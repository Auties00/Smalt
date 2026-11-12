package com.github.auties00.smalt.audio.celp.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the pulse position tables of the multi-pulse excitation against ISO/IEC 14496-3 Table 3.97 and, for every
 * combination of subframe, pulses and field width Tables 3.66, 3.67 and 3.70 use, against the bit allocation of the
 * ISO/IEC 14496-3 reference decoder and the rule that the pulses own every position of the subframe once.
 */
@DisplayName("CELP pulse tables")
class PulseTableTest {
    @Test
    @DisplayName("the 7 pulses of 80-sample subframes give Table 3.97")
    void table397() {
        var table = PulseTable.of(80, 7, 24);
        assertArrayEquals(new int[]{4, 4, 4, 3, 3, 3, 3}, table.bits);
        int[][] expected = {
                {0, 5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55, 60, 65, 70, 75},
                {1, 6, 11, 16, 21, 26, 31, 36, 41, 46, 51, 56, 61, 66, 71, 76},
                {2, 7, 12, 17, 22, 27, 32, 37, 42, 47, 52, 57, 62, 67, 72, 77},
                {3, 13, 23, 33, 43, 53, 63, 73},
                {4, 14, 24, 34, 44, 54, 64, 74},
                {8, 18, 28, 38, 48, 58, 68, 78},
                {9, 19, 29, 39, 49, 59, 69, 79}};
        for (var pulse = 0; pulse < expected.length; pulse++) {
            var sorted = table.positions[pulse].clone();
            Arrays.sort(sorted);
            assertArrayEquals(expected[pulse], sorted, "pulse " + pulse);
        }
        // Table 3.97 prints each pulse's positions sorted; the indices follow the procedure above it, track by track
        assertArrayEquals(new int[]{0, 10, 20, 30, 40, 50, 60, 70, 5, 15, 25, 35, 45, 55, 65, 75},
                table.positions[0]);
    }

    // subframe, pulses, field width, then the bits of each pulse that the reference decoder allocates (nec_pulse_bit)
    @ParameterizedTest(name = "{0} samples, {1} pulses, {2} bits")
    @CsvSource({
            "80, 3, 14, 5 5 4", "80, 4, 17, 5 4 4 4", "80, 5, 20, 4 4 4 4 4", "80, 6, 22, 4 4 4 4 3 3",
            "80, 7, 24, 4 4 4 3 3 3 3", "80, 8, 26, 4 4 3 3 3 3 3 3", "80, 9, 28, 4 3 3 3 3 3 3 3 3",
            "80, 10, 30, 3 3 3 3 3 3 3 3 3 3", "80, 11, 31, 3 3 3 3 3 3 3 3 3 2 2",
            "80, 12, 32, 3 3 3 3 3 3 3 3 2 2 2 2",
            "40, 4, 13, 4 3 3 3", "40, 5, 15, 3 3 3 3 3", "40, 6, 16, 3 3 3 3 2 2", "40, 7, 17, 3 3 3 2 2 2 2",
            "40, 8, 18, 3 3 2 2 2 2 2 2", "40, 9, 19, 3 2 2 2 2 2 2 2 2", "40, 10, 20, 2 2 2 2 2 2 2 2 2 2",
            "40, 11, 20, 2 2 2 2 2 2 2 2 2 1 1", "40, 12, 20, 2 2 2 2 2 2 2 2 1 1 1 1",
            "40, 3, 11, 4 4 3", "60, 6, 19, 4 4 3 3 3 2",
    })
    @DisplayName("allocates the bits as the reference decoder and owns every position once")
    void allocation(int subframe, int pulses, int width, String bits) {
        var table = PulseTable.of(subframe, pulses, width);
        var expected = new int[pulses];
        var parts = bits.split(" ");
        for (var i = 0; i < pulses; i++) {
            expected[i] = Integer.parseInt(parts[i]);
        }
        assertArrayEquals(expected, table.bits);
        var owners = new int[subframe];
        for (var pulse = 0; pulse < pulses; pulse++) {
            assertEquals(1 << table.bits[pulse], table.positions[pulse].length);
            for (var position : table.positions[pulse]) {
                assertTrue(position < subframe, "position " + position);
                owners[position]++;
            }
        }
        for (var position = 0; position < subframe; position++) {
            assertEquals(1, owners[position], "position " + position);
        }
    }
}
