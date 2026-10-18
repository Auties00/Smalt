package com.github.auties00.smalt.image;

import java.util.Objects;

/**
 * Pairs one composited frame of an animated image with its timing.
 *
 * <p>The picture is the reader's canvas, reused for every frame: it is valid only until the next
 * {@link AnimationReader#read()}.
 *
 * @param picture         the composited canvas
 * @param timestampMicros the time the frame is first displayed, in microseconds from the start of
 *                        the animation: the sum of the durations of the frames before it
 * @param durationMicros  how long the frame is displayed, in microseconds, as stored (a stored
 *                        zero stays zero)
 */
public record AnimationFrame(RgbaPicture picture, long timestampMicros, long durationMicros) {
    /**
     * Validates the frame.
     *
     * @throws NullPointerException     if {@code picture} is {@code null}
     * @throws IllegalArgumentException if a time is negative
     */
    public AnimationFrame {
        Objects.requireNonNull(picture, "picture");
        if (timestampMicros < 0 || durationMicros < 0) {
            throw new IllegalArgumentException("negative frame time " + timestampMicros + "/" + durationMicros);
        }
    }

    /**
     * Returns how long the frame is displayed, in milliseconds.
     *
     * @return the duration in milliseconds, rounded down
     */
    public int durationMillis() {
        return (int) Math.min(Integer.MAX_VALUE, durationMicros / 1000);
    }
}
