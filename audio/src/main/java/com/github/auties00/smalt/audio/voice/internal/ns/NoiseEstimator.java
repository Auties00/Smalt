package com.github.auties00.smalt.audio.voice.internal.ns;

import com.github.auties00.smalt.audio.voice.internal.FloatMath;

import static com.github.auties00.smalt.audio.voice.internal.ns.NsConstants.BINS;
import static com.github.auties00.smalt.audio.voice.internal.ns.NsConstants.SHORT_STARTUP_PHASE_BLOCKS;

/**
 * Estimates the noise spectrum from the quantile estimate, a parametric white and pink noise
 * model during start-up, and the speech probability.
 *
 * @implNote This implementation ports WebRTC's
 * {@code modules/audio_processing/ns/noise_estimator.cc}.
 */
final class NoiseEstimator {
    /**
     * The natural logarithms of the bin indices from 5 to 128; the first five entries are
     * zero.
     *
     * @implNote This implementation copies {@code log_table} of {@code noise_estimator.cc}.
     */
    private static final float[] LOG_TABLE = {
            0.f, 0.f, 0.f, 0.f, 0.f, 1.609438f, 1.791759f,
            1.945910f, 2.079442f, 2.197225f, 2.302585f, 2.397895f, 2.484907f, 2.564949f,
            2.639057f, 2.708050f, 2.772589f, 2.833213f, 2.890372f, 2.944439f, 2.995732f,
            3.044522f, 3.091043f, 3.135494f, 3.178054f, 3.218876f, 3.258097f, 3.295837f,
            3.332205f, 3.367296f, 3.401197f, 3.433987f, 3.465736f, 3.496507f, 3.526361f,
            3.555348f, 3.583519f, 3.610918f, 3.637586f, 3.663562f, 3.688879f, 3.713572f,
            3.737669f, 3.761200f, 3.784190f, 3.806663f, 3.828641f, 3.850147f, 3.871201f,
            3.891820f, 3.912023f, 3.931826f, 3.951244f, 3.970292f, 3.988984f, 4.007333f,
            4.025352f, 4.043051f, 4.060443f, 4.077538f, 4.094345f, 4.110874f, 4.127134f,
            4.143135f, 4.158883f, 4.174387f, 4.189655f, 4.204693f, 4.219508f, 4.234107f,
            4.248495f, 4.262680f, 4.276666f, 4.290460f, 4.304065f, 4.317488f, 4.330733f,
            4.343805f, 4.356709f, 4.369448f, 4.382027f, 4.394449f, 4.406719f, 4.418841f,
            4.430817f, 4.442651f, 4.454347f, 4.465908f, 4.477337f, 4.488636f, 4.499810f,
            4.510859f, 4.521789f, 4.532599f, 4.543295f, 4.553877f, 4.564348f, 4.574711f,
            4.584968f, 4.595119f, 4.605170f, 4.615121f, 4.624973f, 4.634729f, 4.644391f,
            4.653960f, 4.663439f, 4.672829f, 4.682131f, 4.691348f, 4.700480f, 4.709530f,
            4.718499f, 4.727388f, 4.736198f, 4.744932f, 4.753591f, 4.762174f, 4.770685f,
            4.779124f, 4.787492f, 4.795791f, 4.804021f, 4.812184f, 4.820282f, 4.828314f,
            4.836282f, 4.844187f, 4.852030f
    };

    /**
     * The first bin of the start-up pink noise fit.
     */
    private static final int START_BAND = 5;

    /**
     * The suppression level tuning.
     */
    private final SuppressionParams params;

    /**
     * The quantile estimator feeding the noise estimate.
     */
    private final QuantileNoiseEstimator quantileEstimator = new QuantileNoiseEstimator();

    /**
     * The accumulated white noise level of the start-up model.
     */
    private float whiteNoiseLevel;

    /**
     * The accumulated pink noise numerator of the start-up model.
     */
    private float pinkNoiseNumerator;

    /**
     * The accumulated pink noise exponent of the start-up model.
     */
    private float pinkNoiseExp;

    /**
     * The noise spectrum of the previous frame.
     */
    private final float[] prevNoiseSpectrum = new float[BINS];

    /**
     * The conservative noise spectrum, updated only in frames unlikely to hold speech.
     */
    private final float[] conservativeNoiseSpectrum = new float[BINS];

    /**
     * The parametric start-up noise spectrum.
     */
    private final float[] parametricNoiseSpectrum = new float[BINS];

    /**
     * The current noise spectrum estimate.
     */
    private final float[] noiseSpectrum = new float[BINS];

    /**
     * Creates an estimator in its initial state.
     *
     * @param params the suppression level tuning
     */
    NoiseEstimator(SuppressionParams params) {
        this.params = params;
    }

    /**
     * Saves the current estimate as the previous one before a frame is analyzed.
     */
    void prepareAnalysis() {
        System.arraycopy(noiseSpectrum, 0, prevNoiseSpectrum, 0, BINS);
    }

