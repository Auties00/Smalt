package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.raw.CfaPattern;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.task.ParallelBands;

/**
 * Holds the linear reference values of the active area of a sensor image: the DNG "stage 2"
 * image, in which 0 is black and 1 is the white level of each sample.
 *
 * <p>{@link #of(Sensor, SensorPlane, MediaLimits)} builds it from the stored samples: it applies
 * the linearization table, then subtracts the black level (the repeating pattern plus the per-row
 * and per-column deltas) and divides by the distance to the white level, over the active area.
 * Values are not clipped, so noise below black stays negative and floating-point data may exceed
 * 1.
 */
final class LinearImage {
    /**
     * The width in pixels.
     */
    final int width;

    /**
     * The height in pixels.
     */
    final int height;

    /**
     * The samples of each pixel.
     */
    final int samplesPerPixel;

    /**
     * The values, interleaved.
     */
    final float[] data;

    /**
     * The color filter array of this image, or {@code null}.
     */
    final CfaPattern cfa;

    /**
     * For each photosite of one repeat of {@link #cfa}, the color plane (0 red, 1 green, 2 blue)
     * it records, row by row; {@code null} without a CFA.
     */
    final int[] planes;

    /**
     * The number of color planes {@link #planes} names: 3, or 4 for a four-color sensor.
     */
    final int colors;

    /**
     * Constructs an image.
     *
     * @param width the width in pixels
     * @param height the height in pixels
     * @param samplesPerPixel the samples of each pixel
     * @param data the values
     * @param cfa the color filter array, or {@code null}
     * @param planes the plane of each photosite of one repeat, or {@code null}
     */
    LinearImage(int width, int height, int samplesPerPixel, float[] data, CfaPattern cfa, int[] planes) {
        this.width = width;
        this.height = height;
        this.samplesPerPixel = samplesPerPixel;
        this.data = data;
        this.cfa = cfa;
        this.planes = planes;
        var colors = 3;
        if (planes != null) {
            for (var plane : planes) {
                colors = Math.max(colors, plane + 1);
            }
        }
        this.colors = colors;
    }

    /**
     * Returns the color plane a photosite records.
     *
     * @param row the row of the photosite
     * @param column the column of the photosite
     * @return 0 for red, 1 for green, 2 for blue
     */
    int plane(int row, int column) {
        return planes[(row % cfa.height()) * cfa.width() + column % cfa.width()];
    }

