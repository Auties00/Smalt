package com.github.auties00.smalt.image;

import java.util.Objects;

/**
 * Holds a picture as packed 32-bit floating-point samples of any {@link PixelLayout}.
 *
 * <p>Samples are nominal values, 1.0 being full intensity as {@link ChannelPicture} defines it. Values outside
 * 0.0 to 1.0 are legal and kept by every operation that does not convert to an integer picture: brighter than
 * reference white in high-dynamic-range images, out of gamut in wide-gamut ones. Conversions to integer pictures
 * map 0.0 to 1.0 linearly onto the integer range, clamp what lies outside, and map NaN to zero; they never
 * change the transfer function. The layout and the pixel addressing are those of {@link ChannelPicture}.
 *
 * <p>Instances are not thread-safe; the sample array is shared, not copied, and threads that share a picture
 * while one of them writes its samples must synchronize externally.
 */
public final class ChannelFloatPicture implements ChannelPicture {
    /**
     * The width in pixels.
     */
    private final int width;

    /**
     * The height in pixels.
     */
    private final int height;

    /**
     * The distance in samples between the starts of consecutive rows.
     */
    private final int stride;

    /**
     * The packed samples.
     */
    private final float[] pixels;

    /**
     * The channels of every pixel.
     */
    private final PixelLayout layout;

    private ChannelFloatPicture(int width, int height, int stride, float[] pixels, PixelLayout layout) {
        Objects.requireNonNull(pixels, "pixels");
        Objects.requireNonNull(layout, "layout");
        ChannelShapes.check(width, height, stride, pixels.length, layout.count());
        this.width = width;
        this.height = height;
        this.stride = stride;
        this.pixels = pixels;
        this.layout = layout;
    }

    /**
     * Allocates a zero-filled picture with a tight stride.
     *
     * @param width the width in pixels; positive
     * @param height the height in pixels; positive
     * @param layout the channels of every pixel
     * @return the picture, with a stride of {@code width * layout.count()}
     * @throws IllegalArgumentException if a dimension is not positive or the buffer would exceed the largest
     * Java array
     */
    public static ChannelFloatPicture allocate(int width, int height, PixelLayout layout) {
        var length = ChannelShapes.tightLength(width, height, layout.count());
        return new ChannelFloatPicture(width, height, width * layout.count(), new float[length], layout);
    }

    /**
     * Wraps existing samples without copying them.
     *
     * @param width the width in pixels; positive
     * @param height the height in pixels; positive
     * @param stride the distance in samples between row starts; at least {@code width * layout.count()}
     * @param pixels the samples, shared with the picture; at least
     * {@code stride * (height - 1) + width * layout.count()} long
     * @param layout the channels of every pixel
     * @return the picture
     * @throws IllegalArgumentException if a dimension is not positive, the stride is too small, or the array is
     * too short
     */
    public static ChannelFloatPicture of(int width, int height, int stride, float[] pixels, PixelLayout layout) {
        return new ChannelFloatPicture(width, height, stride, pixels, layout);
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }

    @Override
    public PixelLayout layout() {
        return layout;
    }

    @Override
    public int channelCount() {
        return layout.count();
    }

    @Override
    public int stride() {
        return stride;
    }

    /**
     * Returns the backing sample array, not a copy.
     *
     * @return the samples
     */
    public float[] pixels() {
        return pixels;
    }

    @Override
    public boolean hasAlpha() {
        return layout.hasAlpha();
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code 32}
     */
    @Override
    public int bitDepth() {
        return 32;
    }
}
