/* Ports libopus 1.5.2 (src/analysis.c, src/opus_encoder.c). Copyright Xiph.Org Foundation and Skype Limited; BSD
   3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal;

import com.github.auties00.smalt.audio.opus.internal.celt.AnalysisInfo;
import com.github.auties00.smalt.audio.opus.internal.celt.CeltMode;
import com.github.auties00.smalt.audio.opus.internal.celt.KissFft;
import com.github.auties00.smalt.audio.opus.internal.celt.MathOps;

/**
 * Analyzes the encoder input ahead of coding: tonality, noisiness, spectral activity, the
 * effective audio bandwidth, and the speech/music probabilities of a small recurrent network.
 *
 * <p>The analysis runs on 20 ms windows of a 24 kHz downmix (the input is decimated or
 * sample-repeated to 24 kHz), keeps a ring of {@link #DETECT_SIZE} per-window results, and
 * {@link #tonalityGetInfo(AnalysisInfo, int)} reads them back with look-ahead compensation. The
 * Opus layer uses the results to pick the coding mode and bandwidth, and CELT uses them to tune
 * its allocation.
 *
 * @implNote This implementation holds the per-window scratch arrays in the state.
 */
final class TonalityAnalysis {
    /**
     * The number of past windows kept for the stationarity and variability features
     * ({@code NB_FRAMES}).
     */
    private static final int NB_FRAMES = 8;

    /**
     * The number of tonality bands ({@code NB_TBANDS}).
     */
    private static final int NB_TBANDS = 18;

    /**
     * The size of the 24 kHz input buffer, 30 ms ({@code ANALYSIS_BUF_SIZE}).
     */
    private static final int ANALYSIS_BUF_SIZE = 720;

    /**
     * The frame count at which counting stops ({@code ANALYSIS_COUNT_MAX}).
     */
    private static final int ANALYSIS_COUNT_MAX = 10000;

    /**
     * The number of per-window results kept ({@code DETECT_SIZE}).
     */
    static final int DETECT_SIZE = 100;

    /**
     * The penalty for switching between speech and music during activity
     * ({@code TRANSITION_PENALTY}).
     */
    private static final int TRANSITION_PENALTY = 10;

    /**
     * The number of high tonality bands left out of the frame tonality
     * ({@code NB_TONAL_SKIP_BANDS}).
     */
    private static final int NB_TONAL_SKIP_BANDS = 9;

    /**
     * The number of bands of the leakage boost ({@code LEAK_BANDS}).
     */
    private static final int LEAK_BANDS = 19;

    /**
     * The level below which leakage is ignored, in log2 units ({@code LEAKAGE_OFFSET}).
     */
    private static final float LEAKAGE_OFFSET = 2.5f;

    /**
     * The spreading slope of the leakage model ({@code LEAKAGE_SLOPE}).
     */
    private static final float LEAKAGE_SLOPE = 2.f;

    /**
     * The 8x16 DCT used to compute the band cepstrum ({@code dct_table}).
     */
    private static final float[] dct_table = {
            0.250000f, 0.250000f, 0.250000f, 0.250000f, 0.250000f, 0.250000f, 0.250000f, 0.250000f,
            0.250000f, 0.250000f, 0.250000f, 0.250000f, 0.250000f, 0.250000f, 0.250000f, 0.250000f,
            0.351851f, 0.338330f, 0.311806f, 0.273300f, 0.224292f, 0.166664f, 0.102631f, 0.034654f,
            -0.034654f, -0.102631f, -0.166664f, -0.224292f, -0.273300f, -0.311806f, -0.338330f, -0.351851f,
            0.346760f, 0.293969f, 0.196424f, 0.068975f, -0.068975f, -0.196424f, -0.293969f, -0.346760f,
            -0.346760f, -0.293969f, -0.196424f, -0.068975f, 0.068975f, 0.196424f, 0.293969f, 0.346760f,
            0.338330f, 0.224292f, 0.034654f, -0.166664f, -0.311806f, -0.351851f, -0.273300f, -0.102631f,
            0.102631f, 0.273300f, 0.351851f, 0.311806f, 0.166664f, -0.034654f, -0.224292f, -0.338330f,
            0.326641f, 0.135299f, -0.135299f, -0.326641f, -0.326641f, -0.135299f, 0.135299f, 0.326641f,
            0.326641f, 0.135299f, -0.135299f, -0.326641f, -0.326641f, -0.135299f, 0.135299f, 0.326641f,
            0.311806f, 0.034654f, -0.273300f, -0.338330f, -0.102631f, 0.224292f, 0.351851f, 0.166664f,
            -0.166664f, -0.351851f, -0.224292f, 0.102631f, 0.338330f, 0.273300f, -0.034654f, -0.311806f,
            0.293969f, -0.068975f, -0.346760f, -0.196424f, 0.196424f, 0.346760f, 0.068975f, -0.293969f,
            -0.293969f, 0.068975f, 0.346760f, 0.196424f, -0.196424f, -0.346760f, -0.068975f, 0.293969f,
            0.273300f, -0.166664f, -0.338330f, 0.034654f, 0.351851f, 0.102631f, -0.311806f, -0.224292f,
            0.224292f, 0.311806f, -0.102631f, -0.351851f, -0.034654f, 0.338330f, 0.166664f, -0.273300f
    };

