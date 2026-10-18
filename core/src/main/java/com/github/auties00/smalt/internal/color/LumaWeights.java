package com.github.auties00.smalt.internal.color;

import com.github.auties00.smalt.image.MatrixCoefficients;

import java.util.Optional;

/**
 * The red and blue weights of luma that define a non-constant-luminance Y'CbCr matrix, from
 * ITU-T H.273 Table 4.
 *
 * <p>{@code Y' = kr * R' + kg * G' + kb * B'} with {@code kg = 1 - kr - kb}; the chroma rows follow
 * from the same two weights. Instances are immutable and thread-safe.
 *
 * @param kr the red weight, in (0, 1)
 * @param kb the blue weight, in (0, 1)
 */
public record LumaWeights(double kr, double kb) {
    /**
     * The weights of BT.601 (and BT.470 System B and G): 0.299 and 0.114.
     */
    public static final LumaWeights BT601 = new LumaWeights(0.299, 0.114);

    /**
     * The weights of BT.709: 0.2126 and 0.0722.
     */
    public static final LumaWeights BT709 = new LumaWeights(0.2126, 0.0722);

    /**
     * The weights of the FCC 73.682 matrix: 0.30 and 0.11.
     */
    public static final LumaWeights FCC = new LumaWeights(0.30, 0.11);

    /**
     * The weights of SMPTE ST 240: 0.212 and 0.087.
     */
    public static final LumaWeights SMPTE240M = new LumaWeights(0.212, 0.087);

    /**
     * The weights of BT.2020 non-constant luminance: 0.2627 and 0.0593.
     */
    public static final LumaWeights BT2020 = new LumaWeights(0.2627, 0.0593);

    /**
     * Returns the weights of a matrix.
     *
     * <p>The result is empty for every matrix that two weights do not define:
     * {@link MatrixCoefficients#UNSPECIFIED}, which {@link MatrixCoefficients#effective()} resolves;
     * {@link MatrixCoefficients#IDENTITY} and {@link MatrixCoefficients#YCGCO}, which are not
     * luma-weighted; the constant-luminance and chromaticity-derived matrices and
     * {@link MatrixCoefficients#ICTCP}, which need the transfer function or the primaries; and
     * {@link MatrixCoefficients#SMPTE2085}.
     *
     * @param matrix the matrix coefficients
     * @return the weights, or empty when two weights do not define {@code matrix}
     */
    public static Optional<LumaWeights> of(MatrixCoefficients matrix) {
        return Optional.ofNullable(switch (matrix) {
            case BT601, BT470BG -> BT601;
            case BT709 -> BT709;
            case FCC -> FCC;
            case SMPTE240M -> SMPTE240M;
            case BT2020_NCL -> BT2020;
            case IDENTITY, UNSPECIFIED, YCGCO, BT2020_CL, SMPTE2085, CHROMA_DERIVED_NCL, CHROMA_DERIVED_CL,
                 ICTCP -> null;
        });
    }

    /**
     * {@return the green weight, {@code 1 - kr - kb}}
     */
    public double kg() {
        return 1 - kr - kb;
    }
}
