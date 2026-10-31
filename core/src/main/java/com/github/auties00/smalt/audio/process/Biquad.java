package com.github.auties00.smalt.audio.process;

import java.util.Objects;

/**
 * A second-order (biquad) filter section of the Audio EQ Cookbook: a shape, a center or corner frequency, a quality
 * factor and, for the peaking and shelving shapes, a gain.
 *
 * <p>The coefficients at a sampling rate {@code fs} follow the cookbook's formulas with
 * {@code w0 = 2 pi f0 / fs}, {@code alpha = sin(w0) / (2 Q)} and {@code A = 10^(gain / 40)}, normalized so that
 * {@code a0 = 1}; the transfer function is
 * {@snippet :
 * H(z) = (b0 + b1 z^-1 + b2 z^-2) / (1 + a1 z^-1 + a2 z^-2)
 * }
 * The quality factor of the shelving shapes sets the steepness of the shelf as it does for the other shapes;
 * {@link #BUTTERWORTH_Q} gives the maximally flat response (a shelf slope of one). {@link BiquadFilter} runs a
 * cascade of sections over audio.
 *
 * <p>Instances are immutable and thread-safe.
 */
public final class Biquad {
    /**
     * The quality factor of a Butterworth response, {@code 1 / sqrt(2)}: no peak at the corner frequency.
     */
    public static final double BUTTERWORTH_Q = 0.7071067811865476;

    /**
     * A shape of {@link Biquad}.
     */
    public enum Shape {
        /**
         * A low-pass filter: unit gain below the corner frequency, 12 dB per octave above it.
         */
        LOW_PASS,

        /**
         * A high-pass filter: unit gain above the corner frequency, 12 dB per octave below it.
         */
        HIGH_PASS,

        /**
         * A band-pass filter with a peak gain of 0 dB at the center frequency.
         */
        BAND_PASS,

        /**
         * A notch (band-stop) filter: zero gain at the center frequency.
         */
        NOTCH,

        /**
         * An all-pass filter: unit gain at every frequency, a phase shift of 180 degrees at the center frequency.
         */
        ALL_PASS,

        /**
         * A peaking equalizer: the gain at the center frequency, unit gain far from it.
         */
        PEAKING,

        /**
         * A low shelf: the gain below the corner frequency, unit gain above it.
         */
        LOW_SHELF,

        /**
         * A high shelf: the gain above the corner frequency, unit gain below it.
         */
        HIGH_SHELF
    }

    private final Shape shape;
    private final double frequency;
    private final double q;
    private final double gainDb;

    /**
     * Constructs a section.
     *
     * @param shape the response shape
     * @param frequency the center or corner frequency in hertz
     * @param q the quality factor
     * @param gainDb the gain in decibels
     * @throws IllegalArgumentException if the frequency or the quality factor is not positive and finite, or the
     * gain is not finite
     */
    private Biquad(Shape shape, double frequency, double q, double gainDb) {
        this.shape = Objects.requireNonNull(shape, "shape");
        if (!(frequency > 0) || !Double.isFinite(frequency)) {
            throw new IllegalArgumentException("frequency " + frequency + " is not positive");
        }
        if (!(q > 0) || !Double.isFinite(q)) {
            throw new IllegalArgumentException("quality factor " + q + " is not positive");
        }
        if (!Double.isFinite(gainDb)) {
            throw new IllegalArgumentException("gain " + gainDb + " is not finite");
        }
        this.frequency = frequency;
        this.q = q;
        this.gainDb = gainDb;
    }

    /**
     * Returns a section of any shape.
     *
     * @param shape the response shape
     * @param frequency the center or corner frequency in hertz, positive
     * @param q the quality factor, positive
     * @param gainDb the gain in decibels, which only {@link Shape#PEAKING}, {@link Shape#LOW_SHELF} and
     * {@link Shape#HIGH_SHELF} use
     * @return the section
     * @throws IllegalArgumentException if the frequency or the quality factor is not positive and finite, or the
     * gain is not finite
     */
    public static Biquad of(Shape shape, double frequency, double q, double gainDb) {
        return new Biquad(shape, frequency, q, gainDb);
    }

    /**
     * Returns a Butterworth low-pass section, which attenuates by 3 dB at the corner frequency.
     *
     * @param frequency the corner frequency in hertz, positive
     * @return the section
     * @throws IllegalArgumentException if the frequency is not positive and finite
     */
    public static Biquad lowPass(double frequency) {
        return new Biquad(Shape.LOW_PASS, frequency, BUTTERWORTH_Q, 0);
    }

    /**
     * Returns a low-pass section of a quality factor.
     *
     * @param frequency the corner frequency in hertz, positive
     * @param q the quality factor, positive
     * @return the section
     * @throws IllegalArgumentException if the frequency or the quality factor is not positive and finite
     */
    public static Biquad lowPass(double frequency, double q) {
        return new Biquad(Shape.LOW_PASS, frequency, q, 0);
    }

