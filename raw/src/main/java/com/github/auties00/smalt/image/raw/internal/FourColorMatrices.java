package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.image.raw.CfaColor;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Gives a color matrix to the files of four-color cameras (cyan, magenta, yellow and green
 * filters, or other sets of four) that carry none: the matrix fitted for the camera model when it
 * is known, else a generic matrix built from the colors of its filters.
 *
 * <p>A matrix maps CIE XYZ under D65 to the four camera colors in the order of the sensor's color
 * planes, row by row, like a DNG file's {@code ColorMatrix1} with {@code CalibrationIlluminant1}
 * D65. A model is recognized as {@link CameraMatrices#key(String, String)} keys it. A generic
 * matrix marks the file's color description as {@link ColorCalibration#generic}, so the file
 * reports its colors as approximate.
 *
 * @implNote This implementation's fitted matrices are least-squares fits of each model's CC0
 * sample file against the camera's own rendering of the shot, its embedded JPEG preview: the
 * linear sensor image and the preview are reduced to the grid of {@link PreviewBalance}, the
 * unclipped and well exposed cells' preview colors are linearized from sRGB and taken to XYZ, the
 * {@code 4 x 3} matrix from XYZ to camera values that best predicts the cells' camera means is
 * fitted by least squares, then refined with Levenberg-Marquardt iterations on the CIELAB chroma
 * errors of the cells as the default rendering (its white balance estimated from the preview and
 * its tone curve) renders them, keeping the D65 white on the shot's camera neutral and with a
 * small penalty on the distance from the least-squares fit, since the chroma errors leave one
 * direction of the coefficients almost free; scaled so that
 * the D65 white maps to a largest camera value of 1, it is the matrix. A test re-derives every
 * matrix from the pinned samples. The generic rows of cyan, magenta, yellow and green are the
 * means of the fitted models' rows of that color, each scaled to sum to one; red, green and blue
 * in another set take the rows of {@link CameraColor#GENERIC_XYZ_TO_CAMERA}, and a mixed or white
 * filter the sum of the rows of the primaries it passes.
 */
final class FourColorMatrices {
    /**
     * The DNG code of CIE illuminant D65.
     */
    private static final int ILLUMINANT_D65 = 21;

    /**
     * The fitted models.
     */
    private static final List<Entry> ENTRIES = List.of(
            new Entry("Canon", "PowerShot G1", new CfaColor[]{CfaColor.GREEN, CfaColor.MAGENTA, CfaColor.CYAN,
                    CfaColor.YELLOW},
                    new double[]{-0.3259, 0.6366, 0.3085, 0.6845, -0.3104, 0.3282, -0.2136, 0.7421, 0.4233, 0.2165, 0.7593, -0.0077}),
            new Entry("Nikon", "E5700", new CfaColor[]{CfaColor.GREEN, CfaColor.MAGENTA, CfaColor.CYAN,
                    CfaColor.YELLOW},
                    new double[]{0.1212, 0.2682, 0.3913, 1.0258, -0.6290, 0.3970, 0.4302, -0.0120, 0.5539, 0.8138, 0.0083, 0.1576})
    );

    /**
     * The fitted models, by {@link CameraMatrices#key(String, String)}.
     */
    private static final Map<String, Entry> MODELS = models();

    /**
     * The generic XYZ to camera row of cyan, magenta, yellow and green filters, by
     * {@link CfaColor#code()}, {@code null} for the other colors.
     */
    private static final double[][] GENERIC_ROWS = genericRows();

    /**
     * Prevents instantiation.
     */
    private FourColorMatrices() {
        throw new AssertionError();
    }

    /**
     * Gives a file of a four-color sensor without color matrices the matrix of its model or the
     * generic matrix of its filter colors.
     *
     * @param file the description to complete
     */
    static void complete(RawFile file) {
        var sensor = file.sensor;
        if (sensor == null || sensor.cfa == null || sensor.samplesPerPixel != 1 || sensor.cfa.isRgb()
                || file.color.channels() != 0 || file.color.cameraToSrgb != null
                || Renderer.fourColorPlanesProblem(sensor) != null) {
            return;
        }
        var planes = new CfaColor[4];
        for (var color : sensor.cfa.colors()) {
            planes[sensor.planeOfColor[color.code()]] = color;
        }
        var entry = file.make != null && file.model != null ? find(file.make, file.model) : null;
        if (entry != null && Arrays.equals(entry.planes(), planes)) {
            file.color.colorMatrix[0] = entry.d65().clone();
        } else {
            file.color.colorMatrix[0] = generic(planes);
            file.color.generic = true;
        }
        file.color.illuminant[0] = ILLUMINANT_D65;
    }

    /**
     * Returns the fitted matrix of a model.
     *
     * @param make the camera maker
     * @param model the model name
     * @return the entry, or {@code null} when the model has no fitted matrix
     */
    static Entry find(String make, String model) {
        return MODELS.get(CameraMatrices.key(make, model));
    }

    /**
     * Returns the generic XYZ to camera matrix (D65) of four filter colors.
     *
     * @param planes the color of each color plane, four distinct colors
     * @return the {@code 4 x 3} matrix, row by row in plane order
     */
    static double[] generic(CfaColor[] planes) {
        var cmyg = true;
        for (var color : planes) {
            cmyg &= GENERIC_ROWS[color.code()] != null;
        }
        var result = new double[12];
        for (var plane = 0; plane < 4; plane++) {
            var color = planes[plane];
            var row = cmyg ? GENERIC_ROWS[color.code()] : nominal(color);
            System.arraycopy(row, 0, result, plane * 3, 3);
        }
        return result;
    }

    /**
     * Returns the XYZ to camera row of a filter as the sum of the rows of
     * {@link CameraColor#GENERIC_XYZ_TO_CAMERA} of the primaries it passes.
     *
     * @param color the filter color
     * @return the row
     */
    private static double[] nominal(CfaColor color) {
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

    /**
     * Computes the generic rows of cyan, magenta, yellow and green: for each, the mean of the
     * fitted models' rows of that color, each scaled to sum to one.
     *
     * @return the rows by color code
     */
    private static double[][] genericRows() {
        var rows = new double[CfaColor.values().length][];
        var counts = new int[rows.length];
        for (var entry : ENTRIES) {
            for (var plane = 0; plane < 4; plane++) {
                var code = entry.planes()[plane].code();
                var sum = entry.d65()[plane * 3] + entry.d65()[plane * 3 + 1] + entry.d65()[plane * 3 + 2];
                if (rows[code] == null) {
                    rows[code] = new double[3];
                }
                for (var column = 0; column < 3; column++) {
                    rows[code][column] += entry.d65()[plane * 3 + column] / sum;
                }
                counts[code]++;
            }
        }
        for (var code = 0; code < rows.length; code++) {
            if (rows[code] != null) {
                for (var column = 0; column < 3; column++) {
                    rows[code][column] /= counts[code];
                }
            }
        }
        return rows;
    }

    /**
     * Indexes the entries by key.
     *
     * @return the entries of {@link #ENTRIES} by key
     */
    private static Map<String, Entry> models() {
        var models = new HashMap<String, Entry>();
        for (var entry : ENTRIES) {
            models.put(CameraMatrices.key(entry.make(), entry.model()), entry);
        }
        return Map.copyOf(models);
    }

    /**
     * Holds the fitted matrix of a model.
     *
     * @param make the camera maker
     * @param model the model name
     * @param planes the color of each of the sensor's color planes, in the order the matrix rows follow
     * @param d65 the XYZ to camera matrix for illuminant D65, {@code 4 x 3} row by row
     */
    record Entry(String make, String model, CfaColor[] planes, double[] d65) {
    }
}
