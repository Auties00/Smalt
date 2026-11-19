/* Ports webrtc-audio-processing 2.1 (modules/audio_processing/agc2/clipping_predictor.cc,
   clipping_predictor_level_buffer.cc, gain_map_internal.h). Copyright (c) 2021, The WebRTC project authors; BSD
   3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.voice.internal.agc2;

import com.github.auties00.smalt.audio.voice.internal.FloatMath;

/**
 * Predicts clipping of the capture signal from the level history of every channel, and estimates
 * the microphone volume step that avoids it.
 *
 * <p>The event predictor flags clipping when the latest frames peak above a threshold while their
 * crest factor dropped by a margin below a reference window's; the peak predictors project the peak
 * level of the latest frames from their mean level and the reference crest factor. Instances are
 * not thread-safe.
 *
 * @implNote This implementation ports {@code ClippingEventPredictor} and
 * {@code ClippingPeakPredictor} of WebRTC's {@code clipping_predictor.cc} with
 * {@code ClippingPredictorLevelBuffer}.
 */
public final class ClippingPredictor {
    /**
     * Predicts clipping events and steps by the default step.
     */
    public static final int CLIPPING_EVENT = 0;

    /**
     * Projects the peak and steps by its excess over full scale.
     */
    public static final int ADAPTIVE_STEP_PEAK = 1;

    /**
     * Projects the peak and steps by the default step.
     */
    public static final int FIXED_STEP_PEAK = 2;

    /**
     * The volume and gain mapping of the analog controllers, in dB per volume step
     * ({@code kGainMap}).
     */
    public static final int[] GAIN_MAP = {
            -56, -54, -52, -50, -48, -47, -45, -43, -42, -40, -38, -37, -35, -34, -33,
            -31, -30, -29, -27, -26, -25, -24, -23, -22, -20, -19, -18, -17, -16, -15,
            -14, -14, -13, -12, -11, -10, -9, -8, -8, -7, -6, -5, -5, -4, -3,
            -2, -2, -1, 0, 0, 1, 1, 2, 3, 3, 4, 4, 5, 5, 6,
            6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13,
            13, 14, 14, 15, 15, 15, 16, 16, 17, 17, 17, 18, 18, 18, 19,
            19, 19, 20, 20, 21, 21, 21, 22, 22, 22, 23, 23, 23, 24, 24,
            24, 24, 25, 25, 25, 26, 26, 26, 27, 27, 27, 28, 28, 28, 28,
            29, 29, 29, 30, 30, 30, 30, 31, 31, 31, 32, 32, 32, 32, 33,
            33, 33, 33, 34, 34, 34, 35, 35, 35, 35, 36, 36, 36, 36, 37,
            37, 37, 38, 38, 38, 38, 39, 39, 39, 39, 40, 40, 40, 40, 41,
            41, 41, 41, 42, 42, 42, 42, 43, 43, 43, 44, 44, 44, 44, 45,
            45, 45, 45, 46, 46, 46, 46, 47, 47, 47, 47, 48, 48, 48, 48,
            49, 49, 49, 49, 50, 50, 50, 50, 51, 51, 51, 51, 52, 52, 52,
            52, 53, 53, 53, 53, 54, 54, 54, 54, 55, 55, 55, 55, 56, 56,
            56, 56, 57, 57, 57, 57, 58, 58, 58, 58, 59, 59, 59, 59, 60,
            60, 60, 60, 61, 61, 61, 61, 62, 62, 62, 62, 63, 63, 63, 63,
            64};

    private static final int MAX_GAIN_CHANGE = 15;

    private final int mode;
    private final int windowLength;
    private final int referenceWindowLength;
    private final int referenceWindowDelay;
    private final float clippingThreshold;
    private final float crestFactorMargin;
    private final int capacity;
    private final float[][] averages;
    private final float[][] maxima;
    private final int[] tails;
    private final int[] sizes;
    private float metricAverage;
    private float metricMax;

    /**
     * Creates a predictor.
     *
     * @param numChannels the number of channels
     * @param mode {@link #CLIPPING_EVENT}, {@link #ADAPTIVE_STEP_PEAK} or {@link #FIXED_STEP_PEAK}
     * @param windowLength the number of latest frames examined
     * @param referenceWindowLength the number of frames of the reference window
     * @param referenceWindowDelay the delay of the reference window, in frames
     * @param clippingThreshold the peak threshold, in dBFS; the peak predictors truncate it to an
     * integer
     * @param crestFactorMargin the crest factor margin of the event predictor, in dB
     */
    public ClippingPredictor(int numChannels, int mode, int windowLength, int referenceWindowLength,
                             int referenceWindowDelay, float clippingThreshold, float crestFactorMargin) {
        this.mode = mode;
        this.windowLength = windowLength;
        this.referenceWindowLength = referenceWindowLength;
        this.referenceWindowDelay = referenceWindowDelay;
        this.clippingThreshold = mode == CLIPPING_EVENT ? clippingThreshold : (int) clippingThreshold;
        this.crestFactorMargin = crestFactorMargin;
        this.capacity = Math.max(1, referenceWindowDelay + referenceWindowLength);
        this.averages = new float[numChannels][capacity];
        this.maxima = new float[numChannels][capacity];
        this.tails = new int[numChannels];
        this.sizes = new int[numChannels];
        reset();
    }

