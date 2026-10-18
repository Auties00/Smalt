package com.github.auties00.smalt.audio.voice.internal.aec3;

import java.util.Arrays;

import static com.github.auties00.smalt.audio.voice.internal.aec3.Aec3.BLOCK_SIZE;
import static com.github.auties00.smalt.audio.voice.internal.aec3.Aec3.BLOCK_SIZE_LOG2;

/**
 * Analyzes the time-domain response of the refined filter one block per call: the delay of its
 * main peak, the echo path gain, and whether the peak is consistent enough to trust.
 *
 * <p>The response is first high-pass filtered, region by region, into the adjusted filter that
 * the reverberation model also uses.
 *
 * @implNote This implementation ports {@code FilterAnalyzer} of WebRTC's
 * {@code modules/audio_processing/aec3/filter_analyzer.cc} for one capture channel and an
 * unbounded ERL.
 */
final class FilterAnalyzer {
    /**
     * The high-pass filter applied to the response.
     */
    private static final float[] HIGH_PASS = {0.7929742f, -0.36072128f, -0.47047766f};

    /**
     * The block energy above which a render block counts as active,
     * {@code active_render_limit^2 * 64}.
     */
    private static final float ACTIVE_RENDER_THRESHOLD = Aec3.ACTIVE_RENDER_LIMIT * Aec3.ACTIVE_RENDER_LIMIT * Aec3.FFT_LENGTH_BY_2;

    /**
     * The high-pass filtered response.
     */
    final float[] hHighpass = new float[Aec3.REFINED_LENGTH_BLOCKS * BLOCK_SIZE];

    /**
     * The length of {@link #hHighpass}.
     */
    int hHighpassLength = Aec3.REFINED_LENGTH_BLOCKS * BLOCK_SIZE;

    /**
     * The number of updates since the last reset.
     */
    private int blocksSinceReset;

    /**
     * The first sample of the region analyzed by the last update.
     */
    private int regionStart;

    /**
     * The last sample of the region analyzed by the last update.
     */
    private int regionEnd;

    /**
     * The echo path gain estimate.
     */
    private float gain;

    /**
     * The index of the peak of the response.
     */
    private int peakIndex;

    /**
     * The length of the analyzed response in blocks.
     */
    private int filterLengthBlocks = Aec3.REFINED_INITIAL_LENGTH_BLOCKS;

    /**
     * Whether the peak has stayed at a consistent delay long enough.
     */
    private boolean consistentEstimate;

    /**
     * The delay of the peak in blocks.
     */
    private int filterDelayBlocks;

    /**
     * Whether the peak stands out from the rest of the response.
     */
    private boolean significantPeak;

    /**
     * The accumulated magnitude of the response away from the peak.
     */
    private float filterFloorAccum;

    /**
     * The largest magnitude of the response away from the peak.
     */
    private float filterSecondaryPeak;

    /**
     * The end of the floor region before the peak.
     */
    private int filterFloorLowLimit;

    /**
     * The start of the floor region after the peak.
     */
    private int filterFloorHighLimit;

    /**
     * The number of active render blocks with the peak at the same delay.
     */
    private int consistentEstimateCounter;

    /**
     * The delay the consistency counter refers to.
     */
    private int consistentDelayReference = -10;

    /**
     * Creates an analyzer.
     */
    FilterAnalyzer() {
        reset();
    }

    /**
     * Resets the analysis.
     */
    void reset() {
        blocksSinceReset = 0;
        regionStart = 0;
        regionEnd = 0;
        peakIndex = 0;
        gain = Aec3.EP_DEFAULT_GAIN;
        significantPeak = false;
        filterFloorAccum = 0.f;
        filterSecondaryPeak = 0.f;
        filterFloorLowLimit = 0;
        filterFloorHighLimit = 0;
        consistentEstimateCounter = 0;
        consistentDelayReference = -10;
        filterDelayBlocks = 0;
    }

    /**
     * Analyzes the next region of the refined filter response.
     *
     * @param filter       the time-domain response of the refined filter
     * @param renderBuffer the render rings
     */
    void update(AdaptiveFirFilter.ImpulseResponse filter, RenderBuffer renderBuffer) {
        ++blocksSinceReset;
        setRegionToAnalyze(filter.length);
        preProcessFilter(filter);
        peakIndex = Math.min(peakIndex, hHighpassLength - 1);
        peakIndex = findPeakIndex(hHighpass, peakIndex, regionStart, regionEnd);
        filterDelayBlocks = peakIndex >> BLOCK_SIZE_LOG2;
        updateFilterGain();
        filterLengthBlocks = (int) (filter.length * (1.f / BLOCK_SIZE));
        consistentEstimate = detectConsistent(renderBuffer);
    }

    /**
     * Returns whether the peak is consistent.
     *
     * @return whether the filter estimate is consistent
     */
    boolean consistentEstimate() {
        return consistentEstimate;
    }

    /**
     * Returns the echo path gain estimate.
     *
     * @return the gain
     */
    float gain() {
        return gain;
    }

    /**
     * Returns the delay of the response peak.
     *
     * @return the delay in blocks
     */
    int filterDelayBlocks() {
        return filterDelayBlocks;
    }

