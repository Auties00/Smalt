/* Ports Pillow (src/libImaging/ModeFilter.c, the window, tie and repetition rules). Copyright Secret Labs AB,
 * Fredrik Lundh and contributors, and Jeffrey A. Clark and contributors; MIT-CMU License. Modified for Smalt. */
package com.github.auties00.smalt.image.ops;

import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.internal.task.ParallelBands;

import java.util.Arrays;

/**
 * Replaces every sample of a packed RGBA picture with the most frequent value of the square window
 * around it, each channel independently.
 *
 * <p>The window is {@code (2r + 1) x (2r + 1)} samples, cut at the edges of the picture (positions
 * outside it are not counted). The most frequent value wins, the smallest one among equally frequent
 * values; when it occurs at most twice the sample is kept. Alpha is filtered like the colors when the
 * picture reports alpha, and is opaque otherwise.
 *
 * @implNote This implementation slides a histogram along each row and keeps the largest count of each block of
 * values, so that finding the mode scans the block maxima and one block instead of every bin; a block whose
 * maximum may have dropped is rescanned only when it is next queried.
 */
final class ModeFilter {
    /**
     * Prevents instantiation.
     */
    private ModeFilter() {
        throw new AssertionError();
    }

    /**
     * Filters a packed picture.
     *
     * @param picture the packed picture, 8 or 16-bit
     * @param radius the window radius, positive
     * @return the filtered picture, at the input's depth
     */
    static Picture filter(Picture picture, int radius) {
        var width = picture.width();
        var height = picture.height();
        OpChecks.checkArray(4L * width * height);
        var out = Tone.allocate(picture);
        var max = Tone.max(picture);
        var channels = picture.hasAlpha() ? 4 : 3;
        var side = 2L * radius + 1;
        var bands = ParallelBands.bands(height, 4L * width * height * (2 * side + 64));
        ParallelBands.run(bands, band -> {
            var top = ParallelBands.start(band, bands, height);
            var bottom = ParallelBands.start(band + 1, bands, height);
            var counter = new Counter(max + 1);
            var window = Math.min(2 * radius + 1, height);
            var rows = new int[window][4 * width];
            var held = new int[window];
            Arrays.fill(held, -1);
            var line = new int[4 * width];
            for (var y = top; y < bottom; y++) {
                var first = Math.max(y - radius, 0);
                var last = Math.min(y + radius, height - 1);
                for (var r = first; r <= last; r++) {
                    var slot = r % window;
                    if (held[slot] != r) {
                        RankFilter.load(picture, r, rows[slot]);
                        held[slot] = r;
                    }
                }
                for (var c = 0; c < channels; c++) {
                    for (var x = 0; x <= Math.min(radius, width - 1); x++) {
                        column(counter, rows, window, first, last, 4 * x + c, 1);
                    }
                    var own = rows[y % window];
                    for (var x = 0; x < width; x++) {
                        var mode = counter.mode();
                        line[4 * x + c] = mode >= 0 ? mode : own[4 * x + c];
                        if (x - radius >= 0) {
                            column(counter, rows, window, first, last, 4 * (x - radius) + c, -1);
                        }
                        if (x + radius + 1 < width) {
                            column(counter, rows, window, first, last, 4 * (x + radius + 1) + c, 1);
                        }
                    }
                    for (var x = Math.max(width - radius, 0); x < width; x++) {
                        column(counter, rows, window, first, last, 4 * x + c, -1);
                    }
                }
                for (var c = channels; c < 4; c++) {
                    for (var x = 0; x < width; x++) {
                        line[4 * x + c] = max;
                    }
                }
                RankFilter.store(out, y, line);
            }
        });
        return out;
    }

    /**
     * Adds or removes the samples of one column of the window.
     *
     * @param counter the histogram
     * @param rows the ring of loaded rows, row {@code r} in slot {@code r % window}
     * @param window the number of slots of the ring
     * @param first the first row of the window
     * @param last the last row of the window
     * @param index the index of the sample in a row
     * @param delta {@code 1} to add, {@code -1} to remove
     */
    private static void column(Counter counter, int[][] rows, int window, int first, int last, int index, int delta) {
        for (var r = first; r <= last; r++) {
            var value = rows[r % window][index];
            if (delta > 0) {
                counter.add(value);
            } else {
                counter.remove(value);
            }
        }
    }

    /**
     * Counts the values of a window and finds its mode.
     */
    private static final class Counter {
        /**
         * The base-2 logarithm of the values per block.
         */
        private final int shift;

        /**
         * The count of each value.
         */
        private final int[] counts;

        /**
         * An upper bound of the largest count of each block, exact when the block is not dirty.
         */
        private final int[] blockMax;

        /**
         * Whether the largest count of each block may be below its bound.
         */
        private final boolean[] dirty;

        /**
         * Creates an empty counter.
         *
         * @param bins the number of values, 256 or 65536
         */
        Counter(int bins) {
            this.shift = bins > 256 ? 8 : 4;
            this.counts = new int[bins];
            this.blockMax = new int[bins >>> shift];
            this.dirty = new boolean[bins >>> shift];
        }

        /**
         * Counts one more sample of a value.
         *
         * @param value the sample value, below the number of bins
         */
        void add(int value) {
            var count = ++counts[value];
            var block = value >>> shift;
            if (count > blockMax[block]) {
                blockMax[block] = count;
            }
        }

        /**
         * Counts one sample of a value less.
         *
         * @param value the value, counted at least once
         */
        void remove(int value) {
            var block = value >>> shift;
            if (counts[value]-- == blockMax[block]) {
                dirty[block] = true;
            }
        }

        /**
         * Returns the smallest of the most frequent values.
         *
         * @return the value, or {@code -1} when the most frequent value occurs at most twice
         */
        int mode() {
            var best = 0;
            var bestBlock = 0;
            var size = 1 << shift;
            for (var block = 0; block < blockMax.length; block++) {
                if (dirty[block]) {
                    var top = 0;
                    var base = block << shift;
                    for (var v = 0; v < size; v++) {
                        top = Math.max(top, counts[base + v]);
                    }
                    blockMax[block] = top;
                    dirty[block] = false;
                }
                if (blockMax[block] > best) {
                    best = blockMax[block];
                    bestBlock = block;
                }
            }
            if (best <= 2) {
                return -1;
            }
            var base = bestBlock << shift;
            var v = 0;
            while (counts[base + v] != best) {
                v++;
            }
            return base + v;
        }
    }
}
