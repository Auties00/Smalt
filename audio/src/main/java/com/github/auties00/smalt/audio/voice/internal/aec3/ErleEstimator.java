/* Ports webrtc-audio-processing 2.1 (modules/audio_processing/aec3/erle_estimator.cc, subband_erle_estimator.cc,
   fullband_erle_estimator.cc). Copyright (c) 2011, The WebRTC project authors; BSD 3-Clause License. Modified for
   Smalt. */
package com.github.auties00.smalt.audio.voice.internal.aec3;

import com.github.auties00.smalt.audio.voice.internal.FloatMath;

import java.util.Arrays;

import static com.github.auties00.smalt.audio.voice.internal.aec3.Aec3.FFT_LENGTH_BY_2;
import static com.github.auties00.smalt.audio.voice.internal.aec3.Aec3.FFT_LENGTH_BY_2_PLUS_1;

/**
 * Estimates the echo return loss enhancement of the linear filter per bin (bounded, unbounded
 * and compensated for onsets), together with an instantaneous quality estimate of the linear
 * filter from the full-band powers.
 *
 * <p>The per-bin estimates are updated from power spectra accumulated over six blocks while the
 * filter is converged and the render signal is strong; the onset-compensated estimate decays
 * back towards the estimate measured at onsets once a bin's render energy drops.
 *
 * @implNote This implementation ports {@code ErleEstimator}, {@code SubbandErleEstimator} and
 * {@code FullBandErleEstimator} of WebRTC's {@code modules/audio_processing/aec3}
 * ({@code erle_estimator.cc}, {@code subband_erle_estimator.cc},
 * {@code fullband_erle_estimator.cc}) for one capture channel with the default
 * {@code erle} configuration: one section (so no signal-dependent estimator), onset detection,
 * and the quality clamped to {@code [0, 1]}. The smoothed full-band estimate, which only the
 * reference's metrics read, is omitted.
 */
final class ErleEstimator {
    /**
     * The render band energy below which a bin counts as low energy.
     */
    private static final float X2_BAND_ENERGY_THRESHOLD = 44015068.0f;

    /**
     * The number of blocks an estimate is held.
     */
    private static final int BLOCKS_TO_HOLD_ERLE = 100;

    /**
     * The number of blocks after an update during which a bin is not in an onset.
     */
    private static final int BLOCKS_FOR_ONSET_DETECTION = BLOCKS_TO_HOLD_ERLE + 150;

    /**
     * The number of blocks accumulated per update.
     */
    private static final int POINTS_TO_ACCUMULATE = 6;

    /**
     * The epsilon of the full-band ratio.
     */
    private static final float EPSILON = 1e-3f;

    /**
     * The number of blocks after a reset before the estimates are updated.
     */
    private static final int STARTUP_PHASE_LENGTH_BLOCKS = 2 * Aec3.NUM_BLOCKS_PER_SECOND;

    /**
     * The upper bound of each bin's estimate.
     */
    private final float[] maxErle = new float[FFT_LENGTH_BY_2_PLUS_1];

    /**
     * The accumulated capture power per bin.
     */
    private final float[] accumY2 = new float[FFT_LENGTH_BY_2_PLUS_1];

    /**
     * The accumulated linear filter error power per bin.
     */
    private final float[] accumE2 = new float[FFT_LENGTH_BY_2_PLUS_1];

    /**
     * Whether a bin's render power was low in an accumulated block.
     */
    private final boolean[] accumLowRenderEnergy = new boolean[FFT_LENGTH_BY_2_PLUS_1];

    /**
     * The number of accumulated blocks.
     */
    private int accumNumPoints;

    /**
     * The bounded per-bin estimate.
     */
    final float[] erle = new float[FFT_LENGTH_BY_2_PLUS_1];

    /**
     * The onset-compensated per-bin estimate.
     */
    final float[] erleOnsetCompensated = new float[FFT_LENGTH_BY_2_PLUS_1];

    /**
     * The unbounded per-bin estimate.
     */
    final float[] erleUnbounded = new float[FFT_LENGTH_BY_2_PLUS_1];

    /**
     * The per-bin estimate measured during onsets.
     */
    private final float[] erleDuringOnsets = new float[FFT_LENGTH_BY_2_PLUS_1];

    /**
     * Whether a bin awaits an onset.
     */
    private final boolean[] comingOnset = new boolean[FFT_LENGTH_BY_2_PLUS_1];

    /**
     * The per-bin onset hold counters.
     */
    private final int[] holdCounters = new int[FFT_LENGTH_BY_2_PLUS_1];

