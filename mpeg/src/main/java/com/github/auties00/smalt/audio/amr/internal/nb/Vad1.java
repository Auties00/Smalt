/* Ports opencore-amr 0.1.6 (vad1.cpp). Copyright PacketVideo; Apache License 2.0. Modified for Smalt. */
package com.github.auties00.smalt.audio.amr.internal.nb;

import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.abs;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.add;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.divS;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lAdd;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lMac;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lMsu;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lShl;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lSub;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.mult;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.multR;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.normS;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.round;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.shl;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.shr;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.sub;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.*;

/**
 * Detects voice activity in the AMR-NB encoder input with the TS 26.094 option 1 detector: a
 * nine-band filter bank, a background noise estimate, intermediate decisions on the sub-band
 * signal-to-noise ratio, pitch, tone and complex-signal detectors, and a hangover.
 *
 * <p>The pitch, tone and complex flags are fed by the open-loop pitch search through
 * {@link #toneDetection(int, int, Flag)}, {@link #toneDetectionUpdate(boolean, Flag)},
 * {@link #complexDetectionUpdate(short)} and {@link #pitchDetection(short[], Flag)}.
 */
final class Vad1 {
    /**
     * The background noise estimate of every band.
     */
    private final short[] bckrEst = new short[COMPLEN];

    /**
     * The averaged band levels used by the stationarity estimate.
     */
    private final short[] aveLevel = new short[COMPLEN];

    /**
     * The band levels of the previous frame.
     */
    private final short[] oldLevel = new short[COMPLEN];

    /**
     * The band levels of the look-ahead part of the previous frame.
     */
    private final short[] subLevel = new short[COMPLEN];

    /**
     * The memories of the three 5th-order filter bank stages, two values each.
     */
    private final short[][] aData5 = new short[3][2];

    /**
     * The memories of the five 3rd-order filter bank stages.
     */
    private final short[] aData3 = new short[5];

    /**
     * The length of the current speech burst.
     */
    private short burstCount;

    /**
     * The hangover counter.
     */
    private short hangCount;

    /**
     * The stationarity counter.
     */
    private short statCount;

    /**
     * The history of intermediate decisions, newest in bit 14.
     */
    private short vadreg;

    /**
     * The history of pitch detections, newest in bit 14.
     */
    private short pitch;

    /**
     * The history of tone detections, newest in bit 14.
     */
    private short tone;

    /**
     * The history of high complex-signal detections.
     */
    private short complexHigh;

    /**
     * The history of low complex-signal detections.
     */
    private short complexLow;

    /**
     * The number of consecutive similar lags of the previous frame.
     */
    private short oldlagCount;

    /**
     * The last open-loop lag.
     */
    private short oldlag;

    /**
     * The complex hangover counter.
     */
    private short complexHangCount;

    /**
     * The complex hangover initiator.
     */
    private short complexHangTimer;

    /**
     * The high-pass filtered correlation maximum of the last open-loop search, Q15.
     */
    private short bestCorrHp;

    /**
     * The final decision of the last frame.
     */
    private short speechVadDecision;

    /**
     * The complex background warning.
     */
    private short complexWarning;

    /**
     * The filtered complex correlation.
     */
    private short corrHpFast;

    /**
     * The filter bank output of the current frame.
     */
    private final short[] tmpBuf = new short[FRAME_LEN];

    /**
     * The band levels of the current frame.
     */
    private final short[] level = new short[COMPLEN];

    /**
     * Creates a detector in its reset state.
     */
    Vad1() {
        reset();
    }

    /**
     * Resets the detector.
     */
    void reset() {
        oldlagCount = 0;
        oldlag = 0;
        pitch = 0;
        tone = 0;
        complexHigh = 0;
        complexLow = 0;
        complexHangTimer = 0;
        vadreg = 0;
        statCount = 0;
        burstCount = 0;
        hangCount = 0;
        complexHangCount = 0;
        for (var stage : aData5) {
            stage[0] = 0;
            stage[1] = 0;
        }
        java.util.Arrays.fill(aData3, (short) 0);
        for (var i = 0; i < COMPLEN; i++) {
            bckrEst[i] = NOISE_INIT;
            oldLevel[i] = NOISE_INIT;
            aveLevel[i] = NOISE_INIT;
            subLevel[i] = 0;
        }
        bestCorrHp = CVAD_LOWPOW_RESET;
        speechVadDecision = 0;
        complexWarning = 0;
        corrHpFast = CVAD_LOWPOW_RESET;
    }

