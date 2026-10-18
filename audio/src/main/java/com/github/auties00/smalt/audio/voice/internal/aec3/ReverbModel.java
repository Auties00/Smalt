package com.github.auties00.smalt.audio.voice.internal.aec3;

import static com.github.auties00.smalt.audio.voice.internal.aec3.Aec3.FFT_LENGTH_BY_2_PLUS_1;

/**
 * Models the reverberation of the echo as an exponentially decaying accumulation of the render
 * power spectrum.
 *
 * @implNote This implementation ports WebRTC's
 * {@code modules/audio_processing/aec3/reverb_model.cc}.
 */
final class ReverbModel {
    /**
     * The modeled reverberation power per bin.
     */
    final float[] reverb = new float[FFT_LENGTH_BY_2_PLUS_1];

    /**
     * Creates a model without reverberation.
     */
    ReverbModel() {
    }

    /**
     * Adds a power spectrum scaled by a constant, then decays the model.
     *
     * @param powerSpectrum        the power spectrum
     * @param offset               the index of bin 0 of {@code powerSpectrum}
     * @param powerSpectrumScaling the scaling of the power spectrum
     * @param reverbDecay          the decay per block; non-positive values leave the model
     *                             unchanged
     */
    void updateReverbNoFreqShaping(float[] powerSpectrum, int offset, float powerSpectrumScaling, float reverbDecay) {
        if (reverbDecay > 0) {
            for (var k = 0; k < FFT_LENGTH_BY_2_PLUS_1; ++k) {
                reverb[k] = (reverb[k] + powerSpectrum[offset + k] * powerSpectrumScaling) * reverbDecay;
            }
        }
    }

    /**
     * Adds a power spectrum scaled per bin, then decays the model.
     *
     * @param powerSpectrum        the power spectrum
     * @param offset               the index of bin 0 of {@code powerSpectrum}
     * @param powerSpectrumScaling the per-bin scaling of the power spectrum
     * @param reverbDecay          the decay per block; non-positive values leave the model
     *                             unchanged
     */
    void updateReverb(float[] powerSpectrum, int offset, float[] powerSpectrumScaling, float reverbDecay) {
        if (reverbDecay > 0) {
            for (var k = 0; k < FFT_LENGTH_BY_2_PLUS_1; ++k) {
                reverb[k] = (reverb[k] + powerSpectrum[offset + k] * powerSpectrumScaling[k]) * reverbDecay;
            }
        }
    }
}
