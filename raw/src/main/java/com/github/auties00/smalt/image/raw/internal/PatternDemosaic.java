package com.github.auties00.smalt.image.raw.internal;

import java.util.Arrays;

/**
 * Demosaics a window of an image with any color filter array by interpolating from precomputed
 * stencils: for each photosite of the pattern's repeat and each color plane it lacks, the nearest
 * photosites of that plane within a square of radius one, or two, or three where the smaller
 * square holds none.
 *
 * <p>In bilinear mode each missing value is the mean of its stencil; on a Bayer array this is
 * exactly bilinear interpolation (four orthogonal greens, two red or blue neighbors at green
 * photosites, four diagonal ones at the opposite color). In color-difference mode, used for
 * arrays other than Bayer when quality is asked for (Fujifilm X-Trans), green is interpolated
 * first, then red and blue as green plus the mean of the neighbors' color differences, which
 * keeps edges sharper and colors cleaner. Neighbors outside the image are left out of the means.
 * A four-color image (cyan, yellow, green and magenta, or red, green, blue and emerald) is
 * interpolated bilinearly in all four planes and mapped to three channels as it is demosaiced, by
 * {@link Projection}.
 */
final class PatternDemosaic {
    /**
     * The photosites a window must extend beyond in color-difference mode for the green of every
     * neighbor to be known.
     */
    static final int MARGIN = 6;

    /**
     * For each phase of the repeat (row-major) and each plane, the row offsets of the stencil.
     */
    private final int[][][] rowOffsets;

    /**
     * For each phase and plane, the column offsets of the stencil.
     */
    private final int[][][] columnOffsets;

    /**
     * The green values of the extended window, in color-difference mode.
     */
    private float[] greens;

    /**
     * Builds the stencils of an image's pattern.
     *
     * @param image the image, with a color filter array
     */
    PatternDemosaic(LinearImage image) {
        var pattern = image.cfa;
        var phases = pattern.width() * pattern.height();
        rowOffsets = new int[phases][image.colors][];
        columnOffsets = new int[phases][image.colors][];
        for (var py = 0; py < pattern.height(); py++) {
            for (var px = 0; px < pattern.width(); px++) {
                var phase = py * pattern.width() + px;
                for (var p = 0; p < image.colors; p++) {
                    for (var radius = 1; radius <= 3; radius++) {
                        var count = 0;
                        var rows = new int[(2 * radius + 1) * (2 * radius + 1)];
                        var columns = new int[rows.length];
                        for (var dy = -radius; dy <= radius; dy++) {
                            for (var dx = -radius; dx <= radius; dx++) {
                                if (dy == 0 && dx == 0) {
                                    continue;
                                }
                                var y = Math.floorMod(py + dy, pattern.height()) + pattern.height() * 8;
                                var x = Math.floorMod(px + dx, pattern.width()) + pattern.width() * 8;
                                if (image.plane(y, x) == p) {
                                    rows[count] = dy;
                                    columns[count] = dx;
                                    count++;
                                }
                            }
                        }
                        if (count > 0 || radius == 3) {
                            rowOffsets[phase][p] = Arrays.copyOf(rows, count);
                            columnOffsets[phase][p] = Arrays.copyOf(columns, count);
                            break;
                        }
                    }
                }
            }
        }
    }

    /**
     * Demosaics a window by averaging the stencils (bilinear interpolation on Bayer arrays).
     *
     * @param image the linear image to demosaic
     * @param x0 the first column of the window
     * @param y0 the first row of the window
     * @param width the window width
     * @param height the window height
     * @param r receives the red values, row by row
     * @param g receives the green values
     * @param b receives the blue values
     */
    void bilinear(LinearImage image, int x0, int y0, int width, int height, float[] r, float[] g, float[] b) {
        for (var row = 0; row < height; row++) {
            for (var column = 0; column < width; column++) {
                pixel(image, x0 + column, y0 + row, r, g, b, row * width + column);
            }
        }
    }

    /**
     * Interpolates one photosite by averaging its stencils.
     *
     * @param image the linear image to demosaic
     * @param x the column
     * @param y the row
     * @param r receives the red value
     * @param g receives the green value
     * @param b receives the blue value
     * @param at the index the values are stored at
     */
    void pixel(LinearImage image, int x, int y, float[] r, float[] g, float[] b, int at) {
        var data = image.data;
        var w = image.width;
        var h = image.height;
        var pattern = image.cfa;
        var own = image.plane(y, x);
        var phase = (y % pattern.height()) * pattern.width() + x % pattern.width();
        var interior = y >= 3 && x >= 3 && y < h - 3 && x < w - 3;
        for (var p = 0; p < 3; p++) {
            var output = p == 0 ? r : p == 1 ? g : b;
            output[at] = p == own ? data[y * w + x]
                    : mean(data, w, h, x, y, rowOffsets[phase][p], columnOffsets[phase][p], interior);
        }
    }

