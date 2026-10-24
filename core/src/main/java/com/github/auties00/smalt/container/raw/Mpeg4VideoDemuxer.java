package com.github.auties00.smalt.container.raw;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.container.internal.SeekTimes;
import com.github.auties00.smalt.internal.bits.MsbBitReader;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.VideoTrack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;

/**
 * Reads an MPEG-4 Part 2 ({@code .m4v}, {@code .cmp}) or H.263 ({@code .263}) video elementary stream, one packet
 * per picture.
 *
 * <p>An MPEG-4 stream starts with a visual object sequence, visual object, video object or video object layer start
 * code ({@code 00 00 01 B0}, {@code B5}, {@code 00} to {@code 1F}, {@code 20} to {@code 2F}) or, with the short video
 * header, with a picture start code; an H.263 stream starts with a picture start code ({@code 00 00 80} to
 * {@code 00 00 83}). The stream becomes one {@link VideoTrack} with identifier 1, of {@link Codec#MPEG4_PART2} or,
 * for a stream of picture start codes, {@link Codec#H263}. Every packet ends where the next picture's headers begin:
 * the configuration headers and group of VOP headers that precede a VOP belong to the packet of that VOP, so the
 * first packet carries the stream headers. The headers before the first VOP are also the track's
 * {@linkplain VideoTrack#codecConfig() codec configuration}.
 *
 * <p>Every packet carries the presentation time of its picture, from the stream's own clock. An MPEG-4 stream is
 * timed in units of its video object layer's {@code vop_time_increment_resolution} (the track's time base is its
 * inverse): a VOP's time is its {@code modulo_time_base} seconds after the time base of the previous I-, P- or S-VOP
 * in decoding order (for a B-VOP, the one before it, which precedes the B-VOP in display order), or after the time
 * code of the group of VOP header before it, plus its {@code vop_time_increment} (ISO/IEC 14496-2 clause 6.3.5). An
 * H.263 stream is timed in units of its picture clock, 1001/30000 second or the custom picture clock frequency of
 * its first picture (ITU-T H.263 clause 5.1.2 and Annex P), from the temporal references (with the extended
 * temporal reference of a custom clock) unwrapped from picture to picture; a B-picture (Annex O) counts back from
 * the reference picture before it. The first picture is at the time of its own clock reading less that of the first
 * picture, so presentation times start at 0. A picture's decoding time is the presentation time of the picture that
 * comes one place earlier in display order than the number of pictures decoded before it, when the stream may hold
 * B-VOPs (a video object layer without {@code low_delay}, an H.263 stream with an extended picture type), and its
 * presentation time otherwise, so no decoding time follows its presentation time; the decoding time of the first
 * picture of a stream with B-VOPs is earlier by the frame period ({@code fixed_vop_time_increment}, or the time
 * between the first two pictures). Packets of a stream whose time base cannot be read (an MPEG-4 stream with no
 * video object layer header before its first VOP) carry no timestamp but the first one, zero. Packets of intra
 * pictures are flagged as key frames, and packets last {@code fixed_vop_time_increment} when the video object layer
 * fixes the VOP rate.
 *
 * <p>The demuxer is seekable: the first {@link #seek(long)} reads the whole stream once to index its intra pictures,
 * and every seek then resumes from the intra picture at or before the target, with the timestamps a linear read
 * gives. The source is read sequentially between seeks and not closed. Instances are not thread-safe.
 */
public final class Mpeg4VideoDemuxer implements Demuxer {
    /**
     * The time base of a stream whose clock cannot be read.
     */
    private static final Rational UNTIMED = new Rational(1, 90_000);

    /**
     * The H.263 picture clock without a custom picture clock frequency, 30000/1001 hertz.
     */
    private static final Rational H263_CLOCK = new Rational(1001, 30_000);

    /**
     * The number of bytes read from the source at once.
     */
    private static final int CHUNK = 64 * 1024;

    /**
     * The most bytes searched for the first video object plane, whose headers become the track configuration.
     */
    private static final int MAX_HEADERS = 1 << 20;

    /**
     * The size of the H.263 picture start code, in bits.
     */
    private static final int PSC_BITS = 22;

    /**
     * The size of the H.263 temporal reference, in bits.
     */
    private static final int TR_BITS = 8;

