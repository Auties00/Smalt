/* Ports webrtc-audio-processing 2.1 (modules/audio_processing/audio_processing_impl.cc, audio_buffer.cc,
   high_pass_filter.cc, echo_control_mobile_impl.cc). Copyright (c) 2011, The WebRTC project authors; BSD 3-Clause
   License. Modified for Smalt. */
package com.github.auties00.smalt.audio.voice;

import com.github.auties00.smalt.audio.voice.internal.CascadedBiQuadFilter;
import com.github.auties00.smalt.audio.voice.internal.FixedPointMath;
import com.github.auties00.smalt.audio.voice.internal.PushSincResampler;
import com.github.auties00.smalt.audio.voice.internal.SplittingFilter;
import com.github.auties00.smalt.audio.voice.internal.aec3.EchoCanceller3;
import com.github.auties00.smalt.audio.voice.internal.aecm.MobileEchoCanceller;
import com.github.auties00.smalt.audio.voice.internal.agc.AnalogGainManager;
import com.github.auties00.smalt.audio.voice.internal.agc.GainController;
import com.github.auties00.smalt.audio.voice.internal.agc2.ClippingPredictor;
import com.github.auties00.smalt.audio.voice.internal.agc2.LevelController;
import com.github.auties00.smalt.audio.voice.internal.ns.NoiseSuppressor;
import com.github.auties00.smalt.internal.simd.Simd;

import java.util.Objects;

/**
 * Conditions the microphone signal of a voice call: cancels the acoustic echo of the far-end
 * signal played on the local speaker, suppresses stationary background noise and controls the
 * speech level.
 *
 * <p>Audio is processed in 10 ms frames of {@link #frameSamples()} samples per channel, at the
 * sampling rate and with the channel counts of the {@linkplain VoiceProcessorOptions options};
 * the samples of a frame are interleaved, channel by channel. The far-end ("render") signal, the
 * audio the local loudspeaker plays, is passed to {@link #processRender(float[], int)}; the
 * near-end ("capture") signal, the microphone audio, to
 * {@link #processCapture(float[], int, float[], int)}, which conditions it into another array or
 * in place. Calls alternate: for every 10 ms of time, one render frame then one capture frame.
 * Render frames are queued and consumed by the next capture frame, so short bursts of either kind
 * are tolerated; the standard echo canceller holds up to one second of render frames not yet
 * consumed and drops the frames beyond it.
 *
 * <p>Samples are floats in {@code [-1, 1]} (values beyond are clamped, NaN is read as zero), or
 * 16-bit integers through the {@code short[]} overloads, which give exactly the result of
 * converting {@code s / 32768f} and back with rounding. The capture path applies, in order, a
 * 100 Hz high-pass filter (whenever echo cancellation or noise suppression is enabled), the
 * analysis of the gain controller, the echo canceller and the noise suppressor (with the mobile
 * echo canceller, the noise suppressor runs first), the gain controller and the level
 * controller. The standard echo canceller delays the capture signal by 4 ms and the noise
 * suppressor by 6 ms.
 *
 * <p>The standard echo canceller estimates the delay between the render and the capture signal
 * itself (up to about 600 ms); {@link #setStreamDelayMs(int)} reports an externally measured
 * delay, which it uses as the starting point. The mobile echo canceller depends on that report,
 * which stays in effect until the next one. With an analog gain controller
 * ({@link GainControl#ADAPTIVE_ANALOG}, {@link GainControl#MANAGED_ANALOG}) or the input volume
 * controller of the level controller, {@link #setAnalogLevel(int)} reports the microphone volume,
 * at least once before the first capture frame, and {@link #recommendedAnalogLevel()} returns the
 * volume to apply.
 *
 * <p>Instances are not thread-safe: render and capture calls must not overlap.
 *
 * @implNote This implementation follows WebRTC's {@code AudioProcessingImpl}, with AEC3 in its
 * default configuration, in the order of {@code ProcessCaptureStreamLocked}. It flushes tiny values
 * to zero (see the note in the class body) and, with the Vector API, sums the delay estimator of
 * the standard echo canceller in another order, so its output matches the reference in quality,
 * not bit for bit; without both it reproduces the reference built for WebAssembly bit for bit.
 */
public final class VoiceProcessor {
    /*
     * The render frames of the mobile echo canceller and the gain controller are handed over at once
     * instead of through the reference's 100-frame queues, which the next capture frame empties in the
     * same order, so the result is the same.
     *
     * When the render or the capture signal falls silent, the reference's filters decay into
     * subnormal numbers, which slow the delay estimator down for as long as the silence lasts; the
     * reference avoids this only where its build flushes subnormals to zero (DenormalDisabler). Java
     * cannot change the floating-point mode, so input samples, high-pass filter states and delay
     * estimator decimation states are zeroed once they fall below 0x1p-80 in the 16-bit range (about
     * 500 dB below full scale), which changes the output by far less than one part in 10^10 of full
     * scale.
     */