    /**
     * The analysis window, the first half of a 480-point window ({@code analysis_window}).
     */
    private static final float[] analysis_window = {
            0.000043f, 0.000171f, 0.000385f, 0.000685f, 0.001071f, 0.001541f, 0.002098f, 0.002739f,
            0.003466f, 0.004278f, 0.005174f, 0.006156f, 0.007222f, 0.008373f, 0.009607f, 0.010926f,
            0.012329f, 0.013815f, 0.015385f, 0.017037f, 0.018772f, 0.020590f, 0.022490f, 0.024472f,
            0.026535f, 0.028679f, 0.030904f, 0.033210f, 0.035595f, 0.038060f, 0.040604f, 0.043227f,
            0.045928f, 0.048707f, 0.051564f, 0.054497f, 0.057506f, 0.060591f, 0.063752f, 0.066987f,
            0.070297f, 0.073680f, 0.077136f, 0.080665f, 0.084265f, 0.087937f, 0.091679f, 0.095492f,
            0.099373f, 0.103323f, 0.107342f, 0.111427f, 0.115579f, 0.119797f, 0.124080f, 0.128428f,
            0.132839f, 0.137313f, 0.141849f, 0.146447f, 0.151105f, 0.155823f, 0.160600f, 0.165435f,
            0.170327f, 0.175276f, 0.180280f, 0.185340f, 0.190453f, 0.195619f, 0.200838f, 0.206107f,
            0.211427f, 0.216797f, 0.222215f, 0.227680f, 0.233193f, 0.238751f, 0.244353f, 0.250000f,
            0.255689f, 0.261421f, 0.267193f, 0.273005f, 0.278856f, 0.284744f, 0.290670f, 0.296632f,
            0.302628f, 0.308658f, 0.314721f, 0.320816f, 0.326941f, 0.333097f, 0.339280f, 0.345492f,
            0.351729f, 0.357992f, 0.364280f, 0.370590f, 0.376923f, 0.383277f, 0.389651f, 0.396044f,
            0.402455f, 0.408882f, 0.415325f, 0.421783f, 0.428254f, 0.434737f, 0.441231f, 0.447736f,
            0.454249f, 0.460770f, 0.467298f, 0.473832f, 0.480370f, 0.486912f, 0.493455f, 0.500000f,
            0.506545f, 0.513088f, 0.519630f, 0.526168f, 0.532702f, 0.539230f, 0.545751f, 0.552264f,
            0.558769f, 0.565263f, 0.571746f, 0.578217f, 0.584675f, 0.591118f, 0.597545f, 0.603956f,
            0.610349f, 0.616723f, 0.623077f, 0.629410f, 0.635720f, 0.642008f, 0.648271f, 0.654508f,
            0.660720f, 0.666903f, 0.673059f, 0.679184f, 0.685279f, 0.691342f, 0.697372f, 0.703368f,
            0.709330f, 0.715256f, 0.721144f, 0.726995f, 0.732807f, 0.738579f, 0.744311f, 0.750000f,
            0.755647f, 0.761249f, 0.766807f, 0.772320f, 0.777785f, 0.783203f, 0.788573f, 0.793893f,
            0.799162f, 0.804381f, 0.809547f, 0.814660f, 0.819720f, 0.824724f, 0.829673f, 0.834565f,
            0.839400f, 0.844177f, 0.848895f, 0.853553f, 0.858151f, 0.862687f, 0.867161f, 0.871572f,
            0.875920f, 0.880203f, 0.884421f, 0.888573f, 0.892658f, 0.896677f, 0.900627f, 0.904508f,
            0.908321f, 0.912063f, 0.915735f, 0.919335f, 0.922864f, 0.926320f, 0.929703f, 0.933013f,
            0.936248f, 0.939409f, 0.942494f, 0.945503f, 0.948436f, 0.951293f, 0.954072f, 0.956773f,
            0.959396f, 0.961940f, 0.964405f, 0.966790f, 0.969096f, 0.971321f, 0.973465f, 0.975528f,
            0.977510f, 0.979410f, 0.981228f, 0.982963f, 0.984615f, 0.986185f, 0.987671f, 0.989074f,
            0.990393f, 0.991627f, 0.992778f, 0.993844f, 0.994826f, 0.995722f, 0.996534f, 0.997261f,
            0.997902f, 0.998459f, 0.998929f, 0.999315f, 0.999615f, 0.999829f, 0.999957f, 1.000000f
    };

    /**
     * The first FFT bin of each tonality band at 24 kHz ({@code tbands}).
     */
    private static final int[] tbands = {4, 8, 12, 16, 20, 24, 28, 32, 40, 48, 56, 64, 80, 96, 112, 136, 160, 192, 240};

    /**
     * The means subtracted from the feature standard deviations ({@code std_feature_bias}).
     */
    private static final float[] std_feature_bias = {
            5.684947f, 3.475288f, 1.770634f, 1.599784f, 3.773215f, 2.163313f, 1.260756f, 1.116868f, 1.918795f
    };

    /**
     * The application the encoder was created for.
     */
    int application;

    /**
     * The input sampling rate.
     */
    final int Fs;

    /**
     * The phase of each bin in the previous window, in turns.
     */
    private final float[] angle = new float[240];

    /**
     * The phase increment of each bin.
     */
    private final float[] d_angle = new float[240];

    /**
     * The fourth power of the phase acceleration of each bin.
     */
    private final float[] d2_angle = new float[240];

    /**
     * The buffered 24 kHz input.
     */
    private final float[] inmem = new float[ANALYSIS_BUF_SIZE];

    /**
     * The number of usable samples in {@link #inmem}.
     */
    private int mem_fill;

    /**
     * The tonality of each band in the previous window.
     */
    private final float[] prev_band_tonality = new float[NB_TBANDS];

    /**
     * The frame tonality of the previous window.
     */
    private float prev_tonality;

    /**
     * The bandwidth detected in the previous window.
     */
    private int prev_bandwidth;

    /**
     * The band energies of the last {@link #NB_FRAMES} windows.
     */
    private final float[][] E = new float[NB_FRAMES][NB_TBANDS];

    /**
     * The log band energies of the last {@link #NB_FRAMES} windows.
     */
    private final float[][] logE = new float[NB_FRAMES][NB_TBANDS];

    /**
     * The tracked low log energy of each band.
     */
    private final float[] lowE = new float[NB_TBANDS];

    /**
     * The tracked high log energy of each band.
     */
    private final float[] highE = new float[NB_TBANDS];

    /**
     * The decaying mean energy of each band, plus the band above 12 kHz.
     */
    private final float[] meanE = new float[NB_TBANDS + 1];

    /**
     * The cepstra of the last four windows.
     */
    private final float[] mem = new float[32];