    /**
     * Returns a Butterworth high-pass section, which attenuates by 3 dB at the corner frequency.
     *
     * @param frequency the corner frequency in hertz, positive
     * @return the section
     * @throws IllegalArgumentException if the frequency is not positive and finite
     */
    public static Biquad highPass(double frequency) {
        return new Biquad(Shape.HIGH_PASS, frequency, BUTTERWORTH_Q, 0);
    }

    /**
     * Returns a high-pass section of a quality factor.
     *
     * @param frequency the corner frequency in hertz, positive
     * @param q the quality factor, positive
     * @return the section
     * @throws IllegalArgumentException if the frequency or the quality factor is not positive and finite
     */
    public static Biquad highPass(double frequency, double q) {
        return new Biquad(Shape.HIGH_PASS, frequency, q, 0);
    }

    /**
     * Returns a band-pass section with a peak gain of 0 dB.
     *
     * @param frequency the center frequency in hertz, positive
     * @param q the quality factor, the center frequency over the bandwidth between the -3 dB points, positive
     * @return the section
     * @throws IllegalArgumentException if the frequency or the quality factor is not positive and finite
     */
    public static Biquad bandPass(double frequency, double q) {
        return new Biquad(Shape.BAND_PASS, frequency, q, 0);
    }

    /**
     * Returns a notch section.
     *
     * @param frequency the frequency removed, in hertz, positive
     * @param q the quality factor, positive; higher values give a narrower notch
     * @return the section
     * @throws IllegalArgumentException if the frequency or the quality factor is not positive and finite
     */
    public static Biquad notch(double frequency, double q) {
        return new Biquad(Shape.NOTCH, frequency, q, 0);
    }

    /**
     * Returns an all-pass section.
     *
     * @param frequency the frequency of the 180-degree phase shift, in hertz, positive
     * @param q the quality factor, positive
     * @return the section
     * @throws IllegalArgumentException if the frequency or the quality factor is not positive and finite
     */
    public static Biquad allPass(double frequency, double q) {
        return new Biquad(Shape.ALL_PASS, frequency, q, 0);
    }

    /**
     * Returns a peaking equalizer section, one band of a parametric equalizer.
     *
     * @param frequency the center frequency in hertz, positive
     * @param q the quality factor, positive; higher values give a narrower band
     * @param gainDb the gain at the center frequency in decibels, negative to cut
     * @return the section
     * @throws IllegalArgumentException if the frequency or the quality factor is not positive and finite, or the
     * gain is not finite
     */
    public static Biquad peaking(double frequency, double q, double gainDb) {
        return new Biquad(Shape.PEAKING, frequency, q, gainDb);
    }

    /**
     * Returns a low shelf section with a shelf slope of one.
     *
     * @param frequency the corner frequency in hertz, where the gain is half the shelf gain in decibels, positive
     * @param gainDb the gain below the corner frequency in decibels
     * @return the section
     * @throws IllegalArgumentException if the frequency is not positive and finite, or the gain is not finite
     */
    public static Biquad lowShelf(double frequency, double gainDb) {
        return new Biquad(Shape.LOW_SHELF, frequency, BUTTERWORTH_Q, gainDb);
    }

    /**
     * Returns a high shelf section with a shelf slope of one.
     *
     * @param frequency the corner frequency in hertz, where the gain is half the shelf gain in decibels, positive
     * @param gainDb the gain above the corner frequency in decibels
     * @return the section
     * @throws IllegalArgumentException if the frequency is not positive and finite, or the gain is not finite
     */
    public static Biquad highShelf(double frequency, double gainDb) {
        return new Biquad(Shape.HIGH_SHELF, frequency, BUTTERWORTH_Q, gainDb);
    }

    /**
     * {@return the shape}
     */
    public Shape shape() {
        return shape;
    }

    /**
     * {@return the center or corner frequency in hertz}
     */
    public double frequency() {
        return frequency;
    }

    /**
     * {@return the quality factor}
     */
    public double q() {
        return q;
    }

    /**
     * {@return the gain in decibels of the peaking and shelving shapes, ignored by the others}
     */
    public double gainDb() {
        return gainDb;
    }

