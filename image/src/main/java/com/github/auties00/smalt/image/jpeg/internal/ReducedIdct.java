package com.github.auties00.smalt.image.jpeg.internal;

import java.util.Arrays;

/**
 * Computes reduced-size inverse DCTs that turn one 8x8 coefficient block into 4x4, 2x2 or 1x1
 * samples, for decoding at 1/2, 1/4 or 1/8 of the stored size.
 *
 * <p>Each method reads a block of dequantized coefficients in natural order, writes the samples,
 * and clears the block. Only the coefficients the output depends on are read, so decoding at a
 * reduced size skips most of the transform work.
 *
 * @implNote This implementation is a port of libjpeg-turbo's jidctred.c
 * ({@code jpeg_idct_4x4}, {@code jpeg_idct_2x2}, {@code jpeg_idct_1x1}), including its zero-column
 * and zero-row shortcuts and its range limiting, so it is bit-exact with libjpeg-turbo's scaled
 * decoding.
 */
public final class ReducedIdct {
    /**
     * The number of fraction bits of the fixed-point constants.
     */
    private static final int CONST_BITS = 13;

    /**
     * The number of extra precision bits kept between the two passes.
     */
    private static final int PASS1_BITS = 2;

    /**
     * {@code FIX(0.211164243)}.
     */
    static final int FIX_0_211164243 = 1730;

    /**
     * {@code FIX(0.509795579)}.
     */
    static final int FIX_0_509795579 = 4176;

    /**
     * {@code FIX(0.601344887)}.
     */
    static final int FIX_0_601344887 = 4926;

    /**
     * {@code FIX(0.720959822)}.
     */
    static final int FIX_0_720959822 = 5906;

    /**
     * {@code FIX(0.765366865)}.
     */
    static final int FIX_0_765366865 = 6270;

    /**
     * {@code FIX(0.850430095)}.
     */
    static final int FIX_0_850430095 = 6967;

    /**
     * {@code FIX(0.899976223)}.
     */
    static final int FIX_0_899976223 = 7373;

    /**
     * {@code FIX(1.061594337)}.
     */
    static final int FIX_1_061594337 = 8697;

    /**
     * {@code FIX(1.272758580)}.
     */
    static final int FIX_1_272758580 = 10426;

    /**
     * {@code FIX(1.451774981)}.
     */
    static final int FIX_1_451774981 = 11893;

    /**
     * {@code FIX(1.847759065)}.
     */
    static final int FIX_1_847759065 = 15137;

    /**
     * {@code FIX(2.172734803)}.
     */
    static final int FIX_2_172734803 = 17799;

    /**
     * {@code FIX(2.562915447)}.
     */
    static final int FIX_2_562915447 = 20995;

    /**
     * {@code FIX(3.624509785)}.
     */
    static final int FIX_3_624509785 = 29692;

    /**
     * Prevents instantiation.
     */
    private ReducedIdct() {
        throw new AssertionError();
    }

