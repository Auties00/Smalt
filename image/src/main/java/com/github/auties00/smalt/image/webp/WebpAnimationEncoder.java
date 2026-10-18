package com.github.auties00.smalt.image.webp;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.AnimationWriter;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.image.webp.internal.anim.AnimationEncoderCore;
import com.github.auties00.smalt.image.webp.internal.anim.FrameEncoder;
import com.github.auties00.smalt.internal.provider.image.WebpAnimationWriter;
import com.github.auties00.smalt.internal.limits.Guards;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.io.MediaSink;

import java.time.Duration;
import java.util.Objects;

/**
 * Encodes an animated WebP file frame by frame.
 *
 * <p>Frames are full canvas images added in display order with their durations; the encoder
 * finds for each one the smallest changed rectangle, the blending and disposal that minimize the
 * file, and inserts key frames as configured. A frame identical to the previous one is not
 * encoded: its duration is added to the previous frame instead. Encoding is streaming: frames are
 * encoded as they are added and only the frames awaiting a key frame decision are kept.
 *
 * <p>An encoder is single-use: add every frame, then call {@link #finish()} once. Frame encodes
 * run in parallel on the shared threads {@link com.github.auties00.smalt.MediaRuntime MediaRuntime}
 * describes while later frames are added, and {@link #finish()} waits for them; the file does not
 * depend on the thread count. Instances are not thread-safe.
 */
public final class WebpAnimationEncoder {
    /**
     * The operation the calls of this encoder form, whose computing time decides how long a call keeps
     * its carrier thread.
     */
    private final ParallelBands.Operation operation = new ParallelBands.Operation();

    /**
     * The encoding state.
     */
    private final AnimationEncoderCore core;

    /**
     * The canvas width.
     */
    private final int canvasWidth;

    /**
     * The canvas height.
     */
    private final int canvasHeight;

    /**
     * The duration of the last added frame.
     */
    private int lastDuration;

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
    private WebpAnimationWriter durationWriter;

    /**
     * Creates an {@link AnimationWriter} that encodes an animation into a sink.
     *
     * <p>Frame durations are rounded as {@link #addFrame(Picture, Duration)} describes. Nothing
     * reaches the sink before {@link AnimationWriter#finish()}, which writes the whole file;
     * closing the writer without finishing writes nothing.
     *
     * @param width the canvas width, from 1 to 16383
     * @param height the canvas height, from 1 to 16383
     * @param options the encoder settings
     * @param sink the output; not closed
     * @return the writer
     * @throws IllegalArgumentException if a dimension is not positive
     * @throws MediaException.LimitExceeded if a dimension exceeds 16383
     */
    public static AnimationWriter openAnimationWriter(int width, int height, WebpAnimationEncoderOptions options,
                                                      MediaSink sink) {
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(sink, "sink");
        return new WebpAnimationWriter(new WebpAnimationEncoder(width, height, options), width, height, sink);
    }

    /**
     * Constructs an encoder with the {@link WebpAnimationEncoderOptions#defaults()}.
     *
     * @param canvasWidth the canvas width, from 1 to 16383
     * @param canvasHeight the canvas height, from 1 to 16383
     * @throws IllegalArgumentException if a dimension is not positive
     * @throws MediaException.LimitExceeded if a dimension exceeds 16383
     */
    public WebpAnimationEncoder(int canvasWidth, int canvasHeight) {
        this(canvasWidth, canvasHeight, WebpAnimationEncoderOptions.defaults());
    }

    /**
     * Constructs an encoder.
     *
     * @param canvasWidth the canvas width, from 1 to 16383
     * @param canvasHeight the canvas height, from 1 to 16383
     * @param options the encoder settings
     * @throws IllegalArgumentException if a dimension is not positive
     * @throws MediaException.LimitExceeded if a dimension exceeds 16383
     */
    public WebpAnimationEncoder(int canvasWidth, int canvasHeight, WebpAnimationEncoderOptions options) {
        Objects.requireNonNull(options, "options");
        if (canvasWidth <= 0 || canvasHeight <= 0) {
            throw new IllegalArgumentException("invalid canvas size " + canvasWidth + "x" + canvasHeight);
        }
        FrameEncoder.checkDimensions(canvasWidth, canvasHeight);
        this.canvasWidth = canvasWidth;
        this.canvasHeight = canvasHeight;
        this.core = new AnimationEncoderCore(canvasWidth, canvasHeight, options.loopCount(), options.isLossless(),
                options.quality(), options.method(), options.minKeyframeDistance(), options.maxKeyframeDistance(),
                options.allowMixed(), options.minimize());
    }

