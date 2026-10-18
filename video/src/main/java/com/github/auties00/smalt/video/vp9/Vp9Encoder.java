/* Ports libvpx 1.17.0 (vp9/vp9_cx_iface.c, vp9/encoder/vp9_encoder.c). Copyright (c) 2010, The WebM Project
   authors; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.video.vp9;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaRuntime;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.ops.YuvConverter;
import com.github.auties00.smalt.internal.codec.RealtimeEncoderClock;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.VideoEncoder;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.vp9.internal.Vp9RtEncoder;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Encodes pictures to a real-time VP9 stream at a constant bitrate, for video calls and other
 * low-delay uses.
 *
 * <p>The encoder codes profile 0 (8-bit 4:2:0) frames without frame reordering: every picture
 * yields at most one packet, immediately, holding one frame in the format a VP9 decoder or the
 * VP9 RTP payload format expects. The stream signals an unknown color space in studio range,
 * which decoders read as BT.601 limited range, so a picture in any other representation, chroma
 * format, matrix or range is converted to BT.601 limited-range 4:2:0 first. The first frame is a
 * key frame, as are frames following a call to {@link #requestKeyframe()} and, when configured,
 * periodic ones. The rate control may drop a frame when its buffer is empty and dropping is
 * enabled; {@link #encodeFrame} then returns an empty result and {@link #encode} produces no
 * packet. {@link #reconfigure(Vp9EncoderOptions)} changes the rate control and coding settings of
 * a running stream, and {@link #setTargetBitrate(long)} its bitrate alone.
 *
 * <p>Packet timestamps are the frames' timestamps in the time base of the options; a frame without
 * a timestamp follows the previous one by the nominal frame duration. The stream does not depend
 * on {@link Vp9EncoderOptions#threads()}. Instances are not thread-safe, except
 * {@link #requestKeyframe()}, which any thread may call at any time.
 *
 * @implNote This implementation codes with the variance-based partitioning, the non-RD mode
 * decision, the cyclic refresh and the one-pass CBR rate control of libvpx's real-time speeds 5 to
 * 9. Superblock rows run in parallel on the module's pool with per-row adaptive state, which keeps
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

    private final Vp9RtEncoder encoder;

    /**
     * The timestamps, key frame requests and counters of the stream.
     */
    private final RealtimeEncoderClock state;

    /**
     * The pool scope of the encoder's calls.
     */
    private final ParallelBands.Operation operation = new ParallelBands.Operation();

    /**
     * The converter of the pictures the stream does not signal, owning their converted copies.
     */
    private final YuvConverter converter = YuvConverter.create(ChromaFormat.YUV420, ColorInfo.BT601_LIMITED);

    private boolean closed;

    /**
     * Constructs an encoder for a picture size.
     *
     * @param width the picture width in pixels, from 1 to 16384
     * @param height the picture height in pixels, from 1 to 16384
     * @param options the encoder settings
     * @throws IllegalArgumentException if a dimension is outside 1 to 16384
     * @throws MediaException.LimitExceeded if the picture exceeds the options' limits
     */
    public Vp9Encoder(int width, int height, Vp9EncoderOptions options) {
        this.options = Objects.requireNonNull(options, "options");
        if (width < 1 || height < 1 || width > MAX_DIMENSION || height > MAX_DIMENSION) {
            throw new IllegalArgumentException("invalid picture size " + width + "x" + height);
        }
        this.width = width;
        this.height = height;
        var threads = options.threads() == 0
                ? Math.min(MAX_AUTOMATIC_THREADS, MediaRuntime.parallelism())
                : options.threads();
        this.encoder = new Vp9RtEncoder(width, height, options.temporalLayers(), options.tileColumnsLog2(), threads,
                options.limits());
        this.state = new RealtimeEncoderClock(options.frameRate(), options.timeBase());
        encoder.configure(options);
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
     * {@return the current options: those of the constructor or of the last
     * {@link #reconfigure(Vp9EncoderOptions)}}
     */
    public Vp9EncoderOptions options() {
        return options;
    }

    /**
     * {@return the frame encoder, for the module's tests}
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
     * <p>The packet, if any, is the one {@link #encodeFrame(VideoFrame)} returns.
     *
     * @throws IllegalArgumentException if the picture size differs from the encoder's or the
     * timestamp does not follow the previous one
     * @throws IllegalStateException if the encoder is closed
     */
    @Override
    public void encode(VideoFrame frame, Consumer<Packet> output) {
        Objects.requireNonNull(output, "output");
        encodeFrame(frame).ifPresent(output);
    }

    /**
     * Encodes one picture and returns its packet.
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
     * @throws IllegalStateException if the encoder is closed
     */
    public Optional<Packet> encodeFrame(VideoFrame frame) {
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
            var clock = state.clock();
            var timing = clock.resolve(frame);
            var data = encoder.encode(toYuv420(picture), clock.startTicks(timing, TICKS),
                    clock.endTicks(timing, TICKS), state.takeKeyframe(false));
            if (data == null) {
                state.dropped(timing);
                return Optional.empty();
            }
            return Optional.of(state.coded(data, timing, encoder.lastKeyFrame()));
        }
    }

    /**
     * Returns a picture in the representation the stream signals, BT.601 limited-range 4:2:0.
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
     * <p>The encoder holds no frame back, so nothing is emitted.
     */
    @Override
    public void flush(Consumer<Packet> output) {
        Objects.requireNonNull(output, "output");
    }

    /**
     * Makes the next frame encoded after this call a key frame; any thread may call this at any
     * time.
     *
     * <p>A request that races with a frame being encoded is never lost: that frame or the next one
     * is a key frame.
     */
    @Override
    public void requestKeyframe() {
        state.requestKeyframe();
    }

    /**
     * Changes the bitrate, frame rate, quantizer window, key frame interval, speed, adaptive
     * quantization, frame dropping and buffer sizes of a running encoder; the next frame uses
     * them.
     *
     * <p>A new frame rate changes the nominal duration of the frames that follow; the timestamps
     * already assigned stay.
     *
     * @param options the new options
     * @throws IllegalArgumentException if the time base, temporal layers, tile columns, threads,
     * error resilience or limits differ from the current options
     * @throws IllegalStateException if the encoder is closed
     */
    public void reconfigure(Vp9EncoderOptions options) {
        Objects.requireNonNull(options, "options");
        if (closed) {
            throw new IllegalStateException("encoder is closed");
        }
        var current = this.options;
        if (!options.timeBase().equals(current.timeBase()) || options.temporalLayers() != current.temporalLayers()
                || options.tileColumnsLog2() != current.tileColumnsLog2() || options.threads() != current.threads()
                || options.errorResilient() != current.errorResilient() || !options.limits().equals(current.limits())) {
            throw new IllegalArgumentException("the time base, temporal layers, tile columns, threads, error "
                    + "resilience and limits of a running vp9 encoder cannot change");
        }
        this.options = options;
        state.clock().setFrameRate(options.frameRate());
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
     * {@return a snapshot of the encoder's statistics since it was constructed}
     */
    public Vp9EncoderStats stats() {
        return new Vp9EncoderStats(state.framesEncoded(), state.keyFramesEncoded(), state.framesDropped(),
                state.bytesEncoded(), encoder.lastQuantizer(), encoder.lastLayer(), encoder.bufferLevel());
    }

    /**
     * {@inheritDoc}
     * <p>Later calls of {@link #encode}, {@link #encodeFrame} and {@link #reconfigure} throw
     * {@link IllegalStateException}; closing again has no effect.
     */
    @Override
    public void close() {
        closed = true;
    }
}
