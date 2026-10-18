/* Ports libopus 1.5.2 (silk/float/pitch_analysis_core_FLP.c). Copyright Skype Limited; BSD 3-Clause License. Modified
   for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.silk;

import com.github.auties00.smalt.audio.opus.internal.celt.Pitch;

import java.util.Arrays;

import static com.github.auties00.smalt.audio.opus.internal.silk.PitchEstTables.*;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.*;

/**
 * Estimates the pitch lags of a SILK frame with the three-stage search of the float encoder: a
 * coarse normalized correlation search at 4 kHz, a codebook search around the best candidates
 * at 8 kHz, and a contour refinement at the internal rate.
 *
 * <p>An instance holds the scratch of one encoder channel, so an analysis allocates nothing.
 */
final class PitchAnalysisCoreFlp {
    /**
     * The size of the stage-3 scratch correlations ({@code SCRATCH_SIZE}).
     */
    private static final int SCRATCH_SIZE = 22;

    /**
     * The row stride of the correlation matrix {@link #c}.
     */
    private static final int C_STRIDE = (PE_MAX_LAG >> 1) + 5;

    /**
     * The frame decimated to 8 kHz.
     */
    private final float[] frame_8kHz = new float[PE_MAX_FRAME_LENGTH_MS * 8];

    /**
     * The frame decimated to 4 kHz.
     */
    private final float[] frame_4kHz = new float[PE_MAX_FRAME_LENGTH_MS * 4];

    /**
     * The 8 kHz frame as 16-bit integers.
     */
    private final short[] frame_8_FIX = new short[PE_MAX_FRAME_LENGTH_MS * 8];

    /**
     * The 4 kHz frame as 16-bit integers.
     */
    private final short[] frame_4_FIX = new short[PE_MAX_FRAME_LENGTH_MS * 4];

    /**
     * The input frame as 16-bit integers, before decimation.
     */
    private final short[] frame_FIX = new short[16 * PE_MAX_FRAME_LENGTH_MS];

    /**
     * The state of the decimation filters.
     */
    private final int[] filt_state = new int[6];

    /**
     * The normalized correlations per subframe and lag, {@link #C_STRIDE} per row.
     */
    private final float[] c = new float[PE_MAX_NB_SUBFR * C_STRIDE];

    /**
     * The first-stage cross-correlations.
     */
    private final float[] xcorr = new float[PE_MAX_LAG_MS * 4 - PE_MIN_LAG_MS * 4 + 1];

    /**
     * The codebook correlation sums of the second stage.
     */
    private final float[] cc = new float[PE_NB_CBKS_STAGE2_EXT];

    /**
     * The candidate lags.
     */
    private final int[] d_srch = new int[PE_D_SRCH_LENGTH];

    /**
     * The lag candidate map and, after compaction, the lags to compute.
     */
    private final short[] d_comp = new short[C_STRIDE];

    /**
     * The stage-3 energies, indexed {@code [subframe][codebook][lag]}.
     */
    private final float[] energies_st3 = new float[PE_MAX_NB_SUBFR * PE_NB_CBKS_STAGE3_MAX * PE_NB_STAGE3_LAGS];

    /**
     * The stage-3 cross-correlations, indexed {@code [subframe][codebook][lag]}.
     */
    private final float[] cross_corr_st3 = new float[PE_MAX_NB_SUBFR * PE_NB_CBKS_STAGE3_MAX * PE_NB_STAGE3_LAGS];

    /**
     * The stage-3 scratch values.
     */
    private final float[] scratch_mem = new float[SCRATCH_SIZE];

    /**
     * The stage-3 cross-correlations of one subframe.
     */
    private final float[] xcorr_st3 = new float[SCRATCH_SIZE];

    /**
     * Creates the estimator scratch.
     */
    PitchAnalysisCoreFlp() {
    }

    /**
     * Returns the index of a stage-3 element.
     *
     * @param k the subframe
     * @param j the codebook entry
     * @param l the lag
     * @return the flat index
     */
    private static int st3(int k, int j, int l) {
        return (k * PE_NB_CBKS_STAGE3_MAX + j) * PE_NB_STAGE3_LAGS + l;
    }

