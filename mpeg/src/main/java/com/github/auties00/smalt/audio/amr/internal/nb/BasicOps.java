/* Ports opencore-amr 0.1.6 (basic_op_c_equivalent.h, basic_op.h, basicop_malloc.h, add.cpp, sub.cpp, mult_r.cpp,
   shr.cpp, shr_r.cpp, l_shr_r.cpp, negate.cpp, norm_s.cpp, norm_l.cpp, div_s.cpp, round.cpp, extract_h.cpp,
   l_extract.cpp, l_comp.cpp, l_negate.cpp, l_abs.cpp, div_32.cpp). Copyright PacketVideo; Apache License 2.0.
   Modified for Smalt. */
package com.github.auties00.smalt.audio.amr.internal.nb;

/**
 * Implements the 16- and 32-bit fixed-point basic operators of the AMR-NB codec: saturating
 * addition, subtraction, multiplication, shifts, normalization, division and the double precision
 * helpers built on them.
 *
 * <p>Every operator reproduces the reference's results bit for bit, including its quirks: shifts
 * by large or negative counts follow the reference's control flow (and the masked shift counts of
 * the processors it runs on, which Java shares), and the operators that the reference lets raise
 * the overflow flag do so through a {@link Flag}. Operators whose reference takes the overflow
 * pointer without using it take no {@link Flag}.
 *
 * @implNote This implementation keeps the reference's non-saturating {@code amrnb_fxp_mac_16_by_16bb} as
 * {@link #fxpMac(int, int, int)}, which wraps around like plain C {@code int} arithmetic.
 */
final class BasicOps {
    /**
     * The largest 32-bit value.
     */
    static final int MAX_32 = 0x7fffffff;

    /**
     * The smallest 32-bit value.
     */
    static final int MIN_32 = 0x80000000;

    /**
     * The largest 16-bit value.
     */
    static final short MAX_16 = 0x7fff;

    /**
     * The smallest 16-bit value.
     */
    static final short MIN_16 = (short) 0x8000;

    /**
     * Prevents instantiation.
     */
    private BasicOps() {
    }

    /**
     * Adds two 16-bit values with saturation ({@code add_16}).
     *
     * @param var1 the first operand
     * @param var2 the second operand
     * @param overflow the flag raised on saturation
     * @return the saturated sum
     */
    static short add(short var1, short var2, Flag overflow) {
        var sum = var1 + var2;
        if (sum > 0x7fff) {
            overflow.value = true;
            sum = MAX_16;
        } else if (sum < -32768) {
            overflow.value = true;
            sum = MIN_16;
        }
        return (short) sum;
    }

    /**
     * Subtracts two 16-bit values with saturation ({@code sub}).
     *
     * @param var1 the minuend
     * @param var2 the subtrahend
     * @param overflow the flag raised on saturation
     * @return the saturated difference
     */
    static short sub(short var1, short var2, Flag overflow) {
        var diff = var1 - var2;
        if (Integer.compareUnsigned(diff + 32768, 0xffff) > 0) {
            diff = diff > 0x7fff ? MAX_16 : MIN_16;
            overflow.value = true;
        }
        return (short) diff;
    }

    /**
     * Multiplies two Q15 values, truncating the product to Q15 with saturation ({@code mult}).
     *
     * @param var1 the first factor
     * @param var2 the second factor
     * @param overflow the flag raised on saturation
     * @return {@code (var1 * var2) >> 15}, saturated
     */
    static short mult(short var1, short var2, Flag overflow) {
        var product = (var1 * var2) >> 15;
        if (product > 0x7fff) {
            overflow.value = true;
            product = MAX_16;
        }
        return (short) product;
    }

    /**
     * Multiplies two Q15 values, rounding the product to Q15 with saturation ({@code mult_r}).
     *
     * @param var1 the first factor
     * @param var2 the second factor
     * @param overflow the flag raised on saturation
     * @return {@code (var1 * var2 + 0x4000) >> 15}, saturated
     */
    static short multR(short var1, short var2, Flag overflow) {
        var product = var1 * var2;
        product += 0x4000;
        product >>= 15;
        product |= -(product & 0x10000);
        if (product > 0x7fff) {
            overflow.value = true;
            product = MAX_16;
        } else if (product < -32768) {
            overflow.value = true;
            product = MIN_16;
        }
        return (short) product;
    }

