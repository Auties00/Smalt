package com.github.auties00.smalt.image.gif;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.AnimationWriter;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.gif.internal.GifEncoderCore;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.internal.provider.image.GifAnimationWriter;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.io.MediaSink;

import java.time.Duration;
import java.util.Objects;

/**
 * Encodes an animated GIF frame by frame.
 *
 * <p>Frames are full-canvas pictures added in display order with their delays. Each frame is
 * coded as the smallest rectangle covering what changed since the canvas a decoder shows before
 * it; inside it, unchanged pixels are coded transparent so the decoder keeps them, and the
 * disposal method of each frame ("do not dispose" or "restore to background") is chosen when the
 * next frame shows which one codes it more cheaply or which one it needs (only "restore to
 * background" can turn opaque pixels transparent). A frame identical to the previous one is not
 * coded: its delay is added to the previous frame's while the sum fits.
 *
 * <p>The first frame's palette becomes the global color table; the other frames reuse it or carry
 * a local color table as {@link GifEncoderOptions#paletteMode()} decides, and the palettes are
 * built and applied as for {@link GifEncoder}. The animation loops
 * {@link GifEncoderOptions#loopCount()} times through the {@code NETSCAPE2.0} application
 * extension; an animation that plays once has no such extension. An animation whose frames all
 * collapse into one is written as a still image.
 *
 * <p>An encoder is single-use: add every frame, then call {@link #finish()} once. It holds three
 * canvas-sized buffers and the datastream written so far; nothing reaches a sink before
 * {@link #finish(MediaSink)}. Instances are not thread-safe.
 *
 * @apiNote Browsers display frames whose delay is 0 or 1 hundredth of a second for about 10
 * hundredths; the encoder stores the delays as given.
 */
public final class GifAnimationEncoder {
    /**
     * The operation the calls of this encoder form, whose computing time decides how long a call
     * keeps its carrier thread.
     */
    private final ParallelBands.Operation operation = new ParallelBands.Operation();

    /**
     * The encoding state.
     */
    private final GifEncoderCore core;

    /**
     * The canvas width.
     */
    private final int canvasWidth;

    /**
     * The canvas height.
     */
    private final int canvasHeight;

    /**
     * Whether a frame was added.
     */
    private boolean hasFrames;

    /**
     * Whether {@link #finish()} was called.
     */
    private boolean finished;

    /**
     * The writer that rounds the durations of {@link #addFrame(Picture, Duration)}, created on
     * first use.
     */
    private GifAnimationWriter durationWriter;

    /**
     * Creates an {@link AnimationWriter} that encodes an animation into a sink.
     *
     * <p>Frame durations are rounded as {@link #addFrame(Picture, Duration)} describes. Nothing
     * reaches the sink before {@link AnimationWriter#finish()}, which writes the whole file;
     * closing the writer without finishing writes nothing.
     *
     * @param width the canvas width, from 1 to 65535
     * @param height the canvas height, from 1 to 65535
     * @param options the palette, dithering, transparency, lossless and loop settings
     * @param sink the output; not closed
     * @return the writer
     * @throws IllegalArgumentException if a dimension is not positive
     * @throws MediaException.LimitExceeded if a dimension exceeds 65535, the canvas has more than
     * {@link Integer#MAX_VALUE} pixels, or the canvas buffers do not fit in memory
     */
    public static AnimationWriter openAnimationWriter(int width, int height, GifEncoderOptions options,
                                                      MediaSink sink) {
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(sink, "sink");
        return new GifAnimationWriter(new GifAnimationEncoder(width, height, options), width, height, sink);
    }

    /**
     * Constructs an encoder with the {@link GifEncoderOptions#defaults()}.
     *
     * @param canvasWidth the canvas width, from 1 to 65535
     * @param canvasHeight the canvas height, from 1 to 65535
     * @throws IllegalArgumentException if a dimension is not positive
     * @throws MediaException.LimitExceeded if a dimension exceeds 65535, the canvas has more than
     * {@link Integer#MAX_VALUE} pixels, or the canvas buffers do not fit in memory
     * @see #GifAnimationEncoder(int, int, GifEncoderOptions)
     */
    public GifAnimationEncoder(int canvasWidth, int canvasHeight) {
        this(canvasWidth, canvasHeight, GifEncoderOptions.defaults());
    }

    /**
     * Constructs an encoder.
     *
     * @param canvasWidth the canvas width, from 1 to 65535
     * @param canvasHeight the canvas height, from 1 to 65535
     * @param options the palette, dithering, transparency, lossless and loop settings
     * @throws IllegalArgumentException if a dimension is not positive
     * @throws MediaException.LimitExceeded if a dimension exceeds 65535, the canvas has more than
     * {@link Integer#MAX_VALUE} pixels, or the canvas buffers do not fit in memory
     */
    public GifAnimationEncoder(int canvasWidth, int canvasHeight, GifEncoderOptions options) {
        Objects.requireNonNull(options, "options");
        if (canvasWidth <= 0 || canvasHeight <= 0) {
            throw new IllegalArgumentException("invalid canvas size " + canvasWidth + "x" + canvasHeight);
        }
        GifEncoder.checkDimensions(canvasWidth, canvasHeight);
        this.canvasWidth = canvasWidth;
        this.canvasHeight = canvasHeight;
        try {
            this.core = new GifEncoderCore(canvasWidth, canvasHeight, options);
        } catch (OutOfMemoryError error) {
            throw new MediaException.LimitExceeded("a GIF canvas of " + canvasWidth + "x" + canvasHeight
                    + " does not fit in memory");
        }
    }

