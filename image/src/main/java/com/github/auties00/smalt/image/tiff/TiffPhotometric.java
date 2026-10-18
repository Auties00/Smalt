package com.github.auties00.smalt.image.tiff;

/**
 * Enumerates the TIFF photometric interpretations, as the {@code PhotometricInterpretation} tag
 * (262) names them.
 *
 * <p>{@link TiffDecoder} decodes {@link #WHITE_IS_ZERO}, {@link #BLACK_IS_ZERO}, {@link #RGB},
 * {@link #PALETTE}, {@link #SEPARATED} (with the CMYK ink set) and {@link #YCBCR}; the others are
 * reported so that a page can describe them.
 *
 * <p>Constants may be added in minor releases, so a {@code switch} over this type needs a
 * {@code default} branch.
 */
public enum TiffPhotometric {
    /**
     * Gray, zero is white (code 0).
     */
    WHITE_IS_ZERO(0),

    /**
     * Gray, zero is black (code 1).
     */
    BLACK_IS_ZERO(1),

    /**
     * Red, green and blue (code 2).
     */
    RGB(2),

    /**
     * Indexes into a {@code ColorMap} (code 3).
     */
    PALETTE(3),

    /**
     * A transparency mask (code 4).
     */
    TRANSPARENCY_MASK(4),

    /**
     * Separated inks, normally CMYK (code 5).
     */
    SEPARATED(5),

    /**
     * Y'CbCr (code 6).
     */
    YCBCR(6),

    /**
     * CIE L*a*b* (code 8).
     */
    CIELAB(8),

    /**
     * ICC L*a*b* (code 9).
     */
    ICCLAB(9),

    /**
     * ITU L*a*b* (code 10).
     */
    ITULAB(10),

    /**
     * Color filter array, as in DNG (code 32803).
     */
    CFA(32803),

    /**
     * SGI LogL (code 32844).
     */
    LOG_L(32844),

    /**
     * SGI LogLuv (code 32845).
     */
    LOG_LUV(32845),

    /**
     * Linear raw, as in DNG (code 34892).
     */
    LINEAR_RAW(34892),

    /**
     * An interpretation this enumeration does not name.
     */
    OTHER(-1);

    /**
     * The value of the tag.
     */
    private final int code;

    /**
     * Constructs a constant.
     *
     * @param code the tag value
     */
    TiffPhotometric(int code) {
        this.code = code;
    }

    /**
     * Returns the value of the {@code PhotometricInterpretation} tag for this interpretation.
     *
     * @return the tag value, or {@code -1} for {@link #OTHER}
     */
    public int code() {
        return code;
    }

    /**
     * Returns the interpretation of a tag value.
     *
     * @param code the tag value
     * @return the interpretation, {@link #OTHER} when unknown
     */
    public static TiffPhotometric of(int code) {
        for (var value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        return OTHER;
    }
}
