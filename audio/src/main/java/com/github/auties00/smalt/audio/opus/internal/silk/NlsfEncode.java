/* Ports libopus 1.5.2 (silk/NLSF_encode.c, silk/NLSF_VQ.c, silk/NLSF_del_dec_quant.c, silk/NLSF_VQ_weights_laroia.c).
   Copyright Skype Limited; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.silk;

import static com.github.auties00.smalt.audio.opus.internal.silk.Define.*;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.*;

/**
 * Quantizes an NLSF vector with the two-stage SILK codebook: a first-stage vector quantizer
 * keeps the best survivors, and a delayed-decision trellis quantizes the residual of each.
 *
 * <p>An instance holds the scratch of one encoder channel.
 */
final class NlsfEncode {
    /**
     * The first-stage quantization error of every codebook vector.
     */
    private final int[] err_Q24 = new int[NLSF_VQ_MAX_VECTORS];

    /**
     * The first-stage index of each survivor.
     */
    private final int[] tempIndices1 = new int[NLSF_VQ_MAX_VECTORS];

    /**
     * The rate-distortion cost of each survivor.
     */
    private final int[] rd_Q25 = new int[NLSF_VQ_MAX_VECTORS];

    /**
     * The second-stage indices of each survivor, {@code MAX_LPC_ORDER} per survivor.
     */
    private final byte[] tempIndices2 = new byte[NLSF_VQ_MAX_VECTORS * MAX_LPC_ORDER];

    /**
     * The first-stage residual.
     */
    private final short[] res_Q10 = new short[MAX_LPC_ORDER];

    /**
     * The first-stage codebook vector.
     */
    private final short[] nlsf_tmp_Q15 = new short[MAX_LPC_ORDER];

    /**
     * The adjusted weights of the residual quantizer.
     */
    private final short[] w_adj_Q5 = new short[MAX_LPC_ORDER];

    /**
     * The backward prediction coefficients of the selected first-stage vector.
     */
    private final short[] pred_Q8 = new short[MAX_LPC_ORDER];

    /**
     * The entropy-table indices of the selected first-stage vector.
     */
    private final short[] ec_ix = new short[MAX_LPC_ORDER];

    /**
     * The trellis indices of each state.
     */
    private final byte[][] ind = new byte[NLSF_QUANT_DEL_DEC_STATES][MAX_LPC_ORDER];

    /**
     * The trellis state indices after sorting ({@code ind_sort}).
     */
    private final int[] ind_sort = new int[NLSF_QUANT_DEL_DEC_STATES];

    /**
     * The previous quantized output of each trellis state and candidate ({@code prev_out_Q10}).
     */
    private final short[] prev_out_Q10 = new short[2 * NLSF_QUANT_DEL_DEC_STATES];

    /**
     * The rate distortion of each trellis state and candidate ({@code RD_Q25}).
     */
    private final int[] trellis_RD_Q25 = new int[2 * NLSF_QUANT_DEL_DEC_STATES];

    /**
     * The smaller rate distortion of each candidate pair ({@code RD_min_Q25}).
     */
    private final int[] RD_min_Q25 = new int[NLSF_QUANT_DEL_DEC_STATES];

    /**
     * The larger rate distortion of each candidate pair ({@code RD_max_Q25}).
     */
    private final int[] RD_max_Q25 = new int[NLSF_QUANT_DEL_DEC_STATES];

    /**
     * The dequantized value of the lower candidate of each index ({@code out0_Q10_table}).
     */
    private final int[] out0_Q10_table = new int[2 * NLSF_QUANT_MAX_AMPLITUDE_EXT];

    /**
     * The dequantized value of the upper candidate of each index ({@code out1_Q10_table}).
     */
    private final int[] out1_Q10_table = new int[2 * NLSF_QUANT_MAX_AMPLITUDE_EXT];

    /**
     * The step size {@link #out0_Q10_table} and {@link #out1_Q10_table} were computed for, or
     * {@link Integer#MIN_VALUE} before the first quantization.
     */
    private int tableStep = Integer.MIN_VALUE;

