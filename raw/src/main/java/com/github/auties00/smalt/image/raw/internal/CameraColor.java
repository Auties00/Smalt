package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.raw.RawWhiteBalance;

import java.util.Arrays;

/**
 * Maps the camera's color space to the CIE XYZ profile connection space (D50) for one white
 * balance, following the DNG specification (chapter 6, "Mapping Camera Color Space to CIE XYZ
 * Space").
 *
 * <p>The result is the camera neutral (the camera's response to a neutral under the chosen light,
 * scaled so its largest component is 1) and the matrix that maps white-balanced camera values,
 * those divided by the neutral, to XYZ: it maps {@code (1, 1, 1)} to the D50 white with a
 * luminance of 1, so the level at which the most sensitive channel saturates on a neutral renders
 * as white. A camera of four colors has a neutral of four components and a {@code 3 x 4} matrix;
 * the inverse of its XYZ to camera matrix is the pseudo-inverse, as the specification defines it.
 *
 * @implNote This implementation interpolates the color matrices, camera calibrations, forward
 * matrices and hue, saturation and value maps between the calibration illuminants
 * linearly in inverse correlated color temperature, and finds the white of a camera
 * neutral by fixed-point iteration from D50, as the specification describes. With three
 * illuminants (DNG 1.6), for which the specification fixes no interpolation, it
 * interpolates between the two whose temperatures bracket the white's.
 */
final class CameraColor {
    /**
     * The most iterations spent finding the white of a camera neutral.
     */
    private static final int MAX_ITERATIONS = 30;

    /**
     * The XYZ to camera matrix (D65 calibration) of a generic camera, for files that carry no color
     * matrix and whose model {@link CameraMatrices} does not know.
     *
     * @implNote This implementation derives the matrix as the element-wise median of the D65
     * {@code ColorMatrix} of seven camera-produced DNG files published under CC0 on
     * raw.pixls.us (Pentax K-x, K-5 II and K-3, Ricoh GR, Samsung GX20, Leica M9 and X2),
     * each row scaled to sum to one; a test recomputes it from the files.
     */
    static final double[] GENERIC_XYZ_TO_CAMERA = {
            1.7497, -0.5358, -0.1924,
            -0.3922, 1.2076, 0.2000,
            -0.1348, 0.2363, 0.9025
    };

    /**
     * The camera neutral, largest component 1.
     */
    final double[] neutral;

    /**
     * The matrix mapping white-balanced camera values to XYZ (D50).
     */
    final double[] balancedToPcs;

    /**
     * The chromaticity of the white.
     */
    final double[] whiteXy;

    /**
     * The weight of each calibration illuminant, for the profile tables.
     */
    final double[] weights;

    /**
     * Whether the matrix comes from the file rather than the generic fallback.
     */
    final boolean calibrated;

    /**
     * Constructs a result.
     *
     * @param neutral the camera neutral
     * @param balancedToPcs the white-balanced camera to XYZ matrix
     * @param whiteXy the chromaticity of the white
     * @param weights the weight of each calibration illuminant
     * @param calibrated whether the matrix comes from the file
     */
    private CameraColor(double[] neutral, double[] balancedToPcs, double[] whiteXy, double[] weights, boolean calibrated) {
        this.neutral = neutral;
        this.balancedToPcs = balancedToPcs;
        this.whiteXy = whiteXy;
        this.weights = weights;
        this.calibrated = calibrated;
    }

    /**
     * Computes the camera color mapping of a file of a three-color camera for a white balance.
     *
     * @param color the color description of the file
     * @param balance the white balance
     * @param matrix an XYZ to camera matrix (D65) that replaces the file's own, or {@code null}
     * @return the mapping
     * @throws MediaException.Unsupported if the matrix or the multipliers are not of three colors
     */
    static CameraColor of(ColorCalibration color, RawWhiteBalance balance, double[] matrix) {
        return of(color, balance, matrix, Math.max(3, color.channels()));
    }

