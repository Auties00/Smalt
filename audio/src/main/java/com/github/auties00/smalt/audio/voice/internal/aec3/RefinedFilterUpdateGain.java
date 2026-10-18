package com.github.auties00.smalt.audio.voice.internal.aec3;

import com.github.auties00.smalt.audio.voice.internal.FloatMath;

import java.util.Arrays;

import static com.github.auties00.smalt.audio.voice.internal.aec3.Aec3.FFT_LENGTH_BY_2_PLUS_1;

/**
 * Computes the NLMS update gain of the refined adaptive filter, normalized by a per-bin estimate
 * of the filter error that leaks back up over time.
 *
 * <p>The tuning (leakage, error floor and ceiling, noise gate) moves gradually from the initial
 * to the final configuration when the canceller leaves its initial state.
 *
 * @implNote This implementation ports WebRTC's
 * {@code modules/audio_processing/aec3/refined_filter_update_gain.cc} with the default
 * {@code filter.refined_initial} and {@code filter.refined} tunings.
 */
final class RefinedFilterUpdateGain {
    /**
     * The initial filter error estimate.
     */
    private static final float H_ERROR_INITIAL = 10000.f;

    /**
     * The initial number of blocks since poor render excitation.
     */
    private static final int POOR_EXCITATION_COUNTER_INITIAL = 1000;

    /**
     * {@code filter.refined}: leakage converged, leakage diverged, error floor, error ceiling,
     * noise gate.
     */
    static final float[] REFINED = {0.00005f, 0.05f, 0.001f, 2.f, 20075344.f};

    /**
     * {@code filter.refined_initial}: leakage converged, leakage diverged, error floor, error
     * ceiling, noise gate.
     */
    static final float[] REFINED_INITIAL = {0.005f, 0.5f, 0.001f, 2.f, 20075344.f};

    /**
     * The current tuning: leakage converged, leakage diverged, error floor, error ceiling, noise
     * gate.
     */
    private final float[] current = new float[5];

    /**
     * The tuning the current one moves towards.
     */
    private final float[] target = new float[5];

    /**
     * The tuning the current one moves from.
     */
    private final float[] oldTarget = new float[5];

    /**
     * {@code 1 / CONFIG_CHANGE_DURATION_BLOCKS}.
     */
    private final float oneByConfigChangeDurationBlocks = 1.f / Aec3.CONFIG_CHANGE_DURATION_BLOCKS;

    /**
     * The per-bin filter error estimate.
     */
    private final float[] hError = new float[FFT_LENGTH_BY_2_PLUS_1];

    /**
     * The number of blocks since the render excitation was poor.
     */
    private long poorExcitationCounter = POOR_EXCITATION_COUNTER_INITIAL;

    /**
     * The number of gain computations since the last echo path change.
     */
    private long callCounter;

    /**
     * The number of blocks left of the gradual tuning change.
     */
    private int configChangeCounter;

    /**
     * The scratch buffer of the step sizes.
     */
    private final float[] mu = new float[FFT_LENGTH_BY_2_PLUS_1];

    /**
     * Creates a gain with the initial tuning.
     */
    RefinedFilterUpdateGain() {
        setConfig(REFINED_INITIAL, true);
        Arrays.fill(hError, H_ERROR_INITIAL);
    }

    /**
     * Resets the error estimate on a delay change and the excitation counters on any non-gain
     * change.
     *
     * @param gainChange  whether the echo path gain changed
     * @param delayChange whether the echo path delay changed
     */
    void handleEchoPathChange(boolean gainChange, boolean delayChange) {
        if (delayChange) {
            Arrays.fill(hError, H_ERROR_INITIAL);
        }
        if (!gainChange) {
            poorExcitationCounter = POOR_EXCITATION_COUNTER_INITIAL;
            callCounter = 0;
        }
    }

