package com.github.auties00.smalt.image.jp2.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * Checks that the Vector API wavelet kernel is bit-exact with its scalar reference for every
 * signal length and parity, and that the forward and inverse 5-3 transforms are inverse.
 */
class Jp2KernelTest {
    private static final WaveletKernel SCALAR = new ScalarWaveletKernel();
    private static final WaveletKernel VECTOR = new VectorWaveletKernel();

    private static int[] ints(Random random, int positions) {
        var buffer = new int[positions * WaveletKernel.LANES];
        for (var i = 0; i < buffer.length; i++) {
            buffer[i] = random.nextInt(1 << 16) - (1 << 15);
        }
        return buffer;
    }

    private static float[] floats(Random random, int positions) {
        var buffer = new float[positions * WaveletKernel.LANES];
        for (var i = 0; i < buffer.length; i++) {
            buffer[i] = (float) (random.nextGaussian() * 300);
        }
        return buffer;
    }

    @Test
    @DisplayName("vector 5-3 kernels match the scalar ones bit for bit and invert each other")
    void reversible() {
        var random = new Random(53);
        for (var n = 1; n <= 70; n++) {
            for (var cas = 0; cas <= 1; cas++) {
                var sn = (n + 1 - cas) / 2;
                var dn = n - sn;
                var input = ints(random, n);
                var scalar = input.clone();
                var vector = input.clone();
                SCALAR.forward53(scalar, sn, dn, cas);
                VECTOR.forward53(vector, sn, dn, cas);
                assertArrayEquals(scalar, vector, "forward n=" + n + " cas=" + cas);
                SCALAR.inverse53(scalar, sn, dn, cas);
                VECTOR.inverse53(vector, sn, dn, cas);
                assertArrayEquals(scalar, vector, "inverse n=" + n + " cas=" + cas);
                assertArrayEquals(input, scalar, "round trip n=" + n + " cas=" + cas);
            }
        }
    }

    @Test
    @DisplayName("vector 9-7 kernels match the scalar ones bit for bit")
    void irreversible() {
        var random = new Random(97);
        for (var n = 1; n <= 70; n++) {
            for (var cas = 0; cas <= 1; cas++) {
                var sn = (n + 1 - cas) / 2;
                var dn = n - sn;
                var input = floats(random, n);
                var scalar = input.clone();
                var vector = input.clone();
                SCALAR.forward97(scalar, sn, dn, cas);
                VECTOR.forward97(vector, sn, dn, cas);
                assertArrayEquals(scalar, vector, "forward n=" + n + " cas=" + cas);
                scalar = input.clone();
                vector = input.clone();
                SCALAR.inverse97(scalar, sn, dn, cas);
                VECTOR.inverse97(vector, sn, dn, cas);
                assertArrayEquals(scalar, vector, "inverse n=" + n + " cas=" + cas);
            }
        }
    }
}
