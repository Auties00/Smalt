/* Ports opencore-amr 0.1.6 (pvamrwbdecoder_basic_op.h, pvamrwbdecoder_basic_op_cequivalent.h, normalize_amr_wb.cpp,
   pvamrwb_math_op.cpp). Copyright PacketVideo; Apache License 2.0. Modified for Smalt. */
package com.github.auties00.smalt.audio.amr.internal.wb;

import com.github.auties00.smalt.audio.amr.internal.AmrMathTables;

/**
 * Implements the fixed-point basic operations and the mathematical helpers of the AMR-WB decoder.
 *
 * <p>Values typed {@code Word16} by the specification are carried in {@code short},
 * {@code Word32} values in {@code int}; every operation reproduces the saturation, rounding and
 * truncation of the ITU-T/ETSI basic operators that 3GPP TS 26.173 is written in, for every
 * argument, including shift counts beyond the word size and the normalization of zero and
 * negative values. Helpers that return two values pack them: {@link #pack(int, int)} puts a
 * 32-bit mantissa in the low half of a {@code long} and an exponent in the high half.
 *
 * @implNote This implementation gives the shifts, {@link #round(int)}, the normalizations, {@link #div(int, int)},
 * {@link #mul32By16(int, int, int)} and {@link #powerOf2(int, int)} the semantics TS 26.173 specifies for its operators
 * ({@code shl}, {@code shr}, {@code L_shl}, {@code L_shr}, {@code round}, {@code norm_s}, {@code norm_l},
 * {@code div_s}, {@code Mpy_32_16}, {@code Pow2}); opencore masks shift counts of 16 or more, lets {@code round} wrap
 * just below the largest value, normalizes zero to 27 and returns zero from divisions the specification still computes.
 */
final class WbMath {
    /**
     * The largest 16-bit value.
     */
    static final short MAX_16 = 32767;

    /**
     * The smallest 16-bit value.
     */
    static final short MIN_16 = -32768;

    /**
     * The largest 32-bit value.
     */
    static final int MAX_32 = 0x7fffffff;

    /**
     * The smallest 32-bit value.
     */
    static final int MIN_32 = 0x80000000;

    /**
     * Prevents instantiation.
     */
    private WbMath() {
    }

    /**
     * Saturates a value to 16 bits.
     *
     * @param value the 32-bit value
     * @return the value clamped to {@code [-32768, 32767]}
     */
    static short sat16(int value) {
        return value > MAX_16 ? MAX_16 : value < MIN_16 ? MIN_16 : (short) value;
    }

    /**
     * Negates a 16-bit value, saturating the negation of the smallest value.
     *
     * @param var1 the value
     * @return the negated value
     */
    static short negate(short var1) {
        return var1 == MIN_16 ? MAX_16 : (short) -var1;
    }

    /**
     * Shifts a 16-bit value left with saturation, or right for a negative count.
     *
     * @param var1 the value
     * @param var2 the shift count, taken as a 16-bit value
     * @return the shifted value
     */
    static short shl16(int var1, int var2) {
        var1 = (short) var1;
        var2 = (short) var2;
        if (var2 < 0) {
            return shr16(var1, -Math.max(var2, -16));
        }
        if (var2 > 15) {
            return var1 == 0 ? 0 : var1 > 0 ? MAX_16 : MIN_16;
        }
        return sat16(var1 << var2);
    }

    /**
     * Shifts a 16-bit value right arithmetically, or left with saturation for a negative count.
     *
     * @param var1 the value
     * @param var2 the shift count, taken as a 16-bit value
     * @return the shifted value
     */
    static short shr16(int var1, int var2) {
        var1 = (short) var1;
        var2 = (short) var2;
        if (var2 < 0) {
            return shl16(var1, -Math.max(var2, -16));
        }
        if (var2 >= 15) {
            return (short) (var1 < 0 ? -1 : 0);
        }
        return (short) (var1 >> var2);
    }

    /**
     * Shifts a 32-bit value left with saturation, or right for a non-positive count.
     *
     * @param lVar1 the value
     * @param var2 the shift count, taken as a 16-bit value
     * @return the shifted value
     */
    static int shl32(int lVar1, int var2) {
        var2 = (short) var2;
        if (var2 <= 0) {
            return shr32(lVar1, -Math.max(var2, -32));
        }
        if (lVar1 == 0) {
            return 0;
        }
        if (var2 >= 31) {
            return lVar1 > 0 ? MAX_32 : MIN_32;
        }
        var shifted = (long) lVar1 << var2;
        return shifted > MAX_32 ? MAX_32 : shifted < MIN_32 ? MIN_32 : (int) shifted;
    }

