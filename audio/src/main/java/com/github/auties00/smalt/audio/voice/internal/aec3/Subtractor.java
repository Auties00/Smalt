/* Ports webrtc-audio-processing 2.1 (modules/audio_processing/aec3/subtractor.cc). Copyright (c) 2011, The WebRTC
   project authors; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.voice.internal.aec3;

import static com.github.auties00.smalt.audio.voice.internal.aec3.Aec3.BLOCK_SIZE;
import static com.github.auties00.smalt.audio.voice.internal.aec3.Aec3.FFT_LENGTH;
import static com.github.auties00.smalt.audio.voice.internal.aec3.Aec3.FFT_LENGTH_BY_2;
import static com.github.auties00.smalt.audio.voice.internal.aec3.Aec3.FFT_LENGTH_BY_2_PLUS_1;

/**
 * Subtracts the linear echo estimate from every capture channel with two adaptive filters per
 * channel: a refined filter with a normalized, leaky NLMS update and a fast coarse filter, which is
 * reset to the refined one when it performs poorly for five blocks. Every filter models the echo
 * of all render channels.
 *
 * <p>When a refined filter's error energy grows far beyond the capture energy, the filter is
 * scaled down to correct the misadjustment.
 *
 * @implNote This implementation ports {@code Subtractor} of WebRTC's
 * {@code modules/audio_processing/aec3/subtractor.cc} with the coarse filter reset hangover
 * enabled (no field trials).
 */
final class Subtractor {
    /**
     * The scaling of the inverse transform, {@code 1 / 64}.
     */
    private static final float SCALE = 1.0f / FFT_LENGTH_BY_2;

    /**
     * The tunings.
     */
    private final Aec3Config config;

    /**
     * The number of capture channels.
     */
    private final int numCaptureChannels;

    /**
     * The refined filter of every capture channel.
     */
    private final AdaptiveFirFilter[] refinedFilters;

    /**
     * The coarse filter of every capture channel.
     */
    private final AdaptiveFirFilter[] coarseFilters;

    /**
     * The update gain of every refined filter.
     */
    private final RefinedFilterUpdateGain[] refinedGains;

    /**
     * The update gain of every coarse filter.
     */
    private final CoarseFilterUpdateGain[] coarseGains;

    /**
     * The number of consecutive blocks in which the coarse filter did worse than the refined one,
     * per channel.
     */
    private final int[] poorCoarseFilterCounters;

    /**
     * The number of blocks after a coarse filter reset during which leakage is not increased, per
     * channel.
     */
    private final int[] coarseFilterResetHangovers;

    /**
     * The power responses of every refined filter, 65 per partition.
     */
    final float[][] refinedFrequencyResponses;

    /**
     * The number of partitions of every refined filter's power responses.
     */
    final int[] refinedFrequencyResponsesSizes;

    /**
     * The time-domain impulse response of every refined filter.
     */
    final AdaptiveFirFilter.ImpulseResponse[] refinedImpulseResponses;

    /**
     * The accumulated refined error energy of the misadjustment estimators.
     */
    private final float[] misadjustmentE2Acum;

    /**
     * The accumulated capture energy of the misadjustment estimators.
     */
    private final float[] misadjustmentY2Acum;

    /**
     * The number of accumulated blocks of the misadjustment estimators.
     */
    private final int[] misadjustmentBlocksAcum;

    /**
     * The smoothed inverse misadjustment of every channel.
     */
    private final float[] invMisadjustment;

    /**
     * The number of blocks the misadjustment estimators keep updating after a large error.
     */
    private final int[] misadjustmentOverhang;

    /**
     * The transform.
     */
    private final Aec3Fft fft = new Aec3Fft();

    /**
     * The render power summed over the refined filter's partitions.
     */
    private final float[] x2Refined = new float[FFT_LENGTH_BY_2_PLUS_1];

    /**
     * The render power summed over the coarse filter's partitions, when the sizes differ.
     */
    private final float[] x2CoarseData = new float[FFT_LENGTH_BY_2_PLUS_1];

    /**
     * The scratch filter output spectrum, reused as the update gain.
     */
    private final FftData s = new FftData();

