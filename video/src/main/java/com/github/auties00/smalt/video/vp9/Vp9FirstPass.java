package com.github.auties00.smalt.video.vp9;

import com.github.auties00.smalt.MediaRuntime;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.internal.codec.FrameClock;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.vp9.internal.Vp9FirstPassRunner;
import com.github.auties00.smalt.video.vp9.internal.Vp9RtEncoder;

import java.util.Objects;

/**
 * Runs the first pass of a two-pass VP9 encode: measures every picture of a clip and produces the
 * {@link Vp9FirstPassStats} that the second pass, a good-quality {@link Vp9Encoder} configured with
 * {@link Vp9EncoderOptions.Builder#firstPassStats(Vp9FirstPassStats)}, reads to place key frames
 * and spread its bitrate over the whole clip.
 *
 * <p>The first pass converts each picture as the encoder of the same options would and measures
 * how well it predicts from the pictures before it; it produces no stream. Pass the same pictures, in
 * the same order and with the same timestamps, to both passes. The measures do not depend on
 * {@link Vp9EncoderOptions#threads()}. Instances are not thread-safe.
 *
 * @apiNote A two-pass encode spends its bits where the clip needs them, which a one-pass encode
 * can only estimate from its lookahead; it suits files whose pictures can be read twice.
 */
public final class Vp9FirstPass {
    /**
     * The time base of the frame durations the statistics record, 100 nanoseconds.
     */
    private static final Rational TICKS = new Rational(1, Vp9RtEncoder.TICKS_PER_SECOND);

    private final int width;
    private final int height;
    private final Vp9Pictures pictures;
    private final Vp9FirstPassRunner runner;
    private final FrameClock clock;
    private final ParallelBands.Operation operation = new ParallelBands.Operation();

    /**
     * Creates the first pass of a clip.
     *
     * @param width the picture width in pixels, from 1 to 16384
     * @param height the picture height in pixels, from 1 to 16384
     * @param options the options of the second pass; the picture format, frame rate, time base,
     * threads and limits apply
     * @throws IllegalArgumentException if a dimension is outside 1 to 16384
     * @throws com.github.auties00.smalt.MediaException.LimitExceeded if the picture exceeds the
     * options' limits
     */
    public Vp9FirstPass(int width, int height, Vp9EncoderOptions options) {
        Objects.requireNonNull(options, "options");
        if (width < 1 || height < 1 || width > 16_384 || height > 16_384) {
            throw new IllegalArgumentException("invalid picture size " + width + "x" + height);
        }
        LimitChecks.checkPicture(options.limits(), width, height);
        this.width = width;
        this.height = height;
        this.pictures = new Vp9Pictures(options);
        var threads = options.threads() == 0 ? Math.min(64, MediaRuntime.parallelism()) : options.threads();
        var subsampling = options.subsampling();
        this.runner = new Vp9FirstPassRunner(width, height, subsampling.shiftX(), subsampling.shiftY(),
                options.bitDepth(), threads);
        this.clock = new FrameClock(options.frameRate(), options.timeBase());
    }

    /**
     * Measures the next picture of the clip.
     *
     * @param frame the picture and its timestamp; a frame without a timestamp follows the
     * previous one by the nominal frame duration
     * @throws IllegalArgumentException if the picture size differs from the pass's or the
     * timestamp does not follow the previous one
     */
    public void add(VideoFrame frame) {
        Objects.requireNonNull(frame, "frame");
        var picture = frame.picture();
        if (picture.width() != width || picture.height() != height) {
            throw new IllegalArgumentException("picture is " + picture.width() + "x" + picture.height()
                    + ", first pass expects " + width + "x" + height);
        }
        try (var _ = ParallelBands.operation(operation)) {
            var timing = clock.resolve(frame);
            var ticks = clock.endTicks(timing, TICKS) - clock.startTicks(timing, TICKS);
            if (pictures.high() && pictures.toCodedHigh(picture) instanceof Yuv16Picture high) {
                runner.add(high, ticks);
            } else {
                runner.add(pictures.toCoded(picture), ticks);
            }
            clock.commit(timing.pts());
        }
    }

    /**
     * Returns the statistics of the pictures measured so far.
     *
     * @return the statistics
     */
    public Vp9FirstPassStats finish() {
        return new Vp9FirstPassStats(width, height, runner.records());
    }
}
