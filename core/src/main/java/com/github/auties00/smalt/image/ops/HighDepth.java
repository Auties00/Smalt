package com.github.auties00.smalt.image.ops;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.internal.plane.Narrow;
import com.github.auties00.smalt.internal.plane.Planes;
import com.github.auties00.smalt.internal.task.ParallelBands;

/**
 * Implements the operations of {@link ImageOps} on pictures with 16-bit storage: bit depth
 * conversions, cropping, orientation and the opacity scan. Arguments are validated by the caller.
 *
 * <p>Y'CbCr samples change depth by shifting, as video decoders and encoders do: a sample gains
 * {@code n} bits as {@code v << n} and loses them with rounding to nearest,
 * {@code min((v + (1 << (n - 1))) >> n, max)}, which maps the limited range of one depth exactly
 * onto the limited range of the other. Alpha samples and packed RGBA samples, which are always
 * full range, are rescaled exactly instead: {@code round(v * maxOut / maxIn)}, so opaque stays
 * opaque.
 */
final class HighDepth {
    /**
     * The largest shift the shared narrowing kernel takes, which reduces 12-bit samples.
     */
    private static final int MAX_KERNEL_SHIFT = 4;

    /**
     * Prevents instantiation.
     */
    private HighDepth() {
        throw new AssertionError();
    }

    /**
     * Reduces a planar picture to 8-bit samples.
     *
     * @param picture the picture to reduce
     * @return a new 8-bit picture with the same layout and color description
     */
    static YuvPicture toEightBit(Yuv16Picture picture) {
        var width = picture.width();
        var height = picture.height();
        var format = picture.chromaFormat();
        var depth = picture.bitDepth();
        var out = YuvPicture.allocate(width, height, format, picture.hasAlpha(), picture.color());
        narrowPlane(picture.y(), picture.yStride(), out.y(), out.yStride(), width, height, depth);
        if (format.hasChroma()) {
            var chromaWidth = out.chromaWidth();
            var chromaHeight = out.chromaHeight();
            narrowPlane(picture.u(), picture.uvStride(), out.u(), out.uvStride(), chromaWidth, chromaHeight, depth);
            narrowPlane(picture.v(), picture.uvStride(), out.v(), out.uvStride(), chromaWidth, chromaHeight, depth);
        }
        if (picture.hasAlpha()) {
            var max = (1 << depth) - 1;
            var alpha = picture.alpha();
            var stride = picture.alphaStride();
            var target = out.alpha();
            for (var row = 0; row < height; row++) {
                for (var x = 0; x < width; x++) {
                    target[row * width + x] = (byte) rescale(alpha[row * stride + x] & 0xFFFF, max, 255);
                }
            }
        }
        return out;
    }

    /**
     * Reduces a packed picture to 8-bit samples, each becoming {@code (v * 255 + 32767) / 65535}.
     *
     * @param picture the picture to reduce
     * @return a new 8-bit picture
     */
    static RgbaPicture toEightBit(Rgba16Picture picture) {
        var width = picture.width();
        var height = picture.height();
        OpChecks.checkArray(4L * width * height);
        var out = RgbaPicture.allocate(width, height, picture.hasAlpha());
        var source = picture.pixels();
        var stride = picture.stride();
        var target = out.pixels();
        var bands = ParallelBands.bands(height, 4L * width * height);
        ParallelBands.run(bands, band -> {
            var last = ParallelBands.start(band + 1, bands, height);
            for (var row = ParallelBands.start(band, bands, height); row < last; row++) {
                var in = row * stride;
                var outRow = row * width * 4;
                for (var i = 0; i < width * 4; i++) {
                    target[outRow + i] = (byte) (((source[in + i] & 0xFFFF) * 255 + 32767) / 65535);
                }
            }
        });
        return out;
    }

    /**
     * Widens a packed 8-bit picture to 16-bit samples, each becoming {@code v * 257}.
     *
     * @param picture the picture to widen
     * @return a new 16-bit picture
     */
    static Rgba16Picture toSixteenBit(RgbaPicture picture) {
        var width = picture.width();
        var height = picture.height();
        OpChecks.checkArray(4L * width * height);
        var out = Rgba16Picture.allocate(width, height, picture.hasAlpha());
        var source = picture.pixels();
        var stride = picture.stride();
        var target = out.pixels();
        for (var row = 0; row < height; row++) {
            var in = row * stride;
            var outRow = row * width * 4;
            for (var i = 0; i < width * 4; i++) {
                target[outRow + i] = (short) ((source[in + i] & 0xFF) * 257);
            }
        }
        return out;
    }

