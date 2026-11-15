/* Ports libopus 1.5.2 (silk/PLC.c, silk/PLC.h). Copyright Skype Limited; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.silk;

import com.github.auties00.smalt.audio.opus.internal.dnn.LpcnetPlc;

import java.util.Arrays;

import static com.github.auties00.smalt.audio.opus.internal.silk.Define.LTP_ORDER;
import static com.github.auties00.smalt.audio.opus.internal.silk.Define.MAX_LPC_ORDER;
import static com.github.auties00.smalt.audio.opus.internal.silk.Define.MAX_NB_SUBFR;
import static com.github.auties00.smalt.audio.opus.internal.silk.Define.TYPE_VOICED;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.addSat32;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.fixConst;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.inverse32VarQ;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.lshiftSat32;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.rand;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.rshiftRound;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.sat16;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.smlawb;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.smulbb;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.smulwb;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.smulww;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.sqrtApprox;

/**
 * Conceals lost SILK frames by extrapolating the pitch and LPC parameters of the last good frame,
 * and smooths the transition back to decoded frames.
 *
 * <p>A channel in deep PLC mode ({@link DecoderState#dnn}) behaves as the reference built with
 * {@code ENABLE_DEEP_PLC}: at 16 kHz the deep PLC receives every decoded frame and replaces the concealment when
 * enabled or when DRED features are queued, and the energy glue after a loss is skipped.
 */
public final class Plc {
    /**
     * The bandwidth expansion applied to the LPC filter of each concealed frame.
     */
    private static final double BWE_COEF = 0.99;

    /**
     * The lowest LTP gain kept for voiced concealment, 0.7 in Q14.
     */
    private static final int V_PITCH_GAIN_START_MIN_Q14 = 11469;

    /**
     * The highest LTP gain kept for voiced concealment, 0.95 in Q14.
     */
    private static final int V_PITCH_GAIN_START_MAX_Q14 = 15565;

    /**
     * The maximum pitch lag in milliseconds.
     */
    private static final int MAX_PITCH_LAG_MS = 18;

    /**
     * The size of the random excitation buffer.
     */
    private static final int RAND_BUF_SIZE = 128;

    /**
     * The index mask of the random excitation buffer.
     */
    private static final int RAND_BUF_MASK = RAND_BUF_SIZE - 1;

    /**
     * The base-2 logarithm of the high threshold of the inverse LPC gain (8 dB).
     */
    private static final int LOG2_INV_LPC_GAIN_HIGH_THRES = 3;

    /**
     * The base-2 logarithm of the low threshold of the inverse LPC gain (24 dB).
     */
    private static final int LOG2_INV_LPC_GAIN_LOW_THRES = 8;

    /**
     * The pitch lag drift per subframe, 0.01 in Q16.
     */
    private static final int PITCH_DRIFT_FAC_Q16 = 655;

    /**
     * The number of attenuation steps.
     */
    private static final int NB_ATT = 2;

    /**
     * The harmonic attenuation of the first and later lost frames (0.99, 0.95), in Q15.
     */
    private static final short[] HARM_ATT_Q15 = {32440, 31130};

    /**
     * The random noise attenuation of voiced frames (0.95, 0.8), in Q15.
     */
    private static final short[] PLC_RAND_ATTENUATE_V_Q15 = {31130, 26214};

    /**
     * The random noise attenuation of unvoiced frames (0.99, 0.9), in Q15.
     */
    private static final short[] PLC_RAND_ATTENUATE_UV_Q15 = {32440, 29491};

    /**
     * The chirp factor of {@link #BWE_COEF} in Q16.
     */
    private static final int BWE_COEF_Q16 = fixConst(BWE_COEF, 16);

    private Plc() {
        throw new AssertionError();
    }

