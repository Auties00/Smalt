/* Ports libopus 1.5.2 (silk/stereo_LR_to_MS.c, silk/stereo_encode_pred.c, silk/stereo_find_predictor.c,
   silk/stereo_quant_pred.c). Copyright Skype Limited; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.silk;

import com.github.auties00.smalt.audio.opus.internal.celt.RangeEncoder;

import static com.github.auties00.smalt.audio.opus.internal.silk.Define.*;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.*;

/**
 * Converts a left/right stereo signal to the adaptive mid/side representation of SILK and codes
 * its predictors.
 */
final class StereoEncode {
    /**
     * The side signal with two samples of history.
     */
    private final short[] side = new short[MAX_FRAME_LENGTH + 2];

    /**
     * The low-passed mid signal.
     */
    private final short[] lpMid = new short[MAX_FRAME_LENGTH];

    /**
     * The high-passed mid signal.
     */
    private final short[] hpMid = new short[MAX_FRAME_LENGTH];

    /**
     * The low-passed side signal.
     */
    private final short[] lpSide = new short[MAX_FRAME_LENGTH];

    /**
     * The high-passed side signal.
     */
    private final short[] hpSide = new short[MAX_FRAME_LENGTH];

    /**
     * Creates the converter scratch.
     */
    StereoEncode() {
    }

