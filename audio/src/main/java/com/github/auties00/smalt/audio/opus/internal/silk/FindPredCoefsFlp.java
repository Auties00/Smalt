/* Ports libopus 1.5.2 (silk/float/find_pred_coefs_FLP.c, silk/float/find_LPC_FLP.c, silk/float/find_LTP_FLP.c,
   silk/float/LTP_scale_ctrl_FLP.c). Copyright Skype Limited; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.silk;

import java.util.Arrays;

import static com.github.auties00.smalt.audio.opus.internal.silk.Define.*;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.*;
import static com.github.auties00.smalt.audio.opus.internal.silk.TuningParameters.*;

/**
 * Finds and quantizes the long-term (LTP) and short-term (LPC) prediction parameters of a SILK
 * frame.
 */
final class FindPredCoefsFlp {
    private FindPredCoefsFlp() {
        throw new AssertionError();
    }

    /**
     * Finds the LPC and LTP coefficients of a frame ({@code silk_find_pred_coefs_FLP}).
     *
     * @param psEnc        the channel state
     * @param psEncCtrl    the frame control
     * @param res_pitch    the residual from the pitch analysis
     * @param resPitchOff  the index of the first residual sample of the frame
     * @param x            the analysis buffer
     * @param xOff         the index of the first sample of the frame to encode
     * @param condCoding   the type of conditional coding
     */
    static void findPredCoefs(EncoderStateFlp psEnc, EncoderControlFlp psEncCtrl, float[] res_pitch, int resPitchOff,
                              float[] x, int xOff, int condCoding) {
        int i;
        var s = psEnc.scratch;
        var sCmn = psEnc.sCmn;
        var XXLTP = s.XXLTP;
        var xXLTP = s.xXLTP;
        var invGains = s.invGains;
        var NLSF_Q15 = s.NLSF_Q15;
        var LPC_in_pre = s.LPC_in_pre;
        float minInvGain;

        Arrays.fill(NLSF_Q15, (short) 0);

        for (i = 0; i < sCmn.nb_subfr; i++) {
            invGains[i] = 1.0f / psEncCtrl.Gains[i];
        }

        if (sCmn.indices.signalType == TYPE_VOICED) {
            findLtp(XXLTP, xXLTP, res_pitch, resPitchOff, psEncCtrl.pitchL, sCmn.subfr_length, sCmn.nb_subfr);

            WrappersFlp.quantLtpGainsFlp(psEnc, psEncCtrl, XXLTP, xXLTP, sCmn.subfr_length, sCmn.nb_subfr);

            ltpScaleCtrl(psEnc, psEncCtrl, condCoding);

            SigProcFlp.ltpAnalysisFilterFlp(LPC_in_pre, x, xOff - sCmn.predictLPCOrder, psEncCtrl.LTPCoef,
                    psEncCtrl.pitchL, invGains, sCmn.subfr_length, sCmn.nb_subfr, sCmn.predictLPCOrder);
        } else {
            var x_ptr = xOff - sCmn.predictLPCOrder;
            var x_pre_ptr = 0;
            for (i = 0; i < sCmn.nb_subfr; i++) {
                SigProcFlp.scaleCopyVectorFlp(LPC_in_pre, x_pre_ptr, x, x_ptr, invGains[i],
                        sCmn.subfr_length + sCmn.predictLPCOrder);
                x_pre_ptr += sCmn.subfr_length + sCmn.predictLPCOrder;
                x_ptr += sCmn.subfr_length;
            }
            Arrays.fill(psEncCtrl.LTPCoef, 0, sCmn.nb_subfr * LTP_ORDER, 0f);
            psEncCtrl.LTPredCodGain = 0.0f;
            sCmn.sum_log_gain_Q7 = 0;
        }

        if (sCmn.first_frame_after_reset != 0) {
            minInvGain = 1.0f / MAX_PREDICTION_POWER_GAIN_AFTER_RESET;
        } else {
            minInvGain = (float) StrictMath.pow(2, psEncCtrl.LTPredCodGain / 3) / MAX_PREDICTION_POWER_GAIN;
            minInvGain /= 0.25f + 0.75f * psEncCtrl.coding_quality;
        }

        findLpc(psEnc, NLSF_Q15, LPC_in_pre, minInvGain);

        WrappersFlp.processNlsfsFlp(psEnc, psEncCtrl.PredCoef, NLSF_Q15, sCmn.prev_NLSFq_Q15);

        SigProcFlp.residualEnergyFlp(psEncCtrl.ResNrg, LPC_in_pre, psEncCtrl.PredCoef, psEncCtrl.Gains,
                sCmn.subfr_length, sCmn.nb_subfr, sCmn.predictLPCOrder, s.resNrgLpcRes);

        System.arraycopy(NLSF_Q15, 0, sCmn.prev_NLSFq_Q15, 0, MAX_LPC_ORDER);
    }

