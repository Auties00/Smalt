package com.github.auties00.smalt.image.ops;

import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.internal.task.ParallelBands;

import java.util.Arrays;

/**
 * Runs the linear-light adjustments of {@link ImageAdjustments} on packed RGBA pictures: the vignette and the
 * white balance.
 *
 * <p>Samples are decoded with the sRGB transfer function, computed in double precision from single-precision
 * linear values, and encoded to the nearest sample. Alpha is kept, opaque when the picture reports none.
 */
final class Lighting {
    /**
     * The approximate work per pixel, weighed against {@link ParallelBands#MIN_PARALLEL_WORK}.
     */
    private static final long PIXEL_WORK = 40;

    /**
     * The color temperature of the reference white, in kelvin.
     */
    static final double REFERENCE_KELVIN = 6504;

    /**
     * The linear sRGB to CIE XYZ matrix (IEC 61966-2-1, D65), row by row.
     */
    private static final double[] RGB_TO_XYZ = {
            0.4124564, 0.3575761, 0.1804375,
            0.2126729, 0.7151522, 0.0721750,
            0.0193339, 0.1191920, 0.9503041
    };

    /**
     * The Bradford cone response matrix, row by row.
     */
    private static final double[] BRADFORD = {
            0.8951, 0.2664, -0.1614,
            -0.7502, 1.7135, 0.0367,
            0.0389, -0.0685, 1.0296
    };

    /**
     * Prevents instantiation.
     */
    private Lighting() {
        throw new AssertionError();
    }

    /**
     * Applies a vignette.
     *
     * @param packed the packed picture
     * @param amount the strength at the corners, {@code -1..1}
     * @param radius the normalized distance at which the falloff starts, {@code [0, 1)}
     * @return the adjusted picture
     */
    static Picture vignette(Picture packed, double amount, double radius) {
        var width = packed.width();
        var height = packed.height();
        var halfWidth = width / 2.0;
        var halfHeight = height / 2.0;
        var columns = new double[width];
        for (var x = 0; x < width; x++) {
            var dx = (x + 0.5 - halfWidth) / halfWidth;
            columns[x] = dx * dx;
        }
        return rows(packed, (y, linear, count) -> {
            var dy = (y + 0.5 - halfHeight) / halfHeight;
            var rowTerm = dy * dy;
            for (var x = 0; x < count; x++) {
                var d = Math.sqrt((columns[x] + rowTerm) / 2);
                var t = Math.clamp((d - radius) / (1 - radius), 0, 1);
                var factor = 1 - amount * t * t * (3 - 2 * t);
                linear[3 * x] *= factor;
                linear[3 * x + 1] *= factor;
                linear[3 * x + 2] *= factor;
            }
        });
    }

    /**
     * Applies a linear-light color matrix.
     *
     * @param packed the packed picture
     * @param matrix the matrix from linear source RGB to linear result RGB, row by row
     * @return the adjusted picture
     */
    static Picture whiteBalance(Picture packed, double[] matrix) {
        return rows(packed, (y, linear, count) -> {
            for (var x = 0; x < count; x++) {
                var r = linear[3 * x];
                var g = linear[3 * x + 1];
                var b = linear[3 * x + 2];
                linear[3 * x] = matrix[0] * r + matrix[1] * g + matrix[2] * b;
                linear[3 * x + 1] = matrix[3] * r + matrix[4] * g + matrix[5] * b;
                linear[3 * x + 2] = matrix[6] * r + matrix[7] * g + matrix[8] * b;
            }
        });
    }

    /**
     * Computes the linear sRGB matrix that adapts colors seen under a light to the reference white, as
     * {@link ImageAdjustments#whiteBalance} describes.
     *
     * @param kelvin the color temperature of the light
     * @param tint the {@code Duv} of the light
     * @return the matrix, row by row
     */
    static double[] adaptation(double kelvin, double tint) {
        var source = cones(white(kelvin, tint));
        var target = cones(white(REFERENCE_KELVIN, 0));
        if (Arrays.equals(source, target)) {
            return new double[]{1, 0, 0, 0, 1, 0, 0, 0, 1};
        }
        var toCones = multiply(BRADFORD, RGB_TO_XYZ);
        var scale = new double[]{
                target[0] / source[0], 0, 0,
                0, target[1] / source[1], 0,
                0, 0, target[2] / source[2]
        };
        return multiply(invert(toCones), multiply(scale, toCones));
    }

    /**
     * Returns the CIE XYZ coordinates, with {@code Y = 1}, of a light near the Planckian locus.
     *
     * @param kelvin the color temperature, on Krystek's approximation of the locus
     * @param tint the distance from the locus along its normal in the CIE 1960 diagram, positive toward higher
     * {@code v}
     * @return {@code X, Y, Z}
     */
    static double[] white(double kelvin, double tint) {
        var u = planckU(kelvin);
        var v = planckV(kelvin);
        var du = planckU(kelvin + 0.5) - planckU(kelvin - 0.5);
        var dv = planckV(kelvin + 0.5) - planckV(kelvin - 0.5);
        var length = Math.sqrt(du * du + dv * dv);
        u += tint * dv / length;
        v -= tint * du / length;
        var denominator = 2 * u - 8 * v + 4;
        var x = 3 * u / denominator;
        var y = 2 * v / denominator;
        return new double[]{x / y, 1, (1 - x - y) / y};
    }

