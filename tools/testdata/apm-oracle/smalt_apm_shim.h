/*
 * smalt_apm_shim.h
 *
 * An extern-C facade over the WebRTC Audio Processing Module (the
 * webrtc-audio-processing 2.1 standalone library: AEC3, the mobile echo
 * canceller, the noise suppressor and the gain controller), compiled to
 * WebAssembly as the bit-exact reference of Smalt's voice processor tests
 * (build_wasm.py next to this file builds apm.wasm from it).
 *
 * The WebRTC APM has no C ABI: its surface is the C++ webrtc::AudioProcessing
 * class, configured through a nested AudioProcessing::Config aggregate and
 * driven through ProcessStream / ProcessReverseStream over deinterleaved channel
 * pointers and StreamConfig objects. This shim hides the whole object behind
 * one opaque void* handle, fixes the geometry to a single 16 kHz mono channel,
 * exchanges audio as flat float32 arrays of one 10 ms APM frame (160 samples),
 * and flattens the Config to scalar knobs, so the WebAssembly exports take only
 * int32_t, float and pointers.
 *
 * TODO: the exported symbols keep the cobalt_webrtc_ prefix that the checked-in
 * apm.wasm exports; rename them to smalt_apm_ / smalt_aecm_ (here, in
 * build_wasm.py's EXPORTS and in WasmApmOracle / WasmAecmOracle) when the
 * module is next rebuilt.
 */

#ifndef SMALT_APM_SHIM_H
#define SMALT_APM_SHIM_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Status codes returned by the process/create entry points. Zero is success.
 * Negative values are shim-level failures: an invalid argument supplied to the
 * shim, an allocation failure inside the APM factory, or an error code returned
 * by the underlying WebRTC ProcessStream / ProcessReverseStream call.
 *
 *   SMALT_APM_OK         0   the call succeeded
 *   SMALT_APM_BAD_PARAM (-1) a NULL handle/buffer or a wrong frame length
 *   SMALT_APM_NOMEM     (-2) the APM instance could not be allocated
 *   SMALT_APM_ERROR     (-3) the underlying WebRTC APM returned an error
 */
#define SMALT_APM_OK         0
#define SMALT_APM_BAD_PARAM (-1)
#define SMALT_APM_NOMEM     (-2)
#define SMALT_APM_ERROR     (-3)

/*
 * The single supported sample rate, in hertz. The shim hard-codes one rate and
 * one channel (16 kHz mono) rather than exposing a StreamConfig.
 */
#define SMALT_APM_SAMPLE_RATE_HZ 16000

/*
 * The samples in one WebRTC APM frame: kChunkSizeMs (10 ms) at 16 kHz, i.e.
 * 160. ProcessStream and ProcessReverseStream each consume exactly this many
 * samples per call; the caller splits a longer block (for example 20 ms,
 * 320 samples) into successive 10 ms frames.
 */
#define SMALT_APM_FRAME_SAMPLES 160

/*
 * The echo-canceller selection passed to cobalt_webrtc_apm_create as aec_mode.
 * Value 2 selects AEC3, value 1 the mobile echo controller (AECM), and value 0
 * disables the echo canceller. The shim
 * maps these onto the WebRTC AudioProcessing::Config::EchoCanceller fields
 * (enabled / mobile_mode).
 */
#define SMALT_APM_AEC_OFF    0
#define SMALT_APM_AEC_MOBILE 1
#define SMALT_APM_AEC_AEC3   2

/**
 * Creates a WebRTC Audio Processing Module instance for 16 kHz mono capture
 * conditioning.
 *
 * It builds an APM instance, enables the echo canceller selected by aec_mode,
 * enables the noise suppressor at the level ns_denoiser_intensity maps to when
 * ns_use_denoiser is non-zero, and enables the gain controller when agc_enabled
 * is non-zero. The returned handle is opaque; on failure the function returns
 * NULL.
 *
 * @param aec_mode             one of SMALT_APM_AEC_OFF / _MOBILE / _AEC3.
 * @param ns_enabled           non-zero to enable noise suppression.
 * @param ns_use_denoiser      non-zero to derive the suppression level from
 *                             ns_denoiser_intensity.
 * @param ns_denoiser_intensity the suppression intensity in [0, 1]; ignored when
 *                             ns_use_denoiser is zero.
 * @param agc_enabled          non-zero to enable the WebRTC gain controller.
 * @return an opaque APM handle on success, or NULL on failure.
 */
void *cobalt_webrtc_apm_create(int32_t aec_mode,
                               int32_t ns_enabled,
                               int32_t ns_use_denoiser,
                               float ns_denoiser_intensity,
                               int32_t agc_enabled);

