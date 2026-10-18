/* Ports opencore-amr 0.1.6 (ol_ltp.cpp, pitch_ol.cpp, p_ol_wgh.cpp, calc_cor.cpp, hp_max.cpp, corrwght_tab.cpp).
   Copyright PacketVideo; Apache License 2.0. Modified for Smalt. */
package com.github.auties00.smalt.audio.amr.internal.nb;

import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.MAX_16;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.MIN_16;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.divS;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.fxpMac;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lAbs;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lExtractHi;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lExtractLo;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lMac;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lMsu;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lShl;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lShr;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lSub;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.mpy32;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.mpy3216;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.negate;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.normL;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.round;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.shl;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.shr;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.sub;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.L_FRAME;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.L_FRAME_BY2;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.PIT_MAX;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.PIT_MIN;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.PIT_MIN_MR122;

/**
 * Estimates the open-loop pitch lag of the AMR-NB encoder from the weighted speech
 * ({@code ol_ltp}): once per frame in the 4.75 and 5.15 kbit/s modes, once per half frame
 * otherwise, with the lag-weighted search and the median-smoothed lag history of the 10.2 kbit/s
 * mode. With discontinuous transmission it also feeds the tone and complex-signal detectors of
 * {@link Vad1}.
 */
final class OpenLoopPitch {
    /**
     * The threshold favoring shorter lags, 0.85 in Q15.
     */
    private static final short THRESHOLD = 27853;

    /**
     * The lag weighting function of the 10.2 kbit/s search, from {@code corrwght_tab.cpp}.
     */
    private static final short[] CORR_WEIGHT = {
            20473, 20506, 20539, 20572, 20605, 20644, 20677, 20716, 20749, 20788, 20821, 20860, 20893, 20932, 20972,
            21011, 21050, 21089, 21129, 21168, 21207, 21247, 21286, 21332, 21371, 21417, 21456, 21502, 21542, 21588,
            21633, 21679, 21725, 21771, 21817, 21863, 21909, 21961, 22007, 22059, 22105, 22158, 22210, 22263, 22315,
            22367, 22420, 22472, 22531, 22584, 22643, 22702, 22761, 22820, 22879, 22938, 23003, 23062, 23128, 23193,
            23252, 23324, 23390, 23455, 23527, 23600, 23665, 23744, 23816, 23888, 23967, 24045, 24124, 24202, 24288,
            24366, 24451, 24537, 24628, 24714, 24805, 24904, 24995, 25094, 25192, 25297, 25395, 25500, 25611, 25723,
            25834, 25952, 26070, 26188, 26313, 26444, 26575, 26706, 26844, 26988, 27132, 27283, 27440, 27597, 27761,
            27931, 28108, 28285, 28475, 28665, 28869, 29078, 29295, 29524, 29760, 30002, 30258, 30527, 30808, 31457,
            32767, 32767, 32767, 32767, 32767, 32767, 32767, 31457, 30808, 30527, 30258, 30002, 29760, 29524, 29295,
            29078, 28869, 28665, 28475, 28285, 28108, 27931, 27761, 27597, 27440, 27283, 27132, 26988, 26844, 26706,
            26575, 26444, 26313, 26188, 26070, 25952, 25834, 25723, 25611, 25500, 25395, 25297, 25192, 25094, 24995,
            24904, 24805, 24714, 24628, 24537, 24451, 24366, 24288, 24202, 24124, 24045, 23967, 23888, 23816, 23744,
            23665, 23600, 23527, 23455, 23390, 23324, 23252, 23193, 23128, 23062, 23003, 22938, 22879, 22820, 22761,
            22702, 22643, 22584, 22531, 22472, 22420, 22367, 22315, 22263, 22210, 22158, 22105, 22059, 22007, 21961,
            21909, 21863, 21817, 21771, 21725, 21679, 21633, 21588, 21542, 21502, 21456, 21417, 21371, 21332, 21286,
            21247, 21207, 21168, 21129, 21089, 21050, 21011, 20972, 20932, 20893, 20860, 20821, 20788, 20749, 20716,
            20677, 20644, 20605, 20572, 20539, 20506, 20473, 20434, 20401, 20369, 20336
    };

