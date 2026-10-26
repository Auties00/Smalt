/* Ports libjpeg-turbo (jccolor.c, jdcolor.c, the full-range BT.601 constants). Copyright the Independent JPEG Group
 * and D. R. Commander; IJG License and the libjpeg-turbo license. Modified for Smalt. */
package com.github.auties00.smalt.image.ops;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.internal.color.LumaWeights;

/**
 * Holds the fixed-point coefficients that convert between 8-bit R'G'B' and 8-bit Y'CbCr for one
 * matrix and range.
 *
 * <p>The real-valued forward transform is {@code yuv = M * rgb + o}, where {@code rgb} are the
 * 8-bit sRGB code values (so {@code E' = rgb / 255}), {@code M} is the 3x3 matrix of ITU-T H.273
 * for the matrix coefficients scaled by the quantization of the range, and {@code o} holds the
 * quantization offsets ({@code 16} for limited-range luma, {@code 128} for color differences).
 * Every coefficient is stored with {@value #BITS} fractional bits:
 * <ul>
 *   <li>forward: {@code Y = clamp((fy[0] * R + fy[1] * G + fy[2] * B + fk[0]) >> 16)}, and the
 *       same with {@code fu}/{@code fv} and {@code fk[1]}/{@code fk[2]} for Cb and Cr; the
 *       color-difference constants round half down (libjpeg's {@code jccolor.c} convention), so a
 *       full-range pure blue maps to {@code Cb = 255}, not an overflowing {@code 256};</li>
 *   <li>inverse: {@code R = clamp((ir[0] * Y + ir[1] * Cb + ir[2] * Cr + ik[0]) >> 16)}, and the
 *       same with {@code ig}/{@code ib} and {@code ik[1]}/{@code ik[2]}, rounding half up.</li>
 * </ul>
 * Coefficients are rounded independently, except that the middle (green) coefficient of each
 * forward row is adjusted so every row sums exactly to its rounded real sum. This keeps grays gray:
 * {@code R = G = B} maps to {@code Cb = Cr = 128} and the full-range luma of a gray equals its
 * value. Full-range BT.601 (JPEG) instead uses libjpeg-turbo's {@code jccolor.c} and
 * {@code jdcolor.c} constants, which are the fixed-point roundings of the five-decimal
 * coefficients published in the JFIF specification (for example {@code 0.34414} rather than the
 * exact {@code 0.344136}), so conversions match libjpeg-turbo bit for bit; they differ from the
 * exact coefficients by at most one unit in the sixteenth fractional bit.
 *
 * <p>A matrix is resolved with {@link MatrixCoefficients#effective()}, so
 * {@link MatrixCoefficients#UNSPECIFIED} is converted as {@link MatrixCoefficients#BT601}. Only the
 * matrices defined by two fixed luma weights, YCgCo and the identity have coefficients; the others, which
 * {@link CicpColor#handles}, convert in {@link CicpColor}.
 *
 * @implNote This implementation derives YCgCo and identity (GBR) samples from the linear formula with a single
 * rounding. ITU-T H.273 first quantizes R, G and B to the sample range and then applies the integer YCgCo transform,
 * which rounds twice; the two agree within one code value.
 */
final class ColorMatrix {
    /**
     * The number of fractional bits of every coefficient.
     */
    static final int BITS = 16;

    /**
     * The fixed-point one half used for rounding.
     */
    private static final int HALF = 1 << (BITS - 1);

    private static final int RANGES = ColorRange.values().length;

    /**
     * The coefficients built so far, by matrix and range. Instances are immutable, so two threads
     * racing on an empty entry each build an equal instance.
     */
    private static final ColorMatrix[] BUILT = new ColorMatrix[MatrixCoefficients.values().length * RANGES];

    /**
     * The forward coefficients of luma, multiplying R, G and B.
     */
    final int[] fy;

    /**
     * The forward coefficients of Cb, multiplying R, G and B.
     */
    final int[] fu;

    /**
     * The forward coefficients of Cr, multiplying R, G and B.
     */
    final int[] fv;

    /**
     * The forward constants of Y, Cb and Cr: the offsets scaled to fixed point plus the rounding
     * bias.
     */
    final int[] fk;

    /**
     * The inverse coefficients of R, multiplying Y, Cb and Cr.
     */
    final int[] ir;

    /**
     * The inverse coefficients of G, multiplying Y, Cb and Cr.
     */
    final int[] ig;

    /**
     * The inverse coefficients of B, multiplying Y, Cb and Cr.
     */
    final int[] ib;

    /**
     * The inverse constants of R, G and B: minus the offsets times the coefficients, plus the
     * rounding bias.
     */
    final int[] ik;

    /**
     * The inverse coefficients copied into fields, which the scalar conversion reads for every
     * pixel without array accesses.
     */
    final ScalarColorKernel.Converter converter;

    /**
     * Whether the inverse has the standard form: one luma coefficient shared by R, G and B, R
     * independent of Cb and B independent of Cr.
     */
    final boolean standard;

