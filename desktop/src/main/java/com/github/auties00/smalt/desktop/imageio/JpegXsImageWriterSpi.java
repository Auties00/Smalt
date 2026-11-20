package com.github.auties00.smalt.desktop.imageio;

import com.github.auties00.smalt.image.ImageFormat;

/**
 * Registers the JPEG XS writer with ImageIO: {@link SmaltImageWriter} for {@link ImageFormat#JPEG_XS}.
 *
 * <p>It writes JXS files, lossy or lossless as {@link SmaltImageWriteParam} selects, when a codec artifact present
 * at run time ({@code smalt-mpeg}) encodes JPEG XS; the provider deregisters itself otherwise.
 */
public final class JpegXsImageWriterSpi extends SmaltImageWriterSpi {
    /**
     * Creates the provider.
     */
    public JpegXsImageWriterSpi() {
        super(ImageFormat.JPEG_XS);
    }
}
