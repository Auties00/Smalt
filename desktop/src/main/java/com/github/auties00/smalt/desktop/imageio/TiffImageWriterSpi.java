package com.github.auties00.smalt.desktop.imageio;

import com.github.auties00.smalt.image.ImageFormat;

import java.util.List;

/**
 * Registers Smalt's TIFF writer with ImageIO: {@link SmaltImageWriter} for {@link ImageFormat#TIFF}
 * under the format names {@value SmaltImageIo#TIFF} and {@code SMALT-TIFF} only, with no suffix and no MIME
 * type.
 *
 * <p>The JDK writes TIFF too, so this writer takes a name of its own: {@code tiff}, the
 * {@code tif} and {@code tiff} suffixes and {@code image/tiff} stay with the JDK's writer, and which
 * writer ImageIO picks for them never depends on the class path. A caller selects this one by
 * name, for example with {@code ImageIO.write(image, "smalt-tiff", file)}. It writes LZW with the
 * horizontal predictor, gray or RGB with or without alpha, at 8 or 16 bits as the image holds them,
 * and a sequence writes one page per image.
 * The provider deregisters itself unless a codec artifact present at run time ({@code smalt-image})
 * encodes TIFF.
 *
 * <p>This class is thread-safe.
 */
public final class TiffImageWriterSpi extends SmaltImageWriterSpi {
    /**
     * Constructs the provider.
     */
    public TiffImageWriterSpi() {
        super(ImageFormat.TIFF, List.of(SmaltImageIo.TIFF, "SMALT-TIFF"), List.of(), List.of());
    }
}
