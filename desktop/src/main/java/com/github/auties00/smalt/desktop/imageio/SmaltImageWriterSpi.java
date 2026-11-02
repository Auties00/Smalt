package com.github.auties00.smalt.desktop.imageio;

import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Images;

import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.spi.ImageWriterSpi;
import javax.imageio.spi.ServiceRegistry;
import javax.imageio.stream.ImageOutputStream;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Registers {@link SmaltImageWriter} with ImageIO for one image format the JDK does not write.
 *
 * <p>ImageIO creates writers without telling them which format name was asked for, so each format
 * has its own provider: {@link WebpImageWriterSpi}, {@link AvifImageWriterSpi},
 * {@link HeicImageWriterSpi}, {@link IcoImageWriterSpi} and {@link Jp2ImageWriterSpi}, plus {@link ApngImageWriterSpi},
 * {@link GifImageWriterSpi}, {@link TiffImageWriterSpi} and {@link BmpImageWriterSpi}, which write formats the JDK
 * also writes under names of their own. Each deregisters itself when
 * no codec artifact present at run time encodes its format
 * ({@link Images#isEncodable(ImageFormat)}), so the AVIF and HEIC writers appear as soon as an
 * artifact that encodes those formats is added.
 */
public abstract sealed class SmaltImageWriterSpi extends ImageWriterSpi
        permits WebpImageWriterSpi, AvifImageWriterSpi, HeicImageWriterSpi, ApngImageWriterSpi, GifImageWriterSpi,
        IcoImageWriterSpi, Jp2ImageWriterSpi, TiffImageWriterSpi, BmpImageWriterSpi, JxlImageWriterSpi {
    /**
     * The format written.
     */
    private final ImageFormat format;

    /**
     * Constructs the provider of a format.
     *
     * @param format the format written
     */
    SmaltImageWriterSpi(ImageFormat format) {
        this(format, ImageIoFormats.names(format, false), ImageIoFormats.suffixes(format, false),
                ImageIoFormats.mimeTypes(format, false));
    }

    /**
     * Constructs the provider of a format under explicit names, for a format the JDK also writes.
     *
     * @param format the format written
     * @param names the informal format names, at least one
     * @param suffixes the file suffixes, possibly none
     * @param mimeTypes the MIME types, possibly none
     */
    SmaltImageWriterSpi(ImageFormat format, List<String> names, List<String> suffixes, List<String> mimeTypes) {
        super("Smalt", ImageIoFormats.version(),
                names.toArray(String[]::new),
                suffixes.toArray(String[]::new),
                mimeTypes.toArray(String[]::new),
                SmaltImageWriter.class.getName(), new Class<?>[]{ImageOutputStream.class},
                new String[]{SmaltImageReaderSpi.class.getName()},
                false, null, null, null, null,
                true, SmaltImageMetadataFormat.NAME, SmaltImageMetadataFormat.class.getName(), null, null);
        this.format = Objects.requireNonNull(format, "format");
    }

    /**
     * Returns the format this provider writes.
     *
     * @return the format
     */
    public ImageFormat format() {
        return format;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Deregisters the provider when no codec present at run time encodes its format.
     */
    @Override
    public void onRegistration(ServiceRegistry registry, Class<?> category) {
        if (!Images.isEncodable(format)) {
            registry.deregisterServiceProvider(this, ImageWriterSpi.class);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Every image of up to four bands is accepted: images other than the standard RGB, ARGB and
     * gray types are converted to sRGB first.
     */
    @Override
    public boolean canEncodeImage(ImageTypeSpecifier type) {
        var bands = type.getNumBands();
        return bands >= 1 && bands <= 4 && Images.isEncodable(format);
    }

    @Override
    public ImageWriter createWriterInstance(Object extension) {
        return new SmaltImageWriter(this, format);
    }

    @Override
    public String getDescription(Locale locale) {
        return "Smalt " + format.name() + " image writer";
    }
}
