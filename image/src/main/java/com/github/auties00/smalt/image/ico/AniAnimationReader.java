package com.github.auties00.smalt.image.ico;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.AnimationFrame;
import com.github.auties00.smalt.image.AnimationReader;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.internal.limits.Guards;
import com.github.auties00.smalt.internal.limits.LimitChecks;

import java.util.Arrays;
import java.util.List;

/**
 * Plays the steps of an animated cursor onto a canvas as large as its largest frame.
 *
 * <p>Each step replaces the whole canvas with its frame, drawn at the top-left corner on a
 * transparent background, so any step can be shown without its predecessors and a seek goes
 * straight to its step. A step showing the frame already on the canvas reuses it. Instances are
 * not thread-safe.
 */
final class AniAnimationReader implements AnimationReader {
    /**
     * The parsed file.
     */
    private final AniFile file;

    /**
     * The steps, in order.
     */
    private final List<AniStep> steps;

    /**
     * The start time of each step, in microseconds, followed by the end of the animation.
     */
    private final long[] starts;

    /**
     * The options each frame is decoded with, without a size hint.
     */
    private final ImageDecoderOptions frameOptions;

    /**
     * The smallest acceptable entry width, or zero.
     */
    private final int minWidth;

    /**
     * The smallest acceptable entry height, or zero.
     */
    private final int minHeight;

    /**
     * The canvas width.
     */
    private final int width;

    /**
     * The canvas height.
     */
    private final int height;

    /**
     * The canvas, packed RGBA.
     */
    private final byte[] canvas;

    /**
     * The index of the next step.
     */
    private int next;

    /**
     * The frame on the canvas, or {@code -1}.
     */
    private int shown = -1;

    /**
     * Whether the canvas has transparent pixels.
     */
    private boolean alpha;

    /**
     * Opens the steps of a parsed file.
     *
     * @param file the parsed file
     * @param options the limits, the size hint choosing each frame's entry, and the bit depth choice
     * @throws MediaException.Malformed if a frame's directory is invalid
     * @throws MediaException.LimitExceeded if the canvas exceeds the limits
     */
    AniAnimationReader(AniFile file, ImageDecoderOptions options) {
        this.file = file;
        this.steps = file.header().steps();
        this.minWidth = options.minWidth();
        this.minHeight = options.minHeight();
        this.frameOptions = options.toBuilder().fullSize().build();
        var size = canvasSize(file, options);
        this.width = size[0];
        this.height = size[1];
        LimitChecks.checkAllocation(file.limits(), (long) width * height * 4);
        this.canvas = new byte[width * height * 4];
        this.starts = new long[steps.size() + 1];
        var jiffies = 0L;
        for (var i = 0; i < steps.size(); i++) {
            starts[i] = micros(jiffies);
            jiffies += steps.get(i).jiffies();
        }
        starts[steps.size()] = micros(jiffies);
    }

    /**
     * Computes the canvas size: the largest width and height of the entries the frames show.
     *
     * @param file the parsed file
     * @param options the size hint choosing each frame's entry
     * @return the width and height
     * @throws MediaException.Malformed if a frame's directory is invalid or no entry has a size
     * @throws MediaException.LimitExceeded if the canvas exceeds the limits
     */
    static int[] canvasSize(AniFile file, ImageDecoderOptions options) {
        var width = 0;
        var height = 0;
        for (var i = 0; i < file.frameCount(); i++) {
            var entries = IcoDecoder.Directory.read(file.frame(i), file.limits()).entries();
            var entry = entries.get(IcoDecoder.choose(entries, options.minWidth(), options.minHeight()));
            width = Math.max(width, entry.width());
            height = Math.max(height, entry.height());
        }
        if (width <= 0 || height <= 0) {
            throw new MediaException.Malformed("the animated cursor frames have no valid size");
        }
        LimitChecks.checkPicture(file.limits(), width, height);
        return new int[]{width, height};
    }

    /**
     * Converts a time in jiffies to microseconds, rounding down and saturating.
     *
     * @param jiffies the time in sixtieths of a second, not negative
     * @return the time in microseconds, at most {@link Long#MAX_VALUE}
     */
    private static long micros(long jiffies) {
        var whole = jiffies / 60;
        if (whole > Long.MAX_VALUE / 1_000_000 - 1) {
            return Long.MAX_VALUE;
        }
        return whole * 1_000_000 + jiffies % 60 * 1_000_000 / 60;
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code 0}: an animated cursor plays forever
     */
    @Override
    public int loopCount() {
        return 0;
    }

    /**
     * {@inheritDoc}
     *
     * @return the number of steps, which a step showing the same frame as another still counts
     */
    @Override
    public int frameCount() {
        return steps.size();
    }

    @Override
    public AnimationFrame read() {
        if (next >= steps.size()) {
            return null;
        }
        var step = next;
        var frame = steps.get(step).frame();
        if (frame != shown) {
            Guards.malformed(AniDecoder.WHAT, () -> draw(frame));
        }
        next++;
        var picture = RgbaPicture.of(width, height, width * 4, canvas, alpha);
        return new AnimationFrame(picture, starts[step], starts[step + 1] - starts[step]);
    }

    /**
     * Decodes a frame onto the canvas.
     *
     * @param frame the frame index
     * @return {@code null}
     */
    private Void draw(int frame) {
        shown = -1;
        var directory = IcoDecoder.Directory.read(file.frame(frame), file.limits());
        var decoded = directory.decode(IcoDecoder.choose(directory.entries(), minWidth, minHeight), frameOptions);
        var picture = ImageOps.toRgba(decoded.picture());
        var w = Math.min(width, picture.width());
        var h = Math.min(height, picture.height());
        if (w < width || h < height) {
            Arrays.fill(canvas, (byte) 0);
        }
        var pixels = picture.pixels();
        for (var y = 0; y < h; y++) {
            System.arraycopy(pixels, y * picture.stride(), canvas, y * width * 4, w * 4);
        }
        alpha = picture.hasAlpha() || w < width || h < height;
        shown = frame;
        return null;
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code true}
     */
    @Override
    public boolean isSeekable() {
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Every step is complete, so the seek decodes no frame; the next {@link #read()} decodes the
     * frame of the step found.
     */
    @Override
    public void seek(long micros) {
        if (micros < 0) {
            next = 0;
            return;
        }
        if (micros >= starts[steps.size()]) {
            next = steps.size();
            return;
        }
        var low = 0;
        var high = steps.size() - 1;
        while (low < high) {
            var middle = (low + high + 1) >>> 1;
            if (starts[middle] <= micros) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        next = low;
    }
}
