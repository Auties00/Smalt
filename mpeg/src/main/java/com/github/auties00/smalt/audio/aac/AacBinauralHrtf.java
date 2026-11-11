package com.github.auties00.smalt.audio.aac;

/**
 * Enumerates the head related transfer function data sets of the binaural rendering of MPEG Surround; see
 * {@link AacDecoderOptions#mpegSurroundBinaural()}.
 *
 * <p>Each set describes the five loudspeaker directions at 44.1 kHz as band powers, coherences and phase differences,
 * which {@link AacBinauralQuality#PARAMETRIC} uses, and as impulse responses, which
 * {@link AacBinauralQuality#FILTERED} uses. The sets come with the ISO/IEC 23003-3 reference software.
 */
public enum AacBinauralHrtf {
    /**
     * The set the reference software calls Kemar, with impulse responses of 128 samples.
     */
    KEMAR,
    /**
     * The set the reference software calls Vast, with impulse responses of 1000 samples.
     */
    VAST,
    /**
     * The set the reference software calls MPSVT, with impulse responses of 2048 samples and a reverb of 2048 samples
     * that {@link AacBinauralQuality#PARAMETRIC} adds to its output.
     */
    MPSVT
}
