package com.github.auties00.smalt.audio.aac;

/**
 * Enumerates the dynamic range compression an {@link AacDecoder} applies.
 *
 * <p>Both kinds of gain come from the stream. The light compression gains are the
 * {@code dynamic_range_info} of ISO/IEC 14496-3 (subclause 4.5.2.7), carried in fill elements per
 * channel and per band; the heavy compression gain is the {@code compression_value} of the MPEG-4
 * ancillary data of ETSI TS 101 154 (Annex C.5), carried in a data stream element for the whole
 * program. A gain persists until the stream overwrites it.
 */
public enum AacCompression {
    /**
     * Reproduces the full dynamic range: the stream's compression gains are ignored.
     */
    NONE,

    /**
     * Applies the {@code dynamic_range_info} gains, their attenuations and boosts scaled by the
     * decoder's cut and boost factors.
     */
    LIGHT,

    /**
     * Applies the heavy compression gain of the ETSI TS 101 154 ancillary data, unscaled, while
     * the stream has it switched on, and the {@code dynamic_range_info} gains otherwise.
     */
    HEAVY
}
