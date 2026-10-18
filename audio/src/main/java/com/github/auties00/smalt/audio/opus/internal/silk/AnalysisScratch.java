package com.github.auties00.smalt.audio.opus.internal.silk;

import com.github.auties00.smalt.audio.opus.internal.celt.RangeEncoder;

import static com.github.auties00.smalt.audio.opus.internal.silk.Define.*;

/**
 * Holds the scratch buffers of the SILK float encoder's per-frame stages, which the reference
 * allocates on the stack.
 *
 * <p>No field carries information from one frame to the next; each stage overwrites what it
 * reads.
 */
final class AnalysisScratch {
    /**
     * The pitch-analysis LPC residual of the whole analysis buffer ({@code res_pitch}).
     */
    final float[] res_pitch = new float[2 * MAX_FRAME_LENGTH + LA_PITCH_MAX];

    /**
     * The windowed signal of the pitch-analysis LPC estimation ({@code Wsig}).
     */
    final float[] Wsig = new float[FIND_PITCH_LPC_WIN_MAX];

    /**
     * The autocorrelation of the pitch-analysis window.
     */
    final float[] pitch_auto_corr = new float[MAX_FIND_PITCH_LPC_ORDER + 1];

    /**
     * The whitening filter of the pitch analysis.
     */
    final float[] pitch_A = new float[MAX_FIND_PITCH_LPC_ORDER];

    /**
     * The reflection coefficients of the pitch analysis.
     */
    final float[] pitch_refl_coef = new float[MAX_FIND_PITCH_LPC_ORDER];

    /**
     * The normalized correlation of the pitch estimator as a one-element array.
     */
    final float[] ltpCorr = new float[1];

    /**
     * The windowed signal of every subframe of the noise-shaping analysis ({@code x_windowed}),
     * {@code SHAPE_LPC_WIN_MAX} samples per subframe.
     */
    final float[] x_windowed = new float[MAX_NB_SUBFR * SHAPE_LPC_WIN_MAX];

    /**
     * The warped autocorrelations of every subframe of the noise-shaping analysis,
     * {@code MAX_SHAPE_LPC_ORDER + 1} values per subframe.
     */
    final float[] shape_all_corr = new float[MAX_NB_SUBFR * (MAX_SHAPE_LPC_ORDER + 1)];

    /**
     * The work buffer of the batched warped autocorrelation.
     */
    final double[] warpedScratch = new double[SilkFlpKernels.warpedScratchLength(SHAPE_LPC_WIN_MAX)];

    /**
     * The autocorrelation of the noise-shaping analysis.
     */
    final float[] shape_auto_corr = new float[MAX_SHAPE_LPC_ORDER + 1];

    /**
     * The reflection coefficients of the noise-shaping analysis.
     */
    final float[] shape_rc = new float[MAX_SHAPE_LPC_ORDER + 1];

    /**
     * The LTP correlation matrices ({@code XXLTP}).
     */
    final float[] XXLTP = new float[MAX_NB_SUBFR * LTP_ORDER * LTP_ORDER];

    /**
     * The LTP correlation vectors ({@code xXLTP}).
     */
    final float[] xXLTP = new float[MAX_NB_SUBFR * LTP_ORDER];

    /**
     * The inverse quantization gains ({@code invGains}).
     */
    final float[] invGains = new float[MAX_NB_SUBFR];

    /**
     * The unquantized NLSFs of the frame ({@code NLSF_Q15}).
     */
    final short[] NLSF_Q15 = new short[MAX_LPC_ORDER];

    /**
     * The LTP-filtered, gain-normalized input of the LPC analysis ({@code LPC_in_pre}).
     */
    final float[] LPC_in_pre = new float[MAX_NB_SUBFR * MAX_LPC_ORDER + MAX_FRAME_LENGTH];

    /**
     * The full-frame prediction coefficients of the LPC analysis.
     */
    final float[] lpc_a = new float[MAX_LPC_ORDER];

    /**
     * The second-half or interpolated prediction coefficients of the LPC analysis.
     */
    final float[] lpc_a_tmp = new float[MAX_LPC_ORDER];

    /**
     * The interpolated NLSFs of the first frame half.
     */
    final short[] NLSF0_Q15 = new short[MAX_LPC_ORDER];

    /**
     * The LPC residual of the LPC analysis ({@code LPC_res}).
     */
    final float[] LPC_res = new float[MAX_FRAME_LENGTH + MAX_NB_SUBFR * MAX_LPC_ORDER];

    /**
     * The fixed-point prediction coefficients of both frame halves.
     */
    final short[] PredCoef_Q12 = new short[2 * MAX_LPC_ORDER];

    /**
     * The fixed-point AR coefficients of the NLSF conversions.
     */
    final int[] a_fix_Q16 = new int[MAX_LPC_ORDER];

    /**
     * The fixed-point prediction coefficients of the NLSF conversions.
     */
    final short[] a_fix_Q12 = new short[MAX_LPC_ORDER];

    /**
     * The quantizer input in Q0 ({@code x16}).
     */
    final short[] x16 = new short[MAX_FRAME_LENGTH];

    /**
     * The quantization step sizes in Q16.
     */
    final int[] Gains_Q16 = new int[MAX_NB_SUBFR];

    /**
     * The fixed-point LTP coefficients.
     */
    final short[] LTPCoef_Q14 = new short[LTP_ORDER * MAX_NB_SUBFR];

