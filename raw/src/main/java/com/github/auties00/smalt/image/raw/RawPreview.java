package com.github.auties00.smalt.image.raw;

import com.github.auties00.smalt.image.ImageFormat;

import java.util.Objects;

/**
 * Describes a rendered preview a camera raw file embeds next to its sensor data: a JPEG stream,
 * (DNG 1.7) an image coded with JPEG XL, or (Sigma X3F) 8-bit RGB pixels stored uncompressed or
 * Huffman coded in the raw format's own coding, which only the raw decoder reads.
 *
 * <p>Instances are immutable and thread-safe.
 *
 * @param width the stored width in pixels, before the file's orientation is applied
 * @param height the stored height in pixels
 * @param length the length of the coded preview in bytes, every tile of a tiled preview included
 * @param format the coding: {@link ImageFormat#JPEG}, {@link ImageFormat#JXL}, or
 * {@link ImageFormat#RAW} for pixels in the raw format's own coding
 */
public record RawPreview(int width, int height, long length, ImageFormat format) {
    /**
     * Validates the description.
     *
     * @throws IllegalArgumentException if a value is not positive or the format is not JPEG, JPEG XL
     * or raw
     */
    public RawPreview {
        Objects.requireNonNull(format, "format");
        if (width <= 0 || height <= 0 || length <= 0) {
            throw new IllegalArgumentException("invalid preview " + width + "x" + height + ", " + length + " bytes");
        }
        if (format != ImageFormat.JPEG && format != ImageFormat.JXL && format != ImageFormat.RAW) {
            throw new IllegalArgumentException("a raw preview is a JPEG, JPEG XL or raw-coded image, not " + format);
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
