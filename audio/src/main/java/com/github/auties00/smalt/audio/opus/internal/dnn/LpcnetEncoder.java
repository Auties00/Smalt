/* Ports libopus 1.5.2 (dnn/lpcnet_enc.c, dnn/lpcnet_private.h). Copyright Mozilla; BSD 3-Clause
   License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.dnn;

import com.github.auties00.smalt.audio.opus.internal.celt.CeltLpc;
import com.github.auties00.smalt.audio.opus.internal.celt.MathOps;
import com.github.auties00.smalt.audio.opus.internal.celt.Pitch;

import java.util.Arrays;

/**
 * Computes the LPCNet features of 10 ms frames of 16 kHz speech ({@code LPCNetEncState}): 18 cepstral
 * coefficients, the pitch, the pitch correlation and 16 LPC coefficients.
 *
 * <p>Instances are not thread-safe.
 */
final class LpcnetEncoder {
    /**
     * The number of features the networks use ({@code NB_FEATURES}).
     */
    static final int NB_FEATURES = 20;

    /**
     * The number of features computed per frame, the LPC coefficients included ({@code NB_TOTAL_FEATURES}).
     */
    static final int NB_TOTAL_FEATURES = 36;

    private static final int NB_BANDS = Freq.NB_BANDS;
    private static final int FRAME_SIZE = Freq.FRAME_SIZE;
    private static final int OVERLAP_SIZE = Freq.OVERLAP_SIZE;
    private static final int LPC_ORDER = Freq.LPC_ORDER;
    private static final int TRAINING_OFFSET = Freq.TRAINING_OFFSET;
    private static final int PITCH_MAX_PERIOD = PitchDnn.PITCH_MAX_PERIOD;
    private static final int PITCH_MIN_PERIOD = PitchDnn.PITCH_MIN_PERIOD;
    private static final int PITCH_FRAME_SIZE = 320;
    private static final int PITCH_BUF_SIZE = PITCH_MAX_PERIOD + PITCH_FRAME_SIZE;
    private static final int PITCH_IF_MAX_FREQ = 30;
    private static final int PITCH_IF_FEATURES = 3 * PITCH_IF_MAX_FREQ - 2;

    /**
     * The numerator of the 1200 Hz elliptic low-pass, {@code [b,a]=ellip(2, 2, 20, 1200/8000)}.
     */
    private static final float[] LP_B = {-0.84946f, 1.f};

    /**
     * The denominator of the 1200 Hz elliptic low-pass.
     */
    private static final float[] LP_A = {-1.54220f, 0.70781f};

    private final PitchDnn pitchdnn;
    private final Freq freq;
    private final float[] analysisMem = new float[OVERLAP_SIZE];
    private float memPreemph;
    private final float[] prevIf = new float[2 * PITCH_IF_MAX_FREQ];
    private final float[] ifFeatures = new float[PITCH_IF_FEATURES];
    private final float[] xcorrFeatures = new float[PITCH_MAX_PERIOD - PITCH_MIN_PERIOD];
    private float dnnPitch;
    private final float[] pitchMem = new float[LPC_ORDER];
    private float pitchFilt;
    private final float[] excBuf = new float[PITCH_BUF_SIZE];
    private final float[] lpBuf = new float[PITCH_BUF_SIZE];
    private final float[] lpMem = new float[4];
    private final float[] lpc = new float[LPC_ORDER];

    /**
     * The features of the last frame.
     */
    final float[] features = new float[NB_TOTAL_FEATURES];

    private final float[] alignedIn = new float[FRAME_SIZE];
    private final float[] ly = new float[NB_BANDS];
    private final float[] spectrum = new float[2 * Freq.FREQ_SIZE];
    private final float[] ex = new float[NB_BANDS];
    private final float[] xcorr = new float[PITCH_MAX_PERIOD];
    private final float[] x = new float[FRAME_SIZE + LPC_ORDER];
    private final float[] enerNorm = new float[PITCH_MAX_PERIOD - PITCH_MIN_PERIOD];
    private final float[] window = new float[Freq.WINDOW_SIZE];
    private final float[] frame = new float[FRAME_SIZE];
    private final float[] firNum = new float[LPC_ORDER];
    private final float[] firSum = new float[4];

    /**
     * Constructs an encoder with a cleared state ({@code lpcnet_encoder_init}).
     *
     * @param pitchModel the pitch network
     * @param nnet the scratch space of the network evaluation
     * @param freq the scratch space of the spectral analysis
     */
    LpcnetEncoder(PitchDnnModel pitchModel, Nnet nnet, Freq freq) {
        this.pitchdnn = new PitchDnn(pitchModel, nnet);
        this.freq = freq;
    }

