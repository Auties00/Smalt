package com.github.auties00.smalt.audio.opus.internal;

import com.github.auties00.smalt.internal.simd.Simd;

/**
 * The hot kernel of the tonality analysis: the per-bin phase tracking that measures tonality and noisiness.
 *
 * <p>Implementations must return the results of {@link ScalarAnalysisKernels}, the reference, bit for bit.
 */
interface AnalysisKernels {
    /**
     * The implementation selected for this JVM: the vector implementation when
     * {@link Simd#ENABLED}, the scalar one otherwise.
     */
    AnalysisKernels INSTANCE = Simd.ENABLED ? VectorAnalysisKernels.create() : new ScalarAnalysisKernels();

    /**
     * The scale from the approximate angle in radians to turns, {@code (float) (0.5 / pi)}.
     */
    float TURNS = (float) (.5f / Math.PI);

    /**
     * The weight of the phase acceleration in the tonality, {@code 40 * 16 * pi^4}.
     */
    float TONALITY_WEIGHT = 40.f * 16.f * (float) (Math.PI * Math.PI * Math.PI * Math.PI);

    /**
     * Updates the phase trackers of bins {@code 1..239} and computes their tonality and
     * noisiness (the first loop of {@code tonality_analysis}).
     *
     * <p>{@code re[i]}/{@code im[i]} hold FFT bin {@code i} and {@code reRev[i]}/{@code imRev[i]}
     * bin {@code 480 - i} of the two real signals packed in one complex FFT.
     *
     * @param re        the real parts of bins {@code i}
     * @param im        the imaginary parts of bins {@code i}
     * @param reRev     the real parts of bins {@code 480 - i}
     * @param imRev     the imaginary parts of bins {@code 480 - i}
     * @param A         the phase of each bin, updated
     * @param dA        the phase increment of each bin, updated
     * @param d2A       the fourth power of the wrapped phase acceleration, updated
     * @param noisiness receives the noisiness of each bin
     * @param tonality  receives the delayed tonality of each bin
     * @param tonality2 receives the immediate tonality of each bin
     */
    void tonalityBins(float[] re, float[] im, float[] reRev, float[] imRev, float[] A, float[] dA, float[] d2A,
                      float[] noisiness, float[] tonality, float[] tonality2);
}
