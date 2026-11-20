package com.github.auties00.smalt.desktop.imageio;

import com.github.auties00.smalt.image.ImageFormat;

import java.util.List;

/**
 * Registers Smalt's BMP writer with ImageIO: {@link SmaltImageWriter} for {@link ImageFormat#BMP}
 * under the format names {@value SmaltImageIo#BMP} and {@code SMALT-BMP} only, with no suffix and no MIME
 * type.
 *
 * <p>The JDK writes BMP too, so this writer takes a name of its own: {@code bmp}, the {@code bmp}
 * suffix and {@code image/bmp} stay with the JDK's writer, and which writer ImageIO picks for them never
 * depends on the class path. A caller selects this one by name, for example with
 * {@code ImageIO.write(image, "smalt-bmp", file)}. It writes the smallest lossless layout of the image:
 * a 1-, 4- or 8-bit palette when the image has few enough colors, 24-bit pixels otherwise, and 32-bit
 * pixels with an alpha mask in a V5 header when some pixel is not opaque. The provider deregisters itself
 * unless a codec artifact present at run time ({@code smalt-image}) encodes BMP.
 *
 * <p>This class is thread-safe.
 */
public final class BmpImageWriterSpi extends SmaltImageWriterSpi {
    /**
     * Constructs the provider.
     */
    public BmpImageWriterSpi() {
        super(ImageFormat.BMP, List.of(SmaltImageIo.BMP, "SMALT-BMP"), List.of(), List.of());
    }
}