    /**
     * Shifts a 32-bit value right arithmetically, or left with saturation for a negative count.
     *
     * @param lVar1 the value
     * @param var2 the shift count, taken as a 16-bit value
     * @return the shifted value
     */
    static int shr32(int lVar1, int var2) {
        var2 = (short) var2;
        if (var2 < 0) {
            return shl32(lVar1, -Math.max(var2, -32));
        }
        if (var2 >= 31) {
            return lVar1 < 0 ? -1 : 0;
        }
        return lVar1 >> var2;
    }

    /**
     * Shifts a 32-bit value right arithmetically with rounding.
     *
     * @param lVar1 the value
     * @param var2 the shift count, taken as a 16-bit value
     * @return the rounded shifted value
     */
    static int lShrR(int lVar1, int var2) {
        var2 = (short) var2;
        if (var2 > 31) {
            return 0;
        }
        var out = shr32(lVar1, var2);
        if (var2 > 0 && (lVar1 & (1 << (var2 - 1))) != 0) {
            out++;
        }
        return out;
    }

    /**
     * Adds two 16-bit values with saturation.
     *
     * @param var1 the first value
     * @param var2 the second value
     * @return the saturated sum
     */
    static short add16(int var1, int var2) {
        return sat16((short) var1 + (short) var2);
    }

    /**
     * Subtracts two 16-bit values with saturation.
     *
     * @param var1 the minuend
     * @param var2 the subtrahend
     * @return the saturated difference
     */
    static short sub16(int var1, int var2) {
        return sat16((short) var1 - (short) var2);
    }

    /**
     * Multiplies two Q15 values with saturation, truncating the product.
     *
     * @param var1 the first value
     * @param var2 the second value
     * @return the saturated product
     */
    static short mult(int var1, int var2) {
        return sat16(((short) var1 * (short) var2) >> 15);
    }

    /**
     * Multiplies two Q15 values with rounding and saturation.
     *
     * @param var1 the first value
     * @param var2 the second value
     * @return the rounded, saturated product
     */
    static short multR(int var1, int var2) {
        return sat16(((short) var1 * (short) var2 + 0x00004000) >> 15);
    }

    /**
     * Adds two 32-bit values with saturation.
     *
     * @param lVar1 the first value
     * @param lVar2 the second value
     * @return the saturated sum
     */
    static int add32(int lVar1, int lVar2) {
        return Math.clamp((long) lVar1 + lVar2, MIN_32, MAX_32);
    }

    /**
     * Subtracts two 32-bit values with saturation.
     *
     * @param lVar1 the minuend
     * @param lVar2 the subtrahend
     * @return the saturated difference
     */
    static int sub32(int lVar1, int lVar2) {
        return Math.clamp((long) lVar1 - lVar2, MIN_32, MAX_32);
    }

    /**
     * Multiplies two 16-bit values into a doubled 32-bit product with saturation.
     *
     * @param var1 the first value
     * @param var2 the second value
     * @return {@code 2 * var1 * var2}, saturated
     */
    static int mul(int var1, int var2) {
        var product = (short) var1 * (short) var2;
        return product != 0x40000000 ? product << 1 : MAX_32;
    }

    /**
     * Adds a doubled 16-bit product to a 32-bit accumulator with saturation.
     *
     * @param lVar3 the accumulator
     * @param var1 the first factor
     * @param var2 the second factor
     * @return the saturated sum
     */
    static int mac(int lVar3, int var1, int var2) {
        return add32(lVar3, mul(var1, var2));
    }

    /**
     * Subtracts a doubled 16-bit product from a 32-bit accumulator with saturation.
     *
     * @param lVar3 the accumulator
     * @param var1 the first factor
     * @param var2 the second factor
     * @return the saturated difference
     */
    static int msu(int lVar3, int var1, int var2) {
        return sub32(lVar3, mul(var1, var2));
    }

    /**
     * Saturates a multiply-accumulate sum to 32 bits after one step.
     *
     * <p>A loop that adds each doubled product to a {@code long} and saturates the sum after
     * every addition computes exactly the chain of saturating {@code L_mac} operations of
     * TS 26.173, as long as no product is the saturating {@code -32768 * -32768}, which the
     * callers exclude or compute with {@link #mul(int, int)}.
     *
     * @param sum the sum after one addition
     * @return the sum clamped to the 32-bit range
     */
    static long saturate(long sum) {
        return sum == (int) sum ? sum : sum > 0 ? MAX_32 : MIN_32;
    }

