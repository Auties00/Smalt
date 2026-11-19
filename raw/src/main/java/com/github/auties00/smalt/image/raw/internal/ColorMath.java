package com.github.auties00.smalt.image.raw.internal;

import java.util.Arrays;

/**
 * Computes with 3x3 matrices, chromaticities and correlated color temperatures, as the DNG color
 * model needs.
 *
 * <p>Matrices are {@code double[9]} arrays stored row by row.
 *
 * @implNote This implementation converts between chromaticity and correlated color temperature
 * with Robertson's method over the isotemperature lines Wyszecki and Stiles tabulate
 * (Color Science, 2nd edition, table 1(3.11)), and computes the chromaticity of a
 * temperature on the CIE daylight locus above 4000 K and on the Planckian locus below
 * (Kim et al. cubic approximation); the white point conversions use the Bradford cone
 * response matrix.
 */
final class ColorMath {
    /**
     * The chromaticity of D50, the white of the profile connection space.
     */
    static final double[] D50_XY = {0.34567, 0.35850};

    /**
     * The XYZ of D50 with a luminance of 1.
     */
    static final double[] D50_XYZ = {0.9642, 1.0, 0.8249};

    /**
     * The chromaticity of D65.
     */
    static final double[] D65_XY = {0.31271, 0.32902};

    /**
     * The Bradford cone response matrix.
     */
    private static final double[] BRADFORD = {
            0.8951, 0.2664, -0.1614,
            -0.7502, 1.7135, 0.0367,
            0.0389, -0.0685, 1.0296
    };

    /**
     * Robertson's isotemperature lines: reciprocal megakelvin, u, v and the slope of the line.
     */
    private static final double[][] ROBERTSON = {
            {0, 0.18006, 0.26352, -0.24341}, {10, 0.18066, 0.26589, -0.25479}, {20, 0.18133, 0.26846, -0.26876},
            {30, 0.18208, 0.27119, -0.28539}, {40, 0.18293, 0.27407, -0.30470}, {50, 0.18388, 0.27709, -0.32675},
            {60, 0.18494, 0.28021, -0.35156}, {70, 0.18611, 0.28342, -0.37915}, {80, 0.18740, 0.28668, -0.40955},
            {90, 0.18880, 0.28997, -0.44278}, {100, 0.19032, 0.29326, -0.47888}, {125, 0.19462, 0.30141, -0.58204},
            {150, 0.19962, 0.30921, -0.70471}, {175, 0.20525, 0.31647, -0.84901}, {200, 0.21142, 0.32312, -1.0182},
            {225, 0.21807, 0.32909, -1.2168}, {250, 0.22511, 0.33439, -1.4512}, {275, 0.23247, 0.33904, -1.7298},
            {300, 0.24010, 0.34308, -2.0637}, {325, 0.24792, 0.34655, -2.4681}, {350, 0.25591, 0.34951, -2.9641},
            {375, 0.26400, 0.35200, -3.5814}, {400, 0.27218, 0.35407, -4.3633}, {425, 0.28039, 0.35577, -5.3762},
            {450, 0.28863, 0.35714, -6.7262}, {475, 0.29685, 0.35823, -8.5955}, {500, 0.30505, 0.35907, -11.324},
            {525, 0.31320, 0.35968, -15.628}, {550, 0.32129, 0.36011, -23.325}, {575, 0.32931, 0.36038, -40.770},
            {600, 0.33724, 0.36051, -116.45}
    };

    /**
     * Prevents instantiation.
     */
    private ColorMath() {
        throw new AssertionError();
    }

    /**
     * Multiplies two 3x3 matrices.
     *
     * @param a the left matrix
     * @param b the right matrix
     * @return {@code a * b}
     */
    static double[] multiply(double[] a, double[] b) {
        var result = new double[9];
        for (var row = 0; row < 3; row++) {
            for (var column = 0; column < 3; column++) {
                result[row * 3 + column] = a[row * 3] * b[column] + a[row * 3 + 1] * b[3 + column]
                        + a[row * 3 + 2] * b[6 + column];
            }
        }
        return result;
    }

    /**
     * Multiplies a 3x3 matrix by a vector.
     *
     * @param m the matrix
     * @param v the vector
     * @return {@code m * v}
     */
    static double[] apply(double[] m, double[] v) {
        return new double[]{
                m[0] * v[0] + m[1] * v[1] + m[2] * v[2],
                m[3] * v[0] + m[4] * v[1] + m[5] * v[2],
                m[6] * v[0] + m[7] * v[1] + m[8] * v[2]
        };
    }

