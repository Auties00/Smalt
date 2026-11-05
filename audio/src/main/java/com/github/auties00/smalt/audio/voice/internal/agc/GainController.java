/* Ports webrtc-audio-processing 2.1 (modules/audio_processing/gain_control_impl.cc). Copyright (c) 2011, The WebRTC
   project authors; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.voice.internal.agc;

import com.github.auties00.smalt.audio.voice.GainControl;
import com.github.auties00.smalt.audio.voice.internal.FixedPointMath;

/**
 * Applies WebRTC's legacy automatic gain control to the bands of one or more channels the way the
 * audio processing module drives it: the gains are computed in fixed point on a 16-bit copy of
 * the lowest band of each channel and applied to every band of every channel in floating point.
 *
 * <p>Samples are floats in the 16-bit range, 160 per band. Each capture frame goes through
 * {@link #analyzeCapture(float[][][])} before the echo canceller and the noise suppressor and
 * through {@link #processCapture(float[][][])} after them; render frames go to
 * {@link #processRender(float[][], int)}, which feeds the far-end speech detector. In the adaptive
 * analog mode the caller reports the microphone level through {@link #setStreamAnalogLevel(int)},
 * which holds until the next report, and reads the recommended level back from
 * {@link #streamAnalogLevel()}. Every channel runs its own controller; the gains of the channel
 * with the highest final gain are applied to all of them, and the lowest recommended level wins.
 *
 * @implNote This implementation follows {@code GainControlImpl} with the analog level limits
 * {@code [0, 255]}, as {@code AudioProcessingImpl} configures it without the analog controller
 * ({@code AgcManagerDirect}), or as that controller sets it up. Where the reference rejects an
 * adaptive analog frame whose level was not reported since the previous frame, this
 * implementation keeps the last reported or recommended level, which gives the same output
 * whenever the caller reports every frame's level.
 */
public final class GainController {
    /**
     * The number of samples of a band.
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
     * The mode, never {@link GainControl#OFF} or {@link GainControl#MANAGED_ANALOG}.
     */
    private GainControl mode;

    /**
     * The number of bands.
     */
    private final int numBands;

    /**
     * The legacy controller of every channel.
     */
    private final LegacyAgc[] agcs;

    /**
     * The gains of the last frame of every channel, in Q16.
     */
    private final int[][] gains;

    /**
     * The gains of the applied channel, scaled to linear factors.
     */
    private final float[] gainsScaled;

    /**
     * The 16-bit copy of the band the legacy controller works on.
     */
    private final short[] splitBand;

    /**
     * The microphone level carried from the analysis to the processing of every channel.
     */
    private final int[] captureLevels;

    /**
     * The target level, in dB below full scale.
     */
    private int targetLevelDbfs;

    /**
     * The compression gain, in dB.
     */
    private int compressionGainDb;

    /**
     * Whether the limiter is enabled.
     */
    private boolean limiter;

    /**
     * The microphone level the caller reported, or the recommendation of the last frame in the
     * adaptive analog mode.
     */
    private int analogCaptureLevel;

    /**
     * Creates a controller.
     *
     * @param mode the gain control mode, other than {@link GainControl#OFF} and
     * {@link GainControl#MANAGED_ANALOG}
     * @param targetLevelDbfs the target peak level, in dB below full scale, in {@code [0, 31]}
     * @param compressionGainDb the compression gain, in dB, in {@code [0, 90]}
     * @param limiter whether the limiter is enabled
     * @param numChannels the number of channels
     * @param numBands the number of bands, 1 at 16 kHz, 2 at 32 kHz and 3 at 48 kHz
     * @throws IllegalArgumentException if {@code mode} is not supported or the legacy controller
     * rejects the configuration
     */
    public GainController(GainControl mode, int targetLevelDbfs, int compressionGainDb, boolean limiter,
                          int numChannels, int numBands) {
        this.numBands = numBands;
        this.agcs = new LegacyAgc[numChannels];
        this.gains = new int[numChannels][11];
        this.captureLevels = new int[numChannels];
        this.gainsScaled = new float[11];
        this.splitBand = new short[FRAME];
        this.targetLevelDbfs = targetLevelDbfs;
        this.compressionGainDb = compressionGainDb;
        this.limiter = limiter;
        this.analogCaptureLevel = 0;
        for (var ch = 0; ch < numChannels; ch++) {
            agcs[ch] = new LegacyAgc(numBands > 1);
        }
        setMode(mode);
    }

    /**
     * Sets the mode and reinitializes every channel's controller ({@code set_mode}).
     *
     * @param mode the gain control mode, other than {@link GainControl#OFF} and
     * {@link GainControl#MANAGED_ANALOG}
     * @throws IllegalArgumentException if {@code mode} is not supported or the legacy controller
     * rejects the configuration
     */
    public void setMode(GainControl mode) {
        var legacyMode = switch (mode) {
            case OFF, MANAGED_ANALOG -> throw new IllegalArgumentException("unsupported mode: " + mode);
            case ADAPTIVE_ANALOG -> DigitalAgc.MODE_ADAPTIVE_ANALOG;
            case ADAPTIVE_DIGITAL -> DigitalAgc.MODE_ADAPTIVE_DIGITAL;
            case FIXED_DIGITAL -> DigitalAgc.MODE_FIXED_DIGITAL;
        };
        this.mode = mode;
        for (var ch = 0; ch < agcs.length; ch++) {
            if (agcs[ch].init(MINIMUM_CAPTURE_LEVEL, MAXIMUM_CAPTURE_LEVEL, legacyMode) != 0) {
                throw new IllegalArgumentException("invalid gain control mode: " + mode);
            }
            captureLevels[ch] = analogCaptureLevel;
        }
        configure();
    }

