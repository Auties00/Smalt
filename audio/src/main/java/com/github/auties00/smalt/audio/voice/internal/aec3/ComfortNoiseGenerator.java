package com.github.auties00.smalt.audio.voice.internal.aec3;

import com.github.auties00.smalt.audio.voice.internal.FloatMath;

import java.util.Arrays;

import static com.github.auties00.smalt.audio.voice.internal.aec3.Aec3.FFT_LENGTH_BY_2;
import static com.github.auties00.smalt.audio.voice.internal.aec3.Aec3.FFT_LENGTH_BY_2_PLUS_1;

/**
 * Estimates the background noise spectrum of the capture signal and generates comfort noise of
 * that spectrum with random phases, to fill what the suppressor removes.
 *
 * @implNote This implementation ports WebRTC's
 * {@code modules/audio_processing/aec3/comfort_noise_generator.cc} for one capture channel; the
 * phases come from the same linear congruential generator seeded with 42, so the noise is
 * identical to the reference's.
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
     * The noise floor power, {@code 64 * 10^((90.309 + noise_floor_dbfs) / 10)}.
     */
    private final float noiseFloor = 64.f * FloatMath.powf(10.f, (90.30899869919436f + Aec3.NOISE_FLOOR_DBFS) * 0.1f);

    /**
     * The state of the phase generator.
     */
    private int seed = 42;

    /**
     * The noise estimate used during the first 1000 blocks, or {@code null} afterwards.
     */
    private float[] n2Initial = new float[FFT_LENGTH_BY_2_PLUS_1];

    /**
     * The smoothed capture power spectrum.
     */
    private final float[] y2Smoothed = new float[FFT_LENGTH_BY_2_PLUS_1];

    /**
     * The noise power spectrum estimate.
     */
    private final float[] n2 = new float[FFT_LENGTH_BY_2_PLUS_1];

    /**
     * The number of unsaturated blocks, up to 1000.
     */
    private int n2Counter;

    /**
     * The scratch buffer of the noise magnitude spectrum.
     */
    private final float[] magnitude = new float[FFT_LENGTH_BY_2_PLUS_1];

    /**
     * Creates a generator.
     */
    ComfortNoiseGenerator() {
        Arrays.fill(n2, 1.0e6f);
    }

    /**
     * Updates the noise estimate and generates the comfort noise of a block.
     *
     * @param saturatedCapture whether the capture signal is saturated
     * @param y2               the capture power spectrum
     * @param lowerBandNoise   receives the comfort noise spectrum
     */
    void compute(boolean saturatedCapture, float[] y2, FftData lowerBandNoise) {
        if (!saturatedCapture) {
            for (var k = 0; k < FFT_LENGTH_BY_2_PLUS_1; k++) {
                var a = y2Smoothed[k];
                y2Smoothed[k] = a + 0.1f * (y2[k] - a);
            }
            if (n2Counter > 50) {
                for (var k = 0; k < FFT_LENGTH_BY_2_PLUS_1; k++) {
                    var a = n2[k];
                    var b = y2Smoothed[k];
                    n2[k] = b < a ? (0.9f * b + 0.1f * a) * 1.0002f : a * 1.0002f;
                }
            }
            if (n2Initial != null) {
                if (++n2Counter == 1000) {
                    n2Initial = null;
                } else {
                    for (var k = 0; k < FFT_LENGTH_BY_2_PLUS_1; k++) {
                        var a = n2[k];
                        var b = n2Initial[k];
                        n2Initial[k] = a > b ? b + 0.001f * (a - b) : a;
                    }
                }
            }
            for (var k = 0; k < FFT_LENGTH_BY_2_PLUS_1; k++) {
                n2[k] = FloatMath.max(n2[k], noiseFloor);
            }
            if (n2Initial != null) {
                for (var k = 0; k < FFT_LENGTH_BY_2_PLUS_1; k++) {
                    n2Initial[k] = FloatMath.max(n2Initial[k], noiseFloor);
                }
            }
        }
        generate(n2Initial != null ? n2Initial : n2, lowerBandNoise);
    }

    /**
     * Returns the noise power spectrum estimate, which the suppressor treats as the masker of the
     * echo; during the first 1000 blocks the comfort noise is generated from a separate initial
     * estimate instead.
     *
     * @return the live array of 65 powers
     */
    float[] noiseSpectrum() {
        return n2;
    }

    /**
     * Generates comfort noise with the given power spectrum and random phases.
     *
     * @param power the noise power spectrum
     * @param noise receives the noise spectrum
     */
    private void generate(float[] power, FftData noise) {
        for (var k = 0; k < FFT_LENGTH_BY_2_PLUS_1; k++) {
            magnitude[k] = (float) Math.sqrt(power[k]);
        }
        noise.re[0] = noise.re[FFT_LENGTH_BY_2] = 0.f;
        noise.im[0] = noise.im[FFT_LENGTH_BY_2] = 0.f;
        for (var k = 1; k < FFT_LENGTH_BY_2; k++) {
            seed = (seed * 69069 + 1) & 0x7FFFFFFF;
            var i = seed >>> 26;
            var x = SQRT2_SIN[i];
            var y = SQRT2_SIN[(i + 8) & 31];
            noise.re[k] = magnitude[k] * x;
            noise.im[k] = magnitude[k] * y;
        }
    }
}
