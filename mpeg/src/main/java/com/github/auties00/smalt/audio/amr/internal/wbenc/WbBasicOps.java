/* Ports vo-amrwbenc 0.1.3 (basic_op.h, oper_32b.c, math_op.c, log2.c). Copyright VisualOn; Apache License 2.0.
   Modified for Smalt. */
package com.github.auties00.smalt.audio.amr.internal.wbenc;

import com.github.auties00.smalt.audio.amr.internal.AmrMathTables;

/**
 * Implements the fixed-point basic operations and mathematical helpers of the AMR-WB encoder.
 *
 * <p>Values typed {@code Word16} by the reference are carried in {@code short}, {@code Word32}
 * values in {@code int}. The operations saturate exactly as the ITU-T/3GPP basic operations do
 * ({@code add}, {@code L_mult}, {@code L_add}, {@code L_mac}, {@code L_shl}, ...), and the
 * double-precision and mathematical helpers ({@code Mpy_32}, {@code Div_32}, {@code Isqrt_n},
 * {@code Pow2}, {@code Log2}, {@code Dot_product12}) are built on them as TS 26.173 builds them.
 * Helpers with two results pack them: {@link #pack(int, int)} puts a 32-bit value in the low half
 * of a {@code long} and a 16-bit exponent in the high half.
 *
 * @implNote This implementation follows the arithmetic of the 3GPP TS 26.173 fixed-point reference where vo-amrwbenc
 * departs from it, and keeps no global overflow flag. The saturations are computed branch-free in 64 bits, and the hot
 * chains of {@code L_mac}/{@code L_msu} go through {@link #macForward(int, short[], int, short[], int, int)} and its
 * siblings, which skip the per-step saturation when no partial sum can leave the 32-bit range.
 */
final class WbBasicOps {
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
    private WbBasicOps() {
    }

    /**
     * Packs a 32-bit value and a 16-bit exponent into a {@code long}.
     *
     * @param value the 32-bit value, stored in the low half
     * @param exponent the exponent, stored in the high half
     * @return the packed pair
     */
    static long pack(int value, int exponent) {
        return ((long) exponent << 32) | (value & 0xffffffffL);
    }

    /**
     * Returns the 32-bit value of a pair packed by {@link #pack(int, int)}.
     *
     * @param packed the packed pair
     * @return the value
     */
    static int value(long packed) {
        return (int) packed;
    }

    /**
     * Returns the exponent of a pair packed by {@link #pack(int, int)}.
     *
     * @param packed the packed pair
     * @return the exponent, as a 16-bit value
     */
    static short exponent(long packed) {
        return (short) (packed >> 32);
    }

    /**
     * Saturates a 32-bit value to 16 bits ({@code saturate}).
     *
     * @param value the 32-bit value
     * @return the value clamped to the 16-bit range
     */
    static short saturate(int value) {
        return (short) Math.clamp(value, -32768, 32767);
    }

    /**
     * Returns the saturated absolute value of a 16-bit value ({@code abs_s}).
     *
     * @param x the value
     * @return {@code |x|}, with {@code -32768} mapped to {@code 32767}
     */
    static short absS(int x) {
        return (short) (x != MIN_16 ? (x >= 0 ? x : -x) : MAX_16);
    }

    /**
     * Returns the saturated absolute value of a 32-bit value ({@code L_abs}).
     *
     * @param x the value
     * @return {@code |x|}, with the smallest value mapped to the largest
     */
    static int lAbs(int x) {
        return x != MIN_32 ? (x >= 0 ? x : -x) : MAX_32;
    }

    /**
     * Returns the saturated negation of a 16-bit value ({@code negate}).
     *
     * @param x the value
     * @return {@code -x}, with {@code -32768} mapped to {@code 32767}
     */
    static short negate(int x) {
        return (short) (x == MIN_16 ? MAX_16 : -x);
    }

    /**
     * Returns the saturated negation of a 32-bit value ({@code L_negate}).
     *
     * @param x the value
     * @return {@code -x}, with the smallest value mapped to the largest
     */
    static int lNegate(int x) {
        return x == MIN_32 ? MAX_32 : -x;
    }

    /**
     * Returns the high 16 bits of a 32-bit value ({@code extract_h}).
     *
     * @param x the value
     * @return {@code x >> 16}
     */
    static short extractH(int x) {
        return (short) (x >> 16);
    }

