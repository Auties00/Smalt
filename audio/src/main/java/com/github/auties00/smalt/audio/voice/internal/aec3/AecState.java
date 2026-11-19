/* Ports webrtc-audio-processing 2.1 (modules/audio_processing/aec3/aec_state.cc, subtractor_output_analyzer.cc,
   reverb_model_estimator.cc, reverb_frequency_response.cc). Copyright (c) 2011, The WebRTC project authors; BSD
   3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.voice.internal.aec3;

import com.github.auties00.smalt.audio.voice.internal.FloatMath;

import java.util.Arrays;

import static com.github.auties00.smalt.audio.voice.internal.aec3.Aec3.BLOCK_SIZE;
import static com.github.auties00.smalt.audio.voice.internal.aec3.Aec3.FFT_LENGTH_BY_2;
import static com.github.auties00.smalt.audio.voice.internal.aec3.Aec3.FFT_LENGTH_BY_2_PLUS_1;

/**
 * Tracks the state of the echo canceller that the residual echo estimation and the suppressor
 * depend on: whether the linear filters are usable, the echo path delay and gain of every capture
 * channel, the echo return loss enhancement, saturation, the initial state, transparent mode and
 * the reverberation model.
 *
 * @implNote This implementation ports {@code AecState} of WebRTC's
 * {@code modules/audio_processing/aec3/aec_state.cc}, with its nested {@code InitialState},
 * {@code FilterDelay}, {@code FilteringQualityAnalyzer} and {@code SaturationDetector}, and
 * {@code SubtractorOutputAnalyzer}, {@code ReverbModelEstimator} and
 * {@code ReverbFrequencyResponse} of the same directory, with the default configuration. With
 * the default {@code ep_strength.default_len} the reverb decay estimator only ever reports its
 * fixed decay, and with the default {@code echo_audibility} the render stationarity flags are
 * never set, so neither is modeled. Only the first capture channel's reverberation frequency
 * response is used, so only that one is computed. The echo return loss estimator
 * ({@code ErlEstimator}) only feeds the reference's metrics and is omitted.
 */
final class AecState {
    /**
     * The block energy above which a render block counts as active.
     */
    private static final float ACTIVE_RENDER_ENERGY = (Aec3.ACTIVE_RENDER_LIMIT * Aec3.ACTIVE_RENDER_LIMIT) * FFT_LENGTH_BY_2;

    /**
     * The number of capture channels.
     */
    private final int numChannels;

    /**
     * The filter response analyzer.
     */
    private final FilterAnalyzer filterAnalyzer;

    /**
     * The transparent mode detector.
     */
    private final TransparentMode transparentMode = new TransparentMode();

    /**
     * The echo return loss enhancement estimator of every capture channel.
     */
    final ErleEstimator[] erleEstimators;

    /**
     * The reverberation of the average render spectrum.
     */
    private final ReverbModel avgRenderReverb = new ReverbModel();

    /**
     * The average render spectrum at the delay plus its reverberation.
     */
    private final float[] avgRenderSpectrumWithReverb = new float[FFT_LENGTH_BY_2_PLUS_1];

    /**
     * The scratch average render spectrum.
     */
    private final float[] x2Average = new float[FFT_LENGTH_BY_2_PLUS_1];

    /**
     * The reverberation frequency response of the first capture channel.
     */
    private final float[] tailResponse = new float[FFT_LENGTH_BY_2_PLUS_1];

    /**
     * The smoothed decay of the first capture channel's filter.
     */
    private float averageDecay;

    /**
     * Whether the capture signal is saturated.
     */
    private boolean captureSignalSaturation;

    /**
     * Whether the echo is estimated to saturate the capture signal.
     */
    private boolean saturatedEcho;

    /**
     * The number of active, non-saturated render blocks since the last reset.
     */
    private long strongNotSaturatedRenderBlocks;

    /**
     * The number of active render blocks since the last reset.
     */
    private long blocksWithActiveRender;

