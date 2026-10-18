package com.github.auties00.smalt.desktop.imageio;

import com.github.auties00.smalt.image.ImageFormat;

/**
 * Registers the WebP writer with ImageIO: {@link SmaltImageWriter} for {@link ImageFormat#WEBP}.
 *
 * <p>It writes still images, lossy or lossless as {@link SmaltImageWriteParam} selects, when a
 * codec artifact present at run time ({@code smalt-image}) encodes WebP.
 */
public final class WebpImageWriterSpi extends SmaltImageWriterSpi {
    /**
     * Creates the provider.
     */
    public WebpImageWriterSpi() {
        super(ImageFormat.WEBP);
    }
}
