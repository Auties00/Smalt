package com.github.auties00.smalt.audio.vorbis;

import com.github.auties00.smalt.testkit.Skip;
import com.github.auties00.smalt.internal.simd.Simd;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks the inverse MDCT of both kernels against the direct O(n^2) definition for every block
 * size, the windows against the specification formula, and the vector kernel against the scalar
 * reference on randomized input, bit for bit.
 */
class VorbisKernelTest {
    private static final VorbisKernel SCALAR = new ScalarVorbisKernel();

    private static VorbisKernel vector() {
        Skip.unlessVector(Simd.ENABLED);
        return VectorVorbisKernel.create();
    }

    private static double[] directImdct(float[] spectrum, int n) {
        var out = new double[n];
        for (var i = 0; i < n; i++) {
            var sum = 0.0;
            for (var k = 0; k < n / 2; k++) {
                sum += spectrum[k] * Math.cos(2 * Math.PI / n * (i + 0.5 + n / 4.0) * (k + 0.5));
            }
            out[i] = sum;
        }
        return out;
    }

    private static float[] random(Random random, int length) {
        var values = new float[length];
        for (var i = 0; i < length; i++) {
            values[i] = random.nextFloat() * 2 - 1;
        }
        return values;
    }

    private static float[] imdct(VorbisKernel kernel, float[] spectrum, int n) {
        var output = new float[n];
        kernel.imdct(new Imdct(n), spectrum.clone(), output, new float[n / 4], new float[n / 4]);
        return output;
    }

