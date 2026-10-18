/* Ports libopus 1.5.2 (silk/decode_core.c). Copyright Skype Limited; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.silk;

import static com.github.auties00.smalt.audio.opus.internal.silk.Define.LTP_ORDER;
import static com.github.auties00.smalt.audio.opus.internal.silk.Define.MAX_LPC_ORDER;
import static com.github.auties00.smalt.audio.opus.internal.silk.Define.MAX_NB_SUBFR;
import static com.github.auties00.smalt.audio.opus.internal.silk.Define.QUANT_LEVEL_ADJUST_Q10;
import static com.github.auties00.smalt.audio.opus.internal.silk.Define.TYPE_VOICED;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.addSat32;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.div32VarQ;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.fixConst;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.inverse32VarQ;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.lshiftSat32;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.rand;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.rshiftRound;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.sat16;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.smlawb;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.smulwb;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.smulww;

/**
 * Reconstructs the speech signal of a SILK frame from its excitation and parameters: the inverse
 * of the noise shaping quantizer, applying long-term (pitch) and short-term (LPC) prediction.
 *
 * @implNote This implementation keeps the LPC synthesis scalar: it is a first-order recurrence across samples, with no
 * independent lanes to vectorize.
 */
public final class DecodeCore {
    /**
     * The LTP tap used to smooth the transition from voiced concealment to unvoiced decoding,
     * 0.25 in Q14.
     */
    private static final short PLC_TRANSITION_TAP_Q14 = (short) fixConst(0.25, 14);

    private DecodeCore() {
        throw new AssertionError();
    }

