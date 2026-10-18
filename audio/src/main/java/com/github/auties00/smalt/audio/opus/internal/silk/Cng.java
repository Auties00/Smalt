/* Ports libopus 1.5.2 (silk/CNG.c). Copyright Skype Limited; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.silk;

import java.util.Arrays;

import static com.github.auties00.smalt.audio.opus.internal.silk.Define.CNG_BUF_MASK_MAX;
import static com.github.auties00.smalt.audio.opus.internal.silk.Define.CNG_GAIN_SMTH_Q16;
import static com.github.auties00.smalt.audio.opus.internal.silk.Define.CNG_GAIN_SMTH_THRESHOLD_Q16;
import static com.github.auties00.smalt.audio.opus.internal.silk.Define.CNG_NLSF_SMTH_Q16;
import static com.github.auties00.smalt.audio.opus.internal.silk.Define.MAX_LPC_ORDER;
import static com.github.auties00.smalt.audio.opus.internal.silk.Define.TYPE_NO_VOICE_ACTIVITY;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.addSat16;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.addSat32;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.lshiftSat32;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.rand;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.rshiftRound;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.sat16;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.smlawb;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.smultt;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.smulwb;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.smulww;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.sqrtApprox;

/**
 * Estimates the background noise during inactive frames and generates comfort noise during
 * packet loss.
 */
public final class Cng {
    private Cng() {
        throw new AssertionError();
    }

    /**
     * Generates the comfort noise excitation by drawing random samples from the excitation buffer
     * ({@code silk_CNG_exc}).
     *
     * @param exc_Q14     receives the excitation
     * @param excOff      the index of the first excitation sample
     * @param exc_buf_Q14 the buffer of past excitation
     * @param length      the number of samples
     * @param rand_seed   the seed of the generator
     * @return the updated seed
     */
    private static int cngExc(int[] exc_Q14, int excOff, int[] exc_buf_Q14, int length, int rand_seed) {
        var exc_mask = CNG_BUF_MASK_MAX;
        while (exc_mask > length) {
            exc_mask = exc_mask >> 1;
        }
        var seed = rand_seed;
        for (var i = 0; i < length; i++) {
            seed = rand(seed);
            var idx = (seed >> 24) & exc_mask;
            exc_Q14[excOff + i] = exc_buf_Q14[idx];
        }
        return seed;
    }

    /**
     * Resets the comfort noise generator ({@code silk_CNG_Reset}).
     *
     * @param psDec the channel state
     */
    public static void cngReset(DecoderState psDec) {
        var NLSF_step_Q15 = Short.MAX_VALUE / (psDec.LPC_order + 1);
        var NLSF_acc_Q15 = 0;
        for (var i = 0; i < psDec.LPC_order; i++) {
            NLSF_acc_Q15 += NLSF_step_Q15;
            psDec.sCNG.CNG_smth_NLSF_Q15[i] = (short) NLSF_acc_Q15;
        }
        psDec.sCNG.CNG_smth_Gain_Q16 = 0;
        psDec.sCNG.rand_seed = 3176576;
    }

