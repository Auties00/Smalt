package com.github.auties00.smalt.image.ico;

import com.github.auties00.smalt.image.AnimationWriter;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.io.MediaSink;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Writes an animation as an animated cursor, one step per picture.
 *
 * <p>Each picture is encoded to its icon file when written; an icon file equal to an earlier one is
 * stored once. The steps' display times are the differences of their end times rounded to the
 * nearest jiffy, halves up. Nothing reaches the sink before {@link #finish()}. Instances are not
 * thread-safe.
 */
final class AniAnimationWriter implements AnimationWriter {
    /**
     * The canvas width.
     */
    private final int width;

    /**
     * The canvas height.
     */
    private final int height;

    /**
     * The frame options, title and author.
     */
    private final AniEncoderOptions options;

    /**
     * The output.
     */
    private final MediaSink sink;

    /**
     * The distinct icon files, in the order first written.
     */
    private final List<byte[]> icons = new ArrayList<>();

    /**
     * The index of each distinct icon file.
     */
    private final Map<ByteBuffer, Integer> indices = new HashMap<>();

    /**
     * The steps written.
     */
    private final List<AniStep> steps = new ArrayList<>();

    /**
     * The end time of the last step, in microseconds.
     */
    private long elapsedMicros;

    /**
     * The end time of the last step rounded to jiffies.
     */
    private long elapsedJiffies;

    /**
     * Whether {@link #finish()} ran.
     */
    private boolean finished;

    /**
     * Constructs a writer.
     *
     * @param width the canvas width
     * @param height the canvas height
     * @param options the frame options, title and author
     * @param sink the output
     */
    AniAnimationWriter(int width, int height, AniEncoderOptions options, MediaSink sink) {
        this.width = width;
        this.height = height;
        this.options = options;
        this.sink = sink;
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }

    @Override
    public void write(RgbaPicture picture, long durationMicros) {
        Objects.requireNonNull(picture, "picture");
        if (finished) {
            throw new IllegalStateException("the animated cursor is finished");
        }
        if (picture.width() != width || picture.height() != height) {
            throw new IllegalArgumentException("frame of " + picture.width() + "x" + picture.height()
                    + " on a canvas of " + width + "x" + height);
        }
        if (durationMicros < 0) {
            throw new IllegalArgumentException("negative frame duration " + durationMicros);
        }
        long end;
        try {
            end = Math.addExact(elapsedMicros, durationMicros);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("frame duration " + durationMicros + " overflows the animation time");
        }
        var endJiffies = jiffies(end);
        var jiffies = endJiffies - elapsedJiffies;
        if (jiffies > AniStep.MAX_JIFFIES) {
            throw new IllegalArgumentException("frame duration " + durationMicros + " exceeds what ANI stores");
        }
        var icon = IcoEncoder.encode(picture, options.frameOptions());
        var key = ByteBuffer.wrap(icon);
        var index = indices.get(key);
        if (index == null) {
            index = icons.size();
            icons.add(icon);
            indices.put(key, index);
        }
        steps.add(new AniStep(index, jiffies));
        elapsedMicros = end;
        elapsedJiffies = endJiffies;
    }

    /**
     * Rounds a non-negative time to the nearest jiffy, halves up.
     *
     * @param micros the time in microseconds
     * @return the time in sixtieths of a second
     */
    private static long jiffies(long micros) {
        var whole = micros / 1_000_000;
        var rest = micros % 1_000_000;
        return whole * 60 + (rest * 60 + 500_000) / 1_000_000;
    }

    @Override
    public void finish() {
        if (finished) {
            throw new IllegalStateException("the animated cursor is already finished");
        }
        if (steps.isEmpty()) {
            throw new IllegalStateException("an animated cursor needs at least one frame");
        }
        finished = true;
        AniEncoder.write(icons, steps, options, sink);
        close();
    }

    /**
     * Releases the frames written; the sink is not closed, and an unfinished file writes nothing.
     */
    @Override
    public void close() {
        icons.clear();
        indices.clear();
        steps.clear();
    }
}
