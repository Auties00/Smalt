/* Ports webrtc-audio-processing 2.1 (modules/audio_processing/aec3/echo_remover.cc). Copyright (c) 2011, The WebRTC
   project authors; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.voice.internal.aec3;

import com.github.auties00.smalt.audio.voice.internal.FloatMath;

import static com.github.auties00.smalt.audio.voice.internal.aec3.Aec3.BLOCK_SIZE;
import static com.github.auties00.smalt.audio.voice.internal.aec3.Aec3.FFT_LENGTH_BY_2;
import static com.github.auties00.smalt.audio.voice.internal.aec3.Aec3.FFT_LENGTH_BY_2_PLUS_1;

/**
 * Removes the echo from one capture block: linear subtraction with the adaptive filters, then
 * suppression of the residual echo with comfort noise.
 *
 * @implNote This implementation ports {@code EchoRemoverImpl} of WebRTC's
 * {@code modules/audio_processing/aec3/echo_remover.cc} for one render and one capture channel
 * and one band; its metrics are omitted.
 */
final class EchoRemover {
    /**
     * The number of samples over which the output switches between the refined and the coarse
     * filter output.
     */
    private static final int TRANSITION_SIZE = 30;

    /**
     * The subtractor.
     */
    private final Subtractor subtractor;

    /**
     * The suppression gain.
     */
    private final SuppressionGain suppressionGain = new SuppressionGain();

    /**
     * The comfort noise generator.
     */
    private final ComfortNoiseGenerator cng = new ComfortNoiseGenerator();

    /**
     * The suppression filter.
     */
    private final SuppressionFilter suppressionFilter = new SuppressionFilter();

    /**
     * The narrow-band render detector.
     */
    private final RenderSignalAnalyzer renderSignalAnalyzer = new RenderSignalAnalyzer();

    /**
     * The residual echo estimator.
     */
    private final ResidualEchoEstimator residualEchoEstimator = new ResidualEchoEstimator();

    /**
     * The canceller state.
     */
    final AecState aecState = new AecState();

    /**
     * The transform.
     */
    private final Aec3Fft fft = new Aec3Fft();

    /**
     * The previous linear filter output block.
     */
    private final float[] eOld = new float[FFT_LENGTH_BY_2];

    /**
     * The previous capture block.
     */
    private final float[] yOld = new float[FFT_LENGTH_BY_2];

    /**
     * The blocks left of the gain change hangover.
     */
    private int gainChangeHangover;

    /**
     * Whether the refined filter output was selected for the previous block.
     */
    private boolean refinedFilterOutputLastSelected = true;

    /**
     * The subtractor output of the block.
     */
    private final SubtractorOutput subtractorOutput = new SubtractorOutput();

    /**
     * The linear filter output block.
     */
    private final float[] e = new float[BLOCK_SIZE];

    /**
     * The capture spectrum.
     */
    private final FftData y = new FftData();

    /**
     * The linear filter output spectrum.
     */
    private final FftData eSpectrum = new FftData();

    /**
     * The capture power spectrum.
     */
    private final float[] y2 = new float[FFT_LENGTH_BY_2_PLUS_1];

    /**
     * The linear filter output power spectrum.
     */
    private final float[] e2 = new float[FFT_LENGTH_BY_2_PLUS_1];

    /**
     * The residual echo power spectrum.
     */
    private final float[] r2 = new float[FFT_LENGTH_BY_2_PLUS_1];

    /**
     * The residual echo power spectrum computed with the unbounded ERLE.
     */
    private final float[] r2Unbounded = new float[FFT_LENGTH_BY_2_PLUS_1];

    /**
     * The linear echo power spectrum.
     */
    private final float[] s2Linear = new float[FFT_LENGTH_BY_2_PLUS_1];

    /**
     * The comfort noise spectrum.
     */
    private final FftData comfortNoise = new FftData();

    /**
     * The suppression gain.
     */
    private final float[] g = new float[FFT_LENGTH_BY_2_PLUS_1];

    /**
     * Creates an echo remover.
     *
     * @param kernel the kernel of the adaptive filters
     */
    EchoRemover(AdaptiveFilterKernel kernel) {
        this.subtractor = new Subtractor(kernel);
    }