    /**
     * The spectrum of the coarse filter's error.
     */
    private final FftData eCoarse = new FftData();

    /**
     * The scratch echo return loss estimate.
     */
    private final float[] erl = new float[FFT_LENGTH_BY_2_PLUS_1];

    /**
     * The scratch time-domain buffer.
     */
    private final float[] tmp = new float[FFT_LENGTH];

    /**
     * Creates a subtractor in its initial state.
     *
     * @param config the tunings
     * @param numRenderChannels the number of render channels
     * @param numCaptureChannels the number of capture channels
     * @param kernel the kernel of the adaptive filters
     */
    Subtractor(Aec3Config config, int numRenderChannels, int numCaptureChannels, AdaptiveFilterKernel kernel) {
        this.config = config;
        this.numCaptureChannels = numCaptureChannels;
        this.refinedFilters = new AdaptiveFirFilter[numCaptureChannels];
        this.coarseFilters = new AdaptiveFirFilter[numCaptureChannels];
        this.refinedGains = new RefinedFilterUpdateGain[numCaptureChannels];
        this.coarseGains = new CoarseFilterUpdateGain[numCaptureChannels];
        this.poorCoarseFilterCounters = new int[numCaptureChannels];
        this.coarseFilterResetHangovers = new int[numCaptureChannels];
        var refinedMax = Math.max(Aec3.REFINED_INITIAL_LENGTH_BLOCKS, Aec3.REFINED_LENGTH_BLOCKS);
        this.refinedFrequencyResponses = new float[numCaptureChannels][refinedMax * FFT_LENGTH_BY_2_PLUS_1];
        this.refinedFrequencyResponsesSizes = new int[numCaptureChannels];
        this.refinedImpulseResponses = new AdaptiveFirFilter.ImpulseResponse[numCaptureChannels];
        this.misadjustmentE2Acum = new float[numCaptureChannels];
        this.misadjustmentY2Acum = new float[numCaptureChannels];
        this.misadjustmentBlocksAcum = new int[numCaptureChannels];
        this.invMisadjustment = new float[numCaptureChannels];
        this.misadjustmentOverhang = new int[numCaptureChannels];
        for (var ch = 0; ch < numCaptureChannels; ch++) {
            refinedFilters[ch] = new AdaptiveFirFilter(Aec3.REFINED_LENGTH_BLOCKS, Aec3.REFINED_INITIAL_LENGTH_BLOCKS,
                    Aec3.CONFIG_CHANGE_DURATION_BLOCKS, numRenderChannels, kernel);
            coarseFilters[ch] = new AdaptiveFirFilter(config.coarseLengthBlocks(), config.coarseInitialLengthBlocks(),
                    Aec3.CONFIG_CHANGE_DURATION_BLOCKS, numRenderChannels, kernel);
            refinedGains[ch] = new RefinedFilterUpdateGain();
            coarseGains[ch] = new CoarseFilterUpdateGain(config.coarseInitial());
            refinedFrequencyResponsesSizes[ch] = refinedMax;
            refinedImpulseResponses[ch] = new AdaptiveFirFilter.ImpulseResponse(refinedMax * FFT_LENGTH_BY_2);
        }
    }

    /**
     * Resets the filters after an echo path change.
     *
     * @param gainChange whether the echo path gain changed
     * @param delayChange whether the delay changed
     */
    void handleEchoPathChange(boolean gainChange, boolean delayChange) {
        if (delayChange) {
            for (var ch = 0; ch < numCaptureChannels; ch++) {
                refinedFilters[ch].handleEchoPathChange();
                coarseFilters[ch].handleEchoPathChange();
                refinedGains[ch].handleEchoPathChange(gainChange, true);
                coarseGains[ch].handleEchoPathChange();
                refinedGains[ch].setConfig(RefinedFilterUpdateGain.REFINED_INITIAL, true);
                coarseGains[ch].setConfig(config.coarseInitial(), true);
                refinedFilters[ch].setSizePartitions(Aec3.REFINED_INITIAL_LENGTH_BLOCKS, true);
                coarseFilters[ch].setSizePartitions(config.coarseInitialLengthBlocks(), true);
            }
        }
        if (gainChange) {
            for (var ch = 0; ch < numCaptureChannels; ch++) {
                refinedGains[ch].handleEchoPathChange(true, delayChange);
            }
        }
    }

