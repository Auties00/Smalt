package com.github.auties00.smalt.image;

import com.github.auties00.smalt.internal.color.LumaWeights;

import java.util.OptionalDouble;

/**
 * Enumerates the matrix coefficients that derive Y'CbCr from R'G'B', with the code points of
 * ITU-T H.273 (the {@code matrix_coefficients} field of H.264, H.265, VP9 and AV1 streams).
 *
 * <p>This enum may gain constants in later releases; a {@code switch} over it needs a {@code default} branch.
 */
public enum MatrixCoefficients {
    /**
     * Identity: the planes carry G, B and R directly (code point 0).
     */
    IDENTITY(0),

    /**
     * ITU-R BT.709 (code point 1).
     */
    BT709(1),

    /**
     * Not specified by the stream (code point 2); Smalt converts it as BT.601 (see {@link #effective()}).
     */
    UNSPECIFIED(2),

    /**
     * US FCC 73.682 (code point 4).
     */
    FCC(4),

    /**
     * ITU-R BT.470 System B/G, numerically identical to BT.601 (code point 5).
     */
    BT470BG(5),

    /**
     * SMPTE 170M, the BT.601 525-line matrix (code point 6).
     */
    BT601(6),

    /**
     * SMPTE 240M (code point 7).
     */
    SMPTE240M(7),

    /**
     * YCgCo (code point 8).
     */
    YCGCO(8),

    /**
     * ITU-R BT.2020 non-constant luminance (code point 9).
     */
    BT2020_NCL(9),

    /**
     * ITU-R BT.2020 constant luminance (code point 10).
     */
    BT2020_CL(10),

    /**
     * SMPTE ST 2085 Y'D'zD'x (code point 11).
     */
    SMPTE2085(11),

    /**
     * Chromaticity-derived non-constant luminance (code point 12).
     */
    CHROMA_DERIVED_NCL(12),

    /**
     * Chromaticity-derived constant luminance (code point 13).
     */
    CHROMA_DERIVED_CL(13),

    /**
     * ITU-R BT.2100 ICtCp (code point 14).
     */
    ICTCP(14),

    /**
     * IPT-C2, the SMPTE IPT-PQ-C2 representation (code point 15).
     */
    IPT_C2(15),

    /**
     * YCgCo-Re, the reversible YCgCo-R transform of R'G'B' samples two bits shallower than the Y'CbCr samples
     * (code point 16).
     */
    YCGCO_RE(16),

    /**
     * YCgCo-Ro, the reversible YCgCo-R transform of R'G'B' samples one bit shallower than the Y'CbCr samples
     * (code point 17).
     */
    YCGCO_RO(17);

    /**
     * The H.273 code point.
     */
    private final int code;

    /**
     * Constructs a constant.
     *
     * @param code the H.273 code point
     */
    MatrixCoefficients(int code) {
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
     * Returns the red weight {@code Kr} of the luma equation {@code Y' = Kr R' + (1 - Kr - Kb) G' + Kb B'}.
     *
     * <p>The weight is defined for the matrices built on that equation: {@link #BT709},
     * {@link #FCC}, {@link #BT470BG}, {@link #BT601}, {@link #SMPTE240M} and {@link #BT2020_NCL}.
     * It is empty for every other matrix, {@link #UNSPECIFIED} included; {@code effective().kr()}
     * gives the weight Smalt converts an unspecified matrix with, and the chromaticity-derived
     * matrices take their weights from the color primaries.
     *
     * @return the weight, or empty when the matrix does not derive luma from a weighted sum
     */
    public OptionalDouble kr() {
        return LumaWeights.of(this).map(weights -> OptionalDouble.of(weights.kr())).orElse(OptionalDouble.empty());
    }

    /**
     * Returns the blue weight {@code Kb} of the luma equation; see {@link #kr()}.
     *
     * @return the weight, or empty when the matrix does not derive luma from a weighted sum
     */
    public OptionalDouble kb() {
        return LumaWeights.of(this).map(weights -> OptionalDouble.of(weights.kb())).orElse(OptionalDouble.empty());
    }

    /**
     * Returns the matrix that Smalt's conversions between R'G'B' and Y'CbCr apply for this one.
     *
     * <p>{@link #UNSPECIFIED} resolves to {@link #BT601}, the matrix of standard-definition video
     * and JPEG; every other matrix resolves to itself. {@link com.github.auties00.smalt.image.ops.ImageOps},
     * {@link com.github.auties00.smalt.image.ops.YuvConverter} and the codecs that convert pictures
     * between matrices apply this resolution; a codec that resolves an unspecified matrix
     * otherwise documents it in its options.
     *
     * @return {@link #BT601} for {@link #UNSPECIFIED}, otherwise this matrix
     */
    public MatrixCoefficients effective() {
        return this == UNSPECIFIED ? BT601 : this;
    }

    /**
     * Returns the constant for an H.273 code point.
     *
     * @param code the code point read from a stream
     * @return the matching constant, or {@link #UNSPECIFIED} for reserved or unknown values
     */
    public static MatrixCoefficients of(int code) {
        for (var value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        return UNSPECIFIED;
    }
}
