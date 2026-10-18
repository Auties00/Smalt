package com.github.auties00.smalt.audio.voice.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Accuracy and special cases of the musl float math ports. Bit-exactness with musl itself is
 * proven end to end by the oracle tests; here every function is checked against the correctly
 * rounded double-precision result to within its documented error (powf 0.82 ulp, the others
 * about 1 ulp).
 */
@DisplayName("musl float math")
class FloatMathTest {
    private static float ulpDistance(float a, double exact) {
        var rounded = (float) exact;
        return Math.abs(a - rounded) / Math.ulp(rounded);
    }

    @Nested
    @DisplayName("powf")
    class Powf {
        @Test
        @DisplayName("stays within one ulp of the exact power on random arguments")
        void accuracy() {
            var random = new SplittableRandom(1);
            for (var i = 0; i < 200_000; i++) {
                var x = (float) (random.nextDouble() * 1000);
                var y = (float) (random.nextDouble() * 20 - 10);
                var exact = StrictMath.pow(x, y);
                if (exact > Float.MAX_VALUE || exact < Float.MIN_NORMAL) {
                    continue;
                }
                assertTrue(ulpDistance(FloatMath.powf(x, y), exact) <= 1, () -> "powf(" + x + ", " + y + ")");
            }
        }

        @Test
        @DisplayName("is exact for powers of two")
        void powersOfTwo() {
            for (var e = -30; e <= 30; e++) {
                assertEquals((float) Math.pow(2, e), FloatMath.powf(2.f, e));
            }
        }

        @ParameterizedTest(name = "powf({0}, {1}) = {2}")
        @CsvSource({"1.5, 0, 1", "1, NaN, 1", "0, 2, 0", "0, -1, Infinity", "-0, -1, -Infinity", "-2, 3, -8",
                "-2, 0.5, NaN", "2, 200, Infinity", "2, -200, 0", "0.5, Infinity, 0", "2, -Infinity, 0", "NaN, 2, NaN"})
        @DisplayName("follows C99 Annex F for special cases")
        void specialCases(float x, float y, float expected) {
            assertEquals(expected, FloatMath.powf(x, y));
        }
    }

    @Nested
    @DisplayName("tanhf and expm1f")
    class Hyperbolic {
        @Test
        @DisplayName("tanhf stays within two ulps of the exact value")
        void tanh() {
            var random = new SplittableRandom(2);
            for (var i = 0; i < 200_000; i++) {
                var x = (float) (random.nextDouble() * 24 - 12);
                assertTrue(ulpDistance(FloatMath.tanhf(x), StrictMath.tanh(x)) <= 2, () -> "tanhf(" + x + ")");
            }
            assertEquals(1.f, FloatMath.tanhf(50));
            assertEquals(-1.f, FloatMath.tanhf(-50));
            assertEquals(0.f, FloatMath.tanhf(0));
        }

        @Test
        @DisplayName("expm1f stays within one ulp of the exact value")
        void expm1() {
            var random = new SplittableRandom(3);
            for (var i = 0; i < 200_000; i++) {
                var x = (float) (random.nextDouble() * 80 - 40);
                assertTrue(ulpDistance(FloatMath.expm1f(x), StrictMath.expm1(x)) <= 1, () -> "expm1f(" + x + ")");
            }
            assertEquals(-1.f, FloatMath.expm1f(-100));
            assertEquals(Float.POSITIVE_INFINITY, FloatMath.expm1f(100));
        }
    }

    @Nested
    @DisplayName("cosf and sinf")
    class Trigonometric {
        @Test
        @DisplayName("stay within one ulp on [-3 pi / 4, 3 pi / 4] and reject larger arguments")
        void accuracy() {
            var random = new SplittableRandom(4);
            for (var i = 0; i < 200_000; i++) {
                var x = (float) ((random.nextDouble() * 2 - 1) * 3 * Math.PI / 4);
                assertTrue(ulpDistance(FloatMath.cosf(x), StrictMath.cos(x)) <= 1, () -> "cosf(" + x + ")");
                assertTrue(ulpDistance(FloatMath.sinf(x), StrictMath.sin(x)) <= 1, () -> "sinf(" + x + ")");
            }
            assertThrows(IllegalArgumentException.class, () -> FloatMath.cosf(3));
            assertThrows(IllegalArgumentException.class, () -> FloatMath.sinf(-3));
        }

        @Test
        @DisplayName("atanOne is the float nearest pi / 4")
        void atanOne() {
            assertEquals((float) (Math.PI / 4), FloatMath.atanOne());
        }
    }

    @Test
    @DisplayName("fastLog2f reads the exponent with the reference's bias")
    void fastLog2() {
        assertEquals(0.057305f, FloatMath.fastLog2f(1.f), 1e-6f);
        assertEquals(1.057305f, FloatMath.fastLog2f(2.f), 1e-5f);
        assertEquals(10.057305f, FloatMath.fastLog2f(1024.f), 1e-4f);
    }

    @Test
    @DisplayName("max and min keep std::max and std::min's choice of argument")
    void minMax() {
        assertEquals(Float.floatToRawIntBits(-0.f), Float.floatToRawIntBits(FloatMath.max(-0.f, 0.f)));
        assertEquals(Float.floatToRawIntBits(0.f), Float.floatToRawIntBits(FloatMath.max(0.f, -0.f)));
        assertTrue(Float.isNaN(FloatMath.max(Float.NaN, 1.f)));
        assertEquals(1.f, FloatMath.max(1.f, Float.NaN));
        assertEquals(1.f, FloatMath.min(1.f, Float.NaN));
        assertEquals(2.f, FloatMath.min(3.f, 2.f));
    }
}