    /**
     * Adds two 16-bit values with saturation ({@code add}).
     *
     * @param a the first value
     * @param b the second value
     * @return the saturated sum
     */
    static short add(int a, int b) {
        return saturate(a + b);
    }

    /**
     * Subtracts two 16-bit values with saturation ({@code sub}).
     *
     * @param a the minuend
     * @param b the subtrahend
     * @return the saturated difference
     */
    static short sub(int a, int b) {
        return saturate(a - b);
    }

    /**
     * Shifts a 16-bit value left with saturation; a negative count shifts right ({@code shl}).
     *
     * @param var1 the value
     * @param var2 the shift count
     * @return the shifted value
     */
    static short shl(int var1, int var2) {
        if (var2 < 0) {
            if (var2 < -16) {
                var2 = -16;
            }
            return (short) (var1 >> -var2);
        }
        if (var2 > 15 && var1 != 0) {
            return var1 > 0 ? MAX_16 : MIN_16;
        }
        var result = var1 * (1 << var2);
        if (result != (short) result) {
            return var1 > 0 ? MAX_16 : MIN_16;
        }
        return (short) result;
    }

    /**
     * Shifts a 16-bit value right arithmetically; a negative count shifts left with saturation
     * ({@code shr}).
     *
     * @param var1 the value
     * @param var2 the shift count
     * @return the shifted value
     */
    static short shr(int var1, int var2) {
        if (var2 < 0) {
            if (var2 < -16) {
                var2 = -16;
            }
            return shl(var1, -var2);
        }
        if (var2 >= 15) {
            return (short) (var1 < 0 ? -1 : 0);
        }
        return (short) (var1 >> var2);
    }

    /**
     * Multiplies two Q15 values with saturation ({@code mult}).
     *
     * @param var1 the first value
     * @param var2 the second value
     * @return the saturated Q15 product
     */
    static short mult(int var1, int var2) {
        var product = var1 * var2;
        product = (product & 0xffff8000) >> 15;
        if ((product & 0x00010000) != 0) {
            product |= 0xffff0000;
        }
        return saturate(product);
    }

    /**
     * Multiplies two Q15 values with rounding and saturation ({@code mult_r}).
     *
     * @param var1 the first value
     * @param var2 the second value
     * @return the rounded, saturated Q15 product
     */
    static short multR(int var1, int var2) {
        var product = var1 * var2 + 0x00004000;
        product &= 0xffff8000;
        product >>= 15;
        if ((product & 0x00010000) != 0) {
            product |= 0xffff0000;
        }
        return saturate(product);
    }

    /**
     * Composes a 32-bit value from its double-precision halves ({@code L_Comp}).
     *
     * @param hi the high half
     * @param lo the low half
     * @return {@code hi << 16 + lo << 1}, saturated
     */
    static int lComp(int hi, int lo) {
        return lMac(hi << 16, lo, 1);
    }

    /**
     * Shifts a 16-bit value right with rounding ({@code shr_r}).
     *
     * @param var1 the value
     * @param var2 the shift count; a negative count shifts left with saturation
     * @return the rounded shifted value
     */
    static short shrR(int var1, int var2) {
        if (var2 > 15) {
            return 0;
        }
        var out = shr(var1, var2);
        if (var2 > 0 && (var1 & (1 << (var2 - 1))) != 0) {
            out++;
        }
        return out;
    }

    /**
     * Multiplies two 16-bit values into a Q31 product with saturation ({@code L_mult}).
     *
     * @param var1 the first value
     * @param var2 the second value
     * @return {@code var1 * var2 * 2}, saturated
     */
    static int lMult(int var1, int var2) {
        var product = var1 * var2;
        return product != 0x40000000 ? product * 2 : MAX_32;
    }

    /**
     * Rounds a 32-bit value to its high 16 bits with saturation ({@code round}).
     *
     * @param value the 32-bit value
     * @return the rounded high half
     */
    static short round(int value) {
        return (short) (Math.min((long) value + 0x8000, MAX_32) >> 16);
    }

    /**
     * Adds a doubled product to an accumulator with saturation of the addition ({@code L_mac}).
     *
     * @param acc the accumulator
     * @param var1 the first factor
     * @param var2 the second factor
     * @return {@code acc + var1 * var2 * 2}, the addition saturated
     */
    static int lMac(int acc, int var1, int var2) {
        return lAdd(acc, lMult(var1, var2));
    }

