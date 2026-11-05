package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.image.raw.RawColorSpace;

/**
 * Holds a DNG hue, saturation and value table ({@code ProfileHueSatMap} or
 * {@code ProfileLookTable}) for linear ProPhoto RGB values, which a {@link HueSatKernel} applies.
 *
 * <p>Each cell holds a hue shift in degrees, a saturation scale and a value scale; cells are stored
 * value division outermost, then hue, then saturation.
 */
final class HueSatTable {
    /**
     * Holds the number of intervals of {@link #SRGB_ENCODING}.
     */
    static final int ENCODING_STEPS = 4096;

    /**
     * Holds the sRGB transfer function sampled over [0, 1], interpolated linearly between samples.
     *
     * @implNote This implementation replaces the per-pixel power function of the sRGB-encoded
     * value axis with this table: interpolating 4096 intervals errs by less than 2e-5, far below
     * the spacing of any table's value divisions.
     */
    static final float[] SRGB_ENCODING = encoding();
    /**
     * The hue divisions.
     */
    final int hues;

    /**
     * The saturation divisions.
     */
    final int saturations;

    /**
     * The value divisions.
     */
    final int values;

    /**
     * The cells, three floats each.
     */
    final float[] cells;

    /**
     * Whether the value axis is sRGB-encoded.
     */
    final boolean srgb;

    /**
     * Holds the hue divisions per sextant of the hue circle.
     */
    final float hueScale;

    /**
     * The floats of a cell's entry in the {@link #corners()} table of a table with several value
     * divisions: eight per channel.
     */
    static final int VOLUME_ENTRY = 24;

    /**
     * The floats of a cell's entry in the {@link #corners()} table of a table with one value
     * division: four per channel and four unused.
     */
    static final int PLANE_ENTRY = 16;

    /**
     * The corner table, built on first use by {@link #corners()}.
     */
    private volatile float[] corners;

    /**
     * Constructs a table.
     *
     * @param dims the hue, saturation and value divisions
     * @param cells the hue shift, saturation scale and value scale of every cell, in table order
     * @param srgb whether the value axis is sRGB-encoded
     */
    HueSatTable(int[] dims, float[] cells, boolean srgb) {
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
     * @param dims the divisions shared by both
     * @param first the cells of the first
     * @param second the cells of the second, or {@code null}
     * @param weight the weight of the first, from 0 to 1
     * @param srgb whether the value axis is sRGB-encoded
     * @return the blended table
     */
    static HueSatTable blend(int[] dims, float[] first, float[] second, double weight, boolean srgb) {
        if (second == null || first == null) {
            return new HueSatTable(dims, first != null ? first : second, srgb);
        }
        var cells = new float[first.length];
        for (var index = 0; index < cells.length; index++) {
            cells[index] = (float) (weight * first[index] + (1 - weight) * second[index]);
        }
        return new HueSatTable(dims, cells, srgb);
    }

    /**
     * Returns the corner table: for every cell at the lower hue, saturation and value of an
     * interpolation, the values the interpolation reads, next to each other.
     *
     * <p>The cell with hue division {@code h}, saturation division {@code s} and value division
     * {@code v} is cell number {@code (v * hues + h) * saturations + s}, a third of the index of
     * its first value in {@link #cells}. With several value divisions its entry holds
     * {@link #VOLUME_ENTRY} floats, for each channel {@code ch} at {@code 8 * ch}: the channel at
     * hue {@code h} and {@code h + 1} (wrapping around) times saturation {@code s} and
     * {@code s + 1}, in the order (h, s), (h, s + 1), (h + 1, s), (h + 1, s + 1), first in value
     * division {@code v} and then in {@code v + 1}. With one value division its entry holds
     * {@link #PLANE_ENTRY} floats: the four corners of each channel at {@code 4 * ch}, then four
     * zeros. Cells whose interpolation would read past the last saturation or value division are
     * never read and hold zeros.
     *
     * @implNote This implementation builds the table when a kernel prepares this table
     * ({@link HueSatKernel#prepare}), or on first use, and keeps it: the vector kernel reads the
     * corners of a pixel with one load, and the scalar kernel never needs it.
     *
     * @return the corner table
     */
    float[] corners() {
        var table = corners;
        if (table == null) {
            table = buildCorners();
            corners = table;
        }
        return table;
    }

    /**
     * Builds the table {@link #corners()} returns.
     *
     * @return the corner table
     */
    private float[] buildCorners() {
        var volume = values > 1;
        var entry = volume ? VOLUME_ENTRY : PLANE_ENTRY;
        var hueStride = saturations * 3;
        var valueStride = hues * hueStride;
        var table = new float[values * hues * saturations * entry];
        for (var v = 0; v < values; v++) {
            for (var h = 0; h < hues; h++) {
                var next = h + 1 == hues ? 0 : h + 1;
                for (var s = 0; s + 1 < saturations; s++) {
                    if (volume && v + 1 == values) {
                        continue;
                    }
                    var lower = v * valueStride + h * hueStride + s * 3;
                    var upper = v * valueStride + next * hueStride + s * 3;
                    var at = ((v * hues + h) * saturations + s) * entry;
                    for (var channel = 0; channel < 3; channel++) {
                        if (volume) {
                            var base = at + 8 * channel;
                            table[base] = cells[lower + channel];
                            table[base + 1] = cells[lower + 3 + channel];
                            table[base + 2] = cells[upper + channel];
                            table[base + 3] = cells[upper + 3 + channel];
                            table[base + 4] = cells[lower + valueStride + channel];
                            table[base + 5] = cells[lower + valueStride + 3 + channel];
                            table[base + 6] = cells[upper + valueStride + channel];
                            table[base + 7] = cells[upper + valueStride + 3 + channel];
                        } else {
                            var base = at + 4 * channel;
                            table[base] = cells[lower + channel];
                            table[base + 1] = cells[lower + 3 + channel];
                            table[base + 2] = cells[upper + channel];
                            table[base + 3] = cells[upper + 3 + channel];
                        }
                    }
                }
            }
        }
        return table;
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
    static float encode(float value) {
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
}
