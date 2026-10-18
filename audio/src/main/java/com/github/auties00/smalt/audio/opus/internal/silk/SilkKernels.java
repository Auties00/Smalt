package com.github.auties00.smalt.audio.opus.internal.silk;

import com.github.auties00.smalt.internal.simd.Simd;

/**
 * The hot fixed-point kernels of SILK.
 *
 * <p>Implementations must return the results of {@link ScalarSilkKernels}, the reference, bit for bit.
 */
public interface SilkKernels {
    /**
     * The implementation selected for this JVM: the vector implementation when
     * {@link Simd#ENABLED}, the scalar one otherwise.
     */
    SilkKernels INSTANCE = Simd.ENABLED ? VectorSilkKernels.create() : new ScalarSilkKernels();

    /**
     * The size of the work array the kernels accept, enough for every rate pair of the resampler.
     */
    int SCRATCH_SIZE = 4096;

    /**
     * Interpolates a 2x upsampled signal at fractional positions with the 12-phase, 8-tap FIR
     * table ({@code silk_resampler_private_IIR_FIR_INTERPOL}).
     *
     * @param out                 receives the output signal
     * @param outOff              the index of the first output sample
     * @param buf                 the upsampled signal, preceded by 8 samples of history
     * @param max_index_Q16       the end position in Q16
     * @param index_increment_Q16 the position increment in Q16
     * @param scratch             a work array of at least {@link #SCRATCH_SIZE} elements, owned by
     *                            the calling resampler; implementations may overwrite it
     * @return the index after the last output sample written
     */
    int iirFirInterpol(short[] out, int outOff, short[] buf, int max_index_Q16, int index_increment_Q16,
                       int[] scratch);

    /**
     * Interpolates an AR-filtered signal at fractional positions with a symmetric decimation FIR
     * ({@code silk_resampler_private_down_FIR_INTERPOL}).
     *
     * @param out                 receives the output signal
     * @param outOff              the index of the first output sample
     * @param buf                 the filtered signal in Q8, preceded by {@code FIR_Order} samples
     *                            of history
     * @param coefs               the array holding the FIR coefficients
     * @param coefsOff            the index of the first FIR coefficient
     * @param FIR_Order           the FIR order: 18, 24 or 36
     * @param FIR_Fracs           the number of FIR phases
     * @param max_index_Q16       the end position in Q16
     * @param index_increment_Q16 the position increment in Q16
     * @param scratch             a work array of at least {@link #SCRATCH_SIZE} elements, owned by
     *                            the calling resampler; implementations may overwrite it
     * @return the index after the last output sample written
     */
    int downFirInterpol(short[] out, int outOff, int[] buf, short[] coefs, int coefsOff, int FIR_Order, int FIR_Fracs,
                        int max_index_Q16, int index_increment_Q16, int[] scratch);

    /**
     * Computes the LPC prediction residual of a signal, without the zeroing of the first
     * {@code d} outputs ({@code silk_LPC_analysis_filter}).
     *
     * <p>For every {@code ix} in {@code [d, len)}, the output is the saturated, rounded
     * {@code (in[ix] << 12) - sum(j < d) in[ix - 1 - j] * B[j]} shifted right by 12, with the sum
     * wrapping in 32 bits.
     *
     * @param out    receives the residual
     * @param outOff the index of the first output sample
     * @param in     the input signal
     * @param inOff  the index of the first input sample
     * @param B      the prediction coefficients in Q12
     * @param BOff   the index of the first coefficient
     * @param len    the number of samples
     * @param d      the filter order, even and at least 6
     */
    void lpcAnalysisFilter(short[] out, int outOff, short[] in, int inOff, short[] B, int BOff, int len, int d);

    /**
     * Computes, for the four delayed-decision states of the noise shaping quantizer, the sums of
     * the short-term prediction and of the noise-shaping feedback ({@code silk_SMLAWB} chains of
     * {@code silk_noise_shape_quantizer_del_dec}).
     *
     * <p>For each state {@code k} in {@code [0, 4)}, whose block starts at {@code k * stride} in
     * {@code dd}:
     * <ul>
     * <li>{@code out[k]} is the sum over {@code m < 16} of
     * {@code smulwb(dd[k * stride + lpcOffset + m], lpcCoef[m])};</li>
     * <li>{@code out[4 + k]} is the sum over {@code m < 24} of
     * {@code smulwb(dd[k * stride + arOffset + m], arCoef[m])},</li>
     * </ul>
     * with {@code smulwb(a, b) = (int) ((a * (long) b) >> 16)} and the sums wrapping in 32 bits.
     *
     * @param dd        the state blocks
     * @param stride    the distance between two state blocks
     * @param lpcOffset the offset in a block of the 16 prediction-history values
     * @param lpcCoef   the 16 prediction coefficients, each in the 16-bit range
     * @param arOffset  the offset in a block of the 24 shaping-filter states
     * @param arCoef    the 24 shaping coefficients, each in the 16-bit range
     * @param out       receives the four prediction sums, then the four shaping sums
     */
    void nsqDotProducts(int[] dd, int stride, int lpcOffset, int[] lpcCoef, int arOffset, int[] arCoef, int[] out);
}
