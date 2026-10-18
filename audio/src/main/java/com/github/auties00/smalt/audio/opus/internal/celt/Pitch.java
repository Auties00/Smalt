/* Ports libopus 1.5.2 (celt/pitch.h, celt/pitch.c). Copyright Xiph.Org Foundation, CSIRO and Jean-Marc Valin; BSD
   3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.celt;

/**
 * Computes the correlations and pitch analyses shared by the CELT encoder, the CELT packet-loss
 * concealment, the SILK float encoder and the Opus tonality analysis.
 */
public final class Pitch {
    /**
     * The secondary period multiples checked by {@link #removeDoubling} ({@code second_check}).
     */
    private static final int[] SECOND_CHECK = {0, 0, 3, 2, 3, 2, 5, 2, 3, 2, 3, 2, 5, 2, 3, 2};

    private Pitch() {
        throw new AssertionError();
    }

    /**
     * Returns the inner product of two vectors accumulated in increasing index order
     * ({@code celt_inner_prod}).
     *
     * @param x    the first vector
     * @param xOff the index of the first element of {@code x}
     * @param y    the second vector
     * @param yOff the index of the first element of {@code y}
     * @param n    the number of elements
     * @return the inner product
     */
    public static float celtInnerProd(float[] x, int xOff, float[] y, int yOff, int n) {
        var xy = 0f;
        for (var i = 0; i < n; i++) {
            xy = xy + x[xOff + i] * y[yOff + i];
        }
        return xy;
    }

    /**
     * Computes two inner products sharing their first vector ({@code dual_inner_prod}).
     *
     * @param x      the shared vector
     * @param xOff   the index of the first element of {@code x}
     * @param y01    the second vector of the first product
     * @param y01Off the index of the first element of {@code y01}
     * @param y02    the second vector of the second product
     * @param y02Off the index of the first element of {@code y02}
     * @param n      the number of elements
     * @param out    receives the first product at index 0 and the second at index 1
     */
    public static void dualInnerProd(float[] x, int xOff, float[] y01, int y01Off, float[] y02, int y02Off, int n,
                                     float[] out) {
        var xy01 = 0f;
        var xy02 = 0f;
        for (var i = 0; i < n; i++) {
            xy01 = xy01 + x[xOff + i] * y01[y01Off + i];
            xy02 = xy02 + x[xOff + i] * y02[y02Off + i];
        }
        out[0] = xy01;
        out[1] = xy02;
    }

    /**
     * Computes the cross-correlation of {@code x} with {@code maxPitch} shifted copies of
     * {@code y} ({@code celt_pitch_xcorr}).
     *
     * @param x        the reference signal
     * @param xOff     the index of the first sample of {@code x}
     * @param y        the shifted signal, holding at least {@code len + maxPitch - 1} samples
     * @param yOff     the index of the first sample of {@code y}
     * @param xcorr    receives the correlations
     * @param xcorrOff the index of the first correlation
     * @param len      the correlation length
     * @param maxPitch the number of lags
     * @see CeltKernels#pitchXcorr(float[], int, float[], int, float[], int, int, int)
     */
    public static void celtPitchXcorr(float[] x, int xOff, float[] y, int yOff, float[] xcorr, int xcorrOff,
                                      int len, int maxPitch) {
        CeltKernels.INSTANCE.pitchXcorr(x, xOff, y, yOff, xcorr, xcorrOff, len, maxPitch);
    }

    /**
     * Keeps the two lags with the highest normalized correlation ({@code find_best_pitch}).
     *
     * @param xcorr     the correlation of each lag
     * @param y         the signal the lags index into
     * @param yOff      the index of the first sample of {@code y}
     * @param len       the correlation length
     * @param maxPitch  the number of lags
     * @param bestPitch receives the best and second best lags
     */
    private static void findBestPitch(float[] xcorr, float[] y, int yOff, int len, int maxPitch, int[] bestPitch) {
        var Syy = 1f;
        var bestNum0 = -1f;
        var bestNum1 = -1f;
        var bestDen0 = 0f;
        var bestDen1 = 0f;
        bestPitch[0] = 0;
        bestPitch[1] = 1;
        for (var j = 0; j < len; j++) {
            Syy = Syy + y[yOff + j] * y[yOff + j];
        }
        for (var i = 0; i < maxPitch; i++) {
            if (xcorr[i] > 0) {
                var xcorr16 = xcorr[i];
                xcorr16 *= 1e-12f;
                var num = xcorr16 * xcorr16;
                if (num * bestDen1 > bestNum1 * Syy) {
                    if (num * bestDen0 > bestNum0 * Syy) {
                        bestNum1 = bestNum0;
                        bestDen1 = bestDen0;
                        bestPitch[1] = bestPitch[0];
                        bestNum0 = num;
                        bestDen0 = Syy;
                        bestPitch[0] = i;
                    } else {
                        bestNum1 = num;
                        bestDen1 = Syy;
                        bestPitch[1] = i;
                    }
                }
            }
            Syy += y[yOff + i + len] * y[yOff + i + len] - y[yOff + i] * y[yOff + i];
            Syy = MathOps.max(1, Syy);
        }
    }

