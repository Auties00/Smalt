package com.github.auties00.smalt.desktop.imageio;

import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Images;

import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.spi.ServiceRegistry;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Names the Smalt image formats the way ImageIO does, and decides which of them the plug-ins
 * serve.
 *
 * <p>The plug-ins serve every {@link ImageFormat} that the JDK's own plug-ins do not read or write,
 * so a format added to {@link ImageFormat} and to a codec artifact is picked up without changes
 * here; the only JDK-handled format they also read is animated PNG (APNG), whose frames the JDK's
 * PNG reader ignores.
 */
final class ImageIoFormats {
    /**
     * Prevents instantiation.
     */
    private ImageIoFormats() {
        throw new AssertionError();
    }

    /**
     * Returns the version the providers report.
     *
     * @return the {@code Implementation-Version} of the jar manifest, or {@code "unknown"} when the
     * classes do not come from a jar that declares one
     */
    static String version() {
        var version = ImageIoFormats.class.getPackage().getImplementationVersion();
        return version != null ? version : "unknown";
    }

    /**
     * Returns whether the JDK's built-in ImageIO plug-ins read and write a format.
     *
     * @param format the image format to check
     * @return {@code true} for JPEG, PNG, GIF, BMP and TIFF
     */
    static boolean isJdkFormat(ImageFormat format) {
        return switch (format) {
            case JPEG, PNG, GIF, BMP, TIFF -> true;
            default -> false;
        };
    }

    /**
     * Returns the informal format names of a format, as ImageIO matches them in
     * {@link javax.imageio.ImageIO#getImageReadersByFormatName(String)}.
     *
     * @param format the image format to name
     * @param reader whether the names are for the reader, which serves PNG only as APNG
     * @return the names, lower case first
     */
    static List<String> names(ImageFormat format, boolean reader) {
        if (format == ImageFormat.PNG && reader) {
            return List.of("apng", "APNG");
        }
        var base = switch (format) {
            case HEIC -> List.of("heic", "heif");
            case JP2 -> List.of("jpeg2000", "jp2");
            case JPEG_XS -> List.of("jpeg-xs", "jxs");
            case RAW -> List.of("raw", "camera-raw");
            default -> List.of(format.name().toLowerCase(Locale.ROOT));
        };
        var names = new ArrayList<String>();
        for (var name : base) {
            names.add(name);
            names.add(name.toUpperCase(Locale.ROOT));
        }
        return names;
    }

    /**
     * Returns the file suffixes of a format.
     *
     * @param format the image format whose suffixes are returned
     * @param reader whether the suffixes are for the reader, which serves PNG only as APNG
     * @return the suffixes, without the dot
     */
    static List<String> suffixes(ImageFormat format, boolean reader) {
        if (format == ImageFormat.PNG && reader) {
            return List.of("apng");
        }
        return switch (format) {
            case HEIC -> List.of("heic", "heif", "hif");
            case JPEG -> List.of("jpg", "jpeg");
            case TIFF -> List.of("tif", "tiff");
            case ICO -> List.of("ico", "cur");
            // JPH files and HTJ2K codestreams are read; the writer writes them only when its options ask for it
            case JP2 -> reader ? List.of("jp2", "jpf", "jpx", "j2k", "j2c", "jph", "jhc")
                    : List.of("jp2", "jpf", "jpx", "j2k", "j2c");
            // raw codestreams are read; the writer writes JXS files
            case JPEG_XS -> reader ? List.of("jxs", "jxc") : List.of("jxs");
            case RAW -> List.of("cr2", "cr3", "crw", "nef", "nrw", "arw", "srf", "sr2", "raf", "orf", "rw2", "rwl",
                    "pef", "srw", "erf", "3fr", "fff", "mos", "mef", "dcr", "kdc", "mrw", "x3f");
            default -> List.of(format.name().toLowerCase(Locale.ROOT));
        };
    }