    /**
     * Builds the linear image of a sensor.
     *
     * @param sensor the sensor description
     * @param plane the stored samples, after opcode list 1
     * @param limits the resource bounds
     * @return the image
     */
    static LinearImage of(Sensor sensor, SensorPlane plane, MediaLimits limits) {
        var area = sensor.active();
        var top = area[0];
        var left = area[1];
        var width = area[3] - left;
        var height = area[2] - top;
        var spp = sensor.samplesPerPixel;
        LimitChecks.checkAllocation(limits, (long) width * height * spp * 4);
        var data = new float[width * height * spp];
        var maximum = sensor.maximum();
        var white = new double[spp];
        for (var sample = 0; sample < spp; sample++) {
            white[sample] = sensor.white != null && sensor.white[sample] > 0 ? sensor.white[sample] : maximum;
        }
        var table = sensor.linearization;
        var rowCount = sensor.blackRows;
        var columnCount = sensor.blackColumns;
        var rowBlack = new float[rowCount][width * spp];
        for (var row = 0; row < rowCount; row++) {
            for (var column = 0; column < width; column++) {
                for (var sample = 0; sample < spp; sample++) {
                    var black = sensor.black == null ? 0 : sensor.black[((row * columnCount) + column % columnCount) * spp + sample];
                    if (sensor.blackDeltaH != null && column < sensor.blackDeltaH.length) {
                        black += sensor.blackDeltaH[column];
                    }
                    rowBlack[row][column * spp + sample] = (float) black;
                }
            }
        }
        var rowScale = new float[rowCount][];
        if (sensor.blackDeltaV == null) {
            for (var row = 0; row < rowCount; row++) {
                rowScale[row] = new float[width * spp];
                scales(rowBlack[row], 0f, white, spp, sensor.gain, rowBlack[row], rowScale[row]);
            }
        }
        var bands = ParallelBands.bands(height, (long) width * height * spp);
        ParallelBands.run(bands, band -> {
            var first = ParallelBands.start(band, bands, height);
            var last = ParallelBands.start(band + 1, bands, height);
            var count = width * spp;
            var rowOffset = sensor.blackDeltaV != null ? new float[count] : null;
            var rowScales = sensor.blackDeltaV != null ? new float[count] : null;
            for (var row = first; row < last; row++) {
                float[] offset;
                float[] scale;
                if (rowOffset == null) {
                    offset = rowBlack[row % rowCount];
                    scale = rowScale[row % rowCount];
                } else {
                    var delta = row < sensor.blackDeltaV.length ? (float) sensor.blackDeltaV[row] : 0f;
                    scales(rowBlack[row % rowCount], delta, white, spp, sensor.gain, rowOffset, rowScales);
                    offset = rowOffset;
                    scale = rowScales;
                }
                var source = ((top + row) * plane.width + left) * spp;
                var target = row * count;
                if (plane.integers != null) {
                    var in = plane.integers;
                    if (table != null) {
                        var last1 = table.length - 1;
                        for (var index = 0; index < count; index++) {
                            var value = table[Math.min(in[source + index] & 0xFFFF, last1)];
                            data[target + index] = (value - offset[index]) * scale[index];
                        }
                    } else {
                        for (var index = 0; index < count; index++) {
                            data[target + index] = ((in[source + index] & 0xFFFF) - offset[index]) * scale[index];
                        }
                    }
                } else if (plane.wide != null) {
                    var in = plane.wide;
                    var last1 = table != null ? table.length - 1 : 0;
                    for (var index = 0; index < count; index++) {
                        var stored = Integer.toUnsignedLong(in[source + index]);
                        double value = table != null ? table[(int) Math.min(stored, last1)] : stored;
                        data[target + index] = (float) ((value - offset[index]) * scale[index]);
                    }
                } else {
                    var in = plane.floats;
                    for (var index = 0; index < count; index++) {
                        var value = in[source + index];
                        data[target + index] = Float.isFinite(value) ? (value - offset[index]) * scale[index] : 0;
                    }
                }
            }
        });
        CfaPattern cfa = null;
        int[] planes = null;
        if (sensor.cfa != null && spp == 1) {
            cfa = sensor.cfa.shifted(top, left);
            planes = new int[cfa.width() * cfa.height()];
            var colors = 0;
            for (var mapped : sensor.planeOfColor) {
                colors = Math.max(colors, mapped + 1);
            }
            colors = Math.clamp(colors, 3, 4);
            for (var row = 0; row < cfa.height(); row++) {
                for (var column = 0; column < cfa.width(); column++) {
                    var code = cfa.color(row, column).code();
                    var mapped = sensor.planeOfColor[code];
                    planes[row * cfa.width() + column] = mapped >= 0 && mapped < colors ? mapped : Math.min(code, 2);
                }
            }
        }
        return new LinearImage(width, height, spp, data, cfa, planes);
    }

    /**
     * Computes the black offset and the reciprocal of the black-to-white range of each sample of a row.
     *
     * @param blacks the black level of each sample of the row, before the row delta
     * @param delta the row's black delta
     * @param white the white level of each sample of a pixel
     * @param spp the samples of each pixel
     * @param gain the factor the stored values carry relative to the main image, divided out
     * @param offset receives the black offsets; may be {@code blacks} when {@code delta} is 0
     * @param scale receives the reciprocals, divided by the gain, 0 for an empty range
     */
    private static void scales(float[] blacks, float delta, double[] white, int spp, double gain, float[] offset,
                               float[] scale) {
        for (var index = 0; index < blacks.length; index++) {
            var black = blacks[index] + delta;
            var range = (float) white[index % spp] - black;
            offset[index] = black;
            scale[index] = range > 1e-6f ? gain == 1 ? 1 / range : (float) (1 / (range * gain)) : 0;
        }
    }
}
