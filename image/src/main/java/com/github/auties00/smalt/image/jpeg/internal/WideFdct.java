/* Ports libjpeg-turbo (jfdctint.c, jpeg_fdct_islow at 8 and 12 bits; jcdctmgr.c, quantize). Copyright (C)
   1991-1996, Thomas G. Lane; Copyright (C) 2015-2022, D. R. Commander; IJG License. Modified for Smalt. */
package com.github.auties00.smalt.image.jpeg.internal;

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

/**
 * Computes the accurate integer forward DCT of one 8x8 block of 8-bit or 12-bit samples and quantizes it
 * by plain division, the transform of the frames the byte kernels do not serve: 12-bit samples, and
 * quantizers above 255.
 *
 * <p>The samples are centered by subtracting {@code 2^(precision - 1)} and transformed as libjpeg-turbo's
 * {@code jpeg_fdct_islow} does at that precision (two extra bits between the passes at 8 bits, one at 12);
 * each output, scaled by 8, is divided by {@code 8 * quantizer} with rounding half away from zero. At 8 bits
 * the coefficients equal {@link ScalarFdctKernel}'s for the quantizers that kernel takes. Arguments are not
 * checked; values outside the documented ranges give unspecified results.
 */
final class WideFdct {
    /**
     * Prevents instantiation.
     */
    private WideFdct() {
        throw new AssertionError();
    }