    /**
     * The median-smoothed lag of the weighted search.
     */
    private short oldT0Med;

    /**
     * The adaptive weighting factor of the weighted search.
     */
    private short adaW;

    /**
     * Whether the weighted search weights around {@link #oldT0Med}.
     */
    private boolean wghtFlg;

    /**
     * The correlations for every lag; the correlation of lag {@code k} is at
     * {@code PIT_MAX - k}.
     */
    private final int[] corr = new int[PIT_MAX + 1];

    /**
     * The scaled weighted speech; the sample at {@code n} is at {@code PIT_MAX + n}.
     */
    private final short[] scaledSignal = new short[PIT_MAX + L_FRAME];

    /**
     * The correlation maxima of the three lag ranges.
     */
    private final short[] corMax = new short[1];

    /**
     * The open-loop gain flag of the weighted search.
     */
    private final short[] gainFlg = new short[1];

    /**
     * Creates the search in its reset state.
     */
    OpenLoopPitch() {
        reset();
    }

    /**
     * Resets the state of the weighted search.
     */
    void reset() {
        oldT0Med = 40;
        adaW = 0;
        wghtFlg = false;
    }

    /**
     * Estimates the open-loop lag of a frame or half frame ({@code ol_ltp}).
     *
     * @param vad the voice activity detector
     * @param mode the codec mode
     * @param wsp the weighted speech buffer, with {@link Constants#PIT_MAX} samples of history
     * @param wspOffset the first sample of the analyzed part
     * @param oldLags the lag history of the weighted search
     * @param olGainFlg the open-loop gain flags of the two half frames
     * @param idx the half frame index
     * @param dtx whether discontinuous transmission is on
     * @param overflow the saturation flag
     * @return the open-loop lag
     */
    short olLtp(Vad1 vad, int mode, short[] wsp, int wspOffset, short[] oldLags, short[] olGainFlg, int idx,
                boolean dtx, Flag overflow) {
        if (mode != Mode.MR102) {
            olGainFlg[0] = 0;
            olGainFlg[1] = 0;
        }
        if (mode == Mode.MR475 || mode == Mode.MR515) {
            return pitchOl(vad, mode, wsp, wspOffset, PIT_MIN, PIT_MAX, L_FRAME, idx, dtx, overflow);
        } else if (mode <= Mode.MR795) {
            return pitchOl(vad, mode, wsp, wspOffset, PIT_MIN, PIT_MAX, L_FRAME_BY2, idx, dtx, overflow);
        } else if (mode == Mode.MR102) {
            return pitchOlWgh(vad, wsp, wspOffset, PIT_MIN, PIT_MAX, L_FRAME_BY2, oldLags, olGainFlg, idx, dtx,
                    overflow);
        } else {
            return pitchOl(vad, mode, wsp, wspOffset, PIT_MIN_MR122, PIT_MAX, L_FRAME_BY2, idx, dtx, overflow);
        }
    }

    /**
     * Computes the correlation of the scaled signal with its delayed versions ({@code comp_corr}).
     *
     * @param lFrame the number of samples
     * @param lagMax the largest lag
     * @param lagMin the smallest lag
     */
    private void compCorr(int lFrame, int lagMax, int lagMin) {
        var base = PIT_MAX;
        var out = PIT_MAX - lagMax;
        for (var lag = lagMax; lag >= lagMax - 4 * (((lagMax - lagMin) >> 2) + 1) + 1; lag--) {
            var s = 0;
            for (var j = 0; j < lFrame; j++) {
                s = fxpMac(scaledSignal[base + j], scaledSignal[base + j - lag], s);
            }
            corr[out++] = s << 1;
        }
    }

