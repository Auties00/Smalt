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
 * degrees, flips, transposition, cropping on MCU boundaries and extension beyond the image, wiping a
 * region, dropping another JPEG image into one, conversion to grayscale, and transcoding between
 * sequential and progressive coding, Huffman coding with standard or optimized tables and arithmetic
 * coding.
 *
 * <p>No pixel is decoded or re-encoded: the quantized coefficients of the source are moved and
 * mirrored block by block, then entropy coded again with the source's quantization tables, so
 * the output loses nothing. Rotating four times by 90 degrees gives back the source's
 * coefficients. Blocks only move whole, so an image whose size is not a multiple of the MCU size
 * (8 or 16 pixels, by its chroma subsampling) has partial MCUs at its right or bottom edge that a
 * flip or rotation cannot move; {@link JpegTransformOptions#trim()} drops them (the default), and
 * {@link JpegTransformOptions#perfect()} rejects such images instead.
 *
 * <p>The source may be baseline, extended or progressive, 8-bit or 12-bit, Huffman or arithmetic
 * coded, gray, YCbCr, RGB, CMYK or YCCK, with any subsampling; the output has the source's precision
 * and is Huffman coded (with optimized tables when it is 12-bit) or, on request, arithmetic coded.
 * Every DCT-coded image the {@link JpegDecoder} decodes can be transformed. The marker segments the
 * output keeps (EXIF, XMP, ICC, comments) are chosen by {@link JpegTransformOptions#markerCopy()}; an
 * EXIF thumbnail is transformed with the image and {@link #autoOrient(MediaSource)} resets the EXIF orientation.
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
     * @throws IllegalArgumentException if the crop region neither fits the transformed image nor
     * extends it as {@link JpegTransformOptions#crop()} allows, or the wipe region or the dropped
     * image does not fit the image
     * @throws MediaException.Malformed if the source or the dropped image is not a valid JPEG stream
     * @throws MediaException.Unsupported if the source or the dropped image uses a coding feature
     * {@link JpegDecoder} rejects (lossless, hierarchical), if {@link JpegTransformOptions#perfect()} is
     * set and the transform is not perfect for the image, if a grayscale conversion is asked of an
     * image that is not YCbCr or gray, or if the dropped image's precision or sampling differs from the
     * source's
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
     * @throws IllegalArgumentException if the crop region neither fits the transformed image nor
     * extends it as {@link JpegTransformOptions#crop()} allows, or the wipe region or the dropped
     * image does not fit the image
     * @throws MediaException.Malformed if the source or the dropped image is not a valid JPEG stream
     * @throws MediaException.Unsupported if the source or the dropped image uses an unsupported coding
     * feature, or the transformation is impossible for it
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
     * output covers the region plus at most one MCU less one pixel on its top and left sides. A
     * region wider or taller than the image extends it with gray instead, as
     * {@link JpegTransformOptions#crop()} describes.
     *
     * @param source the JPEG file
     * @param crop the region to keep
     * @return the cropped JPEG file
     * @throws IllegalArgumentException if the region neither fits the image nor extends it
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
     * Clears a region of a JPEG image to neutral gray, keeping the rest losslessly, under the
     * {@link JpegTransformOptions#defaults()}.
     *
     * <p>The region grows to the MCU grid so that it covers every pixel asked.
     *
     * @param source the JPEG file
     * @param region the region to clear
     * @return the wiped JPEG file
     * @throws IllegalArgumentException if the region does not fit the image
     * @throws MediaException.Malformed if the source is not a valid JPEG stream
     * @throws MediaException.Unsupported if the source uses an unsupported coding feature
     * @throws MediaException.LimitExceeded if the image or the input exceeds the limits
     * @throws MediaException.Io if reading the source fails
     * @see JpegTransformOptions#wipe()
     */
    public static byte[] wipe(MediaSource source, Region region) {
        Objects.requireNonNull(region, "region");
        return transform(source, JpegTransformOptions.builder().wipe(region).build());
    }

    /**
     * Drops (inserts) another JPEG image into a JPEG image losslessly, under the
     * {@link JpegTransformOptions#defaults()}.
     *
     * <p>The offset moves right and down to the next MCU boundary. When the two images have different
     * quantization tables, the output gets tables that keep both exact.
     *
     * @param source the JPEG file
     * @param image the JPEG file to drop into it
     * @param x the horizontal offset in pixels, not negative
     * @param y the vertical offset in pixels, not negative
     * @return the JPEG file with the image dropped in
     * @throws IllegalArgumentException if an offset is negative or the image does not fit the source
     * at the offset
     * @throws MediaException.Malformed if the source or the image is not a valid JPEG stream
     * @throws MediaException.Unsupported if either uses an unsupported coding feature, or the image's
     * precision or sampling differs from the source's
     * @throws MediaException.LimitExceeded if an image or an input exceeds the limits
     * @throws MediaException.Io if reading the source or the image fails
     * @see JpegTransformOptions#dropImage()
     */
    public static byte[] drop(MediaSource source, MediaSource image, int x, int y) {
        Objects.requireNonNull(image, "image");
        var bytes = SourceBytes.readAll(image, JpegTransformOptions.defaults().limits(), true);
        return transform(source, JpegTransformOptions.builder().drop(bytes, x, y).build());
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
     * @throws IllegalArgumentException if the crop region neither fits the upright image nor extends
     * it as {@link JpegTransformOptions#crop()} allows (which no image that needs a transform
     * allows), the wipe region or the dropped image does not fit it, or the options wipe or drop into
     * an image that is not upright
     * @throws MediaException.Malformed if the source or the dropped image is not a valid JPEG stream
     * @throws MediaException.Unsupported if the source or the dropped image uses an unsupported coding
     * feature, or the transformation is impossible for it
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
