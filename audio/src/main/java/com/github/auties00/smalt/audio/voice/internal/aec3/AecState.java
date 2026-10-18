/* Ports webrtc-audio-processing 2.1 (modules/audio_processing/aec3/aec_state.cc). Copyright (c) 2011, The WebRTC
   project authors; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.voice.internal.aec3;

import com.github.auties00.smalt.audio.voice.internal.FloatMath;

import static com.github.auties00.smalt.audio.voice.internal.aec3.Aec3.BLOCK_SIZE;
import static com.github.auties00.smalt.audio.voice.internal.aec3.Aec3.FFT_LENGTH_BY_2;
import static com.github.auties00.smalt.audio.voice.internal.aec3.Aec3.FFT_LENGTH_BY_2_PLUS_1;

/**
 * Tracks the state of the echo canceller that the residual echo estimation and the suppressor
 * depend on: whether the linear filter is usable, the echo path delay and gain, the echo return
 * loss enhancement, saturation, the initial state, transparent mode and the reverberation model.
 *
 * @implNote This implementation ports {@code AecState} of WebRTC's
 * {@code modules/audio_processing/aec3/aec_state.cc}, with its nested {@code InitialState},
 * {@code FilterDelay}, {@code FilteringQualityAnalyzer} and {@code SaturationDetector}, and
 * {@code SubtractorOutputAnalyzer}, {@code ReverbModelEstimator} and
 * {@code ReverbFrequencyResponse} of the same directory, for one render and one capture channel
 * with the default configuration. With the default {@code ep_strength.default_len} the reverb
 * decay estimator only ever reports its fixed decay, and with the default
 * {@code echo_audibility} the render stationarity flags are never set, so neither is modeled. The
 * echo return loss estimator ({@code ErlEstimator}) only feeds the reference's metrics and is
 * omitted.
 */
final class AecState {
    /**
     * The block energy above which a render block counts as active.
     */
    private static final float ACTIVE_RENDER_ENERGY = (Aec3.ACTIVE_RENDER_LIMIT * Aec3.ACTIVE_RENDER_LIMIT) * FFT_LENGTH_BY_2;

    /**
     * The filter response analyzer.
     */
    private final FilterAnalyzer filterAnalyzer = new FilterAnalyzer();

    /**
     * The transparent mode detector.
     */
    private final TransparentMode transparentMode = new TransparentMode();

    /**
     * The echo return loss enhancement estimator.
     */
    final ErleEstimator erleEstimator = new ErleEstimator();

    /**
     * The reverberation model of the render signal at the echo path delay.
     */
    private final ReverbModel avgRenderReverb = new ReverbModel();

    /**
     * The render power with reverberation at the echo path delay.
     */
    private final float[] avgRenderSpectrumWithReverb = new float[FFT_LENGTH_BY_2_PLUS_1];

    /**
     * The estimated frequency response of the echo tail.
     */
    private final float[] tailResponse = new float[FFT_LENGTH_BY_2_PLUS_1];

    /**
     * The smoothed ratio of tail to direct-path energy of the filter.
     */
    private float averageDecay;

    /**
     * Whether the capture signal is saturated.
     */
    private boolean captureSignalSaturation;

    /**
     * Whether the echo is saturated.
     */
    private boolean saturatedEcho;

    /**
     * The number of strong, unsaturated render blocks.
     */
    private long strongNotSaturatedRenderBlocks;

    /**
     * The number of active render blocks.
     */
    private long blocksWithActiveRender;

    /**
     * Whether the filter of the capture channel has converged.
     */
    private boolean filterConverged;

    /**
     * Whether the transition out of the initial state happened in the last update.
     */
    private boolean transitionTriggered;

    /**
     * Whether the canceller is in its initial state.
     */
    private boolean initialState = true;

    /**
     * The number of strong, unsaturated render blocks since the initial state began.
     */
    private long initialStateStrongBlocks;

    /**
     * The last external delay estimate, or {@code -1} when none was reported.
     */
    private int externalDelay = -1;

    /**
     * The delay of the direct path of the filter, in blocks.
     */
    private int minFilterDelay;

    /**
     * Whether the linear filter output is usable.
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
     * Whether a filter has ever converged.
     */
    private boolean convergenceSeen;

    /**
     * Creates the state of a fresh canceller.
     */
    AecState() {
    }

    /**
     * Returns whether the linear filter estimate is usable.
     *
     * @return whether the linear echo estimate can be relied on
     */
    boolean usableLinearEstimate() {
        return usableLinearEstimates;
    }

    /**
     * Returns whether the suppressor works on the linear filter output.
     *
     * @return whether the linear filter output replaces the capture signal
     */
    boolean useLinearFilterOutput() {
        return usableLinearEstimates;
    }

    /**
     * Returns the per-bin echo return loss enhancement.
     *
     * @param onsetCompensated whether the onset-compensated estimate is returned
     * @return the live array of 65 estimates
     */
    float[] erle(boolean onsetCompensated) {
        return erleEstimator.erle(onsetCompensated);
    }

