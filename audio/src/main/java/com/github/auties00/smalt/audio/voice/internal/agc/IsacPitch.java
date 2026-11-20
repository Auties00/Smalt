/* Ports webrtc-audio-processing 2.1 (modules/audio_coding/codecs/isac/main/source/isac_vad.c, pitch_estimator.c,
   pitch_filter.c, filter_functions.c). Copyright (c) 2011, The WebRTC project authors; BSD 3-Clause License.
   Modified for Smalt. */
package com.github.auties00.smalt.audio.voice.internal.agc;

import com.github.auties00.smalt.audio.voice.internal.DoubleMath;

/**
 * Estimates the pitch lags and gains of 30 ms of 16 kHz audio with the iSAC encoder's pitch
 * analysis, as the voice activity detector of the analog gain controller uses it.
 *
 * <p>Each call takes 480 samples, keeps their lower half band at 8 kHz and returns four lags (in
 * 8 kHz samples) and four gains, one per 7.5 ms sub-frame. Instances keep the filter states of
 * the previous call and are not thread-safe.
 *
 * @implNote This implementation ports {@code WebRtcIsac_SplitAndFilterFloat} and
 * {@code WebRtcIsac_PitchAnalysis}, leaving out the stages whose results the detector does not
 * read: the phase-equalized band outputs of the filter bank and the lookahead pitch filter. The
 * reference reads the correlation surface out of bounds around peaks next to its border; this
 * implementation reads zeros there, so such frames may differ from it.
 */
final class IsacPitch {
    private static final int FRAME_SAMPLES = 480;
    private static final int HALF = 240;
    private static final int QLOOKAHEAD = 24;
    private static final int PITCH_FRAME_LEN = 240;
    private static final int PITCH_MAX_LAG = 140;
    private static final int PITCH_MIN_LAG = 20;
    private static final double PITCH_MAX_GAIN = 0.45;
    private static final double PITCH_MAX_GAIN_06 = 0.27;
    private static final int PITCH_LAG_SPAN2 = PITCH_MAX_LAG / 2 - PITCH_MIN_LAG / 2 + 5;
    private static final int PITCH_CORR_LEN2 = 60;
    private static final int PITCH_CORR_STEP2 = PITCH_FRAME_LEN / 4;
    private static final int PITCH_BW = 11;
    private static final int PITCH_SUBFRAMES = 4;
    private static final int PITCH_GRAN_PER_SUBFRAME = 5;
    private static final int PITCH_SUBFRAME_LEN = PITCH_FRAME_LEN / PITCH_SUBFRAMES;
    private static final int PITCH_UPDATE = PITCH_SUBFRAME_LEN / PITCH_GRAN_PER_SUBFRAME;
    private static final int PITCH_MAX_NUM_PEAKS = 10;
    private static final double PITCH_PEAK_DECAY = 0.85;
    private static final int PITCH_WLPCORDER = 6;
    private static final int PITCH_WLPCWINLEN = PITCH_FRAME_LEN;
    private static final double PITCH_WLPCASYM = 0.3;
    private static final int PITCH_WLPCBUFLEN = PITCH_WLPCWINLEN;
    private static final int PITCH_BUFFSIZE = PITCH_MAX_LAG + 50;
    private static final int PITCH_INTBUFFSIZE = PITCH_FRAME_LEN + PITCH_BUFFSIZE;
    private static final double PITCH_UPSTEP = 1.5;
    private static final double PITCH_DOWNSTEP = 0.67;
    private static final int PITCH_FRACS = 8;
    private static final int PITCH_FRACORDER = 9;
    private static final int PITCH_DAMPORDER = 5;
    private static final double PITCH_FILTDELAY = 1.5;
    private static final int ALLPASSSECTIONS = 2;
    private static final int DEC_STATE = PITCH_CORR_LEN2 + PITCH_CORR_STEP2 + PITCH_MAX_LAG / 2
            - PITCH_FRAME_LEN / 2 + 2;
    private static final int DEC_BUFFER = PITCH_CORR_LEN2 + PITCH_CORR_STEP2 + PITCH_MAX_LAG / 2 + 2;
    private static final int SURFACE_ROW = PITCH_LAG_SPAN2 + 4;
    private static final int SURFACE_ROWS = 2 * PITCH_BW + 3;
    private static final int SURFACE_PAD = 2 * (PITCH_LAG_SPAN2 + 5) + 8;
    private static final int LOOKAHEAD_FRAME = PITCH_FRAME_LEN + QLOOKAHEAD;

