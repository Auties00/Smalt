/* Ports libjpeg-turbo (jfdctint.c, jpeg_fdct_islow). Copyright (C) 1991-1996, Thomas G. Lane; Copyright (C)
   2015, 2020, D. R. Commander; IJG License. Modified for Smalt. */
package com.github.auties00.smalt.video.mpeg4.internal.encoder;

/**
 * Computes the forward 8x8 DCT of a block of samples or residuals at the scale of ISO/IEC 14496-2 7.4.4 and ITU-T
 * H.263 Annex A, where the DC coefficient is eight times the block mean.
 *
 * <p>Arguments are not checked; values outside the documented ranges give unspecified results.
 *
 * @implNote This implementation is libjpeg-turbo's accurate integer transform: the Loeffler-Ligtenberg-Moschytz
 * algorithm with 13-bit constants and two extra bits of precision between the passes, whose output, eight times
 * the orthonormal transform, is divided by eight with rounding.
 */
public final class ForwardDct {
    private static final int CONST_BITS = 13;
    private static final int PASS1_BITS = 2;
    private static final int FIX_0_298631336 = 2446;
    private static final int FIX_0_390180644 = 3196;
    private static final int FIX_0_541196100 = 4433;
    private static final int FIX_0_765366865 = 6270;
    private static final int FIX_0_899976223 = 7373;
    private static final int FIX_1_175875602 = 9633;
    private static final int FIX_1_501321110 = 12299;
    private static final int FIX_1_847759065 = 15137;
    private static final int FIX_1_961570560 = 16069;
    private static final int FIX_2_053119869 = 16819;
    private static final int FIX_2_562915447 = 20995;
    private static final int FIX_3_072711026 = 25172;

    /**
     * Prevents instantiation.
     */
    private ForwardDct() {
        throw new AssertionError();
    }

    /**
     * Transforms one block in place.
     *
     * @param data the 64 values in raster order, from -255 to 255 for residuals or 0 to 255 for samples; replaced
     * by the coefficients in raster order (row {@code v}, column {@code u} at {@code v * 8 + u})
     */
    public static void transform(int[] data) {
        var round1 = 1 << (CONST_BITS - PASS1_BITS - 1);
        var shift1 = CONST_BITS - PASS1_BITS;
        for (var base = 0; base < 64; base += 8) {
            var tmp0 = data[base] + data[base + 7];
            var tmp7 = data[base] - data[base + 7];
            var tmp1 = data[base + 1] + data[base + 6];
            var tmp6 = data[base + 1] - data[base + 6];
            var tmp2 = data[base + 2] + data[base + 5];
            var tmp5 = data[base + 2] - data[base + 5];
            var tmp3 = data[base + 3] + data[base + 4];
            var tmp4 = data[base + 3] - data[base + 4];
            var tmp10 = tmp0 + tmp3;
            var tmp13 = tmp0 - tmp3;
            var tmp11 = tmp1 + tmp2;
            var tmp12 = tmp1 - tmp2;
            data[base] = (tmp10 + tmp11) << PASS1_BITS;
            data[base + 4] = (tmp10 - tmp11) << PASS1_BITS;
            var z1 = (tmp12 + tmp13) * FIX_0_541196100;
            data[base + 2] = (z1 + tmp13 * FIX_0_765366865 + round1) >> shift1;
            data[base + 6] = (z1 - tmp12 * FIX_1_847759065 + round1) >> shift1;
            z1 = tmp4 + tmp7;
            var z2 = tmp5 + tmp6;
            var z3 = tmp4 + tmp6;
            var z4 = tmp5 + tmp7;
            var z5 = (z3 + z4) * FIX_1_175875602;
            tmp4 *= FIX_0_298631336;
            tmp5 *= FIX_2_053119869;
            tmp6 *= FIX_3_072711026;
            tmp7 *= FIX_1_501321110;
            z1 *= -FIX_0_899976223;
            z2 *= -FIX_2_562915447;
            z3 = z3 * -FIX_1_961570560 + z5;
            z4 = z4 * -FIX_0_390180644 + z5;
            data[base + 7] = (tmp4 + z1 + z3 + round1) >> shift1;
            data[base + 5] = (tmp5 + z2 + z4 + round1) >> shift1;
            data[base + 3] = (tmp6 + z2 + z3 + round1) >> shift1;
            data[base + 1] = (tmp7 + z1 + z4 + round1) >> shift1;
        }
        // The second pass removes PASS1_BITS and the factor eight of the integer transform at once.
        var shiftDc = PASS1_BITS + 3;
        var roundDc = 1 << (shiftDc - 1);
        var shift2 = CONST_BITS + PASS1_BITS + 3;
        var round2 = 1 << (shift2 - 1);
        for (var col = 0; col < 8; col++) {
            var tmp0 = data[col] + data[56 + col];
            var tmp7 = data[col] - data[56 + col];
            var tmp1 = data[8 + col] + data[48 + col];
            var tmp6 = data[8 + col] - data[48 + col];
            var tmp2 = data[16 + col] + data[40 + col];
            var tmp5 = data[16 + col] - data[40 + col];
            var tmp3 = data[24 + col] + data[32 + col];
            var tmp4 = data[24 + col] - data[32 + col];
            var tmp10 = tmp0 + tmp3;
            var tmp13 = tmp0 - tmp3;
            var tmp11 = tmp1 + tmp2;
            var tmp12 = tmp1 - tmp2;
            data[col] = (tmp10 + tmp11 + roundDc) >> shiftDc;
            data[32 + col] = (tmp10 - tmp11 + roundDc) >> shiftDc;
            var z1 = (tmp12 + tmp13) * FIX_0_541196100;
            data[16 + col] = (z1 + tmp13 * FIX_0_765366865 + round2) >> shift2;
            data[48 + col] = (z1 - tmp12 * FIX_1_847759065 + round2) >> shift2;
            z1 = tmp4 + tmp7;
            var z2 = tmp5 + tmp6;
            var z3 = tmp4 + tmp6;
            var z4 = tmp5 + tmp7;
            var z5 = (z3 + z4) * FIX_1_175875602;
            tmp4 *= FIX_0_298631336;
            tmp5 *= FIX_2_053119869;
            tmp6 *= FIX_3_072711026;
            tmp7 *= FIX_1_501321110;
            z1 *= -FIX_0_899976223;
            z2 *= -FIX_2_562915447;
            z3 = z3 * -FIX_1_961570560 + z5;
            z4 = z4 * -FIX_0_390180644 + z5;
            data[56 + col] = (tmp4 + z1 + z3 + round2) >> shift2;
            data[40 + col] = (tmp5 + z2 + z4 + round2) >> shift2;
            data[24 + col] = (tmp6 + z2 + z3 + round2) >> shift2;
            data[8 + col] = (tmp7 + z1 + z4 + round2) >> shift2;
        }
    }
}
