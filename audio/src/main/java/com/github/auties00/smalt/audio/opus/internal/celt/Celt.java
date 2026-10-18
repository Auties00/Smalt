/* Ports libopus 1.5.2 (celt/celt.h, celt/celt.c, include/opus_defines.h). Copyright Xiph.Org Foundation, CSIRO and
   Jean-Marc Valin; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.celt;

/**
 * Provides the constants and helpers shared by the CELT encoder and decoder: the Opus result
 * codes, the pitch pre/post-filter, the TF resolution table and the band caps.
 *
 * @implNote This implementation omits custom modes.
 */
public final class Celt {
    /**
     * No error ({@code OPUS_OK}).
     */
    public static final int OPUS_OK = 0;

    /**
     * One or more invalid or out of range arguments ({@code OPUS_BAD_ARG}).
     */
    public static final int OPUS_BAD_ARG = -1;

    /**
     * Not enough bytes allocated in the buffer ({@code OPUS_BUFFER_TOO_SMALL}).
     */
    public static final int OPUS_BUFFER_TOO_SMALL = -2;

    /**
     * An internal error was detected ({@code OPUS_INTERNAL_ERROR}).
     */
    public static final int OPUS_INTERNAL_ERROR = -3;

    /**
     * The compressed data passed is corrupted ({@code OPUS_INVALID_PACKET}).
     */
    public static final int OPUS_INVALID_PACKET = -4;

    /**
     * Invalid or unsupported request number ({@code OPUS_UNIMPLEMENTED}).
     */
    public static final int OPUS_UNIMPLEMENTED = -5;

    /**
     * The bitrate value meaning "as many bits as the packet allows" ({@code OPUS_BITRATE_MAX}).
     */
    public static final int OPUS_BITRATE_MAX = -1;

    /**
     * The largest pitch period of the pre/post-filter ({@code COMBFILTER_MAXPERIOD}).
     */
    public static final int COMBFILTER_MAXPERIOD = 1024;

    /**
     * The smallest pitch period of the pre/post-filter ({@code COMBFILTER_MINPERIOD}).
     */
    public static final int COMBFILTER_MINPERIOD = 15;

    /**
     * The number of bands carrying a leakage boost in {@link AnalysisInfo} ({@code LEAK_BANDS}).
     */
    public static final int LEAK_BANDS = 19;

    /**
     * The largest pitch period of the packet-loss concealment, and the history kept for it
     * ({@code MAX_PERIOD}).
     */
    public static final int MAX_PERIOD = 1024;

    /**
     * No spreading ({@code SPREAD_NONE}).
     */
    public static final int SPREAD_NONE = 0;

    /**
     * Light spreading ({@code SPREAD_LIGHT}).
     */
    public static final int SPREAD_LIGHT = 1;

    /**
     * Normal spreading ({@code SPREAD_NORMAL}).
     */
    public static final int SPREAD_NORMAL = 2;

    /**
     * Aggressive spreading ({@code SPREAD_AGGRESSIVE}).
     */
    public static final int SPREAD_AGGRESSIVE = 3;

    /**
     * The inverse CDF of the allocation trim ({@code trim_icdf}).
     */
    static final short[] TRIM_ICDF = {126, 124, 119, 109, 87, 41, 19, 9, 4, 2, 0};

    /**
     * The inverse CDF of the spreading decision ({@code spread_icdf}).
     */
    static final short[] SPREAD_ICDF = {25, 23, 2, 0};

    /**
     * The inverse CDF of the pre-filter tapset ({@code tapset_icdf}).
     */
    static final short[] TAPSET_ICDF = {2, 1, 0};

    /**
     * The TF resolution change of each LM, indexed by
     * {@code 4 * isTransient + 2 * tf_select + per_band_flag} ({@code tf_select_table}).
     */
    static final byte[][] TF_SELECT_TABLE = {
            {0, -1, 0, -1, 0, -1, 0, -1},
            {0, -1, 0, -2, 1, 0, 1, -1},
            {0, -2, 0, -3, 2, 0, 1, -1},
            {0, -2, 0, -3, 3, 0, 1, -1}
    };

    /**
     * The three-tap gains of each pre/post-filter tapset ({@code gains} in {@code comb_filter}).
     */
    private static final float[][] COMB_GAINS = {
            {0.3066406250f, 0.2170410156f, 0.1296386719f},
            {0.4638671875f, 0.2680664062f, 0.f},
            {0.7998046875f, 0.1000976562f, 0.f}
    };

