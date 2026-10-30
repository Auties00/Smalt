/* Ports libjpeg-turbo (jidctint.c, jpeg_idct_islow). Copyright (C) 1991-1998, Thomas G. Lane; libjpeg-turbo
   Modifications Copyright (C) 2015, 2020, 2022, 2026, D. R. Commander; IJG License. Modified for Smalt. */
package com.github.auties00.smalt.video.mpeg4.internal;

/**
 * Implements the {@link Mpeg4Dsp} kernels with scalar arithmetic; the reference implementation.
 *
 * @implNote This implementation's inverse DCT is libjpeg-turbo's accurate integer transform
 * ({@code jpeg_idct_islow}): the Loeffler-Ligtenberg-Moschytz algorithm with 13-bit constants and
 * two extra bits of precision between the passes, the rows transformed first, without the JPEG
 * level shift and range limit. It meets the IEEE 1180 accuracy that ISO/IEC 14496-2 Annex A and
 * ITU-T H.263 Annex A require.
 */
public class ScalarMpeg4Dsp extends Mpeg4Dsp {
    /**
     * The number of fraction bits of the fixed-point constants.
     */
    static final int CONST_BITS = 13;

    /**
     * The number of extra precision bits kept between the two passes.
     */
    static final int PASS1_BITS = 2;

    /**
     * {@code FIX(0.298631336)}.
     */
    static final int FIX_0_298631336 = 2446;

    /**
     * {@code FIX(0.390180644)}.
     */
    static final int FIX_0_390180644 = 3196;

    /**
     * {@code FIX(0.541196100)}.
     */
    static final int FIX_0_541196100 = 4433;

    /**
     * {@code FIX(0.765366865)}.
     */
    static final int FIX_0_765366865 = 6270;

    /**
     * {@code FIX(0.899976223)}.
     */
    static final int FIX_0_899976223 = 7373;

    /**
     * {@code FIX(1.175875602)}.
     */
    static final int FIX_1_175875602 = 9633;

    /**
     * {@code FIX(1.501321110)}.
     */
    static final int FIX_1_501321110 = 12299;

    /**
     * {@code FIX(1.847759065)}.
     */
    static final int FIX_1_847759065 = 15137;

    /**
     * {@code FIX(1.961570560)}.
     */
    static final int FIX_1_961570560 = 16069;

    /**
     * {@code FIX(2.053119869)}.
     */
    static final int FIX_2_053119869 = 16819;

    /**
     * {@code FIX(2.562915447)}.
     */
    static final int FIX_2_562915447 = 20995;

    /**
     * {@code FIX(3.072711026)}.
     */
    static final int FIX_3_072711026 = 25172;

    /**
     * The row stride of the quarter sample scratch planes.
     */
    static final int GRID_STRIDE = 24;

    /**
     * The index of the integer sample plane in the quarter sample scratch buffer, padded by three
     * mirrored samples on every side.
     */
    static final int INTEGER_PLANE = 0;

    /**
     * The index of the plane of samples interpolated horizontally to the vector's horizontal
     * fraction, padded by three mirrored rows above and below.
     */
    static final int HORIZONTAL_PLANE = GRID_STRIDE * GRID_STRIDE;

    /**
     * Constructs the kernels.
     */
    ScalarMpeg4Dsp() {
    }

    @Override
    public void idctPut(short[] coefficients, int offset, byte[] dst, int dstOffset, int stride, int[] work) {
        transform(coefficients, offset, work);
        for (var row = 0; row < 8; row++) {
            var out = dstOffset + row * stride;
            var base = row * 8;
            for (var col = 0; col < 8; col++) {
                var value = work[base + col];
                dst[out + col] = (byte) (value < 0 ? 0 : value > 255 ? 255 : value);
            }
        }
    }

    @Override
    public void idctAdd(short[] coefficients, int offset, byte[] dst, int dstOffset, int stride, int[] work) {
        transform(coefficients, offset, work);
        for (var row = 0; row < 8; row++) {
            var out = dstOffset + row * stride;
            var base = row * 8;
            for (var col = 0; col < 8; col++) {
                var value = (dst[out + col] & 0xFF) + work[base + col];
                dst[out + col] = (byte) (value < 0 ? 0 : value > 255 ? 255 : value);
            }
        }
    }

