package com.github.auties00.smalt.image.webp.internal.anim;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.AnimationFrame;
import com.github.auties00.smalt.image.AnimationReader;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.webp.internal.riff.WebpParser;
import com.github.auties00.smalt.internal.io.SourceReader;
import com.github.auties00.smalt.internal.limits.Guards;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.io.MediaSource;

import java.util.Arrays;

/**
 * Plays a WebP animation: decodes each {@code ANMF} frame and composites it onto the canvas.
 *
 * <p>The canvas starts fully transparent. A frame replaces the pixels of its rectangle; when it
 * is blended, its non-opaque pixels are then alpha-blended (straight alpha) over the previous
 * canvas. After display, a frame whose dispose flag is set leaves its rectangle transparent for
 * the next frame. The {@code ANIM} background color is reported by the container but, as in
 * libwebp, not used for disposal. Frames are read from the source one at a time, so memory does
 * not grow with the frame count.
 *
 * @implNote This implementation follows libwebp's {@code src/demux/anim_decode.c} with
 * {@code MODE_RGBA} output: its key-frame detection (a key frame is composited on a cleared canvas
 * instead of the previous one), its integer blending formula
 * ({@code dst_factor_a = dst_a * (256 - src_a) >> 8}, channels scaled by
 * {@code (1 << 24) / blend_a}), and its restriction of blending to the part of a row outside the
 * previous frame's rectangle when that frame was disposed.
 */
public final class WebpAnimationReader implements AnimationReader {
    /**
     * The operation the calls of this reader form, whose computing time decides how long a call keeps
     * its carrier thread.
     */
    private final ParallelBands.Operation operation = new ParallelBands.Operation();

    /**
     * The file.
     */
    private final SourceReader in;

    /**
     * The parsed structure of the file.
     */
    private final WebpParser.Layout layout;

    /**
     * The resource bounds.
     */
    private final MediaLimits limits;

    /**
     * The walk over the frames.
     */
    private final WebpParser.AnimationCursor cursor;

    /**
     * The canvas holding the current frame.
     */
    private final byte[] current;

    /**
     * The previous canvas after disposal.
     */
    private final byte[] previousDisposed;

    /**
     * The picture wrapping {@link #current}.
     */
    private final RgbaPicture picture;

    /**
     * The previous frame, or {@code null} before the second frame.
     */
    private WebpParser.Frame previous;

    /**
     * Whether the previous frame was a key frame.
     */
    private boolean previousWasKeyFrame;

    /**
     * The number of frames rendered so far.
     */
    private int rendered;

    /**
     * The duration of the current frame.
     */
    private int duration;

    /**
     * Whether the reader failed and must not be used any more.
     */
    private boolean failed;

    /**
     * Opens an animation whose layout was parsed.
     *
     * @param source the file
     * @param layout the parsed structure, an animation
     * @param limits the resource bounds
     * @throws MediaException.LimitExceeded if the canvas exceeds {@code limits}
     */
    public WebpAnimationReader(MediaSource source, WebpParser.Layout layout, MediaLimits limits) {
        this.in = new SourceReader(source, 16 * 1024);
        this.layout = layout;
        this.limits = limits;
        this.cursor = new WebpParser.AnimationCursor(layout);
        var width = layout.canvasWidth;
        var height = layout.canvasHeight;
        LimitChecks.checkPicture(limits, width, height);
        LimitChecks.checkAllocation(limits, (long) width * height * 4);
        this.current = new byte[width * height * 4];
        this.previousDisposed = new byte[width * height * 4];
        var hasAlpha = (layout.flags & WebpParser.FLAG_ALPHA) != 0 || !layout.framesCoverCanvas;
        this.picture = RgbaPicture.of(width, height, width * 4, current, hasAlpha);
    }

    /**
     * The sum of the durations of the frames returned so far, in microseconds.
     */
    private long elapsedMicros;

    @Override
    public int width() {
        return layout.canvasWidth;
    }

    @Override
    public int height() {
        return layout.canvasHeight;
    }

    /**
     * Returns the total number of plays stored in the {@code ANIM} chunk, unchanged.
     *
     * <p>WebP's loop count already counts plays, as Chromium's decoder reads it: {@code 1} plays
     * the animation once and {@code 0} plays it forever.
     *
     * @return the number of plays, {@code 0} meaning forever
     */
    @Override
    public int loopCount() {
        return layout.loopCount;
    }

    @Override
    public int frameCount() {
        return layout.frameCount;
    }

    /**
     * Returns the background color of the {@code ANIM} chunk.
     *
     * @return the color as {@code 0xAARRGGBB}
     */
    public int backgroundColor() {
        var bgra = layout.backgroundColor;
        return (bgra & 0xFF00FF00) | ((bgra >>> 16) & 0xFF) | ((bgra & 0xFF) << 16);
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
        try (var _ = ParallelBands.operation(operation)) {
            if (failed) {
                throw new IllegalStateException("the animation reader failed");
            }
            if (rendered >= layout.frameCount) {
                return false;
            }
            try {
                var frame = cursor.next(in);
                if (frame == null) {
                    return false;
                }
                render(frame);
                return true;
            } catch (RuntimeException exception) {
                failed = true;
                throw Guards.translate("WebP animation frame", exception);
            }
        }
    }

