package com.github.auties00.smalt.video;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Encodes pictures into the uncompressed frames of a {@link Codec#RAW_VIDEO} track in one {@link RawVideoFormat}.
 *
 * <p>Every frame becomes one key frame packet of {@link RawVideoFormat#frameSize(int, int)} bytes, row padding zero,
 * with the frame's timestamp as presentation and decoding time. Pictures are first converted to the representation
 * of the format: YUV formats take a picture of their chroma format and depth as is (and the planes of a
 * {@link RawPixelFormat#YUV411P} frame every other chroma sample of a 4:2:2 picture), and convert any other picture
 * to the encoder's color description with {@link ImageOps#toYuv(Picture, ChromaFormat, ColorInfo)} or
 * {@link ImageOps#toYuv16(Picture, ChromaFormat, ColorInfo, int)}; RGB formats convert with
 * {@link ImageOps#toRgba(Picture)} or {@link ImageOps#toRgba16(Picture)}, the 5- and 6-bit components keeping the
 * high bits. A picture decoded by {@link RawVideoDecoder} from a frame of the same format encodes back to the same
 * frame, padding aside. Instances are not thread-safe.
 */
public final class RawVideoEncoder implements VideoEncoder {
    private final RawVideoFormat format;

    private final int width;

    private final int height;

    private final Rational timeBase;

    private final ColorInfo color;

    private final long frameSize;

    /**
     * The twelve components of one {@code v210} group of six pixels.
     */
    private final int[] groupComponents = new int[12];

    /**
     * Constructs an encoder.
     *
     * @param format the frame format, not a palette format
     * @param width the picture width, positive
     * @param height the picture height, positive
     * @param frameRate the frame rate; the time base is its inverse
     * @param color the color description YUV formats convert other pictures to, or {@code null} for BT.601 limited
     * range ({@link RawPixelFormat#GRAY}: full range)
     * @throws IllegalArgumentException if the format is a palette format, a dimension is not positive, the
     * frame would exceed the largest Java array, or the frame rate is not positive
     */
    public RawVideoEncoder(RawVideoFormat format, int width, int height, Rational frameRate, ColorInfo color) {
        this.format = Objects.requireNonNull(format, "format");
        Objects.requireNonNull(frameRate, "frameRate");
        if (format.pixelFormat().indexBits() != 0) {
            throw new IllegalArgumentException("palette frames cannot be encoded");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("invalid picture size " + width + "x" + height);
        }
        if (frameRate.numerator() <= 0 || frameRate.denominator() <= 0) {
            throw new IllegalArgumentException("invalid frame rate " + frameRate);
        }
        this.frameSize = format.frameSize(width, height);
        if (frameSize > Integer.MAX_VALUE - 16) {
            throw new IllegalArgumentException("a " + width + "x" + height + " frame is too large");
        }
        this.width = width;
        this.height = height;
        this.timeBase = frameRate.invert();
        this.color = color != null ? color : format.pixelFormat() == RawPixelFormat.GRAY ? ColorInfo.JPEG
                : ColorInfo.BT601_LIMITED;
    }

    /**
     * {@return the frame format}
     */
    public RawVideoFormat format() {
        return format;
    }

    @Override
    public Codec codec() {
        return Codec.RAW_VIDEO;
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
        return timeBase;
    }

    /**
     * {@return the format's {@link RawVideoFormat#toCodecConfig() codec configuration}}
     */
    @Override
    public Optional<byte[]> codecConfig() {
        return Optional.of(format.toCodecConfig());
    }

    /**
     * {@inheritDoc}
     *
     * <p>Every frame is coded at once, so this method emits exactly one packet.
     */
    @Override
    public void encode(VideoFrame frame, Consumer<Packet> output) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(output, "output");
        var picture = frame.picture();
        if (picture.width() != width || picture.height() != height) {
            throw new IllegalArgumentException("a " + picture.width() + "x" + picture.height()
                    + " picture does not match the " + width + "x" + height + " encoder");
        }
        var data = new byte[(int) frameSize];
        var pixelFormat = format.pixelFormat();
        switch (pixelFormat.kind()) {
            case PLANAR, SEMI_PLANAR, PACKED_422 -> {
                if (format.bitDepth() > 8) {
                    planar16(data, yuv16(picture));
                } else if (pixelFormat.kind() == RawPixelFormat.Kind.PLANAR) {
                    planar8(data, yuv8(picture));
                } else if (pixelFormat.kind() == RawPixelFormat.Kind.SEMI_PLANAR) {
                    semiPlanar(data, yuv8(picture));
                } else {
                    packed422(data, yuv8(picture));
                }
            }
            case V210 -> v210(data, yuv16(picture));
            case RGB -> {
                if (format.bitDepth() > 8) {
                    rgb16(data, picture instanceof Rgba16Picture deep ? deep : ImageOps.toRgba16(picture));
                } else {
                    rgb8(data, picture instanceof RgbaPicture rgba ? rgba : ImageOps.toRgba(picture));
                }
            }
            case PALETTE -> throw new IllegalStateException("palette frames cannot be encoded");
        }
        var duration = frame.duration() >= 0 ? frame.duration() : Track.UNKNOWN_DURATION;
        output.accept(new Packet(0, data, frame.pts(), frame.pts(), duration, true));
    }

    /**
     * Holds no frames; does nothing.
     */
    @Override
    public void flush(Consumer<Packet> output) {
        Objects.requireNonNull(output, "output");
    }

    /**
     * Returns a picture as 8-bit YUV of the format's chroma format.
     *
     * @param picture the picture to encode, of any type
     * @return the YUV picture
     */
    private YuvPicture yuv8(Picture picture) {
        var chroma = format.pixelFormat().chromaFormat();
        if (picture instanceof YuvPicture yuv && yuv.chromaFormat() == chroma) {
            return yuv;
        }
        if (picture instanceof Yuv16Picture deep && deep.chromaFormat() == chroma) {
            return ImageOps.toEightBit(deep);
        }
        return ImageOps.toYuv(picture, chroma, color);
    }

    /**
     * Returns a picture as YUV of the format's chroma format and depth.
     *
     * @param picture the picture to encode, of any type
     * @return the YUV picture
     */
    private Yuv16Picture yuv16(Picture picture) {
        var chroma = format.pixelFormat().chromaFormat();
        var depth = format.bitDepth();
        if (picture instanceof Yuv16Picture deep && deep.chromaFormat() == chroma) {
            return deep.bitDepth() == depth ? deep : ImageOps.toBitDepth(deep, depth);
        }
        if (picture instanceof YuvPicture yuv && yuv.chromaFormat() == chroma) {
            return ImageOps.toBitDepth(yuv, depth);
        }
        return ImageOps.toYuv16(picture, chroma, color, depth);
    }

    /**
     * Returns the offset of the first byte of a row of a plane.
     *
     * @param plane the index of the plane in the frame
     * @param row the row, top to bottom
     * @return the offset in the frame
     */
    private int rowOffset(int plane, int row) {
        var start = 0L;
        for (var i = 0; i < plane; i++) {
            start += format.stride(i, width) * format.rows(i, height);
        }
        var rows = format.rows(plane, height);
        var stored = format.bottomUp() ? rows - 1 - row : row;
        return (int) (start + stored * format.stride(plane, width));
    }

    /**
     * Writes an 8-bit planar frame.
     *
     * @param data the frame
     * @param yuv the picture
     */
    private void planar8(byte[] data, YuvPicture yuv) {
        var pixelFormat = format.pixelFormat();
        var swapped = pixelFormat == RawPixelFormat.YVU420P;
        for (var plane = 0; plane < format.planes(); plane++) {
            var source = plane == 0 || plane == 3 ? plane : swapped ? 3 - plane : plane;
            var samples = (int) format.rowBytes(plane, width);
            for (var row = 0; row < format.rows(plane, height); row++) {
                var offset = rowOffset(plane, row);
                switch (source) {
                    case 0 -> System.arraycopy(yuv.y(), row * yuv.yStride(), data, offset, samples);
                    case 3 -> {
                        if (yuv.alpha() != null) {
                            System.arraycopy(yuv.alpha(), row * yuv.alphaStride(), data, offset, samples);
                        } else {
                            Arrays.fill(data, offset, offset + samples, (byte) 0xFF);
                        }
                    }
                    default -> {
                        var array = source == 1 ? yuv.u() : yuv.v();
                        var base = row * yuv.uvStride();
                        if (pixelFormat == RawPixelFormat.YUV411P) {
                            for (var x = 0; x < samples; x++) {
                                data[offset + x] = array[base + 2 * x];
                            }
                        } else {
                            System.arraycopy(array, base, data, offset, samples);
                        }
                    }
                }
            }
        }
    }

    /**
     * Writes a planar frame of two-byte samples.
     *
     * @param data the frame
     * @param yuv the picture
     */
    private void planar16(byte[] data, Yuv16Picture yuv) {
        var pixelFormat = format.pixelFormat();
        var swapped = pixelFormat == RawPixelFormat.YVU420P;
        var big = format.bigEndian();
        var opaque = (1 << format.bitDepth()) - 1;
        for (var plane = 0; plane < format.planes(); plane++) {
            var source = plane == 0 || plane == 3 ? plane : swapped ? 3 - plane : plane;
            var samples = (int) (format.rowBytes(plane, width) / 2);
            for (var row = 0; row < format.rows(plane, height); row++) {
                var offset = rowOffset(plane, row);
                for (var x = 0; x < samples; x++) {
                    int value;
                    switch (source) {
                        case 0 -> value = yuv.y()[row * yuv.yStride() + x];
                        case 3 -> value = yuv.alpha() != null ? yuv.alpha()[row * yuv.alphaStride() + x] : opaque;
                        default -> {
                            var array = source == 1 ? yuv.u() : yuv.v();
                            var column = pixelFormat == RawPixelFormat.YUV411P ? 2 * x : x;
                            value = array[row * yuv.uvStride() + column];
                        }
                    }
                    put16(data, offset + 2 * x, value, big);
                }
            }
        }
    }

    /**
     * Stores a two-byte sample.
     *
     * @param data the bytes
     * @param offset the index of the sample
     * @param value the sample
     * @param bigEndian whether to store it big-endian
     */
    private static void put16(byte[] data, int offset, int value, boolean bigEndian) {
        if (bigEndian) {
            data[offset] = (byte) (value >>> 8);
            data[offset + 1] = (byte) value;
        } else {
            data[offset] = (byte) value;
            data[offset + 1] = (byte) (value >>> 8);
        }
    }

    /**
     * Writes an NV12 or NV21 frame.
     *
     * @param data the frame
     * @param yuv the picture
     */
    private void semiPlanar(byte[] data, YuvPicture yuv) {
        for (var row = 0; row < height; row++) {
            System.arraycopy(yuv.y(), row * yuv.yStride(), data, rowOffset(0, row), width);
        }
        var first = format.pixelFormat() == RawPixelFormat.NV12 ? yuv.u() : yuv.v();
        var second = format.pixelFormat() == RawPixelFormat.NV12 ? yuv.v() : yuv.u();
        for (var row = 0; row < yuv.chromaHeight(); row++) {
            var offset = rowOffset(1, row);
            var base = row * yuv.uvStride();
            for (var x = 0; x < yuv.chromaWidth(); x++) {
                data[offset + 2 * x] = first[base + x];
                data[offset + 2 * x + 1] = second[base + x];
            }
        }
    }

    /**
     * Writes a packed 4:2:2 frame; the second luma sample of an odd width's last pair repeats the first.
     *
     * @param data the frame
     * @param yuv the picture
     */
    private void packed422(byte[] data, YuvPicture yuv) {
        var pixelFormat = format.pixelFormat();
        for (var row = 0; row < height; row++) {
            var offset = rowOffset(0, row);
            var lumaBase = row * yuv.yStride();
            var chromaBase = row * yuv.uvStride();
            for (var pair = 0; pair < (width + 1) / 2; pair++) {
                var first = yuv.y()[lumaBase + 2 * pair];
                var second = 2 * pair + 1 < width ? yuv.y()[lumaBase + 2 * pair + 1] : first;
                var u = yuv.u()[chromaBase + pair];
                var v = yuv.v()[chromaBase + pair];
                var at = offset + 4 * pair;
                switch (pixelFormat) {
                    case UYVY422 -> {
                        data[at] = u;
                        data[at + 1] = first;
                        data[at + 2] = v;
                        data[at + 3] = second;
                    }
                    case YUYV422 -> {
                        data[at] = first;
                        data[at + 1] = u;
                        data[at + 2] = second;
                        data[at + 3] = v;
                    }
                    default -> {
                        data[at] = first;
                        data[at + 1] = v;
                        data[at + 2] = second;
                        data[at + 3] = u;
                    }
                }
            }
        }
    }

    /**
     * Writes a {@code v210} frame; the components past the picture edge in the last group of a row repeat the
     * row's last samples.
     *
     * @param data the frame
     * @param yuv the 10-bit 4:2:2 picture
     */
    private void v210(byte[] data, Yuv16Picture yuv) {
        var components = groupComponents;
        var chromaWidth = yuv.chromaWidth();
        for (var row = 0; row < height; row++) {
            var offset = rowOffset(0, row);
            var lumaBase = row * yuv.yStride();
            var chromaBase = row * yuv.uvStride();
            for (var group = 0; group * 6 < width; group++) {
                for (var i = 0; i < 6; i++) {
                    components[2 * i + 1] = yuv.y()[lumaBase + Math.min(width - 1, group * 6 + i)] & 0x3FF;
                }
                for (var i = 0; i < 3; i++) {
                    var x = Math.min(chromaWidth - 1, group * 3 + i);
                    components[4 * i] = yuv.u()[chromaBase + x] & 0x3FF;
                    components[4 * i + 2] = yuv.v()[chromaBase + x] & 0x3FF;
                }
                for (var word = 0; word < 4; word++) {
                    var value = components[3 * word] | components[3 * word + 1] << 10 | components[3 * word + 2] << 20;
                    var at = offset + 16 * group + 4 * word;
                    data[at] = (byte) value;
                    data[at + 1] = (byte) (value >>> 8);
                    data[at + 2] = (byte) (value >>> 16);
                    data[at + 3] = (byte) (value >>> 24);
                }
            }
        }
    }

    /**
     * Writes an 8-bit RGB frame.
     *
     * @param data the frame
     * @param rgba the picture
     */
    private void rgb8(byte[] data, RgbaPicture rgba) {
        var pixelFormat = format.pixelFormat();
        var pixels = rgba.pixels();
        for (var row = 0; row < height; row++) {
            var offset = rowOffset(0, row);
            var base = row * rgba.stride();
            for (var x = 0; x < width; x++) {
                var in = base + 4 * x;
                var r = pixels[in];
                var g = pixels[in + 1];
                var b = pixels[in + 2];
                var a = rgba.hasAlpha() ? pixels[in + 3] : (byte) 0xFF;
                switch (pixelFormat) {
                    case RGB24 -> put(data, offset + 3 * x, r, g, b);
                    case BGR24 -> put(data, offset + 3 * x, b, g, r);
                    case RGBA -> put(data, offset + 4 * x, r, g, b, a);
                    case BGRA -> put(data, offset + 4 * x, b, g, r, a);
                    case ARGB -> put(data, offset + 4 * x, a, r, g, b);
                    case ABGR -> put(data, offset + 4 * x, a, b, g, r);
                    case RGB0 -> put(data, offset + 4 * x, r, g, b, (byte) 0);
                    case BGR0 -> put(data, offset + 4 * x, b, g, r, (byte) 0);
                    default -> {
                        var red = (r & 0xFF) >>> 3;
                        var blue = (b & 0xFF) >>> 3;
                        var six = pixelFormat == RawPixelFormat.RGB565LE || pixelFormat == RawPixelFormat.RGB565BE;
                        var value = six ? red << 11 | ((g & 0xFF) >>> 2) << 5 | blue
                                : red << 10 | ((g & 0xFF) >>> 3) << 5 | blue;
                        var big = pixelFormat == RawPixelFormat.RGB565BE || pixelFormat == RawPixelFormat.RGB555BE;
                        put16(data, offset + 2 * x, value, big);
                    }
                }
            }
        }
    }

    /**
     * Writes a 16-bit RGB frame.
     *
     * @param data the frame
     * @param rgba the picture
     */
    private void rgb16(byte[] data, Rgba16Picture rgba) {
        var pixelFormat = format.pixelFormat();
        var pixels = rgba.pixels();
        var big = pixelFormat != RawPixelFormat.RGB48LE;
        var components = pixelFormat == RawPixelFormat.RGB48BE || pixelFormat == RawPixelFormat.RGB48LE ? 3 : 4;
        for (var row = 0; row < height; row++) {
            var offset = rowOffset(0, row);
            var base = row * rgba.stride();
            for (var x = 0; x < width; x++) {
                var in = base + 4 * x;
                var at = offset + 2 * components * x;
                var alpha = rgba.hasAlpha() ? pixels[in + 3] & 0xFFFF : 0xFFFF;
                if (pixelFormat == RawPixelFormat.ARGB64BE) {
                    put16(data, at, alpha, true);
                    put16(data, at + 2, pixels[in] & 0xFFFF, true);
                    put16(data, at + 4, pixels[in + 1] & 0xFFFF, true);
                    put16(data, at + 6, pixels[in + 2] & 0xFFFF, true);
                } else {
                    put16(data, at, pixels[in] & 0xFFFF, big);
                    put16(data, at + 2, pixels[in + 1] & 0xFFFF, big);
                    put16(data, at + 4, pixels[in + 2] & 0xFFFF, big);
                    if (components == 4) {
                        put16(data, at + 6, alpha, big);
                    }
                }
            }
        }
    }

    /**
     * Stores three bytes.
     *
     * @param data the bytes
     * @param at the index of the first
     * @param first the first byte
     * @param second the second byte
     * @param third the third byte
     */
    private static void put(byte[] data, int at, byte first, byte second, byte third) {
        data[at] = first;
        data[at + 1] = second;
        data[at + 2] = third;
    }

    /**
     * Stores four bytes.
     *
     * @param data the bytes
     * @param at the index of the first
     * @param first the first byte
     * @param second the second byte
     * @param third the third byte
     * @param fourth the fourth byte
     */
    private static void put(byte[] data, int at, byte first, byte second, byte third, byte fourth) {
        data[at] = first;
        data[at + 1] = second;
        data[at + 2] = third;
        data[at + 3] = fourth;
    }
}
