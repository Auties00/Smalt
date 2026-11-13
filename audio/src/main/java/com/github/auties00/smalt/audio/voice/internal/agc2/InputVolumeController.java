/* Ports webrtc-audio-processing 2.1 (modules/audio_processing/agc2/input_volume_controller.cc). Copyright (c)
   2013, The WebRTC project authors; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.voice.internal.agc2;

/**
 * Recommends the microphone volume that keeps the speech level of the capture signal in a target
 * range and lowers it when the signal clips or is predicted to clip.
 *
 * <p>Each channel runs its own controller and the lowest recommendation wins. The controller uses
 * WebRTC's default configuration: volumes from 20, clipping handling that steps by 15 down to 70
 * after 10% of clipped samples and then waits 300 frames, the clipping event predictor, the
 * -50 to -30 dBFS target range and updates every 100 frames when 60% of them have a speech
 * probability of at least 0.7. Instances are not thread-safe.
 *
 * @implNote This implementation ports {@code InputVolumeController} and
 * {@code MonoInputVolumeController} with the default {@code InputVolumeController::Config}; the
 * histogram reporting is left out.
 */
final class InputVolumeController {
    private static final int VOLUME_QUANTIZATION_SLACK = 25;
    private static final int MAX_INPUT_VOLUME = 255;
    private static final int MAX_ABS_RMS_ERROR_DBFS = 15;
    private static final int MIN_INPUT_VOLUME = 20;
    private static final int CLIPPED_LEVEL_MIN = 70;
    private static final int CLIPPED_LEVEL_STEP = 15;
    private static final float CLIPPED_RATIO_THRESHOLD = 0.1f;
    private static final int CLIPPED_WAIT_FRAMES = 300;
    private static final int TARGET_RANGE_MAX_DBFS = -30;
    private static final int TARGET_RANGE_MIN_DBFS = -50;
    private static final int UPDATE_INPUT_VOLUME_WAIT_FRAMES = 100;
    private static final float SPEECH_PROBABILITY_THRESHOLD = 0.7f;
    private static final float SPEECH_RATIO_THRESHOLD = 0.6f;

    private final int numChannels;
    private final Mono[] controllers;
    private final ClippingPredictor clippingPredictor;
    private boolean captureOutputUsed;
    private int framesSinceClipped;
    private int recommendedInputVolume;
    private int appliedInputVolume;

    /**
     * Creates a controller.
     *
     * @param numChannels the number of capture channels analyzed
     */
    InputVolumeController(int numChannels) {
        this.numChannels = numChannels;
        this.controllers = new Mono[numChannels];
        for (var ch = 0; ch < numChannels; ch++) {
            controllers[ch] = new Mono();
        }
        this.clippingPredictor = new ClippingPredictor(numChannels, ClippingPredictor.CLIPPING_EVENT,
                5, 5, 5, -1.0f, 3.0f);
        this.framesSinceClipped = CLIPPED_WAIT_FRAMES;
        this.appliedInputVolume = -1;
        for (var controller : controllers) {
            controller.initialize();
        }
        captureOutputUsed = true;
        aggregateChannelLevels();
    }

    /**
     * Analyzes a capture frame before processing, handling clipping.
     *
     * @param inputVolume the applied microphone volume, from 0 to 255
     * @param channels the full-band samples of every channel, in 16-bit scale
     * @param channelCount the number of channels in {@code channels}
     * @param samplesPerChannel the number of samples per channel
     */
    void analyzeInputAudio(int inputVolume, float[][] channels, int channelCount, int samplesPerChannel) {
        appliedInputVolume = inputVolume;
        for (var controller : controllers) {
            controller.recommendedInputVolume = inputVolume;
        }
        aggregateChannelLevels();
        if (!captureOutputUsed) {
            return;
        }
        clippingPredictor.analyze(channels, numChannels, samplesPerChannel);
        var clippedRatio = clippedRatio(channels, samplesPerChannel);
        if (framesSinceClipped < CLIPPED_WAIT_FRAMES) {
            ++framesSinceClipped;
            return;
        }
        var clippingDetected = clippedRatio > CLIPPED_RATIO_THRESHOLD;
        var clippingPredicted = false;
        var predictedStep = 0;
        for (var ch = 0; ch < numChannels; ++ch) {
            var step = clippingPredictor.estimateClippedLevelStep(ch, recommendedInputVolume, CLIPPED_LEVEL_STEP,
                    CLIPPED_LEVEL_MIN, MAX_INPUT_VOLUME);
            if (step >= 0) {
                predictedStep = Math.max(predictedStep, step);
                clippingPredicted = true;
            }
        }
        var step = CLIPPED_LEVEL_STEP;
        if (clippingPredicted) {
            step = Math.max(predictedStep, CLIPPED_LEVEL_STEP);
        }
        if (clippingDetected || clippingPredicted) {
            for (var controller : controllers) {
                controller.handleClipping(step);
            }
            framesSinceClipped = 0;
            clippingPredictor.reset();
        }
        aggregateChannelLevels();
    }

