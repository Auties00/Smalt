/* Ports webrtc-audio-processing 2.1 (modules/audio_processing/aec3/matched_filter_lag_aggregator.cc). Copyright (c)
   2011, The WebRTC project authors; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.voice.internal.aec3;

import java.util.Arrays;

/**
 * Aggregates the matched filter lag estimates of the last second into a robust delay estimate.
 *
 * <p>A histogram of the last 250 lags (minus the headroom) gives the candidate with the most
 * votes; once it gathers enough votes it is reported, coarse until a candidate first passes the
 * convergence threshold and refined afterwards. A second histogram of the pre-echo lags, at
 * block resolution, selects the delay actually reported.
 *
 * @implNote This implementation ports {@code MatchedFilterLagAggregator} of WebRTC's
 * {@code modules/audio_processing/aec3/matched_filter_lag_aggregator.cc} with pre-echo
 * detection enabled.
 */
final class MatchedFilterLagAggregator {
    /**
     * The number of lags each histogram remembers.
     */
    private static final int HISTORY = 250;

    /**
     * The marker of a pre-echo history entry not yet written.
     */
    private static final int PRE_ECHO_NOT_UPDATED = -1;

    /**
     * The lag headroom, in decimated samples.
     */
    private static final int HEADROOM = Aec3.DELAY_HEADROOM_SAMPLES / Aec3.DOWN_SAMPLING_FACTOR;

    /**
     * The block size in decimated samples, as a base-2 logarithm.
     */
    private static final int PRE_ECHO_BLOCK_SIZE_LOG2 = Aec3.BLOCK_SIZE_LOG2 - 2;

    /**
     * Whether a candidate passed the convergence threshold since the last hard reset.
     */
    private boolean significantCandidateFound;

    /**
     * The lag histogram.
     */
    private final int[] histogram = new int[MatchedFilter.MAX_FILTER_LAG + 1];

    /**
     * The lags in the histogram, in arrival order.
     */
    private final int[] histogramData = new int[HISTORY];

    /**
     * The next slot of {@link #histogramData}.
     */
    private int histogramDataIndex;

    /**
     * The lag with the most votes.
     */
    private int candidate = -1;

    /**
     * The pre-echo lag histogram, in blocks.
     */
    private final int[] preEchoHistogram = new int[((MatchedFilter.MAX_FILTER_LAG + 1) * Aec3.DOWN_SAMPLING_FACTOR) >> Aec3.BLOCK_SIZE_LOG2];

    /**
     * The pre-echo lags in the histogram, in arrival order.
     */
    private final int[] preEchoHistogramData = new int[HISTORY];

    /**
     * The next slot of {@link #preEchoHistogramData}.
     */
    private int preEchoHistogramDataIndex;

    /**
     * The selected pre-echo lag, in decimated samples.
     */
    private int preEchoCandidate;

    /**
     * The number of pre-echo updates, saturating at two seconds of blocks.
     */
    private int preEchoNumberUpdates;

    /**
     * The quality of the last reported estimate: {@code 0} coarse, {@code 1} refined.
     */
    private int quality;

    /**
     * Creates an aggregator with empty histograms.
     */
    MatchedFilterLagAggregator() {
        resetPreEcho();
    }

    /**
     * Clears the histograms, and on a hard reset also forgets that a candidate converged.
     *
     * @param hardReset whether the convergence is forgotten
     */
    void reset(boolean hardReset) {
        Arrays.fill(histogram, 0);
        Arrays.fill(histogramData, 0);
        histogramDataIndex = 0;
        resetPreEcho();
        if (hardReset) {
            significantCandidateFound = false;
        }
    }

    /**
     * Clears the pre-echo histogram.
     */
    private void resetPreEcho() {
        Arrays.fill(preEchoHistogram, 0);
        Arrays.fill(preEchoHistogramData, PRE_ECHO_NOT_UPDATED);
        preEchoHistogramDataIndex = 0;
        preEchoCandidate = 0;
    }

