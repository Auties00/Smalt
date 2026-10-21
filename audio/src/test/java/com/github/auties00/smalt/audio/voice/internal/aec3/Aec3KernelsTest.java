package com.github.auties00.smalt.audio.voice.internal.aec3;

import com.github.auties00.smalt.testkit.Skip;
import com.github.auties00.smalt.internal.simd.Simd;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Equivalence of the AEC3 kernels: both widths of the Vector API matched filter against its
 * scalar twin, and both widths of the Vector API adaptive filter against the scalar reference,
 * bit for bit on random data, ring positions that wrap and both matched filter modes.
 */
@DisplayName("AEC3 kernels")
class Aec3KernelsTest {
    private static final int CASES = 300;

    private static float[] gaussian(SplittableRandom random, int length, double scale) {
        var values = new float[length];
        for (var i = 0; i < length; i++) {
            values[i] = (float) (random.nextGaussian() * scale);
        }
        return values;
    }

    @Nested
    @DisplayName("matched filter")
    class MatchedFilterKernels {
        @ParameterizedTest(name = "wide={0}")
        @ValueSource(booleans = {true, false})
        @DisplayName("the vector kernels equal the scalar twin bit for bit in both modes")
        void vectorEqualsTwin(boolean wide) {
            Skip.unlessVector(Simd.ENABLED);
            var vector = VectorAec3Kernels.matchedFilter(wide);
            var twin = new LaneMatchedFilterKernel();
            var random = new SplittableRandom(wide ? 7 : 11);
            for (var c = 0; c < CASES; c++) {
                var size = RenderDelayBuffer.LOW_RATE_SIZE;
                var ring = gaussian(random, size + MatchedFilter.FILTER_LENGTH, 1000);
                System.arraycopy(ring, 0, ring, size, MatchedFilter.FILTER_LENGTH);
                var y = gaussian(random, 16, 800);
                if (c % 10 == 3) {
                    y[5] = 32500;
                }
                var h = gaussian(random, 3 * MatchedFilter.FILTER_LENGTH, 0.01);
                var hTwin = h.clone();
                var start = c % 4 == 0 ? random.nextInt(8) : size - 1 - random.nextInt(32);
                var offset = MatchedFilter.FILTER_LENGTH * random.nextInt(3);
                var accumulate = c % 2 == 1;
                var threshold = c % 7 == 0 ? Float.MAX_VALUE : 1e-6f;
                var errorVector = new float[128];
                var errorTwin = new float[128];
                var resultVector = new float[2];
                var resultTwin = new float[2];
                vector.core(start, threshold, 0.7f, ring, y, h, offset, accumulate, errorVector, resultVector);
                twin.core(start, threshold, 0.7f, ring, y, hTwin, offset, accumulate, errorTwin, resultTwin);
                assertArrayEquals(hTwin, h, "taps, case " + c);
                assertArrayEquals(resultTwin, resultVector, "error sum, case " + c);
                if (accumulate) {
                    assertArrayEquals(errorTwin, errorVector, "accumulated error, case " + c);
                }
            }
        }

        @ParameterizedTest(name = "accumulate={0}")
        @ValueSource(booleans = {true, false})
        @DisplayName("the twin agrees with the scalar reference to float rounding")
        void twinCloseToReference(boolean accumulate) {
            var reference = new ScalarMatchedFilterKernel();
            var twin = new LaneMatchedFilterKernel();
            var random = new SplittableRandom(accumulate ? 3 : 5);
            var size = RenderDelayBuffer.LOW_RATE_SIZE;
            var ring = gaussian(random, size + MatchedFilter.FILTER_LENGTH, 1000);
            System.arraycopy(ring, 0, ring, size, MatchedFilter.FILTER_LENGTH);
            var y = gaussian(random, 16, 800);
            var h = gaussian(random, MatchedFilter.FILTER_LENGTH, 0.01);
            var hTwin = h.clone();
            var resultReference = new float[2];
            var resultTwin = new float[2];
            reference.core(100, 1e-6f, 0.7f, ring, y, h, 0, accumulate, new float[128], resultReference);
            twin.core(100, 1e-6f, 0.7f, ring, y, hTwin, 0, accumulate, new float[128], resultTwin);
            assertEquals(resultReference[1], resultTwin[1]);
            assertEquals(resultReference[0], resultTwin[0], Math.abs(resultReference[0]) * 1e-3);
            for (var k = 0; k < h.length; k++) {
                assertEquals(h[k], hTwin[k], 1e-3 * (Math.abs(h[k]) + 1e-3), "tap " + k);
            }
        }
    }

    @Nested
    @DisplayName("adaptive filter")
    class AdaptiveFilterKernels {
        @ParameterizedTest(name = "wide={0}")
        @ValueSource(booleans = {true, false})
        @DisplayName("the vector kernels equal the scalar reference bit for bit")
        void vectorEqualsScalar(boolean wide) {
            Skip.unlessVector(Simd.ENABLED);
            var vector = VectorAec3Kernels.adaptiveFilter(wide);
            var scalar = new ScalarAdaptiveFilterKernel();
            var random = new SplittableRandom(wide ? 13 : 17);
            var bins = Aec3.FFT_LENGTH_BY_2_PLUS_1;
            for (var c = 0; c < CASES; c++) {
                var xRe = gaussian(random, RenderBuffer.SIZE * bins, 1000);
                var xIm = gaussian(random, RenderBuffer.SIZE * bins, 1000);
                var partitions = 12 + random.nextInt(2);
                var hRe = gaussian(random, 13 * bins, 0.1);
                var hIm = gaussian(random, 13 * bins, 0.1);
                var gRe = gaussian(random, bins, 1e-6);
                var gIm = gaussian(random, bins, 1e-6);
                var position = c % 3 == 0 ? RenderBuffer.SIZE - 1 - random.nextInt(12) : random.nextInt(RenderBuffer.SIZE);
                float[] sReV = new float[bins], sImV = new float[bins], sReS = new float[bins], sImS = new float[bins];
                vector.apply(xRe, xIm, position, partitions, hRe, hIm, sReV, sImV);
                scalar.apply(xRe, xIm, position, partitions, hRe, hIm, sReS, sImS);
                assertArrayEquals(sReS, sReV, "apply re, case " + c);
                assertArrayEquals(sImS, sImV, "apply im, case " + c);
                var hReV = hRe.clone();
                var hImV = hIm.clone();
                vector.adapt(xRe, xIm, position, partitions, gRe, gIm, hReV, hImV);
                scalar.adapt(xRe, xIm, position, partitions, gRe, gIm, hRe, hIm);
                assertArrayEquals(hRe, hReV, "adapt re, case " + c);
                assertArrayEquals(hIm, hImV, "adapt im, case " + c);
                float[] h2V = new float[13 * bins], h2S = new float[13 * bins];
                vector.frequencyResponse(partitions, hRe, hIm, h2V);
                scalar.frequencyResponse(partitions, hRe, hIm, h2S);
                assertArrayEquals(h2S, h2V, "frequency response, case " + c);
                float[] erlV = new float[bins], erlS = new float[bins];
                vector.erl(partitions, h2S, erlV);
                scalar.erl(partitions, h2S, erlS);
                assertArrayEquals(erlS, erlV, "erl, case " + c);
            }
        }
    }
}
