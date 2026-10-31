package com.github.auties00.smalt.image.raw;

import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.exif.ExifData;
import com.github.auties00.smalt.image.exif.ExifValue;
import com.github.auties00.smalt.image.jpeg.JpegDecoder;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.image.ops.ResizeFilter;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Quality;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.SplittableRandom;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the DNG encoder on synthetic sensor images: every image kind, pattern, compression and tile size reads
 * back through the decoder sample for sample with its color description, the file's structure follows the DNG
 * specification as an independent TIFF reader sees it, and the previews and metadata are what the options ask for.
 */
class DngEncoderTest {
    /** Linear sRGB primaries adapted to D50 (Bradford), rows summing to the D50 white. */
    static final double[] SRGB_D50 = {
            0.4360747, 0.3850649, 0.1430804,
            0.2225045, 0.7168786, 0.0606169,
            0.0139322, 0.0971045, 0.7141733};

    static final double[] NEUTRAL = {0.52, 1.0, 0.71};

    static final ByteOrder LE = ByteOrder.LITTLE_ENDIAN;

    static final List<CfaPattern> PATTERNS = List.of(CfaPattern.bayer("RGGB"), CfaPattern.bayer("BGGR"),
            CfaPattern.bayer("GRBG"), CfaPattern.bayer("GBRG"), xTrans());

    static CfaPattern xTrans() {
        var letters = "GGRGGBGGBGGRBRGRBGGGBGGRGGRGGBRBGBRG";
        var colors = new ArrayList<CfaColor>();
        for (var i = 0; i < letters.length(); i++) {
            colors.add(CfaColor.of("RGB".indexOf(letters.charAt(i))));
        }
        return CfaPattern.of(6, 6, colors);
    }

