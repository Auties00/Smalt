/* Ports X3F tools (x3f_process.c: preprocess_data, expand_quattro; x3f_denoise.cpp: x3f_expand_quattro;
   x3f_denoise_utils.cpp: BMT_to_YUV_Yis4T, YUV_to_BMT_Yis4T) at commit e8b48ca. Copyright 2015 Roland and Erik
   Karlsson, Mark Roden; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.internal.task.ParallelBands;

import java.util.Arrays;

/**
 * Brings the layers of a Sigma Quattro sensor, whose bottom and middle layers have a quarter of the
 * photosites of its top layer, to the top layer's resolution.
 *
 * <p>Each layer value {@code v} of layer {@code c} is first taken to a common scale,
 * {@code n = gain[c] * (v - black[c]) / (white[c] - black[c])}, with the white balance gains, so
 * that a neutral reads the same in every layer. The top layer is averaged over each 2x2 block to
 * the lower layers' resolution; the differences of the bottom and middle layers from that average
 * are upsampled by two with bicubic interpolation ({@code a = -0.75}, sample centers at the middle
 * of each photosite, edges replicated) and added to the full-resolution top layer:
 * {@snippet :
 * bottom(x, y) = top(x, y) + up(bottom - mean(top))(x, y)
 * middle(x, y) = top(x, y) + up(middle - mean(top))(x, y)
 * }
 * The results go back to stored units, rounded and clamped to 16 bits.
 *
 * @implNote This implementation follows the expansion of the X3F tools library (which converts the
 * layers to a luminance and two color differences, resizes them with OpenCV's bicubic
 * interpolation and puts the top layer back as the luminance), whose license was checked; it works
 * in floating point on the stored values where that library works on rescaled 16-bit integers, so
 * the results differ by rounding only.
 */
final class X3fQuattro {
    /**
     * The weights of the four source samples of an output sample a quarter of a sample after the
     * second of them, in 256ths: {@code -0.75} bicubic interpolation at a fraction of 0.25.
     */
    private static final float[] NEAR = {-27 / 256f, 225 / 256f, 67 / 256f, -9 / 256f};

    /**
     * The weights of the four source samples of an output sample three quarters of a sample after
     * the second of them.
     */
    private static final float[] FAR = {-9 / 256f, 67 / 256f, 225 / 256f, -27 / 256f};

    /**
     * Prevents instantiation.
     */
    private X3fQuattro() {
        throw new AssertionError();
    }

    /**
     * Stores the rounded mean of each 2x2 block of the top layer as the third sample of the lower
     * layers' image.
     *
     * @param low the lower layers, three samples per pixel
     * @param width the lower layers' width
     * @param height the lower layers' height
     * @param top the top layer
     * @param topWidth the top layer's width, at least {@code 2 * width}; its height is at least
     * {@code 2 * height}
     */
    static void bin(short[] low, int width, int height, short[] top, int topWidth) {
        var bands = ParallelBands.bands(height, (long) width * height * 4);
        ParallelBands.run(bands, band -> {
            var first = ParallelBands.start(band, bands, height);
            var last = ParallelBands.start(band + 1, bands, height);
            for (var row = first; row < last; row++) {
                var above = 2 * row * topWidth;
                var below = above + topWidth;
                for (var column = 0; column < width; column++) {
                    var sum = (top[above + 2 * column] & 0xFFFF) + (top[above + 2 * column + 1] & 0xFFFF)
                            + (top[below + 2 * column] & 0xFFFF) + (top[below + 2 * column + 1] & 0xFFFF);
                    low[(row * width + column) * 3 + 2] = (short) ((sum + 2) >> 2);
                }
            }
        });
    }

