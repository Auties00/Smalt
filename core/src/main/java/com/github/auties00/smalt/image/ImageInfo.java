package com.github.auties00.smalt.image;

import java.util.Objects;

/**
 * Summarizes an image from its headers, without decoding pixels.
 *
 * <p>Instances are immutable and thread-safe.
 *
 * @param format      the recognized format
 * @param width       the stored width in pixels, before {@code orientation} is applied
 * @param height      the stored height in pixels, before {@code orientation} is applied
 * @param hasAlpha    whether the image declares transparency
 * @param animated    whether the image holds more than one frame to animate
 * @param frameCount  the number of frames, or {@code -1} when it cannot be known without
 *                    decoding
 * @param orientation the display orientation from EXIF or the container, {@link Orientation#NORMAL}
 *                    when absent
 */
public record ImageInfo(ImageFormat format, int width, int height, boolean hasAlpha, boolean animated,
                        int frameCount, Orientation orientation) {
    /**
     * Validates the summary.
     *
     * @throws NullPointerException     if {@code format} or {@code orientation} is {@code null}
     * @throws IllegalArgumentException if a dimension is not positive
     */
    public ImageInfo {
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(orientation, "orientation");
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("invalid image size " + width + "x" + height);
        }
    }

    /**
     * Returns the width after applying the orientation.
     *
     * @return the displayed width in pixels
     */
    public int displayWidth() {
        return orientation.swapsDimensions() ? height : width;
    }

    /**
     * Returns the height after applying the orientation.
     *
     * @return the displayed height in pixels
     */
    public int displayHeight() {
        return orientation.swapsDimensions() ? width : height;
    }
}
