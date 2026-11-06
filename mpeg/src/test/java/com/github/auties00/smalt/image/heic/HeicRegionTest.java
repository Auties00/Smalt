package com.github.auties00.smalt.image.heic;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.image.ops.Region;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.HeifBuilder;
import com.github.auties00.smalt.testkit.container.CountingSource;
import com.github.auties00.smalt.testkit.image.PictureDump;
import com.github.auties00.smalt.testkit.image.RegionAsserts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;
import java.util.stream.Stream;

import static com.github.auties00.smalt.testkit.HeifBuilder.essential;
import static com.github.auties00.smalt.testkit.HeifBuilder.plain;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers region decoding of HEIC files: a region decode equals the crop of the whole decode for grids of uncompressed
 * and x265-coded images, with alpha grids, per-image alpha, alpha of another size, clean apertures at odd offsets
 * and rotations, under every decoding option, reads only the grid images the region intersects, and is bounded by
 * the limits of those images rather than the whole grid.
 */
@DisplayName("HEIC region decoding")
class HeicRegionTest {
    private static final ImageDecoderOptions WIDE = ImageDecoderOptions.builder().highBitDepth(true).build();

    /** An uncompressed grid image: RGB, RGBA, 4:2:0 or 4:4:4 Y'CbCr of 8 or 10 bits, or gray, of random samples. */
    static int unci(HeifBuilder builder, String kind, int width, int height, long seed) {
        var random = new Random(seed);
        byte[] data;
        HeifBuilder.Property layout;
        HeifBuilder.Property components = null;
        switch (kind) {
            case "rgb3" -> {
                data = new byte[width * height * 3];
                random.nextBytes(data);
                layout = essential(HeicUncompressedTest.profile("rgb3"));
            }
            case "rgba" -> {
                data = HeicUncompressedTest.rgba(random, width, height);
                layout = essential(HeicUncompressedTest.profile("rgba"));
            }
            case "gray" -> {
                data = new byte[width * height];
                random.nextBytes(data);
                layout = essential(HeicUncompressedTest.planar(0, 8, 1));
                components = essential(HeicUncompressedTest.cmpd(0));
            }
            default -> {
                var subsampled = kind.startsWith("420");
                var depth = kind.endsWith("10") ? 10 : 8;
                var chroma = subsampled ? (width / 2) * (height / 2) : width * height;
                var out = new ByteArrayOutputStream();
                for (var i = 0; i < width * height + 2 * chroma; i++) {
                    var value = random.nextInt(1 << depth);
                    if (depth > 8) {
                        out.write(value >> 8);
                    }
                    out.write(value);
                }
                data = out.toByteArray();
                layout = essential(HeicUncompressedTest.planar(subsampled ? 2 : 0, depth, 3));
                components = essential(HeicUncompressedTest.cmpd(1, 2, 3));
            }
        }
        var properties = new ArrayList<HeifBuilder.Property>(List.of(plain(HeifBuilder.ispe(width, height)), layout));
        if (components != null) {
            properties.add(components);
        }
        var id = builder.item("unci", data, properties.toArray(HeifBuilder.Property[]::new));
        builder.hidden(id);
        return id;
    }

    /** A grid of uncompressed images, of the given output size, with extra grid properties. */
    static int unciGrid(HeifBuilder builder, String kind, int rows, int columns, int tileWidth, int tileHeight,
                        int width, int height, long seed, HeifBuilder.Property... extra) {
        var ids = new int[rows * columns];
        for (var i = 0; i < ids.length; i++) {
            ids[i] = unci(builder, kind, tileWidth, tileHeight, seed * 100 + i);
        }
        var properties = new ArrayList<>(List.of(plain(HeifBuilder.ispe(width, height))));
        properties.addAll(List.of(extra));
        var grid = builder.idatItem("grid", HeifBuilder.grid(rows, columns, width, height),
                properties.toArray(HeifBuilder.Property[]::new));
        builder.reference("dimg", grid, ids);
        return grid;
    }

    static byte[] single(String kind, int rows, int columns, int tileWidth, int tileHeight, int width, int height,
                         HeifBuilder.Property... extra) {
        var builder = HeicUncompressedTest.unif();
        if (rows * columns > 32) {
            builder.wideIndex();
        }
        builder.primary(unciGrid(builder, kind, rows, columns, tileWidth, tileHeight, width, height, 1, extra));
        return builder.build();
    }

