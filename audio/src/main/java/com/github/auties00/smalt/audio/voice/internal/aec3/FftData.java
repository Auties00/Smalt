package com.github.auties00.smalt.audio.voice.internal.aec3;

import java.util.Arrays;

/**
 * Holds one 128-point spectrum as the real and imaginary parts of its 65 bins.
 *
 * @implNote This implementation follows WebRTC's
 * {@code modules/audio_processing/aec3/fft_data.h}.
 */
final class FftData {
    /**
     * The real parts of bins 0 to 64.
     */
    final float[] re = new float[Aec3.FFT_LENGTH_BY_2_PLUS_1];

    /**
     * The imaginary parts of bins 0 to 64.
     */
    final float[] im = new float[Aec3.FFT_LENGTH_BY_2_PLUS_1];

    /**
     * Creates a spectrum of zeros.
     */
    FftData() {
    }

    /**
     * Copies another spectrum, forcing the imaginary parts of the DC and Nyquist bins to zero.
     *
     * @param source the spectrum to copy
     */
    void assign(FftData source) {
        System.arraycopy(source.re, 0, re, 0, Aec3.FFT_LENGTH_BY_2_PLUS_1);
        System.arraycopy(source.im, 0, im, 0, Aec3.FFT_LENGTH_BY_2_PLUS_1);
        im[0] = 0;
        im[Aec3.FFT_LENGTH_BY_2] = 0;
    }

    /**
     * Sets every bin to zero.
     */
    void clear() {
        Arrays.fill(re, 0.f);
        Arrays.fill(im, 0.f);
    }

    /**
     * Computes the power spectrum {@code re^2 + im^2} of every bin.
     *
     * @param powerSpectrum receives the 65 powers
     */
    void spectrum(float[] powerSpectrum) {
        for (var k = 0; k < Aec3.FFT_LENGTH_BY_2_PLUS_1; k++) {
            powerSpectrum[k] = re[k] * re[k] + im[k] * im[k];
        }
    }
}
