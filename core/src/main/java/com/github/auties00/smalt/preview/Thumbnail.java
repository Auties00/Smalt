package com.github.auties00.smalt.preview;

import com.github.auties00.smalt.image.RgbaPicture;

import java.util.Objects;

/**
 * Pairs a thumbnail picture with the time of the frame it shows.
 *
 * <p>The picture is owned by the caller: no reader reuses it.
 *
 * @param picture         the thumbnail, upright and scaled as the options asked
 * @param timestampMicros the presentation time of the frame shown, in microseconds; zero for a
 *                        still image
 */
public record Thumbnail(RgbaPicture picture, long timestampMicros) {
    /**
     * Validates the thumbnail.
     *
     * @throws NullPointerException if {@code picture} is {@code null}
     */
    public Thumbnail {
        Objects.requireNonNull(picture, "picture");
    }
}
