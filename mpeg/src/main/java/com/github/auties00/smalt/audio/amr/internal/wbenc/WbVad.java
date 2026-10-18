/* Ports vo-amrwbenc 0.1.3 (wb_vad.c, wb_vad_c.h). Copyright VisualOn; Apache License 2.0. Modified for Smalt. */
package com.github.auties00.smalt.audio.amr.internal.wbenc;

import static com.github.auties00.smalt.audio.amr.internal.wbenc.WbBasicOps.*;

/**
 * Detects voice activity in the AMR-WB encoder (TS 26.194): a 12-band filter bank, background
 * noise and speech level estimation, an adaptive threshold on the band SNRs, and hangover.
 *
 * <p>An instance is the detector's state; it is not thread-safe.
 *
 * @implNote This implementation computes with the saturating arithmetic of the 3GPP TS 26.173 fixed-point reference.
 * The floating-point constant expressions of {@code wb_vad_c.h} are evaluated to the values the reference compiles to.
 */
final class WbVad {
    /**
     * The number of samples of a frame at 12.8 kHz.
     */
    private static final int FRAME_LEN = 256;

    /**
     * The number of filter bank bands.
     */
    private static final int COMPLEN = 12;

    /**
     * The scaling of the band SNRs.
     */
    private static final int UNIRSHFT = 7;

    /**
     * The open-loop pitch gain above which a frame counts as tonal: 0.65 in Q15.
     */
    private static final int TONE_THR = 21298;

    /**
     * The number of frames of the speech level estimation window.
     */
    private static final int SP_EST_COUNT = 80;

    /**
     * The number of active frames that update the speech level.
     */
    private static final int SP_ACTIVITY_COUNT = 25;

    /**
     * The adaptation speed of the speech level: 0.15 in Q15.
     */
    private static final int ALPHA_SP_UP = 4915;

    /**
     * The adaptation speed of the speech level: 0.15 in Q15.
     */
    private static final int ALPHA_SP_DOWN = 4915;

    /**
     * The nominal speech level.
     */
    private static final int NOM_LEVEL = 2050;

    /**
     * The smallest level counted as speech: 0.063 of {@link #NOM_LEVEL}.
     */
    private static final int MIN_SPEECH_LEVEL1 = 129;

    /**
     * The smallest speech level estimate: 0.2 of {@link #NOM_LEVEL}.
     */
    private static final int MIN_SPEECH_LEVEL2 = 410;

    /**
     * The smallest speech to noise ratio.
     */
    private static final int MIN_SPEECH_SNR = 4096;

    /**
     * The fast noise update upwards: 0.05 in Q15.
     */
    private static final short ALPHA_UP1 = 1638;

    /**
     * The fast noise update downwards: 0.064 in Q15.
     */
    private static final short ALPHA_DOWN1 = 2097;

    /**
     * The slow noise update upwards: 0.015 in Q15.
     */
    private static final short ALPHA_UP2 = 491;

    /**
     * The slow noise update downwards: 0.057 in Q15.
     */
    private static final short ALPHA_DOWN2 = 1867;

    /**
     * The noise update downwards during speech: 0.05 in Q15.
     */
    private static final short ALPHA3 = 1638;

    /**
     * The update speed of the average level: 0.1 in Q15.
     */
    private static final short ALPHA4 = 3276;

    /**
     * The update speed of the average level when inactive: 0.5 in Q15.
     */
    private static final short ALPHA5 = 16383;

    /**
     * The smallest VAD threshold: 1.6 in Q7.
     */
    private static final int THR_MIN = 204;

    /**
     * The VAD threshold at low noise: 6 in Q7.
     */
    private static final int THR_HIGH = 768;

    /**
     * The VAD threshold at high noise: 1.7 in Q7.
     */
    private static final int THR_LOW = 217;

    /**
     * The noise level (log domain) of {@link #THR_HIGH}.
     */
    private static final int NO_P1 = 31744;

