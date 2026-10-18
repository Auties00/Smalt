package com.github.auties00.smalt.audio.ac3.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * Checks the integration of the power spectral density over the bit allocation bands, which
 * advances runs of equal-width bands together, against the one-band-at-a-time definition of
 * A/52 sections 7.2.2.2 and 7.2.2.3 over random exponent sets with every kind of start and end.
 */
class BitAllocationIntegrationTest {
    @ParameterizedTest(name = "start {0}, end {1}")
    @CsvSource({"0, 253", "0, 7", "0, 100", "0, 1", "13, 253", "37, 181", "37, 253", "49, 229", "133, 253",
            "145, 241", "100, 120", "28, 253", "3, 30", "240, 253", "0, 252", "36, 191"})
    @DisplayName("the integrated density of every band matches the per-band log-addition")
    void integration(int start, int end) {
        var random = new Random(start * 977L + end);
        var allocation = new BitAllocation();
        for (var trial = 0; trial < 500; trial++) {
            var exponents = new int[256];
            var spread = 1 + random.nextInt(25);
            for (var bin = 0; bin < exponents.length; bin++) {
                exponents[bin] = random.nextInt(spread);
            }
            allocation.integrate(exponents, start, end);
            assertArrayEquals(reference(exponents, start, end), allocation.bandPsd, "trial " + trial);
        }
    }

    private static int[] reference(int[] exponents, int start, int end) {
        var expected = new int[51];
        for (var band = Ac3Tables.MASK[start]; band <= Ac3Tables.MASK[end - 1]; band++) {
            var from = Math.max(start, Ac3Tables.BAND_START[band]);
            var to = Math.min(end, Ac3Tables.BAND_START[band + 1]);
            var sum = 3072 - (exponents[from] << 7);
            for (var bin = from + 1; bin < to; bin++) {
                var power = 3072 - (exponents[bin] << 7);
                var address = Math.min(Math.abs(sum - power) >> 1, 255);
                sum = (sum > power ? sum : power) + Ac3Tables.LOG_ADD[address];
            }
            expected[band] = sum;
        }
        return expected;
    }
}