    /**
     * Demosaics a window of a four-color image and maps it to three channels: each photosite's
     * four camera values (its own and the means of the stencils of the other three planes) go
     * through {@link Projection#project}.
     *
     * @param image the image, of four color planes
     * @param x0 the first column of the window
     * @param y0 the first row of the window
     * @param width the window width
     * @param height the window height
     * @param r receives the first projected channel, row by row
     * @param g receives the second projected channel
     * @param b receives the third projected channel
     * @param projection the {@code 3 x 4} projection matrix followed by the four white balance
     * multipliers
     * @param blend whether highlights are blended rather than clipped
     */
    void projected(LinearImage image, int x0, int y0, int width, int height, float[] r, float[] g, float[] b,
                   float[] projection, boolean blend) {
        var data = image.data;
        var w = image.width;
        var h = image.height;
        var pattern = image.cfa;
        var values = new float[4];
        var out = new float[3];
        for (var row = 0; row < height; row++) {
            var y = y0 + row;
            for (var column = 0; column < width; column++) {
                var x = x0 + column;
                var own = image.plane(y, x);
                var phase = (y % pattern.height()) * pattern.width() + x % pattern.width();
                var interior = y >= 3 && x >= 3 && y < h - 3 && x < w - 3;
                for (var p = 0; p < 4; p++) {
                    values[p] = p == own ? data[y * w + x]
                            : mean(data, w, h, x, y, rowOffsets[phase][p], columnOffsets[phase][p], interior);
                }
                Projection.project(values, projection, blend, out, 0);
                var at = row * width + column;
                r[at] = out[0];
                g[at] = out[1];
                b[at] = out[2];
            }
        }
    }

    /**
     * Demosaics a window from color differences: green first, from the stencils, then red and
     * blue as green plus the mean color difference of their stencils.
     *
     * @param image the linear image to demosaic
     * @param x0 the first column of the window
     * @param y0 the first row of the window
     * @param width the window width
     * @param height the window height
     * @param r receives the red values, row by row
     * @param g receives the green values
     * @param b receives the blue values
     */
    void colorDifference(LinearImage image, int x0, int y0, int width, int height, float[] r, float[] g, float[] b) {
        var data = image.data;
        var w = image.width;
        var h = image.height;
        var pattern = image.cfa;
        var ex0 = Math.max(0, x0 - MARGIN);
        var ey0 = Math.max(0, y0 - MARGIN);
        var ex1 = Math.min(w, x0 + width + MARGIN);
        var ey1 = Math.min(h, y0 + height + MARGIN);
        var ew = ex1 - ex0;
        var eh = ey1 - ey0;
        if (greens == null || greens.length < ew * eh) {
            greens = new float[ew * eh];
        }
        for (var y = ey0; y < ey1; y++) {
            for (var x = ex0; x < ex1; x++) {
                var own = image.plane(y, x);
                var phase = (y % pattern.height()) * pattern.width() + x % pattern.width();
                greens[(y - ey0) * ew + (x - ex0)] = own == 1 ? data[y * w + x]
                        : mean(data, w, h, x, y, rowOffsets[phase][1], columnOffsets[phase][1],
                        y >= 3 && x >= 3 && y < h - 3 && x < w - 3);
            }
        }
        for (var row = 0; row < height; row++) {
            var y = y0 + row;
            for (var column = 0; column < width; column++) {
                var x = x0 + column;
                var at = row * width + column;
                var own = image.plane(y, x);
                var phase = (y % pattern.height()) * pattern.width() + x % pattern.width();
                var green = greens[(y - ey0) * ew + (x - ex0)];
                g[at] = green;
                for (var p = 0; p < 3; p += 2) {
                    var out = p == 0 ? r : b;
                    if (p == own) {
                        out[at] = data[y * w + x];
                        continue;
                    }
                    var rows = rowOffsets[phase][p];
                    var columns = columnOffsets[phase][p];
                    var sum = 0f;
                    var count = 0;
                    for (var k = 0; k < rows.length; k++) {
                        var ny = y + rows[k];
                        var nx = x + columns[k];
                        if (ny < ey0 || nx < ex0 || ny >= ey1 || nx >= ex1) {
                            continue;
                        }
                        sum += data[ny * w + nx] - greens[(ny - ey0) * ew + (nx - ex0)];
                        count++;
                    }
                    out[at] = count == 0 ? green : green + sum / count;
                }
            }
        }
    }

    /**
     * Returns the mean of a stencil around a photosite.
     *
     * @param data the image values
     * @param w the image width
     * @param h the image height
     * @param x the column
     * @param y the row
     * @param rows the stencil's row offsets
     * @param columns the stencil's column offsets
     * @param interior whether every stencil photosite lies in the image
     * @return the mean, or {@code 0} when no neighbor lies in the image
     */
    private static float mean(float[] data, int w, int h, int x, int y, int[] rows, int[] columns, boolean interior) {
        var sum = 0f;
        var count = 0;
        if (interior) {
            for (var k = 0; k < rows.length; k++) {
                sum += data[(y + rows[k]) * w + x + columns[k]];
            }
            count = rows.length;
        } else {
            for (var k = 0; k < rows.length; k++) {
                var ny = y + rows[k];
                var nx = x + columns[k];
                if (ny >= 0 && nx >= 0 && ny < h && nx < w) {
                    sum += data[ny * w + nx];
                    count++;
                }
            }
        }
        return count == 0 ? 0 : sum / count;
    }
}
