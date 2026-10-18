/* Ports libopus 1.5.2 (silk/NSQ.c, silk/NSQ.h). Copyright Skype Limited; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.silk;

import static com.github.auties00.smalt.audio.opus.internal.silk.Define.*;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.*;

/**
 * Quantizes the SILK excitation with noise shaping, one decision per sample.
 *
 * <p>An instance holds the scratch buffers of one encoder channel, so quantizing a frame
 * allocates nothing.
 */
final class Nsq {
    /**
     * The LTP state matching the scaled input, {@code ltp_mem_length + frame_length} values.
     */
    private final int[] sLTP_Q15 = new int[2 * MAX_FRAME_LENGTH];

    /**
     * The re-whitened LTP state in Q0.
     */
    private final short[] sLTP = new short[2 * MAX_FRAME_LENGTH];

    /**
     * The input of the current subframe scaled by the inverse gain.
     */
    private final int[] x_sc_Q10 = new int[MAX_SUB_FRAME_LENGTH];

    /**
     * Creates the quantizer scratch.
     */
    Nsq() {
    }

    /**
     * Returns the short-term prediction of the next sample ({@code silk_noise_shape_quantizer_short_prediction_c}).
     *
     * @param buf32  the prediction state
     * @param bufOff the index of the newest state sample
     * @param coef16 the prediction coefficients in Q12
     * @param coefOff the index of the first coefficient
     * @param order  the prediction order, 10 or 16
     * @return the prediction in Q10
     */
    static int shortPrediction(int[] buf32, int bufOff, short[] coef16, int coefOff, int order) {
        var out = order >> 1;
        out = smlawb(out, buf32[bufOff], coef16[coefOff]);
        out = smlawb(out, buf32[bufOff - 1], coef16[coefOff + 1]);
        out = smlawb(out, buf32[bufOff - 2], coef16[coefOff + 2]);
        out = smlawb(out, buf32[bufOff - 3], coef16[coefOff + 3]);
        out = smlawb(out, buf32[bufOff - 4], coef16[coefOff + 4]);
        out = smlawb(out, buf32[bufOff - 5], coef16[coefOff + 5]);
        out = smlawb(out, buf32[bufOff - 6], coef16[coefOff + 6]);
        out = smlawb(out, buf32[bufOff - 7], coef16[coefOff + 7]);
        out = smlawb(out, buf32[bufOff - 8], coef16[coefOff + 8]);
        out = smlawb(out, buf32[bufOff - 9], coef16[coefOff + 9]);
        if (order == 16) {
            out = smlawb(out, buf32[bufOff - 10], coef16[coefOff + 10]);
            out = smlawb(out, buf32[bufOff - 11], coef16[coefOff + 11]);
            out = smlawb(out, buf32[bufOff - 12], coef16[coefOff + 12]);
            out = smlawb(out, buf32[bufOff - 13], coef16[coefOff + 13]);
            out = smlawb(out, buf32[bufOff - 14], coef16[coefOff + 14]);
            out = smlawb(out, buf32[bufOff - 15], coef16[coefOff + 15]);
        }
        return out;
    }

    /**
     * Runs the noise-shaping feedback filter for one sample and shifts its state
     * ({@code silk_NSQ_noise_shape_feedback_loop_c}).
     *
     * @param data0   the newest shaping input sample
     * @param data1   the filter state, updated in place
     * @param coef    the shaping coefficients in Q13
     * @param coefOff the index of the first coefficient
     * @param order   the filter order, even
     * @return the feedback in Q12
     */
    static int noiseShapeFeedbackLoop(int data0, int[] data1, short[] coef, int coefOff, int order) {
        var tmp2 = data0;
        var tmp1 = data1[0];
        data1[0] = tmp2;
        var out = order >> 1;
        out = smlawb(out, tmp2, coef[coefOff]);
        for (var j = 2; j < order; j += 2) {
            tmp2 = data1[j - 1];
            data1[j - 1] = tmp1;
            out = smlawb(out, tmp1, coef[coefOff + j - 1]);
            tmp1 = data1[j];
            data1[j] = tmp2;
            out = smlawb(out, tmp2, coef[coefOff + j]);
        }
        data1[order - 1] = tmp1;
        out = smlawb(out, tmp1, coef[coefOff + order - 1]);
        return out << 1;
    }

