/* Ports opencore-amr 0.1.6 (log2.cpp, log2_norm.cpp, pow2.cpp, inv_sqrt.cpp, sqrt_l.cpp, sqrt_l_tbl.cpp,
   gmed_n.cpp). Copyright PacketVideo; Apache License 2.0. Modified for Smalt. */
package com.github.auties00.smalt.audio.amr.internal.nb;

import com.github.auties00.smalt.audio.amr.internal.AmrMathTables;

import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lMsu;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lMult;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lShl;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lShrR;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.normL;

/**
 * Implements the table-driven fixed-point functions of the AMR-NB codec: base-2 logarithm and
 * power, inverse square root, square root and the median of a short list.
 *
 * <p>Functions returning two 16-bit results pack them into one {@code int}: the first result
 * (the exponent) in the upper 16 bits and the second (the fraction) in the lower 16 bits, so a
 * caller unpacks them with {@code (short) (r >> 16)} and {@code (short) r}.
 *
 * @implNote This implementation keeps the reference's selection order in the median, including its use of
 * {@code -32768} as the removed-element marker, without the reference's temporary arrays.
 */
final class FixedMath {
    /**
     * Holds the square root interpolation table ({@code sqrt_l_tbl.cpp}).
     */
    static final short[] SQRT_L_TBL = {
            16384, 16888, 17378, 17854, 18318, 18770, 19212, 19644, 20066, 20480, 20886, 21283,
            21674, 22058, 22435, 22806, 23170, 23530, 23884, 24232, 24576, 24915, 25249, 25580,
            25905, 26227, 26545, 26859, 27170, 27477, 27780, 28081, 28378, 28672, 28963, 29251,
            29537, 29819, 30099, 30377, 30652, 30924, 31194, 31462, 31727, 31991, 32252, 32511,
            32767, 32767
    };

    /**
     * Prevents instantiation.
     */
    private FixedMath() {
    }

    /**
     * Computes the base-2 logarithm of a positive 32-bit value ({@code Log2}).
     *
     * @param x the value
     * @return the exponent (upper 16 bits, 0 to 30) and the Q15 fraction (lower 16 bits); both
     * 0 for a value that is not positive
     */
    static int log2(int x) {
        var exp = normL(x);
        return log2Norm(x << exp, exp);
    }

    /**
     * Computes the base-2 logarithm of a value already normalized by {@code exp} left shifts
     * ({@code Log2_norm}).
     *
     * @param x the normalized value
     * @param exp the normalization shift
     * @return the exponent {@code 30 - exp} (upper 16 bits) and the Q15 fraction (lower 16
     * bits); both 0 for a value that is not positive
     */
    static int log2Norm(int x, short exp) {
        if (x <= 0) {
            return 0;
        }
        var exponent = (short) (30 - exp);
        x >>= 10;
        var i = (short) (x >> 15);
        var a = (short) (x & 0x7fff);
        i -= 32;
        var y = AmrMathTables.LOG2[i] << 16;
        var tmp = (short) (AmrMathTables.LOG2[i] - AmrMathTables.LOG2[i + 1]);
        y -= (tmp * a) << 1;
        var fraction = (short) (y >> 16);
        return (exponent << 16) | (fraction & 0xffff);
    }

    /**
     * Computes {@code 2^(exponent + fraction)} ({@code Pow2}).
     *
     * @param exponent the integer part, 0 to 30
     * @param fraction the fractional part, Q15
     * @param overflow the flag raised on saturation
     * @return the power, rounded
     */
    static int pow2(short exponent, short fraction, Flag overflow) {
        var x = lMult(fraction, (short) 32, overflow);
        var i = (short) ((short) (x >> 16) & 31);
        var a = (short) ((x >> 1) & 0x7fff);
        x = AmrMathTables.POW2[i] << 16;
        var tmp = (short) (AmrMathTables.POW2[i] - AmrMathTables.POW2[i + 1]);
        x = lMsu(x, tmp, a, overflow);
        var exp = (short) (30 - exponent);
        return lShrR(x, exp);
    }

    /**
     * Computes {@code 1 / sqrt(x)} ({@code Inv_sqrt}).
     *
     * @param x the value, Q0
     * @return the inverse square root in Q30, or {@code 0x3fffffff} for a value that is not
     * positive
     */
    static int invSqrt(int x) {
        if (x <= 0) {
            return 0x3fffffff;
        }
        var exp = normL(x);
        x <<= exp;
        exp = (short) (30 - exp);
        if ((exp & 1) == 0) {
            x >>= 1;
        }
        exp >>= 1;
        exp += 1;
        x >>= 9;
        var i = (short) (x >> 16);
        var a = (short) (x >> 1);
        a &= 0x7fff;
        i -= 16;
        var y = AmrMathTables.INV_SQRT[i] << 16;
        var tmp = (short) (AmrMathTables.INV_SQRT[i] - AmrMathTables.INV_SQRT[i + 1]);
        y -= (tmp * a) << 1;
        y >>= exp;
        return y;
    }

    /**
     * Computes the square root of a value together with the even normalization shift the
     * result must be scaled by ({@code sqrt_l_exp}): the square root of {@code x} is the result
     * divided by {@code 2^(exp / 2)}.
     *
     * @param x the value
     * @param exp receives the even normalization shift at {@code expOffset}, 0 for a value
     * that is not positive
     * @param expOffset the index written in {@code exp}
     * @param overflow the flag raised on saturation
     * @return the square root of the normalized value, or 0 for a value that is not positive
     */
    static int sqrtLExp(int x, short[] exp, int expOffset, Flag overflow) {
        if (x <= 0) {
            exp[expOffset] = 0;
            return 0;
        }
        var e = (short) (normL(x) & 0xfffe);
        x = lShl(x, e);
        exp[expOffset] = e;
        x >>= 10;
        var i = (short) ((short) (x >> 15) & 63);
        var a = (short) x;
        a &= 0x7fff;
        if (i > 15) {
            i -= 16;
        }
        var y = SQRT_L_TBL[i] << 16;
        var tmp = (short) (SQRT_L_TBL[i] - SQRT_L_TBL[i + 1]);
        return lMsu(y, tmp, a, overflow);
    }

    /**
     * Returns the median of up to 9 values ({@code gmed_n}).
     *
     * @param ind the values
     * @param offset the index of the first value
     * @param n the number of values, odd, at most 9
     * @return the median value
     */
    static short gmedN(short[] ind, int offset, int n) {
        var removed = 0;
        var ix = 0;
        var medianIndex = 0;
        var half = n >> 1;
        for (var i = 0; i < n; i++) {
            short max = -32767;
            for (var j = 0; j < n; j++) {
                var value = ((removed >> j) & 1) != 0 ? Short.MIN_VALUE : ind[offset + j];
                if (value >= max) {
                    max = value;
                    ix = j;
                }
            }
            removed |= 1 << ix;
            if (i == half) {
                medianIndex = ix;
            }
        }
        return ind[offset + medianIndex];
    }
}
