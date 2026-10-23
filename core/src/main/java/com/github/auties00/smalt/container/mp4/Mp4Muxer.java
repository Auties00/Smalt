package com.github.auties00.smalt.container.mp4;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.Chapter;
import com.github.auties00.smalt.container.Muxer;
import com.github.auties00.smalt.container.internal.ConfigChanges;
import com.github.auties00.smalt.internal.codec.AudioConfigs;
import com.github.auties00.smalt.internal.tags.Mp4ChapterTrack;
import com.github.auties00.smalt.internal.tags.MuxerTags;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.TrackKind;
import com.github.auties00.smalt.track.VideoTrack;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Writes ISO base media files (MP4 and M4A) and QuickTime movies from the packets of the codecs
 * {@link #supports} lists for each {@linkplain Mp4MuxerOptions.Brand brand}.
 *
 * <p>Packets are grouped per track into chunks of at most
 * {@link Mp4MuxerOptions#interleaveMicros()} of media (or 8 MiB), and chunks are stored in the
 * order they complete, which interleaves tracks whose packets arrive interleaved. With
 * {@link Mp4MuxerOptions#fastStart()} the media data is spooled until {@link #finish()}, which
 * writes {@code ftyp}, then the {@code moov} with chunk offsets that account for its own size,
 * then the {@code mdat}; otherwise the {@code mdat} is written as packets arrive, its size is
 * patched when the muxer finishes (switching to a 64-bit size when needed), and the {@code moov}
 * follows it. Chunk offsets use {@code co64} when any offset exceeds 32 bits.
 *
 * <p>Each track gets a media timescale equal to the denominator of its time base, so timestamps
 * convert exactly. Sample durations come from the gaps between decoding timestamps (the last from
 * its packet's duration); composition offsets are written only when presentation and decoding
 * timestamps differ, as a version 1 {@code ctts} when an offset is negative; the sync sample table
 * is omitted when every sample is a sync sample. Tracks whose packets carry presentation
 * timestamps only get decoding timestamps derived from them. A PCM track is stored one frame per
 * sample in a timescale of its sampling rate: its frames follow each other without gaps from the
 * first packet's timestamp, and the timestamps of the later packets are not used.
 *
 * <p>The edit list mirrors the timing conventions of {@link Mp4Demuxer}: a track whose first
 * presentation timestamp is positive starts with an empty edit of that length; a video track whose
 * earliest presentation timestamp is below its first decoding timestamp gets a media time that
 * presents timestamp zero first; an audio track's {@link AudioTrack#skipSamples()} (plus the Opus
 * pre-skip) becomes the media time, so players trim the encoder priming, unless the packets start
 * that far or further before zero (as Ogg Opus packets do), in which case the media time is that
 * lead instead, so the priming is not counted twice; a track duration shorter than the media trims
 * the end; and a subtitle track that ends before the movie gets an edit of its own length, so
 * players do not stretch its last sample to the end of the movie. The track header matrix encodes
 * {@link VideoTrack#orientation()}, a declared color description becomes an {@code nclx}
 * {@code colr} box, followed by an {@code mdcv} box for its
 * {@linkplain com.github.auties00.smalt.image.ColorInfo#masteringDisplay() mastering display} and a
 * {@code clli} box for its
 * {@linkplain com.github.auties00.smalt.image.ColorInfo#contentLightLevel() content light level}
 * when it has them (chromaticities rounded to units of 0.00002, luminances to units of 0.0001
 * candelas per square meter), a declared {@link VideoTrack#sampleAspectRatio() sample aspect ratio} a
 * {@code pasp} box, and a declared {@link VideoTrack#interlacing() scan} a QuickTime {@code fiel} box.
 *
 * <p>A packet whose side data carries a
 * {@link com.github.auties00.smalt.track.PacketSideData#newCodecConfig() new codec configuration}
 * that differs from the one in use adds a sample description to its track (or returns to an
 * earlier one with the same configuration), which that packet and the following ones reference
 * through {@code stsc}; the chunk in progress ends there, since a chunk uses one description. The
 * description of a video track takes its picture size from the new configuration's sequence
 * parameter set when it has one, and that of an AAC track its sampling rate and channel count
 * from the new {@code AudioSpecificConfig}.
 *
 * <p>Instances are not thread-safe. Output failures raise {@link MediaException.Io}; the output
 * sink is written from its position at construction and is never closed by the muxer. A
 * fast-start file is written strictly in order, so any {@link MediaSink} works, a streaming one
 * included; a file without fast start patches its {@code mdat} header at the end and needs a
 * seekable sink.
 *
 * @implNote This implementation relies on {@link Packet} payloads never changing: in fast-start
 * mode it holds the payload arrays of {@link #writePacket(Packet)} by reference until they reach
 * the output (or the temporary file past the in-memory limit), so a packet is copied once, when
 * it is written, instead of into a chunk buffer and then into the spool.
 */
public final class Mp4Muxer implements Muxer {
    /**
     * The movie timescale of files with a video track.
     */
    private static final int MOVIE_TIMESCALE = 1000;

    /**
     * The largest chunk assembled in memory before it is written.
     */
    private static final int MAX_PENDING_BYTES = 8 << 20;

    /**
     * The largest value a 32-bit box field holds.
     */
    private static final long U32_MAX = 0xFFFFFFFFL;

    /**
     * The output sink, or {@code null} for a planning muxer.
     */
    private final MediaSink output;

    /**
     * The muxer options.
     */
    private final Mp4MuxerOptions options;

    /**
     * The output position at construction, where the file starts.
     */
    private final long base;

    /**
     * The declared tracks, in declaration order.
     */
    private final List<MuxTrack> tracks;

    /**
     * The sample writers of the text subtitle tracks, which turn cue packets into samples.
     */
    private final List<TextSamples> textTracks = new ArrayList<>();

    /**
     * The spool of a fast-start file, or {@code null}.
     */
    private MdatSpool spool;

    /**
     * The number of media data bytes written or spooled.
     */
    private long dataSize;

    /**
     * The file offset of the media data of a file without fast start, once its header is
     * written, otherwise {@code -1}.
     */
    private long dataStart = -1;

    /**
     * The {@code udta} box the tags were encoded to, empty without tags.
     */
    private byte[] userData = new byte[0];

    /**
     * The chapters of the tags, which also become a QuickTime chapter track.
     */
    private List<Chapter> chapters = List.of();

    /**
     * The chapter track laid out by {@link #finish()}, or {@code null} when there is none.
     */
    private Mp4ChapterTrack chapterTrack;

    /**
     * The offset of the chapter samples from the start of the media data.
     */
    private long chapterDataOffset;

    /**
     * Whether a packet was written.
     */
    private boolean started;

    /**
     * Whether {@link #finish()} ran.
     */
    private boolean finished;

    /**
     * Whether chunk offsets and the media data size are always written as 64-bit values.
     */
    private boolean largeOffsets;

    /**
     * The presentation duration of the finished file in microseconds.
     */
    private long durationMicros;

    /**
     * Whether this muxer only plans the layout of a fast-start file, writing nothing.
     */
    private final boolean planning;

    /**
     * The {@code ftyp}, {@code moov} and {@code mdat} header computed by a planning muxer, or,
     * for a muxer that streams after a planned header, the header it wrote; {@code null}
     * otherwise.
     */
    private byte[] plannedHeader;

    /**
     * Constructs a muxer writing a fast-start MP4 to a sink from its current position.
     *
     * @param output the sink to write; not closed by this muxer
     * @throws MediaException.Io if the sink position cannot be read
     */
    public Mp4Muxer(MediaSink output) {
        this(output, Mp4MuxerOptions.defaults());
    }

    /**
     * Constructs a muxer writing to a sink from its current position.
     *
     * @param output the sink to write; not closed by this muxer
     * @param options the muxer options
     * @throws MediaException.Unsupported if the sink is not seekable and the options disable fast
     * start, which patches the file after its media data
     * @throws MediaException.Io if the sink position cannot be read
     */
    public Mp4Muxer(MediaSink output, Mp4MuxerOptions options) {
        this.output = Objects.requireNonNull(output, "output");
        this.options = Objects.requireNonNull(options, "options");
        if (!options.fastStart() && !output.isSeekable()) {
            throw new MediaException.Unsupported("non-seekable output",
                    "an MP4 file without fast start patches its header after the media data, so it needs a seekable "
                            + "output: enable fast start or write to a seekable sink");
        }
        this.tracks = new ArrayList<>();
        this.planning = false;
        this.base = output.position();
    }

    /**
     * Constructs a planning muxer, which computes the layout of a fast-start file from sample
     * sizes and timestamps alone and writes nothing.
     *
     * @param options the options of the muxer that will write the file
     */
    private Mp4Muxer(Mp4MuxerOptions options) {
        this.output = null;
        this.options = options.toBuilder().fastStart(true).build();
        this.tracks = new ArrayList<>();
        this.planning = true;
        this.base = 0;
    }

    /**
     * Returns a muxer that plans the layout of a fast-start file without writing anything.
     *
     * <p>The caller declares the same tracks and feeds the same sample sizes and timestamps, in
     * the same order, through {@link #planSample} that it will later write through a muxer set
     * up with {@link #streamAfter(byte[])}; {@link #finishPlan()} then returns the header that
     * muxer needs.
     *
     * @param options the options of the muxer that will write the file
     * @return the planning muxer
     */
    static Mp4Muxer planner(Mp4MuxerOptions options) {
        return new Mp4Muxer(options);
    }

    /**
     * Records the size and timing of one sample in a planning muxer.
     *
     * @param trackId the identifier of the sample's track
     * @param size the sample size in bytes
     * @param pts the presentation timestamp, or {@link Packet#NO_TIMESTAMP}
     * @param dts the decoding timestamp, or {@link Packet#NO_TIMESTAMP}
     * @param duration the duration, or {@link Packet#UNKNOWN_DURATION}
     * @param keyframe whether the sample is a sync sample
     * @throws IllegalStateException if this muxer does not plan or is finished
     * @throws IllegalArgumentException if the track was not declared or its decoding timestamps
     * decrease
     */
    void planSample(int trackId, int size, long pts, long dts, long duration, boolean keyframe) {
        if (!planning) {
            throw new IllegalStateException("not a planning muxer");
        }
        accept(trackId, null, 0, size, pts, dts, duration, keyframe, false);
    }

    /**
     * Completes a plan and returns the {@code ftyp}, {@code moov} and {@code mdat} header of the
     * planned file.
     *
     * @return the header bytes
     * @throws IllegalStateException if this muxer does not plan or is finished
     */
    byte[] finishPlan() {
        if (!planning) {
            throw new IllegalStateException("not a planning muxer");
        }
        finish();
        return plannedHeader;
    }

    /**
     * Makes this muxer write a planned header immediately and then store every chunk right after
     * it, instead of spooling the media data.
     *
     * <p>The packets written afterwards must have exactly the sizes and timestamps the plan was
     * built from; {@link #finish()} recomputes the header and fails if it differs.
     *
     * @param header the header returned by {@link #finishPlan()}
     * @throws IllegalStateException if the muxer is not a fast-start muxer or a packet was already
     * written
     */
    void streamAfter(byte[] header) {
        if (planning || !options.fastStart() || started || finished || plannedHeader != null) {
            throw new IllegalStateException("cannot stream after a planned header now");
        }
        plannedHeader = header.clone();
        write(0, ByteBuffer.wrap(plannedHeader));
    }

    /**
     * Returns the presentation duration of the finished file: the longest track, edit lists
     * included.
     *
     * @return the duration in microseconds, or {@code 0} before {@link #finish()}
     */
    long durationMicros() {
        return durationMicros;
    }

    /**
     * Makes the muxer write every chunk offset as a 64-bit {@code co64} entry and the
     * {@code mdat} header with a 64-bit size, as it does on its own once an offset or the media
     * data exceeds 32 bits. A test hook: the large layout is otherwise reached only by files
     * over 4 GiB.
     */
    void forceLargeOffsets() {
        largeOffsets = true;
    }

    /**
     * Returns whether an MP4 file of a brand can carry a codec, so that {@link #addTrack(Track)}
     * accepts a track of it given the configuration the codec needs.
     *
     * <p>Every brand carries AAC, Opus, MP3, MP2, ALAC, FLAC, AMR-NB, AMR-WB, AC-3 and E-AC-3;
     * {@link Mp4MuxerOptions.Brand#MP4} and {@link Mp4MuxerOptions.Brand#QUICKTIME} add H.264, HEVC,
     * VP8, VP9, AV1, MPEG-4 Part 2, H.263, Motion-JPEG, the 16-, 24- and 32-bit integer and 32- and
     * 64-bit float PCM formats, {@link Codec#MOV_TEXT}, {@link Codec#WEBVTT} and {@link Codec#TTML};
     * only {@link Mp4MuxerOptions.Brand#QUICKTIME} carries ProRes, DNxHD, 8-bit PCM, A-law and
     * mu-law.
     *
     * @param brand the brand of the file
     * @param codec the codec of the track
     * @return {@code true} when the file can carry the codec
     */
    public static boolean supports(Mp4MuxerOptions.Brand brand, Codec codec) {
        Objects.requireNonNull(brand, "brand");
        return switch (Objects.requireNonNull(codec, "codec")) {
            case AAC, OPUS, MP3, MP2, ALAC, FLAC, AMR_NB, AMR_WB, AC3, EAC3 -> true;
            case H264, HEVC, VP8, VP9, AV1, MPEG4_PART2, H263, MJPEG, MOV_TEXT, WEBVTT, TTML, PCM_S16LE, PCM_S16BE,
                 PCM_S24LE, PCM_S24BE, PCM_S32LE, PCM_S32BE, PCM_F32LE, PCM_F32BE, PCM_F64LE, PCM_F64BE ->
                    brand != Mp4MuxerOptions.Brand.M4A;
            case PRORES, DNXHD, PCM_U8, PCM_S8, PCM_ALAW, PCM_MULAW -> brand == Mp4MuxerOptions.Brand.QUICKTIME;
            default -> false;
        };
    }

    /**
     * {@inheritDoc}
     *
     * <p>The codecs are those {@link #supports} lists for the brand. H.264 and HEVC need their
     * {@code avcC}/{@code hvcC} configuration (the parameter sets of HEVC layers above the base
     * layer an {@code hvcC} holds are written to an {@code lhvC} box); VP8, VP9 and AV1 (written as {@code vp08},
     * {@code vp09} and {@code av01}) take their {@code vpcC}/{@code av1C} record from the track or,
     * when it has none, derive it from the first key frame's headers; MPEG-4 Part 2 is an
     * {@code mp4v} entry whose {@code esds} holds the track's decoder configuration or the headers
     * that precede the first picture of the first frame; H.263 is {@code s263} with a {@code d263}
     * box ({@code h263} in a QuickTime file); Motion-JPEG is {@code mp4v} with the JPEG object
     * type in an ISO file and {@code jpeg} ({@code mjpa} for Motion-JPEG format A frames,
     * {@code mjpb} for format B frames) in a QuickTime file. AAC needs its
     * {@code AudioSpecificConfig}, Opus its {@code OpusHead}, ALAC its {@code ALACSpecificConfig}
     * and FLAC its metadata blocks; MP3 and MP2 are {@code mp4a}
     * entries with the MPEG-1 or MPEG-2 audio object type; AMR-NB and AMR-WB are {@code samr} and
     * {@code sawb} with a {@code damr} box listing the modes the samples use, each packet holding
     * storage-format frames. PCM tracks need their sampling rate and channel layout, and each packet
     * a whole number of frames; an ISO file stores them as {@code ipcm} or {@code fpcm} with a
     * {@code pcmC} box (ISO/IEC 23003-5) and a {@code chnl} box, a QuickTime file as a version 1
     * sound description ({@code twos}, {@code sowt}, {@code raw }, {@code in24}, {@code in32},
     * {@code fl32}, {@code fl64}, {@code alaw}, {@code ulaw}) or, for the encodings without one,
     * more than two channels or rates above 65535 Hz, a version 2 description ({@code lpcm} for
     * linear PCM), with a {@code chan} box. ProRes and DNxHD (QuickTime only) take their sample
     * entry type from the track's four-character configuration, or derive it from the first frame
     * when the track has none. The text subtitle codecs are {@link Codec#MOV_TEXT} (a
     * {@code tx3g} track) and {@link Codec#WEBVTT} (a {@code wvtt} track); their packets may
     * overlap and leave gaps, as cues do: the muxer cuts them into the gapless samples MP4 stores,
     * each holding the cues shown during it, starting the track at zero with an empty sample.
     * {@link Codec#TTML} is an {@code stpp} track (with the {@code subt} handler) whose packets are
     * stored as they are, one document per sample; they must not overlap, and a gap, the start of
     * the track included, becomes a sample holding a TTML document that shows nothing.
     *
     * @throws IllegalArgumentException if the id is already used, the codec is not supported, the
     * configuration it needs is missing or a TTML configuration is malformed, a PCM track has no
     * sampling rate or channel layout, or the brand cannot carry the track
     */
    @Override
    public void addTrack(Track track) {
        Objects.requireNonNull(track, "track");
        if (started || finished) {
            throw new IllegalStateException("tracks must be declared before the first packet");
        }
        for (var existing : tracks) {
            if (existing.track.id() == track.id()) {
                throw new IllegalArgumentException("track id " + track.id() + " is already used");
            }
        }
        var codec = track.codec();
        if (!supports(options.brand(), codec)) {
            if (options.brand() == Mp4MuxerOptions.Brand.M4A && supports(Mp4MuxerOptions.Brand.MP4, codec)) {
                throw new IllegalArgumentException(codec.kind() != TrackKind.AUDIO ? "the M4A brand carries audio only"
                        : "the M4A brand does not carry " + codec.displayName() + "; the MP4 and QuickTime brands do");
            }
            if (supports(Mp4MuxerOptions.Brand.QUICKTIME, codec)) {
                throw new IllegalArgumentException(codec.displayName() + " tracks need the QuickTime brand");
            }
            throw new IllegalArgumentException("MP4 muxing of " + codec.displayName() + " is not supported");
        }
        MovieHeaderWriter.validateConfig(track);
        var state = new MuxTrack(track, tracks.size() + 1);
        state.quickTime = options.brand() == Mp4MuxerOptions.Brand.QUICKTIME;
        if (TextSamples.handles(codec)) {
            textTracks.add(new TextSamples(track.id(), codec, this::writeTextSample));
        }
        tracks.add(state);
    }

    /**
     * {@inheritDoc}
     *
     * <p>A FLAC configuration replaces the track's until {@link #finish()} writes the movie header,
     * so the {@code dfLa} box carries the final {@code STREAMINFO}; a header planned up front, and
     * every other codec, keep their configuration.
     *
     * @throws IllegalArgumentException if the track was not declared, or a FLAC configuration does
     * not start with {@code STREAMINFO}
     * @throws IllegalStateException if the muxer is finished
     */
    @Override
    public void updateCodecConfig(int trackId, byte[] config) {
        Objects.requireNonNull(config, "config");
        if (finished) {
            throw new IllegalStateException("the muxer is finished");
        }
        MuxTrack target = null;
        for (var existing : tracks) {
            if (existing.track.id() == trackId) {
                target = existing;
            }
        }
        if (target == null) {
            throw new IllegalArgumentException("undeclared track " + trackId);
        }
        if (target.track.codec() != Codec.FLAC || plannedHeader != null || planning) {
            return;
        }
        AudioConfigs.parseFlac(config);
        target.config = config.clone();
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
     * <p>The tags become a {@code udta} box at the end of {@code moov}: an iTunes {@code ilst}
     * with cover art in {@code covr}, and a Nero {@code chpl} box for the chapters. The iTunes
     * integer atoms ({@code tmpo}, {@code rtng}, {@code stik}, {@code pgap}, {@code hdvd},
     * {@code tvsn}, {@code tves}, {@code plID} and the other store identifiers) take decimal
     * values. The chapters are also written as a QuickTime chapter track: a disabled text track,
     * after the declared tracks, whose samples hold the titles, follow the media data and are
     * referenced through {@code tref/chap} by every audio and video track.
     *
     * @throws MediaException.Unsupported if the value of an iTunes integer atom is not an integer
     * the atom holds
     */
    @Override
    public void setTags(MediaTags tags) {
        Objects.requireNonNull(tags, "tags");
        if (started || finished) {
            throw new IllegalStateException("tags must be set before the first packet");
        }
        this.userData = MuxerTags.mp4UserData(tags);
        this.chapters = tags.chapters();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Timestamps may be {@link Packet#NO_TIMESTAMP}: a missing presentation timestamp equals
     * the decoding timestamp, a missing decoding timestamp follows the previous packet, and a
     * track whose first packet has a presentation timestamp only is treated as presentation-timed
     * throughout.
     *
     * @throws IllegalArgumentException if the track was not declared, its decoding timestamps
     * decrease, a new codec configuration is invalid, the packet of a PCM track does not hold a
     * whole number of frames or takes the track past {@code 2^32 - 1} frames, or the packet of a
     * TTML track starts before zero or before the previous one ends
     * @throws MediaException.Unsupported if the packet changes the codec configuration of a text
     * track or of a codec whose sample description holds none (MP3, MP2, AMR, AC-3, E-AC-3, PCM,
     * H.263, Motion-JPEG)
     */
    @Override
    public void writePacket(Packet packet) {
        Objects.requireNonNull(packet, "packet");
        if (planning) {
            throw new IllegalStateException("a planning muxer takes sample sizes, not packets");
        }
        if (!textTracks.isEmpty()) {
            for (var text : textTracks) {
                if (text.trackId() == packet.trackId()) {
                    if (finished) {
                        throw new IllegalStateException("the muxer is finished");
                    }
                    for (var track : tracks) {
                        if (track.track.id() == packet.trackId()) {
                            ConfigChanges.reject(packet, track.track.codecConfig().orElse(null), "MP4");
                        }
                    }
                    text.offer(packet);
                    return;
                }
            }
        }
        var data = packet.data();
        for (var track : tracks) {
            if (track.track.id() == packet.trackId() && track.codec() == Codec.PRORES && !hasProResPrefix(data)) {
                data = withProResPrefix(data);
            }
        }
        var newConfig = packet.sideData().newCodecConfig().orElse(null);
        if (newConfig != null) {
            changeCodecConfig(packet.trackId(), newConfig);
        }
        accept(packet.trackId(), data, 0, data.length, packet.pts(), packet.dts(), packet.duration(), packet.keyframe(),
                spooling());
    }

    /**
     * Makes the following samples of a track use the sample description of a codec configuration,
     * adding the description when no earlier one of the track has that configuration.
     *
     * @param trackId the identifier of the track
     * @param config the configuration, in the form {@link Track#codecConfig()} describes
     * @throws IllegalArgumentException if the track was not declared or the configuration is
     * invalid for its codec
     * @throws IllegalStateException if the muxer is finished
     * @throws MediaException.Unsupported if the track's codec has no configuration in its sample
     * description that could change
     */
    void changeCodecConfig(int trackId, byte[] config) {
        if (finished) {
            throw new IllegalStateException("the muxer is finished");
        }
        MuxTrack target = null;
        for (var existing : tracks) {
            if (existing.track.id() == trackId) {
                target = existing;
            }
        }
        if (target == null) {
            throw new IllegalArgumentException("undeclared track " + trackId);
        }
        var current = target.entry == 0 ? target.config : target.extraConfigs.get(target.entry - 1);
        if (Arrays.equals(current, config)) {
            return;
        }
        switch (target.codec()) {
            case H264, HEVC, VP8, VP9, AV1, MPEG4_PART2, AAC, OPUS, ALAC, FLAC, PRORES, DNXHD -> {
            }
            default -> throw ConfigChanges.unsupported("MP4", trackId);
        }
        var index = -1;
        if (Arrays.equals(target.config, config)) {
            index = 0;
        }
        for (var extra = 0; extra < target.extraConfigs.size() && index < 0; extra++) {
            if (Arrays.equals(target.extraConfigs.get(extra), config)) {
                index = extra + 1;
            }
        }
        if (index < 0) {
            MovieHeaderWriter.validateConfig(SampleEntryWriter.describe(target.track, config));
            target.extraConfigs.add(config.clone());
            index = target.extraConfigs.size();
        }
        if (target.pendingSamples > 0) {
            writeChunk(target);
        }
        target.entry = index;
    }

    /**
     * Makes a track's edit list the given one instead of the one derived from its timestamps.
     *
     * @param trackId the identifier of the track
     * @param edits three arrays: the duration of each edit and its media time (the presentation
     * timestamp presented first, {@code -1} for an empty edit), both in the track's time base, and
     * its integer media rate
     * @throws IllegalArgumentException if the track was not declared or the arrays differ in length
     */
    void setEdits(int trackId, long[][] edits) {
        if (edits.length != 3 || edits[0].length != edits[1].length || edits[0].length != edits[2].length) {
            throw new IllegalArgumentException("inconsistent edit list");
        }
        for (var existing : tracks) {
            if (existing.track.id() == trackId) {
                existing.edits = new long[][]{edits[0].clone(), edits[1].clone(), edits[2].clone()};
                return;
            }
        }
        throw new IllegalArgumentException("undeclared track " + trackId);
    }

    /**
     * Returns whether a ProRes frame starts with its frame size and {@code icpf} identifier, which
     * QuickTime samples carry and Matroska blocks omit.
     *
     * @param frame the coded ProRes frame
     * @return {@code true} when the prefix is present
     */
    static boolean hasProResPrefix(byte[] frame) {
        return frame.length >= 8 && frame[4] == 'i' && frame[5] == 'c' && frame[6] == 'p' && frame[7] == 'f';
    }

    /**
     * Restores the frame size and {@code icpf} identifier of a ProRes frame from a Matroska block.
     *
     * @param frame the frame without its prefix
     * @return a new array holding the prefixed frame
     */
    static byte[] withProResPrefix(byte[] frame) {
        var out = new byte[frame.length + 8];
        var size = out.length;
        out[0] = (byte) (size >>> 24);
        out[1] = (byte) (size >>> 16);
        out[2] = (byte) (size >>> 8);
        out[3] = (byte) size;
        out[4] = 'i';
        out[5] = 'c';
        out[6] = 'p';
        out[7] = 'f';
        System.arraycopy(frame, 0, out, 8, frame.length);
        return out;
    }

    /**
     * Writes one sample of a text subtitle track.
     *
     * @param trackId the track identifier
     * @param data the sample, a new array
     * @param time the sample time
     * @param duration the sample duration
     */
    private void writeTextSample(int trackId, byte[] data, long time, long duration) {
        accept(trackId, data, 0, data.length, time, time, duration, true, spooling());
    }

    /**
     * Writes one sample whose bytes are copied from a range of an array, as
     * {@link #writePacket(Packet)} writes a packet.
     *
     * <p>The array may be reused by the caller as soon as this method returns.
     *
     * @param trackId the track identifier
     * @param data the array holding the sample bytes
     * @param offset the index of the first sample byte
     * @param size the sample size
     * @param pts the presentation timestamp, or {@link Packet#NO_TIMESTAMP}
     * @param dts the decoding timestamp, or {@link Packet#NO_TIMESTAMP}
     * @param duration the duration, or {@link Packet#UNKNOWN_DURATION}
     * @param keyframe whether the sample is a sync sample
     * @throws IllegalStateException if the muxer is finished or only plans
     * @throws IllegalArgumentException if the track was not declared or its decoding timestamps
     * decrease
     * @throws MediaException.Io if writing the output fails
     */
    void writeSample(int trackId, byte[] data, int offset, int size, long pts, long dts, long duration, boolean keyframe) {
        if (planning) {
            throw new IllegalStateException("a planning muxer takes sample sizes, not packets");
        }
        accept(trackId, data, offset, size, pts, dts, duration, keyframe, false);
    }

    /**
     * Returns whether the media data goes to the spool, where chunks are assembled from payloads
     * held by reference instead of copies.
     *
     * @return {@code true} for a fast-start muxer without a planned header
     */
    private boolean spooling() {
        return options.fastStart() && plannedHeader == null && !planning;
    }

    /**
     * Routes a sample to its declared track.
     *
     * @param trackId the track identifier
     * @param data the array holding the sample bytes, or {@code null} when planning
     * @param offset the index of the first sample byte in {@code data}
     * @param size the sample size
     * @param pts the presentation timestamp
     * @param dts the decoding timestamp
     * @param duration the sample duration in track time base units
     * @param keyframe whether the sample is a sync sample
     * @param hold whether {@code data} is an immutable whole-sample array that the chunk may hold
     * by reference
     * @throws IllegalStateException if the muxer is finished
     * @throws IllegalArgumentException if the track was not declared or its decoding timestamps
     * decrease
     */
    private void accept(int trackId, byte[] data, int offset, int size, long pts, long dts, long duration, boolean keyframe,
                        boolean hold) {
        if (finished) {
            throw new IllegalStateException("the muxer is finished");
        }
        for (var candidate : tracks) {
            if (candidate.track.id() == trackId) {
                record(candidate, data, offset, size, pts, dts, duration, keyframe, hold);
                return;
            }
        }
        throw new IllegalArgumentException("sample for undeclared track " + trackId);
    }

    /**
     * Adds a sample to its track and to the chunk being assembled, writing the previous chunk
     * when the sample starts a new one.
     *
     * @param track the track the sample belongs to
     * @param data the array holding the sample bytes, or {@code null} when planning
     * @param offset the index of the first sample byte in {@code data}
     * @param size the sample size
     * @param pts the presentation timestamp
     * @param dts the decoding timestamp
     * @param duration the sample duration in track time base units
     * @param keyframe whether the sample is a sync sample
     * @param hold whether the chunk may hold {@code data} by reference instead of copying it
     */
    private void record(MuxTrack track, byte[] data, int offset, int size, long pts, long dts, long duration, boolean keyframe,
                        boolean hold) {
        started = true;
        if (keyframe) {
            track.deriveConfig(data, offset, size);
        }
        track.addSample(size, pts, dts, duration, keyframe, data, offset);
        var last = track.count - 1;
        var time = track.toMicros(track.ptsOnly ? track.pts[last] : track.dts[last]);
        if (track.pendingSamples > 0 && (time - track.pendingStartMicros >= options.interleaveMicros()
                || track.pendingBytes + size > MAX_PENDING_BYTES)) {
            writeChunk(track);
        }
        if (track.pendingSamples == 0) {
            track.pendingStartMicros = time;
        }
        if (hold) {
            track.holdPending(data);
        } else {
            track.appendPending(data, offset, size);
        }
    }

    /**
     * Writes the pending chunk of a track.
     *
     * @param track the track whose pending samples are written
     */
    private void writeChunk(MuxTrack track) {
        var offset = dataSize;
        if (track.heldCount > 0) {
            if (spool == null) {
                spool = new MdatSpool(options.temporaryDirectory().orElse(null));
            }
            for (var index = 0; index < track.heldCount; index++) {
                spool.hold(track.held[index]);
            }
            dataSize += track.pendingBytes;
        } else {
            writeData(track.pending, track.pendingBytes);
        }
        track.closeChunk(offset);
    }

    /**
     * Appends media data to the spool or the output.
     *
     * @param data the bytes
     * @param length the number of bytes from index zero
     */
    private void writeData(byte[] data, int length) {
        if (planning) {
            dataSize += length;
            return;
        }
        if (plannedHeader != null) {
            write(plannedHeader.length + dataSize, ByteBuffer.wrap(data, 0, length));
            dataSize += length;
            return;
        }
        if (options.fastStart()) {
            if (spool == null) {
                spool = new MdatSpool(options.temporaryDirectory().orElse(null));
            }
            spool.write(data, 0, length);
        } else {
            if (dataStart < 0) {
                writeProgressiveHeader();
            }
            write(dataStart + dataSize, ByteBuffer.wrap(data, 0, length));
        }
        dataSize += length;
    }

    /**
     * Writes the {@code ftyp}, a {@code free} placeholder and the {@code mdat} header of a file
     * without fast start.
     */
    private void writeProgressiveHeader() {
        var writer = new BoxWriter(64);
        writeFileType(writer);
        writer.begin(BoxTypes.FREE);
        writer.end();
        writer.u32(0);
        writer.u32(BoxTypes.MDAT);
        write(0, ByteBuffer.wrap(writer.buffer(), 0, writer.size()));
        dataStart = writer.size();
    }

    /**
     * Writes bytes at a file offset.
     *
     * @param position the offset relative to the start of the file
     * @param buffer the bytes
     */
    private void write(long position, ByteBuffer buffer) {
        output.position(base + position);
        output.write(buffer);
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalStateException if the muxer is already finished, or a VP9 or AV1 track has no
     * configuration and no key frame it can be derived from
     */
    @Override
    public void finish() {
        if (finished) {
            throw new IllegalStateException("the muxer is already finished");
        }
        for (var text : textTracks) {
            text.finish();
        }
        finished = true;
        var pending = new ArrayList<MuxTrack>();
        for (var track : tracks) {
            if (track.pendingSamples > 0) {
                pending.add(track);
            }
        }
        pending.sort(Comparator.comparingLong(track -> track.pendingStartMicros));
        for (var track : pending) {
            writeChunk(track);
        }
        var plans = new ArrayList<TrackPlan>();
        for (var track : tracks) {
            track.deriveDecodingTimes();
            plans.add(new TrackPlan(track));
        }
        var movieTimescale = movieTimescale();
        var movieDuration = 0L;
        for (var plan : plans) {
            plan.resolveEdits(movieTimescale);
            movieDuration = Math.max(movieDuration, plan.trackDuration);
            durationMicros = Math.max(durationMicros, Mp4Track.rescale(plan.trackDuration, movieTimescale, 1_000_000));
        }
        for (var plan : plans) {
            plan.boundSubtitles(movieDuration);
        }
        if (!chapters.isEmpty() && !tracks.isEmpty()) {
            chapterTrack = Mp4ChapterTrack.of(chapters, durationMicros);
            chapterDataOffset = dataSize;
            writeData(chapterTrack.data(), chapterTrack.dataLength());
        }
        if (planning) {
            plannedHeader = fastStartHeader(plans, movieTimescale);
        } else if (plannedHeader != null) {
            if (!Arrays.equals(plannedHeader, fastStartHeader(plans, movieTimescale))) {
                throw new IllegalStateException("the written samples diverge from the planned layout");
            }
        } else if (options.fastStart()) {
            finishFastStart(plans, movieTimescale);
        } else {
            finishProgressive(plans, movieTimescale);
        }
    }

    /**
     * Writes a fast-start file: {@code ftyp}, {@code moov}, then the spooled {@code mdat}.
     *
     * @param plans the track layouts
     * @param movieTimescale the movie timescale
     */
    private void finishFastStart(List<TrackPlan> plans, long movieTimescale) {
        write(0, ByteBuffer.wrap(fastStartHeader(plans, movieTimescale)));
        if (spool != null) {
            spool.transferTo(output);
            spool.close();
            spool = null;
        }
    }

    /**
     * Serializes the {@code ftyp}, the {@code moov} and the {@code mdat} header of a fast-start
     * file whose media data directly follows them.
     *
     * @param plans the track layouts
     * @param movieTimescale the movie timescale
     * @return the header bytes
     */
    private byte[] fastStartHeader(List<TrackPlan> plans, long movieTimescale) {
        var fileType = new BoxWriter(64);
        writeFileType(fileType);
        var mdatHeader = largeOffsets || 8 + dataSize > U32_MAX ? 16 : 8;
        var co64 = largeOffsets;
        var moov = buildMovie(plans, movieTimescale, 0, co64);
        var start = fileType.size() + moov.length + mdatHeader;
        if (!co64 && start + dataSize > U32_MAX) {
            co64 = true;
            moov = buildMovie(plans, movieTimescale, 0, true);
            start = fileType.size() + moov.length + mdatHeader;
        }
        moov = buildMovie(plans, movieTimescale, start, co64);
        var header = new BoxWriter(16);
        if (mdatHeader == 16) {
            header.u32(1);
            header.u32(BoxTypes.MDAT);
            header.u64(16 + dataSize);
        } else {
            header.u32(8 + dataSize);
            header.u32(BoxTypes.MDAT);
        }
        var result = new byte[fileType.size() + moov.length + header.size()];
        System.arraycopy(fileType.buffer(), 0, result, 0, fileType.size());
        System.arraycopy(moov, 0, result, fileType.size(), moov.length);
        System.arraycopy(header.buffer(), 0, result, fileType.size() + moov.length, header.size());
        return result;
    }

    /**
     * Completes a file without fast start: patches the {@code mdat} size and appends the
     * {@code moov}.
     *
     * @param plans the track layouts
     * @param movieTimescale the movie timescale
     */
    private void finishProgressive(List<TrackPlan> plans, long movieTimescale) {
        if (dataStart < 0) {
            writeProgressiveHeader();
        }
        var header = new BoxWriter(16);
        if (largeOffsets || 8 + dataSize > U32_MAX) {
            header.u32(1);
            header.u32(BoxTypes.MDAT);
            header.u64(16 + dataSize);
            write(dataStart - 16, ByteBuffer.wrap(header.buffer(), 0, header.size()));
        } else {
            header.u32(8 + dataSize);
            write(dataStart - 8, ByteBuffer.wrap(header.buffer(), 0, header.size()));
        }
        var co64 = largeOffsets || dataStart + dataSize > U32_MAX;
        var moov = buildMovie(plans, movieTimescale, dataStart, co64);
        write(dataStart + dataSize, ByteBuffer.wrap(moov));
    }

    /**
     * Releases the spool of an unfinished fast-start file; the output is not closed.
     */
    @Override
    public void close() {
        if (spool != null) {
            try {
                spool.close();
            } finally {
                spool = null;
            }
        }
        finished = true;
    }

    /**
     * Returns the movie timescale: the audio timescale of an audio-only file, so edit lists trim
     * priming to the sample, and {@value #MOVIE_TIMESCALE} otherwise.
     *
     * @return the movie timescale
     */
    private long movieTimescale() {
        var timescale = 0L;
        for (var track : tracks) {
            if (track.kind() != TrackKind.AUDIO) {
                return MOVIE_TIMESCALE;
            }
            if (timescale == 0) {
                timescale = track.timescale;
            }
        }
        return timescale == 0 ? MOVIE_TIMESCALE : timescale;
    }

    /**
     * Writes the {@code ftyp} box of the configured brand.
     *
     * @param writer the box writer of the output
     */
    private void writeFileType(BoxWriter writer) {
        writer.begin(BoxTypes.FTYP);
        if (options.brand() == Mp4MuxerOptions.Brand.M4A) {
            writer.u32(BoxTypes.M4A);
            writer.u32(0x200);
            writer.u32(BoxTypes.M4A);
            writer.u32(BoxTypes.ISOM);
            writer.u32(BoxTypes.ISO2);
            writer.u32(BoxTypes.MP42);
        } else if (options.brand() == Mp4MuxerOptions.Brand.QUICKTIME) {
            writer.u32(BoxTypes.QT);
            writer.u32(0x200);
            writer.u32(BoxTypes.QT);
        } else {
            writer.u32(BoxTypes.ISOM);
            writer.u32(0x200);
            writer.u32(BoxTypes.ISOM);
            writer.u32(BoxTypes.ISO2);
            writer.u32(BoxTypes.AVC1);
            writer.u32(BoxTypes.MP41);
        }
        writer.end();
    }

    /**
     * Serializes the {@code moov} box.
     *
     * @param plans the track layouts
     * @param movieTimescale the movie timescale
     * @param dataStart the file offset of the first media data byte
     * @param co64 whether chunk offsets are 64-bit
     * @return the box bytes
     */
    private byte[] buildMovie(List<TrackPlan> plans, long movieTimescale, long dataStart, boolean co64) {
        var writer = new BoxWriter(4096);
        var duration = 0L;
        for (var plan : plans) {
            duration = Math.max(duration, plan.trackDuration);
        }
        writer.begin(BoxTypes.MOOV);
        var chapterId = tracks.size() + 1;
        MovieHeaderWriter.writeMovieHeader(writer, movieTimescale, duration,
                chapterTrack != null ? chapterId + 1 : chapterId);
        var media = false;
        for (var track : tracks) {
            media |= track.kind() == TrackKind.AUDIO || track.kind() == TrackKind.VIDEO;
        }
        for (var plan : plans) {
            var kind = plan.track.kind();
            var references = chapterTrack != null && (kind == TrackKind.AUDIO || kind == TrackKind.VIDEO
                    || !media && plan.track == tracks.getFirst());
            writeTrack(writer, plan, dataStart, co64, references ? chapterId : -1);
        }
        if (chapterTrack != null) {
            writer.bytes(chapterTrack.trak(chapterId, movieTimescale, dataStart + chapterDataOffset, co64));
        }
        writer.bytes(userData);
        writer.end();
        return writer.toByteArray();
    }

    /**
     * Serializes one {@code trak} box.
     *
     * @param writer the box writer of the output
     * @param plan the track layout
     * @param dataStart the file offset of the first media data byte
     * @param co64 whether chunk offsets are 64-bit
     * @param chapterId the identifier of the chapter track the track references, or {@code -1}
     */
    private void writeTrack(BoxWriter writer, TrackPlan plan, long dataStart, boolean co64, int chapterId) {
        writer.begin(BoxTypes.TRAK);
        MovieHeaderWriter.writeTrackHeader(writer, plan.track, plan.trackDuration);
        if (chapterId > 0) {
            writer.bytes(Mp4ChapterTrack.trackReference(chapterId));
        }
        if (plan.hasEdits) {
            writer.begin(BoxTypes.EDTS);
            var wideEdits = false;
            for (var index = 0; index < plan.editDurations.length; index++) {
                wideEdits |= plan.editDurations[index] > U32_MAX || plan.editTimes[index] > Integer.MAX_VALUE
                        || plan.editTimes[index] < -1;
            }
            writer.beginFull(BoxTypes.ELST, wideEdits ? 1 : 0, 0);
            writer.u32(plan.editDurations.length);
            for (var index = 0; index < plan.editDurations.length; index++) {
                MovieHeaderWriter.writeLength(writer, plan.editDurations[index], wideEdits);
                MovieHeaderWriter.writeLength(writer, plan.editTimes[index], wideEdits);
                writer.u32((plan.editRates[index] & 0xFFFF) << 16);
            }
            writer.end();
            writer.end();
        }
        MovieHeaderWriter.writeMedia(writer, plan.track, plan.mediaDuration,
                media -> writeSampleTable(media, plan, dataStart, co64));
        writer.end();
    }

    /**
     * Serializes the {@code stbl} box of a track.
     *
     * @param writer the box writer of the output
     * @param plan the track layout
     * @param dataStart the file offset of the first media data byte
     * @param co64 whether chunk offsets are 64-bit
     */
    private void writeSampleTable(BoxWriter writer, TrackPlan plan, long dataStart, boolean co64) {
        var track = plan.track;
        var count = track.count;
        writer.begin(BoxTypes.STBL);
        writer.beginFull(BoxTypes.STSD, 0, 0);
        writer.u32(1 + track.extraConfigs.size());
        SampleEntryWriter.write(writer, plan);
        for (var extra = 0; extra < track.extraConfigs.size(); extra++) {
            SampleEntryWriter.write(writer, plan, extra + 1);
        }
        writer.end();
        if (track.pcmFrameBytes > 0) {
            writePcmTables(writer, track, dataStart, co64);
            writer.end();
            return;
        }

        writer.beginFull(BoxTypes.STTS, 0, 0);
        var runsPosition = writer.size();
        writer.u32(0);
        var runs = 0;
        for (var index = 0; index < count; ) {
            var delta = Math.min(plan.durations[index], U32_MAX);
            var end = index + 1;
            while (end < count && Math.min(plan.durations[end], U32_MAX) == delta) {
                end++;
            }
            writer.u32(end - index);
            writer.u32(delta);
            runs++;
            index = end;
        }
        writer.patch32(runsPosition, runs);
        writer.end();

        if (plan.hasCompositionOffsets) {
            writer.beginFull(BoxTypes.CTTS, plan.negativeCompositionOffsets ? 1 : 0, 0);
            runsPosition = writer.size();
            writer.u32(0);
            runs = 0;
            for (var index = 0; index < count; ) {
                var offset = compositionOffset(track, index);
                var end = index + 1;
                while (end < count && compositionOffset(track, end) == offset) {
                    end++;
                }
                writer.u32(end - index);
                writer.u32(offset & U32_MAX);
                runs++;
                index = end;
            }
            writer.patch32(runsPosition, runs);
            writer.end();
        }

        if (!plan.allKeyframes) {
            writer.beginFull(BoxTypes.STSS, 0, 0);
            var keys = 0;
            for (var index = 0; index < count; index++) {
                if (track.keyframes[index]) {
                    keys++;
                }
            }
            writer.u32(keys);
            for (var index = 0; index < count; index++) {
                if (track.keyframes[index]) {
                    writer.u32(index + 1);
                }
            }
            writer.end();
        }

        writer.beginFull(BoxTypes.STSC, 0, 0);
        runsPosition = writer.size();
        writer.u32(0);
        runs = 0;
        var previous = -1;
        var previousEntry = -1;
        for (var chunk = 0; chunk < track.chunkCount; chunk++) {
            if (track.chunkSamples[chunk] != previous || track.chunkEntries[chunk] != previousEntry) {
                previous = track.chunkSamples[chunk];
                previousEntry = track.chunkEntries[chunk];
                writer.u32(chunk + 1);
                writer.u32(previous);
                writer.u32(previousEntry + 1);
                runs++;
            }
        }
        writer.patch32(runsPosition, runs);
        writer.end();

        writer.beginFull(BoxTypes.STSZ, 0, 0);
        var constant = count > 0 ? track.sizes[0] : 0;
        for (var index = 1; index < count && constant != 0; index++) {
            if (track.sizes[index] != constant) {
                constant = 0;
            }
        }
        writer.u32(constant);
        writer.u32(count);
        if (constant == 0) {
            for (var index = 0; index < count; index++) {
                writer.u32(track.sizes[index]);
            }
        }
        writer.end();

        writeChunkOffsets(writer, track, dataStart, co64);
        writer.end();
    }

    /**
     * Writes the {@code stco} or {@code co64} box of a track.
     *
     * @param writer the box writer, inside the {@code stbl} box
     * @param track the track whose chunks are listed
     * @param dataStart the file offset of the first media data byte
     * @param co64 whether chunk offsets are 64-bit
     */
    private static void writeChunkOffsets(BoxWriter writer, MuxTrack track, long dataStart, boolean co64) {
        writer.beginFull(co64 ? BoxTypes.CO64 : BoxTypes.STCO, 0, 0);
        writer.u32(track.chunkCount);
        for (var chunk = 0; chunk < track.chunkCount; chunk++) {
            var offset = dataStart + track.chunkOffsets[chunk];
            if (co64) {
                writer.u64(offset);
            } else {
                writer.u32(offset);
            }
        }
        writer.end();
    }

    /**
     * Writes the timing, chunk and size tables of a PCM track, which store one frame per sample:
     * every sample lasts one tick of the sampling-rate timescale and has the frame size, and each
     * chunk counts the frames of the packets it holds.
     *
     * @param writer the box writer, inside the {@code stbl} box after the {@code stsd}
     * @param track the PCM track
     * @param dataStart the file offset of the first media data byte
     * @param co64 whether chunk offsets are 64-bit
     */
    private static void writePcmTables(BoxWriter writer, MuxTrack track, long dataStart, boolean co64) {
        var frameBytes = track.pcmFrameBytes;
        writer.beginFull(BoxTypes.STTS, 0, 0);
        writer.u32(track.pcmFrames > 0 ? 1 : 0);
        if (track.pcmFrames > 0) {
            writer.u32(track.pcmFrames);
            writer.u32(1);
        }
        writer.end();

        writer.beginFull(BoxTypes.STSC, 0, 0);
        var runsPosition = writer.size();
        writer.u32(0);
        var runs = 0;
        var previous = -1L;
        var previousEntry = -1;
        var packet = 0;
        for (var chunk = 0; chunk < track.chunkCount; chunk++) {
            var frames = 0L;
            for (var index = 0; index < track.chunkSamples[chunk]; index++) {
                frames += track.sizes[packet++] / frameBytes;
            }
            if (frames != previous || track.chunkEntries[chunk] != previousEntry) {
                previous = frames;
                previousEntry = track.chunkEntries[chunk];
                writer.u32(chunk + 1);
                writer.u32(frames);
                writer.u32(previousEntry + 1);
                runs++;
            }
        }
        writer.patch32(runsPosition, runs);
        writer.end();

        writer.beginFull(BoxTypes.STSZ, 0, 0);
        writer.u32(frameBytes);
        writer.u32(track.pcmFrames);
        writer.end();
        writeChunkOffsets(writer, track, dataStart, co64);
    }

    /**
     * Returns the composition offset of a sample, clamped to 32 bits.
     *
     * @param track the track holding the sample
     * @param index the sample index
     * @return {@code pts - dts}
     */
    private static int compositionOffset(MuxTrack track, int index) {
        var offset = track.pts[index] - track.dts[index];
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, offset));
    }

    /**
     * Holds the derived layout of one track: durations, edit list and the flags that decide
     * which optional tables are written.
     */
    static final class TrackPlan {
        /**
         * The track.
         */
        final MuxTrack track;

        /**
         * The duration of each sample in media timescale units.
         */
        final long[] durations;

        /**
         * The sum of the sample durations.
         */
        final long mediaDuration;

        /**
         * Whether any sample's presentation timestamp differs from its decoding timestamp.
         */
        final boolean hasCompositionOffsets;

        /**
         * Whether any composition offset is negative.
         */
        final boolean negativeCompositionOffsets;

        /**
         * Whether every sample is a sync sample.
         */
        final boolean allKeyframes;

        /**
         * Whether the track gets an edit list.
         */
        boolean hasEdits;

        /**
         * The duration of each edit, in movie timescale units.
         */
        long[] editDurations = new long[0];

        /**
         * The media time of each edit in media timescale units, {@code -1} for an empty edit.
         */
        long[] editTimes = new long[0];

        /**
         * The integer media rate of each edit.
         */
        int[] editRates = new int[0];

        /**
         * The track duration in movie timescale units.
         */
        long trackDuration;

        /**
         * Derives the sample durations and table flags of a track.
         *
         * @param track the track the plan describes
         */
        TrackPlan(MuxTrack track) {
            this.track = track;
            this.durations = track.durations();
            var total = 0L;
            var composition = false;
            var negative = false;
            var keys = true;
            for (var index = 0; index < track.count; index++) {
                total += durations[index];
                var offset = track.pts[index] - track.dts[index];
                composition |= offset != 0;
                negative |= offset < 0;
                keys &= track.keyframes[index];
            }
            this.mediaDuration = total;
            this.hasCompositionOffsets = composition;
            this.negativeCompositionOffsets = negative;
            this.allKeyframes = keys;
        }

        /**
         * Derives the edit list and the track duration.
         *
         * @param movieTimescale the movie timescale
         */
        void resolveEdits(long movieTimescale) {
            if (track.count == 0) {
                trackDuration = 0;
                return;
            }
            if (track.edits != null) {
                useEdits(movieTimescale);
                return;
            }
            var firstDts = track.dts[0];
            var minPts = Long.MAX_VALUE;
            var naturalEnd = Long.MIN_VALUE;
            for (var index = 0; index < track.count; index++) {
                minPts = Math.min(minPts, track.pts[index]);
                naturalEnd = Math.max(naturalEnd, track.pts[index] + durations[index]);
            }
            long start;
            long presentationEnd;
            long mediaTime;
            if (track.kind() == TrackKind.AUDIO) {
                var firstPts = track.pts[0];
                start = Math.max(firstPts, 0);
                // packets that start before zero (Ogg Opus) already account for the priming
                mediaTime = Math.max(track.priming(), start - firstDts);
                presentationEnd = start + (mediaDuration - mediaTime);
            } else {
                start = Math.max(minPts, 0);
                mediaTime = start - firstDts;
                presentationEnd = naturalEnd;
            }
            mediaTime = Math.max(mediaTime, 0);
            var declared = track.track.duration();
            if (declared > 0) {
                var declaredEnd = track.toMedia(declared);
                if (declaredEnd > start && declaredEnd < presentationEnd) {
                    presentationEnd = declaredEnd;
                }
            }
            var segmentMedia = Math.max(presentationEnd - start, 0);
            hasEdits = start > 0 || mediaTime != 0 || segmentMedia != mediaDuration;
            var emptyEdit = Mp4Track.rescale(start, track.timescale, movieTimescale);
            var segment = Mp4Track.rescale(segmentMedia, track.timescale, movieTimescale);
            trackDuration = hasEdits ? emptyEdit + segment : Mp4Track.rescale(mediaDuration, track.timescale, movieTimescale);
            if (hasEdits && segment == 0) {
                hasEdits = false;
                trackDuration = Mp4Track.rescale(mediaDuration, track.timescale, movieTimescale);
            }
            if (hasEdits && emptyEdit > 0) {
                editDurations = new long[]{emptyEdit, segment};
                editTimes = new long[]{-1, mediaTime};
                editRates = new int[]{1, 1};
            } else if (hasEdits) {
                editDurations = new long[]{segment};
                editTimes = new long[]{mediaTime};
                editRates = new int[]{1};
            }
        }

        /**
         * Gives a subtitle track without an edit list that ends before the movie an edit covering
         * its media, so that readers do not extend its last sample to the end of the movie.
         *
         * @param movieDuration the movie duration, in movie timescale units
         */
        void boundSubtitles(long movieDuration) {
            if (track.kind() != TrackKind.DATA || hasEdits || trackDuration <= 0 || trackDuration >= movieDuration) {
                return;
            }
            hasEdits = true;
            editDurations = new long[]{trackDuration};
            editTimes = new long[]{0};
            editRates = new int[]{1};
        }

        /**
         * Converts the edit list the caller set to the movie and media timescales: an edit's media
         * time is a presentation timestamp, which becomes a composition time measured from the
         * track's first decoding timestamp.
         *
         * @param movieTimescale the movie timescale
         */
        private void useEdits(long movieTimescale) {
            var count = track.edits[0].length;
            editDurations = new long[count];
            editTimes = new long[count];
            editRates = new int[count];
            var elapsed = 0L;
            var written = 0L;
            for (var index = 0; index < count; index++) {
                elapsed += track.toMedia(track.edits[0][index]);
                var end = Mp4Track.rescale(elapsed, track.timescale, movieTimescale);
                editDurations[index] = Math.max(0, end - written);
                written = end;
                var time = track.edits[1][index];
                editTimes[index] = time < 0 ? -1 : Math.max(0, track.toMedia(time) - track.dts[0]);
                editRates[index] = (int) track.edits[2][index];
            }
            hasEdits = count > 0;
            trackDuration = hasEdits ? written : Mp4Track.rescale(mediaDuration, track.timescale, movieTimescale);
        }
    }
}
