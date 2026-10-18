/* Ports vo-amrwbenc 0.1.3 (p_med_ol.c, pitch_f4.c, g_pitch.c). Copyright VisualOn; Apache License 2.0. Modified for
   Smalt. */
package com.github.auties00.smalt.audio.amr.internal.wbenc;

import com.github.auties00.smalt.audio.amr.internal.AmrKernel;
import com.github.auties00.smalt.audio.amr.internal.AmrKernels;
import com.github.auties00.smalt.audio.amr.internal.wbcommon.WbPitchOps;

import static com.github.auties00.smalt.audio.amr.internal.wbenc.WbBasicOps.*;

/**
 * Implements the pitch analysis of the AMR-WB encoder: the weighted open-loop pitch search with
 * its median smoothing, the closed-loop fractional pitch search, the interpolation of the
 * adaptive codebook and the computation of the pitch gain.
 *
 * <p>An instance holds the scratch buffers of the searches, so searching allocates nothing; an
 * instance is not thread-safe.
 *
 * @implNote This implementation computes with the saturating arithmetic of the 3GPP TS 26.173 fixed-point reference.
 */
final class WbPitch {
    /**
     * The filters and their scratch buffers.
     */
    private final WbFilters filters = new WbFilters();

    /**
     * The kernel that runs the integer filters.
     */
    private static final AmrKernel KERNEL = AmrKernels.INSTANCE;

    /**
     * The smallest lag of the open-loop search (decimated domain).
     */
    private static final int OL_MIN = 17;

    /**
     * The largest lag of the open-loop search (decimated domain).
     */
    private static final int OL_MAX = 115;

    /**
     * The smallest pitch lag at 12.8 kHz.
     */
    private static final int PIT_MIN = 34;

    /**
     * The half length of the correlation interpolation filter.
     */
    private static final int L_INTERPOL1 = 4;

    /**
     * The normalized correlations of the closed-loop search, indexed by lag minus the smallest
     * lag searched.
     */
    private final short[] correlations = new short[40];

    /**
     * The past excitation filtered by the weighted synthesis filter, updated recursively for
     * each lag.
     */
    private final short[] filteredExcitation = new short[64];

    /**
     * Creates the pitch analysis with its scratch buffers.
     */
    WbPitch() {
    }

    /**
     * Finds the open-loop pitch lag of a half frame or frame of decimated weighted speech, and
     * updates the open-loop gain of the encoder state ({@code Pitch_med_ol}).
     *
     * @param wsp the decimated weighted speech, with 115 samples of history before the offset
     * @param wspOff the offset of the speech
     * @param length the number of samples analyzed
     * @param state the encoder state: the median lag, the weighting flag, the high-pass filter
     * memory and history are read, the open-loop gain and history are updated
     * @return the open-loop lag in the decimated domain
     */
    static short pitchMedOl(short[] wsp, int wspOff, int length, WbEncoder state) {
        var corrWeight = WbTables.CORRELATION_WEIGHT;
        var l0 = state.oldT0Med;
        var weightFlag = state.olWeightFlag;
        var ww = 198;
        var we = 98 + OL_MAX - l0;
        var max = MIN_32;
        var tm = (short) 0;
        var sums = state.pitchSums;
        var wide = false;
        if (length <= 128) {
            var windowEnergy = 0L;
            var spanEnergy = 0L;
            var minimum = 0;
            for (var m = 0; m < length; m++) {
                var value = wsp[wspOff + m];
                windowEnergy += (long) value * value;
                minimum = Math.min(minimum, value);
            }
            for (var j = wspOff - OL_MAX; j < wspOff + length; j++) {
                var value = wsp[j];
                spanEnergy += (long) value * value;
                minimum = Math.min(minimum, value);
            }
            if (minimum != Short.MIN_VALUE && 2.0 * Math.sqrt((double) windowEnergy * (double) spanEnergy) <= 2.1e9) {
                var coefficients = state.pitchCoefficients;
                for (var m = 0; m < length; m++) {
                    coefficients[m] = 2 * wsp[wspOff + m];
                }
                KERNEL.fir(wsp, wspOff - OL_MAX, coefficients, length, OL_MAX - OL_MIN, sums, 0);
                wide = true;
            }
        }
        for (var i = OL_MAX; i > OL_MIN; i--) {
            var r0 = wide ? sums[OL_MAX - i] : macForward(0, wsp, wspOff, wsp, wspOff - i, length);
            r0 = mpy3216(hi(r0), lo(r0), corrWeight[ww]);
            ww--;
            if (l0 > 0 && weightFlag > 0) {
                r0 = mpy3216(hi(r0), lo(r0), corrWeight[we]);
                we--;
            }
            if (lSub(r0, max) >= 0) {
                max = r0;
                tm = (short) i;
            }
        }
        var oldHpWsp = state.oldHpWsp;
        var hp = OL_MAX;
        WbFilters.hpWsp(wsp, wspOff, oldHpWsp, hp, length, state.hpWspMem);
        var r0 = macForward(0, oldHpWsp, hp, oldHpWsp, hp - tm, length);
        var r1 = macForward(1, oldHpWsp, hp - tm, oldHpWsp, hp - tm, length);
        var r2 = macForward(1, oldHpWsp, hp, oldHpWsp, hp, length);
        var expR0 = normL(r0);
        r0 = lShl(r0, expR0);
        var expR1 = normL(r1);
        r1 = lShl(r1, expR1);
        var expR2 = normL(r2);
        r2 = lShl(r2, expR2);
        r1 = lMult(round(r1), round(r2));
        var i = normL(r1);
        r1 = lShl(r1, i);
        expR1 = add(expR1, expR2);
        expR1 = add(expR1, i);
        expR1 = sub(62, expR1);
        var packed = isqrtN(r1, expR1);
        r1 = value(packed);
        expR1 = exponent(packed);
        r0 = lMult(round(r0), round(r1));
        expR0 = sub(31, expR0);
        expR0 = add(expR0, expR1);
        state.olGain = round(lShl(r0, expR0));
        System.arraycopy(oldHpWsp, length, oldHpWsp, 0, OL_MAX);
        return tm;
    }

