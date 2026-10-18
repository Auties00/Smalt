package com.github.auties00.smalt.image.ops;

import java.util.HashMap;

/**
 * Holds the fixed-point filter taps that map one axis of a picture to a new size.
 *
 * <p>Output sample {@code i} is {@code sum(weights[i * stride + k] * source[starts[i] + k])} for
 * {@code k < counts[i]}; the weights of every output sum exactly to {@code 1 << bits}, and the
 * entries from {@code counts[i]} to {@code stride} are zero.
 *
 * <p>The source window of the axis is {@code [offset, offset + length)}, in source samples,
 * given as fractions {@code offsetNumerator / denominator} and {@code lengthNumerator /
 * denominator} so the half-sample offsets of subsampled chroma planes stay exact. Output sample
 * {@code i} is centered on {@code offset + (i + 0.5) * length / size}, and source sample {@code p}
 * on {@code p + 0.5}. Taps that fall outside {@code [0, inputSize)} are folded onto the nearest
 * edge sample (edge replication).
 *
 * @implNote This implementation computes every tap position from exact integers, evaluates the kernel at
 * {@code |distance|}, normalizes with a sum taken pairwise from both ends of the window, and quantizes the cumulative
 * sums from both ends towards the tap nearest the output center, which absorbs the remainder. The taps of the output
 * sample mirrored about the center of the axis are therefore the exact reverse of these taps, so symmetric pictures
 * resize to symmetric pictures, and the remainder that makes the sum exact lands on the largest tap, where its relative
 * error is smallest. The separable structure (stretched kernels, normalized fixed-point taps, clamped output) follows
 * Pillow's {@code libImaging/Resample.c}; the tap placement and quantization are this module's own.
 *
 * @param starts the first source sample of each output sample
 * @param counts the number of taps of each output sample, at least {@code 1}
 * @param weights the fixed-point weights, {@code stride} entries per output sample
 * @param stride the distance between the weight rows of consecutive output samples
 * @param maxTaps the largest entry of {@code counts}
 * @param bits the number of fractional bits of the weights
 */
record ResampleWeights(int[] starts, int[] counts, int[] weights, int stride, int maxTaps, int bits) {
    /**
     * Computes the taps of an axis.
     *
     * @param filter the reconstruction filter
     * @param inputSize the number of source samples; positive
     * @param outputSize the number of output samples; positive
     * @param offsetNumerator the numerator of the window's start; non-negative
     * @param lengthNumerator the numerator of the window's length; positive
     * @param denominator the denominator of both window fractions; positive
     * @param bits the number of fractional bits of the weights, at most {@code 24}
     * @return the taps
     */
    static ResampleWeights compute(ResizeFilter filter, int inputSize, int outputSize, long offsetNumerator,
                                   long lengthNumerator, long denominator, int bits) {
        var one = 1L << bits;
        var starts = new int[outputSize];
        var counts = new int[outputSize];
        if (filter == ResizeFilter.NEAREST) {
            var stride = 1;
            var weights = new int[outputSize * stride];
            for (var i = 0; i < outputSize; i++) {
                var center = 2 * offsetNumerator * outputSize + (2L * i + 1) * lengthNumerator;
                var p = Math.floorDiv(center, 2 * denominator * outputSize);
                starts[i] = (int) Math.clamp(p, 0, inputSize - 1);
                counts[i] = 1;
                weights[i * stride] = (int) one;
            }
            return new ResampleWeights(starts, counts, weights, stride, 1, bits);
        }
        var quantized = new int[outputSize][];
        var nearest = new int[1];
        var tie = new boolean[1];
        var folds = new int[1];
        var interior = new boolean[1];
        // interior taps depend only on the phase of the output center, so they are computed once
        // per phase: for rational scale factors most outputs share a handful of phases
        var cache = filter == ResizeFilter.AREA ? null : new HashMap<Long, int[]>();
        var phaseUnit = 2 * denominator * outputSize;
        var maxTaps = 1;
        for (var i = 0; i < outputSize; i++) {
            int[] taps = null;
            long phase = 0;
            if (cache != null) {
                var center = 2 * offsetNumerator * outputSize + (2L * i + 1) * lengthNumerator;
                phase = Math.floorMod(center, phaseUnit);
                taps = cache.get(phase);
                if (taps != null) {
                    var first = Math.floorDiv(center, phaseUnit) + firstOffset(filter, phase, outputSize, lengthNumerator, denominator);
                    if (first >= 0 && first + taps.length <= inputSize) {
                        starts[i] = (int) first;
                    } else {
                        taps = null;
                    }
                }
            }
            if (taps == null) {
                var raw = filter == ResizeFilter.AREA
                        ? areaTaps(inputSize, outputSize, offsetNumerator, lengthNumerator, denominator, i, starts, folds, nearest, tie)
                        : kernelTaps(filter, inputSize, outputSize, offsetNumerator, lengthNumerator, denominator, i, starts, folds, nearest, tie, interior);
                taps = quantize(raw, nearest[0], tie[0], folds[0], one);
                if (cache != null && interior[0]) {
                    cache.put(phase, taps);
                }
            }
            quantized[i] = taps;
            counts[i] = taps.length;
            maxTaps = Math.max(maxTaps, taps.length);
        }
        var stride = maxTaps;
        var weights = new int[outputSize * stride];
        for (var i = 0; i < outputSize; i++) {
            System.arraycopy(quantized[i], 0, weights, i * stride, quantized[i].length);
        }
        return new ResampleWeights(starts, counts, weights, stride, maxTaps, bits);
    }

