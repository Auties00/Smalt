/* Ports webrtc-audio-processing 2.1 (modules/audio_processing/aec3/echo_remover.cc). Copyright (c) 2011, The WebRTC
   project authors; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.voice.internal.aec3;

import com.github.auties00.smalt.audio.voice.internal.FloatMath;

import static com.github.auties00.smalt.audio.voice.internal.aec3.Aec3.BLOCK_SIZE;
import static com.github.auties00.smalt.audio.voice.internal.aec3.Aec3.FFT_LENGTH_BY_2;
import static com.github.auties00.smalt.audio.voice.internal.aec3.Aec3.FFT_LENGTH_BY_2_PLUS_1;

/**
 * Removes the echo from one capture block of every channel and band: linear subtraction with the
 * adaptive filters of the lowest band, then suppression of the residual echo with comfort noise
 * in every band.
 *
 * @implNote This implementation ports {@code EchoRemoverImpl} of WebRTC's
 * {@code modules/audio_processing/aec3/echo_remover.cc}; its metrics are omitted. As in the
 * reference, the choice between the refined and the coarse filter output of a channel ramps from
 * the choice made for the previous channel.
 */
final class EchoRemover {
    /**
     * The number of samples over which the output switches between the refined and the coarse
     * filter output.
     */
    private static final int TRANSITION_SIZE = 30;

    /**
     * The number of capture channels.
     */
    private final int numChannels;

    /**
     * The subtractor.
     */
    private final Subtractor subtractor;

    /**
     * The suppression gain computer.
     */
    private final SuppressionGain suppressionGain;

    /**
     * The comfort noise generator.
     */
    private final ComfortNoiseGenerator cng;

    /**
     * The suppression filter.
     */
    private final SuppressionFilter suppressionFilter;

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
    final AecState aecState;

    /**
     * The transform.
     */
    private final Aec3Fft fft = new Aec3Fft();

    /**
     * The previous linear filter output of every channel, the first half of its windowed transform.
     */
    private final float[][] eOld;

    /**
     * The previous capture block of every channel.
     */
    private final float[][] yOld;

    /**
     * The number of blocks left during which further gain changes are ignored.
     */
    private int gainChangeHangover;

    /**
     * Whether the refined filter output was selected for the last channel processed.
     */
    private boolean refinedFilterOutputLastSelected = true;

    /**
     * The subtractor outputs, per channel.
     */
    private final SubtractorOutput[] subtractorOutputs;

    /**
     * The linear filter output of every channel.
     */
    private final float[][] e;

    /**
     * The capture spectrum of every channel.
     */
    private final FftData[] y;

    /**
     * The linear filter output spectrum of every channel.
     */
    private final FftData[] eSpectrum;

    /**
     * The capture power spectrum of every channel.
     */
    private final float[][] y2;

    /**
     * The linear filter output power spectrum of every channel.
     */
    private final float[][] e2;

    /**
     * The residual echo power spectrum of every channel.
     */
    private final float[][] r2;

    /**
     * The residual echo power spectrum with the unbounded ERLE of every channel.
     */
    private final float[][] r2Unbounded;

    /**
     * The linear echo power spectrum of every channel.
     */
    private final float[][] s2Linear;

    /**
     * The comfort noise of the lowest band of every channel.
     */
    private final FftData[] comfortNoise;

    /**
     * The comfort noise of the upper bands of every channel.
     */
    private final FftData[] highBandComfortNoise;

    /**
     * The suppression gains.
     */
    private final float[] g = new float[FFT_LENGTH_BY_2_PLUS_1];