    /**
     * Quantizes one frame of excitation ({@code silk_NSQ_c}).
     *
     * @param psEncC            the encoder state
     * @param nsq               the quantizer state, updated
     * @param psIndices         the quantization indices; {@code Seed} is read
     * @param x16               the input
     * @param x16Off            the index of the first input sample
     * @param pulses            receives the quantized pulses
     * @param pulsesOff         the index of the first pulse
     * @param predCoef_Q12      the short-term prediction coefficients of both frame halves,
     *                          {@code 2 x MAX_LPC_ORDER}
     * @param LTPCoef_Q14       the long-term prediction coefficients
     * @param AR_Q13            the noise-shaping coefficients
     * @param HarmShapeGain_Q14 the long-term shaping gains
     * @param Tilt_Q14          the spectral tilts
     * @param LF_shp_Q14        the packed low-frequency shaping coefficients
     * @param Gains_Q16         the quantization step sizes
     * @param pitchL            the pitch lags
     * @param Lambda_Q10        the rate/distortion trade-off
     * @param LTP_scale_Q14     the LTP state scaling
     */
    void nsq(EncoderState psEncC, NsqState nsq, SideInfoIndices psIndices, short[] x16, int x16Off, byte[] pulses,
             int pulsesOff, short[] predCoef_Q12, short[] LTPCoef_Q14, short[] AR_Q13, int[] HarmShapeGain_Q14,
             int[] Tilt_Q14, int[] LF_shp_Q14, int[] Gains_Q16, int[] pitchL, int Lambda_Q10, int LTP_scale_Q14) {
        int k, lag, start_idx, LSF_interpolation_flag;
        int HarmShapeFIRPacked_Q14;
        int offset_Q10;

        nsq.rand_seed = psIndices.Seed;
        lag = nsq.lagPrev;
        offset_Q10 = Tables.silk_Quantization_Offsets_Q10[psIndices.signalType >> 1][psIndices.quantOffsetType];
        if (psIndices.NLSFInterpCoef_Q2 == 4) {
            LSF_interpolation_flag = 0;
        } else {
            LSF_interpolation_flag = 1;
        }

        nsq.sLTP_shp_buf_idx = psEncC.ltp_mem_length;
        nsq.sLTP_buf_idx = psEncC.ltp_mem_length;
        var pxq = psEncC.ltp_mem_length;
        for (k = 0; k < psEncC.nb_subfr; k++) {
            var aQ12Off = ((k >> 1) | (1 - LSF_interpolation_flag)) * MAX_LPC_ORDER;
            var bQ14Off = k * LTP_ORDER;
            var arShpOff = k * MAX_SHAPE_LPC_ORDER;

            HarmShapeFIRPacked_Q14 = HarmShapeGain_Q14[k] >> 2;
            HarmShapeFIRPacked_Q14 |= (HarmShapeGain_Q14[k] >> 1) << 16;

            nsq.rewhite_flag = 0;
            if (psIndices.signalType == TYPE_VOICED) {
                lag = pitchL[k];
                if ((k & (3 - (LSF_interpolation_flag << 1))) == 0) {
                    start_idx = psEncC.ltp_mem_length - lag - psEncC.predictLPCOrder - LTP_ORDER / 2;
                    LpcAnalysisFilter.lpcAnalysisFilter(sLTP, start_idx, nsq.xq, start_idx + k * psEncC.subfr_length,
                            predCoef_Q12, aQ12Off, psEncC.ltp_mem_length - start_idx, psEncC.predictLPCOrder);
                    nsq.rewhite_flag = 1;
                    nsq.sLTP_buf_idx = psEncC.ltp_mem_length;
                }
            }

            scaleStates(psEncC, nsq, x16, x16Off, k, LTP_scale_Q14, Gains_Q16, pitchL, psIndices.signalType);

            noiseShapeQuantizer(nsq, psIndices.signalType, pulses, pulsesOff, pxq, predCoef_Q12, aQ12Off, LTPCoef_Q14,
                    bQ14Off, AR_Q13, arShpOff, lag, HarmShapeFIRPacked_Q14, Tilt_Q14[k], LF_shp_Q14[k], Gains_Q16[k],
                    Lambda_Q10, offset_Q10, psEncC.subfr_length, psEncC.shapingLPCOrder, psEncC.predictLPCOrder);

            x16Off += psEncC.subfr_length;
            pulsesOff += psEncC.subfr_length;
            pxq += psEncC.subfr_length;
        }

        nsq.lagPrev = pitchL[psEncC.nb_subfr - 1];

        System.arraycopy(nsq.xq, psEncC.frame_length, nsq.xq, 0, psEncC.ltp_mem_length);
        System.arraycopy(nsq.sLTP_shp_Q14, psEncC.frame_length, nsq.sLTP_shp_Q14, 0, psEncC.ltp_mem_length);
    }

