package com.github.auties00.smalt.video.av1.internal.encoder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Covers the variances of the high bit depth kernels, whose sums are rounded to the 8-bit scale
 * separately, against the libaom definition: the rounded sum of squares less the square of the
 * rounded sum over the samples, and zero when that is negative.
 */
class HighBitDepthEncDspTest {
    static long round(long value, int bits) {
        return (value + (1L << (bits - 1))) >> bits;
    }

    @ParameterizedTest(name = "{0}-bit {1}x{2}")
    @CsvSource({"10, 8, 8", "12, 8, 8", "10, 4, 4", "12, 4, 4", "10, 16, 16", "12, 16, 8"})
    @DisplayName("the variance of nearly flat blocks is the rounded sums' variance, never negative")
    void variance(int bitDepth, int w, int h) {
        var dsp = EncDsp.select(bitDepth);
        var shift = bitDepth - 8;
        var log2n = Integer.numberOfTrailingZeros(w) + Integer.numberOfTrailingZeros(h);
        var random = new SplittableRandom(bitDepth * 31L + w);
        var a = new short[w * h];
        var b = new short[w * h];
        var negatives = 0;
        for (var trial = 0; trial < 2000; trial++) {
            // a flat offset with one-step noise, as a fade or a brightness change gives
            var offset = random.nextInt(1 << (shift + 6));
            long sse = 0;
            long sum = 0;
            for (var i = 0; i < a.length; i++) {
                b[i] = (short) random.nextInt(1 << (bitDepth - 1));
                var d = offset + random.nextInt(3) - 1;
                a[i] = (short) (b[i] + d);
                sse += (long) d * d;
                sum += d;
            }
            var expected = round(sse, 2 * shift) - ((round(sum, shift) * round(sum, shift)) >> log2n);
            if (expected < 0) {
                negatives++;
            }
            var packed = dsp.sseSum(a, 0, w, b, 0, w, w, h);
            assertEquals(Math.max(0, expected), EncDsp.variance(packed, log2n) & 0xFFFFFFFFL, "trial " + trial);
        }
        // the cases the clamp guards against do occur
        if (bitDepth == 12 || w * h >= 64) {
            org.junit.jupiter.api.Assertions.assertTrue(negatives > 0, "no rounded sums with a negative variance");
        }
    }
}