    /**
     * The slope of the threshold over the noise level.
     */
    private static final int NO_SLOPE = 1509;

    /**
     * The smallest speech-dependent threshold change: -0.75 in Q7.
     */
    private static final int SP_CH_MIN = -96;

    /**
     * The largest speech-dependent threshold change: 0.75 in Q7.
     */
    private static final int SP_CH_MAX = 96;

    /**
     * The speech level (log domain) of {@link #SP_CH_MIN}.
     */
    private static final int SP_P1 = 22527;

    /**
     * The slope of the threshold change over the speech level.
     */
    private static final int SP_SLOPE = -1339;

    /**
     * The longest hangover.
     */
    private static final int HANG_HIGH = 12;

    /**
     * The shortest hangover.
     */
    private static final int HANG_LOW = 2;

    /**
     * The threshold of {@link #HANG_HIGH}.
     */
    private static final int HANG_P1 = THR_LOW;

    /**
     * The slope of the hangover over the threshold.
     */
    private static final int HANG_SLOPE = -1110;

    /**
     * The longest burst.
     */
    private static final int BURST_HIGH = 8;

    /**
     * The threshold of {@link #BURST_HIGH}.
     */
    private static final int BURST_P1 = THR_HIGH;

    /**
     * The slope of the burst length over the threshold.
     */
    private static final int BURST_SLOPE = 297;

    /**
     * The stationarity counter value of a non-stationary signal.
     */
    private static final int STAT_COUNT = 20;

    /**
     * The smallest level of the stationarity ratio.
     */
    private static final int STAT_THR_LEVEL = 184;

    /**
     * The stationarity ratio above which the signal is not stationary.
     */
    private static final int STAT_THR = 1000;

    /**
     * The smallest background noise estimate.
     */
    private static final int NOISE_MIN = 40;

    /**
     * The largest background noise estimate.
     */
    private static final int NOISE_MAX = 20000;

    /**
     * The initial background noise estimate.
     */
    private static final int NOISE_INIT = 150;

    /**
     * The frame power below which the frame is inactive.
     */
    private static final int VAD_POW_LOW = 30000;

    /**
     * The frame power below which the tone flag is cleared.
     */
    private static final int POW_TONE_THR = 686080;

    /**
     * The coefficient of the third-order filter bank sections.
     */
    private static final int COEFF3 = 13363;

    /**
     * The first coefficient of the fifth-order filter bank sections.
     */
    private static final int COEFF5_1 = 21955;

    /**
     * The second coefficient of the fifth-order filter bank sections.
     */
    private static final int COEFF5_2 = 6390;

    /**
     * The background noise estimate of each band.
     */
    private final short[] bckrEst = new short[COMPLEN];

    /**
     * The averaged level of each band.
     */
    private final short[] aveLevel = new short[COMPLEN];

    /**
     * The level of each band in the previous frame.
     */
    private final short[] oldLevel = new short[COMPLEN];

    /**
     * The level of each band carried over to the next frame.
     */
    private final short[] subLevel = new short[COMPLEN];

    /**
     * The memories of the five fifth-order filter sections, two per section.
     */
    private final short[] aData5 = new short[10];

    /**
     * The memories of the six third-order filter sections.
     */
    private final short[] aData3 = new short[6];

    /**
     * The number of consecutive active frames.
     */
    private short burstCount;

    /**
     * The remaining hangover frames.
     */
    private short hangCount;

    /**
     * The stationarity counter.
     */
    private short statCount;

    /**
     * The history of the intermediate VAD decisions, one bit per frame.
     */
    private short vadreg;

    /**
     * The history of the tone detections, one bit per half frame.
     */
    private short toneFlag;

    /**
     * The frame counter of the speech level estimation.
     */
    private short spEstCnt;

    /**
     * The largest level of the speech level estimation window.
     */
    private short spMax;

    /**
     * The number of frames contributing to {@link #spMax}.
     */
    private short spMaxCnt;