    /**
     * Filters a signal in place with a five-tap FIR ({@code celt_fir5}).
     *
     * @param x    the signal
     * @param xOff the index of the first sample
     * @param num  the five coefficients
     * @param N    the number of samples
     */
    private static void celtFir5(float[] x, int xOff, float[] num, int N) {
        var num0 = num[0];
        var num1 = num[1];
        var num2 = num[2];
        var num3 = num[3];
        var num4 = num[4];
        var mem0 = 0f;
        var mem1 = 0f;
        var mem2 = 0f;
        var mem3 = 0f;
        var mem4 = 0f;
        for (var i = 0; i < N; i++) {
            var sum = x[xOff + i];
            sum = sum + num0 * mem0;
            sum = sum + num1 * mem1;
            sum = sum + num2 * mem2;
            sum = sum + num3 * mem3;
            sum = sum + num4 * mem4;
            mem4 = mem3;
            mem3 = mem2;
            mem2 = mem1;
            mem1 = mem0;
            mem0 = x[xOff + i];
            x[xOff + i] = sum;
        }
    }

    /**
     * Downsamples one or two channels by two, mixes them and whitens the result with a
     * fourth-order LPC plus a zero ({@code pitch_downsample}).
     *
     * @param x      the signal of both channels
     * @param x0Off  the index of the first sample of the first channel
     * @param x1Off  the index of the first sample of the second channel, ignored when
     *               {@code C == 1}
     * @param xLp    receives {@code len / 2} samples
     * @param len    the number of input samples per channel
     * @param C      the number of channels
     * @param scratch the pitch scratch space
     */
    public static void pitchDownsample(float[] x, int x0Off, int x1Off, float[] xLp, int len, int C, Scratch scratch) {
        var ac = scratch.ac;
        var tmp = 1.0f;
        var lpc = scratch.lpc;
        var lpc2 = scratch.lpc2;
        var c1 = .8f;
        for (var i = 1; i < len >> 1; i++) {
            xLp[i] = .25f * x[x0Off + (2 * i - 1)] + .25f * x[x0Off + (2 * i + 1)] + .5f * x[x0Off + 2 * i];
        }
        xLp[0] = .25f * x[x0Off + 1] + .5f * x[x0Off];
        if (C == 2) {
            for (var i = 1; i < len >> 1; i++) {
                xLp[i] += .25f * x[x1Off + (2 * i - 1)] + .25f * x[x1Off + (2 * i + 1)] + .5f * x[x1Off + 2 * i];
            }
            xLp[0] += .25f * x[x1Off + 1] + .5f * x[x1Off];
        }
        CeltLpc.celtAutocorr(xLp, 0, ac, null, 0, 4, len >> 1);
        ac[0] *= 1.0001f;
        for (var i = 1; i <= 4; i++) {
            ac[i] -= ac[i] * (.008f * i) * (.008f * i);
        }
        CeltLpc.celtLpc(lpc, 0, ac, 4);
        for (var i = 0; i < 4; i++) {
            tmp = .9f * tmp;
            lpc[i] = lpc[i] * tmp;
        }
        lpc2[0] = lpc[0] + .8f;
        lpc2[1] = lpc[1] + c1 * lpc[0];
        lpc2[2] = lpc[2] + c1 * lpc[1];
        lpc2[3] = lpc[3] + c1 * lpc[2];
        lpc2[4] = c1 * lpc[3];
        celtFir5(xLp, 0, lpc2, len >> 1);
    }