    /**
     * Returns the position of the first tap of an interior window relative to the source sample
     * that contains the output center.
     *
     * @param filter the interpolating filter
     * @param phase the output center modulo {@code 2 * denominator * outputSize}, in the distance units of
     * {@link #kernelTaps}
     * @param outputSize the number of output samples
     * @param lengthNumerator the numerator of the window's length
     * @param denominator the denominator of the window fractions
     * @return the offset of the first tap
     */
    private static long firstOffset(ResizeFilter filter, long phase, int outputSize, long lengthNumerator, long denominator) {
        var unit = denominator * outputSize;
        var scaleDenominator = lengthNumerator > unit ? 2 * lengthNumerator : 2 * unit;
        var reach = filter.support() * scaleDenominator;
        var first = Math.floorDiv(phase - reach - unit, 2 * unit) + 1;
        var last = Math.ceilDiv(phase + reach - unit, 2 * unit) - 1;
        return last < first ? Math.floorDiv(phase, 2 * unit) : first;
    }

    /**
     * Computes the unnormalized taps of one output sample of an interpolating filter.
     *
     * <p>Distances are measured in units of {@code 1 / (2 * denominator * outputSize)} source
     * samples, in which every sample center is an integer.
     *
     * @param filter the interpolating reconstruction filter
     * @param inputSize the number of source samples
     * @param outputSize the number of output samples
     * @param offsetNumerator the numerator of the window's start
     * @param lengthNumerator the numerator of the window's length
     * @param denominator the denominator of the window fractions
     * @param i the output sample
     * @param starts receives the first source sample
     * @param folds receives the signed difference between the taps folded onto the left and the right edge
     * @param nearest receives the index, within the taps, of the tap nearest the center
     * @param tie receives whether the center lies exactly between two taps, the second of which follows the nearest one
     * @param interior receives whether no tap was folded, so the taps depend only on the phase of the center
     * @return the taps
     */
    private static double[] kernelTaps(ResizeFilter filter, int inputSize, int outputSize, long offsetNumerator,
                                       long lengthNumerator, long denominator, int i, int[] starts, int[] folds,
                                       int[] nearest, boolean[] tie, boolean[] interior) {
        var unit = denominator * outputSize;
        var center = 2 * offsetNumerator * outputSize + (2L * i + 1) * lengthNumerator;
        var downscale = lengthNumerator > unit;
        var scaleDenominator = downscale ? 2 * lengthNumerator : 2 * unit;
        var reach = filter.support() * scaleDenominator;
        // source sample p is centered at (2p + 1) * unit; keep |(2p + 1) * unit - center| < reach
        var first = Math.floorDiv(center - reach - unit, 2 * unit) + 1;
        var last = Math.ceilDiv(center + reach - unit, 2 * unit) - 1;
        if (last < first) {
            first = last = Math.floorDiv(center, 2 * unit);
        }
        var low = (int) Math.clamp(first, 0, inputSize - 1);
        var high = (int) Math.clamp(last, 0, inputSize - 1);
        var taps = new double[high - low + 1];
        var leftFold = 0.0;
        for (var p = Math.min(-1, last); p >= first; p--) {
            leftFold += value(filter, p, unit, center, scaleDenominator);
        }
        var rightFold = 0.0;
        for (var p = Math.max(inputSize, first); p <= last; p++) {
            rightFold += value(filter, p, unit, center, scaleDenominator);
        }
        for (var p = low; p <= high; p++) {
            if (p >= first && p <= last) {
                taps[p - low] = value(filter, p, unit, center, scaleDenominator);
            }
        }
        taps[0] += leftFold;
        taps[taps.length - 1] += rightFold;
        folds[0] = (int) (Math.max(0, -first) - Math.max(0, last - (inputSize - 1)));
        interior[0] = first >= 0 && last <= inputSize - 1;
        starts[i] = low;
        locateCenter(low, high, unit, center, nearest, tie);
        return taps;
    }

