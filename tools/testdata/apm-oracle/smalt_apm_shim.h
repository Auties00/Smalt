/*
 * smalt_apm_shim.h
 *
 * An extern-C facade over the WebRTC Audio Processing Module (the
 * webrtc-audio-processing 2.1 standalone library), compiled to WebAssembly as
 * the bit-exact reference of Smalt's voice processor tests (build_wasm.py next
 * to this file builds apm.wasm from it).
 *
 * The WebRTC APM has no C ABI: its surface is the C++ webrtc::AudioProcessing
 * class, configured through a nested AudioProcessing::Config aggregate and
 * driven through ProcessStream / ProcessReverseStream over deinterleaved channel
 * pointers and StreamConfig objects. This shim hides the object behind one
 * opaque void* handle, flattens the Config into an int32 and a float parameter
 * array (the SMALT_APM_I_* and SMALT_APM_F_* indices below), and exchanges
 * audio as planar float32 arrays of one 10 ms frame: channel c of a frame of
 * n samples per channel starts at index c * n. Samples are normalized to
 * [-1, 1], the range the float ProcessStream API expects.
 *
 * Besides the APM, the shim exports a few of its building blocks, so a port
 * can be checked one component at a time: the push sinc resampler, the band
 * splitting filter, the AGC2 voice activity detector and the legacy mobile
 * echo canceller.
 */

#ifndef SMALT_APM_SHIM_H
#define SMALT_APM_SHIM_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Status codes. Zero is success.
 *
 *   SMALT_APM_OK         0   the call succeeded
 *   SMALT_APM_BAD_PARAM (-1) a NULL handle or buffer
 *   SMALT_APM_ERROR     (-3) the underlying WebRTC call returned an error
 */
#define SMALT_APM_OK         0
#define SMALT_APM_BAD_PARAM (-1)
#define SMALT_APM_ERROR     (-3)

/* Echo canceller selection (SMALT_APM_I_AEC). */
#define SMALT_APM_AEC_OFF    0
#define SMALT_APM_AEC_MOBILE 1
#define SMALT_APM_AEC_AEC3   2

/*
 * Indices of the int32 parameter array of smalt_apm_create. Booleans are 0 or
 * 1; a level or mode of -1 disables the component.
 *
 *   RATE              sample rate of every stream, in hertz
 *   CAPTURE_CHANNELS  channels of the capture input and output
 *   RENDER_CHANNELS   channels of the render input
 *   MC_CAPTURE        pipeline.multi_channel_capture
 *   MC_RENDER         pipeline.multi_channel_render
 *   DOWNMIX_FIRST     1 for DownmixMethod::kUseFirstChannel, 0 for kAverageChannels
 *   AEC               SMALT_APM_AEC_*
 *   NS                noise suppression level, -1 off, 0 kLow to 3 kVeryHigh
 *   AGC1_MODE         gain_controller1.mode, -1 to disable gain_controller1
 *   AGC1_TARGET       gain_controller1.target_level_dbfs
 *   AGC1_COMPRESSION  gain_controller1.compression_gain_db
 *   AGC1_LIMITER      gain_controller1.enable_limiter
 *   ANALOG            gain_controller1.analog_gain_controller.enabled
 *   ANALOG_STARTUP    analog_gain_controller.startup_min_volume
 *   ANALOG_CLIPPED_MIN    analog_gain_controller.clipped_level_min
 *   ANALOG_DIGITAL        analog_gain_controller.enable_digital_adaptive
 *   ANALOG_CLIPPED_STEP   analog_gain_controller.clipped_level_step
 *   ANALOG_CLIPPED_WAIT   analog_gain_controller.clipped_wait_frames
 *   CP_ENABLED        clipping_predictor.enabled
 *   CP_MODE           clipping_predictor.mode
 *   CP_WINDOW         clipping_predictor.window_length
 *   CP_REF_WINDOW     clipping_predictor.reference_window_length
 *   CP_REF_DELAY      clipping_predictor.reference_window_delay
 *   CP_USE_STEP       clipping_predictor.use_predicted_step
 *   AGC2              gain_controller2.enabled
 *   AGC2_ADAPTIVE     gain_controller2.adaptive_digital.enabled
 *   AGC2_IVC          gain_controller2.input_volume_controller.enabled
 *   HPF               high_pass_filter.enabled
 */
#define SMALT_APM_I_RATE                0
#define SMALT_APM_I_CAPTURE_CHANNELS    1
#define SMALT_APM_I_RENDER_CHANNELS     2
#define SMALT_APM_I_MC_CAPTURE          3
#define SMALT_APM_I_MC_RENDER           4
#define SMALT_APM_I_DOWNMIX_FIRST       5
#define SMALT_APM_I_AEC                 6
#define SMALT_APM_I_NS                  7
#define SMALT_APM_I_AGC1_MODE           8
#define SMALT_APM_I_AGC1_TARGET         9
#define SMALT_APM_I_AGC1_COMPRESSION   10
#define SMALT_APM_I_AGC1_LIMITER       11
#define SMALT_APM_I_ANALOG             12
#define SMALT_APM_I_ANALOG_STARTUP     13
#define SMALT_APM_I_ANALOG_CLIPPED_MIN 14
#define SMALT_APM_I_ANALOG_DIGITAL     15
#define SMALT_APM_I_ANALOG_CLIPPED_STEP 16
#define SMALT_APM_I_ANALOG_CLIPPED_WAIT 17
#define SMALT_APM_I_CP_ENABLED         18
#define SMALT_APM_I_CP_MODE            19
#define SMALT_APM_I_CP_WINDOW          20
#define SMALT_APM_I_CP_REF_WINDOW      21
#define SMALT_APM_I_CP_REF_DELAY       22
#define SMALT_APM_I_CP_USE_STEP        23
#define SMALT_APM_I_AGC2               24
#define SMALT_APM_I_AGC2_ADAPTIVE      25
#define SMALT_APM_I_AGC2_IVC           26
#define SMALT_APM_I_HPF                27
#define SMALT_APM_I_COUNT              28

