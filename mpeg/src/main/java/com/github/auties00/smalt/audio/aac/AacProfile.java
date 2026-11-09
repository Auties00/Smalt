package com.github.auties00.smalt.audio.aac;

/**
 * Selects the MPEG-4 audio object type an {@link AacEncoder} produces.
 */
public enum AacProfile {
    /**
     * AAC Low Complexity, audio object type 2: the full band coded by the AAC core at the input rate.
     */
    LC,

    /**
     * HE-AAC (HE-AAC v1), audio object type 5: an AAC Low Complexity core at half the input rate, the band above
     * its crossover reconstructed by spectral band replication.
     */
    HE,

    /**
     * HE-AAC v2, audio object type 29: HE-AAC of a mono downmix of a stereo input, the stereo image restored by
     * parametric stereo.
     */
    HE_V2,

    /**
     * AAC-LD, audio object type 23: frames of 512 or 480 samples coded with long sine windows only, in the error
     * resilient syntax, for a delay of one frame.
     */
    LD,

    /**
     * AAC-ELD, audio object type 39: frames of 512 or 480 samples coded with the low delay filterbank, in the
     * error resilient syntax, for a delay of three quarters of a frame.
     */
    ELD
}
