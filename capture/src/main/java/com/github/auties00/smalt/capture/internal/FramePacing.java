package com.github.auties00.smalt.capture.internal;

import com.github.auties00.smalt.track.Rational;

import java.math.BigInteger;

/**
 * Converts a frame rate into the schedule of a paced capture: the frame period, the time of each tick and the
 * tick reached at a time.
 *
 * <p>Every result is exact and rounded toward zero; a result too large for a {@code long} saturates at
 * {@link Long#MAX_VALUE}. With an integer rate {@code fps}, {@code tickNanos(rate, tick)} equals
 * {@code tick * 1000000000 / fps} computed without overflow.
 */
public final class FramePacing {
    /**
     * The number of nanoseconds per second.
     */
    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    /**
     * Prevents instantiation.
     */
    private FramePacing() {
        throw new AssertionError();
    }

    /**
     * {@return the frame period of a rate, in nanoseconds, rounded down}
     *
     * @param frameRate the frames per second
     */
    public static long periodNanos(Rational frameRate) {
        return tickNanos(frameRate, 1);
    }

    /**
     * {@return the time of a tick after the first, in nanoseconds, rounded down}
     *
     * @param frameRate the frames per second
     * @param tick the tick's index, at least 0
     */
    public static long tickNanos(Rational frameRate, long tick) {
        return scale(tick, frameRate.denominator(), NANOS_PER_SECOND, frameRate.numerator(), 1);
    }

    /**
     * {@return the number of whole frame periods in a span, that is the index of the last tick due at its end}
     *
     * @param frameRate the frames per second
     * @param elapsedNanos the span, in nanoseconds, at least 0
     */
    public static long ticksElapsed(Rational frameRate, long elapsedNanos) {
        return scale(elapsedNanos, frameRate.numerator(), 1, frameRate.denominator(), NANOS_PER_SECOND);
    }

    /**
     * {@return a rate capped at an integer frame rate: {@code frameRate} when it is at most {@code maxFps},
     * otherwise {@code maxFps / 1}}
     *
     * @param frameRate the frames per second
     * @param maxFps the highest rate, positive
     */
    public static Rational atMost(Rational frameRate, long maxFps) {
        var high = Math.multiplyHigh(maxFps, frameRate.denominator());
        var low = maxFps * frameRate.denominator();
        var above = high == 0 && low >= 0 && frameRate.numerator() > low;
        return above ? new Rational(maxFps, 1) : frameRate;
    }

    /**
     * Computes {@code value * a * b / (c * d)} rounded down, for non-negative {@code value} and positive factors.
     *
     * @param value the value to scale, at least 0
     * @param a the first multiplier
     * @param b the second multiplier
     * @param c the first divisor
     * @param d the second divisor
     * @return the quotient, saturated at {@link Long#MAX_VALUE}
     */
    private static long scale(long value, long a, long b, long c, long d) {
        try {
            var product = Math.multiplyExact(Math.multiplyExact(value, a), b);
            return Math.floorDiv(product, Math.multiplyExact(c, d));
        } catch (ArithmeticException overflow) {
            var quotient = BigInteger.valueOf(value)
                    .multiply(BigInteger.valueOf(a))
                    .multiply(BigInteger.valueOf(b))
                    .divide(BigInteger.valueOf(c).multiply(BigInteger.valueOf(d)));
            return quotient.bitLength() < Long.SIZE ? quotient.longValue() : Long.MAX_VALUE;
        }
    }
}