    /**
     * Evaluates the filter at one tap.
     *
     * @param filter the reconstruction filter to evaluate
     * @param p the source sample
     * @param unit half the distance between source sample centers, in distance units
     * @param center the output sample's center, in distance units
     * @param scaleDenominator the distance, in distance units, of one unit of kernel argument
     * @return the kernel value
     */
    private static double value(ResizeFilter filter, long p, long unit, long center, long scaleDenominator) {
        var distance = Math.abs((2 * p + 1) * unit - center);
        return filter.kernel((double) distance / (double) scaleDenominator);
    }

    /**
     * Computes the unnormalized taps of one output sample of the area filter: the exact overlap
     * of the output sample's footprint with each source sample.
     *
     * @param inputSize the number of source samples
     * @param outputSize the number of output samples
     * @param offsetNumerator the numerator of the window's start
     * @param lengthNumerator the numerator of the window's length
     * @param denominator the denominator of the window fractions
     * @param i the output sample
     * @param starts receives the first source sample
     * @param folds receives the signed difference between the taps folded onto the left and the right edge
     * @param nearest receives the index, within the taps, of the tap nearest the center
     * @param tie receives whether the center lies exactly between two taps
     * @return the taps
     */
    private static double[] areaTaps(int inputSize, int outputSize, long offsetNumerator, long lengthNumerator,
                                     long denominator, int i, int[] starts, int[] folds, int[] nearest, boolean[] tie) {
        // positions in units of 1 / (denominator * outputSize) source samples
        var unit = denominator * outputSize;
        var low = offsetNumerator * outputSize + (long) i * lengthNumerator;
        var high = low + lengthNumerator;
        var first = Math.floorDiv(low, unit);
        var last = Math.ceilDiv(high, unit) - 1;
        var lowIndex = (int) Math.clamp(first, 0, inputSize - 1);
        var highIndex = (int) Math.clamp(last, 0, inputSize - 1);
        var taps = new double[highIndex - lowIndex + 1];
        for (var p = first; p <= last; p++) {
            var overlap = Math.min(high, (p + 1) * unit) - Math.max(low, p * unit);
            if (overlap > 0) {
                var index = (int) Math.clamp(p, lowIndex, highIndex) - lowIndex;
                taps[index] += overlap;
            }
        }
        folds[0] = (int) (Math.max(0, -first) - Math.max(0, last - (inputSize - 1)));
        starts[i] = lowIndex;
        locateCenter(lowIndex, highIndex, unit, low + high, nearest, tie);
        return taps;
    }

