package com.github.auties00.smalt.audio.aac;

/**
 * Enumerates how the binaural rendering of MPEG Surround applies its head related transfer functions; see
 * {@link AacDecoderOptions#mpegSurroundBinauralQuality()}.
 */
public enum AacBinauralQuality {
    /**
     * Applies the band powers, coherences and phase differences of the data set in the parameter bands. With
     * {@link AacBinauralHrtf#MPSVT} it adds the reverb of the set.
     */
    PARAMETRIC,
    /**
     * Filters a stereo downmix with the impulse responses of the data set, converted to the QMF domain and weighted by
     * the spatial parameters, at a higher cost, and the first use of a data set in a JVM at each QMF bank size solves
     * its conversion. A mono downmix has no such path: it renders as {@link #PARAMETRIC},
     * without the reverb.
     */
    FILTERED
}
