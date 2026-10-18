/*
 * smalt_apm_shim.cpp
 *
 * Implementation of the extern-C facade declared in smalt_apm_shim.h over the
 * webrtc-audio-processing standalone library (pulseaudio fork, v2.1). It fixes
 * the geometry to a single 16 kHz mono channel and exchanges audio as flat
 * float32 arrays of one 10 ms APM frame (160 samples), normalized to [-1, 1],
 * the range the WebRTC float ProcessStream / ProcessReverseStream API expects.
 */

#include "smalt_apm_shim.h"

#include <modules/audio_processing/include/audio_processing.h>
#include <modules/audio_processing/aecm/echo_control_mobile.h>

#include <new>

namespace {

using webrtc::AudioProcessing;
using webrtc::AudioProcessingBuilder;
using webrtc::StreamConfig;

// Owns the reference-counted APM instance behind the opaque void* handle.
struct ApmHandle {
    webrtc::scoped_refptr<AudioProcessing> apm;
};

// Maps a suppression intensity (0..1) onto the WebRTC noise-suppressor level.
// webrtc-audio-processing has no ML denoiser, so the ns_use_denoiser flag only
// selects the level here.
AudioProcessing::Config::NoiseSuppression::Level ns_level(float intensity) {
    if (intensity >= 0.75f) {
        return AudioProcessing::Config::NoiseSuppression::kVeryHigh;
    }
    if (intensity >= 0.50f) {
        return AudioProcessing::Config::NoiseSuppression::kHigh;
    }
    if (intensity >= 0.25f) {
        return AudioProcessing::Config::NoiseSuppression::kModerate;
    }
    return AudioProcessing::Config::NoiseSuppression::kLow;
}

}  // namespace

extern "C" void *cobalt_webrtc_apm_create(int32_t aec_mode,
                                          int32_t ns_enabled,
                                          int32_t ns_use_denoiser,
                                          float ns_denoiser_intensity,
                                          int32_t agc_enabled) {
    AudioProcessing::Config config;

    switch (aec_mode) {
        case SMALT_APM_AEC_AEC3:
            config.echo_canceller.enabled = true;
            config.echo_canceller.mobile_mode = false;
            break;
        case SMALT_APM_AEC_MOBILE:
            config.echo_canceller.enabled = true;
            config.echo_canceller.mobile_mode = true;
            break;
        case SMALT_APM_AEC_OFF:
        default:
            config.echo_canceller.enabled = false;
            break;
    }

    config.noise_suppression.enabled = ns_enabled != 0;
    config.noise_suppression.level =
            ns_level(ns_use_denoiser != 0 ? ns_denoiser_intensity : 0.50f);

    // The gain controller is opt-in.
    config.gain_controller1.enabled = agc_enabled != 0;
    config.gain_controller2.enabled = false;

    webrtc::scoped_refptr<AudioProcessing> apm = AudioProcessingBuilder().Create();
    if (apm == nullptr) {
        return nullptr;
    }
    apm->ApplyConfig(config);

    ApmHandle *handle = new (std::nothrow) ApmHandle();
    if (handle == nullptr) {
        return nullptr;
    }
    handle->apm = apm;
    return handle;
}

extern "C" int32_t cobalt_webrtc_apm_process_reverse(void *apm, const float *frame) {
    if (apm == nullptr || frame == nullptr) {
        return SMALT_APM_BAD_PARAM;
    }
    ApmHandle *handle = static_cast<ApmHandle *>(apm);
    const StreamConfig config(SMALT_APM_SAMPLE_RATE_HZ, 1);
    const float *const src[1] = {frame};
    // ProcessReverseStream also writes the (linear AEC farend) output; a local
    // scratch absorbs it since the render path only needs the analysis.
    float scratch[SMALT_APM_FRAME_SAMPLES];
    float *const dst[1] = {scratch};
    const int err = handle->apm->ProcessReverseStream(src, config, config, dst);
    return err == AudioProcessing::kNoError ? SMALT_APM_OK : SMALT_APM_ERROR;
}

extern "C" int32_t cobalt_webrtc_apm_process(void *apm, float *frame) {
    if (apm == nullptr || frame == nullptr) {
        return SMALT_APM_BAD_PARAM;
    }
    ApmHandle *handle = static_cast<ApmHandle *>(apm);
    const StreamConfig config(SMALT_APM_SAMPLE_RATE_HZ, 1);
    float *const io[1] = {frame};
    const int err = handle->apm->ProcessStream(io, config, config, io);
    return err == AudioProcessing::kNoError ? SMALT_APM_OK : SMALT_APM_ERROR;
}