    /**
     * Inverts a 3x3 matrix.
     *
     * @param m the matrix
     * @return the inverse, or {@code null} when the matrix is singular
     */
    static double[] invert(double[] m) {
        var a = m[4] * m[8] - m[5] * m[7];
        var b = m[5] * m[6] - m[3] * m[8];
        var c = m[3] * m[7] - m[4] * m[6];
        var determinant = m[0] * a + m[1] * b + m[2] * c;
        if (Math.abs(determinant) < 1e-12 || !Double.isFinite(determinant)) {
            return null;
        }
        var inverse = 1 / determinant;
        return new double[]{
                a * inverse, (m[2] * m[7] - m[1] * m[8]) * inverse, (m[1] * m[5] - m[2] * m[4]) * inverse,
                b * inverse, (m[0] * m[8] - m[2] * m[6]) * inverse, (m[2] * m[3] - m[0] * m[5]) * inverse,
                c * inverse, (m[1] * m[6] - m[0] * m[7]) * inverse, (m[0] * m[4] - m[1] * m[3]) * inverse
        };
    }

    /**
     * Returns a diagonal matrix.
     *
     * @param d the diagonal
     * @return the matrix
     */
    static double[] diagonal(double[] d) {
        return new double[]{d[0], 0, 0, 0, d[1], 0, 0, 0, d[2]};
    }

    /**
     * Returns the identity matrix.
     *
     * @return the matrix
     */
    static double[] identity() {
        return new double[]{1, 0, 0, 0, 1, 0, 0, 0, 1};
    }

    /**
     * Multiplies two matrices of any size, stored row by row.
     *
     * @param a the left matrix, {@code rows x inner}
     * @param rows the rows of {@code a}
     * @param b the right matrix, {@code inner x (b.length / inner)}
     * @param inner the columns of {@code a}, the rows of {@code b}
     * @return {@code a * b}, {@code rows x (b.length / inner)}
     */
    static double[] multiply(double[] a, int rows, double[] b, int inner) {
        var columns = b.length / inner;
        var result = new double[rows * columns];
        for (var row = 0; row < rows; row++) {
            for (var column = 0; column < columns; column++) {
                var sum = 0.0;
                for (var k = 0; k < inner; k++) {
                    sum += a[row * inner + k] * b[k * columns + column];
                }
                result[row * columns + column] = sum;
            }
        }
        return result;
    }

    /**
     * Multiplies a matrix of any size by a vector.
     *
     * @param m the matrix, {@code (m.length / v.length) x v.length}
     * @param v the vector
     * @return {@code m * v}
     */
    static double[] transform(double[] m, double[] v) {
        var rows = m.length / v.length;
        var result = new double[rows];
        for (var row = 0; row < rows; row++) {
            var sum = 0.0;
            for (var k = 0; k < v.length; k++) {
                sum += m[row * v.length + k] * v[k];
            }
            result[row] = sum;
        }
        return result;
    }

    /**
     * Returns a diagonal matrix of any size.
     *
     * @param d the diagonal
     * @return the {@code d.length x d.length} matrix
     */
    static double[] diagonalOf(double[] d) {
        var n = d.length;
        var result = new double[n * n];
        for (var index = 0; index < n; index++) {
            result[index * n + index] = d[index];
        }
        return result;
    }

    /**
     * Inverts a square matrix of any size by Gauss-Jordan elimination with partial pivoting; a
     * 3x3 matrix is inverted as {@link #invert(double[])} does.
     *
     * @param m the matrix
     * @param n the size
     * @return the inverse, or {@code null} when the matrix is singular
     */
    static double[] invert(double[] m, int n) {
        if (n == 3) {
            return invert(m);
        }
        var a = m.clone();
        var inverse = diagonalOf(filled(n, 1));
        for (var column = 0; column < n; column++) {
            var pivot = column;
            for (var row = column + 1; row < n; row++) {
                if (Math.abs(a[row * n + column]) > Math.abs(a[pivot * n + column])) {
                    pivot = row;
                }
            }
            var value = a[pivot * n + column];
            if (Math.abs(value) < 1e-12 || !Double.isFinite(value)) {
                return null;
            }
            swapRows(a, n, column, pivot);
            swapRows(inverse, n, column, pivot);
            for (var k = 0; k < n; k++) {
                a[column * n + k] /= value;
                inverse[column * n + k] /= value;
            }
            for (var row = 0; row < n; row++) {
                var factor = a[row * n + column];
                if (row == column || factor == 0) {
                    continue;
                }
                for (var k = 0; k < n; k++) {
                    a[row * n + k] -= factor * a[column * n + k];
                    inverse[row * n + k] -= factor * inverse[column * n + k];
                }
            }
        }
        return inverse;
    }

