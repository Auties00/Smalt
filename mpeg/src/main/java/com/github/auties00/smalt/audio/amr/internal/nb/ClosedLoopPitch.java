/* Ports opencore-amr 0.1.6 (cl_ltp.cpp, pitch_fr.cpp, enc_lag3.cpp, enc_lag6.cpp, inter_36.cpp, inter_36_tab.cpp,
   g_pitch.cpp). Copyright PacketVideo; Apache License 2.0. Modified for Smalt. */
package com.github.auties00.smalt.audio.amr.internal.nb;

import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.MAX_16;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.divS;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.fxpMac;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.mpy32;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.normL;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.round;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.shr;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.GP_CLIP;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.L_FRAME_BY2;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.L_INTER_SRCH;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.L_SUBFR;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.PIT_MAX;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.PIT_MIN;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.PIT_MIN_MR122;

/**
 * Searches the adaptive codebook of the AMR-NB encoder ({@code cl_ltp}): the closed-loop
 * fractional pitch lag around the open-loop estimate or the previous subframe's lag, its index,
 * the adaptive codevector, its filtered version and the pitch gain.
 */
final class ClosedLoopPitch {

    /**
     * The scratch of the integer filters.
     */
    private final int[] work = new int[96];
    /**
     * The upsampling factor of the fractional interpolation.
     */
    private static final int UP_SAMP_MAX = 6;

    /**
     * The 1/6 resolution interpolation filter, from {@code inter_36_tab.cpp}.
     */
    private static final short[] INTER_6 = {
            29519, 28316, 24906, 19838, 13896, 7945, 2755, -1127, -3459, -4304, -3969, -2899, -1561, -336, 534, 970,
            1023, 823, 516, 220, 0, -131, -194, -215, 0
    };

    /**
     * The mode-dependent search parameters of {@code pitch_fr.cpp}, one row per mode:
     * the largest lag with a fractional part, whether the resolution is 1/3, the first and last
     * fraction, the integer search range low part and width, the delta search range low part and
     * width, and the smallest lag.
     */
    private static final short[][] MODE_DEP_PARM = {
            {84, 1, -2, 2, 5, 10, 5, 9, PIT_MIN},
            {84, 1, -2, 2, 5, 10, 5, 9, PIT_MIN},
            {84, 1, -2, 2, 3, 6, 5, 9, PIT_MIN},
            {84, 1, -2, 2, 3, 6, 5, 9, PIT_MIN},
            {84, 1, -2, 2, 3, 6, 5, 9, PIT_MIN},
            {84, 1, -2, 2, 3, 6, 10, 19, PIT_MIN},
            {84, 1, -2, 2, 3, 6, 5, 9, PIT_MIN},
            {94, 0, -3, 3, 3, 6, 5, 9, PIT_MIN_MR122}
    };

    /**
     * The integer lag of the previous subframe.
     */
    private short t0PrevSubframe;

    /**
     * The normalized correlations; lag {@code k} is at {@code k - tMin}.
     */
    private final short[] corrV = new short[40];

    /**
     * The past excitation filtered by the impulse response.
     */
    private final short[] excf = new short[L_SUBFR];

    /**
     * {@link #excf} scaled down by 2 bits.
     */
    private final short[] scaledExcf = new short[L_SUBFR];

    /**
     * The search results of the subframe: integer lag, fraction, whether the fraction is in
     * thirds, and the lag index.
     */
    private final short[] lag = new short[4];

    /**
     * Creates the search in its reset state.
     */
    ClosedLoopPitch() {
        reset();
    }

    /**
     * Resets the lag of the previous subframe.
     */
    void reset() {
        t0PrevSubframe = 0;
    }

