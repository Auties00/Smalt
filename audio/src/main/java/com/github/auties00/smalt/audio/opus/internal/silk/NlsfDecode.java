/* Ports libopus 1.5.2 (silk/NLSF_decode.c, silk/NLSF_unpack.c). Copyright Skype Limited; BSD 3-Clause License.
   Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.silk;

import static com.github.auties00.smalt.audio.opus.internal.silk.Define.MAX_LPC_ORDER;
import static com.github.auties00.smalt.audio.opus.internal.silk.Define.NLSF_QUANT_LEVEL_ADJ;
import static com.github.auties00.smalt.audio.opus.internal.silk.Define.NLSF_QUANT_MAX_AMPLITUDE;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.fixConst;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.limit;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.smlawb;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.smulbb;

/**
 * Dequantizes NLSF vectors from their two-stage codebook indices and unpacks the entropy coding
 * parameters of a first-stage vector.
 */
public final class NlsfDecode {
    /**
     * The quantization level adjustment in Q10 ({@code SILK_FIX_CONST(NLSF_QUANT_LEVEL_ADJ, 10)}).
     */
    private static final int LEVEL_ADJ_Q10 = fixConst(NLSF_QUANT_LEVEL_ADJ, 10);

    private NlsfDecode() {
        throw new AssertionError();
    }

    /**
     * Unpacks the entropy table indices and the predictor of a first-stage vector
     * ({@code silk_NLSF_unpack}).
     *
     * @param ec_ix      receives the offset of the residual iCDF of each coefficient
     * @param pred_Q8    receives the backward predictor of each coefficient, in Q8
     * @param psNLSF_CB  the codebook
     * @param CB1_index  the first-stage index
     */
    public static void nlsfUnpack(short[] ec_ix, short[] pred_Q8, NlsfCodebook psNLSF_CB, int CB1_index) {
        var ec_sel_ptr = CB1_index * psNLSF_CB.order / 2;
        for (var i = 0; i < psNLSF_CB.order; i += 2) {
            var entry = psNLSF_CB.ec_sel[ec_sel_ptr++];
            ec_ix[i] = (short) smulbb((entry >> 1) & 7, 2 * NLSF_QUANT_MAX_AMPLITUDE + 1);
            pred_Q8[i] = psNLSF_CB.pred_Q8[i + (entry & 1) * (psNLSF_CB.order - 1)];
            ec_ix[i + 1] = (short) smulbb((entry >> 5) & 7, 2 * NLSF_QUANT_MAX_AMPLITUDE + 1);
            pred_Q8[i + 1] = psNLSF_CB.pred_Q8[i + ((entry >> 4) & 1) * (psNLSF_CB.order - 1) + 1];
        }
    }

    /**
     * Dequantizes the predictive NLSF residuals ({@code silk_NLSF_residual_dequant}).
     *
     * @param x_Q10               receives the residuals in Q10
     * @param indices             the codebook path vector; the residual indices start at index 1
     * @param pred_coef_Q8        the backward predictor coefficients in Q8
     * @param quant_step_size_Q16 the quantization step size in Q16
     * @param order               the number of coefficients
     */
    private static void nlsfResidualDequant(short[] x_Q10, byte[] indices, short[] pred_coef_Q8,
                                            int quant_step_size_Q16, int order) {
        var out_Q10 = 0;
        for (var i = order - 1; i >= 0; i--) {
            var pred_Q10 = smulbb(out_Q10, pred_coef_Q8[i]) >> 8;
            out_Q10 = indices[1 + i] << 10;
            if (out_Q10 > 0) {
                out_Q10 = out_Q10 - LEVEL_ADJ_Q10;
            } else if (out_Q10 < 0) {
                out_Q10 = out_Q10 + LEVEL_ADJ_Q10;
            }
            out_Q10 = smlawb(pred_Q10, out_Q10, quant_step_size_Q16);
            x_Q10[i] = (short) out_Q10;
        }
    }

    /**
     * Decodes an NLSF vector from its codebook path ({@code silk_NLSF_decode}).
     *
     * @param pNLSF_Q15   receives the stabilized NLSF vector in Q15
     * @param NLSFIndices the codebook path: the first-stage index, then the residual indices
     * @param psNLSF_CB   the codebook
     */
    public static void nlsfDecode(short[] pNLSF_Q15, byte[] NLSFIndices, NlsfCodebook psNLSF_CB) {
        nlsfDecode(pNLSF_Q15, NLSFIndices, psNLSF_CB, new short[MAX_LPC_ORDER], new short[MAX_LPC_ORDER],
                new short[MAX_LPC_ORDER]);
    }

    /**
     * Decodes an NLSF vector from its codebook path using caller-provided work arrays
     * ({@code silk_NLSF_decode}).
     *
     * @param pNLSF_Q15   receives the stabilized NLSF vector in Q15
     * @param NLSFIndices the codebook path: the first-stage index, then the residual indices
     * @param psNLSF_CB   the codebook
     * @param pred_Q8     a work array of {@link Define#MAX_LPC_ORDER} elements, overwritten
     * @param ec_ix       a work array of {@link Define#MAX_LPC_ORDER} elements, overwritten
     * @param res_Q10     a work array of {@link Define#MAX_LPC_ORDER} elements, overwritten
     */
    public static void nlsfDecode(short[] pNLSF_Q15, byte[] NLSFIndices, NlsfCodebook psNLSF_CB, short[] pred_Q8,
                                  short[] ec_ix, short[] res_Q10) {
        nlsfUnpack(ec_ix, pred_Q8, psNLSF_CB, NLSFIndices[0]);
        nlsfResidualDequant(res_Q10, NLSFIndices, pred_Q8, psNLSF_CB.quantStepSize_Q16, psNLSF_CB.order);
        var pCB_element = NLSFIndices[0] * psNLSF_CB.order;
        for (var i = 0; i < psNLSF_CB.order; i++) {
            var NLSF_Q15_tmp = (res_Q10[i] << 14) / psNLSF_CB.CB1_Wght_Q9[pCB_element + i]
                    + (psNLSF_CB.CB1_NLSF_Q8[pCB_element + i] << 7);
            pNLSF_Q15[i] = (short) limit(NLSF_Q15_tmp, 0, 32767);
        }
        NlsfStabilize.nlsfStabilize(pNLSF_Q15, psNLSF_CB.deltaMin_Q15, psNLSF_CB.order);
    }
}
