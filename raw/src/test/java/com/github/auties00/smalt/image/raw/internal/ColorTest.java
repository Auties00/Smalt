package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.raw.RawColorSpace;
import com.github.auties00.smalt.image.raw.RawSamples;
import com.github.auties00.smalt.image.raw.RawWhiteBalance;
import com.github.auties00.smalt.io.MediaSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Checks the color science: temperature conversions, white point adaptation, the output matrices,
 * the DNG camera model, and the provenance of the generic camera matrix.
 */
class ColorTest {
    @ParameterizedTest(name = "{0} K")
    @ValueSource(doubles = {2856, 3200, 4000, 5003, 5500, 6504, 7500, 10000})
    @DisplayName("Robertson's temperature of a locus chromaticity returns the temperature")
    void temperature(double kelvin) {
        assertEquals(kelvin, ColorMath.temperature(ColorMath.chromaticity(kelvin)), kelvin * 0.01);
    }

    @Test
    @DisplayName("D65 and illuminant A have their correlated color temperatures")
    void standardIlluminants() {
        assertEquals(6504, ColorMath.temperature(ColorMath.D65_XY), 15);
        assertEquals(2856, ColorMath.temperature(new double[]{0.44757, 0.40745}), 10);
    }

    @Test
    @DisplayName("Bradford adaptation maps one white to the other and is the identity on equal whites")
    void adaptation() {
        var matrix = ColorMath.adaptation(ColorMath.D65_XY, ColorMath.D50_XY);
        var white = ColorMath.apply(matrix, ColorMath.xyToXyz(ColorMath.D65_XY));
        assertArrayEquals(ColorMath.xyToXyz(ColorMath.D50_XY), white, 1e-9);
        assertArrayEquals(ColorMath.identity(), ColorMath.adaptation(ColorMath.D50_XY, ColorMath.D50_XY), 1e-12);
    }

    @Test
    @DisplayName("a 4x4 matrix inverts and a 4x3 matrix has a left pseudo-inverse")
    void fourColorAlgebra() {
        var square = new double[]{2, 1, 0, 0.5, 0.1, 3, 1, 0, 0, 0.2, 1.5, 1, 1, 0, 0.3, 2};
        assertArrayEquals(ColorMath.diagonalOf(ColorMath.filled(4, 1)),
                ColorMath.multiply(ColorMath.invert(square, 4), 4, square, 4), 1e-12);
        var tall = new double[]{0.9, 0.2, -0.1, 0.4, 0.8, 0.3, -0.2, 0.5, 1.1, 0.6, 0.6, 0.1};
        assertArrayEquals(ColorMath.identity(), ColorMath.multiply(ColorMath.pseudoInverse(tall, 4), 3, tall, 4), 1e-12);
        assertArrayEquals(ColorMath.invert(ColorMath.identity()), ColorMath.pseudoInverse(ColorMath.identity(), 3));
    }

    @Test
    @DisplayName("the D50 to linear sRGB matrix matches the published Bradford-adapted one")
    void srgbMatrix() {
        var expected = new double[]{3.1338561, -1.6168667, -0.4906146, -0.9787684, 1.9161415, 0.0334540,
                0.0719453, -0.2289914, 1.4052427};
        assertArrayEquals(expected, ColorPipeline.outputFromPcs(RawColorSpace.SRGB), 2e-3);
    }

    @Test
    @DisplayName("the camera neutral of a DNG maps to the D50 white with a luminance of 1")
    void neutralMapsToWhite() {
        try (var source = MediaSource.of(RawSamples.ADC_5D3_LOSSLESS.fetch())) {
            var file = RawParser.parse(source, MediaLimits.DEFAULT);
            var color = CameraColor.of(file.color, RawWhiteBalance.asShot(), null);
            assertArrayEquals(ColorMath.D50_XYZ, ColorMath.apply(color.balancedToPcs, new double[]{1, 1, 1}), 2e-3);
            assertArrayEquals(new double[]{0.606276, 1, 0.461885}, color.neutral, 1e-6);
        }
    }

    static final double[] MATRIX_A = {1.2, -0.3, -0.1, -0.4, 1.3, 0.1, -0.1, 0.3, 0.6};
    static final double[] MATRIX_D65 = {0.9, -0.2, -0.1, -0.3, 1.2, 0.1, -0.05, 0.15, 0.7};
    static final double[] MATRIX_THIRD = {1.0, -0.25, -0.05, -0.35, 1.25, 0.1, -0.08, 0.2, 0.65};

    static CameraColor temperature(ColorCalibration color, double kelvin) {
        return CameraColor.of(color, RawWhiteBalance.temperature(kelvin), null);
    }

    static ColorCalibration threeIlluminants() {
        var color = new ColorCalibration();
        color.colorMatrix[0] = MATRIX_A;
        color.colorMatrix[1] = MATRIX_D65;
        color.colorMatrix[2] = MATRIX_THIRD;
        color.illuminant[0] = 17;
        color.illuminant[1] = 21;
        color.illuminant[2] = 255;
        color.illuminantXy[2] = ColorMath.chromaticity(4200);
        return color;
    }

    @Test
    @DisplayName("a third calibration illuminant given by chromaticity takes all the weight at its own temperature")
    void thirdIlluminant() {
        var color = threeIlluminants();
        var at = ColorMath.temperature(color.illuminantXy[2]);
        var mapping = temperature(color, at);
        assertArrayEquals(new double[]{0, 0, 1}, mapping.weights, 2e-3);
        var single = new ColorCalibration();
        single.colorMatrix[0] = MATRIX_THIRD;
        single.illuminant[0] = 255;
        single.illuminantXy[0] = color.illuminantXy[2];
        assertArrayEquals(temperature(single, at).balancedToPcs, mapping.balancedToPcs, 1e-3);
    }

