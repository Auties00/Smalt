/* Ports webrtc-audio-processing 2.1 (modules/audio_processing/aec3/suppression_filter.cc). Copyright (c) 2011, The
   WebRTC project authors; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.voice.internal.aec3;

import static com.github.auties00.smalt.audio.voice.internal.aec3.Aec3.BLOCK_SIZE;
import static com.github.auties00.smalt.audio.voice.internal.aec3.Aec3.FFT_LENGTH;
import static com.github.auties00.smalt.audio.voice.internal.aec3.Aec3.FFT_LENGTH_BY_2;
import static com.github.auties00.smalt.audio.voice.internal.aec3.Aec3.FFT_LENGTH_BY_2_PLUS_1;

/**
 * Applies the suppression gain to the capture spectrum of every channel, fills the removed energy
 * with comfort noise, and synthesizes the lowest band by square-root Hanning windowed overlap-add;
 * the upper bands are scaled by one gain, get comfort noise in the first upper band and are
 * delayed by a block to stay aligned with the lowest band.
 *
 * @implNote This implementation ports {@code SuppressionFilter::ApplyGain} of WebRTC's
 * {@code modules/audio_processing/aec3/suppression_filter.cc}.
 */
final class SuppressionFilter {
    /**
     * The normalization of the inverse transform, {@code 2 / 128}.
     */
    private static final float IFFT_NORMALIZATION = 2.f / FFT_LENGTH;

    /**
     * The number of bands.
     */
    private final int numBands;

    /**
     * The number of capture channels.
     */
    private final int numChannels;

    /**
     * The second half of the previous lowest-band synthesis, and the previous block of every
     * upper band, {@code [band][channel]}.
     */
    private final float[][][] outputOld;

    /**
     * The scratch noise gains.
     */
    private final float[] noiseGain = new float[FFT_LENGTH_BY_2_PLUS_1];

    /**
     * The scratch spectrum.
     */
    private final FftData e = new FftData();

    /**
     * The scratch time-domain frame.
     */
    private final float[] extended = new float[FFT_LENGTH];

    /**
     * Creates a filter.
     *
     * @param numBands the number of bands
     * @param numChannels the number of capture channels
     */
    SuppressionFilter(int numBands, int numChannels) {
        this.numBands = numBands;
        this.numChannels = numChannels;
        this.outputOld = new float[numBands][numChannels][FFT_LENGTH_BY_2];
    }

    /**
     * Applies the gains to a block in place.
     *
     * @param comfortNoise the comfort noise of the lowest band, per channel
     * @param comfortNoiseHighBand the comfort noise of the upper bands, per channel
     * @param suppressionGain the 65 gains of the lowest band
     * @param highBandsGain the gain of the upper bands
     * @param spectrum the spectrum of the lowest band to suppress, per channel
     * @param block the capture block, band by band and channel by channel; receives the output
     */
    void applyGain(FftData[] comfortNoise, FftData[] comfortNoiseHighBand, float[] suppressionGain, float highBandsGain,
                   FftData[] spectrum, float[] block) {
        for (var i = 0; i < FFT_LENGTH_BY_2_PLUS_1; ++i) {
            noiseGain[i] = 1.f - suppressionGain[i] * suppressionGain[i];
        }
        for (var i = 0; i < FFT_LENGTH_BY_2_PLUS_1; ++i) {
            noiseGain[i] = (float) Math.sqrt(noiseGain[i]);
        }
        var highBandsNoiseScaling = 0.4f * (float) Math.sqrt(1.f - highBandsGain * highBandsGain);
        for (var ch = 0; ch < numChannels; ch++) {
            e.assign(spectrum[ch]);
            var noise = comfortNoise[ch];
            for (var i = 0; i < FFT_LENGTH_BY_2_PLUS_1; ++i) {
                var eReal = e.re[i] * suppressionGain[i];
                var eImag = e.im[i] * suppressionGain[i];
                e.re[i] = eReal + noiseGain[i] * noise.re[i];
                e.im[i] = eImag + noiseGain[i] * noise.im[i];
            }
            Aec3Fft.ifft(e.re, e.im, 0, extended);
            var window = Aec3Fft.SQRT_HANNING_128;
            var old0 = outputOld[0][ch];
            var o0 = ch * BLOCK_SIZE;
            for (var i = 0; i < FFT_LENGTH_BY_2; ++i) {
                var e0 = old0[i] * window[FFT_LENGTH_BY_2 + i];
                e0 += extended[i] * window[i];
                block[o0 + i] = e0 * IFFT_NORMALIZATION;
            }
            System.arraycopy(extended, FFT_LENGTH_BY_2, old0, 0, FFT_LENGTH_BY_2);
            for (var b = 1; b < numBands; ++b) {
                var o = (b * numChannels + ch) * BLOCK_SIZE;
                for (var i = 0; i < FFT_LENGTH_BY_2; ++i) {
                    block[o + i] *= highBandsGain;
                }
            }
            if (numBands > 1) {
                e.assign(comfortNoiseHighBand[ch]);
                Aec3Fft.ifft(e.re, e.im, 0, extended);
                var o1 = (numChannels + ch) * BLOCK_SIZE;
                var gain = highBandsNoiseScaling * IFFT_NORMALIZATION;
                for (var i = 0; i < FFT_LENGTH_BY_2; ++i) {
                    block[o1 + i] += extended[i] * gain;
                }
            }
            for (var b = 1; b < numBands; ++b) {
                var o = (b * numChannels + ch) * BLOCK_SIZE;
                var old = outputOld[b][ch];
                for (var i = 0; i < FFT_LENGTH_BY_2; ++i) {
                    var tmp = block[o + i];
                    block[o + i] = old[i];
                    old[i] = tmp;
                }
            }
            for (var b = 0; b < numBands; ++b) {
                var o = (b * numChannels + ch) * BLOCK_SIZE;
                for (var i = 0; i < FFT_LENGTH_BY_2; ++i) {
                    var v = block[o + i];
                    block[o + i] = v <= -32768.f ? -32768.f : (v >= 32767.f ? 32767.f : v);
                }
            }
        }
    }
}
