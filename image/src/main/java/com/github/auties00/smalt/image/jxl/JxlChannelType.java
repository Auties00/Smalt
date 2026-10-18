package com.github.auties00.smalt.image.jxl;

/**
 * Enumerates the kinds of extra channel a JPEG XL image can carry beside its color channels
 * (ISO/IEC 18181-1 table A.16).
 *
 * <p>This enum may gain constants in later releases; a {@code switch} over it needs a
 * {@code default} branch.
 */
public enum JxlChannelType {
    /**
     * Opacity, straight or premultiplied into the color.
     */
    ALPHA,

    /**
     * Depth, a distance from the camera.
     */
    DEPTH,

    /**
     * A spot color, an extra ink with its own color.
     */
    SPOT_COLOR,

    /**
     * A selection mask.
     */
    SELECTION_MASK,

    /**
     * The black (K) channel of a CMYK image.
     */
    BLACK,

    /**
     * A channel of a color filter array, such as a raw camera mosaic.
     */
    CFA,

    /**
     * Thermal (infrared) intensity.
     */
    THERMAL,

    /**
     * A channel of unspecified meaning that a viewer must not ignore.
     */
    NON_OPTIONAL,

    /**
     * A channel of unspecified meaning that a viewer may ignore.
     */
    OPTIONAL
}