    @ParameterizedTest(name = "{0} K")
    @ValueSource(doubles = {2000, 3000, 5000, 9000})
    @DisplayName("three illuminants interpolate between the two that bracket the white, or clamp outside them")
    void threeWayWeights(double kelvin) {
        var weights = temperature(threeIlluminants(), kelvin).weights;
        assertEquals(1, weights[0] + weights[1] + weights[2], 1e-9);
        if (kelvin <= 2856) {
            assertArrayEquals(new double[]{1, 0, 0}, weights, 1e-9);
        } else if (kelvin < 4200) {
            assertEquals(0, weights[1], 1e-9);
        } else if (kelvin < 6504) {
            assertEquals(0, weights[0], 1e-9);
        } else {
            assertArrayEquals(new double[]{0, 1, 0}, weights, 1e-9);
        }
    }

    @Test
    @DisplayName("the chromaticity of spectra integrates against the CIE 1931 observer")
    void spectrum() {
        var flat = new double[]{1, 1};
        assertArrayEquals(new double[]{1 / 3.0, 1 / 3.0}, ColorMath.spectrumXy(flat, 360, 470), 2e-3);
        var planck = new double[95];
        for (var index = 0; index < planck.length; index++) {
            var meters = (360 + 5 * index) * 1e-9;
            planck[index] = 1 / (Math.pow(meters, 5) * (Math.exp(1.4388e-2 / (meters * 2856)) - 1));
        }
        assertArrayEquals(new double[]{0.44757, 0.40745}, ColorMath.spectrumXy(planck, 360, 5), 2e-3);
    }

    @Test
    @DisplayName("IlluminantData gives an x-y chromaticity or a spectrum")
    void illuminantData() {
        var xy = java.nio.ByteBuffer.allocate(18).order(java.nio.ByteOrder.LITTLE_ENDIAN).putShort((short) 0)
                .putInt(3127).putInt(10000).putInt(3290).putInt(10000).array();
        assertArrayEquals(new double[]{0.3127, 0.329}, DngParser.illuminantData(xy, true), 1e-9);
        var spectral = java.nio.ByteBuffer.allocate(22 + 16).order(java.nio.ByteOrder.BIG_ENDIAN).putShort((short) 1)
                .putInt(2).putInt(360).putInt(1).putInt(470).putInt(1).putInt(5).putInt(1).putInt(5).putInt(1).array();
        assertArrayEquals(new double[]{1 / 3.0, 1 / 3.0}, DngParser.illuminantData(spectral, false), 2e-3);
        assertEquals(null, DngParser.illuminantData(new byte[4], true));
    }

    @Test
    @DisplayName("CameraCalibration applies only when its signature matches the profile's")
    void calibrationSignature() {
        var dng = com.github.auties00.smalt.image.raw.TinyDng.ramp(8, 8);
        dng.cameraCalibration = new double[]{2, 0, 0, 0, 1, 0, 0, 0, 1};
        dng.cameraSignature = "camera A";
        dng.profileSignature = "camera A";
        assertArrayEquals(dng.cameraCalibration, parse(dng.write()).cameraCalibration[0], 1e-9);
        dng.profileSignature = "camera B";
        assertEquals(null, parse(dng.write()).cameraCalibration[0]);
        dng.cameraSignature = null;
        dng.profileSignature = null;
        assertArrayEquals(dng.cameraCalibration, parse(dng.write()).cameraCalibration[0], 1e-9);
    }

    static ColorCalibration parse(byte[] dng) {
        try (var source = MediaSource.of(dng)) {
            return RawParser.parse(source, MediaLimits.DEFAULT).color;
        }
    }

    @Test
    @DisplayName("the generic camera matrix is the median of seven CC0 camera DNG matrices")
    void genericMatrixProvenance() {
        var samples = new RawSamples[]{RawSamples.DNG_PENTAX_KX, RawSamples.DNG_PENTAX_K5II, RawSamples.DNG_PENTAX_K3,
                RawSamples.DNG_RICOH_GR, RawSamples.DNG_SAMSUNG_GX20, RawSamples.DNG_LEICA_M9, RawSamples.DNG_LEICA_X2};
        var matrices = new ArrayList<double[]>();
        for (var sample : samples) {
            try (var source = MediaSource.of(sample.fetch())) {
                var color = RawParser.parse(source, MediaLimits.DEFAULT).color;
                var index = color.illuminant[1] == 21 ? 1 : 0;
                var matrix = color.colorMatrix[index].clone();
                for (var row = 0; row < 3; row++) {
                    var sum = matrix[3 * row] + matrix[3 * row + 1] + matrix[3 * row + 2];
                    for (var column = 0; column < 3; column++) {
                        matrix[3 * row + column] /= sum;
                    }
                }
                matrices.add(matrix);
            }
        }
        var median = new double[9];
        for (var element = 0; element < 9; element++) {
            var values = new double[matrices.size()];
            for (var i = 0; i < values.length; i++) {
                values[i] = matrices.get(i)[element];
            }
            java.util.Arrays.sort(values);
            median[element] = values[values.length / 2];
        }
        assertArrayEquals(median, CameraColor.GENERIC_XYZ_TO_CAMERA, 1e-3);
    }
}
