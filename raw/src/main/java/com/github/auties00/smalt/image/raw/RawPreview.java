package com.github.auties00.smalt.image.raw;

/**
 * Describes a JPEG preview a camera raw file embeds next to its sensor data.
 *
 * <p>Instances are immutable and thread-safe.
 *
 * @param width the stored width in pixels, before the file's orientation is applied
 * @param height the stored height in pixels
 * @param length the length of the JPEG stream in bytes
 */
public record RawPreview(int width, int height, long length) {
    /**
     * Validates the description.
     *
     * @throws IllegalArgumentException if a value is not positive
     */
    public RawPreview {
        if (width <= 0 || height <= 0 || length <= 0) {
            throw new IllegalArgumentException("invalid preview " + width + "x" + height + ", " + length + " bytes");
        }
    }
}