    /**
     * Decodes and composites one frame.
     *
     * @param frame the frame header
     */
    private void render(WebpParser.Frame frame) {
        var width = layout.canvasWidth;
        var height = layout.canvasHeight;
        var stride = width * 4;
        LimitChecks.checkFrames(limits, rendered + 1L);
        var keyFrame = isKeyFrame(frame);
        if (keyFrame) {
            Arrays.fill(current, (byte) 0);
        } else {
            System.arraycopy(previousDisposed, 0, current, 0, current.length);
        }
        FrameDecoder.decodeInto(in, frame.image(), frame.lossless(), frame.alpha(), frame.width(), frame.height(),
                limits, current, frame.yOffset() * stride + frame.xOffset() * 4, stride, true);
        if (rendered > 0 && frame.blend() && !keyFrame) {
            if (!previous.disposeBackground()) {
                for (var y = 0; y < frame.height(); y++) {
                    var offset = ((frame.yOffset() + y) * width + frame.xOffset()) * 4;
                    blendRow(current, previousDisposed, offset, frame.width());
                }
            } else {
                for (var y = 0; y < frame.height(); y++) {
                    var canvasY = frame.yOffset() + y;
                    blendOutsidePrevious(frame, canvasY, width);
                }
            }
        }
        previous = frame;
        previousWasKeyFrame = keyFrame;
        System.arraycopy(current, 0, previousDisposed, 0, current.length);
        if (frame.disposeBackground()) {
            for (var y = 0; y < frame.height(); y++) {
                var offset = ((frame.yOffset() + y) * width + frame.xOffset()) * 4;
                Arrays.fill(previousDisposed, offset, offset + frame.width() * 4, (byte) 0);
            }
        }
        duration = frame.duration();
        ++rendered;
    }

    /**
     * Returns whether a frame can be composited on a cleared canvas.
     *
     * @param frame the frame header
     * @return whether it is a key frame
     */
    private boolean isKeyFrame(WebpParser.Frame frame) {
        if (rendered == 0) {
            return true;
        }
        if ((!frame.hasAlpha() || !frame.blend()) && isFullFrame(frame)) {
            return true;
        }
        return previous.disposeBackground() && (isFullFrame(previous) || previousWasKeyFrame);
    }

    /**
     * Returns whether a frame covers the whole canvas.
     *
     * @param frame the frame header
     * @return whether its size equals the canvas size
     */
    private boolean isFullFrame(WebpParser.Frame frame) {
        return frame.width() == layout.canvasWidth && frame.height() == layout.canvasHeight;
    }

    /**
     * Blends the parts of one canvas row of a frame that lie outside the disposed rectangle of
     * the previous frame.
     *
     * @param frame the current frame
     * @param canvasY the canvas row
     * @param width the canvas width
     */
    private void blendOutsidePrevious(WebpParser.Frame frame, int canvasY, int width) {
        var srcMaxX = frame.xOffset() + frame.width();
        var dstMaxX = previous.xOffset() + previous.width();
        var dstMaxY = previous.yOffset() + previous.height();
        if (canvasY < previous.yOffset() || canvasY >= dstMaxY || frame.xOffset() >= dstMaxX || srcMaxX <= previous.xOffset()) {
            blendRow(current, previousDisposed, (canvasY * width + frame.xOffset()) * 4, frame.width());
            return;
        }
        if (frame.xOffset() < previous.xOffset()) {
            blendRow(current, previousDisposed, (canvasY * width + frame.xOffset()) * 4, previous.xOffset() - frame.xOffset());
        }
        if (srcMaxX > dstMaxX) {
            blendRow(current, previousDisposed, (canvasY * width + dstMaxX) * 4, srcMaxX - dstMaxX);
        }
    }

    /**
     * Blends the non-opaque pixels of a run over the corresponding pixels of another canvas.
     *
     * @param src the canvas holding the frame pixels, updated in place
     * @param dst the canvas blended under them
     * @param offset the index of the first byte of the run
     * @param count the number of pixels
     */
    static void blendRow(byte[] src, byte[] dst, int offset, int count) {
        for (var i = 0; i < count; i++) {
            var p = offset + (i << 2);
            var srcA = src[p + 3] & 0xFF;
            if (srcA == 0xFF) {
                continue;
            }
            if (srcA == 0) {
                src[p] = dst[p];
                src[p + 1] = dst[p + 1];
                src[p + 2] = dst[p + 2];
                src[p + 3] = dst[p + 3];
                continue;
            }
            var dstA = dst[p + 3] & 0xFF;
            var dstFactorA = (dstA * (256 - srcA)) >> 8;
            var blendA = srcA + dstFactorA;
            var scale = (1L << 24) / blendA;
            for (var c = 0; c < 3; c++) {
                var unscaled = (src[p + c] & 0xFF) * srcA + (dst[p + c] & 0xFF) * dstFactorA;
                src[p + c] = (byte) ((unscaled * scale) >>> 24);
            }
            src[p + 3] = (byte) blendA;
        }
    }

    /**
     * Returns the canvas holding the frame rendered by the last successful {@link #advance()}.
     *
     * @return the canvas
     * @throws IllegalStateException if no frame was rendered yet
     */
    private RgbaPicture frame() {
        if (rendered == 0) {
            throw new IllegalStateException("no frame was rendered yet");
        }
        return picture;
    }

    /**
     * Returns how long the frame rendered by the last successful {@link #advance()} is displayed.
     *
     * @return the duration in milliseconds, as stored
     * @throws IllegalStateException if no frame was rendered yet
     */
    private int durationMillis() {
        if (rendered == 0) {
            throw new IllegalStateException("no frame was rendered yet");
        }
        return duration;
    }

    /**
     * Leaves the underlying source open and does nothing else: the reader owns no threads, and no
     * work of it runs once a call to {@link #advance()} has returned.
     */
    @Override
    public void close() {
    }
}
