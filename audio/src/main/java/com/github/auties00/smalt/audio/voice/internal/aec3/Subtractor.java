/* Ports webrtc-audio-processing 2.1 (modules/audio_processing/aec3/subtractor.cc). Copyright (c) 2011, The WebRTC
   project authors; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.voice.internal.aec3;

import static com.github.auties00.smalt.audio.voice.internal.aec3.Aec3.BLOCK_SIZE;
import static com.github.auties00.smalt.audio.voice.internal.aec3.Aec3.FFT_LENGTH;
import static com.github.auties00.smalt.audio.voice.internal.aec3.Aec3.FFT_LENGTH_BY_2;
import static com.github.auties00.smalt.audio.voice.internal.aec3.Aec3.FFT_LENGTH_BY_2_PLUS_1;

/**
 * Subtracts the linear echo estimate from the capture signal with two adaptive filters: a refined
 * filter with a normalized, leaky NLMS update and a fast coarse filter, which is reset to the
 * refined one when it performs poorly for five blocks.
 *
 * <p>When the refined filter's error energy grows far beyond the capture energy, the filter is
 * scaled down to correct the misadjustment.
 *
 * @implNote This implementation ports {@code Subtractor} of WebRTC's
 * {@code modules/audio_processing/aec3/subtractor.cc} for one render and one capture channel,
 * with the coarse filter reset hangover enabled (no field trials).
 */
final class Subtractor {
    /**
     * The scaling of the inverse transform, {@code 1 / 64}.
     */
    private static final float SCALE = 1.0f / FFT_LENGTH_BY_2;

    /**
     * The refined filter.
     */
    private final AdaptiveFirFilter refinedFilter;

    /**
     * The coarse filter.
     */
    private final AdaptiveFirFilter coarseFilter;

    /**
     * The update gain of the refined filter.
     */
    private final RefinedFilterUpdateGain refinedGain = new RefinedFilterUpdateGain();

    /**
     * The update gain of the coarse filter.
     */
    private final CoarseFilterUpdateGain coarseGain = new CoarseFilterUpdateGain();

    /**
     * The number of consecutive blocks the coarse filter did worse than the refined one.
     */
    private int poorCoarseFilterCounter;

    /**
     * The blocks left during which the diverged leakage of the refined gain is disallowed after
     * a coarse filter reset.
     */
    private int coarseFilterResetHangover;

    /**
     * The power responses of the refined filter partitions, 65 per partition.
     */
    final float[] refinedFrequencyResponses = new float[Aec3.REFINED_LENGTH_BLOCKS * FFT_LENGTH_BY_2_PLUS_1];

    /**
     * The number of partitions in {@link #refinedFrequencyResponses}.
     */
    int refinedFrequencyResponsesSize = Math.max(Aec3.REFINED_INITIAL_LENGTH_BLOCKS, Aec3.REFINED_LENGTH_BLOCKS);

    /**
     * The time-domain response of the refined filter.
     */
    final AdaptiveFirFilter.ImpulseResponse refinedImpulseResponse = new AdaptiveFirFilter.ImpulseResponse(
            Math.max(Aec3.REFINED_INITIAL_LENGTH_BLOCKS, Aec3.REFINED_LENGTH_BLOCKS) * FFT_LENGTH_BY_2);

    /**
     * The accumulated refined error energy of the misadjustment estimator.
     */
    private float misadjustmentE2Acum;

    /**
     * The accumulated capture energy of the misadjustment estimator.
     */
    private float misadjustmentY2Acum;

    /**
     * The number of accumulated blocks of the misadjustment estimator.
     */
    private int misadjustmentBlocksAcum;

    /**
     * The estimated inverse misadjustment of the refined filter.
     */
    private float invMisadjustment;

    /**
     * The hangover of the misadjustment estimator after a large error.
     */
    private int misadjustmentOverhang;

    /**
     * The transform.
     */
    private final Aec3Fft fft = new Aec3Fft();

    /**
     * The render power summed over the refined filter partitions.
     */
    private final float[] x2Refined = new float[FFT_LENGTH_BY_2_PLUS_1];

    /**
     * The render power summed over the coarse filter partitions.
     */
    private final float[] x2CoarseData = new float[FFT_LENGTH_BY_2_PLUS_1];

    /**
     * The filter output spectrum, also used for the update gain.
     */
    private final FftData s = new FftData();

    /**
     * The Hanning-windowed spectrum of the coarse filter error.
     */
    private final FftData eCoarse = new FftData();

