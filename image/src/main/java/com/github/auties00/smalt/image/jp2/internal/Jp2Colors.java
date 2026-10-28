package com.github.auties00.smalt.image.jp2.internal;

import com.github.auties00.smalt.image.jp2.Jp2Channel;
import com.github.auties00.smalt.image.jp2.Jp2ColorSpace;
import com.github.auties00.smalt.internal.io.ByteArrays;

/**
 * Converts the samples of the enumerated color spaces of ISO/IEC 15444-2 M.11.7.3.1 to RGB: the YCbCr and YPbPr
 * spaces of Rec. ITU-R BT.601 and BT.709, PhotoYCC, CMY, YCCK, CIE 1976 L*a*b* with the enumerated parameters of
 * M.11.7.4.1, and CIECAM97s J*a*b* with those of M.11.7.4.2.
 *
 * <p>Every method takes the color planes upsampled to the image, as decoded (signed samples are two's complement), and
 * the channels for their precision and signedness. A signed channel is read with the offset of half its range that
 * makes it unsigned, which M.11.7.2 states for chroma channels (their zero is the achromatic value).
 *
 * <p>Arguments are not checked; values outside the documented ranges give unspecified results.
 */
final class Jp2Colors {
    /**
     * The Bradford cone response matrix (CIE TC1-34, equation 2.2).
     */
    private static final double[][] BRADFORD = {
            {0.8951, 0.2664, -0.1614}, {-0.7502, 1.7135, 0.0367}, {0.0389, -0.0685, 1.0296}};

    /**
     * The inverse of {@link #BRADFORD}.
     */
    private static final double[][] BRADFORD_INVERSE = invert(BRADFORD);

    /**
     * The Hunt-Pointer-Estevez cone response matrix (CIE TC1-34, equation 2.14).
     */
    private static final double[][] HUNT_POINTER_ESTEVEZ = {
            {0.38971, 0.68898, -0.07868}, {-0.22981, 1.18340, 0.04641}, {0.0, 0.0, 1.0}};

    /**
     * The matrix from Bradford to Hunt-Pointer-Estevez responses, {@code M_H M_B^-1}.
     */
    private static final double[][] BRADFORD_TO_HPE = multiply(HUNT_POINTER_ESTEVEZ, BRADFORD_INVERSE);

    /**
     * The matrix from Hunt-Pointer-Estevez to Bradford responses, {@code M_B M_H^-1}.
     */
    private static final double[][] HPE_TO_BRADFORD = invert(BRADFORD_TO_HPE);

    /**
     * The D65 white of the sRGB color space, from its chromaticity (0.3127, 0.3290), with Y = 1.
     */
    private static final double[] D65 = {0.3127 / 0.3290, 1.0, (1 - 0.3127 - 0.3290) / 0.3290};

    /**
     * The D50 white of the ICC profile connection space, with Y = 1.
     */
    private static final double[] D50 = {0.9642, 1.0, 0.8249};

    /**
     * The matrix from CIE XYZ relative to D65 to linear sRGB, computed from the sRGB primaries and white.
     */
    private static final double[][] XYZ_TO_SRGB = srgbMatrix();

    /**
     * The unique hues of CIECAM97s: hue angle in degrees and eccentricity, red repeated at the end one turn later.
     */
    private static final double[][] UNIQUE_HUES = {
            {20.14, 0.8}, {90.00, 0.7}, {164.25, 1.0}, {237.53, 1.2}, {380.14, 0.8}};

    /**
     * Prevents instantiation.
     */
    private Jp2Colors() {
        throw new AssertionError();
    }

    /**
     * Reads a sample as an unsigned value: signed samples are offset by half their range.
     *
     * @param value the sample
     * @param channel the channel, for its precision and signedness
     * @return the unsigned value, from 0 to {@code 2^p - 1}
     */
    private static double unsigned(int value, Jp2Channel channel) {
        return channel.signed() ? value + Math.scalb(1.0, channel.precision() - 1) : value;
    }