    /**
     * The size of PTYPE when it announces a PLUSPTYPE, in bits.
     */
    private static final int PTYPE_BITS = 8;

    /**
     * The index one past the source format field within PTYPE, in bits.
     */
    private static final int FORMAT_END = 8;

    /**
     * The PTYPE source format announcing a PLUSPTYPE.
     */
    private static final int EXTENDED_FORMAT = 7;

    /**
     * The size of the PLUSPTYPE update full extended PTYPE field (UFEP), in bits.
     */
    private static final int UFEP_BITS = 3;

    /**
     * The size of the optional part of PLUSPTYPE (OPPTYPE), present when UFEP is 1, in bits.
     */
    private static final int OPPTYPE_BITS = 18;

    /**
     * The size of the picture type code that starts the mandatory part of PLUSPTYPE, in bits.
     */
    private static final int TYPE_BITS = 3;

    /**
     * The H.263 PLUSPTYPE picture type of a B-picture (Annex O).
     */
    private static final int H263_B_PICTURE = 4;

    /**
     * The MPEG-4 {@code vop_coding_type} of a B-VOP.
     */
    private static final int B_VOP = 2;

    /**
     * The most {@code modulo_time_base} bits read from one VOP header.
     */
    private static final int MAX_MODULO = 64;

    /**
     * The source.
     */
    private final MediaSource source;

    /**
     * The limits.
     */
    private final MediaLimits limits;

    /**
     * Whether the stream is H.263 (or MPEG-4 with only short video headers).
     */
    private final boolean shortHeader;

    /**
     * The track.
     */
    private final VideoTrack track;

    /**
     * The video object layer parameters of the first header, or {@code null} for H.263 or a stream without one.
     */
    private final Layer firstLayer;

    /**
     * Whether a picture can be presented after a picture decoded after it.
     */
    private final boolean reordered;

    /**
     * The reader the packets come from.
     */
    private Reader reader;

    /**
     * The intra pictures of the whole stream, built on the first seek, or {@code null}.
     */
    private Index index;

    /**
     * Constructs a demuxer with {@link DemuxerOptions#defaults()}.
     *
     * @param source the stream, read from offset zero and not closed by this demuxer
     * @throws MediaException.Unsupported if the content is not an MPEG-4 Part 2 or H.263 elementary stream
     * @throws MediaException.Io if reading fails
     */
    public Mpeg4VideoDemuxer(MediaSource source) {
        this(source, DemuxerOptions.defaults());
    }

    /**
     * Constructs a demuxer whose {@link DemuxerOptions#limits()} bound the size of one picture.
     *
     * @param source the stream, read from offset zero and not closed by this demuxer
     * @param options the demuxing options
     * @throws MediaException.Unsupported if the content is not an MPEG-4 Part 2 or H.263 elementary stream
     * @throws MediaException.Io if reading fails
     */
    public Mpeg4VideoDemuxer(MediaSource source, DemuxerOptions options) {
        this(Objects.requireNonNull(source, "source"), Objects.requireNonNull(options, "options").limits());
    }

    /**
     * Recognizes an MPEG-4 Part 2 or H.263 elementary stream from its leading bytes.
     *
     * <p>An MPEG-4 stream must start with one of its configuration start codes and hold a video object plane start
     * code ({@code 00 00 01 B6}) within the bytes given; an H.263 stream must start with a picture start code whose
     * picture type is plausible and hold a second one.
     *
     * @param header the leading bytes of the input
     * @return {@link ContainerFormat#MPEG4_VIDEO} or {@link ContainerFormat#H263}, or empty when the bytes start
     * neither
     */
    public static Optional<ContainerFormat> detect(byte[] header) {
        return Optional.ofNullable(ElementaryProbe.mpeg4(Objects.requireNonNull(header, "header")));
    }

    /**
     * Returns whether bytes start with an MPEG-4 Part 2 configuration start code or a picture start code.
     *
     * @param header the first bytes of the stream, at least four
     * @return {@code true} when they start with a configuration or picture start code
     */
    private static boolean startsStream(byte[] header) {
        if (header.length < 4 || header[0] != 0 || header[1] != 0) {
            return false;
        }
        if ((header[2] & 0xFC) == 0x80) {
            return true;
        }
        if (header[2] != 1) {
            return false;
        }
        var code = header[3] & 0xFF;
        return code == 0xB0 || code == 0xB5 || code <= 0x2F;
    }

