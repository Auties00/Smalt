package com.github.auties00.smalt.image.ops;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.YuvPicture;

import java.util.Arrays;

/**
 * Converts a planar picture to another chroma layout and range without leaving Y'CbCr, for
 * source and target descriptions that share the matrix.
 *
 * <p>Chroma planes are resampled per axis: kept, halved by averaging each pair of samples, or
 * doubled with the triangle filter (weights 3/4 and 1/4, centered siting, edge samples
 * replicated); an axis pair that is both halved is averaged over the whole 2x2 block, and every
 * result is rounded once, half up. A range change remaps every sample exactly through a lookup
 * table: {@code round((v - sourceOffset) * targetScale / sourceScale) + targetOffset}, clamped,
 * where {@code round} rounds halves away from zero so the mapping is symmetric about the
 * offset (limited-range chroma 16 and 240 map to full-range 0 and 255).
 */
final class PlaneConversion {
    /**
     * Prevents instantiation.
     */
    private PlaneConversion() {
        throw new AssertionError();
    }

    /**
     * Converts a planar picture.
     *
     * @param yuv the picture
     * @param format the target layout
     * @param color the target color description
     * @param source the coefficients of the picture's color
     * @param target the coefficients of the target color, with the same matrix
     * @param downsampling how to average chroma when halving
     * @param buffers the source of the result
     * @return the converted picture
     */
    static YuvPicture convert(YuvPicture yuv, ChromaFormat format, ColorInfo color, ColorMatrix source, ColorMatrix target,
                              ChromaDownsampling downsampling, PictureBuffers buffers) {
        var width = yuv.width();
        var height = yuv.height();
        var out = buffers.yuv(width, height, format, yuv.hasAlpha(), color);
        var lumaTable = table(source, target, 0);
        for (var row = 0; row < height; row++) {
            var in = row * yuv.yStride();
            var dst = row * out.yStride();
            for (var x = 0; x < width; x++) {
                out.y()[dst + x] = lumaTable[yuv.y()[in + x] & 0xFF];
            }
        }
        if (yuv.hasAlpha()) {
            for (var row = 0; row < height; row++) {
                System.arraycopy(yuv.alpha(), row * yuv.alphaStride(), out.alpha(), row * out.alphaStride(), width);
            }
        }
        if (!format.hasChroma()) {
            return out;
        }
        if (!yuv.chromaFormat().hasChroma()) {
            var neutral = (byte) target.offsets[1];
            Arrays.fill(out.u(), neutral);
            Arrays.fill(out.v(), neutral);
            return out;
        }
        int[] coverage = null;
        var downX = yuv.chromaFormat().shiftX() < format.shiftX();
        var downY = yuv.chromaFormat().shiftY() < format.shiftY();
        var upX = yuv.chromaFormat().shiftX() > format.shiftX();
        var upY = yuv.chromaFormat().shiftY() > format.shiftY();
        if (downsampling == ChromaDownsampling.ALPHA_WEIGHTED && yuv.hasAlpha() && (downX || downY) && !upX && !upY) {
            coverage = coverage(yuv);
        }
        resample(yuv.u(), yuv, out.u(), out, coverage, table(source, target, 1));
        resample(yuv.v(), yuv, out.v(), out, coverage, table(source, target, 2));
        return out;
    }

    /**
     * Builds the lookup table that remaps one component between two ranges.
     *
     * @param source    the source coefficients
     * @param target    the target coefficients
     * @param component {@code 0} for luma, {@code 1} for Cb, {@code 2} for Cr
     * @return the table, indexed by the unsigned source sample
     */
    private static byte[] table(ColorMatrix source, ColorMatrix target, int component) {
        var table = new byte[256];
        var ratio = target.scales[component] / source.scales[component];
        for (var v = 0; v < 256; v++) {
            var difference = (v - source.offsets[component]) * ratio;
            var rounded = (int) Math.signum(difference) * (int) Math.floor(Math.abs(difference) + 0.5);
            table[v] = (byte) ScalarColorKernel.clamp(rounded + target.offsets[component]);
        }
        return table;
    }

