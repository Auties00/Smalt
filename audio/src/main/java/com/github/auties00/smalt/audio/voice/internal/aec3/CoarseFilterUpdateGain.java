package com.github.auties00.smalt.audio.voice.internal.aec3;

import java.util.Arrays;

import static com.github.auties00.smalt.audio.voice.internal.aec3.Aec3.FFT_LENGTH_BY_2_PLUS_1;

/**
 * Computes the NLMS update gain of the coarse adaptive filter: a fixed rate divided by the render
 * power, above a noise gate.
 *
 * <p>The tuning (rate and noise gate) moves gradually from the initial to the final
 * configuration when the canceller leaves its initial state.
 *
 * @implNote This implementation ports WebRTC's
 * {@code modules/audio_processing/aec3/coarse_filter_update_gain.cc}; the tunings come from an
 * {@link Aec3Config}.
 */
final class CoarseFilterUpdateGain {
    /**
     * The current tuning: rate, noise gate.
     */
    private final float[] current = new float[2];

    /**
     * The tuning the current one moves towards.
     */
    private final float[] target = new float[2];

    /**
     * The tuning the current one moves from.
     */
    private final float[] oldTarget = new float[2];

    /**
     * {@code 1 / CONFIG_CHANGE_DURATION_BLOCKS}.
     */
    private final float oneByConfigChangeDurationBlocks = 1.f / Aec3.CONFIG_CHANGE_DURATION_BLOCKS;

    /**
     * The number of blocks since the render excitation was poor.
     */
    private long poorSignalExcitationCounter;

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
     *
     * @param initial the initial tuning: rate, noise gate
     */
    CoarseFilterUpdateGain(float[] initial) {
        setConfig(initial, true);
    }

    /**
     * Resets the excitation and call counters.
     */
    void handleEchoPathChange() {
        poorSignalExcitationCounter = 0;
        callCounter = 0;
    }

    /**
     * Sets the tuning, at once or gradually over the configuration change duration.
     *
     * @param config          the tuning: rate, noise gate
     * @param immediateEffect whether the tuning changes at once
     */
    void setConfig(float[] config, boolean immediateEffect) {
        if (immediateEffect) {
            System.arraycopy(config, 0, oldTarget, 0, 2);
            System.arraycopy(config, 0, current, 0, 2);
            System.arraycopy(config, 0, target, 0, 2);
            configChangeCounter = 0;
        } else {
            System.arraycopy(current, 0, oldTarget, 0, 2);
            System.arraycopy(config, 0, target, 0, 2);
            configChangeCounter = Aec3.CONFIG_CHANGE_DURATION_BLOCKS;
        }
    }

    /**
     * Computes the update gain.
     *
     * @param renderPower            the render power summed over the filter partitions
     * @param renderSignalAnalyzer   the narrow-band detector
     * @param eCoarse                the error spectrum to adapt on
     * @param sizePartitions         the current number of filter partitions
     * @param saturatedCaptureSignal whether the capture signal is saturated
     * @param g                      receives the gain
     */
    void compute(float[] renderPower, RenderSignalAnalyzer renderSignalAnalyzer, FftData eCoarse, int sizePartitions,
                 boolean saturatedCaptureSignal, FftData g) {
        ++callCounter;
        updateCurrentConfig();
        if (renderSignalAnalyzer.poorSignalExcitation()) {
            poorSignalExcitationCounter = 0;
        }
        if (++poorSignalExcitationCounter < sizePartitions || saturatedCaptureSignal || callCounter <= sizePartitions) {
            Arrays.fill(g.re, 0.f);
            Arrays.fill(g.im, 0.f);
            return;
        }
        var x2 = renderPower;
        var rate = current[0];
        var noiseGate = current[1];
        for (var k = 0; k < FFT_LENGTH_BY_2_PLUS_1; ++k) {
            if (x2[k] > noiseGate) {
                mu[k] = rate / x2[k];
            } else {
                mu[k] = 0.f;
            }
        }
        renderSignalAnalyzer.maskRegionsAroundNarrowBands(mu);
        for (var k = 0; k < FFT_LENGTH_BY_2_PLUS_1; ++k) {
            g.re[k] = mu[k] * eCoarse.re[k];
            g.im[k] = mu[k] * eCoarse.im[k];
        }
    }

    /**
     * Moves the current tuning one step towards its target.
     */
    private void updateCurrentConfig() {
        if (configChangeCounter > 0) {
            if (--configChangeCounter > 0) {
                var changeFactor = configChangeCounter * oneByConfigChangeDurationBlocks;
                for (var i = 0; i < 2; i++) {
                    current[i] = oldTarget[i] * changeFactor + target[i] * (1.f - changeFactor);
                }
            } else {
                System.arraycopy(target, 0, current, 0, 2);
                System.arraycopy(target, 0, oldTarget, 0, 2);
            }
        }
    }
}