    /**
     * The running mean of the cepstrum.
     */
    private final float[] cmean = new float[8];

    /**
     * The running variance of the features.
     */
    private final float[] std = new float[9];

    /**
     * The tracked loudness peak in dB.
     */
    private float Etracker;

    /**
     * The smoothed proportion of quiet windows.
     */
    private float lowECount;

    /**
     * The ring index of the next row of {@link #E} and {@link #logE}.
     */
    private int E_count;

    /**
     * The number of windows analyzed, saturating at {@link #ANALYSIS_COUNT_MAX}.
     */
    private int count;

    /**
     * The offset of the not yet analyzed input within the current frame.
     */
    int analysis_offset;

    /**
     * The ring index of the next result to write.
     */
    int write_pos;

    /**
     * The ring index of the next result to read.
     */
    int read_pos;

    /**
     * The number of 2.5 ms subframes already read from the result at {@link #read_pos}.
     */
    int read_subframe;

    /**
     * The accumulated energy above 12 kHz of the buffered input.
     */
    private float hp_ener_accum;

    /**
     * Whether the analysis has started.
     */
    boolean initialized;

    /**
     * The state of the recurrent layer.
     */
    private final float[] rnn_state = new float[Mlp.MAX_NEURONS];

    /**
     * The state of the high-pass half-band decimator.
     */
    private final float[] downmix_state = new float[3];

    /**
     * The ring of per-window results.
     */
    private final AnalysisInfo[] info = new AnalysisInfo[DETECT_SIZE];

    /**
     * The downmixed input of one call ({@code tmp} of {@code downmix_and_resample}).
     */
    private final float[] downmixBuffer;

    /**
     * The sample-repeated 16 kHz input ({@code tmp3x}).
     */
    private final float[] repeatBuffer;

    /**
     * The FFT input, interleaved complex.
     */
    private final float[] fftIn = new float[960];

    /**
     * The FFT output, interleaved complex.
     */
    private final float[] fftOut = new float[960];

    /**
     * The scratch space of the FFT.
     */
    private final float[] fftScratch = new float[960];

    /**
     * The real part of each FFT bin {@code i} of the current window.
     */
    private final float[] binRe = new float[240];

    /**
     * The imaginary part of each FFT bin {@code i} of the current window.
     */
    private final float[] binIm = new float[240];

    /**
     * The real part of each FFT bin {@code 480 - i} of the current window.
     */
    private final float[] binReRev = new float[240];

    /**
     * The imaginary part of each FFT bin {@code 480 - i} of the current window.
     */
    private final float[] binImRev = new float[240];

    /**
     * The per-bin tonality.
     */
    private final float[] tonality = new float[240];

    /**
     * The per-bin noisiness.
     */
    private final float[] noisiness = new float[240];

    /**
     * The per-bin tonality without the two-window delay.
     */
    private final float[] tonality2 = new float[240];

    /**
     * The network features of the window.
     */
    private final float[] features = new float[25];

    /**
     * The output of the first network layer.
     */
    private final float[] layer_out = new float[Mlp.MAX_NEURONS];

    /**
     * The network output probabilities.
     */
    private final float[] frame_probs = new float[2];

    /**
     * The scratch space of the recurrent layer.
     */
    private final float[] gruScratch = new float[4 * Mlp.MAX_NEURONS];

    /**
     * The tonality of each band in the current window.
     */
    private final float[] band_tonality = new float[NB_TBANDS];

    /**
     * The natural-log energy of each band in the current window.
     */
    private final float[] bandLogE = new float[NB_TBANDS];

    /**
     * The band cepstrum of the current window.
     */
    private final float[] BFCC = new float[8];

    /**
     * Whether each band is masked by the bands below it.
     */
    private final boolean[] is_masked = new boolean[NB_TBANDS + 1];

    /**
     * The cepstrum of the mid-point between the tracked low and high energies.
     */
    private final float[] midE = new float[8];

    /**
     * The base-2 log energy of each band, the DC band first.
     */
    private final float[] band_log2 = new float[NB_TBANDS + 1];

    /**
     * The leakage each band causes towards weaker bands.
     */
    private final float[] leakage_from = new float[NB_TBANDS + 1];

    /**
     * The leakage each band receives from louder bands.
     */
    private final float[] leakage_to = new float[NB_TBANDS + 1];

    /**
     * Creates the analysis state for an input rate ({@code tonality_analysis_init}).
     *
     * @param Fs the input rate: 8000, 12000, 16000, 24000 or 48000
     */
    TonalityAnalysis(int Fs) {
        this.Fs = Fs;
        for (var i = 0; i < DETECT_SIZE; i++) {
            info[i] = new AnalysisInfo();
        }
        var maxSubframe = Fs / 50 * 2;
        downmixBuffer = new float[Math.max(maxSubframe, 1)];
        repeatBuffer = new float[Math.max(3 * maxSubframe, 1)];
        reset();
    }

    /**
     * Clears every field except the configuration ({@code tonality_analysis_reset}).
     */
    void reset() {
        java.util.Arrays.fill(angle, 0);
        java.util.Arrays.fill(d_angle, 0);
        java.util.Arrays.fill(d2_angle, 0);
        java.util.Arrays.fill(inmem, 0);
        mem_fill = 0;
        java.util.Arrays.fill(prev_band_tonality, 0);
        prev_tonality = 0;
        prev_bandwidth = 0;
        for (var row : E) {
            java.util.Arrays.fill(row, 0);
        }
        for (var row : logE) {
            java.util.Arrays.fill(row, 0);
        }
        java.util.Arrays.fill(lowE, 0);
        java.util.Arrays.fill(highE, 0);
        java.util.Arrays.fill(meanE, 0);
        java.util.Arrays.fill(mem, 0);
        java.util.Arrays.fill(cmean, 0);
        java.util.Arrays.fill(std, 0);
        Etracker = 0;
        lowECount = 0;
        E_count = 0;
        count = 0;
        analysis_offset = 0;
        write_pos = 0;
        read_pos = 0;
        read_subframe = 0;
        hp_ener_accum = 0;
        initialized = false;
        java.util.Arrays.fill(rnn_state, 0);
        java.util.Arrays.fill(downmix_state, 0);
        for (var entry : info) {
            clear(entry);
        }
    }

