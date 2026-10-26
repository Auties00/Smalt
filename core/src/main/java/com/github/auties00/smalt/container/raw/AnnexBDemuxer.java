package com.github.auties00.smalt.container.raw;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.container.internal.SeekTimes;
import com.github.auties00.smalt.internal.codec.NalConfigs;
import com.github.auties00.smalt.internal.codec.VideoConfigs;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.nal.NalUnits;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketFlag;
import com.github.auties00.smalt.track.PacketSideData;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.VideoTrack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.LongPredicate;

/**
 * Reads an H.264 ({@code .h264}, {@code .264}, {@code .jsv}) or HEVC ({@code .h265}, {@code .265}, {@code .bit})
 * elementary stream in the Annex B byte stream format: NAL units after {@code 00 00 01} start codes.
 *
 * <p>The stream becomes one {@link VideoTrack} of {@link Codec#H264} or {@link Codec#HEVC}, with identifier 1, whose
 * configuration record ({@code avcC} or {@code hvcC}) holds the parameter sets met before the first key frame (or,
 * in a stream whose first key frame comes late or never, within its first 256 access units or 8 MiB) and which
 * takes its picture size, color and sample aspect ratio from the first sequence parameter set. Each access unit
 * becomes one packet of NAL units with four-byte big-endian lengths, as an MP4 file stores them: access unit
 * delimiters are removed and every other NAL unit (parameter sets and SEI messages included) is kept. A new access
 * unit starts, once the current one holds a coded slice, at the NAL unit types ITU-T H.264 clause 7.4.1.2.3 and H.265
 * clause 7.4.2.4.4 list or at the first slice of a new picture. Two H.264 fields of opposite parity with the same
 * {@code frame_num}, coded one after the other, make one packet, as they make one frame. A packet is a key frame when
 * it holds an IDR picture or, for H.264, only intra slices and a recovery point SEI message; for HEVC, when it holds
 * an IRAP picture. An IDR or IRAP access unit at which the active sequence (and HEVC video) parameter sets differ
 * from those last reported carries a record of every active set as its
 * {@linkplain PacketSideData#newCodecConfig() new codec configuration}.
 *
 * <p>The stream holds no timestamps, so the demuxer derives them from the pictures' timing information and order
 * counts. The time base is one field period of the track's frame rate: the rate of the
 * {@linkplain ElementaryStreamOptions#frameRate() options} or, by default, of the timing information of the first
 * sequence parameter set (the HEVC video parameter set's when the sequence parameter set has none), else 25 frames
 * per second. A picture lasts as many fields as it is displayed for: a frame 2 ticks and a field coded alone 1 tick,
 * or, when picture timing SEI messages carry a {@code pic_struct}, 3 ticks for a frame shown with a repeated field,
 * 4 for a doubled frame and 6 for a tripled one. Unless the options set the frame rate, each picture's field period
 * is that of the timing information of its own sequence parameter set, so a stream whose frame rate changes is timed
 * at each rate, converted to the track's ticks without accumulating rounding errors. Presentation times add up the
 * durations of the pictures before each one in output order, starting at 0. A picture's decoding time is the
 * presentation time of the output picture that comes as many places earlier in output order than the number of
 * output pictures decoded before it as the stream's reorder depth, so the first decoding times are negative and no
 * decoding time follows its presentation time. The reorder depth is the one a decoder applies: H.264
 * {@code max_num_reorder_frames} when the VUI signals it, 0 for {@code pic_order_cnt_type} 2 and otherwise the
 * decoded picture buffer size of the level and picture size; HEVC {@code sps_max_num_reorder_pics}, with
 * {@code sps_max_latency_increase_plus1}. Pictures the decoding process does not output (HEVC pictures with
 * {@code pic_output_flag} zero, the skipped leading pictures of a stream that starts at a CRA picture, and the
 * pictures still waiting for output when an IDR or IRAP picture with {@code no_output_of_prior_pics_flag} set, or an
 * HEVC CRA picture after an end of sequence, empties the decoded picture buffer) have no presentation time; which
 * pictures still wait follows the decoder's output process, with its reorder, latency and buffer size bounds and the
 * reference pictures the HEVC reference picture sets and the H.264 reference marking keep.
 *
 * <p>The demuxer is seekable: the first {@link #seek(long)} reads the whole stream once to index its key frames,
 * and every seek then resumes from the IDR or IRAP picture that starts the run of pictures holding the target.
 * The source is read sequentially between seeks and is not closed. Instances are not thread-safe.
 */
public final class AnnexBDemuxer implements Demuxer {
    /**
     * The number of bytes read from the source at once.
     */
    private static final int CHUNK = 64 * 1024;

    /**
     * The largest NAL unit or access unit accepted, in bytes.
     */
    private static final int CAPACITY = 64 << 20;

    /**
     * The most access units read ahead for the track configuration once the parameter sets are known, when no key
     * frame comes first.
     */
    private static final int MAX_CONFIGURE_UNITS = 256;

    /**
     * The most bytes of access units read ahead for the track configuration once the parameter sets are known, when no
     * key frame comes first.
     */
    private static final long MAX_CONFIGURE_BYTES = 8L << 20;

    /**
     * The largest number of distinct parameter sets of one type kept for the track configuration.
     */
    private static final int MAX_PARAMETER_SETS = 16;

    /**
     * The largest reorder depth of H.264 and HEVC, in frames.
     */
    private static final int MAX_REORDER = 16;

    /**
     * The largest decoded picture buffer of H.264 and HEVC, in frames.
     */
    private static final int MAX_DPB_FRAMES = 16;

    /**
     * The NAL unit type of an H.264 sequence parameter set.
     */
    private static final int AVC_SPS = 7;

    /**
     * The NAL unit type of an H.264 picture parameter set.
     */
    private static final int AVC_PPS = 8;

    /**
     * The NAL unit type of an HEVC video parameter set.
     */
    private static final int HEVC_VPS = 32;

    /**
     * The NAL unit type of an HEVC sequence parameter set.
     */
    private static final int HEVC_SPS = 33;

    /**
     * The NAL unit type of an HEVC picture parameter set.
     */
    private static final int HEVC_PPS = 34;

    private final MediaSource source;

    private final MediaLimits limits;

    private final boolean hevc;

    private final VideoTrack track;

    /**
     * The frame rate the ticks derive from.
     */
    private final Rational frameRate;

    /**
     * Whether the options set the frame rate, so that every picture is timed at it.
     */
    private final boolean fixedRate;

    /**
     * The splitter that reads the packets.
     */
    private Splitter splitter;

    /**
     * The number of pictures the presentation ordering looks ahead, from the first sequence parameter set, or
     * {@code -1} until the first splitter starts.
     */
    private int lookahead = -1;

    /**
     * The number of pictures that may wait for output, from the first sequence parameter set.
     */
    private int reorder;

    /**
     * The latency at which a waiting picture is output, from the first HEVC sequence parameter set, or 0.
     */
    private int maxLatency;

    /**
     * The parameter sets of the track configuration, which the configuration changes are reported against.
     */
    private List<byte[]> initialSets = List.of();

    /**
     * The key frames and resumption points of the whole stream, built on the first seek, or {@code null}.
     */
    private Index index;

    /**
     * Constructs a demuxer with {@link ElementaryStreamOptions#defaults()}.
     *
     * @param source the stream, read from offset zero and not closed by this demuxer
     * @throws MediaException.Malformed if the content is not an H.264 or HEVC Annex B stream with parameter sets
     * @throws MediaException.LimitExceeded if a NAL unit or the picture size exceeds the limits
     * @throws MediaException.Io if reading fails
     */
    public AnnexBDemuxer(MediaSource source) {
        this(source, ElementaryStreamOptions.defaults());
    }