    /**
     * Computes the camera color mapping of a file for a white balance.
     *
     * @param color the color description of the file
     * @param balance the white balance
     * @param matrix an XYZ to camera matrix (D65) that replaces the file's own, {@code 3 x 3} or
     * {@code 4 x 3}, or {@code null}
     * @param colors the colors of the camera's sensor, 3 or 4
     * @return the mapping
     * @throws MediaException.Unsupported if the matrix, the file's matrices or the multipliers are
     * not of the sensor's number of colors, or a four-color camera has no matrices
     */
    static CameraColor of(ColorCalibration color, RawWhiteBalance balance, double[] matrix, int colors) {
        if (matrix != null && matrix.length / 3 != colors) {
            throw new MediaException.Unsupported("raw", "a color matrix of " + matrix.length / 3
                    + " colors cannot replace the matrices of a " + colors + "-color camera");
        }
        if (matrix == null && colors == 4 && color.channels() != 4) {
            throw new MediaException.Unsupported("raw", Renderer.NO_FOUR_COLOR_MATRICES);
        }
        if (matrix == null && color.colorMatrix[0] == null && color.cameraToSrgb != null) {
            return embedded(color, balance);
        }
        var model = new Model(color, matrix);
        var n = model.channels;
        double[] neutral;
        double[] xy;
        switch (balance) {
            case RawWhiteBalance.Temperature temperature -> {
                xy = ColorMath.chromaticity(temperature.kelvin());
                neutral = model.neutralOf(xy);
            }
            case RawWhiteBalance.Multipliers multipliers -> {
                if (multipliers.factors().size() != n) {
                    throw new MediaException.Unsupported("raw", multipliers.factors().size()
                            + " multipliers cannot white balance a " + n + "-color camera");
                }
                neutral = inverse(channels(multipliers));
                xy = model.whiteOf(neutral);
            }
            case RawWhiteBalance.AsShot _ -> {
                if (valid(color.asShotNeutral, n)) {
                    neutral = normalize(color.asShotNeutral, n);
                    xy = model.whiteOf(neutral);
                } else if (color.asShotWhiteXy != null && color.asShotWhiteXy.length >= 2
                        && color.asShotWhiteXy[0] > 0 && color.asShotWhiteXy[1] > 0) {
                    xy = color.asShotWhiteXy.clone();
                    neutral = model.neutralOf(xy);
                } else {
                    xy = ColorMath.D65_XY.clone();
                    neutral = model.neutralOf(xy);
                }
            }
        }
        neutral = normalize(neutral, n);
        var weights = model.weights(xy);
        double[] cameraToPcs;
        var forward = model.forward(weights);
        var calibration = model.calibration(weights);
        var uncalibrated = ColorMath.invert(calibration, n);
        if (forward != null && matrix == null && uncalibrated != null) {
            var reference = ColorMath.transform(uncalibrated, neutral);
            var scale = new double[n];
            for (var index = 0; index < n; index++) {
                scale[index] = reference[index] > 1e-9 ? 1 / reference[index] : 1;
            }
            cameraToPcs = ColorMath.multiply(forward, 3, ColorMath.multiply(ColorMath.diagonalOf(scale), n, uncalibrated, n), n);
        } else {
            var cameraToXyz = ColorMath.pseudoInverse(model.xyzToCamera(weights), n);
            if (cameraToXyz == null) {
                throw new MediaException.Malformed("the camera color matrix is singular");
            }
            cameraToPcs = ColorMath.multiply(ColorMath.adaptation(xy, ColorMath.D50_XY), 3, cameraToXyz, 3);
            var white = ColorMath.transform(cameraToPcs, neutral);
            if (white[1] > 1e-9) {
                for (var index = 0; index < cameraToPcs.length; index++) {
                    cameraToPcs[index] /= white[1];
                }
            }
        }
        var balanced = ColorMath.multiply(cameraToPcs, 3, ColorMath.diagonalOf(neutral), n);
        return new CameraColor(neutral, balanced, xy, weights, model.calibrated);
    }

    /**
     * Returns the number of camera colors of the mapping.
     *
     * @return 3, or 4 for a four-color camera
     */
    int channels() {
        return neutral.length;
    }

