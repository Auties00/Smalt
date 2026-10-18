/* Ports libopus 1.5.2 (silk/macros.h, silk/SigProc_FIX.h, silk/Inlines.h). Copyright Skype Limited; BSD 3-Clause
   License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.silk;

/**
 * The fixed-point arithmetic macros of SILK, as static methods.
 *
 * <p>Method names are the lowerCamelCase form of the libopus macro names ({@code silk_SMULWB} ->
 * {@link #smulwb(int, int)}). Java {@code int} arithmetic wraps exactly like the reference build,
 * so the {@code _ovflw} macros are plain operators at their call sites, and the macros the JDK
 * provides ({@code silk_min}, {@code silk_max}, {@code silk_abs}, {@code silk_CLZ32},
 * {@code silk_ROR32}) are {@link Math} and {@link Integer} calls. The multiply-and-shift macros use
 * the 64-bit formulation the reference selects on 64-bit targets ({@code OPUS_FAST_INT64}).
 */
public final class SigProcFix {
    private SigProcFix() {
        throw new AssertionError();
    }

    /**
     * Returns {@code (a32 * (int16) b32) >> 16} ({@code silk_SMULWB}).
     *
     * @param a32 the 32-bit multiplicand
     * @param b32 the value whose low 16 bits, sign-extended, are the multiplier
     * @return the product, shifted
     */
    public static int smulwb(int a32, int b32) {
        return (int) ((a32 * (long) (short) b32) >> 16);
    }

    /**
     * Returns {@code a32 + ((b32 * (int16) c32) >> 16)} ({@code silk_SMLAWB}).
     *
     * <p>The shifted product always fits in 32 bits, so adding it with wrapping 32-bit arithmetic
     * equals the reference's 64-bit sum truncated to 32 bits.
     *
     * @param a32 the accumulator
     * @param b32 the 32-bit multiplicand
     * @param c32 the value whose low 16 bits, sign-extended, are the multiplier
     * @return the accumulated value, wrapped to 32 bits
     */
    public static int smlawb(int a32, int b32, int c32) {
        return a32 + (int) ((b32 * (long) (short) c32) >> 16);
    }

    /**
     * Returns {@code a32 + ((b32 * (c32 >> 16)) >> 16)} ({@code silk_SMLAWT}).
     *
     * @param a32 the accumulator
     * @param b32 the 32-bit multiplicand
     * @param c32 the value whose high 16 bits are the multiplier
     * @return the accumulated value, wrapped to 32 bits
     */
    public static int smlawt(int a32, int b32, int c32) {
        return (int) (a32 + ((b32 * ((long) c32 >> 16)) >> 16));
    }

    /**
     * Returns {@code (int16) a32 * (int16) b32} ({@code silk_SMULBB}).
     *
     * @param a32 the value whose low 16 bits are the multiplicand
     * @param b32 the value whose low 16 bits are the multiplier
     * @return the 32-bit product
     */
    public static int smulbb(int a32, int b32) {
        return (short) a32 * (short) b32;
    }

    /**
     * Returns {@code a32 + (int16) b32 * (int16) c32} ({@code silk_SMLABB}).
     *
     * @param a32 the accumulator
     * @param b32 the value whose low 16 bits are the multiplicand
     * @param c32 the value whose low 16 bits are the multiplier
     * @return the accumulated value
     */
    public static int smlabb(int a32, int b32, int c32) {
        return a32 + (short) b32 * (short) c32;
    }

    /**
     * Returns {@code (a32 * b32) >> 16} with a 64-bit product ({@code silk_SMULWW}).
     *
     * @param a32 the multiplicand
     * @param b32 the multiplier
     * @return the product, shifted and wrapped to 32 bits
     */
    public static int smulww(int a32, int b32) {
        return (int) (((long) a32 * b32) >> 16);
    }

    /**
     * Returns {@code a32 + ((b32 * c32) >> 16)} with a 64-bit product ({@code silk_SMLAWW}).
     *
     * @param a32 the accumulator
     * @param b32 the multiplicand
     * @param c32 the multiplier
     * @return the accumulated value, wrapped to 32 bits
     */
    public static int smlaww(int a32, int b32, int c32) {
        return a32 + (int) (((long) b32 * c32) >> 16);
    }

    /**
     * Returns the top 32 bits of the 64-bit product ({@code silk_SMMUL}).
     *
     * @param a32 the multiplicand
     * @param b32 the multiplier
     * @return {@code (a32 * b32) >> 32}
     */
    public static int smmul(int a32, int b32) {
        return (int) (((long) a32 * b32) >> 32);
    }

