/* Ports libvpx 1.17.0 (vp9/vp9_cx_iface.c, vp9/encoder/vp9_encoder.c). Copyright (c) 2010, The WebM Project
   authors; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.video.vp9;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaRuntime;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.internal.codec.AlphaStreamEncoder;
import com.github.auties00.smalt.internal.codec.FrameClock;
import com.github.auties00.smalt.internal.codec.RealtimeEncoderClock;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.VideoEncoder;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.vp9.internal.Vp9GoodEncoder;
import com.github.auties00.smalt.video.vp9.internal.Vp9RtEncoder;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Encodes pictures to a VP9 stream, in real time at a constant bitrate for video calls and other
 * low-delay uses, or at good quality with a lookahead for files and on-demand video.
 *
 * <p><b>Real time</b> ({@link Vp9EncoderUsage#REALTIME}, {@link Vp9EncoderOptions#forRealtime()}):
 * the encoder codes without frame reordering: every picture yields at most one packet,
 * immediately, holding one frame in the format a VP9 decoder or the VP9 RTP payload format
 * expects. The rate control may drop a frame when its buffer is empty and dropping is enabled;
 * {@link #encodeFrame} then returns an empty result and {@link #encode} produces no packet.
 *
 * <p><b>Good quality</b> ({@link Vp9EncoderUsage#GOOD_QUALITY}, {@link Vp9EncoderOptions#ofBitrate(long)},
 * {@link Vp9EncoderOptions#ofQuality(int)}, {@link Vp9EncoderOptions#forArchive()}): the encoder
 * buffers {@link Vp9EncoderOptions#lookahead()} pictures, plans golden frame groups from their
 * first-pass statistics or from those of a whole clip ({@link Vp9FirstPass}), and codes each group
 * after a hidden alternate reference made by temporally filtering the picture that ends it. Packets
 * arrive in display order, each showing one frame and carrying the hidden frames coded before it
 * in a superframe; the last ones arrive at {@link #flush}. Only {@link #encode} codes in this
 * usage. The best-quality usage ({@link Vp9EncoderUsage#BEST_QUALITY}) codes the same way with a
 * fuller search; what this class says of the good-quality usage applies to it too.
 *
 * <p>The stream codes the subsampling and bit depth of {@link Vp9EncoderOptions#subsampling()} and
 * {@link Vp9EncoderOptions#bitDepth()} (profiles 0 to 3) and signals the matrix and range of
 * {@link Vp9EncoderOptions#color()}; a picture in another chroma format, matrix, range or bit
 * depth is converted first. The first frame is a key frame, as are frames following a call to
 * {@link #requestKeyframe()} and, when configured, periodic ones; good-quality coding adds key
 * frames at scene cuts. {@link #reconfigure(Vp9EncoderOptions)} changes the rate control of a
 * running stream, and {@link #setTargetBitrate(long)} its bitrate alone.
 *
 * <p>With {@link Vp9EncoderOptions#alpha()}, the alpha plane of each picture is coded as a second
 * 8-bit 4:2:0 VP9 stream, at the constant quantizer of {@link Vp9EncoderOptions#alphaQuality()}, and
 * each packet carries its alpha frame in
 * {@link com.github.auties00.smalt.track.PacketSideData#alphaFrame()}, as WebM stores transparent
 * video; the alpha stream has a key frame wherever the main stream has one.
 *
 * <p>Packet timestamps are the frames' timestamps in the time base of the options; a frame without
 * a timestamp follows the previous one by the nominal frame duration. The stream does not depend
 * on {@link Vp9EncoderOptions#threads()}. Instances are not thread-safe, except
 * {@link #requestKeyframe()}, which any thread may call at any time.
 *
 * @implNote This implementation codes real-time frames with the variance-based partitioning, the
 * non-RD mode decision, the cyclic refresh and the one-pass CBR rate control of libvpx's real-time
 * speeds 5 to 9, and good-quality frames with the rate-distortion partition and mode search, the
 * two-pass rate control and the temporal filter of libvpx's good-quality speeds 0 to 4 and of its
 * best-quality mode.
 * Superblock rows run in parallel on the module's pool with per-row adaptive state, which keeps
 * the stream independent of the thread count.
 */
