/* Ports Pillow (src/libImaging/RankFilter.c, the window, rank and edge rules). Copyright Secret Labs AB, Fredrik
 * Lundh and contributors, and Jeffrey A. Clark and contributors; MIT-CMU License. Modified for Smalt. */
package com.github.auties00.smalt.image.ops;

import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.internal.task.ParallelBands;

/**
 * Replaces every sample of a packed RGBA picture with the sample of a given rank in the square window
 * around it, each channel independently: the minimum, the median, the maximum or any rank between.
 *
 * <p>The window is {@code (2r + 1) x (2r + 1)} samples; positions outside the picture repeat the
 * nearest edge pixel. The samples of a window are sorted in ascending order and the one at the
 * zero-based rank is kept, so the result is always a value present in the window. Alpha is filtered
 * like the colors when the picture reports alpha, and is opaque otherwise.
 *
 * @implNote This implementation computes 3x3 medians of 8-bit pictures with Devillard's 19-exchange minimum/maximum
 * network on every sample of a row at once (a kernel with identical scalar and Vector API implementations), other
 * 8-bit ranks by sliding a 256-bin histogram (with 16 coarse bins to locate the rank quickly) along each row, after
 * Huang, Yang and Tang (1979), so its cost per sample grows linearly with the radius; the minimum and the maximum are
 * separable and run as a row pass and a column pass; other 16-bit ranks select from each window directly. The window,
 * the rank and the edge replication match Pillow's {@code RankFilter} (which expands the picture by its edge pixels
 * first), so 8-bit results equal Pillow's.
 */
final class RankFilter {
    /**
     * Prevents instantiation.
     */
    private RankFilter() {
        throw new AssertionError();
    }

    /**
     * Filters a packed picture with the median of each window.
     *
     * @param picture the packed picture, 8 or 16-bit
     * @param radius the window radius, positive
     * @return the filtered picture, at the input's depth
     */
    static Picture median(Picture picture, int radius) {
        var side = 2 * radius + 1;
        return filter(picture, radius, side * side / 2);
    }

    /**
     * Filters a packed picture.
     *
     * @param picture the packed picture, 8 or 16-bit
     * @param radius the window radius, positive
     * @param rank the zero-based rank, from 0 to {@code (2 * radius + 1)^2 - 1}
     * @return the filtered picture, at the input's depth
     */
    static Picture filter(Picture picture, int radius, int rank) {
        var width = picture.width();
        var height = picture.height();
        var side = 2 * radius + 1;
        var last = side * side - 1;
        if (rank == 0 || rank == last) {
            return extreme(picture, radius, rank == last);
        }
        var bands = ParallelBands.bands(height, 4L * width * height * (2L * side + 32));
        return switch (picture) {
            case RgbaPicture rgba -> {
                OpChecks.checkArray(4L * width * height);
                var out = RgbaPicture.allocate(width, height, rgba.hasAlpha());
                ParallelBands.run(bands, band -> rows8(rgba, radius, rank, out,
                        ParallelBands.start(band, bands, height), ParallelBands.start(band + 1, bands, height)));
                yield out;
            }
            case Rgba16Picture rgba -> {
                OpChecks.checkArray(4L * width * height);
                var out = Rgba16Picture.allocate(width, height, rgba.hasAlpha());
                ParallelBands.run(bands, band -> rows16(rgba, radius, rank, out,
                        ParallelBands.start(band, bands, height), ParallelBands.start(band + 1, bands, height)));
                yield out;
            }
            default -> throw new IllegalArgumentException("not a packed picture: " + picture);
        };
    }

    /**
     * Filters a packed picture with the minimum or the maximum of each window, a row pass followed by a column
     * pass.
     *
     * @param picture the packed picture, 8 or 16-bit
     * @param radius the window radius, positive
     * @param maximum whether to keep the maximum rather than the minimum
     * @return the filtered picture, at the input's depth
     */
    private static Picture extreme(Picture picture, int radius, boolean maximum) {
        var width = picture.width();
        var height = picture.height();
        OpChecks.checkArray(4L * width * height);
        var out = Tone.allocate(picture);
        var channels = picture.hasAlpha() ? 4 : 3;
        var opaque = Tone.max(picture);
        var bands = ParallelBands.bands(height, 4L * width * height * (4L * radius + 2));
        ParallelBands.run(bands, band -> {
            var top = ParallelBands.start(band, bands, height);
            var bottom = ParallelBands.start(band + 1, bands, height);
            var first = Math.max(top - radius, 0);
            var last = Math.min(bottom + radius, height);
            var rows = new int[last - first][4 * width];
            var line = new int[4 * width];
            for (var y = first; y < last; y++) {
                load(picture, y, line);
                var target = rows[y - first];
                for (var x = 0; x < width; x++) {
                    for (var c = 0; c < channels; c++) {
                        var best = line[4 * x + c];
                        for (var dx = -radius; dx <= radius; dx++) {
                            var value = line[4 * Math.clamp(x + dx, 0, width - 1) + c];
                            best = maximum ? Math.max(best, value) : Math.min(best, value);
                        }
                        target[4 * x + c] = best;
                    }
                }
            }
            for (var y = top; y < bottom; y++) {
                for (var i = 0; i < 4 * width; i++) {
                    if ((i & 3) >= channels) {
                        line[i] = opaque;
                        continue;
                    }
                    var best = rows[y - first][i];
                    for (var dy = -radius; dy <= radius; dy++) {
                        var value = rows[Math.clamp(y + dy, 0, height - 1) - first][i];
                        best = maximum ? Math.max(best, value) : Math.min(best, value);
                    }
                    line[i] = best;
                }
                store(out, y, line);
            }
        });
        return out;
    }