    /**
     * The scratch buffer of the new per-bin estimates.
     */
    private final float[] newErle = new float[FFT_LENGTH_BY_2];

    /**
     * Whether a bin's new estimate was computed.
     */
    private final boolean[] isErleUpdated = new boolean[FFT_LENGTH_BY_2];

    /**
     * The full-band hold counter.
     */
    private int holdCounterInstantaneousErle;

    /**
     * Whether the instantaneous full-band estimate holds a value.
     */
    private boolean hasInstErleLog2;

    /**
     * The instantaneous full-band estimate, as a base-2 logarithm.
     */
    private float instErleLog2;

    /**
     * The instantaneous quality of the linear filter.
     */
    private float instQualityEstimate;

    /**
     * The slowly decaying maximum of the instantaneous estimate.
     */
    private float instMaxErleLog2;

    /**
     * The slowly growing minimum of the instantaneous estimate.
     */
    private float instMinErleLog2;

    /**
     * The accumulated full-band capture power.
     */
    private float instY2Acum;

    /**
     * The accumulated full-band error power.
     */
    private float instE2Acum;

    /**
     * The number of accumulated full-band blocks.
     */
    private int instNumPoints;

    /**
     * Whether the linear filter quality estimate holds a value.
     */
    private boolean hasLinearFilterQuality;

    /**
     * The linear filter quality estimate, clamped to {@code [0, 1]}.
     */
    private float linearFilterQuality;

    /**
     * The number of updates since the last delay change.
     */
    private long blocksSinceReset;

    /**
     * Creates an estimator at the lower bounds.
     */
    ErleEstimator() {
        Arrays.fill(maxErle, 0, FFT_LENGTH_BY_2 / 2, Aec3.ERLE_MAX_L);
        Arrays.fill(maxErle, FFT_LENGTH_BY_2 / 2, FFT_LENGTH_BY_2_PLUS_1, Aec3.ERLE_MAX_H);
        reset(true);
    }

    /**
     * Resets the estimates, and on a delay change also restarts the start-up phase.
     *
     * @param delayChange whether the reset follows a delay change
     */
    void reset(boolean delayChange) {
        resetFullband();
        resetSubband();
        if (delayChange) {
            blocksSinceReset = 0;
        }
    }

    /**
     * Updates the estimates with one block.
     *
     * @param x2Reverb the render power with reverberation at the echo path delay
     * @param y2 the capture power spectrum
     * @param e2 the linear filter error power spectrum
     * @param converged whether the filter has converged
     */
    void update(float[] x2Reverb, float[] y2, float[] e2, boolean converged) {
        if (++blocksSinceReset < STARTUP_PHASE_LENGTH_BLOCKS) {
            return;
        }
        updateSubband(x2Reverb, y2, e2, converged);
        updateFullband(x2Reverb, y2, e2, converged);
    }

    /**
     * Returns the per-bin estimate.
     *
     * @param onsetCompensated whether the onset-compensated estimate is returned
     * @return the live array of 65 estimates
     */
    float[] erle(boolean onsetCompensated) {
        return onsetCompensated ? erleOnsetCompensated : erle;
    }

    /**
     * Returns whether the linear filter quality estimate holds a value.
     *
     * @return whether {@link #linearFilterQuality()} is valid
     */
    boolean hasLinearFilterQuality() {
        return hasLinearFilterQuality;
    }

    /**
     * Returns the linear filter quality estimate.
     *
     * @return the quality in {@code [0, 1]}
     */
    float linearFilterQuality() {
        return linearFilterQuality;
    }

    /**
     * Resets the per-bin estimates to the lower bound.
     */
    private void resetSubband() {
        Arrays.fill(erle, Aec3.ERLE_MIN);
        Arrays.fill(erleOnsetCompensated, Aec3.ERLE_MIN);
        Arrays.fill(erleUnbounded, Aec3.ERLE_MIN);
        Arrays.fill(erleDuringOnsets, Aec3.ERLE_MIN);
        Arrays.fill(comingOnset, true);
        Arrays.fill(holdCounters, 0);
        Arrays.fill(accumY2, 0.f);
        Arrays.fill(accumE2, 0.f);
        accumNumPoints = 0;
        Arrays.fill(accumLowRenderEnergy, false);
    }

