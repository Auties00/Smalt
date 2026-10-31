package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.image.ops.ResizeFilter;
import com.github.auties00.smalt.image.raw.CfaLayout;
import com.github.auties00.smalt.image.raw.RawDecoder;
import com.github.auties00.smalt.image.raw.RawDecoderOptions;
import com.github.auties00.smalt.image.raw.RawSamples;
import com.github.auties00.smalt.image.raw.RawWhiteBalance;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.testkit.Skip;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the DNG layouts and features Adobe DNG Converter 18 writes against its own files, as a black-box
 * oracle: its DNG 1.7 files with lossless JPEG XL sensor data stored as interleaved subimages (decoded to the
 * samples of its uncompressed file of the same sample), their JPEG XL previews and fast load linear raw previews,
 * the staggered photosites of the Fujifilm SuperCCD (rendered like the converter's preview), and the four-color
 * Canon PowerShot G1 CRW and Nikon E5700 NEF files, whose cameras have no color matrices, decoded raw, rendered by
 * default with the fitted matrix and rendered with the converter's matrix. The oracle files are those {@code tools/testdata/raw-oracle.py} writes into
 * {@code <conformance cache>/raw-oracle} ({@code -Dsmalt.test.rawOracle} overrides the directory); the tests are
 * skipped without them.
 */
class DngSpecOracleTest {
    private static final Path ORACLE = Path.of(System.getProperty("smalt.test.rawOracle",
            Conformance.directory().resolve("raw-oracle").toString()));

    private static final int GRID_WIDTH = 48;

    private static final int GRID_HEIGHT = 32;

    private static Path oracle(String stem) {
        var path = ORACLE.resolve(stem + ".dng");
        Skip.unless(Files.isRegularFile(path), "data:raw-oracle", "no oracle file " + path + "; run tools/testdata/raw-oracle.py");
        return path;
    }

    private static SensorPlane plane(Path dng) {
        try (var source = MediaSource.of(dng)) {
            var file = RawParser.parse(source, MediaLimits.DEFAULT);
            assertTrue(file.sensor != null, file.unsupported);
            var plane = SensorPlane.of(file.sensor, MediaLimits.DEFAULT);
            file.sensor.reader.read(plane, MediaLimits.DEFAULT);
            return plane;
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"rawpixls-canon-eos-10d-raw-3-2", "rawpixls-canon-powershot-g1-raw-4-3",
            "rawpixls-fujifilm-finepix-s6500fd-4-3"})
    @DisplayName("JPEG XL sensor data stored as interleaved subimages equals the converter's uncompressed samples")
    void interleavedJpegXl(String stem) {
        var jxl = oracle(stem + "-jxl");
        var uncompressed = oracle(stem);
        var ours = plane(jxl);
        var theirs = plane(uncompressed);
        assertEquals(theirs.width, ours.width);
        assertEquals(theirs.height, ours.height);
        assertArrayEquals(theirs.integers, ours.integers);
        try (var a = MediaSource.of(jxl); var b = MediaSource.of(uncompressed)) {
            var fromJxl = RawDecoder.decodeRaw(a);
            var fromUncompressed = RawDecoder.decodeRaw(b);
            assertArrayEquals(fromUncompressed.samples(), fromJxl.samples());
            assertEquals(fromUncompressed.cfaPattern(), fromJxl.cfaPattern());
            assertEquals(fromUncompressed.cfaLayout(), fromJxl.cfaLayout());
        }
    }

    @Test
    @DisplayName("a size hint is met from a JPEG XL preview, which shows what the converter's JPEG preview shows")
    void jpegXlPreview() {
        var jxl = oracle("rawpixls-canon-eos-10d-raw-3-2-jxl");
        var uncompressed = oracle("rawpixls-canon-eos-10d-raw-3-2");
        try (var source = MediaSource.of(jxl); var other = MediaSource.of(uncompressed)) {
            var info = RawDecoder.info(source);
            assertTrue(info.previews().stream().anyMatch(preview -> preview.format() == ImageFormat.JXL), info.previews().toString());
            // the only JPEG preview left is the thumbnail of the first directory
            var thumbnail = Images.decode(MediaSource.of(RawDecoder.extractPreview(source).orElseThrow())).picture();
            assertEquals(256, thumbnail.width());
            var decoded = RawDecoder.decode(source, RawDecoderOptions.ofMinimumSize(900, 600)).picture();
            assertEquals(1024, decoded.width());
            assertEquals(683, decoded.height());
            var preview = Images.decode(MediaSource.of(RawDecoder.extractPreview(other).orElseThrow())).picture();
            var ours = grid(decoded);
            var theirs = grid(preview);
            var report = new StringBuilder();
            for (var channel = 0; channel < 3; channel++) {
                report.append(" channel ").append(channel).append(": correlation ")
                        .append(correlation(ours[channel], theirs[channel])).append(", means ").append(mean(ours[channel]))
                        .append(" and ").append(mean(theirs[channel]));
            }
            for (var channel = 0; channel < 3; channel++) {
                assertTrue(correlation(ours[channel], theirs[channel]) > 0.98, report.toString());
                assertEquals(mean(theirs[channel]), mean(ours[channel]), 6, report.toString());
            }
            // the preview decodes as libjxl decodes its codestream
            if (Oracle.find(Oracle.Tool.DJXL).isPresent()) {
                var file = RawParser.parse(source, MediaLimits.DEFAULT);
                var stored = file.previews.stream().filter(p -> p.image() != null && p.width() == 1024).findFirst().orElseThrow();
                var codestream = new byte[(int) stored.length()];
                source.readFully(stored.offset(), codestream, 0, codestream.length);
                var directory = Scratch.directory("dng-spec-oracle");
                var input = directory.resolve("preview.jxl");
                var output = directory.resolve("preview.png");
                try {
                    Files.write(input, codestream);
                } catch (java.io.IOException exception) {
                    throw new java.io.UncheckedIOException(exception);
                }
                Oracle.run(Oracle.Tool.DJXL, java.time.Duration.ofMinutes(2), List.of(input.toString(), output.toString(), "--quiet"));
                var reference = ImageOps.toRgba(Images.decode(MediaSource.of(output)).picture());
                var actual = ImageOps.toRgba(decoded);
                var worst = 0;
                for (var y = 0; y < 683; y++) {
                    for (var x = 0; x < 1024; x++) {
                        for (var c = 0; c < 3; c++) {
                            var difference = Math.abs((reference.pixels()[y * reference.stride() + x * 4 + c] & 0xFF)
                                    - (actual.pixels()[y * actual.stride() + x * 4 + c] & 0xFF));
                            worst = Math.max(worst, difference);
                        }
                    }
                }
                assertTrue(worst <= 3, "the largest difference from djxl is " + worst);
            }
        }
    }

    @Test
    @DisplayName("a size hint is met by rendering a fast load linear raw preview, which renders like the raw image")
    void linearRawPreview() {
        var jxl = oracle("rawpixls-canon-eos-10d-raw-3-2-jxl");
        try (var source = MediaSource.of(jxl)) {
            // the half-size rendering (1536 by 1024) is too small, the full rendering (3072 by 2048) larger than the preview
            var options = RawDecoderOptions.builder().minSize(1600, 1100).embeddedPreview(false).build();
            var hinted = RawDecoder.decode(source, options);
            assertEquals(2048, hinted.picture().width());
            assertEquals(1365, hinted.picture().height());
            var info = RawDecoder.info(source);
            assertEquals(info.width(), hinted.sourceWidth());
            var full = RawDecoder.decode(source, options.toBuilder().fullSize().build()).picture();
            var ours = grid(hinted.picture());
            var reference = grid(full);
            for (var channel = 0; channel < 3; channel++) {
                assertTrue(correlation(ours[channel], reference[channel]) > 0.99, "channel " + channel);
                assertEquals(mean(reference[channel]), mean(ours[channel]), 6, "the mean of channel " + channel);
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"rawpixls-fujifilm-finepix-s2pro-3-2", "rawpixls-fujifilm-finepix-s6500fd-4-3"})
    @DisplayName("a SuperCCD file of staggered photosites renders like the converter's preview")
    void staggered(String stem) {
        var dng = oracle(stem);
        try (var source = MediaSource.of(dng)) {
            var info = RawDecoder.info(source);
            assertEquals(CfaLayout.STAGGERED_A, info.cfaLayout());
            assertTrue(info.isDecodable(), info.unsupportedFeature().orElse(""));
            var preview = Images.decode(MediaSource.of(RawDecoder.extractPreview(source).orElseThrow())).picture();
            var rendered = RawDecoder.decode(source).picture();
            assertEquals((double) preview.width() / preview.height(), (double) rendered.width() / rendered.height(), 0.01);
            var ours = grid(rendered);
            var theirs = grid(preview);
            assertTrue(correlation(ours[1], theirs[1]) > 0.98, "structure " + correlation(ours[1], theirs[1]));
            for (var channel = 0; channel < 3; channel += 2) {
                assertEquals(mean(theirs[channel]) / mean(theirs[1]), mean(ours[channel]) / mean(ours[1]), 0.1,
                        "the balance of channel " + channel + " to green");
            }
            var raw = RawDecoder.decodeRaw(source);
            assertEquals(CfaLayout.STAGGERED_A.shifted(0, 0), CfaLayout.STAGGERED_A);
            assertTrue(raw.cfaLayout().isStaggered());
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"rawpixls-fujifilm-finepix-s2pro-3-2", "rawpixls-fujifilm-finepix-s6500fd-4-3"})
    @DisplayName("the staggered rendering matches the preview better with the layout's half-row offsets than without")
    void staggeredOffsets(String stem) {
        var dng = oracle(stem);
        try (var source = MediaSource.of(dng)) {
            var preview = Images.decode(MediaSource.of(RawDecoder.extractPreview(source).orElseThrow())).picture();
            var file = RawParser.parse(source, MediaLimits.DEFAULT);
            var options = RawDecoderOptions.defaults();
            var staggered = Renderer.render(file, file.sensor, options, false);
            file.sensor.layout = CfaLayout.STAGGERED_B;
            var flipped = Renderer.render(file, file.sensor, options, false);
            var size = new int[]{preview.width(), preview.height()};
            var right = detailCorrelation(staggered, preview, size);
            var wrong = detailCorrelation(flipped, preview, size);
            assertTrue(right > wrong, "the layout's offsets " + right + ", the opposite offsets " + wrong);
        }
    }

    @ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.EnumSource(value = RawSamples.class, names = {"CRW_G1", "NEF_E5700"})
    @DisplayName("a four-color file without color matrices decodes raw like the converter's file, renders by default near its preview and with its matrix and multipliers as it does")
    void fourColorWithoutMatrices(RawSamples sample) {
        var name = sample.fileName();
        var dng = oracle(name.substring(0, name.lastIndexOf('.')));
        try (var vendor = MediaSource.of(sample.fetch()); var oracle = MediaSource.of(dng)) {
            var info = RawDecoder.info(vendor);
            assertTrue(info.unsupportedFeature().isEmpty(), info.unsupportedFeature().orElse(""));
            assertTrue(info.isColorCalibrated());
            var raw = RawDecoder.decodeRaw(vendor);
            assertEquals(4, raw.cameraNeutral().length);
            assertEquals(12, raw.balancedToXyz().length);
            var theirs = RawDecoder.decodeRaw(oracle);
            var best = sampleOffset(raw.samples(), raw.width(), raw.height(), theirs.samples(), theirs.width(),
                    theirs.height());
            assertTrue(best[2] > 0.999, "the raw samples correlate with the converter's with " + best[2]
                    + " at offset " + best[0] + ", " + best[1]);
            // where the converter's image starts within ours, our pattern shifted there is the converter's
            assertEquals(theirs.cfaPattern().orElseThrow(), raw.cfaPattern().orElseThrow().shifted(
                    Math.floorMod((int) best[0], 2), Math.floorMod((int) best[1], 2)), "at offset " + best[0] + ", "
                    + best[1] + " (correlation " + best[2] + ")");
            var color = RawParser.parse(oracle, MediaLimits.DEFAULT).color;
            var matrix = color.colorMatrix[1] != null ? color.colorMatrix[1] : color.colorMatrix[0];
            assertEquals(12, matrix.length);
            var withMatrix = RawDecoder.decodeRaw(vendor, RawDecoderOptions.builder().colorMatrix(matrix).build());
            assertEquals(12, withMatrix.balancedToXyz().length);
            // the vendor files carry no four-color white balance and no baseline exposure: the converter's as-shot
            // neutral gives the multipliers, its baseline exposure the exposure
            var neutral = theirs.cameraNeutral();
            var factors = new double[4];
            for (var index = 0; index < 4; index++) {
                factors[index] = 1 / neutral[index];
            }
            var options = RawDecoderOptions.builder().colorMatrix(matrix)
                    .whiteBalance(RawWhiteBalance.multipliers(factors)).exposure(color.baselineExposure).build();
            var full = RawDecoder.decode(vendor, options).picture();
            // the area of our rendering that the converter's covers
            var ourPicture = ImageOps.crop(full, Math.clamp((long) best[1], 0, full.width() - theirs.width()),
                    Math.clamp((long) best[0], 0, full.height() - theirs.height()), theirs.width(), theirs.height());
            var theirPicture = Images.decode(MediaSource.of(RawDecoder.extractPreview(oracle).orElseThrow())).picture();
            var rendered = grid(ourPicture);
            var preview = grid(theirPicture);
            // the default rendering, with the fitted matrix and the neutral estimated from the camera's preview
            var byDefault = grid(ImageOps.crop(RawDecoder.decode(vendor).picture(), Math.clamp((long) best[1], 0,
                    full.width() - theirs.width()), Math.clamp((long) best[0], 0, full.height() - theirs.height()),
                    theirs.width(), theirs.height()));
            // without the converter's baseline exposure the default rendering's tones differ more
            assertTrue(correlation(byDefault[1], preview[1]) > 0.95, "default structure " + correlation(byDefault[1],
                    preview[1]));
            for (var channel = 0; channel < 3; channel += 2) {
                assertEquals(mean(preview[channel]) / mean(preview[1]), mean(byDefault[channel]) / mean(byDefault[1]),
                        0.15, "the default balance of channel " + channel + " to green");
            }
            assertTrue(correlation(rendered[1], preview[1]) > 0.98, "structure " + correlation(rendered[1], preview[1])
                    + " (" + ourPicture.width() + "x" + ourPicture.height() + " against " + theirPicture.width() + "x"
                    + theirPicture.height() + ")");
            for (var channel = 0; channel < 3; channel += 2) {
                assertEquals(mean(preview[channel]) / mean(preview[1]), mean(rendered[channel]) / mean(rendered[1]), 0.15,
                        "the balance of channel " + channel + " to green");
            }
            // the converter's file, of the same samples, pattern, matrix, neutral and exposure, renders alike
            var direct = grid(RawDecoder.decode(oracle).picture());
            for (var channel = 0; channel < 3; channel++) {
                assertTrue(correlation(rendered[channel], direct[channel]) > 0.995, "channel " + channel + " correlates "
                        + correlation(rendered[channel], direct[channel]) + " with the converter's file's rendering");
                assertEquals(1, mean(rendered[channel]) / mean(direct[channel]), 0.04, "the level of channel " + channel);
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.EnumSource(value = RawSamples.class, names = {"CRW_G1", "NEF_E5700"})
    @DisplayName("the fitted matrix renders a four-color file's chroma as close to the camera's preview as the converter's matrix does")
    void fourColorChroma(RawSamples sample) {
        var name = sample.fileName();
        var dng = oracle(name.substring(0, name.lastIndexOf('.')));
        try (var vendor = MediaSource.of(sample.fetch()); var oracle = MediaSource.of(dng)) {
            var preview = FourColorMatricesTest.grid(Images.decode(MediaSource.of(RawDecoder.extractPreview(vendor)
                    .orElseThrow())).picture());
            var color = RawParser.parse(oracle, MediaLimits.DEFAULT).color;
            var theirs = color.colorMatrix[1] != null ? color.colorMatrix[1] : color.colorMatrix[0];
            var fitted = FourColorMatricesTest.chromaDistance(FourColorMatricesTest.grid(RawDecoder.decode(vendor)
                    .picture()), preview);
            var converter = FourColorMatricesTest.chromaDistance(FourColorMatricesTest.grid(RawDecoder.decode(vendor,
                    RawDecoderOptions.builder().colorMatrix(theirs).build()).picture()), preview);
            var generic = FourColorMatricesTest.chromaDistance(FourColorMatricesTest.grid(RawDecoder.decode(vendor,
                    RawDecoderOptions.builder().colorMatrix(FourColorMatrices.generic(FourColorMatricesTest.planes(
                            RawParser.parse(vendor, MediaLimits.DEFAULT)))).build()).picture()), preview);
            assertTrue(fitted <= converter, "mean chroma distance to the preview: fitted " + fitted + ", converter's "
                    + converter + ", generic " + generic);
        }
    }

    @Test
    @DisplayName("four multipliers equal to the as-shot balance render the four-color DNG file as shot")
    void fourMultipliers() {
        var dng = oracle("rawpixls-canon-powershot-g1-raw-4-3");
        try (var source = MediaSource.of(dng)) {
            var neutral = RawDecoder.decodeRaw(source).cameraNeutral();
            var factors = new double[4];
            for (var index = 0; index < 4; index++) {
                factors[index] = 1 / neutral[index];
            }
            var asShot = RawDecoder.decode(source).picture();
            var balanced = RawDecoder.decode(source, RawDecoderOptions.builder()
                    .whiteBalance(RawWhiteBalance.multipliers(factors)).build()).picture();
            var a = grid(asShot);
            var b = grid(balanced);
            for (var channel = 0; channel < 3; channel++) {
                assertArrayEquals(a[channel], b[channel], 1.0, "channel " + channel);
            }
        }
    }

    @Test
    @DisplayName("the ProfileGainTableMap of an iPhone 12 Pro ProRAW file brings the rendering closer to the camera's")
    void gainTableMap() {
        try (var source = MediaSource.of(RawSamples.DNG_IPHONE_12_PRO.fetch())) {
            var file = RawParser.parse(source, MediaLimits.DEFAULT);
            var map = file.color.gainTableMap;
            assertTrue(map != null, "no gain table map");
            assertEquals(6, map.pointsV);
            assertEquals(8, map.pointsH);
            assertEquals(257, map.pointsN);
            assertEquals(1, file.color.blackRender);
            var preview = Images.decode(MediaSource.of(RawDecoder.extractPreview(source).orElseThrow())).picture();
            var options = RawDecoderOptions.defaults();
            var with = luminance(grid(Renderer.render(file, file.sensor, options, false)));
            file.color.gainTableMap = null;
            var without = luminance(grid(Renderer.render(file, file.sensor, options, false)));
            var camera = luminance(grid(preview));
            var withError = meanAbsolute(with, camera);
            var withoutError = meanAbsolute(without, camera);
            assertTrue(withError < withoutError, "with the map " + withError + ", without " + withoutError);
        }
    }

    // the red, green and blue values of a 48 by 32 reduction of a picture
    static double[][] grid(Picture picture) {
        var small = ImageOps.toRgba(ImageOps.resize(picture, GRID_WIDTH, GRID_HEIGHT, ResizeFilter.AREA));
        var values = new double[3][GRID_WIDTH * GRID_HEIGHT];
        var pixels = small.pixels();
        for (var y = 0; y < GRID_HEIGHT; y++) {
            for (var x = 0; x < GRID_WIDTH; x++) {
                for (var channel = 0; channel < 3; channel++) {
                    values[channel][y * GRID_WIDTH + x] = pixels[y * small.stride() + x * 4 + channel] & 0xFF;
                }
            }
        }
        return values;
    }

    private static double[] luminance(double[][] grid) {
        var result = new double[grid[0].length];
        for (var index = 0; index < result.length; index++) {
            result[index] = 0.2126 * grid[0][index] + 0.7152 * grid[1][index] + 0.0722 * grid[2][index];
        }
        return result;
    }

    private static double meanAbsolute(double[] a, double[] b) {
        var sum = 0.0;
        for (var index = 0; index < a.length; index++) {
            sum += Math.abs(a[index] - b[index]);
        }
        return sum / a.length;
    }

    // the correlation of the high-pass green (each pixel minus its 3x3 mean) of a rendering resized to a size
    private static double detailCorrelation(Picture rendering, Picture reference, int[] size) {
        var a = green(ImageOps.toRgba(ImageOps.resize(rendering, size[0], size[1], ResizeFilter.AREA)));
        var b = green(ImageOps.toRgba(reference));
        return correlation(highPass(a, size[0], size[1]), highPass(b, size[0], size[1]));
    }

    private static double[] green(com.github.auties00.smalt.image.RgbaPicture picture) {
        var values = new double[picture.width() * picture.height()];
        for (var y = 0; y < picture.height(); y++) {
            for (var x = 0; x < picture.width(); x++) {
                values[y * picture.width() + x] = picture.pixels()[y * picture.stride() + x * 4 + 1] & 0xFF;
            }
        }
        return values;
    }

    private static double[] highPass(double[] values, int width, int height) {
        var result = new double[(width - 2) * (height - 2)];
        for (var y = 1; y < height - 1; y++) {
            for (var x = 1; x < width - 1; x++) {
                var sum = 0.0;
                for (var dy = -1; dy <= 1; dy++) {
                    for (var dx = -1; dx <= 1; dx++) {
                        sum += values[(y + dy) * width + x + dx];
                    }
                }
                result[(y - 1) * (width - 2) + x - 1] = values[y * width + x] - sum / 9;
            }
        }
        return result;
    }

    // the offset of the second sample image within the first, of up to twelve photosites each way, where they correlate
    // best: the row offset, the column offset and the correlation. The odd strides visit every phase of a 2x2 pattern,
    // so a wrong parity correlates the planes of different colors and loses.
    private static double[] sampleOffset(short[] a, int aw, int ah, short[] b, int bw, int bh) {
        var best = new double[]{0, 0, -1};
        for (var dy = -12; dy <= 12; dy++) {
            for (var dx = -12; dx <= 12; dx++) {
                var count = 0;
                var x = new double[(aw / 7 + 1) * (ah / 7 + 1)];
                var y = new double[x.length];
                for (var row = 16; row < ah - 16; row += 7) {
                    for (var column = 16; column < aw - 16; column += 7) {
                        var br = row - dy;
                        var bc = column - dx;
                        if (br < 0 || bc < 0 || br >= bh || bc >= bw || count >= x.length) {
                            continue;
                        }
                        x[count] = a[row * aw + column] & 0xFFFF;
                        y[count] = b[br * bw + bc] & 0xFFFF;
                        count++;
                    }
                }
                var correlation = correlation(Arrays.copyOf(x, count), Arrays.copyOf(y, count));
                if (correlation > best[2]) {
                    best = new double[]{dy, dx, correlation};
                }
            }
        }
        return best;
    }

    static double mean(double[] values) {
        return Arrays.stream(values).average().orElse(0);
    }

    static double correlation(double[] a, double[] b) {
        var ma = mean(a);
        var mb = mean(b);
        var covariance = 0.0;
        var va = 0.0;
        var vb = 0.0;
        for (var index = 0; index < a.length; index++) {
            covariance += (a[index] - ma) * (b[index] - mb);
            va += (a[index] - ma) * (a[index] - ma);
            vb += (b[index] - mb) * (b[index] - mb);
        }
        return covariance / Math.sqrt(va * vb);
    }
}
