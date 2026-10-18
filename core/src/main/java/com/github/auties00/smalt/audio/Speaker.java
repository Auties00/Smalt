package com.github.auties00.smalt.audio;

/**
 * Enumerates the loudspeaker positions an audio channel can feed.
 *
 * <p>The constants and their declaration order follow the WAVE {@code WAVEFORMATEXTENSIBLE}
 * channel mask, which is also the canonical order of MP4, Matroska and most codecs once their
 * native order is normalized.
 *
 * <p>Instances are immutable and thread-safe.
 */
public enum Speaker {
    /**
     * Front left.
     */
    FRONT_LEFT,

    /**
     * Front right.
     */
    FRONT_RIGHT,

    /**
     * Front center.
     */
    FRONT_CENTER,

    /**
     * Low-frequency effects.
     */
    LOW_FREQUENCY,

    /**
     * Back (rear) left.
     */
    BACK_LEFT,

    /**
     * Back (rear) right.
     */
    BACK_RIGHT,

    /**
     * Front left of center.
     */
    FRONT_LEFT_OF_CENTER,

    /**
     * Front right of center.
     */
    FRONT_RIGHT_OF_CENTER,

    /**
     * Back center.
     */
    BACK_CENTER,

    /**
     * Side left.
     */
    SIDE_LEFT,

    /**
     * Side right.
     */
    SIDE_RIGHT,

    /**
     * Top center.
     */
    TOP_CENTER,

    /**
     * Top front left.
     */
    TOP_FRONT_LEFT,

    /**
     * Top front center.
     */
    TOP_FRONT_CENTER,

    /**
     * Top front right.
     */
    TOP_FRONT_RIGHT,

    /**
     * Top back left.
     */
    TOP_BACK_LEFT,

    /**
     * Top back center.
     */
    TOP_BACK_CENTER,

    /**
     * Top back right.
     */
    TOP_BACK_RIGHT,

    /**
     * A channel with no declared position.
     */
    UNKNOWN
}
