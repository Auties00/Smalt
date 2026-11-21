/* Ports webrtc-audio-processing 2.1 (modules/audio_processing/agc/agc_manager_direct.cc, agc.cc,
   loudness_histogram.cc, utility.cc). Copyright (c) 2013, The WebRTC project authors; BSD 3-Clause License.
   Modified for Smalt. */
package com.github.auties00.smalt.audio.voice.internal.agc;

import com.github.auties00.smalt.audio.voice.internal.DoubleMath;
import com.github.auties00.smalt.audio.voice.internal.FixedPointMath;
import com.github.auties00.smalt.audio.voice.internal.agc2.ClippingPredictor;

/**
 * Adapts the microphone volume and the compression gain of the legacy digital gain controller so
 * that the speech level reaches -18 dBFS, and lowers the volume when the capture signal clips.
 *
 * <p>Every channel runs its own controller on the lowest band of the processed capture signal: a
 * voice activity detector feeds a loudness histogram, and every second of voice the controller
 * splits the gap to the target between the compression gain (from 2 to 12 dB, more after clipping
 * lowered the volume ceiling) and the microphone volume. The lowest recommended volume wins, and
 * the compression gain of the channel that recommends it is applied. Clipping is detected (and
 * optionally predicted) on the full-band signal before processing. Instances are not
 * thread-safe.
 *
 * @implNote This implementation ports {@code AgcManagerDirect} with {@code MonoAgc}, {@code Agc}
 * and {@code LoudnessHistogram}, without the field trial that overrides the lowest volume and
 * without the histogram reporting.
 */
public final class AnalogGainManager {
    private static final int DEFAULT_COMPRESSION_GAIN = 7;
    private static final int MAX_MIC_LEVEL = 255;
    private static final int MIN_MIC_LEVEL = 12;

    private final int numChannels;
    private final boolean digitalAdaptive;
    private final int clippedLevelStep;
    private final float clippedRatioThreshold;
    private final int clippedWaitFrames;
    private final ClippingPredictor clippingPredictor;
    private final boolean useClippingPredictorStep;
    private final Mono[] channels;
    private final int[] newCompressions;
    private final short[] lowBand;
    private int framesSinceClipped;
    private boolean captureOutputUsed = true;
    private int recommendedInputVolume;
    private int channelControllingGain;

    /**
     * Creates a manager.
     *
     * @param numChannels the number of capture channels it controls
     * @param clippedLevelMin the lowest volume clipping lowers the volume to
     * @param clippedLevelStep the volume step on clipping
     * @param clippedRatioThreshold the share of clipped samples that counts as clipping
     * @param clippedWaitFrames the frames to wait after clipping before checking again
     * @param digitalAdaptive whether the compression gain adapts
     * @param clippingPredictor the clipping predictor, or {@code null} when prediction is off
     * @param usePredictedStep whether a predicted clipping lowers the volume by the predicted step
     */
    public AnalogGainManager(int numChannels, int clippedLevelMin, int clippedLevelStep, float clippedRatioThreshold,
                             int clippedWaitFrames, boolean digitalAdaptive, ClippingPredictor clippingPredictor,
                             boolean usePredictedStep) {
        this.numChannels = numChannels;
        this.digitalAdaptive = digitalAdaptive;
        this.clippedLevelStep = clippedLevelStep;
        this.clippedRatioThreshold = clippedRatioThreshold;
        this.clippedWaitFrames = clippedWaitFrames;
        this.clippingPredictor = clippingPredictor;
        this.useClippingPredictorStep = clippingPredictor != null && usePredictedStep;
        this.framesSinceClipped = clippedWaitFrames;
        this.channels = new Mono[numChannels];
        for (var ch = 0; ch < numChannels; ch++) {
            channels[ch] = new Mono(clippedLevelMin, !digitalAdaptive);
            channels[ch].initialize();
        }
        this.newCompressions = new int[numChannels];
        java.util.Arrays.fill(newCompressions, -1);
        this.lowBand = new short[160];
        aggregateChannelLevels();
    }

