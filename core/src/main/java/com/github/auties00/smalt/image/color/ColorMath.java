package com.github.auties00.smalt.image.color;

/**
 * Holds the 3x3 matrix, white point and CIE L*a*b* arithmetic of color management.
 *
 * <p>Matrices are {@code double[9]} arrays in row-major order and vectors are {@code double[3]}
 * arrays. CIE XYZ values are relative to a luminance of {@code 1.0}; the profile connection space
 * illuminant is the ICC D50 white {@code (0.9642, 1.0, 0.8249)}.
 *
 * @implNote This implementation follows Little CMS 2.17 ({@code cmsmtrx.c}, {@code cmspcs.c} and
 *           {@code cmswtpnt.c}): the same matrix inversion, the same identity and singularity
 *           tolerances, the same Bradford cone matrix and the same piecewise L*a*b* functions, so
 *           that transforms built on them agree with Little CMS to the last bit wherever the
 *           arithmetic is the same.
 */
final class ColorMath {
    /**
     * The X component of the D50 illuminant.
     */
    static final double D50_X = 0.9642;

    /**
     * The Y component of the D50 illuminant.
     */
    static final double D50_Y = 1.0;

    /**
     * The Z component of the D50 illuminant.
     */
    static final double D50_Z = 0.8249;

    /**
     * The largest value the 16-bit XYZ encoding represents: {@code 1 + 32767 / 32768}.
     */
    static final double MAX_ENCODEABLE_XYZ = 1.0 + 32767.0 / 32768.0;

    /**
     * The determinant below which a matrix counts as singular, and the tolerance of several
     * degenerate-parameter checks.
     */
    static final double DETERMINANT_TOLERANCE = 0.0001;

    /**
     * The Bradford cone response matrix.
     */
    private static final double[] BRADFORD = {
            0.8951, 0.2664, -0.1614,
            -0.7502, 1.7135, 0.0367,
            0.0389, -0.0685, 1.0296
    };

    /**
     * Prevents instantiation.
     */
    private ColorMath() {
        throw new AssertionError();
    }

    /**
     * Returns the D50 illuminant.
     *
     * @return a new vector holding the D50 white point
     */
    static double[] d50() {
        return new double[]{D50_X, D50_Y, D50_Z};
    }

    /**
     * Returns the identity matrix.
     *
     * @return a new identity matrix
     */
    static double[] identity() {
        return new double[]{1, 0, 0, 0, 1, 0, 0, 0, 1};
    }

    /**
     * Multiplies two matrices.
     *
     * @param a the left matrix
     * @param b the right matrix
     * @return the product {@code a * b}
     */
    static double[] multiply(double[] a, double[] b) {
        var r = new double[9];
        for (var i = 0; i < 3; i++) {
            for (var j = 0; j < 3; j++) {
                r[i * 3 + j] = a[i * 3] * b[j] + a[i * 3 + 1] * b[3 + j] + a[i * 3 + 2] * b[6 + j];
            }
        }
        return r;
    }

    /**
     * Inverts a matrix.
     *
     * @param a the matrix
     * @return the inverse, or {@code null} when the determinant is below
     *         {@link #DETERMINANT_TOLERANCE}
     */
    static double[] invert(double[] a) {
        var c0 = a[4] * a[8] - a[5] * a[7];
        var c1 = -a[3] * a[8] + a[5] * a[6];
        var c2 = a[3] * a[7] - a[4] * a[6];
        var det = a[0] * c0 + a[1] * c1 + a[2] * c2;
        if (Math.abs(det) < DETERMINANT_TOLERANCE) {
            return null;
        }
        return new double[]{
                c0 / det,
                (a[2] * a[7] - a[1] * a[8]) / det,
                (a[1] * a[5] - a[2] * a[4]) / det,
                c1 / det,
                (a[0] * a[8] - a[2] * a[6]) / det,
                (a[2] * a[3] - a[0] * a[5]) / det,
                c2 / det,
                (a[1] * a[6] - a[0] * a[7]) / det,
                (a[0] * a[4] - a[1] * a[3]) / det
        };
    }

    /**
     * Applies a matrix to a vector.
     *
     * @param a the matrix
     * @param v the vector
     * @return the product {@code a * v}
     */
    static double[] apply(double[] a, double[] v) {
        return new double[]{
                a[0] * v[0] + a[1] * v[1] + a[2] * v[2],
                a[3] * v[0] + a[4] * v[1] + a[5] * v[2],
                a[6] * v[0] + a[7] * v[1] + a[8] * v[2]
        };
    }

