package com.github.auties00.smalt.container.avi;

import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigInteger;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the 16-bit {@code dwFrameAspectRatio} of the AVI video properties header: exact ratios are
 * stored exactly, and the approximation of the others is the best one, checked against a search of
 * every denominator.
 */
@DisplayName("AVI frame aspect ratio")
class AviFrameAspectTest {
    @ParameterizedTest(name = "{0}x{1} sar {2}/{3}")
    @CsvSource({"176, 144, 16, 11, 16, 9", "720, 576, 64, 45, 16, 9", "720, 480, 8, 9, 4, 3", "176, 144, 1, 1, 11, 9",
            "1920, 1080, 1, 1, 16, 9", "1440, 1080, 4, 3, 16, 9"})
    @DisplayName("stores an exact display aspect ratio in lowest terms, horizontal term high")
    void exact(int width, int height, long numerator, long denominator, long horizontal, long vertical) {
        assertEquals(horizontal << 16 | vertical, AviMuxer.frameAspect(width, height, new Rational(numerator, denominator)));
    }

    @Test
    @DisplayName("stores nothing for an empty picture")
    void empty() {
        assertEquals(0, AviMuxer.frameAspect(0, 144, new Rational(1, 1)));
    }

    /**
     * Returns the closest fraction with both terms from 1 to 65535 by trying every denominator.
     */
    static long[] bruteForce(BigInteger numerator, BigInteger denominator) {
        long[] best = null;
        BigInteger bestErrorNumerator = null;
        long bestErrorDenominator = 1;
        for (long q = 1; q <= 0xFFFF; q++) {
            var scaled = numerator.multiply(BigInteger.valueOf(q));
            var floor = scaled.divide(denominator).longValue();
            for (var p = Math.max(1, floor); p <= Math.min(0xFFFF, floor + 1); p++) {
                var error = scaled.subtract(denominator.multiply(BigInteger.valueOf(p))).abs();
                if (best == null || error.multiply(BigInteger.valueOf(bestErrorDenominator))
                        .compareTo(bestErrorNumerator.multiply(BigInteger.valueOf(q))) < 0) {
                    best = new long[]{p, q};
                    bestErrorNumerator = error;
                    bestErrorDenominator = q;
                }
            }
            if (floor > 0xFFFF) {
                break;
            }
        }
        return best;
    }

    static boolean sameError(BigInteger numerator, BigInteger denominator, long[] first, long[] second) {
        var left = numerator.multiply(BigInteger.valueOf(first[1])).subtract(denominator.multiply(BigInteger.valueOf(first[0])))
                .abs().multiply(BigInteger.valueOf(second[1]));
        var right = numerator.multiply(BigInteger.valueOf(second[1])).subtract(denominator.multiply(BigInteger.valueOf(second[0])))
                .abs().multiply(BigInteger.valueOf(first[1]));
        return left.equals(right);
    }

    @Test
    @DisplayName("approximates ratios with terms over 16 bits as well as the best fraction")
    void approximation() {
        var random = new Random(20261016);
        for (var sample = 0; sample < 60; sample++) {
            var numerator = BigInteger.valueOf(65_536 + random.nextInt(1 << 24));
            var denominator = BigInteger.valueOf(65_536 + random.nextInt(1 << 24));
            if (sample % 3 == 0) {
                denominator = BigInteger.valueOf(1 + random.nextInt(4096));
            }
            var gcd = numerator.gcd(denominator);
            numerator = numerator.divide(gcd);
            denominator = denominator.divide(gcd);
            var ours = AviMuxer.closest(numerator, denominator);
            assertTrue(ours[0] >= 1 && ours[0] <= 0xFFFF && ours[1] >= 1 && ours[1] <= 0xFFFF);
            var best = bruteForce(numerator, denominator);
            assertTrue(sameError(numerator, denominator, ours, best),
                    numerator + "/" + denominator + ": " + ours[0] + "/" + ours[1] + " vs " + best[0] + "/" + best[1]);
        }
    }

    @Test
    @DisplayName("clamps ratios beyond the 16-bit range to its ends")
    void extremes() {
        var huge = AviMuxer.closest(BigInteger.valueOf(10_000_000), BigInteger.ONE);
        assertEquals(0xFFFF, huge[0]);
        assertEquals(1, huge[1]);
        var tiny = AviMuxer.closest(BigInteger.ONE, BigInteger.valueOf(10_000_000));
        assertEquals(1, tiny[0]);
        assertEquals(0xFFFF, tiny[1]);
    }
}