    /**
     * The 16-bit full scale the float samples are multiplied by.
     */
    private static final float FULL_SCALE = 32768.f;

    /**
     * The magnitude, in the 16-bit range, below which input samples and high-pass filter states
     * are zeroed when tiny values are flushed.
     */
    private static final float FLUSH_FLOOR = 0x1p-80f;

    /**
     * The longest stream delay the echo canceller accepts, in milliseconds.
     */
    private static final int MAX_STREAM_DELAY_MS = 500;

    /**
     * The highest microphone level of the analog gain controllers.
     */
    private static final int MAX_ANALOG_LEVEL = 255;

    /**
     * The samples of a 16 kHz band.
     */
    private static final int BAND_SAMPLES = 160;

    /**
     * The options.
     */
    private final VoiceProcessorOptions options;

    /**
     * The number of samples per channel of one 10 ms frame at the stream rate.
     */
    private final int frameSamples;

    /**
     * The number of samples per channel of one 10 ms frame at the processing rate.
     */
    private final int processingSamples;

    /**
     * The number of 16 kHz bands of the processing rate.
     */
    private final int numBands;

    /**
     * The number of capture channels of the stream.
     */
    private final int captureChannels;

    /**
     * The number of capture channels the processing stages condition.
     */
    private final int processingChannels;

    /**
     * The number of render channels of the stream.
     */
    private final int renderChannels;

    /**
     * The number of render channels the echo cancellers see.
     */
    private final int renderProcessingChannels;

    /**
     * Whether any stage works on bands.
     */
    private final boolean bandsNeeded;

    /**
     * Whether any stage consumes render frames.
     */
    private final boolean rendersNeeded;

    /**
     * The capture signal at the processing rate, {@code [channel][sample]}, in the 16-bit range.
     */
    private final float[][] capture;

    /**
     * The bands of the capture signal, {@code [channel][band][sample]}; the same arrays as
     * {@link #capture} when there is one band.
     */
    private final float[][][] captureBands;

    /**
     * The render signal at the processing rate, {@code [channel][sample]}, in the 16-bit range.
     */
    private final float[][] render;

    /**
     * The bands of the render signal, {@code [channel][band][sample]}.
     */
    private final float[][][] renderBands;

    /**
     * The lowest band of every render channel.
     */
    private final float[][] renderLowBands;

    /**
     * The band splitting filter of the capture signal, or {@code null} with one band.
     */
    private final SplittingFilter captureSplitter;

    /**
     * The band splitting filter of the render signal, or {@code null} with one band.
     */
    private final SplittingFilter renderSplitter;

    /**
     * The resamplers of the capture input, one per channel, or {@code null} when the stream rate
     * is the processing rate.
     */
    private final PushSincResampler[] captureInputResamplers;

    /**
     * The resamplers of the capture output, one per processed channel, or {@code null}.
     */
    private final PushSincResampler[] captureOutputResamplers;

    /**
     * The resamplers of the render input, one per processed render channel, or {@code null}.
     */
    private final PushSincResampler[] renderInputResamplers;

    /**
     * The deinterleaved input of one channel at the stream rate.
     */
    private final float[] streamChannel;

    /**
     * The resampled output of one channel at the stream rate.
     */
    private final float[] streamOutput;

    /**
     * The high-pass filters of every capture channel, or {@code null} when disabled.
     */
    private final CascadedBiQuadFilter[] highPassFilters;

    /**
     * The standard echo canceller, or {@code null} when disabled.
     */
    private final EchoCanceller3 echoCanceller;

    /**
     * The mobile echo cancellers, one per pair of capture and render channel, or {@code null}
     * when disabled.
     */
    private final MobileEchoCanceller[] mobileEchoCancellers;

    /**
     * The 16-bit frame the mobile echo canceller works on, or {@code null} when it is disabled.
     */
    private final short[] mobileFrame;

    /**
     * The noise suppressor, or {@code null} when disabled.
     */
    private final NoiseSuppressor noiseSuppressor;

    /**
     * The gain controller, or {@code null} when disabled.
     */
    private final GainController gainController;

    /**
     * The analog gain controller of {@link GainControl#MANAGED_ANALOG}, or {@code null} with the
     * other gain control modes.
     */
    private final AnalogGainManager analogGainManager;

    /**
     * The level controller, or {@code null} when disabled.
     */
    private final LevelController levelController;

