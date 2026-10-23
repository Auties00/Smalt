package com.github.auties00.smalt.desktop.imageio;

import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Images;

import javax.imageio.ImageReader;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.spi.ServiceRegistry;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.util.Locale;

/**
 * Registers Smalt's BMP reader with ImageIO: {@link SmaltImageReader} for {@link ImageFormat#BMP}
 * under the format names {@value SmaltImageIo#BMP} and {@code SMALT-BMP} only, with no suffix and no MIME
 * type.
 *
 * <p>The JDK reads BMP too, so this reader takes a name of its own and orders itself after every
 * reader that serves {@code bmp}: {@link javax.imageio.ImageIO#read(java.io.File)} keeps using the
 * JDK's reader, and a caller selects this one by name, for example with
 * {@code ImageIO.getImageReadersByFormatName("smalt-bmp")}. Each bitmap of an OS/2 bitmap array is one
 * image index. Unlike the JDK's reader it decodes 64-bit pixels, OS/2 Huffman 1D and RLE24 data, CMYK
 * pixels, bitmap arrays and color masks of any width, and reports calibrated color spaces. The provider
 * deregisters itself unless a codec artifact present at run time ({@code smalt-image}) decodes BMP.
 *
 * <p>This class is thread-safe.
 */
public final class BmpImageReaderSpi extends ImageReaderSpi {
    /**
     * How many leading bytes {@link #canDecodeInput(Object)} inspects.
     */
    private static final int PEEK_LENGTH = ImageFormat.DETECTION_LENGTH;

    /**
     * Constructs the provider.
     */
    public BmpImageReaderSpi() {
        super("Smalt", ImageIoFormats.version(), new String[]{SmaltImageIo.BMP, "SMALT-BMP"}, new String[0],
                new String[0], SmaltImageReader.class.getName(), new Class<?>[]{ImageInputStream.class},
                new String[]{BmpImageWriterSpi.class.getName()},
                false, null, null, null, null,
                true, SmaltImageMetadataFormat.NAME, SmaltImageMetadataFormat.class.getName(), null, null);
    }

    /**
     * Deregisters the provider when no artifact decodes BMP, and otherwise orders it after every
     * other reader that serves the {@code bmp} format name.
     *
     * @param registry the registry this provider is registered in
     * @param category the category registered under
     */
    @Override
    public void onRegistration(ServiceRegistry registry, Class<?> category) {
        if (!Images.isDecodable(ImageFormat.BMP)) {
            registry.deregisterServiceProvider(this, ImageReaderSpi.class);
            return;
        }
        ImageIoFormats.order(registry, this, "bmp", false);
    }

    /**
     * Returns whether the input is a BMP file or an OS/2 bitmap array.
     *
     * @param source the input
     * @return {@code true} for an {@link ImageInputStream} whose leading bytes are a BMP header
     * @throws IOException if reading the stream fails
     */
    @Override
    public boolean canDecodeInput(Object source) throws IOException {
        if (!(source instanceof ImageInputStream stream)) {
            return false;
        }
        var header = ImageInputStreamSource.peek(stream, PEEK_LENGTH);
        return ImageFormat.detect(header, header.length).filter(ImageFormat.BMP::equals).isPresent();
    }

    /**
     * Creates a reader.
     *
     * @param extension ignored
     * @return a new {@link SmaltImageReader}
     */
    @Override
    public ImageReader createReaderInstance(Object extension) {
        return new SmaltImageReader(this);
    }

    /**
     * Describes the provider.
     *
     * @param locale ignored
     * @return the description
     */
    @Override
    public String getDescription(Locale locale) {
        return "Smalt BMP image reader";
    }
}
