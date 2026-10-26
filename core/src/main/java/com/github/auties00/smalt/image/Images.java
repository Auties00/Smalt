package com.github.auties00.smalt.image;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.internal.heif.HeifFile;
import com.github.auties00.smalt.internal.heif.HeifImageCodec;
import com.github.auties00.smalt.internal.provider.Artifacts;
import com.github.auties00.smalt.internal.provider.Providers;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.spi.ImageCodecProvider;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Decodes an image of any supported format, recognized from its content, and encodes pictures
 * into the supported formats.
 *
 * <p>This is the entry point for callers that do not know what a file is: it recognizes the format
 * with {@link #detect(MediaSource)}, from the leading bytes and, for TIFF and HEIF files, the
 * structure they hold (never trusting a file name or declared mimetype), and dispatches to the
 * {@link ImageCodecProvider} that decodes it.
 * The JPEG, PNG (and APNG), GIF, WebP, BMP, TIFF, ICO and ANI decoders of {@code smalt-image} are such
 * providers, and its JPEG, PNG, GIF, WebP, BMP, TIFF, ICO and ANI encoders serve {@link #encode(Picture,
 * ImageFormat, ImageEncoderOptions, MediaSink)}, and its animated WebP, GIF, APNG and ANI encoders serve
 * {@link #openAnimationWriter(ImageFormat, int, int, ImageEncoderOptions, MediaSink)}; the DNG and
 * camera raw decoder of {@code smalt-raw} decodes {@link ImageFormat#DNG} and
 * {@link ImageFormat#RAW} files. Formats this module recognizes but no provider handles raise
 * {@link MediaException.Unsupported} naming the format (and the artifact to add, when a Smalt
 * artifact handles it), so a caller can tell "not an image" ({@code format}
 * {@code "unknown image"}) from "an image that is not decodable here" (for example
 * {@code "heic"}).
 */
public final class Images {
    /**
     * Prevents instantiation.
     */
    private Images() {
        throw new AssertionError();
    }

    /**
     * Recognizes the format of an image from its leading bytes.
     *
     * <p>A file that starts with a TIFF signature is told apart further by its directories, which
     * this method follows through the source: a camera raw file is recognized as
     * {@link ImageFormat#DNG} or {@link ImageFormat#RAW} wherever its directories lie, rather than
     * as {@link ImageFormat#TIFF}. A HEIF file is told apart by the coding of the image it shows,
     * whatever its brands say: this method reads its {@code meta} box (and, for a file that shows its image
     * sequence, its {@code moov} box) and recognizes a file whose primary image, or whose sequence without a
     * primary image, is AV1-coded as {@link ImageFormat#AVIF}, and one coded with HEVC, AVC, JPEG, JPEG 2000 or as
     * uncompressed samples as {@link ImageFormat#HEIC}; when the structure cannot be read within the
     * {@linkplain com.github.auties00.smalt.MediaLimits#DEFAULT default limits}, or the coding is another one,
     * the brands decide as for {@link ImageFormat#detect(byte[], int)}.
     *
     * @param source the input
     * @return the recognized format, or empty when the bytes match no known image format
     * @throws MediaException.Io if reading the source fails
     */
    public static Optional<ImageFormat> detect(MediaSource source) {
        Objects.requireNonNull(source, "source");
        var header = new byte[ImageFormat.DETECTION_LENGTH];
        var length = 0;
        while (length < header.length) {
            var read = source.read(length, header, length, header.length - length);
            if (read <= 0) {
                break;
            }
            length += read;
        }
        var format = ImageFormat.detect(header, length);
        if (format.isPresent() && format.get() == ImageFormat.TIFF) {
            return Optional.of(RawDetection.classifyTiff(RawDetection.over(source)));
        }
        if (format.isPresent() && (format.get() == ImageFormat.HEIC || format.get() == ImageFormat.AVIF)) {
            return Optional.of(heifFamily(source, format.get()));
        }
        return format;
    }

    /**
     * Recognizes a HEIF file by the coding of the image it shows.
     *
     * @param source the file
     * @param brands the format its brands give
     * @return the format of the coding of the image it shows, or {@code brands} when that is unknown or the
     * structure cannot be read
     * @throws MediaException.Io if reading the source fails
     */
    private static ImageFormat heifFamily(MediaSource source, ImageFormat brands) {
        try {
            var file = HeifFile.parse(source, MediaLimits.DEFAULT, false);
            var family = HeifImageCodec.formatOf(source, file, MediaLimits.DEFAULT);
            return family == null ? brands : family;
        } catch (MediaException.Io exception) {
            throw exception;
        } catch (RuntimeException exception) {
            // the decoder the brands name reports the defect when it reads the file
            return brands;
        }
    }

    /**
     * Recognizes the format of an image file from its leading bytes, as
     * {@link #detect(MediaSource)} does.
     *
     * @param file the file, opened for the call and closed before it returns
     * @return the recognized format, or empty when the bytes match no known image format
     * @throws MediaException.Io if the file cannot be opened or read
     */
    public static Optional<ImageFormat> detect(Path file) {
        try (var source = MediaSource.of(file)) {
            return detect(source);
        }
    }

    /**
     * Returns whether images of a format can be decoded with the artifacts present at run time.
     *
     * @param format the image format to check
     * @return {@code true} when {@link #decode(MediaSource, ImageDecoderOptions)} accepts it
     */
    public static boolean isDecodable(ImageFormat format) {
        return decoder(Objects.requireNonNull(format, "format")) != null;
    }

    /**
     * Returns whether pictures can be encoded into a format with the artifacts present at run
     * time.
     *
     * @param format the image format to check
     * @return {@code true} when {@link #encode(Picture, ImageFormat, ImageEncoderOptions, MediaSink)} accepts it
     * @throws NullPointerException if {@code format} is {@code null}
     */
    public static boolean isEncodable(ImageFormat format) {
        return encoder(Objects.requireNonNull(format, "format")) != null;
    }

    /**
     * Returns whether animations of a format can be encoded with the artifacts present at run
     * time.
     *
     * @param format the image format to check
     * @return {@code true} when {@link #openAnimationWriter(ImageFormat, int, int, ImageEncoderOptions, MediaSink)}
     * accepts it
     * @throws NullPointerException if {@code format} is {@code null}
     */
    public static boolean isAnimationEncodable(ImageFormat format) {
        return animationEncoder(Objects.requireNonNull(format, "format")) != null;
    }

    /**
     * Returns whether multi-page images of a format can be encoded with the artifacts present at
     * run time.
     *
     * @param format the image format to check
     * @return {@code true} when {@link #openPageWriter(ImageFormat, ImageEncoderOptions, MediaSink)} accepts it
     * @throws NullPointerException if {@code format} is {@code null}
     */
    public static boolean isPageEncodable(ImageFormat format) {
        return pageEncoder(Objects.requireNonNull(format, "format")) != null;
    }

    /**
     * Summarizes an image from its headers without decoding its pixels.
     *
     * @param source the input
     * @return the summary
     * @throws NullPointerException       if {@code source} is {@code null}
     * @throws MediaException.Unsupported if the format is unknown or not decodable
     * @throws MediaException.Malformed   if the headers are corrupt
     * @throws MediaException.Io          if reading the source fails
     */
    public static ImageInfo probe(MediaSource source) {
        return provider(source).probe(source);
    }

    /**
     * Summarizes an image file from its headers without decoding its pixels.
     *
     * @param file the file, opened for the call and closed before it returns
     * @return the summary
     * @throws MediaException.Unsupported if the format is unknown or not decodable
     * @throws MediaException.Malformed if the headers are corrupt
     * @throws MediaException.Io if the file cannot be opened or read
     */
    public static ImageInfo probe(Path file) {
        try (var source = MediaSource.of(file)) {
            return probe(source);
        }
    }

    /**
     * Decodes a still image, or the first frame of an animated one, at full size under the default
     * limits.
     *
     * @param source the input
     * @return the decoded image in its format's native picture representation
     * @throws NullPointerException         if {@code source} is {@code null}
     * @throws MediaException.Unsupported   if the format is unknown or not decodable
     * @throws MediaException.Malformed     if the image is corrupt
     * @throws MediaException.LimitExceeded if the image exceeds the limits
     * @throws MediaException.Io            if reading the source fails
     */
    public static DecodedImage decode(MediaSource source) {
        return decode(source, ImageDecoderOptions.defaults());
    }

    /**
     * Decodes a still image, or the first frame of an animated one.
     *
     * @param source  the input
     * @param options the limits and the reduced-size hint
     * @return the decoded image in its format's native picture representation
     * @throws NullPointerException         if an argument is {@code null}
     * @throws MediaException.Unsupported   if the format is unknown or not decodable
     * @throws MediaException.Malformed     if the image is corrupt
     * @throws MediaException.LimitExceeded if the image exceeds the limits
     * @throws MediaException.Io            if reading the source fails
     */
    public static DecodedImage decode(MediaSource source, ImageDecoderOptions options) {
        Objects.requireNonNull(options, "options");
        return provider(source).decode(source, options);
    }

    /**
     * Decodes a still image file, or the first frame of an animated one, at full size under the
     * default limits.
     *
     * @param file the file, opened for the call and closed before it returns
     * @return the decoded image in its format's native picture representation
     * @throws MediaException.Unsupported if the format is unknown or not decodable
     * @throws MediaException.Malformed if the image is corrupt
     * @throws MediaException.LimitExceeded if the image exceeds the limits
     * @throws MediaException.Io if the file cannot be opened or read
     */
    public static DecodedImage decode(Path file) {
        return decode(file, ImageDecoderOptions.defaults());
    }

    /**
     * Decodes a still image file, or the first frame of an animated one.
     *
     * @param file the file, opened for the call and closed before it returns
     * @param options the limits and the reduced-size hint
     * @return the decoded image in its format's native picture representation
     * @throws MediaException.Unsupported if the format is unknown or not decodable
     * @throws MediaException.Malformed if the image is corrupt
     * @throws MediaException.LimitExceeded if the image exceeds the limits
     * @throws MediaException.Io if the file cannot be opened or read
     */
    public static DecodedImage decode(Path file, ImageDecoderOptions options) {
        Objects.requireNonNull(options, "options");
        try (var source = MediaSource.of(file)) {
            return decode(source, options);
        }
    }

    /**
     * Opens an image as an animation under the default limits.
     *
     * @param source the input
     * @return the reader, which the caller closes
     * @throws NullPointerException         if {@code source} is {@code null}
     * @throws MediaException.Unsupported   if the format is unknown or not decodable
     * @throws MediaException.Malformed     if the image is corrupt
     * @throws MediaException.LimitExceeded if the image exceeds the limits
     * @throws MediaException.Io            if reading the source fails
     * @see #openAnimation(MediaSource, ImageDecoderOptions)
     */
    public static AnimationReader openAnimation(MediaSource source) {
        return openAnimation(source, ImageDecoderOptions.defaults());
    }

    /**
     * Opens an image as an animation.
     *
     * <p>GIF, APNG, ANI and animated WebP files yield their frames; every other decodable image yields a
     * single frame, shown for zero microseconds and played once, so callers that build animations
     * can accept still images too. Frames are {@link RgbaPicture}s, except that the AVIF and HEIC
     * readers return {@link Rgba16Picture}s for sources deeper than 8 bits under
     * {@link ImageDecoderOptions#highBitDepth()}.
     *
     * @param source  the input
     * @param options the limits and the bit depth choice; the reduced-size hint is ignored
     * @return the reader, which the caller closes
     * @throws NullPointerException         if an argument is {@code null}
     * @throws MediaException.Unsupported   if the format is unknown or not decodable
     * @throws MediaException.Malformed     if the image is corrupt
     * @throws MediaException.LimitExceeded if the image exceeds the limits
     * @throws MediaException.Io            if reading the source fails
     */
    public static AnimationReader openAnimation(MediaSource source, ImageDecoderOptions options) {
        Objects.requireNonNull(options, "options");
        var full = options.toBuilder().fullSize().build();
        var provider = provider(source);
        var animation = provider.openAnimation(source, full);
        if (animation.isPresent()) {
            return animation.get();
        }
        return new StillAnimationReader(ImageOps.toRgba(provider.decode(source, full).picture()));
    }

    /**
     * Opens the pages of an image under the default limits.
     *
     * @param source the input
     * @return the reader, which the caller closes
     * @throws NullPointerException         if {@code source} is {@code null}
     * @throws MediaException.Unsupported   if the format is unknown or not decodable
     * @throws MediaException.Malformed     if the image is corrupt
     * @throws MediaException.LimitExceeded if the page count exceeds the limits
     * @throws MediaException.Io            if reading the source fails
     * @see #openPages(MediaSource, ImageDecoderOptions)
     */
    public static PageReader openPages(MediaSource source) {
        return openPages(source, ImageDecoderOptions.defaults());
    }

    /**
     * Opens the pages of an image, the independent images a multi-image file holds.
     *
     * <p>A multi-page TIFF yields one page per image file directory of its main chain, and an icon
     * or cursor one page per entry. A HEIF file (HEIC or AVIF) yields one page per master image, the
     * image {@link #decode(MediaSource, ImageDecoderOptions)} returns first, each page decoded by the
     * artifact that implements its coding, and reports its stereo pairs, bursts and other entity groups
     * through {@link PageReader#groups()}. Every other decodable image, an animated one included, yields
     * a single page holding what {@link #decode(MediaSource, ImageDecoderOptions)} returns (the
     * first frame of an animation, whose frames {@link #openAnimation(MediaSource,
     * ImageDecoderOptions)} reads).
     *
     * @param source  the input
     * @param options the limits, the reduced-size hint and the bit depth choice, applied to every
     *                page
     * @return the reader, which the caller closes
     * @throws NullPointerException         if an argument is {@code null}
     * @throws MediaException.Unsupported   if the format is unknown or not decodable
     * @throws MediaException.Malformed     if the image is corrupt
     * @throws MediaException.LimitExceeded if the page count exceeds the limits
     * @throws MediaException.Io            if reading the source fails
     */
    public static PageReader openPages(MediaSource source, ImageDecoderOptions options) {
        Objects.requireNonNull(options, "options");
        var provider = provider(source);
        var pages = provider.openPages(source, options);
        if (pages.isPresent()) {
            return pages.get();
        }
        return new SinglePageReader(provider, source, options);
    }

    /**
     * Encodes a picture into a format at the format's default settings.
     *
     * @param picture the pixels to encode
     * @param format  the format to produce
     * @return the complete encoded image
     * @throws NullPointerException       if an argument is {@code null}
     * @throws MediaException.Unsupported if the format is not encodable
     */
    public static byte[] encode(Picture picture, ImageFormat format) {
        return encode(picture, format, ImageEncoderOptions.defaults());
    }

    /**
     * Encodes a picture into a format.
     *
     * <p>The metadata of the options is recorded as
     * {@link #encode(Picture, ImageFormat, ImageEncoderOptions, MediaSink)} describes.
     *
     * @param picture the pixels to encode
     * @param format  the format to produce
     * @param options the lossless flag, the quality and the metadata
     * @return the complete encoded image
     * @throws NullPointerException       if an argument is {@code null}
     * @throws IllegalArgumentException   if the color profile does not describe the picture, or the
     *                                    EXIF block exceeds what the format stores
     * @throws MediaException.Unsupported if the format is not encodable, or cannot honor the
     *                                    options (a lossless picture with more bits per sample than
     *                                    the format holds, unless its samples are exactly those of an
     *                                    8-bit picture)
     */
    public static byte[] encode(Picture picture, ImageFormat format, ImageEncoderOptions options) {
        var sink = MediaSink.memory();
        encode(picture, format, options, sink);
        return sink.toByteArray();
    }

    /**
     * Encodes a picture into a format and writes it to a sink, from the sink's position.
     *
     * <p>A JPEG encoding of a picture with alpha drops the alpha; a PNG ignores the quality; a
     * WebP and a JPEG code the picture losslessly when {@link ImageEncoderOptions#isLossless()} and
     * lossily otherwise. The file records the color profile, EXIF block, XMP packet and orientation of
     * the options where the format has a place for them, as {@link ImageEncoderOptions} describes; a
     * format that cannot record the profile holds the pixels converted to sRGB, and a format that
     * records no orientation holds the picture turned upright.
     *
     * @apiNote {@link ImageEncoderOptions.Builder#metadataOf(DecodedImage)} carries the metadata of a
     * decoded image over to its encoding.
     * @param picture the pixels to encode
     * @param format  the format to produce
     * @param options the lossless flag, the quality and the metadata
     * @param sink    the output; not closed
     * @throws NullPointerException       if an argument is {@code null}
     * @throws IllegalArgumentException   if the color profile does not describe the picture, or the
     *                                    EXIF block exceeds what the format stores (65527 bytes in
     *                                    JPEG)
     * @throws MediaException.Unsupported if the format is not encodable, or cannot honor the
     *                                    options (a lossless picture with more bits per sample than
     *                                    the format holds, unless its samples are exactly those of an
     *                                    8-bit picture)
     * @throws MediaException.Io          if writing the sink fails
     */
    public static void encode(Picture picture, ImageFormat format, ImageEncoderOptions options, MediaSink sink) {
        Objects.requireNonNull(picture, "picture");
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(sink, "sink");
        var provider = encoder(format);
        if (provider == null) {
            throw Artifacts.unsupported(Artifacts.nameOf(format), "encodable", Artifacts.imageEncoderOf(format));
        }
        provider.encode(picture, format, options, sink);
    }

    /**
     * Opens a writer that encodes an animation of a format into a sink at the format's default
     * settings, looping forever.
     *
     * @param format the format to produce
     * @param width  the canvas width, positive
     * @param height the canvas height, positive
     * @param sink   the output, written from its position; not closed
     * @return the writer, which the caller finishes and closes
     * @throws NullPointerException         if an argument is {@code null}
     * @throws IllegalArgumentException     if a dimension is not positive
     * @throws MediaException.LimitExceeded if a dimension exceeds what the format stores
     * @throws MediaException.Unsupported   if animations of the format are not encodable
     * @see #openAnimationWriter(ImageFormat, int, int, ImageEncoderOptions, MediaSink)
     */
    public static AnimationWriter openAnimationWriter(ImageFormat format, int width, int height, MediaSink sink) {
        return openAnimationWriter(format, width, height, ImageEncoderOptions.defaults(), sink);
    }

    /**
     * Opens a writer that encodes an animation of a format into a sink.
     *
     * <p>Frames are written with {@link AnimationWriter#write(RgbaPicture, long)} and the file is
     * completed with {@link AnimationWriter#finish()}. A WebP animation codes its frames
     * losslessly when {@link ImageEncoderOptions#isLossless()} and lossily otherwise; a GIF
     * animation quantizes its frames to palettes whose size follows the quality, and a PNG
     * animation (APNG) is always lossless. Every format loops
     * {@link ImageEncoderOptions#loopCount()} times, except an animated cursor (ANI), which always
     * loops forever and rejects any other loop count. The frames are in the options' color profile,
     * which an APNG, WebP, GIF or AVIF animation records and an ANI animation converts each frame to
     * sRGB from; the EXIF block and XMP packet are recorded as
     * {@link #encode(Picture, ImageFormat, ImageEncoderOptions, MediaSink)} records them, and an
     * orientation other than {@link Orientation#NORMAL} is accepted only by the formats that record it.
     *
     * @param format  the format to produce
     * @param width   the canvas width, positive
     * @param height  the canvas height, positive
     * @param options the lossless flag, the quality, the loop count and the metadata
     * @param sink    the output, written from its position; not closed
     * @return the writer, which the caller finishes and closes
     * @throws NullPointerException         if an argument is {@code null}
     * @throws IllegalArgumentException     if a dimension is not positive, the loop count
     *                                      exceeds what the format stores or the color profile is
     *                                      neither RGB nor gray
     * @throws MediaException.LimitExceeded if a dimension exceeds what the format stores
     * @throws MediaException.Unsupported   if animations of the format are not encodable, or the
     *                                      format cannot honor the options (an orientation a GIF or
     *                                      ANI animation cannot record)
     */
    public static AnimationWriter openAnimationWriter(ImageFormat format, int width, int height,
                                                      ImageEncoderOptions options, MediaSink sink) {
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(sink, "sink");
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("invalid canvas size " + width + "x" + height);
        }
        var provider = animationEncoder(format);
        if (provider == null) {
            throw Artifacts.unsupported(Artifacts.nameOf(format), "encodable as an animation",
                    Artifacts.imageAnimationEncoderOf(format));
        }
        return provider.openAnimationWriter(format, width, height, options, sink);
    }

    /**
     * Opens a writer that encodes the pages of a multi-image file of a format into a sink at the
     * format's default settings.
     *
     * @param format the format to produce
     * @param sink the output, written from its position; not closed
     * @return the writer, which the caller finishes and closes
     * @throws NullPointerException if an argument is {@code null}
     * @throws MediaException.Unsupported if multi-page images of the format are not encodable
     * @see #openPageWriter(ImageFormat, ImageEncoderOptions, MediaSink)
     */
    public static PageWriter openPageWriter(ImageFormat format, MediaSink sink) {
        return openPageWriter(format, ImageEncoderOptions.defaults(), sink);
    }

    /**
     * Opens a writer that encodes the pages of a multi-image file of a format into a sink.
     *
     * <p>Pages are written with {@link PageWriter#write(Picture)} and the file is completed with
     * {@link PageWriter#finish()}; {@link #openPages(MediaSource, ImageDecoderOptions)} reads them
     * back in order. A TIFF holds one page per picture, losslessly, with the page metadata of
     * {@link PageWriter#write(DecodedImage)}; an icon holds one entry per picture, a picture larger
     * than 256 pixels on a side being scaled into a 256-pixel square entry. The quality and the
     * lossless flag apply as {@link #encode(Picture, ImageFormat, ImageEncoderOptions, MediaSink)}
     * applies them to each page, and so does the metadata of the options to every page written with
     * {@link PageWriter#write(Picture)}.
     *
     * @param format the format to produce
     * @param options the lossless flag, the quality and the metadata of the pictures
     * @param sink the output, written from its position; not closed
     * @return the writer, which the caller finishes and closes
     * @throws NullPointerException if an argument is {@code null}
     * @throws MediaException.Unsupported if multi-page images of the format are not encodable
     */
    public static PageWriter openPageWriter(ImageFormat format, ImageEncoderOptions options, MediaSink sink) {
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(sink, "sink");
        var provider = pageEncoder(format);
        if (provider == null) {
            throw Artifacts.unsupported(Artifacts.nameOf(format), "encodable with several pages",
                    Artifacts.imagePageEncoderOf(format));
        }
        return provider.openPageWriter(format, options, sink);
    }

    /**
     * Recognizes the format of an image and finds the provider that decodes it.
     *
     * @param source the input
     * @return the first provider whose {@link ImageCodecProvider#decodes(ImageFormat)} accepts the
     *         recognized format
     * @throws NullPointerException       if {@code source} is {@code null}
     * @throws MediaException.Unsupported if the bytes match no known image format, or no provider
     *                                    decodes the recognized one
     * @throws MediaException.Io          if reading the source fails
     */
    private static ImageCodecProvider provider(MediaSource source) {
        var format = detect(source).orElseThrow(() ->
                new MediaException.Unsupported("unknown image", "the input is not a recognized image format"));
        var provider = decoder(format);
        if (provider == null) {
            throw Artifacts.unsupported(Artifacts.nameOf(format), "decodable", Artifacts.imageDecoderOf(format));
        }
        return provider;
    }

    /**
     * Finds the provider that decodes a format.
     *
     * @param format the format to decode
     * @return the first provider whose {@link ImageCodecProvider#decodes(ImageFormat)} accepts {@code format}, or
     * {@code null}
     */
    private static ImageCodecProvider decoder(ImageFormat format) {
        for (var provider : Providers.image()) {
            if (provider.decodes(format)) {
                return provider;
            }
        }
        return null;
    }

    /**
     * Finds the provider that encodes a format.
     *
     * @param format the format to encode
     * @return the first provider whose {@link ImageCodecProvider#encodes(ImageFormat)} accepts {@code format}, or
     * {@code null}
     */
    private static ImageCodecProvider encoder(ImageFormat format) {
        for (var provider : Providers.image()) {
            if (provider.encodes(format)) {
                return provider;
            }
        }
        return null;
    }

    /**
     * Finds the provider that encodes animations of a format.
     *
     * @param format the format to encode animations in
     * @return the first provider whose {@link ImageCodecProvider#encodesAnimation(ImageFormat)} accepts {@code format},
     * or {@code null}
     */
    private static ImageCodecProvider animationEncoder(ImageFormat format) {
        for (var provider : Providers.image()) {
            if (provider.encodesAnimation(format)) {
                return provider;
            }
        }
        return null;
    }

    /**
     * Finds the provider that encodes multi-page images of a format.
     *
     * @param format the format to encode pages in
     * @return the first provider whose {@link ImageCodecProvider#encodesPages(ImageFormat)} accepts {@code format},
     * or {@code null}
     */
    private static ImageCodecProvider pageEncoder(ImageFormat format) {
        for (var provider : Providers.image()) {
            if (provider.encodesPages(format)) {
                return provider;
            }
        }
        return null;
    }

    /**
     * Presents an image of a single-image format as a one-page reader.
     */
    private static final class SinglePageReader implements PageReader {
        /**
         * The provider decoding the image.
         */
        private final ImageCodecProvider provider;

        /**
         * The input.
         */
        private final MediaSource source;

        /**
         * The decoding options.
         */
        private final ImageDecoderOptions options;

        /**
         * Constructs the reader.
         *
         * @param provider the provider decoding the image
         * @param source   the input
         * @param options  the decoding options
         */
        private SinglePageReader(ImageCodecProvider provider, MediaSource source, ImageDecoderOptions options) {
            this.provider = provider;
            this.source = source;
            this.options = options;
        }

        @Override
        public int pageCount() {
            return 1;
        }

        @Override
        public ImageInfo probe(int index) {
            Objects.checkIndex(index, 1);
            var info = provider.probe(source);
            return new ImageInfo(info.format(), info.width(), info.height(), info.hasAlpha(), false, 1,
                    info.orientation());
        }

        @Override
        public DecodedImage read(int index) {
            Objects.checkIndex(index, 1);
            return provider.decode(source, options);
        }
    }

    /**
     * Presents a still picture as a one-frame animation.
     */
    private static final class StillAnimationReader implements AnimationReader {
        /**
         * The only frame.
         */
        private final RgbaPicture frame;

        /**
         * Whether {@link #read()} already returned the frame.
         */
        private boolean consumed;

        /**
         * Constructs the reader.
         *
         * @param frame the only frame
         */
        private StillAnimationReader(RgbaPicture frame) {
            this.frame = frame;
        }

        @Override
        public int width() {
            return frame.width();
        }

        @Override
        public int height() {
            return frame.height();
        }

        @Override
        public int loopCount() {
            return 1;
        }

        @Override
        public int frameCount() {
            return 1;
        }

        @Override
        public AnimationFrame read() {
            if (consumed) {
                return null;
            }
            consumed = true;
            return new AnimationFrame(frame, 0, 0);
        }

        /**
         * {@inheritDoc}
         *
         * @return {@code true}
         */
        @Override
        public boolean isSeekable() {
            return true;
        }

        /**
         * {@inheritDoc}
         *
         * <p>The frame is shown for zero microseconds from time zero, so a time before zero selects
         * it and any other time is past the end.
         */
        @Override
        public void seek(long micros) {
            consumed = micros >= 0;
        }
    }
}
