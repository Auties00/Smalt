package com.github.auties00.smalt.audio.loudness;

import com.github.auties00.smalt.internal.simd.Simd;

/**
 * Computes the largest magnitude of the oversampled signal of the true-peak meter: the outputs of
 * the polyphase interpolation filters over a block of input samples.
 *
 * <p>The input array holds the filter history followed by the block, so output {@code n} of phase
 * {@code p} is the sum over the taps {@code t} of
 * {@code coefficients[p * taps + t] * input[n + offsets[p * taps + t]]}, accumulated in tap order
 * with fused multiply-adds starting from zero. Implementations return exactly the same value.
 *
 * @implSpec Implementations must accumulate every output with {@link Math#fma(float, float, float)}
 * in tap order from {@code 0f} and return the maximum of the absolute values of all outputs (or
 * {@code 0f} when there are none), so that every implementation returns a bit-identical result.
 */
sealed interface TruePeakKernel permits ScalarTruePeakKernel, VectorLoudnessKernels.VectorTruePeakKernel {
    /**
     * The kernel every meter uses: the vector kernel when {@link Simd#ENABLED}, the scalar kernel
     * otherwise.
     */
    TruePeakKernel SELECTED = Simd.ENABLED ? VectorLoudnessKernels.truePeak() : new ScalarTruePeakKernel();

    /**
     * Returns the largest output magnitude over a block.
     *
     * @param input        the filter history followed by the block
     * @param count        the number of input samples of the block, whose outputs are computed
     * @param coefficients the coefficients, {@code taps} per phase
     * @param offsets      the index of the input sample each coefficient multiplies, relative to
     *                     the output index
     * @param phases       the number of phases
     * @param taps         the number of taps of each phase
     * @return the largest magnitude, {@code 0f} for an empty block
     */
    float peak(float[] input, int count, float[] coefficients, int[] offsets, int phases, int taps);
}
