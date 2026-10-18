package com.github.auties00.smalt.audio.ac3;

/**
 * Enumerates the dynamic range compression an {@link Ac3Decoder} applies (ATSC A/52 section 7.7).
 */
public enum Ac3Compression {
    /**
     * Reproduces the full dynamic range: the dynamic range words of the stream are ignored.
     */
    NONE,

    /**
     * Applies the per-block dynamic range words ({@code dynrng}), scaled by the decoder's cut and
     * boost factors: the default reproduction A/52 section 7.7.1 requires.
     */
    LIGHT,

    /**
     * Applies the per-frame heavy compression words ({@code compr}) where the stream carries them,
     * and the dynamic range words in the frames that lack them (A/52 section 7.7.2).
     */
    HEAVY
}
