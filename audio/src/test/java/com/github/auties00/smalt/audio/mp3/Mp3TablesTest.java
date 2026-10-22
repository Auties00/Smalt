package com.github.auties00.smalt.audio.mp3;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Checks that the scalefactor band tables of every sample rate cover one granule of 576 coefficients, so the
 * decoder never reads past a channel's spectrum.
 */
class Mp3TablesTest {
    @ParameterizedTest(name = "sample rate row {0}")
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7})
    @DisplayName("long, short and mixed band tables each cover a granule")
    void granule(int row) {
        assertEquals(576, Arrays.stream(Mp3Tables.SFB_LONG[row]).sum());
        assertEquals(576, Arrays.stream(Mp3Tables.SFB_SHORT[row]).sum());
        // rows 5 to 7 are MPEG-1, with 8 long bands in a mixed block; the others have 6, and the 8 kHz row (1)
        // keeps four subbands long instead of two
        var longBands = row >= 5 ? 8 : 6;
        var longCoefficients = row == 1 ? 72 : 36;
        var mixed = Mp3Tables.SFB_MIXED[row];
        assertEquals(longCoefficients, Arrays.stream(mixed, 0, longBands).sum());
        var shortBands = 0;
        var shortCoefficients = 0;
        for (var band = longBands; mixed[band] != 0; band++) {
            shortCoefficients += mixed[band];
            shortBands++;
        }
        assertEquals(30, shortBands);
        assertEquals(576 - longCoefficients, shortCoefficients);
        // the long part repeats the start of the long table, the short part the end of the short table
        assertEquals(Arrays.toString(Arrays.copyOf(Mp3Tables.SFB_LONG[row], longBands)),
                Arrays.toString(Arrays.copyOf(mixed, longBands)));
        var shortTable = Mp3Tables.SFB_SHORT[row];
        assertEquals(Arrays.toString(Arrays.copyOfRange(shortTable, 39 - shortBands, 39)),
                Arrays.toString(Arrays.copyOfRange(mixed, longBands, longBands + shortBands)));
    }
}