    /**
     * Converts a left/right frame to mid/side ({@code silk_stereo_LR_to_MS}).
     *
     * <p>{@code x1} holds the left signal at {@code x1Off} with two samples of history before it,
     * and becomes the mid signal; {@code x2} likewise becomes the side signal.
     *
     * @param state              the stereo state
     * @param x1                 the left input, becomes the mid signal
     * @param x1Off              the index of the first left sample
     * @param x2                 the right input, becomes the side signal
     * @param x2Off              the index of the first right sample
     * @param ix                 receives the quantization indices, {@code [2][3]}
     * @param mid_only_flag      receives the mid-only flag at {@code midOnlyOff}
     * @param midOnlyOff         the index in {@code mid_only_flag} to write
     * @param mid_side_rates_bps receives the bitrates of the mid and side signals
     * @param total_rate_bps     the total bitrate
     * @param prev_speech_act_Q8 the speech activity of the previous frame
     * @param toMono             nonzero for the last frame before a stereo to mono transition
     * @param fs_kHz             the sample rate in kHz
     * @param frame_length       the number of samples
     */
    void stereoLrToMs(StereoEncState state, short[] x1, int x1Off, short[] x2, int x2Off, byte[][] ix,
                      byte[] mid_only_flag, int midOnlyOff, int[] mid_side_rates_bps, int total_rate_bps,
                      int prev_speech_act_Q8, int toMono, int fs_kHz, int frame_length) {
        int n, is10msFrame, denom_Q16, delta0_Q13, delta1_Q13;
        int sum, diff, smooth_coef_Q16, pred0_Q13, pred1_Q13;
        var pred_Q13 = new int[2];
        int LP_ratio_Q14, HP_ratio_Q14, frac_Q16, frac_3_Q16, min_mid_rate_bps, width_Q14, w_Q24, deltaw_Q24;
        var ratio = new int[1];
        var mid = x1;
        var midOff = x1Off - 2;

        for (n = 0; n < frame_length + 2; n++) {
            sum = x1[x1Off + n - 2] + x2[x2Off + n - 2];
            diff = x1[x1Off + n - 2] - x2[x2Off + n - 2];
            mid[midOff + n] = (short) rshiftRound(sum, 1);
            side[n] = (short) sat16(rshiftRound(diff, 1));
        }

        System.arraycopy(state.sMid, 0, mid, midOff, 2);
        System.arraycopy(state.sSide, 0, side, 0, 2);
        System.arraycopy(mid, midOff + frame_length, state.sMid, 0, 2);
        System.arraycopy(side, frame_length, state.sSide, 0, 2);

        for (n = 0; n < frame_length; n++) {
            sum = rshiftRound((mid[midOff + n] + mid[midOff + n + 2]) + (mid[midOff + n + 1] << 1), 2);
            lpMid[n] = (short) sum;
            hpMid[n] = (short) (mid[midOff + n + 1] - sum);
        }

        for (n = 0; n < frame_length; n++) {
            sum = rshiftRound((side[n] + side[n + 2]) + (side[n + 1] << 1), 2);
            lpSide[n] = (short) sum;
            hpSide[n] = (short) (side[n + 1] - sum);
        }

        is10msFrame = frame_length == 10 * fs_kHz ? 1 : 0;
        smooth_coef_Q16 = is10msFrame != 0
                ? fixConst(STEREO_RATIO_SMOOTH_COEF / 2, 16)
                : fixConst(STEREO_RATIO_SMOOTH_COEF, 16);
        smooth_coef_Q16 = smulwb(smulbb(prev_speech_act_Q8, prev_speech_act_Q8), smooth_coef_Q16);

        pred_Q13[0] = stereoFindPredictor(ratio, lpMid, lpSide, state.mid_side_amp_Q0, 0, frame_length, smooth_coef_Q16);
        LP_ratio_Q14 = ratio[0];
        pred_Q13[1] = stereoFindPredictor(ratio, hpMid, hpSide, state.mid_side_amp_Q0, 2, frame_length, smooth_coef_Q16);
        HP_ratio_Q14 = ratio[0];

        frac_Q16 = smlabb(HP_ratio_Q14, LP_ratio_Q14, 3);
        frac_Q16 = Math.min(frac_Q16, fixConst(1, 16));

        total_rate_bps -= is10msFrame != 0 ? 1200 : 600;
        if (total_rate_bps < 1) {
            total_rate_bps = 1;
        }
        min_mid_rate_bps = smlabb(2000, fs_kHz, 600);
        frac_3_Q16 = 3 * frac_Q16;
        mid_side_rates_bps[0] = div32VarQ(total_rate_bps, fixConst(8 + 5, 16) + frac_3_Q16, 16 + 3);
        if (mid_side_rates_bps[0] < min_mid_rate_bps) {
            mid_side_rates_bps[0] = min_mid_rate_bps;
            mid_side_rates_bps[1] = total_rate_bps - mid_side_rates_bps[0];
            width_Q14 = div32VarQ((mid_side_rates_bps[1] << 1) - min_mid_rate_bps,
                    smulwb(fixConst(1, 16) + frac_3_Q16, min_mid_rate_bps), 14 + 2);
            width_Q14 = limit(width_Q14, 0, fixConst(1, 14));
        } else {
            mid_side_rates_bps[1] = total_rate_bps - mid_side_rates_bps[0];
            width_Q14 = fixConst(1, 14);
        }

        state.smth_width_Q14 = (short) smlawb(state.smth_width_Q14, width_Q14 - state.smth_width_Q14, smooth_coef_Q16);

        mid_only_flag[midOnlyOff] = 0;
        if (toMono != 0) {
            width_Q14 = 0;
            pred_Q13[0] = 0;
            pred_Q13[1] = 0;
            stereoQuantPred(pred_Q13, ix);
        } else if (state.width_prev_Q14 == 0
                && (8 * total_rate_bps < 13 * min_mid_rate_bps || smulwb(frac_Q16, state.smth_width_Q14) < fixConst(0.05, 14))) {
            pred_Q13[0] = smulbb(state.smth_width_Q14, pred_Q13[0]) >> 14;
            pred_Q13[1] = smulbb(state.smth_width_Q14, pred_Q13[1]) >> 14;
            stereoQuantPred(pred_Q13, ix);
            width_Q14 = 0;
            pred_Q13[0] = 0;
            pred_Q13[1] = 0;
            mid_side_rates_bps[0] = total_rate_bps;
            mid_side_rates_bps[1] = 0;
            mid_only_flag[midOnlyOff] = 1;
        } else if (state.width_prev_Q14 != 0
                && (8 * total_rate_bps < 11 * min_mid_rate_bps || smulwb(frac_Q16, state.smth_width_Q14) < fixConst(0.02, 14))) {
            pred_Q13[0] = smulbb(state.smth_width_Q14, pred_Q13[0]) >> 14;
            pred_Q13[1] = smulbb(state.smth_width_Q14, pred_Q13[1]) >> 14;
            stereoQuantPred(pred_Q13, ix);
            width_Q14 = 0;
            pred_Q13[0] = 0;
            pred_Q13[1] = 0;
        } else if (state.smth_width_Q14 > fixConst(0.95, 14)) {
            stereoQuantPred(pred_Q13, ix);
            width_Q14 = fixConst(1, 14);
        } else {
            pred_Q13[0] = smulbb(state.smth_width_Q14, pred_Q13[0]) >> 14;
            pred_Q13[1] = smulbb(state.smth_width_Q14, pred_Q13[1]) >> 14;
            stereoQuantPred(pred_Q13, ix);
            width_Q14 = state.smth_width_Q14;
        }

        if (mid_only_flag[midOnlyOff] == 1) {
            state.silent_side_len += (short) (frame_length - STEREO_INTERP_LEN_MS * fs_kHz);
            if (state.silent_side_len < LA_SHAPE_MS * fs_kHz) {
                mid_only_flag[midOnlyOff] = 0;
            } else {
                state.silent_side_len = 10000;
            }
        } else {
            state.silent_side_len = 0;
        }

        if (mid_only_flag[midOnlyOff] == 0 && mid_side_rates_bps[1] < 1) {
            mid_side_rates_bps[1] = 1;
            mid_side_rates_bps[0] = Math.max(1, total_rate_bps - mid_side_rates_bps[1]);
        }

        pred0_Q13 = -state.pred_prev_Q13[0];
        pred1_Q13 = -state.pred_prev_Q13[1];
        w_Q24 = state.width_prev_Q14 << 10;
        denom_Q16 = (1 << 16) / (STEREO_INTERP_LEN_MS * fs_kHz);
        delta0_Q13 = -rshiftRound(smulbb(pred_Q13[0] - state.pred_prev_Q13[0], denom_Q16), 16);
        delta1_Q13 = -rshiftRound(smulbb(pred_Q13[1] - state.pred_prev_Q13[1], denom_Q16), 16);
        deltaw_Q24 = smulwb(width_Q14 - state.width_prev_Q14, denom_Q16) << 10;
        for (n = 0; n < STEREO_INTERP_LEN_MS * fs_kHz; n++) {
            pred0_Q13 += delta0_Q13;
            pred1_Q13 += delta1_Q13;
            w_Q24 += deltaw_Q24;
            sum = ((mid[midOff + n] + mid[midOff + n + 2]) + (mid[midOff + n + 1] << 1)) << 9;
            sum = smlawb(smulwb(w_Q24, side[n + 1]), sum, pred0_Q13);
            sum = smlawb(sum, mid[midOff + n + 1] << 11, pred1_Q13);
            x2[x2Off + n - 1] = (short) sat16(rshiftRound(sum, 8));
        }

        pred0_Q13 = -pred_Q13[0];
        pred1_Q13 = -pred_Q13[1];
        w_Q24 = width_Q14 << 10;
        for (n = STEREO_INTERP_LEN_MS * fs_kHz; n < frame_length; n++) {
            sum = ((mid[midOff + n] + mid[midOff + n + 2]) + (mid[midOff + n + 1] << 1)) << 9;
            sum = smlawb(smulwb(w_Q24, side[n + 1]), sum, pred0_Q13);
            sum = smlawb(sum, mid[midOff + n + 1] << 11, pred1_Q13);
            x2[x2Off + n - 1] = (short) sat16(rshiftRound(sum, 8));
        }
        state.pred_prev_Q13[0] = (short) pred_Q13[0];
        state.pred_prev_Q13[1] = (short) pred_Q13[1];
        state.width_prev_Q14 = (short) width_Q14;
    }

