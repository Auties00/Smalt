/* Ports opencore-amr 0.1.6 (lpc.cpp, autocorr.cpp, lag_wind.cpp, lag_wind_tab.cpp, levinson.cpp). Copyright
   PacketVideo; Apache License 2.0. Modified for Smalt. */
package com.github.auties00.smalt.audio.amr.internal.nb;

import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.abs;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.div32;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.fxpMac;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lAbs;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lNegate;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lShl;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.mpy32;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.normL;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.round;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.L_WINDOW;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.M;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.MP1;

/**
 * Computes the linear prediction coefficients of the AMR-NB encoder: windowed autocorrelation,
 * lag windowing and the Levinson-Durbin recursion, once per frame (twice in the 12.2 kbit/s mode,
 * with its two asymmetric windows).
 */
final class LpcAnalysis {
    /**
     * The high halves of the lag window, from {@code lag_wind_tab.cpp}.
     */
    private static final short[] LAG_H = {32728, 32619, 32438, 32187, 31867, 31480, 31029, 30517, 29946, 29321};

    /**
     * The low halves of the lag window, from {@code lag_wind_tab.cpp}.
     */
    private static final short[] LAG_L = {11904, 17280, 30720, 25856, 24192, 28992, 24384, 7360, 19520, 14784};

    /**
     * The last stable filter, used when the recursion meets an unstable one.
     */
    private final short[] oldA = new short[M + 1];

    /**
     * The windowed signal.
     */
    private final short[] y = new short[L_WINDOW];

    /**
     * The high halves of the autocorrelation.
     */
    private final short[] rHigh = new short[MP1];

    /**
     * The low halves of the autocorrelation.
     */
    private final short[] rLow = new short[MP1];

    /**
     * The first reflection coefficients.
     */
    private final short[] rc = new short[4];

    /**
     * The high halves of the current filter.
     */
    private final short[] ah = new short[M + 1];

    /**
     * The low halves of the current filter.
     */
    private final short[] al = new short[M + 1];

    /**
     * The high halves of the next filter.
     */
    private final short[] anh = new short[M + 1];

    /**
     * The low halves of the next filter.
     */
    private final short[] anl = new short[M + 1];

    /**
     * Creates the analysis in its reset state.
     */
    LpcAnalysis() {
        reset();
    }

    /**
     * Resets the memory of the last stable filter.
     */
    void reset() {
        oldA[0] = 4096;
        for (var i = 1; i < M + 1; i++) {
            oldA[i] = 0;
        }
    }

    /**
     * Computes the filters of a frame.
     *
     * @param mode the codec mode
     * @param x the speech buffer
     * @param xOffset the start of the 240-sample window of the modes below 12.2 kbit/s
     * @param x12k2Offset the start of the window of the 12.2 kbit/s mode
     * @param a receives the filters: the 4th subframe's at {@code 3 * MP1}, and in the
     * 12.2 kbit/s mode the 2nd subframe's at {@code MP1}
     * @param aOffset the first coefficient
     * @param overflow the saturation flag
     */
    void lpc(int mode, short[] x, int xOffset, int x12k2Offset, short[] a, int aOffset, Flag overflow) {
        if (mode == Mode.MR122) {
            autocorr(x, x12k2Offset, CommonTables.WINDOW_160_80);
            lagWindow(overflow);
            levinson(a, aOffset + MP1, overflow);
            autocorr(x, x12k2Offset, CommonTables.WINDOW_232_8);
            lagWindow(overflow);
            levinson(a, aOffset + MP1 * 3, overflow);
        } else {
            autocorr(x, xOffset, CommonTables.WINDOW_200_40);
            lagWindow(overflow);
            levinson(a, aOffset + MP1 * 3, overflow);
        }
    }

    /**
     * Computes the autocorrelation of the windowed signal into {@link #rHigh} and {@link #rLow}.
     *
     * @param x the signal
     * @param xOffset the first sample of the window
     * @param wind the window
     * @return the normalization shift
     */
    private short autocorr(short[] x, int xOffset, short[] wind) {
        for (var n = 0; n < L_WINDOW; n++) {
            y[n] = (short) (fxpMac(x[xOffset + n], wind[n], 0x04000) >> 15);
        }
        // the energy is the saturating sum of the doubled squares; while it saturates, the window is scaled by 1/4
        short overflowShift = 0;
        long energy;
        while (true) {
            energy = 0L;
            for (var n = 0; n < L_WINDOW; n++) {
                energy += y[n] * y[n];
            }
            if (energy << 1 < Integer.MAX_VALUE) {
                break;
            }
            overflowShift += 4;
            for (var n = 0; n < L_WINDOW; n++) {
                y[n] = (short) (y[n] >> 2);
            }
        }
        var sum = (int) (energy << 1);
        int i;
        sum += 1;
        var norm = normL(sum);
        sum <<= norm;
        rHigh[0] = (short) (sum >> 16);
        rLow[0] = (short) ((sum >> 1) - (rHigh[0] << 15));
        for (i = M; i > 0; i--) {
            sum = 0;
            var p = L_WINDOW - i - 1;
            var q = L_WINDOW - 1;
            for (var j = (L_WINDOW - i - 1) >> 1; j != 0; j--) {
                sum = fxpMac(y[p--], y[q--], sum);
                sum = fxpMac(y[p--], y[q--], sum);
            }
            sum = fxpMac(y[p--], y[q--], sum);
            if (((L_WINDOW - i - 1) & 1) != 0) {
                sum = fxpMac(y[p], y[q], sum);
            }
            sum <<= norm + 1;
            rHigh[i] = (short) (sum >> 16);
            rLow[i] = (short) ((sum >> 1) - (rHigh[i] << 15));
        }
        return (short) (norm - overflowShift);
    }