    /**
     * Computes the inverse DCT of a block into 64 integers in raster order and clears the block.
     *
     * @param c the coefficients in column-major order
     * @param offset the index of the first coefficient
     * @param out receives the samples
     */
    static void transform(short[] c, int offset, int[] out) {
        var mask = rows(c, offset, out);
        if (mask <= 1) {
            firstRowColumns(out, mask);
        } else {
            columns(out);
        }
    }

    /**
     * Runs the second pass of the inverse DCT for a block whose first pass left only the first row
     * nonzero: every column then holds one value, so every row of the result equals the first.
     *
     * @param out the intermediate values of {@link #rows(short[], int, int[])}, replaced by the
     * samples
     * @param mask the nonzero rows reported by {@code rows}: {@code 0} or {@code 1}
     */
    static void firstRowColumns(int[] out, int mask) {
        var round = 1 << (CONST_BITS + PASS1_BITS + 2);
        var shift = CONST_BITS + PASS1_BITS + 3;
        for (var col = 0; col < 8; col++) {
            var value = mask == 0 ? 0 : ((out[col] << CONST_BITS) + round) >> shift;
            for (var row = 0; row < 8; row++) {
                out[row * 8 + col] = value;
            }
        }
    }

    /**
     * Runs the first pass of the inverse DCT, over the rows, and clears the block.
     *
     * @param c the coefficients in column-major order: coefficient {@code column} of row
     * {@code row} is at {@code offset + column * 8 + row}
     * @param offset the index of the first coefficient
     * @param out receives the intermediate values in raster order, scaled by
     * {@code 1 << PASS1_BITS}
     */
    static int rows(short[] c, int offset, int[] out) {
        var mask = 0;
        for (var row = 0; row < 8; row++) {
            var base = offset + row;
            var d0 = (int) c[base];
            var d1 = (int) c[base + 8];
            var d2 = (int) c[base + 16];
            var d3 = (int) c[base + 24];
            var d4 = (int) c[base + 32];
            var d5 = (int) c[base + 40];
            var d6 = (int) c[base + 48];
            var d7 = (int) c[base + 56];
            var o = row * 8;
            if ((d0 | d1 | d2 | d3 | d4 | d5 | d6 | d7) == 0) {
                out[o] = 0;
                out[o + 1] = 0;
                out[o + 2] = 0;
                out[o + 3] = 0;
                out[o + 4] = 0;
                out[o + 5] = 0;
                out[o + 6] = 0;
                out[o + 7] = 0;
                continue;
            }
            mask |= 1 << row;
            c[base] = 0;
            c[base + 8] = 0;
            c[base + 16] = 0;
            c[base + 24] = 0;
            c[base + 32] = 0;
            c[base + 40] = 0;
            c[base + 48] = 0;
            c[base + 56] = 0;
            if ((d1 | d2 | d3 | d4 | d5 | d6 | d7) == 0) {
                var dc = d0 << PASS1_BITS;
                out[o] = dc;
                out[o + 1] = dc;
                out[o + 2] = dc;
                out[o + 3] = dc;
                out[o + 4] = dc;
                out[o + 5] = dc;
                out[o + 6] = dc;
                out[o + 7] = dc;
                continue;
            }
            int tmp0;
            int tmp1;
            int tmp2;
            int tmp3;
            int tmp10;
            int tmp11;
            int tmp12;
            int tmp13;
            if ((d4 | d5 | d6 | d7) == 0) {
                var z1 = d2 * FIX_0_541196100;
                tmp0 = d0 << CONST_BITS;
                tmp3 = z1 + d2 * FIX_0_765366865;
                tmp10 = tmp0 + tmp3;
                tmp13 = tmp0 - tmp3;
                tmp11 = tmp0 + z1;
                tmp12 = tmp0 - z1;
                var z5 = (d3 + d1) * FIX_1_175875602;
                var odd1 = d1 * -FIX_0_899976223;
                var odd3 = d3 * -FIX_2_562915447;
                var z3 = d3 * -FIX_1_961570560 + z5;
                var z4 = d1 * -FIX_0_390180644 + z5;
                tmp0 = odd1 + z3;
                tmp1 = odd3 + z4;
                tmp2 = d3 * FIX_3_072711026 + odd3 + z3;
                tmp3 = d1 * FIX_1_501321110 + odd1 + z4;
            } else {
                var z1 = (d2 + d6) * FIX_0_541196100;
                tmp2 = z1 + d6 * -FIX_1_847759065;
                tmp3 = z1 + d2 * FIX_0_765366865;
                tmp0 = (d0 + d4) << CONST_BITS;
                tmp1 = (d0 - d4) << CONST_BITS;
                tmp10 = tmp0 + tmp3;
                tmp13 = tmp0 - tmp3;
                tmp11 = tmp1 + tmp2;
                tmp12 = tmp1 - tmp2;
                z1 = d7 + d1;
                var z2 = d5 + d3;
                var z3 = d7 + d3;
                var z4 = d5 + d1;
                var z5 = (z3 + z4) * FIX_1_175875602;
                tmp0 = d7 * FIX_0_298631336;
                tmp1 = d5 * FIX_2_053119869;
                tmp2 = d3 * FIX_3_072711026;
                tmp3 = d1 * FIX_1_501321110;
                z1 = z1 * -FIX_0_899976223;
                z2 = z2 * -FIX_2_562915447;
                z3 = z3 * -FIX_1_961570560 + z5;
                z4 = z4 * -FIX_0_390180644 + z5;
                tmp0 += z1 + z3;
                tmp1 += z2 + z4;
                tmp2 += z2 + z3;
                tmp3 += z1 + z4;
            }
            var round = 1 << (CONST_BITS - PASS1_BITS - 1);
            var shift = CONST_BITS - PASS1_BITS;
            out[o] = (tmp10 + tmp3 + round) >> shift;
            out[o + 7] = (tmp10 - tmp3 + round) >> shift;
            out[o + 1] = (tmp11 + tmp2 + round) >> shift;
            out[o + 6] = (tmp11 - tmp2 + round) >> shift;
            out[o + 2] = (tmp12 + tmp1 + round) >> shift;
            out[o + 5] = (tmp12 - tmp1 + round) >> shift;
            out[o + 3] = (tmp13 + tmp0 + round) >> shift;
            out[o + 4] = (tmp13 - tmp0 + round) >> shift;
        }
        return mask;
    }

