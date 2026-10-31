/*
 * smalt_apm_shim.cpp
 *
 * Implementation of the extern-C facade declared in smalt_apm_shim.h over the
 * webrtc-audio-processing standalone library (pulseaudio fork, v2.1).
 */

#include "smalt_apm_shim.h"

#include <api/audio/audio_processing.h>
#include <api/audio/audio_view.h>
#include <common_audio/channel_buffer.h>
#include <common_audio/resampler/push_sinc_resampler.h>
#include <modules/audio_processing/aecm/echo_control_mobile.h>
#include <modules/audio_processing/agc2/agc2_common.h>
#include <modules/audio_processing/agc2/cpu_features.h>
#include <modules/audio_processing/agc2/vad_wrapper.h>
#include <modules/audio_processing/splitting_filter.h>
#include <modules/audio_processing/vad/voice_activity_detector.h>

#include <cmath>
#include <cstring>
#include <memory>
#include <new>
#include <vector>

namespace {

using webrtc::AudioProcessing;
using webrtc::AudioProcessingBuilder;
using webrtc::StreamConfig;

// Owns the APM instance and the channel pointers of its frames.
struct ApmHandle {
    webrtc::scoped_refptr<AudioProcessing> apm;
    int frames;
    int capture_channels;
    int render_channels;
    std::vector<float> render_scratch;
};

struct SplitHandle {
    int bands;
    int frames;
    std::unique_ptr<webrtc::SplittingFilter> filter;
    webrtc::ChannelBuffer<float> data;
    webrtc::ChannelBuffer<float> split;

    SplitHandle(int num_bands, int num_frames)
        : bands(num_bands),
          frames(num_frames),
          filter(new webrtc::SplittingFilter(1, num_bands, num_frames)),
          data(num_frames, 1),
          split(num_frames, 1, num_bands) {}
};

struct ResamplerHandle {
    int source_frames;
    int destination_frames;
    webrtc::PushSincResampler resampler;

    ResamplerHandle(int source, int destination)
        : source_frames(source), destination_frames(destination), resampler(source, destination) {}
};

struct VadHandle {
    int frames;
    std::unique_ptr<webrtc::VoiceActivityDetectorWrapper> vad;
};

}  // namespace

