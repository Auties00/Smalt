package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.limits.LimitChecks;

/**
 * Holds the stored sensor samples of a raw image, before any processing: unsigned integers of up
 * to 16 bits in a {@code short[]}, or floating-point values in a {@code float[]}.
 *
 * <p>Samples are interleaved: the sample {@code s} of the pixel at row {@code y} and column
 * {@code x} is at index {@code (y * width + x) * samplesPerPixel + s}.
 */
public final class SensorPlane {
    /**
     * The width in pixels.
     */
    final int width;

    /**
     * The height in pixels.
     */
    final int height;

    /**
     * The number of samples of each pixel.
     */
    final int samplesPerPixel;

    /**
     * The integer samples, or {@code null} for a floating-point plane.
     */
    final short[] integers;

    /**
     * The floating-point samples, or {@code null} for an integer plane.
     */
    final float[] floats;

    /**
     * Allocates a plane after checking it against the limits.
     *
     * @param width the width in pixels
     * @param height the height in pixels
     * @param samplesPerPixel the samples of each pixel
     * @param floating whether samples are floating-point
     * @param limits the resource bounds
     */
    SensorPlane(int width, int height, int samplesPerPixel, boolean floating, MediaLimits limits) {
        LimitChecks.checkPicture(limits, width, height);
        var samples = (long) width * height * samplesPerPixel;
        LimitChecks.checkAllocation(limits, samples * (floating ? 4 : 2));
        this.width = width;
        this.height = height;
        this.samplesPerPixel = samplesPerPixel;
        this.integers = floating ? null : new short[(int) samples];
        this.floats = floating ? new float[(int) samples] : null;
    }

    /**
     * Returns whether the samples are floating-point.
     *
     * @return {@code true} for a floating-point plane
     */
    boolean floating() {
        return floats != null;
    }

    /**
     * Returns one sample as a number.
     *
     * @param index the sample index
     * @return the sample
     */
    float sample(int index) {
        return integers != null ? integers[index] & 0xFFFF : floats[index];
    }
}