    static Map<String, byte[]> files() {
        var files = new LinkedHashMap<String, byte[]>();
        files.put("rgb3 3x2", single("rgb3", 2, 3, 64, 48, 180, 90));
        files.put("rgba 2x2", single("rgba", 2, 2, 40, 30, 80, 55));
        files.put("420 3x3", single("420", 3, 3, 32, 32, 90, 96));
        files.put("420-10 2x3", single("420-10", 3, 2, 48, 32, 96, 70));
        files.put("444 2x2 rotated", single("444", 2, 2, 40, 40, 77, 80, plain(HeifBuilder.irot(1))));
        // Display P3 primaries: the sRGB conversion converts the Y'CbCr samples, upsampling the chroma
        files.put("420 display p3", single("420", 3, 3, 32, 32, 90, 84, plain(HeifBuilder.nclx(12, 13, 1, false))));
        files.put("420 bt2020 pq", single("420-10", 2, 3, 32, 32, 70, 64, plain(HeifBuilder.nclx(9, 16, 9, true))));
        files.put("420 clap centered odd", single("420", 3, 3, 32, 32, 96, 96,
                essential(HeifBuilder.clap(81, 1, 67, 1, 0, 1, 0, 1))));
        files.put("420 clap offset", single("420", 3, 3, 32, 32, 96, 96,
                essential(HeifBuilder.clap(60, 1, 50, 1, -5, 1, 7, 1))));
        files.put("444 clap mirrored", single("444", 2, 3, 32, 32, 96, 64,
                essential(HeifBuilder.clap(71, 1, 41, 1, 3, 2, -1, 2)), essential(HeifBuilder.imir(1))));
        // an alpha grid of the same layout, and one of another tile size
        for (var alphaTile : new int[]{32, 24}) {
            var builder = HeicUncompressedTest.unif();
            var color = unciGrid(builder, "420", 3, 3, 32, 32, 90, 96, 2);
            var rows = (96 + alphaTile - 1) / alphaTile;
            var columns = (90 + alphaTile - 1) / alphaTile;
            var alpha = unciGrid(builder, "gray", rows, columns, alphaTile, alphaTile, 90, 96, 3,
                    essential(HeifBuilder.auxC("urn:mpeg:hevc:2015:auxid:1")));
            builder.reference("auxl", alpha, color).hidden(alpha).primary(color);
            files.put("420 alpha grid " + alphaTile, builder.build());
        }
        // a single alpha image smaller than the grid, resampled to it
        {
            var builder = HeicUncompressedTest.unif();
            var color = unciGrid(builder, "444", 2, 2, 32, 32, 64, 60, 4);
            var alpha = unci(builder, "gray", 21, 17, 5);
            builder.reference("auxl", alpha, color).primary(color);
            files.put("444 small alpha", builder.build());
        }
        // an alpha image per grid image
        {
            var builder = HeicUncompressedTest.unif();
            var ids = new int[4];
            for (var i = 0; i < 4; i++) {
                ids[i] = unci(builder, "420", 32, 32, 10 + i);
                var alpha = unci(builder, "gray", 32, 32, 20 + i);
                builder.reference("auxl", alpha, ids[i]);
            }
            var grid = builder.idatItem("grid", HeifBuilder.grid(2, 2, 60, 62), plain(HeifBuilder.ispe(60, 62)));
            builder.reference("dimg", grid, ids).primary(grid);
            files.put("420 alpha per image", builder.build());
        }
        return files;
    }

    static Stream<DynamicTest> cases(Map<String, byte[]> files, List<ImageDecoderOptions> optionSets) {
        return files.entrySet().stream().flatMap(entry -> optionSets.stream().map(options -> DynamicTest.dynamicTest(
                entry.getKey() + " " + options.highBitDepth() + "/" + options.convertToSrgb(), () -> {
                    var data = entry.getValue();
                    Function<ImageDecoderOptions, DecodedImage> decoder = o -> HeicDecoder.decode(MediaSource.of(data),
                            o);
                    var whole = decoder.apply(options);
                    var regions = RegionAsserts.regions(whole.sourceWidth(), whole.sourceHeight(), 10,
                            entry.getKey().hashCode());
                    RegionAsserts.assertRegions(entry.getKey(), decoder, options, regions);
                    RegionAsserts.assertOutsideRejected(decoder, options, whole.sourceWidth(), whole.sourceHeight());
                    Function<ImageDecoderOptions, DecodedImage> dispatched = o -> Images.decode(MediaSource.of(data),
                            o);
                    RegionAsserts.assertRegions(entry.getKey() + " through Images", dispatched, options,
                            regions.subList(0, 3));
                })));
    }

    @TestFactory
    @DisplayName("a region of a grid of uncompressed images equals the crop of the whole image")
    Stream<DynamicTest> uncompressed() {
        return cases(files(), List.of(ImageDecoderOptions.defaults(), WIDE,
                ImageDecoderOptions.builder().convertToSrgb(true).build()));
    }

