package com.github.auties00.smalt.spi;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.AnimationReader;
import com.github.auties00.smalt.image.AnimationWriter;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageEncoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.ImageInfo;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.PageReader;
import com.github.auties00.smalt.image.PageWriter;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;

import java.util.Optional;
import java.util.ServiceLoader;

/**
 * Decodes and encodes the images of one or more formats on behalf of {@link Images}.
 *
 * <p>{@link Images} recognizes the format of its input from the content, with
 * {@link Images#detect(MediaSource)}, and hands the input to the first provider whose
 * {@link #decodes(ImageFormat)} accepts that format; it hands a picture to encode to the first
 * provider whose {@link #encodes(ImageFormat)} accepts the requested format. A format no provider
 * accepts raises {@link MediaException.Unsupported}. Providers are found once, the first time
 * {@link Images} needs them, with {@link ServiceLoader} through the class loader that defined this
 * interface: a named module declares one with {@code provides} in its module declaration, a jar
 * on the class path with a {@code META-INF/services} entry named after this interface. The order
 * in which providers of the same format are consulted is unspecified, so providers should not
 * claim the same format.
 *
 * <p>Every decoding method receives the whole input, positioned at its first byte, and reads it
 * with the positional reads of {@link MediaSource}.
 *
 * @implSpec Implementations must be thread-safe, since one instance serves every caller of the
 * JVM, and must have a public no-argument constructor that does no work: every provider is
 * instantiated when the providers are first looked up, whatever format the caller uses. A
 * provider overrides the decoding methods ({@link #decodes(ImageFormat)},
 * {@link #probe(MediaSource)}, {@link #decode(MediaSource, ImageDecoderOptions)} and optionally
 * {@link #openAnimation(MediaSource, ImageDecoderOptions)} and
 * {@link #openPages(MediaSource, ImageDecoderOptions)}), the encoding pair
 * ({@link #encodes(ImageFormat)} and
 * {@link #encode(Picture, ImageFormat, ImageEncoderOptions, MediaSink)}), the animation encoding
 * pair ({@link #encodesAnimation(ImageFormat)} and
 * {@link #openAnimationWriter(ImageFormat, int, int, ImageEncoderOptions, MediaSink)}), the page
 * encoding pair ({@link #encodesPages(ImageFormat)} and
 * {@link #openPageWriter(ImageFormat, ImageEncoderOptions, MediaSink)}), or any combination of
 * them.
 */
public interface ImageCodecProvider {
    /**
     * Returns whether this provider decodes images of a format.
     *
     * @implSpec The default implementation returns {@code false}.
     *
     * @param format the format recognized from the content of the input
     * @return {@code true} when {@link #probe(MediaSource)} and
     * {@link #decode(MediaSource, ImageDecoderOptions)} accept inputs of {@code format}
     */
    default boolean decodes(ImageFormat format) {
        return false;
    }

    /**
     * Summarizes an image from its headers without decoding its pixels.
     *
     * @implSpec The default implementation throws {@link UnsupportedOperationException}; a
     * provider whose {@link #decodes(ImageFormat)} accepts a format overrides it.
     *
     * @param source the input, of a format {@link #decodes(ImageFormat)} accepts
     * @return the summary
     * @throws MediaException.Unsupported if the image uses a feature the decoder lacks
     * @throws MediaException.Malformed if the headers are corrupt
     * @throws MediaException.Io if reading the source fails
     */
    default ImageInfo probe(MediaSource source) {
        throw new UnsupportedOperationException(getClass().getName() + " decodes no format");
    }

