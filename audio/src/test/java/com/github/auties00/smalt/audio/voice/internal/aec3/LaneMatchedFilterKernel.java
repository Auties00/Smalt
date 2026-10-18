package com.github.auties00.smalt.audio.voice.internal.aec3;

import java.util.Arrays;

/**
 * Implements {@link MatchedFilterKernel} in plain Java with the summation order of the Vector API
 * kernels, so that its results equal theirs bit for bit at both vector widths.
 *
 * <p>The filter output and the render energy are accumulated in 16 fused multiply-add chains by
 * tap index modulo 16; chains {@code c} and {@code c + 8} are added, then the eight sums are
 * combined pairwise, {@code ((c0 + c1) + (c2 + c3)) + ((c4 + c5) + (c6 + c7))}. With the
 * accumulated error, the output is built from the 128 four-tap group sums
 * {@code (p0 + p1) + (p2 + p3)} of the plain products as running sums within four blocks of 32
 * groups, each block offset by the totals of the previous blocks added in order; the squared error
 * of every group's offset running sum is accumulated, and the output is the last block's offset
 * plus its total. The adaptation is {@code h[k] = fma(x[k], alpha, h[k])}. The results differ from
 * the reference ({@link ScalarMatchedFilterKernel}) only by float rounding.
 *
 * <p>The kernel holds no state, so one instance serves every filter of every thread.
 */
final class LaneMatchedFilterKernel implements MatchedFilterKernel {
    /**
     * The number of four-tap groups in a block of the accumulated error.
     */
    private static final int BLOCK_GROUPS = 32;

    /**
     * Creates the kernel.
     */
    LaneMatchedFilterKernel() {
    }

    @Override
    public void core(int xStartIndex, float x2SumThreshold, float smoothing, float[] x, float[] y, float[] h, int hOffset,
                     boolean computeAccumulatedError, float[] accumulatedError, float[] result) {
        if (computeAccumulatedError) {
            Arrays.fill(accumulatedError, 0.0f);
        }
        var errorSum = 0.f;
        var updated = false;
        for (var i = 0; i < y.length; ++i) {
            var yi = y[i];
            var x2Sum = energy(x, xStartIndex);
            var s = computeAccumulatedError
                    ? accumulate(yi, x, xStartIndex, h, hOffset, accumulatedError)
                    : output(x, xStartIndex, h, hOffset);
            var e = yi - s;
            var saturation = yi >= 32000.f || yi <= -32000.f;
            errorSum += e * e;
            if (x2Sum > x2SumThreshold && !saturation) {
                var alpha = smoothing * e / x2Sum;
                for (var k = 0; k < MatchedFilter.FILTER_LENGTH; ++k) {
                    h[hOffset + k] = Math.fma(x[xStartIndex + k], alpha, h[hOffset + k]);
                }
                updated = true;
            }
            xStartIndex = xStartIndex > 0 ? xStartIndex - 1 : RenderDelayBuffer.LOW_RATE_SIZE - 1;
        }
        result[0] = errorSum;
        result[1] = updated ? 1 : 0;
    }

    /**
     * Returns the render energy of the taps in the chained order.
     *
     * @param x     the render ring
     * @param start the ring index of the first tap
     * @return the energy
     */
    private static float energy(float[] x, int start) {
        float c0 = 0, c1 = 0, c2 = 0, c3 = 0, c4 = 0, c5 = 0, c6 = 0, c7 = 0;
        float c8 = 0, c9 = 0, c10 = 0, c11 = 0, c12 = 0, c13 = 0, c14 = 0, c15 = 0;
        for (var k = start; k < start + MatchedFilter.FILTER_LENGTH; k += 16) {
            c0 = Math.fma(x[k], x[k], c0);
            c1 = Math.fma(x[k + 1], x[k + 1], c1);
            c2 = Math.fma(x[k + 2], x[k + 2], c2);
            c3 = Math.fma(x[k + 3], x[k + 3], c3);
            c4 = Math.fma(x[k + 4], x[k + 4], c4);
            c5 = Math.fma(x[k + 5], x[k + 5], c5);
            c6 = Math.fma(x[k + 6], x[k + 6], c6);
            c7 = Math.fma(x[k + 7], x[k + 7], c7);
            c8 = Math.fma(x[k + 8], x[k + 8], c8);
            c9 = Math.fma(x[k + 9], x[k + 9], c9);
            c10 = Math.fma(x[k + 10], x[k + 10], c10);
            c11 = Math.fma(x[k + 11], x[k + 11], c11);
            c12 = Math.fma(x[k + 12], x[k + 12], c12);
            c13 = Math.fma(x[k + 13], x[k + 13], c13);
            c14 = Math.fma(x[k + 14], x[k + 14], c14);
            c15 = Math.fma(x[k + 15], x[k + 15], c15);
        }
        return (((c0 + c8) + (c1 + c9)) + ((c2 + c10) + (c3 + c11)))
                + (((c4 + c12) + (c5 + c13)) + ((c6 + c14) + (c7 + c15)));
    }