public final class Vp9Encoder implements VideoEncoder {
    /**
     * The time base of the rate control, 100 nanoseconds.
     */
    private static final Rational TICKS = new Rational(1, Vp9RtEncoder.TICKS_PER_SECOND);

    /**
     * The largest picture side.
     */
    private static final int MAX_DIMENSION = 16_384;

    /**
     * The most threads an automatic thread count ({@link Vp9EncoderOptions#threads()} 0) takes.
     */
    private static final int MAX_AUTOMATIC_THREADS = 64;

    private Vp9EncoderOptions options;

    private final int width;

    private final int height;

    /**
     * The real-time frame encoder, or {@code null} in the good-quality usage.
     */
    private final Vp9RtEncoder encoder;

    /**
     * The good-quality loop, or {@code null} in the real-time usage.
     */
    private final Vp9GoodEncoder good;

    /**
     * The timestamps, key frame requests and counters of the stream.
     */
    private final RealtimeEncoderClock state;

    /**
     * The pool scope of the encoder's calls.
     */
    private final ParallelBands.Operation operation = new ParallelBands.Operation();

    /**
     * The conversions of the pictures to the coded representation.
     */
    private final Vp9Pictures pictures;

    /**
     * The coder of the alpha planes, or {@code null} without {@link Vp9EncoderOptions#alpha()}.
     */
    private final AlphaStreamEncoder alpha;

    private boolean closed;

    /**
     * Constructs an encoder for a picture size.
     *
     * @param width the picture width in pixels, from 1 to 16384
     * @param height the picture height in pixels, from 1 to 16384
     * @param options the encoder settings
     * @throws IllegalArgumentException if a dimension is outside 1 to 16384, or if the first-pass
     * statistics were measured on another picture size
     * @throws MediaException.LimitExceeded if the picture exceeds the options' limits
     */
    public Vp9Encoder(int width, int height, Vp9EncoderOptions options) {
        this.options = Objects.requireNonNull(options, "options");
        if (width < 1 || height < 1 || width > MAX_DIMENSION || height > MAX_DIMENSION) {
            throw new IllegalArgumentException("invalid picture size " + width + "x" + height);
        }
        var stats = options.firstPassStats();
        if (stats.isPresent() && (stats.get().width() != width || stats.get().height() != height)) {
            throw new IllegalArgumentException("the first-pass statistics were measured on " + stats.get().width()
                    + "x" + stats.get().height() + " pictures, not " + width + "x" + height);
        }
        this.width = width;
        this.height = height;
        var threads = threads(options);
        var subsampling = options.subsampling();
        var colorSpace = Vp9EncoderOptions.colorSpaceOf(options.color().matrix());
        var fullRange = options.color().range() == ColorRange.FULL;
        if (options.usage() != Vp9EncoderUsage.REALTIME) {
            this.encoder = null;
            this.good = new Vp9GoodEncoder(width, height, subsampling.shiftX(), subsampling.shiftY(),
                    options.bitDepth(), options.tileColumnsLog2(), threads, options.limits());
            good.setColor(colorSpace, fullRange);
            good.configure(settings(options));
        } else {
            this.good = null;
            this.encoder = new Vp9RtEncoder(width, height, subsampling.shiftX(), subsampling.shiftY(),
                    options.bitDepth(), options.temporalLayers(), options.tileColumnsLog2(), threads,
                    options.limits());
            encoder.setColor(colorSpace, fullRange);
            encoder.configure(options);
        }
        this.state = new RealtimeEncoderClock(options.frameRate(), options.timeBase());
        this.pictures = new Vp9Pictures(options);
        this.alpha = options.alpha()
                ? new AlphaStreamEncoder(new Vp9Encoder(width, height, alphaOptions(options)), ColorInfo.BT601_LIMITED)
                : null;
    }