    /**
     * Runs the first 5th-order stage of the filter bank, splitting the input in two bands.
     *
     * @param in the input, {@link Constants#FRAME_LEN} samples
     * @param inOffset the first input sample
     * @param out the output, interleaved
     * @param data the stage memory
     * @param overflow the saturation flag
     */
    private static void firstFilterStage(short[] in, int inOffset, short[] out, short[] data, Flag overflow) {
        var data0 = data[0];
        var data1 = data[1];
        for (var i = 0; i < FRAME_LEN / 4; i++) {
            var temp0 = (short) ((COEFF5_1 * data0) >> 15);
            var temp1 = (short) (in[inOffset + 4 * i] >> 2);
            temp0 = sub(temp1, temp0, overflow);
            temp1 = (short) ((COEFF5_1 * temp0) >> 15);
            temp1 = add(data0, temp1, overflow);
            var temp3 = (short) ((COEFF5_2 * data1) >> 15);
            var temp2 = (short) (in[inOffset + 4 * i + 1] >> 2);
            temp3 = sub(temp2, temp3, overflow);
            temp2 = (short) ((COEFF5_2 * temp3) >> 15);
            temp2 = add(data1, temp2, overflow);
            out[4 * i] = add(temp1, temp2, overflow);
            out[4 * i + 1] = sub(temp1, temp2, overflow);
            temp1 = (short) ((COEFF5_1 * temp0) >> 15);
            temp2 = (short) (in[inOffset + 4 * i + 2] >> 2);
            data0 = sub(temp2, temp1, overflow);
            temp1 = (short) ((COEFF5_1 * data0) >> 15);
            temp1 = add(temp0, temp1, overflow);
            data1 = (short) ((COEFF5_2 * temp3) >> 15);
            temp2 = (short) (in[inOffset + 4 * i + 3] >> 2);
            data1 = sub(temp2, data1, overflow);
            temp2 = (short) ((COEFF5_2 * data1) >> 15);
            temp2 = add(temp3, temp2, overflow);
            out[4 * i + 2] = add(temp1, temp2, overflow);
            out[4 * i + 3] = sub(temp1, temp2, overflow);
        }
        data[0] = data0;
        data[1] = data1;
    }

    /**
     * Runs a 5th-order all-pass stage on two samples in place.
     *
     * @param buf the buffer
     * @param in0 the index of the first sample
     * @param in1 the index of the second sample
     * @param data the stage memory
     * @param overflow the saturation flag
     */
    private static void filter5(short[] buf, int in0, int in1, short[] data, Flag overflow) {
        var temp0 = mult(COEFF5_1, data[0], overflow);
        temp0 = sub(buf[in0], temp0, overflow);
        var temp1 = mult(COEFF5_1, temp0, overflow);
        temp1 = add(data[0], temp1, overflow);
        data[0] = temp0;
        temp0 = mult(COEFF5_2, data[1], overflow);
        temp0 = sub(buf[in1], temp0, overflow);
        var temp2 = mult(COEFF5_2, temp0, overflow);
        temp2 = add(data[1], temp2, overflow);
        data[1] = temp0;
        temp0 = add(temp1, temp2, overflow);
        buf[in0] = shr(temp0, (short) 1, overflow);
        temp0 = sub(temp1, temp2, overflow);
        buf[in1] = shr(temp0, (short) 1, overflow);
    }

    /**
     * Runs a 3rd-order all-pass stage on two samples in place.
     *
     * @param buf the buffer
     * @param in0 the index of the first sample
     * @param in1 the index of the second sample
     * @param index the index of the stage memory in {@link #aData3}
     * @param overflow the saturation flag
     */
    private void filter3(short[] buf, int in0, int in1, int index, Flag overflow) {
        var data = aData3[index];
        var temp1 = mult(COEFF3, data, overflow);
        temp1 = sub(buf[in1], temp1, overflow);
        var temp2 = mult(COEFF3, temp1, overflow);
        temp2 = add(data, temp2, overflow);
        aData3[index] = temp1;
        temp1 = sub(buf[in0], temp2, overflow);
        buf[in1] = shr(temp1, (short) 1, overflow);
        temp1 = add(buf[in0], temp2, overflow);
        buf[in0] = shr(temp1, (short) 1, overflow);
    }

