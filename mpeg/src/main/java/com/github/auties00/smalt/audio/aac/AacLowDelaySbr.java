package com.github.auties00.smalt.audio.aac;

/**
 * Selects the mode of the low delay spectral band replication an {@link AacEncoder} adds to AAC-ELD
 * ({@link AacProfile#ELD}, ISO/IEC 14496-3 subclause 4.6.19), which reconstructs the band above the core's crossover
 * from the core's band and a few parameters per frame.
 */
public enum AacLowDelaySbr {
    /**
     * The dual-rate mode ({@code ldSbrSamplingRate} 1): the core runs at half the input rate, with frames of 512 or
     * 480 core samples covering twice as many input samples, and the spectral band replication reconstructs the band
     * up to its stop frequency at the input rate.
     */
    DUAL_RATE,

    /**
     * The downsampled mode ({@code ldSbrSamplingRate} 0): the core runs at the input rate, and the spectral band
     * replication reconstructs the band from the core's crossover to at most half the input rate, so the core can
     * code a narrower band at the same delay.
     */
    DOWNSAMPLED
}
