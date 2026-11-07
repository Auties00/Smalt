package com.github.auties00.smalt.image.heic;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.PageGroup;
import com.github.auties00.smalt.image.PageReader;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.avif.AvifDecoder;
import com.github.auties00.smalt.image.avif.AvifEncoder;
import com.github.auties00.smalt.image.avif.AvifEncoderOptions;
import com.github.auties00.smalt.image.jpeg.JpegEncoder;
import com.github.auties00.smalt.image.jpeg.JpegEncoderOptions;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.internal.heif.HeifFile;
import com.github.auties00.smalt.internal.heif.HeifProperty;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.HeifBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

import static com.github.auties00.smalt.testkit.HeifBuilder.essential;
import static com.github.auties00.smalt.testkit.HeifBuilder.plain;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes HEIF files whose images are coded with the codecs of two artifacts, built around payloads Smalt's own AV1
 * (through the AVIF encoder) and JPEG encoders write: every image decodes with the artifact that implements its
 * coding whatever the brands of the file, so a file with only the generic brands decodes exactly as the same file
 * under the avif brand, and the pages of a collection mixing AV1 and JPEG images each equal the direct decode of
 * their payload. These tests run here because they need both {@code smalt-video} and {@code smalt-mpeg}.
 */
@DisplayName("HEIF images dispatched by item coding")
class HeifDispatchTest {
    private static RgbaPicture pattern(int width, int height, int seed) {
        var picture = RgbaPicture.allocate(width, height, false);
        var pixels = picture.pixels();
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var at = y * picture.stride() + 4 * x;
                pixels[at] = (byte) (x * 3 + y * seed);
                pixels[at + 1] = (byte) (y * 4 + seed * 17);
                pixels[at + 2] = (byte) (128 + x - y * 2);
                pixels[at + 3] = (byte) 255;
            }
        }
        return picture;
    }

    /** An AVIF file of Smalt's AV1 encoder. */
    private static byte[] avif(int seed) {
        return AvifEncoder.encode(pattern(48, 32, seed), AvifEncoderOptions.lossy(70));
    }

    /** The AV1 payload and av1C record of the primary item of an AVIF file. */
    private record Av1Item(byte[] payload, byte[] config, int width, int height) {
        static Av1Item of(byte[] avif) {
            var source = MediaSource.of(avif);
            var file = HeifFile.parse(source, MediaLimits.DEFAULT);
            var primary = file.primaryItem();
            var extent = primary.property(HeifProperty.SpatialExtent.class);
            return new Av1Item(file.readItem(source, primary, MediaLimits.DEFAULT),
                    primary.property(HeifProperty.Av1Configuration.class).record(), (int) extent.width(),
                    (int) extent.height());
        }

        int add(HeifBuilder builder, HeifBuilder.Property... extra) {
            var properties = new ArrayList<>(List.of(plain(HeifBuilder.ispe(width, height)),
                    essential(HeifBuilder.av1C(config))));
            properties.addAll(List.of(extra));
            return builder.item("av01", payload, properties.toArray(HeifBuilder.Property[]::new));
        }

        /** Decodes the item alone, with the same properties, as the primary item of an AVIF file. */
        DecodedImage alone() {
            var builder = new HeifBuilder();
            builder.primary(add(builder));
            return AvifDecoder.decode(MediaSource.of(builder.build()));
        }
    }

    private static int jpeg(HeifBuilder builder, int seed) {
        return builder.item("jpeg", JpegEncoder.encode(pattern(40, 24, seed)), plain(HeifBuilder.ispe(40, 24)));
    }

    /** Rewrites the brands of the ftyp box of a file, keeping its size. */
    private static byte[] rebrand(byte[] data, String brand) {
        var copy = data.clone();
        var size = ((copy[0] & 0xFF) << 24) | ((copy[1] & 0xFF) << 16) | ((copy[2] & 0xFF) << 8) | (copy[3] & 0xFF);
        for (var offset = 8; offset + 4 <= size; offset += 4) {
            if (offset != 12) {
                System.arraycopy(brand.getBytes(StandardCharsets.ISO_8859_1), 0, copy, offset, 4);
            }
        }
        return copy;
    }

    private static void assertSame(DecodedImage expected, DecodedImage actual) {
        assertEquals(expected.format(), actual.format());
        assertEquals(expected.orientation(), actual.orientation());
        assertSamePixels(expected.picture(), actual.picture());
        assertArrayEquals(expected.iccProfile().orElse(null), actual.iccProfile().orElse(null));
        assertArrayEquals(expected.exif().orElse(null), actual.exif().orElse(null));
        assertArrayEquals(expected.xmp().orElse(null), actual.xmp().orElse(null));
    }

    private static void assertSamePixels(Picture expected, Picture actual) {
        assertEquals(expected.getClass(), actual.getClass());
        assertEquals(expected.width(), actual.width());
        assertEquals(expected.height(), actual.height());
        assertArrayEquals(ImageOps.toRgba(expected).pixels(), ImageOps.toRgba(actual).pixels());
    }

    @Nested
    @DisplayName("AV1 images in files without the AVIF brands")
    class Brands {
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"mif1", "miaf", "heic"})
        @DisplayName("decode in every entry point exactly as under the avif brand, and report AVIF")
        void same(String brand) {
            var avif = avif(1);
            var other = rebrand(avif, brand);
            assertEquals(ImageFormat.AVIF, Images.detect(MediaSource.of(other)).orElseThrow());
            for (var options : List.of(ImageDecoderOptions.defaults(),
                    ImageDecoderOptions.builder().highBitDepth(true).build())) {
                var expected = AvifDecoder.decode(MediaSource.of(avif), options);
                assertEquals(ImageFormat.AVIF, expected.format());
                assertSame(expected, Images.decode(MediaSource.of(other), options));
                assertSame(expected, HeicDecoder.decode(MediaSource.of(other), options));
                assertSame(expected, AvifDecoder.decode(MediaSource.of(other), options));
            }
            assertEquals(AvifDecoder.probe(MediaSource.of(avif)), HeicDecoder.probe(MediaSource.of(other)));
            assertEquals(AvifDecoder.probe(MediaSource.of(avif)), Images.probe(MediaSource.of(other)));
            try (var expected = AvifDecoder.openAnimation(MediaSource.of(avif));
                 var actual = HeicDecoder.openAnimation(MediaSource.of(other))) {
                assertSamePixels(expected.read().picture(), actual.read().picture());
            }
            try (var pages = HeicDecoder.openPages(MediaSource.of(other))) {
                assertEquals(1, pages.pageCount());
                assertSame(AvifDecoder.decode(MediaSource.of(avif)), pages.read(0));
            }
        }

        @Test
        @DisplayName("the AVIF decoder reads a HEIC-family file as leniently as the HEIC decoder does")
        void lenient() {
            var builder = new HeifBuilder().brands("mif1", "mif1", "miaf");
            // a rotation not marked essential breaks a rule the AVIF container enforces
            builder.primary(builder.item("jpeg", JpegEncoder.encode(pattern(40, 24, 3)),
                    plain(HeifBuilder.ispe(40, 24)), plain(HeifBuilder.irot(1))));
            var data = builder.build();
            var expected = HeicDecoder.decode(MediaSource.of(data));
            assertNotEquals(Orientation.NORMAL, expected.orientation());
            assertSame(expected, AvifDecoder.decode(MediaSource.of(data)));
            assertEquals(HeicDecoder.probe(MediaSource.of(data)), AvifDecoder.probe(MediaSource.of(data)));
        }

        @Test
        @DisplayName("a JPEG image in a file with the avif brand decodes as HEIC")
        void jpegUnderAvifBrand() {
            var builder = new HeifBuilder().brands("avif", "avif", "mif1", "miaf");
            builder.primary(jpeg(builder, 2));
            var data = builder.build();
            var generic = new HeifBuilder().brands("mif1", "mif1", "miaf");
            generic.primary(jpeg(generic, 2));
            var expected = HeicDecoder.decode(MediaSource.of(generic.build()));
            assertEquals(ImageFormat.HEIC, Images.detect(MediaSource.of(data)).orElseThrow());
            assertSame(expected, AvifDecoder.decode(MediaSource.of(data)));
            assertSame(expected, Images.decode(MediaSource.of(data)));
        }
    }

    @Nested
    @DisplayName("derived images mixing AV1 and JPEG inputs")
    class Derivations {
        private static DecodedImage alone(boolean av1, int seed) {
            var builder = new HeifBuilder().brands("mif1", "mif1", "miaf");
            if (av1) {
                return Av1Item.of(avif(seed)).alone();
            }
            builder.primary(jpeg(builder, seed));
            return HeicDecoder.decode(MediaSource.of(builder.build()));
        }

        private static void paste(RgbaPicture canvas, RgbaPicture picture, int left) {
            for (var y = 0; y < picture.height(); y++) {
                System.arraycopy(picture.pixels(), y * picture.stride(), canvas.pixels(), y * canvas.stride() + 4 * left,
                        4 * picture.width());
            }
        }

        @ParameterizedTest(name = "AV1 input first: {0}")
        @ValueSource(booleans = {false, true})
        @DisplayName("an overlay reconstructs each input with the artifact of its coding, whichever decoder opens it")
        void overlay(boolean av1First) {
            var builder = new HeifBuilder().brands("mif1", "mif1", "miaf");
            var av1 = Av1Item.of(avif(6));
            int first;
            int second;
            if (av1First) {
                first = av1.add(builder);
                second = jpeg(builder, 7);
            } else {
                first = jpeg(builder, 7);
                second = av1.add(builder);
            }
            builder.hidden(first).hidden(second);
            var firstWidth = av1First ? 48 : 40;
            var overlay = builder.idatItem("iovl", HeifBuilder.overlay(false, new int[]{65535, 65535, 65535, 65535},
                    88, 32, new int[]{0, 0}, new int[]{firstWidth, 0}), plain(HeifBuilder.ispe(88, 32)));
            builder.reference("dimg", overlay, first, second);
            builder.primary(overlay);
            var data = builder.build();
            var expected = RgbaPicture.allocate(88, 32, true);
            Arrays.fill(expected.pixels(), (byte) 0xFF);
            paste(expected, ImageOps.toRgba(alone(av1First, av1First ? 6 : 7).picture()), 0);
            paste(expected, ImageOps.toRgba(alone(!av1First, av1First ? 7 : 6).picture()), firstWidth);
            assertEquals(av1First ? ImageFormat.AVIF : ImageFormat.HEIC, Images.detect(MediaSource.of(data))
                    .orElseThrow());
            for (var image : List.of(Images.decode(MediaSource.of(data)), HeicDecoder.decode(MediaSource.of(data)),
                    AvifDecoder.decode(MediaSource.of(data)))) {
                var picture = (RgbaPicture) image.picture();
                assertEquals(88, picture.width());
                assertArrayEquals(expected.pixels(), ImageOps.toRgba(picture).pixels());
            }
        }
    }

    @Nested
    @DisplayName("grids mixing AV1 and JPEG tiles")
    class MixedGrids {
        /** Copies a plane of a picture into a tightly packed array. */
        private static byte[] plane(byte[] samples, int stride, int left, int width, int height) {
            var plane = new byte[width * height];
            for (var y = 0; y < height; y++) {
                System.arraycopy(samples, y * stride + left, plane, y * width, width);
            }
            return plane;
        }

        private static void assertTile(YuvPicture tile, YuvPicture grid, int left) {
            var format = tile.chromaFormat();
            assertEquals(format, grid.chromaFormat());
            assertArrayEquals(plane(tile.y(), tile.yStride(), 0, tile.width(), tile.height()),
                    plane(grid.y(), grid.yStride(), left, tile.width(), tile.height()), "luma of the tile at " + left);
            var chromaWidth = tile.width() >> format.shiftX();
            var chromaHeight = tile.height() >> format.shiftY();
            var chromaLeft = left >> format.shiftX();
            assertArrayEquals(plane(tile.u(), tile.uvStride(), 0, chromaWidth, chromaHeight),
                    plane(grid.u(), grid.uvStride(), chromaLeft, chromaWidth, chromaHeight), "Cb of the tile at " + left);
            assertArrayEquals(plane(tile.v(), tile.uvStride(), 0, chromaWidth, chromaHeight),
                    plane(grid.v(), grid.uvStride(), chromaLeft, chromaWidth, chromaHeight), "Cr of the tile at " + left);
        }

        @ParameterizedTest(name = "AV1 tile first: {0}")
        @ValueSource(booleans = {false, true})
        @DisplayName("a grid stitches each tile reconstructed with the artifact of its coding, whichever decoder opens it")
        void grid(boolean av1First) {
            var av1 = Av1Item.of(AvifEncoder.encode(pattern(64, 64, 8), AvifEncoderOptions.lossy(70)));
            var jpegPayload = JpegEncoder.encode(pattern(64, 64, 9),
                    JpegEncoderOptions.builder().chromaFormat(ChromaFormat.YUV420).build());
            var builder = new HeifBuilder().brands("mif1", "mif1", "miaf");
            var av1Tile = av1.add(builder);
            var jpegTile = builder.item("jpeg", jpegPayload, plain(HeifBuilder.ispe(64, 64)));
            builder.hidden(av1Tile).hidden(jpegTile);
            var grid = builder.idatItem("grid", HeifBuilder.grid(1, 2, 128, 64), plain(HeifBuilder.ispe(128, 64)));
            builder.reference("dimg", grid, av1First ? new int[]{av1Tile, jpegTile} : new int[]{jpegTile, av1Tile});
            builder.primary(grid);
            var data = builder.build();
            var jpegAlone = new HeifBuilder().brands("mif1", "mif1", "miaf");
            jpegAlone.primary(jpegAlone.item("jpeg", jpegPayload, plain(HeifBuilder.ispe(64, 64))));
            var jpegPicture = assertInstanceOf(YuvPicture.class,
                    HeicDecoder.decode(MediaSource.of(jpegAlone.build())).picture());
            var av1Picture = assertInstanceOf(YuvPicture.class, av1.alone().picture());
            var family = av1First ? ImageFormat.AVIF : ImageFormat.HEIC;
            assertEquals(family, Images.detect(MediaSource.of(data)).orElseThrow());
            for (var image : List.of(Images.decode(MediaSource.of(data)), HeicDecoder.decode(MediaSource.of(data)),
                    AvifDecoder.decode(MediaSource.of(data)))) {
                assertEquals(family, image.format());
                var picture = assertInstanceOf(YuvPicture.class, image.picture());
                assertEquals(128, picture.width());
                assertEquals(64, picture.height());
                assertTile(av1First ? av1Picture : jpegPicture, picture, 0);
                assertTile(av1First ? jpegPicture : av1Picture, picture, 64);
            }
            for (var info : List.of(Images.probe(MediaSource.of(data)), HeicDecoder.probe(MediaSource.of(data)),
                    AvifDecoder.probe(MediaSource.of(data)))) {
                assertEquals(family, info.format());
                assertEquals(128, info.width());
            }
        }
    }

    @Nested
    @DisplayName("derivations that cross between the families")
    class Cycles {
        @Test
        @DisplayName("reject a derivation cycle through both decoders as malformed, without looping")
        void cycle() {
            var builder = new HeifBuilder().brands("mif1", "mif1", "miaf");
            var jpeg = jpeg(builder, 1);
            builder.hidden(jpeg);
            var overlay = builder.idatItem("iovl", HeifBuilder.overlay(false, new int[]{0, 0, 0, 65535}, 40, 24,
                    new int[]{0, 0}, new int[]{0, 0}), plain(HeifBuilder.ispe(40, 24)));
            var transform = builder.idatItem("sato", HeifBuilder.sato(2, 1), plain(HeifBuilder.ispe(40, 24)),
                    plain(HeifBuilder.pixi(8, 8, 8)));
            builder.hidden(transform);
            builder.reference("dimg", overlay, jpeg, transform).reference("dimg", transform, overlay);
            builder.primary(overlay);
            var data = builder.build();
            for (var operation : List.<Runnable>of(() -> Images.decode(MediaSource.of(data)),
                    () -> HeicDecoder.decode(MediaSource.of(data)), () -> AvifDecoder.decode(MediaSource.of(data)))) {
                assertTimeoutPreemptively(Duration.ofSeconds(30),
                        () -> assertThrows(MediaException.class, operation::run));
            }
        }
    }

    @Nested
    @DisplayName("collections mixing AV1 and JPEG images")
    class Collections {
        private record Mixed(byte[] data, byte[] av1, int jpegSeed1, int jpegSeed2) {
            static Mixed of(boolean av1Primary) {
                var av1 = avif(3);
                var builder = new HeifBuilder().brands("mif1", "mif1", "miaf");
                int first;
                int second;
                int third;
                if (av1Primary) {
                    first = Av1Item.of(av1).add(builder);
                    second = jpeg(builder, 4);
                } else {
                    first = jpeg(builder, 4);
                    second = Av1Item.of(av1).add(builder);
                }
                third = jpeg(builder, 5);
                builder.metaBox(HeifBuilder.grpl(HeifBuilder.entityGroup("ster", 9, second, first),
                        HeifBuilder.entityGroup("brst", 10, first, second, third)));
                builder.primary(first);
                return new Mixed(builder.build(), av1, 4, 5);
            }
        }

        private static DecodedImage jpegAlone(int seed) {
            var builder = new HeifBuilder().brands("mif1", "mif1", "miaf");
            builder.primary(jpeg(builder, seed));
            return HeicDecoder.decode(MediaSource.of(builder.build()));
        }

        private static void assertPages(Mixed mixed, boolean av1Primary, PageReader reader) {
            assertEquals(3, reader.pageCount());
            assertEquals(List.of(new PageGroup("ster", List.of(1, 0)), new PageGroup("brst", List.of(0, 1, 2))),
                    reader.groups());
            var av1Page = av1Primary ? 0 : 1;
            var jpegPage = av1Primary ? 1 : 0;
            assertSame(Av1Item.of(mixed.av1()).alone(), reader.read(av1Page));
            assertEquals(ImageFormat.AVIF, reader.probe(av1Page).format());
            assertSame(jpegAlone(mixed.jpegSeed1()), reader.read(jpegPage));
            assertEquals(ImageFormat.HEIC, reader.probe(jpegPage).format());
            assertSame(jpegAlone(mixed.jpegSeed2()), reader.read(2));
            assertEquals(3, reader.probe(2).frameCount());
        }

        @ParameterizedTest(name = "AV1 primary: {0}")
        @ValueSource(booleans = {false, true})
        @DisplayName("read each page with the artifact of its coding, from every entry point")
        void pages(boolean av1Primary) {
            var mixed = Mixed.of(av1Primary);
            assertEquals(av1Primary ? ImageFormat.AVIF : ImageFormat.HEIC,
                    Images.detect(MediaSource.of(mixed.data())).orElseThrow());
            List<Supplier<PageReader>> openers = List.of(() -> Images.openPages(MediaSource.of(mixed.data())),
                    () -> HeicDecoder.openPages(MediaSource.of(mixed.data())),
                    () -> AvifDecoder.openPages(MediaSource.of(mixed.data())));
            for (var opener : openers) {
                try (var reader = opener.get()) {
                    assertPages(mixed, av1Primary, reader);
                }
            }
        }

        @Test
        @DisplayName("decode the AV1 view of a stereo pair through the HEIC decoder")
        void stereoView() {
            var mixed = Mixed.of(false);
            assertSame(Av1Item.of(mixed.av1()).alone(),
                    HeicDecoder.decodeStereoView(MediaSource.of(mixed.data()), 0).orElseThrow());
        }
    }

    @Nested
    @DisplayName("alpha images coded in the other family")
    class CrossCodedAlpha {
        private static final HeifBuilder.Property ALPHA =
                essential(HeifBuilder.auxC("urn:mpeg:mpegB:cicp:systems:auxiliary:alpha"));

        /** Adds a 64x64 image, the smallest an AV1 grid tile may be, coded with AV1 or JPEG. */
        private static int image(HeifBuilder builder, boolean av1, int seed, HeifBuilder.Property... extra) {
            if (av1) {
                return Av1Item.of(AvifEncoder.encode(pattern(64, 64, seed), AvifEncoderOptions.lossy(70)))
                        .add(builder, extra);
            }
            var properties = new ArrayList<>(List.of(plain(HeifBuilder.ispe(64, 64))));
            properties.addAll(List.of(extra));
            return builder.item("jpeg", JpegEncoder.encode(pattern(64, 64, seed)),
                    properties.toArray(HeifBuilder.Property[]::new));
        }

        /** The luma plane of an image decoded alone, which an alpha image of the same payload gives. */
        private static byte[] luma(boolean av1, int seed) {
            var builder = new HeifBuilder().brands("mif1", "mif1", "miaf");
            builder.primary(image(builder, av1, seed));
            var picture = (YuvPicture) Images.decode(MediaSource.of(builder.build())).picture();
            assertEquals(ColorRange.FULL, picture.color().range(), "the alpha samples are full range");
            var plane = new byte[picture.width() * picture.height()];
            for (var y = 0; y < picture.height(); y++) {
                System.arraycopy(picture.y(), y * picture.yStride(), plane, y * picture.width(), picture.width());
            }
            return plane;
        }

        /**
         * Builds an image of one family whose alpha images are of the other: a single image, or a grid of two
         * tiles with an alpha image per tile.
         */
        private static byte[] file(boolean av1Color, boolean grid, boolean withAlpha) {
            var builder = new HeifBuilder().brands("mif1", "mif1", "miaf");
            var tiles = grid ? 2 : 1;
            var ids = new int[tiles];
            for (var tile = 0; tile < tiles; tile++) {
                ids[tile] = image(builder, av1Color, 3 + tile);
                if (withAlpha) {
                    var alpha = image(builder, !av1Color, 11 + tile, ALPHA);
                    builder.reference("auxl", alpha, ids[tile]).hidden(alpha);
                }
            }
            if (!grid) {
                builder.primary(ids[0]);
                return builder.build();
            }
            var item = builder.idatItem("grid", HeifBuilder.grid(1, 2, 128, 64), plain(HeifBuilder.ispe(128, 64)));
            builder.reference("dimg", item, ids);
            for (var id : ids) {
                builder.hidden(id);
            }
            builder.primary(item);
            return builder.build();
        }

        @ParameterizedTest(name = "AV1 color: {0}, grid: {1}")
        @CsvSource({"false,false", "true,false", "false,true", "true,true"})
        @DisplayName("attach the alpha with the artifact of its coding, whichever decoder opens the file")
        void attached(boolean av1Color, boolean grid) {
            var data = file(av1Color, grid, true);
            var opaque = ImageOps.toRgba(Images.decode(MediaSource.of(file(av1Color, grid, false))).picture());
            var width = grid ? 128 : 64;
            var expected = new byte[width * 64];
            for (var tile = 0; tile < (grid ? 2 : 1); tile++) {
                var plane = luma(!av1Color, 11 + tile);
                for (var y = 0; y < 64; y++) {
                    System.arraycopy(plane, y * 64, expected, y * width + 64 * tile, 64);
                }
            }
            var family = av1Color ? ImageFormat.AVIF : ImageFormat.HEIC;
            assertEquals(family, Images.detect(MediaSource.of(data)).orElseThrow());
            for (var image : List.of(Images.decode(MediaSource.of(data)), HeicDecoder.decode(MediaSource.of(data)),
                    AvifDecoder.decode(MediaSource.of(data)))) {
                assertEquals(family, image.format());
                var rgba = ImageOps.toRgba(image.picture());
                assertEquals(width, rgba.width());
                for (var y = 0; y < 64; y++) {
                    for (var x = 0; x < width; x++) {
                        var at = y * rgba.stride() + 4 * x;
                        var reference = y * opaque.stride() + 4 * x;
                        for (var channel = 0; channel < 3; channel++) {
                            assertEquals(opaque.pixels()[reference + channel], rgba.pixels()[at + channel],
                                    "color at " + x + "," + y);
                        }
                        assertEquals(expected[y * width + x], rgba.pixels()[at + 3], "alpha at " + x + "," + y);
                    }
                }
            }
            for (var info : List.of(Images.probe(MediaSource.of(data)),
                    HeicDecoder.probe(MediaSource.of(data)), AvifDecoder.probe(MediaSource.of(data)))) {
                assertTrue(info.hasAlpha());
            }
        }
    }
}