    /**
     * Constructs a demuxer with options.
     *
     * <p>The codec is recognized from the stream: HEVC when its first NAL units have HEVC headers and an HEVC
     * parameter set, H.264 otherwise.
     *
     * @param source the stream, read from offset zero and not closed by this demuxer
     * @param options the limits and frame rate
     * @throws MediaException.Malformed if the content is not an H.264 or HEVC Annex B stream with parameter sets
     * @throws MediaException.LimitExceeded if a NAL unit or the picture size exceeds the limits
     * @throws MediaException.Io if reading fails
     */
    public AnnexBDemuxer(MediaSource source, ElementaryStreamOptions options) {
        this.source = Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        this.limits = options.limits();
        var head = new byte[(int) Math.min(CHUNK, source.size() < 0 ? CHUNK : source.size())];
        var read = 0;
        while (read < head.length) {
            var count = source.read(read, head, read, head.length - read);
            if (count <= 0) {
                break;
            }
            read += count;
        }
        var probe = Arrays.copyOf(head, read);
        var kind = ElementaryProbe.annexB(probe);
        if (kind == null) {
            throw new MediaException.Malformed("not an H.264 or HEVC Annex B stream");
        }
        this.hevc = kind == Codec.HEVC;
        var first = new Splitter(this, 0, null, null);
        var configured = first.configure();
        if (configured == null) {
            throw new MediaException.Malformed("the stream holds no sequence and picture parameter sets");
        }
        this.fixedRate = options.frameRate().isPresent();
        var streamRate = first.streamRate();
        this.frameRate = options.frameRate().orElse(streamRate != null ? streamRate
                : ElementaryStreamOptions.DEFAULT_FRAME_RATE);
        var builder = VideoTrack.builder()
                .id(1)
                .codec(hevc ? Codec.HEVC : Codec.H264)
                .timeBase(new Rational(frameRate.denominator(), 2 * frameRate.numerator()).reduce())
                .frameRate(frameRate)
                .codecConfig(configured.config());
        if (configured.width() > 0 && configured.height() > 0) {
            LimitChecks.checkPicture(limits, configured.width(), configured.height());
            builder.width(configured.width()).height(configured.height());
        }
        if (configured.color() != null) {
            builder.color(configured.color());
        }
        if (configured.sampleAspect() != null) {
            builder.sampleAspectRatio(configured.sampleAspect());
        }
        this.track = builder.build();
        this.splitter = first;
        first.start();
    }

    /**
     * Recognizes an H.264 or HEVC Annex B byte stream from its leading bytes.
     *
     * <p>The bytes must start with a start code and hold NAL units with valid headers, among them a sequence
     * parameter set that parses, so other content that happens to start with a start code is not taken for a
     * stream; the first 64 KiB of a stream are enough.
     *
     * @param header the leading bytes of the input
     * @return {@link ContainerFormat#H264} or {@link ContainerFormat#HEVC}, or empty when the bytes start neither
     */
    public static Optional<ContainerFormat> detect(byte[] header) {
        var codec = ElementaryProbe.annexB(Objects.requireNonNull(header, "header"));
        return codec == null ? Optional.empty()
                : Optional.of(codec == Codec.HEVC ? ContainerFormat.HEVC : ContainerFormat.H264);
    }

    /**
     * {@return {@link ContainerFormat#HEVC} or {@link ContainerFormat#H264}}
     */
    @Override
    public ContainerFormat format() {
        return hevc ? ContainerFormat.HEVC : ContainerFormat.H264;
    }

    @Override
    public List<Track> tracks() {
        return List.of(track);
    }

    @Override
    public Map<String, String> metadata() {
        return Map.of();
    }

    /**
     * {@inheritDoc}
     *
     * <p>The duration is known once a seek has indexed the stream.
     */
    @Override
    public long durationMicros() {
        return index == null ? -1 : track.timeBase().toMicros(index.end);
    }

    /**
     * @throws MediaException.Malformed {@inheritDoc}
     * @throws MediaException.Io {@inheritDoc}
     */
    @Override
    public Packet read() {
        return splitter.next();
    }

