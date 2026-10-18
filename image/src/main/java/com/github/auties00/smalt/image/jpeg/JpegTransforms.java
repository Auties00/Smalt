/* Ports libjpeg-turbo (jpegtran.c, transupp.c). Copyright (C) 1995-2019, Thomas G. Lane, Guido Vollbeding;
   Copyright (C) 2010-2024, D. R. Commander; IJG License. Modified for Smalt. */
package com.github.auties00.smalt.image.jpeg;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.jpeg.internal.JpegTranscoder;
import com.github.auties00.smalt.image.ops.Region;
import com.github.auties00.smalt.internal.io.SourceBytes;
import com.github.auties00.smalt.internal.limits.Guards;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;

import java.util.Objects;

/**
 * Transforms JPEG images losslessly, in the DCT coefficient domain: rotations by multiples of 90
 * degrees, flips, transposition, cropping on MCU boundaries, conversion to grayscale, and
 * transcoding between sequential and progressive coding with standard or optimized Huffman
 * tables.
 *
 * <p>No pixel is decoded or re-encoded: the quantized coefficients of the source are moved and
 * mirrored block by block, then entropy coded again with the source's quantization tables, so
 * the output loses nothing. Rotating four times by 90 degrees gives back the source's
 * coefficients. Blocks only move whole, so an image whose size is not a multiple of the MCU size
 * (8 or 16 pixels, by its chroma subsampling) has partial MCUs at its right or bottom edge that a
 * flip or rotation cannot move; {@link JpegTransformOptions#trim()} drops them (the default), and
 * {@link JpegTransformOptions#perfect()} rejects such images instead.
 *
 * <p>The source may be baseline, extended or progressive, Huffman or arithmetic coded, gray,
 * YCbCr, RGB, CMYK or YCCK, with any subsampling; the output is Huffman coded. Every image the
 * {@link JpegDecoder} decodes can be transformed. The marker segments the output keeps (EXIF, XMP,
 * ICC, comments) are chosen by {@link JpegTransformOptions#markerCopy()}; an EXIF thumbnail is
 * transformed with the image and {@link #autoOrient(MediaSource)} resets the EXIF orientation.
 * Under {@link JpegTransformOptions#jpegtran()} the output is byte-identical to libjpeg-turbo's
 * {@code jpegtran} given the same transformation.
 *
 * <p>This class is thread-safe.
 */
public final class JpegTransforms {
    /**
     * The input, named in failure messages.
     */
    private static final String WHAT = "JPEG stream";

    /**
     * Prevents instantiation.
     */
    private JpegTransforms() {
        throw new AssertionError();
    }

    /**
     * Transforms a JPEG image.
     *
     * @param source the JPEG file
     * @param options the transformation
     * @return the transformed JPEG file
     * @throws IllegalArgumentException if the crop region does not fit the transformed image
     * @throws MediaException.Malformed if the source is not a valid JPEG stream
     * @throws MediaException.Unsupported if the source uses a coding feature {@link JpegDecoder}
     * rejects (12-bit, lossless, hierarchical), if {@link JpegTransformOptions#perfect()} is set and
     * the transform is not perfect for the image, or if a grayscale conversion is asked of an image
     * that is not YCbCr or gray
     * @throws MediaException.LimitExceeded if the image or the input exceeds the limits
     * @throws MediaException.Io if reading the source fails
     */
    public static byte[] transform(MediaSource source, JpegTransformOptions options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        try (var _ = ParallelBands.operation()) {
            return Guards.malformedExceptArguments(WHAT, () -> JpegTranscoder.transcode(
                    SourceBytes.readAll(source, options.limits(), true), options));
        }
    }