    /**
     * The speech level estimate.
     */
    private short speechLevel;

    /**
     * The power of the previous frame.
     */
    private int prevPowSum;

    /**
     * The levels of the current frame.
     */
    private final short[] level = new short[COMPLEN];

    /**
     * The scratch buffer of the filter bank.
     */
    private final short[] bankBuffer = new short[FRAME_LEN];

    /**
     * Creates a detector in its reset state.
     */
    WbVad() {
        reset();
    }

    /**
     * Resets the detector ({@code wb_vad_reset}); the stationarity counter keeps its value, as in
     * the reference.
     */
    void reset() {
        toneFlag = 0;
        vadreg = 0;
        hangCount = 0;
        burstCount = 0;
        java.util.Arrays.fill(aData5, (short) 0);
        java.util.Arrays.fill(aData3, (short) 0);
        for (var i = 0; i < COMPLEN; i++) {
            bckrEst[i] = NOISE_INIT;
            oldLevel[i] = NOISE_INIT;
            aveLevel[i] = NOISE_INIT;
            subLevel[i] = 0;
        }
        spEstCnt = 0;
        spMax = 0;
        spMaxCnt = 0;
        speechLevel = NOM_LEVEL;
        prevPowSum = 0;
    }

    /**
     * Clears every field, including the stationarity counter, as a newly allocated detector.
     */
    void clear() {
        statCount = 0;
        reset();
    }

    /**
     * Records the open-loop pitch gain of a half frame for the tone detection
     * ({@code wb_vad_tone_detection}).
     *
     * @param pGain the open-loop pitch gain in Q15
     */
    void toneDetection(short pGain) {
        toneFlag = (short) (toneFlag >> 1);
        if (pGain > TONE_THR) {
            toneFlag = (short) (toneFlag | 0x4000);
        }
    }

    /**
     * Decides whether a frame is active ({@code wb_vad}).
     *
     * @param inBuf the 256 samples of the pre-processed frame
     * @return {@code 1} for an active frame, {@code 0} for an inactive one
     */
    short vad(short[] inBuf) {
        var temp32 = 0;
        for (var i = 0; i < FRAME_LEN; i++) {
            temp32 = lMac(temp32, inBuf[i], inBuf[i]);
        }
        var powSum = lAdd(temp32, prevPowSum);
        prevPowSum = temp32;
        if (lSub(powSum, POW_TONE_THR) < 0) {
            toneFlag = (short) (toneFlag & 0x1fff);
        }
        filterBank(inBuf);
        var vadFlag = vadDecision(powSum);
        temp32 = 0;
        for (var i = 1; i < COMPLEN; i++) {
            temp32 = lAdd(temp32, level[i]);
        }
        var temp = extractH(lShl(temp32, 12));
        estimateSpeech(temp);
        return vadFlag;
    }

    /**
     * Computes the base-2 logarithm used for the threshold adaptation ({@code ilog2}).
     *
     * @param mant the value
     * @return the logarithm in Q10 plus offsets
     */
    private static short ilog2(short mant) {
        if (mant <= 0) {
            mant = 1;
        }
        var ex = normS(mant);
        mant = shl(mant, ex);
        for (var i = 0; i < 3; i++) {
            mant = mult(mant, mant);
        }
        var temp = lMult(mant, mant);
        var ex2 = normL(temp);
        mant = extractH(lShl(temp, ex2));
        var res = shl(add(ex, 16), 10);
        res = add(res, shl(ex2, 6));
        res = sub(add(res, 127), shr(mant, 8));
        return res;
    }