    /**
     * {@return the target level the legacy digital gain controller runs with, in dB below full scale}
     */
    public int digitalTargetLevelDbfs() {
        return digitalAdaptive ? 2 : 0;
    }

    /**
     * {@return the initial compression gain of the legacy digital gain controller, in dB}
     */
    public int digitalCompressionGainDb() {
        return digitalAdaptive ? DEFAULT_COMPRESSION_GAIN : 0;
    }

    /**
     * {@return whether the legacy digital gain controller limits}
     */
    public boolean digitalLimiter() {
        return digitalAdaptive;
    }

    /**
     * Reports the volume in effect ({@code set_stream_analog_level}).
     *
     * @param level the volume, from 0 to 255
     */
    public void setStreamAnalogLevel(int level) {
        for (var channel : channels) {
            channel.recommendedInputVolume = level;
        }
        aggregateChannelLevels();
    }

    /**
     * {@return the recommended volume}
     */
    public int recommendedAnalogLevel() {
        return recommendedInputVolume;
    }

    /**
     * Records whether the processed capture is used.
     *
     * @param used whether the capture output is used
     */
    public void handleCaptureOutputUsedChange(boolean used) {
        for (var channel : channels) {
            channel.handleCaptureOutputUsedChange(used);
        }
        captureOutputUsed = used;
    }

    /**
     * Detects and predicts clipping on the full-band capture before processing
     * ({@code AnalyzePreProcess}).
     *
     * @param audio the full-band samples of every channel, in the 16-bit range
     * @param samplesPerChannel the number of samples per channel
     */
    public void analyzePreProcess(float[][] audio, int samplesPerChannel) {
        aggregateChannelLevels();
        if (!captureOutputUsed) {
            return;
        }
        if (clippingPredictor != null) {
            clippingPredictor.analyze(audio, numChannels, samplesPerChannel);
        }
        var numClipped = 0;
        for (var ch = 0; ch < numChannels; ++ch) {
            var numClippedInChannel = 0;
            var samples = audio[ch];
            for (var i = 0; i < samplesPerChannel; ++i) {
                if (samples[i] >= 32767.0f || samples[i] <= -32768.0f) {
                    ++numClippedInChannel;
                }
            }
            numClipped = Math.max(numClipped, numClippedInChannel);
        }
        var clippedRatio = (float) numClipped / samplesPerChannel;
        if (framesSinceClipped < clippedWaitFrames) {
            ++framesSinceClipped;
            return;
        }
        var clippingDetected = clippedRatio > clippedRatioThreshold;
        var clippingPredicted = false;
        var predictedStep = 0;
        if (clippingPredictor != null) {
            for (var ch = 0; ch < numChannels; ++ch) {
                var step = clippingPredictor.estimateClippedLevelStep(ch, recommendedInputVolume, clippedLevelStep,
                        MIN_MIC_LEVEL, MAX_MIC_LEVEL);
                if (step >= 0) {
                    predictedStep = Math.max(predictedStep, step);
                    clippingPredicted = true;
                }
            }
        }
        var step = clippedLevelStep;
        if (clippingPredicted) {
            predictedStep = Math.max(predictedStep, clippedLevelStep);
            if (useClippingPredictorStep) {
                step = predictedStep;
            }
        }
        if (clippingDetected || (clippingPredicted && useClippingPredictorStep)) {
            for (var channel : channels) {
                channel.handleClipping(step);
            }
            framesSinceClipped = 0;
            if (clippingPredictor != null) {
                clippingPredictor.reset();
            }
        }
        aggregateChannelLevels();
    }

    /**
     * Updates the volume and the compression gain from the processed lowest band ({@code Process}).
     *
     * @param bands the bands of every channel, in the 16-bit range; band 0 is read
     */
    public void process(float[][][] bands) {
        aggregateChannelLevels();
        if (!captureOutputUsed) {
            return;
        }
        for (var ch = 0; ch < numChannels; ++ch) {
            var band = bands[ch][0];
            for (var i = 0; i < lowBand.length; i++) {
                lowBand[i] = FixedPointMath.floatS16ToS16(band[i]);
            }
            channels[ch].process(lowBand);
            newCompressions[ch] = channels[ch].newCompression;
        }
        aggregateChannelLevels();
    }