    /**
     * Quantizes one subframe ({@code silk_noise_shape_quantizer}).
     *
     * @param nsq                    the quantizer state
     * @param signalType             the signal type
     * @param pulses                 receives the pulses
     * @param pulsesOff              the index of the first pulse of the subframe
     * @param xqOff                  the index in {@code nsq.xq} of the first output sample
     * @param a_Q12                  the short-term prediction coefficients
     * @param aOff                   the index of the first short-term coefficient
     * @param b_Q14                  the long-term prediction coefficients
     * @param bOff                   the index of the first long-term coefficient
     * @param AR_shp_Q13             the noise-shaping coefficients
     * @param arOff                  the index of the first shaping coefficient
     * @param lag                    the pitch lag
     * @param HarmShapeFIRPacked_Q14 the packed harmonic shaping filter
     * @param Tilt_Q14               the spectral tilt
     * @param LF_shp_Q14             the packed low-frequency shaping coefficients
     * @param Gain_Q16               the quantization step size
     * @param Lambda_Q10             the rate/distortion trade-off
     * @param offset_Q10             the quantization offset
     * @param length                 the subframe length
     * @param shapingLPCOrder        the noise-shaping filter order
     * @param predictLPCOrder        the prediction filter order
     */
    private void noiseShapeQuantizer(NsqState nsq, int signalType, byte[] pulses, int pulsesOff, int xqOff,
                                     short[] a_Q12, int aOff, short[] b_Q14, int bOff, short[] AR_shp_Q13, int arOff,
                                     int lag, int HarmShapeFIRPacked_Q14, int Tilt_Q14, int LF_shp_Q14, int Gain_Q16,
                                     int Lambda_Q10, int offset_Q10, int length, int shapingLPCOrder,
                                     int predictLPCOrder) {
        int i;
        int LTP_pred_Q13, LPC_pred_Q10, n_AR_Q12, n_LTP_Q13;
        int n_LF_Q12, r_Q10, rr_Q10, q1_Q0, q1_Q10, q2_Q10, rd1_Q20, rd2_Q20;
        int exc_Q14, LPC_exc_Q14, xq_Q14, Gain_Q10;
        int tmp1, tmp2, sLF_AR_shp_Q14;
        var sLTP_Q15 = this.sLTP_Q15;
        var x_sc_Q10 = this.x_sc_Q10;
        var sLTP_shp_Q14 = nsq.sLTP_shp_Q14;
        var sLPC_Q14 = nsq.sLPC_Q14;
        var xq = nsq.xq;

        var shp_lag_ptr = nsq.sLTP_shp_buf_idx - lag + HARM_SHAPE_FIR_TAPS / 2;
        var pred_lag_ptr = nsq.sLTP_buf_idx - lag + LTP_ORDER / 2;
        Gain_Q10 = Gain_Q16 >> 6;

        var psLPC_Q14 = NSQ_LPC_BUF_LENGTH - 1;

        for (i = 0; i < length; i++) {
            nsq.rand_seed = rand(nsq.rand_seed);

            LPC_pred_Q10 = shortPrediction(sLPC_Q14, psLPC_Q14, a_Q12, aOff, predictLPCOrder);

            if (signalType == TYPE_VOICED) {
                LTP_pred_Q13 = 2;
                LTP_pred_Q13 = smlawb(LTP_pred_Q13, sLTP_Q15[pred_lag_ptr], b_Q14[bOff]);
                LTP_pred_Q13 = smlawb(LTP_pred_Q13, sLTP_Q15[pred_lag_ptr - 1], b_Q14[bOff + 1]);
                LTP_pred_Q13 = smlawb(LTP_pred_Q13, sLTP_Q15[pred_lag_ptr - 2], b_Q14[bOff + 2]);
                LTP_pred_Q13 = smlawb(LTP_pred_Q13, sLTP_Q15[pred_lag_ptr - 3], b_Q14[bOff + 3]);
                LTP_pred_Q13 = smlawb(LTP_pred_Q13, sLTP_Q15[pred_lag_ptr - 4], b_Q14[bOff + 4]);
                pred_lag_ptr++;
            } else {
                LTP_pred_Q13 = 0;
            }

            n_AR_Q12 = noiseShapeFeedbackLoop(nsq.sDiff_shp_Q14, nsq.sAR2_Q14, AR_shp_Q13, arOff, shapingLPCOrder);
            n_AR_Q12 = smlawb(n_AR_Q12, nsq.sLF_AR_shp_Q14, Tilt_Q14);

            n_LF_Q12 = smulwb(sLTP_shp_Q14[nsq.sLTP_shp_buf_idx - 1], LF_shp_Q14);
            n_LF_Q12 = smlawt(n_LF_Q12, nsq.sLF_AR_shp_Q14, LF_shp_Q14);

            tmp1 = (LPC_pred_Q10 << 2) - n_AR_Q12;
            tmp1 = tmp1 - n_LF_Q12;
            if (lag > 0) {
                n_LTP_Q13 = smulwb(addSat32(sLTP_shp_Q14[shp_lag_ptr], sLTP_shp_Q14[shp_lag_ptr - 2]), HarmShapeFIRPacked_Q14);
                n_LTP_Q13 = smlawt(n_LTP_Q13, sLTP_shp_Q14[shp_lag_ptr - 1], HarmShapeFIRPacked_Q14);
                n_LTP_Q13 = n_LTP_Q13 << 1;
                shp_lag_ptr++;

                tmp2 = LTP_pred_Q13 - n_LTP_Q13;
                tmp1 = tmp2 + (tmp1 << 1);
                tmp1 = rshiftRound(tmp1, 3);
            } else {
                tmp1 = rshiftRound(tmp1, 2);
            }

            r_Q10 = x_sc_Q10[i] - tmp1;

            if (nsq.rand_seed < 0) {
                r_Q10 = -r_Q10;
            }
            r_Q10 = limit(r_Q10, -(31 << 10), 30 << 10);

            q1_Q10 = r_Q10 - offset_Q10;
            q1_Q0 = q1_Q10 >> 10;
            if (Lambda_Q10 > 2048) {
                var rdo_offset = Lambda_Q10 / 2 - 512;
                if (q1_Q10 > rdo_offset) {
                    q1_Q0 = (q1_Q10 - rdo_offset) >> 10;
                } else if (q1_Q10 < -rdo_offset) {
                    q1_Q0 = (q1_Q10 + rdo_offset) >> 10;
                } else if (q1_Q10 < 0) {
                    q1_Q0 = -1;
                } else {
                    q1_Q0 = 0;
                }
            }
            if (q1_Q0 > 0) {
                q1_Q10 = (q1_Q0 << 10) - QUANT_LEVEL_ADJUST_Q10;
                q1_Q10 = q1_Q10 + offset_Q10;
                q2_Q10 = q1_Q10 + 1024;
                rd1_Q20 = smulbb(q1_Q10, Lambda_Q10);
                rd2_Q20 = smulbb(q2_Q10, Lambda_Q10);
            } else if (q1_Q0 == 0) {
                q1_Q10 = offset_Q10;
                q2_Q10 = q1_Q10 + (1024 - QUANT_LEVEL_ADJUST_Q10);
                rd1_Q20 = smulbb(q1_Q10, Lambda_Q10);
                rd2_Q20 = smulbb(q2_Q10, Lambda_Q10);
            } else if (q1_Q0 == -1) {
                q2_Q10 = offset_Q10;
                q1_Q10 = q2_Q10 - (1024 - QUANT_LEVEL_ADJUST_Q10);
                rd1_Q20 = smulbb(-q1_Q10, Lambda_Q10);
                rd2_Q20 = smulbb(q2_Q10, Lambda_Q10);
            } else {
                q1_Q10 = (q1_Q0 << 10) + QUANT_LEVEL_ADJUST_Q10;
                q1_Q10 = q1_Q10 + offset_Q10;
                q2_Q10 = q1_Q10 + 1024;
                rd1_Q20 = smulbb(-q1_Q10, Lambda_Q10);
                rd2_Q20 = smulbb(-q2_Q10, Lambda_Q10);
            }
            rr_Q10 = r_Q10 - q1_Q10;
            rd1_Q20 = smlabb(rd1_Q20, rr_Q10, rr_Q10);
            rr_Q10 = r_Q10 - q2_Q10;
            rd2_Q20 = smlabb(rd2_Q20, rr_Q10, rr_Q10);

            if (rd2_Q20 < rd1_Q20) {
                q1_Q10 = q2_Q10;
            }

            pulses[pulsesOff + i] = (byte) rshiftRound(q1_Q10, 10);

            exc_Q14 = q1_Q10 << 4;
            if (nsq.rand_seed < 0) {
                exc_Q14 = -exc_Q14;
            }

            LPC_exc_Q14 = exc_Q14 + (LTP_pred_Q13 << 1);
            xq_Q14 = LPC_exc_Q14 + (LPC_pred_Q10 << 4);

            xq[xqOff + i] = (short) sat16(rshiftRound(smulww(xq_Q14, Gain_Q10), 8));

            psLPC_Q14++;
            sLPC_Q14[psLPC_Q14] = xq_Q14;
            nsq.sDiff_shp_Q14 = xq_Q14 - (x_sc_Q10[i] << 4);
            sLF_AR_shp_Q14 = nsq.sDiff_shp_Q14 - (n_AR_Q12 << 2);
            nsq.sLF_AR_shp_Q14 = sLF_AR_shp_Q14;

            sLTP_shp_Q14[nsq.sLTP_shp_buf_idx] = sLF_AR_shp_Q14 - (n_LF_Q12 << 2);
            sLTP_Q15[nsq.sLTP_buf_idx] = LPC_exc_Q14 << 1;
            nsq.sLTP_shp_buf_idx++;
            nsq.sLTP_buf_idx++;

            nsq.rand_seed = nsq.rand_seed + pulses[pulsesOff + i];
        }

        System.arraycopy(sLPC_Q14, length, sLPC_Q14, 0, NSQ_LPC_BUF_LENGTH);
    }

