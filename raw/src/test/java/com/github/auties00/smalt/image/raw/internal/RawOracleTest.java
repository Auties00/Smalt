package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.testkit.Skip;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.raw.RawDecoder;
import com.github.auties00.smalt.image.raw.RawDecoderOptions;
import com.github.auties00.smalt.image.raw.RawSamples;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks, against Adobe DNG Converter as a black-box oracle, that the decoder reproduces exactly the
 * sensor values the converter stores for CC0 samples of the layouts worked out against it: the
 * Olympus compression and the old ORF word, packed and interlaced layouts, Panasonic RW2 versions
 * 4, 5, 6 and 8, the PEF table and packing variants, Canon mRAW and sRAW, the interlaced rows of
 * the E-series Coolpix NEF, and the packed and word layouts of Minolta MRW (the Canon CRW decoder is
 * checked by {@link CrwTablesTest}). For MRW the linear values (crop, black and white levels
 * applied) and the white balance must equal those of the converter's file, and for CRW the image
 * area, the levels and the white balance; the default rendering of both, with the generic camera
 * matrix, must match the converter's own rendering in structure, and with the converter's matrix
 * also in channel balance. The converter's files of three four-color cameras (cyan, yellow,
 * green and magenta; red, green, blue and emerald) check the rendering of four-color DNG files
 * against the preview the converter renders into them. The converter stores denoised,
 * color-converted values for Sigma X3F files, not the sensor layers, so for X3F only the image
 * area, the linear relation of the layers to its values and the rendering are compared. The oracle files are the uncompressed DNG
 * files {@code tools/testdata/raw-oracle.py} writes into {@code <conformance cache>/raw-oracle}
 * ({@code -Dsmalt.test.rawOracle} overrides the directory); the tests are skipped without them. The
 * converter may store a few rows or columns fewer than the file; the oracle is located within the
 * decoded image at the offset, of at most eight photosites, where it matches.
 */