    /**
     * Returns the compression gain to apply after the last {@link #process(float[][][])}
     * ({@code GetDigitalComressionGain}).
     *
     * @return the gain in dB, or {@code -1} when it does not change
     */
    public int digitalCompressionGain() {
        return newCompressions[channelControllingGain];
    }

    private void aggregateChannelLevels() {
        var recommended = channels[0].recommendedInputVolume;
        channelControllingGain = 0;
        for (var ch = 1; ch < channels.length; ++ch) {
            var level = channels[ch].recommendedInputVolume;
            if (level < recommended) {
                recommended = level;
                channelControllingGain = ch;
            }
        }
        recommendedInputVolume = recommended;
    }

    /**
     * Controls the volume and compression gain from one channel ({@code MonoAgc} with
     * {@code Agc}).
     */
    private static final class Mono {
        private static final int LEVEL_QUANTIZATION_SLACK = 25;
        private static final int MAX_COMPRESSION_GAIN = 12;
        private static final int MIN_COMPRESSION_GAIN = 2;
        private static final float COMPRESSION_GAIN_STEP = 0.05f;
        private static final int MAX_RESIDUAL_GAIN_CHANGE = 15;
        private static final int SURPLUS_COMPRESSION_GAIN = 6;
        private static final int NUM_ANALYSIS_FRAMES = 100;
        private static final double ACTIVITY_THRESHOLD = 0.3;
        private static final double LOG10 = 2.30258509299;
        private static final double LINEAR_TO_DB_SCALE = 20.0 / LOG10;
        private static final double LINEAR_TO_LOUDNESS_SCALE = 13.4 / LOG10;
        private static final double TARGET_LEVEL_LOUDNESS = (90 + -18) * LINEAR_TO_LOUDNESS_SCALE / LINEAR_TO_DB_SCALE;

        private final boolean disableDigitalAdaptive;
        private final int clippedLevelMin;
        private final VoiceDetector vad = new VoiceDetector();
        private final LoudnessHistogram histogram = new LoudnessHistogram(NUM_ANALYSIS_FRAMES);
        private int level;
        private int maxLevel = MAX_MIC_LEVEL;
        private int maxCompressionGain = MAX_COMPRESSION_GAIN;
        private int targetCompression = DEFAULT_COMPRESSION_GAIN;
        private int compression = DEFAULT_COMPRESSION_GAIN;
        private float compressionAccumulator = DEFAULT_COMPRESSION_GAIN;
        private boolean captureOutputUsed = true;
        private boolean checkVolumeOnNextProcess = true;
        private boolean startup = true;
        private int recommendedInputVolume;
        private int newCompression = -1;
        private boolean firstFrame = true;

        Mono(int clippedLevelMin, boolean disableDigitalAdaptive) {
            this.clippedLevelMin = clippedLevelMin;
            this.disableDigitalAdaptive = disableDigitalAdaptive;
        }

        void initialize() {
            maxLevel = MAX_MIC_LEVEL;
            maxCompressionGain = MAX_COMPRESSION_GAIN;
            targetCompression = disableDigitalAdaptive ? 0 : DEFAULT_COMPRESSION_GAIN;
            compression = disableDigitalAdaptive ? 0 : targetCompression;
            compressionAccumulator = compression;
            captureOutputUsed = true;
            checkVolumeOnNextProcess = true;
            firstFrame = true;
        }

