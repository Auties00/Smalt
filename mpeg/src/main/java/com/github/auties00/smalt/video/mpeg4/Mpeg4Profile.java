package com.github.auties00.smalt.video.mpeg4;

/**
 * An MPEG-4 Visual profile an {@link Mpeg4Encoder} codes, which decides the coding tools a stream may use
 * (ISO/IEC 14496-2 Annex N).
 */
public enum Mpeg4Profile {
    /**
     * The Simple profile, {@code video_object_type_indication} 1: I- and P-VOPs with half-sample vectors, one or
     * four vectors per macroblock, AC and DC prediction, H.263 quantization and video packets; the profile every
     * MPEG-4 Part 2 decoder supports.
     */
    SIMPLE,

    /**
     * The Advanced Simple profile, {@code video_object_type_indication} 17: the Simple profile's tools with B-VOPs,
     * quarter-sample vectors and the MPEG quantization method with default or downloaded matrices.
     */
    ADVANCED_SIMPLE
}
