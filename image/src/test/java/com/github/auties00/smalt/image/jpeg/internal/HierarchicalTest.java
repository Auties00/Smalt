package com.github.auties00.smalt.image.jpeg.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.jpeg.JpegDecoder;
import com.github.auties00.smalt.image.jpeg.JpegTransformOptions;
import com.github.auties00.smalt.image.jpeg.JpegTransforms;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Tolerance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the hierarchical mode of ITU-T T.81 annex J: the inverse DCT of differential frames, the J.1.1.2
 * upsampling, round trips through the test encoder of annex J (DCT and lossless frames, sequential and progressive,
 * Huffman and arithmetic lossless coding, every expansion, subsampled components, 8 to 16 bits) whose decode must
 * equal the encoder's reconstruction bit for bit, the streams of the ISO/IEC 10918-7 reference software A (also
 * arithmetic DCT coding, restart intervals and DNL) against its decode, and the decoder's handling of malformed
 * hierarchical streams.
 */
@DisplayName("hierarchical JPEG")
class HierarchicalTest {
    private static final String DIRECTORY = "/com/github/auties00/smalt/image/jpeg/hierarchical/";

    static int[][] components(byte[] jpeg) {
        var reader = new JpegReader(jpeg, 0, jpeg.length, MediaLimits.DEFAULT);
        reader.readHeaders();
        reader.checkSupported();
        if (reader.lossless()) {
            return JpegLsVectors.decode(jpeg);
        }
        var frame = reader.frame();
        var planes = frame.precision == 12 ? reader.decodeWidePlanes(0) : reader.decodePlanes(0);
        var result = new int[frame.components.length][];
        for (var c = 0; c < result.length; c++) {
            var component = frame.components[c];
            result[c] = new int[component.width * component.height];
            for (var y = 0; y < component.height; y++) {
                for (var x = 0; x < component.width; x++) {
                    result[c][y * component.width + x] = frame.precision == 12
                            ? planes.widePlane(c)[y * planes.stride(c) + x] : planes.plane(c)[y * planes.stride(c) + x] & 0xFF;
                }
            }
        }
        return result;
    }

    @Nested
    @DisplayName("inverse DCT")
    class Idct {
        @ParameterizedTest(name = "{0} bits")
        @ValueSource(ints = {8, 12})
        @DisplayName("of a non-differential block equals libjpeg-turbo's accurate integer transform")
        void plain(int precision) {
            var random = new Random(precision);
            var block = new int[64];
            var copy = new int[64];
            var expected8 = new byte[64];
            var expected12 = new short[64];
            var actual = new int[64];
            for (var trial = 0; trial < 2000; trial++) {
                var range = trial % 3 == 0 ? 2048 : trial % 3 == 1 ? 64 : 8;
                for (var k = 0; k < 64; k++) {
                    block[k] = k == 0 || random.nextInt(4) == 0 ? random.nextInt(2 * range + 1) - range : 0;
                }
                System.arraycopy(block, 0, copy, 0, 64);
                if (precision == 8) {
                    ScalarIdctKernel.INSTANCE.idct8(copy, expected8, 0, 8);
                } else {
                    Idct12.idct8(copy, expected12, 0, 8);
                }
                HierarchicalIdct.transform(block, precision, false, actual, 0, 8, 8, 8);
                for (var k = 0; k < 64; k++) {
                    assertEquals(precision == 8 ? expected8[k] & 0xFF : expected12[k], actual[k], "sample " + k);
                    assertEquals(0, block[k]);
                }
            }
        }

        @ParameterizedTest(name = "{0} bits")
        @ValueSource(ints = {8, 12})
        @DisplayName("of a differential block is the same transform without the level shift and the range limit")
        void differential(int precision) {
            var random = new Random(precision + 1);
            var block = new int[64];
            var copy = new int[64];
            var expected8 = new byte[64];
            var expected12 = new short[64];
            var actual = new int[64];
            var center = 1 << (precision - 1);
            var max = (1 << precision) - 1;
            var compared = 0;
            for (var trial = 0; trial < 2000; trial++) {
                for (var k = 0; k < 64; k++) {
                    block[k] = random.nextInt(5) == 0 ? random.nextInt(201) - 100 : 0;
                }
                System.arraycopy(block, 0, copy, 0, 64);
                if (precision == 8) {
                    ScalarIdctKernel.INSTANCE.idct8(copy, expected8, 0, 8);
                } else {
                    Idct12.idct8(copy, expected12, 0, 8);
                }
                HierarchicalIdct.transform(block, precision, true, actual, 0, 8, 8, 8);
                for (var k = 0; k < 64; k++) {
                    var limited = precision == 8 ? expected8[k] & 0xFF : expected12[k];
                    if (limited > 0 && limited < max) {
                        assertEquals(limited, actual[k] + center, "sample " + k);
                        compared++;
                    }
                }
            }
            assertTrue(compared > 100_000, "compared " + compared);
        }

