package com.github.auties00.smalt.desktop.imageio;

/**
 * Names the ImageIO formats Smalt registers under names of its own, for the formats the JDK also
 * reads or writes.
 *
 * <p>Pass a name to {@link javax.imageio.ImageIO#write(java.awt.image.RenderedImage, String, java.io.File)}
 * or {@link javax.imageio.ImageIO#getImageReadersByFormatName(String)} to select Smalt's plug-in over the
 * JDK's. Formats the JDK does not handle are served under their usual names.
 */
public final class SmaltImageIo {
    /**
     * The format name of Smalt's TIFF reader and writer, {@value}.
     */
    public static final String TIFF = "smalt-tiff";

    /**
     * The format name of Smalt's GIF writer, {@value}.
     */
    public static final String GIF = "smalt-gif";

    /**
     * The format name of Smalt's BMP reader and writer, {@value}.
     */
    public static final String BMP = "smalt-bmp";

    /**
     * Prevents instantiation.
     */
    private SmaltImageIo() {
        throw new AssertionError();
    }
}
