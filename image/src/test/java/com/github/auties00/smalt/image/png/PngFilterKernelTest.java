package com.github.auties00.smalt.image.png;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks the PNG filter kernels: the scalar reference against hand-computed rows and the
 * specification's predictor, filter/unfilter round trips, and the vector kernel against the
 * scalar one on randomized rows of every legal filter distance and many lengths and offsets.
 */
class PngFilterKernelTest {
    private static final PngFilterKernel SCALAR = new ScalarPngFilterKernel();
    private static final PngFilterKernel VECTOR = new VectorPngFilterKernel();
    private static final int[] DISTANCES = {1, 2, 3, 4, 6, 8};

    private static int specPaeth(int a, int b, int c) {
        var p = a + b - c;
        var pa = Math.abs(p - a);
        var pb = Math.abs(p - b);
        var pc = Math.abs(p - c);
        if (pa <= pb && pa <= pc) {
            return a;
        }
        return pb <= pc ? b : c;
    }

    @Nested
    @DisplayName("scalar reference")
    class Reference {
        @Test
        @DisplayName("matches the PNG 3rd edition 9.4 Paeth predictor for all 2^24 inputs")
        void paethExhaustive() {
            for (var a = 0; a < 256; a++) {
                for (var b = 0; b < 256; b++) {
                    for (var c = 0; c < 256; c++) {
                        if (ScalarPngFilterKernel.paeth(a, b, c) != specPaeth(a, b, c)) {
                            fail("paeth(" + a + "," + b + "," + c + ")");
                        }
                    }
                }
            }
        }

        @Test
        @DisplayName("reconstructs hand-computed rows for every filter type")
        void handComputed() {
            var prior = new byte[]{10, 20, 30, 40, (byte) 250, 5};
            var raw = new byte[]{1, 2, 3, 4, 5, 6};
            var out = new byte[6];
            SCALAR.unfilterSub(raw, 0, out, 0, 6, 2);
            assertArrayEquals(new byte[]{1, 2, 4, 6, 9, 12}, out);
            SCALAR.unfilterUp(raw, 0, out, 0, prior, 0, 6);
            assertArrayEquals(new byte[]{11, 22, 33, 44, (byte) 255, 11}, out);
            SCALAR.unfilterAverage(raw, 0, out, 0, prior, 0, 6, 2);
            // x0 = 1 + 10/2 = 6, x1 = 2 + 20/2 = 12, x2 = 3 + (6 + 30)/2 = 21, x3 = 4 + (12 + 40)/2 = 30,
            // x4 = 5 + (21 + 250)/2 = 140, x5 = 6 + (30 + 5)/2 = 23
            assertArrayEquals(new byte[]{6, 12, 21, 30, (byte) 140, 23}, out);
            SCALAR.unfilterPaeth(raw, 0, out, 0, prior, 0, 6, 2);
            // x0 = 1 + b(10) = 11, x1 = 2 + 20 = 22; x2: a=11 b=30 c=10 -> p=31 -> b=30 -> 33;
            // x3: a=22 b=40 c=20 -> p=42 -> b -> 44; x4: a=33 b=250 c=30 -> p=253 -> b -> 255;
            // x5: a=44 b=5 c=40 -> p=9, pa=35 pb=4 pc=31 -> b=5 -> 11
            assertArrayEquals(new byte[]{11, 22, 33, 44, (byte) 255, 11}, out);
        }