    /**
     * Transforms one block into 4x4 samples and clears it.
     *
     * @param c the 64 dequantized coefficients in natural order; zero on return
     * @param output the destination samples
     * @param offset the index of the top-left output sample
     * @param stride the distance between output rows
     */
    public static void idct4(int[] c, byte[] output, int offset, int stride) {
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
            var tmp2 = in2 * FIX_1_847759065 + in6 * -FIX_0_765366865;
            var tmp10 = tmp0 + tmp2;
            var tmp12 = tmp0 - tmp2;
            tmp0 = in7 * -FIX_0_211164243 + in5 * FIX_1_451774981 + in3 * -FIX_2_172734803 + in1 * FIX_1_061594337;
            tmp2 = in7 * -FIX_0_509795579 + in5 * -FIX_0_601344887 + in3 * FIX_0_899976223 + in1 * FIX_2_562915447;
            var shift = CONST_BITS - PASS1_BITS + 1;
            var round = 1 << (shift - 1);
            c[col] = (tmp10 + tmp2 + round) >> shift;
            c[24 + col] = (tmp10 - tmp2 + round) >> shift;
            c[8 + col] = (tmp12 + tmp0 + round) >> shift;
            c[16 + col] = (tmp12 - tmp0 + round) >> shift;
        }
        var limit = ScalarIdctKernel.LIMIT;
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
                var value = limit[((w0 + (1 << (PASS1_BITS + 2))) >> (PASS1_BITS + 3)) & 1023];
                output[out] = value;
                output[out + 1] = value;
                output[out + 2] = value;
                output[out + 3] = value;
                continue;
            }
            var tmp0 = w0 << (CONST_BITS + 1);
            var tmp2 = w2 * FIX_1_847759065 + w6 * -FIX_0_765366865;
            var tmp10 = tmp0 + tmp2;
            var tmp12 = tmp0 - tmp2;
            tmp0 = w7 * -FIX_0_211164243 + w5 * FIX_1_451774981 + w3 * -FIX_2_172734803 + w1 * FIX_1_061594337;
            tmp2 = w7 * -FIX_0_509795579 + w5 * -FIX_0_601344887 + w3 * FIX_0_899976223 + w1 * FIX_2_562915447;
            var shift = CONST_BITS + PASS1_BITS + 3 + 1;
            var round = 1 << (shift - 1);
            output[out] = limit[((tmp10 + tmp2 + round) >> shift) & 1023];
            output[out + 3] = limit[((tmp10 - tmp2 + round) >> shift) & 1023];
            output[out + 1] = limit[((tmp12 + tmp0 + round) >> shift) & 1023];
            output[out + 2] = limit[((tmp12 - tmp0 + round) >> shift) & 1023];
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
    public static void idct2(int[] c, byte[] output, int offset, int stride) {
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
            var tmp0 = in7 * -FIX_0_720959822 + in5 * FIX_0_850430095 + in3 * -FIX_1_272758580 + in1 * FIX_3_624509785;
            var shift = CONST_BITS - PASS1_BITS + 2;
            var round = 1 << (shift - 1);
            c[col] = (tmp10 + tmp0 + round) >> shift;
            c[8 + col] = (tmp10 - tmp0 + round) >> shift;
        }
        var limit = ScalarIdctKernel.LIMIT;
        for (var row = 0; row < 2; row++) {
            var base = row * 8;
            var out = offset + row * stride;
            var w0 = c[base];
            var w1 = c[base + 1];
            var w3 = c[base + 3];
            var w5 = c[base + 5];
            var w7 = c[base + 7];
            if ((w1 | w3 | w5 | w7) == 0) {
                var value = limit[((w0 + (1 << (PASS1_BITS + 2))) >> (PASS1_BITS + 3)) & 1023];
                output[out] = value;
                output[out + 1] = value;
                continue;
            }
            var tmp10 = w0 << (CONST_BITS + 2);
            var tmp0 = w7 * -FIX_0_720959822 + w5 * FIX_0_850430095 + w3 * -FIX_1_272758580 + w1 * FIX_3_624509785;
            var shift = CONST_BITS + PASS1_BITS + 3 + 2;
            var round = 1 << (shift - 1);
            output[out] = limit[((tmp10 + tmp0 + round) >> shift) & 1023];
            output[out + 1] = limit[((tmp10 - tmp0 + round) >> shift) & 1023];
        }
        Arrays.fill(c, 0, 64, 0);
    }

    /**
     * Returns the 1x1 output of a block from its dequantized DC coefficient.
     *
     * @param dc the dequantized DC coefficient
     * @return the sample
     */
    public static byte dcValue1(int dc) {
        return ScalarIdctKernel.LIMIT[((dc + 4) >> 3) & 1023];
    }

    /**
     * Returns the output of every sample of a 4x4 or 2x2 transform whose only nonzero coefficient
     * is the DC coefficient; the same value as the 8x8 transform of such a block.
     *
     * @param dc the dequantized DC coefficient
     * @return the sample
     */
    public static byte dcValue(int dc) {
        return ScalarIdctKernel.LIMIT[(((dc << PASS1_BITS) + (1 << (PASS1_BITS + 2))) >> (PASS1_BITS + 3)) & 1023];
    }
}
