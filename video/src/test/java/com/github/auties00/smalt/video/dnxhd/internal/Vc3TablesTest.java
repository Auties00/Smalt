package com.github.auties00.smalt.video.dnxhd.internal;

import com.github.auties00.smalt.video.intra.internal.VlcTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the VC-3 tables transcribed from SMPTE ST 2019-1: every code is a complete prefix code
 * covering its symbol alphabet, the zig-zag order is a permutation, every compression ID has its
 * tables, and the RI frame sizes follow equation 7.1 (checked against frames ffmpeg writes).
 */
@DisplayName("VC-3 tables")
class Vc3TablesTest {
    @ParameterizedTest(name = "set {0}")
    @ValueSource(ints = {0, 1, 2, 3, 4, 5})
    @DisplayName("hold complete prefix codes over their alphabets")
    void codes(int set) {
        assertComplete(Vc3Tables.amplitude(set), 0, 1023, 257);
        assertComplete(Vc3Tables.run(set), 1, 62, 62);
        assertTrue(Vc3Tables.dc(set).codeLength(11) > 0);
    }

    static void assertComplete(VlcTable table, int first, int last, int expected) {
        var kraft = 0.0;
        var count = 0;
        for (var symbol = first; symbol <= last; symbol++) {
            var length = table.codeLength(symbol);
            if (length > 0) {
                kraft += Math.pow(2, -length);
                count++;
                var entry = table.lookup((long) table.code(symbol) << (64 - length));
                assertEquals(symbol, VlcTable.symbol(entry));
            }
        }
        assertEquals(expected, count);
        assertEquals(1.0, kraft, 1e-12);
    }

    @Test
    @DisplayName("zig-zag order is a permutation of the 64 positions")
    void zigzag() {
        var seen = new HashSet<Integer>();
        for (var position : Vc3Tables.ZIGZAG) {
            seen.add(position);
        }
        assertEquals(64, seen.size());
    }

    @Test
    @DisplayName("every compression ID has its weights and a consistent size")
    void compressions() {
        for (var compression : Vc3Compression.ALL) {
            assertEquals(64, Vc3Tables.weights(compression.weights(), false).length);
            assertTrue(compression.codingUnitSize(120, 68, false) >= 8192);
        }
        // the 400x200 DNxHR frames ffmpeg writes: LB 8192, SQ 24576, HQ 36864, 444 73728 bytes
        assertEquals(8192, Vc3Compression.of(1274).orElseThrow().codingUnitSize(25, 13, false));
        assertEquals(24576, Vc3Compression.of(1273).orElseThrow().codingUnitSize(25, 13, false));
        assertEquals(36864, Vc3Compression.of(1272).orElseThrow().codingUnitSize(25, 13, false));
        assertEquals(73728, Vc3Compression.of(1270).orElseThrow().codingUnitSize(25, 13, false));
        assertEquals(458752, Vc3Compression.of(1241).orElseThrow().codingUnitSize(120, 34, false));
    }

    @ParameterizedTest(name = "ID {0}")
    @org.junit.jupiter.params.provider.CsvSource({"1270, 11", "1271, 1", "1272, 3", "1273, 2", "1235, 1", "1256, 11", "1260, 7"})
    @DisplayName("quantizes DCT alpha with the luma table of the compression ID, as clause 6.3 says")
    void alphaWeights(int cid, int table) {
        var compression = Vc3Compression.of(cid).orElseThrow();
        assertEquals(table, compression.alphaWeights());
        assertEquals(compression.weights(), compression.alphaWeights());
    }
}