    /**
     * Returns whether every element of a matrix is within {@code 1 / 65535} of the identity.
     *
     * @param a the matrix
     * @return {@code true} if the matrix is the identity within that tolerance
     */
    static boolean isIdentity(double[] a) {
        for (var i = 0; i < 9; i++) {
            var expected = i % 4 == 0 ? 1.0 : 0.0;
            if (Math.abs(a[i] - expected) >= 1.0 / 65535.0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the Bradford matrix that adapts colors seen under one white to another.
     *
     * @param from the source white, XYZ
     * @param to   the destination white, XYZ
     * @return the adaptation matrix, or {@code null} when a white is degenerate
     */
    static double[] bradford(double[] from, double[] to) {
        var inverse = invert(BRADFORD);
        if (inverse == null) {
            return null;
        }
        var source = apply(BRADFORD, from);
        var destination = apply(BRADFORD, to);
        if (Math.abs(source[0]) < DETERMINANT_TOLERANCE || Math.abs(source[1]) < DETERMINANT_TOLERANCE
                || Math.abs(source[2]) < DETERMINANT_TOLERANCE) {
            return null;
        }
        var cone = new double[]{
                destination[0] / source[0], 0, 0,
                0, destination[1] / source[1], 0,
                0, 0, destination[2] / source[2]
        };
        return multiply(inverse, multiply(cone, BRADFORD));
    }

    /**
     * Converts a chromaticity of luminance {@code 1.0} to XYZ.
     *
     * @param x the x chromaticity coordinate
     * @param y the y chromaticity coordinate, non-zero
     * @return the XYZ value
     */
    static double[] xyToXyz(double x, double y) {
        return new double[]{x / y, 1.0, (1.0 - x - y) / y};
    }

    /**
     * Builds the matrix that converts linear RGB to D50 XYZ from primaries and a white point,
     * adapting the white to D50 with the Bradford transform.
     *
     * @param red   the red chromaticity, {@code {x, y}}
     * @param green the green chromaticity, {@code {x, y}}
     * @param blue  the blue chromaticity, {@code {x, y}}
     * @param white the white chromaticity, {@code {x, y}}
     * @return the matrix whose columns are the D50-adapted XYZ of the primaries, or {@code null}
     *         when the primaries are degenerate
     */
    static double[] rgbToD50(double[] red, double[] green, double[] blue, double[] white) {
        var primaries = new double[]{
                red[0], green[0], blue[0],
                red[1], green[1], blue[1],
                1 - red[0] - red[1], 1 - green[0] - green[1], 1 - blue[0] - blue[1]
        };
        var inverse = invert(primaries);
        if (inverse == null || white[1] == 0) {
            return null;
        }
        var whiteXyz = xyToXyz(white[0], white[1]);
        var coefficients = apply(inverse, whiteXyz);
        var matrix = new double[9];
        for (var row = 0; row < 3; row++) {
            for (var column = 0; column < 3; column++) {
                matrix[row * 3 + column] = coefficients[column] * primaries[row * 3 + column];
            }
        }
        var adaptation = bradford(whiteXyz, d50());
        return adaptation == null ? null : multiply(adaptation, matrix);
    }

    /**
     * Applies the forward L*a*b* companding function.
     *
     * @param t a white-relative tristimulus ratio
     * @return the companded value
     */
    static double labForward(double t) {
        var limit = (24.0 / 116.0) * (24.0 / 116.0) * (24.0 / 116.0);
        return t <= limit ? (841.0 / 108.0) * t + (16.0 / 116.0) : StrictMath.pow(t, 1.0 / 3.0);
    }

    /**
     * Applies the inverse L*a*b* companding function.
     *
     * @param t a companded value
     * @return the white-relative tristimulus ratio
     */
    static double labInverse(double t) {
        return t <= 24.0 / 116.0 ? (108.0 / 841.0) * (t - (16.0 / 116.0)) : t * t * t;
    }

    /**
     * Converts D50 XYZ to CIE L*a*b*.
     *
     * @param xyz the XYZ value
     * @return the L*a*b* value
     */
    static double[] xyzToLab(double[] xyz) {
        var fx = labForward(xyz[0] / D50_X);
        var fy = labForward(xyz[1] / D50_Y);
        var fz = labForward(xyz[2] / D50_Z);
        return new double[]{116.0 * fy - 16.0, 500.0 * (fx - fy), 200.0 * (fy - fz)};
    }

    /**
     * Converts CIE L*a*b* to D50 XYZ.
     *
     * @param lab the L*a*b* value
     * @return the XYZ value
     */
    static double[] labToXyz(double[] lab) {
        var y = (lab[0] + 16.0) / 116.0;
        var x = y + 0.002 * lab[1];
        var z = y - 0.005 * lab[2];
        return new double[]{labInverse(x) * D50_X, labInverse(y) * D50_Y, labInverse(z) * D50_Z};
    }

    /**
     * Rounds a value to a saturated 16-bit word.
     *
     * @implNote This implementation reproduces Little CMS's {@code _cmsQuickSaturateWord}, whose
     *           floor adds {@code 1.5 * 2^36} to the value: the sum rounds the value to the nearest
     *           multiple of {@code 2^-16} before the fraction is dropped, so a value within
     *           {@code 2^-17} below an integer rounds up to it.
     *
     * @param value the value in 16-bit units
     * @return {@code value} rounded half up and clamped to {@code 0..65535}
     */
    static int saturateWord(double value) {
        var d = value + 0.5;
        if (d <= 0) {
            return 0;
        }
        if (d >= 65535.0) {
            return 0xFFFF;
        }
        return quickFloor(d - 32767.0) + 32767;
    }

    /**
     * Floors a value after rounding it to the nearest multiple of {@code 2^-16}.
     *
     * @param value the value, within {@code -32767..32767}
     * @return the floor of the rounded value
     */
    static int quickFloor(double value) {
        var magic = 68719476736.0 * 1.5;
        return (int) Math.floor((value + magic) - magic);
    }

    /**
     * Reduces a 16-bit sample to 8 bits with rounding.
     *
     * @param word the 16-bit sample
     * @return the 8-bit sample
     */
    static int wordToByte(int word) {
        return (int) (((word & 0xFFFFL) * 65281L + 8388608L) >>> 24) & 0xFF;
    }
}
