package com.github.auties00.smalt.image.tiff;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.image.ops.Region;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.container.CountingSource;
import com.github.auties00.smalt.testkit.image.PictureDump;
import com.github.auties00.smalt.testkit.image.RegionAsserts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers region decoding of TIFF pages: a region decode equals the crop of the whole decode for every strip and tile
 * layout, compression, sample layout and transparency mask the samples hold, reads only the strips or tiles the
 * region intersects, is bounded by the limits of the region rather than the page, and maps a region onto the
 * reduced-resolution level a size hint selects.
 */
@DisplayName("TIFF region decoding")
class TiffRegionTest {
    private static final int WIDTH = 75;
    private static final int HEIGHT = 53;

    static RgbaPicture picture(int width, int height, boolean alpha, long seed) {
        var picture = RgbaPicture.allocate(width, height, alpha);
        var random = new Random(seed);
        var pixels = picture.pixels();
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var at = y * picture.stride() + x * 4;
                pixels[at] = (byte) (x * 255 / width + random.nextInt(9));
                pixels[at + 1] = (byte) (y * 255 / height + random.nextInt(9));
                pixels[at + 2] = (byte) ((x + y) * 3 + random.nextInt(32));
                pixels[at + 3] = alpha ? (byte) random.nextInt(256) : (byte) 255;
            }
        }
        return picture;
    }

    static Rgba16Picture picture16(int width, int height, long seed) {
        var picture = Rgba16Picture.allocate(width, height, true);
        var random = new Random(seed);
        var pixels = picture.pixels();
        for (var i = 0; i < pixels.length; i++) {
            pixels[i] = (short) random.nextInt(65536);
        }
        return picture;
    }

    /** Files written by the encoder, by name: every compression it writes, in strips and in tiles. */
    static Map<String, byte[]> encoded() {
        var files = new LinkedHashMap<String, byte[]>();
        var rgb = picture(WIDTH, HEIGHT, false, 1);
        var rgba = picture(WIDTH, HEIGHT, true, 2);
        var compressions = List.of(TiffCompression.NONE, TiffCompression.LZW, TiffCompression.DEFLATE,
                TiffCompression.PACKBITS, TiffCompression.ZSTD, TiffCompression.JPEG, TiffCompression.WEBP,
                TiffCompression.JPEG_2000);
        for (var compression : compressions) {
            var lossy = compression == TiffCompression.JPEG || compression == TiffCompression.WEBP
                    || compression == TiffCompression.JPEG_2000;
            var source = lossy ? rgb : rgba;
            var base = TiffEncoderOptions.builder().compression(compression);
            // JPEG strips hold whole MCU rows
            files.put(compression + "-strips", TiffEncoder.encode(source, base.strips(lossy ? 16 : 7).build()));
            files.put(compression + "-tiles", TiffEncoder.encode(source, base.tiles(32, 16).build()));
        }
        files.put("planar-tiles", TiffEncoder.encode(rgba, TiffEncoderOptions.builder()
                .compression(TiffCompression.LZW).planar(true).tiles(16, 16).build()));
        files.put("planar-strips", TiffEncoder.encode(rgba, TiffEncoderOptions.builder().planar(true).strips(5)
                .build()));
        files.put("sixteen-tiles", TiffEncoder.encode(picture16(WIDTH, HEIGHT, 3), TiffEncoderOptions.builder()
                .compression(TiffCompression.DEFLATE).depth(TiffEncoderOptions.Depth.SIXTEEN).tiles(16, 32).build()));
        files.put("gray-strips", TiffEncoder.encode(rgb, TiffEncoderOptions.builder()
                .layout(TiffEncoderOptions.Layout.GRAY).strips(3).build()));
        files.put("bilevel-tiles", TiffEncoder.encode(rgb, TiffEncoderOptions.builder()
                .layout(TiffEncoderOptions.Layout.BILEVEL).compression(TiffCompression.CCITT_T6).tiles(16, 16)
                .build()));
        files.put("palette-tiles", TiffEncoder.encode(rgb, TiffEncoderOptions.builder()
                .layout(TiffEncoderOptions.Layout.PALETTE).tiles(32, 32).build()));
        files.put("cmyk-strips", TiffEncoder.encode(rgb, TiffEncoderOptions.builder()
                .layout(TiffEncoderOptions.Layout.CMYK).strips(4).build()));
        files.put("ycbcr-420-tiles", TiffEncoder.encode(rgb, TiffEncoderOptions.builder()
                .layout(TiffEncoderOptions.Layout.YCBCR).chromaFormat(ChromaFormat.YUV420).tiles(16, 16).build()));
        files.put("ycbcr-422-strips", TiffEncoder.encode(rgb, TiffEncoderOptions.builder()
                .layout(TiffEncoderOptions.Layout.YCBCR).chromaFormat(ChromaFormat.YUV422).strips(6).build()));
        files.put("bigtiff-tiles", TiffEncoder.encode(rgba, TiffEncoderOptions.builder().bigTiff(true)
                .tiles(48, 32).build()));
        return files;
    }

    /** The behavior samples and the committed TIFF vectors. */
    static Map<String, byte[]> resources() {
        var files = new LinkedHashMap<String, byte[]>();
        for (var directory : List.of(Path.of("src", "test", "resources", "samples", "tiff"),
                Path.of("src", "test", "resources", "com", "github", "auties00", "smalt", "image", "tiff"))) {
            try (var walk = Files.walk(directory)) {
                for (var file : walk.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".tif")
                        || path.toString().endsWith(".tiff")).sorted().toList()) {
                    files.put(directory.relativize(file).toString().replace('\\', '/'), Files.readAllBytes(file));
                }
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }
        return files;
    }

    static Stream<DynamicTest> cases(Map<String, byte[]> files, ImageDecoderOptions options) {
        return files.entrySet().stream().map(entry -> DynamicTest.dynamicTest(entry.getKey(), () -> {
            var data = entry.getValue();
            Function<ImageDecoderOptions, DecodedImage> decoder = o -> TiffDecoder.decode(MediaSource.of(data), o);
            DecodedImage whole;
            try {
                whole = decoder.apply(options);
            } catch (MediaException failure) {
                // a page that does not decode whole fails the same way for a region of it
                var region = options.toBuilder().region(0, 0, 1, 1).build();
                assertEquals(failure.getClass(), assertThrows(MediaException.class,
                        () -> decoder.apply(region)).getClass(), entry.getKey());
                return;
            }
            var regions = RegionAsserts.regions(whole.sourceWidth(), whole.sourceHeight(), 12,
                    entry.getKey().hashCode());
            RegionAsserts.assertRegions(entry.getKey(), decoder, options, regions);
            RegionAsserts.assertOutsideRejected(decoder, options, whole.sourceWidth(), whole.sourceHeight());
            Function<ImageDecoderOptions, DecodedImage> dispatched = o -> Images.decode(MediaSource.of(data), o);
            RegionAsserts.assertRegions(entry.getKey() + " through Images", dispatched, options,
                    regions.subList(0, 4));
        }));
    }

    @TestFactory
    @DisplayName("a region of every encoded layout equals the crop of the whole page")
    Stream<DynamicTest> encodedLayouts() {
        return cases(encoded(), ImageDecoderOptions.defaults());
    }

    @TestFactory
    @DisplayName("a region of every variant sample equals the crop of the whole page")
    Stream<DynamicTest> variantSamples() {
        return cases(TiffVariantSamples.files(), ImageDecoderOptions.defaults());
    }

    @TestFactory
    @DisplayName("a region of every behavior sample equals the crop of the whole page")
    Stream<DynamicTest> behaviorSamples() {
        return cases(resources(), ImageDecoderOptions.defaults());
    }

    @TestFactory
    @DisplayName("16-bit, channel and sRGB decodes of a region equal the crop of the whole page")
    Stream<DynamicTest> options() {
        var files = new LinkedHashMap<String, byte[]>();
        var encoded = encoded();
        files.put("sixteen-tiles", encoded.get("sixteen-tiles"));
        files.put("cmyk-strips", encoded.get("cmyk-strips"));
        files.putAll(TiffVariantSamples.files());
        return Stream.of(ImageDecoderOptions.builder().highBitDepth(true).build(),
                        ImageDecoderOptions.builder().keepChannels(true).highBitDepth(true).build(),
                        ImageDecoderOptions.builder().convertToSrgb(true).build())
                .flatMap(options -> cases(files, options).map(test -> DynamicTest.dynamicTest(
                        test.getDisplayName() + " " + options, test.getExecutable())));
    }

    @Test
    @DisplayName("every page of a page reader decodes the region")
    void pages() {
        var first = picture(40, 30, false, 4);
        var second = picture(40, 30, true, 5);
        var data = TiffEncoder.encode(List.of(first, second), TiffEncoderOptions.builder().tiles(16, 16).build());
        var region = new Region(9, 7, 20, 13);
        try (var whole = TiffDecoder.openPages(MediaSource.of(data));
             var pages = TiffDecoder.openPages(MediaSource.of(data),
                     ImageDecoderOptions.builder().region(region).build())) {
            for (var index = 0; index < 2; index++) {
                var expected = ImageOps.crop(whole.read(index).picture(), region);
                assertEquals(PictureDump.picture(expected), PictureDump.picture(pages.read(index).picture()));
            }
        }
    }

    @Test
    @DisplayName("a region of a tiled page reads only the tiles it intersects")
    void readsIntersectingTiles() {
        var data = TiffEncoder.encode(picture(1024, 768, false, 6), TiffEncoderOptions.builder().tiles(64, 64)
                .build());
        var source = new CountingSource(data);
        var region = new Region(500, 300, 10, 10);
        var decoded = TiffDecoder.decode(source, ImageDecoderOptions.builder().region(region).build());
        assertEquals(10, decoded.picture().width());
        // one 64x64 RGB tile of 12 KiB, plus the header and the tile tables of the 192 tiles
        assertTrue(source.bytes < 64 * 64 * 3 + 8192, source.toString());
        var whole = TiffDecoder.decode(MediaSource.of(data));
        assertEquals(PictureDump.picture(ImageOps.crop(whole.picture(), region)),
                PictureDump.picture(decoded.picture()));
    }

    @Test
    @DisplayName("a region of a stripped page reads only the strips it intersects")
    void readsIntersectingStrips() {
        var data = TiffEncoder.encode(picture(256, 1024, false, 7), TiffEncoderOptions.builder().strips(16).build());
        var source = new CountingSource(data);
        TiffDecoder.decode(source, ImageDecoderOptions.builder().region(30, 600, 100, 20).build());
        // two strips of 16 rows of 768 bytes, plus the header and the strip tables
        assertTrue(source.bytes < 2 * 16 * 256 * 3 + 4096, source.toString());
    }

    @Test
    @DisplayName("a region of a page larger than the limits decodes under limits that bound the region")
    void regionBoundsTheLimits() {
        var data = TiffEncoder.encode(picture(1200, 900, false, 8), TiffEncoderOptions.builder()
                .compression(TiffCompression.DEFLATE).tiles(128, 128).build());
        var limits = MediaLimits.builder().maxPixels(512 * 512).build();
        var options = ImageDecoderOptions.builder().limits(limits).build();
        assertThrows(MediaException.LimitExceeded.class, () -> TiffDecoder.decode(MediaSource.of(data), options));
        var region = new Region(700, 500, 300, 200);
        var decoded = TiffDecoder.decode(MediaSource.of(data), options.toBuilder().region(region).build());
        var whole = TiffDecoder.decode(MediaSource.of(data));
        assertEquals(PictureDump.picture(ImageOps.crop(whole.picture(), region)),
                PictureDump.picture(decoded.picture()));
        assertEquals(1200, decoded.sourceWidth());
        assertThrows(MediaException.LimitExceeded.class, () -> TiffDecoder.decode(MediaSource.of(data),
                options.toBuilder().region(0, 0, 1200, 300).build()));
    }

    @Test
    @DisplayName("a region with a size hint maps onto the reduced-resolution level the scaled hint selects")
    void levels() {
        var page = picture(64, 48, false, 9);
        var half = picture(32, 24, false, 10);
        var quarter = picture(16, 12, false, 11);
        var data = TiffFiles.chain(TiffFiles.of(64, 48, 3, 8).data(rgb(page)).sub(TiffFiles.of(32, 24, 3, 8)
                .longTag(254, 1).data(rgb(half))).sub(TiffFiles.of(16, 12, 3, 8).longTag(254, 1)
                .data(rgb(quarter))));
        Function<ImageDecoderOptions, DecodedImage> decoder = o -> TiffDecoder.decode(MediaSource.of(data), o);
        for (var minimum : List.of(new int[]{8, 6}, new int[]{9, 6}, new int[]{16, 12}, new int[]{17, 1},
                new int[]{1, 1})) {
            var options = ImageDecoderOptions.builder().minSize(minimum[0], minimum[1]).build();
            RegionAsserts.assertRegions("pyramid " + minimum[0] + "x" + minimum[1], decoder, options,
                    RegionAsserts.regions(64, 48, 8, minimum[0]));
        }
        // a 32x24 region with an 8x6 hint scales the hint to 16x12, the quarter level, where it is 8x6
        var decoded = decoder.apply(ImageDecoderOptions.builder().minSize(8, 6).region(16, 12, 32, 24).build());
        assertEquals(8, decoded.picture().width());
        assertEquals(6, decoded.picture().height());
        assertEquals(64, decoded.sourceWidth());
    }

    private static byte[] rgb(RgbaPicture picture) {
        var out = new byte[picture.width() * picture.height() * 3];
        for (var o = 0; o < out.length; o += 3) {
            var i = o / 3 * 4;
            out[o] = picture.pixels()[i];
            out[o + 1] = picture.pixels()[i + 1];
            out[o + 2] = picture.pixels()[i + 2];
        }
        return out;
    }

    @Test
    @DisplayName("a region of a page with a strip or tile missing declares the alpha the whole page declares")
    void missingChunk() {
        for (var tiled : new boolean[]{false, true}) {
            var options = TiffEncoderOptions.uncompressed().toBuilder();
            var file = TiffEncoder.encode(picture(48, 40, false, 7), tiled ? options.tiles(16, 16).build()
                    : options.strips(8).build());
            // zero the offset of the last strip or tile: StripOffsets or TileOffsets, stored out of line
            var damaged = file.clone();
            var ifd = (damaged[4] & 0xFF) | (damaged[5] & 0xFF) << 8 | (damaged[6] & 0xFF) << 16;
            var count = (damaged[ifd] & 0xFF) | (damaged[ifd + 1] & 0xFF) << 8;
            for (var i = 0; i < count; i++) {
                var at = ifd + 2 + i * 12;
                var tag = (damaged[at] & 0xFF) | (damaged[at + 1] & 0xFF) << 8;
                if (tag == 273 || tag == 324) {
                    var values = (damaged[at + 4] & 0xFF) | (damaged[at + 5] & 0xFF) << 8;
                    var pointer = (damaged[at + 8] & 0xFF) | (damaged[at + 9] & 0xFF) << 8
                            | (damaged[at + 10] & 0xFF) << 16;
                    for (var b = 0; b < 4; b++) {
                        damaged[pointer + (values - 1) * 4 + b] = 0;
                    }
                }
            }
            Function<ImageDecoderOptions, DecodedImage> decoder = o -> TiffDecoder.decode(MediaSource.of(damaged), o);
            var whole = decoder.apply(ImageDecoderOptions.defaults());
            assertTrue(whole.picture().hasAlpha(), "the whole page declares the alpha of its missing data");
            RegionAsserts.assertRegions(tiled ? "tiles" : "strips", decoder, ImageDecoderOptions.defaults(),
                    List.of(new Region(0, 0, 5, 5), new Region(3, 2, 20, 9), new Region(40, 33, 8, 7),
                            new Region(0, 0, 48, 40)));
        }
    }

    @TestFactory
    @DisplayName("a transparency mask applies to a region as to the whole page")
    Stream<DynamicTest> masks() {
        var files = new LinkedHashMap<String, byte[]>();
        for (var size : List.of(new int[]{10, 8}, new int[]{20, 16}, new int[]{7, 5}, new int[]{33, 3})) {
            files.put("mask " + size[0] + "x" + size[1], TiffFiles.chain(TiffMaskTest.rgb(size[0]),
                    TiffMaskTest.mask(TiffMaskTest.bits(size[0], size[1], size[1]), size[0], size[1])));
        }
        return cases(files, ImageDecoderOptions.defaults());
    }

    @Test
    @DisplayName("regions of a large tiled page decoded in parallel equal the crop of the whole page")
    void largeParallel() {
        var data = TiffEncoder.encode(picture(700, 500, true, 12), TiffEncoderOptions.builder()
                .compression(TiffCompression.LZW).tiles(64, 32).build());
        Function<ImageDecoderOptions, DecodedImage> decoder = o -> TiffDecoder.decode(MediaSource.of(data), o);
        var whole = decoder.apply(ImageDecoderOptions.defaults());
        var regions = new ArrayList<>(RegionAsserts.regions(700, 500, 6, 13));
        regions.add(new Region(63, 31, 2, 2));
        regions.add(new Region(64, 32, 64, 32));
        for (var region : regions) {
            RegionAsserts.assertRegion("large", decoder, ImageDecoderOptions.defaults(), region, whole);
        }
        Picture picture = whole.picture();
        assertEquals(700, picture.width());
    }
}