    /**
     * Reads a sample on the scale of 8-bit samples: unsigned, divided by {@code 2^(p-8)}.
     *
     * @param value the sample
     * @param channel the channel, for its precision and signedness
     * @return the value, from 0 to just under 256
     */
    private static double eightBit(int value, Jp2Channel channel) {
        return Math.scalb(unsigned(value, channel), 8 - channel.precision());
    }

    /**
     * Returns half the range of a channel, the zero of a color difference.
     *
     * @param channel the channel, for its precision
     * @return {@code 2^(p-1)}
     */
    private static double half(Jp2Channel channel) {
        return Math.scalb(1.0, channel.precision() - 1);
    }

    /**
     * Returns the largest unsigned value of a channel.
     *
     * @param channel the channel, for its precision and signedness
     * @return {@code 2^p - 1}
     */
    private static double max(Jp2Channel channel) {
        return Math.scalb(1.0, channel.precision()) - 1;
    }

    /**
     * Converts luma and color difference planes to R'G'B' in place (Rec. ITU-T T.871 and Rec. ITU-R BT.601 and
     * BT.709): {@code YCbCr(1)} and {@code YPbPr(1125/60)} with the BT.709 luma coefficients over the video range,
     * {@code YCbCr(3)} and {@code YPbPr(1250/50)} with the BT.601 ones over the video range, and {@code YCbCr(2)}
     * with the BT.601 ones over the full range.
     *
     * @param planes the Y, Cb and Cr planes, replaced by R', G' and B' at the luma's precision
     * @param channels the three channels
     * @param space the color space
     */
    static void ycc(int[][] planes, Jp2Channel[] channels, Jp2ColorSpace space) {
        var bt709 = space == Jp2ColorSpace.YCBCR_709 || space == Jp2ColorSpace.YPBPR_1125_60;
        var full = space == Jp2ColorSpace.YCBCR_601_625;
        var kr = bt709 ? 0.2126 : 0.299;
        var kb = bt709 ? 0.0722 : 0.114;
        var kg = 1 - kr - kb;
        var out = max(channels[0]);
        var length = planes[0].length;
        for (var i = 0; i < length; i++) {
            double y;
            double cb;
            double cr;
            if (full) {
                y = unsigned(planes[0][i], channels[0]) / max(channels[0]);
                cb = (unsigned(planes[1][i], channels[1]) - half(channels[1])) / max(channels[1]);
                cr = (unsigned(planes[2][i], channels[2]) - half(channels[2])) / max(channels[2]);
            } else {
                // the video range of 8-bit samples, scaled by 2^(p-8): luma 16 to 235, color differences 16 to 240
                y = (eightBit(planes[0][i], channels[0]) - 16) / 219;
                cb = (eightBit(planes[1][i], channels[1]) - 128) / 224;
                cr = (eightBit(planes[2][i], channels[2]) - 128) / 224;
            }
            var r = y + 2 * (1 - kr) * cr;
            var b = y + 2 * (1 - kb) * cb;
            var g = (y - kr * r - kb * b) / kg;
            planes[0][i] = quantize(r, out);
            planes[1][i] = quantize(g, out);
            planes[2][i] = quantize(b, out);
        }
    }

    /**
     * Converts PhotoYCC planes to R'G'B' in place, with the Photo CD equations for 8-bit samples scaled to the
     * channels' precision; the overrange of PhotoYCC is clipped.
     *
     * @param planes the Y, C1 and C2 planes, replaced by R', G' and B' at the luma's precision
     * @param channels the three channels
     */
    static void photoYcc(int[][] planes, Jp2Channel[] channels) {
        var out = max(channels[0]);
        var length = planes[0].length;
        for (var i = 0; i < length; i++) {
            var y = 1.3584 * eightBit(planes[0][i], channels[0]);
            var c1 = 2.2179 * (eightBit(planes[1][i], channels[1]) - 156);
            var c2 = 1.8215 * (eightBit(planes[2][i], channels[2]) - 137);
            var r = y + c2;
            var g = y - 0.194 * c1 - 0.509 * c2;
            var b = y + c1;
            planes[0][i] = quantize(r / 255, out);
            planes[1][i] = quantize(g / 255, out);
            planes[2][i] = quantize(b / 255, out);
        }
    }

