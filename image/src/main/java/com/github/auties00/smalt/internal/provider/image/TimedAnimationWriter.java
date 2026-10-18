package com.github.auties00.smalt.internal.provider.image;

import com.github.auties00.smalt.image.AnimationWriter;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.io.MediaSink;

import java.util.Objects;

/**
 * Presents an animation encoder that stores frame delays in its own unit as an
 * {@link AnimationWriter}: it checks each frame and its duration and keeps the timeline of the
 * frames written, in microseconds, so that a subclass converts each frame's start and end times
 * rather than its duration and the rounding never accumulates.
 *
 * <p>Nothing reaches the sink before {@link #finish()}, which writes the whole file; closing an
 * unfinished writer writes nothing. Instances are not thread-safe.
 */
abstract class TimedAnimationWriter implements AnimationWriter {
    /**
     * The canvas width.
     */
    private final int width;

    /**
     * The canvas height.
     */
    private final int height;

    /**
     * The output, or {@code null} for a writer that only rounds durations for its encoder.
     */
    final MediaSink sink;

    /**
     * The end time of the last written frame, in microseconds.
     */
    private long elapsedMicros;

    /**
     * Constructs the writer.
     *
     * @param width the canvas width of the encoder
     * @param height the canvas height of the encoder
     * @param sink the output, or {@code null} when {@link #finish()} is never called
     */
    TimedAnimationWriter(int width, int height, MediaSink sink) {
        this.width = width;
        this.height = height;
        this.sink = sink;
    }

    @Override
    public final int width() {
        return width;
    }

    @Override
    public final int height() {
        return height;
    }

    @Override
    public final void write(RgbaPicture picture, long durationMicros) {
        Objects.requireNonNull(picture, "picture");
        if (durationMicros < 0) {
            throw new IllegalArgumentException("negative frame duration " + durationMicros);
        }
        long end;
        try {
            end = Math.addExact(elapsedMicros, durationMicros);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("frame duration " + durationMicros + " overflows the animation time");
        }
        writeFrame(picture, durationMicros, elapsedMicros, end);
        elapsedMicros = end;
    }

    /**
     * Encodes one frame shown from {@code startMicros} to {@code endMicros}.
     *
     * <p>Implementations must leave the encoder unchanged when they throw.
     *
     * @param picture the frame, not {@code null}
     * @param durationMicros the frame duration in microseconds, not negative
     * @param startMicros the end time of the frames written before, in microseconds
     * @param endMicros {@code startMicros + durationMicros}
     * @throws IllegalArgumentException if the encoder cannot store the frame's delay
     */
    abstract void writeFrame(RgbaPicture picture, long durationMicros, long startMicros, long endMicros);

    /**
     * Does nothing: the encoder holds no resource beyond memory, and an unfinished animation has
     * written nothing to the sink.
     */
    @Override
    public final void close() {
    }
}
