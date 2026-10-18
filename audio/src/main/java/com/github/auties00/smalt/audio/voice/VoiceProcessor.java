/* Ports webrtc-audio-processing 2.1 (modules/audio_processing/audio_processing_impl.cc, high_pass_filter.cc,
   echo_control_mobile_impl.cc). Copyright (c) 2011, The WebRTC project authors; BSD 3-Clause License. Modified for
   Smalt. */
package com.github.auties00.smalt.audio.voice;

import com.github.auties00.smalt.audio.voice.internal.CascadedBiQuadFilter;
import com.github.auties00.smalt.audio.voice.internal.FixedPointMath;
import com.github.auties00.smalt.audio.voice.internal.aec3.EchoCanceller3;
import com.github.auties00.smalt.audio.voice.internal.aecm.MobileEchoCanceller;
import com.github.auties00.smalt.audio.voice.internal.agc.GainController;
import com.github.auties00.smalt.audio.voice.internal.ns.NoiseSuppressor;
import com.github.auties00.smalt.internal.simd.Simd;

import java.util.Objects;

/**
 * Conditions the microphone signal of a voice call: cancels the acoustic echo of the far-end
 * signal played on the local speaker, suppresses stationary background noise and controls the
 * speech level.
 *
 * <p>Audio is processed in 10 ms frames ({@link #frameSamples()} samples) of mono audio at
 * 16 kHz. The far-end ("render") signal, the audio the local loudspeaker plays, is
 * passed to {@link #processRender(float[], int)}; the near-end ("capture") signal, the
 * microphone audio, to {@link #processCapture(float[], int, float[], int)}, which conditions it
 * into another array or in place.
 * Calls alternate: for every 10 ms of time, one render frame then one capture frame. Render
 * frames are queued and consumed by the next capture frame, so short bursts of either kind are
 * tolerated; the standard echo canceller holds up to one second of render frames not yet consumed
 * and drops the frames beyond it. Render frames received before the first capture frame are
 * discarded.
 *
 * <p>Samples are floats in {@code [-1, 1]} (values beyond are clamped, NaN is read as zero), or
 * 16-bit integers through the {@code short[]} overloads, which give exactly the result of
 * converting {@code s / 32768f} and back with rounding. The capture path applies, in order, a
 * 100 Hz high-pass filter (whenever echo cancellation or noise suppression is enabled), the
 * analysis of the gain controller, the echo canceller and the noise suppressor (with the mobile
 * echo canceller, the noise suppressor runs first), and the gain controller. The standard echo
 * canceller delays the capture signal by 4 ms and the noise suppressor by 6 ms.
 *
 * <p>The standard echo canceller estimates the delay between the render and the capture signal
 * itself (up to about 600 ms); {@link #setStreamDelayMs(int)} reports an externally measured
 * delay, which it uses as the starting point. The mobile echo canceller depends on that report,
 * which stays in effect until the next one. With the adaptive analog gain controller,
 * {@link #setAnalogLevel(int)} reports the microphone volume, at least once before the first
 * capture frame, and {@link #recommendedAnalogLevel()} returns the volume to apply.
 *
 * <p>Instances are not thread-safe: render and capture calls must not overlap.
 *
 * @implNote This implementation follows WebRTC's {@code AudioProcessingImpl} for one 16 kHz
 * channel, with AEC3 in its default configuration and the gain controller 1 without its analog
 * controller, in the order of {@code ProcessCaptureStreamLocked}. It flushes tiny values to zero
 * (see the note in the class body) and, with the Vector API, sums the delay estimator of the
 * standard echo canceller in another order, so its output matches the reference in quality, not
 * bit for bit; without both it reproduces the reference built for WebAssembly bit for bit.
 */
public final class VoiceProcessor {
    /*
     * The render frames of the mobile echo canceller and the gain controller are handed over at once
     * instead of through the reference's 100-frame queues, which the next capture frame empties in the
     * same order, so the result is the same. Like the reference, the components start fresh at the
     * first capture frame, which is why earlier render frames are dropped.
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
     * The highest microphone level of the analog gain controller.
     */
    private static final int MAX_ANALOG_LEVEL = 255;

