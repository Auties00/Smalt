package com.github.auties00.smalt.audio.celp.internal;

/**
 * The CELP post-processor (ISO/IEC 14496-3 Annex 3.A, informative): a formant post-filter, an optional pitch
 * post-filter and a tilt compensation filter, followed by adaptive gain control.
 *
 * <p>Instances are not thread-safe.
 */
final class PostFilter {
    /**
     * The numerator weighting factor of the formant post-filter.
     */
    private static final double GAMMA_N = 0.65;

    /**
     * The denominator weighting factor of the formant post-filter.
     */
    private static final double GAMMA_D = 0.75;

    /**
     * The pitch post-filter factor.
     */
    private static final double GAMMA_P = 0.5;

    /**
     * The tilt factor.
     */
    private static final double GAMMA_T = 0.3;

    /**
     * The largest pitch delay the history keeps.
     */
    private static final int MAX_DELAY = 400;

    /**
     * The LPC order.
     */
    private final int order;

    /**
     * Whether the pitch post-filter may run.
     */
    private final boolean pitch;

    /**
     * The last {@code order} input samples, newest first, the memory of the numerator.
     */
    private final double[] numeratorMemory;

    /**
     * The last {@code order} outputs of the denominator, newest first.
     */
    private final double[] denominatorMemory;

    /**
     * The residual history, {@link #MAX_DELAY} samples followed by the current subframe.
     */
    private final double[] residual;

    /**
     * The pitch post-filtered residual of the current subframe.
     */
    private final double[] filtered;

    /**
     * The weighted numerator and denominator coefficients.
     */
    private final double[] numerator;

    private final double[] denominator;

    /**
     * The last output of the formant post-filter, the memory of the tilt filter.
     */
    private double tiltMemory;

    /**
     * The gain of the last sample, {@code g(n - 1)}, which the standard starts from 0.
     */
    private double gain;

    /**
     * Creates a post-processor.
     *
     * @param order the LPC order
     * @param subframeSize the largest subframe
     * @param pitch whether the pitch post-filter may run (the multi-pulse coder)
     */
    PostFilter(int order, int subframeSize, boolean pitch) {
        this.order = order;
        this.pitch = pitch;
        this.numeratorMemory = new double[order];
        this.denominatorMemory = new double[order];
        this.residual = new double[MAX_DELAY + subframeSize];
        this.filtered = new double[subframeSize];
        this.numerator = new double[order];
        this.denominator = new double[order];
    }

    /**
     * Filters one subframe in place.
     *
     * @param signal the synthesized subframe, replaced by the post-filtered one
     * @param offset the first sample
     * @param length the samples of the subframe
     * @param a the synthesis filter coefficients of the subframe
     * @param delay the integer pitch delay, or less than 10 to skip the pitch post-filter
     * @param adaptiveGain the adaptive codebook gain, or less than 0.4 to skip the pitch post-filter
     */
    void apply(double[] signal, int offset, int length, double[] a, int delay, double adaptiveGain) {
        var gn = 1.0;
        var gd = 1.0;
        for (var k = 0; k < order; k++) {
            gn *= GAMMA_N;
            gd *= GAMMA_D;
            numerator[k] = a[k] * gn;
            denominator[k] = a[k] * gd;
        }
        System.arraycopy(residual, length, residual, 0, MAX_DELAY);
        var inputEnergy = 0.0;
        for (var n = 0; n < length; n++) {
            var x = signal[offset + n];
            inputEnergy += x * x;
            var r = x;
            for (var k = 0; k < order; k++) {
                r -= numerator[k] * numeratorMemory[k];
            }
            System.arraycopy(numeratorMemory, 0, numeratorMemory, 1, order - 1);
            numeratorMemory[0] = x;
            residual[MAX_DELAY + n] = r;
        }
        var usePitch = pitch && delay >= 10 && delay < MAX_DELAY && adaptiveGain > 0.4;
        var gp = 0.0;
        if (usePitch) {
            var correlation = 0.0;
            var energy = 0.0;
            for (var n = 0; n < length; n++) {
                var past = residual[MAX_DELAY + n - delay];
                correlation += residual[MAX_DELAY + n] * past;
                energy += past * past;
            }
            var ga = energy > 0 ? correlation / energy : 0;
            ga = Math.min(Math.max(ga, 0), 1);
            gp = GAMMA_P * ga;
        }
        for (var n = 0; n < length; n++) {
            var r = residual[MAX_DELAY + n];
            filtered[n] = usePitch ? (r + gp * residual[MAX_DELAY + n - delay]) / (1 + gp) : r;
        }
        var outputEnergy = 0.0;
        for (var n = 0; n < length; n++) {
            var y = filtered[n];
            for (var k = 0; k < order; k++) {
                y += denominator[k] * denominatorMemory[k];
            }
            System.arraycopy(denominatorMemory, 0, denominatorMemory, 1, order - 1);
            denominatorMemory[0] = y;
            var tilted = y - GAMMA_T * tiltMemory;
            tiltMemory = y;
            signal[offset + n] = tilted;
            outputEnergy += tilted * tilted;
        }
        var target = outputEnergy > 0 ? Math.sqrt(inputEnergy / outputEnergy) : 0;
        for (var n = 0; n < length; n++) {
            gain = 0.95 * gain + 0.05 * target;
            signal[offset + n] *= gain;
        }
    }
}