    /**
     * Whether the level controller recommends the microphone volume.
     */
    private final boolean inputVolumeControlled;

    /**
     * Whether tiny input samples and filter states are zeroed.
     */
    private final boolean flushTiny;

    /**
     * The last reported stream delay, in milliseconds.
     */
    private int streamDelayMs;

    /**
     * Whether a stream delay was reported since the last capture frame.
     */
    private boolean streamDelaySet;

    /**
     * The last reported microphone level, or {@code -1} before the first report.
     */
    private int analogLevel;

    /**
     * Whether the last report changed the microphone level.
     */
    private boolean analogLevelChanged;

    /**
     * The microphone level recommended after the last capture frame, or {@code -1} when none was
     * recommended since the last report.
     */
    private int recommendedLevel;

    /**
     * Creates a processor.
     *
     * @param options the stages to run and their settings
     */
    public VoiceProcessor(VoiceProcessorOptions options) {
        this(options, Simd.ENABLED, true);
    }

    /**
     * Creates a processor, choosing whether the standard echo canceller uses its Vector API
     * kernels and whether tiny values are flushed.
     *
     * @param options the stages to run and their settings
     * @param vectorize whether the Vector API kernels are used
     * @param flushTiny whether tiny input samples and filter states are zeroed; the processor
     * reproduces the reference bit for bit only when neither this nor {@code vectorize} is set
     */
    VoiceProcessor(VoiceProcessorOptions options, boolean vectorize, boolean flushTiny) {
        this.options = Objects.requireNonNull(options, "options");
        this.flushTiny = flushTiny;
        var rate = options.sampleRate();
        this.frameSamples = rate / 100;
        var processingRate = Math.max(rate, 16_000);
        this.processingSamples = processingRate / 100;
        this.numBands = processingRate / 16_000;
        this.captureChannels = options.captureChannels();
        this.renderChannels = options.renderChannels();
        var echoCancellation = options.echoCancellation();
        var noiseSuppression = options.noiseSuppression() != NoiseSuppression.OFF;
        var gainControl = options.gainControl();
        this.processingChannels = echoCancellation == EchoCancellation.STANDARD && !options.multichannelCapture()
                ? 1 : captureChannels;
        var renderBanded = echoCancellation != EchoCancellation.OFF || gainControl != GainControl.OFF;
        this.renderProcessingChannels = renderBanded && !options.multichannelRender() ? 1 : renderChannels;
        this.bandsNeeded = echoCancellation != EchoCancellation.OFF || noiseSuppression || gainControl != GainControl.OFF;
        this.rendersNeeded = echoCancellation != EchoCancellation.OFF
                || (gainControl != GainControl.OFF && gainControl != GainControl.MANAGED_ANALOG);

        this.capture = new float[captureChannels][processingSamples];
        this.captureBands = bands(capture);
        this.render = new float[renderProcessingChannels][processingSamples];
        this.renderBands = bands(render);
        this.renderLowBands = new float[renderProcessingChannels][];
        for (var ch = 0; ch < renderProcessingChannels; ch++) {
            renderLowBands[ch] = renderBands[ch][0];
        }
        this.captureSplitter = numBands > 1 ? new SplittingFilter(captureChannels, numBands) : null;
        this.renderSplitter = numBands > 1 ? new SplittingFilter(renderProcessingChannels, numBands) : null;
        var resampled = frameSamples != processingSamples;
        this.captureInputResamplers = resampled ? resamplers(captureChannels, frameSamples, processingSamples) : null;
        this.captureOutputResamplers = resampled ? resamplers(captureChannels, processingSamples, frameSamples) : null;
        this.renderInputResamplers = resampled
                ? resamplers(renderProcessingChannels, frameSamples, processingSamples) : null;
        this.streamChannel = new float[frameSamples];
        this.streamOutput = new float[frameSamples];

        if (echoCancellation != EchoCancellation.OFF || noiseSuppression) {
            this.highPassFilters = new CascadedBiQuadFilter[captureChannels];
            for (var ch = 0; ch < captureChannels; ch++) {
                highPassFilters[ch] = highPassFilter(processingRate);
            }
        } else {
            this.highPassFilters = null;
        }
        if (echoCancellation == EchoCancellation.STANDARD) {
            this.echoCanceller = new EchoCanceller3(numBands, renderProcessingChannels, processingChannels, vectorize,
                    flushTiny);
        } else {
            this.echoCanceller = null;
        }
        if (echoCancellation == EchoCancellation.MOBILE) {
            this.mobileEchoCancellers = new MobileEchoCanceller[processingChannels * renderProcessingChannels];
            for (var i = 0; i < mobileEchoCancellers.length; i++) {
                mobileEchoCancellers[i] = new MobileEchoCanceller(options.mobileEchoRoute().echoMode(),
                        options.comfortNoise());
            }
            this.mobileFrame = new short[BAND_SAMPLES];
        } else {
            this.mobileEchoCancellers = null;
            this.mobileFrame = null;
        }
        this.noiseSuppressor = noiseSuppression
                ? new NoiseSuppressor(options.noiseSuppression().level(), numBands, processingChannels) : null;
        if (gainControl == GainControl.OFF) {
            this.gainController = null;
            this.analogGainManager = null;
        } else if (gainControl == GainControl.MANAGED_ANALOG) {
            this.analogGainManager = analogGainManager(options.analogGainController(), processingChannels);
            this.gainController = new GainController(GainControl.FIXED_DIGITAL,
                    analogGainManager.digitalTargetLevelDbfs(), analogGainManager.digitalCompressionGainDb(),
                    analogGainManager.digitalLimiter(), processingChannels, numBands);
        } else {
            this.analogGainManager = null;
            this.gainController = new GainController(gainControl, options.targetLevelDbfs(),
                    options.compressionGainDb(), options.limiter(), processingChannels, numBands);
        }
        var levelControl = options.levelControl().orElse(null);
        if (levelControl != null) {
            this.levelController = new LevelController(processingRate, captureChannels, levelControl.fixedGainDb(),
                    levelControl.adaptiveDigital(), levelControl.headroomDb(), levelControl.maxGainDb(),
                    levelControl.initialGainDb(), levelControl.maxGainChangeDbPerSecond(),
                    levelControl.maxOutputNoiseLevelDbfs(), levelControl.inputVolumeController());
            this.inputVolumeControlled = levelControl.inputVolumeController();
        } else {
            this.levelController = null;
            this.inputVolumeControlled = false;
        }
        this.analogLevel = -1;
        this.recommendedLevel = -1;
    }

