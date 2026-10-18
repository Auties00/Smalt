package com.github.auties00.smalt.audio.voice.internal.aec3;

import java.util.Arrays;

/**
 * Implements {@link MatchedFilterKernel} with sequential sums in tap order, which reproduces the
 * reference's scalar core bit for bit.
 *
 * @implNote This implementation ports {@code MatchedFilterCore} of WebRTC's
 * {@code modules/audio_processing/aec3/matched_filter.cc}. The render ring carries a mirror of
 * its start after its end, so each window is read contiguously instead of wrapping the index per
 * tap, which keeps the order of every sum.
 */
final class ScalarMatchedFilterKernel implements MatchedFilterKernel {
    /**
     * Creates the kernel.
     */
    ScalarMatchedFilterKernel() {
    }

    @Override
    public void core(int xStartIndex, float x2SumThreshold, float smoothing, float[] x, float[] y, float[] h, int hOffset,
                     boolean computeAccumulatedError, float[] accumulatedError, float[] result) {
        var ringSize = RenderDelayBuffer.LOW_RATE_SIZE;
        var hSize = MatchedFilter.FILTER_LENGTH;
        if (computeAccumulatedError) {
            Arrays.fill(accumulatedError, 0.0f);
        }
        var errorSum = 0.f;
        var updated = false;
        for (var i = 0; i < y.length; ++i) {
            var x2Sum = 0.f;
            var s = 0.f;
            var yi = y[i];
            if (computeAccumulatedError) {
                for (var k = 0; k < hSize; ++k) {
                    var xv = x[xStartIndex + k];
                    x2Sum += xv * xv;
                    s += h[hOffset + k] * xv;
                    if (((k + 1) & 0b11) == 0) {
                        accumulatedError[k >> 2] += (yi - s) * (yi - s);
                    }
                }
            } else {
                for (var k = 0; k < hSize; ++k) {
                    var xv = x[xStartIndex + k];
                    x2Sum += xv * xv;
                    s += h[hOffset + k] * xv;
                }
            }
            var e = yi - s;
            var saturation = yi >= 32000.f || yi <= -32000.f;
            errorSum += e * e;
            if (x2Sum > x2SumThreshold && !saturation) {
                var alpha = smoothing * e / x2Sum;
                for (var k = 0; k < hSize; ++k) {
                    h[hOffset + k] += alpha * x[xStartIndex + k];
                }
                updated = true;
            }
            xStartIndex = xStartIndex > 0 ? xStartIndex - 1 : ringSize - 1;
        }
        result[0] = errorSum;
        result[1] = updated ? 1 : 0;
    }
}