    /**
     * Estimates the LPC coefficients of a frame and converts them to NLSFs, searching the best
     * interpolation with the previous frame ({@code silk_find_LPC_FLP}).
     *
     * @param psEnc      the channel state; {@code indices.NLSFInterpCoef_Q2} is written
     * @param NLSF_Q15   receives the NLSFs
     * @param x          the input, each subframe preceded by {@code predictLPCOrder} samples
     * @param minInvGain the inverse of the maximum prediction gain
     */
    static void findLpc(EncoderStateFlp psEnc, short[] NLSF_Q15, float[] x, float minInvGain) {
        int k, subfr_length;
        var psEncC = psEnc.sCmn;
        var s = psEnc.scratch;
        var a = s.lpc_a;
        float res_nrg, res_nrg_2nd, res_nrg_interp;
        var NLSF0_Q15 = s.NLSF0_Q15;
        var a_tmp = s.lpc_a_tmp;
        var LPC_res = s.LPC_res;

        subfr_length = psEncC.subfr_length + psEncC.predictLPCOrder;

        psEncC.indices.NLSFInterpCoef_Q2 = 4;

        res_nrg = SigProcFlp.burgModifiedFlp(a, 0, x, 0, minInvGain, subfr_length, psEncC.nb_subfr, psEncC.predictLPCOrder,
                s);

        if (psEncC.useInterpolatedNLSFs != 0 && psEncC.first_frame_after_reset == 0 && psEncC.nb_subfr == MAX_NB_SUBFR) {
            res_nrg -= SigProcFlp.burgModifiedFlp(a_tmp, 0, x, (MAX_NB_SUBFR / 2) * subfr_length, minInvGain, subfr_length,
                    MAX_NB_SUBFR / 2, psEncC.predictLPCOrder, s);

            WrappersFlp.a2nlsfFlp(NLSF_Q15, a_tmp, psEncC.predictLPCOrder, s.a_fix_Q16, s);

            res_nrg_2nd = Float.MAX_VALUE;
            for (k = 3; k >= 0; k--) {
                Interpolate.interpolate(NLSF0_Q15, psEncC.prev_NLSFq_Q15, NLSF_Q15, k, psEncC.predictLPCOrder);

                WrappersFlp.nlsf2aFlp(a_tmp, NLSF0_Q15, psEncC.predictLPCOrder, s.a_fix_Q12, s);

                SigProcFlp.lpcAnalysisFilterFlp(LPC_res, 0, a_tmp, 0, x, 0, 2 * subfr_length, psEncC.predictLPCOrder);
                res_nrg_interp = (float) (
                        SigProcFlp.energyFlp(LPC_res, psEncC.predictLPCOrder, subfr_length - psEncC.predictLPCOrder)
                                + SigProcFlp.energyFlp(LPC_res, psEncC.predictLPCOrder + subfr_length, subfr_length - psEncC.predictLPCOrder));

                if (res_nrg_interp < res_nrg) {
                    res_nrg = res_nrg_interp;
                    psEncC.indices.NLSFInterpCoef_Q2 = (byte) k;
                } else if (res_nrg_interp > res_nrg_2nd) {
                    break;
                }
                res_nrg_2nd = res_nrg_interp;
            }
        }

        if (psEncC.indices.NLSFInterpCoef_Q2 == 4) {
            WrappersFlp.a2nlsfFlp(NLSF_Q15, a, psEncC.predictLPCOrder, s.a_fix_Q16, s);
        }
    }

