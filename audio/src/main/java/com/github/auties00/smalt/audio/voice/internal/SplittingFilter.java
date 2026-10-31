/* Ports webrtc-audio-processing 2.1 (modules/audio_processing/splitting_filter.cc, three_band_filter_bank.cc,
   common_audio/signal_processing/splitting_filter.c). Copyright (c) 2011, The WebRTC project authors; BSD 3-Clause
   License. Modified for Smalt. */
package com.github.auties00.smalt.audio.voice.internal;

/**
 * Splits 10 ms frames of 32 or 48 kHz audio into bands of 8 kHz bandwidth at a 16 kHz rate and
 * merges them back, one state per channel.
 *
 * <p>Two bands (32 kHz) use a pair of fixed-point all-pass QMF filters on samples rounded to 16
 * bits; three bands (48 kHz) use a sparse polyphase filter bank with a DCT modulation in single
 * precision. Samples are in the 16-bit range. Instances are not thread-safe.
 *
 * @implNote This implementation ports {@code SplittingFilter}, {@code ThreeBandFilterBank} and
 * {@code WebRtcSpl_AnalysisQMF} / {@code WebRtcSpl_SynthesisQMF} of WebRTC.
 */
public final class SplittingFilter {
    /**
     * The samples of a band.
     */
    private static final int SPLIT_BAND_SIZE = 160;

    /**
     * The coefficients of the first all-pass QMF filter chain.
     */
    private static final int[] ALL_PASS_FILTER_1 = {6418, 36982, 57261};

    /**
     * The coefficients of the second all-pass QMF filter chain.
     */
    private static final int[] ALL_PASS_FILTER_2 = {21333, 49062, 63010};

    /**
     * The sparsity stride of the three-band filters.
     */
    private static final int STRIDE = 4;

    /**
     * The base-2 logarithm of {@link #STRIDE}.
     */
    private static final int STRIDE_LOG2 = 2;

    /**
     * The number of non-zero taps of a three-band filter.
     */
    private static final int FILTER_SIZE = 4;

    /**
     * The state length of a three-band filter.
     */
    private static final int MEMORY_SIZE = FILTER_SIZE * STRIDE - 1;

    /**
     * The number of non-zero three-band filters.
     */
    private static final int NUM_NON_ZERO_FILTERS = 10;

    /**
     * The polyphase indices whose filter is zero.
     */
    private static final int ZERO_FILTER_INDEX_1 = 3;

    /**
     * The second polyphase index whose filter is zero.
     */
    private static final int ZERO_FILTER_INDEX_2 = 9;

    /**
     * The taps of the non-zero three-band filters ({@code kFilterCoeffs}).
     */
    private static final float[][] FILTER_COEFFS = {
            {-0.00047749f, -0.00496888f, +0.16547118f, +0.00425496f},
            {-0.00173287f, -0.01585778f, +0.14989004f, +0.00994113f},
            {-0.00304815f, -0.02536082f, +0.12154542f, +0.01157993f},
            {-0.00346946f, -0.02587886f, +0.04760441f, +0.00607594f},
            {-0.00154717f, -0.01136076f, +0.01387458f, +0.00186353f},
            {+0.00186353f, +0.01387458f, -0.01136076f, -0.00154717f},
            {+0.00607594f, +0.04760441f, -0.02587886f, -0.00346946f},
            {+0.00983212f, +0.08543175f, -0.02982767f, -0.00383509f},
            {+0.00994113f, +0.14989004f, -0.01585778f, -0.00173287f},
            {+0.00425496f, +0.16547118f, -0.00496888f, -0.00047749f}};

    /**
     * The DCT modulation of every filter and band ({@code kDctModulation}).
     */
    private static final float[][] DCT_MODULATION = {
            {2.f, 2.f, 2.f},
            {1.73205077f, 0.f, -1.73205077f},
            {1.f, -2.f, 1.f},
            {-1.f, 2.f, -1.f},
            {-1.73205077f, 0.f, 1.73205077f},
            {-2.f, -2.f, -2.f},
            {-1.73205077f, 0.f, 1.73205077f},
            {-1.f, 2.f, -1.f},
            {1.f, -2.f, 1.f},
            {1.73205077f, 0.f, -1.73205077f}};

    /**
     * The number of bands, 2 or 3.
     */
    private final int numBands;