    /**
     * Returns the settings of the alpha stream of an encoder: its usage, speed, time base, tile columns,
     * threads and limits, 8-bit limited-range BT.601 4:2:0, a fixed quantizer, no lookahead, no hidden
     * frames, no dropped frames and no periodic key frames, so that every picture gives one shown frame at
     * once.
     *
     * @param options the settings of the main stream
     * @return the settings of the alpha stream
     */
    private static Vp9EncoderOptions alphaOptions(Vp9EncoderOptions options) {
        var quantizer = options.alphaQuality();
        var builder = options.toBuilder().alpha(false).noFirstPassStats().noPeriodicKeyframes()
                .quantizers(quantizer, quantizer).cqLevel(quantizer)
                .pictureFormat(Vp9Subsampling.YUV420, 8).color(ColorInfo.BT601_LIMITED);
        if (options.usage() == Vp9EncoderUsage.REALTIME) {
            builder.dropFrames(false).temporalLayers(1).adaptiveQuantization(false);
        } else {
            builder.rateControl(Vp9RateControl.CONSTANT_QUALITY).lookahead(0).alternateReferences(false);
        }
        return builder.build();
    }

    /**
     * Constructs an encoder for a picture size with the {@link Vp9EncoderOptions#defaults()}.
     *
     * @param width the picture width in pixels, from 1 to 16384
     * @param height the picture height in pixels, from 1 to 16384
     * @throws IllegalArgumentException if a dimension is outside 1 to 16384
     * @throws MediaException.LimitExceeded if the picture exceeds the default limits
     */
    public Vp9Encoder(int width, int height) {
        this(width, height, Vp9EncoderOptions.defaults());
    }

    /**
     * Returns the number of threads the options ask for, with the automatic count resolved.
     *
     * @param options the encoder settings
     * @return the thread count, at least 1
     */
    private static int threads(Vp9EncoderOptions options) {
        return options.threads() == 0 ? Math.min(MAX_AUTOMATIC_THREADS, MediaRuntime.parallelism()) : options.threads();
    }

    /**
     * Returns the settings of the good-quality loop.
     *
     * @param options the encoder settings
     * @return the loop's settings
     */
    private static Vp9GoodEncoder.Settings settings(Vp9EncoderOptions options) {
        var mode = switch (options.rateControl()) {
            case CONSTRAINED_QUALITY -> 1;
            case CONSTANT_QUALITY -> 2;
            default -> 0;
        };
        var stats = options.firstPassStats();
        return new Vp9GoodEncoder.Settings(mode, options.bitrate(), options.frameRate().toDouble(),
                Vp9GoodEncoder.qindexOf(options.minQuantizer()), Vp9GoodEncoder.qindexOf(options.maxQuantizer()),
                Vp9GoodEncoder.qindexOf(options.cqLevel()), options.keyframeInterval().orElse(0),
                options.lookahead(), options.alternateReferences(), options.temporalFilterStrength(),
                options.temporalFilterFrames(), options.speed().level(), options.usage() == Vp9EncoderUsage.BEST_QUALITY,
                stats.map(Vp9FirstPassStats::values).orElse(null), stats.map(Vp9FirstPassStats::frameCount).orElse(0));
    }

    /**
     * {@return the current options: those of the constructor or of the last
     * {@link #reconfigure(Vp9EncoderOptions)}}
     */
    public Vp9EncoderOptions options() {
        return options;
    }

    /**
     * {@return the real-time frame encoder, or {@code null} in the good-quality and best-quality
     * usages, for the module's tests}
     */
    Vp9RtEncoder engine() {
        return encoder;
    }