    /**
     * Finds the tap nearest the output center.
     *
     * @param low the first source sample
     * @param high the last source sample
     * @param unit half the distance between source sample centers, in the units of {@code center}
     * @param center the output center, in units where source sample {@code p} is centered at {@code (2p + 1) * unit}
     * @param nearest receives the index of the nearest tap within the taps
     * @param tie receives whether the center lies exactly between that tap and the next
     */
    private static void locateCenter(int low, int high, long unit, long center, int[] nearest, boolean[] tie) {
        var best = low;
        var bestDistance = Long.MAX_VALUE;
        var tied = false;
        for (var p = low; p <= high; p++) {
            var distance = Math.abs((2L * p + 1) * unit - center);
            if (distance < bestDistance) {
                best = p;
                bestDistance = distance;
                tied = false;
            } else if (distance == bestDistance) {
                tied = true;
            }
        }
        nearest[0] = best - low;
        tie[0] = tied && best < high;
    }

    /**
     * Normalizes and quantizes the taps of one output sample into fixed point.
     *
     * <p>Taps before the nearest tap are quantized from their cumulative sums taken from the
     * left, taps after it from their cumulative sums taken from the right, and the nearest tap
     * (or, when the center lies exactly between two taps, those two taps) takes the remainder,
     * so the weights sum exactly to {@code one}.
     *
     * @param taps the unnormalized taps
     * @param nearest the index of the tap nearest the center
     * @param tie whether the center lies exactly between {@code nearest} and the next tap
     * @param fold the signed difference between the taps folded onto the left and the right edge, which breaks ties in
     * a mirror-consistent way
     * @param one the fixed-point unit
     * @return the fixed-point weights
     */
    private static int[] quantize(double[] taps, int nearest, boolean tie, int fold, long one) {
        var n = taps.length;
        var weights = new int[n];
        var offset = 0;
        var sum = 0.0;
        for (var k = 0; k < n / 2; k++) {
            sum += taps[k] + taps[n - 1 - k];
        }
        if ((n & 1) != 0) {
            sum += taps[n / 2];
        }
        if (!(sum > 0)) {
            weights[nearest] = (int) one;
            return weights;
        }
        var middleEnd = tie ? nearest + 1 : nearest;
        var assigned = 0L;
        var cumulative = 0.0;
        var previous = 0L;
        for (var k = 0; k < nearest; k++) {
            cumulative += taps[k] / sum;
            var rounded = Math.round(cumulative * one);
            weights[offset + k] = (int) (rounded - previous);
            assigned += rounded - previous;
            previous = rounded;
        }
        cumulative = 0.0;
        previous = 0L;
        for (var k = n - 1; k > middleEnd; k--) {
            cumulative += taps[k] / sum;
            var rounded = Math.round(cumulative * one);
            weights[offset + k] = (int) (rounded - previous);
            assigned += rounded - previous;
            previous = rounded;
        }
        var remainder = one - assigned;
        if (!tie) {
            weights[offset + nearest] = (int) remainder;
            return weights;
        }
        var left = taps[nearest];
        var right = taps[nearest + 1];
        long leftWeight;
        if (left == right) {
            leftWeight = remainder >> 1;
            if ((remainder & 1) != 0 && fold < 0) {
                leftWeight++;
            }
        } else {
            leftWeight = Math.round(remainder * (left / (left + right)));
        }
        weights[offset + nearest] = (int) leftWeight;
        weights[offset + nearest + 1] = (int) (remainder - leftWeight);
        return weights;
    }
}