    private static final float[] HP_ST_COEF_IN = {-1.94895953203325f, 0.94984516000000f, -0.05101826139794f,
            0.05015484000000f};
    private static final float[] UPPER_AP_FACTORS = {0.03470000000000f, 0.38260000000000f};
    private static final float[] LOWER_AP_FACTORS = {0.15440000000000f, 0.74400000000000f};
    private static final double[] AP_UPPER = {0.0347, 0.3826};
    private static final double[] AP_LOWER = {0.1544, 0.744};
    private static final double[] INTERPOL_WIN = {-0.00067556028640, 0.02184247643159, -0.12203175715679,
            0.60086484101160, 0.60086484101160, -0.12203175715679, 0.02184247643159, -0.00067556028640};
    private static final double[] LAG_WIN = {0.2, 0.5, 0.98};
    private static final double[][] WEIGHT = {
            {0.29714285714286, -0.30857142857143, -0.05714285714286, 0.05142857142857, 0.01714285714286},
            {-0.30857142857143, 0.67428571428571, -0.27142857142857, -0.14571428571429, 0.05142857142857},
            {-0.05714285714286, -0.27142857142857, 0.65714285714286, -0.27142857142857, -0.05714285714286},
            {0.05142857142857, -0.14571428571429, -0.27142857142857, 0.67428571428571, -0.30857142857143},
            {0.01714285714286, 0.05142857142857, -0.05714285714286, -0.30857142857143, 0.29714285714286}};
    private static final double[] HP_A = {1.86864659625574, -0.88360000000000};
    private static final double[] HP_B = {-1.99524591718270, 0.99600400000000};
    private static final double[] DAMP_FILTER = {-0.07, 0.25, 0.64, 0.25, -0.07};
    private static final double[][] INTRP_COEF = {
            {-0.02239172458614, 0.06653315052934, -0.16515880017569, 0.60701333734125, 0.64671399919202,
                    -0.20249000396417, 0.09926548334755, -0.04765933793109, 0.01754159521746},
            {-0.01985640750434, 0.05816126837866, -0.13991265473714, 0.44560418147643, 0.79117042386876,
                    -0.20266133815188, 0.09585268418555, -0.04533310458084, 0.01654127246314},
            {-0.01463300534216, 0.04229888475060, -0.09897034715253, 0.28284326017787, 0.90385267956632,
                    -0.16976950138649, 0.07704272393639, -0.03584218578311, 0.01295781500709},
            {-0.00764851320885, 0.02184035544377, -0.04985561057281, 0.13083306574393, 0.97545011664662,
                    -0.10177807997561, 0.04400901776474, -0.02010737175166, 0.00719783432422},
            {-0.00000000000000, 0.00000000000000, -0.00000000000001, 0.00000000000001, 0.99999999999999,
                    0.00000000000001, -0.00000000000001, 0.00000000000000, -0.00000000000000},
            {0.00719783432422, -0.02010737175166, 0.04400901776474, -0.10177807997562, 0.97545011664663,
                    0.13083306574393, -0.04985561057280, 0.02184035544377, -0.00764851320885},
            {0.01295781500710, -0.03584218578312, 0.07704272393640, -0.16976950138650, 0.90385267956634,
                    0.28284326017785, -0.09897034715252, 0.04229888475059, -0.01463300534216},
            {0.01654127246315, -0.04533310458085, 0.09585268418557, -0.20266133815190, 0.79117042386878,
                    0.44560418147640, -0.13991265473712, 0.05816126837865, -0.01985640750433}};

    private final float[] hpStates = new float[2];
    private final float[] instatLa1 = new float[2];
    private final float[] instatLa2 = new float[2];

    private final double[] decBuffer = new double[DEC_STATE];
    private final double[] decimatorState = new double[2 * ALLPASSSECTIONS + 1];
    private final double[] hpState = new double[2];
    private final double[] whitenedBuf = new double[QLOOKAHEAD];
    private final double[] ubuf = new double[PITCH_BUFFSIZE];
    private final double[] ystate = new double[PITCH_DAMPORDER];
    private double oldLag = 50.0;
    private double oldGain;
    private final double[] weightBuffer = new double[PITCH_WLPCBUFLEN];
    private final double[] weostate = new double[PITCH_WLPCORDER];
    private final double[] whostate = new double[PITCH_WLPCORDER];
    private final double[] window = new double[PITCH_WLPCWINLEN];

    private final float[] in = new float[FRAME_SAMPLES];
    private final float[] ch1 = new float[HALF];
    private final float[] ch2 = new float[HALF];
    private final double[] lowLookahead = new double[HALF];
    private final double[] hpIn = new double[PITCH_FRAME_LEN];
    private final double[] weighted = new double[PITCH_FRAME_LEN];
    private final double[] whitened = new double[LOOKAHEAD_FRAME];
    private final double[] outG = new double[LOOKAHEAD_FRAME];
    private final double[][] outDg = new double[4][LOOKAHEAD_FRAME];
    private final double[] preOut = new double[LOOKAHEAD_FRAME];
    private final double[][] hessian = new double[4][4];
    private final double[] grad = new double[4];
    private final double[] dG = new double[4];
    private final double[] tmpBuffer = new double[PITCH_FRAME_LEN + PITCH_WLPCBUFLEN];
    private final double[] weoutBuf = new double[PITCH_WLPCBUFLEN + PITCH_WLPCORDER];
    private final double[] whoutBuf = new double[PITCH_WLPCBUFLEN + PITCH_WLPCORDER];
    private final double[] corr = new double[PITCH_WLPCORDER + 1];
    private final double[] rc = new double[PITCH_WLPCORDER + 1];
    private final double[] apol = new double[PITCH_WLPCORDER + 1];
    private final double[] apolr = new double[PITCH_WLPCORDER + 1];
    private final double[] opol = new double[PITCH_WLPCORDER + 1];
    private final double[] ext = new double[PITCH_WLPCWINLEN];
    private final double[] bufDec = new double[DEC_BUFFER];
    private final double[] dataVec = new double[PITCH_FRAME_LEN];
    private final double[] corrvec1 = new double[PITCH_LAG_SPAN2];
    private final double[] corrvec2 = new double[PITCH_LAG_SPAN2];
    private final double[] surface = new double[SURFACE_PAD + SURFACE_ROWS * SURFACE_ROW + SURFACE_PAD];
    private final int[] peaks = new int[PITCH_MAX_NUM_PEAKS];
    private final double[] peakVals = new double[PITCH_MAX_NUM_PEAKS];
    private final double[] lags1 = new double[PITCH_MAX_NUM_PEAKS];
    private final double[] lags2 = new double[PITCH_MAX_NUM_PEAKS];
    private final double[][] t = new double[3][3];
    private final double[] point = new double[3];
    private final Filter filter = new Filter();