    /**
     * Estimates the pitch of a frame ({@code silk_pitch_analysis_core_FLP}).
     *
     * @param frame         the signal, {@code PE_FRAME_LENGTH_MS * Fs_kHz} samples
     * @param frameOff      the index of the first sample
     * @param pitch_out     receives the pitch lag of each subframe
     * @param psIndices     receives the lag index and contour index
     * @param LTPCorr       the normalized correlation of the previous frame at index 0, replaced
     *                      by this frame's
     * @param prevLag       the last lag of the previous frame, or zero if it was unvoiced
     * @param search_thres1 the first-stage threshold, 0 to 1
     * @param search_thres2 the final threshold, 0 to 1
     * @param Fs_kHz        the sampling rate in kHz
     * @param complexity    the complexity, 0 to 2
     * @param nb_subfr      the number of 5 ms subframes
     * @return {@code 0} for a voiced frame, {@code 1} for an unvoiced one
     */
    int pitchAnalysisCore(float[] frame, int frameOff, int[] pitch_out, SideInfoIndices psIndices, float[] LTPCorr,
                          int prevLag, float search_thres1, float search_thres2, int Fs_kHz, int complexity,
                          int nb_subfr) {
        int i, k, d, j;
        float threshold, contour_bias;
        double cross_corr, normalizer, energy, energy_tmp;
        int length_d_srch, length_d_comp;
        float Cmax, CCmax, CCmax_b, CCmax_new_b, CCmax_new;
        int CBimax, CBimax_new, lag, start_lag, end_lag, lag_new;
        int cbk_size;
        float lag_log2, prevLag_log2, delta_lag_log2_sqr;
        int lag_counter;
        int frame_length, frame_length_8kHz, frame_length_4kHz;
        int sf_length, sf_length_8kHz, sf_length_4kHz;
        int min_lag, min_lag_8kHz, min_lag_4kHz;
        int max_lag, max_lag_8kHz, max_lag_4kHz;
        int nb_cbk_search;
        byte[][] Lag_CB_ptr;
        var C = c;

        frame_length = (PE_LTP_MEM_LENGTH_MS + nb_subfr * PE_SUBFR_LENGTH_MS) * Fs_kHz;
        frame_length_4kHz = (PE_LTP_MEM_LENGTH_MS + nb_subfr * PE_SUBFR_LENGTH_MS) * 4;
        frame_length_8kHz = (PE_LTP_MEM_LENGTH_MS + nb_subfr * PE_SUBFR_LENGTH_MS) * 8;
        sf_length = PE_SUBFR_LENGTH_MS * Fs_kHz;
        sf_length_4kHz = PE_SUBFR_LENGTH_MS * 4;
        sf_length_8kHz = PE_SUBFR_LENGTH_MS * 8;
        min_lag = PE_MIN_LAG_MS * Fs_kHz;
        min_lag_4kHz = PE_MIN_LAG_MS * 4;
        min_lag_8kHz = PE_MIN_LAG_MS * 8;
        max_lag = PE_MAX_LAG_MS * Fs_kHz - 1;
        max_lag_4kHz = PE_MAX_LAG_MS * 4;
        max_lag_8kHz = PE_MAX_LAG_MS * 8 - 1;

        if (Fs_kHz == 16) {
            SigProcFlp.float2shortArray(frame_FIX, 0, frame, frameOff, frame_length);
            filt_state[0] = 0;
            filt_state[1] = 0;
            ResamplerDown.down2(filt_state, frame_8_FIX, 0, frame_FIX, 0, frame_length);
            SigProcFlp.short2floatArray(frame_8kHz, 0, frame_8_FIX, 0, frame_length_8kHz);
        } else if (Fs_kHz == 12) {
            SigProcFlp.float2shortArray(frame_FIX, 0, frame, frameOff, frame_length);
            Arrays.fill(filt_state, 0);
            ResamplerDown.down2_3(filt_state, frame_8_FIX, 0, frame_FIX, 0, frame_length);
            SigProcFlp.short2floatArray(frame_8kHz, 0, frame_8_FIX, 0, frame_length_8kHz);
        } else {
            SigProcFlp.float2shortArray(frame_8_FIX, 0, frame, frameOff, frame_length_8kHz);
        }

        filt_state[0] = 0;
        filt_state[1] = 0;
        ResamplerDown.down2(filt_state, frame_4_FIX, 0, frame_8_FIX, 0, frame_length_8kHz);
        SigProcFlp.short2floatArray(frame_4kHz, 0, frame_4_FIX, 0, frame_length_4kHz);

        for (i = frame_length_4kHz - 1; i > 0; i--) {
            frame_4kHz[i] = (short) sat16((int) frame_4kHz[i] + (int) frame_4kHz[i - 1]);
        }

        Arrays.fill(C, 0, nb_subfr * C_STRIDE, 0f);
        var target_ptr = sf_length_4kHz << 2;
        for (k = 0; k < nb_subfr >> 1; k++) {
            var basis_ptr = target_ptr - min_lag_4kHz;

            Pitch.celtPitchXcorr(frame_4kHz, target_ptr, frame_4kHz, target_ptr - max_lag_4kHz, xcorr, 0, sf_length_8kHz,
                    max_lag_4kHz - min_lag_4kHz + 1);

            cross_corr = xcorr[max_lag_4kHz - min_lag_4kHz];
            normalizer = SigProcFlp.energyFlp(frame_4kHz, target_ptr, sf_length_8kHz)
                    + SigProcFlp.energyFlp(frame_4kHz, basis_ptr, sf_length_8kHz)
                    + sf_length_8kHz * 4000.0f;

            C[min_lag_4kHz] += (float) (2 * cross_corr / normalizer);

            for (d = min_lag_4kHz + 1; d <= max_lag_4kHz; d++) {
                basis_ptr--;

                cross_corr = xcorr[max_lag_4kHz - d];

                normalizer += frame_4kHz[basis_ptr] * (double) frame_4kHz[basis_ptr]
                        - frame_4kHz[basis_ptr + sf_length_8kHz] * (double) frame_4kHz[basis_ptr + sf_length_8kHz];
                C[d] += (float) (2 * cross_corr / normalizer);
            }
            target_ptr += sf_length_8kHz;
        }

        for (i = max_lag_4kHz; i >= min_lag_4kHz; i--) {
            C[i] -= C[i] * i / 4096.0f;
        }

        length_d_srch = 4 + 2 * complexity;
        SigProcFlp.insertionSortDecreasingFlp(C, min_lag_4kHz, d_srch, max_lag_4kHz - min_lag_4kHz + 1, length_d_srch);

        Cmax = C[min_lag_4kHz];
        if (Cmax < 0.2f) {
            Arrays.fill(pitch_out, 0, nb_subfr, 0);
            LTPCorr[0] = 0.0f;
            psIndices.lagIndex = 0;
            psIndices.contourIndex = 0;
            return 1;
        }

        threshold = search_thres1 * Cmax;
        for (i = 0; i < length_d_srch; i++) {
            if (C[min_lag_4kHz + i] > threshold) {
                d_srch[i] = (d_srch[i] + min_lag_4kHz) << 1;
            } else {
                length_d_srch = i;
                break;
            }
        }

        for (i = min_lag_8kHz - 5; i < max_lag_8kHz + 5; i++) {
            d_comp[i] = 0;
        }
        for (i = 0; i < length_d_srch; i++) {
            d_comp[d_srch[i]] = 1;
        }

        for (i = max_lag_8kHz + 3; i >= min_lag_8kHz; i--) {
            d_comp[i] += (short) (d_comp[i - 1] + d_comp[i - 2]);
        }

        length_d_srch = 0;
        for (i = min_lag_8kHz; i < max_lag_8kHz + 1; i++) {
            if (d_comp[i + 1] > 0) {
                d_srch[length_d_srch] = i;
                length_d_srch++;
            }
        }

        for (i = max_lag_8kHz + 3; i >= min_lag_8kHz; i--) {
            d_comp[i] += (short) (d_comp[i - 1] + d_comp[i - 2] + d_comp[i - 3]);
        }

        length_d_comp = 0;
        for (i = min_lag_8kHz; i < max_lag_8kHz + 4; i++) {
            if (d_comp[i] > 0) {
                d_comp[length_d_comp] = (short) (i - 2);
                length_d_comp++;
            }
        }

        Arrays.fill(C, 0, PE_MAX_NB_SUBFR * C_STRIDE, 0f);

        float[] target;
        int targetOff;
        if (Fs_kHz == 8) {
            target = frame;
            targetOff = frameOff + PE_LTP_MEM_LENGTH_MS * 8;
        } else {
            target = frame_8kHz;
            targetOff = PE_LTP_MEM_LENGTH_MS * 8;
        }
        for (k = 0; k < nb_subfr; k++) {
            energy_tmp = SigProcFlp.energyFlp(target, targetOff, sf_length_8kHz) + 1.0;
            for (j = 0; j < length_d_comp; j++) {
                d = d_comp[j];
                var basis = targetOff - d;
                cross_corr = SigProcFlp.innerProductFlp(target, basis, target, targetOff, sf_length_8kHz);
                if (cross_corr > 0.0f) {
                    energy = SigProcFlp.energyFlp(target, basis, sf_length_8kHz);
                    C[k * C_STRIDE + d] = (float) (2 * cross_corr / (energy + energy_tmp));
                } else {
                    C[k * C_STRIDE + d] = 0.0f;
                }
            }
            targetOff += sf_length_8kHz;
        }

        CCmax = 0.0f;
        CCmax_b = -1000.0f;

        CBimax = 0;
        lag = -1;

        if (prevLag > 0) {
            if (Fs_kHz == 12) {
                prevLag = (prevLag << 1) / 3;
            } else if (Fs_kHz == 16) {
                prevLag = prevLag >> 1;
            }
            prevLag_log2 = SigProcFlp.log2(prevLag);
        } else {
            prevLag_log2 = 0;
        }

        if (nb_subfr == PE_MAX_NB_SUBFR) {
            cbk_size = PE_NB_CBKS_STAGE2_EXT;
            Lag_CB_ptr = silk_CB_lags_stage2;
            if (Fs_kHz == 8 && complexity > SILK_PE_MIN_COMPLEX) {
                nb_cbk_search = PE_NB_CBKS_STAGE2_EXT;
            } else {
                nb_cbk_search = PE_NB_CBKS_STAGE2;
            }
        } else {
            cbk_size = PE_NB_CBKS_STAGE2_10MS;
            Lag_CB_ptr = silk_CB_lags_stage2_10_ms;
            nb_cbk_search = PE_NB_CBKS_STAGE2_10MS;
        }

        for (k = 0; k < length_d_srch; k++) {
            d = d_srch[k];
            for (j = 0; j < nb_cbk_search; j++) {
                cc[j] = 0.0f;
                for (i = 0; i < nb_subfr; i++) {
                    cc[j] += C[i * C_STRIDE + d + Lag_CB_ptr[i][j]];
                }
            }
            CCmax_new = -1000.0f;
            CBimax_new = 0;
            for (i = 0; i < nb_cbk_search; i++) {
                if (cc[i] > CCmax_new) {
                    CCmax_new = cc[i];
                    CBimax_new = i;
                }
            }

            lag_log2 = SigProcFlp.log2(d);
            CCmax_new_b = CCmax_new - PE_SHORTLAG_BIAS * nb_subfr * lag_log2;

            if (prevLag > 0) {
                delta_lag_log2_sqr = lag_log2 - prevLag_log2;
                delta_lag_log2_sqr *= delta_lag_log2_sqr;
                CCmax_new_b -= PE_PREVLAG_BIAS * nb_subfr * LTPCorr[0] * delta_lag_log2_sqr / (delta_lag_log2_sqr + 0.5f);
            }

            if (CCmax_new_b > CCmax_b && CCmax_new > nb_subfr * search_thres2) {
                CCmax_b = CCmax_new_b;
                CCmax = CCmax_new;
                lag = d;
                CBimax = CBimax_new;
            }
        }

        if (lag == -1) {
            Arrays.fill(pitch_out, 0, PE_MAX_NB_SUBFR, 0);
            LTPCorr[0] = 0.0f;
            psIndices.lagIndex = 0;
            psIndices.contourIndex = 0;
            return 1;
        }

        LTPCorr[0] = CCmax / nb_subfr;

        if (Fs_kHz > 8) {
            if (Fs_kHz == 12) {
                lag = rshiftRound(smulbb(lag, 3), 1);
            } else {
                lag = lag << 1;
            }

            lag = limit(lag, min_lag, max_lag);
            start_lag = Math.max(lag - 2, min_lag);
            end_lag = Math.min(lag + 2, max_lag);
            lag_new = lag;
            CBimax = 0;

            CCmax = -1000.0f;

            calcCorrSt3(frame, frameOff, start_lag, sf_length, nb_subfr, complexity);
            calcEnergySt3(frame, frameOff, start_lag, sf_length, nb_subfr, complexity);

            lag_counter = 0;
            contour_bias = PE_FLATCONTOUR_BIAS / lag;

            if (nb_subfr == PE_MAX_NB_SUBFR) {
                nb_cbk_search = silk_nb_cbk_searchs_stage3[complexity];
                cbk_size = PE_NB_CBKS_STAGE3_MAX;
                Lag_CB_ptr = silk_CB_lags_stage3;
            } else {
                nb_cbk_search = PE_NB_CBKS_STAGE3_10MS;
                cbk_size = PE_NB_CBKS_STAGE3_10MS;
                Lag_CB_ptr = silk_CB_lags_stage3_10_ms;
            }

            var tOff = frameOff + PE_LTP_MEM_LENGTH_MS * Fs_kHz;
            energy_tmp = SigProcFlp.energyFlp(frame, tOff, nb_subfr * sf_length) + 1.0;
            for (d = start_lag; d <= end_lag; d++) {
                for (j = 0; j < nb_cbk_search; j++) {
                    cross_corr = 0.0;
                    energy = energy_tmp;
                    for (k = 0; k < nb_subfr; k++) {
                        cross_corr += cross_corr_st3[st3(k, j, lag_counter)];
                        energy += energies_st3[st3(k, j, lag_counter)];
                    }
                    if (cross_corr > 0.0) {
                        CCmax_new = (float) (2 * cross_corr / energy);
                        CCmax_new *= 1.0f - contour_bias * j;
                    } else {
                        CCmax_new = 0.0f;
                    }

                    if (CCmax_new > CCmax && (d + silk_CB_lags_stage3[0][j]) <= max_lag) {
                        CCmax = CCmax_new;
                        lag_new = d;
                        CBimax = j;
                    }
                }
                lag_counter++;
            }

            for (k = 0; k < nb_subfr; k++) {
                pitch_out[k] = lag_new + Lag_CB_ptr[k][CBimax];
                pitch_out[k] = limit(pitch_out[k], min_lag, PE_MAX_LAG_MS * Fs_kHz);
            }
            psIndices.lagIndex = (short) (lag_new - min_lag);
            psIndices.contourIndex = (byte) CBimax;
        } else {
            for (k = 0; k < nb_subfr; k++) {
                pitch_out[k] = lag + Lag_CB_ptr[k][CBimax];
                pitch_out[k] = limit(pitch_out[k], min_lag_8kHz, PE_MAX_LAG_MS * 8);
            }
            psIndices.lagIndex = (short) (lag - min_lag_8kHz);
            psIndices.contourIndex = (byte) CBimax;
        }
        return 0;
    }