    /**
     * Returns the filter output in the chained order.
     *
     * @param x       the render ring
     * @param start   the ring index of the first tap
     * @param h       the filter bank taps
     * @param hOffset the index of the filter's first tap
     * @return the output
     */
    private static float output(float[] x, int start, float[] h, int hOffset) {
        float c0 = 0, c1 = 0, c2 = 0, c3 = 0, c4 = 0, c5 = 0, c6 = 0, c7 = 0;
        float c8 = 0, c9 = 0, c10 = 0, c11 = 0, c12 = 0, c13 = 0, c14 = 0, c15 = 0;
        for (var k = 0; k < MatchedFilter.FILTER_LENGTH; k += 16) {
            var xk = start + k;
            var hk = hOffset + k;
            c0 = Math.fma(h[hk], x[xk], c0);
            c1 = Math.fma(h[hk + 1], x[xk + 1], c1);
            c2 = Math.fma(h[hk + 2], x[xk + 2], c2);
            c3 = Math.fma(h[hk + 3], x[xk + 3], c3);
            c4 = Math.fma(h[hk + 4], x[xk + 4], c4);
            c5 = Math.fma(h[hk + 5], x[xk + 5], c5);
            c6 = Math.fma(h[hk + 6], x[xk + 6], c6);
            c7 = Math.fma(h[hk + 7], x[xk + 7], c7);
            c8 = Math.fma(h[hk + 8], x[xk + 8], c8);
            c9 = Math.fma(h[hk + 9], x[xk + 9], c9);
            c10 = Math.fma(h[hk + 10], x[xk + 10], c10);
            c11 = Math.fma(h[hk + 11], x[xk + 11], c11);
            c12 = Math.fma(h[hk + 12], x[xk + 12], c12);
            c13 = Math.fma(h[hk + 13], x[xk + 13], c13);
            c14 = Math.fma(h[hk + 14], x[xk + 14], c14);
            c15 = Math.fma(h[hk + 15], x[xk + 15], c15);
        }
        return (((c0 + c8) + (c1 + c9)) + ((c2 + c10) + (c3 + c11)))
                + (((c4 + c12) + (c5 + c13)) + ((c6 + c14) + (c7 + c15)));
    }

    /**
     * Returns the filter output built from the four-tap group sums and adds the squared error of
     * every group's partial output to the accumulated error.
     *
     * @param yi               the capture sample
     * @param x                the render ring
     * @param start            the ring index of the first tap
     * @param h                the filter bank taps
     * @param hOffset          the index of the filter's first tap
     * @param accumulatedError the accumulated error of each group, updated
     * @return the output
     */
    private static float accumulate(float yi, float[] x, int start, float[] h, int hOffset, float[] accumulatedError) {
        var offset = 0.f;
        var group = 0;
        var running = 0.f;
        for (var k = 0; k < MatchedFilter.FILTER_LENGTH; k += 4, group++) {
            var xk = start + k;
            var hk = hOffset + k;
            var p0 = h[hk] * x[xk];
            var p1 = h[hk + 1] * x[xk + 1];
            var p2 = h[hk + 2] * x[xk + 2];
            var p3 = h[hk + 3] * x[xk + 3];
            var sum = (p0 + p1) + (p2 + p3);
            if (group % BLOCK_GROUPS == 0) {
                if (group > 0) {
                    offset = group == BLOCK_GROUPS ? running : offset + running;
                }
                running = sum;
            } else {
                running = running + sum;
            }
            var d = yi - (offset + running);
            accumulatedError[group] += d * d;
        }
        return offset + running;
    }
}
