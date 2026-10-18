package com.github.auties00.smalt.audio.opus.internal.silk;

import java.util.Arrays;

import static com.github.auties00.smalt.audio.opus.internal.silk.Define.*;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.*;

/**
 * Quantizes the SILK excitation with noise shaping and delayed decision, keeping several
 * candidate quantization paths and committing the best one {@code DECISION_DELAY} samples late.
 *
 * <p>Each delayed-decision state ({@code NSQ_del_dec_struct}) is stored as one {@code int} array
 * whose layout reproduces the C structure word for word, so the partial structure copy of the
 * reference ({@code memcpy} from the {@code i}-th word to the end) is a single
 * {@link System#arraycopy(Object, int, Object, int, int)}. An instance holds the scratch of one
 * encoder channel, so quantizing a frame allocates nothing.
 *
 * @implNote This implementation ports libopus 1.5.2 {@code silk/NSQ_del_dec.c}.
 */
final class NsqDelDecReference {
    /**
     * The offset of {@code sLPC_Q14[MAX_SUB_FRAME_LENGTH + NSQ_LPC_BUF_LENGTH]} in a state.
     */
    private static final int DD_SLPC_Q14 = 0;

    /**
     * The offset of {@code RandState[DECISION_DELAY]} in a state.
     */
    private static final int DD_RAND_STATE = DD_SLPC_Q14 + MAX_SUB_FRAME_LENGTH + NSQ_LPC_BUF_LENGTH;

    /**
     * The offset of {@code Q_Q10[DECISION_DELAY]} in a state.
     */
    private static final int DD_Q_Q10 = DD_RAND_STATE + DECISION_DELAY;

    /**
     * The offset of {@code Xq_Q14[DECISION_DELAY]} in a state.
     */
    private static final int DD_XQ_Q14 = DD_Q_Q10 + DECISION_DELAY;

    /**
     * The offset of {@code Pred_Q15[DECISION_DELAY]} in a state.
     */
    private static final int DD_PRED_Q15 = DD_XQ_Q14 + DECISION_DELAY;

    /**
     * The offset of {@code Shape_Q14[DECISION_DELAY]} in a state.
     */
    private static final int DD_SHAPE_Q14 = DD_PRED_Q15 + DECISION_DELAY;

    /**
     * The offset of {@code sAR2_Q14[MAX_SHAPE_LPC_ORDER]} in a state.
     */
    private static final int DD_SAR2_Q14 = DD_SHAPE_Q14 + DECISION_DELAY;

    /**
     * The offset of {@code LF_AR_Q14} in a state.
     */
    private static final int DD_LF_AR_Q14 = DD_SAR2_Q14 + MAX_SHAPE_LPC_ORDER;

    /**
     * The offset of {@code Diff_Q14} in a state.
     */
    private static final int DD_DIFF_Q14 = DD_LF_AR_Q14 + 1;

    /**
     * The offset of {@code Seed} in a state.
     */
    private static final int DD_SEED = DD_DIFF_Q14 + 1;

    /**
     * The offset of {@code SeedInit} in a state.
     */
    private static final int DD_SEED_INIT = DD_SEED + 1;

    /**
     * The offset of {@code RD_Q10} in a state.
     */
    private static final int DD_RD_Q10 = DD_SEED_INIT + 1;

    /**
     * The number of words of a delayed-decision state.
     */
    private static final int DD_SIZE = DD_RD_Q10 + 1;

    /**
     * The offset of {@code Q_Q10} in a sample state ({@code NSQ_sample_struct}).
     */
    private static final int SS_Q_Q10 = 0;

    /**
     * The offset of {@code RD_Q10} in a sample state.
     */
    private static final int SS_RD_Q10 = 1;

    /**
     * The offset of {@code xq_Q14} in a sample state.
     */
    private static final int SS_XQ_Q14 = 2;

    /**
     * The offset of {@code LF_AR_Q14} in a sample state.
     */
    private static final int SS_LF_AR_Q14 = 3;

    /**
     * The offset of {@code Diff_Q14} in a sample state.
     */
    private static final int SS_DIFF_Q14 = 4;