    /**
     * Searches the adaptive codebook of a subframe and updates the targets ({@code cl_ltp}).
     *
     * @param tonStab the tone stability state
     * @param mode the codec mode
     * @param frameOffset the first sample of the subframe in the frame
     * @param tOp the open-loop lags of the two half frames
     * @param h1 the impulse response
     * @param h1Offset the first coefficient
     * @param exc the excitation buffer
     * @param excOffset the first excitation sample of the subframe
     * @param res2 the LP residual, updated to the long-term prediction residual
     * @param xn the target
     * @param lspFlag whether the LSPs indicate a resonance (gain clipping)
     * @param xn2 receives the target for the algebraic codebook
     * @param yl receives the filtered adaptive codevector
     * @param gCoeff receives the correlations of the pitch gain
     * @param ana receives the lag index (and the pitch gain index at 12.2 kbit/s)
     * @param anaOffset the next free parameter
     * @param result receives the integer lag, the fraction, the pitch gain and the gain limit
     * @param overflow the saturation flag
     * @return the next free parameter
     */
    int clLtp(ToneStability tonStab, int mode, int frameOffset, short[] tOp, short[] h1, int h1Offset, short[] exc,
              int excOffset, short[] res2, short[] xn, boolean lspFlag, short[] xn2, short[] yl, short[] gCoeff,
              short[] ana, int anaOffset, short[] result, Flag overflow) {
        pitchFr(mode, tOp, exc, excOffset, xn, h1, h1Offset, frameOffset, overflow);
        var t0 = lag[0];
        var t0Frac = lag[1];
        ana[anaOffset++] = lag[3];
        Filters.predLt3or6(exc, excOffset, t0, t0Frac, L_SUBFR, lag[2] != 0, work);
        CodebookCorrelation.convolve(exc, excOffset, h1, h1Offset, yl, 0, L_SUBFR);
        var gainPit = gPitch(mode, xn, yl, gCoeff, overflow);
        var gpcFlag = false;
        var gpLimit = MAX_16;
        if (lspFlag && gainPit > GP_CLIP) {
            gpcFlag = tonStab.checkGpClipping(gainPit, overflow);
        }
        if (mode == Mode.MR475 || mode == Mode.MR515) {
            gainPit = gainPit > 13926 ? 13926 : gainPit;
            if (gpcFlag) {
                gpLimit = GP_CLIP;
            }
        } else {
            if (gpcFlag) {
                gpLimit = GP_CLIP;
                gainPit = GP_CLIP;
            }
            if (mode == Mode.MR122) {
                result[GainQuantizer.GAIN_PIT] = gainPit;
                ana[anaOffset++] = GainQuantizer.qGainPitch(Mode.MR122, gpLimit, result, null, null, overflow);
                gainPit = result[GainQuantizer.GAIN_PIT];
            }
        }
        for (var i = 0; i < L_SUBFR; i++) {
            var lTemp = (yl[i] * gainPit) >> 14;
            xn2[i] = (short) (xn[i] - (short) lTemp);
            lTemp = (exc[excOffset + i] * gainPit) >> 14;
            res2[i] -= (short) lTemp;
        }
        result[0] = t0;
        result[1] = t0Frac;
        result[2] = gainPit;
        result[3] = gpLimit;
        return anaOffset;
    }

    /**
     * Computes the normalized correlation between the target and the filtered past excitation
     * for every lag of a range ({@code Norm_Corr}).
     *
     * @param exc the excitation buffer
     * @param excOffset the first excitation sample of the subframe
     * @param xn the target
     * @param h the impulse response
     * @param hOffset the first coefficient
     * @param tMin the smallest lag
     * @param tMax the largest lag
     * @param overflow the saturation flag
     */
    private void normCorr(short[] exc, int excOffset, short[] xn, short[] h, int hOffset, int tMin, int tMax,
                          Flag overflow) {
        var k = -tMin;
        CodebookCorrelation.convolve(exc, excOffset + k, h, hOffset, excf, 0, L_SUBFR);
        var s = 0;
        for (var j = 0; j < L_SUBFR; j++) {
            var temp = excf[j];
            scaledExcf[j] = (short) (temp >> 2);
            s += temp * temp;
        }
        short[] sExcf;
        int hFac;
        int scaling;
        if (s <= (67108864 >> 1)) {
            sExcf = excf;
            hFac = 12;
            scaling = 0;
        } else {
            sExcf = scaledExcf;
            hFac = 14;
            scaling = 2;
        }
        for (var i = tMin; i <= tMax; i++) {
            s = 0;
            var s2 = 0;
            for (var j = 0; j < L_SUBFR; j++) {
                s += xn[j] * sExcf[j];
                s2 += sExcf[j] * sExcf[j];
            }
            s2 = s2 << 1;
            s2 = FixedMath.invSqrt(s2);
            var normH = (short) (s2 >> 16);
            var normL = (short) ((s2 >> 1) - (normH << 15));
            var corrH = (short) (s >> 15);
            var corrL = (short) (s - (corrH << 15));
            s = mpy32(corrH, corrL, normH, normL);
            corrV[i - tMin] = (short) s;
            if (i != tMax) {
                k--;
                var temp = exc[excOffset + k];
                for (var j = L_SUBFR - 1; j > 0; j--) {
                    s = (temp * h[hOffset + j]) >> hFac;
                    sExcf[j] = (short) ((short) s + sExcf[j - 1]);
                }
                sExcf[0] = (short) (temp >> scaling);
            }
        }
    }

