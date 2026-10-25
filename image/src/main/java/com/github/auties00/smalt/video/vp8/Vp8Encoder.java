package com.github.auties00.smalt.video.vp8;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.ops.YuvConverter;
import com.github.auties00.smalt.internal.codec.RealtimeEncoderClock;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketFlag;
import com.github.auties00.smalt.track.PacketSideData;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.internal.codec.FrameClock;
import com.github.auties00.smalt.video.VideoEncoder;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.vp8.internal.GoodEncoder;
import com.github.auties00.smalt.video.vp8.internal.RtEncoder;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Encodes pictures into a VP8 stream (RFC 6386), in real time at a constant bitrate for video
 * calls and live streaming, or at good quality with a lookahead for files and on-demand video.
 *
 * <p><b>Real time</b> ({@link Vp8EncoderUsage#REALTIME}, {@link Vp8EncoderOptions#defaults()},
 * {@link Vp8EncoderOptions#forRealtime()}): the stream holds key frames and inter frames that
 * predict, with motion compensation, from the last frame and from the golden and alternate
 * reference frames; it has no frame reordering, so every packet's presentation and decoding
 * timestamps are equal and each input frame produces its packet, or is dropped, before
 * {@link #encode(VideoFrame, Consumer)} returns. Each packet holds one frame, as stored in IVF and
 * WebM. The first frame, the next frame after a {@link #requestKeyframe()} and every
 * {@code keyframeInterval}-th frame are key frames. {@link #reconfigure(Vp8EncoderOptions)}
 * changes the bitrate, frame rate, quantizers, key frame interval, speed and buffer of a running
 * stream, and {@link #setTargetBitrate(long)} its bitrate alone; the rate control follows a new
 * bitrate within about a second.
 *
 * <p><b>Good quality</b> ({@link Vp8EncoderUsage#GOOD_QUALITY}, {@link Vp8EncoderOptions#ofBitrate(long)},
 * {@link Vp8EncoderOptions#ofQuality(int)}, {@link Vp8EncoderOptions#forArchive()}): the encoder
 * buffers {@link Vp8EncoderOptions#lookahead()} pictures, plans golden frame groups from their
 * first-pass statistics or from those of a whole clip ({@link Vp8FirstPass}), and codes each
 * group after a hidden alternate reference made by temporally filtering the picture that ends it.
 * Packets arrive in decoding order: each shown frame in a packet with its own timestamp and
 * duration, each hidden frame in a packet of its own just before the shown frame after it,
 * flagged {@link PacketFlag#DISCARD}, with that frame's timestamp and no duration. The last
 * packets arrive at {@link #flush}; only {@link #encode} codes in this usage. Key frames come
 * also at scene cuts. The best-quality usage ({@link Vp8EncoderUsage#BEST_QUALITY}) codes the same
 * way with a fuller search; what this class says of the good-quality usage applies to it too.
 *
 * <p>Frames may be given in any {@link Picture} representation of exactly the encoder's size.
 * VP8 defines one color space, limited-range BT.601 4:2:0: a picture in it (or in limited-range
 * 4:2:0 with an unspecified matrix) is coded as it is, and a picture in any other representation,
 * chroma format, matrix or range is converted to it first. Timestamps are in {@link #timeBase()} units and must
 * increase; a frame whose timestamp is {@link Packet#NO_TIMESTAMP} is placed one nominal frame
 * after the previous one, and a frame without a duration lasts one nominal frame. The rate
 * control measures the frame rate from the timestamps.
 *
 * <p>With {@link Vp8EncoderOptions#threads()} 0 or above 1, the macroblock rows of a frame are
 * coded on threads of the library's shared pool together with the calling thread (see
 * {@link com.github.auties00.smalt.MediaRuntime MediaRuntime}); the encoder starts no threads of
 * its own. The stream depends only on the picture size, the options and the input, never on the
 * threads.
 *
 * <p>Instances are not thread-safe, except {@link #requestKeyframe()}, which may be called from
 * any thread at any time.
 */
public final class Vp8Encoder implements VideoEncoder {
    /**
     * The unit of the internal timestamps.
     */
    private static final Rational TICKS = new Rational(1, RtEncoder.TICKS_PER_SECOND);

    /**
     * The largest picture side the VP8 frame header can carry.
     */
    private static final int MAX_DIMENSION = 16_383;

    /**
     * The flags of a packet holding a hidden frame.
     */
    private static final Set<PacketFlag> HIDDEN = Set.of(PacketFlag.DISCARD);

    private Vp8EncoderOptions options;

    private final int width;

    private final int height;

    /**
     * The real-time frame encoder, or {@code null} in the good-quality usage.
     */
    private final RtEncoder encoder;

    /**
     * The good-quality loop, or {@code null} in the real-time usage.
     */
    private final GoodEncoder good;

    /**
     * The total size of the hidden frames coded, in bytes.
     */
    private long hiddenBytes;

    /**
     * The timestamps, key frame requests and counters of the stream.
     */
    private final RealtimeEncoderClock clock;

    /**
     * The operation the calls of this encoder form, whose computing time decides how long a call
     * keeps its carrier thread.
     */
    private final ParallelBands.Operation operation = new ParallelBands.Operation();

    /**
     * The converter of the pictures VP8 does not define, owning their converted copies.
     */
    private final YuvConverter converter = YuvConverter.create(ChromaFormat.YUV420, ColorInfo.BT601_LIMITED);

    private boolean closed;

    /**
     * Constructs an encoder for a picture size with the {@link Vp8EncoderOptions#defaults()}.
     *
     * @param width the picture width in pixels, from 1 to 16383
     * @param height the picture height in pixels, from 1 to 16383
     * @throws IllegalArgumentException if a dimension is outside 1 to 16383, the largest the frame
     * header can carry
     * @throws MediaException.LimitExceeded if the picture exceeds the default limits
     */
    public Vp8Encoder(int width, int height) {
        this(width, height, Vp8EncoderOptions.defaults());
    }

    /**
     * Constructs an encoder for a picture size.
     *
     * @param width the picture width in pixels, from 1 to 16383
     * @param height the picture height in pixels, from 1 to 16383
     * @param options the encoder settings
     * @throws IllegalArgumentException if a dimension is outside 1 to 16383, the largest the
     * frame header can carry
     * @throws IllegalArgumentException if the first-pass statistics were measured on pictures of
     * another size
     * @throws MediaException.LimitExceeded if the picture or the encoder's frame buffers exceed
     * the options' limits
     */
    public Vp8Encoder(int width, int height, Vp8EncoderOptions options) {
        this.options = Objects.requireNonNull(options, "options");
        if (width < 1 || height < 1 || width > MAX_DIMENSION || height > MAX_DIMENSION) {
            throw new IllegalArgumentException("invalid picture size " + width + "x" + height);
        }
        var stats = options.firstPassStats();
        if (stats.isPresent() && (stats.get().width() != width || stats.get().height() != height)) {
            throw new IllegalArgumentException("the first-pass statistics were measured on " + stats.get().width()
                    + "x" + stats.get().height() + " pictures, not " + width + "x" + height);
        }
        LimitChecks.checkPicture(options.limits(), width, height);
        this.width = width;
        this.height = height;
        this.clock = new RealtimeEncoderClock(options.frameRate(), options.timeBase());
        if (options.usage() != Vp8EncoderUsage.REALTIME) {
            this.encoder = null;
            this.good = new GoodEncoder(width, height, options);
            good.configure(settings(options));
        } else {
            this.good = null;
            this.encoder = new RtEncoder(width, height, options);
            encoder.configure(options);
        }
    }

    /**
     * Returns the settings of the good-quality loop.
     *
     * @param options the encoder settings
     * @return the loop's settings
     */
    private static GoodEncoder.Settings settings(Vp8EncoderOptions options) {
        var mode = switch (options.rateControl()) {
            case CONSTRAINED_QUALITY -> 1;
            case CONSTANT_QUALITY -> 2;
            default -> 0;
        };
        var stats = options.firstPassStats();
        return new GoodEncoder.Settings(mode, options.bitrate(), options.frameRate().toDouble(),
                GoodEncoder.qindexOf(options.minQuantizer()), GoodEncoder.qindexOf(options.maxQuantizer()),
                GoodEncoder.qindexOf(options.cqLevel()), options.keyframeInterval().orElse(0), options.lookahead(),
                options.alternateReferences(), options.temporalFilterStrength(), options.temporalFilterFrames(),
                options.speed().level(), options.usage() == Vp8EncoderUsage.BEST_QUALITY, stats.map(Vp8FirstPassStats::values).orElse(null),
                stats.map(Vp8FirstPassStats::frameCount).orElse(0));
    }

    /**
     * {@return the options in force, those of the last {@link #reconfigure} or else of the
     * constructor}
     */
    public Vp8EncoderOptions options() {
        return options;
    }

    /**
     * {@return the real-time encoding engine, for tests in this package, or {@code null} in the
     * good-quality usage}
     */
    RtEncoder engine() {
        return encoder;
    }

    @Override
    public Codec codec() {
        return Codec.VP8;
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }

    @Override
    public Rational timeBase() {
        return options.timeBase();
    }

    /**
     * {@inheritDoc}
     *
     * @return empty: VP8 needs no out-of-band configuration
     */
    @Override
    public Optional<byte[]> codecConfig() {
        return Optional.empty();
    }

    /**
     * {@inheritDoc}
     *
     * <p>In the real-time usage at most one packet is emitted per frame, before this method
     * returns; none when the rate control drops the frame. In the good-quality usage the picture
     * enters the lookahead and the packets of the frames it lets out are passed on.
     *
     * @throws IllegalArgumentException if the picture size differs from the encoder's or the
     * timestamp does not increase
     * @throws IllegalStateException if the encoder is closed, or a two-pass encode receives more
     * pictures than its first pass measured
     */
    @Override
    public void encode(VideoFrame frame, Consumer<Packet> output) {
        Objects.requireNonNull(output, "output");
        if (good == null) {
            encodeFrame(frame).ifPresent(output);
            return;
        }
        Objects.requireNonNull(frame, "frame");
        try (var _ = ParallelBands.operation(operation)) {
            if (closed) {
                throw new IllegalStateException("encoder is closed");
            }
            var picture = frame.picture();
            if (picture.width() != width || picture.height() != height) {
                throw new IllegalArgumentException("picture is " + picture.width() + "x" + picture.height()
                        + ", encoder expects " + width + "x" + height);
            }
            var frames = clock.clock();
            var timing = frames.resolve(frame);
            var ticks = frames.endTicks(timing, TICKS) - frames.startTicks(timing, TICKS);
            var key = clock.takeKeyframe(false);
            good.encode(toYuv420(picture), timing.pts(), timing.duration(), ticks, key,
                    packet -> output.accept(packet(packet)));
            frames.commit(timing.pts());
        }
    }

    /**
     * Counts a good-quality packet and wraps it in a packet.
     *
     * @param packet the loop's packet
     * @return the packet
     */
    private Packet packet(GoodEncoder.Packet packet) {
        if (packet.hidden()) {
            hiddenBytes += packet.data().length;
            return new Packet(0, packet.data(), packet.pts(), packet.pts(), 0, HIDDEN, PacketSideData.NONE);
        }
        return clock.delivered(packet.data(), new FrameClock.Timing(packet.pts(), packet.duration()), packet.key());
    }

    /**
     * Encodes one frame and returns its packet.
     *
     * <p>The frame is a key frame when it is the first, when the interval of
     * {@link Vp8EncoderOptions#keyframeInterval()} is due, or when {@link #requestKeyframe()} was
     * called since the previous frame took a request. A frame whose encoding fails leaves the
     * timestamps as they were.
     *
     * @param frame the next frame in presentation order
     * @return the packet, or empty when the rate control dropped the frame because its buffer is
     * empty
     * @throws IllegalArgumentException if the picture size differs from the encoder's or the
     * timestamp does not increase
     * @throws IllegalStateException if the encoder is closed or codes in the good-quality usage,
     * whose lookahead holds pictures back; use {@link #encode(VideoFrame, Consumer)} there
     */
    public Optional<Packet> encodeFrame(VideoFrame frame) {
        Objects.requireNonNull(frame, "frame");
        try (var _ = ParallelBands.operation(operation)) {
            if (closed) {
                throw new IllegalStateException("encoder is closed");
            }
            if (good != null) {
                throw new IllegalStateException("a good-quality vp8 encoder holds frames back; use encode(frame, "
                        + "output) and flush(output)");
            }
            var picture = frame.picture();
            if (picture.width() != width || picture.height() != height) {
                throw new IllegalArgumentException("picture is " + picture.width() + "x" + picture.height()
                        + ", encoder expects " + width + "x" + height);
            }
            var frames = clock.clock();
            var timing = frames.resolve(frame);
            var data = encoder.encode(toYuv420(picture), frames.startTicks(timing, TICKS),
                    frames.endTicks(timing, TICKS), clock.takeKeyframe(false));
            if (data == null) {
                clock.dropped(timing);
                return Optional.empty();
            }
            return Optional.of(clock.coded(data, timing, encoder.lastKeyFrame()));
        }
    }

    /**
     * Returns a picture in the color space VP8 defines, limited-range BT.601 4:2:0.
     *
     * @param picture the picture to code
     * @return {@code picture} itself when it is in that representation already, otherwise a
     * converted copy, valid until the next call
     */
    private YuvPicture toYuv420(Picture picture) {
        if (picture instanceof YuvPicture yuv && yuv.chromaFormat() == ChromaFormat.YUV420
                && yuv.color().range() == ColorRange.LIMITED && codesAsBt601(yuv.color().matrix())) {
            return yuv;
        }
        return converter.convert(picture);
    }

    /**
     * Returns whether samples of a matrix are coded unchanged as BT.601.
     *
     * @param matrix the matrix coefficients of a picture
     * @return {@code true} for BT.601, BT.470 System B and G (the same matrix) and unspecified
     */
    private static boolean codesAsBt601(MatrixCoefficients matrix) {
        var resolved = matrix.effective();
        return resolved == MatrixCoefficients.BT601 || resolved == MatrixCoefficients.BT470BG;
    }

    /**
     * {@inheritDoc}
     *
     * <p>In the real-time usage the encoder has no delay, so this emits nothing. In the
     * good-quality usage the pictures of the lookahead are coded and their packets passed on; the
     * encoder stays usable, later pictures continuing the stream.
     *
     * @throws IllegalStateException if the encoder is closed
     */
    @Override
    public void flush(Consumer<Packet> output) {
        Objects.requireNonNull(output, "output");
        if (closed) {
            throw new IllegalStateException("encoder is closed");
        }
        if (good == null) {
            return;
        }
        try (var _ = ParallelBands.operation(operation)) {
            good.flush(packet -> output.accept(packet(packet)));
        }
    }

    /**
     * Makes the next encoded frame a key frame, so a receiver that lost frames can resume
     * decoding from it.
     *
     * <p>This method may be called from any thread, also while another thread encodes a frame:
     * the request is then taken by that frame or by the next one, and is never lost. In the
     * good-quality usage, the next picture passed to {@link #encode} becomes a key frame.
     */
    @Override
    public void requestKeyframe() {
        clock.requestKeyframe();
    }

    /**
     * Changes the rate control of a running stream; the next frame uses it.
     *
     * <p>In the real-time usage the bitrate, frame rate, quantizer window, key frame interval,
     * speed, frame dropping, buffer, encode breakout and rate tolerances may change; the buffer
     * keeps its fullness in milliseconds, so the rate follows a new bitrate within about a second.
     * In the good-quality usage the bitrate, quantizer window and quality level may change.
     *
     * @param options the new options
     * @throws IllegalArgumentException if the new options change the usage, the time base, the
     * temporal layers, the token partitions, the threads, error resilience or the limits, or, in
     * the good-quality usage, anything but the bitrate, the quantizer window and the quality level
     * @throws IllegalStateException if the encoder is closed
     */
    public void reconfigure(Vp8EncoderOptions options) {
        Objects.requireNonNull(options, "options");
        if (closed) {
            throw new IllegalStateException("encoder is closed");
        }
        var current = this.options;
        if (options.usage() != current.usage() || !options.timeBase().equals(current.timeBase())
                || options.temporalLayers() != current.temporalLayers()
                || options.tokenPartitions() != current.tokenPartitions() || options.threads() != current.threads()
                || options.errorResilient() != current.errorResilient() || !options.limits().equals(current.limits())) {
            throw new IllegalArgumentException("the usage, time base, temporal layers, token partitions, threads, "
                    + "error resilience and limits of a running vp8 encoder cannot change");
        }
        if (good != null) {
            var retargeted = options.toBuilder().bitrate(current.bitrate())
                    .quantizers(current.minQuantizer(), current.maxQuantizer()).cqLevel(current.cqLevel()).build();
            if (!retargeted.equals(current)) {
                throw new IllegalArgumentException("a running good-quality vp8 encoder changes only its bitrate, "
                        + "quantizer window and quality level");
            }
            this.options = options;
            good.retarget(options.bitrate(), GoodEncoder.qindexOf(options.minQuantizer()),
                    GoodEncoder.qindexOf(options.maxQuantizer()), GoodEncoder.qindexOf(options.cqLevel()));
            return;
        }
        this.options = options;
        clock.clock().setFrameRate(options.frameRate());
        encoder.configure(options);
    }

    /**
     * Changes the target bitrate from the next frame on, for example when a congestion
     * controller reports a new estimate; the buffer keeps its fullness in milliseconds.
     *
     * @param bitrate the bitrate in bits per second
     * @throws IllegalArgumentException if {@code bitrate} is not positive or exceeds
     * {@link Integer#MAX_VALUE}
     * @throws IllegalStateException if the encoder is closed
     */
    @Override
    public void setTargetBitrate(long bitrate) {
        reconfigure(options.toBuilder().bitrate(bitrate).build());
    }

    /**
     * {@return a snapshot of the encoder's counters and rate control state}
     */
    public Vp8EncoderStats stats() {
        if (good != null) {
            return new Vp8EncoderStats(clock.framesEncoded(), clock.keyFramesEncoded(), 0,
                    clock.bytesEncoded() + hiddenBytes, good.lastQindex(), 0, 0);
        }
        return new Vp8EncoderStats(clock.framesEncoded(), clock.keyFramesEncoded(), clock.framesDropped(),
                clock.bytesEncoded(), encoder.lastQuantizer(), encoder.lastLayer(), encoder.bufferLevel());
    }

    /**
     * {@inheritDoc}
     *
     * <p>The encoder cannot be used afterwards. It holds no threads between calls, so closing it
     * releases no thread.
     */
    @Override
    public void close() {
        closed = true;
    }
}
