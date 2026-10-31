package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.raw.CfaPattern;
import com.github.auties00.smalt.image.raw.RawSamples;
import com.github.auties00.smalt.io.MediaSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Checks, without the converter's files, the decoding of the formats {@link SampleGapsOracleTest} checks against
 * them: the Pentax table a maker note without one implies, the size of the largest mRAW files, the white balance of
 * synthetic encrypted Sony SR2 blocks (among them blocks holding the encrypted levels of the CC0 files), and synthetic
 * Panasonic data of raw format versions 6 (12-bit), 7 and 8 (shifts, ceiling and origins, tiles in rows).
 */
class SampleGapsTest {
    @Test
    @DisplayName("a K10D file without its maker note's table decodes as with it, its table being the implied one")
    void pentaxImpliedTable() {
        var original = read(SampleGapsOracleTest.patched("patched-pentax-k10d-no-table"));
        try (var source = MediaSource.of(RawSamples.PEF_K10D.fetch())) {
            var file = RawParser.parse(source, MediaLimits.DEFAULT);
            var plane = SensorPlane.of(file.sensor, MediaLimits.DEFAULT);
            file.sensor.reader.read(plane, MediaLimits.DEFAULT);
            assertArrayEquals(plane.integers, original.integers);
        }
        var k2000 = read(SampleGapsOracleTest.patched("patched-pentax-k2000-no-table"));
        try (var source = MediaSource.of(RawSamples.PEF_K2000.fetch())) {
            var file = RawParser.parse(source, MediaLimits.DEFAULT);
            var plane = SensorPlane.of(file.sensor, MediaLimits.DEFAULT);
            file.sensor.reader.read(plane, MediaLimits.DEFAULT);
            // the K2000 embeds another table, so its data read with the implied one differs
            assertNotEquals(java.util.Arrays.hashCode(plane.integers), java.util.Arrays.hashCode(k2000.integers));
        }
    }

    @Test
    @DisplayName("the mRAW file of a 5DS decodes at its full size under the default limits")
    void largeMraw() {
        try (var source = MediaSource.of(RawSamples.CR2_5DS_MRAW.fetch())) {
            var file = RawParser.parse(source, MediaLimits.DEFAULT);
            assertNotNull(file.sensor, file.unsupported);
            assertEquals(6480, file.sensor.width);
            assertEquals(4320, file.sensor.height);
            var plane = SensorPlane.of(file.sensor, MediaLimits.DEFAULT);
            file.sensor.reader.read(plane, MediaLimits.DEFAULT);
        }
    }

    @Test
    @DisplayName("the as-shot white balance of an encrypted SR2 block of a known layout is read, of another rejected")
    void sr2Balance() {
        // the first directory points to the SR2Private directory, which locates a 29000-byte block of the layout
        // that starts with 157 and keeps the as-shot levels, XORed with its keystream, at 2434
        var block = new byte[29000];
        block[0] = (byte) 157;
        int[] keystream = {0x14, 0x4a, 0x05, 0x3b, 0x0d, 0xe6, 0xe1, 0xc9};
        int[] levels = {2400, 1024, 1024, 1600};
        for (var k = 0; k < 4; k++) {
            block[2434 + 2 * k] = (byte) ((levels[k] & 0xFF) ^ keystream[2 * k]);
            block[2435 + 2 * k] = (byte) ((levels[k] >> 8) ^ keystream[2 * k + 1]);
        }
        var neutral = sr2(block, 1144201745L);
        assertNotNull(neutral);
        assertArrayEquals(new double[]{1024 / 2400.0, 1, 1024 / 1600.0}, neutral, 1e-12);
        assertEquals(null, sr2(block, 1144201746L), "another key");
        assertEquals(null, sr2(java.util.Arrays.copyOf(block, 2441), 1144201745L), "a block ending inside the levels");
        var negative = block.clone();
        negative[2441] ^= (byte) 0x80;
        assertEquals(null, sr2(negative, 1144201745L), "a negative blue level");
        var zero = block.clone();
        zero[2434] = (byte) keystream[0];
        zero[2435] = (byte) keystream[1];
        assertEquals(null, sr2(zero, 1144201745L), "a zero red level");
        block[0] = 1;
        assertEquals(null, sr2(block, 1144201745L), "another layout");
    }