/*
 * Indices of the float parameter array of smalt_apm_create.
 *
 *   CLIPPED_RATIO     analog_gain_controller.clipped_ratio_threshold
 *   CP_THRESHOLD      clipping_predictor.clipping_threshold
 *   CP_CREST          clipping_predictor.crest_factor_margin
 *   AGC2_FIXED_GAIN   gain_controller2.fixed_digital.gain_db
 *   AGC2_HEADROOM     adaptive_digital.headroom_db
 *   AGC2_MAX_GAIN     adaptive_digital.max_gain_db
 *   AGC2_INITIAL_GAIN adaptive_digital.initial_gain_db
 *   AGC2_MAX_CHANGE   adaptive_digital.max_gain_change_db_per_second
 *   AGC2_MAX_NOISE    adaptive_digital.max_output_noise_level_dbfs
 */
#define SMALT_APM_F_CLIPPED_RATIO     0
#define SMALT_APM_F_CP_THRESHOLD      1
#define SMALT_APM_F_CP_CREST          2
#define SMALT_APM_F_AGC2_FIXED_GAIN   3
#define SMALT_APM_F_AGC2_HEADROOM     4
#define SMALT_APM_F_AGC2_MAX_GAIN     5
#define SMALT_APM_F_AGC2_INITIAL_GAIN 6
#define SMALT_APM_F_AGC2_MAX_CHANGE   7
#define SMALT_APM_F_AGC2_MAX_NOISE    8
#define SMALT_APM_F_COUNT             9

/*
 * Creates an APM with the configuration of the two parameter arrays (see the
 * index lists above), or returns NULL on failure.
 */
void *smalt_apm_create(const int32_t *ints, const float *floats);

/*
 * Feeds one 10 ms planar render frame (RENDER_CHANNELS channels) to
 * ProcessReverseStream.
 */
int32_t smalt_apm_process_reverse(void *apm, const float *frame);

/*
 * Conditions one 10 ms planar capture frame (CAPTURE_CHANNELS channels) in
 * place through ProcessStream.
 */
int32_t smalt_apm_process(void *apm, float *frame);

/* Mirrors AudioProcessing::set_stream_delay_ms. */
int32_t smalt_apm_set_stream_delay_ms(void *apm, int32_t delay_ms);

/* Mirrors AudioProcessing::set_stream_analog_level. */
int32_t smalt_apm_set_stream_analog_level(void *apm, int32_t level);

/* Mirrors AudioProcessing::recommended_stream_analog_level. */
int32_t smalt_apm_recommended_stream_analog_level(void *apm);

/* Destroys an APM; NULL-safe. */
void smalt_apm_destroy(void *apm);

/*
 * A PushSincResampler from source_frames to destination_frames samples per
 * call; resample reads source_frames and writes destination_frames floats.
 */
void *smalt_resampler_create(int32_t source_frames, int32_t destination_frames);
int32_t smalt_resampler_resample(void *resampler, const float *source, float *destination);
void smalt_resampler_destroy(void *resampler);

/*
 * A SplittingFilter of one channel with num_bands bands (2 or 3) over frames
 * of num_frames samples; analysis reads num_frames samples and writes the
 * bands one after the other, synthesis the reverse.
 */
void *smalt_split_create(int32_t num_bands, int32_t num_frames);
int32_t smalt_split_analysis(void *filter, const float *data, float *bands);
int32_t smalt_split_synthesis(void *filter, const float *bands, float *data);
void smalt_split_destroy(void *filter);

/*
 * The AGC2 voice activity detector (VoiceActivityDetectorWrapper with the
 * default reset period and no SIMD) at sample_rate_hz; analyze reads one 10 ms
 * mono frame in the 16-bit range and returns the speech probability.
 */
void *smalt_vad_create(int32_t sample_rate_hz);
float smalt_vad_analyze(void *vad, const float *frame);
void smalt_vad_destroy(void *vad);

/*
 * Drives the legacy mobile echo canceller directly (WebRtcAecm_*), 16 kHz,
 * with the given echo mode (0..4) and comfort noise flag.
 */
void *smalt_aecm_create(int32_t echo_mode, int32_t comfort_noise);
int32_t smalt_aecm_buffer_farend(void *aecm, const int16_t *farend);
int32_t smalt_aecm_process(void *aecm, const int16_t *noisy, const int16_t *clean, int16_t *out,
                           int32_t delay_ms);
void smalt_aecm_destroy(void *aecm);

/*
 * Evaluates a libm function: 0 exp(x), 1 log(x), 2 pow(x, y), 3 log10(x),
 * 4 sin(x).
 */
double smalt_libm(int32_t function, double x, double y);

/*
 * The voice activity detector of the analog gain controller
 * (VoiceActivityDetector) at 16 kHz; process reads 160 samples and writes the
 * RMS levels of the 10 ms results to out[0..2], their voice probabilities to
 * out[3..5] and the last voice probability to out[6], returning the number of
 * results (0 or 3).
 */
void *smalt_avad_create(void);
int32_t smalt_avad_process(void *vad, const int16_t *audio, double *out);
void smalt_avad_destroy(void *vad);

#ifdef __cplusplus
}
#endif

#endif /* SMALT_APM_SHIM_H */