        void process(short[] audio) {
            newCompression = -1;
            if (checkVolumeOnNextProcess) {
                checkVolumeOnNextProcess = false;
                checkVolumeAndReset();
            }
            vad.process(audio);
            for (var i = 0; i < vad.frames(); ++i) {
                histogram.update(vad.rms(i), vad.probability(i));
            }
            if (histogram.numUpdates() >= NUM_ANALYSIS_FRAMES
                    && histogram.audioContent() >= NUM_ANALYSIS_FRAMES * ACTIVITY_THRESHOLD) {
                var loudness = linearToLoudness(histogram.currentRms());
                var rmsError = (int) Math.floor(loudnessToDb(TARGET_LEVEL_LOUDNESS - loudness) + 0.5);
                histogram.reset();
                updateGain(rmsError);
            }
            if (!disableDigitalAdaptive) {
                updateCompressor();
            }
            firstFrame = false;
        }

        private static double loudnessToDb(double loudness) {
            return loudness * LINEAR_TO_DB_SCALE / LINEAR_TO_LOUDNESS_SCALE;
        }

        private static double linearToLoudness(double rms) {
            if (rms == 0) {
                return -15;
            }
            return LINEAR_TO_LOUDNESS_SCALE * DoubleMath.log(rms);
        }

        void handleClipping(int step) {
            setMaxLevel(Math.max(clippedLevelMin, maxLevel - step));
            if (level > clippedLevelMin) {
                setLevel(Math.max(clippedLevelMin, level - step));
                histogram.reset();
                firstFrame = false;
            }
        }

        private void setLevel(int newLevel) {
            var voeLevel = recommendedInputVolume;
            if (voeLevel == 0 || voeLevel < 0 || voeLevel > MAX_MIC_LEVEL) {
                return;
            }
            if (voeLevel > level + LEVEL_QUANTIZATION_SLACK || voeLevel < level - LEVEL_QUANTIZATION_SLACK) {
                level = voeLevel;
                if (level > maxLevel) {
                    setMaxLevel(level);
                }
                histogram.reset();
                firstFrame = false;
                return;
            }
            newLevel = Math.min(newLevel, maxLevel);
            if (newLevel == level) {
                return;
            }
            recommendedInputVolume = newLevel;
            level = newLevel;
        }

        private void setMaxLevel(int newMaxLevel) {
            maxLevel = newMaxLevel;
            var surplus = (float) Math.floor((1.f * MAX_MIC_LEVEL - maxLevel) / (MAX_MIC_LEVEL - clippedLevelMin)
                    * SURPLUS_COMPRESSION_GAIN + 0.5f);
            maxCompressionGain = (int) (MAX_COMPRESSION_GAIN + surplus);
        }

        void handleCaptureOutputUsedChange(boolean used) {
            if (captureOutputUsed == used) {
                return;
            }
            captureOutputUsed = used;
            if (used) {
                checkVolumeOnNextProcess = true;
            }
        }

        private void checkVolumeAndReset() {
            var volume = recommendedInputVolume;
            if (volume == 0 && !startup) {
                return;
            }
            if (volume < 0 || volume > MAX_MIC_LEVEL) {
                return;
            }
            if (volume < MIN_MIC_LEVEL) {
                volume = MIN_MIC_LEVEL;
                recommendedInputVolume = volume;
            }
            histogram.reset();
            level = volume;
            startup = false;
            firstFrame = true;
        }

        private void updateGain(int rmsErrorDb) {
            var rmsError = rmsErrorDb + MIN_COMPRESSION_GAIN;
            var rawCompression = Math.clamp(rmsError, MIN_COMPRESSION_GAIN, maxCompressionGain);
            if ((rawCompression == maxCompressionGain && targetCompression == maxCompressionGain - 1)
                    || (rawCompression == MIN_COMPRESSION_GAIN && targetCompression == MIN_COMPRESSION_GAIN + 1)) {
                targetCompression = rawCompression;
            } else {
                targetCompression = (rawCompression - targetCompression) / 2 + targetCompression;
            }
            var residualGain = Math.clamp(rmsError - rawCompression, -MAX_RESIDUAL_GAIN_CHANGE,
                    MAX_RESIDUAL_GAIN_CHANGE);
            if (residualGain == 0) {
                return;
            }
            var oldLevel = level;
            setLevel(levelFromGainError(residualGain, level));
            if (oldLevel != level) {
                histogram.reset();
            }
        }