    /**
     * Multiplies two 16-bit values into a doubled 32-bit product with saturation
     * ({@code L_mult}).
     *
     * @param var1 the first factor
     * @param var2 the second factor
     * @param overflow the flag raised on saturation
     * @return {@code var1 * var2 * 2}, saturated
     */
    static int lMult(short var1, short var2, Flag overflow) {
        var product = var1 * var2;
        if (product != 0x40000000) {
            product <<= 1;
        } else {
            overflow.value = true;
            product = MAX_32;
        }
        return product;
    }

    /**
     * Accumulates a doubled 16-bit product into a 32-bit value with saturation ({@code L_mac}).
     *
     * @param var3 the accumulator
     * @param var1 the first factor
     * @param var2 the second factor
     * @param overflow the flag raised on saturation
     * @return {@code var3 + var1 * var2 * 2}, saturated
     */
    static int lMac(int var3, short var1, short var2, Flag overflow) {
        var result = var1 * var2;
        int sum;
        if (result != 0x40000000) {
            sum = (result << 1) + var3;
            if ((var3 ^ result) > 0) {
                if (((sum ^ var3) >> 31) != 0) {
                    sum = (var3 >> 31) != 0 ? MIN_32 : MAX_32;
                    overflow.value = true;
                }
            }
        } else {
            overflow.value = true;
            sum = MAX_32;
        }
        return sum;
    }

    /**
     * Subtracts a doubled 16-bit product from a 32-bit value with saturation ({@code L_msu}).
     *
     * @param var3 the accumulator
     * @param var1 the first factor
     * @param var2 the second factor
     * @param overflow the flag raised on saturation
     * @return {@code var3 - var1 * var2 * 2}, saturated
     */
    static int lMsu(int var3, short var1, short var2, Flag overflow) {
        var result = lMult(var1, var2, overflow);
        return lSub(var3, result, overflow);
    }

    /**
     * Adds two 32-bit values with saturation ({@code L_add}).
     *
     * @param var1 the first operand
     * @param var2 the second operand
     * @param overflow the flag raised on saturation
     * @return the saturated sum
     */
    static int lAdd(int var1, int var2, Flag overflow) {
        var sum = var1 + var2;
        if ((var1 ^ var2) >= 0) {
            if (((sum ^ var1) >> 31) != 0) {
                sum = (var1 >> 31) != 0 ? MIN_32 : MAX_32;
                overflow.value = true;
            }
        }
        return sum;
    }

    /**
     * Subtracts two 32-bit values with saturation ({@code L_sub}).
     *
     * @param var1 the minuend
     * @param var2 the subtrahend
     * @param overflow the flag raised on saturation
     * @return the saturated difference
     */
    static int lSub(int var1, int var2, Flag overflow) {
        var diff = var1 - var2;
        if (((var1 ^ var2) >> 31) != 0) {
            if (((diff ^ var1) & MIN_32) != 0) {
                diff = (var1 >> 31) != 0 ? MIN_32 : MAX_32;
                overflow.value = true;
            }
        }
        return diff;
    }

    /**
     * Shifts a 16-bit value left, or right for a negative count, saturating on overflow
     * ({@code shl}).
     *
     * <p>A right shift by 15 or more returns 0, also for negative values.
     *
     * @param var1 the value
     * @param var2 the shift count, negative for a right shift
     * @return the shifted value
     */
    static short shl(short var1, short var2) {
        short out = 0;
        if (var2 < 0) {
            var2 = (short) -var2;
            if (var2 < 15) {
                out = (short) (var1 >> var2);
            }
        } else {
            out = (short) (var1 << var2);
            if (out >> var2 != var1) {
                out = (short) ((var1 >> 15) ^ MAX_16);
            }
        }
        return out;
    }

