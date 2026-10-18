package com.github.auties00.smalt.image.ops;

import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.internal.task.ParallelBands;

/**
 * Converts a planar picture to another matrix, and optionally another range, without leaving
 * Y'CbCr, for a layout with chroma that does not change.
 *
 * <p>The conversion is the affine map {@code target.forward * source.inverse} between the two
 * descriptions, applied to the samples minus the source offsets. A change between matrices that
 * keep grays gray leaves chroma independent of luma, so the chroma planes are converted sample by
 * sample at their own resolution. Each luma sample adds a chroma term computed from the chroma at
 * its position, reconstructed with the triangle filter (weights 3/4 and 1/4 per subsampled axis,
 * centered siting, edge samples replicated). Every result is rounded once, half up, and clamped to
 * a byte. The coefficients are fixed point with {@value #BITS} fractional bits.
 */
final class MatrixConversion {
    /**
     * The fractional bits of the coefficients.
     */
    private static final int BITS = 16;

    /**
     * The approximate number of operations per pixel, which weighs the work against
     * {@link ParallelBands#MIN_PARALLEL_WORK}.
     */
    private static final long WORK = 8;

    /**
     * Prevents instantiation.
     */
    private MatrixConversion() {
        throw new AssertionError();
    }

    /**
     * Returns whether two descriptions convert directly: both keep chroma independent of luma,
     * which holds for every supported matrix except identity (GBR).
     *
     * @param source the coefficients of the picture's color
     * @param target the coefficients of the target color
     * @return {@code true} when {@link #convert} applies
     */
    static boolean applies(ColorMatrix source, ColorMatrix target) {
        return source.offsets[1] == 128 && source.offsets[2] == 128
                && target.offsets[1] == 128 && target.offsets[2] == 128;
    }

    /**
     * Converts a planar picture with chroma to another color description in the same layout.
     *
     * @param yuv the picture, whose layout has chroma
     * @param color the target color description
     * @param source the coefficients of the picture's color
     * @param target the coefficients of the target color, for which {@link #applies} holds
     * @param buffers the source of the result
     * @return the converted picture
     */
    static YuvPicture convert(YuvPicture yuv, ColorInfo color, ColorMatrix source, ColorMatrix target,
                              PictureBuffers buffers) {
        var format = yuv.chromaFormat();
        var width = yuv.width();
        var height = yuv.height();
        var out = buffers.yuv(width, height, format, yuv.hasAlpha(), color);
        var a = new int[3][3];
        for (var row = 0; row < 3; row++) {
            for (var column = 0; column < 3; column++) {
                var sum = 0.0;
                for (var k = 0; k < 3; k++) {
                    sum += target.forward[row][k] * source.inverse[k][column];
                }
                a[row][column] = (int) Math.round(sum * (1 << BITS));
            }
        }
        var coefficients = new Coefficients(a, source.offsets[0], target.offsets[0]);
        var groups = out.chromaHeight();
        var bands = ParallelBands.bands(groups, WORK * width * height);
        ParallelBands.run(bands, band -> rows(yuv, out, coefficients, ParallelBands.start(band, bands, groups),
                ParallelBands.start(band + 1, bands, groups)));
        if (yuv.hasAlpha()) {
            for (var row = 0; row < height; row++) {
                System.arraycopy(yuv.alpha(), row * yuv.alphaStride(), out.alpha(), row * out.alphaStride(), width);
            }
        }
        return out;
    }

    /**
     * Holds the fixed-point map: luma from luma, Cb and Cr; Cb and Cr from Cb and Cr.
     *
     * @param yy the weight of the source luma in the luma
     * @param yu the weight of the source Cb in the luma
     * @param yv the weight of the source Cr in the luma
     * @param uu the weight of the source Cb in the Cb
     * @param uv the weight of the source Cr in the Cb
     * @param vu the weight of the source Cb in the Cr
     * @param vv the weight of the source Cr in the Cr
     * @param sourceOffset the source luma offset
     * @param targetOffset the target luma offset
     */
    private record Coefficients(int yy, int yu, int yv, int uu, int uv, int vu, int vv, int sourceOffset,
                                int targetOffset) {
        Coefficients(int[][] a, int sourceOffset, int targetOffset) {
            this(a[0][0], a[0][1], a[0][2], a[1][1], a[1][2], a[2][1], a[2][2], sourceOffset, targetOffset);
        }
    }

