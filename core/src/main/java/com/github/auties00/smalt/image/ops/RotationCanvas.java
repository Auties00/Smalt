package com.github.auties00.smalt.image.ops;

/**
 * Enumerates the canvases a picture rotated by an arbitrary angle can be placed on.
 *
 * <p>Instances are immutable and thread-safe.
 */
public enum RotationCanvas {
    /**
     * Enlarges the canvas to the bounding box of the rotated picture, so no pixel is lost; the
     * corners the picture does not cover take the background color.
     */
    EXPAND,

    /**
     * Keeps the picture's own size and center; the corners of the rotated picture are cut off and
     * the areas it does not cover take the background color.
     */
    KEEP,

    /**
     * Shrinks the canvas to the largest rectangle with the picture's aspect ratio that the
     * rotated picture covers entirely, centered on it, so no background is visible.
     */
    INSCRIBED
}