    /**
     * Shifts a 32-bit value left, or right for a negative count, saturating on overflow
     * ({@code L_shl}).
     *
     * <p>A right shift by 31 or more returns 0, also for negative values.
     *
     * @param var1 the value
     * @param var2 the shift count, negative for a right shift
     * @return the shifted value
     */
    static int lShl(int var1, short var2) {
        var out = 0;
        if (var2 > 0) {
            out = var1 << var2;
            if (out >> var2 != var1) {
                out = (var1 >> 31) ^ MAX_32;
            }
        } else {
            var2 = (short) -var2;
            if (var2 < 31) {
                out = var1 >> var2;
            }
        }
        return out;
    }

    /**
     * Shifts a 32-bit value right, or left for a negative count, saturating on overflow
     * ({@code L_shr}).
     *
     * <p>A right shift by 31 or more returns 0, also for negative values.
     *
     * @param var1 the value
     * @param var2 the shift count, negative for a left shift
     * @return the shifted value
     */
    static int lShr(int var1, short var2) {
        var out = 0;
        if (var2 > 0) {
            if (var2 < 31) {
                out = var1 >> var2;
            }
        } else {
            var2 = (short) -var2;
            out = var1 << var2;
            if ((out >> var2) != var1) {
                out = (var1 >> 31) ^ MAX_32;
            }
        }
        return out;
    }

    /**
     * Shifts a 16-bit value right, or left for a negative count, with the count clamped to 15
     * and saturation on a left-shift overflow ({@code shr}).
     *
     * @param var1 the value
     * @param var2 the shift count, negative for a left shift
     * @param overflow the flag raised on saturation
     * @return the shifted value
     */
    static short shr(short var1, short var2, Flag overflow) {
        short result;
        if (var2 != 0) {
            if (var2 > 0) {
                if (var2 > 15) {
                    var2 = 15;
                }
                result = (short) (var1 >> var2);
            } else {
                var2 = (short) -var2;
                if (var2 > 15) {
                    var2 = 15;
                }
                result = (short) (var1 << var2);
                if ((result >> var2) != var1) {
                    overflow.value = true;
                    result = var1 > 0 ? MAX_16 : MIN_16;
                }
            }
        } else {
            result = var1;
        }
        return result;
    }

    /**
     * Shifts a 16-bit value right with rounding ({@code shr_r}); a count above 15 returns 0.
     *
     * @param var1 the value
     * @param var2 the shift count, negative for a left shift
     * @param overflow the flag raised on saturation
     * @return the rounded shifted value
     */
    static short shrR(short var1, short var2, Flag overflow) {
        short out;
        if (var2 > 15) {
            out = 0;
        } else {
            out = shr(var1, var2, overflow);
            if (var2 > 0) {
                if ((var1 & (1 << (var2 - 1))) != 0) {
                    out++;
                }
            }
        }
        return out;
    }

    /**
     * Shifts a 32-bit value right with rounding ({@code L_shr_r}); a count above 31 returns 0.
     *
     * @param var1 the value
     * @param var2 the shift count, negative for a left shift
     * @return the rounded shifted value
     */
    static int lShrR(int var1, short var2) {
        int result;
        if (var2 > 31) {
            result = 0;
        } else {
            result = lShr(var1, var2);
            if (var2 > 0) {
                if ((var1 & (1 << (var2 - 1))) != 0) {
                    result++;
                }
            }
        }
        return result;
    }

    /**
     * Negates a 16-bit value, mapping {@link #MIN_16} to {@link #MAX_16} ({@code negate}).
     *
     * @param var1 the value
     * @return the saturated negation
     */
    static short negate(short var1) {
        return var1 == MIN_16 ? MAX_16 : (short) -var1;
    }

    /**
     * Returns the absolute value of a 16-bit value, mapping {@link #MIN_16} to {@link #MAX_16}
     * ({@code abs_s}).
     *
     * @param var1 the value
     * @return the saturated absolute value
     */
    static short abs(short var1) {
        var y = (short) (var1 - (var1 < 0 ? 1 : 0));
        return (short) (y ^ (y >> 15));
    }

    /**
     * Returns the number of left shifts that normalize a 16-bit value ({@code norm_s}).
     *
     * @param var1 the value
     * @return the shift count, 0 for 0
     */
    static short normS(short var1) {
        if (var1 == 0) {
            return 0;
        }
        int y = (short) (var1 - (var1 < 0 ? 1 : 0));
        return (short) (Integer.numberOfLeadingZeros(y ^ (y >> 15)) - 17);
    }

