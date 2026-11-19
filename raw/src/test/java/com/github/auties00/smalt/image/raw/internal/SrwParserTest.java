package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.raw.CfaPattern;
import com.github.auties00.smalt.image.raw.RawDecoder;
import com.github.auties00.smalt.image.raw.RawDecoderOptions;
import com.github.auties00.smalt.image.raw.RawFormat;
import com.github.auties00.smalt.image.raw.VendorTiffWriter;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.fuzz.FuzzSeeds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the Samsung SRW parser on synthetic files built from the layouts the CC0 samples show (16-bit words,
 * 12-bit samples packed least or most significant bit first, the three compressed codings written by
 * {@link SrwCoding}, the maker note's levels stored plus the key, the valid sensor area and the white levels of the
 * models), the variants it rejects, truncated and damaged files.
 */
class SrwParserTest {
    private static final String FUZZ_TARGET = "com.github.auties00.smalt.image.raw.fuzz.RawFuzzTest";

    // the maker note key of every CC0 sample
    private static final long[] KEY = {305, 72, 737, 456, 282, 307, 519, 724, 13, 505, 193};

    static final class Srw {
        int width = 16;
        int height = 8;
        int bits = 12;
        int compression = 32770;
        boolean little = true;
        Long byteOrder = 0L;
        String model = "NX1000";
        byte[] cfa = {1, 0, 2, 1};
        long[] black = {3, 2, 2, 3};
        long[] balance = {10480, 4096, 4096, 7664};
        long[] areas = {0, 0, 16, 8, 2, 2, 14, 6};
        short[] samples;
        int dropBytes;

        Srw samples(long seed) {
            var random = new SplittableRandom(seed);
            samples = new short[width * height];
            for (var index = 0; index < samples.length; index++) {
                samples[index] = (short) random.nextInt(1 << bits);
            }
            return this;
        }

        byte[] strip() {
            if (compression == 32769) {
                var buffer = ByteBuffer.allocate(samples.length * 2).order(ByteOrder.LITTLE_ENDIAN);
                for (var sample : samples) {
                    buffer.putShort(sample);
                }
                return buffer.array();
            }
            var data = new byte[samples.length / 2 * 3];
            var msb = byteOrder == null ? !little : byteOrder == 1;
            for (var pair = 0; pair < samples.length / 2; pair++) {
                var a = samples[2 * pair] & 0xFFF;
                var b = samples[2 * pair + 1] & 0xFFF;
                if (msb) {
                    data[3 * pair] = (byte) (a >> 4);
                    data[3 * pair + 1] = (byte) (((a & 0xF) << 4) | (b >> 8));
                    data[3 * pair + 2] = (byte) b;
                } else {
                    data[3 * pair] = (byte) a;
                    data[3 * pair + 1] = (byte) ((a >> 8) | ((b & 0xF) << 4));
                    data[3 * pair + 2] = (byte) (b >> 4);
                }
            }
            return data;
        }

        byte[] write() {
            var writer = writer();
            var strip = strip();
            writer.strip = Arrays.copyOf(strip, strip.length - dropBytes);
            return writer.write();
        }

        VendorTiffWriter writer() {
            var writer = new VendorTiffWriter();
            writer.little = little;
            writer.make = "SAMSUNG";
            writer.model = model;
            writer.width = width;
            writer.height = height;
            writer.bits = bits;
            writer.samplesPerPixel = 3;
            writer.compression = compression;
            // Samsung's raw directories state no photometric interpretation
            writer.photometric = 0;
            writer.cfa = cfa;
            writer.makerNote = offset -> {
                var entries = new ArrayList<VendorTiffWriter.Entry>();
                if (byteOrder != null) {
                    entries.add(writer.longs(0x0040, byteOrder));
                }
                entries.add(writer.longs(0xA010, areas));
                entries.add(writer.longs(0xA020, KEY));
                var wb = new long[4];
                var levels = new long[4];
                for (var index = 0; index < 4; index++) {
                    wb[index] = balance[index] + KEY[index];
                    levels[index] = black[index] + KEY[index];
                }
                entries.add(writer.longs(0xA021, wb));
                entries.add(writer.slongs(0xA028, levels));
                return VendorTiffWriter.directory(entries, 0, writer.order());
            };
            return writer;
        }
    }

    private static RawFile parse(byte[] bytes) {
        return RawParser.parse(MediaSource.of(bytes), MediaLimits.DEFAULT);
    }

