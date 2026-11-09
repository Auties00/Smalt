/* Ports webrtc-audio-processing 2.1 (modules/audio_processing/aec3/filter_analyzer.cc). Copyright (c) 2011, The
   WebRTC project authors; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.voice.internal.aec3;

import java.util.Arrays;

import static com.github.auties00.smalt.audio.voice.internal.aec3.Aec3.BLOCK_SIZE;
import static com.github.auties00.smalt.audio.voice.internal.aec3.Aec3.BLOCK_SIZE_LOG2;

/**
 * Analyzes the time-domain response of every capture channel's refined filter one block per call:
 * the delay of its main peak, the echo path gain, and whether the peak is consistent enough to
 * trust.
 *
 * <p>Each response is first high-pass filtered, region by region, into the adjusted filter that
 * the reverberation model also uses.
 *
 * @implNote This implementation ports {@code FilterAnalyzer} of WebRTC's
 * {@code modules/audio_processing/aec3/filter_analyzer.cc} with an unbounded ERL.
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
     * The number of capture channels.
     */
    private final int numChannels;

    /**
     * The high-pass filtered responses.
     */
    private final float[][] hHighpass;

    /**
     * The number of taps of each high-pass filtered response.
     */
    private final int[] hHighpassLength;

    /**
     * The number of updates since the last reset.
     */
    private int blocksSinceReset;

    /**
     * The first sample of the region analyzed by the current update.
     */
    private int regionStart;

    /**
     * The last sample of the region analyzed by the current update.
     */
    private int regionEnd;

    /**
     * The echo path gain estimate of every channel.
     */
    private final float[] gain;

    /**
     * The index of the main peak of every channel's response.
     */
    private final int[] peakIndex;

    /**
     * The length of every channel's filter in blocks.
     */
    private final int[] filterLengthBlocks;

    /**
     * Whether every channel's peak has been at a consistent delay for long enough.
     */
    private final boolean[] consistentEstimate;

    /**
     * The delay of every channel's main peak in blocks.
     */
    private final int[] filterDelayBlocks;

    /**
     * Whether every channel's peak stands out of the filter floor.
     */
    private final boolean[] significantPeak;

    /**
     * The accumulated magnitude of every channel's filter floor.
     */
    private final float[] filterFloorAccum;

    /**
     * The largest magnitude of every channel's filter floor.
     */
    private final float[] filterSecondaryPeak;

    /**
     * The end of every channel's lower filter floor region.
     */
    private final int[] filterFloorLowLimit;

    /**
     * The start of every channel's upper filter floor region.
     */
    private final int[] filterFloorHighLimit;

    /**
     * The number of active render blocks every channel's peak delay has been unchanged.
     */
    private final int[] consistentEstimateCounter;

    /**
     * The delay every channel's consistency is counted for.
     */
    private final int[] consistentDelayReference;

    /**
     * Whether some channel's estimate is consistent, after the last update.
     */
    private boolean anyFilterConsistent;

    /**
     * The largest echo path gain estimate, after the last update.
     */
    private float maxEchoPathGain;

    /**
     * Creates an analyzer.
     *
     * @param numChannels the number of capture channels
     */
    FilterAnalyzer(int numChannels) {
        this.numChannels = numChannels;
        this.hHighpass = new float[numChannels][Aec3.REFINED_LENGTH_BLOCKS * BLOCK_SIZE];
        this.hHighpassLength = new int[numChannels];
        Arrays.fill(hHighpassLength, Aec3.REFINED_LENGTH_BLOCKS * BLOCK_SIZE);
        this.gain = new float[numChannels];
        this.peakIndex = new int[numChannels];
        this.filterLengthBlocks = new int[numChannels];
        Arrays.fill(filterLengthBlocks, Aec3.REFINED_INITIAL_LENGTH_BLOCKS);
        this.consistentEstimate = new boolean[numChannels];
        this.filterDelayBlocks = new int[numChannels];
        this.significantPeak = new boolean[numChannels];
        this.filterFloorAccum = new float[numChannels];
        this.filterSecondaryPeak = new float[numChannels];
        this.filterFloorLowLimit = new int[numChannels];
        this.filterFloorHighLimit = new int[numChannels];
        this.consistentEstimateCounter = new int[numChannels];
        this.consistentDelayReference = new int[numChannels];
        reset();
    }

    /**
     * Forgets the analysis.
     */
    void reset() {
        blocksSinceReset = 0;
        regionStart = 0;
        regionEnd = 0;
        for (var ch = 0; ch < numChannels; ch++) {
            peakIndex[ch] = 0;
            gain[ch] = Aec3.EP_DEFAULT_GAIN;
            significantPeak[ch] = false;
            filterFloorAccum[ch] = 0.f;
            filterSecondaryPeak[ch] = 0.f;
            filterFloorLowLimit[ch] = 0;
            filterFloorHighLimit[ch] = 0;
            consistentEstimateCounter[ch] = 0;
            consistentDelayReference[ch] = -10;
            filterDelayBlocks[ch] = 0;
        }
    }

    /**
     * Analyzes the next region of every channel's filter.
     *
     * @param filters the time-domain responses of every channel's refined filter
     * @param renderBuffer the render buffer
     */
    void update(AdaptiveFirFilter.ImpulseResponse[] filters, RenderBuffer renderBuffer) {
        ++blocksSinceReset;
        setRegionToAnalyze(filters[0].length);
        for (var ch = 0; ch < numChannels; ch++) {
            preProcessFilter(ch, filters[ch]);
        }
        for (var ch = 0; ch < numChannels; ch++) {
            var filter = filters[ch];
            peakIndex[ch] = Math.min(peakIndex[ch], hHighpassLength[ch] - 1);
            peakIndex[ch] = findPeakIndex(hHighpass[ch], peakIndex[ch], regionStart, regionEnd);
            filterDelayBlocks[ch] = peakIndex[ch] >> BLOCK_SIZE_LOG2;
            updateFilterGain(ch);
            filterLengthBlocks[ch] = (int) (filter.length * (1.f / BLOCK_SIZE));
            consistentEstimate[ch] = detectConsistent(ch, renderBuffer);
        }
        anyFilterConsistent = consistentEstimate[0];
        maxEchoPathGain = gain[0];
        for (var ch = 1; ch < numChannels; ch++) {
            anyFilterConsistent = anyFilterConsistent || consistentEstimate[ch];
            maxEchoPathGain = maxEchoPathGain < gain[ch] ? gain[ch] : maxEchoPathGain;
        }
    }

    /**
     * {@return whether some channel's estimate is consistent}
     */
    boolean anyFilterConsistent() {
        return anyFilterConsistent;
    }

    /**
     * {@return the largest echo path gain estimate over the channels}
     */
    float maxEchoPathGain() {
        return maxEchoPathGain;
    }

    /**
     * {@return the delay of every channel's main peak in blocks}
     */
    int[] filterDelaysBlocks() {
        return filterDelayBlocks;
    }

    /**
     * {@return the length of the first channel's filter in blocks}
     */
    int filterLengthBlocks() {
        return filterLengthBlocks[0];
    }

    private void setRegionToAnalyze(int filterSize) {
        regionStart = regionEnd >= filterSize - 1 ? 0 : regionEnd + 1;
        regionEnd = Math.min(regionStart + BLOCK_SIZE - 1, filterSize - 1);
    }

    private void preProcessFilter(int ch, AdaptiveFirFilter.ImpulseResponse filter) {
        var newLength = filter.length;
        var h = hHighpass[ch];
        if (newLength > hHighpassLength[ch]) {
            Arrays.fill(h, hHighpassLength[ch], newLength, 0.f);
        }
        hHighpassLength[ch] = newLength;
        Arrays.fill(h, regionStart, regionEnd + 1, 0.f);
        var values = filter.values;
        for (var k = Math.max(HIGH_PASS.length - 1, regionStart); k <= regionEnd; ++k) {
            var tmp = h[k];
            for (var j = 0; j < HIGH_PASS.length; ++j) {
                tmp += values[k - j] * HIGH_PASS[j];
            }
            h[k] = tmp;
        }
    }

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

    private void updateFilterGain(int ch) {
        var sufficientTimeToConverge = blocksSinceReset > 5 * Aec3.NUM_BLOCKS_PER_SECOND;
        var peak = Math.abs(hHighpass[ch][peakIndex[ch]]);
        if (sufficientTimeToConverge && consistentEstimate[ch]) {
            gain[ch] = peak;
        } else if (gain[ch] != 0) {
            gain[ch] = gain[ch] < peak ? peak : gain[ch];
        }
    }

    private boolean detectConsistent(int ch, RenderBuffer renderBuffer) {
        var filterToAnalyze = hHighpass[ch];
        var size = hHighpassLength[ch];
        var peak = peakIndex[ch];
        if (regionStart == 0) {
            filterFloorAccum[ch] = 0.f;
            filterSecondaryPeak[ch] = 0.f;
            filterFloorLowLimit[ch] = peak < 64 ? 0 : peak - 64;
            filterFloorHighLimit[ch] = peak > size - 129 ? 0 : peak + 128;
        }
        var accum = filterFloorAccum[ch];
        var secondaryPeak = filterSecondaryPeak[ch];
        for (var k = regionStart; k < Math.min(regionEnd + 1, filterFloorLowLimit[ch]); ++k) {
            var absH = Math.abs(filterToAnalyze[k]);
            accum += absH;
            secondaryPeak = secondaryPeak < absH ? absH : secondaryPeak;
        }
        for (var k = Math.max(filterFloorHighLimit[ch], regionStart); k <= regionEnd; ++k) {
            var absH = Math.abs(filterToAnalyze[k]);
            accum += absH;
            secondaryPeak = secondaryPeak < absH ? absH : secondaryPeak;
        }
        filterFloorAccum[ch] = accum;
        filterSecondaryPeak[ch] = secondaryPeak;
        if (regionEnd == size - 1) {
            var filterFloor = filterFloorAccum[ch] / (filterFloorLowLimit[ch] + size - filterFloorHighLimit[ch]);
            var absPeak = Math.abs(filterToAnalyze[peak]);
            significantPeak[ch] = absPeak > 10.f * filterFloor && absPeak > 2.f * filterSecondaryPeak[ch];
        }
        if (significantPeak[ch]) {
            var blocks = renderBuffer.blocks;
            var activeRenderBlock = false;
            for (var r = 0; r < renderBuffer.numChannels; r++) {
                var b = renderBuffer.blockOffset(-filterDelayBlocks[ch], 0, r);
                var xEnergy = 0.f;
                for (var i = 0; i < BLOCK_SIZE; i++) {
                    xEnergy = xEnergy + blocks[b + i] * blocks[b + i];
                }
                if (xEnergy > ACTIVE_RENDER_THRESHOLD) {
                    activeRenderBlock = true;
                    break;
                }
            }
            if (consistentDelayReference[ch] == filterDelayBlocks[ch]) {
                if (activeRenderBlock) {
                    ++consistentEstimateCounter[ch];
                }
            } else {
                consistentEstimateCounter[ch] = 0;
                consistentDelayReference[ch] = filterDelayBlocks[ch];
            }
        }
        return consistentEstimateCounter[ch] > 1.5f * Aec3.NUM_BLOCKS_PER_SECOND;
    }
}