    @Override
    public boolean isSeekable() {
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The first seek reads the whole stream once.
     *
     * @throws MediaException.Malformed {@inheritDoc}
     * @throws MediaException.Io {@inheritDoc}
     */
    @Override
    public void seek(long micros) {
        if (index == null) {
            index = buildIndex();
        }
        var target = SeekTimes.floor(micros, track.timeBase());
        if (index.keys.isEmpty() || target >= index.end) {
            var resumed = new Splitter(this, index.endOffset, null, null);
            resumed.exhaust();
            splitter = resumed;
            return;
        }
        Key chosen = null;
        for (var key : index.keys) {
            if (key.pts != Packet.NO_TIMESTAMP && key.pts <= target && (chosen == null || key.pts > chosen.pts)) {
                chosen = key;
            }
        }
        if (chosen == null) {
            chosen = index.keys.getFirst();
        }
        Snapshot resume = null;
        for (var snapshot : index.snapshots) {
            if (snapshot.offset <= chosen.offset) {
                resume = snapshot;
            }
        }
        var resumed = resume == null ? new Splitter(this, 0, null, null)
                : new Splitter(this, resume.offset, resume, null);
        if (resume == null) {
            resumed.established = new ArrayList<>(initialSets);
        }
        resumed.skipBefore = chosen.offset;
        resumed.start();
        splitter = resumed;
    }

    /**
     * Reads the whole stream once and records its key frames and resumption points.
     *
     * @return the index
     */
    private Index buildIndex() {
        var scan = new Splitter(this, 0, null, new Index());
        scan.established = new ArrayList<>(initialSets);
        scan.start();
        while (scan.next() != null) {
            // the scan returns placeholders while it records the index
        }
        scan.index.firstDuration = Math.max(0, scan.order.firstDuration());
        return scan.index;
    }

    /**
     * A key frame of the stream.
     *
     * @param offset the offset of the first start code of its access unit
     * @param pts its presentation time
     */
    private record Key(long offset, long pts) {
    }

    /**
     * The state of the splitter at an access unit that starts a new run of picture order counts, from which reading
     * can resume.
     *
     * @param offset the offset of the first start code of the access unit
     * @param outputs the number of output pictures decoded before the access unit, discarded ones included
     * @param presentClock the presentation time at which its run starts
     * @param elapsed the exact sum of the picture durations before the access unit, in ticks
     * @param period the run number
     * @param avcSequences the H.264 sequence parameter sets met
     * @param avcPictures the H.264 picture parameter sets met
     * @param hevcSequences the HEVC sequence parameter sets met
     * @param hevcPictures the HEVC picture parameter sets met
     * @param video the latest HEVC video parameter set, or {@code null}
     * @param active the latest parameter sets by type and identifier
     * @param established the parameter sets of the configuration last reported
     */
    private record Snapshot(long offset, long outputs, long presentClock, double elapsed, long period,
                            Map<Integer, AvcSyntax.Sps> avcSequences, Map<Integer, AvcSyntax.Pps> avcPictures,
                            Map<Integer, HevcSyntax.Sps> hevcSequences, Map<Integer, HevcSyntax.Pps> hevcPictures,
                            HevcSyntax.Vps video, TreeMap<Integer, byte[]> active, List<byte[]> established) {
    }

    /**
     * The state of the splitter at an access unit that starts a new run, before the ordering has placed it.
     *
     * @param offset the offset of the first start code of the access unit
     * @param elapsed the exact sum of the picture durations before the access unit, in ticks
     * @param period the run number
     * @param avcSequences the H.264 sequence parameter sets met
     * @param avcPictures the H.264 picture parameter sets met
     * @param hevcSequences the HEVC sequence parameter sets met
     * @param hevcPictures the HEVC picture parameter sets met
     * @param video the latest HEVC video parameter set, or {@code null}
     * @param active the latest parameter sets by type and identifier
     * @param established the parameter sets of the configuration last reported
     */
    private record PendingSnapshot(long offset, double elapsed, long period,
                                   Map<Integer, AvcSyntax.Sps> avcSequences, Map<Integer, AvcSyntax.Pps> avcPictures,
                                   Map<Integer, HevcSyntax.Sps> hevcSequences,
                                   Map<Integer, HevcSyntax.Pps> hevcPictures, HevcSyntax.Vps video,
                                   TreeMap<Integer, byte[]> active, List<byte[]> established) {
        /**
         * Completes the snapshot once the access unit was added to the ordering.
         *
         * @param outputs the number of output pictures decoded before the access unit
         * @param presentClock the presentation time at which its run starts
         * @return the snapshot
         */
        Snapshot complete(long outputs, long presentClock) {
            return new Snapshot(offset, outputs, presentClock, elapsed, period, avcSequences, avcPictures,
                    hevcSequences, hevcPictures, video, active, established);
        }
    }

    /**
     * The key frames and resumption points of a stream.
     */
    private static final class Index {
        /**
         * The key frames, in decoding order.
         */
        final List<Key> keys = new ArrayList<>();

        /**
         * The resumption points, in decoding order.
         */
        final List<Snapshot> snapshots = new ArrayList<>();

        /**
         * The presentation time after the last picture.
         */
        long end;

        /**
         * The size of the stream.
         */
        long endOffset;

        /**
         * The presentation times of the stream, by place in presentation order.
         */
        final PresentationOrder.Times times = new PresentationOrder.Times(0);

        /**
         * The duration of the first picture in presentation order, or {@code -1} until known.
         */
        int firstDuration = -1;
    }

    /**
     * The configuration the stream's leading parameter sets give.
     *
     * @param config the {@code avcC} or {@code hvcC} record
     * @param width the picture width, or {@code 0}
     * @param height the picture height, or {@code 0}
     * @param color the color description, or {@code null}
     * @param sampleAspect the sample aspect ratio, or {@code null}
     */
    private record Configured(byte[] config, int width, int height, com.github.auties00.smalt.image.ColorInfo color,
                              Rational sampleAspect) {
    }

    /**
     * Collects the NAL units of one access unit.
     */
    private static final class AccessUnit {
        byte[] data = new byte[1024];

        int size;

        /**
         * The offset of the start code of the first NAL unit.
         */
        long offset;

        /**
         * Whether a coded slice was added.
         */
        boolean coded;

        /**
         * Whether an IDR picture (H.264) or IRAP picture (HEVC) was added.
         */
        boolean idr;

        /**
         * Whether a recovery point SEI message was added.
         */
        boolean recovery;

        /**
         * The payload of the first picture timing SEI message, without emulation prevention bytes, or {@code null}.
         */
        byte[] picTiming;

        /**
         * Whether the picture empties the decoded picture buffer without outputting the pictures waiting in it.
         */
        boolean discardPrior;

        /**
         * The resumption point the unit opens, when building the index, or {@code null}.
         */
        PendingSnapshot snapshot;

        /**
         * Whether every slice added is intra coded.
         */
        boolean intra = true;

        /**
         * The HEVC NAL unit type of the first base-layer slice, or {@code -1}.
         */
        int hevcType = -1;

        /**
         * The temporal identifier of the first base-layer slice.
         */
        int temporalId;

        /**
         * Whether an end of sequence NAL unit was added.
         */
        boolean endOfSequence;

        /**
         * The first H.264 slice header, or {@code null}.
         */
        AvcSyntax.Slice avc;

        /**
         * The first slice header of the second field of an H.264 field pair joined into this unit, or {@code null}.
         */
        AvcSyntax.Slice secondField;

        /**
         * The last H.264 slice header of the primary coded picture, or {@code null}.
         */
        AvcSyntax.Slice lastSlice;

        /**
         * The first HEVC slice segment header of the base layer, or {@code null}.
         */
        HevcSyntax.Slice hevcSlice;

        /**
         * The configuration record the unit establishes, or {@code null}.
         */
        byte[] newConfig;

        /**
         * Whether the unit is a key frame.
         */
        boolean key;

        /**
         * The picture order count used for output order.
         */
        long poc;

        /**
         * The run of picture order counts the unit belongs to.
         */
        long period;

        /**
         * Whether the picture is output.
         */
        boolean output = true;

        /**
         * The duration in ticks.
         */
        int duration = 2;

        /**
         * Whether the unit holds an H.264 field picture alone.
         */
        boolean field;

        /**
         * Whether the field is a bottom field.
         */
        boolean bottom;
    }

    /**
     * An H.264 reference frame of the marking model.
     */
    private static final class AvcReference {
        /**
         * The frame's order count.
         */
        final long poc;

        /**
         * The frame's {@code frame_num}.
         */
        final int frameNum;

        /**
         * Whether the frame is a long-term reference.
         */
        boolean longTerm;

        /**
         * {@code LongTermFrameIdx} of a long-term frame.
         */
        int longTermIndex;

        /**
         * Constructs a reference frame.
         *
         * @param poc the order count
         * @param frameNum the {@code frame_num}
         * @param longTerm whether the frame is a long-term reference
         * @param longTermIndex its long-term frame index
         */
        AvcReference(long poc, int frameNum, boolean longTerm, int longTermIndex) {
            this.poc = poc;
            this.frameNum = frameNum;
            this.longTerm = longTerm;
            this.longTermIndex = longTermIndex;
        }
    }

    /**
     * A small set of order counts.
     */
    private static final class ReferenceSet {
        private long[] values = new long[16];

        private int size;

        /**
         * Adds an order count.
         *
         * @param poc the order count
         */
        void add(long poc) {
            if (size == values.length) {
                values = Arrays.copyOf(values, 2 * size);
            }
            values[size++] = poc;
        }

        /**
         * Returns whether the set holds an order count.
         *
         * @param poc the order count
         * @return {@code true} when it is in the set
         */
        boolean contains(long poc) {
            for (var i = 0; i < size; i++) {
                if (values[i] == poc) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Splits the byte stream into access units and times them, from a position of the stream.
     */
    private static final class Splitter {
        private final AnnexBDemuxer owner;

        private final boolean hevc;

        /**
         * The index being built, or {@code null} when packets are returned.
         */
        private final Index index;

        private byte[] buffer = new byte[2 * CHUNK];

        /**
         * The number of bytes in {@link #buffer}.
         */
        private int end;

        /**
         * The stream offset of {@code buffer[0]}.
         */
        private long base;

        /**
         * Whether the source is exhausted.
         */
        private boolean exhausted;

        /**
         * The index of the start code of the NAL unit being read, or {@code -1}.
         */
        private int unitCode = -1;

        /**
         * The index from which the next start code search begins.
         */
        private int scan;

        private final Map<Integer, AvcSyntax.Sps> avcSequences;

        private final Map<Integer, AvcSyntax.Pps> avcPictures;

        private final Map<Integer, HevcSyntax.Sps> hevcSequences;

        private final Map<Integer, HevcSyntax.Pps> hevcPictures;

        /**
         * The latest parameter set of each type and identifier, keyed by {@code type * 256 + id}.
         */
        private TreeMap<Integer, byte[]> active;

        /**
         * The parameter sets of the configuration last reported, or {@code null} before the track is configured.
         */
        private List<byte[]> established;

        /**
         * Whether a parameter set became active since the last comparison with {@link #established}.
         */
        private boolean activated;

        private final List<byte[]> videoSets = new ArrayList<>();

        private final List<byte[]> sequenceSets = new ArrayList<>();

        private final List<byte[]> pictureSets = new ArrayList<>();

        private AccessUnit current;

        /**
         * An H.264 field waiting for the field that completes its frame, or {@code null}.
         */
        private AccessUnit pendingField;

        private PresentationOrder order;

        /**
         * The exact sum of the durations of the pictures timed, in ticks.
         */
        private double elapsed;

        /**
         * The latest HEVC video parameter set, or {@code null}.
         */
        private HevcSyntax.Vps video;

        /**
         * The H.264 reference frames of the current period, in decoding order.
         */
        private final List<AvcReference> avcReferences = new ArrayList<>();

        /**
         * {@code MaxLongTermFrameIdx}, or {@code -1} for no long-term frame indices.
         */
        private int maxLongTermIndex = -1;

        /**
         * The period the H.264 reference frames belong to.
         */
        private long markedPeriod = Long.MIN_VALUE;

        /**
         * Packets of access units starting before this offset are not returned.
         */
        long skipBefore;

        private long period;

        /**
         * Whether a picture was timed, after which a CRA no longer starts a run by itself.
         */
        private boolean seenPicture;

        /**
         * Whether the last IRAP picture skips its random access skipped leading pictures.
         */
        private boolean skipLeading;

        /**
         * Whether the previous HEVC picture ended its coded video sequence.
         */
        private boolean afterEndOfSequence;

        private long prevRefMsb;

        private long prevRefLsb;

        private boolean prevRefMmco5;

        private boolean prevRefBottomField;

        private long prevRefTop;

        private long prevFrameNumOffset;

        private int prevFrameNum;

        private boolean prevMmco5;

        private long prevTid0Msb;

        private long prevTid0Lsb;

        /**
         * The snapshot the splitter resumed from, or {@code null}.
         */
        private final Snapshot resumed;

        /**
         * Constructs a splitter.
         *
         * @param owner the demuxer
         * @param offset the stream offset to read from
         * @param resume the state to resume from, or {@code null} to start fresh
         * @param index the index to build, or {@code null} to return packets
         */
        Splitter(AnnexBDemuxer owner, long offset, Snapshot resume, Index index) {
            this.owner = owner;
            this.hevc = owner.hevc;
            this.index = index;
            this.base = offset;
            this.resumed = resume;
            if (resume != null) {
                avcSequences = new HashMap<>(resume.avcSequences);
                avcPictures = new HashMap<>(resume.avcPictures);
                hevcSequences = new HashMap<>(resume.hevcSequences);
                hevcPictures = new HashMap<>(resume.hevcPictures);
                active = new TreeMap<>(resume.active);
                established = new ArrayList<>(resume.established);
                period = resume.period - 1;
                elapsed = resume.elapsed;
                video = resume.video;
                seenPicture = true;
            } else {
                avcSequences = new HashMap<>();
                avcPictures = new HashMap<>();
                hevcSequences = new HashMap<>();
                hevcPictures = new HashMap<>();
                active = new TreeMap<>();
            }
        }

        /**
         * Reads the stream up to its first key frame and returns the track configuration it gives, leaving the access
         * units read for {@link #next()}.
         *
         * <p>Once the parameter sets are known, reading stops after {@value AnnexBDemuxer#MAX_CONFIGURE_UNITS} access
         * units or {@value AnnexBDemuxer#MAX_CONFIGURE_BYTES} bytes even when no key frame came, so that a stream
         * without key frames is not buffered whole.
         *
         * @return the configuration, or {@code null} when the stream has no parameter sets
         */
        Configured configure() {
            pending = new ArrayList<>();
            var buffered = 0L;
            while (true) {
                var unit = nextAccessUnit();
                if (unit == null) {
                    break;
                }
                pending.add(unit);
                buffered += unit.size;
                var configured = !sequenceSets.isEmpty() && !pictureSets.isEmpty();
                if (configured && (unit.key || pending.size() >= MAX_CONFIGURE_UNITS
                        || buffered >= MAX_CONFIGURE_BYTES)) {
                    break;
                }
                LimitChecks.checkAllocation(owner.limits, buffered);
            }
            if (sequenceSets.isEmpty() || pictureSets.isEmpty()) {
                return null;
            }
            var config = hevc ? NalConfigs.hevcConfig(videoSets, sequenceSets, pictureSets)
                    : NalConfigs.avcConfig(sequenceSets, pictureSets);
            if (config == null) {
                return null;
            }
            established = new ArrayList<>(active.values());
            owner.initialSets = List.copyOf(established);
            VideoConfigs.Info info;
            try {
                var first = sequenceSets.getFirst();
                info = hevc ? VideoConfigs.hevcSps(first) : VideoConfigs.avcSps(first);
            } catch (RuntimeException exception) {
                info = null;
            }
            if (info == null) {
                return new Configured(config, 0, 0, null, null);
            }
            return new Configured(config, info.width(), info.height(), info.color(), info.sampleAspect());
        }

        /**
         * Returns the frame rate of the timing information of the first sequence parameter set.
         *
         * @return the rate, or {@code null} when the stream signals none
         */
        Rational streamRate() {
            if (sequenceSets.isEmpty()) {
                return null;
            }
            var first = sequenceSets.getFirst();
            long[] period;
            if (hevc) {
                period = fieldPeriod(HevcSyntax.sps(first, 0, first.length));
            } else {
                var sps = AvcSyntax.sps(first, 0, first.length);
                period = sps == null ? null : fieldPeriod(sps);
            }
            if (period == null) {
                return null;
            }
            var numerator = period[1];
            var denominator = 2 * period[0];
            var divisor = gcd(numerator, denominator);
            return new Rational(numerator / divisor, denominator / divisor);
        }

        /**
         * Returns the field period of an H.264 sequence parameter set's timing information.
         *
         * @param sps the set
         * @return the period as {@code {units, scale}} seconds, or {@code null} without timing information
         */
        private static long[] fieldPeriod(AvcSyntax.Sps sps) {
            return sps.timingUnits() > 0 ? new long[]{sps.timingUnits(), sps.timingScale()} : null;
        }

        /**
         * Returns the field period of an HEVC sequence parameter set's timing information, or of the latest video
         * parameter set's when the sequence parameter set has none: the tick is a picture, which is a field when
         * {@code field_seq_flag} is set and a frame otherwise.
         *
         * @param sps the set, or {@code null}
         * @return the period as {@code {units, scale}} seconds, or {@code null} without timing information
         */
        private long[] fieldPeriod(HevcSyntax.Sps sps) {
            if (sps == null) {
                return null;
            }
            long units = sps.timingUnits();
            long scale = sps.timingScale();
            if (units <= 0) {
                var vps = video;
                if (vps == null && !videoSets.isEmpty()) {
                    var first = videoSets.getFirst();
                    vps = HevcSyntax.vps(first, 0, first.length);
                }
                if (vps == null || vps.timingUnits() <= 0) {
                    return null;
                }
                units = vps.timingUnits();
                scale = vps.timingScale();
            }
            return sps.fieldSeq() ? new long[]{units, scale} : new long[]{units, 2 * scale};
        }

        /**
         * Returns the greatest common divisor of two positive numbers.
         *
         * @param a a number
         * @param b a number
         * @return the divisor
         */
        private static long gcd(long a, long b) {
            while (b != 0) {
                var t = a % b;
                a = b;
                b = t;
            }
            return a;
        }

        /**
         * Returns the number of fields a picture is displayed for (ITU-T H.264 Table E-6, H.265 Table D.2).
         *
         * @param unit the access unit
         * @return 1 for a field, 2 for a frame, 3, 4 or 6 for a frame its {@code pic_struct} repeats
         */
        private int fields(AccessUnit unit) {
            int picStruct;
            if (hevc) {
                var slice = unit.hevcSlice;
                if (slice == null) {
                    return 2;
                }
                if (slice.sps().fieldSeq()) {
                    return 1;
                }
                picStruct = unit.picTiming == null ? -1 : HevcSyntax.picStruct(unit.picTiming, slice.sps());
            } else {
                var slice = unit.avc;
                if (slice == null) {
                    return 2;
                }
                if (slice.field()) {
                    return 1;
                }
                picStruct = unit.picTiming == null ? -1 : AvcSyntax.picStruct(unit.picTiming, slice.sps());
            }
            return switch (picStruct) {
                case 5, 6 -> 3;
                case 7 -> 4;
                case 8 -> 6;
                default -> 2;
            };
        }

        /**
         * Returns the duration of a picture in ticks: its fields at the field period of its sequence parameter set,
         * converted to the track's ticks with the rounding error carried to the next picture.
         *
         * @param unit the access unit
         * @return the duration, at least 1
         */
        private int ticks(AccessUnit unit) {
            var fields = fields(unit);
            var ratio = 1.0;
            if (!owner.fixedRate) {
                long[] period = null;
                if (hevc && unit.hevcSlice != null) {
                    period = fieldPeriod(unit.hevcSlice.sps());
                } else if (!hevc && unit.avc != null) {
                    period = fieldPeriod(unit.avc.sps());
                }
                if (period != null) {
                    var rate = owner.frameRate;
                    ratio = (double) period[0] * (2.0 * rate.numerator()) / ((double) period[1] * rate.denominator());
                }
            }
            var before = Math.round(elapsed);
            elapsed += fields * ratio;
            var after = Math.round(elapsed);
            if (after <= before) {
                after = before + 1;
                elapsed = after;
            }
            return (int) Math.min(Integer.MAX_VALUE, after - before);
        }

        /**
         * The access units read by {@link #configure()} and not timed yet, or {@code null}.
         */
        private List<AccessUnit> pending;

        /**
         * Creates the presentation ordering once the reorder depth is known and times the access units read so far.
         */
        void start() {
            if (owner.lookahead < 0) {
                var depth = MAX_REORDER;
                var latency = 0;
                var interlaced = false;
                if (hevc) {
                    var sps = firstSequence(hevcSequences);
                    if (sps != null) {
                        depth = sps.reorderPictures();
                        latency = sps.latencyIncreasePlus1() == 0 ? 0 : depth + sps.latencyIncreasePlus1() - 1;
                    }
                } else {
                    var sps = firstSequence(avcSequences);
                    if (sps != null) {
                        depth = sps.reorderFrames();
                        interlaced = !sps.frameMbsOnly();
                    }
                }
                // an interlaced stream may code each frame as two fields, each a picture of the ordering
                owner.lookahead = interlaced ? 2 * depth + 1 : depth;
                owner.reorder = depth;
                owner.maxLatency = latency;
            }
            if (resumed != null) {
                order = new PresentationOrder(owner.lookahead, owner.reorder, owner.maxLatency, resumed.outputs,
                        resumed.presentClock, owner.index.times, owner.index.firstDuration);
            } else {
                order = new PresentationOrder(owner.lookahead, owner.reorder, owner.maxLatency,
                        index == null ? null : index.times);
            }
            if (pending != null) {
                for (var unit : pending) {
                    time(unit);
                }
                pending = null;
            }
        }

        /**
         * Marks the source as read entirely, so that {@link #next()} returns {@code null}.
         */
        void exhaust() {
            exhausted = true;
            order = new PresentationOrder(0, 0, 0, null);
        }

        /**
         * Returns the sequence parameter set of the first sequence parameter set NAL unit of the stream.
         *
         * @param sequences the parsed sets by identifier
         * @param <T> the set type
         * @return the set, or {@code null}
         */
        private <T> T firstSequence(Map<Integer, T> sequences) {
            if (sequenceSets.isEmpty()) {
                return sequences.values().stream().findFirst().orElse(null);
            }
            var first = sequenceSets.getFirst();
            var id = hevc ? hevcSpsId(first) : avcSpsId(first);
            var sps = sequences.get(id);
            return sps != null ? sps : sequences.values().stream().findFirst().orElse(null);
        }

        /**
         * {@return the identifier of an H.264 sequence parameter set NAL unit, or {@code -1}}
         *
         * @param unit the NAL unit
         */
        private static int avcSpsId(byte[] unit) {
            var sps = AvcSyntax.sps(unit, 0, unit.length);
            return sps == null ? -1 : sps.id();
        }

        /**
         * {@return the identifier of an HEVC sequence parameter set NAL unit, or {@code -1}}
         *
         * @param unit the NAL unit
         */
        private static int hevcSpsId(byte[] unit) {
            var sps = HevcSyntax.sps(unit, 0, unit.length);
            return sps == null ? -1 : sps.id();
        }

        /**
         * Returns the next packet.
         *
         * @return the packet, or {@code null} at the end of the stream
         */
        Packet next() {
            while (true) {
                var entry = order.poll(finished());
                if (entry == null) {
                    if (finished()) {
                        return null;
                    }
                    var unit = nextAccessUnit();
                    if (unit != null) {
                        time(unit);
                    } else {
                        flushPending();
                    }
                    continue;
                }
                var unit = (AccessUnit) entry.payload;
                var dts = order.dts(entry);
                if (index != null) {
                    if (unit.key) {
                        index.keys.add(new Key(unit.offset, entry.pts()));
                    }
                    if (entry.pts() != Packet.NO_TIMESTAMP) {
                        index.end = Math.max(index.end, entry.pts() + unit.duration);
                    }
                    index.endOffset = base + end;
                    return PLACEHOLDER;
                }
                if (unit.offset < skipBefore) {
                    continue;
                }
                var flags = unit.key ? EnumSet.of(PacketFlag.KEY) : EnumSet.noneOf(PacketFlag.class);
                var side = unit.newConfig == null ? PacketSideData.NONE
                        : PacketSideData.builder().newCodecConfig(unit.newConfig).build();
                return new Packet(1, Arrays.copyOf(unit.data, unit.size), entry.pts(), dts,
                        entry.output() ? unit.duration : 0, flags, side);
            }
        }

        /**
         * Returns whether every access unit was read and timed.
         *
         * @return {@code true} at the end of the input
         */
        private boolean finished() {
            return exhausted && unitCode < 0 && current == null && pendingField == null && pending == null;
        }

        /**
         * Times the field waiting for its pair, when the input ended.
         */
        private void flushPending() {
            if (pendingField != null) {
                var field = pendingField;
                pendingField = null;
                add(field);
            }
        }

        /**
         * Pairs an H.264 field with the next one, then times the access unit.
         *
         * @param unit the completed access unit
         */
        private void time(AccessUnit unit) {
            if (hevc) {
                hevcPoc(unit);
                unit.duration = ticks(unit);
                add(unit);
                return;
            }
            var first = pendingField;
            if (first != null) {
                pendingField = null;
                if (unit.field && unit.bottom != first.bottom && unit.avc != null && first.avc != null
                        && unit.avc.frameNum() == first.avc.frameNum() && !(unit.idr && !first.idr)) {
                    avcPoc(unit, true);
                    first.data = Arrays.copyOf(first.data, first.size + unit.size);
                    System.arraycopy(unit.data, 0, first.data, first.size, unit.size);
                    first.size += unit.size;
                    first.poc = Math.min(first.poc, unit.poc);
                    first.duration += ticks(unit);
                    first.secondField = unit.avc;
                    first.field = false;
                    first.key |= unit.key;
                    add(first);
                    return;
                }
                add(first);
            }
            if (unit.avc != null && (unit.idr || unit.avc.mmco5())) {
                snapshot(unit);
            }
            unit.discardPrior = unit.avc != null && unit.avc.idr() && unit.avc.noOutputOfPriorPics();
            avcPoc(unit, false);
            unit.duration = ticks(unit);
            if (unit.field) {
                pendingField = unit;
                return;
            }
            add(unit);
        }

        /**
         * Adds an access unit to the presentation ordering.
         *
         * @param unit the access unit
         */
        private void add(AccessUnit unit) {
            seenPicture = true;
            PresentationOrder.Entry entry;
            if (hevc && unit.hevcSlice != null) {
                var slice = unit.hevcSlice;
                var sps = slice.sps();
                var latency = sps.latencyIncreasePlus1() == 0 ? 0
                        : sps.reorderPictures() + sps.latencyIncreasePlus1() - 1;
                var poc = unit.poc;
                LongPredicate references = slice.shortTermDeltas() == null ? null
                        : other -> slice.references(other, poc);
                entry = new PresentationOrder.Entry(unit.poc, unit.period, unit.output, unit.duration,
                        unit.discardPrior, sps.reorderPictures(), latency, sps.maxDecPicBuffering(), references, true,
                        false, unit);
            } else if (!hevc && unit.avc != null) {
                var sps = unit.avc.sps();
                var references = mark(unit);
                entry = new PresentationOrder.Entry(unit.poc, unit.period, unit.output, unit.duration,
                        unit.discardPrior, sps.reorderFrames(), 0, sps.dpbFrames(), references::contains,
                        unit.avc.reference(), true, unit);
            } else {
                entry = new PresentationOrder.Entry(unit.poc, unit.period, unit.output, unit.duration,
                        unit.discardPrior, unit);
            }
            order.add(entry);
            if (unit.snapshot != null) {
                index.snapshots.add(unit.snapshot.complete(entry.outputsBefore, entry.presentBefore));
                unit.snapshot = null;
            }
        }

        /**
         * Applies the decoded reference picture marking of an H.264 picture (ITU-T H.264 clause 8.2.5) to the
         * reference frames of the period and returns the order counts of the frames that stay references.
         *
         * <p>Frames and field pairs are marked as frames: the picture numbers of a field picture's operations name
         * the frame that holds the field.
         *
         * @param unit the access unit, whose picture is added after the frames returned
         * @return the order counts of the earlier reference frames still used for reference
         */
        private ReferenceSet mark(AccessUnit unit) {
            // TODO: mark field pictures one field at a time (a frame stays a reference while one of its fields is)
            // and insert the frames a gap in frame_num implies (ITU-T H.264 clause 8.2.5.2)
            var slice = unit.avc;
            if (unit.period != markedPeriod) {
                avcReferences.clear();
                maxLongTermIndex = -1;
                markedPeriod = unit.period;
            }
            var result = new ReferenceSet();
            if (slice.idr()) {
                avcReferences.clear();
                maxLongTermIndex = slice.longTermReference() ? 0 : -1;
                if (slice.reference()) {
                    avcReferences.add(new AvcReference(unit.poc, slice.frameNum(), slice.longTermReference(), 0));
                }
                return result;
            }
            var maxFrameNum = 1 << slice.sps().log2MaxFrameNum();
            var current = slice.frameNum();
            var longTermCurrent = -1;
            if (slice.reference()) {
                var operations = new ArrayList<int[]>();
                if (slice.operations() != null) {
                    operations.add(slice.operations());
                }
                if (unit.secondField != null && unit.secondField.operations() != null) {
                    operations.add(unit.secondField.operations());
                }
                if (operations.isEmpty()) {
                    var shortTerm = 0;
                    AvcReference oldest = null;
                    for (var reference : avcReferences) {
                        if (!reference.longTerm) {
                            shortTerm++;
                            if (oldest == null || wrap(reference.frameNum, current, maxFrameNum)
                                    < wrap(oldest.frameNum, current, maxFrameNum)) {
                                oldest = reference;
                            }
                        }
                    }
                    if (avcReferences.size() >= Math.max(1, slice.sps().refFrames()) && shortTerm > 0) {
                        avcReferences.remove(oldest);
                    }
                }
                for (var list : operations) {
                    for (var i = 0; i < list.length; i += 3) {
                        longTermCurrent = operate(list[i], list[i + 1], list[i + 2], slice, current, maxFrameNum,
                                longTermCurrent);
                    }
                }
            }
            for (var reference : avcReferences) {
                result.add(reference.poc);
            }
            if (slice.reference()) {
                avcReferences.add(new AvcReference(unit.poc, current, longTermCurrent >= 0,
                        Math.max(0, longTermCurrent)));
                // a marking that releases no frame cannot keep more than a decoded picture buffer holds
                while (avcReferences.size() > MAX_DPB_FRAMES) {
                    avcReferences.removeFirst();
                }
            }
            return result;
        }

        /**
         * Applies one memory management control operation (ITU-T H.264 clause 8.2.5.4).
         *
         * @param operation {@code memory_management_control_operation}
         * @param first its first value
         * @param second its second value
         * @param slice the slice header
         * @param current the current {@code frame_num}
         * @param maxFrameNum {@code MaxFrameNum}
         * @param longTermCurrent the long-term frame index the current picture takes, or {@code -1}
         * @return the long-term frame index the current picture takes, or {@code -1}
         */
        private int operate(int operation, int first, int second, AvcSyntax.Slice slice, int current, int maxFrameNum,
                            int longTermCurrent) {
            var currentPicNum = slice.field() ? 2 * current + 1 : current;
            switch (operation) {
                case 1 -> removeShortTerm(currentPicNum - (first + 1), slice.field(), current, maxFrameNum);
                case 2 -> avcReferences.removeIf(reference -> reference.longTerm
                        && reference.longTermIndex == (slice.field() ? first >> 1 : first));
                case 3 -> {
                    avcReferences.removeIf(reference -> reference.longTerm && reference.longTermIndex == second);
                    var picNum = currentPicNum - (first + 1);
                    for (var reference : avcReferences) {
                        if (!reference.longTerm && frameNumber(picNum, slice.field())
                                == wrap(reference.frameNum, current, maxFrameNum)) {
                            reference.longTerm = true;
                            reference.longTermIndex = second;
                            break;
                        }
                    }
                }
                case 4 -> {
                    maxLongTermIndex = first - 1;
                    avcReferences.removeIf(reference -> reference.longTerm
                            && reference.longTermIndex > maxLongTermIndex);
                }
                case 5 -> {
                    avcReferences.clear();
                    maxLongTermIndex = -1;
                }
                case 6 -> {
                    avcReferences.removeIf(reference -> reference.longTerm && reference.longTermIndex == first);
                    return first;
                }
                default -> {
                }
            }
            return longTermCurrent;
        }

        /**
         * Removes the short-term reference frame a picture number names.
         *
         * @param picNum the picture number
         * @param field whether the current picture is a field
         * @param current the current {@code frame_num}
         * @param maxFrameNum {@code MaxFrameNum}
         */
        private void removeShortTerm(int picNum, boolean field, int current, int maxFrameNum) {
            var frame = frameNumber(picNum, field);
            avcReferences.removeIf(reference -> !reference.longTerm
                    && wrap(reference.frameNum, current, maxFrameNum) == frame);
        }

        /**
         * Returns the {@code FrameNumWrap} a picture number names: the number itself for a frame, the frame of the
         * field otherwise.
         *
         * @param picNum the picture number
         * @param field whether the current picture is a field
         * @return the frame number wrap
         */
        private static int frameNumber(int picNum, boolean field) {
            return field ? Math.floorDiv(picNum, 2) : picNum;
        }

        /**
         * Returns {@code FrameNumWrap} of a reference frame (ITU-T H.264 clause 8.2.4.1).
         *
         * @param frameNum the frame's {@code frame_num}
         * @param current the current {@code frame_num}
         * @param maxFrameNum {@code MaxFrameNum}
         * @return the wrapped number
         */
        private static int wrap(int frameNum, int current, int maxFrameNum) {
            return frameNum > current ? frameNum - maxFrameNum : frameNum;
        }

        /**
         * Records a resumption point at an access unit that starts a run, when building the index.
         *
         * @param unit the access unit
         */
        private void snapshot(AccessUnit unit) {
            if (index == null || (!hevc && !unit.idr) || order == null) {
                return;
            }
            unit.snapshot = new PendingSnapshot(unit.offset, elapsed, period + 1, Map.copyOf(avcSequences),
                    Map.copyOf(avcPictures), Map.copyOf(hevcSequences), Map.copyOf(hevcPictures), video,
                    new TreeMap<>(active), established == null ? List.of() : List.copyOf(established));
        }

        /**
         * Computes the picture order count of an H.264 picture (ITU-T H.264 clause 8.2.1) and its run.
         *
         * @param unit the access unit
         * @param secondField whether the unit is the second field of a frame, in the run of the first
         */
        private void avcPoc(AccessUnit unit, boolean secondField) {
            var slice = unit.avc;
            if (slice == null) {
                unit.period = period;
                unit.poc = Long.MAX_VALUE / 2;
                return;
            }
            var sps = slice.sps();
            if ((slice.idr() && !secondField) || slice.mmco5()) {
                period++;
            }
            unit.period = period;
            long top;
            long bottom;
            var maxFrameNum = 1L << sps.log2MaxFrameNum();
            long frameNumOffset;
            if (slice.idr()) {
                frameNumOffset = 0;
            } else {
                var previousOffset = prevMmco5 ? 0 : prevFrameNumOffset;
                var previousNum = prevMmco5 ? 0 : prevFrameNum;
                frameNumOffset = previousNum > slice.frameNum() ? previousOffset + maxFrameNum : previousOffset;
            }
            switch (sps.pocType()) {
                case 0 -> {
                    long previousMsb;
                    long previousLsb;
                    if (slice.idr()) {
                        previousMsb = 0;
                        previousLsb = 0;
                    } else if (prevRefMmco5) {
                        previousMsb = 0;
                        previousLsb = prevRefBottomField ? 0 : prevRefTop;
                    } else {
                        previousMsb = prevRefMsb;
                        previousLsb = prevRefLsb;
                    }
                    var maxLsb = 1L << sps.log2MaxPocLsb();
                    var lsb = slice.pocLsb();
                    long msb;
                    if (lsb < previousLsb && previousLsb - lsb >= maxLsb / 2) {
                        msb = previousMsb + maxLsb;
                    } else if (lsb > previousLsb && lsb - previousLsb > maxLsb / 2) {
                        msb = previousMsb - maxLsb;
                    } else {
                        msb = previousMsb;
                    }
                    top = msb + lsb;
                    bottom = slice.field() ? msb + lsb : top + slice.deltaPocBottom();
                    if (slice.reference()) {
                        prevRefMsb = msb;
                        prevRefLsb = lsb;
                    }
                }
                case 1 -> {
                    var cycle = sps.offsetForRefFrame();
                    var absolute = cycle.length != 0 ? frameNumOffset + slice.frameNum() : 0;
                    if (!slice.reference() && absolute > 0) {
                        absolute--;
                    }
                    long expected = 0;
                    if (absolute > 0) {
                        long delta = 0;
                        for (var value : cycle) {
                            delta += value;
                        }
                        var cycles = (absolute - 1) / cycle.length;
                        var inCycle = (int) ((absolute - 1) % cycle.length);
                        expected = cycles * delta;
                        for (var i = 0; i <= inCycle; i++) {
                            expected += cycle[i];
                        }
                    }
                    if (!slice.reference()) {
                        expected += sps.offsetForNonRefPic();
                    }
                    if (!slice.field()) {
                        top = expected + slice.deltaPoc0();
                        bottom = top + sps.offsetForTopToBottomField() + slice.deltaPoc1();
                    } else if (!slice.bottom()) {
                        top = expected + slice.deltaPoc0();
                        bottom = top;
                    } else {
                        bottom = expected + sps.offsetForTopToBottomField() + slice.deltaPoc0();
                        top = bottom;
                    }
                }
                default -> {
                    long value;
                    if (slice.idr()) {
                        value = 0;
                    } else if (!slice.reference()) {
                        value = 2 * (frameNumOffset + slice.frameNum()) - 1;
                    } else {
                        value = 2 * (frameNumOffset + slice.frameNum());
                    }
                    top = value;
                    bottom = value;
                }
            }
            long poc;
            if (!slice.field()) {
                poc = Math.min(top, bottom);
            } else {
                poc = slice.bottom() ? bottom : top;
            }
            if (slice.mmco5()) {
                var temp = slice.field() ? poc : Math.min(top, bottom);
                top -= temp;
                bottom -= temp;
                poc = 0;
            }
            if (slice.reference()) {
                prevRefMmco5 = slice.mmco5();
                prevRefBottomField = slice.field() && slice.bottom();
                prevRefTop = top;
            }
            prevFrameNumOffset = frameNumOffset;
            prevFrameNum = slice.frameNum();
            prevMmco5 = slice.mmco5();
            unit.poc = poc;
            unit.field = slice.field();
            unit.bottom = slice.bottom();
        }

        /**
         * Computes the picture order count of an HEVC picture (ITU-T H.265 clause 8.3.1), its run and whether it is
         * output.
         *
         * @param unit the access unit
         */
        private void hevcPoc(AccessUnit unit) {
            var type = unit.hevcType;
            var slice = unit.hevcSlice;
            if (slice == null) {
                unit.period = period;
                unit.poc = Long.MAX_VALUE / 2;
                unit.output = false;
                afterEndOfSequence |= unit.endOfSequence;
                return;
            }
            var irap = type >= 16 && type <= 23;
            var noRaslOutput = irap && (type <= 20 || !seenPicture || afterEndOfSequence);
            if (noRaslOutput) {
                snapshot(unit);
                period++;
            }
            if (irap) {
                skipLeading = noRaslOutput;
            }
            unit.period = period;
            var maxLsb = 1L << slice.sps().log2MaxPocLsb();
            var lsb = slice.pocLsb();
            long msb;
            if (noRaslOutput) {
                msb = 0;
            } else if (lsb < prevTid0Lsb && prevTid0Lsb - lsb >= maxLsb / 2) {
                msb = prevTid0Msb + maxLsb;
            } else if (lsb > prevTid0Lsb && lsb - prevTid0Lsb > maxLsb / 2) {
                msb = prevTid0Msb - maxLsb;
            } else {
                msb = prevTid0Msb;
            }
            unit.poc = msb + lsb;
            var rasl = type == 8 || type == 9;
            var radl = type == 6 || type == 7;
            var subLayerNonReference = type <= 14 && type % 2 == 0;
            if (unit.temporalId == 0 && !rasl && !radl && !subLayerNonReference) {
                prevTid0Msb = msb;
                prevTid0Lsb = lsb;
            }
            unit.output = slice.output() && !(rasl && skipLeading);
            // a CRA picture that starts a coded video sequence after the first empties the buffer as well
            unit.discardPrior = noRaslOutput && seenPicture && (type == 21 || slice.noOutputOfPriorPics());
            afterEndOfSequence = unit.endOfSequence;
        }

        /**
         * Reads the next complete access unit.
         *
         * @return the access unit, or {@code null} at the end of the input
         */
        private AccessUnit nextAccessUnit() {
            while (true) {
                if (!nextUnit()) {
                    var unit = finishAccessUnit();
                    return unit;
                }
                var completed = handleUnit(buffer, unitStart, unitLength, unitOffset);
                if (completed != null) {
                    return completed;
                }
            }
        }

        /**
         * The index of the first byte of the NAL unit {@link #nextUnit()} found.
         */
        private int unitStart;

        /**
         * The length of the NAL unit {@link #nextUnit()} found.
         */
        private int unitLength;

        /**
         * The stream offset of the start code of the NAL unit {@link #nextUnit()} found.
         */
        private long unitOffset;

        /**
         * Finds the next non-empty NAL unit, valid in {@link #buffer} until the next call.
         *
         * @return {@code true} when a unit was found, {@code false} at the end of the input
         */
        private boolean nextUnit() {
            while (true) {
                if (unitCode < 0) {
                    var code = NalUnits.findStartCode(buffer, scan, end);
                    if (code < 0) {
                        if (exhausted) {
                            return false;
                        }
                        fill(Math.max(0, end - 2));
                        continue;
                    }
                    unitCode = code;
                    scan = code + 3;
                }
                var next = NalUnits.findStartCode(buffer, scan, end);
                if (next < 0 && !exhausted) {
                    if (end - unitCode > CAPACITY) {
                        throw new MediaException.LimitExceeded("a NAL unit exceeds " + CAPACITY + " bytes");
                    }
                    scan = Math.max(unitCode + 3, end - 2);
                    fill(unitCode);
                    continue;
                }
                var start = unitCode + 3;
                var stop = next < 0 ? end : next;
                var trimmed = stop;
                while (trimmed > start && buffer[trimmed - 1] == 0) {
                    trimmed--;
                }
                unitStart = start;
                unitLength = trimmed - start;
                unitOffset = base + unitCode;
                if (next < 0) {
                    unitCode = -1;
                    scan = end;
                } else {
                    unitCode = next;
                    scan = next + 3;
                }
                if (unitLength > 0) {
                    return true;
                }
            }
        }

        /**
         * Reads the next chunk of the source, dropping the bytes before an index.
         *
         * @param keep the index of the first byte to keep
         */
        private void fill(int keep) {
            if (keep > 0) {
                System.arraycopy(buffer, keep, buffer, 0, end - keep);
                end -= keep;
                base += keep;
                scan -= keep;
                if (unitCode >= 0) {
                    unitCode -= keep;
                }
            }
            if (buffer.length - end < CHUNK) {
                LimitChecks.checkAllocation(owner.limits, (long) end + CHUNK);
                buffer = Arrays.copyOf(buffer, Math.max(buffer.length * 2, end + CHUNK));
            }
            var read = owner.source.read(base + end, buffer, end, CHUNK);
            if (read <= 0) {
                exhausted = true;
                return;
            }
            end += read;
        }

        /**
         * Adds a NAL unit to the access unit being collected.
         *
         * @param data the bytes
         * @param offset the index of the NAL unit header
         * @param length the NAL unit length
         * @param code the stream offset of the unit's start code
         * @return the access unit the NAL unit completed, or {@code null}
         */
        private AccessUnit handleUnit(byte[] data, int offset, int length, long code) {
            if ((data[offset] & 0x80) != 0 || (hevc && length < 2)) {
                return null;
            }
            var type = hevc ? (data[offset] >> 1) & 0x3F : data[offset] & 0x1F;
            var layer = hevc ? ((data[offset] & 1) << 5) | ((data[offset + 1] & 0xFF) >> 3) : 0;
            var slice = !hevc && (type == 1 || type == 2 || type == 5)
                    ? AvcSyntax.slice(data, offset, length, avcSequences, avcPictures) : null;
            AccessUnit completed = null;
            if (current != null && current.coded && startsAccessUnit(type, layer, data, offset, length, slice)) {
                completed = finishAccessUnit();
            }
            if (current == null) {
                current = new AccessUnit();
                current.offset = code;
            }
            if (hevc ? type == 35 : type == 9) {
                return completed;
            }
            parameterSet(type, layer, data, offset, length);
            if (hevc ? type <= 31 : type >= 1 && type <= 5) {
                current.coded = true;
                if (hevc) {
                    if (layer == 0 && current.hevcType < 0) {
                        current.hevcType = type;
                        current.temporalId = (data[offset + 1] & 7) - 1;
                        current.hevcSlice = HevcSyntax.slice(data, offset, length, hevcSequences, hevcPictures);
                    }
                    current.idr |= layer == 0 && type >= 16 && type <= 23;
                } else {
                    current.idr |= type == 5;
                    if (slice != null) {
                        if (current.avc == null) {
                            current.avc = slice;
                        }
                        if (slice.redundantPicCnt() == 0) {
                            current.lastSlice = slice;
                        }
                        if (!slice.intra()) {
                            current.intra = false;
                        }
                    }
                }
            } else if (hevc ? type == 39 && layer == 0 : type == 6) {
                readSei(current, data, offset, hevc ? 2 : 1, length);
            } else if (hevc && type == 36) {
                current.endOfSequence = true;
            }
            if (index == null) {
                append(current, data, offset, length);
            }
            return completed;
        }

        /**
         * Records a parameter set: for the track configuration before it is configured, as the active set of its
         * type and identifier, and parsed for the picture order counts.
         *
         * @param type the NAL unit type
         * @param layer the HEVC layer identifier
         * @param data the bytes
         * @param offset the index of the NAL unit header
         * @param length the NAL unit length
         */
        private void parameterSet(int type, int layer, byte[] data, int offset, int length) {
            int id;
            List<byte[]> target;
            if (hevc) {
                if (layer != 0) {
                    return;
                }
                switch (type) {
                    case HEVC_VPS -> {
                        id = length > 2 ? (data[offset + 2] & 0xFF) >> 4 : -1;
                        var vps = HevcSyntax.vps(data, offset, length);
                        if (vps != null) {
                            video = vps;
                        }
                        target = videoSets;
                    }
                    case HEVC_SPS -> {
                        var sps = HevcSyntax.sps(data, offset, length);
                        id = sps == null ? -1 : sps.id();
                        if (sps != null) {
                            hevcSequences.put(sps.id(), sps);
                        }
                        target = sequenceSets;
                    }
                    case HEVC_PPS -> {
                        var pps = HevcSyntax.pps(data, offset, length);
                        id = pps == null ? -1 : pps.id();
                        if (pps != null) {
                            hevcPictures.put(pps.id(), pps);
                        }
                        target = pictureSets;
                    }
                    default -> {
                        return;
                    }
                }
            } else {
                switch (type) {
                    case AVC_SPS -> {
                        var sps = AvcSyntax.sps(data, offset, length);
                        id = sps == null ? -1 : sps.id();
                        if (sps != null) {
                            avcSequences.put(sps.id(), sps);
                        }
                        target = sequenceSets;
                    }
                    case AVC_PPS -> {
                        var pps = AvcSyntax.pps(data, offset, length);
                        id = pps == null ? -1 : pps.id();
                        if (pps != null) {
                            avcPictures.put(pps.id(), pps);
                        }
                        target = pictureSets;
                    }
                    default -> {
                        return;
                    }
                }
            }
            if (id < 0 || length > 0xFFFF) {
                return;
            }
            var unit = Arrays.copyOfRange(data, offset, offset + length);
            var previous = active.put(type * 256 + id, unit);
            if (previous == null || !Arrays.equals(previous, unit)) {
                activated = true;
            }
            if (established == null && target.size() < MAX_PARAMETER_SETS) {
                for (var known : target) {
                    if (Arrays.equals(known, unit)) {
                        return;
                    }
                }
                target.add(unit);
            }
        }

        /**
         * Completes the access unit being collected.
         *
         * @return the access unit, or {@code null} when none holds a coded slice
         */
        private AccessUnit finishAccessUnit() {
            var unit = current;
            current = null;
            if (unit == null || !unit.coded) {
                return null;
            }
            unit.key = unit.idr || (!hevc && unit.recovery && unit.intra);
            if (unit.avc != null) {
                unit.field = unit.avc.field();
                unit.bottom = unit.avc.bottom();
            }
            if (unit.idr && established != null) {
                unit.newConfig = changedConfig();
            }
            return unit;
        }

        /**
         * Returns the configuration record of the active parameter sets when their sequence and video parameter sets
         * differ from the ones last reported, and makes them the reported ones.
         *
         * @return the record, or {@code null} when nothing changed or the active sets do not make a record
         */
        private byte[] changedConfig() {
            if (!activated) {
                return null;
            }
            activated = false;
            var units = new ArrayList<>(active.values());
            if (sameSets(sequenceLevel(units), sequenceLevel(established))) {
                return null;
            }
            var videoUnits = new ArrayList<byte[]>();
            var sequenceUnits = new ArrayList<byte[]>();
            var pictureUnits = new ArrayList<byte[]>();
            for (var entry : active.entrySet()) {
                var type = entry.getKey() / 256;
                if (hevc && type == HEVC_VPS) {
                    videoUnits.add(entry.getValue());
                } else if (type == (hevc ? HEVC_SPS : AVC_SPS)) {
                    sequenceUnits.add(entry.getValue());
                } else {
                    pictureUnits.add(entry.getValue());
                }
            }
            if (sequenceUnits.isEmpty() || pictureUnits.isEmpty()) {
                return null;
            }
            var config = hevc ? NalConfigs.hevcConfig(videoUnits, sequenceUnits, pictureUnits)
                    : NalConfigs.avcConfig(sequenceUnits, pictureUnits);
            if (config != null) {
                established = units;
            }
            return config;
        }

        /**
         * Returns the sequence and video parameter sets of a list of parameter sets.
         *
         * @param units whole NAL units
         * @return the units that are sequence or video parameter sets, in order
         */
        private List<byte[]> sequenceLevel(List<byte[]> units) {
            var result = new ArrayList<byte[]>();
            for (var unit : units) {
                var type = hevc ? (unit[0] >> 1) & 0x3F : unit[0] & 0x1F;
                if (hevc ? type == HEVC_VPS || type == HEVC_SPS : type == AVC_SPS) {
                    result.add(unit);
                }
            }
            return result;
        }

        /**
         * Returns whether two lists hold the same parameter sets, in any order.
         *
         * @param first a list of whole NAL units
         * @param second a list of whole NAL units
         * @return {@code true} when every unit of each list is in the other and the sizes agree
         */
        private static boolean sameSets(List<byte[]> first, List<byte[]> second) {
            if (first.size() != second.size()) {
                return false;
            }
            for (var unit : first) {
                var found = false;
                for (var other : second) {
                    if (Arrays.equals(unit, other)) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    return false;
                }
            }
            return true;
        }

        /**
         * Returns whether a NAL unit starts a new access unit after a coded slice.
         *
         * @param type the NAL unit type
         * @param layer the HEVC layer identifier
         * @param data the bytes
         * @param offset the index of the NAL unit header
         * @param length the NAL unit length
         * @param slice the header of an H.264 slice, or {@code null} for another NAL unit or one that cannot be read
         * @return {@code true} when the NAL unit belongs to the next access unit
         */
        private boolean startsAccessUnit(int type, int layer, byte[] data, int offset, int length,
                                         AvcSyntax.Slice slice) {
            if (hevc) {
                if (layer != 0) {
                    return false;
                }
                if (type <= 31) {
                    return length > 2 && (data[offset + 2] & 0x80) != 0;
                }
                return (type >= 32 && type <= 35) || type == 39 || (type >= 41 && type <= 44)
                        || (type >= 48 && type <= 55);
            }
            if (type >= 1 && type <= 5) {
                if (type == 3 || type == 4) {
                    return false;
                }
                if (slice != null && current.lastSlice != null) {
                    // the first slice of a new primary picture, in any slice order (ITU-T H.264 clause 7.4.1.2.4)
                    return slice.redundantPicCnt() == 0 && !current.lastSlice.samePicture(slice);
                }
                return length > 1 && (data[offset + 1] & 0x80) != 0;
            }
            return (type >= 6 && type <= 9) || (type >= 14 && type <= 18);
        }

        /**
         * Reads the messages of an SEI NAL unit (ITU-T H.264 clause 7.3.2.3, H.265 clause 7.3.5) into an access
         * unit: whether an H.264 recovery point message ({@code payloadType} 6) is present, and the payload of the
         * first picture timing message ({@code payloadType} 1).
         *
         * @param unit the access unit
         * @param data the bytes
         * @param offset the index of the NAL unit header
         * @param header the size of the NAL unit header
         * @param length the NAL unit length
         */
        private void readSei(AccessUnit unit, byte[] data, int offset, int header, int length) {
            if (length <= header) {
                return;
            }
            var rbsp = NalUnits.unescape(data, offset + header, length - header);
            var position = 0;
            while (position < rbsp.length && (rbsp[position] & 0xFF) != 0x80) {
                var payloadType = 0;
                while (position < rbsp.length && (rbsp[position] & 0xFF) == 0xFF) {
                    payloadType += 255;
                    position++;
                }
                if (position >= rbsp.length) {
                    return;
                }
                payloadType += rbsp[position++] & 0xFF;
                var payloadSize = 0;
                while (position < rbsp.length && (rbsp[position] & 0xFF) == 0xFF) {
                    payloadSize += 255;
                    position++;
                }
                if (position >= rbsp.length) {
                    return;
                }
                payloadSize += rbsp[position++] & 0xFF;
                if (payloadType == 6 && !hevc) {
                    unit.recovery = true;
                } else if (payloadType == 1 && unit.picTiming == null) {
                    unit.picTiming = Arrays.copyOfRange(rbsp, position, (int) Math.min(rbsp.length,
                            (long) position + payloadSize));
                }
                position += payloadSize;
            }
        }

        /**
         * Appends a NAL unit with its four-byte length to an access unit.
         *
         * @param unit the access unit
         * @param data the bytes
         * @param offset the index of the NAL unit header
         * @param length the NAL unit length
         */
        private void append(AccessUnit unit, byte[] data, int offset, int length) {
            var required = (long) unit.size + 4 + length;
            if (required > unit.data.length) {
                if (required > CAPACITY) {
                    throw new MediaException.LimitExceeded("an access unit exceeds " + CAPACITY + " bytes");
                }
                LimitChecks.checkAllocation(owner.limits, required);
                var grown = Math.min(CAPACITY, Math.max(required, unit.data.length * 2L));
                unit.data = Arrays.copyOf(unit.data, (int) grown);
            }
            var position = unit.size;
            unit.data[position] = (byte) (length >>> 24);
            unit.data[position + 1] = (byte) (length >>> 16);
            unit.data[position + 2] = (byte) (length >>> 8);
            unit.data[position + 3] = (byte) length;
            System.arraycopy(data, offset, unit.data, position + 4, length);
            unit.size = position + 4 + length;
        }
    }

    /**
     * The packet the index scan returns in place of real packets.
     */
    private static final Packet PLACEHOLDER = new Packet(1, new byte[0], 0, 0, 0, false);
}
