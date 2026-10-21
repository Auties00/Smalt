package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.internal.task.ParallelBands;

/**
 * Estimates the as-shot white balance of a raw file that does not record it readably from the
 * camera's own rendering of the shot, its embedded JPEG preview.
 *
 * <p>The camera rendered the preview with its as-shot white balance, so photosites that the
 * preview shows as neutral gray recorded the camera neutral. The preview and the sensor image are
 * both reduced to a coarse grid of cells over the same field of view; the cells that are neutral
 * and well exposed in the preview, and unclipped in the sensor image, are averaged in camera space,
 * and their mean is the camera neutral. Without enough such cells the mean of every unclipped
 * cell is used (a gray world estimate), and without a preview the neutral is left unknown.
 *
 * @implNote This implementation uses a 48x32 grid, a preview decoded at its smallest DCT scale of
 * at least 256x170, cells whose preview saturation is below 0.08 (relaxed to 0.16 when fewer
 * than 12 qualify) with linear luminance from 0.03 to 0.8, and sensor cells without a
 * photosite above 0.95 of white.
 */
final class PreviewBalance {
    /**
     * The grid columns.
     */
    private static final int COLUMNS = 48;

    /**
     * The grid rows.
     */
    private static final int ROWS = 32;

    /**
     * Prevents instantiation.
     */
    private PreviewBalance() {
        throw new AssertionError();
    }

    /**
     * Estimates and records the as-shot neutral of a file, when it has none.
     *
     * @param file the parsed file, whose color calibration receives the estimate
     * @param image the linear image of the sensor
     * @param window the left, top, width and height of the area the preview shows
     */
    static void apply(RawFile file, LinearImage image, int[] window) {
        if (file.sensor == null || !file.sensor.estimateBalance || file.color.asShotNeutral != null || image.cfa == null
                || image.colors != 3) {
            return;
        }
        var neutral = estimate(file, image, window);
        if (neutral != null) {
            file.color.asShotNeutral = neutral;
        }
    }

    /**
     * Estimates the camera neutral.
     *
     * @param file the parsed file holding the previews
     * @param image the linear image
     * @param window the area the preview shows
     * @return the neutral, or {@code null} when nothing can be estimated
     */
    private static double[] estimate(RawFile file, LinearImage image, int[] window) {
        var raw = new double[ROWS * COLUMNS * 3];
        var counts = new int[ROWS * COLUMNS * 3];
        var clipped = new boolean[ROWS * COLUMNS];
        var width = window[2];
        var height = window[3];
        var cfaWidth = image.cfa.width();
        var cfaHeight = image.cfa.height();
        var cellOfColumn = new int[width];
        var phaseOfColumn = new int[width];
        for (var x = 0; x < width; x++) {
            cellOfColumn[x] = x * COLUMNS / width;
            phaseOfColumn[x] = (window[0] + x) % cfaWidth;
        }
        // A band owns whole grid rows, so each cell sums its photosites in the serial order.
        var bands = ParallelBands.bands(ROWS, (long) width * height);
        ParallelBands.run(bands, band -> {
            var last = firstRow(ParallelBands.start(band + 1, bands, ROWS), height);
            for (var y = firstRow(ParallelBands.start(band, bands, ROWS), height); y < last; y++) {
                var cellRow = y * ROWS / height * COLUMNS;
                var line = (window[1] + y) * image.width + window[0];
                var phases = ((window[1] + y) % cfaHeight) * cfaWidth;
                for (var x = 0; x < width; x++) {
                    var cell = cellRow + cellOfColumn[x];
                    var value = image.data[line + x];
                    var slot = cell * 3 + image.planes[phases + phaseOfColumn[x]];
                    raw[slot] += value;
                    counts[slot]++;
                    if (value > 0.95f) {
                        clipped[cell] = true;
                    }
                }
            }
        });
        var preview = preview(file);
        double[] sums;
        if (preview != null) {
            sums = select(raw, counts, clipped, preview, 0.08);
            if (sums == null) {
                sums = select(raw, counts, clipped, preview, 0.16);
            }
        } else {
            sums = null;
        }
        if (sums == null) {
            sums = new double[3];
            for (var cell = 0; cell < ROWS * COLUMNS; cell++) {
                if (clipped[cell]) {
                    continue;
                }
                for (var plane = 0; plane < 3; plane++) {
                    if (counts[cell * 3 + plane] > 0) {
                        sums[plane] += raw[cell * 3 + plane] / counts[cell * 3 + plane];
                    }
                }
            }
        }
        if (!(sums[0] > 0) || !(sums[1] > 0) || !(sums[2] > 0)) {
            return null;
        }
        return new double[]{sums[0] / sums[1], 1, sums[2] / sums[1]};
    }