    /**
     * Returns the inverse of an XYZ to camera matrix as the DNG specification defines it for
     * cameras of more than three colors: the pseudo-inverse {@code (M^T M)^-1 M^T}, which for three
     * colors is the plain inverse.
     *
     * @param m the matrix, {@code rows x 3}
     * @param rows the camera's colors, 3 or more
     * @return the {@code 3 x rows} inverse, or {@code null} when the matrix has no inverse
     */
    static double[] pseudoInverse(double[] m, int rows) {
        if (rows == 3) {
            return invert(m);
        }
        var transposed = new double[3 * rows];
        for (var row = 0; row < rows; row++) {
            for (var column = 0; column < 3; column++) {
                transposed[column * rows + row] = m[row * 3 + column];
            }
        }
        var square = invert(multiply(transposed, 3, m, rows));
        return square == null ? null : multiply(square, 3, transposed, 3);
    }

    /**
     * Returns a vector of equal components.
     *
     * @param n the length
     * @param value the component
     * @return the vector
     */
    static double[] filled(int n, double value) {
        var result = new double[n];
        Arrays.fill(result, value);
        return result;
    }

    /**
     * Swaps two rows of a square matrix in place.
     *
     * @param m the matrix
     * @param n the size
     * @param first the first row
     * @param second the second row
     */
    private static void swapRows(double[] m, int n, int first, int second) {
        if (first == second) {
            return;
        }
        for (var k = 0; k < n; k++) {
            var swap = m[first * n + k];
            m[first * n + k] = m[second * n + k];
            m[second * n + k] = swap;
        }
    }

    /**
     * Converts a chromaticity to XYZ with a luminance of 1.
     *
     * @param xy the chromaticity
     * @return the XYZ
     */
    static double[] xyToXyz(double[] xy) {
        var x = Math.clamp(xy[0], 0.000001, 0.999999);
        var y = Math.clamp(xy[1], 0.000001, 0.999999);
        if (x + y > 0.999999) {
            var scale = 0.999999 / (x + y);
            x *= scale;
            y *= scale;
        }
        return new double[]{x / y, 1.0, (1 - x - y) / y};
    }

    /**
     * Converts XYZ to a chromaticity.
     *
     * @param xyz the XYZ
     * @return the chromaticity, D50 when the sum is not positive
     */
    static double[] xyzToXy(double[] xyz) {
        var sum = xyz[0] + xyz[1] + xyz[2];
        if (!(sum > 0)) {
            return D50_XY.clone();
        }
        return new double[]{xyz[0] / sum, xyz[1] / sum};
    }

    /**
     * Returns the Bradford chromatic adaptation from one white to another.
     *
     * @param from the source white chromaticity
     * @param to the destination white chromaticity
     * @return the matrix mapping XYZ under {@code from} to XYZ under {@code to}
     */
    static double[] adaptation(double[] from, double[] to) {
        var source = apply(BRADFORD, xyToXyz(from));
        var target = apply(BRADFORD, xyToXyz(to));
        var scale = new double[]{
                source[0] > 0 ? target[0] / source[0] : 1,
                source[1] > 0 ? target[1] / source[1] : 1,
                source[2] > 0 ? target[2] / source[2] : 1
        };
        for (var index = 0; index < 3; index++) {
            scale[index] = Math.clamp(scale[index], 0.1, 10);
        }
        return multiply(invert(BRADFORD), multiply(diagonal(scale), BRADFORD));
    }

