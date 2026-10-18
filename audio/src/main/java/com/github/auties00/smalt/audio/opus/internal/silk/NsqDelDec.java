/* Ports libopus 1.5.2 (silk/NSQ_del_dec.c). Copyright Skype Limited; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.silk;

import java.util.Arrays;

import static com.github.auties00.smalt.audio.opus.internal.silk.Define.*;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.*;

/**
 * Quantizes the SILK excitation with noise shaping and delayed decision, keeping up to four
 * candidate quantization paths and committing the best one {@code DECISION_DELAY} samples late.
 *
 * <p>The four delayed-decision states ({@code NSQ_del_dec_struct}) live in one {@code int} array,
 * each state a block whose layout reproduces the C structure word for word, so replacing one state
 * by another, the reference's partial structure copy, takes two
 * {@link System#arraycopy(Object, int, Object, int, int)} calls that skip the prediction history
 * no later sample reads. The per-sample warped noise-shaping
 * recursion, whose long dependency chains dominate the encoder's run time, is evaluated for the
 * four states side by side, so the processor overlaps four independent chains; the short-term
 * prediction and the shaping feedback, sums of {@code silk_SMLAWB} terms, are then formed for all
 * four states by {@link SilkKernels#nsqDotProducts}. Every term is computed exactly as in the
 * reference and wrapping 32-bit sums do not depend on their order, so the output is bit-identical
 * to libopus. The quantization decisions of the states (candidate levels, rate/distortion
 * comparisons, winner and replacement selection) are computed without data-dependent branches.
 * An instance holds the scratch of one encoder channel, so quantizing a frame allocates nothing.
 *
 * @implNote This implementation computes the states beyond {@code nStatesDelayedDecision} alongside and never reads
 * them. It keeps the recursion scalar: with one state per lane, the vector operations of a sample exceed the JIT
 * inlining budget and the 32-bit vector multiply lengthens the critical path, so only the order-free sums use a kernel.
 */
final class NsqDelDec {
    /**
     * The number of states computed side by side.
     */
    private static final int L = MAX_DEL_DEC_STATES;

    /**
     * The offset of {@code sLPC_Q14[MAX_SUB_FRAME_LENGTH + NSQ_LPC_BUF_LENGTH]} in a state block.
     */
    private static final int DD_SLPC_Q14 = 0;

    /**
     * The offset of {@code RandState[DECISION_DELAY]} in a state block.
     */
    private static final int DD_RAND_STATE = DD_SLPC_Q14 + MAX_SUB_FRAME_LENGTH + NSQ_LPC_BUF_LENGTH;

    /**
     * The offset of {@code Q_Q10[DECISION_DELAY]} in a state block.
     */
    private static final int DD_Q_Q10 = DD_RAND_STATE + DECISION_DELAY;

    /**
     * The offset of {@code Xq_Q14[DECISION_DELAY]} in a state block.
     */
    private static final int DD_XQ_Q14 = DD_Q_Q10 + DECISION_DELAY;

    /**
     * The offset of {@code Pred_Q15[DECISION_DELAY]} in a state block.
     */
    private static final int DD_PRED_Q15 = DD_XQ_Q14 + DECISION_DELAY;

    /**
     * The offset of {@code Shape_Q14[DECISION_DELAY]} in a state block.
     */
    private static final int DD_SHAPE_Q14 = DD_PRED_Q15 + DECISION_DELAY;

    /**
     * The offset of {@code sAR2_Q14[MAX_SHAPE_LPC_ORDER]} in a state block.
     */
    private static final int DD_SAR2_Q14 = DD_SHAPE_Q14 + DECISION_DELAY;

    /**
     * The offset of {@code LF_AR_Q14} in a state block.
     */
    private static final int DD_LF_AR_Q14 = DD_SAR2_Q14 + MAX_SHAPE_LPC_ORDER;

    /**
     * The offset of {@code Diff_Q14} in a state block.
     */
    private static final int DD_DIFF_Q14 = DD_LF_AR_Q14 + 1;

    /**
     * The offset of {@code Seed} in a state block.
     */
    private static final int DD_SEED = DD_DIFF_Q14 + 1;

    /**
     * The offset of {@code SeedInit} in a state block.
     */
    private static final int DD_SEED_INIT = DD_SEED + 1;

    /**
     * The offset of {@code RD_Q10} in a state block.
     */
    private static final int DD_RD_Q10 = DD_SEED_INIT + 1;

    /**
     * The number of words of a state block.
     */
    private static final int DD_SIZE = DD_RD_Q10 + 1;

    /**
     * The four delayed-decision states, {@link #DD_SIZE} words each.
     */
    private final int[] dd = new int[L * DD_SIZE];

    /**
     * The number of {@code int} slots of one state in {@link #ss}.
     */
    private static final int SS_STRIDE = 16;

