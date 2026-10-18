package com.github.auties00.smalt.track;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/**
 * Represents an exact positive fraction, used for time bases and frame rates.
 *
 * <p>A time base of {@code 1/90000} means a timestamp of {@code 90000} is one second. Fractions
 * are stored as given, not reduced.
 *
 * <p>Instances are immutable and thread-safe.
 *
 * @param numerator   the numerator; positive
 * @param denominator the denominator; positive
 */
public record Rational(long numerator, long denominator) {
    /**
     * The microsecond time base, {@code 1/1000000}.
     */
    public static final Rational MICROSECONDS = new Rational(1, 1_000_000);

    /**
     * The millisecond time base, {@code 1/1000}.
     */
    public static final Rational MILLISECONDS = new Rational(1, 1_000);

    /**
     * Validates the fraction.
     *
     * @throws IllegalArgumentException if the numerator or denominator is not positive
     */
    public Rational {
        if (numerator <= 0 || denominator <= 0) {
            throw new IllegalArgumentException("invalid rational " + numerator + "/" + denominator);
        }
    }

    /**
     * Returns the time base whose tick is one period of a rate, {@code 1/rate}.
     *
     * <p>A sampling rate of 48000 hertz gives the time base {@code 1/48000}, in which a timestamp
     * counts samples.
     *
     * @param rate the rate in hertz, positive
     * @return the time base {@code 1/rate}
     * @throws IllegalArgumentException if {@code rate} is not positive
     */
    public static Rational timeBaseOf(long rate) {
        return new Rational(1, rate);
    }

    /**
     * Converts a value expressed in this time base to another time base, rounding to nearest
     * with ties away from zero.
     *
     * <p>The computation is exact for every {@code long} input: it uses 64-bit arithmetic when the
     * intermediate product fits and falls back to arbitrary precision otherwise.
     *
     * @param value  the value in units of this time base
     * @param target the time base to convert to
     * @return the value in units of {@code target}
     * @throws ArithmeticException if the result does not fit in a {@code long}
     */
    public long rescale(long value, Rational target) {
        var multiplier = numerator * 1.0 * target.denominator;
        var divisor = denominator * 1.0 * target.numerator;
        if (Math.abs(value * multiplier) < 0x1p62 && multiplier < 0x1p62 && divisor < 0x1p62) {
            var product = value * (numerator * target.denominator);
            var divide = denominator * target.numerator;
            var half = divide / 2;
            return product >= 0 ? (product + half) / divide : -((-product + half) / divide);
        }
        var exact = BigDecimal.valueOf(value)
                .multiply(BigDecimal.valueOf(numerator))
                .multiply(BigDecimal.valueOf(target.denominator))
                .divide(new BigDecimal(BigInteger.valueOf(denominator).multiply(BigInteger.valueOf(target.numerator))),
                        0, RoundingMode.HALF_UP);
        return exact.longValueExact();
    }

    /**
     * Converts a value expressed in this time base to microseconds.
     *
     * @param value the value in units of this time base
     * @return the value in microseconds, rounded to nearest
     * @throws ArithmeticException if the result does not fit in a {@code long}
     */
    public long toMicros(long value) {
        return rescale(value, MICROSECONDS);
    }

    /**
     * Returns the value of this fraction as a double.
     *
     * @return {@code numerator / denominator}
     */
    public double toDouble() {
        return (double) numerator / denominator;
    }

    /**
     * Returns the reciprocal of this fraction.
     *
     * @return {@code denominator / numerator}
     */
    public Rational invert() {
        return new Rational(denominator, numerator);
    }

    /**
     * Returns this fraction reduced to lowest terms.
     *
     * @return the reduced fraction
     */
    public Rational reduce() {
        var gcd = BigInteger.valueOf(numerator).gcd(BigInteger.valueOf(denominator)).longValue();
        return gcd == 1 ? this : new Rational(numerator / gcd, denominator / gcd);
    }

    @Override
    public String toString() {
        return numerator + "/" + denominator;
    }
}