    /**
     * Moves the filters and their gains towards the final tuning.
     */
    void exitInitialState() {
        for (var ch = 0; ch < numCaptureChannels; ch++) {
            refinedGains[ch].setConfig(RefinedFilterUpdateGain.REFINED, false);
            coarseGains[ch].setConfig(config.coarse(), false);
            refinedFilters[ch].setSizePartitions(Aec3.REFINED_LENGTH_BLOCKS, false);
            coarseFilters[ch].setSizePartitions(config.coarseLengthBlocks(), false);
        }
    }

    /**
     * Filters and adapts every channel's filters on a capture block.
     *
     * @param renderBuffer the render buffer
     * @param capture the capture block, the lowest band of every channel first
     * @param renderSignalAnalyzer the narrow-band detector
     * @param saturatedCapture whether the capture signal is saturated
     * @param outputs receives the outputs, one per capture channel
     */
    void process(RenderBuffer renderBuffer, float[] capture, RenderSignalAnalyzer renderSignalAnalyzer,
                 boolean saturatedCapture, SubtractorOutput[] outputs) {
        var refinedSize = refinedFilters[0].sizePartitions();
        var coarseSize = coarseFilters[0].sizePartitions();
        var sameFilterSizes = refinedSize == coarseSize;
        var x2Coarse = sameFilterSizes ? x2Refined : x2CoarseData;
        if (sameFilterSizes) {
            renderBuffer.spectralSum(refinedSize, x2Refined);
        } else if (refinedSize > coarseSize) {
            renderBuffer.spectralSums(coarseSize, refinedSize, x2Coarse, x2Refined);
        } else {
            renderBuffer.spectralSums(refinedSize, coarseSize, x2Refined, x2Coarse);
        }
        for (var ch = 0; ch < numCaptureChannels; ch++) {
            var output = outputs[ch];
            var offset = ch * BLOCK_SIZE;
            var refinedFilter = refinedFilters[ch];
            var coarseFilter = coarseFilters[ch];
            refinedFilter.filter(renderBuffer, s);
            predictionError(s, capture, offset, output.eRefined, output.sRefined);
            coarseFilter.filter(renderBuffer, s);
            predictionError(s, capture, offset, output.eCoarse, output.sCoarse);
            output.computeMetrics(capture, offset);
            var refinedFiltersAdjusted = false;
            updateMisadjustment(ch, output);
            if (invMisadjustment[ch] > 10.f) {
                var scale = 2.f / (float) Math.sqrt(invMisadjustment[ch]);
                refinedFilter.scaleFilter(scale);
                var ir = refinedImpulseResponses[ch];
                for (var i = 0; i < ir.length; i++) {
                    ir.values[i] *= scale;
                }
                for (var k = 0; k < BLOCK_SIZE; ++k) {
                    output.sRefined[k] *= scale;
                    output.eRefined[k] = capture[offset + k] - output.sRefined[k];
                }
                misadjustmentE2Acum[ch] = 0.f;
                misadjustmentY2Acum[ch] = 0.f;
                misadjustmentBlocksAcum[ch] = 0;
                invMisadjustment[ch] = 0.f;
                misadjustmentOverhang[ch] = 0;
                refinedFiltersAdjusted = true;
            }
            fft.zeroPaddedFft(output.eRefined, 0, output.eRefinedSpectrum);
            fft.zeroPaddedFft(output.eCoarse, 0, eCoarse);
            eCoarse.spectrum(output.e2CoarseSpectrum);
            output.eRefinedSpectrum.spectrum(output.e2RefinedSpectrum);
            var g = s;
            if (!refinedFiltersAdjusted) {
                var disallowLeakageDiverged = coarseFilterResetHangovers[ch] > 0;
                computeErl(ch);
                refinedGains[ch].compute(x2Refined, renderSignalAnalyzer, output, erl, refinedFilter.sizePartitions(),
                        saturatedCapture, disallowLeakageDiverged, g);
            } else {
                g.clear();
            }
            refinedFilter.adapt(renderBuffer, g, refinedImpulseResponses[ch]);
            refinedFrequencyResponsesSizes[ch] = refinedFilter.computeFrequencyResponse(refinedFrequencyResponses[ch]);
            poorCoarseFilterCounters[ch] = output.e2Refined < output.e2Coarse ? poorCoarseFilterCounters[ch] + 1 : 0;
            if (poorCoarseFilterCounters[ch] < 5) {
                coarseGains[ch].compute(x2Coarse, renderSignalAnalyzer, eCoarse, coarseFilter.sizePartitions(),
                        saturatedCapture, g);
                coarseFilterResetHangovers[ch] = Math.max(coarseFilterResetHangovers[ch] - 1, 0);
            } else {
                poorCoarseFilterCounters[ch] = 0;
                coarseFilter.setFilter(refinedFilter.sizePartitions(), refinedFilter);
                coarseGains[ch].compute(x2Coarse, renderSignalAnalyzer, output.eRefinedSpectrum,
                        coarseFilter.sizePartitions(), saturatedCapture, g);
                coarseFilterResetHangovers[ch] = Aec3.COARSE_RESET_HANGOVER_BLOCKS;
            }
            coarseFilter.adapt(renderBuffer, g);
            var eRefined = output.eRefined;
            for (var k = 0; k < BLOCK_SIZE; k++) {
                var a = eRefined[k];
                eRefined[k] = a <= -32768.f ? -32768.f : (a >= 32767.f ? 32767.f : a);
            }
        }
    }

