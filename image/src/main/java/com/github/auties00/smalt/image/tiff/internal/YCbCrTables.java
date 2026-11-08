package com.github.auties00.smalt.image.tiff.internal;

/**
 * Converts TIFF YCbCr samples to RGB as TIFF 6.0 section 21 specifies, honoring the
 * {@code YCbCrCoefficients} and {@code ReferenceBlackWhite} tags.
 *
 * <p>The conversion is {@code R = Y + Cr * (2 - 2 * LumaRed)},
 * {@code B = Y + Cb * (2 - 2 * LumaBlue)} and
 * {@code G = Y - LumaBlue * Cb * (2 - 2 * LumaBlue) / LumaGreen - LumaRed * Cr * (2 - 2 * LumaRed) / LumaGreen},
 * where each sample is first mapped from its code range to the nominal range by the reference
 * black and white values (section 20): {@code (code - black) * range / (white - black)}, the range
 * being {@code 2^n - 1} for Y and {@code 2^(n-1) - 1} for Cb and Cr at {@code n} bits per sample
 * (255 and 127 at 8 bits). The 8-bit conversion produces 8-bit RGB; the conversion of other depths
 * produces 16-bit RGB, {@code round(v * 65535 / (2^n - 1))} of the nominal value {@code v} clamped
 * to {@code 0..2^n - 1}.
 *
 * @implNote This implementation is a port of libtiff's tif_color.c ({@code TIFFYCbCrToRGBInit}
 * and {@code TIFFYCbCrtoRGB}) for 8-bit samples: the same single-precision arithmetic builds the
 * same 16-bit fixed point tables, so the output equals libtiff's {@code TIFFReadRGBAImage}
 * exactly. libtiff converts no other depth; those are converted in double precision.
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
    private final int[] crR;

    /**
     * The blue contribution of each Cb code.
     */
    private final int[] cbB;

    /**
     * The green contribution of each Cr code, in fixed point.
     */
    private final int[] crG;

    /**
     * The green contribution of each Cb code, in fixed point, rounding bias included.
     */
    private final int[] cbG;

    /**
     * The nominal luma of each Y code.
     */
    private final int[] y;

    /**
     * The bits per sample, 8 for the table conversion.
     */
    private final int bits;

    /**
     * The reference black of Y, for depths other than 8.
     */
    private final double yBlack;

    /**
     * The scale from Y codes to nominal values, for depths other than 8.
     */
    private final double yScale;

    /**
     * The reference black of Cb, for depths other than 8.
     */
    private final double cbBlack;

    /**
     * The scale from Cb codes to nominal values, for depths other than 8.
     */
    private final double cbScale;

    /**
     * The reference black of Cr, for depths other than 8.
     */
    private final double crBlack;

    /**
     * The scale from Cr codes to nominal values, for depths other than 8.
     */
    private final double crScale;

    /**
     * The red weight of Cr, {@code 2 - 2 * LumaRed}.
     */
    private final double redCr;

    /**
     * The green weight of Cb, {@code LumaBlue * (2 - 2 * LumaBlue) / LumaGreen}.
     */
    private final double greenCb;

    /**
     * The green weight of Cr, {@code LumaRed * (2 - 2 * LumaRed) / LumaGreen}.
     */
    private final double greenCr;

    /**
     * The blue weight of Cb, {@code 2 - 2 * LumaBlue}.
     */
    private final double blueCb;

    /**
     * Builds the tables of 8-bit samples.
     *
     * @param luma           the {@code YCbCrCoefficients}: red, green and blue luma weights
     * @param referenceBlack the {@code ReferenceBlackWhite} values: Y black, Y white, Cb black,
     *                       Cb white, Cr black, Cr white
     */
    public YCbCrTables(float[] luma, float[] referenceBlack) {
        bits = 8;
        crR = new int[256];
        cbB = new int[256];
        crG = new int[256];
        cbG = new int[256];
        y = new int[256];
        yBlack = 0;
        yScale = 0;
        cbBlack = 0;
        cbScale = 0;
        crBlack = 0;
        crScale = 0;
        redCr = 0;
        greenCb = 0;
        greenCr = 0;
        blueCb = 0;
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
     * Builds the conversion of samples of any depth from 1 to 16 bits.
     *
     * <p>Missing tags take their defaults: the CCIR 601-1 luma weights and the reference black and
     * white {@code [0, 2^n - 1, 2^(n-1), 2^n - 1, 2^(n-1), 2^n - 1]}, that is no headroom or
     * footroom.
     *
     * @param luma           the {@code YCbCrCoefficients}, or {@code null} for the default
     * @param referenceBlack the {@code ReferenceBlackWhite} values, or {@code null} for the default
     * @param bits           the bits per sample, from 1 to 16
     * @return the conversion; the 8-bit table conversion for 8 bits
     */
    public static YCbCrTables of(double[] luma, double[] referenceBlack, int bits) {
        var weights = luma != null && luma.length >= 3 ? luma : new double[]{0.299, 0.587, 0.114};
        if (bits == 8) {
            var reference = referenceBlack != null && referenceBlack.length >= 6 ? referenceBlack
                    : new double[]{0, 255, 128, 255, 128, 255};
            var lumaFloats = new float[3];
            var referenceFloats = new float[6];
            for (var i = 0; i < 3; i++) {
                lumaFloats[i] = (float) weights[i];
            }
            for (var i = 0; i < 6; i++) {
                referenceFloats[i] = (float) reference[i];
            }
            return new YCbCrTables(lumaFloats, referenceFloats);
        }
        var max = (1 << bits) - 1;
        var half = 1 << (bits - 1);
        var reference = referenceBlack != null && referenceBlack.length >= 6 ? referenceBlack
                : new double[]{0, max, half, max, half, max};
        return new YCbCrTables(weights, reference, bits);
    }

    /**
     * Builds the conversion of samples of a depth other than 8 bits.
     *
     * @param luma           the {@code YCbCrCoefficients}: red, green and blue luma weights
     * @param referenceBlack the {@code ReferenceBlackWhite} values
     * @param bits           the bits per sample
     */
    private YCbCrTables(double[] luma, double[] referenceBlack, int bits) {
        this.bits = bits;
        crR = null;
        cbB = null;
        crG = null;
        cbG = null;
        y = null;
        var max = (double) ((1 << bits) - 1);
        var chromaRange = (double) ((1 << (bits - 1)) - 1);
        yBlack = referenceBlack[0];
        yScale = max / span(referenceBlack[0], referenceBlack[1]);
        cbBlack = referenceBlack[2];
        cbScale = chromaRange / span(referenceBlack[2], referenceBlack[3]);
        crBlack = referenceBlack[4];
        crScale = chromaRange / span(referenceBlack[4], referenceBlack[5]);
        var lumaRed = luma[0];
        var lumaGreen = luma[1] != 0 ? luma[1] : 1;
        var lumaBlue = luma[2];
        redCr = 2 - 2 * lumaRed;
        blueCb = 2 - 2 * lumaBlue;
        greenCb = lumaBlue * blueCb / lumaGreen;
        greenCr = lumaRed * redCr / lumaGreen;
    }

    /**
     * Returns the distance between a reference black and white, 1 when they are equal.
     *
     * @param black the reference black
     * @param white the reference white
     * @return the distance
     */
    private static double span(double black, double white) {
        return white - black != 0 ? white - black : 1;
    }

    /**
     * Returns whether the samples are 8-bit, converted by {@link #toRgb(int, int, int)}.
     *
     * @return {@code true} for 8-bit samples; {@code false} for samples of other depths, converted
     * by {@link #toRgb16(int, int, int)}
     */
    public boolean eightBit() {
        return bits == 8;
    }

    /**
     * Converts one 8-bit sample to a packed pixel.
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
     * Converts one sample of a depth other than 8 bits to a packed 16-bit pixel.
     *
     * @param luma the Y code
     * @param cb   the Cb code
     * @param cr   the Cr code
     * @return the pixel as {@code r << 32 | g << 16 | b}, each component from 0 to 65535
     */
    public long toRgb16(int luma, int cb, int cr) {
        var yValue = (luma - yBlack) * yScale;
        var cbValue = (cb - cbBlack) * cbScale;
        var crValue = (cr - crBlack) * crScale;
        var scale = 65535.0 / ((1 << bits) - 1);
        var r = to16((yValue + crValue * redCr) * scale);
        var g = to16((yValue - cbValue * greenCb - crValue * greenCr) * scale);
        var b = to16((yValue + cbValue * blueCb) * scale);
        return (long) r << 32 | (long) g << 16 | b;
    }

    /**
     * Rounds and clamps a 16-bit component.
     *
     * @param value the component scaled to 0..65535
     * @return the rounded component, from 0 to 65535
     */
    private static int to16(double value) {
        return value <= 0 ? 0 : value >= 65535 ? 65535 : (int) (value + 0.5);
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
