package com.github.auties00.smalt.video.vp8;

import com.github.auties00.smalt.MediaRuntime;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.ops.YuvConverter;
import com.github.auties00.smalt.internal.codec.FrameClock;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.vp8.internal.FirstPassAnalyzer;
import com.github.auties00.smalt.video.vp8.internal.RtEncoder;

import java.util.Arrays;
import java.util.Objects;

/**
 * Runs the first pass of a two-pass VP8 encode: measures every picture of a clip and produces the
 * {@link Vp8FirstPassStats} that the second pass, a good-quality {@link Vp8Encoder} configured with
 * {@link Vp8EncoderOptions.Builder#firstPassStats(Vp8FirstPassStats)}, reads to place key frames
 * and spread its bitrate over the whole clip.
 *
 * <p>The first pass converts each picture as the encoder does, to limited-range BT.601 4:2:0, and
 * measures how well it predicts from the pictures before it; it produces no stream. Pass the same
 * pictures, in the same order and with the same timestamps, to both passes. The measures do not
 * depend on {@link Vp8EncoderOptions#threads()}. Instances are not thread-safe.
 *
 * @apiNote A two-pass encode spends its bits where the clip needs them, which a one-pass encode
 * can only estimate from its lookahead; it suits files whose pictures can be read twice.
 */
public final class Vp8FirstPass {
    /**
     * The time base of the frame durations the statistics record, 100 nanoseconds.
     */
    private static final Rational TICKS = new Rational(1, RtEncoder.TICKS_PER_SECOND);

    /**
     * The largest picture side the VP8 frame header can carry.
     */
    private static final int MAX_DIMENSION = 16_383;

    private final int width;

    private final int height;

    private final int threads;

    private final FirstPassAnalyzer analyzer;

    private final FrameClock clock;

    private final ParallelBands.Operation operation = new ParallelBands.Operation();

    /**
     * The converter of the pictures VP8 does not define, owning their converted copies.
     */
    private final YuvConverter converter = YuvConverter.create(ChromaFormat.YUV420, ColorInfo.BT601_LIMITED);

    /**
     * The records measured so far.
     */
    private double[] records = new double[64 * FirstPassAnalyzer.FIELDS];

    /**
     * The number of records.
     */
    private int count;

    /**
     * Creates the first pass of a clip.
     *
     * @param width the picture width in pixels, from 1 to 16383
     * @param height the picture height in pixels, from 1 to 16383
     * @param options the options of the second pass; the frame rate, time base, threads and
     * limits apply
     * @throws IllegalArgumentException if a dimension is outside 1 to 16383
     * @throws com.github.auties00.smalt.MediaException.LimitExceeded if the picture exceeds the
     * options' limits
     */
    public Vp8FirstPass(int width, int height, Vp8EncoderOptions options) {
        Objects.requireNonNull(options, "options");
        if (width < 1 || height < 1 || width > MAX_DIMENSION || height > MAX_DIMENSION) {
            throw new IllegalArgumentException("invalid picture size " + width + "x" + height);
        }
        LimitChecks.checkPicture(options.limits(), width, height);
        this.width = width;
        this.height = height;
        this.threads = options.threads() == 0 ? Math.min(64, MediaRuntime.parallelism()) : options.threads();
        this.analyzer = new FirstPassAnalyzer(width, height, options.limits());
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
            if ((count + 1) * FirstPassAnalyzer.FIELDS > records.length) {
                records = Arrays.copyOf(records, records.length * 2);
            }
            analyzer.analyze(toYuv420(picture), ticks, records, count * FirstPassAnalyzer.FIELDS, threads);
            count++;
            clock.commit(timing.pts());
        }
    }

    /**
     * Returns the statistics of the pictures measured so far.
     *
     * @return the statistics
     */
    public Vp8FirstPassStats finish() {
        return new Vp8FirstPassStats(width, height, Arrays.copyOf(records, count * FirstPassAnalyzer.FIELDS));
    }

    /**
     * Returns a picture in the color space VP8 defines, limited-range BT.601 4:2:0.
     *
     * @param picture the picture to measure
     * @return {@code picture} itself when it is in that representation already, otherwise a
     * converted copy, valid until the next call
     */
    private YuvPicture toYuv420(Picture picture) {
        if (picture instanceof YuvPicture yuv && yuv.chromaFormat() == ChromaFormat.YUV420
                && yuv.color().range() == ColorRange.LIMITED) {
            var matrix = yuv.color().matrix().effective();
            if (matrix == MatrixCoefficients.BT601 || matrix == MatrixCoefficients.BT470BG) {
                return yuv;
            }
        }
        return converter.convert(picture);
    }
}
