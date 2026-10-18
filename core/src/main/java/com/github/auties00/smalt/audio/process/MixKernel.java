package com.github.auties00.smalt.audio.process;

import com.github.auties00.smalt.internal.simd.Simd;

/**
 * Computes one output channel of the {@link ChannelMixer} as a weighted sum of input channels.
 *
 * <p>Two implementations exist: {@link ScalarMixKernel}, the reference, and a Vector API
 * implementation created by {@link VectorProcessKernels#mix()}. Both evaluate, for every sample,
 * {@code g0 * x0}, then add {@code g1 * x1}, {@code g2 * x2} and so on with a separate rounding
 * after each multiplication and each addition, so their results are bit-identical.
 */
sealed interface MixKernel permits ScalarMixKernel, VectorProcessKernels.VectorMixKernel {
    /**
     * The implementation selected for this JVM: the vector implementation when
     * {@link Simd#ENABLED}, the scalar one otherwise.
     */
    MixKernel SELECTED = Simd.ENABLED ? VectorProcessKernels.mix() : new ScalarMixKernel();

    /**
     * Writes {@code out[i] = sum(gains[j] * inputs[j][i])} for {@code j} in {@code [0, count)}
     * and {@code i} in {@code [0, frames)}, accumulating in increasing {@code j}.
     *
     * @param inputs the input planes, one per term
     * @param gains  the weights, one per term
     * @param count  the number of terms; positive
     * @param out    the output plane, which must not be one of {@code inputs}
     * @param frames the number of samples; non-negative
     */
    void mix(float[][] inputs, float[] gains, int count, float[] out, int frames);
}
