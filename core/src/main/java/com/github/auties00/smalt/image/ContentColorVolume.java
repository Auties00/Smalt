package com.github.auties00.smalt.image;

import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;

/**
 * Describes the color volume the samples of content are expected to occupy: the chromaticities of the primaries of
 * its gamut and its minimum, maximum and average luminance (Rec. ITU-T H.274 clause 8.14).
 *
 * <p>This is the metadata of the content colour volume SEI message of H.265 and H.266. Unlike a
 * {@link MasteringDisplay}, it describes the content rather than a display, and its luminances are normalized to the
 * reference luminance of the transfer characteristics of the content, not in candelas per square meter. Values the
 * message leaves out are those of the color description of the stream (its primaries) or unknown (the luminances).
 *
 * <p>Instances are immutable and thread-safe.
 *
 * @param primaries the chromaticities of the three primaries of the gamut, suggested to be green, blue then red
 *                  (H.274 stores each coordinate in units of 0.00002, from -100 to 100), or empty when they are those
 *                  of the color primaries of the stream
 * @param minLuminance the normalized minimum luminance (H.274 stores it in units of 0.0000001), or empty when unknown
 * @param maxLuminance the normalized maximum luminance, or empty when unknown
 * @param averageLuminance the normalized average luminance, or empty when unknown
 */
public record ContentColorVolume(List<Coordinate> primaries, OptionalDouble minLuminance,
                                 OptionalDouble maxLuminance, OptionalDouble averageLuminance) {
    /**
     * Validates the description and copies the list of primaries.
     *
     * @throws NullPointerException     if an argument or a primary is {@code null}
     * @throws IllegalArgumentException if there are neither 0 nor 3 primaries, or a luminance is negative or not
     *                                  finite
     */
    public ContentColorVolume {
        primaries = List.copyOf(primaries);
        Objects.requireNonNull(minLuminance, "minLuminance");
        Objects.requireNonNull(maxLuminance, "maxLuminance");
        Objects.requireNonNull(averageLuminance, "averageLuminance");
        if (!primaries.isEmpty() && primaries.size() != 3) {
            throw new IllegalArgumentException("a content color volume has 0 or 3 primaries, not " + primaries.size());
        }
        for (var luminance : List.of(minLuminance, maxLuminance, averageLuminance)) {
            if (luminance.isPresent() && !(luminance.getAsDouble() >= 0 && Double.isFinite(luminance.getAsDouble()))) {
                throw new IllegalArgumentException("invalid content luminance " + luminance.getAsDouble());
            }
        }
    }

    /**
     * Holds a CIE 1931 {@code (x, y)} chromaticity coordinate of a primary, which, unlike a {@link Chromaticity},
     * may lie outside the gamut of real colors.
     *
     * @param x the x coordinate; finite
     * @param y the y coordinate; finite
     */
    public record Coordinate(double x, double y) {
        /**
         * Validates the coordinate.
         *
         * @throws IllegalArgumentException if a coordinate is infinite or NaN
         */
        public Coordinate {
            if (!(Double.isFinite(x) && Double.isFinite(y))) {
                throw new IllegalArgumentException("invalid coordinate (" + x + ", " + y + ")");
            }
        }
    }
}
