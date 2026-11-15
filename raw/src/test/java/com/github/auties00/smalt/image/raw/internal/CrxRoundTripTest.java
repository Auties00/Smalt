package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.raw.Cr3Writer;
import com.github.auties00.smalt.image.raw.RawDecoder;
import com.github.auties00.smalt.image.raw.RawFormat;
import com.github.auties00.smalt.io.MediaSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Decodes synthetic CR3 files written by {@link Cr3Writer}, the encoder counterpart of the decoder, and requires
 * the exact photosites the writer expects: the samples of a lossless file and, for a lossy file, the synthesis of
 * the dequantized subbands, which the writer computes with a plain whole-plane transform of its own. The layouts
 * cover both codec versions, one and two tiles, every color filter layout, odd plane sizes at every level, 12- and
 * 14-bit data, flat images that are coded almost wholly in run mode and noise large enough to need escape codes;
 * damaged files must be rejected with a {@link MediaException} and nothing else.
 */
class CrxRoundTripTest {
    static Stream<Arguments> layouts() {
        return Stream.of(
                Arguments.of("lossless", 0, 0x100, 1, 96, 64, 0, 14, "ramp"),
                Arguments.of("lossless, two tiles", 0, 0x100, 2, 96, 64, 0, 14, "ramp"),
                Arguments.of("lossless, three tiles, odd plane width", 0, 0x100, 3, 138, 40, 0, 14, "ramp"),
                Arguments.of("lossless GRBG", 0, 0x100, 1, 64, 48, 1, 14, "ramp"),
                Arguments.of("lossless GBRG", 0, 0x100, 1, 64, 48, 2, 14, "ramp"),
                Arguments.of("lossless BGGR", 0, 0x100, 1, 64, 48, 3, 14, "ramp"),
                Arguments.of("lossless 12-bit", 0, 0x100, 1, 64, 48, 0, 12, "ramp"),
                Arguments.of("lossless flat", 0, 0x100, 1, 640, 32, 0, 14, "flat"),
                Arguments.of("lossless noise", 0, 0x100, 1, 64, 48, 0, 14, "noise"),
                Arguments.of("lossless, codec version 2", 0, 0x200, 1, 96, 64, 0, 14, "ramp"),
                Arguments.of("C-RAW version 1", 3, 0x100, 1, 128, 96, 0, 14, "ramp"),
                Arguments.of("C-RAW version 1, odd plane sizes", 3, 0x100, 1, 202, 74, 0, 14, "ramp"),
                Arguments.of("C-RAW version 1 flat", 3, 0x100, 1, 512, 64, 0, 14, "flat"),
                Arguments.of("C-RAW version 1 noise", 3, 0x100, 1, 128, 96, 0, 14, "noise"),
                Arguments.of("C-RAW version 2", 3, 0x200, 1, 128, 96, 0, 14, "ramp"),
                Arguments.of("C-RAW version 2, odd plane sizes", 3, 0x200, 1, 202, 74, 0, 14, "ramp"),
                Arguments.of("C-RAW version 2 noise", 3, 0x200, 1, 136, 104, 0, 14, "noise"),
                Arguments.of("C-RAW version 2 BGGR", 3, 0x200, 1, 128, 96, 3, 14, "ramp"));
    }

    static Cr3Writer writer(int levels, int version, int tiles, int width, int height, int cfa, int bits, String content) {
        var writer = new Cr3Writer();
        writer.levels = levels;
        writer.version = version;
        writer.tiles = tiles;
        writer.width = width;
        writer.height = height;
        writer.cfa = cfa;
        writer.bits = bits;
        writer.black = bits == 12 ? 128 : 512;
        writer.white = bits == 12 ? 4000 : 15000;
        switch (content) {
            case "ramp" -> writer.ramp();
            case "flat" -> {
                writer.samples = new int[width * height];
                Arrays.fill(writer.samples, writer.black);
                // one bright photosite per row makes runs end at every position of the run index
                for (var y = 0; y < height; y++) {
                    writer.samples[y * width + (y * 37) % width] = writer.black + 1000;
                }
            }
            case "noise" -> {
                var random = new Random(7);
                writer.samples = new int[width * height];
                for (var i = 0; i < writer.samples.length; i++) {
                    writer.samples[i] = random.nextInt(1 << bits);
                }
            }
            default -> throw new IllegalArgumentException(content);
        }
        return writer;
    }

