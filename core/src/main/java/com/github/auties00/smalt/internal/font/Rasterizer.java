package com.github.auties00.smalt.internal.font;

import java.util.Arrays;

/**
 * Fills outlines into an 8-bit coverage mask with the nonzero winding rule.
 *
 * <p>Each pixel row is sampled on {@value #SUBROWS} evenly spaced lines; on each, the spans whose
 * winding number is not zero are covered with their exact horizontal extent, so a pixel's coverage
 * is the covered fraction of its sample lines' length, rounded to 0 to 255. Lines outside the mask
 * are clipped. An instance is not thread-safe.
 */
public final class Rasterizer implements GlyphOutline.LineSink {
    /**
     * The sample lines of each pixel row.
     */
    private static final int SUBROWS = 16;

    private final int width;
    private final int height;

    /**
     * The edges as {@code x0, y0, x1, y1} quadruples, y0 below y1, with the winding direction in
     * {@link #directions}.
     */
    private double[] edges = new double[256];

    private int[] directions = new int[64];

    private int count;

    /**
     * Constructs an empty rasterizer.
     *
     * @param width the mask width in pixels, positive
     * @param height the mask height in pixels, positive
     */
    public Rasterizer(int width, int height) {
        this.width = width;
        this.height = height;
    }

    @Override
    public void line(double x0, double y0, double x1, double y1) {
        if (y0 == y1 || Double.isNaN(x0 + y0 + x1 + y1)) {
            return;
        }
        var direction = 1;
        if (y0 > y1) {
            var t = x0;
            x0 = x1;
            x1 = t;
            t = y0;
            y0 = y1;
            y1 = t;
            direction = -1;
        }
        if (y1 <= 0 || y0 >= height) {
            return;
        }
        if (count == directions.length) {
            directions = Arrays.copyOf(directions, 2 * count);
            edges = Arrays.copyOf(edges, 8 * count);
        }
        edges[4 * count] = x0;
        edges[4 * count + 1] = y0;
        edges[4 * count + 2] = x1;
        edges[4 * count + 3] = y1;
        directions[count++] = direction;
    }

    /**
     * Fills the outlines received so far.
     *
     * @return the coverage, {@code width * height} bytes in rows, 0 for none and 255 for full
     */
    public byte[] fill() {
        var mask = new byte[width * height];
        var coverage = new float[width + 1];
        var crossings = new double[Math.max(4, count)];
        var windings = new int[Math.max(4, count)];
        var order = new int[Math.max(4, count)];
        var rows = new int[height + 1];
        for (var edge = 0; edge < count; edge++) {
            var first = (int) Math.max(0, Math.floor(edges[4 * edge + 1]));
            var last = (int) Math.min(height - 1, Math.ceil(edges[4 * edge + 3]) - 1);
            for (var row = first; row <= last; row++) {
                rows[row + 1]++;
            }
        }
        for (var row = 0; row < height; row++) {
            rows[row + 1] += rows[row];
        }
        var bucket = new int[rows[height]];
        var fill = Arrays.copyOf(rows, height);
        for (var edge = 0; edge < count; edge++) {
            var first = (int) Math.max(0, Math.floor(edges[4 * edge + 1]));
            var last = (int) Math.min(height - 1, Math.ceil(edges[4 * edge + 3]) - 1);
            for (var row = first; row <= last; row++) {
                bucket[fill[row]++] = edge;
            }
        }
        for (var row = 0; row < height; row++) {
            Arrays.fill(coverage, 0);
            var active = rows[row + 1] - rows[row];
            if (active == 0) {
                continue;
            }
            for (var sub = 0; sub < SUBROWS; sub++) {
                var y = row + (sub + 0.5) / SUBROWS;
                var found = 0;
                for (var slot = rows[row]; slot < rows[row + 1]; slot++) {
                    var edge = bucket[slot];
                    var y0 = edges[4 * edge + 1];
                    var y1 = edges[4 * edge + 3];
                    if (y < y0 || y >= y1) {
                        continue;
                    }
                    var x0 = edges[4 * edge];
                    var x1 = edges[4 * edge + 2];
                    crossings[found] = x0 + (x1 - x0) * (y - y0) / (y1 - y0);
                    windings[found] = directions[edge];
                    order[found] = found;
                    found++;
                }
                if (found < 2) {
                    continue;
                }
                sort(order, crossings, found);
                var winding = 0;
                for (var index = 0; index < found - 1; index++) {
                    winding += windings[order[index]];
                    if (winding != 0) {
                        span(coverage, crossings[order[index]], crossings[order[index + 1]]);
                    }
                }
            }
            var offset = row * width;
            for (var x = 0; x < width; x++) {
                var value = Math.round(coverage[x] * (255f / SUBROWS));
                mask[offset + x] = (byte) Math.min(255, value);
            }
        }
        return mask;
    }

    /**
     * Adds the coverage of a span of one sample line.
     *
     * @param coverage the covered length of each pixel, in sample-line units
     * @param from the left end in pixels
     * @param to the right end in pixels
     */
    private void span(float[] coverage, double from, double to) {
        var left = Math.max(0, from);
        var right = Math.min(width, to);
        if (right <= left) {
            return;
        }
        var first = (int) left;
        var last = (int) Math.ceil(right) - 1;
        if (first == last) {
            coverage[first] += (float) (right - left);
            return;
        }
        coverage[first] += (float) (first + 1 - left);
        for (var x = first + 1; x < last; x++) {
            coverage[x] += 1;
        }
        coverage[last] += (float) (right - last);
    }

    /**
     * Sorts the first indices of an order by their crossing, with an insertion sort (the lists are
     * short).
     *
     * @param order the indices to sort
     * @param crossings the crossing of each index
     * @param length the number of indices
     */
    private static void sort(int[] order, double[] crossings, int length) {
        for (var index = 1; index < length; index++) {
            var value = order[index];
            var key = crossings[value];
            var position = index - 1;
            while (position >= 0 && crossings[order[position]] > key) {
                order[position + 1] = order[position];
                position--;
            }
            order[position + 1] = value;
        }
    }
}