    /**
     * Sums the alpha of the pixels each source chroma sample covers.
     *
     * @param yuv the picture, which has an alpha plane
     * @return the coverage of every chroma sample, row by row with the chroma width as stride
     */
    private static int[] coverage(YuvPicture yuv) {
        var format = yuv.chromaFormat();
        var chromaWidth = yuv.chromaWidth();
        var coverage = new int[chromaWidth * yuv.chromaHeight()];
        for (var y = 0; y < yuv.height(); y++) {
            var row = (y >> format.shiftY()) * chromaWidth;
            for (var x = 0; x < yuv.width(); x++) {
                coverage[row + (x >> format.shiftX())] += yuv.alpha()[y * yuv.alphaStride() + x] & 0xFF;
            }
        }
        return coverage;
    }

    /**
     * Resamples one chroma plane between layouts and remaps it through a table.
     *
     * @param src      the source plane
     * @param in       the source picture, for the plane geometry
     * @param dst      the destination plane
     * @param out      the destination picture, for the plane geometry
     * @param coverage the alpha coverage of each source sample, or {@code null} for equal
     *                 weights
     * @param table    the range lookup table
     */
    private static void resample(byte[] src, YuvPicture in, byte[] dst, YuvPicture out, int[] coverage, byte[] table) {
        var sourceWidth = in.chromaWidth();
        var sourceHeight = in.chromaHeight();
        var stride = in.uvStride();
        var directionX = out.chromaFormat().shiftX() - in.chromaFormat().shiftX();
        var directionY = out.chromaFormat().shiftY() - in.chromaFormat().shiftY();
        var xs = new int[2];
        var ws = new int[2];
        var ys = new int[2];
        var hs = new int[2];
        for (var y = 0; y < out.chromaHeight(); y++) {
            var rows = taps(directionY, y, sourceHeight, ys, hs);
            for (var x = 0; x < out.chromaWidth(); x++) {
                var columns = taps(directionX, x, sourceWidth, xs, ws);
                long sum = 0;
                long total = 0;
                if (coverage != null) {
                    for (var j = 0; j < rows; j++) {
                        for (var i = 0; i < columns; i++) {
                            long weight = coverage[ys[j] * sourceWidth + xs[i]];
                            sum += weight * (src[ys[j] * stride + xs[i]] & 0xFF);
                            total += weight;
                        }
                    }
                }
                if (total == 0) {
                    sum = 0;
                    for (var j = 0; j < rows; j++) {
                        for (var i = 0; i < columns; i++) {
                            var weight = hs[j] * ws[i];
                            sum += (long) weight * (src[ys[j] * stride + xs[i]] & 0xFF);
                            total += weight;
                        }
                    }
                }
                var value = (int) ((2 * sum + total) / (2 * total));
                dst[y * out.uvStride() + x] = table[value];
            }
        }
    }

    /**
     * Computes the source taps of one output chroma sample along one axis.
     *
     * @param direction {@code 1} when the axis is halved, {@code -1} when it is doubled,
     *                  {@code 0} when it is kept
     * @param index     the output sample
     * @param size      the number of source samples
     * @param positions receives the source samples
     * @param weights   receives their weights
     * @return the number of taps
     */
    private static int taps(int direction, int index, int size, int[] positions, int[] weights) {
        if (direction == 0) {
            positions[0] = index;
            weights[0] = 1;
            return 1;
        }
        if (direction > 0) {
            positions[0] = 2 * index;
            positions[1] = Math.min(2 * index + 1, size - 1);
            weights[0] = 1;
            weights[1] = 1;
            return 2;
        }
        var near = index >> 1;
        positions[0] = near;
        positions[1] = (index & 1) == 0 ? Math.max(near - 1, 0) : Math.min(near + 1, size - 1);
        weights[0] = 3;
        weights[1] = 1;
        return 2;
    }
}
