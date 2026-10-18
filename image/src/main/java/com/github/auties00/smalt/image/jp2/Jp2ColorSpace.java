package com.github.auties00.smalt.image.jp2;

/**
 * Enumerates the color spaces a JPEG 2000 file can declare: the enumerated color spaces of
 * ISO/IEC 15444-1 Table I.10 and ISO/IEC 15444-2 Table M.25, an embedded ICC profile, or none.
 *
 * <p>Constants may be added in minor releases, so a {@code switch} over this type needs a
 * {@code default} branch.
 */
public enum Jp2ColorSpace {
    /**
     * No color space is declared: a raw codestream, or a file without a color specification
     * this decoder understands.
     */
    UNSPECIFIED(-1),

    /**
     * Bi-level (enumerated value 0).
     */
    BILEVEL(0),

    /**
     * Y'CbCr per Rec. ITU-R BT.709 (enumerated value 1).
     */
    YCBCR_709(1),

    /**
     * Y'CbCr per Rec. ITU-R BT.601 with 625 lines (enumerated value 3).
     */
    YCBCR_601_625(3),

    /**
     * Y'CbCr per Rec. ITU-R BT.601 with 525 lines (enumerated value 4).
     */
    YCBCR_601_525(4),

    /**
     * Kodak PhotoYCC (enumerated value 9).
     */
    PHOTO_YCC(9),

    /**
     * Cyan, magenta and yellow (enumerated value 11).
     */
    CMY(11),

    /**
     * Cyan, magenta, yellow and black (enumerated value 12).
     */
    CMYK(12),

    /**
     * Y'CbCr and black (enumerated value 13).
     */
    YCCK(13),

    /**
     * CIE 1976 L*a*b* (enumerated value 14).
     */
    CIELAB(14),

    /**
     * Bi-level with inverted polarity (enumerated value 15).
     */
    BILEVEL_INVERTED(15),

    /**
     * sRGB, IEC 61966-2-1 (enumerated value 16).
     */
    SRGB(16),

    /**
     * Grayscale with the sRGB transfer curve (enumerated value 17).
     */
    GRAYSCALE(17),

    /**
     * sYCC, the Y'CbCr encoding of sRGB of IEC 61966-2-1 Amd. 1 (enumerated value 18).
     */
    SYCC(18),

    /**
     * CIECAM02 J*a*b* (enumerated value 19).
     */
    CIEJAB(19),

    /**
     * e-sRGB (enumerated value 20).
     */
    E_SRGB(20),

    /**
     * ROMM-RGB, ISO 22028-2 (enumerated value 21).
     */
    ROMM_RGB(21),

    /**
     * Y'PbPr with 1125 lines at 60 Hz (enumerated value 22).
     */
    YPBPR_1125_60(22),

    /**
     * Y'PbPr with 1250 lines at 50 Hz (enumerated value 23).
     */
    YPBPR_1250_50(23),

    /**
     * e-sYCC (enumerated value 24).
     */
    E_SYCC(24),

    /**
     * The color space of an embedded ICC profile.
     */
    ICC(-2);

    /**
     * The {@code EnumCS} value, or a negative value for the constants that are not enumerated.
     */
    private final int value;

    /**
     * Constructs a constant.
     *
     * @param value the {@code EnumCS} value
     */
    Jp2ColorSpace(int value) {
        this.value = value;
    }

    /**
     * Returns the constant of an enumerated color space code.
     *
     * @param code the {@code EnumCS} value of a color specification box
     * @return the color space, or {@link #UNSPECIFIED} for a code this enumeration does not name
     */
    public static Jp2ColorSpace of(int code) {
        for (var space : values()) {
            if (space.value == code && code >= 0) {
                return space;
            }
        }
        return UNSPECIFIED;
    }

    /**
     * {@return the {@code EnumCS} code of this color space, or -1 for {@link #UNSPECIFIED} and
     * {@link #ICC}, which a color specification box does not enumerate}
     */
    public int code() {
        return Math.max(value, -1);
    }
}
