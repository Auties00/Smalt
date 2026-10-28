package com.github.auties00.smalt.video.h263;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.ops.YuvConverter;
import com.github.auties00.smalt.internal.codec.FrameClock;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.VideoEncoder;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.mpeg4.internal.Frame;
import com.github.auties00.smalt.video.mpeg4.internal.encoder.H263EncoderCore;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Encodes pictures into an ITU-T H.263 stream, the {@code s263} video of 3GP and MP4 files: the baseline syntax, or
 * H.263 version 2 (H.263+) with a custom picture format, a custom picture clock frequency and the optional modes of
 * {@link H263EncoderOptions}, slices of the Slice Structured mode included.
 *
 * <p>The stream holds I- and P-pictures, each P-picture predicted from the picture before it with one half-sample
 * vector per macroblock, or with four where the Advanced Prediction or Deblocking Filter mode allows them. The first
 * picture, every {@link H263EncoderOptions#keyframeInterval()}-th picture and the picture after a
 * {@link #requestKeyframe()} are I-pictures. A picture of one of the standard source formats (sub-QCIF 128x96, QCIF
 * 176x144, CIF 352x288, 4CIF 704x576 and 16CIF 1408x1152) without the modes of the second version has the baseline
 * picture header; any other picture has the extended picture type, with a custom picture format of 1:1 pixels for
 * any other size and, when the frame rate is a whole fraction of one, a custom picture clock frequency.
 *
 * <p>Each packet holds one picture, starting with its picture start code; every frame gives its packet before
 * {@link #encode(VideoFrame, Consumer)} returns, with equal presentation and decoding timestamps. Frames may be
 * given in any {@link Picture} representation of exactly the encoder's size: a picture in 8-bit 4:2:0 of the color
 * a decoder assumes (limited-range BT.601, or BT.709 above 1024x576) is coded as it is, any other is converted to it
 * first. Timestamps are in {@link #timeBase()} units and must increase; a frame without a timestamp is placed one
 * nominal frame after the previous one, and a frame without a duration lasts one nominal frame. The decoded
 * pictures equal those of {@link H263Decoder}'s decode of the stream, sample for sample.
 *
 * <p>With {@link H263EncoderOptions#threads()} 0 or above 1 the motion analysis of a picture runs on threads of the
 * library's shared pool together with the calling thread (see
 * {@link com.github.auties00.smalt.MediaRuntime MediaRuntime}); the stream never depends on the threads.
 *
 * <p>Instances are not thread-safe, except {@link #requestKeyframe()}, which may be called from any thread at any
 * time.
 */
public final class H263Encoder implements VideoEncoder {
    private final H263EncoderOptions options;
    private final int width;
    private final int height;
    private final ColorInfo color;
    private final H263EncoderCore core;
    private final FrameClock clock;
    private final YuvConverter converter;
    private final boolean extendedType;
    private final AtomicBoolean keyframeRequested = new AtomicBoolean();
    private final ParallelBands.Operation operation = new ParallelBands.Operation();
    private boolean closed;

    /**
     * Constructs an encoder for a picture size with the {@link H263EncoderOptions#defaults()}.
     *
     * @param width the picture width in pixels: that of a standard source format, or a multiple of 4 from 4 to 2048
     * @param height the picture height in pixels: that of a standard source format, or a multiple of 4 from 4 to
     * 1152
     * @throws IllegalArgumentException if H.263 cannot code the picture size
     * @throws MediaException.LimitExceeded if the picture exceeds the default limits
     */
    public H263Encoder(int width, int height) {
        this(width, height, H263EncoderOptions.defaults());
    }

    /**
     * Constructs an encoder for a picture size.
     *
     * @param width the picture width in pixels: that of a standard source format, or a multiple of 4 from 4 to 2048
     * @param height the picture height in pixels: that of a standard source format, or a multiple of 4 from 4 to
     * 1152
     * @param options the encoder settings
     * @throws IllegalArgumentException if H.263 cannot code the picture size
     * @throws MediaException.LimitExceeded if the picture exceeds the options' limits
     */
    public H263Encoder(int width, int height, H263EncoderOptions options) {
        this.options = Objects.requireNonNull(options, "options");
        if (!codesSize(width, height)) {
            throw new IllegalArgumentException("H.263 cannot code pictures of " + width + "x" + height
                    + ": a custom format needs a width that is a multiple of 4 from 4 to 2048 and a height that is"
                    + " a multiple of 4 from 4 to 1152");
        }
        LimitChecks.checkPicture(options.limits(), width, height);
        this.width = width;
        this.height = height;
        this.color = width > 1024 || height > 576 ? ColorInfo.BT709_LIMITED : ColorInfo.BT601_LIMITED;
        this.converter = YuvConverter.create(ChromaFormat.YUV420, color);
        this.clock = new FrameClock(options.frameRate(), options.timeBase());
        var standard = H263EncoderCore.formatOf(width, height) != 6;
        this.extendedType = !standard || options.unrestrictedVectors() || options.advancedIntraCoding()
                || options.deblockingFilter() || options.modifiedQuantization() || options.sliceSize().isPresent();
        var conversion = 0;
        var divisor = 0;
        if (extendedType) {
            var custom = customClock(options.frameRate());
            conversion = custom[0];
            divisor = custom[1];
        }
        var threads = options.threads() == 0 ? ParallelBands.THREADS : options.threads();
        this.core = new H263EncoderCore(new H263EncoderCore.Settings(width, height, extendedType,
                options.unrestrictedVectors(), options.advancedPrediction(), options.advancedIntraCoding(),
                options.deblockingFilter(), options.modifiedQuantization(), options.groupOfBlocksHeaders(),
                options.sliceSize().orElse(0), conversion,
                divisor, options.searchRange(), threads, options.bitrate(), options.frameRate(),
                options.fixedQuantizer().orElse(0), options.minQuantizer(), options.maxQuantizer(),
                options.keyframeInterval().orElse(0), options.timeBase()));
    }

    /**
     * Returns whether H.263 codes a picture size: one of the standard source formats, or a custom format.
     *
     * @param width the picture width
     * @param height the picture height
     * @return {@code true} when an encoder of the size can be constructed
     */
    public static boolean codesSize(int width, int height) {
        return H263EncoderCore.formatOf(width, height) != 6
                || width >= 4 && width <= 2048 && width % 4 == 0 && height >= 4 && height <= 1152 && height % 4 == 0;
    }

    /**
     * Returns the custom picture clock frequency of a frame rate: the clock conversion code and divisor of the
     * slowest clock {@code 1800000 / (conversion * divisor)} Hz of which the frame rate is a whole fraction, or
     * zeros for the standard clock when the frame rate is a whole fraction of it or of no custom clock.
     *
     * @param frameRate the frame rate
     * @return the conversion code (1000 or 1001, or 0) and the divisor
     */
    static int[] customClock(Rational frameRate) {
        var rate = frameRate.reduce();
        if (wholeFraction(1001, 60, rate)) {
            return new int[]{0, 0};
        }
        for (var divisor = 127; divisor >= 1; divisor--) {
            for (var conversion : new int[]{1000, 1001}) {
                if (wholeFraction(conversion, divisor, rate)) {
                    return new int[]{conversion, divisor};
                }
            }
        }
        return new int[]{0, 0};
    }

    /**
     * Returns whether a frame rate is a whole fraction of a picture clock, the clock frequency divided by the frame
     * rate being a positive integer.
     *
     * @param conversion the clock conversion code
     * @param divisor the clock divisor
     * @param rate the frame rate, reduced
     * @return {@code true} when the frame period is a whole number of clock periods
     */
    private static boolean wholeFraction(int conversion, int divisor, Rational rate) {
        var numerator = 1_800_000L * rate.denominator();
        var denominator = (long) conversion * divisor * rate.numerator();
        return numerator % denominator == 0;
    }

    /**
     * {@return the options of this encoder}
     */
    public H263EncoderOptions options() {
        return options;
    }

    /**
     * {@return whether the pictures carry the extended picture type of H.263 version 2}
     */
    public boolean extendedType() {
        return extendedType;
    }

    @Override
    public Codec codec() {
        return Codec.H263;
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
     * @return empty: H.263 needs no out-of-band configuration
     */
    @Override
    public Optional<byte[]> codecConfig() {
        return Optional.empty();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Exactly one packet is emitted before this method returns.
     *
     * @throws IllegalArgumentException if the picture size differs from the encoder's or the timestamp does not
     * increase
     * @throws IllegalStateException if the encoder is closed
     */
    @Override
    public void encode(VideoFrame frame, Consumer<Packet> output) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(output, "output");
        try (var _ = ParallelBands.operation(operation)) {
            if (closed) {
                throw new IllegalStateException("encoder is closed");
            }
            var picture = frame.picture();
            if (picture.width() != width || picture.height() != height) {
                throw new IllegalArgumentException("picture is " + picture.width() + "x" + picture.height()
                        + ", encoder expects " + width + "x" + height);
            }
            var timing = clock.resolve(frame);
            core.encode(toYuv420(picture), timing.pts(), timing.duration(), keyframeRequested.getAndSet(false),
                    output);
            clock.commit(timing.pts());
        }
    }

    /**
     * Returns a picture in the color a decoder assumes, 8-bit 4:2:0.
     *
     * @param picture the picture to code
     * @return {@code picture} itself when it is in that representation already, otherwise a converted copy, valid
     * until the next call
     */
    private YuvPicture toYuv420(Picture picture) {
        if (picture instanceof YuvPicture yuv && yuv.chromaFormat() == ChromaFormat.YUV420 && yuv.bitDepth() == 8
                && yuv.color().range() == color.range()
                && yuv.color().matrix().effective() == color.matrix().effective()) {
            return yuv;
        }
        return converter.convert(picture);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The encoder holds no pictures, so this emits nothing.
     *
     * @throws IllegalStateException if the encoder is closed
     */
    @Override
    public void flush(Consumer<Packet> output) {
        Objects.requireNonNull(output, "output");
        if (closed) {
            throw new IllegalStateException("encoder is closed");
        }
    }

    /**
     * Makes the next picture passed to {@link #encode(VideoFrame, Consumer)} an I-picture.
     *
     * <p>This method may be called from any thread, also while another thread encodes a frame: the request is then
     * taken by that frame or by the next one, and is never lost.
     */
    @Override
    public void requestKeyframe() {
        keyframeRequested.set(true);
    }

    /**
     * Changes the target bitrate from the next picture on, for example when a congestion controller reports a new
     * estimate.
     *
     * @param bitsPerSecond the new target, in bits per second; positive
     * @throws IllegalArgumentException if {@code bitsPerSecond} is not positive
     * @throws IllegalStateException if the encoder is closed or codes with a fixed quantizer
     */
    @Override
    public void setTargetBitrate(long bitsPerSecond) {
        if (bitsPerSecond <= 0) {
            throw new IllegalArgumentException("bitrate " + bitsPerSecond + " is not positive");
        }
        if (closed) {
            throw new IllegalStateException("encoder is closed");
        }
        if (options.fixedQuantizer().isPresent()) {
            throw new IllegalStateException("the encoder codes with a fixed quantizer");
        }
        core.retarget(bitsPerSecond);
    }

    /**
     * {@return the quantizer of the picture coded last, from 1 to 31, or 0 before the first}
     */
    public int lastQuantizer() {
        return core.lastQuantizer();
    }

    /**
     * Copies the reconstruction of the picture coded last as planar 4:2:0 samples of the picture size, for tests.
     *
     * @return the samples, or {@code null} before the first picture
     */
    byte[] lastReconstruction() {
        Frame frame = core.lastReconstruction();
        if (frame == null) {
            return null;
        }
        var chromaWidth = (width + 1) >> 1;
        var chromaHeight = (height + 1) >> 1;
        var out = new byte[width * height + 2 * chromaWidth * chromaHeight];
        var at = 0;
        for (var row = 0; row < height; row++, at += width) {
            System.arraycopy(frame.y, frame.lumaOrigin + row * frame.lumaStride, out, at, width);
        }
        for (var row = 0; row < chromaHeight; row++, at += chromaWidth) {
            System.arraycopy(frame.u, frame.chromaOrigin + row * frame.chromaStride, out, at, chromaWidth);
        }
        for (var row = 0; row < chromaHeight; row++, at += chromaWidth) {
            System.arraycopy(frame.v, frame.chromaOrigin + row * frame.chromaStride, out, at, chromaWidth);
        }
        return out;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The encoder cannot be used afterwards. It holds no threads between calls, so closing it releases no thread.
     */
    @Override
    public void close() {
        closed = true;
    }
}