    /**
     * Sets the target level ({@code set_target_level_dbfs}).
     *
     * @param targetLevelDbfs the target peak level, in dB below full scale, in {@code [0, 31]}
     */
    public void setTargetLevelDbfs(int targetLevelDbfs) {
        this.targetLevelDbfs = targetLevelDbfs;
        configure();
    }

    /**
     * Sets the compression gain ({@code set_compression_gain_db}).
     *
     * @param compressionGainDb the compression gain, in dB, in {@code [0, 90]}
     */
    public void setCompressionGainDb(int compressionGainDb) {
        this.compressionGainDb = compressionGainDb;
        configure();
    }

    /**
     * Enables or disables the limiter ({@code enable_limiter}).
     *
     * @param limiter whether the limiter is enabled
     */
    public void setLimiter(boolean limiter) {
        this.limiter = limiter;
        configure();
    }

    /**
     * Applies the target level, compression gain and limiter to every channel ({@code Configure}).
     *
     * @throws IllegalArgumentException if the legacy controller rejects the configuration
     */
    private void configure() {
        for (var agc : agcs) {
            if (agc.setConfig(targetLevelDbfs, compressionGainDb, limiter) != 0) {
                throw new IllegalArgumentException("invalid gain control configuration: target " + targetLevelDbfs
                        + " dBFS, compression " + compressionGainDb + " dB");
            }
        }
    }

    /**
     * Feeds one render frame to the far-end speech detector of every channel ({@code
     * ProcessRenderAudio} of the frame {@code PackRenderAudioBuffer} packs: the lowest band,
     * averaged over the render channels in 16-bit integers).
     *
     * @param render the lowest band of every render channel, 160 samples in the 16-bit range each;
     * not modified
     * @param renderChannels the number of render channels
     */
    public void processRender(float[][] render, int renderChannels) {
        if (renderChannels == 1) {
            var band = render[0];
            for (var i = 0; i < FRAME; i++) {
                splitBand[i] = FixedPointMath.floatS16ToS16(band[i]);
            }
        } else {
            for (var i = 0; i < FRAME; i++) {
                var sum = 0;
                for (var ch = 0; ch < renderChannels; ch++) {
                    sum += FixedPointMath.floatS16ToS16(render[ch][i]);
                }
                splitBand[i] = (short) (sum / renderChannels);
            }
        }
        for (var agc : agcs) {
            agc.addFarend(splitBand, 0);
        }
    }

    /**
     * Collects the statistics of a capture frame before the other processing stages
     * ({@code AnalyzeCaptureAudio}).
     *
     * @param capture the bands of every channel, {@code [channel][band]}, 160 samples in the
     * 16-bit range each; not modified
     */
    public void analyzeCapture(float[][][] capture) {
        switch (mode) {
            case ADAPTIVE_ANALOG -> {
                for (var ch = 0; ch < agcs.length; ch++) {
                    captureLevels[ch] = analogCaptureLevel;
                    export(capture[ch][0]);
                    agcs[ch].addMic(splitBand, 0);
                }
            }
            case ADAPTIVE_DIGITAL -> {
                for (var ch = 0; ch < agcs.length; ch++) {
                    export(capture[ch][0]);
                    captureLevels[ch] = agcs[ch].virtualMic(splitBand, 0, analogCaptureLevel);
                }
            }
            case FIXED_DIGITAL, OFF, MANAGED_ANALOG -> {
            }
        }
    }

    /**
     * Computes the gains of a capture frame after the other processing stages and applies them
     * in place ({@code ProcessCaptureAudio}).
     *
     * @param capture the bands of every channel, {@code [channel][band]}, 160 samples in the
     * 16-bit range each, modified in place
     */
    public void processCapture(float[][][] capture) {
        for (var ch = 0; ch < agcs.length; ch++) {
            export(capture[ch][0]);
            captureLevels[ch] = agcs[ch].analyze(splitBand, 0, captureLevels[ch], false, gains[ch]);
        }
        var indexToApply = 0;
        for (var ch = 1; ch < agcs.length; ch++) {
            if (gains[indexToApply][10] < gains[ch][10]) {
                indexToApply = ch;
            }
        }
        var applied = gains[indexToApply];
        for (var k = 0; k < 11; k++) {
            gainsScaled[k] = applied[k] * GAIN_SCALING;
        }
        for (var ch = 0; ch < agcs.length; ch++) {
            for (var b = 0; b < numBands; b++) {
                applyDigitalGain(capture[ch][b]);
            }
        }
        if (mode == GainControl.ADAPTIVE_ANALOG) {
            var level = captureLevels[0];
            for (var ch = 1; ch < agcs.length; ch++) {
                level = Math.min(level, captureLevels[ch]);
            }
            analogCaptureLevel = level;
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
     * {@return the gain control mode}
     */
    public GainControl mode() {
        return mode;
    }

    /**
     * Copies a band into {@link #splitBand}, rounding to 16-bit integers.
     *
     * @param band the band in the 16-bit float range
     */
    private void export(float[] band) {
        for (var i = 0; i < FRAME; i++) {
            splitBand[i] = FixedPointMath.floatS16ToS16(band[i]);
        }
    }

    /**
     * Applies the scaled gains, interpolated linearly over each millisecond, and clamps to the
     * 16-bit range ({@code ApplyDigitalGain}).
     *
     * @param out the band, modified in place
     */
    private void applyDigitalGain(float[] out) {
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