    /**
     * Removes the echo from a capture block in place.
     *
     * @param gainChange whether the echo path gain changed
     * @param delayChange whether the echo path delay changed
     * @param clockDrift whether clock drift is detected
     * @param captureSignalSaturation whether the capture signal is saturated
     * @param externalDelay the delay estimate in blocks, or {@code -1}
     * @param renderBuffer the render rings
     * @param capture the capture samples; receives the echo-free samples
     * @param offset the index of the first of the 64 capture samples
     */
    void processCapture(boolean gainChange, boolean delayChange, boolean clockDrift, boolean captureSignalSaturation,
                        int externalDelay, RenderBuffer renderBuffer, float[] capture, int offset) {
        var xOffset = renderBuffer.blockOffset(0);
        aecState.updateCaptureSaturation(captureSignalSaturation);
        if (gainChange || delayChange) {
            if (gainChange) {
                if (gainChangeHangover == 0) {
                    gainChangeHangover = 3;
                } else {
                    gainChange = false;
                }
            }
            subtractor.handleEchoPathChange(gainChange, delayChange);
            aecState.handleEchoPathChange(gainChange, delayChange);
        }
        if (gainChangeHangover > 0) {
            --gainChangeHangover;
        }
        renderSignalAnalyzer.update(renderBuffer, aecState.minDirectPathFilterDelay());
        if (aecState.transitionTriggered()) {
            subtractor.exitInitialState();
        }
        var output = subtractorOutput;
        subtractor.process(renderBuffer, capture, offset, renderSignalAnalyzer, aecState.saturatedCapture(), output);
        formLinearFilterOutput(output);
        fft.windowedPaddedFft(capture, offset, yOld, y);
        fft.windowedPaddedFft(e, 0, eOld, eSpectrum);
        for (var k = 0; k < FFT_LENGTH_BY_2_PLUS_1; ++k) {
            var dr = y.re[k] - eSpectrum.re[k];
            var di = y.im[k] - eSpectrum.im[k];
            s2Linear[k] = dr * dr + di * di;
        }
        y.spectrum(y2);
        eSpectrum.spectrum(e2);
        aecState.update(externalDelay, subtractor, renderBuffer, e2, y2, output);
        var yFft = aecState.useLinearFilterOutput() ? eSpectrum : y;
        cng.compute(aecState.saturatedCapture(), y2, comfortNoise);
        residualEchoEstimator.estimate(aecState, renderBuffer, s2Linear, y2, suppressionGain.isDominantNearend(), r2,
                r2Unbounded);
        if (aecState.usableLinearEstimate()) {
            for (var k = 0; k < FFT_LENGTH_BY_2_PLUS_1; k++) {
                e2[k] = FloatMath.min(e2[k], y2[k]);
            }
        }
        var nearendSpectrum = aecState.usableLinearEstimate() ? e2 : y2;
        suppressionGain.getGain(nearendSpectrum, r2, r2Unbounded, cng.noiseSpectrum(), aecState.saturatedEcho(),
                renderBuffer.blocks, xOffset, clockDrift, g);
        suppressionFilter.applyGain(comfortNoise, g, yFft, capture, offset);
    }

    /**
     * Selects the refined or the coarse filter output for {@link #e}, crossfading over 30
     * samples when the selection changes.
     *
     * @param output the subtractor output
     */
    private void formLinearFilterOutput(SubtractorOutput output) {
        var useRefinedOutput = true;
        if (output.e2Coarse < 0.9f * output.e2Refined && output.y2 > 30.f * 30.f * BLOCK_SIZE
                && (output.s2Refined > 60.f * 60.f * BLOCK_SIZE || output.s2Coarse > 60.f * 60.f * BLOCK_SIZE)) {
            useRefinedOutput = false;
        } else {
            if (output.e2Coarse < output.e2Refined && output.y2 < output.e2Refined) {
                useRefinedOutput = false;
            }
        }
        var from = refinedFilterOutputLastSelected ? output.eRefined : output.eCoarse;
        var to = useRefinedOutput ? output.eRefined : output.eCoarse;
        if (from == to) {
            System.arraycopy(to, 0, e, 0, BLOCK_SIZE);
        } else {
            var oneByTransitionSizePlusOne = 1.f / (TRANSITION_SIZE + 1);
            for (var k = 0; k < TRANSITION_SIZE; ++k) {
                var a = (k + 1) * oneByTransitionSizePlusOne;
                e[k] = a * to[k] + (1.f - a) * from[k];
            }
            System.arraycopy(to, TRANSITION_SIZE, e, TRANSITION_SIZE, BLOCK_SIZE - TRANSITION_SIZE);
        }
        refinedFilterOutputLastSelected = useRefinedOutput;
    }
}