    /**
     * Adds a lag estimate and returns the aggregated delay.
     *
     * @param hasLag whether the matched filter produced an estimate
     * @param lag the lag of the estimate
     * @param preEchoLag the pre-echo lag of the estimate
     * @return the aggregated delay in decimated samples, or {@code -1} when none is reliable yet;
     * its quality is then {@link #quality()}
     */
    int aggregate(boolean hasLag, int lag, int preEchoLag) {
        if (hasLag) {
            aggregatePreEcho(Math.max(0, preEchoLag - HEADROOM));
            aggregateHighestPeak(Math.max(0, lag - HEADROOM));
            significantCandidateFound = significantCandidateFound
                    || histogram[candidate] > Aec3.DELAY_SELECTION_THRESHOLD_CONVERGED;
            if (histogram[candidate] > Aec3.DELAY_SELECTION_THRESHOLD_CONVERGED
                    || (histogram[candidate] > Aec3.DELAY_SELECTION_THRESHOLD_INITIAL && !significantCandidateFound)) {
                quality = significantCandidateFound ? 1 : 0;
                return preEchoCandidate;
            }
        }
        return -1;
    }

    /**
     * Returns the quality of the last delay {@link #aggregate(boolean, int, int)} returned.
     *
     * @return {@code 0} for coarse, {@code 1} for refined
     */
    int quality() {
        return quality;
    }

    /**
     * Returns whether a candidate passed the convergence threshold.
     *
     * @return whether a reliable delay was found
     */
    boolean reliableDelayFound() {
        return significantCandidateFound;
    }

    /**
     * Returns the lag with the most votes.
     *
     * @return the candidate lag
     */
    int delayAtHighestPeak() {
        return candidate;
    }

    /**
     * Adds a lag to the lag histogram and updates the candidate.
     *
     * @param lag the lag of the highest matched-filter peak, in samples
     */
    private void aggregateHighestPeak(int lag) {
        --histogram[histogramData[histogramDataIndex]];
        histogramData[histogramDataIndex] = lag;
        ++histogram[histogramData[histogramDataIndex]];
        histogramDataIndex = (histogramDataIndex + 1) % HISTORY;
        candidate = argMax(histogram, 0, histogram.length);
    }

    /**
     * Adds a pre-echo lag to the pre-echo histogram and updates the pre-echo candidate,
     * favoring early peaks during the first two seconds.
     *
     * @param preEchoLag the pre-echo lag in decimated samples
     */
    private void aggregatePreEcho(int preEchoLag) {
        var preEchoBlockSize = preEchoLag >> PRE_ECHO_BLOCK_SIZE_LOG2;
        preEchoBlockSize = Math.clamp(preEchoBlockSize, 0, preEchoHistogram.length - 1);
        if (preEchoHistogramData[preEchoHistogramDataIndex] != PRE_ECHO_NOT_UPDATED) {
            --preEchoHistogram[preEchoHistogramData[preEchoHistogramDataIndex]];
        }
        preEchoHistogramData[preEchoHistogramDataIndex] = preEchoBlockSize;
        ++preEchoHistogram[preEchoHistogramData[preEchoHistogramDataIndex]];
        preEchoHistogramDataIndex = (preEchoHistogramDataIndex + 1) % HISTORY;
        var preEchoCandidateBlockSize = 0;
        if (preEchoNumberUpdates < Aec3.NUM_BLOCKS_PER_SECOND * 2) {
            preEchoNumberUpdates++;
            var penalizationPerDelay = 1.0f;
            var maxHistogramValue = -1.0f;
            var window = Aec3.MATCHED_FILTER_WINDOW_SIZE_SUB_BLOCKS;
            for (var it = 0; preEchoHistogram.length - it >= window; it += window) {
                var maxIndex = argMax(preEchoHistogram, it, it + window);
                var weightedMaxValue = (float) preEchoHistogram[maxIndex] * penalizationPerDelay;
                if (weightedMaxValue > maxHistogramValue) {
                    maxHistogramValue = weightedMaxValue;
                    preEchoCandidateBlockSize = maxIndex;
                }
                penalizationPerDelay *= 0.7f;
            }
        } else {
            preEchoCandidateBlockSize = argMax(preEchoHistogram, 0, preEchoHistogram.length);
        }
        preEchoCandidate = preEchoCandidateBlockSize << PRE_ECHO_BLOCK_SIZE_LOG2;
    }

    /**
     * Returns the index of the first largest entry of a range, as {@code std::max_element}.
     *
     * @param values the histogram
     * @param from the first index
     * @param to the index after the last
     * @return the index of the first maximum
     */
    private static int argMax(int[] values, int from, int to) {
        var best = from;
        for (var i = from + 1; i < to; i++) {
            if (values[best] < values[i]) {
                best = i;
            }
        }
        return best;
    }
}