    /**
     * Returns the unbounded per-bin echo return loss enhancement.
     *
     * @return the live array of 65 estimates
     */
    float[] erleUnbounded() {
        return erleEstimator.erleUnbounded;
    }

    /**
     * Returns the delay of the direct path of the filter.
     *
     * @return the delay in blocks
     */
    int minDirectPathFilterDelay() {
        return minFilterDelay;
    }

    /**
     * Returns whether the capture signal is saturated.
     *
     * @return the saturation flag of the current block
     */
    boolean saturatedCapture() {
        return captureSignalSaturation;
    }

    /**
     * Returns whether the echo is saturated.
     *
     * @return whether the echo may have been clipped
     */
    boolean saturatedEcho() {
        return saturatedEcho;
    }

    /**
     * Sets whether the capture signal of the current block is saturated.
     *
     * @param saturation the saturation flag
     */
    void updateCaptureSaturation(boolean saturation) {
        captureSignalSaturation = saturation;
    }

    /**
     * Returns whether transparent mode is active.
     *
     * @return whether the suppressor should be transparent
     */
    boolean transparentModeActive() {
        return transparentMode.active();
    }

    /**
     * Returns the reverberation decay per block.
     *
     * @return {@code ep_strength.default_len}, for normal and for mild decay alike
     */
    float reverbDecay() {
        return Aec3.EP_DEFAULT_LEN;
    }

    /**
     * Returns the estimated frequency response of the echo tail.
     *
     * @return the live array of 65 values
     */
    float[] reverbFrequencyResponse() {
        return tailResponse;
    }

    /**
     * Returns whether the canceller left its initial state in the last update.
     *
     * @return whether the transition happened
     */
    boolean transitionTriggered() {
        return transitionTriggered;
    }

    /**
     * Returns the length of the refined filter response.
     *
     * @return the length in blocks
     */
    int filterLengthBlocks() {
        return filterAnalyzer.filterLengthBlocks();
    }

    /**
     * Resets the state after an echo path change.
     *
     * @param gainChange whether the echo path gain changed
     * @param delayChange whether the echo path delay changed
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
            erleEstimator.reset(true);
            usableLinearEstimates = false;
            filterUpdateBlocksSinceReset = 0;
        } else if (gainChange) {
            erleEstimator.reset(false);
        }
        filterConverged = false;
    }

    /**
     * Updates the state with one block.
     *
     * @param externalDelayBlocks the delay estimate of the delay controller in blocks, or
     * {@code -1} when there is none
     * @param subtractor the subtractor, whose refined filter is analyzed
     * @param renderBuffer the render rings
     * @param e2 the power spectrum of the linear filter output
     * @param y2 the capture power spectrum
     * @param output the subtractor output
     */
    void update(int externalDelayBlocks, Subtractor subtractor, RenderBuffer renderBuffer, float[] e2, float[] y2,
                SubtractorOutput output) {
        var y2Energy = output.y2;
        var e2RefinedEnergy = output.e2Refined;
        var e2CoarseEnergy = output.e2Coarse;
        var convergenceThreshold = 50 * 50 * BLOCK_SIZE;
        var convergenceThresholdLowLevel = 20 * 20 * BLOCK_SIZE;
        var refinedFilterConverged = e2RefinedEnergy < 0.5f * y2Energy && y2Energy > convergenceThreshold;
        var coarseFilterConvergedStrict = e2CoarseEnergy < 0.05f * y2Energy && y2Energy > convergenceThreshold;
        var coarseFilterConvergedRelaxed = e2CoarseEnergy < 0.3f * y2Energy && y2Energy > convergenceThresholdLowLevel;
        var minE2 = FloatMath.min(e2RefinedEnergy, e2CoarseEnergy);
        var filterDiverged = minE2 > 1.5f * y2Energy && y2Energy > 30.f * 30.f * BLOCK_SIZE;
        filterConverged = refinedFilterConverged || coarseFilterConvergedStrict;
        var anyFilterConverged = filterConverged;
        var allFiltersDiverged = filterDiverged;

        filterAnalyzer.update(subtractor.refinedImpulseResponse, renderBuffer);
        var anyFilterConsistent = filterAnalyzer.consistentEstimate();
        var maxEchoPathGain = filterAnalyzer.gain();

        updateFilterDelay(filterAnalyzer.filterDelayBlocks(), externalDelayBlocks);

        var blocks = renderBuffer.blocks;
        var alignedOffset = renderBuffer.blockOffset(-minFilterDelay);
        var renderEnergy = 0.f;
        for (var i = 0; i < BLOCK_SIZE; i++) {
            renderEnergy = renderEnergy + blocks[alignedOffset + i] * blocks[alignedOffset + i];
        }
        var activeRender = renderEnergy > ACTIVE_RENDER_ENERGY;
        blocksWithActiveRender += activeRender ? 1 : 0;
        strongNotSaturatedRenderBlocks += activeRender && !captureSignalSaturation ? 1 : 0;

        computeAvgRenderReverb(renderBuffer);

        if (transitionTriggered) {
            erleEstimator.reset(false);
        }
        erleEstimator.update(avgRenderSpectrumWithReverb, y2, e2, filterConverged);
        updateSaturation(blocks, alignedOffset, output, maxEchoPathGain);
        updateInitialState(activeRender);
        transparentMode.update(minFilterDelay, anyFilterConsistent, anyFilterConverged, allFiltersDiverged, activeRender,
                captureSignalSaturation);
        updateFilteringQuality(activeRender, externalDelayBlocks, anyFilterConverged);
        updateReverbFrequencyResponse(subtractor);
    }

