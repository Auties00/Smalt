package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.jxl.JxlDecoder;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.image.ops.ResizeFilter;
import com.github.auties00.smalt.image.raw.RawDecoder;
import com.github.auties00.smalt.image.raw.RawSamples;
import com.github.auties00.smalt.image.raw.TinyDng;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the DNG 1.7 JPEG XL compression of sensor data: synthetic DNG files whose strip is a
 * libjxl conformance codestream, and the enhanced image of a CC0 DNG file Adobe software wrote with
 * JPEG XL tiles.
 */
class JpegXlTilesTest {
    private static final int GRID_WIDTH = 48;

    private static final int GRID_HEIGHT = 32;

    private static SensorPlane read(byte[] dng) {
        var file = RawParser.parse(MediaSource.of(dng), MediaLimits.DEFAULT);
        assertNotNull(file.sensor, file.unsupported);
        var sensor = file.sensor;
        var plane = new SensorPlane(sensor.width, sensor.height, sensor.samplesPerPixel, sensor.floating, MediaLimits.DEFAULT);
        sensor.reader.read(plane, MediaLimits.DEFAULT);
        return plane;
    }

    private static TinyDng wrap(Path jxl, int samples, int bits) throws Exception {
        var info = JxlDecoder.probe(MediaSource.of(jxl));
        var dng = new TinyDng();
        dng.width = info.width();
        dng.height = info.height();
        dng.samplesPerPixel = samples;
        dng.bits = bits;
        dng.compression = TiffSamples.COMPRESSION_JPEG_XL;
        dng.stripData = Files.readAllBytes(jxl);
        return dng;
    }

    private static void assertScaled(Path jxl, SensorPlane plane, int bits) {
        var image = JxlDecoder.decodeFloat(MediaSource.of(jxl));
        var maximum = (1 << bits) - 1;
        for (var sample = 0; sample < plane.samplesPerPixel; sample++) {
            var channel = image.planes()[sample];
            for (var index = 0; index < channel.length; index++) {
                var expected = Math.clamp(Math.round(channel[index] * maximum), 0, maximum);
                var actual = plane.integers[index * plane.samplesPerPixel + sample] & 0xFFFF;
                if (expected != actual) {
                    assertEquals(expected, actual, "sample " + sample + " of pixel " + index);
                }
            }
        }
    }

    @Test
    @DisplayName("an 8-bit lossless RGB codestream fills a linear raw image, extra channels ignored")
    void losslessRgb() throws Exception {
        var jxl = Conformance.fetch("jxl-conformance-patches_lossless.jxl",
                "https://raw.githubusercontent.com/libjxl/conformance/b1d0f990b03e57bf6d137c365cd5dc8b470b9191/testcases/patches_lossless/input.jxl",
                "2a47b6766264ac6243a43bbb85684688717591bb3bd9b4c2db56226192418b4e");
        var plane = read(wrap(jxl, 3, 8).write());
        assertEquals(3, plane.samplesPerPixel);
        assertScaled(jxl, plane, 8);
    }

    @Test
    @DisplayName("a lossy gray codestream fills a 16-bit image, clamped to the sample range")
    void lossyGray() throws Exception {
        var jxl = Conformance.fetch("jxl-conformance-grayscale.jxl",
                "https://raw.githubusercontent.com/libjxl/conformance/b1d0f990b03e57bf6d137c365cd5dc8b470b9191/testcases/grayscale/input.jxl",
                "78fbbba852e99946d187dcf0bcbd7fb0e7c22be2f0852523aaae6ed91e7e3c39");
        var plane = read(wrap(jxl, 1, 16).write());
        assertScaled(jxl, plane, 16);
    }

    @Test
    @DisplayName("a block that is not a JPEG XL codestream raises Malformed")
    void corrupt() {
        var dng = TinyDng.ramp(16, 16);
        dng.compression = TiffSamples.COMPRESSION_JPEG_XL;
        assertTrue(RawDecoder.info(MediaSource.of(dng.write())).isDecodable());
        assertThrows(MediaException.Malformed.class, () -> RawDecoder.decode(MediaSource.of(dng.write())));
    }

    @Test
    @DisplayName("the JPEG XL enhanced image of a CC0 DNG 1.7 file matches its embedded preview")
    void enhancedImage() {
        try (var source = MediaSource.of(RawSamples.DNG_7D2_JPEG_XL.fetch())) {
            var reader = TiffReader.open(source, 0, MediaLimits.DEFAULT);
            Ifd enhanced = null;
            for (var ifd : new TiffTree(reader).directories) {
                if (ifd.longValue(TiffTree.NEW_SUBFILE_TYPE, 0) == 16) {
                    enhanced = ifd;
                }
            }
            assertNotNull(enhanced);
            assertEquals(TiffSamples.COMPRESSION_JPEG_XL, enhanced.intValue(TiffSamples.COMPRESSION, 0));
            var sensor = new Sensor();
            var samples = TiffSamples.describe(enhanced, sensor);
            var plane = new SensorPlane(sensor.width, sensor.height, sensor.samplesPerPixel, false, MediaLimits.DEFAULT);
            samples.read(plane, MediaLimits.DEFAULT);
            assertEquals(5496, plane.width);
            assertEquals(3670, plane.height);
            var preview = Images.decode(MediaSource.of(RawDecoder.extractPreview(source).orElseThrow())).picture();
            var small = ImageOps.toRgba(ImageOps.resize(preview, GRID_WIDTH, GRID_HEIGHT, ResizeFilter.AREA));
            var expected = new double[GRID_WIDTH * GRID_HEIGHT];
            var actual = new double[GRID_WIDTH * GRID_HEIGHT];
            for (var y = 0; y < GRID_HEIGHT; y++) {
                for (var x = 0; x < GRID_WIDTH; x++) {
                    var at = y * small.stride() + x * 4;
                    expected[y * GRID_WIDTH + x] = small.pixels()[at + 1] & 0xFF;
                    var sum = 0.0;
                    var count = 0;
                    for (var row = y * plane.height / GRID_HEIGHT; row < (y + 1) * plane.height / GRID_HEIGHT; row += 4) {
                        for (var column = x * plane.width / GRID_WIDTH; column < (x + 1) * plane.width / GRID_WIDTH; column += 4) {
                            sum += plane.integers[(row * plane.width + column) * 3 + 1] & 0xFFFF;
                            count++;
                        }
                    }
                    actual[y * GRID_WIDTH + x] = Math.pow(sum / count / 65535.0, 1 / 2.2);
                }
            }
            var correlation = correlation(expected, actual);
            assertTrue(correlation > 0.9, "correlation with the preview " + correlation);
        }
    }

    private static double correlation(double[] a, double[] b) {
        var meanA = 0.0;
        var meanB = 0.0;
        for (var i = 0; i < a.length; i++) {
            meanA += a[i];
            meanB += b[i];
        }
        meanA /= a.length;
        meanB /= b.length;
        var covariance = 0.0;
        var varianceA = 0.0;
        var varianceB = 0.0;
        for (var i = 0; i < a.length; i++) {
            covariance += (a[i] - meanA) * (b[i] - meanB);
            varianceA += (a[i] - meanA) * (a[i] - meanA);
            varianceB += (b[i] - meanB) * (b[i] - meanB);
        }
        return covariance / Math.sqrt(varianceA * varianceB);
    }
}
