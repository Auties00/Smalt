package com.github.auties00.smalt.internal.color;

import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.MatrixCoefficients;

import java.util.Optional;

/**
 * The red and blue weights of luma that define a non-constant-luminance Y'CbCr matrix, from
 * ITU-T H.273 Table 4.
 *
 * <p>{@code Y' = kr * R' + kg * G' + kb * B'} with {@code kg = 1 - kr - kb}; the chroma rows follow
 * from the same two weights. Instances are immutable and thread-safe.
 *
 * @param kr the red weight, in [0, 1)
 * @param kb the blue weight, in [0, 1)
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
     * <p>The result is empty for every matrix that two fixed weights do not define:
     * {@link MatrixCoefficients#UNSPECIFIED}, which {@link MatrixCoefficients#effective()} resolves;
     * {@link MatrixCoefficients#IDENTITY}, {@link MatrixCoefficients#SMPTE2085} and the YCgCo matrices,
     * which are not luma-weighted; the constant-luminance and chromaticity-derived matrices, which need the
     * transfer function or the primaries (see {@link #of(MatrixCoefficients, ColorPrimaries)}); and
     * {@link MatrixCoefficients#ICTCP} and {@link MatrixCoefficients#IPT_C2}.
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
                 ICTCP, IPT_C2, YCGCO_RE, YCGCO_RO -> null;
        });
    }

    /**
     * Returns the weights of a matrix with the primaries it is used with.
     *
     * <p>The chromaticity-derived matrices take the weights of {@link #of(ColorPrimaries)}; the BT.2020
     * constant-luminance matrix takes {@link #BT2020}; every other matrix gives the result of
     * {@link #of(MatrixCoefficients)}.
     *
     * @param matrix the matrix coefficients
     * @param primaries the color primaries, {@link ColorPrimaries#UNSPECIFIED} meaning BT.709
     * @return the weights, or empty when the matrix is not built on weighted luma
     */
    public static Optional<LumaWeights> of(MatrixCoefficients matrix, ColorPrimaries primaries) {
        return switch (matrix) {
            case CHROMA_DERIVED_NCL, CHROMA_DERIVED_CL -> Optional.of(of(primaries));
            case BT2020_CL -> Optional.of(BT2020);
            default -> of(matrix);
        };
    }

    /**
     * Returns the weights a set of primaries derives (ITU-T H.273 equations 39 to 44): the luminance of
     * each unit primary relative to the white point.
     *
     * <p>The CIE XYZ primaries of {@link ColorPrimaries#SMPTE428} give {@code kr = kb = 0}, luma being Y.
     *
     * @param primaries the color primaries, {@link ColorPrimaries#UNSPECIFIED} meaning BT.709
     * @return the weights
     */
    public static LumaWeights of(ColorPrimaries primaries) {
        var defined = primaries == ColorPrimaries.UNSPECIFIED ? ColorPrimaries.BT709 : primaries;
        var red = defined.red().orElseThrow();
        var green = defined.green().orElseThrow();
        var blue = defined.blue().orElseThrow();
        var white = defined.white().orElseThrow();
        double xr = red.x(), yr = red.y(), zr = 1 - (xr + yr);
        double xg = green.x(), yg = green.y(), zg = 1 - (xg + yg);
        double xb = blue.x(), yb = blue.y(), zb = 1 - (xb + yb);
        double xw = white.x(), yw = white.y(), zw = 1 - (xw + yw);
        var denominator = yw * (xr * (yg * zb - yb * zg) + xg * (yb * zr - yr * zb) + xb * (yr * zg - yg * zr));
        var kr = yr * (xw * (yg * zb - yb * zg) + yw * (xb * zg - xg * zb) + zw * (xg * yb - xb * yg)) / denominator;
        var kb = yb * (xw * (yr * zg - yg * zr) + yw * (xg * zr - xr * zg) + zw * (xr * yg - xg * yr)) / denominator;
        return new LumaWeights(kr, kb);
    }

    /**
     * {@return the green weight, {@code 1 - kr - kb}}
     */
    public double kg() {
        return 1 - kr - kb;
    }
}
