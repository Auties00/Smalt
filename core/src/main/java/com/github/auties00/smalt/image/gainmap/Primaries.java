package com.github.auties00.smalt.image.gainmap;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.Chromaticity;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.color.ColorTransform;
import com.github.auties00.smalt.image.color.ColorTransformOptions;
import com.github.auties00.smalt.image.color.ProfileColorSpace;

import java.util.List;

/**
 * The primaries and white point of an RGB color space, with the CICP constant they match.
 *
 * @param xy the chromaticities of red, green, blue and white, as {@code x, y} pairs
 * @param cicp the CICP primaries these are, or {@code null} for primaries no CICP code point describes
 */
record Primaries(double[] xy, ColorPrimaries cicp) {
    /**
     * The D50 white of the ICC profile connection space.
     */
    private static final double[] D50 = {0.9642, 1.0, 0.8249};

    /**
     * The Bradford cone response matrix.
     */
    private static final double[] BRADFORD = {
            0.8951, 0.2664, -0.1614,
            -0.7502, 1.7135, 0.0367,
            0.0389, -0.0685, 1.0296
    };

    /**
     * The CICP primaries a profile's colorants are matched against.
     */
    private static final List<ColorPrimaries> KNOWN = List.of(ColorPrimaries.BT709, ColorPrimaries.SMPTE432,
            ColorPrimaries.BT2020, ColorPrimaries.SMPTE431, ColorPrimaries.SMPTE170M, ColorPrimaries.BT470M,
            ColorPrimaries.EBU3213, ColorPrimaries.FILM);

    /**
     * The largest difference of a colorant component from that of CICP primaries that still matches them.
     */
    private static final double MATCH = 0.003;

    /**
     * Returns the primaries of a CICP code point.
     *
     * @param primaries the code point; {@link ColorPrimaries#UNSPECIFIED} is taken as BT.709
     * @return the primaries
     */
    static Primaries of(ColorPrimaries primaries) {
        var defined = primaries == ColorPrimaries.UNSPECIFIED ? ColorPrimaries.BT709 : primaries;
        var red = defined.red().orElseThrow();
        var green = defined.green().orElseThrow();
        var blue = defined.blue().orElseThrow();
        var white = defined.white().orElseThrow();
        return new Primaries(new double[]{red.x(), red.y(), green.x(), green.y(), blue.x(), blue.y(), white.x(),
                white.y()}, defined);
    }

    /**
     * Returns the primaries of an RGB color profile.
     *
     * <p>A high-dynamic-range profile gives its CICP primaries. Otherwise the colorants the profile maps pure
     * red, green and blue to in the connection space are matched against the CICP primaries adapted to D50
     * with the Bradford transform; without a match, they are the primaries, with the D50 white.
     *
     * @param profile the profile, or {@code null} for sRGB
     * @return the primaries
     * @throws MediaException.Unsupported if the profile is not an RGB profile
     * @throws MediaException.Malformed if the profile is damaged
     */
    static Primaries of(ColorProfile profile) {
        if (profile == null || profile.isSrgb()) {
            return of(ColorPrimaries.BT709);
        }
        if (profile.colorSpace() != ProfileColorSpace.RGB) {
            throw new MediaException.Unsupported("gain map", "a " + profile.colorSpace() + " color profile");
        }
        if (profile.colorInfo().isPresent()) {
            return of(profile.colorInfo().get().primaries());
        }
        var colorants = colorants(profile);
        for (var known : KNOWN) {
            var adapted = of(known).adaptedToD50();
            var matches = true;
            for (var i = 0; i < 9 && matches; i++) {
                matches = Math.abs(adapted[i] - colorants[i]) <= MATCH;
            }
            if (matches) {
                return of(known);
            }
        }
        var xy = new double[8];
        for (var channel = 0; channel < 3; channel++) {
            var x = colorants[channel];
            var y = colorants[3 + channel];
            var z = colorants[6 + channel];
            var sum = x + y + z;
            if (!(sum > 0)) {
                throw new MediaException.Unsupported("gain map", "a color profile whose primaries have no "
                        + "chromaticity");
            }
            xy[channel * 2] = x / sum;
            xy[channel * 2 + 1] = y / sum;
        }
        var d50 = D50[0] + D50[1] + D50[2];
        xy[6] = D50[0] / d50;
        xy[7] = D50[1] / d50;
        return new Primaries(xy, null);
    }