    /**
     * Returns the length of the analyzed response.
     *
     * @return the length in blocks
     */
    int filterLengthBlocks() {
        return filterLengthBlocks;
    }

    /**
     * Moves the analyzed region to the next block of the response, wrapping at its end.
     *
     * @param filterSize the length of the response
     */
    private void setRegionToAnalyze(int filterSize) {
        regionStart = regionEnd >= filterSize - 1 ? 0 : regionEnd + 1;
        regionEnd = Math.min(regionStart + BLOCK_SIZE - 1, filterSize - 1);
    }

    /**
     * Recomputes the high-pass filtered response over the analyzed region.
     *
     * @param filter the response
     */
    private void preProcessFilter(AdaptiveFirFilter.ImpulseResponse filter) {
        var newLength = filter.length;
        if (newLength > hHighpassLength) {
            Arrays.fill(hHighpass, hHighpassLength, newLength, 0.f);
        }
        hHighpassLength = newLength;
        Arrays.fill(hHighpass, regionStart, regionEnd + 1, 0.f);
        var values = filter.values;
        for (var k = Math.max(HIGH_PASS.length - 1, regionStart); k <= regionEnd; ++k) {
            var tmp = hHighpass[k];
            for (var j = 0; j < HIGH_PASS.length; ++j) {
                tmp += values[k - j] * HIGH_PASS[j];
            }
            hHighpass[k] = tmp;
        }
    }

    /**
     * Returns the index of the largest squared value, starting from a previous peak and searching
     * a region.
     *
     * @param h           the response
     * @param peakIndexIn the previous peak
     * @param start       the first index of the region
     * @param end         the last index of the region
     * @return the new peak
     */
    private static int findPeakIndex(float[] h, int peakIndexIn, int start, int end) {
        var peakIndexOut = peakIndexIn;
        var maxH2 = h[peakIndexOut] * h[peakIndexOut];
        for (var k = start; k <= end; ++k) {
            var tmp = h[k] * h[k];
            if (tmp > maxH2) {
                peakIndexOut = k;
                maxH2 = tmp;
            }
        }
        return peakIndexOut;
    }

    /**
     * Updates the echo path gain estimate from the peak.
     */
    private void updateFilterGain() {
        var sufficientTimeToConverge = blocksSinceReset > 5 * Aec3.NUM_BLOCKS_PER_SECOND;
        var peak = Math.abs(hHighpass[peakIndex]);
        if (sufficientTimeToConverge && consistentEstimate) {
            gain = peak;
        } else if (gain != 0) {
            gain = gain < peak ? peak : gain;
        }
    }

    /**
     * Updates the significance of the peak and the consistency of its delay.
     *
     * @param renderBuffer the render rings
     * @return whether the estimate is consistent
     */
    private boolean detectConsistent(RenderBuffer renderBuffer) {
        var filterToAnalyze = hHighpass;
        var size = hHighpassLength;
        if (regionStart == 0) {
            filterFloorAccum = 0.f;
            filterSecondaryPeak = 0.f;
            filterFloorLowLimit = peakIndex < 64 ? 0 : peakIndex - 64;
            filterFloorHighLimit = peakIndex > size - 129 ? 0 : peakIndex + 128;
        }
        var accum = filterFloorAccum;
        var secondaryPeak = filterSecondaryPeak;
        for (var k = regionStart; k < Math.min(regionEnd + 1, filterFloorLowLimit); ++k) {
            var absH = Math.abs(filterToAnalyze[k]);
            accum += absH;
            secondaryPeak = secondaryPeak < absH ? absH : secondaryPeak;
        }
        for (var k = Math.max(filterFloorHighLimit, regionStart); k <= regionEnd; ++k) {
            var absH = Math.abs(filterToAnalyze[k]);
            accum += absH;
            secondaryPeak = secondaryPeak < absH ? absH : secondaryPeak;
        }
        filterFloorAccum = accum;
        filterSecondaryPeak = secondaryPeak;
        if (regionEnd == size - 1) {
            var filterFloor = filterFloorAccum / (filterFloorLowLimit + size - filterFloorHighLimit);
            var absPeak = Math.abs(filterToAnalyze[peakIndex]);
            significantPeak = absPeak > 10.f * filterFloor && absPeak > 2.f * filterSecondaryPeak;
        }
        if (significantPeak) {
            var blocks = renderBuffer.blocks;
            var b = renderBuffer.blockOffset(-filterDelayBlocks);
            var xEnergy = 0.f;
            for (var i = 0; i < BLOCK_SIZE; i++) {
                xEnergy = xEnergy + blocks[b + i] * blocks[b + i];
            }
            var activeRenderBlock = xEnergy > ACTIVE_RENDER_THRESHOLD;
            if (consistentDelayReference == filterDelayBlocks) {
                if (activeRenderBlock) {
                    ++consistentEstimateCounter;
                }
            } else {
                consistentEstimateCounter = 0;
                consistentDelayReference = filterDelayBlocks;
            }
        }
        return consistentEstimateCounter > 1.5f * Aec3.NUM_BLOCKS_PER_SECOND;
    }
}