    /**
     * Zeroes an analysis result.
     *
     * @param target the result to clear
     */
    private static void clear(AnalysisInfo target) {
        target.valid = 0;
        target.tonality = 0;
        target.tonality_slope = 0;
        target.noisiness = 0;
        target.activity = 0;
        target.music_prob = 0;
        target.music_prob_min = 0;
        target.music_prob_max = 0;
        target.bandwidth = 0;
        target.activity_probability = 0;
        target.max_pitch_ratio = 0;
        java.util.Arrays.fill(target.leak_boost, 0);
    }

    /**
     * Copies an analysis result.
     *
     * @param target the destination
     * @param source the result to copy
     */
    static void copy(AnalysisInfo target, AnalysisInfo source) {
        target.valid = source.valid;
        target.tonality = source.tonality;
        target.tonality_slope = source.tonality_slope;
        target.noisiness = source.noisiness;
        target.activity = source.activity;
        target.music_prob = source.music_prob;
        target.music_prob_min = source.music_prob_min;
        target.music_prob_max = source.music_prob_max;
        target.bandwidth = source.bandwidth;
        target.activity_probability = source.activity_probability;
        target.max_pitch_ratio = source.max_pitch_ratio;
        System.arraycopy(source.leak_boost, 0, target.leak_boost, 0, LEAK_BANDS);
    }

    /**
     * Decimates a signal by two with a pair of all-pass sections and measures its high-band
     * energy ({@code silk_resampler_down2_hp}).
     *
     * @param S     the three-sample filter state
     * @param out   receives {@code inLen / 2} samples
     * @param outOff the index of the first output sample
     * @param in    the input
     * @param inLen the number of input samples
     * @return the energy of the high-pass output
     */
    private static float resamplerDown2Hp(float[] S, float[] out, int outOff, float[] in, int inLen) {
        var len2 = inLen / 2;
        var hp_ener = 0f;
        for (var k = 0; k < len2; k++) {
            var in32 = in[2 * k];
            var Y = in32 - S[0];
            var X = 0.6074371f * Y;
            var out32 = S[0] + X;
            S[0] = in32 + X;
            var out32_hp = out32;
            in32 = in[2 * k + 1];
            Y = in32 - S[1];
            X = 0.15063f * Y;
            out32 = out32 + S[1];
            out32 = out32 + X;
            S[1] = in32 + X;
            Y = -in32 - S[2];
            X = 0.15063f * Y;
            out32_hp = out32_hp + S[2];
            out32_hp = out32_hp + X;
            S[2] = -in32 + X;
            hp_ener += out32_hp * out32_hp;
            out[outOff + k] = .5f * out32;
        }
        return hp_ener;
    }

    /**
     * Downmixes interleaved float input to one channel scaled to the 16-bit range
     * ({@code downmix_float}).
     *
     * @param x        the interleaved input
     * @param xOff     the index in {@code x} of sample frame zero
     * @param y        receives {@code subframe} samples
     * @param subframe the number of samples
     * @param offset   the index of the first sample frame relative to {@code xOff}
     * @param c1       the first channel to mix
     * @param c2       the second channel to mix, {@code -1} for none, {@code -2} for all others
     * @param C        the number of interleaved channels
     */
    static void downmixFloat(float[] x, int xOff, float[] y, int subframe, int offset, int c1, int c2, int C) {
        for (var j = 0; j < subframe; j++) {
            y[j] = x[xOff + (j + offset) * C + c1] * MathOps.CELT_SIG_SCALE;
        }
        if (c2 > -1) {
            for (var j = 0; j < subframe; j++) {
                y[j] += x[xOff + (j + offset) * C + c2] * MathOps.CELT_SIG_SCALE;
            }
        } else if (c2 == -2) {
            for (var c = 1; c < C; c++) {
                for (var j = 0; j < subframe; j++) {
                    y[j] += x[xOff + (j + offset) * C + c] * MathOps.CELT_SIG_SCALE;
                }
            }
        }
    }

    /**
     * Downmixes and resamples input to 24 kHz into {@code y} ({@code downmix_and_resample}).
     *
     * @param x        the interleaved float input
     * @param xOff     the index in {@code x} of sample frame zero
     * @param y        receives the 24 kHz samples
     * @param yOff     the index of the first output sample
     * @param S        the decimator state
     * @param subframe the number of 24 kHz samples wanted
     * @param offset   the offset of the first input frame, in 24 kHz samples
     * @param c1       the first channel to mix
     * @param c2       the second channel to mix, {@code -1} for none, {@code -2} for all others
     * @param C        the number of interleaved channels
     * @param Fs       the input rate
     * @return the energy above 12 kHz, for 48 kHz input only
     */
    private float downmixAndResample(float[] x, int xOff, float[] y, int yOff, float[] S, int subframe, int offset,
                                     int c1, int c2, int C, int Fs) {
        var ret = 0f;
        if (subframe == 0) {
            return 0;
        }
        if (Fs == 48000) {
            subframe *= 2;
            offset *= 2;
        } else if (Fs == 16000) {
            subframe = subframe * 2 / 3;
            offset = offset * 2 / 3;
        }
        var tmp = downmixBuffer;
        downmixFloat(x, xOff, tmp, subframe, offset, c1, c2, C);
        var scale = 1.f / 32768;
        if (c2 == -2) {
            scale /= C;
        } else if (c2 > -1) {
            scale /= 2;
        }
        for (var j = 0; j < subframe; j++) {
            tmp[j] *= scale;
        }
        if (Fs == 48000) {
            ret = resamplerDown2Hp(S, y, yOff, tmp, subframe);
        } else if (Fs == 24000) {
            System.arraycopy(tmp, 0, y, yOff, subframe);
        } else if (Fs == 16000) {
            var tmp3x = repeatBuffer;
            for (var j = 0; j < subframe; j++) {
                tmp3x[3 * j] = tmp[j];
                tmp3x[3 * j + 1] = tmp[j];
                tmp3x[3 * j + 2] = tmp[j];
            }
            resamplerDown2Hp(S, y, yOff, tmp3x, 3 * subframe);
        }
        return ret;
    }

