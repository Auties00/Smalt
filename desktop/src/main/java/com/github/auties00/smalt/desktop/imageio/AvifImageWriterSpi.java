package com.github.auties00.smalt.desktop.imageio;

import com.github.auties00.smalt.image.ImageFormat;

/**
 * Registers the AVIF writer with ImageIO: {@link SmaltImageWriter} for {@link ImageFormat#AVIF}.
 *
 * <p>The provider deregisters itself unless a codec artifact present at run time encodes AVIF.
 */
public final class AvifImageWriterSpi extends SmaltImageWriterSpi {
    /**
     * Creates the provider.
     */
    public AvifImageWriterSpi() {
        super(ImageFormat.AVIF);
    }
}