    /**
     * Creates an analyzer in its initial state ({@code WebRtcIsac_InitPreFilterbank},
     * {@code WebRtcIsac_InitPitchAnalysis}).
     */
    IsacPitch() {
        var tt = 0.5;
        var denum = 1.0 / ((double) PITCH_WLPCWINLEN);
        var denum2 = denum * denum;
        for (var k = 0; k < PITCH_WLPCWINLEN; k++) {
            var dtmp = PITCH_WLPCASYM * tt * denum + (1 - PITCH_WLPCASYM) * tt * tt * denum2;
            dtmp *= 3.14159265;
            var dtmp2 = DoubleMath.sin(dtmp);
            window[k] = dtmp2 * dtmp2;
            tt++;
        }
    }

    /**
     * Analyzes 30 ms of audio.
     *
     * @param samples the 16 kHz samples
     * @param offset the index of the first of the 480 samples
     * @param lags receives the four pitch lags, in 8 kHz samples
     * @param gains receives the four pitch gains
     */
    void analyze(float[] samples, int offset, double[] lags, double[] gains) {
        splitAndFilter(samples, offset);
        pitchAnalysis(lowLookahead, lags, gains);
    }

    private void splitAndFilter(float[] pin, int offset) {
        for (var k = 0; k < FRAME_SAMPLES; k++) {
            var x = pin[offset + k];
            in[k] = x + HP_ST_COEF_IN[2] * hpStates[0] + HP_ST_COEF_IN[3] * hpStates[1];
            var ftmp = x - HP_ST_COEF_IN[0] * hpStates[0] - HP_ST_COEF_IN[1] * hpStates[1];
            hpStates[1] = hpStates[0];
            hpStates[0] = ftmp;
        }
        for (var k = 0; k < HALF; k++) {
            ch1[k] = in[2 * k + 1];
            ch2[k] = in[2 * k];
        }
        allPass(ch1, UPPER_AP_FACTORS, instatLa1);
        allPass(ch2, LOWER_AP_FACTORS, instatLa2);
        for (var k = 0; k < HALF; k++) {
            lowLookahead[k] = 0.5f * (ch1[k] + ch2[k]);
        }
    }

    private static void allPass(float[] inOut, float[] factors, float[] state) {
        for (var j = 0; j < factors.length; j++) {
            for (var n = 0; n < HALF; n++) {
                var temp = state[j] + factors[j] * inOut[n];
                state[j] = -factors[j] * temp + inOut[n];
                inOut[n] = temp;
            }
        }
    }

    private void pitchAnalysis(double[] input, double[] lags, double[] gains) {
        for (var k = 0; k < PITCH_FRAME_LEN; k++) {
            var x = input[k];
            var out = x + hpState[1];
            hpIn[k] = out;
            hpState[1] = hpState[0] + HP_B[0] * x + HP_A[0] * out;
            hpState[0] = HP_B[1] * x + HP_A[1] * out;
        }
        System.arraycopy(whitenedBuf, 0, whitened, 0, QLOOKAHEAD);
        weightingFilter(hpIn, weighted, whitened, QLOOKAHEAD);
        System.arraycopy(whitened, PITCH_FRAME_LEN, whitenedBuf, 0, QLOOKAHEAD);
        var previousLag = oldLag;
        var previousGain = oldGain;
        initializePitch(weighted, previousLag, previousGain, lags);
        var nrgWht = 0.0;
        for (var k = 0; k < LOOKAHEAD_FRAME; k++) {
            nrgWht += whitened[k] * whitened[k];
        }
        var wnrg = 1.0 / nrgWht;
        var wgain = 0.005;
        var wfluct = 3.0;
        for (var k = 0; k < 4; k++) {
            gains[k] = PITCH_MAX_GAIN_06;
        }
        var h = hessian;
        for (var iter = 0; iter < 2; iter++) {
            filterFrame(whitened, lags, gains, true, outG, outDg);
            for (var k = 0; k < 4; k++) {
                var tmp = 0.0;
                for (var n = 0; n < LOOKAHEAD_FRAME; n++) {
                    tmp += outG[n] * outDg[k][n];
                }
                grad[k] = tmp * wnrg;
            }
            for (var k = 0; k < 4; k++) {
                for (var m = 0; m <= k; m++) {
                    var tmp = 0.0;
                    for (var n = 0; n < LOOKAHEAD_FRAME; n++) {
                        tmp += outDg[m][n] * outDg[k][n];
                    }
                    h[k][m] = tmp * wnrg;
                }
            }
            for (var k = 0; k < 4; k++) {
                var tmp = WEIGHT[k + 1][0] * previousGain;
                for (var m = 0; m < 4; m++) {
                    tmp += WEIGHT[k + 1][m + 1] * gains[m];
                }
                grad[k] += tmp * wfluct;
            }
            for (var k = 0; k < 4; k++) {
                for (var m = 0; m <= k; m++) {
                    h[k][m] += WEIGHT[k + 1][m + 1] * wfluct;
                }
            }
            for (var k = 0; k < 3; k++) {
                var tmp = 1.0 / (1 - gains[k]);
                grad[k] += tmp * tmp * wgain;
                h[k][k] += 2.0 * tmp * (tmp * tmp * wgain);
            }
            var tmp = 1.0 / (1 - gains[3]);
            grad[3] += 1.33 * (tmp * tmp * wgain);
            h[3][3] += 2.66 * tmp * (tmp * tmp * wgain);
            h[0][1] = h[1][0] / h[0][0];
            h[0][2] = h[2][0] / h[0][0];
            h[0][3] = h[3][0] / h[0][0];
            h[1][1] -= h[0][0] * h[0][1] * h[0][1];
            h[1][2] = (h[2][1] - h[0][1] * h[2][0]) / h[1][1];
            h[1][3] = (h[3][1] - h[0][1] * h[3][0]) / h[1][1];
            h[2][2] -= h[0][0] * h[0][2] * h[0][2] + h[1][1] * h[1][2] * h[1][2];
            h[2][3] = (h[3][2] - h[0][2] * h[3][0] - h[1][2] * h[1][1] * h[1][3]) / h[2][2];
            h[3][3] -= h[0][0] * h[0][3] * h[0][3] + h[1][1] * h[1][3] * h[1][3] + h[2][2] * h[2][3] * h[2][3];
            for (var k = 0; k < 4; k++) {
                dG[k] = -grad[k];
            }
            dG[1] -= dG[0] * h[0][1];
            dG[2] -= dG[0] * h[0][2] + dG[1] * h[1][2];
            dG[3] -= dG[0] * h[0][3] + dG[1] * h[1][3] + dG[2] * h[2][3];
            for (var k = 0; k < 4; k++) {
                dG[k] /= h[k][k];
            }
            dG[2] -= dG[3] * h[2][3];
            dG[1] -= dG[3] * h[1][3] + dG[2] * h[1][2];
            dG[0] -= dG[3] * h[0][3] + dG[2] * h[0][2] + dG[1] * h[0][1];
            for (var k = 0; k < 4; k++) {
                gains[k] += dG[k];
                if (gains[k] > PITCH_MAX_GAIN) {
                    gains[k] = PITCH_MAX_GAIN;
                } else if (gains[k] < 0.0) {
                    gains[k] = 0.0;
                }
            }
        }
        filterFrame(whitened, lags, gains, false, preOut, null);
    }