    /**
     * Finds the lag of maximum correlation in a range and its normalized correlation
     * ({@code Lag_max} of {@code pitch_ol.cpp}).
     *
     * @param vad the voice activity detector
     * @param scalFac the scaling shift of the signal
     * @param scalFlag whether the normalized correlation is rescaled (12.2 kbit/s)
     * @param lFrame the number of samples
     * @param lagMax the largest lag
     * @param lagMin the smallest lag
     * @param dtx whether discontinuous transmission is on
     * @param overflow the saturation flag
     * @return the lag; the normalized correlation is left in {@link #corMax}
     */
    private short lagMax(Vad1 vad, short scalFac, boolean scalFlag, int lFrame, int lagMax, int lagMin, boolean dtx,
                         Flag overflow) {
        var max = Integer.MIN_VALUE;
        var pMax = lagMax;
        for (var i = lagMax; i >= lagMin; i--) {
            var value = corr[PIT_MAX - i];
            if (value >= max) {
                max = value;
                pMax = i;
            }
        }
        var t0 = 0;
        var p = PIT_MAX - pMax;
        for (var i = 0; i < lFrame; i++) {
            t0 = fxpMac(scaledSignal[p], scaledSignal[p], t0);
            p++;
        }
        t0 <<= 1;
        if (dtx) {
            vad.toneDetection(max, t0, overflow);
        }
        t0 = FixedMath.invSqrt(t0);
        if (scalFlag) {
            if (t0 > 0x3fffffff) {
                t0 = Integer.MAX_VALUE;
            } else {
                t0 = t0 << 1;
            }
        }
        var maxH = (short) (max >> 16);
        var maxL = (short) ((max >> 1) - (maxH << 15));
        var enerH = (short) (t0 >> 16);
        var enerL = (short) ((t0 >> 1) - (enerH << 15));
        t0 = mpy32(maxH, maxL, enerH, enerL);
        if (scalFlag) {
            t0 = lShr(t0, scalFac);
            if (t0 > 0x0000FFFF) {
                corMax[0] = MAX_16;
            } else if (t0 < 0xFFFF0000) {
                corMax[0] = MIN_16;
            } else {
                corMax[0] = (short) (t0 >> 1);
            }
        } else {
            corMax[0] = (short) t0;
        }
        return (short) pMax;
    }

    /**
     * Estimates the open-loop lag in three lag ranges, favoring the shorter ones
     * ({@code Pitch_ol}).
     *
     * @param vad the voice activity detector
     * @param mode the codec mode
     * @param signal the weighted speech buffer
     * @param offset the first analyzed sample
     * @param pitMin the smallest lag
     * @param pitMax the largest lag
     * @param lFrame the number of samples
     * @param idx the half frame index
     * @param dtx whether discontinuous transmission is on
     * @param overflow the saturation flag
     * @return the lag
     */
    private short pitchOl(Vad1 vad, int mode, short[] signal, int offset, int pitMin, int pitMax, int lFrame, int idx,
                          boolean dtx, Flag overflow) {
        if (dtx) {
            vad.toneDetectionUpdate(mode == Mode.MR475 || mode == Mode.MR515, overflow);
        }
        var t0 = 0;
        for (var i = -pitMax; i < lFrame; i++) {
            var x = signal[offset + i];
            t0 += (x * x) << 1;
            if (t0 < 0) {
                t0 = Integer.MAX_VALUE;
                break;
            }
        }
        var count = pitMax + lFrame;
        var src = offset - pitMax;
        var dst = PIT_MAX - pitMax;
        short scalFac;
        if (t0 == Integer.MAX_VALUE) {
            for (var i = 0; i < count; i++) {
                scaledSignal[dst + i] = (short) (signal[src + i] >> 3);
            }
            scalFac = 3;
        } else if (t0 < 1048576) {
            for (var i = 0; i < count; i++) {
                scaledSignal[dst + i] = (short) (signal[src + i] << 3);
            }
            scalFac = -3;
        } else {
            System.arraycopy(signal, src, scaledSignal, dst, count);
            scalFac = 0;
        }
        compCorr(lFrame, pitMax, pitMin);
        var scalFlag = mode == Mode.MR122;
        var lTemp = pitMin << 2;
        int j;
        if (lTemp != (short) lTemp) {
            overflow.value = true;
            j = pitMin > 0 ? MAX_16 : MIN_16;
        } else {
            j = lTemp;
        }
        var pMax1 = lagMax(vad, scalFac, scalFlag, lFrame, pitMax, j, dtx, overflow);
        var max1 = corMax[0];
        var i = j - 1;
        j = pitMin << 1;
        var pMax2 = lagMax(vad, scalFac, scalFlag, lFrame, i, j, dtx, overflow);
        var max2 = corMax[0];
        i = j - 1;
        var pMax3 = lagMax(vad, scalFac, scalFlag, lFrame, i, pitMin, dtx, overflow);
        var max3 = corMax[0];
        if (dtx && idx == 1) {
            vad.complexDetectionUpdate(hpMax(lFrame, pitMax, pitMin, overflow));
        }
        var threshold = (short) ((max1 * THRESHOLD) >> 15);
        if (threshold < max2) {
            max1 = max2;
            pMax1 = pMax2;
        }
        threshold = (short) ((max1 * THRESHOLD) >> 15);
        if (threshold < max3) {
            pMax1 = pMax3;
        }
        return pMax1;
    }

