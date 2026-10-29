/* Ports libjpeg-turbo (jidctint.c, jpeg_idct_islow). Copyright (C) 1991-1998, Thomas G. Lane; modification
   developed 2002-2018 by Guido Vollbeding; Copyright (C) 2015-2026, D. R. Commander; IJG License. Modified for
   Smalt. */
package com.github.auties00.smalt.image.jpeg.internal;

/**
 * Computes the accurate integer inverse DCT of the blocks of the frames of a hierarchical image, at 8 or 12 bits,
 * into integer samples.
 *
 * <p>A block of a non-differential frame is level-shifted and range-limited exactly as {@link ScalarIdctKernel} and
 * {@link Idct12} transform it, so its samples equal libjpeg-turbo's {@code jpeg_idct_islow}. A block of a
 * differential frame is transformed with the same arithmetic but neither level-shifted nor range-limited
 * (T.81 J.2.3.1): its samples are the signed descaled outputs. Arguments are not checked; values outside the
 * documented ranges give unspecified results.
 */
final class HierarchicalIdct {
    /**
     * Prevents instantiation.
     */
    private HierarchicalIdct() {
        throw new AssertionError();
    }

    /**
     * Transforms one block into samples and clears it.
     *
     * @param c the 64 dequantized coefficients in natural order; zero on return
     * @param precision the sample precision, 8 or 12
     * @param differential whether the block belongs to a differential frame
     * @param output the destination samples
     * @param offset the index of the top-left output sample
     * @param stride the distance between output rows
     * @param columns the number of columns written, from 1 to 8
     * @param rows the number of rows written, from 1 to 8
     */
    static void transform(int[] c, int precision, boolean differential, int[] output, int offset, int stride,
                          int columns, int rows) {
        var pass1 = precision == 12 ? 1 : 2;
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
                var dc = in0 << pass1;
                for (var k = 0; k < 64; k += 8) {
                    c[k + col] = dc;
                }
                continue;
            }
            var z1 = (in2 + in6) * ScalarIdctKernel.FIX_0_541196100;
            var tmp2 = z1 + in6 * -ScalarIdctKernel.FIX_1_847759065;
            var tmp3 = z1 + in2 * ScalarIdctKernel.FIX_0_765366865;
            var tmp0 = (in0 + in4) << ScalarIdctKernel.CONST_BITS;
            var tmp1 = (in0 - in4) << ScalarIdctKernel.CONST_BITS;
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

            var round = 1 << (ScalarIdctKernel.CONST_BITS - pass1 - 1);
            var shift = ScalarIdctKernel.CONST_BITS - pass1;
            c[col] = (tmp10 + tmp3 + round) >> shift;
            c[56 + col] = (tmp10 - tmp3 + round) >> shift;
            c[8 + col] = (tmp11 + tmp2 + round) >> shift;
            c[48 + col] = (tmp11 - tmp2 + round) >> shift;
            c[16 + col] = (tmp12 + tmp1 + round) >> shift;
            c[40 + col] = (tmp12 - tmp1 + round) >> shift;
            c[24 + col] = (tmp13 + tmp0 + round) >> shift;
            c[32 + col] = (tmp13 - tmp0 + round) >> shift;
        }
        for (var y = 0; y < 8; y++) {
            var base = y * 8;
            var w0 = c[base];
            var w1 = c[base + 1];
            var w2 = c[base + 2];
            var w3 = c[base + 3];
            var w4 = c[base + 4];
            var w5 = c[base + 5];
            var w6 = c[base + 6];
            var w7 = c[base + 7];
            for (var k = 0; k < 8; k++) {
                c[base + k] = 0;
            }
            if (y >= rows) {
                continue;
            }
            var out = offset + y * stride;
            if ((w1 | w2 | w3 | w4 | w5 | w6 | w7) == 0) {
                var value = sample((w0 + (1 << (pass1 + 2))) >> (pass1 + 3), precision, differential);
                for (var x = 0; x < columns; x++) {
                    output[out + x] = value;
                }
                continue;
            }
            var z1 = (w2 + w6) * ScalarIdctKernel.FIX_0_541196100;
            var tmp2 = z1 + w6 * -ScalarIdctKernel.FIX_1_847759065;
            var tmp3 = z1 + w2 * ScalarIdctKernel.FIX_0_765366865;
            var tmp0 = (w0 + w4) << ScalarIdctKernel.CONST_BITS;
            var tmp1 = (w0 - w4) << ScalarIdctKernel.CONST_BITS;
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

            var round = 1 << (ScalarIdctKernel.CONST_BITS + pass1 + 2);
            var shift = ScalarIdctKernel.CONST_BITS + pass1 + 3;
            for (var x = 0; x < columns; x++) {
                var t = switch (x) {
                    case 0 -> tmp10 + tmp3;
                    case 1 -> tmp11 + tmp2;
                    case 2 -> tmp12 + tmp1;
                    case 3 -> tmp13 + tmp0;
                    case 4 -> tmp13 - tmp0;
                    case 5 -> tmp12 - tmp1;
                    case 6 -> tmp11 - tmp2;
                    default -> tmp10 - tmp3;
                };
                output[out + x] = sample((t + round) >> shift, precision, differential);
            }
        }
    }

    /**
     * Turns a descaled output into a sample.
     *
     * @param t the descaled output
     * @param precision the sample precision, 8 or 12
     * @param differential whether the block belongs to a differential frame
     * @return {@code t} for a differential frame, else the level-shifted, range-limited sample
     */
    private static int sample(int t, int precision, boolean differential) {
        if (differential) {
            return t;
        }
        return precision == 12 ? Idct12.LIMIT[t & 16383] : ScalarIdctKernel.LIMIT[t & 1023] & 0xFF;
    }
}