    @Test
    @DisplayName("an SR2 structure that points outside the file leaves the white balance unread without failing")
    void sr2Outside() {
        var block = new byte[29000];
        block[0] = (byte) 157;
        // the block length claims more bytes than the file holds: the levels at 2434 lie outside it
        var truncated = java.util.Arrays.copyOf(block, 2000);
        assertEquals(null, sr2(truncated, 1144201745L, 256, block.length, 26));
        // the block offset lies past the end of the file
        assertEquals(null, sr2(block, 1144201745L, 1 << 30, block.length, 26));
        // the SR2Private directory offset lies past the end of the file, or is zero
        assertEquals(null, sr2(block, 1144201745L, 256, block.length, 1 << 30));
        assertEquals(null, sr2(block, 1144201745L, 256, block.length, 0));
        // a zero length
        assertEquals(null, sr2(block, 1144201745L, 256, 0, 26));
        // a DNGPrivateData tag whose values lie past the end of the file
        assertEquals(null, sr2(block, 1144201745L, 256, block.length, 1 << 30, 1 << 20));
    }

    // the CC0 DSLR files of the raw.pixls.us archive whose raw directory has no WB_RGGBLevels tag: model, the block's
    // first byte and length, the eight encrypted bytes at its layout's as-shot offset, and the red and blue of the
    // neutral Adobe DNG Converter stores (green 1); the A450 and A700 files gave the keystream of the layouts of first
    // bytes 224 and 220, the other files check it
    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "DSC-RX0, 133, 56958, 4b2d715b88b77d72, 0.498054, 0.536688",
            "ILCE-7M2, 133, 56958, cb20715b88b7817e, 0.723164, 0.346883",
            "NEX-6, 133, 29252, 272c715b88b7a573, 0.425957, 0.599532",
            "DSLR-A200, 220, 41456, fd27b6a41fc1ee3a, 0.742029, 0.32",
            "DSLR-A230, 224, 30960, f164edd30da847d9, 0.577878, 0.619855",
            "DSLR-A290, 224, 30960, 6d69edd30da89fda, 0.349727, 0.705234",
            "DSLR-A350, 220, 41456, 5d28b6a41fc1d633, 0.371553, 0.699454",
            "DSLR-A380, 224, 30960, f96bedd30da8abd9, 0.404423, 0.606635",
            "DSLR-A390, 224, 30960, 5168edd30da863d9, 0.388467, 0.633663",
            "DSLR-A450, 224, 27152, d16bedd30da8dbda, 0.408293, 0.677249",
            "DSLR-A500, 224, 27152, b96aedd30da8bbda, 0.462929, 0.723164",
            "DSLR-A560, 157, 29000, 9443053f0de201cc, 0.421053, 0.680851",
            "DSLR-A700, 220, 62112, c92ab6a41fc17230, 0.481203, 0.654731",
            "DSLR-A900, 224, 53248, 9d68edd30da8dfda, 0.380952, 0.675462",
            "NEX-5, 157, 29000, 0043053f0de239cc, 0.44062, 0.684492",
            "SLT-A33, 157, 29000, 3c43053f0de205cc, 0.43686, 0.679045"})
    @DisplayName("the encrypted levels of the CC0 files decrypt to the converter's white balance")
    void sr2Files(String model, int first, int length, String encrypted, double red, double blue) {
        var offsets = java.util.Map.of(133, 2526, 157, 2434, 220, 1654, 224, 2182);
        var block = new byte[length];
        block[0] = (byte) first;
        var bytes = java.util.HexFormat.of().parseHex(encrypted);
        System.arraycopy(bytes, 0, block, offsets.get(first), bytes.length);
        var neutral = sr2(block, 1144201745L);
        assertNotNull(neutral, model);
        assertArrayEquals(new double[]{red, 1, blue}, neutral, 1e-6, model);
        // one flipped bit of the encrypted red level moves the red of the neutral
        block[offsets.get(first)] ^= 1;
        assertNotEquals(red, sr2(block, 1144201745L)[0], 1e-7, model);
    }

    // a little-endian TIFF structure of a first directory with DNGPrivateData, an SR2Private directory and the block
    private static double[] sr2(byte[] block, long key) {
        return sr2(block, key, 256, block.length, 26);
    }

    // the same structure with the block offset and length and the SR2Private directory offset given
    private static double[] sr2(byte[] block, long key, int offset, int length, int directory) {
        return sr2(block, key, offset, length, directory, 4);
    }

    // the same structure with the value count of the DNGPrivateData tag given too
    private static double[] sr2(byte[] block, long key, int offset, int length, int directory, int count) {
        var out = java.nio.ByteBuffer.allocate(256 + block.length).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        out.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(8);
        out.putShort((short) 1);
        out.putShort((short) 0xC634).putShort((short) 1).putInt(count).putInt(directory);
        out.putInt(0);
        out.position(26);
        out.putShort((short) 3);
        out.putShort((short) 0x7200).putShort((short) 4).putInt(1).putInt(offset);
        out.putShort((short) 0x7201).putShort((short) 4).putInt(1).putInt(length);
        out.putShort((short) 0x7221).putShort((short) 7).putInt(4).putInt((int) key);
        out.putInt(0);
        out.position(256);
        out.put(block);
        try (var source = MediaSource.of(out.array())) {
            var reader = TiffReader.open(source, 0, MediaLimits.DEFAULT);
            return Sr2Balance.neutral(reader, reader.directory(reader.firstDirectory()));
        }
    }

    @Test
    @DisplayName("version 7 stores nine 14-bit photosites per block, least significant bits first, without chunks")
    void version7() {
        var values = new int[18];
        for (var k = 0; k < values.length; k++) {
            values[k] = 1 + (k * 2731 + 5) % 16383;
        }
        var data = new byte[32];
        for (var block = 0; block < 2; block++) {
            var bit = 0;
            for (var k = 0; k < 9; k++, bit += 14) {
                for (var b = 0; b < 14; b++) {
                    if ((values[block * 9 + k] >> b & 1) != 0) {
                        data[block * 16 + (bit + b) / 8] |= (byte) (1 << ((bit + b) % 8));
                    }
                }
            }
        }
        var plane = new SensorPlane(9, 2, 1, false, MediaLimits.DEFAULT);
        PanasonicDecoder.decode(data, 7, 14, plane, CfaPattern.bayer("RGGB"));
        for (var k = 0; k < values.length; k++) {
            assertEquals(values[k], plane.integers[k], "photosite " + k);
        }
    }

    @Test
    @DisplayName("version 6 12-bit blocks hold two values and four groups of a code and three 8-bit fields")
    void version6Twelve() {
        // two 12-bit values, then groups of code 0, 1, 2 and 3, read most significant bit first from the reversed block
        var bits = new StringBuilder();
        bits.append(binary(1000, 12)).append(binary(2000, 12));
        int[][] fields = {{100, 0, 120}, {130, 140, 150}, {160, 170, 180}, {10, 20, 30}};
        int[] codes = {0, 1, 2, 3};
        for (var group = 0; group < 4; group++) {
            bits.append(binary(codes[group], 2));
            for (var field : fields[group]) {
                bits.append(binary(field, 8));
            }
        }
        var block = new byte[16];
        for (var index = 0; index < 128; index++) {
            if (bits.charAt(index) == '1') {
                block[index / 8] |= (byte) (0x80 >> (index % 8));
            }
        }
        var stored = new byte[16];
        for (var index = 0; index < 16; index++) {
            stored[15 - index] = block[index];
        }
        var plane = new SensorPlane(14, 1, 1, false, MediaLimits.DEFAULT);
        PanasonicDecoder.decode(stored, 6, 12, plane, CfaPattern.bayer("RGGB"));
        var expected = new int[14];
        expected[0] = 1000;
        expected[1] = 2000;
        var index = 2;
        for (var group = 0; group < 4; group++) {
            for (var field : fields[group]) {
                expected[index] = PanasonicDecoder.step(field, codes[group], expected[index - 2]);
                index++;
            }
        }
        for (var k = 0; k < 14; k++) {
            // every value is lowered by the pedestal of 15
            assertEquals(Math.max(0, expected[k] - PanasonicDecoder.PEDESTAL), plane.integers[k] & 0xFFFF,
                    "photosite " + k);
        }
    }

    @Test
    @DisplayName("version 8 shifts differences, takes the sign of an unstored one from the next bit, clamps to the "
            + "ceiling and starts each tile from the cell origins")
    void version8Parameters() {
        // value c has the 4-bit code c for c < 15, then 11110 and 11111
        var codes = new int[17];
        var lengths = new int[17];
        for (var value = 0; value < 15; value++) {
            codes[value] = value;
            lengths[value] = 4;
        }
        codes[15] = 0b11110;
        codes[16] = 0b11111;
        lengths[15] = 5;
        lengths[16] = 5;
        var lookup = PanasonicDecoder.prefixTable(codes, lengths);
        var shifts = new int[17];
        shifts[3] = 1;
        shifts[2] = 2;
        shifts[5] = 7;
        var origins = new int[]{100, 200, 300, 400};
        var ceiling = 1000;
        // two tiles of 2x2 photosites, one above the other; each photosite a code and its stored bits
        var symbols = new ArrayList<Symbol>();
        symbols.add(new Symbol(3, 0b10, 2));
        symbols.add(new Symbol(3, 0b01, 2));
        symbols.add(new Symbol(2, 0, 0));
        symbols.add(new Symbol(5, 0, 0));
        symbols.add(new Symbol(10, 0b1111111111, 10));
        symbols.add(new Symbol(0, 0, 0));
        symbols.add(new Symbol(4, 0b0011, 4));
        symbols.add(new Symbol(2, 0, 0));
        var stream = new ByteArrayOutputStream();
        stream.writeBytes(encode(symbols.subList(0, 4), codes, lengths));
        var offset = stream.size();
        stream.writeBytes(encode(symbols.subList(4, 8), codes, lengths));
        var data = stream.toByteArray();
        var tiles = List.of(new PanasonicDecoder.Tile(0, 0, 0, 2, 2), new PanasonicDecoder.Tile(offset, 0, 2, 2, 2));
        var plane = new SensorPlane(2, 4, 1, false, MediaLimits.DEFAULT);
        try (var source = MediaSource.of(data)) {
            PanasonicDecoder.version8(source, tiles, lookup, shifts, origins, ceiling, plane, MediaLimits.DEFAULT);
        }
        // the order of a unit: upper-left, lower-left, upper-right, lower-right; cells row by row
        int[] cell = {0, 2, 1, 3};
        int[] position = {0, 2, 1, 3};
        var expected = new int[8];
        for (var tile = 0; tile < 2; tile++) {
            for (var k = 0; k < 4; k++) {
                var symbol = symbols.get(tile * 4 + k);
                var next = tile * 4 + k + 1 < 8 && (tile * 4 + k + 1) % 4 != 0 ? symbols.get(tile * 4 + k + 1) : null;
                var difference = difference(symbol, shifts[symbol.size()], next, codes, lengths);
                expected[tile * 4 + position[k]] = Math.clamp(origins[cell[k]] + difference, 0, ceiling);
            }
        }
        for (var k = 0; k < 8; k++) {
            assertEquals(expected[k], plane.integers[k] & 0xFFFF, "photosite " + k);
        }
    }

    // a photosite of version 8 data: the size its code announces and its stored bits
    private record Symbol(int size, int stored, int storedBits) {
    }

    // the difference a symbol codes by the documented rule; the sign of a difference without stored bits is the
    // first bit of the next code, or 0 past a tile's last code
    private static int difference(Symbol symbol, int shift, Symbol next, int[] codes, int[] lengths) {
        int top;
        if (symbol.storedBits() > 0) {
            top = symbol.stored() >>> (symbol.storedBits() - 1) & 1;
        } else if (next != null) {
            top = codes[next.size()] >>> (lengths[next.size()] - 1) & 1;
        } else {
            top = 0;
        }
        var v = symbol.stored() << shift;
        var half = shift > 0 ? 1 << (shift - 1) : 0;
        return symbol.size() > 0 && top == 0 ? v - (1 << symbol.size()) + Math.max(half, 1) : v + half;
    }

    private static byte[] encode(List<Symbol> symbols, int[] codes, int[] lengths) {
        var writer = new Bits();
        for (var symbol : symbols) {
            writer.write(codes[symbol.size()], lengths[symbol.size()]);
            writer.write(symbol.stored(), symbol.storedBits());
        }
        return writer.bytes();
    }

    // writes fields most significant bit first into bytes read from their least significant bit up
    private static final class Bits {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private int current;
        private int count;

        void write(int value, int length) {
            for (var bit = length - 1; bit >= 0; bit--) {
                current |= (value >>> bit & 1) << count;
                if (++count == 8) {
                    out.write(current);
                    current = 0;
                    count = 0;
                }
            }
        }

        byte[] bytes() {
            if (count > 0) {
                out.write(current);
                current = 0;
                count = 0;
            }
            out.writeBytes(new byte[8]);
            return out.toByteArray();
        }
    }

    private static String binary(int value, int length) {
        var text = Integer.toBinaryString(value);
        return "0".repeat(length - text.length()) + text;
    }

    private static SensorPlane read(byte[] data) {
        try (var source = MediaSource.of(data)) {
            var file = RawParser.parse(source, MediaLimits.DEFAULT);
            assertNotNull(file.sensor, file.unsupported);
            var plane = SensorPlane.of(file.sensor, MediaLimits.DEFAULT);
            file.sensor.reader.read(plane, MediaLimits.DEFAULT);
            return plane;
        }
    }
}