    @Override
    public Codec codec() {
        return Codec.VP9;
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
     * <p>A VP9 stream has no codec configuration, so the result is always empty.
     */
    @Override
    public Optional<byte[]> codecConfig() {
        return Optional.empty();
    }

    /**
     * {@inheritDoc}
     * <p>In the real-time usage the packet, if any, is the one {@link #encodeFrame(VideoFrame)}
     * returns. In the good-quality usage the picture enters the lookahead and the packets of the
     * frames it lets out are passed on, in display order.
     *
     * @throws IllegalArgumentException if the picture size differs from the encoder's or the
     * timestamp does not follow the previous one
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
            checkOpen();
            checkSize(frame);
            var clock = state.clock();
            var timing = clock.resolve(frame);
            var ticks = clock.endTicks(timing, TICKS) - clock.startTicks(timing, TICKS);
            var key = state.takeKeyframe(false);
            var picture = frame.picture();
            if (alpha != null) {
                alpha.push(picture, timing.pts(), timing.duration());
            }
            Consumer<Vp9GoodEncoder.Packet> sink = packet -> output.accept(packet(packet));
            if (pictures.high() && pictures.toCodedHigh(picture) instanceof Yuv16Picture high) {
                good.encode(high, timing.pts(), timing.duration(), ticks, key, sink);
            } else {
                good.encode(pictures.toCoded(picture), timing.pts(), timing.duration(), ticks, key, sink);
            }
            clock.commit(timing.pts());
        }
    }

    /**
     * Counts a good-quality packet and wraps it in a packet.
     *
     * @param packet the loop's packet
     * @return the packet
     */
    private Packet packet(Vp9GoodEncoder.Packet packet) {
        var delivered = state.delivered(packet.data(), new FrameClock.Timing(packet.pts(), packet.duration()),
                packet.key());
        return alpha != null ? alpha.attach(delivered) : delivered;
    }

    /**
     * Encodes one picture and returns its packet, in the real-time usage.
     *
     * <p>The frame is a key frame when it is the first, {@link #requestKeyframe()} was called since
     * the previous frame was encoded, or the interval of {@link Vp9EncoderOptions#keyframeInterval()}
     * is due. A frame that fails to encode leaves the timestamps as they were.
     *
     * @param frame the picture and its timestamp
     * @return the packet, or empty when the rate control dropped the frame because its buffer is
     * empty
     * @throws IllegalArgumentException if the picture size differs from the encoder's or the
     * timestamp does not follow the previous one
     * @throws IllegalStateException if the encoder is closed or codes in the good-quality usage,
     * whose lookahead holds pictures back; use {@link #encode(VideoFrame, Consumer)} there
     */
    public Optional<Packet> encodeFrame(VideoFrame frame) {
        Objects.requireNonNull(frame, "frame");
        try (var _ = ParallelBands.operation(operation)) {
            checkOpen();
            if (good != null) {
                throw new IllegalStateException("a good-quality vp9 encoder holds frames back; use encode(frame, "
                        + "output) and flush(output)");
            }
            checkSize(frame);
            var picture = frame.picture();
            var clock = state.clock();
            var timing = clock.resolve(frame);
            var start = clock.startTicks(timing, TICKS);
            var end = clock.endTicks(timing, TICKS);
            var key = state.takeKeyframe(false);
            if (alpha != null) {
                alpha.push(picture, timing.pts(), timing.duration());
            }
            var data = pictures.high() && pictures.toCodedHigh(picture) instanceof Yuv16Picture high
                    ? encoder.encode(high, start, end, key)
                    : encoder.encode(pictures.toCoded(picture), start, end, key);
            if (data == null) {
                state.dropped(timing);
                return Optional.empty();
            }
            var coded = state.coded(data, timing, encoder.lastKeyFrame());
            return Optional.of(alpha != null ? alpha.attach(coded) : coded);
        }
    }

    /**
     * Checks that the encoder is open.
     *
     * @throws IllegalStateException if the encoder is closed
     */
    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("encoder is closed");
        }
    }

    /**
     * Checks that a frame's picture has the encoder's size.
     *
     * @param frame the frame to check
     * @throws IllegalArgumentException if the sizes differ
     */
    private void checkSize(VideoFrame frame) {
        var picture = frame.picture();
        if (picture.width() != width || picture.height() != height) {
            throw new IllegalArgumentException("picture is " + picture.width() + "x" + picture.height()
                    + ", encoder expects " + width + "x" + height);
        }
    }

    /**
     * {@inheritDoc}
     * <p>In the real-time usage the encoder holds no frame back, so nothing is emitted. In the
     * good-quality usage the pictures of the lookahead are coded and their packets passed on; the
     * encoder stays usable, later pictures continuing the stream.
     *
     * @throws IllegalStateException if the encoder is closed
     */
    @Override
    public void flush(Consumer<Packet> output) {
        Objects.requireNonNull(output, "output");
        checkOpen();
        if (good == null) {
            return;
        }
        try (var _ = ParallelBands.operation(operation)) {
            good.flush(packet -> output.accept(packet(packet)));
        }
    }

    /**
     * Makes the next frame encoded after this call a key frame; any thread may call this at any
     * time.
     *
     * <p>A request that races with a frame being encoded is never lost: that frame or the next one
     * is a key frame. In the good-quality usage, the next picture passed to {@link #encode} becomes
     * a key frame.
     */
    @Override
    public void requestKeyframe() {
        state.requestKeyframe();
    }

    /**
     * Changes the rate control of a running encoder; the next frame uses it.
     *
     * <p>In the real-time usage the bitrate, frame rate, quantizer window, key frame interval,
     * speed, adaptive quantization, frame dropping and buffer sizes may change; a new frame rate
     * changes the nominal duration of the frames that follow, the timestamps already assigned
     * stay. In the good-quality usage the bitrate, quantizer window and quality level may change.
     *
     * @param options the new options
     * @throws IllegalArgumentException if a setting the usage cannot change differs from the
     * current options: in both usages the usage, time base, tile columns, threads, picture format,
     * color, limits and alpha stream, in the real-time usage the temporal layers and error resilience, in the
     * good-quality usage everything but the bitrate, the quantizer window and the quality level
     * @throws IllegalStateException if the encoder is closed
     */
    public void reconfigure(Vp9EncoderOptions options) {
        Objects.requireNonNull(options, "options");
        checkOpen();
        var current = this.options;
        if (options.usage() != current.usage() || !options.timeBase().equals(current.timeBase())
                || options.tileColumnsLog2() != current.tileColumnsLog2() || options.threads() != current.threads()
                || options.subsampling() != current.subsampling() || options.bitDepth() != current.bitDepth()
                || !options.color().equals(current.color()) || !options.limits().equals(current.limits())
                || options.alpha() != current.alpha() || options.alphaQuality() != current.alphaQuality()) {
            throw new IllegalArgumentException("the usage, time base, tile columns, threads, picture format, color, "
                    + "limits and alpha stream of a running vp9 encoder cannot change");
        }
        if (good != null) {
            var retargeted = options.toBuilder().bitrate(current.bitrate())
                    .quantizers(current.minQuantizer(), current.maxQuantizer()).cqLevel(current.cqLevel()).build();
            if (!retargeted.equals(current)) {
                throw new IllegalArgumentException("a running good-quality vp9 encoder changes only its bitrate, "
                        + "quantizer window and quality level");
            }
            this.options = options;
            good.retarget(options.bitrate(), Vp9GoodEncoder.qindexOf(options.minQuantizer()),
                    Vp9GoodEncoder.qindexOf(options.maxQuantizer()), Vp9GoodEncoder.qindexOf(options.cqLevel()));
            return;
        }
        if (options.temporalLayers() != current.temporalLayers() || options.errorResilient() != current.errorResilient()) {
            throw new IllegalArgumentException("the temporal layers and error resilience of a running vp9 encoder "
                    + "cannot change");
        }
        this.options = options;
        state.clock().setFrameRate(options.frameRate());
        encoder.configure(options);
    }

    /**
     * Changes the target bitrate from the next frame on, for example when a congestion
     * controller reports a new estimate; in the real-time usage the buffer keeps its fullness in
     * milliseconds.
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
     * {@return a snapshot of the encoder's statistics since it was constructed}
     */
    public Vp9EncoderStats stats() {
        if (good != null) {
            return new Vp9EncoderStats(state.framesEncoded(), state.keyFramesEncoded(), 0, state.bytesEncoded(),
                    good.lastQindex(), 0, 0);
        }
        return new Vp9EncoderStats(state.framesEncoded(), state.keyFramesEncoded(), state.framesDropped(),
                state.bytesEncoded(), encoder.lastQuantizer(), encoder.lastLayer(), encoder.bufferLevel());
    }

    /**
     * {@inheritDoc}
     * <p>Later calls of {@link #encode}, {@link #encodeFrame}, {@link #flush} and
     * {@link #reconfigure} throw {@link IllegalStateException}; closing again has no effect.
     */
    @Override
    public void close() {
        closed = true;
        if (alpha != null) {
            alpha.close();
        }
    }
}