    /**
     * Returns the matrix that maps the white-balanced camera values of a four-color camera to
     * the three channels the rest of the rendering works in: their D50 XYZ values, each divided by
     * the D50 white's, so that a neutral at the saturation of the most sensitive color maps to
     * {@code (1, 1, 1)}.
     *
     * @return the {@code 3 x 4} matrix
     * @see #projected()
     */
    double[] projection() {
        var result = balancedToPcs.clone();
        var n = channels();
        for (var row = 0; row < 3; row++) {
            for (var column = 0; column < n; column++) {
                result[row * n + column] /= ColorMath.D50_XYZ[row];
            }
        }
        return result;
    }

    /**
     * Returns the mapping of the three channels {@link #projection()} produces: a neutral of
     * {@code (1, 1, 1)} and a matrix that scales each channel back to D50 XYZ.
     *
     * @return the three-color mapping
     */
    CameraColor projected() {
        return new CameraColor(new double[]{1, 1, 1}, ColorMath.diagonal(ColorMath.D50_XYZ), whiteXy, weights, calibrated);
    }

    /**
     * Computes the mapping of a vendor file that embeds a camera to sRGB matrix.
     *
     * @param color the color description
     * @param balance the white balance
     * @return the mapping
     */
    private static CameraColor embedded(ColorCalibration color, RawWhiteBalance balance) {
        double[] neutral;
        if (balance instanceof RawWhiteBalance.Multipliers multipliers) {
            if (multipliers.factors().size() != 3) {
                throw new MediaException.Unsupported("raw", multipliers.factors().size()
                        + " multipliers cannot white balance a 3-color camera");
            }
            neutral = inverse(channels(multipliers));
        } else {
            neutral = valid(color.asShotNeutral, 3) ? color.asShotNeutral : new double[]{1, 1, 1};
        }
        neutral = normalize(neutral, 3);
        var srgbToPcs = ColorMath.multiply(ColorMath.adaptation(ColorMath.D65_XY, ColorMath.D50_XY),
                ColorMath.rgbToXyz(new double[]{0.64, 0.33}, new double[]{0.30, 0.60}, new double[]{0.15, 0.06},
                        ColorMath.D65_XY));
        return new CameraColor(neutral, ColorMath.multiply(srgbToPcs, color.cameraToSrgb), ColorMath.D65_XY.clone(),
                new double[]{0.5, 0.5, 0}, true);
    }