    /**
     * The slot in {@link #ss} of the best candidate's quantized excitation level in Q10.
     */
    private static final int SS0_Q = 0;

    /**
     * The slot in {@link #ss} of the best candidate's accumulated rate/distortion in Q10.
     */
    private static final int SS0_RD = 1;

    /**
     * The slot in {@link #ss} of the best candidate's reconstructed sample in Q14.
     */
    private static final int SS0_XQ = 2;

    /**
     * The slot in {@link #ss} of the best candidate's low-frequency AR shaping state in Q14.
     */
    private static final int SS0_LFAR = 3;

    /**
     * The slot in {@link #ss} of the best candidate's shaping-filter input difference in Q14.
     */
    private static final int SS0_DIFF = 4;

    /**
     * The slot in {@link #ss} of the best candidate's long-term shaping state in Q14.
     */
    private static final int SS0_SHP = 5;

    /**
     * The slot in {@link #ss} of the best candidate's excitation plus long-term prediction in Q14.
     */
    private static final int SS0_EXC = 6;

    /**
     * The slot in {@link #ss} of the second-best candidate's quantized excitation level in Q10.
     */
    private static final int SS1_Q = 8;

    /**
     * The slot in {@link #ss} of the second-best candidate's accumulated rate/distortion in Q10.
     */
    private static final int SS1_RD = 9;

    /**
     * The slot in {@link #ss} of the second-best candidate's reconstructed sample in Q14.
     */
    private static final int SS1_XQ = 10;

    /**
     * The slot in {@link #ss} of the second-best candidate's low-frequency AR shaping state in Q14.
     */
    private static final int SS1_LFAR = 11;

    /**
     * The slot in {@link #ss} of the second-best candidate's shaping-filter input difference in Q14.
     */
    private static final int SS1_DIFF = 12;

    /**
     * The slot in {@link #ss} of the second-best candidate's long-term shaping state in Q14.
     */
    private static final int SS1_SHP = 13;

    /**
     * The slot in {@link #ss} of the second-best candidate's excitation plus long-term prediction in Q14.
     */
    private static final int SS1_EXC = 14;

    /**
     * The two candidate states of each delayed-decision state for the current sample
     * ({@code NSQ_sample_struct[2]}): {@link #SS_STRIDE} slots per state, the best candidate's seven
     * values from slot 0 and the second-best's from slot 8, in the same order, so replacing a
     * state's best candidate by another state's second-best is one seven-element copy.
     */
    private final int[] ss = new int[L * SS_STRIDE];

    /**
     * The short-term prediction of every state for the current sample.
     */
    private final int[] vLpcPred = new int[L];

    /**
     * The noise-shaping AR feedback of every state for the current sample.
     */
    private final int[] vNAr = new int[L];

    /**
     * The low-frequency shaping feedback of every state for the current sample.
     */
    private final int[] vNLf = new int[L];

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
     * The short-term prediction coefficients of the current subframe in the order of the
     * prediction history they multiply (oldest first), zero-padded at the front to 16.
     */
    private final int[] lpcCoef = new int[NSQ_LPC_BUF_LENGTH];

    /**
     * The noise-shaping coefficients of the current subframe, zero-padded to
     * {@code MAX_SHAPE_LPC_ORDER}.
     */
    private final int[] arCoef = new int[MAX_SHAPE_LPC_ORDER];

    /**
     * The prediction and shaping sums of the four states for the current sample, as computed by
     * {@link SilkKernels#nsqDotProducts}.
     */
    private final int[] sums = new int[2 * L];

    /**
     * The index of the newest sample in the circular buffers ({@code smpl_buf_idx}).
     */
    private int smpl_buf_idx;

    /**
     * Creates the quantizer scratch.
     */
    NsqDelDec() {
    }