    /**
     * Runs the first step of the update: the quantile estimate, blended with the parametric
     * model during the short start-up phase.
     *
     * @param numAnalyzedFrames the index of the analyzed frame
     * @param signalSpectrum    the magnitude spectrum of the frame
     * @param signalSpectralSum the sum of {@code signalSpectrum}
     */
    void preUpdate(int numAnalyzedFrames, float[] signalSpectrum, float signalSpectralSum) {
        quantileEstimator.estimate(signalSpectrum, noiseSpectrum);
        if (numAnalyzedFrames < SHORT_STARTUP_PHASE_BLOCKS) {
            var sumLogILogMagn = 0.f;
            var sumLogI = 0.f;
            var sumLogISquare = 0.f;
            var sumLogMagn = 0.f;
            for (var i = START_BAND; i < BINS; ++i) {
                var logI = LOG_TABLE[i];
                sumLogI += logI;
                sumLogISquare += logI * logI;
                var logSignal = NsConstants.logApproximation(signalSpectrum[i]);
                sumLogMagn += logSignal;
                sumLogILogMagn += logI * logSignal;
            }
            var oneByBins = 1.f / BINS;
            whiteNoiseLevel += signalSpectralSum * oneByBins * params.overSubtractionFactor();
            var denom = sumLogISquare * (BINS - START_BAND) - sumLogI * sumLogI;
            var num = sumLogISquare * sumLogMagn - sumLogI * sumLogILogMagn;
            var pinkNoiseAdjustment = num / denom;
            pinkNoiseAdjustment = FloatMath.max(pinkNoiseAdjustment, 0.f);
            pinkNoiseNumerator += pinkNoiseAdjustment;
            num = sumLogI * sumLogMagn - (BINS - START_BAND) * sumLogILogMagn;
            pinkNoiseAdjustment = num / denom;
            pinkNoiseAdjustment = FloatMath.max(FloatMath.min(pinkNoiseAdjustment, 1.f), 0.f);
            pinkNoiseExp += pinkNoiseAdjustment;
            var oneByNumAnalyzedFramesPlus1 = 1.f / (numAnalyzedFrames + 1.f);
            var parametricExp = 0.f;
            var parametricNum = 0.f;
            if (pinkNoiseExp > 0.f) {
                parametricNum = NsConstants.expApproximation(pinkNoiseNumerator * oneByNumAnalyzedFramesPlus1);
                parametricNum *= numAnalyzedFrames + 1.f;
                parametricExp = pinkNoiseExp * oneByNumAnalyzedFramesPlus1;
            }
            var oneByShortStartupPhaseBlocks = 1.f / SHORT_STARTUP_PHASE_BLOCKS;
            for (var i = 0; i < BINS; ++i) {
                if (pinkNoiseExp == 0.f) {
                    parametricNoiseSpectrum[i] = whiteNoiseLevel;
                } else {
                    float useBand = i < START_BAND ? START_BAND : i;
                    var parametricDenom = NsConstants.powApproximation(useBand, parametricExp);
                    parametricNoiseSpectrum[i] = parametricNum / parametricDenom;
                }
            }
            for (var i = 0; i < BINS; ++i) {
                noiseSpectrum[i] *= numAnalyzedFrames;
                var tmp = parametricNoiseSpectrum[i] * (SHORT_STARTUP_PHASE_BLOCKS - numAnalyzedFrames);
                noiseSpectrum[i] += tmp * oneByNumAnalyzedFramesPlus1;
                noiseSpectrum[i] *= oneByShortStartupPhaseBlocks;
            }
        }
    }

    /**
     * Runs the second step of the update, which smooths the noise estimate with a time constant
     * that grows with the speech probability.
     *
     * @param speechProbability the per-bin speech probability
     * @param signalSpectrum    the magnitude spectrum of the frame
     */
    void postUpdate(float[] speechProbability, float[] signalSpectrum) {
        var noiseUpdate = 0.9f;
        var gamma = noiseUpdate;
        for (var i = 0; i < BINS; ++i) {
            var probSpeech = speechProbability[i];
            var probNonSpeech = 1.f - probSpeech;
            var noiseUpdateTmp = gamma * prevNoiseSpectrum[i]
                    + (1.f - gamma) * (probNonSpeech * signalSpectrum[i] + probSpeech * prevNoiseSpectrum[i]);
            var gammaOld = gamma;
            var probRange = .2f;
            gamma = probSpeech > probRange ? .99f : noiseUpdate;
            if (probSpeech < probRange) {
                conservativeNoiseSpectrum[i] += 0.05f * (signalSpectrum[i] - conservativeNoiseSpectrum[i]);
            }
            if (gamma == gammaOld) {
                noiseSpectrum[i] = noiseUpdateTmp;
            } else {
                noiseSpectrum[i] = gamma * prevNoiseSpectrum[i]
                        + (1.f - gamma) * (probNonSpeech * signalSpectrum[i] + probSpeech * prevNoiseSpectrum[i]);
                noiseSpectrum[i] = FloatMath.min(noiseSpectrum[i], noiseUpdateTmp);
            }
        }
    }

    /**
     * Returns the current noise spectrum estimate.
     *
     * @return the live array, {@link NsConstants#BINS} bins
     */
    float[] noiseSpectrum() {
        return noiseSpectrum;
    }

    /**
     * Returns the noise spectrum of the previous frame.
     *
     * @return the live array, {@link NsConstants#BINS} bins
     */
    float[] prevNoiseSpectrum() {
        return prevNoiseSpectrum;
    }

    /**
     * Returns the parametric start-up noise spectrum.
     *
     * @return the live array, {@link NsConstants#BINS} bins
     */
    float[] parametricNoiseSpectrum() {
        return parametricNoiseSpectrum;
    }

    /**
     * Returns the conservative noise spectrum.
     *
     * @return the live array, {@link NsConstants#BINS} bins
     */
    float[] conservativeNoiseSpectrum() {
        return conservativeNoiseSpectrum;
    }
}
