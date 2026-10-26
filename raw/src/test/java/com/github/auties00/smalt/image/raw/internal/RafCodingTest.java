package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.image.raw.CfaLayout;
import com.github.auties00.smalt.image.raw.RafWriter;
import com.github.auties00.smalt.image.raw.RawDecoder;
import com.github.auties00.smalt.image.raw.RawDecoderOptions;
import com.github.auties00.smalt.image.raw.RawToneCurve;
import com.github.auties00.smalt.io.MediaSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the decoding of Fujifilm RAF files written by {@link RafWriter}: lossless compressed files of every bit
 * depth, both filter arrays and partial blocks and groups decode to their samples exactly, lossy ones to the values
 * their coder reconstructs within each level's error bound, damaged ones raise only
 * {@link MediaException}, the bare sensor words of older models are placed, oriented, cropped and described as
 * their records say, and the second image of SuperCCD SR and EXR files becomes the second sample of each photosite or,
 * in files whose output has twice the image's area, the odd columns of one staggered grid (the even ones in the bare
 * sensor words of the SuperCCD EXR models).
 */
class RafCodingTest {
    private static SensorPlane decode(byte[] file, RawFile[] parsed) {
        try (var source = MediaSource.of(file)) {
            var raw = RawParser.parse(source, MediaLimits.DEFAULT);
            assertNotNull(raw.sensor, raw.unsupported);
            var plane = SensorPlane.of(raw.sensor, MediaLimits.DEFAULT);
            raw.sensor.reader.read(plane, MediaLimits.DEFAULT);
            parsed[0] = raw;
            return plane;
        }
    }

    @Nested
    @DisplayName("compressed files")
    class Compressed {
        @ParameterizedTest(name = "x-trans {0}, {1} bits, {2}x{3}, blocks of {4}")
        @CsvSource({
                "false, 12, 96, 24, 768",
                "false, 14, 100, 23, 48",
                "false, 16, 150, 13, 96",
                "true, 12, 96, 24, 768",
                "true, 14, 100, 23, 48",
                "true, 16, 150, 13, 96",
                "true, 14, 6, 6, 6",
                "false, 14, 2, 2, 6"
        })
        @DisplayName("decode to their samples exactly")
        void roundTrip(boolean xtrans, int bits, int width, int height, int blockWidth) {
            var writer = new RafWriter();
            writer.xtrans = xtrans;
            writer.bits = bits;
            writer.width = width;
            writer.height = height;
            writer.blockWidth = blockWidth;
            writer.black = 64;
            writer.scene(width * 31L + height);
            var parsed = new RawFile[1];
            var plane = decode(writer.compressed(), parsed);
            assertArrayEquals(writer.samples, plane.integers);
            var sensor = parsed[0].sensor;
            assertEquals("Fujifilm lossless compressed", sensor.compression);
            assertEquals(bits, sensor.bitsPerSample);
            assertEquals(xtrans ? 6 : 2, sensor.cfa.width());
            assertEquals(64, sensor.black[0]);
        }

        @ParameterizedTest(name = "x-trans {0}, {1} bits, levels {2}")
        @CsvSource({
                "false, 14, '1 3 4 4 5 4 9 0'",
                "true, 14, '0 1 2 3 2 1 0 7'",
                "false, 12, '2 2 1 30 0'",
                "true, 16, '1 4 13 70 0 2'",
                "false, 16, '0 0 0'"
        })
        @DisplayName("lossy files decode to the values their coder reconstructs, within each level's error bound")
        void lossy(boolean xtrans, int bits, String levels) {
            var writer = new RafWriter();
            writer.xtrans = xtrans;
            writer.bits = bits;
            writer.width = 150;
            writer.height = 50;
            writer.blockWidth = 96;
            writer.levels = Arrays.stream(levels.split(" ")).mapToInt(Integer::parseInt).toArray();
            writer.scene(bits * 3L + (xtrans ? 1 : 0));
            var parsed = new RawFile[1];
            var plane = decode(writer.compressed(), parsed);
            assertArrayEquals(writer.reconstructed, plane.integers);
            assertEquals("Fujifilm lossy compressed", parsed[0].sensor.compression);
            var bound = Arrays.stream(writer.levels).max().orElseThrow();
            var exceeded = 0;
            var changed = 0;
            for (var index = 0; index < plane.integers.length; index++) {
                var error = Math.abs((plane.integers[index] & 0xFFFF) - (writer.samples[index] & 0xFFFF));
                exceeded += error > bound ? 1 : 0;
                changed += error > 0 ? 1 : 0;
            }
            assertEquals(0, exceeded, "samples beyond the error bound");
            assertTrue(bound == 0 ? changed == 0 : changed > 0, changed + " samples changed");
        }

