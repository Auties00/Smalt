/* Ports libopus 1.5.2 (silk/quant_LTP_gains.c, silk/VQ_WMat_EC.c). Copyright Skype Limited; BSD 3-Clause License.
   Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.silk;

import static com.github.auties00.smalt.audio.opus.internal.silk.Define.*;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.*;

/**
 * Quantizes the long-term prediction gains with the three entropy-constrained, matrix-weighted
 * vector quantizer codebooks and picks the codebook with the lowest rate-distortion cost.
 */
final class QuantLtpGains {
    private QuantLtpGains() {
        throw new AssertionError();
    }

    /**
     * Quantizes the LTP gains of every subframe ({@code silk_quant_LTP_gains}).
     *
     * @param B_Q14             receives the quantized LTP coefficients
     * @param cbk_index         receives the codebook index of each subframe
     * @param periodicity_index receives the periodicity (codebook) index at index 0
     * @param sum_log_gain_Q7   the cumulative maximum prediction gain at index 0, updated
     * @param pred_gain_dB_Q7   receives the LTP prediction gain at index 0
     * @param XX_Q17            the correlation matrices, {@code LTP_ORDER x LTP_ORDER} per
     *                          subframe
     * @param xX_Q17            the correlation vectors, {@code LTP_ORDER} per subframe
     * @param subfr_len         the subframe length
     * @param nb_subfr          the number of subframes
     * @param temp_idx          a work array of {@code MAX_NB_SUBFR} codebook indices, overwritten
     * @param out               a work array of four values, overwritten
     */
    static void quantLtpGains(short[] B_Q14, byte[] cbk_index, byte[] periodicity_index, int[] sum_log_gain_Q7,
                              int[] pred_gain_dB_Q7, int[] XX_Q17, int[] xX_Q17, int subfr_len, int nb_subfr,
                              byte[] temp_idx, int[] out) {
        int j, k, cbk_size;
        int res_nrg_Q15 = 0, rate_dist_Q7, min_rate_dist_Q7;
        int sum_log_gain_tmp_Q7, best_sum_log_gain_Q7, max_gain_Q7;

        min_rate_dist_Q7 = Integer.MAX_VALUE;
        best_sum_log_gain_Q7 = 0;
        for (k = 0; k < 3; k++) {
            var gain_safety = fixConst(0.4, 7);

            var cl_ptr_Q5 = Tables.silk_LTP_gain_BITS_Q5_ptrs[k];
            var cbk_ptr_Q7 = Tables.silk_LTP_vq_ptrs_Q7[k];
            var cbk_gain_ptr_Q7 = Tables.silk_LTP_vq_gain_ptrs_Q7[k];
            cbk_size = Tables.silk_LTP_vq_sizes[k];

            var XX_Q17_ptr = 0;
            var xX_Q17_ptr = 0;

            res_nrg_Q15 = 0;
            rate_dist_Q7 = 0;
            sum_log_gain_tmp_Q7 = sum_log_gain_Q7[0];
            for (j = 0; j < nb_subfr; j++) {
                max_gain_Q7 = LinLog.log2lin((fixConst((double) TuningParameters.MAX_SUM_LOG_GAIN_DB / 6.0, 7) - sum_log_gain_tmp_Q7)
                        + fixConst(7, 7)) - gain_safety;
                vqWMatEc(out, temp_idx, j, XX_Q17, XX_Q17_ptr, xX_Q17, xX_Q17_ptr, cbk_ptr_Q7, cbk_gain_ptr_Q7,
                        cl_ptr_Q5, subfr_len, max_gain_Q7, cbk_size);
                var res_nrg_Q15_subfr = out[0];
                var rate_dist_Q7_subfr = out[1];
                var gain_Q7 = out[2];

                res_nrg_Q15 = addPosSat32(res_nrg_Q15, res_nrg_Q15_subfr);
                rate_dist_Q7 = addPosSat32(rate_dist_Q7, rate_dist_Q7_subfr);
                sum_log_gain_tmp_Q7 = Math.max(0, sum_log_gain_tmp_Q7
                        + LinLog.lin2log(gain_safety + gain_Q7) - fixConst(7, 7));

                XX_Q17_ptr += LTP_ORDER * LTP_ORDER;
                xX_Q17_ptr += LTP_ORDER;
            }

            if (rate_dist_Q7 <= min_rate_dist_Q7) {
                min_rate_dist_Q7 = rate_dist_Q7;
                periodicity_index[0] = (byte) k;
                System.arraycopy(temp_idx, 0, cbk_index, 0, nb_subfr);
                best_sum_log_gain_Q7 = sum_log_gain_tmp_Q7;
            }
        }

        var cbk_ptr_Q7 = Tables.silk_LTP_vq_ptrs_Q7[periodicity_index[0]];
        for (j = 0; j < nb_subfr; j++) {
            for (k = 0; k < LTP_ORDER; k++) {
                B_Q14[j * LTP_ORDER + k] = (short) (cbk_ptr_Q7[cbk_index[j] * LTP_ORDER + k] << 7);
            }
        }

        if (nb_subfr == 2) {
            res_nrg_Q15 = res_nrg_Q15 >> 1;
        } else {
            res_nrg_Q15 = res_nrg_Q15 >> 2;
        }

        sum_log_gain_Q7[0] = best_sum_log_gain_Q7;
        pred_gain_dB_Q7[0] = smulbb(-3, LinLog.lin2log(res_nrg_Q15) - (15 << 7));
    }

