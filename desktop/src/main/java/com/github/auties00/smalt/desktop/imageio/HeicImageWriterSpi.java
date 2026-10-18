package com.github.auties00.smalt.desktop.imageio;

import com.github.auties00.smalt.image.ImageFormat;

/**
 * Registers the HEIC writer with ImageIO: {@link SmaltImageWriter} for {@link ImageFormat#HEIC}.
 *
 * <p>The provider deregisters itself unless a codec artifact present at run time encodes HEIC.
 */
public final class HeicImageWriterSpi extends SmaltImageWriterSpi {
    /**
     * Creates the provider.
     */
    public HeicImageWriterSpi() {
        super(ImageFormat.HEIC);
    }
}