    /**
     * Updates the per-bin estimates.
     *
     * @param x2 the render power with reverberation
     * @param y2 the capture power spectrum
     * @param e2 the linear filter error power spectrum
     * @param converged whether the filter has converged
     */
    private void updateSubband(float[] x2, float[] y2, float[] e2, boolean converged) {
        if (converged) {
            if (accumNumPoints == POINTS_TO_ACCUMULATE) {
                accumNumPoints = 0;
                Arrays.fill(accumY2, 0.f);
                Arrays.fill(accumE2, 0.f);
                Arrays.fill(accumLowRenderEnergy, false);
            }
            for (var k = 0; k < FFT_LENGTH_BY_2_PLUS_1; k++) {
                accumY2[k] = y2[k] + accumY2[k];
            }
            for (var k = 0; k < FFT_LENGTH_BY_2_PLUS_1; k++) {
                accumE2[k] = e2[k] + accumE2[k];
            }
            for (var k = 0; k < FFT_LENGTH_BY_2_PLUS_1; ++k) {
                accumLowRenderEnergy[k] = accumLowRenderEnergy[k] || x2[k] < X2_BAND_ENERGY_THRESHOLD;
            }
            ++accumNumPoints;
        }
        updateBands(converged);
        decreaseErlePerBandForLowRenderSignals();
        erle[0] = erle[1];
        erle[FFT_LENGTH_BY_2] = erle[FFT_LENGTH_BY_2 - 1];
        erleOnsetCompensated[0] = erleOnsetCompensated[1];
        erleOnsetCompensated[FFT_LENGTH_BY_2] = erleOnsetCompensated[FFT_LENGTH_BY_2 - 1];
        erleUnbounded[0] = erleUnbounded[1];
        erleUnbounded[FFT_LENGTH_BY_2] = erleUnbounded[FFT_LENGTH_BY_2 - 1];
    }

    /**
     * Updates the per-bin estimates from a complete accumulation.
     *
     * @param converged whether the filter has converged
     */
    private void updateBands(boolean converged) {
        if (!converged || accumNumPoints != POINTS_TO_ACCUMULATE) {
            return;
        }
        Arrays.fill(isErleUpdated, false);
        for (var k = 1; k < FFT_LENGTH_BY_2; ++k) {
            if (accumE2[k] > 0.f) {
                newErle[k] = accumY2[k] / accumE2[k];
                isErleUpdated[k] = true;
            }
        }
        for (var k = 1; k < FFT_LENGTH_BY_2; ++k) {
            if (isErleUpdated[k] && !accumLowRenderEnergy[k]) {
                if (comingOnset[k]) {
                    comingOnset[k] = false;
                }
                holdCounters[k] = BLOCKS_FOR_ONSET_DETECTION;
            }
        }
        for (var k = 1; k < FFT_LENGTH_BY_2; ++k) {
            if (isErleUpdated[k]) {
                var lowRenderEnergy = accumLowRenderEnergy[k];
                erle[k] = updateErleBand(erle[k], newErle[k], lowRenderEnergy, Aec3.ERLE_MIN, maxErle[k]);
                erleOnsetCompensated[k] = updateErleBand(erleOnsetCompensated[k], newErle[k], lowRenderEnergy,
                        Aec3.ERLE_MIN, maxErle[k]);
                erleUnbounded[k] = updateErleBand(erleUnbounded[k], newErle[k], lowRenderEnergy, Aec3.ERLE_MIN,
                        100000.0f);
            }
        }
    }

    /**
     * Smooths one bin's estimate towards a new value: quickly upwards, more quickly downwards,
     * and not downwards for low render energy.
     *
     * @param erle the current estimate
     * @param newErle the new value
     * @param lowRenderEnergy whether the render energy of the bin was low
     * @param minErle the lower bound
     * @param maxErle the upper bound
     * @return the updated estimate
     */
    private static float updateErleBand(float erle, float newErle, boolean lowRenderEnergy, float minErle, float maxErle) {
        var alpha = 0.05f;
        if (newErle < erle) {
            alpha = lowRenderEnergy ? 0.f : 0.1f;
        }
        var value = erle + alpha * (newErle - erle);
        return value <= minErle ? minErle : (value >= maxErle ? maxErle : value);
    }

    /**
     * Decays the onset-compensated estimates of bins whose hold has expired towards the estimate
     * measured during onsets.
     */
    private void decreaseErlePerBandForLowRenderSignals() {
        for (var k = 1; k < FFT_LENGTH_BY_2; ++k) {
            --holdCounters[k];
            if (holdCounters[k] <= (BLOCKS_FOR_ONSET_DETECTION - BLOCKS_TO_HOLD_ERLE)) {
                if (erleOnsetCompensated[k] > erleDuringOnsets[k]) {
                    erleOnsetCompensated[k] = FloatMath.max(erleDuringOnsets[k], 0.97f * erleOnsetCompensated[k]);
                }
                if (holdCounters[k] <= 0) {
                    comingOnset[k] = true;
                    holdCounters[k] = 0;
                }
            }
        }
    }