        @Test
        @DisplayName("inverts every forward filter")
        void roundTrip() {
            var random = new Random(7);
            for (var distance : DISTANCES) {
                for (var trial = 0; trial < 50; trial++) {
                    var length = distance * (1 + random.nextInt(40));
                    var row = bytes(random, length);
                    var prior = bytes(random, length);
                    var filtered = new byte[length + 1];
                    var out = new byte[length];
                    SCALAR.filterSub(row, 0, length, distance, filtered, 1);
                    SCALAR.unfilterSub(filtered, 1, out, 0, length, distance);
                    assertArrayEquals(row, out, "sub");
                    SCALAR.filterUp(row, 0, prior, 0, length, filtered, 1);
                    SCALAR.unfilterUp(filtered, 1, out, 0, prior, 0, length);
                    assertArrayEquals(row, out, "up");
                    SCALAR.filterAverage(row, 0, prior, 0, length, distance, filtered, 1);
                    SCALAR.unfilterAverage(filtered, 1, out, 0, prior, 0, length, distance);
                    assertArrayEquals(row, out, "average");
                    SCALAR.filterPaeth(row, 0, prior, 0, length, distance, filtered, 1);
                    SCALAR.unfilterPaeth(filtered, 1, out, 0, prior, 0, length, distance);
                    assertArrayEquals(row, out, "paeth");
                }
            }
        }

        @Test
        @DisplayName("computes the filter cost as the sum of absolute signed bytes")
        void cost() {
            var row = new byte[]{0, 1, -1, 127, -128, (byte) 200};
            var out = new byte[7];
            // |0| + |1| + |-1| + |127| + |-128| + |-56|
            assertEquals(0 + 1 + 1 + 127 + 128 + 56, SCALAR.filterNone(row, 0, row.length, out, 1));
        }
    }

    @Nested
    @DisplayName("vector kernel")
    class VectorEquivalence {
        @ParameterizedTest(name = "distance {0}")
        @ValueSource(ints = {1, 2, 3, 4, 6, 8})
        @DisplayName("reconstructs exactly like the scalar kernel")
        void unfilter(int distance) {
            var random = new Random(1000 + distance);
            for (var trial = 0; trial < 300; trial++) {
                var length = distance * random.nextInt(1, 200);
                var rawOffset = random.nextInt(5);
                var outOffset = random.nextInt(5);
                var priorOffset = random.nextInt(5);
                var raw = bytes(random, rawOffset + length + random.nextInt(4));
                var prior = bytes(random, priorOffset + length + random.nextInt(4));
                for (var type = 1; type <= 4; type++) {
                    var expected = bytes(new Random(trial), outOffset + length + 3);
                    var actual = expected.clone();
                    apply(SCALAR, type, raw, rawOffset, expected, outOffset, prior, priorOffset, length, distance);
                    apply(VECTOR, type, raw, rawOffset, actual, outOffset, prior, priorOffset, length, distance);
                    assertArrayEquals(expected, actual, "type " + type + " length " + length);
                }
            }
        }

        @ParameterizedTest(name = "distance {0}")
        @ValueSource(ints = {1, 2, 3, 4, 6, 8})
        @DisplayName("filters exactly like the scalar kernel, with equal costs")
        void filter(int distance) {
            var random = new Random(2000 + distance);
            for (var trial = 0; trial < 300; trial++) {
                var length = distance * random.nextInt(1, 1200);
                var rowOffset = random.nextInt(5);
                var priorOffset = random.nextInt(5);
                var row = bytes(random, rowOffset + length);
                var prior = bytes(random, priorOffset + length);
                for (var type = 0; type <= 4; type++) {
                    var expected = new byte[length + 1];
                    var actual = new byte[length + 1];
                    var expectedCost = forward(SCALAR, type, row, rowOffset, prior, priorOffset, length, distance, expected);
                    var actualCost = forward(VECTOR, type, row, rowOffset, prior, priorOffset, length, distance, actual);
                    assertArrayEquals(expected, actual, "type " + type);
                    assertEquals(expectedCost, actualCost, "cost of type " + type);
                }
            }
        }