    /**
     * Subtracts a doubled product from an accumulator with saturation of the subtraction
     * ({@code L_msu}).
     *
     * @param acc the accumulator
     * @param var1 the first factor
     * @param var2 the second factor
     * @return {@code acc - var1 * var2 * 2}, the subtraction saturated
     */
    static int lMsu(int acc, int var1, int var2) {
        return lSub(acc, lMult(var1, var2));
    }

    /**
     * Adds two 32-bit values with saturation ({@code L_add}).
     *
     * @param a the first value
     * @param b the second value
     * @return the saturated sum
     */
    static int lAdd(int a, int b) {
        return Math.clamp((long) a + b, MIN_32, MAX_32);
    }

    /**
     * Subtracts two 32-bit values with saturation ({@code L_sub}).
     *
     * @param a the minuend
     * @param b the subtrahend
     * @return the saturated difference
     */
    static int lSub(int a, int b) {
        return Math.clamp((long) a - b, MIN_32, MAX_32);
    }

    /**
     * Shifts a 32-bit value left with saturation; a count of zero or less shifts right
     * ({@code L_shl}).
     *
     * @param value the 32-bit value
     * @param count the shift count
     * @return the shifted value
     */
    static int lShl(int value, int count) {
        if (count <= 0) {
            if (count < -32) {
                count = -32;
            }
            return lShr(value, -count);
        }
        return shiftLeft(value, count);
    }

    /**
     * Shifts a 32-bit value left by a positive count with the saturation of the reference's
     * bit-by-bit loop: the result saturates as soon as a doubling would leave the 32-bit range.
     *
     * <p>Counts above 31 give the same result as 31: every non-zero value other than {@code -1}
     * has saturated by then, and {@code -1} shifted by 31 is {@link #MIN_32} either way.
     *
     * @param value the 32-bit value
     * @param count the shift count, positive
     * @return the shifted value
     */
    private static int shiftLeft(int value, int count) {
        if (count > 31) {
            count = 31;
        }
        if (value > MAX_32 >> count) {
            return MAX_32;
        }
        if (value < MIN_32 >> count) {
            return MIN_32;
        }
        return value << count;
    }

    /**
     * Shifts a 32-bit value left by a positive count with saturation ({@code L_shl2}); a count
     * of zero or less yields {@code 0}, as in the reference.
     *
     * @param value the 32-bit value
     * @param count the shift count
     * @return the shifted value
     */
    static int lShl2(int value, int count) {
        return count > 0 ? shiftLeft(value, count) : 0;
    }

    /**
     * Shifts a 32-bit value right arithmetically; a negative count shifts left with saturation
     * ({@code L_shr}).
     *
     * @param value the 32-bit value
     * @param count the shift count
     * @return the shifted value
     */
    static int lShr(int value, int count) {
        if (count < 0) {
            if (count < -32) {
                count = -32;
            }
            return lShl2(value, -count);
        }
        if (count >= 31) {
            return value < 0 ? -1 : 0;
        }
        return value >> count;
    }

    /**
     * Shifts a 32-bit value right with rounding ({@code L_shr_r}).
     *
     * @param value the 32-bit value
     * @param count the shift count
     * @return the rounded shifted value
     */
    static int lShrR(int value, int count) {
        if (count > 31) {
            return 0;
        }
        var out = lShr(value, count);
        if (count > 0 && (value & (1 << (count - 1))) != 0) {
            out++;
        }
        return out;
    }

    /**
     * Returns the left shift that normalizes a 16-bit value ({@code norm_s}).
     *
     * @param var1 the value
     * @return the number of redundant sign bits, {@code 0} for {@code 0} and {@code 15} for
     * {@code -1}
     */
    static short normS(int var1) {
        if (var1 == 0) {
            return 0;
        }
        if (var1 == -1) {
            return 15;
        }
        if (var1 < 0) {
            var1 = ~var1;
        }
        return (short) (Integer.numberOfLeadingZeros(var1) - 17);
    }

    /**
     * Returns the left shift that normalizes a 32-bit value ({@code norm_l}).
     *
     * @param value the 32-bit value
     * @return the number of redundant sign bits, {@code 0} for {@code 0} and {@code 31} for
     * {@code -1}
     */
    static short normL(int value) {
        if (value == 0) {
            return 0;
        }
        if (value == -1) {
            return 31;
        }
        value ^= value >> 31;
        return (short) (Integer.numberOfLeadingZeros(value) - 1);
    }