    /**
     * The NLSF weights of the frame.
     */
    final short[] pNLSFW_QW = new short[MAX_LPC_ORDER];

    /**
     * The predictor work array of the decode that reconstructs the quantized vector.
     */
    private final short[] decodePred_Q8 = new short[MAX_LPC_ORDER];

    /**
     * The entropy-table index work array of the decode that reconstructs the quantized vector.
     */
    private final short[] decodeEc_ix = new short[MAX_LPC_ORDER];

    /**
     * The residual work array of the decode that reconstructs the quantized vector.
     */
    private final short[] decodeRes_Q10 = new short[MAX_LPC_ORDER];

    /**
     * The work arrays of the NLSF to LPC conversions that follow the quantization.
     */
    final Nlsf2A.Scratch nlsf2aScratch = new Nlsf2A.Scratch();

    /**
     * The interpolated NLSFs of the first frame half.
     */
    final short[] pNLSF0_temp_Q15 = new short[MAX_LPC_ORDER];

    /**
     * The NLSF weights of the interpolated first half.
     */
    final short[] pNLSFW0_temp_QW = new short[MAX_LPC_ORDER];

    /**
     * The prediction coefficients produced by one NLSF conversion.
     */
    final short[] a_Q12 = new short[MAX_LPC_ORDER];

    /**
     * The best survivor index, as the one-element output of the final sort.
     */
    private final int[] bestIndex = new int[1];

    /**
     * Creates the quantizer scratch.
     */
    NlsfEncode() {
    }

    /**
     * Quantizes an NLSF vector ({@code silk_NLSF_encode}).
     *
     * @param NLSFIndices receives the codebook path, {@code LPC_ORDER + 1} indices
     * @param pNLSF_Q15   the unquantized NLSFs, replaced by the quantized ones
     * @param psNLSF_CB   the codebook
     * @param pW_Q2       the NLSF weights
     * @param NLSF_mu_Q20 the rate weight of the rate-distortion optimization
     * @param nSurvivors  the maximum number of first-stage survivors
     * @param signalType  the signal type, 0 to 2
     * @return the rate-distortion value in Q25
     */
    int nlsfEncode(byte[] NLSFIndices, short[] pNLSF_Q15, NlsfCodebook psNLSF_CB, short[] pW_Q2, int NLSF_mu_Q20,
                   int nSurvivors, int signalType) {
        int i, s, ind1, prob_Q8, bits_q7;
        int W_tmp_Q9, ret;

        NlsfStabilize.nlsfStabilize(pNLSF_Q15, psNLSF_CB.deltaMin_Q15, psNLSF_CB.order);

        nlsfVq(err_Q24, pNLSF_Q15, psNLSF_CB.CB1_NLSF_Q8, psNLSF_CB.CB1_Wght_Q9, psNLSF_CB.nVectors, psNLSF_CB.order);

        Sort.insertionSortIncreasing(err_Q24, tempIndices1, psNLSF_CB.nVectors, nSurvivors);

        for (s = 0; s < nSurvivors; s++) {
            ind1 = tempIndices1[s];

            var pCB_element = ind1 * psNLSF_CB.order;
            var pCB_Wght_Q9 = ind1 * psNLSF_CB.order;
            for (i = 0; i < psNLSF_CB.order; i++) {
                nlsf_tmp_Q15[i] = (short) (psNLSF_CB.CB1_NLSF_Q8[pCB_element + i] << 7);
                W_tmp_Q9 = psNLSF_CB.CB1_Wght_Q9[pCB_Wght_Q9 + i];
                res_Q10[i] = (short) (smulbb(pNLSF_Q15[i] - nlsf_tmp_Q15[i], W_tmp_Q9) >> 14);
                w_adj_Q5[i] = (short) div32VarQ(pW_Q2[i], smulbb(W_tmp_Q9, W_tmp_Q9), 21);
            }

            NlsfDecode.nlsfUnpack(ec_ix, pred_Q8, psNLSF_CB, ind1);

            rd_Q25[s] = nlsfDelDecQuant(tempIndices2, s * MAX_LPC_ORDER, res_Q10, w_adj_Q5, pred_Q8, ec_ix,
                    psNLSF_CB.ec_Rates_Q5, psNLSF_CB.quantStepSize_Q16, psNLSF_CB.invQuantStepSize_Q6, NLSF_mu_Q20,
                    psNLSF_CB.order);

            var iCDF_ptr = (signalType >> 1) * psNLSF_CB.nVectors;
            if (ind1 == 0) {
                prob_Q8 = 256 - psNLSF_CB.CB1_iCDF[iCDF_ptr + ind1];
            } else {
                prob_Q8 = psNLSF_CB.CB1_iCDF[iCDF_ptr + ind1 - 1] - psNLSF_CB.CB1_iCDF[iCDF_ptr + ind1];
            }
            bits_q7 = (8 << 7) - LinLog.lin2log(prob_Q8);
            rd_Q25[s] = smlabb(rd_Q25[s], bits_q7, NLSF_mu_Q20 >> 2);
        }

        Sort.insertionSortIncreasing(rd_Q25, bestIndex, nSurvivors, 1);

        NLSFIndices[0] = (byte) tempIndices1[bestIndex[0]];
        System.arraycopy(tempIndices2, bestIndex[0] * MAX_LPC_ORDER, NLSFIndices, 1, psNLSF_CB.order);

        NlsfDecode.nlsfDecode(pNLSF_Q15, NLSFIndices, psNLSF_CB, decodePred_Q8, decodeEc_ix, decodeRes_Q10);

        ret = rd_Q25[0];
        return ret;
    }