    /**
     * Computes the level of one band over the frame and its look-ahead part.
     *
     * @param data the filter bank output
     * @param band the index of the band in {@link #subLevel}
     * @param count1 the first sample of the look-ahead part, in band samples
     * @param count2 the number of band samples
     * @param indM the band decimation step
     * @param indA the band offset
     * @param scale the scaling shift
     * @param overflow the saturation flag
     * @return the level
     */
    private short levelCalculation(short[] data, int band, int count1, int count2, int indM, int indA, int scale,
                                   Flag overflow) {
        var lTemp1 = 0;
        for (var i = count1; i < count2; i++) {
            lTemp1 = lMac(lTemp1, (short) 1, abs(data[indM * i + indA]), overflow);
        }
        var lTemp2 = lAdd(lTemp1, lShl(subLevel[band], sub((short) 16, (short) scale, overflow)), overflow);
        subLevel[band] = (short) (lShl(lTemp1, (short) scale) >> 16);
        for (var i = 0; i < count1; i++) {
            lTemp2 = lMac(lTemp2, (short) 1, abs(data[indM * i + indA]), overflow);
        }
        return (short) (lShl(lTemp2, (short) scale) >> 16);
    }

    /**
     * Splits the frame into nine bands and computes their levels.
     *
     * @param in the input
     * @param inOffset the first sample of the frame
     * @param overflow the saturation flag
     */
    private void filterBank(short[] in, int inOffset, Flag overflow) {
        firstFilterStage(in, inOffset, tmpBuf, aData5[0], overflow);
        for (var i = 0; i < FRAME_LEN / 4; i++) {
            filter5(tmpBuf, 4 * i, 4 * i + 2, aData5[1], overflow);
            filter5(tmpBuf, 4 * i + 1, 4 * i + 3, aData5[2], overflow);
        }
        for (var i = 0; i < FRAME_LEN / 8; i++) {
            filter3(tmpBuf, 8 * i, 8 * i + 4, 0, overflow);
            filter3(tmpBuf, 8 * i + 2, 8 * i + 6, 1, overflow);
            filter3(tmpBuf, 8 * i + 3, 8 * i + 7, 4, overflow);
        }
        for (var i = 0; i < FRAME_LEN / 16; i++) {
            filter3(tmpBuf, 16 * i, 16 * i + 8, 2, overflow);
            filter3(tmpBuf, 16 * i + 4, 16 * i + 12, 3, overflow);
        }
        level[8] = levelCalculation(tmpBuf, 8, FRAME_LEN / 4 - 8, FRAME_LEN / 4, 4, 1, 15, overflow);
        level[7] = levelCalculation(tmpBuf, 7, FRAME_LEN / 8 - 4, FRAME_LEN / 8, 8, 7, 16, overflow);
        level[6] = levelCalculation(tmpBuf, 6, FRAME_LEN / 8 - 4, FRAME_LEN / 8, 8, 3, 16, overflow);
        level[5] = levelCalculation(tmpBuf, 5, FRAME_LEN / 8 - 4, FRAME_LEN / 8, 8, 2, 16, overflow);
        level[4] = levelCalculation(tmpBuf, 4, FRAME_LEN / 8 - 4, FRAME_LEN / 8, 8, 6, 16, overflow);
        level[3] = levelCalculation(tmpBuf, 3, FRAME_LEN / 16 - 2, FRAME_LEN / 16, 16, 4, 16, overflow);
        level[2] = levelCalculation(tmpBuf, 2, FRAME_LEN / 16 - 2, FRAME_LEN / 16, 16, 12, 16, overflow);
        level[1] = levelCalculation(tmpBuf, 1, FRAME_LEN / 16 - 2, FRAME_LEN / 16, 16, 8, 16, overflow);
        level[0] = levelCalculation(tmpBuf, 0, FRAME_LEN / 16 - 2, FRAME_LEN / 16, 16, 0, 16, overflow);
    }