    /**
     * Converts CMY planes to RGB in place: each color is its ink's complement.
     *
     * @param planes the C, M and Y planes, replaced by R, G and B at each channel's precision
     * @param channels the three channels
     */
    static void cmy(int[][] planes, Jp2Channel[] channels) {
        for (var c = 0; c < 3; c++) {
            var max = max(channels[c]);
            for (var i = 0; i < planes[c].length; i++) {
                planes[c][i] = (int) (max - Math.clamp(unsigned(planes[c][i], channels[c]), 0, max));
            }
        }
    }

    /**
     * Converts YCCK planes to CMYK in place: the YCC planes go back to R'G'B' as {@code YCbCr(2)} does, and each ink
     * is the complement of its color (M.11.7.3.1, EnumCS 13).
     *
     * @param planes the Y, Cb, Cr and K planes; the first three are replaced by C, M and Y at the luma's precision
     * @param channels the four channels
     */
    static void ycck(int[][] planes, Jp2Channel[] channels) {
        ycc(planes, channels, Jp2ColorSpace.YCBCR_601_625);
        var max = (int) max(channels[0]);
        for (var c = 0; c < 3; c++) {
            for (var i = 0; i < planes[c].length; i++) {
                planes[c][i] = max - planes[c][i];
            }
        }
    }

    /**
     * Converts CIE 1976 L*a*b* planes to 16-bit sRGB (M.11.7.4.1): each sample is unscaled with the ranges and
     * offsets of the enumerated parameters, or their defaults, then converted to CIE XYZ relative to the parameters'
     * illuminant, adapted to the D65 white of sRGB with the Bradford transformation, and encoded with the sRGB
     * transfer function; colors outside the sRGB gamut are clipped.
     *
     * @param planes the L*, a* and b* planes, replaced by R, G and B from 0 to 65535
     * @param channels the three channels
     * @param parameters the {@code EP} field: {@code RL}, {@code OL}, {@code RA}, {@code OA}, {@code RB},
     * {@code OB} and {@code IL}, or {@code null} for the defaults
     */
    static void lab(int[][] planes, Jp2Channel[] channels, byte[] parameters) {
        var ranges = new double[]{100, 170, 200};
        var offsets = new double[3];
        for (var c = 0; c < 3; c++) {
            if (!channels[c].signed()) {
                var p = channels[c].precision();
                offsets[c] = c == 0 ? 0 : c == 1 ? Math.scalb(1.0, p - 1) : Math.scalb(1.0, p - 2)
                        + Math.scalb(1.0, p - 3);
            }
        }
        var white = D50;
        if (parameters != null && parameters.length >= 24) {
            for (var c = 0; c < 3; c++) {
                ranges[c] = ByteArrays.u32be(parameters, 8 * c);
                offsets[c] = ByteArrays.u32be(parameters, 8 * c + 4);
            }
            if (parameters.length >= 28) {
                white = illuminant(parameters, 24);
            }
        }
        var adaptation = adaptation(white, D65);
        var matrix = multiply(XYZ_TO_SRGB, adaptation);
        var scales = new double[3];
        var shifts = new double[3];
        for (var c = 0; c < 3; c++) {
            var max = signedMax(channels[c]);
            scales[c] = ranges[c] / max;
            shifts[c] = offsetScale(max, channels[c].precision()) * offsets[c];
        }
        var length = planes[0].length;
        var xyz = new double[3];
        for (var i = 0; i < length; i++) {
            var l = (planes[0][i] - shifts[0]) * scales[0];
            var a = (planes[1][i] - shifts[1]) * scales[1];
            var b = (planes[2][i] - shifts[2]) * scales[2];
            var fy = (l + 16) / 116;
            xyz[0] = white[0] * inverseLab(fy + a / 500);
            xyz[1] = white[1] * inverseLab(fy);
            xyz[2] = white[2] * inverseLab(fy - b / 200);
            store(planes, i, matrix, xyz);
        }
    }

