package com.github.auties00.smalt.audio.vorbis.internal;

import java.util.Arrays;

/**
 * Detects the pre-echo and post-echo events that make the encoder switch to short blocks.
 *
 * <p>The detector slides a 128-sample MDCT over the input in steps of 64 samples, smooths the
 * spectrum into seven bands and marks a step when a band's amplitude rises above (pre-echo) or
 * falls below (post-echo) its recent history by more than the setup's thresholds.
 *
 * @implNote This implementation ports libvorbis 1.3.7's {@code envelope.c} (BSD-3).
 */
public final class Envelope {
    /**
     * The number of earlier steps a band amplitude is compared with.
     */
    private static final int PRE = 16;

    /**
     * The number of steps of the search window.
     */
    private static final int WIN = 4;

    /**
     * The number of steps marks reach past the search.
     */
    private static final int POST = 2;

    /**
     * The length of each band's amplitude history.
     */
    private static final int AMP = PRE + POST - 1;

    /**
     * The length of the near-DC energy history.
     */
    private static final int NEAR_DC = 15;

    /**
     * The smallest stretch.
     */
    private static final int MIN_STRETCH = 2;

    /**
     * The largest stretch.
     */
    private static final int MAX_STRETCH = 12;

    /**
     * The first spectral pair of each band.
     */
    private static final int[] BAND_BEGIN = {2, 4, 6, 9, 13, 17, 22};

    /**
     * The width of each band in spectral pairs.
     */
    private static final int[] BAND_END = {4, 5, 6, 8, 8, 8, 8};

    /**
     * The window of each band.
     */
    private static final float[][] BAND_WINDOW = new float[7][];

    /**
     * The reciprocal of each band window's sum.
     */
    private static final float[] BAND_TOTAL = new float[7];

    static {
        for (var j = 0; j < 7; j++) {
            var n = BAND_END[j];
            BAND_WINDOW[j] = new float[n];
            var total = 0.0f;
            for (var i = 0; i < n; i++) {
                BAND_WINDOW[j][i] = (float) StrictMath.sin((i + .5) / n * Math.PI);
                total += BAND_WINDOW[j][i];
            }
            BAND_TOTAL[j] = (float) (1. / total);
        }
    }

    /**
     * The global parameters.
     */
    private final PsyGlobal global;

    /**
     * The number of channels.
     */
    private final int channels;

    /**
     * The search step in samples.
     */
    private final int searchStep = 64;

    /**
     * The analysis window length.
     */
    private final int windowLength = 128;

    /**
     * The smallest energy considered, in decibels.
     */
    private final float minEnergy;

    /**
     * The 128-point MDCT.
     */
    private final ForwardMdct mdct = new ForwardMdct(128);

    /**
     * The sin-squared analysis window.
     */
    private final float[] mdctWindow = new float[128];

    /**
     * The amplitude history of each channel and band.
     */
    private final float[][] ampBuffer;

    /**
     * The history position of each channel and band.
     */
    private final int[] ampPointer;

    /**
     * The near-DC energy history of each channel and band filter.
     */
    private final float[][] nearDc;

    /**
     * The running near-DC accumulator of each filter.
     */
    private final float[] nearDcAcc;

    /**
     * The partial near-DC accumulator of each filter.
     */
    private final float[] nearDcPartialAcc;

    /**
     * The near-DC history position of each filter.
     */
    private final int[] nearPointer;

    /**
     * The current stretch.
     */
    private int stretch;

    /**
     * The step marks.
     */
    private int[] mark = new int[128];

    /**
     * The sample position the search reached.
     */
    long current;

    /**
     * The position of the last mark found after the window center.
     */
    long curmark;

    /**
     * The position where the next search for a mark starts.
     */
    long cursor;

    /**
     * Scratch: the windowed and transformed step.
     */
    private final float[] vec = new float[128];

