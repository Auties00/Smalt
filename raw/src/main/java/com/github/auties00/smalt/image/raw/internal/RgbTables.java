/* Ports the Digital Negative (DNG) Specification 1.7.1.0, Adobe. This product includes DNG technology under license
   by Adobe. */
package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.io.ByteArrays;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Holds a DNG {@code RGBTables} tag (DNG 1.6): RGB to RGB lookup tables, each applied to the region
 * of the image a semantic mask names (or, for the background table, to the rest), combined by
 * weighted sum or one after another.
 *
 * <p>The tables apply to linear ProPhoto RGB values, after the tone curve. Each table converts the
 * values to its own primaries (adapted to their white with the Bradford transform), clamps them to
 * [0, 1], keeping the clamped-off part when the table asks for gamut extension, encodes them with
 * its transfer curve, looks them up with trilinear interpolation, decodes them and converts them
 * back. A table whose name matches no semantic mask of the file is ignored. The specification does
 * not fix the byte order of the multi-byte fields: the tag is read big-endian, or little-endian when
 * only that order parses to its whole length. Instances are immutable after parsing.
 */
final class RgbTables {
    /**
     * The most tables a tag may hold.
     */
    private static final int MAX_TABLES = 20;

    /**
     * The primaries and white of each {@code ColorPrimaries} code: sRGB, Adobe RGB, Display P3,
     * Rec. 2020 and ProPhoto.
     */
    private static final double[][][] PRIMARIES = {
            {{0.64, 0.33}, {0.30, 0.60}, {0.15, 0.06}, ColorMath.D65_XY},
            {{0.64, 0.33}, {0.21, 0.71}, {0.15, 0.06}, ColorMath.D65_XY},
            {{0.680, 0.320}, {0.265, 0.690}, {0.150, 0.060}, ColorMath.D65_XY},
            {{0.708, 0.292}, {0.170, 0.797}, {0.131, 0.046}, ColorMath.D65_XY},
            {{0.7347, 0.2653}, {0.1596, 0.8404}, {0.0366, 0.0001}, {0.3457, 0.3585}}
    };

    /**
     * One table.
     *
     * @param name the semantic mask it applies to, or {@code null} for the background table
     * @param divisions the entries along each axis
     * @param entries the output red, green and blue of each entry, blue fastest
     * @param gamma the {@code GammaEncoding} code: linear, sRGB, 1.8, 2.2 or Rec. 2020
     * @param toTable the matrix from linear ProPhoto RGB to the table's primaries
     * @param fromTable the matrix back
     * @param extend whether out-of-gamut values keep their clamped-off part
     */
    record Table(String name, int divisions, float[] entries, int gamma, float[] toTable, float[] fromTable,
                 boolean extend) {
    }

    /**
     * Whether the tables apply one after another rather than as a weighted sum.
     */
    final boolean sequential;

    /**
     * The tables, in the order of the tag.
     */
    final List<Table> tables;

    /**
     * Constructs the tag.
     *
     * @param sequential whether the tables apply one after another
     * @param tables the tables, in the order of the tag
     */
    RgbTables(boolean sequential, List<Table> tables) {
        this.sequential = sequential;
        this.tables = tables;
    }

    /**
     * Parses the tag.
     *
     * @param data the tag's bytes, or {@code null}
     * @return the tables, or {@code null} when the data is absent or invalid
     */
    static RgbTables parse(byte[] data) {
        var tables = parse(data, false);
        return tables != null ? tables : parse(data, true);
    }

