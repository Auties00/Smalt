package com.github.auties00.smalt.internal.provider.image;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the bounded rational approximation the APNG animation writer stores durations with,
 * against a brute-force search over denominators.
 */
class AnimationWriterRoundingTest {
    @ParameterizedTest(name = "{0}")
    @ValueSource(doubles = {0, 0.033333, 0.0333666, Math.PI, Math.E / 1000, 65_534.5, 1e-7, 0.123456, 65_535,
            7.0 / 65_535, 0.5, 2.0 / 3})
    @DisplayName("the approximation is the closest fraction of 16-bit terms")
    void closest(double value) {
        var fraction = ApngAnimationWriter.approximate(value, 65_535);
        var numerator = (int) (fraction >>> 32);
        var denominator = (int) fraction;
        assertTrue(numerator >= 0 && numerator <= 65_535 && denominator >= 1 && denominator <= 65_535,
                numerator + "/" + denominator);
        var error = Math.abs(value - (double) numerator / denominator);
        for (var d = 1; d <= 65_535; d++) {
            var n = Math.min(65_535, Math.round(value * d));
            assertTrue(error <= Math.abs(value - (double) n / d) + 1e-15,
                    value + ": " + numerator + "/" + denominator + " is worse than " + n + "/" + d);
        }
    }
}