    /**
     * Computes the weighted predictive quantization error of an NLSF vector against every
     * first-stage codebook vector ({@code silk_NLSF_VQ}).
     *
     * @param err_Q24   receives the {@code K} errors
     * @param in_Q15    the NLSF vector
     * @param pCB_Q8    the codebook vectors
     * @param pWght_Q9  the codebook weights
     * @param K         the number of codebook vectors
     * @param LPC_order the vector length, even
     */
    static void nlsfVq(int[] err_Q24, short[] in_Q15, short[] pCB_Q8, short[] pWght_Q9, int K, int LPC_order) {
        int i, m;
        int diff_Q15, diffw_Q24, sum_error_Q24, pred_Q24;
        var cb_Q8_ptr = 0;
        var w_Q9_ptr = 0;
        for (i = 0; i < K; i++) {
            sum_error_Q24 = 0;
            pred_Q24 = 0;
            for (m = LPC_order - 2; m >= 0; m -= 2) {
                diff_Q15 = in_Q15[m + 1] - (pCB_Q8[cb_Q8_ptr + m + 1] << 7);
                diffw_Q24 = smulbb(diff_Q15, pWght_Q9[w_Q9_ptr + m + 1]);
                sum_error_Q24 = sum_error_Q24 + Math.abs(diffw_Q24 - (pred_Q24 >> 1));
                pred_Q24 = diffw_Q24;

                diff_Q15 = in_Q15[m] - (pCB_Q8[cb_Q8_ptr + m] << 7);
                diffw_Q24 = smulbb(diff_Q15, pWght_Q9[w_Q9_ptr + m]);
                sum_error_Q24 = sum_error_Q24 + Math.abs(diffw_Q24 - (pred_Q24 >> 1));
                pred_Q24 = diffw_Q24;
            }
            err_Q24[i] = sum_error_Q24;
            cb_Q8_ptr += LPC_order;
            w_Q9_ptr += LPC_order;
        }
    }

