/* Ports libopus 1.5.2 (src/opus_multistream_encoder.c: surround_analysis, logSum, channel_pos). Copyright
   Xiph.Org Foundation; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.celt;

import java.util.Arrays;

/**
 * Estimates how much the channels of a surround signal mask each other, band by band, so that the
 * multistream encoder can lower the rate of the bands other channels mask ({@code surround_analysis}).
 *
 * <p>The input is interleaved in the Vorbis channel order of Opus mapping family 1; the result
 * holds, for each channel and each of the 21 CELT bands, the signal-to-mask ratio in log2 units,
 * which the stream encoders take as their energy mask. The LFE channel gets a zero mask.
 *
 * <p>Instances are not thread-safe. Arguments are not checked; values outside the documented
 * ranges give unspecified results.
 */
public final class SurroundAnalysis {
    /**
     * The number of CELT bands analyzed.
     */
    private static final int BANDS = 21;

    /**
     * The MDCT overlap at 48 kHz.
     */
    private static final int OVERLAP = 120;

    /**
     * The interpolation table of {@link #logSum(float, float)}: {@code log2(1 + 2^-x) / 2} at half
     * steps, whose entries past the ninth are zero as in the reference.
     */
    private static final float[] DIFF_TABLE = {
            0.5000000f, 0.2924813f, 0.1609640f, 0.0849625f, 0.0437314f, 0.0221971f, 0.0111839f, 0.0056136f,
            0.0028123f, 0, 0, 0, 0, 0, 0, 0, 0
    };

    /**
     * The mode of the analysis, the 48 kHz CELT mode.
     */
    private final CeltMode mode = CeltMode.MODE_48000_960;

    /**
     * The number of channels.
     */
    private final int channels;

    /**
     * The position of each channel in the mix: 0 none (LFE), 1 left, 2 center, 3 right.
     */
    private final int[] pos = new int[8];

    /**
     * The last {@value #OVERLAP} pre-emphasized samples of each channel ({@code window_mem}).
     */
    private final float[] windowMem;

    /**
     * The pre-emphasis filter state of each channel ({@code preemph_mem}).
     */
    private final float[] preemphMem;

    /**
     * The pre-emphasized samples of one channel plus the overlap ({@code in}).
     */
    private final float[] in;

    /**
     * One channel of the input ({@code x}).
     */
    private final float[] x;

    /**
     * The MDCT coefficients of one window ({@code freq}).
     */
    private final float[] freq;

    /**
     * The largest band amplitudes of one channel over its windows ({@code bandE}).
     */
    private final float[] bandE = new float[BANDS];

    /**
     * The band amplitudes of one window ({@code tmpE}).
     */
    private final float[] tmpE = new float[BANDS];

    /**
     * The log energies of one channel.
     */
    private final float[] logE = new float[BANDS];

    /**
     * The masking of the left, center and right mixes ({@code maskLogE}).
     */
    private final float[][] maskLogE = new float[3][BANDS];

    /**
     * The first scratch buffer of the MDCT.
     */
    private final float[] mdctF;

    /**
     * The second scratch buffer of the MDCT.
     */
    private final float[] mdctF2;

    /**
     * Constructs the analysis of a surround signal.
     *
     * @param channels the number of channels in Vorbis order, from 3 to 8
     */
    public SurroundAnalysis(int channels) {
        this.channels = channels;
        channelPos(channels, pos);
        this.windowMem = new float[channels * OVERLAP];
        this.preemphMem = new float[channels];
        var maxFrame = 48000 / 25 * 3;
        this.in = new float[maxFrame + OVERLAP];
        this.x = new float[maxFrame];
        this.freq = new float[960];
        this.mdctF = new float[mode.mdct.n >> 1];
        this.mdctF2 = new float[mode.mdct.n >> 1];
    }

    /**
     * Sets the mix position of each channel ({@code channel_pos}).
     *
     * @param channels the number of channels
     * @param pos receives the positions
     */
    private static void channelPos(int channels, int[] pos) {
        if (channels == 4) {
            pos[0] = 1;
            pos[1] = 3;
            pos[2] = 1;
            pos[3] = 3;
        } else if (channels == 3 || channels == 5 || channels == 6) {
            pos[0] = 1;
            pos[1] = 2;
            pos[2] = 3;
            pos[3] = 1;
            pos[4] = 3;
            pos[5] = 0;
        } else if (channels == 7) {
            pos[0] = 1;
            pos[1] = 2;
            pos[2] = 3;
            pos[3] = 1;
            pos[4] = 3;
            pos[5] = 2;
            pos[6] = 0;
        } else if (channels == 8) {
            pos[0] = 1;
            pos[1] = 2;
            pos[2] = 3;
            pos[3] = 1;
            pos[4] = 3;
            pos[5] = 1;
            pos[6] = 3;
            pos[7] = 0;
        }
    }

    /**
     * Returns a coarse approximation of {@code log2(2^a + 2^b)} in the half-log2 domain
     * ({@code logSum}).
     *
     * @param a the first term
     * @param b the second term
     * @return the approximate sum
     */
    static float logSum(float a, float b) {
        float max;
        float diff;
        if (a > b) {
            max = a;
            diff = a - b;
        } else {
            max = b;
            diff = b - a;
        }
        if (!(diff < 8.f)) {
            return max;
        }
        var low = (int) Math.floor(2 * diff);
        var frac = 2 * diff - low;
        return max + DIFF_TABLE[low] + frac * (DIFF_TABLE[low + 1] - DIFF_TABLE[low]);
    }

