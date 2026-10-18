package com.github.auties00.smalt.image.ops;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.internal.task.ParallelBands;

/**
 * Applies the eight orientation transforms to pictures, plane by plane.
 *
 * <p>With {@code (x, y)} a source pixel of a {@code w x h} picture, the transforms write it to:
 * <ul>
 *   <li>{@link Orientation#FLIP_HORIZONTAL}: {@code (w - 1 - x, y)};</li>
 *   <li>{@link Orientation#ROTATE_180}: {@code (w - 1 - x, h - 1 - y)};</li>
 *   <li>{@link Orientation#FLIP_VERTICAL}: {@code (x, h - 1 - y)};</li>
 *   <li>{@link Orientation#TRANSPOSE}: {@code (y, x)};</li>
 *   <li>{@link Orientation#ROTATE_90}: {@code (h - 1 - y, x)};</li>
 *   <li>{@link Orientation#TRANSVERSE}: {@code (h - 1 - y, w - 1 - x)};</li>
 *   <li>{@link Orientation#ROTATE_270}: {@code (y, w - 1 - x)}.</li>
 * </ul>
 *
 * <p>Large planes are transformed in bands on several threads: bands of source rows for the
 * copies and flips, bands of source columns (whole tiles of the transpose kernel) for the
 * quarter turns. Each band writes its own destination rows, so the result does not depend on
 * the split.
 */
final class Orienter {
    /**
     * Prevents instantiation.
     */
    private Orienter() {
        throw new AssertionError();
    }

    /**
     * Applies a transform other than {@link Orientation#NORMAL}.
     *
     * @param picture the picture to transform
     * @param orientation the transform
     * @return the transformed picture
     */
    static Picture orient(Picture picture, Orientation orientation) {
        return switch (picture) {
            case RgbaPicture rgba -> orientRgba(rgba, orientation);
            case YuvPicture yuv -> orientYuv(yuv, orientation);
            case Rgba16Picture rgba -> HighDepth.orient(rgba, orientation);
            case Yuv16Picture yuv -> HighDepth.orient(yuv, orientation);
        };
    }

    /**
     * Applies a transform to a packed RGBA picture.
     *
     * @param rgba the picture
     * @param orientation the transform
     * @return the transformed picture
     */
    private static RgbaPicture orientRgba(RgbaPicture rgba, Orientation orientation) {
        var width = rgba.width();
        var height = rgba.height();
        var swap = orientation.swapsDimensions();
        OpChecks.checkArray(4L * width * height);
        var out = RgbaPicture.allocate(swap ? height : width, swap ? width : height, rgba.hasAlpha());
        transform(rgba.pixels(), rgba.stride(), width, height, out.pixels(), out.stride(), orientation, true);
        return out;
    }

    /**
     * Applies a transform to a planar picture.
     *
     * @param yuv the picture
     * @param orientation the transform
     * @return the transformed picture
     */
    private static YuvPicture orientYuv(YuvPicture yuv, Orientation orientation) {
        var width = yuv.width();
        var height = yuv.height();
        var swap = orientation.swapsDimensions();
        var format = yuv.chromaFormat();
        var source = yuv;
        if (swap && format == ChromaFormat.YUV422) {
            source = widenChroma(yuv);
            format = ChromaFormat.YUV444;
        }
        var out = YuvPicture.allocate(swap ? height : width, swap ? width : height, format, yuv.hasAlpha(), yuv.color());
        transform(source.y(), source.yStride(), width, height, out.y(), out.yStride(), orientation, false);
        if (format.hasChroma()) {
            var chromaWidth = source.chromaWidth();
            var chromaHeight = source.chromaHeight();
            transform(source.u(), source.uvStride(), chromaWidth, chromaHeight, out.u(), out.uvStride(), orientation, false);
            transform(source.v(), source.uvStride(), chromaWidth, chromaHeight, out.v(), out.uvStride(), orientation, false);
        }
        if (yuv.hasAlpha()) {
            transform(source.alpha(), source.alphaStride(), width, height, out.alpha(), out.alphaStride(), orientation, false);
        }
        return out;
    }

    /**
     * Converts a 4:2:2 picture to 4:4:4 by replicating every chroma sample over the two columns
     * it covers; the luma and alpha planes are shared, not copied.
     *
     * @param yuv the 4:2:2 picture
     * @return the 4:4:4 picture
     */
    private static YuvPicture widenChroma(YuvPicture yuv) {
        var width = yuv.width();
        var height = yuv.height();
        var u = new byte[width * height];
        var v = new byte[width * height];
        for (var row = 0; row < height; row++) {
            var in = row * yuv.uvStride();
            var out = row * width;
            for (var x = 0; x < width; x++) {
                u[out + x] = yuv.u()[in + (x >> 1)];
                v[out + x] = yuv.v()[in + (x >> 1)];
            }
        }
        return yuv.toBuilder()
                .chromaFormat(ChromaFormat.YUV444)
                .chroma(u, v, width)
                .build();
    }

