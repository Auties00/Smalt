/* Ports webrtc-audio-processing 2.1 (modules/audio_processing/aec3/aec3_common.h,
   api/audio/echo_canceller3_config.h). Copyright (c) 2011, The WebRTC project authors; BSD 3-Clause License.
   Modified for Smalt. */
package com.github.auties00.smalt.audio.voice.internal.aec3;

import com.github.auties00.smalt.audio.voice.internal.FloatMath;

/**
 * Holds the dimensions of AEC3 and the parameters of its default configuration.
 *
 * <p>AEC3 processes 64-sample blocks (4 ms at 16 kHz) in a 128-point transform domain with 65
 * bins. The parameters are the defaults of WebRTC's {@code EchoCanceller3Config} for one render
 * and one capture channel, which is the configuration the processor runs; they are constants so
 * that the processing code reads like the reference.
 *
 * @implNote This implementation copies the constants of WebRTC's
 * {@code modules/audio_processing/aec3/aec3_common.h} and the defaults of
 * {@code api/audio/echo_canceller3_config.h} (webrtc-audio-processing 2.1, no field trials).
 */
final class Aec3 {
    /**
     * The number of samples of a block.
     */
    static final int BLOCK_SIZE = 64;

    /**
     * The base-2 logarithm of {@link #BLOCK_SIZE}.
     */
    static final int BLOCK_SIZE_LOG2 = 6;

    /**
     * Half the transform length.
     */
    static final int FFT_LENGTH_BY_2 = 64;

    /**
     * The number of transform bins, {@code FFT_LENGTH_BY_2 + 1}.
     */
    static final int FFT_LENGTH_BY_2_PLUS_1 = 65;

    /**
     * {@code FFT_LENGTH_BY_2 - 1}.
     */
    static final int FFT_LENGTH_BY_2_MINUS_1 = 63;

    /**
     * The transform length.
     */
    static final int FFT_LENGTH = 128;

    /**
     * The number of blocks per second.
     */
    static final int NUM_BLOCKS_PER_SECOND = 250;

    /**
     * The number of samples of a 10 ms frame.
     */
    static final int FRAME_SIZE = 160;

    /**
     * The number of samples of a 5 ms sub-frame.
     */
    static final int SUB_FRAME_LENGTH = FRAME_SIZE / 2;

    /**
     * The length of each matched filter of the delay estimator, in sub-blocks.
     */
    static final int MATCHED_FILTER_WINDOW_SIZE_SUB_BLOCKS = 32;

    /**
     * The lag offset between consecutive matched filters, in sub-blocks.
     */
    static final int MATCHED_FILTER_ALIGNMENT_SHIFT_SIZE_SUB_BLOCKS = MATCHED_FILTER_WINDOW_SIZE_SUB_BLOCKS * 3 / 4;

    /**
     * The capacity of the render frame queue between the render and the capture path.
     */
    static final int RENDER_TRANSFER_QUEUE_SIZE_FRAMES = 100;

    /**
     * {@code buffering.excess_render_detection_interval_blocks}.
     */
    static final int EXCESS_RENDER_DETECTION_INTERVAL_BLOCKS = 250;

    /**
     * {@code buffering.max_allowed_excess_render_blocks}.
     */
    static final int MAX_ALLOWED_EXCESS_RENDER_BLOCKS = 8;

    /**
     * {@code delay.default_delay}, in blocks.
     */
    static final int DEFAULT_DELAY = 5;

    /**
     * {@code delay.down_sampling_factor}.
     */
    static final int DOWN_SAMPLING_FACTOR = 4;

    /**
     * The magnitude, in the 16-bit range, below which flushed filter states and samples are
     * zeroed: about 500 dB below full scale, far above the subnormal range of the products that
     * involve them.
     */
    static final float FLUSH_FLOOR = 0x1p-80f;

    /**
     * {@code delay.num_filters}.
     */
    static final int NUM_FILTERS = 5;

    /**
     * {@code delay.delay_headroom_samples}.
     */
    static final int DELAY_HEADROOM_SAMPLES = 32;

    /**
     * {@code delay.hysteresis_limit_blocks}.
     */
    static final int HYSTERESIS_LIMIT_BLOCKS = 1;

    /**
     * {@code delay.delay_estimate_smoothing}.
     */
    static final float DELAY_ESTIMATE_SMOOTHING = 0.7f;

    /**
     * {@code delay.delay_estimate_smoothing_delay_found}.
     */
    static final float DELAY_ESTIMATE_SMOOTHING_DELAY_FOUND = 0.7f;

    /**
     * {@code delay.delay_candidate_detection_threshold}.
     */
    static final float DELAY_CANDIDATE_DETECTION_THRESHOLD = 0.2f;

    /**
     * {@code delay.delay_selection_thresholds.initial}.
     */
    static final int DELAY_SELECTION_THRESHOLD_INITIAL = 5;

    /**
     * {@code delay.delay_selection_thresholds.converged}.
     */
    static final int DELAY_SELECTION_THRESHOLD_CONVERGED = 20;

    /**
     * {@code filter.refined.length_blocks}.
     */
    static final int REFINED_LENGTH_BLOCKS = 13;

    /**
     * {@code filter.refined_initial.length_blocks}.
     */
    static final int REFINED_INITIAL_LENGTH_BLOCKS = 12;