    /**
     * Resets the full-band estimate.
     */
    private void resetFullband() {
        resetInstantaneousAccumulators();
        instMaxErleLog2 = -10.f;
        instMinErleLog2 = 33.f;
        instQualityEstimate = 0.f;
        updateQualityEstimate();
        holdCounterInstantaneousErle = 0;
    }

    /**
     * Clears the full-band accumulators and the instantaneous estimate.
     */
    private void resetInstantaneousAccumulators() {
        hasInstErleLog2 = false;
        instQualityEstimate = 0.f;
        instNumPoints = 0;
        instE2Acum = 0.f;
        instY2Acum = 0.f;
    }

    /**
     * Updates the full-band estimate.
     *
     * @param x2 the render power with reverberation
     * @param y2 the capture power spectrum
     * @param e2 the linear filter error power spectrum
     * @param converged whether the filter has converged
     */
    private void updateFullband(float[] x2, float[] y2, float[] e2, boolean converged) {
        if (converged) {
            var x2Sum = 0.0f;
            for (var k = 0; k < FFT_LENGTH_BY_2_PLUS_1; k++) {
                x2Sum = x2Sum + x2[k];
            }
            if (x2Sum > X2_BAND_ENERGY_THRESHOLD * FFT_LENGTH_BY_2_PLUS_1) {
                var y2Sum = 0.0f;
                for (var k = 0; k < FFT_LENGTH_BY_2_PLUS_1; k++) {
                    y2Sum = y2Sum + y2[k];
                }
                var e2Sum = 0.0f;
                for (var k = 0; k < FFT_LENGTH_BY_2_PLUS_1; k++) {
                    e2Sum = e2Sum + e2[k];
                }
                if (updateInstantaneous(y2Sum, e2Sum)) {
                    holdCounterInstantaneousErle = BLOCKS_TO_HOLD_ERLE;
                }
            }
        }
        --holdCounterInstantaneousErle;
        if (holdCounterInstantaneousErle == 0) {
            resetInstantaneousAccumulators();
        }
        updateQualityEstimate();
    }

    /**
     * Accumulates the full-band powers and, every six blocks, computes the instantaneous
     * estimate and its quality.
     *
     * @param y2Sum the capture power
     * @param e2Sum the error power
     * @return whether the instantaneous estimate was updated
     */
    private boolean updateInstantaneous(float y2Sum, float e2Sum) {
        var updateEstimates = false;
        instE2Acum += e2Sum;
        instY2Acum += y2Sum;
        instNumPoints++;
        if (instNumPoints == POINTS_TO_ACCUMULATE) {
            if (instE2Acum > 0.f) {
                updateEstimates = true;
                instErleLog2 = Aec3.fastApproxLog2f(instY2Acum / instE2Acum + EPSILON);
                hasInstErleLog2 = true;
            }
            instNumPoints = 0;
            instE2Acum = 0.f;
            instY2Acum = 0.f;
        }
        if (updateEstimates) {
            instMaxErleLog2 -= 0.0004f;
            instMaxErleLog2 = FloatMath.max(instMaxErleLog2, instErleLog2);
            instMinErleLog2 += 0.0004f;
            instMinErleLog2 = FloatMath.min(instMinErleLog2, instErleLog2);
            var alpha = 0.07f;
            var qualityEstimate = 0.f;
            if (instMaxErleLog2 > instMinErleLog2) {
                qualityEstimate = (instErleLog2 - instMinErleLog2) / (instMaxErleLog2 - instMinErleLog2);
            }
            if (qualityEstimate > instQualityEstimate) {
                instQualityEstimate = qualityEstimate;
            } else {
                instQualityEstimate += alpha * (qualityEstimate - instQualityEstimate);
            }
        }
        return updateEstimates;
    }

    /**
     * Publishes the clamped instantaneous quality as the linear filter quality.
     */
    private void updateQualityEstimate() {
        if (hasInstErleLog2) {
            var value = FloatMath.max(0.f, instQualityEstimate);
            value = FloatMath.min(1.f, value);
            linearFilterQuality = value;
            hasLinearFilterQuality = true;
        } else {
            hasLinearFilterQuality = false;
        }
    }
}