    /**
     * Whether every capture channel's filters converged in the last block.
     */
    private final boolean[] filtersConverged;

    /**
     * Whether the initial state was left in the last block.
     */
    private boolean transitionTriggered;

    /**
     * Whether the canceller is in its initial state.
     */
    private boolean initialState = true;

    /**
     * The number of active, non-saturated render blocks counted by the initial state.
     */
    private long initialStateStrongBlocks;

    /**
     * The last external delay in blocks, or {@code -1} when none was reported.
     */
    private int externalDelay = -1;

    /**
     * The direct path delay of every capture channel's filter in blocks.
     */
    private final int[] filterDelays;

    /**
     * The smallest of {@link #filterDelays}.
     */
    private int minFilterDelay;

    /**
     * Whether the linear filters are usable.
     */
    private boolean usableLinearEstimates;

    /**
     * The number of filter update blocks since the last reset.
     */
    private long filterUpdateBlocksSinceReset;

    /**
     * The number of filter update blocks since the start.
     */
    private long filterUpdateBlocksSinceStart;

    /**
     * Whether a filter ever converged.
     */
    private boolean convergenceSeen;

    /**
     * Creates the state.
     *
     * @param numChannels the number of capture channels
     */
    AecState(int numChannels) {
        this.numChannels = numChannels;
        this.filterAnalyzer = new FilterAnalyzer(numChannels);
        this.erleEstimators = new ErleEstimator[numChannels];
        for (var ch = 0; ch < numChannels; ch++) {
            erleEstimators[ch] = new ErleEstimator();
        }
        this.filtersConverged = new boolean[numChannels];
        this.filterDelays = new int[numChannels];
        Arrays.fill(filterDelays, Aec3.DELAY_HEADROOM_SAMPLES / BLOCK_SIZE);
        this.minFilterDelay = Aec3.DELAY_HEADROOM_SAMPLES / BLOCK_SIZE;
    }

    boolean usableLinearEstimate() {
        return usableLinearEstimates;
    }

    boolean useLinearFilterOutput() {
        return usableLinearEstimates;
    }

    /**
     * Returns a channel's echo return loss enhancement.
     *
     * @param ch the capture channel
     * @param onsetCompensated whether the onset compensated estimate is returned
     * @return the 65 bins
     */
    float[] erle(int ch, boolean onsetCompensated) {
        return erleEstimators[ch].erle(onsetCompensated);
    }

    /**
     * Returns a channel's unbounded echo return loss enhancement.
     *
     * @param ch the capture channel
     * @return the 65 bins
     */
    float[] erleUnbounded(int ch) {
        return erleEstimators[ch].erleUnbounded;
    }

    int minDirectPathFilterDelay() {
        return minFilterDelay;
    }

    boolean saturatedCapture() {
        return captureSignalSaturation;
    }

    boolean saturatedEcho() {
        return saturatedEcho;
    }

    void updateCaptureSaturation(boolean saturation) {
        captureSignalSaturation = saturation;
    }

    boolean transparentModeActive() {
        return transparentMode.active();
    }

    float reverbDecay() {
        return Aec3.EP_DEFAULT_LEN;
    }

    float[] reverbFrequencyResponse() {
        return tailResponse;
    }

    boolean transitionTriggered() {
        return transitionTriggered;
    }

    int filterLengthBlocks() {
        return filterAnalyzer.filterLengthBlocks();
    }

    /**
     * Resets the state after an echo path change.
     *
     * @param gainChange whether the echo path gain changed
     * @param delayChange whether the delay changed
     */
    void handleEchoPathChange(boolean gainChange, boolean delayChange) {
        if (delayChange) {
            filterAnalyzer.reset();
            captureSignalSaturation = false;
            strongNotSaturatedRenderBlocks = 0;
            blocksWithActiveRender = 0;
            initialState = true;
            initialStateStrongBlocks = 0;
            transparentMode.reset();
            for (var erleEstimator : erleEstimators) {
                erleEstimator.reset(true);
            }
            usableLinearEstimates = false;
            filterUpdateBlocksSinceReset = 0;
        } else if (gainChange) {
            for (var erleEstimator : erleEstimators) {
                erleEstimator.reset(false);
            }
        }
        Arrays.fill(filtersConverged, false);
    }