    /**
     * Creates the analog gain controller of {@link GainControl#MANAGED_ANALOG}.
     *
     * @param analog its settings
     * @param channels the number of processed capture channels
     * @return the controller
     */
    private static AnalogGainManager analogGainManager(AnalogGainControllerOptions analog, int channels) {
        var prediction = analog.clippingPrediction();
        ClippingPredictor predictor = null;
        if (prediction != ClippingPrediction.OFF) {
            var mode = switch (prediction) {
                case CLIPPING_EVENT -> ClippingPredictor.CLIPPING_EVENT;
                case ADAPTIVE_STEP_PEAK -> ClippingPredictor.ADAPTIVE_STEP_PEAK;
                default -> ClippingPredictor.FIXED_STEP_PEAK;
            };
            predictor = new ClippingPredictor(channels, mode, analog.predictionWindowFrames(),
                    analog.referenceWindowFrames(), analog.referenceWindowDelayFrames(), analog.clippingThresholdDbfs(),
                    analog.crestFactorMarginDb());
        }
        return new AnalogGainManager(channels, analog.clippedLevelMin(), analog.clippedLevelStep(),
                analog.clippedRatioThreshold(), analog.clippedWaitFrames(), analog.digitalAdaptive(), predictor,
                analog.predictedStep());
    }

    /**
     * Returns the per-band views of a signal.
     *
     * @param signal the signal, {@code [channel][sample]}
     * @return the bands, {@code [channel][band][sample]}; the channel arrays themselves with one
     * band
     */
    private float[][][] bands(float[][] signal) {
        var result = new float[signal.length][][];
        for (var ch = 0; ch < signal.length; ch++) {
            if (numBands == 1) {
                result[ch] = new float[][]{signal[ch]};
            } else {
                result[ch] = new float[numBands][BAND_SAMPLES];
            }
        }
        return result;
    }

    /**
     * Creates resamplers.
     *
     * @param count the number of resamplers
     * @param from the input samples per call
     * @param to the output samples per call
     * @return the resamplers
     */
    private static PushSincResampler[] resamplers(int count, int from, int to) {
        var result = new PushSincResampler[count];
        for (var i = 0; i < count; i++) {
            result[i] = new PushSincResampler(from, to);
        }
        return result;
    }

    /**
     * Creates the 100 Hz high-pass filter of a processing rate.
     *
     * @param rate the processing rate, 16, 32 or 48 kHz
     * @return the filter
     */
    private static CascadedBiQuadFilter highPassFilter(int rate) {
        return switch (rate) {
            case 16_000 -> new CascadedBiQuadFilter(0.97261f, -1.94523f, 0.97261f, -1.94448f, 0.94598f, 1);
            case 32_000 -> new CascadedBiQuadFilter(0.98621f, -1.97242f, 0.98621f, -1.97223f, 0.97261f, 1);
            default -> new CascadedBiQuadFilter(0.99079f, -1.98157f, 0.99079f, -1.98149f, 0.98166f, 1);
        };
    }

