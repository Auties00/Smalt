package com.github.auties00.smalt.image.jpeg.internal;

/**
 * Enumerates the color spaces a JPEG frame can be coded in.
 *
 * <p>JPEG itself does not record the color space; it is inferred from the component count, the
 * JFIF and Adobe application segments, and the component identifiers, as
 * {@link JpegReader#colorSpace()} describes.
 */
public enum JpegColorSpace {
    /**
     * One luminance component.
     */
    GRAY,

    /**
     * Y, Cb and Cr components (JFIF, BT.601 full range).
     */
    YCBCR,

    /**
     * R, G and B components coded without a color transform.
     */
    RGB,

    /**
     * C, M, Y and K components, stored inverted as Adobe applications write them.
     */
    CMYK,

    /**
     * Y, Cb, Cr and K components: the Adobe transform of inverted CMYK.
     */
    YCCK
}