    private void weightingFilter(double[] input, double[] weiout, double[] whiout, int whiOffset) {
        System.arraycopy(weightBuffer, 0, tmpBuffer, 0, PITCH_WLPCBUFLEN);
        System.arraycopy(input, 0, tmpBuffer, PITCH_WLPCBUFLEN, PITCH_FRAME_LEN);
        System.arraycopy(tmpBuffer, PITCH_FRAME_LEN, weightBuffer, 0, PITCH_WLPCBUFLEN);
        for (var k = 0; k < PITCH_WLPCORDER; k++) {
            weoutBuf[k] = weostate[k];
            whoutBuf[k] = whostate[k];
            opol[k] = 0.0;
        }
        opol[0] = 1.0;
        opol[PITCH_WLPCORDER] = 0.0;
        var weo = PITCH_WLPCORDER;
        var endpos = PITCH_WLPCBUFLEN + PITCH_SUBFRAME_LEN;
        var inp = PITCH_WLPCBUFLEN;
        for (var n = 0; n < PITCH_SUBFRAMES; n++) {
            var start = endpos - PITCH_WLPCWINLEN;
            for (var k = 0; k < PITCH_WLPCWINLEN; k++) {
                ext[k] = window[k] * tmpBuffer[start + k];
            }
            autoCorr(corr, ext, PITCH_WLPCWINLEN, PITCH_WLPCORDER);
            corr[0] = 1.01 * corr[0] + 1.0;
            levinsonDurbin(apol, rc, corr, PITCH_WLPCORDER);
            var chirp = 0.9;
            apolr[0] = apol[0];
            for (var i = 1; i < PITCH_WLPCORDER + 1; i++) {
                apolr[i] = chirp * apol[i];
                chirp *= 0.9;
            }
            zeroPoleFilter(tmpBuffer, inp, apol, apolr, PITCH_SUBFRAME_LEN, PITCH_WLPCORDER, weoutBuf, weo);
            zeroPoleFilter(tmpBuffer, inp, apolr, opol, PITCH_SUBFRAME_LEN, PITCH_WLPCORDER, whoutBuf, weo);
            inp += PITCH_SUBFRAME_LEN;
            endpos += PITCH_SUBFRAME_LEN;
            weo += PITCH_SUBFRAME_LEN;
        }
        for (var k = 0; k < PITCH_WLPCORDER; k++) {
            weostate[k] = weoutBuf[PITCH_FRAME_LEN + k];
            whostate[k] = whoutBuf[PITCH_FRAME_LEN + k];
        }
        System.arraycopy(weoutBuf, PITCH_WLPCORDER, weiout, 0, PITCH_FRAME_LEN);
        System.arraycopy(whoutBuf, PITCH_WLPCORDER, whiout, whiOffset, PITCH_FRAME_LEN);
    }

    private static void zeroPoleFilter(double[] in, int inOffset, double[] zeroCoef, double[] poleCoef, int length,
                                       int order, double[] out, int outOffset) {
        for (var n = 0; n < length; n++) {
            var tmp = in[inOffset + n] * zeroCoef[0];
            for (var k = 1; k <= order; k++) {
                tmp += zeroCoef[k] * in[inOffset + n - k];
            }
            out[outOffset + n] = tmp;
        }
        if (poleCoef[0] > 0.9999 && poleCoef[0] < 1.0001) {
            for (var n = 0; n < length; n++) {
                var p = outOffset + n;
                var sum = poleCoef[1] * out[p - 1];
                for (var k = 2; k <= order; k++) {
                    sum += poleCoef[k] * out[p - k];
                }
                out[p] -= sum;
            }
        } else {
            var scal = 1.0 / poleCoef[0];
            for (var n = 0; n < length; n++) {
                var p = outOffset + n;
                out[p] *= scal;
                for (var k = 1; k <= order; k++) {
                    out[p] -= scal * poleCoef[k] * out[p - k];
                }
            }
        }
    }

