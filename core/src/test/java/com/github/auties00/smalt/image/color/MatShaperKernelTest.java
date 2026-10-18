package com.github.auties00.smalt.image.color;

import com.github.auties00.smalt.internal.simd.Simd;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Checks that the Vector API matrix-shaper kernel returns exactly what its scalar twin returns, on
 * random tables (including linearized values large enough to overflow the fixed-point products),
 * every block length and both RGB and RGBA layouts.
 */
@DisplayName("matrix-shaper kernel")
class MatShaperKernelTest {
    @BeforeAll
    static void requireVectors() {
        assumeTrue(Simd.ENABLED, "the Vector API is not enabled");
    }

    private static MatShaperKernel.Tables tables(SplittableRandom random, boolean extreme) {
        var linear = new int[3][256];
        for (var c = 0; c < 3; c++) {
            for (var i = 0; i < 256; i++) {
                linear[c][i] = extreme && random.nextInt(8) == 0 ? 0x7FFFFFFF : random.nextInt(extreme ? 40000 : 16385);
            }
        }
        var matrix = new int[9];
        for (var i = 0; i < 9; i++) {
            matrix[i] = random.nextInt(extreme ? 200000 : 40000) - (extreme ? 100000 : 12000);
        }
        var offset = new int[]{random.nextInt(4000) - 2000, random.nextInt(4000) - 2000, random.nextInt(4000) - 2000};
        var out = new byte[3][16385];
        for (var c = 0; c < 3; c++) {
            random.nextBytes(out[c]);
        }
        return new MatShaperKernel.Tables(linear[0], linear[1], linear[2], matrix, offset, out[0], out[1], out[2]);
    }

    @ParameterizedTest(name = "stride {0}")
    @ValueSource(ints = {3, 4})
    @DisplayName("vector and scalar agree on every length")
    void equivalent(int stride) {
        var random = new SplittableRandom(stride * 7919L);
        var scalar = new ScalarMatShaperKernel();
        var vector = VectorMatShaperKernel.create();
        var scratch = new int[MatShaperKernel.SCRATCH];
        for (var round = 0; round < 40; round++) {
            var tables = tables(random, round % 2 == 1);
            for (var count = 0; count <= MatShaperKernel.BLOCK; count++) {
                var src = new byte[count * stride + 5];
                random.nextBytes(src);
                var expected = new byte[count * stride + 5];
                random.nextBytes(expected);
                var actual = expected.clone();
                scalar.apply(src, 2, stride, expected, 1, stride, count, tables, scratch);
                vector.apply(src, 2, stride, actual, 1, stride, count, tables, scratch);
                assertArrayEquals(expected, actual, "count " + count);
            }
        }
    }
}
