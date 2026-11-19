package com.github.auties00.smalt.image.jpeg;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.image.ops.Region;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.testkit.Tolerance;
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
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers region decoding of JPEG images: a region decode equals the crop of the whole decode for baseline,
 * progressive and arithmetic frames of every sampling layout, with and without restart markers (decoded in bands of
 * intervals or speculatively for large scans), of 8 and 12 bits, at every DCT scaling a size hint selects and under
 * every decoding option; its luma equals {@code djpeg -crop}; and the limits bound the region rather than the
 * image. Lossless and hierarchical images decode whole and are cropped.
 */
@DisplayName("JPEG region decoding")
class JpegRegionTest {
    static RgbaPicture picture(int width, int height, long seed) {
        var picture = RgbaPicture.allocate(width, height, false);
        var random = new Random(seed);
        var pixels = picture.pixels();
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var at = y * picture.stride() + x * 4;
                pixels[at] = (byte) (x * 255 / width + random.nextInt(40));
                pixels[at + 1] = (byte) (y * 255 / height + random.nextInt(40));
                pixels[at + 2] = (byte) (((x / 9) ^ (y / 7)) * 13 + random.nextInt(24));
                pixels[at + 3] = (byte) 255;
            }
        }
        return picture;
    }

    static YuvPicture gray(int width, int height, long seed) {
        var luma = new byte[width * height];
        var random = new Random(seed);
        for (var i = 0; i < luma.length; i++) {
            luma[i] = (byte) ((i % width) * 2 + (i / width) + random.nextInt(30));
        }
        return YuvPicture.builder().size(width, height).chromaFormat(ChromaFormat.MONOCHROME)
                .color(ColorInfo.JPEG).luma(luma, width).build();
    }

    static JpegEncoderOptions.Builder quality() {
        return JpegEncoderOptions.ofQuality(85).toBuilder();
    }

    static Map<String, byte[]> encoded() {
        var files = new LinkedHashMap<String, byte[]>();
        var picture = picture(203, 147, 1);
        files.put("420", JpegEncoder.encode(picture, quality().build()));
        files.put("444", JpegEncoder.encode(picture, quality().chromaFormat(ChromaFormat.YUV444).build()));
        files.put("422", JpegEncoder.encode(picture, quality().chromaFormat(ChromaFormat.YUV422).build()));
        files.put("440", JpegEncoder.encode(picture, quality().samplingFactors(JpegEncoderOptions.Sampling.of(1, 2),
                JpegEncoderOptions.Sampling.of(1, 1), JpegEncoderOptions.Sampling.of(1, 1)).build()));
        files.put("411", JpegEncoder.encode(picture, quality().samplingFactors(JpegEncoderOptions.Sampling.of(4, 1),
                JpegEncoderOptions.Sampling.of(1, 1), JpegEncoderOptions.Sampling.of(1, 1)).build()));
        files.put("gray", JpegEncoder.encode(gray(203, 147, 4), quality().build()));
        files.put("restart per row", JpegEncoder.encode(picture, quality().restartInterval(13).build()));
        files.put("restart odd", JpegEncoder.encode(picture, quality().restartInterval(5).build()));
        files.put("progressive", JpegEncoder.encode(picture, JpegEncoderOptions.ofProgressive(80)));
        files.put("progressive 444", JpegEncoder.encode(picture, JpegEncoderOptions.ofProgressive(80).toBuilder()
                .chromaFormat(ChromaFormat.YUV444).build()));
        files.put("arithmetic", JpegEncoder.encode(picture, quality().arithmetic(true).build()));
        files.put("arithmetic restart", JpegEncoder.encode(picture, quality().arithmetic(true).restartInterval(7)
                .build()));
        files.put("arithmetic progressive", JpegEncoder.encode(picture, JpegEncoderOptions.ofProgressive(80)
                .toBuilder().arithmetic(true).build()));
        files.put("12-bit", JpegEncoder.encode(picture, quality().precision(12).build()));
        files.put("12-bit progressive", JpegEncoder.encode(picture, JpegEncoderOptions.ofProgressive(80).toBuilder()
                .precision(12).build()));
        files.put("rgb", JpegEncoder.encode(picture, quality().colorSpace(JpegEncoderOptions.ColorSpace.RGB)
                .build()));
        files.put("cmyk", JpegEncoder.encode(picture, quality().colorSpace(JpegEncoderOptions.ColorSpace.CMYK)
                .build()));
        files.put("ycck", JpegEncoder.encode(picture, quality().colorSpace(JpegEncoderOptions.ColorSpace.YCCK)
                .build()));
        files.put("lossless", JpegEncoder.encode(picture, JpegEncoderOptions.ofLossless()));
        return files;
    }

    /** Scans large enough for the parallel paths: bands of restart intervals and speculative runs. */
    static Map<String, byte[]> large() {
        var files = new LinkedHashMap<String, byte[]>();
        var picture = picture(1600, 1200, 2);
        files.put("large", JpegEncoder.encode(picture, JpegEncoderOptions.ofQuality(97)));
        files.put("large restart", JpegEncoder.encode(picture, JpegEncoderOptions.ofQuality(97).toBuilder()
                .restartInterval(100).build()));
        files.put("large 444", JpegEncoder.encode(picture, JpegEncoderOptions.ofQuality(97).toBuilder()
                .chromaFormat(ChromaFormat.YUV444).build()));
        return files;
    }

    static Map<String, byte[]> resources() {
        var files = new LinkedHashMap<String, byte[]>();
        for (var directory : List.of(Path.of("src", "test", "resources", "samples", "jpeg"),
                Path.of("src", "test", "resources", "com", "github", "auties00", "smalt", "image", "jpeg"))) {
            if (!Files.isDirectory(directory)) {
                continue;
            }
            try (var walk = Files.walk(directory)) {
                for (var file : walk.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".jpg"))
                        .sorted().toList()) {
                    files.put(directory.relativize(file).toString(), Files.readAllBytes(file));
                }
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }
        return files;
    }

    static Stream<DynamicTest> cases(Map<String, byte[]> files, List<ImageDecoderOptions> optionSets, int random) {
        return files.entrySet().stream().flatMap(entry -> optionSets.stream().map(options -> DynamicTest.dynamicTest(
                entry.getKey() + " " + options, () -> {
                    var data = entry.getValue();
                    Function<ImageDecoderOptions, DecodedImage> decoder = o -> JpegDecoder.decode(MediaSource.of(data),
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
                    var regions = RegionAsserts.regions(whole.sourceWidth(), whole.sourceHeight(), random,
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
                ImageDecoderOptions.builder().minSize(60, 40).build(),
                ImageDecoderOptions.builder().minSize(10, 10).build(),
                ImageDecoderOptions.builder().minSize(1, 1).build());
    }

    @TestFactory
    @DisplayName("a region of every encoded layout equals the crop of the whole image")
    Stream<DynamicTest> encodedLayouts() {
        return cases(encoded(), optionSets(), 10);
    }

    @TestFactory
    @DisplayName("a region of a large scan decoded in parallel equals the crop of the whole image")
    Stream<DynamicTest> largeScans() {
        return cases(large(), List.of(ImageDecoderOptions.defaults(), ImageDecoderOptions.builder().minSize(300, 200)
                .build()), 4);
    }

    @TestFactory
    @DisplayName("a region of every committed JPEG file equals the crop of the whole image")
    Stream<DynamicTest> samples() {
        return cases(resources(), List.of(ImageDecoderOptions.defaults()), 3);
    }

    @TestFactory
    @DisplayName("a region dispatched through Images equals the crop of the whole image")
    Stream<DynamicTest> dispatched() {
        return encoded().entrySet().stream().limit(3).map(entry -> DynamicTest.dynamicTest(entry.getKey(), () -> {
            var data = entry.getValue();
            Function<ImageDecoderOptions, DecodedImage> decoder = o -> Images.decode(MediaSource.of(data), o);
            RegionAsserts.assertRegions(entry.getKey(), decoder, ImageDecoderOptions.defaults(),
                    RegionAsserts.regions(203, 147, 4, 7));
        }));
    }

    @TestFactory
    @DisplayName("the luma of a region equals djpeg -crop")
    Stream<DynamicTest> djpegCrop() {
        var files = new LinkedHashMap<String, byte[]>();
        var encoded = encoded();
        for (var name : List.of("420", "444", "422", "gray", "restart per row", "progressive", "arithmetic")) {
            files.put(name, encoded.get(name));
        }
        files.putAll(large());
        return files.entrySet().stream().map(entry -> DynamicTest.dynamicTest(entry.getKey(), () -> {
            Oracle.assume("djpeg");
            var data = entry.getValue();
            var input = Scratch.directory("jpeg-region").resolve(entry.getKey().replace(' ', '-') + ".jpg");
            Files.write(input, data);
            var whole = JpegDecoder.decode(MediaSource.of(data));
            for (var region : RegionAsserts.regions(whole.sourceWidth(), whole.sourceHeight(), 4, 3).subList(1, 10)) {
                var pgm = Oracle.run(Oracle.Tool.DJPEG, Duration.ofMinutes(1), List.of("-dct", "int",
                        "-grayscale", "-pnm", "-crop", region.width() + "x" + region.height() + "+" + region.x() + "+"
                                + region.y(), input.toAbsolutePath().toString()));
                var parsed = JpegTestSupport.parsePnm(pgm);
                // djpeg moves the left edge down to an iMCU boundary and widens the crop by as much
                var left = region.x() + region.width() - parsed.width();
                var expanded = new Region(left, region.y(), parsed.width(), region.height());
                var decoded = JpegDecoder.decode(MediaSource.of(data), ImageDecoderOptions.builder().region(expanded)
                        .build());
                var luma = (YuvPicture) decoded.picture();
                Tolerance.exact().assertWithin(entry.getKey() + " " + expanded,
                        JpegTestSupport.plane(parsed.pixels(), parsed.width(), parsed.width(), region.height()),
                        JpegTestSupport.luma(luma));
            }
        }));
    }

    @Test
    @DisplayName("a region of an image larger than the limits decodes under limits that bound the region")
    void regionBoundsTheLimits() {
        var data = JpegEncoder.encode(picture(1000, 800, 3), JpegEncoderOptions.ofQuality(80));
        var options = ImageDecoderOptions.builder().limits(MediaLimits.builder().maxPixels(300 * 300).build())
                .build();
        assertThrows(MediaException.LimitExceeded.class, () -> JpegDecoder.decode(MediaSource.of(data), options));
        var region = new Region(500, 400, 200, 150);
        var decoded = JpegDecoder.decode(MediaSource.of(data), options.toBuilder().region(region).build());
        var whole = JpegDecoder.decode(MediaSource.of(data));
        assertEquals(PictureDump.picture(ImageOps.crop(whole.picture(), region)),
                PictureDump.picture(decoded.picture()));
        assertEquals(1000, decoded.sourceWidth());
        // a progressive frame keeps the coefficients of the whole frame, so the limits bound the whole frame
        var progressive = JpegEncoder.encode(picture(1000, 800, 3), JpegEncoderOptions.ofProgressive(80));
        assertThrows(MediaException.LimitExceeded.class, () -> JpegDecoder.decode(MediaSource.of(progressive),
                options.toBuilder().region(region).build()));
    }
}