    /**
     * Converts an 8-bit planar picture to the given depth.
     *
     * @param picture the picture to convert
     * @param bitDepth the target depth, 8 to 16
     * @return a new picture
     */
    static Yuv16Picture toBitDepth(YuvPicture picture, int bitDepth) {
        var width = picture.width();
        var height = picture.height();
        var format = picture.chromaFormat();
        OpChecks.checkArray((long) width * height);
        var out = Yuv16Picture.allocate(width, height, format, bitDepth, picture.hasAlpha(), picture.color());
        var shift = bitDepth - 8;
        widenPlane(picture.y(), picture.yStride(), out.y(), out.yStride(), width, height, shift);
        if (format.hasChroma()) {
            var chromaWidth = out.chromaWidth();
            var chromaHeight = out.chromaHeight();
            widenPlane(picture.u(), picture.uvStride(), out.u(), out.uvStride(), chromaWidth, chromaHeight, shift);
            widenPlane(picture.v(), picture.uvStride(), out.v(), out.uvStride(), chromaWidth, chromaHeight, shift);
        }
        if (picture.hasAlpha()) {
            var max = (1 << bitDepth) - 1;
            var alpha = picture.alpha();
            var stride = picture.alphaStride();
            var target = out.alpha();
            for (var row = 0; row < height; row++) {
                for (var x = 0; x < width; x++) {
                    target[row * width + x] = (short) rescale(alpha[row * stride + x] & 0xFF, 255, max);
                }
            }
        }
        return out;
    }

    /**
     * Converts a planar picture to another depth.
     *
     * @param picture the picture to convert
     * @param bitDepth the target depth, 8 to 16
     * @return a new picture
     */
    static Yuv16Picture toBitDepth(Yuv16Picture picture, int bitDepth) {
        var width = picture.width();
        var height = picture.height();
        var format = picture.chromaFormat();
        var depth = picture.bitDepth();
        OpChecks.checkArray((long) width * height);
        var out = Yuv16Picture.allocate(width, height, format, bitDepth, picture.hasAlpha(), picture.color());
        repackPlane(picture.y(), picture.yStride(), out.y(), out.yStride(), width, height, depth, bitDepth);
        if (format.hasChroma()) {
            var chromaWidth = out.chromaWidth();
            var chromaHeight = out.chromaHeight();
            repackPlane(picture.u(), picture.uvStride(), out.u(), out.uvStride(), chromaWidth, chromaHeight, depth, bitDepth);
            repackPlane(picture.v(), picture.uvStride(), out.v(), out.uvStride(), chromaWidth, chromaHeight, depth, bitDepth);
        }
        if (picture.hasAlpha()) {
            var maxIn = (1 << depth) - 1;
            var maxOut = (1 << bitDepth) - 1;
            var alpha = picture.alpha();
            var stride = picture.alphaStride();
            var target = out.alpha();
            for (var row = 0; row < height; row++) {
                for (var x = 0; x < width; x++) {
                    target[row * width + x] = (short) rescale(alpha[row * stride + x] & 0xFFFF, maxIn, maxOut);
                }
            }
        }
        return out;
    }

    /**
     * Rescales a full-range sample between two maxima, rounding to nearest.
     *
     * @param value the sample, {@code 0} to {@code maxIn}
     * @param maxIn the largest input sample
     * @param maxOut the largest output sample
     * @return the rescaled sample, clamped to {@code 0} to {@code maxOut}
     */
    private static int rescale(int value, int maxIn, int maxOut) {
        return (int) Math.min(maxOut, ((long) value * maxOut + maxIn / 2) / maxIn);
    }

    /**
     * Reduces one plane to 8 bits by rounding to nearest, in parallel bands of rows for a large
     * plane.
     *
     * @implNote This implementation uses the shared narrowing kernel up to 12 bits and an unsigned scalar loop above,
     * where the kernel's shift range ends.
     * @param src the source samples
     * @param srcStride the source stride
     * @param dst the destination samples
     * @param dstStride the destination stride
     * @param width the plane width
     * @param height the plane height
     * @param depth the source depth, 8 to 16
     */
    private static void narrowPlane(short[] src, int srcStride, byte[] dst, int dstStride, int width, int height,
                                    int depth) {
        var shift = depth - 8;
        if (shift <= MAX_KERNEL_SHIFT) {
            Narrow.plane(src, 0, srcStride, dst, 0, dstStride, width, height, shift, true);
            return;
        }
        var half = shift == 0 ? 0 : 1 << (shift - 1);
        var bands = ParallelBands.bands(height, (long) width * height);
        ParallelBands.run(bands, band -> {
            var last = ParallelBands.start(band + 1, bands, height);
            for (var row = ParallelBands.start(band, bands, height); row < last; row++) {
                var in = row * srcStride;
                var out = row * dstStride;
                for (var x = 0; x < width; x++) {
                    dst[out + x] = (byte) Math.min(((src[in + x] & 0xFFFF) + half) >> shift, 255);
                }
            }
        });
    }

