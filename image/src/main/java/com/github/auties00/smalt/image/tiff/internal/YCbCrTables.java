package com.github.auties00.smalt.image.tiff.internal;

/**
 * Converts 8-bit TIFF YCbCr samples to RGB as TIFF 6.0 section 21 specifies, honoring the
 * {@code YCbCrCoefficients} and {@code ReferenceBlackWhite} tags.
 *
 * <p>The conversion is {@code R = Y + Cr * (2 - 2 * LumaRed)},
 * {@code B = Y + Cb * (2 - 2 * LumaBlue)} and
 * {@code G = Y - LumaBlue * Cb * (2 - 2 * LumaBlue) / LumaGreen - LumaRed * Cr * (2 - 2 * LumaRed) / LumaGreen},
 * where each sample is first mapped from its code range to the nominal range by the reference
 * black and white values.
 *
 * @implNote This implementation is a port of libtiff's tif_color.c ({@code TIFFYCbCrToRGBInit}
 * and {@code TIFFYCbCrtoRGB}): the same single-precision arithmetic builds the same 16-bit fixed
 * point tables, so the output equals libtiff's {@code TIFFReadRGBAImage} exactly.
 */
public final class YCbCrTables {
    /**
     * The fixed-point precision of the chroma tables.
     */
    private static final int SHIFT = 16;

    /**
     * One half in fixed point.
     */
    private static final int ONE_HALF = 1 << (SHIFT - 1);

    /**
     * The red contribution of each Cr code.
     */
    private final int[] crR = new int[256];

    /**
     * The blue contribution of each Cb code.
     */
    private final int[] cbB = new int[256];

    /**
     * The green contribution of each Cr code, in fixed point.
     */
    private final int[] crG = new int[256];

    /**
     * The green contribution of each Cb code, in fixed point, rounding bias included.
     */
    private final int[] cbG = new int[256];

    /**
     * The nominal luma of each Y code.
     */
    private final int[] y = new int[256];

    /**
     * Builds the tables.
     *
     * @param luma           the {@code YCbCrCoefficients}: red, green and blue luma weights
     * @param referenceBlack the {@code ReferenceBlackWhite} values: Y black, Y white, Cb black,
     *                       Cb white, Cr black, Cr white
     */
    public YCbCrTables(float[] luma, float[] referenceBlack) {
        var lumaRed = luma[0];
        var lumaGreen = luma[1];
        var lumaBlue = luma[2];
        var f1 = 2 - 2 * lumaRed;
        var d1 = fix(clamp(f1, 0.0F, 2.0F));
        var f2 = lumaRed * f1 / lumaGreen;
        var d2 = -fix(clamp(f2, 0.0F, 2.0F));
        var f3 = 2 - 2 * lumaBlue;
        var d3 = fix(clamp(f3, 0.0F, 2.0F));
        var f4 = lumaBlue * f3 / lumaGreen;
        var d4 = -fix(clamp(f4, 0.0F, 2.0F));
        for (int i = 0, x = -128; i < 256; i++, x++) {
            var cr = (int) clampWide(codeToValue(x, referenceBlack[4] - 128.0F, referenceBlack[5] - 128.0F, 127),
                    -128.0F * 32, 128.0F * 32);
            var cb = (int) clampWide(codeToValue(x, referenceBlack[2] - 128.0F, referenceBlack[3] - 128.0F, 127),
                    -128.0F * 32, 128.0F * 32);
            crR[i] = (d1 * cr + ONE_HALF) >> SHIFT;
            cbB[i] = (d3 * cb + ONE_HALF) >> SHIFT;
            crG[i] = d2 * cr;
            cbG[i] = d4 * cb + ONE_HALF;
            y[i] = (int) clampWide(codeToValue(x + 128, referenceBlack[0], referenceBlack[1], 255),
                    -128.0F * 32, 128.0F * 32);
        }
    }

    /**
     * Converts one sample to a packed pixel.
     *
     * @param luma the Y code
     * @param cb   the Cb code
     * @param cr   the Cr code
     * @return the pixel as {@code r << 16 | g << 8 | b}
     */
    public int toRgb(int luma, int cb, int cr) {
        var base = y[luma];
        var r = clamp255(base + crR[cr]);
        var g = clamp255(base + ((cbG[cb] + crG[cr]) >> SHIFT));
        var b = clamp255(base + cbB[cb]);
        return r << 16 | g << 8 | b;
    }

    /**
     * Converts a real to 16-bit fixed point as libtiff's {@code FIX} does.
     *
     * @param value the real
     * @return the fixed-point value
     */
    private static int fix(float value) {
        return (int) ((double) (value * (float) (1L << SHIFT)) + 0.5);
    }

    /**
     * Maps a code to its nominal value as libtiff's {@code Code2V} does.
     *
     * @param code       the code
     * @param black      the reference black
     * @param white      the reference white
     * @param codeRange  the nominal range
     * @return the value
     */
    private static float codeToValue(int code, float black, float white, int codeRange) {
        var span = white - black != 0 ? white - black : 1;
        return ((code - (int) black) * (float) codeRange) / span;
    }

    /**
     * Clamps a real, mapping NaN to the minimum, as libtiff's {@code CLAMP} does.
     *
     * @param value the value
     * @param min   the minimum
     * @param max   the maximum
     * @return the clamped value
     */
    private static float clamp(float value, float min, float max) {
        return !(value >= min) ? min : value > max ? max : value;
    }

    /**
     * Clamps a real as libtiff's {@code CLAMPw} does.
     *
     * @param value the value
     * @param min   the minimum
     * @param max   the maximum
     * @return the clamped value
     */
    private static float clampWide(float value, float min, float max) {
        if (value < min) {
            return min;
        }
        return value > max ? max : value;
    }

    /**
     * Clamps an integer to a sample.
     *
     * @param value the value
     * @return the value limited to 0..255
     */
    private static int clamp255(int value) {
        return value < 0 ? 0 : Math.min(value, 255);
    }
}