    /**
     * Quantizes one frame of excitation with delayed decision ({@code silk_NSQ_del_dec_c}).
     *
     * @param psEncC            the encoder state
     * @param nsq               the quantizer state, updated
     * @param psIndices         the quantization indices; {@code Seed} is updated to the winning
     *                          path's seed
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
        var dd = this.dd;

        lag = nsq.lagPrev;

        Arrays.fill(dd, 0);
        for (k = 0; k < nStates; k++) {
            var b = k * DD_SIZE;
            dd[b + DD_SEED] = (k + psIndices.Seed) & 3;
            dd[b + DD_SEED_INIT] = dd[b + DD_SEED];
            dd[b + DD_RD_Q10] = 0;
            dd[b + DD_LF_AR_Q14] = nsq.sLF_AR_shp_Q14;
            dd[b + DD_DIFF_Q14] = nsq.sDiff_shp_Q14;
            dd[b + DD_SHAPE_Q14] = nsq.sLTP_shp_Q14[psEncC.ltp_mem_length - 1];
            System.arraycopy(nsq.sLPC_Q14, 0, dd, b + DD_SLPC_Q14, NSQ_LPC_BUF_LENGTH);
            System.arraycopy(nsq.sAR2_Q14, 0, dd, b + DD_SAR2_Q14, MAX_SHAPE_LPC_ORDER);
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
                        RDmin_Q10 = dd[DD_RD_Q10];
                        Winner_ind = 0;
                        for (i = 1; i < nStates; i++) {
                            if (dd[i * DD_SIZE + DD_RD_Q10] < RDmin_Q10) {
                                RDmin_Q10 = dd[i * DD_SIZE + DD_RD_Q10];
                                Winner_ind = i;
                            }
                        }
                        for (i = 0; i < nStates; i++) {
                            if (i != Winner_ind) {
                                dd[i * DD_SIZE + DD_RD_Q10] += Integer.MAX_VALUE >> 4;
                            }
                        }

                        var w = Winner_ind * DD_SIZE;
                        last_smple_idx = smpl_buf_idx + decisionDelay;
                        for (i = 0; i < decisionDelay; i++) {
                            last_smple_idx = (last_smple_idx - 1) % DECISION_DELAY;
                            if (last_smple_idx < 0) {
                                last_smple_idx += DECISION_DELAY;
                            }
                            pulses[pulsesOff + i - decisionDelay] = (byte) rshiftRound(dd[w + DD_Q_Q10 + last_smple_idx], 10);
                            nsq.xq[pxq + i - decisionDelay] = (short) sat16(rshiftRound(
                                    smulww(dd[w + DD_XQ_Q14 + last_smple_idx], Gains_Q16[1]), 14));
                            nsq.sLTP_shp_Q14[nsq.sLTP_shp_buf_idx - decisionDelay + i] = dd[w + DD_SHAPE_Q14 + last_smple_idx];
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

            quantizeSubframe(nsq, psIndices.signalType, pulses, pulsesOff, pxq, predCoef_Q12, aQ12Off, LTPCoef_Q14,
                    bQ14Off, AR_Q13, arShpOff, lag, HarmShapeFIRPacked_Q14, Tilt_Q14[k], LF_shp_Q14[k], Gains_Q16[k],
                    Lambda_Q10, offset_Q10, psEncC.subfr_length, subfr++, psEncC.shapingLPCOrder,
                    psEncC.predictLPCOrder, psEncC.warping_Q16, nStates, decisionDelay);

            x16Off += psEncC.subfr_length;
            pulsesOff += psEncC.subfr_length;
            pxq += psEncC.subfr_length;
        }

        RDmin_Q10 = dd[DD_RD_Q10];
        Winner_ind = 0;
        for (k = 1; k < nStates; k++) {
            if (dd[k * DD_SIZE + DD_RD_Q10] < RDmin_Q10) {
                RDmin_Q10 = dd[k * DD_SIZE + DD_RD_Q10];
                Winner_ind = k;
            }
        }

        var w = Winner_ind * DD_SIZE;
        psIndices.Seed = (byte) dd[w + DD_SEED_INIT];
        last_smple_idx = smpl_buf_idx + decisionDelay;
        Gain_Q10 = Gains_Q16[psEncC.nb_subfr - 1] >> 6;
        for (i = 0; i < decisionDelay; i++) {
            last_smple_idx = (last_smple_idx - 1) % DECISION_DELAY;
            if (last_smple_idx < 0) {
                last_smple_idx += DECISION_DELAY;
            }
            pulses[pulsesOff + i - decisionDelay] = (byte) rshiftRound(dd[w + DD_Q_Q10 + last_smple_idx], 10);
            nsq.xq[pxq + i - decisionDelay] = (short) sat16(rshiftRound(
                    smulww(dd[w + DD_XQ_Q14 + last_smple_idx], Gain_Q10), 8));
            nsq.sLTP_shp_Q14[nsq.sLTP_shp_buf_idx - decisionDelay + i] = dd[w + DD_SHAPE_Q14 + last_smple_idx];
        }
        System.arraycopy(dd, w + DD_SLPC_Q14 + psEncC.subfr_length, nsq.sLPC_Q14, 0, NSQ_LPC_BUF_LENGTH);
        System.arraycopy(dd, w + DD_SAR2_Q14, nsq.sAR2_Q14, 0, MAX_SHAPE_LPC_ORDER);

        nsq.sLF_AR_shp_Q14 = dd[w + DD_LF_AR_Q14];
        nsq.sDiff_shp_Q14 = dd[w + DD_DIFF_Q14];
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
    private void quantizeSubframe(NsqState nsq, int signalType, byte[] pulses, int pulsesOff, int xqOff, short[] a_Q12,
                                  int aOff, short[] b_Q14, int bOff, short[] AR_shp_Q13, int arOff, int lag,
                                  int HarmShapeFIRPacked_Q14, int Tilt_Q14, int LF_shp_Q14, int Gain_Q16,
                                  int Lambda_Q10, int offset_Q10, int length, int subfr, int shapingLPCOrder,
                                  int predictLPCOrder, int warping_Q16, int nStatesDelayedDecision, int decisionDelay) {
        int i, k, Winner_ind, RDmin_ind, RDmax_ind, last_smple_idx;
        int Winner_rand_state;
        int LTP_pred_Q14, n_LTP_Q14;
        int RDmin_Q10, RDmax_Q10;
        int Gain_Q10;
        var dd = this.dd;
        var sLTP_Q15 = this.sLTP_Q15;
        var x_Q10 = this.x_sc_Q10;
        var sLTP_shp_Q14 = nsq.sLTP_shp_Q14;
        var maxRd = Integer.MAX_VALUE >> 4;

        var shp_lag_ptr = nsq.sLTP_shp_buf_idx - lag + HARM_SHAPE_FIR_TAPS / 2;
        var pred_lag_ptr = nsq.sLTP_buf_idx - lag + LTP_ORDER / 2;
        Gain_Q10 = Gain_Q16 >> 6;
        var rdoOffset = Lambda_Q10 > 2048 ? Lambda_Q10 / 2 - 512 : -1;
        var fourStates = nStatesDelayedDecision == L;
        for (var m = 0; m < NSQ_LPC_BUF_LENGTH; m++) {
            var j = NSQ_LPC_BUF_LENGTH - 1 - m;
            lpcCoef[m] = j < predictLPCOrder ? a_Q12[aOff + j] : 0;
        }
        for (var j = 0; j < MAX_SHAPE_LPC_ORDER; j++) {
            arCoef[j] = j < shapingLPCOrder ? AR_shp_Q13[arOff + j] : 0;
        }

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

            predictAndShape(i, predictLPCOrder, shapingLPCOrder, warping_Q16, Tilt_Q14, LF_shp_Q14);

            evaluate(nStatesDelayedDecision, x_Q10[i], LTP_pred_Q14, n_LTP_Q14, offset_Q10, Lambda_Q10, rdoOffset);

            smpl_buf_idx = (smpl_buf_idx - 1) % DECISION_DELAY;
            if (smpl_buf_idx < 0) {
                smpl_buf_idx += DECISION_DELAY;
            }
            last_smple_idx = (smpl_buf_idx + decisionDelay) % DECISION_DELAY;

            if (fourStates) {
                Winner_ind = firstMinimum(ss, SS0_RD);
                Winner_rand_state = dd[Winner_ind * DD_SIZE + DD_RAND_STATE + last_smple_idx];
                for (k = 0; k < L; k++) {
                    var differs = dd[k * DD_SIZE + DD_RAND_STATE + last_smple_idx] ^ Winner_rand_state;
                    var expired = maxRd & ((differs | -differs) >> 31);
                    ss[k * SS_STRIDE + SS0_RD] += expired;
                    ss[k * SS_STRIDE + SS1_RD] += expired;
                }
                RDmax_ind = firstMaximum(ss, SS0_RD);
                RDmin_ind = firstMinimum(ss, SS1_RD);
                RDmax_Q10 = ss[RDmax_ind * SS_STRIDE + SS0_RD];
                RDmin_Q10 = ss[RDmin_ind * SS_STRIDE + SS1_RD];
            } else {
                RDmin_Q10 = ss[0 * SS_STRIDE + SS0_RD];
                Winner_ind = 0;
                for (k = 1; k < nStatesDelayedDecision; k++) {
                    if (ss[k * SS_STRIDE + SS0_RD] < RDmin_Q10) {
                        RDmin_Q10 = ss[k * SS_STRIDE + SS0_RD];
                        Winner_ind = k;
                    }
                }

                Winner_rand_state = dd[Winner_ind * DD_SIZE + DD_RAND_STATE + last_smple_idx];
                for (k = 0; k < nStatesDelayedDecision; k++) {
                    if (dd[k * DD_SIZE + DD_RAND_STATE + last_smple_idx] != Winner_rand_state) {
                        ss[k * SS_STRIDE + SS0_RD] = ss[k * SS_STRIDE + SS0_RD] + maxRd;
                        ss[k * SS_STRIDE + SS1_RD] = ss[k * SS_STRIDE + SS1_RD] + maxRd;
                    }
                }

                RDmax_Q10 = ss[0 * SS_STRIDE + SS0_RD];
                RDmin_Q10 = ss[0 * SS_STRIDE + SS1_RD];
                RDmax_ind = 0;
                RDmin_ind = 0;
                for (k = 1; k < nStatesDelayedDecision; k++) {
                    if (ss[k * SS_STRIDE + SS0_RD] > RDmax_Q10) {
                        RDmax_Q10 = ss[k * SS_STRIDE + SS0_RD];
                        RDmax_ind = k;
                    }
                    if (ss[k * SS_STRIDE + SS1_RD] < RDmin_Q10) {
                        RDmin_Q10 = ss[k * SS_STRIDE + SS1_RD];
                        RDmin_ind = k;
                    }
                }
            }

            if (RDmin_Q10 < RDmax_Q10) {
                // Only sLPC_Q14[i + 1 .. i + 15] of the prediction history is read again before it is
                // rewritten, so the rest of that region, which the reference copies too, is skipped.
                var from = RDmin_ind * DD_SIZE;
                var to = RDmax_ind * DD_SIZE;
                System.arraycopy(dd, from + DD_SLPC_Q14 + i + 1, dd, to + DD_SLPC_Q14 + i + 1, NSQ_LPC_BUF_LENGTH - 1);
                System.arraycopy(dd, from + DD_RAND_STATE, dd, to + DD_RAND_STATE, DD_SIZE - DD_RAND_STATE);
                System.arraycopy(ss, RDmin_ind * SS_STRIDE + SS1_Q, ss, RDmax_ind * SS_STRIDE + SS0_Q, 7);
            }

            if (subfr > 0 || i >= decisionDelay) {
                var w = Winner_ind * DD_SIZE;
                pulses[pulsesOff + i - decisionDelay] = (byte) rshiftRound(dd[w + DD_Q_Q10 + last_smple_idx], 10);
                nsq.xq[xqOff + i - decisionDelay] = (short) sat16(rshiftRound(
                        smulww(dd[w + DD_XQ_Q14 + last_smple_idx], delayedGain_Q10[last_smple_idx]), 8));
                sLTP_shp_Q14[nsq.sLTP_shp_buf_idx - decisionDelay] = dd[w + DD_SHAPE_Q14 + last_smple_idx];
                sLTP_Q15[nsq.sLTP_buf_idx - decisionDelay] = dd[w + DD_PRED_Q15 + last_smple_idx];
            }
            nsq.sLTP_shp_buf_idx++;
            nsq.sLTP_buf_idx++;

            for (k = 0; k < nStatesDelayedDecision; k++) {
                var b = k * DD_SIZE;
                var q = ss[k * SS_STRIDE + SS0_Q];
                var slot = smpl_buf_idx;
                dd[b + DD_LF_AR_Q14] = ss[k * SS_STRIDE + SS0_LFAR];
                dd[b + DD_DIFF_Q14] = ss[k * SS_STRIDE + SS0_DIFF];
                dd[b + DD_SLPC_Q14 + NSQ_LPC_BUF_LENGTH + i] = ss[k * SS_STRIDE + SS0_XQ];
                dd[b + DD_XQ_Q14 + slot] = ss[k * SS_STRIDE + SS0_XQ];
                dd[b + DD_Q_Q10 + slot] = q;
                dd[b + DD_PRED_Q15 + slot] = ss[k * SS_STRIDE + SS0_EXC] << 1;
                dd[b + DD_SHAPE_Q14 + slot] = ss[k * SS_STRIDE + SS0_SHP];
                var s = dd[b + DD_SEED] + (((q >> 9) + 1) >> 1);
                dd[b + DD_SEED] = s;
                dd[b + DD_RAND_STATE + slot] = s;
                dd[b + DD_RD_Q10] = ss[k * SS_STRIDE + SS0_RD];
            }
            delayedGain_Q10[smpl_buf_idx] = Gain_Q10;
        }

        for (k = 0; k < nStatesDelayedDecision; k++) {
            System.arraycopy(dd, k * DD_SIZE + DD_SLPC_Q14 + length, dd, k * DD_SIZE + DD_SLPC_Q14, NSQ_LPC_BUF_LENGTH);
        }
    }

    /**
     * Advances the dither seeds and computes, for the four states side by side, the short-term
     * prediction and the noise-shaping feedback of the current sample, updating the warped filter
     * states.
     *
     * <p>The prediction and shaping coefficients are those {@link #lpcCoef} and {@link #arCoef}
     * hold for the subframe. The results are left in {@link #vLpcPred}, {@link #vNAr} and
     * {@link #vNLf}. States beyond the configured number are computed too and ignored.
     *
     * @param i               the sample index within the subframe
     * @param predictLPCOrder the prediction filter order
     * @param shapingLPCOrder the noise-shaping filter order
     * @param warping_Q16     the warping of the shaping filter
     * @param Tilt_Q14        the spectral tilt
     * @param LF_shp_Q14      the packed low-frequency shaping coefficients
     */
    private void predictAndShape(int i, int predictLPCOrder, int shapingLPCOrder, int warping_Q16, int Tilt_Q14,
                                 int LF_shp_Q14) {
        var dd = this.dd;
        final var S0 = 0;
        final var S1 = DD_SIZE;
        final var S2 = 2 * DD_SIZE;
        final var S3 = 3 * DD_SIZE;
        dd[S0 + DD_SEED] = rand(dd[S0 + DD_SEED]);
        dd[S1 + DD_SEED] = rand(dd[S1 + DD_SEED]);
        dd[S2 + DD_SEED] = rand(dd[S2 + DD_SEED]);
        dd[S3 + DD_SEED] = rand(dd[S3 + DD_SEED]);

        final var A = S0 + DD_SAR2_Q14;
        final var B = S1 + DD_SAR2_Q14;
        final var C = S2 + DD_SAR2_Q14;
        final var D = S3 + DD_SAR2_Q14;
        var t2a = smlawb(dd[S0 + DD_DIFF_Q14], dd[A], warping_Q16);
        var t2b = smlawb(dd[S1 + DD_DIFF_Q14], dd[B], warping_Q16);
        var t2c = smlawb(dd[S2 + DD_DIFF_Q14], dd[C], warping_Q16);
        var t2d = smlawb(dd[S3 + DD_DIFF_Q14], dd[D], warping_Q16);
        var t1a = smlawb(dd[A], dd[A + 1] - t2a, warping_Q16);
        var t1b = smlawb(dd[B], dd[B + 1] - t2b, warping_Q16);
        var t1c = smlawb(dd[C], dd[C + 1] - t2c, warping_Q16);
        var t1d = smlawb(dd[D], dd[D + 1] - t2d, warping_Q16);
        dd[A] = t2a;
        dd[B] = t2b;
        dd[C] = t2c;
        dd[D] = t2d;
        for (var j = 2; j < shapingLPCOrder; j += 2) {
            var ua = smlawb(dd[A + j - 1], dd[A + j] - t1a, warping_Q16);
            var ub = smlawb(dd[B + j - 1], dd[B + j] - t1b, warping_Q16);
            var uc = smlawb(dd[C + j - 1], dd[C + j] - t1c, warping_Q16);
            var ud = smlawb(dd[D + j - 1], dd[D + j] - t1d, warping_Q16);
            dd[A + j - 1] = t1a;
            dd[B + j - 1] = t1b;
            dd[C + j - 1] = t1c;
            dd[D + j - 1] = t1d;
            t1a = smlawb(dd[A + j], dd[A + j + 1] - ua, warping_Q16);
            t1b = smlawb(dd[B + j], dd[B + j + 1] - ub, warping_Q16);
            t1c = smlawb(dd[C + j], dd[C + j + 1] - uc, warping_Q16);
            t1d = smlawb(dd[D + j], dd[D + j + 1] - ud, warping_Q16);
            dd[A + j] = ua;
            dd[B + j] = ub;
            dd[C + j] = uc;
            dd[D + j] = ud;
        }
        var last = shapingLPCOrder - 1;
        dd[A + last] = t1a;
        dd[B + last] = t1b;
        dd[C + last] = t1c;
        dd[D + last] = t1d;

        var sums = this.sums;
        SilkKernels.INSTANCE.nsqDotProducts(dd, DD_SIZE, DD_SLPC_Q14 + i, lpcCoef, DD_SAR2_Q14, arCoef, sums);
        var lpcHalf = predictLPCOrder >> 1;
        vLpcPred[0] = (lpcHalf + sums[0]) << 4;
        vLpcPred[1] = (lpcHalf + sums[1]) << 4;
        vLpcPred[2] = (lpcHalf + sums[2]) << 4;
        vLpcPred[3] = (lpcHalf + sums[3]) << 4;
        var arHalf = shapingLPCOrder >> 1;
        var na = (arHalf + sums[4]) << 1;
        var nb = (arHalf + sums[5]) << 1;
        var nc = (arHalf + sums[6]) << 1;
        var nd = (arHalf + sums[7]) << 1;
        var lf0 = dd[S0 + DD_LF_AR_Q14];
        var lf1 = dd[S1 + DD_LF_AR_Q14];
        var lf2 = dd[S2 + DD_LF_AR_Q14];
        var lf3 = dd[S3 + DD_LF_AR_Q14];
        vNAr[0] = smlawb(na, lf0, Tilt_Q14) << 2;
        vNAr[1] = smlawb(nb, lf1, Tilt_Q14) << 2;
        vNAr[2] = smlawb(nc, lf2, Tilt_Q14) << 2;
        vNAr[3] = smlawb(nd, lf3, Tilt_Q14) << 2;

        var s = DD_SHAPE_Q14 + smpl_buf_idx;
        vNLf[0] = smlawt(smulwb(dd[S0 + s], LF_shp_Q14), lf0, LF_shp_Q14) << 2;
        vNLf[1] = smlawt(smulwb(dd[S1 + s], LF_shp_Q14), lf1, LF_shp_Q14) << 2;
        vNLf[2] = smlawt(smulwb(dd[S2 + s], LF_shp_Q14), lf2, LF_shp_Q14) << 2;
        vNLf[3] = smlawt(smulwb(dd[S3 + s], LF_shp_Q14), lf3, LF_shp_Q14) << 2;
    }

