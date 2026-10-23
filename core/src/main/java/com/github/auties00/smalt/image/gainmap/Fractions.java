/* Ports libultrahdr 1.4.0 (lib/src/gainmapmath.cpp, floatToUnsignedFractionImpl). Copyright 2022 The Android Open
 * Source Project; Apache License 2.0. Modified for Smalt. */
package com.github.auties00.smalt.image.gainmap;

/**
 * Converts the real values of gain map metadata to the 32-bit fractions ISO 21496-1 stores.
 *
 * @implNote This implementation bounds the denominator by the numerator limit for values below 1 too, where
 * libultrahdr lets a signed fraction overflow its numerator and fail.
 */
final class Fractions {
    /**
     * The largest unsigned 32-bit value.
     */
    private static final long UINT32_MAX = 0xFFFFFFFFL;

    /**
     * The largest signed 32-bit value.
     */
    private static final long INT32_MAX = Integer.MAX_VALUE;

    /**
     * The most continued-fraction terms tried; the golden ratio, the worst case, needs 39.
     */
    private static final int MAX_TERMS = 39;

    private Fractions() {
        throw new AssertionError();
    }

    /**
     * Returns the closest fraction to a non-negative value whose numerator and denominator fit unsigned 32-bit
     * fields.
     *
     * @param value the value, from 0 to {@code 2^32 - 1}
     * @return the numerator in the high 32 bits and the denominator in the low 32 bits
     * @throws IllegalArgumentException if the value is negative, not finite or too large
     */
    static long unsigned(double value) {
        return fraction(value, UINT32_MAX);
    }

    /**
     * Returns the closest fraction to a value whose numerator fits a signed and whose denominator fits an
     * unsigned 32-bit field.
     *
     * @param value the value, from {@code -2^31} to {@code 2^31 - 1}
     * @return the numerator (as a signed 32-bit value) in the high 32 bits and the denominator in the low 32 bits
     * @throws IllegalArgumentException if the value is not finite or too large
     */
    static long signed(double value) {
        var fraction = fraction(Math.abs(value), value < 0 ? INT32_MAX + 1 : INT32_MAX);
        if (value < 0) {
            var numerator = -(fraction >>> 32);
            return (numerator << 32) | (fraction & UINT32_MAX);
        }
        return fraction;
    }

    /**
     * Finds the best rational approximation of a value through its continued fraction.
     *
     * @param value the non-negative value
     * @param maxNumerator the largest numerator
     * @return the numerator in the high 32 bits and the denominator in the low 32 bits
     * @throws IllegalArgumentException if the value is negative, not finite or above {@code maxNumerator}
     */
    private static long fraction(double value, long maxNumerator) {
        if (!(value >= 0) || value > maxNumerator) {
            throw new IllegalArgumentException("cannot store " + value + " as a 32-bit fraction");
        }
        var maxDenominator = value <= 0 ? UINT32_MAX : Math.min(UINT32_MAX, (long) Math.floor(maxNumerator / value));
        long denominator = 1;
        long previousDenominator = 0;
        long numerator;
        var remainder = value - Math.floor(value);
        for (var term = 0; term < MAX_TERMS; term++) {
            var exact = denominator * value;
            if (exact > maxNumerator) {
                throw new IllegalArgumentException("cannot store " + value + " as a 32-bit fraction");
            }
            numerator = Math.round(exact);
            if (exact == numerator) {
                return numerator << 32 | denominator;
            }
            remainder = 1 / remainder;
            var next = previousDenominator + Math.floor(remainder) * denominator;
            if (next > maxDenominator) {
                return numerator << 32 | denominator;
            }
            previousDenominator = denominator;
            denominator = (long) next;
            remainder -= Math.floor(remainder);
        }
        numerator = Math.round(denominator * value);
        return numerator << 32 | denominator;
    }
}