    /**
     * Updates the state with the results of a block.
     *
     * @param externalDelayBlocks the delay estimate in blocks, or {@code -1} when there is none
     * @param subtractor the linear filters whose responses and outputs are analyzed
     * @param renderBuffer the render buffer
     * @param e2 the power spectra of the linear filter outputs, per capture channel
     * @param y2 the power spectra of the capture signal, per capture channel
     * @param outputs the subtractor outputs, per capture channel
     */
    void update(int externalDelayBlocks, Subtractor subtractor, RenderBuffer renderBuffer, float[][] e2, float[][] y2,
                SubtractorOutput[] outputs) {
        var anyFilterConverged = false;
        var allFiltersDiverged = true;
        for (var ch = 0; ch < numChannels; ch++) {
            var output = outputs[ch];
            var y2Energy = output.y2;
            var e2RefinedEnergy = output.e2Refined;
            var e2CoarseEnergy = output.e2Coarse;
            var convergenceThreshold = 50 * 50 * BLOCK_SIZE;
            var refinedFilterConverged = e2RefinedEnergy < 0.5f * y2Energy && y2Energy > convergenceThreshold;
            var coarseFilterConvergedStrict = e2CoarseEnergy < 0.05f * y2Energy && y2Energy > convergenceThreshold;
            var minE2 = FloatMath.min(e2RefinedEnergy, e2CoarseEnergy);
            var filterDiverged = minE2 > 1.5f * y2Energy && y2Energy > 30.f * 30.f * BLOCK_SIZE;
            filtersConverged[ch] = refinedFilterConverged || coarseFilterConvergedStrict;
            anyFilterConverged = anyFilterConverged || filtersConverged[ch];
            allFiltersDiverged = allFiltersDiverged && filterDiverged;
        }
        filterAnalyzer.update(subtractor.refinedImpulseResponses, renderBuffer);
        var anyFilterConsistent = filterAnalyzer.anyFilterConsistent();
        var maxEchoPathGain = filterAnalyzer.maxEchoPathGain();
        updateFilterDelay(filterAnalyzer.filterDelaysBlocks(), externalDelayBlocks);
        var blocks = renderBuffer.blocks;
        var activeRender = false;
        for (var r = 0; r < renderBuffer.numChannels; r++) {
            var alignedOffset = renderBuffer.blockOffset(-minFilterDelay, 0, r);
            var renderEnergy = 0.f;
            for (var i = 0; i < BLOCK_SIZE; i++) {
                renderEnergy = renderEnergy + blocks[alignedOffset + i] * blocks[alignedOffset + i];
            }
            if (renderEnergy > ACTIVE_RENDER_ENERGY) {
                activeRender = true;
                break;
            }
        }
        blocksWithActiveRender += activeRender ? 1 : 0;
        strongNotSaturatedRenderBlocks += activeRender && !captureSignalSaturation ? 1 : 0;
        computeAvgRenderReverb(renderBuffer);
        if (transitionTriggered) {
            for (var erleEstimator : erleEstimators) {
                erleEstimator.reset(false);
            }
        }
        for (var ch = 0; ch < numChannels; ch++) {
            erleEstimators[ch].update(avgRenderSpectrumWithReverb, y2[ch], e2[ch], filtersConverged[ch]);
        }
        updateSaturation(renderBuffer, outputs, maxEchoPathGain);
        updateInitialState(activeRender);
        transparentMode.update(minFilterDelay, anyFilterConsistent, anyFilterConverged, allFiltersDiverged, activeRender,
                captureSignalSaturation);
        updateFilteringQuality(activeRender, externalDelayBlocks, anyFilterConverged);
        updateReverbFrequencyResponse(subtractor);
    }