    /**
     * Returns the analysis result for the frame about to be encoded, smoothing the music
     * probability over the available look-ahead ({@code tonality_get_info}).
     *
     * @param info_out receives the result
     * @param len      the frame size in samples at the input rate
     */
    void tonalityGetInfo(AnalysisInfo info_out, int len) {
        var pos = read_pos;
        var curr_lookahead = write_pos - read_pos;
        if (curr_lookahead < 0) {
            curr_lookahead += DETECT_SIZE;
        }
        read_subframe += len / (Fs / 400);
        while (read_subframe >= 8) {
            read_subframe -= 8;
            read_pos++;
        }
        if (read_pos >= DETECT_SIZE) {
            read_pos -= DETECT_SIZE;
        }
        if (len > Fs / 50 && pos != write_pos) {
            pos++;
            if (pos == DETECT_SIZE) {
                pos = 0;
            }
        }
        if (pos == write_pos) {
            pos--;
        }
        if (pos < 0) {
            pos = DETECT_SIZE - 1;
        }
        var pos0 = pos;
        copy(info_out, info[pos]);
        if (info_out.valid == 0) {
            return;
        }
        var tonality_max = info_out.tonality;
        var tonality_avg = info_out.tonality;
        var tonality_count = 1;
        var bandwidth_span = 6;
        for (var i = 0; i < 3; i++) {
            pos++;
            if (pos == DETECT_SIZE) {
                pos = 0;
            }
            if (pos == write_pos) {
                break;
            }
            tonality_max = max(tonality_max, info[pos].tonality);
            tonality_avg += info[pos].tonality;
            tonality_count++;
            info_out.bandwidth = Math.max(info_out.bandwidth, info[pos].bandwidth);
            bandwidth_span--;
        }
        pos = pos0;
        for (var i = 0; i < bandwidth_span; i++) {
            pos--;
            if (pos < 0) {
                pos = DETECT_SIZE - 1;
            }
            if (pos == write_pos) {
                break;
            }
            info_out.bandwidth = Math.max(info_out.bandwidth, info[pos].bandwidth);
        }
        info_out.tonality = max(tonality_avg / tonality_count, tonality_max - .2f);
        var mpos = pos0;
        var vpos = pos0;
        if (curr_lookahead > 15) {
            mpos += 5;
            if (mpos >= DETECT_SIZE) {
                mpos -= DETECT_SIZE;
            }
            vpos += 1;
            if (vpos >= DETECT_SIZE) {
                vpos -= DETECT_SIZE;
            }
        }
        var prob_min = 1.f;
        var prob_max = 0.f;
        var vad_prob = info[vpos].activity_probability;
        var prob_count = max(.1f, vad_prob);
        var prob_avg = max(.1f, vad_prob) * info[mpos].music_prob;
        while (true) {
            mpos++;
            if (mpos == DETECT_SIZE) {
                mpos = 0;
            }
            if (mpos == write_pos) {
                break;
            }
            vpos++;
            if (vpos == DETECT_SIZE) {
                vpos = 0;
            }
            if (vpos == write_pos) {
                break;
            }
            var pos_vad = info[vpos].activity_probability;
            prob_min = min((prob_avg - TRANSITION_PENALTY * (vad_prob - pos_vad)) / prob_count, prob_min);
            prob_max = max((prob_avg + TRANSITION_PENALTY * (vad_prob - pos_vad)) / prob_count, prob_max);
            prob_count += max(.1f, pos_vad);
            prob_avg += max(.1f, pos_vad) * info[mpos].music_prob;
        }
        info_out.music_prob = prob_avg / prob_count;
        prob_min = min(prob_avg / prob_count, prob_min);
        prob_max = max(prob_avg / prob_count, prob_max);
        prob_min = max(prob_min, 0.f);
        prob_max = min(prob_max, 1.f);
        if (curr_lookahead < 10) {
            var pmin = prob_min;
            var pmax = prob_max;
            pos = pos0;
            for (var i = 0; i < Math.min(count - 1, 15); i++) {
                pos--;
                if (pos < 0) {
                    pos = DETECT_SIZE - 1;
                }
                pmin = min(pmin, info[pos].music_prob);
                pmax = max(pmax, info[pos].music_prob);
            }
            pmin = max(0.f, pmin - .1f * vad_prob);
            pmax = min(1.f, pmax + .1f * vad_prob);
            prob_min += (1.f - .1f * curr_lookahead) * (pmin - prob_min);
            prob_max += (1.f - .1f * curr_lookahead) * (pmax - prob_max);
        }
        info_out.music_prob_min = prob_min;
        info_out.music_prob_max = prob_max;
    }

