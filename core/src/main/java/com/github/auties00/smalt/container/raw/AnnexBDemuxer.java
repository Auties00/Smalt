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

/**
 * Reads an H.264 ({@code .h264}, {@code .264}, {@code .jsv}) or HEVC ({@code .h265}, {@code .265}, {@code .bit})
 * elementary stream in the Annex B byte stream format: NAL units after {@code 00 00 01} start codes.
 *
 * <p>The stream becomes one {@link VideoTrack} of {@link Codec#H264} or {@link Codec#HEVC}, with identifier 1, whose
 * configuration record ({@code avcC} or {@code hvcC}) holds the parameter sets met before the first key frame and
 * which takes its picture size, color and sample aspect ratio from the first sequence parameter set. Each access unit
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
 * <p>The stream holds no timestamps, so the demuxer derives them from the frame rate of the
 * {@linkplain ElementaryStreamOptions#frameRate() options} (by default the stream's VUI timing, else 25 frames per
 * second) and the pictures' order counts. The time base is half a frame period: a frame lasts 2 ticks and an H.264
 * field coded alone 1 tick. Presentation times count the pictures before each one in output order, starting at 0;
 * decoding times count the pictures before each one in decoding order, less the stream's reorder depth (its VUI
 * {@code max_num_reorder_frames} or {@code sps_max_num_reorder_pics}, or else the largest depth among the first
 * pictures), so the first ones may be negative. Pictures the decoding process does not output (HEVC pictures with
 * {@code pic_output_flag} zero and the skipped leading pictures of a stream that starts at a CRA picture) have no
 * presentation time.
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
     * The largest number of distinct parameter sets of one type kept for the track configuration.
     */
    private static final int MAX_PARAMETER_SETS = 16;

    /**
     * The largest reorder depth of H.264 and HEVC, in frames.
     */
    private static final int MAX_REORDER = 16;

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
     * The splitter that reads the packets.
     */
    private Splitter splitter;

    /**
     * The number of pictures the presentation ordering looks ahead, from the first sequence parameter set, or
     * {@code -1} until the first splitter starts.
     */
    private int lookahead = -1;

    /**
     * The decoding delay in ticks the first sequence parameter set declares, or {@code -1} when it declares none and
     * the delay is measured.
     */
    private long declaredDelay = -1;

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
        this.frameRate = options.frameRate().orElse(configured.frameRate() != null ? configured.frameRate()
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
        resumed.delay = index.delay;
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
        scan.delay = splitter.delay;
        scan.start();
        while (scan.next() != null) {
            if (splitter.delay < 0) {
                splitter.delay = scan.delay;
            }
        }
        scan.index.delay = scan.delay;
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
     * @param decodeClock the decoding time of the access unit, before the delay
     * @param presentClock the presentation time at which its run starts
     * @param period the run number
     * @param avcSequences the H.264 sequence parameter sets met
     * @param avcPictures the H.264 picture parameter sets met
     * @param hevcSequences the HEVC sequence parameter sets met
     * @param hevcPictures the HEVC picture parameter sets met
     * @param active the latest parameter sets by type and identifier
     * @param established the parameter sets of the configuration last reported
     */
    private record Snapshot(long offset, long decodeClock, long presentClock, long period,
                            Map<Integer, AvcSyntax.Sps> avcSequences, Map<Integer, AvcSyntax.Pps> avcPictures,
                            Map<Integer, HevcSyntax.Sps> hevcSequences, Map<Integer, HevcSyntax.Pps> hevcPictures,
                            TreeMap<Integer, byte[]> active, List<byte[]> established) {
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
         * The decoding delay in ticks.
         */
        long delay;
    }

    /**
     * The configuration the stream's leading parameter sets give.
     *
     * @param config the {@code avcC} or {@code hvcC} record
     * @param width the picture width, or {@code 0}
     * @param height the picture height, or {@code 0}
     * @param color the color description, or {@code null}
     * @param sampleAspect the sample aspect ratio, or {@code null}
     * @param frameRate the frame rate of the timing information, or {@code null}
     */
    private record Configured(byte[] config, int width, int height, com.github.auties00.smalt.image.ColorInfo color,
                              Rational sampleAspect, Rational frameRate) {
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
         * The decoding delay in ticks, or {@code -1} until measured.
         */
        long delay = -1;

        /**
         * Packets of access units starting before this offset are not returned.
         */
        long skipBefore;

        private long period;

        /**
         * The sum of the durations of the output pictures added.
         */
        private long presentTotal;

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
                presentTotal = resume.presentClock;
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
         * @return the configuration, or {@code null} when the stream has no parameter sets
         */
        Configured configure() {
            pending = new ArrayList<>();
            while (true) {
                var unit = nextAccessUnit();
                if (unit == null) {
                    break;
                }
                pending.add(unit);
                if (unit.key && !sequenceSets.isEmpty() && !pictureSets.isEmpty()) {
                    break;
                }
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
                return new Configured(config, 0, 0, null, null, null);
            }
            return new Configured(config, info.width(), info.height(), info.color(), info.sampleAspect(),
                    info.frameRate());
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
                var reorder = -1;
                var interlaced = false;
                if (hevc) {
                    var sps = hevcSequences.values().stream().findFirst().orElse(null);
                    if (sps != null) {
                        reorder = sps.reorderPictures();
                    }
                } else {
                    var sps = avcSequences.values().stream().findFirst().orElse(null);
                    if (sps != null) {
                        reorder = sps.reorderFrames() >= 0 ? sps.reorderFrames() : sps.pocType() == 2 ? 0 : -1;
                        interlaced = !sps.frameMbsOnly();
                    }
                }
                var depth = reorder >= 0 ? reorder : MAX_REORDER;
                // an interlaced stream may code each frame as two fields, each a picture of the ordering
                owner.lookahead = interlaced ? 2 * depth + 1 : depth;
                owner.declaredDelay = reorder >= 0 ? 2L * reorder : -1;
            }
            var startDelay = delay >= 0 ? delay : owner.declaredDelay;
            var decodeClock = resumed == null ? 0 : resumed.decodeClock;
            order = new PresentationOrder(owner.lookahead, startDelay, resumed == null ? 0 : resumed.presentClock,
                    decodeClock);
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
            order = new PresentationOrder(0, 0, 0, 0);
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
                if (delay < 0) {
                    delay = order.delay();
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
                        unit.output ? unit.duration : 0, flags, side);
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
                    first.duration = 2;
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
            avcPoc(unit, false);
            if (unit.field) {
                unit.duration = 1;
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
            if (unit.output) {
                presentTotal += unit.duration;
            }
            order.add(new PresentationOrder.Entry(unit.poc, unit.period, unit.output, unit.duration, unit));
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
            index.snapshots.add(new Snapshot(unit.offset, order.decodeClock(), presentTotal, period + 1,
                    Map.copyOf(avcSequences), Map.copyOf(avcPictures), Map.copyOf(hevcSequences),
                    Map.copyOf(hevcPictures), new TreeMap<>(active),
                    established == null ? List.of() : List.copyOf(established)));
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
            } else if (!hevc && type == 6 && hasRecoveryPoint(data, offset, length)) {
                current.recovery = true;
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
         * Returns whether an H.264 SEI NAL unit holds a recovery point message ({@code payloadType} 6, ITU-T H.264
         * clause D.1.8).
         *
         * @param data the bytes
         * @param offset the index of the NAL unit header
         * @param length the NAL unit length
         * @return {@code true} when a recovery point message is present
         */
        private static boolean hasRecoveryPoint(byte[] data, int offset, int length) {
            var rbsp = NalUnits.unescape(data, offset + 1, length - 1);
            var position = 0;
            while (position < rbsp.length && (rbsp[position] & 0xFF) != 0x80) {
                var payloadType = 0;
                while (position < rbsp.length && (rbsp[position] & 0xFF) == 0xFF) {
                    payloadType += 255;
                    position++;
                }
                if (position >= rbsp.length) {
                    return false;
                }
                payloadType += rbsp[position++] & 0xFF;
                var payloadSize = 0;
                while (position < rbsp.length && (rbsp[position] & 0xFF) == 0xFF) {
                    payloadSize += 255;
                    position++;
                }
                if (position >= rbsp.length) {
                    return false;
                }
                payloadSize += rbsp[position++] & 0xFF;
                if (payloadType == 6) {
                    return true;
                }
                position += payloadSize;
            }
            return false;
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