extern "C" void *smalt_apm_create(const int32_t *ints, const float *floats) {
    if (ints == nullptr || floats == nullptr) {
        return nullptr;
    }
    AudioProcessing::Config config;
    config.pipeline.multi_channel_capture = ints[SMALT_APM_I_MC_CAPTURE] != 0;
    config.pipeline.multi_channel_render = ints[SMALT_APM_I_MC_RENDER] != 0;
    config.pipeline.capture_downmix_method = ints[SMALT_APM_I_DOWNMIX_FIRST] != 0
            ? AudioProcessing::Config::Pipeline::DownmixMethod::kUseFirstChannel
            : AudioProcessing::Config::Pipeline::DownmixMethod::kAverageChannels;
    const int aec = ints[SMALT_APM_I_AEC];
    config.echo_canceller.enabled = aec == SMALT_APM_AEC_AEC3 || aec == SMALT_APM_AEC_MOBILE;
    config.echo_canceller.mobile_mode = aec == SMALT_APM_AEC_MOBILE;
    config.noise_suppression.enabled = ints[SMALT_APM_I_NS] >= 0;
    if (ints[SMALT_APM_I_NS] >= 0) {
        config.noise_suppression.level =
                static_cast<AudioProcessing::Config::NoiseSuppression::Level>(ints[SMALT_APM_I_NS]);
    }
    config.high_pass_filter.enabled = ints[SMALT_APM_I_HPF] != 0;

    auto &agc1 = config.gain_controller1;
    agc1.enabled = ints[SMALT_APM_I_AGC1_MODE] >= 0;
    if (agc1.enabled) {
        agc1.mode = static_cast<AudioProcessing::Config::GainController1::Mode>(ints[SMALT_APM_I_AGC1_MODE]);
    }
    agc1.target_level_dbfs = ints[SMALT_APM_I_AGC1_TARGET];
    agc1.compression_gain_db = ints[SMALT_APM_I_AGC1_COMPRESSION];
    agc1.enable_limiter = ints[SMALT_APM_I_AGC1_LIMITER] != 0;
    auto &analog = agc1.analog_gain_controller;
    analog.enabled = ints[SMALT_APM_I_ANALOG] != 0;
    analog.startup_min_volume = ints[SMALT_APM_I_ANALOG_STARTUP];
    analog.clipped_level_min = ints[SMALT_APM_I_ANALOG_CLIPPED_MIN];
    analog.enable_digital_adaptive = ints[SMALT_APM_I_ANALOG_DIGITAL] != 0;
    analog.clipped_level_step = ints[SMALT_APM_I_ANALOG_CLIPPED_STEP];
    analog.clipped_ratio_threshold = floats[SMALT_APM_F_CLIPPED_RATIO];
    analog.clipped_wait_frames = ints[SMALT_APM_I_ANALOG_CLIPPED_WAIT];
    auto &predictor = analog.clipping_predictor;
    predictor.enabled = ints[SMALT_APM_I_CP_ENABLED] != 0;
    predictor.mode = static_cast<AudioProcessing::Config::GainController1::AnalogGainController::ClippingPredictor::Mode>(
            ints[SMALT_APM_I_CP_MODE]);
    predictor.window_length = ints[SMALT_APM_I_CP_WINDOW];
    predictor.reference_window_length = ints[SMALT_APM_I_CP_REF_WINDOW];
    predictor.reference_window_delay = ints[SMALT_APM_I_CP_REF_DELAY];
    predictor.clipping_threshold = floats[SMALT_APM_F_CP_THRESHOLD];
    predictor.crest_factor_margin = floats[SMALT_APM_F_CP_CREST];
    predictor.use_predicted_step = ints[SMALT_APM_I_CP_USE_STEP] != 0;

    auto &agc2 = config.gain_controller2;
    agc2.enabled = ints[SMALT_APM_I_AGC2] != 0;
    agc2.adaptive_digital.enabled = ints[SMALT_APM_I_AGC2_ADAPTIVE] != 0;
    agc2.input_volume_controller.enabled = ints[SMALT_APM_I_AGC2_IVC] != 0;
    agc2.fixed_digital.gain_db = floats[SMALT_APM_F_AGC2_FIXED_GAIN];
    agc2.adaptive_digital.headroom_db = floats[SMALT_APM_F_AGC2_HEADROOM];
    agc2.adaptive_digital.max_gain_db = floats[SMALT_APM_F_AGC2_MAX_GAIN];
    agc2.adaptive_digital.initial_gain_db = floats[SMALT_APM_F_AGC2_INITIAL_GAIN];
    agc2.adaptive_digital.max_gain_change_db_per_second = floats[SMALT_APM_F_AGC2_MAX_CHANGE];
    agc2.adaptive_digital.max_output_noise_level_dbfs = floats[SMALT_APM_F_AGC2_MAX_NOISE];

    webrtc::scoped_refptr<AudioProcessing> apm = AudioProcessingBuilder().SetConfig(config).Create();
    if (apm == nullptr) {
        return nullptr;
    }
    const int rate = ints[SMALT_APM_I_RATE];
    const int capture_channels = ints[SMALT_APM_I_CAPTURE_CHANNELS];
    const int render_channels = ints[SMALT_APM_I_RENDER_CHANNELS];
    webrtc::ProcessingConfig processing_config = {{
            StreamConfig(rate, capture_channels), StreamConfig(rate, capture_channels),
            StreamConfig(rate, render_channels), StreamConfig(rate, render_channels)}};
    if (apm->Initialize(processing_config) != AudioProcessing::kNoError) {
        return nullptr;
    }
    ApmHandle *handle = new (std::nothrow) ApmHandle();
    if (handle == nullptr) {
        return nullptr;
    }
    handle->apm = apm;
    handle->frames = rate / 100;
    handle->capture_channels = capture_channels;
    handle->render_channels = render_channels;
    handle->render_scratch.resize(static_cast<size_t>(handle->frames) * render_channels);
    return handle;
}