    /**
     * Finds the pitch lag of a downsampled signal with a coarse search at 4x decimation, a finer
     * one at 2x, and a pseudo-interpolation ({@code pitch_search}).
     *
     * @param xLp      the downsampled current signal
     * @param xLpOff   the index of the first sample of {@code xLp}
     * @param y        the downsampled history
     * @param yOff     the index of the first sample of {@code y}
     * @param len      the length of the current signal, at the original rate
     * @param maxPitch the largest lag, at the original rate
     * @param scratch  the pitch scratch space
     * @return the pitch lag at the original rate
     */
    public static int pitchSearch(float[] xLp, int xLpOff, float[] y, int yOff, int len, int maxPitch,
                                  Scratch scratch) {
        int i, j;
        int lag;
        var bestPitch = scratch.bestPitch;
        bestPitch[0] = 0;
        bestPitch[1] = 0;
        var xLp4 = scratch.xLp4;
        var yLp4 = scratch.yLp4;
        var xcorr = scratch.xcorr;
        int offset;
        lag = len + maxPitch;
        for (j = 0; j < len >> 2; j++) {
            xLp4[j] = xLp[xLpOff + 2 * j];
        }
        for (j = 0; j < lag >> 2; j++) {
            yLp4[j] = y[yOff + 2 * j];
        }
        celtPitchXcorr(xLp4, 0, yLp4, 0, xcorr, 0, len >> 2, maxPitch >> 2);
        findBestPitch(xcorr, yLp4, 0, len >> 2, maxPitch >> 2, bestPitch);
        var half = maxPitch >> 1;
        i = 0;
        while (i < half) {
            if (Math.abs(i - 2 * bestPitch[0]) > 2 && Math.abs(i - 2 * bestPitch[1]) > 2) {
                xcorr[i] = 0;
                i++;
                continue;
            }
            // Contiguous lags go through the xcorr kernel, which sums each lag in the same order.
            j = i;
            while (j < half && !(Math.abs(j - 2 * bestPitch[0]) > 2 && Math.abs(j - 2 * bestPitch[1]) > 2)) {
                j++;
            }
            celtPitchXcorr(xLp, xLpOff, y, yOff + i, xcorr, i, len >> 1, j - i);
            for (var k = i; k < j; k++) {
                xcorr[k] = MathOps.max(-1, xcorr[k]);
            }
            i = j;
        }
        findBestPitch(xcorr, y, yOff, len >> 1, maxPitch >> 1, bestPitch);
        if (bestPitch[0] > 0 && bestPitch[0] < (maxPitch >> 1) - 1) {
            float a, b, c;
            a = xcorr[bestPitch[0] - 1];
            b = xcorr[bestPitch[0]];
            c = xcorr[bestPitch[0] + 1];
            if ((c - a) > .7f * (b - a)) {
                offset = 1;
            } else if ((a - c) > .7f * (b - c)) {
                offset = -1;
            } else {
                offset = 0;
            }
        } else {
            offset = 0;
        }
        return 2 * bestPitch[0] - offset;
    }

    /**
     * Returns the normalized correlation gain of a lag ({@code compute_pitch_gain}).
     *
     * @param xy the cross-correlation
     * @param xx the energy of the signal
     * @param yy the energy of the lagged signal
     * @return the gain
     */
    private static float computePitchGain(float xy, float xx, float yy) {
        return xy / MathOps.celtSqrt(1 + xx * yy);
    }