    /**
     * Returns the profile that holds these primaries with a pure power tone curve.
     *
     * @param gamma the exponent of the tone curve
     * @return the profile
     */
    ColorProfile profile(double gamma) {
        return ColorProfile.rgb(new Chromaticity(xy[0], xy[1]), new Chromaticity(xy[2], xy[3]),
                new Chromaticity(xy[4], xy[5]), new Chromaticity(xy[6], xy[7]), gamma);
    }

    /**
     * {@return whether these primaries and white are those of another color space}
     */
    boolean same(Primaries other) {
        if (cicp != null && other.cicp != null) {
            return cicp == other.cicp;
        }
        for (var i = 0; i < 8; i++) {
            if (Math.abs(xy[i] - other.xy[i]) > 1e-9) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the matrix from linear RGB to CIE XYZ, white scaled to {@code Y = 1}.
     *
     * @return the 3x3 matrix, row-major
     */
    double[] toXyz() {
        var matrix = new double[9];
        for (var channel = 0; channel < 3; channel++) {
            var x = xy[channel * 2];
            var y = xy[channel * 2 + 1];
            matrix[channel] = x / y;
            matrix[3 + channel] = 1;
            matrix[6 + channel] = (1 - x - y) / y;
        }
        var white = white();
        var scale = multiply(invert(matrix), white);
        for (var row = 0; row < 3; row++) {
            for (var channel = 0; channel < 3; channel++) {
                matrix[row * 3 + channel] *= scale[channel];
            }
        }
        return matrix;
    }

    /**
     * {@return the luminance weights of red, green and blue}
     */
    double[] luminance() {
        var matrix = toXyz();
        return new double[]{matrix[3], matrix[4], matrix[5]};
    }

    /**
     * Returns the matrix from linear RGB in these primaries to linear RGB in other ones, adapting the white with
     * the Bradford transform when the whites differ.
     *
     * @param target the target primaries
     * @return the 3x3 matrix, row-major
     */
    double[] conversion(Primaries target) {
        var source = toXyz();
        var whiteSource = white();
        var whiteTarget = target.white();
        if (Math.abs(whiteSource[0] - whiteTarget[0]) > 1e-9 || Math.abs(whiteSource[2] - whiteTarget[2]) > 1e-9) {
            source = multiply(adaptation(whiteSource, whiteTarget), source);
        }
        return multiply(invert(target.toXyz()), source);
    }

    /**
     * {@return the colorants of these primaries adapted to the D50 white, as the columns of a row-major matrix}
     */
    private double[] adaptedToD50() {
        return multiply(adaptation(white(), D50), toXyz());
    }

    /**
     * {@return the XYZ of the white point, {@code Y = 1}}
     */
    private double[] white() {
        var x = xy[6];
        var y = xy[7];
        return new double[]{x / y, 1, (1 - x - y) / y};
    }

    /**
     * Measures the colorants of a profile: the connection space values of pure red, green and blue.
     *
     * @param profile the RGB profile
     * @return the colorants as the columns of a row-major matrix
     */
    private static double[] colorants(ColorProfile profile) {
        var transform = ColorTransform.create(List.of(profile), ColorTransformOptions.relativeColorimetric());
        var source = new short[]{(short) 0xFFFF, 0, 0, 0, (short) 0xFFFF, 0, 0, 0, (short) 0xFFFF};
        var target = new short[9];
        transform.transform(source, 0, 3, target, 0, 3, 3);
        var colorants = new double[9];
        var lab = profile.connectionSpace() == ProfileColorSpace.LAB;
        for (var channel = 0; channel < 3; channel++) {
            double x;
            double y;
            double z;
            if (lab) {
                var l = (target[channel * 3] & 0xFFFF) * 100.0 / 65535;
                var a = (target[channel * 3 + 1] & 0xFFFF) * 255.0 / 65535 - 128;
                var b = (target[channel * 3 + 2] & 0xFFFF) * 255.0 / 65535 - 128;
                var fy = (l + 16) / 116;
                y = D50[1] * labInverse(fy);
                x = D50[0] * labInverse(fy + a / 500);
                z = D50[2] * labInverse(fy - b / 200);
            } else {
                x = (target[channel * 3] & 0xFFFF) / 32768.0;
                y = (target[channel * 3 + 1] & 0xFFFF) / 32768.0;
                z = (target[channel * 3 + 2] & 0xFFFF) / 32768.0;
            }
            colorants[channel] = x;
            colorants[3 + channel] = y;
            colorants[6 + channel] = z;
        }
        return colorants;
    }

    /**
     * {@return the inverse of the CIE L*a*b* companding function}
     */
    private static double labInverse(double t) {
        return t > 6.0 / 29 ? t * t * t : 3 * (6.0 / 29) * (6.0 / 29) * (t - 4.0 / 29);
    }

    /**
     * Returns the Bradford adaptation matrix between two whites.
     *
     * @param from the source white, XYZ
     * @param to the target white, XYZ
     * @return the 3x3 matrix, row-major
     */
    private static double[] adaptation(double[] from, double[] to) {
        var coneFrom = multiply(BRADFORD, from);
        var coneTo = multiply(BRADFORD, to);
        var scale = new double[]{coneTo[0] / coneFrom[0], 0, 0, 0, coneTo[1] / coneFrom[1], 0, 0, 0,
                coneTo[2] / coneFrom[2]};
        return multiply(invert(BRADFORD), multiply(scale, BRADFORD));
    }

    /**
     * {@return the product of two 3x3 matrices, or of a matrix and a vector of three}
     */
    static double[] multiply(double[] left, double[] right) {
        if (right.length == 3) {
            var result = new double[3];
            for (var row = 0; row < 3; row++) {
                result[row] = left[row * 3] * right[0] + left[row * 3 + 1] * right[1] + left[row * 3 + 2] * right[2];
            }
            return result;
        }
        var result = new double[9];
        for (var row = 0; row < 3; row++) {
            for (var column = 0; column < 3; column++) {
                result[row * 3 + column] = left[row * 3] * right[column] + left[row * 3 + 1] * right[3 + column]
                        + left[row * 3 + 2] * right[6 + column];
            }
        }
        return result;
    }

    /**
     * Inverts a 3x3 matrix.
     *
     * @param m the matrix, row-major
     * @return the inverse
     * @throws MediaException.Unsupported if the matrix is singular, as primaries that span no color space
     */
    static double[] invert(double[] m) {
        var c00 = m[4] * m[8] - m[5] * m[7];
        var c01 = m[5] * m[6] - m[3] * m[8];
        var c02 = m[3] * m[7] - m[4] * m[6];
        var determinant = m[0] * c00 + m[1] * c01 + m[2] * c02;
        if (!(Math.abs(determinant) > 1e-12)) {
            throw new MediaException.Unsupported("gain map", "primaries that span no color space");
        }
        var inverse = 1 / determinant;
        return new double[]{
                c00 * inverse, (m[2] * m[7] - m[1] * m[8]) * inverse, (m[1] * m[5] - m[2] * m[4]) * inverse,
                c01 * inverse, (m[0] * m[8] - m[2] * m[6]) * inverse, (m[2] * m[3] - m[0] * m[5]) * inverse,
                c02 * inverse, (m[1] * m[6] - m[0] * m[7]) * inverse, (m[0] * m[4] - m[1] * m[3]) * inverse
        };
    }
}