    /**
     * Finds the weighted maximum of the correlation and the open-loop gain flag
     * ({@code Lag_max} of {@code p_ol_wgh.cpp}).
     *
     * @param vad the voice activity detector
     * @param lFrame the number of samples
     * @param lagMax the largest lag
     * @param lagMin the smallest lag
     * @param oldLag the median-smoothed lag
     * @param dtx whether discontinuous transmission is on
     * @param overflow the saturation flag
     * @return the lag; the gain flag is left in {@link #gainFlg}
     */
    private short lagMaxWgh(Vad1 vad, int lFrame, int lagMax, int lagMin, short oldLag, boolean dtx, Flag overflow) {
        var ww = 250;
        var we = 123 + lagMax - oldLag;
        var max = Integer.MIN_VALUE;
        var pMax = lagMax;
        for (var i = lagMax; i >= lagMin; i--) {
            var value = corr[PIT_MAX - i];
            var t0 = mpy3216(lExtractHi(value), lExtractLo(value), CORR_WEIGHT[ww], overflow);
            ww--;
            if (wghtFlg) {
                t0 = mpy3216(lExtractHi(t0), lExtractLo(t0), CORR_WEIGHT[we], overflow);
                we--;
            }
            if (t0 >= max) {
                max = t0;
                pMax = i;
            }
        }
        var t0 = 0;
        var t1 = 0;
        var p = PIT_MAX;
        var p1 = PIT_MAX - pMax;
        for (var j = 0; j < lFrame; j++, p++, p1++) {
            t0 = lMac(t0, scaledSignal[p], scaledSignal[p1], overflow);
            t1 = lMac(t1, scaledSignal[p1], scaledSignal[p1], overflow);
        }
        if (dtx) {
            vad.toneDetectionUpdate(false, overflow);
            vad.toneDetection(t0, t1, overflow);
        }
        var temp = round(t1, overflow);
        t1 = lMsu(t0, temp, (short) 13107, overflow);
        gainFlg[0] = round(t1, overflow);
        return (short) pMax;
    }