    /**
     * Returns the number of left shifts that normalize a 32-bit value ({@code norm_l}).
     *
     * @param var1 the value
     * @return the shift count, 0 for 0
     */
    static short normL(int var1) {
        if (var1 == 0) {
            return 0;
        }
        var y = var1 - (var1 < 0 ? 1 : 0);
        return (short) (Integer.numberOfLeadingZeros(y ^ (y >> 31)) - 1);
    }

    /**
     * Divides two non-negative 16-bit values into a Q15 fraction ({@code div_s}).
     *
     * <p>Returns 0 when {@code var1} is negative or larger than {@code var2}, and
     * {@link #MAX_16} when they are equal and nonzero.
     *
     * @param var1 the numerator
     * @param var2 the denominator
     * @return {@code var1 / var2} in Q15
     */
    static short divS(short var1, short var2) {
        if (var1 > var2 || var1 < 0) {
            return 0;
        }
        if (var1 == 0) {
            return 0;
        }
        if (var1 == var2) {
            return MAX_16;
        }
        return (short) ((var1 << 15) / var2);
    }

    /**
     * Rounds a 32-bit value to its upper 16 bits with saturation ({@code pv_round}).
     *
     * @param var1 the value
     * @param overflow the flag raised on saturation
     * @return {@code (var1 + 0x8000) >> 16}, saturated
     */
    static short round(int var1, Flag overflow) {
        var1 = lAdd(var1, 0x8000, overflow);
        return (short) (var1 >> 16);
    }

    /**
     * Returns the upper 16 bits of a 32-bit value ({@code extract_h}).
     *
     * @param var1 the value
     * @return {@code var1 >> 16}
     */
    static short extractH(int var1) {
        return (short) (var1 >> 16);
    }

    /**
     * Negates a 32-bit value, mapping {@link #MIN_32} to {@link #MAX_32} ({@code L_negate}).
     *
     * @param var1 the value
     * @return the saturated negation
     */
    static int lNegate(int var1) {
        return var1 == MIN_32 ? MAX_32 : -var1;
    }

    /**
     * Returns the absolute value of a 32-bit value, mapping {@link #MIN_32} to {@link #MAX_32}
     * ({@code L_abs}).
     *
     * @param var1 the value
     * @return the saturated absolute value
     */
    static int lAbs(int var1) {
        var y = var1 - (var1 < 0 ? 1 : 0);
        return y ^ (y >> 31);
    }

    /**
     * Returns the high part of the double precision form of a 32-bit value: the {@code hi} output
     * of {@code L_Extract}.
     *
     * @param var the value
     * @return {@code var >> 16}
     */
    static short lExtractHi(int var) {
        return (short) (var >> 16);
    }

    /**
     * Returns the low part of the double precision form of a 32-bit value: the {@code lo} output
     * of {@code L_Extract}, {@code (var >> 1) - (hi << 15)}.
     *
     * @param var the value
     * @return the low 15 bits
     */
    static short lExtractLo(int var) {
        var temp = var >> 16;
        return (short) ((var >> 1) - (temp << 15));
    }

    /**
     * Composes a 32-bit value from its double precision parts ({@code L_Comp}).
     *
     * @param hi the high part
     * @param lo the low part
     * @param overflow the flag raised on saturation
     * @return {@code (hi << 16) + (lo << 1)}, saturated
     */
    static int lComp(short hi, short lo, Flag overflow) {
        return lMac(hi << 16, lo, (short) 1, overflow);
    }

    /**
     * Multiplies two double precision values ({@code Mpy_32}).
     *
     * @param hi1 the high part of the first factor
     * @param lo1 the low part of the first factor
     * @param hi2 the high part of the second factor
     * @param lo2 the low part of the second factor
     * @return the saturated 32-bit product
     */
    static int mpy32(short hi1, short lo1, short hi2, short lo2) {
        var product = hi1 * hi2;
        if (product != 0x40000000) {
            product <<= 1;
        } else {
            product = MAX_32;
        }
        var product32 = (hi1 * lo2) >> 15;
        var sum = product + (product32 << 1);
        if ((product ^ product32) > 0) {
            if (((sum ^ product) >> 31) != 0) {
                sum = (product >> 31) != 0 ? MIN_32 : MAX_32;
            }
        }
        product = sum;
        product32 = (lo1 * hi2) >> 15;
        sum = product + (product32 << 1);
        if ((product ^ product32) > 0) {
            if (((sum ^ product) >> 31) != 0) {
                sum = (product >> 31) != 0 ? MIN_32 : MAX_32;
            }
        }
        return sum;
    }