    /**
     * The QMF analysis states, two chains of six values per channel.
     */
    private final int[][] analysisState1;

    /**
     * The second QMF analysis state of every channel.
     */
    private final int[][] analysisState2;

    /**
     * The first QMF synthesis state of every channel.
     */
    private final int[][] synthesisState1;

    /**
     * The second QMF synthesis state of every channel.
     */
    private final int[][] synthesisState2;

    /**
     * The three-band analysis states, {@code [channel][filter][MEMORY_SIZE]}.
     */
    private final float[][][] threeBandAnalysisState;

    /**
     * The three-band synthesis states, {@code [channel][filter][MEMORY_SIZE]}.
     */
    private final float[][][] threeBandSynthesisState;

    /**
     * The 16-bit full-band scratch frame.
     */
    private final short[] fullBand16 = new short[2 * SPLIT_BAND_SIZE];

    /**
     * The 16-bit low-band scratch.
     */
    private final short[] low16 = new short[SPLIT_BAND_SIZE];

    /**
     * The 16-bit high-band scratch.
     */
    private final short[] high16 = new short[SPLIT_BAND_SIZE];

    /**
     * The QMF scratch buffers.
     */
    private final int[] halfIn1 = new int[SPLIT_BAND_SIZE];

    /**
     * The second QMF input scratch.
     */
    private final int[] halfIn2 = new int[SPLIT_BAND_SIZE];

    /**
     * The first QMF output scratch.
     */
    private final int[] filter1 = new int[SPLIT_BAND_SIZE];

    /**
     * The second QMF output scratch.
     */
    private final int[] filter2 = new int[SPLIT_BAND_SIZE];

    /**
     * The subsampled three-band input scratch.
     */
    private final float[] inSubsampled = new float[SPLIT_BAND_SIZE];

    /**
     * The filtered three-band scratch.
     */
    private final float[] outSubsampled = new float[SPLIT_BAND_SIZE];

    /**
     * Creates a filter.
     *
     * @param numChannels the number of channels
     * @param numBands the number of bands, 2 for 32 kHz or 3 for 48 kHz
     */
    public SplittingFilter(int numChannels, int numBands) {
        if (numBands != 2 && numBands != 3) {
            throw new IllegalArgumentException("bands must be 2 or 3: " + numBands);
        }
        this.numBands = numBands;
        var twoBands = numBands == 2 ? numChannels : 0;
        this.analysisState1 = new int[twoBands][6];
        this.analysisState2 = new int[twoBands][6];
        this.synthesisState1 = new int[twoBands][6];
        this.synthesisState2 = new int[twoBands][6];
        var threeBands = numBands == 3 ? numChannels : 0;
        this.threeBandAnalysisState = new float[threeBands][NUM_NON_ZERO_FILTERS][MEMORY_SIZE];
        this.threeBandSynthesisState = new float[threeBands][NUM_NON_ZERO_FILTERS][MEMORY_SIZE];
    }

    /**
     * Splits the full band of a channel into its bands.
     *
     * @param channel the index of the channel whose filter states are used
     * @param data the full-band frame, {@code 160 * numBands} samples from index 0
     * @param bands receives the bands, 160 samples each from index 0
     */
    public void analysis(int channel, float[] data, float[][] bands) {
        if (numBands == 2) {
            twoBandsAnalysis(channel, data, bands[0], bands[1]);
        } else {
            threeBandsAnalysis(threeBandAnalysisState[channel], data, bands);
        }
    }

    /**
     * Merges the bands of a channel into its full band.
     *
     * @param channel the index of the channel whose filter states are used
     * @param bands the bands, 160 samples each from index 0
     * @param data receives the full-band frame, {@code 160 * numBands} samples from index 0
     */
    public void synthesis(int channel, float[][] bands, float[] data) {
        if (numBands == 2) {
            twoBandsSynthesis(channel, bands[0], bands[1], data);
        } else {
            threeBandsSynthesis(threeBandSynthesisState[channel], bands, data);
        }
    }