extern "C" int32_t smalt_apm_process_reverse(void *apm, const float *frame) {
    if (apm == nullptr || frame == nullptr) {
        return SMALT_APM_BAD_PARAM;
    }
    ApmHandle *handle = static_cast<ApmHandle *>(apm);
    const StreamConfig config(handle->frames * 100, handle->render_channels);
    std::vector<const float *> src(handle->render_channels);
    std::vector<float *> dst(handle->render_channels);
    for (int c = 0; c < handle->render_channels; c++) {
        src[c] = frame + c * handle->frames;
        dst[c] = handle->render_scratch.data() + c * handle->frames;
    }
    const int err = handle->apm->ProcessReverseStream(src.data(), config, config, dst.data());
    return err == AudioProcessing::kNoError ? SMALT_APM_OK : SMALT_APM_ERROR;
}

extern "C" int32_t smalt_apm_process(void *apm, float *frame) {
    if (apm == nullptr || frame == nullptr) {
        return SMALT_APM_BAD_PARAM;
    }
    ApmHandle *handle = static_cast<ApmHandle *>(apm);
    const StreamConfig config(handle->frames * 100, handle->capture_channels);
    std::vector<float *> io(handle->capture_channels);
    for (int c = 0; c < handle->capture_channels; c++) {
        io[c] = frame + c * handle->frames;
    }
    const int err = handle->apm->ProcessStream(io.data(), config, config, io.data());
    return err == AudioProcessing::kNoError ? SMALT_APM_OK : SMALT_APM_ERROR;
}

extern "C" int32_t smalt_apm_set_stream_delay_ms(void *apm, int32_t delay_ms) {
    if (apm == nullptr) {
        return SMALT_APM_BAD_PARAM;
    }
    static_cast<ApmHandle *>(apm)->apm->set_stream_delay_ms(delay_ms);
    return SMALT_APM_OK;
}

extern "C" int32_t smalt_apm_set_stream_analog_level(void *apm, int32_t level) {
    if (apm == nullptr) {
        return SMALT_APM_BAD_PARAM;
    }
    static_cast<ApmHandle *>(apm)->apm->set_stream_analog_level(level);
    return SMALT_APM_OK;
}

extern "C" int32_t smalt_apm_recommended_stream_analog_level(void *apm) {
    if (apm == nullptr) {
        return SMALT_APM_BAD_PARAM;
    }
    return static_cast<ApmHandle *>(apm)->apm->recommended_stream_analog_level();
}

extern "C" void smalt_apm_destroy(void *apm) {
    delete static_cast<ApmHandle *>(apm);
}

extern "C" void *smalt_resampler_create(int32_t source_frames, int32_t destination_frames) {
    return new (std::nothrow) ResamplerHandle(source_frames, destination_frames);
}

extern "C" int32_t smalt_resampler_resample(void *resampler, const float *source, float *destination) {
    if (resampler == nullptr || source == nullptr || destination == nullptr) {
        return SMALT_APM_BAD_PARAM;
    }
    auto *r = static_cast<ResamplerHandle *>(resampler);
    return static_cast<int32_t>(r->resampler.Resample(source, r->source_frames, destination, r->destination_frames));
}

extern "C" void smalt_resampler_destroy(void *resampler) {
    delete static_cast<ResamplerHandle *>(resampler);
}

extern "C" void *smalt_split_create(int32_t num_bands, int32_t num_frames) {
    return new (std::nothrow) SplitHandle(num_bands, num_frames);
}

extern "C" int32_t smalt_split_analysis(void *filter, const float *data, float *bands) {
    if (filter == nullptr || data == nullptr || bands == nullptr) {
        return SMALT_APM_BAD_PARAM;
    }
    SplitHandle *h = static_cast<SplitHandle *>(filter);
    std::memcpy(h->data.channels()[0], data, sizeof(float) * h->frames);
    h->filter->Analysis(&h->data, &h->split);
    const int per_band = h->frames / h->bands;
    for (int b = 0; b < h->bands; b++) {
        std::memcpy(bands + b * per_band, h->split.channels(b)[0], sizeof(float) * per_band);
    }
    return SMALT_APM_OK;
}