    /**
     * Creates a detector.
     *
     * @param global    the global parameters
     * @param channels  the number of channels
     * @param longBlock the long block size
     */
    public Envelope(PsyGlobal global, int channels, int longBlock) {
        this.global = global;
        this.channels = channels;
        this.minEnergy = global.preEchoMinEnergy;
        this.cursor = longBlock / 2;
        var n = windowLength;
        for (var i = 0; i < n; i++) {
            mdctWindow[i] = (float) StrictMath.sin(i / (n - 1.) * Math.PI);
            mdctWindow[i] *= mdctWindow[i];
        }
        var filters = 7 * channels;
        ampBuffer = new float[filters][AMP];
        ampPointer = new int[filters];
        nearDc = new float[filters][NEAR_DC];
        nearDcAcc = new float[filters];
        nearDcPartialAcc = new float[filters];
        nearPointer = new int[filters];
    }

    /**
     * Analyzes one step of one channel and returns its trigger flags.
     *
     * @param data   the samples
     * @param offset the index of the step's first sample
     * @param filter the index of the channel's first band filter
     * @return bit 0 for pre-echo, bit 1 for post-echo, bit 2 to reset the stretch
     */
    private int amplitude(float[] data, int offset, int filter) {
        var n = windowLength;
        var ret = 0;
        var minV = minEnergy;
        var stretchNow = Math.max(MIN_STRETCH, stretch / 2);
        var penalty = global.stretchPenalty - (stretch / 2 - MIN_STRETCH);
        if (penalty < 0.f) {
            penalty = 0.f;
        }
        if (penalty > global.stretchPenalty) {
            penalty = global.stretchPenalty;
        }
        for (var i = 0; i < n; i++) {
            vec[i] = data[offset + i] * mdctWindow[i];
        }
        mdct.forward(vec, 0, vec, 0);
        float decay;
        {
            var temp = (float) (vec[0] * vec[0] + .7 * vec[1] * vec[1] + .2 * vec[2] * vec[2]);
            var ptr = nearPointer[filter];
            if (ptr == 0) {
                decay = nearDcAcc[filter] = nearDcPartialAcc[filter] + temp;
                nearDcPartialAcc[filter] = temp;
            } else {
                decay = nearDcAcc[filter] += temp;
                nearDcPartialAcc[filter] += temp;
            }
            nearDcAcc[filter] -= nearDc[filter][ptr];
            nearDc[filter][ptr] = temp;
            decay = (float) (decay * (1. / (NEAR_DC + 1)));
            nearPointer[filter]++;
            if (nearPointer[filter] >= NEAR_DC) {
                nearPointer[filter] = 0;
            }
            decay = (float) (Scales.todB(decay) * .5 - 15.f);
        }
        for (var i = 0; i < n / 2; i += 2) {
            var val = vec[i] * vec[i] + vec[i + 1] * vec[i + 1];
            val = Scales.todB(val) * .5f;
            if (val < decay) {
                val = decay;
            }
            if (val < minV) {
                val = minV;
            }
            vec[i >> 1] = val;
            decay -= 8.;
        }
        for (var j = 0; j < 7; j++) {
            var acc = 0.f;
            for (var i = 0; i < BAND_END[j]; i++) {
                acc += vec[i + BAND_BEGIN[j]] * BAND_WINDOW[j][i];
            }
            acc *= BAND_TOTAL[j];
            var f = filter + j;
            var buffer = ampBuffer[f];
            var self = ampPointer[f];
            var p = self;
            p--;
            if (p < 0) {
                p += AMP;
            }
            var postMax = Math.max(acc, buffer[p]);
            var postMin = Math.min(acc, buffer[p]);
            var preMax = -99999.f;
            var preMin = 99999.f;
            for (var i = 0; i < stretchNow; i++) {
                p--;
                if (p < 0) {
                    p += AMP;
                }
                preMax = Math.max(preMax, buffer[p]);
                preMin = Math.min(preMin, buffer[p]);
            }
            var valMin = postMin - preMin;
            var valMax = postMax - preMax;
            buffer[self] = acc;
            ampPointer[f]++;
            if (ampPointer[f] >= AMP) {
                ampPointer[f] = 0;
            }
            if (valMax > global.preEchoThreshold[j] + penalty) {
                ret |= 1;
                ret |= 4;
            }
            if (valMin < global.postEchoThreshold[j] - penalty) {
                ret |= 2;
            }
        }
        return ret;
    }

