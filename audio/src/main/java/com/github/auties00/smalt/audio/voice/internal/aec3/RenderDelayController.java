package com.github.auties00.smalt.audio.voice.internal.aec3;

/**
 * Turns the echo path delay estimates into the block delay the render buffer is aligned with,
 * with one block of hysteresis against increases once the estimate is refined.
 *
 * @implNote This implementation ports {@code RenderDelayControllerImpl} of WebRTC's
 * {@code modules/audio_processing/aec3/render_delay_controller.cc}; its metrics are omitted.
 */
final class RenderDelayController {
    /**
     * The echo path delay estimator.
     */
    private final EchoPathDelayEstimator delayEstimator;

    /**
     * Whether a delay estimate in samples has been seen since the last reset.
     */
    private boolean hasDelaySamples;

    /**
     * The last delay estimate in samples.
     */
    private int delaySamples;

    /**
     * The quality of the last delay estimate in samples.
     */
    private int delaySamplesQuality;

    /**
     * Whether a block delay has been computed since the last reset.
     */
    private boolean hasDelay;

    /**
     * The current block delay.
     */
    private int delay;

    /**
     * The quality of the estimate behind the current block delay: {@code 0} coarse, {@code 1}
     * refined.
     */
    private int lastDelayEstimateQuality;

    /**
     * Creates a controller.
     *
     * @param kernel    the matched filter kernel of the delay estimator
     * @param numCaptureChannels the number of capture channels
     * @param flushTiny whether the delay estimator's capture decimator zeroes tiny filter states
     *                  and samples; {@code false} reproduces the reference bit for bit
     */
    RenderDelayController(MatchedFilterKernel kernel, int numCaptureChannels, boolean flushTiny) {
        this.delayEstimator = new EchoPathDelayEstimator(kernel, numCaptureChannels, flushTiny);
    }

    /**
     * Forgets the delay and resets the estimator.
     *
     * @param resetDelayConfidence whether the estimator also forgets its convergence
     */
    void reset(boolean resetDelayConfidence) {
        hasDelay = false;
        hasDelaySamples = false;
        delayEstimator.reset(resetDelayConfidence);
        if (resetDelayConfidence) {
            lastDelayEstimateQuality = 0;
        }
    }

    /**
     * Updates the delay estimate with a capture block and returns the block delay.
     *
     * @param renderBuffer the decimated render ring
     * @param renderRead   the read index of the decimated render ring
     * @param capture      the capture block, the lowest band of every channel first
     * @return the block delay, or {@code -1} when none is known yet
     */
    int getDelay(float[] renderBuffer, int renderRead, float[] capture) {
        var estimate = delayEstimator.estimateDelay(renderBuffer, renderRead, capture);
        if (estimate >= 0) {
            delaySamples = estimate;
            delaySamplesQuality = delayEstimator.quality();
            hasDelaySamples = true;
        }
        if (hasDelaySamples) {
            var useHysteresis = lastDelayEstimateQuality == 1 && delaySamplesQuality == 1;
            var hysteresisLimit = useHysteresis ? Aec3.HYSTERESIS_LIMIT_BLOCKS : 0;
            var newDelayBlocks = delaySamples >> Aec3.BLOCK_SIZE_LOG2;
            if (hasDelay && newDelayBlocks > delay && newDelayBlocks <= delay + hysteresisLimit) {
                newDelayBlocks = delay;
            }
            delay = newDelayBlocks;
            hasDelay = true;
            lastDelayEstimateQuality = delaySamplesQuality;
        }
        return hasDelay ? delay : -1;
    }

    /**
     * Returns whether the estimator detects clock drift.
     *
     * @return whether the drift level is not none
     */
    boolean hasClockdrift() {
        return delayEstimator.clockdrift() != EchoPathDelayEstimator.DRIFT_NONE;
    }
}
