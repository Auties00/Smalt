package com.github.auties00.smalt.image.raw;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Writes the synthetic DNG files of the high-dynamic-range profile tests: a linear raw image of three 16-bit samples
 * whose camera space is linear ProPhoto RGB (the color matrix is the XYZ to ProPhoto matrix, calibrated under D50,
 * with a neutral of 1), so that its rendering in ProPhoto RGB is the identity before the profile's look, with the
 * profile tags of each test: {@code ProfileDynamicRange}, the tone curve, the hue, saturation and value map, the look
 * table, {@code RGBTables}, the baseline exposure and the extra camera profiles.
 */
public final class HdrProfileFiles {
    /** The width and height of the image. */
    static final int SIZE = 64;

    /** The first of the rows of neutral values at the bottom of the image. */
    static final int NEUTRAL_ROWS = 56;

    /** The XYZ (D50) to linear ProPhoto RGB matrix, the color matrix of the files. */
    static final double[] PROPHOTO_FROM_XYZ = {1.3459433, -0.2556075, -0.0511118, -0.5445989, 1.5081673, 0.0205351,
            0, 0, 1.2118128};

    /** The dynamic range written in {@code ProfileDynamicRange}, or -1 to leave the tag out. */
    int dynamicRange = 1;

    /** The {@code HintMaxOutputValue} of {@code ProfileDynamicRange}. */
    float maxOutputHint = 8;

    /** The {@code ProfileDynamicRange} bytes written instead of the version, range and hint, or {@code null}. */
    byte[] dynamicRangeBytes;

    /** The tone curve's input and output pairs, or {@code null} for none. */
    public float[] toneCurve = {0, 0, 1, 1};

    /** The baseline exposure in stops. */
    double baselineExposure = 2;

    /** The hue, value and saturation divisions of the hue, saturation and value map, or {@code null} for none. */
    int[] hueSatDims;

    /** The hue, saturation and value map. */
    float[] hueSat;

    /** Whether the map's value axis is sRGB-encoded ({@code ProfileHueSatMapEncoding} 1). */
    boolean hueSatSrgb;

    /** The divisions of the look table, or {@code null} for none. */
    public int[] lookDims;

    /** The look table. */
    public float[] look;

    /** Whether the look table's value axis is sRGB-encoded ({@code ProfileLookTableEncoding} 1). */
    boolean lookSrgb;

    /** The {@code RGBTables} bytes, or {@code null} for none. */
    public byte[] rgbTables;

    /** The profile's name. */
    String name = "Test";

    /** The profile's group name, or {@code null} for none. */
    String group;

    /** The extra camera profiles, as written by {@link #profile}. */
    byte[][] extraProfiles;

    /** Whether the file is big endian. */
    boolean bigEndian;

    /** Whether the rows from {@link #NEUTRAL_ROWS} on hold the neutral ramp of {@link #sample}. */
    boolean neutralRows = true;

    /**
     * {@return the stored value of a sample, from 0 to 1: red rising along the row, green down the column and blue
     * along the diagonal, except in the rows from {@link #NEUTRAL_ROWS} on, which hold a neutral ramp}
     *
     * @param x the column
     * @param y the row
     * @param channel the channel
     */
    static double sample(int x, int y, int channel) {
        return sample(x, y, channel, true);
    }

    /**
     * {@return the stored value of a sample, from 0 to 1}
     *
     * @param x the column
     * @param y the row
     * @param channel the channel
     * @param neutralRows whether the rows from {@link #NEUTRAL_ROWS} on hold a neutral ramp
     */
    static double sample(int x, int y, int channel, boolean neutralRows) {
        var value = neutralRows && y >= NEUTRAL_ROWS ? x / 63.0 : switch (channel) {
            case 0 -> x / 63.0;
            case 1 -> y / 63.0;
            default -> ((x + y) % 64) / 63.0;
        };
        return Math.round(value * 65535) / 65535.0;
    }

    /** {@return the file} */
    public byte[] write() {
        var builder = new DngBuilder(!bigEndian, 42);
        var first = builder.first;
        first.longs(254, 0).longs(256, SIZE).longs(257, SIZE).shorts(258, 16, 16, 16).shorts(259, 1).shorts(262, 34892)
                .shorts(274, 1).shorts(277, 3).shorts(284, 1).ascii(271, "Smalt").ascii(272, "HDR")
                .bytes(50706, 1, 7, 1, 0).bytes(50707, 1, 4, 0, 0).ascii(50708, "Smalt HDR")
                .longs(50717, 65535, 65535, 65535).signedRationals(50721, PROPHOTO_FROM_XYZ).shorts(50778, 23)
                .rationals(50728, 1, 1, 1).signedRationals(50730, baselineExposure).longs(51110, 1);
        profileTags(first, builder.order());
        if (extraProfiles != null) {
            first.blobs(50933, extraProfiles);
        }
        var data = ByteBuffer.allocate(SIZE * SIZE * 6).order(builder.order());
        for (var y = 0; y < SIZE; y++) {
            for (var x = 0; x < SIZE; x++) {
                for (var c = 0; c < 3; c++) {
                    data.putShort((short) Math.round(sample(x, y, c, neutralRows) * 65535));
                }
            }
        }
        first.strips(SIZE, data.array());
        return builder.write();
    }

