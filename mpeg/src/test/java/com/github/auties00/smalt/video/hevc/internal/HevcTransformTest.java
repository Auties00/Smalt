package com.github.auties00.smalt.video.hevc.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * Checks the inverse transforms against a direct evaluation of ITU-T H.265 8.6.4 (two matrix
 * products with the intermediate clipping) on random sparse and dense coefficient blocks of every
 * size, and the extended precision transform against the same evaluation with its wider clipping.
 */
@DisplayName("HEVC inverse transforms")
class HevcTransformTest {
    // Equations 8-314 to 8-319 evaluated directly, with 64-bit sums.
    private static int[] reference(int[] d, int log2Size, boolean dst, int bdShift, long coeffMin, long coeffMax) {
        var size = 1 << log2Size;
        var basis = dst ? HevcTransform.DST4 : HevcTransform.DCT32;
        var step = dst ? 4 : (32 >> log2Size) * 32;
        var g = new long[size * size];
        for (var x = 0; x < size; x++) {
            for (var y = 0; y < size; y++) {
                long sum = 0;
                for (var k = 0; k < size; k++) {
                    sum += (long) basis[k * step + y] * d[k * size + x];
                }
                g[y * size + x] = Math.clamp((sum + 64) >> 7, coeffMin, coeffMax);
            }
        }
        var r = new int[size * size];
        for (var y = 0; y < size; y++) {
            for (var x = 0; x < size; x++) {
                long sum = 0;
                for (var k = 0; k < size; k++) {
                    sum += basis[k * step + x] * g[y * size + k];
                }
                r[y * size + x] = (int) ((sum + (1L << (bdShift - 1))) >> bdShift);
            }
        }
        return r;
    }

    private static int[] block(Random random, int size, int columns, int rows, int range) {
        var d = new int[size * size];
        for (var y = 0; y < rows; y++) {
            for (var x = 0; x < columns; x++) {
                if (random.nextInt(3) == 0) {
                    d[y * size + x] = random.nextInt(2 * range + 1) - range;
                }
            }
        }
        d[(rows - 1) * size + columns - 1] = range;
        return d;
    }

    @ParameterizedTest(name = "{0}-bit")
    @ValueSource(ints = {8, 10, 12})
    @DisplayName("the DCT and DST follow the specification for every size and sparsity, scalar and vector")
    void matchesSpecification(int bitDepth) {
        var random = new Random(bitDepth);
        var transform = new HevcTransform();
        var kernels = new HevcDsp[]{new ScalarHevcDsp(), VectorHevcDsp.create()};
        for (var log2Size = 2; log2Size <= 5; log2Size++) {
            var size = 1 << log2Size;
            for (var trial = 0; trial < 200; trial++) {
                var dst = log2Size == 2 && trial % 2 == 0;
                var columns = dst ? size : 1 + random.nextInt(size);
                var rows = dst ? size : 1 + random.nextInt(size);
                var d = block(random, size, columns, rows, trial % 5 == 0 ? 32768 : 2000);
                var expected = reference(d, log2Size, dst, 20 - bitDepth, -32768, 32767);
                for (var dsp : kernels) {
                    var actual = new int[size * size];
                    transform.inverse(d.clone(), log2Size, dst, 20 - bitDepth, columns, rows, actual, dsp);
                    assertArrayEquals(expected, actual, dsp.getClass().getSimpleName() + " " + size + "x" + size
                            + " trial " + trial);
                }
            }
        }
    }

    @ParameterizedTest(name = "range {0}")
    @ValueSource(ints = {16, 18, 22})
    @DisplayName("the extended precision transform follows the specification with its wider clipping")
    void extendedPrecision(int log2Range) {
        var random = new Random(log2Range);
        var transform = new HevcTransform();
        var min = -(1 << log2Range);
        var max = (1 << log2Range) - 1;
        for (var log2Size = 2; log2Size <= 5; log2Size++) {
            var size = 1 << log2Size;
            for (var trial = 0; trial < 50; trial++) {
                var dst = log2Size == 2 && trial % 2 == 0;
                var d = block(random, size, size, size, 1 << (log2Range - 1));
                var actual = new int[size * size];
                transform.inverseWide(d.clone(), log2Size, dst, 11, min, max, actual);
                assertArrayEquals(reference(d, log2Size, dst, 11, min, max), actual, size + "x" + size + " trial " + trial);
            }
        }
    }
}