    /**
     * Analyzes up to 20 ms of input once enough of it is buffered ({@code tonality_analysis}).
     *
     * @param x         the interleaved float input
     * @param xOff      the index in {@code x} of sample frame zero
     * @param len       the number of input samples to consume
     * @param offset    the offset of the first input frame
     * @param c1        the first channel to mix
     * @param c2        the second channel to mix, {@code -1} for none, {@code -2} for all others
     * @param C         the number of interleaved channels
     * @param lsb_depth the effective bit depth of the input
     */
    private void tonalityAnalysis(float[] x, int xOff, int len, int offset, int c1, int c2, int C, int lsb_depth) {
        final var N = 480;
        final var N2 = 240;
        var A = angle;
        var dA = d_angle;
        var d2A = d2_angle;
        var band_tonality = this.band_tonality;
        var logE = this.bandLogE;
        var BFCC = this.BFCC;
        var slope = 0f;
        var is_masked = this.is_masked;
        var bandwidth = 0;
        var maxE = 0f;
        var midE = this.midE;
        var spec_variability = 0f;
        var band_log2 = this.band_log2;
        var leakage_from = this.leakage_from;
        var leakage_to = this.leakage_to;
        if (!initialized) {
            mem_fill = 240;
            initialized = true;
        }
        var alpha = 1.f / Math.min(10, 1 + count);
        var alphaE = 1.f / Math.min(25, 1 + count);
        var alphaE2 = 1.f / Math.min(100, 1 + count);
        if (count <= 1) {
            alphaE2 = 1;
        }
        if (Fs == 48000) {
            len /= 2;
            offset /= 2;
        } else if (Fs == 16000) {
            len = 3 * len / 2;
            offset = 3 * offset / 2;
        }
        hp_ener_accum += downmixAndResample(x, xOff, inmem, mem_fill, downmix_state,
                Math.min(len, ANALYSIS_BUF_SIZE - mem_fill), offset, c1, c2, C, Fs);
        if (mem_fill + len < ANALYSIS_BUF_SIZE) {
            mem_fill += len;
            return;
        }
        var hp_ener = hp_ener_accum;
        var info = this.info[write_pos++];
        if (write_pos >= DETECT_SIZE) {
            write_pos -= DETECT_SIZE;
        }
        var is_silence = OpusStreamEncoder.isDigitalSilence(inmem, 0, ANALYSIS_BUF_SIZE, 1, lsb_depth);
        var in = fftIn;
        var out = fftOut;
        for (var i = 0; i < N2; i++) {
            var w = analysis_window[i];
            in[2 * i] = w * inmem[i];
            in[2 * i + 1] = w * inmem[N2 + i];
            in[2 * (N - i - 1)] = w * inmem[N - i - 1];
            in[2 * (N - i - 1) + 1] = w * inmem[N + N2 - i - 1];
        }
        System.arraycopy(inmem, ANALYSIS_BUF_SIZE - 240, inmem, 0, 240);
        var remaining = len - (ANALYSIS_BUF_SIZE - mem_fill);
        hp_ener_accum = downmixAndResample(x, xOff, inmem, 240, downmix_state, remaining,
                offset + ANALYSIS_BUF_SIZE - mem_fill, c1, c2, C, Fs);
        mem_fill = 240 + remaining;
        if (is_silence) {
            var prev_pos = write_pos - 2;
            if (prev_pos < 0) {
                prev_pos += DETECT_SIZE;
            }
            copy(info, this.info[prev_pos]);
            return;
        }
        KissFft.opusFft(CeltMode.MODE_48000_960.mdct.kfft[0], in, out, fftScratch);
        if (Float.isNaN(out[0])) {
            info.valid = 0;
            return;
        }
        var tonality = this.tonality;
        var noisiness = this.noisiness;
        var tonality2 = this.tonality2;
        var binRe = this.binRe;
        var binIm = this.binIm;
        var binReRev = this.binReRev;
        var binImRev = this.binImRev;
        for (var i = 1; i < N2; i++) {
            binRe[i] = out[2 * i];
            binIm[i] = out[2 * i + 1];
            binReRev[i] = out[2 * (N - i)];
            binImRev[i] = out[2 * (N - i) + 1];
        }
        AnalysisKernels.INSTANCE.tonalityBins(binRe, binIm, binReRev, binImRev, A, dA, d2A, noisiness, tonality, tonality2);
        for (var i = 2; i < N2 - 1; i++) {
            var tt = min(tonality2[i], max(tonality2[i - 1], tonality2[i + 1]));
            tonality[i] = .9f * max(tonality[i], tt - .1f);
        }
        var frame_tonality = 0f;
        var max_frame_tonality = 0f;
        info.activity = 0;
        var frame_noisiness = 0f;
        var frame_stationarity = 0f;
        if (count == 0) {
            for (var b = 0; b < NB_TBANDS; b++) {
                lowE[b] = 1e10f;
                highE[b] = -1e10f;
            }
        }
        var relativeE = 0f;
        var frame_loudness = 0f;
        {
            var X1r = 2 * out[0];
            var X2r = 2 * out[1];
            var E = X1r * X1r + X2r * X2r;
            for (var i = 1; i < 4; i++) {
                var binE = out[2 * i] * out[2 * i] + out[2 * (N - i)] * out[2 * (N - i)]
                        + out[2 * i + 1] * out[2 * i + 1] + out[2 * (N - i) + 1] * out[2 * (N - i) + 1];
                E += binE;
            }
            band_log2[0] = .5f * 1.442695f * (float) StrictMath.log(E + 1e-10f);
        }
        for (var b = 0; b < NB_TBANDS; b++) {
            var E = 0f;
            var tE = 0f;
            var nE = 0f;
            for (var i = tbands[b]; i < tbands[b + 1]; i++) {
                var binE = out[2 * i] * out[2 * i] + out[2 * (N - i)] * out[2 * (N - i)]
                        + out[2 * i + 1] * out[2 * i + 1] + out[2 * (N - i) + 1] * out[2 * (N - i) + 1];
                E += binE;
                tE += binE * max(0, tonality[i]);
                nE += binE * 2.f * (.5f - noisiness[i]);
            }
            if (!(E < 1e9f) || Float.isNaN(E)) {
                info.valid = 0;
                return;
            }
            this.E[E_count][b] = E;
            frame_noisiness += nE / (1e-15f + E);
            frame_loudness += (float) Math.sqrt(E + 1e-10f);
            logE[b] = (float) StrictMath.log(E + 1e-10f);
            band_log2[b + 1] = .5f * 1.442695f * (float) StrictMath.log(E + 1e-10f);
            this.logE[E_count][b] = logE[b];
            if (count == 0) {
                highE[b] = lowE[b] = logE[b];
            }
            if (highE[b] > lowE[b] + 7.5) {
                if (highE[b] - logE[b] > logE[b] - lowE[b]) {
                    highE[b] -= .01f;
                } else {
                    lowE[b] += .01f;
                }
            }
            if (logE[b] > highE[b]) {
                highE[b] = logE[b];
                lowE[b] = max(highE[b] - 15, lowE[b]);
            } else if (logE[b] < lowE[b]) {
                lowE[b] = logE[b];
                highE[b] = min(lowE[b] + 15, highE[b]);
            }
            relativeE += (logE[b] - lowE[b]) / (1e-5f + (highE[b] - lowE[b]));
            var L1 = 0f;
            var L2 = 0f;
            for (var i = 0; i < NB_FRAMES; i++) {
                L1 += (float) Math.sqrt(this.E[i][b]);
                L2 += this.E[i][b];
            }
            var stationarity = min(0.99f, L1 / (float) Math.sqrt(1e-15 + NB_FRAMES * L2));
            stationarity *= stationarity;
            stationarity *= stationarity;
            frame_stationarity += stationarity;
            band_tonality[b] = max(tE / (1e-15f + E), stationarity * prev_band_tonality[b]);
            frame_tonality += band_tonality[b];
            if (b >= NB_TBANDS - NB_TONAL_SKIP_BANDS) {
                frame_tonality -= band_tonality[b - NB_TBANDS + NB_TONAL_SKIP_BANDS];
            }
            max_frame_tonality = max(max_frame_tonality, (1.f + .03f * (b - NB_TBANDS)) * frame_tonality);
            slope += band_tonality[b] * (b - 8);
            prev_band_tonality[b] = band_tonality[b];
        }
        leakage_from[0] = band_log2[0];
        leakage_to[0] = band_log2[0] - LEAKAGE_OFFSET;
        for (var b = 1; b < NB_TBANDS + 1; b++) {
            var leak_slope = LEAKAGE_SLOPE * (tbands[b] - tbands[b - 1]) / 4;
            leakage_from[b] = min(leakage_from[b - 1] + leak_slope, band_log2[b]);
            leakage_to[b] = max(leakage_to[b - 1] - leak_slope, band_log2[b] - LEAKAGE_OFFSET);
        }
        for (var b = NB_TBANDS - 2; b >= 0; b--) {
            var leak_slope = LEAKAGE_SLOPE * (tbands[b + 1] - tbands[b]) / 4;
            leakage_from[b] = min(leakage_from[b + 1] + leak_slope, leakage_from[b]);
            leakage_to[b] = max(leakage_to[b + 1] - leak_slope, leakage_to[b]);
        }
        var b = 0;
        for (; b < NB_TBANDS + 1; b++) {
            var boost = max(0, leakage_to[b] - band_log2[b])
                    + max(0, band_log2[b] - (leakage_from[b] + LEAKAGE_OFFSET));
            info.leak_boost[b] = Math.min(255, (int) Math.floor(.5 + 64.f * boost));
        }
        for (; b < LEAK_BANDS; b++) {
            info.leak_boost[b] = 0;
        }
        for (var i = 0; i < NB_FRAMES; i++) {
            var mindist = 1e15f;
            for (var j = 0; j < NB_FRAMES; j++) {
                var dist = 0f;
                for (var k = 0; k < NB_TBANDS; k++) {
                    var tmp = this.logE[i][k] - this.logE[j][k];
                    dist += tmp * tmp;
                }
                if (j != i) {
                    mindist = min(mindist, dist);
                }
            }
            spec_variability += mindist;
        }
        spec_variability = (float) Math.sqrt(spec_variability / NB_FRAMES / NB_TBANDS);
        var bandwidth_mask = 0f;
        bandwidth = 0;
        maxE = 0;
        var noise_floor = 5.7e-4f / (1 << Math.max(0, lsb_depth - 8));
        noise_floor *= noise_floor;
        var below_max_pitch = 0f;
        var above_max_pitch = 0f;
        for (b = 0; b < NB_TBANDS; b++) {
            var E = 0f;
            var band_start = tbands[b];
            var band_end = tbands[b + 1];
            for (var i = band_start; i < band_end; i++) {
                var binE = out[2 * i] * out[2 * i] + out[2 * (N - i)] * out[2 * (N - i)]
                        + out[2 * i + 1] * out[2 * i + 1] + out[2 * (N - i) + 1] * out[2 * (N - i) + 1];
                E += binE;
            }
            maxE = max(maxE, E);
            if (band_start < 64) {
                below_max_pitch += E;
            } else {
                above_max_pitch += E;
            }
            meanE[b] = max((1 - alphaE2) * meanE[b], E);
            var Em = max(E, meanE[b]);
            if (E * 1e9f > maxE && (Em > 3 * noise_floor * (band_end - band_start) || E > noise_floor * (band_end - band_start))) {
                bandwidth = b + 1;
            }
            is_masked[b] = E < (prev_bandwidth >= b + 1 ? .01f : .05f) * bandwidth_mask;
            bandwidth_mask = max(.05f * bandwidth_mask, E);
        }
        if (Fs == 48000) {
            var E = hp_ener * (1.f / (60 * 60));
            var noise_ratio = prev_bandwidth == 20 ? 10.f : 30.f;
            above_max_pitch += E;
            meanE[b] = max((1 - alphaE2) * meanE[b], E);
            var Em = max(E, meanE[b]);
            if (Em > 3 * noise_ratio * noise_floor * 160 || E > noise_ratio * noise_floor * 160) {
                bandwidth = 20;
            }
            is_masked[b] = E < (prev_bandwidth == 20 ? .01f : .05f) * bandwidth_mask;
        }
        if (above_max_pitch > below_max_pitch) {
            info.max_pitch_ratio = below_max_pitch / above_max_pitch;
        } else {
            info.max_pitch_ratio = 1;
        }
        if (bandwidth == 20 && is_masked[NB_TBANDS]) {
            bandwidth -= 2;
        } else if (bandwidth > 0 && bandwidth <= NB_TBANDS && is_masked[bandwidth - 1]) {
            bandwidth--;
        }
        if (count <= 2) {
            bandwidth = 20;
        }
        frame_loudness = 20 * (float) StrictMath.log10(frame_loudness);
        Etracker = max(Etracker - .003f, frame_loudness);
        lowECount *= (1 - alphaE);
        if (frame_loudness < Etracker - 30) {
            lowECount += alphaE;
        }
        for (var i = 0; i < 8; i++) {
            var sum = 0f;
            for (b = 0; b < 16; b++) {
                sum += dct_table[i * 16 + b] * logE[b];
            }
            BFCC[i] = sum;
        }
        for (var i = 0; i < 8; i++) {
            var sum = 0f;
            for (b = 0; b < 16; b++) {
                sum += dct_table[i * 16 + b] * .5f * (highE[b] + lowE[b]);
            }
            midE[i] = sum;
        }
        frame_stationarity /= NB_TBANDS;
        relativeE /= NB_TBANDS;
        if (count < 10) {
            relativeE = .5f;
        }
        frame_noisiness /= NB_TBANDS;
        info.activity = frame_noisiness + (1 - frame_noisiness) * relativeE;
        frame_tonality = max_frame_tonality / (NB_TBANDS - NB_TONAL_SKIP_BANDS);
        frame_tonality = max(frame_tonality, prev_tonality * .8f);
        prev_tonality = frame_tonality;
        slope /= 8 * 8;
        info.tonality_slope = slope;
        E_count = (E_count + 1) % NB_FRAMES;
        count = Math.min(count + 1, ANALYSIS_COUNT_MAX);
        info.tonality = frame_tonality;
        var features = this.features;
        for (var i = 0; i < 4; i++) {
            features[i] = -0.12299f * (BFCC[i] + mem[i + 24]) + 0.49195f * (mem[i] + mem[i + 16])
                    + 0.69693f * mem[i + 8] - 1.4349f * cmean[i];
        }
        for (var i = 0; i < 4; i++) {
            cmean[i] = (1 - alpha) * cmean[i] + alpha * BFCC[i];
        }
        for (var i = 0; i < 4; i++) {
            features[4 + i] = 0.63246f * (BFCC[i] - mem[i + 24]) + 0.31623f * (mem[i] - mem[i + 16]);
        }
        for (var i = 0; i < 3; i++) {
            features[8 + i] = 0.53452f * (BFCC[i] + mem[i + 24]) - 0.26726f * (mem[i] + mem[i + 16])
                    - 0.53452f * mem[i + 8];
        }
        if (count > 5) {
            for (var i = 0; i < 9; i++) {
                std[i] = (1 - alpha) * std[i] + alpha * features[i] * features[i];
            }
        }
        for (var i = 0; i < 4; i++) {
            features[i] = BFCC[i] - midE[i];
        }
        for (var i = 0; i < 8; i++) {
            mem[i + 24] = mem[i + 16];
            mem[i + 16] = mem[i + 8];
            mem[i + 8] = mem[i];
            mem[i] = BFCC[i];
        }
        for (var i = 0; i < 9; i++) {
            features[11 + i] = (float) Math.sqrt(std[i]) - std_feature_bias[i];
        }
        features[18] = spec_variability - 0.78f;
        features[20] = info.tonality - 0.154723f;
        features[21] = info.activity - 0.724643f;
        features[22] = frame_stationarity - 0.743717f;
        features[23] = info.tonality_slope + 0.069216f;
        features[24] = lowECount - 0.067930f;
        Mlp.computeDense(MlpData.layer0, layer_out, features);
        Mlp.computeGru(MlpData.layer1, rnn_state, layer_out, gruScratch);
        Mlp.computeDense(MlpData.layer2, frame_probs, rnn_state);
        info.activity_probability = frame_probs[1];
        info.music_prob = frame_probs[0];
        info.bandwidth = bandwidth;
        prev_bandwidth = bandwidth;
        info.noisiness = frame_noisiness;
        info.valid = 1;
    }