    /**
     * Applies the lag window to the autocorrelation.
     *
     * @param overflow the saturation flag
     */
    private void lagWindow(Flag overflow) {
        for (var i = 1; i <= M; i++) {
            var x = mpy32(rHigh[i], rLow[i], LAG_H[i - 1], LAG_L[i - 1]);
            rHigh[i] = (short) (x >> 16);
            rLow[i] = (short) ((x >> 1) - (rHigh[i] << 15));
        }
    }

    /**
     * Solves the normal equations for the filter with the Levinson-Durbin recursion, keeping the
     * previous filter when the new one is unstable.
     *
     * @param a receives the filter, Q12
     * @param aOffset the first coefficient
     * @param overflow the saturation flag
     */
    private void levinson(short[] a, int aOffset, Flag overflow) {
        var rh = rHigh;
        var rl = rLow;
        var t1 = rh[1] << 16;
        t1 += rl[1] << 1;
        var t2 = lAbs(t1);
        var t0 = div32(t2, rh[0], rl[0], overflow);
        if (t1 > 0) {
            t0 = lNegate(t0);
        }
        var kh = (short) (t0 >> 16);
        var kl = (short) ((t0 >> 1) - (kh << 15));
        rc[0] = round(t0, overflow);
        t0 = t0 >> 4;
        ah[1] = (short) (t0 >> 16);
        al[1] = (short) ((t0 >> 1) - (ah[1] << 15));
        t0 = mpy32(kh, kl, kh, kl);
        t0 = lAbs(t0);
        t0 = 0x7fffffff - t0;
        var hi = (short) (t0 >> 16);
        var lo = (short) ((t0 >> 1) - (hi << 15));
        t0 = mpy32(rh[0], rl[0], hi, lo);
        var alpExp = normL(t0);
        t0 = t0 << alpExp;
        var alpH = (short) (t0 >> 16);
        var alpL = (short) ((t0 >> 1) - (alpH << 15));
        for (var i = 2; i <= M; i++) {
            t0 = 0;
            for (var j = 1; j < i; j++) {
                t0 += (rh[j] * al[i - j]) >> 15;
                t0 += (rl[j] * ah[i - j]) >> 15;
                t0 += rh[j] * ah[i - j];
            }
            t0 = t0 << 5;
            t1 = (rh[i] << 16) + (rl[i] << 1);
            t0 += t1;
            t1 = lAbs(t0);
            t2 = div32(t1, alpH, alpL, overflow);
            if (t0 > 0) {
                t2 = lNegate(t2);
            }
            t2 = lShl(t2, alpExp);
            kh = (short) (t2 >> 16);
            kl = (short) ((t2 >> 1) - (kh << 15));
            if (i < 5) {
                rc[i - 1] = (short) ((t2 + 0x00008000) >> 16);
            }
            if (abs(kh) > 32750) {
                System.arraycopy(oldA, 0, a, aOffset, M + 1);
                java.util.Arrays.fill(rc, (short) 0);
                return;
            }
            for (var j = 1; j < i; j++) {
                t0 = (kh * al[i - j]) >> 15;
                t0 += (kl * ah[i - j]) >> 15;
                t0 += kh * ah[i - j];
                t0 += (ah[j] << 15) + al[j];
                anh[j] = (short) (t0 >> 15);
                anl[j] = (short) (t0 - (anh[j] << 15));
            }
            anh[i] = (short) (t2 >> 20);
            anl[i] = (short) ((t2 >> 5) - (anh[i] << 15));
            t0 = mpy32(kh, kl, kh, kl);
            t0 = lAbs(t0);
            t0 = 0x7fffffff - t0;
            hi = (short) (t0 >> 16);
            lo = (short) ((t0 >> 1) - (hi << 15));
            t0 = (alpH * lo) >> 15;
            t0 += (alpL * hi) >> 15;
            t0 += alpH * hi;
            t0 <<= 1;
            var shift = normL(t0);
            t0 <<= shift;
            alpH = (short) (t0 >> 16);
            alpL = (short) ((t0 >> 1) - (alpH << 15));
            alpExp += shift;
            System.arraycopy(anh, 1, ah, 1, i);
            System.arraycopy(anl, 1, al, 1, i);
        }
        a[aOffset] = 4096;
        for (var i = 1; i <= M; i++) {
            t0 = (ah[i] << 15) + al[i];
            var value = (short) ((t0 + 0x00002000) >> 14);
            oldA[i] = value;
            a[aOffset + i] = value;
        }
    }
}
