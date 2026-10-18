/* Ports libopus 1.5.2 (silk/process_NLSFs.c). Copyright Skype Limited; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.silk;

import static com.github.auties00.smalt.audio.opus.internal.silk.Define.*;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.*;

/**
 * Weights, quantizes and interpolates the NLSFs of a SILK frame and converts them back to
 * prediction coefficients.
 */
final class ProcessNlsfs {
    private ProcessNlsfs() {
        throw new AssertionError();
    }

    /**
     * Limits, stabilizes, quantizes and converts the NLSFs of a frame ({@code silk_process_NLSFs}).
     *
     * @param psEncC         the encoder state; the NLSF indices are written
     * @param nlsfEncoder    the NLSF quantizer of the channel, whose scratch is reused
     * @param PredCoef_Q12   receives the prediction coefficients of both frame halves,
     *                       {@code 2 x MAX_LPC_ORDER}
     * @param pNLSF_Q15      the unquantized NLSFs, replaced by the quantized ones
     * @param prev_NLSFq_Q15 the quantized NLSFs of the previous frame
     */
    static void processNlsfs(EncoderState psEncC, NlsfEncode nlsfEncoder, short[] PredCoef_Q12, short[] pNLSF_Q15,
                             short[] prev_NLSFq_Q15) {
        int i;
        boolean doInterpolate;
        int NLSF_mu_Q20;
        short i_sqr_Q15;
        var pNLSF0_temp_Q15 = nlsfEncoder.pNLSF0_temp_Q15;
        var pNLSFW_QW = nlsfEncoder.pNLSFW_QW;
        var pNLSFW0_temp_QW = nlsfEncoder.pNLSFW0_temp_QW;
        var a_Q12 = nlsfEncoder.a_Q12;

        NLSF_mu_Q20 = smlawb(fixConst(0.003, 20), fixConst(-0.001, 28), psEncC.speech_activity_Q8);
        if (psEncC.nb_subfr == 2) {
            NLSF_mu_Q20 = NLSF_mu_Q20 + (NLSF_mu_Q20 >> 1);
        }

        NlsfEncode.nlsfVqWeightsLaroia(pNLSFW_QW, pNLSF_Q15, psEncC.predictLPCOrder);

        doInterpolate = psEncC.useInterpolatedNLSFs == 1 && psEncC.indices.NLSFInterpCoef_Q2 < 4;
        if (doInterpolate) {
            Interpolate.interpolate(pNLSF0_temp_Q15, prev_NLSFq_Q15, pNLSF_Q15,
                    psEncC.indices.NLSFInterpCoef_Q2, psEncC.predictLPCOrder);

            NlsfEncode.nlsfVqWeightsLaroia(pNLSFW0_temp_QW, pNLSF0_temp_Q15, psEncC.predictLPCOrder);

            i_sqr_Q15 = (short) (smulbb(psEncC.indices.NLSFInterpCoef_Q2, psEncC.indices.NLSFInterpCoef_Q2) << 11);
            for (i = 0; i < psEncC.predictLPCOrder; i++) {
                pNLSFW_QW[i] = (short) ((pNLSFW_QW[i] >> 1) + (smulbb(pNLSFW0_temp_QW[i], i_sqr_Q15) >> 16));
            }
        }

        nlsfEncoder.nlsfEncode(psEncC.indices.NLSFIndices, pNLSF_Q15, psEncC.psNLSF_CB, pNLSFW_QW, NLSF_mu_Q20,
                psEncC.NLSF_MSVQ_Survivors, psEncC.indices.signalType);

        Nlsf2A.nlsf2a(a_Q12, pNLSF_Q15, psEncC.predictLPCOrder, nlsfEncoder.nlsf2aScratch);
        System.arraycopy(a_Q12, 0, PredCoef_Q12, MAX_LPC_ORDER, psEncC.predictLPCOrder);

        if (doInterpolate) {
            Interpolate.interpolate(pNLSF0_temp_Q15, prev_NLSFq_Q15, pNLSF_Q15,
                    psEncC.indices.NLSFInterpCoef_Q2, psEncC.predictLPCOrder);

            Nlsf2A.nlsf2a(a_Q12, pNLSF0_temp_Q15, psEncC.predictLPCOrder, nlsfEncoder.nlsf2aScratch);
            System.arraycopy(a_Q12, 0, PredCoef_Q12, 0, psEncC.predictLPCOrder);
        } else {
            System.arraycopy(PredCoef_Q12, MAX_LPC_ORDER, PredCoef_Q12, 0, psEncC.predictLPCOrder);
        }
    }
}
