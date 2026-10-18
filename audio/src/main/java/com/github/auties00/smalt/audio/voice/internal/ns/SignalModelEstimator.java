/* Ports webrtc-audio-processing 2.1 (modules/audio_processing/ns/signal_model_estimator.cc, signal_model.cc,
   prior_signal_model.cc, prior_signal_model_estimator.cc, histograms.cc). Copyright (c) 2011, The WebRTC project
   authors; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.voice.internal.ns;

import com.github.auties00.smalt.audio.voice.internal.FloatMath;

import java.util.Arrays;

import static com.github.auties00.smalt.audio.voice.internal.ns.NsConstants.BINS;
import static com.github.auties00.smalt.audio.voice.internal.ns.NsConstants.BIN_SIZE_LRT;
import static com.github.auties00.smalt.audio.voice.internal.ns.NsConstants.BIN_SIZE_SPEC_DIFF;
import static com.github.auties00.smalt.audio.voice.internal.ns.NsConstants.BIN_SIZE_SPEC_FLAT;
import static com.github.auties00.smalt.audio.voice.internal.ns.NsConstants.FEATURE_UPDATE_WINDOW_SIZE;
import static com.github.auties00.smalt.audio.voice.internal.ns.NsConstants.HISTOGRAM_SIZE;
import static com.github.auties00.smalt.audio.voice.internal.ns.NsConstants.LTR_FEATURE_THR;

/**
 * Tracks the speech features of the noise suppressor (the averaged likelihood ratio, the
 * spectral flatness and the spectral difference to the noise template) and periodically derives
 * the prior model (feature thresholds and weights) from their histograms.
 *
 * <p>The current features are {@link #lrt}, {@link #spectralFlatness}, {@link #spectralDiff} and
 * {@link #avgLogLrt}; the prior model is {@link #priorLrt}, {@link #flatnessThreshold},
 * {@link #templateDiffThreshold} and the three weightings.
 *
 * @implNote This implementation ports WebRTC's {@code signal_model_estimator.cc},
 * {@code signal_model.cc}, {@code prior_signal_model.cc},
 * {@code prior_signal_model_estimator.cc} and {@code histograms.cc} of
 * {@code modules/audio_processing/ns}, merged into one class because they share their state.
 */
final class SignalModelEstimator {
    /**
     * {@code 1 / BINS}.
     */
    private static final float ONE_BY_BINS = 1.f / BINS;

    /**
     * The averaged log likelihood ratio feature.
     */
    float lrt = LTR_FEATURE_THR;

    /**
     * The spectral difference feature.
     */
    float spectralDiff = 0.5f;

    /**
     * The spectral flatness feature.
     */
    float spectralFlatness = 0.5f;

    /**
     * The time-smoothed per-bin log likelihood ratio.
     */
    final float[] avgLogLrt = new float[BINS];

    /**
     * The prior model's likelihood-ratio threshold.
     */
    float priorLrt = LTR_FEATURE_THR;

    /**
     * The prior model's spectral flatness threshold.
     */
    float flatnessThreshold = .5f;

    /**
     * The prior model's spectral difference threshold.
     */
    float templateDiffThreshold = .5f;

    /**
     * The prior model's weight of the likelihood-ratio indicator.
     */
    float lrtWeighting = 1.f;

    /**
     * The prior model's weight of the spectral flatness indicator.
     */
    float flatnessWeighting = 0.f;

    /**
     * The prior model's weight of the spectral difference indicator.
     */
    float differenceWeighting = 0.f;

    /**
     * The normalization of the spectral difference.
     */
    private float diffNormalization = 0.f;

    /**
     * The signal energy accumulated over the current feature window.
     */
    private float signalEnergySum = 0.f;

    /**
     * The histogram of the likelihood-ratio feature.
     */
    private final int[] lrtHistogram = new int[HISTOGRAM_SIZE];

    /**
     * The histogram of the spectral flatness feature.
     */
    private final int[] flatnessHistogram = new int[HISTOGRAM_SIZE];

    /**
     * The histogram of the spectral difference feature.
     */
    private final int[] diffHistogram = new int[HISTOGRAM_SIZE];