        @Test
        @DisplayName("writes only the part of a block inside the component")
        void partial() {
            var block = new int[64];
            block[0] = 80;
            var output = new int[100];
            Arrays.fill(output, -7);
            HierarchicalIdct.transform(block, 8, true, output, 0, 10, 3, 2);
            for (var y = 0; y < 10; y++) {
                for (var x = 0; x < 10; x++) {
                    assertEquals(x < 3 && y < 2 ? 10 : -7, output[y * 10 + x], x + "," + y);
                }
            }
        }
    }

    @Nested
    @DisplayName("upsampling")
    class Upsampling {
        @Test
        @DisplayName("doubles by truncated averages, horizontally then vertically, and fits the frame size")
        void expand() {
            var random = new Random(3);
            for (var trial = 0; trial < 200; trial++) {
                var width = 1 + random.nextInt(9);
                var height = 1 + random.nextInt(9);
                var plane = new int[width * height];
                for (var i = 0; i < plane.length; i++) {
                    plane[i] = random.nextInt(65536);
                }
                var horizontal = random.nextBoolean();
                var vertical = random.nextBoolean();
                var w = horizontal ? 2 * width : width;
                var h = vertical ? 2 * height : height;
                var targetWidth = Math.max(1, w - random.nextInt(3) + 1);
                var targetHeight = Math.max(1, h - random.nextInt(3) + 1);
                var expected = HierarchicalTestWriter.upsample(plane, width, height, horizontal, vertical, targetWidth,
                        targetHeight);
                var expanded = HierarchicalImage.expand(plane, width, height, horizontal, vertical, MediaLimits.DEFAULT);
                var actual = HierarchicalImage.fit(expanded, w, h, targetWidth, targetHeight, MediaLimits.DEFAULT);
                assertArrayEquals(expected, actual, "trial " + trial);
            }
        }

        @Test
        @DisplayName("follows the example of J.1.1.2 on a line")
        void example() {
            var plane = new int[]{10, 21, 30};
            assertArrayEquals(new int[]{10, 15, 21, 25, 30, 30},
                    HierarchicalImage.expand(plane, 3, 1, true, false, MediaLimits.DEFAULT));
            assertArrayEquals(new int[]{10, 15, 21, 25, 30, 30},
                    HierarchicalImage.expand(plane, 1, 3, false, true, MediaLimits.DEFAULT));
        }
    }