    /**
     * Parses the tag in one byte order.
     *
     * @param data the tag's bytes, or {@code null}
     * @param little whether the multi-byte fields are little endian
     * @return the tables, or {@code null} when the data is absent, invalid or not exactly consumed
     */
    private static RgbTables parse(byte[] data, boolean little) {
        if (data == null || data.length < 8) {
            return null;
        }
        var count = ByteArrays.u32(data, 0, little);
        var method = ByteArrays.u32(data, 4, little);
        if (count < 1 || count > MAX_TABLES || method > 1) {
            return null;
        }
        var position = 8;
        var tables = new ArrayList<Table>();
        var backgrounds = 0;
        var proPhoto = ColorMath.rgbToXyz(PRIMARIES[4][0], PRIMARIES[4][1], PRIMARIES[4][2], PRIMARIES[4][3]);
        for (var index = 0; index < count; index++) {
            if (position + 2 > data.length) {
                return null;
            }
            var nameLength = ByteArrays.u16(data, position, little);
            position += 2;
            if (position + nameLength + 5 > data.length) {
                return null;
            }
            String name = null;
            if (nameLength > 0) {
                name = new String(data, position, nameLength, StandardCharsets.US_ASCII);
            } else {
                backgrounds++;
            }
            position += nameLength;
            var divisions = data[position] & 0xFF;
            var pixelType = data[position + 1] & 0xFF;
            var gamma = data[position + 2] & 0xFF;
            var primaries = data[position + 3] & 0xFF;
            var extension = data[position + 4] & 0xFF;
            position += 5;
            if (divisions < 2 || divisions > 32 || pixelType > 2 || gamma > 4 || primaries > 4 || extension > 1) {
                return null;
            }
            var size = pixelType == 0 ? 1 : pixelType == 1 ? 2 : 4;
            var values = divisions * divisions * divisions * 3;
            if (position + (long) values * size > data.length) {
                return null;
            }
            var entries = new float[values];
            for (var k = 0; k < values; k++) {
                var at = position + k * size;
                entries[k] = switch (pixelType) {
                    case 0 -> (data[at] & 0xFF) / 255f;
                    case 1 -> ByteArrays.u16(data, at, little) / 65535f;
                    default -> Float.intBitsToFloat((int) ByteArrays.u32(data, at, little));
                };
                if (!Float.isFinite(entries[k])) {
                    return null;
                }
            }
            position += values * size;
            var space = PRIMARIES[primaries];
            var tableToXyz = ColorMath.rgbToXyz(space[0], space[1], space[2], space[3]);
            var toTable = ColorMath.multiply(ColorMath.invert(tableToXyz),
                    ColorMath.multiply(ColorMath.adaptation(PRIMARIES[4][3], space[3]), proPhoto));
            var fromTable = ColorMath.invert(toTable);
            tables.add(new Table(name, divisions, entries, gamma, floats(toTable), floats(fromTable), extension == 1));
        }
        if (position != data.length || backgrounds > 1) {
            return null;
        }
        return new RgbTables(method == 1, List.copyOf(tables));
    }

    /**
     * Scratch arrays for {@link #apply}, one set per thread.
     */
    static final class Scratch {
        /**
         * The weight of each table.
         */
        final float[] weights;

        /**
         * The color being transformed.
         */
        final float[] color = new float[3];

        /**
         * The color a table maps it to.
         */
        final float[] mapped = new float[3];

        /**
         * The weighted sum of the mapped colors.
         */
        final float[] sum = new float[3];

        /**
         * The encoded result of a lookup.
         */
        final float[] looked = new float[3];

        /**
         * Constructs the arrays.
         *
         * @param tables the number of tables
         */
        Scratch(int tables) {
            weights = new float[tables];
        }
    }

    /**
     * Returns the masks of the tables, matched by name and decoded; a mask that cannot be decoded
     * matches no table.
     *
     * @param masks the semantic masks of the file
     * @param limits the resource bounds the masks decode under
     * @return for each table its mask, {@code null} for the background table and for a table no
     * decodable mask matches
     * @throws MediaException.LimitExceeded if a mask exceeds the limits
     */
    SemanticMask[] match(List<SemanticMask> masks, MediaLimits limits) {
        var matched = new SemanticMask[tables.size()];
        for (var index = 0; index < matched.length; index++) {
            var name = tables.get(index).name;
            if (name == null) {
                continue;
            }
            for (var mask : masks) {
                if (mask.name.equals(name)) {
                    try {
                        mask.values(limits);
                        matched[index] = mask;
                    } catch (MediaException.Unsupported | MediaException.Malformed _) {
                        // the table of a mask that cannot be decoded is ignored, as one without a mask
                    }
                    break;
                }
            }
        }
        return matched;
    }

    /**
     * Returns the scratch arrays {@link #apply} works in.
     *
     * @return new scratch arrays
     */
    Scratch scratch() {
        return new Scratch(tables.size());
    }