    /**
     * Rounds a 32-bit value to its upper 16 bits, saturating at the largest value.
     *
     * @param lVar1 the value
     * @return the rounded upper half
     */
    static short round(int lVar1) {
        return (short) (add32(lVar1, 0x00008000) >> 16);
    }

    /**
     * Multiplies a double-precision value by a 16-bit value with saturation.
     *
     * @param hi the high part
     * @param lo the low part
     * @param n the multiplier
     * @return the doubled product
     */
    static int mul32By16(int hi, int lo, int n) {
        return mac(mul(hi, n), mult(lo, n), 1);
    }

    /**
     * Returns the number of left shifts that normalize a 32-bit value: 0 for zero, 31 for -1.
     *
     * @param x the value
     * @return the normalization shift
     */
    static int normalize(int x) {
        if (x == 0) {
            return 0;
        }
        if (x == -1) {
            return 31;
        }
        return Integer.numberOfLeadingZeros(x < 0 ? ~x : x) - 1;
    }

    /**
     * Returns the number of left shifts that normalize a 16-bit value: 0 for zero, 15 for -1.
     *
     * @param x the value, taken as a 16-bit value
     * @return the normalization shift
     */
    static int normS(int x) {
        x = (short) x;
        if (x == 0) {
            return 0;
        }
        if (x == -1) {
            return 15;
        }
        return Integer.numberOfLeadingZeros(x < 0 ? ~x : x) - 17;
    }

    /**
     * Shifts a 16-bit value right with rounding.
     *
     * @param var1 the value
     * @param var2 the shift count
     * @return the rounded shifted value
     */
    static short shrRnd(int var1, int var2) {
        var2 = (short) var2;
        if (var2 > 15) {
            return 0;
        }
        var out = shr16(var1, var2);
        if (var2 > 0 && (var1 & (1 << (var2 - 1))) != 0) {
            out++;
        }
        return out;
    }

    /**
     * Divides two 16-bit values into a Q15 quotient by 15 steps of restoring division, as the
     * {@code div_s} operator does for every input.
     *
     * @param var1 the dividend
     * @param var2 the divisor
     * @return the quotient
     */
    static short div(int var1, int var2) {
        var1 = (short) var1;
        var2 = (short) var2;
        if (var1 == 0) {
            return 0;
        }
        if (var1 == var2) {
            return MAX_16;
        }
        short out = 0;
        var num = var1;
        for (var iteration = 0; iteration < 15; iteration++) {
            out <<= 1;
            num <<= 1;
            if (num >= var2) {
                num = sub32(num, var2);
                out = add16(out, 1);
            }
        }
        return out;
    }

    /**
     * Clamps a table index computed from a value the specification assumes normalized.
     *
     * <p>Every input of a valid stream yields an index in range; the clamp only keeps hostile
     * input, for which the reference would read outside its table, from failing.
     *
     * @param index the computed index
     * @param max the largest valid index
     * @return the index clamped to {@code [0, max]}
     */
    static int clampIndex(int index, int max) {
        return index < 0 ? 0 : Math.min(index, max);
    }

    /**
     * Packs a 32-bit mantissa and an exponent into a {@code long}.
     *
     * @param value the mantissa
     * @param exponent the binary exponent
     * @return the packed pair
     */
    static long pack(int value, int exponent) {
        return (value & 0xffffffffL) | ((long) exponent << 32);
    }

    /**
     * Returns the mantissa of a packed pair.
     *
     * @param packed the pair
     * @return the mantissa
     */
    static int value(long packed) {
        return (int) packed;
    }

    /**
     * Returns the exponent of a packed pair.
     *
     * @param packed the pair
     * @return the exponent
     */
    static int exponent(long packed) {
        return (short) (packed >> 32);
    }

    /**
     * Computes {@code 1 / sqrt(x)} of a 32-bit value.
     *
     * @param lX the value
     * @return the inverse square root
     */
    static int oneOvSqrt(int lX) {
        var exp = normalize(lX);
        lX = shl32(lX, exp);
        exp = 31 - exp;
        var packed = oneOvSqrtNorm(lX, exp);
        return shl32(value(packed), exponent(packed));
    }

    /**
     * Computes the inverse square root of a normalized value {@code frac * 2^exp}.
     *
     * @param frac the normalized mantissa
     * @param exp the exponent
     * @return the packed mantissa and exponent of the result
     */
    static long oneOvSqrtNorm(int frac, int exp) {
        exp = (short) exp;
        if (frac <= 0) {
            return pack(0x7fffffff, 0);
        }
        if ((exp & 1) == 1) {
            frac >>= 1;
        }
        exp = negate(shr16(sub16(exp, 1), 1));
        frac >>= 9;
        var i = (short) (frac >> 16);
        frac >>= 1;
        var a = (short) (((short) frac) & 0x7fff);
        i = (short) clampIndex(i - 16, AmrMathTables.INV_SQRT.length - 2);
        frac = AmrMathTables.INV_SQRT[i] << 16;
        var tmp = (short) (AmrMathTables.INV_SQRT[i] - AmrMathTables.INV_SQRT[i + 1]);
        frac = msu(frac, tmp, a);
        return pack(frac, exp);
    }