    /**
     * {@return the options this processor was created with}
     */
    public VoiceProcessorOptions options() {
        return options;
    }

    /**
     * Returns the number of samples per channel of one 10 ms frame.
     *
     * @return {@code sampleRate / 100}, 160 at 16 kHz
     */
    public int frameSamples() {
        return frameSamples;
    }

    /**
     * Passes one 10 ms frame of the far-end signal to the echo canceller and the gain controller.
     *
     * @param frame the interleaved samples of the {@link VoiceProcessorOptions#renderChannels()}
     * channels, in {@code [-1, 1]}; not modified
     * @param offset the index of the first sample of the frame
     * @throws IndexOutOfBoundsException if {@code frame} holds fewer than {@link #frameSamples()}
     * samples per render channel from {@code offset}
     */
    public void processRender(float[] frame, int offset) {
        Objects.checkFromIndexSize(offset, frameSamples * renderChannels, frame.length);
        if (!rendersNeeded) {
            return;
        }
        var input = streamChannel;
        for (var ch = 0; ch < renderProcessingChannels; ch++) {
            if (renderProcessingChannels == 1 && renderChannels > 1) {
                var oneByNumChannels = 1.f / renderChannels;
                for (var i = 0; i < frameSamples; i++) {
                    var base = offset + i * renderChannels;
                    var value = sanitize(frame[base]);
                    for (var j = 1; j < renderChannels; j++) {
                        value += sanitize(frame[base + j]);
                    }
                    input[i] = value * oneByNumChannels;
                }
            } else {
                for (var i = 0; i < frameSamples; i++) {
                    input[i] = sanitize(frame[offset + i * renderChannels + ch]);
                }
            }
            importChannel(input, renderInputResamplers, ch, render[ch]);
        }
        processRenderWork();
    }

    /**
     * Passes one 10 ms frame of 16-bit far-end samples to the echo canceller and the gain
     * controller.
     *
     * @param frame the interleaved samples of the {@link VoiceProcessorOptions#renderChannels()}
     * channels; not modified
     * @param offset the index of the first sample of the frame
     * @throws IndexOutOfBoundsException if {@code frame} holds fewer than {@link #frameSamples()}
     * samples per render channel from {@code offset}
     */
    public void processRender(short[] frame, int offset) {
        Objects.checkFromIndexSize(offset, frameSamples * renderChannels, frame.length);
        if (!rendersNeeded) {
            return;
        }
        var input = streamChannel;
        for (var ch = 0; ch < renderProcessingChannels; ch++) {
            if (renderProcessingChannels == 1 && renderChannels > 1) {
                var oneByNumChannels = 1.f / renderChannels;
                for (var i = 0; i < frameSamples; i++) {
                    var base = offset + i * renderChannels;
                    var value = frame[base] / FULL_SCALE;
                    for (var j = 1; j < renderChannels; j++) {
                        value += frame[base + j] / FULL_SCALE;
                    }
                    input[i] = value * oneByNumChannels;
                }
            } else {
                for (var i = 0; i < frameSamples; i++) {
                    input[i] = frame[offset + i * renderChannels + ch] / FULL_SCALE;
                }
            }
            importChannel(input, renderInputResamplers, ch, render[ch]);
        }
        processRenderWork();
    }

    /**
     * Conditions one 10 ms frame of the near-end signal into another array or in place.
     *
     * <p>The output does not depend on whether {@code output} aliases {@code input}.
     *
     * @param input the interleaved samples of the {@link VoiceProcessorOptions#captureChannels()}
     * channels, in {@code [-1, 1]}; not modified unless it aliases {@code output}
     * @param inputOffset the index of the first input sample
     * @param output receives the conditioned interleaved samples, in {@code [-1, 1]}; may be
     * {@code input}
     * @param outputOffset the index of the first output sample
     * @throws IllegalStateException if an analog gain controller needs the microphone volume and
     * none was reported through {@link #setAnalogLevel(int)}
     * @throws IndexOutOfBoundsException if either array holds fewer than {@link #frameSamples()}
     * samples per capture channel from its offset
     */
    public void processCapture(float[] input, int inputOffset, float[] output, int outputOffset) {
        checkAnalogLevel();
        Objects.checkFromIndexSize(inputOffset, frameSamples * captureChannels, input.length);
        Objects.checkFromIndexSize(outputOffset, frameSamples * captureChannels, output.length);
        var channel = streamChannel;
        for (var ch = 0; ch < captureChannels; ch++) {
            for (var i = 0; i < frameSamples; i++) {
                channel[i] = sanitize(input[inputOffset + i * captureChannels + ch]);
            }
            importChannel(channel, captureInputResamplers, ch, capture[ch]);
        }
        processCaptureWork();
        for (var ch = 0; ch < processingChannels; ch++) {
            var out = exportChannel(ch);
            for (var i = 0; i < frameSamples; i++) {
                output[outputOffset + i * captureChannels + ch] = out[i];
            }
        }
        copyFirstChannel(output, outputOffset);
    }

