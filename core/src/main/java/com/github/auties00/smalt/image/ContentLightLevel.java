package com.github.auties00.smalt.image;

/**
 * Describes the light level of high-dynamic-range content (CTA-861.3 {@code MaxCLL} and
 * {@code MaxFALL}).
 *
 * <p>This is the metadata of the H.264 and H.265 content light level SEI message, the AV1
 * {@code METADATA_TYPE_HDR_CLL} metadata, the MP4 {@code clli} box and the Matroska
 * {@code MaxCLL} and {@code MaxFALL} elements. A value of {@code 0} means unknown.
 *
 * <p>Instances are immutable and thread-safe.
 *
 * @param maxContentLightLevel      the largest light level of any pixel of the content, in
 *                                  candelas per square meter; 0 to 65535
 * @param maxFrameAverageLightLevel the largest average light level of any frame of the content,
 *                                  in candelas per square meter; 0 to 65535
 */
public record ContentLightLevel(int maxContentLightLevel, int maxFrameAverageLightLevel) {
    /**
     * Validates the levels.
     *
     * @throws IllegalArgumentException if a level is negative or above 65535
     */
    public ContentLightLevel {
        if (maxContentLightLevel < 0 || maxContentLightLevel > 0xFFFF
                || maxFrameAverageLightLevel < 0 || maxFrameAverageLightLevel > 0xFFFF) {
            throw new IllegalArgumentException("invalid content light level " + maxContentLightLevel + "/"
                    + maxFrameAverageLightLevel);
        }
    }
}
