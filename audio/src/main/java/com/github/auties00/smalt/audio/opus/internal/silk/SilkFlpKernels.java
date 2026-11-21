package com.github.auties00.smalt.audio.opus.internal.silk;

import com.github.auties00.smalt.internal.simd.Simd;

import static com.github.auties00.smalt.audio.opus.internal.silk.Define.MAX_SHAPE_LPC_ORDER;

/**
 * The hot DSP kernels of the SILK float encoder.
 *
 * <p>Implementations must return the results of {@link ScalarSilkFlpKernels}, the reference, bit for bit, so the
 * encoder's bitstream does not depend on which one runs.
 */
interface SilkFlpKernels {
    /**
     * The implementation selected for this JVM: the vector implementation when
     * {@link Simd#ENABLED}, the scalar one otherwise.
     */
    SilkFlpKernels INSTANCE = Simd.ENABLED ? VectorSilkFlpKernels.create() : new ScalarSilkFlpKernels();

    /**
     * Computes the LPC residual of samples {@code order .. length - 1}
     * ({@code silk_LPC_analysis_filterN_FLP}).
     *
     * <p>For each {@code ix}, the prediction {@code s[ix - 1] * c[0] + s[ix - 2] * c[1] + ...}
     * is summed left to right and subtracted from {@code s[ix]}. The first {@code order} output
     * samples are left untouched.
     *
     * @param rLpc    receives the residual
     * @param rLpcOff the index of the first residual sample
     * @param coef    the prediction coefficients
     * @param coefOff the index of the first coefficient
     * @param s       the signal
     * @param sOff    the index of the first sample
     * @param length  the number of samples
     * @param order   the LPC order
     */
    void lpcAnalysisFilter(float[] rLpc, int rLpcOff, float[] coef, int coefOff, float[] s, int sOff, int length, int order);

    /**
     * Computes the warped autocorrelations of four equally long signals at once
     * ({@code silk_warped_autocorrelation_FLP} applied to each).
     *
     * <p>Signal {@code c} starts at {@code c * inputStride} in {@code input}; its {@code order + 1}
     * correlations are written from {@code c * corrStride} in {@code corr}. Each result is
     * bit-identical to a separate call of {@link SigProcFlp#warpedAutocorrelationFlp}: the four
     * recursions are merely interleaved, so the processor overlaps their dependency chains.
     *
     * @param corr        receives the correlations
     * @param corrStride  the distance between the correlations of consecutive signals
     * @param input       the signals
     * @param inputStride the distance between consecutive signals
     * @param warping     the warping coefficient
     * @param length      the length of each signal
     * @param order       the correlation order, even and at most {@code MAX_SHAPE_LPC_ORDER}
     * @param scratch     a work buffer of at least {@link #warpedScratchLength(int)} values
     */
    void warpedAutocorrelation4(float[] corr, int corrStride, float[] input, int inputStride, float warping, int length,
                                int order, double[] scratch);

    /**
     * Returns the scratch length {@link #warpedAutocorrelation4} needs for signals of a length.
     *
     * @param length the signal length
     * @return the number of {@code double} values
     */
    static int warpedScratchLength(int length) {
        return 8 * (MAX_SHAPE_LPC_ORDER + 1) + 4 * length;
    }
}

