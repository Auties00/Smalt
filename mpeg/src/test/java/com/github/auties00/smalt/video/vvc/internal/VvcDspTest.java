package com.github.auties00.smalt.video.vvc.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * Checks the Vector API kernels of the VVC decoder against their scalar twins on random inputs of every block width
 * the decoder uses, the widths the vector kernels leave to the scalar ones included.
 */
@DisplayName("VVC DSP kernels")
class VvcDspTest {
    private static final ScalarVvcDsp SCALAR = new ScalarVvcDsp();
    private static final ScalarVvcDsp VECTOR = VectorVvcDsp.create();
    private static final int[] WIDTHS = {1, 2, 4, 8, 12, 16, 24, 32, 64, 128};
    private static final int[][] LUMA = {{0, 1, -3, 63, 4, -2, 1, 0}, {-1, 4, -11, 40, 40, -11, 4, -1},
            {0, 3, 9, 20, 20, 9, 3, 0}, {1, -5, 17, 58, -10, 4, -1, 0}};
    private static final int[][] CHROMA = {{-2, 58, 10, -2}, {-4, 36, 36, -4}, {-1, 6, 61, -2}};
    private static final int[][] BILINEAR = {{8, 8}, {14, 2}};

    private static int[] random(Random random, int length, int min, int max) {
        var values = new int[length];
        for (var i = 0; i < length; i++) {
            // runs of the extremes reach the largest sums
            var pick = random.nextInt(8);
            values[i] = pick == 0 ? min : pick == 1 ? max : min + random.nextInt(max - min + 1);
        }
        return values;
    }

    @ParameterizedTest(name = "{0} taps")
    @ValueSource(ints = {8, 4, 2})
    @DisplayName("filters rows and columns as the scalar kernels do")
    void filters(int taps) {
        var random = new Random(taps);
        var coefficients = taps == 8 ? LUMA : taps == 4 ? CHROMA : BILINEAR;
        for (var w : WIDTHS) {
            for (var h : new int[]{1, 4, 8, 16, 37}) {
                for (var c : coefficients) {
                    var ss = w + 9;
                    var src = random(random, ss * (h + 8), -(1 << 14), (1 << 14) - 1);
                    for (var shift : new int[]{2, 6}) {
                        var expected = new int[(w + 3) * h + 5];
                        var actual = expected.clone();
                        SCALAR.filterRows(src, 3, ss, expected, 5, w + 3, w, h, c, taps, shift);
                        VECTOR.filterRows(src, 3, ss, actual, 5, w + 3, w, h, c, taps, shift);
                        assertArrayEquals(expected, actual, "rows " + w + "x" + h);
                        expected = new int[(w + 3) * h + 5];
                        actual = expected.clone();
                        SCALAR.filterCols(src, 2, ss, expected, 5, w + 3, w, h, c, taps, shift, -77);
                        VECTOR.filterCols(src, 2, ss, actual, 5, w + 3, w, h, c, taps, shift, -77);
                        assertArrayEquals(expected, actual, "columns " + w + "x" + h);
                    }
                }
            }
        }
    }

    @ParameterizedTest(name = "{0}-bit")
    @ValueSource(ints = {8, 10})
    @DisplayName("averages and clips predictions as the scalar kernels do")
    void averages(int bitDepth) {
        var random = new Random(bitDepth);
        var maxVal = (1 << bitDepth) - 1;
        var hr = 14 - bitDepth;
        for (var w : WIDTHS) {
            for (var h : new int[]{1, 2, 4, 8, 16}) {
                var bs = w + 4;
                var p0 = random(random, bs * h + 3, -(1 << 13) - 2000, (1 << 13) + 20000);
                var p1 = random(random, bs * h + 3, -(1 << 13) - 2000, (1 << 13) + 20000);
                var stride = w + 7;
                for (var weights : new int[][]{{1, 1}, {3, 5}, {-2, 10}, {10, -2}}) {
                    var bcw = weights[0] != 1;
                    var shift = bcw ? hr + 3 : hr + 1;
                    var offset = bcw ? (1 << (shift - 1)) + ((1 << 13) << 3) : (1 << (shift - 1)) + 2 * (1 << 13);
                    var expected = new short[stride * h + 2];
                    var actual = expected.clone();
                    SCALAR.average(p0, p1, 3, bs, expected, 2, stride, w, h, weights[0], weights[1], shift, offset,
                            maxVal);
                    VECTOR.average(p0, p1, 3, bs, actual, 2, stride, w, h, weights[0], weights[1], shift, offset,
                            maxVal);
                    assertArrayEquals(expected, actual, "average " + w + "x" + h);
                }
                var expected = new short[stride * h + 2];
                var actual = expected.clone();
                var offset = (1 << 13) + (1 << (hr - 1));
                SCALAR.single(p0, 1, bs, expected, 2, stride, w, h, hr, offset, maxVal);
                VECTOR.single(p0, 1, bs, actual, 2, stride, w, h, hr, offset, maxVal);
                assertArrayEquals(expected, actual, "single " + w + "x" + h);
            }
        }
    }