    /**
     * The coefficient that expands luma to a gray level for monochrome pictures.
     */
    final int grayScale;

    /**
     * The constant of the monochrome expansion: minus the luma offset times {@link #grayScale},
     * plus the rounding bias.
     */
    final int grayConstant;

    /**
     * The quantization offsets of Y, Cb and Cr.
     */
    final int[] offsets;

    /**
     * The quantization scales of Y, Cb and Cr: the code-value span of a unit color value
     * ({@code 255} in full range, {@code 219} for limited-range luma, {@code 224} for
     * limited-range color differences, {@code 219} for every component of limited-range YCgCo
     * and identity).
     */
    final double[] scales;

    /**
     * The real-valued forward matrix, rows Y, Cb, Cr and columns R, G, B, applied to 8-bit code
     * values.
     */
    final double[][] forward;

    /**
     * The inverse of {@link #forward}, rows R, G, B and columns Y, Cb, Cr.
     */
    final double[][] inverse;

    /**
     * The matrix these coefficients implement.
     */
    final MatrixCoefficients matrix;

    /**
     * The range these coefficients implement.
     */
    final ColorRange range;

    /**
     * Computes the coefficients of a matrix and range.
     *
     * @param matrix the matrix coefficients, already resolved, with {@link MatrixCoefficients#BT470BG} mapped to
     * {@link MatrixCoefficients#BT601}
     * @param range the quantization range
     * @param m the real-valued forward matrix, rows Y, Cb, Cr and columns R, G, B, applied to 8-bit code values
     * @param o the quantization offsets of Y, Cb and Cr
     * @param scales the quantization scales of Y, Cb and Cr
     */
    private ColorMatrix(MatrixCoefficients matrix, ColorRange range, double[][] m, int[] o, double[] scales) {
        this.matrix = matrix;
        this.range = range;
        this.offsets = o;
        this.scales = scales;
        var jpeg = matrix == MatrixCoefficients.BT601 && range == ColorRange.FULL;
        this.fy = jpeg ? new int[]{19595, 38470, 7471} : forwardRow(m[0]);
        this.fu = jpeg ? new int[]{-11059, -21709, 32768} : forwardRow(m[1]);
        this.fv = jpeg ? new int[]{32768, -27439, -5329} : forwardRow(m[2]);
        this.fk = new int[]{
                (o[0] << BITS) + HALF,
                (o[1] << BITS) + HALF - 1,
                (o[2] << BITS) + HALF - 1
        };
        this.forward = m;
        var inverse = invert(m);
        this.inverse = inverse;
        this.ir = jpeg ? new int[]{65536, 0, 91881} : fixedRow(inverse[0]);
        this.ig = jpeg ? new int[]{65536, -22554, -46802} : fixedRow(inverse[1]);
        this.ib = jpeg ? new int[]{65536, 116130, 0} : fixedRow(inverse[2]);
        this.ik = new int[]{inverseConstant(ir, o), inverseConstant(ig, o), inverseConstant(ib, o)};
        this.converter = new ScalarColorKernel.Converter(this);
        this.standard = ir[0] == ig[0] && ig[0] == ib[0] && ir[1] == 0 && ib[2] == 0;
        var lumaScale = range == ColorRange.FULL ? 255 : 219;
        this.grayScale = (int) Math.round(255.0 / lumaScale * (1 << BITS));
        this.grayConstant = HALF - grayScale * (range == ColorRange.FULL ? 0 : 16);
    }

    /**
     * Returns the coefficients for the matrix and range of a color description.
     *
     * @param color the color description
     * @return the coefficients
     * @throws MediaException.Unsupported if {@link CicpColor#handles} the matrix
     */
    static ColorMatrix of(ColorInfo color) {
        return of(color.matrix(), color.range());
    }

    /**
     * Returns the coefficients for a matrix and range.
     *
     * @param matrix the matrix coefficients, resolved with {@link MatrixCoefficients#effective()}
     * @param range the quantization range
     * @return the coefficients
     * @throws MediaException.Unsupported if {@link CicpColor#handles} the matrix
     */
    static ColorMatrix of(MatrixCoefficients matrix, ColorRange range) {
        var index = matrix.ordinal() * RANGES + range.ordinal();
        var built = BUILT[index];
        if (built == null) {
            built = build(matrix, range);
            BUILT[index] = built;
        }
        return built;
    }

