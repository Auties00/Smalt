package com.github.auties00.smalt.desktop.imageio;

import com.github.auties00.smalt.image.ImageFormat;

/**
 * Registers the JPEG 2000 writer with ImageIO: {@link SmaltImageWriter} for {@link ImageFormat#JP2}
 * under the format names {@code jpeg2000} and {@code jp2}, the suffixes {@code jp2}, {@code jpf},
 * {@code jpx}, {@code j2k} and {@code j2c}, and the MIME types {@code image/jp2} and
 * {@code image/jpx}.
 *
 * <p>It writes JP2 files, lossless or lossy as {@link SmaltImageWriteParam} selects, when a codec
 * artifact present at run time ({@code smalt-image}) encodes JPEG 2000; the JDK has no JPEG 2000
 * plug-in.
 */
public final class Jp2ImageWriterSpi extends SmaltImageWriterSpi {
    /**
     * Creates the provider.
     */
    public Jp2ImageWriterSpi() {
        super(ImageFormat.JP2);
    }
}