    /**
     * Updates the noise estimate from inactive frames and adds comfort noise to lost frames
     * ({@code silk_CNG}).
     *
     * @param psDec     the channel state
     * @param psDecCtrl the parameters of the frame
     * @param frame     the signal, with comfort noise added in place during loss
     * @param frameOff  the index of the first sample
     * @param length    the number of samples
     */
    public static void cng(DecoderState psDec, DecoderControl psDecCtrl, short[] frame, int frameOff, int length) {
        var psCNG = psDec.sCNG;
        if (psDec.fs_kHz != psCNG.fs_kHz) {
            cngReset(psDec);
            psCNG.fs_kHz = psDec.fs_kHz;
        }
        if (psDec.lossCnt == 0 && psDec.prevSignalType == TYPE_NO_VOICE_ACTIVITY) {
            for (var i = 0; i < psDec.LPC_order; i++) {
                psCNG.CNG_smth_NLSF_Q15[i] = (short) (psCNG.CNG_smth_NLSF_Q15[i]
                        + smulwb(psDec.prevNLSF_Q15[i] - psCNG.CNG_smth_NLSF_Q15[i], CNG_NLSF_SMTH_Q16));
            }
            var max_Gain_Q16 = 0;
            var subfr = 0;
            for (var i = 0; i < psDec.nb_subfr; i++) {
                if (psDecCtrl.Gains_Q16[i] > max_Gain_Q16) {
                    max_Gain_Q16 = psDecCtrl.Gains_Q16[i];
                    subfr = i;
                }
            }
            System.arraycopy(psCNG.CNG_exc_buf_Q14, 0, psCNG.CNG_exc_buf_Q14, psDec.subfr_length,
                    (psDec.nb_subfr - 1) * psDec.subfr_length);
            System.arraycopy(psDec.exc_Q14, subfr * psDec.subfr_length, psCNG.CNG_exc_buf_Q14, 0, psDec.subfr_length);
            for (var i = 0; i < psDec.nb_subfr; i++) {
                psCNG.CNG_smth_Gain_Q16 += smulwb(psDecCtrl.Gains_Q16[i] - psCNG.CNG_smth_Gain_Q16, CNG_GAIN_SMTH_Q16);
                if (smulww(psCNG.CNG_smth_Gain_Q16, CNG_GAIN_SMTH_THRESHOLD_Q16) > psDecCtrl.Gains_Q16[i]) {
                    psCNG.CNG_smth_Gain_Q16 = psDecCtrl.Gains_Q16[i];
                }
            }
        }
        if (psDec.lossCnt != 0) {
            var CNG_sig_Q14 = psDec.scratchCngSig;
            var A_Q12 = psDec.scratchCngA_Q12;
            var gain_Q16 = smulww(psDec.sPLC.randScale_Q14, psDec.sPLC.prevGain_Q16[1]);
            if (gain_Q16 >= (1 << 21) || psCNG.CNG_smth_Gain_Q16 > (1 << 23)) {
                gain_Q16 = smultt(gain_Q16, gain_Q16);
                gain_Q16 = smultt(psCNG.CNG_smth_Gain_Q16, psCNG.CNG_smth_Gain_Q16) - (gain_Q16 << 5);
                gain_Q16 = sqrtApprox(gain_Q16) << 16;
            } else {
                gain_Q16 = smulww(gain_Q16, gain_Q16);
                gain_Q16 = smulww(psCNG.CNG_smth_Gain_Q16, psCNG.CNG_smth_Gain_Q16) - (gain_Q16 << 5);
                gain_Q16 = sqrtApprox(gain_Q16) << 8;
            }
            var gain_Q10 = gain_Q16 >> 6;
            psCNG.rand_seed = cngExc(CNG_sig_Q14, MAX_LPC_ORDER, psCNG.CNG_exc_buf_Q14, length, psCNG.rand_seed);
            Nlsf2A.nlsf2a(A_Q12, psCNG.CNG_smth_NLSF_Q15, psDec.LPC_order, psDec.scratchNlsf2A);
            System.arraycopy(psCNG.CNG_synth_state, 0, CNG_sig_Q14, 0, MAX_LPC_ORDER);
            int a0 = A_Q12[0];
            int a1 = A_Q12[1];
            int a2 = A_Q12[2];
            int a3 = A_Q12[3];
            int a4 = A_Q12[4];
            int a5 = A_Q12[5];
            int a6 = A_Q12[6];
            int a7 = A_Q12[7];
            int a8 = A_Q12[8];
            int a9 = A_Q12[9];
            for (var i = 0; i < length; i++) {
                var base = MAX_LPC_ORDER + i;
                var LPC_pred_Q10 = psDec.LPC_order >> 1;
                LPC_pred_Q10 = smlawb(LPC_pred_Q10, CNG_sig_Q14[base - 1], a0);
                LPC_pred_Q10 = smlawb(LPC_pred_Q10, CNG_sig_Q14[base - 2], a1);
                LPC_pred_Q10 = smlawb(LPC_pred_Q10, CNG_sig_Q14[base - 3], a2);
                LPC_pred_Q10 = smlawb(LPC_pred_Q10, CNG_sig_Q14[base - 4], a3);
                LPC_pred_Q10 = smlawb(LPC_pred_Q10, CNG_sig_Q14[base - 5], a4);
                LPC_pred_Q10 = smlawb(LPC_pred_Q10, CNG_sig_Q14[base - 6], a5);
                LPC_pred_Q10 = smlawb(LPC_pred_Q10, CNG_sig_Q14[base - 7], a6);
                LPC_pred_Q10 = smlawb(LPC_pred_Q10, CNG_sig_Q14[base - 8], a7);
                LPC_pred_Q10 = smlawb(LPC_pred_Q10, CNG_sig_Q14[base - 9], a8);
                LPC_pred_Q10 = smlawb(LPC_pred_Q10, CNG_sig_Q14[base - 10], a9);
                if (psDec.LPC_order == 16) {
                    LPC_pred_Q10 = smlawb(LPC_pred_Q10, CNG_sig_Q14[base - 11], A_Q12[10]);
                    LPC_pred_Q10 = smlawb(LPC_pred_Q10, CNG_sig_Q14[base - 12], A_Q12[11]);
                    LPC_pred_Q10 = smlawb(LPC_pred_Q10, CNG_sig_Q14[base - 13], A_Q12[12]);
                    LPC_pred_Q10 = smlawb(LPC_pred_Q10, CNG_sig_Q14[base - 14], A_Q12[13]);
                    LPC_pred_Q10 = smlawb(LPC_pred_Q10, CNG_sig_Q14[base - 15], A_Q12[14]);
                    LPC_pred_Q10 = smlawb(LPC_pred_Q10, CNG_sig_Q14[base - 16], A_Q12[15]);
                }
                CNG_sig_Q14[base] = addSat32(CNG_sig_Q14[base], lshiftSat32(LPC_pred_Q10, 4));
                frame[frameOff + i] = addSat16(frame[frameOff + i],
                        sat16(rshiftRound(smulww(CNG_sig_Q14[base], gain_Q10), 8)));
            }
            System.arraycopy(CNG_sig_Q14, length, psCNG.CNG_synth_state, 0, MAX_LPC_ORDER);
        } else {
            Arrays.fill(psCNG.CNG_synth_state, 0, psDec.LPC_order, 0);
        }
    }
}