    /**
     * Computes the correlations of the stage-3 search for every codebook entry and lag offset
     * ({@code silk_P_Ana_calc_corr_st3}).
     *
     * @param frame      the signal
     * @param frameOff   the index of the first sample
     * @param start_lag  the first lag
     * @param sf_length  the subframe length
     * @param nb_subfr   the number of subframes
     * @param complexity the complexity, 0 to 2
     */
    private void calcCorrSt3(float[] frame, int frameOff, int start_lag, int sf_length, int nb_subfr, int complexity) {
        int i, j, k, lag_counter, lag_low, lag_high;
        int nb_cbk_search, delta, idx;
        byte[][] Lag_range_ptr, Lag_CB_ptr;

        if (nb_subfr == PE_MAX_NB_SUBFR) {
            Lag_range_ptr = silk_Lag_range_stage3[complexity];
            Lag_CB_ptr = silk_CB_lags_stage3;
            nb_cbk_search = silk_nb_cbk_searchs_stage3[complexity];
        } else {
            Lag_range_ptr = silk_Lag_range_stage3_10_ms;
            Lag_CB_ptr = silk_CB_lags_stage3_10_ms;
            nb_cbk_search = PE_NB_CBKS_STAGE3_10MS;
        }

        var target_ptr = frameOff + (sf_length << 2);
        for (k = 0; k < nb_subfr; k++) {
            lag_counter = 0;

            lag_low = Lag_range_ptr[k][0];
            lag_high = Lag_range_ptr[k][1];
            Pitch.celtPitchXcorr(frame, target_ptr, frame, target_ptr - start_lag - lag_high, xcorr_st3, 0, sf_length,
                    lag_high - lag_low + 1);
            for (j = lag_low; j <= lag_high; j++) {
                scratch_mem[lag_counter] = xcorr_st3[lag_high - j];
                lag_counter++;
            }

            delta = Lag_range_ptr[k][0];
            for (i = 0; i < nb_cbk_search; i++) {
                idx = Lag_CB_ptr[k][i] - delta;
                for (j = 0; j < PE_NB_STAGE3_LAGS; j++) {
                    cross_corr_st3[st3(k, i, j)] = scratch_mem[idx + j];
                }
            }
            target_ptr += sf_length;
        }
    }