    /**
     * Applies the tables to a row of linear ProPhoto RGB values, in place.
     *
     * @param r the red values
     * @param g the green values
     * @param b the blue values
     * @param offset the index of the row's first pixel in the arrays
     * @param count the number of pixels
     * @param u the horizontal position of the first pixel's center, relative to the active area
     * @param du the horizontal distance between pixels, relative to the active area
     * @param v the vertical position of the row's center, relative to the active area
     * @param masks the mask of each table, from {@link #match}
     * @param values the decoded values of each of {@code masks}, or {@code null} entries
     * @param scratch the scratch arrays, from {@link #scratch()}
     */
    void apply(float[] r, float[] g, float[] b, int offset, int count, double u, double du, double v,
               SemanticMask[] masks, float[][] values, Scratch scratch) {
        var tableCount = tables.size();
        var weights = scratch.weights;
        var color = scratch.color;
        var mapped = scratch.mapped;
        var sum = scratch.sum;
        var looked = scratch.looked;
        for (var pixel = 0; pixel < count; pixel++) {
            var index = offset + pixel;
            var x = u + pixel * du;
            var covered = 0f;
            var background = -1;
            for (var t = 0; t < tableCount; t++) {
                var table = tables.get(t);
                if (table.name == null) {
                    background = t;
                    weights[t] = 0;
                } else {
                    weights[t] = values[t] != null ? Math.clamp(masks[t].sample(values[t], x, v), 0f, 1f) : 0;
                    covered += weights[t];
                }
            }
            var backgroundWeight = 1 - Math.min(1f, covered);
            if (background >= 0) {
                weights[background] = backgroundWeight;
            }
            color[0] = r[index];
            color[1] = g[index];
            color[2] = b[index];
            if (sequential) {
                for (var t = 0; t < tableCount; t++) {
                    if (weights[t] <= 0 || (tables.get(t).name != null && values[t] == null)) {
                        continue;
                    }
                    transform(tables.get(t), color, mapped, looked);
                    for (var c = 0; c < 3; c++) {
                        color[c] += weights[t] * (mapped[c] - color[c]);
                    }
                }
            } else {
                sum[0] = 0;
                sum[1] = 0;
                sum[2] = 0;
                var total = 0f;
                for (var t = 0; t < tableCount; t++) {
                    if (weights[t] <= 0 || (tables.get(t).name != null && values[t] == null)) {
                        continue;
                    }
                    transform(tables.get(t), color, mapped, looked);
                    for (var c = 0; c < 3; c++) {
                        sum[c] += weights[t] * mapped[c];
                    }
                    total += weights[t];
                }
                if (background < 0 && backgroundWeight > 0) {
                    for (var c = 0; c < 3; c++) {
                        sum[c] += backgroundWeight * color[c];
                    }
                    total += backgroundWeight;
                }
                if (total > 0) {
                    for (var c = 0; c < 3; c++) {
                        color[c] = sum[c] / total;
                    }
                }
            }
            r[index] = color[0];
            g[index] = color[1];
            b[index] = color[2];
        }
    }

    /**
     * Transforms one color through a table.
     *
     * @param table the table to apply
     * @param color the linear ProPhoto RGB color
     * @param out receives the transformed color
     * @param looked a scratch array of three values
     */
    static void transform(Table table, float[] color, float[] out, float[] looked) {
        var m = table.toTable;
        var red = m[0] * color[0] + m[1] * color[1] + m[2] * color[2];
        var green = m[3] * color[0] + m[4] * color[1] + m[5] * color[2];
        var blue = m[6] * color[0] + m[7] * color[1] + m[8] * color[2];
        var cr = Math.clamp(red, 0f, 1f);
        var cg = Math.clamp(green, 0f, 1f);
        var cb = Math.clamp(blue, 0f, 1f);
        var dr = table.extend ? red - cr : 0;
        var dg = table.extend ? green - cg : 0;
        var db = table.extend ? blue - cb : 0;
        lookup(table, encode(table.gamma, cr), encode(table.gamma, cg), encode(table.gamma, cb), looked);
        red = decode(table.gamma, looked[0]) + dr;
        green = decode(table.gamma, looked[1]) + dg;
        blue = decode(table.gamma, looked[2]) + db;
        var n = table.fromTable;
        out[0] = n[0] * red + n[1] * green + n[2] * blue;
        out[1] = n[3] * red + n[4] * green + n[5] * blue;
        out[2] = n[6] * red + n[7] * green + n[8] * blue;
    }