    /**
     * Divides two positive 16-bit values into a Q15 fraction ({@code div_s}).
     *
     * @param var1 the numerator, at most {@code var2}
     * @param var2 the denominator
     * @return {@code var1 / var2} in Q15; {@code 32767} for a negative operand or a zero
     * denominator
     */
    static short divS(int var1, int var2) {
        if (var1 < 0 || var2 < 0 || var2 == 0) {
            return MAX_16;
        }
        if (var1 == 0) {
            return 0;
        }
        if (var1 == var2) {
            return MAX_16;
        }
        var out = 0;
        var num = var1;
        for (var iteration = 0; iteration < 15; iteration++) {
            out <<= 1;
            num <<= 1;
            if (num >= var2) {
                num -= var2;
                out += 1;
            }
        }
        return (short) out;
    }

    /**
     * Splits a 32-bit value into its double-precision high and low halves and returns the high
     * half ({@code L_Extract}).
     *
     * @param value the 32-bit value
     * @return {@code value >> 16}
     */
    static short hi(int value) {
        return (short) (value >> 16);
    }

    /**
     * Splits a 32-bit value into its double-precision high and low halves and returns the low
     * half ({@code L_Extract}).
     *
     * @param value the 32-bit value
     * @return {@code (value & 0xffff) >> 1}
     */
    static short lo(int value) {
        return (short) ((value & 0xffff) >> 1);
    }

    /**
     * Multiplies two double-precision values ({@code Mpy_32}).
     *
     * @param hi1 the high half of the first value
     * @param lo1 the low half of the first value
     * @param hi2 the high half of the second value
     * @param lo2 the low half of the second value
     * @return the 32-bit product
     */
    static int mpy32(int hi1, int lo1, int hi2, int lo2) {
        var result = lMult(hi1, hi2);
        result = lMac(result, mult(hi1, lo2), 1);
        return lMac(result, mult(lo1, hi2), 1);
    }

    /**
     * Multiplies a double-precision value by a 16-bit value ({@code Mpy_32_16}).
     *
     * @param hi the high half
     * @param lo the low half
     * @param n the 16-bit factor
     * @return the 32-bit product
     */
    static int mpy3216(int hi, int lo, int n) {
        var result = lMult(hi, n);
        return lMac(result, mult(lo, n), 1);
    }

    /**
     * Divides a 32-bit value by a double-precision denominator ({@code Div_32}).
     *
     * @param num the numerator
     * @param denomHi the high half of the denominator
     * @param denomLo the low half of the denominator
     * @return the 32-bit quotient
     */
    static int div32(int num, int denomHi, int denomLo) {
        var approx = divS(0x3fff, denomHi);
        var value = mpy3216(denomHi, denomLo, approx);
        value = lSub(0x7fffffff, value);
        var h = (short) (value >> 16);
        var l = (short) ((value & 0xffff) >> 1);
        value = mpy3216(h, l, approx);
        h = (short) (value >> 16);
        l = (short) ((value & 0xffff) >> 1);
        value = mpy32(hi(num), lo(num), h, l);
        return lShl(value, 2);
    }

    /**
     * Computes the normalized inverse square root of a mantissa and exponent ({@code Isqrt_n}).
     *
     * @param frac the normalized mantissa
     * @param exp the exponent
     * @return the result mantissa and exponent, packed by {@link #pack(int, int)}
     */
    static long isqrtN(int frac, int exp) {
        if (frac <= 0) {
            return pack(0x7fffffff, 0);
        }
        var e = (short) exp;
        if ((e & 1) == 1) {
            frac = frac >> 1;
        }
        e = negate((e - 1) >> 1);
        frac = frac >> 9;
        var i = extractH(frac);
        frac = frac >> 1;
        var a = (short) frac;
        a = (short) (a & 0x7fff);
        i -= 16;
        frac = AmrMathTables.INV_SQRT[i] << 16;
        var tmp = (short) (AmrMathTables.INV_SQRT[i] - AmrMathTables.INV_SQRT[i + 1]);
        frac = frac - ((tmp * a) << 1);
        return pack(frac, e);
    }

    /**
     * Computes {@code 2^(exponent + fraction)} ({@code Pow2}).
     *
     * @param exponent the integer part, 0 to 30
     * @param fraction the fractional part in Q15
     * @return the power in Q0
     */
    static int pow2(int exponent, int fraction) {
        var x = (fraction * 32) << 1;
        var i = extractH(x);
        x = x >> 1;
        var a = (short) x;
        a = (short) (a & 0x7fff);
        x = AmrMathTables.POW2[i] << 16;
        var tmp = (short) (AmrMathTables.POW2[i] - AmrMathTables.POW2[i + 1]);
        x -= (tmp * a) << 1;
        var exp = (short) (30 - exponent);
        return lShrR(x, exp);
    }