class RawOracleTest {
    private static final Path ORACLE = Path.of(System.getProperty("smalt.test.rawOracle",
            Conformance.directory().resolve("raw-oracle").toString()));

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = RawSamples.class, names = {"CR2_40D_SRAW", "CR2_5D3_SRAW", "CR2_7D_MRAW", "CR2_7D_SRAW", "NEF_E5700",
            "NEF_E8400", "NEF_E8800", "ORF_C5060WZ", "ORF_E1", "ORF_E10", "ORF_E400", "ORF_E410", "ORF_EM1", "ORF_EM10",
            "ORF_EP1", "ORF_EP3", "ORF_EP5", "ORF_EPL6", "ORF_OM1", "ORF_TG7", "PEF_IST_D", "PEF_IST_DL",
            "PEF_K3III_MONOCHROME", "PEF_K5", "PEF_KS1", "RW2_G9", "RW2_G9M2", "RW2_GF6", "RW2_GH1", "RW2_GH5S",
            "RW2_LX3", "RW2_S1", "RW2_S5M2", "MRW_ALPHA_7D", "MRW_ALPHA_7D_2025", "MRW_ALPHA_SWEET", "MRW_DIMAGE_5",
            "MRW_DIMAGE_A2", "MRW_DYNAX_5D", "MRW_DYNAX_7D", "MRW_MAXXUM_7D"})
    @DisplayName("the decoder reproduces the values Adobe DNG Converter stores for the sample")
    void decoding(RawSamples sample) {
        var name = sample.fileName();
        var dng = ORACLE.resolve(name.substring(0, name.lastIndexOf('.')) + ".dng");
        Skip.unless(Files.isRegularFile(dng), "data:raw-oracle", "no oracle file " + dng + "; run tools/testdata/raw-oracle.py");
        var oracle = NikonTableDerivation.oracle(dng);
        SensorPlane decoded;
        try (var source = MediaSource.of(sample.fetch())) {
            var file = RawParser.parse(source, MediaLimits.DEFAULT);
            assertTrue(file.sensor != null, file.unsupported);
            var sensor = file.sensor;
            decoded = new SensorPlane(sensor.width, sensor.height, sensor.samplesPerPixel, sensor.floating, MediaLimits.DEFAULT);
            sensor.reader.read(decoded, MediaLimits.DEFAULT);
        }
        assertEquals(oracle.samplesPerPixel, decoded.samplesPerPixel);
        var offset = locate(decoded, oracle);
        assertTrue(offset != null, "the oracle matches the decoded samples at no offset");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = RawSamples.class, names = {"MRW_ALPHA_7D", "MRW_ALPHA_7D_2025", "MRW_ALPHA_SWEET", "MRW_DIMAGE_5",
            "MRW_DIMAGE_A2", "MRW_DYNAX_5D", "MRW_DYNAX_7D", "MRW_MAXXUM_7D"})
    @DisplayName("the linear values, crop, levels and white balance equal those of the converter's DNG file")
    void linear(RawSamples sample) {
        var name = sample.fileName();
        var dng = ORACLE.resolve(name.substring(0, name.lastIndexOf('.')) + ".dng");
        Skip.unless(Files.isRegularFile(dng), "data:raw-oracle", "no oracle file " + dng + "; run tools/testdata/raw-oracle.py");
        try (var source = MediaSource.of(sample.fetch()); var oracle = MediaSource.of(dng)) {
            var ours = RawDecoder.decodeRaw(source);
            var theirs = RawDecoder.decodeRaw(oracle);
            assertEquals(theirs.width(), ours.width());
            assertEquals(theirs.height(), ours.height());
            assertEquals(theirs.cfaPattern(), ours.cfaPattern());
            assertTrue(Arrays.equals(theirs.samples(), ours.samples()), "the linear values differ");
            assertArrayEquals(theirs.cameraNeutral(), ours.cameraNeutral(), 1e-4);
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = RawSamples.class, names = {"CRW_D30", "CRW_D60", "CRW_10D", "CRW_10D_3164", "CRW_10D_3165", "CRW_300D", "CRW_300D_3162",
            "CRW_DIGITAL_REBEL", "CRW_G5", "CRW_G6", "CRW_G1"})
    @DisplayName("the image area, levels and white balance of a CRW file equal those of the converter's DNG file")
    void crwLevels(RawSamples sample) {
        var name = sample.fileName();
        var dng = ORACLE.resolve(name.substring(0, name.lastIndexOf('.')) + ".dng");
        Skip.unless(Files.isRegularFile(dng), "data:raw-oracle", "no oracle file " + dng + "; run tools/testdata/raw-oracle.py");
        try (var source = MediaSource.of(sample.fetch()); var oracle = MediaSource.of(dng)) {
            var ours = RawParser.parse(source, MediaLimits.DEFAULT);
            var theirs = RawParser.parse(oracle, MediaLimits.DEFAULT);
            var area = ours.sensor.active();
            var active = theirs.sensor.active();
            var crop = theirs.sensor.defaultCrop;
            var left = active[1] + (int) crop[0];
            var top = active[0] + (int) crop[1];
            // the converter moves the G1's odd top edge to the even row above it
            var expected = new int[]{top, left, top + (int) crop[3], left + (int) crop[2]};
            if (sample == RawSamples.CRW_G1) {
                expected = new int[]{top + 1, left, top + 1 + (int) crop[3], left + (int) crop[2]};
            }
            assertArrayEquals(expected, area, "image area");
            var plane = new SensorPlane(ours.sensor.width, ours.sensor.height, 1, false, MediaLimits.DEFAULT);
            ours.sensor.reader.read(plane, MediaLimits.DEFAULT);
            if (theirs.sensor.linearization == null) {
                assertArrayEquals(theirs.sensor.white, ours.sensor.white, "white level");
                // the converter measures the black level on its own masked area; G5 files get none
                if (sample != RawSamples.CRW_G5) {
                    for (var cell = 0; cell < 4; cell++) {
                        var row = (cell >> 1) + area[0] - active[0];
                        var column = (cell & 1) + area[1] - active[1];
                        var level = theirs.sensor.black[((row % theirs.sensor.blackRows) * theirs.sensor.blackColumns)
                                + column % theirs.sensor.blackColumns];
                        var actual = ours.sensor.black[(cell >> 1) * 2 + (cell & 1)];
                        assertEquals(level, actual, 1.0, "black level of cell " + cell);
                    }
                }
            }
            if (theirs.color.asShotNeutral != null && ours.color.asShotNeutral != null) {
                assertArrayEquals(theirs.color.asShotNeutral, ours.color.asShotNeutral, 1e-4);
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = RawSamples.class, names = {"MRW_ALPHA_7D", "MRW_ALPHA_7D_2025", "MRW_ALPHA_SWEET", "MRW_DIMAGE_5",
            "MRW_DIMAGE_A2", "MRW_DYNAX_5D", "MRW_DYNAX_7D", "MRW_MAXXUM_7D", "CRW_D30", "CRW_D60", "CRW_10D", "CRW_10D_3164", "CRW_10D_3165", "CRW_300D", "CRW_300D_3162",
            "CRW_DIGITAL_REBEL", "CRW_G5", "CRW_G6"})
    @DisplayName("the rendering matches the converter's rendering of the sample in structure and balance")
    void rendering(RawSamples sample) {
        var name = sample.fileName();
        var dng = ORACLE.resolve(name.substring(0, name.lastIndexOf('.')) + ".dng");
        Skip.unless(Files.isRegularFile(dng), "data:raw-oracle", "no oracle file " + dng + "; run tools/testdata/raw-oracle.py");
        try (var source = MediaSource.of(sample.fetch()); var oracle = MediaSource.of(dng)) {
            var converter = grid(com.github.auties00.smalt.image.Images.decode(MediaSource.of(
                    RawDecoder.extractPreview(oracle).orElseThrow())).picture());
            // the default rendering has the generic camera matrix: only its structure is compared
            var standard = grid(RawDecoder.decode(source).picture());
            var correlation = correlation(standard[1], converter[1]);
            assertTrue(correlation > 0.95, "the rendering's structure differs from the converter's: " + correlation);
            // with the converter's own D65 matrix, read from its file at run time, the balance must match too
            var color = RawParser.parse(oracle, MediaLimits.DEFAULT).color;
            var matrix = color.colorMatrix[1] != null ? color.colorMatrix[1] : color.colorMatrix[0];
            var matched = grid(RawDecoder.decode(source, RawDecoderOptions.builder().colorMatrix(matrix).build()).picture());
            correlation = correlation(matched[1], converter[1]);
            assertTrue(correlation > 0.98, "the rendering's structure differs from the converter's: " + correlation);
            for (var channel = 0; channel < 3; channel += 2) {
                var ours = mean(matched[channel]) / mean(matched[1]);
                var theirs = mean(converter[channel]) / mean(converter[1]);
                assertEquals(theirs, ours, 0.1, "the balance of channel " + channel + " to green");
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = RawSamples.class, names = {"X3F_DP1", "X3F_SD14", "X3F_SD15"})
    @DisplayName("an X3F file's image area is the converter's, and its layers map linearly onto the converter's values")
    void x3fArea(RawSamples sample) {
        var name = sample.fileName();
        var dng = ORACLE.resolve(name.substring(0, name.lastIndexOf('.')) + ".dng");
        Skip.unless(Files.isRegularFile(dng), "data:raw-oracle", "no oracle file " + dng + "; run tools/testdata/raw-oracle.py");
        try (var source = MediaSource.of(sample.fetch()); var oracle = MediaSource.of(dng)) {
            var ours = RawDecoder.decodeRaw(source);
            var theirs = RawDecoder.decodeRaw(oracle);
            assertEquals(3, ours.channels());
            assertEquals(theirs.channels(), ours.channels());
            assertEquals(theirs.width(), ours.width());
            assertEquals(theirs.height(), ours.height());
            // the converter stores denoised, color-converted values, not the layers: each of its channels must be,
            // over 16x16 block means, an affine combination of the three layers
            var layers = blocks(ours.samples(), ours.width(), ours.height());
            var converted = blocks(theirs.samples(), theirs.width(), theirs.height());
            for (var channel = 0; channel < 3; channel++) {
                var fit = determination(layers, converted[channel]);
                assertTrue(fit > 0.995, "channel " + channel + " fits the layers with R^2 " + fit);
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = RawSamples.class, names = {"X3F_DP1", "X3F_SD14", "X3F_SD15"})
    @DisplayName("an X3F file's rendering, with the camera's own colors, matches the converter's in structure and balance")
    void x3fRendering(RawSamples sample) {
        var name = sample.fileName();
        var dng = ORACLE.resolve(name.substring(0, name.lastIndexOf('.')) + ".dng");
        Skip.unless(Files.isRegularFile(dng), "data:raw-oracle", "no oracle file " + dng + "; run tools/testdata/raw-oracle.py");
        try (var source = MediaSource.of(sample.fetch()); var oracle = MediaSource.of(dng)) {
            var converter = grid(com.github.auties00.smalt.image.Images.decode(MediaSource.of(
                    RawDecoder.extractPreview(oracle).orElseThrow())).picture());
            var ours = grid(RawDecoder.decode(source).picture());
            for (var channel = 0; channel < 3; channel++) {
                var correlation = correlation(ours[channel], converter[channel]);
                assertTrue(correlation > 0.97, "channel " + channel + " correlates with " + correlation);
            }
            // the DP1's blue to green ratio is the farthest, 1.26 against the converter's 1.15
            for (var channel = 0; channel < 3; channel += 2) {
                var balance = mean(ours[channel]) / mean(ours[1]);
                var expected = mean(converter[channel]) / mean(converter[1]);
                assertEquals(expected, balance, 0.12, "the balance of channel " + channel + " to green");
            }
        }
    }

    // the means of each channel over 16x16 blocks
    private static double[][] blocks(short[] samples, int width, int height) {
        var columns = width / 16;
        var rows = height / 16;
        var means = new double[3][columns * rows];
        for (var y = 0; y < rows * 16; y++) {
            for (var x = 0; x < columns * 16; x++) {
                for (var channel = 0; channel < 3; channel++) {
                    means[channel][(y / 16) * columns + x / 16] += (samples[(y * width + x) * 3 + channel] & 0xFFFF) / 256.0;
                }
            }
        }
        return means;
    }

    // the coefficient of determination of the least-squares affine fit of a target onto three inputs
    private static double determination(double[][] inputs, double[] target) {
        var normal = new double[4][5];
        for (var index = 0; index < target.length; index++) {
            var row = new double[]{inputs[0][index], inputs[1][index], inputs[2][index], 1};
            for (var i = 0; i < 4; i++) {
                for (var j = 0; j < 4; j++) {
                    normal[i][j] += row[i] * row[j];
                }
                normal[i][4] += row[i] * target[index];
            }
        }
        for (var i = 0; i < 4; i++) {
            var pivot = i;
            for (var k = i + 1; k < 4; k++) {
                if (Math.abs(normal[k][i]) > Math.abs(normal[pivot][i])) {
                    pivot = k;
                }
            }
            var swap = normal[i];
            normal[i] = normal[pivot];
            normal[pivot] = swap;
            for (var k = 0; k < 4; k++) {
                if (k != i) {
                    var factor = normal[k][i] / normal[i][i];
                    for (var j = i; j < 5; j++) {
                        normal[k][j] -= factor * normal[i][j];
                    }
                }
            }
        }
        var mean = mean(target);
        var residual = 0.0;
        var total = 0.0;
        for (var index = 0; index < target.length; index++) {
            var fitted = normal[3][4] / normal[3][3];
            for (var i = 0; i < 3; i++) {
                fitted += normal[i][4] / normal[i][i] * inputs[i][index];
            }
            residual += (target[index] - fitted) * (target[index] - fitted);
            total += (target[index] - mean) * (target[index] - mean);
        }
        return 1 - residual / total;
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = RawSamples.class, names = {"CRW_G1", "NEF_E5700", "SRF_F828"})
    @DisplayName("a four-color DNG file renders like the converter's preview")
    void fourColors(RawSamples sample) {
        var name = sample.fileName();
        var dng = ORACLE.resolve(name.substring(0, name.lastIndexOf('.')) + ".dng");
        Skip.unless(Files.isRegularFile(dng), "data:raw-oracle", "no oracle file " + dng + "; run tools/testdata/raw-oracle.py");
        try (var source = MediaSource.of(dng)) {
            var rendered = grid(com.github.auties00.smalt.image.raw.RawDecoder.decode(source).picture());
            var preview = grid(com.github.auties00.smalt.image.Images.decode(MediaSource.of(
                    com.github.auties00.smalt.image.raw.RawDecoder.extractPreview(source).orElseThrow())).picture());
            assertTrue(correlation(rendered[1], preview[1]) > 0.99, "the rendering's structure differs from the preview's");
            for (var channel = 0; channel < 3; channel += 2) {
                var ours = mean(rendered[channel]) / mean(rendered[1]);
                var theirs = mean(preview[channel]) / mean(preview[1]);
                assertEquals(theirs, ours, 0.1, "the balance of channel " + channel + " to green");
            }
        }
    }

    // the red, green and blue values of a 48 by 32 reduction of a picture
    private static double[][] grid(com.github.auties00.smalt.image.Picture picture) {
        var small = com.github.auties00.smalt.image.ops.ImageOps.toRgba(com.github.auties00.smalt.image.ops.ImageOps.resize(picture,
                48, 32, com.github.auties00.smalt.image.ops.ResizeFilter.AREA));
        var values = new double[3][48 * 32];
        var pixels = small.pixels();
        for (var y = 0; y < 32; y++) {
            for (var x = 0; x < 48; x++) {
                for (var channel = 0; channel < 3; channel++) {
                    values[channel][y * 48 + x] = pixels[y * small.stride() + x * 4 + channel] & 0xFF;
                }
            }
        }
        return values;
    }

    private static double mean(double[] values) {
        return java.util.Arrays.stream(values).average().orElse(0);
    }

    private static double correlation(double[] a, double[] b) {
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

    // the offset, of at most eight photosites each way, at which every oracle sample equals the decoded one
    private static int[] locate(SensorPlane decoded, SensorPlane oracle) {
        var spp = oracle.samplesPerPixel;
        for (var y = 0; y <= Math.min(8, decoded.height - oracle.height); y++) {
            for (var x = 0; x <= Math.min(8, decoded.width - oracle.width); x++) {
                var matches = true;
                for (var row = 0; row < oracle.height && matches; row++) {
                    var from = ((row + y) * decoded.width + x) * spp;
                    matches = java.util.Arrays.equals(decoded.integers, from, from + oracle.width * spp, oracle.integers,
                            row * oracle.width * spp, (row + 1) * oracle.width * spp);
                }
                if (matches) {
                    return new int[]{x, y};
                }
            }
        }
        return null;
    }
}