        private static int levelFromGainError(int gainError, int level) {
            return ClippingPredictor.computeVolumeUpdate(gainError, level, MIN_MIC_LEVEL, MAX_MIC_LEVEL);
        }

        private void updateCompressor() {
            if (compression == targetCompression) {
                return;
            }
            if (targetCompression > compression) {
                compressionAccumulator += COMPRESSION_GAIN_STEP;
            } else {
                compressionAccumulator -= COMPRESSION_GAIN_STEP;
            }
            var updated = compression;
            var nearestNeighbor = (int) Math.floor(compressionAccumulator + 0.5);
            if (Math.abs(compressionAccumulator - nearestNeighbor) < COMPRESSION_GAIN_STEP / 2) {
                updated = nearestNeighbor;
            }
            if (updated != compression) {
                compression = updated;
                compressionAccumulator = updated;
                newCompression = compression;
            }
        }
    }

    /**
     * Accumulates the voice-weighted loudness of the latest frames ({@code LoudnessHistogram}).
     */
    private static final class LoudnessHistogram {
        private static final double[] BIN_CENTERS = {
                7.59621091765857e-02, 9.02036021061016e-02, 1.07115112009343e-01,
                1.27197217770508e-01, 1.51044347572047e-01, 1.79362373905283e-01,
                2.12989507320644e-01, 2.52921107370304e-01, 3.00339145144454e-01,
                3.56647189489147e-01, 4.23511952494003e-01, 5.02912623991786e-01,
                5.97199455365749e-01, 7.09163326739184e-01, 8.42118356728544e-01,
                1.00000000000000e+00, 1.18748153630660e+00, 1.41011239906908e+00,
                1.67448243801153e+00, 1.98841697800836e+00, 2.36120844786349e+00,
                2.80389143520905e+00, 3.32956930911896e+00, 3.95380207843188e+00,
                4.69506696634852e+00, 5.57530533426190e+00, 6.62057214370769e+00,
                7.86180718043869e+00, 9.33575086877358e+00, 1.10860317842269e+01,
                1.31644580546776e+01, 1.56325508754123e+01, 1.85633655299256e+01,
                2.20436538184971e+01, 2.61764319021997e+01, 3.10840295702492e+01,
                3.69117111886792e+01, 4.38319755100383e+01, 5.20496616180135e+01,
                6.18080121423973e+01, 7.33958732149108e+01, 8.71562442838066e+01,
                1.03496430860848e+02, 1.22900100720889e+02, 1.45941600416277e+02,
                1.73302955873365e+02, 2.05794060286978e+02, 2.44376646872353e+02,
                2.90192756065437e+02, 3.44598539797631e+02, 4.09204403447902e+02,
                4.85922673669740e+02, 5.77024203055553e+02, 6.85205587130498e+02,
                8.13668983291589e+02, 9.66216894324125e+02, 1.14736472207740e+03,
                1.36247442287647e+03, 1.61791322085579e+03, 1.92124207711260e+03,
                2.28143949334655e+03, 2.70916727454970e+03, 3.21708611729384e+03,
                3.82023036499473e+03, 4.53645302286906e+03, 5.38695420497926e+03,
                6.39690865534207e+03, 7.59621091765857e+03, 9.02036021061016e+03,
                1.07115112009343e+04, 1.27197217770508e+04, 1.51044347572047e+04,
                1.79362373905283e+04, 2.12989507320644e+04, 2.52921107370304e+04,
                3.00339145144454e+04, 3.56647189489147e+04};
        private static final int HIST_SIZE = BIN_CENTERS.length;
        private static final double PROB_Q_DOMAIN = 1024.0;
        private static final double LOG_DOMAIN_MIN_BIN_CENTER = -2.57752062648587;
        private static final double LOG_DOMAIN_STEP_SIZE_INVERSE = 5.81954605750359;
        private static final int TRANSIENT_WIDTH_THRESHOLD = 7;
        private static final int LOW_PROB_THRESHOLD_Q10 = (int) (0.2 * PROB_Q_DOMAIN);

