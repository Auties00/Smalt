package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.raw.RawDecoderOptions;
import com.github.auties00.smalt.image.raw.RawDemosaic;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Skip;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the lens corrections derived from the maker notes of Sony, Panasonic, Olympus and Fujifilm files against
 * the {@code WarpRectilinear} and {@code FixVignetteRadial} opcodes Adobe DNG Converter, run as a black box, writes
 * for the same CC0 samples, kept by {@code tools/testdata/raw-lens-oracle.py} in
 * {@code <conformance cache>/raw-lens-oracle} ({@code -Dsmalt.test.rawLensOracle}); skipped without them.
 *
 * <p>The opcodes are compared as maps from an output radius in pixels to a source radius in pixels, up to the
 * corner of the default crop, with each side's own normalization. The tolerance of each sample, in pixels, is
 * the measured agreement of the derived model, rounded up; every sample's correction is at least four times its
 * tolerance, so a sample decoded without its correction fails; the Panasonic samples' chromatic aberration (the
 * largest difference between the converter's red or blue plane and its green plane) is at least three times the red
 * and blue tolerance, so a sample decoded without it fails too.
 */
class LensCorrectionOracleTest {
    private static final Path ORACLE = Path.of(System.getProperty("smalt.test.rawLensOracle",
            Conformance.directory().resolve("raw-lens-oracle").toString()));

    /**
     * A sample: its file name, the tolerance in pixels of the green plane and of the red and blue planes, and the
     * tolerance of the vignetting gain, or a negative value when the converter writes none.
     */
    private record Sample(String name, double green, double redBlue, double vignette) {
    }

    private static final List<Sample> SAMPLES = List.of(
            new Sample("rawpixls-sony-dsc-rx100m3-12bit-12bit-compressed-3-2.arw", 1, 1, -1),
            new Sample("rawpixls-sony-dsc-rx10-12bit-12bit-compressed-3-2.arw", 1, 1, -1),
            new Sample("rawpixls-sony-dsc-rx0-12bit-12bit-compressed-3-2.arw", 3, 3, -1),
            new Sample("rawpixls-sony-zv-1-12bit-12bit-compressed-3-2.arw", 1, 1, -1),
            new Sample("rawpixls-panasonic-dc-g9-4-3-2348.rw2", 1, 0.25, -1),
            new Sample("rawpixls-panasonic-dmc-gx80-4-3.rw2", 1.5, 0.1, -1),
            new Sample("rawpixls-panasonic-dc-tz200-4-3.rw2", 1, 1, -1),
            new Sample("rawpixls-panasonic-dmc-lx100-4-3.rw2", 2.5, 0.5, -1),
            new Sample("rawpixls-panasonic-dmc-fz1000-1-1.rw2", 2, 0.5, -1),
            new Sample("rawpixls-olympus-e-m1-16bit-4-3.orf", 1, 1, -1),
            new Sample("rawpixls-olympus-e-m10-mark-iii-16bit-4-3.orf", 1, 1, -1),
            new Sample("rawpixls-olympus-e-pl5-16bit-4-3.orf", 1, 1, -1),
            new Sample("rawpixls-olympus-stylus1-16bit-4-3.orf", 1, 1, -1),
            new Sample("rawpixls-fujifilm-x-t4-14bit-14bit-compressed-3-2.raf", 7, 7, 0.015),
            new Sample("rawpixls-fujifilm-gfx-50s-14bit-14bit-compressed-3-2.raf", 5, 5, 0.015),
            new Sample("rawpixls-fujifilm-x-a1-12bit-12bit-uncompressed-3-2.raf", 25, 25, 0.06),
            new Sample("rawpixls-fujifilm-finepix-x10-12bit-12bit-uncompressed-4-3.raf", 3, 3, -1));

    /**
     * The converter's opcodes of one sample.
     *
     * @param active the active area: top, left, bottom and right
     * @param crop the default crop relative to the active area: x, y, width and height
     * @param list2 opcode list 2, or {@code null}
     * @param list3 opcode list 3, or {@code null}
     */
    private record Oracle(int[] active, double[] crop, byte[] list2, byte[] list3) {
        static Oracle read(Path path) throws IOException {
            int[] active = null;
            double[] crop = null;
            byte[] list2 = null;
            byte[] list3 = null;
            for (var line : Files.readAllLines(path)) {
                var parts = line.split(" ");
                switch (parts[0]) {
                    case "active" -> active = new int[]{Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
                            Integer.parseInt(parts[3]), Integer.parseInt(parts[4])};
                    case "crop" -> crop = new double[]{Double.parseDouble(parts[1]), Double.parseDouble(parts[2]),
                            Double.parseDouble(parts[3]), Double.parseDouble(parts[4])};
                    case "list2" -> list2 = HexFormat.of().parseHex(parts[1]);
                    case "list3" -> list3 = HexFormat.of().parseHex(parts[1]);
                    default -> {
                    }
                }
            }
            return new Oracle(active, crop, list2, list3);
        }
    }

