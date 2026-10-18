/* Ports libopus 1.5.2 (silk/decode_parameters.c). Copyright Skype Limited; BSD 3-Clause License. Modified for Smalt.
   */
package com.github.auties00.smalt.audio.opus.internal.silk;

import java.util.Arrays;

import static com.github.auties00.smalt.audio.opus.internal.silk.Define.BWE_AFTER_LOSS_Q16;
import static com.github.auties00.smalt.audio.opus.internal.silk.Define.CODE_CONDITIONALLY;
import static com.github.auties00.smalt.audio.opus.internal.silk.Define.LTP_ORDER;
import static com.github.auties00.smalt.audio.opus.internal.silk.Define.TYPE_VOICED;

/**
 * Dequantizes the parameters of a SILK frame from its side information indices.
 */
public final class DecodeParameters {
    private DecodeParameters() {
        throw new AssertionError();
    }

    /**
     * Computes the gains, LPC coefficients, pitch lags and LTP coefficients of a frame
     * ({@code silk_decode_parameters}).
     *
     * @param psDec      the channel state
     * @param psDecCtrl  receives the parameters
     * @param condCoding the conditional coding type
     */
    public static void decodeParameters(DecoderState psDec, DecoderControl psDecCtrl, int condCoding) {
        var pNLSF_Q15 = psDec.scratchNLSF_Q15;
        var pNLSF0_Q15 = psDec.scratchNLSF0_Q15;
        var indices = psDec.indices;
        psDec.LastGainIndex = GainQuant.gainsDequant(psDecCtrl.Gains_Q16, indices.GainsIndices, psDec.LastGainIndex,
                condCoding == CODE_CONDITIONALLY ? 1 : 0, psDec.nb_subfr);
        NlsfDecode.nlsfDecode(pNLSF_Q15, indices.NLSFIndices, psDec.psNLSF_CB, psDec.scratchPred_Q8, psDec.scratchEc_ix,
                psDec.scratchRes_Q10);
        Nlsf2A.nlsf2a(psDecCtrl.PredCoef_Q12[1], pNLSF_Q15, psDec.LPC_order, psDec.scratchNlsf2A);
        if (psDec.first_frame_after_reset == 1) {
            indices.NLSFInterpCoef_Q2 = 4;
        }
        if (indices.NLSFInterpCoef_Q2 < 4) {
            for (var i = 0; i < psDec.LPC_order; i++) {
                pNLSF0_Q15[i] = (short) (psDec.prevNLSF_Q15[i]
                        + ((indices.NLSFInterpCoef_Q2 * (pNLSF_Q15[i] - psDec.prevNLSF_Q15[i])) >> 2));
            }
            Nlsf2A.nlsf2a(psDecCtrl.PredCoef_Q12[0], pNLSF0_Q15, psDec.LPC_order, psDec.scratchNlsf2A);
        } else {
            System.arraycopy(psDecCtrl.PredCoef_Q12[1], 0, psDecCtrl.PredCoef_Q12[0], 0, psDec.LPC_order);
        }
        System.arraycopy(pNLSF_Q15, 0, psDec.prevNLSF_Q15, 0, psDec.LPC_order);
        if (psDec.lossCnt != 0) {
            Bwexpander.bwexpander(psDecCtrl.PredCoef_Q12[0], psDec.LPC_order, BWE_AFTER_LOSS_Q16);
            Bwexpander.bwexpander(psDecCtrl.PredCoef_Q12[1], psDec.LPC_order, BWE_AFTER_LOSS_Q16);
        }
        if (indices.signalType == TYPE_VOICED) {
            DecodePitch.decodePitch(indices.lagIndex, indices.contourIndex, psDecCtrl.pitchL, psDec.fs_kHz,
                    psDec.nb_subfr);
            var cbk_ptr_Q7 = Tables.silk_LTP_vq_ptrs_Q7[indices.PERIndex];
            for (var k = 0; k < psDec.nb_subfr; k++) {
                var Ix = indices.LTPIndex[k];
                for (var i = 0; i < LTP_ORDER; i++) {
                    psDecCtrl.LTPCoef_Q14[k * LTP_ORDER + i] = (short) (cbk_ptr_Q7[Ix * LTP_ORDER + i] << 7);
                }
            }
            psDecCtrl.LTP_scale_Q14 = Tables.silk_LTPScales_table_Q14[indices.LTP_scaleIndex];
        } else {
            Arrays.fill(psDecCtrl.pitchL, 0, psDec.nb_subfr, 0);
            Arrays.fill(psDecCtrl.LTPCoef_Q14, 0, LTP_ORDER * psDec.nb_subfr, (short) 0);
            indices.PERIndex = 0;
            psDecCtrl.LTP_scale_Q14 = 0;
        }
    }
}
