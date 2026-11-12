/* Ports libjpeg-turbo (jidctint.c, jpeg_idct_islow; jidctred.c, jpeg_idct_4x4, jpeg_idct_2x2, jpeg_idct_1x1;
   jdmaster.c, prepare_range_limit_table) built with 12 bits per sample. Copyright (C) 1991-1998, Thomas G.
   Lane; modification developed 2002-2018 by Guido Vollbeding; Copyright (C) 2009-2026, D. R. Commander; IJG
   License. Modified for Smalt. */
package com.github.auties00.smalt.image.jpeg.internal;

import java.util.Arrays;

/**
 * Computes the inverse DCTs of 12-bit frames: the accurate integer 8x8 transform and the
 * reduced-size transforms into 4x4, 2x2 and 1x1 samples.
 *
 * <p>Each method reads a block of dequantized coefficients in natural order, writes 12-bit samples
 * (0 to 4095), and clears the block. The results are bit-exact with libjpeg-turbo's 12-bit
 * {@code jpeg_idct_islow}, {@code jpeg_idct_4x4}, {@code jpeg_idct_2x2} and {@code jpeg_idct_1x1}
 * for every input, overflowing intermediate values and range limiting included: each descaled
 * output {@code t} maps to {@link #LIMIT}{@code [t & 16383]}. Arguments are not checked; values
 * outside the documented ranges give unspecified results.
 *
 * @implNote This implementation keeps one extra bit of precision between the passes, where the
 * 8-bit transforms keep two, and uses 32-bit intermediate arithmetic, as libjpeg-turbo builds
 * whose {@code JLONG} is 32 bits wide do.
 */
final class Idct12 {
    /**
     * The largest 12-bit sample.
     */
    static final int MAX_SAMPLE = 4095;

    /**
     * The number of fraction bits of the fixed-point constants.
     */
    private static final int CONST_BITS = 13;

    /**
     * The number of extra precision bits kept between the two passes.
     */
    private static final int PASS1_BITS = 1;

    /**
     * The mask applied to a descaled output before {@link #LIMIT}: two bits wider than a sample.
     */
    private static final int RANGE_MASK = 16383;

    /**
     * Maps a descaled output masked to 14 bits to a sample: {@code t + 2048} for {@code t} in
     * 0..2047, 4095 for 2048..8191, 0 for 8192..14335, and {@code t - 14336} for 14336..16383
     * ({@code IDCT_range_limit}).
     */
    static final short[] LIMIT = limitTable();

    /**
     * Maps a 12-bit sample {@code v} to the 8-bit sample {@code round(v * 255 / 4095)}.
     */
    static final byte[] EIGHT_BIT = eightBitTable();

    /**
     * Maps a 12-bit sample {@code v} to the 16-bit sample {@code round(v * 65535 / 4095)}.
     */
    static final short[] SIXTEEN_BIT = sixteenBitTable();

    /**
     * Prevents instantiation.
     */
    private Idct12() {
        throw new AssertionError();
    }

    /**
     * Builds {@link #EIGHT_BIT}.
     *
     * @return the table
     */
    private static byte[] eightBitTable() {
        var table = new byte[MAX_SAMPLE + 1];
        for (var v = 0; v <= MAX_SAMPLE; v++) {
            table[v] = (byte) ((v * 510 + MAX_SAMPLE) / (2 * MAX_SAMPLE));
        }
        return table;
    }

    /**
     * Builds {@link #SIXTEEN_BIT}.
     *
     * @return the table
     */
    private static short[] sixteenBitTable() {
        var table = new short[MAX_SAMPLE + 1];
        for (var v = 0; v <= MAX_SAMPLE; v++) {
            table[v] = (short) ((v * 131070L + MAX_SAMPLE) / (2 * MAX_SAMPLE));
        }
        return table;
    }

    /**
     * Builds {@link #LIMIT}.
     *
     * @return the table
     */
    private static short[] limitTable() {
        var table = new short[RANGE_MASK + 1];
        for (var t = 0; t <= RANGE_MASK; t++) {
            int value;
            if (t < 2048) {
                value = t + 2048;
            } else if (t < 8192) {
                value = MAX_SAMPLE;
            } else if (t < 14336) {
                value = 0;
            } else {
                value = t - 14336;
            }
            table[t] = (short) value;
        }
        return table;
    }