    /**
     * Updates the stationarity counter and the averaged band levels.
     *
     * @param overflow the saturation flag
     */
    private void updateCntrl(Flag overflow) {
        if (complexWarning != 0 && statCount < CAD_MIN_STAT_COUNT) {
            statCount = CAD_MIN_STAT_COUNT;
        }
        if ((short) (pitch & 0x6000) == 0x6000 || (short) (tone & 0x7c00) == 0x7c00) {
            statCount = STAT_COUNT;
        } else if ((vadreg & 0x7f80) == 0) {
            statCount = STAT_COUNT;
        } else {
            short statRat = 0;
            for (var i = 0; i < COMPLEN; i++) {
                short num;
                short denom;
                if (level[i] > aveLevel[i]) {
                    num = level[i];
                    denom = aveLevel[i];
                } else {
                    num = aveLevel[i];
                    denom = level[i];
                }
                if (num < STAT_THR_LEVEL) {
                    num = STAT_THR_LEVEL;
                }
                if (denom < STAT_THR_LEVEL) {
                    denom = STAT_THR_LEVEL;
                }
                var exp = normS(denom);
                denom = shl(denom, exp);
                var temp = shr(num, (short) 1, overflow);
                temp = divS(temp, denom);
                statRat = add(statRat, shr(temp, sub((short) 8, exp, overflow), overflow), overflow);
            }
            if (statRat > STAT_THR) {
                statCount = STAT_COUNT;
            } else if ((vadreg & 0x4000) != 0 && statCount != 0) {
                statCount = sub(statCount, (short) 1, overflow);
            }
        }
        var alpha = ALPHA4;
        if (statCount == STAT_COUNT) {
            alpha = 32767;
        } else if ((vadreg & 0x4000) == 0) {
            alpha = ALPHA5;
        }
        for (var i = 0; i < COMPLEN; i++) {
            var temp = sub(level[i], aveLevel[i], overflow);
            temp = multR(alpha, temp, overflow);
            aveLevel[i] = add(aveLevel[i], temp, overflow);
        }
    }

    /**
     * Applies the hangover to the intermediate decision.
     *
     * @param noiseLevel the background noise level
     * @param lowPower whether the frame energy is very low
     * @param overflow the saturation flag
     * @return 1 for speech, 0 otherwise
     */
    private short hangoverAddition(short noiseLevel, boolean lowPower, Flag overflow) {
        short hangLen;
        short burstLen;
        if (noiseLevel > HANG_NOISE_THR) {
            burstLen = BURST_LEN_HIGH_NOISE;
            hangLen = HANG_LEN_HIGH_NOISE;
        } else {
            burstLen = BURST_LEN_LOW_NOISE;
            hangLen = HANG_LEN_LOW_NOISE;
        }
        if (lowPower) {
            burstCount = 0;
            hangCount = 0;
            complexHangCount = 0;
            complexHangTimer = 0;
            return 0;
        }
        if (complexHangTimer > CVAD_HANG_LIMIT && complexHangCount < CVAD_HANG_LENGTH) {
            complexHangCount = CVAD_HANG_LENGTH;
        }
        if (complexHangCount != 0) {
            burstCount = BURST_LEN_HIGH_NOISE;
            complexHangCount = sub(complexHangCount, (short) 1, overflow);
            return 1;
        } else if ((vadreg & 0x3ff0) == 0 && corrHpFast > CVAD_THRESH_IN_NOISE) {
            return 1;
        }
        if ((vadreg & 0x4000) != 0) {
            burstCount = add(burstCount, (short) 1, overflow);
            if (burstCount >= burstLen) {
                hangCount = hangLen;
            }
            return 1;
        }
        burstCount = 0;
        if (hangCount > 0) {
            hangCount = sub(hangCount, (short) 1, overflow);
            return 1;
        }
        return 0;
    }

