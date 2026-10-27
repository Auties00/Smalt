/* Ports libavif (src/write.c: the file layout and the quality scale of avifEncoderAddImage and
   avifEncoderFinish). Copyright 2019 Joe Drago; BSD 2-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.image.avif;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.AnimationWriter;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.gainmap.GainMap;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.image.avif.internal.AvifAnimationWriter;
import com.github.auties00.smalt.image.avif.internal.AvifStillEncoder;

import java.util.Objects;

/**
 * Encodes AVIF images: still pictures as AV1 image items, and animations as AV1 image sequences.
 *
 * <p>A still picture is coded as one AV1 key frame at a constant quality, searched for the
 * smallest rate at that quality (the intra modes, chroma-from-luma, filter intra, every transform
 * size and type, the partitions down to 4x4 and the in-loop filters). The file holds the color
 * item and, for a picture with transparency, an alpha auxiliary item coded as a monochrome AV1
 * image; its properties describe the size, bit depth, color (an {@code nclx} description and an
 * optional ICC profile), the display orientation and, for high dynamic range content, the content
 * light level and mastering display. Pictures wider or taller than 8192 samples, or any picture
 * when the options ask for it, are split into an image grid of separately coded images. Exif and
 * XMP metadata become items that describe the primary item.
 *
 * <p>Planar pictures are coded in their own chroma format, bit depth and color description
 * unless the options ask for others; RGB pictures are converted with the options' color
 * description, to 4:2:0 for lossy coding and to 4:4:4 in the identity matrix for lossless coding.
 *
 * <p>Encoding runs on the module's shared helper threads, the calling thread taking part, unless
 * the options ask for one thread; the file does not depend on the threading.
 *
 * @implNote This implementation follows libavif's {@code src/write.c} for the file layout and the
 * quality scale, and codes the AV1 images with the module's intra-only encoder, a port of libaom's
 * all-intra mode decisions.
 */
public final class AvifEncoder {
    /**
     * Prevents instantiation.
     */
    private AvifEncoder() {
        throw new AssertionError();
    }

    /**
     * Encodes a picture as an AVIF file with the {@link AvifEncoderOptions#defaults()}.
     *
     * @param picture the image to encode
     * @return the AVIF file
     * @throws MediaException.LimitExceeded if the picture exceeds the default limits
     * @throws MediaException.Unsupported if the picture's color description cannot be coded
     * @see #encode(Picture, AvifEncoderOptions)
     */
    public static byte[] encode(Picture picture) {
        return encode(picture, AvifEncoderOptions.defaults());
    }

    /**
     * Encodes a picture as an AVIF file.
     *
     * <p>An {@link com.github.auties00.smalt.image.RgbaPicture} or
     * {@link com.github.auties00.smalt.image.Rgba16Picture} contributes an alpha item only when it
     * reports alpha and is not fully opaque; so does a planar picture with an alpha plane.
     *
     * @param picture the image to encode
     * @param options the encoder settings
     * @return the AVIF file
     * @throws MediaException.LimitExceeded if the picture exceeds the options' limits, needs a grid of more
     * than 256 images per side, or the file would exceed 2 GiB
     * @throws MediaException.Unsupported if the picture's color description cannot be coded
     */
    public static byte[] encode(Picture picture, AvifEncoderOptions options) {
        Objects.requireNonNull(picture, "picture");
        Objects.requireNonNull(options, "options");
        try (var _ = ParallelBands.operation()) {
            LimitChecks.checkPicture(options.limits(), picture.width(), picture.height());
            try {
                return AvifStillEncoder.encode(picture, options);
            } catch (OutOfMemoryError error) {
                throw new MediaException.LimitExceeded("out of memory while encoding an AVIF image");
            }
        }
    }

    /**
     * Encodes a picture as an AVIF file and writes it to a sink, from its position.
     *
     * <p>The bytes written are those {@link #encode(Picture, AvifEncoderOptions)} returns.
     *
     * @param picture the image to encode
     * @param options the encoder settings
     * @param sink the output; not closed
     * @throws MediaException.LimitExceeded if the picture exceeds the options' limits
     * @throws MediaException.Unsupported if the picture's color description cannot be coded
     * @throws MediaException.Io if writing the sink fails
     */
    public static void encode(Picture picture, AvifEncoderOptions options, MediaSink sink) {
        Objects.requireNonNull(sink, "sink");
        sink.write(encode(picture, options));
    }

    /**
     * Encodes a picture with its gain map as an AVIF file, with the {@link AvifEncoderOptions#defaults()} for both
     * images.
     *
     * @param picture the picture, the base rendition
     * @param gainMap the gain map of the picture
     * @return the AVIF file
     * @throws IllegalArgumentException if a metadata value is too large for a 32-bit fraction
     * @throws MediaException.LimitExceeded if a picture exceeds the default limits
     * @throws MediaException.Unsupported if the picture's color description cannot be coded
     * @see #encode(Picture, GainMap, AvifEncoderOptions, AvifEncoderOptions)
     */
    public static byte[] encode(Picture picture, GainMap gainMap) {
        return encode(picture, gainMap, AvifEncoderOptions.defaults(), AvifEncoderOptions.defaults());
    }

