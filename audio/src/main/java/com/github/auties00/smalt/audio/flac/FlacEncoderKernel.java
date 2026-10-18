package com.github.auties00.smalt.audio.flac;

import com.github.auties00.smalt.internal.simd.Simd;

/**
 * Performs the data-parallel stages of FLAC encoding: windowing, autocorrelation, fixed and
 * linear-predictive residuals, the fixed-predictor error sums, residual magnitude sums and the
 * mid/side transform.
 *
 * <p>Two implementations exist: {@link ScalarFlacEncoderKernel}, the reference, and
 * {@code VectorFlacEncoderKernel}, which uses the Vector API. Both return bit-identical results:
 * the integer kernels are exact, and the floating-point ones vectorize across independent outputs
 * (the lags of the autocorrelation, the samples of the window) without reassociating any sum, so
 * the encoder's bitstream never depends on which implementation runs.
 */
interface FlacEncoderKernel {
    /**
     * The implementation selected for this JVM: the vector implementation when
     * {@link Simd#ENABLED}, the scalar one otherwise.
     */
    FlacEncoderKernel INSTANCE = Simd.ENABLED ? VectorFlacEncoderKernel.create() : new ScalarFlacEncoderKernel();

    /**
     * The number of zero elements {@link #autocorrelation(double[], int, int, double[])} needs
     * after the data, and the number of spare elements it needs after the lags.
     */
    int AUTOCORRELATION_PADDING = 64;

    /**
     * Multiplies samples by a window: {@code out[outOffset + i] = signal[signalOffset + i] *
     * window[windowOffset + i]} for {@code i} in {@code [0, count)}, each product rounded once to
     * double.
     *
     * @param signal the integer samples
     * @param signalOffset the index of the first sample
     * @param window the window coefficients
     * @param windowOffset the index of the first window coefficient
     * @param out the destination
     * @param outOffset the index of the first windowed sample
     * @param count the number of samples
     */
    void window(int[] signal, int signalOffset, double[] window, int windowOffset, double[] out, int outOffset, int count);

    /**
     * Computes the autocorrelation of a windowed signal.
     *
     * <p>{@code autoc[c] = sum(s = 0 .. length - 1 - c) data[s] * data[s + c]} for {@code c} in
     * {@code [0, lags)}, accumulated in increasing {@code s} with one rounding per multiply and
     * one per add. {@code data} must hold {@value #AUTOCORRELATION_PADDING} zeros after
     * {@code length}, and {@code autoc} must have {@value #AUTOCORRELATION_PADDING} spare
     * elements after {@code lags}, which may be overwritten.
     *
     * @param data the windowed signal, zero padded
     * @param length the number of samples
     * @param lags the number of lags, from 1 to 33
     * @param autoc receives the autocorrelation
     */
    void autocorrelation(double[] data, int length, int lags, double[] autoc);

    /**
     * Computes the residual of a quantized linear predictor with 32-bit arithmetic:
     * {@code residual[i] = signal[i] - (sum(j) coefficients[j] * signal[i - 1 - j] >> shift)} for
     * {@code i} in {@code [order, length)}.
     *
     * <p>The caller guarantees that every partial sum fits a signed 32-bit integer.
     *
     * @param signal the samples
     * @param length the number of samples
     * @param coefficients the quantized coefficients
     * @param order the predictor order, from 1 to 32
     * @param shift the quantization shift, from 0 to 15
     * @param residual receives the residual at the samples' indices
     */
    void lpcResidual(int[] signal, int length, int[] coefficients, int order, int shift, int[] residual);

    /**
     * Computes the residual of a quantized linear predictor with 64-bit arithmetic, as
     * {@link #lpcResidual(int[], int, int[], int, int, int[])} does, and checks that every
     * residual sample fits in {@code (Integer.MIN_VALUE, Integer.MAX_VALUE]}.
     *
     * @param signal the samples
     * @param length the number of samples
     * @param coefficients the quantized coefficients
     * @param order the predictor order, from 1 to 32
     * @param shift the quantization shift, from 0 to 15
     * @param residual receives the residual at the samples' indices
     * @return {@code false} when a residual sample does not fit, in which case {@code residual}
     * holds partial results
     */
    boolean lpcResidualWide(int[] signal, int length, int[] coefficients, int order, int shift, int[] residual);

    /**
     * Computes the residual of a fixed predictor (RFC 9639 section 9.2.5) for
     * {@code i} in {@code [order, length)}, at the samples' indices.
     *
     * <p>The caller guarantees that every residual sample fits a signed 32-bit integer.
     *
     * @param signal the samples
     * @param length the number of samples
     * @param order the predictor order, from 0 to 4
     * @param residual receives the residual
     */
    void fixedResidual(int[] signal, int length, int order, int[] residual);

    /**
     * Sums the magnitudes of the residuals of the five fixed predictors over the samples
     * {@code [4, length)}.
     *
     * <p>{@code errors[k]} receives the sum for order {@code k}. Up to
     * {@value #FIXED_EXACT_BITS} bits per sample every residual magnitude is below
     * {@code 2^(bitsPerSample + 3)} and the sums are exact; above it each residual is computed in
     * 64 bits and a residual whose magnitude is not below {@code 2^31} marks its order invalid by
     * setting its sum to {@link Long#MAX_VALUE}.
     *
     * @param signal the samples, at least five, each representable in
     * {@code bitsPerSample} bits
     * @param length the number of samples
     * @param errors receives the five sums
     * @param bitsPerSample the sample size, from 1 to 32
     */
    void fixedErrors(int[] signal, int length, long[] errors, int bitsPerSample);

    /**
     * The largest sample size for which {@link #fixedErrors(int[], int, long[], int)} uses 32-bit
     * residuals.
     */
    int FIXED_EXACT_BITS = 27;

    /**
     * Sums the magnitudes of a residual range.
     *
     * @param residual the residual, no element equal to {@link Integer#MIN_VALUE}
     * @param from the index of the first sample
     * @param to the index after the last sample
     * @return the sum
     */
    long absSum(int[] residual, int from, int to);

    /**
     * Computes the mid and side channels of a stereo block: {@code side[i] = left[i] - right[i]}
     * and {@code mid[i] = (left[i] + right[i]) >> 1}.
     *
     * <p>The caller guarantees that the samples are at most 31 bits wide.
     *
     * @param left the left channel
     * @param right the right channel
     * @param mid receives the mid channel
     * @param side receives the side channel
     * @param count the number of samples
     */
    void midSide(int[] left, int[] right, int[] mid, int[] side, int count);
}