    /**
     * Opens the stream and reads its headers.
     *
     * @param source the stream, read from offset zero
     * @param limits the limits bounding the size of one picture
     */
    private Mpeg4VideoDemuxer(MediaSource source, MediaLimits limits) {
        this.source = source;
        this.limits = limits;
        var first = new Reader(this, 0, null, null);
        while (first.end - first.start < 4 && !first.exhausted) {
            first.fill();
        }
        var head = Arrays.copyOfRange(first.buffer, first.start, Math.min(first.end, first.start + 4));
        if (!startsStream(head)) {
            throw new MediaException.Unsupported("mpeg4-elementary", "not an MPEG-4 Part 2 or H.263 elementary stream");
        }
        shortHeader = (head[2] & 0xFC) == 0x80;
        byte[] config = null;
        Rational timeBase;
        if (shortHeader) {
            firstLayer = null;
            while (first.end - first.start < 16 && !first.exhausted) {
                first.fill();
            }
            var picture = h263Picture(first.buffer, first.start, first.end - first.start, false, first.bits);
            timeBase = picture == null || picture.clock == null ? H263_CLOCK : picture.clock;
            reordered = picture != null && picture.extended;
        } else {
            var vop = first.findPicture(first.start);
            var searched = Math.min(limits.maxBytes(), MAX_HEADERS);
            while (vop < 0 && !first.exhausted && first.end - first.start < searched) {
                first.fill();
                vop = first.findPicture(first.start);
            }
            if (vop > first.start) {
                config = Arrays.copyOfRange(first.buffer, first.start, vop);
            }
            firstLayer = config == null ? null : Layer.find(config, 0, config.length);
            timeBase = firstLayer == null ? UNTIMED : new Rational(1, firstLayer.resolution);
            reordered = firstLayer == null || !firstLayer.lowDelay;
        }
        this.track = VideoTrack.builder()
                .id(1)
                .codec(shortHeader ? Codec.H263 : Codec.MPEG4_PART2)
                .timeBase(timeBase)
                .codecConfig(config)
                .build();
        this.reader = first;
    }

