package com.github.auties00.smalt.desktop.imageio;

import com.github.auties00.smalt.image.ImageFormat;

/**
 * Registers the JPEG XL writer with ImageIO: {@link SmaltImageWriter} for {@link ImageFormat#JXL}.
 *
 * <p>It writes still images and animation sequences, lossy or lossless as {@link SmaltImageWriteParam}
 * selects, when a codec artifact present at run time ({@code smalt-image}) encodes JPEG XL.
 */
public final class JxlImageWriterSpi extends SmaltImageWriterSpi {
    /**
     * Creates the provider.
     */
    public JxlImageWriterSpi() {
        super(ImageFormat.JXL);
    }
}
