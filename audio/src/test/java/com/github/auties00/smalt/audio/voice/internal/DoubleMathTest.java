package com.github.auties00.smalt.audio.voice.internal;

import com.github.auties00.smalt.audio.voice.WasmApmOracle;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.SplittableRandom;
import java.util.function.DoubleBinaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The musl double math ports against the musl build of the WebRTC oracle, bit for bit, on random
 * arguments over each function's range and on its special values.
 */
@DisplayName("musl double math against the oracle's libm")
class DoubleMathTest {
    private static WasmApmOracle.Libm libm;

    @BeforeAll
    static void open() {
        libm = new WasmApmOracle.Libm();
    }

    @AfterAll
    static void close() {
        libm.close();
    }

    private static void check(int which, String name, DoubleBinaryOperator java, double x, double y) {
        var expected = libm.apply(which, x, y);
        var actual = java.applyAsDouble(x, y);
        if (Double.isNaN(expected)) {
            assertTrue(Double.isNaN(actual), () -> name + "(" + x + ", " + y + "): expected NaN but was " + actual);
            return;
        }
        assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(actual),
                () -> name + "(" + x + ", " + y + "): expected " + expected + " but was " + actual);
    }

    private static double logUniform(SplittableRandom random, int minExponent, int maxExponent) {
        return Math.scalb(1.0 + random.nextDouble(), random.nextInt(minExponent, maxExponent));
    }

    @Test
    @DisplayName("exp matches on random arguments from underflow to overflow")
    void exp() {
        var random = new SplittableRandom(11);
        for (var i = 0; i < 20_000; i++) {
            var x = i % 2 == 0 ? random.nextDouble(-760, 720) : (random.nextBoolean() ? 1 : -1) * logUniform(random, -60, 4);
            check(WasmApmOracle.Libm.EXP, "exp", (a, b) -> DoubleMath.exp(a), x, 0);
        }
    }

    @Test
    @DisplayName("log matches on random arguments, subnormals and values near one")
    void log() {
        var random = new SplittableRandom(12);
        for (var i = 0; i < 20_000; i++) {
            var x = switch (i % 3) {
                case 0 -> logUniform(random, -1074, 1023);
                case 1 -> 1 + random.nextDouble(-0.07, 0.07);
                default -> random.nextDouble(0, 40_000);
            };
            check(WasmApmOracle.Libm.LOG, "log", (a, b) -> DoubleMath.log(a), x, 0);
        }
    }

    @Test
    @DisplayName("pow matches on random bases and exponents")
    void pow() {
        var random = new SplittableRandom(13);
        for (var i = 0; i < 20_000; i++) {
            var x = i % 2 == 0 ? 0.85 : logUniform(random, -40, 40);
            var y = random.nextDouble(-30, 30);
            check(WasmApmOracle.Libm.POW, "pow", DoubleMath::pow, x, y);
        }
    }

    @Test
    @DisplayName("log10 matches on random arguments")
    void log10() {
        var random = new SplittableRandom(14);
        for (var i = 0; i < 20_000; i++) {
            check(WasmApmOracle.Libm.LOG10, "log10", (a, b) -> DoubleMath.log10(a), logUniform(random, -1074, 1023), 0);
        }
    }

    @Test
    @DisplayName("sin matches on random arguments within the ported reduction")
    void sin() {
        var random = new SplittableRandom(15);
        for (var i = 0; i < 20_000; i++) {
            var x = i % 2 == 0 ? random.nextDouble(-8, 8) : random.nextDouble(-1.6e6, 1.6e6);
            check(WasmApmOracle.Libm.SIN, "sin", (a, b) -> DoubleMath.sin(a), x, 0);
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(doubles = {0.0, -0.0, 1.0, -1.0, 0.5, 2.0, Double.MIN_VALUE, Double.MIN_NORMAL, Double.MAX_VALUE,
            Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NaN, 1e-300, 709.78, -745.2, 3.14159265,
            1.5707963267948966, 4.71238898038469, 6.283185307179586})
    @DisplayName("every function matches on special values")
    void specialValues(double x) {
        check(WasmApmOracle.Libm.EXP, "exp", (a, b) -> DoubleMath.exp(a), x, 0);
        check(WasmApmOracle.Libm.LOG, "log", (a, b) -> DoubleMath.log(a), x, 0);
        check(WasmApmOracle.Libm.LOG10, "log10", (a, b) -> DoubleMath.log10(a), x, 0);
        if (!(Math.abs(x) >= 1.6e6) || Double.isInfinite(x) || Double.isNaN(x)) {
            check(WasmApmOracle.Libm.SIN, "sin", (a, b) -> DoubleMath.sin(a), x, 0);
        }
        for (var y : new double[]{0.0, -0.0, 1.0, -1.0, 0.5, 2.0, 3.0, -3.0, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY, Double.NaN, 1e-30, 1e30}) {
            check(WasmApmOracle.Libm.POW, "pow", DoubleMath::pow, x, y);
            check(WasmApmOracle.Libm.POW, "pow", DoubleMath::pow, y, x);
        }
    }
}
