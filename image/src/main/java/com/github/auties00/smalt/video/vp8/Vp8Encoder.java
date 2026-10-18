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
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.VideoEncoder;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.vp8.internal.RtEncoder;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Encodes pictures into a real-time VP8 stream (RFC 6386) at a constant bitrate, for video calls
 * and live streaming.
 *
 * <p>The stream holds key frames and inter frames that predict, with motion compensation, from
 * the last frame and from the golden and alternate reference frames; it has no frame
 * reordering, so every packet's presentation and decoding timestamps are equal and each input
 * frame produces its packet, or is dropped, before {@link #encode(VideoFrame, Consumer)}
 * returns. Each packet holds one frame, as stored in IVF and WebM. The first frame, the next
 * frame after a {@link #requestKeyframe()} and every {@code keyframeInterval}-th frame are key
 * frames. {@link #reconfigure(Vp8EncoderOptions)} changes the bitrate, frame rate, quantizers,
 * key frame interval, speed and buffer of a running stream, and {@link #setTargetBitrate(long)}
 * its bitrate alone; the rate control follows a new bitrate within about a second.
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

    private Vp8EncoderOptions options;

    private final int width;

    private final int height;

    private final RtEncoder encoder;

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
     * @throws MediaException.LimitExceeded if the picture or the encoder's frame buffers exceed
     * the options' limits
     */
    public Vp8Encoder(int width, int height, Vp8EncoderOptions options) {
        this.options = Objects.requireNonNull(options, "options");
        if (width < 1 || height < 1 || width > MAX_DIMENSION || height > MAX_DIMENSION) {
            throw new IllegalArgumentException("invalid picture size " + width + "x" + height);
        }
        LimitChecks.checkPicture(options.limits(), width, height);
        this.width = width;
        this.height = height;
        this.encoder = new RtEncoder(width, height, options);
        this.clock = new RealtimeEncoderClock(options.frameRate(), options.timeBase());
        encoder.configure(options);
    }

    /**
     * {@return the options in force, those of the last {@link #reconfigure} or else of the
     * constructor}
     */
    public Vp8EncoderOptions options() {
        return options;
    }

    /**
     * {@return the encoding engine, for tests in this package}
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
     * <p>At most one packet is emitted per frame, before this method returns; none when the rate
     * control drops the frame.
     *
     * @throws IllegalArgumentException if the picture size differs from the encoder's or the
     * timestamp does not increase
     * @throws IllegalStateException if the encoder is closed
     */
    @Override
    public void encode(VideoFrame frame, Consumer<Packet> output) {
        Objects.requireNonNull(output, "output");
        encodeFrame(frame).ifPresent(output);
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
     * <p>The encoder has no delay, so this emits nothing.
     */
    @Override
    public void flush(Consumer<Packet> output) {
        Objects.requireNonNull(output, "output");
    }

    /**
     * Makes the next encoded frame a key frame, so a receiver that lost frames can resume
     * decoding from it.
     *
     * <p>This method may be called from any thread, also while another thread encodes a frame:
     * the request is then taken by that frame or by the next one, and is never lost.
     */
    @Override
    public void requestKeyframe() {
        clock.requestKeyframe();
    }

    /**
     * Changes the bitrate, frame rate, quantizer window, key frame interval, speed, frame
     * dropping, buffer, encode breakout and rate tolerances of a running stream.
     *
     * <p>The new settings apply from the next frame; the buffer keeps its fullness in
     * milliseconds, so the rate follows a new bitrate within about a second.
     *
     * @param options the new options
     * @throws IllegalArgumentException if the new options change the time base, the temporal
     * layers, the token partitions, the threads, error resilience or the limits
     * @throws IllegalStateException if the encoder is closed
     */
    public void reconfigure(Vp8EncoderOptions options) {
        Objects.requireNonNull(options, "options");
        if (closed) {
            throw new IllegalStateException("encoder is closed");
        }
        var current = this.options;
        if (!options.timeBase().equals(current.timeBase()) || options.temporalLayers() != current.temporalLayers()
                || options.tokenPartitions() != current.tokenPartitions() || options.threads() != current.threads()
                || options.errorResilient() != current.errorResilient() || !options.limits().equals(current.limits())) {
            throw new IllegalArgumentException("the time base, temporal layers, token partitions, threads, error "
                    + "resilience and limits of a running vp8 encoder cannot change");
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