    /**
     * The radial opcodes of a list: the four radial coefficients of each WarpRectilinear plane, its center, and the
     * FixVignetteRadial coefficients and center.
     */
    private record Radial(List<double[]> planes, double[] center, double[] vignette, double[] vignetteCenter) {
        static Radial of(byte[]... lists) {
            var planes = new ArrayList<double[]>();
            double[] center = null;
            double[] vignette = null;
            double[] vignetteCenter = null;
            for (var list : lists) {
                if (list == null) {
                    continue;
                }
                var buffer = ByteBuffer.wrap(list);
                var count = buffer.getInt();
                for (var op = 0; op < count; op++) {
                    var id = buffer.getInt();
                    buffer.getInt();
                    buffer.getInt();
                    var length = buffer.getInt();
                    var start = buffer.position();
                    if (id == 1) {
                        var sets = buffer.getInt();
                        for (var set = 0; set < sets; set++) {
                            var k = new double[6];
                            for (var index = 0; index < 6; index++) {
                                k[index] = buffer.getDouble();
                            }
                            planes.add(k);
                        }
                        center = new double[]{buffer.getDouble(), buffer.getDouble()};
                    } else if (id == 3) {
                        vignette = new double[5];
                        for (var index = 0; index < 5; index++) {
                            vignette[index] = buffer.getDouble();
                        }
                        vignetteCenter = new double[]{buffer.getDouble(), buffer.getDouble()};
                    }
                    buffer.position(start + length);
                }
            }
            return new Radial(planes, center, vignette, vignetteCenter);
        }

        double[] plane(int index) {
            return planes.get(Math.min(index, planes.size() - 1));
        }
    }

    private static double corner(double[] center, double width, double height) {
        var cx = center[0] * width;
        var cy = center[1] * height;
        return Math.hypot(Math.max(cx, width - cx), Math.max(cy, height - cy));
    }

    private static double source(double[] k, double pixels, double corner) {
        var r = pixels / corner;
        return pixels * (k[0] + r * r * (k[1] + r * r * (k[2] + r * r * k[3])));
    }

    private static double gain(double[] k, double pixels, double corner) {
        var r2 = Math.pow(pixels / corner, 2);
        return 1 + r2 * (k[0] + r2 * (k[1] + r2 * (k[2] + r2 * (k[3] + r2 * k[4]))));
    }

    private static RawFile open(Path path) throws IOException {
        return RawDecoding.open(MediaSource.of(FileChannel.open(path, StandardOpenOption.READ)), MediaLimits.DEFAULT);
    }