    /**
     * Returns {@code (a32 >> 16) * (b32 >> 16)} ({@code silk_SMULTT}).
     *
     * @param a32 the value whose high 16 bits are the multiplicand
     * @param b32 the value whose high 16 bits are the multiplier
     * @return the product
     */
    public static int smultt(int a32, int b32) {
        return (a32 >> 16) * (b32 >> 16);
    }

    /**
     * Returns {@code a32 + b32 * c32} ({@code silk_MLA}).
     *
     * @param a32 the accumulator
     * @param b32 the multiplicand
     * @param c32 the multiplier
     * @return the accumulated value, wrapped to 32 bits
     */
    public static int mla(int a32, int b32, int c32) {
        return a32 + b32 * c32;
    }

    /**
     * Returns {@code a32 / b16} truncated toward zero ({@code silk_DIV32_16}).
     *
     * @param a32 the dividend
     * @param b16 the divisor, nonzero
     * @return the quotient
     */
    public static int div32_16(int a32, int b16) {
        return a32 / b16;
    }

    /**
     * Returns the sum of two 32-bit values saturated to 32 bits ({@code silk_ADD_SAT32}).
     *
     * <p>The reference's sign-bit tests compute exactly the clamped exact sum; the port clamps a
     * 64-bit sum, which compiles to branch-free code.
     *
     * @param a the first addend
     * @param b the second addend
     * @return the saturated sum
     */
    public static int addSat32(int a, int b) {
        var sum = (long) a + b;
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, sum));
    }

    /**
     * Returns the difference of two 32-bit values saturated to 32 bits ({@code silk_SUB_SAT32}).
     *
     * @param a the minuend
     * @param b the subtrahend
     * @return the saturated difference
     */
    public static int subSat32(int a, int b) {
        var diff = (long) a - b;
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, diff));
    }

    /**
     * Saturates a value to the signed 16-bit range ({@code silk_SAT16}).
     *
     * @param a the value
     * @return the saturated value
     */
    public static int sat16(int a) {
        return Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, a));
    }

    /**
     * Returns the sum of two values saturated to 16 bits ({@code silk_ADD_SAT16}).
     *
     * @param a the first addend
     * @param b the second addend
     * @return the saturated 16-bit sum
     */
    public static short addSat16(int a, int b) {
        return (short) sat16(a + b);
    }

    /**
     * Adds two non-negative values saturating to the 32-bit maximum
     * ({@code silk_ADD_POS_SAT32}).
     *
     * @param a the first addend
     * @param b the second addend
     * @return the saturated sum
     */
    public static int addPosSat32(int a, int b) {
        return ((a + b) & 0x80000000) != 0 ? Integer.MAX_VALUE : a + b;
    }

    /**
     * Shifts left after saturating so the result cannot overflow ({@code silk_LSHIFT_SAT32}).
     *
     * @param a     the value
     * @param shift the shift, in {@code [0, 31]}
     * @return the saturated shifted value
     */
    public static int lshiftSat32(int a, int shift) {
        return Math.max(Integer.MIN_VALUE >> shift, Math.min(Integer.MAX_VALUE >> shift, a)) << shift;
    }

    /**
     * Returns {@code a} shifted right by {@code shift} with rounding ({@code silk_RSHIFT_ROUND}).
     *
     * @param a     the value
     * @param shift the shift, in {@code [1, 31]}
     * @return the rounded shifted value
     */
    public static int rshiftRound(int a, int shift) {
        return shift == 1 ? (a >> 1) + (a & 1) : ((a >> (shift - 1)) + 1) >> 1;
    }

    /**
     * Returns the 64-bit {@code a} shifted right by {@code shift} with rounding
     * ({@code silk_RSHIFT_ROUND64}).
     *
     * @param a     the value
     * @param shift the shift, in {@code [1, 63]}
     * @return the rounded shifted value
     */
    public static long rshiftRound64(long a, int shift) {
        return shift == 1 ? (a >> 1) + (a & 1) : ((a >> (shift - 1)) + 1) >> 1;
    }

    /**
     * Clamps {@code a} between two limits given in either order ({@code silk_LIMIT},
     * {@code silk_LIMIT_int}, {@code silk_LIMIT_16}, {@code silk_LIMIT_32}).
     *
     * @param a      the value
     * @param limit1 the first limit
     * @param limit2 the second limit
     * @return the clamped value
     */
    public static int limit(int a, int limit1, int limit2) {
        return limit1 > limit2
                ? (a > limit1 ? limit1 : (a < limit2 ? limit2 : a))
                : (a > limit2 ? limit2 : (a < limit1 ? limit1 : a));
    }

    /**
     * Advances the SILK linear congruential generator ({@code silk_RAND}).
     *
     * @param seed the current seed
     * @return the next seed, whose high bits are the most random
     */
    public static int rand(int seed) {
        return Define.RAND_INCREMENT + seed * Define.RAND_MULTIPLIER;
    }

    /**
     * Returns the fixed-point representation of a double constant ({@code SILK_FIX_CONST}).
     *
     * @param c the constant
     * @param q the number of fractional bits
     * @return {@code (int) (c * 2^q + 0.5)}, computed in double precision
     */
    public static int fixConst(double c, int q) {
        return (int) (c * (1L << q) + 0.5);
    }

    /**
     * Returns the fixed-point representation of a float constant ({@code SILK_FIX_CONST}).
     *
     * <p>As in the reference, the product is computed in single precision (float times a 64-bit
     * integer is a float) and only the rounding offset is added in double precision.
     *
     * @param c the constant
     * @param q the number of fractional bits
     * @return {@code (int) (c * 2^q + 0.5)}
     */
    public static int fixConst(float c, int q) {
        return (int) ((c * (1L << q)) + 0.5);
    }

    /**
     * Returns the seven bits that follow the leading one of a value, the {@code frac_Q7} output of
     * {@code silk_CLZ_FRAC}; its {@code lz} output is {@link Integer#numberOfLeadingZeros(int)}.
     *
     * @param in the value
     * @return the fractional part in Q7
     */
    public static int clzFracQ7(int in) {
        return Integer.rotateRight(in, 24 - Integer.numberOfLeadingZeros(in)) & 0x7f;
    }

    /**
     * Returns an approximation of the square root ({@code silk_SQRT_APPROX}).
     *
     * <p>The error is within 10 percent for outputs above 15 and 2.5 percent above 120.
     *
     * @param x the value
     * @return the approximate square root, zero for non-positive input
     */
    public static int sqrtApprox(int x) {
        if (x <= 0) {
            return 0;
        }
        var lz = Integer.numberOfLeadingZeros(x);
        var fracQ7 = clzFracQ7(x);
        int y;
        if ((lz & 1) != 0) {
            y = 32768;
        } else {
            y = 46214;
        }
        y >>= lz >> 1;
        y = smlawb(y, y, smulbb(213, fracQ7));
        return y;
    }

    /**
     * Returns an approximation of {@code (a32 << qres) / b32} ({@code silk_DIV32_varQ}).
     *
     * @param a32  the numerator
     * @param b32  the denominator, nonzero
     * @param qres the Q-domain of the result, non-negative
     * @return the approximate quotient
     */
    public static int div32VarQ(int a32, int b32, int qres) {
        var aHeadrm = Integer.numberOfLeadingZeros(Math.abs(a32)) - 1;
        var a32Nrm = a32 << aHeadrm;
        var bHeadrm = Integer.numberOfLeadingZeros(Math.abs(b32)) - 1;
        var b32Nrm = b32 << bHeadrm;
        var b32Inv = (Integer.MAX_VALUE >> 2) / (b32Nrm >> 16);
        var result = smulwb(a32Nrm, b32Inv);
        a32Nrm = a32Nrm - (smmul(b32Nrm, result) << 3);
        result = smlawb(result, a32Nrm, b32Inv);
        var lshift = 29 + aHeadrm - bHeadrm - qres;
        if (lshift < 0) {
            return lshiftSat32(result, -lshift);
        } else {
            if (lshift < 32) {
                return result >> lshift;
            } else {
                return 0;
            }
        }
    }

    /**
     * Returns an approximation of {@code (1 << qres) / b32} ({@code silk_INVERSE32_varQ}).
     *
     * @param b32  the denominator, nonzero
     * @param qres the Q-domain of the result, positive
     * @return the approximate inverse
     */
    public static int inverse32VarQ(int b32, int qres) {
        var bHeadrm = Integer.numberOfLeadingZeros(Math.abs(b32)) - 1;
        var b32Nrm = b32 << bHeadrm;
        var b32Inv = (Integer.MAX_VALUE >> 2) / (b32Nrm >> 16);
        var result = b32Inv << 16;
        var errQ32 = ((1 << 29) - smulwb(b32Nrm, b32Inv)) << 3;
        result = smlaww(result, errQ32, b32Inv);
        var lshift = 61 - bHeadrm - qres;
        if (lshift <= 0) {
            return lshiftSat32(result, -lshift);
        } else {
            if (lshift < 32) {
                return result >> lshift;
            } else {
                return 0;
            }
        }
    }
}