    /**
     * Clears the state ({@code lpcnet_encoder_init}).
     */
    void reset() {
        pitchdnn.reset();
        Arrays.fill(analysisMem, 0);
        memPreemph = 0;
        Arrays.fill(prevIf, 0);
        Arrays.fill(ifFeatures, 0);
        Arrays.fill(xcorrFeatures, 0);
        dnnPitch = 0;
        Arrays.fill(pitchMem, 0);
        pitchFilt = 0;
        Arrays.fill(excBuf, 0);
        Arrays.fill(lpBuf, 0);
        Arrays.fill(lpMem, 0);
        Arrays.fill(lpc, 0);
        Arrays.fill(features, 0);
    }

    /**
     * Filters with a second-order section whose recursion is reordered as in the C source ({@code biquad}).
     */
    private static void biquad(float[] y, int yOff, float[] mem, float[] x, int xOff, float[] b, float[] a, int n) {
        var mem0 = mem[0];
        var mem1 = mem[1];
        for (var i = 0; i < n; i++) {
            var xi = x[xOff + i];
            var yi = x[xOff + i] + mem0;
            var mem00 = mem0;
            mem0 = (b[0] - a[0]) * xi + mem1 - a[0] * mem0;
            // The 1e-30 forces the evaluation order of the C source and does not change the result.
            mem1 = (b[1] - a[1]) * xi + 1e-30f - a[1] * mem00;
            y[yOff + i] = yi;
        }
        mem[0] = mem0;
        mem[1] = mem1;
    }

    /**
     * Returns {@code log10(x)} as {@code 0.3010299957 * celt_log2(x)} ({@code celt_log10}).
     */
    private static float log10(float x) {
        return 0.3010299957f * MathOps.celtLog2(x);
    }