    /**
     * Resets the concealment state ({@code silk_PLC_Reset}).
     *
     * @param psDec the channel state
     */
    public static void plcReset(DecoderState psDec) {
        psDec.sPLC.pitchL_Q8 = psDec.frame_length << (8 - 1);
        psDec.sPLC.prevGain_Q16[0] = 1 << 16;
        psDec.sPLC.prevGain_Q16[1] = 1 << 16;
        psDec.sPLC.subfr_length = 20;
        psDec.sPLC.nb_subfr = 2;
    }

    /**
     * Updates the concealment state from a good frame, or conceals a lost frame ({@code silk_PLC}).
     *
     * @param psDec     the channel state
     * @param psDecCtrl the parameters of the frame
     * @param frame     the decoded frame, or receives the concealed frame
     * @param frameOff  the index of the first sample
     * @param lost      nonzero when the frame is lost
     * @param lpcnet    the deep PLC, or {@code null}
     */
    public static void plc(DecoderState psDec, DecoderControl psDecCtrl, short[] frame, int frameOff, int lost,
                           LpcnetPlc lpcnet) {
        if (psDec.fs_kHz != psDec.sPLC.fs_kHz) {
            plcReset(psDec);
            psDec.sPLC.fs_kHz = psDec.fs_kHz;
        }
        if (lost != 0) {
            plcConceal(psDec, psDecCtrl, frame, frameOff, lpcnet);
            psDec.lossCnt++;
        } else {
            plcUpdate(psDec, psDecCtrl);
            if (lpcnet != null && psDec.sPLC.fs_kHz == 16) {
                for (var k = 0; k < psDec.nb_subfr; k += 2) {
                    lpcnet.update(frame, frameOff + k * psDec.subfr_length);
                }
            }
        }
    }

    /**
     * Saves the parameters of a good frame for later concealment ({@code silk_PLC_update}).
     *
     * @param psDec     the channel state
     * @param psDecCtrl the parameters of the frame
     */
    private static void plcUpdate(DecoderState psDec, DecoderControl psDecCtrl) {
        var psPLC = psDec.sPLC;
        psDec.prevSignalType = psDec.indices.signalType;
        var LTP_Gain_Q14 = 0;
        if (psDec.indices.signalType == TYPE_VOICED) {
            for (var j = 0; j * psDec.subfr_length < psDecCtrl.pitchL[psDec.nb_subfr - 1]; j++) {
                if (j == psDec.nb_subfr) {
                    break;
                }
                var temp_LTP_Gain_Q14 = 0;
                for (var i = 0; i < LTP_ORDER; i++) {
                    temp_LTP_Gain_Q14 += psDecCtrl.LTPCoef_Q14[(psDec.nb_subfr - 1 - j) * LTP_ORDER + i];
                }
                if (temp_LTP_Gain_Q14 > LTP_Gain_Q14) {
                    LTP_Gain_Q14 = temp_LTP_Gain_Q14;
                    System.arraycopy(psDecCtrl.LTPCoef_Q14, (psDec.nb_subfr - 1 - j) * LTP_ORDER, psPLC.LTPCoef_Q14, 0,
                            LTP_ORDER);
                    psPLC.pitchL_Q8 = psDecCtrl.pitchL[psDec.nb_subfr - 1 - j] << 8;
                }
            }
            Arrays.fill(psPLC.LTPCoef_Q14, (short) 0);
            psPLC.LTPCoef_Q14[LTP_ORDER / 2] = (short) LTP_Gain_Q14;
            if (LTP_Gain_Q14 < V_PITCH_GAIN_START_MIN_Q14) {
                var tmp = V_PITCH_GAIN_START_MIN_Q14 << 10;
                var scale_Q10 = tmp / Math.max(LTP_Gain_Q14, 1);
                for (var i = 0; i < LTP_ORDER; i++) {
                    psPLC.LTPCoef_Q14[i] = (short) (smulbb(psPLC.LTPCoef_Q14[i], scale_Q10) >> 10);
                }
            } else if (LTP_Gain_Q14 > V_PITCH_GAIN_START_MAX_Q14) {
                var tmp = V_PITCH_GAIN_START_MAX_Q14 << 14;
                var scale_Q14 = tmp / Math.max(LTP_Gain_Q14, 1);
                for (var i = 0; i < LTP_ORDER; i++) {
                    psPLC.LTPCoef_Q14[i] = (short) (smulbb(psPLC.LTPCoef_Q14[i], scale_Q14) >> 14);
                }
            }
        } else {
            psPLC.pitchL_Q8 = smulbb(psDec.fs_kHz, 18) << 8;
            Arrays.fill(psPLC.LTPCoef_Q14, (short) 0);
        }
        System.arraycopy(psDecCtrl.PredCoef_Q12[1], 0, psPLC.prevLPC_Q12, 0, psDec.LPC_order);
        psPLC.prevLTP_scale_Q14 = (short) psDecCtrl.LTP_scale_Q14;
        System.arraycopy(psDecCtrl.Gains_Q16, psDec.nb_subfr - 2, psPLC.prevGain_Q16, 0, 2);
        psPLC.subfr_length = psDec.subfr_length;
        psPLC.nb_subfr = psDec.nb_subfr;
    }