    /**
     * Returns the chromaticity of a spectral power distribution: its integral against the CIE 1931
     * 2-degree standard observer from 360 to 830 nm in 1 nm steps, the distribution linearly
     * interpolated between its samples and held at its first and last sample outside them.
     *
     * @implNote This implementation evaluates the color matching functions with the multi-lobe
     * Gaussian fit of Wyman, Sloan and Shirley ("Simple Analytic Approximations to the CIE
     * XYZ Color Matching Functions", Journal of Computer Graphics Techniques 2(2), 2013),
     * which keeps the chromaticity of smooth spectra within about 0.001 of the tabulated
     * functions.
     *
     * @param values the samples
     * @param minimum the wavelength of the first sample, in nanometers
     * @param spacing the distance between samples, in nanometers
     * @return the chromaticity
     */
    static double[] spectrumXy(double[] values, double minimum, double spacing) {
        var x = 0.0;
        var y = 0.0;
        var z = 0.0;
        for (var lambda = 360; lambda <= 830; lambda++) {
            var position = (lambda - minimum) / spacing;
            double power;
            if (position <= 0) {
                power = values[0];
            } else if (position >= values.length - 1) {
                power = values[values.length - 1];
            } else {
                var below = (int) position;
                var fraction = position - below;
                power = values[below] + (values[below + 1] - values[below]) * fraction;
            }
            x += power * (1.056 * lobe(lambda, 599.8, 37.9, 31.0) + 0.362 * lobe(lambda, 442.0, 16.0, 26.7)
                    - 0.065 * lobe(lambda, 501.1, 20.4, 26.2));
            y += power * (0.821 * lobe(lambda, 568.8, 46.9, 40.5) + 0.286 * lobe(lambda, 530.9, 16.3, 31.1));
            z += power * (1.217 * lobe(lambda, 437.0, 11.8, 36.0) + 0.681 * lobe(lambda, 459.0, 26.0, 13.8));
        }
        var sum = x + y + z;
        return sum > 0 ? new double[]{x / sum, y / sum} : new double[]{0, 0};
    }

    /**
     * Evaluates a Gaussian with different widths below and above its center.
     *
     * @param lambda the wavelength
     * @param center the wavelength of the peak
     * @param below the standard deviation below the center
     * @param above the standard deviation above the center
     * @return the value
     */
    private static double lobe(double lambda, double center, double below, double above) {
        var t = (lambda - center) / (lambda < center ? below : above);
        return StrictMath.exp(-0.5 * t * t);
    }

    /**
     * Returns the correlated color temperature of a chromaticity, by Robertson's method.
     *
     * @param xy the chromaticity
     * @return the temperature in kelvin, clamped to the table's range
     */
    static double temperature(double[] xy) {
        var denominator = 1.5 - xy[0] + 6 * xy[1];
        var u = 2 * xy[0] / denominator;
        var v = 3 * xy[1] / denominator;
        var previous = 0.0;
        for (var index = 0; index < ROBERTSON.length; index++) {
            var row = ROBERTSON[index];
            var distance = ((v - row[2]) - row[3] * (u - row[1])) / Math.sqrt(1 + row[3] * row[3]);
            if (index > 0 && (distance <= 0) != (previous <= 0)) {
                var before = ROBERTSON[index - 1];
                var fraction = previous / (previous - distance);
                var mired = before[0] + (row[0] - before[0]) * fraction;
                return mired <= 0 ? 50_000 : 1e6 / mired;
            }
            previous = distance;
        }
        return previous > 0 ? 1e6 / 600 : 50_000;
    }

    /**
     * Returns the chromaticity of a correlated color temperature: the CIE daylight locus from 4000
     * K up, the Planckian locus below.
     *
     * @param kelvin the temperature
     * @return the chromaticity
     */
    static double[] chromaticity(double kelvin) {
        var t = Math.clamp(kelvin, 1667, 25000);
        double x;
        double y;
        if (t >= 4000) {
            x = t <= 7000
                    ? -4.6070e9 / (t * t * t) + 2.9678e6 / (t * t) + 0.09911e3 / t + 0.244063
                    : -2.0064e9 / (t * t * t) + 1.9018e6 / (t * t) + 0.24748e3 / t + 0.237040;
            y = -3.000 * x * x + 2.870 * x - 0.275;
        } else {
            x = -0.2661239e9 / (t * t * t) - 0.2343589e6 / (t * t) + 0.8776956e3 / t + 0.179910;
            y = t <= 2222
                    ? -1.1063814 * x * x * x - 1.34811020 * x * x + 2.18555832 * x - 0.20219683
                    : -0.9549476 * x * x * x - 1.37418593 * x * x + 2.09137015 * x - 0.16748867;
        }
        return new double[]{x, y};
    }

