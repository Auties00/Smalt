package com.github.auties00.smalt.image.ops;

/**
 * Enumerates the ways {@link ImageFilters#gaussianBlur} computes a Gaussian blur.
 *
 * <p>Instances are immutable and thread-safe.
 */
public enum BlurMethod {
    /**
     * Uses {@link #EXACT} for small standard deviations, where it is cheap, and {@link #BOX} for
     * large ones, where its cost grows with the deviation.
     */
    AUTO,

    /**
     * Convolves separably with the sampled Gaussian, truncated at three standard deviations and
     * normalized, in single-precision floating point. Its cost grows linearly with the deviation.
     */
    EXACT,

    /**
     * Approximates the Gaussian with three passes of an extended box filter in each direction
     * (Gwosdek, Grewenig, Bruhn and Weickert, 2011), whose cost does not depend on the deviation,
     * with rounding to the sample depth between passes. On 8-bit pictures this reproduces
     * Pillow's {@code GaussianBlur} exactly.
     */
    BOX
}
