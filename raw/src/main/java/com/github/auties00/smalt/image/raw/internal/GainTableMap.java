/* Ports the Digital Negative (DNG) Specification 1.7.1.0, Adobe. This product includes DNG technology under license
   by Adobe. */
package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.internal.io.ByteArrays;

/**
 * Holds a DNG {@code ProfileGainTableMap} (DNG 1.6) or {@code ProfileGainTableMap2} (DNG 1.7): a
 * grid of gain tables over the image, each indexed by a weighted value of a pixel's color, whose
 * gain multiplies the pixel.
 *
 * <p>At an image position the four nearest tables are interpolated bilinearly, positions outside
 * the grid taking the nearest edge tables; the table input is the dot product of the pixel's red,
 * green, blue, smallest and largest components with the five input weights, clamped to [0, 1] and,
 * for the second version, raised to the map's gamma; the gain is looked up with linear
 * interpolation between the {@code MapPointsN} entries, the input times {@code MapPointsN} giving
 * the fractional index. Integer gains of the second version map linearly onto
 * {@code [GainMin, GainMax]}. The specification does not fix the byte order of the data: it is read
 * big-endian, as DNG's other binary tags are, or little-endian when only that order gives a map
 * that fits the tag. Instances are immutable after parsing.
 */
final class GainTableMap {
    /**
     * The most gain values a map may hold.
     */
    private static final long MAX_VALUES = 1 << 24;

    /**
     * The number of tables down.
     */
    final int pointsV;

    /**
     * The number of tables across.
     */
    final int pointsH;

    /**
     * The vertical spacing of the tables, relative to the image height.
     */
    final double spacingV;

    /**
     * The horizontal spacing of the tables, relative to the image width.
     */
    final double spacingH;

    /**
     * The vertical position of the first table, relative to the image height.
     */
    final double originV;

    /**
     * The horizontal position of the first table, relative to the image width.
     */
    final double originH;

    /**
     * The entries of each table.
     */
    final int pointsN;

    /**
     * The weights of red, green, blue, the smallest and the largest component.
     */
    final float[] weights;

    /**
     * The exponent applied to the table input, 1 for the first version.
     */
    final float gamma;

    /**
     * The gains, {@code pointsN} per table, tables row by row.
     */
    final float[] gains;

    /**
     * Constructs a map.
     *
     * @param pointsV the number of tables down
     * @param pointsH the number of tables across
     * @param spacingV the vertical spacing
     * @param spacingH the horizontal spacing
     * @param originV the vertical origin
     * @param originH the horizontal origin
     * @param pointsN the entries of each table
     * @param weights the five input weights
     * @param gamma the input exponent
     * @param gains the gains, {@code pointsN} per table, tables row by row
     */
    GainTableMap(int pointsV, int pointsH, double spacingV, double spacingH, double originV, double originH, int pointsN,
                 float[] weights, float gamma, float[] gains) {
        this.pointsV = pointsV;
        this.pointsH = pointsH;
        this.spacingV = spacingV;
        this.spacingH = spacingH;
        this.originV = originV;
        this.originH = originH;
        this.pointsN = pointsN;
        this.weights = weights;
        this.gamma = gamma;
        this.gains = gains;
    }

    /**
     * Parses a map.
     *
     * @param data the tag's bytes, or {@code null}
     * @param second whether the data is a {@code ProfileGainTableMap2}
     * @return the map, or {@code null} when the data is absent or invalid
     */
    static GainTableMap parse(byte[] data, boolean second) {
        var map = parse(data, second, false);
        return map != null ? map : parse(data, second, true);
    }

