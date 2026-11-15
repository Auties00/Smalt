package com.github.auties00.smalt.video.cfhd;

/**
 * The arrangement of the 2x2 color filter array of a CineForm RAW (Bayer coded) frame, named by the colors of its
 * top-left, top-right, bottom-left and bottom-right samples.
 *
 * <p>A sample carries its phase in its {@code BFMT} metadata; a decoder that has not seen one assumes
 * {@link #RGGB}, as GoPro's decoder does.
 *
 * @see CfhdDecoder#bayerPhase()
 */
public enum CfhdBayerPhase {
    /**
     * Red and green on the even rows, green and blue on the odd rows ({@code BFMT} 0).
     */
    RGGB,

    /**
     * Green and red on the even rows, blue and green on the odd rows ({@code BFMT} 1).
     */
    GRBG,

    /**
     * Green and blue on the even rows, red and green on the odd rows ({@code BFMT} 2).
     */
    GBRG,

    /**
     * Blue and green on the even rows, green and red on the odd rows ({@code BFMT} 3).
     */
    BGGR
}
