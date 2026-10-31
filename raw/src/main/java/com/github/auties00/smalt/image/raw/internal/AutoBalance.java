package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.image.raw.RawWhiteBalance;

import java.util.Arrays;

/**
 * Estimates the white balance of a raw image from its camera values, for {@link RawWhiteBalance.Automatic}.
 *
 * <p>The estimate works on blocks: the photosites of one repeat of the color filter array of a color filter array
 * image (2x2 for a Bayer array, 6x6 for an X-Trans array; each of whose colors is averaged over the block, from the
 * first sample of an image of two samples per photosite), or the pixels of a linear image. A block counts when
 * none of its values is at or above {@link #CLIPPED} or below {@link #DARK}. Gray world takes the mean of each
 * color over the blocks; white
 * patch the mean over the blocks whose color sum is in the top {@link #PATCH} of the blocks, found with a
 * histogram of the sums. The result is the multipliers that make that mean neutral.
 */
final class AutoBalance {
    /**
     * The level, relative to white, at or above which a value counts as clipped.
     */
    static final float CLIPPED = 0.98f;

    /**
     * The level, relative to white, below which a value counts as too dark to measure.
     */
    static final float DARK = 0.001f;

    /**
     * The fraction of the counted blocks the white patch method averages.
     */
    static final double PATCH = 0.01;

    /**
     * The largest color filter array repeat, in photosites each way, whose blocks are measured.
     */
    private static final int MAX_REPEAT = 8;

    /**
     * The bins of the histogram of block sums.
     */
    private static final int BINS = 4096;

    /**
     * Prevents instantiation.
     */
    private AutoBalance() {
        throw new AssertionError();
    }

    /**
     * Returns a white balance with an automatic estimate replaced by the multipliers it finds.
     *
     * @param balance the white balance
     * @param image the linear image
     * @param window the left, top, width and height of the area measured
     * @return the balance itself when it is not automatic, the estimated multipliers, or the as-shot balance
     * when the area holds no block to measure
     */
    static RawWhiteBalance resolve(RawWhiteBalance balance, LinearImage image, int[] window) {
        if (!(balance instanceof RawWhiteBalance.Automatic automatic)) {
            return balance;
        }
        var means = estimate(image, window, automatic.method());
        if (means == null) {
            return RawWhiteBalance.asShot();
        }
        var factors = new double[means.length];
        for (var index = 0; index < means.length; index++) {
            factors[index] = 1 / means[index];
        }
        return RawWhiteBalance.multipliers(factors);
    }

    /**
     * Returns the mean of each color over the counted blocks of an area.
     *
     * @param image the linear image: a color filter array image of a repeat of at most {@link #MAX_REPEAT}
     * photosites each way, or an image of three samples per pixel
     * @param window the left, top, width and height of the area
     * @param method the estimation method
     * @return the mean of each color plane (three or the image's four), each positive, or {@code null} when no
     * block counts or the image is of another kind
     */
    static double[] estimate(LinearImage image, int[] window, RawWhiteBalance.Automatic.Method method) {
        int colors;
        int stepX;
        int stepY;
        if (image.cfa != null && image.cfa.width() <= MAX_REPEAT && image.cfa.height() <= MAX_REPEAT) {
            colors = image.colors;
            stepX = image.cfa.width();
            stepY = image.cfa.height();
        } else if (image.cfa == null && image.samplesPerPixel == 3) {
            colors = 3;
            stepX = 1;
            stepY = 1;
        } else {
            return null;
        }
        var left = window[0];
        var top = window[1];
        var columns = window[2] / stepX;
        var rows = window[3] / stepY;
        if (columns <= 0 || rows <= 0) {
            return null;
        }
        var block = new double[colors];
        var counts = new int[colors];
        var sums = new double[colors];
        var counted = 0L;
        var histogram = method == RawWhiteBalance.Automatic.Method.WHITE_PATCH ? new long[BINS] : null;
        for (var pass = 0; pass < (histogram != null ? 2 : 1); pass++) {
            var threshold = pass == 1 ? threshold(histogram, counted, colors) : -1;
            for (var row = 0; row < rows; row++) {
                for (var column = 0; column < columns; column++) {
                    if (!block(image, left + column * stepX, top + row * stepY, stepX, stepY, block, counts)) {
                        continue;
                    }
                    var total = 0.0;
                    for (var color = 0; color < colors; color++) {
                        total += block[color];
                    }
                    if (histogram != null && pass == 0) {
                        histogram[Math.min(BINS - 1, (int) (total / colors * BINS))]++;
                        counted++;
                        continue;
                    }
                    if (threshold >= 0 && (int) Math.min(BINS - 1, total / colors * BINS) < threshold) {
                        continue;
                    }
                    for (var color = 0; color < colors; color++) {
                        sums[color] += block[color];
                    }
                    if (histogram == null) {
                        counted++;
                    }
                }
            }
        }
        var largest = 0.0;
        for (var color = 0; color < colors; color++) {
            largest = Math.max(largest, sums[color]);
        }
        if (counted == 0 || !(largest > 0)) {
            return null;
        }
        var result = new double[colors];
        for (var color = 0; color < colors; color++) {
            if (!(sums[color] > 0)) {
                return null;
            }
            result[color] = sums[color] / largest;
        }
        return result;
    }

    /**
     * Returns the histogram bin from which the brightest {@link #PATCH} of the counted blocks lie.
     *
     * @param histogram the histogram of the block sums
     * @param counted the blocks counted
     * @param colors the colors of a block
     * @return the first bin of the brightest blocks
     */
    private static int threshold(long[] histogram, long counted, int colors) {
        var wanted = Math.max(1, (long) Math.ceil(counted * PATCH));
        var seen = 0L;
        for (var bin = BINS - 1; bin > 0; bin--) {
            seen += histogram[bin];
            if (seen >= wanted) {
                return bin;
            }
        }
        return 0;
    }

    /**
     * Averages the values of each color of one block.
     *
     * @param image the linear image
     * @param x the block's left column
     * @param y the block's top row
     * @param width the block's width, the color filter array's repeat or 1
     * @param height the block's height, the color filter array's repeat or 1
     * @param block the mean of each color, filled
     * @param counts scratch space of one count per color
     * @return whether the block counts: every color present and no value clipped or too dark
     */
    private static boolean block(LinearImage image, int x, int y, int width, int height, double[] block,
                                 int[] counts) {
        Arrays.fill(block, 0);
        Arrays.fill(counts, 0);
        var spp = image.samplesPerPixel;
        if (image.cfa == null) {
            var at = (y * image.width + x) * spp;
            for (var color = 0; color < 3; color++) {
                var value = image.data[at + color];
                if (!(value >= DARK && value < CLIPPED)) {
                    return false;
                }
                block[color] = value;
            }
            return true;
        }
        for (var dy = 0; dy < height; dy++) {
            for (var dx = 0; dx < width; dx++) {
                var value = image.data[((y + dy) * image.width + x + dx) * spp];
                if (!(value >= DARK && value < CLIPPED)) {
                    return false;
                }
                var plane = image.plane(y + dy, x + dx);
                if (plane < 0 || plane >= block.length) {
                    return false;
                }
                block[plane] += value;
                counts[plane]++;
            }
        }
        for (var color = 0; color < block.length; color++) {
            if (counts[color] == 0) {
                return false;
            }
            block[color] /= counts[color];
        }
        return true;
    }
}