    /**
     * Transforms one block into 8x8 samples and clears it.
     *
     * @param c the 64 dequantized coefficients in natural order; zero on return
     * @param output the destination samples
     * @param offset the index of the top-left output sample
     * @param stride the distance between output rows
     */
    static void idct8(int[] c, short[] output, int offset, int stride) {
        for (var col = 0; col < 8; col++) {
            var in0 = c[col];
            var in1 = c[8 + col];
            var in2 = c[16 + col];
            var in3 = c[24 + col];
            var in4 = c[32 + col];
            var in5 = c[40 + col];
            var in6 = c[48 + col];
            var in7 = c[56 + col];
            if ((in1 | in2 | in3 | in4 | in5 | in6 | in7) == 0) {
                var dc = in0 << PASS1_BITS;
                c[col] = dc;
                c[8 + col] = dc;
                c[16 + col] = dc;
                c[24 + col] = dc;
                c[32 + col] = dc;
                c[40 + col] = dc;
                c[48 + col] = dc;
                c[56 + col] = dc;
                continue;
            }
            var z1 = (in2 + in6) * ScalarIdctKernel.FIX_0_541196100;
            var tmp2 = z1 + in6 * -ScalarIdctKernel.FIX_1_847759065;
            var tmp3 = z1 + in2 * ScalarIdctKernel.FIX_0_765366865;
            var tmp0 = (in0 + in4) << CONST_BITS;
            var tmp1 = (in0 - in4) << CONST_BITS;
            var tmp10 = tmp0 + tmp3;
            var tmp13 = tmp0 - tmp3;
            var tmp11 = tmp1 + tmp2;
            var tmp12 = tmp1 - tmp2;

            tmp0 = in7;
            tmp1 = in5;
            tmp2 = in3;
            tmp3 = in1;
            z1 = tmp0 + tmp3;
            var z2 = tmp1 + tmp2;
            var z3 = tmp0 + tmp2;
            var z4 = tmp1 + tmp3;
            var z5 = (z3 + z4) * ScalarIdctKernel.FIX_1_175875602;
            tmp0 = tmp0 * ScalarIdctKernel.FIX_0_298631336;
            tmp1 = tmp1 * ScalarIdctKernel.FIX_2_053119869;
            tmp2 = tmp2 * ScalarIdctKernel.FIX_3_072711026;
            tmp3 = tmp3 * ScalarIdctKernel.FIX_1_501321110;
            z1 = z1 * -ScalarIdctKernel.FIX_0_899976223;
            z2 = z2 * -ScalarIdctKernel.FIX_2_562915447;
            z3 = z3 * -ScalarIdctKernel.FIX_1_961570560;
            z4 = z4 * -ScalarIdctKernel.FIX_0_390180644;
            z3 += z5;
            z4 += z5;
            tmp0 += z1 + z3;
            tmp1 += z2 + z4;
            tmp2 += z2 + z3;
            tmp3 += z1 + z4;

            var round = 1 << (CONST_BITS - PASS1_BITS - 1);
            var shift = CONST_BITS - PASS1_BITS;
            c[col] = (tmp10 + tmp3 + round) >> shift;
            c[56 + col] = (tmp10 - tmp3 + round) >> shift;
            c[8 + col] = (tmp11 + tmp2 + round) >> shift;
            c[48 + col] = (tmp11 - tmp2 + round) >> shift;
            c[16 + col] = (tmp12 + tmp1 + round) >> shift;
            c[40 + col] = (tmp12 - tmp1 + round) >> shift;
            c[24 + col] = (tmp13 + tmp0 + round) >> shift;
            c[32 + col] = (tmp13 - tmp0 + round) >> shift;
        }

        var limit = LIMIT;
        for (var row = 0; row < 8; row++) {
            var base = row * 8;
            var out = offset + row * stride;
            var w0 = c[base];
            var w1 = c[base + 1];
            var w2 = c[base + 2];
            var w3 = c[base + 3];
            var w4 = c[base + 4];
            var w5 = c[base + 5];
            var w6 = c[base + 6];
            var w7 = c[base + 7];
            if ((w1 | w2 | w3 | w4 | w5 | w6 | w7) == 0) {
                var value = limit[((w0 + (1 << (PASS1_BITS + 2))) >> (PASS1_BITS + 3)) & RANGE_MASK];
                Arrays.fill(output, out, out + 8, value);
                continue;
            }
            var z1 = (w2 + w6) * ScalarIdctKernel.FIX_0_541196100;
            var tmp2 = z1 + w6 * -ScalarIdctKernel.FIX_1_847759065;
            var tmp3 = z1 + w2 * ScalarIdctKernel.FIX_0_765366865;
            var tmp0 = (w0 + w4) << CONST_BITS;
            var tmp1 = (w0 - w4) << CONST_BITS;
            var tmp10 = tmp0 + tmp3;
            var tmp13 = tmp0 - tmp3;
            var tmp11 = tmp1 + tmp2;
            var tmp12 = tmp1 - tmp2;

            tmp0 = w7;
            tmp1 = w5;
            tmp2 = w3;
            tmp3 = w1;
            z1 = tmp0 + tmp3;
            var z2 = tmp1 + tmp2;
            var z3 = tmp0 + tmp2;
            var z4 = tmp1 + tmp3;
            var z5 = (z3 + z4) * ScalarIdctKernel.FIX_1_175875602;
            tmp0 = tmp0 * ScalarIdctKernel.FIX_0_298631336;
            tmp1 = tmp1 * ScalarIdctKernel.FIX_2_053119869;
            tmp2 = tmp2 * ScalarIdctKernel.FIX_3_072711026;
            tmp3 = tmp3 * ScalarIdctKernel.FIX_1_501321110;
            z1 = z1 * -ScalarIdctKernel.FIX_0_899976223;
            z2 = z2 * -ScalarIdctKernel.FIX_2_562915447;
            z3 = z3 * -ScalarIdctKernel.FIX_1_961570560;
            z4 = z4 * -ScalarIdctKernel.FIX_0_390180644;
            z3 += z5;
            z4 += z5;
            tmp0 += z1 + z3;
            tmp1 += z2 + z4;
            tmp2 += z2 + z3;
            tmp3 += z1 + z4;

            var round = 1 << (CONST_BITS + PASS1_BITS + 2);
            var shift = CONST_BITS + PASS1_BITS + 3;
            output[out] = limit[((tmp10 + tmp3 + round) >> shift) & RANGE_MASK];
            output[out + 7] = limit[((tmp10 - tmp3 + round) >> shift) & RANGE_MASK];
            output[out + 1] = limit[((tmp11 + tmp2 + round) >> shift) & RANGE_MASK];
            output[out + 6] = limit[((tmp11 - tmp2 + round) >> shift) & RANGE_MASK];
            output[out + 2] = limit[((tmp12 + tmp1 + round) >> shift) & RANGE_MASK];
            output[out + 5] = limit[((tmp12 - tmp1 + round) >> shift) & RANGE_MASK];
            output[out + 3] = limit[((tmp13 + tmp0 + round) >> shift) & RANGE_MASK];
            output[out + 4] = limit[((tmp13 - tmp0 + round) >> shift) & RANGE_MASK];
        }
        Arrays.fill(c, 0, 64, 0);
    }