    /**
     * Quantizes the NLSF residual with a delayed-decision trellis ({@code silk_NLSF_del_dec_quant}).
     *
     * @param indices                receives {@code order} quantization indices
     * @param indicesOff             the index of the first output index
     * @param x_Q10                  the residual
     * @param w_Q5                   the weights
     * @param pred_coef_Q8           the backward predictor coefficients
     * @param ec_ix                  the indices into the entropy coding tables
     * @param ec_rates_Q5            the rates
     * @param quant_step_size_Q16    the quantization step size
     * @param inv_quant_step_size_Q6 the inverse quantization step size
     * @param mu_Q20                 the rate/distortion trade-off
     * @param order                  the number of values
     * @return the rate-distortion value in Q25
     */
    private int nlsfDelDecQuant(byte[] indices, int indicesOff, short[] x_Q10, short[] w_Q5, short[] pred_coef_Q8,
                                short[] ec_ix, short[] ec_rates_Q5, int quant_step_size_Q16,
                                short inv_quant_step_size_Q6, int mu_Q20, short order) {
        int i, j, nStates, ind_tmp, ind_min_max, ind_max_min, in_Q10, res_Q10;
        int pred_Q10, diff_Q10, rate0_Q5, rate1_Q5;
        short out0_Q10, out1_Q10;
        int RD_tmp_Q25, min_Q25, min_max_Q25, max_min_Q25;
        var ind_sort = this.ind_sort;
        var ind = this.ind;
        var prev_out_Q10 = this.prev_out_Q10;
        var RD_Q25 = this.trellis_RD_Q25;
        var RD_min_Q25 = this.RD_min_Q25;
        var RD_max_Q25 = this.RD_max_Q25;
        var out0_Q10_table = this.out0_Q10_table;
        var out1_Q10_table = this.out1_Q10_table;
        var levelAdj = fixConst(NLSF_QUANT_LEVEL_ADJ, 10);

        // The reconstruction levels depend only on the codebook's step size, so they are kept
        // across calls until the step size changes.
        if (tableStep != quant_step_size_Q16) {
            for (i = -NLSF_QUANT_MAX_AMPLITUDE_EXT; i <= NLSF_QUANT_MAX_AMPLITUDE_EXT - 1; i++) {
                out0_Q10 = (short) (i << 10);
                out1_Q10 = (short) (out0_Q10 + 1024);
                if (i > 0) {
                    out0_Q10 = (short) (out0_Q10 - levelAdj);
                    out1_Q10 = (short) (out1_Q10 - levelAdj);
                } else if (i == 0) {
                    out1_Q10 = (short) (out1_Q10 - levelAdj);
                } else if (i == -1) {
                    out0_Q10 = (short) (out0_Q10 + levelAdj);
                } else {
                    out0_Q10 = (short) (out0_Q10 + levelAdj);
                    out1_Q10 = (short) (out1_Q10 + levelAdj);
                }
                out0_Q10_table[i + NLSF_QUANT_MAX_AMPLITUDE_EXT] = smulbb(out0_Q10, quant_step_size_Q16) >> 16;
                out1_Q10_table[i + NLSF_QUANT_MAX_AMPLITUDE_EXT] = smulbb(out1_Q10, quant_step_size_Q16) >> 16;
            }
            tableStep = quant_step_size_Q16;
        }
        nStates = 1;
        RD_Q25[0] = 0;
        prev_out_Q10[0] = 0;
        for (i = order - 1; i >= 0; i--) {
            var rates_Q5 = ec_ix[i];
            in_Q10 = x_Q10[i];
            for (j = 0; j < nStates; j++) {
                pred_Q10 = smulbb((short) pred_coef_Q8[i], prev_out_Q10[j]) >> 8;
                res_Q10 = in_Q10 - pred_Q10;
                ind_tmp = smulbb(inv_quant_step_size_Q6, res_Q10) >> 16;
                ind_tmp = limit(ind_tmp, -NLSF_QUANT_MAX_AMPLITUDE_EXT, NLSF_QUANT_MAX_AMPLITUDE_EXT - 1);
                ind[j][i] = (byte) ind_tmp;

                out0_Q10 = (short) out0_Q10_table[ind_tmp + NLSF_QUANT_MAX_AMPLITUDE_EXT];
                out1_Q10 = (short) out1_Q10_table[ind_tmp + NLSF_QUANT_MAX_AMPLITUDE_EXT];

                out0_Q10 = (short) (out0_Q10 + pred_Q10);
                out1_Q10 = (short) (out1_Q10 + pred_Q10);
                prev_out_Q10[j] = out0_Q10;
                prev_out_Q10[j + nStates] = out1_Q10;

                if (ind_tmp + 1 >= NLSF_QUANT_MAX_AMPLITUDE) {
                    if (ind_tmp + 1 == NLSF_QUANT_MAX_AMPLITUDE) {
                        rate0_Q5 = ec_rates_Q5[rates_Q5 + ind_tmp + NLSF_QUANT_MAX_AMPLITUDE];
                        rate1_Q5 = 280;
                    } else {
                        rate0_Q5 = smlabb(280 - 43 * NLSF_QUANT_MAX_AMPLITUDE, 43, ind_tmp);
                        rate1_Q5 = rate0_Q5 + 43;
                    }
                } else if (ind_tmp <= -NLSF_QUANT_MAX_AMPLITUDE) {
                    if (ind_tmp == -NLSF_QUANT_MAX_AMPLITUDE) {
                        rate0_Q5 = 280;
                        rate1_Q5 = ec_rates_Q5[rates_Q5 + ind_tmp + 1 + NLSF_QUANT_MAX_AMPLITUDE];
                    } else {
                        rate0_Q5 = smlabb(280 - 43 * NLSF_QUANT_MAX_AMPLITUDE, -43, ind_tmp);
                        rate1_Q5 = rate0_Q5 - 43;
                    }
                } else {
                    rate0_Q5 = ec_rates_Q5[rates_Q5 + ind_tmp + NLSF_QUANT_MAX_AMPLITUDE];
                    rate1_Q5 = ec_rates_Q5[rates_Q5 + ind_tmp + 1 + NLSF_QUANT_MAX_AMPLITUDE];
                }
                RD_tmp_Q25 = RD_Q25[j];
                diff_Q10 = in_Q10 - out0_Q10;
                RD_Q25[j] = smlabb(mla(RD_tmp_Q25, smulbb(diff_Q10, diff_Q10), w_Q5[i]), mu_Q20, rate0_Q5);
                diff_Q10 = in_Q10 - out1_Q10;
                RD_Q25[j + nStates] = smlabb(mla(RD_tmp_Q25, smulbb(diff_Q10, diff_Q10), w_Q5[i]), mu_Q20, rate1_Q5);
            }

            if (nStates <= NLSF_QUANT_DEL_DEC_STATES / 2) {
                for (j = 0; j < nStates; j++) {
                    ind[j + nStates][i] = (byte) (ind[j][i] + 1);
                }
                nStates = nStates << 1;
                for (j = nStates; j < NLSF_QUANT_DEL_DEC_STATES; j++) {
                    ind[j][i] = ind[j - nStates][i];
                }
            } else {
                for (j = 0; j < NLSF_QUANT_DEL_DEC_STATES; j++) {
                    if (RD_Q25[j] > RD_Q25[j + NLSF_QUANT_DEL_DEC_STATES]) {
                        RD_max_Q25[j] = RD_Q25[j];
                        RD_min_Q25[j] = RD_Q25[j + NLSF_QUANT_DEL_DEC_STATES];
                        RD_Q25[j] = RD_min_Q25[j];
                        RD_Q25[j + NLSF_QUANT_DEL_DEC_STATES] = RD_max_Q25[j];
                        out0_Q10 = prev_out_Q10[j];
                        prev_out_Q10[j] = prev_out_Q10[j + NLSF_QUANT_DEL_DEC_STATES];
                        prev_out_Q10[j + NLSF_QUANT_DEL_DEC_STATES] = out0_Q10;
                        ind_sort[j] = j + NLSF_QUANT_DEL_DEC_STATES;
                    } else {
                        RD_min_Q25[j] = RD_Q25[j];
                        RD_max_Q25[j] = RD_Q25[j + NLSF_QUANT_DEL_DEC_STATES];
                        ind_sort[j] = j;
                    }
                }
                while (true) {
                    min_max_Q25 = Integer.MAX_VALUE;
                    max_min_Q25 = 0;
                    ind_min_max = 0;
                    ind_max_min = 0;
                    for (j = 0; j < NLSF_QUANT_DEL_DEC_STATES; j++) {
                        if (min_max_Q25 > RD_max_Q25[j]) {
                            min_max_Q25 = RD_max_Q25[j];
                            ind_min_max = j;
                        }
                        if (max_min_Q25 < RD_min_Q25[j]) {
                            max_min_Q25 = RD_min_Q25[j];
                            ind_max_min = j;
                        }
                    }
                    if (min_max_Q25 >= max_min_Q25) {
                        break;
                    }
                    ind_sort[ind_max_min] = ind_sort[ind_min_max] ^ NLSF_QUANT_DEL_DEC_STATES;
                    RD_Q25[ind_max_min] = RD_Q25[ind_min_max + NLSF_QUANT_DEL_DEC_STATES];
                    prev_out_Q10[ind_max_min] = prev_out_Q10[ind_min_max + NLSF_QUANT_DEL_DEC_STATES];
                    RD_min_Q25[ind_max_min] = 0;
                    RD_max_Q25[ind_min_max] = Integer.MAX_VALUE;
                    System.arraycopy(ind[ind_min_max], 0, ind[ind_max_min], 0, MAX_LPC_ORDER);
                }
                for (j = 0; j < NLSF_QUANT_DEL_DEC_STATES; j++) {
                    ind[j][i] += (byte) (ind_sort[j] >> NLSF_QUANT_DEL_DEC_STATES_LOG2);
                }
            }
        }

        ind_tmp = 0;
        min_Q25 = Integer.MAX_VALUE;
        for (j = 0; j < 2 * NLSF_QUANT_DEL_DEC_STATES; j++) {
            if (min_Q25 > RD_Q25[j]) {
                min_Q25 = RD_Q25[j];
                ind_tmp = j;
            }
        }
        for (j = 0; j < order; j++) {
            indices[indicesOff + j] = ind[ind_tmp & (NLSF_QUANT_DEL_DEC_STATES - 1)][j];
        }
        indices[indicesOff] += (byte) (ind_tmp >> NLSF_QUANT_DEL_DEC_STATES_LOG2);
        return min_Q25;
    }