    /**
     * Finds the least-squares predictor of one signal from another and updates the smoothed
     * norms ({@code silk_stereo_find_predictor}).
     *
     * @param ratio_Q14       receives the ratio of residual and mid energies at index 0
     * @param x               the basis signal
     * @param y               the target signal
     * @param mid_res_amp_Q0  the smoothed mid and residual norms, updated
     * @param ampOff          the index of the mid norm in {@code mid_res_amp_Q0}
     * @param length          the number of samples
     * @param smooth_coef_Q16 the smoothing coefficient
     * @return the predictor in Q13
     */
    static int stereoFindPredictor(int[] ratio_Q14, short[] x, short[] y, int[] mid_res_amp_Q0, int ampOff, int length,
                                   int smooth_coef_Q16) {
        int scale, scale1, scale2;
        int nrgx, nrgy, corr, pred_Q13, pred2_Q10;
        var packed = SumSqrShift.sumSqrShift(x, 0, length);
        nrgx = SumSqrShift.energy(packed);
        scale1 = SumSqrShift.shift(packed);
        packed = SumSqrShift.sumSqrShift(y, 0, length);
        nrgy = SumSqrShift.energy(packed);
        scale2 = SumSqrShift.shift(packed);
        scale = Math.max(scale1, scale2);
        scale = scale + (scale & 1);
        nrgy = nrgy >> (scale - scale2);
        nrgx = nrgx >> (scale - scale1);
        nrgx = Math.max(nrgx, 1);
        corr = InnerProdAligned.innerProdAlignedScale(x, 0, y, 0, scale, length);
        pred_Q13 = div32VarQ(corr, nrgx, 13);
        pred_Q13 = limit(pred_Q13, -(1 << 14), 1 << 14);
        pred2_Q10 = smulwb(pred_Q13, pred_Q13);

        smooth_coef_Q16 = Math.max(smooth_coef_Q16, Math.abs(pred2_Q10));

        scale = scale >> 1;
        mid_res_amp_Q0[ampOff] = smlawb(mid_res_amp_Q0[ampOff], (sqrtApprox(nrgx) << scale) - mid_res_amp_Q0[ampOff],
                smooth_coef_Q16);
        nrgy = nrgy - (smulwb(corr, pred_Q13) << (3 + 1));
        nrgy = nrgy + (smulwb(nrgx, pred2_Q10) << 6);
        mid_res_amp_Q0[ampOff + 1] = smlawb(mid_res_amp_Q0[ampOff + 1],
                (sqrtApprox(nrgy) << scale) - mid_res_amp_Q0[ampOff + 1], smooth_coef_Q16);

        ratio_Q14[0] = div32VarQ(mid_res_amp_Q0[ampOff + 1], Math.max(mid_res_amp_Q0[ampOff], 1), 14);
        ratio_Q14[0] = limit(ratio_Q14[0], 0, 32767);

        return pred_Q13;
    }

