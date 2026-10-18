package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.image.raw.RawColorSpace;

/**
 * The per-pixel hue, saturation and value table of the first camera raw round, kept as the
 * baseline of {@link HueSatKernel}: the kernels must reproduce it bit for bit, and the benchmarks
 * compare against it.
 */
final class BaselineHueSatTable {
    /**
     * Holds the number of intervals of {@link #SRGB_ENCODING}.
     */
    private static final int ENCODING_STEPS = 4096;

    /**
     * Holds the sRGB transfer function sampled over [0, 1], interpolated linearly between samples.
     *
     * @implNote This implementation replaces the per-pixel power function of the sRGB-encoded
     * value axis with this table: interpolating 4096 intervals errs by less than 2e-5, far below
     * the spacing of any table's value divisions.
     */
    private static final float[] SRGB_ENCODING = encoding();
    /**
     * The hue divisions.
     */
    private final int hues;

    /**
     * The saturation divisions.
     */
    private final int saturations;

    /**
     * The value divisions.
     */
    private final int values;

    /**
     * The cells, three floats each.
     */
    private final float[] cells;

    /**
     * Whether the value axis is sRGB-encoded.
     */
    private final boolean srgb;

    /**
     * Holds the hue divisions per sextant of the hue circle.
     */
    private final float hueScale;

    /**
     * Constructs a table.
     *
     * @param dims  the hue, saturation and value divisions
     * @param cells the cells
     * @param srgb  whether the value axis is sRGB-encoded
     */
    BaselineHueSatTable(int[] dims, float[] cells, boolean srgb) {
        this.hues = dims[0];
        this.saturations = dims[1];
        this.values = dims[2];
        this.cells = cells;
        this.srgb = srgb;
        this.hueScale = hues / 6f;
    }

    /**
     * Blends two tables cell by cell.
     *
     * @param dims   the divisions shared by both
     * @param first  the cells of the first
     * @param second the cells of the second, or {@code null}
     * @param weight the weight of the first, from 0 to 1
     * @param srgb   whether the value axis is sRGB-encoded
     * @return the blended table
     */
    static BaselineHueSatTable blend(int[] dims, float[] first, float[] second, double weight, boolean srgb) {
        if (second == null || first == null) {
            return new BaselineHueSatTable(dims, first != null ? first : second, srgb);
        }
        var cells = new float[first.length];
        for (var index = 0; index < cells.length; index++) {
            cells[index] = (float) (weight * first[index] + (1 - weight) * second[index]);
        }
        return new BaselineHueSatTable(dims, cells, srgb);
    }