    /**
     * Returns the normalized coefficients at a sampling rate.
     *
     * @param sampleRate the sampling rate in hertz, more than twice {@link #frequency()}
     * @return {@code b0, b1, b2, a1, a2}, with {@code a0} divided out
     * @throws IllegalArgumentException if the frequency is not below half the sampling rate
     */
    public double[] coefficients(int sampleRate) {
        if (sampleRate <= 0 || !(frequency < sampleRate / 2.0)) {
            throw new IllegalArgumentException("frequency " + frequency + " Hz is not below half the sampling rate "
                    + sampleRate);
        }
        var w0 = 2 * StrictMath.PI * frequency / sampleRate;
        var cos = StrictMath.cos(w0);
        var alpha = StrictMath.sin(w0) / (2 * q);
        var a = StrictMath.pow(10, gainDb / 40);
        var root = 2 * StrictMath.sqrt(a) * alpha;
        double b0;
        double b1;
        double b2;
        double a0;
        double a1;
        double a2;
        switch (shape) {
            case LOW_PASS -> {
                b0 = (1 - cos) / 2;
                b1 = 1 - cos;
                b2 = b0;
                a0 = 1 + alpha;
                a1 = -2 * cos;
                a2 = 1 - alpha;
            }
            case HIGH_PASS -> {
                b0 = (1 + cos) / 2;
                b1 = -(1 + cos);
                b2 = b0;
                a0 = 1 + alpha;
                a1 = -2 * cos;
                a2 = 1 - alpha;
            }
            case BAND_PASS -> {
                b0 = alpha;
                b1 = 0;
                b2 = -alpha;
                a0 = 1 + alpha;
                a1 = -2 * cos;
                a2 = 1 - alpha;
            }
            case NOTCH -> {
                b0 = 1;
                b1 = -2 * cos;
                b2 = 1;
                a0 = 1 + alpha;
                a1 = -2 * cos;
                a2 = 1 - alpha;
            }
            case ALL_PASS -> {
                b0 = 1 - alpha;
                b1 = -2 * cos;
                b2 = 1 + alpha;
                a0 = 1 + alpha;
                a1 = -2 * cos;
                a2 = 1 - alpha;
            }
            case PEAKING -> {
                b0 = 1 + alpha * a;
                b1 = -2 * cos;
                b2 = 1 - alpha * a;
                a0 = 1 + alpha / a;
                a1 = -2 * cos;
                a2 = 1 - alpha / a;
            }
            case LOW_SHELF -> {
                b0 = a * ((a + 1) - (a - 1) * cos + root);
                b1 = 2 * a * ((a - 1) - (a + 1) * cos);
                b2 = a * ((a + 1) - (a - 1) * cos - root);
                a0 = (a + 1) + (a - 1) * cos + root;
                a1 = -2 * ((a - 1) + (a + 1) * cos);
                a2 = (a + 1) + (a - 1) * cos - root;
            }
            default -> {
                b0 = a * ((a + 1) + (a - 1) * cos + root);
                b1 = -2 * a * ((a - 1) + (a + 1) * cos);
                b2 = a * ((a + 1) + (a - 1) * cos - root);
                a0 = (a + 1) - (a - 1) * cos + root;
                a1 = 2 * ((a - 1) - (a + 1) * cos);
                a2 = (a + 1) - (a - 1) * cos - root;
            }
        }
        return new double[]{b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0};
    }

    /**
     * Returns the magnitude of the section's frequency response at a frequency.
     *
     * @param sampleRate the sampling rate in hertz, more than twice {@link #frequency()}
     * @param frequency the frequency evaluated, in hertz, from 0 to half the sampling rate
     * @return the linear gain, {@code |H(e^jw)|}
     * @throws IllegalArgumentException if the section's frequency is not below half the sampling rate, or the
     * evaluated frequency is outside the range
     */
    public double magnitude(int sampleRate, double frequency) {
        var c = coefficients(sampleRate);
        if (!(frequency >= 0 && frequency <= sampleRate / 2.0)) {
            throw new IllegalArgumentException("frequency " + frequency + " Hz is outside 0 to " + sampleRate / 2.0);
        }
        var w = 2 * StrictMath.PI * frequency / sampleRate;
        var cos1 = StrictMath.cos(w);
        var sin1 = StrictMath.sin(w);
        var cos2 = StrictMath.cos(2 * w);
        var sin2 = StrictMath.sin(2 * w);
        var numeratorRe = c[0] + c[1] * cos1 + c[2] * cos2;
        var numeratorIm = -(c[1] * sin1 + c[2] * sin2);
        var denominatorRe = 1 + c[3] * cos1 + c[4] * cos2;
        var denominatorIm = -(c[3] * sin1 + c[4] * sin2);
        return StrictMath.sqrt((numeratorRe * numeratorRe + numeratorIm * numeratorIm)
                / (denominatorRe * denominatorRe + denominatorIm * denominatorIm));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Biquad that && shape == that.shape && Double.compare(frequency, that.frequency) == 0
                && Double.compare(q, that.q) == 0 && Double.compare(gainDb, that.gainDb) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(shape, frequency, q, gainDb);
    }

    @Override
    public String toString() {
        return "Biquad[shape=" + shape + ", frequency=" + frequency + ", q=" + q + ", gainDb=" + gainDb + "]";
    }
}
