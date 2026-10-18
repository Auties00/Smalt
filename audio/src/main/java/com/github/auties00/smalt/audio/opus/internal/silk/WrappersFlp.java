/* Ports libopus 1.5.2 (silk/float/wrappers_FLP.c). Copyright Skype Limited; BSD 3-Clause License. Modified for Smalt.
   */
package com.github.auties00.smalt.audio.opus.internal.silk;

import static com.github.auties00.smalt.audio.opus.internal.silk.Define.*;

/**
 * Bridges the SILK float encoder and the fixed-point routines it shares with the decoder:
 * NLSF conversions and quantization, the noise-shaping quantizers and the LTP quantizer.
 */
final class WrappersFlp {
    private WrappersFlp() {
        throw new AssertionError();
    }

    /**
     * Converts float AR coefficients to NLSFs ({@code silk_A2NLSF_FLP}).
     *
     * @param NLSF_Q15  receives the NLSFs
     * @param pAR       the prediction coefficients
     * @param LPC_order the prediction order
     * @param a_fix_Q16 a scratch buffer of at least {@code LPC_order} values
     * @param scratch   the work arrays, overwritten
     */
    static void a2nlsfFlp(short[] NLSF_Q15, float[] pAR, int LPC_order, int[] a_fix_Q16, AnalysisScratch scratch) {
        for (var i = 0; i < LPC_order; i++) {
            a_fix_Q16[i] = SigProcFlp.float2int(pAR[i] * 65536.0f);
        }
        A2Nlsf.a2nlsf(NLSF_Q15, a_fix_Q16, LPC_order, scratch.a2nlsfP, scratch.a2nlsfQ);
    }

    /**
     * Converts NLSFs to float AR coefficients ({@code silk_NLSF2A_FLP}).
     *
     * @param pAR       receives the prediction coefficients
     * @param NLSF_Q15  the NLSFs
     * @param LPC_order the prediction order
     * @param a_fix_Q12 a scratch buffer of at least {@code LPC_order} values
     * @param scratch   the work arrays, overwritten
     */
    static void nlsf2aFlp(float[] pAR, short[] NLSF_Q15, int LPC_order, short[] a_fix_Q12, AnalysisScratch scratch) {
        Nlsf2A.nlsf2a(a_fix_Q12, NLSF_Q15, LPC_order, scratch.nlsf2a);
        for (var i = 0; i < LPC_order; i++) {
            pAR[i] = (float) a_fix_Q12[i] * (1.0f / 4096.0f);
        }
    }

    /**
     * Quantizes the NLSFs and computes the float prediction coefficients of both frame halves
     * ({@code silk_process_NLSFs_FLP}).
     *
     * @param psEnc         the channel state
     * @param PredCoef      receives the prediction coefficients of both frame halves
     * @param NLSF_Q15      the unquantized NLSFs, replaced by the quantized ones
     * @param prev_NLSF_Q15 the quantized NLSFs of the previous frame
     */
    static void processNlsfsFlp(EncoderStateFlp psEnc, float[][] PredCoef, short[] NLSF_Q15, short[] prev_NLSF_Q15) {
        var PredCoef_Q12 = psEnc.scratch.PredCoef_Q12;
        ProcessNlsfs.processNlsfs(psEnc.sCmn, psEnc.nlsfEncode, PredCoef_Q12, NLSF_Q15, prev_NLSF_Q15);
        for (var j = 0; j < 2; j++) {
            for (var i = 0; i < psEnc.sCmn.predictLPCOrder; i++) {
                PredCoef[j][i] = (float) PredCoef_Q12[j * MAX_LPC_ORDER + i] * (1.0f / 4096.0f);
            }
        }
    }

