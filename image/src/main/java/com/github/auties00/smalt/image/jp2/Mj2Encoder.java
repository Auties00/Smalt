package com.github.auties00.smalt.image.jp2;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.PixelDensity;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.jp2.internal.Jp2Writer;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.internal.codec.FrameClock;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.FieldOrder;
import com.github.auties00.smalt.video.VideoEncoder;
import com.github.auties00.smalt.video.VideoFrame;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Encodes pictures into Motion JPEG 2000 video (ISO/IEC 15444-3), every frame an independent JPEG 2000 image coded
 * by {@link Jp2Encoder}.
 *
 * <p>Each picture is converted to the options' {@linkplain Mj2EncoderOptions#pixelFormat() component layout} and
 * {@linkplain Mj2EncoderOptions#bitDepth() depth}, then coded as {@link Jp2Encoder#encode(Jp2Image,
 * Jp2EncoderOptions)} codes channels under the options' {@linkplain Mj2EncoderOptions#coding() coding}: Y'CbCr
 * layouts as their planes, without a component transform and declared sYCC; gray as one component; RGB through
 * the component transform the coding selects, declared sRGB, with an opacity component for
 * {@link Mj2PixelFormat#RGBA}. A progressive frame is the JP2 file or the raw codestream of the coding's format;
 * an interlaced frame ({@link Mj2EncoderOptions#fieldOrder()}) is two Contiguous Codestream boxes, one per field,
 * the earlier field first. Every frame codes the same components at the same precision, so
 * {@link #codecConfig()} describes them all.
 *
 * <p>Every frame is independent: each {@link #encode(VideoFrame, Consumer)} emits one key-frame packet carrying the
 * frame's timestamp (or, without one, the timestamp following the previous frame at the nominal frame rate) and
 * duration, and {@link #flush(Consumer)} has nothing to drain. The packets suit every container that carries
 * {@link Codec#JPEG2000}: MJ2, MP4 and QuickTime, Matroska and AVI. A lossless coding reproduces the converted
 * samples exactly: {@link Mj2Decoder} returns them unchanged.
 *
 * <p>Instances are not thread-safe. Frames are coded on the shared pool that
 * {@link com.github.auties00.smalt.MediaRuntime} describes; the output does not depend on the threading.
 */
public final class Mj2Encoder implements VideoEncoder {
    /**
     * The largest frame dimension, the limit of the 16-bit size fields of a visual sample entry.
     */
    private static final int MAX_DIMENSION = 65535;

    /**
     * The smallest per-image byte budget the rate control asks for.
     */
    private static final long MIN_BUDGET = 64;

    private final int width;

    private final int height;

    private final Mj2EncoderOptions options;

    /**
     * The timestamps of the coded frames.
     */
    private final FrameClock clock;

    /**
     * The operation the calls of this encoder form.
     */
    private final ParallelBands.Operation operation = new ParallelBands.Operation();

    /**
     * The JP2 Header box describing every coded image.
     */
    private final byte[] header;

    /**
     * The color space the images declare.
     */
    private final Jp2ColorSpace colorSpace;

    /**
     * The sample buffers of each image of a frame (one, or one per field) and component, reused from frame to frame.
     */
    private final int[][][] buffers;

    /**
     * The target bitrate in bits per second, or 0 for none.
     */
    private long bitrate;

    /**
     * The per-image byte budget {@link #budgeted} was built for, or -1.
     */
    private long budget = -1;

    /**
     * The coding options of the last budget.
     */
    private Jp2EncoderOptions budgeted;

    /**
     * Constructs an encoder for a picture size under the {@link Mj2EncoderOptions#defaults()}.
     *
     * @param width the picture width, from 1 to 65535
     * @param height the picture height, from 1 to 65535
     * @throws IllegalArgumentException if a dimension is out of range
     * @throws MediaException.LimitExceeded if the picture exceeds the default limits
     */
    public Mj2Encoder(int width, int height) {
        this(width, height, Mj2EncoderOptions.defaults());
    }

    /**
     * Constructs an encoder for a picture size.
     *
     * @param width the picture width, from 1 to 65535
     * @param height the picture height, from 1 to 65535, and at least 2 for an interlaced
     * {@link Mj2EncoderOptions#fieldOrder()}
     * @param options the encoding options
     * @throws IllegalArgumentException if a dimension is out of range
     * @throws MediaException.LimitExceeded if the picture exceeds the limits of the options' coding
     */
    public Mj2Encoder(int width, int height, Mj2EncoderOptions options) {
        this.options = Objects.requireNonNull(options, "options");
        var minHeight = options.fieldOrder().isInterlaced() ? 2 : 1;
        if (width < 1 || height < minHeight || width > MAX_DIMENSION || height > MAX_DIMENSION) {
            throw new IllegalArgumentException("invalid picture size " + width + "x" + height);
        }
        LimitChecks.checkPicture(options.coding().limits(), width, height);
        this.width = width;
        this.height = height;
        this.clock = new FrameClock(options.frameRate(), options.timeBase());
        this.bitrate = options.bitrate().orElse(0);
        var format = options.pixelFormat();
        this.colorSpace = format == Mj2PixelFormat.GRAY ? Jp2ColorSpace.GRAYSCALE
                : format.isRgb() ? Jp2ColorSpace.SRGB : Jp2ColorSpace.SYCC;
        var count = format.components();
        var depths = new int[count];
        Arrays.fill(depths, options.bitDepth());
        var imageHeight = options.fieldOrder() == FieldOrder.PROGRESSIVE ? height
                : options.fieldOrder() == FieldOrder.TOP_FIRST ? (height + 1) / 2 : height / 2;
        var coding = options.coding();
        this.header = Jp2Writer.header(width, imageHeight, depths, new boolean[count], colorSpace.code(),
                coding.colorProfile().map(ColorProfile::toByteArray).orElse(null),
                format == Mj2PixelFormat.RGBA ? 3 : -1, false,
                coding.density().filter(PixelDensity::isPhysical).orElse(null));
        this.buffers = new int[options.fieldOrder().isInterlaced() ? 2 : 1][count][];
    }

    /**
     * {@return the encoding options}
     */
    public Mj2EncoderOptions options() {
        return options;
    }

    /**
     * {@inheritDoc}
     *
     * @return {@link Codec#JPEG2000}
     */
    @Override
    public Codec codec() {
        return Codec.JPEG2000;
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
     * @return the JP2 Header box ({@code jp2h}, header included) of the coded images, the one every JP2 file frame
     * carries: an Image Header box of the image size (a field's for interlaced frames, the first field's height)
     * and the components, a Colour Specification box holding the coding's
     * {@linkplain Jp2EncoderOptions#colorProfile() ICC profile} or else the layout's enumerated color space, a
     * Channel Definition box declaring the opacity component of {@link Mj2PixelFormat#RGBA}, and a Capture
     * Resolution box for the coding's physical {@linkplain Jp2EncoderOptions#density() density}
     */
    @Override
    public Optional<byte[]> codecConfig() {
        return Optional.of(header.clone());
    }

    /**
     * Encodes one frame into one key-frame packet.
     *
     * @param frame the next frame in presentation order
     * @param output receives the packet
     * @throws IllegalArgumentException if the picture size differs from the encoder's, the timestamp does not
     * follow the previous one, or the image does not meet the coding's profile
     */
    @Override
    public void encode(VideoFrame frame, Consumer<Packet> output) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(output, "output");
        output.accept(encodeFrame(frame));
    }

    /**
     * Encodes one frame and returns its packet.
     *
     * @param frame the picture and its timestamp
     * @return the key-frame packet of track 0, whose presentation and decoding times are the frame's timestamp
     * @throws IllegalArgumentException if the picture size differs from the encoder's, the timestamp does not
     * follow the previous one, or the image does not meet the coding's profile
     */
    public Packet encodeFrame(VideoFrame frame) {
        Objects.requireNonNull(frame, "frame");
        try (var _ = ParallelBands.operation(operation)) {
            var picture = frame.picture();
            if (picture.width() != width || picture.height() != height) {
                throw new IllegalArgumentException("picture is " + picture.width() + "x" + picture.height()
                        + ", encoder expects " + width + "x" + height);
            }
            var timing = clock.resolve(frame);
            var source = convert(picture);
            var interlaced = options.fieldOrder().isInterlaced();
            var coding = coding(timing.duration(), interlaced);
            byte[] data;
            if (!interlaced) {
                data = Jp2Encoder.encode(image(source, 0, 1, height, 0), coding);
            } else {
                var topFirst = options.fieldOrder() == FieldOrder.TOP_FIRST;
                var firstParity = topFirst ? 0 : 1;
                var first = Jp2Encoder.encode(image(source, firstParity, 2, rows(firstParity), 0), coding);
                var second = Jp2Encoder.encode(image(source, 1 - firstParity, 2, rows(1 - firstParity), 1), coding);
                data = new byte[16 + first.length + second.length];
                var at = box(data, 0, first);
                box(data, at, second);
            }
            clock.commit(timing.pts());
            return new Packet(0, data, timing.pts(), timing.pts(), timing.duration(), true);
        }
    }

    /**
     * Does nothing: every Motion JPEG 2000 frame is coded independently, so every packet is a key frame.
     */
    @Override
    public void requestKeyframe() {
    }

    /**
     * {@inheritDoc}
     *
     * <p>The frames after the call are coded at the new target.
     *
     * @throws IllegalArgumentException if {@code bitsPerSecond} is not positive
     * @throws IllegalStateException if the encoder codes without a {@link Mj2EncoderOptions#bitrate()}
     */
    @Override
    public void setTargetBitrate(long bitsPerSecond) {
        if (bitsPerSecond <= 0) {
            throw new IllegalArgumentException("bitrate " + bitsPerSecond + " is not positive");
        }
        if (bitrate == 0) {
            throw new IllegalStateException("the encoder codes without a target bitrate");
        }
        bitrate = bitsPerSecond;
        budget = -1;
    }

    /**
     * Does nothing: Motion JPEG 2000 frames are never held back.
     *
     * @param output receives no packet
     */
    @Override
    public void flush(Consumer<Packet> output) {
        Objects.requireNonNull(output, "output");
    }

    /**
     * Returns the number of rows of a field.
     *
     * @param parity 0 for the top field, 1 for the bottom field
     * @return the rows
     */
    private int rows(int parity) {
        return parity == 0 ? (height + 1) / 2 : height / 2;
    }

    /**
     * Returns the coding options of the next image: the options' coding, its format a codestream for the fields of
     * an interlaced frame, its size capped at the image's share of the target bitrate.
     *
     * @param duration the frame duration, in the time base
     * @param interlaced whether the frame is coded as two fields
     * @return the options
     */
    private Jp2EncoderOptions coding(long duration, boolean interlaced) {
        var coding = options.coding();
        if (bitrate == 0) {
            return interlaced && coding.format() != Jp2Format.CODESTREAM
                    ? cached(-2, () -> coding.toBuilder().format(Jp2Format.CODESTREAM).build()) : coding;
        }
        var bits = (double) bitrate * duration * options.timeBase().toDouble();
        var share = Math.max(MIN_BUDGET, (long) (bits / 8 / (interlaced ? 2 : 1)));
        var limit = coding.maxCodestreamBytes();
        var bytes = limit.isPresent() ? Math.min(limit.getAsLong(), share) : share;
        return cached(bytes, () -> {
            var builder = coding.toBuilder().maxCodestreamBytes(bytes);
            if (interlaced) {
                builder.format(Jp2Format.CODESTREAM);
            }
            return builder.build();
        });
    }

    /**
     * Returns the coding options built for a budget, building them when the budget changed.
     *
     * @param key the budget, or -2 for the unbudgeted field coding
     * @param factory builds the options
     * @return the options
     */
    private Jp2EncoderOptions cached(long key, Supplier<Jp2EncoderOptions> factory) {
        if (budget != key) {
            budgeted = factory.get();
            budget = key;
        }
        return budgeted;
    }

    /**
     * Converts a picture to the options' layout and depth.
     *
     * @param picture the frame's picture
     * @return a {@link YuvPicture}, {@link Yuv16Picture}, {@link RgbaPicture} or {@link Rgba16Picture}
     */
    private Picture convert(Picture picture) {
        var format = options.pixelFormat();
        var depth = options.bitDepth();
        if (format.isRgb()) {
            return depth == 8 ? ImageOps.toRgba(picture) : ImageOps.toRgba16(picture);
        }
        var chroma = format.chromaFormat();
        var color = switch (picture) {
            case YuvPicture yuv -> yuv.color();
            case Yuv16Picture yuv -> yuv.color();
            default -> ColorInfo.JPEG;
        };
        if (depth == 8) {
            var narrow = picture instanceof Yuv16Picture wide ? ImageOps.toEightBit(wide) : picture;
            return narrow instanceof YuvPicture yuv && yuv.chromaFormat() == chroma ? yuv
                    : ImageOps.toYuv(narrow, chroma, color);
        }
        return switch (picture) {
            case Yuv16Picture yuv when yuv.chromaFormat() == chroma ->
                    yuv.bitDepth() == depth ? yuv : ImageOps.toBitDepth(yuv, depth);
            case YuvPicture yuv when yuv.chromaFormat() == chroma -> ImageOps.toBitDepth(yuv, depth);
            default -> ImageOps.toYuv16(picture, chroma, color, depth);
        };
    }

    /**
     * Builds the channels of one image of a frame: the whole picture, or one field of it.
     *
     * @param source the converted picture
     * @param firstRow the first row of the image, 0 or 1
     * @param step the row step, 1 for the whole picture and 2 for a field
     * @param rows the number of rows of the image
     * @param slot the buffer set to fill, 0 or 1
     * @return the image
     */
    private Jp2Image image(Picture source, int firstRow, int step, int rows, int slot) {
        var format = options.pixelFormat();
        var count = format.components();
        var depth = options.bitDepth();
        var channels = new ArrayList<Jp2Channel>(count);
        for (var c = 0; c < count; c++) {
            var chromaPlane = !format.isRgb() && c > 0;
            var chroma = format.chromaFormat();
            var planeWidth = chromaPlane ? chroma.chromaWidth(width) : width;
            var planeRows = chromaPlane ? chroma.chromaHeight(rows) : rows;
            var sourceRows = chromaPlane ? chroma.chromaHeight(height) : height;
            var samples = buffer(slot, c, planeWidth * planeRows);
            switch (source) {
                case YuvPicture yuv -> copy(c == 0 ? yuv.y() : c == 1 ? yuv.u() : yuv.v(),
                        c == 0 ? yuv.yStride() : yuv.uvStride(), 1, 0, planeWidth, planeRows, sourceRows, firstRow,
                        step, samples);
                case Yuv16Picture yuv -> copy(c == 0 ? yuv.y() : c == 1 ? yuv.u() : yuv.v(),
                        c == 0 ? yuv.yStride() : yuv.uvStride(), 1, 0, planeWidth, planeRows, sourceRows, firstRow,
                        step, samples, 0);
                case RgbaPicture rgba -> copy(rgba.pixels(), rgba.stride(), 4, c, planeWidth, planeRows, sourceRows,
                        firstRow, step, samples);
                case Rgba16Picture rgba -> copy(rgba.pixels(), rgba.stride(), 4, c, planeWidth, planeRows,
                        sourceRows, firstRow, step, samples, depth);
                default -> throw new IllegalStateException("unconverted picture " + source.getClass());
            }
            var dx = chromaPlane ? 1 << chroma.shiftX() : 1;
            var dy = chromaPlane ? 1 << chroma.shiftY() : 1;
            var opacity = c == 3;
            channels.add(new Jp2Channel(0, 0, planeWidth, planeRows, dx, dy, depth, false,
                    opacity ? Jp2ChannelType.OPACITY : Jp2ChannelType.COLOR, opacity ? 0 : c + 1, samples));
        }
        return new Jp2Image(0, 0, width, rows, width, rows, 0, channels, colorSpace, Optional.empty(),
                Optional.empty(), Optional.empty());
    }

    /**
     * Returns a reused sample buffer of an exact length.
     *
     * @param slot the buffer set
     * @param component the component index
     * @param length the number of samples
     * @return the buffer
     */
    private int[] buffer(int slot, int component, int length) {
        var buffer = buffers[slot][component];
        if (buffer == null || buffer.length != length) {
            buffer = new int[length];
            buffers[slot][component] = buffer;
        }
        return buffer;
    }

    /**
     * Copies the rows of an 8-bit plane, or of one channel of interleaved 8-bit pixels, into samples.
     *
     * @param plane the samples
     * @param stride the row stride, in elements
     * @param pixelStep the elements per pixel
     * @param channel the index of the channel within a pixel
     * @param planeWidth the samples per row
     * @param rows the number of rows to copy
     * @param sourceRows the number of rows of the plane
     * @param firstRow the first row copied
     * @param step the row step
     * @param out receives the samples, row-major
     */
    private static void copy(byte[] plane, int stride, int pixelStep, int channel, int planeWidth, int rows,
                             int sourceRows, int firstRow, int step, int[] out) {
        for (var r = 0; r < rows; r++) {
            var base = sourceRow(r, sourceRows, firstRow, step) * stride + channel;
            var at = r * planeWidth;
            for (var x = 0; x < planeWidth; x++) {
                out[at + x] = plane[base + x * pixelStep] & 0xFF;
            }
        }
    }

    /**
     * Copies the rows of a 16-bit plane, or of one channel of interleaved 16-bit pixels, into samples.
     *
     * @param plane the samples
     * @param stride the row stride, in elements
     * @param pixelStep the elements per pixel
     * @param channel the index of the channel within a pixel
     * @param planeWidth the samples per row
     * @param rows the number of rows to copy
     * @param sourceRows the number of rows of the plane
     * @param firstRow the first row copied
     * @param step the row step
     * @param out receives the samples, row-major
     * @param depth the depth 16-bit samples are rescaled to, below 16, or 0 or 16 to copy them as they are
     */
    private static void copy(short[] plane, int stride, int pixelStep, int channel, int planeWidth, int rows,
                             int sourceRows, int firstRow, int step, int[] out, int depth) {
        var max = depth > 0 && depth < 16 ? (1L << depth) - 1 : 0;
        for (var r = 0; r < rows; r++) {
            var base = sourceRow(r, sourceRows, firstRow, step) * stride + channel;
            var at = r * planeWidth;
            for (var x = 0; x < planeWidth; x++) {
                var value = plane[base + x * pixelStep] & 0xFFFF;
                out[at + x] = max == 0 ? value : (int) ((value * max * 2 + 65535) / (2 * 65535));
            }
        }
    }

    /**
     * Returns the plane row an image row comes from: {@code firstRow + r * step}, or, past the plane, its last row
     * of the same parity (its last row when it has none).
     *
     * @param r the image row
     * @param sourceRows the number of rows of the plane
     * @param firstRow the first row copied
     * @param step the row step
     * @return the plane row
     */
    private static int sourceRow(int r, int sourceRows, int firstRow, int step) {
        var row = firstRow + r * step;
        if (row < sourceRows) {
            return row;
        }
        var last = sourceRows - 1;
        if (step == 2 && (last & 1) != firstRow && last > 0) {
            last--;
        }
        return last;
    }

    /**
     * Writes a codestream as a Contiguous Codestream box.
     *
     * @param data the destination
     * @param at the index of the box
     * @param codestream the bytes of the codestream, from its {@code SOC} marker
     * @return the index after the box
     */
    private static int box(byte[] data, int at, byte[] codestream) {
        var size = 8 + codestream.length;
        data[at] = (byte) (size >>> 24);
        data[at + 1] = (byte) (size >>> 16);
        data[at + 2] = (byte) (size >>> 8);
        data[at + 3] = (byte) size;
        data[at + 4] = 'j';
        data[at + 5] = 'p';
        data[at + 6] = '2';
        data[at + 7] = 'c';
        System.arraycopy(codestream, 0, data, at + 8, codestream.length);
        return at + size;
    }
}