    /**
     * Transforms a JPEG image into a sink.
     *
     * <p>Nothing is written to the sink when the transformation fails.
     *
     * @param source the JPEG file
     * @param sink the destination of the transformed file
     * @param options the transformation
     * @throws IllegalArgumentException if the crop region does not fit the transformed image
     * @throws MediaException.Malformed if the source is not a valid JPEG stream
     * @throws MediaException.Unsupported if the source uses an unsupported coding feature or the
     * transformation is impossible for it
     * @throws MediaException.LimitExceeded if the image or the input exceeds the limits
     * @throws MediaException.Io if reading the source or writing the sink fails
     * @see #transform(MediaSource, JpegTransformOptions)
     */
    public static void transform(MediaSource source, MediaSink sink, JpegTransformOptions options) {
        Objects.requireNonNull(sink, "sink");
        sink.write(transform(source, options));
    }

    /**
     * Rotates a JPEG image clockwise by a multiple of 90 degrees, under the
     * {@link JpegTransformOptions#defaults()}.
     *
     * @param source the JPEG file
     * @param degrees the angle; negative angles rotate counter-clockwise
     * @return the rotated JPEG file
     * @throws IllegalArgumentException if {@code degrees} is not a multiple of 90
     * @throws MediaException.Malformed if the source is not a valid JPEG stream
     * @throws MediaException.Unsupported if the source uses an unsupported coding feature
     * @throws MediaException.LimitExceeded if the image or the input exceeds the limits
     * @throws MediaException.Io if reading the source fails
     */
    public static byte[] rotate(MediaSource source, int degrees) {
        if (degrees % 90 != 0) {
            throw new IllegalArgumentException("rotation " + degrees + " is not a multiple of 90 degrees");
        }
        return transform(source, JpegTransformOptions.of(Orientation.ofRotation(Math.floorMod(degrees, 360), false)));
    }

    /**
     * Mirrors a JPEG image left to right, under the {@link JpegTransformOptions#defaults()}.
     *
     * @param source the JPEG file
     * @return the mirrored JPEG file
     * @throws MediaException.Malformed if the source is not a valid JPEG stream
     * @throws MediaException.Unsupported if the source uses an unsupported coding feature
     * @throws MediaException.LimitExceeded if the image or the input exceeds the limits
     * @throws MediaException.Io if reading the source fails
     */
    public static byte[] flipHorizontal(MediaSource source) {
        return transform(source, JpegTransformOptions.of(Orientation.FLIP_HORIZONTAL));
    }

    /**
     * Mirrors a JPEG image top to bottom, under the {@link JpegTransformOptions#defaults()}.
     *
     * @param source the JPEG file
     * @return the mirrored JPEG file
     * @throws MediaException.Malformed if the source is not a valid JPEG stream
     * @throws MediaException.Unsupported if the source uses an unsupported coding feature
     * @throws MediaException.LimitExceeded if the image or the input exceeds the limits
     * @throws MediaException.Io if reading the source fails
     */
    public static byte[] flipVertical(MediaSource source) {
        return transform(source, JpegTransformOptions.of(Orientation.FLIP_VERTICAL));
    }

    /**
     * Crops a JPEG image losslessly, under the {@link JpegTransformOptions#defaults()}.
     *
     * <p>The top-left corner of the region moves up and left to the nearest MCU boundary, so the
     * output covers the region plus at most one MCU less one pixel on its top and left sides.
     *
     * @param source the JPEG file
     * @param crop the region to keep
     * @return the cropped JPEG file
     * @throws IllegalArgumentException if the region does not fit the image
     * @throws MediaException.Malformed if the source is not a valid JPEG stream
     * @throws MediaException.Unsupported if the source uses an unsupported coding feature
     * @throws MediaException.LimitExceeded if the image or the input exceeds the limits
     * @throws MediaException.Io if reading the source fails
     */
    public static byte[] crop(MediaSource source, Region crop) {
        Objects.requireNonNull(crop, "crop");
        return transform(source, JpegTransformOptions.builder().crop(crop).build());
    }