    /**
     * Computes the features of one pre-emphasized frame ({@code compute_frame_features}).
     *
     * @param in the {@link Freq#FRAME_SIZE} samples
     */
    private void computeFrameFeatures(float[] in) {
        System.arraycopy(analysisMem, OVERLAP_SIZE - TRAINING_OFFSET, alignedIn, 0, TRAINING_OFFSET);
        System.arraycopy(analysisMem, 0, window, 0, OVERLAP_SIZE);
        System.arraycopy(in, 0, window, OVERLAP_SIZE, FRAME_SIZE);
        System.arraycopy(in, FRAME_SIZE - OVERLAP_SIZE, analysisMem, 0, OVERLAP_SIZE);
        Freq.applyWindow(window);
        freq.forwardTransform(spectrum, window, 0);
        Freq.computeBandEnergy(ex, spectrum);
        var X = spectrum;
        var v = (1.f / 64) * (10.f * log10(1e-15f + X[0] * X[0]) - 6.f);
        v = 1.f < v ? 1.f : v;
        ifFeatures[0] = -1.f > v ? -1.f : v;
        for (var i = 1; i < PITCH_IF_MAX_FREQ; i++) {
            var xr = X[2 * i];
            var xi = X[2 * i + 1];
            var pr = prevIf[2 * i];
            var pi = prevIf[2 * i + 1];
            var prodR = xr * pr + xi * pi;
            var prodI = xi * pr - xr * pi;
            var norm1 = (float) (1.f / StrictMath.sqrt(1e-15f + prodR * prodR + prodI * prodI));
            prodR *= norm1;
            prodI *= norm1;
            ifFeatures[3 * i - 2] = prodR;
            ifFeatures[3 * i - 1] = prodI;
            var e = (1.f / 64) * (10.f * log10(1e-15f + xr * xr + xi * xi) - 6.f);
            var c = 1.f < e ? 1.f : e;
            ifFeatures[3 * i] = -1.f > c ? -1.f : c;
        }
        System.arraycopy(X, 0, prevIf, 0, 2 * PITCH_IF_MAX_FREQ);
        var logMax = -2f;
        var follow = -2f;
        for (var i = 0; i < NB_BANDS; i++) {
            var y = log10(1e-2f + ex[i]);
            var f = follow - 2.5f;
            y = f > y ? f : y;
            y = logMax - 8 > y ? logMax - 8 : y;
            ly[i] = y;
            logMax = logMax > y ? logMax : y;
            follow = f > y ? f : y;
        }
        Freq.dct(features, 0, ly, 0);
        features[0] -= 4;
        freq.lpcFromCepstrum(lpc, features, 0);
        System.arraycopy(lpc, 0, features, NB_BANDS + 2, LPC_ORDER);
        System.arraycopy(excBuf, FRAME_SIZE, excBuf, 0, PITCH_MAX_PERIOD);
        System.arraycopy(lpBuf, FRAME_SIZE, lpBuf, 0, PITCH_MAX_PERIOD);
        System.arraycopy(in, 0, alignedIn, TRAINING_OFFSET, FRAME_SIZE - TRAINING_OFFSET);
        System.arraycopy(pitchMem, 0, x, 0, LPC_ORDER);
        System.arraycopy(alignedIn, 0, x, LPC_ORDER, FRAME_SIZE);
        System.arraycopy(alignedIn, FRAME_SIZE - LPC_ORDER, pitchMem, 0, LPC_ORDER);
        CeltLpc.celtFir(x, LPC_ORDER, lpc, 0, lpBuf, PITCH_MAX_PERIOD, FRAME_SIZE, LPC_ORDER, firNum, firSum);
        for (var i = 0; i < FRAME_SIZE; i++) {
            excBuf[PITCH_MAX_PERIOD + i] = lpBuf[PITCH_MAX_PERIOD + i] + .7f * pitchFilt;
            pitchFilt = lpBuf[PITCH_MAX_PERIOD + i];
        }
        biquad(lpBuf, PITCH_MAX_PERIOD, lpMem, lpBuf, PITCH_MAX_PERIOD, LP_B, LP_A, FRAME_SIZE);
        var buf = excBuf;
        Pitch.celtPitchXcorr(buf, PITCH_MAX_PERIOD, buf, 0, xcorr, 0, FRAME_SIZE, PITCH_MAX_PERIOD - PITCH_MIN_PERIOD);
        var ener0 = Pitch.celtInnerProd(buf, PITCH_MAX_PERIOD, buf, PITCH_MAX_PERIOD, FRAME_SIZE);
        double ener1 = Pitch.celtInnerProd(buf, 0, buf, 0, FRAME_SIZE);
        for (var i = 0; i < PITCH_MAX_PERIOD - PITCH_MIN_PERIOD; i++) {
            var ener = (float) ((1 + ener0) + ener1);
            xcorrFeatures[i] = 2 * xcorr[i];
            enerNorm[i] = ener;
            ener1 += buf[i + FRAME_SIZE] * (double) buf[i + FRAME_SIZE] - buf[i] * (double) buf[i];
        }
        for (var i = 0; i < PITCH_MAX_PERIOD - PITCH_MIN_PERIOD; i++) {
            xcorrFeatures[i] /= enerNorm[i];
        }
        dnnPitch = pitchdnn.compute(ifFeatures, xcorrFeatures);
        var pitch = period(dnnPitch);
        var xx = Pitch.celtInnerProd(lpBuf, PITCH_MAX_PERIOD, lpBuf, PITCH_MAX_PERIOD, FRAME_SIZE);
        var yy = Pitch.celtInnerProd(lpBuf, PITCH_MAX_PERIOD - pitch, lpBuf, PITCH_MAX_PERIOD - pitch, FRAME_SIZE);
        var xy = Pitch.celtInnerProd(lpBuf, PITCH_MAX_PERIOD, lpBuf, PITCH_MAX_PERIOD - pitch, FRAME_SIZE);
        var frameCorr = (float) (xy / StrictMath.sqrt(1 + xx * yy));
        frameCorr = (float) (StrictMath.log(1.f + StrictMath.exp(5.f * frameCorr))
                / StrictMath.log(1 + StrictMath.exp(5.f)));
        features[NB_BANDS] = dnnPitch;
        features[NB_BANDS + 1] = frameCorr - .5f;
    }

    /**
     * Converts a pitch feature to a period in samples at 16 kHz, as the vocoder and the analysis do.
     *
     * @param pitchFeature the pitch feature
     * @return the period
     */
    static int period(float pitchFeature) {
        return (int) StrictMath.floor(.5 + 256. / StrictMath.pow(2.f, (1. / 60.) * ((pitchFeature + 1.5) * 60)));
    }

    /**
     * Computes the features of one 10 ms frame of float samples in 16-bit range
     * ({@code lpcnet_compute_single_frame_features_float}).
     *
     * @param pcm the samples
     * @param pcmOffset the index of the first of {@link Freq#FRAME_SIZE} samples
     * @param out receives {@link #NB_TOTAL_FEATURES} features
     * @param outOffset the index of the first feature
     */
    void computeSingleFrameFeatures(float[] pcm, int pcmOffset, float[] out, int outOffset) {
        var mem = memPreemph;
        for (var i = 0; i < FRAME_SIZE; i++) {
            var xi = pcm[pcmOffset + i];
            frame[i] = xi + mem;
            mem = -Freq.PREEMPHASIS * xi;
        }
        memPreemph = mem;
        computeFrameFeatures(frame);
        System.arraycopy(features, 0, out, outOffset, NB_TOTAL_FEATURES);
    }
}