    /**
     * Computes, for each of the first {@code states} states, the residual, the two quantization
     * candidates and their rate distortion, and stores both candidates' resulting states in
     * {@link #ss}; the per-state prediction and shaping inputs come from
     * {@link #vLpcPred}, {@link #vNAr} and {@link #vNLf}.
     *
     * <p>The rate/distortion offset {@code rdoOffset} is {@code Lambda_Q10 / 2 - 512} when
     * {@code Lambda_Q10 > 2048} (the aggressive rate/distortion optimization of the reference) and
     * negative otherwise.
     *
     * @implNote This implementation evaluates the reference's data-dependent branches (the dither
     * sign flip, the four quantization-level cases, the aggressive rounding bias and the choice of the
     * better candidate) with sign masks and absolute values instead of jumps, yielding the same
     * integers as the reference. The four level cases collapse into one formula because a candidate
     * level is positive exactly when the reference multiplies it unnegated. All states are evaluated
     * in one call, because a per-state method is too large for the JIT to inline.
     *
     * @param states       the number of states
     * @param x_Q10        the scaled input sample
     * @param LTP_pred_Q14 the long-term prediction
     * @param n_LTP_Q14    the long-term shaping feedback
     * @param offset_Q10   the quantization offset
     * @param Lambda_Q10   the rate/distortion trade-off
     * @param rdoOffset    the aggressive rounding bias, or a negative value when it is off
     */
    private void evaluate(int states, int x_Q10, int LTP_pred_Q14, int n_LTP_Q14, int offset_Q10, int Lambda_Q10,
                          int rdoOffset) {
        var dd = this.dd;
        var x_Q14 = x_Q10 << 4;
        for (var k = 0; k < states; k++) {
            var base = k * DD_SIZE;
            var flip = dd[base + DD_SEED] >> 31;
            var LPC_pred_Q14 = vLpcPred[k];
            var n_AR_Q14 = vNAr[k];
            var n_LF_Q14 = vNLf[k];

            var tmp1 = subSat32(n_LTP_Q14 + LPC_pred_Q14, addSat32(n_AR_Q14, n_LF_Q14));
            tmp1 = ((tmp1 >> 3) + 1) >> 1;
            var r_Q10 = x_Q10 - tmp1;
            r_Q10 = (r_Q10 ^ flip) - flip;
            r_Q10 = Math.min(Math.max(r_Q10, -(31 << 10)), 30 << 10);

            var q1_Q10 = r_Q10 - offset_Q10;
            var q1_Q0 = q1_Q10 >> 10;
            if (rdoOffset >= 0) {
                var negative = q1_Q10 >> 31;
                var excess = Math.abs(q1_Q10) - rdoOffset;
                var biased = ((excess ^ negative) - negative) >> 10;
                var beyond = -excess >> 31;
                q1_Q0 = negative ^ ((biased ^ negative) & beyond);
            }
            var sign = (q1_Q0 >> 31) | (-q1_Q0 >>> 31);
            q1_Q10 = (q1_Q0 << 10) - sign * QUANT_LEVEL_ADJUST_Q10 + offset_Q10;
            var nearZero = q1_Q0 + (q1_Q0 >>> 31);
            var notNearZero = (nearZero | -nearZero) >> 31;
            var q2_Q10 = q1_Q10 + 1024 - (QUANT_LEVEL_ADJUST_Q10 & ~notNearZero);
            var rr_Q10 = r_Q10 - q1_Q10;
            var rd1_Q10 = ((short) Math.abs(q1_Q10) * (short) Lambda_Q10 + (short) rr_Q10 * (short) rr_Q10) >> 10;
            rr_Q10 = r_Q10 - q2_Q10;
            var rd2_Q10 = ((short) Math.abs(q2_Q10) * (short) Lambda_Q10 + (short) rr_Q10 * (short) rr_Q10) >> 10;

            var rdk = dd[base + DD_RD_Q10];
            var first = (int) (((long) rd1_Q10 - rd2_Q10) >> 63);
            var rdSwap = (rd1_Q10 ^ rd2_Q10) & first;
            var qSwap = (q1_Q10 ^ q2_Q10) & first;
            ss[k * SS_STRIDE + SS0_RD] = rdk + (rd2_Q10 ^ rdSwap);
            ss[k * SS_STRIDE + SS1_RD] = rdk + (rd1_Q10 ^ rdSwap);
            var qa = q2_Q10 ^ qSwap;
            var qb = q1_Q10 ^ qSwap;
            ss[k * SS_STRIDE + SS0_Q] = qa;
            ss[k * SS_STRIDE + SS1_Q] = qb;

            var LPC_exc_Q14 = (((qa << 4) ^ flip) - flip) + LTP_pred_Q14;
            var xq_Q14 = LPC_exc_Q14 + LPC_pred_Q14;
            var d = xq_Q14 - x_Q14;
            var sLF_AR_shp_Q14 = d - n_AR_Q14;
            ss[k * SS_STRIDE + SS0_DIFF] = d;
            ss[k * SS_STRIDE + SS0_SHP] = subSat32(sLF_AR_shp_Q14, n_LF_Q14);
            ss[k * SS_STRIDE + SS0_LFAR] = sLF_AR_shp_Q14;
            ss[k * SS_STRIDE + SS0_EXC] = LPC_exc_Q14;
            ss[k * SS_STRIDE + SS0_XQ] = xq_Q14;

            LPC_exc_Q14 = (((qb << 4) ^ flip) - flip) + LTP_pred_Q14;
            xq_Q14 = LPC_exc_Q14 + LPC_pred_Q14;
            d = xq_Q14 - x_Q14;
            sLF_AR_shp_Q14 = d - n_AR_Q14;
            ss[k * SS_STRIDE + SS1_DIFF] = d;
            ss[k * SS_STRIDE + SS1_SHP] = subSat32(sLF_AR_shp_Q14, n_LF_Q14);
            ss[k * SS_STRIDE + SS1_LFAR] = sLF_AR_shp_Q14;
            ss[k * SS_STRIDE + SS1_EXC] = LPC_exc_Q14;
            ss[k * SS_STRIDE + SS1_XQ] = xq_Q14;
        }
    }

