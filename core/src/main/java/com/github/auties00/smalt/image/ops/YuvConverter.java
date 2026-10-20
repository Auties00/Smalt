package com.github.auties00.smalt.image.ops;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.YuvPicture;

import java.util.Objects;

/**
 * A converter of pictures to one planar Y'CbCr layout and color description that writes its
 * results into pictures it owns and reuses.
 *
 * <p>{@link #convert} gives exactly the samples
 * {@link ImageOps#toYuv(Picture, ChromaFormat, ColorInfo, ChromaDownsampling)} gives for the same
 * picture, layout, color description and downsampling. Unlike that method it does not allocate a
 * new picture for every call: the result, and the RGBA picture a conversion through RGBA passes
 * through, are allocated for the first picture of a size and kind and overwritten by the
 * conversions of later pictures of that size and kind, so a stream of 8-bit pictures of one size
 * converts without allocating pictures after the first. A 16-bit picture is converted at its full
 * precision into a new picture, which allocates.
 *
 * <p>A result is either the argument itself, when it is already a {@link YuvPicture} with this
 * converter's layout and color description, or a picture this converter owns. An owned picture is
 * valid until the next call of {@code convert}, which may overwrite it. Callers must not modify
 * an owned picture, and copy a result they keep past that call.
 *
 * <p>Instances are not thread-safe.
 *
 * @apiNote Use one converter per stream of pictures, for example per video encoder or capture
 * session, and {@link ImageOps#toYuv(Picture, ChromaFormat, ColorInfo)} for pictures that must
 * outlive the next conversion.
 */
public final class YuvConverter {
    /**
     * The chroma layout of the results.
     */
    private final ChromaFormat chromaFormat;

    /**
     * The color description of the results.
     */
    private final ColorInfo color;

    /**
     * How subsampled chroma is averaged.
     */
    private final ChromaDownsampling downsampling;

    /**
     * The pictures kept from the previous conversion.
     */
    private final KeptBuffers buffers = new KeptBuffers();

    /**
     * Constructs a converter.
     *
     * @param chromaFormat the chroma layout of the results
     * @param color the color description of the results
     * @param downsampling how subsampled chroma is averaged
     */
    private YuvConverter(ChromaFormat chromaFormat, ColorInfo color, ChromaDownsampling downsampling) {
        this.chromaFormat = chromaFormat;
        this.color = color;
        this.downsampling = downsampling;
    }

    /**
     * Returns a converter to a layout and color description that averages subsampled chroma with
     * equal weights.
     *
     * @param chromaFormat the chroma layout of the results
     * @param color the color description of the results
     * @return the converter, holding no pictures yet
     */
    public static YuvConverter create(ChromaFormat chromaFormat, ColorInfo color) {
        return create(chromaFormat, color, ChromaDownsampling.AVERAGE);
    }

    /**
     * Returns a converter to a layout and color description.
     *
     * @param chromaFormat the chroma layout of the results
     * @param color the color description of the results
     * @param downsampling how to average the pixels a subsampled chroma sample covers
     * @return the converter, holding no pictures yet
     */
    public static YuvConverter create(ChromaFormat chromaFormat, ColorInfo color, ChromaDownsampling downsampling) {
        Objects.requireNonNull(chromaFormat, "chromaFormat");
        Objects.requireNonNull(color, "color");
        Objects.requireNonNull(downsampling, "downsampling");
        return new YuvConverter(chromaFormat, color, downsampling);
    }

    /**
     * {@return the chroma layout of the results}
     */
    public ChromaFormat chromaFormat() {
        return chromaFormat;
    }

    /**
     * {@return the color description of the results}
     */
    public ColorInfo color() {
        return color;
    }

    /**
     * {@return how subsampled chroma is averaged}
     */
    public ChromaDownsampling downsampling() {
        return downsampling;
    }

    /**
     * Converts a picture to this converter's layout and color description.
     *
     * @param picture the picture to convert
     * @return {@code picture} itself if it already has the layout and color description, otherwise
     * a picture this converter owns, valid until the next call
     * @throws MediaException.LimitExceeded if the RGBA form of the picture does not fit one Java array
     */
    public YuvPicture convert(Picture picture) {
        return ImageOps.toYuv(picture, chromaFormat, color, downsampling, buffers);
    }

    /**
     * Supplies the kept pictures while their size and kind still fit, and new ones otherwise.
     */
    private static final class KeptBuffers implements PictureBuffers {
        /**
         * The kept RGBA picture, or {@code null}.
         */
        private RgbaPicture rgba;

        /**
         * The kept planar picture, or {@code null}.
         */
        private YuvPicture yuv;

        @Override
        public RgbaPicture rgba(int width, int height, boolean alpha) {
            var kept = rgba;
            if (kept == null || kept.width() != width || kept.height() != height || kept.hasAlpha() != alpha) {
                kept = RgbaPicture.allocate(width, height, alpha);
                rgba = kept;
            }
            return kept;
        }

        @Override
        public YuvPicture yuv(int width, int height, ChromaFormat format, boolean alpha, ColorInfo color) {
            var kept = yuv;
            if (kept == null || kept.width() != width || kept.height() != height || kept.chromaFormat() != format
                    || kept.hasAlpha() != alpha || !kept.color().equals(color)) {
                kept = YuvPicture.allocate(width, height, format, alpha, color);
                yuv = kept;
            }
            return kept;
        }
    }
}
