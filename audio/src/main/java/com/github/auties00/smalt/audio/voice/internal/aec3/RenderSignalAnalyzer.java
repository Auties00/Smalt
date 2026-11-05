/* Ports webrtc-audio-processing 2.1 (modules/audio_processing/aec3/render_signal_analyzer.cc). Copyright (c) 2011,
   The WebRTC project authors; BSD 3-Clause License. Modified for Smalt. */
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
 * {@code modules/audio_processing/aec3/render_signal_analyzer.cc}.
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
     * The scratch per-bin counts of channels with a narrow-band peak.
     */
    private final int[] channelCounters = new int[FFT_LENGTH_BY_2 - 1];

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
     * @param renderBuffer the render buffer
     * @param delayPartitions the delay of the echo path in blocks, or {@code -1} when unknown
     */
    void update(RenderBuffer renderBuffer, int delayPartitions) {
        identifySmallNarrowBandRegions(renderBuffer, delayPartitions);
        identifyStrongNarrowBandComponent(renderBuffer);
    }

    /**
     * {@return whether some bin has had a narrow-band peak for more than 10 blocks}
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
     * Zeroes the bins of a spectrum around the narrow-band regions.
     *
     * @param v the 65 bins, modified in place
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
     * {@return the bin of the strong narrow-band peak, or {@code -1} when there is none}
     */
    int narrowPeakBand() {
        return narrowPeakBand;
    }

    private void identifySmallNarrowBandRegions(RenderBuffer renderBuffer, int delayPartitions) {
        if (delayPartitions < 0) {
            Arrays.fill(narrowBandCounters, 0);
            return;
        }
        Arrays.fill(channelCounters, 0);
        var x2 = renderBuffer.spectra;
        for (var ch = 0; ch < renderBuffer.numChannels; ch++) {
            var o = renderBuffer.spectrumOffset(delayPartitions, ch);
            for (var k = 1; k < FFT_LENGTH_BY_2; ++k) {
                var neighbor = x2[o + k - 1] < x2[o + k + 1] ? x2[o + k + 1] : x2[o + k - 1];
                if (x2[o + k] > 3 * neighbor) {
                    ++channelCounters[k - 1];
                }
            }
        }
        for (var k = 1; k < FFT_LENGTH_BY_2; ++k) {
            narrowBandCounters[k - 1] = channelCounters[k - 1] > 0 ? narrowBandCounters[k - 1] + 1 : 0;
        }
    }

    private void identifyStrongNarrowBandComponent(RenderBuffer renderBuffer) {
        if (narrowPeakBand >= 0 && ++narrowPeakCounter > STRONG_PEAK_FREEZE_DURATION) {
            narrowPeakBand = -1;
        }
        var blocks = renderBuffer.blocks;
        var x2 = renderBuffer.spectra;
        var maxPeakLevel = 0.f;
        for (var ch = 0; ch < renderBuffer.numChannels; ch++) {
            var o = renderBuffer.spectrumOffset(0, ch);
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
            var maxAbs = maxAbs(blocks, renderBuffer.blockOffset(0, 0, ch));
            if (renderBuffer.numBands > 1) {
                var maxAbs1 = maxAbs(blocks, renderBuffer.blockOffset(0, 1, ch));
                maxAbs = maxAbs < maxAbs1 ? maxAbs1 : maxAbs;
            }
            var peakLevel = x2[o + peakBin];
            if (peakBin > 0 && maxAbs > 100 && peakLevel > 100 * nonPeakPower) {
                if (peakLevel > maxPeakLevel) {
                    maxPeakLevel = peakLevel;
                    narrowPeakBand = peakBin;
                    narrowPeakCounter = 0;
                }
            }
        }
    }

    /**
     * Returns the larger magnitude of the first smallest and the last largest sample of a block
     * ({@code std::minmax_element}).
     *
     * @param blocks the samples of the render blocks
     * @param b the index of the first of the 64 samples
     * @return the magnitude
     */
    private static float maxAbs(float[] blocks, int b) {
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
        return Math.abs(min) < Math.abs(max) ? Math.abs(max) : Math.abs(min);
    }
}