    /**
     * Returns the {@code u} coordinate of the Planckian locus (Krystek, 1985).
     *
     * @param t the temperature in kelvin, 1000 to 15000
     * @return the CIE 1960 {@code u}
     */
    private static double planckU(double t) {
        return (0.860117757 + 1.54118254e-4 * t + 1.28641212e-7 * t * t)
                / (1 + 8.42420235e-4 * t + 7.08145163e-7 * t * t);
    }

    /**
     * Returns the {@code v} coordinate of the Planckian locus (Krystek, 1985).
     *
     * @param t the temperature in kelvin, 1000 to 15000
     * @return the CIE 1960 {@code v}
     */
    private static double planckV(double t) {
        return (0.317398726 + 4.22806245e-5 * t + 4.20481691e-8 * t * t)
                / (1 - 2.89741816e-5 * t + 1.61456053e-7 * t * t);
    }

    /**
     * Returns the Bradford cone responses of a color.
     *
     * @param xyz the CIE XYZ coordinates
     * @return the three responses
     */
    private static double[] cones(double[] xyz) {
        var cones = new double[3];
        for (var i = 0; i < 3; i++) {
            cones[i] = BRADFORD[3 * i] * xyz[0] + BRADFORD[3 * i + 1] * xyz[1] + BRADFORD[3 * i + 2] * xyz[2];
        }
        return cones;
    }

    /**
     * Multiplies two 3x3 matrices.
     *
     * @param a the left matrix, row by row
     * @param b the right matrix, row by row
     * @return {@code a * b}, row by row
     */
    private static double[] multiply(double[] a, double[] b) {
        var product = new double[9];
        for (var i = 0; i < 3; i++) {
            for (var j = 0; j < 3; j++) {
                product[3 * i + j] = a[3 * i] * b[j] + a[3 * i + 1] * b[3 + j] + a[3 * i + 2] * b[6 + j];
            }
        }
        return product;
    }

    /**
     * Inverts a 3x3 matrix by its adjugate.
     *
     * @param m the invertible matrix, row by row
     * @return the inverse, row by row
     */
    private static double[] invert(double[] m) {
        var c00 = m[4] * m[8] - m[5] * m[7];
        var c01 = m[5] * m[6] - m[3] * m[8];
        var c02 = m[3] * m[7] - m[4] * m[6];
        var determinant = m[0] * c00 + m[1] * c01 + m[2] * c02;
        return new double[]{
                c00 / determinant, (m[2] * m[7] - m[1] * m[8]) / determinant, (m[1] * m[5] - m[2] * m[4]) / determinant,
                c01 / determinant, (m[0] * m[8] - m[2] * m[6]) / determinant, (m[2] * m[3] - m[0] * m[5]) / determinant,
                c02 / determinant, (m[1] * m[6] - m[0] * m[7]) / determinant, (m[0] * m[4] - m[1] * m[3]) / determinant
        };
    }

    /**
     * Transforms the linear colors of one row in place.
     */
    @FunctionalInterface
    private interface RowFunction {
        /**
         * Replaces the linear colors of a row.
         *
         * @param y the row
         * @param linear the linear red, green and blue of each pixel, replaced in place
         * @param count the number of pixels
         */
        void apply(int y, double[] linear, int count);
    }

    /**
     * Decodes every row of a packed picture to linear light, transforms it and encodes it back.
     *
     * @param packed the packed picture
     * @param function the transform of each row
     * @return the new picture
     */
    private static Picture rows(Picture packed, RowFunction function) {
        var out = Tone.allocate(packed);
        var width = packed.width();
        var height = packed.height();
        var bands = ParallelBands.bands(height, PIXEL_WORK * width * height);
        ParallelBands.run(bands, band -> {
            var linear = new double[3 * width];
            for (var y = ParallelBands.start(band, bands, height); y < ParallelBands.start(band + 1, bands, height); y++) {
                switch (packed) {
                    case RgbaPicture rgba -> {
                        var src = rgba.pixels();
                        var s = y * rgba.stride();
                        for (var x = 0; x < width; x++) {
                            for (var c = 0; c < 3; c++) {
                                linear[3 * x + c] = Srgb.decode8(src[s + 4 * x + c] & 0xFF);
                            }
                        }
                        function.apply(y, linear, width);
                        var dst = ((RgbaPicture) out).pixels();
                        var d = y * width * 4;
                        for (var x = 0; x < width; x++) {
                            for (var c = 0; c < 3; c++) {
                                dst[d + 4 * x + c] = (byte) Srgb.encode8((float) linear[3 * x + c]);
                            }
                            dst[d + 4 * x + 3] = rgba.hasAlpha() ? src[s + 4 * x + 3] : (byte) 255;
                        }
                    }
                    case Rgba16Picture rgba -> {
                        var src = rgba.pixels();
                        var s = y * rgba.stride();
                        for (var x = 0; x < width; x++) {
                            for (var c = 0; c < 3; c++) {
                                linear[3 * x + c] = Srgb.decode16(src[s + 4 * x + c] & 0xFFFF);
                            }
                        }
                        function.apply(y, linear, width);
                        var dst = ((Rgba16Picture) out).pixels();
                        var d = y * width * 4;
                        for (var x = 0; x < width; x++) {
                            for (var c = 0; c < 3; c++) {
                                dst[d + 4 * x + c] = (short) Srgb.encode16((float) linear[3 * x + c]);
                            }
                            dst[d + 4 * x + 3] = rgba.hasAlpha() ? src[s + 4 * x + 3] : (short) 0xFFFF;
                        }
                    }
                    default -> throw new IllegalArgumentException("not a packed picture: " + packed);
                }
            }
        });
        return out;
    }
}
