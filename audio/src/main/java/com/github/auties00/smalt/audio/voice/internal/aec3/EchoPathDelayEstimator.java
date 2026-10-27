package com.github.auties00.smalt.audio.voice.internal.aec3;

/**
 * Estimates the echo path delay from the decimated render and capture signals, and detects clock
 * drift from the evolution of the estimate.
 *
 * <p>The capture block is decimated by four and fed to the {@link MatchedFilter} bank; the
 * {@link MatchedFilterLagAggregator} turns the lags into a delay estimate in full-rate samples.
 * After half a second of identical estimates the filters are reset (keeping the aggregated
 * histogram), so that they keep tracking changes. Clock drift is flagged when refined estimates
 * move by one sample at a time in a consistent direction.
 *
 * @implNote This implementation ports {@code EchoPathDelayEstimator} of WebRTC's
 * {@code modules/audio_processing/aec3/echo_path_delay_estimator.cc} and
 * {@code ClockdriftDetector} of {@code clockdrift_detector.cc}, for one capture channel.
 */
final class EchoPathDelayEstimator {
    /**
     * No clock drift.
     */
    static final int DRIFT_NONE = 0;

    /**
     * Probable clock drift.
     */
    static final int DRIFT_PROBABLE = 1;

    /**
     * Verified clock drift.
     */
    static final int DRIFT_VERIFIED = 2;

    /**
     * The decimator of the capture signal.
     */
    private final Decimator captureDecimator;

    /**
     * The selector of the capture channel the matched filter sees.
     */
    private final AlignmentMixer captureMixer;

    /**
     * The scratch buffer of the selected capture channel.
     */
    private final float[] downmixedCapture = new float[Aec3.BLOCK_SIZE];

    /**
     * The matched filter bank.
     */
    private final MatchedFilter matchedFilter;

    /**
     * The lag aggregator.
     */
    private final MatchedFilterLagAggregator lagAggregator = new MatchedFilterLagAggregator();

    /**
     * The decimated capture block.
     */
    private final float[] downsampledCapture = new float[Aec3.BLOCK_SIZE / Aec3.DOWN_SAMPLING_FACTOR];

    /**
     * The previous aggregated lag in full-rate samples, or {@code -1} when there was none.
     */
    private int oldAggregatedLag = -1;

    /**
     * The number of consecutive identical estimates.
     */
    private int consistentEstimateCounter;

    /**
     * The quality of the last estimate: {@code 0} coarse, {@code 1} refined.
     */
    private int quality;

    /**
     * The clock drift level.
     */
    private int driftLevel = DRIFT_NONE;

    /**
     * The last three distinct delays seen by the drift detector, most recent first.
     */
    private final int[] delayHistory = new int[3];

    /**
     * The number of updates since the delay seen by the drift detector last changed.
     */
    private int stabilityCounter;

    /**
     * Creates an estimator.
     *
     * @param kernel    the matched filter kernel
     * @param numCaptureChannels the number of capture channels
     * @param flushTiny whether the capture decimator zeroes tiny filter states and samples;
     *                  {@code false} reproduces the reference bit for bit
     */
    EchoPathDelayEstimator(MatchedFilterKernel kernel, int numCaptureChannels, boolean flushTiny) {
        this.captureMixer = new AlignmentMixer(numCaptureChannels, false);
        this.matchedFilter = new MatchedFilter(kernel);
        this.captureDecimator = new Decimator(flushTiny);
    }

    /**
     * Resets the lag aggregator and the matched filters.
     *
     * @param resetDelayConfidence whether the convergence of the aggregator is forgotten too
     */
    void reset(boolean resetDelayConfidence) {
        reset(true, resetDelayConfidence);
    }

    /**
     * Resets the estimator.
     *
     * @param resetLagAggregator   whether the lag aggregator is reset
     * @param resetDelayConfidence whether the convergence of the aggregator is forgotten
     */
    private void reset(boolean resetLagAggregator, boolean resetDelayConfidence) {
        if (resetLagAggregator) {
            lagAggregator.reset(resetDelayConfidence);
        }
        matchedFilter.reset(resetLagAggregator);
        oldAggregatedLag = -1;
        consistentEstimateCounter = 0;
    }

    /**
     * Updates the estimate with a capture block.
     *
     * @param renderBuffer the decimated render ring
     * @param renderRead   the read index of the decimated render ring
     * @param capture      the capture block, the lowest band of every channel first
     * @return the delay estimate in full-rate samples, or {@code -1} when there is none; its
     *         quality is then {@link #quality()}
     */
    int estimateDelay(float[] renderBuffer, int renderRead, float[] capture) {
        captureMixer.produceOutput(capture, 0, Aec3.BLOCK_SIZE, downmixedCapture);
        captureDecimator.decimate(downmixedCapture, 0, downsampledCapture);
        matchedFilter.update(renderBuffer, renderRead, downsampledCapture, lagAggregator.reliableDelayFound());
        var aggregated = lagAggregator.aggregate(matchedFilter.hasLagEstimate(), matchedFilter.lag(),
                matchedFilter.preEchoLag());
        if (aggregated >= 0) {
            quality = lagAggregator.quality();
            if (quality == 1) {
                updateClockdrift(lagAggregator.delayAtHighestPeak());
            }
            aggregated *= Aec3.DOWN_SAMPLING_FACTOR;
        }
        if (oldAggregatedLag >= 0 && aggregated >= 0 && oldAggregatedLag == aggregated) {
            ++consistentEstimateCounter;
        } else {
            consistentEstimateCounter = 0;
        }
        oldAggregatedLag = aggregated;
        if (consistentEstimateCounter > Aec3.NUM_BLOCKS_PER_SECOND / 2) {
            reset(false, false);
        }
        return aggregated;
    }

    /**
     * Returns the quality of the last estimate.
     *
     * @return {@code 0} for coarse, {@code 1} for refined
     */
    int quality() {
        return quality;
    }

    /**
     * Returns the clock drift level.
     *
     * @return {@link #DRIFT_NONE}, {@link #DRIFT_PROBABLE} or {@link #DRIFT_VERIFIED}
     */
    int clockdrift() {
        return driftLevel;
    }

    /**
     * Updates the clock drift detector with the delay of the highest histogram peak.
     *
     * @param delayEstimate the delay in decimated samples
     */
    private void updateClockdrift(int delayEstimate) {
        if (delayEstimate == delayHistory[0]) {
            if (++stabilityCounter > 7500) {
                driftLevel = DRIFT_NONE;
            }
            return;
        }
        stabilityCounter = 0;
        var d1 = delayHistory[0] - delayEstimate;
        var d2 = delayHistory[1] - delayEstimate;
        var d3 = delayHistory[2] - delayEstimate;
        var probableDriftUp = (d1 == -1 && d2 == -2) || (d1 == -2 && d2 == -1);
        var driftUp = probableDriftUp && d3 == -3;
        var probableDriftDown = (d1 == 1 && d2 == 2) || (d1 == 2 && d2 == 1);
        var driftDown = probableDriftDown && d3 == 3;
        if (driftUp || driftDown) {
            driftLevel = DRIFT_VERIFIED;
        } else if ((probableDriftUp || probableDriftDown) && driftLevel == DRIFT_NONE) {
            driftLevel = DRIFT_PROBABLE;
        }
        delayHistory[2] = delayHistory[1];
        delayHistory[1] = delayHistory[0];
        delayHistory[0] = delayEstimate;
    }
}
