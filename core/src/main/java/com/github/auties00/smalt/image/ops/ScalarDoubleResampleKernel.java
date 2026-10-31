package com.github.auties00.smalt.image.ops;

import java.util.Arrays;

/**
 * Implements {@link DoubleResampleKernel} with plain Java arithmetic.
 *
 * <p>This is the reference implementation: the vector implementation must produce identical
 * values, and delegates to the range methods of this class for the values its vector loops do
 * not cover.
 */
final class ScalarDoubleResampleKernel implements DoubleResampleKernel {
    /**
     * Constructs the kernel, which filters one row per block.
     */
    ScalarDoubleResampleKernel() {
    }

    @Override
    public int lanes() {
        return 1;
    }

    @Override
    public void vertical(double[] src, int[] offsets, double[] weights, int wOff, int taps, int length, double[] dst,
                         boolean accumulate) {
        if (!accumulate) {
            Arrays.fill(dst, 0, length, 0);
        }
        verticalRange(src, offsets, weights, wOff, taps, dst, 0, length);
    }

    /**
     * Adds the taps of the values {@code [from, to)} of rows to a destination row.
     *
     * @param src the source rows
     * @param offsets the index of the first value of each tap row
     * @param weights the tap weights
     * @param wOff the index of the first weight
     * @param taps the number of tap rows
     * @param dst the destination row, holding the sums to add to
     * @param from the first value
     * @param to the value after the last one
     */
    void verticalRange(double[] src, int[] offsets, double[] weights, int wOff, int taps, double[] dst, int from,
                       int to) {
        for (var k = 0; k < taps; k++) {
            var weight = weights[wOff + k];
            var offset = offsets[k];
            for (var x = from; x < to; x++) {
                dst[x] += weight * src[offset + x];
            }
        }
    }

    @Override
    public void horizontal(double[] src, int lanes, int[] starts, int[] counts, double[] weights, int stride, int count,
                           double[] dst) {
        for (var i = 0; i < count; i++) {
            var base = starts[i] * lanes;
            var w = i * stride;
            var taps = counts[i];
            for (var r = 0; r < lanes; r++) {
                var sum = 0.0;
                for (var k = 0; k < taps; k++) {
                    sum += weights[w + k] * src[base + k * lanes + r];
                }
                dst[i * lanes + r] = sum;
            }
        }
    }
}