    /**
     * Computes autocorrelation coefficients ({@code WebRtcIsac_AutoCorr}).
     *
     * @param r receives the {@code order + 1} coefficients
     * @param x the signal
     * @param length the number of samples
     * @param order the highest lag
     */
    static void autoCorr(double[] r, double[] x, int length, int order) {
        for (var lag = 0; lag <= order; lag++) {
            var sum = 0.0;
            var prod = x[0] * x[lag];
            for (var n = 1; n < length - lag; n++) {
                sum += prod;
                prod = x[n] * x[lag + n];
            }
            sum += prod;
            r[lag] = sum;
        }
    }

    /**
     * Computes LPC and reflection coefficients from autocorrelations ({@code WebRtcIsac_LevDurb}).
     *
     * @param a receives the {@code order + 1} LPC coefficients
     * @param k receives the {@code order} reflection coefficients
     * @param r the {@code order + 1} autocorrelations
     * @param order the LPC order
     * @return the prediction error
     */
    static double levinsonDurbin(double[] a, double[] k, double[] r, int order) {
        var alpha = 0.0;
        a[0] = 1.0;
        if (r[0] < 1.0e-10) {
            for (var i = 0; i < order; i++) {
                k[i] = 0;
                a[i + 1] = 0;
            }
        } else {
            a[1] = k[0] = -r[1] / r[0];
            alpha = r[0] + r[1] * k[0];
            for (var m = 1; m < order; m++) {
                var sum = r[m + 1];
                for (var i = 0; i < m; i++) {
                    sum += a[i + 1] * r[m - i];
                }
                k[m] = -sum / alpha;
                alpha += k[m] * sum;
                var mh = (m + 1) >> 1;
                for (var i = 0; i < mh; i++) {
                    sum = a[i + 1] + k[m] * a[m - i];
                    a[m - i] += k[m] * a[i + 1];
                    a[i + 1] = sum;
                }
                a[m + 1] = k[m];
            }
        }
        return alpha;
    }

    private void decimate(double[] input, double[] out, int outOffset) {
        var n = PITCH_FRAME_LEN;
        System.arraycopy(input, 0, dataVec, 1, n - 1);
        dataVec[0] = decimatorState[2 * ALLPASSSECTIONS];
        decimatorState[2 * ALLPASSSECTIONS] = input[n - 1];
        allpassForDecimation(dataVec, 1, AP_UPPER, n, decimatorState, 0);
        allpassForDecimation(dataVec, 0, AP_LOWER, n, decimatorState, ALLPASSSECTIONS);
        for (var i = 0; i < n / 2; i++) {
            out[outOffset + i] = dataVec[2 * i] + dataVec[2 * i + 1];
        }
    }

    private static void allpassForDecimation(double[] inOut, int offset, double[] factors, int length,
                                             double[] state, int stateOffset) {
        for (var j = 0; j < ALLPASSSECTIONS; j++) {
            for (var n = 0; n < length; n += 2) {
                var temp = inOut[offset + n];
                inOut[offset + n] = state[stateOffset + j] + factors[j] * temp;
                state[stateOffset + j] = -factors[j] * inOut[offset + n] + temp;
            }
        }
    }

    private static void pCorr(double[] in, int offset, double[] outcorr) {
        var ysum = 1e-13;
        var sum = 0.0;
        var x = offset + PITCH_MAX_LAG / 2 + 2;
        for (var n = 0; n < PITCH_CORR_LEN2; n++) {
            ysum += in[offset + n] * in[offset + n];
            sum += in[x + n] * in[offset + n];
        }
        var out = PITCH_LAG_SPAN2 - 1;
        outcorr[out] = sum / Math.sqrt(ysum);
        for (var k = 1; k < PITCH_LAG_SPAN2; k++) {
            ysum -= in[offset + k - 1] * in[offset + k - 1];
            ysum += in[offset + PITCH_CORR_LEN2 + k - 1] * in[offset + PITCH_CORR_LEN2 + k - 1];
            sum = 0.0;
            var inptr = offset + k;
            var prod = in[x] * in[inptr];
            for (var n = 1; n < PITCH_CORR_LEN2; n++) {
                sum += prod;
                prod = in[x + n] * in[inptr + n];
            }
            sum += prod;
            out--;
            outcorr[out] = sum / Math.sqrt(ysum);
        }
    }

    private double interpolate(int index) {
        var s = surface;
        var intrp = INTERPOL_WIN[0] * s[index - 3];
        intrp += INTERPOL_WIN[1] * s[index - 2];
        intrp += INTERPOL_WIN[2] * s[index - 1];
        intrp += INTERPOL_WIN[3] * s[index];
        intrp += INTERPOL_WIN[4] * s[index + 1];
        intrp += INTERPOL_WIN[5] * s[index + 2];
        intrp += INTERPOL_WIN[6] * s[index + 3];
        intrp += INTERPOL_WIN[7] * s[index + 4];
        return intrp;
    }

