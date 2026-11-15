package com.github.auties00.smalt.image.jp2.internal;

import com.github.auties00.smalt.internal.io.ByteArrays;

/**
 * Reads the numeric field types of the ISO/IEC 15444-2 marker segments: two's complement integers and big-endian
 * ISO/IEC/IEEE 60559 binary floating-point numbers of 32, 64 and 128 bits.
 *
 * <p>Arguments are not checked; values outside the documented ranges give unspecified results.
 */
final class Part2Values {
    /**
     * Prevents instantiation.
     */
    private Part2Values() {
        throw new AssertionError();
    }

    /**
     * Reads a value of an {@code ATK} coefficient type ({@code Coeff_Typ}).
     *
     * @param data the bytes
     * @param position the index of the first byte
     * @param type 0 for an 8-bit and 1 for a 16-bit signed integer, 2, 3 and 4 for 32, 64 and 128-bit binary floating
     * point
     * @return the value, a 128-bit number rounded to the nearest double
     */
    static double real(byte[] data, int position, int type) {
        return switch (type) {
            case 0 -> data[position];
            case 1 -> (short) ByteArrays.u16be(data, position);
            case 2 -> Float.intBitsToFloat((int) ByteArrays.u32be(data, position));
            case 3 -> Double.longBitsToDouble(u64(data, position));
            default -> quadruple(u64(data, position), u64(data, position + 8));
        };
    }

    /**
     * Reads a big-endian 64-bit value.
     *
     * @param data the bytes
     * @param position the index of the first byte
     * @return the value
     */
    static long u64(byte[] data, int position) {
        return (ByteArrays.u32be(data, position) << 32) | ByteArrays.u32be(data, position + 4);
    }

    /**
     * Converts a binary128 number to the nearest double, ties to even.
     *
     * @param high the sign, the 15-bit exponent and the top 48 bits of the significand
     * @param low the low 64 bits of the significand
     * @return the value; infinite when it exceeds the double range, a signed zero or subnormal double when below it
     */
    static double quadruple(long high, long low) {
        var negative = high < 0;
        var exponent = (int) ((high >>> 48) & 0x7FFF);
        var significandHigh = high & 0xFFFF_FFFF_FFFFL;
        if (exponent == 0x7FFF) {
            if (significandHigh != 0 || low != 0) {
                return Double.NaN;
            }
            return negative ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        }
        if (exponent == 0 && significandHigh == 0 && low == 0) {
            return negative ? -0.0 : 0.0;
        }
        // 53 significant bits: the implicit one (absent for subnormals), 48 high bits and the top 4 of the low word
        var top = ((exponent == 0 ? 0L : 1L) << 52) | (significandHigh << 4) | (low >>> 60);
        var rest = low << 4;
        if (rest < 0 && ((rest << 1) != 0 || (top & 1) != 0)) {
            top++;
        }
        var unbiased = (exponent == 0 ? 1 : exponent) - 16383 - 52;
        var value = Math.scalb((double) top, unbiased);
        return negative ? -value : value;
    }
}