    /**
     * Sums the power responses of a channel's refined filter into {@link #erl}.
     *
     * @param ch the channel
     */
    private void computeErl(int ch) {
        var e = erl;
        java.util.Arrays.fill(e, 0.f);
        var responses = refinedFrequencyResponses[ch];
        for (var p = 0; p < refinedFrequencyResponsesSizes[ch]; p++) {
            var o = p * FFT_LENGTH_BY_2_PLUS_1;
            for (var k = 0; k < FFT_LENGTH_BY_2_PLUS_1; k++) {
                e[k] = responses[o + k] + e[k];
            }
        }
    }

    /**
     * Updates the misadjustment estimate of a channel ({@code FilterMisadjustmentEstimator}).
     *
     * @param ch the channel
     * @param output the output of the channel's filters
     */
    private void updateMisadjustment(int ch, SubtractorOutput output) {
        var nBlocks = 4;
        misadjustmentE2Acum[ch] += output.e2Refined;
        misadjustmentY2Acum[ch] += output.y2;
        if (++misadjustmentBlocksAcum[ch] == nBlocks) {
            if (misadjustmentY2Acum[ch] > nBlocks * 200.f * 200.f * BLOCK_SIZE) {
                var update = misadjustmentE2Acum[ch] / misadjustmentY2Acum[ch];
                if (misadjustmentE2Acum[ch] > nBlocks * 7500.f * 7500.f * BLOCK_SIZE) {
                    misadjustmentOverhang[ch] = 4;
                } else {
                    misadjustmentOverhang[ch] = Math.max(misadjustmentOverhang[ch] - 1, 0);
                }
                if ((update < invMisadjustment[ch]) || (misadjustmentOverhang[ch] > 0)) {
                    invMisadjustment[ch] += 0.1f * (update - invMisadjustment[ch]);
                }
            }
            misadjustmentE2Acum[ch] = 0.f;
            misadjustmentY2Acum[ch] = 0.f;
            misadjustmentBlocksAcum[ch] = 0;
        }
    }

    private void predictionError(FftData s, float[] y, int offset, float[] e, float[] sOut) {
        Aec3Fft.ifft(s.re, s.im, 0, tmp);
        for (var k = 0; k < BLOCK_SIZE; k++) {
            e[k] = y[offset + k] - tmp[FFT_LENGTH_BY_2 + k] * SCALE;
        }
        for (var k = 0; k < BLOCK_SIZE; ++k) {
            sOut[k] = SCALE * tmp[k + FFT_LENGTH_BY_2];
        }
    }
}