    /**
     * Updates the background noise estimate.
     *
     * @param overflow the saturation flag
     */
    private void noiseEstimateUpdate(Flag overflow) {
        updateCntrl(overflow);
        short bckrAdd = 2;
        short alphaUp;
        short alphaDown;
        if ((0x7800 & vadreg) == 0 && (pitch & 0x7800) == 0 && complexHangCount == 0) {
            alphaUp = ALPHA_UP1;
            alphaDown = ALPHA_DOWN1;
        } else if (statCount == 0 && complexHangCount == 0) {
            alphaUp = ALPHA_UP2;
            alphaDown = ALPHA_DOWN2;
        } else {
            alphaUp = 0;
            alphaDown = ALPHA3;
            bckrAdd = 0;
        }
        for (var i = 0; i < COMPLEN; i++) {
            var temp = sub(oldLevel[i], bckrEst[i], overflow);
            if (temp < 0) {
                temp = multR(alphaDown, temp, overflow);
                temp = add(bckrEst[i], temp, overflow);
                bckrEst[i] = add((short) -2, temp, overflow);
                if (bckrEst[i] < NOISE_MIN) {
                    bckrEst[i] = NOISE_MIN;
                }
            } else {
                temp = multR(alphaUp, temp, overflow);
                temp = add(bckrEst[i], temp, overflow);
                bckrEst[i] = add(bckrAdd, temp, overflow);
                if (bckrEst[i] > NOISE_MAX) {
                    bckrEst[i] = NOISE_MAX;
                }
            }
        }
        System.arraycopy(level, 0, oldLevel, 0, COMPLEN);
    }

    /**
     * Adapts the filtered complex correlation.
     *
     * @param lowPower whether the frame energy is very low
     * @param overflow the saturation flag
     */
    private void complexEstimateAdapt(boolean lowPower, Flag overflow) {
        short alpha;
        if (bestCorrHp < corrHpFast) {
            alpha = corrHpFast < CVAD_THRESH_ADAPT_HIGH ? CVAD_ADAPT_FAST : CVAD_ADAPT_REALLY_FAST;
        } else {
            alpha = corrHpFast < CVAD_THRESH_ADAPT_HIGH ? CVAD_ADAPT_FAST : CVAD_ADAPT_SLOW;
        }
        var lTmp = corrHpFast << 16;
        lTmp = lMsu(lTmp, alpha, corrHpFast, overflow);
        lTmp = lMac(lTmp, alpha, bestCorrHp, overflow);
        corrHpFast = round(lTmp, overflow);
        if (corrHpFast < CVAD_MIN_CORR) {
            corrHpFast = CVAD_MIN_CORR;
        }
        if (lowPower) {
            corrHpFast = CVAD_MIN_CORR;
        }
    }

    /**
     * Updates the complex-signal detector.
     *
     * @param lowPower whether the frame energy is very low
     * @param overflow the saturation flag
     * @return 1 for a complex background warning, 0 otherwise
     */
    private short complexVad(boolean lowPower, Flag overflow) {
        complexHigh = shr(complexHigh, (short) 1, overflow);
        complexLow = shr(complexLow, (short) 1, overflow);
        if (!lowPower) {
            if (corrHpFast > CVAD_THRESH_ADAPT_HIGH) {
                complexHigh |= 0x4000;
            }
            if (corrHpFast > CVAD_THRESH_ADAPT_LOW) {
                complexLow |= 0x4000;
            }
        }
        if (corrHpFast > CVAD_THRESH_HANG) {
            complexHangTimer = add(complexHangTimer, (short) 1, overflow);
        } else {
            complexHangTimer = 0;
        }
        return (short) ((short) (complexHigh & 0x7f80) == 0x7f80 || (short) (complexLow & 0x7fff) == 0x7fff ? 1 : 0);
    }

