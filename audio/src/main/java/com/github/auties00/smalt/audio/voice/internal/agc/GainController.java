/* Ports webrtc-audio-processing 2.1 (modules/audio_processing/gain_control_impl.cc). Copyright (c) 2011, The WebRTC
   project authors; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.voice.internal.agc;

import com.github.auties00.smalt.audio.voice.GainControl;
import com.github.auties00.smalt.audio.voice.internal.FixedPointMath;

/**
 * Applies WebRTC's legacy automatic gain control to one 16 kHz mono channel the way the audio
 * processing module drives it: the gains are computed in fixed point on a 16-bit copy of each
 * frame and applied to the floating-point frame.
 *
 * <p>Samples are floats in the 16-bit range. Each capture frame goes through
 * {@link #analyzeCapture(float[])} before the echo canceller and the noise suppressor and
 * through {@link #processCapture(float[])} after them; render frames go to
 * {@link #processRender(float[])}, which feeds the far-end speech detector. In the adaptive
 * analog mode the caller reports the microphone level through
 * {@link #setStreamAnalogLevel(int)}, which holds until the next report, and reads the
 * recommended level back from {@link #streamAnalogLevel()}.
 *
 * @implNote This implementation follows {@code GainControlImpl} for one channel with the analog
 * level limits {@code [0, 255]}, as {@code AudioProcessingImpl} configures it without the analog
 * controller ({@code AgcManagerDirect}). Where the reference rejects an adaptive analog frame whose
 * level was not reported since the previous frame, this implementation keeps the last reported or
 * recommended level, which gives the same output whenever the caller reports every frame's level.
 */
public final class GainController {
    /**
     * The number of samples of a frame.
     */
    private static final int FRAME = 160;

    /**
     * The scale of the Q16 gains.
     */
    private static final float GAIN_SCALING = 1.f / 65536.f;

    /**
     * The number of samples over which one pair of gains is interpolated.
     */
    private static final int NUM_SUB_SECTIONS = 16;

    /**
     * The interpolation step of one sample.
     */
    private static final float ONE_BY_NUM_SUB_SECTIONS = 1.f / NUM_SUB_SECTIONS;

    /**
     * The lowest microphone level.
     */
    private static final int MINIMUM_CAPTURE_LEVEL = 0;

    /**
     * The highest microphone level.
     */
    private static final int MAXIMUM_CAPTURE_LEVEL = 255;

    /**
     * The mode, never {@link GainControl#OFF}.
     */
    private final GainControl mode;

    /**
     * The legacy controller.
     */
    private final LegacyAgc agc;

    /**
     * The gains of the last frame, in Q16.
     */
    private final int[] gains;

    /**
     * The gains of the last frame, scaled to linear factors.
     */
    private final float[] gainsScaled;

    /**
     * The 16-bit copy of the frame the legacy controller works on.
     */
    private final short[] splitBand;

    /**
     * The microphone level the caller reported, or the recommendation of the last frame in the
     * adaptive analog mode.
     */
    private int analogCaptureLevel;

    /**
     * The microphone level carried from the analysis to the processing of a frame.
     */
    private int captureLevel;

    /**
     * Creates a controller.
     *
     * @param mode the gain control mode, other than {@link GainControl#OFF}
     * @param targetLevelDbfs the target peak level, in dB below full scale, in {@code [0, 31]}
     * @param compressionGainDb the compression gain, in dB, in {@code [0, 90]}
     * @param limiter whether the limiter is enabled
     * @throws IllegalArgumentException if {@code mode} is {@link GainControl#OFF} or the legacy
     * controller rejects the configuration
     */
    public GainController(GainControl mode, int targetLevelDbfs, int compressionGainDb, boolean limiter) {
        var legacyMode = switch (mode) {
            case OFF -> throw new IllegalArgumentException("gain control is off");
            case ADAPTIVE_ANALOG -> DigitalAgc.MODE_ADAPTIVE_ANALOG;
            case ADAPTIVE_DIGITAL -> DigitalAgc.MODE_ADAPTIVE_DIGITAL;
            case FIXED_DIGITAL -> DigitalAgc.MODE_FIXED_DIGITAL;
        };
        this.mode = mode;
        this.agc = new LegacyAgc();
        this.gains = new int[11];
        this.gainsScaled = new float[11];
        this.splitBand = new short[FRAME];
        if (agc.init(MINIMUM_CAPTURE_LEVEL, MAXIMUM_CAPTURE_LEVEL, legacyMode) != 0
                || agc.setConfig(targetLevelDbfs, compressionGainDb, limiter) != 0) {
            throw new IllegalArgumentException("invalid gain control configuration: target " + targetLevelDbfs
                    + " dBFS, compression " + compressionGainDb + " dB");
        }
        this.analogCaptureLevel = 0;
        this.captureLevel = 0;
    }