    /**
     * Analyzes the new input of a frame and returns the result for the frame
     * ({@code run_analysis}).
     *
     * @param analysis_pcm        the interleaved float input, or {@code null} to only read back
     * @param pcmOff              the index of the first sample of {@code analysis_pcm}
     * @param analysis_frame_size the number of new input samples
     * @param frame_size          the frame size being encoded
     * @param c1                  the first channel to mix
     * @param c2                  the second channel to mix, {@code -1} for none, {@code -2} for
     *                            all others
     * @param C                   the number of interleaved channels
     * @param Fs                  the input rate
     * @param lsb_depth           the effective bit depth of the input
     * @param analysis_info       receives the result
     */
    void runAnalysis(float[] analysis_pcm, int pcmOff, int analysis_frame_size, int frame_size, int c1, int c2, int C,
                     int Fs, int lsb_depth, AnalysisInfo analysis_info) {
        analysis_frame_size -= analysis_frame_size & 1;
        if (analysis_pcm != null) {
            analysis_frame_size = Math.min((DETECT_SIZE - 5) * Fs / 50, analysis_frame_size);
            var pcm_len = analysis_frame_size - analysis_offset;
            var offset = analysis_offset;
            while (pcm_len > 0) {
                tonalityAnalysis(analysis_pcm, pcmOff, Math.min(Fs / 50, pcm_len), offset, c1, c2, C, lsb_depth);
                offset += Fs / 50;
                pcm_len -= Fs / 50;
            }
            analysis_offset = analysis_frame_size;
            analysis_offset -= frame_size;
        }
        tonalityGetInfo(analysis_info, frame_size);
    }

    /**
     * Returns the larger of two floats with C macro semantics ({@code MAX16}, {@code MAX32}).
     *
     * @param a the first value
     * @param b the second value
     * @return {@code a > b ? a : b}
     */
    private static float max(float a, float b) {
        return a > b ? a : b;
    }

    /**
     * Returns the smaller of two floats with C macro semantics ({@code MIN16}, {@code MIN32}).
     *
     * @param a the first value
     * @param b the second value
     * @return {@code a < b ? a : b}
     */
    private static float min(float a, float b) {
        return a < b ? a : b;
    }
}