    private static SensorPlane decode(byte[] file) {
        try (var source = MediaSource.of(file)) {
            var parsed = RawParser.parse(source, MediaLimits.DEFAULT);
            assertTrue(parsed.sensor != null, parsed.unsupported);
            var plane = new SensorPlane(parsed.sensor.width, parsed.sensor.height, 1, false, MediaLimits.DEFAULT);
            parsed.sensor.reader.read(plane, MediaLimits.DEFAULT);
            return plane;
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("layouts")
    @DisplayName("decodes the photosites the encoder expects")
    void roundTrip(String name, int levels, int version, int tiles, int width, int height, int cfa, int bits,
                   String content) {
        var writer = writer(levels, version, tiles, width, height, cfa, bits, content);
        var file = writer.write();
        var plane = decode(file);
        assertEquals(width, plane.width);
        assertEquals(height, plane.height);
        var decoded = new int[plane.integers.length];
        for (var i = 0; i < decoded.length; i++) {
            decoded[i] = plane.integers[i] & 0xFFFF;
        }
        if (!Arrays.equals(writer.expected, decoded)) {
            fail("photosites differ: " + differences(decoded, writer.expected, width));
        }
        if (levels == 0) {
            assertArrayEquals(writer.samples, decoded, "a lossless file restores its samples");
        }
    }

    private static String differences(int[] decoded, int[] expected, int width) {
        var list = new StringBuilder();
        var count = 0;
        for (var i = 0; i < expected.length && count < 8; i++) {
            if (decoded[i] != expected[i]) {
                list.append(" (").append(i % width).append(',').append(i / width).append(")=").append(decoded[i])
                        .append('/').append(expected[i]);
                count++;
            }
        }
        return list.toString();
    }

    @Test
    @DisplayName("describes the file: format, camera, levels, white balance, color filter and image area")
    void description() {
        var writer = writer(3, 0x200, 1, 128, 96, 0, 14, "ramp");
        writer.inset = 4;
        var file = writer.write();
        try (var source = MediaSource.of(file)) {
            var info = RawDecoder.info(source);
            assertEquals(RawFormat.CR3, info.format());
            assertTrue(info.isDecodable());
            assertEquals("Canon EOS R5", info.model().orElseThrow());
            assertEquals(200, info.iso().orElse(0));
            assertEquals("RGGB", info.cfaPattern().orElseThrow().toString());
            assertEquals(14, info.bitsPerSample());
            assertEquals(120, info.width());
            assertEquals(88, info.height());
            var parsed = RawParser.parse(source, MediaLimits.DEFAULT);
            assertArrayEquals(new double[]{512, 512, 512, 512}, parsed.sensor.black);
            assertArrayEquals(new double[]{15000}, parsed.sensor.white);
            assertArrayEquals(new double[]{1024.0 / 2000, 1, 1024.0 / 1600}, parsed.color.asShotNeutral, 1e-12);
            assertEquals("Canon CRX lossy (C-RAW)", parsed.sensor.compression);
            var picture = RawDecoder.decode(source).picture();
            assertEquals(120, picture.width());
            assertEquals(88, picture.height());
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"lossless", "C-RAW version 1", "C-RAW version 2"})
    @DisplayName("a file cut short anywhere is rejected with a media exception or still described")
    void truncated(String name) {
        var writer = name.equals("lossless") ? writer(0, 0x100, 2, 96, 64, 0, 14, "ramp")
                : writer(3, name.endsWith("2") ? 0x200 : 0x100, 1, 128, 96, 0, 14, "ramp");
        var file = writer.write();
        var end = writer.sampleOffset + writer.sampleLength;
        for (var length = 0; length < file.length; length += Math.max(1, file.length / 97)) {
            var cut = Arrays.copyOf(file, length);
            if (length > writer.sampleOffset && length < end) {
                // the file describes the image but holds only part of its data
                assertThrows(MediaException.class, () -> decode(cut), "cut at " + length);
            } else {
                expectRejectedOrDecoded(cut, length >= end);
            }
        }
        for (var length = writer.sampleOffset + 1; length < end; length += Math.max(1, writer.sampleLength / 13)) {
            var cut = Arrays.copyOf(file, length);
            assertThrows(MediaException.class, () -> decode(cut), "cut at " + length);
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"lossless", "C-RAW version 1", "C-RAW version 2"})
    @DisplayName("bitstreams whose recorded lengths are too short for their subbands are rejected")
    void shortenedBitstreams(String name) {
        var writer = name.equals("lossless") ? writer(0, 0x100, 1, 96, 64, 0, 14, "noise")
                : writer(3, name.endsWith("2") ? 0x200 : 0x100, 1, 128, 96, 0, 14, "noise");
        var file = writer.write();
        // the size of the first subband of the first plane, in the header after the tile and plane headers
        var tileHeader = writer.version == 0x200 ? 20 : 12;
        var size = writer.sampleOffset + tileHeader + 12 + 4;
        var length = java.nio.ByteBuffer.wrap(file).getInt(size);
        assertTrue(length > 16);
        java.nio.ByteBuffer.wrap(file).putInt(size, length / 2);
        assertThrows(MediaException.Malformed.class, () -> decode(file));
    }

    @Test
    @DisplayName("a plane header setting its rounded bits, a field no sample sets, is unsupported")
    void roundedBits() {
        var writer = writer(0, 0x100, 1, 96, 64, 0, 14, "ramp");
        var file = writer.write();
        // the flags word of the first plane header, after the tile header
        var word = writer.sampleOffset + 12 + 8;
        file[word] |= 0x02;
        assertThrows(MediaException.Unsupported.class, () -> decode(file));
    }

    @Test
    @DisplayName("a version 1 quantization parameter or a version 2 map value whose step is zero is rejected")
    void zeroSteps() {
        var first = writer(3, 0x100, 1, 128, 96, 0, 14, "ramp");
        first.quantizers = new int[]{4, 3, 4, 4, 16, 16, 22, 26, 26, 32};
        assertThrows(MediaException.Malformed.class, () -> decode(first.write()));
        var second = writer(3, 0x200, 1, 128, 96, 0, 14, "ramp");
        second.mapValues = new int[]{210};
        assertThrows(MediaException.Malformed.class, () -> decode(second.write()));
        var range = writer(3, 0x200, 1, 128, 96, 0, 14, "ramp");
        range.mapValues = new int[]{302 + 97};
        assertThrows(MediaException.Malformed.class, () -> decode(range.write()));
    }

    @Test
    @DisplayName("the largest CRX image is the sensor data, and a smaller one never stands in for an unsupported one")
    void mainImage() {
        var writer = writer(3, 0x100, 1, 128, 96, 0, 14, "ramp");
        writer.thumbnail = writer(0, 0x100, 1, 64, 48, 0, 14, "ramp");
        var plane = decode(writer.write());
        assertEquals(128, plane.width);
        assertEquals(96, plane.height);
        var decoded = new int[plane.integers.length];
        for (var i = 0; i < decoded.length; i++) {
            decoded[i] = plane.integers[i] & 0xFFFF;
        }
        assertArrayEquals(writer.expected, decoded);
        writer.encoding = 3;
        var file = writer.write();
        try (var source = MediaSource.of(file)) {
            var info = RawDecoder.info(source);
            assertTrue(!info.isDecodable(), "an unsupported main image");
            assertEquals("Canon EOS R5", info.model().orElseThrow());
            var exception = assertThrows(MediaException.Unsupported.class, () -> RawDecoder.decode(source));
            assertTrue(exception.getMessage().contains("encoding type 3"), exception.getMessage());
            assertThrows(MediaException.Unsupported.class, () -> RawDecoder.decodeRaw(source));
        }
        writer.thumbnail = null;
        try (var source = MediaSource.of(writer.write())) {
            assertTrue(!RawDecoder.info(source).isDecodable());
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"lossless", "C-RAW version 1", "C-RAW version 2"})
    @DisplayName("damaged bitstreams and headers are rejected with a media exception or decoded")
    void damaged(String name) {
        var writer = name.equals("lossless") ? writer(0, 0x100, 2, 96, 64, 0, 14, "ramp")
                : writer(3, name.endsWith("2") ? 0x200 : 0x100, 1, 128, 96, 0, 14, "ramp");
        var file = writer.write();
        var random = new Random(name.hashCode());
        for (var round = 0; round < 300; round++) {
            var copy = file.clone();
            var flips = 1 + random.nextInt(4);
            for (var i = 0; i < flips; i++) {
                // the sample's headers in a third of the rounds, its bitstreams otherwise
                var headers = round % 3 == 0;
                var span = headers ? Math.min(200, writer.sampleLength) : writer.sampleLength;
                var at = writer.sampleOffset + random.nextInt(span);
                copy[at] ^= (byte) (1 << random.nextInt(8));
            }
            expectRejectedOrDecoded(copy, false);
        }
    }

    private static void expectRejectedOrDecoded(byte[] file, boolean mustDecode) {
        try {
            decode(file);
        } catch (MediaException exception) {
            if (mustDecode) {
                throw exception;
            }
        } catch (AssertionError error) {
            // the file was described without sensor data
        }
    }

    @Test
    @DisplayName("an unknown codec version or encoding is unsupported, a CMP1 box that contradicts itself is not decoded")
    void unsupported() {
        var cmp1 = new byte[0x34];
        java.nio.ByteBuffer.wrap(cmp1).putShort(4, (short) 0x300).putInt(8, 64).putInt(12, 48).putInt(16, 64)
                .putInt(20, 48).put(24, (byte) 14).put(25, (byte) 0x40).putInt(28, 0x70);
        assertThrows(MediaException.Unsupported.class, () -> CrxDecoder.image(cmp1));
        java.nio.ByteBuffer.wrap(cmp1).putShort(4, (short) 0x100).put(26, (byte) 0x30);
        assertThrows(MediaException.Unsupported.class, () -> CrxDecoder.image(cmp1));
        java.nio.ByteBuffer.wrap(cmp1).put(26, (byte) 0x02);
        assertThrows(MediaException.Unsupported.class, () -> CrxDecoder.image(cmp1));
        java.nio.ByteBuffer.wrap(cmp1).put(26, (byte) 0x03).putInt(16, 128);
        assertThrows(MediaException.Malformed.class, () -> CrxDecoder.image(cmp1));
        java.nio.ByteBuffer.wrap(cmp1).putInt(16, 63);
        assertThrows(MediaException.Malformed.class, () -> CrxDecoder.image(cmp1));
        assertThrows(MediaException.Malformed.class, () -> CrxDecoder.image(new byte[20]));
    }
}
