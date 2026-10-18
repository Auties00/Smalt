/* Ports libjpeg-turbo (jidctint.c, jpeg_idct_islow). Copyright (C) 1991-1998, Thomas G. Lane; modification
   developed 2002-2018 by Guido Vollbeding; Copyright (C) 2015-2026, D. R. Commander; IJG License. Modified for
   Smalt. */
package com.github.auties00.smalt.image.jpeg.internal;

/**
 * Computes the accurate integer inverse DCT of one 8x8 block, the transform of single blocks; the
 * batched {@link BatchIdctKernel} transforms several at once.
 *
 * <p>The input is a block of dequantized coefficients in natural (row-major) order; the output is
 * 64 samples written as eight rows of eight bytes. The result is bit-exact with libjpeg-turbo's
 * {@code jpeg_idct_islow} for every input, values that overflow the 32-bit intermediate
 * arithmetic and its range limiting included: each descaled output {@code t} maps to
 * {@link #LIMIT}{@code [t & 1023]}. Arguments are not checked; values outside the documented
 * ranges give unspecified results.
 *
 * @implNote This implementation is the Loeffler-Ligtenberg-Moschytz algorithm with 13-bit
 * constants and two extra bits of precision between the passes, with the shortcuts for columns and
 * rows whose AC terms are all zero; the intermediate arithmetic is 32-bit, as in libjpeg-turbo
 * builds whose {@code JLONG} is 32 bits wide.
 */
final class ScalarIdctKernel {
    /**
     * The kernel used for single blocks.
     */
    static final ScalarIdctKernel INSTANCE = new ScalarIdctKernel();

    /**
     * Constructs the kernel.
     */
    ScalarIdctKernel() {
    }

    /**
     * Maps a descaled output masked to 10 bits to a sample: {@code t + 128} for {@code t} in
     * 0..127, 255 for 128..511, 0 for 512..895, and {@code t - 896} for 896..1023
     * ({@code IDCT_range_limit}).
     */
    static final byte[] LIMIT = limitTable();

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
     * Builds {@link #LIMIT}.
     *
     * @return the table
     */
    static byte[] limitTable() {
        var table = new byte[1024];
        for (var t = 0; t < 1024; t++) {
            int value;
            if (t < 128) {
                value = t + 128;
            } else if (t < 512) {
                value = 255;
            } else if (t < 896) {
                value = 0;
            } else {
                value = t - 896;
            }
            table[t] = (byte) value;
        }
        return table;
    }

    /**
     * Transforms one block and clears it.
     *
     * @param c the 64 dequantized coefficients in natural order; zero on return
     * @param output the destination samples
     * @param offset the index of the top-left output sample
     * @param stride the distance between output rows
     */
    void idct8(int[] c, byte[] output, int offset, int stride) {
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
            var z1 = (in2 + in6) * FIX_0_541196100;
            var tmp2 = z1 + in6 * -FIX_1_847759065;
            var tmp3 = z1 + in2 * FIX_0_765366865;
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
            var z5 = (z3 + z4) * FIX_1_175875602;
            tmp0 = tmp0 * FIX_0_298631336;
            tmp1 = tmp1 * FIX_2_053119869;
            tmp2 = tmp2 * FIX_3_072711026;
            tmp3 = tmp3 * FIX_1_501321110;
            z1 = z1 * -FIX_0_899976223;
            z2 = z2 * -FIX_2_562915447;
            z3 = z3 * -FIX_1_961570560;
            z4 = z4 * -FIX_0_390180644;
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
            c[base] = 0;
            c[base + 1] = 0;
            c[base + 2] = 0;
            c[base + 3] = 0;
            c[base + 4] = 0;
            c[base + 5] = 0;
            c[base + 6] = 0;
            c[base + 7] = 0;
            if ((w1 | w2 | w3 | w4 | w5 | w6 | w7) == 0) {
                var value = limit[((w0 + (1 << (PASS1_BITS + 2))) >> (PASS1_BITS + 3)) & 1023];
                output[out] = value;
                output[out + 1] = value;
                output[out + 2] = value;
                output[out + 3] = value;
                output[out + 4] = value;
                output[out + 5] = value;
                output[out + 6] = value;
                output[out + 7] = value;
                continue;
            }
            var z1 = (w2 + w6) * FIX_0_541196100;
            var tmp2 = z1 + w6 * -FIX_1_847759065;
            var tmp3 = z1 + w2 * FIX_0_765366865;
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
            var z5 = (z3 + z4) * FIX_1_175875602;
            tmp0 = tmp0 * FIX_0_298631336;
            tmp1 = tmp1 * FIX_2_053119869;
            tmp2 = tmp2 * FIX_3_072711026;
            tmp3 = tmp3 * FIX_1_501321110;
            z1 = z1 * -FIX_0_899976223;
            z2 = z2 * -FIX_2_562915447;
            z3 = z3 * -FIX_1_961570560;
            z4 = z4 * -FIX_0_390180644;
            z3 += z5;
            z4 += z5;
            tmp0 += z1 + z3;
            tmp1 += z2 + z4;
            tmp2 += z2 + z3;
            tmp3 += z1 + z4;

            var round = 1 << (CONST_BITS + PASS1_BITS + 2);
            var shift = CONST_BITS + PASS1_BITS + 3;
            output[out] = limit[((tmp10 + tmp3 + round) >> shift) & 1023];
            output[out + 7] = limit[((tmp10 - tmp3 + round) >> shift) & 1023];
            output[out + 1] = limit[((tmp11 + tmp2 + round) >> shift) & 1023];
            output[out + 6] = limit[((tmp11 - tmp2 + round) >> shift) & 1023];
            output[out + 2] = limit[((tmp12 + tmp1 + round) >> shift) & 1023];
            output[out + 5] = limit[((tmp12 - tmp1 + round) >> shift) & 1023];
            output[out + 3] = limit[((tmp13 + tmp0 + round) >> shift) & 1023];
            output[out + 4] = limit[((tmp13 - tmp0 + round) >> shift) & 1023];
        }
    }
}
