package com.github.auties00.smalt.image.ops;

import com.github.auties00.smalt.internal.simd.Simd;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Checks that the Vector API implementation of {@link WarpKernel} produces exactly the output of
 * its scalar reference on randomized runs of every length from 1 to beyond several vectors, with
 * random taps, weights (including the negative bicubic lobes) and sums.
 */
@DisplayName("warp kernels")
class WarpKernelEquivalenceTest {
    private static final int STRIDE = 40;

    private final WarpKernel vector = VectorWarpKernel.create();
    private final WarpKernel scalar = new ScalarWarpKernel();

    @BeforeAll
    static void requireVectors() {
        assumeTrue(Simd.ENABLED, "the Vector API is not enabled");
    }

    private static float[] weights(SplittableRandom random, int count) {
        var array = new float[count];
        for (var i = 0; i < count; i++) {
            array[i] = random.nextInt(4) == 0 ? (random.nextBoolean() ? 0 : 1) : (float) random.nextDouble(-0.2, 1.1);
        }
        return array;
    }

    @Test
    @DisplayName("accumulating two and four taps and resolving match")
    void matches() {
        var random = new SplittableRandom(9);
        var source = new int[STRIDE * 12];
        for (var i = 0; i < source.length; i++) {
            source[i] = random.nextInt();
            if (random.nextInt(4) == 0) {
                source[i] &= 0x00FFFFFF;
            }
        }
        for (var count = 1; count <= 70; count++) {
            var base = new int[count];
            for (var i = 0; i < count; i++) {
                base[i] = random.nextInt(STRIDE * 8);
            }
            var w = new float[5][];
            for (var k = 0; k < 5; k++) {
                w[k] = weights(random, count);
            }
            var expected = new float[4][count];
            var actual = new float[4][count];
            for (var k = 0; k < 4; k++) {
                for (var i = 0; i < count; i++) {
                    expected[k][i] = actual[k][i] = (float) random.nextDouble(0, 50);
                }
            }
            scalar.accumulate2(source, STRIDE, base, w[0], w[1], w[4], expected[0], expected[1], expected[2], expected[3], count);
            vector.accumulate2(source, STRIDE, base, w[0], w[1], w[4], actual[0], actual[1], actual[2], actual[3], count);
            scalar.accumulate4(source, 2 * STRIDE, base, w[0], w[1], w[2], w[3], w[4], expected[0], expected[1], expected[2],
                    expected[3], count);
            vector.accumulate4(source, 2 * STRIDE, base, w[0], w[1], w[2], w[3], w[4], actual[0], actual[1], actual[2],
                    actual[3], count);
            for (var k = 0; k < 4; k++) {
                assertArrayEquals(expected[k], actual[k], "sums " + k + ", count " + count);
            }
            if (count % 3 == 0) {
                expected[3][0] = 0;
                actual[3][0] = 0;
            }
            var expectedPixels = new byte[4 * count + 8];
            var actualPixels = new byte[4 * count + 8];
            scalar.resolve(expected[0], expected[1], expected[2], expected[3], count, expectedPixels, 4);
            vector.resolve(actual[0], actual[1], actual[2], actual[3], count, actualPixels, 4);
            assertArrayEquals(expectedPixels, actualPixels, "pixels, count " + count);
        }
    }
}