    /**
     * {@return {@link ContainerFormat#H263} for a stream of picture start codes, {@link ContainerFormat#MPEG4_VIDEO}
     * otherwise}
     */
    @Override
    public ContainerFormat format() {
        return shortHeader ? ContainerFormat.H263 : ContainerFormat.MPEG4_VIDEO;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The single track is MPEG-4 Part 2 with the headers before the first VOP as configuration, or H.263; its
     * size is left for the decoder to read.
     */
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
     * <p>The duration is known once a seek has indexed the stream: the end of the last picture in display order.
     */
    @Override
    public long durationMicros() {
        return index == null ? -1 : track.timeBase().toMicros(index.end);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Each packet holds one picture, flagged as a key frame when the picture is intra coded.
     *
     * @throws MediaException.LimitExceeded if a picture exceeds the allocation limit
     * @throws MediaException.Io {@inheritDoc}
     */
    @Override
    public Packet read() {
        return reader.next();
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
     * @throws MediaException.LimitExceeded if a picture exceeds the allocation limit
     * @throws MediaException.Io {@inheritDoc}
     */
    @Override
    public void seek(long micros) {
        if (index == null) {
            var scan = new Reader(this, 0, null, new Index());
            while (scan.next() != null) {
                // the scan records the index as it reads
            }
            index = scan.index;
            if (index.last != Long.MIN_VALUE) {
                var step = firstLayer != null && firstLayer.fixedIncrement > 0 ? firstLayer.fixedIncrement
                        : index.beforeLast != Long.MIN_VALUE ? index.last - index.beforeLast : 0;
                index.end = index.last + step;
            }
        }
        var target = SeekTimes.floor(micros, track.timeBase());
        if (target >= index.end && index.end > 0) {
            var resumed = new Reader(this, index.endOffset, null, null);
            resumed.exhausted = true;
            reader = resumed;
            return;
        }
        Key chosen = null;
        for (var key : index.keys) {
            if (key.pts <= target && (chosen == null || key.pts > chosen.pts)) {
                chosen = key;
            }
        }
        if (chosen == null && !index.keys.isEmpty()) {
            chosen = index.keys.getFirst();
        }
        reader = chosen == null || chosen.offset == 0 ? new Reader(this, 0, null, null)
                : new Reader(this, chosen.offset, chosen.state, null);
    }

    /**
     * The fields of a video object layer header that time its VOPs (ISO/IEC 14496-2 clause 6.2.3).
     *
     * @param resolution {@code vop_time_increment_resolution}, positive
     * @param incrementBits the size of {@code vop_time_increment}, in bits
     * @param fixedIncrement {@code fixed_vop_time_increment}, or 0 when the VOP rate is not fixed
     * @param lowDelay {@code low_delay}: whether the layer holds no B-VOPs
     */
    private record Layer(int resolution, int incrementBits, int fixedIncrement, boolean lowDelay) {
        /**
         * Finds and reads the first video object layer header in a range.
         *
         * @param data the bytes
         * @param from the first index
         * @param to the index after the last
         * @return the fields, or {@code null} when the range holds no readable header
         */
        static Layer find(byte[] data, int from, int to) {
            for (var i = from; i + 4 < to; i++) {
                if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1) {
                    var code = data[i + 3] & 0xFF;
                    if (code == 0xB6) {
                        return null;
                    }
                    if ((code & 0xF0) == 0x20) {
                        var layer = read(data, i + 4, to);
                        if (layer != null) {
                            return layer;
                        }
                    }
                }
            }
            return null;
        }

        /**
         * Reads a video object layer header after its start code.
         *
         * @param data the bytes
         * @param from the index after the start code
         * @param to the index after the last byte
         * @return the fields, or {@code null} for a truncated or invalid header
         */
        private static Layer read(byte[] data, int from, int to) {
            var bits = new MsbBitReader(data, from, to - from);
            bits.skip(1);
            bits.skip(8);
            var verid = 1;
            if (bits.readFlag()) {
                verid = bits.read(4);
                bits.skip(3);
            }
            if (bits.read(4) == 15) {
                bits.skip(16);
            }
            var lowDelay = false;
            if (bits.readFlag()) {
                bits.skip(2);
                lowDelay = bits.readFlag();
                if (bits.readFlag()) {
                    bits.skip(79);
                }
            }
            var shape = bits.read(2);
            if (shape == 3 && verid != 1) {
                bits.skip(4);
            }
            bits.skip(1);
            var resolution = bits.read(16);
            bits.skip(1);
            var fixed = bits.readFlag();
            var incrementBits = Math.max(1, 32 - Integer.numberOfLeadingZeros(Math.max(1, resolution - 1)));
            var increment = fixed ? bits.read(incrementBits) : 0;
            if (bits.overrun() || resolution <= 0) {
                return null;
            }
            return new Layer(resolution, incrementBits, increment, lowDelay);
        }
    }

    /**
     * The fields of an H.263 picture header that time it.
     *
     * @param temporalReference the temporal reference, with the extended temporal reference when present
     * @param modulus the range of the temporal reference, 256 or 1024
     * @param bPicture whether the picture is a B-picture
     * @param extended whether the header has an extended picture type (PLUSPTYPE)
     * @param customClock whether a custom picture clock frequency is in use after this header
     * @param clock the period of the custom picture clock this header sets, or {@code null}
     */
    private record H263Picture(int temporalReference, int modulus, boolean bPicture, boolean extended,
                               boolean customClock, Rational clock) {
    }

    /**
     * Reads the timing fields of an H.263 picture header (ITU-T H.263 clause 5.1 and Annex P).
     *
     * @param data the bytes
     * @param offset the index of the picture start code
     * @param length the bytes available from the start code
     * @param customClock whether a custom picture clock frequency was in use before this header
     * @param bits the reader to read the header with
     * @return the fields, or {@code null} when the header is truncated
     */
    private static H263Picture h263Picture(byte[] data, int offset, int length, boolean customClock,
                                           MsbBitReader bits) {
        bits.reset(data, offset, Math.min(length, 64));
        bits.skip(PSC_BITS);
        var tr = bits.read(TR_BITS);
        bits.skip(5);
        var format = bits.read(3);
        if (format != EXTENDED_FORMAT) {
            return bits.overrun() ? null : new H263Picture(tr, 256, false, false, false, null);
        }
        var ufep = bits.read(UFEP_BITS);
        var custom = customClock;
        var sourceFormat = -1;
        if (ufep == 1) {
            sourceFormat = bits.read(3);
            custom = bits.readFlag();
            bits.skip(OPPTYPE_BITS - 4);
        }
        var type = bits.read(TYPE_BITS);
        bits.skip(6);
        if (bits.readFlag()) {
            bits.skip(2);
        }
        Rational clock = null;
        if (ufep == 1) {
            if (sourceFormat == 6) {
                var aspect = bits.read(4);
                bits.skip(19);
                if (aspect == 15) {
                    bits.skip(16);
                }
            }
            if (custom) {
                var conversion = bits.read(1);
                var divisor = bits.read(7);
                if (divisor > 0) {
                    clock = new Rational((long) divisor * (1000 + conversion), 1_800_000).reduce();
                }
            }
        }
        if (custom) {
            tr |= bits.read(2) << 8;
        }
        if (bits.overrun()) {
            return null;
        }
        return new H263Picture(tr, custom ? 1024 : 256, type == H263_B_PICTURE, true, custom, clock);
    }

    /**
     * The clock state of a stream between two pictures.
     */
    private static final class Clock {
        /**
         * The time base in seconds of the latest I-, P- or S-VOP or group of VOP header.
         */
        long referenceBase;

        /**
         * The time base in seconds of the I-, P- or S-VOP before the latest one.
         */
        long previousBase;

        /**
         * The video object layer in effect, or {@code null}.
         */
        Layer layer;

        /**
         * The time of the first picture, which presentation times count from, or {@code Long.MIN_VALUE} until it
         * is read.
         */
        long origin = Long.MIN_VALUE;

        /**
         * The unwrapped time of the latest H.263 reference picture.
         */
        long referenceTime;

        /**
         * The temporal reference of the latest H.263 reference picture, or {@code -1} before the first.
         */
        int referenceTr = -1;

        /**
         * Whether an H.263 custom picture clock frequency is in use.
         */
        boolean customClock;

        Clock copy() {
            var copy = new Clock();
            copy.referenceBase = referenceBase;
            copy.previousBase = previousBase;
            copy.layer = layer;
            copy.origin = origin;
            copy.referenceTime = referenceTime;
            copy.referenceTr = referenceTr;
            copy.customClock = customClock;
            return copy;
        }
    }

    /**
     * The state a reader resumes from at a key frame.
     *
     * @param clock the clock before the key frame's packet
     * @param pending the presentation times not yet used as decoding times
     * @param decoded the number of pictures before the key frame
     * @param lastDts the decoding time of the picture before the key frame
     * @param period the frame period of the first decoding time
     */
    private record State(Clock clock, long[] pending, long decoded, long lastDts, long period) {
    }

    /**
     * A key frame of the stream.
     *
     * @param offset the offset of its packet
     * @param pts its presentation time
     * @param state the state of a reader at its packet
     */
    private record Key(long offset, long pts, State state) {
    }

    /**
     * The key frames of a stream.
     */
    private static final class Index {
        /**
         * The key frames, in decoding order.
         */
        final List<Key> keys = new ArrayList<>();

        /**
         * The end of the last picture in display order: the last presentation time plus the frame period, or the
         * last interval between presentation times.
         */
        long end;

        /**
         * The size of the stream.
         */
        long endOffset;

        /**
         * The largest presentation time, or {@code Long.MIN_VALUE}.
         */
        long last = Long.MIN_VALUE;

        /**
         * The largest presentation time below {@link #last}, or {@code Long.MIN_VALUE}.
         */
        long beforeLast = Long.MIN_VALUE;

        /**
         * Records a presentation time.
         *
         * @param pts the time
         */
        void time(long pts) {
            if (pts > last) {
                beforeLast = last;
                last = pts;
            } else if (pts < last && pts > beforeLast) {
                beforeLast = pts;
            }
        }
    }

    /**
     * A picture read and timed, waiting for its decoding time.
     *
     * @param data the packet
     * @param pts the presentation time, or {@link Packet#NO_TIMESTAMP}
     * @param key whether the picture is intra coded
     */
    private record Picture(byte[] data, long pts, boolean key) {
    }

    /**
     * Splits the stream into pictures and times them, from a position of the stream.
     */
    private static final class Reader {
        private final Mpeg4VideoDemuxer owner;

        /**
         * The index being built, or {@code null} when packets are returned.
         */
        final Index index;

        /**
         * The bytes read and not yet returned, from {@link #start} to {@link #end}.
         */
        byte[] buffer = new byte[CHUNK * 2];

        /**
         * The first byte not yet returned.
         */
        int start;

        /**
         * The end of the bytes read.
         */
        int end;

        /**
         * The position in the source of the next byte to read.
         */
        long position;

        /**
         * Whether the source is exhausted.
         */
        boolean exhausted;

        /**
         * The clock.
         */
        private Clock clock;

        /**
         * The presentation times of the pictures read and not yet used as decoding times.
         */
        private final PriorityQueue<Long> pending = new PriorityQueue<>();

        /**
         * The number of pictures read.
         */
        private long decoded;

        /**
         * The decoding time of the last packet returned, or {@code Long.MIN_VALUE}.
         */
        private long lastDts = Long.MIN_VALUE;

        /**
         * The first picture of a stream with B-VOPs, held until the second gives its decoding time, or
         * {@code null}.
         */
        private Picture held;

        /**
         * The frame period that times the first picture of a stream with B-VOPs, or 0 until known.
         */
        private long period;

        /**
         * The packets timed and not yet returned.
         */
        private final ArrayDeque<Packet> ready = new ArrayDeque<>();

        /**
         * The bit reader of the picture headers, reused.
         */
        final MsbBitReader bits = new MsbBitReader(new byte[0]);

        /**
         * Constructs a reader.
         *
         * @param owner the demuxer
         * @param offset the stream offset to read from
         * @param state the state to resume from, or {@code null} to start fresh
         * @param index the index to build, or {@code null} to return packets
         */
        Reader(Mpeg4VideoDemuxer owner, long offset, State state, Index index) {
            this.owner = owner;
            this.index = index;
            this.position = offset;
            if (state != null) {
                clock = state.clock.copy();
                for (var time : state.pending) {
                    pending.add(time);
                }
                decoded = state.decoded;
                lastDts = state.lastDts;
                period = state.period;
            } else {
                clock = new Clock();
            }
        }

        /**
         * Returns the next packet.
         *
         * @return the packet, or {@code null} at the end of the stream
         */
        Packet next() {
            while (ready.isEmpty()) {
                var offset = position - (end - start);
                var data = nextPicture();
                if (data == null) {
                    if (held != null) {
                        emit(held, held.pts);
                        held = null;
                        continue;
                    }
                    if (index != null) {
                        index.endOffset = position;
                    }
                    return null;
                }
                var key = isKey(data);
                var resume = index != null && key ? state() : null;
                var picture = new Picture(data, time(data), key);
                if (resume != null && picture.pts != Packet.NO_TIMESTAMP) {
                    index.keys.add(new Key(offset, picture.pts, resume));
                }
                if (index != null && picture.pts != Packet.NO_TIMESTAMP) {
                    index.time(picture.pts);
                }
                order(picture);
            }
            return ready.poll();
        }

        /**
         * {@return the state to resume from before the next picture}
         */
        private State state() {
            var times = new long[pending.size()];
            var i = 0;
            for (var time : pending) {
                times[i++] = time;
            }
            return new State(clock.copy(), times, decoded, lastDts, period);
        }

        /**
         * Gives a picture its decoding time, the presentation time of the place it would have without reordering.
         *
         * @param picture the picture read last, in decoding order
         */
        private void order(Picture picture) {
            var n = decoded++;
            if (picture.pts == Packet.NO_TIMESTAMP) {
                emit(picture, n == 0 ? 0 : Packet.NO_TIMESTAMP);
                return;
            }
            pending.add(picture.pts);
            if (!owner.reordered) {
                emit(picture, pending.poll());
                return;
            }
            if (n == 0) {
                held = picture;
                return;
            }
            var place = pending.poll();
            if (held != null) {
                var layer = clock.layer;
                var step = period > 0 ? period : layer != null && layer.fixedIncrement > 0 ? layer.fixedIncrement
                        : Math.max(1, Math.abs(picture.pts - held.pts));
                period = step;
                emit(held, place - step);
                held = null;
            }
            emit(picture, place);
        }

        /**
         * Queues a packet.
         *
         * @param picture the timed picture the packet holds
         * @param dts its decoding time
         */
        private void emit(Picture picture, long dts) {
            var pts = picture.pts;
            if (pts == Packet.NO_TIMESTAMP) {
                pts = dts;
            } else if (dts > pts) {
                dts = pts;
            }
            if (dts != Packet.NO_TIMESTAMP && lastDts != Long.MIN_VALUE && dts < lastDts) {
                dts = lastDts;
            }
            if (dts != Packet.NO_TIMESTAMP) {
                lastDts = dts;
            }
            var layer = clock.layer;
            var duration = layer != null && layer.fixedIncrement > 0 ? layer.fixedIncrement
                    : Packet.UNKNOWN_DURATION;
            ready.add(new Packet(1, picture.data, pts, dts, duration, picture.key));
        }

        /**
         * Returns whether a packet's picture is intra coded.
         *
         * @param data the packet
         * @return {@code true} for an intra picture
         */
        private boolean isKey(byte[] data) {
            var picture = owner.findPicture(data, 0, data.length);
            return picture >= 0 && owner.isIntra(data, picture);
        }

        /**
         * Returns the presentation time of a packet's picture and advances the clock past it.
         *
         * @param data the packet
         * @return the time, or {@link Packet#NO_TIMESTAMP} when the stream's clock cannot be read
         */
        private long time(byte[] data) {
            long time;
            if (owner.shortHeader) {
                var picture = owner.findPicture(data, 0, data.length);
                var header = picture < 0 ? null : h263Picture(data, picture, data.length - picture,
                        clock.customClock, bits);
                if (header == null) {
                    return Packet.NO_TIMESTAMP;
                }
                clock.customClock = header.customClock;
                var tr = header.temporalReference % header.modulus;
                if (clock.referenceTr < 0) {
                    time = 0;
                    clock.referenceTime = 0;
                    clock.referenceTr = tr;
                } else if (header.bPicture) {
                    time = clock.referenceTime - Math.floorMod(clock.referenceTr - tr, header.modulus);
                } else {
                    time = clock.referenceTime + Math.floorMod(tr - clock.referenceTr, header.modulus);
                    clock.referenceTime = time;
                    clock.referenceTr = tr;
                }
            } else {
                var layer = Layer.find(data, 0, data.length);
                if (layer != null) {
                    clock.layer = layer;
                }
                var timeCode = groupTimeCode(data);
                if (timeCode >= 0) {
                    clock.referenceBase = timeCode;
                }
                var picture = owner.findPicture(data, 0, data.length);
                if (clock.layer == null || picture < 0) {
                    return Packet.NO_TIMESTAMP;
                }
                bits.reset(data, picture + 4, data.length - picture - 4);
                var type = bits.read(2);
                var modulo = 0;
                while (modulo < MAX_MODULO && bits.readFlag()) {
                    modulo++;
                }
                bits.skip(1);
                var increment = bits.read(clock.layer.incrementBits);
                if (bits.overrun()) {
                    return Packet.NO_TIMESTAMP;
                }
                long base;
                if (type == B_VOP) {
                    base = clock.previousBase + modulo;
                } else {
                    base = clock.referenceBase + modulo;
                    clock.previousBase = clock.referenceBase;
                    clock.referenceBase = base;
                }
                time = base * clock.layer.resolution + increment;
            }
            if (clock.origin == Long.MIN_VALUE) {
                clock.origin = time;
            }
            return time - clock.origin;
        }

        /**
         * Returns the time code of the group of VOP header in a packet (ISO/IEC 14496-2 clause 6.2.4).
         *
         * @param data the packet
         * @return the time code in seconds, or {@code -1} when the packet has no group of VOP header
         */
        private long groupTimeCode(byte[] data) {
            for (var i = 0; i + 7 < data.length; i++) {
                if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1) {
                    var code = data[i + 3] & 0xFF;
                    if (code == 0xB6) {
                        return -1;
                    }
                    if (code == 0xB3) {
                        bits.reset(data, i + 4, data.length - i - 4);
                        var hours = bits.read(5);
                        var minutes = bits.read(6);
                        bits.skip(1);
                        var seconds = bits.read(6);
                        return bits.overrun() ? -1 : hours * 3600L + minutes * 60L + seconds;
                    }
                }
            }
            return -1;
        }

        /**
         * Splits the next picture off the stream.
         *
         * @return the packet bytes, or {@code null} at the end of the stream
         */
        private byte[] nextPicture() {
            while (true) {
                var picture = findPicture(start);
                var next = picture < 0 ? -1 : findBoundary(picture + 3);
                if (next >= 0 || exhausted) {
                    if (start >= end) {
                        return null;
                    }
                    var stop = next >= 0 ? next : end;
                    var data = Arrays.copyOfRange(buffer, start, stop);
                    start = stop;
                    return data;
                }
                LimitChecks.checkAllocation(owner.limits, end - start + (long) CHUNK);
                fill();
            }
        }

        /**
         * Finds the start code of the next picture in the buffer.
         *
         * @param from the index to search from
         * @return the index of the start code, or {@code -1}
         */
        int findPicture(int from) {
            return owner.findPicture(buffer, from, end);
        }

        /**
         * Finds where the packet of the picture before a position ends: the next start code of any
         * kind for MPEG-4, the next picture start code for H.263.
         *
         * @param from the index to search from
         * @return the index, or {@code -1} when it is not in the buffer yet
         */
        private int findBoundary(int from) {
            for (var i = from; i + 3 < end; i++) {
                if (buffer[i] == 0 && buffer[i + 1] == 0) {
                    if (owner.shortHeader ? (buffer[i + 2] & 0xFC) == 0x80 : buffer[i + 2] == 1) {
                        return i;
                    }
                }
            }
            return -1;
        }

        /**
         * Reads the next chunk of the source, compacting the buffer.
         */
        void fill() {
            if (exhausted) {
                return;
            }
            if (start > 0) {
                System.arraycopy(buffer, start, buffer, 0, end - start);
                end -= start;
                start = 0;
            }
            if (buffer.length - end < CHUNK) {
                buffer = Arrays.copyOf(buffer, Math.max(buffer.length * 2, end + CHUNK));
            }
            var read = owner.source.read(position, buffer, end, CHUNK);
            if (read <= 0) {
                exhausted = true;
                return;
            }
            end += read;
            position += read;
        }
    }

    /**
     * Finds the start code of the next picture in a range.
     *
     * @param data the bytes
     * @param from the index to search from
     * @param to the index after the last byte
     * @return the index of the start code, or {@code -1}
     */
    private int findPicture(byte[] data, int from, int to) {
        for (var i = from; i + 3 < to; i++) {
            if (data[i] == 0 && data[i + 1] == 0) {
                var picture = shortHeader ? (data[i + 2] & 0xFC) == 0x80
                        : data[i + 2] == 1 && (data[i + 3] & 0xFF) == 0xB6;
                if (picture) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * Returns whether a picture is intra coded.
     *
     * @param data the packet
     * @param offset the index of the picture's start code
     * @return {@code true} for an intra picture
     */
    private boolean isIntra(byte[] data, int offset) {
        if (offset < 0 || offset + 8 > data.length) {
            return false;
        }
        if (!shortHeader && data[offset + 2] == 1) {
            return (data[offset + 4] & 0xC0) == 0;
        }
        var bits = 0L;
        for (var i = 0; i < 8; i++) {
            bits = bits << 8 | (data[offset + i] & 0xFF);
        }
        var ptype = Long.SIZE - PSC_BITS - TR_BITS;
        var format = (int) (bits >>> (ptype - FORMAT_END)) & 7;
        if (format != EXTENDED_FORMAT) {
            return ((bits >>> (ptype - FORMAT_END - 1)) & 1) == 0;
        }
        var plusptype = ptype - PTYPE_BITS;
        var ufep = (int) (bits >>> (plusptype - UFEP_BITS)) & 7;
        var typeShift = plusptype - UFEP_BITS - (ufep == 1 ? OPPTYPE_BITS : 0) - TYPE_BITS;
        return ((bits >>> typeShift) & 7) == 0;
    }
}