    /**
     * Transforms one block into 4x4 samples and clears it.
     *
     * @param c the 64 dequantized coefficients in natural order; zero on return
     * @param output the destination samples
     * @param offset the index of the top-left output sample
     * @param stride the distance between output rows
     */
    static void idct4(int[] c, short[] output, int offset, int stride) {
        for (var col = 0; col < 8; col++) {
            if (col == 4) {
                continue;
            }
            var in0 = c[col];
            var in1 = c[8 + col];
            var in2 = c[16 + col];
            var in3 = c[24 + col];
            var in5 = c[40 + col];
            var in6 = c[48 + col];
            var in7 = c[56 + col];
            if ((in1 | in2 | in3 | in5 | in6 | in7) == 0) {
                var dc = in0 << PASS1_BITS;
                c[col] = dc;
                c[8 + col] = dc;
                c[16 + col] = dc;
                c[24 + col] = dc;
                continue;
            }
            var tmp0 = in0 << (CONST_BITS + 1);
            var tmp2 = in2 * ReducedIdct.FIX_1_847759065 + in6 * -ReducedIdct.FIX_0_765366865;
            var tmp10 = tmp0 + tmp2;
            var tmp12 = tmp0 - tmp2;
            tmp0 = in7 * -ReducedIdct.FIX_0_211164243 + in5 * ReducedIdct.FIX_1_451774981
                    + in3 * -ReducedIdct.FIX_2_172734803 + in1 * ReducedIdct.FIX_1_061594337;
            tmp2 = in7 * -ReducedIdct.FIX_0_509795579 + in5 * -ReducedIdct.FIX_0_601344887
                    + in3 * ReducedIdct.FIX_0_899976223 + in1 * ReducedIdct.FIX_2_562915447;
            var shift = CONST_BITS - PASS1_BITS + 1;
            var round = 1 << (shift - 1);
            c[col] = (tmp10 + tmp2 + round) >> shift;
            c[24 + col] = (tmp10 - tmp2 + round) >> shift;
            c[8 + col] = (tmp12 + tmp0 + round) >> shift;
            c[16 + col] = (tmp12 - tmp0 + round) >> shift;
        }
        var limit = LIMIT;
        for (var row = 0; row < 4; row++) {
            var base = row * 8;
            var out = offset + row * stride;
            var w0 = c[base];
            var w1 = c[base + 1];
            var w2 = c[base + 2];
            var w3 = c[base + 3];
            var w5 = c[base + 5];
            var w6 = c[base + 6];
            var w7 = c[base + 7];
            if ((w1 | w2 | w3 | w5 | w6 | w7) == 0) {
                var value = limit[((w0 + (1 << (PASS1_BITS + 2))) >> (PASS1_BITS + 3)) & RANGE_MASK];
                output[out] = value;
                output[out + 1] = value;
                output[out + 2] = value;
                output[out + 3] = value;
                continue;
            }
            var tmp0 = w0 << (CONST_BITS + 1);
            var tmp2 = w2 * ReducedIdct.FIX_1_847759065 + w6 * -ReducedIdct.FIX_0_765366865;
            var tmp10 = tmp0 + tmp2;
            var tmp12 = tmp0 - tmp2;
            tmp0 = w7 * -ReducedIdct.FIX_0_211164243 + w5 * ReducedIdct.FIX_1_451774981
                    + w3 * -ReducedIdct.FIX_2_172734803 + w1 * ReducedIdct.FIX_1_061594337;
            tmp2 = w7 * -ReducedIdct.FIX_0_509795579 + w5 * -ReducedIdct.FIX_0_601344887
                    + w3 * ReducedIdct.FIX_0_899976223 + w1 * ReducedIdct.FIX_2_562915447;
            var shift = CONST_BITS + PASS1_BITS + 3 + 1;
            var round = 1 << (shift - 1);
            output[out] = limit[((tmp10 + tmp2 + round) >> shift) & RANGE_MASK];
            output[out + 3] = limit[((tmp10 - tmp2 + round) >> shift) & RANGE_MASK];
            output[out + 1] = limit[((tmp12 + tmp0 + round) >> shift) & RANGE_MASK];
            output[out + 2] = limit[((tmp12 - tmp0 + round) >> shift) & RANGE_MASK];
        }
        Arrays.fill(c, 0, 64, 0);
    }