    /**
     * Computes the energies of the last two excitation subframes scaled by their gains
     * ({@code silk_PLC_energy}).
     *
     * @param exc_buf      the scratch buffer receiving the scaled excitation, two subframes long
     * @param exc_Q14      the excitation of the last frame
     * @param prevGain_Q10 the gains of the last two subframes in Q10
     * @param subfr_length the subframe length
     * @param nb_subfr     the number of subframes
     * @param energies     receives the result
     * @return {@code energies}, holding the energy and shift of the first subframe at index 0 and of the second
     *         subframe at index 1, each packed as by {@link SumSqrShift#sumSqrShift(short[], int, int)}
     */
    private static long[] plcEnergy(short[] exc_buf, int[] exc_Q14, int[] prevGain_Q10, int subfr_length,
                                    int nb_subfr, long[] energies) {
        var exc_buf_ptr = 0;
        for (var k = 0; k < 2; k++) {
            for (var i = 0; i < subfr_length; i++) {
                exc_buf[exc_buf_ptr + i] = (short) sat16(
                        smulww(exc_Q14[i + (k + nb_subfr - 2) * subfr_length], prevGain_Q10[k]) >> 8);
            }
            exc_buf_ptr += subfr_length;
        }
        energies[0] = SumSqrShift.sumSqrShift(exc_buf, 0, subfr_length);
        energies[1] = SumSqrShift.sumSqrShift(exc_buf, subfr_length, subfr_length);
        return energies;
    }