    /**
     * Interpolates the normalized correlation at a fractional lag ({@code Interpol_3or6}).
     *
     * @param index the index of the integer lag in {@link #corrV}
     * @param frac the fraction
     * @param flag3 whether the fraction is in thirds
     * @return the interpolated correlation
     */
    private short interpol3or6(int index, int frac, boolean flag3) {
        if (flag3) {
            frac <<= 1;
        }
        var x = index;
        if (frac < 0) {
            frac += UP_SAMP_MAX;
            x--;
        }
        var c2 = UP_SAMP_MAX - frac;
        var s = 0x04000;
        for (var m = 0; m < L_INTER_SRCH; m++) {
            s = fxpMac(corrV[x - m], INTER_6[frac + UP_SAMP_MAX * m], s);
            s = fxpMac(corrV[x + 1 + m], INTER_6[c2 + UP_SAMP_MAX * m], s);
        }
        return (short) (s >> 15);
    }

    /**
     * Refines the lag to the best fraction around it ({@code searchFrac}).
     *
     * @param tMin the smallest lag of {@link #corrV}
     * @param lastFrac the last fraction searched
     * @param flag3 whether the fraction is in thirds
     */
    private void searchFrac(int tMin, int lastFrac, boolean flag3) {
        var max = interpol3or6(lag[0] - tMin, lag[1], flag3);
        for (var i = lag[1] + 1; i <= lastFrac; i++) {
            var corrInt = interpol3or6(lag[0] - tMin, i, flag3);
            if (corrInt > max) {
                max = corrInt;
                lag[1] = (short) i;
            }
        }
        if (!flag3) {
            if (lag[1] == -3) {
                lag[1] = 3;
                lag[0]--;
            }
        } else {
            if (lag[1] == -2) {
                lag[1] = 1;
                lag[0]--;
            }
            if (lag[1] == 2) {
                lag[1] = -1;
                lag[0]++;
            }
        }
    }

    /**
     * Encodes a lag with 1/3 resolution ({@code Enc_lag3}).
     *
     * @param t0 the integer lag
     * @param t0Frac the fraction
     * @param t0Prev the integer lag of the previous subframe
     * @param t0Min the smallest lag of the search range
     * @param t0Max the largest lag of the search range
     * @param deltaFlag whether the lag is coded relative to the previous one
     * @param flag4 whether the relative coding uses 4 bits
     * @return the index
     */
    private static short encLag3(int t0, int t0Frac, int t0Prev, int t0Min, int t0Max, boolean deltaFlag,
                                 boolean flag4) {
        int index;
        if (!deltaFlag) {
            if (t0 - 85 <= 0) {
                index = (t0 << 1) + t0 - 58 + t0Frac;
            } else {
                index = t0 + 112;
            }
        } else if (!flag4) {
            var i = t0 - t0Min;
            index = i + (i << 1) + 2 + t0Frac;
        } else {
            var tmpLag = t0Prev;
            if (tmpLag - t0Min - 5 > 0) {
                tmpLag = t0Min + 5;
            }
            if (t0Max - tmpLag - 4 > 0) {
                tmpLag = t0Max - 4;
            }
            var uplag = t0 + (t0 << 1);
            uplag += t0Frac;
            var i = tmpLag - 2;
            var tmpInd = i + (i << 1);
            if (tmpInd - uplag >= 0) {
                index = t0 - tmpLag + 5;
            } else {
                i = tmpLag + 1;
                i += i << 1;
                if (i > uplag) {
                    index = uplag - tmpInd + 3;
                } else {
                    index = t0 - tmpLag + 11;
                }
            }
        }
        return (short) index;
    }