    @Test
    @DisplayName("the opcodes derived from the maker note map radii as the converter's do")
    void opcodes() throws IOException {
        Skip.unless(Files.isDirectory(ORACLE), "data:raw-lens-oracle", "no lens oracle files in " + ORACLE);
        var failures = new ArrayList<String>();
        for (var sample : SAMPLES) {
            var stem = sample.name().substring(0, sample.name().lastIndexOf('.'));
            var oraclePath = ORACLE.resolve(stem + ".lens");
            assertTrue(Files.isRegularFile(oraclePath), "no oracle file " + oraclePath);
            var raw = Conformance.fetchListed(sample.name());
            var oracle = Oracle.read(oraclePath);
            var converter = Radial.of(oracle.list2(), oracle.list3());
            var file = open(raw);
            var sensor = file.sensor;
            var area = sensor.active();
            var width = area[3] - area[1];
            var height = area[2] - area[0];
            assertNotNull(sensor.lens, sample.name());
            var ours = Radial.of(sensor.lens.opcodes(width, height));
            var activeWidth = oracle.active()[3] - oracle.active()[1];
            var activeHeight = oracle.active()[2] - oracle.active()[0];
            var reach = Math.hypot(oracle.crop()[2] / 2, oracle.crop()[3] / 2);
            var theirCorner = corner(converter.center(), activeWidth, activeHeight);
            // the staggered images of EXR sensors have pixels half as wide as high; the converter's are square
            var aspect = Renderer.aspect(sensor);
            var ourCorner = corner(ours.center(), width * aspect, height);
            var correction = 0.0;
            var aberration = 0.0;
            var worst = new double[3];
            for (var step = 0; step <= 400; step++) {
                var pixels = reach * step / 400;
                for (var plane = 0; plane < 3; plane++) {
                    var expected = source(converter.plane(plane), pixels, theirCorner);
                    var actual = source(ours.plane(plane), pixels, ourCorner);
                    worst[plane] = Math.max(worst[plane], Math.abs(expected - actual));
                    if (plane == 1) {
                        correction = Math.max(correction, Math.abs(expected - pixels));
                    } else {
                        aberration = Math.max(aberration,
                                Math.abs(expected - source(converter.plane(1), pixels, theirCorner)));
                    }
                }
            }
            if (correction < 4 * sample.green()) {
                failures.add(sample.name() + ": the converter's correction of " + correction + " px is too small");
            }
            if (sample.name().contains("panasonic") && aberration < 3 * sample.redBlue()) {
                failures.add(sample.name() + ": the converter's chromatic aberration of " + aberration
                        + " px is too small");
            }
            for (var plane = 0; plane < 3; plane++) {
                var tolerance = plane == 1 ? sample.green() : sample.redBlue();
                if (!(worst[plane] <= tolerance)) {
                    failures.add(sample.name() + ": plane " + plane + " differs by " + worst[plane] + " px, more than "
                            + tolerance);
                }
            }
            if (sample.vignette() >= 0) {
                assertNotNull(converter.vignette(), sample.name());
                assertNotNull(ours.vignette(), sample.name());
                var vignetteCorner = corner(converter.vignetteCenter(), activeWidth, activeHeight);
                var ourVignetteCorner = corner(ours.vignetteCenter(), width * aspect, height);
                for (var step = 0; step <= 100; step++) {
                    var pixels = reach * step / 100;
                    var expected = gain(converter.vignette(), pixels, vignetteCorner);
                    var actual = gain(ours.vignette(), pixels, ourVignetteCorner);
                    if (!(Math.abs(expected - actual) <= sample.vignette() * expected)) {
                        failures.add(sample.name() + ": vignetting gain " + actual + " at " + pixels + " px, converter "
                                + expected);
                        break;
                    }
                }
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    @Test
    @DisplayName("the rendering with the corrections correlates with the rendering of the converter's DNG file")
    void rendering() throws IOException {
        Skip.unless(Files.isDirectory(ORACLE), "data:raw-lens-oracle", "no lens oracle files in " + ORACLE);
        for (var name : List.of("rawpixls-sony-dsc-rx100m3-12bit-12bit-compressed-3-2.arw",
                "rawpixls-olympus-e-m1-16bit-4-3.orf")) {
            var stem = name.substring(0, name.lastIndexOf('.'));
            var options = RawDecoderOptions.builder().defaultCrop(false).embeddedPreview(false).highBitDepth(true)
                    .demosaic(RawDemosaic.BILINEAR).build();
            var converted = luminance(render(ORACLE.resolve(stem + ".dng"), options));
            var raw = Conformance.fetchListed(name);
            var corrected = luminance(render(raw, options));
            var plain = luminance(render(raw,
                    options.toBuilder().lensCorrections(false).build()));
            assertEquals(converted.length, corrected.length, name);
            var with = correlation(converted, corrected);
            var without = correlation(converted, plain);
            assertTrue(with > 0.99 && with > without, name + ": correlation " + with + " with, " + without
                    + " without the corrections");
        }
    }

    private static Rgba16Picture render(Path path, RawDecoderOptions options) throws IOException {
        var file = RawDecoding.open(MediaSource.of(FileChannel.open(path, StandardOpenOption.READ)), MediaLimits.DEFAULT);
        return (Rgba16Picture) Renderer.render(file, options, false);
    }

    /**
     * Averages the green channel over 16x16 blocks, which leaves the geometry and removes demosaicing detail.
     */
    private static double[] luminance(Rgba16Picture picture) {
        var columns = picture.width() / 16;
        var rows = picture.height() / 16;
        var result = new double[columns * rows];
        var pixels = picture.pixels();
        var stride = picture.stride();
        for (var y = 0; y < rows * 16; y++) {
            for (var x = 0; x < columns * 16; x++) {
                result[(y / 16) * columns + x / 16] += pixels[y * stride + x * 4 + 1] & 0xFFFF;
            }
        }
        return result;
    }

    private static double correlation(double[] a, double[] b) {
        var n = a.length;
        double ma = 0;
        double mb = 0;
        for (var index = 0; index < n; index++) {
            ma += a[index];
            mb += b[index];
        }
        ma /= n;
        mb /= n;
        double ab = 0;
        double aa = 0;
        double bb = 0;
        for (var index = 0; index < n; index++) {
            ab += (a[index] - ma) * (b[index] - mb);
            aa += (a[index] - ma) * (a[index] - ma);
            bb += (b[index] - mb) * (b[index] - mb);
        }
        return ab / Math.sqrt(aa * bb);
    }
}