    /**
     * The echo return loss estimate of the refined filter.
     */
    private final float[] erl = new float[FFT_LENGTH_BY_2_PLUS_1];

    /**
     * The scratch buffer of the inverse transform.
     */
    private final float[] tmp = new float[FFT_LENGTH];

    /**
     * Creates a subtractor with zero filters in their initial sizes.
     *
     * @param kernel the kernel of the adaptive filters
     */
    Subtractor(AdaptiveFilterKernel kernel) {
        this.refinedFilter = new AdaptiveFirFilter(Aec3.REFINED_LENGTH_BLOCKS, Aec3.REFINED_INITIAL_LENGTH_BLOCKS,
                Aec3.CONFIG_CHANGE_DURATION_BLOCKS, kernel);
        this.coarseFilter = new AdaptiveFirFilter(Aec3.COARSE_LENGTH_BLOCKS, Aec3.COARSE_INITIAL_LENGTH_BLOCKS,
                Aec3.CONFIG_CHANGE_DURATION_BLOCKS, kernel);
    }

    /**
     * Resets the filters to their initial configuration on a delay change, and the refined gain
     * on a gain change.
     *
     * @param gainChange whether the echo path gain changed
     * @param delayChange whether the echo path delay changed
     */
    void handleEchoPathChange(boolean gainChange, boolean delayChange) {
        if (delayChange) {
            refinedFilter.handleEchoPathChange();
            coarseFilter.handleEchoPathChange();
            refinedGain.handleEchoPathChange(gainChange, true);
            coarseGain.handleEchoPathChange();
            refinedGain.setConfig(RefinedFilterUpdateGain.REFINED_INITIAL, true);
            coarseGain.setConfig(CoarseFilterUpdateGain.COARSE_INITIAL, true);
            refinedFilter.setSizePartitions(Aec3.REFINED_INITIAL_LENGTH_BLOCKS, true);
            coarseFilter.setSizePartitions(Aec3.COARSE_INITIAL_LENGTH_BLOCKS, true);
        }
        if (gainChange) {
            refinedGain.handleEchoPathChange(true, delayChange);
        }
    }

    /**
     * Switches the filters and gains gradually to their final configuration.
     */
    void exitInitialState() {
        refinedGain.setConfig(RefinedFilterUpdateGain.REFINED, false);
        coarseGain.setConfig(CoarseFilterUpdateGain.COARSE, false);
        refinedFilter.setSizePartitions(Aec3.REFINED_LENGTH_BLOCKS, false);
        coarseFilter.setSizePartitions(Aec3.COARSE_LENGTH_BLOCKS, false);
    }