    /**
     * The offset of {@code sLTP_shp_Q14} in a sample state.
     */
    private static final int SS_SLTP_SHP_Q14 = 5;

    /**
     * The offset of {@code LPC_exc_Q14} in a sample state.
     */
    private static final int SS_LPC_EXC_Q14 = 6;

    /**
     * The number of words of a sample state.
     */
    private static final int SS_SIZE = 7;

    /**
     * The delayed-decision states, one array of {@link #DD_SIZE} words each.
     */
    private final int[][] psDelDec = new int[MAX_DEL_DEC_STATES][DD_SIZE];

    /**
     * The sample-state pairs of every delayed-decision state, {@code 2 * SS_SIZE} words each,
     * the best candidate first.
     */
    private final int[] psSampleState = new int[MAX_DEL_DEC_STATES * 2 * SS_SIZE];

    /**
     * The LTP state matching the scaled input.
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
     * The gain delay line.
     */
    private final int[] delayedGain_Q10 = new int[DECISION_DELAY];

    /**
     * The index of the newest sample in the circular buffers ({@code smpl_buf_idx}).
     */
    private int smpl_buf_idx;

    /**
     * Creates the quantizer scratch.
     */
    NsqDelDecReference() {
    }

    void nsqDelDec(EncoderState psEncC, NsqState nsq, SideInfoIndices psIndices, short[] x16, int x16Off,
                   byte[] pulses, int pulsesOff, short[] predCoef_Q12, short[] LTPCoef_Q14, short[] AR_Q13,
                   int[] HarmShapeGain_Q14, int[] Tilt_Q14, int[] LF_shp_Q14, int[] Gains_Q16, int[] pitchL,
                   int Lambda_Q10, int LTP_scale_Q14) {
        int i, k, lag, start_idx, LSF_interpolation_flag, Winner_ind, subfr;
        int last_smple_idx, decisionDelay;
        int HarmShapeFIRPacked_Q14;
        int offset_Q10;
        int RDmin_Q10, Gain_Q10;
        var nStates = psEncC.nStatesDelayedDecision;

        lag = nsq.lagPrev;

        for (k = 0; k < nStates; k++) {
            var psDD = psDelDec[k];
            Arrays.fill(psDD, 0);
            psDD[DD_SEED] = (k + psIndices.Seed) & 3;
            psDD[DD_SEED_INIT] = psDD[DD_SEED];
            psDD[DD_RD_Q10] = 0;
            psDD[DD_LF_AR_Q14] = nsq.sLF_AR_shp_Q14;
            psDD[DD_DIFF_Q14] = nsq.sDiff_shp_Q14;
            psDD[DD_SHAPE_Q14] = nsq.sLTP_shp_Q14[psEncC.ltp_mem_length - 1];
            System.arraycopy(nsq.sLPC_Q14, 0, psDD, DD_SLPC_Q14, NSQ_LPC_BUF_LENGTH);
            System.arraycopy(nsq.sAR2_Q14, 0, psDD, DD_SAR2_Q14, MAX_SHAPE_LPC_ORDER);
        }

        offset_Q10 = Tables.silk_Quantization_Offsets_Q10[psIndices.signalType >> 1][psIndices.quantOffsetType];
        smpl_buf_idx = 0;

        decisionDelay = Math.min(DECISION_DELAY, psEncC.subfr_length);

        if (psIndices.signalType == TYPE_VOICED) {
            for (k = 0; k < psEncC.nb_subfr; k++) {
                decisionDelay = Math.min(decisionDelay, pitchL[k] - LTP_ORDER / 2 - 1);
            }
        } else {
            if (lag > 0) {
                decisionDelay = Math.min(decisionDelay, lag - LTP_ORDER / 2 - 1);
            }
        }

        if (psIndices.NLSFInterpCoef_Q2 == 4) {
            LSF_interpolation_flag = 0;
        } else {
            LSF_interpolation_flag = 1;
        }

        var pxq = psEncC.ltp_mem_length;
        nsq.sLTP_shp_buf_idx = psEncC.ltp_mem_length;
        nsq.sLTP_buf_idx = psEncC.ltp_mem_length;
        subfr = 0;
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
                    if (k == 2) {
                        RDmin_Q10 = psDelDec[0][DD_RD_Q10];
                        Winner_ind = 0;
                        for (i = 1; i < nStates; i++) {
                            if (psDelDec[i][DD_RD_Q10] < RDmin_Q10) {
                                RDmin_Q10 = psDelDec[i][DD_RD_Q10];
                                Winner_ind = i;
                            }
                        }
                        for (i = 0; i < nStates; i++) {
                            if (i != Winner_ind) {
                                psDelDec[i][DD_RD_Q10] += Integer.MAX_VALUE >> 4;
                            }
                        }

                        var psDD = psDelDec[Winner_ind];
                        last_smple_idx = smpl_buf_idx + decisionDelay;
                        for (i = 0; i < decisionDelay; i++) {
                            last_smple_idx = (last_smple_idx - 1) % DECISION_DELAY;
                            if (last_smple_idx < 0) {
                                last_smple_idx += DECISION_DELAY;
                            }
                            pulses[pulsesOff + i - decisionDelay] = (byte) rshiftRound(psDD[DD_Q_Q10 + last_smple_idx], 10);
                            nsq.xq[pxq + i - decisionDelay] = (short) sat16(rshiftRound(
                                    smulww(psDD[DD_XQ_Q14 + last_smple_idx], Gains_Q16[1]), 14));
                            nsq.sLTP_shp_Q14[nsq.sLTP_shp_buf_idx - decisionDelay + i] = psDD[DD_SHAPE_Q14 + last_smple_idx];
                        }

                        subfr = 0;
                    }

                    start_idx = psEncC.ltp_mem_length - lag - psEncC.predictLPCOrder - LTP_ORDER / 2;
                    LpcAnalysisFilter.lpcAnalysisFilter(sLTP, start_idx, nsq.xq, start_idx + k * psEncC.subfr_length,
                            predCoef_Q12, aQ12Off, psEncC.ltp_mem_length - start_idx, psEncC.predictLPCOrder);

                    nsq.sLTP_buf_idx = psEncC.ltp_mem_length;
                    nsq.rewhite_flag = 1;
                }
            }

            scaleStates(psEncC, nsq, x16, x16Off, k, nStates, LTP_scale_Q14, Gains_Q16, pitchL, psIndices.signalType,
                    decisionDelay);

            noiseShapeQuantizerDelDec(nsq, psIndices.signalType, pulses, pulsesOff, pxq, predCoef_Q12, aQ12Off,
                    LTPCoef_Q14, bQ14Off, AR_Q13, arShpOff, lag, HarmShapeFIRPacked_Q14, Tilt_Q14[k], LF_shp_Q14[k],
                    Gains_Q16[k], Lambda_Q10, offset_Q10, psEncC.subfr_length, subfr++, psEncC.shapingLPCOrder,
                    psEncC.predictLPCOrder, psEncC.warping_Q16, nStates, decisionDelay);

            x16Off += psEncC.subfr_length;
            pulsesOff += psEncC.subfr_length;
            pxq += psEncC.subfr_length;
        }

        RDmin_Q10 = psDelDec[0][DD_RD_Q10];
        Winner_ind = 0;
        for (k = 1; k < nStates; k++) {
            if (psDelDec[k][DD_RD_Q10] < RDmin_Q10) {
                RDmin_Q10 = psDelDec[k][DD_RD_Q10];
                Winner_ind = k;
            }
        }

        var psDD = psDelDec[Winner_ind];
        psIndices.Seed = (byte) psDD[DD_SEED_INIT];
        last_smple_idx = smpl_buf_idx + decisionDelay;
        Gain_Q10 = Gains_Q16[psEncC.nb_subfr - 1] >> 6;
        for (i = 0; i < decisionDelay; i++) {
            last_smple_idx = (last_smple_idx - 1) % DECISION_DELAY;
            if (last_smple_idx < 0) {
                last_smple_idx += DECISION_DELAY;
            }

            pulses[pulsesOff + i - decisionDelay] = (byte) rshiftRound(psDD[DD_Q_Q10 + last_smple_idx], 10);
            nsq.xq[pxq + i - decisionDelay] = (short) sat16(rshiftRound(
                    smulww(psDD[DD_XQ_Q14 + last_smple_idx], Gain_Q10), 8));
            nsq.sLTP_shp_Q14[nsq.sLTP_shp_buf_idx - decisionDelay + i] = psDD[DD_SHAPE_Q14 + last_smple_idx];
        }
        System.arraycopy(psDD, DD_SLPC_Q14 + psEncC.subfr_length, nsq.sLPC_Q14, 0, NSQ_LPC_BUF_LENGTH);
        System.arraycopy(psDD, DD_SAR2_Q14, nsq.sAR2_Q14, 0, MAX_SHAPE_LPC_ORDER);

        nsq.sLF_AR_shp_Q14 = psDD[DD_LF_AR_Q14];
        nsq.sDiff_shp_Q14 = psDD[DD_DIFF_Q14];
        nsq.lagPrev = pitchL[psEncC.nb_subfr - 1];

        System.arraycopy(nsq.xq, psEncC.frame_length, nsq.xq, 0, psEncC.ltp_mem_length);
        System.arraycopy(nsq.sLTP_shp_Q14, psEncC.frame_length, nsq.sLTP_shp_Q14, 0, psEncC.ltp_mem_length);
    }

    /**
     * Quantizes one subframe with delayed decision ({@code silk_noise_shape_quantizer_del_dec}).
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
     * @param subfr                  the subframe number since the last decision reset
     * @param shapingLPCOrder        the noise-shaping filter order
     * @param predictLPCOrder        the prediction filter order
     * @param warping_Q16            the warping of the shaping filter
     * @param nStatesDelayedDecision the number of states in the decision tree
     * @param decisionDelay          the decision delay
     */
    private void noiseShapeQuantizerDelDec(NsqState nsq, int signalType, byte[] pulses, int pulsesOff, int xqOff,
                                           short[] a_Q12, int aOff, short[] b_Q14, int bOff, short[] AR_shp_Q13,
                                           int arOff, int lag, int HarmShapeFIRPacked_Q14, int Tilt_Q14,
                                           int LF_shp_Q14, int Gain_Q16, int Lambda_Q10, int offset_Q10, int length,
                                           int subfr, int shapingLPCOrder, int predictLPCOrder, int warping_Q16,
                                           int nStatesDelayedDecision, int decisionDelay) {
        int i, j, k, Winner_ind, RDmin_ind, RDmax_ind, last_smple_idx;
        int Winner_rand_state;
        int LTP_pred_Q14, LPC_pred_Q14, n_AR_Q14, n_LTP_Q14;
        int n_LF_Q14, r_Q10, rr_Q10, rd1_Q10, rd2_Q10, RDmin_Q10, RDmax_Q10;
        int q1_Q0, q1_Q10, q2_Q10, exc_Q14, LPC_exc_Q14, xq_Q14, Gain_Q10;
        int tmp1, tmp2, sLF_AR_shp_Q14;
        var ss = psSampleState;
        var sLTP_Q15 = this.sLTP_Q15;
        var x_Q10 = this.x_sc_Q10;
        var sLTP_shp_Q14 = nsq.sLTP_shp_Q14;

        var shp_lag_ptr = nsq.sLTP_shp_buf_idx - lag + HARM_SHAPE_FIR_TAPS / 2;
        var pred_lag_ptr = nsq.sLTP_buf_idx - lag + LTP_ORDER / 2;
        Gain_Q10 = Gain_Q16 >> 6;

        for (i = 0; i < length; i++) {
            if (signalType == TYPE_VOICED) {
                LTP_pred_Q14 = 2;
                LTP_pred_Q14 = smlawb(LTP_pred_Q14, sLTP_Q15[pred_lag_ptr], b_Q14[bOff]);
                LTP_pred_Q14 = smlawb(LTP_pred_Q14, sLTP_Q15[pred_lag_ptr - 1], b_Q14[bOff + 1]);
                LTP_pred_Q14 = smlawb(LTP_pred_Q14, sLTP_Q15[pred_lag_ptr - 2], b_Q14[bOff + 2]);
                LTP_pred_Q14 = smlawb(LTP_pred_Q14, sLTP_Q15[pred_lag_ptr - 3], b_Q14[bOff + 3]);
                LTP_pred_Q14 = smlawb(LTP_pred_Q14, sLTP_Q15[pred_lag_ptr - 4], b_Q14[bOff + 4]);
                LTP_pred_Q14 = LTP_pred_Q14 << 1;
                pred_lag_ptr++;
            } else {
                LTP_pred_Q14 = 0;
            }

            if (lag > 0) {
                n_LTP_Q14 = smulwb(addSat32(sLTP_shp_Q14[shp_lag_ptr], sLTP_shp_Q14[shp_lag_ptr - 2]), HarmShapeFIRPacked_Q14);
                n_LTP_Q14 = smlawt(n_LTP_Q14, sLTP_shp_Q14[shp_lag_ptr - 1], HarmShapeFIRPacked_Q14);
                n_LTP_Q14 = LTP_pred_Q14 - (n_LTP_Q14 << 2);
                shp_lag_ptr++;
            } else {
                n_LTP_Q14 = 0;
            }

            for (k = 0; k < nStatesDelayedDecision; k++) {
                var psDD = psDelDec[k];
                var psSS = k * 2 * SS_SIZE;

                psDD[DD_SEED] = rand(psDD[DD_SEED]);

                var psLPC_Q14 = DD_SLPC_Q14 + NSQ_LPC_BUF_LENGTH - 1 + i;
                LPC_pred_Q14 = Nsq.shortPrediction(psDD, psLPC_Q14, a_Q12, aOff, predictLPCOrder);
                LPC_pred_Q14 = LPC_pred_Q14 << 4;

                tmp2 = smlawb(psDD[DD_DIFF_Q14], psDD[DD_SAR2_Q14], warping_Q16);
                tmp1 = smlawb(psDD[DD_SAR2_Q14], psDD[DD_SAR2_Q14 + 1] - tmp2, warping_Q16);
                psDD[DD_SAR2_Q14] = tmp2;
                n_AR_Q14 = shapingLPCOrder >> 1;
                n_AR_Q14 = smlawb(n_AR_Q14, tmp2, AR_shp_Q13[arOff]);
                for (j = 2; j < shapingLPCOrder; j += 2) {
                    tmp2 = smlawb(psDD[DD_SAR2_Q14 + j - 1], psDD[DD_SAR2_Q14 + j] - tmp1, warping_Q16);
                    psDD[DD_SAR2_Q14 + j - 1] = tmp1;
                    n_AR_Q14 = smlawb(n_AR_Q14, tmp1, AR_shp_Q13[arOff + j - 1]);
                    tmp1 = smlawb(psDD[DD_SAR2_Q14 + j], psDD[DD_SAR2_Q14 + j + 1] - tmp2, warping_Q16);
                    psDD[DD_SAR2_Q14 + j] = tmp2;
                    n_AR_Q14 = smlawb(n_AR_Q14, tmp2, AR_shp_Q13[arOff + j]);
                }
                psDD[DD_SAR2_Q14 + shapingLPCOrder - 1] = tmp1;
                n_AR_Q14 = smlawb(n_AR_Q14, tmp1, AR_shp_Q13[arOff + shapingLPCOrder - 1]);

                n_AR_Q14 = n_AR_Q14 << 1;
                n_AR_Q14 = smlawb(n_AR_Q14, psDD[DD_LF_AR_Q14], Tilt_Q14);
                n_AR_Q14 = n_AR_Q14 << 2;

                n_LF_Q14 = smulwb(psDD[DD_SHAPE_Q14 + smpl_buf_idx], LF_shp_Q14);
                n_LF_Q14 = smlawt(n_LF_Q14, psDD[DD_LF_AR_Q14], LF_shp_Q14);
                n_LF_Q14 = n_LF_Q14 << 2;

                tmp1 = addSat32(n_AR_Q14, n_LF_Q14);
                tmp2 = n_LTP_Q14 + LPC_pred_Q14;
                tmp1 = subSat32(tmp2, tmp1);
                tmp1 = rshiftRound(tmp1, 4);

                r_Q10 = x_Q10[i] - tmp1;

                if (psDD[DD_SEED] < 0) {
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
                    rd1_Q10 = smulbb(q1_Q10, Lambda_Q10);
                    rd2_Q10 = smulbb(q2_Q10, Lambda_Q10);
                } else if (q1_Q0 == 0) {
                    q1_Q10 = offset_Q10;
                    q2_Q10 = q1_Q10 + (1024 - QUANT_LEVEL_ADJUST_Q10);
                    rd1_Q10 = smulbb(q1_Q10, Lambda_Q10);
                    rd2_Q10 = smulbb(q2_Q10, Lambda_Q10);
                } else if (q1_Q0 == -1) {
                    q2_Q10 = offset_Q10;
                    q1_Q10 = q2_Q10 - (1024 - QUANT_LEVEL_ADJUST_Q10);
                    rd1_Q10 = smulbb(-q1_Q10, Lambda_Q10);
                    rd2_Q10 = smulbb(q2_Q10, Lambda_Q10);
                } else {
                    q1_Q10 = (q1_Q0 << 10) + QUANT_LEVEL_ADJUST_Q10;
                    q1_Q10 = q1_Q10 + offset_Q10;
                    q2_Q10 = q1_Q10 + 1024;
                    rd1_Q10 = smulbb(-q1_Q10, Lambda_Q10);
                    rd2_Q10 = smulbb(-q2_Q10, Lambda_Q10);
                }
                rr_Q10 = r_Q10 - q1_Q10;
                rd1_Q10 = smlabb(rd1_Q10, rr_Q10, rr_Q10) >> 10;
                rr_Q10 = r_Q10 - q2_Q10;
                rd2_Q10 = smlabb(rd2_Q10, rr_Q10, rr_Q10) >> 10;

                var ss0 = psSS;
                var ss1 = psSS + SS_SIZE;
                if (rd1_Q10 < rd2_Q10) {
                    ss[ss0 + SS_RD_Q10] = psDD[DD_RD_Q10] + rd1_Q10;
                    ss[ss1 + SS_RD_Q10] = psDD[DD_RD_Q10] + rd2_Q10;
                    ss[ss0 + SS_Q_Q10] = q1_Q10;
                    ss[ss1 + SS_Q_Q10] = q2_Q10;
                } else {
                    ss[ss0 + SS_RD_Q10] = psDD[DD_RD_Q10] + rd2_Q10;
                    ss[ss1 + SS_RD_Q10] = psDD[DD_RD_Q10] + rd1_Q10;
                    ss[ss0 + SS_Q_Q10] = q2_Q10;
                    ss[ss1 + SS_Q_Q10] = q1_Q10;
                }

                exc_Q14 = ss[ss0 + SS_Q_Q10] << 4;
                if (psDD[DD_SEED] < 0) {
                    exc_Q14 = -exc_Q14;
                }
                LPC_exc_Q14 = exc_Q14 + LTP_pred_Q14;
                xq_Q14 = LPC_exc_Q14 + LPC_pred_Q14;
                ss[ss0 + SS_DIFF_Q14] = xq_Q14 - (x_Q10[i] << 4);
                sLF_AR_shp_Q14 = ss[ss0 + SS_DIFF_Q14] - n_AR_Q14;
                ss[ss0 + SS_SLTP_SHP_Q14] = subSat32(sLF_AR_shp_Q14, n_LF_Q14);
                ss[ss0 + SS_LF_AR_Q14] = sLF_AR_shp_Q14;
                ss[ss0 + SS_LPC_EXC_Q14] = LPC_exc_Q14;
                ss[ss0 + SS_XQ_Q14] = xq_Q14;

                exc_Q14 = ss[ss1 + SS_Q_Q10] << 4;
                if (psDD[DD_SEED] < 0) {
                    exc_Q14 = -exc_Q14;
                }
                LPC_exc_Q14 = exc_Q14 + LTP_pred_Q14;
                xq_Q14 = LPC_exc_Q14 + LPC_pred_Q14;
                ss[ss1 + SS_DIFF_Q14] = xq_Q14 - (x_Q10[i] << 4);
                sLF_AR_shp_Q14 = ss[ss1 + SS_DIFF_Q14] - n_AR_Q14;
                ss[ss1 + SS_SLTP_SHP_Q14] = subSat32(sLF_AR_shp_Q14, n_LF_Q14);
                ss[ss1 + SS_LF_AR_Q14] = sLF_AR_shp_Q14;
                ss[ss1 + SS_LPC_EXC_Q14] = LPC_exc_Q14;
                ss[ss1 + SS_XQ_Q14] = xq_Q14;
            }

            smpl_buf_idx = (smpl_buf_idx - 1) % DECISION_DELAY;
            if (smpl_buf_idx < 0) {
                smpl_buf_idx += DECISION_DELAY;
            }
            last_smple_idx = (smpl_buf_idx + decisionDelay) % DECISION_DELAY;

            RDmin_Q10 = ss[SS_RD_Q10];
            Winner_ind = 0;
            for (k = 1; k < nStatesDelayedDecision; k++) {
                if (ss[k * 2 * SS_SIZE + SS_RD_Q10] < RDmin_Q10) {
                    RDmin_Q10 = ss[k * 2 * SS_SIZE + SS_RD_Q10];
                    Winner_ind = k;
                }
            }

            Winner_rand_state = psDelDec[Winner_ind][DD_RAND_STATE + last_smple_idx];
            for (k = 0; k < nStatesDelayedDecision; k++) {
                if (psDelDec[k][DD_RAND_STATE + last_smple_idx] != Winner_rand_state) {
                    ss[k * 2 * SS_SIZE + SS_RD_Q10] = ss[k * 2 * SS_SIZE + SS_RD_Q10] + (Integer.MAX_VALUE >> 4);
                    ss[k * 2 * SS_SIZE + SS_SIZE + SS_RD_Q10] = ss[k * 2 * SS_SIZE + SS_SIZE + SS_RD_Q10] + (Integer.MAX_VALUE >> 4);
                }
            }

            RDmax_Q10 = ss[SS_RD_Q10];
            RDmin_Q10 = ss[SS_SIZE + SS_RD_Q10];
            RDmax_ind = 0;
            RDmin_ind = 0;
            for (k = 1; k < nStatesDelayedDecision; k++) {
                if (ss[k * 2 * SS_SIZE + SS_RD_Q10] > RDmax_Q10) {
                    RDmax_Q10 = ss[k * 2 * SS_SIZE + SS_RD_Q10];
                    RDmax_ind = k;
                }
                if (ss[k * 2 * SS_SIZE + SS_SIZE + SS_RD_Q10] < RDmin_Q10) {
                    RDmin_Q10 = ss[k * 2 * SS_SIZE + SS_SIZE + SS_RD_Q10];
                    RDmin_ind = k;
                }
            }

            if (RDmin_Q10 < RDmax_Q10) {
                System.arraycopy(psDelDec[RDmin_ind], i, psDelDec[RDmax_ind], i, DD_SIZE - i);
                System.arraycopy(ss, RDmin_ind * 2 * SS_SIZE + SS_SIZE, ss, RDmax_ind * 2 * SS_SIZE, SS_SIZE);
            }

            var psDD = psDelDec[Winner_ind];
            if (subfr > 0 || i >= decisionDelay) {
                pulses[pulsesOff + i - decisionDelay] = (byte) rshiftRound(psDD[DD_Q_Q10 + last_smple_idx], 10);
                nsq.xq[xqOff + i - decisionDelay] = (short) sat16(rshiftRound(
                        smulww(psDD[DD_XQ_Q14 + last_smple_idx], delayedGain_Q10[last_smple_idx]), 8));
                sLTP_shp_Q14[nsq.sLTP_shp_buf_idx - decisionDelay] = psDD[DD_SHAPE_Q14 + last_smple_idx];
                sLTP_Q15[nsq.sLTP_buf_idx - decisionDelay] = psDD[DD_PRED_Q15 + last_smple_idx];
            }
            nsq.sLTP_shp_buf_idx++;
            nsq.sLTP_buf_idx++;

            for (k = 0; k < nStatesDelayedDecision; k++) {
                psDD = psDelDec[k];
                var psSS = k * 2 * SS_SIZE;
                psDD[DD_LF_AR_Q14] = ss[psSS + SS_LF_AR_Q14];
                psDD[DD_DIFF_Q14] = ss[psSS + SS_DIFF_Q14];
                psDD[DD_SLPC_Q14 + NSQ_LPC_BUF_LENGTH + i] = ss[psSS + SS_XQ_Q14];
                psDD[DD_XQ_Q14 + smpl_buf_idx] = ss[psSS + SS_XQ_Q14];
                psDD[DD_Q_Q10 + smpl_buf_idx] = ss[psSS + SS_Q_Q10];
                psDD[DD_PRED_Q15 + smpl_buf_idx] = ss[psSS + SS_LPC_EXC_Q14] << 1;
                psDD[DD_SHAPE_Q14 + smpl_buf_idx] = ss[psSS + SS_SLTP_SHP_Q14];
                psDD[DD_SEED] = psDD[DD_SEED] + rshiftRound(ss[psSS + SS_Q_Q10], 10);
                psDD[DD_RAND_STATE + smpl_buf_idx] = psDD[DD_SEED];
                psDD[DD_RD_Q10] = ss[psSS + SS_RD_Q10];
            }
            delayedGain_Q10[smpl_buf_idx] = Gain_Q10;
        }

        for (k = 0; k < nStatesDelayedDecision; k++) {
            var psDD = psDelDec[k];
            System.arraycopy(psDD, DD_SLPC_Q14 + length, psDD, DD_SLPC_Q14, NSQ_LPC_BUF_LENGTH);
        }
    }

    /**
     * Scales the input and every quantizer state to the gain of a subframe
     * ({@code silk_nsq_del_dec_scale_states}).
     *
     * @param psEncC                 the encoder state
     * @param nsq                    the quantizer state
     * @param x16                    the input
     * @param x16Off                 the index of the first sample of the subframe
     * @param subfr                  the subframe number
     * @param nStatesDelayedDecision the number of delayed-decision states
     * @param LTP_scale_Q14          the LTP state scaling
     * @param Gains_Q16              the quantization step sizes
     * @param pitchL                 the pitch lags
     * @param signal_type            the signal type
     * @param decisionDelay          the decision delay
     */
    private void scaleStates(EncoderState psEncC, NsqState nsq, short[] x16, int x16Off, int subfr,
                             int nStatesDelayedDecision, int LTP_scale_Q14, int[] Gains_Q16, int[] pitchL,
                             int signal_type, int decisionDelay) {
        int i, k, lag;
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
                for (i = nsq.sLTP_buf_idx - lag - LTP_ORDER / 2; i < nsq.sLTP_buf_idx - decisionDelay; i++) {
                    sLTP_Q15[i] = smulww(gain_adj_Q16, sLTP_Q15[i]);
                }
            }

            for (k = 0; k < nStatesDelayedDecision; k++) {
                var psDD = psDelDec[k];
                psDD[DD_LF_AR_Q14] = smulww(gain_adj_Q16, psDD[DD_LF_AR_Q14]);
                psDD[DD_DIFF_Q14] = smulww(gain_adj_Q16, psDD[DD_DIFF_Q14]);
                for (i = 0; i < NSQ_LPC_BUF_LENGTH; i++) {
                    psDD[DD_SLPC_Q14 + i] = smulww(gain_adj_Q16, psDD[DD_SLPC_Q14 + i]);
                }
                for (i = 0; i < MAX_SHAPE_LPC_ORDER; i++) {
                    psDD[DD_SAR2_Q14 + i] = smulww(gain_adj_Q16, psDD[DD_SAR2_Q14 + i]);
                }
                for (i = 0; i < DECISION_DELAY; i++) {
                    psDD[DD_PRED_Q15 + i] = smulww(gain_adj_Q16, psDD[DD_PRED_Q15 + i]);
                    psDD[DD_SHAPE_Q14 + i] = smulww(gain_adj_Q16, psDD[DD_SHAPE_Q14 + i]);
                }
            }

            nsq.prev_gain_Q16 = Gains_Q16[subfr];
        }
    }
}
