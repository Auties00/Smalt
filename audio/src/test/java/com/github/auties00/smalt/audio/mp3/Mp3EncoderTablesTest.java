package com.github.auties00.smalt.audio.mp3;

import com.github.auties00.smalt.internal.bits.MsbBitWriter;
import com.github.auties00.smalt.internal.header.Crc16;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Mp3EncoderTablesTest {
    @Test
    @DisplayName("derives the code words of ISO/IEC 11172-3 Table B.7 for tables 1, A and B")
    void knownCodes() {
        // table 1: (0,0)=1, (0,1)=001, (1,0)=01, (1,1)=000
        assertEquals(1, Mp3EncoderTables.PAIR_CODES[1][0]);
        assertEquals(1, Mp3EncoderTables.PAIR_LENGTHS[1][0]);
        assertEquals(1, Mp3EncoderTables.PAIR_CODES[1][1]);
        assertEquals(3, Mp3EncoderTables.PAIR_LENGTHS[1][1]);
        assertEquals(1, Mp3EncoderTables.PAIR_CODES[1][16]);
        assertEquals(2, Mp3EncoderTables.PAIR_LENGTHS[1][16]);
        assertEquals(0, Mp3EncoderTables.PAIR_CODES[1][17]);
        assertEquals(3, Mp3EncoderTables.PAIR_LENGTHS[1][17]);
        // table A: 0000 -> 1, 0001 -> 0101; table B codes the complement in four bits
        assertEquals(1, Mp3EncoderTables.QUAD_LENGTHS[0][0]);
        assertEquals(0b0101, Mp3EncoderTables.QUAD_CODES[0][1]);
        for (var q = 0; q < 16; q++) {
            assertEquals(15 - q, Mp3EncoderTables.QUAD_CODES[1][q]);
            assertEquals(4, Mp3EncoderTables.QUAD_LENGTHS[1][q]);
        }
    }

    @ParameterizedTest(name = "table {0}")
    @ValueSource(ints = {1, 2, 3, 5, 6, 7, 8, 9, 10, 11, 12, 13, 15, 16, 24})
    @DisplayName("every big-value table is a complete prefix code over its value range")
    void complete(int table) {
        var range = Mp3EncoderTables.TABLE_RANGE[table];
        var kraft = 0.0;
        var words = new HashSet<String>();
        for (var x = 0; x < range; x++) {
            for (var y = 0; y < range; y++) {
                var length = Mp3EncoderTables.PAIR_LENGTHS[table][x * 16 + y];
                assertTrue(length > 0 && length <= 19);
                kraft += Math.pow(2, -length);
                var word = Integer.toBinaryString(Mp3EncoderTables.PAIR_CODES[table][x * 16 + y] | 1 << length).substring(1);
                for (var other : words) {
                    assertTrue(!word.startsWith(other) && !other.startsWith(word), word + " and " + other);
                }
                words.add(word);
            }
        }
        assertEquals(1.0, kraft, 1e-12);
    }

    @Test
    @DisplayName("the analysis window peaks at 256, is antisymmetric in magnitude and matches the synthesis window")
    void window() {
        var c = Mp3EncoderTables.ANALYSIS_WINDOW;
        assertEquals(512, c.length);
        assertEquals(75038, c[256]);
        for (var i = 1; i < 256; i++) {
            assertEquals(Math.abs(c[i]), Math.abs(c[512 - i]), "index " + i);
        }
        assertEquals(c[1], (int) Mp3Tables.SYNTH_WINDOW[0]);
    }

    @Test
    @DisplayName("the band layouts cover 576 lines and match the standard tables")
    void bands() {
        for (var rate = 0; rate < 9; rate++) {
            var bounds = Mp3EncoderTables.longBands(rate);
            assertEquals(0, bounds[0]);
            assertEquals(576, bounds[22]);
            var shortBounds = Mp3EncoderTables.shortBands(rate);
            assertEquals(192, shortBounds[13]);
            var layout = new Mp3BandLayout(rate, true);
            var seen = new boolean[576];
            for (var line : layout.order) {
                seen[line] = true;
            }
            for (var flag : seen) {
                assertTrue(flag);
            }
        }
        assertArrayEquals(new int[]{0, 4, 8, 12, 16, 20, 24, 30, 36, 44, 52, 62, 74, 90, 110, 134, 162, 196, 238, 288, 342, 418, 576},
                Mp3EncoderTables.longBands(0));
        assertArrayEquals(new int[]{0, 4, 8, 12, 16, 22, 30, 40, 52, 66, 84, 106, 136, 192}, Mp3EncoderTables.shortBands(0));
    }

    @Test
    @DisplayName("the frame CRC is CRC-16 with polynomial 0x8005 and initial value 0xFFFF")
    void crc() {
        var frame = new byte[]{(byte) 0xFF, (byte) 0xFB, '1', '2', 0, 0, '3', '4', '5', '6', '7', '8', '9'};
        // the check value of this CRC over "123456789": the last two header bytes, then the side information
        assertEquals(0xAEE7, Crc16.crc16(Crc16.crc16(0xFFFF, frame, 2, 2), frame, 6, 7));
    }

    @Test
    @DisplayName("the Huffman coder counts exactly the bits it writes, for every region split and table")
    void countsMatchWrites() {
        var random = new java.util.SplittableRandom(5);
        var layout = new Mp3BandLayout(0, false);
        var shortLayout = new Mp3BandLayout(0, true);
        var huffman = new Mp3Huffman();
        for (var trial = 0; trial < 400; trial++) {
            var granule = new Mp3CodedGranule();
            granule.blockType = trial % 4;
            var scale = new int[]{1, 2, 4, 16, 40, 9000}[trial % 6];
            var end = random.nextInt(577);
            for (var i = 0; i < end; i++) {
                var magnitude = (int) Math.min(8206, Math.abs(random.nextGaussian()) * scale * Math.exp(-i / 150.0));
                granule.values[i] = random.nextBoolean() ? magnitude : -magnitude;
            }
            var widths = granule.blockType == Mp3CodedGranule.SHORT ? shortLayout.widths : layout.widths;
            for (var search : new boolean[]{false, true}) {
                var bits = huffman.code(granule, widths, search);
                var writer = new MsbBitWriter(64);
                huffman.write(granule, widths, writer);
                assertEquals(bits, writer.bitCount(), "trial " + trial);
            }
        }
    }
}
