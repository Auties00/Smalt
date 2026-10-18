package com.github.auties00.smalt.capture.internal.linux;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.capture.internal.convert.ScalarConversionKernel;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.jpeg.MjpegDecoder;
import com.github.auties00.smalt.image.jpeg.JpegEncoder;
import com.github.auties00.smalt.image.jpeg.JpegEncoderOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.SplittableRandom;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves that {@link MjpegDecoder#decodeFrame} decodes Motion-JPEG frames without DHT segments exactly as the
 * capture code did when it inserted the example tables of ITU-T T.81 Annex K itself: the frame converter now hands
 * such frames to the decoder unchanged. {@link #withAnnexKTables} is a copy of that insertion, kept as the
 * reference, and {@link #ANNEX_K_TABLES} the 420-byte segment it inserted.
 */
@DisplayName("Motion-JPEG frames without Huffman tables")
class MotionJpegTest {
    // The DHT segment the capture code inserted: marker, length 0x01A2, then the luminance DC, chrominance DC,
    // luminance AC and chrominance AC tables of T.81 Annex K (table classes and ids 0x00, 0x01, 0x10, 0x11).
    static final byte[] ANNEX_K_TABLES = HexFormat.of().parseHex(
            "ffc401a20000010501010101010100000000000000000102030405060708090a0b010003010101010101010101000000"
            + "0000000102030405060708090a0b100002010303020403050504040000017d0102030004110512213141061351610722"
            + "7114328191a1082342b1c11552d1f02433627282090a161718191a25262728292a3435363738393a434445464748494a"
            + "535455565758595a636465666768696a737475767778797a838485868788898a92939495969798999aa2a3a4a5a6a7a8"
            + "a9aab2b3b4b5b6b7b8b9bac2c3c4c5c6c7c8c9cad2d3d4d5d6d7d8d9dae1e2e3e4e5e6e7e8e9eaf1f2f3f4f5f6f7f8f9"
            + "fa1100020102040403040705040400010277000102031104052131061241510761711322328108144291a1b1c1092333"
            + "52f0156272d10a162434e125f11718191a262728292a35363738393a434445464748494a535455565758595a63646566"
            + "6768696a737475767778797a82838485868788898a92939495969798999aa2a3a4a5a6a7a8a9aab2b3b4b5b6b7b8b9ba"
            + "c2c3c4c5c6c7c8c9cad2d3d4d5d6d7d8d9dae2e3e4e5e6e7e8e9eaf2f3f4f5f6f7f8f9fa");

    static RgbaPicture gradient(int width, int height) {
        var picture = RgbaPicture.allocate(width, height, false);
        var pixels = picture.pixels();
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var index = (y * width + x) * 4;
                pixels[index] = (byte) (x * 255 / Math.max(1, width - 1));
                pixels[index + 1] = (byte) (y * 255 / Math.max(1, height - 1));
                pixels[index + 2] = (byte) ((x ^ y) * 7);
                pixels[index + 3] = (byte) 255;
            }
        }
        return picture;
    }

    /** Encodes with the Annex K tables, as a UVC camera does, and removes every DHT segment. */
    static byte[] tablelessFrame(int width, int height, ChromaFormat subsampling) {
        var jpeg = JpegEncoder.encode(gradient(width, height), JpegEncoderOptions.builder().quality(80).chromaFormat(subsampling).optimizeHuffman(false).build());
        return stripTables(jpeg);
    }

    static byte[] stripTables(byte[] jpeg) {
        var out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2);
        var position = 2;
        while (true) {
            var marker = jpeg[position + 1] & 0xFF;
            if (marker == 0xDA) {
                out.write(jpeg, position, jpeg.length - position);
                return out.toByteArray();
            }
            var size = (jpeg[position + 2] & 0xFF) << 8 | jpeg[position + 3] & 0xFF;
            if (marker != 0xC4) {
                out.write(jpeg, position, 2 + size);
            }
            position += 2 + size;
        }
    }

    /** Lists the payloads (class and id byte, 16 counts, symbols) of every table of every DHT segment. */
    static List<byte[]> huffmanTables(byte[] jpeg) {
        var tables = new ArrayList<byte[]>();
        var position = 2;
        while (position + 4 <= jpeg.length) {
            var marker = jpeg[position + 1] & 0xFF;
            if (marker == 0xDA) {
                return tables;
            }
            var size = (jpeg[position + 2] & 0xFF) << 8 | jpeg[position + 3] & 0xFF;
            if (marker == 0xC4) {
                tables.addAll(segmentTables(jpeg, position + 4, position + 2 + size));
            }
            position += 2 + size;
        }
        return tables;
    }

    static List<byte[]> segmentTables(byte[] data, int from, int to) {
        var tables = new ArrayList<byte[]>();
        while (from < to) {
            var symbols = 0;
            for (var l = 1; l <= 16; l++) {
                symbols += data[from + l] & 0xFF;
            }
            tables.add(Arrays.copyOfRange(data, from, from + 17 + symbols));
            from += 17 + symbols;
        }
        return tables;
    }

    /**
     * Copies a frame with {@link #ANNEX_K_TABLES} inserted before its first SOS marker when no DHT segment precedes
     * it, the way the capture code completed frames before handing them to the decoder; copies the frame unchanged
     * otherwise, and for frames that are not well-formed up to their first scan.
     */
    static byte[] withAnnexKTables(byte[] frame, int length) {
        var point = insertionPoint(frame, length);
        if (point < 0) {
            return Arrays.copyOf(frame, length);
        }
        var completed = new byte[length + ANNEX_K_TABLES.length];
        System.arraycopy(frame, 0, completed, 0, point);
        System.arraycopy(ANNEX_K_TABLES, 0, completed, point, ANNEX_K_TABLES.length);
        System.arraycopy(frame, point, completed, point + ANNEX_K_TABLES.length, length - point);
        return completed;
    }

    private static int insertionPoint(byte[] frame, int length) {
        if (length < 4 || frame[0] != (byte) 0xFF || frame[1] != (byte) 0xD8) {
            return -1;
        }
        var position = 2;
        while (position + 4 <= length) {
            if (frame[position] != (byte) 0xFF) {
                return -1;
            }
            var marker = frame[position + 1] & 0xFF;
            if (marker == 0xFF) {
                position++;
                continue;
            }
            switch (marker) {
                case 0xDA -> {
                    return position;
                }
                case 0xC4, 0xD9 -> {
                    return -1;
                }
                case 0x01, 0xD0, 0xD1, 0xD2, 0xD3, 0xD4, 0xD5, 0xD6, 0xD7, 0xD8 -> position += 2;
                default -> {
                    var size = (frame[position + 2] & 0xFF) << 8 | frame[position + 3] & 0xFF;
                    if (size < 2) {
                        return -1;
                    }
                    position += 2 + size;
                }
            }
        }
        return -1;
    }

    static void assertSamePlanes(YuvPicture expected, YuvPicture actual) {
        assertEquals(expected.width(), actual.width());
        assertEquals(expected.height(), actual.height());
        assertEquals(expected.chromaFormat(), actual.chromaFormat());
        assertEquals(expected.color(), actual.color());
        for (var y = 0; y < expected.height(); y++) {
            assertArrayEquals(Arrays.copyOfRange(expected.y(), y * expected.yStride(), y * expected.yStride() + expected.width()),
                    Arrays.copyOfRange(actual.y(), y * actual.yStride(), y * actual.yStride() + actual.width()), "luma row " + y);
        }
        for (var y = 0; y < expected.chromaHeight(); y++) {
            var e = y * expected.uvStride();
            var a = y * actual.uvStride();
            assertArrayEquals(Arrays.copyOfRange(expected.u(), e, e + expected.chromaWidth()),
                    Arrays.copyOfRange(actual.u(), a, a + actual.chromaWidth()), "cb row " + y);
            assertArrayEquals(Arrays.copyOfRange(expected.v(), e, e + expected.chromaWidth()),
                    Arrays.copyOfRange(actual.v(), a, a + actual.chromaWidth()), "cr row " + y);
        }
    }

    /** Decodes a frame, or describes its failure by the exception class. */
    private static Object outcome(byte[] frame, int length) {
        try {
            return new MjpegDecoder().decodeFrame(frame, 0, length);
        } catch (MediaException exception) {
            return exception.getClass();
        }
    }

    static Stream<Arguments> layouts() {
        var arguments = new ArrayList<Arguments>();
        for (var subsampling : List.of(ChromaFormat.YUV420, ChromaFormat.YUV422, ChromaFormat.YUV444, ChromaFormat.MONOCHROME)) {
            for (var restartInterval : new int[]{0, 3}) {
                arguments.add(Arguments.of(subsampling, restartInterval));
            }
        }
        return arguments.stream();
    }

    @Test
    @DisplayName("the reference segment holds exactly the four tables the baseline encoder writes")
    void annexKSegment() {
        assertEquals(420, ANNEX_K_TABLES.length);
        assertEquals(0xC4, ANNEX_K_TABLES[1] & 0xFF);
        assertEquals(418, (ANNEX_K_TABLES[2] & 0xFF) << 8 | ANNEX_K_TABLES[3] & 0xFF);
        var reference = segmentTables(ANNEX_K_TABLES, 4, ANNEX_K_TABLES.length);
        var written = huffmanTables(JpegEncoder.encode(gradient(32, 32), JpegEncoderOptions.builder().quality(80).chromaFormat(ChromaFormat.YUV420).optimizeHuffman(false).build()));
        assertEquals(4, reference.size());
        assertEquals(4, written.size());
        for (var table : reference) {
            assertTrue(written.stream().anyMatch(candidate -> Arrays.equals(candidate, table)),
                    "table " + Integer.toHexString(table[0] & 0xFF) + " is written by the encoder");
        }
    }

    @ParameterizedTest(name = "{0}, restart interval {1}")
    @MethodSource("layouts")
    @DisplayName("a frame without tables decodes exactly like the frame with its tables and the completed frame")
    void tableless(ChromaFormat subsampling, int restartInterval) {
        var original = JpegEncoder.encode(gradient(96, 64), JpegEncoderOptions.builder().quality(80).chromaFormat(subsampling).optimizeHuffman(false).restartInterval(restartInterval).build());
        var frame = stripTables(original);
        assertTrue(huffmanTables(frame).isEmpty());
        var completed = withAnnexKTables(frame, frame.length);
        assertEquals(frame.length + ANNEX_K_TABLES.length, completed.length);
        var expected = new MjpegDecoder().decodeFrame(original, 0, original.length);
        assertSamePlanes(expected, new MjpegDecoder().decodeFrame(frame, 0, frame.length));
        assertSamePlanes(expected, new MjpegDecoder().decodeFrame(completed, 0, completed.length));
    }

    @Test
    @DisplayName("the frame converter decodes a table-less frame like its completed copy")
    void converter() {
        var frame = tablelessFrame(64, 48, ChromaFormat.YUV420);
        var buffer = Arrays.copyOf(frame, frame.length + 64);
        var picture = new FrameConverter(V4l2PixelFormat.MJPEG, 64, 48, 0, ColorInfo.JPEG, new ScalarConversionKernel())
                .convert(buffer, frame.length);
        assertNotNull(picture);
        var completed = withAnnexKTables(frame, frame.length);
        assertSamePlanes(new MjpegDecoder().decodeFrame(completed, 0, completed.length), picture);
    }

    @Test
    @DisplayName("mutated and truncated frames decode, or fail, exactly as their completed copies do")
    void mutations() {
        var frames = new byte[][]{tablelessFrame(48, 32, ChromaFormat.YUV422), tablelessFrame(17, 9, ChromaFormat.YUV420),
                stripTables(JpegEncoder.encode(gradient(40, 24), JpegEncoderOptions.builder().quality(70).chromaFormat(ChromaFormat.YUV422).optimizeHuffman(false).restartInterval(2).build()))};
        var random = new SplittableRandom(42);
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            var decoded = 0;
            for (var round = 0; round < 600; round++) {
                var source = frames[round % frames.length];
                var mutated = source.clone();
                var length = mutated.length;
                switch (round % 3) {
                    case 0 -> {
                        for (var flip = 0; flip < 1 + random.nextInt(4); flip++) {
                            mutated[random.nextInt(mutated.length)] ^= (byte) (1 << random.nextInt(8));
                        }
                    }
                    case 1 -> length = random.nextInt(mutated.length + 1);
                    default -> {
                        var at = 4 + random.nextInt(Math.max(1, Math.min(200, mutated.length - 6)));
                        mutated[at] = (byte) random.nextInt(256);
                        mutated[at + 1] = (byte) random.nextInt(256);
                    }
                }
                var completed = withAnnexKTables(mutated, length);
                var expected = outcome(completed, completed.length);
                var actual = outcome(mutated, length);
                var label = "round " + round;
                if (expected instanceof YuvPicture picture) {
                    assertTrue(actual instanceof YuvPicture, label + ": " + actual);
                    assertSamePlanes(picture, (YuvPicture) actual);
                    decoded++;
                } else {
                    assertEquals(expected, actual, label);
                }
            }
            assertTrue(decoded > 100, "too few mutated frames decoded to compare pixels: " + decoded);
        });
    }
}