    private void initializePitch(double[] input, double previousLag, double previousGain, double[] lags) {
        var base = SURFACE_PAD;
        java.util.Arrays.fill(surface, 0.0);
        var maxInd = 0;
        System.arraycopy(decBuffer, 0, bufDec, 0, DEC_STATE);
        decimate(input, bufDec, DEC_STATE);
        for (var k = DEC_STATE; k < DEC_BUFFER; k++) {
            bufDec[k] += 0.75 * bufDec[k - 1] - 0.25 * bufDec[k - 2];
        }
        System.arraycopy(bufDec, PITCH_FRAME_LEN / 2, decBuffer, 0, DEC_STATE);
        pCorr(bufDec, 0, corrvec1);
        pCorr(bufDec, PITCH_CORR_STEP2, corrvec2);
        var logLag = DoubleMath.log(0.5 * previousLag);
        var gainBias = 4.0 * previousGain * previousGain;
        if (gainBias > 0.8) {
            gainBias = 0.8;
        }
        for (var k = 0; k < PITCH_LAG_SPAN2; k++) {
            var ratio = DoubleMath.log((double) (k + (PITCH_MIN_LAG / 2 - 2))) - logLag;
            var bias = 1.0 + gainBias * DoubleMath.exp(-5.0 * ratio * ratio);
            corrvec1[k] *= bias;
        }
        for (var k = 0; k < 3; k++) {
            var gainTmp = LAG_WIN[k];
            corrvec1[k] *= gainTmp;
            corrvec2[k] *= gainTmp;
            corrvec1[PITCH_LAG_SPAN2 - 1 - k] *= gainTmp;
            corrvec2[PITCH_LAG_SPAN2 - 1 - k] *= gainTmp;
        }
        var s = surface;
        var corrMax = 0.0;
        var ptr1 = base + PITCH_BW * SURFACE_ROW + 2;
        for (var k = 0; k < PITCH_LAG_SPAN2; k++) {
            var c = corrvec1[k] + corrvec2[k];
            s[ptr1 + k] = c;
            if (c > corrMax) {
                corrMax = c;
                maxInd = ptr1 + k - base;
            }
        }
        for (var m = 0; m < PITCH_BW; m++) {
            var ind1 = 0;
            var ind2 = PITCH_BW - m;
            ptr1 = base + m * SURFACE_ROW + 2;
            var ptr2 = base + (2 * PITCH_BW - m) * SURFACE_ROW + PITCH_BW + 2 - m;
            var scale = m == 0 ? 0.2 : (m == 1 ? 0.9 : 1.0);
            for (var k = 0; k < PITCH_LAG_SPAN2 - PITCH_BW + m; k++) {
                var ratio = ((double) (ind1 + 12)) / ((double) (ind2 + 12));
                var adj = m < 2 ? scale * ratio * (2.0 - ratio) : ratio * (2.0 - ratio);
                var c = adj * (corrvec1[ind1] + corrvec2[ind2]);
                s[ptr1 + k] = c;
                if (c > corrMax) {
                    corrMax = c;
                    maxInd = ptr1 + k - base;
                }
                c = adj * (corrvec1[ind2++] + corrvec2[ind1++]);
                s[ptr2 + k] = c;
                if (c > corrMax) {
                    corrMax = c;
                    maxInd = ptr2 + k - base;
                }
            }
        }
        corrMax *= 0.6;
        var peaksInd = 0;
        for (var m = 1; m < PITCH_BW + 1; m++) {
            if (peaksInd == PITCH_MAX_NUM_PEAKS) {
                break;
            }
            ptr1 = base + m * SURFACE_ROW + 2;
            for (var k = 2; k < PITCH_LAG_SPAN2 - PITCH_BW - 2 + m; k++) {
                if (isPeak(ptr1 + k, corrMax)) {
                    peaks[peaksInd++] = ptr1 + k - base;
                    if (peaksInd == PITCH_MAX_NUM_PEAKS) {
                        break;
                    }
                }
            }
        }
        for (var m = PITCH_BW + 1; m < 2 * PITCH_BW; m++) {
            if (peaksInd == PITCH_MAX_NUM_PEAKS) {
                break;
            }
            ptr1 = base + m * SURFACE_ROW + 2;
            for (var k = 2 + m - PITCH_BW; k < PITCH_LAG_SPAN2 - 2; k++) {
                if (isPeak(ptr1 + k, corrMax)) {
                    peaks[peaksInd++] = ptr1 + k - base;
                    if (peaksInd == PITCH_MAX_NUM_PEAKS) {
                        break;
                    }
                }
            }
        }
        if (peaksInd > 0) {
            var a = SURFACE_ROW + 1;
            var b = SURFACE_ROW;
            for (var k = 0; k < peaksInd; k++) {
                var peak = base + peaks[k];
                var intrpA = interpolate(peak - a);
                var intrpB = interpolate(peak - 1);
                var intrpC = interpolate(peak);
                var intrpD = interpolate(peak + b);
                var c = s[peak];
                corrMax = intrpA;
                if (intrpB > corrMax) {
                    corrMax = intrpB;
                }
                if (intrpC > corrMax) {
                    corrMax = intrpC;
                }
                if (intrpD > corrMax) {
                    corrMax = intrpD;
                }
                var row = peaks[k] / SURFACE_ROW;
                lags1[k] = (double) ((peaks[k] - row * SURFACE_ROW) + PITCH_MIN_LAG / 2 - 4);
                lags2[k] = (double) (lags1[k] + PITCH_BW - row);
                var tt = t;
                if (c > corrMax) {
                    tt[0][0] = s[peak - a];
                    tt[2][0] = s[peak - b];
                    tt[1][1] = c;
                    tt[0][2] = s[peak + b];
                    tt[2][2] = s[peak + a];
                    tt[1][0] = intrpA;
                    tt[0][1] = intrpB;
                    tt[2][1] = intrpC;
                    tt[1][2] = intrpD;
                } else if (intrpA == corrMax) {
                    lags1[k] -= 0.5;
                    lags2[k] += 0.5;
                    tt[0][0] = interpolate(peak - 2 * a);
                    tt[2][0] = interpolate(peak - (2 * PITCH_LAG_SPAN2 + 9));
                    tt[1][1] = intrpA;
                    tt[0][2] = intrpB;
                    tt[2][2] = intrpC;
                    tt[1][0] = s[peak - (2 * PITCH_LAG_SPAN2 + 9)];
                    tt[0][1] = s[peak - a];
                    tt[2][1] = s[peak - b];
                    tt[1][2] = c;
                } else if (intrpB == corrMax) {
                    lags1[k] -= 0.5;
                    lags2[k] -= 0.5;
                    tt[0][0] = interpolate(peak - (PITCH_LAG_SPAN2 + 6));
                    tt[2][0] = intrpA;
                    tt[1][1] = intrpB;
                    tt[0][2] = interpolate(peak + (PITCH_LAG_SPAN2 + 3));
                    tt[2][2] = intrpD;
                    tt[1][0] = s[peak - a];
                    tt[0][1] = s[peak - 1];
                    tt[2][1] = c;
                    tt[1][2] = s[peak + b];
                } else if (intrpC == corrMax) {
                    lags1[k] += 0.5;
                    lags2[k] += 0.5;
                    tt[0][0] = intrpA;
                    tt[2][0] = interpolate(peak - b);
                    tt[1][1] = intrpC;
                    tt[0][2] = intrpD;
                    tt[2][2] = interpolate(peak + a);
                    tt[1][0] = s[peak - b];
                    tt[0][1] = c;
                    tt[2][1] = s[peak + 1];
                    tt[1][2] = s[peak + a];
                } else {
                    lags1[k] += 0.5;
                    lags2[k] -= 0.5;
                    tt[0][0] = intrpB;
                    tt[2][0] = intrpC;
                    tt[1][1] = intrpD;
                    tt[0][2] = interpolate(peak + 2 * b);
                    tt[2][2] = interpolate(peak + (2 * PITCH_LAG_SPAN2 + 9));
                    tt[1][0] = c;
                    tt[0][1] = s[peak + b];
                    tt[2][1] = s[peak + a];
                    tt[1][2] = s[peak + (2 * PITCH_LAG_SPAN2 + 9)];
                }
                point[0] = lags1[k];
                point[1] = lags2[k];
                interpolate2d(tt, point);
                lags1[k] = point[0];
                lags2[k] = point[1];
                peakVals[k] = point[2];
            }
            corrMax = 0.0;
            var peak = 0;
            for (var k = 0; k < peaksInd; k++) {
                var c = peakVals[k] * DoubleMath.pow(PITCH_PEAK_DECAY, DoubleMath.log(lags1[k] + lags2[k]));
                if (c > corrMax) {
                    corrMax = c;
                    peak = k;
                }
            }
            lags1[peak] *= 2.0;
            lags2[peak] *= 2.0;
            storeLags(lags, lags1[peak], lags2[peak]);
        } else {
            var row = maxInd / SURFACE_ROW;
            var l1 = (double) ((maxInd - row * SURFACE_ROW) + PITCH_MIN_LAG / 2 - 4);
            var l2 = (double) (l1 + PITCH_BW - row);
            storeLags(lags, l1, l2);
        }
    }