    /**
     * Returns the volume recommended after a processed frame, and forgets the applied volume.
     *
     * @param speechProbability the speech probability of the frame
     * @param speechLevelConfident whether the speech level estimate is confident
     * @param speechLevelDbfs the speech level estimate, in dBFS
     * @return the recommended volume, or {@code -1} when no volume was applied since the last call
     */
    int recommendInputVolume(float speechProbability, boolean speechLevelConfident, float speechLevelDbfs) {
        if (appliedInputVolume < 0) {
            return -1;
        }
        aggregateChannelLevels();
        if (!captureOutputUsed) {
            return appliedInputVolume;
        }
        var hasRmsError = speechLevelConfident;
        var rmsErrorDb = hasRmsError ? speechLevelRmsErrorDb(speechLevelDbfs) : 0;
        for (var controller : controllers) {
            controller.process(hasRmsError, rmsErrorDb, speechProbability);
        }
        aggregateChannelLevels();
        appliedInputVolume = -1;
        return recommendedInputVolume;
    }

    /**
     * Records whether the processed capture is used.
     *
     * @param used whether the capture output is used
     */
    void handleCaptureOutputUsedChange(boolean used) {
        for (var controller : controllers) {
            controller.handleCaptureOutputUsedChange(used);
        }
        captureOutputUsed = used;
    }

    private float clippedRatio(float[][] channels, int samplesPerChannel) {
        var numClipped = 0;
        for (var ch = 0; ch < numChannels; ++ch) {
            var numClippedInChannel = 0;
            var samples = channels[ch];
            for (var i = 0; i < samplesPerChannel; ++i) {
                if (samples[i] >= 32767.0f || samples[i] <= -32768.0f) {
                    ++numClippedInChannel;
                }
            }
            numClipped = Math.max(numClipped, numClippedInChannel);
        }
        return (float) numClipped / samplesPerChannel;
    }

    private static int speechLevelRmsErrorDb(float speechLevelDbfs) {
        var level = speechLevelDbfs < -90.0f ? -90.0f : (speechLevelDbfs > 30.0f ? 30.0f : speechLevelDbfs);
        if (level > TARGET_RANGE_MAX_DBFS) {
            return (int) roundHalfAway(TARGET_RANGE_MAX_DBFS - level);
        } else if (level < TARGET_RANGE_MIN_DBFS) {
            return (int) roundHalfAway(TARGET_RANGE_MIN_DBFS - level);
        }
        return 0;
    }

    private static float roundHalfAway(float v) {
        var rounded = (float) Math.floor(Math.abs((double) v) + 0.5);
        return v < 0 ? -rounded : rounded;
    }

    private void aggregateChannelLevels() {
        var recommended = controllers[0].recommendedInputVolume;
        for (var ch = 1; ch < controllers.length; ++ch) {
            recommended = Math.min(recommended, controllers[ch].recommendedInputVolume);
        }
        if (appliedInputVolume > 0) {
            recommended = Math.max(recommended, MIN_INPUT_VOLUME);
        }
        recommendedInputVolume = recommended;
    }

    private static int computeVolumeUpdate(int gainErrorDb, int inputVolume) {
        return ClippingPredictor.computeVolumeUpdate(gainErrorDb, inputVolume, MIN_INPUT_VOLUME, MAX_INPUT_VOLUME);
    }

    /**
     * Controls the volume from the levels of one channel ({@code MonoInputVolumeController}).
     */
    private static final class Mono {
        private int maxInputVolume = MAX_INPUT_VOLUME;
        private int lastRecommendedInputVolume;
        private boolean startup = true;
        private boolean captureOutputUsed = true;
        private boolean checkVolumeOnNextProcess = true;
        private int recommendedInputVolume;
        private int framesSinceUpdate;
        private int speechFramesSinceUpdate;
        private boolean firstFrame = true;