    /**
     * Expands the layers to the top layer's resolution.
     *
     * @param low the lower layers, three samples per pixel, the third the binned top layer
     * @param width the lower layers' width
     * @param height the lower layers' height
     * @param top the top layer
     * @param topWidth the top layer's width, at least {@code 2 * width}
     * @param black the black level of each layer, in stored units
     * @param scale the factor of each layer to the common scale, {@code gain / (white - black)}
     * @param out the output, {@code 2 * width} by {@code 2 * height} pixels of three samples
     */
    static void expand(short[] low, int width, int height, short[] top, int topWidth, double[] black, double[] scale,
                       short[] out) {
        var outWidth = 2 * width;
        var outHeight = 2 * height;
        var bands = ParallelBands.bands(outHeight, (long) outWidth * outHeight * 12);
        ParallelBands.run(bands, band -> {
            var first = ParallelBands.start(band, bands, outHeight);
            var last = ParallelBands.start(band + 1, bands, outHeight);
            var differences = new float[2][width];
            var rows = new float[5][2 * outWidth];
            var rowOf = new int[5];
            Arrays.fill(rowOf, Integer.MIN_VALUE);
            var topScale = scale[2];
            var topBlack = black[2];
            var bottomBlack = black[0];
            var middleBlack = black[1];
            var bottomInverse = 1 / scale[0];
            var middleInverse = 1 / scale[1];
            for (var y = first; y < last; y++) {
                var k = y >> 1;
                for (var tap = 0; tap < 5; tap++) {
                    var source = k - 2 + tap;
                    if (rowOf[tap] != source) {
                        var reuse = -1;
                        for (var other = 0; other < 5; other++) {
                            if (rowOf[other] == source) {
                                reuse = other;
                            }
                        }
                        if (reuse >= 0) {
                            var swap = rows[tap];
                            rows[tap] = rows[reuse];
                            rows[reuse] = swap;
                            rowOf[reuse] = rowOf[tap];
                        } else {
                            horizontal(low, width, height, Math.clamp(source, 0, height - 1), black, scale,
                                    differences, rows[tap]);
                        }
                        rowOf[tap] = source;
                    }
                }
                var weights = (y & 1) == 0 ? FAR : NEAR;
                var offset = (y & 1) == 0 ? 0 : 1;
                var r0 = rows[offset];
                var r1 = rows[offset + 1];
                var r2 = rows[offset + 2];
                var r3 = rows[offset + 3];
                var topRow = y * topWidth;
                var target = y * outWidth * 3;
                var w0 = weights[0];
                var w1 = weights[1];
                var w2 = weights[2];
                var w3 = weights[3];
                for (var x = 0; x < outWidth; x++) {
                    var t = top[topRow + x] & 0xFFFF;
                    var level = topScale * (t - topBlack);
                    var at = 2 * x;
                    var bottom = w0 * r0[at] + w1 * r1[at] + w2 * r2[at] + w3 * r3[at];
                    var middle = w0 * r0[at + 1] + w1 * r1[at + 1] + w2 * r2[at + 1] + w3 * r3[at + 1];
                    out[target] = (short) Math.clamp(Math.round(bottomBlack + (level + bottom) * bottomInverse), 0,
                            0xFFFF);
                    out[target + 1] = (short) Math.clamp(Math.round(middleBlack + (level + middle) * middleInverse), 0,
                            0xFFFF);
                    out[target + 2] = (short) t;
                    target += 3;
                }
            }
        });
    }

    /**
     * Computes one row of the bottom and middle layers' differences from the binned top layer, on
     * the common scale, upsampled horizontally by two.
     *
     * @param low the lower layers
     * @param width the lower layers' width
     * @param height the lower layers' height
     * @param row the row of the lower layers, inside them
     * @param black the black level of each layer
     * @param scale the factor of each layer to the common scale
     * @param differences scratch rows of {@code width} values for the two layers
     * @param out the upsampled row, the two layers' values of each column together
     */
    private static void horizontal(short[] low, int width, int height, int row, double[] black, double[] scale,
                                   float[][] differences, float[] out) {
        var base = row * width * 3;
        for (var column = 0; column < width; column++) {
            var at = base + column * 3;
            var level = scale[2] * ((low[at + 2] & 0xFFFF) - black[2]);
            differences[0][column] = (float) (scale[0] * ((low[at] & 0xFFFF) - black[0]) - level);
            differences[1][column] = (float) (scale[1] * ((low[at + 1] & 0xFFFF) - black[1]) - level);
        }
        var last = width - 1;
        for (var layer = 0; layer < 2; layer++) {
            var d = differences[layer];
            for (var column = 0; column < width; column++) {
                var m2 = d[Math.max(column - 2, 0)];
                var m1 = d[Math.max(column - 1, 0)];
                var p0 = d[column];
                var p1 = d[Math.min(column + 1, last)];
                var p2 = d[Math.min(column + 2, last)];
                out[4 * column + layer] = FAR[0] * m2 + FAR[1] * m1 + FAR[2] * p0 + FAR[3] * p1;
                out[4 * column + 2 + layer] = NEAR[0] * m1 + NEAR[1] * p0 + NEAR[2] * p1 + NEAR[3] * p2;
            }
        }
    }
}