    /**
     * Computes the Laroia low-complexity NLSF weights ({@code silk_NLSF_VQ_weights_laroia}).
     *
     * @param pNLSFW_Q_OUT receives the weights
     * @param pNLSF_Q15    the NLSF vector
     * @param D            the vector length, even
     */
    static void nlsfVqWeightsLaroia(short[] pNLSFW_Q_OUT, short[] pNLSF_Q15, int D) {
        int k;
        int tmp1_int, tmp2_int;

        tmp1_int = Math.max(pNLSF_Q15[0], 1);
        tmp1_int = (1 << (15 + NLSF_W_Q)) / tmp1_int;
        tmp2_int = Math.max(pNLSF_Q15[1] - pNLSF_Q15[0], 1);
        tmp2_int = (1 << (15 + NLSF_W_Q)) / tmp2_int;
        pNLSFW_Q_OUT[0] = (short) Math.min(tmp1_int + tmp2_int, Short.MAX_VALUE);

        for (k = 1; k < D - 1; k += 2) {
            tmp1_int = Math.max(pNLSF_Q15[k + 1] - pNLSF_Q15[k], 1);
            tmp1_int = (1 << (15 + NLSF_W_Q)) / tmp1_int;
            pNLSFW_Q_OUT[k] = (short) Math.min(tmp1_int + tmp2_int, Short.MAX_VALUE);

            tmp2_int = Math.max(pNLSF_Q15[k + 2] - pNLSF_Q15[k + 1], 1);
            tmp2_int = (1 << (15 + NLSF_W_Q)) / tmp2_int;
            pNLSFW_Q_OUT[k + 1] = (short) Math.min(tmp1_int + tmp2_int, Short.MAX_VALUE);
        }

        tmp1_int = Math.max((1 << 15) - pNLSF_Q15[D - 1], 1);
        tmp1_int = (1 << (15 + NLSF_W_Q)) / tmp1_int;
        pNLSFW_Q_OUT[D - 1] = (short) Math.min(tmp1_int + tmp2_int, Short.MAX_VALUE);
    }
}