    private static short[] decode(RawFile file) {
        var sensor = file.sensor;
        var plane = new SensorPlane(sensor.width, sensor.height, 1, false, MediaLimits.DEFAULT);
        sensor.reader.read(plane, MediaLimits.DEFAULT);
        return plane.integers;
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"words", "packed-lsb-first", "packed-msb-first", "packed-big-endian-tagged"})
    @DisplayName("decodes the 16-bit words and both packings exactly")
    void layouts(String layout) {
        var srw = new Srw();
        switch (layout) {
            case "words" -> {
                srw.compression = 32769;
                srw.bits = 14;
                srw.little = false;
            }
            case "packed-msb-first" -> {
                srw.little = false;
                srw.byteOrder = null;
            }
            case "packed-big-endian-tagged" -> srw.byteOrder = 1L;
            default -> {
            }
        }
        var file = parse(srw.samples(layout.length()).write());
        assertEquals(RawFormat.SRW, file.format);
        assertNotNull(file.sensor, file.unsupported);
        assertArrayEquals(srw.samples, decode(file));
    }

    @Test
    @DisplayName("keeps the whole 16-bit word of a sample above the bit depth")
    void wholeWords() {
        var srw = new Srw();
        srw.compression = 32769;
        srw.bits = 14;
        srw.samples(9);
        srw.samples[3] = (short) 0xFFFF;
        assertArrayEquals(srw.samples, decode(parse(srw.write())));
    }

    @Test
    @DisplayName("reads the levels and the white balance less the key, in the color filter array's order")
    void levels() {
        var srw = new Srw().samples(1);
        srw.black = new long[]{3, 1, 2, 4};
        var file = parse(srw.write());
        // GRBG: the first green takes the first green level
        assertArrayEquals(new double[]{1, 3, 4, 2}, file.sensor.black);
        assertEquals(2, file.sensor.blackRows);
        assertEquals(2, file.sensor.blackColumns);
        assertArrayEquals(new double[]{4096.0 / 10480, 1, 4096.0 / 7664}, file.color.asShotNeutral, 1e-12);
        assertArrayEquals(new double[]{2, 2, 12, 4}, file.sensor.defaultCrop);
        assertArrayEquals(new double[]{3750}, file.sensor.white);
        assertFalse(file.sensor.estimateBalance);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"NX mini", "NX500", "NX300", "EX1", "WB2000", "NX1"})
    @DisplayName("takes the white level of the model")
    void whites(String model) {
        var srw = new Srw();
        srw.model = model;
        srw.bits = model.equals("EX1") || model.equals("WB2000") || model.equals("NX1") ? 14 : 12;
        if (srw.bits == 14) {
            srw.compression = 32769;
        }
        var expected = switch (model) {
            case "NX mini", "NX500" -> 4000;
            case "EX1" -> 15600;
            case "WB2000" -> 3700;
            case "NX1" -> 16100;
            default -> 4095;
        };
        assertArrayEquals(new double[]{expected}, parse(srw.samples(2).write()).sensor.white);
    }

    @Test
    @DisplayName("ignores a sensor area that does not fit the image")
    void areas() {
        var srw = new Srw();
        srw.areas = new long[]{0, 0, 16, 8, 2, 2, 18, 6};
        assertNull(parse(srw.samples(3).write()).sensor.defaultCrop);
    }

    private static byte[] coded(Srw srw, SrwCoding.Coded coded) {
        var writer = srw.writer();
        writer.strip = coded.strip();
        if (coded.offsets() == null || srw.compression != 32770) {
            return writer.write();
        }
        // the table of row offsets follows the strip, at an offset known from a first pass
        var table = ByteBuffer.allocate(coded.offsets().length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (var offset : coded.offsets()) {
            table.putInt(offset);
        }
        writer.strip = Arrays.copyOf(coded.strip(), coded.strip().length + table.capacity());
        System.arraycopy(table.array(), 0, writer.strip, coded.strip().length, table.capacity());
        var position = writer.longs(0xA010, 0);
        var size = writer.longs(0xA011, table.capacity());
        writer.rawEntries.add(position);
        writer.rawEntries.add(size);
        writer.write();
        writer.rawEntries.set(0, writer.longs(0xA010, writer.stripOffset + coded.strip().length));
        return writer.write();
    }

    private static Srw sized(int compression, int width, int height, int bits) {
        var srw = new Srw();
        srw.compression = compression;
        srw.width = width;
        srw.height = height;
        srw.bits = bits;
        srw.areas = new long[]{0, 0, width, height, 0, 0, width, height};
        return srw;
    }

    @ParameterizedTest(name = "{0}x{1}, seed {2}")
    @CsvSource({"16, 8, 1", "64, 32, 2", "40, 9, 3", "1, 1, 4", "17, 2, 5", "2048, 8, 6"})
    @DisplayName("decodes compression 32770 at fewer bits than the photosites, a table giving its rows")
    void interleaved(int width, int height, long seed) {
        var coded = SrwCoding.interleaved(width, height, seed);
        var file = parse(coded(sized(32770, width, height, 12), coded));
        assertNotNull(file.sensor, file.unsupported);
        assertEquals("Samsung", file.sensor.compression);
        assertArrayEquals(coded.samples(), decode(file));
    }

    @ParameterizedTest(name = "{0}x{1}, {2} bits, seed {3}")
    @CsvSource({"16, 8, 12, 1", "64, 32, 12, 2", "33, 7, 12, 3", "1, 1, 12, 4", "2, 2, 14, 5", "512, 16, 14, 6"})
    @DisplayName("decodes compression 32772: prefix codes of every category, photosites limited to the bit depth")
    void prefixed(int width, int height, int bits, long seed) {
        var coded = SrwCoding.prefixed(width, height, bits, seed);
        var file = parse(coded(sized(32772, width, height, bits), coded));
        assertNotNull(file.sensor, file.unsupported);
        assertEquals(bits, file.sensor.bitsPerSample);
        assertArrayEquals(coded.samples(), decode(file));
    }

    @ParameterizedTest(name = "{0}x{1}, {2} bits, quantized {3}, row start {4}")
    @CsvSource({"16, 8, 12, false, 128, 1", "64, 32, 14, false, 128, 2", "40, 9, 12, false, 64, 3",
            "16, 8, 12, true, 128, 4", "64, 32, 14, true, 128, 5", "200, 21, 12, true, 300, 6", "17, 2, 14, true, 0, 7",
            "1, 1, 12, false, 128, 8"})
    @DisplayName("decodes compression 32773: every prediction mode, the ends of rows, quantizer changes and the "
            + "header's prediction")
    void modal(int width, int height, int bits, boolean quantized, int rowStart, long seed) {
        var coded = SrwCoding.modal(width, height, bits, quantized, rowStart, seed);
        var file = parse(coded(sized(32773, width, height, bits), coded));
        assertNotNull(file.sensor, file.unsupported);
        assertArrayEquals(coded.samples(), decode(file));
    }

    @Test
    @DisplayName("a coded strip cut short decodes to its end")
    void truncatedCoded() {
        var strips = new SrwCoding.Coded[]{SrwCoding.prefixed(64, 32, 12, 2), SrwCoding.modal(64, 32, 12, false, 128, 3)};
        var compressions = new int[]{32772, 32773};
        for (var index = 0; index < strips.length; index++) {
            var coded = strips[index];
            var bytes = coded(sized(compressions[index], 64, 32, 12), coded);
            var file = parse(Arrays.copyOf(bytes, bytes.length - coded.strip().length / 2));
            var decoded = decode(file);
            assertEquals(coded.samples().length, decoded.length);
            // half of the strip holds about half of the rows: the first eight are whole, the last are not reached
            assertArrayEquals(Arrays.copyOf(coded.samples(), 8 * 64), Arrays.copyOf(decoded, 8 * 64));
            assertArrayEquals(new short[8 * 64], Arrays.copyOfRange(decoded, 24 * 64, 32 * 64));
        }
    }

    @Test
    @DisplayName("a 32773 strip ending at a row's start leaves the rows after it at zero")
    void truncatedAtRow() {
        var coded = SrwCoding.modal(64, 32, 12, false, 128, 3);
        var cut = Arrays.copyOf(coded.strip(), coded.offsets()[10]);
        var decoded = decode(parse(coded(sized(32773, 64, 32, 12), new SrwCoding.Coded(cut, null, null))));
        assertArrayEquals(Arrays.copyOf(coded.samples(), 10 * 64), Arrays.copyOf(decoded, 10 * 64));
        assertArrayEquals(new short[22 * 64], Arrays.copyOfRange(decoded, 10 * 64, 32 * 64));
    }

    @Test
    @DisplayName("a strip longer than the file allocates what the file holds")
    void stripPastFile() {
        var coded = SrwCoding.prefixed(64, 32, 12, 2);
        var bytes = coded(sized(32772, 64, 32, 12), coded);
        var entry = entryOf(bytes, 279);
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(entry + 8, 0xFFFFFFF0);
        assertArrayEquals(coded.samples(), decode(parse(bytes)));
    }

    /** {@return the position of the first directory entry of a tag in the raw directory of a little-endian file} */
    private static int entryOf(byte[] bytes, int tag) {
        var buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        for (var at = 8; at + 12 <= bytes.length; at += 2) {
            if (buffer.getShort(at) == (short) tag && buffer.getShort(at + 2) == 4 && buffer.getInt(at + 4) == 1) {
                return at;
            }
        }
        throw new AssertionError("no entry of tag " + tag);
    }

    @ParameterizedTest(name = "compression {0}")
    @ValueSource(ints = {32770, 32773})
    @DisplayName("the rows of a block coding count against the allocation limit")
    void blockRowsLimit(int compression) {
        var coded = compression == 32770 ? SrwCoding.interleaved(4096, 1, 1) : SrwCoding.modal(4096, 1, 12, false, 128, 1);
        var file = parse(coded(sized(compression, 4096, 1, 12), coded));
        // the strip and the plane fit, the rows the predictions read do not
        var limits = MediaLimits.builder().maxBytes(4096 * 19L).build();
        var plane = SensorPlane.of(file.sensor, limits);
        assertThrows(MediaException.LimitExceeded.class, () -> file.sensor.reader.read(plane, limits));
    }

    @Test
    @DisplayName("takes a width of 16 bits, and its differences limited to the bit depth")
    void widestDifferences() {
        var strip = SrwCoding.strip(32, 1, 12, false, "1" + "11111111" + "1111" + "0000" + "0000" + "0000" + "0".repeat(60)
                + "1" + "01000000" + "0111111111111111" + "1000000000000000" + "0000000000000001" + "1111111111111111");
        var decoded = decode(parse(coded(sized(32773, 32, 1, 12), new SrwCoding.Coded(strip, null, null))));
        assertEquals(4095, decoded[16]);
        assertEquals(0, decoded[18]);
        assertEquals(129, decoded[20]);
        assertEquals(127, decoded[22]);
    }

    @Test
    @DisplayName("a width above 16 bits is malformed")
    void widthAboveRange() {
        var strip = SrwCoding.strip(48, 1, 12, false, "1" + "11111111" + "1111" + "0000" + "0000" + "0000" + "0".repeat(60)
                + "1" + "01000000" + "0".repeat(64) + "1" + "01000000");
        var file = parse(coded(sized(32773, 48, 1, 12), new SrwCoding.Coded(strip, null, null)));
        assertThrows(MediaException.Malformed.class, () -> decode(file));
    }

    @ParameterizedTest(name = "mode {0}")
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6})
    @DisplayName("a 32773 block of the first two rows predicting from above is malformed, as the converter refuses it")
    void upperModeInFirstRows(int mode) {
        var modeBits = "0" + String.format("%3s", Integer.toBinaryString(mode)).replace(' ', '0');
        var first = SrwCoding.strip(16, 2, 12, false, modeBits);
        var file = parse(coded(sized(32773, 16, 2, 12), new SrwCoding.Coded(first, null, null)));
        assertThrows(MediaException.Malformed.class, () -> decode(file));
        // the left mode stated there is fine
        var left = SrwCoding.strip(16, 2, 12, false, "0111");
        assertEquals(128, decode(parse(coded(sized(32773, 16, 2, 12), new SrwCoding.Coded(left, null, null))))[0] & 0xFFFF,
                "the row start");
    }

    @Test
    @DisplayName("a 32770 row whose offset is past the strip ends the decoding, the rows before it decoded")
    void offsetPastStrip() {
        var coded = SrwCoding.interleaved(64, 32, 1);
        var offsets = coded.offsets().clone();
        offsets[8] = Integer.MAX_VALUE;
        var damaged = new SrwCoding.Coded(coded.strip(), offsets, coded.samples());
        var decoded = decode(parse(coded(sized(32770, 64, 32, 12), damaged)));
        // coded rows 6 and 7 hold lines 6 and 7; line 8 is left at zero
        assertArrayEquals(Arrays.copyOf(coded.samples(), 8 * 64), Arrays.copyOf(decoded, 8 * 64));
        assertEquals(0, decoded[8 * 64]);
    }

    @Test
    @DisplayName("a block whose always-zero bit is set is malformed")
    void reservedBit() {
        // the field 3 with a quantizer, the bit keeping the left mode, and the bit that must be zero
        var strip = SrwCoding.strip(16, 2, 12, true, "11" + "000000000001" + "1" + "1");
        var file = parse(coded(sized(32773, 16, 2, 12), new SrwCoding.Coded(strip, null, null)));
        assertThrows(MediaException.Malformed.class, () -> decode(file));
    }

    @Test
    @DisplayName("a width below zero is malformed")
    void widthBelowZero() {
        // a block setting its four widths to 0, and a block subtracting one from each
        var strip = SrwCoding.strip(32, 2, 12, false, "1" + "11111111" + "0000000000000000" + "1" + "10101010");
        var file = parse(coded(sized(32773, 32, 2, 12), new SrwCoding.Coded(strip, null, null)));
        assertThrows(MediaException.Malformed.class, () -> decode(file));
    }

    @Test
    @DisplayName("the code no category uses is malformed")
    void unassignedCode() {
        var file = parse(coded(sized(32772, 16, 2, 12),
                new SrwCoding.Coded(new byte[]{(byte) 0b11010111, (byte) 0b11100000, 0, 0}, null, null)));
        assertThrows(MediaException.Malformed.class, () -> decode(file));
    }

    @ParameterizedTest(name = "byte {0} set to {1}")
    @CsvSource({"2, 0", "3, 0", "9, 1"})
    @DisplayName("a 32773 strip of an unknown header is unsupported")
    void unknownHeader(int index, int value) {
        var coded = SrwCoding.modal(16, 2, 12, false, 128, 1);
        coded.strip()[index] = (byte) value;
        var bytes = coded(sized(32773, 16, 2, 12), coded);
        var info = RawDecoder.info(MediaSource.of(bytes));
        assertEquals(RawFormat.SRW, info.format());
        assertFalse(info.isDecodable());
        assertThrows(MediaException.Unsupported.class, () -> RawDecoder.decodeRaw(MediaSource.of(bytes)));
    }

    @Test
    @DisplayName("a 32773 strip whose header states another size is malformed")
    void headerSize() {
        var coded = SrwCoding.modal(16, 2, 12, false, 128, 1);
        var bytes = coded(sized(32773, 32, 2, 12), coded);
        assertThrows(MediaException.Malformed.class, () -> parse(bytes));
    }

    @Test
    @DisplayName("compression 32770 at other bits without a table of rows is unsupported")
    void noTable() {
        var srw = new Srw();
        srw.dropBytes = 10;
        var bytes = srw.samples(4).write();
        var info = RawDecoder.info(MediaSource.of(bytes));
        assertEquals(RawFormat.SRW, info.format());
        assertFalse(info.isDecodable());
        assertThrows(MediaException.Unsupported.class, () -> RawDecoder.decodeRaw(MediaSource.of(bytes)));
    }

    @ParameterizedTest(name = "{0}: {1}")
    @CsvSource({"NX300, GRBG", "NX300M, GRBG", "NX2000, GRBG", "EK-GN100, GRBG", "EK-GN110, GRBG", "EK-GN120, GRBG",
            "NX3000, GRBG", "NX3300, GRBG", "NX mini, RGGB", "NX30, RGGB"})
    @DisplayName("takes the filter array of the models whose files state another")
    void filterArrays(String model, String pattern) {
        var srw = new Srw().samples(1);
        srw.model = model;
        srw.cfa = new byte[]{0, 1, 1, 2};
        var file = parse(srw.write());
        var expected = CfaPattern.bayer(pattern);
        for (var index = 0; index < 4; index++) {
            assertEquals(expected.color(index / 2, index % 2), file.sensor.cfa.color(index / 2, index % 2));
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"NX300", "NX300M", "NX30", "NX2000", "EK-GN100", "EK-GN110", "EK-GN120"})
    @DisplayName("takes the image place of the models whose sensor area leaves the image out")
    void fixedImage(String model) {
        var coded = SrwCoding.interleaved(5546, 3700, 1);
        var srw = sized(32770, 5546, 3700, 12);
        srw.model = model;
        srw.areas = new long[]{0, 0, 5632, 3714, 78, 16, 5526, 3664};
        var file = parse(coded(srw, coded));
        assertArrayEquals(new double[]{52, 28, 5472, 3648}, file.sensor.defaultCrop);
    }

    @Test
    @DisplayName("decodes a truncated file to its end")
    void truncated() {
        var srw = new Srw().samples(5);
        var bytes = srw.write();
        var decoded = decode(parse(Arrays.copyOf(bytes, bytes.length - 30)));
        var full = srw.width * (srw.height - 2);
        assertArrayEquals(Arrays.copyOf(srw.samples, full), Arrays.copyOf(decoded, full));
    }

    @Test
    @DisplayName("damaged synthetic files only raise MediaException, in bounded time")
    void fuzz() {
        var words = new Srw();
        words.compression = 32769;
        words.bits = 14;
        var originals = new byte[][]{new Srw().samples(6).write(), words.samples(7).write(),
                coded(sized(32770, 64, 16, 12), SrwCoding.interleaved(64, 16, 8)),
                coded(sized(32772, 64, 16, 12), SrwCoding.prefixed(64, 16, 12, 9)),
                coded(sized(32773, 64, 16, 12), SrwCoding.modal(64, 16, 12, false, 128, 10)),
                coded(sized(32773, 64, 16, 14), SrwCoding.modal(64, 16, 14, true, 128, 11))};
        var random = new SplittableRandom(21);
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            for (var round = 0; round < 600; round++) {
                var bytes = originals[round % originals.length].clone();
                if (round % 3 == 0) {
                    bytes = Arrays.copyOf(bytes, 8 + random.nextInt(bytes.length - 8));
                } else {
                    for (var count = 0; count < 1 + random.nextInt(6); count++) {
                        bytes[4 + random.nextInt(bytes.length - 4)] = (byte) random.nextInt(256);
                    }
                }
                var input = bytes;
                var limits = MediaLimits.builder().maxPixels(1 << 20).build();
                try {
                    RawDecoder.info(MediaSource.of(input), limits);
                    RawDecoder.decodeRaw(MediaSource.of(input), RawDecoderOptions.builder().limits(limits).build());
                    RawDecoder.decode(MediaSource.of(input), RawDecoderOptions.builder().limits(limits).build());
                } catch (MediaException expected) {
                    // a damaged file may be rejected
                }
            }
        });
    }

    @Test
    @DisplayName("renders a synthetic file")
    void renders() {
        var srw = new Srw();
        srw.width = 64;
        srw.height = 48;
        srw.areas = new long[]{0, 0, 64, 48, 4, 4, 60, 44};
        var picture = RawDecoder.decode(MediaSource.of(srw.samples(8).write())).picture();
        assertEquals(56, picture.width());
        assertEquals(40, picture.height());
        assertTrue(RawDecoder.info(MediaSource.of(srw.write())).isDecodable());
    }

    @Test
    @EnabledIfSystemProperty(named = "smalt.fuzz.writeSeeds", matches = "true",
            disabledReason = "[opt-in:fuzz-seeds] runs only with -Dsmalt.fuzz.writeSeeds=true")
    @DisplayName("writes the seeds of the SRW fuzz target")
    void fuzzSeeds() {
        FuzzSeeds.write(FUZZ_TARGET, "srw", "valid-packed-lsb-first.srw", new Srw().samples(1).write());
        var msb = new Srw();
        msb.little = false;
        msb.byteOrder = null;
        FuzzSeeds.write(FUZZ_TARGET, "srw", "valid-packed-msb-first.srw", msb.samples(2).write());
        var words = new Srw();
        words.compression = 32769;
        words.bits = 14;
        FuzzSeeds.write(FUZZ_TARGET, "srw", "valid-words.srw", words.samples(3).write());
        FuzzSeeds.write(FUZZ_TARGET, "srw", "valid-interleaved.srw",
                coded(sized(32770, 64, 16, 12), SrwCoding.interleaved(64, 16, 4)));
        FuzzSeeds.write(FUZZ_TARGET, "srw", "valid-prefixed.srw",
                coded(sized(32772, 64, 16, 12), SrwCoding.prefixed(64, 16, 12, 5)));
        FuzzSeeds.write(FUZZ_TARGET, "srw", "valid-modal-lossless.srw",
                coded(sized(32773, 64, 16, 14), SrwCoding.modal(64, 16, 14, false, 128, 6)));
        FuzzSeeds.write(FUZZ_TARGET, "srw", "valid-modal-quantized.srw",
                coded(sized(32773, 64, 16, 12), SrwCoding.modal(64, 16, 12, true, 128, 7)));
    }
}
