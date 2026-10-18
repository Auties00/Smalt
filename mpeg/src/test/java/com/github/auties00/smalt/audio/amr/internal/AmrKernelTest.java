package com.github.auties00.smalt.audio.amr.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * Checks that the Vector API kernel returns exactly the values of the scalar one, over every
 * run length around the vector width and over the full range of samples and coefficients.
 */
class AmrKernelTest {
    private static final AmrKernel SCALAR = AmrKernels.scalar();

    private static final AmrKernel VECTOR = AmrKernels.vector();

    private static short[] samples(Random random, int length) {
        var values = new short[length];
        for (var i = 0; i < length; i++) {
            values[i] = (short) random.nextInt(65536);
        }
        return values;
    }

    private static int[] coefficients(Random random, int length) {
        var values = new int[length];
        for (var i = 0; i < length; i++) {
            values[i] = random.nextInt();
        }
        return values;
    }

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(ints = {1, 2, 3})
    @DisplayName("filter forward bit for bit alike over every run length")
    void forward(int seed) {
        var random = new Random(seed);
        for (var taps : new int[]{1, 2, 10, 24, 31, 32, 40}) {
            for (var count = 0; count <= 83; count++) {
                var x = samples(random, count + taps + 16);
                var c = coefficients(random, taps);
                var offset = random.nextInt(8);
                var a = new int[count + 8];
                var b = new int[count + 8];
                SCALAR.fir(x, offset, c, taps, count, a, 3);
                VECTOR.fir(x, offset, c, taps, count, b, 3);
                assertArrayEquals(a, b, "taps " + taps + " count " + count);
            }
        }
    }

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(ints = {1, 2, 3})
    @DisplayName("filter backward bit for bit alike over every run length")
    void backward(int seed) {
        var random = new Random(seed);
        for (var taps : new int[]{1, 2, 11, 24, 40}) {
            for (var count = 0; count <= 83; count++) {
                var x = samples(random, count + taps + 16);
                var c = coefficients(random, taps);
                var offset = taps - 1 + random.nextInt(8);
                var a = new int[count + 8];
                var b = new int[count + 8];
                SCALAR.firReverse(x, offset, c, taps, count, a, 3);
                VECTOR.firReverse(x, offset, c, taps, count, b, 3);
                assertArrayEquals(a, b, "taps " + taps + " count " + count);
            }
        }
    }

    @ParameterizedTest(name = "kind {0}")
    @ValueSource(ints = {0, 1, 2, 3})
    @DisplayName("pair search bit for bit alike on random candidates")
    void pairSearch(int kind) {
        var random = new Random(kind + 7);
        for (var round = 0; round < 2000; round++) {
            var block = new short[64];
            var alp1 = new int[8];
            var psj = new short[8];
            var ta = new short[8];
            var tb = new short[8];
            for (var i = 0; i < 64; i++) {
                block[i] = (short) (random.nextInt(65536) >> random.nextInt(8));
            }
            for (var i = 0; i < 8; i++) {
                alp1[i] = random.nextInt() >> random.nextInt(12);
                psj[i] = (short) (random.nextInt(65536) >> random.nextInt(8));
                ta[i] = (short) (random.nextInt(65536) >> random.nextInt(8));
                tb[i] = (short) random.nextInt(65536);
            }
            var a = new int[32];
            var b = new int[32];
            SCALAR.pairSearch(kind, block, alp1, psj, ta, tb, a);
            VECTOR.pairSearch(kind, block, alp1, psj, ta, tb, b);
            for (var t = 0; t < 8; t++) {
                org.junit.jupiter.api.Assertions.assertEquals(a[24 + t] != 0, b[24 + t] != 0, "bad " + t);
                if (a[24 + t] == 0) {
                    org.junit.jupiter.api.Assertions.assertEquals(a[t], b[t]);
                    org.junit.jupiter.api.Assertions.assertEquals(a[8 + t], b[8 + t]);
                    org.junit.jupiter.api.Assertions.assertEquals(a[16 + t], b[16 + t]);
                }
            }
        }
    }
}