    /**
     * Returns the index of the first smallest of four state values, the state the reference's strict
     * {@code <} scan selects.
     *
     * @implNote This implementation packs each value with its index into a {@code long} key whose
     * minimum is the first minimum, so the selection compiles to conditional moves instead of
     * unpredictable branches.
     *
     * @param values the array holding the values, {@link #SS_STRIDE} apart
     * @param offset the index of the first value
     * @return the index of the first minimum
     */
    private static int firstMinimum(int[] values, int offset) {
        var k0 = (long) values[offset] << 2;
        var k1 = ((long) values[offset + SS_STRIDE] << 2) | 1;
        var k2 = ((long) values[offset + 2 * SS_STRIDE] << 2) | 2;
        var k3 = ((long) values[offset + 3 * SS_STRIDE] << 2) | 3;
        return (int) Math.min(Math.min(k0, k1), Math.min(k2, k3)) & 3;
    }

    /**
     * Returns the index of the first largest of four state values, the state the reference's strict
     * {@code >} scan selects.
     *
     * @param values the array holding the values, {@link #SS_STRIDE} apart
     * @param offset the index of the first value
     * @return the index of the first maximum
     */
    private static int firstMaximum(int[] values, int offset) {
        var k0 = ((long) values[offset] << 2) | 3;
        var k1 = ((long) values[offset + SS_STRIDE] << 2) | 2;
        var k2 = ((long) values[offset + 2 * SS_STRIDE] << 2) | 1;
        var k3 = (long) values[offset + 3 * SS_STRIDE] << 2;
        return 3 - ((int) Math.max(Math.max(k0, k1), Math.max(k2, k3)) & 3);
    }