    /**
     * Computes {@code 2^(exponent + fraction)}.
     *
     * @param exponent the integer part
     * @param fraction the Q15 fractional part
     * @return the power
     */
    static int powerOf2(int exponent, int fraction) {
        var lX = mul(fraction, 32);
        var i = clampIndex((short) (lX >> 16), AmrMathTables.POW2.length - 2);
        lX >>= 1;
        var a = (short) (((short) lX) & 0x7fff);
        lX = AmrMathTables.POW2[i] << 16;
        var tmp = (short) (AmrMathTables.POW2[i] - AmrMathTables.POW2[i + 1]);
        lX = msu(lX, tmp, a);
        return lShrR(lX, sub16(30, exponent));
    }

    /**
     * Computes the normalized energy {@code 1 + 2 * sum(x[i] * y[i])} of two vectors.
     *
     * @param x the first vector
     * @param xOff the offset of the first vector
     * @param y the second vector
     * @param yOff the offset of the second vector
     * @param lg the length
     * @return the packed normalized mantissa and its exponent
     */
    static long dotProduct12(short[] x, int xOff, short[] y, int yOff, int lg) {
        var acc = 1L;
        var range = acc + (1L << 31);
        for (var i = 0; i < lg; i++) {
            acc += (long) x[xOff + i] * y[yOff + i] << 1;
            range |= acc + (1L << 31);
        }
        if (range >>> 32 != 0) {
            acc = 1L;
            for (var i = 0; i < lg; i++) {
                acc = saturate(acc + mul(x[xOff + i], y[yOff + i]));
            }
        }
        var sum = (int) acc;
        var sft = normalize(sum);
        sum = shl32(sum, sft);
        return pack(sum, 30 - sft);
    }

    /**
     * Computes the base 2 logarithm of a normalized value.
     *
     * @param lX the normalized value
     * @param exp the normalization shift that was applied
     * @return the exponent in the high and the Q15 fraction in the low 16 bits
     */
    static int log2Normalized(int lX, int exp) {
        if (lX <= 0) {
            return 0;
        }
        var exponent = sub16(30, exp);
        lX >>= 9;
        var i = (short) (lX >> 16);
        lX >>= 1;
        var a = (short) (((short) lX) & 0x7fff);
        i = (short) clampIndex(i - 32, AmrMathTables.LOG2.length - 2);
        var y = AmrMathTables.LOG2[i] << 16;
        var tmp = (short) (AmrMathTables.LOG2[i] - AmrMathTables.LOG2[i + 1]);
        y = msu(y, tmp, a);
        return (exponent << 16) | ((y >> 16) & 0xffff);
    }

    /**
     * Computes the base 2 logarithm of a positive value.
     *
     * @param lX the value
     * @return the exponent in the high and the Q15 fraction in the low 16 bits
     */
    static int log2(int lX) {
        var exp = normalize(lX);
        return log2Normalized(shl32(lX, exp), exp);
    }

    /**
     * Splits a 32-bit value into double-precision high and low parts.
     *
     * @param l32 the value
     * @return the high part in the high and the low part in the low 16 bits
     */
    static int toDpf(int l32) {
        var hi = (short) (l32 >> 16);
        var lo = (short) ((l32 - (hi << 16)) >> 1);
        return (hi << 16) | (lo & 0xffff);
    }

    /**
     * Multiplies two double-precision values.
     *
     * @param hi1 the high part of the first value
     * @param lo1 the low part of the first value
     * @param hi2 the high part of the second value
     * @param lo2 the low part of the second value
     * @return the product
     */
    static int mpyDpf32(int hi1, int lo1, int hi2, int lo2) {
        var l32 = mul(hi1, hi2);
        l32 = mac(l32, mult(hi1, lo2), 1);
        return mac(l32, mult(lo1, hi2), 1);
    }

    /**
     * Advances a linear congruential noise generator.
     *
     * @param seed the seed holder
     * @param index the computed index of the seed in the holder
     * @return the new seed, which is also stored
     */
    static short noise(short[] seed, int index) {
        var value = (short) (seed[index] * 31821 + 13849);
        seed[index] = value;
        return value;
    }
}