    /**
     * Builds the coefficients for a matrix and range.
     *
     * @param matrix the matrix coefficients, resolved with {@link MatrixCoefficients#effective()}
     * @param range the quantization range
     * @return the coefficients
     * @throws MediaException.Unsupported if {@link CicpColor#handles} the matrix
     */
    private static ColorMatrix build(MatrixCoefficients matrix, ColorRange range) {
        var resolved = matrix.effective();
        var weights = LumaWeights.of(resolved);
        if (weights.isPresent()) {
            return standard(resolved == MatrixCoefficients.BT470BG ? MatrixCoefficients.BT601 : resolved, range,
                    weights.get());
        }
        var full = range == ColorRange.FULL;
        var lumaScale = (full ? 255.0 : 219.0) / 255.0;
        var lumaOffset = full ? 0 : 16;
        return switch (resolved) {
            case YCGCO -> new ColorMatrix(matrix, range, new double[][]{
                    {0.25 * lumaScale, 0.5 * lumaScale, 0.25 * lumaScale},
                    {-0.25 * lumaScale, 0.5 * lumaScale, -0.25 * lumaScale},
                    {0.5 * lumaScale, 0, -0.5 * lumaScale}
            }, new int[]{lumaOffset, 128, 128}, new double[]{255 * lumaScale, 255 * lumaScale, 255 * lumaScale});
            case IDENTITY -> new ColorMatrix(matrix, range, new double[][]{
                    {0, lumaScale, 0},
                    {0, 0, lumaScale},
                    {lumaScale, 0, 0}
            }, new int[]{lumaOffset, lumaOffset, lumaOffset}, new double[]{255 * lumaScale, 255 * lumaScale, 255 * lumaScale});
            default -> throw new MediaException.Unsupported("matrix " + matrix.name().toLowerCase(),
                    "the " + matrix + " matrix coefficients have no fixed-point coefficients");
        };
    }

    /**
     * Builds the coefficients of a matrix defined by its red and blue luma weights.
     *
     * @param matrix the matrix coefficients
     * @param range the quantization range
     * @param weights the red and blue weights of luma
     * @return the coefficients
     */
    private static ColorMatrix standard(MatrixCoefficients matrix, ColorRange range, LumaWeights weights) {
        var kr = weights.kr();
        var kb = weights.kb();
        var full = range == ColorRange.FULL;
        var lumaScale = (full ? 255.0 : 219.0) / 255.0;
        var chromaScale = (full ? 255.0 : 224.0) / 255.0;
        var kg = weights.kg();
        var cb = 2 * (1 - kb);
        var cr = 2 * (1 - kr);
        return new ColorMatrix(matrix, range, new double[][]{
                {kr * lumaScale, kg * lumaScale, kb * lumaScale},
                {-kr / cb * chromaScale, -kg / cb * chromaScale, 0.5 * chromaScale},
                {0.5 * chromaScale, -kg / cr * chromaScale, -kb / cr * chromaScale}
        }, new int[]{full ? 0 : 16, 128, 128}, new double[]{255 * lumaScale, 255 * chromaScale, 255 * chromaScale});
    }

    /**
     * Rounds one forward row to fixed point, adjusting the green coefficient so the row sums to its
     * rounded real sum.
     *
     * @param row the real coefficients multiplying R, G and B
     * @return the fixed-point coefficients
     */
    private static int[] forwardRow(double[] row) {
        var fixed = fixedRow(row);
        var sum = Math.round((row[0] + row[1] + row[2]) * (1 << BITS));
        fixed[1] = (int) (sum - fixed[0] - fixed[2]);
        return fixed;
    }

    /**
     * Rounds a row of real coefficients to fixed point independently.
     *
     * @param row the real coefficients
     * @return the fixed-point coefficients
     */
    private static int[] fixedRow(double[] row) {
        var fixed = new int[3];
        for (var i = 0; i < 3; i++) {
            fixed[i] = (int) Math.round(row[i] * (1 << BITS));
        }
        return fixed;
    }

    /**
     * Computes the constant of one inverse row from its rounded coefficients.
     *
     * @param row the fixed-point coefficients multiplying Y, Cb and Cr
     * @param offsets the quantization offsets of Y, Cb and Cr
     * @return the constant, including the rounding bias
     */
    private static int inverseConstant(int[] row, int[] offsets) {
        return HALF - row[0] * offsets[0] - row[1] * offsets[1] - row[2] * offsets[2];
    }

    /**
     * Inverts a 3x3 matrix, flushing entries that are zero up to rounding noise to exactly zero.
     *
     * @param m the matrix
     * @return the inverse
     */
    private static double[][] invert(double[][] m) {
        var a = m[0][0];
        var b = m[0][1];
        var c = m[0][2];
        var d = m[1][0];
        var e = m[1][1];
        var f = m[1][2];
        var g = m[2][0];
        var h = m[2][1];
        var i = m[2][2];
        var ca = e * i - f * h;
        var cb = -(d * i - f * g);
        var cc = d * h - e * g;
        var determinant = a * ca + b * cb + c * cc;
        var inverse = new double[][]{
                {ca / determinant, -(b * i - c * h) / determinant, (b * f - c * e) / determinant},
                {cb / determinant, (a * i - c * g) / determinant, -(a * f - c * d) / determinant},
                {cc / determinant, -(a * h - b * g) / determinant, (a * e - b * d) / determinant}
        };
        for (var row : inverse) {
            for (var j = 0; j < 3; j++) {
                if (Math.abs(row[j]) < 1e-9) {
                    row[j] = 0;
                }
            }
        }
        return inverse;
    }
}
