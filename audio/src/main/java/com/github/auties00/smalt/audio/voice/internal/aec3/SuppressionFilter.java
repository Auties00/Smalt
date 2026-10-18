package com.github.auties00.smalt.audio.voice.internal.aec3;

import static com.github.auties00.smalt.audio.voice.internal.aec3.Aec3.FFT_LENGTH;
import static com.github.auties00.smalt.audio.voice.internal.aec3.Aec3.FFT_LENGTH_BY_2;
import static com.github.auties00.smalt.audio.voice.internal.aec3.Aec3.FFT_LENGTH_BY_2_PLUS_1;

/**
 * Applies the suppression gain to the capture spectrum, fills the removed energy with comfort
 * noise, and synthesizes the output block by square-root Hanning windowed overlap-add.
 *
 * @implNote This implementation ports {@code SuppressionFilter::ApplyGain} of WebRTC's
 * {@code modules/audio_processing/aec3/suppression_filter.cc} for one capture channel and one
 * band.
 */
final class SuppressionFilter {
    /**
     * The normalization of the inverse transform, {@code 2 / 128}.
     */
    private static final float IFFT_NORMALIZATION = 2.f / FFT_LENGTH;

    /**
     * The second half of the previous synthesis, overlapped with the next block.
     */
    private final float[] outputOld = new float[FFT_LENGTH_BY_2];

    /**
     * The scratch buffer of the comfort noise gain.
     */
    private final float[] noiseGain = new float[FFT_LENGTH_BY_2_PLUS_1];

    /**
     * The scratch spectrum.
     */
    private final FftData e = new FftData();

    /**
     * The scratch buffer of the synthesis.
     */
    private final float[] extended = new float[FFT_LENGTH];

    /**
     * Creates a filter with an empty overlap.
     */
    SuppressionFilter() {
    }

    /**
     * Applies the gain and the comfort noise and writes the output block.
     *
     * @param comfortNoise    the comfort noise spectrum
     * @param suppressionGain the suppression gain per bin
     * @param spectrum        the spectrum to suppress, the linear filter output or the capture
     * @param output          receives the 64 output samples, clamped to the 16-bit range
     * @param offset          the index of the first output sample
     */
    void applyGain(FftData comfortNoise, float[] suppressionGain, FftData spectrum, float[] output, int offset) {
        for (var i = 0; i < FFT_LENGTH_BY_2_PLUS_1; ++i) {
            noiseGain[i] = 1.f - suppressionGain[i] * suppressionGain[i];
        }
        for (var i = 0; i < FFT_LENGTH_BY_2_PLUS_1; ++i) {
            noiseGain[i] = (float) Math.sqrt(noiseGain[i]);
        }
        e.assign(spectrum);
        for (var i = 0; i < FFT_LENGTH_BY_2_PLUS_1; ++i) {
            var eReal = e.re[i] * suppressionGain[i];
            var eImag = e.im[i] * suppressionGain[i];
            e.re[i] = eReal + noiseGain[i] * comfortNoise.re[i];
            e.im[i] = eImag + noiseGain[i] * comfortNoise.im[i];
        }
        Aec3Fft.ifft(e.re, e.im, 0, extended);
        var window = Aec3Fft.SQRT_HANNING_128;
        for (var i = 0; i < FFT_LENGTH_BY_2; ++i) {
            var e0 = outputOld[i] * window[FFT_LENGTH_BY_2 + i];
            e0 += extended[i] * window[i];
            output[offset + i] = e0 * IFFT_NORMALIZATION;
        }
        System.arraycopy(extended, FFT_LENGTH_BY_2, outputOld, 0, FFT_LENGTH_BY_2);
        for (var i = 0; i < FFT_LENGTH_BY_2; ++i) {
            var v = output[offset + i];
            output[offset + i] = v <= -32768.f ? -32768.f : (v >= 32767.f ? 32767.f : v);
        }
    }
}
