package com.github.auties00.smalt.audio.amr.internal.wbcommon;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Checks the shared AMR-WB pitch routines against a direct transcription of the TS 26.173 operators: the adaptive
 * codebook interpolation, whose wrapping fast path must equal the saturating sequential computation for every lag,
 * fraction and signal level, and the median of five values.
 */
class WbPitchOpsTest {
    private static final int HISTORY = 231 + 17;

    private static int lMac(int acc, short a, short b) {
        var product = a == Short.MIN_VALUE && b == Short.MIN_VALUE ? Integer.MAX_VALUE : a * b * 2;
        return (int) Math.clamp((long) acc + product, Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    private static void reference(short[] exc, int off, int t0, int frac, int length) {
        var x = off - t0;
        frac = -frac;
        if (frac < 0) {
            frac += 4;
            x--;
        }
        x -= 15;
        var k = (3 - frac) * 32;
        for (var j = 0; j < length; j++) {
            var sum = 0;
            for (var i = 0; i < 32; i++) {
                sum = lMac(sum, exc[x + j + i], WbCodecTables.INTER4_2[k + i]);
            }
            var doubled = (int) Math.clamp(2L * sum, Integer.MIN_VALUE, Integer.MAX_VALUE);
            exc[off + j] = (short) (Math.clamp((long) doubled + 0x8000, Integer.MIN_VALUE, Integer.MAX_VALUE) >> 16);
        }
    }

    private static short[] excitation(Random random, int level) {
        var exc = new short[HISTORY + 81];
        for (var i = 0; i < HISTORY; i++) {
            exc[i] = switch (level) {
                case 0 -> (short) (random.nextInt(2001) - 1000);
                case 1 -> (short) random.nextInt(65536);
                default -> random.nextBoolean() ? Short.MIN_VALUE : Short.MAX_VALUE;
            };
        }
        return exc;
    }

    @ParameterizedTest(name = "level {0}")
    @ValueSource(ints = {0, 1, 2})
    @DisplayName("interpolates the adaptive codebook exactly as the saturating reference")
    void predLt4(int level) {
        var random = new Random(26_173 + level);
        var scratch = new int[80];
        for (var t0 = 17; t0 <= 231; t0 += t0 < 100 ? 1 : 13) {
            for (var frac = -3; frac <= 3; frac++) {
                var exc = excitation(random, level);
                var expected = exc.clone();
                reference(expected, HISTORY, t0, frac, 65);
                WbPitchOps.predLt4(exc, HISTORY, t0, frac, 65, scratch);
                assertArrayEquals(expected, exc, "t0 " + t0 + " frac " + frac);
            }
        }
    }

    @Test
    @DisplayName("returns the median of five values")
    void median5() {
        var random = new Random(5);
        for (var trial = 0; trial < 10_000; trial++) {
            var values = new short[7];
            for (var i = 0; i < values.length; i++) {
                values[i] = (short) (random.nextInt(9) - 4 + (trial % 3 == 0 ? Short.MIN_VALUE + 4 : 0));
            }
            var sorted = Arrays.copyOfRange(values, 1, 6);
            Arrays.sort(sorted);
            assertEquals(sorted[2], WbPitchOps.median5(values, 3));
        }
    }
}