    /**
     * {@code filter.coarse.length_blocks}.
     */
    static final int COARSE_LENGTH_BLOCKS = 13;

    /**
     * {@code filter.coarse_initial.length_blocks}.
     */
    static final int COARSE_INITIAL_LENGTH_BLOCKS = 12;

    /**
     * {@code filter.config_change_duration_blocks}.
     */
    static final int CONFIG_CHANGE_DURATION_BLOCKS = 250;

    /**
     * {@code filter.initial_state_seconds}.
     */
    static final float INITIAL_STATE_SECONDS = 2.5f;

    /**
     * {@code filter.coarse_reset_hangover_blocks}.
     */
    static final int COARSE_RESET_HANGOVER_BLOCKS = 25;

    /**
     * {@code erle.min}.
     */
    static final float ERLE_MIN = 1.f;

    /**
     * {@code erle.max_l}.
     */
    static final float ERLE_MAX_L = 4.f;

    /**
     * {@code erle.max_h}.
     */
    static final float ERLE_MAX_H = 1.5f;

    /**
     * {@code ep_strength.default_gain}.
     */
    static final float EP_DEFAULT_GAIN = 1.f;

    /**
     * {@code ep_strength.default_len}, the reverberation decay per block.
     */
    static final float EP_DEFAULT_LEN = 0.83f;

    /**
     * {@code echo_audibility.low_render_limit}.
     */
    static final float LOW_RENDER_LIMIT = 4 * 64.f;

    /**
     * {@code echo_audibility.normal_render_limit}.
     */
    static final float NORMAL_RENDER_LIMIT = 64.f;

    /**
     * {@code echo_audibility.floor_power}.
     */
    static final float FLOOR_POWER = 2 * 64.f;

    /**
     * {@code echo_audibility.audibility_threshold_lf}, {@code _mf} and {@code _hf}.
     */
    static final float AUDIBILITY_THRESHOLD = 10;

    /**
     * {@code render_levels.active_render_limit}.
     */
    static final float ACTIVE_RENDER_LIMIT = 100.f;

    /**
     * {@code render_levels.poor_excitation_render_limit}.
     */
    static final float POOR_EXCITATION_RENDER_LIMIT = 150.f;

    /**
     * {@code echo_model.noise_floor_hold}.
     */
    static final int NOISE_FLOOR_HOLD = 50;

    /**
     * {@code echo_model.min_noise_floor_power}.
     */
    static final float MIN_NOISE_FLOOR_POWER = 1638400.f;

    /**
     * {@code echo_model.stationary_gate_slope}.
     */
    static final float STATIONARY_GATE_SLOPE = 10.f;

    /**
     * {@code echo_model.noise_gate_power}.
     */
    static final float NOISE_GATE_POWER = 27509.42f;

    /**
     * {@code echo_model.noise_gate_slope}.
     */
    static final float NOISE_GATE_SLOPE = 0.3f;

    /**
     * {@code echo_model.render_pre_window_size}.
     */
    static final int RENDER_PRE_WINDOW_SIZE = 1;

    /**
     * {@code echo_model.render_post_window_size}.
     */
    static final int RENDER_POST_WINDOW_SIZE = 1;

    /**
     * {@code comfort_noise.noise_floor_dbfs}.
     */
    static final float NOISE_FLOOR_DBFS = -96.03406f;

    /**
     * {@code suppressor.nearend_average_blocks}.
     */
    static final int NEAREND_AVERAGE_BLOCKS = 4;

    /**
     * {@code suppressor.last_permanent_lf_smoothing_band}.
     */
    static final int LAST_PERMANENT_LF_SMOOTHING_BAND = 0;

    /**
     * {@code suppressor.last_lf_smoothing_band}.
     */
    static final int LAST_LF_SMOOTHING_BAND = 5;

    /**
     * {@code suppressor.last_lf_band}.
     */
    static final int LAST_LF_BAND = 5;

    /**
     * {@code suppressor.first_hf_band}.
     */
    static final int FIRST_HF_BAND = 8;

    /**
     * {@code suppressor.dominant_nearend_detection.enr_threshold}.
     */
    static final float DOMINANT_ENR_THRESHOLD = .25f;

    /**
     * {@code suppressor.dominant_nearend_detection.enr_exit_threshold}.
     */
    static final float DOMINANT_ENR_EXIT_THRESHOLD = 10.f;

    /**
     * {@code suppressor.dominant_nearend_detection.snr_threshold}.
     */
    static final float DOMINANT_SNR_THRESHOLD = 30.f;

    /**
     * {@code suppressor.dominant_nearend_detection.hold_duration}.
     */
    static final int DOMINANT_HOLD_DURATION = 50;

    /**
     * {@code suppressor.dominant_nearend_detection.trigger_threshold}.
     */
    static final int DOMINANT_TRIGGER_THRESHOLD = 12;

    /**
     * {@code suppressor.floor_first_increase}.
     */
    static final float FLOOR_FIRST_INCREASE = 0.00001f;

    /**
     * Prevents instantiation.
     */
    private Aec3() {
        throw new AssertionError();
    }

    /**
     * Returns the fast base-2 logarithm approximation of AEC3, {@code FastApproxLog2f}.
     *
     * @param in a positive argument
     * @return the approximation of {@code log2(in)}
     */
    static float fastApproxLog2f(float in) {
        return FloatMath.fastLog2f(in);
    }
}