    /**
     * Encodes a lag with 1/6 resolution ({@code Enc_lag6}).
     *
     * @param t0 the integer lag
     * @param t0Frac the fraction
     * @param t0Min the smallest lag of the search range
     * @param deltaFlag whether the lag is coded relative to the previous one
     * @return the index
     */
    private static short encLag6(int t0, int t0Frac, int t0Min, boolean deltaFlag) {
        int index;
        if (!deltaFlag) {
            if (t0 <= 94) {
                var i = (t0 << 3) - (t0 << 1) - 105;
                index = i + t0Frac;
            } else {
                index = t0 + 368;
            }
        } else {
            var temp = t0 - t0Min;
            var i = (temp << 3) - (temp << 1);
            i += 3;
            index = i + t0Frac;
        }
        return (short) index;
    }

    /**
     * Searches the fractional closed-loop lag of a subframe ({@code Pitch_fr}) into {@link #lag}.
     *
     * @param mode the codec mode
     * @param tOp the open-loop lags
     * @param exc the excitation buffer
     * @param excOffset the first excitation sample of the subframe
     * @param xn the target
     * @param h the impulse response
     * @param hOffset the first coefficient
     * @param iSubfr the first sample of the subframe in the frame
     * @param overflow the saturation flag
     */
    private void pitchFr(int mode, short[] tOp, short[] exc, int excOffset, short[] xn, short[] h, int hOffset,
                         int iSubfr, Flag overflow) {
        var parm = MODE_DEP_PARM[mode];
        var maxFracLag = parm[0];
        var flag3 = parm[1] != 0;
        var frac = parm[2];
        int lastFrac = parm[3];
        var deltaIntLow = parm[4];
        var deltaIntRange = parm[5];
        var deltaFrcLow = parm[6];
        var deltaFrcRange = parm[7];
        var pitMin = parm[8];
        var deltaSearch = true;
        int range;
        if (iSubfr == 0 || iSubfr == L_FRAME_BY2) {
            if ((mode != Mode.MR475 && mode != Mode.MR515) || iSubfr != L_FRAME_BY2) {
                deltaSearch = false;
                range = getRange(tOp[iSubfr == 0 ? 0 : 1], deltaIntLow, deltaIntRange, pitMin);
            } else {
                range = getRange(t0PrevSubframe, deltaFrcLow, deltaFrcRange, pitMin);
            }
        } else {
            range = getRange(t0PrevSubframe, deltaFrcLow, deltaFrcRange, pitMin);
        }
        var t0Min = (short) (range >> 16);
        var t0Max = (short) range;
        var tMin = t0Min - L_INTER_SRCH;
        var tMax = t0Max + L_INTER_SRCH;
        normCorr(exc, excOffset, xn, h, hOffset, tMin, tMax, overflow);
        var max = corrV[t0Min - tMin];
        lag[0] = t0Min;
        for (var i = t0Min + 1; i <= t0Max; i++) {
            if (corrV[i - tMin] >= max) {
                max = corrV[i - tMin];
                lag[0] = (short) i;
            }
        }
        lag[1] = frac;
        if (!deltaSearch && lag[0] > maxFracLag) {
            lag[1] = 0;
        } else if (deltaSearch && (mode == Mode.MR475 || mode == Mode.MR515 || mode == Mode.MR59 || mode == Mode.MR67)) {
            int tmpLag = t0PrevSubframe;
            if (tmpLag - t0Min > 5) {
                tmpLag = t0Min + 5;
            }
            if (t0Max - tmpLag > 4) {
                tmpLag = t0Max - 4;
            }
            if (lag[0] == tmpLag || lag[0] == tmpLag - 1) {
                searchFrac(tMin, lastFrac, flag3);
            } else if (lag[0] == tmpLag - 2) {
                lag[1] = 0;
                searchFrac(tMin, lastFrac, flag3);
            } else if (lag[0] == tmpLag + 1) {
                lastFrac = 0;
                searchFrac(tMin, lastFrac, flag3);
            } else {
                lag[1] = 0;
            }
        } else {
            searchFrac(tMin, lastFrac, flag3);
        }
        if (flag3) {
            var flag4 = mode == Mode.MR475 || mode == Mode.MR515 || mode == Mode.MR59 || mode == Mode.MR67;
            lag[3] = encLag3(lag[0], lag[1], t0PrevSubframe, t0Min, t0Max, deltaSearch, flag4);
        } else {
            lag[3] = encLag6(lag[0], lag[1], t0Min, deltaSearch);
        }
        t0PrevSubframe = lag[0];
        lag[2] = (short) (flag3 ? 1 : 0);
    }