    private void updateFilterDelay(int[] analyzerFilterDelayBlocks, int externalDelayBlocks) {
        if (externalDelayBlocks >= 0 && (externalDelay < 0 || externalDelay != externalDelayBlocks)) {
            externalDelay = externalDelayBlocks;
        }
        var delayEstimatorMayNotHaveConverged = strongNotSaturatedRenderBlocks < 2 * Aec3.NUM_BLOCKS_PER_SECOND;
        if (delayEstimatorMayNotHaveConverged && externalDelay >= 0) {
            Arrays.fill(filterDelays, Aec3.DELAY_HEADROOM_SAMPLES / BLOCK_SIZE);
        } else {
            System.arraycopy(analyzerFilterDelayBlocks, 0, filterDelays, 0, numChannels);
        }
        var min = filterDelays[0];
        for (var ch = 1; ch < numChannels; ch++) {
            min = Math.min(min, filterDelays[ch]);
        }
        minFilterDelay = min;
    }

    private void computeAvgRenderReverb(RenderBuffer renderBuffer) {
        var numRenderChannels = renderBuffer.numChannels;
        var idxAtDelay = RenderBuffer.offsetIndex(renderBuffer.spectrumRead, minFilterDelay);
        var idxPast = RenderBuffer.incIndex(idxAtDelay);
        var spectra = renderBuffer.spectra;
        float[] x2;
        int o;
        if (numRenderChannels > 1) {
            averageChannels(spectra, idxPast * numRenderChannels * FFT_LENGTH_BY_2_PLUS_1, numRenderChannels, x2Average);
            avgRenderReverb.updateReverbNoFreqShaping(x2Average, 0, 1.0f, reverbDecay());
            averageChannels(spectra, idxAtDelay * numRenderChannels * FFT_LENGTH_BY_2_PLUS_1, numRenderChannels,
                    x2Average);
            x2 = x2Average;
            o = 0;
        } else {
            avgRenderReverb.updateReverbNoFreqShaping(spectra, idxPast * FFT_LENGTH_BY_2_PLUS_1, 1.0f, reverbDecay());
            x2 = spectra;
            o = idxAtDelay * FFT_LENGTH_BY_2_PLUS_1;
        }
        var reverbPower = avgRenderReverb.reverb;
        for (var k = 0; k < FFT_LENGTH_BY_2_PLUS_1; ++k) {
            avgRenderSpectrumWithReverb[k] = x2[o + k] + reverbPower[k];
        }
    }

    /**
     * Averages the power spectra of the render channels of a slot.
     *
     * @param spectra the power spectra
     * @param base the index of the slot's first channel
     * @param numRenderChannels the number of render channels
     * @param out receives the 65 averages
     */
    private static void averageChannels(float[] spectra, int base, int numRenderChannels, float[] out) {
        Arrays.fill(out, 0.f);
        for (var ch = 0; ch < numRenderChannels; ++ch) {
            var o = base + ch * FFT_LENGTH_BY_2_PLUS_1;
            for (var k = 0; k < FFT_LENGTH_BY_2_PLUS_1; ++k) {
                out[k] += spectra[o + k];
            }
        }
        var normalizer = 1.f / numRenderChannels;
        for (var k = 0; k < FFT_LENGTH_BY_2_PLUS_1; ++k) {
            out[k] *= normalizer;
        }
    }

    private void updateSaturation(RenderBuffer renderBuffer, SubtractorOutput[] outputs, float echoPathGain) {
        saturatedEcho = false;
        if (!captureSignalSaturation) {
            return;
        }
        if (usableLinearEstimates) {
            var saturationThreshold = 20000.f;
            for (var ch = 0; ch < numChannels; ch++) {
                saturatedEcho = saturatedEcho || (outputs[ch].sRefinedMaxAbs > saturationThreshold
                        || outputs[ch].sCoarseMaxAbs > saturationThreshold);
            }
        } else {
            var blocks = renderBuffer.blocks;
            var maxSample = 0.f;
            for (var r = 0; r < renderBuffer.numChannels; r++) {
                var alignedOffset = renderBuffer.blockOffset(-minFilterDelay, 0, r);
                for (var i = 0; i < BLOCK_SIZE; i++) {
                    maxSample = FloatMath.max(maxSample, Math.abs(blocks[alignedOffset + i]));
                }
            }
            var margin = 10.f;
            var peakEchoAmplitude = maxSample * echoPathGain * margin;
            saturatedEcho = peakEchoAmplitude > 32000;
        }
    }

