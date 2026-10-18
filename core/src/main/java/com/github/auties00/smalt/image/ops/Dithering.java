package com.github.auties00.smalt.image.ops;

/**
 * Enumerates the ways {@link ImageQuantization#quantize} maps pixels to palette entries.
 *
 * <p>Instances are immutable and thread-safe.
 */
public enum Dithering {
    /**
     * Maps each pixel to its nearest entry: flat areas stay flat, gradients band.
     */
    NONE,

    /**
     * Adds an 8x8 Bayer threshold, scaled to the spacing of the palette, before the search: a
     * regular pattern that does not move between frames of an animation.
     */
    ORDERED,

    /**
     * Diffuses each pixel's quantization error to its unvisited neighbors with the
     * Floyd-Steinberg weights (7, 3, 5 and 1 sixteenths), scanning in serpentine order.
     */
    FLOYD_STEINBERG,

    /**
     * Diffuses the error over two rows with Frankie Sierra's weights: smoother than
     * {@link #FLOYD_STEINBERG}, at a little more cost.
     */
    SIERRA
}