    /**
     * Filters, subtracts and adapts for one capture block.
     *
     * @param renderBuffer the render rings
     * @param capture the capture samples
     * @param offset the index of the first of the 64 capture samples
     * @param renderSignalAnalyzer the narrow-band detector
     * @param saturatedCapture whether the capture signal is saturated
     * @param output receives the filter outputs
     */
    void process(RenderBuffer renderBuffer, float[] capture, int offset, RenderSignalAnalyzer renderSignalAnalyzer,
                 boolean saturatedCapture, SubtractorOutput output) {
        var refinedSize = refinedFilter.sizePartitions();
        var coarseSize = coarseFilter.sizePartitions();
        var sameFilterSizes = refinedSize == coarseSize;
        var x2Coarse = sameFilterSizes ? x2Refined : x2CoarseData;
        if (sameFilterSizes) {
            renderBuffer.spectralSum(refinedSize, x2Refined);
        } else if (refinedSize > coarseSize) {
            renderBuffer.spectralSums(coarseSize, refinedSize, x2Coarse, x2Refined);
        } else {
            renderBuffer.spectralSums(refinedSize, coarseSize, x2Refined, x2Coarse);
        }
        refinedFilter.filter(renderBuffer, s);
        predictionError(s, capture, offset, output.eRefined, output.sRefined);
        coarseFilter.filter(renderBuffer, s);
        predictionError(s, capture, offset, output.eCoarse, output.sCoarse);
        output.computeMetrics(capture, offset);
        var refinedFiltersAdjusted = false;
        updateMisadjustment(output);
        if (invMisadjustment > 10.f) {
            var scale = 2.f / (float) Math.sqrt(invMisadjustment);
            refinedFilter.scaleFilter(scale);
            var ir = refinedImpulseResponse;
            for (var i = 0; i < ir.length; i++) {
                ir.values[i] *= scale;
            }
            for (var k = 0; k < BLOCK_SIZE; ++k) {
                output.sRefined[k] *= scale;
                output.eRefined[k] = capture[offset + k] - output.sRefined[k];
            }
            misadjustmentE2Acum = 0.f;
            misadjustmentY2Acum = 0.f;
            misadjustmentBlocksAcum = 0;
            invMisadjustment = 0.f;
            misadjustmentOverhang = 0;
            refinedFiltersAdjusted = true;
        }
        fft.zeroPaddedFft(output.eRefined, 0, output.eRefinedSpectrum);
        fft.zeroPaddedFft(output.eCoarse, 0, eCoarse);
        eCoarse.spectrum(output.e2CoarseSpectrum);
        output.eRefinedSpectrum.spectrum(output.e2RefinedSpectrum);
        var g = s;
        if (!refinedFiltersAdjusted) {
            var disallowLeakageDiverged = coarseFilterResetHangover > 0;
            computeErl();
            refinedGain.compute(x2Refined, renderSignalAnalyzer, output, erl, refinedFilter.sizePartitions(),
                    saturatedCapture, disallowLeakageDiverged, g);
        } else {
            g.clear();
        }
        refinedFilter.adapt(renderBuffer, g, refinedImpulseResponse);
        refinedFrequencyResponsesSize = refinedFilter.computeFrequencyResponse(refinedFrequencyResponses);
        poorCoarseFilterCounter = output.e2Refined < output.e2Coarse ? poorCoarseFilterCounter + 1 : 0;
        if (poorCoarseFilterCounter < 5) {
            coarseGain.compute(x2Coarse, renderSignalAnalyzer, eCoarse, coarseFilter.sizePartitions(), saturatedCapture, g);
            coarseFilterResetHangover = Math.max(coarseFilterResetHangover - 1, 0);
        } else {
            poorCoarseFilterCounter = 0;
            coarseFilter.setFilter(refinedFilter.sizePartitions(), refinedFilter);
            coarseGain.compute(x2Coarse, renderSignalAnalyzer, output.eRefinedSpectrum, coarseFilter.sizePartitions(),
                    saturatedCapture, g);
            coarseFilterResetHangover = Aec3.COARSE_RESET_HANGOVER_BLOCKS;
        }
        coarseFilter.adapt(renderBuffer, g);
        var eRefined = output.eRefined;
        for (var k = 0; k < BLOCK_SIZE; k++) {
            var a = eRefined[k];
            eRefined[k] = a <= -32768.f ? -32768.f : (a >= 32767.f ? 32767.f : a);
        }
    }

    /**
     * Computes the echo return loss estimate, the sum of the refined power responses.
     */
    private void computeErl() {
        var e = erl;
        java.util.Arrays.fill(e, 0.f);
        for (var p = 0; p < refinedFrequencyResponsesSize; p++) {
            var o = p * FFT_LENGTH_BY_2_PLUS_1;
            for (var k = 0; k < FFT_LENGTH_BY_2_PLUS_1; k++) {
                e[k] = refinedFrequencyResponses[o + k] + e[k];
            }
        }
    }

    /**
     * Updates the misadjustment estimator with the energies of a block.
     *
     * @param output the subtractor output of the block
     */
    private void updateMisadjustment(SubtractorOutput output) {
        var nBlocks = 4;
        misadjustmentE2Acum += output.e2Refined;
        misadjustmentY2Acum += output.y2;
        if (++misadjustmentBlocksAcum == nBlocks) {
            if (misadjustmentY2Acum > nBlocks * 200.f * 200.f * BLOCK_SIZE) {
                var update = misadjustmentE2Acum / misadjustmentY2Acum;
                if (misadjustmentE2Acum > nBlocks * 7500.f * 7500.f * BLOCK_SIZE) {
                    misadjustmentOverhang = 4;
                } else {
                    misadjustmentOverhang = Math.max(misadjustmentOverhang - 1, 0);
                }
                if ((update < invMisadjustment) || (misadjustmentOverhang > 0)) {
                    invMisadjustment += 0.1f * (update - invMisadjustment);
                }
            }
            misadjustmentE2Acum = 0.f;
            misadjustmentY2Acum = 0.f;
            misadjustmentBlocksAcum = 0;
        }
    }

    /**
     * Computes the time-domain echo estimate and the error from a filter output spectrum.
     *
     * @param s the filter output spectrum
     * @param y the capture samples
     * @param offset the index of the first of the 64 capture samples
     * @param e receives the error {@code y - s}
     * @param sOut receives the echo estimate
     */
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
