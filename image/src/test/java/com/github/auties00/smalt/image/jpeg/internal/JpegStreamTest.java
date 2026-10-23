package com.github.auties00.smalt.image.jpeg.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.jpeg.JpegDecoder;
import com.github.auties00.smalt.image.jpeg.MjpegDecoder;
import com.github.auties00.smalt.image.jpeg.JpegEncoder;
import com.github.auties00.smalt.image.jpeg.JpegEncoderOptions;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Quality;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Decodes crafted streams that exercise the rarer parts of the format: color spaces other than
 * YCbCr, arbitrary component identifiers and scan orders, 16-bit quantization tables, DNL, missing
 * Huffman tables, restart resynchronization, truncation and junk, unsupported codings, and limits.
 */
class JpegStreamTest {
    private static final int WIDTH = 45;
    private static final int HEIGHT = 29;

    /**
     * Returns a smooth pseudo-random plane, so that transform losses stay small.
     */
    static byte[] plane(int width, int height, int seed) {
        var random = new Random(seed);
        var a = random.nextInt(200) + 20;
        var fx = random.nextDouble() * 0.2;
        var fy = random.nextDouble() * 0.2;
        var plane = new byte[width * height];
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                plane[y * width + x] = (byte) Math.clamp((long) (a + 30 * Math.sin(fx * x) + 30 * Math.cos(fy * y)), 0, 255);
            }
        }
        return plane;
    }

    static Picture decode(byte[] jpeg) {
        return JpegDecoder.decode(MediaSource.of(jpeg), ImageDecoderOptions.defaults()).picture();
    }

    static JpegPlanes planes(byte[] jpeg) {
        return new JpegReader(jpeg, 0, jpeg.length, MediaLimits.DEFAULT).decodePlanes(0);
    }

    static byte[] rgbaPixels(Picture picture) {
        var rgba = (RgbaPicture) picture;
        var out = new byte[rgba.width() * rgba.height() * 4];
        for (var y = 0; y < rgba.height(); y++) {
            System.arraycopy(rgba.pixels(), y * rgba.stride(), out, y * rgba.width() * 4, rgba.width() * 4);
        }
        return out;
    }

    static TestJpegWriter fourComponents(int[] ids) {
        var planes = new byte[4][];
        for (var c = 0; c < 4; c++) {
            planes[c] = plane(WIDTH, HEIGHT, c + 10);
        }
        return TestJpegWriter.fromPlanes(WIDTH, HEIGHT, ids, new int[]{1, 1, 1, 1}, new int[]{1, 1, 1, 1}, planes, 95);
    }

    @Nested
    @DisplayName("color spaces")
    class ColorSpaces {
        @ParameterizedTest(name = "Adobe transform {0}")
        @ValueSource(ints = {-1, 0})
        @DisplayName("CMYK decodes to inverted-CMYK RGB, R = C * K / 255")
        void cmyk(int transform) {
            var writer = fourComponents(new int[]{'C', 'M', 'Y', 'K'});
            writer.jfif = false;
            writer.adobeTransform = transform;
            var jpeg = writer.sequential(new int[]{0, 1, 2, 3});
            var decoded = planes(jpeg);
            var pixels = rgbaPixels(decode(jpeg));
            for (var y = 0; y < HEIGHT; y++) {
                for (var x = 0; x < WIDTH; x++) {
                    var k = decoded.plane(3)[y * decoded.stride(3) + x] & 0xFF;
                    for (var c = 0; c < 3; c++) {
                        var value = decoded.plane(c)[y * decoded.stride(c) + x] & 0xFF;
                        assertEquals(Math.round(value * k / 255.0), pixels[(y * WIDTH + x) * 4 + c] & 0xFF);
                    }
                    assertEquals(255, pixels[(y * WIDTH + x) * 4 + 3] & 0xFF);
                }
            }
        }

        @Test
        @DisplayName("YCCK decodes through libjpeg's YCCK to CMYK conversion")
        void ycck() {
            var writer = fourComponents(new int[]{1, 2, 3, 4});
            writer.jfif = false;
            writer.adobeTransform = 2;
            var jpeg = writer.sequential(new int[]{0, 1, 2, 3});
            var decoded = planes(jpeg);
            var pixels = rgbaPixels(decode(jpeg));
            for (var y = 0; y < HEIGHT; y++) {
                for (var x = 0; x < WIDTH; x++) {
                    var rgb = JpegOutput.yccToRgb(decoded.plane(0)[y * decoded.stride(0) + x] & 0xFF,
                            decoded.plane(1)[y * decoded.stride(1) + x] & 0xFF, decoded.plane(2)[y * decoded.stride(2) + x] & 0xFF);
                    var k = decoded.plane(3)[y * decoded.stride(3) + x] & 0xFF;
                    int[] cmy = {255 - (rgb >>> 16), 255 - ((rgb >>> 8) & 0xFF), 255 - (rgb & 0xFF)};
                    for (var c = 0; c < 3; c++) {
                        assertEquals(Math.round(cmy[c] * k / 255.0), pixels[(y * WIDTH + x) * 4 + c] & 0xFF, "pixel " + x + "," + y);
                    }
                }
            }
        }

        @ParameterizedTest(name = "jfif={0} adobe={1} ids={2}")
        @CsvSource({"false, 0, 1", "false, -1, 82", "true, -1, 82", "false, 1, 82", "false, -1, 1"})
        @DisplayName("infers RGB or YCbCr from JFIF, Adobe and component identifiers as libjpeg does")
        void rgbInference(boolean jfif, int adobe, int firstId) {
            var ids = firstId == 82 ? new int[]{'R', 'G', 'B'} : new int[]{1, 2, 3};
            var planes = new byte[][]{plane(WIDTH, HEIGHT, 1), plane(WIDTH, HEIGHT, 2), plane(WIDTH, HEIGHT, 3)};
            var writer = TestJpegWriter.fromPlanes(WIDTH, HEIGHT, ids, new int[]{1, 1, 1}, new int[]{1, 1, 1}, planes, 95);
            writer.jfif = jfif;
            writer.adobeTransform = adobe;
            var jpeg = writer.sequential(new int[]{0, 1, 2});
            var picture = decode(jpeg);
            var expectRgb = !jfif && (adobe == 0 || (adobe < 0 && firstId == 82));
            assertEquals(expectRgb, picture instanceof RgbaPicture);
            if (expectRgb) {
                var decoded = planes(jpeg);
                var pixels = rgbaPixels(picture);
                for (var i = 0; i < WIDTH * HEIGHT; i++) {
                    var y = i / WIDTH;
                    var x = i % WIDTH;
                    for (var c = 0; c < 3; c++) {
                        assertEquals(decoded.plane(c)[y * decoded.stride(c) + x], pixels[i * 4 + c]);
                    }
                }
            }
        }

        @Test
        @DisplayName("subsampled CMYK components are upsampled before conversion")
        void subsampledCmyk() {
            var planes = new byte[4][];
            int[] h = {2, 1, 1, 2};
            int[] v = {2, 1, 1, 2};
            for (var c = 0; c < 4; c++) {
                var w = JpegFrame.ceilDiv((long) WIDTH * h[c], 2);
                var hh = JpegFrame.ceilDiv((long) HEIGHT * v[c], 2);
                planes[c] = plane(w, hh, c);
            }
            var writer = TestJpegWriter.fromPlanes(WIDTH, HEIGHT, new int[]{1, 2, 3, 4}, h, v, planes, 90);
            writer.jfif = false;
            writer.adobeTransform = 0;
            var picture = decode(writer.sequential(new int[]{0, 1, 2, 3}));
            assertInstanceOf(RgbaPicture.class, picture);
            assertEquals(WIDTH, picture.width());
            assertEquals(HEIGHT, picture.height());
        }

        @ParameterizedTest(name = "luma {0}x{1}, chroma {2}x{3}")
        @CsvSource({"4, 1, 1, 1", "1, 2, 1, 1", "3, 1, 1, 1", "2, 2, 1, 2", "1, 1, 2, 2", "2, 2, 2, 1", "2, 1, 2, 1"})
        @DisplayName("sampling layouts without a matching chroma format decode to 4:4:4 of the right size, others map directly")
        void unusualSampling(int lh, int lv, int ch, int cv) {
            int[] h = {lh, ch, ch};
            int[] v = {lv, cv, cv};
            var hMax = Math.max(lh, ch);
            var vMax = Math.max(lv, cv);
            var planes = new byte[3][];
            for (var c = 0; c < 3; c++) {
                planes[c] = plane(JpegFrame.ceilDiv((long) WIDTH * h[c], hMax), JpegFrame.ceilDiv((long) HEIGHT * v[c], vMax), c);
            }
            var picture = (YuvPicture) decode(TestJpegWriter.fromPlanes(WIDTH, HEIGHT, new int[]{1, 2, 3}, h, v, planes, 90)
                    .sequential(new int[]{0, 1, 2}));
            var expected = lh == ch && lv == cv ? ChromaFormat.YUV444
                    : lh == 2 * ch && lv == cv && lh == hMax ? ChromaFormat.YUV422
                    : lh == 2 * ch && lv == 2 * cv ? ChromaFormat.YUV420
                    : lh == ch && lv == 2 * cv && lv == vMax ? ChromaFormat.YUV440 : ChromaFormat.YUV444;
            assertEquals(expected, picture.chromaFormat());
            assertEquals(WIDTH, picture.width());
            assertEquals(HEIGHT, picture.height());
        }
    }

    @Nested
    @DisplayName("stream structure")
    class Structure {
        private final byte[][] planes = {plane(WIDTH, HEIGHT, 1), plane((WIDTH + 1) / 2, (HEIGHT + 1) / 2, 2),
                plane((WIDTH + 1) / 2, (HEIGHT + 1) / 2, 3)};

        private TestJpegWriter writer(int... ids) {
            return TestJpegWriter.fromPlanes(WIDTH, HEIGHT, ids, new int[]{2, 1, 1}, new int[]{2, 1, 1}, planes, 85);
        }

        private byte[] reference() {
            return JpegTestSupportInternal.planar((YuvPicture) decode(writer(1, 2, 3).sequential(new int[]{0, 1, 2})));
        }

        @Test
        @DisplayName("decodes arbitrary component identifiers and non-interleaved scans in any order")
        void idsAndScanOrder() {
            var expected = reference();
            for (var order : new int[][][]{{{2}, {0}, {1}}, {{1}, {2}, {0}}, {{0, 2}, {1}}, {{2, 1}, {0}}}) {
                var jpeg = writer(200, 7, 0).sequential(order);
                assertArrayEquals(expected, JpegTestSupportInternal.planar((YuvPicture) decode(jpeg)), Arrays.deepToString(order));
            }
        }

        @Test
        @DisplayName("decodes frames whose components repeat one identifier")
        void duplicateIds() {
            var jpeg = writer(5, 5, 5).sequential(new int[]{0, 1, 2});
            assertArrayEquals(reference(), JpegTestSupportInternal.planar((YuvPicture) decode(jpeg)));
        }

        @Test
        @DisplayName("decodes 16-bit quantization tables in SOF1 frames")
        void sixteenBitTables() {
            var writer = writer(1, 2, 3);
            writer.sixteenBitQuant = true;
            writer.sofMarker = 0xC1;
            assertArrayEquals(reference(), JpegTestSupportInternal.planar((YuvPicture) decode(writer.sequential(new int[]{0, 1, 2}))));
        }

        @Test
        @DisplayName("takes the frame height from DNL, in decode and probe")
        void dnl() throws Exception {
            var writer = writer(1, 2, 3);
            writer.dnl = true;
            var jpeg = writer.sequential(new int[]{0, 1, 2});
            assertArrayEquals(reference(), JpegTestSupportInternal.planar((YuvPicture) decode(jpeg)));
            assertEquals(HEIGHT, JpegDecoder.probe(MediaSource.of(jpeg)).height());
            var progressive = writer.progressive(TestJpegWriter.simpleProgression(3, true));
            assertArrayEquals(reference(), JpegTestSupportInternal.planar((YuvPicture) decode(progressive)));
            var withoutDnl = removeMarker(jpeg, 0xDC);
            assertThrows(MediaException.Malformed.class, () -> decode(withoutDnl));
            assertThrows(MediaException.Malformed.class, () -> JpegDecoder.probe(MediaSource.of(withoutDnl)));
        }

        @Test
        @DisplayName("uses the Annex K tables when a stream has no DHT segment (Motion-JPEG)")
        void missingHuffmanTables() {
            var picture = RgbaPicture.allocate(64, 48, false);
            new Random(1).nextBytes(picture.pixels());
            var jpeg = JpegEncoder.encode(picture, JpegEncoderOptions.builder().quality(80)
                    .chromaFormat(ChromaFormat.YUV422).optimizeHuffman(false).build());
            var stripped = TestJpegWriter.removeSegments(jpeg, 0xC4);
            assertTrue(stripped.length < jpeg.length);
            var expected = JpegTestSupportInternal.planar((YuvPicture) decode(jpeg));
            assertArrayEquals(expected, JpegTestSupportInternal.planar((YuvPicture) decode(stripped)));
            assertArrayEquals(expected, JpegTestSupportInternal.planar((YuvPicture) new MjpegDecoder().decodeFrame(stripped, 0, stripped.length)));
            var padded = new byte[stripped.length + 10];
            System.arraycopy(stripped, 0, padded, 5, stripped.length);
            assertArrayEquals(expected, JpegTestSupportInternal.planar((YuvPicture) new MjpegDecoder().decodeFrame(padded, 5, stripped.length)));
        }

        @Test
        @DisplayName("ignores junk between segments, fill bytes, a missing EOI, and data after EOI")
        void junk() {
            var jpeg = writer(1, 2, 3).sequential(new int[]{0, 1, 2});
            var expected = reference();
            var withJunk = insertBefore(jpeg, 0xDB, new byte[]{1, 2, 3, (byte) 0xFF, 0, 7, (byte) 0xFF, (byte) 0xFF});
            assertArrayEquals(expected, JpegTestSupportInternal.planar((YuvPicture) decode(withJunk)));
            var noEoi = Arrays.copyOf(jpeg, jpeg.length - 2);
            assertArrayEquals(expected, JpegTestSupportInternal.planar((YuvPicture) decode(noEoi)));
            var trailing = Arrays.copyOf(jpeg, jpeg.length + 100);
            Arrays.fill(trailing, jpeg.length, trailing.length, (byte) 0xAB);
            assertArrayEquals(expected, JpegTestSupportInternal.planar((YuvPicture) decode(trailing)));
        }
    }

    @Nested
    @DisplayName("damaged entropy data")
    class Damage {
        private final RgbaPicture picture = picture();

        private static RgbaPicture picture() {
            var picture = RgbaPicture.allocate(96, 64, false);
            var random = new Random(9);
            for (var i = 0; i < 96 * 64; i++) {
                var shade = (byte) (i % 96 * 2 + random.nextInt(8));
                picture.pixels()[4 * i] = shade;
                picture.pixels()[4 * i + 1] = (byte) (i / 96 * 3);
                picture.pixels()[4 * i + 2] = (byte) random.nextInt(256);
                picture.pixels()[4 * i + 3] = (byte) 255;
            }
            return picture;
        }

        /**
         * Returns the indices of the RST markers of a stream.
         */
        private static int[] restartMarkers(byte[] jpeg) {
            var sos = 0;
            for (var i = 2; i + 1 < jpeg.length; i++) {
                if (jpeg[i] == (byte) 0xFF && jpeg[i + 1] == (byte) 0xDA) {
                    sos = i;
                    break;
                }
            }
            return java.util.stream.IntStream.range(sos, jpeg.length - 1)
                    .filter(i -> jpeg[i] == (byte) 0xFF && (jpeg[i + 1] & 0xF8) == 0xD0).toArray();
        }

        /**
         * Returns whether two decodes agree outside the rows of MCUs in the given range of MCU
         * rows (the picture has 6 MCUs per row with 4:2:0).
         */
        private static void assertEqualOutside(YuvPicture expected, YuvPicture actual, int fromRow, int toRow) {
            for (var y = 0; y < expected.height(); y++) {
                var mcuRow = y / 16;
                if (mcuRow >= fromRow && mcuRow <= toRow) {
                    continue;
                }
                assertArrayEquals(Arrays.copyOfRange(expected.y(), y * expected.yStride(), y * expected.yStride() + expected.width()),
                        Arrays.copyOfRange(actual.y(), y * actual.yStride(), y * actual.yStride() + actual.width()), "row " + y);
            }
        }

        @Test
        @DisplayName("resynchronizes at the next restart marker after a corrupt interval")
        void corruptInterval() {
            var jpeg = JpegEncoder.encode(picture, JpegEncoderOptions.builder().quality(90)
                    .chromaFormat(ChromaFormat.YUV420).optimizeHuffman(false).restartInterval(6).build());
            var clean = (YuvPicture) decode(jpeg);
            var markers = restartMarkers(jpeg);
            assertEquals(3, markers.length);
            var corrupt = jpeg.clone();
            for (var i = markers[0] + 10; i < markers[1] - 10; i += 3) {
                corrupt[i] = (byte) (corrupt[i] == (byte) 0xFF ? 0x7F : corrupt[i] ^ 0x5A);
            }
            var damaged = (YuvPicture) decode(corrupt);
            assertEqualOutside(clean, damaged, 1, 1);
        }

        @Test
        @DisplayName("recovers from a missing, a duplicated and a misnumbered restart marker")
        void markerDamage() {
            var jpeg = JpegEncoder.encode(picture, JpegEncoderOptions.builder().quality(90)
                    .chromaFormat(ChromaFormat.YUV420).optimizeHuffman(false).restartInterval(6).build());
            var clean = (YuvPicture) decode(jpeg);
            var markers = restartMarkers(jpeg);
            var missing = new ByteArrayOutputStream();
            missing.write(jpeg, 0, markers[1]);
            missing.write(jpeg, markers[1] + 2, jpeg.length - markers[1] - 2);
            assertEqualOutside(clean, (YuvPicture) decode(missing.toByteArray()), 1, 2);
            var duplicated = new ByteArrayOutputStream();
            duplicated.write(jpeg, 0, markers[1] + 2);
            duplicated.write(jpeg, markers[1], jpeg.length - markers[1]);
            assertEqualOutside(clean, (YuvPicture) decode(duplicated.toByteArray()), 1, 2);
            var misnumbered = jpeg.clone();
            misnumbered[markers[1] + 1] = (byte) 0xD6;
            assertDoesNotThrow(() -> decode(misnumbered));
        }

        @Test
        @DisplayName("decodes truncated files as gray below the cut, and rejects a file with no entropy data")
        void truncation() {
            var jpeg = JpegEncoder.encode(picture, JpegEncoderOptions.builder().quality(90)
                    .chromaFormat(ChromaFormat.YUV420).build());
            var sos = 0;
            for (var i = 2; i + 1 < jpeg.length; i++) {
                if (jpeg[i] == (byte) 0xFF && jpeg[i + 1] == (byte) 0xDA) {
                    sos = i + 2 + ((jpeg[i + 2] & 0xFF) << 8 | (jpeg[i + 3] & 0xFF));
                    break;
                }
            }
            var half = (YuvPicture) decode(Arrays.copyOf(jpeg, sos + (jpeg.length - sos) / 2));
            assertEquals(128, half.y()[(half.height() - 1) * half.yStride() + half.width() - 1] & 0xFF);
            var headers = Arrays.copyOf(jpeg, sos);
            assertThrows(MediaException.Malformed.class, () -> decode(headers));
            for (var cut = 0; cut < sos; cut += 7) {
                var truncated = Arrays.copyOf(jpeg, cut);
                assertThrows(MediaException.Malformed.class, () -> decode(truncated), "cut at " + cut);
            }
        }
    }

    @Nested
    @DisplayName("unsupported and invalid")
    class Unsupported {
        @ParameterizedTest(name = "SOF 0x{0}")
        @CsvSource({"C7, jpeg lossless", "CF, jpeg lossless",
                "C5, jpeg hierarchical", "C6, jpeg hierarchical", "CD, jpeg hierarchical", "CE, jpeg hierarchical",
                "F7, jpeg-ls"})
        @DisplayName("names the unsupported coding process")
        void codingProcess(String marker, String name) {
            var writer = TestJpegWriter.fromPlanes(8, 8, new int[]{1}, new int[]{1}, new int[]{1}, new byte[][]{plane(8, 8, 0)}, 90);
            writer.sofMarker = Integer.parseInt(marker, 16);
            var jpeg = writer.sequential(new int[]{0});
            var exception = assertThrows(MediaException.Unsupported.class, () -> decode(jpeg));
            assertEquals(name, exception.format());
            assertEquals(8, JpegDecoder.probe(MediaSource.of(jpeg)).width());
        }

        @Test
        @DisplayName("names the unsupported 16-bit DCT precision")
        void precision() {
            var writer = TestJpegWriter.fromPlanes(8, 8, new int[]{1}, new int[]{1}, new int[]{1}, new byte[][]{plane(8, 8, 0)}, 90);
            writer.precision = 16;
            writer.sofMarker = 0xC1;
            assertEquals("jpeg 16-bit precision",
                    assertThrows(MediaException.Unsupported.class, () -> decode(writer.sequential(new int[]{0}))).format());
        }

        @ParameterizedTest(name = "precision {0}")
        @ValueSource(ints = {0, 1, 7, 9, 10, 11, 13, 15, 255})
        @DisplayName("rejects a DCT precision other than 8, 12 and 16 as malformed")
        void invalidPrecision(int precision) {
            var writer = TestJpegWriter.fromPlanes(8, 8, new int[]{1}, new int[]{1}, new int[]{1}, new byte[][]{plane(8, 8, 0)}, 90);
            writer.precision = precision;
            writer.sofMarker = 0xC2;
            assertThrows(MediaException.Malformed.class, () -> decode(writer.sequential(new int[]{0})));
        }

        @ParameterizedTest(name = "SOF 0x{0}")
        @ValueSource(strings = {"C0", "C1"})
        @DisplayName("decodes a 12-bit Huffman sequential frame, baseline or extended, its transform centered at 2048")
        void twelveBit(String marker) {
            var writer = TestJpegWriter.fromPlanes(8, 8, new int[]{1}, new int[]{1}, new int[]{1}, new byte[][]{plane(8, 8, 0)}, 90);
            writer.precision = 12;
            writer.sofMarker = Integer.parseInt(marker, 16);
            var jpeg = writer.sequential(new int[]{0});
            var wide = (Yuv16Picture) JpegDecoder.decode(MediaSource.of(jpeg),
                    ImageDecoderOptions.builder().highBitDepth(true).build()).picture();
            assertEquals(12, wide.bitDepth());
            var reader = new JpegReader(jpeg, 0, jpeg.length, MediaLimits.DEFAULT);
            var coefficients = reader.decodeCoefficients();
            var block = new int[64];
            var expected = new short[64];
            Idct12.transformBlock(coefficients.coefficients(0), 0, coefficients.quantization(0), block, expected, 0, 8, 0);
            for (var y = 0; y < 8; y++) {
                for (var x = 0; x < 8; x++) {
                    assertEquals(expected[y * 8 + x], wide.y()[y * wide.yStride() + x]);
                }
            }
            // the 8-bit-coded content sits near 2048 + (v - 128) at 12 bits
            assertTrue(Math.abs(wide.y()[0] - 2048 - (plane(8, 8, 0)[0] & 0xFF) + 128) < 32);
        }

        @Test
        @DisplayName("names hierarchical markers and the JPG extension marker, rejects non-JPEG input")
        void markers() {
            var jpeg = JpegEncoder.encode(RgbaPicture.allocate(8, 8, false), JpegEncoderOptions.ofQuality(90));
            var dhp = insertBefore(jpeg, 0xDB, new byte[]{(byte) 0xFF, (byte) 0xDE, 0, 2});
            assertEquals("jpeg hierarchical", assertThrows(MediaException.Unsupported.class, () -> decode(dhp)).format());
            var jpg = insertBefore(jpeg, 0xDB, new byte[]{(byte) 0xFF, (byte) 0xC8, 0, 2});
            assertEquals("jpeg extensions", assertThrows(MediaException.Unsupported.class, () -> decode(jpg)).format());
            assertThrows(MediaException.Malformed.class, () -> decode(new byte[0]));
            assertThrows(MediaException.Malformed.class, () -> decode("GIF89a".getBytes()));
            assertThrows(MediaException.Malformed.class, () -> JpegDecoder.probe(MediaSource.of(new byte[]{(byte) 0xFF, (byte) 0xD8})));
        }

        @Test
        @DisplayName("checks sizes against the limits before allocating")
        void limits() {
            var jpeg = JpegEncoder.encode(RgbaPicture.allocate(300, 200, false), JpegEncoderOptions.ofQuality(90));
            var narrow = ImageDecoderOptions.builder().limits(MediaLimits.builder().maxDimension(256)
                    .maxPixels(1 << 20).maxFrames(1).maxChannels(1).maxBytes(1 << 30).build()).build();
            assertThrows(MediaException.LimitExceeded.class, () -> JpegDecoder.decode(MediaSource.of(jpeg), narrow));
            var few = ImageDecoderOptions.builder().limits(MediaLimits.builder().maxDimension(4096).maxPixels(50_000)
                    .maxFrames(1).maxChannels(1).maxBytes(1 << 30).build()).build();
            assertThrows(MediaException.LimitExceeded.class, () -> JpegDecoder.decode(MediaSource.of(jpeg), few));
            var small = ImageDecoderOptions.builder().limits(MediaLimits.builder().maxDimension(4096)
                    .maxPixels(1 << 20).maxFrames(1).maxChannels(1).maxBytes(1000).build()).build();
            assertThrows(MediaException.LimitExceeded.class, () -> JpegDecoder.decode(MediaSource.of(jpeg), small));
            var hostile = TestJpegWriter.fromPlanes(8, 8, new int[]{1}, new int[]{1}, new int[]{1}, new byte[][]{plane(8, 8, 0)}, 90)
                    .sequential(new int[]{0});
            hostile[findMarker(hostile, 0xC0) + 5] = (byte) 0xFF;
            hostile[findMarker(hostile, 0xC0) + 6] = (byte) 0xFF;
            hostile[findMarker(hostile, 0xC0) + 7] = (byte) 0xFF;
            hostile[findMarker(hostile, 0xC0) + 8] = (byte) 0xFF;
            assertThrows(MediaException.LimitExceeded.class, () -> decode(hostile));
        }
    }

    static int findMarker(byte[] jpeg, int marker) {
        for (var i = 2; i + 1 < jpeg.length; i++) {
            if (jpeg[i] == (byte) 0xFF && (jpeg[i + 1] & 0xFF) == marker) {
                return i;
            }
        }
        throw new IllegalArgumentException("marker not found");
    }

    static byte[] insertBefore(byte[] jpeg, int marker, byte[] bytes) {
        var at = findMarker(jpeg, marker);
        var out = new ByteArrayOutputStream();
        out.write(jpeg, 0, at);
        out.writeBytes(bytes);
        out.write(jpeg, at, jpeg.length - at);
        return out.toByteArray();
    }

    static byte[] removeMarker(byte[] jpeg, int marker) {
        var at = findMarker(jpeg, marker);
        var length = ((jpeg[at + 2] & 0xFF) << 8) | (jpeg[at + 3] & 0xFF);
        var out = new ByteArrayOutputStream();
        out.write(jpeg, 0, at);
        out.write(jpeg, at + 2 + length, jpeg.length - at - 2 - length);
        return out.toByteArray();
    }

    @Test
    @DisplayName("PSNR of CMYK decoding against the source stays high")
    void cmykFidelity() {
        var writer = fourComponents(new int[]{1, 2, 3, 4});
        writer.jfif = false;
        writer.adobeTransform = 0;
        var decoded = planes(writer.sequential(new int[]{0, 1, 2, 3}));
        for (var c = 0; c < 4; c++) {
            var expected = plane(WIDTH, HEIGHT, c + 10);
            var actual = new byte[WIDTH * HEIGHT];
            for (var y = 0; y < HEIGHT; y++) {
                System.arraycopy(decoded.plane(c), y * decoded.stride(c), actual, y * WIDTH, WIDTH);
            }
            assertTrue(Quality.psnr(expected, actual) > 40);
        }
    }
}
