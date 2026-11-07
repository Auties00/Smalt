package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.exif.ExifData;
import com.github.auties00.smalt.image.raw.CfaColor;
import com.github.auties00.smalt.image.raw.CfaLayout;
import com.github.auties00.smalt.image.raw.CfaPattern;
import com.github.auties00.smalt.image.raw.DngCompression;

import java.util.List;

/**
 * Holds what {@link DngWriter} writes into a DNG file: the raw image, its geometry and color description, the
 * descriptive metadata and the rendering the previews are made from. Fields are set by {@link DngWriting}; an
 * instance is confined to one write.
 */
final class DngContent {
    /**
     * The image width in pixels.
     */
    int width;

    /**
     * The image height in pixels.
     */
    int height;

    /**
     * The samples of each pixel, 1 to 3.
     */
    int channels;

    /**
     * The linear 16-bit samples, row by row with the samples of a pixel interleaved, 0 at black and 65535 at
     * white.
     */
    short[] samples;

    /**
     * The color filter array, or {@code null} for linear raw and monochrome images.
     */
    CfaPattern cfa;

    /**
     * The spatial layout of the color filter array.
     */
    CfaLayout layout = CfaLayout.RECTANGULAR;

    /**
     * The color of each component of {@link #neutral}.
     */
    List<CfaColor> planes;

    /**
     * The horizontal default scale.
     */
    double scaleH = 1;

    /**
     * The vertical default scale.
     */
    double scaleV = 1;

    /**
     * The default crop: left, top, width and height in the image, or {@code null} for the whole image.
     */
    int[] crop;

    /**
     * The default user crop: top, left, bottom and right relative to the default crop, or {@code null}.
     */
    double[] userCrop;

    /**
     * The opcode list applied after demosaicing, as the file stores it, or {@code null}.
     */
    byte[] opcodes3;

    /**
     * The camera neutral, largest component 1.
     */
    double[] neutral;

    /**
     * The white-balanced camera to XYZ (D50) matrix, three rows of one column per color plane, or an empty
     * array for a monochrome image.
     */
    double[] balancedToXyz;

    /**
     * The color description written as it is, or {@code null} to derive one from {@link #neutral} and
     * {@link #balancedToXyz}.
     */
    ColorCalibration calibration;

    /**
     * The baseline exposure in stops, without the camera profile's exposure offset.
     */
    double baselineExposure;

    /**
     * Whether {@link #neutral} is written as the as-shot neutral; a file whose source records no white balance
     * writes none, or the source's {@link #asShotWhiteXy}.
     */
    boolean writeNeutral = true;

    /**
     * The chromaticity of the shot's white the source records instead of a neutral, written when
     * {@link #writeNeutral} is {@code false}, or {@code null}.
     */
    double[] asShotWhiteXy;

    /**
     * The entries of the first directory carried over from a DNG source.
     */
    List<DngTag> firstTags = List.of();

    /**
     * The entries of the raw image's directory carried over from a DNG source.
     */
    List<DngTag> rawTags = List.of();

    /**
     * The camera maker, or {@code null}.
     */
    String make;

    /**
     * The camera model, or {@code null}.
     */
    String model;

    /**
     * The unique camera model.
     */
    String uniqueCameraModel = "Smalt";

    /**
     * The EXIF orientation code, 1 to 8.
     */
    int orientation = 1;

    /**
     * The EXIF metadata, or {@code null}.
     */
    ExifData exif;

    /**
     * The compression of the raw image data.
     */
    DngCompression compression = DngCompression.LOSSLESS_JPEG;

    /**
     * The tile width and height.
     */
    int tileSize = 256;

    /**
     * The rendering the preview and the thumbnail are made from, at the preview's size, or {@code null} for a
     * file without previews.
     */
    Picture preview;

    /**
     * The quality of the JPEG preview, 1 to 100.
     */
    int previewQuality = 90;
}