    /**
     * Conditions one 10 ms frame of 16-bit near-end samples.
     *
     * @param input the interleaved samples of the {@link VoiceProcessorOptions#captureChannels()}
     * channels; not modified unless it aliases {@code output}
     * @param inputOffset the index of the first input sample
     * @param output receives the conditioned interleaved samples; may be {@code input}
     * @param outputOffset the index of the first output sample
     * @throws IllegalStateException if an analog gain controller needs the microphone volume and
     * none was reported through {@link #setAnalogLevel(int)}
     * @throws IndexOutOfBoundsException if either array holds fewer than {@link #frameSamples()}
     * samples per capture channel from its offset
     */
    public void processCapture(short[] input, int inputOffset, short[] output, int outputOffset) {
        checkAnalogLevel();
        Objects.checkFromIndexSize(inputOffset, frameSamples * captureChannels, input.length);
        Objects.checkFromIndexSize(outputOffset, frameSamples * captureChannels, output.length);
        var channel = streamChannel;
        for (var ch = 0; ch < captureChannels; ch++) {
            for (var i = 0; i < frameSamples; i++) {
                channel[i] = input[inputOffset + i * captureChannels + ch] / FULL_SCALE;
            }
            importChannel(channel, captureInputResamplers, ch, capture[ch]);
        }
        processCaptureWork();
        for (var ch = 0; ch < processingChannels; ch++) {
            var out = exportChannel(ch);
            for (var i = 0; i < frameSamples; i++) {
                var rounded = Math.round(out[i] * FULL_SCALE);
                output[outputOffset + i * captureChannels + ch] = (short) Math.clamp(rounded, Short.MIN_VALUE,
                        Short.MAX_VALUE);
            }
        }
        for (var ch = processingChannels; ch < captureChannels; ch++) {
            for (var i = 0; i < frameSamples; i++) {
                output[outputOffset + i * captureChannels + ch] = output[outputOffset + i * captureChannels];
            }
        }
    }

    /**
     * Repeats the first output channel on the channels the processing stages did not condition.
     *
     * @param output the interleaved output
     * @param outputOffset the index of the first output sample
     */
    private void copyFirstChannel(float[] output, int outputOffset) {
        for (var ch = processingChannels; ch < captureChannels; ch++) {
            for (var i = 0; i < frameSamples; i++) {
                output[outputOffset + i * captureChannels + ch] = output[outputOffset + i * captureChannels];
            }
        }
    }

    /**
     * Brings one stream channel to the processing rate and the 16-bit range ({@code
     * AudioBuffer::CopyFrom}).
     *
     * @param channel the samples at the stream rate, in {@code [-1, 1]} before clamping
     * @param resamplers the resamplers of the signal, or {@code null}
     * @param index the channel
     * @param destination receives the samples at the processing rate
     */
    private void importChannel(float[] channel, PushSincResampler[] resamplers, int index, float[] destination) {
        if (resamplers != null) {
            resamplers[index].resample(channel, 0, destination, 0);
            for (var i = 0; i < processingSamples; i++) {
                destination[i] = toFloatS16(destination[i]);
            }
        } else {
            for (var i = 0; i < processingSamples; i++) {
                destination[i] = toFloatS16(channel[i]);
            }
        }
        if (flushTiny) {
            flush(destination, processingSamples);
        }
    }

    /**
     * Brings one processed capture channel back to the stream rate and to {@code [-1, 1]}
     * ({@code AudioBuffer::CopyTo}).
     *
     * @param ch the channel
     * @return the samples at the stream rate, valid until the next call
     */
    private float[] exportChannel(int ch) {
        var data = capture[ch];
        if (captureOutputResamplers != null) {
            for (var i = 0; i < processingSamples; i++) {
                data[i] = toFloat(data[i]);
            }
            captureOutputResamplers[ch].resample(data, 0, streamOutput, 0);
        } else {
            for (var i = 0; i < processingSamples; i++) {
                streamOutput[i] = toFloat(data[i]);
            }
        }
        return streamOutput;
    }

