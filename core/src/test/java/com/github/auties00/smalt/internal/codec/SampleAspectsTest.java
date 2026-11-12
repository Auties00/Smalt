package com.github.auties00.smalt.internal.codec;

import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the fitting of sample aspect ratios to bounded bitstream fields against an exhaustive search of the
 * fractions whose terms fit, and the MPEG-4 Visual and H.263 pixel aspect ratio codes.
 */
@DisplayName("Sample aspect ratio fitting")
class SampleAspectsTest {
    /**
     * Returns the error of the closest fraction with terms from 1 to {@code max}, found by trying every denominator.
     */
    static double bestError(double exact, long max) {
        var best = Double.MAX_VALUE;
        for (long q = 1; q <= max; q++) {
            var center = Math.round(exact * q);
            for (var p = center - 1; p <= center + 1; p++) {
                var clamped = Math.clamp(p, 1, max);
                best = Math.min(best, Math.abs((double) clamped / q - exact));
            }
        }
        return best;
    }

    @ParameterizedTest(name = "{0}/{1} within {2}")
    @CsvSource({"4, 3, 255, 4, 3", "128, 96, 255, 4, 3", "1000, 999, 255, 1, 1", "100000, 1, 255, 255, 1",
            "1, 100000, 255, 1, 255", "355, 113, 255, 245, 78", "65536, 65535, 65535, 65535, 65534",
            "16, 11, 65535, 16, 11"})
    @DisplayName("keeps a ratio whose reduced terms fit and approximates the others")
    void known(long numerator, long denominator, long max, long expectedNumerator, long expectedDenominator) {
        var fitted = SampleAspects.fit(new Rational(numerator, denominator), max);
        var exact = (double) numerator / denominator;
        assertTrue(fitted.numerator() <= max && fitted.denominator() <= max, fitted.toString());
        assertEquals(bestError(exact, max), Math.abs(fitted.toDouble() - exact), 1e-12, fitted.toString());
        assertEquals(new Rational(expectedNumerator, expectedDenominator), fitted);
    }

    @Test
    @DisplayName("finds a closest fraction for random ratios with terms too large for 8 bits")
    void random() {
        var random = new Random(0x5A11);
        for (var i = 0; i < 300; i++) {
            var numerator = 1 + random.nextLong(1, 5_000_000);
            var denominator = 1 + random.nextLong(1, 5_000_000);
            var fitted = SampleAspects.fit(new Rational(numerator, denominator), 255);
            var exact = (double) numerator / denominator;
            assertTrue(fitted.numerator() <= 255 && fitted.denominator() <= 255, fitted.toString());
            assertEquals(fitted, fitted.reduce());
            assertEquals(bestError(exact, 255), Math.abs(fitted.toDouble() - exact), 1e-12,
                    numerator + "/" + denominator + " -> " + fitted);
        }
    }

    @Test
    @DisplayName("maps the five coded pixel aspect ratios and the extended code")
    void codes() {
        int[][] table = {{1, 1}, {12, 11}, {10, 11}, {16, 11}, {40, 33}};
        for (var code = 1; code <= 5; code++) {
            var ratio = new Rational(table[code - 1][0], table[code - 1][1]);
            assertEquals(ratio, SampleAspects.pixelAspectOf(code));
            assertEquals(code, SampleAspects.pixelAspectCode(ratio));
        }
        assertEquals(SampleAspects.EXTENDED_PIXEL_ASPECT, SampleAspects.pixelAspectCode(new Rational(4, 3)));
        for (var code : new int[]{0, 6, 14, 15, -1}) {
            assertNull(SampleAspects.pixelAspectOf(code));
        }
        assertEquals(-1, SampleAspects.indexOf(new Rational(24, 22), table));
    }
}