    private Celt() {
        throw new AssertionError();
    }

    /**
     * Returns the decimation factor between 48 kHz and a supported rate
     * ({@code resampling_factor}).
     *
     * @param rate the sampling rate
     * @return 1, 2, 3, 4 or 6, or 0 for an unsupported rate
     */
    public static int resamplingFactor(int rate) {
        return switch (rate) {
            case 48000 -> 1;
            case 24000 -> 2;
            case 16000 -> 3;
            case 12000 -> 4;
            case 8000 -> 6;
            default -> 0;
        };
    }

    /**
     * Applies the pitch comb filter, cross-fading between two filter settings over the overlap
     * ({@code comb_filter}).
     *
     * <p>{@code x} must provide {@code max(T0, T1) + 2} samples of history before {@code xOff}.
     * The filter runs in place when {@code y} and {@code x} are the same region, which makes it
     * recursive (the decoder's post-filter).
     *
     * @param y        the output
     * @param yOff     the index of the first output sample
     * @param x        the input
     * @param xOff     the index of the first input sample
     * @param T0       the period of the old filter
     * @param T1       the period of the new filter
     * @param N        the number of samples
     * @param g0       the gain of the old filter
     * @param g1       the gain of the new filter
     * @param tapset0  the tapset of the old filter
     * @param tapset1  the tapset of the new filter
     * @param window   the overlap window, or {@code null} when {@code overlap} is zero
     * @param overlap  the cross-fade length
     */
    public static void combFilter(float[] y, int yOff, float[] x, int xOff, int T0, int T1, int N, float g0,
                                  float g1, int tapset0, int tapset1, float[] window, int overlap) {
        if (g0 == 0 && g1 == 0) {
            if (x != y || xOff != yOff) {
                System.arraycopy(x, xOff, y, yOff, N);
            }
            return;
        }
        T0 = Math.max(T0, COMBFILTER_MINPERIOD);
        T1 = Math.max(T1, COMBFILTER_MINPERIOD);
        var g00 = g0 * COMB_GAINS[tapset0][0];
        var g01 = g0 * COMB_GAINS[tapset0][1];
        var g02 = g0 * COMB_GAINS[tapset0][2];
        var g10 = g1 * COMB_GAINS[tapset1][0];
        var g11 = g1 * COMB_GAINS[tapset1][1];
        var g12 = g1 * COMB_GAINS[tapset1][2];
        var x1 = x[xOff - T1 + 1];
        var x2 = x[xOff - T1];
        var x3 = x[xOff - T1 - 1];
        var x4 = x[xOff - T1 - 2];
        if (g0 == g1 && T0 == T1 && tapset0 == tapset1) {
            overlap = 0;
        }
        int i;
        for (i = 0; i < overlap; i++) {
            var x0 = x[xOff + i - T1 + 2];
            var f = window[i] * window[i];
            y[yOff + i] = x[xOff + i]
                    + ((1.0f - f) * g00) * x[xOff + i - T0]
                    + ((1.0f - f) * g01) * (x[xOff + i - T0 + 1] + x[xOff + i - T0 - 1])
                    + ((1.0f - f) * g02) * (x[xOff + i - T0 + 2] + x[xOff + i - T0 - 2])
                    + (f * g10) * x2
                    + (f * g11) * (x1 + x3)
                    + (f * g12) * (x0 + x4);
            x4 = x3;
            x3 = x2;
            x2 = x1;
            x1 = x0;
        }
        if (g1 == 0) {
            if (x != y || xOff != yOff) {
                System.arraycopy(x, xOff + overlap, y, yOff + overlap, N - overlap);
            }
            return;
        }
        CeltKernels.INSTANCE.combFilterConst(y, yOff + i, x, xOff + i, T1, N - i, g10, g11, g12);
    }

    /**
     * Computes the maximum useful allocation of each band ({@code init_caps}).
     *
     * @param m   the mode
     * @param cap receives the cap of each band in 1/8 bits
     * @param LM  the frame size shift
     * @param C   the number of channels
     */
    public static void initCaps(CeltMode m, int[] cap, int LM, int C) {
        for (var i = 0; i < m.nbEBands; i++) {
            var N = (m.eBands[i + 1] - m.eBands[i]) << LM;
            cap[i] = (m.cache.caps[m.nbEBands * (2 * LM + C - 1) + i] + 64) * C * N >> 2;
        }
    }
}