    /**
     * Multiplies a double precision value by a 16-bit value ({@code Mpy_32_16}).
     *
     * @param hi the high part of the first factor
     * @param lo the low part of the first factor
     * @param var2 the second factor
     * @param overflow the flag raised on saturation
     * @return the saturated 32-bit product
     */
    static int mpy3216(short hi, short lo, short var2, Flag overflow) {
        var product = hi * var2;
        if (product != 0x40000000) {
            product <<= 1;
        } else {
            overflow.value = true;
            product = MAX_32;
        }
        var result = (lo * var2) >> 15;
        var sum = product + (result << 1);
        if ((product ^ result) > 0) {
            if (((sum ^ product) >> 31) != 0) {
                sum = (product >> 31) != 0 ? MIN_32 : MAX_32;
                overflow.value = true;
            }
        }
        return sum;
    }

    /**
     * Accumulates the product of two double precision values ({@code Mac_32}).
     *
     * @param var3 the accumulator
     * @param hi1 the high part of the first factor
     * @param lo1 the low part of the first factor
     * @param hi2 the high part of the second factor
     * @param lo2 the low part of the second factor
     * @param overflow the flag raised on saturation
     * @return the saturated sum
     */
    static int mac32(int var3, short hi1, short lo1, short hi2, short lo2, Flag overflow) {
        var3 = lMac(var3, hi1, hi2, overflow);
        var product = mult(hi1, lo2, overflow);
        var3 = lMac(var3, product, (short) 1, overflow);
        product = mult(lo1, hi2, overflow);
        return lMac(var3, product, (short) 1, overflow);
    }

    /**
     * Accumulates the product of a double precision value and a 16-bit value
     * ({@code Mac_32_16}).
     *
     * @param var3 the accumulator
     * @param hi the high part of the first factor
     * @param lo the low part of the first factor
     * @param var2 the second factor
     * @param overflow the flag raised on saturation
     * @return the saturated sum
     */
    static int mac3216(int var3, short hi, short lo, short var2, Flag overflow) {
        var3 = lMac(var3, hi, var2, overflow);
        var product = mult(lo, var2, overflow);
        return lMac(var3, product, (short) 1, overflow);
    }

    /**
     * Divides a 32-bit value by a normalized double precision denominator ({@code Div_32}).
     *
     * <p>The denominator must be normalized and larger than the numerator; the result is in Q31.
     *
     * @param num the numerator
     * @param denomHi the high part of the denominator
     * @param denomLo the low part of the denominator
     * @param overflow the flag raised on saturation
     * @return {@code num / denom}
     */
    static int div32(int num, short denomHi, short denomLo, Flag overflow) {
        var approx = divS((short) 0x3fff, denomHi);
        var result = mpy3216(denomHi, denomLo, approx, overflow);
        result = 0x7fffffff - result;
        var hi = (short) (result >> 16);
        var lo = (short) ((result >> 1) - (hi << 15));
        result = mpy3216(hi, lo, approx, overflow);
        hi = (short) (result >> 16);
        lo = (short) ((result >> 1) - (hi << 15));
        var numHi = (short) (num >> 16);
        var numLo = (short) ((num >> 1) - (numHi << 15));
        result = mpy32(numHi, numLo, hi, lo);
        return lShl(result, (short) 2);
    }

    /**
     * Accumulates a plain product without saturation ({@code amrnb_fxp_mac_16_by_16bb}).
     *
     * @param var1 the first factor
     * @param var2 the second factor
     * @param var3 the accumulator
     * @return {@code var3 + var1 * var2}, wrapping around
     */
    static int fxpMac(int var1, int var2, int var3) {
        return var3 + var1 * var2;
    }
}
