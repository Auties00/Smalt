package com.github.auties00.smalt.audio.voice.internal.ns;

import com.github.auties00.smalt.audio.voice.internal.FloatMath;

import static com.github.auties00.smalt.audio.voice.internal.ns.NsConstants.BINS;
import static com.github.auties00.smalt.audio.voice.internal.ns.NsConstants.LONG_STARTUP_PHASE_BLOCKS;

/**
 * Estimates the per-bin probability of speech from the signal model features.
 *
 * @implNote This implementation ports WebRTC's
 * {@code modules/audio_processing/ns/speech_probability_estimator.cc}; the hyperbolic tangent
 * is {@link FloatMath#tanhf(float)}, the float overload the reference's {@code tanh} resolves to.
 */
final class SpeechProbabilityEstimator {
    /**
     * The signal model the probabilities are derived from.
     */
    private final SignalModelEstimator signalModelEstimator = new SignalModelEstimator();

    /**
     * The prior probability of speech.
     */
    private float priorSpeechProb = .5f;

    /**
     * The per-bin speech probability.
     */
    private final float[] speechProbability = new float[BINS];

    /**
     * Updates the speech probability with one frame.
     *
     * @param numAnalyzedFrames         the index of the analyzed frame
     * @param priorSnr                  the prior SNR per bin
     * @param postSnr                   the posterior SNR per bin
     * @param conservativeNoiseSpectrum the conservative noise spectrum
     * @param signalSpectrum            the magnitude spectrum of the frame
     * @param signalSpectralSum         the sum of {@code signalSpectrum}
     * @param signalEnergy              the energy of the frame
     */
    void update(int numAnalyzedFrames, float[] priorSnr, float[] postSnr, float[] conservativeNoiseSpectrum,
                float[] signalSpectrum, float signalSpectralSum, float signalEnergy) {
        var model = signalModelEstimator;
        if (numAnalyzedFrames < LONG_STARTUP_PHASE_BLOCKS) {
            model.adjustNormalization(numAnalyzedFrames, signalEnergy);
        }
        model.update(priorSnr, postSnr, conservativeNoiseSpectrum, signalSpectrum, signalSpectralSum, signalEnergy);
        var widthPrior0 = 4.f;
        var widthPrior1 = 2.f * widthPrior0;
        var widthPrior = model.lrt < model.priorLrt ? widthPrior1 : widthPrior0;
        var indicator0 = 0.5f * (FloatMath.tanhf(widthPrior * (model.lrt - model.priorLrt)) + 1.f);
        widthPrior = model.spectralFlatness > model.flatnessThreshold ? widthPrior1 : widthPrior0;
        var indicator1 = 0.5f * (FloatMath.tanhf(1.f * widthPrior * (model.flatnessThreshold - model.spectralFlatness)) + 1.f);
        widthPrior = model.spectralDiff < model.templateDiffThreshold ? widthPrior1 : widthPrior0;
        var indicator2 = 0.5f * (FloatMath.tanhf(widthPrior * (model.spectralDiff - model.templateDiffThreshold)) + 1.f);
        var indPrior = model.lrtWeighting * indicator0 + model.flatnessWeighting * indicator1
                + model.differenceWeighting * indicator2;
        priorSpeechProb += 0.1f * (indPrior - priorSpeechProb);
        priorSpeechProb = FloatMath.max(FloatMath.min(priorSpeechProb, 1.f), 0.01f);
        var gainPrior = (1.f - priorSpeechProb) / (priorSpeechProb + 0.0001f);
        var avgLogLrt = model.avgLogLrt;
        for (var i = 0; i < BINS; ++i) {
            var invLrt = NsConstants.expApproximation(-avgLogLrt[i]);
            speechProbability[i] = 1.f / (1.f + gainPrior * invLrt);
        }
    }

    /**
     * Returns the prior probability of speech.
     *
     * @return the prior probability
     */
    float priorProbability() {
        return priorSpeechProb;
    }

    /**
     * Returns the per-bin speech probability.
     *
     * @return the live array, {@link NsConstants#BINS} bins
     */
    float[] probability() {
        return speechProbability;
    }
}