    /**
     * Widens one 8-bit plane by shifting every sample left.
     *
     * @param src the source samples
     * @param srcStride the source stride
     * @param dst the destination samples
     * @param dstStride the destination stride
     * @param width the plane width
     * @param height the plane height
     * @param shift the number of bits to add, 0 to 8
     */
    private static void widenPlane(byte[] src, int srcStride, short[] dst, int dstStride, int width, int height,
                                   int shift) {
        for (var row = 0; row < height; row++) {
            var in = row * srcStride;
            var out = row * dstStride;
            for (var x = 0; x < width; x++) {
                dst[out + x] = (short) ((src[in + x] & 0xFF) << shift);
            }
        }
    }

    /**
     * Converts one plane between two depths, shifting left to widen and rounding to nearest to
     * narrow.
     *
     * @param src the source samples
     * @param srcStride the source stride
     * @param dst the destination samples
     * @param dstStride the destination stride
     * @param width the plane width
     * @param height the plane height
     * @param from the source depth
     * @param to the target depth
     */
    private static void repackPlane(short[] src, int srcStride, short[] dst, int dstStride, int width, int height,
                                    int from, int to) {
        var max = (1 << to) - 1;
        for (var row = 0; row < height; row++) {
            var in = row * srcStride;
            var out = row * dstStride;
            for (var x = 0; x < width; x++) {
                var value = src[in + x] & 0xFFFF;
                if (to >= from) {
                    dst[out + x] = (short) (value << (to - from));
                } else {
                    var shift = from - to;
                    dst[out + x] = (short) Math.min((value + (1 << (shift - 1))) >> shift, max);
                }
            }
        }
    }

    /**
     * Copies a rectangle of a planar picture, as {@link ImageOps#crop} describes.
     *
     * @param picture the picture to copy from
     * @param x the rectangle's left edge
     * @param y the rectangle's top edge
     * @param width the rectangle's width
     * @param height the rectangle's height
     * @return the cropped picture
     */
    static Yuv16Picture crop(Yuv16Picture picture, int x, int y, int width, int height) {
        OpChecks.checkArray((long) width * height);
        var format = picture.chromaFormat();
        var out = Yuv16Picture.allocate(width, height, format, picture.bitDepth(), picture.hasAlpha(), picture.color());
        Planes.copy(picture.y(), y * picture.yStride() + x, picture.yStride(), out.y(), 0, out.yStride(), width, height);
        if (format.hasChroma()) {
            var offset = (y >> format.shiftY()) * picture.uvStride() + (x >> format.shiftX());
            Planes.copy(picture.u(), offset, picture.uvStride(), out.u(), 0, out.uvStride(), out.chromaWidth(),
                    out.chromaHeight());
            Planes.copy(picture.v(), offset, picture.uvStride(), out.v(), 0, out.uvStride(), out.chromaWidth(),
                    out.chromaHeight());
        }
        if (picture.hasAlpha()) {
            Planes.copy(picture.alpha(), y * picture.alphaStride() + x, picture.alphaStride(), out.alpha(), 0,
                    out.alphaStride(), width, height);
        }
        return out;
    }

    /**
     * Copies a rectangle of a packed picture.
     *
     * @param picture the picture to copy from
     * @param x the rectangle's left edge
     * @param y the rectangle's top edge
     * @param width the rectangle's width
     * @param height the rectangle's height
     * @return the cropped picture
     */
    static Rgba16Picture crop(Rgba16Picture picture, int x, int y, int width, int height) {
        OpChecks.checkArray(4L * width * height);
        var out = Rgba16Picture.allocate(width, height, picture.hasAlpha());
        Planes.copy(picture.pixels(), y * picture.stride() + 4 * x, picture.stride(), out.pixels(), 0, out.stride(),
                4 * width, height);
        return out;
    }

    /**
     * Applies a transform other than {@link Orientation#NORMAL} to a planar picture, as
     * {@link ImageOps#orient} describes.
     *
     * @param picture the picture to transform
     * @param orientation the transform
     * @return the transformed picture
     */
    static Yuv16Picture orient(Yuv16Picture picture, Orientation orientation) {
        var width = picture.width();
        var height = picture.height();
        var swap = orientation.swapsDimensions();
        var format = picture.chromaFormat();
        var source = picture;
        if (swap && format == ChromaFormat.YUV422) {
            source = widenChroma(picture);
            format = ChromaFormat.YUV444;
        }
        OpChecks.checkArray((long) width * height);
        var out = Yuv16Picture.allocate(swap ? height : width, swap ? width : height, format, picture.bitDepth(),
                picture.hasAlpha(), picture.color());
        transform(source.y(), source.yStride(), width, height, 1, out.y(), out.yStride(), orientation);
        if (format.hasChroma()) {
            var chromaWidth = source.chromaWidth();
            var chromaHeight = source.chromaHeight();
            transform(source.u(), source.uvStride(), chromaWidth, chromaHeight, 1, out.u(), out.uvStride(), orientation);
            transform(source.v(), source.uvStride(), chromaWidth, chromaHeight, 1, out.v(), out.uvStride(), orientation);
        }
        if (picture.hasAlpha()) {
            transform(source.alpha(), source.alphaStride(), width, height, 1, out.alpha(), out.alphaStride(), orientation);
        }
        return out;
    }