        @Test
        @DisplayName("render through the public API")
        void render() {
            var writer = new RafWriter();
            writer.xtrans = true;
            writer.width = 120;
            writer.height = 60;
            writer.scene(7);
            var image = RawDecoder.decode(MediaSource.of(writer.compressed()));
            assertEquals(112, image.picture().width());
            assertEquals(56, image.picture().height());
            var info = RawDecoder.info(MediaSource.of(writer.compressed()));
            assertTrue(info.isDecodable());
        }

        @Test
        @DisplayName("raise only MediaException when truncated or damaged")
        void damaged() {
            var writer = new RafWriter();
            writer.xtrans = true;
            writer.width = 100;
            writer.height = 30;
            writer.blockWidth = 48;
            writer.scene(3);
            var file = writer.compressed();
            var random = new Random(11);
            assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
                for (var trial = 0; trial < 300; trial++) {
                    var damaged = trial < 100 ? Arrays.copyOf(file, random.nextInt(file.length))
                            : file.clone();
                    if (trial >= 100) {
                        for (var flip = 0; flip < 1 + trial % 8; flip++) {
                            var at = 160 + random.nextInt(damaged.length - 160);
                            damaged[at] ^= (byte) (1 << random.nextInt(8));
                        }
                    }
                    try {
                        RawDecoder.decodeRaw(MediaSource.of(damaged));
                    } catch (MediaException expected) {
                        // damage may be detected
                    }
                }
            });
        }

        @Test
        @DisplayName("reject a stream whose header does not match the sensor")
        void mismatch() {
            var writer = new RafWriter();
            writer.width = 48;
            writer.height = 12;
            writer.scene(5);
            var file = writer.compressed();
            var stream = writer.stream();
            var at = indexOf(file, stream);
            var wrongBits = file.clone();
            wrongBits[at + 4] = 12;
            assertThrows(MediaException.Malformed.class, () -> RawDecoder.decodeRaw(MediaSource.of(wrongBits)));
            var wrongMagic = file.clone();
            wrongMagic[at] = 'X';
            assertThrows(MediaException.Malformed.class, () -> RawDecoder.decodeRaw(MediaSource.of(wrongMagic)));
        }

        private static int indexOf(byte[] haystack, byte[] needle) {
            outer:
            for (var index = 0; index + needle.length <= haystack.length; index++) {
                for (var k = 0; k < 16; k++) {
                    if (haystack[index + k] != needle[k]) {
                        continue outer;
                    }
                }
                return index;
            }
            throw new AssertionError("stream not found");
        }
    }

    @Nested
    @DisplayName("uncompressed 12-bit files")
    class Packed {
        @ParameterizedTest(name = "most significant bit first {0}, {1}x{2}")
        @CsvSource({"false, 96, 8", "true, 96, 8", "false, 90, 7", "true, 90, 7", "true, 2, 2", "false, 2, 2",
                "false, 34, 3"})
        @DisplayName("decode to their samples in either bit order, whatever the padding of their rows")
        void roundTrip(boolean msbFirst, int width, int height) {
            var writer = new RafWriter();
            writer.width = width;
            writer.height = height;
            writer.bits = 12;
            writer.scene(width * 31L + height);
            var parsed = new RawFile[1];
            var plane = decode(writer.packed12(msbFirst, null), parsed);
            assertArrayEquals(writer.samples, plane.integers);
            assertEquals(12, parsed[0].sensor.bitsPerSample);
            assertEquals("uncompressed", parsed[0].sensor.compression);
        }

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({"0a0b0908, RGGB", "08090b0a, BGGR", "090a080b, GRBG", "0b08090a, BGRG", "0c0c0c0c, RGGB",
                "08080b0a, RGGB", "0a0b09, RGGB", "-, RGGB"})
        @DisplayName("take the Bayer array from record 0x0130, stored from its last photosite, or else RGGB")
        void pattern(String record, String expected) {
            var writer = new RafWriter();
            writer.bits = 12;
            writer.scene(7);
            var layout = record.equals("-") ? null : java.util.HexFormat.of().parseHex(record);
            var parsed = new RawFile[1];
            decode(writer.packed12(false, layout), parsed);
            assertEquals(expected, parsed[0].sensor.cfa.toString().replace("/", ""));
        }

        @Test
        @DisplayName("a file of an unknown bit order is not decodable")
        void unknownOrder() {
            var writer = new RafWriter();
            writer.bits = 12;
            writer.scene(3);
            var file = writer.packed12(true, null);
            // the value of 0xF006 in the sensor directory: the entry after 0xF005
            var at = indexOf(file, new byte[]{0x06, (byte) 0xF0, 4, 0, 1, 0, 0, 0});
            file[at + 8] = 2;
            try (var source = MediaSource.of(file)) {
                var info = RawDecoder.info(source);
                assertTrue(info.unsupportedFeature().orElseThrow().contains("packing"));
            }
        }

        private static int indexOf(byte[] data, byte[] pattern) {
            for (var index = 0; index + pattern.length <= data.length; index++) {
                if (Arrays.equals(data, index, index + pattern.length, pattern, 0, pattern.length)) {
                    return index;
                }
            }
            throw new AssertionError("entry not found");
        }
    }

    @Nested
    @DisplayName("files of older models")
    class Legacy {
        // stored words numbered by position, so that each decoded value tells where it came from
        static short[] numbered(int rows, int columns) {
            var words = new short[rows * columns];
            for (var index = 0; index < words.length; index++) {
                words[index] = (short) (index % 16000);
            }
            return words;
        }

        @Test
        @DisplayName("a SuperCCD file counted in the stored grid keeps its rows, staggered")
        void stored() {
            var writer = new RafWriter();
            var words = numbered(40, 64);
            var file = writer.legacy(words, 40, 64, false, 0x01, new int[]{2, 8, 36, 48}, new int[]{4, 12, 30, 40});
            var parsed = new RawFile[1];
            var plane = decode(file, parsed);
            var sensor = parsed[0].sensor;
            assertEquals(48, sensor.width);
            assertEquals(36, sensor.height);
            assertEquals(words[2 * 64 + 8], plane.integers[0]);
            assertEquals(words[(2 + 35) * 64 + 8 + 47], plane.integers[35 * 48 + 47]);
            assertEquals(CfaLayout.STAGGERED_A, sensor.layout);
            assertEquals("RGBG:BGRG", sensor.cfa.toString().replace('/', ':'));
            assertArrayEquals(new double[]{4, 2, 40, 30}, sensor.defaultCrop);
            assertEquals(1, parsed[0].orientation);
            assertEquals(Math.sqrt(0.5), sensor.scaleH, 1e-12);
            assertEquals(Math.sqrt(2), sensor.scaleV, 1e-12);
            assertEquals(256, sensor.black[0]);
            assertArrayEquals(new double[]{302 / 512.0, 1, 302 / 410.0}, parsed[0].color.asShotNeutral, 1e-12);
        }

        @Test
        @DisplayName("a transposed SuperCCD file counted in turned units is read column by column and transposed for display")
        void transposed() {
            var writer = new RafWriter();
            var words = numbered(80, 40);
            // area top 2, left 8, height 30, width 64: plane 60 wide (from stored row 4), 32 high (from stored column 4)
            var file = writer.legacy(words, 80, 40, false, 0x83, new int[]{2, 8, 30, 64}, null);
            var parsed = new RawFile[1];
            var plane = decode(file, parsed);
            var sensor = parsed[0].sensor;
            assertEquals(60, sensor.width);
            assertEquals(32, sensor.height);
            for (var y = 0; y < 32; y++) {
                for (var x = 0; x < 60; x++) {
                    assertEquals(words[(4 + x) * 40 + 4 + y], plane.integers[y * 60 + x]);
                }
            }
            assertEquals(5, parsed[0].orientation);
            assertNull(sensor.defaultCrop);
        }

        @Test
        @DisplayName("an untransposed SuperCCD file counted in turned units is turned a quarter for display")
        void turned() {
            var writer = new RafWriter();
            var words = numbered(50, 70);
            // the stored grid cannot hold the area as counted, so it is read in turned units: 60 wide, 40 high
            var file = writer.legacy(words, 50, 70, true, 0x00, new int[]{3, 10, 30, 80}, new int[]{4, 12, 28, 76});
            var parsed = new RawFile[1];
            var plane = decode(file, parsed);
            var sensor = parsed[0].sensor;
            assertEquals(60, sensor.width);
            assertEquals(40, sensor.height);
            assertEquals(words[5 * 70 + 6], plane.integers[0]);
            assertEquals(12, sensor.bitsPerSample);
            assertEquals(6, parsed[0].orientation);
            assertArrayEquals(new double[]{2, 1, 56, 38}, sensor.defaultCrop);
        }

        @Test
        @DisplayName("a Bayer file keeps its rows and an RGGB array")
        void bayer() {
            var writer = new RafWriter();
            var words = numbered(30, 40);
            var file = writer.legacy(words, 30, 40, false, 0x0A, null, null);
            var parsed = new RawFile[1];
            var plane = decode(file, parsed);
            assertArrayEquals(words, plane.integers);
            assertEquals(CfaLayout.RECTANGULAR, parsed[0].sensor.layout);
            assertEquals("RGGB", parsed[0].sensor.cfa.toString());
        }

        @ParameterizedTest(name = "{0} outputs")
        @ValueSource(ints = {2, 8})
        @DisplayName("a file of a sensor of several outputs reads its stored grid from the stripes its header states")
        void outputStripes(int outputs) {
            var writer = new RafWriter();
            writer.stripes = outputs;
            var words = numbered(24, 64);
            var file = writer.legacy(words, 24, 64, true, 0x01, new int[]{2, 4, 20, 56}, null);
            var parsed = new RawFile[1];
            var plane = decode(file, parsed);
            var sensor = parsed[0].sensor;
            assertEquals(56, sensor.width);
            assertEquals(20, sensor.height);
            for (var y = 0; y < 20; y++) {
                for (var x = 0; x < 56; x++) {
                    assertEquals(words[(2 + y) * 64 + 4 + x], plane.integers[y * 56 + x], "at " + x + ", " + y);
                }
            }
            assertEquals(12, sensor.bitsPerSample);
            assertEquals(CfaLayout.STAGGERED_A, sensor.layout);
        }

        @Test
        @DisplayName("a file whose header cuts its rows into chunks that are not the rows of whole stripes is not "
                + "decodable")
        void unevenStripes() {
            var writer = new RafWriter();
            writer.stripes = 2;
            var file = writer.legacy(numbered(24, 60), 24, 60, false, 0x01, null, null);
            // 7 words do not divide the stored rows of 60
            ByteBuffer.wrap(file).putInt(112, 14).putInt(116, 14 * 24);
            try (var source = MediaSource.of(file)) {
                assertTrue(RawDecoder.info(source).unsupportedFeature().isPresent());
            }
        }

        @Test
        @DisplayName("a file with fewer words than its stored size is not decodable")
        void notDecodable() {
            var writer = new RafWriter();
            var words = numbered(30, 40);
            var shortFile = writer.legacy(Arrays.copyOf(words, 100), 30, 40, false, 0x00, null, null);
            try (var source = MediaSource.of(shortFile)) {
                assertTrue(RawDecoder.info(source).unsupportedFeature().isPresent());
            }
        }
    }

    @Nested
    @DisplayName("files of two images per photosite")
    class TwoImages {
        // a packed 12-bit EXR-like file: the second image differs from the first everywhere
        private static RafWriter exr(int width, int height) {
            var writer = new RafWriter();
            writer.bits = 12;
            writer.width = width;
            writer.height = height;
            writer.black = 64;
            writer.scene(width * 7L + height);
            writer.second = new short[width * height];
            for (var index = 0; index < writer.second.length; index++) {
                writer.second[index] = (short) ((writer.samples[index] * 3 + 7 + index) & 0xFFF);
            }
            return writer;
        }

        // the scaled 16-bit value of a stored sample, as RawImage holds it
        private static int scaled(int stored, int black, int white) {
            return (int) Math.round(Math.clamp((double) (stored - black) / (white - black), 0, 1) * 65535);
        }

        @Test
        @DisplayName("an EXR file of the image's output size holds both images as the two samples of each photosite")
        void sameSite() {
            var writer = exr(32, 8);
            writer.output = new int[]{32, 8};
            var file = writer.packed12(false, new byte[]{10, 11, 9, 8});
            var parsed = new RawFile[1];
            var plane = decode(file, parsed);
            var sensor = parsed[0].sensor;
            assertEquals(2, sensor.samplesPerPixel);
            assertEquals(32, sensor.width);
            assertEquals(CfaLayout.RECTANGULAR, sensor.layout);
            assertEquals("RGGB", sensor.cfa.toString());
            for (var index = 0; index < 32 * 8; index++) {
                assertEquals(writer.samples[index], plane.integers[2 * index], "first image at " + index);
                assertEquals(writer.second[index], plane.integers[2 * index + 1], "second image at " + index);
            }
            assertArrayEquals(new double[]{64, 64, 64, 64, 64, 64, 64, 64}, sensor.black);
            var image = RawDecoder.decodeRaw(MediaSource.of(file));
            assertEquals(2, image.channels());
            assertEquals(CfaLayout.RECTANGULAR, image.cfaLayout());
            // single-precision linearization may round the last unit the other way
            for (var index = 0; index < 32 * 8; index++) {
                assertEquals(scaled(writer.samples[index], 64, 4095), image.samples()[2 * index] & 0xFFFF, 1);
                assertEquals(scaled(writer.second[index], 64, 4095), image.samples()[2 * index + 1] & 0xFFFF, 1);
            }
            var picture = RawDecoder.decode(MediaSource.of(file)).picture();
            assertEquals(32, picture.width());
            assertEquals(8, picture.height());
        }

        @Test
        @DisplayName("an EXR file whose output has twice the image's area interleaves the two images into one staggered "
                + "grid, the second half a row down")
        void diagonal() {
            var writer = exr(32, 8);
            writer.output = new int[]{45, 11};
            var file = writer.packed12(true, new byte[]{8, 9, 11, 10});
            var parsed = new RawFile[1];
            var plane = decode(file, parsed);
            var sensor = parsed[0].sensor;
            assertEquals(1, sensor.samplesPerPixel);
            assertEquals(64, sensor.width);
            assertEquals(8, sensor.height);
            assertEquals(CfaLayout.STAGGERED_A, sensor.layout);
            assertEquals("BBGG/GGRR", sensor.cfa.toString());
            for (var y = 0; y < 8; y++) {
                for (var x = 0; x < 32; x++) {
                    assertEquals(writer.samples[y * 32 + x], plane.integers[y * 64 + 2 * x]);
                    assertEquals(writer.second[y * 32 + x], plane.integers[y * 64 + 2 * x + 1]);
                }
            }
            assertEquals(Math.sqrt(0.5), sensor.scaleH, 1e-12);
            assertEquals(Math.sqrt(2), sensor.scaleV, 1e-12);
            var info = RawDecoder.info(MediaSource.of(file));
            assertEquals(CfaLayout.STAGGERED_A, info.cfaLayout());
            var image = RawDecoder.decodeRaw(MediaSource.of(file));
            assertEquals(1, image.channels());
            assertEquals(64, image.width());
            var picture = RawDecoder.decode(MediaSource.of(file)).picture();
            assertEquals(64, picture.width());
            assertEquals(16, picture.height());
        }

        @Test
        @DisplayName("a SuperCCD SR file holds its two photodiodes as the two samples of each staggered photosite")
        void superCcdSr() {
            var writer = new RafWriter();
            var words = Legacy.numbered(40, 64);
            writer.second = new short[words.length];
            for (var index = 0; index < words.length; index++) {
                writer.second[index] = (short) (words[index] / 16 + 1);
            }
            var file = writer.legacy(words, 40, 64, false, 0x01, new int[]{2, 8, 36, 48}, new int[]{4, 12, 30, 40});
            var parsed = new RawFile[1];
            var plane = decode(file, parsed);
            var sensor = parsed[0].sensor;
            assertEquals(2, sensor.samplesPerPixel);
            assertEquals(48, sensor.width);
            assertEquals(36, sensor.height);
            assertEquals(CfaLayout.STAGGERED_A, sensor.layout);
            for (var y = 0; y < 36; y++) {
                for (var x = 0; x < 48; x++) {
                    var stored = (2 + y) * 64 + 8 + x;
                    assertEquals(words[stored], plane.integers[(y * 48 + x) * 2]);
                    assertEquals(writer.second[stored], plane.integers[(y * 48 + x) * 2 + 1]);
                }
            }
            assertArrayEquals(new double[]{256, 256}, sensor.black);
            assertArrayEquals(new double[]{16383, 16383}, sensor.white);
            assertArrayEquals(new double[]{4, 2, 40, 30}, sensor.defaultCrop);
            assertEquals(2, RawDecoder.decodeRaw(MediaSource.of(file)).channels());
        }

        @Test
        @DisplayName("a transposed two-image file reads both images column by column")
        void transposed() {
            var writer = new RafWriter();
            var words = Legacy.numbered(80, 40);
            writer.second = new short[words.length];
            for (var index = 0; index < words.length; index++) {
                writer.second[index] = (short) (16000 - words[index]);
            }
            var file = writer.legacy(words, 80, 40, false, 0x83, new int[]{2, 8, 30, 64}, null);
            var parsed = new RawFile[1];
            var plane = decode(file, parsed);
            assertEquals(2, parsed[0].sensor.samplesPerPixel);
            for (var y = 0; y < 32; y++) {
                for (var x = 0; x < 60; x++) {
                    assertEquals(words[(4 + x) * 40 + 4 + y], plane.integers[(y * 60 + x) * 2]);
                    assertEquals(writer.second[(4 + x) * 40 + 4 + y], plane.integers[(y * 60 + x) * 2 + 1]);
                }
            }
            assertEquals(5, parsed[0].orientation);
        }

        @Test
        @DisplayName("a transposed SuperCCD SR file whose two images alternate row by row, as the F700's do, reads each "
                + "image from its own rows, whatever its record 0x0122 holds")
        void rowInterleaved() {
            var writer = new RafWriter();
            writer.interleaved = true;
            var words = Legacy.numbered(80, 40);
            writer.second = new short[words.length];
            for (var index = 0; index < words.length; index++) {
                writer.second[index] = (short) (16000 - words[index]);
            }
            var file = writer.legacy(words, 80, 40, false, 0x80, new int[]{0, 8, 40, 64}, null);
            // the F700's record 0x0122, which marks no outputs
            var at = indexOf(file, new byte[]{0x01, 0x22, 0x00, 0x08}) + 4;
            System.arraycopy(new byte[]{0, 0, 0, 0x10, 0x04, 0x3C, 0, 0x10}, 0, file, at, 8);
            var parsed = new RawFile[1];
            var plane = decode(file, parsed);
            var sensor = parsed[0].sensor;
            assertEquals(2, sensor.samplesPerPixel);
            assertEquals(80, sensor.width);
            assertEquals(32, sensor.height);
            for (var y = 0; y < 32; y++) {
                for (var x = 0; x < 80; x++) {
                    assertEquals(words[x * 40 + 4 + y], plane.integers[(y * 80 + x) * 2], "first image at " + x + ", "
                            + y);
                    assertEquals(writer.second[x * 40 + 4 + y], plane.integers[(y * 80 + x) * 2 + 1],
                            "second image at " + x + ", " + y);
                }
            }
            assertEquals(5, parsed[0].orientation);
            // read as two images stored whole, the second image's rows are the first's
            var whole = file.clone();
            ByteBuffer.wrap(whole).putInt(112, 0).putInt(116, 0);
            var wrong = decode(whole, parsed);
            assertNotEquals(words[40 + 4], wrong.integers[2]);
        }

        // the index of the first occurrence of a byte sequence
        private static int indexOf(byte[] data, byte[] sequence) {
            for (var index = 0; index + sequence.length <= data.length; index++) {
                if (Arrays.equals(data, index, index + sequence.length, sequence, 0, sequence.length)) {
                    return index;
                }
            }
            throw new AssertionError("no such bytes");
        }

        @Test
        @DisplayName("an older Bayer file whose output has twice the image's area interleaves its two images, the "
                + "second in the even columns as the SuperCCD EXR models place it")
        void legacyDiagonal() {
            var writer = new RafWriter();
            var words = Legacy.numbered(30, 40);
            writer.second = new short[words.length];
            for (var index = 0; index < words.length; index++) {
                writer.second[index] = (short) (words[index] + 1);
            }
            writer.output = new int[]{2 * 40, 30};
            var file = writer.legacy(words, 30, 40, false, 0x0A, null, new int[]{2, 4, 26, 32});
            // the second image's black level of its own record list differs, so each level follows its image
            var data = ByteBuffer.wrap(file);
            var list = data.getInt(120);
            for (int count = data.getInt(list), at = list + 4, index = 0; index < count; index++) {
                var tag = data.getShort(at) & 0xFFFF;
                var size = data.getShort(at + 2) & 0xFFFF;
                for (var level = 0; tag == 0x4000 && level < size / 2; level++) {
                    data.putShort(at + 4 + 2 * level, (short) 260);
                }
                at += 4 + size;
            }
            var parsed = new RawFile[1];
            var plane = decode(file, parsed);
            var sensor = parsed[0].sensor;
            assertEquals(1, sensor.samplesPerPixel);
            assertEquals(80, sensor.width);
            assertEquals(CfaLayout.STAGGERED_A, sensor.layout);
            assertEquals("RRGG/GGBB", sensor.cfa.toString());
            for (var index = 0; index < words.length; index++) {
                assertEquals(writer.second[index], plane.integers[2 * index]);
                assertEquals(words[index], plane.integers[2 * index + 1]);
            }
            assertArrayEquals(new double[]{8, 2, 64, 26}, sensor.defaultCrop);
            assertArrayEquals(new double[]{260, 256}, sensor.black);
            assertEquals(2, sensor.blackColumns);
        }

        @Test
        @DisplayName("a second image shorter than the stored size, or of another size, is left out")
        void leftOut() {
            var writer = new RafWriter();
            var words = Legacy.numbered(30, 40);
            writer.second = Arrays.copyOf(words, 100);
            var parsed = new RawFile[1];
            var plane = decode(writer.legacy(words, 30, 40, false, 0x0A, null, null), parsed);
            assertEquals(1, parsed[0].sensor.samplesPerPixel);
            assertArrayEquals(words, plane.integers);
            var file = exr(32, 8).packed12(false, null);
            // the second block's width, 0xF001 first in its private directory, no longer matches the first's
            var second = (int) bigEndian32(file, 128);
            var directory = second + 8 + 2 + 12 + 4 + 2;
            assertEquals(0xF001, (file[directory] & 0xFF) | (file[directory + 1] & 0xFF) << 8);
            file[directory + 8] = 31;
            decode(file, parsed);
            assertEquals(1, parsed[0].sensor.samplesPerPixel);
        }

        @Test
        @DisplayName("a damaged two-image file raises only MediaException")
        void damaged() {
            var exr = exr(32, 8).packed12(false, null);
            var random = new Random(41);
            for (var trial = 0; trial < 200; trial++) {
                var file = exr.clone();
                for (var flip = 0; flip < 4; flip++) {
                    var at = 84 + random.nextInt(file.length - 84);
                    file[at] = (byte) random.nextInt(256);
                }
                var cut = Arrays.copyOf(file, 120 + random.nextInt(file.length - 120));
                for (var candidate : new byte[][]{file, cut}) {
                    try (var source = MediaSource.of(candidate)) {
                        RawDecoder.decode(source);
                    } catch (MediaException expected) {
                        // a damaged file may fail, but only this way
                    }
                }
            }
        }

        @Test
        @DisplayName("the highlights a SuperCCD SR file's low-sensitivity photodiodes hold render beyond the clipping "
                + "of its primary photodiodes")
        void highlights() {
            // a ramp of light up to 8 times the primary photodiodes' white; the secondary ones are 16 times less
            // sensitive, so they hold the ramp to its end
            var rows = 24;
            var columns = 96;
            var primary = new short[rows * columns];
            var secondary = new short[rows * columns];
            for (var y = 0; y < rows; y++) {
                for (var x = 0; x < columns; x++) {
                    var light = 256 + 8.0 * 16127 * x / (columns - 1);
                    primary[y * columns + x] = (short) Math.min(16383, Math.round(light));
                    secondary[y * columns + x] = (short) Math.round(256 + (light - 256) / 16);
                }
            }
            var dual = new RafWriter();
            dual.second = secondary;
            var options = RawDecoderOptions.builder().exposure(-4).toneCurve(RawToneCurve.LINEAR).highBitDepth(true)
                    .build();
            var merged = RawDecoder.decode(MediaSource.of(dual.legacy(primary, rows, columns, false, 0x0A, null, null)),
                    options).picture();
            var single = RawDecoder.decode(MediaSource.of(new RafWriter().legacy(primary, rows, columns, false, 0x0A,
                    null, null)), options).picture();
            var mergedRamp = ramp(merged);
            var singleRamp = ramp(single);
            // past the primary photodiodes' white (an eighth of the ramp) the single image is flat, the merged one rises
            // with the light (5.7 times from start to end, about 2.3 times once encoded for sRGB)
            var start = columns / 6;
            var end = columns - 4;
            assertTrue(mergedRamp[end] > 1.8 * mergedRamp[start], "merged " + mergedRamp[start] + " to "
                    + mergedRamp[end]);
            assertEquals(singleRamp[start], singleRamp[end], singleRamp[start] * 0.02, "single " + singleRamp[start]
                    + " to " + singleRamp[end]);
            for (var x = 4; x < end; x++) {
                assertTrue(mergedRamp[x + 1] >= mergedRamp[x] * 0.98, "the merged ramp falls at " + x);
            }
            // below the blend, the merged rendering equals the single one
            assertEquals(singleRamp[4], mergedRamp[4], singleRamp[4] * 0.03);
        }

        // the mean of the green channel of each column of the middle rows of a rendering
        private static double[] ramp(Picture picture) {
            var rgba = ImageOps.toRgba16(picture);
            var means = new double[rgba.width()];
            for (var x = 0; x < rgba.width(); x++) {
                for (var y = rgba.height() / 4; y < rgba.height() * 3 / 4; y++) {
                    means[x] += rgba.pixels()[y * rgba.stride() + x * 4 + 1] & 0xFFFF;
                }
            }
            return means;
        }

        private static long bigEndian32(byte[] data, int at) {
            return (data[at] & 0xFFL) << 24 | (data[at + 1] & 0xFFL) << 16 | (data[at + 2] & 0xFFL) << 8
                    | data[at + 3] & 0xFFL;
        }
    }

    @Test
    @DisplayName("orientations compose as transforms applied one after the other")
    void compose() {
        assertEquals(5, RafParser.compose(1, 5));
        assertEquals(6, RafParser.compose(1, 6));
        assertEquals(1, RafParser.compose(5, 5));
        assertEquals(3, RafParser.compose(6, 6));
        assertEquals(2, RafParser.compose(6, 5));
        assertEquals(4, RafParser.compose(8, 5));
    }
}