    /**
     * Runs the second pass of the inverse DCT, over the columns, in place.
     *
     * @param out the intermediate values of {@link #rows(short[], int, int[])}, replaced by the
     * samples
     */
    static void columns(int[] out) {
        for (var col = 0; col < 8; col++) {
            var d0 = out[col];
            var d1 = out[8 + col];
            var d2 = out[16 + col];
            var d3 = out[24 + col];
            var d4 = out[32 + col];
            var d5 = out[40 + col];
            var d6 = out[48 + col];
            var d7 = out[56 + col];
            var z1 = (d2 + d6) * FIX_0_541196100;
            var tmp2 = z1 + d6 * -FIX_1_847759065;
            var tmp3 = z1 + d2 * FIX_0_765366865;
            var tmp0 = (d0 + d4) << CONST_BITS;
            var tmp1 = (d0 - d4) << CONST_BITS;
            var tmp10 = tmp0 + tmp3;
            var tmp13 = tmp0 - tmp3;
            var tmp11 = tmp1 + tmp2;
            var tmp12 = tmp1 - tmp2;
            z1 = d7 + d1;
            var z2 = d5 + d3;
            var z3 = d7 + d3;
            var z4 = d5 + d1;
            var z5 = (z3 + z4) * FIX_1_175875602;
            tmp0 = d7 * FIX_0_298631336;
            tmp1 = d5 * FIX_2_053119869;
            tmp2 = d3 * FIX_3_072711026;
            tmp3 = d1 * FIX_1_501321110;
            z1 = z1 * -FIX_0_899976223;
            z2 = z2 * -FIX_2_562915447;
            z3 = z3 * -FIX_1_961570560 + z5;
            z4 = z4 * -FIX_0_390180644 + z5;
            tmp0 += z1 + z3;
            tmp1 += z2 + z4;
            tmp2 += z2 + z3;
            tmp3 += z1 + z4;
            var round = 1 << (CONST_BITS + PASS1_BITS + 2);
            var shift = CONST_BITS + PASS1_BITS + 3;
            out[col] = (tmp10 + tmp3 + round) >> shift;
            out[56 + col] = (tmp10 - tmp3 + round) >> shift;
            out[8 + col] = (tmp11 + tmp2 + round) >> shift;
            out[48 + col] = (tmp11 - tmp2 + round) >> shift;
            out[16 + col] = (tmp12 + tmp1 + round) >> shift;
            out[40 + col] = (tmp12 - tmp1 + round) >> shift;
            out[24 + col] = (tmp13 + tmp0 + round) >> shift;
            out[32 + col] = (tmp13 - tmp0 + round) >> shift;
        }
    }

