package com.github.auties00.smalt.audio.voice.internal.ns;

import com.github.auties00.smalt.audio.voice.internal.FloatMath;

import java.util.Arrays;

import static com.github.auties00.smalt.audio.voice.internal.ns.NsConstants.BINS;
import static com.github.auties00.smalt.audio.voice.internal.ns.NsConstants.LONG_STARTUP_PHASE_BLOCKS;
import static com.github.auties00.smalt.audio.voice.internal.ns.NsConstants.SHORT_STARTUP_PHASE_BLOCKS;

/**
 * Estimates the frequency-domain Wiener filter of the noise suppressor with a decision-directed
 * SNR estimate.
 *
 * @implNote This implementation ports WebRTC's
 * {@code modules/audio_processing/ns/wiener_filter.cc}.
 */
final class WienerFilter {
    /**
     * The suppression level tuning.
     */
    private final SuppressionParams params;

    /**
     * The magnitude spectrum of the previous processed frame.
     */
    private final float[] spectrumPrevProcess = new float[BINS];

    /**
     * The accumulated start-up spectrum.
     */
    private final float[] initialSpectralEstimate = new float[BINS];

    /**
     * The current filter gains.
     */
    private final float[] filter = new float[BINS];

    /**
     * Creates a filter that passes everything.
     *
     * @param params the suppression level tuning
     */
    WienerFilter(SuppressionParams params) {
        this.params = params;
        Arrays.fill(filter, 1.f);
    }

    /**
     * Updates the filter from the noise and signal spectra of a frame.
     *
     * @param numAnalyzedFrames       the number of frames analyzed so far
     * @param noiseSpectrum           the noise spectrum estimate
     * @param prevNoiseSpectrum       the noise spectrum of the previous frame
     * @param parametricNoiseSpectrum the parametric start-up noise spectrum
     * @param signalSpectrum          the magnitude spectrum of the frame
     */
    void update(int numAnalyzedFrames, float[] noiseSpectrum, float[] prevNoiseSpectrum, float[] parametricNoiseSpectrum,
                float[] signalSpectrum) {
        var overSubtraction = params.overSubtractionFactor();
        var minimumGain = params.minimumAttenuatingGain();
        for (var i = 0; i < BINS; ++i) {
            var prevTsa = spectrumPrevProcess[i] / (prevNoiseSpectrum[i] + 0.0001f) * filter[i];
            float currentTsa;
            if (signalSpectrum[i] > noiseSpectrum[i]) {
                currentTsa = signalSpectrum[i] / (noiseSpectrum[i] + 0.0001f) - 1.f;
            } else {
                currentTsa = 0.f;
            }
            var snrPrior = 0.98f * prevTsa + (1.f - 0.98f) * currentTsa;
            filter[i] = snrPrior / (overSubtraction + snrPrior);
            filter[i] = FloatMath.max(FloatMath.min(filter[i], 1.f), minimumGain);
        }
        if (numAnalyzedFrames < SHORT_STARTUP_PHASE_BLOCKS) {
            var oneByShortStartupPhaseBlocks = 1.f / SHORT_STARTUP_PHASE_BLOCKS;
            for (var i = 0; i < BINS; ++i) {
                initialSpectralEstimate[i] += signalSpectrum[i];
                var filterInitial = initialSpectralEstimate[i] - overSubtraction * parametricNoiseSpectrum[i];
                filterInitial /= initialSpectralEstimate[i] + 0.0001f;
                filterInitial = FloatMath.max(FloatMath.min(filterInitial, 1.f), minimumGain);
                filterInitial *= SHORT_STARTUP_PHASE_BLOCKS - numAnalyzedFrames;
                filter[i] *= numAnalyzedFrames;
                filter[i] += filterInitial;
                filter[i] *= oneByShortStartupPhaseBlocks;
            }
        }
        System.arraycopy(signalSpectrum, 0, spectrumPrevProcess, 0, BINS);
    }

    /**
     * Returns the overall gain correction for the energy the filter removed.
     *
     * @param numAnalyzedFrames      the number of frames analyzed so far
     * @param priorSpeechProbability the prior probability of speech
     * @param energyBeforeFiltering  the windowed frame energy before filtering
     * @param energyAfterFiltering   the frame energy after filtering
     * @return the gain to apply to the filtered frame
     */
    float computeOverallScalingFactor(int numAnalyzedFrames, float priorSpeechProbability, float energyBeforeFiltering,
                                      float energyAfterFiltering) {
        if (!params.useAttenuationAdjustment() || numAnalyzedFrames <= LONG_STARTUP_PHASE_BLOCKS) {
            return 1.f;
        }
        var gain = (float) Math.sqrt(energyAfterFiltering / (energyBeforeFiltering + 1.f));
        var bLim = 0.5f;
        var scaleFactor1 = 1.f;
        if (gain > bLim) {
            scaleFactor1 = 1.f + 1.3f * (gain - bLim);
            if (gain * scaleFactor1 > 1.f) {
                scaleFactor1 = 1.f / gain;
            }
        }
        var scaleFactor2 = 1.f;
        if (gain < bLim) {
            gain = FloatMath.max(gain, params.minimumAttenuatingGain());
            scaleFactor2 = 1.f - 0.3f * (bLim - gain);
        }
        return priorSpeechProbability * scaleFactor1 + (1.f - priorSpeechProbability) * scaleFactor2;
    }

    /**
     * Returns the current filter gains.
     *
     * @return the live array, {@link NsConstants#BINS} bins
     */
    float[] filter() {
        return filter;
    }
}