    /**
     * Takes the decision of the frame from its band levels.
     *
     * @param powSum the frame energy
     * @param overflow the saturation flag
     * @return 1 for speech, 0 otherwise
     */
    private short vadDecision(int powSum, Flag overflow) {
        var lTemp = 0;
        for (var i = 0; i < COMPLEN; i++) {
            var exp = normS(bckrEst[i]);
            var temp = shl(bckrEst[i], exp);
            temp = divS(shr(level[i], (short) 1, overflow), temp);
            temp = shl(temp, sub(exp, (short) (UNIRSHFT - 1), overflow));
            lTemp = lMac(lTemp, temp, temp, overflow);
        }
        var snrSum = (short) (lShl(lTemp, (short) 6) >> 16);
        snrSum = mult(snrSum, INV_COMPLEN, overflow);
        lTemp = 0;
        for (var i = 0; i < COMPLEN; i++) {
            lTemp = lAdd(lTemp, bckrEst[i], overflow);
        }
        var noiseLevel = (short) (lShl(lTemp, (short) 13) >> 16);
        var temp1 = sub(noiseLevel, VAD_P1, overflow);
        temp1 = mult(VAD_SLOPE, temp1, overflow);
        var vadThr = add(temp1, VAD_THR_HIGH, overflow);
        if (vadThr < VAD_THR_LOW) {
            vadThr = VAD_THR_LOW;
        }
        vadreg = shr(vadreg, (short) 1, overflow);
        if (snrSum > vadThr) {
            vadreg |= 0x4000;
        }
        var lowPower = lSub(powSum, VAD_POW_LOW, overflow) < 0;
        complexEstimateAdapt(lowPower, overflow);
        complexWarning = complexVad(lowPower, overflow);
        noiseEstimateUpdate(overflow);
        speechVadDecision = hangoverAddition(noiseLevel, lowPower, overflow);
        return speechVadDecision;
    }

    /**
     * Records the high-pass filtered correlation maximum of the open-loop pitch search.
     *
     * @param bestCorrHp the correlation, Q15
     */
    void complexDetectionUpdate(short bestCorrHp) {
        this.bestCorrHp = bestCorrHp;
    }

    /**
     * Sets the tone flag when the open-loop correlation is close to the energy.
     *
     * @param t0 the maximum correlation
     * @param t1 the energy
     * @param overflow the saturation flag
     */
    void toneDetection(int t0, int t1, Flag overflow) {
        var temp = round(t1, overflow);
        if (temp > 0 && lMsu(t0, temp, TONE_THR, overflow) > 0) {
            tone |= 0x4000;
        }
    }

    /**
     * Shifts the tone flags at the start of an open-loop search.
     *
     * @param oneLagPerFrame whether the mode searches one lag per frame
     * @param overflow the saturation flag
     */
    void toneDetectionUpdate(boolean oneLagPerFrame, Flag overflow) {
        tone = shr(tone, (short) 1, overflow);
        if (oneLagPerFrame) {
            tone = shr(tone, (short) 1, overflow);
            tone |= 0x2000;
        }
    }

    /**
     * Updates the pitch flags from the open-loop lags of the frame.
     *
     * @param tOp the two open-loop lags
     * @param overflow the saturation flag
     */
    void pitchDetection(short[] tOp, Flag overflow) {
        short lagcount = 0;
        for (var i = 0; i < 2; i++) {
            var temp = abs(sub(oldlag, tOp[i], overflow));
            if (temp < LTHRESH) {
                lagcount++;
            }
            oldlag = tOp[i];
        }
        pitch = shr(pitch, (short) 1, overflow);
        var temp = add(oldlagCount, lagcount, overflow);
        if (temp >= NTHRESH) {
            pitch |= 0x4000;
        }
        oldlagCount = lagcount;
    }

    /**
     * Decides whether a frame holds speech.
     *
     * @param in the speech buffer, with the 40 samples before the frame available
     * @param inOffset the first sample of the frame
     * @param overflow the saturation flag
     * @return 1 for speech, 0 otherwise
     */
    short vad(short[] in, int inOffset, Flag overflow) {
        var powSum = 0;
        for (var i = 0; i < FRAME_LEN; i++) {
            var x = in[inOffset + i - LOOKAHEAD];
            powSum = lMac(powSum, x, x, overflow);
        }
        if (lSub(powSum, POW_PITCH_THR, overflow) < 0) {
            pitch = (short) (pitch & 0x3fff);
        }
        if (lSub(powSum, POW_COMPLEX_THR, overflow) < 0) {
            complexLow = (short) (complexLow & 0x3fff);
        }
        filterBank(in, inOffset, overflow);
        return vadDecision(powSum, overflow);
    }
}