    /**
     * Reports the delay between a render frame and its echo in the capture signal.
     *
     * <p>The delay takes effect at the next capture frame. The standard echo canceller uses it as
     * the starting point of its own delay estimate; the mobile echo canceller aligns the render
     * signal with it until the next report. Delays are clamped to {@code [0, 500]}.
     *
     * @param delayMs the delay in milliseconds
     */
    public void setStreamDelayMs(int delayMs) {
        streamDelayMs = Math.clamp(delayMs, 0, MAX_STREAM_DELAY_MS);
        streamDelaySet = true;
    }

    /**
     * Reports the microphone volume in effect for the next capture frames, on the scale
     * {@code [0, 255]}.
     *
     * <p>The analog gain controllers ({@link GainControl#ADAPTIVE_ANALOG},
     * {@link GainControl#MANAGED_ANALOG}) and the input volume controller of the level controller
     * adapt from this volume and keep using it until the next report; they need one report before
     * the first capture frame. A report that changes the volume also tells the standard echo
     * canceller and the level controller that the echo path and the speech level changed. The
     * other configurations ignore the volume. An application that changes the device volume as
     * {@link #recommendedAnalogLevel()} suggests reports the volume before every capture frame.
     *
     * @param level the microphone volume
     * @throws IllegalArgumentException if {@code level} is outside {@code [0, 255]}
     */
    public void setAnalogLevel(int level) {
        if (level < 0 || level > MAX_ANALOG_LEVEL) {
            throw new IllegalArgumentException("level must be in [0, 255]: " + level);
        }
        analogLevelChanged = analogLevel >= 0 && analogLevel != level;
        analogLevel = level;
        recommendedLevel = -1;
        if (analogGainManager != null) {
            analogGainManager.setStreamAnalogLevel(level);
        } else if (gainController != null) {
            gainController.setStreamAnalogLevel(level);
        }
    }

    /**
     * Returns the microphone volume to apply, on the scale {@code [0, 255]}.
     *
     * <p>After a capture frame, an analog gain controller or the input volume controller returns
     * the volume the application should apply to the device. Otherwise, and between a report and
     * the next capture frame, it is the volume last reported through {@link #setAnalogLevel(int)},
     * or {@code 255} before the first report.
     *
     * @return the recommended microphone volume
     */
    public int recommendedAnalogLevel() {
        if (recommendedLevel >= 0) {
            return recommendedLevel;
        }
        return analogLevel >= 0 ? analogLevel : MAX_ANALOG_LEVEL;
    }

    /**
     * Rejects a capture frame of an analog gain controller before the first microphone volume
     * report.
     *
     * @throws IllegalStateException if the volume is needed and was never reported
     */
    private void checkAnalogLevel() {
        if (analogLevel < 0 && needsAnalogLevel()) {
            throw new IllegalStateException("the analog gain controller needs a microphone level: "
                    + "call setAnalogLevel before the first capture frame");
        }
    }

    /**
     * {@return whether a stage needs the microphone volume}
     */
    private boolean needsAnalogLevel() {
        var gainControl = options.gainControl();
        return gainControl == GainControl.ADAPTIVE_ANALOG || gainControl == GainControl.MANAGED_ANALOG
                || options.levelControl().map(LevelControlOptions::inputVolumeController).orElse(false);
    }

    /**
     * Runs the render path on {@link #render}.
     */
    private void processRenderWork() {
        if (renderSplitter != null) {
            for (var ch = 0; ch < renderProcessingChannels; ch++) {
                renderSplitter.analysis(ch, render[ch], renderBands[ch]);
            }
        }
        if (mobileEchoCancellers != null) {
            var mobileFrame = this.mobileFrame;
            for (var i = 0; i < processingChannels; i++) {
                for (var j = 0; j < renderProcessingChannels; j++) {
                    var band = renderBands[j][0];
                    for (var k = 0; k < BAND_SAMPLES; k++) {
                        mobileFrame[k] = FixedPointMath.floatS16ToS16(band[k]);
                    }
                    mobileEchoCancellers[i * renderProcessingChannels + j].bufferFarend(mobileFrame, 0);
                }
            }
        }
        if (gainController != null && analogGainManager == null) {
            gainController.processRender(renderLowBands, renderProcessingChannels);
        }
        if (echoCanceller != null) {
            echoCanceller.analyzeRender(renderBands);
        }
    }