    /**
     * Transforms one block into 2x2 samples and clears it.
     *
     * @param c the 64 dequantized coefficients in natural order; zero on return
     * @param output the destination samples
     * @param offset the index of the top-left output sample
     * @param stride the distance between output rows
     */
    static void idct2(int[] c, short[] output, int offset, int stride) {
        for (var col = 0; col < 8; col++) {
            if (col == 2 || col == 4 || col == 6) {
                continue;
            }
            var in0 = c[col];
            var in1 = c[8 + col];
            var in3 = c[24 + col];
            var in5 = c[40 + col];
            var in7 = c[56 + col];
            if ((in1 | in3 | in5 | in7) == 0) {
                var dc = in0 << PASS1_BITS;
                c[col] = dc;
                c[8 + col] = dc;
                continue;
            }
            var tmp10 = in0 << (CONST_BITS + 2);
            var tmp0 = in7 * -ReducedIdct.FIX_0_720959822 + in5 * ReducedIdct.FIX_0_850430095
                    + in3 * -ReducedIdct.FIX_1_272758580 + in1 * ReducedIdct.FIX_3_624509785;
            var shift = CONST_BITS - PASS1_BITS + 2;
            var round = 1 << (shift - 1);
            c[col] = (tmp10 + tmp0 + round) >> shift;
            c[8 + col] = (tmp10 - tmp0 + round) >> shift;
        }
        var limit = LIMIT;
        for (var row = 0; row < 2; row++) {
            var base = row * 8;
            var out = offset + row * stride;
            var w0 = c[base];
            var w1 = c[base + 1];
            var w3 = c[base + 3];
            var w5 = c[base + 5];
            var w7 = c[base + 7];
            if ((w1 | w3 | w5 | w7) == 0) {
                var value = limit[((w0 + (1 << (PASS1_BITS + 2))) >> (PASS1_BITS + 3)) & RANGE_MASK];
                output[out] = value;
                output[out + 1] = value;
                continue;
            }
            var tmp10 = w0 << (CONST_BITS + 2);
            var tmp0 = w7 * -ReducedIdct.FIX_0_720959822 + w5 * ReducedIdct.FIX_0_850430095
                    + w3 * -ReducedIdct.FIX_1_272758580 + w1 * ReducedIdct.FIX_3_624509785;
            var shift = CONST_BITS + PASS1_BITS + 3 + 2;
            var round = 1 << (shift - 1);
            output[out] = limit[((tmp10 + tmp0 + round) >> shift) & RANGE_MASK];
            output[out + 1] = limit[((tmp10 - tmp0 + round) >> shift) & RANGE_MASK];
        }
        Arrays.fill(c, 0, 64, 0);
    }

