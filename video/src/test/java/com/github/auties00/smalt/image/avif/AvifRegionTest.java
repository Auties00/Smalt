package com.github.auties00.smalt.image.avif;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.image.ops.Region;
import com.github.auties00.smalt.internal.heif.HeifFile;
import com.github.auties00.smalt.internal.heif.HeifItem;
import com.github.auties00.smalt.internal.heif.HeifProperty;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.HeifBuilder;
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

import static com.github.auties00.smalt.testkit.HeifBuilder.essential;
import static com.github.auties00.smalt.testkit.HeifBuilder.plain;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers region decoding of AVIF files: a region decode equals the crop of the whole decode for grids of every chroma
 * format, depth and alpha layout the encoder writes, for grids rebuilt with clean apertures at odd offsets, and for
 * the committed samples, under every decoding option; reads only the grid images the region intersects, and is
 * bounded by the limits of those images rather than the whole grid.
 */
@DisplayName("AVIF region decoding")
class AvifRegionTest {
    private static final ImageDecoderOptions WIDE = ImageDecoderOptions.builder().highBitDepth(true).build();

    static RgbaPicture picture(int width, int height, boolean alpha, long seed) {
        var picture = RgbaPicture.allocate(width, height, alpha);
        var random = new Random(seed);
        var pixels = picture.pixels();
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var at = y * picture.stride() + x * 4;
                pixels[at] = (byte) (x * 255 / width + random.nextInt(24));
                pixels[at + 1] = (byte) (y * 255 / height + random.nextInt(24));
                pixels[at + 2] = (byte) ((x ^ y) * 5);
                pixels[at + 3] = alpha ? (byte) (x * 7 + y * 3) : (byte) 255;
            }
        }
        return picture;
    }

    static AvifEncoderOptions.Builder fast() {
        return AvifEncoderOptions.lossy(60).toBuilder().speed(10).gridTileSize(64);
    }

    static Map<String, byte[]> encoded() {
        var files = new LinkedHashMap<String, byte[]>();
        var rgb = picture(200, 150, false, 1);
        var rgba = picture(170, 130, true, 2);
        files.put("420 grid", AvifEncoder.encode(rgb, fast().build()));
        files.put("444 10-bit grid", AvifEncoder.encode(rgb, fast().chromaFormat(ChromaFormat.YUV444).bitDepth(10)
                .build()));
        files.put("alpha grid", AvifEncoder.encode(rgba, fast().build()));
        files.put("premultiplied alpha grid", AvifEncoder.encode(rgba, fast().premultipliedAlpha(true).build()));
        files.put("rotated grid", AvifEncoder.encode(rgb, fast().orientation(Orientation.ROTATE_90).build()));
        files.put("single", AvifEncoder.encode(picture(90, 70, true, 3), fast().automaticGridTileSize().build()));
        return files;
    }

    /** Rebuilds the grid of an encoded file with other grid properties, such as a clean aperture. */
    static byte[] regrid(byte[] avif, HeifBuilder.Property... extra) {
        var source = MediaSource.of(avif);
        var file = HeifFile.parse(source, MediaLimits.DEFAULT);
        var primary = file.primaryItem();
        var grid = new HeifBuilder();
        var ids = new ArrayList<Integer>();
        for (var id : primary.references("dimg")) {
            var tile = file.item(id);
            ids.add(copy(grid, source, file, tile));
        }
        var payload = file.readItem(source, primary, MediaLimits.DEFAULT);
        var gridExtent = primary.property(HeifProperty.SpatialExtent.class);
        var properties = new ArrayList<>(List.of(plain(HeifBuilder.ispe((int) gridExtent.width(),
                (int) gridExtent.height()))));
        properties.addAll(List.of(extra));
        var id = grid.idatItem("grid", payload, properties.toArray(HeifBuilder.Property[]::new));
        grid.reference("dimg", id, ids.stream().mapToInt(Integer::intValue).toArray()).primary(id);
        return grid.build();
    }

    private static int copy(HeifBuilder builder, MediaSource source, HeifFile file, HeifItem tile) {
        var extent = tile.property(HeifProperty.SpatialExtent.class);
        var config = tile.property(HeifProperty.Av1Configuration.class);
        var properties = new ArrayList<>(List.of(plain(HeifBuilder.ispe((int) extent.width(), (int) extent.height())),
                essential(HeifBuilder.av1C(config.record()))));
        var pixi = tile.property(HeifProperty.PixelInformation.class);
        if (pixi != null) {
            properties.add(plain(HeifBuilder.pixi(pixi.depths())));
        }
        var id = builder.item("av01", file.readItem(source, tile, MediaLimits.DEFAULT),
                properties.toArray(HeifBuilder.Property[]::new));
        builder.hidden(id);
        return id;
    }

    static Map<String, byte[]> rebuilt() {
        var files = new LinkedHashMap<String, byte[]>();
        var base = AvifEncoder.encode(picture(192, 160, false, 4), fast().build());
        files.put("clap centered odd", regrid(base, essential(HeifBuilder.clap(171, 1, 137, 1, 0, 1, 0, 1))));
        files.put("clap offset", regrid(base, essential(HeifBuilder.clap(100, 1, 90, 1, -7, 1, 9, 2))));
        files.put("clap rotated", regrid(base, essential(HeifBuilder.clap(121, 1, 99, 1, 3, 2, -3, 1)),
                essential(HeifBuilder.irot(3))));
        return files;
    }

    static Map<String, byte[]> resources() {
        var files = new LinkedHashMap<String, byte[]>();
        for (var directory : List.of(Path.of("src", "test", "resources", "samples", "avif"),
                Path.of("src", "test", "resources", "com", "github", "auties00", "smalt", "video", "fuzz",
                        "VideoFuzzTestInputs", "avif"))) {
            try (var list = Files.list(directory)) {
                for (var file : list.filter(path -> path.toString().endsWith(".avif")).sorted().toList()) {
                    files.put(file.getFileName().toString(), Files.readAllBytes(file));
                }
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }
        return files;
    }

    static Stream<DynamicTest> cases(Map<String, byte[]> files, List<ImageDecoderOptions> optionSets) {
        return files.entrySet().stream().flatMap(entry -> optionSets.stream().map(options -> DynamicTest.dynamicTest(
                entry.getKey() + " " + options.highBitDepth() + "/" + options.convertToSrgb(), () -> {
                    var data = entry.getValue();
                    Function<ImageDecoderOptions, DecodedImage> decoder = o -> AvifDecoder.decode(MediaSource.of(data),
                            o);
                    var whole = decoder.apply(options);
                    var regions = RegionAsserts.regions(whole.sourceWidth(), whole.sourceHeight(), 8,
                            entry.getKey().hashCode());
                    RegionAsserts.assertRegions(entry.getKey(), decoder, options, regions);
                    RegionAsserts.assertOutsideRejected(decoder, options, whole.sourceWidth(), whole.sourceHeight());
                    Function<ImageDecoderOptions, DecodedImage> dispatched = o -> Images.decode(MediaSource.of(data),
                            o);
                    RegionAsserts.assertRegions(entry.getKey() + " through Images", dispatched, options,
                            regions.subList(0, 3));
                })));
    }

    private static List<ImageDecoderOptions> optionSets() {
        return List.of(ImageDecoderOptions.defaults(), WIDE, ImageDecoderOptions.builder().convertToSrgb(true).build());
    }

    @TestFactory
    @DisplayName("a region of every encoded grid equals the crop of the whole image")
    Stream<DynamicTest> encodedGrids() {
        return cases(encoded(), optionSets());
    }

    @TestFactory
    @DisplayName("a region of a grid with a clean aperture equals the crop of the whole image")
    Stream<DynamicTest> cleanApertures() {
        return cases(rebuilt(), optionSets());
    }

    @TestFactory
    @DisplayName("a region of every committed sample equals the crop of the whole image")
    Stream<DynamicTest> samples() {
        return cases(resources(), optionSets());
    }

    @Test
    @DisplayName("the AVIF provider declares that it decodes regions")
    void provider() {
        assertTrue(new com.github.auties00.smalt.internal.provider.video.AvifCodecProvider()
                .decodesRegions(com.github.auties00.smalt.image.ImageFormat.AVIF));
    }

    @Test
    @DisplayName("a region of a grid reads only the grid images it intersects")
    void readsIntersectingImages() {
        var data = AvifEncoder.encode(picture(512, 512, true, 5), fast().build());
        var source = new CountingSource(data);
        var region = new Region(300, 200, 10, 10);
        var decoded = AvifDecoder.decode(source, ImageDecoderOptions.builder().region(region).build());
        // a few of the 64 color and 64 alpha images, and the metadata
        assertTrue(source.bytes < data.length / 8, source + " of " + data.length);
        var whole = AvifDecoder.decode(MediaSource.of(data));
        assertEquals(PictureDump.picture(ImageOps.crop(whole.picture(), region)),
                PictureDump.picture(decoded.picture()));
    }

    @Test
    @DisplayName("a region of a grid larger than the limits decodes under limits that bound its grid images")
    void regionBoundsTheLimits() {
        var data = AvifEncoder.encode(picture(320, 320, false, 6), fast().build());
        var options = ImageDecoderOptions.builder().limits(MediaLimits.builder().maxPixels(200 * 200).build())
                .build();
        assertThrows(MediaException.LimitExceeded.class, () -> AvifDecoder.decode(MediaSource.of(data), options));
        var region = new Region(100, 150, 50, 40);
        var decoded = AvifDecoder.decode(MediaSource.of(data), options.toBuilder().region(region).build());
        Picture expected = ImageOps.crop(AvifDecoder.decode(MediaSource.of(data)).picture(), region);
        assertEquals(PictureDump.picture(expected), PictureDump.picture(decoded.picture()));
    }
}