    /**
     * {@return the first image row of a grid row, or the image height for the grid row past the last}
     *
     * @param cellRow the grid row, from 0 to {@link #ROWS}
     * @param height the image height
     */
    private static int firstRow(int cellRow, int height) {
        return (int) (((long) cellRow * height + ROWS - 1) / ROWS);
    }

    /**
     * Sums the camera means of the cells the preview shows as neutral.
     *
     * @param raw the per-cell, per-plane sums of the sensor image
     * @param counts the per-cell, per-plane photosite counts
     * @param clipped whether each cell has a clipped photosite
     * @param preview the per-cell linear preview colors
     * @param saturation the largest preview saturation of a neutral cell
     * @return the sums of the selected cells' means, or {@code null} when fewer than 12 qualify
     */
    private static double[] select(double[] raw, int[] counts, boolean[] clipped, float[] preview, double saturation) {
        var sums = new double[3];
        var selected = 0;
        for (var cell = 0; cell < ROWS * COLUMNS; cell++) {
            if (clipped[cell] || counts[cell * 3] == 0 || counts[cell * 3 + 1] == 0 || counts[cell * 3 + 2] == 0) {
                continue;
            }
            var r = preview[cell * 3];
            var g = preview[cell * 3 + 1];
            var b = preview[cell * 3 + 2];
            var max = Math.max(r, Math.max(g, b));
            var min = Math.min(r, Math.min(g, b));
            var luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b;
            if (luminance < 0.03 || luminance > 0.8 || (max - min) / max > saturation) {
                continue;
            }
            for (var plane = 0; plane < 3; plane++) {
                sums[plane] += raw[cell * 3 + plane] / counts[cell * 3 + plane];
            }
            selected++;
        }
        return selected >= 12 ? sums : null;
    }

    /**
     * Decodes the largest preview at a small size and averages it over the grid, in linear light.
     *
     * @param file the parsed file holding the previews
     * @return the per-cell linear RGB means, or {@code null} when the file has no decodable
     * preview
     */
    private static float[] preview(RawFile file) {
        RawFile.Preview largest = null;
        for (var preview : file.previews) {
            if (largest == null || (long) preview.width() * preview.height() > (long) largest.width() * largest.height()) {
                largest = preview;
            }
        }
        if (largest == null) {
            return null;
        }
        try {
            var picture = ImageOps.toRgba(RawDecoding.previewPicture(file, largest,
                    ImageDecoderOptions.builder().limits(file.limits).minSize(256, 170).build()));
            var linear = new float[256];
            for (var index = 0; index < 256; index++) {
                var v = index / 255.0;
                linear[index] = (float) (v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4));
            }
            var sums = new float[ROWS * COLUMNS * 3];
            var counts = new int[ROWS * COLUMNS];
            var pixels = picture.pixels();
            for (var y = 0; y < picture.height(); y++) {
                var cellRow = y * ROWS / picture.height();
                for (var x = 0; x < picture.width(); x++) {
                    var cell = cellRow * COLUMNS + x * COLUMNS / picture.width();
                    var at = y * picture.stride() + x * 4;
                    sums[cell * 3] += linear[pixels[at] & 0xFF];
                    sums[cell * 3 + 1] += linear[pixels[at + 1] & 0xFF];
                    sums[cell * 3 + 2] += linear[pixels[at + 2] & 0xFF];
                    counts[cell]++;
                }
            }
            for (var cell = 0; cell < counts.length; cell++) {
                if (counts[cell] > 0) {
                    for (var plane = 0; plane < 3; plane++) {
                        sums[cell * 3 + plane] /= counts[cell];
                    }
                }
            }
            return sums;
        } catch (MediaException.Io exception) {
            throw exception;
        } catch (MediaException exception) {
            return null;
        }
    }
}
