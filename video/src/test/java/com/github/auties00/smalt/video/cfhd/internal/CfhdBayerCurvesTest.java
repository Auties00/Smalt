package com.github.auties00.smalt.video.cfhd.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Holds the curve tables of {@link CfhdBayer#restoreTable(int)} to the SHA-256 digests of the tables GoPro's decoder
 * builds (tools/testdata/cfhd/curve-tables.c): 16384 little-endian 16-bit values per encode curve.
 */
@DisplayName("CineForm RAW curve tables")
class CfhdBayerCurvesTest {
    @Test
    @DisplayName("builds every linearizing table bit-exact with GoPro's decoder")
    void tables() throws Exception {
        String pins;
        try (var in = CfhdBayerCurvesTest.class.getResourceAsStream("curves.sha256")) {
            pins = new String(in.readAllBytes(), StandardCharsets.US_ASCII);
        }
        var checked = 0;
        for (var line : pins.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            var digest = line.substring(0, 64);
            var curve = Integer.parseUnsignedInt(line.substring(66, 74), 16);
            assertTrue(CfhdBayer.linearizes(curve), Integer.toHexString(curve));
            var table = CfhdBayer.restoreTable(curve);
            var bytes = new byte[2 * table.length];
            for (var i = 0; i < table.length; i++) {
                bytes[2 * i] = (byte) table[i];
                bytes[2 * i + 1] = (byte) (table[i] >>> 8);
            }
            var actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            assertEquals(digest, actual, "curve " + Integer.toHexString(curve));
            checked++;
        }
        assertEquals(20, checked);
    }

    @Test
    @DisplayName("keeps the coded photosites of the linear curve and of curve types it does not know")
    void notLinearized() {
        assertFalse(CfhdBayer.linearizes(0x00040101));
        assertFalse(CfhdBayer.linearizes(0x00036410));
        assertFalse(CfhdBayer.linearizes(0x00FF0101));
        assertThrows(IllegalArgumentException.class, () -> CfhdBayer.restoreTable(0x00040101));
    }

    @Test
    @DisplayName("maps the ends of the default log curve to black and to nearly full scale")
    void defaultCurve() {
        var table = CfhdBayer.restoreTable(0);
        assertEquals(0, table[0]);
        assertTrue(table[16383] > 65000, Integer.toString(table[16383]));
        for (var i = 1; i < table.length; i++) {
            assertTrue(table[i] >= table[i - 1], "monotonic at " + i);
        }
    }
}
