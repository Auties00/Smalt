/* Ports webrtc-audio-processing 2.1 (modules/audio_processing/ns/suppression_params.cc). Copyright (c) 2011, The
   WebRTC project authors; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.voice.internal.ns;

/**
 * Holds the tuning of one noise suppression level, with the values of WebRTC's
 * {@code SuppressionParams}.
 *
 * @param overSubtractionFactor the factor the noise estimate is multiplied by before it is
 * subtracted
 * @param minimumAttenuatingGain the lowest gain the Wiener filter applies to a bin
 * @param useAttenuationAdjustment whether the overall gain is corrected for the energy the filter
 * removed
 */
record SuppressionParams(float overSubtractionFactor, float minimumAttenuatingGain, boolean useAttenuationAdjustment) {
    /**
     * Returns the parameters of a target attenuation level.
     *
     * @param level the level: {@code 0} for 6 dB, {@code 1} for 12 dB, {@code 2} for 18 dB and
     * {@code 3} for 21 dB
     * @return the parameters
     * @throws IllegalArgumentException if {@code level} is outside {@code [0, 3]}
     */
    static SuppressionParams forLevel(int level) {
        return switch (level) {
            case 0 -> new SuppressionParams(1.f, 0.5f, false);
            case 1 -> new SuppressionParams(1.f, 0.25f, true);
            case 2 -> new SuppressionParams(1.1f, 0.125f, true);
            case 3 -> new SuppressionParams(1.25f, 0.09f, true);
            default -> throw new IllegalArgumentException("unknown suppression level: " + level);
        };
    }
}
