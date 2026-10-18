package com.github.auties00.smalt.image.ops;

import com.github.auties00.smalt.internal.simd.Simd;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Checks that the Vector API implementation of {@link EffectKernel} produces exactly the output of
 * its scalar reference on randomized rows of every length from 1 to beyond several vectors, so
 * both the vector loops and their scalar tails run.
 */
@DisplayName("effect kernels")
class EffectKernelEquivalenceTest {
    private static final int MAX_LENGTH = 90;

    private final EffectKernel vector = VectorEffectKernel.create();
    private final EffectKernel scalar = new ScalarEffectKernel();

    @BeforeAll
    static void requireVectors() {
        assumeTrue(Simd.ENABLED, "the Vector API is not enabled");
    }

    private static byte[] bytes(SplittableRandom random, int length) {
        var array = new byte[length];
        random.nextBytes(array);
        for (var i = 0; i < length; i++) {
            switch (random.nextInt(5)) {
                case 0 -> array[i] = 0;
                case 1 -> array[i] = (byte) 255;
                default -> {
                }
            }
        }
        return array;
    }

    private static float[] unit(SplittableRandom random, int length) {
        var array = new float[length];
        for (var i = 0; i < length; i++) {
            array[i] = switch (random.nextInt(6)) {
                case 0 -> 0;
                case 1 -> 1;
                case 2 -> 0.5f;
                case 3 -> 0.25f;
                default -> random.nextInt(256) / 255f;
            };
        }
        return array;
    }

    @Test
    @DisplayName("box accumulation and box rows match")
    void box() {
        var random = new SplittableRandom(1);
        for (var length = 1; length <= MAX_LENGTH; length++) {
            var src = bytes(random, 5 * length + 16);
            var expectedAcc = new int[length];
            var actualAcc = new int[length];
            var weight = random.nextInt(40);
            scalar.boxAccumulate(src, 3, weight, expectedAcc, length);
            vector.boxAccumulate(src, 3, weight, actualAcc, length);
            assertArrayEquals(expectedAcc, actualAcc, "accumulate, length " + length);
            var radius = random.nextFloat() * 20;
            var whole = (int) radius;
            var ww = (int) ((float) (1 << 24) / (radius * 2 + 1));
            var fw = ((1 << 24) - (whole * 2 + 1) * ww) / 2;
            var expected = new byte[length + 9];
            var actual = new byte[length + 9];
            scalar.boxRow(src, length, 2 * length, 3 * length, 4 * length, expectedAcc, ww, fw, expected, 2, length);
            vector.boxRow(src, length, 2 * length, 3 * length, 4 * length, actualAcc, ww, fw, actual, 2, length);
            assertArrayEquals(expected, actual, "row, length " + length);
            assertArrayEquals(expectedAcc, actualAcc, "sums, length " + length);
        }
    }

    @Test
    @DisplayName("channel mixing matches for positive and negative matrices")
    void mix() {
        var random = new SplittableRandom(3);
        for (var width = 1; width <= MAX_LENGTH; width++) {
            var src = bytes(random, 4 * width + 12);
            var matrix = new int[12];
            for (var i = 0; i < 12; i++) {
                matrix[i] = i % 4 == 3 ? random.nextInt(-(1 << 22), 1 << 22) : random.nextInt(-(1 << 17), 1 << 17);
            }
            var expected = new byte[4 * width + 8];
            var actual = new byte[4 * width + 8];
            scalar.mixRow(src, 4, width, matrix, expected, 4);
            vector.mixRow(src, 4, width, matrix, actual, 4);
            assertArrayEquals(expected, actual, "width " + width);
        }
    }

    @Test
    @DisplayName("unsharp rows match at every strength and threshold")
    void unsharp() {
        var random = new SplittableRandom(4);
        for (var length = 1; length <= MAX_LENGTH; length++) {
            var original = bytes(random, length + 5);
            var blurred = bytes(random, length + 3);
            var percent = random.nextInt(4) == 0 ? 10_000 : random.nextInt(500);
            var threshold = random.nextInt(4) == 0 ? 0 : random.nextInt(30);
            var expected = new byte[length + 9];
            var actual = new byte[length + 9];
            scalar.unsharpRow(original, 5, blurred, 3, length, percent, threshold, expected, 1);
            vector.unsharpRow(original, 5, blurred, 3, length, percent, threshold, actual, 1);
            assertArrayEquals(expected, actual, "length " + length);
        }
    }

    @Test
    @DisplayName("the 3x3 median network matches and equals a sorted median")
    void median() {
        var random = new SplittableRandom(5);
        for (var length = 1; length <= 3 * MAX_LENGTH; length++) {
            var above = bytes(random, length + 12);
            var row = bytes(random, length + 12);
            var below = bytes(random, length + 12);
            var expected = new byte[length + 2];
            var actual = new byte[length + 2];
            scalar.median9Row(above, row, below, 4, length, expected, 1);
            vector.median9Row(above, row, below, 4, length, actual, 1);
            assertArrayEquals(expected, actual, "length " + length);
            for (var i = 0; i < length; i++) {
                var window = new int[9];
                var n = 0;
                for (var source : new byte[][]{above, row, below}) {
                    for (var dx = -4; dx <= 4; dx += 4) {
                        window[n++] = source[4 + i + dx] & 0xFF;
                    }
                }
                java.util.Arrays.sort(window);
                org.junit.jupiter.api.Assertions.assertEquals(window[4], expected[1 + i] & 0xFF, "sample " + i);
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(BlendMode.class)
    @DisplayName("blending matches for every mode")
    void blend(BlendMode mode) {
        var random = new SplittableRandom(mode.ordinal());
        for (var length = 1; length <= MAX_LENGTH; length++) {
            var planes = new float[8][];
            for (var i = 0; i < 8; i++) {
                planes[i] = unit(random, length);
            }
            var expected = new float[8][];
            var actual = new float[8][];
            for (var i = 0; i < 8; i++) {
                expected[i] = planes[i].clone();
                actual[i] = planes[i].clone();
            }
            var opacity = random.nextInt(3) == 0 ? 1f : random.nextFloat();
            scalar.blend(mode, expected[0], expected[1], expected[2], expected[3], expected[4], expected[5], expected[6],
                    expected[7], opacity, length);
            vector.blend(mode, actual[0], actual[1], actual[2], actual[3], actual[4], actual[5], actual[6], actual[7],
                    opacity, length);
            for (var i = 0; i < 8; i++) {
                assertArrayEquals(expected[i], actual[i], "plane " + i + ", length " + length);
            }
        }
    }
}