    /**
     * Forgets the level history.
     */
    public void reset() {
        java.util.Arrays.fill(tails, -1);
        java.util.Arrays.fill(sizes, 0);
    }

    /**
     * Pushes the mean square and the peak of a frame of every channel.
     *
     * @param channels the full-band samples of every channel
     * @param numChannels the number of channels
     * @param samplesPerChannel the number of samples per channel
     */
    public void analyze(float[][] channels, int numChannels, int samplesPerChannel) {
        for (var ch = 0; ch < numChannels; ++ch) {
            var sumSquares = 0.0f;
            var peak = 0.0f;
            var samples = channels[ch];
            for (var i = 0; i < samplesPerChannel; i++) {
                var sample = samples[i];
                sumSquares += sample * sample;
                peak = FloatMath.max(Math.abs(sample), peak);
            }
            if (++tails[ch] == capacity) {
                tails[ch] = 0;
            }
            if (sizes[ch] < capacity) {
                sizes[ch]++;
            }
            averages[ch][tails[ch]] = sumSquares / (float) samplesPerChannel;
            maxima[ch][tails[ch]] = peak;
        }
    }

    /**
     * Estimates the volume step that avoids a predicted clipping ({@code EstimateClippedLevelStep}).
     *
     * @param channel the index of the capture channel
     * @param level the current volume
     * @param defaultStep the default step
     * @param minMicLevel the lowest volume
     * @param maxMicLevel the highest volume
     * @return the step, or {@code -1} when no clipping is predicted
     */
    public int estimateClippedLevelStep(int channel, int level, int defaultStep, int minMicLevel, int maxMicLevel) {
        if (level <= minMicLevel) {
            return -1;
        }
        if (mode == CLIPPING_EVENT) {
            if (predictClippingEvent(channel)) {
                var newLevel = Math.clamp(level - defaultStep, minMicLevel, maxMicLevel);
                var step = level - newLevel;
                if (step > 0) {
                    return step;
                }
            }
            return -1;
        }
        if (!computeMetrics(channel, referenceWindowDelay, referenceWindowLength)) {
            return -1;
        }
        var referenceCrestFactor = crestFactor();
        if (!computeMetrics(channel, 0, windowLength)
                || !(LevelController.floatS16ToDbfs(metricMax) > clippingThreshold)) {
            return -1;
        }
        var estimateDb = referenceCrestFactor + LevelController.floatS16ToDbfs((float) Math.sqrt(metricAverage));
        if (estimateDb > clippingThreshold) {
            int step;
            if (mode == FIXED_STEP_PEAK) {
                step = defaultStep;
            } else {
                var estimatedGainChange = Math.clamp(-(int) Math.ceil(estimateDb), -MAX_GAIN_CHANGE, 0);
                step = Math.max(level - computeVolumeUpdate(estimatedGainChange, level, minMicLevel, maxMicLevel),
                        defaultStep);
            }
            var newLevel = Math.clamp(level - step, minMicLevel, maxMicLevel);
            if (level > newLevel) {
                return level - newLevel;
            }
        }
        return -1;
    }

    private boolean predictClippingEvent(int channel) {
        if (!computeMetrics(channel, 0, windowLength)
                || !(LevelController.floatS16ToDbfs(metricMax) > clippingThreshold)) {
            return false;
        }
        var crestFactor = crestFactor();
        if (!computeMetrics(channel, referenceWindowDelay, referenceWindowLength)) {
            return false;
        }
        var referenceCrestFactor = crestFactor();
        return crestFactor < referenceCrestFactor - crestFactorMargin;
    }

    private float crestFactor() {
        return LevelController.floatS16ToDbfs(metricMax)
                - LevelController.floatS16ToDbfs((float) Math.sqrt(metricAverage));
    }

    private boolean computeMetrics(int channel, int delay, int numItems) {
        if (delay + numItems > sizes[channel]) {
            return false;
        }
        var sum = 0.0f;
        var max = 0.0f;
        for (var i = 0; i < numItems && i < sizes[channel]; ++i) {
            var idx = tails[channel] - delay - i;
            if (idx < 0) {
                idx += capacity;
            }
            sum += averages[channel][idx];
            max = Math.max(maxima[channel][idx], max);
        }
        metricAverage = sum / (float) numItems;
        metricMax = max;
        return true;
    }

    /**
     * Returns the volume whose gain differs by about {@code gainErrorDb} from that of a volume
     * ({@code ComputeVolumeUpdate}).
     *
     * @param gainErrorDb the gain change, in dB
     * @param inputVolume the volume
     * @param minInputVolume the lowest volume
     * @param maxInputVolume the highest volume
     * @return the new volume
     */
    public static int computeVolumeUpdate(int gainErrorDb, int inputVolume, int minInputVolume, int maxInputVolume) {
        if (gainErrorDb == 0) {
            return inputVolume;
        }
        var newVolume = inputVolume;
        if (gainErrorDb > 0) {
            while (GAIN_MAP[newVolume] - GAIN_MAP[inputVolume] < gainErrorDb && newVolume < maxInputVolume) {
                ++newVolume;
            }
        } else {
            while (GAIN_MAP[newVolume] - GAIN_MAP[inputVolume] > gainErrorDb && newVolume > minInputVolume) {
                --newVolume;
            }
        }
        return newVolume;
    }
}