    /**
     * Synthesizes the output of a frame ({@code silk_decode_core}).
     *
     * @param psDec     the channel state
     * @param psDecCtrl the frame parameters
     * @param xq        receives the decoded speech
     * @param xqOff     the index of the first output sample
     * @param pulses    the excitation pulses
     */
    public static void decodeCore(DecoderState psDec, DecoderControl psDecCtrl, short[] xq, int xqOff, short[] pulses) {
        var sLTP = psDec.scratchSLTP;
        var sLTP_Q15 = psDec.scratchSLTP_Q15;
        var res_Q14 = psDec.scratchResQ14;
        var sLPC_Q14 = psDec.scratchSLPC_Q14;
        var exc_Q14 = psDec.exc_Q14;
        var indices = psDec.indices;
        var lag = 0;
        int offset_Q10 = Tables.silk_Quantization_Offsets_Q10[indices.signalType >> 1][indices.quantOffsetType];
        var NLSF_interpolation_flag = indices.NLSFInterpCoef_Q2 < 1 << 2 ? 1 : 0;
        int rand_seed = indices.Seed;
        var offset_Q14 = offset_Q10 << 4;
        for (var i = 0; i < psDec.frame_length; i++) {
            rand_seed = rand(rand_seed);
            var exc = pulses[i] << 14;
            // branch-free form of: exc > 0 ? exc - ADJ : exc < 0 ? exc + ADJ : exc, then negate when rand_seed < 0
            exc -= Integer.signum(exc) * (QUANT_LEVEL_ADJUST_Q10 << 4);
            exc += offset_Q14;
            var sign = rand_seed >> 31;
            exc_Q14[i] = (exc ^ sign) - sign;
            rand_seed = rand_seed + pulses[i];
        }
        System.arraycopy(psDec.sLPC_Q14_buf, 0, sLPC_Q14, 0, MAX_LPC_ORDER);
        var pexc_Q14 = 0;
        var pxq = xqOff;
        var sLTP_buf_idx = psDec.ltp_mem_length;
        var lpcOrder = psDec.LPC_order;
        var subfrLength = psDec.subfr_length;
        for (var k = 0; k < psDec.nb_subfr; k++) {
            var A_Q12 = psDecCtrl.PredCoef_Q12[k >> 1];
            var B_Q14 = psDecCtrl.LTPCoef_Q14;
            var bOff = k * LTP_ORDER;
            int signalType = indices.signalType;
            var Gain_Q10 = psDecCtrl.Gains_Q16[k] >> 6;
            var inv_gain_Q31 = inverse32VarQ(psDecCtrl.Gains_Q16[k], 47);
            int gain_adj_Q16;
            if (psDecCtrl.Gains_Q16[k] != psDec.prev_gain_Q16) {
                gain_adj_Q16 = div32VarQ(psDec.prev_gain_Q16, psDecCtrl.Gains_Q16[k], 16);
                for (var i = 0; i < MAX_LPC_ORDER; i++) {
                    sLPC_Q14[i] = smulww(gain_adj_Q16, sLPC_Q14[i]);
                }
            } else {
                gain_adj_Q16 = 1 << 16;
            }
            psDec.prev_gain_Q16 = psDecCtrl.Gains_Q16[k];
            if (psDec.lossCnt != 0 && psDec.prevSignalType == TYPE_VOICED && indices.signalType != TYPE_VOICED
                    && k < MAX_NB_SUBFR / 2) {
                for (var i = 0; i < LTP_ORDER; i++) {
                    B_Q14[bOff + i] = 0;
                }
                B_Q14[bOff + LTP_ORDER / 2] = PLC_TRANSITION_TAP_Q14;
                signalType = TYPE_VOICED;
                psDecCtrl.pitchL[k] = psDec.lagPrev;
            }
            if (signalType == TYPE_VOICED) {
                lag = psDecCtrl.pitchL[k];
                if (k == 0 || (k == 2 && NLSF_interpolation_flag != 0)) {
                    var start_idx = psDec.ltp_mem_length - lag - lpcOrder - LTP_ORDER / 2;
                    if (k == 2) {
                        System.arraycopy(xq, xqOff, psDec.outBuf, psDec.ltp_mem_length, 2 * subfrLength);
                    }
                    LpcAnalysisFilter.lpcAnalysisFilter(sLTP, start_idx, psDec.outBuf, start_idx + k * subfrLength,
                            A_Q12, 0, psDec.ltp_mem_length - start_idx, lpcOrder);
                    if (k == 0) {
                        inv_gain_Q31 = smulwb(inv_gain_Q31, psDecCtrl.LTP_scale_Q14) << 2;
                    }
                    for (var i = 0; i < lag + LTP_ORDER / 2; i++) {
                        sLTP_Q15[sLTP_buf_idx - i - 1] = smulwb(inv_gain_Q31, sLTP[psDec.ltp_mem_length - i - 1]);
                    }
                } else {
                    if (gain_adj_Q16 != 1 << 16) {
                        for (var i = 0; i < lag + LTP_ORDER / 2; i++) {
                            sLTP_Q15[sLTP_buf_idx - i - 1] = smulww(gain_adj_Q16, sLTP_Q15[sLTP_buf_idx - i - 1]);
                        }
                    }
                }
            }
            int[] pres;
            int presOff;
            if (signalType == TYPE_VOICED) {
                var pred_lag_ptr = sLTP_buf_idx - lag + LTP_ORDER / 2;
                int b0 = B_Q14[bOff];
                int b1 = B_Q14[bOff + 1];
                int b2 = B_Q14[bOff + 2];
                int b3 = B_Q14[bOff + 3];
                int b4 = B_Q14[bOff + 4];
                for (var i = 0; i < subfrLength; i++) {
                    var LTP_pred_Q13 = 2;
                    LTP_pred_Q13 = smlawb(LTP_pred_Q13, sLTP_Q15[pred_lag_ptr], b0);
                    LTP_pred_Q13 = smlawb(LTP_pred_Q13, sLTP_Q15[pred_lag_ptr - 1], b1);
                    LTP_pred_Q13 = smlawb(LTP_pred_Q13, sLTP_Q15[pred_lag_ptr - 2], b2);
                    LTP_pred_Q13 = smlawb(LTP_pred_Q13, sLTP_Q15[pred_lag_ptr - 3], b3);
                    LTP_pred_Q13 = smlawb(LTP_pred_Q13, sLTP_Q15[pred_lag_ptr - 4], b4);
                    pred_lag_ptr++;
                    res_Q14[i] = exc_Q14[pexc_Q14 + i] + (LTP_pred_Q13 << 1);
                    sLTP_Q15[sLTP_buf_idx] = res_Q14[i] << 1;
                    sLTP_buf_idx++;
                }
                pres = res_Q14;
                presOff = 0;
            } else {
                pres = exc_Q14;
                presOff = pexc_Q14;
            }
            lpcSynthesis(sLPC_Q14, A_Q12, lpcOrder, pres, presOff, Gain_Q10, xq, pxq, subfrLength);
            System.arraycopy(sLPC_Q14, subfrLength, sLPC_Q14, 0, MAX_LPC_ORDER);
            pexc_Q14 += subfrLength;
            pxq += subfrLength;
        }
        System.arraycopy(sLPC_Q14, 0, psDec.sLPC_Q14_buf, 0, MAX_LPC_ORDER);
    }