    /**
     * Transforms and quantizes one block.
     *
     * @param samples the source samples
     * @param offset the index of the top-left sample
     * @param stride the distance between sample rows
     * @param precision the sample precision, 8 or 12
     * @param table the 64 quantizers in natural order, from 1 to 32767
     * @param out the destination coefficients, natural order
     * @param outOffset the index of the block's first coefficient in {@code out}
     * @param d a scratch array of 64 values owned by the calling thread
     */
    static void transform(short[] samples, int offset, int stride, int precision, int[] table, short[] out,
                          int outOffset, long[] d) {
        var pass1 = precision == 8 ? 2 : 1;
        var center = 1 << (precision - 1);
        var shift1 = CONST_BITS - pass1;
        var round1 = 1L << (shift1 - 1);
        for (var row = 0; row < 8; row++) {
            var in = offset + row * stride;
            var base = row * 8;
            long s0 = samples[in] - center;
            long s1 = samples[in + 1] - center;
            long s2 = samples[in + 2] - center;
            long s3 = samples[in + 3] - center;
            long s4 = samples[in + 4] - center;
            long s5 = samples[in + 5] - center;
            long s6 = samples[in + 6] - center;
            long s7 = samples[in + 7] - center;
            var tmp0 = s0 + s7;
            var tmp7 = s0 - s7;
            var tmp1 = s1 + s6;
            var tmp6 = s1 - s6;
            var tmp2 = s2 + s5;
            var tmp5 = s2 - s5;
            var tmp3 = s3 + s4;
            var tmp4 = s3 - s4;
            var tmp10 = tmp0 + tmp3;
            var tmp13 = tmp0 - tmp3;
            var tmp11 = tmp1 + tmp2;
            var tmp12 = tmp1 - tmp2;
            d[base] = (tmp10 + tmp11) << pass1;
            d[base + 4] = (tmp10 - tmp11) << pass1;
            var z1 = (tmp12 + tmp13) * FIX_0_541196100;
            d[base + 2] = (z1 + tmp13 * FIX_0_765366865 + round1) >> shift1;
            d[base + 6] = (z1 + tmp12 * -FIX_1_847759065 + round1) >> shift1;
            z1 = tmp4 + tmp7;
            var z2 = tmp5 + tmp6;
            var z3 = tmp4 + tmp6;
            var z4 = tmp5 + tmp7;
            var z5 = (z3 + z4) * FIX_1_175875602;
            tmp4 = tmp4 * FIX_0_298631336;
            tmp5 = tmp5 * FIX_2_053119869;
            tmp6 = tmp6 * FIX_3_072711026;
            tmp7 = tmp7 * FIX_1_501321110;
            z1 = z1 * -FIX_0_899976223;
            z2 = z2 * -FIX_2_562915447;
            z3 = z3 * -FIX_1_961570560 + z5;
            z4 = z4 * -FIX_0_390180644 + z5;
            d[base + 7] = (tmp4 + z1 + z3 + round1) >> shift1;
            d[base + 5] = (tmp5 + z2 + z4 + round1) >> shift1;
            d[base + 3] = (tmp6 + z2 + z3 + round1) >> shift1;
            d[base + 1] = (tmp7 + z1 + z4 + round1) >> shift1;
        }
        var round = 1L << (pass1 - 1);
        var shift2 = CONST_BITS + pass1;
        var round2 = 1L << (shift2 - 1);
        for (var col = 0; col < 8; col++) {
            var tmp0 = d[col] + d[56 + col];
            var tmp7 = d[col] - d[56 + col];
            var tmp1 = d[8 + col] + d[48 + col];
            var tmp6 = d[8 + col] - d[48 + col];
            var tmp2 = d[16 + col] + d[40 + col];
            var tmp5 = d[16 + col] - d[40 + col];
            var tmp3 = d[24 + col] + d[32 + col];
            var tmp4 = d[24 + col] - d[32 + col];
            var tmp10 = tmp0 + tmp3;
            var tmp13 = tmp0 - tmp3;
            var tmp11 = tmp1 + tmp2;
            var tmp12 = tmp1 - tmp2;
            var out0 = (tmp10 + tmp11 + round) >> pass1;
            var out4 = (tmp10 - tmp11 + round) >> pass1;
            var z1 = (tmp12 + tmp13) * FIX_0_541196100;
            var out2 = (z1 + tmp13 * FIX_0_765366865 + round2) >> shift2;
            var out6 = (z1 + tmp12 * -FIX_1_847759065 + round2) >> shift2;
            z1 = tmp4 + tmp7;
            var z2 = tmp5 + tmp6;
            var z3 = tmp4 + tmp6;
            var z4 = tmp5 + tmp7;
            var z5 = (z3 + z4) * FIX_1_175875602;
            tmp4 = tmp4 * FIX_0_298631336;
            tmp5 = tmp5 * FIX_2_053119869;
            tmp6 = tmp6 * FIX_3_072711026;
            tmp7 = tmp7 * FIX_1_501321110;
            z1 = z1 * -FIX_0_899976223;
            z2 = z2 * -FIX_2_562915447;
            z3 = z3 * -FIX_1_961570560 + z5;
            z4 = z4 * -FIX_0_390180644 + z5;
            var out7 = (tmp4 + z1 + z3 + round2) >> shift2;
            var out5 = (tmp5 + z2 + z4 + round2) >> shift2;
            var out3 = (tmp6 + z2 + z3 + round2) >> shift2;
            var out1 = (tmp7 + z1 + z4 + round2) >> shift2;
            out[outOffset + col] = quantize(out0, table[col]);
            out[outOffset + 8 + col] = quantize(out1, table[8 + col]);
            out[outOffset + 16 + col] = quantize(out2, table[16 + col]);
            out[outOffset + 24 + col] = quantize(out3, table[24 + col]);
            out[outOffset + 32 + col] = quantize(out4, table[32 + col]);
            out[outOffset + 40 + col] = quantize(out5, table[40 + col]);
            out[outOffset + 48 + col] = quantize(out6, table[48 + col]);
            out[outOffset + 56 + col] = quantize(out7, table[56 + col]);
        }
    }

    /**
     * Quantizes one scaled forward DCT output.
     *
     * @param value the output, scaled by 8
     * @param quantizer the quantization step, from 1 to 32767
     * @return the quantized coefficient
     */
    private static short quantize(long value, int quantizer) {
        long divisor = quantizer << 3;
        var magnitude = value < 0 ? -value : value;
        var quotient = (magnitude + (divisor >> 1)) / divisor;
        return (short) (value < 0 ? -quotient : quotient);
    }
}