    private void twoBandsAnalysis(int channel, float[] data, float[] low, float[] high) {
        var full = fullBand16;
        for (var i = 0; i < 2 * SPLIT_BAND_SIZE; i++) {
            full[i] = FixedPointMath.floatS16ToS16(data[i]);
        }
        for (int i = 0, k = 0; i < SPLIT_BAND_SIZE; i++, k += 2) {
            halfIn2[i] = full[k] * (1 << 10);
            halfIn1[i] = full[k + 1] * (1 << 10);
        }
        allPassQmf(halfIn1, filter1, ALL_PASS_FILTER_1, analysisState1[channel]);
        allPassQmf(halfIn2, filter2, ALL_PASS_FILTER_2, analysisState2[channel]);
        for (var i = 0; i < SPLIT_BAND_SIZE; i++) {
            low[i] = FixedPointMath.satW32ToW16((filter1[i] + filter2[i] + 1024) >> 11);
            high[i] = FixedPointMath.satW32ToW16((filter1[i] - filter2[i] + 1024) >> 11);
        }
    }

    private void twoBandsSynthesis(int channel, float[] low, float[] high, float[] data) {
        var lowBand = low16;
        var highBand = high16;
        for (var i = 0; i < SPLIT_BAND_SIZE; i++) {
            lowBand[i] = FixedPointMath.floatS16ToS16(low[i]);
            highBand[i] = FixedPointMath.floatS16ToS16(high[i]);
        }
        for (var i = 0; i < SPLIT_BAND_SIZE; i++) {
            halfIn1[i] = (lowBand[i] + highBand[i]) * (1 << 10);
            halfIn2[i] = (lowBand[i] - highBand[i]) * (1 << 10);
        }
        allPassQmf(halfIn1, filter1, ALL_PASS_FILTER_2, synthesisState1[channel]);
        allPassQmf(halfIn2, filter2, ALL_PASS_FILTER_1, synthesisState2[channel]);
        for (int i = 0, k = 0; i < SPLIT_BAND_SIZE; i++) {
            data[k++] = FixedPointMath.satW32ToW16((filter2[i] + 512) >> 10);
            data[k++] = FixedPointMath.satW32ToW16((filter1[i] + 512) >> 10);
        }
    }

    /**
     * Runs three cascaded first-order all-pass sections ({@code WebRtcSpl_AllPassQMF}); the input
     * is used as scratch and holds the second section's output on return.
     *
     * @param in the input, overwritten
     * @param out receives the output
     * @param coefficients the three section coefficients, unsigned 16-bit
     * @param state the six state values
     */
    private static void allPassQmf(int[] in, int[] out, int[] coefficients, int[] state) {
        var length = SPLIT_BAND_SIZE;
        var diff = subSatW32(in[0], state[1]);
        out[0] = FixedPointMath.scaleDiff32(coefficients[0], diff, state[0]);
        for (var k = 1; k < length; k++) {
            diff = subSatW32(in[k], out[k - 1]);
            out[k] = FixedPointMath.scaleDiff32(coefficients[0], diff, in[k - 1]);
        }
        state[0] = in[length - 1];
        state[1] = out[length - 1];
        diff = subSatW32(out[0], state[3]);
        in[0] = FixedPointMath.scaleDiff32(coefficients[1], diff, state[2]);
        for (var k = 1; k < length; k++) {
            diff = subSatW32(out[k], in[k - 1]);
            in[k] = FixedPointMath.scaleDiff32(coefficients[1], diff, out[k - 1]);
        }
        state[2] = out[length - 1];
        state[3] = in[length - 1];
        diff = subSatW32(in[0], state[5]);
        out[0] = FixedPointMath.scaleDiff32(coefficients[2], diff, state[4]);
        for (var k = 1; k < length; k++) {
            diff = subSatW32(in[k], out[k - 1]);
            out[k] = FixedPointMath.scaleDiff32(coefficients[2], diff, in[k - 1]);
        }
        state[4] = in[length - 1];
        state[5] = out[length - 1];
    }