    /**
     * Pushes an open-loop lag into the history of the last five lags and returns their median
     * ({@code Med_olag}).
     *
     * @param prevOlLag the new lag
     * @param oldOlLag the 5-lag history, updated
     * @return the median lag
     */
    static short medOlag(short prevOlLag, short[] oldOlLag) {
        for (var i = 4; i > 0; i--) {
            oldOlLag[i] = oldOlLag[i - 1];
        }
        oldOlLag[0] = prevOlLag;
        return WbPitchOps.median5(oldOlLag, 2);
    }

    /**
     * Finds the closed-loop pitch lag with fractional resolution ({@code Pitch_fr4}).
     *
     * @param exc the excitation, with the past excitation before the offset
     * @param excOff the offset of the current subframe in the excitation
     * @param xn the target vector
     * @param h the impulse response of the weighted synthesis filter
     * @param t0Min the smallest lag searched
     * @param t0Max the largest lag searched
     * @param iSubfr {@code 0} when the lag is coded absolutely, else the subframe offset
     * @param t0Fr2 the lag below which quarter resolution is used
     * @param t0Fr1 the lag from which integer resolution is used
     * @return the integer lag in the high 16 bits and the fraction (0 to 3) in the low 16 bits
     */
    int pitchFr4(short[] exc, int excOff, short[] xn, short[] h, int t0Min, int t0Max, int iSubfr, int t0Fr2,
                 int t0Fr1) {
        var tMin = t0Min - L_INTERPOL1;
        var tMax = t0Max + L_INTERPOL1;
        var corr = correlations;
        normCorr(exc, excOff, xn, h, tMin, tMax);
        var max = corr[t0Min - tMin];
        var t0 = t0Min;
        for (var i = t0Min + 1; i <= t0Max; i++) {
            if (corr[i - tMin] >= max) {
                max = corr[i - tMin];
                t0 = i;
            }
        }
        if (iSubfr == 0 && t0 >= t0Fr1) {
            return t0 << 16;
        }
        var step = 1;
        var fraction = -3;
        if (t0Fr2 == PIT_MIN || (iSubfr == 0 && t0 >= t0Fr2)) {
            step = 2;
            fraction = -2;
        }
        if (t0 == t0Min) {
            fraction = 0;
        }
        var center = t0 - tMin;
        max = interpol4(corr, center, fraction);
        for (var i = fraction + step; i <= 3; i += step) {
            var temp = interpol4(corr, center, i);
            if (temp > max) {
                max = temp;
                fraction = i;
            }
        }
        if (fraction < 0) {
            fraction += 4;
            t0 -= 1;
        }
        return (t0 << 16) | fraction;
    }