    /**
     * Sets the tuning, at once or gradually over the configuration change duration.
     *
     * @param config          the tuning: leakage converged, leakage diverged, error floor,
     *                        error ceiling, noise gate
     * @param immediateEffect whether the tuning changes at once
     */
    void setConfig(float[] config, boolean immediateEffect) {
        if (immediateEffect) {
            System.arraycopy(config, 0, oldTarget, 0, 5);
            System.arraycopy(config, 0, current, 0, 5);
            System.arraycopy(config, 0, target, 0, 5);
            configChangeCounter = 0;
        } else {
            System.arraycopy(current, 0, oldTarget, 0, 5);
            System.arraycopy(config, 0, target, 0, 5);
            configChangeCounter = Aec3.CONFIG_CHANGE_DURATION_BLOCKS;
        }
    }

    /**
     * Computes the update gain.
     *
     * @param renderPower            the render power summed over the filter partitions
     * @param renderSignalAnalyzer   the narrow-band detector
     * @param output                 the subtractor output
     * @param erl                    the echo return loss estimate of the filter
     * @param sizePartitions         the current number of filter partitions
     * @param saturatedCaptureSignal whether the capture signal is saturated
     * @param disallowLeakageDiverged whether the converged leakage is forced
     * @param g                      receives the gain
     */
    void compute(float[] renderPower, RenderSignalAnalyzer renderSignalAnalyzer, SubtractorOutput output, float[] erl,
                 int sizePartitions, boolean saturatedCaptureSignal, boolean disallowLeakageDiverged, FftData g) {
        var eRefined = output.eRefinedSpectrum;
        var e2Refined = output.e2RefinedSpectrum;
        var e2Coarse = output.e2CoarseSpectrum;
        var x2 = renderPower;
        ++callCounter;
        updateCurrentConfig();
        if (renderSignalAnalyzer.poorSignalExcitation()) {
            poorExcitationCounter = 0;
        }
        if (++poorExcitationCounter < sizePartitions || saturatedCaptureSignal || callCounter <= sizePartitions) {
            Arrays.fill(g.re, 0.f);
            Arrays.fill(g.im, 0.f);
        } else {
            var noiseGate = current[4];
            for (var k = 0; k < FFT_LENGTH_BY_2_PLUS_1; ++k) {
                if (x2[k] >= noiseGate) {
                    mu[k] = hError[k] / (0.5f * hError[k] * x2[k] + sizePartitions * e2Refined[k]);
                } else {
                    mu[k] = 0.f;
                }
            }
            renderSignalAnalyzer.maskRegionsAroundNarrowBands(mu);
            for (var k = 0; k < FFT_LENGTH_BY_2_PLUS_1; ++k) {
                hError[k] -= 0.5f * mu[k] * x2[k] * hError[k];
            }
            for (var k = 0; k < FFT_LENGTH_BY_2_PLUS_1; ++k) {
                g.re[k] = mu[k] * eRefined.re[k];
                g.im[k] = mu[k] * eRefined.im[k];
            }
        }
        var leakageConverged = current[0];
        var leakageDiverged = current[1];
        var errorFloor = current[2];
        var errorCeil = current[3];
        for (var k = 0; k < FFT_LENGTH_BY_2_PLUS_1; ++k) {
            if (e2Refined[k] <= e2Coarse[k] || disallowLeakageDiverged) {
                hError[k] += leakageConverged * erl[k];
            } else {
                hError[k] += leakageDiverged * erl[k];
            }
            hError[k] = FloatMath.max(hError[k], errorFloor);
            hError[k] = FloatMath.min(hError[k], errorCeil);
        }
    }

    /**
     * Moves the current tuning one step towards its target.
     */
    private void updateCurrentConfig() {
        if (configChangeCounter > 0) {
            if (--configChangeCounter > 0) {
                var changeFactor = configChangeCounter * oneByConfigChangeDurationBlocks;
                for (var i = 0; i < 5; i++) {
                    current[i] = oldTarget[i] * changeFactor + target[i] * (1.f - changeFactor);
                }
            } else {
                System.arraycopy(target, 0, current, 0, 5);
                System.arraycopy(target, 0, oldTarget, 0, 5);
            }
        }
    }
}