    /**
     * Refines a pitch lag by checking whether a submultiple explains the signal as well, which
     * avoids octave errors, and returns the pitch gain ({@code remove_doubling}).
     *
     * @param x           the downsampled signal
     * @param xOff        the index of its first sample
     * @param maxperiod   the largest period at the original rate
     * @param minperiod   the smallest period at the original rate
     * @param N           the analysis length at the original rate
     * @param T0          holds the pitch lag at index 0, in and out
     * @param prevPeriod  the period of the previous frame
     * @param prevGain    the gain of the previous frame
     * @param scratch     the pitch scratch space
     * @return the pitch gain
     */
    public static float removeDoubling(float[] x, int xOff, int maxperiod, int minperiod, int N, int[] T0,
                                       int prevPeriod, float prevGain, Scratch scratch) {
        int k, i, T, T0v;
        float g, g0;
        float pg;
        float xy, xx, yy, xy2;
        var xcorr = scratch.xcorr3;
        float bestXy, bestYy;
        int offset;
        int minperiod0;
        var yyLookup = scratch.yyLookup;
        var dual = scratch.dual;
        minperiod0 = minperiod;
        maxperiod /= 2;
        minperiod /= 2;
        T0[0] /= 2;
        prevPeriod /= 2;
        N /= 2;
        var xp = xOff + maxperiod;
        if (T0[0] >= maxperiod) {
            T0[0] = maxperiod - 1;
        }
        T = T0v = T0[0];
        dualInnerProd(x, xp, x, xp, x, xp - T0v, N, dual);
        xx = dual[0];
        xy = dual[1];
        yyLookup[0] = xx;
        yy = xx;
        for (i = 1; i <= maxperiod; i++) {
            yy = yy + x[xp - i] * x[xp - i] - x[xp + N - i] * x[xp + N - i];
            yyLookup[i] = MathOps.max(0, yy);
        }
        yy = yyLookup[T0v];
        bestXy = xy;
        bestYy = yy;
        g = g0 = computePitchGain(xy, xx, yy);
        for (k = 2; k <= 15; k++) {
            int T1, T1b;
            float g1;
            float cont;
            float thresh;
            T1 = Integer.divideUnsigned(2 * T0v + k, 2 * k);
            if (T1 < minperiod) {
                break;
            }
            if (k == 2) {
                if (T1 + T0v > maxperiod) {
                    T1b = T0v;
                } else {
                    T1b = T0v + T1;
                }
            } else {
                T1b = Integer.divideUnsigned(2 * SECOND_CHECK[k] * T0v + k, 2 * k);
            }
            dualInnerProd(x, xp, x, xp - T1, x, xp - T1b, N, dual);
            xy = dual[0];
            xy2 = dual[1];
            xy = .5f * (xy + xy2);
            yy = .5f * (yyLookup[T1] + yyLookup[T1b]);
            g1 = computePitchGain(xy, xx, yy);
            if (Math.abs(T1 - prevPeriod) <= 1) {
                cont = prevGain;
            } else if (Math.abs(T1 - prevPeriod) <= 2 && 5 * k * k < T0v) {
                cont = .5f * prevGain;
            } else {
                cont = 0;
            }
            thresh = MathOps.max(.3f, .7f * g0 - cont);
            if (T1 < 3 * minperiod) {
                thresh = MathOps.max(.4f, .85f * g0 - cont);
            } else if (T1 < 2 * minperiod) {
                thresh = MathOps.max(.5f, .9f * g0 - cont);
            }
            if (g1 > thresh) {
                bestXy = xy;
                bestYy = yy;
                T = T1;
                g = g1;
            }
        }
        bestXy = MathOps.max(0, bestXy);
        if (bestYy <= bestXy) {
            pg = 1.0f;
        } else {
            pg = bestXy / (bestYy + 1);
        }
        for (k = 0; k < 3; k++) {
            xcorr[k] = celtInnerProd(x, xp, x, xp - (T + k - 1), N);
        }
        if ((xcorr[2] - xcorr[0]) > .7f * (xcorr[1] - xcorr[0])) {
            offset = 1;
        } else if ((xcorr[0] - xcorr[2]) > .7f * (xcorr[1] - xcorr[2])) {
            offset = -1;
        } else {
            offset = 0;
        }
        if (pg > g) {
            pg = g;
        }
        T0[0] = 2 * T + offset;
        if (T0[0] < minperiod0) {
            T0[0] = minperiod0;
        }
        return pg;
    }

    /**
     * Holds the scratch space of the pitch analysis, sized for the CELT pre-filter and the
     * packet-loss concealment.
     */
    public static final class Scratch {
        /**
         * The autocorrelation of {@link #pitchDownsample}.
         */
        final float[] ac = new float[5];

        /**
         * The LPC of {@link #pitchDownsample}.
         */
        final float[] lpc = new float[4];

        /**
         * The whitening filter of {@link #pitchDownsample}.
         */
        final float[] lpc2 = new float[5];

        /**
         * The 4x-decimated current signal of {@link #pitchSearch}.
         */
        final float[] xLp4 = new float[1024];

        /**
         * The 4x-decimated history of {@link #pitchSearch}.
         */
        final float[] yLp4 = new float[1024];

        /**
         * The lag correlations of {@link #pitchSearch}.
         */
        final float[] xcorr = new float[1024];

        /**
         * The two best lags of {@link #pitchSearch}.
         */
        final int[] bestPitch = new int[2];

        /**
         * The energy of each lag of {@link #removeDoubling}.
         */
        final float[] yyLookup = new float[1025];

        /**
         * The three neighboring correlations of {@link #removeDoubling}.
         */
        final float[] xcorr3 = new float[3];

        /**
         * The output of {@link #dualInnerProd}.
         */
        final float[] dual = new float[2];

        /**
         * Creates the scratch space.
         */
        public Scratch() {
        }
    }
}