    /**
     * Converts CIECAM97s J*a*b* planes to 16-bit sRGB (M.11.7.4.2): each sample is unscaled with the ranges and
     * offsets of the enumerated parameters, or their defaults; J is the lightness and a and b the rectangular
     * coordinates of chroma and hue ({@code C cos h} and {@code C sin h}); the inverse CIECAM97s model gives CIE XYZ
     * under D65, encoded with the sRGB transfer function; colors outside the sRGB gamut are clipped.
     *
     * @implNote This implementation inverts the model as the CIE TC1-34 report (1998) states it, under the viewing
     * conditions of its first worked example, since the file format signals none: a D65 white, an adapting luminance
     * of 318.31 cd/m^2, a background of 20% and an average surround.
     * @param planes the J, a and b planes, replaced by R, G and B from 0 to 65535
     * @param channels the three channels
     * @param parameters the {@code EP} field: {@code RJ}, {@code OJ}, {@code RA}, {@code OA}, {@code RB} and
     * {@code OB}, or {@code null} for the defaults
     */
    static void jab(int[][] planes, Jp2Channel[] channels, byte[] parameters) {
        var ranges = new double[]{100, 255, 255};
        var offsets = new double[3];
        for (var c = 1; c < 3; c++) {
            if (!channels[c].signed()) {
                offsets[c] = Math.scalb(1.0, channels[c].precision() - 1);
            }
        }
        if (parameters != null && parameters.length >= 24) {
            for (var c = 0; c < 3; c++) {
                ranges[c] = ByteArrays.u32be(parameters, 8 * c);
                offsets[c] = ByteArrays.u32be(parameters, 8 * c + 4);
            }
        }
        var scales = new double[3];
        var shifts = new double[3];
        for (var c = 0; c < 3; c++) {
            var max = signedMax(channels[c]);
            scales[c] = ranges[c] / max;
            shifts[c] = offsetScale(max, channels[c].precision()) * offsets[c];
        }
        var model = new Ciecam97s(new double[]{D65[0] * 100, 100, D65[2] * 100}, 318.31, 20, 1.0, 0.69, 1.0, 1.0);
        var length = planes[0].length;
        var xyz = new double[3];
        for (var i = 0; i < length; i++) {
            var j = (planes[0][i] - shifts[0]) * scales[0];
            var a = (planes[1][i] - shifts[1]) * scales[1];
            var b = (planes[2][i] - shifts[2]) * scales[2];
            model.inverse(j, StrictMath.hypot(a, b), Math.toDegrees(StrictMath.atan2(b, a)), xyz);
            xyz[0] /= 100;
            xyz[1] /= 100;
            xyz[2] /= 100;
            store(planes, i, XYZ_TO_SRGB, xyz);
        }
    }

    /**
     * Converts CIE XYZ to linear sRGB, encodes it and stores it as 16-bit samples.
     *
     * @param planes the planes receiving R, G and B
     * @param i the sample index
     * @param matrix the matrix from the XYZ values to linear sRGB
     * @param xyz the XYZ values
     */
    private static void store(int[][] planes, int i, double[][] matrix, double[] xyz) {
        for (var c = 0; c < 3; c++) {
            var linear = matrix[c][0] * xyz[0] + matrix[c][1] * xyz[1] + matrix[c][2] * xyz[2];
            planes[c][i] = quantize(encode(linear), 65535);
        }
    }

    /**
     * Returns the nominal largest value of a channel (Table M.24bis).
     *
     * @param channel the channel, for its precision and signedness
     * @return {@code 2^p - 1} unsigned, {@code 2^(p-1) - 1} signed
     */
    private static double signedMax(Jp2Channel channel) {
        return channel.signed() ? Math.scalb(1.0, channel.precision() - 1) - 1 : Math.scalb(1.0, channel.precision())
                - 1;
    }

