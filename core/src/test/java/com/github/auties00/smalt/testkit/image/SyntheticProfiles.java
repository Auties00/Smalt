package com.github.auties00.smalt.testkit.image;

import com.github.auties00.smalt.image.Channel8Picture;
import com.github.auties00.smalt.image.PixelLayout;
import com.github.auties00.smalt.image.RgbaPicture;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Builds the ICC profiles and pictures the color-managed encoding tests need and no test vector provides: a
 * version 2 CMYK printer profile whose {@code A2B0} lookup table (an {@code mft2} with a 5-point grid) maps
 * inks to CIE L*a*b* through {@link #inkRgb(double, double, double, double)}, a mixing of the inks unlike the
 * naive {@code R = (1 - C)(1 - K)}, followed by sRGB, so that a conversion through the profile tells itself
 * apart from a naive one, plus gradients that reach out of the sRGB gamut.
 */
public final class SyntheticProfiles {
    private static final int GRID = 5;

    private SyntheticProfiles() {
    }

    /**
     * {@return the bytes of the synthetic CMYK profile}
     */
    public static byte[] cmyk() {
        var tags = new ByteArrayOutputStream();
        var desc = description("Smalt synthetic CMYK");
        var wtpt = xyz(0.9642, 1.0, 0.8249);
        var a2b0 = lut();
        var offsets = new int[3];
        var start = 128 + 4 + 3 * 12;
        var cursor = start;
        var parts = new byte[][]{desc, wtpt, a2b0};
        for (var i = 0; i < parts.length; i++) {
            offsets[i] = cursor;
            tags.writeBytes(parts[i]);
            var pad = (4 - parts[i].length % 4) % 4;
            tags.writeBytes(new byte[pad]);
            cursor += parts[i].length + pad;
        }
        var profile = new byte[cursor];
        put32(profile, 0, cursor);
        put32(profile, 8, 0x02100000);
        ascii(profile, 12, "prtr");
        ascii(profile, 16, "CMYK");
        ascii(profile, 20, "Lab ");
        ascii(profile, 36, "acsp");
        put32(profile, 68, fixed(0.9642));
        put32(profile, 72, fixed(1.0));
        put32(profile, 76, fixed(0.8249));
        put32(profile, 128, 3);
        var signatures = new String[]{"desc", "wtpt", "A2B0"};
        for (var i = 0; i < 3; i++) {
            ascii(profile, 132 + 12 * i, signatures[i]);
            put32(profile, 136 + 12 * i, offsets[i]);
            put32(profile, 140 + 12 * i, parts[i].length);
        }
        var body = tags.toByteArray();
        System.arraycopy(body, 0, profile, start, body.length);
        return profile;
    }

    /**
     * {@return the sRGB values the synthetic profile assigns to inks, each from 0 (no ink) to 1: inks that
     * absorb in their neighbors' bands too, and a black that leaves a tenth of the light}
     *
     * @param c the cyan ink
     * @param m the magenta ink
     * @param y the yellow ink
     * @param k the black ink
     */
    public static double[] inkRgb(double c, double m, double y, double k) {
        var light = 1 - 0.9 * k;
        return new double[]{Math.clamp((1 - 0.85 * c - 0.1 * m) * light, 0, 1),
                Math.clamp((1 - 0.8 * m - 0.15 * c) * light, 0, 1),
                Math.clamp((1 - 0.75 * y - 0.2 * m) * light, 0, 1)};
    }

    /**
     * {@return a CMYK picture whose inks sweep the grid of the synthetic profile and the values between, smoothly
     * enough for lossy formats}
     *
     * @param width the width
     * @param height the height
     */
    public static Channel8Picture cmykPicture(int width, int height) {
        var picture = Channel8Picture.allocate(width, height, PixelLayout.CMYK);
        var pixels = picture.pixels();
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var at = y * picture.stride() + x * 4;
                pixels[at] = (byte) (x * 255 / Math.max(1, width - 1));
                pixels[at + 1] = (byte) (y * 255 / Math.max(1, height - 1));
                pixels[at + 2] = (byte) ((x + y) * 255 / Math.max(1, width + height - 2));
                pixels[at + 3] = (byte) (Math.abs(x - y) * 127 / Math.max(1, Math.max(width, height) - 1));
            }
        }
        return picture;
    }

    /**
     * {@return an opaque RGBA gradient whose corners are the primaries, so that in a wide-gamut profile it
     * reaches out of the sRGB gamut}
     *
     * @param width the width
     * @param height the height
     */
    public static RgbaPicture wideGradient(int width, int height) {
        var picture = RgbaPicture.allocate(width, height, false);
        var pixels = picture.pixels();
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var at = y * picture.stride() + x * 4;
                var u = x / (double) Math.max(1, width - 1);
                var v = y / (double) Math.max(1, height - 1);
                pixels[at] = (byte) Math.round(255 * (1 - u) * (1 - v * 0.5));
                pixels[at + 1] = (byte) Math.round(255 * u * (1 - v * 0.3));
                pixels[at + 2] = (byte) Math.round(255 * v);
                pixels[at + 3] = (byte) 255;
            }
        }
        return picture;
    }

    private static byte[] lut() {
        var out = new ByteArrayOutputStream();
        ascii(out, "mft2");
        out.writeBytes(new byte[4]);
        out.write(4);
        out.write(3);
        out.write(GRID);
        out.write(0);
        for (var i = 0; i < 9; i++) {
            put32(out, i % 4 == 0 ? fixed(1.0) : 0);
        }
        put16(out, 2);
        put16(out, 2);
        for (var i = 0; i < 4; i++) {
            put16(out, 0);
            put16(out, 65535);
        }
        for (var c = 0; c < GRID; c++) {
            for (var m = 0; m < GRID; m++) {
                for (var y = 0; y < GRID; y++) {
                    for (var k = 0; k < GRID; k++) {
                        var rgb = inkRgb(c / (GRID - 1.0), m / (GRID - 1.0), y / (GRID - 1.0), k / (GRID - 1.0));
                        var lab = lab(rgb);
                        put16(out, clamp16(Math.round(lab[0] * 65280 / 100)));
                        put16(out, clamp16(Math.round((lab[1] + 128) * 256)));
                        put16(out, clamp16(Math.round((lab[2] + 128) * 256)));
                    }
                }
            }
        }
        for (var i = 0; i < 3; i++) {
            put16(out, 0);
            put16(out, 65535);
        }
        return out.toByteArray();
    }

    /**
     * {@return the D50 CIE L*a*b* of an sRGB color, through the Bradford-adapted sRGB matrix}
     */
    private static double[] lab(double[] rgb) {
        var linear = new double[3];
        for (var i = 0; i < 3; i++) {
            var v = rgb[i];
            linear[i] = v <= 0.04045 ? v / 12.92 : StrictMath.pow((v + 0.055) / 1.055, 2.4);
        }
        // the sRGB to D50 XYZ matrix of ICC.1 Annex (Bradford adapted)
        var x = 0.4360747 * linear[0] + 0.3850649 * linear[1] + 0.1430804 * linear[2];
        var y = 0.2225045 * linear[0] + 0.7168786 * linear[1] + 0.0606169 * linear[2];
        var z = 0.0139322 * linear[0] + 0.0971045 * linear[1] + 0.7141733 * linear[2];
        var fx = f(x / 0.9642);
        var fy = f(y);
        var fz = f(z / 0.8249);
        return new double[]{116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz)};
    }

    private static double f(double t) {
        return t > 216.0 / 24389 ? StrictMath.cbrt(t) : (24389.0 / 27 * t + 16) / 116;
    }

    private static int clamp16(long value) {
        return (int) Math.clamp(value, 0, 65535);
    }

    private static byte[] description(String text) {
        var out = new ByteArrayOutputStream();
        ascii(out, "desc");
        out.writeBytes(new byte[4]);
        var bytes = (text + "\0").getBytes(StandardCharsets.US_ASCII);
        put32(out, bytes.length);
        out.writeBytes(bytes);
        put32(out, 0);
        put32(out, 0);
        put16(out, 0);
        out.write(0);
        out.writeBytes(new byte[67]);
        return out.toByteArray();
    }

    private static byte[] xyz(double x, double y, double z) {
        var out = new ByteArrayOutputStream();
        ascii(out, "XYZ ");
        out.writeBytes(new byte[4]);
        put32(out, fixed(x));
        put32(out, fixed(y));
        put32(out, fixed(z));
        return out.toByteArray();
    }

    private static int fixed(double value) {
        return (int) Math.round(value * 65536);
    }

    private static void ascii(byte[] data, int at, String text) {
        var bytes = text.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, data, at, bytes.length);
    }

    private static void ascii(ByteArrayOutputStream out, String text) {
        out.writeBytes(text.getBytes(StandardCharsets.US_ASCII));
    }

    private static void put32(byte[] data, int at, int value) {
        data[at] = (byte) (value >>> 24);
        data[at + 1] = (byte) (value >>> 16);
        data[at + 2] = (byte) (value >>> 8);
        data[at + 3] = (byte) value;
    }

    private static void put32(ByteArrayOutputStream out, int value) {
        out.write(value >>> 24);
        out.write(value >>> 16);
        out.write(value >>> 8);
        out.write(value);
    }

    private static void put16(ByteArrayOutputStream out, int value) {
        out.write(value >>> 8);
        out.write(value);
    }
}