    /**
     * Runs the short-term (LPC) synthesis filter over one subframe and scales the result by the
     * gain.
     *
     * @param sLPC_Q14 the synthesis state: {@link Define#MAX_LPC_ORDER} samples of history, then
     *                 receives the subframe
     * @param A_Q12    the LPC coefficients in Q12
     * @param order    the LPC order, 10 or 16
     * @param pres     the LPC excitation in Q14
     * @param presOff  the index of the first excitation sample
     * @param Gain_Q10 the gain in Q10
     * @param xq       receives the output samples
     * @param xqOff    the index of the first output sample
     * @param length   the subframe length
     */
    private static void lpcSynthesis(int[] sLPC_Q14, short[] A_Q12, int order, int[] pres, int presOff, int Gain_Q10,
                                     short[] xq, int xqOff, int length) {
        if (order == 16) {
            lpcSynthesis16(sLPC_Q14, A_Q12, pres, presOff, Gain_Q10, xq, xqOff, length);
        } else {
            lpcSynthesis10(sLPC_Q14, A_Q12, pres, presOff, Gain_Q10, xq, xqOff, length);
        }
    }

    /**
     * Runs the order-10 LPC synthesis of {@link #lpcSynthesis}.
     *
     * @param sLPC_Q14 the synthesis state and output
     * @param A_Q12    the LPC coefficients in Q12
     * @param pres     the LPC excitation in Q14
     * @param presOff  the index of the first excitation sample
     * @param Gain_Q10 the gain in Q10
     * @param xq       receives the output samples
     * @param xqOff    the index of the first output sample
     * @param length   the subframe length
     */
    private static void lpcSynthesis10(int[] sLPC_Q14, short[] A_Q12, int[] pres, int presOff, int Gain_Q10,
                                       short[] xq, int xqOff, int length) {
        long a0 = A_Q12[0];
        long a1 = A_Q12[1];
        long a2 = A_Q12[2];
        long a3 = A_Q12[3];
        long a4 = A_Q12[4];
        long a5 = A_Q12[5];
        long a6 = A_Q12[6];
        long a7 = A_Q12[7];
        long a8 = A_Q12[8];
        long a9 = A_Q12[9];
        var last = sLPC_Q14[MAX_LPC_ORDER - 1];
        for (var i = 0; i < length; i++) {
            var base = MAX_LPC_ORDER + i;
            // integer addition is associative, so the newest sample's term is added last to keep
            // the sample-to-sample dependency chain short
            var LPC_pred_Q10 = 5;
            LPC_pred_Q10 += (int) ((sLPC_Q14[base - 10] * a9) >> 16);
            LPC_pred_Q10 += (int) ((sLPC_Q14[base - 9] * a8) >> 16);
            LPC_pred_Q10 += (int) ((sLPC_Q14[base - 8] * a7) >> 16);
            LPC_pred_Q10 += (int) ((sLPC_Q14[base - 7] * a6) >> 16);
            LPC_pred_Q10 += (int) ((sLPC_Q14[base - 6] * a5) >> 16);
            LPC_pred_Q10 += (int) ((sLPC_Q14[base - 5] * a4) >> 16);
            LPC_pred_Q10 += (int) ((sLPC_Q14[base - 4] * a3) >> 16);
            LPC_pred_Q10 += (int) ((sLPC_Q14[base - 3] * a2) >> 16);
            LPC_pred_Q10 += (int) ((sLPC_Q14[base - 2] * a1) >> 16);
            LPC_pred_Q10 += (int) ((last * a0) >> 16);
            var v = addSat32(pres[presOff + i], lshiftSat32(LPC_pred_Q10, 4));
            sLPC_Q14[base] = v;
            last = v;
            xq[xqOff + i] = (short) sat16(rshiftRound(smulww(v, Gain_Q10), 8));
        }
    }