    /**
     * Looks a color up in a table with trilinear interpolation.
     *
     * @param table the table to look in
     * @param red the encoded red, from 0 to 1
     * @param green the encoded green
     * @param blue the encoded blue
     * @param out receives the encoded result
     */
    private static void lookup(Table table, float red, float green, float blue, float[] out) {
        var d = table.divisions;
        var last = d - 1;
        var fr = red * last;
        var fg = green * last;
        var fb = blue * last;
        var r0 = Math.min((int) fr, last - 1);
        var g0 = Math.min((int) fg, last - 1);
        var b0 = Math.min((int) fb, last - 1);
        var tr = fr - r0;
        var tg = fg - g0;
        var tb = fb - b0;
        var e = table.entries;
        for (var c = 0; c < 3; c++) {
            var c000 = e[((r0 * d + g0) * d + b0) * 3 + c];
            var c001 = e[((r0 * d + g0) * d + b0 + 1) * 3 + c];
            var c010 = e[((r0 * d + g0 + 1) * d + b0) * 3 + c];
            var c011 = e[((r0 * d + g0 + 1) * d + b0 + 1) * 3 + c];
            var c100 = e[(((r0 + 1) * d + g0) * d + b0) * 3 + c];
            var c101 = e[(((r0 + 1) * d + g0) * d + b0 + 1) * 3 + c];
            var c110 = e[(((r0 + 1) * d + g0 + 1) * d + b0) * 3 + c];
            var c111 = e[(((r0 + 1) * d + g0 + 1) * d + b0 + 1) * 3 + c];
            var c00 = c000 + (c001 - c000) * tb;
            var c01 = c010 + (c011 - c010) * tb;
            var c10 = c100 + (c101 - c100) * tb;
            var c11 = c110 + (c111 - c110) * tb;
            var c0 = c00 + (c01 - c00) * tg;
            var c1 = c10 + (c11 - c10) * tg;
            out[c] = c0 + (c1 - c0) * tr;
        }
    }

    /**
     * Applies a {@code GammaEncoding} curve.
     *
     * @param gamma the code: 0 linear, 1 sRGB, 2 gamma 1.8, 3 gamma 2.2, 4 Rec. 2020
     * @param x the linear value, from 0 to 1
     * @return the encoded value
     */
    static float encode(int gamma, float x) {
        return switch (gamma) {
            case 1 -> x <= 0.0031308f ? 12.92f * x : (float) (1.055 * StrictMath.pow(x, 1 / 2.4) - 0.055);
            case 2 -> (float) StrictMath.pow(x, 1 / 1.8);
            case 3 -> (float) StrictMath.pow(x, 1 / 2.2);
            case 4 -> x < 0.018053968510807f ? 4.5f * x : (float) (1.09929682680944 * StrictMath.pow(x, 0.45) - 0.09929682680944);
            default -> x;
        };
    }

    /**
     * Undoes a {@code GammaEncoding} curve.
     *
     * @param gamma the code, as {@link #encode} takes it
     * @param y the encoded value
     * @return the linear value
     */
    static float decode(int gamma, float y) {
        if (gamma != 0 && y <= 0) {
            return gamma == 4 ? y / 4.5f : gamma == 1 ? y / 12.92f : 0;
        }
        return switch (gamma) {
            case 1 -> y <= 0.04045f ? y / 12.92f : (float) StrictMath.pow((y + 0.055) / 1.055, 2.4);
            case 2 -> (float) StrictMath.pow(y, 1.8);
            case 3 -> (float) StrictMath.pow(y, 2.2);
            case 4 -> y < 4.5f * 0.018053968510807f ? y / 4.5f
                    : (float) StrictMath.pow((y + 0.09929682680944) / 1.09929682680944, 1 / 0.45);
            default -> y;
        };
    }

    /**
     * Converts a matrix to single precision.
     *
     * @param matrix the matrix in double precision
     * @return the converted matrix
     */
    private static float[] floats(double[] matrix) {
        var result = new float[matrix.length];
        for (var index = 0; index < matrix.length; index++) {
            result[index] = (float) matrix[index];
        }
        return result;
    }
}