    /**
     * Returns the saturated sum of two values ({@code silk_ADD_SAT32}).
     *
     * @param a the first addend
     * @param b the second addend
     * @return the sum clamped to the {@code int} range
     */
    private static int addSat32(int a, int b) {
        var s = (long) a + b;
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, s));
    }

    /**
     * Returns the saturated difference of two values ({@code silk_SUB_SAT32}).
     *
     * @param a the minuend
     * @param b the subtrahend
     * @return the difference clamped to the {@code int} range
     */
    private static int subSat32(int a, int b) {
        var d = (long) a - b;
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, d));
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
                var b = k * DD_SIZE;
                dd[b + DD_LF_AR_Q14] = smulww(gain_adj_Q16, dd[b + DD_LF_AR_Q14]);
                dd[b + DD_DIFF_Q14] = smulww(gain_adj_Q16, dd[b + DD_DIFF_Q14]);
                for (i = 0; i < NSQ_LPC_BUF_LENGTH; i++) {
                    dd[b + DD_SLPC_Q14 + i] = smulww(gain_adj_Q16, dd[b + DD_SLPC_Q14 + i]);
                }
                for (i = 0; i < MAX_SHAPE_LPC_ORDER; i++) {
                    dd[b + DD_SAR2_Q14 + i] = smulww(gain_adj_Q16, dd[b + DD_SAR2_Q14 + i]);
                }
                for (i = 0; i < DECISION_DELAY; i++) {
                    dd[b + DD_PRED_Q15 + i] = smulww(gain_adj_Q16, dd[b + DD_PRED_Q15 + i]);
                    dd[b + DD_SHAPE_Q14 + i] = smulww(gain_adj_Q16, dd[b + DD_SHAPE_Q14 + i]);
                }
            }

            nsq.prev_gain_Q16 = Gains_Q16[subfr];
        }
    }
}
