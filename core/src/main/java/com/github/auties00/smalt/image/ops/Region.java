package com.github.auties00.smalt.image.ops;

/**
 * Describes a rectangle of a picture in pixels.
 *
 * <p>Instances are immutable and thread-safe.
 *
 * @param x      the left edge; non-negative
 * @param y      the top edge; non-negative
 * @param width  the width; positive
 * @param height the height; positive
 */
public record Region(int x, int y, int width, int height) {
    /**
     * Validates the rectangle.
     *
     * @throws IllegalArgumentException if an edge is negative or a dimension is not positive
     */
    public Region {
        if (x < 0 || y < 0 || width <= 0 || height <= 0) {
            throw new IllegalArgumentException("invalid region " + width + "x" + height + "+" + x + "+" + y);
        }
    }

    /**
     * Returns whether this rectangle lies inside a picture of a size.
     *
     * @param pictureWidth  the width of the picture in pixels
     * @param pictureHeight the height of the picture in pixels
     * @return {@code true} if {@code x + width <= pictureWidth} and {@code y + height <= pictureHeight}
     */
    public boolean fitsIn(int pictureWidth, int pictureHeight) {
        return (long) x + width <= pictureWidth && (long) y + height <= pictureHeight;
    }
}
