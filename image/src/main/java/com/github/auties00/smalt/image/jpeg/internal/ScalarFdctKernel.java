/* Ports libjpeg-turbo (jfdctint.c, jpeg_fdct_islow; jcdctmgr.c). Copyright (C) 1991-1996, Thomas G. Lane;
   Copyright (C) 2015-2022, D. R. Commander; IJG License. Modified for Smalt. */
package com.github.auties00.smalt.image.jpeg.internal;

import static com.github.auties00.smalt.image.jpeg.internal.ScalarIdctKernel.*;

/**
 * Computes the accurate integer forward DCT of one 8x8 block of samples and quantizes it, the
 * transform of single blocks; the batched {@link BatchFdctKernel} transforms several at once.
 *
 * <p>The samples are read as eight rows of eight bytes, centered by subtracting 128, transformed
 * as libjpeg-turbo's {@code jpeg_fdct_islow} does, and quantized with the reciprocals of a
 * {@link QuantDivisors}; the coefficients are bit-exact with libjpeg-turbo's encoder. Arguments are
 * not checked; values outside the documented ranges give unspecified results.
 *
 * @implNote This implementation is the Loeffler-Ligtenberg-Moschytz algorithm with 13-bit
 * constants and two extra bits of precision between the passes, the second pass quantizing each
 * coefficient as it is produced.
 */
final class ScalarFdctKernel {
    /**
     * Constructs the kernel.
     */
    ScalarFdctKernel() {
    }

    /**
     * Transforms and quantizes one block.
     *
     * @param samples the source samples
     * @param offset the index of the top-left sample
     * @param stride the distance between sample rows
     * @param divisors the quantization table
     * @param d the 64 quantized coefficients, written in natural order
     * @return the natural-order mask of nonzero coefficients: bit {@code n} is set when
     * {@code d[n] != 0}
     */
    long transform(byte[] samples, int offset, int stride, QuantDivisors divisors, int[] d) {
        for (var row = 0; row < 8; row++) {
            var in = offset + row * stride;
            var base = row * 8;
            var s0 = (samples[in] & 0xFF) - 128;
            var s1 = (samples[in + 1] & 0xFF) - 128;
            var s2 = (samples[in + 2] & 0xFF) - 128;
            var s3 = (samples[in + 3] & 0xFF) - 128;
            var s4 = (samples[in + 4] & 0xFF) - 128;
            var s5 = (samples[in + 5] & 0xFF) - 128;
            var s6 = (samples[in + 6] & 0xFF) - 128;
            var s7 = (samples[in + 7] & 0xFF) - 128;
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
            d[base] = (tmp10 + tmp11) << PASS1_BITS;
            d[base + 4] = (tmp10 - tmp11) << PASS1_BITS;
            var z1 = (tmp12 + tmp13) * FIX_0_541196100;
            var round = 1 << (CONST_BITS - PASS1_BITS - 1);
            var shift = CONST_BITS - PASS1_BITS;
            d[base + 2] = (z1 + tmp13 * FIX_0_765366865 + round) >> shift;
            d[base + 6] = (z1 + tmp12 * -FIX_1_847759065 + round) >> shift;
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
            d[base + 7] = (tmp4 + z1 + z3 + round) >> shift;
            d[base + 5] = (tmp5 + z2 + z4 + round) >> shift;
            d[base + 3] = (tmp6 + z2 + z3 + round) >> shift;
            d[base + 1] = (tmp7 + z1 + z4 + round) >> shift;
        }
        var mask = 0L;
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
            var round = 1 << (PASS1_BITS - 1);
            var out0 = (tmp10 + tmp11 + round) >> PASS1_BITS;
            var out4 = (tmp10 - tmp11 + round) >> PASS1_BITS;
            var z1 = (tmp12 + tmp13) * FIX_0_541196100;
            var round2 = 1 << (CONST_BITS + PASS1_BITS - 1);
            var shift2 = CONST_BITS + PASS1_BITS;
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
            mask |= store(d, col, divisors.quantize(out0, col));
            mask |= store(d, 8 + col, divisors.quantize(out1, 8 + col));
            mask |= store(d, 16 + col, divisors.quantize(out2, 16 + col));
            mask |= store(d, 24 + col, divisors.quantize(out3, 24 + col));
            mask |= store(d, 32 + col, divisors.quantize(out4, 32 + col));
            mask |= store(d, 40 + col, divisors.quantize(out5, 40 + col));
            mask |= store(d, 48 + col, divisors.quantize(out6, 48 + col));
            mask |= store(d, 56 + col, divisors.quantize(out7, 56 + col));
        }
        return mask;
    }

    /**
     * Stores one quantized coefficient and returns its bit of the nonzero mask.
     *
     * @param output the coefficient array
     * @param index the natural-order index
     * @param value the quantized coefficient
     * @return {@code 1L << index} when {@code value} is nonzero, zero otherwise
     */
    private static long store(int[] output, int index, int value) {
        output[index] = value;
        return value != 0 ? 1L << index : 0;
    }
}