    /**
     * Parses a map in one byte order.
     *
     * @param data the tag's bytes, or {@code null}
     * @param second whether the data is a {@code ProfileGainTableMap2}
     * @param little whether the numbers are little endian
     * @return the map, or {@code null} when the data is absent, invalid, or not of the size its
     * dimensions give in this byte order
     */
    private static GainTableMap parse(byte[] data, boolean second, boolean little) {
        var header = second ? 80 : 64;
        if (data == null || data.length < header) {
            return null;
        }
        var pointsV = ByteArrays.u32(data, 0, little);
        var pointsH = ByteArrays.u32(data, 4, little);
        var spacingV = f64(data, 8, little);
        var spacingH = f64(data, 16, little);
        var originV = f64(data, 24, little);
        var originH = f64(data, 32, little);
        var pointsN = ByteArrays.u32(data, 40, little);
        var weights = new float[5];
        for (var index = 0; index < 5; index++) {
            weights[index] = Float.intBitsToFloat((int) ByteArrays.u32(data, 44 + 4 * index, little));
        }
        if (pointsV < 1 || pointsH < 1 || pointsN < 1 || pointsV > MAX_VALUES || pointsH > MAX_VALUES
                || pointsN > MAX_VALUES || pointsV * pointsH > MAX_VALUES || pointsV * pointsH * pointsN > MAX_VALUES
                || !(spacingV > 0) || !(spacingH > 0) || !Double.isFinite(spacingV) || !Double.isFinite(spacingH)
                || !Double.isFinite(originV) || !Double.isFinite(originH)) {
            return null;
        }
        for (var weight : weights) {
            if (!Float.isFinite(weight)) {
                return null;
            }
        }
        var count = (int) (pointsV * pointsH * pointsN);
        var gains = new float[count];
        var gamma = 1f;
        if (!second) {
            if (data.length < header + 4L * count) {
                return null;
            }
            for (var index = 0; index < count; index++) {
                gains[index] = Float.intBitsToFloat((int) ByteArrays.u32(data, header + 4 * index, little));
            }
        } else {
            var type = ByteArrays.u32(data, 64, little);
            gamma = Float.intBitsToFloat((int) ByteArrays.u32(data, 68, little));
            var minimum = Float.intBitsToFloat((int) ByteArrays.u32(data, 72, little));
            var maximum = Float.intBitsToFloat((int) ByteArrays.u32(data, 76, little));
            if (!(gamma >= 0.25f && gamma <= 4f) || type < 0 || type > 3) {
                return null;
            }
            var size = type == 0 ? 1 : type == 3 ? 4 : 2;
            if (data.length < header + (long) size * count) {
                return null;
            }
            if ((type == 0 || type == 1) && (!Float.isFinite(minimum) || !Float.isFinite(maximum))) {
                return null;
            }
            for (var index = 0; index < count; index++) {
                var at = header + size * index;
                gains[index] = switch ((int) type) {
                    case 0 -> minimum + (data[at] & 0xFF) / 255f * (maximum - minimum);
                    case 1 -> minimum + ByteArrays.u16(data, at, little) / 65535f * (maximum - minimum);
                    case 2 -> Float.float16ToFloat((short) ByteArrays.u16(data, at, little));
                    default -> Float.intBitsToFloat((int) ByteArrays.u32(data, at, little));
                };
            }
        }
        for (var gain : gains) {
            if (!(gain >= 0) || !Float.isFinite(gain)) {
                return null;
            }
        }
        return new GainTableMap((int) pointsV, (int) pointsH, spacingV, spacingH, originV, originH, (int) pointsN,
                weights, gamma, gains);
    }

    /**
     * Reads a double.
     *
     * @param data the bytes
     * @param offset the offset of the first byte
     * @param little whether the value is little endian
     * @return the value
     */
    private static double f64(byte[] data, int offset, boolean little) {
        return Double.longBitsToDouble(little ? ByteArrays.i64le(data, offset) : ByteArrays.i64be(data, offset));
    }

    /**
     * Applies the map to a row of pixels, in place.
     *
     * @param r the red values
     * @param g the green values
     * @param b the blue values
     * @param offset the index of the row's first pixel in the arrays
     * @param count the number of pixels
     * @param u the horizontal position of the first pixel's center, relative to the image width
     * @param du the horizontal distance between pixels, relative to the image width
     * @param v the vertical position of the row's center, relative to the image height
     * @param rowTables a scratch array of {@code pointsH * pointsN} values
     */
    void apply(float[] r, float[] g, float[] b, int offset, int count, double u, double du, double v,
               float[] rowTables) {
        var fy = (v - originV) / spacingV;
        var y0 = (int) Math.floor(fy);
        var ty = (float) (fy - y0);
        if (y0 < 0) {
            y0 = 0;
            ty = 0;
        } else if (y0 >= pointsV - 1) {
            y0 = pointsV - 1;
            ty = 0;
        }
        var y1 = Math.min(y0 + 1, pointsV - 1);
        var n = pointsN;
        for (var k = 0; k < pointsH * n; k++) {
            var top = gains[y0 * pointsH * n + k];
            rowTables[k] = top + (gains[y1 * pointsH * n + k] - top) * ty;
        }
        for (var index = 0; index < count; index++) {
            var fx = (u + index * du - originH) / spacingH;
            var x0 = (int) Math.floor(fx);
            var tx = (float) (fx - x0);
            if (x0 < 0) {
                x0 = 0;
                tx = 0;
            } else if (x0 >= pointsH - 1) {
                x0 = pointsH - 1;
                tx = 0;
            }
            var x1 = Math.min(x0 + 1, pointsH - 1);
            var at = offset + index;
            var red = r[at];
            var green = g[at];
            var blue = b[at];
            var input = weights[0] * red + weights[1] * green + weights[2] * blue
                    + weights[3] * Math.min(red, Math.min(green, blue)) + weights[4] * Math.max(red, Math.max(green, blue));
            input = Math.clamp(input, 0f, 1f);
            if (gamma != 1f) {
                input = (float) StrictMath.pow(input, gamma);
            }
            var position = input * n;
            var lower = Math.min((int) position, n - 1);
            var upper = Math.min(lower + 1, n - 1);
            var fraction = upper > lower ? position - lower : 0f;
            var left = rowTables[x0 * n + lower] + (rowTables[x0 * n + upper] - rowTables[x0 * n + lower]) * fraction;
            var right = rowTables[x1 * n + lower] + (rowTables[x1 * n + upper] - rowTables[x1 * n + lower]) * fraction;
            var gain = left + (right - left) * tx;
            r[at] = red * gain;
            g[at] = green * gain;
            b[at] = blue * gain;
        }
    }
}