    private static void assertClose(double[] expected, float[] actual, double relative, String message) {
        var norm = 0.0;
        for (var value : expected) {
            norm = Math.max(norm, Math.abs(value));
        }
        for (var i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual[i], relative * norm, message + " at " + i);
        }
    }

    @Nested
    @DisplayName("inverse MDCT")
    class Transform {
        @ParameterizedTest(name = "n = {0}")
        @ValueSource(ints = {64, 128, 256, 512, 1024, 2048, 4096, 8192})
        @DisplayName("scalar kernel matches the direct definition")
        void scalar(int n) {
            var spectrum = random(new Random(n), n / 2);
            assertClose(directImdct(spectrum, n), imdct(SCALAR, spectrum, n), 2e-6, "scalar n=" + n);
        }

        @ParameterizedTest(name = "n = {0}")
        @ValueSource(ints = {64, 128, 256, 512, 1024, 2048, 4096, 8192})
        @DisplayName("vector kernel matches the direct definition")
        void vector(int n) {
            var kernel = VorbisKernelTest.vector();
            var spectrum = random(new Random(n + 1), n / 2);
            assertClose(directImdct(spectrum, n), imdct(kernel, spectrum, n), 2e-6, "vector n=" + n);
        }

        @ParameterizedTest(name = "n = {0}")
        @ValueSource(ints = {64, 128, 256, 512, 1024, 2048, 4096, 8192})
        @DisplayName("vector kernel equals the scalar kernel bit for bit on 50 random blocks")
        void equivalence(int n) {
            var kernel = VorbisKernelTest.vector();
            var random = new Random(7 * n);
            for (var round = 0; round < 50; round++) {
                var spectrum = random(random, n / 2);
                var scale = (float) Math.pow(10, random.nextInt(9) - 4);
                for (var i = 0; i < spectrum.length; i++) {
                    spectrum[i] *= scale;
                }
                assertArrayEquals(imdct(SCALAR, spectrum, n), imdct(kernel, spectrum, n), "round " + round);
            }
        }

        @ParameterizedTest(name = "n = {0}")
        @ValueSource(ints = {64, 128, 256, 512, 1024, 2048, 4096, 8192})
        @DisplayName("vector kernel equals the scalar kernel bit for bit on zeros, infinities and NaN")
        void specials(int n) {
            var kernel = VorbisKernelTest.vector();
            var random = new Random(11 * n);
            float[] specials = {0.0f, -0.0f, Float.MIN_VALUE, -Float.MAX_VALUE, Float.POSITIVE_INFINITY, Float.NaN};
            for (var round = 0; round < 12; round++) {
                var spectrum = new float[n / 2];
                for (var i = 0; i < spectrum.length; i++) {
                    if (round < specials.length) {
                        spectrum[i] = specials[round];
                    } else {
                        spectrum[i] = random.nextInt(4) == 0 ? specials[random.nextInt(specials.length)]
                                : random.nextFloat() - 0.5f;
                    }
                }
                assertArrayEquals(imdct(SCALAR, spectrum, n), imdct(kernel, spectrum, n), "round " + round);
            }
        }

        @Test
        @DisplayName("maps a single coefficient to its cosine and a zero spectrum to zeros")
        void impulse() {
            var n = 256;
            var spectrum = new float[n / 2];
            spectrum[3] = 1;
            var expected = new double[n];
            for (var i = 0; i < n; i++) {
                expected[i] = Math.cos(2 * Math.PI / n * (i + 0.5 + n / 4.0) * 3.5);
            }
            assertClose(expected, imdct(SCALAR, spectrum, n), 1e-6, "impulse");
            assertArrayEquals(new float[n], imdct(SCALAR, new float[n / 2], n), 0.0f);
        }
    }

    @Nested
    @DisplayName("window")
    class Window {
        @ParameterizedTest(name = "n = {0}")
        @ValueSource(ints = {64, 2048, 8192})
        @DisplayName("slopes follow sin(PI/2 sin^2((k + 1/2)/(n/2) PI/2)) and satisfy Princen-Bradley")
        void slope(int n) {
            var plan = new Imdct(n);
            for (var k = 0; k < n / 2; k++) {
                var inner = Math.sin((k + 0.5) / (n / 2) * Math.PI / 2);
                assertEquals(Math.sin(Math.PI / 2 * inner * inner), plan.slope[k], 1e-7);
                assertEquals(plan.slope[n / 2 - 1 - k], plan.slopeDown[k]);
                var power = (double) plan.slope[k] * plan.slope[k] + (double) plan.slopeDown[k] * plan.slopeDown[k];
                assertEquals(1.0, power, 1e-6, "power complementarity at " + k);
            }
        }
    }

    @Nested
    @DisplayName("coupling and lapping primitives")
    class Primitives {
        private static float[] withSpecials(Random random, int length) {
            var values = random(random, length);
            float[] specials = {0.0f, -0.0f, 1.0f, -1.0f, Float.MIN_VALUE, -Float.MIN_VALUE, 1e-30f, -1e30f,
                    Float.NaN, -Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.MAX_VALUE};
            for (var i = 0; i < specials.length && i < length; i++) {
                values[random.nextInt(length)] = specials[i];
            }
            return values;
        }

        @Test
        @DisplayName("inverse coupling follows the four-case table of Vorbis I 4.3.5")
        void couplingTable() {
            float[][] cases = {{3, 1, 3, 2}, {3, -1, 2, 3}, {-3, 1, -3, -2}, {-3, -1, -2, -3}, {0, 2, 0, 2}, {0, -2, 2, 0},
                    {Float.POSITIVE_INFINITY, 1, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY}};
            for (var kernel : new VorbisKernel[]{SCALAR, Simd.ENABLED ? VectorVorbisKernel.create() : SCALAR}) {
                for (var c : cases) {
                    var magnitude = new float[17];
                    var angle = new float[17];
                    java.util.Arrays.fill(magnitude, c[0]);
                    java.util.Arrays.fill(angle, c[1]);
                    kernel.inverseCoupling(magnitude, angle, 17);
                    for (var i = 0; i < 17; i++) {
                        assertEquals(c[2], magnitude[i], "M' for " + c[0] + "/" + c[1]);
                        assertEquals(c[3], angle[i], "A' for " + c[0] + "/" + c[1]);
                    }
                }
            }
        }

        @ParameterizedTest(name = "length {0}")
        @ValueSource(ints = {1, 7, 8, 31, 1024, 4099})
        @DisplayName("vector inverse coupling equals the scalar result bit for bit")
        void coupling(int length) {
            var kernel = vector();
            var random = new Random(length);
            for (var round = 0; round < 20; round++) {
                var magnitude = withSpecials(random, length);
                var angle = withSpecials(random, length);
                var m1 = magnitude.clone();
                var a1 = angle.clone();
                var m2 = magnitude.clone();
                var a2 = angle.clone();
                SCALAR.inverseCoupling(m1, a1, length);
                kernel.inverseCoupling(m2, a2, length);
                assertArrayEquals(m1, m2);
                assertArrayEquals(a1, a2);
            }
        }

        @ParameterizedTest(name = "length {0}")
        @ValueSource(ints = {0, 1, 7, 8, 33, 1000})
        @DisplayName("vector multiply, multiply-add and add equal the scalar results bit for bit")
        void lapping(int length) {
            var kernel = vector();
            var random = new Random(length + 99);
            var a = random(random, length + 5);
            var b = random(random, length + 3);
            var base = random(random, length + 2);
            for (var op = 0; op < 3; op++) {
                var expected = base.clone();
                var actual = base.clone();
                switch (op) {
                    case 0 -> {
                        SCALAR.multiplyAdd(a, 5, b, 3, expected, 2, length);
                        kernel.multiplyAdd(a, 5, b, 3, actual, 2, length);
                    }
                    case 1 -> {
                        SCALAR.multiply(a, 5, b, 3, expected, 2, length);
                        kernel.multiply(a, 5, b, 3, actual, 2, length);
                    }
                    default -> {
                        SCALAR.add(a, 5, expected, 2, length);
                        kernel.add(a, 5, actual, 2, length);
                    }
                }
                assertArrayEquals(expected, actual, "operation " + op);
            }
        }
    }
}
