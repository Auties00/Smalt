package com.github.auties00.smalt.image;

import java.util.Objects;

/**
 * Pairs one composited frame of an animated image with its timing.
 *
 * <p>The picture is the reader's canvas, reused for every frame: it is valid only until the next
 * {@link AnimationReader#read()}. It is an {@link RgbaPicture}, except that the AVIF and HEIC readers
 * opened with {@link ImageDecoderOptions#highBitDepth()} return an {@link Rgba16Picture} for a
 * sequence coded with more than 8 bits per sample.
 *
 * @param picture         the composited canvas, an {@link RgbaPicture} or an {@link Rgba16Picture}
 * @param timestampMicros the time the frame is first displayed, in microseconds from the start of
 *                        the animation: the sum of the durations of the frames before it
 * @param durationMicros  how long the frame is displayed, in microseconds, as stored (a stored
 *                        zero stays zero)
 */
public record AnimationFrame(Picture picture, long timestampMicros, long durationMicros) {
    /**
     * Validates the frame.
     *
     * @throws NullPointerException     if {@code picture} is {@code null}
     * @throws IllegalArgumentException if the picture is neither an {@link RgbaPicture} nor an
     *                                  {@link Rgba16Picture}, or a time is negative
     */
    public AnimationFrame {
        Objects.requireNonNull(picture, "picture");
        if (!(picture instanceof RgbaPicture) && !(picture instanceof Rgba16Picture)) {
            throw new IllegalArgumentException("an animation frame is packed RGBA, not " + picture.getClass().getSimpleName());
        }
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
