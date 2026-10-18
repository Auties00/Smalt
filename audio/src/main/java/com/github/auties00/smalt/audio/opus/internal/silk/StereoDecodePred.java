/* Ports libopus 1.5.2 (silk/stereo_decode_pred.c). Copyright Skype Limited; BSD 3-Clause License. Modified for Smalt.
   */
package com.github.auties00.smalt.audio.opus.internal.silk;

import com.github.auties00.smalt.audio.opus.internal.celt.RangeDecoder;

import static com.github.auties00.smalt.audio.opus.internal.silk.Define.STEREO_QUANT_SUB_STEPS;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.fixConst;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.smlabb;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.smulwb;

/**
 * Decodes the mid/side predictors and the mid-only flag of a stereo SILK frame.
 */
public final class StereoDecodePred {
    /**
     * The sub-step size of the predictor quantizer in Q16.
     */
    private static final int HALF_STEP_Q16 = fixConst(0.5 / STEREO_QUANT_SUB_STEPS, 16);

    private StereoDecodePred() {
        throw new AssertionError();
    }

    /**
     * Decodes the two mid/side predictors ({@code silk_stereo_decode_pred}).
     *
     * <p>The first output is the difference of the two decoded predictors, which is how they are
     * applied.
     *
     * @param psRangeDec the range decoder
     * @param pred_Q13   receives the two predictors in Q13
     */
    public static void stereoDecodePred(RangeDecoder psRangeDec, int[] pred_Q13) {
        var n = psRangeDec.decIcdf(Tables.silk_stereo_pred_joint_iCDF, 8);
        var ix02 = n / 5;
        var ix12 = n - 5 * ix02;
        var ix00 = psRangeDec.decIcdf(Tables.silk_uniform3_iCDF, 8);
        var ix01 = psRangeDec.decIcdf(Tables.silk_uniform5_iCDF, 8);
        var ix10 = psRangeDec.decIcdf(Tables.silk_uniform3_iCDF, 8);
        var ix11 = psRangeDec.decIcdf(Tables.silk_uniform5_iCDF, 8);
        pred_Q13[0] = dequantize(ix00 + 3 * ix02, ix01);
        pred_Q13[1] = dequantize(ix10 + 3 * ix12, ix11);
        pred_Q13[0] -= pred_Q13[1];
    }

    /**
     * Dequantizes one predictor from its coarse and fine indices.
     *
     * @param ix0 the coarse index, including the joint part
     * @param ix1 the fine index
     * @return the predictor in Q13
     */
    private static int dequantize(int ix0, int ix1) {
        int low_Q13 = Tables.silk_stereo_pred_quant_Q13[ix0];
        var step_Q13 = smulwb(Tables.silk_stereo_pred_quant_Q13[ix0 + 1] - low_Q13, HALF_STEP_Q16);
        return smlabb(low_Q13, step_Q13, 2 * ix1 + 1);
    }

    /**
     * Decodes the flag telling that only the mid channel is coded
     * ({@code silk_stereo_decode_mid_only}).
     *
     * @param psRangeDec the range decoder
     * @return the flag
     */
    public static int stereoDecodeMidOnly(RangeDecoder psRangeDec) {
        return psRangeDec.decIcdf(Tables.silk_stereo_only_code_mid_iCDF, 8);
    }
}