    @Override
    public void halfPel(byte[] src, int srcOffset, int srcStride, byte[] dst, int dstOffset, int dstStride,
                        int width, int height, int halfX, int halfY, int rounding) {
        var mode = halfX | (halfY << 1);
        for (var row = 0; row < height; row++) {
            var s = srcOffset + row * srcStride;
            var d = dstOffset + row * dstStride;
            switch (mode) {
                case 0 -> System.arraycopy(src, s, dst, d, width);
                case 1 -> {
                    var add = 1 - rounding;
                    for (var x = 0; x < width; x++) {
                        dst[d + x] = (byte) (((src[s + x] & 0xFF) + (src[s + x + 1] & 0xFF) + add) >> 1);
                    }
                }
                case 2 -> {
                    var add = 1 - rounding;
                    for (var x = 0; x < width; x++) {
                        dst[d + x] = (byte) (((src[s + x] & 0xFF) + (src[s + x + srcStride] & 0xFF) + add) >> 1);
                    }
                }
                default -> {
                    var add = 2 - rounding;
                    var t = s + srcStride;
                    for (var x = 0; x < width; x++) {
                        dst[d + x] = (byte) (((src[s + x] & 0xFF) + (src[s + x + 1] & 0xFF)
                                + (src[t + x] & 0xFF) + (src[t + x + 1] & 0xFF) + add) >> 2);
                    }
                }
            }
        }
    }

    @Override
    public void average(byte[] src, int srcOffset, int srcStride, byte[] dst, int dstOffset, int dstStride,
                        int width, int height) {
        for (var row = 0; row < height; row++) {
            var s = srcOffset + row * srcStride;
            var d = dstOffset + row * dstStride;
            for (var x = 0; x < width; x++) {
                dst[d + x] = (byte) (((dst[d + x] & 0xFF) + (src[s + x] & 0xFF) + 1) >> 1);
            }
        }
    }

    @Override
    public void quarterPel(byte[] src, int srcOffset, int srcStride, byte[] dst, int dstOffset, int dstStride,
                           int width, int height, int quarterX, int quarterY, int rounding, int[] scratch) {
        if ((quarterX | quarterY) == 0) {
            for (var row = 0; row < height; row++) {
                System.arraycopy(src, srcOffset + row * srcStride, dst, dstOffset + row * dstStride, width);
            }
            return;
        }
        var rows = height + 1;
        padReference(src, srcOffset, srcStride, width + 1, rows, scratch);
        var source = INTEGER_PLANE + 3;
        if (quarterX != 0) {
            horizontalPass(scratch, width, rows, quarterX, rounding);
            source = HORIZONTAL_PLANE;
        }
        verticalPass(scratch, source, dst, dstOffset, dstStride, width, height, quarterY, rounding);
    }

    /**
     * Copies the (width + 1) by (height + 1) reference block of {@link #quarterPel} into the integer
     * plane of the scratch buffer, extended by three samples on every side with the block boundary
     * mirroring of Figure V2-17, so the interpolation filters need no bounds checks.
     *
     * @param src the reference plane
     * @param srcOffset the index of the top-left sample of the block
     * @param srcStride the reference row stride
     * @param cols the number of block columns
     * @param rows the number of block rows
     * @param scratch the scratch buffer
     */
    static void padReference(byte[] src, int srcOffset, int srcStride, int cols, int rows, int[] scratch) {
        for (var j = 0; j < rows; j++) {
            var s = srcOffset + j * srcStride;
            var o = INTEGER_PLANE + (j + 3) * GRID_STRIDE;
            for (var i = 0; i < cols; i++) {
                scratch[o + 3 + i] = src[s + i] & 0xFF;
            }
            for (var k = 0; k < 3; k++) {
                scratch[o + 2 - k] = scratch[o + 3 + k];
                scratch[o + 3 + cols + k] = scratch[o + 2 + cols - k];
            }
        }
        var padded = cols + 6;
        for (var k = 0; k < 3; k++) {
            System.arraycopy(scratch, INTEGER_PLANE + (3 + k) * GRID_STRIDE, scratch,
                    INTEGER_PLANE + (2 - k) * GRID_STRIDE, padded);
            System.arraycopy(scratch, INTEGER_PLANE + (2 + rows - k) * GRID_STRIDE, scratch,
                    INTEGER_PLANE + (3 + rows + k) * GRID_STRIDE, padded);
        }
    }

