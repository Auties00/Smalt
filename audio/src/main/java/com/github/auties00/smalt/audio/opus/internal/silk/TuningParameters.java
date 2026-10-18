/* Ports libopus 1.5.2 (silk/tuning_parameters.h). Copyright Skype Limited; BSD 3-Clause License. Modified for Smalt.
   */
package com.github.auties00.smalt.audio.opus.internal.silk;

/**
 * Holds the tuning parameters of the SILK encoder.
 *
 * @implNote This implementation keeps the libopus macro names.
 */
public final class TuningParameters {
    /**
     * The decay time of the bit reservoir in milliseconds.
     */
    public static final int BITRESERVOIR_DECAY_TIME_MS = 500;

    /**
     * The noise floor of the whitening LPC analysis of the pitch estimator.
     */
    public static final float FIND_PITCH_WHITE_NOISE_FRACTION = 1e-3f;

    /**
     * The bandwidth expansion of the whitening filter of the pitch estimator.
     */
    public static final float FIND_PITCH_BANDWIDTH_EXPANSION = 0.99f;

    /**
     * The regularization of the LPC analysis.
     */
    public static final float FIND_LPC_COND_FAC = 1e-5f;

    /**
     * The maximum cumulative LTP gain in dB.
     */
    public static final float MAX_SUM_LOG_GAIN_DB = 250.0f;

    /**
     * The maximum inverse correlation of the LTP analysis.
     */
    public static final float LTP_CORR_INV_MAX = 0.03f;

    /**
     * The first smoothing coefficient of the variable high-pass cutoff estimate.
     */
    public static final float VARIABLE_HP_SMTH_COEF1 = 0.1f;

    /**
     * The second smoothing coefficient of the variable high-pass cutoff estimate.
     */
    public static final float VARIABLE_HP_SMTH_COEF2 = 0.015f;

    /**
     * The maximum relative change of the variable high-pass cutoff per frame.
     */
    public static final float VARIABLE_HP_MAX_DELTA_FREQ = 0.4f;

    /**
     * The minimum cutoff of the variable high-pass filter in Hz.
     */
    public static final int VARIABLE_HP_MIN_CUTOFF_HZ = 60;

    /**
     * The maximum cutoff of the variable high-pass filter in Hz.
     */
    public static final int VARIABLE_HP_MAX_CUTOFF_HZ = 100;

    /**
     * The speech activity below which DTX may start.
     */
    public static final float SPEECH_ACTIVITY_DTX_THRES = 0.05f;

    /**
     * The speech activity above which LBRR data is coded.
     */
    public static final float LBRR_SPEECH_ACTIVITY_THRES = 0.3f;

    /**
     * The reduction of the coding SNR during low speech activity, in dB.
     */
    public static final float BG_SNR_DECR_dB = 2.0f;

    /**
     * The increase of the coding SNR during voiced speech, in dB.
     */
    public static final float HARM_SNR_INCR_dB = 2.0f;

    /**
     * The sparseness above which unvoiced frames use the lower quantization offset.
     */
    public static final float ENERGY_VARIATION_THRESHOLD_QNT_OFFSET = 0.6f;

    /**
     * The frequency warping control of the noise shaping.
     */
    public static final float WARPING_MULTIPLIER = 0.015f;

    /**
     * The fraction added to the first autocorrelation value of the noise shape analysis.
     */
    public static final float SHAPE_WHITE_NOISE_FRACTION = 3e-5f;

    /**
     * The chirp factor of the noise shaping filter.
     */
    public static final float BANDWIDTH_EXPANSION = 0.94f;

    /**
     * The harmonic noise shaping strength.
     */
    public static final float HARMONIC_SHAPING = 0.3f;

    /**
     * The extra harmonic noise shaping for high bitrates or noisy input.
     */
    public static final float HIGH_RATE_OR_LOW_QUALITY_HARMONIC_SHAPING = 0.2f;

    /**
     * The shaping of the noise towards higher frequencies.
     */
    public static final float HP_NOISE_COEF = 0.25f;

    /**
     * The extra shaping of the noise towards higher frequencies during voiced speech.
     */
    public static final float HARM_HP_NOISE_COEF = 0.35f;

    /**
     * The reduction of the noise at very low frequencies.
     */
    public static final float LOW_FREQ_SHAPING = 4.0f;

    /**
     * The lower reduction of the low-frequency noise for signals with low SNR at low frequencies.
     */
    public static final float LOW_QUALITY_LOW_FREQ_SHAPING_DECR = 0.5f;

    /**
     * The subframe smoothing coefficient of the harmonic boost, harmonic shaping gain and tilt.
     */
    public static final float SUBFR_SMTH_COEF = 0.4f;

    /**
     * The offset of the rate-distortion tradeoff of the residual quantizer.
     */
    public static final float LAMBDA_OFFSET = 1.2f;

    /**
     * The speech activity weight of the rate-distortion tradeoff.
     */
    public static final float LAMBDA_SPEECH_ACT = -0.2f;

    /**
     * The delayed decision weight of the rate-distortion tradeoff.
     */
    public static final float LAMBDA_DELAYED_DECISIONS = -0.05f;

    /**
     * The input quality weight of the rate-distortion tradeoff.
     */
    public static final float LAMBDA_INPUT_QUALITY = -0.1f;

    /**
     * The coding quality weight of the rate-distortion tradeoff.
     */
    public static final float LAMBDA_CODING_QUALITY = -0.2f;

    /**
     * The quantization offset weight of the rate-distortion tradeoff.
     */
    public static final float LAMBDA_QUANT_OFFSET = 0.8f;

    /**
     * The maximum time before a bandwidth transition is allowed, in milliseconds.
     */
    public static final int MAX_BANDWIDTH_SWITCH_DELAY_MS = 5000;

    private TuningParameters() {
        throw new AssertionError();
    }
}
