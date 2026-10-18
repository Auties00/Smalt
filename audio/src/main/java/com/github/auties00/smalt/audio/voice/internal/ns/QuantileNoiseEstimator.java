package com.github.auties00.smalt.audio.voice.internal.ns;

import static com.github.auties00.smalt.audio.voice.internal.ns.NsConstants.BINS;
import static com.github.auties00.smalt.audio.voice.internal.ns.NsConstants.LONG_STARTUP_PHASE_BLOCKS;

/**
 * Estimates the noise spectrum as a running quantile of the log magnitude spectrum.
 *
 * <p>Three staggered estimators track the quantile of each bin's log magnitude, each restarting
 * every {@value NsConstants#LONG_STARTUP_PHASE_BLOCKS} frames; the estimate of the one that
 * restarts is published, and during start-up the last estimator is published every frame.
 *
 * @implNote This implementation ports WebRTC's
 * {@code modules/audio_processing/ns/quantile_noise_estimator.cc}.
 */
final class QuantileNoiseEstimator {
    /**
     * The number of staggered estimators.
     */
    private static final int SIMULTANEOUS = 3;

    /**
     * The density estimates of every estimator, {@value #SIMULTANEOUS} runs of
     * {@link NsConstants#BINS} bins.
     */
    private final float[] density = new float[SIMULTANEOUS * BINS];

    /**
     * The log quantile estimates of every estimator, laid out like {@link #density}.
     */
    private final float[] logQuantile = new float[SIMULTANEOUS * BINS];

    /**
     * The last published noise spectrum.
     */
    private final float[] quantile = new float[BINS];

    /**
     * The per-estimator frame counters.
     */
    private final int[] counter = new int[SIMULTANEOUS];

    /**
     * The scratch buffer holding the log spectrum of the current frame.
     */
    private final float[] logSpectrum = new float[BINS];

    /**
     * The number of frames estimated so far, saturating at the long start-up length.
     */
    private int numUpdates = 1;

    /**
     * Creates an estimator in its initial state.
     */
    QuantileNoiseEstimator() {
        java.util.Arrays.fill(density, 0.3f);
        java.util.Arrays.fill(logQuantile, 8.f);
        var oneBySimult = 1.f / SIMULTANEOUS;
        for (var i = 0; i < SIMULTANEOUS; ++i) {
            counter[i] = (int) (float) Math.floor(LONG_STARTUP_PHASE_BLOCKS * (i + 1.f) * oneBySimult);
        }
    }

    /**
     * Updates the estimators with a magnitude spectrum and returns the noise estimate.
     *
     * @param signalSpectrum the magnitude spectrum of the frame, {@link NsConstants#BINS} bins
     * @param noiseSpectrum  receives the noise spectrum estimate, {@link NsConstants#BINS} bins
     */
    void estimate(float[] signalSpectrum, float[] noiseSpectrum) {
        var logSpectrum = this.logSpectrum;
        for (var k = 0; k < BINS; ++k) {
            logSpectrum[k] = NsConstants.logApproximation(signalSpectrum[k]);
        }
        var quantileIndexToReturn = -1;
        for (int s = 0, k = 0; s < SIMULTANEOUS; ++s, k += BINS) {
            var oneByCounterPlus1 = 1.f / (counter[s] + 1.f);
            for (int i = 0, j = k; i < BINS; ++i, ++j) {
                var delta = density[j] > 1.f ? 40.f / density[j] : 40.f;
                var multiplier = delta * oneByCounterPlus1;
                if (logSpectrum[i] > logQuantile[j]) {
                    logQuantile[j] += 0.25f * multiplier;
                } else {
                    logQuantile[j] -= 0.75f * multiplier;
                }
                var width = 0.01f;
                var oneByWidthPlus2 = 1.f / (2.f * width);
                if (Math.abs(logSpectrum[i] - logQuantile[j]) < width) {
                    density[j] = (counter[s] * density[j] + oneByWidthPlus2) * oneByCounterPlus1;
                }
            }
            if (counter[s] >= LONG_STARTUP_PHASE_BLOCKS) {
                counter[s] = 0;
                if (numUpdates >= LONG_STARTUP_PHASE_BLOCKS) {
                    quantileIndexToReturn = k;
                }
            }
            ++counter[s];
        }
        if (numUpdates < LONG_STARTUP_PHASE_BLOCKS) {
            quantileIndexToReturn = BINS * (SIMULTANEOUS - 1);
            ++numUpdates;
        }
        if (quantileIndexToReturn >= 0) {
            for (var i = 0; i < BINS; ++i) {
                quantile[i] = NsConstants.expApproximation(logQuantile[quantileIndexToReturn + i]);
            }
        }
        System.arraycopy(quantile, 0, noiseSpectrum, 0, BINS);
    }
}
