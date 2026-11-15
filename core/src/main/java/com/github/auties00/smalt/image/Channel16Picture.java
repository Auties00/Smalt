package com.github.auties00.smalt.image;

import java.util.Objects;

/**
 * Holds a picture as packed unsigned 16-bit samples of any {@link PixelLayout}.
 *
 * <p>Each sample is read as {@code s & 0xFFFF} and uses the full range, 65535 being full intensity; the
 * layout, the value conventions and the pixel addressing are those of {@link ChannelPicture}.
 *
 * <p>Instances are not thread-safe; the sample array is shared, not copied, and threads that share a picture
 * while one of them writes its samples must synchronize externally.
 */
public final class Channel16Picture implements ChannelPicture {
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
    private final short[] pixels;

    /**
     * The channels of every pixel.
     */
    private final PixelLayout layout;

    private Channel16Picture(int width, int height, int stride, short[] pixels, PixelLayout layout) {
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
    public static Channel16Picture allocate(int width, int height, PixelLayout layout) {
        var length = ChannelShapes.tightLength(width, height, layout.count());
        return new Channel16Picture(width, height, width * layout.count(), new short[length], layout);
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
    public static Channel16Picture of(int width, int height, int stride, short[] pixels, PixelLayout layout) {
        return new Channel16Picture(width, height, stride, pixels, layout);
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
    public short[] pixels() {
        return pixels;
    }

    @Override
    public boolean hasAlpha() {
        return layout.hasAlpha();
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code 16}
     */
    @Override
    public int bitDepth() {
        return 16;
    }
}
