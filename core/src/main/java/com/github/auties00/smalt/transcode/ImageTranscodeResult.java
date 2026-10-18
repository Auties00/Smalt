package com.github.auties00.smalt.transcode;

import com.github.auties00.smalt.image.ImageFormat;

import java.util.Objects;

/**
 * Describes what an image transcode wrote.
 *
 * <p>Instances are immutable and thread-safe.
 *
 * @param format      the format written
 * @param width       the width of the output picture, as displayed
 * @param height      the height of the output picture, as displayed
 * @param frames      the number of frames written, {@code 1} for a still image
 * @param passthrough whether the input bytes were copied unchanged
 * @param bytes       the number of bytes written
 */
public record ImageTranscodeResult(ImageFormat format, int width, int height, int frames, boolean passthrough, long bytes) {
    /**
     * Validates the result.
     *
     * @throws NullPointerException if {@code format} is {@code null}
     */
    public ImageTranscodeResult {
        Objects.requireNonNull(format, "format");
    }

    /**
     * Returns whether the output is an animation.
     *
     * @return {@code true} when more than one frame was written
     */
    public boolean animated() {
        return frames > 1;
    }
}
