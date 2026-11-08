package com.github.auties00.smalt.image;

/**
 * The meaning of one channel of a {@link PixelLayout}.
 *
 * <p>The color channels ({@link #isColor()}) carry the color of a pixel in its {@link ColorModel}; the
 * other channels are extra channels that ride along with it. Sample values follow the conventions of
 * {@link ChannelPicture}: an ink channel ({@link #CYAN}, {@link #MAGENTA}, {@link #YELLOW}, {@link #BLACK},
 * {@link #INK}, {@link #SPOT}) holds the amount of ink, the largest value being full coverage and zero no
 * ink, and an alpha channel holds the opacity, zero being fully transparent.
 *
 * <p>This enum may gain constants in later releases; a {@code switch} over it needs a {@code default} branch.
 */
public enum ChannelType {
    /**
     * Gray (luminance), the only color channel of {@link ColorModel#GRAY}; zero is black.
     */
    GRAY,

    /**
     * Red, a color channel of {@link ColorModel#RGB}.
     */
    RED,

    /**
     * Green, a color channel of {@link ColorModel#RGB}.
     */
    GREEN,

    /**
     * Blue, a color channel of {@link ColorModel#RGB}.
     */
    BLUE,

    /**
     * Cyan ink, a color channel of {@link ColorModel#CMYK}.
     */
    CYAN,

    /**
     * Magenta ink, a color channel of {@link ColorModel#CMYK}.
     */
    MAGENTA,

    /**
     * Yellow ink, a color channel of {@link ColorModel#CMYK}.
     */
    YELLOW,

    /**
     * Black ink, a color channel of {@link ColorModel#CMYK}.
     */
    BLACK,

    /**
     * One named separation of {@link ColorModel#INK}, a color model of any number of inks (TIFF
     * {@code InkSet} 2, ICC {@code nCLR}).
     */
    INK,

    /**
     * Straight opacity: the color channels are not multiplied by it.
     */
    ALPHA,

    /**
     * Associated opacity: the color channels, and for an ink model the ink amounts, are already
     * multiplied by it.
     */
    PREMULTIPLIED_ALPHA,

    /**
     * A spot color printed on top of the color channels, its ink amount per pixel.
     */
    SPOT,

    /**
     * Depth, as JPEG XL's depth extra channel defines it.
     */
    DEPTH,

    /**
     * A selection mask.
     */
    SELECTION_MASK,

    /**
     * Thermal measurements.
     */
    THERMAL,

    /**
     * An extra channel whose meaning the file does not state.
     */
    UNSPECIFIED;

    /**
     * {@return whether this is a color channel, from {@link #GRAY} to {@link #INK}}
     */
    public boolean isColor() {
        return ordinal() <= INK.ordinal();
    }

    /**
     * {@return whether this is {@link #ALPHA} or {@link #PREMULTIPLIED_ALPHA}}
     */
    public boolean isAlpha() {
        return this == ALPHA || this == PREMULTIPLIED_ALPHA;
    }
}