    private void updateInitialState(boolean activeRender) {
        initialStateStrongBlocks += activeRender && !captureSignalSaturation ? 1 : 0;
        var previous = initialState;
        initialState = initialStateStrongBlocks < Aec3.INITIAL_STATE_SECONDS * Aec3.NUM_BLOCKS_PER_SECOND;
        transitionTriggered = !initialState && previous;
    }

    private void updateFilteringQuality(boolean activeRender, int externalDelayBlocks, boolean anyFilterConverged) {
        var filterUpdate = activeRender && !captureSignalSaturation;
        filterUpdateBlocksSinceReset += filterUpdate ? 1 : 0;
        filterUpdateBlocksSinceStart += filterUpdate ? 1 : 0;
        convergenceSeen = convergenceSeen || anyFilterConverged;
        var sufficientDataToConvergeAtStartup = filterUpdateBlocksSinceStart > Aec3.NUM_BLOCKS_PER_SECOND * 0.4f;
        var sufficientDataToConvergeAtReset = sufficientDataToConvergeAtStartup
                && filterUpdateBlocksSinceReset > Aec3.NUM_BLOCKS_PER_SECOND * 0.2f;
        var usable = sufficientDataToConvergeAtStartup && sufficientDataToConvergeAtReset;
        usable = usable && (externalDelayBlocks >= 0 || convergenceSeen);
        usable = usable && !transparentMode.active();
        usableLinearEstimates = usable;
    }

    private void updateReverbFrequencyResponse(Subtractor subtractor) {
        var erleEstimator = erleEstimators[0];
        if (!erleEstimator.hasLinearFilterQuality()) {
            return;
        }
        var responses = subtractor.refinedFrequencyResponses[0];
        var tail = (subtractor.refinedFrequencyResponsesSizes[0] - 1) * FFT_LENGTH_BY_2_PLUS_1;
        var direct = filterDelays[0] * FFT_LENGTH_BY_2_PLUS_1;
        var decay = averageDecayWithinFilter(responses, direct, tail);
        var smoothing = 0.2f * erleEstimator.linearFilterQuality();
        averageDecay += smoothing * (decay - averageDecay);
        for (var k = 0; k < FFT_LENGTH_BY_2_PLUS_1; ++k) {
            tailResponse[k] = responses[direct + k] * averageDecay;
        }
        for (var k = 0; k < FFT_LENGTH_BY_2_PLUS_1; ++k) {
            tailResponse[k] = FloatMath.max(responses[tail + k], tailResponse[k]);
        }
        for (var k = 1; k < FFT_LENGTH_BY_2; ++k) {
            var avgNeighbor = 0.5f * (tailResponse[k - 1] + tailResponse[k + 1]);
            tailResponse[k] = FloatMath.max(tailResponse[k], avgNeighbor);
        }
    }

    private static float averageDecayWithinFilter(float[] responses, int direct, int tail) {
        var directPathEnergy = 0.f;
        for (var k = 1; k < FFT_LENGTH_BY_2_PLUS_1; k++) {
            directPathEnergy = directPathEnergy + responses[direct + k];
        }
        if (directPathEnergy == 0.f) {
            return 0.f;
        }
        var tailEnergy = 0.f;
        for (var k = 1; k < FFT_LENGTH_BY_2_PLUS_1; k++) {
            tailEnergy = tailEnergy + responses[tail + k];
        }
        return tailEnergy / directPathEnergy;
    }
}
