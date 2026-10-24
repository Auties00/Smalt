package com.github.auties00.smalt.image.raw;

import com.github.auties00.smalt.image.ImageFormat;

import java.util.Objects;

/**
 * Describes a rendered preview a camera raw file embeds next to its sensor data: a JPEG stream, or
 * (DNG 1.7) an image coded with JPEG XL.
 *
 * <p>Instances are immutable and thread-safe.
 *
 * @param width the stored width in pixels, before the file's orientation is applied
 * @param height the stored height in pixels
 * @param length the length of the coded preview in bytes, every tile of a tiled preview included
 * @param format the coding: {@link ImageFormat#JPEG} or {@link ImageFormat#JXL}
 */
public record RawPreview(int width, int height, long length, ImageFormat format) {
    /**
     * Validates the description.
     *
     * @throws IllegalArgumentException if a value is not positive or the format is neither JPEG nor
     * JPEG XL
     */
    public RawPreview {
        Objects.requireNonNull(format, "format");
        if (width <= 0 || height <= 0 || length <= 0) {
            throw new IllegalArgumentException("invalid preview " + width + "x" + height + ", " + length + " bytes");
        }
        if (format != ImageFormat.JPEG && format != ImageFormat.JXL) {
            throw new IllegalArgumentException("a raw preview is a JPEG or JPEG XL image, not " + format);
        }
    }

    /**
     * Constructs the description of a JPEG preview.
     *
     * @param width the stored width in pixels, before the file's orientation is applied
     * @param height the stored height in pixels
     * @param length the length of the JPEG stream in bytes
     * @throws IllegalArgumentException if a value is not positive
     */
    public RawPreview(int width, int height, long length) {
        this(width, height, length, ImageFormat.JPEG);
    }
}
