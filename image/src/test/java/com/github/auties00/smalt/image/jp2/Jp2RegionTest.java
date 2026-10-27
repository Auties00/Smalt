package com.github.auties00.smalt.image.jp2;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.image.ops.Region;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Tolerance;
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
import java.util.Optional;
import java.util.Random;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers region decoding of JPEG 2000 images: a region decode equals the crop of the whole decode for tiled and
 * untiled codestreams of the 5-3 and 9-7 wavelets, high-throughput code-blocks, subsampled components, image and
 * tile origins, regions of interest and tile-part divisions, at every reduction a size hint selects and under every
 * decoding option; the channels of an area equal those of the whole decode and those {@code opj_decompress -d}
 * decodes; only the tiles a region needs are read; and the limits bound the region rather than the image.
 */
@DisplayName("JPEG 2000 region decoding")
class Jp2RegionTest {
    static RgbaPicture picture(int width, int height, boolean alpha, long seed) {
        var picture = RgbaPicture.allocate(width, height, alpha);
        var random = new Random(seed);
        var pixels = picture.pixels();
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var at = y * picture.stride() + x * 4;
                pixels[at] = (byte) (x * 255 / width + random.nextInt(16));
                pixels[at + 1] = (byte) (y * 255 / height + random.nextInt(16));
                pixels[at + 2] = (byte) ((x * y) / 7 + random.nextInt(64));
                pixels[at + 3] = alpha ? (byte) (x * 3 + y * 5) : (byte) 255;
            }
        }
        return picture;
    }

    /** A Y'CbCr image with half-resolution chroma, its origin at an odd position. */
    static Jp2Image subsampled(int width, int height, long x0, long y0, int precision, long seed) {
        var random = new Random(seed);
        var channels = new ArrayList<Jp2Channel>();
        for (var c = 0; c < 3; c++) {
            var d = c == 0 ? 1 : 2;
            var cx0 = Math.ceilDiv(x0, d);
            var cy0 = Math.ceilDiv(y0, d);
            var w = (int) (Math.ceilDiv(x0 + width, d) - cx0);
            var h = (int) (Math.ceilDiv(y0 + height, d) - cy0);
            var samples = new int[w * h];
            for (var i = 0; i < samples.length; i++) {
                samples[i] = (int) ((i % w) * ((1L << precision) - 1) / w + random.nextInt(8)) & ((1 << precision) - 1);
            }
            channels.add(new Jp2Channel(cx0, cy0, w, h, d, d, precision, false, Jp2ChannelType.COLOR, c + 1,
                    samples));
        }
        return new Jp2Image(x0, y0, width, height, width, height, 0, channels, Jp2ColorSpace.SYCC, Optional.empty(),
                Optional.empty(), Optional.empty());
    }

    static Map<String, byte[]> encoded() {
        var files = new LinkedHashMap<String, byte[]>();
        var rgb = picture(203, 147, false, 1);
        var rgba = picture(130, 98, true, 2);
        files.put("lossless tiles", Jp2Encoder.encode(rgb, Jp2EncoderOptions.lossless().toBuilder().tileSize(64, 64)
                .build()));
        files.put("lossy tiles", Jp2Encoder.encode(rgb, Jp2EncoderOptions.lossy(12).toBuilder().tileSize(64, 48)
                .build()));
        files.put("lossless untiled", Jp2Encoder.encode(rgb, Jp2EncoderOptions.lossless().toBuilder().noTiles()
                .decompositionLevels(5).codeBlockSize(16, 16).build()));
        files.put("lossy untiled precincts", Jp2Encoder.encode(rgb, Jp2EncoderOptions.lossy(8).toBuilder().noTiles()
                .decompositionLevels(4).codeBlockSize(32, 16).precinctSize(64, 64).build()));
        files.put("high throughput", Jp2Encoder.encode(rgb, Jp2EncoderOptions.lossless().toBuilder()
                .highThroughput(true).tileSize(96, 80).build()));
        files.put("origins", Jp2Encoder.encode(rgb, Jp2EncoderOptions.lossy(6).toBuilder().imageOrigin(13, 7)
                .tileOrigin(5, 3).tileSize(48, 40).build()));
        files.put("alpha", Jp2Encoder.encode(rgba, Jp2EncoderOptions.lossless().toBuilder().tileSize(32, 32).build()));
        files.put("raw codestream", Jp2Encoder.encode(rgb, Jp2EncoderOptions.lossy(10).toBuilder()
                .format(Jp2Format.CODESTREAM).tileSize(50, 50).build()));
        files.put("tile parts", Jp2Encoder.encode(rgb, Jp2EncoderOptions.lossless().toBuilder().tileSize(64, 64)
                .tilePartDivision(Jp2TilePartDivision.RESOLUTION).build()));
        files.put("subsampled", Jp2Encoder.encode(subsampled(141, 97, 3, 5, 8, 3), Jp2EncoderOptions.lossless()
                .toBuilder().tileSize(40, 32).build()));
        files.put("subsampled 12-bit lossy", Jp2Encoder.encode(subsampled(120, 90, 0, 1, 12, 4),
                Jp2EncoderOptions.lossy(9).toBuilder().noTiles().build()));
        return files;
    }

    static Map<String, byte[]> resources() {
        var files = new LinkedHashMap<String, byte[]>();
        for (var directory : List.of(Path.of("src", "test", "resources", "samples", "jp2"),
                Path.of("src", "test", "resources", "com", "github", "auties00", "smalt", "image", "fuzz"))) {
            if (!Files.isDirectory(directory)) {
                continue;
            }
            try (var walk = Files.walk(directory)) {
                for (var file : walk.filter(Files::isRegularFile).filter(path -> path.toString().matches(
                        ".*\\.(jp2|j2k|j2c|jpx|jph)")).sorted().toList()) {
                    files.put(directory.relativize(file).toString(), Files.readAllBytes(file));
                }
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }
        return files;
    }

    static Stream<DynamicTest> cases(Map<String, byte[]> files, List<ImageDecoderOptions> optionSets) {
        return files.entrySet().stream().flatMap(entry -> optionSets.stream().map(options -> DynamicTest.dynamicTest(
                entry.getKey() + " " + options, () -> {
                    var data = entry.getValue();
                    Function<ImageDecoderOptions, DecodedImage> decoder = o -> Jp2Decoder.decode(MediaSource.of(data),
                            o);
                    DecodedImage whole;
                    try {
                        whole = decoder.apply(options.toBuilder().fullSize().build());
                    } catch (MediaException failure) {
                        // an image that does not decode whole fails the same way for a region of it
                        assertEquals(failure.getClass(), assertThrows(MediaException.class,
                                () -> decoder.apply(options.toBuilder().region(0, 0, 1, 1).build())).getClass());
                        return;
                    }
                    var regions = RegionAsserts.regions(whole.sourceWidth(), whole.sourceHeight(), 8,
                            entry.getKey().hashCode());
                    RegionAsserts.assertRegions(entry.getKey(), decoder, options, regions);
                    RegionAsserts.assertOutsideRejected(decoder, options.toBuilder().fullSize().build(),
                            whole.sourceWidth(), whole.sourceHeight());
                })));
    }

    static List<ImageDecoderOptions> optionSets() {
        return List.of(ImageDecoderOptions.defaults(), ImageDecoderOptions.builder().highBitDepth(true).build(),
                ImageDecoderOptions.builder().convertToSrgb(true).build(),
                ImageDecoderOptions.builder().keepChannels(true).build(),
                ImageDecoderOptions.builder().minSize(20, 15).build(),
                ImageDecoderOptions.builder().minSize(4, 4).highBitDepth(true).build());
    }

    @TestFactory
    @DisplayName("a region of every encoded layout equals the crop of the whole image")
    Stream<DynamicTest> encodedLayouts() {
        return cases(encoded(), optionSets());
    }

    @TestFactory
    @DisplayName("a region of every committed sample equals the crop of the whole image")
    Stream<DynamicTest> samples() {
        return cases(resources(), List.of(ImageDecoderOptions.defaults(),
                ImageDecoderOptions.builder().minSize(8, 8).build()));
    }

    @TestFactory
    @DisplayName("a region dispatched through Images equals the crop of the whole image")
    Stream<DynamicTest> dispatched() {
        return encoded().entrySet().stream().limit(3).map(entry -> DynamicTest.dynamicTest(entry.getKey(), () -> {
            var data = entry.getValue();
            Function<ImageDecoderOptions, DecodedImage> decoder = o -> Images.decode(MediaSource.of(data), o);
            var whole = decoder.apply(ImageDecoderOptions.defaults());
            RegionAsserts.assertRegions(entry.getKey(), decoder, ImageDecoderOptions.defaults(),
                    RegionAsserts.regions(whole.sourceWidth(), whole.sourceHeight(), 4, 9));
        }));
    }

    /** The channels of the whole decode cut to the area the options describe, computed here. */
    static Jp2Image expectedChannels(Jp2Image whole, Region area) {
        var x0 = whole.x0() + area.x();
        var y0 = whole.y0() + area.y();
        var x1 = x0 + area.width();
        var y1 = y0 + area.height();
        var channels = new ArrayList<Jp2Channel>();
        for (var channel : whole.channels()) {
            var dx = channel.subsamplingX();
            var dy = channel.subsamplingY();
            // a pixel takes the sample floor(X / d) of the channel, clamped to it
            var cx0 = Math.clamp(Math.floorDiv(x0, dx), channel.x0(), channel.x0() + channel.width() - 1);
            var cy0 = Math.clamp(Math.floorDiv(y0, dy), channel.y0(), channel.y0() + channel.height() - 1);
            var cx1 = Math.clamp(Math.floorDiv(x1 - 1, dx) + 1, cx0 + 1, channel.x0() + channel.width());
            var cy1 = Math.clamp(Math.floorDiv(y1 - 1, dy) + 1, cy0 + 1, channel.y0() + channel.height());
            var w = (int) (cx1 - cx0);
            var h = (int) (cy1 - cy0);
            var samples = new int[w * h];
            for (var y = 0; y < h; y++) {
                for (var x = 0; x < w; x++) {
                    samples[y * w + x] = channel.samples()[(int) ((cy0 - channel.y0() + y) * channel.width()
                            + cx0 - channel.x0() + x)];
                }
            }
            channels.add(new Jp2Channel(cx0, cy0, w, h, dx, dy, channel.precision(), channel.signed(),
                    channel.type(), channel.association(), samples));
        }
        return new Jp2Image(x0, y0, area.width(), area.height(), whole.fullWidth(), whole.fullHeight(),
                whole.reduction(), channels, whole.colorSpace(), whole.iccProfile(), whole.exif(), whole.xmp());
    }

    static void assertSameChannels(Jp2Image expected, Jp2Image actual, String what) {
        assertEquals(expected.x0(), actual.x0(), what + " x0");
        assertEquals(expected.y0(), actual.y0(), what + " y0");
        assertEquals(expected.width(), actual.width(), what + " width");
        assertEquals(expected.height(), actual.height(), what + " height");
        assertEquals(expected.channels().size(), actual.channels().size(), what + " channels");
        for (var c = 0; c < expected.channels().size(); c++) {
            var e = expected.channels().get(c);
            var a = actual.channels().get(c);
            assertEquals(e.x0(), a.x0(), what + " channel " + c + " x0");
            assertEquals(e.y0(), a.y0(), what + " channel " + c + " y0");
            assertEquals(e.width(), a.width(), what + " channel " + c + " width");
            assertEquals(e.height(), a.height(), what + " channel " + c + " height");
            assertArrayEquals(e.samples(), a.samples(), what + " channel " + c);
        }
    }

    @TestFactory
    @DisplayName("the channels of an area equal those of the whole decode, at every reduction")
    Stream<DynamicTest> channels() {
        return encoded().entrySet().stream().map(entry -> DynamicTest.dynamicTest(entry.getKey(), () -> {
            var data = entry.getValue();
            for (var reduction = 0; reduction <= 3; reduction++) {
                var options = Jp2DecoderOptions.builder().reduction(reduction).build();
                var whole = Jp2Decoder.decodeChannels(MediaSource.of(data), options);
                for (var region : RegionAsserts.regions(whole.fullWidth(), whole.fullHeight(), 6, reduction)) {
                    var mapped = RegionAsserts.map(region, whole.fullWidth(), whole.fullHeight(), whole.width(),
                            whole.height());
                    var actual = Jp2Decoder.decodeChannels(MediaSource.of(data),
                            options.toBuilder().region(region).build());
                    assertSameChannels(expectedChannels(whole, mapped), actual, entry.getKey() + " r" + reduction
                            + " " + region);
                }
            }
        }));
    }

    @TestFactory
    @DisplayName("the channels of an area equal those opj_decompress -d decodes")
    Stream<DynamicTest> openJpegArea() {
        return encoded().entrySet().stream().filter(entry -> !entry.getKey().startsWith("subsampled"))
                .map(entry -> DynamicTest.dynamicTest(entry.getKey(), () -> {
                    OpenJpeg.assume("opj_decompress");
                    var data = entry.getValue();
                    var whole = Jp2Decoder.decodeChannels(MediaSource.of(data));
                    var extension = data[0] == (byte) 0xFF ? "j2k" : "jp2";
                    // opj_decompress 2.5.3 decodes an area of high-throughput code-blocks wrongly (mid-gray where
                    // code-blocks it skips are needed), so those are compared with its whole decode
                    var highThroughput = entry.getKey().equals("high throughput");
                    var full = highThroughput ? OpenJpeg.decode(data, extension, 0, 0) : null;
                    for (var region : RegionAsserts.regions(whole.width(), whole.height(), 3, 11).subList(4, 9)) {
                        var actual = Jp2Decoder.decodeChannels(MediaSource.of(data),
                                Jp2DecoderOptions.builder().region(region).build());
                        var planes = highThroughput ? cropPlanes(full, region)
                                : area(data, extension, whole.x0() + region.x(), whole.y0() + region.y(),
                                whole.x0() + region.x() + region.width(), whole.y0() + region.y() + region.height());
                        assertNotNull(planes, "opj_decompress rejected " + entry.getKey());
                        assertEquals(actual.channels().size(), planes.length, entry.getKey());
                        for (var c = 0; c < planes.length; c++) {
                            var channel = actual.channels().get(c);
                            assertEquals(channel.width(), planes[c].width(), entry.getKey() + " " + region);
                            assertEquals(channel.height(), planes[c].height(), entry.getKey() + " " + region);
                            Tolerance.exact().assertWithin(entry.getKey() + " " + region + " channel " + c,
                                    planes[c].samples(), channel.samples());
                        }
                    }
                }));
    }

    /** Crops whole planes of components without subsampling to a rectangle. */
    static Jp2Testing.Plane[] cropPlanes(Jp2Testing.Plane[] planes, Region region) {
        var result = new Jp2Testing.Plane[planes.length];
        for (var c = 0; c < planes.length; c++) {
            var plane = planes[c];
            var samples = new int[region.width() * region.height()];
            for (var y = 0; y < region.height(); y++) {
                System.arraycopy(plane.samples(), (region.y() + y) * plane.width() + region.x(), samples,
                        y * region.width(), region.width());
            }
            result[c] = new Jp2Testing.Plane(region.width(), region.height(), plane.depth(), plane.signed(), samples);
        }
        return result;
    }

    /** Decodes an area with {@code opj_decompress -d}, one plane per component. */
    static Jp2Testing.Plane[] area(byte[] data, String extension, long x0, long y0, long x1, long y1) {
        try {
            var input = OpenJpeg.temp("area." + extension);
            Files.write(input, data);
            var output = OpenJpeg.temp("area.pgx");
            if (OpenJpeg.run("opj_decompress", List.of("-i", input.toString(), "-o", output.toString(), "-d",
                    x0 + "," + y0 + "," + x1 + "," + y1)) == null) {
                return null;
            }
            var base = output.toString().substring(0, output.toString().length() - 4);
            var planes = new ArrayList<Jp2Testing.Plane>();
            for (var c = 0; ; c++) {
                var file = Path.of(base + "_" + c + ".pgx");
                if (!Files.exists(file)) {
                    if (c == 0 && Files.exists(output)) {
                        planes.add(Jp2Testing.pgx(Files.readAllBytes(output)));
                    }
                    break;
                }
                planes.add(Jp2Testing.pgx(Files.readAllBytes(file)));
            }
            return planes.toArray(Jp2Testing.Plane[]::new);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    @Test
    @DisplayName("a region of a tiled image reads only the tiles it needs")
    void readsNeededTiles() {
        var picture = picture(1024, 768, false, 5);
        for (var format : Jp2Format.values()) {
            var data = Jp2Encoder.encode(picture, Jp2EncoderOptions.lossless().toBuilder().format(format)
                    .tileSize(128, 128).build());
            var source = new CountingSource(data);
            var region = new Region(500, 400, 8, 8);
            var decoded = Jp2Decoder.decode(source, ImageDecoderOptions.builder().region(region).build());
            // one or two of the 48 tiles, and the headers of every tile-part
            assertTrue(source.bytes < data.length / 12, format + ": " + source + " of " + data.length);
            var whole = Jp2Decoder.decode(MediaSource.of(data));
            assertEquals(PictureDump.picture(ImageOps.crop(whole.picture(), region)),
                    PictureDump.picture(decoded.picture()), format.toString());
            var channels = new CountingSource(data);
            Jp2Decoder.decodeChannels(channels, Jp2DecoderOptions.builder().region(region).build());
            assertTrue(channels.bytes < data.length / 12, format + ": " + channels);
        }
    }

    @Test
    @DisplayName("a region of an image larger than the limits decodes under limits that bound the region")
    void regionBoundsTheLimits() {
        var data = Jp2Encoder.encode(picture(900, 700, false, 6), Jp2EncoderOptions.lossy(10).toBuilder()
                .tileSize(128, 128).build());
        var options = ImageDecoderOptions.builder().limits(MediaLimits.builder().maxPixels(300 * 300).build())
                .build();
        assertThrows(MediaException.LimitExceeded.class, () -> Jp2Decoder.decode(MediaSource.of(data), options));
        var region = new Region(400, 300, 200, 150);
        var decoded = Jp2Decoder.decode(MediaSource.of(data), options.toBuilder().region(region).build());
        var whole = Jp2Decoder.decode(MediaSource.of(data));
        assertEquals(PictureDump.picture(ImageOps.crop(whole.picture(), region)),
                PictureDump.picture(decoded.picture()));
        assertEquals(900, decoded.sourceWidth());
        // the one tile of an untiled codestream decodes whole, so the limits bound it as they bound the image
        var untiled = Jp2Encoder.encode(picture(900, 700, false, 6), Jp2EncoderOptions.lossy(10).toBuilder()
                .noTiles().build());
        assertThrows(MediaException.LimitExceeded.class, () -> Jp2Decoder.decode(MediaSource.of(untiled),
                options.toBuilder().region(region).build()));
        assertThrows(MediaException.LimitExceeded.class, () -> Jp2Decoder.decodeChannels(MediaSource.of(untiled),
                Jp2DecoderOptions.builder().limits(options.limits()).region(region).build()));
    }

    @Test
    @DisplayName("an area outside the image is rejected")
    void outside() {
        var data = encoded().get("lossless tiles");
        assertThrows(IllegalArgumentException.class, () -> Jp2Decoder.decodeChannels(MediaSource.of(data),
                Jp2DecoderOptions.builder().region(200, 0, 4, 4).build()));
        var options = Jp2DecoderOptions.builder().region(3, 4, 5, 6).build();
        assertEquals(Optional.of(new Region(3, 4, 5, 6)), options.region());
        assertEquals(Jp2DecoderOptions.defaults(), options.toBuilder().wholeImage().build());
        assertEquals(options, options.toBuilder().build());
        assertEquals(options.hashCode(), options.toBuilder().build().hashCode());
    }
}
