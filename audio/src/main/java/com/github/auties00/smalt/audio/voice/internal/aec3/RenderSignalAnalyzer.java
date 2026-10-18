package com.github.auties00.smalt.audio.voice.internal.aec3;

import java.util.Arrays;

import static com.github.auties00.smalt.audio.voice.internal.aec3.Aec3.BLOCK_SIZE;
import static com.github.auties00.smalt.audio.voice.internal.aec3.Aec3.FFT_LENGTH_BY_2;
import static com.github.auties00.smalt.audio.voice.internal.aec3.Aec3.FFT_LENGTH_BY_2_PLUS_1;

/**
 * Detects narrow-band render signals, which excite the echo path too poorly for the adaptive
 * filters to be updated reliably around them.
 *
 * @implNote This implementation ports WebRTC's
 * {@code modules/audio_processing/aec3/render_signal_analyzer.cc} for one render channel and
 * one band.
 */
final class RenderSignalAnalyzer {
    /**
     * The number of consecutive narrow-band blocks after which a bin is masked.
     */
    private static final int COUNTER_THRESHOLD = 5;

    /**
     * The number of blocks a detected strong narrow-band peak is remembered.
     */
    private static final int STRONG_PEAK_FREEZE_DURATION = Aec3.REFINED_LENGTH_BLOCKS;

    /**
     * The per-bin counts of consecutive blocks with a narrow-band peak, for bins 1 to 63.
     */
    private final int[] narrowBandCounters = new int[FFT_LENGTH_BY_2 - 1];

    /**
     * The bin of the strong narrow-band peak, or {@code -1} when there is none.
     */
    private int narrowPeakBand = -1;

    /**
     * The number of blocks since the strong peak was detected.
     */
    private int narrowPeakCounter;

    /**
     * Creates an analyzer that has detected nothing.
     */
    RenderSignalAnalyzer() {
    }

    /**
     * Updates the detection with the latest render data.
     *
     * @param renderBuffer    the render rings
     * @param delayPartitions the delay of the echo path in blocks, or {@code -1} when unknown
     */
    void update(RenderBuffer renderBuffer, int delayPartitions) {
        identifySmallNarrowBandRegions(renderBuffer, delayPartitions);
        identifyStrongNarrowBandComponent(renderBuffer);
    }

    /**
     * Returns whether a narrow-band region has persisted long enough to make the excitation poor.
     *
     * @return whether any bin has been narrow-band for more than ten blocks
     */
    boolean poorSignalExcitation() {
        for (var counter : narrowBandCounters) {
            if (counter > 10) {
                return true;
            }
        }
        return false;
    }

    /**
     * Zeroes the bins of a vector around the persistent narrow-band regions.
     *
     * @param v the 65 values
     */
    void maskRegionsAroundNarrowBands(float[] v) {
        if (narrowBandCounters[0] > COUNTER_THRESHOLD) {
            v[1] = v[0] = 0.f;
        }
        for (var k = 2; k < FFT_LENGTH_BY_2 - 1; ++k) {
            if (narrowBandCounters[k - 1] > COUNTER_THRESHOLD) {
                v[k - 2] = v[k - 1] = v[k] = v[k + 1] = v[k + 2] = 0.f;
            }
        }
        if (narrowBandCounters[FFT_LENGTH_BY_2 - 2] > COUNTER_THRESHOLD) {
            v[FFT_LENGTH_BY_2] = v[FFT_LENGTH_BY_2 - 1] = 0.f;
        }
    }

    /**
     * Returns the bin of the strong narrow-band peak.
     *
     * @return the bin, or {@code -1} when there is none
     */
    int narrowPeakBand() {
        return narrowPeakBand;
    }

    /**
     * Counts, per bin, the consecutive blocks whose power exceeds three times both neighbors.
     *
     * @param renderBuffer    the render rings
     * @param delayPartitions the delay of the echo path in blocks, or {@code -1} when unknown
     */
    private void identifySmallNarrowBandRegions(RenderBuffer renderBuffer, int delayPartitions) {
        if (delayPartitions < 0) {
            Arrays.fill(narrowBandCounters, 0);
            return;
        }
        var x2 = renderBuffer.spectra;
        var o = renderBuffer.spectrumOffset(delayPartitions);
        for (var k = 1; k < FFT_LENGTH_BY_2; ++k) {
            var neighbor = x2[o + k - 1] < x2[o + k + 1] ? x2[o + k + 1] : x2[o + k - 1];
            var narrow = x2[o + k] > 3 * neighbor;
            narrowBandCounters[k - 1] = narrow ? narrowBandCounters[k - 1] + 1 : 0;
        }
    }

    /**
     * Detects a strong isolated spectral peak in the latest render block.
     *
     * @param renderBuffer the render rings
     */
    private void identifyStrongNarrowBandComponent(RenderBuffer renderBuffer) {
        if (narrowPeakBand >= 0 && ++narrowPeakCounter > STRONG_PEAK_FREEZE_DURATION) {
            narrowPeakBand = -1;
        }
        var blocks = renderBuffer.blocks;
        var b = renderBuffer.blockOffset(0);
        var x2 = renderBuffer.spectra;
        var o = renderBuffer.spectrumOffset(0);
        var maxPeakLevel = 0.f;
        var peakBin = 0;
        for (var k = 1; k < FFT_LENGTH_BY_2_PLUS_1; k++) {
            if (x2[o + peakBin] < x2[o + k]) {
                peakBin = k;
            }
        }
        var nonPeakPower = 0.f;
        for (var k = Math.max(0, peakBin - 14); k < peakBin - 4; ++k) {
            nonPeakPower = nonPeakPower < x2[o + k] ? x2[o + k] : nonPeakPower;
        }
        for (var k = peakBin + 5; k < Math.min(peakBin + 15, FFT_LENGTH_BY_2_PLUS_1); ++k) {
            nonPeakPower = nonPeakPower < x2[o + k] ? x2[o + k] : nonPeakPower;
        }
        var min = blocks[b];
        var max = blocks[b];
        for (var i = 1; i < BLOCK_SIZE; i++) {
            var v = blocks[b + i];
            if (v < min) {
                min = v;
            }
            if (!(v < max)) {
                max = v;
            }
        }
        var maxAbs = Math.abs(min) < Math.abs(max) ? Math.abs(max) : Math.abs(min);
        var peakLevel = x2[o + peakBin];
        if (peakBin > 0 && maxAbs > 100 && peakLevel > 100 * nonPeakPower) {
            if (peakLevel > maxPeakLevel) {
                narrowPeakBand = peakBin;
                narrowPeakCounter = 0;
            }
        }
    }
}
