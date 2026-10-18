/* Ports jxl-oxide 0.12.6 (crates/jxl-color: ColourEncoding). Copyright Wonwoo Choi and the jxl-oxide contributors;
   MIT License. Modified for Smalt. */
package com.github.auties00.smalt.image.jxl.internal.header;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.jxl.internal.bitstream.JxlBitReader;

/**
 * Describes the color space of a JPEG XL image (ISO/IEC 18181-1 table A.18,
 * {@code ColorEncoding}): either an ICC profile stored after the header, or enumerated values
 * (color space, white point, primaries, transfer function and rendering intent).
 *
 * @param wantIcc whether an ICC profile follows the image header
 * @param colorSpace {@link #RGB}, {@link #GRAY}, {@link #XYB} or {@link #UNKNOWN}
 * @param whitePoint {@link #WHITE_D65}, {@link #WHITE_CUSTOM}, {@link #WHITE_E} or
 * {@link #WHITE_DCI}
 * @param white the custom white point as {@code x, y} chromaticities, meaningful for
 * {@link #WHITE_CUSTOM}
 * @param primaries {@link #PRIMARIES_SRGB}, {@link #PRIMARIES_CUSTOM},
 * {@link #PRIMARIES_BT2100} or {@link #PRIMARIES_P3}
 * @param customPrimaries the custom red, green and blue chromaticities as {@code xr, yr, xg, yg,
 * xb, yb}, meaningful for {@link #PRIMARIES_CUSTOM}
 * @param transfer the transfer function: one of the {@code TF_} constants, or
 * {@link #TF_GAMMA} for a pure power curve
 * @param gamma the encoding exponent times {@code 10^7} for {@link #TF_GAMMA} (the
 * stored value is the inverse of the decoding gamma), else 0
 * @param renderingIntent the ICC rendering intent, from 0 (perceptual) to 3 (absolute)
 */
