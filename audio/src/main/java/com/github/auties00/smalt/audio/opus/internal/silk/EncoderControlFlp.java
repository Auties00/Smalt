/* Ports libopus 1.5.2 (silk/float/structs_FLP.h). Copyright Skype Limited; BSD 3-Clause License. Modified for Smalt.
   */
package com.github.auties00.smalt.audio.opus.internal.silk;

import static com.github.auties00.smalt.audio.opus.internal.silk.Define.*;

/**
 * Holds the per-frame analysis results and coding parameters of the SILK float encoder
 * ({@code silk_encoder_control_FLP}).
 *
 * @implNote This implementation flattens {@code PredCoef} to {@code 2 x MAX_LPC_ORDER} rows.
 */
final class EncoderControlFlp {
    /**
     * The quantization gain of each subframe.
     */
    final float[] Gains = new float[MAX_NB_SUBFR];

    /**
     * The interpolated (row 0) and final (row 1) prediction coefficients.
     */
    final float[][] PredCoef = new float[2][MAX_LPC_ORDER];

    /**
     * The LTP coefficients, {@code LTP_ORDER} per subframe.
     */
    final float[] LTPCoef = new float[LTP_ORDER * MAX_NB_SUBFR];

    /**
     * The LTP state scaling.
     */
    float LTP_scale;

    /**
     * The pitch lag of each subframe.
     */
    final int[] pitchL = new int[MAX_NB_SUBFR];

    /**
     * The noise-shaping AR coefficients, {@code MAX_SHAPE_LPC_ORDER} per subframe.
     */
    final float[] AR = new float[MAX_NB_SUBFR * MAX_SHAPE_LPC_ORDER];

    /**
     * The low-frequency shaping MA coefficient of each subframe.
     */
    final float[] LF_MA_shp = new float[MAX_NB_SUBFR];

    /**
     * The low-frequency shaping AR coefficient of each subframe.
     */
    final float[] LF_AR_shp = new float[MAX_NB_SUBFR];

    /**
     * The spectral tilt of each subframe.
     */
    final float[] Tilt = new float[MAX_NB_SUBFR];

    /**
     * The harmonic shaping gain of each subframe.
     */
    final float[] HarmShapeGain = new float[MAX_NB_SUBFR];

    /**
     * The rate/distortion trade-off of the quantizer.
     */
    float Lambda;

    /**
     * The input quality estimate.
     */
    float input_quality;

    /**
     * The coding quality level, between 0 and 1.
     */
    float coding_quality;

    /**
     * The prediction gain of the pitch-analysis whitening filter.
     */
    float predGain;

    /**
     * The long-term prediction coding gain in dB.
     */
    float LTPredCodGain;

    /**
     * The residual energy of each subframe.
     */
    final float[] ResNrg = new float[MAX_NB_SUBFR];

    /**
     * The unquantized gains of each subframe in Q16, kept for the rate-control loop.
     */
    final int[] GainsUnq_Q16 = new int[MAX_NB_SUBFR];

    /**
     * The last gain index of the previous frame, kept for the rate-control loop.
     */
    byte lastGainIndexPrev;

    /**
     * Creates a zeroed control structure.
     */
    EncoderControlFlp() {
    }
}
