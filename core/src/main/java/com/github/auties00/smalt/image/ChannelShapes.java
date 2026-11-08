package com.github.auties00.smalt.image;

/**
 * Validates and sizes the sample arrays of {@link ChannelPicture}s.
 */
final class ChannelShapes {
    /**
     * Prevents instantiation.
     */
    private ChannelShapes() {
        throw new AssertionError();
    }

    /**
     * Validates the shape of a channel picture.
     *
     * @param width the width in pixels
     * @param height the height in pixels
     * @param stride the row stride in samples
     * @param length the length of the sample array
     * @param channels the samples per pixel
     * @throws IllegalArgumentException if a dimension is not positive, the stride is too small, or the array
     * is too short
     */
    static void check(int width, int height, int stride, int length, int channels) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("invalid picture size " + width + "x" + height);
        }
        var row = (long) width * channels;
        if (stride < row) {
            throw new IllegalArgumentException("stride " + stride + " is smaller than " + row);
        }
        if (length < (long) stride * (height - 1) + row) {
            throw new IllegalArgumentException("sample array of " + length + " samples is too short");
        }
    }

    /**
     * Returns the length of the tight sample array of a channel picture.
     *
     * @param width the width in pixels
     * @param height the height in pixels
     * @param channels the samples per pixel
     * @return the length
     * @throws IllegalArgumentException if a dimension is not positive or the array would exceed the largest
     * Java array
     */
    static int tightLength(int width, int height, int channels) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("invalid picture size " + width + "x" + height);
        }
        var size = (long) width * height * channels;
        if (size > Integer.MAX_VALUE - 16) {
            throw new IllegalArgumentException("picture size " + width + "x" + height + " with " + channels
                    + " channels is too large");
        }
        return (int) size;
    }
}