    /**
     * Adds a frame.
     *
     * <p>The frame's alpha channel is used only when {@link RgbaPicture#hasAlpha()} is set.
     *
     * @param frame the frame, of the canvas size
     * @param durationMillis the display time in milliseconds, from 0 to 16777215
     * @throws IllegalArgumentException if the frame size differs from the canvas size or the
     * duration is out of range
     * @throws IllegalStateException if {@link #finish()} was called
     * @throws MediaException.Unsupported if frames are lossy and no VP8 encoder is available
     * @throws MediaException.LimitExceeded if encoding runs out of memory
     */
    public void addFrame(RgbaPicture frame, int durationMillis) {
        try (var _ = ParallelBands.operation(operation)) {
            Objects.requireNonNull(frame, "frame");
            if (finished) {
                throw new IllegalStateException("the animation is finished");
            }
            if (frame.width() != canvasWidth || frame.height() != canvasHeight) {
                throw new IllegalArgumentException("frame " + frame.width() + "x" + frame.height()
                        + " does not match the canvas " + canvasWidth + "x" + canvasHeight);
            }
            if (durationMillis < 0 || durationMillis >= AnimationEncoderCore.MAX_DURATION) {
                throw new IllegalArgumentException("duration " + durationMillis + " is outside 0..16777215");
            }
            Guards.encoding("WebP animation", () -> {
                core.add(frame.pixels(), frame.stride(), frame.hasAlpha(), lastDuration);
                return null;
            });
            lastDuration = durationMillis;
            hasFrames = true;
        }
    }

    /**
     * Adds a frame displayed for a duration, converting it to packed RGBA when it is not.
     *
     * <p>WebP stores durations in milliseconds. The frames added through this method form one
     * timeline, truncated to the microsecond, whose end times are rounded to the nearest
     * millisecond, halves up; each duration is the difference between the rounded end and the
     * rounded start of its frame, so the rounding never accumulates over the animation.
     *
     * @param frame the frame, of the canvas size
     * @param duration the display time, not negative
     * @throws IllegalArgumentException if the frame size differs from the canvas size, the
     * duration is negative, or the delay exceeds 16777215 milliseconds
     * @throws IllegalStateException if {@link #finish()} was called
     * @throws MediaException.Unsupported if the frame's color matrix cannot be converted to RGB,
     * or frames are lossy and no VP8 encoder is available
     * @throws MediaException.LimitExceeded if encoding runs out of memory
     */
    public void addFrame(Picture frame, Duration duration) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(duration, "duration");
        if (durationWriter == null) {
            durationWriter = new WebpAnimationWriter(this, canvasWidth, canvasHeight, null);
        }
        durationWriter.write(ImageOps.toRgba(frame), toMicros(duration));
    }

    /**
     * Finishes the animation and writes the file to a sink.
     *
     * <p>The bytes written are those {@link #finish()} returns.
     *
     * @param sink the output, written from its position and not closed
     * @throws IllegalStateException if no frame was added or the encoder is already finished
     * @throws MediaException.LimitExceeded if encoding runs out of memory or the file would exceed
     * the RIFF size limit
     * @throws MediaException.Io if writing the sink fails
     */
    public void finish(MediaSink sink) {
        Objects.requireNonNull(sink, "sink");
        sink.write(finish());
    }

    /**
     * Finishes the animation and returns the file.
     *
     * <p>An animation whose frames all collapse into one full-canvas frame is written as a still
     * image.
     *
     * @return the WebP file
     * @throws IllegalStateException if no frame was added or the encoder is already finished
     * @throws MediaException.LimitExceeded if encoding runs out of memory or the file would exceed
     * the RIFF size limit
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
            return Guards.encoding("WebP animation", () -> core.finish(lastDuration));
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
