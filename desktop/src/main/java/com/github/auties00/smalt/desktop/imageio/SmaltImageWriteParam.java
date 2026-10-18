package com.github.auties00.smalt.desktop.imageio;

import com.github.auties00.smalt.image.ImageEncoderOptions;

import javax.imageio.ImageWriteParam;
import java.util.Locale;

/**
 * Carries the compression settings of a {@link SmaltImageWriter}.
 *
 * <p>The compression types are {@value #LOSSY} (the default) and {@value #LOSSLESS}. They map to
 * {@link ImageEncoderOptions} as follows:
 * <ul>
 * <li>{@link #MODE_DEFAULT} and {@link #MODE_COPY_FROM_METADATA}: the encoder's defaults
 * ({@link ImageEncoderOptions#defaults()}), lossy quality 75 for WebP;</li>
 * <li>{@link #MODE_DISABLED}: lossless, since no lossy compression is wanted;</li>
 * <li>{@link #MODE_EXPLICIT} with {@value #LOSSLESS}: lossless;</li>
 * <li>{@link #MODE_EXPLICIT} with {@value #LOSSY}: the compression quality, {@code 0.0} to
 * {@code 1.0}, scaled to Smalt's {@code 1} to {@code 100} ({@code round(quality * 100)}, at least
 * {@code 1}); the quality starts at {@value #DEFAULT_QUALITY}.</li>
 * </ul>
 * Tiling and progressive encoding are not supported.
 *
 * <p>Instances are not thread-safe.
 */
public final class SmaltImageWriteParam extends ImageWriteParam {
    /**
     * The lossy compression type.
     */
    public static final String LOSSY = "Lossy";

    /**
     * The lossless compression type.
     */
    public static final String LOSSLESS = "Lossless";

    /**
     * The compression quality an explicit lossy write starts with.
     */
    public static final float DEFAULT_QUALITY = 0.75f;

    /**
     * Constructs the parameters with lossy compression at {@value #DEFAULT_QUALITY}.
     *
     * @param locale the locale of the compression descriptions, or {@code null}
     */
    public SmaltImageWriteParam(Locale locale) {
        super(locale);
        this.canWriteCompressed = true;
        this.compressionTypes = new String[]{LOSSY, LOSSLESS};
        this.compressionType = LOSSY;
        this.compressionQuality = DEFAULT_QUALITY;
    }

    /**
     * Restores the explicit compression settings to {@value #LOSSY} at {@value #DEFAULT_QUALITY}.
     *
     * <p>{@link #setCompressionMode(int)} calls this when switching to {@link #MODE_EXPLICIT}, so
     * an explicit write starts from a usable type and quality, as the JDK's JPEG parameters do.
     *
     * @throws IllegalStateException if the compression mode is not {@link #MODE_EXPLICIT}
     */
    @Override
    public void unsetCompression() {
        if (getCompressionMode() != MODE_EXPLICIT) {
            throw new IllegalStateException("Compression mode not MODE_EXPLICIT!");
        }
        this.compressionType = LOSSY;
        this.compressionQuality = DEFAULT_QUALITY;
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code true} when the {@value #LOSSLESS} type is selected
     */
    @Override
    public boolean isCompressionLossless() {
        super.isCompressionLossless();
        return LOSSLESS.equals(compressionType);
    }

    /**
     * Returns the encoder options a set of write parameters selects.
     *
     * @param param the parameters, or {@code null} for the defaults; parameters of another class
     * are read through the standard {@link ImageWriteParam} accessors
     * @return the options
     */
    static ImageEncoderOptions toOptions(ImageWriteParam param) {
        if (param == null || !param.canWriteCompressed()) {
            return ImageEncoderOptions.defaults();
        }
        return switch (param.getCompressionMode()) {
            case MODE_DISABLED -> ImageEncoderOptions.lossless();
            case MODE_EXPLICIT -> {
                var type = param.getCompressionType();
                if (LOSSLESS.equals(type)) {
                    yield ImageEncoderOptions.lossless();
                }
                var quality = Math.round(param.getCompressionQuality() * 100);
                yield ImageEncoderOptions.ofQuality(Math.clamp(quality, 1, 100));
            }
            default -> ImageEncoderOptions.defaults();
        };
    }
}