    @Nested
    @DisplayName("annex J test encoder")
    class RoundTrips {
        static Stream<Arguments> settings() {
            var cases = new ArrayList<Arguments>();
            int[][][] layouts = {{{1, 1}}, {{1, 1}, {1, 1}, {1, 1}}, {{2, 2}, {1, 1}, {1, 1}}, {{1, 2}, {2, 1}, {1, 1}}};
            int[][] expansions = {{3}, {3, 3}, {2}, {1}, {0}, {3, 2, 1}};
            var index = 0;
            for (var layout : layouts) {
                for (var expansion : expansions) {
                    for (var mode = 0; mode < 6; mode++) {
                        var precision = switch (mode) {
                            case 0, 1, 2 -> 8;
                            case 3 -> 12;
                            case 4 -> 2 + index % 15;
                            default -> 16;
                        };
                        var dct = mode < 4;
                        var finalLossless = mode == 1 || mode == 3 && index % 2 == 0;
                        var progressive = mode == 2;
                        var arithmetic = mode == 5 || (mode == 4 && index % 2 == 1);
                        var name = (dct ? "DCT" : "lossless") + " " + precision + "-bit"
                                + (finalLossless ? ", final lossless" : "") + (progressive ? ", progressive" : "")
                                + (arithmetic ? ", arithmetic" : "") + ", expansions " + Arrays.toString(expansion)
                                + ", factors " + Arrays.deepToString(layout);
                        cases.add(Arguments.of(name, layout, new HierarchicalTestWriter.Settings(precision, dct,
                                finalLossless, progressive, arithmetic, expansion, 70 + index % 25), index++));
                    }
                }
            }
            return cases.stream();
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("settings")
        @DisplayName("decodes to the encoder's reconstruction")
        void decode(String name, int[][] factors, HierarchicalTestWriter.Settings settings, int index) {
            var width = 37 + index % 7;
            var height = 29 - index % 5;
            var source = JpegLsVectors.synthetic(width, height, factors.length, settings.precision(), 900 + index,
                    factors);
            var written = HierarchicalTestWriter.write(source, width, height, factors, settings);
            var decoded = components(written.stream());
            assertEquals(factors.length, decoded.length);
            for (var c = 0; c < factors.length; c++) {
                Tolerance.exact().assertWithin(name + " component " + c, written.expected()[c], decoded[c]);
            }
            var lossless = !settings.dct() || settings.finalLossless();
            if (lossless) {
                for (var c = 0; c < factors.length; c++) {
                    assertArrayEquals(source[c], decoded[c], "lossless component " + c);
                }
            }
            var reader = new JpegReader(written.stream(), 0, written.stream().length, MediaLimits.DEFAULT);
            reader.readHeaders();
            assertEquals(lossless, reader.lossless());
            assertTrue(reader.hierarchical());
            assertEquals(width, JpegDecoder.probe(MediaSource.of(written.stream())).width());
            assertEquals(height, JpegDecoder.probe(MediaSource.of(written.stream())).height());
        }

        @ParameterizedTest(name = "{0} bits")
        @ValueSource(ints = {8, 12})
        @DisplayName("keep the reconstructions of differential DCT frames unclamped, so a final lossless frame over"
                + " them is exact")
        void unclamped(int precision) {
            var width = 40;
            var height = 24;
            var max = (1 << precision) - 1;
            var source = new int[1][width * height];
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    source[0][y * width + x] = ((x / 2 + y / 3) & 1) == 0 ? 0 : max;
                }
            }
            var written = HierarchicalTestWriter.write(source, width, height, new int[][]{{1, 1}},
                    new HierarchicalTestWriter.Settings(precision, true, true, false, false, new int[]{3, 0}, 40));
            // the DCT reconstruction the final lossless frame refers to rings outside the range of the precision
            assertTrue(written.outOfRange() > 0, "no reference sample outside the range");
            assertArrayEquals(source[0], components(written.stream())[0]);
        }
    }

    record ReferenceCase(String name, int components, int bits, int width, int height, boolean lossless,
                         String options, int seed, String decoded, String reconstruction, String source) {
        @Override
        public String toString() {
            return name;
        }
    }

    static byte[] resource(String name) {
        try (var input = HierarchicalTest.class.getResourceAsStream(DIRECTORY + name)) {
            if (input == null) {
                throw new IllegalStateException("missing resource " + name);
            }
            return input.readAllBytes();
        } catch (java.io.IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
    }

    static List<ReferenceCase> referenceCases() {
        var cases = new ArrayList<ReferenceCase>();
        for (var line : new String(resource("hierarchical.tsv"), StandardCharsets.UTF_8).split("\n")) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            var r = line.split("\t");
            cases.add(new ReferenceCase(r[0], Integer.parseInt(r[1]), Integer.parseInt(r[2]), Integer.parseInt(r[3]),
                    Integer.parseInt(r[4]), Boolean.parseBoolean(r[5]), r[6], Integer.parseInt(r[7]), r[8], r[9], r[10]));
        }
        return cases;
    }

    @Nested
    @DisplayName("reference software A streams")
    class ReferenceA {
        static List<ReferenceCase> lossless() {
            return referenceCases().stream().filter(ReferenceCase::lossless).toList();
        }

        static List<ReferenceCase> dct() {
            return referenceCases().stream().filter(vector -> !vector.lossless()).toList();
        }

        static List<ReferenceCase> twins() {
            var names = referenceCases().stream().map(ReferenceCase::name).toList();
            return referenceCases().stream().filter(vector -> vector.lossless() && vector.name().endsWith("-arith")
                    && names.contains(vector.name().substring(0, vector.name().length() - 6))).toList();
        }

        // reference A's encoder rounds its upsampled references its own way, so its lossless hierarchies decode to
        // the T.81 reconstruction (t81decode.py), one off the source on some interpolated samples; over DCT frames
        // its reference is the unclamped DCT reconstruction, so the .raw holds the source with that reference
        // clamped as T.81 A.3.1 requires, which the decode matches up to the difference of the two inverse DCTs
        @ParameterizedTest(name = "{0}")
        @MethodSource("lossless")
        @DisplayName("whose last frames are lossless decode to the T.81 reconstruction, near the source image")
        void lossless(ReferenceCase vector) {
            var decoded = components(resource(vector.name() + ".jpg"));
            if (vector.options().contains("-q")) {
                var expected = raw(resource(vector.name() + ".raw"), decoded);
                for (var c = 0; c < decoded.length; c++) {
                    Tolerance.maxAbs(vector.bits() == 12 ? 48 : 3).and(Tolerance.psnr(vector.bits() == 12 ? 60 : 44))
                            .assertWithin(vector + " component " + c, expected[c], decoded[c]);
                }
                return;
            }
            if (!vector.reconstruction().equals("-")) {
                assertEquals(vector.reconstruction(), JpegLsVectors.sha256(decoded), vector.name());
            }
            var source = JpegLsVectors.synthetic(vector.width(), vector.height(), vector.components(), vector.bits(),
                    vector.seed(), null);
            var bound = vector.options().contains("-y 2") ? 1 : 2;
            for (var c = 0; c < vector.components(); c++) {
                assertEquals(source[c].length, decoded[c].length, vector + " component " + c);
                var worst = 0;
                for (var i = 0; i < source[c].length; i++) {
                    var difference = (decoded[c][i] - source[c][i]) & 0xFFFF;
                    worst = Math.max(worst, Math.min(difference, 0x10000 - difference));
                }
                assertTrue(worst <= bound, vector + " component " + c + " is " + worst + " off the source");
            }
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("twins")
        @DisplayName("arithmetic coded decode like their Huffman coded twins, which code the same differences")
        void twin(ReferenceCase vector) {
            var huffman = vector.name().substring(0, vector.name().length() - 6);
            var expected = components(resource(huffman + ".jpg"));
            var decoded = components(resource(vector.name() + ".jpg"));
            assertEquals(expected.length, decoded.length);
            for (var c = 0; c < expected.length; c++) {
                assertArrayEquals(expected[c], decoded[c], vector + " component " + c);
            }
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("dct")
        @DisplayName("of DCT frames decode within rounding of the reference's decode, which has its own inverse DCT")
        void close(ReferenceCase vector) {
            var decoded = components(resource(vector.name() + ".jpg"));
            var expected = raw(resource(vector.name() + ".raw"), decoded);
            for (var c = 0; c < decoded.length; c++) {
                Tolerance.maxAbs(vector.bits() == 12 ? 48 : 3).and(Tolerance.psnr(vector.bits() == 12 ? 60 : 44))
                        .assertWithin(vector + " component " + c, expected[c], decoded[c]);
            }
        }

        private static int[][] raw(byte[] raw, int[][] like) {
            var planes = new int[like.length][];
            var at = 0;
            for (var c = 0; c < like.length; c++) {
                planes[c] = new int[like[c].length];
                for (var i = 0; i < planes[c].length; i++) {
                    planes[c][i] = (raw[at] & 0xFF) | ((raw[at + 1] & 0xFF) << 8);
                    at += 2;
                }
            }
            assertEquals(raw.length, at, "component sizes");
            return planes;
        }
    }

    @Nested
    @DisplayName("through JpegDecoder")
    class Pictures {
        @Test
        @DisplayName("decode gray, RGB, 12-bit and Y'CbCr 4:2:0 hierarchical images to the usual picture types")
        void pictures() {
            var gray = assertInstanceOf(YuvPicture.class, JpegDecoder.decode(MediaSource.of(
                    resource("lossless8-gray-2.jpg"))).picture());
            var source = components(resource("lossless8-gray-2.jpg"))[0];
            for (var y = 0; y < 45; y++) {
                for (var x = 0; x < 67; x++) {
                    assertEquals(source[y * 67 + x], gray.y()[y * gray.yStride() + x] & 0xFF);
                }
            }
            var rgb = assertInstanceOf(RgbaPicture.class, JpegDecoder.decode(MediaSource.of(
                    resource("lossless8-rgb-2.jpg"))).picture());
            var planes = components(resource("lossless8-rgb-2.jpg"));
            for (var i = 0; i < 67 * 45; i++) {
                for (var c = 0; c < 3; c++) {
                    assertEquals(planes[c][i], rgb.pixels()[(i / 67) * rgb.stride() + 4 * (i % 67) + c] & 0xFF);
                }
            }
            var wide = assertInstanceOf(Yuv16Picture.class, JpegDecoder.decode(MediaSource.of(
                    resource("lossless16-gray-2.jpg")), ImageDecoderOptions.builder().highBitDepth(true).build())
                    .picture());
            assertEquals(16, wide.bitDepth());
            var deep = components(resource("lossless16-gray-2.jpg"))[0];
            for (var i = 0; i < deep.length; i++) {
                assertEquals(deep[i], wide.y()[(i / 67) * wide.yStride() + i % 67] & 0xFFFF);
            }
            var twelve = assertInstanceOf(Yuv16Picture.class, JpegDecoder.decode(MediaSource.of(
                    resource("dct12-gray-2.jpg")), ImageDecoderOptions.builder().highBitDepth(true).build()).picture());
            assertEquals(12, twelve.bitDepth());
            var ycc = assertInstanceOf(YuvPicture.class, JpegDecoder.decode(MediaSource.of(
                    resource("dct8-ycc420-2.jpg"))).picture());
            assertEquals(com.github.auties00.smalt.image.ChromaFormat.YUV420, ycc.chromaFormat());
            assertEquals(41, ycc.width());
            assertEquals(29, ycc.height());
        }

        @Test
        @DisplayName("ignore a size hint, and take the image height from the frames when the DHP segment has none")
        void hints() {
            var image = JpegDecoder.decode(MediaSource.of(resource("dct8-gray-2.jpg")),
                    ImageDecoderOptions.ofMinimumSize(4, 4));
            assertEquals(41, image.picture().width());
            var dnl = resource("dct8-gray-2-dnl.jpg");
            assertEquals(29, JpegDecoder.decode(MediaSource.of(dnl)).picture().height());
            assertEquals(29, JpegDecoder.probe(MediaSource.of(dnl)).height());
        }

        @Test
        @DisplayName("are rejected by the lossless transforms")
        void transforms() {
            var jpeg = resource("dct8-gray-2.jpg");
            var exception = assertThrows(MediaException.Unsupported.class,
                    () -> JpegTransforms.transform(MediaSource.of(jpeg), JpegTransformOptions.defaults()));
            assertEquals("jpeg hierarchical", exception.format());
        }
    }

    @Nested
    @DisplayName("malformed streams")
    class Malformed {
        private static byte[] stream() {
            var source = JpegLsVectors.synthetic(21, 17, 1, 8, 4, null);
            return HierarchicalTestWriter.write(source, 21, 17, new int[][]{{1, 1}},
                    new HierarchicalTestWriter.Settings(8, true, false, false, false, new int[]{3}, 80)).stream();
        }

        @Test
        @DisplayName("reject a differential frame before any non-differential one, and EXP outside hierarchical coding")
        void order() {
            var jpeg = stream();
            var sof = JpegLsWriterTest.indexOf(jpeg, 0xC1);
            var first = jpeg.clone();
            first[sof + 1] = (byte) 0xC5;
            assertThrows(MediaException.Malformed.class, () -> JpegDecoder.decode(MediaSource.of(first)));
            var plain = com.github.auties00.smalt.image.jpeg.JpegEncoder.encode(RgbaPicture.allocate(8, 8, false));
            var exp = TestJpegWriter.insertSegment(plain, 1, 0xDF, new byte[]{0x11});
            assertThrows(MediaException.Malformed.class, () -> JpegDecoder.decode(MediaSource.of(exp)));
            var plainSof = JpegLsWriterTest.indexOf(plain, 0xC0);
            var length = ((plain[plainSof + 2] & 0xFF) << 8) | (plain[plainSof + 3] & 0xFF);
            var after = plainSof + 2 + length;
            var late = new java.io.ByteArrayOutputStream();
            late.write(plain, 0, after);
            late.write(0xFF);
            late.write(0xDE);
            late.write(plain, plainSof + 2, length);
            late.write(plain, after, plain.length - after);
            assertThrows(MediaException.Malformed.class, () -> JpegDecoder.decode(MediaSource.of(late.toByteArray())));
        }

        @Test
        @DisplayName("reject frames that break the order of annex J, and two EXP segments before one frame")
        void annexOrder() {
            var dct = HierarchicalTestWriter.write(JpegLsVectors.synthetic(21, 17, 1, 8, 4, null), 21, 17,
                    new int[][]{{1, 1}}, new HierarchicalTestWriter.Settings(8, true, true, false, false,
                            new int[]{3, 3}, 80)).stream();
            var second = dct.clone();
            second[JpegLsWriterTest.indexOf(dct, 0xC5) + 1] = (byte) 0xC1;
            assertMalformed(second, "second non-differential frame");
            var afterLossless = dct.clone();
            afterLossless[JpegLsWriterTest.indexOf(dct, 0xC5) + 1] = (byte) 0xC7;
            assertMalformed(afterLossless, "must be its last");
            var lossless = HierarchicalTestWriter.write(JpegLsVectors.synthetic(21, 17, 1, 8, 4, null), 21, 17,
                    new int[][]{{1, 1}}, new HierarchicalTestWriter.Settings(8, false, false, false, false,
                            new int[]{3}, 80)).stream();
            var dctOverLossless = lossless.clone();
            dctOverLossless[JpegLsWriterTest.indexOf(lossless, 0xC7) + 1] = (byte) 0xC5;
            assertMalformed(dctOverLossless, "holds a DCT frame");
            var mixed = lossless.clone();
            mixed[JpegLsWriterTest.indexOf(lossless, 0xC7) + 1] = (byte) 0xC1;
            assertMalformed(mixed, "mix DCT and lossless");
            var exp = JpegLsWriterTest.indexOf(dct, 0xDF);
            var twice = new java.io.ByteArrayOutputStream();
            twice.write(dct, 0, exp + 5);
            twice.write(dct, exp, dct.length - exp);
            assertMalformed(twice.toByteArray(), "two EXP segments");
        }

        private static void assertMalformed(byte[] jpeg, String message) {
            var exception = assertThrows(MediaException.Malformed.class, () -> JpegDecoder.decode(MediaSource.of(jpeg)));
            assertTrue(exception.getMessage().contains(message), exception.getMessage());
        }

        @Test
        @DisplayName("reject frames that do not fit the DHP segment")
        void mismatch() {
            var jpeg = stream();
            var dhp = JpegLsWriterTest.indexOf(jpeg, 0xDE);
            var precision = jpeg.clone();
            precision[dhp + 4] = 12;
            assertThrows(MediaException.Malformed.class, () -> JpegDecoder.decode(MediaSource.of(precision)));
            var narrow = jpeg.clone();
            narrow[dhp + 8] = 5;
            assertThrows(MediaException.Malformed.class, () -> JpegDecoder.decode(MediaSource.of(narrow)));
            var id = jpeg.clone();
            id[dhp + 10] = 9;
            assertThrows(MediaException.Malformed.class, () -> JpegDecoder.decode(MediaSource.of(id)));
        }

        @Test
        @DisplayName("fail only as malformed when cut anywhere or corrupted")
        void robustness() {
            var jpeg = stream();
            for (var cut = 0; cut < jpeg.length; cut++) {
                var truncated = Arrays.copyOf(jpeg, cut);
                try {
                    JpegDecoder.decode(MediaSource.of(truncated));
                } catch (MediaException.Malformed expected) {
                    // the frames before the cut may not hold a whole image
                }
            }
            var random = new Random(11);
            for (var trial = 0; trial < 300; trial++) {
                var damaged = jpeg.clone();
                for (var flips = 0; flips < 4; flips++) {
                    damaged[random.nextInt(damaged.length)] ^= (byte) (1 << random.nextInt(8));
                }
                try {
                    JpegDecoder.decode(MediaSource.of(damaged));
                } catch (MediaException expected) {
                    assertFalse(expected instanceof MediaException.Io);
                }
            }
        }

        @Test
        @DisplayName("name 16-bit DCT frames as unsupported")
        void sixteenBit() {
            var jpeg = stream();
            var damaged = jpeg.clone();
            damaged[JpegLsWriterTest.indexOf(jpeg, 0xDE) + 4] = 16;
            for (var at = JpegLsWriterTest.indexOf(jpeg, 0xC1); at < damaged.length - 1; at++) {
                if ((damaged[at] & 0xFF) == 0xFF && ((damaged[at + 1] & 0xFF) == 0xC1 || (damaged[at + 1] & 0xFF) == 0xC5)) {
                    damaged[at + 4] = 16;
                }
            }
            assertEquals("jpeg 16-bit precision", assertThrows(MediaException.Unsupported.class,
                    () -> JpegDecoder.decode(MediaSource.of(damaged))).format());
        }
    }
}
