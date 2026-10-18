package com.github.auties00.smalt.container.mp4;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.Muxer;
import com.github.auties00.smalt.container.Segment;
import com.github.auties00.smalt.container.SegmentSink;
import com.github.auties00.smalt.internal.io.FourCc;
import com.github.auties00.smalt.internal.tags.MuxerTags;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.TrackKind;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Writes fragmented ISO base media files (ISO/IEC 14496-12 clause 8.8), including CMAF track
 * files, from the packets of the codecs {@link Mp4Muxer} supports: H.264, HEVC, VP9, AV1, AAC,
 * Opus, MP3, ALAC and FLAC.
 *
 * <p>The output starts with an initialization segment, {@code ftyp} and a {@code moov} whose
 * sample tables are empty and whose {@code mvex} declares every track, and continues with one
 * media segment per fragment: a {@code moof} with one {@code traf} per track that has samples in
 * it ({@code tfhd} with {@code default-base-is-moof}, {@code tfdt} with the decoding time of the
 * first sample, and a {@code trun} with the duration, size and flags of every sample, plus
 * composition offsets when they are not all zero), then the {@code mdat} holding each track's
 * samples contiguously in declaration order. Fragments are cut as
 * {@link FragmentedMp4MuxerOptions} describes. The initialization segment is written with the
 * first fragment, so that configurations derived from the first key frame (VP9, AV1) are known.
 *
 * <p>Timing. Each track's media timescale is the denominator of its time base, and its decoding
 * times are the packets' decoding times moved so that the first is zero, or the first decoding
 * time when that is positive, so a track that starts late keeps its offset in its {@code tfdt}.
 * Composition offsets are the gaps between presentation and decoding timestamps, and the first
 * sample's offset (the reordering delay of B-frames) becomes the media time of an edit list, so
 * players and {@link Mp4Demuxer} present the packets at their presentation timestamps. For CMAF
 * video the offsets are instead measured from the first sample's and written as negative values
 * in a version 1 {@code trun} where needed, which needs no edit list. A track whose packets carry
 * presentation timestamps only (H.264 from Matroska) gets decoding times from the sorted
 * presentation times of each fragment, delayed by the reordering of its first fragment. A
 * sample's duration is the gap to the next decoding time of its track, so the last sample of each
 * track before a cut moves to the next fragment unless its successor is already known; the very
 * last sample takes its packet's duration, or else the previous gap. An audio track's priming
 * ({@link AudioTrack#skipSamples()}, plus the Opus pre-skip) becomes the media time of its edit
 * list. An edit written before the length of the track is known has a duration of zero, which
 * in a fragmented file means "to the end of the fragments".
 *
 * <p>Outputs. With a {@link MediaSink} the whole file goes to one sink, written strictly in
 * order, so a streaming sink works; when the sink is seekable, {@link #finish()} also fills in
 * the {@code mehd} fragment duration and the edit list durations, trimming each track to its
 * declared duration, which a streaming sink cannot. With a {@link SegmentSink} the
 * initialization segment and every media segment ({@code styp}, optional {@code sidx},
 * {@code moof}, {@code mdat}) go to a sink of their own, which the muxer closes once written:
 * the shape DASH and HLS packaging needs. A
 * {@code sidx} before each fragment references that fragment, measured on the first video track
 * (or the first track); a {@code mfra} index closes a single-file output when enabled.
 *
 * <p>Single segment index. With {@link FragmentedMp4MuxerOptions.SegmentIndex#SINGLE} the file
 * is laid out for the DASH on-demand profile (ISO/IEC 23009-1 clause 8.3): {@code ftyp} (with the
 * {@code dash} compatible brand) and {@code moov}, one version 1 {@code sidx} that references
 * every fragment as a subsegment (its size, its duration and whether it starts with a stream
 * access point, measured on the reference track as above), then the fragments. The fragments are
 * held until {@link #finish()} (in memory up to a threshold, then in a temporary file), so the
 * output still goes strictly in order to any {@link MediaSink}; {@link #indexLayout()} then gives
 * the byte ranges a DASH {@code SegmentBase} (its {@code Initialization range} and
 * {@code indexRange}) or an HLS byte-range playlist needs. This layout needs a {@link MediaSink}
 * output and at most 65535 fragments.
 *
 * <p>Instances are not thread-safe.
 *
 * @apiNote Use this muxer for streaming, recording that must survive a crash (every finished
 * fragment is playable), Media Source Extensions and adaptive streaming; use {@link Mp4Muxer}
 * for files that are complete when written and should be indexed in a single {@code moov}.
 */
public final class FragmentedMp4Muxer implements Muxer {
    /**
     * Describes one fragment of a file indexed by a single segment index.
     *
     * @param offset the offset of the fragment's {@code moof} from the first byte the muxer wrote
     * @param size the size of the fragment ({@code moof} and {@code mdat}) in bytes
     * @param startMicros the earliest presentation time of the fragment, in microseconds
     * @param durationMicros the duration of the fragment measured on the reference track, in
     * microseconds
     * @param independent whether the fragment starts with a key frame of every video track
     */
    public record Subsegment(long offset, long size, long startMicros, long durationMicros, boolean independent) {
    }

    /**
     * Describes the layout of a file indexed by a single segment index, as the DASH
     * {@code SegmentBase} addressing needs it.
     *
     * <p>Offsets count from the first byte the muxer wrote: the initialization segment
     * ({@code ftyp} and {@code moov}) spans {@code [0, initializationSize)}, the {@code sidx}
     * {@code [indexOffset, indexOffset + indexSize)}, and the subsegments follow it in order.
     *
     * @param initializationSize the size of {@code ftyp} and {@code moov} in bytes
     * @param indexOffset the offset of the {@code sidx}, equal to the initialization size
     * @param indexSize the size of the {@code sidx} in bytes
     * @param subsegments the fragments the {@code sidx} references, in order
     */
    public record IndexLayout(long initializationSize, long indexOffset, long indexSize, List<Subsegment> subsegments) {
        /**
         * Validates the layout and copies the subsegments.
         */
        public IndexLayout {
            subsegments = List.copyOf(subsegments);
        }
    }

    /**
     * The movie timescale of files with a video track.
     */
    private static final int MOVIE_TIMESCALE = 1000;

    /**
     * The largest number of references of one {@code sidx}.
     */
    private static final int MAX_INDEX_REFERENCES = 0xFFFF;

    /**
     * The fragment size at which a fragment is cut whatever its duration or the next key frame.
     */
    private static final long MAX_FRAGMENT_BYTES = 64L << 20;

    /**
     * The sample flags of a sync sample: {@code sample_depends_on = 2}.
     */
    private static final int SYNC_SAMPLE_FLAGS = 0x02000000;

    /**
     * The sample flags of a non-sync sample: {@code sample_depends_on = 1} and
     * {@code sample_is_non_sync_sample}.
     */
    private static final int NON_SYNC_SAMPLE_FLAGS = 0x01010000;

    /**
     * The {@code tfhd} flag making the {@code moof} the base of every data offset.
     */
    private static final int TFHD_DEFAULT_BASE_IS_MOOF = 0x020000;

    /**
     * The {@code trun} flags: data offset, sample duration, size and flags.
     */
    private static final int TRUN_FLAGS = 0x000001 | 0x000100 | 0x000200 | 0x000400;

    /**
     * The {@code trun} flag announcing composition offsets.
     */
    private static final int TRUN_COMPOSITION = 0x000800;

    /**
     * The single-file output, or {@code null} for a segmented output.
     */
    private final MediaSink output;

    /**
     * The segmented output, or {@code null} for a single-file output.
     */
    private final SegmentSink segments;

    /**
     * The configuration.
     */
    private final FragmentedMp4MuxerOptions options;

    /**
     * The single-file output position at construction, where the file starts.
     */
    private final long base;

    /**
     * The declared tracks, in declaration order.
     */
    private final List<FragmentTrack> tracks = new ArrayList<>();

    /**
     * The track that decides fragment boundaries and indexes: the first video track, or the
     * first track.
     */
    private FragmentTrack reference;

    /**
     * The decoding time of the earliest sample of the fragment being assembled, in microseconds.
     */
    private long fragmentStartMicros;

    /**
     * The number of samples waiting for the next fragment.
     */
    private int pendingSamples;

    /**
     * The number of sample bytes waiting for the next fragment.
     */
    private long pendingBytes;

    /**
     * The number of fragments written.
     */
    private int sequence;

    /**
     * The absolute offset of the {@code mehd} fragment duration, or {@code -1}.
     */
    private long mehdOffset = -1;

    /**
     * The movie timescale.
     */
    private long movieTimescale;

    /**
     * The {@code udta} box the tags were encoded to, empty without tags.
     */
    private byte[] userData = new byte[0];

    /**
     * Whether a packet was accepted.
     */
    private boolean started;

    /**
     * Whether the initialization segment was written.
     */
    private boolean initialized;

    /**
     * The spool holding the fragments of a single segment index until {@link #finish()}, or
     * {@code null}.
     */
    private MdatSpool spool;

    /**
     * The fragments spooled for a single segment index: offset in the spool, size, earliest
     * presentation time and duration in the reference track's timescale, start time and duration
     * in microseconds, and whether the fragment starts with a stream access point and is
     * independent (1 or 0), seven values per fragment.
     */
    private long[] spooled = new long[7 * 16];

    /**
     * The number of fragments spooled for a single segment index.
     */
    private int spooledCount;

    /**
     * The layout of a single-index file, known after {@link #finish()}, or {@code null}.
     */
    private IndexLayout layout;

    /**
     * Whether {@link #finish()} or {@link #close()} ran.
     */
    private boolean finished;

    /**
     * Constructs a muxer writing a fragmented MP4 to a sink with
     * {@link FragmentedMp4MuxerOptions#defaults()}.
     *
     * @param output the sink, written from its position; not closed by this muxer
     * @throws MediaException.Io if the sink position cannot be read
     */
    public FragmentedMp4Muxer(MediaSink output) {
        this(output, FragmentedMp4MuxerOptions.defaults());
    }

    /**
     * Constructs a muxer writing a single fragmented file to a sink.
     *
     * @param output the sink, written from its position; not closed by this muxer
     * @param options the configuration
     * @throws MediaException.Io if the sink position cannot be read
     */
    public FragmentedMp4Muxer(MediaSink output, FragmentedMp4MuxerOptions options) {
        this.output = Objects.requireNonNull(output, "output");
        this.segments = null;
        this.options = Objects.requireNonNull(options, "options");
        this.base = output.position();
    }

    /**
     * Constructs a muxer writing the initialization segment and every media segment to sinks of
     * their own.
     *
     * @param segments the provider of the segment sinks
     * @param options the configuration; its {@code mfra} setting is ignored
     * @throws IllegalArgumentException if the options ask for a
     * {@linkplain FragmentedMp4MuxerOptions.SegmentIndex#SINGLE single segment index}
     */
    public FragmentedMp4Muxer(SegmentSink segments, FragmentedMp4MuxerOptions options) {
        this.output = null;
        this.segments = Objects.requireNonNull(segments, "segments");
        this.options = Objects.requireNonNull(options, "options");
        if (options.segmentIndex() == FragmentedMp4MuxerOptions.SegmentIndex.SINGLE) {
            throw new IllegalArgumentException("a single segment index needs a MediaSink output");
        }
        this.base = 0;
    }

    /**
     * Returns whether fragmented MP4 files can carry a codec.
     *
     * @param codec the codec to look up
     * @return {@code true} for H.264, HEVC, VP9, AV1, AAC, Opus, MP3, ALAC, FLAC, AMR-NB, AMR-WB,
     * AC-3 and E-AC-3
     */
    public static boolean supports(Codec codec) {
        return switch (Objects.requireNonNull(codec, "codec")) {
            case H264, HEVC, VP9, AV1, AAC, OPUS, MP3, ALAC, FLAC, AMR_NB, AMR_WB, AC3, EAC3 -> true;
            default -> false;
        };
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if the id is already used, the codec is not supported, the
     * configuration it needs is missing, or a CMAF file would get a second track
     */
    @Override
    public void addTrack(Track track) {
        Objects.requireNonNull(track, "track");
        if (started || finished) {
            throw new IllegalStateException("tracks must be declared before the first packet");
        }
        for (var existing : tracks) {
            if (existing.media.track.id() == track.id()) {
                throw new IllegalArgumentException("track id " + track.id() + " is already used");
            }
        }
        if (options.brand() == FragmentedMp4MuxerOptions.Brand.CMAF && !tracks.isEmpty()) {
            throw new IllegalArgumentException("a CMAF track file carries one track");
        }
        var codec = track.codec();
        if (!supports(codec)) {
            throw new IllegalArgumentException("fragmented MP4 muxing of " + codec.displayName() + " is not supported");
        }
        MovieHeaderWriter.validateConfig(track);
        tracks.add(new FragmentTrack(new MuxTrack(track, tracks.size() + 1),
                options.brand() == FragmentedMp4MuxerOptions.Brand.CMAF));
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code true}
     */
    @Override
    public boolean supportsTags() {
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The tags become a {@code udta} box at the end of the initialization segment's
     * {@code moov}: an iTunes {@code ilst} with cover art in {@code covr}, and a Nero {@code chpl}
     * box for the chapters.
     */
    @Override
    public void setTags(MediaTags tags) {
        Objects.requireNonNull(tags, "tags");
        if (started || initialized || finished) {
            throw new IllegalStateException("tags must be set before the first packet");
        }
        this.userData = MuxerTags.mp4UserData(tags);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Timestamps may be {@link Packet#NO_TIMESTAMP}: a missing presentation timestamp equals
     * the decoding timestamp, a missing decoding timestamp follows the previous packet, and a
     * track whose first packet has a presentation timestamp only is treated as
     * presentation-timed throughout.
     *
     * @throws IllegalArgumentException if the track was not declared, its decoding timestamps
     * decrease, or a timestamp overflows the media timescale
     */
    @Override
    public void writePacket(Packet packet) {
        Objects.requireNonNull(packet, "packet");
        if (finished) {
            throw new IllegalStateException("the muxer is finished");
        }
        FragmentTrack track = null;
        for (var candidate : tracks) {
            if (candidate.media.track.id() == packet.trackId()) {
                track = candidate;
            }
        }
        if (track == null) {
            throw new IllegalArgumentException("packet for undeclared track " + packet.trackId());
        }
        if (!started) {
            started = true;
            for (var candidate : tracks) {
                if (candidate.video && reference == null) {
                    reference = candidate;
                }
            }
            if (reference == null) {
                reference = tracks.getFirst();
            }
        }
        var sample = track.toSample(packet);
        var micros = track.media.toMicros(sample.dts);
        if (pendingSamples > 0 && shouldCut(track, sample, micros)) {
            flush(track, sample.pts, false);
        }
        track.pending.add(sample);
        if (pendingSamples == 0 || micros < fragmentStartMicros) {
            fragmentStartMicros = micros;
        }
        pendingSamples++;
        pendingBytes += sample.data.length;
    }

    /**
     * Returns whether a sample starts a new fragment.
     *
     * @param track the sample's track
     * @param sample the sample about to be added
     * @param micros its decoding time in microseconds
     * @return {@code true} when the fragment reached its duration (at a reference key frame, when
     * fragments are aligned) or its size limit
     */
    private boolean shouldCut(FragmentTrack track, Sample sample, long micros) {
        if (pendingBytes >= MAX_FRAGMENT_BYTES) {
            return true;
        }
        var longEnough = micros - fragmentStartMicros >= options.fragmentMicros();
        if (options.keyframeAligned() && reference.video) {
            return longEnough && track == reference && sample.key;
        }
        return longEnough;
    }

    /**
     * {@inheritDoc}
     *
     * <p>A muxer without packets writes the initialization segment only (and, with a single
     * segment index, a {@code sidx} without references).
     *
     * @throws IllegalStateException if the muxer is already finished, or a VP9 or AV1 track has no
     * configuration and no key frame it can be derived from
     * @throws MediaException.Unsupported if a fragment is too large for a single segment index
     */
    @Override
    public void finish() {
        if (finished) {
            throw new IllegalStateException("the muxer is already finished");
        }
        finished = true;
        if (pendingSamples > 0) {
            flush(null, Packet.NO_TIMESTAMP, true);
        }
        if (!initialized) {
            writeInit();
        }
        if (output == null) {
            return;
        }
        if (options.segmentIndex() == FragmentedMp4MuxerOptions.SegmentIndex.SINGLE) {
            writeSingleIndex();
        }
        if (options.randomAccessIndex()) {
            writeRandomAccessIndex();
        }
        if (output.isSeekable()) {
            var end = output.position();
            patchDurations();
            output.position(end);
        }
    }

    /**
     * Marks the muxer finished without writing the samples still waiting for a fragment; the
     * output is not closed.
     */
    @Override
    public void close() {
        finished = true;
        if (spool != null) {
            spool.close();
            spool = null;
        }
    }

    /**
     * Returns the layout of a file indexed by a single segment index.
     *
     * @return the layout, present once {@link #finish()} has written a file with
     * {@link FragmentedMp4MuxerOptions.SegmentIndex#SINGLE}, empty otherwise
     */
    public Optional<IndexLayout> indexLayout() {
        return Optional.ofNullable(layout);
    }

    /**
     * Writes the samples waiting for a fragment as one fragment.
     *
     * @param trigger the track of the sample that caused the cut, or {@code null}
     * @param nextPts the media presentation time of that sample, or {@link Packet#NO_TIMESTAMP}
     * @param last whether this is the final fragment, which takes every waiting sample
     */
    private void flush(FragmentTrack trigger, long nextPts, boolean last) {
        var runs = new ArrayList<Run>();
        for (var track : tracks) {
            var run = track.takeRun(track == trigger ? nextPts : Packet.NO_TIMESTAMP, last);
            if (run != null) {
                runs.add(run);
            }
        }
        pendingSamples = 0;
        pendingBytes = 0;
        fragmentStartMicros = Long.MAX_VALUE;
        for (var track : tracks) {
            for (var sample : track.pending) {
                pendingSamples++;
                pendingBytes += sample.data.length;
                fragmentStartMicros = Math.min(fragmentStartMicros, track.media.toMicros(sample.dts));
            }
        }
        if (runs.isEmpty()) {
            return;
        }
        if (!initialized) {
            writeInit();
        }
        sequence++;
        writeFragment(runs);
    }

    /**
     * Writes the initialization segment.
     */
    private void writeInit() {
        initialized = true;
        movieTimescale = MOVIE_TIMESCALE;
        var audioOnly = !tracks.isEmpty();
        for (var track : tracks) {
            audioOnly &= !track.video;
        }
        if (audioOnly) {
            movieTimescale = tracks.getFirst().media.timescale;
        }
        var writer = new BoxWriter(4096);
        writer.begin(BoxTypes.FTYP);
        writer.u32(FourCc.of("iso6"));
        writer.u32(0);
        writer.u32(FourCc.of("iso6"));
        if (options.brand() == FragmentedMp4MuxerOptions.Brand.CMAF) {
            writer.u32(FourCc.of("cmfc"));
        }
        if (options.segmentIndex() == FragmentedMp4MuxerOptions.SegmentIndex.SINGLE) {
            writer.u32(FourCc.of("dash"));
        }
        writer.u32(BoxTypes.ISOM);
        writer.u32(BoxTypes.MP41);
        writer.end();
        writer.begin(BoxTypes.MOOV);
        MovieHeaderWriter.writeMovieHeader(writer, movieTimescale, 0, tracks.size() + 1);
        var patchable = output != null && output.isSeekable();
        for (var track : tracks) {
            writeTrack(writer, track, patchable);
        }
        writer.begin(BoxTypes.MVEX);
        if (patchable) {
            writer.beginFull(BoxTypes.MEHD, 1, 0);
            mehdOffset = writer.size();
            writer.u64(0);
            writer.end();
        }
        for (var track : tracks) {
            writer.beginFull(BoxTypes.TREX, 0, 0);
            writer.u32(track.media.number);
            writer.u32(1);
            writer.u32(0);
            writer.u32(0);
            writer.u32(0);
            writer.end();
        }
        writer.end();
        writer.bytes(userData);
        writer.end();
        if (output != null) {
            var start = output.position();
            if (mehdOffset >= 0) {
                mehdOffset += start;
            }
            for (var track : tracks) {
                if (track.elstOffset >= 0) {
                    track.elstOffset += start;
                }
            }
            output.write(ByteBuffer.wrap(writer.buffer(), 0, writer.size()));
        } else {
            try (var sink = segments.open(new Segment(Segment.Kind.INIT, 0, 0, 0, true, writer.size()))) {
                sink.write(ByteBuffer.wrap(writer.buffer(), 0, writer.size()));
            }
        }
    }

    /**
     * Serializes the {@code trak} box of a track, with empty sample tables.
     *
     * @param writer the destination of the box
     * @param track the track the box describes
     * @param patchable whether the edit list duration is patched by {@link #finish()}
     */
    private void writeTrack(BoxWriter writer, FragmentTrack track, boolean patchable) {
        var media = track.media;
        writer.begin(BoxTypes.TRAK);
        MovieHeaderWriter.writeTrackHeader(writer, media, 0);
        var mediaTime = track.mediaTime();
        if (mediaTime > 0) {
            writer.begin(BoxTypes.EDTS);
            writer.beginFull(BoxTypes.ELST, 1, 0);
            writer.u32(1);
            if (patchable) {
                track.elstOffset = writer.size();
            }
            writer.u64(0);
            writer.u64(mediaTime);
            writer.u32(0x00010000);
            writer.end();
            writer.end();
        }
        MovieHeaderWriter.writeMedia(writer, media, 0, table -> writeEmptySampleTable(table, media));
        writer.end();
    }

    /**
     * Writes an {@code stbl} box with the sample description of a track and empty tables, as
     * the samples of a fragmented file live in its fragments.
     *
     * @param writer the destination
     * @param media the track
     */
    private static void writeEmptySampleTable(BoxWriter writer, MuxTrack media) {
        writer.begin(BoxTypes.STBL);
        writer.beginFull(BoxTypes.STSD, 0, 0);
        writer.u32(1);
        SampleEntryWriter.write(writer, new Mp4Muxer.TrackPlan(media));
        writer.end();
        for (var table : new int[]{BoxTypes.STTS, BoxTypes.STSC, BoxTypes.STCO}) {
            writer.beginFull(table, 0, 0);
            writer.u32(0);
            writer.end();
        }
        writer.beginFull(BoxTypes.STSZ, 0, 0);
        writer.u32(0);
        writer.u32(0);
        writer.end();
        writer.end();
    }

    /**
     * Writes one fragment: the optional {@code styp} and {@code sidx}, the {@code moof} and the
     * {@code mdat}.
     *
     * @param runs the samples of each track in the fragment, in declaration order
     */
    private void writeFragment(List<Run> runs) {
        var moof = new BoxWriter(1024);
        moof.begin(BoxTypes.MOOF);
        moof.beginFull(BoxTypes.MFHD, 0, 0);
        moof.u32(sequence);
        moof.end();
        var dataOffsetFields = new int[runs.size()];
        for (var index = 0; index < runs.size(); index++) {
            var run = runs.get(index);
            moof.begin(BoxTypes.TRAF);
            moof.beginFull(BoxTypes.TFHD, 0, TFHD_DEFAULT_BASE_IS_MOOF);
            moof.u32(run.track.media.number);
            moof.end();
            moof.beginFull(BoxTypes.TFDT, 1, 0);
            moof.u64(run.decode[0]);
            moof.end();
            var composition = false;
            var negative = false;
            for (var offset : run.offsets) {
                composition |= offset != 0;
                negative |= offset < 0;
            }
            moof.beginFull(BoxTypes.TRUN, negative ? 1 : 0, TRUN_FLAGS | (composition ? TRUN_COMPOSITION : 0));
            moof.u32(run.samples.length);
            dataOffsetFields[index] = moof.size();
            moof.u32(0);
            for (var sample = 0; sample < run.samples.length; sample++) {
                moof.u32(run.durations[sample]);
                moof.u32(run.samples[sample].data.length);
                moof.u32(run.samples[sample].key ? SYNC_SAMPLE_FLAGS : NON_SYNC_SAMPLE_FLAGS);
                if (composition) {
                    moof.u32(run.offsets[sample] & 0xFFFFFFFFL);
                }
            }
            moof.end();
            moof.end();
        }
        moof.end();
        var dataSize = 0L;
        for (var run : runs) {
            dataSize += run.bytes;
        }
        var mdatHeader = 8 + dataSize > 0xFFFFFFFFL ? 16 : 8;
        var position = (long) moof.size() + mdatHeader;
        for (var index = 0; index < runs.size(); index++) {
            moof.patch32(dataOffsetFields[index], position);
            position += runs.get(index).bytes;
        }
        var prefix = new BoxWriter(128);
        if (output == null) {
            prefix.begin(BoxTypes.STYP);
            if (options.brand() == FragmentedMp4MuxerOptions.Brand.CMAF) {
                prefix.u32(FourCc.of("cmfs"));
                prefix.u32(0);
                prefix.u32(FourCc.of("cmfs"));
                prefix.u32(FourCc.of("cmff"));
            } else {
                prefix.u32(FourCc.of("msdh"));
                prefix.u32(0);
            }
            prefix.u32(FourCc.of("msdh"));
            if (options.segmentIndex() == FragmentedMp4MuxerOptions.SegmentIndex.PER_FRAGMENT) {
                prefix.u32(FourCc.of("msix"));
            }
            prefix.end();
        }
        var indexRun = runs.getFirst();
        for (var run : runs) {
            if (run.track == reference) {
                indexRun = run;
            }
        }
        if (options.segmentIndex() == FragmentedMp4MuxerOptions.SegmentIndex.PER_FRAGMENT) {
            var earliest = Long.MAX_VALUE;
            var duration = 0L;
            for (var sample = 0; sample < indexRun.samples.length; sample++) {
                earliest = Math.min(earliest, indexRun.track.presentation(indexRun.decode[sample] + indexRun.offsets[sample]));
                duration += indexRun.durations[sample];
            }
            prefix.beginFull(BoxTypes.SIDX, 1, 0);
            prefix.u32(indexRun.track.media.number);
            prefix.u32(indexRun.track.media.timescale);
            prefix.u64(earliest);
            prefix.u64(0);
            prefix.u16(0);
            prefix.u16(1);
            prefix.u32(Math.min(moof.size() + mdatHeader + dataSize, 0x7FFFFFFFL));
            prefix.u32(Math.min(duration, 0xFFFFFFFFL));
            prefix.u32(indexRun.samples[0].key ? 0x90000000L : 0);
            prefix.end();
        }
        var mdat = new BoxWriter(16);
        if (mdatHeader == 16) {
            mdat.u32(1);
            mdat.u32(BoxTypes.MDAT);
            mdat.u64(16 + dataSize);
        } else {
            mdat.u32(8 + dataSize);
            mdat.u32(BoxTypes.MDAT);
        }
        var independent = true;
        var start = Long.MAX_VALUE;
        for (var trafIndex = 0; trafIndex < runs.size(); trafIndex++) {
            var run = runs.get(trafIndex);
            independent &= !run.track.video || run.samples[0].key;
            for (var sample = 0; sample < run.samples.length; sample++) {
                start = Math.min(start, run.track.media.toMicros(run.track.presentation(run.decode[sample] + run.offsets[sample])));
            }
        }
        var indexDuration = 0L;
        for (var duration : indexRun.durations) {
            indexDuration += duration;
        }
        if (options.segmentIndex() == FragmentedMp4MuxerOptions.SegmentIndex.SINGLE) {
            spoolFragment(runs, indexRun, moof, mdat, start, indexRun.track.media.toMicros(indexDuration), independent);
            return;
        }
        MediaSink sink;
        if (output != null) {
            sink = output;
        } else {
            var size = (long) prefix.size() + moof.size() + mdat.size() + dataSize;
            sink = segments.open(new Segment(Segment.Kind.MEDIA, sequence, start, indexRun.track.media.toMicros(indexDuration),
                    independent, size));
        }
        try {
            sink.write(ByteBuffer.wrap(prefix.buffer(), 0, prefix.size()));
            var moofOffset = output != null ? output.position() - base : -1;
            for (var trafIndex = 0; trafIndex < runs.size(); trafIndex++) {
                var run = runs.get(trafIndex);
                if (moofOffset >= 0 && (run.samples[0].key || !run.track.video)) {
                    run.track.addRandomAccess(run.track.presentation(run.decode[0] + run.offsets[0]), moofOffset, trafIndex + 1);
                }
            }
            sink.write(ByteBuffer.wrap(moof.buffer(), 0, moof.size()));
            sink.write(ByteBuffer.wrap(mdat.buffer(), 0, mdat.size()));
            for (var run : runs) {
                for (var sample : run.samples) {
                    sink.write(ByteBuffer.wrap(sample.data));
                }
            }
        } finally {
            if (output == null) {
                sink.close();
            }
        }
    }

    /**
     * Appends a fragment to the spool of a single segment index and records its reference.
     *
     * @param runs the runs of the fragment
     * @param indexRun the run of the reference track
     * @param moof the serialized {@code moof}
     * @param mdat the serialized {@code mdat} header
     * @param startMicros the earliest presentation time of the fragment, in microseconds
     * @param durationMicros the duration of the fragment on the reference track, in microseconds
     * @param independent whether the fragment starts with a key frame of every video track
     * @throws MediaException.Unsupported if the file already holds the most fragments one
     * {@code sidx} can reference
     */
    private void spoolFragment(List<Run> runs, Run indexRun, BoxWriter moof, BoxWriter mdat, long startMicros,
                               long durationMicros, boolean independent) {
        if (spool == null) {
            spool = new MdatSpool(options.temporaryDirectory().orElse(null));
        }
        if (spooledCount == MAX_INDEX_REFERENCES) {
            throw new MediaException.Unsupported("sidx", "a single segment index references at most "
                    + MAX_INDEX_REFERENCES + " fragments");
        }
        var offset = spool.size();
        for (var trafIndex = 0; trafIndex < runs.size(); trafIndex++) {
            var run = runs.get(trafIndex);
            if (run.samples[0].key || !run.track.video) {
                run.track.addRandomAccess(run.track.presentation(run.decode[0] + run.offsets[0]), offset, trafIndex + 1);
            }
        }
        spool.write(moof.buffer(), 0, moof.size());
        spool.write(mdat.buffer(), 0, mdat.size());
        for (var run : runs) {
            for (var sample : run.samples) {
                spool.write(sample.data, 0, sample.data.length);
            }
        }
        var earliest = Long.MAX_VALUE;
        var duration = 0L;
        for (var sample = 0; sample < indexRun.samples.length; sample++) {
            earliest = Math.min(earliest, indexRun.track.presentation(indexRun.decode[sample] + indexRun.offsets[sample]));
            duration += indexRun.durations[sample];
        }
        if (7 * spooledCount + 7 > spooled.length) {
            spooled = Arrays.copyOf(spooled, spooled.length * 2);
        }
        var at = 7 * spooledCount++;
        spooled[at] = offset;
        spooled[at + 1] = spool.size() - offset;
        spooled[at + 2] = earliest;
        spooled[at + 3] = duration;
        spooled[at + 4] = startMicros;
        spooled[at + 5] = durationMicros;
        spooled[at + 6] = (indexRun.samples[0].key ? 2 : 0) | (independent ? 1 : 0);
    }

    /**
     * Writes the single segment index after the initialization segment, then the spooled
     * fragments, and records the layout.
     */
    private void writeSingleIndex() {
        var initializationSize = output.position() - base;
        var writer = new BoxWriter(32 + 12 * spooledCount);
        writer.beginFull(BoxTypes.SIDX, 1, 0);
        writer.u32(reference == null ? 1 : reference.media.number);
        writer.u32(reference == null ? MOVIE_TIMESCALE : reference.media.timescale);
        writer.u64(spooledCount == 0 ? 0 : spooled[2]);
        writer.u64(0);
        writer.u16(0);
        writer.u16(spooledCount);
        for (var index = 0; index < spooledCount; index++) {
            var at = 7 * index;
            if (spooled[at + 1] > 0x7FFFFFFFL || spooled[at + 3] > 0xFFFFFFFFL) {
                throw new MediaException.Unsupported("sidx", "fragment " + (index + 1) + " is too large for a segment index");
            }
            writer.u32(spooled[at + 1]);
            writer.u32(spooled[at + 3]);
            writer.u32((spooled[at + 6] & 2) != 0 ? 0x90000000L : 0);
        }
        writer.end();
        output.write(ByteBuffer.wrap(writer.buffer(), 0, writer.size()));
        var dataOffset = initializationSize + writer.size();
        var subsegments = new ArrayList<Subsegment>(spooledCount);
        for (var index = 0; index < spooledCount; index++) {
            var at = 7 * index;
            subsegments.add(new Subsegment(dataOffset + spooled[at], spooled[at + 1], spooled[at + 4], spooled[at + 5],
                    (spooled[at + 6] & 1) != 0));
        }
        for (var track : tracks) {
            for (var index = 0; index < track.randomAccessCount; index++) {
                track.randomAccess[3 * index + 1] += dataOffset;
            }
        }
        if (spool != null) {
            try {
                spool.transferTo(output);
            } finally {
                spool.close();
                spool = null;
            }
        }
        layout = new IndexLayout(initializationSize, initializationSize, writer.size(), subsegments);
    }

    /**
     * Writes the {@code mfra} box: one {@code tfra} per track listing the fragments that start
     * with a sync sample, then the {@code mfro}.
     */
    private void writeRandomAccessIndex() {
        var writer = new BoxWriter(256);
        writer.begin(BoxTypes.MFRA);
        for (var track : tracks) {
            writer.beginFull(FourCc.of("tfra"), 1, 0);
            writer.u32(track.media.number);
            writer.u32(0);
            writer.u32(track.randomAccessCount);
            for (var index = 0; index < track.randomAccessCount; index++) {
                writer.u64(track.randomAccess[3 * index]);
                writer.u64(track.randomAccess[3 * index + 1]);
                writer.u8((int) Math.min(track.randomAccess[3 * index + 2], 0xFF));
                writer.u8(1);
                writer.u8(1);
            }
            writer.end();
        }
        writer.beginFull(FourCc.of("mfro"), 0, 0);
        writer.u32(0);
        writer.end();
        writer.end();
        writer.patch32(writer.size() - 4, writer.size());
        output.write(ByteBuffer.wrap(writer.buffer(), 0, writer.size()));
    }

    /**
     * Fills in the {@code mehd} fragment duration and the edit list durations of a seekable
     * single-file output.
     */
    private void patchDurations() {
        var longest = 0L;
        for (var track : tracks) {
            var media = track.media;
            var presented = Math.max(track.presentationEnd - track.mediaTime(), 0);
            var declared = media.track.duration();
            if (declared > 0) {
                presented = Math.min(presented, media.toMedia(declared));
            }
            var movie = Mp4Track.rescale(presented, media.timescale, movieTimescale);
            longest = Math.max(longest, movie);
            if (track.elstOffset >= 0) {
                var field = new BoxWriter(8);
                field.u64(movie);
                output.position(track.elstOffset);
                output.write(ByteBuffer.wrap(field.buffer(), 0, field.size()));
            }
        }
        if (mehdOffset >= 0) {
            var field = new BoxWriter(8);
            field.u64(longest);
            output.position(mehdOffset);
            output.write(ByteBuffer.wrap(field.buffer(), 0, field.size()));
        }
    }

    /**
     * Holds one sample waiting for a fragment, with its timing in media timescale units before
     * the track's decode-time mapping.
     */
    private static final class Sample {
        /**
         * The sample bytes, held by reference.
         */
        final byte[] data;

        /**
         * The presentation time.
         */
        final long pts;

        /**
         * The decoding time; for a presentation-timed track, the presentation time until the
         * fragment's decoding times are derived.
         */
        final long dts;

        /**
         * The duration the packet declared, or {@code 0}.
         */
        final long declared;

        /**
         * Whether the sample is a sync sample.
         */
        final boolean key;

        /**
         * The gap to the next decoding time of the track, or {@code -1} until it is known.
         */
        long duration = -1;

        /**
         * Constructs a sample.
         *
         * @param data the bytes
         * @param pts the presentation time
         * @param dts the decoding time
         * @param declared the declared duration
         * @param key whether it is a sync sample
         */
        Sample(byte[] data, long pts, long dts, long declared, boolean key) {
            this.data = data;
            this.pts = pts;
            this.dts = dts;
            this.declared = declared;
            this.key = key;
        }
    }

    /**
     * Holds the samples of one track in one fragment, with their final timing.
     */
    private static final class Run {
        /**
         * The track.
         */
        final FragmentTrack track;

        /**
         * The samples, in decoding order.
         */
        final Sample[] samples;

        /**
         * The media decoding time of each sample.
         */
        final long[] decode;

        /**
         * The duration of each sample.
         */
        final long[] durations;

        /**
         * The composition offset of each sample.
         */
        final int[] offsets;

        /**
         * The number of sample bytes.
         */
        final long bytes;

        /**
         * Constructs a run.
         *
         * @param track the track the samples belong to
         * @param samples the samples of the run, in decoding order
         * @param decode the decoding times
         * @param durations the duration of each sample, in media time-base units
         * @param offsets the composition offsets
         */
        Run(FragmentTrack track, Sample[] samples, long[] decode, long[] durations, int[] offsets) {
            this.track = track;
            this.samples = samples;
            this.decode = decode;
            this.durations = durations;
            this.offsets = offsets;
            var total = 0L;
            for (var sample : samples) {
                total += sample.data.length;
            }
            this.bytes = total;
        }
    }

    /**
     * Holds the timing state of one output track.
     *
     * <p>Decoding times in the file are the packets' decoding times moved so that the first one
     * is {@link #start}: the first decoding time, or zero when it is negative. With positive
     * composition offsets the composition times are then the presentation times moved the same
     * way, and an edit whose media time is {@link #mediaTime()} maps them back; with negative
     * offsets (CMAF video) the offsets are measured from the first sample's, so the composition
     * times equal the presentation times and no edit is needed.
     */
    private static final class FragmentTrack {
        /**
         * The track description, timescale and configuration.
         */
        final MuxTrack media;

        /**
         * Whether the track is video.
         */
        final boolean video;

        /**
         * Whether composition offsets are measured from the first sample's, so they may be
         * negative, instead of signaling the first offset with an edit list.
         */
        final boolean negativeOffsets;

        /**
         * The samples waiting for a fragment, in decoding order.
         */
        final List<Sample> pending = new ArrayList<>();

        /**
         * Whether the packets carry presentation timestamps only.
         */
        boolean ptsOnly;

        /**
         * Whether a packet was seen.
         */
        boolean seen;

        /**
         * Whether the timing origin is fixed.
         */
        boolean mapped;

        /**
         * The first decoding time, before mapping.
         */
        long firstDecode;

        /**
         * The first presentation time, before mapping.
         */
        long firstPresentation;

        /**
         * The mapped decoding time of the first sample.
         */
        long start;

        /**
         * The reorder delay subtracted from the sorted presentation times of a
         * presentation-timed track to derive its decoding times.
         */
        long reorderDelay;

        /**
         * The decoding time of the previous packet, before mapping.
         */
        long lastDts;

        /**
         * The presentation time of the previous packet, before mapping.
         */
        long lastPts;

        /**
         * The duration of the last sample.
         */
        long lastDuration;

        /**
         * The declared duration of the previous packet.
         */
        long lastDeclared;

        /**
         * The latest composition time plus duration of a written sample.
         */
        long presentationEnd;

        /**
         * The absolute offset of the edit list duration, or {@code -1}.
         */
        long elstOffset = -1;

        /**
         * The random access entries: presentation time, {@code moof} offset and {@code traf}
         * number, three longs per entry.
         */
        long[] randomAccess = new long[48];

        /**
         * The number of random access entries.
         */
        int randomAccessCount;

        /**
         * Constructs the state of a track.
         *
         * @param media the track description
         * @param negativeOffsets whether video composition offsets are measured from the first
         * sample's
         */
        FragmentTrack(MuxTrack media, boolean negativeOffsets) {
            this.media = media;
            this.video = media.kind() == TrackKind.VIDEO;
            this.negativeOffsets = negativeOffsets && video;
        }

        /**
         * Fixes the timing origin from the first decoding and presentation times.
         *
         * @param decode the first decoding time
         * @param presentation the first presentation time
         */
        private void map(long decode, long presentation) {
            mapped = true;
            firstDecode = decode;
            firstPresentation = presentation;
            start = Math.max(negativeOffsets ? presentation : decode, 0);
        }

        /**
         * Returns the media time of the edit that maps composition times to presentation times.
         *
         * <p>For audio this also covers the priming ({@link AudioTrack#skipSamples()} plus the
         * Opus pre-skip); when the packets already start at a negative time that accounts for
         * the priming, as the Ogg demuxer's Opus packets do, the larger of the two is used, so
         * the priming is not counted twice.
         *
         * @return the media time, non-negative; zero when the track needs no edit
         */
        long mediaTime() {
            if (!mapped) {
                if (!seen) {
                    return video ? 0 : media.priming();
                }
                var first = pending.isEmpty() ? null : pending.getFirst();
                map(first == null ? lastDts : first.dts, first == null ? lastPts : first.pts);
            }
            if (negativeOffsets) {
                return 0;
            }
            var lead = start - firstDecode;
            return video ? lead : Math.max(media.priming(), lead);
        }

        /**
         * Converts a mapped composition time to a presentation time.
         *
         * @param composition the composition time
         * @return the presentation time, non-negative
         */
        long presentation(long composition) {
            return Math.max(0, composition - mediaTime());
        }

        /**
         * Converts a packet into a sample, completing the duration of the previous sample.
         *
         * @param packet the packet of the track
         * @return the sample
         * @throws IllegalArgumentException if the decoding timestamps decrease or overflow
         */
        Sample toSample(Packet packet) {
            if (!seen) {
                ptsOnly = packet.dts() == Packet.NO_TIMESTAMP && packet.pts() != Packet.NO_TIMESTAMP;
            }
            var declared = packet.duration() > 0 ? media.toMedia(packet.duration()) : 0;
            var step = lastDeclared > 0 ? lastDeclared : Math.max(lastDuration, 0);
            long dts;
            long pts;
            if (ptsOnly) {
                pts = packet.pts() != Packet.NO_TIMESTAMP ? media.toMedia(packet.pts()) : lastPts + step;
                dts = pts;
            } else {
                dts = packet.dts() != Packet.NO_TIMESTAMP ? media.toMedia(packet.dts())
                        : seen ? lastDts + step : 0;
                pts = packet.pts() != Packet.NO_TIMESTAMP ? media.toMedia(packet.pts()) : dts;
                if (seen && dts < lastDts) {
                    throw new IllegalArgumentException("decoding timestamps of track " + media.track.id() + " decrease: "
                            + lastDts + " then " + dts);
                }
                if (!pending.isEmpty()) {
                    pending.getLast().duration = dts - pending.getLast().dts;
                }
            }
            if (seen) {
                lastDuration = ptsOnly ? Math.abs(pts - lastPts) : dts - lastDts;
            }
            if (!seen && media.firstBytes == null) {
                media.firstBytes = Arrays.copyOf(packet.data(),
                        Math.min(packet.size(), MuxTrack.firstBytesLength(media.track.codec())));
            }
            if (packet.keyframe()) {
                media.deriveConfig(packet.data(), 0, packet.size());
            }
            seen = true;
            lastDts = dts;
            lastPts = pts;
            lastDeclared = declared;
            return new Sample(packet.data(), pts, dts, declared, !video || packet.keyframe());
        }

        /**
         * Takes the samples that go into the next fragment and fixes their timing.
         *
         * @param nextPts the presentation time of the track's next sample, or
         * {@link Packet#NO_TIMESTAMP}
         * @param last whether this is the final fragment
         * @return the run, or {@code null} when no sample is ready
         */
        Run takeRun(long nextPts, boolean last) {
            if (pending.isEmpty()) {
                return null;
            }
            int count;
            long[] decode;
            long[] durations;
            if (ptsOnly) {
                count = pending.size();
                decode = new long[count];
                for (var index = 0; index < count; index++) {
                    decode[index] = pending.get(index).pts;
                }
                Arrays.sort(decode);
                durations = new long[count];
                for (var index = 0; index + 1 < count; index++) {
                    durations[index] = decode[index + 1] - decode[index];
                }
                durations[count - 1] = nextPts != Packet.NO_TIMESTAMP && nextPts > decode[count - 1]
                        ? nextPts - decode[count - 1] : finalDuration(pending.getLast(), count > 1 ? durations[count - 2] : 0);
                if (!mapped) {
                    for (var index = 0; index < count; index++) {
                        reorderDelay = Math.max(reorderDelay, decode[index] - pending.get(index).pts);
                    }
                }
                for (var index = 0; index < count; index++) {
                    decode[index] -= reorderDelay;
                }
            } else {
                count = last ? pending.size() : 0;
                if (!last) {
                    while (count < pending.size() && pending.get(count).duration >= 0) {
                        count++;
                    }
                }
                if (count == 0) {
                    return null;
                }
                decode = new long[count];
                durations = new long[count];
                for (var index = 0; index < count; index++) {
                    var sample = pending.get(index);
                    decode[index] = sample.dts;
                    durations[index] = sample.duration >= 0 ? sample.duration
                            : finalDuration(sample, index > 0 ? durations[index - 1] : lastDuration);
                }
            }
            var samples = pending.subList(0, count).toArray(new Sample[0]);
            pending.subList(0, count).clear();
            if (!mapped) {
                map(decode[0], samples[0].pts);
            }
            var originOffset = negativeOffsets ? firstPresentation - firstDecode : 0;
            var offsets = new int[count];
            for (var index = 0; index < count; index++) {
                var offset = samples[index].pts - decode[index] - originOffset;
                offsets[index] = (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, offset));
                decode[index] = Math.max(0, decode[index] - firstDecode + start);
                presentationEnd = Math.max(presentationEnd, decode[index] + offsets[index] + durations[index]);
            }
            lastDuration = durations[count - 1];
            return new Run(this, samples, decode, durations, offsets);
        }

        /**
         * Returns the duration of a sample whose successor is unknown.
         *
         * @param sample the last sample of the track
         * @param previous the duration of the previous sample, or {@code 0}
         * @return the declared duration, else the previous duration, else the codec's frame
         * duration, else {@code 0}
         */
        private long finalDuration(Sample sample, long previous) {
            if (sample.declared > 0) {
                return sample.declared;
            }
            if (previous > 0) {
                return previous;
            }
            if (media.track instanceof AudioTrack audio && audio.sampleRate() > 0) {
                var frame = switch (audio.codec()) {
                    case AAC -> 1024;
                    case MP3 -> audio.sampleRate() >= 32000 ? 1152 : 576;
                    default -> 0;
                };
                return Mp4Track.rescale(frame, audio.sampleRate(), media.timescale);
            }
            return 0;
        }

        /**
         * Records a fragment that starts with a sync sample of this track.
         *
         * @param time the presentation time of the sample in media units
         * @param moofOffset the offset of the fragment's {@code moof} from the start of the file
         * @param trafNumber the one-based index of the track's {@code traf} in the {@code moof}
         */
        void addRandomAccess(long time, long moofOffset, int trafNumber) {
            if (3 * randomAccessCount + 3 > randomAccess.length) {
                randomAccess = Arrays.copyOf(randomAccess, randomAccess.length * 2);
            }
            randomAccess[3 * randomAccessCount] = time;
            randomAccess[3 * randomAccessCount + 1] = moofOffset;
            randomAccess[3 * randomAccessCount + 2] = trafNumber;
            randomAccessCount++;
        }
    }
}
