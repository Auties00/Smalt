package com.github.auties00.smalt.image.jpeg.internal;

import java.util.Arrays;

import static com.github.auties00.smalt.image.jpeg.internal.ScalarIdctKernel.CONST_BITS;
import static com.github.auties00.smalt.image.jpeg.internal.ScalarIdctKernel.FIX_0_298631336;
import static com.github.auties00.smalt.image.jpeg.internal.ScalarIdctKernel.FIX_0_390180644;
import static com.github.auties00.smalt.image.jpeg.internal.ScalarIdctKernel.FIX_0_541196100;
import static com.github.auties00.smalt.image.jpeg.internal.ScalarIdctKernel.FIX_0_765366865;
import static com.github.auties00.smalt.image.jpeg.internal.ScalarIdctKernel.FIX_0_899976223;
import static com.github.auties00.smalt.image.jpeg.internal.ScalarIdctKernel.FIX_1_175875602;
import static com.github.auties00.smalt.image.jpeg.internal.ScalarIdctKernel.FIX_1_501321110;
import static com.github.auties00.smalt.image.jpeg.internal.ScalarIdctKernel.FIX_1_847759065;
import static com.github.auties00.smalt.image.jpeg.internal.ScalarIdctKernel.FIX_1_961570560;
import static com.github.auties00.smalt.image.jpeg.internal.ScalarIdctKernel.FIX_2_053119869;
import static com.github.auties00.smalt.image.jpeg.internal.ScalarIdctKernel.FIX_2_562915447;
import static com.github.auties00.smalt.image.jpeg.internal.ScalarIdctKernel.FIX_3_072711026;
import static com.github.auties00.smalt.image.jpeg.internal.ScalarIdctKernel.LIMIT;
import static com.github.auties00.smalt.image.jpeg.internal.ScalarIdctKernel.PASS1_BITS;

/**
 * Implements {@link BatchIdctKernel} with plain Java arithmetic, one block at a time.
 *
 * <p>This is the reference implementation: each block is transformed in place in the interleaved
 * layout with exactly the arithmetic, shortcuts and range limiting of {@link ScalarIdctKernel}
 * and {@link ReducedIdct}, so the vector implementation must produce identical samples.
 */
final class ScalarBatchIdctKernel implements BatchIdctKernel {
    /**
     * {@code FIX(0.211164243)}.
     */
    private static final int FIX_0_211164243 = 1730;

    /**
     * {@code FIX(0.509795579)}.
     */
    private static final int FIX_0_509795579 = 4176;

    /**
     * {@code FIX(0.601344887)}.
     */
    private static final int FIX_0_601344887 = 4926;

    /**
     * {@code FIX(0.720959822)}.
     */
    private static final int FIX_0_720959822 = 5906;

    /**
     * {@code FIX(0.850430095)}.
     */
    private static final int FIX_0_850430095 = 6967;

    /**
     * {@code FIX(1.061594337)}.
     */
    private static final int FIX_1_061594337 = 8697;

    /**
     * {@code FIX(1.272758580)}.
     */
    private static final int FIX_1_272758580 = 10426;

    /**
     * {@code FIX(1.451774981)}.
     */
    private static final int FIX_1_451774981 = 11893;

    /**
     * {@code FIX(2.172734803)}.
     */
    private static final int FIX_2_172734803 = 17799;

    /**
     * {@code FIX(3.624509785)}.
     */
    private static final int FIX_3_624509785 = 29692;

    /**
     * The distance between two rows of coefficients of one block in the interleaved layout.
     */
    private static final int ROW = 8 * LANES;

    /**
     * Constructs the kernel.
     */
    ScalarBatchIdctKernel() {
    }

    @Override
    public void idct8(int[] c, int count, byte[] output, int offset, int stride) {
        for (var b = 0; b < count; b++) {
            for (var col = 0; col < 8; col++) {
                column8(c, col * LANES + b);
            }
            for (var row = 0; row < 8; row++) {
                row8(c, row * ROW + b, output, offset + row * stride + b * 8);
            }
        }
        Arrays.fill(c, 0, BATCH_LENGTH, 0);
    }