    /**
     * The longest product chain whose absolute bound, at {@link #FAST_MAC_SHIFT} bits less
     * precision, cannot overflow an {@code int}: each product is at most {@code 2^30}, so each
     * term of the bound is at most {@code 2^22}.
     */
    private static final int FAST_MAC_LENGTH = 511;

    /**
     * The number of low bits dropped from each absolute product in the bound of a product chain.
     */
    private static final int FAST_MAC_SHIFT = 8;

    /**
     * Returns whether a chain of doubled products starting at {@code acc} can reach no partial sum
     * outside the 32-bit range.
     *
     * <p>{@code bound} is the sum of the absolute products shifted right by
     * {@link #FAST_MAC_SHIFT}; each absolute product is below its shifted value plus one, shifted
     * back, so {@code |acc| + 2 * ((bound + length) << FAST_MAC_SHIFT)} bounds every partial sum.
     * A product of {@code 2^30}, the one {@link #lMult(int, int)} saturates, alone makes the bound
     * exceed the range.
     *
     * @param acc the accumulator
     * @param bound the sum of the shifted absolute products
     * @param length the number of products
     * @return whether no step of the chain saturates
     */
    private static boolean fitsWithoutSaturation(int acc, int bound, int length) {
        return Math.abs((long) acc) + (((long) bound + length) << (FAST_MAC_SHIFT + 1)) <= MAX_32;
    }

    /**
     * Accumulates {@code length} products {@code x[xOffset + i] * y[yOffset + i]} with
     * {@link #lMac(int, int, int)}.
     *
     * <p>The result is that of the chain of {@code lMac} calls, found in up to three passes. The
     * first sums the products in 32 bits with a bound on their absolute values
     * ({@link #fitsWithoutSaturation(int, int, int)}); when the bound shows no partial sum can
     * leave the 32-bit range, the plain sum is the result. Otherwise the second sums the products
     * in 64 bits while tracking the range of the partial sums; when every partial sum stays within
     * 32 bits and no product is the one {@link #lMult(int, int)} saturates
     * ({@code -32768 * -32768}), no step saturates and the 64-bit sum is the result. Otherwise the
     * chain is evaluated step by step.
     *
     * @param acc the accumulator
     * @param x the first vector
     * @param xOffset the offset of the first vector
     * @param y the second vector
     * @param yOffset the offset of the second vector
     * @param length the number of products
     * @return the accumulated value
     */
    static int macForward(int acc, short[] x, int xOffset, short[] y, int yOffset, int length) {
        if (length <= FAST_MAC_LENGTH) {
            var products = 0;
            var bound = 0;
            for (var i = 0; i < length; i++) {
                var product = x[xOffset + i] * y[yOffset + i];
                products += product;
                bound += Math.abs(product) >>> FAST_MAC_SHIFT;
            }
            if (fitsWithoutSaturation(acc, bound, length)) {
                return acc + 2 * products;
            }
        }
        long sum = acc;
        var high = sum;
        var low = sum;
        var edge = false;
        for (var i = 0; i < length; i++) {
            var product = x[xOffset + i] * y[yOffset + i];
            edge |= product == 0x40000000;
            sum += 2L * product;
            high = Math.max(high, sum);
            low = Math.min(low, sum);
        }
        if (!edge && high <= MAX_32 && low >= MIN_32) {
            return (int) sum;
        }
        for (var i = 0; i < length; i++) {
            acc = lMac(acc, x[xOffset + i], y[yOffset + i]);
        }
        return acc;
    }