    /**
     * Computes the lag search range around a lag ({@code getRange}).
     *
     * @param t0 the center lag
     * @param deltaLow the extent below it
     * @param deltaRange the width of the range
     * @param pitMin the smallest lag
     * @return the smallest lag in the high half and the largest in the low half
     */
    private static int getRange(int t0, int deltaLow, int deltaRange, int pitMin) {
        var temp = (short) (t0 - deltaLow);
        if (temp < pitMin) {
            temp = (short) pitMin;
        }
        var t0Min = temp;
        temp = (short) (temp + deltaRange);
        if (temp > PIT_MAX) {
            temp = PIT_MAX;
            t0Min = (short) (PIT_MAX - deltaRange);
        }
        return (t0Min << 16) | (temp & 0xffff);
    }

    /**
     * Computes the pitch gain and the correlations its quantizers use ({@code G_pitch}).
     *
     * @param mode the codec mode
     * @param xn the target
     * @param y1 the filtered adaptive codevector
     * @param gCoeff receives the energy and correlation with their exponents
     * @param overflow the saturation flag, cleared and then set by the rounding
     * @return the pitch gain, Q14
     */
    private static short gPitch(int mode, short[] xn, short[] y1, short[] gCoeff, Flag overflow) {
        overflow.value = false;
        var s = 0;
        for (var i = 0; i < L_SUBFR; i++) {
            s = fxpMac(y1[i], y1[i], s);
        }
        short yy;
        short expYy;
        if (s >= 0 && s < 0x40000000) {
            s <<= 1;
            s += 1;
            expYy = normL(s);
            yy = round(s << expYy, overflow);
        } else {
            s = 0;
            for (var i = 0; i < L_SUBFR; i++) {
                var tmp = (short) (y1[i] >> 2);
                s = fxpMac(tmp, tmp, s);
            }
            s <<= 1;
            s += 1;
            expYy = normL(s);
            yy = round(s << expYy, overflow);
            expYy = (short) (expYy - 4);
        }
        s = 0;
        overflow.value = false;
        for (var i = 0; i < L_SUBFR; i++) {
            var lTemp = xn[i] * y1[i];
            var s1 = s;
            s = s1 + lTemp;
            if ((s1 ^ lTemp) > 0 && (s1 ^ s) < 0) {
                overflow.value = true;
                break;
            }
        }
        short xy;
        short expXy;
        if (!overflow.value) {
            s <<= 1;
            s += 1;
            expXy = normL(s);
            xy = round(s << expXy, overflow);
        } else {
            s = 0;
            for (var i = 0; i < L_SUBFR; i++) {
                s = fxpMac(xn[i], y1[i] >> 2, s);
            }
            s <<= 1;
            s += 1;
            expXy = normL(s);
            xy = round(s << expXy, overflow);
            expXy = (short) (expXy - 4);
        }
        gCoeff[0] = yy;
        gCoeff[1] = (short) (15 - expYy);
        gCoeff[2] = xy;
        gCoeff[3] = (short) (15 - expXy);
        if (xy < 4) {
            return 0;
        }
        xy = (short) (xy >> 1);
        var gain = divS(xy, yy);
        var i = (short) (expXy - expYy);
        gain = shr(gain, i, overflow);
        if (gain > 19661) {
            gain = 19661;
        }
        if (mode == Mode.MR122) {
            gain = (short) (gain & 0xfffC);
        }
        return gain;
    }
}