    /**
     * Searches the buffered input for the next block boundary ({@code _ve_envelope_search}).
     *
     * @param pcm        the buffered input of each channel
     * @param pcmCurrent the number of buffered samples
     * @param centerW    the center of the current window
     * @param blockSizes the short and long block sizes
     * @param w          the current window size, 0 or 1
     * @return 1 when the next block can be long, 0 when it must be short, -1 when more input
     *         is needed
     */
    public int search(float[][] pcm, long pcmCurrent, long centerW, int[] blockSizes, int w) {
        var first = (int) (current / searchStep);
        var last = (int) (pcmCurrent / searchStep - WIN);
        if (first < 0) {
            first = 0;
        }
        if (last + WIN + POST > mark.length) {
            mark = Arrays.copyOf(mark, last + WIN + POST);
        }
        for (var j = first; j < last; j++) {
            var ret = 0;
            stretch++;
            if (stretch > MAX_STRETCH * 2) {
                stretch = MAX_STRETCH * 2;
            }
            for (var i = 0; i < channels; i++) {
                ret |= amplitude(pcm[i], searchStep * j, i * 7);
            }
            mark[j + POST] = 0;
            if ((ret & 1) != 0) {
                mark[j] = 1;
                mark[j + 1] = 1;
            }
            if ((ret & 2) != 0) {
                mark[j] = 1;
                if (j > 0) {
                    mark[j - 1] = 1;
                }
            }
            if ((ret & 4) != 0) {
                stretch = -1;
            }
        }
        current = (long) last * searchStep;
        var testW = centerW + blockSizes[w] / 4 + blockSizes[1] / 2 + blockSizes[0] / 4;
        var j = cursor;
        while (j < current - searchStep) {
            if (j >= testW) {
                return 1;
            }
            cursor = j;
            if (mark[(int) (j / searchStep)] != 0) {
                if (j > centerW) {
                    curmark = j;
                    if (j >= testW) {
                        return 1;
                    }
                    return 0;
                }
            }
            j += searchStep;
        }
        return -1;
    }

    /**
     * Returns whether the current short block holds a mark ({@code _ve_envelope_mark}).
     *
     * @param centerW    the window center
     * @param blockSizes the short and long block sizes
     * @param lW         the previous window size
     * @param w          the current window size
     * @param nW         the next window size
     * @return {@code true} for an impulse block
     */
    public boolean marked(long centerW, int[] blockSizes, int lW, int w, int nW) {
        var beginW = centerW - blockSizes[w] / 4;
        var endW = centerW + blockSizes[w] / 4;
        if (w != 0) {
            beginW -= blockSizes[lW] / 4;
            endW += blockSizes[nW] / 4;
        } else {
            beginW -= blockSizes[0] / 4;
            endW += blockSizes[0] / 4;
        }
        if (curmark >= beginW && curmark < endW) {
            return true;
        }
        var first = beginW / searchStep;
        var last = endW / searchStep;
        for (var i = first; i < last; i++) {
            if (mark[(int) i] != 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Shifts the detector's positions after the encoder drops consumed samples
     * ({@code _ve_envelope_shift}).
     *
     * @param shift the number of samples dropped
     */
    public void shift(long shift) {
        var smallSize = (int) (current / searchStep + POST);
        var smallShift = (int) (shift / searchStep);
        System.arraycopy(mark, smallShift, mark, 0, smallSize - smallShift);
        current -= shift;
        if (curmark >= 0) {
            curmark -= shift;
        }
        cursor -= shift;
    }
}
