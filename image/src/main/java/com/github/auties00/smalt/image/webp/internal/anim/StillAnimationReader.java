package com.github.auties00.smalt.image.webp.internal.anim;

import com.github.auties00.smalt.image.AnimationFrame;
import com.github.auties00.smalt.image.AnimationReader;
import com.github.auties00.smalt.image.RgbaPicture;

import java.util.function.Supplier;

/**
 * Presents a still image as a one-frame animation that plays once.
 *
 * <p>The frame is decoded lazily by the first {@link #advance()}, so opening the reader costs only
 * the header parse.
 */
public final class StillAnimationReader implements AnimationReader {
    /**
     * The image width.
     */
    private final int width;

    /**
     * The image height.
     */
    private final int height;

    /**
     * Decodes the image when the frame is first requested.
     */
    private final Supplier<RgbaPicture> decoder;

    /**
     * The decoded frame, or {@code null} before the first {@link #advance()}.
     */
    private RgbaPicture frame;

    /**
     * Whether the single frame has been returned.
     */
    private boolean done;

    /**
     * Constructs a reader.
     *
     * @param width   the image width
     * @param height  the image height
     * @param decoder decodes the image to RGBA
     */
    public StillAnimationReader(int width, int height, Supplier<RgbaPicture> decoder) {
        this.width = width;
        this.height = height;
        this.decoder = decoder;
    }

    /**
     * The sum of the durations of the frames returned so far, in microseconds.
     */
    private long elapsedMicros;

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }

    /**
     * Returns {@code 1}: a still image is shown once.
     *
     * @return the number of plays, always {@code 1}
     */
    @Override
    public int loopCount() {
        return 1;
    }

    @Override
    public int frameCount() {
        return 1;
    }

    @Override
    public AnimationFrame read() {
        if (!advance()) {
            return null;
        }
        var duration = durationMillis() * 1000L;
        var frame = new AnimationFrame(frame(), elapsedMicros, duration);
        elapsedMicros += duration;
        return frame;
    }

    /**
     * Composites the next frame onto the canvas.
     *
     * @return {@code true} when a frame was rendered, {@code false} at the end of the animation
     */
    private boolean advance() {
        if (done) {
            return false;
        }
        frame = decoder.get();
        done = true;
        return true;
    }

    /**
     * Returns the canvas holding the frame rendered by the last successful {@link #advance()}.
     *
     * @return the canvas
     * @throws IllegalStateException if no frame was rendered yet
     */
    private RgbaPicture frame() {
        if (frame == null) {
            throw new IllegalStateException("no frame was rendered yet");
        }
        return frame;
    }

    /**
     * Returns how long the frame rendered by the last successful {@link #advance()} is displayed.
     *
     * @return the duration in milliseconds, as stored
     * @throws IllegalStateException if no frame was rendered yet
     */
    private int durationMillis() {
        if (frame == null) {
            throw new IllegalStateException("no frame was rendered yet");
        }
        return 0;
    }
}