    /**
     * Loads one row of a packed picture as four integer samples per pixel.
     *
     * @param picture the packed picture
     * @param y the row
     * @param line the destination, {@code 4 * width} samples
     */
    static void load(Picture picture, int y, int[] line) {
        switch (picture) {
            case RgbaPicture rgba -> {
                var pixels = rgba.pixels();
                var offset = y * rgba.stride();
                for (var i = 0; i < line.length; i++) {
                    line[i] = pixels[offset + i] & 0xFF;
                }
            }
            case Rgba16Picture rgba -> {
                var pixels = rgba.pixels();
                var offset = y * rgba.stride();
                for (var i = 0; i < line.length; i++) {
                    line[i] = pixels[offset + i] & 0xFFFF;
                }
            }
            default -> throw new IllegalArgumentException("not a packed picture: " + picture);
        }
    }

    /**
     * Stores one row of four integer samples per pixel into a picture.
     *
     * @param picture the packed picture
     * @param y the row
     * @param line the samples, {@code 4 * width}, within the picture's depth
     */
    static void store(Picture picture, int y, int[] line) {
        switch (picture) {
            case RgbaPicture rgba -> {
                var pixels = rgba.pixels();
                var offset = y * rgba.stride();
                for (var i = 0; i < line.length; i++) {
                    pixels[offset + i] = (byte) line[i];
                }
            }
            case Rgba16Picture rgba -> {
                var pixels = rgba.pixels();
                var offset = y * rgba.stride();
                for (var i = 0; i < line.length; i++) {
                    pixels[offset + i] = (short) line[i];
                }
            }
            default -> throw new IllegalArgumentException("not a packed picture: " + picture);
        }
    }

    /**
     * Filters the rows {@code [top, bottom)} of an 8-bit picture.
     *
     * @param src the picture
     * @param radius the window radius
     * @param rank the zero-based rank
     * @param out the output picture
     * @param top the first row
     * @param bottom the row after the last one
     */
    private static void rows8(RgbaPicture src, int radius, int rank, RgbaPicture out, int top, int bottom) {
        if (radius == 1 && rank == 4) {
            rows3x3(src, out, top, bottom);
            return;
        }
        var width = src.width();
        var height = src.height();
        var side = 2 * radius + 1;
        var channels = src.hasAlpha() ? 4 : 3;
        var pixels = src.pixels();
        var stride = src.stride();
        var dst = out.pixels();
        var fine = new int[256];
        var coarse = new int[16];
        var rows = new int[side];
        for (var y = top; y < bottom; y++) {
            for (var k = 0; k < side; k++) {
                rows[k] = Math.clamp(y + k - radius, 0, height - 1) * stride;
            }
            var base = y * out.stride();
            for (var channel = 0; channel < channels; channel++) {
                java.util.Arrays.fill(fine, 0);
                java.util.Arrays.fill(coarse, 0);
                for (var dx = -radius; dx <= radius; dx++) {
                    var column = 4 * Math.clamp(dx, 0, width - 1) + channel;
                    for (var row : rows) {
                        var value = pixels[row + column] & 0xFF;
                        fine[value]++;
                        coarse[value >>> 4]++;
                    }
                }
                dst[base + channel] = (byte) select(fine, coarse, rank);
                for (var x = 1; x < width; x++) {
                    var leaving = 4 * Math.max(x - radius - 1, 0) + channel;
                    var entering = 4 * Math.min(x + radius, width - 1) + channel;
                    for (var row : rows) {
                        var removed = pixels[row + leaving] & 0xFF;
                        fine[removed]--;
                        coarse[removed >>> 4]--;
                        var added = pixels[row + entering] & 0xFF;
                        fine[added]++;
                        coarse[added >>> 4]++;
                    }
                    dst[base + 4 * x + channel] = (byte) select(fine, coarse, rank);
                }
            }
            if (channels == 3) {
                for (var x = 0; x < width; x++) {
                    dst[base + 4 * x + 3] = (byte) 255;
                }
            }
        }
    }

