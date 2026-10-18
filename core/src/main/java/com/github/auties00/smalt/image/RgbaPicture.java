package com.github.auties00.smalt.image;

import java.util.Objects;

/**
 * Holds a picture as packed 8-bit R, G, B, A samples.
 *
 * <p>Each pixel occupies four consecutive bytes in the order red, green, blue, alpha. Color is
 * sRGB and alpha is straight (not premultiplied): a fully transparent pixel may carry any color.
 * Pixel {@code (x, y)} starts at index {@code y * stride() + x * 4} of {@link #pixels()}.
 *
 * <p>Instances are not thread-safe; the sample array is shared, not copied, and threads that
 * share a picture while one of them writes its samples must synchronize externally.
 */
public final class RgbaPicture implements Picture {
    /**
     * The width in pixels.
     */
    private final int width;

    /**
     * The height in pixels.
     */
    private final int height;

    /**
     * The distance in bytes between the starts of consecutive rows.
     */
    private final int stride;

    /**
     * The packed samples.
     */
    private final byte[] pixels;

    /**
     * Whether the producer declared per-pixel transparency.
     */
    private final boolean hasAlpha;

    private RgbaPicture(int width, int height, boolean hasAlpha) {
        this(width, height, width * 4, allocatePixels(width, height), hasAlpha);
    }

    private RgbaPicture(int width, int height, int stride, byte[] pixels, boolean hasAlpha) {
        Objects.requireNonNull(pixels, "pixels");
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("invalid picture size " + width + "x" + height);
        }
        if (stride < width * 4L) {
            throw new IllegalArgumentException("stride " + stride + " is smaller than " + width * 4L);
        }
        if (pixels.length < (long) stride * (height - 1) + width * 4L) {
            throw new IllegalArgumentException("pixel array of " + pixels.length + " bytes is too short");
        }
        this.width = width;
        this.height = height;
        this.stride = stride;
        this.pixels = pixels;
        this.hasAlpha = hasAlpha;
    }

    /**
     * Allocates a zero-filled (fully transparent black) picture with a tight stride.
     *
     * @param width the width in pixels; positive
     * @param height the height in pixels; positive
     * @param hasAlpha whether the picture carries meaningful transparency
     * @return the picture, with a stride of {@code width * 4}
     * @throws IllegalArgumentException if a dimension is not positive or the buffer would exceed
     * the largest Java array
     */
    public static RgbaPicture allocate(int width, int height, boolean hasAlpha) {
        return new RgbaPicture(width, height, hasAlpha);
    }

    /**
     * Wraps existing samples without copying them.
     *
     * @param width the width in pixels; positive
     * @param height the height in pixels; positive
     * @param stride the distance in bytes between row starts; at least {@code width * 4}
     * @param pixels the samples, shared with the picture; at least
     * {@code stride * (height - 1) + width * 4} long
     * @param hasAlpha whether the picture carries meaningful transparency
     * @return the picture
     * @throws IllegalArgumentException if a dimension is not positive, the stride is too small,
     * or the array is too short
     */
    public static RgbaPicture of(int width, int height, int stride, byte[] pixels, boolean hasAlpha) {
        return new RgbaPicture(width, height, stride, pixels, hasAlpha);
    }

    /**
     * Allocates the tight sample array of a picture.
     *
     * @param width  the width in pixels
     * @param height the height in pixels
     * @return the zero-filled array
     * @throws IllegalArgumentException if a dimension is not positive or the array would exceed
     *                                  the largest Java array
     */
    private static byte[] allocatePixels(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("invalid picture size " + width + "x" + height);
        }
        var size = (long) width * height * 4;
        if (size > Integer.MAX_VALUE - 16) {
            throw new IllegalArgumentException("picture size " + width + "x" + height + " is too large");
        }
        return new byte[(int) size];
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }

    /**
     * Returns the distance in bytes between the starts of consecutive rows.
     *
     * @return the stride, at least {@code width() * 4}
     */
    public int stride() {
        return stride;
    }

    /**
     * Returns the backing sample array, not a copy.
     *
     * @return the samples
     */
    public byte[] pixels() {
        return pixels;
    }

    @Override
    public boolean hasAlpha() {
        return hasAlpha;
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code 8}
     */
    @Override
    public int bitDepth() {
        return 8;
    }
}
