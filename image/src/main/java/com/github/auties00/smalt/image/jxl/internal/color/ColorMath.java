/* Ports jxl-oxide 0.12.6 (crates/jxl-color: ciexyz.rs). Copyright Wonwoo Choi and the jxl-oxide contributors;
   MIT License. Modified for Smalt. */
package com.github.auties00.smalt.image.jxl.internal.color;

/**
 * Holds the colorimetric matrices of the JPEG XL color conversions: RGB to XYZ from primaries
 * and a white point, and Bradford chromatic adaptation.
 *
 * <p>Matrices are 3x3, row-major. Every method is a pure function of its arguments.
 */
public final class ColorMath {
    /**
     * The Bradford cone response matrix.
     */
    private static final double[] BRADFORD = {0.8951, 0.2664, -0.1614, -0.7502, 1.7135, 0.0367, 0.0389, -0.0685, 1.0296};

    /**
     * The inverse of {@link #BRADFORD}.
     */
    private static final double[] BRADFORD_INV = {0.9869929, -0.1470543, 0.1599627, 0.4323053, 0.5183603, 0.0492912,
            -0.0085287, 0.0400428, 0.9684867};

    /**
     * Prevents instantiation.
     */
    private ColorMath() {
        throw new AssertionError();
    }

    /**
     * Multiplies two matrices.
     *
     * @param a the left matrix
     * @param b the right matrix
     * @return {@code a * b}
     */
    public static double[] multiply(double[] a, double[] b) {
        var out = new double[9];
        for (var r = 0; r < 3; r++) {
            for (var c = 0; c < 3; c++) {
                out[r * 3 + c] = a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c];
            }
        }
        return out;
    }

    /**
     * Multiplies a matrix by a vector.
     *
     * @param m the matrix
     * @param v the vector
     * @return {@code m * v}
     */
    public static double[] apply(double[] m, double[] v) {
        return new double[]{
                m[0] * v[0] + m[1] * v[1] + m[2] * v[2],
                m[3] * v[0] + m[4] * v[1] + m[5] * v[2],
                m[6] * v[0] + m[7] * v[1] + m[8] * v[2],
        };
    }

    /**
     * Inverts a matrix.
     *
     * @param m the matrix
     * @return the inverse
     */
    public static double[] invert(double[] m) {
        var det = m[0] * (m[4] * m[8] - m[5] * m[7]) + m[1] * (m[5] * m[6] - m[3] * m[8])
                + m[2] * (m[3] * m[7] - m[4] * m[6]);
        return new double[]{
                (m[4] * m[8] - m[5] * m[7]) / det, (m[7] * m[2] - m[8] * m[1]) / det,
                (m[1] * m[5] - m[2] * m[4]) / det, (m[5] * m[6] - m[3] * m[8]) / det,
                (m[8] * m[0] - m[6] * m[2]) / det, (m[2] * m[3] - m[0] * m[5]) / det,
                (m[3] * m[7] - m[4] * m[6]) / det, (m[6] * m[1] - m[7] * m[0]) / det,
                (m[0] * m[4] - m[1] * m[3]) / det,
        };
    }

    /**
     * Converts a chromaticity to XYZ with a luminance of one.
     *
     * @param xy the chromaticity
     * @return the XYZ vector
     */
    public static double[] whiteXyz(double[] xy) {
        return new double[]{xy[0] / xy[1], 1.0, (1.0 - xy[0]) / xy[1] - 1.0};
    }

    /**
     * Returns the matrix that maps linear RGB with given primaries and white to XYZ.
     *
     * @param primaries the primaries as {@code xr, yr, xg, yg, xb, yb}
     * @param white the white chromaticity
     * @return the matrix
     */
    public static double[] rgbToXyz(double[] primaries, double[] white) {
        var p = new double[]{
                primaries[0], primaries[2], primaries[4],
                primaries[1], primaries[3], primaries[5],
                1 - primaries[0] - primaries[1], 1 - primaries[2] - primaries[3], 1 - primaries[4] - primaries[5],
        };
        var mul = apply(invert(p), whiteXyz(white));
        for (var i = 0; i < 9; i++) {
            p[i] *= mul[i % 3];
        }
        return p;
    }

    /**
     * Returns the Bradford matrix that adapts XYZ from one white to another.
     *
     * @param from the source white chromaticity
     * @param to the target white chromaticity
     * @return the matrix, the identity when the whites are equal
     */
    public static double[] adapt(double[] from, double[] to) {
        var fromXyz = whiteXyz(from);
        var toXyz = whiteXyz(to);
        if (fromXyz[0] == toXyz[0] && fromXyz[2] == toXyz[2]) {
            return new double[]{1, 0, 0, 0, 1, 0, 0, 0, 1};
        }
        var fromLms = apply(BRADFORD, fromXyz);
        var toLms = apply(BRADFORD, toXyz);
        var scaled = new double[9];
        for (var i = 0; i < 9; i++) {
            scaled[i] = BRADFORD[i] * (toLms[i / 3] / fromLms[i / 3]);
        }
        return multiply(BRADFORD_INV, scaled);
    }
}
