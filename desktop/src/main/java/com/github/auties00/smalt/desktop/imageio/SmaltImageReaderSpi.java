package com.github.auties00.smalt.desktop.imageio;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Images;

import javax.imageio.ImageReader;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.spi.ServiceRegistry;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Registers {@link SmaltImageReader} with ImageIO for the image formats the Smalt codecs present at
 * run time decode and the JDK does not.
 *
 * <p>The formats are those {@link com.github.auties00.smalt.image.Images#isDecodable(ImageFormat)}
 * accepts when the provider is created, less the formats the JDK's own plug-ins read (JPEG, PNG,
 * GIF, BMP, TIFF): with {@code smalt-image} that is WebP (still and animated), and AVIF or HEIC
 * appear as soon as an artifact that decodes them is present, with no change here. Animated PNG
 * (APNG) is the one JDK format served too: {@link #canDecodeInput(Object)} accepts a PNG only when
 * it declares an animation, so still PNGs stay with the JDK's reader, and the provider orders
 * itself before the JDK's PNG reader so that {@link javax.imageio.ImageIO#read(java.io.File)}
 * reaches it for animated ones.
 *
 * <p>When no such format is decodable (no codec artifact is present), the provider deregisters
 * itself as soon as it is registered.
 *
 * <p>This class is thread-safe.
 */
public final class SmaltImageReaderSpi extends ImageReaderSpi {
    /**
     * How many leading bytes {@link #canDecodeInput(Object)} inspects.
     */
    private static final int PEEK_LENGTH = 64 * 1024;

    /**
     * The formats served, fixed when the provider is created.
     */
    private final List<ImageFormat> formats;

    /**
     * Constructs the provider for the formats decodable at run time.
     */
    public SmaltImageReaderSpi() {
        this(ImageIoFormats.readable());
    }

    /**
     * Constructs the provider for a list of formats.
     *
     * @param formats the formats served
     */
    private SmaltImageReaderSpi(List<ImageFormat> formats) {
        super("Smalt", ImageIoFormats.version(),
                names(formats), ImageIoFormats.collect(formats, format -> ImageIoFormats.suffixes(format, true)),
                ImageIoFormats.collect(formats, format -> ImageIoFormats.mimeTypes(format, true)),
                SmaltImageReader.class.getName(), new Class<?>[]{ImageInputStream.class}, null,
                false, null, null, null, null,
                true, SmaltImageMetadataFormat.NAME, SmaltImageMetadataFormat.class.getName(), null, null);
        this.formats = formats;
    }

    /**
     * Returns the format names of the served formats.
     *
     * @param formats the served formats
     * @return the names; a placeholder name when no format is served, because ImageIO requires one
     */
    private static String[] names(List<ImageFormat> formats) {
        var names = ImageIoFormats.collect(formats, format -> ImageIoFormats.names(format, true));
        return names.length == 0 ? new String[]{"smalt"} : names;
    }

    /**
     * Returns the formats this provider serves.
     *
     * @return the formats, fixed when the provider was created
     */
    public List<ImageFormat> formats() {
        return formats;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Deregisters the provider when it serves no format, and otherwise orders it before every
     * other reader of PNG, which then still reads every PNG this provider declines, and, when it
     * serves DNG or camera raw files, before every other reader of TIFF, which then still reads
     * every TIFF file this provider declines.
     */
    @Override
    public void onRegistration(ServiceRegistry registry, Class<?> category) {
        if (formats.isEmpty()) {
            registry.deregisterServiceProvider(this, ImageReaderSpi.class);
            return;
        }
        if (formats.contains(ImageFormat.DNG) || formats.contains(ImageFormat.RAW)) {
            ImageIoFormats.order(registry, this, "tiff", true);
        }
        if (formats.contains(ImageFormat.PNG)) {
            ImageIoFormats.order(registry, this, "png", true);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Recognizes the format from the leading bytes, never from a file name, and accepts it when
     * this provider serves it; a PNG is accepted only when an {@code acTL} chunk precedes its first
     * {@code IDAT} chunk. The stream's position is restored.
     */
    @Override
    public boolean canDecodeInput(Object source) throws IOException {
        if (!(source instanceof ImageInputStream stream)) {
            return false;
        }
        var header = ImageInputStreamSource.peek(stream, PEEK_LENGTH);
        var format = ImageFormat.detect(header, header.length).orElse(null);
        if (format == ImageFormat.TIFF && (formats.contains(ImageFormat.DNG) || formats.contains(ImageFormat.RAW))) {
            format = followDirectories(stream);
        }
        if (format == null || !formats.contains(format)) {
            return false;
        }
        return format != ImageFormat.PNG || isAnimatedPng(header);
    }

    /**
     * Recognizes a TIFF-structured input whose directories lie beyond the peeked bytes, by
     * following them through the stream: a camera raw file is then told from a TIFF file. The
     * stream's position is restored.
     *
     * @param stream the stream, positioned at the first byte of the image
     * @return the recognized format
     * @throws IOException if reading the stream fails
     */
    private static ImageFormat followDirectories(ImageInputStream stream) throws IOException {
        stream.mark();
        try {
            return Images.detect(ImageInputStreamSource.of(stream)).orElse(null);
        } catch (MediaException exception) {
            return ImageFormat.TIFF;
        } finally {
            stream.reset();
        }
    }

    /**
     * Returns whether the leading bytes of a PNG declare an animation.
     *
     * @param header the leading bytes, starting with the PNG signature
     * @return {@code true} when an {@code acTL} chunk appears before the first {@code IDAT} chunk
     * within {@code header}
     */
    static boolean isAnimatedPng(byte[] header) {
        var position = 8L;
        while (position + 8 <= header.length) {
            var at = (int) position;
            var length = ((header[at] & 0xFFL) << 24) | ((header[at + 1] & 0xFF) << 16)
                    | ((header[at + 2] & 0xFF) << 8) | (header[at + 3] & 0xFF);
            var type = new String(header, at + 4, 4, StandardCharsets.ISO_8859_1);
            if (type.equals("acTL")) {
                return true;
            }
            if (type.equals("IDAT")) {
                return false;
            }
            position += 12 + length;
        }
        return false;
    }

    @Override
    public ImageReader createReaderInstance(Object extension) {
        return new SmaltImageReader(this);
    }

    @Override
    public String getDescription(Locale locale) {
        return formats.stream()
                .map(format -> format == ImageFormat.PNG ? "APNG" : format.name())
                .collect(Collectors.joining(", ", "Smalt image reader (", ")"));
    }
}