    /**
     * Adds a frame.
     *
     * <p>The frame's alpha channel is used only when {@link RgbaPicture#hasAlpha()} is set.
     *
     * @param frame the frame, of the canvas size
     * @param delayCentiseconds the display time in hundredths of a second, from 0 to 65535
     * @throws IllegalArgumentException if the frame size differs from the canvas size or the
     * delay is out of range
     * @throws IllegalStateException if {@link #finish()} was called
     * @throws MediaException.Unsupported if lossless coding is requested and the frame cannot
     * be coded exactly
     * @throws MediaException.LimitExceeded if encoding runs out of memory
     */
    public void addFrame(RgbaPicture frame, int delayCentiseconds) {
        try (var _ = ParallelBands.operation(operation)) {
            Objects.requireNonNull(frame, "frame");
            if (finished) {
                throw new IllegalStateException("the animation is finished");
            }
            if (frame.width() != canvasWidth || frame.height() != canvasHeight) {
                throw new IllegalArgumentException("frame " + frame.width() + "x" + frame.height()
                        + " does not match the canvas " + canvasWidth + "x" + canvasHeight);
            }
            if (delayCentiseconds < 0 || delayCentiseconds > GifEncoderCore.MAX_DELAY) {
                throw new IllegalArgumentException("delay " + delayCentiseconds + " is outside 0..65535");
            }
            try {
                core.add(frame.pixels(), frame.stride(), frame.hasAlpha(), delayCentiseconds);
            } catch (OutOfMemoryError error) {
                throw new MediaException.LimitExceeded("out of memory while encoding a GIF animation");
            }
            hasFrames = true;
        }
    }

    /**
     * Adds a frame displayed for a duration, converting it to packed RGBA when it is not.
     *
     * <p>GIF stores delays in hundredths of a second. The frames added through this method form one
     * timeline, truncated to the microsecond, whose end times are rounded to the nearest hundredth,
     * halves up; each delay is the difference between the rounded end and the rounded start of its
     * frame, so the rounding never accumulates over the animation.
     *
     * @param frame the frame, of the canvas size
     * @param duration the display time, not negative
     * @throws IllegalArgumentException if the frame size differs from the canvas size, the
     * duration is negative, or the delay exceeds 65535 hundredths of a second
     * @throws IllegalStateException if {@link #finish()} was called
     * @throws MediaException.Unsupported if the frame's color matrix cannot be converted to RGB, or
     * lossless coding is requested and the frame cannot be coded exactly
     * @throws MediaException.LimitExceeded if encoding runs out of memory
     */
    public void addFrame(Picture frame, Duration duration) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(duration, "duration");
        if (durationWriter == null) {
            durationWriter = new GifAnimationWriter(this, canvasWidth, canvasHeight, null);
        }
        durationWriter.write(ImageOps.toRgba(frame), toMicros(duration));
    }

    /**
     * Finishes the animation and writes the datastream to a sink, from its position.
     *
     * <p>The bytes written are those {@link #finish()} returns.
     *
     * @param sink the output, written from its position; not closed
     * @throws IllegalStateException if no frame was added or the encoder is already finished
     * @throws MediaException.Unsupported if lossless coding is requested and the last frame
     * cannot be coded exactly
     * @throws MediaException.LimitExceeded if encoding runs out of memory
     * @throws MediaException.Io if writing the sink fails
     */
    public void finish(MediaSink sink) {
        Objects.requireNonNull(sink, "sink");
        sink.write(finish());
    }

    /**
     * Finishes the animation and returns the datastream.
     *
     * @return the GIF datastream
     * @throws IllegalStateException if no frame was added or the encoder is already finished
     * @throws MediaException.Unsupported if lossless coding is requested and the last frame
     * cannot be coded exactly
     * @throws MediaException.LimitExceeded if encoding runs out of memory
     */
    public byte[] finish() {
        try (var _ = ParallelBands.operation(operation)) {
            if (finished) {
                throw new IllegalStateException("the animation is finished");
            }
            if (!hasFrames) {
                throw new IllegalStateException("no frame was added");
            }
            finished = true;
            try {
                return core.finish();
            } catch (OutOfMemoryError error) {
                throw new MediaException.LimitExceeded("out of memory while encoding a GIF animation");
            }
        }
    }

    /**
     * Converts a frame duration to whole microseconds, truncating, saturating at
     * {@link Long#MAX_VALUE} and keeping negative durations negative for the writer to reject.
     *
     * @param duration the frame duration, possibly negative
     * @return the duration in microseconds
     */
    private static long toMicros(Duration duration) {
        if (duration.isNegative()) {
            return -1;
        }
        try {
            return Math.addExact(Math.multiplyExact(duration.getSeconds(), 1_000_000L), duration.getNano() / 1_000);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }
}