    /**
     * Applies the table to one color, in place.
     *
     * @param rgb the linear ProPhoto RGB values, modified
     */
    void apply(float[] rgb) {
        var r = rgb[0];
        var g = rgb[1];
        var b = rgb[2];
        var max = Math.max(r, Math.max(g, b));
        var min = Math.min(r, Math.min(g, b));
        if (!(max > 0)) {
            return;
        }
        var chroma = max - min;
        var saturation = chroma / max;
        float hue;
        if (chroma <= 0) {
            hue = 0;
        } else if (max == r) {
            hue = (g - b) / chroma;
            if (hue < 0) {
                hue += 6;
            }
        } else if (max == g) {
            hue = 2 + (b - r) / chroma;
        } else {
            hue = 4 + (r - g) / chroma;
        }
        var value = max;
        var hueScaled = hue * hueScale;
        var h0 = (int) hueScaled;
        var hueFraction = hueScaled - h0;
        if (h0 >= hues) {
            h0 -= hues;
        }
        var h1 = h0 + 1 == hues ? 0 : h0 + 1;
        var satScaled = Math.min(saturation, 1f) * (saturations - 1);
        var s0 = Math.min((int) satScaled, saturations - 2);
        var satFraction = satScaled - s0;
        var hueStride = saturations * 3;
        var lower = h0 * hueStride + s0 * 3;
        var upper = h1 * hueStride + s0 * 3;
        var w00 = (1 - hueFraction) * (1 - satFraction);
        var w01 = (1 - hueFraction) * satFraction;
        var w10 = hueFraction * (1 - satFraction);
        var w11 = hueFraction * satFraction;
        var c = cells;
        float shift;
        float satScale;
        float valScale;
        if (values > 1) {
            var encoded = srgb ? encode(value) : Math.min(value, 1f);
            var valScaled = encoded * (values - 1);
            var v0 = Math.min((int) valScaled, values - 2);
            var valFraction = valScaled - v0;
            var valueStride = hues * hueStride;
            lower += v0 * valueStride;
            upper += v0 * valueStride;
            var low = 1 - valFraction;
            shift = low * corners(c, lower, upper, 0, w00, w01, w10, w11)
                    + valFraction * corners(c, lower + valueStride, upper + valueStride, 0, w00, w01, w10, w11);
            satScale = low * corners(c, lower, upper, 1, w00, w01, w10, w11)
                    + valFraction * corners(c, lower + valueStride, upper + valueStride, 1, w00, w01, w10, w11);
            valScale = low * corners(c, lower, upper, 2, w00, w01, w10, w11)
                    + valFraction * corners(c, lower + valueStride, upper + valueStride, 2, w00, w01, w10, w11);
        } else {
            shift = corners(c, lower, upper, 0, w00, w01, w10, w11);
            satScale = corners(c, lower, upper, 1, w00, w01, w10, w11);
            valScale = corners(c, lower, upper, 2, w00, w01, w10, w11);
        }
        hue += shift * 6f / 360f;
        if (hue < 0) {
            hue += 6;
        } else if (hue >= 6) {
            hue -= 6;
        }
        saturation = Math.clamp(saturation * satScale, 0f, 1f);
        value *= valScale;
        var p = value * (1 - saturation);
        var sector = (int) hue;
        var f = hue - sector;
        var q = value * (1 - saturation * f);
        var t = value * (1 - saturation * (1 - f));
        switch (sector) {
            case 0 -> set(rgb, value, t, p);
            case 1 -> set(rgb, q, value, p);
            case 2 -> set(rgb, p, value, t);
            case 3 -> set(rgb, p, q, value);
            case 4 -> set(rgb, t, p, value);
            default -> set(rgb, value, p, q);
        }
    }

    /**
     * Interpolates one channel of the four cells around a hue and saturation.
     *
     * @param cells the cells
     * @param low   the index of the cell at the lower hue and saturation
     * @param high  the index of the cell at the higher hue and the lower saturation
     * @param channel the channel: 0 hue shift, 1 saturation scale, 2 value scale
     * @param w00   the weight of the lower hue and saturation
     * @param w01   the weight of the lower hue and higher saturation
     * @param w10   the weight of the higher hue and lower saturation
     * @param w11   the weight of the higher hue and saturation
     * @return the interpolated value
     */
    private static float corners(float[] cells, int low, int high, int channel, float w00, float w01, float w10,
                                 float w11) {
        return w00 * cells[low + channel] + w01 * cells[low + 3 + channel]
                + w10 * cells[high + channel] + w11 * cells[high + 3 + channel];
    }

    /**
     * Samples the sRGB transfer function.
     *
     * @return the {@link #ENCODING_STEPS} + 1 samples
     */
    private static float[] encoding() {
        var table = new float[ENCODING_STEPS + 1];
        for (var index = 0; index <= ENCODING_STEPS; index++) {
            table[index] = (float) Transfer.encode(RawColorSpace.SRGB, index / (double) ENCODING_STEPS);
        }
        return table;
    }

    /**
     * Encodes a linear value with the sRGB transfer function, clamping it to [0, 1].
     *
     * @param value the linear value
     * @return the encoded value
     */
    private static float encode(float value) {
        if (!(value > 0)) {
            return 0;
        }
        if (value >= 1) {
            return 1;
        }
        var scaled = value * ENCODING_STEPS;
        var index = (int) scaled;
        return SRGB_ENCODING[index] + (SRGB_ENCODING[index + 1] - SRGB_ENCODING[index]) * (scaled - index);
    }

    /**
     * Stores three values.
     *
     * @param rgb the destination
     * @param r   the first
     * @param g   the second
     * @param b   the third
     */
    private static void set(float[] rgb, float r, float g, float b) {
        rgb[0] = r;
        rgb[1] = g;
        rgb[2] = b;
    }
}