    /**
     * Returns the 1x1 output of a block from its dequantized DC coefficient.
     *
     * @param dc the dequantized DC coefficient
     * @return the sample
     */
    static short dcValue1(int dc) {
        return LIMIT[((dc + 4) >> 3) & RANGE_MASK];
    }

    /**
     * Returns the output of every sample of an 8x8, 4x4 or 2x2 transform whose only nonzero
     * coefficient is the DC coefficient.
     *
     * @param dc the dequantized DC coefficient
     * @return the sample
     */
    static short dcValue(int dc) {
        return LIMIT[(((dc << PASS1_BITS) + (1 << (PASS1_BITS + 2))) >> (PASS1_BITS + 3)) & RANGE_MASK];
    }

    /**
     * Dequantizes and transforms one block of stored coefficients of a 12-bit component.
     *
     * @param coefficients the coefficient buffer, natural order
     * @param base the index of the block's first coefficient
     * @param quant the natural-order quantization table
     * @param block a scratch block, all zero on entry and on return
     * @param plane the destination plane
     * @param offset the index of the block's top-left sample
     * @param stride the plane stride
     * @param scaleShift the binary logarithm of the size reduction, from 0 to 3
     */
    static void transformBlock(short[] coefficients, int base, int[] quant, int[] block, short[] plane, int offset,
                               int stride, int scaleShift) {
        var dc = coefficients[base] * quant[0];
        if (scaleShift == 3) {
            plane[offset] = dcValue1(dc);
            return;
        }
        var ac = 0;
        for (var k = 1; k < 64; k++) {
            var value = coefficients[base + k] * quant[k];
            block[k] = value;
            ac |= value;
        }
        var size = 8 >> scaleShift;
        if (ac == 0) {
            var value = dcValue(dc);
            for (var row = 0; row < size; row++) {
                Arrays.fill(plane, offset + row * stride, offset + row * stride + size, value);
            }
            return;
        }
        block[0] = dc;
        switch (scaleShift) {
            case 0 -> idct8(block, plane, offset, stride);
            case 1 -> idct4(block, plane, offset, stride);
            default -> idct2(block, plane, offset, stride);
        }
    }
}
