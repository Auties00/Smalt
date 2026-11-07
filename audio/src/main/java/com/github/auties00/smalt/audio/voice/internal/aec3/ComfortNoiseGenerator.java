/* Ports webrtc-audio-processing 2.1 (modules/audio_processing/aec3/comfort_noise_generator.cc). Copyright (c) 2011,
   The WebRTC project authors; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.voice.internal.aec3;

import com.github.auties00.smalt.audio.voice.internal.FloatMath;

import java.util.Arrays;

import static com.github.auties00.smalt.audio.voice.internal.aec3.Aec3.FFT_LENGTH_BY_2;
import static com.github.auties00.smalt.audio.voice.internal.aec3.Aec3.FFT_LENGTH_BY_2_PLUS_1;

/**
 * Estimates the background noise spectrum of every capture channel and generates comfort noise of
 * that spectrum with random phases, to fill what the suppressor removes, for the lowest band and,
 * at the average level of the upper half of the lowest band, for the upper bands.
 *
 * @implNote This implementation ports WebRTC's
 * {@code modules/audio_processing/aec3/comfort_noise_generator.cc}; the phases come from the same
 * linear congruential generator seeded with 42, shared by the channels, so the noise is identical
 * to the reference's.
 */
final class ComfortNoiseGenerator {
    /**
     * {@code sqrt(2) * sin(2 pi i / 32)}, the quantized phase table.
     *
     * @implNote This implementation copies {@code kSqrt2Sin} of {@code comfort_noise_generator.cc}.
     */
    private static final float[] SQRT2_SIN = {
            +0.0000000f, +0.2758994f, +0.5411961f, +0.7856950f, +1.0000000f,
            +1.1758756f, +1.3065630f, +1.3870398f, +1.4142136f, +1.3870398f,
            +1.3065630f, +1.1758756f, +1.0000000f, +0.7856950f, +0.5411961f,
            +0.2758994f, +0.0000000f, -0.2758994f, -0.5411961f, -0.7856950f,
            -1.0000000f, -1.1758756f, -1.3065630f, -1.3870398f, -1.4142136f,
            -1.3870398f, -1.3065630f, -1.1758756f, -1.0000000f, -0.7856950f,
            -0.5411961f, -0.2758994f
    };

    /**
     * The lowest noise power per bin.
     */
    private final float noiseFloor = 64.f * FloatMath.powf(10.f, (90.30899869919436f + Aec3.NOISE_FLOOR_DBFS) * 0.1f);

    /**
     * The number of capture channels.
     */
    private final int numChannels;

    /**
     * The state of the phase generator.
     */
    private int seed = 42;

    /**
     * The initial noise estimate of every channel, used for the first 1000 blocks, then
     * {@code null}.
     */
    private float[][] n2Initial;

    /**
     * The smoothed capture power of every channel.
     */
    private final float[][] y2Smoothed;

    /**
     * The noise estimate of every channel.
     */
    private final float[][] n2;

    /**
     * The number of non-saturated blocks, up to 1000.
     */
    private int n2Counter;

    /**
     * The scratch noise magnitude spectrum.
     */
    private final float[] magnitude = new float[FFT_LENGTH_BY_2_PLUS_1];

    /**
     * Creates a generator.
     *
     * @param numChannels the number of capture channels
     */
    ComfortNoiseGenerator(int numChannels) {
        this.numChannels = numChannels;
        this.n2Initial = new float[numChannels][FFT_LENGTH_BY_2_PLUS_1];
        this.y2Smoothed = new float[numChannels][FFT_LENGTH_BY_2_PLUS_1];
        this.n2 = new float[numChannels][FFT_LENGTH_BY_2_PLUS_1];
        for (var ch = 0; ch < numChannels; ch++) {
            Arrays.fill(n2[ch], 1.0e6f);
        }
    }

