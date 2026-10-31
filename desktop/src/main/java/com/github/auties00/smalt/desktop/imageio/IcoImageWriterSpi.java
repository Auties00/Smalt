package com.github.auties00.smalt.desktop.imageio;

import com.github.auties00.smalt.image.ImageFormat;

/**
 * Registers the icon writer with ImageIO: {@link SmaltImageWriter} for {@link ImageFormat#ICO}
 * under the format names {@code ico} and {@code ICO}, the suffixes {@code ico} and {@code cur}, and
 * the MIME types {@code image/vnd.microsoft.icon} and {@code image/x-icon}.
 *
 * <p>An image becomes a one-entry icon of its own size, or a 256-pixel square entry it is scaled
 * into when it is larger than 256 pixels on a side; a sequence
 * ({@link SmaltImageWriter#prepareWriteSequence(javax.imageio.metadata.IIOMetadata)}) writes one entry per
 * image. The provider deregisters itself unless a codec
 * artifact present at run time ({@code smalt-image}) encodes ICO.
 */
public final class IcoImageWriterSpi extends SmaltImageWriterSpi {
    /**
     * Creates the provider.
     */
    public IcoImageWriterSpi() {
        super(ImageFormat.ICO);
    }
}