    private static void storeLags(double[] lags, double l1, double l2) {
        if (l1 < (double) PITCH_MIN_LAG) {
            l1 = PITCH_MIN_LAG;
        }
        if (l2 < (double) PITCH_MIN_LAG) {
            l2 = PITCH_MIN_LAG;
        }
        if (l1 > (double) PITCH_MAX_LAG) {
            l1 = PITCH_MAX_LAG;
        }
        if (l2 > (double) PITCH_MAX_LAG) {
            l2 = PITCH_MAX_LAG;
        }
        lags[0] = l1;
        lags[1] = l1;
        lags[2] = l2;
        lags[3] = l2;
    }

    private boolean isPeak(int index, double threshold) {
        var s = surface;
        var c = s[index];
        return c > threshold
                && c > s[index - (PITCH_LAG_SPAN2 + 5)] && c > s[index - (PITCH_LAG_SPAN2 + 4)]
                && c > s[index + (PITCH_LAG_SPAN2 + 4)] && c > s[index + (PITCH_LAG_SPAN2 + 5)];
    }

    private static void interpolate2d(double[][] t, double[] point) {
        var c = t[1][1];
        var b0 = 0.5 * (t[1][2] + t[2][1] - t[0][1] - t[1][0]);
        var b1 = 0.5 * (t[1][0] + t[2][1] - t[0][1] - t[1][2]);
        var a01 = -0.5 * (t[0][1] + t[2][1] - t[1][0] - t[1][2]);
        var t1 = 0.5 * (t[0][0] + t[2][2]) - c;
        var t2 = 0.5 * (t[2][0] + t[0][2]) - c;
        var d = (t[0][1] + t[1][2] + t[1][0] + t[2][1]) - 4.0 * c - t1 - t2;
        var a00 = -t1 - 0.5 * d;
        var a11 = -t2 - 0.5 * d;
        if ((a00 < 1e-7) || ((a00 * a11 - a01 * a01) < 1e-7)) {
            point[2] = t[1][1];
            return;
        }
        a00 = Math.sqrt(a00);
        a01 = a01 / a00;
        a11 = Math.sqrt(a11 - a01 * a01);
        t1 = b0 / a00;
        t2 = (b1 - t1 * a01) / a11;
        var delta2 = t2 / a11;
        var delta1 = 0.5 * (t1 - delta2 * a01) / a00;
        delta2 *= 0.5;
        t1 = delta1 * delta1 + delta2 * delta2;
        if (t1 > 1.0) {
            delta1 /= t1;
            delta2 /= t1;
        }
        point[2] = 0.5 * (b0 * delta1 + b1 * delta2) + c;
        point[0] += delta1;
        point[1] += delta2;
    }