    /**
     * Returns the factor of an offset in equations M-18 and M-19: {@code 2^ceil(log2 MAX) / 2^p}.
     *
     * @param max the nominal largest value
     * @param precision the channel precision
     * @return the factor
     */
    private static double offsetScale(double max, int precision) {
        var exponent = max <= 1 ? 0 : 64 - Long.numberOfLeadingZeros((long) max - 1);
        return Math.scalb(1.0, exponent - precision);
    }

    /**
     * Inverts the CIE 1976 lightness function.
     *
     * @param t the value of {@code f}
     * @return the relative tristimulus value
     */
    private static double inverseLab(double t) {
        var delta = 6.0 / 29;
        return t > delta ? t * t * t : 3 * delta * delta * (t - 4.0 / 29);
    }

    /**
     * Returns the white of an illuminant field ({@code IL} of Table M.29, Rec. ITU-T T.4 annex E): a standard
     * illuminant, or a correlated color temperature ({@code CT} and the temperature in kelvin), read on the CIE
     * daylight locus from 4000 K and on the Planckian locus below it.
     *
     * @param data the bytes holding the field
     * @param at the index of the field
     * @return the XYZ of the white with Y = 1; D50 for an unknown illuminant
     */
    static double[] illuminant(byte[] data, int at) {
        var code = (int) ByteArrays.u32be(data, at);
        return switch (code) {
            case 0x00443530 -> D50;
            case 0x00443635 -> new double[]{0.95047, 1, 1.08883};
            case 0x00443735 -> new double[]{0.94972, 1, 1.22638};
            case 0x00005341 -> new double[]{1.09850, 1, 0.35585};
            case 0x00005343 -> new double[]{0.98074, 1, 1.18232};
            case 0x00004632 -> new double[]{0.99186, 1, 0.67393};
            case 0x00004637 -> new double[]{0.95041, 1, 1.08747};
            case 0x00463131 -> new double[]{1.00962, 1, 0.64350};
            default -> (code >>> 16) == 0x4354 ? temperature(code & 0xFFFF) : D50;
        };
    }

    /**
     * Returns the white of a correlated color temperature.
     *
     * @param kelvin the temperature
     * @return the XYZ with Y = 1
     */
    private static double[] temperature(int kelvin) {
        var t = Math.clamp(kelvin, 1667, 25000);
        double x;
        double y;
        if (t >= 4000) {
            // CIE 15 daylight locus
            x = t <= 7000 ? -4.6070e9 / (t * t * (double) t) + 2.9678e6 / (t * (double) t) + 0.09911e3 / t + 0.244063
                    : -2.0064e9 / (t * t * (double) t) + 1.9018e6 / (t * (double) t) + 0.24748e3 / t + 0.237040;
            y = -3.0 * x * x + 2.87 * x - 0.275;
        } else {
            // the cubic approximation of the Planckian locus by Kim, Kang and Kwak (2002)
            x = -0.2661239e9 / (t * t * (double) t) - 0.2343589e6 / (t * (double) t) + 0.8776956e3 / t + 0.179910;
            y = t <= 2222 ? -1.1063814 * x * x * x - 1.34811020 * x * x + 2.18555832 * x - 0.20219683
                    : -0.9549476 * x * x * x - 1.37418593 * x * x + 2.09137015 * x - 0.16748867;
        }
        return new double[]{x / y, 1, (1 - x - y) / y};
    }

    /**
     * Returns the Bradford chromatic adaptation from one white to another.
     *
     * @param from the source white
     * @param to the destination white
     * @return the matrix
     */
    private static double[][] adaptation(double[] from, double[] to) {
        var source = apply(BRADFORD, from);
        var target = apply(BRADFORD, to);
        var scale = new double[][]{
                {target[0] / source[0], 0, 0}, {0, target[1] / source[1], 0}, {0, 0, target[2] / source[2]}};
        return multiply(BRADFORD_INVERSE, multiply(scale, BRADFORD));
    }