    /**
     * Filters the rows {@code [top, bottom)} of an 8-bit picture with the 3x3 median network of
     * {@link EffectKernel#median9Row}, every channel at once.
     *
     * @param src the picture
     * @param out the output picture
     * @param top the first row
     * @param bottom the row after the last one
     */
    private static void rows3x3(RgbaPicture src, RgbaPicture out, int top, int bottom) {
        var kernel = EffectKernel.INSTANCE;
        var width = src.width();
        var height = src.height();
        var length = 4 * width;
        var ring = new byte[3][length + 8];
        var held = new int[]{-1, -1, -1};
        var rows = new byte[3][];
        var dst = out.pixels();
        for (var y = top; y < bottom; y++) {
            for (var k = 0; k < 3; k++) {
                var source = Math.clamp(y + k - 1, 0, height - 1);
                var slot = source % 3;
                if (held[slot] != source) {
                    var padded = ring[slot];
                    System.arraycopy(src.pixels(), source * src.stride(), padded, 4, length);
                    System.arraycopy(padded, 4, padded, 0, 4);
                    System.arraycopy(padded, length, padded, length + 4, 4);
                    held[slot] = source;
                }
                rows[k] = ring[slot];
            }
            var base = y * out.stride();
            kernel.median9Row(rows[0], rows[1], rows[2], 4, length, dst, base);
            if (!src.hasAlpha()) {
                for (var x = 0; x < width; x++) {
                    dst[base + 4 * x + 3] = (byte) 255;
                }
            }
        }
    }

    /**
     * Returns the sample of a given rank in a histogram.
     *
     * @param fine the counts of each value
     * @param coarse the counts of each group of 16 values
     * @param rank the zero-based rank
     * @return the value
     */
    private static int select(int[] fine, int[] coarse, int rank) {
        var seen = 0;
        var group = 0;
        while (seen + coarse[group] <= rank) {
            seen += coarse[group];
            group++;
        }
        var value = group << 4;
        while (seen + fine[value] <= rank) {
            seen += fine[value];
            value++;
        }
        return value;
    }

    /**
     * Filters the rows {@code [top, bottom)} of a 16-bit picture.
     *
     * @param src the picture
     * @param radius the window radius
     * @param rank the zero-based rank
     * @param out the output picture
     * @param top the first row
     * @param bottom the row after the last one
     */
    private static void rows16(Rgba16Picture src, int radius, int rank, Rgba16Picture out, int top, int bottom) {
        var width = src.width();
        var height = src.height();
        var side = 2 * radius + 1;
        var channels = src.hasAlpha() ? 4 : 3;
        var pixels = src.pixels();
        var stride = src.stride();
        var dst = out.pixels();
        var window = new int[side * side];
        for (var y = top; y < bottom; y++) {
            var base = y * out.stride();
            for (var x = 0; x < width; x++) {
                for (var channel = 0; channel < channels; channel++) {
                    var n = 0;
                    for (var dy = -radius; dy <= radius; dy++) {
                        var row = Math.clamp(y + dy, 0, height - 1) * stride;
                        for (var dx = -radius; dx <= radius; dx++) {
                            window[n++] = pixels[row + 4 * Math.clamp(x + dx, 0, width - 1) + channel] & 0xFFFF;
                        }
                    }
                    dst[base + 4 * x + channel] = (short) select(window, rank);
                }
                if (channels == 3) {
                    dst[base + 4 * x + 3] = (short) 0xFFFF;
                }
            }
        }
    }

    /**
     * Returns the value of a given rank, reordering the array (Hoare's selection).
     *
     * @param values the values to select from, reordered by the call
     * @param rank the zero-based rank
     * @return the value
     */
    private static int select(int[] values, int rank) {
        var low = 0;
        var high = values.length - 1;
        while (low < high) {
            var pivot = values[(low + high) >>> 1];
            var i = low;
            var j = high;
            while (i <= j) {
                while (values[i] < pivot) {
                    i++;
                }
                while (values[j] > pivot) {
                    j--;
                }
                if (i <= j) {
                    var swap = values[i];
                    values[i] = values[j];
                    values[j] = swap;
                    i++;
                    j--;
                }
            }
            if (rank <= j) {
                high = j;
            } else if (rank >= i) {
                low = i;
            } else {
                return values[rank];
            }
        }
        return values[rank];
    }
}
