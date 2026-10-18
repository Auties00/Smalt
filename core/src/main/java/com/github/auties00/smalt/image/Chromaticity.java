package com.github.auties00.smalt.image;

/**
 * Holds a CIE 1931 {@code (x, y)} chromaticity coordinate.
 *
 * <p>Containers and bitstreams store chromaticities as fixed-point integers of different scales
 * (units of 0.00002 in H.264, H.265 and the MP4 {@code mdcv} box, 0.16 fixed point in AV1,
 * floating point in Matroska); this record holds the real value, so a writer rounds it to its own
 * scale.
 *
 * <p>Instances are immutable and thread-safe.
 *
 * @param x the x coordinate; finite and non-negative
 * @param y the y coordinate; finite and non-negative
 */
public record Chromaticity(double x, double y) {
    /**
     * Validates the coordinate.
     *
     * @throws IllegalArgumentException if a coordinate is negative, infinite or NaN
     */
    public Chromaticity {
        if (!(x >= 0 && y >= 0 && Double.isFinite(x) && Double.isFinite(y))) {
            throw new IllegalArgumentException("invalid chromaticity (" + x + ", " + y + ")");
        }
    }
}
