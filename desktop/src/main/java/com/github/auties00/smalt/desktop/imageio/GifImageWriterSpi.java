package com.github.auties00.smalt.desktop.imageio;

import com.github.auties00.smalt.image.ImageFormat;

import java.util.List;

/**
 * Registers Smalt's GIF writer with ImageIO: {@link SmaltImageWriter} for {@link ImageFormat#GIF}
 * under the format names {@value SmaltImageIo#GIF} and {@code SMALT-GIF} only, with no suffix and no MIME
 * type.
 *
 * <p>The JDK writes GIF too, so this writer takes a name of its own: {@code gif}, the {@code gif}
 * suffix and {@code image/gif} stay with the JDK's writer, and which writer ImageIO picks for them
 * never depends on the class path. A caller selects this one by name, for example with
 * {@code ImageIO.write(image, "smalt-gif", file)} or
 * {@code ImageIO.getImageWritersByFormatName("smalt-gif")}. Unlike the JDK's writer, which needs
 * images that already have at most 256 colors, it quantizes any image (with dithering), and it
 * writes sequences as animations whose frames are coded as changed rectangles. The provider
 * deregisters itself unless a codec artifact present at run time ({@code smalt-image}) encodes
 * GIF.
 *
 * <p>This class is thread-safe.
 */
public final class GifImageWriterSpi extends SmaltImageWriterSpi {
    /**
     * Constructs the provider.
     */
    public GifImageWriterSpi() {
        super(ImageFormat.GIF, List.of(SmaltImageIo.GIF, "SMALT-GIF"), List.of(), List.of());
    }
}
