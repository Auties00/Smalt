package com.github.auties00.smalt.image.tiff;

import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.testkit.image.RasterTesting;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the TIFF encoder: lossless round trips through Smalt's decoder for every option
 * combination, libtiff reading the output (raw samples through tiffcp, pixels through
 * tiff2rgba), ffmpeg decoding it, multi-page streaming, tiles, BigTIFF, byte order and ICC
 * embedding, and option validation.
 */
class TiffEncoderTest {
    static RgbaPicture picture(int width, int height, boolean alpha, boolean gray, long seed) {
        var random = new Random(seed);
        var picture = RgbaPicture.allocate(width, height, alpha);
        var pixels = picture.pixels();
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var at = (y * width + x) * 4;
                var r = (x * 255 / Math.max(1, width - 1) + random.nextInt(9)) & 0xFF;
                var g = gray ? r : (y * 255 / Math.max(1, height - 1)) & 0xFF;
                var b = gray ? r : ((x ^ y) * 7 + random.nextInt(3)) & 0xFF;
                pixels[at] = (byte) r;
                pixels[at + 1] = (byte) g;
                pixels[at + 2] = (byte) b;
                pixels[at + 3] = (byte) (alpha ? (x + y) * 13 : 255);
            }
        }
        return picture;
    }

    static Rgba16Picture picture16(int width, int height, boolean alpha, long seed) {
        var random = new Random(seed);
        var picture = Rgba16Picture.allocate(width, height, alpha);
        var pixels = picture.pixels();
        for (var i = 0; i < pixels.length; i++) {
            pixels[i] = (short) (i % 4 == 3 && !alpha ? 0xFFFF : random.nextInt(65536));
        }
        return picture;
    }

    static Stream<Arguments> options() {
        var compressions = List.of(TiffCompression.NONE, TiffCompression.PACKBITS, TiffCompression.LZW, TiffCompression.DEFLATE);
        var result = new ArrayList<Arguments>();
        for (var compression : compressions) {
            for (var predictor : List.of(TiffPredictor.NONE, TiffPredictor.HORIZONTAL)) {
                for (var layout : List.of(TiffEncoderOptions.Layout.AUTO, TiffEncoderOptions.Layout.GRAY,
                        TiffEncoderOptions.Layout.GRAY_ALPHA, TiffEncoderOptions.Layout.RGB, TiffEncoderOptions.Layout.RGBA)) {
                    var base = TiffEncoderOptions.builder().compression(compression).predictor(predictor)
                            .layout(layout).build();
                    result.add(Arguments.of(base));
                    result.add(Arguments.of(base.toBuilder().tiles(32, 16).bigEndian(true).build()));
                    result.add(Arguments.of(base.toBuilder().strips(3).bigTiff(true).build()));
                    result.add(Arguments.of(base.toBuilder().depth(TiffEncoderOptions.Depth.SIXTEEN).build()));
                }
            }
        }
        return result.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("options")
    @DisplayName("every option combination round-trips losslessly through the decoder")
    void roundTrip(TiffEncoderOptions options) {
        for (var source : new RgbaPicture[]{picture(53, 37, true, false, 1), picture(53, 37, false, true, 2), picture(1, 1, false, false, 3)}) {
            var encoded = TiffEncoder.encode(source, options);
            var decoded = TiffDecoder.decode(MediaSource.of(encoded)).picture();
            assertEquals(source.width(), decoded.width());
            var expected = expected(source, options.layout());
            RasterTesting.assertSameRgba(expected, RasterTesting.tight((RgbaPicture) decoded), source.width(), options.toString());
        }
    }

    /** The RGBA a layout keeps from a picture: gray layouts keep the BT.601 luma, alpha-free ones opaque. */
    private static byte[] expected(RgbaPicture source, TiffEncoderOptions.Layout layout) {
        var pixels = RasterTesting.tight(source);
        var gray = layout == TiffEncoderOptions.Layout.GRAY || layout == TiffEncoderOptions.Layout.GRAY_ALPHA;
        var alpha = layout == TiffEncoderOptions.Layout.RGBA || layout == TiffEncoderOptions.Layout.GRAY_ALPHA
                || (layout == TiffEncoderOptions.Layout.AUTO && source.hasAlpha());
        for (var i = 0; i < pixels.length; i += 4) {
            if (gray) {
                var luma = (77 * (pixels[i] & 0xFF) + 150 * (pixels[i + 1] & 0xFF) + 29 * (pixels[i + 2] & 0xFF) + 128) >> 8;
                pixels[i] = pixels[i + 1] = pixels[i + 2] = (byte) luma;
            }
            if (!alpha) {
                pixels[i + 3] = (byte) 0xFF;
            }
        }
        return pixels;
    }

    @Nested
    @DisplayName("independent readers")
    class Readers {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.image.tiff.TiffEncoderTest#options")
        @DisplayName("libtiff reads the exact samples back (tiffcp to uncompressed)")
        void libtiffSamples(TiffEncoderOptions options) {
            LibTiff.assume("tiffcp");
            var source = picture(41, 29, true, false, 7);
            var encoded = TiffEncoder.encode(source, options.toBuilder().layout(TiffEncoderOptions.Layout.RGBA)
                    .build());
            var raw = LibTiff.RawStrips.read(LibTiff.tiffcp(encoded, List.of("-c", "none", "-L", "-s", "-r", "1000")));
            assertEquals(4, raw.samples());
            var expected = RasterTesting.tight(source);
            if (raw.bits() == 16) {
                for (var i = 0; i < expected.length; i++) {
                    var value = (raw.data()[i * 2] & 0xFF) | (raw.data()[i * 2 + 1] & 0xFF) << 8;
                    assertEquals((expected[i] & 0xFF) * 257, value, "sample " + i);
                }
            } else {
                assertArrayEquals(expected, raw.data());
            }
        }

        @Test
        @DisplayName("libtiff's RGBA reader agrees pixel for pixel, premultiplying unassociated alpha")
        void libtiffPixels() {
            LibTiff.assume("tiff2rgba");
            var source = picture(64, 48, true, false, 9);
            for (var options : List.of(TiffEncoderOptions.defaults(), TiffEncoderOptions.deflate().toBuilder()
                    .tiles(16, 16).build(),
                    TiffEncoderOptions.uncompressed().toBuilder().bigEndian(true).build(), TiffEncoderOptions.builder()
                            .bigTiff(true).build())) {
                var expected = LibTiff.premultiply(RasterTesting.tight(source));
                assertArrayEquals(expected, LibTiff.rgba(TiffEncoder.encode(source, options)), options.toString());
            }
        }

        @Test
        @DisplayName("ffmpeg decodes the output to the same pixels")
        void ffmpeg() {
            Oracle.assume(Oracle.Tool.FFMPEG);
            for (var alpha : new boolean[]{false, true}) {
                var source = picture(66, 34, alpha, false, 11);
                for (var options : List.of(TiffEncoderOptions.defaults(), TiffEncoderOptions.deflate(), TiffEncoderOptions.uncompressed(),
                        TiffEncoderOptions.builder().compression(TiffCompression.PACKBITS).build())) {
                    var path = RasterTesting.write("smalt-" + alpha + "-" + options.compression() + ".tif", TiffEncoder.encode(source, options));
                    var frames = RasterTesting.ffmpegFrames(path, "rgba");
                    RasterTesting.assertSameRgba(RasterTesting.tight(source), frames, source.width(), options.toString());
                }
            }
        }
    }

    @Nested
    @DisplayName("structure")
    class Structure {
        @Test
        @DisplayName("writes pages of different sizes and depths that both readers count and decode")
        void multiPage() {
            var pages = List.<Picture>of(picture(40, 30, false, false, 1), picture16(17, 9, true, 2), picture(5, 70, true, true, 3));
            var sink = MediaSink.memory();
            var encoder = new TiffEncoder(TiffEncoderOptions.deflate(), sink);
            for (var page : pages) {
                encoder.addPage(page);
            }
            assertEquals(3, encoder.pageCount());
            encoder.finish();
            var file = sink.toByteArray();
            try (var reader = TiffDecoder.openPages(MediaSource.of(file), ImageDecoderOptions.builder()
                    .highBitDepth(true).build())) {
                assertEquals(3, reader.pageCount());
                for (var i = 0; i < 3; i++) {
                    var decoded = reader.read(i).picture();
                    assertEquals(pages.get(i).width(), decoded.width());
                    assertEquals(pages.get(i).height(), decoded.height());
                    assertEquals(pages.get(i).getClass(), decoded.getClass());
                }
                var wide = (Rgba16Picture) reader.read(1).picture();
                assertArrayEquals(((Rgba16Picture) pages.get(1)).pixels(), wide.pixels());
                assertEquals(3, reader.probe(2).frameCount());
                assertFalse(reader.probe(0).animated());
            }
            assertEquals(3, Images.probe(MediaSource.of(file)).frameCount());
            if (LibTiff.find("tiffinfo").isPresent()) {
                var info = new String(LibTiff.run("tiffinfo", List.of(LibTiff.write("pages.tif", file).toString())));
                assertEquals(3, info.split("TIFF Directory at offset", -1).length - 1, info);
            }
        }

        @Test
        @DisplayName("embeds the color profile's ICC bytes in every page")
        void iccProfile() {
            var profile = ColorProfile.displayP3().toByteArray();
            var file = TiffEncoder.encode(List.of(picture(8, 8, false, false, 1), picture(4, 4, false, false, 2)),
                    TiffEncoderOptions.builder().colorProfile(ColorProfile.parse(profile)).build());
            try (var reader = TiffDecoder.openPages(MediaSource.of(file))) {
                for (var i = 0; i < 2; i++) {
                    assertArrayEquals(profile, reader.read(i).iccProfile().orElseThrow());
                    assertArrayEquals(profile, reader.page(i).iccProfile().orElseThrow());
                }
            }
        }

        @Test
        @DisplayName("describes its pages: tiles, strips, predictor, compression, byte order and BigTIFF")
        void pageDescription() {
            var file = TiffEncoder.encode(picture(70, 50, true, false, 1),
                    TiffEncoderOptions.deflate().toBuilder().tiles(32, 48).bigEndian(true).bigTiff(true).build());
            assertEquals('M', file[0]);
            assertEquals(43, file[3]);
            try (var reader = TiffDecoder.openPages(MediaSource.of(file))) {
                var page = reader.page(0);
                assertEquals(TiffCompression.DEFLATE, page.compression());
                assertEquals(TiffPredictor.HORIZONTAL, page.predictor());
                assertEquals(32, page.tileWidth().orElseThrow());
                assertEquals(48, page.tileHeight().orElseThrow());
                assertTrue(page.rowsPerStrip().isEmpty());
                assertTrue(page.hasAlpha());
                assertFalse(page.isAlphaPremultiplied());
                assertEquals(4, page.samplesPerPixel());
                assertEquals(TiffPhotometric.RGB, page.photometric());
                assertEquals(72, page.resolution().orElseThrow().horizontal(), 1e-9);
            }
        }

        @Test
        @DisplayName("the core prober reads BigTIFF and multi-page headers")
        void prober() {
            var file = TiffEncoder.encode(List.of(picture(30, 20, true, false, 1), picture(5, 5, false, false, 2)),
                    TiffEncoderOptions.builder().bigTiff(true).bigEndian(true).build());
            var image = com.github.auties00.smalt.Media.probe(MediaSource.of(file)).image().orElseThrow();
            assertEquals(30, image.width());
            assertEquals(20, image.height());
            assertEquals(2, image.frameCount().orElse(-1));
            assertFalse(image.isAnimated());
            assertTrue(image.hasAlpha());
        }

        @Test
        @DisplayName("streams to a sink that cannot seek")
        void streaming() {
            var stream = new java.io.ByteArrayOutputStream();
            var sink = MediaSink.of(stream);
            assertFalse(sink.isSeekable());
            TiffEncoder.encode(List.of(picture(9, 9, false, false, 1), picture(3, 3, false, false, 2)), TiffEncoderOptions.defaults(), sink);
            assertArrayEquals(TiffEncoder.encode(List.of(picture(9, 9, false, false, 1), picture(3, 3, false, false, 2)),
                    TiffEncoderOptions.defaults()), stream.toByteArray());
        }

        @Test
        @DisplayName("is reached through the registry")
        void registry() {
            assertTrue(Images.isEncodable(ImageFormat.TIFF));
            assertTrue(Images.isDecodable(ImageFormat.TIFF));
            var source = picture(12, 7, true, false, 5);
            var file = Images.encode(source, ImageFormat.TIFF);
            assertEquals(ImageFormat.TIFF, Images.detect(MediaSource.of(file)).orElseThrow());
            RasterTesting.assertSameRgba(RasterTesting.tight(source),
                    RasterTesting.tight((RgbaPicture) Images.decode(MediaSource.of(file)).picture()), 12, "registry");
            try (var pages = Images.openPages(MediaSource.of(file))) {
                assertEquals(1, pages.pageCount());
            }
        }
    }

    @Nested
    @DisplayName("options")
    class Options {
        @Test
        @DisplayName("validates its settings")
        void validation() {
            var defaults = TiffEncoderOptions.defaults();
            assertThrows(IllegalArgumentException.class, () -> defaults.toBuilder().compression(TiffCompression.LZMA)
                    .build());
            assertThrows(IllegalArgumentException.class, () -> defaults.toBuilder().compression(TiffCompression.JPEG)
                    .layout(TiffEncoderOptions.Layout.RGBA).build());
            assertThrows(IllegalArgumentException.class, () -> defaults.toBuilder().compressionLevel(0).build());
            assertThrows(IllegalArgumentException.class, () -> defaults.toBuilder().tiles(17, 16).build());
            assertThrows(IllegalArgumentException.class, () -> defaults.toBuilder().tiles(0, 16).build());
            assertThrows(IllegalArgumentException.class, () -> defaults.toBuilder().strips(-1).build());
            assertThrows(IllegalArgumentException.class, () -> defaults.toBuilder().strips(0).build());
            assertThrows(NullPointerException.class, () -> defaults.toBuilder().layout(null).build());
            assertEquals(defaults, TiffEncoderOptions.builder().build());
            assertEquals(defaults.toBuilder().tiles(16, 32).build(), defaults.toBuilder().tiles(16, 32).build());
            assertNotEquals(defaults, TiffEncoderOptions.uncompressed());
            assertTrue(defaults.toBuilder().tiles(16, 16).automaticStrips().build().tileWidth().isEmpty());
            assertTrue(TiffEncoderOptions.deflate().toString().contains("DEFLATE"));
        }

        @Test
        @DisplayName("rejects encoding after finish and finishing twice or empty")
        void lifecycle() {
            var encoder = new TiffEncoder(MediaSink.memory());
            assertThrows(IllegalStateException.class, encoder::finish);
            encoder.addPage(picture(2, 2, false, false, 1));
            encoder.finish();
            assertThrows(IllegalStateException.class, encoder::finish);
            assertThrows(IllegalStateException.class, () -> encoder.addPage(picture(2, 2, false, false, 1)));
            assertThrows(IllegalArgumentException.class, () -> TiffEncoder.encode(List.of(), TiffEncoderOptions.defaults()));
        }

        @Test
        @DisplayName("predictors and compression shrink a smooth picture in the expected order")
        void sizes() {
            var source = picture(256, 128, false, false, 3);
            var none = TiffEncoder.encode(source, TiffEncoderOptions.uncompressed()).length;
            var lzw = TiffEncoder.encode(source, TiffEncoderOptions.builder().predictor(TiffPredictor.NONE).build())
                    .length;
            var lzwPredicted = TiffEncoder.encode(source, TiffEncoderOptions.defaults()).length;
            var deflate = TiffEncoder.encode(source, TiffEncoderOptions.deflate().toBuilder().compressionLevel(9)
                    .build()).length;
            assertTrue(lzwPredicted < lzw && lzwPredicted < none, lzwPredicted + " " + lzw + " " + none);
            assertTrue(deflate < lzwPredicted, deflate + " " + lzwPredicted);
        }
    }

    @Nested
    @DisplayName("strip resets, the defaults list overload and openPages")
    class ResetsAndOverloads {
        @Test
        @DisplayName("strips and tiles exclude each other, so the options compare by what is written")
        void exclusive() {
            var tiled = TiffEncoderOptions.builder().strips(8).tiles(16, 16).build();
            assertTrue(tiled.rowsPerStrip().isEmpty());
            assertEquals(TiffEncoderOptions.builder().tiles(16, 16).build(), tiled);
            var stripped = TiffEncoderOptions.builder().tiles(16, 16).strips(8).build();
            assertTrue(stripped.tileWidth().isEmpty());
            assertTrue(stripped.tileHeight().isEmpty());
            assertEquals(8, stripped.rowsPerStrip().orElseThrow());
            assertEquals(TiffEncoderOptions.builder().strips(8).build(), stripped);
        }

        @Test
        @DisplayName("automaticStrips() drops the rows per strip and the tiles")
        void automaticStrips() {
            var options = TiffEncoderOptions.builder().tiles(32, 32).automaticStrips().build();
            assertEquals(TiffEncoderOptions.defaults(), options);
            assertEquals(TiffEncoderOptions.defaults(), TiffEncoderOptions.builder().strips(8).automaticStrips().build());
            assertTrue(options.rowsPerStrip().isEmpty());
            assertTrue(options.tileWidth().isEmpty());
        }

        @Test
        @DisplayName("encode(pictures) writes what encode(pictures, defaults()) writes")
        void encodeList() {
            var pages = List.of(picture(9, 7, false, false, 1), picture(5, 4, true, false, 2));
            assertArrayEquals(TiffEncoder.encode(pages, TiffEncoderOptions.defaults()), TiffEncoder.encode(pages));
        }

        @Test
        @DisplayName("openPages(source) reads the pages openPages(source, defaults) reads")
        void openPages() throws Exception {
            var file = TiffEncoder.encode(List.of(picture(9, 7, false, false, 3), picture(5, 4, true, false, 4)));
            try (var pages = TiffDecoder.openPages(MediaSource.of(file));
                 var withOptions = TiffDecoder.openPages(MediaSource.of(file), ImageDecoderOptions.defaults());
                 var reference = TiffDecoder.openPages(MediaSource.of(file), ImageDecoderOptions.defaults())) {
                assertEquals(2, pages.pageCount());
                assertEquals(2, withOptions.pageCount());
                for (var i = 0; i < 2; i++) {
                    assertEquals(reference.probe(i), pages.probe(i));
                    assertArrayEquals(RasterTesting.tight((RgbaPicture) reference.read(i).picture()),
                            RasterTesting.tight((RgbaPicture) withOptions.read(i).picture()));
                }
            }
        }
    }
}
