package com.github.auties00.smalt.image.jp2;

/**
 * Enumerates the roles a JP2 channel definition box gives a channel (ISO/IEC 15444-1 I.5.3.6,
 * {@code Typ}).
 *
 * <p>Constants may be added in minor releases, so a {@code switch} over this type needs a
 * {@code default} branch.
 */
public enum Jp2ChannelType {
    /**
     * A color channel, associated with one color of the color space.
     */
    COLOR,

    /**
     * An opacity channel: 0 is fully transparent, the maximum value fully opaque.
     */
    OPACITY,

    /**
     * A premultiplied opacity channel: the color channels it applies to are multiplied by it.
     */
    PREMULTIPLIED_OPACITY,

    /**
     * A channel whose role the file does not specify.
     */
    UNSPECIFIED
}