    /**
     * Updates the external delay and the direct path delay of the filter.
     *
     * @param analyzerFilterDelayBlocks the delay of the filter peak in blocks
     * @param externalDelayBlocks the delay controller's estimate in blocks, or {@code -1}
     */
    private void updateFilterDelay(int analyzerFilterDelayBlocks, int externalDelayBlocks) {
        if (externalDelayBlocks >= 0 && (externalDelay < 0 || externalDelay != externalDelayBlocks)) {
            externalDelay = externalDelayBlocks;
        }
        var delayEstimatorMayNotHaveConverged = strongNotSaturatedRenderBlocks < 2 * Aec3.NUM_BLOCKS_PER_SECOND;
        if (delayEstimatorMayNotHaveConverged && externalDelay >= 0) {
            minFilterDelay = Aec3.DELAY_HEADROOM_SAMPLES / BLOCK_SIZE;
        } else {
            minFilterDelay = analyzerFilterDelayBlocks;
        }
    }

    /**
     * Updates the render reverberation model and the render power with reverberation at the
     * direct path delay.
     *
     * @param renderBuffer the render rings
     */
    private void computeAvgRenderReverb(RenderBuffer renderBuffer) {
        var idxAtDelay = RenderBuffer.offsetIndex(renderBuffer.spectrumRead, minFilterDelay);
        var idxPast = RenderBuffer.incIndex(idxAtDelay);
        avgRenderReverb.updateReverbNoFreqShaping(renderBuffer.spectra, idxPast * FFT_LENGTH_BY_2_PLUS_1, 1.0f,
                reverbDecay());
        var x2 = renderBuffer.spectra;
        var o = idxAtDelay * FFT_LENGTH_BY_2_PLUS_1;
        var reverbPower = avgRenderReverb.reverb;
        for (var k = 0; k < FFT_LENGTH_BY_2_PLUS_1; ++k) {
            avgRenderSpectrumWithReverb[k] = x2[o + k] + reverbPower[k];
        }
    }

    /**
     * Updates the echo saturation flag.
     *
     * @param blocks the render block ring
     * @param alignedOffset the start of the render block at the direct path delay
     * @param output the subtractor output
     * @param echoPathGain the echo path gain estimate
     */
    private void updateSaturation(float[] blocks, int alignedOffset, SubtractorOutput output, float echoPathGain) {
        saturatedEcho = false;
        if (!captureSignalSaturation) {
            return;
        }
        if (usableLinearEstimates) {
            var saturationThreshold = 20000.f;
            saturatedEcho = output.sRefinedMaxAbs > saturationThreshold || output.sCoarseMaxAbs > saturationThreshold;
        } else {
            var maxSample = 0.f;
            for (var i = 0; i < BLOCK_SIZE; i++) {
                maxSample = FloatMath.max(maxSample, Math.abs(blocks[alignedOffset + i]));
            }
            var margin = 10.f;
            var peakEchoAmplitude = maxSample * echoPathGain * margin;
            saturatedEcho = peakEchoAmplitude > 32000;
        }
    }

    /**
     * Updates the initial state and detects the transition out of it.
     *
     * @param activeRender whether the render block is active
     */
    private void updateInitialState(boolean activeRender) {
        initialStateStrongBlocks += activeRender && !captureSignalSaturation ? 1 : 0;
        var previous = initialState;
        initialState = initialStateStrongBlocks < Aec3.INITIAL_STATE_SECONDS * Aec3.NUM_BLOCKS_PER_SECOND;
        transitionTriggered = !initialState && previous;
    }

    /**
     * Updates whether the linear filter output is usable.
     *
     * @param activeRender whether the render block is active
     * @param externalDelayBlocks the delay controller's estimate in blocks, or {@code -1}
     * @param anyFilterConverged whether a filter converged
     */
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

    /**
     * Updates the frequency response of the echo tail from the refined filter.
     *
     * @param subtractor the subtractor holding the refined filter
     */
    private void updateReverbFrequencyResponse(Subtractor subtractor) {
        if (!erleEstimator.hasLinearFilterQuality()) {
            return;
        }
        var responses = subtractor.refinedFrequencyResponses;
        var tail = (subtractor.refinedFrequencyResponsesSize - 1) * FFT_LENGTH_BY_2_PLUS_1;
        var direct = minFilterDelay * FFT_LENGTH_BY_2_PLUS_1;
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

    /**
     * Returns the ratio of the tail energy to the direct path energy of the filter, skipping the
     * DC bin.
     *
     * @param responses the power responses of the filter partitions
     * @param direct the start of the direct path partition
     * @param tail the start of the last partition
     * @return the ratio, or {@code 0} when the direct path has no energy
     */
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