    @TestFactory
    @DisplayName("a region of a grid of x265 images, with alpha, equals the crop of the whole image")
    Stream<DynamicTest> hevc() {
        var files = new LinkedHashMap<String, byte[]>();
        for (var format : List.of("yuv420p", "yuv420p10le", "gray")) {
            var builder = HeicStructureTest.heic();
            builder.primary(HeicStructureTest.grid(builder, HeicStructureTest.tiles(format), 2, 120, 100));
            files.put("x265 " + format, builder.build());
        }
        var alpha = HeicStructureTest.heic();
        var alphas = new ArrayList<HeicStructureTest.Coded>();
        for (var i = 0; i < 4; i++) {
            alphas.add(HeicStructureTest.alphaPlane(64, 64, ""));
        }
        var color = HeicStructureTest.grid(alpha, HeicStructureTest.tiles("yuv420p"), 2, 128, 128);
        var auxGrid = HeicStructureTest.grid(alpha, alphas, 2, 128, 128,
                essential(HeifBuilder.auxC("urn:mpeg:hevc:2015:auxid:1")));
        alpha.reference("auxl", auxGrid, color).primary(color);
        files.put("x265 alpha grid", alpha.build());
        return cases(files, List.of(ImageDecoderOptions.defaults(), WIDE,
                ImageDecoderOptions.builder().convertToSrgb(true).build()));
    }

    static Stream<DynamicTest> damaged(String name, byte[] data) {
        var info = HeicDecoder.probe(MediaSource.of(data));
        var regions = RegionAsserts.damageRegions(info.width(), info.height());
        return RegionAsserts.damaged(data).entrySet().stream().map(variant -> DynamicTest.dynamicTest(name + " "
                + variant.getKey(), () -> RegionAsserts.assertDamaged(name + " " + variant.getKey(),
                o -> HeicDecoder.decode(MediaSource.of(variant.getValue()), o), regions)));
    }

    @TestFactory
    @DisplayName("a region of a damaged grid of uncompressed images equals the crop of its whole decode, or fails")
    Stream<DynamicTest> damagedGrid() {
        return damaged("rgb3 4x4", single("rgb3", 4, 4, 32, 32, 128, 120));
    }

    @TestFactory
    @DisplayName("a region of a damaged grid of x265 images equals the crop of its whole decode, or fails")
    Stream<DynamicTest> damagedHevcGrid() {
        var builder = HeicStructureTest.heic();
        builder.primary(HeicStructureTest.grid(builder, HeicStructureTest.tiles("yuv420p"), 2, 120, 100));
        return damaged("x265 grid", builder.build());
    }

    @Test
    @DisplayName("the HEIC provider declares that it decodes regions")
    void provider() {
        assertTrue(new com.github.auties00.smalt.internal.provider.mpeg.HeicCodecProvider()
                .decodesRegions(com.github.auties00.smalt.image.ImageFormat.HEIC));
    }

    @Test
    @DisplayName("a region of a grid reads only the grid images it intersects")
    void readsIntersectingImages() {
        var data = single("rgb3", 8, 8, 64, 64, 512, 512);
        var source = new CountingSource(data);
        var region = new Region(200, 300, 20, 10);
        var decoded = HeicDecoder.decode(source, ImageDecoderOptions.builder().region(region).build());
        // the four grid images the region and its margin meet and the first one, 12 KiB each, and the metadata,
        // of the 64 of the file
        assertTrue(source.bytes < 6 * 64 * 64 * 3 + 16384, source.toString());
        var whole = HeicDecoder.decode(MediaSource.of(data));
        assertEquals(PictureDump.picture(ImageOps.crop(whole.picture(), region)),
                PictureDump.picture(decoded.picture()));
    }

    @Test
    @DisplayName("a region of a grid larger than the limits decodes under limits that bound its grid images")
    void regionBoundsTheLimits() {
        var data = single("420", 6, 6, 64, 64, 384, 384);
        var options = ImageDecoderOptions.builder().limits(MediaLimits.builder().maxPixels(160 * 160).build())
                .build();
        assertThrows(MediaException.LimitExceeded.class, () -> HeicDecoder.decode(MediaSource.of(data), options));
        var region = new Region(130, 70, 40, 40);
        var decoded = HeicDecoder.decode(MediaSource.of(data), options.toBuilder().region(region).build());
        var whole = HeicDecoder.decode(MediaSource.of(data));
        assertEquals(PictureDump.picture(ImageOps.crop(whole.picture(), region)),
                PictureDump.picture(decoded.picture()));
        assertEquals(384, decoded.sourceWidth());
        // an alpha image of another size is resampled to the whole grid, which the limits then bound
        var builder = HeicUncompressedTest.unif();
        var color = unciGrid(builder, "420", 6, 6, 64, 64, 384, 384, 8);
        var alpha = unciGrid(builder, "gray", 1, 1, 21, 17, 21, 17, 9,
                essential(HeifBuilder.auxC("urn:mpeg:hevc:2015:auxid:1")));
        builder.reference("auxl", alpha, color).hidden(alpha).primary(color);
        var resampled = builder.build();
        assertTrue(HeicDecoder.decode(MediaSource.of(resampled)).picture().hasAlpha());
        assertThrows(MediaException.LimitExceeded.class, () -> HeicDecoder.decode(MediaSource.of(resampled),
                options.toBuilder().region(region).build()));
    }
}
