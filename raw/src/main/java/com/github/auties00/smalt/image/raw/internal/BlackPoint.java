package com.github.auties00.smalt.image.raw.internal;

import java.util.Arrays;

/**
 * Measures the automatic black point of a rendering: the luminance, after white balance and
 * exposure, below which the darkest 0.01% of the rendered area's pixels lie, at most 2% of white.
 *
 * <p>The luminance is sampled on a grid of at most 256 by 256 points over the window; at each point
 * a color filter array image contributes the mean of each color plane over one repeat of its
 * pattern, so the result does not depend on the demosaicing.
 */
final class BlackPoint {
    /**
     * The points sampled along each axis, at most.
     */
    private static final int GRID = 256;

    /**
     * The fraction of the samples lying below the black point.
     */
    private static final double FRACTION = 1e-4;

    /**
     * The largest black point, relative to white.
     */
    private static final float LIMIT = 0.02f;

    /**
     * Prevents instantiation.
     */
    private BlackPoint() {
        throw new AssertionError();
    }

    /**
     * Measures the black point.
     *
     * @param image the linear image, with a color filter array or three channels
     * @param window the left, top, width and height of the rendered area
     * @param four the projection of a four-color image whose color filter array the image still has,
     * or {@code null}
     * @param blend whether a four-color image's highlights are blended
     * @param pipeline the color pipeline, whose white balance and luminance the measure uses
     * @return the black point, from 0 to 0.02
     */
    static float measure(LinearImage image, int[] window, float[] four, boolean blend, ColorPipeline pipeline) {
        var stepX = Math.max(1, window[2] / GRID);
        var stepY = Math.max(1, window[3] / GRID);
        var columns = window[2] / stepX;
        var rows = window[3] / stepY;
        if (columns <= 0 || rows <= 0) {
            return 0;
        }
        var samples = new float[columns * rows];
        var values = new float[4];
        var counts = new int[4];
        var projected = new float[3];
        var multipliers = pipeline.multipliers;
        var count = 0;
        for (var row = 0; row < rows; row++) {
            var y = window[1] + row * stepY;
            for (var column = 0; column < columns; column++) {
                var x = window[0] + column * stepX;
                float r;
                float g;
                float b;
                if (image.cfa == null) {
                    var at = (y * image.width + x) * image.samplesPerPixel;
                    r = image.data[at] * multipliers[0];
                    g = image.data[at + 1] * multipliers[1];
                    b = image.data[at + 2] * multipliers[2];
                } else {
                    Arrays.fill(values, 0);
                    Arrays.fill(counts, 0);
                    var bottom = Math.min(image.height, y + image.cfa.height());
                    var right = Math.min(image.width, x + image.cfa.width());
                    for (var yy = y; yy < bottom; yy++) {
                        for (var xx = x; xx < right; xx++) {
                            var plane = image.plane(yy, xx);
                            values[plane] += image.data[yy * image.width + xx];
                            counts[plane]++;
                        }
                    }
                    for (var plane = 0; plane < 4; plane++) {
                        values[plane] = counts[plane] > 0 ? values[plane] / counts[plane] : 0;
                    }
                    if (four != null) {
                        Projection.project(values, four, blend, projected, 0);
                        r = projected[0];
                        g = projected[1];
                        b = projected[2];
                    } else {
                        r = values[0] * multipliers[0];
                        g = values[1] * multipliers[1];
                        b = values[2] * multipliers[2];
                    }
                }
                samples[count++] = pipeline.luminance(Math.min(r, 1f), Math.min(g, 1f), Math.min(b, 1f));
            }
        }
        Arrays.sort(samples, 0, count);
        var index = (int) Math.min(count - 1, Math.floor(count * FRACTION));
        return Math.clamp(samples[index], 0f, LIMIT);
    }
}
