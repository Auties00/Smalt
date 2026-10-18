package com.github.auties00.smalt.image.png;

import com.github.auties00.smalt.MediaException;

/**
 * Holds the validated fields of an {@code IHDR} chunk.
 *
 * <p>Only the combinations of color type and bit depth listed in PNG 3rd edition Table 11 are
 * representable; {@link #of(long, long, int, int, int, int, int)} rejects every other header.
 *
 * @param width      the image width in pixels, positive
 * @param height     the image height in pixels, positive
 * @param bitDepth   the bits per sample (or per palette index): 1, 2, 4, 8 or 16
 * @param colorType  the color type: {@link #GRAY}, {@link #RGB}, {@link #PALETTE},
 *                   {@link #GRAY_ALPHA} or {@link #RGBA}
 * @param interlaced whether the image uses Adam7 interlacing
 */
record PngHeader(int width, int height, int bitDepth, int colorType, boolean interlaced) {
    /**
     * The grayscale color type.
     */
    static final int GRAY = 0;

    /**
     * The truecolor color type.
     */
    static final int RGB = 2;

    /**
     * The indexed-color color type.
     */
    static final int PALETTE = 3;

    /**
     * The grayscale with alpha color type.
     */
    static final int GRAY_ALPHA = 4;

    /**
     * The truecolor with alpha color type.
     */
    static final int RGBA = 6;

    /**
     * Validates raw {@code IHDR} fields and builds the header.
     *
     * @param width       the declared width, as an unsigned 32-bit value
     * @param height      the declared height, as an unsigned 32-bit value
     * @param bitDepth    the declared bit depth
     * @param colorType   the declared color type
     * @param compression the declared compression method, which must be 0
     * @param filter      the declared filter method, which must be 0
     * @param interlace   the declared interlace method, 0 or 1
     * @return the header
     * @throws MediaException.Malformed if any field is outside the values PNG 3rd edition 11.2.1
     *                                  allows
     */
    static PngHeader of(long width, long height, int bitDepth, int colorType, int compression, int filter,
                        int interlace) {
        if (width <= 0 || height <= 0 || width > PngChunks.MAX_CHUNK_LENGTH || height > PngChunks.MAX_CHUNK_LENGTH) {
            throw new MediaException.Malformed("invalid PNG size " + width + "x" + height);
        }
        var depthValid = switch (colorType) {
            case GRAY -> bitDepth == 1 || bitDepth == 2 || bitDepth == 4 || bitDepth == 8 || bitDepth == 16;
            case PALETTE -> bitDepth == 1 || bitDepth == 2 || bitDepth == 4 || bitDepth == 8;
            case RGB, GRAY_ALPHA, RGBA -> bitDepth == 8 || bitDepth == 16;
            default -> throw new MediaException.Malformed("invalid PNG color type " + colorType);
        };
        if (!depthValid) {
            throw new MediaException.Malformed("invalid PNG bit depth " + bitDepth + " for color type " + colorType);
        }
        if (compression != 0) {
            throw new MediaException.Malformed("invalid PNG compression method " + compression);
        }
        if (filter != 0) {
            throw new MediaException.Malformed("invalid PNG filter method " + filter);
        }
        if (interlace != 0 && interlace != 1) {
            throw new MediaException.Malformed("invalid PNG interlace method " + interlace);
        }
        return new PngHeader((int) width, (int) height, bitDepth, colorType, interlace == 1);
    }

    /**
     * Returns the number of samples per pixel.
     *
     * @return 1 for grayscale and indexed color, 2 for grayscale with alpha, 3 for truecolor and
     *         4 for truecolor with alpha
     */
    int channels() {
        return switch (colorType) {
            case RGB -> 3;
            case GRAY_ALPHA -> 2;
            case RGBA -> 4;
            default -> 1;
        };
    }

    /**
     * Returns the number of bits one pixel occupies in a scanline.
     *
     * @return the bits per pixel, from 1 to 64
     */
    int bitsPerPixel() {
        return channels() * bitDepth;
    }

    /**
     * Returns the byte distance between corresponding bytes of adjacent pixels that the filters
     * use (the {@code bpp} of PNG 3rd edition 9.2).
     *
     * @return the filter distance, from 1 to 8
     */
    int filterDistance() {
        return Math.max(1, bitsPerPixel() >> 3);
    }

    /**
     * Returns the number of data bytes in one scanline of the given width, excluding the filter
     * type byte.
     *
     * @param pixels the scanline width in pixels
     * @return the byte count
     */
    long rowBytes(int pixels) {
        return ((long) pixels * bitsPerPixel() + 7) >>> 3;
    }

    /**
     * Returns whether the color type carries an alpha channel.
     *
     * @return {@code true} for grayscale with alpha and truecolor with alpha
     */
    boolean hasAlphaChannel() {
        return colorType == GRAY_ALPHA || colorType == RGBA;
    }
}