    /**
     * Accumulates {@code length} products {@code x[xOffset + i] * y[yOffset - i]}, the second
     * vector read backwards, with {@link #lMac(int, int, int)}.
     *
     * <p>The result is that of the chain of {@code lMac} calls, computed as
     * {@link #macForward(int, short[], int, short[], int, int)} does.
     *
     * @param acc the accumulator
     * @param x the first vector
     * @param xOffset the offset of the first vector
     * @param y the second vector
     * @param yOffset the offset of the first element read from the second vector
     * @param length the number of products
     * @return the accumulated value
     */
    static int macReverse(int acc, short[] x, int xOffset, short[] y, int yOffset, int length) {
        if (length <= FAST_MAC_LENGTH) {
            var products = 0;
            var bound = 0;
            for (var i = 0; i < length; i++) {
                var product = x[xOffset + i] * y[yOffset - i];
                products += product;
                bound += Math.abs(product) >>> FAST_MAC_SHIFT;
            }
            if (fitsWithoutSaturation(acc, bound, length)) {
                return acc + 2 * products;
            }
        }
        long sum = acc;
        var high = sum;
        var low = sum;
        var edge = false;
        for (var i = 0; i < length; i++) {
            var product = x[xOffset + i] * y[yOffset - i];
            edge |= product == 0x40000000;
            sum += 2L * product;
            high = Math.max(high, sum);
            low = Math.min(low, sum);
        }
        if (!edge && high <= MAX_32 && low >= MIN_32) {
            return (int) sum;
        }
        for (var i = 0; i < length; i++) {
            acc = lMac(acc, x[xOffset + i], y[yOffset - i]);
        }
        return acc;
    }

    /**
     * Subtracts {@code length} products {@code x[xOffset + i] * y[yOffset - i]}, the second
     * vector read backwards, with {@link #lMsu(int, int, int)}.
     *
     * <p>The result is that of the chain of {@code lMsu} calls, computed as
     * {@link #macForward(int, short[], int, short[], int, int)} does.
     *
     * @param acc the accumulator
     * @param x the first vector
     * @param xOffset the offset of the first vector
     * @param y the second vector
     * @param yOffset the offset of the first element read from the second vector
     * @param length the number of products
     * @return the accumulated value
     */
    static int msuReverse(int acc, short[] x, int xOffset, short[] y, int yOffset, int length) {
        if (length <= FAST_MAC_LENGTH) {
            var products = 0;
            var bound = 0;
            for (var i = 0; i < length; i++) {
                var product = x[xOffset + i] * y[yOffset - i];
                products += product;
                bound += Math.abs(product) >>> FAST_MAC_SHIFT;
            }
            if (fitsWithoutSaturation(acc, bound, length)) {
                return acc - 2 * products;
            }
        }
        long sum = acc;
        var high = sum;
        var low = sum;
        var edge = false;
        for (var i = 0; i < length; i++) {
            var product = x[xOffset + i] * y[yOffset - i];
            edge |= product == 0x40000000;
            sum -= 2L * product;
            high = Math.max(high, sum);
            low = Math.min(low, sum);
        }
        if (!edge && high <= MAX_32 && low >= MIN_32) {
            return (int) sum;
        }
        for (var i = 0; i < length; i++) {
            acc = lMsu(acc, x[xOffset + i], y[yOffset - i]);
        }
        return acc;
    }

    /**
     * Computes the normalized dot product of two 16-bit vectors ({@code Dot_product12}).
     *
     * @param x the first vector
     * @param xOffset the offset of the first vector
     * @param y the second vector
     * @param yOffset the offset of the second vector
     * @param length the number of elements
     * @return the normalized sum and its exponent, packed by {@link #pack(int, int)}
     */
    static long dotProduct12(short[] x, int xOffset, short[] y, int yOffset, int length) {
        var sum = macForward(1, x, xOffset, y, yOffset, length);
        var shift = normL(sum);
        sum = lShl(sum, shift);
        return pack(sum, 30 - shift);
    }

    /**
     * Computes the base-2 logarithm of a normalized value ({@code Log2_norm}).
     *
     * @param x the normalized value
     * @param exp the normalization shift that was applied
     * @return the exponent in the high 16 bits and the Q15 fraction in the low 16 bits
     */
    static int log2Norm(int x, int exp) {
        if (x <= 0) {
            return 0;
        }
        var exponent = (short) (30 - exp);
        x = x >> 9;
        var i = extractH(x);
        x = x >> 1;
        var a = (short) x;
        a = (short) (a & 0x7fff);
        i -= 32;
        var y = AmrMathTables.LOG2[i] << 16;
        var tmp = (short) (AmrMathTables.LOG2[i] - AmrMathTables.LOG2[i + 1]);
        y = y - ((tmp * a) << 1);
        var fraction = extractH(y);
        return (exponent << 16) | (fraction & 0xffff);
    }

    /**
     * Computes the base-2 logarithm of a positive value ({@code Log2}).
     *
     * @param x the value
     * @return the exponent in the high 16 bits and the Q15 fraction in the low 16 bits
     */
    static int log2(int x) {
        var exp = normL(x);
        return log2Norm(x << exp, exp);
    }
}
