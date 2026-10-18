package com.github.auties00.smalt.internal.quantize;

/**
 * Reduces a list of colors to a palette by recursive box splitting of their histogram, refined
 * with k-means.
 *
 * <p>The colors are counted in a histogram of 5 bits per channel whose bins also accumulate the
 * exact channel sums. Starting from one box holding every non-empty bin, the box with the largest
 * squared error is split, across the channel of largest variance, at the position that minimizes
 * the squared error of the two halves; this repeats until there are as many boxes as palette
 * entries or no box can be split. Each box contributes the mean of its colors. Two k-means
 * iterations over the bins then move each entry to the mean of the bins nearest to it.
 *
 * <p>The result is deterministic: the same colors and size give the same palette.
 *
 * @implNote This implementation follows Heckbert's median cut ("Color image quantization for
 * frame buffer display", SIGGRAPH 1982) with the variance-minimizing split point of Wan, Wong and
 * Prusinkiewicz ("Variance-based color image quantization for frame buffer display", 1990)
 * instead of the median; it is Smalt's own code.
 */
public final class MedianCut {
    /**
     * The number of histogram bins per channel.
     */
    private static final int LEVELS = 32;

    /**
     * The number of k-means refinement iterations.
     */
    private static final int REFINEMENTS = 2;

    /**
     * Prevents instantiation.
     */
    private MedianCut() {
        throw new AssertionError();
    }

    /**
     * Builds a palette for colors.
     *
     * @param colors the colors, packed RGB (red in the low byte); the upper byte is ignored
     * @param count  the number of colors, positive
     * @param size   the largest number of palette entries, from 1 to 256
     * @return the palette entries, packed RGB, at most {@code size} of them
     */
    public static int[] quantize(int[] colors, int count, int size) {
        var counts = new int[LEVELS * LEVELS * LEVELS];
        var sums = new long[counts.length * 3];
        for (var i = 0; i < count; i++) {
            var color = colors[i];
            var red = color & 0xFF;
            var green = (color >>> 8) & 0xFF;
            var blue = (color >>> 16) & 0xFF;
            var bin = (red >>> 3) | (green >>> 3) << 5 | (blue >>> 3) << 10;
            counts[bin]++;
            sums[bin * 3] += red;
            sums[bin * 3 + 1] += green;
            sums[bin * 3 + 2] += blue;
        }
        var binCount = 0;
        for (var count1 : counts) {
            if (count1 != 0) {
                binCount++;
            }
        }
        var bins = new int[binCount];
        var weights = new long[binCount];
        var means = new double[binCount * 3];
        var next = 0;
        for (var bin = 0; bin < counts.length; bin++) {
            if (counts[bin] == 0) {
                continue;
            }
            bins[next] = bin;
            weights[next] = counts[bin];
            means[next * 3] = (double) sums[bin * 3] / counts[bin];
            means[next * 3 + 1] = (double) sums[bin * 3 + 1] / counts[bin];
            means[next * 3 + 2] = (double) sums[bin * 3 + 2] / counts[bin];
            next++;
        }
        var order = new int[binCount];
        for (var i = 0; i < binCount; i++) {
            order[i] = i;
        }
        var starts = new int[size];
        var ends = new int[size];
        var errors = new double[size];
        starts[0] = 0;
        ends[0] = binCount;
        errors[0] = error(order, 0, binCount, weights, means);
        var boxes = 1;
        var scratch = new int[binCount];
        while (boxes < size) {
            var target = -1;
            var largest = 0.0;
            for (var box = 0; box < boxes; box++) {
                if (ends[box] - starts[box] > 1 && errors[box] > largest) {
                    largest = errors[box];
                    target = box;
                }
            }
            if (target < 0) {
                break;
            }
            var split = split(order, starts[target], ends[target], bins, weights, means, scratch);
            starts[boxes] = split;
            ends[boxes] = ends[target];
            ends[target] = split;
            errors[target] = error(order, starts[target], ends[target], weights, means);
            errors[boxes] = error(order, starts[boxes], ends[boxes], weights, means);
            boxes++;
        }
        var palette = new int[boxes];
        for (var box = 0; box < boxes; box++) {
            palette[box] = mean(order, starts[box], ends[box], weights, means);
        }
        for (var iteration = 0; iteration < REFINEMENTS && boxes > 1; iteration++) {
            refine(palette, weights, means, binCount);
        }
        return palette;
    }

    /**
     * Returns the weighted squared error of a box around its mean.
     *
     * @param order   the bin order
     * @param start   the first position of the box in {@code order}
     * @param end     the position after the box
     * @param weights the pixel count of each bin
     * @param means   the mean color of each bin, three channels each
     * @return the squared error
     */
    private static double error(int[] order, int start, int end, long[] weights, double[] means) {
        var total = 0.0;
        var red = 0.0;
        var green = 0.0;
        var blue = 0.0;
        var squares = 0.0;
        for (var i = start; i < end; i++) {
            var bin = order[i];
            double weight = weights[bin];
            var r = means[bin * 3];
            var g = means[bin * 3 + 1];
            var b = means[bin * 3 + 2];
            total += weight;
            red += weight * r;
            green += weight * g;
            blue += weight * b;
            squares += weight * (r * r + g * g + b * b);
        }
        return squares - (red * red + green * green + blue * blue) / total;
    }