    /** A smooth scene with edges and noise, each photosite scaled by its color's response. */
    static short[] scene(int width, int height, int channels, CfaPattern cfa, long seed) {
        var random = new SplittableRandom(seed);
        var samples = new short[width * height * channels];
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                for (var c = 0; c < channels; c++) {
                    var plane = cfa != null ? cfa.color(y % cfa.height(), x % cfa.width()).code() % 3 : c;
                    var base = 0.15 + 0.6 * x / Math.max(1, width - 1) * (0.5 + 0.5 * Math.sin(y * 0.07 + plane));
                    if ((x / 16 + y / 16) % 3 == 0) {
                        base *= 0.4;
                    }
                    var value = base * NEUTRAL[plane] * 65535 + random.nextGaussian() * 300;
                    samples[(y * width + x) * channels + c] = (short) Math.clamp(Math.round(value), 0, 65535);
                }
            }
        }
        return samples;
    }

    static RawImage bayer(int width, int height, CfaPattern cfa, long seed) {
        return RawImage.ofCfa(width, height, scene(width, height, 1, cfa, seed), cfa, NEUTRAL, SRGB_D50);
    }

    static RawImage readBack(byte[] dng) {
        return RawDecoder.decodeRaw(MediaSource.of(dng));
    }

    static DngEncoderOptions options(DngCompression compression, int tile) {
        return DngEncoderOptions.builder().compression(compression).tileSize(tile).preview(false).build();
    }

    static void assertSameImage(RawImage expected, RawImage actual) {
        assertEquals(expected.width(), actual.width(), "width");
        assertEquals(expected.height(), actual.height(), "height");
        assertEquals(expected.channels(), actual.channels(), "channels");
        assertArrayEquals(expected.samples(), actual.samples(), "samples");
        assertEquals(expected.cfaPattern(), actual.cfaPattern(), "pattern");
        assertEquals(expected.cfaLayout(), actual.cfaLayout(), "layout");
        assertEquals(expected.colorPlanes(), actual.colorPlanes(), "color planes");
        assertArrayEquals(expected.cameraNeutral(), actual.cameraNeutral(), 1e-8, "neutral");
        if (expected.balancedToXyz().length > 0) {
            assertArrayEquals(expected.balancedToXyz(), actual.balancedToXyz(), 1e-8, "matrix");
        }
    }

    static Stream<Arguments> patternsAndCompressions() {
        var result = new ArrayList<Arguments>();
        for (var pattern : PATTERNS) {
            for (var compression : DngCompression.values()) {
                for (var tile : new int[]{16, 256}) {
                    result.add(Arguments.of(pattern, compression, tile));
                }
            }
        }
        return result.stream();
    }

    /** The photosite of the same phase nearest to a coordinate, inside an image of a size. */
    static int same(int coordinate, int size, int period) {
        var result = coordinate;
        while (result < 0) {
            result += period;
        }
        while (result >= size) {
            result -= period;
        }
        return result;
    }

    static Stream<Arguments> paddings() {
        return Stream.of(Arguments.of(CfaPattern.bayer("RGGB"), 2), Arguments.of(xTrans(), 6));
    }

    @Nested
    @DisplayName("sample and color round trips")
    class RoundTrips {
        @ParameterizedTest(name = "{0}, {1}, tiles of {2}")
        @MethodSource("com.github.auties00.smalt.image.raw.DngEncoderTest#patternsAndCompressions")
        @DisplayName("a color filter array image reads back unchanged")
        void cfa(CfaPattern pattern, DngCompression compression, int tile) {
            var image = bayer(301, 197, pattern, 7);
            assertSameImage(image, readBack(DngEncoder.encode(image, options(compression, tile))));
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(DngCompression.class)
        @DisplayName("a four-color image keeps its color planes, neutral and 3x4 matrix")
        void fourColor(DngCompression compression) {
            var cfa = CfaPattern.bayer("CYGM");
            double[] neutral = {0.8, 1.0, 0.6, 0.9};
            double[] matrix = {
                    0.10, 0.45, 0.30, 0.12,
                    0.35, 0.40, 0.05, 0.20,
                    0.02, 0.05, 0.50, 0.25};
            var image = RawImage.ofCfa(122, 90, scene(122, 90, 1, cfa, 3), cfa, neutral, matrix);
            assertEquals(List.of(CfaColor.GREEN, CfaColor.CYAN, CfaColor.MAGENTA, CfaColor.YELLOW),
                    image.colorPlanes());
            var back = readBack(DngEncoder.encode(image, options(compression, 32)));
            assertSameImage(image, back);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(DngCompression.class)
        @DisplayName("a linear raw image of three samples per pixel reads back unchanged")
        void linear(DngCompression compression) {
            var image = RawImage.ofLinear(133, 77, scene(133, 77, 3, null, 5), NEUTRAL, SRGB_D50);
            assertSameImage(image, readBack(DngEncoder.encode(image, options(compression, 64))));
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(DngCompression.class)
        @DisplayName("a monochrome image is written as one-sample linear raw data")
        void monochrome(DngCompression compression) {
            var image = RawImage.ofMonochrome(90, 61, scene(90, 61, 1, null, 9));
            var dng = DngEncoder.encode(image, options(compression, 48));
            var back = readBack(dng);
            assertArrayEquals(image.samples(), back.samples());
            assertEquals(1, back.channels());
            assertTrue(back.cfaPattern().isEmpty());
            var raw = DngStructure.of(dng).raw();
            assertEquals(34892, raw.get(262).longs(LE)[0]);
            assertFalse(DngStructure.of(dng).first().has(50721), "a monochrome file has no color matrix");
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(DngCompression.class)
        @DisplayName("extreme values and differences of -32768 code exactly")
        void extremes(DngCompression compression) {
            var width = 70;
            var height = 33;
            var samples = new short[width * height];
            var random = new SplittableRandom(11);
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    samples[y * width + x] = switch (y % 4) {
                        case 0 -> (short) (((x / 2) % 2) * 32768);
                        case 1 -> (short) (x % 3 == 0 ? 65535 : 0);
                        case 2 -> (short) random.nextInt(65536);
                        default -> (short) 32768;
                    };
                }
            }
            var image = RawImage.ofCfa(width, height, samples, CfaPattern.bayer("RGGB"), NEUTRAL, SRGB_D50);
            assertSameImage(image, readBack(DngEncoder.encode(image, options(compression, 16))));
        }

        static Stream<Arguments> randomCases() {
            var random = new SplittableRandom(2026);
            return IntStream.range(0, 48).mapToObj(index -> {
                var kind = random.nextInt(4);
                return Arguments.of(index, kind, 1 + random.nextInt(90), 1 + random.nextInt(70),
                        16 * (1 + random.nextInt(4)), random.nextBoolean() ? DngCompression.LOSSLESS_JPEG
                                : DngCompression.UNCOMPRESSED, random.nextLong());
            });
        }

        @ParameterizedTest(name = "case {0}: kind {1}, {2}x{3}, tiles of {4}, {5}")
        @MethodSource("randomCases")
        @DisplayName("random sizes, kinds, tiles and noise read back unchanged")
        void random(int index, int kind, int width, int height, int tile, DngCompression compression, long seed) {
            var random = new SplittableRandom(seed);
            var channels = kind == 2 ? 3 : 1;
            var samples = new short[width * height * channels];
            var smooth = random.nextBoolean();
            for (var i = 0; i < samples.length; i++) {
                samples[i] = (short) (smooth ? (i * 37 + random.nextInt(64)) & 0xFFFF : random.nextInt(65536));
            }
            var image = switch (kind) {
                case 0 -> RawImage.ofCfa(width, height, samples, PATTERNS.get(random.nextInt(PATTERNS.size())),
                        NEUTRAL, SRGB_D50);
                case 1 -> RawImage.ofCfa(width, height, samples, CfaPattern.bayer("GMCY"),
                        new double[]{1, 0.7, 0.8, 0.9}, new double[]{0.3, 0.2, 0.1, 0.3642, 0.4, 0.1, 0.2, 0.3,
                                0.05, 0.3, 0.4, 0.0749});
                case 2 -> RawImage.ofLinear(width, height, samples, NEUTRAL, SRGB_D50);
                default -> RawImage.ofMonochrome(width, height, samples);
            };
            var back = readBack(DngEncoder.encode(image, options(compression, tile)));
            assertArrayEquals(image.samples(), back.samples(), "case " + index);
            if (kind != 3) {
                assertSameImage(image, back);
            }
        }

        @Test
        @DisplayName("lossless JPEG tiles are smaller than uncompressed ones on a camera-like scene")
        void compresses() {
            var image = bayer(512, 384, CfaPattern.bayer("RGGB"), 1);
            var lossless = DngEncoder.encode(image, options(DngCompression.LOSSLESS_JPEG, 256));
            var uncompressed = DngEncoder.encode(image, options(DngCompression.UNCOMPRESSED, 256));
            assertTrue(lossless.length < uncompressed.length * 0.8, lossless.length + " vs " + uncompressed.length);
            assertTrue(uncompressed.length >= 512 * 384 * 2);
        }

        @Test
        @DisplayName("the output is deterministic")
        void deterministic() {
            var image = bayer(700, 500, xTrans(), 4);
            assertArrayEquals(DngEncoder.encode(image), DngEncoder.encode(image));
        }
    }

    @Nested
    @DisplayName("file structure")
    class Structure {
        @Test
        @DisplayName("the first directory holds the version, camera and color tags and points to the raw image")
        void tags() {
            var image = bayer(320, 240, CfaPattern.bayer("RGGB"), 2);
            var dng = DngEncoder.encode(image, DngEncoderOptions.builder().make("Smalt Co").model("Test Body")
                    .build());
            var structure = DngStructure.of(dng);
            var first = structure.first();
            assertArrayEquals(new long[]{1, 7, 1, 0}, first.get(50706).longs(LE));
            assertArrayEquals(new long[]{1, 1, 0, 0}, first.get(50707).longs(LE));
            assertEquals("Smalt Co Test Body", first.get(50708).text());
            assertEquals("Smalt Co", first.get(271).text());
            assertEquals("Test Body", first.get(272).text());
            assertEquals(1, first.get(254).longs(LE)[0], "the first directory is the thumbnail");
            assertEquals(2, first.get(330).count(), "raw image and preview sub-directories");
            assertEquals(10, first.get(50721).type(), "ColorMatrix1 is SRATIONAL");
            assertEquals(23, first.get(50778).longs(LE)[0], "D50 calibration illuminant");
            assertArrayEquals(SRGB_D50, first.get(50964).doubles(LE), 1e-8, "the forward matrix");
            assertArrayEquals(NEUTRAL, first.get(50728).doubles(LE), 1e-8, "the as-shot neutral");
            var color = first.get(50721).doubles(LE);
            var d50 = new double[3];
            for (var row = 0; row < 3; row++) {
                d50[row] = SRGB_D50[row * 3] + SRGB_D50[row * 3 + 1] + SRGB_D50[row * 3 + 2];
            }
            var mapped = new double[3];
            for (var row = 0; row < 3; row++) {
                for (var column = 0; column < 3; column++) {
                    mapped[row] += color[row * 3 + column] * d50[column];
                }
            }
            assertArrayEquals(NEUTRAL, mapped, 1e-7, "the color matrix maps the D50 white to the neutral");
            var raw = structure.raw();
            assertEquals(32803, raw.get(262).longs(LE)[0]);
            assertArrayEquals(new long[]{2, 2}, raw.get(33421).longs(LE));
            assertArrayEquals(new long[]{0, 1, 1, 2}, raw.get(33422).longs(LE));
            assertArrayEquals(new long[]{0, 1, 2}, raw.get(50710).longs(LE));
            assertEquals(7, raw.get(259).longs(LE)[0]);
            assertEquals(65535, raw.get(50717).longs(LE)[0]);
            var offsets = raw.get(324).longs(LE);
            var counts = raw.get(325).longs(LE);
            assertEquals(2, offsets.length, "two tiles across, one down");
            for (var i = 0; i < offsets.length; i++) {
                assertEquals(0, offsets[i] % 2, "tiles start on a word boundary");
                assertTrue(offsets[i] + counts[i] <= dng.length);
                assertEquals(0xFF, dng[(int) offsets[i]] & 0xFF);
                assertEquals(0xD8, dng[(int) offsets[i] + 1] & 0xFF, "a tile is a JPEG stream");
                assertEquals(0xD9, dng[(int) (offsets[i] + counts[i] - 1)] & 0xFF);
            }
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.image.raw.DngEncoderTest#paddings")
        @DisplayName("a CFA image gains a margin of same-color photosites around its default crop")
        void padding(CfaPattern pattern, int margin) {
            var image = bayer(97, 61, pattern, 12);
            var dng = DngEncoder.encode(image, options(DngCompression.LOSSLESS_JPEG, 64));
            var raw = DngStructure.of(dng).raw();
            assertArrayEquals(new long[]{97 + 2L * margin, 61 + 2L * margin},
                    new long[]{raw.get(256).longs(LE)[0], raw.get(257).longs(LE)[0]});
            assertArrayEquals(new long[]{margin, margin}, raw.get(50719).longs(LE));
            assertArrayEquals(new long[]{97, 61}, raw.get(50720).longs(LE));
            var whole = RawDecoder.decodeRaw(MediaSource.of(dng), RawDecoderOptions.builder().defaultCrop(false)
                    .build());
            assertEquals(pattern, whole.cfaPattern().orElseThrow());
            var width = whole.width();
            for (var y = 0; y < whole.height(); y++) {
                for (var x = 0; x < width; x++) {
                    var sx = same(x - margin, 97, pattern.width() % 2 == 0 ? pattern.width() : 2 * pattern.width());
                    var sy = same(y - margin, 61, pattern.height() % 2 == 0 ? pattern.height() : 2 * pattern.height());
                    assertEquals(pattern.color(Math.floorMod(y - margin, pattern.height()),
                            Math.floorMod(x - margin, pattern.width())), pattern.color(sy % pattern.height(),
                            sx % pattern.width()), "photosite " + x + "," + y + " copies one of its color");
                    assertEquals(image.samples()[sy * 97 + sx], whole.samples()[y * width + x],
                            "photosite " + x + "," + y);
                }
            }
            assertArrayEquals(image.samples(), readBack(dng).samples());
        }

        @Test
        @DisplayName("without a preview the raw image is the first directory")
        void noPreview() {
            var image = bayer(64, 48, CfaPattern.bayer("RGGB"), 2);
            var dng = DngEncoder.encode(image, options(DngCompression.LOSSLESS_JPEG, 32));
            var structure = DngStructure.of(dng);
            assertEquals(1, structure.directories.size());
            assertEquals(0, structure.first().get(254).longs(LE)[0]);
            assertTrue(RawDecoder.info(MediaSource.of(dng)).previews().isEmpty());
        }

        @ParameterizedTest(name = "layout {0}")
        @EnumSource(CfaLayout.class)
        @DisplayName("a CFA layout is written, and layouts 6 to 9 need DNG 1.3 readers")
        void layouts(CfaLayout layout) {
            var cfa = CfaPattern.bayer("RGGB");
            var samples = scene(64, 64, 1, cfa, 1);
            var image = new RawImage(64, 64, 1, samples, cfa, NEUTRAL, SRGB_D50, layout,
                    List.of(CfaColor.RED, CfaColor.GREEN, CfaColor.BLUE));
            var dng = DngEncoder.encode(image, options(DngCompression.LOSSLESS_JPEG, 32));
            var structure = DngStructure.of(dng);
            assertEquals(layout.code(), structure.raw().get(50711).longs(LE)[0]);
            var backward = structure.first().get(50707).longs(LE);
            assertArrayEquals(layout.code() >= 6 ? new long[]{1, 3, 0, 0} : new long[]{1, 1, 0, 0}, backward);
            var back = readBack(dng);
            assertEquals(layout, back.cfaLayout());
            assertArrayEquals(samples, back.samples());
        }

        @Test
        @DisplayName("the pixel aspect ratio is written as the default scale")
        void aspect() {
            var image = bayer(200, 100, CfaPattern.bayer("RGGB"), 3);
            var dng = DngEncoder.encode(image, DngEncoderOptions.builder().pixelAspectRatio(2).preview(false).build());
            assertArrayEquals(new double[]{2, 1}, DngStructure.of(dng).raw().get(50718).doubles(LE), 1e-12);
            var decoded = RawDecoder.decode(MediaSource.of(dng));
            assertEquals(400, decoded.picture().width());
            assertEquals(100, decoded.picture().height());
        }

        @Test
        @DisplayName("the camera, orientation, EXIF and baseline exposure options are written")
        void metadata() {
            var exif = new ExifData(Map.of(270, new ExifValue.Ascii("synthetic"), 50721,
                    new ExifValue.Ascii("not a DNG tag")),
                    Map.of(0x8827, new ExifValue.Integers(ExifValue.Type.SHORT, new long[]{400}),
                            0x829A, new ExifValue.Rationals(ExifValue.Type.RATIONAL, new long[]{1}, new long[]{250})),
                    Map.of());
            var image = bayer(96, 64, CfaPattern.bayer("RGGB"), 3);
            var dng = DngEncoder.encode(image, DngEncoderOptions.builder().make("Maker").model("Maker Model")
                    .uniqueCameraModel("Unique One").orientation(Orientation.ROTATE_90).exif(exif)
                    .baselineExposure(-0.5).preview(false).build());
            var info = RawDecoder.info(MediaSource.of(dng));
            assertEquals(RawFormat.DNG, info.format());
            assertEquals("Maker", info.make().orElseThrow());
            assertEquals("Maker Model", info.model().orElseThrow());
            assertEquals(Orientation.ROTATE_90, info.orientation());
            assertEquals(400, info.iso().orElseThrow());
            assertEquals(1.0 / 250, info.exposureTime().orElseThrow(), 1e-12);
            var first = DngStructure.of(dng).first();
            assertEquals("Unique One", first.get(50708).text());
            assertEquals("synthetic", first.get(270).text());
            assertEquals(10, first.get(50721).type(), "EXIF entries never replace the DNG tags");
            assertEquals(-0.5, first.get(50730).doubles(LE)[0], 1e-12);
            assertEquals("Smalt", first.get(305).text());
        }
    }

    @Nested
    @DisplayName("previews")
    class Previews {
        @Test
        @DisplayName("the JPEG preview and the thumbnail are renderings of the written image")
        void rendering() {
            var image = bayer(1600, 1000, CfaPattern.bayer("RGGB"), 6);
            var dng = DngEncoder.encode(image);
            var info = RawDecoder.info(MediaSource.of(dng));
            var jpeg = info.previews().stream().filter(preview -> preview.format() == ImageFormat.JPEG).findFirst()
                    .orElseThrow();
            assertEquals(1024, jpeg.width());
            assertEquals(640, jpeg.height());
            var thumbnail = DngStructure.of(dng).first();
            assertEquals(256, thumbnail.get(256).longs(LE)[0]);
            assertEquals(160, thumbnail.get(257).longs(LE)[0]);
            assertEquals(2, thumbnail.get(262).longs(LE)[0]);
            assertEquals(2, thumbnail.get(50970).longs(LE)[0], "sRGB preview color space");
            var preview = JpegDecoder.decode(MediaSource.of(RawDecoder.extractPreview(MediaSource.of(dng))
                    .orElseThrow())).picture();
            var rendered = RawDecoder.decode(MediaSource.of(dng), RawDecoderOptions.builder().embeddedPreview(false)
                    .build()).picture();
            var expected = ImageOps.resize(rendered, 1024, 640, ResizeFilter.LANCZOS3);
            var psnr = Quality.psnr(ImageOps.toRgba(expected), ImageOps.toRgba(preview));
            assertTrue(psnr > 32, "preview PSNR " + psnr);
            var hinted = RawDecoder.decode(MediaSource.of(dng), RawDecoderOptions.ofMinimumSize(800, 500));
            assertEquals(1024, hinted.picture().width(), "a size hint is met from the preview");
        }

        @ParameterizedTest(name = "preview size {0}")
        @ValueSource(ints = {256, 700, 0})
        @DisplayName("the preview takes the requested size and never exceeds the rendering")
        void sizes(int size) {
            var image = bayer(900, 600, CfaPattern.bayer("BGGR"), 8);
            var builder = DngEncoderOptions.builder();
            if (size == 0) {
                builder.fullSizePreview();
            } else {
                builder.previewSize(size);
            }
            var dng = DngEncoder.encode(image, builder.build());
            var jpeg = RawDecoder.info(MediaSource.of(dng)).previews().stream()
                    .filter(preview -> preview.format() == ImageFormat.JPEG).findFirst().orElseThrow();
            var expected = size == 0 ? 900 : size;
            assertEquals(expected, jpeg.width());
            assertEquals(Math.round(expected * 600.0 / 900), jpeg.height());
        }

        @Test
        @DisplayName("a small image's preview is the full rendering")
        void small() {
            var image = bayer(300, 200, CfaPattern.bayer("RGGB"), 8);
            var dng = DngEncoder.encode(image);
            var jpeg = RawDecoder.info(MediaSource.of(dng)).previews().stream()
                    .filter(preview -> preview.format() == ImageFormat.JPEG).findFirst().orElseThrow();
            assertEquals(300, jpeg.width());
            assertEquals(200, jpeg.height());
        }
    }

    @Nested
    @DisplayName("argument checks")
    class Checks {
        @Test
        @DisplayName("the options reject out-of-range values")
        void options() {
            assertThrows(IllegalArgumentException.class, () -> DngEncoderOptions.builder().tileSize(15).build());
            assertThrows(IllegalArgumentException.class, () -> DngEncoderOptions.builder().tileSize(24).build());
            assertThrows(IllegalArgumentException.class, () -> DngEncoderOptions.builder().tileSize(4112).build());
            assertThrows(IllegalArgumentException.class, () -> DngEncoderOptions.builder().previewSize(100).build());
            assertThrows(IllegalArgumentException.class, () -> DngEncoderOptions.builder().previewQuality(0).build());
            assertThrows(IllegalArgumentException.class, () -> DngEncoderOptions.builder().baselineExposure(11)
                    .build());
            assertThrows(IllegalArgumentException.class, () -> DngEncoderOptions.builder().pixelAspectRatio(0)
                    .build());
            var options = DngEncoderOptions.builder().compression(DngCompression.UNCOMPRESSED).tileSize(64)
                    .make("A").noExif().build();
            assertEquals(options, options.toBuilder().build());
            assertEquals(options.hashCode(), options.toBuilder().build().hashCode());
            assertEquals(ExifData.EMPTY, options.exif().orElseThrow());
            assertTrue(options.toBuilder().automaticExif().automaticMake().build().make().isEmpty());
            assertTrue(DngEncoderOptions.builder().fullSizePreview().build().previewSize().isEmpty());
            assertThrows(IllegalArgumentException.class, () -> DngEncoderOptions.builder().previewSize(0).build(),
                    "0 is not a sentinel for the full-size preview");
            var full = DngEncoderOptions.builder().fullSizePreview().build();
            assertEquals(full, full.toBuilder().build());
            assertEquals(OptionalInt.of(512), full.toBuilder().previewSize(512).build().previewSize());
        }

        @Test
        @DisplayName("the image factories reject inconsistent images")
        void factories() {
            var cfa = CfaPattern.bayer("RGGB");
            assertThrows(IllegalArgumentException.class, () -> RawImage.ofCfa(4, 4, new short[15], cfa, NEUTRAL,
                    SRGB_D50));
            assertThrows(IllegalArgumentException.class, () -> RawImage.ofCfa(4, 4, new short[16], cfa,
                    new double[]{1, 1}, SRGB_D50));
            assertThrows(IllegalArgumentException.class, () -> RawImage.ofCfa(4, 4, new short[16], cfa,
                    new double[]{1, 0, 1}, SRGB_D50));
            assertThrows(IllegalArgumentException.class, () -> RawImage.ofCfa(4, 4, new short[16],
                    CfaPattern.bayer("CMCM"), NEUTRAL, SRGB_D50));
            assertThrows(IllegalArgumentException.class, () -> RawImage.ofLinear(0, 4, new short[0], NEUTRAL,
                    SRGB_D50));
            assertThrows(IllegalArgumentException.class, () -> RawImage.ofLinear(2, 2, new short[12], NEUTRAL,
                    new double[]{1, 0, 0, 0, 1, 0, 0, 0, Double.NaN}));
            assertThrows(NullPointerException.class, () -> DngEncoder.encode(null));
            var doubled = SRGB_D50.clone();
            for (var i = 0; i < doubled.length; i++) {
                doubled[i] *= 2;
            }
            assertThrows(IllegalArgumentException.class, () -> RawImage.ofCfa(4, 4, new short[16], cfa, NEUTRAL,
                    doubled), "a matrix that does not map a white-balanced neutral to D50");
            assertThrows(IllegalArgumentException.class, () -> RawImage.ofLinear(2, 2, new short[12], NEUTRAL,
                    new double[]{1, 0, 0, 0, 1, 0, 0, 0, 1}), "an identity matrix maps the neutral to equal-energy");
        }

        @Test
        @DisplayName("a decoded matrix that maps the neutral away from D50 is written as a conforming forward matrix")
        void nonConformingMatrix() {
            var cfa = CfaPattern.bayer("RGGB");
            var scaled = SRGB_D50.clone();
            double[] gains = {1.3, 0.9, 1.1};
            for (var i = 0; i < scaled.length; i++) {
                scaled[i] *= gains[i / 3];
            }
            var image = new RawImage(64, 48, 1, scene(64, 48, 1, cfa, 6), cfa, NEUTRAL, scaled,
                    CfaLayout.RECTANGULAR, List.of(CfaColor.RED, CfaColor.GREEN, CfaColor.BLUE));
            var dng = DngEncoder.encode(image, DngEncoderTest.options(DngCompression.LOSSLESS_JPEG, 32));
            var forward = DngStructure.of(dng).first().get(50964).doubles(LE);
            double[] d50 = {0.9642, 1.0, 0.8249};
            for (var row = 0; row < 3; row++) {
                assertEquals(d50[row], forward[row * 3] + forward[row * 3 + 1] + forward[row * 3 + 2], 1e-7,
                        "row " + row + " maps the neutral to D50");
                for (var column = 0; column < 3; column++) {
                    assertEquals(SRGB_D50[row * 3 + column] * d50[row] / (SRGB_D50[row * 3] + SRGB_D50[row * 3 + 1]
                            + SRGB_D50[row * 3 + 2]), forward[row * 3 + column], 1e-7);
                }
            }
            var singular = new RawImage(8, 8, 1, new short[64], cfa, NEUTRAL, new double[]{1, 0, 0, 1, 0, 0,
                    1, 0, 0}, CfaLayout.RECTANGULAR, List.of(CfaColor.RED, CfaColor.GREEN, CfaColor.BLUE));
            assertThrows(IllegalArgumentException.class, () -> DngEncoder.encode(singular));
        }

        @Test
        @DisplayName("an image without a color matrix that is not monochrome is rejected")
        void colorless() {
            var cfa = CfaPattern.bayer("CYGM");
            var image = new RawImage(8, 8, 1, new short[64], cfa, new double[]{1, 1, 1, 1}, new double[0],
                    CfaLayout.RECTANGULAR, List.of(CfaColor.GREEN, CfaColor.CYAN, CfaColor.MAGENTA, CfaColor.YELLOW));
            assertThrows(IllegalArgumentException.class, () -> DngEncoder.encode(image));
        }
    }
}
