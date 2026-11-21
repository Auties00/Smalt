package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.raw.CfaLayout;
import com.github.auties00.smalt.image.raw.RawDemosaic;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.task.ParallelBands;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Demosaics a color filter array whose photosites follow a staggered DNG layout onto the
 * rectangular grid of the stored image.
 *
 * <p>Each photosite lies at its physical place: its stored row and column, moved by the half row or
 * column its {@link CfaLayout} gives, with rows {@code scaleV} and columns {@code scaleH} apart
 * (the {@code DefaultScale} of the file, so that distances are measured on the sensor). Every pixel
 * of the output, at the integer grid positions, gets each color plane from the photosites of that
 * plane nearest to it, through the plane that fits them best (weighted least squares), so that a
 * smoothly varying scene is reproduced whatever the arrangement: in bilinear mode the plane's value
 * at the pixel; in color-difference mode, used for {@link RawDemosaic#MENON}, green that way and
 * red and blue as green plus the fitted difference of the neighbors to the green interpolated at
 * their own places. Neighbors outside the image are left out. The result is a three-channel linear image
 * of the stored size; a four-color image is white balanced, clipped or blended and projected to
 * three channels as it is demosaiced.
 */
final class Staggered {
    /**
     * The farthest row or column a stencil reaches.
     */
    private static final int RADIUS = 3;

    /**
     * The distance, relative to the nearest photosite of a plane, up to which photosites of the plane
     * enter a stencil.
     */
    private static final double RING = 1.5;

    /**
     * Prevents instantiation.
     */
    private Staggered() {
        throw new AssertionError();
    }

    /**
     * A neighbor considered for a stencil.
     *
     * @param row the row offset
     * @param column the column offset
     * @param x the horizontal distance from the target, on the sensor
     * @param y the vertical distance from the target, on the sensor
     * @param distance the distance from the target
     */
    private record Candidate(int row, int column, double x, double y, double distance) {
    }

    /**
     * The neighbors of one phase for one plane: their row and column offsets and normalized
     * weights.
     *
     * @param rows the row offsets
     * @param columns the column offsets
     * @param weights the weights, summing to 1
     */
    private record Stencil(int[] rows, int[] columns, float[] weights) {
    }

    /**
     * Demosaics a staggered image.
     *
     * @param image the linear image of the active area, with a color filter array
     * @param layout the layout, relative to the image's top-left photosite
     * @param scaleH the horizontal distance between columns
     * @param scaleV the vertical distance between rows
     * @param algorithm the demosaicing algorithm
     * @param four the projection of a four-color image (the {@code 3 x 4} matrix followed by the four
     * white balance multipliers), or {@code null}
     * @param blend whether a four-color image's clipped highlights are blended rather than clipped
     * @param limits the resource bounds
     * @return the three-channel image of the same size
     */
    static LinearImage demosaic(LinearImage image, CfaLayout layout, double scaleH, double scaleV, RawDemosaic algorithm,
                                float[] four, boolean blend, MediaLimits limits) {
        var width = image.width;
        var height = image.height;
        var cfa = image.cfa;
        var phaseRows = lcm(cfa.height(), 2);
        var phaseColumns = lcm(cfa.width(), 2);
        var colors = image.colors;
        var pixel = stencils(image, layout, scaleH, scaleV, phaseRows, phaseColumns, false);
        var difference = algorithm == RawDemosaic.MENON && four == null && colors == 3;
        var site = difference ? stencils(image, layout, scaleH, scaleV, phaseRows, phaseColumns, true) : null;
        LimitChecks.checkAllocation(limits, (long) width * height * 4 * (difference ? 4 : 3));
        var data = image.data;
        float[] greens = null;
        if (difference) {
            // the green of every photosite, interpolated at its own place
            var g = new float[width * height];
            rows(height, (long) width * height, (first, last) -> {
                for (var y = first; y < last; y++) {
                    for (var x = 0; x < width; x++) {
                        g[y * width + x] = image.plane(y, x) == 1 ? data[y * width + x]
                                : apply(data, width, height, x, y, site[phase(y, x, phaseRows, phaseColumns) * colors + 1]);
                    }
                }
            });
            greens = g;
        }
        var green = greens;
        var output = new float[width * height * 3];
        rows(height, (long) width * height * 3, (first, last) -> {
            var values = new float[4];
            for (var y = first; y < last; y++) {
                for (var x = 0; x < width; x++) {
                    var phase = phase(y, x, phaseRows, phaseColumns);
                    var at = (y * width + x) * 3;
                    if (four != null) {
                        for (var p = 0; p < 4; p++) {
                            values[p] = apply(data, width, height, x, y, pixel[phase * colors + p]);
                        }
                        Projection.project(values, four, blend, output, at);
                        continue;
                    }
                    if (!difference) {
                        for (var p = 0; p < 3; p++) {
                            output[at + p] = apply(data, width, height, x, y, pixel[phase * colors + p]);
                        }
                        continue;
                    }
                    var g = apply(data, width, height, x, y, pixel[phase * colors + 1]);
                    output[at + 1] = g;
                    for (var p = 0; p < 3; p += 2) {
                        var stencil = pixel[phase * colors + p];
                        var sum = 0f;
                        var weight = 0f;
                        for (var k = 0; k < stencil.rows.length; k++) {
                            var ny = y + stencil.rows[k];
                            var nx = x + stencil.columns[k];
                            if (ny < 0 || nx < 0 || ny >= height || nx >= width) {
                                continue;
                            }
                            var index = ny * width + nx;
                            sum += stencil.weights[k] * (data[index] - green[index]);
                            weight += stencil.weights[k];
                        }
                        output[at + p] = weight > 0 ? g + sum / weight : g;
                    }
                }
            }
        });
        return new LinearImage(width, height, 3, output, null, null);
    }

    /**
     * Runs over a range of rows.
     */
    @FunctionalInterface
    private interface Rows {
        /**
         * Processes rows.
         *
         * @param first the first row
         * @param last the row after the last
         */
        void run(int first, int last);
    }

    /**
     * Splits rows into bands run on the shared pool.
     *
     * @param height the number of rows
     * @param work the work of all rows, for the band count
     * @param body the work of a range of rows
     */
    private static void rows(int height, long work, Rows body) {
        var bands = ParallelBands.bands(height, work);
        ParallelBands.run(bands, band -> body.run(ParallelBands.start(band, bands, height),
                ParallelBands.start(band + 1, bands, height)));
    }

    /**
     * Returns the phase of a position in the repeat of the pattern and the layout.
     *
     * @param y the row
     * @param x the column
     * @param phaseRows the rows of the repeat
     * @param phaseColumns the columns of the repeat
     * @return the phase, row-major
     */
    private static int phase(int y, int x, int phaseRows, int phaseColumns) {
        return (y % phaseRows) * phaseColumns + x % phaseColumns;
    }

    /**
     * Interpolates one value from a stencil around a position.
     *
     * @param data the image values
     * @param width the image width
     * @param height the image height
     * @param x the column
     * @param y the row
     * @param stencil the neighbors and their weights
     * @return the weighted mean of the neighbors inside the image, or {@code 0} when none is
     */
    private static float apply(float[] data, int width, int height, int x, int y, Stencil stencil) {
        var sum = 0f;
        var weight = 0f;
        var rows = stencil.rows;
        var columns = stencil.columns;
        var weights = stencil.weights;
        var interior = y >= RADIUS && x >= RADIUS && y < height - RADIUS && x < width - RADIUS;
        if (interior) {
            for (var k = 0; k < rows.length; k++) {
                sum += weights[k] * data[(y + rows[k]) * width + x + columns[k]];
            }
            return sum;
        }
        for (var k = 0; k < rows.length; k++) {
            var ny = y + rows[k];
            var nx = x + columns[k];
            if (ny >= 0 && nx >= 0 && ny < height && nx < width) {
                sum += weights[k] * data[ny * width + nx];
                weight += weights[k];
            }
        }
        return weight > 0 ? sum / weight : 0;
    }

    /**
     * Builds the stencils of every phase and plane.
     *
     * @param image the image, with its pattern and color planes
     * @param layout the photosite layout
     * @param scaleH the horizontal distance between columns
     * @param scaleV the vertical distance between rows
     * @param phaseRows the rows of the repeat
     * @param phaseColumns the columns of the repeat
     * @param atSite whether the stencils interpolate at the place of the photosite of each phase
     * rather than at its grid position
     * @return the stencils, indexed by {@code phase * colors + plane}
     */
    private static Stencil[] stencils(LinearImage image, CfaLayout layout, double scaleH, double scaleV, int phaseRows,
                                      int phaseColumns, boolean atSite) {
        var colors = image.colors;
        var stencils = new Stencil[phaseRows * phaseColumns * colors];
        for (var py = 0; py < phaseRows; py++) {
            for (var px = 0; px < phaseColumns; px++) {
                // positions are taken far from the origin so that the parities repeat
                var baseRow = py + phaseRows * 8;
                var baseColumn = px + phaseColumns * 8;
                var targetY = (baseRow + (atSite ? layout.rowOffset(baseRow, baseColumn) : 0)) * scaleV;
                var targetX = (baseColumn + (atSite ? layout.columnOffset(baseRow, baseColumn) : 0)) * scaleH;
                for (var p = 0; p < colors; p++) {
                    var candidates = new ArrayList<Candidate>();
                    for (var dy = -RADIUS; dy <= RADIUS; dy++) {
                        for (var dx = -RADIUS; dx <= RADIUS; dx++) {
                            var row = baseRow + dy;
                            var column = baseColumn + dx;
                            if (image.plane(row, column) != p || (atSite && dy == 0 && dx == 0)) {
                                continue;
                            }
                            var y = (row + layout.rowOffset(row, column)) * scaleV;
                            var x = (column + layout.columnOffset(row, column)) * scaleH;
                            candidates.add(new Candidate(dy, dx, x - targetX, y - targetY,
                                    StrictMath.hypot(y - targetY, x - targetX)));
                        }
                    }
                    candidates.sort(Comparator.comparingDouble(Candidate::distance));
                    var stencil = new Stencil(new int[0], new int[0], new float[0]);
                    if (!candidates.isEmpty()) {
                        var nearest = candidates.getFirst().distance();
                        if (nearest < 1e-9) {
                            var only = candidates.getFirst();
                            stencil = new Stencil(new int[]{only.row()}, new int[]{only.column()}, new float[]{1});
                        } else {
                            stencil = fit(candidates, nearest);
                        }
                    }
                    stencils[(py * phaseColumns + px) * colors + p] = stencil;
                }
            }
        }
        return stencils;
    }

    /**
     * Returns the stencil that interpolates a value from neighbors by a local plane: the weighted
     * least-squares fit of {@code a + b x + c y} to the neighbors nearest the target, evaluated at
     * the target, which reproduces any planar field exactly whatever the neighbors' arrangement. The
     * neighbors are those up to {@link #RING} times the nearest distance, more being taken, nearest
     * first, while they lie on one line; without three that do not, the inverse-distance weighted
     * mean of the nearest ones.
     *
     * @param candidates the neighbors, nearest first
     * @param nearest the distance of the nearest neighbor, positive
     * @return the stencil
     */
    private static Stencil fit(List<Candidate> candidates, double nearest) {
        var count = 0;
        while (count < candidates.size() && candidates.get(count).distance() <= nearest * RING + 1e-9) {
            count++;
        }
        for (var size = count; size <= candidates.size(); size++) {
            var weights = plane(candidates, size);
            if (weights != null) {
                return stencil(candidates, size, weights);
            }
        }
        var weights = new double[count];
        var total = 0.0;
        for (var k = 0; k < count; k++) {
            weights[k] = 1 / candidates.get(k).distance();
            total += weights[k];
        }
        for (var k = 0; k < count; k++) {
            weights[k] /= total;
        }
        return stencil(candidates, count, weights);
    }

    /**
     * Computes the weights of the planar least-squares fit to the first neighbors.
     *
     * @param candidates the neighbors, nearest first
     * @param size the number of neighbors used
     * @return the weights, summing to 1, or {@code null} when fewer than three neighbors or neighbors on
     * one line leave the plane undetermined
     */
    private static double[] plane(List<Candidate> candidates, int size) {
        if (size < 3) {
            return null;
        }
        // the normal equations of the fit, each neighbor weighted by its inverse distance
        var m = new double[9];
        for (var k = 0; k < size; k++) {
            var candidate = candidates.get(k);
            var w = 1 / candidate.distance();
            double[] row = {1, candidate.x(), candidate.y()};
            for (var i = 0; i < 3; i++) {
                for (var j = 0; j < 3; j++) {
                    m[i * 3 + j] += w * row[i] * row[j];
                }
            }
        }
        var inverse = ColorMath.invert(m);
        var scale = Math.abs(m[0] * m[4] * m[8]) + 1e-30;
        var determinant = m[0] * (m[4] * m[8] - m[5] * m[7]) - m[1] * (m[3] * m[8] - m[5] * m[6])
                + m[2] * (m[3] * m[7] - m[4] * m[6]);
        if (inverse == null || Math.abs(determinant) < 1e-9 * scale) {
            return null;
        }
        var weights = new double[size];
        for (var k = 0; k < size; k++) {
            var candidate = candidates.get(k);
            var w = 1 / candidate.distance();
            weights[k] = w * (inverse[0] + inverse[1] * candidate.x() + inverse[2] * candidate.y());
        }
        return weights;
    }

    /**
     * Builds a stencil from the first neighbors and their weights.
     *
     * @param candidates the neighbors
     * @param size the number of neighbors used
     * @param weights their weights
     * @return the stencil
     */
    private static Stencil stencil(List<Candidate> candidates, int size, double[] weights) {
        var rows = new int[size];
        var columns = new int[size];
        var values = new float[size];
        for (var k = 0; k < size; k++) {
            rows[k] = candidates.get(k).row();
            columns[k] = candidates.get(k).column();
            values[k] = (float) weights[k];
        }
        return new Stencil(rows, columns, values);
    }

    /**
     * {@return the least common multiple of two positive numbers}
     *
     * @param a a number
     * @param b another number
     */
    private static int lcm(int a, int b) {
        var x = a;
        var y = b;
        while (y != 0) {
            var t = x % y;
            x = y;
            y = t;
        }
        return a / x * b;
    }
}