    /**
     * Decodes a still image, or the first frame of an animated one.
     *
     * @implSpec The default implementation throws {@link UnsupportedOperationException}; a
     * provider whose {@link #decodes(ImageFormat)} accepts a format overrides it.
     *
     * @param source the input, of a format {@link #decodes(ImageFormat)} accepts
     * @param options the limits and the reduced-size hint
     * @return the decoded image in its format's native picture representation
     * @throws MediaException.Unsupported if the image uses a feature the decoder lacks
     * @throws MediaException.Malformed if the image is corrupt
     * @throws MediaException.LimitExceeded if the image exceeds the limits
     * @throws MediaException.Io if reading the source fails
     */
    default DecodedImage decode(MediaSource source, ImageDecoderOptions options) {
        throw new UnsupportedOperationException(getClass().getName() + " decodes no format");
    }

    /**
     * Opens an image as an animation, when its format has an animated form.
     *
     * <p>A format that can hold an animation opens every image of it, a still one included, as an
     * {@link AnimationReader} that yields its frames. A format that never animates returns an empty
     * {@link Optional}, and {@link Images#openAnimation(MediaSource, ImageDecoderOptions)} then
     * presents the picture of {@link #decode(MediaSource, ImageDecoderOptions)} as a single frame.
     *
     * @implSpec The default implementation returns an empty {@link Optional}.
     *
     * @param source the input, of a format {@link #decodes(ImageFormat)} accepts
     * @param options the limits; the reduced-size hint is ignored
     * @return the reader, which the caller closes, or an empty {@link Optional} when the format
     * has no animated form
     * @throws MediaException.Unsupported if the image uses a feature the decoder lacks
     * @throws MediaException.Malformed if the image is corrupt
     * @throws MediaException.LimitExceeded if the image exceeds the limits
     * @throws MediaException.Io if reading the source fails
     */
    default Optional<AnimationReader> openAnimation(MediaSource source, ImageDecoderOptions options) {
        return Optional.empty();
    }

    /**
     * Opens the pages of an image, when its format can hold several independent images.
     *
     * <p>A format that can hold several images (the directories of a TIFF, the entries of an icon, the master
     * images of a HEIF file) opens every image of it, a single-image one included, as a {@link PageReader}. A
     * format that holds one image returns an empty {@link Optional}, and
     * {@link Images#openPages(MediaSource, ImageDecoderOptions)} then presents the image of
     * {@link #decode(MediaSource, ImageDecoderOptions)} as a single page.
     *
     * @implSpec The default implementation returns an empty {@link Optional}.
     *
     * @param source the input, of a format {@link #decodes(ImageFormat)} accepts
     * @param options the limits, the reduced-size hint and the bit depth choice, applied to every
     * page
     * @return the reader, which the caller closes, or an empty {@link Optional} when the format
     * holds a single image
     * @throws MediaException.Unsupported if the image uses a feature the decoder lacks
     * @throws MediaException.Malformed if the image is corrupt
     * @throws MediaException.LimitExceeded if the page count exceeds the limits
     * @throws MediaException.Io if reading the source fails
     */
    default Optional<PageReader> openPages(MediaSource source, ImageDecoderOptions options) {
        return Optional.empty();
    }

    /**
     * Returns whether this provider encodes images of a format.
     *
     * @implSpec The default implementation returns {@code false}.
     *
     * @param format the image format to produce
     * @return {@code true} when
     * {@link #encode(Picture, ImageFormat, ImageEncoderOptions, MediaSink)} accepts
     * {@code format}
     */
    default boolean encodes(ImageFormat format) {
        return false;
    }

    /**
     * Encodes a picture as a complete image of a format and writes it to a sink.
     *
     * <p>Implementations must honor the metadata of the options as {@link ImageEncoderOptions} describes:
     * record the color profile, the EXIF block and the XMP packet where the format has a place for them,
     * convert the pixels to sRGB when the format cannot record the profile, record the orientation, and
     * turn the picture upright when the format records none.
     *
     * @implSpec The default implementation throws {@link UnsupportedOperationException}; a
     * provider whose {@link #encodes(ImageFormat)} accepts a format overrides it, mapping the
     * options onto the format's own configuration and converting the picture as the format
     * needs.
     *
     * @param picture the pixels to encode
     * @param format the format to produce, one {@link #encodes(ImageFormat)} accepts
     * @param options the lossless flag, the quality and the metadata
     * @param sink the output, written from its position
     * @throws IllegalArgumentException if the color profile does not describe the picture, or the EXIF
     * block exceeds what the format stores
     * @throws MediaException.Unsupported if the format cannot honor the options (lossless JPEG)
     * @throws MediaException.Io if writing the sink fails
     */
    default void encode(Picture picture, ImageFormat format, ImageEncoderOptions options, MediaSink sink) {
        throw new UnsupportedOperationException(getClass().getName() + " encodes no format");
    }

