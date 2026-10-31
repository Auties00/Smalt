package com.github.auties00.smalt.image.raw.internal;

import java.util.Objects;

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
     * The exposure the profile adds to the shot's baseline exposure, in stops
     * ({@code BaselineExposureOffset}), included in {@link #baselineExposure}.
     */
    double exposureOffset;

    /**
     * The camera calibration matrices as the file stores them, kept or dropped for each profile by
     * comparing {@link #cameraSignature} with the profile's {@link #profileSignature}; {@code null}
     * entries.
     */
    final double[][] calibrationTags = new double[3][];

    /**
     * The camera's calibration signature ({@code CameraCalibrationSignature}), or {@code null}.
     */
    String cameraSignature;

    /**
     * The profile's calibration signature ({@code ProfileCalibrationSignature}), or {@code null}.
     */
    String profileSignature;

    /**
     * The profile's name ({@code ProfileName}), or {@code null}.
     */
    String profileName;

    /**
     * The profile's black rendering hint ({@code DefaultBlackRender}): 0 for automatic black
     * subtraction, 1 for none.
     */
    int blackRender;

    /**
     * The gain table map the profile renders with, or {@code null}.
     */
    GainTableMap gainTableMap;

    /**
     * The masked color tables the profile renders with, or {@code null}.
     */
    RgbTables rgbTables;

    /**
     * The bytes of the tag {@link #gainTableMap} was read from, or {@code null}.
     */
    byte[] gainTableMapData;

    /**
     * Whether {@link #gainTableMapData} is a {@code ProfileGainTableMap2} rather than a {@code ProfileGainTableMap}.
     */
    boolean gainTableMapSecond;

    /**
     * The bytes of the tag {@link #rgbTables} was read from, or {@code null}.
     */
    byte[] rgbTablesData;

    /**
     * Returns the description of the shot rendered with another camera profile: this
     * description's white balance, analog balance and baseline exposure with the profile's
     * matrices, tables, tone curve, exposure offset and hints, the camera calibration kept when the
     * profile's calibration signature matches the camera's, and this description's gain table map
     * and color tables where the profile has none.
     *
     * @param profile a profile read from an extra camera profile directory
     * @return the combined description
     */
    ColorCalibration withProfile(ColorCalibration profile) {
        var result = new ColorCalibration();
        for (var index = 0; index < 3; index++) {
            result.colorMatrix[index] = profile.colorMatrix[index];
            result.forwardMatrix[index] = profile.forwardMatrix[index];
            result.illuminant[index] = profile.illuminant[index];
            result.illuminantXy[index] = profile.illuminantXy[index];
            result.hueSatMap[index] = profile.hueSatMap[index];
            result.calibrationTags[index] = calibrationTags[index];
        }
        result.cameraSignature = cameraSignature;
        result.profileSignature = profile.profileSignature;
        result.keepCalibration();
        result.analogBalance = analogBalance;
        result.asShotNeutral = asShotNeutral;
        result.asShotWhiteXy = asShotWhiteXy;
        result.cameraToSrgb = cameraToSrgb;
        result.baselineExposure = baselineExposure - exposureOffset + profile.exposureOffset;
        result.exposureOffset = profile.exposureOffset;
        result.hueSatDims = profile.hueSatDims;
        result.hueSatSrgb = profile.hueSatSrgb;
        result.lookDims = profile.lookDims;
        result.lookTable = profile.lookTable;
        result.lookSrgb = profile.lookSrgb;
        result.toneCurve = profile.toneCurve;
        result.profileName = profile.profileName;
        result.blackRender = profile.blackRender;
        var source = profile.gainTableMap != null ? profile : this;
        result.gainTableMap = source.gainTableMap;
        result.gainTableMapData = source.gainTableMapData;
        result.gainTableMapSecond = source.gainTableMapSecond;
        var tables = profile.rgbTables != null ? profile : this;
        result.rgbTables = tables.rgbTables;
        result.rgbTablesData = tables.rgbTablesData;
        return result;
    }

    /**
     * Sets {@link #cameraCalibration} from {@link #calibrationTags}: kept when the camera's and the
     * profile's calibration signatures are equal (both absent counting as equal), dropped otherwise.
     */
    void keepCalibration() {
        var matches = Objects.equals(cameraSignature == null ? "" : cameraSignature,
                profileSignature == null ? "" : profileSignature);
        for (var index = 0; index < 3; index++) {
            cameraCalibration[index] = matches ? calibrationTags[index] : null;
        }
    }

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
