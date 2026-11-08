package com.github.auties00.smalt.video.ffv1;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.internal.check.Checks;
import com.github.auties00.smalt.internal.codec.FrameClock;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.VideoEncoder;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.ffv1.internal.Ffv1FrameEncoder;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Encodes pictures into FFV1 video (RFC 9043), versions 0, 1 and 3, losslessly.
 *
 * <p>The coded layout is fixed by the {@link Ffv1EncoderOptions}: Y'CbCr of the options'
 * {@linkplain Ffv1EncoderOptions#chromaFormat() chroma format}, or RGB, at the options' depth, with or without a
 * transparency plane. A picture already in that layout (a {@link YuvPicture} or {@link Yuv16Picture} of the coded
 * chroma format and depth, or for RGB an {@link RgbaPicture} at 8 bits or an {@link Rgba16Picture} deeper, whose
 * samples are scaled from 16 bits to the coded depth as {@code round(v * (2^d - 1) / 65535)}) is coded exactly, so
 * that {@link Ffv1Decoder} returns its samples unchanged; any other picture is converted with
 * {@link ImageOps}, using {@link Ffv1EncoderOptions#color()}, and the conversion is not lossless. Pictures without
 * alpha are coded opaque when a transparency plane is coded.
 *
 * <p>Every packet holds one frame, a key frame every {@link Ffv1EncoderOptions#keyframeInterval()} frames and
 * after {@link #requestKeyframe()}; its timestamps are the frame's, or follow the previous frame at the nominal
 * frame rate when the frame has none. A version 3 stream's configuration record is {@link #codecConfig()}; the
 * slices of a version 3 frame are coded in parallel on the module's shared helper threads unless the options ask
 * for one thread, and the output does not depend on the threading.
 *
 * <p>Instances are not thread-safe.
 */
public final class Ffv1Encoder implements VideoEncoder {
    private final int width;

    private final int height;

    private final Ffv1EncoderOptions options;

    private final Ffv1FrameEncoder frames;

    private final FrameClock clock;

    private final byte[] configuration;

    private final int bits;

    private final int pictureStructure;

    private final int aspectNumerator;

    private final int aspectDenominator;

    private final ParallelBands.Operation operation = new ParallelBands.Operation();

    private long count;

    private boolean keyRequested;

    /**
     * Constructs an encoder with the {@link Ffv1EncoderOptions#defaults()}.
     *
     * @param width the picture width, positive
     * @param height the picture height, positive
     * @throws IllegalArgumentException if a dimension is not positive
     */
    public Ffv1Encoder(int width, int height) {
        this(width, height, Ffv1EncoderOptions.defaults());
    }

    /**
     * Constructs an encoder.
     *
     * @param width the picture width, positive
     * @param height the picture height, positive
     * @param options the encoding options
     * @throws IllegalArgumentException if a dimension is not positive, or the frame cannot be divided into the
     * requested number of slices with every slice boundary on a chroma sample boundary
     */
    public Ffv1Encoder(int width, int height, Ffv1EncoderOptions options) {
        Checks.positive("width", width);
        Checks.positive("height", height);
        this.width = width;
        this.height = height;
        this.options = Objects.requireNonNull(options, "options");
        var rgb = options.rgb();
        var format = options.chromaFormat();
        this.bits = options.bitDepth();
        var coderType = switch (options.coder()) {
            case GOLOMB_RICE -> bits > 8 ? 2 : 0;
            case RANGE -> 1;
            case RANGE_CUSTOM_STATES -> 2;
        };
        var columns = 1;
        var rows = 1;
        if (options.version() >= 3) {
            var shiftX = rgb ? 0 : format.shiftX();
            var shiftY = rgb ? 0 : format.shiftY();
            int[] raster;
            if (options.slices().isPresent()) {
                var slices = options.slices().getAsInt();
                raster = raster(slices, width, height, shiftX, shiftY);
                if (raster == null) {
                    throw new IllegalArgumentException(slices + " slices do not fit a " + width + "x" + height
                            + " frame on its chroma sample boundaries");
                }
            } else {
                var automatic = automaticSlices(width, height);
                raster = null;
                for (var slices = automatic; raster == null && slices <= 1024; slices++) {
                    raster = raster(slices, width, height, shiftX, shiftY);
                }
                for (var slices = automatic - 1; raster == null; slices--) {
                    raster = raster(slices, width, height, shiftX, shiftY);
                }
            }
            columns = raster[0];
            rows = raster[1];
        }
        this.frames = new Ffv1FrameEncoder(width, height, options.version(), coderType, rgb,
                format != ChromaFormat.MONOCHROME, format.shiftX(), format.shiftY(), options.alpha(), bits,
                options.largeContext(), columns, rows, options.sliceCrc(), options.keyframeInterval() == 1,
                options.threads());
        this.configuration = frames.configurationRecord();
        this.clock = new FrameClock(options.frameRate(), options.timeBase());
        this.pictureStructure = options.fieldOrder().map(order -> switch (order) {
            case TOP_FIRST -> 1;
            case BOTTOM_FIRST -> 2;
            default -> 3;
        }).orElse(0);
        var aspect = options.sampleAspectRatio().map(Rational::reduce).orElse(null);
        this.aspectNumerator = aspect == null ? 0 : (int) Math.min(aspect.numerator(), Integer.MAX_VALUE);
        this.aspectDenominator = aspect == null ? 0 : (int) Math.min(aspect.denominator(), Integer.MAX_VALUE);
    }

    /**
     * Returns the slice raster of a slice count: the columns and rows whose product is the count, as near to square
     * as possible (more columns than rows for a frame wider than high), whose every boundary falls on a chroma
     * sample boundary, or {@code null} when no raster of the count fits.
     */
    private static int[] raster(int slices, int width, int height, int shiftX, int shiftY) {
        int[] best = null;
        var bestSkew = Integer.MAX_VALUE;
        for (var rows = 1; rows <= slices; rows++) {
            if (slices % rows != 0) {
                continue;
            }
            var columns = slices / rows;
            if (columns > width || rows > height || columns > 256 || rows > 256
                    || !aligned(columns, width, shiftX) || !aligned(rows, height, shiftY)) {
                continue;
            }
            var skew = Math.abs(columns - rows) * 2 + (width >= height ? (rows > columns ? 1 : 0)
                    : (columns > rows ? 1 : 0));
            if (skew < bestSkew) {
                bestSkew = skew;
                best = new int[]{columns, rows};
            }
        }
        return best;
    }

    /**
     * {@return whether the boundaries of {@code count} slices across {@code size} samples are multiples of
     * {@code 2^shift}}
     */
    private static boolean aligned(int count, int size, int shift) {
        var mask = (1 << shift) - 1;
        for (var k = 1; k < count; k++) {
            if (((int) ((long) k * size / count) & mask) != 0) {
                return false;
            }
        }
        return true;
    }

    private static int automaticSlices(int width, int height) {
        var pixels = (long) width * height;
        if (pixels <= 352 * 288) {
            return 1;
        }
        if (pixels <= 1280 * 720) {
            return 6;
        }
        return pixels <= 1920 * 1088 ? 12 : 24;
    }

    /**
     * {@return the encoding options}
     */
    public Ffv1EncoderOptions options() {
        return options;
    }

    /**
     * {@inheritDoc}
     *
     * @return {@link Codec#FFV1}
     */
    @Override
    public Codec codec() {
        return Codec.FFV1;
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
     * @return the configuration record of a version 3 stream, or empty for versions 0 and 1
     */
    @Override
    public Optional<byte[]> codecConfig() {
        return Optional.ofNullable(configuration).map(byte[]::clone);
    }

    /**
     * Encodes one frame into one packet.
     *
     * @param frame the next frame in presentation order
     * @param output receives the packet
     * @throws IllegalArgumentException if the picture size differs from the encoder's or the timestamp does not
     * follow the previous one
     */
    @Override
    public void encode(VideoFrame frame, Consumer<Packet> output) {
        Objects.requireNonNull(output, "output");
        output.accept(encodeFrame(frame));
    }

    /**
     * Encodes one frame and returns its packet.
     *
     * @param frame the picture and its timestamp
     * @return the packet, a key frame when the frame resets the context states
     * @throws IllegalArgumentException if the picture size differs from the encoder's or the timestamp does not
     * follow the previous one
     */
    public Packet encodeFrame(VideoFrame frame) {
        Objects.requireNonNull(frame, "frame");
        try (var ignored = ParallelBands.operation(operation)) {
            var picture = frame.picture();
            if (picture.width() != width || picture.height() != height) {
                throw new IllegalArgumentException("picture is " + picture.width() + "x" + picture.height()
                        + ", encoder expects " + width + "x" + height);
            }
            var timing = clock.resolve(frame);
            var keyframe = keyRequested || count % options.keyframeInterval() == 0;
            var data = frames.encode(prepare(picture), keyframe, pictureStructure, aspectNumerator,
                    aspectDenominator);
            clock.commit(timing.pts());
            count = keyframe ? 1 : count + 1;
            keyRequested = false;
            return new Packet(0, data, timing.pts(), timing.pts(), timing.duration(), keyframe);
        }
    }

    /**
     * Converts a picture to the coded layout, unless it already has it.
     */
    private Picture prepare(Picture picture) {
        if (options.rgb()) {
            if (bits == 8) {
                return picture instanceof RgbaPicture ? picture : ImageOps.toRgba(picture);
            }
            return picture instanceof Rgba16Picture ? picture : ImageOps.toRgba16(picture);
        }
        var format = options.chromaFormat();
        if (bits == 8) {
            if (picture instanceof YuvPicture yuv && yuv.chromaFormat() == format) {
                return yuv;
            }
            return ImageOps.toYuv(picture, format, options.color());
        }
        if (picture instanceof Yuv16Picture yuv && yuv.chromaFormat() == format) {
            return yuv.bitDepth() == bits ? yuv : ImageOps.toBitDepth(yuv, bits);
        }
        if (picture instanceof YuvPicture yuv && yuv.chromaFormat() == format) {
            return ImageOps.toBitDepth(yuv, bits);
        }
        return ImageOps.toYuv16(picture, format, options.color(), bits);
    }

    /**
     * Makes the next frame a key frame.
     */
    @Override
    public void requestKeyframe() {
        keyRequested = true;
    }

    /**
     * Does nothing: FFV1 frames are never held back.
     *
     * @param output receives no packet
     */
    @Override
    public void flush(Consumer<Packet> output) {
        Objects.requireNonNull(output, "output");
    }
}