    /**
     * Sorts a box along its channel of largest variance and returns the split position that
     * minimizes the squared error of the two halves.
     *
     * @param order   the bin order, reordered within the box
     * @param start   the first position of the box
     * @param end     the position after the box, at least {@code start + 2}
     * @param bins    the histogram index of each bin
     * @param weights the pixel count of each bin
     * @param means   the mean color of each bin
     * @param scratch a buffer at least as long as {@code order}
     * @return the first position of the second half, strictly between {@code start} and
     *         {@code end}
     */
    private static int split(int[] order, int start, int end, int[] bins, long[] weights, double[] means,
                             int[] scratch) {
        var total = 0.0;
        var sums = new double[3];
        var squares = new double[3];
        for (var i = start; i < end; i++) {
            var bin = order[i];
            double weight = weights[bin];
            total += weight;
            for (var channel = 0; channel < 3; channel++) {
                var value = means[bin * 3 + channel];
                sums[channel] += weight * value;
                squares[channel] += weight * value * value;
            }
        }
        var axis = 0;
        var widest = -1.0;
        for (var channel = 0; channel < 3; channel++) {
            var variance = squares[channel] - sums[channel] * sums[channel] / total;
            if (variance > widest) {
                widest = variance;
                axis = channel;
            }
        }
        var histogram = new int[LEVELS + 1];
        var shift = axis * 5;
        for (var i = start; i < end; i++) {
            histogram[((bins[order[i]] >>> shift) & (LEVELS - 1)) + 1]++;
        }
        for (var level = 0; level < LEVELS; level++) {
            histogram[level + 1] += histogram[level];
        }
        for (var i = start; i < end; i++) {
            var bin = order[i];
            scratch[start + histogram[(bins[bin] >>> shift) & (LEVELS - 1)]++] = bin;
        }
        System.arraycopy(scratch, start, order, start, end - start);
        var bestPosition = start + 1;
        var bestScore = Double.NEGATIVE_INFINITY;
        var leftWeight = 0.0;
        var leftSum = 0.0;
        var axisTotal = sums[axis];
        for (var i = start; i < end - 1; i++) {
            var bin = order[i];
            leftWeight += weights[bin];
            leftSum += weights[bin] * means[bin * 3 + axis];
            var rightWeight = total - leftWeight;
            var rightSum = axisTotal - leftSum;
            var score = leftSum * leftSum / leftWeight + rightSum * rightSum / rightWeight;
            if (score > bestScore) {
                bestScore = score;
                bestPosition = i + 1;
            }
        }
        return bestPosition;
    }

    /**
     * Returns the weighted mean color of a box, rounded.
     *
     * @param order   the bin order
     * @param start   the first position of the box
     * @param end     the position after the box
     * @param weights the pixel count of each bin
     * @param means   the mean color of each bin
     * @return the mean, packed RGB
     */
    private static int mean(int[] order, int start, int end, long[] weights, double[] means) {
        var total = 0.0;
        var red = 0.0;
        var green = 0.0;
        var blue = 0.0;
        for (var i = start; i < end; i++) {
            var bin = order[i];
            double weight = weights[bin];
            total += weight;
            red += weight * means[bin * 3];
            green += weight * means[bin * 3 + 1];
            blue += weight * means[bin * 3 + 2];
        }
        return pack(red / total, green / total, blue / total);
    }

    /**
     * Moves each palette entry to the weighted mean of the bins nearest to it, leaving an entry
     * that no bin chooses in place.
     *
     * @param palette  the entries, updated in place
     * @param weights  the pixel count of each bin
     * @param means    the mean color of each bin
     * @param binCount the number of bins
     */
    private static void refine(int[] palette, long[] weights, double[] means, int binCount) {
        var search = new Palette(palette);
        var totals = new double[palette.length * 4];
        for (var bin = 0; bin < binCount; bin++) {
            var r = means[bin * 3];
            var g = means[bin * 3 + 1];
            var b = means[bin * 3 + 2];
            var index = search.indexOf(pack(r, g, b));
            double weight = weights[bin];
            totals[index * 4] += weight;
            totals[index * 4 + 1] += weight * r;
            totals[index * 4 + 2] += weight * g;
            totals[index * 4 + 3] += weight * b;
        }
        for (var i = 0; i < palette.length; i++) {
            var weight = totals[i * 4];
            if (weight > 0) {
                palette[i] = pack(totals[i * 4 + 1] / weight, totals[i * 4 + 2] / weight, totals[i * 4 + 3] / weight);
            }
        }
    }

    /**
     * Packs rounded channels into an RGB color.
     *
     * @param red   the red channel
     * @param green the green channel
     * @param blue  the blue channel
     * @return the color, packed RGB
     */
    private static int pack(double red, double green, double blue) {
        var r = (int) Math.clamp(Math.round(red), 0, 255);
        var g = (int) Math.clamp(Math.round(green), 0, 255);
        var b = (int) Math.clamp(Math.round(blue), 0, 255);
        return r | g << 8 | b << 16;
    }
}