    @Test
    @DisplayName("computes optical flow gradients and refined samples as the scalar kernels do")
    void opticalFlow() {
        var random = new Random(5);
        for (var w : new int[]{4, 8, 16}) {
            for (var h : new int[]{4, 8, 16}) {
                var es = w + 2;
                var size = es * (h + 2);
                var src = random(random, size, -(1 << 13), (1 << 14));
                var expectedX = new int[size];
                var expectedY = new int[size];
                var actualX = new int[size];
                var actualY = new int[size];
                SCALAR.gradients(src, expectedX, expectedY, es, w, h);
                VECTOR.gradients(src, actualX, actualY, es, w, h);
                assertArrayEquals(expectedX, actualX, "horizontal gradients " + w + "x" + h);
                assertArrayEquals(expectedY, actualY, "vertical gradients " + w + "x" + h);
            }
        }
        for (var round = 0; round < 500; round++) {
            var es = 18;
            var size = es * 18;
            var s0 = random(random, size, -(1 << 14), 1 << 15);
            var s1 = random(random, size, -(1 << 14), 1 << 15);
            var gx0 = random(random, size, -512, 512);
            var gx1 = random(random, size, -512, 512);
            var gy0 = random(random, size, -512, 512);
            var gy1 = random(random, size, -512, 512);
            var tx = random.nextInt(31) - 15;
            var ty = random.nextInt(31) - 15;
            var bitDepth = random.nextBoolean() ? 8 : 10;
            var shift = 15 - bitDepth;
            var offset = (1 << (shift - 1)) + 2 * (1 << 13);
            var p = random.nextInt(4) * es + random.nextInt(4) + es + 1;
            var expected = new short[20 * 6];
            var actual = expected.clone();
            SCALAR.bdofSamples(s0, s1, gx0, gx1, gy0, gy1, p, es, expected, 3, 20, tx, ty, shift, offset,
                    (1 << bitDepth) - 1);
            VECTOR.bdofSamples(s0, s1, gx0, gx1, gy0, gy1, p, es, actual, 3, 20, tx, ty, shift, offset,
                    (1 << bitDepth) - 1);
            assertArrayEquals(expected, actual, "refined samples, round " + round);
        }
    }

    @Test
    @DisplayName("applies both inverse transform passes as the scalar kernels do")
    void inverseTransform() {
        var random = new Random(7);
        var sizes = new int[]{2, 4, 8, 16, 32, 64};
        for (var w : sizes) {
            for (var h : sizes) {
                for (var type = 0; type < 3; type++) {
                    if (type > 0 && (w > 32 || h > 32 || w < 4 || h < 4)) {
                        continue;
                    }
                    // the zero-out bounds of the transforms, and the 4 and 8 of a low-frequency non-separable one
                    for (var bound : new int[]{type > 0 ? 16 : 32, 8, 4}) {
                        checkTransform(random, w, h, type, Math.min(w, bound), Math.min(h, bound));
                    }
                }
            }
        }
    }

    private static void checkTransform(Random random, int w, int h, int type, int nonZeroW, int nonZeroH) {
        var d = random(random, w * h, -(1 << 15), (1 << 15) - 1);
        var expected = new int[w * h];
        var actual = new int[w * h];
        SCALAR.inverseVertical(d, w, h, nonZeroW, nonZeroH, matrix(h, type), rowStride(h, type), expected);
        VECTOR.inverseVertical(d, w, h, nonZeroW, nonZeroH, matrix(h, type), rowStride(h, type), actual);
        assertArrayEquals(expected, actual, "vertical " + w + "x" + h + " type " + type);
        var e = random(random, w * h, -(1 << 15), (1 << 15) - 1);
        expected = new int[w * h];
        actual = new int[w * h];
        SCALAR.inverseHorizontal(e, w, h, nonZeroW, matrix(w, type), rowStride(w, type), expected, 1 << 9, 10);
        VECTOR.inverseHorizontal(e, w, h, nonZeroW, matrix(w, type), rowStride(w, type), actual, 1 << 9, 10);
        assertArrayEquals(expected, actual, "horizontal " + w + "x" + h + " type " + type);
    }

    private static int[] matrix(int size, int type) {
        var log2 = Integer.numberOfTrailingZeros(size);
        return type == 0 ? VvcTransformTables.DCT2_64 : type == 1 ? VvcTransformTables.DST7[log2]
                : VvcTransformTables.DCT8[log2];
    }

    private static int rowStride(int size, int type) {
        return type == 0 ? (64 / size) * 64 : size;
    }
}