    /**
     * The frames left before the prior model is next updated.
     */
    private int histogramAnalysisCounter = 500;

    /**
     * Creates an estimator in its initial state.
     */
    SignalModelEstimator() {
        Arrays.fill(avgLogLrt, LTR_FEATURE_THR);
    }

    /**
     * Updates the spectral difference normalization during the start-up phase.
     *
     * @param numAnalyzedFrames the index of the analyzed frame
     * @param signalEnergy the energy of the frame
     */
    void adjustNormalization(int numAnalyzedFrames, float signalEnergy) {
        diffNormalization *= numAnalyzedFrames;
        diffNormalization += signalEnergy;
        diffNormalization /= (numAnalyzedFrames + 1);
    }

    /**
     * Updates the features with one frame and, once per window, the prior model.
     *
     * @param priorSnr the prior SNR per bin
     * @param postSnr the posterior SNR per bin
     * @param conservativeNoiseSpectrum the conservative noise spectrum
     * @param signalSpectrum the magnitude spectrum of the frame
     * @param signalSpectralSum the sum of {@code signalSpectrum}
     * @param signalEnergy the energy of the frame
     */
    void update(float[] priorSnr, float[] postSnr, float[] conservativeNoiseSpectrum, float[] signalSpectrum,
                float signalSpectralSum, float signalEnergy) {
        updateSpectralFlatness(signalSpectrum, signalSpectralSum);
        var newSpectralDiff = computeSpectralDiff(conservativeNoiseSpectrum, signalSpectrum, signalSpectralSum);
        spectralDiff += 0.3f * (newSpectralDiff - spectralDiff);
        signalEnergySum += signalEnergy;
        if (--histogramAnalysisCounter > 0) {
            updateHistograms();
        } else {
            updatePriorModel();
            Arrays.fill(lrtHistogram, 0);
            Arrays.fill(flatnessHistogram, 0);
            Arrays.fill(diffHistogram, 0);
            histogramAnalysisCounter = FEATURE_UPDATE_WINDOW_SIZE;
            signalEnergySum = signalEnergySum / FEATURE_UPDATE_WINDOW_SIZE;
            diffNormalization = 0.5f * (signalEnergySum + diffNormalization);
            signalEnergySum = 0.f;
        }
        updateSpectralLrt(priorSnr, postSnr);
    }

    /**
     * Returns the normalized spectral difference between the frame and the noise template.
     *
     * @param conservativeNoiseSpectrum the conservative noise spectrum
     * @param signalSpectrum the magnitude spectrum of the frame
     * @param signalSpectralSum the sum of {@code signalSpectrum}
     * @return the spectral difference
     */
    private float computeSpectralDiff(float[] conservativeNoiseSpectrum, float[] signalSpectrum, float signalSpectralSum) {
        var noiseAverage = 0.f;
        for (var i = 0; i < BINS; ++i) {
            noiseAverage += conservativeNoiseSpectrum[i];
        }
        noiseAverage = noiseAverage * ONE_BY_BINS;
        var signalAverage = signalSpectralSum * ONE_BY_BINS;
        var covariance = 0.f;
        var noiseVariance = 0.f;
        var signalVariance = 0.f;
        for (var i = 0; i < BINS; ++i) {
            var signalDiff = signalSpectrum[i] - signalAverage;
            var noiseDiff = conservativeNoiseSpectrum[i] - noiseAverage;
            covariance += signalDiff * noiseDiff;
            noiseVariance += noiseDiff * noiseDiff;
            signalVariance += signalDiff * signalDiff;
        }
        covariance *= ONE_BY_BINS;
        noiseVariance *= ONE_BY_BINS;
        signalVariance *= ONE_BY_BINS;
        var spectralDiff = signalVariance - (covariance * covariance) / (noiseVariance + 0.0001f);
        return spectralDiff / (diffNormalization + 0.0001f);
    }

