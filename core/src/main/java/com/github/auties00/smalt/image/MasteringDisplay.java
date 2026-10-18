package com.github.auties00.smalt.image;

import java.util.Objects;

/**
 * Describes the color volume of the display a high-dynamic-range picture was mastered on
 * (SMPTE ST 2086).
 *
 * <p>This is the metadata of the H.264 and H.265 mastering display color volume SEI message, the
 * AV1 {@code METADATA_TYPE_HDR_MDCV} metadata, the MP4 {@code mdcv} box and the Matroska
 * {@code MasteringMetadata} element. A tone mapper uses it to know which colors and which
 * luminance range the content can contain.
 *
 * <p>Instances are immutable and thread-safe.
 *
 * @param red          the chromaticity of the red primary
 * @param green        the chromaticity of the green primary
 * @param blue         the chromaticity of the blue primary
 * @param whitePoint   the chromaticity of the white point
 * @param maxLuminance the maximum luminance of the display in candelas per square meter; finite
 *                     and non-negative
 * @param minLuminance the minimum luminance of the display in candelas per square meter; finite
 *                     and non-negative
 */
public record MasteringDisplay(Chromaticity red, Chromaticity green, Chromaticity blue, Chromaticity whitePoint,
                               double maxLuminance, double minLuminance) {
    /**
     * Validates the description.
     *
     * @throws NullPointerException     if a chromaticity is {@code null}
     * @throws IllegalArgumentException if a luminance is negative, infinite or NaN
     */
    public MasteringDisplay {
        Objects.requireNonNull(red, "red");
        Objects.requireNonNull(green, "green");
        Objects.requireNonNull(blue, "blue");
        Objects.requireNonNull(whitePoint, "whitePoint");
        if (!(maxLuminance >= 0 && minLuminance >= 0 && Double.isFinite(maxLuminance) && Double.isFinite(minLuminance))) {
            throw new IllegalArgumentException("invalid mastering luminance " + minLuminance + ".." + maxLuminance);
        }
    }
}