    /**
     * Creates an echo remover.
     *
     * @param config the tunings
     * @param numBands the number of bands
     * @param numRenderChannels the number of render channels
     * @param numCaptureChannels the number of capture channels
     * @param kernel the kernel of the adaptive filters
     */
    EchoRemover(Aec3Config config, int numBands, int numRenderChannels, int numCaptureChannels,
                AdaptiveFilterKernel kernel) {
        this.numChannels = numCaptureChannels;
        this.subtractor = new Subtractor(config, numRenderChannels, numCaptureChannels, kernel);
        this.suppressionGain = new SuppressionGain(config, numCaptureChannels);
        this.cng = new ComfortNoiseGenerator(numCaptureChannels);
        this.suppressionFilter = new SuppressionFilter(numBands, numCaptureChannels);
        this.aecState = new AecState(numCaptureChannels);
        this.eOld = new float[numCaptureChannels][FFT_LENGTH_BY_2];
        this.yOld = new float[numCaptureChannels][FFT_LENGTH_BY_2];
        this.subtractorOutputs = new SubtractorOutput[numCaptureChannels];
        this.e = new float[numCaptureChannels][BLOCK_SIZE];
        this.y = new FftData[numCaptureChannels];
        this.eSpectrum = new FftData[numCaptureChannels];
        this.comfortNoise = new FftData[numCaptureChannels];
        this.highBandComfortNoise = new FftData[numCaptureChannels];
        for (var ch = 0; ch < numCaptureChannels; ch++) {
            subtractorOutputs[ch] = new SubtractorOutput();
            y[ch] = new FftData();
            eSpectrum[ch] = new FftData();
            comfortNoise[ch] = new FftData();
            highBandComfortNoise[ch] = new FftData();
        }
        this.y2 = new float[numCaptureChannels][FFT_LENGTH_BY_2_PLUS_1];
        this.e2 = new float[numCaptureChannels][FFT_LENGTH_BY_2_PLUS_1];
        this.r2 = new float[numCaptureChannels][FFT_LENGTH_BY_2_PLUS_1];
        this.r2Unbounded = new float[numCaptureChannels][FFT_LENGTH_BY_2_PLUS_1];
        this.s2Linear = new float[numCaptureChannels][FFT_LENGTH_BY_2_PLUS_1];
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
     * @param capture the capture block, band by band and channel by channel; receives the echo-free
     * samples
     */
    void processCapture(boolean gainChange, boolean delayChange, boolean clockDrift, boolean captureSignalSaturation,
                        int externalDelay, RenderBuffer renderBuffer, float[] capture) {
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
        var outputs = subtractorOutputs;
        subtractor.process(renderBuffer, capture, renderSignalAnalyzer, aecState.saturatedCapture(), outputs);
        for (var ch = 0; ch < numChannels; ch++) {
            formLinearFilterOutput(outputs[ch], e[ch]);
            fft.windowedPaddedFft(capture, ch * BLOCK_SIZE, yOld[ch], y[ch]);
            fft.windowedPaddedFft(e[ch], 0, eOld[ch], eSpectrum[ch]);
            var yCh = y[ch];
            var eCh = eSpectrum[ch];
            var s2 = s2Linear[ch];
            for (var k = 0; k < FFT_LENGTH_BY_2_PLUS_1; ++k) {
                var dr = yCh.re[k] - eCh.re[k];
                var di = yCh.im[k] - eCh.im[k];
                s2[k] = dr * dr + di * di;
            }
            yCh.spectrum(y2[ch]);
            eCh.spectrum(e2[ch]);
        }
        aecState.update(externalDelay, subtractor, renderBuffer, e2, y2, outputs);
        var yFft = aecState.useLinearFilterOutput() ? eSpectrum : y;
        cng.compute(aecState.saturatedCapture(), y2, comfortNoise, highBandComfortNoise);
        residualEchoEstimator.estimate(aecState, renderBuffer, s2Linear, y2, suppressionGain.isDominantNearend(), r2,
                r2Unbounded, numChannels);
        var usable = aecState.usableLinearEstimate();
        if (usable) {
            for (var ch = 0; ch < numChannels; ch++) {
                var e2Ch = e2[ch];
                var y2Ch = y2[ch];
                for (var k = 0; k < FFT_LENGTH_BY_2_PLUS_1; k++) {
                    e2Ch[k] = FloatMath.min(e2Ch[k], y2Ch[k]);
                }
            }
        }
        var nearendSpectrum = usable ? e2 : y2;
        var echoSpectrum = usable ? s2Linear : r2;
        suppressionGain.getGain(nearendSpectrum, echoSpectrum, r2, r2Unbounded, cng.noiseSpectrum(),
                renderSignalAnalyzer, aecState.saturatedEcho(), renderBuffer, clockDrift, g);
        suppressionFilter.applyGain(comfortNoise, highBandComfortNoise, g, suppressionGain.highBandsGain(), yFft,
                capture);
    }

    private void formLinearFilterOutput(SubtractorOutput output, float[] e) {
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
