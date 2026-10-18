package com.github.auties00.smalt.video.intra.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the bit writer and reader against each other and the variable-length code tables.
 */
@DisplayName("intra bitstream helpers")
class IntraBitsTest {
    @Test
    @DisplayName("the reader reads back what the writer wrote")
    void roundTrip() {
        var random = new Random(4);
        var writer = new IntraBitWriter(4);
        var values = new int[5000];
        var widths = new int[values.length];
        for (var i = 0; i < values.length; i++) {
            widths[i] = random.nextInt(33);
            values[i] = widths[i] == 0 ? 0 : (int) (random.nextLong() >>> (64 - widths[i]));
            writer.write(values[i], widths[i]);
        }
        var bits = writer.bitCount();
        writer.alignToByte();
        var bytes = writer.toByteArray();
        assertEquals((bits + 7) / 8, bytes.length);
        var reader = new IntraBitReader();
        reader.reset(bytes, 0, bytes.length);
        for (var i = 0; i < values.length; i++) {
            assertEquals(values[i], reader.read(widths[i]), "value " + i);
        }
        assertFalse(reader.overrun());
        reader.skip(64);
        assertTrue(reader.overrun());
        assertEquals(0, reader.window(), "reading past the array yields zeros");
    }

    @Test
    @DisplayName("a prefix code decodes every symbol and rejects unused patterns")
    void prefixCode() {
        var table = VlcTable.of(new String[]{"0", "10", "110", "1110000000001", "1110000000010"}, new int[]{7, 3, 9, 1, 2});
        for (var symbol : new int[]{7, 3, 9, 1, 2}) {
            var window = (long) table.code(symbol) << (64 - table.codeLength(symbol));
            var entry = table.lookup(window);
            assertEquals(symbol, VlcTable.symbol(entry));
            assertEquals(table.codeLength(symbol), VlcTable.length(entry));
        }
        assertEquals(0, VlcTable.length(table.lookup(-1L)));
        assertThrows(IllegalArgumentException.class, () -> VlcTable.of(new String[]{"0", "01"}, new int[]{0, 1}));
    }

    @Test
    @DisplayName("word reads eight big-endian bytes and zeros past the end of the array")
    void word() {
        var bytes = new byte[11];
        new Random(5).nextBytes(bytes);
        for (var index = 0; index < 14; index++) {
            var expected = 0L;
            for (var i = 0; i < 8; i++) {
                expected = expected << 8 | (index + i < bytes.length ? bytes[index + i] & 0xFF : 0);
            }
            assertEquals(expected, IntraBitReader.word(bytes, index), "index " + index);
        }
    }
}
