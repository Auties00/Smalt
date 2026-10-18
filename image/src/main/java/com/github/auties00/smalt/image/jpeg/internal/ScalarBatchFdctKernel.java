package com.github.auties00.smalt.image.jpeg.internal;

import static com.github.auties00.smalt.image.jpeg.internal.ScalarIdctKernel.*;

/**
 * Implements {@link BatchFdctKernel} with plain Java arithmetic, one block at a time.
 *
 * <p>Each block goes through the same accurate integer forward DCT and quantization as
 * {@link ScalarFdctKernel}, with every coefficient written straight into the block's lane of the
 * interleaved layout; it is the reference the vector implementation must match coefficient for
 * coefficient.
 *
 * @implNote This implementation repeats the arithmetic of {@link ScalarFdctKernel} rather than
 *           calling it with a variable output stride: with the stride a compile-time constant,
 *           every coefficient index is a constant offset from the lane, which keeps the array
 *           bounds checks out of the loops.
 */
final class ScalarBatchFdctKernel implements BatchFdctKernel {
    /**
     * Constructs the kernel.
     */
    ScalarBatchFdctKernel() {
    }

    @Override
    public void transform(byte[] samples, int offset, int stride, QuantDivisors divisors, int[] output, long[] masks,
                          byte[] scratch) {
        for (var b = 0; b < LANES; b++) {
            masks[b] = transformLane(samples, offset + 8 * b, stride, divisors, output, b);
        }
    }

    /**
     * Transforms and quantizes one block into its lane of the interleaved layout.
     *
     * @param samples  the source samples
     * @param offset   the index of the block's top-left sample
     * @param stride   the distance between sample rows
     * @param divisors the quantization table
     * @param d        the interleaved coefficients: natural-order coefficient {@code k} of the
     *                 block is written at {@code lane + k * LANES}
     * @param lane     the lane of the block
     * @return the natural-order mask of nonzero coefficients
     */
    private static long transformLane(byte[] samples, int offset, int stride, QuantDivisors divisors, int[] d,
                                      int lane) {
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
            d[lane + base * LANES] = (tmp10 + tmp11) << PASS1_BITS;
            d[lane + (base + 4) * LANES] = (tmp10 - tmp11) << PASS1_BITS;
            var z1 = (tmp12 + tmp13) * FIX_0_541196100;
            var round = 1 << (CONST_BITS - PASS1_BITS - 1);
            var shift = CONST_BITS - PASS1_BITS;
            d[lane + (base + 2) * LANES] = (z1 + tmp13 * FIX_0_765366865 + round) >> shift;
            d[lane + (base + 6) * LANES] = (z1 + tmp12 * -FIX_1_847759065 + round) >> shift;
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
            d[lane + (base + 7) * LANES] = (tmp4 + z1 + z3 + round) >> shift;
            d[lane + (base + 5) * LANES] = (tmp5 + z2 + z4 + round) >> shift;
            d[lane + (base + 3) * LANES] = (tmp6 + z2 + z3 + round) >> shift;
            d[lane + (base + 1) * LANES] = (tmp7 + z1 + z4 + round) >> shift;
        }
        var mask = 0L;
        for (var col = 0; col < 8; col++) {
            var tmp0 = d[lane + col * LANES] + d[lane + (56 + col) * LANES];
            var tmp7 = d[lane + col * LANES] - d[lane + (56 + col) * LANES];
            var tmp1 = d[lane + (8 + col) * LANES] + d[lane + (48 + col) * LANES];
            var tmp6 = d[lane + (8 + col) * LANES] - d[lane + (48 + col) * LANES];
            var tmp2 = d[lane + (16 + col) * LANES] + d[lane + (40 + col) * LANES];
            var tmp5 = d[lane + (16 + col) * LANES] - d[lane + (40 + col) * LANES];
            var tmp3 = d[lane + (24 + col) * LANES] + d[lane + (32 + col) * LANES];
            var tmp4 = d[lane + (24 + col) * LANES] - d[lane + (32 + col) * LANES];
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
            mask |= store(d, lane + col * LANES, col, divisors.quantize(out0, col));
            mask |= store(d, lane + (8 + col) * LANES, 8 + col, divisors.quantize(out1, 8 + col));
            mask |= store(d, lane + (16 + col) * LANES, 16 + col, divisors.quantize(out2, 16 + col));
            mask |= store(d, lane + (24 + col) * LANES, 24 + col, divisors.quantize(out3, 24 + col));
            mask |= store(d, lane + (32 + col) * LANES, 32 + col, divisors.quantize(out4, 32 + col));
            mask |= store(d, lane + (40 + col) * LANES, 40 + col, divisors.quantize(out5, 40 + col));
            mask |= store(d, lane + (48 + col) * LANES, 48 + col, divisors.quantize(out6, 48 + col));
            mask |= store(d, lane + (56 + col) * LANES, 56 + col, divisors.quantize(out7, 56 + col));
        }
        return mask;
    }

    /**
     * Stores one quantized coefficient and returns its bit of the nonzero mask.
     *
     * @param output   the interleaved coefficients
     * @param position the index in {@code output} of the coefficient
     * @param index    the natural-order index of the coefficient
     * @param value    the quantized coefficient
     * @return {@code 1L << index} when {@code value} is nonzero, zero otherwise
     */
    private static long store(int[] output, int position, int index, int value) {
        output[position] = value;
        return value != 0 ? 1L << index : 0;
    }
}
