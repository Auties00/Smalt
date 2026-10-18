package com.github.auties00.smalt.audio.ac3.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Checks the shortcuts the encoder takes through the parametric bit allocation against the
 * per-mantissa definitions: a spread finished under several dB per bit codes gives the masking
 * curve of each code, and the run tally of an exponent set gives the per-mantissa sum of the
 * weight of each pointer, over random exponent sets shaped like D15, D25 and D45 exponents.
 */
class BitAllocationShortcutsTest {
    @ParameterizedTest(name = "start {0}, end {1}, group {2}")
    @CsvSource({"0, 253, 1", "0, 253, 2", "0, 253, 4", "0, 7, 1", "37, 181, 1", "49, 229, 2", "133, 253, 4",
            "0, 100, 1", "145, 241, 4", "0, 1, 1"})
    @DisplayName("a spread finished per code and the run tally match the per-mantissa definitions")
    void shortcuts(int start, int end, int group) {
        var random = new Random(start * 31L + end * 7L + group);
        var allocation = new BitAllocation();
        var reference = new BitAllocation();
        var weights = new long[64];
        for (var address = 0; address < 64; address++) {
            weights[address] = random.nextInt(1 << 20) | (long) random.nextInt(1 << 10) << 32;
        }
        var runs = new ExponentRuns();
        var padded = ExponentRuns.padCost(weights);
        var pair = new long[2];
        for (var trial = 0; trial < 200; trial++) {
            var exponents = exponents(random, start, end, group);
            var fastGain = Ac3Tables.FAST_GAIN[random.nextInt(8)];
            var fastLeak = (random.nextInt(8) << 8) + 768;
            var slowLeak = (random.nextInt(8) << 8) + 768;
            var rate = random.nextInt(3);
            var codes = new BitAllocation.Parameters[4];
            for (var code = 0; code < 4; code++) {
                codes[code] = new BitAllocation.Parameters();
                codes[code].set(rate, 2, 1, 1, code, 4);
            }
            allocation.spread(exponents, start, end, codes[0], fastGain, fastLeak, slowLeak);
            for (var code = 0; code < 4; code++) {
                var expected = new int[51];
                var actual = new int[51];
                reference.mask(exponents, start, end, codes[code], fastGain, fastLeak, slowLeak, expected);
                allocation.finish(codes[code], actual);
                assertArrayEquals(expected, actual, "masking curve of code " + code);
            }
            var mask = new int[51];
            reference.mask(exponents, start, end, codes[random.nextInt(4)], fastGain, fastLeak, slowLeak, mask);
            runs.collect(exponents, start, end);
            for (var offset = 0; offset < 1024; offset += 1 + random.nextInt(40)) {
                var snr = (((offset >> 4) - 15 << 4) + (offset & 15)) << 2;
                var floor = Ac3Tables.FLOOR[random.nextInt(8)];
                var expected = perMantissa(exponents, start, end, mask, snr, floor, weights);
                assertEquals(expected, runs.tally(mask, snr, floor, padded), "tally at offset " + offset);
                var other = new int[51];
                reference.mask(exponents, start, end, codes[random.nextInt(4)], fastGain, fastLeak, slowLeak, other);
                ExponentRuns.tallyPair(exponents, start, end, mask, other, snr, floor, padded, pair);
                assertEquals(expected, pair[0], "pair tally A at offset " + offset);
                assertEquals(perMantissa(exponents, start, end, other, snr, floor, weights), pair[1],
                        "pair tally B at offset " + offset);
            }
        }
    }

    /**
     * Returns random exponents in {@code [start, end)}, constant over groups of {@code group}
     * mantissas, with long flat stretches and large steps both present.
     */
    private static int[] exponents(Random random, int start, int end, int group) {
        var exponents = new int[256];
        var value = random.nextInt(25);
        for (var bin = start; bin < end; bin++) {
            if ((bin - start) % group == 0 && random.nextInt(3) == 0) {
                value = random.nextInt(5) == 0 ? random.nextInt(25) : Math.clamp(value + random.nextInt(5) - 2, 0, 24);
            }
            exponents[bin] = value;
        }
        return exponents;
    }

    /**
     * Sums the weight of the pointer address of every mantissa as A/52 section 7.2.2.7 derives it.
     */
    private static long perMantissa(int[] exponents, int start, int end, int[] mask, int snr, int floor,
                                    long[] weights) {
        var sum = 0L;
        for (var bin = start; bin < end; bin++) {
            var level = Math.max(0, mask[Ac3Tables.MASK[bin]] - snr - floor);
            level = (level & 0x1fe0) + floor;
            var address = (3072 - (exponents[bin] << 7) - level) >> 5;
            sum += weights[Math.min(63, Math.max(0, address))];
        }
        return sum;
    }
}