    /**
     * The fixed-point noise-shaping coefficients.
     */
    final short[] AR_Q13 = new short[MAX_NB_SUBFR * MAX_SHAPE_LPC_ORDER];

    /**
     * The packed low-frequency shaping coefficients.
     */
    final int[] LF_shp_Q14 = new int[MAX_NB_SUBFR];

    /**
     * The fixed-point spectral tilts.
     */
    final int[] Tilt_Q14 = new int[MAX_NB_SUBFR];

    /**
     * The fixed-point harmonic shaping gains.
     */
    final int[] HarmShapeGain_Q14 = new int[MAX_NB_SUBFR];

    /**
     * The LTP correlation matrices in Q17.
     */
    final int[] XX_Q17 = new int[MAX_NB_SUBFR * LTP_ORDER * LTP_ORDER];

    /**
     * The LTP correlation vectors in Q17.
     */
    final int[] xX_Q17 = new int[MAX_NB_SUBFR * LTP_ORDER];

    /**
     * The quantized LTP coefficients in Q14.
     */
    final short[] B_Q14 = new short[MAX_NB_SUBFR * LTP_ORDER];

    /**
     * The periodicity index output of the LTP quantizer.
     */
    final byte[] periodicityIndex = new byte[1];

    /**
     * The cumulative log gain in/out value of the LTP quantizer.
     */
    final int[] sumLogGain = new int[1];

    /**
     * The prediction gain output of the LTP quantizer.
     */
    final int[] predGainDb = new int[1];

    /**
     * The quantizer state of the LBRR encoding ({@code sNSQ_LBRR}).
     */
    final NsqState sNSQ_LBRR = new NsqState();

    /**
     * The original gains saved during the LBRR encoding ({@code TempGains}).
     */
    final float[] TempGains = new float[MAX_NB_SUBFR];

    /**
     * The saved quantizer state of the rate-control loop ({@code sNSQ_copy}).
     */
    final NsqState sNSQ_copy = new NsqState();

    /**
     * The quantizer state of the best under-budget iteration ({@code sNSQ_copy2}).
     */
    final NsqState sNSQ_copy2 = new NsqState();

    /**
     * The saved range coder of the rate-control loop ({@code sRangeEnc_copy}).
     */
    final RangeEncoder sRangeEnc_copy = new RangeEncoder();

    /**
     * The range coder of the best under-budget iteration ({@code sRangeEnc_copy2}).
     */
    final RangeEncoder sRangeEnc_copy2 = new RangeEncoder();

    /**
     * The payload bytes of the best under-budget iteration ({@code ec_buf_copy}).
     */
    final byte[] ec_buf_copy = new byte[1275];

    /**
     * The gains of the rate-control loop in Q16 ({@code pGains_Q16}).
     */
    final int[] pGains_Q16 = new int[MAX_NB_SUBFR];

    /**
     * Whether each subframe's gain multiplier is locked ({@code gain_lock}).
     */
    final int[] gain_lock = new int[MAX_NB_SUBFR];

    /**
     * The best gain multiplier of each subframe ({@code best_gain_mult}).
     */
    final short[] best_gain_mult = new short[MAX_NB_SUBFR];

    /**
     * The lowest pulse sum of each subframe ({@code best_sum}).
     */
    final int[] best_sum = new int[MAX_NB_SUBFR];

    /**
     * The residual of the residual energy computation.
     */
    final float[] resNrgLpcRes = new float[(MAX_FRAME_LENGTH + MAX_NB_SUBFR * MAX_LPC_ORDER) / 2];

    /**
     * The first-row correlations of the Burg recursion ({@code C_first_row}).
     */
    final double[] burgCFirstRow = new double[SILK_MAX_ORDER_LPC];

    /**
     * The last-row correlations of the Burg recursion ({@code C_last_row}).
     */
    final double[] burgCLastRow = new double[SILK_MAX_ORDER_LPC];

    /**
     * The forward correlations of the Burg recursion ({@code CAf}).
     */
    final double[] burgCaf = new double[SILK_MAX_ORDER_LPC + 1];

    /**
     * The backward correlations of the Burg recursion ({@code CAb}).
     */
    final double[] burgCab = new double[SILK_MAX_ORDER_LPC + 1];

    /**
     * The prediction coefficients of the Burg recursion ({@code Af}).
     */
    final double[] burgAf = new double[SILK_MAX_ORDER_LPC];

    /**
     * The first column of the Schur recursion's work matrix.
     */
    final double[] schurC0 = new double[SILK_MAX_ORDER_LPC + 1];

    /**
     * The second column of the Schur recursion's work matrix.
     */
    final double[] schurC1 = new double[SILK_MAX_ORDER_LPC + 1];

    /**
     * The even polynomial of the NLSF root search.
     */
    final int[] a2nlsfP = new int[SILK_MAX_ORDER_LPC / 2 + 1];

    /**
     * The odd polynomial of the NLSF root search.
     */
    final int[] a2nlsfQ = new int[SILK_MAX_ORDER_LPC / 2 + 1];

    /**
     * The work arrays of the NLSF to LPC conversion.
     */
    final Nlsf2A.Scratch nlsf2a = new Nlsf2A.Scratch();

    /**
     * Creates the scratch.
     */
    AnalysisScratch() {
    }
}
