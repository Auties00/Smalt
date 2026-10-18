package com.github.auties00.smalt.image;

import com.github.auties00.smalt.MediaException;

/**
 * Encodes an animation frame by frame into a sink, the writing counterpart of
 * {@link AnimationReader}.
 *
 * <p>Frames are full-canvas pictures of {@link #width()} by {@link #height()} pixels, written in
 * display order with their display durations through {@link #write(RgbaPicture, long)} or
 * {@link #write(AnimationFrame)}; the frames of an {@link AnimationReader} can be written as they
 * are read. {@link #finish()} completes the file and writes whatever the format holds back to the
 * sink; an instance that is closed without finishing leaves the output incomplete, so it is
 * typically used as:
 * {@snippet :
 * try (var writer = Images.openAnimationWriter(ImageFormat.WEBP, width, height, sink)) {
 *     for (var frame = reader.read(); frame != null; frame = reader.read()) {
 *         writer.write(frame);
 *     }
 *     writer.finish();
 * }
 * }
 *
 * <p>An instance is single-use and not thread-safe.
 *
 * @implSpec Implementations must reject a frame of another size with
 * {@link IllegalArgumentException}, a write or a finish after {@link #finish()} with
 * {@link IllegalStateException}, and must translate output failures into
 * {@link MediaException.Io}. A format that stores durations at a coarser precision than
 * microseconds rounds the running end time of each frame, not each duration, so the rounding
 * never accumulates over the animation.
 */
public interface AnimationWriter extends AutoCloseable {
    /**
     * Returns the canvas width.
     *
     * @return the width in pixels
     */
    int width();

    /**
     * Returns the canvas height.
     *
     * @return the height in pixels
     */
    int height();

    /**
     * Adds a frame.
     *
     * <p>The frame's alpha channel is used only when {@link RgbaPicture#hasAlpha()} is set.
     *
     * @param picture        the frame, of the canvas size
     * @param durationMicros the display time in microseconds
     * @throws NullPointerException         if {@code picture} is {@code null}
     * @throws IllegalArgumentException     if the picture size differs from the canvas size, or
     *                                      the duration is negative or exceeds what the format
     *                                      stores
     * @throws IllegalStateException        if the writer is finished
     * @throws MediaException.LimitExceeded if encoding runs out of memory
     */
    void write(RgbaPicture picture, long durationMicros);

    /**
     * Adds a frame, using its picture and duration; its timestamp is ignored, since frames are
     * shown back to back.
     *
     * @implSpec The default implementation calls {@link #write(RgbaPicture, long)} with
     * {@link AnimationFrame#picture()} and {@link AnimationFrame#durationMicros()}.
     *
     * @param frame the frame, of the canvas size
     * @throws NullPointerException         if {@code frame} is {@code null}
     * @throws IllegalArgumentException     if the picture size differs from the canvas size, or
     *                                      the duration exceeds what the format stores
     * @throws IllegalStateException        if the writer is finished
     * @throws MediaException.LimitExceeded if encoding runs out of memory
     */
    default void write(AnimationFrame frame) {
        write(frame.picture(), frame.durationMicros());
    }

    /**
     * Completes the animation and writes the rest of the file to the sink.
     *
     * @throws IllegalStateException if no frame was written or the writer is already finished
     * @throws MediaException.Io     if writing the sink fails
     */
    void finish();

    /**
     * Releases the writer's resources; the sink is not closed, and an unfinished file is left
     * incomplete.
     */
    @Override
    void close();
}