    /**
     * Returns the MIME types of a format.
     *
     * @param format the image format whose MIME types are returned
     * @param reader whether the types are for the reader, which serves PNG only as APNG
     * @return the MIME types
     */
    static List<String> mimeTypes(ImageFormat format, boolean reader) {
        if (format == ImageFormat.PNG && reader) {
            return List.of("image/apng", "image/vnd.mozilla.apng");
        }
        if (format == ImageFormat.HEIC) {
            return List.of(format.mimeType(), "image/heif");
        }
        if (format == ImageFormat.ICO) {
            return List.of(format.mimeType(), "image/x-icon");
        }
        if (format == ImageFormat.JP2) {
            return reader ? List.of(format.mimeType(), "image/jpx", "image/jph", "image/jphc")
                    : List.of(format.mimeType(), "image/jpx");
        }
        if (format == ImageFormat.DNG) {
            return List.of(format.mimeType(), "image/dng");
        }
        if (format == ImageFormat.RAW) {
            return List.of(format.mimeType(), "image/x-canon-cr2", "image/x-canon-cr3", "image/x-nikon-nef",
                    "image/x-sony-arw", "image/x-fuji-raf", "image/x-olympus-orf", "image/x-panasonic-rw2",
                    "image/x-pentax-pef");
        }
        return List.of(format.mimeType());
    }

    /**
     * Returns the formats the reader serves with the codec artifacts present at run time.
     *
     * @return every format {@link Images#isDecodable(ImageFormat)} accepts that is not a JDK format,
     * plus PNG (for APNG) when PNG is decodable
     */
    static List<ImageFormat> readable() {
        return select(format -> Images.isDecodable(format) && (!isJdkFormat(format) || format == ImageFormat.PNG));
    }

    /**
     * Collects the names, suffixes or MIME types of a list of formats.
     *
     * @param formats the image formats, in the order their values are collected
     * @param values the values of one format, such as {@code format -> names(format, true)}
     * @return the values without duplicates, in format order
     */
    static String[] collect(List<ImageFormat> formats, Function<ImageFormat, List<String>> values) {
        Set<String> result = new LinkedHashSet<>();
        for (var format : formats) {
            result.addAll(values.apply(format));
        }
        return result.toArray(String[]::new);
    }

    /**
     * Orders a reader provider before or after every other registered reader provider that serves
     * a format name.
     *
     * @param registry the registry the providers are registered in
     * @param self the provider to order
     * @param formatName the format name the other providers serve, matched ignoring case
     * @param selfFirst whether {@code self} goes before the other providers rather than after them
     */
    static void order(ServiceRegistry registry, ImageReaderSpi self, String formatName, boolean selfFirst) {
        var others = new ArrayList<ImageReaderSpi>();
        var providers = registry.getServiceProviders(ImageReaderSpi.class, false);
        while (providers.hasNext()) {
            var provider = providers.next();
            if (provider != self && serves(provider, formatName)) {
                others.add(provider);
            }
        }
        for (var other : others) {
            if (selfFirst) {
                registry.setOrdering(ImageReaderSpi.class, self, other);
            } else {
                registry.setOrdering(ImageReaderSpi.class, other, self);
            }
        }
    }

    /**
     * {@return whether a reader provider names a format among its format names, ignoring case}
     *
     * @param provider the reader provider to inspect
     * @param formatName the format name to look for
     */
    private static boolean serves(ImageReaderSpi provider, String formatName) {
        var names = provider.getFormatNames();
        if (names == null) {
            return false;
        }
        for (var name : names) {
            if (formatName.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Selects the formats matching a predicate.
     *
     * @param filter the predicate
     * @return the matching formats, in declaration order
     */
    private static List<ImageFormat> select(Predicate<ImageFormat> filter) {
        var result = new ArrayList<ImageFormat>();
        for (var format : ImageFormat.values()) {
            if (filter.test(format)) {
                result.add(format);
            }
        }
        return List.copyOf(result);
    }
}