extern "C" int32_t smalt_split_synthesis(void *filter, const float *bands, float *data) {
    if (filter == nullptr || data == nullptr || bands == nullptr) {
        return SMALT_APM_BAD_PARAM;
    }
    SplitHandle *h = static_cast<SplitHandle *>(filter);
    const int per_band = h->frames / h->bands;
    for (int b = 0; b < h->bands; b++) {
        std::memcpy(h->split.channels(b)[0], bands + b * per_band, sizeof(float) * per_band);
    }
    h->filter->Synthesis(&h->split, &h->data);
    std::memcpy(data, h->data.channels()[0], sizeof(float) * h->frames);
    return SMALT_APM_OK;
}

extern "C" void smalt_split_destroy(void *filter) {
    delete static_cast<SplitHandle *>(filter);
}

extern "C" void *smalt_vad_create(int32_t sample_rate_hz) {
    VadHandle *handle = new (std::nothrow) VadHandle();
    if (handle == nullptr) {
        return nullptr;
    }
    handle->frames = sample_rate_hz / 100;
    handle->vad.reset(new webrtc::VoiceActivityDetectorWrapper(
            webrtc::kVadResetPeriodMs, webrtc::AvailableCpuFeatures(false, false, false), sample_rate_hz));
    return handle;
}

extern "C" float smalt_vad_analyze(void *vad, const float *frame) {
    if (vad == nullptr || frame == nullptr) {
        return -1.0f;
    }
    VadHandle *handle = static_cast<VadHandle *>(vad);
    webrtc::DeinterleavedView<const float> view(frame, handle->frames, 1);
    return handle->vad->Analyze(view);
}

extern "C" void smalt_vad_destroy(void *vad) {
    delete static_cast<VadHandle *>(vad);
}

extern "C" void *smalt_aecm_create(int32_t echo_mode, int32_t comfort_noise) {
    void *aecm = webrtc::WebRtcAecm_Create();
    if (aecm == nullptr) {
        return nullptr;
    }
    if (webrtc::WebRtcAecm_Init(aecm, 16000) != 0) {
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

extern "C" int32_t smalt_aecm_buffer_farend(void *aecm, const int16_t *farend) {
    if (aecm == nullptr || farend == nullptr) {
        return SMALT_APM_BAD_PARAM;
    }
    return webrtc::WebRtcAecm_BufferFarend(aecm, farend, 160);
}

extern "C" int32_t smalt_aecm_process(void *aecm, const int16_t *noisy, const int16_t *clean, int16_t *out,
                                      int32_t delay_ms) {
    if (aecm == nullptr || noisy == nullptr || out == nullptr) {
        return SMALT_APM_BAD_PARAM;
    }
    return webrtc::WebRtcAecm_Process(aecm, noisy, clean, out, 160, static_cast<int16_t>(delay_ms));
}

extern "C" void smalt_aecm_destroy(void *aecm) {
    if (aecm != nullptr) {
        webrtc::WebRtcAecm_Free(aecm);
    }
}

extern "C" double smalt_libm(int32_t function, double x, double y) {
    switch (function) {
        case 0:
            return std::exp(x);
        case 1:
            return std::log(x);
        case 2:
            return std::pow(x, y);
        case 3:
            return std::log10(x);
        case 4:
            return std::sin(x);
        default:
            return 0.0;
    }
}

extern "C" void *smalt_avad_create(void) {
    return new (std::nothrow) webrtc::VoiceActivityDetector();
}

extern "C" int32_t smalt_avad_process(void *vad, const int16_t *audio, double *out) {
    if (vad == nullptr || audio == nullptr || out == nullptr) {
        return -1;
    }
    auto *detector = static_cast<webrtc::VoiceActivityDetector *>(vad);
    detector->ProcessChunk(audio, 160, 16000);
    const std::vector<double> &rms = detector->chunkwise_rms();
    const std::vector<double> &probabilities = detector->chunkwise_voice_probabilities();
    for (size_t i = 0; i < rms.size() && i < 3; i++) {
        out[i] = rms[i];
        out[3 + i] = probabilities[i];
    }
    out[6] = detector->last_voice_probability();
    return static_cast<int32_t>(rms.size());
}

extern "C" void smalt_avad_destroy(void *vad) {
    delete static_cast<webrtc::VoiceActivityDetector *>(vad);
}