    /**
     * Runs a fifth-order section of the filter bank on two samples of the buffer
     * ({@code filter5}).
     *
     * @param buf the buffer
     * @param in0 the index of the first sample, replaced by the low band
     * @param in1 the index of the second sample, replaced by the high band
     * @param data the index of the section's two memories in {@link #aData5}
     */
    private void filter5(short[] buf, int in0, int in1, int data) {
        var temp0 = sub(buf[in0], mult(COEFF5_1, aData5[data]));
        var temp1 = add(aData5[data], mult(COEFF5_1, temp0));
        aData5[data] = temp0;
        temp0 = sub(buf[in1], mult(COEFF5_2, aData5[data + 1]));
        var temp2 = add(aData5[data + 1], mult(COEFF5_2, temp0));
        aData5[data + 1] = temp0;
        buf[in0] = extractH(lShl(lAdd(temp1, temp2), 15));
        buf[in1] = extractH(lShl(lSub(temp1, temp2), 15));
    }

    /**
     * Runs a third-order section of the filter bank on two samples of the buffer
     * ({@code filter3}).
     *
     * @param buf the buffer
     * @param in0 the index of the first sample, replaced by the low band
     * @param in1 the index of the second sample, replaced by the high band
     * @param data the index of the section's memory in {@link #aData3}
     */
    private void filter3(short[] buf, int in0, int in1, int data) {
        var temp1 = sub(buf[in1], mult(COEFF3, aData3[data]));
        var temp2 = add(aData3[data], mult(COEFF3, temp1));
        aData3[data] = temp1;
        buf[in1] = extractH(lShl(lSub(buf[in0], temp2), 15));
        buf[in0] = extractH(lShl(lAdd(buf[in0], temp2), 15));
    }

    /**
     * Computes the level of one band ({@code level_calculation}).
     *
     * @param data the filtered buffer
     * @param subIndex the index of the band in {@link #subLevel}
     * @param count1 the number of samples carried over from the previous frame
     * @param count2 the number of samples of the band
     * @param indM the decimation of the band in the buffer
     * @param indA the offset of the band in the buffer
     * @param scale the scaling of the level
     * @return the level
     */
    private short levelCalculation(short[] data, int subIndex, int count1, int count2, int indM, int indA, int scale) {
        var temp1 = 0;
        for (var i = count1; i < count2; i++) {
            temp1 = lMac(temp1, 1, absS(data[indM * i + indA]));
        }
        var temp2 = lAdd(temp1, lShl(subLevel[subIndex], sub(16, scale)));
        subLevel[subIndex] = extractH(lShl(temp1, scale));
        for (var i = 0; i < count1; i++) {
            temp2 = lMac(temp2, 1, absS(data[indM * i + indA]));
        }
        return extractH(lShl(temp2, scale));
    }

    /**
     * Splits a frame into the 12 bands and computes their levels into {@link #level}
     * ({@code filter_bank}).
     *
     * @param in the 256 samples of the frame
     */
    private void filterBank(short[] in) {
        var buf = bankBuffer;
        for (var i = 0; i < FRAME_LEN; i++) {
            buf[i] = shr(in[i], 1);
        }
        for (var i = 0; i < 128; i++) {
            filter5(buf, 2 * i, 2 * i + 1, 0);
        }
        for (var i = 0; i < 64; i++) {
            filter5(buf, 4 * i, 4 * i + 2, 2);
            filter5(buf, 4 * i + 1, 4 * i + 3, 4);
        }
        for (var i = 0; i < 32; i++) {
            filter5(buf, 8 * i, 8 * i + 4, 6);
            filter5(buf, 8 * i + 2, 8 * i + 6, 8);
            filter3(buf, 8 * i + 3, 8 * i + 7, 0);
        }
        for (var i = 0; i < 16; i++) {
            filter3(buf, 16 * i, 16 * i + 8, 1);
            filter3(buf, 16 * i + 4, 16 * i + 12, 2);
            filter3(buf, 16 * i + 6, 16 * i + 14, 3);
        }
        for (var i = 0; i < 8; i++) {
            filter3(buf, 32 * i, 32 * i + 16, 4);
            filter3(buf, 32 * i + 8, 32 * i + 24, 5);
        }
        level[11] = levelCalculation(buf, 11, 16, 64, 4, 1, 14);
        level[10] = levelCalculation(buf, 10, 8, 32, 8, 7, 15);
        level[9] = levelCalculation(buf, 9, 8, 32, 8, 3, 15);
        level[8] = levelCalculation(buf, 8, 8, 32, 8, 2, 15);
        level[7] = levelCalculation(buf, 7, 4, 16, 16, 14, 16);
        level[6] = levelCalculation(buf, 6, 4, 16, 16, 6, 16);
        level[5] = levelCalculation(buf, 5, 4, 16, 16, 4, 16);
        level[4] = levelCalculation(buf, 4, 4, 16, 16, 12, 16);
        level[3] = levelCalculation(buf, 3, 2, 8, 32, 8, 17);
        level[2] = levelCalculation(buf, 2, 2, 8, 32, 24, 17);
        level[1] = levelCalculation(buf, 1, 2, 8, 32, 16, 17);
        level[0] = levelCalculation(buf, 0, 2, 8, 32, 0, 17);
    }