    /**
     * Runs the order-16 LPC synthesis of {@link #lpcSynthesis}.
     *
     * @param sLPC_Q14 the synthesis state and output
     * @param A_Q12    the LPC coefficients in Q12
     * @param pres     the LPC excitation in Q14
     * @param presOff  the index of the first excitation sample
     * @param Gain_Q10 the gain in Q10
     * @param xq       receives the output samples
     * @param xqOff    the index of the first output sample
     * @param length   the subframe length
     */
    private static void lpcSynthesis16(int[] sLPC_Q14, short[] A_Q12, int[] pres, int presOff, int Gain_Q10,
                                       short[] xq, int xqOff, int length) {
        long a0 = A_Q12[0];
        long a1 = A_Q12[1];
        long a2 = A_Q12[2];
        long a3 = A_Q12[3];
        long a4 = A_Q12[4];
        long a5 = A_Q12[5];
        long a6 = A_Q12[6];
        long a7 = A_Q12[7];
        long a8 = A_Q12[8];
        long a9 = A_Q12[9];
        long a10 = A_Q12[10];
        long a11 = A_Q12[11];
        long a12 = A_Q12[12];
        long a13 = A_Q12[13];
        long a14 = A_Q12[14];
        long a15 = A_Q12[15];
        var last = sLPC_Q14[MAX_LPC_ORDER - 1];
        for (var i = 0; i < length; i++) {
            var base = MAX_LPC_ORDER + i;
            var LPC_pred_Q10 = 8;
            LPC_pred_Q10 += (int) ((sLPC_Q14[base - 16] * a15) >> 16);
            LPC_pred_Q10 += (int) ((sLPC_Q14[base - 15] * a14) >> 16);
            LPC_pred_Q10 += (int) ((sLPC_Q14[base - 14] * a13) >> 16);
            LPC_pred_Q10 += (int) ((sLPC_Q14[base - 13] * a12) >> 16);
            LPC_pred_Q10 += (int) ((sLPC_Q14[base - 12] * a11) >> 16);
            LPC_pred_Q10 += (int) ((sLPC_Q14[base - 11] * a10) >> 16);
            LPC_pred_Q10 += (int) ((sLPC_Q14[base - 10] * a9) >> 16);
            LPC_pred_Q10 += (int) ((sLPC_Q14[base - 9] * a8) >> 16);
            LPC_pred_Q10 += (int) ((sLPC_Q14[base - 8] * a7) >> 16);
            LPC_pred_Q10 += (int) ((sLPC_Q14[base - 7] * a6) >> 16);
            LPC_pred_Q10 += (int) ((sLPC_Q14[base - 6] * a5) >> 16);
            LPC_pred_Q10 += (int) ((sLPC_Q14[base - 5] * a4) >> 16);
            LPC_pred_Q10 += (int) ((sLPC_Q14[base - 4] * a3) >> 16);
            LPC_pred_Q10 += (int) ((sLPC_Q14[base - 3] * a2) >> 16);
            LPC_pred_Q10 += (int) ((sLPC_Q14[base - 2] * a1) >> 16);
            LPC_pred_Q10 += (int) ((last * a0) >> 16);
            var v = addSat32(pres[presOff + i], lshiftSat32(LPC_pred_Q10, 4));
            sLPC_Q14[base] = v;
            last = v;
            xq[xqOff + i] = (short) sat16(rshiftRound(smulww(v, Gain_Q10), 8));
        }
    }
}