    /**
     * Applies a transform other than {@link Orientation#NORMAL} to a packed picture.
     *
     * @param picture the picture to transform
     * @param orientation the transform
     * @return the transformed picture
     */
    static Rgba16Picture orient(Rgba16Picture picture, Orientation orientation) {
        var width = picture.width();
        var height = picture.height();
        var swap = orientation.swapsDimensions();
        OpChecks.checkArray(4L * width * height);
        var out = Rgba16Picture.allocate(swap ? height : width, swap ? width : height, picture.hasAlpha());
        transform(picture.pixels(), picture.stride(), width, height, 4, out.pixels(), out.stride(), orientation);
        return out;
    }

    /**
     * Converts a 4:2:2 picture to 4:4:4 by replicating every chroma sample over the two columns
     * it covers; the luma and alpha planes are shared, not copied.
     *
     * @param picture the 4:2:2 picture
     * @return the 4:4:4 picture
     */
    private static Yuv16Picture widenChroma(Yuv16Picture picture) {
        var width = picture.width();
        var height = picture.height();
        var u = new short[width * height];
        var v = new short[width * height];
        for (var row = 0; row < height; row++) {
            var in = row * picture.uvStride();
            var out = row * width;
            for (var x = 0; x < width; x++) {
                u[out + x] = picture.u()[in + (x >> 1)];
                v[out + x] = picture.v()[in + (x >> 1)];
            }
        }
        return picture.toBuilder()
                .chromaFormat(ChromaFormat.YUV444)
                .chroma(u, v, width)
                .build();
    }

    /**
     * Applies a transform to one plane of pixels made of {@code elements} consecutive samples.
     *
     * @param src the source samples
     * @param srcStride the source row stride in samples
     * @param width the source width in pixels
     * @param height the source height in pixels
     * @param elements the samples per pixel
     * @param dst the destination samples
     * @param dstStride the destination row stride in samples
     * @param orientation the transform
     */
    private static void transform(short[] src, int srcStride, int width, int height, int elements, short[] dst,
                                  int dstStride, Orientation orientation) {
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                int tx;
                int ty;
                switch (orientation) {
                    case FLIP_HORIZONTAL -> {
                        tx = width - 1 - x;
                        ty = y;
                    }
                    case ROTATE_180 -> {
                        tx = width - 1 - x;
                        ty = height - 1 - y;
                    }
                    case FLIP_VERTICAL -> {
                        tx = x;
                        ty = height - 1 - y;
                    }
                    case TRANSPOSE -> {
                        tx = y;
                        ty = x;
                    }
                    case ROTATE_90 -> {
                        tx = height - 1 - y;
                        ty = x;
                    }
                    case TRANSVERSE -> {
                        tx = height - 1 - y;
                        ty = width - 1 - x;
                    }
                    case ROTATE_270 -> {
                        tx = y;
                        ty = width - 1 - x;
                    }
                    default -> {
                        tx = x;
                        ty = y;
                    }
                }
                System.arraycopy(src, y * srcStride + x * elements, dst, ty * dstStride + tx * elements, elements);
            }
        }
    }

    /**
     * Returns whether every alpha sample of a picture with alpha is at its maximum.
     *
     * @param picture the picture to inspect
     * @return whether no pixel is transparent
     */
    static boolean isOpaque(Yuv16Picture picture) {
        var max = (1 << picture.bitDepth()) - 1;
        var alpha = picture.alpha();
        for (var row = 0; row < picture.height(); row++) {
            var offset = row * picture.alphaStride();
            for (var x = 0; x < picture.width(); x++) {
                if ((alpha[offset + x] & 0xFFFF) < max) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Returns whether every alpha sample of a packed picture is {@code 65535}.
     *
     * @param picture the picture to inspect
     * @return whether no pixel is transparent
     */
    static boolean isOpaque(Rgba16Picture picture) {
        var pixels = picture.pixels();
        for (var row = 0; row < picture.height(); row++) {
            var offset = row * picture.stride();
            for (var x = 0; x < picture.width(); x++) {
                if (pixels[offset + 4 * x + 3] != (short) 0xFFFF) {
                    return false;
                }
            }
        }
        return true;
    }
}
