package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.image.ops.ResizeFilter;
import com.github.auties00.smalt.image.raw.CfaColor;
import com.github.auties00.smalt.image.raw.RawDecoder;
import com.github.auties00.smalt.image.raw.RawDecoderOptions;
import com.github.auties00.smalt.image.raw.RawSamples;
import com.github.auties00.smalt.io.MediaSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the four-color camera matrices of {@link FourColorMatrices}: that
 * {@link FourColorMatrixDerivation} re-derives the fitted ones from the pinned CC0 samples, that the
 * samples render by default close to the camera's own JPEG preview (in structure, channel balance and
 * CIELAB chroma, and closer in chroma than with a nominal matrix of the filter colors), and how the
 * generic matrices of unknown models are built.
 */
class FourColorMatricesTest {
    private static final int GRID_WIDTH = 48;

    private static final int GRID_HEIGHT = 32;

    private static final double[] D65_XYZ = {0.95047, 1, 1.08883};

    private static final double[] SRGB_TO_XYZ = {
            0.4124564, 0.3575761, 0.1804375,
            0.2126729, 0.7151522, 0.0721750,
            0.0193339, 0.1191920, 0.9503041
    };

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = RawSamples.class, names = {"CRW_G1", "NEF_E5700"})
    @DisplayName("every fitted matrix is re-derived from its sample against the embedded preview")
    void derivation(RawSamples sample) {
        var fit = FourColorMatrixDerivation.fit(sample.fetch());
        try (var source = MediaSource.of(sample.fetch())) {
            var file = RawParser.parse(source, MediaLimits.DEFAULT);
            var entry = FourColorMatrices.find(file.make, file.model);
            assertNotNull(entry, file.make + " " + file.model);
            assertArrayEquals(entry.planes(), fit.planes());
            // the table holds the fit of an x64 JVM rounded to four decimals; the single-precision rendering the
            // refinement measures rounds differently on other platforms, which moves the fit by about 1e-3
            assertArrayEquals(entry.d65(), fit.d65(), 3e-3, Arrays.toString(fit.d65()));
            assertTrue(fit.patches() > 300, fit.patches() + " patches");
            for (var determination : fit.determination()) {
                assertTrue(determination > 0.9, "the linear fit explains " + determination + " of a color's variance");
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = RawSamples.class, names = {"CRW_G1", "NEF_E5700"})
    @DisplayName("a file of a fitted model gets its matrix under D65 and reports calibrated colors")
    void complete(RawSamples sample) {
        try (var source = MediaSource.of(sample.fetch())) {
            var file = RawParser.parse(source, MediaLimits.DEFAULT);
            var entry = FourColorMatrices.find(file.make, file.model);
            assertArrayEquals(entry.d65(), file.color.colorMatrix[0]);
            assertNull(file.color.colorMatrix[1]);
            assertEquals(21, file.color.illuminant[0]);
            assertFalse(file.color.generic);
            assertNull(file.color.asShotNeutral);
            assertTrue(file.sensor.estimateBalance);
            var info = RawDecoder.info(source);
            assertTrue(info.isDecodable(), info.unsupportedFeature().orElse(""));
            assertTrue(info.isColorCalibrated());
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = RawSamples.class, names = {"CRW_G1", "NEF_E5700"})
    @DisplayName("the default rendering matches the camera's preview in structure, balance and chroma")
    void rendering(RawSamples sample) {
        try (var source = MediaSource.of(sample.fetch())) {
            var preview = grid(Images.decode(MediaSource.of(RawDecoder.extractPreview(source).orElseThrow())).picture());
            var rendered = grid(RawDecoder.decode(source).picture());
            assertTrue(correlation(luminance(rendered), luminance(preview)) > 0.97,
                    "structure " + correlation(luminance(rendered), luminance(preview)));
            for (var channel = 0; channel < 3; channel += 2) {
                assertEquals(mean(preview, 1) > 0 ? mean(preview, channel) / mean(preview, 1) : 0,
                        mean(rendered, channel) / mean(rendered, 1), 0.1, "the balance of channel " + channel + " to green");
            }
            var fitted = chromaDistance(rendered, preview);
            var file = RawParser.parse(source, MediaLimits.DEFAULT);
            var planes = planes(file);
            var nominal = new double[12];
            for (var plane = 0; plane < 4; plane++) {
                System.arraycopy(nominalRow(planes[plane]), 0, nominal, plane * 3, 3);
            }
            var withNominal = grid(RawDecoder.decode(source, RawDecoderOptions.builder().colorMatrix(nominal).build())
                    .picture());
            var other = chromaDistance(withNominal, preview);
            assertTrue(fitted < 4.5, "mean chroma distance " + fitted + " to the preview (nominal matrix " + other + ")");
            assertTrue(fitted < other, "mean chroma distance " + fitted + " with the fitted matrix, " + other
                    + " with the nominal one");
        }
    }

    @Test
    @DisplayName("an unknown four-color model gets the generic matrix of its filter colors and reports approximate colors")
    void unknownModel() {
        try (var source = MediaSource.of(RawSamples.NEF_E5700.fetch())) {
            var file = RawParser.parse(source, MediaLimits.DEFAULT);
            file.model = "E9999";
            file.color.colorMatrix[0] = null;
            file.color.illuminant[0] = 0;
            FourColorMatrices.complete(file);
            assertTrue(file.color.generic);
            assertEquals(21, file.color.illuminant[0]);
            assertArrayEquals(FourColorMatrices.generic(planes(file)), file.color.colorMatrix[0]);
            var color = CameraColor.of(file.color, com.github.auties00.smalt.image.raw.RawWhiteBalance.asShot(), null, 4);
            assertFalse(color.calibrated);
        }
    }

    @Test
    @DisplayName("a file with matrices of its own keeps them")
    void keepsOwnMatrices() {
        try (var source = MediaSource.of(RawSamples.NEF_E5700.fetch())) {
            var file = RawParser.parse(source, MediaLimits.DEFAULT);
            var own = new double[]{0.4, 0.6, -0.1, 0.1, 0.9, 0, 0.6, 0.2, 0.3, -0.1, 0.4, 0.7};
            file.color.colorMatrix[0] = own.clone();
            file.color.illuminant[0] = 17;
            FourColorMatrices.complete(file);
            assertArrayEquals(own, file.color.colorMatrix[0]);
            assertEquals(17, file.color.illuminant[0]);
            assertFalse(file.color.generic);
        }
    }

    @Test
    @DisplayName("the generic cyan, magenta, yellow and green rows are the normalized means of the fitted rows, in any plane order")
    void genericCmyg() {
        var order = new CfaColor[]{CfaColor.CYAN, CfaColor.MAGENTA, CfaColor.YELLOW, CfaColor.GREEN};
        var matrix = FourColorMatrices.generic(order);
        var g1 = FourColorMatrices.find("Canon", "PowerShot G1");
        var e5700 = FourColorMatrices.find("Nikon", "E5700");
        for (var plane = 0; plane < 4; plane++) {
            var expected = new double[3];
            for (var entry : new FourColorMatrices.Entry[]{g1, e5700}) {
                var row = rowOf(entry, order[plane]);
                var sum = row[0] + row[1] + row[2];
                for (var column = 0; column < 3; column++) {
                    expected[column] += row[column] / sum / 2;
                }
            }
            assertArrayEquals(expected, Arrays.copyOfRange(matrix, plane * 3, plane * 3 + 3), 1e-12, order[plane].toString());
        }
        var swapped = FourColorMatrices.generic(new CfaColor[]{CfaColor.GREEN, CfaColor.YELLOW, CfaColor.MAGENTA,
                CfaColor.CYAN});
        for (var plane = 0; plane < 4; plane++) {
            assertArrayEquals(Arrays.copyOfRange(matrix, (3 - plane) * 3, (3 - plane) * 3 + 3),
                    Arrays.copyOfRange(swapped, plane * 3, plane * 3 + 3), 0);
        }
        assertNotNull(ColorMath.pseudoInverse(matrix, 4), "the generic matrix has full column rank");
    }

    @Test
    @DisplayName("filters of another set take the generic rows of the primaries they pass")
    void genericNominal() {
        var planes = new CfaColor[]{CfaColor.RED, CfaColor.GREEN, CfaColor.BLUE, CfaColor.WHITE};
        var matrix = FourColorMatrices.generic(planes);
        assertArrayEquals(CameraColor.GENERIC_XYZ_TO_CAMERA, Arrays.copyOfRange(matrix, 0, 9), 0);
        for (var column = 0; column < 3; column++) {
            assertEquals(matrix[column] + matrix[3 + column] + matrix[6 + column], matrix[9 + column], 1e-12);
        }
        var mixed = FourColorMatrices.generic(new CfaColor[]{CfaColor.CYAN, CfaColor.MAGENTA, CfaColor.YELLOW,
                CfaColor.WHITE});
        assertArrayEquals(nominalRow(CfaColor.CYAN), Arrays.copyOfRange(mixed, 0, 3), 1e-12);
        assertArrayEquals(nominalRow(CfaColor.WHITE), Arrays.copyOfRange(mixed, 9, 12), 1e-12);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = RawSamples.class, names = {"CRW_G1", "NEF_E5700"})
    @DisplayName("a four-color neutral is estimated from the preview, else from every unclipped cell, largest component 1")
    void fourColorNeutral(RawSamples sample) {
        try (var source = MediaSource.of(sample.fetch())) {
            var file = RawParser.parse(source, MediaLimits.DEFAULT);
            var linear = Renderer.linearize(file, file.sensor, true);
            var window = Renderer.window(file.sensor, linear, true);
            PreviewBalance.apply(file, linear, window);
            var fromPreview = file.color.asShotNeutral;
            assertNotNull(fromPreview);
            assertEquals(4, fromPreview.length);
            assertEquals(1, Arrays.stream(fromPreview).max().orElseThrow(), 0);
            assertTrue(Arrays.stream(fromPreview).allMatch(value -> value > 0), Arrays.toString(fromPreview));

            file.color.asShotNeutral = null;
            file.previews.clear();
            var cells = PreviewBalance.cells(file, linear, window);
            assertNull(cells.preview());
            var expected = new double[4];
            for (var cell = 0; cell < cells.clipped().length; cell++) {
                for (var plane = 0; plane < 4 && !cells.clipped()[cell]; plane++) {
                    if (!Double.isNaN(cells.means()[cell * 4 + plane])) {
                        expected[plane] += cells.means()[cell * 4 + plane];
                    }
                }
            }
            var largest = Arrays.stream(expected).max().orElseThrow();
            for (var plane = 0; plane < 4; plane++) {
                expected[plane] /= largest;
            }
            PreviewBalance.apply(file, linear, window);
            assertArrayEquals(expected, file.color.asShotNeutral, 1e-12);
            assertFalse(Arrays.equals(fromPreview, file.color.asShotNeutral), "the preview selects the neutral cells");
        }
    }

    @Test
    @DisplayName("a cell whose mean is below black still counts as holding every color plane")
    void negativeCells() {
        try (var source = MediaSource.of(RawSamples.NEF_E5700.fetch())) {
            var file = RawParser.parse(source, MediaLimits.DEFAULT);
            var linear = Renderer.linearize(file, file.sensor, true);
            Arrays.fill(linear.data, -0.001f);
            var cells = PreviewBalance.cells(file, linear, Renderer.window(file.sensor, linear, true));
            for (var cell = 0; cell < cells.clipped().length; cell++) {
                assertTrue(cells.complete(cell), "cell " + cell);
                assertEquals(-0.001, cells.means()[cell * 4], 1e-6);
            }
        }
    }

    private static double[] rowOf(FourColorMatrices.Entry entry, CfaColor color) {
        for (var plane = 0; plane < 4; plane++) {
            if (entry.planes()[plane] == color) {
                return Arrays.copyOfRange(entry.d65(), plane * 3, plane * 3 + 3);
            }
        }
        throw new AssertionError(color + " is not a color of " + entry.model());
    }

    // the sum of the generic rows of the primaries a filter passes
    static double[] nominalRow(CfaColor color) {
        var passes = switch (color) {
            case RED -> new int[]{0};
            case GREEN -> new int[]{1};
            case BLUE -> new int[]{2};
            case CYAN -> new int[]{1, 2};
            case MAGENTA -> new int[]{0, 2};
            case YELLOW -> new int[]{0, 1};
            case WHITE -> new int[]{0, 1, 2};
        };
        var row = new double[3];
        for (var primary : passes) {
            for (var column = 0; column < 3; column++) {
                row[column] += CameraColor.GENERIC_XYZ_TO_CAMERA[primary * 3 + column];
            }
        }
        return row;
    }

    static CfaColor[] planes(RawFile file) {
        var planes = new CfaColor[4];
        for (var color : file.sensor.cfa.colors()) {
            planes[file.sensor.planeOfColor[color.code()]] = color;
        }
        return planes;
    }

    // the 8-bit red, green and blue of each cell of a 48 by 32 reduction, three per cell
    static double[] grid(Picture picture) {
        var small = ImageOps.toRgba(ImageOps.resize(picture, GRID_WIDTH, GRID_HEIGHT, ResizeFilter.AREA));
        var values = new double[GRID_WIDTH * GRID_HEIGHT * 3];
        var pixels = small.pixels();
        for (var y = 0; y < GRID_HEIGHT; y++) {
            for (var x = 0; x < GRID_WIDTH; x++) {
                for (var channel = 0; channel < 3; channel++) {
                    values[(y * GRID_WIDTH + x) * 3 + channel] = pixels[y * small.stride() + x * 4 + channel] & 0xFF;
                }
            }
        }
        return values;
    }

    private static double[] luminance(double[] grid) {
        var values = new double[grid.length / 3];
        for (var cell = 0; cell < values.length; cell++) {
            values[cell] = 0.2126 * grid[cell * 3] + 0.7152 * grid[cell * 3 + 1] + 0.0722 * grid[cell * 3 + 2];
        }
        return values;
    }

    private static double mean(double[] grid, int channel) {
        var sum = 0.0;
        for (var cell = 0; cell < grid.length / 3; cell++) {
            sum += grid[cell * 3 + channel];
        }
        return sum / (grid.length / 3.0);
    }

    // the mean CIELAB a*b* distance of the cells both pictures show neither nearly black nor nearly white
    static double chromaDistance(double[] rendered, double[] preview) {
        var sum = 0.0;
        var count = 0;
        for (var cell = 0; cell < rendered.length / 3; cell++) {
            var ours = lab(rendered, cell);
            var theirs = lab(preview, cell);
            if (theirs[0] < 10 || theirs[0] > 90 || ours[0] < 10 || ours[0] > 90) {
                continue;
            }
            sum += Math.hypot(ours[1] - theirs[1], ours[2] - theirs[2]);
            count++;
        }
        assertTrue(count > GRID_WIDTH * GRID_HEIGHT / 4, count + " cells compared");
        return sum / count;
    }

    private static double[] lab(double[] grid, int cell) {
        var rgb = new double[3];
        for (var channel = 0; channel < 3; channel++) {
            var value = grid[cell * 3 + channel] / 255;
            rgb[channel] = value <= 0.04045 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
        }
        return CameraMatrixDerivation.lab(ColorMath.transform(SRGB_TO_XYZ, rgb), D65_XYZ);
    }

    private static double correlation(double[] a, double[] b) {
        var meanA = Arrays.stream(a).average().orElse(0);
        var meanB = Arrays.stream(b).average().orElse(0);
        var covariance = 0.0;
        var varianceA = 0.0;
        var varianceB = 0.0;
        for (var index = 0; index < a.length; index++) {
            covariance += (a[index] - meanA) * (b[index] - meanB);
            varianceA += (a[index] - meanA) * (a[index] - meanA);
            varianceB += (b[index] - meanB) * (b[index] - meanB);
        }
        return covariance / Math.sqrt(varianceA * varianceB);
    }
}
