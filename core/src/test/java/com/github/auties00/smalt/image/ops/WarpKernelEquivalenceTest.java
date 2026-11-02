package com.github.auties00.smalt.image.ops;

import com.github.auties00.smalt.testkit.Skip;
import com.github.auties00.smalt.internal.simd.Simd;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * Checks that the Vector API implementation of {@link WarpKernel} produces exactly the output of
 * its scalar reference on randomized runs of every length from 1 to beyond several vectors, with
 * random taps, weights (including the negative bicubic lobes) and sums.
 */
@DisplayName("warp kernels")
class WarpKernelEquivalenceTest {
    private final WarpKernel vector = VectorWarpKernel.create();
    private final WarpKernel scalar = new ScalarWarpKernel();

    @BeforeAll
    static void requireVectors() {
        Skip.unlessVector(Simd.ENABLED);
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
        for (var count = 1; count <= 70; count++) {
            var taps = new int[4][count];
            for (var tap : taps) {
                for (var i = 0; i < count; i++) {
                    tap[i] = random.nextInt();
                    if (random.nextInt(4) == 0) {
                        tap[i] &= 0x00FFFFFF;
                    }
                }
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
            scalar.accumulate2(taps[0], taps[1], w[0], w[1], w[4], expected[0], expected[1], expected[2], expected[3],
                    count);
            vector.accumulate2(taps[0], taps[1], w[0], w[1], w[4], actual[0], actual[1], actual[2], actual[3], count);
            scalar.accumulate4(taps[0], taps[1], taps[2], taps[3], w[0], w[1], w[2], w[3], w[4], expected[0], expected[1],
                    expected[2], expected[3], count);
            vector.accumulate4(taps[0], taps[1], taps[2], taps[3], w[0], w[1], w[2], w[3], w[4], actual[0], actual[1],
                    actual[2], actual[3], count);
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
