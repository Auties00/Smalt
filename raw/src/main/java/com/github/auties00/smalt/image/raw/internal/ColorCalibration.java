package com.github.auties00.smalt.image.raw.internal;

/**
 * Holds the color description of a raw file in the terms of the DNG specification (chapter 6):
 * the camera matrices for up to three calibration illuminants, the white balance of the shot and
 * the camera profile's look.
 *
 * <p>Matrices are stored row by row. A vendor format without DNG matrices fills
 * {@link #cameraToSrgb} when the file embeds a camera to sRGB matrix, or leaves every matrix
 * empty, in which case the pipeline falls back to a generic camera matrix. Fields are set by the
 * parsers; an instance is confined to one decode.
 */
final class ColorCalibration {
    /**
     * The XYZ to camera matrices ({@code ColorMatrix1..3}), {@code channels x 3}, or {@code null}
     * entries.
     */
    final double[][] colorMatrix = new double[3][];

    /**
     * The camera calibration matrices ({@code CameraCalibration1..3}), {@code channels x channels},
     * or {@code null} entries.
     */
    final double[][] cameraCalibration = new double[3][];

    /**
     * The forward matrices ({@code ForwardMatrix1..3}), {@code 3 x channels}, or {@code null}
     * entries.
     */
    final double[][] forwardMatrix = new double[3][];

    /**
     * The EXIF light source codes of the calibration illuminants ({@code CalibrationIlluminant1..3}).
     */
    final int[] illuminant = new int[3];

    /**
     * The chromaticity of each calibration illuminant whose code is 255 (other), from
     * {@code IlluminantData1..3}, or {@code null} entries.
     */
    final double[][] illuminantXy = new double[3][];

    /**
     * The analog balance ({@code AnalogBalance}), or {@code null}.
     */
    double[] analogBalance;

    /**
     * The camera's response to a neutral under the shot's light ({@code AsShotNeutral}), or
     * {@code null}.
     */
    double[] asShotNeutral;

    /**
     * The chromaticity of the shot's white ({@code AsShotWhiteXY}), or {@code null}.
     */
    double[] asShotWhiteXy;

    /**
     * A camera to linear sRGB matrix embedded by a vendor format, applied after white balance, or
     * {@code null}.
     */
    double[] cameraToSrgb;

    /**
     * The exposure the file asks to add, in stops ({@code BaselineExposure} plus
     * {@code BaselineExposureOffset}).
     */
    double baselineExposure;

    /**
     * The dimensions of the hue, saturation and value map: hue divisions, saturation divisions and
     * value divisions; or {@code null}.
     */
    int[] hueSatDims;

    /**
     * The hue, saturation and value maps for each illuminant, or {@code null} entries.
     */
    final float[][] hueSatMap = new float[3][];

    /**
     * Whether the value axis of the hue, saturation and value map is sRGB-encoded.
     */
    boolean hueSatSrgb;

    /**
     * The dimensions of the look table, or {@code null}.
     */
    int[] lookDims;

    /**
     * The look table, or {@code null}.
     */
    float[] lookTable;

    /**
     * Whether the value axis of the look table is sRGB-encoded.
     */
    boolean lookSrgb;

    /**
     * The profile tone curve as input and output pairs, or {@code null}.
     */
    float[] toneCurve;

    /**
     * Returns the number of color channels the matrices describe.
     *
     * @return 3 or 4, or 0 when no matrix is present
     */
    int channels() {
        for (var matrix : colorMatrix) {
            if (matrix != null) {
                return matrix.length / 3;
            }
        }
        return 0;
    }
}