    /**
     * Computes the energies of the stage-3 search recursively for every codebook entry and lag
     * offset ({@code silk_P_Ana_calc_energy_st3}).
     *
     * @param frame      the signal
     * @param frameOff   the index of the first sample
     * @param start_lag  the first lag
     * @param sf_length  the subframe length
     * @param nb_subfr   the number of subframes
     * @param complexity the complexity, 0 to 2
     */
    private void calcEnergySt3(float[] frame, int frameOff, int start_lag, int sf_length, int nb_subfr, int complexity) {
        double energy;
        int k, i, j, lag_counter;
        int nb_cbk_search, delta, idx, lag_diff;
        byte[][] Lag_range_ptr, Lag_CB_ptr;

        if (nb_subfr == PE_MAX_NB_SUBFR) {
            Lag_range_ptr = silk_Lag_range_stage3[complexity];
            Lag_CB_ptr = silk_CB_lags_stage3;
            nb_cbk_search = silk_nb_cbk_searchs_stage3[complexity];
        } else {
            Lag_range_ptr = silk_Lag_range_stage3_10_ms;
            Lag_CB_ptr = silk_CB_lags_stage3_10_ms;
            nb_cbk_search = PE_NB_CBKS_STAGE3_10MS;
        }

        var target_ptr = frameOff + (sf_length << 2);
        for (k = 0; k < nb_subfr; k++) {
            lag_counter = 0;

            var basis_ptr = target_ptr - (start_lag + Lag_range_ptr[k][0]);
            energy = SigProcFlp.energyFlp(frame, basis_ptr, sf_length) + 1e-3;
            scratch_mem[lag_counter] = (float) energy;
            lag_counter++;

            lag_diff = Lag_range_ptr[k][1] - Lag_range_ptr[k][0] + 1;
            for (i = 1; i < lag_diff; i++) {
                energy -= frame[basis_ptr + sf_length - i] * (double) frame[basis_ptr + sf_length - i];
                energy += frame[basis_ptr - i] * (double) frame[basis_ptr - i];
                scratch_mem[lag_counter] = (float) energy;
                lag_counter++;
            }

            delta = Lag_range_ptr[k][0];
            for (i = 0; i < nb_cbk_search; i++) {
                idx = Lag_CB_ptr[k][i] - delta;
                for (j = 0; j < PE_NB_STAGE3_LAGS; j++) {
                    energies_st3[st3(k, i, j)] = scratch_mem[idx + j];
                }
            }
            target_ptr += sf_length;
        }
    }
}
