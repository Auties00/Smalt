package com.github.auties00.smalt.audio.voice.internal.aec3;

import static com.github.auties00.smalt.audio.voice.internal.aec3.Aec3.BLOCK_SIZE;
import static com.github.auties00.smalt.audio.voice.internal.aec3.Aec3.FFT_LENGTH_BY_2_PLUS_1;

/**
 * Holds the outputs of the two adaptive filters of the subtractor for one block, and their
 * energies.
 *
 * @implNote This implementation ports WebRTC's
 * {@code modules/audio_processing/aec3/subtractor_output.cc}.
 */
final class SubtractorOutput {
    /**
     * The echo estimate of the refined filter.
     */
    final float[] sRefined = new float[BLOCK_SIZE];

    /**
     * The echo estimate of the coarse filter.
     */
    final float[] sCoarse = new float[BLOCK_SIZE];

    /**
     * The error (capture minus echo estimate) of the refined filter.
     */
    final float[] eRefined = new float[BLOCK_SIZE];

    /**
     * The error of the coarse filter.
     */
    final float[] eCoarse = new float[BLOCK_SIZE];

    /**
     * The Hanning-windowed spectrum of the refined filter error.
     */
    final FftData eRefinedSpectrum = new FftData();

    /**
     * The power spectrum of the refined filter error.
     */
    final float[] e2RefinedSpectrum = new float[FFT_LENGTH_BY_2_PLUS_1];

    /**
     * The power spectrum of the coarse filter error.
     */
    final float[] e2CoarseSpectrum = new float[FFT_LENGTH_BY_2_PLUS_1];

    /**
     * The energy of the refined echo estimate.
     */
    float s2Refined;

    /**
     * The energy of the coarse echo estimate.
     */
    float s2Coarse;

    /**
     * The energy of the refined filter error.
     */
    float e2Refined;

    /**
     * The energy of the coarse filter error.
     */
    float e2Coarse;

    /**
     * The energy of the capture block.
     */
    float y2;

    /**
     * The largest magnitude of the refined echo estimate.
     */
    float sRefinedMaxAbs;

    /**
     * The largest magnitude of the coarse echo estimate.
     */
    float sCoarseMaxAbs;

    /**
     * Creates an output of zeros.
     */
    SubtractorOutput() {
    }

    /**
     * Computes the energies and the peak magnitudes of the block.
     *
     * @param y      the capture samples
     * @param offset the index of the first of the 64 capture samples
     */
    void computeMetrics(float[] y, int offset) {
        var sum = 0.f;
        for (var i = 0; i < BLOCK_SIZE; i++) {
            sum = sum + y[offset + i] * y[offset + i];
        }
        y2 = sum;
        e2Refined = sumOfSquares(eRefined);
        e2Coarse = sumOfSquares(eCoarse);
        s2Refined = sumOfSquares(sRefined);
        s2Coarse = sumOfSquares(sCoarse);
        sRefinedMaxAbs = maxAbs(sRefined);
        sCoarseMaxAbs = maxAbs(sCoarse);
    }

    /**
     * Returns the sum of the squares of a block, accumulated in order.
     *
     * @param v the block
     * @return the energy
     */
    private static float sumOfSquares(float[] v) {
        var sum = 0.f;
        for (var i = 0; i < BLOCK_SIZE; i++) {
            sum = sum + v[i] * v[i];
        }
        return sum;
    }

    /**
     * Returns {@code max(max(v), -min(v))} of a block, as the reference computes it.
     *
     * @param v the block
     * @return the largest magnitude
     */
    private static float maxAbs(float[] v) {
        var max = v[0];
        var min = v[0];
        for (var i = 1; i < BLOCK_SIZE; i++) {
            if (max < v[i]) {
                max = v[i];
            }
            if (v[i] < min) {
                min = v[i];
            }
        }
        var negatedMin = -min;
        return max < negatedMin ? negatedMin : max;
    }
}