    /**
     * Interpolates every row of the padded reference block to the horizontal fraction into the
     * horizontal plane (the half sample of the 8-tap filter, averaged with the integer sample to its
     * left or right for a quarter fraction), and extends the plane by three mirrored rows above and
     * below for the vertical pass.
     *
     * @param scratch the scratch buffer holding the padded reference block
     * @param width the number of samples per row
     * @param rows the number of block rows
     * @param quarterX the horizontal quarter sample fraction, from 1 to 3
     * @param rounding the rounding control, 0 or 1
     */
    static void horizontalPass(int[] scratch, int width, int rows, int quarterX, int rounding) {
        var round = 16 - rounding;
        var add = 1 - rounding;
        var neighbor = quarterX == 3 ? 1 : 0;
        for (var j = 0; j < rows; j++) {
            var c = INTEGER_PLANE + (j + 3) * GRID_STRIDE + 3;
            var o = HORIZONTAL_PLANE + (j + 3) * GRID_STRIDE;
            for (var i = 0; i < width; i++) {
                var k = c + i;
                var sum = 20 * (scratch[k] + scratch[k + 1]) - 6 * (scratch[k - 1] + scratch[k + 2])
                        + 3 * (scratch[k - 2] + scratch[k + 3]) - (scratch[k - 3] + scratch[k + 4]);
                var half = clip((sum + round) >> 5);
                scratch[o + i] = quarterX == 2 ? half : (half + scratch[k + neighbor] + add) >> 1;
            }
        }
        for (var k = 0; k < 3; k++) {
            System.arraycopy(scratch, HORIZONTAL_PLANE + (3 + k) * GRID_STRIDE, scratch,
                    HORIZONTAL_PLANE + (2 - k) * GRID_STRIDE, width);
            System.arraycopy(scratch, HORIZONTAL_PLANE + (2 + rows - k) * GRID_STRIDE, scratch,
                    HORIZONTAL_PLANE + (3 + rows + k) * GRID_STRIDE, width);
        }
    }

    /**
     * Interpolates the columns of a plane padded by three mirrored rows above and below to the
     * vertical fraction into the destination: the plane's samples for fraction 0, the half sample of
     * the 8-tap filter for fraction 2, and for a quarter fraction that half sample averaged with the
     * plane's sample above or below it.
     *
     * @param scratch the scratch buffer
     * @param source the index of the first column of the source plane in its top padding row
     * @param dst the destination plane
     * @param dstOffset the index of the destination's top-left sample
     * @param dstStride the row stride of the destination
     * @param width the number of columns
     * @param height the number of rows
     * @param quarterY the vertical quarter sample fraction, from 0 to 3
     * @param rounding the rounding control, 0 or 1
     */
    static void verticalPass(int[] scratch, int source, byte[] dst, int dstOffset, int dstStride, int width,
                             int height, int quarterY, int rounding) {
        final var s = GRID_STRIDE;
        var round = 16 - rounding;
        var add = 1 - rounding;
        var neighbor = quarterY == 3 ? s : 0;
        for (var j = 0; j < height; j++) {
            var c = source + (j + 3) * s;
            var d = dstOffset + j * dstStride;
            for (var i = 0; i < width; i++) {
                var k = c + i;
                if (quarterY == 0) {
                    dst[d + i] = (byte) scratch[k];
                    continue;
                }
                var sum = 20 * (scratch[k] + scratch[k + s]) - 6 * (scratch[k - s] + scratch[k + 2 * s])
                        + 3 * (scratch[k - 2 * s] + scratch[k + 3 * s]) - (scratch[k - 3 * s] + scratch[k + 4 * s]);
                var half = clip((sum + round) >> 5);
                dst[d + i] = (byte) (quarterY == 2 ? half : (half + scratch[k + neighbor] + add) >> 1);
            }
        }
    }

    /**
     * Clips a value to the 8-bit sample range.
     *
     * @param value the sample before clipping
     * @return the clipped value
     */
    private static int clip(int value) {
        return value < 0 ? 0 : value > 255 ? 255 : value;
    }
}