    /**
     * Runs the column pass of the 8x8 transform on one column of one block, in place.
     *
     * @param c    the interleaved coefficients
     * @param base the index of the column's first coefficient
     */
    private static void column8(int[] c, int base) {
        var in0 = c[base];
        var in1 = c[base + ROW];
        var in2 = c[base + 2 * ROW];
        var in3 = c[base + 3 * ROW];
        var in4 = c[base + 4 * ROW];
        var in5 = c[base + 5 * ROW];
        var in6 = c[base + 6 * ROW];
        var in7 = c[base + 7 * ROW];
        if ((in1 | in2 | in3 | in4 | in5 | in6 | in7) == 0) {
            var dc = in0 << PASS1_BITS;
            for (var r = 0; r < 8; r++) {
                c[base + r * ROW] = dc;
            }
            return;
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
        var odd0 = in7;
        var odd1 = in5;
        var odd2 = in3;
        var odd3 = in1;
        z1 = odd0 + odd3;
        var z2 = odd1 + odd2;
        var z3 = odd0 + odd2;
        var z4 = odd1 + odd3;
        var z5 = (z3 + z4) * FIX_1_175875602;
        odd0 = odd0 * FIX_0_298631336;
        odd1 = odd1 * FIX_2_053119869;
        odd2 = odd2 * FIX_3_072711026;
        odd3 = odd3 * FIX_1_501321110;
        z1 = z1 * -FIX_0_899976223;
        z2 = z2 * -FIX_2_562915447;
        z3 = z3 * -FIX_1_961570560 + z5;
        z4 = z4 * -FIX_0_390180644 + z5;
        odd0 += z1 + z3;
        odd1 += z2 + z4;
        odd2 += z2 + z3;
        odd3 += z1 + z4;
        var round = 1 << (CONST_BITS - PASS1_BITS - 1);
        var shift = CONST_BITS - PASS1_BITS;
        c[base] = (tmp10 + odd3 + round) >> shift;
        c[base + 7 * ROW] = (tmp10 - odd3 + round) >> shift;
        c[base + ROW] = (tmp11 + odd2 + round) >> shift;
        c[base + 6 * ROW] = (tmp11 - odd2 + round) >> shift;
        c[base + 2 * ROW] = (tmp12 + odd1 + round) >> shift;
        c[base + 5 * ROW] = (tmp12 - odd1 + round) >> shift;
        c[base + 3 * ROW] = (tmp13 + odd0 + round) >> shift;
        c[base + 4 * ROW] = (tmp13 - odd0 + round) >> shift;
    }

    /**
     * Runs the row pass of the 8x8 transform on one row of one block and stores its samples.
     *
     * @param c      the interleaved column-pass results
     * @param base   the index of the row's first value
     * @param output the destination samples
     * @param out    the index of the row's first sample
     */
    private static void row8(int[] c, int base, byte[] output, int out) {
        var w0 = c[base];
        var w1 = c[base + LANES];
        var w2 = c[base + 2 * LANES];
        var w3 = c[base + 3 * LANES];
        var w4 = c[base + 4 * LANES];
        var w5 = c[base + 5 * LANES];
        var w6 = c[base + 6 * LANES];
        var w7 = c[base + 7 * LANES];
        var limit = LIMIT;
        if ((w1 | w2 | w3 | w4 | w5 | w6 | w7) == 0) {
            var value = limit[((w0 + (1 << (PASS1_BITS + 2))) >> (PASS1_BITS + 3)) & 1023];
            Arrays.fill(output, out, out + 8, value);
            return;
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
        var odd0 = w7;
        var odd1 = w5;
        var odd2 = w3;
        var odd3 = w1;
        z1 = odd0 + odd3;
        var z2 = odd1 + odd2;
        var z3 = odd0 + odd2;
        var z4 = odd1 + odd3;
        var z5 = (z3 + z4) * FIX_1_175875602;
        odd0 = odd0 * FIX_0_298631336;
        odd1 = odd1 * FIX_2_053119869;
        odd2 = odd2 * FIX_3_072711026;
        odd3 = odd3 * FIX_1_501321110;
        z1 = z1 * -FIX_0_899976223;
        z2 = z2 * -FIX_2_562915447;
        z3 = z3 * -FIX_1_961570560 + z5;
        z4 = z4 * -FIX_0_390180644 + z5;
        odd0 += z1 + z3;
        odd1 += z2 + z4;
        odd2 += z2 + z3;
        odd3 += z1 + z4;
        var round = 1 << (CONST_BITS + PASS1_BITS + 2);
        var shift = CONST_BITS + PASS1_BITS + 3;
        output[out] = limit[((tmp10 + odd3 + round) >> shift) & 1023];
        output[out + 7] = limit[((tmp10 - odd3 + round) >> shift) & 1023];
        output[out + 1] = limit[((tmp11 + odd2 + round) >> shift) & 1023];
        output[out + 6] = limit[((tmp11 - odd2 + round) >> shift) & 1023];
        output[out + 2] = limit[((tmp12 + odd1 + round) >> shift) & 1023];
        output[out + 5] = limit[((tmp12 - odd1 + round) >> shift) & 1023];
        output[out + 3] = limit[((tmp13 + odd0 + round) >> shift) & 1023];
        output[out + 4] = limit[((tmp13 - odd0 + round) >> shift) & 1023];
    }

    @Override
    public void idct4(int[] c, int count, byte[] output, int offset, int stride) {
        for (var b = 0; b < count; b++) {
            for (var col = 0; col < 8; col++) {
                if (col != 4) {
                    column4(c, col * LANES + b);
                }
            }
            for (var row = 0; row < 4; row++) {
                row4(c, row * ROW + b, output, offset + row * stride + b * 4);
            }
        }
        Arrays.fill(c, 0, BATCH_LENGTH, 0);
    }

    /**
     * Runs the column pass of the 4x4 reduced transform on one column of one block, in place.
     *
     * @param c    the interleaved coefficients
     * @param base the index of the column's first coefficient
     */
    private static void column4(int[] c, int base) {
        var in0 = c[base];
        var in1 = c[base + ROW];
        var in2 = c[base + 2 * ROW];
        var in3 = c[base + 3 * ROW];
        var in5 = c[base + 5 * ROW];
        var in6 = c[base + 6 * ROW];
        var in7 = c[base + 7 * ROW];
        if ((in1 | in2 | in3 | in5 | in6 | in7) == 0) {
            var dc = in0 << PASS1_BITS;
            c[base] = dc;
            c[base + ROW] = dc;
            c[base + 2 * ROW] = dc;
            c[base + 3 * ROW] = dc;
            return;
        }
        var tmp0 = in0 << (CONST_BITS + 1);
        var tmp2 = in2 * FIX_1_847759065 + in6 * -FIX_0_765366865;
        var tmp10 = tmp0 + tmp2;
        var tmp12 = tmp0 - tmp2;
        tmp0 = in7 * -FIX_0_211164243 + in5 * FIX_1_451774981 + in3 * -FIX_2_172734803 + in1 * FIX_1_061594337;
        tmp2 = in7 * -FIX_0_509795579 + in5 * -FIX_0_601344887 + in3 * FIX_0_899976223 + in1 * FIX_2_562915447;
        var shift = CONST_BITS - PASS1_BITS + 1;
        var round = 1 << (shift - 1);
        c[base] = (tmp10 + tmp2 + round) >> shift;
        c[base + 3 * ROW] = (tmp10 - tmp2 + round) >> shift;
        c[base + ROW] = (tmp12 + tmp0 + round) >> shift;
        c[base + 2 * ROW] = (tmp12 - tmp0 + round) >> shift;
    }

    /**
     * Runs the row pass of the 4x4 reduced transform on one row of one block and stores its
     * samples.
     *
     * @param c      the interleaved column-pass results
     * @param base   the index of the row's first value
     * @param output the destination samples
     * @param out    the index of the row's first sample
     */
    private static void row4(int[] c, int base, byte[] output, int out) {
        var w0 = c[base];
        var w1 = c[base + LANES];
        var w2 = c[base + 2 * LANES];
        var w3 = c[base + 3 * LANES];
        var w5 = c[base + 5 * LANES];
        var w6 = c[base + 6 * LANES];
        var w7 = c[base + 7 * LANES];
        var limit = LIMIT;
        if ((w1 | w2 | w3 | w5 | w6 | w7) == 0) {
            var value = limit[((w0 + (1 << (PASS1_BITS + 2))) >> (PASS1_BITS + 3)) & 1023];
            output[out] = value;
            output[out + 1] = value;
            output[out + 2] = value;
            output[out + 3] = value;
            return;
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

    @Override
    public void idct2(int[] c, int count, byte[] output, int offset, int stride) {
        for (var b = 0; b < count; b++) {
            for (var col = 0; col < 8; col += col == 0 ? 1 : 2) {
                column2(c, col * LANES + b);
            }
            for (var row = 0; row < 2; row++) {
                row2(c, row * ROW + b, output, offset + row * stride + b * 2);
            }
        }
        Arrays.fill(c, 0, BATCH_LENGTH, 0);
    }

    /**
     * Runs the column pass of the 2x2 reduced transform on one column of one block, in place.
     *
     * @param c    the interleaved coefficients
     * @param base the index of the column's first coefficient
     */
    private static void column2(int[] c, int base) {
        var in0 = c[base];
        var in1 = c[base + ROW];
        var in3 = c[base + 3 * ROW];
        var in5 = c[base + 5 * ROW];
        var in7 = c[base + 7 * ROW];
        if ((in1 | in3 | in5 | in7) == 0) {
            var dc = in0 << PASS1_BITS;
            c[base] = dc;
            c[base + ROW] = dc;
            return;
        }
        var tmp10 = in0 << (CONST_BITS + 2);
        var tmp0 = in7 * -FIX_0_720959822 + in5 * FIX_0_850430095 + in3 * -FIX_1_272758580 + in1 * FIX_3_624509785;
        var shift = CONST_BITS - PASS1_BITS + 2;
        var round = 1 << (shift - 1);
        c[base] = (tmp10 + tmp0 + round) >> shift;
        c[base + ROW] = (tmp10 - tmp0 + round) >> shift;
    }

    /**
     * Runs the row pass of the 2x2 reduced transform on one row of one block and stores its
     * samples.
     *
     * @param c      the interleaved column-pass results
     * @param base   the index of the row's first value
     * @param output the destination samples
     * @param out    the index of the row's first sample
     */
    private static void row2(int[] c, int base, byte[] output, int out) {
        var w0 = c[base];
        var w1 = c[base + LANES];
        var w3 = c[base + 3 * LANES];
        var w5 = c[base + 5 * LANES];
        var w7 = c[base + 7 * LANES];
        var limit = LIMIT;
        if ((w1 | w3 | w5 | w7) == 0) {
            var value = limit[((w0 + (1 << (PASS1_BITS + 2))) >> (PASS1_BITS + 3)) & 1023];
            output[out] = value;
            output[out + 1] = value;
            return;
        }
        var tmp10 = w0 << (CONST_BITS + 2);
        var tmp0 = w7 * -FIX_0_720959822 + w5 * FIX_0_850430095 + w3 * -FIX_1_272758580 + w1 * FIX_3_624509785;
        var shift = CONST_BITS + PASS1_BITS + 3 + 2;
        var round = 1 << (shift - 1);
        output[out] = limit[((tmp10 + tmp0 + round) >> shift) & 1023];
        output[out + 1] = limit[((tmp10 - tmp0 + round) >> shift) & 1023];
    }
}
