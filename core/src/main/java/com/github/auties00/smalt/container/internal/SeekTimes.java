package com.github.auties00.smalt.container.internal;

import com.github.auties00.smalt.track.Rational;

import java.math.BigInteger;

/**
 * Converts seek targets to time bases.
 *
 * <p>A seek target names an instant; the packet displayed at it is the last one starting at or
 * before it. Converting the target to a coarser time base must therefore round down, never to
 * nearest: rounding up could move the target onto the start of the next packet, or past the end
 * of a track that ends just after it.
 */
public final class SeekTimes {
    /**
     * Prevents instantiation.
     */
    private SeekTimes() {
        throw new AssertionError();
    }

    /**
     * Converts a time in microseconds to a time base, rounding down.
     *
     * @param micros   the time in microseconds
     * @param timeBase the time base
     * @return the largest value of {@code timeBase} at or before {@code micros}, saturated to a
     *         quarter of the {@code long} range
     */
    public static long floor(long micros, Rational timeBase) {
        var numerator = BigInteger.valueOf(micros).multiply(BigInteger.valueOf(timeBase.denominator()));
        var denominator = BigInteger.valueOf(timeBase.numerator()).multiply(BigInteger.valueOf(1_000_000));
        var quotient = numerator.divide(denominator);
        if (numerator.signum() < 0 && !quotient.multiply(denominator).equals(numerator)) {
            quotient = quotient.subtract(BigInteger.ONE);
        }
        var limit = BigInteger.valueOf(Long.MAX_VALUE / 4);
        if (quotient.compareTo(limit) > 0) {
            return Long.MAX_VALUE / 4;
        }
        if (quotient.compareTo(limit.negate()) < 0) {
            return Long.MIN_VALUE / 4;
        }
        return quotient.longValue();
    }
}
