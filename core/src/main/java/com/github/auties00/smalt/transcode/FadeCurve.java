package com.github.auties00.smalt.transcode;

/**
 * Enumerates the shapes of a {@link Fade}: the gain {@code g(x)} at the fraction {@code x} of a fade-in that has
 * elapsed, from 0 to 1; a fade-out runs the same curve backwards, {@code g(1 - x)}.
 */
public enum FadeCurve {
    /**
     * A straight line, {@code g(x) = x}.
     */
    LINEAR,

    /**
     * A quarter of a sine period, {@code g(x) = sin(x pi / 2)}: fast at first, then flattening.
     */
    QUARTER_SINE,

    /**
     * Half a sine period, {@code g(x) = (1 - cos(x pi)) / 2}: slow at both ends.
     */
    HALF_SINE,

    /**
     * A parabola, {@code g(x) = x^2}: slow at first.
     */
    QUADRATIC,

    /**
     * A cubic, {@code g(x) = x^3}: slower at first than {@link #QUADRATIC}.
     */
    CUBIC,

    /**
     * A ramp of the level in decibels from -60 dB to 0 dB, {@code g(x) = 10^(-3 (1 - x))} for {@code x > 0} and
     * {@code g(0) = 0}: a fade that sounds even.
     */
    EXPONENTIAL;

    /**
     * Returns the gain of the curve at a fraction of a fade-in.
     *
     * @param fraction the elapsed fraction, clamped to 0 to 1
     * @return the gain, from 0 to 1
     */
    public double gain(double fraction) {
        var x = Math.clamp(fraction, 0.0, 1.0);
        return switch (this) {
            case LINEAR -> x;
            case QUARTER_SINE -> StrictMath.sin(x * StrictMath.PI / 2);
            case HALF_SINE -> (1 - StrictMath.cos(x * StrictMath.PI)) / 2;
            case QUADRATIC -> x * x;
            case CUBIC -> x * x * x;
            case EXPONENTIAL -> x <= 0 ? 0 : StrictMath.pow(10, -3 * (1 - x));
        };
    }
}
