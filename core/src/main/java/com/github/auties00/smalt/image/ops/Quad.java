package com.github.auties00.smalt.image.ops;

/**
 * Describes a quadrilateral by its four corners, in the coordinates of {@link AffineTransform}.
 *
 * <p>The corners are named after the corners of the rectangle they correspond to in
 * {@link ImageOps#quad}, {@link ImageOps#mesh} and {@link PerspectiveTransform#mapping}; the
 * quadrilateral itself may have any shape, convex or not, and any orientation.
 *
 * <p>Instances are immutable and thread-safe.
 *
 * @param topLeftX the column of the top-left corner
 * @param topLeftY the row of the top-left corner
 * @param topRightX the column of the top-right corner
 * @param topRightY the row of the top-right corner
 * @param bottomRightX the column of the bottom-right corner
 * @param bottomRightY the row of the bottom-right corner
 * @param bottomLeftX the column of the bottom-left corner
 * @param bottomLeftY the row of the bottom-left corner
 */
public record Quad(double topLeftX, double topLeftY, double topRightX, double topRightY, double bottomRightX,
                   double bottomRightY, double bottomLeftX, double bottomLeftY) {
    /**
     * Validates the corners.
     *
     * @throws IllegalArgumentException if a coordinate is not finite
     */
    public Quad {
        if (!Double.isFinite(topLeftX) || !Double.isFinite(topLeftY) || !Double.isFinite(topRightX)
                || !Double.isFinite(topRightY) || !Double.isFinite(bottomRightX) || !Double.isFinite(bottomRightY)
                || !Double.isFinite(bottomLeftX) || !Double.isFinite(bottomLeftY)) {
            throw new IllegalArgumentException("quadrilateral corners must be finite");
        }
    }

    /**
     * Returns an axis-aligned rectangle.
     *
     * @param x the column of the left edge
     * @param y the row of the top edge
     * @param width the width, in pixels
     * @param height the height, in pixels
     * @return the rectangle from {@code (x, y)} to {@code (x + width, y + height)}
     * @throws IllegalArgumentException if a corner is not finite
     */
    public static Quad of(double x, double y, double width, double height) {
        return new Quad(x, y, x + width, y, x + width, y + height, x, y + height);
    }
}