    /**
     * Scales the input and the quantizer states to the gain of a subframe
     * ({@code silk_nsq_scale_states}).
     *
     * @param psEncC        the encoder state
     * @param nsq           the quantizer state
     * @param x16           the input
     * @param x16Off        the index of the first sample of the subframe
     * @param subfr         the subframe number
     * @param LTP_scale_Q14 the LTP state scaling
     * @param Gains_Q16     the quantization step sizes
     * @param pitchL        the pitch lags
     * @param signal_type   the signal type
     */
    private void scaleStates(EncoderState psEncC, NsqState nsq, short[] x16, int x16Off, int subfr, int LTP_scale_Q14,
                             int[] Gains_Q16, int[] pitchL, int signal_type) {
        int i, lag;
        int gain_adj_Q16, inv_gain_Q31, inv_gain_Q26;

        lag = pitchL[subfr];
        inv_gain_Q31 = inverse32VarQ(Math.max(Gains_Q16[subfr], 1), 47);

        inv_gain_Q26 = rshiftRound(inv_gain_Q31, 5);
        for (i = 0; i < psEncC.subfr_length; i++) {
            x_sc_Q10[i] = smulww(x16[x16Off + i], inv_gain_Q26);
        }

        if (nsq.rewhite_flag != 0) {
            if (subfr == 0) {
                inv_gain_Q31 = smulwb(inv_gain_Q31, LTP_scale_Q14) << 2;
            }
            for (i = nsq.sLTP_buf_idx - lag - LTP_ORDER / 2; i < nsq.sLTP_buf_idx; i++) {
                sLTP_Q15[i] = smulwb(inv_gain_Q31, sLTP[i]);
            }
        }

        if (Gains_Q16[subfr] != nsq.prev_gain_Q16) {
            gain_adj_Q16 = div32VarQ(nsq.prev_gain_Q16, Gains_Q16[subfr], 16);

            for (i = nsq.sLTP_shp_buf_idx - psEncC.ltp_mem_length; i < nsq.sLTP_shp_buf_idx; i++) {
                nsq.sLTP_shp_Q14[i] = smulww(gain_adj_Q16, nsq.sLTP_shp_Q14[i]);
            }

            if (signal_type == TYPE_VOICED && nsq.rewhite_flag == 0) {
                for (i = nsq.sLTP_buf_idx - lag - LTP_ORDER / 2; i < nsq.sLTP_buf_idx; i++) {
                    sLTP_Q15[i] = smulww(gain_adj_Q16, sLTP_Q15[i]);
                }
            }

            nsq.sLF_AR_shp_Q14 = smulww(gain_adj_Q16, nsq.sLF_AR_shp_Q14);
            nsq.sDiff_shp_Q14 = smulww(gain_adj_Q16, nsq.sDiff_shp_Q14);

            for (i = 0; i < NSQ_LPC_BUF_LENGTH; i++) {
                nsq.sLPC_Q14[i] = smulww(gain_adj_Q16, nsq.sLPC_Q14[i]);
            }
            for (i = 0; i < MAX_SHAPE_LPC_ORDER; i++) {
                nsq.sAR2_Q14[i] = smulww(gain_adj_Q16, nsq.sAR2_Q14[i]);
            }

            nsq.prev_gain_Q16 = Gains_Q16[subfr];
        }
    }
}