    /**
     * Clears the filter and window memories ({@code OPUS_RESET_STATE}).
     */
    public void reset() {
        Arrays.fill(windowMem, 0);
        Arrays.fill(preemphMem, 0);
    }

    /**
     * Analyzes one frame ({@code surround_analysis}).
     *
     * @param pcm the interleaved input in Vorbis order, nominally in {@code [-1, 1]}
     * @param pcmOff the index of the first sample
     * @param bandLogE receives the signal-to-mask ratio of each channel, 21 values per channel
     * @param len the number of samples per channel: 2.5 to 120 ms at {@code rate}
     * @param rate the sampling rate: 8000, 12000, 16000, 24000 or 48000
     */
    public void analyze(float[] pcm, int pcmOff, float[] bandLogE, int len, int rate) {
        var upsample = Celt.resamplingFactor(rate);
        var frameSize = len * upsample;
        var freqSize = Math.min(960, frameSize);
        int LM;
        for (LM = 0; LM < mode.maxLM; LM++) {
            if (mode.shortMdctSize << LM == frameSize) {
                break;
            }
        }
        for (var c = 0; c < 3; c++) {
            Arrays.fill(maskLogE[c], -28.f);
        }
        var nbFrames = frameSize / freqSize;
        for (var c = 0; c < channels; c++) {
            System.arraycopy(windowMem, c * OVERLAP, in, 0, OVERLAP);
            for (var i = 0; i < len; i++) {
                x[i] = pcm[pcmOff + i * channels + c];
            }
            CeltEncoder.celtPreemphasis(x, 0, in, OVERLAP, frameSize, 1, upsample, mode.preemph, preemphMem, c, 0);
            var sum = Pitch.celtInnerProd(in, 0, in, 0, frameSize + OVERLAP);
            if (!(sum < 1e18f) || Float.isNaN(sum)) {
                Arrays.fill(in, 0, frameSize + OVERLAP, 0);
                preemphMem[c] = 0;
            }
            Arrays.fill(bandE, 0);
            for (var frame = 0; frame < nbFrames; frame++) {
                Mdct.cltMdctForward(mode.mdct, in, 960 * frame, freq, 0, mode.window, OVERLAP, mode.maxLM - LM, 1,
                        mdctF, mdctF2);
                if (upsample != 1) {
                    var bound = freqSize / upsample;
                    int i;
                    for (i = 0; i < bound; i++) {
                        freq[i] *= upsample;
                    }
                    for (; i < freqSize; i++) {
                        freq[i] = 0;
                    }
                }
                Bands.computeBandEnergies(mode, freq, tmpE, BANDS, 1, LM);
                for (var i = 0; i < BANDS; i++) {
                    bandE[i] = MathOps.max(bandE[i], tmpE[i]);
                }
            }
            QuantBands.amp2Log2(mode, BANDS, BANDS, bandE, logE, 1);
            var base = BANDS * c;
            System.arraycopy(logE, 0, bandLogE, base, BANDS);
            for (var i = 1; i < BANDS; i++) {
                bandLogE[base + i] = MathOps.max(bandLogE[base + i], bandLogE[base + i - 1] - 1.f);
            }
            for (var i = BANDS - 2; i >= 0; i--) {
                bandLogE[base + i] = MathOps.max(bandLogE[base + i], bandLogE[base + i + 1] - 2.f);
            }
            if (pos[c] == 1) {
                for (var i = 0; i < BANDS; i++) {
                    maskLogE[0][i] = logSum(maskLogE[0][i], bandLogE[base + i]);
                }
            } else if (pos[c] == 3) {
                for (var i = 0; i < BANDS; i++) {
                    maskLogE[2][i] = logSum(maskLogE[2][i], bandLogE[base + i]);
                }
            } else if (pos[c] == 2) {
                for (var i = 0; i < BANDS; i++) {
                    maskLogE[0][i] = logSum(maskLogE[0][i], bandLogE[base + i] - .5f);
                    maskLogE[2][i] = logSum(maskLogE[2][i], bandLogE[base + i] - .5f);
                }
            }
            System.arraycopy(in, frameSize, windowMem, c * OVERLAP, OVERLAP);
        }
        for (var i = 0; i < BANDS; i++) {
            maskLogE[1][i] = MathOps.min(maskLogE[0][i], maskLogE[2][i]);
        }
        var channelOffset = .5f * MathOps.celtLog2(2.f / (channels - 1));
        for (var c = 0; c < 3; c++) {
            for (var i = 0; i < BANDS; i++) {
                maskLogE[c][i] += channelOffset;
            }
        }
        for (var c = 0; c < channels; c++) {
            var base = BANDS * c;
            if (pos[c] != 0) {
                var mask = maskLogE[pos[c] - 1];
                for (var i = 0; i < BANDS; i++) {
                    bandLogE[base + i] = bandLogE[base + i] - mask[i];
                }
            } else {
                Arrays.fill(bandLogE, base, base + BANDS, 0);
            }
        }
    }
}