    /**
     * Quantizes the mid/side predictors ({@code silk_stereo_quant_pred}).
     *
     * @param pred_Q13 the two predictors, replaced by the quantized values (the first minus the
     *                 second)
     * @param ix       receives the quantization indices, {@code [2][3]}
     */
    static void stereoQuantPred(int[] pred_Q13, byte[][] ix) {
        int i, j, n;
        int low_Q13, step_Q13, lvl_Q13, err_min_Q13, err_Q13, quant_pred_Q13 = 0;

        for (n = 0; n < 2; n++) {
            err_min_Q13 = Integer.MAX_VALUE;
            search:
            for (i = 0; i < STEREO_QUANT_TAB_SIZE - 1; i++) {
                low_Q13 = Tables.silk_stereo_pred_quant_Q13[i];
                step_Q13 = smulwb(Tables.silk_stereo_pred_quant_Q13[i + 1] - low_Q13,
                        fixConst(0.5 / STEREO_QUANT_SUB_STEPS, 16));
                for (j = 0; j < STEREO_QUANT_SUB_STEPS; j++) {
                    lvl_Q13 = smlabb(low_Q13, step_Q13, 2 * j + 1);
                    err_Q13 = Math.abs(pred_Q13[n] - lvl_Q13);
                    if (err_Q13 < err_min_Q13) {
                        err_min_Q13 = err_Q13;
                        quant_pred_Q13 = lvl_Q13;
                        ix[n][0] = (byte) i;
                        ix[n][1] = (byte) j;
                    } else {
                        break search;
                    }
                }
            }
            ix[n][2] = (byte) (ix[n][0] / 3);
            ix[n][0] -= (byte) (ix[n][2] * 3);
            pred_Q13[n] = quant_pred_Q13;
        }

        pred_Q13[0] -= pred_Q13[1];
    }

    /**
     * Entropy codes the mid/side predictor indices ({@code silk_stereo_encode_pred}).
     *
     * @param psRangeEnc the range encoder
     * @param ix         the quantization indices, {@code [2][3]}
     */
    static void stereoEncodePred(RangeEncoder psRangeEnc, byte[][] ix) {
        int n;
        n = 5 * ix[0][2] + ix[1][2];
        psRangeEnc.encIcdf(n, Tables.silk_stereo_pred_joint_iCDF, 8);
        for (n = 0; n < 2; n++) {
            psRangeEnc.encIcdf(ix[n][0], Tables.silk_uniform3_iCDF, 8);
            psRangeEnc.encIcdf(ix[n][1], Tables.silk_uniform5_iCDF, 8);
        }
    }

    /**
     * Entropy codes the mid-only flag ({@code silk_stereo_encode_mid_only}).
     *
     * @param psRangeEnc    the range encoder
     * @param mid_only_flag the flag
     */
    static void stereoEncodeMidOnly(RangeEncoder psRangeEnc, int mid_only_flag) {
        psRangeEnc.encIcdf(mid_only_flag, Tables.silk_stereo_only_code_mid_iCDF, 8);
    }
}