    /**
     * Updates the noise estimates and generates the comfort noise of every channel.
     *
     * @param saturatedCapture whether the capture signal is saturated, which freezes the estimates
     * @param y2 the capture power spectra, per channel
     * @param lowerBandNoise receives the comfort noise spectra of the lowest band, per channel
     * @param upperBandNoise receives the comfort noise spectra of the upper bands, per channel
     */
    void compute(boolean saturatedCapture, float[][] y2, FftData[] lowerBandNoise, FftData[] upperBandNoise) {
        if (!saturatedCapture) {
            for (var ch = 0; ch < numChannels; ch++) {
                var smoothed = y2Smoothed[ch];
                var y2Ch = y2[ch];
                for (var k = 0; k < FFT_LENGTH_BY_2_PLUS_1; k++) {
                    var a = smoothed[k];
                    smoothed[k] = a + 0.1f * (y2Ch[k] - a);
                }
            }
            if (n2Counter > 50) {
                for (var ch = 0; ch < numChannels; ch++) {
                    var n2Ch = n2[ch];
                    var smoothed = y2Smoothed[ch];
                    for (var k = 0; k < FFT_LENGTH_BY_2_PLUS_1; k++) {
                        var a = n2Ch[k];
                        var b = smoothed[k];
                        n2Ch[k] = b < a ? (0.9f * b + 0.1f * a) * 1.0002f : a * 1.0002f;
                    }
                }
            }
            if (n2Initial != null) {
                if (++n2Counter == 1000) {
                    n2Initial = null;
                } else {
                    for (var ch = 0; ch < numChannels; ch++) {
                        var n2Ch = n2[ch];
                        var initial = n2Initial[ch];
                        for (var k = 0; k < FFT_LENGTH_BY_2_PLUS_1; k++) {
                            var a = n2Ch[k];
                            var b = initial[k];
                            initial[k] = a > b ? b + 0.001f * (a - b) : a;
                        }
                    }
                }
            }
            for (var ch = 0; ch < numChannels; ch++) {
                var n2Ch = n2[ch];
                for (var k = 0; k < FFT_LENGTH_BY_2_PLUS_1; k++) {
                    n2Ch[k] = FloatMath.max(n2Ch[k], noiseFloor);
                }
                if (n2Initial != null) {
                    var initial = n2Initial[ch];
                    for (var k = 0; k < FFT_LENGTH_BY_2_PLUS_1; k++) {
                        initial[k] = FloatMath.max(initial[k], noiseFloor);
                    }
                }
            }
        }
        for (var ch = 0; ch < numChannels; ch++) {
            generate(n2Initial != null ? n2Initial[ch] : n2[ch], lowerBandNoise[ch], upperBandNoise[ch]);
        }
    }

    /**
     * {@return the noise estimates, per channel}
     */
    float[][] noiseSpectrum() {
        return n2;
    }

    private void generate(float[] power, FftData low, FftData high) {
        for (var k = 0; k < FFT_LENGTH_BY_2_PLUS_1; k++) {
            magnitude[k] = (float) Math.sqrt(power[k]);
        }
        var oneByNumBands = 1.f / (FFT_LENGTH_BY_2_PLUS_1 / 2 + 1);
        var highBandNoiseLevel = 0.f;
        for (var k = FFT_LENGTH_BY_2_PLUS_1 / 2; k < FFT_LENGTH_BY_2_PLUS_1; k++) {
            highBandNoiseLevel = highBandNoiseLevel + magnitude[k];
        }
        highBandNoiseLevel = highBandNoiseLevel * oneByNumBands;
        low.re[0] = low.re[FFT_LENGTH_BY_2] = 0.f;
        low.im[0] = low.im[FFT_LENGTH_BY_2] = 0.f;
        high.re[0] = high.re[FFT_LENGTH_BY_2] = 0.f;
        high.im[0] = high.im[FFT_LENGTH_BY_2] = 0.f;
        for (var k = 1; k < FFT_LENGTH_BY_2; k++) {
            seed = (seed * 69069 + 1) & 0x7FFFFFFF;
            var i = seed >>> 26;
            var x = SQRT2_SIN[i];
            var y = SQRT2_SIN[(i + 8) & 31];
            low.re[k] = magnitude[k] * x;
            low.im[k] = magnitude[k] * y;
            high.re[k] = highBandNoiseLevel * x;
            high.im[k] = highBandNoiseLevel * y;
        }
    }
}
