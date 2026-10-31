/* Ports the Digital Negative (DNG) Specification 1.7.1.0, Adobe. This product includes DNG technology under license
   by Adobe. */
package com.github.auties00.smalt.image.raw;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.raw.internal.DngWriting;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;

import java.util.Objects;

/**
 * Writes DNG files (Adobe DNG specification 1.7.1): a {@link RawImage}, or a camera raw file converted with its
 * metadata.
 *
 * <p>The raw image is stored as it is: a color filter array image (one or two samples per photosite, any
 * pattern and layout), a linear raw image of three samples per pixel, or a monochrome image, in 16-bit tiles,
 * uncompressed or as lossless JPEG ({@link DngEncoderOptions#compression()}). Its samples are the file's linear
 * reference values: {@link RawDecoder#decodeRaw(MediaSource, RawDecoderOptions)} of the written file returns
 * them unchanged, with the same color filter array, layout, neutral and, to within the precision of the file's
 * rationals, the same white-balanced camera to XYZ matrix. The color description is the camera's neutral and a
 * matrix pair derived from {@link RawImage#balancedToXyz()}: the forward matrix it is, and the color matrix that
 * maps the D50 white to the neutral, for the D50 calibration illuminant. The specification requires a forward
 * matrix to map a white-balanced neutral to the D50 white; a matrix that maps it more than 0.01 away from D50 in
 * an XYZ component (one {@link RawImage#ofCfa} and {@link RawImage#ofLinear} reject, but a file's decoded image
 * can carry) is written with each row scaled to map it to D50 exactly. A color filter array image is stored with
 * a margin of two or more photosites of the same colors around it, outside the file's default crop, so that a raw
 * converter demosaics the image's edges from neighbors. By default the file holds a baseline
 * JPEG preview in sRGB and an uncompressed thumbnail ({@link DngEncoderOptions#preview()}), rendered from the
 * written image as {@link RawDecoder#decode(MediaSource)} renders it.
 *
 * <p>The output does not depend on the thread count; tiles are coded in parallel on the shared pool. This class
 * is thread-safe.
 */
public final class DngEncoder {
    /**
     * Prevents instantiation.
     */
    private DngEncoder() {
        throw new AssertionError();
    }

    /**
     * Writes a sensor image as a DNG file with the {@linkplain DngEncoderOptions#defaults() default options}.
     *
     * @param image the sensor image to write
     * @return the file
     * @throws IllegalArgumentException if the image is neither monochrome nor has a color matrix, or its matrix
     * is singular or maps a white-balanced neutral to a white with a component that is not positive
     * @throws MediaException.LimitExceeded if the file would exceed 4 GiB
     */
    public static byte[] encode(RawImage image) {
        return encode(image, DngEncoderOptions.defaults());
    }

    /**
     * Writes a sensor image as a DNG file.
     *
     * @param image the sensor image to write
     * @param options the encoder settings
     * @return the file
     * @throws IllegalArgumentException if the image is neither monochrome nor has a color matrix, or its matrix
     * is singular or maps a white-balanced neutral to a white with a component that is not positive
     * @throws MediaException.LimitExceeded if the file would exceed 4 GiB
     */
    public static byte[] encode(RawImage image, DngEncoderOptions options) {
        var sink = MediaSink.memory();
        encode(image, options, sink);
        return sink.toByteArray();
    }

    /**
     * Writes a sensor image as a DNG file into a sink.
     *
     * <p>The options' camera, orientation, EXIF, baseline exposure and pixel aspect ratio describe the image;
     * those left automatic are absent, normal, absent, 0 and square.
     *
     * @param image the sensor image to write
     * @param options the encoder settings
     * @param sink the output, written from its position; not closed
     * @throws IllegalArgumentException if the image is neither monochrome nor has a color matrix, or its matrix
     * is singular or maps a white-balanced neutral to a white with a component that is not positive
     * @throws MediaException.LimitExceeded if the file would exceed 4 GiB
     * @throws MediaException.Io if writing the sink fails
     */
    public static void encode(RawImage image, DngEncoderOptions options, MediaSink sink) {
        Objects.requireNonNull(image, "image");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(sink, "sink");
        try (var _ = ParallelBands.operation()) {
            DngWriting.encode(image, options, sink);
        }
    }