/**
 * Feeds one 10 ms far-end render frame to the echo canceller as the reference.
 *
 * Mirrors webrtc::AudioProcessing::ProcessReverseStream: the most recently
 * rendered (played) frame is handed to the APM so the echo canceller can model
 * and subtract its leakage from the next captured frame. The frame is one
 * SMALT_APM_FRAME_SAMPLES (160-sample) 16 kHz mono float32 block, samples
 * normalized to [-1, 1].
 *
 * @param apm   the handle from cobalt_webrtc_apm_create; must not be NULL.
 * @param frame the far-end reference frame, SMALT_APM_FRAME_SAMPLES float32
 *              samples; must not be NULL.
 * @return SMALT_APM_OK on success, SMALT_APM_BAD_PARAM for a NULL argument, or
 *         SMALT_APM_ERROR when the underlying call fails.
 */
int32_t cobalt_webrtc_apm_process_reverse(void *apm, const float *frame);

/**
 * Conditions one 10 ms near-end capture frame in place.
 *
 * Mirrors webrtc::AudioProcessing::ProcessStream: it runs the configured echo
 * canceller (against the reference last supplied through
 * cobalt_webrtc_apm_process_reverse), the noise suppressor, and the gain
 * controller over the captured frame and writes the conditioned samples back
 * into the same buffer. The frame is one SMALT_APM_FRAME_SAMPLES (160-sample)
 * 16 kHz mono float32 block, samples normalized to [-1, 1].
 *
 * @param apm   the handle from cobalt_webrtc_apm_create; must not be NULL.
 * @param frame the near-end capture frame, conditioned in place,
 *              SMALT_APM_FRAME_SAMPLES float32 samples; must not be NULL.
 * @return SMALT_APM_OK on success, SMALT_APM_BAD_PARAM for a NULL argument, or
 *         SMALT_APM_ERROR when the underlying call fails.
 */
int32_t cobalt_webrtc_apm_process(void *apm, float *frame);

/**
 * Reports the running delay, in milliseconds, between the render and capture
 * streams to the echo canceller.
 *
 * Mirrors webrtc::AudioProcessing::set_stream_delay_ms: AEC3 uses this hint to
 * align the reference and capture streams. The caller supplies the platform's
 * measured round-trip render-to-capture delay; passing it each tick before
 * cobalt_webrtc_apm_process keeps the canceller aligned when the device latency
 * drifts.
 *
 * @param apm      the handle from cobalt_webrtc_apm_create; must not be NULL.
 * @param delay_ms the render-to-capture delay in milliseconds; non-negative.
 * @return SMALT_APM_OK on success, SMALT_APM_BAD_PARAM for a NULL handle.
 */
int32_t cobalt_webrtc_apm_set_stream_delay_ms(void *apm, int32_t delay_ms);

/**
 * Destroys an APM instance created by cobalt_webrtc_apm_create.
 *
 * Mirrors the APM destructor: NULL-safe, and must be called at most once per
 * handle.
 *
 * @param apm the handle, or NULL.
 */
void cobalt_webrtc_apm_destroy(void *apm);

/*
 * Extensions for the voice processor tests. cobalt_webrtc_apm_create_ex sets each knob directly: the
 * echo canceller as in cobalt_webrtc_apm_create, the noise suppression level
 * (-1 off, 0 kLow .. 3 kVeryHigh), and the gain controller 1 with its analog
 * controller (AgcManagerDirect) disabled, so GainControlImpl runs the legacy
 * WebRtcAgc in agc_mode (-1 off, 0 kAdaptiveAnalog, 1 kAdaptiveDigital,
 * 2 kFixedDigital) with the given target level, compression gain and limiter.
 */
void *cobalt_webrtc_apm_create_ex(int32_t aec_mode,
                                  int32_t ns_level,
                                  int32_t agc_mode,
                                  int32_t agc_target_level_dbfs,
                                  int32_t agc_compression_gain_db,
                                  int32_t agc_enable_limiter);

/* Mirrors AudioProcessing::set_stream_analog_level. */
int32_t cobalt_webrtc_apm_set_stream_analog_level(void *apm, int32_t level);

/* Mirrors AudioProcessing::recommended_stream_analog_level. */
int32_t cobalt_webrtc_apm_recommended_stream_analog_level(void *apm);

/*
 * Drives the legacy mobile echo canceller directly (WebRtcAecm_*), 16 kHz,
 * with the given echo mode (0..4) and comfort noise flag.
 */
void *cobalt_webrtc_aecm_create(int32_t echo_mode, int32_t comfort_noise);
int32_t cobalt_webrtc_aecm_buffer_farend(void *aecm, const int16_t *farend);
int32_t cobalt_webrtc_aecm_process(void *aecm, const int16_t *noisy, const int16_t *clean, int16_t *out,
                                   int32_t delay_ms);
void cobalt_webrtc_aecm_destroy(void *aecm);

#ifdef __cplusplus
}
#endif

#endif /* SMALT_APM_SHIM_H */