    /**
     * Returns the matrix from CIE XYZ to linear sRGB, from the primaries and white of IEC 61966-2-1.
     *
     * @return the matrix
     */
    private static double[][] srgbMatrix() {
        double[][] chromaticities = {{0.64, 0.33}, {0.30, 0.60}, {0.15, 0.06}};
        var primaries = new double[3][3];
        for (var c = 0; c < 3; c++) {
            var x = chromaticities[c][0];
            var y = chromaticities[c][1];
            primaries[0][c] = x / y;
            primaries[1][c] = 1;
            primaries[2][c] = (1 - x - y) / y;
        }
        var weights = apply(invert(primaries), D65);
        for (var r = 0; r < 3; r++) {
            for (var c = 0; c < 3; c++) {
                primaries[r][c] *= weights[c];
            }
        }
        return invert(primaries);
    }

    /**
     * Encodes linear sRGB with the sRGB transfer function.
     *
     * @param linear the linear value
     * @return the encoded value, clipped to 0 to 1
     */
    private static double encode(double linear) {
        if (linear <= 0) {
            return 0;
        }
        if (linear >= 1) {
            return 1;
        }
        return linear <= 0.0031308 ? 12.92 * linear : 1.055 * StrictMath.pow(linear, 1 / 2.4) - 0.055;
    }

    /**
     * Rounds a value of 0 to 1 to an integer sample.
     *
     * @param value the value from 0 to 1
     * @param max the largest sample
     * @return the sample, clamped
     */
    private static int quantize(double value, double max) {
        return (int) Math.clamp(Math.round(value * max), 0, (long) max);
    }

    /**
     * Applies a 3x3 matrix to a vector.
     *
     * @param matrix the 3x3 matrix, row-major
     * @param vector the three components
     * @return the product
     */
    static double[] apply(double[][] matrix, double[] vector) {
        var result = new double[3];
        apply(matrix, vector, result);
        return result;
    }

    /**
     * Applies a 3x3 matrix to a vector into a caller's buffer.
     *
     * @param matrix the 3x3 matrix, row-major
     * @param vector the three components
     * @param result receives the product; a different array from {@code vector}
     */
    private static void apply(double[][] matrix, double[] vector, double[] result) {
        for (var r = 0; r < 3; r++) {
            result[r] = matrix[r][0] * vector[0] + matrix[r][1] * vector[1] + matrix[r][2] * vector[2];
        }
    }

    /**
     * Multiplies two 3x3 matrices.
     *
     * @param a the left matrix
     * @param b the right matrix
     * @return {@code a b}
     */
    private static double[][] multiply(double[][] a, double[][] b) {
        var result = new double[3][3];
        for (var r = 0; r < 3; r++) {
            for (var c = 0; c < 3; c++) {
                result[r][c] = a[r][0] * b[0][c] + a[r][1] * b[1][c] + a[r][2] * b[2][c];
            }
        }
        return result;
    }

    /**
     * Inverts a 3x3 matrix.
     *
     * @param m the matrix, invertible
     * @return its inverse
     */
    private static double[][] invert(double[][] m) {
        var a = m[1][1] * m[2][2] - m[1][2] * m[2][1];
        var b = m[1][2] * m[2][0] - m[1][0] * m[2][2];
        var c = m[1][0] * m[2][1] - m[1][1] * m[2][0];
        var determinant = m[0][0] * a + m[0][1] * b + m[0][2] * c;
        return new double[][]{
                {a / determinant, (m[0][2] * m[2][1] - m[0][1] * m[2][2]) / determinant,
                        (m[0][1] * m[1][2] - m[0][2] * m[1][1]) / determinant},
                {b / determinant, (m[0][0] * m[2][2] - m[0][2] * m[2][0]) / determinant,
                        (m[0][2] * m[1][0] - m[0][0] * m[1][2]) / determinant},
                {c / determinant, (m[0][1] * m[2][0] - m[0][0] * m[2][1]) / determinant,
                        (m[0][0] * m[1][1] - m[0][1] * m[1][0]) / determinant}};
    }