    /**
     * Returns whether this provider encodes animations of a format.
     *
     * @implSpec The default implementation returns {@code false}.
     *
     * @param format the image format of the animations
     * @return {@code true} when
     * {@link #openAnimationWriter(ImageFormat, int, int, ImageEncoderOptions, MediaSink)}
     * accepts {@code format}
     */
    default boolean encodesAnimation(ImageFormat format) {
        return false;
    }

    /**
     * Opens a writer that encodes an animation of a format frame by frame into a sink.
     *
     * <p>Implementations must honor the metadata of the options as
     * {@link #encode(Picture, ImageFormat, ImageEncoderOptions, MediaSink)} does, the frames being in the
     * options' color profile, and reject an orientation the format cannot record.
     *
     * @implSpec The default implementation throws {@link UnsupportedOperationException}; a
     * provider whose {@link #encodesAnimation(ImageFormat)} accepts a format overrides it, mapping
     * the options (lossless flag, quality and loop count) onto the format's own configuration.
     *
     * @param format the format to produce, one {@link #encodesAnimation(ImageFormat)} accepts
     * @param width the canvas width, positive
     * @param height the canvas height, positive
     * @param options the lossless flag, the quality, the loop count and the metadata
     * @param sink the output, written from its position; not closed
     * @return the writer, which the caller finishes and closes
     * @throws IllegalArgumentException if a dimension is not positive, the loop count
     * exceeds what the format stores or the EXIF block exceeds what the format stores
     * @throws MediaException.LimitExceeded if a dimension exceeds what the format stores
     * @throws MediaException.Unsupported if the format cannot honor the options, an orientation
     * it cannot record included
     */
    default AnimationWriter openAnimationWriter(ImageFormat format, int width, int height, ImageEncoderOptions options,
                                                MediaSink sink) {
        throw new UnsupportedOperationException(getClass().getName() + " encodes no animation format");
    }

    /**
     * Returns whether this provider encodes multi-page images of a format.
     *
     * @implSpec The default implementation returns {@code false}.
     *
     * @param format the image format of the pages
     * @return {@code true} when {@link #openPageWriter(ImageFormat, ImageEncoderOptions, MediaSink)} accepts
     * {@code format}
     */
    default boolean encodesPages(ImageFormat format) {
        return false;
    }

    /**
     * Opens a writer that encodes the pages of a multi-image file of a format into a sink.
     *
     * @implSpec The default implementation throws {@link UnsupportedOperationException}; a
     * provider whose {@link #encodesPages(ImageFormat)} accepts a format overrides it, mapping the
     * options (lossless flag and quality) onto the format's own configuration as
     * {@link #encode(Picture, ImageFormat, ImageEncoderOptions, MediaSink)} does, and honoring their
     * metadata for every page written with {@link PageWriter#write(Picture)}.
     *
     * @param format the format to produce, one {@link #encodesPages(ImageFormat)} accepts
     * @param options the lossless flag, the quality and the metadata of the pictures
     * @param sink the output, written from its position; not closed
     * @return the writer, which the caller finishes and closes
     */
    default PageWriter openPageWriter(ImageFormat format, ImageEncoderOptions options, MediaSink sink) {
        throw new UnsupportedOperationException(getClass().getName() + " encodes no multi-page format");
    }
}