    /**
     * Converts the float control parameters to fixed point and runs the noise-shaping quantizer
     * ({@code silk_NSQ_wrapper_FLP}).
     *
     * @param psEnc     the channel state
     * @param psEncCtrl the frame control
     * @param psIndices the quantization indices
     * @param psNSQ     the quantizer state
     * @param pulses    receives the quantized pulses
     * @param x         the prefiltered input
     * @param xOff      the index of the first input sample
     */
    static void nsqWrapperFlp(EncoderStateFlp psEnc, EncoderControlFlp psEncCtrl, SideInfoIndices psIndices,
                              NsqState psNSQ, byte[] pulses, float[] x, int xOff) {
        int i, j;
        var s = psEnc.scratch;
        var sCmn = psEnc.sCmn;
        var x16 = s.x16;
        var Gains_Q16 = s.Gains_Q16;
        var PredCoef_Q12 = s.PredCoef_Q12;
        var LTPCoef_Q14 = s.LTPCoef_Q14;
        int LTP_scale_Q14;
        var AR_Q13 = s.AR_Q13;
        var LF_shp_Q14 = s.LF_shp_Q14;
        int Lambda_Q10;
        var Tilt_Q14 = s.Tilt_Q14;
        var HarmShapeGain_Q14 = s.HarmShapeGain_Q14;

        for (i = 0; i < sCmn.nb_subfr; i++) {
            for (j = 0; j < sCmn.shapingLPCOrder; j++) {
                AR_Q13[i * MAX_SHAPE_LPC_ORDER + j] = (short) SigProcFlp.float2int(psEncCtrl.AR[i * MAX_SHAPE_LPC_ORDER + j] * 8192.0f);
            }
        }

        for (i = 0; i < sCmn.nb_subfr; i++) {
            LF_shp_Q14[i] = (SigProcFlp.float2int(psEncCtrl.LF_AR_shp[i] * 16384.0f) << 16)
                    | (SigProcFlp.float2int(psEncCtrl.LF_MA_shp[i] * 16384.0f) & 0xFFFF);
            Tilt_Q14[i] = SigProcFlp.float2int(psEncCtrl.Tilt[i] * 16384.0f);
            HarmShapeGain_Q14[i] = SigProcFlp.float2int(psEncCtrl.HarmShapeGain[i] * 16384.0f);
        }
        Lambda_Q10 = SigProcFlp.float2int(psEncCtrl.Lambda * 1024.0f);

        for (i = 0; i < sCmn.nb_subfr * LTP_ORDER; i++) {
            LTPCoef_Q14[i] = (short) SigProcFlp.float2int(psEncCtrl.LTPCoef[i] * 16384.0f);
        }

        for (j = 0; j < 2; j++) {
            for (i = 0; i < sCmn.predictLPCOrder; i++) {
                PredCoef_Q12[j * MAX_LPC_ORDER + i] = (short) SigProcFlp.float2int(psEncCtrl.PredCoef[j][i] * 4096.0f);
            }
        }

        for (i = 0; i < sCmn.nb_subfr; i++) {
            Gains_Q16[i] = SigProcFlp.float2int(psEncCtrl.Gains[i] * 65536.0f);
        }

        if (psIndices.signalType == TYPE_VOICED) {
            LTP_scale_Q14 = Tables.silk_LTPScales_table_Q14[psIndices.LTP_scaleIndex];
        } else {
            LTP_scale_Q14 = 0;
        }

        for (i = 0; i < sCmn.frame_length; i++) {
            x16[i] = (short) SigProcFlp.float2int(x[xOff + i]);
        }

        if (sCmn.nStatesDelayedDecision > 1 || sCmn.warping_Q16 > 0) {
            psEnc.nsqDelDec.nsqDelDec(sCmn, psNSQ, psIndices, x16, 0, pulses, 0, PredCoef_Q12, LTPCoef_Q14, AR_Q13,
                    HarmShapeGain_Q14, Tilt_Q14, LF_shp_Q14, Gains_Q16, psEncCtrl.pitchL, Lambda_Q10, LTP_scale_Q14);
        } else {
            psEnc.nsq.nsq(sCmn, psNSQ, psIndices, x16, 0, pulses, 0, PredCoef_Q12, LTPCoef_Q14, AR_Q13,
                    HarmShapeGain_Q14, Tilt_Q14, LF_shp_Q14, Gains_Q16, psEncCtrl.pitchL, Lambda_Q10, LTP_scale_Q14);
        }
    }

    /**
     * Converts the LTP correlations to fixed point, quantizes the LTP gains and converts the
     * result back to float ({@code silk_quant_LTP_gains_FLP}).
     *
     * @param psEnc     the channel state; the LTP indices and {@code sum_log_gain_Q7} are
     *                  updated
     * @param psEncCtrl the frame control; {@code LTPCoef} and {@code LTPredCodGain} are written
     * @param XX        the correlation matrices
     * @param xX        the correlation vectors
     * @param subfr_len the subframe length
     * @param nb_subfr  the number of subframes
     */
    static void quantLtpGainsFlp(EncoderStateFlp psEnc, EncoderControlFlp psEncCtrl, float[] XX, float[] xX,
                                 int subfr_len, int nb_subfr) {
        int i;
        var s = psEnc.scratch;
        var XX_Q17 = s.XX_Q17;
        var xX_Q17 = s.xX_Q17;
        var B_Q14 = s.B_Q14;
        var indices = psEnc.sCmn.indices;

        i = 0;
        do {
            XX_Q17[i] = SigProcFlp.float2int(XX[i] * 131072.0f);
        } while (++i < nb_subfr * LTP_ORDER * LTP_ORDER);
        i = 0;
        do {
            xX_Q17[i] = SigProcFlp.float2int(xX[i] * 131072.0f);
        } while (++i < nb_subfr * LTP_ORDER);

        s.periodicityIndex[0] = indices.PERIndex;
        s.sumLogGain[0] = psEnc.sCmn.sum_log_gain_Q7;
        QuantLtpGains.quantLtpGains(B_Q14, indices.LTPIndex, s.periodicityIndex, s.sumLogGain, s.predGainDb, XX_Q17,
                xX_Q17, subfr_len, nb_subfr);
        indices.PERIndex = s.periodicityIndex[0];
        psEnc.sCmn.sum_log_gain_Q7 = s.sumLogGain[0];

        for (i = 0; i < nb_subfr * LTP_ORDER; i++) {
            psEncCtrl.LTPCoef[i] = (float) B_Q14[i] * (1.0f / 16384.0f);
        }

        psEncCtrl.LTPredCodGain = (float) s.predGainDb[0] * (1.0f / 128.0f);
    }
}