    /**
     * Updates the stationarity counter and the averaged band levels ({@code update_cntrl}).
     */
    private void updateCntrl() {
        if ((toneFlag & 0x7c00) == 0x7c00) {
            statCount = STAT_COUNT;
        } else if ((vadreg & 0x7f80) == 0) {
            statCount = STAT_COUNT;
        } else {
            var statRat = (short) 0;
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
                var temp = divS(shr(num, 1), denom);
                statRat = add(statRat, shr(temp, sub(8, exp)));
            }
            if (statRat > STAT_THR) {
                statCount = STAT_COUNT;
            } else if ((vadreg & 0x4000) != 0 && statCount != 0) {
                statCount = sub(statCount, 1);
            }
        }
        var alpha = ALPHA4;
        if (statCount == STAT_COUNT) {
            alpha = 32767;
        } else if ((vadreg & 0x4000) == 0) {
            alpha = ALPHA5;
        }
        for (var i = 0; i < COMPLEN; i++) {
            aveLevel[i] = add(aveLevel[i], multR(alpha, sub(level[i], aveLevel[i])));
        }
    }

    /**
     * Applies the burst and hangover logic to the intermediate decision
     * ({@code hangover_addition}).
     *
     * @param lowPower whether the frame power is very low
     * @param hangLen the hangover length
     * @param burstLen the burst length that arms the hangover
     * @return the final VAD decision
     */
    private short hangoverAddition(boolean lowPower, int hangLen, int burstLen) {
        if (lowPower) {
            burstCount = 0;
            hangCount = 0;
            return 0;
        }
        if ((vadreg & 0x4000) != 0) {
            burstCount = add(burstCount, 1);
            if (burstCount >= burstLen) {
                hangCount = (short) hangLen;
            }
            return 1;
        }
        burstCount = 0;
        if (hangCount > 0) {
            hangCount = sub(hangCount, 1);
            return 1;
        }
        return 0;
    }

    /**
     * Updates the background noise estimate of each band ({@code noise_estimate_update}).
     */
    private void noiseEstimateUpdate() {
        updateCntrl();
        short alphaUp;
        short alphaDown;
        var bckrAdd = 2;
        if ((0x7800 & vadreg) == 0) {
            alphaUp = ALPHA_UP1;
            alphaDown = ALPHA_DOWN1;
        } else if (statCount == 0) {
            alphaUp = ALPHA_UP2;
            alphaDown = ALPHA_DOWN2;
        } else {
            alphaUp = 0;
            alphaDown = ALPHA3;
            bckrAdd = 0;
        }
        for (var i = 0; i < COMPLEN; i++) {
            var temp = sub(oldLevel[i], bckrEst[i]);
            if (temp < 0) {
                bckrEst[i] = add(-2, add(bckrEst[i], multR(alphaDown, temp)));
                if (bckrEst[i] < NOISE_MIN) {
                    bckrEst[i] = NOISE_MIN;
                }
            } else {
                bckrEst[i] = add(bckrAdd, add(bckrEst[i], multR(alphaUp, temp)));
                if (bckrEst[i] > NOISE_MAX) {
                    bckrEst[i] = NOISE_MAX;
                }
            }
        }
        System.arraycopy(level, 0, oldLevel, 0, COMPLEN);
    }

    /**
     * Makes the VAD decision of a frame from its band levels ({@code vad_decision}).
     *
     * @param powSum the power of the frame and the previous one
     * @return the final VAD decision
     */
    private short vadDecision(int powSum) {
        var snrSum = 0;
        for (var i = 0; i < COMPLEN; i++) {
            var exp = normS(bckrEst[i]);
            var temp = shl(bckrEst[i], exp);
            temp = divS(shr(level[i], 1), temp);
            temp = shl(temp, sub(exp, UNIRSHFT - 1));
            snrSum = lMac(snrSum, temp, temp);
        }
        var temp32 = 0;
        for (var i = 1; i < COMPLEN; i++) {
            temp32 = lAdd(temp32, bckrEst[i]);
        }
        var noiseLevel = extractH(lShl(temp32, 12));
        var temp = shl(mult(noiseLevel, MIN_SPEECH_SNR), 3);
        if (speechLevel < temp) {
            speechLevel = temp;
        }
        var ilog2NoiseLevel = ilog2(noiseLevel);
        var ilog2SpeechLevel = ilog2(sub(speechLevel, temp));
        temp = add(mult(NO_SLOPE, sub(ilog2NoiseLevel, NO_P1)), THR_HIGH);
        var temp2 = add(SP_CH_MIN, mult(SP_SLOPE, sub(ilog2SpeechLevel, SP_P1)));
        if (temp2 < SP_CH_MIN) {
            temp2 = SP_CH_MIN;
        }
        if (temp2 > SP_CH_MAX) {
            temp2 = SP_CH_MAX;
        }
        var vadThr = add(temp, temp2);
        if (vadThr < THR_MIN) {
            vadThr = THR_MIN;
        }
        vadreg = shr(vadreg, 1);
        if (lSub(snrSum, lMult(vadThr, 512 * COMPLEN)) > 0) {
            vadreg = (short) (vadreg | 0x4000);
        }
        var lowPowerFlag = lSub(powSum, VAD_POW_LOW) < 0;
        noiseEstimateUpdate();
        var hangLen = add(mult(HANG_SLOPE, sub(vadThr, HANG_P1)), HANG_HIGH);
        if (hangLen < HANG_LOW) {
            hangLen = HANG_LOW;
        }
        var burstLen = add(mult(BURST_SLOPE, sub(vadThr, BURST_P1)), BURST_HIGH);
        return hangoverAddition(lowPowerFlag, hangLen, burstLen);
    }

    /**
     * Updates the speech level estimate ({@code Estimate_Speech}).
     *
     * @param inLevel the total level of the frame
     */
    private void estimateSpeech(short inLevel) {
        if (sub(sub(spEstCnt, spMaxCnt), SP_EST_COUNT - SP_ACTIVITY_COUNT) > 0) {
            spEstCnt = 0;
            spMax = 0;
            spMaxCnt = 0;
        }
        spEstCnt = add(spEstCnt, 1);
        if (((vadreg & 0x4000) != 0 || inLevel > speechLevel) && inLevel > MIN_SPEECH_LEVEL1) {
            if (inLevel > spMax) {
                spMax = inLevel;
            }
            spMaxCnt = add(spMaxCnt, 1);
            if (spMaxCnt >= SP_ACTIVITY_COUNT) {
                var tmp = shr(spMax, 1);
                var alpha = tmp > speechLevel ? ALPHA_SP_UP : ALPHA_SP_DOWN;
                if (tmp > MIN_SPEECH_LEVEL2) {
                    speechLevel = add(speechLevel, multR(alpha, sub(tmp, speechLevel)));
                }
                spMax = 0;
                spMaxCnt = 0;
                spEstCnt = 0;
            }
        }
    }
}
