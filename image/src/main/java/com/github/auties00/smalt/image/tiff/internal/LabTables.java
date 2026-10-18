package com.github.auties00.smalt.image.tiff.internal;

import com.github.auties00.smalt.MediaException;

/**
 * Converts TIFF CIE L*a*b* samples to 8-bit RGB as libtiff's {@code TIFFReadRGBAImage} renders
 * them for display.
 *
 * <p>Three sample encodings are read:
 * <ul>
 *   <li>CIE L*a*b* (photometric interpretation 8): {@code L*} unsigned, {@code 0} to the largest
 *       code covering 0 to 100; {@code a*} and {@code b*} signed two's complement, one unit per
 *       code at 8 bits and 1/256 of a unit per code at 16 bits;</li>
 *   <li>ICC L*a*b* (9), the ICC version 2 encoding: {@code L*} as above but with 0xFF00 for 100
 *       at 16 bits; {@code a*} and {@code b*} unsigned with 128 (or 0x8000) for zero;</li>
 *   <li>ITU L*a*b* (10), from ITU-T T.42: each sample maps linearly from {@code 0..2^n - 1} onto
 *       the range its {@code Decode} pair gives, by default {@code L*} 0 to 100, {@code a*} -85
 *       to 85 and {@code b*} -75 to 125.</li>
 * </ul>
 *
 * <p>The color is then converted to XYZ relative to the reference white the {@code WhitePoint}
 * tag gives (CIE D50 when absent), and from XYZ to RGB through the sRGB primaries matrix with a
 * pure 2.4 power law quantized to 1500 steps, the display libtiff calls {@code display_sRGB}. The
 * matrix is applied without chromatic adaptation, so the result is libtiff's rendering rather
 * than a colorimetric sRGB conversion.
 *
 * @implNote This implementation is a port of libtiff 4.7's tif_color.c
 * ({@code TIFFCIELabToRGBInit}, {@code TIFFCIELab16ToXYZ}, {@code TIFFXYZToRGB}) and the
 * {@code display_sRGB} constants and white point set-up of tif_getimage.c
 * ({@code initCIELabConversion}). The arithmetic is single precision in the same order, so CIE
 * L*a*b* output equals libtiff's exactly. libtiff does not render ICC or ITU L*a*b*; those
 * encodings are decoded to the same L*a*b* values and rendered the same way.
 */
public final class LabTables {
    /**
     * The CIE L*a*b* encoding.
     */
    public static final int CIELAB = 8;

    /**
     * The ICC L*a*b* encoding.
     */
    public static final int ICCLAB = 9;

    /**
     * The ITU L*a*b* encoding.
     */
    public static final int ITULAB = 10;

    /**
     * The number of steps of the luminance-to-value tables.
     */
    private static final int RANGE = 1500;

    /**
     * The XYZ to luminance matrix of the sRGB display, row-major.
     */
    private static final float[] MATRIX = {
            3.2410F, -1.5374F, -0.4986F,
            -0.9692F, 1.8760F, 0.0416F,
            0.0556F, -0.2040F, 1.0570F};

    /**
     * The light output for reference white.
     */
    private static final float WHITE_LIGHT = 100.0F;

    /**
     * The residual light output for a black pixel.
     */
    private static final float BLACK_LIGHT = 1.0F;

    /**
     * The pixel value of reference white.
     */
    private static final int WHITE_VALUE = 255;

    /**
     * The default ITU L*a*b* {@code Decode} ranges: L* minimum and maximum, then a*, then b*.
     */
    private static final double[] ITU_DEFAULT = {0, 100, -85, 85, -75, 125};

    /**
     * The value of each luminance step, shared by the three guns (their gammas are equal).
     */
    private static final float[] VALUES = values();

    /**
     * The luminance covered by one table step.
     */
    private static final float STEP = (WHITE_LIGHT - BLACK_LIGHT) / RANGE;

    /**
     * The encoding: {@link #CIELAB}, {@link #ICCLAB} or {@link #ITULAB}.
     */
    private final int encoding;

    /**
     * The reference white X.
     */
    private final float x0;

    /**
     * The reference white Y.
     */
    private final float y0;

    /**
     * The reference white Z.
     */
    private final float z0;

    /**
     * The ITU {@code Decode} ranges, six values, or {@code null} for the other encodings.
     */
    private final double[] decode;