    /**
     * Updates the spectral flatness feature with the ratio of the geometric to the arithmetic
     * mean of the spectrum.
     *
     * @param signalSpectrum the magnitude spectrum of the frame
     * @param signalSpectralSum the sum of {@code signalSpectrum}
     */
    private void updateSpectralFlatness(float[] signalSpectrum, float signalSpectralSum) {
        var averaging = 0.3f;
        var avgSpectFlatnessNum = 0.f;
        for (var i = 1; i < BINS; ++i) {
            if (signalSpectrum[i] == 0.f) {
                spectralFlatness -= averaging * spectralFlatness;
                return;
            }
        }
        for (var i = 1; i < BINS; ++i) {
            avgSpectFlatnessNum += NsConstants.logApproximation(signalSpectrum[i]);
        }
        var avgSpectFlatnessDenom = signalSpectralSum - signalSpectrum[0];
        avgSpectFlatnessDenom = avgSpectFlatnessDenom * ONE_BY_BINS;
        avgSpectFlatnessNum = avgSpectFlatnessNum * ONE_BY_BINS;
        var spectralTmp = NsConstants.expApproximation(avgSpectFlatnessNum) / avgSpectFlatnessDenom;
        spectralFlatness += averaging * (spectralTmp - spectralFlatness);
    }

    /**
     * Updates the per-bin log likelihood ratios and their average.
     *
     * @param priorSnr the prior SNR per bin
     * @param postSnr the posterior SNR per bin
     */
    private void updateSpectralLrt(float[] priorSnr, float[] postSnr) {
        var avgLogLrt = this.avgLogLrt;
        for (var i = 0; i < BINS; ++i) {
            var tmp1 = 1.f + 2.f * priorSnr[i];
            var tmp2 = 2.f * priorSnr[i] / (tmp1 + 0.0001f);
            var besselTmp = (postSnr[i] + 1.f) * tmp2;
            avgLogLrt[i] += .5f * (besselTmp - NsConstants.logApproximation(tmp1) - avgLogLrt[i]);
        }
        var logLrtTimeAvgKSum = 0.f;
        for (var i = 0; i < BINS; ++i) {
            logLrtTimeAvgKSum += avgLogLrt[i];
        }
        lrt = logLrtTimeAvgKSum * ONE_BY_BINS;
    }

    /**
     * Adds the current features to their histograms.
     */
    private void updateHistograms() {
        var oneByBinSizeLrt = 1.f / BIN_SIZE_LRT;
        if (lrt < HISTOGRAM_SIZE * BIN_SIZE_LRT && lrt >= 0.f) {
            ++lrtHistogram[(int) (oneByBinSizeLrt * lrt)];
        }
        var oneByBinSizeSpecFlat = 1.f / BIN_SIZE_SPEC_FLAT;
        if (spectralFlatness < HISTOGRAM_SIZE * BIN_SIZE_SPEC_FLAT && spectralFlatness >= 0.f) {
            ++flatnessHistogram[(int) (spectralFlatness * oneByBinSizeSpecFlat)];
        }
        var oneByBinSizeSpecDiff = 1.f / BIN_SIZE_SPEC_DIFF;
        if (spectralDiff < HISTOGRAM_SIZE * BIN_SIZE_SPEC_DIFF && spectralDiff >= 0.f) {
            ++diffHistogram[(int) (spectralDiff * oneByBinSizeSpecDiff)];
        }
    }

