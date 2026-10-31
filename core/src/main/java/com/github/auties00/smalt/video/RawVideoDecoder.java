package com.github.auties00.smalt.video;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.VideoTrack;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Decodes the packets of a {@link Codec#RAW_VIDEO} track, one uncompressed frame each, into pictures.
 *
 * <p>The track's codec configuration gives the {@link RawVideoFormat} and the track its width and height. Every
 * packet holds one frame of {@link RawVideoFormat#frameSize(int, int)} bytes or more (bytes past the frame are
 * ignored) and decodes to one frame with the packet's timestamp and key frame flag. The picture type follows the
 * pixel format:
 * <ul>
 *   <li>planar, semi-planar and packed YUV formats decode to a {@link YuvPicture} at a depth of 8 bits and to a
 *       {@link Yuv16Picture} of the format's depth otherwise ({@link RawPixelFormat#V210} at 10 bits), with the
 *       track's color description or, when it has none, BT.601 limited range ({@link RawPixelFormat#GRAY}: full
 *       range); {@link RawPixelFormat#YUV411P} decodes to 4:2:2 by repeating each chroma sample;</li>
 *   <li>8-bit RGB, 16-bit-pixel RGB and palette formats decode to an {@link RgbaPicture}, the 5- and 6-bit
 *       components widened by repeating their high bits, and the 16-bit RGB formats to an {@link Rgba16Picture};
 *       the alpha of formats without one is opaque.</li>
 * </ul>
 * Decoding is a copy of the samples; the output never depends on anything but the packet. Instances are not
 * thread-safe.
 */
public final class RawVideoDecoder implements VideoDecoder {
    private final RawVideoFormat format;

    private final int width;

    private final int height;

    private final ColorInfo color;

    private final long frameSize;

    /**
     * The picture the frames are decoded into, reused from frame to frame.
     */
    private final Picture picture;

    /**
     * The twelve components of one {@code v210} group of six pixels.
     */
    private final int[] groupComponents = new int[12];

    /**
     * The palette of a palette format, or {@code null}.
     */
    private final int[] palette;

    /**
     * Constructs a decoder for a track.
     *
     * @param track the track, of {@link Codec#RAW_VIDEO} with its size and configuration
     * @param options the limits
     * @throws IllegalArgumentException if the track is not a raw video track
     * @throws MediaException.Malformed if the track has no size or no valid configuration
     * @throws MediaException.Unsupported if the configuration names an unknown pixel format
     * @throws MediaException.LimitExceeded if the picture size exceeds the limits
     */
    public RawVideoDecoder(VideoTrack track, VideoDecoderOptions options) {
        Objects.requireNonNull(track, "track");
        Objects.requireNonNull(options, "options");
        if (track.codec() != Codec.RAW_VIDEO) {
            throw new IllegalArgumentException("not a raw video track: " + track.codec().displayName());
        }
        this.format = RawVideoFormat.fromCodecConfig(track.codecConfig().orElseThrow(
                () -> new MediaException.Malformed("a raw video track needs its codec configuration")));
        this.width = track.width();
        this.height = track.height();
        if (width <= 0 || height <= 0) {
            throw new MediaException.Malformed("a raw video track needs its picture size");
        }
        LimitChecks.checkPicture(options.limits(), width, height);
        this.frameSize = format.frameSize(width, height);
        LimitChecks.checkAllocation(options.limits(), frameSize);
        this.palette = format.palette().orElse(null);
        var pixelFormat = format.pixelFormat();
        this.color = track.color().orElse(pixelFormat == RawPixelFormat.GRAY ? ColorInfo.JPEG
                : ColorInfo.BT601_LIMITED);
        var deep = format.bitDepth() > 8;
        if (pixelFormat.isYuv()) {
            this.picture = deep ? Yuv16Picture.allocate(width, height, pixelFormat.chromaFormat(), format.bitDepth(),
                    pixelFormat.hasAlpha(), color)
                    : YuvPicture.allocate(width, height, pixelFormat.chromaFormat(), pixelFormat.hasAlpha(), color);
        } else if (deep) {
            this.picture = Rgba16Picture.allocate(width, height, pixelFormat.hasAlpha());
        } else {
            this.picture = RgbaPicture.allocate(width, height, pixelFormat.hasAlpha());
        }
    }

    /**
     * Constructs a decoder for a track with {@link VideoDecoderOptions#defaults()}.
     *
     * @param track the track, of {@link Codec#RAW_VIDEO} with its size and configuration
     * @throws IllegalArgumentException if the track is not a raw video track
     * @throws MediaException.Malformed if the track has no size or no valid configuration
     * @throws MediaException.Unsupported if the configuration names an unknown pixel format
     * @throws MediaException.LimitExceeded if the picture size exceeds the limits
     */
    public RawVideoDecoder(VideoTrack track) {
        this(track, VideoDecoderOptions.defaults());
    }

    /**
     * {@return the format of the frames}
     */
    public RawVideoFormat format() {
        return format;
    }

    /**
     * {@inheritDoc}
     *
     * @throws MediaException.Malformed if the packet is shorter than one frame
     */
    @Override
    public void decode(Packet packet, Consumer<VideoFrame> output) {
        Objects.requireNonNull(packet, "packet");
        Objects.requireNonNull(output, "output");
        var data = packet.data();
        if (data.length < frameSize) {
            throw new MediaException.Malformed("a raw video packet of " + data.length + " bytes is shorter than its "
                    + frameSize + " byte frame");
        }
        switch (format.pixelFormat().kind()) {
            case PLANAR -> planar(data);
            case SEMI_PLANAR -> semiPlanar(data);
            case PACKED_422 -> packed422(data);
            case V210 -> v210(data);
            case RGB -> rgb(data);
            case PALETTE -> palette(data);
        }
        output.accept(new VideoFrame(picture, packet.pts(), packet.duration() >= 0 ? packet.duration()
                : Track.UNKNOWN_DURATION, packet.keyframe()));
    }

    /**
     * Holds no frames; does nothing.
     */
    @Override
    public void flush(Consumer<VideoFrame> output) {
        Objects.requireNonNull(output, "output");
    }

    /**
     * Returns the offset of the first byte of a row of a plane.
     *
     * @param plane the index of the plane in the frame
     * @param row the row, top to bottom
     * @return the offset in the frame
     */
    private long rowOffset(int plane, int row) {
        var start = 0L;
        for (var i = 0; i < plane; i++) {
            start += format.stride(i, width) * format.rows(i, height);
        }
        var rows = format.rows(plane, height);
        var stored = format.bottomUp() ? rows - 1 - row : row;
        return start + stored * format.stride(plane, width);
    }

    /**
     * Decodes a planar frame.
     *
     * @param data the frame
     */
    private void planar(byte[] data) {
        var pixelFormat = format.pixelFormat();
        var swapped = pixelFormat == RawPixelFormat.YVU420P;
        var planes = format.planes();
        for (var plane = 0; plane < planes; plane++) {
            var target = plane == 0 || plane == 3 ? plane : swapped ? 3 - plane : plane;
            var rows = format.rows(plane, height);
            var samples = (int) (format.rowBytes(plane, width) / (format.bitDepth() > 8 ? 2 : 1));
            for (var row = 0; row < rows; row++) {
                var offset = (int) rowOffset(plane, row);
                if (picture instanceof YuvPicture yuv) {
                    var array = plane8(yuv, target);
                    var stride = stride8(yuv, target);
                    if (pixelFormat == RawPixelFormat.YUV411P && target != 0) {
                        var chromaWidth = yuv.chromaWidth();
                        for (var x = 0; x < chromaWidth; x++) {
                            array[row * stride + x] = data[offset + x / 2];
                        }
                    } else {
                        System.arraycopy(data, offset, array, row * stride, samples);
                    }
                } else {
                    var yuv = (Yuv16Picture) picture;
                    var array = plane16(yuv, target);
                    var stride = stride16(yuv, target);
                    var max = (1 << format.bitDepth()) - 1;
                    var big = format.bigEndian();
                    var base = row * stride;
                    if (pixelFormat == RawPixelFormat.YUV411P && target != 0) {
                        var chromaWidth = yuv.chromaWidth();
                        for (var x = 0; x < chromaWidth; x++) {
                            array[base + x] = (short) Math.min(max, sample16(data, offset + (x / 2) * 2, big));
                        }
                    } else {
                        for (var x = 0; x < samples; x++) {
                            array[base + x] = (short) Math.min(max, sample16(data, offset + 2 * x, big));
                        }
                    }
                }
            }
        }
    }

    /**
     * Reads a two-byte sample.
     *
     * @param data the bytes
     * @param offset the index of the sample
     * @param bigEndian whether the sample is big-endian
     * @return the sample
     */
    private static int sample16(byte[] data, int offset, boolean bigEndian) {
        return bigEndian ? (data[offset] & 0xFF) << 8 | (data[offset + 1] & 0xFF)
                : (data[offset] & 0xFF) | (data[offset + 1] & 0xFF) << 8;
    }

    /**
     * Returns a plane of an 8-bit picture.
     *
     * @param yuv the picture
     * @param plane 0 for Y, 1 for U, 2 for V, 3 for alpha
     * @return the samples
     */
    private static byte[] plane8(YuvPicture yuv, int plane) {
        return switch (plane) {
            case 0 -> yuv.y();
            case 1 -> yuv.u();
            case 2 -> yuv.v();
            default -> yuv.alpha();
        };
    }

    /**
     * Returns the stride of a plane of an 8-bit picture.
     *
     * @param yuv the picture
     * @param plane 0 for Y, 1 for U, 2 for V, 3 for alpha
     * @return the stride
     */
    private static int stride8(YuvPicture yuv, int plane) {
        return switch (plane) {
            case 0 -> yuv.yStride();
            case 3 -> yuv.alphaStride();
            default -> yuv.uvStride();
        };
    }

    /**
     * Returns a plane of a deep picture.
     *
     * @param yuv the picture
     * @param plane 0 for Y, 1 for U, 2 for V, 3 for alpha
     * @return the samples
     */
    private static short[] plane16(Yuv16Picture yuv, int plane) {
        return switch (plane) {
            case 0 -> yuv.y();
            case 1 -> yuv.u();
            case 2 -> yuv.v();
            default -> yuv.alpha();
        };
    }

    /**
     * Returns the stride of a plane of a deep picture.
     *
     * @param yuv the picture
     * @param plane 0 for Y, 1 for U, 2 for V, 3 for alpha
     * @return the stride
     */
    private static int stride16(Yuv16Picture yuv, int plane) {
        return switch (plane) {
            case 0 -> yuv.yStride();
            case 3 -> yuv.alphaStride();
            default -> yuv.uvStride();
        };
    }

    /**
     * Decodes an NV12 or NV21 frame.
     *
     * @param data the frame
     */
    private void semiPlanar(byte[] data) {
        var yuv = (YuvPicture) picture;
        for (var row = 0; row < height; row++) {
            System.arraycopy(data, (int) rowOffset(0, row), yuv.y(), row * yuv.yStride(), width);
        }
        var first = format.pixelFormat() == RawPixelFormat.NV12 ? yuv.u() : yuv.v();
        var second = format.pixelFormat() == RawPixelFormat.NV12 ? yuv.v() : yuv.u();
        var chromaWidth = yuv.chromaWidth();
        for (var row = 0; row < yuv.chromaHeight(); row++) {
            var offset = (int) rowOffset(1, row);
            var base = row * yuv.uvStride();
            for (var x = 0; x < chromaWidth; x++) {
                first[base + x] = data[offset + 2 * x];
                second[base + x] = data[offset + 2 * x + 1];
            }
        }
    }

    /**
     * Decodes a packed 4:2:2 frame.
     *
     * @param data the frame
     */
    private void packed422(byte[] data) {
        var yuv = (YuvPicture) picture;
        int lumaFirst;
        int lumaSecond;
        int uIndex;
        int vIndex;
        switch (format.pixelFormat()) {
            case UYVY422 -> {
                uIndex = 0;
                lumaFirst = 1;
                vIndex = 2;
                lumaSecond = 3;
            }
            case YUYV422 -> {
                lumaFirst = 0;
                uIndex = 1;
                lumaSecond = 2;
                vIndex = 3;
            }
            default -> {
                lumaFirst = 0;
                vIndex = 1;
                lumaSecond = 2;
                uIndex = 3;
            }
        }
        var y = yuv.y();
        var u = yuv.u();
        var v = yuv.v();
        var pairs = (width + 1) / 2;
        for (var row = 0; row < height; row++) {
            var offset = (int) rowOffset(0, row);
            var lumaBase = row * yuv.yStride();
            var chromaBase = row * yuv.uvStride();
            for (var pair = 0; pair < pairs; pair++) {
                var at = offset + 4 * pair;
                y[lumaBase + 2 * pair] = data[at + lumaFirst];
                if (2 * pair + 1 < width) {
                    y[lumaBase + 2 * pair + 1] = data[at + lumaSecond];
                }
                u[chromaBase + pair] = data[at + uIndex];
                v[chromaBase + pair] = data[at + vIndex];
            }
        }
    }

    /**
     * Decodes a {@code v210} frame: every group of six pixels is four little-endian words holding, from the low
     * bits, {@code Cb0 Y0 Cr0}, {@code Y1 Cb1 Y2}, {@code Cr1 Y3 Cb2}, {@code Y4 Cr2 Y5}.
     *
     * @param data the frame
     */
    private void v210(byte[] data) {
        var yuv = (Yuv16Picture) picture;
        var y = yuv.y();
        var u = yuv.u();
        var v = yuv.v();
        var chromaWidth = yuv.chromaWidth();
        var components = groupComponents;
        for (var row = 0; row < height; row++) {
            var offset = (int) rowOffset(0, row);
            var lumaBase = row * yuv.yStride();
            var chromaBase = row * yuv.uvStride();
            for (var group = 0; group * 6 < width; group++) {
                for (var word = 0; word < 4; word++) {
                    var at = offset + 16 * group + 4 * word;
                    var value = (data[at] & 0xFF) | (data[at + 1] & 0xFF) << 8 | (data[at + 2] & 0xFF) << 16
                            | (data[at + 3] & 0xFF) << 24;
                    components[3 * word] = value & 0x3FF;
                    components[3 * word + 1] = (value >>> 10) & 0x3FF;
                    components[3 * word + 2] = (value >>> 20) & 0x3FF;
                }
                // component order within the group: Cb0 Y0 Cr0 Y1 Cb1 Y2 Cr1 Y3 Cb2 Y4 Cr2 Y5
                for (var i = 0; i < 6; i++) {
                    var x = group * 6 + i;
                    if (x < width) {
                        y[lumaBase + x] = (short) components[2 * i + 1];
                    }
                }
                for (var i = 0; i < 3; i++) {
                    var x = group * 3 + i;
                    if (x < chromaWidth) {
                        u[chromaBase + x] = (short) components[4 * i];
                        v[chromaBase + x] = (short) components[4 * i + 2];
                    }
                }
            }
        }
    }

    /**
     * Decodes an RGB frame.
     *
     * @param data the frame
     */
    private void rgb(byte[] data) {
        var pixelFormat = format.pixelFormat();
        if (picture instanceof Rgba16Picture deep) {
            var pixels = deep.pixels();
            var big = pixelFormat != RawPixelFormat.RGB48LE;
            var components = pixelFormat == RawPixelFormat.RGB48BE || pixelFormat == RawPixelFormat.RGB48LE ? 3 : 4;
            var alphaFirst = pixelFormat == RawPixelFormat.ARGB64BE;
            for (var row = 0; row < height; row++) {
                var offset = (int) rowOffset(0, row);
                var base = row * deep.stride();
                for (var x = 0; x < width; x++) {
                    var at = offset + 2 * components * x;
                    var out = base + 4 * x;
                    if (alphaFirst) {
                        pixels[out + 3] = (short) sample16(data, at, true);
                        pixels[out] = (short) sample16(data, at + 2, true);
                        pixels[out + 1] = (short) sample16(data, at + 4, true);
                        pixels[out + 2] = (short) sample16(data, at + 6, true);
                    } else {
                        pixels[out] = (short) sample16(data, at, big);
                        pixels[out + 1] = (short) sample16(data, at + 2, big);
                        pixels[out + 2] = (short) sample16(data, at + 4, big);
                        pixels[out + 3] = components == 4 ? (short) sample16(data, at + 6, big) : (short) 0xFFFF;
                    }
                }
            }
            return;
        }
        var rgba = (RgbaPicture) picture;
        var pixels = rgba.pixels();
        for (var row = 0; row < height; row++) {
            var offset = (int) rowOffset(0, row);
            var base = row * rgba.stride();
            switch (pixelFormat) {
                case RGB24 -> {
                    for (var x = 0; x < width; x++) {
                        put(pixels, base + 4 * x, data[offset + 3 * x], data[offset + 3 * x + 1],
                                data[offset + 3 * x + 2], (byte) 0xFF);
                    }
                }
                case BGR24 -> {
                    for (var x = 0; x < width; x++) {
                        put(pixels, base + 4 * x, data[offset + 3 * x + 2], data[offset + 3 * x + 1],
                                data[offset + 3 * x], (byte) 0xFF);
                    }
                }
                case RGBA -> System.arraycopy(data, offset, pixels, base, 4 * width);
                case BGRA, BGR0 -> {
                    var opaque = pixelFormat == RawPixelFormat.BGR0;
                    for (var x = 0; x < width; x++) {
                        var at = offset + 4 * x;
                        put(pixels, base + 4 * x, data[at + 2], data[at + 1], data[at], opaque ? (byte) 0xFF
                                : data[at + 3]);
                    }
                }
                case ARGB -> {
                    for (var x = 0; x < width; x++) {
                        var at = offset + 4 * x;
                        put(pixels, base + 4 * x, data[at + 1], data[at + 2], data[at + 3], data[at]);
                    }
                }
                case ABGR -> {
                    for (var x = 0; x < width; x++) {
                        var at = offset + 4 * x;
                        put(pixels, base + 4 * x, data[at + 3], data[at + 2], data[at + 1], data[at]);
                    }
                }
                case RGB0 -> {
                    for (var x = 0; x < width; x++) {
                        var at = offset + 4 * x;
                        put(pixels, base + 4 * x, data[at], data[at + 1], data[at + 2], (byte) 0xFF);
                    }
                }
                default -> {
                    var big = pixelFormat == RawPixelFormat.RGB565BE || pixelFormat == RawPixelFormat.RGB555BE;
                    var six = pixelFormat == RawPixelFormat.RGB565LE || pixelFormat == RawPixelFormat.RGB565BE;
                    for (var x = 0; x < width; x++) {
                        var value = sample16(data, offset + 2 * x, big);
                        int r;
                        int g;
                        int b = value & 0x1F;
                        if (six) {
                            r = (value >>> 11) & 0x1F;
                            g = (value >>> 5) & 0x3F;
                            g = g << 2 | g >>> 4;
                        } else {
                            r = (value >>> 10) & 0x1F;
                            g = (value >>> 5) & 0x1F;
                            g = g << 3 | g >>> 2;
                        }
                        put(pixels, base + 4 * x, (byte) (r << 3 | r >>> 2), (byte) g, (byte) (b << 3 | b >>> 2),
                                (byte) 0xFF);
                    }
                }
            }
        }
    }

    /**
     * Decodes a palette frame.
     *
     * @param data the frame
     */
    private void palette(byte[] data) {
        var rgba = (RgbaPicture) picture;
        var pixels = rgba.pixels();
        var bits = format.pixelFormat().indexBits();
        var perByte = 8 / bits;
        var mask = (1 << bits) - 1;
        for (var row = 0; row < height; row++) {
            var offset = (int) rowOffset(0, row);
            var base = row * rgba.stride();
            for (var x = 0; x < width; x++) {
                var shift = 8 - bits * (x % perByte + 1);
                var index = (data[offset + x / perByte] & 0xFF) >>> shift & mask;
                var entry = index < palette.length ? palette[index] : 0xFF000000;
                put(pixels, base + 4 * x, (byte) (entry >>> 16), (byte) (entry >>> 8), (byte) entry,
                        (byte) (entry >>> 24));
            }
        }
    }

    /**
     * Stores one RGBA pixel.
     *
     * @param pixels the samples
     * @param at the index of the pixel
     * @param r the red sample
     * @param g the green sample
     * @param b the blue sample
     * @param a the alpha sample
     */
    private static void put(byte[] pixels, int at, byte r, byte g, byte b, byte a) {
        pixels[at] = r;
        pixels[at + 1] = g;
        pixels[at + 2] = b;
        pixels[at + 3] = a;
    }
}
