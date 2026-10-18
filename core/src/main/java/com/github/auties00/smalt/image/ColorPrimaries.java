package com.github.auties00.smalt.image;

import java.util.Optional;

/**
 * Enumerates the color primaries of a picture, with the code points of ITU-T H.273 (the
 * {@code colour_primaries} field of H.264, H.265, VP9 and AV1 streams).
 *
 * <p>{@link #red()}, {@link #green()}, {@link #blue()} and {@link #white()} give the CIE 1931
 * chromaticities H.273 defines for each set of primaries; they are empty for {@link #UNSPECIFIED}
 * and for {@link #SMPTE428}, whose primaries are the CIE XYZ axes rather than chromaticities.
 *
 * <p>Instances are immutable and thread-safe.
 */
public enum ColorPrimaries {
    /**
     * ITU-R BT.709 and sRGB (code point 1).
     */
    BT709(1),

    /**
     * Not specified by the stream (code point 2).
     */
    UNSPECIFIED(2),

    /**
     * ITU-R BT.470 System M (code point 4).
     */
    BT470M(4),

    /**
     * ITU-R BT.470 System B/G, the BT.601 625-line primaries (code point 5).
     */
    BT470BG(5),

    /**
     * SMPTE 170M, the BT.601 525-line primaries (code point 6).
     */
    SMPTE170M(6),

    /**
     * SMPTE 240M (code point 7).
     */
    SMPTE240M(7),

    /**
     * Generic film (code point 8).
     */
    FILM(8),

    /**
     * ITU-R BT.2020 and BT.2100 (code point 9).
     */
    BT2020(9),

    /**
     * SMPTE ST 428-1 CIE XYZ (code point 10).
     */
    SMPTE428(10),

    /**
     * SMPTE RP 431-2 DCI-P3 (code point 11).
     */
    SMPTE431(11),

    /**
     * SMPTE EG 432-1 Display P3 (code point 12).
     */
    SMPTE432(12),

    /**
     * EBU Tech 3213-E (code point 22).
     */
    EBU3213(22);

    /**
     * The H.273 code point.
     */
    private final int code;

    /**
     * Constructs a constant.
     *
     * @param code the H.273 code point
     */
    ColorPrimaries(int code) {
        this.code = code;
    }

    /**
     * Returns the H.273 code point.
     *
     * @return the code point
     */
    public int code() {
        return code;
    }

    /**
     * {@return the chromaticity of the red primary, or empty for {@link #UNSPECIFIED} and
     * {@link #SMPTE428}}
     */
    public Optional<Chromaticity> red() {
        return chromaticity(0);
    }

    /**
     * {@return the chromaticity of the green primary, or empty for {@link #UNSPECIFIED} and
     * {@link #SMPTE428}}
     */
    public Optional<Chromaticity> green() {
        return chromaticity(1);
    }

    /**
     * {@return the chromaticity of the blue primary, or empty for {@link #UNSPECIFIED} and
     * {@link #SMPTE428}}
     */
    public Optional<Chromaticity> blue() {
        return chromaticity(2);
    }

    /**
     * {@return the chromaticity of the white point, or empty for {@link #UNSPECIFIED} and
     * {@link #SMPTE428}}
     */
    public Optional<Chromaticity> white() {
        return chromaticity(3);
    }

    /**
     * Returns one chromaticity of the H.273 table.
     *
     * @param index 0 for red, 1 for green, 2 for blue, 3 for the white point
     * @return the chromaticity, or empty when these primaries define none
     */
    private Optional<Chromaticity> chromaticity(int index) {
        var table = switch (this) {
            case BT709 -> new double[]{0.64, 0.33, 0.30, 0.60, 0.15, 0.06, 0.3127, 0.3290};
            case BT470M -> new double[]{0.67, 0.33, 0.21, 0.71, 0.14, 0.08, 0.310, 0.316};
            case BT470BG -> new double[]{0.64, 0.33, 0.29, 0.60, 0.15, 0.06, 0.3127, 0.3290};
            case SMPTE170M, SMPTE240M -> new double[]{0.630, 0.340, 0.310, 0.595, 0.155, 0.070, 0.3127, 0.3290};
            case FILM -> new double[]{0.681, 0.319, 0.243, 0.692, 0.145, 0.049, 0.310, 0.316};
            case BT2020 -> new double[]{0.708, 0.292, 0.170, 0.797, 0.131, 0.046, 0.3127, 0.3290};
            case SMPTE431 -> new double[]{0.680, 0.320, 0.265, 0.690, 0.150, 0.060, 0.314, 0.351};
            case SMPTE432 -> new double[]{0.680, 0.320, 0.265, 0.690, 0.150, 0.060, 0.3127, 0.3290};
            case EBU3213 -> new double[]{0.630, 0.340, 0.295, 0.605, 0.155, 0.077, 0.3127, 0.3290};
            case UNSPECIFIED, SMPTE428 -> null;
        };
        return table == null ? Optional.empty()
                : Optional.of(new Chromaticity(table[index * 2], table[index * 2 + 1]));
    }

    /**
     * Returns the constant for an H.273 code point.
     *
     * @param code the code point read from a stream
     * @return the matching constant, or {@link #UNSPECIFIED} for reserved or unknown values
     */
    public static ColorPrimaries of(int code) {
        for (var value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        return UNSPECIFIED;
    }
}
