package com.github.auties00.smalt.image;

/**
 * The set of color channels that leads a {@link PixelLayout}.
 *
 * <p>This enum may gain constants in later releases; a {@code switch} over it needs a {@code default} branch.
 */
public enum ColorModel {
    /**
     * One {@link ChannelType#GRAY} channel.
     */
    GRAY,

    /**
     * {@link ChannelType#RED}, {@link ChannelType#GREEN} and {@link ChannelType#BLUE}, in that order.
     */
    RGB,

    /**
     * The process inks {@link ChannelType#CYAN}, {@link ChannelType#MAGENTA}, {@link ChannelType#YELLOW} and
     * {@link ChannelType#BLACK}, in that order (TIFF {@code InkSet} 1, ICC {@code CMYK}).
     */
    CMYK,

    /**
     * One or more {@link ChannelType#INK} separations identified by name (TIFF {@code InkSet} 2, ICC
     * {@code nCLR}); their color is defined only by a color profile.
     */
    INK
}
