package com.github.auties00.smalt.image.jxs;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.internal.codec.FrameClock;
import com.github.auties00.smalt.internal.codec.JxsConfigs;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.VideoEncoder;
import com.github.auties00.smalt.video.VideoEncoderOptions;
import com.github.auties00.smalt.video.VideoFrame;

import java.util.ArrayList;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Encodes JPEG XS video (ISO/IEC 21122): every frame is an independent progressive codestream, coded as
 * {@link JpegXsEncoder} codes a picture, in one key-frame packet.
 *
 * <p>Pictures are converted to the coded layout, Y'CbCr of the options' {@linkplain VideoEncoderOptions#color()
 * color description} (else {@link ColorInfo#BT709_LIMITED}) without the alpha plane:
 * <ul>
 *   <li>4:2:2 at 8 or 10 bits under the Main 422.10 profile, for a depth of at most 10 bits and an even width;</li>
 *   <li>4:4:4 at 12 bits under the Main 444.12 profile otherwise.</li>
 * </ul>
 *
 * <p>Each frame is coded at the rate the bitrate gives over the frame rate, {@code bitrate / (frameRate * width *
 * height)} bits per pixel, clamped to the range 0.25 to 24. The {@linkplain #codecConfig() configuration} is the
 * {@code jpvi} and {@code jxpl} boxes the MP4, MXF and transport stream mappings carry. Timestamps are in the time base
 * of the inverse frame rate. Instances are not thread-safe; frames are coded partly on the shared pool that
 * {@link com.github.auties00.smalt.MediaRuntime} describes, and the packets do not depend on the threading.
 */
public final class JpegXsVideoEncoder implements VideoEncoder {
    /**
     * The smallest coded rate in bits per pixel.
     */
    private static final double MIN_BITS_PER_PIXEL = 0.25;

    /**
     * The largest coded rate in bits per pixel.
     */
    private static final double MAX_BITS_PER_PIXEL = 24;

    /**
     * The full sublevel of ISO/IEC 21122-2, which bounds a codestream by its profile alone; the configuration names it
     * since the rate of each frame, which its own codestream signals, follows the bitrate.
     */
    private static final int FULL_SUBLEVEL = 0x80;

    /**
     * The picture width.
     */
    private final int width;

    /**
     * The picture height.
     */
    private final int height;

    /**
     * The time base of the timestamps.
     */
    private final Rational timeBase;

    /**
     * The nominal frame rate.
     */
    private final Rational frameRate;

    /**
     * The coded chroma format.
     */
    private final ChromaFormat format;

    /**
     * The coded bit depth.
     */
    private final int bitDepth;

    /**
     * The color description of the coded pictures.
     */
    private final ColorInfo color;

    /**
     * The track configuration.
     */
    private final byte[] config;

    /**
     * The clock assigning the timestamps.
     */
    private final FrameClock clock;

    /**
     * The operation the calls of this encoder form.
     */
    private final ParallelBands.Operation operation = new ParallelBands.Operation();

    /**
     * The coding settings, which follow the target bitrate.
     */
    private JpegXsEncoderOptions coding;

    /**
     * Constructs an encoder.
     *
     * @param options the picture size, frame rate, bitrate, bit depth and color description; the real-time, key
     * frame, thread and alpha settings do not apply to an intra-only codec coded on the shared pool
     * @throws IllegalArgumentException if a dimension is outside 1 to 65535 or the frame rate is outside 0.1 to 1000
     * frames per second
     */
    public JpegXsVideoEncoder(VideoEncoderOptions options) {
        Objects.requireNonNull(options, "options");
        this.width = options.width();
        this.height = options.height();
        if (width < 1 || height < 1 || width > 0xFFFF || height > 0xFFFF) {
            throw new IllegalArgumentException("invalid picture size " + width + "x" + height);
        }
        this.frameRate = options.frameRate();
        this.timeBase = frameRate.invert();
        FrameClock.checkRates(frameRate, timeBase);
        this.clock = new FrameClock(frameRate, timeBase);
        var narrow = options.bitDepth() <= 10 && width % 2 == 0 && width >= 2;
        this.format = narrow ? ChromaFormat.YUV422 : ChromaFormat.YUV444;
        this.bitDepth = narrow ? Math.max(8, options.bitDepth()) : 12;
        this.color = options.color().orElse(ColorInfo.BT709_LIMITED);
        var profile = narrow ? JpegXsProfile.MAIN_422_10 : JpegXsProfile.MAIN_444_12;
        this.coding = JpegXsEncoderOptions.defaults().toBuilder().profile(profile).fileFormat(false)
                .bitsPerPixel(bitsPerPixel(options.bitrate())).build();
        var blank = blank();
        var plan = JpegXsEncoder.plan(blank, coding);
        var level = plan.level() & 0xFF00 | FULL_SUBLEVEL;
        var picture = new JxsConfigs.Picture(width, height, 3, bitDepth, plan.profile(), level, 0, narrow ? 0 : 1);
        var brat = (options.bitrate() + 999_999) / 1_000_000;
        this.config = JxsConfigs.config(new JxsConfigs.Config(brat, JxsConfigs.frat(frameRate, null),
                JxsConfigs.schar(picture), 0, plan.profile(), level));
    }

    /**
     * {@return the coding rate of a bitrate in bits per pixel, clamped}
     *
     * @param bitrate the bitrate in bits per second
     */
    private double bitsPerPixel(long bitrate) {
        var rate = bitrate / (frameRate.toDouble() * width * height);
        return Math.clamp(rate, MIN_BITS_PER_PIXEL, MAX_BITS_PER_PIXEL);
    }

    /**
     * {@return an image of the coded layout whose samples are all zero}
     */
    private JpegXsImage blank() {
        var components = new ArrayList<JpegXsImage.Component>(3);
        var sx = format == ChromaFormat.YUV422 ? 2 : 1;
        components.add(new JpegXsImage.Component(width, height, bitDepth, 1, 1, 0, 0, new short[width * height]));
        for (var c = 0; c < 2; c++) {
            components.add(new JpegXsImage.Component(width / sx, height, bitDepth, sx, 1, 0, 0,
                    new short[width / sx * height]));
        }
        return new JpegXsImage(width, height, components, JpegXsImage.ColorTransform.NONE, Optional.empty(), 0, 0,
                Optional.of(color), Optional.empty());
    }

    /**
     * {@inheritDoc}
     *
     * @return {@link Codec#JPEG_XS}
     */
    @Override
    public Codec codec() {
        return Codec.JPEG_XS;
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }

    /**
     * {@inheritDoc}
     *
     * @return the inverse of the frame rate
     */
    @Override
    public Rational timeBase() {
        return timeBase;
    }

    /**
     * {@inheritDoc}
     *
     * @return the {@code jpvi} box (the largest bit rate in Mbit/s, the frame rate, the sample characteristics and
     * no time code) followed by the {@code jxpl} box (the profile, and the level of the picture size at the full
     * sublevel)
     */
    @Override
    public Optional<byte[]> codecConfig() {
        return Optional.of(config.clone());
    }

    /**
     * Encodes one frame into one key-frame packet.
     *
     * @param frame the next frame in presentation order
     * @param output receives the packet, whose data is the codestream
     * @throws IllegalArgumentException if the picture size differs from the encoder's or the timestamp does not
     * follow the previous one
     * @throws com.github.auties00.smalt.MediaException.Unsupported if the picture has floating-point channels
     */
    @Override
    public void encode(VideoFrame frame, Consumer<Packet> output) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(output, "output");
        try (var _ = ParallelBands.operation(operation)) {
            var picture = frame.picture();
            if (picture.width() != width || picture.height() != height) {
                throw new IllegalArgumentException("picture is " + picture.width() + "x" + picture.height()
                        + ", encoder expects " + width + "x" + height);
            }
            var timing = clock.resolve(frame);
            var source = ImageOps.toYuv16(picture, format, color, bitDepth);
            var image = JpegXsEncoder.components(source, coding);
            if (image.components().size() > 3) {
                image = new JpegXsImage(width, height, image.components().subList(0, 3), image.colorTransform(),
                        Optional.empty(), 0, 0, image.color(), Optional.empty());
            }
            var data = JpegXsEncoder.encode(image, coding);
            clock.commit(timing.pts());
            output.accept(new Packet(0, data, timing.pts(), timing.pts(), timing.duration(), true));
        }
    }

    /**
     * Does nothing: every JPEG XS frame is coded independently, so every packet is a key frame.
     */
    @Override
    public void requestKeyframe() {
    }

    /**
     * {@inheritDoc}
     *
     * <p>The frames after the call are coded at the new rate.
     *
     * @throws IllegalArgumentException if {@code bitsPerSecond} is not positive
     */
    @Override
    public void setTargetBitrate(long bitsPerSecond) {
        if (bitsPerSecond <= 0) {
            throw new IllegalArgumentException("bitrate " + bitsPerSecond + " is not positive");
        }
        coding = coding.toBuilder().bitsPerPixel(bitsPerPixel(bitsPerSecond)).build();
    }

    /**
     * Does nothing: JPEG XS frames are never held back.
     *
     * @param output receives no packet
     */
    @Override
    public void flush(Consumer<Packet> output) {
        Objects.requireNonNull(output, "output");
    }
}
