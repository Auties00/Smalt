package com.github.auties00.smalt.image.jpeg.internal;

import com.github.auties00.smalt.image.Channel16Picture;
import com.github.auties00.smalt.image.Channel8Picture;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.PixelLayout;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.jpeg.JpegDecoder;
import com.github.auties00.smalt.image.jpeg.JpegEncoderOptions;
import com.github.auties00.smalt.image.jpeg.JpegEncoderOptions.ColorSpace;
import com.github.auties00.smalt.image.jpeg.JpegEncoderOptions.Sampling;
import com.github.auties00.smalt.image.jpeg.JpegEncoderOptions.Scan;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the general encoding path where no external encoder is an oracle: it must write exactly the baseline
 * writer's bytes for the frames both write, its 12-bit and 8-bit plain-division transform must equal the
 * reciprocal-quantizing kernel, and lossless frames (arithmetic-coded ones included, which libjpeg-turbo does not
 * write) must decode to their samples exactly in every predictor, point transform, precision, color space,
 * subsampling, script and restart interval.
 */
@DisplayName("JpegEncoding")
class JpegEncodingTest {
    static RgbaPicture rgba(int width, int height, long seed) {
        var random = new Random(seed);
        var picture = RgbaPicture.allocate(width, height, false);
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var i = y * picture.stride() + 4 * x;
                picture.pixels()[i] = (byte) (x * 255 / Math.max(1, width - 1) + random.nextInt(24));
                picture.pixels()[i + 1] = (byte) (y * 255 / Math.max(1, height - 1) ^ random.nextInt(16));
                picture.pixels()[i + 2] = (byte) ((x + y) * 3 + random.nextInt(64));
                picture.pixels()[i + 3] = (byte) 255;
            }
        }
        return picture;
    }

    static Rgba16Picture rgba16(int width, int height, long seed) {
        var random = new Random(seed);
        var picture = Rgba16Picture.allocate(width, height, false);
        for (var i = 0; i < width * height; i++) {
            for (var c = 0; c < 3; c++) {
                picture.pixels()[4 * i + c] = (short) ((i * 523 + c * 20000 + random.nextInt(3000)) & 0xFFFF);
            }
            picture.pixels()[4 * i + 3] = (short) 0xFFFF;
        }
        return picture;
    }

    static YuvPicture yuv(int width, int height, ChromaFormat format, long seed) {
        var random = new Random(seed);
        var picture = YuvPicture.allocate(width, height, format, false, ColorInfo.JPEG);
        for (var i = 0; i < picture.y().length; i++) {
            picture.y()[i] = (byte) (i * 7 + random.nextInt(40));
        }
        if (format.hasChroma()) {
            for (var i = 0; i < picture.u().length; i++) {
                picture.u()[i] = (byte) (128 + random.nextInt(60) - 30);
                picture.v()[i] = (byte) (i * 3 + random.nextInt(10));
            }
        }
        return picture;
    }

    static Yuv16Picture yuv16(int width, int height, ChromaFormat format, int depth, long seed) {
        var random = new Random(seed);
        var picture = Yuv16Picture.allocate(width, height, format, depth, false, ColorInfo.JPEG);
        var mask = (1 << depth) - 1;
        for (var i = 0; i < picture.y().length; i++) {
            picture.y()[i] = (short) ((i * 37 + random.nextInt(500)) & mask);
        }
        if (format.hasChroma()) {
            for (var i = 0; i < picture.u().length; i++) {
                picture.u()[i] = (short) ((i * 11 + random.nextInt(900)) & mask);
                picture.v()[i] = (short) ((i * 5 + random.nextInt(90)) & mask);
            }
        }
        return picture;
    }

    @Nested
    @DisplayName("the general path")
    class GeneralPath {
        static List<Arguments> baselineFrames() {
            var cases = new ArrayList<Arguments>();
            var sizes = new int[][]{{1, 1}, {8, 8}, {17, 9}, {61, 37}, {130, 67}};
            var formats = new ChromaFormat[]{ChromaFormat.YUV420, ChromaFormat.YUV422, ChromaFormat.YUV444,
                    ChromaFormat.MONOCHROME};
            for (var size : sizes) {
                for (var format : formats) {
                    for (var optimize : new boolean[]{false, true}) {
                        cases.add(Arguments.of(size[0], size[1], format, optimize, optimize ? 0 : 3));
                    }
                }
            }
            return cases;
        }

        @ParameterizedTest(name = "{0}x{1} {2} optimize={3} restart={4}")
        @MethodSource("baselineFrames")
        @DisplayName("writes the baseline writer's bytes for RGB and planar input")
        void equalsBaselineWriter(int width, int height, ChromaFormat format, boolean optimize, int restart) {
            var options = JpegEncoderOptions.builder().quality(83).chromaQuality(61).chromaFormat(format)
                    .optimizeHuffman(optimize).restartInterval(restart).build();
            for (var picture : List.<Picture>of(rgba(width, height, width * 31L + height), yuv(width, height,
                    format, 7))) {
                assertArrayEquals(JpegEncoding.encode(picture, options, true), JpegEncoding.encode(picture, options,
                        false));
            }
        }

        @Test
        @DisplayName("narrows 16-bit pictures to 8 bits as the baseline writer does")
        void sixteenBit() {
            var options = JpegEncoderOptions.ofQuality(90);
            var picture = rgba16(45, 31, 3);
            assertArrayEquals(JpegEncoding.encode(picture, options, true), JpegEncoding.encode(picture, options, false));
            var yuv = yuv16(45, 31, ChromaFormat.YUV420, 10, 5);
            assertArrayEquals(JpegEncoding.encode(yuv, options, true), JpegEncoding.encode(yuv, options, false));
        }
    }

    @Nested
    @DisplayName("the plain-division forward DCT")
    class Fdct {
        @Test
        @DisplayName("equals the reciprocal kernel at 8 bits for every quantizer up to 255")
        void equalsScalarKernel() {
            var random = new Random(11);
            var kernel = new ScalarFdctKernel();
            var bytes = new byte[64];
            var shorts = new short[64];
            var table = new int[64];
            var expected = new int[64];
            var actual = new short[64];
            var scratch = new long[64];
            for (var round = 0; round < 4000; round++) {
                var extreme = round % 5;
                for (var i = 0; i < 64; i++) {
                    var value = switch (extreme) {
                        case 0 -> random.nextInt(256);
                        case 1 -> random.nextBoolean() ? 0 : 255;
                        case 2 -> 255;
                        case 3 -> 0;
                        default -> (i * 4 + random.nextInt(3)) & 0xFF;
                    };
                    bytes[i] = (byte) value;
                    shorts[i] = (short) value;
                    table[i] = round % 7 == 0 ? 1 : 1 + random.nextInt(255);
                }
                kernel.transform(bytes, 0, 8, new QuantDivisors(table), expected);
                WideFdct.transform(shorts, 0, 8, 8, table, actual, 0, scratch);
                for (var i = 0; i < 64; i++) {
                    assertEquals(expected[i], actual[i], "coefficient " + i + " of round " + round);
                }
            }
        }
    }

    @Nested
    @DisplayName("lossless frames")
    class Lossless {
        static List<Arguments> frames() {
            var cases = new ArrayList<Arguments>();
            for (var arithmetic : new boolean[]{false, true}) {
                for (var predictor = 1; predictor <= 7; predictor++) {
                    cases.add(Arguments.of("rgb8 p" + predictor, rgba(37, 23, predictor), JpegEncoderOptions.builder()
                            .lossless(true).arithmetic(arithmetic).predictor(predictor).colorSpace(ColorSpace.RGB)
                            .build()));
                }
                cases.add(Arguments.of("rgb8 pt3 restart", rgba(37, 23, 9), JpegEncoderOptions.builder().lossless(true)
                        .arithmetic(arithmetic).predictor(4).pointTransform(3).restartInterval(74)
                        .colorSpace(ColorSpace.RGB).build()));
                cases.add(Arguments.of("rgb16", rgba16(29, 17, 4), JpegEncoderOptions.builder().lossless(true)
                        .arithmetic(arithmetic).predictor(6).colorSpace(ColorSpace.RGB).build()));
                cases.add(Arguments.of("rgb16 at 12 bits", rgba16(29, 17, 4), JpegEncoderOptions.builder()
                        .lossless(true).arithmetic(arithmetic).predictor(5).precision(12).colorSpace(ColorSpace.RGB)
                        .build()));
                cases.add(Arguments.of("rgb8 at 3 bits", rgba(29, 17, 6), JpegEncoderOptions.builder().lossless(true)
                        .arithmetic(arithmetic).predictor(7).precision(3).pointTransform(1).colorSpace(ColorSpace.RGB)
                        .build()));
                for (var format : new ChromaFormat[]{ChromaFormat.YUV420, ChromaFormat.YUV422, ChromaFormat.YUV444,
                        ChromaFormat.MONOCHROME}) {
                    cases.add(Arguments.of("yuv8 " + format, yuv(33, 19, format, 2), JpegEncoderOptions.builder()
                            .lossless(true).arithmetic(arithmetic).predictor(4).chromaFormat(format)
                            .restartInterval(format == ChromaFormat.YUV444 || format == ChromaFormat.MONOCHROME ? 66
                                    : 34).build()));
                    cases.add(Arguments.of("yuv10 " + format, yuv16(33, 19, format, 10, 3), JpegEncoderOptions
                            .builder().lossless(true).arithmetic(arithmetic).predictor(1).chromaFormat(format).build()));
                    cases.add(Arguments.of("yuv16 " + format, yuv16(21, 14, format, 16, 8), JpegEncoderOptions
                            .builder().lossless(true).arithmetic(arithmetic).predictor(7).chromaFormat(format).build()));
                }
                // a 9-bit YCbCr frame of three 1x1 components has the shape of a reversibly transformed RGB file
                cases.add(Arguments.of("yuv9 444", yuv16(26, 15, ChromaFormat.YUV444, 9, 21), JpegEncoderOptions
                        .builder().lossless(true).arithmetic(arithmetic).predictor(2).chromaFormat(ChromaFormat.YUV444)
                        .build()));
                cases.add(Arguments.of("yuv8 420 script", yuv(33, 19, ChromaFormat.YUV420, 12), JpegEncoderOptions
                        .builder().lossless(true).arithmetic(arithmetic).chromaFormat(ChromaFormat.YUV420)
                        .scanScript(Scan.of(3, 0, 0, 1, 0), Scan.of(2, 0, 0, 0, 1, 2)).build()));
                cases.add(Arguments.of("yuv8 444 factors", yuv(33, 19, ChromaFormat.YUV444, 13), JpegEncoderOptions.builder()
                        .lossless(true).arithmetic(arithmetic).samplingFactors(Sampling.of(1, 1), Sampling.of(1, 1),
                                Sampling.of(1, 1)).build()));
                var cmyk = Channel8Picture.allocate(23, 11, PixelLayout.CMYK);
                var random = new Random(17);
                for (var i = 0; i < cmyk.pixels().length; i++) {
                    cmyk.pixels()[i] = (byte) random.nextInt(256);
                }
                cases.add(Arguments.of("cmyk8", cmyk, JpegEncoderOptions.builder().lossless(true).arithmetic(arithmetic)
                        .predictor(2).colorSpace(ColorSpace.CMYK).build()));
                var cmyk16 = Channel16Picture.allocate(23, 11, PixelLayout.CMYK);
                for (var i = 0; i < cmyk16.pixels().length; i++) {
                    cmyk16.pixels()[i] = (short) random.nextInt(65536);
                }
                cases.add(Arguments.of("cmyk16", cmyk16, JpegEncoderOptions.builder().lossless(true)
                        .arithmetic(arithmetic).predictor(3).colorSpace(ColorSpace.CMYK).build()));
            }
            return cases;
        }

        @ParameterizedTest(name = "{0} {2}")
        @MethodSource("frames")
        @DisplayName("decode to exactly the coded samples")
        void exact(String name, Picture picture, JpegEncoderOptions options) {
            var jpeg = JpegEncoding.encode(picture, options);
            assertEquals(options.arithmetic() ? 0xCB : 0xC3, sofMarker(jpeg), name);
            // each arithmetic scan states its DC conditioning; a Huffman scan carries a DHT instead
            assertEquals(options.arithmetic(), firstScanTables(jpeg) == 0xCC, name);
            var keepChannels = options.colorSpace() == ColorSpace.CMYK;
            var decoded = JpegDecoder.decode(MediaSource.of(jpeg), ImageDecoderOptions.builder().highBitDepth(true)
                    .keepChannels(keepChannels).build()).picture();
            var precision = options.precision().orElse(Math.min(16, picture.bitDepth()));
            var pt = options.scanScript().isEmpty() ? options.pointTransform() : -1;
            for (var y = 0; y < picture.height(); y++) {
                for (var x = 0; x < picture.width(); x++) {
                    for (var c = 0; c < channels(picture); c++) {
                        var component = c;
                        var shift = pt >= 0 ? pt : options.scanScript().stream().filter(s -> s.components()
                                .contains(component)).findFirst().orElseThrow().successiveLow();
                        var expected = (coded(picture, x, y, c, precision) >> shift) << shift;
                        var actual = decodedSample(decoded, x, y, c, precision);
                        if (expected != actual) {
                            throw new AssertionError(name + ": channel " + c + " at " + x + "," + y + " decodes to "
                                    + actual + ", not " + expected);
                        }
                    }
                }
            }
        }

        private static int channels(Picture picture) {
            return switch (picture) {
                case YuvPicture yuv -> yuv.chromaFormat().hasChroma() ? 3 : 1;
                case Yuv16Picture yuv -> yuv.chromaFormat().hasChroma() ? 3 : 1;
                case Channel8Picture _, Channel16Picture _ -> 4;
                default -> 3;
            };
        }

        /**
         * Returns the sample the encoder codes at full resolution: chroma of a subsampled picture at its co-sited
         * position, rescaled to the precision.
         */
        private static int coded(Picture picture, int x, int y, int c, int precision) {
            int value;
            int depth;
            switch (picture) {
                case RgbaPicture rgba -> {
                    value = rgba.pixels()[y * rgba.stride() + 4 * x + c] & 0xFF;
                    depth = 8;
                }
                case Rgba16Picture rgba -> {
                    value = rgba.pixels()[y * rgba.stride() + 4 * x + c] & 0xFFFF;
                    depth = 16;
                }
                case YuvPicture yuv -> {
                    var format = yuv.chromaFormat();
                    value = c == 0 ? yuv.y()[y * yuv.yStride() + x] & 0xFF
                            : (c == 1 ? yuv.u() : yuv.v())[(y >> format.shiftY()) * yuv.uvStride() + (x >> format.shiftX())]
                            & 0xFF;
                    depth = 8;
                }
                case Yuv16Picture yuv -> {
                    var format = yuv.chromaFormat();
                    value = c == 0 ? yuv.y()[y * yuv.yStride() + x] & 0xFFFF
                            : (c == 1 ? yuv.u() : yuv.v())[(y >> format.shiftY()) * yuv.uvStride() + (x >> format.shiftX())]
                            & 0xFFFF;
                    depth = yuv.bitDepth();
                }
                case Channel8Picture channels -> {
                    value = 255 - (channels.pixels()[y * channels.stride() + 4 * x + c] & 0xFF);
                    depth = 8;
                }
                case Channel16Picture channels -> {
                    value = 65535 - (channels.pixels()[y * channels.stride() + 4 * x + c] & 0xFFFF);
                    depth = 16;
                }
                default -> throw new AssertionError();
            }
            var maxIn = (1L << depth) - 1;
            var maxOut = (1L << precision) - 1;
            return (int) ((value * maxOut + maxIn / 2) / maxIn);
        }

        private static int decodedSample(Picture decoded, int x, int y, int c, int precision) {
            var max = (1 << precision) - 1;
            return switch (decoded) {
                case RgbaPicture rgba -> rescale(rgba.pixels()[y * rgba.stride() + 4 * x + c] & 0xFF, 255, max);
                case Rgba16Picture rgba -> rescale(rgba.pixels()[y * rgba.stride() + 4 * x + c] & 0xFFFF, 65535, max);
                case YuvPicture yuv -> {
                    var format = yuv.chromaFormat();
                    var value = c == 0 ? yuv.y()[y * yuv.yStride() + x] & 0xFF
                            : (c == 1 ? yuv.u() : yuv.v())[(y >> format.shiftY()) * yuv.uvStride() + (x >> format.shiftX())]
                            & 0xFF;
                    yield rescale(value, 255, max);
                }
                case Yuv16Picture yuv -> {
                    var format = yuv.chromaFormat();
                    yield c == 0 ? yuv.y()[y * yuv.yStride() + x] & 0xFFFF
                            : (c == 1 ? yuv.u() : yuv.v())[(y >> format.shiftY()) * yuv.uvStride() + (x >> format.shiftX())]
                            & 0xFFFF;
                }
                case Channel8Picture channels -> {
                    var ink = channels.pixels()[y * channels.stride() + 4 * x + c] & 0xFF;
                    yield max - rescale(ink, 255, max);
                }
                case Channel16Picture channels -> {
                    var ink = channels.pixels()[y * channels.stride() + 4 * x + c] & 0xFFFF;
                    yield max - rescale(ink, 65535, max);
                }
                default -> throw new AssertionError("unexpected " + decoded);
            };
        }

        private static int rescale(int value, int from, int to) {
            return from == to ? value : (int) Math.round(value * (double) to / from);
        }
    }

    @Nested
    @DisplayName("the ITU-T T.83 compliance stream P1")
    class Compliance {
        static byte[] entry(String name) {
            var archive = Conformance.fetchListed("itu-t83-data-files.zip");
            try (var zip = new ZipFile(archive.toFile())) {
                try (var input = zip.getInputStream(zip.getEntry(name))) {
                    return input.readAllBytes();
                }
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }

        // P1 (T.81 process 15) codes four 8-bit components of 255x257 samples sampled 1x1, 1x2, 3x1 and 1x4,
        // interleaved with predictor 1, a table per component, the default conditioning and a restart interval
        // of one MCU row. Without its COM segment the stream is exactly what the encoder writes for its samples.
        @Test
        @DisplayName("is what the arithmetic lossless encoder writes for its samples, byte for byte")
        void reencoded() {
            var expected = withoutComments(entry("T83_process15/P1.JPG"));
            var layout = new FrameLayout(255, 257, 8, true, ColorSpace.CMYK, new int[]{200, 150, 100, 50},
                    new int[]{1, 1, 3, 1}, new int[]{1, 2, 1, 4}, new int[4], new int[]{0, 1, 2, 3});
            var bytes = new byte[4][];
            var strides = new int[4];
            for (var c = 0; c < 4; c++) {
                bytes[c] = entry("T83_encoder_8/" + (char) ('A' + c) + "8.SRC");
                strides[c] = layout.sampleWidth[c];
                assertEquals(layout.sampleWidth[c] * layout.sampleHeight[c], bytes[c].length, "component " + c);
            }
            var planes = new ComponentBands.Planes(null, bytes, strides, null, 0xFF);
            var header = new CoefficientWriter.Header(false, 1, 1, 0, 1, 1, false, 0);
            var actual = new LosslessFrameEncoder(layout, null, planes, 85, true).write(header, List.of(),
                    List.of(new CoefficientWriter.Scan(new int[]{0, 1, 2, 3}, 1, 0, 0, 0)));
            assertArrayEquals(expected, actual);
        }

        private static byte[] withoutComments(byte[] jpeg) {
            var out = new ByteArrayOutputStream();
            out.write(jpeg, 0, 2);
            var position = 2;
            while ((jpeg[position + 1] & 0xFF) != 0xDA) {
                var length = 2 + (((jpeg[position + 2] & 0xFF) << 8) | (jpeg[position + 3] & 0xFF));
                if ((jpeg[position + 1] & 0xFF) != 0xFE) {
                    out.write(jpeg, position, length);
                }
                position += length;
            }
            out.write(jpeg, position, jpeg.length - position);
            return out.toByteArray();
        }
    }

    /**
     * Returns the code of the first SOF marker of a file.
     */
    static int sofMarker(byte[] jpeg) {
        var position = 2;
        while (position + 4 <= jpeg.length) {
            var marker = jpeg[position + 1] & 0xFF;
            if (marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                return marker;
            }
            position += 2 + (((jpeg[position + 2] & 0xFF) << 8) | (jpeg[position + 3] & 0xFF));
        }
        throw new AssertionError("no SOF marker");
    }

    static int firstScanTables(byte[] jpeg) {
        var position = 2;
        var tables = -1;
        while (position + 4 <= jpeg.length) {
            var marker = jpeg[position + 1] & 0xFF;
            if (marker == 0xDA) {
                return tables;
            }
            if (marker == 0xC4 || marker == 0xCC) {
                tables = marker;
            }
            position += 2 + (((jpeg[position + 2] & 0xFF) << 8) | (jpeg[position + 3] & 0xFF));
        }
        throw new AssertionError("no SOS marker");
    }

    @Test
    @DisplayName("encodes random combinations of settings and pictures into files the decoder reads back")
    void randomSettings() {
        var random = new Random(20261022);
        var spaces = ColorSpace.values();
        for (var round = 0; round < 400; round++) {
            var width = 1 + random.nextInt(70);
            var height = 1 + random.nextInt(50);
            Picture picture = switch (random.nextInt(6)) {
                case 0 -> rgba(width, height, round);
                case 1 -> yuv(width, height, ChromaFormat.values()[random.nextInt(4)], round);
                case 2 -> rgba16(width, height, round);
                case 3 -> yuv16(width, height, ChromaFormat.values()[random.nextInt(4)], 9 + random.nextInt(8), round);
                case 4 -> {
                    var cmyk = Channel8Picture.allocate(width, height, PixelLayout.CMYK);
                    for (var i = 0; i < cmyk.pixels().length; i++) {
                        cmyk.pixels()[i] = (byte) random.nextInt(256);
                    }
                    yield cmyk;
                }
                default -> rgba(width, height, -round);
            };
            var builder = JpegEncoderOptions.builder();
            var space = spaces[random.nextInt(spaces.length)];
            builder.colorSpace(space);
            var formats = space == ColorSpace.YCBCR ? ChromaFormat.values()
                    : new ChromaFormat[]{ChromaFormat.YUV420, ChromaFormat.YUV422, ChromaFormat.YUV444};
            builder.chromaFormat(formats[random.nextInt(formats.length)]);
            var lossless = random.nextInt(4) == 0;
            builder.lossless(lossless).arithmetic(random.nextBoolean()).optimizeHuffman(random.nextBoolean());
            if (!lossless) {
                builder.progressive(random.nextBoolean());
                builder.quality(random.nextInt(101));
                if (random.nextBoolean()) {
                    builder.chromaQuality(random.nextInt(101));
                }
                builder.baselineQuantization(random.nextInt(3) != 0);
                if (random.nextBoolean()) {
                    builder.precision(random.nextBoolean() ? 8 : 12);
                }
            } else {
                var precision = 2 + random.nextInt(15);
                if (random.nextBoolean()) {
                    builder.precision(precision);
                    builder.pointTransform(random.nextInt(precision));
                }
                builder.predictor(1 + random.nextInt(7));
            }
            if (random.nextInt(3) == 0) {
                var factors = new ArrayList<Sampling>();
                var count = 1 + random.nextInt(space == ColorSpace.YCBCR || space == ColorSpace.RGB ? 3 : 4);
                for (var c = 0; c < count; c++) {
                    factors.add(Sampling.of(1 << random.nextInt(3), 1 << random.nextInt(2)));
                }
                if (space == ColorSpace.YCBCR && !builder.build().chromaFormat().hasChroma()) {
                    factors = new ArrayList<>(factors.subList(0, 1));
                }
                builder.samplingFactors(factors);
            }
            if (random.nextInt(4) == 0) {
                builder.restartInterval(1 + random.nextInt(20));
            }
            var options = builder.build();
            byte[] jpeg;
            try {
                jpeg = JpegEncoding.encode(picture, options);
            } catch (IllegalArgumentException expected) {
                var message = expected.getMessage();
                assertTrue(message.contains("data units per MCU") || message.contains("restart interval")
                        || message.contains("point transform"), message);
                continue;
            }
            var decoded = JpegDecoder.decode(MediaSource.of(jpeg), ImageDecoderOptions.defaults()).picture();
            assertEquals(width, decoded.width(), options.toString());
            assertEquals(height, decoded.height(), options.toString());
        }
    }

    @Test
    @DisplayName("names the frame process in the SOF marker")
    void processMarkers() {
        var picture = rgba(40, 20, 1);
        var cases = new Object[][]{
                {JpegEncoderOptions.defaults(), 0xC0},
                {JpegEncoderOptions.builder().samplingFactors(Sampling.of(1, 2)).build(), 0xC0},
                {JpegEncoderOptions.builder().quality(5).baselineQuantization(false).build(), 0xC1},
                {JpegEncoderOptions.builder().precision(12).build(), 0xC1},
                {JpegEncoderOptions.ofProgressive(75), 0xC2},
                {JpegEncoderOptions.ofLossless(), 0xC3},
                {JpegEncoderOptions.builder().arithmetic(true).build(), 0xC9},
                {JpegEncoderOptions.builder().arithmetic(true).progressive(true).build(), 0xCA},
                {JpegEncoderOptions.builder().arithmetic(true).lossless(true).build(), 0xCB}};
        for (var c : cases) {
            assertEquals((int) c[1], sofMarker(JpegEncoding.encode(picture, (JpegEncoderOptions) c[0])),
                    String.valueOf(c[0]));
        }
        assertTrue(JpegEncoding.encode(picture, JpegEncoderOptions.defaults()).length > 0);
    }
}