extern "C" int32_t cobalt_webrtc_apm_set_stream_delay_ms(void *apm, int32_t delay_ms) {
    if (apm == nullptr) {
        return SMALT_APM_BAD_PARAM;
    }
    ApmHandle *handle = static_cast<ApmHandle *>(apm);
    handle->apm->set_stream_delay_ms(delay_ms);
    return SMALT_APM_OK;
}

extern "C" void cobalt_webrtc_apm_destroy(void *apm) {
    if (apm == nullptr) {
        return;
    }
    delete static_cast<ApmHandle *>(apm);
}

extern "C" void *cobalt_webrtc_apm_create_ex(int32_t aec_mode,
                                             int32_t ns_level,
                                             int32_t agc_mode,
                                             int32_t agc_target_level_dbfs,
                                             int32_t agc_compression_gain_db,
                                             int32_t agc_enable_limiter) {
    AudioProcessing::Config config;
    config.echo_canceller.enabled = aec_mode == SMALT_APM_AEC_AEC3 || aec_mode == SMALT_APM_AEC_MOBILE;
    config.echo_canceller.mobile_mode = aec_mode == SMALT_APM_AEC_MOBILE;
    config.noise_suppression.enabled = ns_level >= 0;
    if (ns_level >= 0) {
        config.noise_suppression.level = static_cast<AudioProcessing::Config::NoiseSuppression::Level>(ns_level);
    }
    config.gain_controller1.enabled = agc_mode >= 0;
    if (agc_mode >= 0) {
        config.gain_controller1.mode = static_cast<AudioProcessing::Config::GainController1::Mode>(agc_mode);
        config.gain_controller1.target_level_dbfs = agc_target_level_dbfs;
        config.gain_controller1.compression_gain_db = agc_compression_gain_db;
        config.gain_controller1.enable_limiter = agc_enable_limiter != 0;
        config.gain_controller1.analog_gain_controller.enabled = false;
    }
    config.gain_controller2.enabled = false;
    webrtc::scoped_refptr<AudioProcessing> apm = AudioProcessingBuilder().Create();
    if (apm == nullptr) {
        return nullptr;
    }
    apm->ApplyConfig(config);
    ApmHandle *handle = new (std::nothrow) ApmHandle();
    if (handle == nullptr) {
        return nullptr;
    }
    handle->apm = apm;
    return handle;
}

extern "C" int32_t cobalt_webrtc_apm_set_stream_analog_level(void *apm, int32_t level) {
    if (apm == nullptr) {
        return SMALT_APM_BAD_PARAM;
    }
    static_cast<ApmHandle *>(apm)->apm->set_stream_analog_level(level);
    return SMALT_APM_OK;
}

extern "C" int32_t cobalt_webrtc_apm_recommended_stream_analog_level(void *apm) {
    if (apm == nullptr) {
        return SMALT_APM_BAD_PARAM;
    }
    return static_cast<ApmHandle *>(apm)->apm->recommended_stream_analog_level();
}

extern "C" void *cobalt_webrtc_aecm_create(int32_t echo_mode, int32_t comfort_noise) {
    void *aecm = webrtc::WebRtcAecm_Create();
    if (aecm == nullptr) {
        return nullptr;
    }
    if (webrtc::WebRtcAecm_Init(aecm, SMALT_APM_SAMPLE_RATE_HZ) != 0) {
        webrtc::WebRtcAecm_Free(aecm);
        return nullptr;
    }
    webrtc::AecmConfig config;
    config.cngMode = static_cast<int16_t>(comfort_noise != 0 ? webrtc::AecmTrue : webrtc::AecmFalse);
    config.echoMode = static_cast<int16_t>(echo_mode);
    if (webrtc::WebRtcAecm_set_config(aecm, config) != 0) {
        webrtc::WebRtcAecm_Free(aecm);
        return nullptr;
    }
    return aecm;
}

extern "C" int32_t cobalt_webrtc_aecm_buffer_farend(void *aecm, const int16_t *farend) {
    if (aecm == nullptr || farend == nullptr) {
        return SMALT_APM_BAD_PARAM;
    }
    return webrtc::WebRtcAecm_BufferFarend(aecm, farend, SMALT_APM_FRAME_SAMPLES);
}

extern "C" int32_t cobalt_webrtc_aecm_process(void *aecm, const int16_t *noisy, const int16_t *clean, int16_t *out,
                                              int32_t delay_ms) {
    if (aecm == nullptr || noisy == nullptr || out == nullptr) {
        return SMALT_APM_BAD_PARAM;
    }
    return webrtc::WebRtcAecm_Process(aecm, noisy, clean, out, SMALT_APM_FRAME_SAMPLES,
                                      static_cast<int16_t>(delay_ms));
}

extern "C" void cobalt_webrtc_aecm_destroy(void *aecm) {
    if (aecm != nullptr) {
        webrtc::WebRtcAecm_Free(aecm);
    }
}
