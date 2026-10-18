package com.github.auties00.smalt.image.raw.internal;

/**
 * Applies hue, saturation and value tables with plain loops: the reference implementation of
 * {@link HueSatKernel}.
 */
final class ScalarHueSatKernel implements HueSatKernel {
    /**
     * Constructs the kernel.
     */
    ScalarHueSatKernel() {
    }

    @Override
    public void apply(HueSatTable table, float[] r, float[] g, float[] b, int count) {
        pixels(table, r, g, b, 0, count);
    }

    /**
     * Applies the table to a range of pixels in place.
     *
     * @param table the hue, saturation and value table to apply
     * @param r the red values
     * @param g the green values
     * @param b the blue values
     * @param from the first pixel
     * @param to the pixel after the last
     */
    static void pixels(HueSatTable table, float[] r, float[] g, float[] b, int from, int to) {
        var hues = table.hues;
        var saturations = table.saturations;
        var values = table.values;
        var hueScale = table.hueScale;
        var c = table.cells;
        var hueStride = saturations * 3;
        var valueStride = hues * hueStride;
        for (var index = from; index < to; index++) {
            var red = r[index];
            var green = g[index];
            var blue = b[index];
            var max = Math.max(red, Math.max(green, blue));
            var min = Math.min(red, Math.min(green, blue));
            if (!(max > 0)) {
                continue;
            }
            var chroma = max - min;
            var saturation = chroma / max;
            float hue;
            if (chroma <= 0) {
                hue = 0;
            } else if (max == red) {
                hue = (green - blue) / chroma;
                if (hue < 0) {
                    hue += 6;
                }
            } else if (max == green) {
                hue = 2 + (blue - red) / chroma;
            } else {
                hue = 4 + (red - green) / chroma;
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
            var lower = h0 * hueStride + s0 * 3;
            var upper = h1 * hueStride + s0 * 3;
            var w00 = (1 - hueFraction) * (1 - satFraction);
            var w01 = (1 - hueFraction) * satFraction;
            var w10 = hueFraction * (1 - satFraction);
            var w11 = hueFraction * satFraction;
            float shift;
            float satScale;
            float valScale;
            if (values > 1) {
                var encoded = table.srgb ? HueSatTable.encode(value) : Math.min(value, 1f);
                var valScaled = encoded * (values - 1);
                var v0 = Math.min((int) valScaled, values - 2);
                var valFraction = valScaled - v0;
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
                case 0 -> set(r, g, b, index, value, t, p);
                case 1 -> set(r, g, b, index, q, value, p);
                case 2 -> set(r, g, b, index, p, value, t);
                case 3 -> set(r, g, b, index, p, q, value);
                case 4 -> set(r, g, b, index, t, p, value);
                default -> set(r, g, b, index, value, p, q);
            }
        }
    }

    /**
     * Interpolates one channel of the four cells around a hue and saturation.
     *
     * @param cells the cells of the table
     * @param low the index of the cell at the lower hue and saturation
     * @param high the index of the cell at the higher hue and the lower saturation
     * @param channel the channel: 0 hue shift, 1 saturation scale, 2 value scale
     * @param w00 the weight of the lower hue and saturation
     * @param w01 the weight of the lower hue and higher saturation
     * @param w10 the weight of the higher hue and lower saturation
     * @param w11 the weight of the higher hue and saturation
     * @return the interpolated value
     */
    private static float corners(float[] cells, int low, int high, int channel, float w00, float w01, float w10,
                                 float w11) {
        return w00 * cells[low + channel] + w01 * cells[low + 3 + channel]
                + w10 * cells[high + channel] + w11 * cells[high + 3 + channel];
    }

    /**
     * Stores one pixel.
     *
     * @param r the red values
     * @param g the green values
     * @param b the blue values
     * @param index the pixel
     * @param red the red value
     * @param green the green value
     * @param blue the blue value
     */
    private static void set(float[] r, float[] g, float[] b, int index, float red, float green, float blue) {
        r[index] = red;
        g[index] = green;
        b[index] = blue;
    }
}
