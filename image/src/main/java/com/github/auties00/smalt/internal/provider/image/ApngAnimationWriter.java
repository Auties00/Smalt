package com.github.auties00.smalt.internal.provider.image;

import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.png.ApngEncoder;
import com.github.auties00.smalt.io.MediaSink;

/**
 * Presents an {@link ApngEncoder} as an
 * {@link com.github.auties00.smalt.image.AnimationWriter AnimationWriter} that writes to a sink.
 *
 * <p>APNG stores each frame's delay as a fraction of 16-bit terms. A duration whose reduced
 * fraction of a second fits (every whole number of milliseconds does, as do the frame times of
 * common rates such as 1/30 or 1001/30000 of a second given in microseconds) is stored exactly.
 * Any other duration is approximated by the closest fraction of 16-bit terms to the time between
 * the frame's end and the end of the frames already stored, so the approximation error never
 * accumulates over the animation.
 */
public final class ApngAnimationWriter extends TimedAnimationWriter {
    /**
     * The largest delay term APNG stores.
     */
    private static final int MAX_TERM = 0xFFFF;

    /**
     * The number of microseconds in a second.
     */
    private static final long MICROS = 1_000_000L;

    /**
     * The encoder, of the canvas size.
     */
    private final ApngEncoder encoder;

    /**
     * The sum of the stored delays, in microseconds.
     */
    private double storedMicros;

    /**
     * Whether every delay so far was stored exactly, so that {@link #storedMicros} equals the end
     * time of the frames written.
     */
    private boolean exact = true;

    /**
     * Constructs the writer.
     *
     * @param encoder the encoder, of the canvas size
     * @param width the canvas width
     * @param height the canvas height
     * @param sink the output, or {@code null} when {@link #finish()} is never called
     */
    public ApngAnimationWriter(ApngEncoder encoder, int width, int height, MediaSink sink) {
        super(width, height, sink);
        this.encoder = encoder;
    }

    @Override
    void writeFrame(RgbaPicture picture, long durationMicros, long startMicros, long endMicros) {
        if (durationMicros > MAX_TERM * MICROS) {
            throw new IllegalArgumentException("frame duration " + durationMicros + " exceeds what APNG stores");
        }
        var divisor = gcd(durationMicros, MICROS);
        int numerator;
        int denominator;
        var stays = exact && durationMicros / divisor <= MAX_TERM && MICROS / divisor <= MAX_TERM;
        if (stays) {
            numerator = (int) (durationMicros / divisor);
            denominator = (int) (MICROS / divisor);
        } else {
            var fraction = approximate(Math.max(0, (endMicros - storedMicros) / MICROS), MAX_TERM);
            numerator = (int) (fraction >>> 32);
            denominator = (int) fraction;
        }
        encoder.addFrame(picture, numerator, denominator);
        exact = stays;
        storedMicros = stays ? endMicros : storedMicros + numerator * (double) MICROS / denominator;
    }

    @Override
    public void finish() {
        encoder.finish(sink);
    }

    /**
     * Returns the greatest common divisor of a non-negative and a positive number.
     *
     * @param a the first number
     * @param b the second number
     * @return the divisor
     */
    private static long gcd(long a, long b) {
        while (b != 0) {
            var t = a % b;
            a = b;
            b = t;
        }
        return a;
    }

    /**
     * Returns the fraction closest to a non-negative number among those whose numerator and
     * denominator are at most a bound.
     *
     * @implNote This implementation walks the continued fraction expansion and, where the next
     * convergent exceeds the bound, also considers the largest semiconvergent within it; the best
     * rational approximation of bounded terms is always one of these.
     *
     * @param value the number
     * @param max the bound of both terms
     * @return the numerator in the upper 32 bits and the denominator, at least 1, in the lower 32
     */
    static long approximate(double value, int max) {
        if (value >= max) {
            return (long) max << 32 | 1;
        }
        long previousNumerator = 0;
        long numerator = 1;
        long previousDenominator = 1;
        long denominator = 0;
        var rest = value;
        for (var i = 0; i < 64; i++) {
            var term = (long) Math.floor(rest);
            var nextNumerator = term * numerator + previousNumerator;
            var nextDenominator = term * denominator + previousDenominator;
            if (nextNumerator > max || nextDenominator > max) {
                var byNumerator = numerator == 0 ? Long.MAX_VALUE : (max - previousNumerator) / numerator;
                var byDenominator = denominator == 0 ? Long.MAX_VALUE : (max - previousDenominator) / denominator;
                var steps = Math.min(byNumerator, byDenominator);
                var semiNumerator = steps * numerator + previousNumerator;
                var semiDenominator = steps * denominator + previousDenominator;
                if (steps > 0 && semiDenominator > 0 && (denominator == 0 || Math.abs(value - (double) semiNumerator / semiDenominator)
                        < Math.abs(value - (double) numerator / denominator))) {
                    return semiNumerator << 32 | semiDenominator;
                }
                break;
            }
            previousNumerator = numerator;
            numerator = nextNumerator;
            previousDenominator = denominator;
            denominator = nextDenominator;
            var fraction = rest - term;
            if (fraction < 1e-12) {
                break;
            }
            rest = 1 / fraction;
        }
        return numerator << 32 | Math.max(1, denominator);
    }
}
