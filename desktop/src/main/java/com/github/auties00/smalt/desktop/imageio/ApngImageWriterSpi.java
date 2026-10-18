package com.github.auties00.smalt.desktop.imageio;

import com.github.auties00.smalt.image.ImageFormat;

/**
 * Registers the APNG writer with ImageIO: {@link SmaltImageWriter} for {@link ImageFormat#PNG}
 * under the format names {@code apng} and {@code APNG}, the suffix {@code apng} and the MIME types
 * {@code image/apng} and {@code image/vnd.mozilla.apng}, the names the APNG reader serves.
 *
 * <p>Sequences become animated PNG files; a single image written outside a sequence is a plain
 * PNG. The plain {@code png} name stays with the JDK's PNG writer, which writes no animations, so
 * {@code ImageIO.write(image, "png", file)} keeps its behavior. The provider deregisters itself
 * unless a codec artifact present at run time ({@code smalt-image}) encodes PNG.
 *
 * <p>This class is thread-safe.
 */
public final class ApngImageWriterSpi extends SmaltImageWriterSpi {
    /**
     * Constructs the provider.
     */
    public ApngImageWriterSpi() {
        super(ImageFormat.PNG, ImageIoFormats.names(ImageFormat.PNG, true), ImageIoFormats.suffixes(ImageFormat.PNG, true),
                ImageIoFormats.mimeTypes(ImageFormat.PNG, true));
    }
}