    /**
     * Estimates the open-loop lag of the 10.2 kbit/s mode with the lag-weighted correlation
     * ({@code Pitch_ol_wgh}).
     *
     * @param vad the voice activity detector
     * @param signal the weighted speech buffer
     * @param offset the first analyzed sample
     * @param pitMin the smallest lag
     * @param pitMax the largest lag
     * @param lFrame the number of samples
     * @param oldLags the lag history
     * @param olGainFlg the open-loop gain flags of the two half frames
     * @param idx the half frame index
     * @param dtx whether discontinuous transmission is on
     * @param overflow the saturation flag
     * @return the lag
     */
    private short pitchOlWgh(Vad1 vad, short[] signal, int offset, int pitMin, int pitMax, int lFrame, short[] oldLags,
                             short[] olGainFlg, int idx, boolean dtx, Flag overflow) {
        var t0 = 0;
        for (var i = -pitMax; i < lFrame; i++) {
            var x = signal[offset + i];
            t0 = lMac(t0, x, x, overflow);
        }
        var base = PIT_MAX;
        if (lSub(t0, Integer.MAX_VALUE, overflow) == 0) {
            for (var i = -pitMax; i < lFrame; i++) {
                scaledSignal[base + i] = shr(signal[offset + i], (short) 3, overflow);
            }
        } else if (lSub(t0, 1048576, overflow) < 0) {
            for (var i = -pitMax; i < lFrame; i++) {
                scaledSignal[base + i] = shl(signal[offset + i], (short) 3);
            }
        } else {
            for (var i = -pitMax; i < lFrame; i++) {
                scaledSignal[base + i] = signal[offset + i];
            }
        }
        compCorr(lFrame, pitMax, pitMin);
        var pMax1 = lagMaxWgh(vad, lFrame, pitMax, pitMin, oldT0Med, dtx, overflow);
        olGainFlg[idx] = gainFlg[0];
        if (olGainFlg[idx] > 0) {
            for (var i = 4; i > 0; i--) {
                oldLags[i] = oldLags[i - 1];
            }
            oldLags[0] = pMax1;
            oldT0Med = FixedMath.gmedN(oldLags, 0, 5);
            adaW = 32767;
        } else {
            oldT0Med = pMax1;
            adaW = (short) ((adaW * 29491) >> 15);
        }
        wghtFlg = sub(adaW, (short) 9830, overflow) >= 0;
        if (dtx && sub((short) idx, (short) 1, overflow) == 0) {
            vad.complexDetectionUpdate(hpMax(lFrame, pitMax, pitMin, overflow));
        }
        return pMax1;
    }

    /**
     * Computes the maximum of the high-pass filtered correlation, normalized by the high-pass
     * filtered energy ({@code hp_max}).
     *
     * @param lFrame the number of samples
     * @param lagMax the largest lag
     * @param lagMin the smallest lag
     * @param overflow the saturation flag
     * @return the normalized maximum, Q15
     */
    private short hpMax(int lFrame, int lagMax, int lagMin, Flag overflow) {
        var max = Integer.MIN_VALUE;
        for (var i = lagMax - 1; i > lagMin; i--) {
            var t0 = lShl(corr[PIT_MAX - i], (short) 1);
            var lTemp = lSub(t0, corr[PIT_MAX - i - 1], overflow);
            t0 = lSub(lTemp, corr[PIT_MAX - i + 1], overflow);
            t0 = lAbs(t0);
            if (t0 >= max) {
                max = t0;
            }
        }
        var base = PIT_MAX;
        var t0 = 0;
        for (var i = 0; i < lFrame; i++) {
            t0 = lMac(t0, scaledSignal[base + i], scaledSignal[base + i], overflow);
        }
        var t1 = 0;
        for (var i = 0; i < lFrame; i++) {
            t1 = lMac(t1, scaledSignal[base + i], scaledSignal[base + i - 1], overflow);
        }
        var lTemp = lShl(t0, (short) 1);
        t1 = lShl(t1, (short) 1);
        t0 = lSub(lTemp, t1, overflow);
        t0 = lAbs(t0);
        var t016 = normL(max);
        var shift1 = (short) (t016 - 1);
        lTemp = lShl(max, shift1);
        var max16 = (short) (lTemp >> 16);
        var shift2 = normL(t0);
        lTemp = lShl(t0, shift2);
        t016 = (short) (lTemp >> 16);
        short corMaxValue;
        if (t016 != 0) {
            corMaxValue = divS(max16, t016);
        } else {
            corMaxValue = 0;
        }
        var shift = (short) (shift1 - shift2);
        if (shift >= 0) {
            return shr(corMaxValue, shift, overflow);
        }
        return shl(corMaxValue, negate(shift));
    }
}