    /**
     * Holds the per-frame working state of the pitch filter ({@code PitchFilterParam}).
     */
    private static final class Filter {
        final double[] buffer = new double[PITCH_INTBUFFSIZE + QLOOKAHEAD];
        final double[] damperState = new double[PITCH_DAMPORDER];
        double[] interpolCoeff;
        double gain;
        double lag;
        int lagOffset;
        int subFrame;
        boolean gainMode;
        int numSamples;
        int index;
        final double[][] damperStateDg = new double[4][PITCH_DAMPORDER];
        final double[] gainMult = new double[4];
    }

    private void filterFrame(double[] inData, double[] lags, double[] gains, boolean gainMode, double[] outData,
                             double[][] outDg) {
        var p = filter;
        p.index = 0;
        p.lagOffset = 0;
        p.gainMode = gainMode;
        System.arraycopy(ubuf, 0, p.buffer, 0, PITCH_BUFFSIZE);
        java.util.Arrays.fill(p.buffer, PITCH_BUFFSIZE, p.buffer.length, 0.0);
        System.arraycopy(ystate, 0, p.damperState, 0, PITCH_DAMPORDER);
        if (gainMode) {
            java.util.Arrays.fill(p.gainMult, 0.0);
            for (var row : p.damperStateDg) {
                java.util.Arrays.fill(row, 0.0);
            }
            for (var n = 0; n < PITCH_SUBFRAMES; n++) {
                java.util.Arrays.fill(outDg[n], 0.0);
            }
        }
        var previousLag = oldLag;
        var previousGain = oldGain;
        if ((lags[0] > (PITCH_UPSTEP * previousLag)) || (lags[0] < (PITCH_DOWNSTEP * previousLag))) {
            previousLag = lags[0];
            previousGain = gains[0];
            if (gainMode) {
                p.gainMult[0] = 1.0;
            }
        }
        p.numSamples = PITCH_UPDATE;
        for (var m = 0; m < PITCH_SUBFRAMES; m++) {
            p.subFrame = m;
            var lagDelta = (lags[m] - previousLag) / PITCH_GRAN_PER_SUBFRAME;
            p.lag = previousLag;
            var gainDelta = (gains[m] - previousGain) / PITCH_GRAN_PER_SUBFRAME;
            p.gain = previousGain;
            previousLag = lags[m];
            previousGain = gains[m];
            for (var n = 0; n < PITCH_GRAN_PER_SUBFRAME; n++) {
                p.gain += gainDelta;
                p.lag += lagDelta;
                update(p);
                filterSegment(inData, p, outData, outDg);
            }
        }
        if (!gainMode) {
            System.arraycopy(p.buffer, PITCH_FRAME_LEN, ubuf, 0, PITCH_BUFFSIZE);
            System.arraycopy(p.damperState, 0, ystate, 0, PITCH_DAMPORDER);
            oldLag = previousLag;
            oldGain = previousGain;
        } else {
            p.subFrame = PITCH_SUBFRAMES - 1;
            p.numSamples = QLOOKAHEAD;
            filterSegment(inData, p, outData, outDg);
        }
    }

    private static void update(Filter p) {
        p.lagOffset = (int) Math.rint(p.lag + PITCH_FILTDELAY + 0.5);
        var fraction = p.lagOffset - (p.lag + PITCH_FILTDELAY);
        var fractionIndex = (int) Math.rint(PITCH_FRACS * fraction - 0.5);
        p.interpolCoeff = INTRP_COEF[fractionIndex];
        if (p.gainMode) {
            p.gainMult[p.subFrame] += 0.2;
            if (p.gainMult[p.subFrame] > 1.0) {
                p.gainMult[p.subFrame] = 1.0;
            }
            if (p.subFrame > 0) {
                p.gainMult[p.subFrame - 1] -= 0.2;
            }
        }
    }

    private static void filterSegment(double[] inData, Filter p, double[] outData, double[][] outDg) {
        var pos = p.index + PITCH_BUFFSIZE;
        var posLag = pos - p.lagOffset;
        var coeff = p.interpolCoeff;
        for (var n = 0; n < p.numSamples; ++n) {
            for (var m = PITCH_DAMPORDER - 1; m > 0; --m) {
                p.damperState[m] = p.damperState[m - 1];
            }
            var sum = 0.0;
            for (var m = 0; m < PITCH_FRACORDER; ++m) {
                sum += p.buffer[posLag + m] * coeff[m];
            }
            p.damperState[0] = p.gain * sum;
            if (p.gainMode) {
                var lagIndex = p.index - p.lagOffset;
                var mTmp = lagIndex < 0 ? -lagIndex : 0;
                for (var m = PITCH_DAMPORDER - 1; m > 0; --m) {
                    for (var j = 0; j < 4; ++j) {
                        p.damperStateDg[j][m] = p.damperStateDg[j][m - 1];
                    }
                }
                for (var j = 0; j < p.subFrame + 1; ++j) {
                    var sum2 = 0.0;
                    for (var m = PITCH_FRACORDER - 1; m >= mTmp; --m) {
                        sum2 += outDg[j][lagIndex + m] * coeff[m];
                    }
                    p.damperStateDg[j][0] = p.gainMult[j] * sum + p.gain * sum2;
                }
                for (var j = 0; j < p.subFrame + 1; ++j) {
                    var s = 0.0;
                    for (var m = 0; m < PITCH_DAMPORDER; ++m) {
                        s -= p.damperStateDg[j][m] * DAMP_FILTER[m];
                    }
                    outDg[j][p.index] = s;
                }
            }
            sum = 0.0;
            for (var m = 0; m < PITCH_DAMPORDER; ++m) {
                sum += p.damperState[m] * DAMP_FILTER[m];
            }
            outData[p.index] = inData[p.index] - sum;
            p.buffer[pos] = inData[p.index] + outData[p.index];
            ++p.index;
            ++pos;
            ++posLag;
        }
    }
}