public record ColorEncoding(boolean wantIcc, int colorSpace, int whitePoint, double[] white, int primaries,
                             double[] customPrimaries, int transfer, int gamma, int renderingIntent) {
    /**
     * Tristimulus RGB, including CMYK images with a black extra channel.
     */
    public static final int RGB = 0;

    /**
     * Grayscale.
     */
    public static final int GRAY = 1;

    /**
     * The XYB opsin space.
     */
    public static final int XYB = 2;

    /**
     * An unknown color space.
     */
    public static final int UNKNOWN = 3;

    /**
     * CIE standard illuminant D65.
     */
    public static final int WHITE_D65 = 1;

    /**
     * A custom white point.
     */
    public static final int WHITE_CUSTOM = 2;

    /**
     * CIE standard illuminant E.
     */
    public static final int WHITE_E = 10;

    /**
     * The DCI-P3 white point.
     */
    public static final int WHITE_DCI = 11;

    /**
     * The sRGB (BT.709) primaries.
     */
    public static final int PRIMARIES_SRGB = 1;

    /**
     * Custom primaries.
     */
    public static final int PRIMARIES_CUSTOM = 2;

    /**
     * The BT.2100 (BT.2020) primaries.
     */
    public static final int PRIMARIES_BT2100 = 9;

    /**
     * The DCI-P3 primaries.
     */
    public static final int PRIMARIES_P3 = 11;

    /**
     * A pure power transfer curve.
     */
    public static final int TF_GAMMA = 0;

    /**
     * The BT.709 transfer function.
     */
    public static final int TF_BT709 = 1;

    /**
     * An unknown transfer function.
     */
    public static final int TF_UNKNOWN = 2;

    /**
     * The linear transfer function.
     */
    public static final int TF_LINEAR = 8;

    /**
     * The sRGB transfer function.
     */
    public static final int TF_SRGB = 13;

    /**
     * The perceptual quantizer (SMPTE ST 2084).
     */
    public static final int TF_PQ = 16;

    /**
     * The DCI transfer function, a pure power curve of 2.6.
     */
    public static final int TF_DCI = 17;

    /**
     * Hybrid log-gamma (ARIB STD-B67).
     */
    public static final int TF_HLG = 18;

    /**
     * The default encoding: sRGB with a relative colorimetric intent.
     */
    public static final ColorEncoding SRGB = new ColorEncoding(false, RGB, WHITE_D65, null, PRIMARIES_SRGB, null,
            TF_SRGB, 0, 1);

    /**
     * Reads a color encoding.
     *
     * @param br the bitstream
     * @return the encoding
     * @throws MediaException.Malformed if an enumerated value is out of range
     */
    public static ColorEncoding read(JxlBitReader br) {
        if (br.readBool()) {
            return SRGB;
        }
        var wantIcc = br.readBool();
        var space = br.readEnum();
        if (space > UNKNOWN) {
            throw new MediaException.Malformed("invalid color space " + space);
        }
        if (wantIcc) {
            return new ColorEncoding(true, space, WHITE_D65, null, PRIMARIES_SRGB, null, TF_SRGB, 0, 1);
        }
        var whitePoint = WHITE_D65;
        double[] white = null;
        if (space != XYB) {
            whitePoint = br.readEnum();
            switch (whitePoint) {
                case WHITE_D65, WHITE_E, WHITE_DCI -> {
                }
                case WHITE_CUSTOM -> white = readCustomXy(br);
                default -> throw new MediaException.Malformed("invalid white point " + whitePoint);
            }
        }
        var primaries = PRIMARIES_SRGB;
        double[] custom = null;
        if (space != XYB && space != GRAY) {
            primaries = br.readEnum();
            switch (primaries) {
                case PRIMARIES_SRGB, PRIMARIES_BT2100, PRIMARIES_P3 -> {
                }
                case PRIMARIES_CUSTOM -> {
                    var red = readCustomXy(br);
                    var green = readCustomXy(br);
                    var blue = readCustomXy(br);
                    custom = new double[]{red[0], red[1], green[0], green[1], blue[0], blue[1]};
                }
                default -> throw new MediaException.Malformed("invalid primaries " + primaries);
            }
        }
        int transfer;
        var gamma = 0;
        if (br.readBool()) {
            transfer = TF_GAMMA;
            gamma = br.readBits(24);
            if (gamma == 0 || gamma > 10_000_000) {
                throw new MediaException.Malformed("invalid gamma " + gamma);
            }
        } else {
            transfer = br.readEnum();
            switch (transfer) {
                case TF_BT709, TF_UNKNOWN, TF_LINEAR, TF_SRGB, TF_PQ, TF_DCI, TF_HLG -> {
                }
                default -> throw new MediaException.Malformed("invalid transfer function " + transfer);
            }
        }
        var intent = br.readEnum();
        if (intent > 3) {
            throw new MediaException.Malformed("invalid rendering intent " + intent);
        }
        return new ColorEncoding(false, space, whitePoint, white, primaries, custom, transfer, gamma, intent);
    }

    /**
     * Reads a custom chromaticity.
     *
     * @param br the bitstream
     * @return {@code {x, y}}
     */
    private static double[] readCustomXy(JxlBitReader br) {
        var x = unpackSigned(br.readU32(0, 19, 524288, 19, 1048576, 20, 2097152, 21));
        var y = unpackSigned(br.readU32(0, 19, 524288, 19, 1048576, 20, 2097152, 21));
        return new double[]{x / 1e6, y / 1e6};
    }

    /**
     * Maps an unsigned value to a signed one ({@code UnpackSigned}).
     *
     * @param value the unsigned value
     * @return the signed value
     */
    private static int unpackSigned(int value) {
        return (value >>> 1) ^ -(value & 1);
    }

    /**
     * Returns the white point chromaticity.
     *
     * @return {@code {x, y}}
     */
    public double[] whiteXy() {
        return switch (whitePoint) {
            case WHITE_CUSTOM -> white.clone();
            case WHITE_E -> new double[]{1.0 / 3.0, 1.0 / 3.0};
            case WHITE_DCI -> new double[]{0.314, 0.351};
            default -> new double[]{0.3127, 0.329};
        };
    }

    /**
     * Returns the primaries chromaticities.
     *
     * @return {@code {xr, yr, xg, yg, xb, yb}}
     */
    public double[] primariesXy() {
        return switch (primaries) {
            case PRIMARIES_CUSTOM -> customPrimaries.clone();
            case PRIMARIES_BT2100 -> new double[]{0.708, 0.292, 0.170, 0.797, 0.131, 0.046};
            case PRIMARIES_P3 -> new double[]{0.680, 0.320, 0.265, 0.690, 0.150, 0.060};
            default -> new double[]{0.639998686, 0.330010138, 0.300003784, 0.600003357, 0.150002046, 0.059997204};
        };
    }

    /**
     * Returns whether the encoding is grayscale.
     *
     * @return whether the color space is {@link #GRAY}
     */
    public boolean isGray() {
        return colorSpace == GRAY;
    }

    /**
     * Returns whether the transfer function is a high-dynamic-range one.
     *
     * @return whether it is {@link #TF_PQ} or {@link #TF_HLG}
     */
    public boolean isHdr() {
        return !wantIcc && (transfer == TF_PQ || transfer == TF_HLG);
    }
}