    /**
     * Runs the capture path on {@link #capture}.
     */
    private void processCaptureWork() {
        if (highPassFilters != null) {
            for (var ch = 0; ch < captureChannels; ch++) {
                highPassFilters[ch].process(capture[ch], 0, processingSamples);
                if (flushTiny) {
                    highPassFilters[ch].flushStates(FLUSH_FLOOR);
                    flush(capture[ch], processingSamples);
                }
            }
        }
        if (echoCanceller != null) {
            echoCanceller.analyzeCapture(capture, captureChannels, processingSamples);
        }
        if (analogGainManager != null) {
            analogGainManager.analyzePreProcess(capture, processingSamples);
        }
        if (inputVolumeControlled && analogLevel >= 0) {
            levelController.analyze(analogLevel, capture, captureChannels);
        }
        if (bandsNeeded && captureSplitter != null) {
            for (var ch = 0; ch < captureChannels; ch++) {
                captureSplitter.analysis(ch, capture[ch], captureBands[ch]);
            }
        }
        if (gainController != null) {
            gainController.analyzeCapture(captureBands);
        }
        if (noiseSuppressor != null) {
            noiseSuppressor.analyze(captureBands);
        }
        if (mobileEchoCancellers != null) {
            if (noiseSuppressor != null) {
                noiseSuppressor.process(captureBands);
            }
            var mobileFrame = this.mobileFrame;
            var handle = 0;
            for (var ch = 0; ch < processingChannels; ch++) {
                var band = captureBands[ch][0];
                for (var i = 0; i < BAND_SAMPLES; i++) {
                    mobileFrame[i] = FixedPointMath.floatS16ToS16(band[i]);
                }
                for (var r = 0; r < renderProcessingChannels; r++) {
                    mobileEchoCancellers[handle++].process(mobileFrame, 0, null, 0, mobileFrame, 0, streamDelayMs);
                    for (var i = 0; i < BAND_SAMPLES; i++) {
                        band[i] = mobileFrame[i];
                    }
                }
                for (var b = 1; b < numBands; b++) {
                    java.util.Arrays.fill(captureBands[ch][b], 0.f);
                }
            }
        } else {
            if (echoCanceller != null) {
                if (streamDelaySet) {
                    echoCanceller.setAudioBufferDelay(streamDelayMs);
                }
                echoCanceller.processCapture(captureBands, analogLevelChanged);
            }
            if (noiseSuppressor != null) {
                noiseSuppressor.process(captureBands);
            }
        }
        if (analogGainManager != null) {
            analogGainManager.process(captureBands);
            var compressionGainDb = analogGainManager.digitalCompressionGain();
            if (compressionGainDb >= 0) {
                gainController.setCompressionGainDb(compressionGainDb);
            }
        }
        if (gainController != null) {
            gainController.processCapture(captureBands);
        }
        if (bandsNeeded && captureSplitter != null) {
            for (var ch = 0; ch < processingChannels; ch++) {
                captureSplitter.synthesis(ch, captureBands[ch], capture[ch]);
            }
        }
        if (levelController != null) {
            levelController.process(capture, processingChannels, analogLevelChanged);
        }
        if (analogLevel >= 0) {
            if (analogGainManager != null) {
                recommendedLevel = analogGainManager.recommendedAnalogLevel();
            } else if (gainController != null) {
                recommendedLevel = gainController.streamAnalogLevel();
            } else if (inputVolumeControlled) {
                recommendedLevel = levelController.recommendedInputVolume();
            } else {
                recommendedLevel = analogLevel;
            }
        }
        streamDelaySet = false;
    }

    /**
     * Zeroes the samples whose magnitude is below {@link #FLUSH_FLOOR}.
     *
     * @param samples the samples to flush in place
     * @param length the number of samples from index 0
     */
    private static void flush(float[] samples, int length) {
        for (var i = 0; i < length; i++) {
            if (Math.abs(samples[i]) < FLUSH_FLOOR) {
                samples[i] = 0.f;
            }
        }
    }

    /**
     * Reads NaN as zero.
     *
     * @param v the sample
     * @return {@code v}, or zero when it is NaN
     */
    private static float sanitize(float v) {
        return v != v ? 0.f : v;
    }

    /**
     * Converts a sample in {@code [-1, 1]} to the 16-bit range, clamping ({@code FloatToFloatS16}).
     *
     * @param v the sample
     * @return {@code v * 32768} after clamping {@code v} to {@code [-1, 1]}
     */
    private static float toFloatS16(float v) {
        v = 1.f < v ? 1.f : v;
        v = v < -1.f ? -1.f : v;
        return v * FULL_SCALE;
    }

    /**
     * Converts a sample in the 16-bit range to {@code [-1, 1]}, clamping ({@code FloatS16ToFloat}).
     *
     * @param v the sample
     * @return {@code v / 32768} after clamping {@code v} to {@code [-32768, 32768]}
     */
    private static float toFloat(float v) {
        v = v > 32768.f ? 32768.f : v;
        v = v < -32768.f ? -32768.f : v;
        return v * (1.f / FULL_SCALE);
    }
}