    /**
     * Generates a concealed frame ({@code silk_PLC_conceal}).
     *
     * @param psDec     the channel state
     * @param psDecCtrl receives the pitch lags used
     * @param frame     receives the concealed frame
     * @param frameOff  the index of the first sample
     * @param lpcnet    the deep PLC, or {@code null}
     */
    private static void plcConceal(DecoderState psDec, DecoderControl psDecCtrl, short[] frame, int frameOff,
                                   LpcnetPlc lpcnet) {
        var psPLC = psDec.sPLC;
        var sLTP = psDec.scratchPlcSLTP;
        var sLTP_Q14 = psDec.scratchPlcSLTP_Q14;
        var A_Q12 = psDec.scratchPlcA_Q12;
        var prevGain_Q10 = psDec.scratchPlcPrevGain_Q10;
        prevGain_Q10[0] = psPLC.prevGain_Q16[0] >> 6;
        prevGain_Q10[1] = psPLC.prevGain_Q16[1] >> 6;
        if (psDec.first_frame_after_reset != 0) {
            Arrays.fill(psPLC.prevLPC_Q12, (short) 0);
        }
        var energies = plcEnergy(psDec.scratchPlcExcBuf16, psDec.exc_Q14, prevGain_Q10, psDec.subfr_length,
                psDec.nb_subfr, psDec.scratchPlcEnergies);
        var energy1 = SumSqrShift.energy(energies[0]);
        var shift1 = SumSqrShift.shift(energies[0]);
        var energy2 = SumSqrShift.energy(energies[1]);
        var shift2 = SumSqrShift.shift(energies[1]);
        int rand_ptr;
        if ((energy1 >> shift2) < (energy2 >> shift1)) {
            rand_ptr = Math.max(0, (psPLC.nb_subfr - 1) * psPLC.subfr_length - RAND_BUF_SIZE);
        } else {
            rand_ptr = Math.max(0, psPLC.nb_subfr * psPLC.subfr_length - RAND_BUF_SIZE);
        }
        var B_Q14 = psPLC.LTPCoef_Q14;
        var rand_scale_Q14 = psPLC.randScale_Q14;
        int harm_Gain_Q15 = HARM_ATT_Q15[Math.min(NB_ATT - 1, psDec.lossCnt)];
        int rand_Gain_Q15;
        if (psDec.prevSignalType == TYPE_VOICED) {
            rand_Gain_Q15 = PLC_RAND_ATTENUATE_V_Q15[Math.min(NB_ATT - 1, psDec.lossCnt)];
        } else {
            rand_Gain_Q15 = PLC_RAND_ATTENUATE_UV_Q15[Math.min(NB_ATT - 1, psDec.lossCnt)];
        }
        Bwexpander.bwexpander(psPLC.prevLPC_Q12, psDec.LPC_order, BWE_COEF_Q16);
        System.arraycopy(psPLC.prevLPC_Q12, 0, A_Q12, 0, psDec.LPC_order);
        if (psDec.lossCnt == 0) {
            rand_scale_Q14 = 1 << 14;
            if (psDec.prevSignalType == TYPE_VOICED) {
                for (var i = 0; i < LTP_ORDER; i++) {
                    rand_scale_Q14 = (short) (rand_scale_Q14 - B_Q14[i]);
                }
                rand_scale_Q14 = (short) Math.max(3277, rand_scale_Q14);
                rand_scale_Q14 = (short) (smulbb(rand_scale_Q14, psPLC.prevLTP_scale_Q14) >> 14);
            } else {
                var invGain_Q30 = LpcInvPredGain.lpcInversePredGain(psPLC.prevLPC_Q12, psDec.LPC_order);
                var down_scale_Q30 = Math.min((1 << 30) >> LOG2_INV_LPC_GAIN_HIGH_THRES, invGain_Q30);
                down_scale_Q30 = Math.max((1 << 30) >> LOG2_INV_LPC_GAIN_LOW_THRES, down_scale_Q30);
                down_scale_Q30 = down_scale_Q30 << LOG2_INV_LPC_GAIN_HIGH_THRES;
                rand_Gain_Q15 = smulwb(down_scale_Q30, rand_Gain_Q15) >> 14;
            }
        }
        var rand_seed = psPLC.rand_seed;
        var lag = rshiftRound(psPLC.pitchL_Q8, 8);
        var sLTP_buf_idx = psDec.ltp_mem_length;
        var idx = psDec.ltp_mem_length - lag - psDec.LPC_order - LTP_ORDER / 2;
        LpcAnalysisFilter.lpcAnalysisFilter(sLTP, idx, psDec.outBuf, idx, A_Q12, 0, psDec.ltp_mem_length - idx,
                psDec.LPC_order);
        var inv_gain_Q30 = inverse32VarQ(psPLC.prevGain_Q16[1], 46);
        inv_gain_Q30 = Math.min(inv_gain_Q30, Integer.MAX_VALUE >> 1);
        for (var i = idx + psDec.LPC_order; i < psDec.ltp_mem_length; i++) {
            sLTP_Q14[i] = smulwb(inv_gain_Q30, sLTP[i]);
        }
        var exc_Q14 = psDec.exc_Q14;
        for (var k = 0; k < psDec.nb_subfr; k++) {
            var pred_lag_ptr = sLTP_buf_idx - lag + LTP_ORDER / 2;
            for (var i = 0; i < psDec.subfr_length; i++) {
                var LTP_pred_Q12 = 2;
                LTP_pred_Q12 = smlawb(LTP_pred_Q12, sLTP_Q14[pred_lag_ptr], B_Q14[0]);
                LTP_pred_Q12 = smlawb(LTP_pred_Q12, sLTP_Q14[pred_lag_ptr - 1], B_Q14[1]);
                LTP_pred_Q12 = smlawb(LTP_pred_Q12, sLTP_Q14[pred_lag_ptr - 2], B_Q14[2]);
                LTP_pred_Q12 = smlawb(LTP_pred_Q12, sLTP_Q14[pred_lag_ptr - 3], B_Q14[3]);
                LTP_pred_Q12 = smlawb(LTP_pred_Q12, sLTP_Q14[pred_lag_ptr - 4], B_Q14[4]);
                pred_lag_ptr++;
                rand_seed = rand(rand_seed);
                idx = (rand_seed >> 25) & RAND_BUF_MASK;
                sLTP_Q14[sLTP_buf_idx] = smlawb(LTP_pred_Q12, exc_Q14[rand_ptr + idx], rand_scale_Q14) << 2;
                sLTP_buf_idx++;
            }
            for (var j = 0; j < LTP_ORDER; j++) {
                B_Q14[j] = (short) (smulbb(harm_Gain_Q15, B_Q14[j]) >> 15);
            }
            rand_scale_Q14 = (short) (smulbb(rand_scale_Q14, rand_Gain_Q15) >> 15);
            psPLC.pitchL_Q8 = smlawb(psPLC.pitchL_Q8, psPLC.pitchL_Q8, PITCH_DRIFT_FAC_Q16);
            psPLC.pitchL_Q8 = Math.min(psPLC.pitchL_Q8, smulbb(MAX_PITCH_LAG_MS, psDec.fs_kHz) << 8);
            lag = rshiftRound(psPLC.pitchL_Q8, 8);
        }
        var sLPC_Q14_ptr = psDec.ltp_mem_length - MAX_LPC_ORDER;
        System.arraycopy(psDec.sLPC_Q14_buf, 0, sLTP_Q14, sLPC_Q14_ptr, MAX_LPC_ORDER);
        for (var i = 0; i < psDec.frame_length; i++) {
            var base = sLPC_Q14_ptr + MAX_LPC_ORDER + i;
            var LPC_pred_Q10 = psDec.LPC_order >> 1;
            LPC_pred_Q10 = smlawb(LPC_pred_Q10, sLTP_Q14[base - 1], A_Q12[0]);
            LPC_pred_Q10 = smlawb(LPC_pred_Q10, sLTP_Q14[base - 2], A_Q12[1]);
            LPC_pred_Q10 = smlawb(LPC_pred_Q10, sLTP_Q14[base - 3], A_Q12[2]);
            LPC_pred_Q10 = smlawb(LPC_pred_Q10, sLTP_Q14[base - 4], A_Q12[3]);
            LPC_pred_Q10 = smlawb(LPC_pred_Q10, sLTP_Q14[base - 5], A_Q12[4]);
            LPC_pred_Q10 = smlawb(LPC_pred_Q10, sLTP_Q14[base - 6], A_Q12[5]);
            LPC_pred_Q10 = smlawb(LPC_pred_Q10, sLTP_Q14[base - 7], A_Q12[6]);
            LPC_pred_Q10 = smlawb(LPC_pred_Q10, sLTP_Q14[base - 8], A_Q12[7]);
            LPC_pred_Q10 = smlawb(LPC_pred_Q10, sLTP_Q14[base - 9], A_Q12[8]);
            LPC_pred_Q10 = smlawb(LPC_pred_Q10, sLTP_Q14[base - 10], A_Q12[9]);
            for (var j = 10; j < psDec.LPC_order; j++) {
                LPC_pred_Q10 = smlawb(LPC_pred_Q10, sLTP_Q14[base - j - 1], A_Q12[j]);
            }
            sLTP_Q14[base] = addSat32(sLTP_Q14[base], lshiftSat32(LPC_pred_Q10, 4));
            frame[frameOff + i] = (short) sat16(sat16(rshiftRound(smulww(sLTP_Q14[base], prevGain_Q10[1]), 8)));
        }
        if (lpcnet != null && psDec.sPLC.fs_kHz == 16) {
            if (psDec.sPLC.enable_deep_plc || lpcnet.fec_fill_pos != 0) {
                for (var k = 0; k < psDec.nb_subfr; k += 2) {
                    lpcnet.conceal(frame, frameOff + k * psDec.subfr_length);
                }
                for (var i = 0; i < psDec.frame_length; i++) {
                    sLTP_Q14[sLPC_Q14_ptr + MAX_LPC_ORDER + i] = (int) StrictMath.floor(
                            .5 + frame[frameOff + i] * (float) (1 << 24) / prevGain_Q10[1]);
                }
            } else {
                for (var k = 0; k < psDec.nb_subfr; k += 2) {
                    lpcnet.update(frame, frameOff + k * psDec.subfr_length);
                }
            }
        }
        System.arraycopy(sLTP_Q14, sLPC_Q14_ptr + psDec.frame_length, psDec.sLPC_Q14_buf, 0, MAX_LPC_ORDER);
        psPLC.rand_seed = rand_seed;
        psPLC.randScale_Q14 = rand_scale_Q14;
        for (var i = 0; i < MAX_NB_SUBFR; i++) {
            psDecCtrl.pitchL[i] = lag;
        }
    }