    /**
     * Converts the chroma rows {@code [first, last)} and the luma rows they cover.
     *
     * @param yuv the source picture
     * @param out the destination picture
     * @param c the coefficients
     * @param first the first chroma row
     * @param last the chroma row after the last one
     */
    private static void rows(YuvPicture yuv, YuvPicture out, Coefficients c, int first, int last) {
        var format = yuv.chromaFormat();
        var shiftY = format.shiftY();
        var lumaEnd = Math.min(yuv.height(), last << shiftY);
        for (var row = first << shiftY; row < lumaEnd; row++) {
            if (format.shiftX() == 0) {
                lumaRowFull(yuv, out, c, row);
            } else {
                lumaRowHalved(yuv, out, c, row);
            }
        }
        var chromaWidth = yuv.chromaWidth();
        var bias = (128 << BITS) + (1 << (BITS - 1));
        for (var row = first; row < last; row++) {
            var in = row * yuv.uvStride();
            var dst = row * out.uvStride();
            for (var x = 0; x < chromaWidth; x++) {
                var u = (yuv.u()[in + x] & 0xFF) - 128;
                var v = (yuv.v()[in + x] & 0xFF) - 128;
                out.u()[dst + x] = (byte) ScalarColorKernel.clamp((c.uu * u + c.uv * v + bias) >> BITS);
                out.v()[dst + x] = (byte) ScalarColorKernel.clamp((c.vu * u + c.vv * v + bias) >> BITS);
            }
        }
    }

    /**
     * Converts one luma row of a picture whose chroma has the luma width (4:4:4).
     *
     * @param yuv the source picture
     * @param out the destination picture
     * @param c the coefficients
     * @param row the luma row
     */
    private static void lumaRowFull(YuvPicture yuv, YuvPicture out, Coefficients c, int row) {
        var y = yuv.y();
        var u = yuv.u();
        var v = yuv.v();
        var in = row * yuv.yStride();
        var chroma = row * yuv.uvStride();
        var dst = row * out.yStride();
        var bias = (c.targetOffset << BITS) + (1 << (BITS - 1));
        for (var x = 0; x < yuv.width(); x++) {
            var luma = (y[in + x] & 0xFF) - c.sourceOffset;
            var cb = (u[chroma + x] & 0xFF) - 128;
            var cr = (v[chroma + x] & 0xFF) - 128;
            out.y()[dst + x] = (byte) ScalarColorKernel.clamp((c.yy * luma + c.yu * cb + c.yv * cr + bias) >> BITS);
        }
    }

    /**
     * Converts one luma row of a picture whose chroma is halved horizontally (4:2:2, 4:2:0),
     * reconstructing the chroma of each sample with the triangle filter.
     *
     * @param yuv the source picture
     * @param out the destination picture
     * @param c the coefficients
     * @param row the luma row
     */
    private static void lumaRowHalved(YuvPicture yuv, YuvPicture out, Coefficients c, int row) {
        var y = yuv.y();
        var u = yuv.u();
        var v = yuv.v();
        var width = yuv.width();
        var chromaWidth = yuv.chromaWidth();
        var halvedY = yuv.chromaFormat().shiftY() == 1;
        var nearRow = row >> yuv.chromaFormat().shiftY();
        var farRow = !halvedY ? nearRow : (row & 1) == 0 ? Math.max(nearRow - 1, 0)
                : Math.min(nearRow + 1, yuv.chromaHeight() - 1);
        var near = nearRow * yuv.uvStride();
        var far = farRow * yuv.uvStride();
        // the reconstructed chroma is scaled by 16 (both axes halved) or 4 (only the horizontal one)
        var scale = halvedY ? 4 : 2;
        var neutral = 128 << scale;
        var shift = BITS + scale;
        var bias = (c.targetOffset << shift) + (1 << (shift - 1));
        var in = row * yuv.yStride();
        var dst = row * out.yStride();
        for (var cx = 0; cx < chromaWidth; cx++) {
            var left = Math.max(cx - 1, 0);
            var right = Math.min(cx + 1, chromaWidth - 1);
            var cbHere = column(u, near, far, cx, halvedY);
            var crHere = column(v, near, far, cx, halvedY);
            var x = cx << 1;
            var cb = 3 * cbHere + column(u, near, far, left, halvedY) - neutral;
            var cr = 3 * crHere + column(v, near, far, left, halvedY) - neutral;
            var luma = (y[in + x] & 0xFF) - c.sourceOffset;
            out.y()[dst + x] = (byte) ScalarColorKernel.clamp(
                    ((c.yy * luma) << scale) + c.yu * cb + c.yv * cr + bias >> shift);
            if (x + 1 < width) {
                cb = 3 * cbHere + column(u, near, far, right, halvedY) - neutral;
                cr = 3 * crHere + column(v, near, far, right, halvedY) - neutral;
                luma = (y[in + x + 1] & 0xFF) - c.sourceOffset;
                out.y()[dst + x + 1] = (byte) ScalarColorKernel.clamp(
                        ((c.yy * luma) << scale) + c.yu * cb + c.yv * cr + bias >> shift);
            }
        }
    }

    /**
     * Returns the vertically reconstructed chroma of one column: {@code 3 * near + far} when the
     * chroma is halved vertically, the sample itself otherwise.
     *
     * @param plane the chroma plane
     * @param near the index of the nearer chroma row
     * @param far the index of the farther chroma row
     * @param column the chroma column
     * @param halvedY whether the chroma is halved vertically
     * @return the column value, scaled by 4 when halved vertically
     */
    private static int column(byte[] plane, int near, int far, int column, boolean halvedY) {
        var value = plane[near + column] & 0xFF;
        return halvedY ? 3 * value + (plane[far + column] & 0xFF) : value;
    }
}
