package com.github.auties00.smalt.audio.loudness;

/**
 * Implements {@link TruePeakKernel} with scalar arithmetic.
 */
final class ScalarTruePeakKernel implements TruePeakKernel {
    /**
     * Constructs the kernel.
     */
    ScalarTruePeakKernel() {
    }

    @Override
    public float peak(float[] input, int count, float[] coefficients, int[] offsets, int phases, int taps) {
        return peak(input, 0, count, coefficients, offsets, phases, taps);
    }

    /**
     * Returns the largest output magnitude over a range of outputs.
     *
     * @param input        the filter history followed by the block
     * @param from         the index of the first output
     * @param to           the index after the last output
     * @param coefficients the coefficients, {@code taps} per phase
     * @param offsets      the input offsets of the coefficients
     * @param phases       the number of phases
     * @param taps         the number of taps of each phase
     * @return the largest magnitude, {@code 0f} for an empty range
     */
    static float peak(float[] input, int from, int to, float[] coefficients, int[] offsets, int phases, int taps) {
        var peak = 0f;
        for (var n = from; n < to; n++) {
            for (var phase = 0; phase < phases; phase++) {
                var base = phase * taps;
                var acc = 0f;
                for (var tap = 0; tap < taps; tap++) {
                    acc = Math.fma(input[n + offsets[base + tap]], coefficients[base + tap], acc);
                }
                peak = Math.max(peak, Math.abs(acc));
            }
        }
        return peak;
    }
}