    /**
     * Builds the conversion.
     *
     * @param encoding   {@link #CIELAB}, {@link #ICCLAB} or {@link #ITULAB}
     * @param whitePoint the {@code WhitePoint} chromaticity {@code x, y}, or {@code null} for
     *                   CIE D50
     * @param decode     the {@code Decode} values of an ITU L*a*b* image, or {@code null} for the
     *                   defaults
     * @throws MediaException.Malformed if the white point's {@code y} is zero
     */
    public LabTables(int encoding, double[] whitePoint, double[] decode) {
        this.encoding = encoding;
        float wx;
        float wy;
        if (whitePoint != null && whitePoint.length >= 2) {
            wx = (float) whitePoint[0];
            wy = (float) whitePoint[1];
        } else {
            wx = 96.4250F / (96.4250F + 100.0F + 82.4680F);
            wy = 100.0F / (96.4250F + 100.0F + 82.4680F);
        }
        if (wy == 0.0F) {
            throw new MediaException.Malformed("invalid TIFF WhitePoint");
        }
        y0 = 100.0F;
        x0 = wx / wy * y0;
        z0 = (1.0F - wx - wy) / wy * y0;
        this.decode = encoding == ITULAB ? (decode != null && decode.length >= 6 ? decode : ITU_DEFAULT) : null;
    }

    /**
     * Builds the table of display values per luminance step.
     *
     * @return the values, {@code RANGE + 1} of them
     */
    private static float[] values() {
        var values = new float[RANGE + 1];
        var gamma = 1.0 / 2.4F;
        for (var i = 0; i <= RANGE; i++) {
            values[i] = WHITE_VALUE * (float) Math.pow((double) i / RANGE, gamma);
        }
        return values;
    }

    /**
     * Converts one 8-bit sample triple.
     *
     * @param l the stored L* code
     * @param a the stored a* code, as an unsigned byte
     * @param b the stored b* code, as an unsigned byte
     * @return the packed {@code r << 16 | g << 8 | b}
     */
    public int rgb8(int l, int a, int b) {
        return switch (encoding) {
            case CIELAB -> rgb((float) (l * 257) * 100.0F / 65535.0F, (byte) a, (byte) b);
            case ICCLAB -> rgb((float) (l * 257) * 100.0F / 65535.0F, a - 128, b - 128);
            default -> rgb(itu(0, l, 255), itu(1, a, 255), itu(2, b, 255));
        };
    }

    /**
     * Converts one 16-bit sample triple.
     *
     * @param l the stored L* code
     * @param a the stored a* code, as an unsigned 16-bit value
     * @param b the stored b* code, as an unsigned 16-bit value
     * @return the packed {@code r << 16 | g << 8 | b}
     */
    public int rgb16(int l, int a, int b) {
        return switch (encoding) {
            case CIELAB -> rgb((float) l * 100.0F / 65535.0F, (float) (short) a / 256.0F, (float) (short) b / 256.0F);
            case ICCLAB -> rgb((float) Math.min(l, 0xFF00) * 100.0F / 65280.0F, (float) (a - 0x8000) / 256.0F,
                    (float) (b - 0x8000) / 256.0F);
            default -> rgb(itu(0, l, 65535), itu(1, a, 65535), itu(2, b, 65535));
        };
    }

    /**
     * Maps an ITU L*a*b* code onto its {@code Decode} range.
     *
     * @param channel the channel: 0 for L*, 1 for a*, 2 for b*
     * @param code    the stored code
     * @param max     the largest code
     * @return the channel value
     */
    private float itu(int channel, int code, int max) {
        var low = decode[channel * 2];
        var high = decode[channel * 2 + 1];
        return (float) (low + code * (high - low) / max);
    }

    /**
     * Converts an L*a*b* color to packed 8-bit RGB.
     *
     * @param lightness the L*, from 0 to 100
     * @param a         the a*
     * @param b         the b*
     * @return the packed {@code r << 16 | g << 8 | b}
     */
    private int rgb(float lightness, float a, float b) {
        float y;
        float cby;
        if (lightness < 8.856F) {
            y = (lightness * y0) / 903.292F;
            cby = 7.787F * (y / y0) + 16.0F / 116.0F;
        } else {
            cby = (lightness + 16.0F) / 116.0F;
            y = y0 * cby * cby * cby;
        }
        var tmp = a / 500.0F + cby;
        var x = tmp < 0.2069F ? x0 * (tmp - 0.13793F) / 7.787F : x0 * tmp * tmp * tmp;
        tmp = cby - b / 200.0F;
        var z = tmp < 0.2069F ? z0 * (tmp - 0.13793F) / 7.787F : z0 * tmp * tmp * tmp;
        var red = gun(MATRIX[0] * x + MATRIX[1] * y + MATRIX[2] * z);
        var green = gun(MATRIX[3] * x + MATRIX[4] * y + MATRIX[5] * z);
        var blue = gun(MATRIX[6] * x + MATRIX[7] * y + MATRIX[8] * z);
        return red << 16 | green << 8 | blue;
    }

    /**
     * Converts the luminance of one gun to its display value.
     *
     * @param luminance the luminance
     * @return the value, from 0 to 255
     */
    private static int gun(float luminance) {
        var clipped = Math.min(Math.max(luminance, BLACK_LIGHT), WHITE_LIGHT);
        var index = (int) Math.min(RANGE, (long) ((clipped - BLACK_LIGHT) / STEP));
        var value = VALUES[index];
        var rounded = (int) (value > 0 ? value + 0.5 : value - 0.5);
        return Math.min(rounded, WHITE_VALUE);
    }
}