    /**
     * Returns whether a neutral vector is usable.
     *
     * @param neutral the vector
     * @param n the number of camera colors
     * @return whether it holds {@code n} positive finite components
     */
    private static boolean valid(double[] neutral, int n) {
        if (neutral == null || neutral.length < n) {
            return false;
        }
        for (var index = 0; index < n; index++) {
            if (!(neutral[index] > 0) || !Double.isFinite(neutral[index])) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the factors of camera channel multipliers.
     *
     * @param multipliers the white balance holding the factors
     * @return a new array of the three or four factors
     */
    private static double[] channels(RawWhiteBalance.Multipliers multipliers) {
        var factors = multipliers.factors();
        var result = new double[factors.size()];
        for (var index = 0; index < result.length; index++) {
            result[index] = factors.get(index);
        }
        return result;
    }

    /**
     * Returns the reciprocals of multipliers.
     *
     * @param multipliers the camera channel factors
     * @return the reciprocals
     */
    private static double[] inverse(double[] multipliers) {
        var result = new double[multipliers.length];
        for (var index = 0; index < result.length; index++) {
            result[index] = multipliers[index] > 0 ? 1 / multipliers[index] : 1;
        }
        return result;
    }

    /**
     * Scales the first components of a vector so the largest is 1.
     *
     * @param vector the components to scale
     * @param n the number of components to keep
     * @return the scaled copy of {@code n} components
     */
    static double[] normalize(double[] vector, int n) {
        var max = 0.0;
        for (var index = 0; index < n; index++) {
            max = Math.max(max, vector[index]);
        }
        if (!(max > 0) || !Double.isFinite(max)) {
            return ColorMath.filled(n, 1);
        }
        var result = new double[n];
        for (var index = 0; index < n; index++) {
            result[index] = Math.max(vector[index] / max, 1e-4);
        }
        return result;
    }

    /**
     * Interpolates the matrices of a file between its calibration illuminants.
     */
    private static final class Model {
        /**
         * The XYZ to camera matrix of each calibration illuminant used, in illuminant order.
         */
        final double[][] matrices;

        /**
         * The analog balance times the camera calibration of each illuminant.
         */
        final double[][] calibrations;

        /**
         * The forward matrix of each illuminant, or {@code null} when the file has none.
         */
        final double[][] forwards;

        /**
         * The reciprocal correlated color temperature of each illuminant.
         */
        final double[] inverses;

        /**
         * Whether the matrices come from the file.
         */
        final boolean calibrated;

        /**
         * The number of camera colors, the rows of the XYZ to camera matrices.
         */
        final int channels;

        /**
         * Collects the matrices.
         *
         * @param color the color description
         * @param override a matrix that replaces the file's, or {@code null}
         */
        Model(ColorCalibration color, double[] override) {
            channels = override != null ? override.length / 3 : Math.max(3, color.channels());
            var analog = color.analogBalance != null && color.analogBalance.length >= channels
                    ? ColorMath.diagonalOf(Arrays.copyOf(color.analogBalance, channels))
                    : ColorMath.diagonalOf(ColorMath.filled(channels, 1));
            if (override != null) {
                matrices = new double[][]{override};
                calibrations = new double[][]{analog};
                forwards = null;
                inverses = new double[]{1 / 6504.0};
                calibrated = true;
                return;
            }
            calibrated = color.colorMatrix[0] != null && !color.generic;
            if (color.colorMatrix[0] == null) {
                matrices = new double[][]{GENERIC_XYZ_TO_CAMERA};
                calibrations = new double[][]{analog};
                forwards = null;
                inverses = new double[]{1 / 6504.0};
                return;
            }
            var count = color.colorMatrix[1] == null ? 1 : color.colorMatrix[2] == null ? 2 : 3;
            matrices = new double[count][];
            calibrations = new double[count][];
            inverses = new double[count];
            var allForward = true;
            for (var index = 0; index < count; index++) {
                matrices[index] = color.colorMatrix[index];
                var camera = color.cameraCalibration[index];
                calibrations[index] = camera != null && camera.length == channels * channels
                        ? ColorMath.multiply(analog, channels, camera, channels) : analog;
                allForward &= color.forwardMatrix[index] != null;
                var temperature = illuminantTemperature(color, index);
                inverses[index] = temperature > 0 ? 1 / temperature : 1 / (index == 0 ? 2856.0 : 6504.0);
            }
            if (color.forwardMatrix[0] == null) {
                forwards = null;
            } else {
                forwards = new double[count][];
                for (var index = 0; index < count; index++) {
                    forwards[index] = allForward ? color.forwardMatrix[index] : color.forwardMatrix[0];
                }
            }
        }

        /**
         * Returns the correlated color temperature of a calibration illuminant: from its EXIF light
         * source code, or from its chromaticity when the code is 255 (other) and
         * {@code IlluminantData} gives one.
         *
         * @param color the color description
         * @param index the illuminant
         * @return the temperature in kelvin, or {@code 0} when unknown
         */
        private static double illuminantTemperature(ColorCalibration color, int index) {
            if (color.illuminant[index] == 255 && color.illuminantXy[index] != null) {
                return ColorMath.temperature(color.illuminantXy[index]);
            }
            return ColorMath.illuminantTemperature(color.illuminant[index]);
        }

        /**
         * Returns the weight of each calibration illuminant for a white: linear interpolation in
         * reciprocal temperature between the two illuminants whose temperatures bracket the
         * white's, or all the weight on the nearest illuminant outside their range.
         *
         * @param xy the chromaticity of the white
         * @return the weights of illuminants 1 to 3, summing to 1
         */
        double[] weights(double[] xy) {
            var weights = new double[3];
            if (matrices.length == 1) {
                weights[0] = 1;
                return weights;
            }
            var inverse = 1 / ColorMath.temperature(xy);
            var order = new Integer[matrices.length];
            for (var index = 0; index < order.length; index++) {
                order[index] = index;
            }
            Arrays.sort(order, (left, right) -> Double.compare(inverses[left], inverses[right]));
            var lowest = order[0];
            var highest = order[order.length - 1];
            if (!(inverse > inverses[lowest])) {
                weights[lowest] = 1;
                return weights;
            }
            if (!(inverse < inverses[highest])) {
                weights[highest] = 1;
                return weights;
            }
            for (var step = 0; step + 1 < order.length; step++) {
                var below = order[step];
                var above = order[step + 1];
                if (inverse <= inverses[above]) {
                    var span = inverses[above] - inverses[below];
                    var toAbove = span > 0 ? (inverse - inverses[below]) / span : 0;
                    weights[above] = toAbove;
                    weights[below] = 1 - toAbove;
                    return weights;
                }
            }
            weights[highest] = 1;
            return weights;
        }

        /**
         * Blends the matrices of each illuminant with weights.
         *
         * @param matrices the matrices, one per illuminant
         * @param weights the weight of each illuminant, summing to one
         * @return the blended matrix
         */
        private static double[] blend(double[][] matrices, double[] weights) {
            var result = new double[matrices[0].length];
            for (var index = 0; index < matrices.length; index++) {
                if (weights[index] == 0) {
                    continue;
                }
                for (var k = 0; k < result.length; k++) {
                    result[k] += weights[index] * matrices[index][k];
                }
            }
            return result;
        }

        /**
         * Returns the XYZ to camera matrix for weights, analog balance and calibration included.
         *
         * @param weights the weights of the illuminants
         * @return the matrix
         */
        double[] xyzToCamera(double[] weights) {
            return ColorMath.multiply(calibration(weights), channels, blend(matrices, weights), channels);
        }

        /**
         * Returns the analog balance times the camera calibration for weights.
         *
         * @param weights the weights of the illuminants
         * @return the matrix
         */
        double[] calibration(double[] weights) {
            return blend(calibrations, weights);
        }

        /**
         * Returns the forward matrix for weights.
         *
         * @param weights the weights of the illuminants
         * @return the matrix, or {@code null} when the file has none
         */
        double[] forward(double[] weights) {
            return forwards == null ? null : blend(forwards, weights);
        }

        /**
         * Returns the camera neutral of a white.
         *
         * @param xy the chromaticity of the white
         * @return the neutral
         */
        double[] neutralOf(double[] xy) {
            return ColorMath.transform(xyzToCamera(weights(xy)), ColorMath.xyToXyz(xy));
        }

        /**
         * Finds the white whose camera neutral is the given one.
         *
         * @param neutral the camera neutral
         * @return the chromaticity of the white
         */
        double[] whiteOf(double[] neutral) {
            var xy = ColorMath.D50_XY.clone();
            for (var iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
                var cameraToXyz = ColorMath.pseudoInverse(xyzToCamera(weights(xy)), channels);
                if (cameraToXyz == null) {
                    return ColorMath.D50_XY.clone();
                }
                var next = ColorMath.xyzToXy(ColorMath.transform(cameraToXyz, neutral));
                if (!Double.isFinite(next[0]) || !Double.isFinite(next[1])) {
                    return ColorMath.D50_XY.clone();
                }
                var done = Math.abs(next[0] - xy[0]) + Math.abs(next[1] - xy[1]) < 1e-7;
                xy = iteration == MAX_ITERATIONS - 1 ? new double[]{(xy[0] + next[0]) / 2, (xy[1] + next[1]) / 2} : next;
                if (done) {
                    break;
                }
            }
            return new double[]{Math.clamp(xy[0], 0.05, 0.8), Math.clamp(xy[1], 0.05, 0.8)};
        }
    }
}