    /**
     * Converts a camera raw file into a DNG file with the default decoding and encoder options.
     *
     * @param source the raw file, DNG or of any format {@link RawDecoder} decodes
     * @return the DNG file
     * @throws MediaException.Malformed if the file is corrupt
     * @throws MediaException.Unsupported if the input is not a recognized raw format, its sensor data uses a
     * feature the decoder lacks, or it is a four-color image without color matrices
     * @throws MediaException.LimitExceeded if the image exceeds the default limits or the file would exceed 4 GiB
     * @throws MediaException.Io if reading the source fails
     */
    public static byte[] convert(MediaSource source) {
        return convert(source, RawDecoderOptions.defaults(), DngEncoderOptions.defaults());
    }

    /**
     * Converts a camera raw file into a DNG file.
     *
     * @param source the raw file, DNG or of any format {@link RawDecoder} decodes
     * @param decoding the decoding options
     * @param options the encoder settings
     * @return the DNG file
     * @throws MediaException.Malformed if the file is corrupt
     * @throws MediaException.Unsupported if the input is not a recognized raw format, its sensor data uses a
     * feature the decoder lacks, or it is a four-color image without color matrices
     * @throws MediaException.LimitExceeded if the image exceeds the limits or the file would exceed 4 GiB
     * @throws MediaException.Io if reading the source fails
     */
    public static byte[] convert(MediaSource source, RawDecoderOptions decoding, DngEncoderOptions options) {
        var sink = MediaSink.memory();
        convert(source, decoding, options, sink);
        return sink.toByteArray();
    }

    /**
     * Converts a camera raw file into a DNG file written into a sink.
     *
     * <p>The raw image is the whole active area of the sensor image {@link RawDecoder#decodeRaw(MediaSource,
     * RawDecoderOptions)} extracts with the decoding options, linearized and with the opcode lists 1 and 2
     * applied when the options apply them. The file keeps the source's default crop and default user crop and
     * its opcode list 3 when the options apply them, its default scale, and its color description: the color
     * matrices, calibration illuminants, forward matrices, camera calibration, analog balance and the camera
     * profile's tables, tone curve and baseline exposure, unless the decoding options replace the color matrix,
     * in which case the description is derived as {@link #encode(RawImage, DngEncoderOptions, MediaSink)}
     * derives it. The decoding options' white balance and camera profile choice decide the as-shot neutral and
     * the profile written; a source that records no white balance, decoded with the as-shot one, gives a file that
     * records none either. The rendering hints and camera descriptions of a DNG source that the decoder does not
     * interpret ({@code ShadowScale}, {@code BaselineNoise}, {@code NoiseProfile}, {@code BayerGreenSplit} and
     * the like) are carried over, and the EXIF metadata gains the shot's settings the source records elsewhere.
     * A color filter array image whose default crop leaves fewer than two photosites on a side, and that has no
     * opcode list 3, gains a margin of photosites of the same colors, outside the default crop. The options'
     * camera, orientation, EXIF, baseline exposure and pixel aspect ratio replace the source's; the preview
     * renders the source with the decoding options' rendering choices, in 8-bit sRGB.
     *
     * @param source the raw file, DNG or of any format {@link RawDecoder} decodes
     * @param decoding the decoding options
     * @param options the encoder settings
     * @param sink the output, written from its position; not closed
     * @throws MediaException.Malformed if the file is corrupt
     * @throws MediaException.Unsupported if the input is not a recognized raw format, its sensor data uses a
     * feature the decoder lacks, or it is a four-color image without color matrices
     * @throws MediaException.LimitExceeded if the image exceeds the limits or the file would exceed 4 GiB
     * @throws MediaException.Io if reading the source or writing the sink fails
     */
    public static void convert(MediaSource source, RawDecoderOptions decoding, DngEncoderOptions options,
                               MediaSink sink) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(decoding, "decoding");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(sink, "sink");
        try (var _ = ParallelBands.operation()) {
            DngWriting.convert(source, decoding, options, sink);
        }
    }
}
