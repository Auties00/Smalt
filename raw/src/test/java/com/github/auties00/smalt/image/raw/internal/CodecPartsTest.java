package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Checks the pieces of the vendor decoders against hand-computed values: the cRAW block layout
 * and tone curve, the Olympus and least-significant-first bit packings, the DNG 24-bit float, and
 * the explicit-code Huffman tables.
 */
class CodecPartsTest {
    @Test
    @DisplayName("a cRAW block puts the maximum and minimum at their positions and scales the deltas")
    void crawBlock() {
        var max = 1500;
        var min = 1000;
        var maxIndex = 3;
        var minIndex = 9;
        var deltas = new int[14];
        for (var i = 0; i < 14; i++) {
            deltas[i] = i * 9;
        }
        var low = (long) max | (long) min << 11 | (long) maxIndex << 22 | (long) minIndex << 26;
        var high = 0L;
        var bit = 30;
        for (var delta : deltas) {
            if (bit + 7 <= 64) {
                low |= (long) delta << bit;
            } else if (bit >= 64) {
                high |= (long) delta << (bit - 64);
            } else {
                low |= (long) delta << bit;
                high |= (long) delta >>> (64 - bit);
            }
            bit += 7;
        }
        var bytes = new byte[16];
        for (var i = 0; i < 8; i++) {
            bytes[i] = (byte) (low >>> (8 * i));
            bytes[8 + i] = (byte) (high >>> (8 * i));
        }
        var values = new int[16];
        ArwParser.block(bytes, 0, values);
        assertEquals(max, values[maxIndex]);
        assertEquals(min, values[minIndex]);
        var next = 0;
        for (var i = 0; i < 16; i++) {
            if (i != maxIndex && i != minIndex) {
                assertEquals(min + (deltas[next++] << 2), values[i], "photosite " + i);
            }
        }
    }

    @Test
    @DisplayName("the cRAW tone curve doubles its step at each threshold")
    void toneCurve() {
        var curve = ArwParser.toneCurve(new long[]{8000, 10400, 12900, 14100});
        assertEquals(0, curve[0]);
        assertEquals(2000, curve[2000]);
        assertEquals(2002, curve[2001]);
        assertEquals(2000 + 600 * 2, curve[2600]);
        assertEquals(2000 + 1200 + 4, curve[2601]);
        assertEquals(16383, curve[4095]);
    }

    @Test
    @DisplayName("Olympus packing reads 12-bit samples most significant bit first from little-endian words")
    void olympusPacking() {
        var bytes = new byte[]{0x56, 0x34, 0x12, (byte) 0xAB, 0x00, 0x00, (byte) 0xEF, (byte) 0xCD};
        var out = new short[4];
        Unpack.msbFirstWords(bytes, 0, bytes.length, 12, out, 0, 4);
        assertArrayEquals(new short[]{(short) 0xAB1, 0x234, 0x56C, (short) 0xDEF}, out);
    }

    @Test
    @DisplayName("least-significant-first packing reads Fujifilm's 12-bit layout")
    void lsbPacking() {
        var out = new short[2];
        Unpack.lsbFirst(new byte[]{0x34, (byte) 0xC2, (byte) 0xAB}, 0, 3, 12, out, 0, 2);
        assertArrayEquals(new short[]{0x234, (short) 0xABC}, out);
    }

    @Test
    @DisplayName("DNG 24-bit floats convert exactly")
    void float24() {
        assertEquals(1.0f, TiffSamples.float24(64 << 16));
        assertEquals(-2.5f, TiffSamples.float24(1 << 23 | 65 << 16 | 0x4000));
        assertEquals(0f, TiffSamples.float24(0));
        assertEquals(Float.POSITIVE_INFINITY, TiffSamples.float24(0x7F << 16));
    }

    @Test
    @DisplayName("explicit Huffman codes convert to canonical tables and decode")
    void huffman() {
        var spec = Huffman.fromCodes(new int[]{0x800, 0x000, 0xC00}, new int[]{2, 1, 2}, 12);
        assertArrayEquals(new int[]{1, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 2}, spec);
        var bits = new MsbBits(new byte[]{(byte) 0b0101_1000}, 0, 1);
        var table = new MsbBits.Table(spec);
        assertEquals(1, bits.decode(table));
        assertEquals(0, bits.decode(table));
        assertEquals(2, bits.decode(table));
        assertThrows(MediaException.Malformed.class, () -> Huffman.fromCodes(new int[]{0x000, 0x000}, new int[]{1, 1}, 12));
    }
}