        /**
         * Resets the state that {@code Initialize} resets.
         */
        void initialize() {
            maxInputVolume = MAX_INPUT_VOLUME;
            captureOutputUsed = true;
            checkVolumeOnNextProcess = true;
            framesSinceUpdate = 0;
            speechFramesSinceUpdate = 0;
            firstFrame = true;
        }

        /**
         * Updates the recommendation after a processed frame.
         *
         * @param hasRmsError whether {@code rmsErrorDb} is available
         * @param rmsErrorDb the gap between the speech level and the target range, in dB
         * @param speechProbability the speech probability of the frame
         */
        void process(boolean hasRmsError, int rmsErrorDb, float speechProbability) {
            if (checkVolumeOnNextProcess) {
                checkVolumeOnNextProcess = false;
                checkVolumeAndReset();
            }
            if (speechProbability >= SPEECH_PROBABILITY_THRESHOLD) {
                ++speechFramesSinceUpdate;
            }
            if (++framesSinceUpdate >= UPDATE_INPUT_VOLUME_WAIT_FRAMES) {
                var speechRatio = (float) speechFramesSinceUpdate / (float) UPDATE_INPUT_VOLUME_WAIT_FRAMES;
                framesSinceUpdate = 0;
                speechFramesSinceUpdate = 0;
                if (!firstFrame && speechRatio >= SPEECH_RATIO_THRESHOLD && hasRmsError) {
                    updateInputVolume(rmsErrorDb);
                }
            }
            firstFrame = false;
        }

        /**
         * Lowers the volume and its ceiling after clipping.
         *
         * @param step the volume step
         */
        void handleClipping(int step) {
            maxInputVolume = Math.max(CLIPPED_LEVEL_MIN, maxInputVolume - step);
            if (lastRecommendedInputVolume > CLIPPED_LEVEL_MIN) {
                setInputVolume(Math.max(CLIPPED_LEVEL_MIN, lastRecommendedInputVolume - step));
                framesSinceUpdate = 0;
                speechFramesSinceUpdate = 0;
                firstFrame = false;
            }
        }

        /**
         * Records whether the processed capture is used.
         *
         * @param used whether the capture output is used
         */
        void handleCaptureOutputUsedChange(boolean used) {
            if (captureOutputUsed == used) {
                return;
            }
            captureOutputUsed = used;
            if (used) {
                checkVolumeOnNextProcess = true;
            }
        }

        private void setInputVolume(int newVolume) {
            var applied = recommendedInputVolume;
            if (applied == 0 || applied < 0 || applied > MAX_INPUT_VOLUME) {
                return;
            }
            if (applied > lastRecommendedInputVolume + VOLUME_QUANTIZATION_SLACK
                    || applied < lastRecommendedInputVolume - VOLUME_QUANTIZATION_SLACK) {
                lastRecommendedInputVolume = applied;
                if (lastRecommendedInputVolume > maxInputVolume) {
                    maxInputVolume = lastRecommendedInputVolume;
                }
                framesSinceUpdate = 0;
                speechFramesSinceUpdate = 0;
                firstFrame = false;
                return;
            }
            newVolume = Math.min(newVolume, maxInputVolume);
            if (newVolume == lastRecommendedInputVolume) {
                return;
            }
            recommendedInputVolume = newVolume;
            lastRecommendedInputVolume = newVolume;
        }

        private void checkVolumeAndReset() {
            var inputVolume = recommendedInputVolume;
            if (inputVolume == 0 && !startup) {
                return;
            }
            if (inputVolume < 0 || inputVolume > MAX_INPUT_VOLUME) {
                return;
            }
            if (inputVolume < MIN_INPUT_VOLUME) {
                inputVolume = MIN_INPUT_VOLUME;
                recommendedInputVolume = inputVolume;
            }
            lastRecommendedInputVolume = inputVolume;
            startup = false;
            framesSinceUpdate = 0;
            speechFramesSinceUpdate = 0;
            firstFrame = true;
        }

        private void updateInputVolume(int rmsErrorDb) {
            rmsErrorDb = Math.clamp(rmsErrorDb, -MAX_ABS_RMS_ERROR_DBFS, MAX_ABS_RMS_ERROR_DBFS);
            if (rmsErrorDb == 0) {
                return;
            }
            setInputVolume(computeVolumeUpdate(rmsErrorDb, lastRecommendedInputVolume));
        }
    }
}