    /**
     * Holds the viewing conditions of the CIECAM97s model and inverts it (CIE TC1-34, 1998, sections 2 and 4.2).
     */
    static final class Ciecam97s {
        /**
         * The white's Bradford responses.
         */
        private final double[] whiteResponses;

        /**
         * The exponent {@code p} of the short-wavelength response.
         */
        private final double p;

        /**
         * The degree of adaptation {@code D}.
         */
        private final double d;

        /**
         * The luminance level adaptation factor {@code F_L}.
         */
        private final double fl;

        /**
         * The background induction factor {@code n}.
         */
        private final double n;

        /**
         * The brightness and chromatic induction factors {@code N_bb = N_cb}.
         */
        private final double nbb;

        /**
         * The base exponential nonlinearity {@code z}.
         */
        private final double z;

        /**
         * The impact of the surround {@code c}.
         */
        private final double c;

        /**
         * The chromatic induction factor {@code N_c}.
         */
        private final double nc;

        /**
         * The white's achromatic response {@code A_w}.
         */
        private final double aw;

        /**
         * A buffer of three responses reused by {@link #inverse}.
         */
        private final double[] scratch = new double[3];

        /**
         * A second buffer of three responses reused by {@link #inverse}.
         */
        private final double[] responses = new double[3];

        /**
         * Computes the factors of a set of viewing conditions.
         *
         * @param white the XYZ of the white, with Y = 100
         * @param la the adapting luminance in cd/m^2
         * @param yb the relative luminance of the background
         * @param f the degree of adaptation factor {@code F}
         * @param c the impact of the surround {@code c}
         * @param nc the chromatic induction factor {@code N_c}
         * @param fll the lightness contrast factor {@code F_LL}
         */
        Ciecam97s(double[] white, double la, double yb, double f, double c, double nc, double fll) {
            this.c = c;
            this.nc = nc;
            whiteResponses = apply(BRADFORD, new double[]{white[0] / white[1], 1, white[2] / white[1]});
            p = StrictMath.pow(whiteResponses[2], 0.0834);
            d = f - f / (1 + 2 * StrictMath.pow(la, 0.25) + la * la / 300);
            var k = 1 / (5 * la + 1);
            var k4 = k * k * k * k;
            fl = 0.2 * k4 * (5 * la) + 0.1 * (1 - k4) * (1 - k4) * StrictMath.cbrt(5 * la);
            n = yb / white[1];
            nbb = 0.725 * StrictMath.pow(1 / n, 0.2);
            z = 1 + fll * Math.sqrt(n);
            var adapted = new double[]{
                    (d / whiteResponses[0] + 1 - d) * whiteResponses[0] * white[1],
                    (d / whiteResponses[1] + 1 - d) * whiteResponses[1] * white[1],
                    (d / StrictMath.pow(whiteResponses[2], p) + 1 - d) * StrictMath.pow(whiteResponses[2], p)
                            * white[1]};
            var cones = apply(BRADFORD_TO_HPE, adapted);
            var ra = compress(cones[0]);
            var ga = compress(cones[1]);
            var ba = compress(cones[2]);
            aw = (2 * ra + ga + ba / 20 - 2.05) * nbb;
        }

        /**
         * Applies the post-adaptation nonlinear compression (equations 2.15 to 2.17).
         *
         * @param response a cone response
         * @return the compressed response
         */
        private double compress(double response) {
            var x = StrictMath.pow(fl * Math.abs(response) / 100, 0.73);
            var value = 40 * x / (x + 2);
            return (response < 0 ? -value : value) + 1;
        }

        /**
         * Returns the eccentricity factor of a hue angle (equation 2.21).
         *
         * @param hue the hue angle in degrees, 0 to 360
         * @return {@code e}
         */
        private static double eccentricity(double hue) {
            var h = hue < UNIQUE_HUES[0][0] ? hue + 360 : hue;
            for (var i = 0; i < UNIQUE_HUES.length - 1; i++) {
                if (h < UNIQUE_HUES[i + 1][0]) {
                    var h1 = UNIQUE_HUES[i][0];
                    var e1 = UNIQUE_HUES[i][1];
                    return e1 + (UNIQUE_HUES[i + 1][1] - e1) * (h - h1) / (UNIQUE_HUES[i + 1][0] - h1);
                }
            }
            return UNIQUE_HUES[0][1];
        }

