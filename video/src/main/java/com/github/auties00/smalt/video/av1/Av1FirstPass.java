package com.github.auties00.smalt.video.av1;

import com.github.auties00.smalt.MediaRuntime;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.video.av1.internal.encoder.FirstPassAnalyzer;

import java.util.Arrays;
import java.util.Objects;

/**
 * Measures every picture of a video, the first pass of a two-pass good-quality encode: give it the
 * pictures in display order, then pass its {@link #statistics()} to the {@link Av1Encoder} of the
 * second pass with {@link Av1EncoderOptions.Builder#firstPassStatistics}.
 *
 * <p>The measurements are those the good-quality encoder takes on its lookahead: how well each
 * picture predicts from the two before it and from itself. With them the second pass spends its
 * bitrate over the whole video, where one pass can only spread it over a lookahead, and places key
 * frames with the following pictures known. The pass is much cheaper than coding: it reads only the
 * luma of each picture.
 *
 * <p>Instances are not thread-safe.
 */
public final class Av1FirstPass {
    /**
     * The picture width.
     */
    private final int width;

    /**
     * The picture height.
     */
    private final int height;

    /**
     * The color whose matrix and range the second pass codes, to which pictures are converted.
     */
    private final ColorInfo color;

    /**
     * The number of threads.
     */
    private final int threads;

    /**
     * The analysis.
     */
    private final FirstPassAnalyzer analyzer;

    /**
     * The values of the frames measured so far.
     */
    private double[] values = new double[64 * FirstPassAnalyzer.VALUES];

    /**
     * The number of frames measured.
     */
    private int frames;

    /**
     * Constructs the first pass of an encode of pictures of a size with options.
     *
     * @param width the picture width in pixels, positive
     * @param height the picture height in pixels, positive
     * @param options the options of the second pass; their color and thread count apply
     * @throws IllegalArgumentException if a dimension is not positive
     */
    public Av1FirstPass(int width, int height, Av1EncoderOptions options) {
        Objects.requireNonNull(options, "options");
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException("invalid picture size " + width + "x" + height);
        }
        this.width = width;
        this.height = height;
        this.color = options.color();
        this.threads = options.threads() == 0 ? MediaRuntime.parallelism() : options.threads();
        this.analyzer = new FirstPassAnalyzer(width, height);
    }

    /**
     * Measures the next picture of the video.
     *
     * @param picture the picture, of the pass's size; converted to the options' matrix and range
     * when it is not a Y'CbCr picture with them
     * @throws IllegalArgumentException if the picture size differs from the pass's
     */
    public void analyze(Picture picture) {
        Objects.requireNonNull(picture, "picture");
        if (picture.width() != width || picture.height() != height) {
            throw new IllegalArgumentException("picture is " + picture.width() + "x" + picture.height()
                    + ", the first pass expects " + width + "x" + height);
        }
        Picture yuv;
        if (picture instanceof YuvPicture p && sameSamples(p.color())) {
            yuv = p;
        } else if (picture instanceof Yuv16Picture p && sameSamples(p.color())) {
            yuv = p;
        } else {
            yuv = ImageOps.toYuv(picture, ChromaFormat.YUV420, color);
        }
        if ((frames + 1) * FirstPassAnalyzer.VALUES > values.length) {
            values = Arrays.copyOf(values, values.length * 2);
        }
        analyzer.analyze(yuv, threads, values, frames * FirstPassAnalyzer.VALUES);
        frames++;
    }

    /**
     * Returns whether a color gives the same Y'CbCr samples as the pass's color.
     *
     * @param other the color of a picture
     * @return {@code true} when the matrix and the range match
     */
    private boolean sameSamples(ColorInfo other) {
        return other.matrix() == color.matrix() && other.range() == color.range();
    }

    /**
     * {@return the number of pictures measured}
     */
    public int frameCount() {
        return frames;
    }

    /**
     * {@return the statistics of the pictures measured so far}
     */
    public Av1FirstPassStatistics statistics() {
        return new Av1FirstPassStatistics(width, height, Arrays.copyOf(values, frames * FirstPassAnalyzer.VALUES));
    }
}