    /**
     * Computes the normalized correlations between the target and the filtered past excitation
     * for every lag of a range into {@link #correlations} ({@code Norm_Corr}).
     *
     * @param exc the excitation
     * @param excOff the offset of the current subframe in the excitation
     * @param xn the target vector
     * @param h the impulse response of the weighted synthesis filter
     * @param tMin the smallest lag
     * @param tMax the largest lag
     */
    private void normCorr(short[] exc, int excOff, short[] xn, short[] h, int tMin, int tMax) {
        var excf = filteredExcitation;
        var corrNorm = correlations;
        var k = excOff - tMin;
        filters.convolve(exc, k, h, excf);
        var tmp = 1;
        for (var i = 0; i < 64; i++) {
            tmp = lMac(tmp, xn[i], xn[i]);
        }
        var exp = normL(tmp);
        exp = sub(30, exp);
        exp = add(exp, 2);
        var scale = negate(shr(exp, 1));
        for (var t = tMin; t <= tMax; t++) {
            tmp = macForward(1, xn, 0, excf, 0, 64);
            exp = normL(tmp);
            tmp = lShl(tmp, exp);
            var expCorr = sub(30, exp);
            var corr = extractH(tmp);
            tmp = macForward(1, excf, 0, excf, 0, 64);
            exp = normL(tmp);
            tmp = lShl(tmp, exp);
            var expNorm = sub(30, exp);
            var packed = isqrtN(tmp, expNorm);
            tmp = value(packed);
            expNorm = exponent(packed);
            var norm = extractH(tmp);
            tmp = lMult(corr, norm);
            tmp = lShl(tmp, add(add(expCorr, expNorm), scale));
            corrNorm[t - tMin] = round(tmp);
            if (t != tMax) {
                k--;
                for (var i = 63; i > 0; i--) {
                    excf[i] = add(mult(exc[k], h[i]), excf[i - 1]);
                }
                excf[0] = mult(exc[k], h[0]);
            }
        }
    }

    /**
     * Interpolates the normalized correlation at a fractional lag ({@code Interpol_4}).
     *
     * @param x the correlations
     * @param xOff the index of the integer lag
     * @param frac the fraction, -3 to 3
     * @return the interpolated correlation
     */
    private static short interpol4(short[] x, int xOff, int frac) {
        if (frac < 0) {
            frac += 4;
            xOff--;
        }
        xOff = xOff - L_INTERPOL1 + 1;
        var k = (3 - frac) * 8;
        var filter = WbTables.INTER4_1;
        var sum = 0;
        for (var i = 0; i < 8; i++) {
            sum = lMac(sum, x[xOff + i], filter[k + i]);
        }
        return round(lShl(sum, 1));
    }

    /**
     * Computes the pitch gain and the correlations the gain quantizer needs ({@code G_pitch}).
     *
     * @param xn the target vector
     * @param y1 the filtered adaptive codebook vector
     * @param gCoeff the 4 correlations: {@code <y1,y1>} and its exponent, {@code <xn,y1>} and
     * its exponent
     * @param length the vector length
     * @return the pitch gain in Q14, between 0 and 1.2
     */
    static short gPitch(short[] xn, short[] y1, short[] gCoeff, int length) {
        var packed = WbBasicOps.dotProduct12(xn, 0, y1, 0, length);
        var xy = extractH(WbBasicOps.value(packed));
        var expXy = WbBasicOps.exponent(packed);
        packed = WbBasicOps.dotProduct12(y1, 0, y1, 0, length);
        var yy = extractH(WbBasicOps.value(packed));
        var expYy = WbBasicOps.exponent(packed);
        gCoeff[0] = yy;
        gCoeff[1] = expYy;
        gCoeff[2] = xy;
        gCoeff[3] = expXy;
        if (xy < 0) {
            return 0;
        }
        xy >>= 1;
        var gain = divS(xy, yy);
        gain = shl(gain, (short) (expXy - expYy));
        if (gain > 19661) {
            gain = 19661;
        }
        return gain;
    }
}