    /**
     * Subtracts two 32-bit values with saturation ({@code WebRtcSpl_SubSatW32}).
     *
     * @param a the minuend
     * @param b the subtrahend
     * @return {@code a - b} clamped to the 32-bit range
     */
    private static int subSatW32(int a, int b) {
        var diff = (long) a - b;
        return (int) Math.clamp(diff, Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    private void threeBandsAnalysis(float[][] state, float[] in, float[][] out) {
        for (var band = 0; band < 3; ++band) {
            java.util.Arrays.fill(out[band], 0, SPLIT_BAND_SIZE, 0.f);
        }
        var subsampled = inSubsampled;
        var filtered = outSubsampled;
        for (var downsamplingIndex = 0; downsamplingIndex < 3; ++downsamplingIndex) {
            for (var k = 0; k < SPLIT_BAND_SIZE; ++k) {
                subsampled[k] = in[(3 - 1) - downsamplingIndex + 3 * k];
            }
            for (var inShift = 0; inShift < STRIDE; ++inShift) {
                var index = downsamplingIndex + inShift * 3;
                if (index == ZERO_FILTER_INDEX_1 || index == ZERO_FILTER_INDEX_2) {
                    continue;
                }
                var filterIndex = index < ZERO_FILTER_INDEX_1 ? index
                        : (index < ZERO_FILTER_INDEX_2 ? index - 1 : index - 2);
                filterCore(FILTER_COEFFS[filterIndex], subsampled, inShift, filtered, state[filterIndex]);
                var modulation = DCT_MODULATION[filterIndex];
                for (var band = 0; band < 3; ++band) {
                    var outBand = out[band];
                    var m = modulation[band];
                    for (var n = 0; n < SPLIT_BAND_SIZE; ++n) {
                        outBand[n] += m * filtered[n];
                    }
                }
            }
        }
    }

    private void threeBandsSynthesis(float[][] state, float[][] in, float[] out) {
        java.util.Arrays.fill(out, 0, 3 * SPLIT_BAND_SIZE, 0.f);
        var subsampled = inSubsampled;
        var filtered = outSubsampled;
        for (var upsamplingIndex = 0; upsamplingIndex < 3; ++upsamplingIndex) {
            for (var inShift = 0; inShift < STRIDE; ++inShift) {
                var index = upsamplingIndex + inShift * 3;
                if (index == ZERO_FILTER_INDEX_1 || index == ZERO_FILTER_INDEX_2) {
                    continue;
                }
                var filterIndex = index < ZERO_FILTER_INDEX_1 ? index
                        : (index < ZERO_FILTER_INDEX_2 ? index - 1 : index - 2);
                var modulation = DCT_MODULATION[filterIndex];
                java.util.Arrays.fill(subsampled, 0.f);
                for (var band = 0; band < 3; ++band) {
                    var inBand = in[band];
                    var m = modulation[band];
                    for (var n = 0; n < SPLIT_BAND_SIZE; ++n) {
                        subsampled[n] += m * inBand[n];
                    }
                }
                filterCore(FILTER_COEFFS[filterIndex], subsampled, inShift, filtered, state[filterIndex]);
                for (var k = 0; k < SPLIT_BAND_SIZE; ++k) {
                    out[upsamplingIndex + 3 * k] += 3.f * filtered[k];
                }
            }
        }
    }

    /**
     * Filters a subsampled band with a sparse four-tap filter and a polyphase shift.
     *
     * @param filter the four taps
     * @param in the 160 input samples
     * @param inShift the polyphase shift, from 0 to 3
     * @param out receives the 160 filtered samples
     * @param state the {@value #MEMORY_SIZE} remembered input samples
     */
    private static void filterCore(float[] filter, float[] in, int inShift, float[] out, float[] state) {
        java.util.Arrays.fill(out, 0.f);
        for (var k = 0; k < inShift; ++k) {
            for (int i = 0, j = MEMORY_SIZE + k - inShift; i < FILTER_SIZE; ++i, j -= STRIDE) {
                out[k] += state[j] * filter[i];
            }
        }
        for (int k = inShift, shift = 0; k < FILTER_SIZE * STRIDE; ++k, ++shift) {
            var loopLimit = Math.min(FILTER_SIZE, 1 + (shift >> STRIDE_LOG2));
            for (int i = 0, j = shift; i < loopLimit; ++i, j -= STRIDE) {
                out[k] += in[j] * filter[i];
            }
            for (int i = loopLimit, j = MEMORY_SIZE + shift - loopLimit * STRIDE; i < FILTER_SIZE; ++i, j -= STRIDE) {
                out[k] += state[j] * filter[i];
            }
        }
        for (int k = FILTER_SIZE * STRIDE, shift = FILTER_SIZE * STRIDE - inShift; k < SPLIT_BAND_SIZE; ++k, ++shift) {
            for (int i = 0, j = shift; i < FILTER_SIZE; ++i, j -= STRIDE) {
                out[k] += in[j] * filter[i];
            }
        }
        System.arraycopy(in, SPLIT_BAND_SIZE - MEMORY_SIZE, state, 0, MEMORY_SIZE);
    }
}
