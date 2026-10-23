package com.github.auties00.smalt.container.mp4;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.container.AttachedPicture;
import com.github.auties00.smalt.container.Chapter;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.container.internal.AacExtensions;
import com.github.auties00.smalt.container.internal.Annotations;
import com.github.auties00.smalt.container.internal.SeekTimes;
import com.github.auties00.smalt.internal.codec.AudioConfigs;
import com.github.auties00.smalt.internal.heif.BoxCursor;
import com.github.auties00.smalt.internal.io.ArrayBacked;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.io.ForwardOnlySource;
import com.github.auties00.smalt.internal.io.FourCc;
import com.github.auties00.smalt.internal.io.SegmentBacked;
import com.github.auties00.smalt.internal.io.SourceBytes;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.subtitle.WebVttSamples;
import com.github.auties00.smalt.internal.tags.Mp4ChapterTrack;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketFlag;
import com.github.auties00.smalt.track.PacketSideData;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.TrackKind;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Demuxes ISO base media files: MP4, MOV, M4A, M4V, 3GP and 3G2, progressive or fragmented.
 *
 * <p>The constructor walks the top-level boxes, reads the {@code moov} wherever it is (before or
 * after the media data), and indexes every sample of every track, including the samples of movie
 * fragments ({@code moof}); {@code sidx} and {@code mfra} indexes are not needed and ignored.
 * {@link #read()} then returns the samples of all tracks interleaved in file-offset order
 * (the order they are stored in), each track's samples in decoding order.
 *
 * <p>Tracks are identified from their sample descriptions: H.264 ({@code avc1}, {@code avc3}),
 * HEVC ({@code hvc1}, {@code hev1}, Dolby Vision {@code dvh1}/{@code dvhe}), VP8/VP9
 * ({@code vp08}/{@code vp09}), AV1 ({@code av01}), MPEG-4 Part 2 ({@code mp4v}), H.263
 * ({@code s263}), Motion JPEG and ProRes for video; AAC, MP3 and MP2 ({@code mp4a} with the
 * matching object type, {@code .mp3}), Opus, FLAC, ALAC, AMR-NB/WB, AC-3/E-AC-3 and every PCM
 * layout of the ISO and QuickTime sound descriptions for audio. The image sequence ({@code pict})
 * and auxiliary video ({@code auxv}) tracks of HEIF and AVIF sequences are video tracks too. Every
 * other handler (text, subtitles, timecode, timed metadata, hint) becomes a
 * {@link com.github.auties00.smalt.track.DataTrack}: 3GPP timed text ({@code tx3g}) is
 * {@link Codec#MOV_TEXT}, WebVTT ({@code wvtt}) is {@link Codec#WEBVTT} and TTML ({@code stpp}) is
 * {@link Codec#TTML}, except in the chapter tracks a {@code chap} reference names, which stay
 * {@link Codec#DATA}. Each WebVTT sample is returned as one packet per cue it shows (none for an
 * empty sample), all with the sample's timing, in the {@link Codec#WEBVTT} packet form; every
 * other sample is one packet. Codec
 * configurations follow {@link Track#codecConfig()}: the {@code avcC}, {@code hvcC} and {@code av1C} payloads (an
 * {@code hvcC} followed by the arrays of the {@code lhvC} record of a multi-layer HEVC entry, which carries the
 * parameter sets of the layers above the base layer), the {@code vpcC} record without its full box header, the
 * AAC {@code AudioSpecificConfig} and the
 * MPEG-4 Part 2 decoder specific info from {@code esds}, an {@code OpusHead} synthesized from
 * {@code dOps}, {@code fLaC} followed by the metadata blocks of {@code dfLa}, the 24-byte
 * {@code ALACSpecificConfig}, and for data tracks the sample entry payload after its reserved
 * fields and data reference index. A visual sample entry's {@code colr}, {@code pasp} and
 * QuickTime {@code fiel} boxes give the track's color, sample aspect ratio and scan
 * ({@link com.github.auties00.smalt.track.VideoTrack#sampleAspectRatio()},
 * {@link com.github.auties00.smalt.track.VideoTrack#interlacing()}), and its {@code mdcv} and
 * {@code clli} boxes the mastering display and content light level of that color; an entry with
 * either box but no {@code colr} gets a color of unspecified matrix, primaries and transfer
 * characteristics, in the limited range, that carries them.
 *
 * <p>Timestamps are in each track's media timescale ({@code 1/timescale}). Decoding times come
 * from {@code stts} or the fragment runs, presentation times add the {@code ctts} or {@code trun}
 * composition offsets (both versions, negative offsets allowed), and an edit list of empty edits
 * followed by one media edit shifts both: the empty edits delay the track, and the media time of
 * the media edit becomes timestamp zero for video and data, and
 * {@link AudioTrack#skipSamples()} for audio (whose timestamps are left unshifted, so the first
 * kept sample has the timestamp of the first packet). Negative composition offsets lower the
 * decoding timestamps of their track so that no sample is decoded after it is presented. Without
 * an edit list, an iTunes {@code iTunSMPB} tag provides the audio skip.
 *
 * <p>Any other edit list (ISO/IEC 14496-12 clause 8.6.6: several media edits, empty edits between
 * them, dwell edits of media rate zero) makes the track's packets those of its presentation: each
 * edit returns the samples it presents with timestamps on the presentation timeline, preceded by
 * the samples a decoder needs before them (from the key frame at or before them for video and
 * data; for audio, the packets that rebuild the codec's state, every packet up to an edit that
 * does not go back in the media for an AAC object type that predicts from the start of the stream,
 * whose output only converges after an edit that does), which are flagged
 * {@link PacketFlag#DISCARD}; a sample several edits present is returned once per edit. A video
 * or data sample straddling an edit boundary is cut to the edit, and a discarded video sample has
 * a presentation timestamp no presented sample of its track has. An audio packet cut by its edit
 * keeps its timestamp and carries the samples outside the edit as its
 * {@link PacketSideData#discardStart()} and {@link PacketSideData#discardEnd()}, its duration
 * excluding the end. A dwell edit presents the video or data sample shown at its media time for its
 * duration, and nothing for audio; every other edit plays at normal speed, whatever its media rate.
 * Decoding timestamps never decrease, so the pre-roll of an edit that goes back in the media takes
 * the decoding time of the packet before it. Such a track declares no audio skip, and its duration
 * is that of its edits. The image sequence and auxiliary video tracks of HEIF and AVIF files
 * ({@code pict} and {@code auxv} handlers) are the exception: their timestamps follow the first
 * media edit as in the simple form, and each of their samples is returned once.
 *
 * <p>Every sample description of a track is read. The public track describes the one of its first
 * sample; a sample of another description (chosen by {@code stsc}, or by the {@code tfhd} or
 * {@code trex} of a fragment) whose codec configuration differs from the one of the track's previous
 * packet carries the new configuration as its {@link PacketSideData#newCodecConfig()}, and so does
 * the first packet after a seek when its description is not the track's. A description of another
 * codec raises {@link MediaException.Unsupported}, and one that does not parse
 * {@link MediaException.Malformed}, when a sample using it is read.
 *
 * <p>An AAC track whose sample entry declares the rate of the AAC core while spectral band
 * replication doubles it (HE-AAC, signaled explicitly or only inside the raw data blocks), or
 * whose parametric stereo turns a mono core into stereo, is described by the format its first
 * sample decodes to through the registered AAC decoder, as players do; the track keeps its media
 * timescale, and its audio skip is rescaled to the decoded rate. Uncompressed audio stored one
 * frame per sample is returned in packets of up to 1024 frames within a chunk.
 *
 * <p>Every size read from the file is checked against the {@link MediaLimits} before allocating:
 * the {@code moov}, each {@code moof}, each sample, and the number of samples per track.
 * Encrypted tracks ({@code encv}, {@code enca}) raise {@link MediaException.Unsupported}.
 *
 * <p>Cover pictures ({@code covr} items) become {@link #attachedPictures()}; chapters come from a
 * Nero {@code chpl} box or, without one, from the first QuickTime chapter text track a track
 * references through {@code tref}/{@code chap}, whose samples hold the titles; the chapter track
 * is also preferred when it completes the chapters or titles a full {@code chpl} box had to cut
 * (255 chapters, 255-byte titles). Seeking uses the
 * sample index, as {@link #seek(long)} describes.
 *
 * <p>A source that is not in memory is read forwards as far as the layout allows: a file whose
 * {@code moov} follows its media data is read with one jump to the {@code moov} and one back to
 * the media data, and a fragmented file with one jump per fragment.
 *
 * <p>A source that reads its input once, front to back (a stream), is told after every sample
 * which bytes are no longer needed, so it keeps only the bytes from the next sample on. The
 * top-level walk of such a source stops at the first {@code mdat} once the {@code moov} is known,
 * and the {@code moof} boxes of a fragmented file are then read as the demux reaches them rather
 * than at construction, so a fast-start or fragmented file is read with a bounded window while a
 * file whose {@code moov} follows its media data keeps that media data until the {@code moov} is
 * read. {@link #seek(long)} and the sample statistics read every remaining fragment first, and so
 * does the constructor when an edit list needs the presentation index. A track of such a
 * fragmented file whose duration would be measured from its samples reports an unknown duration,
 * since the fragments not yet read would lengthen it.
 *
 * @implNote This implementation reads the payloads of the {@code moov} and {@code moof} boxes
 * together with their headers during the top-level walk, up to {@value #PREFETCH_BUDGET} bytes of
 * them. It takes the composition offset that aligns the decoding timestamps of
 * a track ({@code compositionShift}) as the lowest offset among the samples indexed at construction,
 * so a fragmented file read from a stream takes it from its first fragments only; a later fragment
 * with a lower offset then gives decoding timestamps above the presentation ones, where a
 * random-access source would have shifted every timestamp of the track.
 */
public final class Mp4Demuxer implements Demuxer {
    /**
     * The largest first sample read to refine an AAC track; a raw data block of 48 channels at
     * the 6144 bits each channel may use fits.
     */
    private static final int MAX_AAC_SAMPLE = 1 << 16;

    /**
     * The size of the read-ahead window that serves small samples.
     */
    private static final int WINDOW_SIZE = 1 << 20;

    /**
     * The largest sample read through the window rather than directly.
     */
    private static final int WINDOW_MAX_SAMPLE = 64 * 1024;

    /**
     * The largest number of bytes of {@code moov} and {@code moof} payloads read during the
     * top-level walk of a source that is not in memory.
     *
     * @implNote This implementation allows 16 MiB, which holds the {@code moov} of a multi-hour
     * progressive file and the {@code moof} boxes of thousands of fragments; the payloads are the
     * ones the constructor reads anyway, and only their lifetime grows, from their parse to the
     * end of the walk. In-memory sources prefetch nothing.
     */
    private static final int PREFETCH_BUDGET = 16 << 20;

    /**
     * The bytes of an empty sample.
     */
    private static final byte[] EMPTY = new byte[0];

    /**
     * The flags of a WebVTT cue packet, every cue being a random access point, and of any other
     * sync sample that carries side data.
     */
    private static final Set<PacketFlag> KEY_FLAGS = Set.of(PacketFlag.KEY);

    /**
     * The flags of a sample decoded only so that later samples decode.
     */
    private static final Set<PacketFlag> DISCARD_FLAGS = Set.of(PacketFlag.DISCARD);

    /**
     * The flags of a sync sample decoded only so that later samples decode.
     */
    private static final Set<PacketFlag> KEY_DISCARD_FLAGS = Set.of(PacketFlag.KEY, PacketFlag.DISCARD);

    /**
     * The source being demuxed.
     */
    private final MediaSource source;

    /**
     * The limits applied to every size read from the source.
     */
    private final MediaLimits limits;

    /**
     * The source size sampled at construction, or {@code -1} when unknown.
     */
    private final long sourceSize;

    /**
     * The public track descriptions, in file order.
     */
    private final List<Track> tracks;

    /**
     * The container-level metadata tags.
     */
    private final Map<String, String> metadata;

    /**
     * The presentation duration in microseconds, or {@code -1}.
     */
    private final long durationMicros;

    /**
     * Whether the file stores the {@code moov} before its first {@code mdat}.
     */
    private final boolean fastStart;

    /**
     * The tracks that have samples, indexed by the values in {@link #heap}.
     */
    private final Mp4Track[] active;

    /**
     * Every track, in file order.
     */
    private final Mp4Track[] allTracks;

    /**
     * The chapters: the Nero {@code chpl} box, or else the first QuickTime chapter track.
     */
    private final List<Chapter> chapters;

    /**
     * The cover pictures of the {@code covr} items.
     */
    private final List<AttachedPicture> pictures;

    /**
     * The end of the furthest sample, past which the read-ahead window never reads.
     */
    private long dataEnd;

    /**
     * Whether the source reads its input once, front to back, and is told which bytes the demux
     * no longer needs.
     */
    private final boolean forwardOnly;

    /**
     * The offset of the next top-level box to walk for {@code moof} boxes, or {@code -1} when
     * every fragment is indexed; only a forward-only source indexes fragments as the demux
     * reaches them.
     */
    private long fragmentScan;

    /**
     * Every track of the movie, which the fragments indexed after construction extend, or
     * {@code null} when every fragment was indexed at construction.
     */
    private final List<Mp4Track> fragmentTracks;

    /**
     * The index of the next sample of each active track.
     */
    private final int[] cursors;

    /**
     * The sample description whose codec configuration the packets of each active track last
     * established: the track's own until a sample of another description is returned.
     */
    private final int[] descriptions;

    /**
     * The movie timescale, in which the edit lists of the tracks count.
     */
    private final long movieTimescale;

    /**
     * A binary min-heap of active track indices ordered by the file offset of their next sample.
     */
    private final int[] heap;

    /**
     * The number of entries in {@link #heap}.
     */
    private int heapSize;

    /**
     * The identifiers of the WebVTT tracks, whose samples are split into one packet per cue.
     */
    private final Set<Integer> webVttTracks;

    /**
     * The cue packets of a WebVTT sample not returned yet.
     */
    private final ArrayDeque<Packet> pendingCues = new ArrayDeque<>();

    /**
     * The read-ahead window, allocated on first use.
     */
    private byte[] window;

    /**
     * The array behind the source when it is in memory, read in place instead of through
     * {@link #window}, or {@code null}.
     */
    private final ArrayBacked backing;

    /**
     * The segment behind the source when it is a native memory segment, copied from directly
     * with one bulk copy per sample instead of through {@link #window}, or {@code null}.
     */
    private final MemorySegment segment;

    /**
     * The buffer that receives samples too large for {@link #window} in
     * {@link #readSample(SampleInfo)}, allocated on first use.
     */
    private byte[] scratch;

    /**
     * The absolute offset of {@code window[0]}.
     */
    private long windowStart;

    /**
     * The number of valid bytes in {@link #window}.
     */
    private int windowLength;

    /**
     * Constructs a demuxer over {@code source} with {@link DemuxerOptions#defaults()}.
     *
     * @param source the input, not closed by this demuxer
     * @throws MediaException.Malformed if the file is not an ISO base media file or is corrupt
     * @throws MediaException.Unsupported if a track is encrypted
     * @throws MediaException.LimitExceeded if a declared size exceeds the limits
     * @throws MediaException.Io if reading the source fails
     */
    public Mp4Demuxer(MediaSource source) {
        this(source, DemuxerOptions.defaults());
    }

    /**
     * Constructs a demuxer over {@code source} that enforces the limits of {@code options}.
     *
     * @param source the input, not closed by this demuxer
     * @param options the demuxing options
     * @throws MediaException.Malformed if the file is not an ISO base media file or is corrupt
     * @throws MediaException.Unsupported if a track is encrypted
     * @throws MediaException.LimitExceeded if a declared size exceeds the limits
     * @throws MediaException.Io if reading the source fails
     */
    public Mp4Demuxer(MediaSource source, DemuxerOptions options) {
        this(source, options.limits());
    }

    private Mp4Demuxer(MediaSource source, MediaLimits limits) {
        this.source = Objects.requireNonNull(source, "source");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.backing = ArrayBacked.of(source);
        var segmentBacked = SegmentBacked.of(source);
        this.segment = segmentBacked != null ? segmentBacked.segment() : null;
        this.forwardOnly = ForwardOnlySource.isForwardOnly(source);
        try {
            this.sourceSize = source.size();
            var scan = TopLevelScan.scan(source, forwardOnly,
                    SegmentBacked.isInMemory(source) ? 0 : Math.min(PREFETCH_BUDGET, limits.maxBytes()));
            this.fastStart = scan.isFastStart();
            if (scan.moovOffset < 0) {
                throw new MediaException.Malformed("no moov box");
            }
            var parser = new MovieParser(limits);
            var moov = scan.moovPayload;
            scan.moovPayload = null;
            parser.parse(BoxCursor.of(moov != null ? moov : readBox(scan.moovOffset, scan.moovHeader, scan.moovSize, "moov"),
                    BoxTypes.MOOV));
            for (var track : parser.tracks) {
                var samples = track.samples;
                track.nextFragmentDts = samples.count == 0 ? 0
                        : samples.dts[samples.count - 1] + Integer.toUnsignedLong(samples.durations[samples.count - 1]);
            }
            for (var index = 0; index < scan.fragments.size(); index++) {
                var fragment = scan.fragments.get(index);
                var payload = scan.fragmentPayloads.set(index, null);
                FragmentParser.parse(BoxCursor.of(payload != null ? payload : readBox(fragment[0], (int) fragment[1], fragment[2], "moof"),
                                BoxTypes.MOOF),
                        fragment[0], parser.tracks);
            }
            var lazy = forwardOnly && parser.fragmented && scan.next >= 0;
            if (lazy && parser.tracks.stream().anyMatch(track -> !track.hasSimpleEdits())) {
                indexFragments(scan.next, parser.tracks);
                lazy = false;
            }
            this.fragmentScan = lazy ? scan.next : -1;
            this.fragmentTracks = lazy ? parser.tracks : null;
            var publicTracks = new ArrayList<Track>();
            var activeTracks = new ArrayList<Mp4Track>();
            var gaplessUsed = false;
            var chapterIds = new HashSet<Integer>();
            for (var track : parser.tracks) {
                if (track.chapterTracks != null) {
                    for (var id : track.chapterTracks) {
                        chapterIds.add(id);
                    }
                }
            }
            var webVtt = new HashSet<Integer>();
            for (var track : parser.tracks) {
                track.selectBaseEntry();
                if (track.entry != null && (track.entry.codec().isSubtitle() || track.entry.codec() == Codec.TTML)
                        && chapterIds.contains(track.fileId)) {
                    track.entry = track.entry.withCodec(Codec.DATA);
                }
                refineMpegAudio(track);
                var audio = track.kind() == TrackKind.AUDIO;
                track.resolveTiming(parser.movieTimescale, audio && !gaplessUsed ? parser.gapless : null, limits);
                gaplessUsed |= audio;
                if (lazy && track.durationFromSamples) {
                    track.duration = -1;
                }
                var described = track.toTrack();
                if (described instanceof AudioTrack sound && AacExtensions.mayRefine(sound)) {
                    described = AacExtensions.refine(sound, firstSample(track));
                }
                track.publicConfig = described.codecConfig().orElse(null);
                publicTracks.add(described);
                if (described.codec() == Codec.WEBVTT) {
                    webVtt.add(described.id());
                }
                if (track.samples.count > 0 || (lazy && track.entry != null)) {
                    activeTracks.add(track);
                }
            }
            this.tracks = Collections.unmodifiableList(publicTracks);
            this.webVttTracks = Set.copyOf(webVtt);
            this.metadata = Collections.unmodifiableMap(buildMetadata(scan.fileType, parser.metadata));
            this.durationMicros = computeDuration(parser, !scan.fragments.isEmpty());
            this.active = activeTracks.toArray(new Mp4Track[0]);
            this.dataEnd = dataEnd(active);
            this.cursors = new int[active.length];
            this.heap = new int[active.length];
            this.descriptions = new int[active.length];
            this.movieTimescale = parser.movieTimescale;
            this.allTracks = parser.tracks.toArray(new Mp4Track[0]);
            var annotations = parser.movieMetadata.annotations;
            if (!parser.movieMetadata.neroChapters) {
                readChapterTrack(annotations);
                this.chapters = annotations.chapters(durationMicros);
            } else {
                this.chapters = completeNeroChapters(annotations.chapters(durationMicros));
            }
            this.pictures = annotations.pictures();
            rewind();
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("corrupt ISO base media file: " + exception, exception);
        }
    }

    /**
     * Turns the samples of the first QuickTime chapter track into chapters: each sample is a
     * 16-bit big-endian length followed by the title in UTF-8, or in UTF-16 after a byte order
     * mark, and spans the sample's presentation time.
     *
     * @param annotations receives the chapters
     */
    private void readChapterTrack(Annotations annotations) {
        for (var track : allTracks) {
            if (track.chapterTracks == null) {
                continue;
            }
            for (var id : track.chapterTracks) {
                for (var candidate : allTracks) {
                    if (candidate.fileId != id || candidate.kind() != TrackKind.DATA || candidate.samples.count == 0) {
                        continue;
                    }
                    var samples = candidate.samples;
                    var timeBase = new Rational(1, candidate.timescale);
                    for (var index = 0; index < samples.count && index < 4096; index++) {
                        var size = samples.sizes[index];
                        if (size < 2 || size > 64 * 1024) {
                            continue;
                        }
                        var bytes = new byte[size];
                        if (SourceBytes.readAvailable(source, samples.offsets[index], bytes, 0, bytes.length) < size) {
                            continue;
                        }
                        var length = Math.min(size - 2, ((bytes[0] & 0xFF) << 8) | (bytes[1] & 0xFF));
                        String title;
                        if (length >= 2 && (bytes[2] & 0xFF) == 0xFE && (bytes[3] & 0xFF) == 0xFF) {
                            title = new String(bytes, 4, length - 2, StandardCharsets.UTF_16BE);
                        } else if (length >= 2 && (bytes[2] & 0xFF) == 0xFF && (bytes[3] & 0xFF) == 0xFE) {
                            title = new String(bytes, 4, length - 2, StandardCharsets.UTF_16LE);
                        } else {
                            title = new String(bytes, 2, length, StandardCharsets.UTF_8);
                        }
                        var start = timeBase.toMicros(Math.max(0, samples.pts(index) + candidate.shift));
                        var end = start + timeBase.toMicros(Integer.toUnsignedLong(samples.durations[index]));
                        annotations.addChapter(start, end, title);
                    }
                    return;
                }
            }
        }
    }

    /**
     * Returns the chapters of a Nero {@code chpl} box, or those of the QuickTime chapter track when they complete
     * chapters or titles the box had to cut ({@link Mp4ChapterTrack#supersedes(List, List)}).
     *
     * @param nero the chapters of the {@code chpl} box
     * @return the chapters to report
     */
    private List<Chapter> completeNeroChapters(List<Chapter> nero) {
        if (!Mp4ChapterTrack.mayBeCut(nero)) {
            return nero;
        }
        var track = new Annotations();
        readChapterTrack(track);
        var chapters = track.chapters(durationMicros);
        return Mp4ChapterTrack.supersedes(chapters, nero) ? chapters : nero;
    }

    @Override
    public List<Chapter> chapters() {
        return chapters;
    }

    @Override
    public List<AttachedPicture> attachedPictures() {
        return pictures;
    }

    /**
     * Returns the number of samples of a track, as the sample tables and fragment runs index
     * them, without reading the samples: each media sample once, whatever its edit list presents.
     *
     * @param trackId the identifier of the track
     * @return the sample count, or {@code -1} for an unknown track
     */
    public long sampleCount(int trackId) {
        loadAllFragments();
        for (var track : allTracks) {
            if (track.id == trackId) {
                return track.media.count;
            }
        }
        return -1;
    }

    /**
     * Returns the total size of the samples of a track, as the sample tables and fragment runs
     * index them, without reading the samples.
     *
     * @param trackId the identifier of the track
     * @return the size in bytes, or {@code -1} for an unknown track
     */
    public long sampleBytes(int trackId) {
        loadAllFragments();
        for (var track : allTracks) {
            if (track.id == trackId) {
                var total = 0L;
                for (var index = 0; index < track.media.count; index++) {
                    total += Math.max(0, track.media.sizes[index]);
                }
                return total;
            }
        }
        return -1;
    }

    /**
     * Reads the payload of a top-level box into memory.
     *
     * @param offset the absolute offset of the box
     * @param headerSize the size of the box header
     * @param size the total size of the box
     * @param name the box name, for diagnostics
     * @return the payload
     */
    private byte[] readBox(long offset, int headerSize, long size, String name) {
        var length = size - headerSize;
        LimitChecks.checkAllocation(limits, length);
        if (sourceSize >= 0 && offset + size > sourceSize) {
            throw new MediaException.Malformed(name + " box at offset " + offset + " runs past the end of the file");
        }
        var payload = new byte[(int) length];
        source.readFully(offset + headerSize, payload, 0, payload.length);
        return payload;
    }

    /**
     * Distinguishes MP3 from MP2 in an MPEG audio track whose sample description does not say
     * which layer it carries, by reading the header of the first frame.
     *
     * @param track the track to refine
     */
    private void refineMpegAudio(Mp4Track track) {
        if (track.entry.codec() != Codec.MP3 || track.samples.count == 0 || track.samples.sizes[0] < 4) {
            return;
        }
        var header = new byte[4];
        if (SourceBytes.readAvailable(source, track.samples.offsets[0], header, 0, header.length) < 4) {
            return;
        }
        var parsed = AudioConfigs.parseMpegHeader(header, 0, 4);
        if (parsed != null && parsed.layer() != 3) {
            track.entry = track.entry.withCodec(Codec.MP2);
        }
    }

    /**
     * Reads the first sample of a track for {@link AacExtensions#refine}.
     *
     * @param track the track whose first sample is read
     * @return the sample, or {@code null} when the track has none or it cannot be read whole
     */
    private byte[] firstSample(Mp4Track track) {
        if (track.samples.count == 0) {
            return null;
        }
        var size = track.samples.sizes[0];
        if (size <= 0 || size > Math.min(limits.maxBytes(), MAX_AAC_SAMPLE)) {
            return null;
        }
        var sample = new byte[size];
        return SourceBytes.readAvailable(source, track.samples.offsets[0], sample, 0, sample.length) < size ? null : sample;
    }

    /**
     * Combines the file type brands and the movie metadata.
     *
     * @param fileType the {@code ftyp} payload, or {@code null}
     * @param movie the movie metadata
     * @return the tags, brands first
     */
    private static Map<String, String> buildMetadata(byte[] fileType, Map<String, String> movie) {
        var result = new LinkedHashMap<String, String>();
        if (fileType != null && fileType.length >= 8) {
            result.put("major_brand", FourCc.printable(ByteArrays.i32be(fileType, 0)));
            result.put("minor_version", Long.toString(ByteArrays.i32be(fileType, 4) & 0xFFFFFFFFL));
            var brands = new StringBuilder();
            for (var index = 8; index + 4 <= fileType.length; index += 4) {
                brands.append(FourCc.printable(ByteArrays.i32be(fileType, index)));
            }
            result.put("compatible_brands", brands.toString());
        }
        result.putAll(movie);
        return result;
    }

    /**
     * Computes the presentation duration.
     *
     * @param parser the parsed movie
     * @param fragmented whether the file has movie fragments
     * @return the duration in microseconds, or {@code -1} when unknown
     */
    private long computeDuration(MovieParser parser, boolean fragmented) {
        if (parser.movieTimescale > 0) {
            if (!fragmented && parser.movieDuration > 0) {
                return Mp4Track.rescale(parser.movieDuration, parser.movieTimescale, 1_000_000);
            }
            if (fragmented && parser.fragmentDuration > 0) {
                return Mp4Track.rescale(parser.fragmentDuration, parser.movieTimescale, 1_000_000);
            }
        }
        var longest = -1L;
        for (var track : parser.tracks) {
            if (track.duration >= 0 && track.kind() != TrackKind.DATA) {
                longest = Math.max(longest, Mp4Track.rescale(track.duration, track.timescale, 1_000_000));
            }
        }
        return longest;
    }

    @Override
    public ContainerFormat format() {
        return ContainerFormat.MP4;
    }

    @Override
    public List<Track> tracks() {
        return tracks;
    }

    @Override
    public Map<String, String> metadata() {
        return metadata;
    }

    @Override
    public long durationMicros() {
        return durationMicros;
    }

    /**
     * Returns whether the file stores its {@code moov} before the first {@code mdat}, so that a
     * progressive download can start playing before the whole file arrived.
     *
     * @return {@code true} for a fast-start file
     */
    public boolean isFastStart() {
        return fastStart;
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code true}: every sample of every track, fragments included, is indexed
     */
    @Override
    public boolean isSeekable() {
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Each track restarts independently at the last sync sample ({@code stss}, or a fragment
     * sample without the non-sync flag) whose presentation time, edit list applied, is at or
     * before {@code micros}; a track whose every sample ends at or before {@code micros} returns
     * nothing more. The samples of all tracks are then returned in file-offset order again.
     *
     * @implNote This implementation seeks in the sample index built at construction, which holds
     * the samples of every movie fragment as well, so the fragment indexes {@code tfra} and
     * {@code sidx} are never needed.
     */
    @Override
    public void seek(long micros) {
        pendingCues.clear();
        try {
            loadAllFragments();
            var starts = new int[active.length];
            for (var index = 0; index < active.length; index++) {
                starts[index] = startSample(active[index], micros);
            }
            System.arraycopy(starts, 0, cursors, 0, starts.length);
            resetDescriptions();
            heapSize = 0;
            for (var index = 0; index < active.length; index++) {
                if (cursors[index] < active[index].samples.count) {
                    heapPush(index);
                }
            }
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("corrupt ISO base media file: " + exception, exception);
        }
    }

    /**
     * Finds the sample a track restarts at for a seek.
     *
     * @param track the track searched
     * @param micros the seek target in microseconds
     * @return the sample index, or the sample count when the track ends at or before the target
     */
    private static int startSample(Mp4Track track, long micros) {
        var samples = track.samples;
        var target = SeekTimes.floor(micros, new Rational(1, track.timescale));
        var end = Long.MIN_VALUE;
        for (var index = 0; index < samples.count; index++) {
            end = Math.max(end, samples.pts(index) + track.shift + Integer.toUnsignedLong(samples.durations[index]));
        }
        if (end <= target) {
            return samples.count;
        }
        var low = 0;
        var high = samples.count;
        while (low < high) {
            var middle = (low + high) >>> 1;
            if (samples.dts[middle] + track.shift - track.compositionShift > target) {
                high = middle;
            } else {
                low = middle + 1;
            }
        }
        for (var index = low - 1; index >= 0; index--) {
            if (samples.isKeyframe(index) && samples.pts(index) + track.shift <= target) {
                return index;
            }
        }
        for (var index = 0; index < samples.count; index++) {
            if (samples.isKeyframe(index)) {
                return index;
            }
        }
        return 0;
    }

    @Override
    public Packet read() {
        try {
            while (true) {
                var pending = pendingCues.pollFirst();
                if (pending != null) {
                    return pending;
                }
                var packet = nextPacket();
                if (packet == null || webVttTracks.isEmpty() || !webVttTracks.contains(packet.trackId())) {
                    return packet;
                }
                for (var cue : WebVttSamples.decode(packet.data())) {
                    var sideData = WebVttSamples.sideData(PacketSideData.NONE, cue.identifier(), cue.settings());
                    pendingCues.addLast(new Packet(packet.trackId(), cue.payload().getBytes(StandardCharsets.UTF_8),
                            packet.pts(), packet.dts(), packet.duration(), KEY_FLAGS, sideData));
                }
            }
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("corrupt ISO base media file: " + exception, exception);
        }
    }

    /**
     * Returns the sample with the lowest file offset among the next samples of every track.
     *
     * @return the packet, or {@code null} when every sample was returned
     */
    private Packet nextPacket() {
        loadFragments();
        if (heapSize == 0) {
            return null;
        }
        var trackIndex = heap[0];
        var index = advance();
        var track = active[trackIndex];
        var samples = track.samples;
        var data = readSample(samples.offsets[index], samples.sizes[index]);
        releaseConsumed();
        var decode = samples.dts[index] + track.shift;
        var pts = decode + samples.ctsOffsets[index];
        var dts = decode - track.compositionShift;
        var duration = Integer.toUnsignedLong(samples.durations[index]);
        var config = configChange(trackIndex, index);
        var trim = track.trimOf(index);
        if (config == null && trim < 0 && !samples.isDiscarded(index)) {
            return new Packet(track.id, data, pts, dts, duration, samples.isKeyframe(index));
        }
        var flags = samples.isKeyframe(index) ? samples.isDiscarded(index) ? KEY_DISCARD_FLAGS : KEY_FLAGS
                : samples.isDiscarded(index) ? DISCARD_FLAGS : Set.<PacketFlag>of();
        var sideData = PacketSideData.builder().newCodecConfig(config);
        if (trim >= 0) {
            sideData.discard(track.trimAmounts[trim * 2], track.trimAmounts[trim * 2 + 1]);
        }
        return new Packet(track.id, data, pts, dts, duration, flags, sideData.build());
    }

    /**
     * Returns the codec configuration a sample establishes when its sample description differs
     * from the one the track's previous packets established, and records it.
     *
     * @param trackIndex the active index of the sample's track
     * @param index the index of the sample in its track
     * @return the configuration to signal, or {@code null} when it does not change
     * @throws MediaException.Malformed if the sample's description failed to parse
     * @throws MediaException.Unsupported if the sample's description cannot follow the track's
     */
    private byte[] configChange(int trackIndex, int index) {
        var track = active[trackIndex];
        var description = track.samples.description(index);
        var current = descriptions[trackIndex];
        if (description == current) {
            return null;
        }
        var next = track.configOf(description);
        var previous = track.configOf(current);
        descriptions[trackIndex] = description;
        return Arrays.equals(previous, next) ? null : next;
    }

    /**
     * Makes every active track's own sample description the established one, as it is for a
     * decoder created from the track after a seek.
     */
    private void resetDescriptions() {
        for (var index = 0; index < active.length; index++) {
            descriptions[index] = active[index].baseDescription;
        }
    }

    /**
     * Makes the demuxer return the media samples of a track whose edit list was expanded into a
     * presentation index: every sample once, in decoding order, with its media timestamps (the
     * composition shift applied, the edit list not), for a writer that copies the edit list
     * itself; then rewinds.
     *
     * @param trackId the identifier of the track
     * @return the track's edit list as {@link Mp4Track#editsInMediaUnits(long)} returns it, or
     * {@code null} when the track's edit list was not expanded
     */
    long[][] useMediaSamples(int trackId) {
        for (var track : active) {
            if (track.id == trackId && track.expanded) {
                track.samples = track.media;
                track.expanded = false;
                track.shift = 0;
                track.compositionShift = track.mediaCompositionShift;
                track.trimIndex = new int[0];
                track.trimAmounts = new long[0];
                dataEnd = Math.max(dataEnd, dataEnd(active));
                rewind();
                return track.editsInMediaUnits(movieTimescale);
            }
        }
        return null;
    }

    /**
     * Consumes the sample at the top of the heap.
     *
     * @return the index of the consumed sample within its track, whose active index was at the top
     * of the heap
     */
    private int advance() {
        var trackIndex = heap[0];
        var index = cursors[trackIndex]++;
        if (cursors[trackIndex] == active[trackIndex].samples.count) {
            heap[0] = heap[--heapSize];
        }
        if (heapSize > 0) {
            siftDown(0);
        }
        return index;
    }

    /**
     * Describes one sample without its bytes: the fields of the {@link Packet} that
     * {@link #read()} would return for it, except the data.
     */
    static final class SampleInfo {
        /**
         * The identifier of the sample's track.
         */
        int trackId;

        /**
         * The sample size in bytes.
         */
        int size;

        /**
         * The presentation timestamp.
         */
        long pts;

        /**
         * The decoding timestamp.
         */
        long dts;

        /**
         * The duration.
         */
        long duration;

        /**
         * Whether the sample is a sync sample.
         */
        boolean keyframe;

        /**
         * The codec configuration the sample establishes, or {@code null} when it keeps the one
         * of the track's previous sample.
         */
        byte[] newConfig;

        /**
         * The array holding the sample bytes, filled by {@link #readSample(SampleInfo)} only.
         */
        byte[] data;

        /**
         * The index of the first sample byte in {@link #data}.
         */
        int dataOffset;
    }

    /**
     * Advances like {@link #read()} without reading the sample bytes.
     *
     * @param info the holder that receives the sample's description
     * @return {@code false} when every sample was returned
     */
    boolean nextSample(SampleInfo info) {
        loadFragments();
        if (heapSize == 0) {
            return false;
        }
        var trackIndex = heap[0];
        var track = active[trackIndex];
        var index = advance();
        var samples = track.samples;
        var decode = samples.dts[index] + track.shift;
        info.newConfig = configChange(trackIndex, index);
        info.trackId = track.id;
        info.size = samples.sizes[index];
        info.pts = decode + samples.ctsOffsets[index];
        info.dts = decode - track.compositionShift;
        info.duration = Integer.toUnsignedLong(samples.durations[index]);
        info.keyframe = samples.isKeyframe(index);
        return true;
    }

    /**
     * Advances like {@link #read()} and exposes the sample bytes in a buffer of this
     * demuxer instead of a new array.
     *
     * <p>The bytes stay valid until the next read. The checks, the exceptions and the order of
     * the samples are those of {@link #read()}.
     *
     * @param info the holder that receives the sample's description and bytes
     * @return {@code false} when every sample was returned
     * @throws MediaException.Malformed if a sample lies outside the file
     * @throws MediaException.LimitExceeded if a sample exceeds the allocation limit
     * @throws MediaException.Io if reading the source fails
     */
    boolean readSample(SampleInfo info) {
        try {
            loadFragments();
            if (heapSize == 0) {
                return false;
            }
            var trackIndex = heap[0];
            var track = active[trackIndex];
            var index = advance();
            var samples = track.samples;
            locateSample(samples.offsets[index], samples.sizes[index], info);
            releaseConsumed();
            info.newConfig = configChange(trackIndex, index);
            var decode = samples.dts[index] + track.shift;
            info.trackId = track.id;
            info.size = samples.sizes[index];
            info.pts = decode + samples.ctsOffsets[index];
            info.dts = decode - track.compositionShift;
            info.duration = Integer.toUnsignedLong(samples.durations[index]);
            info.keyframe = samples.isKeyframe(index);
            return true;
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("corrupt ISO base media file: " + exception, exception);
        }
    }

    /**
     * Makes the bytes of one sample available in {@link #window} or {@link #scratch}, with the
     * checks of {@link #readSample(long, int)}.
     *
     * @param offset the absolute file offset
     * @param size the size in bytes
     * @param info the holder that receives the array and the index of the bytes
     * @throws MediaException.Malformed if the sample lies outside the file
     * @throws MediaException.LimitExceeded if the sample exceeds the allocation limit
     */
    private void locateSample(long offset, int size, SampleInfo info) {
        LimitChecks.checkAllocation(limits, size);
        if (sourceSize >= 0 && (offset > sourceSize || size > sourceSize - offset)) {
            throw new MediaException.Malformed("sample of " + size + " bytes at offset " + offset
                    + " runs past the end of the " + sourceSize + " byte file");
        }
        if (size == 0) {
            info.data = EMPTY;
            info.dataOffset = 0;
            return;
        }
        if (backing != null) {
            info.data = backing.array();
            info.dataOffset = backing.arrayOffset() + (int) offset;
            return;
        }
        if (segment != null) {
            if (scratch == null || scratch.length < size) {
                scratch = new byte[size];
            }
            MemorySegment.copy(segment, ValueLayout.JAVA_BYTE, offset, scratch, 0, size);
            info.data = scratch;
            info.dataOffset = 0;
            return;
        }
        if (size > WINDOW_MAX_SAMPLE && !inWindow(offset, size)) {
            if (scratch == null || scratch.length < size) {
                scratch = new byte[size];
            }
            readDirect(offset, scratch, size);
            info.data = scratch;
            info.dataOffset = 0;
            return;
        }
        if (window == null) {
            window = new byte[WINDOW_SIZE];
        }
        if (offset < windowStart || offset + size > windowStart + windowLength) {
            fillWindow(offset, size);
        }
        info.data = window;
        info.dataOffset = (int) (offset - windowStart);
    }

    /**
     * Moves back to the first sample, so that {@link #read()} and
     * {@link #nextSample(SampleInfo)} start over.
     */
    void rewind() {
        Arrays.fill(cursors, 0);
        resetDescriptions();
        heapSize = 0;
        for (var index = 0; index < active.length; index++) {
            if (active[index].samples.count > 0) {
                heapPush(index);
            }
        }
    }

    /**
     * Tells a source that reads its input once that no sample before the next one is read
     * again: the next sample of every track lies at or after the offset of the one at the top of
     * the heap, and the read-ahead window never reads backwards.
     */
    private void releaseConsumed() {
        if (forwardOnly) {
            var next = heapSize > 0 ? active[heap[0]].samples.offsets[cursors[heap[0]]] : Long.MAX_VALUE;
            ForwardOnlySource.release(source, fragmentScan >= 0 ? Math.min(next, fragmentScan) : next);
        }
    }

    /**
     * Indexes every fragment from a top-level offset on, at construction, for a forward-only
     * source whose edit lists need the whole sample index.
     *
     * @param from the offset of the first top-level box after the ones already walked
     * @param tracks every track of the movie
     * @throws MediaException.Malformed if a {@code moof} is corrupt
     */
    private void indexFragments(long from, List<Mp4Track> tracks) {
        var position = from;
        while (position >= 0) {
            var box = TopLevelScan.readHeader(source, position, sourceSize);
            if (box == null || box[1] < 0 || (sourceSize >= 0 && position + box[1] > sourceSize)) {
                return;
            }
            if ((int) box[2] == BoxTypes.MOOF) {
                FragmentParser.parse(BoxCursor.of(readBox(position, (int) box[0], box[1], "moof"), BoxTypes.MOOF), position,
                        tracks);
            }
            position += box[1];
        }
    }

    /**
     * Indexes the next fragments of a forward-only source while every indexed sample has been
     * returned.
     *
     * <p>The samples of a fragment lie in the {@code mdat} that follows its {@code moof}, after
     * every sample of the fragments before it, so returning the samples of the indexed fragments
     * before indexing the next one keeps the order of a demux that indexed every fragment at
     * construction.
     */
    private void loadFragments() {
        while (heapSize == 0 && fragmentScan >= 0) {
            loadFragment();
        }
    }

    /**
     * Indexes every remaining fragment of a forward-only source.
     */
    private void loadAllFragments() {
        while (fragmentScan >= 0) {
            loadFragment();
        }
    }

    /**
     * Walks the top-level boxes from {@link #fragmentScan} to the next {@code moof}, indexes its
     * samples, and puts the tracks that gained samples back in the heap.
     *
     * <p>The header of the {@code mdat} that follows the {@code moof} is read with it, so the
     * walk resumes after the media data of the fragment and the bytes before its first sample are
     * not kept while the samples are read.
     *
     * @throws MediaException.Malformed if the {@code moof} is corrupt
     */
    private void loadFragment() {
        while (fragmentScan >= 0) {
            var position = fragmentScan;
            var box = TopLevelScan.readHeader(source, position, sourceSize);
            if (box == null || box[1] < 0 || (sourceSize >= 0 && position + box[1] > sourceSize)) {
                fragmentScan = -1;
            } else {
                fragmentScan = position + box[1];
            }
            if (box == null || (int) box[2] != BoxTypes.MOOF || box[1] < 0) {
                continue;
            }
            var counts = new int[active.length];
            for (var index = 0; index < active.length; index++) {
                counts[index] = active[index].samples.count;
            }
            FragmentParser.parse(BoxCursor.of(readBox(position, (int) box[0], box[1], "moof"), BoxTypes.MOOF), position,
                    fragmentTracks);
            if (fragmentScan >= 0) {
                var data = TopLevelScan.readHeader(source, fragmentScan, sourceSize);
                if (data != null && (int) data[2] == BoxTypes.MDAT) {
                    fragmentScan = data[1] < 0 || (sourceSize >= 0 && fragmentScan + data[1] > sourceSize)
                            ? -1 : fragmentScan + data[1];
                }
            }
            for (var index = 0; index < active.length; index++) {
                var samples = active[index].samples;
                for (var sample = counts[index]; sample < samples.count; sample++) {
                    dataEnd = Math.max(dataEnd, samples.offsets[sample] + Math.max(0, samples.sizes[sample]));
                }
                var wasEmpty = cursors[index] >= counts[index];
                if (wasEmpty && cursors[index] < samples.count) {
                    heapPush(index);
                }
            }
            return;
        }
    }

    /**
     * Reads the bytes of one sample.
     *
     * @param offset the absolute file offset
     * @param size the size in bytes
     * @return the sample bytes
     * @throws MediaException.Malformed if the sample lies outside the file
     * @throws MediaException.LimitExceeded if the sample exceeds the allocation limit
     */
    private byte[] readSample(long offset, int size) {
        LimitChecks.checkAllocation(limits, size);
        if (sourceSize >= 0 && (offset > sourceSize || size > sourceSize - offset)) {
            throw new MediaException.Malformed("sample of " + size + " bytes at offset " + offset
                    + " runs past the end of the " + sourceSize + " byte file");
        }
        if (backing != null) {
            var from = backing.arrayOffset() + (int) offset;
            return Arrays.copyOfRange(backing.array(), from, from + size);
        }
        var data = new byte[size];
        if (segment != null) {
            MemorySegment.copy(segment, ValueLayout.JAVA_BYTE, offset, data, 0, size);
            return data;
        }
        if (size == 0) {
            return data;
        }
        if (size > WINDOW_MAX_SAMPLE && !inWindow(offset, size)) {
            readDirect(offset, data, size);
            return data;
        }
        if (window == null) {
            window = new byte[WINDOW_SIZE];
        }
        if (offset < windowStart || offset + size > windowStart + windowLength) {
            fillWindow(offset, size);
        }
        System.arraycopy(window, (int) (offset - windowStart), data, 0, size);
        return data;
    }

    /**
     * Reads a sample that does not fit the read-ahead window straight into an array, taking the
     * bytes of its start that the window already holds and reading only the rest, so the source
     * is never read backwards.
     *
     * <p>When the source ends before the sample does, the whole sample is read again through
     * {@link MediaSource#readFully(long, byte[], int, int)}, which fails exactly as a direct read
     * would.
     *
     * @param offset the absolute offset of the sample
     * @param target the destination, at least {@code size} bytes long
     * @param size the sample size in bytes
     * @throws MediaException.Malformed if the source ends inside the sample
     */
    private void readDirect(long offset, byte[] target, int size) {
        var held = 0;
        if (window != null && offset >= windowStart && offset < windowStart + windowLength) {
            held = (int) Math.min(size, windowStart + windowLength - offset);
            System.arraycopy(window, (int) (offset - windowStart), target, 0, held);
        }
        var done = held;
        while (done < size) {
            var read = source.read(offset + done, target, done, size - done);
            if (read <= 0) {
                source.readFully(offset, target, 0, size);
                return;
            }
            done += read;
        }
    }

    /**
     * Returns whether a range of the file is buffered in the read-ahead window.
     *
     * @param offset the absolute offset of the first byte
     * @param size the number of bytes
     * @return {@code true} when every byte of the range is in the window
     */
    private boolean inWindow(long offset, int size) {
        return window != null && offset >= windowStart && offset + size <= windowStart + windowLength;
    }

    /**
     * Returns the end of the furthest sample of some tracks.
     *
     * @param tracks every track of the movie
     * @return the largest sample offset plus size, or {@code 0} when no track has a sample
     */
    private static long dataEnd(Mp4Track[] tracks) {
        var end = 0L;
        for (var track : tracks) {
            var samples = track.samples;
            for (var index = 0; index < samples.count; index++) {
                end = Math.max(end, samples.offsets[index] + Math.max(0, samples.sizes[index]));
            }
        }
        return end;
    }

    /**
     * Refills the read-ahead window starting at an offset.
     *
     * <p>Bytes of the old window from {@code offset} on move to the front instead of being read
     * again, so samples that straddle two windows never make the source go backwards. The window
     * stops at the end of the furthest sample, so the boxes after the media data (a trailing
     * {@code moov}, already read) are not read again.
     *
     * @param offset the absolute offset of the first byte to buffer
     * @param needed the number of bytes that must be buffered
     * @throws MediaException.Malformed if the source ends first
     */
    private void fillWindow(long offset, int needed) {
        var keep = 0;
        if (offset >= windowStart && offset < windowStart + windowLength) {
            keep = (int) (windowStart + windowLength - offset);
            System.arraycopy(window, (int) (offset - windowStart), window, 0, keep);
        }
        windowStart = offset;
        windowLength = keep;
        var wanted = (int) Math.min(WINDOW_SIZE, (sourceSize >= 0 ? Math.min(sourceSize, dataEnd) : dataEnd) - offset);
        if (wanted < needed) {
            wanted = sourceSize >= 0 ? (int) Math.min(WINDOW_SIZE, sourceSize - offset) : WINDOW_SIZE;
        }
        while (windowLength < wanted) {
            var read = source.read(offset + windowLength, window, windowLength, wanted - windowLength);
            if (read <= 0) {
                break;
            }
            windowLength += read;
        }
        if (windowLength < needed) {
            throw new MediaException.Malformed("sample at offset " + offset + " runs past the end of the input");
        }
    }

    /**
     * Returns whether heap entry {@code a} orders before entry {@code b}.
     *
     * @param a an active track index
     * @param b an active track index
     * @return {@code true} when the next sample of {@code a} lies earlier in the file, or at the
     * same offset with a lower track index
     */
    private boolean before(int a, int b) {
        var offsetA = active[a].samples.offsets[cursors[a]];
        var offsetB = active[b].samples.offsets[cursors[b]];
        return offsetA < offsetB || (offsetA == offsetB && a < b);
    }

    /**
     * Inserts an active track index into the heap.
     *
     * @param trackIndex the index
     */
    private void heapPush(int trackIndex) {
        var position = heapSize++;
        heap[position] = trackIndex;
        while (position > 0) {
            var parent = (position - 1) >>> 1;
            if (!before(heap[position], heap[parent])) {
                break;
            }
            var swap = heap[parent];
            heap[parent] = heap[position];
            heap[position] = swap;
            position = parent;
        }
    }

    /**
     * Restores the heap order below a position.
     *
     * @param position the position whose entry may be out of order
     */
    private void siftDown(int position) {
        while (true) {
            var left = 2 * position + 1;
            if (left >= heapSize) {
                return;
            }
            var smallest = left + 1 < heapSize && before(heap[left + 1], heap[left]) ? left + 1 : left;
            if (!before(heap[smallest], heap[position])) {
                return;
            }
            var swap = heap[smallest];
            heap[smallest] = heap[position];
            heap[position] = swap;
            position = smallest;
        }
    }
}