    /**
     * Derives the prior model thresholds and weights from the feature histograms.
     */
    private void updatePriorModel() {
        var lowLrtFluctuations = updatePriorLrt();
        var flatnessPeak = firstOfTwoLargestPeaks(BIN_SIZE_SPEC_FLAT, flatnessHistogram);
        var spectralFlatnessPeakPosition = peakPosition;
        var spectralFlatnessPeakWeight = flatnessPeak;
        var diffPeak = firstOfTwoLargestPeaks(BIN_SIZE_SPEC_DIFF, diffHistogram);
        var spectralDiffPeakPosition = peakPosition;
        var spectralDiffPeakWeight = diffPeak;
        var useSpecFlat = spectralFlatnessPeakWeight < 0.3f * 500 || spectralFlatnessPeakPosition < 0.6f ? 0 : 1;
        var useSpecDiff = spectralDiffPeakWeight < 0.3f * 500 || lowLrtFluctuations ? 0 : 1;
        templateDiffThreshold = 1.2f * spectralDiffPeakPosition;
        templateDiffThreshold = FloatMath.min(1.f, FloatMath.max(0.16f, templateDiffThreshold));
        var oneByFeatureSum = 1.f / (1.f + useSpecFlat + useSpecDiff);
        lrtWeighting = oneByFeatureSum;
        if (useSpecFlat == 1) {
            flatnessThreshold = 0.9f * spectralFlatnessPeakPosition;
            flatnessThreshold = FloatMath.min(.95f, FloatMath.max(0.1f, flatnessThreshold));
            flatnessWeighting = oneByFeatureSum;
        } else {
            flatnessWeighting = 0.f;
        }
        if (useSpecDiff == 1) {
            differenceWeighting = oneByFeatureSum;
        } else {
            differenceWeighting = 0.f;
        }
    }

    /**
     * Updates the prior likelihood-ratio threshold from its histogram.
     *
     * @return whether the likelihood ratio fluctuates so little that the signal is likely noise
     */
    private boolean updatePriorLrt() {
        var average = 0.f;
        var averageCompl = 0.f;
        var averageSquared = 0.f;
        var count = 0;
        for (var i = 0; i < 10; ++i) {
            var binMid = (i + 0.5f) * BIN_SIZE_LRT;
            average += lrtHistogram[i] * binMid;
            count += lrtHistogram[i];
        }
        if (count > 0) {
            average = average / count;
        }
        for (var i = 0; i < HISTOGRAM_SIZE; ++i) {
            var binMid = (i + 0.5f) * BIN_SIZE_LRT;
            averageSquared += lrtHistogram[i] * binMid * binMid;
            averageCompl += lrtHistogram[i] * binMid;
        }
        var oneFeatureUpdateWindowSize = 1.f / FEATURE_UPDATE_WINDOW_SIZE;
        averageSquared = averageSquared * oneFeatureUpdateWindowSize;
        averageCompl = averageCompl * oneFeatureUpdateWindowSize;
        var lowLrtFluctuations = averageSquared - average * averageCompl < 0.05f;
        var maxLrt = 1.f;
        var minLrt = .2f;
        if (lowLrtFluctuations) {
            priorLrt = maxLrt;
        } else {
            priorLrt = FloatMath.min(maxLrt, FloatMath.max(minLrt, 1.2f * average));
        }
        return lowLrtFluctuations;
    }

    /**
     * The position of the peak {@link #firstOfTwoLargestPeaks(float, int[])} found last.
     */
    private float peakPosition;

    /**
     * Finds the first of the two largest peaks of a histogram, merging them when they are close
     * and comparable, and stores its position in {@link #peakPosition}.
     *
     * @param binSize the bin width of the histogram
     * @param histogram the bin counts
     * @return the weight of the peak
     */
    private int firstOfTwoLargestPeaks(float binSize, int[] histogram) {
        var peakValue = 0;
        var secondaryPeakValue = 0;
        var position = 0.f;
        var secondaryPeakPosition = 0.f;
        var peakWeight = 0;
        var secondaryPeakWeight = 0;
        for (var i = 0; i < HISTOGRAM_SIZE; ++i) {
            var binMid = (i + 0.5f) * binSize;
            if (histogram[i] > peakValue) {
                secondaryPeakValue = peakValue;
                secondaryPeakWeight = peakWeight;
                secondaryPeakPosition = position;
                peakValue = histogram[i];
                peakWeight = histogram[i];
                position = binMid;
            } else if (histogram[i] > secondaryPeakValue) {
                secondaryPeakValue = histogram[i];
                secondaryPeakWeight = histogram[i];
                secondaryPeakPosition = binMid;
            }
        }
        if ((Math.abs(secondaryPeakPosition - position) < 2 * binSize) && (secondaryPeakWeight > 0.5f * peakWeight)) {
            peakWeight += secondaryPeakWeight;
            position = 0.5f * (position + secondaryPeakPosition);
        }
        peakPosition = position;
        return peakWeight;
    }
}