        @ParameterizedTest(name = "distance {0}")
        @ValueSource(ints = {1, 2, 3, 4, 6, 8})
        @DisplayName("reconstructs scanline pairs exactly like two scalar reconstructions")
        void pairs(int distance) {
            var random = new Random(4000 + distance);
            for (var trial = 0; trial < 300; trial++) {
                var length = distance * random.nextInt(1, 200);
                var raw = bytes(random, 3 + 2 * length + random.nextInt(4));
                var prior = bytes(random, 2 + length);
                for (var type = 3; type <= 4; type++) {
                    var expectedFirst = bytes(new Random(trial), length + 5);
                    var expectedSecond = bytes(new Random(trial + 1), length + 5);
                    var actualFirst = expectedFirst.clone();
                    var actualSecond = expectedSecond.clone();
                    apply(SCALAR, type, raw, 1, expectedFirst, 2, prior, 2, length, distance);
                    apply(SCALAR, type, raw, 2 + length, expectedSecond, 1, expectedFirst, 2, length, distance);
                    if (type == 3) {
                        VECTOR.unfilterAveragePair(raw, 1, 2 + length, actualFirst, 2, actualSecond, 1, prior, 2, length, distance);
                    } else {
                        VECTOR.unfilterPaethPair(raw, 1, 2 + length, actualFirst, 2, actualSecond, 1, prior, 2, length, distance);
                    }
                    assertArrayEquals(expectedFirst, actualFirst, "first, type " + type + " length " + length);
                    assertArrayEquals(expectedSecond, actualSecond, "second, type " + type + " length " + length);
                }
            }
        }

        @Test
        @DisplayName("expands RGB to RGBA exactly like the scalar kernel")
        void expandRgb() {
            var random = new Random(3000);
            for (var count = 0; count < 300; count++) {
                var sourceOffset = random.nextInt(4);
                var targetOffset = random.nextInt(4);
                var source = bytes(random, sourceOffset + count * 3);
                var expected = bytes(new Random(count), targetOffset + count * 4 + 2);
                var actual = expected.clone();
                SCALAR.expandRgb(source, sourceOffset, expected, targetOffset, count);
                VECTOR.expandRgb(source, sourceOffset, actual, targetOffset, count);
                assertArrayEquals(expected, actual, "count " + count);
            }
        }

        @Test
        @DisplayName("sums costs of long rows without 16-bit overflow")
        void longRowCost() {
            var row = new byte[70_000];
            java.util.Arrays.fill(row, (byte) -128);
            var out = new byte[70_001];
            assertEquals(128 * 70_000, VECTOR.filterNone(row, 0, row.length, out, 1));
        }
    }

    private static void apply(PngFilterKernel kernel, int type, byte[] raw, int rawOffset, byte[] out, int outOffset,
                              byte[] prior, int priorOffset, int length, int distance) {
        switch (type) {
            case 1 -> kernel.unfilterSub(raw, rawOffset, out, outOffset, length, distance);
            case 2 -> kernel.unfilterUp(raw, rawOffset, out, outOffset, prior, priorOffset, length);
            case 3 -> kernel.unfilterAverage(raw, rawOffset, out, outOffset, prior, priorOffset, length, distance);
            default -> kernel.unfilterPaeth(raw, rawOffset, out, outOffset, prior, priorOffset, length, distance);
        }
    }

    private static int forward(PngFilterKernel kernel, int type, byte[] row, int rowOffset, byte[] prior, int priorOffset,
                               int length, int distance, byte[] out) {
        return switch (type) {
            case 0 -> kernel.filterNone(row, rowOffset, length, out, 1);
            case 1 -> kernel.filterSub(row, rowOffset, length, distance, out, 1);
            case 2 -> kernel.filterUp(row, rowOffset, prior, priorOffset, length, out, 1);
            case 3 -> kernel.filterAverage(row, rowOffset, prior, priorOffset, length, distance, out, 1);
            default -> kernel.filterPaeth(row, rowOffset, prior, priorOffset, length, distance, out, 1);
        };
    }

    private static byte[] bytes(Random random, int length) {
        var data = new byte[length];
        random.nextBytes(data);
        return data;
    }
}
