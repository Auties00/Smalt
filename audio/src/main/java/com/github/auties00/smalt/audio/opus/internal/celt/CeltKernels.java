package com.github.auties00.smalt.audio.opus.internal.celt;

import com.github.auties00.smalt.internal.simd.Simd;

/**
 * The hot DSP kernels of CELT and of the float analysis code that shares them.
 *
 * <p>Implementations must return the results of {@link ScalarCeltKernels}, the reference, bit for bit, so the
 * encoder's bitstream does not depend on which one runs.
 */
public interface CeltKernels {
    /**
     * The implementation selected for this JVM: the vector implementation when
     * {@link Simd#ENABLED}, the scalar one otherwise.
     */
    CeltKernels INSTANCE = Simd.ENABLED ? VectorCeltKernels.create() : new ScalarCeltKernels();

    /**
     * Computes the cross-correlation of {@code x} with {@code maxPitch} shifted copies of
     * {@code y} ({@code celt_pitch_xcorr}).
     *
     * <p>{@code xcorr[i] = sum(j = 0 .. len - 1) x[j] * y[i + j]} for {@code i} in
     * {@code [0, maxPitch)}, each sum accumulated in increasing {@code j} with one rounding per
     * multiply and one per add.
     *
     * @param x        the reference signal
     * @param xOff     the index of the first sample of {@code x}
     * @param y        the shifted signal, holding at least {@code len + maxPitch - 1} samples
     * @param yOff     the index of the first sample of {@code y}
     * @param xcorr    receives the correlations
     * @param xcorrOff the index of the first correlation
     * @param len      the correlation length
     * @param maxPitch the number of lags
     */
    void pitchXcorr(float[] x, int xOff, float[] y, int yOff, float[] xcorr, int xcorrOff, int len, int maxPitch);

    /**
     * Applies the constant part of the pitch comb filter ({@code comb_filter_const_c}).
     *
     * <p>{@code y[i] = x[i] + g10 * x[i-T] + g11 * (x[i-T+1] + x[i-T-1]) + g12 * (x[i-T+2] + x[i-T-2])}
     * evaluated left to right. When {@code y} and {@code x} are the same region the filter is
     * recursive: each output sample reads earlier output samples, exactly as the sequential
     * reference does.
     *
     * @param y    the output
     * @param yOff the index of the first output sample
     * @param x    the input, with {@code T + 2} samples of history before {@code xOff}
     * @param xOff the index of the first input sample
     * @param T    the pitch period, at least 15
     * @param N    the number of samples
     * @param g10  the gain of the center tap
     * @param g11  the gain of the taps at distance one
     * @param g12  the gain of the taps at distance two
     */
    void combFilterConst(float[] y, int yOff, float[] x, int xOff, int T, int N, float g10, float g11, float g12);

    /**
     * Runs the butterfly stages of an FFT in place on bit-reversed data stored as split real and
     * imaginary arrays (the split-layout {@code opus_fft_impl}).
     *
     * @param st the FFT configuration
     * @param a  the array holding both halves
     * @param re the index of the first real part
     * @param im the index of the first imaginary part
     */
    void fftSoa(KissFftState st, float[] a, int re, int im);

    /**
     * Returns the position where adding one pulse maximizes the normalized correlation of the
     * PVQ search ({@code op_pvq_search_c}'s inner loop).
     *
     * <p>Candidate {@code j} scores {@code num = (xy + X[j])^2} over {@code den = yy + y[j]}; the
     * result is the position a sequential scan from 0 keeps, replacing the best {@code b} by
     * {@code j} when {@code den[b] * num[j] > den[j] * num[b]} in float arithmetic.
     *
     * @param X    the absolute target shape
     * @param xOff the index of the first element of {@code X}
     * @param y    the doubled pulse magnitudes
     * @param N    the vector length, at least 2
     * @param xy   the current correlation
     * @param yy   the current energy, already incremented by one
     * @return the best position
     */
    int pvqSearchBest(float[] X, int xOff, float[] y, int N, float xy, float yy);

    /**
     * Returns the largest absolute value of a run of samples ({@code celt_maxabs16}).
     *
     * <p>The magnitude equals the reference's for every input; for all-zero input the sign of the
     * zero may differ, which no caller can observe because the result only feeds comparisons.
     *
     * @param x   the samples
     * @param off the index of the first sample
     * @param len the number of samples
     * @return the maximum absolute value, zero for an empty run
     */
    float maxabs(float[] x, int off, int len);

    /**
     * Applies one pass of Givens rotations between samples {@code stride} apart, forward then
     * backward ({@code exp_rotation1}).
     *
     * <p>Each pass is a recursion with distance {@code stride}: every rotation reads the result of
     * the rotation {@code stride} positions earlier (later, in the backward sweep).
     *
     * @param X      the vector
     * @param xOff   the index of the first sample
     * @param len    the vector length
     * @param stride the rotation distance
     * @param c      the rotation cosine
     * @param s      the rotation sine
     */
    void expRotation1(float[] X, int xOff, int len, int stride, float c, float s);

    /**
     * Applies one level of the Haar transform between neighboring blocks ({@code haar1}).
     *
     * @param X      the band
     * @param xOff   the index of its first sample
     * @param N0     the band size
     * @param stride the block interleaving stride
     */
    void haar1(float[] X, int xOff, int N0, int stride);
}