    /**
     * The options.
     */
    private final VoiceProcessorOptions options;

    /**
     * The number of samples of one 10 ms frame.
     */
    private final int frameSamples;

    /**
     * The high-pass filter of the capture signal, or {@code null} when disabled.
     */
    private final CascadedBiQuadFilter highPassFilter;

    /**
     * The standard echo canceller, or {@code null} when disabled.
     */
    private final EchoCanceller3 echoCanceller;

    /**
     * The mobile echo canceller, or {@code null} when disabled.
     */
    private final MobileEchoCanceller mobileEchoCanceller;

    /**
     * The noise suppressor, or {@code null} when disabled.
     */
    private final NoiseSuppressor noiseSuppressor;

    /**
     * The gain controller, or {@code null} when disabled.
     */
    private final GainController gainController;

    /**
     * The working frame in the 16-bit range.
     */
    private final float[] work;

    /**
     * The 16-bit frame the mobile echo canceller works on, or {@code null} when it is disabled.
     */
    private final short[] mobileFrame;

    /**
     * Whether tiny input samples and filter states are zeroed.
     */
    private final boolean flushTiny;

    /**
     * Whether a capture frame has been processed.
     */
    private boolean captureStarted;

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
        this.frameSamples = options.sampleRate() / 100;
        var noiseSuppression = options.noiseSuppression() != NoiseSuppression.OFF;
        var echoCancellation = options.echoCancellation();
        this.highPassFilter = echoCancellation != EchoCancellation.OFF || noiseSuppression
                ? new CascadedBiQuadFilter(0.97261f, -1.94523f, 0.97261f, -1.94448f, 0.94598f, 1)
                : null;
        this.echoCanceller = echoCancellation == EchoCancellation.STANDARD
                ? new EchoCanceller3(vectorize, flushTiny)
                : null;
        this.mobileEchoCanceller = echoCancellation == EchoCancellation.MOBILE
                ? new MobileEchoCanceller(options.mobileEchoRoute().echoMode(), options.comfortNoise())
                : null;
        this.mobileFrame = mobileEchoCanceller != null ? new short[frameSamples] : null;
        this.noiseSuppressor = noiseSuppression ? new NoiseSuppressor(options.noiseSuppression().level()) : null;
        this.gainController = options.gainControl() == GainControl.OFF ? null
                : new GainController(options.gainControl(), options.targetLevelDbfs(), options.compressionGainDb(),
                options.limiter());
        this.work = new float[frameSamples];
        this.analogLevel = -1;
    }

    /**
     * {@return the options this processor was created with}
     */
    public VoiceProcessorOptions options() {
        return options;
    }

    /**
     * Returns the number of samples of one 10 ms frame.
     *
     * @return {@code sampleRate / 100}, 160 at 16 kHz
     */
    public int frameSamples() {
        return frameSamples;
    }

    /**
     * Passes one 10 ms frame of the far-end signal to the echo canceller and the gain controller.
     *
     * @param frame the samples in {@code [-1, 1]}; not modified
     * @param offset the index of the first sample of the frame
     * @throws IndexOutOfBoundsException if {@code frame} holds fewer than {@link #frameSamples()}
     * samples from {@code offset}
     */
    public void processRender(float[] frame, int offset) {
        Objects.checkFromIndexSize(offset, frameSamples, frame.length);
        if (!rendersNeeded()) {
            return;
        }
        var work = this.work;
        for (var i = 0; i < frameSamples; i++) {
            work[i] = toFloatS16(frame[offset + i]);
        }
        if (flushTiny) {
            flush(work, frameSamples);
        }
        processRenderWork();
    }

    /**
     * Passes one 10 ms frame of 16-bit far-end samples to the echo canceller and the gain
     * controller.
     *
     * @param frame the samples; not modified
     * @param offset the index of the first sample of the frame
     * @throws IndexOutOfBoundsException if {@code frame} holds fewer than {@link #frameSamples()}
     * samples from {@code offset}
     */
    public void processRender(short[] frame, int offset) {
        Objects.checkFromIndexSize(offset, frameSamples, frame.length);
        if (!rendersNeeded()) {
            return;
        }
        var work = this.work;
        for (var i = 0; i < frameSamples; i++) {
            work[i] = frame[offset + i];
        }
        processRenderWork();
    }

    /**
     * Conditions one 10 ms frame of the near-end signal into another array or in place.
     *
     * <p>The output does not depend on whether {@code output} aliases {@code input}.
     *
     * @param input the samples in {@code [-1, 1]}; not modified unless it aliases {@code output}
     * @param inputOffset the index of the first input sample
     * @param output receives the conditioned samples, in {@code [-1, 1]}; may be {@code input}
     * @param outputOffset the index of the first output sample
     * @throws IllegalStateException if the gain control is {@link GainControl#ADAPTIVE_ANALOG} and no
     * microphone volume was reported through {@link #setAnalogLevel(int)}
     * @throws IndexOutOfBoundsException if either array holds fewer than {@link #frameSamples()}
     * samples from its offset
     */
    public void processCapture(float[] input, int inputOffset, float[] output, int outputOffset) {
        checkAnalogLevel();
        Objects.checkFromIndexSize(inputOffset, frameSamples, input.length);
        Objects.checkFromIndexSize(outputOffset, frameSamples, output.length);
        var work = this.work;
        for (var i = 0; i < frameSamples; i++) {
            work[i] = toFloatS16(input[inputOffset + i]);
        }
        if (flushTiny) {
            flush(work, frameSamples);
        }
        processCaptureWork();
        for (var i = 0; i < frameSamples; i++) {
            var v = work[i];
            v = v > 32768.f ? 32768.f : v;
            v = v < -32768.f ? -32768.f : v;
            output[outputOffset + i] = v * (1.f / FULL_SCALE);
        }
    }

    /**
     * Conditions one 10 ms frame of 16-bit near-end samples.
     *
     * @param input the samples; not modified unless it aliases {@code output}
     * @param inputOffset the index of the first input sample
     * @param output receives the conditioned samples; may be {@code input}
     * @param outputOffset the index of the first output sample
     * @throws IllegalStateException if the gain control is {@link GainControl#ADAPTIVE_ANALOG} and no
     * microphone volume was reported through {@link #setAnalogLevel(int)}
     * @throws IndexOutOfBoundsException if either array holds fewer than {@link #frameSamples()}
     * samples from its offset
     */
    public void processCapture(short[] input, int inputOffset, short[] output, int outputOffset) {
        checkAnalogLevel();
        Objects.checkFromIndexSize(inputOffset, frameSamples, input.length);
        Objects.checkFromIndexSize(outputOffset, frameSamples, output.length);
        var work = this.work;
        for (var i = 0; i < frameSamples; i++) {
            work[i] = input[inputOffset + i];
        }
        processCaptureWork();
        for (var i = 0; i < frameSamples; i++) {
            var rounded = Math.round(work[i]);
            output[outputOffset + i] = (short) Math.clamp(rounded, Short.MIN_VALUE, Short.MAX_VALUE);
        }
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
     * <p>The adaptive analog gain controller ({@link GainControl#ADAPTIVE_ANALOG}) adapts from
     * this volume and keeps using it until the next report; it needs one report before the first
     * capture frame. The other modes ignore the volume, as does a processor without gain control.
     * An application that changes the device volume as {@link #recommendedAnalogLevel()} suggests
     * reports the volume before every capture frame.
     *
     * @param level the microphone volume
     * @throws IllegalArgumentException if {@code level} is outside {@code [0, 255]}
     */
    public void setAnalogLevel(int level) {
        if (level < 0 || level > MAX_ANALOG_LEVEL) {
            throw new IllegalArgumentException("level must be in [0, 255]: " + level);
        }
        analogLevel = level;
        if (gainController != null) {
            gainController.setStreamAnalogLevel(level);
        }
    }

    /**
     * Returns the microphone volume the gain controller recommends, on the scale
     * {@code [0, 255]}.
     *
     * <p>With the adaptive analog gain controller this is the volume the application should apply
     * to the device after the last capture frame. Otherwise it is the last volume reported through
     * {@link #setAnalogLevel(int)}. Before the first report it is {@code 255} in every mode.
     *
     * @return the recommended microphone volume
     */
    public int recommendedAnalogLevel() {
        if (analogLevel < 0) {
            return MAX_ANALOG_LEVEL;
        }
        if (gainController != null && gainController.mode() == GainControl.ADAPTIVE_ANALOG) {
            return gainController.streamAnalogLevel();
        }
        return analogLevel;
    }

    /**
     * Rejects a capture frame of the adaptive analog gain controller before the first microphone
     * volume report.
     *
     * @throws IllegalStateException if the volume is needed and was never reported
     */
    private void checkAnalogLevel() {
        if (analogLevel < 0 && gainController != null && gainController.mode() == GainControl.ADAPTIVE_ANALOG) {
            throw new IllegalStateException("the adaptive analog gain controller needs a microphone level: "
                    + "call setAnalogLevel before the first capture frame");
        }
    }

    /**
     * Returns whether any stage consumes render frames that are accepted now.
     *
     * @return whether render frames are processed
     */
    private boolean rendersNeeded() {
        return captureStarted && (echoCanceller != null || mobileEchoCanceller != null || gainController != null);
    }

    /**
     * Runs the render path on {@link #work}.
     */
    private void processRenderWork() {
        var work = this.work;
        if (mobileEchoCanceller != null) {
            var mobileFrame = this.mobileFrame;
            for (var i = 0; i < frameSamples; i++) {
                mobileFrame[i] = FixedPointMath.floatS16ToS16(work[i]);
            }
            mobileEchoCanceller.bufferFarend(mobileFrame, 0);
        }
        if (gainController != null) {
            gainController.processRender(work);
        }
        if (echoCanceller != null) {
            echoCanceller.analyzeRender(work, 0);
        }
    }

    /**
     * Runs the capture path on {@link #work}.
     */
    private void processCaptureWork() {
        captureStarted = true;
        var work = this.work;
        if (highPassFilter != null) {
            highPassFilter.process(work, 0, frameSamples);
            if (flushTiny) {
                highPassFilter.flushStates(FLUSH_FLOOR);
                flush(work, frameSamples);
            }
        }
        if (echoCanceller != null) {
            echoCanceller.analyzeCapture(work, 0);
        }
        if (gainController != null) {
            gainController.analyzeCapture(work);
        }
        if (noiseSuppressor != null) {
            noiseSuppressor.analyze(work, 0);
        }
        if (mobileEchoCanceller != null) {
            if (noiseSuppressor != null) {
                noiseSuppressor.process(work, 0);
            }
            var mobileFrame = this.mobileFrame;
            for (var i = 0; i < frameSamples; i++) {
                mobileFrame[i] = FixedPointMath.floatS16ToS16(work[i]);
            }
            mobileEchoCanceller.process(mobileFrame, 0, null, 0, mobileFrame, 0, streamDelayMs);
            for (var i = 0; i < frameSamples; i++) {
                work[i] = mobileFrame[i];
            }
        } else {
            if (echoCanceller != null) {
                if (streamDelaySet) {
                    echoCanceller.setAudioBufferDelay(streamDelayMs);
                }
                echoCanceller.processCapture(work, 0, false);
            }
            if (noiseSuppressor != null) {
                noiseSuppressor.process(work, 0);
            }
        }
        if (gainController != null) {
            gainController.processCapture(work);
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
     * Converts a sample in {@code [-1, 1]} to the 16-bit range, clamping and reading NaN as zero.
     *
     * @param v the sample
     * @return {@code v * 32768} after clamping {@code v} to {@code [-1, 1]}
     */
    private static float toFloatS16(float v) {
        if (v != v) {
            return 0.f;
        }
        v = 1.f < v ? 1.f : v;
        v = v < -1.f ? -1.f : v;
        return v * FULL_SCALE;
    }
}