    /**
     * {@return an extra camera profile of the same matrix with this object's profile tags}
     */
    byte[] profile() {
        var profile = new DngBuilder(!bigEndian, 0x4352);
        profile.first.signedRationals(50721, PROPHOTO_FROM_XYZ).shorts(50778, 23);
        profileTags(profile.first, profile.order());
        return profile.write();
    }

    private void profileTags(DngBuilder.Dir dir, ByteOrder order) {
        dir.ascii(50936, name);
        if (group != null) {
            dir.ascii(52552, group);
        }
        if (dynamicRangeBytes != null) {
            dir.undefined(52551, dynamicRangeBytes);
        } else if (dynamicRange >= 0) {
            dir.undefined(52551, dynamicRange(dynamicRange, maxOutputHint, order));
        }
        if (toneCurve != null) {
            dir.floats(50940, toneCurve);
        }
        if (hueSatDims != null) {
            dir.longs(50937, hueSatDims[0], hueSatDims[1], hueSatDims[2]).floats(50938, hueSat);
            if (hueSatSrgb) {
                dir.longs(51107, 1);
            }
        }
        if (lookDims != null) {
            dir.longs(50981, lookDims[0], lookDims[1], lookDims[2]).floats(50982, look);
            if (lookSrgb) {
                dir.longs(51108, 1);
            }
        }
        if (rgbTables != null) {
            dir.undefined(52543, rgbTables);
        }
    }

    /**
     * {@return the bytes of a {@code ProfileDynamicRange} tag of version 1}
     *
     * @param range the dynamic range: 0 standard, 1 high
     * @param hint the {@code HintMaxOutputValue}
     * @param order the byte order of the fields
     */
    static byte[] dynamicRange(int range, float hint, ByteOrder order) {
        return ByteBuffer.allocate(8).order(order).putShort((short) 1).putShort((short) range).putFloat(hint).array();
    }

    /**
     * {@return a hue, saturation and value table in the tag order (value divisions outermost, then hue, then
     * saturation), each entry a hue shift in degrees, a saturation scale and a value scale}
     *
     * @param hues the hue divisions
     * @param saturations the saturation divisions
     * @param values the value divisions
     * @param entry the entry of each hue, saturation and value index
     */
    static float[] table(int hues, int saturations, int values, Entry entry) {
        var data = new float[hues * saturations * values * 3];
        var at = 0;
        for (var v = 0; v < values; v++) {
            for (var h = 0; h < hues; h++) {
                for (var s = 0; s < saturations; s++) {
                    var cell = entry.at(h, s, v);
                    data[at++] = cell[0];
                    data[at++] = cell[1];
                    data[at++] = cell[2];
                }
            }
        }
        return data;
    }

    /** The entries of a table made by {@link #table}. */
    interface Entry {
        /**
         * {@return the hue shift, saturation scale and value scale of a cell}
         *
         * @param hue the hue index
         * @param saturation the saturation index
         * @param value the value index
         */
        float[] at(int hue, int saturation, int value);
    }

    /**
     * {@return the table the oracle comparisons use: hue shifts, saturation and value scales that vary along every
     * axis, the value scale of zero saturation 1 as the specification requires}
     *
     * @param hues the hue divisions
     * @param saturations the saturation divisions
     * @param values the value divisions
     */
    public static float[] varied(int hues, int saturations, int values) {
        return table(hues, saturations, values, (h, s, v) -> new float[]{10f * h * s - 20f * v,
                1 + 0.2f * s * (h % 2) - 0.1f * v, s == 0 ? 1 : 1 - 0.5f * v + 0.1f * h / hues});
    }

    /**
     * {@return an {@code RGBTables} tag of one background table in ProPhoto primaries, linear, without gamut
     * extension, whose entries are given by a function of the entry's input; little endian, the order of the
     * files, in which the DNG SDK reads it}
     *
     * @param divisions the entries along each axis
     * @param entry the output of each red, green and blue index
     */
    public static byte[] rgbTable(int divisions, RgbEntry entry) {
        var b = ByteBuffer.allocate(8 + 2 + 5 + divisions * divisions * divisions * 12)
                .order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(1).putInt(0).putShort((short) 0);
        b.put((byte) divisions).put((byte) 2).put((byte) 0).put((byte) 4).put((byte) 0);
        for (var r = 0; r < divisions; r++) {
            for (var g = 0; g < divisions; g++) {
                for (var bl = 0; bl < divisions; bl++) {
                    var out = entry.at(r / (divisions - 1f), g / (divisions - 1f), bl / (divisions - 1f));
                    b.putFloat(out[0]).putFloat(out[1]).putFloat(out[2]);
                }
            }
        }
        return b.array();
    }

    /** The entries of a table made by {@link #rgbTable}. */
    public interface RgbEntry {
        /**
         * {@return the output red, green and blue of an entry}
         *
         * @param red the entry's red input
         * @param green the entry's green input
         * @param blue the entry's blue input
         */
        float[] at(float red, float green, float blue);
    }
}
