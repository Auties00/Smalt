package com.github.auties00.smalt.audio;

/**
 * Enumerates the loudspeaker positions an audio channel can feed.
 *
 * <p>The constants and their declaration order follow the WAVE {@code WAVEFORMATEXTENSIBLE}
 * channel mask, which is also the canonical order of MP4, Matroska and most codecs once their
 * native order is normalized. The positions after {@link #TOP_BACK_RIGHT}, which the channel mask
 * cannot express, follow it in the order of the 22.2 layout of ITU-R BS.2051 and ISO/IEC 23091-3;
 * the labels in parentheses are those of ITU-R BS.2051 (azimuth and elevation in degrees).
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
     * Front wide left, between front left and side left (M+060 when front left is M+030).
     */
    WIDE_LEFT,

    /**
     * Front wide right, between front right and side right (M-060 when front right is M-030).
     */
    WIDE_RIGHT,

    /**
     * Surround direct left, a left surround at the side fed directly rather than diffusely
     * (M+090 in E-AC-3 and Dolby TrueHD channel maps).
     */
    SURROUND_DIRECT_LEFT,

    /**
     * Surround direct right (M-090 in E-AC-3 and Dolby TrueHD channel maps).
     */
    SURROUND_DIRECT_RIGHT,

    /**
     * Second low-frequency effects channel (LFE2, at the right of the 22.2 layout).
     */
    LOW_FREQUENCY_2,

    /**
     * Top side left (U+090).
     */
    TOP_SIDE_LEFT,

    /**
     * Top side right (U-090).
     */
    TOP_SIDE_RIGHT,

    /**
     * Bottom front center, below the screen (B+000).
     */
    BOTTOM_FRONT_CENTER,

    /**
     * Bottom front left (B+045).
     */
    BOTTOM_FRONT_LEFT,

    /**
     * Bottom front right (B-045).
     */
    BOTTOM_FRONT_RIGHT,

    /**
     * A channel with no declared position.
     */
    UNKNOWN
}