    /**
     * Feeds one render frame to the far-end speech detector ({@code ProcessRenderAudio} of the
     * packed render frame).
     *
     * @param render the 160 render samples in the 16-bit range; not modified
     */
    public void processRender(float[] render) {
        for (var i = 0; i < FRAME; i++) {
            splitBand[i] = FixedPointMath.floatS16ToS16(render[i]);
        }
        agc.addFarend(splitBand, 0);
    }

    /**
     * Collects the statistics of a capture frame before the other processing stages
     * ({@code AnalyzeCaptureAudio}).
     *
     * @param capture the 160 capture samples in the 16-bit range; not modified
     */
    public void analyzeCapture(float[] capture) {
        switch (mode) {
            case ADAPTIVE_ANALOG -> {
                captureLevel = analogCaptureLevel;
                export(capture);
                agc.addMic(splitBand, 0);
            }
            case ADAPTIVE_DIGITAL -> {
                export(capture);
                captureLevel = agc.virtualMic(splitBand, 0, analogCaptureLevel);
            }
            case FIXED_DIGITAL, OFF -> {
            }
        }
    }

    /**
     * Computes the gains of a capture frame after the other processing stages and applies them
     * in place ({@code ProcessCaptureAudio}).
     *
     * @param capture the 160 capture samples in the 16-bit range, modified in place
     */
    public void processCapture(float[] capture) {
        export(capture);
        captureLevel = agc.analyze(splitBand, 0, captureLevel, false, gains);
        applyDigitalGain(capture);
        if (mode == GainControl.ADAPTIVE_ANALOG) {
            analogCaptureLevel = captureLevel;
        }
    }

    /**
     * Reports the microphone level applied to the next capture frame
     * ({@code set_stream_analog_level}).
     *
     * @param level the level, in {@code [0, 255]}
     * @throws IllegalArgumentException if {@code level} is outside {@code [0, 255]}
     */
    public void setStreamAnalogLevel(int level) {
        if (level < MINIMUM_CAPTURE_LEVEL || level > MAXIMUM_CAPTURE_LEVEL) {
            throw new IllegalArgumentException("level must be in [0, 255]: " + level);
        }
        analogCaptureLevel = level;
    }

    /**
     * Returns the microphone level the controller recommends ({@code stream_analog_level}).
     *
     * @return the recommended level in the adaptive analog mode, otherwise the last reported one
     */
    public int streamAnalogLevel() {
        return analogCaptureLevel;
    }

    /**
     * {@return the gain control mode, never {@link GainControl#OFF}}
     */
    public GainControl mode() {
        return mode;
    }

    /**
     * Copies a frame into {@link #splitBand}, rounding to 16-bit integers.
     *
     * @param capture the frame in the 16-bit float range
     */
    private void export(float[] capture) {
        for (var i = 0; i < FRAME; i++) {
            splitBand[i] = FixedPointMath.floatS16ToS16(capture[i]);
        }
    }

    /**
     * Applies the gains of the last frame, interpolated linearly over each millisecond, and
     * clamps to the 16-bit range ({@code ApplyDigitalGain}).
     *
     * @param out the frame, modified in place
     */
    private void applyDigitalGain(float[] out) {
        for (var k = 0; k < 11; k++) {
            gainsScaled[k] = gains[k] * GAIN_SCALING;
        }
        for (int k = 0, sample = 0; k < 10; k++) {
            var delta = (gainsScaled[k + 1] - gainsScaled[k]) * ONE_BY_NUM_SUB_SECTIONS;
            var gain = gainsScaled[k];
            for (var n = 0; n < NUM_SUB_SECTIONS; n++, sample++) {
                var value = out[sample] * gain;
                out[sample] = Math.min(32767.f, Math.max(-32768.f, value));
                gain += delta;
            }
        }
    }
}