    /**
     * Applies a transform to one plane of 8-bit samples or 32-bit pixels.
     *
     * @param src the source bytes
     * @param srcStride the source row stride in bytes
     * @param width the source width in pixels
     * @param height the source height in pixels
     * @param dst the destination bytes
     * @param dstStride the destination row stride in bytes
     * @param orientation the transform
     * @param wide whether pixels are 32 bits instead of 8
     */
    private static void transform(byte[] src, int srcStride, int width, int height, byte[] dst, int dstStride,
                                  Orientation orientation, boolean wide) {
        var work = (long) width * height * (wide ? 4 : 1);
        switch (orientation) {
            case TRANSPOSE, ROTATE_90, TRANSVERSE, ROTATE_270 -> {
                var units = (width + TransformKernel.BLOCK - 1) / TransformKernel.BLOCK;
                var bands = ParallelBands.bands(units, work);
                ParallelBands.run(bands, band -> {
                    var x0 = Math.min(width, ParallelBands.start(band, bands, units) * TransformKernel.BLOCK);
                    var x1 = Math.min(width, ParallelBands.start(band + 1, bands, units) * TransformKernel.BLOCK);
                    if (x0 < x1) {
                        transposeColumns(src, srcStride, width, height, dst, dstStride, orientation, wide, x0, x1);
                    }
                });
            }
            default -> {
                var bands = ParallelBands.bands(height, work);
                ParallelBands.run(bands, band -> transformRows(src, srcStride, width, height, dst, dstStride, orientation,
                        wide, ParallelBands.start(band, bands, height), ParallelBands.start(band + 1, bands, height)));
            }
        }
    }

    /**
     * Applies a copy or flip to the source rows {@code [y0, y1)} of one plane.
     *
     * @param src the source bytes
     * @param srcStride the source row stride in bytes
     * @param width the source width in pixels
     * @param height the source height in pixels
     * @param dst the destination bytes
     * @param dstStride the destination row stride in bytes
     * @param orientation the transform, one that keeps rows as rows
     * @param wide whether pixels are 32 bits instead of 8
     * @param y0 the first source row
     * @param y1 the source row after the last one
     */
    private static void transformRows(byte[] src, int srcStride, int width, int height, byte[] dst, int dstStride,
                                      Orientation orientation, boolean wide, int y0, int y1) {
        var kernel = TransformKernel.INSTANCE;
        var rowBytes = wide ? 4 * width : width;
        switch (orientation) {
            case FLIP_HORIZONTAL, ROTATE_180 -> {
                var flip = orientation == Orientation.ROTATE_180;
                for (var y = y0; y < y1; y++) {
                    var target = (flip ? height - 1 - y : y) * dstStride;
                    if (wide) {
                        kernel.reverse32(src, y * srcStride, dst, target, width);
                    } else {
                        kernel.reverse8(src, y * srcStride, dst, target, width);
                    }
                }
            }
            case FLIP_VERTICAL -> {
                for (var y = y0; y < y1; y++) {
                    System.arraycopy(src, y * srcStride, dst, (height - 1 - y) * dstStride, rowBytes);
                }
            }
            default -> {
                for (var y = y0; y < y1; y++) {
                    System.arraycopy(src, y * srcStride, dst, y * dstStride, rowBytes);
                }
            }
        }
    }

    /**
     * Applies a quarter turn to the source columns {@code [x0, x1)} of one plane, which become
     * destination rows {@code [x0, x1)}, or {@code [width - x1, width - x0)} when the turn reverses
     * the row order.
     *
     * @param src the source bytes
     * @param srcStride the source row stride in bytes
     * @param width the source width in pixels
     * @param height the source height in pixels
     * @param dst the destination bytes
     * @param dstStride the destination row stride in bytes
     * @param orientation the transform, one of the quarter turns
     * @param wide whether pixels are 32 bits instead of 8
     * @param x0 the first source column
     * @param x1 the source column after the last one
     */
    private static void transposeColumns(byte[] src, int srcStride, int width, int height, byte[] dst, int dstStride,
                                         Orientation orientation, boolean wide, int x0, int x1) {
        var kernel = TransformKernel.INSTANCE;
        var reverseRows = orientation == Orientation.TRANSVERSE || orientation == Orientation.ROTATE_270;
        var reverseColumns = orientation == Orientation.ROTATE_90 || orientation == Orientation.TRANSVERSE;
        var dOff = (reverseRows ? width - x1 : x0) * dstStride;
        if (wide) {
            kernel.transpose32(src, 4 * x0, srcStride, x1 - x0, height, dst, dOff, dstStride, reverseRows, reverseColumns);
        } else {
            kernel.transpose8(src, x0, srcStride, x1 - x0, height, dst, dOff, dstStride, reverseRows, reverseColumns);
        }
    }
}