    /**
     * Returns the chromaticity of a correlated color temperature moved off the locus by a tint: the point of
     * {@link #chromaticity(double)}, moved in the CIE 1960 UCS diagram by {@code tint / 3000} along the unit
     * normal to the Planckian locus at the temperature that points toward larger {@code v}.
     *
     * @param kelvin the temperature
     * @param tint the offset from the locus, in units of 1/3000 of the CIE 1960 UCS distance
     * @return the chromaticity
     */
    static double[] chromaticity(double kelvin, double tint) {
        var xy = chromaticity(kelvin);
        if (tint == 0) {
            return xy;
        }
        var uv = xyToUv(xy);
        var normal = planckianNormal(kelvin);
        return uvToXy(uv[0] + tint / 3000 * normal[0], uv[1] + tint / 3000 * normal[1]);
    }

    /**
     * Returns the unit normal to the Planckian locus in the CIE 1960 UCS diagram, pointing toward larger
     * {@code v}, from the derivative of Krystek's rational approximation of the locus.
     *
     * @param kelvin the temperature, clamped to the approximation's range of 1000 to 15000 K
     * @return the normal's {@code u} and {@code v} components
     */
    static double[] planckianNormal(double kelvin) {
        var t = Math.clamp(kelvin, 1000, 15000);
        var step = 1e-3 * t;
        var low = planckianUv(t - step);
        var high = planckianUv(t + step);
        var du = high[0] - low[0];
        var dv = high[1] - low[1];
        var length = StrictMath.hypot(du, dv);
        var nu = -dv / length;
        var nv = du / length;
        return nv >= 0 ? new double[]{nu, nv} : new double[]{-nu, -nv};
    }

    /**
     * Returns the CIE 1960 UCS chromaticity of a Planckian radiator by Krystek's rational approximation (1985),
     * accurate to {@code 1e-4} from 1000 to 15000 K.
     *
     * @param kelvin the temperature
     * @return the {@code u} and {@code v} coordinates
     */
    static double[] planckianUv(double kelvin) {
        var t = kelvin;
        var u = (0.860117757 + 1.54118254e-4 * t + 1.28641212e-7 * t * t) / (1 + 8.42420235e-4 * t + 7.08145163e-7 * t * t);
        var v = (0.317398726 + 4.22806245e-5 * t + 4.20481691e-8 * t * t) / (1 - 2.89741816e-5 * t + 1.61456053e-7 * t * t);
        return new double[]{u, v};
    }

    /**
     * {@return the CIE 1960 UCS coordinates of a CIE 1931 chromaticity}
     *
     * @param xy the chromaticity
     */
    static double[] xyToUv(double[] xy) {
        var denominator = -2 * xy[0] + 12 * xy[1] + 3;
        return new double[]{4 * xy[0] / denominator, 6 * xy[1] / denominator};
    }

    /**
     * {@return the CIE 1931 chromaticity of CIE 1960 UCS coordinates}
     *
     * @param u the {@code u} coordinate
     * @param v the {@code v} coordinate
     */
    static double[] uvToXy(double u, double v) {
        var denominator = 2 * u - 8 * v + 4;
        return new double[]{3 * u / denominator, 2 * v / denominator};
    }

    /**
     * Returns the correlated color temperature of an EXIF light source code, as DNG calibration
     * illuminants use them.
     *
     * @param code the light source code
     * @return the temperature in kelvin, or {@code 0} when the code names no temperature
     */
    static double illuminantTemperature(int code) {
        return switch (code) {
            case 1, 4, 9 -> 5500;
            case 2, 14 -> 4150;
            case 3 -> 2850;
            case 10 -> 6500;
            case 11 -> 7500;
            case 12 -> 6430;
            case 13 -> 5000;
            case 15 -> 3450;
            case 16 -> 2940;
            case 17 -> 2856;
            case 18 -> 4874;
            case 19 -> 6774;
            case 20 -> 5503;
            case 21 -> 6504;
            case 22 -> 7504;
            case 23 -> 5003;
            case 24 -> 3200;
            default -> 0;
        };
    }

    /**
     * Returns the matrix mapping linear RGB of the given primaries and white to XYZ.
     *
     * @param red the red primary
     * @param green the green primary
     * @param blue the blue primary
     * @param white the white point
     * @return the matrix
     */
    static double[] rgbToXyz(double[] red, double[] green, double[] blue, double[] white) {
        var r = xyToXyz(red);
        var g = xyToXyz(green);
        var b = xyToXyz(blue);
        var primaries = new double[]{r[0], g[0], b[0], r[1], g[1], b[1], r[2], g[2], b[2]};
        var scale = apply(invert(primaries), xyToXyz(white));
        return multiply(primaries, diagonal(scale));
    }
}