    /**
     * Converts a YCbCr JPEG image to grayscale losslessly by dropping its chroma, under the
     * {@link JpegTransformOptions#defaults()}.
     *
     * @param source the JPEG file
     * @return the grayscale JPEG file
     * @throws MediaException.Malformed if the source is not a valid JPEG stream
     * @throws MediaException.Unsupported if the source is not YCbCr or gray, or uses an unsupported
     * coding feature
     * @throws MediaException.LimitExceeded if the image or the input exceeds the limits
     * @throws MediaException.Io if reading the source fails
     */
    public static byte[] grayscale(MediaSource source) {
        return transform(source, JpegTransformOptions.builder().grayscale(true).build());
    }

    /**
     * Transcodes a JPEG image to progressive coding, losslessly, under the
     * {@link JpegTransformOptions#defaults()}.
     *
     * @param source the JPEG file
     * @return the progressive JPEG file
     * @throws MediaException.Malformed if the source is not a valid JPEG stream
     * @throws MediaException.Unsupported if the source uses an unsupported coding feature
     * @throws MediaException.LimitExceeded if the image or the input exceeds the limits
     * @throws MediaException.Io if reading the source fails
     */
    public static byte[] progressive(MediaSource source) {
        return transform(source, JpegTransformOptions.builder().progressive(true).build());
    }

    /**
     * Transcodes a JPEG image to sequential coding with optimized Huffman tables, losslessly,
     * under the {@link JpegTransformOptions#defaults()}.
     *
     * @apiNote This also shrinks a sequential file written with the standard tables.
     * @param source the JPEG file
     * @return the sequential JPEG file
     * @throws MediaException.Malformed if the source is not a valid JPEG stream
     * @throws MediaException.Unsupported if the source uses an unsupported coding feature
     * @throws MediaException.LimitExceeded if the image or the input exceeds the limits
     * @throws MediaException.Io if reading the source fails
     */
    public static byte[] sequential(MediaSource source) {
        return transform(source, JpegTransformOptions.defaults());
    }

    /**
     * Rotates or flips a JPEG image losslessly so that it displays upright without its EXIF
     * orientation, then records the normal orientation, under the
     * {@link JpegTransformOptions#defaults()}.
     *
     * <p>The EXIF thumbnail is transformed too. An image without an EXIF orientation, or with the
     * normal one, is only re-encoded.
     *
     * @param source the JPEG file
     * @return the upright JPEG file
     * @throws MediaException.Malformed if the source is not a valid JPEG stream
     * @throws MediaException.Unsupported if the source uses an unsupported coding feature
     * @throws MediaException.LimitExceeded if the image or the input exceeds the limits
     * @throws MediaException.Io if reading the source fails
     */
    public static byte[] autoOrient(MediaSource source) {
        return autoOrient(source, JpegTransformOptions.defaults());
    }

    /**
     * Rotates or flips a JPEG image losslessly so that it displays upright without its EXIF
     * orientation, then records the normal orientation.
     *
     * <p>The transform comes from the orientation of the first EXIF segment, overriding
     * {@link JpegTransformOptions#transform()}; {@link JpegTransformOptions#resetOrientation()}
     * is set. A crop region of the options applies to the upright image.
     *
     * @param source the JPEG file
     * @param options the other settings of the transformation
     * @return the upright JPEG file
     * @throws IllegalArgumentException if the crop region does not fit the upright image
     * @throws MediaException.Malformed if the source is not a valid JPEG stream
     * @throws MediaException.Unsupported if the source uses an unsupported coding feature or the
     * transformation is impossible for it
     * @throws MediaException.LimitExceeded if the image or the input exceeds the limits
     * @throws MediaException.Io if reading the source fails
     */
    public static byte[] autoOrient(MediaSource source, JpegTransformOptions options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        try (var _ = ParallelBands.operation()) {
            return Guards.malformedExceptArguments(WHAT, () -> JpegTranscoder.autoOrient(
                    SourceBytes.readAll(source, options.limits(), true), options));
        }
    }
}