    /**
     * Computes the weighted LTP correlation matrices and vectors of every subframe
     * ({@code silk_find_LTP_FLP}).
     *
     * @param XX          receives the correlation matrices, {@code LTP_ORDER x LTP_ORDER} per
     *                    subframe
     * @param xX          receives the correlation vectors, {@code LTP_ORDER} per subframe
     * @param r           the LPC residual
     * @param rOff        the index of the first residual sample of the frame
     * @param lag         the LTP lag of each subframe
     * @param subfr_length the subframe length
     * @param nb_subfr    the number of subframes
     */
    static void findLtp(float[] XX, float[] xX, float[] r, int rOff, int[] lag, int subfr_length, int nb_subfr) {
        int k;
        float xx, temp;
        var xX_ptr = 0;
        var XX_ptr = 0;
        var r_ptr = rOff;
        for (k = 0; k < nb_subfr; k++) {
            var lag_ptr = r_ptr - (lag[k] + LTP_ORDER / 2);
            SigProcFlp.corrMatrixFlp(r, lag_ptr, subfr_length, LTP_ORDER, XX, XX_ptr);
            SigProcFlp.corrVectorFlp(r, lag_ptr, r, r_ptr, subfr_length, LTP_ORDER, xX, xX_ptr);
            xx = (float) SigProcFlp.energyFlp(r, r_ptr, subfr_length + LTP_ORDER);
            var bound = LTP_CORR_INV_MAX * 0.5f * (XX[XX_ptr] + XX[XX_ptr + 24]) + 1.0f;
            temp = 1.0f / (xx > bound ? xx : bound);
            SigProcFlp.scaleVectorFlp(XX, XX_ptr, temp, LTP_ORDER * LTP_ORDER);
            SigProcFlp.scaleVectorFlp(xX, xX_ptr, temp, LTP_ORDER);

            r_ptr += subfr_length;
            XX_ptr += LTP_ORDER * LTP_ORDER;
            xX_ptr += LTP_ORDER;
        }
    }

    /**
     * Chooses the LTP state scaling of a frame from the expected packet loss
     * ({@code silk_LTP_scale_ctrl_FLP}).
     *
     * @param psEnc      the channel state; {@code indices.LTP_scaleIndex} is written
     * @param psEncCtrl  the frame control; {@code LTP_scale} is written
     * @param condCoding the type of conditional coding
     */
    static void ltpScaleCtrl(EncoderStateFlp psEnc, EncoderControlFlp psEncCtrl, int condCoding) {
        int round_loss;
        var sCmn = psEnc.sCmn;

        if (condCoding == CODE_INDEPENDENTLY) {
            round_loss = sCmn.PacketLoss_perc * sCmn.nFramesPerPacket;
            if (sCmn.LBRR_flag != 0) {
                round_loss = 2 + smulbb(round_loss, round_loss) / 100;
            }
            var predGain = (int) psEncCtrl.LTPredCodGain;
            sCmn.indices.LTP_scaleIndex = (byte) (smulbb(predGain, round_loss) > LinLog.log2lin(2900 - sCmn.SNR_dB_Q7) ? 1 : 0);
            sCmn.indices.LTP_scaleIndex += (byte) (smulbb(predGain, round_loss) > LinLog.log2lin(3900 - sCmn.SNR_dB_Q7) ? 1 : 0);
        } else {
            sCmn.indices.LTP_scaleIndex = 0;
        }

        psEncCtrl.LTP_scale = (float) Tables.silk_LTPScales_table_Q14[sCmn.indices.LTP_scaleIndex] / 16384.0f;
    }
}