    /**
     * Smooths the connection between concealed frames and newly received good frames
     * ({@code silk_PLC_glue_frames}).
     *
     * @param psDec    the channel state
     * @param frame    the frame, faded in place when needed
     * @param frameOff the index of the first sample
     * @param length   the number of samples
     */
    public static void plcGlueFrames(DecoderState psDec, short[] frame, int frameOff, int length) {
        var psPLC = psDec.sPLC;
        if (psDec.lossCnt != 0) {
            var packed = SumSqrShift.sumSqrShift(frame, frameOff, length);
            psPLC.conc_energy = SumSqrShift.energy(packed);
            psPLC.conc_energy_shift = SumSqrShift.shift(packed);
            psPLC.last_frame_lost = 1;
        } else {
            if (psDec.sPLC.last_frame_lost != 0) {
                var packed = SumSqrShift.sumSqrShift(frame, frameOff, length);
                var energy = SumSqrShift.energy(packed);
                var energy_shift = SumSqrShift.shift(packed);
                if (energy_shift > psPLC.conc_energy_shift) {
                    psPLC.conc_energy = psPLC.conc_energy >> (energy_shift - psPLC.conc_energy_shift);
                } else if (energy_shift < psPLC.conc_energy_shift) {
                    energy = energy >> (psPLC.conc_energy_shift - energy_shift);
                }
                if (energy > psPLC.conc_energy) {
                    var LZ = Integer.numberOfLeadingZeros(psPLC.conc_energy);
                    LZ = LZ - 1;
                    psPLC.conc_energy = psPLC.conc_energy << LZ;
                    energy = energy >> Math.max(24 - LZ, 0);
                    var frac_Q24 = psPLC.conc_energy / Math.max(energy, 1);
                    var gain_Q16 = sqrtApprox(frac_Q24) << 4;
                    var slope_Q16 = ((1 << 16) - gain_Q16) / length;
                    slope_Q16 = slope_Q16 << 2;
                    if (!psDec.dnn || psDec.sPLC.fs_kHz != 16) {
                        for (var i = 0; i < length; i++) {
                            frame[frameOff + i] = (short) smulwb(gain_Q16, frame[frameOff + i]);
                            gain_Q16 += slope_Q16;
                            if (gain_Q16 > 1 << 16) {
                                break;
                            }
                        }
                    }
                }
            }
            psPLC.last_frame_lost = 0;
        }
    }
}
