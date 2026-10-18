package com.github.auties00.smalt.image.raw;

/**
 * A rendering of the pixels in which some color channels reached the sensor's saturation.
 *
 * <p>After white balancing, the channels of a camera saturate at different levels, so a bright
 * neutral highlight in which only some channels clipped would turn magenta or cyan if rendered as
 * is.
 */
public enum RawHighlights {
    /**
     * Clips every white-balanced channel at the level of the first channel to saturate, so
     * clipped highlights render white: the safe default.
     */
    CLIP,

    /**
     * Keeps the luminance the unclipped channels still record in partly clipped pixels and
     * desaturates them toward white in proportion to how far they are clipped, which keeps more
     * detail in bright areas than {@link #CLIP} without color casts.
     */
    BLEND
}