    /**
     * Finds the best entry of one LTP codebook for one subframe ({@code silk_VQ_WMat_EC_c}).
     *
     * <p>The results are stored in {@code out}: the residual energy at index 0, the rate
     * distortion at index 1 and the gain at index 2; the chosen index is stored in
     * {@code ind[indOff]}.
     *
     * @param out         receives the residual energy, rate distortion and gain
     * @param ind         receives the index of the best codebook vector
     * @param indOff      the index in {@code ind} to write
     * @param XX_Q17      the correlation matrices
     * @param XXOff       the index of the subframe's matrix
     * @param xX_Q17      the correlation vectors
     * @param xXOff       the index of the subframe's vector
     * @param cb_Q7       the codebook
     * @param cb_gain_Q7  the effective gain of each codebook vector
     * @param cl_Q5       the code length of each codebook vector
     * @param subfr_len   the subframe length
     * @param max_gain_Q7 the maximum sum of absolute LTP coefficients
     * @param L           the number of codebook vectors
     */
    static void vqWMatEc(int[] out, byte[] ind, int indOff, int[] XX_Q17, int XXOff, int[] xX_Q17, int xXOff,
                         byte[] cb_Q7, short[] cb_gain_Q7, short[] cl_Q5, int subfr_len, int max_gain_Q7, int L) {
        int k, gain_tmp_Q7;
        int neg_xX_Q24_0, neg_xX_Q24_1, neg_xX_Q24_2, neg_xX_Q24_3, neg_xX_Q24_4;
        int sum1_Q15, sum2_Q24;
        int bits_res_Q8, bits_tot_Q8;

        neg_xX_Q24_0 = -(xX_Q17[xXOff] << 7);
        neg_xX_Q24_1 = -(xX_Q17[xXOff + 1] << 7);
        neg_xX_Q24_2 = -(xX_Q17[xXOff + 2] << 7);
        neg_xX_Q24_3 = -(xX_Q17[xXOff + 3] << 7);
        neg_xX_Q24_4 = -(xX_Q17[xXOff + 4] << 7);

        var rate_dist_Q8 = Integer.MAX_VALUE;
        var res_nrg_Q15 = Integer.MAX_VALUE;
        var gain_Q7 = out[2];
        var cb_row_Q7 = 0;
        ind[indOff] = 0;
        for (k = 0; k < L; k++) {
            int penalty;
            gain_tmp_Q7 = cb_gain_Q7[k];
            sum1_Q15 = fixConst(1.001, 15);

            penalty = Math.max(gain_tmp_Q7 - max_gain_Q7, 0) << 11;

            var c0 = cb_Q7[cb_row_Q7];
            var c1 = cb_Q7[cb_row_Q7 + 1];
            var c2 = cb_Q7[cb_row_Q7 + 2];
            var c3 = cb_Q7[cb_row_Q7 + 3];
            var c4 = cb_Q7[cb_row_Q7 + 4];

            sum2_Q24 = mla(neg_xX_Q24_0, XX_Q17[XXOff + 1], c1);
            sum2_Q24 = mla(sum2_Q24, XX_Q17[XXOff + 2], c2);
            sum2_Q24 = mla(sum2_Q24, XX_Q17[XXOff + 3], c3);
            sum2_Q24 = mla(sum2_Q24, XX_Q17[XXOff + 4], c4);
            sum2_Q24 = sum2_Q24 << 1;
            sum2_Q24 = mla(sum2_Q24, XX_Q17[XXOff], c0);
            sum1_Q15 = smlawb(sum1_Q15, sum2_Q24, c0);

            sum2_Q24 = mla(neg_xX_Q24_1, XX_Q17[XXOff + 7], c2);
            sum2_Q24 = mla(sum2_Q24, XX_Q17[XXOff + 8], c3);
            sum2_Q24 = mla(sum2_Q24, XX_Q17[XXOff + 9], c4);
            sum2_Q24 = sum2_Q24 << 1;
            sum2_Q24 = mla(sum2_Q24, XX_Q17[XXOff + 6], c1);
            sum1_Q15 = smlawb(sum1_Q15, sum2_Q24, c1);

            sum2_Q24 = mla(neg_xX_Q24_2, XX_Q17[XXOff + 13], c3);
            sum2_Q24 = mla(sum2_Q24, XX_Q17[XXOff + 14], c4);
            sum2_Q24 = sum2_Q24 << 1;
            sum2_Q24 = mla(sum2_Q24, XX_Q17[XXOff + 12], c2);
            sum1_Q15 = smlawb(sum1_Q15, sum2_Q24, c2);

            sum2_Q24 = mla(neg_xX_Q24_3, XX_Q17[XXOff + 19], c4);
            sum2_Q24 = sum2_Q24 << 1;
            sum2_Q24 = mla(sum2_Q24, XX_Q17[XXOff + 18], c3);
            sum1_Q15 = smlawb(sum1_Q15, sum2_Q24, c3);

            sum2_Q24 = neg_xX_Q24_4 << 1;
            sum2_Q24 = mla(sum2_Q24, XX_Q17[XXOff + 24], c4);
            sum1_Q15 = smlawb(sum1_Q15, sum2_Q24, c4);

            if (sum1_Q15 >= 0) {
                bits_res_Q8 = smulbb(subfr_len, LinLog.lin2log(sum1_Q15 + penalty) - (15 << 7));
                bits_tot_Q8 = bits_res_Q8 + (cl_Q5[k] << (3 - 1));
                if (bits_tot_Q8 <= rate_dist_Q8) {
                    rate_dist_Q8 = bits_tot_Q8;
                    res_nrg_Q15 = sum1_Q15 + penalty;
                    ind[indOff] = (byte) k;
                    gain_Q7 = gain_tmp_Q7;
                }
            }

            cb_row_Q7 += LTP_ORDER;
        }
        out[0] = res_nrg_Q15;
        out[1] = rate_dist_Q8;
        out[2] = gain_Q7;
    }
}
