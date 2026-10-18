package com.github.auties00.smalt.audio.ac3.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * Checks that the Vector API kernels reproduce the scalar reference bit for bit on random input,
 * for lengths that exercise both the vector loops and their scalar remainders.
 */
@DisplayName("AC-3 kernels: vector matches scalar")
class Ac3KernelEquivalenceTest {
    private static float[] random(Random random, int length) {
        var values = new float[length];
        for (var i = 0; i < length; i++) {
            values[i] = (float) random.nextGaussian();
        }
        return values;
    }

    @ParameterizedTest(name = "twiddle of {0} values")
    @ValueSource(ints = {1, 7, 64, 100, 128})
    @DisplayName("twiddle")
    void twiddle(int count) {
        var random = new Random(count);
        var re = random(random, 128);
        var im = random(random, 128);
        var cos = random(random, 128);
        var sin = random(random, 128);
        var re2 = re.clone();
        var im2 = im.clone();
        Ac3Kernels.scalar().twiddle(re, im, cos, sin, count);
        Ac3Kernels.vector().twiddle(re2, im2, cos, sin, count);
        assertArrayEquals(re, re2);
        assertArrayEquals(im, im2);
    }

    @ParameterizedTest(name = "stage of span {0} over {1} values")
    @CsvSource({"1, 128", "2, 128", "4, 64", "8, 128", "16, 128", "32, 64", "64, 128", "256, 512"})
    @DisplayName("butterflies")
    void butterflies(int half, int length) {
        var random = new Random(31L * half + length);
        var re = random(random, length);
        var im = random(random, length);
        var cos = random(random, half);
        var sin = random(random, half);
        var re2 = re.clone();
        var im2 = im.clone();
        Ac3Kernels.scalar().butterflies(re, im, cos, sin, half, length);
        Ac3Kernels.vector().butterflies(re2, im2, cos, sin, half, length);
        assertArrayEquals(re, re2);
        assertArrayEquals(im, im2);
    }

    @ParameterizedTest(name = "stages of spans {0} and twice that over {1} values")
    @CsvSource({"1, 128", "2, 64", "4, 128", "8, 128", "16, 64", "16, 128", "32, 128", "64, 256"})
    @DisplayName("butterfly pairs")
    void butterflyPair(int half, int length) {
        var random = new Random(17L * half + length);
        var re = random(random, length);
        var im = random(random, length);
        var cos1 = random(random, half);
        var sin1 = random(random, half);
        var cos2 = random(random, 2 * half);
        var sin2 = random(random, 2 * half);
        var re2 = re.clone();
        var im2 = im.clone();
        var re3 = re.clone();
        var im3 = im.clone();
        Ac3Kernels.scalar().butterflyPair(re, im, cos1, sin1, cos2, sin2, half, length);
        Ac3Kernels.vector().butterflyPair(re2, im2, cos1, sin1, cos2, sin2, half, length);
        assertArrayEquals(re, re2);
        assertArrayEquals(im, im2);
        Ac3Kernels.scalar().butterflies(re3, im3, cos1, sin1, half, length);
        Ac3Kernels.scalar().butterflies(re3, im3, cos2, sin2, 2 * half, length);
        assertArrayEquals(re, re3);
        assertArrayEquals(im, im3);
    }

    @ParameterizedTest(name = "first stages over {0} values")
    @ValueSource(ints = {8, 64, 128, 512})
    @DisplayName("first stages")
    void firstStages(int length) {
        var random = new Random(length);
        var re = random(random, length);
        var im = random(random, length);
        var cos = new float[24];
        var sin = new float[24];
        for (var stage = 0; stage < 3; stage++) {
            var half = 1 << stage;
            var c = random(random, half);
            var s = random(random, half);
            for (var lane = 0; lane < 8; lane++) {
                cos[8 * stage + lane] = c[lane % half];
                sin[8 * stage + lane] = s[lane % half];
            }
        }
        var re2 = re.clone();
        var im2 = im.clone();
        var re3 = re.clone();
        var im3 = im.clone();
        Ac3Kernels.scalar().firstStages(re, im, cos, sin, length);
        Ac3Kernels.vector().firstStages(re2, im2, cos, sin, length);
        assertArrayEquals(re, re2);
        assertArrayEquals(im, im2);
        for (var stage = 0; stage < 3; stage++) {
            Ac3Kernels.scalar().butterflies(re3, im3, java.util.Arrays.copyOfRange(cos, 8 * stage, 8 * stage + 8),
                    java.util.Arrays.copyOfRange(sin, 8 * stage, 8 * stage + 8), 1 << stage, length);
        }
        assertArrayEquals(re, re3);
        assertArrayEquals(im, im3);
    }

    @ParameterizedTest(name = "interleave of {0} pairs")
    @ValueSource(ints = {1, 3, 8, 13, 64})
    @DisplayName("interleave")
    void interleave(int pairs) {
        var random = new Random(pairs);
        var forward = random(random, pairs + 6);
        var backward = random(random, pairs + 4);
        var window = random(random, 2 * pairs + 3);
        var out = new float[2 * pairs + 5];
        var out2 = out.clone();
        Ac3Kernels.scalar().interleave(forward, 6, backward, pairs + 3, window, 3, out, 5, pairs);
        Ac3Kernels.vector().interleave(forward, 6, backward, pairs + 3, window, 3, out2, 5, pairs);
        assertArrayEquals(out, out2);
    }

    @ParameterizedTest(name = "overlap of {0} samples")
    @ValueSource(ints = {1, 9, 128, 256})
    @DisplayName("overlap")
    void overlap(int half) {
        var random = new Random(half);
        var block = random(random, 2 * half);
        var delay = random(random, half);
        var delay2 = delay.clone();
        var out = new float[half + 5];
        var out2 = new float[half + 5];
        Ac3Kernels.scalar().overlap(block, delay, out, 5, half);
        Ac3Kernels.vector().overlap(block, delay2, out2, 5, half);
        assertArrayEquals(out, out2);
        assertArrayEquals(delay, delay2);
    }

    @ParameterizedTest(name = "scale and accumulate over {0} samples")
    @ValueSource(ints = {1, 13, 256, 1536})
    @DisplayName("scale and accumulate")
    void mix(int count) {
        var random = new Random(count);
        var in = random(random, count + 3);
        var out = random(random, count + 2);
        var out2 = out.clone();
        Ac3Kernels.scalar().scale(in, 3, 0.7f, out, 2, count);
        Ac3Kernels.vector().scale(in, 3, 0.7f, out2, 2, count);
        assertArrayEquals(out, out2);
        Ac3Kernels.scalar().accumulate(in, 1, -0.35f, out, 1, count);
        Ac3Kernels.vector().accumulate(in, 1, -0.35f, out2, 1, count);
        assertArrayEquals(out, out2);
    }
}
