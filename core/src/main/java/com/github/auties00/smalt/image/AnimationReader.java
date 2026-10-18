package com.github.auties00.smalt.image;

import com.github.auties00.smalt.MediaException;

/**
 * Reads the frames of an animated image one at a time, fully composited.
 *
 * <p>Each {@link #read()} renders the next frame onto a canvas of {@link #width()} by
 * {@link #height()} pixels, applying the format's blending and disposal rules (GIF disposal
 * methods, WebP blend and dispose flags, APNG operations), and returns it with its timing, or
 * returns {@code null} at the end of the animation. The canvas is one buffer reused for every
 * frame, so a caller that keeps a frame copies its picture before calling {@link #read()} again.
 * Reading is streaming: memory use does not grow with the number of frames.
 *
 * <p>Seeking follows the contract of every Smalt reader: {@link #seek(long)} positions the reader
 * so that the next {@link #read()} returns the frame displayed at the requested time. A reader
 * whose {@link #isSeekable()} is {@code false} rejects it.
 *
 * <p>A reader is not thread-safe.
 *
 * @implSpec
 * Implementations must check the canvas size against their limits before allocating it, must
 * count frames against the frame limit, and must raise {@link MediaException.Malformed} for a
 * corrupt frame.
 */
public interface AnimationReader extends AutoCloseable {
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
     * Returns how many times the animation plays in total.
     *
     * <p>Formats that store a repeat count are normalized to plays: a GIF {@code NETSCAPE2.0}
     * value {@code n > 0} is {@code n + 1} plays, as browsers render it. APNG {@code num_plays} and
     * the WebP {@code ANIM} loop count already count plays.
     *
     * @return the number of plays, where {@code 0} means forever
     */
    int loopCount();

    /**
     * Returns the number of frames declared by the headers.
     *
     * @return the frame count, or {@code -1} when it is known only after reading every frame
     */
    int frameCount();

    /**
     * Composites the next frame onto the canvas and returns it.
     *
     * @return the frame, whose picture is the canvas and stays valid until the next call, or
     *         {@code null} at the end of the animation
     * @throws MediaException.Malformed     if the frame is corrupt
     * @throws MediaException.LimitExceeded if the frame count exceeds the limits
     */
    AnimationFrame read();

    /**
     * Returns whether {@link #seek(long)} is supported.
     *
     * @implSpec The default implementation returns {@code false}.
     *
     * @return {@code true} when the reader can seek
     */
    default boolean isSeekable() {
        return false;
    }

    /**
     * Positions the reader so that the next {@link #read()} returns the frame displayed at a time.
     *
     * <p>The frame displayed at {@code micros} is the last frame whose
     * {@link AnimationFrame#timestampMicros()} is at or before it; a time before zero selects the
     * first frame and a time at or past the end makes the next {@link #read()} return
     * {@code null}. Because every frame is composited onto the ones before it, a reader seeks by
     * compositing from the closest earlier frame that does not depend on its predecessors.
     *
     * @implSpec The default implementation throws {@link MediaException.Unsupported}.
     *
     * @param micros the presentation time in microseconds
     * @throws MediaException.Unsupported if the reader cannot seek
     * @throws MediaException.Malformed   if a frame decoded while seeking is corrupt
     */
    default void seek(long micros) {
        throw new MediaException.Unsupported("seek", "this animation reader cannot seek");
    }

    /**
     * Releases the reader's resources; the underlying source is not closed.
     *
     * <p>The default implementation holds none and does nothing.
     */
    @Override
    default void close() {
    }
}
