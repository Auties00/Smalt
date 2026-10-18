package com.github.auties00.smalt.image.jxl.internal.bitstream;

import com.github.auties00.smalt.MediaException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks the JPEG XL bit reader against streams packed least significant bit first: raw bits,
 * the U32, U64 and F16 field encodings, byte padding and truncation.
 */
class JxlBitReaderTest {
    /** Packs values least significant bit first, as the codestream stores them. */
    static final class Bits {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        long buffer;
        int count;

        Bits put(long value, int length) {
            for (var i = 0; i < length; i++) {
                buffer |= ((value >>> i) & 1) << count;
                if (++count == 8) {
                    out.write((int) buffer);
                    buffer = 0;
                    count = 0;
                }
            }
            return this;
        }

        JxlBitReader reader() {
            var copy = new ByteArrayOutputStream();
            copy.writeBytes(out.toByteArray());
            if (count > 0) {
                copy.write((int) buffer);
            }
            var bytes = copy.toByteArray();
            return new JxlBitReader(bytes, 0, bytes.length);
        }
    }

    @Test
    @DisplayName("reads raw bits least significant first across byte boundaries")
    void rawBits() {
        var reader = new Bits().put(5, 3).put(0x1234, 16).put(1, 1).put(0x7FFFFFFFL, 31).reader();
        assertEquals(5, reader.readBits(3));
        assertEquals(0x1234, reader.readBits(16));
        assertTrue(reader.readBool());
        assertEquals(0x7FFFFFFF, reader.readBits(31));
        assertEquals(51, reader.bitPosition());
    }

    @ParameterizedTest(name = "selector {0}")
    @CsvSource({"0, 0, 0", "1, 0, 3", "2, 37, 41", "3, 1000, 1001"})
    @DisplayName("decodes each U32 distribution")
    void u32(int selector, int raw, int expected) {
        // U32(Val(0), Val(3), BitsOffset(6, 4), BitsOffset(10, 1))
        var bits = new Bits().put(selector, 2);
        if (selector == 2) {
            bits.put(raw, 6);
        } else if (selector == 3) {
            bits.put(raw, 10);
        }
        assertEquals(expected, bits.reader().readU32(0, 0, 3, 0, 4, 6, 1, 10));
    }

    @Test
    @DisplayName("decodes every U64 form")
    void u64() {
        var bits = new Bits().put(0, 2)
                .put(1, 2).put(9, 4)
                .put(2, 2).put(200, 8)
                .put(3, 2).put(0xABC, 12).put(1, 1).put(0x5D, 8).put(0, 1);
        var reader = bits.reader();
        assertEquals(0, reader.readU64());
        assertEquals(10, reader.readU64());
        assertEquals(217, reader.readU64());
        assertEquals(0xABC | (0x5DL << 12), reader.readU64());
    }

    @Test
    @DisplayName("decodes half-precision floats and rejects infinities")
    void f16() {
        var reader = new Bits().put(0x3C00, 16).put(0xC100, 16).put(0x0001, 16).put(0x7C00, 16).reader();
        assertEquals(1.0f, reader.readF16());
        assertEquals(-2.5f, reader.readF16());
        assertEquals(0x1p-24f, reader.readF16());
        assertThrows(MediaException.Malformed.class, reader::readF16);
    }

    @Test
    @DisplayName("pads to the next byte and fails past the end")
    void paddingAndTruncation() {
        var reader = new Bits().put(1, 3).put(0, 5).put(0xAA, 8).reader();
        reader.readBits(3);
        reader.zeroPadToByte();
        assertEquals(8, reader.bitPosition());
        assertEquals(0xAA, reader.readBits(8));
        assertThrows(MediaException.Malformed.class, () -> reader.readBits(1));
    }
}