    /**
     * Encodes a picture with its gain map as an AVIF file (ISO/IEC 23008-12 tone map derived image item, with
     * ISO 21496-1 metadata), which readers without gain map support show as the picture alone.
     *
     * <p>The picture is encoded as {@link #encode(Picture, AvifEncoderOptions)} encodes it, and stays the primary
     * item. The gain map image is encoded with {@code gainMapOptions} as a hidden item: monochrome when it is a
     * gray picture, 4:4:4 with the identity matrix otherwise, full range, whatever the chroma format and color of
     * {@code gainMapOptions}, with the orientation of the picture. A tone map item ({@code tmap}) whose payload is
     * a version byte, 0, followed by the binary metadata
     * ({@link com.github.auties00.smalt.image.gainmap.GainMapMetadata#toByteArray()}) derives the alternate
     * rendition from the color item and the gain map item, in that order; it carries the size of the picture, the
     * {@linkplain GainMap#alternateProfile() alternate color} (an {@code nclx} property for a profile with a CICP
     * description, an ICC one otherwise) and the alternate light level, but no orientation, which its two inputs
     * carry, and an alternatives group ({@code altr}) prefers it to the color item. The file gains the
     * {@code tmap} brand.
     *
     * @param picture the picture, the base rendition
     * @param gainMap the gain map of the picture
     * @param options the encoder settings of the picture, including its metadata
     * @param gainMapOptions the encoder settings of the gain map image: quality, speed, bit depth, losslessness and
     * threads
     * @return the AVIF file
     * @throws IllegalArgumentException if a metadata value is too large for a 32-bit fraction
     * @throws MediaException.LimitExceeded if a picture exceeds the limits of its options, needs a grid of more than
     * 256 images per side, or the file would exceed 2 GiB
     * @throws MediaException.Unsupported if the picture's color description cannot be coded
     */
    public static byte[] encode(Picture picture, GainMap gainMap, AvifEncoderOptions options,
                                AvifEncoderOptions gainMapOptions) {
        Objects.requireNonNull(picture, "picture");
        Objects.requireNonNull(gainMap, "gainMap");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(gainMapOptions, "gainMapOptions");
        try (var _ = ParallelBands.operation()) {
            LimitChecks.checkPicture(options.limits(), picture.width(), picture.height());
            LimitChecks.checkPicture(gainMapOptions.limits(), gainMap.image().width(), gainMap.image().height());
            try {
                return AvifStillEncoder.encode(picture, options, gainMap, gainMapOptions);
            } catch (OutOfMemoryError error) {
                throw new MediaException.LimitExceeded("out of memory while encoding an AVIF image");
            }
        }
    }

    /**
     * Encodes a picture with its gain map as an AVIF file and writes it to a sink, from its position.
     *
     * <p>The bytes written are those {@link #encode(Picture, GainMap, AvifEncoderOptions, AvifEncoderOptions)}
     * returns.
     *
     * @param picture the picture, the base rendition
     * @param gainMap the gain map of the picture
     * @param options the encoder settings of the picture
     * @param gainMapOptions the encoder settings of the gain map image
     * @param sink the output; not closed
     * @throws IllegalArgumentException if a metadata value is too large for a 32-bit fraction
     * @throws MediaException.LimitExceeded if a picture exceeds the limits of its options
     * @throws MediaException.Unsupported if the picture's color description cannot be coded
     * @throws MediaException.Io if writing the sink fails
     */
    public static void encode(Picture picture, GainMap gainMap, AvifEncoderOptions options,
                              AvifEncoderOptions gainMapOptions, MediaSink sink) {
        Objects.requireNonNull(sink, "sink");
        sink.write(encode(picture, gainMap, options, gainMapOptions));
    }

    /**
     * Creates a writer that encodes an animation as an AVIF image sequence into a sink.
     *
     * <p>The file also holds the first frame as its primary image, for readers that only show still
     * images, and transparent animations carry an alpha sequence. Whether the frames after the
     * first predict from other frames follows {@link AvifEncoderOptions#interFrames()}. The Exif and
     * XMP metadata of the options become items that describe the primary image, as for a still
     * picture.
     *
     * @param width the canvas width, positive
     * @param height the canvas height, positive
     * @param options the encoder settings, including the loop count
     * @param sink the output, written from its position when the writer finishes; not closed
     * @return the writer, which the caller finishes and closes
     * @throws IllegalArgumentException if a dimension is not positive
     * @throws MediaException.LimitExceeded if the canvas exceeds the options' limits; the writer's
     * {@code finish} throws it when the file would exceed 2 GiB
     */
    public static AnimationWriter openAnimationWriter(int width, int height, AvifEncoderOptions options,
                                                      MediaSink sink) {
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(sink, "sink");
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException("invalid canvas size " + width + "x" + height);
        }
        LimitChecks.checkPicture(options.limits(), width, height);
        return new AvifAnimationWriter(width, height, options, sink);
    }
}