        private int numUpdates;
        private long audioContentQ10;
        private final long[] binCountQ10 = new long[HIST_SIZE];
        private final int[] activityProbability;
        private final int[] histBinIndex;
        private int bufferIndex;
        private boolean bufferIsFull;
        private final int lenCircularBuffer;
        private int lenHighActivity;

        LoudnessHistogram(int windowSize) {
            this.activityProbability = new int[windowSize];
            this.histBinIndex = new int[windowSize];
            this.lenCircularBuffer = windowSize;
        }

        void update(double rms, double activityProbability) {
            if (lenCircularBuffer > 0) {
                removeOldestEntryAndUpdate();
            }
            var histIndex = binIndex(rms);
            var probQ10 = (int) (short) (int) Math.floor(activityProbability * PROB_Q_DOMAIN);
            insertNewestEntryAndUpdate(probQ10, histIndex);
        }

        private void removeOldestEntryAndUpdate() {
            if (!bufferIsFull) {
                return;
            }
            updateHist(-activityProbability[bufferIndex], histBinIndex[bufferIndex]);
        }

        private void removeTransient() {
            var index = bufferIndex > 0 ? bufferIndex - 1 : lenCircularBuffer - 1;
            while (lenHighActivity > 0) {
                updateHist(-activityProbability[index], histBinIndex[index]);
                activityProbability[index] = 0;
                index = index > 0 ? index - 1 : lenCircularBuffer - 1;
                lenHighActivity--;
            }
        }

        private void insertNewestEntryAndUpdate(int activityProbQ10, int histIndex) {
            if (lenCircularBuffer > 0) {
                if (activityProbQ10 <= LOW_PROB_THRESHOLD_Q10) {
                    activityProbQ10 = 0;
                    if (lenHighActivity <= TRANSIENT_WIDTH_THRESHOLD) {
                        removeTransient();
                    }
                    lenHighActivity = 0;
                } else if (lenHighActivity <= TRANSIENT_WIDTH_THRESHOLD) {
                    lenHighActivity++;
                }
                activityProbability[bufferIndex] = activityProbQ10;
                histBinIndex[bufferIndex] = histIndex;
                bufferIndex++;
                if (bufferIndex >= lenCircularBuffer) {
                    bufferIndex = 0;
                    bufferIsFull = true;
                }
            }
            numUpdates++;
            if (numUpdates < 0) {
                numUpdates--;
            }
            updateHist(activityProbQ10, histIndex);
        }

        private void updateHist(int activityProbQ10, int histIndex) {
            binCountQ10[histIndex] += activityProbQ10;
            audioContentQ10 += activityProbQ10;
        }

        double audioContent() {
            return audioContentQ10 / PROB_Q_DOMAIN;
        }

        int numUpdates() {
            return numUpdates;
        }

        void reset() {
            java.util.Arrays.fill(binCountQ10, 0);
            audioContentQ10 = 0;
            numUpdates = 0;
            bufferIndex = 0;
            bufferIsFull = false;
            lenHighActivity = 0;
        }

        private static int binIndex(double rms) {
            if (rms <= BIN_CENTERS[0]) {
                return 0;
            } else if (rms >= BIN_CENTERS[HIST_SIZE - 1]) {
                return HIST_SIZE - 1;
            }
            var rmsLog = DoubleMath.log(rms);
            var index = (int) Math.floor((rmsLog - LOG_DOMAIN_MIN_BIN_CENTER) * LOG_DOMAIN_STEP_SIZE_INVERSE);
            var b = 0.5 * (BIN_CENTERS[index] + BIN_CENTERS[index + 1]);
            return rms > b ? index + 1 : index;
        }

        double currentRms() {
            if (audioContentQ10 > 0) {
                var pTotalInverse = 1. / (double) audioContentQ10;
                var meanVal = 0.0;
                for (var n = 0; n < HIST_SIZE; n++) {
                    var p = (double) binCountQ10[n] * pTotalInverse;
                    meanVal += p * BIN_CENTERS[n];
                }
                return meanVal;
            }
            return BIN_CENTERS[0];
        }
    }
}