        /**
         * Computes the XYZ of a lightness, chroma and hue angle (section 4.2).
         *
         * @param j the lightness {@code J}
         * @param chroma the chroma {@code C}
         * @param hue the hue angle {@code h} in degrees
         * @param xyz receives the XYZ, relative to a white of Y = 100
         */
        void inverse(double j, double chroma, double hue, double[] xyz) {
            var h = hue < 0 ? hue + 360 : hue;
            var achromatic = StrictMath.pow(Math.max(j, 0) / 100, 1 / (c * z)) * aw;
            var e = eccentricity(h);
            var s = StrictMath.pow(chroma, 1 / 0.69) / StrictMath.pow(2.44 * StrictMath.pow(Math.max(j, 0) / 100,
                    0.67 * n) * (1.64 - StrictMath.pow(0.29, n)), 1 / 0.69);
            var q = achromatic / nbb + 2.05;
            var cos = StrictMath.cos(Math.toRadians(h));
            var sin = StrictMath.sin(Math.toRadians(h));
            // equation 4.7 rewritten without tan h: the radius of (a, b) solves the saturation equation 2.26
            var radius = s * q / (50000 * e * nc * nbb / 13 + s * (11.0 / 23 * cos + 108.0 / 23 * sin));
            var a = radius * cos;
            var b = radius * sin;
            var ra = 20.0 / 61 * q + 41.0 / 61 * 11.0 / 23 * a + 288.0 / 61 / 23 * b;
            var ga = 20.0 / 61 * q - 81.0 / 61 * 11.0 / 23 * a - 261.0 / 61 / 23 * b;
            var ba = 20.0 / 61 * q - 20.0 / 61 * 11.0 / 23 * a - 20.0 / 61 * 315.0 / 23 * b;
            var cones = scratch;
            cones[0] = expand(ra) / fl;
            cones[1] = expand(ga) / fl;
            cones[2] = expand(ba) / fl;
            var adapted = responses;
            apply(HPE_TO_BRADFORD, cones, adapted);
            var yc = 0.43231 * adapted[0] + 0.51836 * adapted[1] + 0.04929 * adapted[2];
            if (yc == 0) {
                xyz[0] = xyz[1] = xyz[2] = 0;
                return;
            }
            var r = adapted[0] / yc / (d / whiteResponses[0] + 1 - d);
            var g = adapted[1] / yc / (d / whiteResponses[1] + 1 - d);
            var blue = adapted[2] / yc;
            var bp = StrictMath.pow(Math.abs(blue), 1 / p) / StrictMath.pow(d / StrictMath.pow(whiteResponses[2], p)
                    + 1 - d, 1 / p);
            if (blue < 0) {
                bp = -bp;
            }
            var yPrime = 0.43231 * r * yc + 0.51836 * g * yc + 0.04929 * bp * yc;
            var ratio = yPrime / yc;
            var correction = ratio > 0 ? StrictMath.pow(ratio, 1 / p - 1) : 1;
            cones[0] = yc * r;
            cones[1] = yc * g;
            cones[2] = yc * bp / correction;
            apply(BRADFORD_INVERSE, cones, xyz);
        }

        /**
         * Inverts the post-adaptation nonlinear compression (equations 4.12 to 4.15), before the division by
         * {@code F_L}.
         *
         * @param compressed a compressed response
         * @return the cone response times {@code F_L}
         */
        private static double expand(double compressed) {
            if (compressed - 1 < 0) {
                return -100 * StrictMath.pow((2 - 2 * compressed) / (39 + compressed), 1 / 0.73);
            }
            return 100 * StrictMath.pow((2 * compressed - 2) / (41 - compressed), 1 / 0.73);
        }
    }
}
