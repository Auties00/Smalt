/* Ports libwebp 1.6.0 (src/mux/anim_encode.c). Copyright 2010 Google Inc.; BSD 3-Clause License.
   Modified for Smalt. */
package com.github.auties00.smalt.image.webp.internal.anim;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.webp.internal.alpha.AlphaEncoder;
import com.github.auties00.smalt.image.webp.internal.color.YuvConverter;
import com.github.auties00.smalt.image.webp.internal.lossless.Vp8lDecoder;
import com.github.auties00.smalt.image.webp.internal.riff.FourCc;
import com.github.auties00.smalt.image.webp.internal.riff.WebpParser;
import com.github.auties00.smalt.image.webp.internal.riff.WebpWriter;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.task.ParallelBands;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Encodes an animation frame by frame, choosing for each frame the smallest sub-rectangle,
 * blending mode, previous-frame disposal and (optionally) coding, and inserting key frames.
 *
 * <p>Frames are compared with the previous canvas to find the rectangle that changed; with
 * blending, unchanged pixels inside it are made transparent (lossless) or flattened (lossy) so
 * they compress well. When the previous frame could be disposed to transparent and that makes the
 * rectangle smaller, disposal is used. A frame identical to the previous canvas is not encoded;
 * its duration extends the previous frame. Between {@code kmin} and {@code kmax} frames after a
 * key frame, each frame is encoded both as a sub-frame and as a key frame, and the candidate
 * whose key-frame version costs least becomes the key frame.
 *
 * @implNote This implementation follows libwebp's {@code src/mux/anim_encode.c}, with
 * frame durations given up front instead of timestamps, and assembles the file as
 * {@code WebPMuxAssemble} does: an animation of a single frame covering the canvas is written as
 * a still image, and a single partial frame is also tried re-encoded on the full canvas.
 */
public final class AnimationEncoderCore {
    /**
     * Reads and writes the four bytes of an RGBA pixel as one little-endian {@code int}
     * ({@code 0xAABBGGRR}).
     */
    private static final VarHandle RGBA = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);

    /**
     * The exclusive upper bound of a frame duration, the 24-bit field.
     */
    public static final int MAX_DURATION = 1 << 24;

    /**
     * The largest number of frames kept between key frame decisions.
     */
    private static final int MAX_CACHED_FRAMES = 30;

    /**
     * The color of cleared pixels.
     */
    private static final int TRANSPARENT = 0;

    /**
     * The value of {@link #bestDelta} when no key frame candidate was seen.
     */
    private static final long DELTA_INFINITY = 1L << 32;

    /**
     * The value of {@link #keyframe} when no key frame is selected.
     */
    private static final int KEYFRAME_NONE = -1;

    /**
     * The fewest colors for which a lossy candidate is tried in mixed mode.
     */
    private static final int MIN_COLORS_LOSSY = 31;

    /**
     * The most colors for which a lossless candidate is tried in mixed mode.
     */
    private static final int MAX_COLORS_LOSSLESS = 194;

    /**
     * The number of alpha plane codings remembered.
     */
    private static final int ALPHA_MEMO_SIZE = 4;

    /**
     * The {@code VP8L} payload of a transparent 1x1 image, from libwebp.
     */
    private static final byte[] LOSSLESS_1X1 = {0x2f, 0x00, 0x00, 0x00, 0x10, (byte) 0x88, (byte) 0x88, 0x08};

    /**
     * The {@code VP8 } payload of the 1x1 lossy image libwebp uses with a transparent
     * {@code ALPH}.
     */
    private static final byte[] LOSSY_1X1_VP8 = {0x30, 0x01, 0x00, (byte) 0x9d, 0x01, 0x2a, 0x01, 0x00, 0x01, 0x00, 0x02, 0x00,
            0x34, 0x25, (byte) 0xa4, 0x00, 0x03, 0x70, 0x00, (byte) 0xfe, (byte) 0xfb, (byte) 0xfd, 0x50, 0x00};

    /**
     * The {@code ALPH} payload of the 1x1 lossy image: raw, one transparent sample.
     */
    private static final byte[] LOSSY_1X1_ALPH = {0x00, 0x00};

    /**
     * The canvas width.
     */
    private final int canvasWidth;

    /**
     * The canvas height.
     */
    private final int canvasHeight;

    /**
     * The loop count written to {@code ANIM} unchanged: the total number of plays, {@code 0}
     * meaning forever.
     */
    private final int loopCount;

    /**
     * Whether frames are encoded losslessly by default.
     */
    private final boolean lossless;

    /**
     * The quality or effort.
     */
    private final float quality;

    /**
     * The method.
     */
    private final int method;

    /**
     * The smallest key frame distance, after sanitizing.
     */
    private final int kmin;

    /**
     * The largest key frame distance, after sanitizing.
     */
    private final int kmax;

    /**
     * Whether frames may mix codings.
     */
    private final boolean allowMixed;

    /**
     * Whether every combination is tried.
     */
    private final boolean minimize;

    /**
     * The frame being added.
     */
    private final int[] currCanvas;

    /**
     * The frame being added, possibly modified to compress better.
     */
    private final int[] currCanvasCopy;

    /**
     * Whether {@link #currCanvasCopy} differs from {@link #currCanvas}.
     */
    private boolean currCanvasCopyModified = true;

    /**
     * The previous canvas.
     */
    private final int[] prevCanvas;

    /**
     * The previous canvas with the previous rectangle disposed.
     */
    private final int[] prevCanvasDisposed;

    /**
     * The rectangle of the previous frame.
     */
    private Rect prevRect = new Rect(0, 0, 0, 0);

    /**
     * The frames waiting for a key frame decision, with one spare slot for the frame inserted
     * when a duration overflows.
     */
    private final EncodedFrame[] encodedFrames;

    /**
     * The number of frames that can wait for a key frame decision.
     */
    private final int frameCapacity;

    /**
     * The index of the first waiting frame.
     */
    private int start;

    /**
     * The number of waiting frames.
     */
    private int count;

    /**
     * The number of waiting frames that can be emitted.
     */
    private int flushCount;

    /**
     * The smallest key frame penalty seen since the last key frame.
     */
    private long bestDelta = DELTA_INFINITY;

    /**
     * The index of the chosen key frame relative to {@link #start}.
     */
    private int keyframe = KEYFRAME_NONE;

    /**
     * The number of frames since the last key frame.
     */
    private int countSinceKeyFrame;

    /**
     * Whether the previous frame may still become a key frame.
     */
    private boolean prevCandidateUndecided;

    /**
     * Whether no frame was added yet.
     */
    private boolean isFirstFrame = true;

    /**
     * Whether the last frame configuration was lossless.
     */
    private boolean lastConfigLossless;

    /**
     * The emitted frames.
     */
    private final List<FrameInfo> output = new ArrayList<>();

    /**
     * The resource bounds of the internal color conversions.
     */
    private final MediaLimits limits = MediaLimits.DEFAULT;

    /**
     * The alpha planes coded recently and their payloads, oldest first; an animation whose
     * shape does not change reuses its alpha coding from frame to frame.
     */
    private final ArrayDeque<AlphaEntry> alphaMemo = new ArrayDeque<>(ALPHA_MEMO_SIZE);

    /**
     * Constructs an encoder.
     *
     * @param canvasWidth the canvas width
     * @param canvasHeight the canvas height
     * @param loopCount the total number of plays, {@code 0} for forever
     * @param lossless whether frames are lossless by default
     * @param quality the quality or effort
     * @param method the encoder effort, from 0 to 6
     * @param kmin the requested smallest key frame distance
     * @param kmax the requested largest key frame distance
     * @param allowMixed whether frames may mix codings
     * @param minimize whether every combination is tried
     */
    public AnimationEncoderCore(int canvasWidth, int canvasHeight, int loopCount, boolean lossless, float quality,
                                int method, int kmin, int kmax, boolean allowMixed, boolean minimize) {
        this.canvasWidth = canvasWidth;
        this.canvasHeight = canvasHeight;
        this.loopCount = loopCount;
        this.lossless = lossless;
        this.quality = quality;
        this.method = method;
        this.allowMixed = allowMixed;
        this.minimize = minimize;
        var sanitized = sanitizeKeyFrames(kmin, kmax, minimize);
        this.kmin = sanitized[0];
        this.kmax = sanitized[1];
        this.frameCapacity = (int) Math.max((long) this.kmax - this.kmin + 1, 2);
        this.encodedFrames = new EncodedFrame[frameCapacity + 1];
        for (var i = 0; i < encodedFrames.length; i++) {
            encodedFrames[i] = new EncodedFrame();
        }
        var pixels = canvasWidth * canvasHeight;
        this.currCanvas = new int[pixels];
        this.currCanvasCopy = new int[pixels];
        this.prevCanvas = new int[pixels];
        this.prevCanvasDisposed = new int[pixels];
    }

    /**
     * Applies libwebp's rules to the key frame distances.
     *
     * @param kmin the requested smallest distance
     * @param kmax the requested largest distance
     * @param minimize whether key frames are disabled for size minimization
     * @return {@code {kmin, kmax}}
     */
    static int[] sanitizeKeyFrames(int kmin, int kmax, boolean minimize) {
        if (minimize) {
            kmax = Integer.MAX_VALUE;
            kmin = kmax - 1;
        }
        if (kmax == 1) {
            return new int[]{0, 0};
        } else if (kmax <= 0) {
            kmax = Integer.MAX_VALUE;
            kmin = kmax - 1;
        }
        if (kmin >= kmax) {
            kmin = kmax - 1;
        } else {
            var kminLimit = kmax / 2 + 1;
            if (kmin < kminLimit && kminLimit < kmax) {
                kmin = kminLimit;
            }
        }
        if ((long) kmax - kmin > MAX_CACHED_FRAMES) {
            kmin = kmax - MAX_CACHED_FRAMES;
        }
        return new int[]{kmin, kmax};
    }

    /**
     * Holds a frame rectangle.
     *
     * @param x the left edge
     * @param y the top edge
     * @param width the width in pixels
     * @param height the height in pixels
     */
    private record Rect(int x, int y, int width, int height) {
        /**
         * Returns whether the rectangle is empty.
         *
         * @return whether a side is zero
         */
        boolean empty() {
            return width == 0 || height == 0;
        }

        /**
         * Returns the area.
         *
         * @return {@code width * height}
         */
        long area() {
            return (long) width * height;
        }

        /**
         * Grows the rectangle to even offsets.
         *
         * @return the snapped rectangle
         */
        Rect snapToEven() {
            return new Rect(x & ~1, y & ~1, width + (x & 1), height + (y & 1));
        }
    }

    /**
     * Holds one encoded frame and its placement.
     */
    private static final class FrameInfo {
        /**
         * The left edge.
         */
        int x;

        /**
         * The top edge.
         */
        int y;

        /**
         * The width.
         */
        int width;

        /**
         * The height.
         */
        int height;

        /**
         * The duration in milliseconds.
         */
        int duration;

        /**
         * Whether the frame is disposed to transparent.
         */
        boolean disposeBackground;

        /**
         * Whether the frame is blended.
         */
        boolean blend;

        /**
         * The encoded image.
         */
        FrameEncoder.EncodedImage image;

        /**
         * The size of the image as a standalone WebP file, the size libwebp compares.
         */
        long fileSize;

        /**
         * The candidate whose encode fills {@link #image} and {@link #fileSize}, or
         * {@code null} when they are set directly.
         */
        Candidate source;

        /**
         * Copies the frame, first waiting for its encode.
         *
         * @return the copy
         */
        FrameInfo copy() {
            var copy = new FrameInfo();
            copy.x = x;
            copy.y = y;
            copy.width = width;
            copy.height = height;
            copy.duration = duration;
            copy.disposeBackground = disposeBackground;
            copy.blend = blend;
            resolve(this);
            copy.image = image;
            copy.fileSize = fileSize;
            return copy;
        }
    }

    /**
     * Holds the sub-frame and key frame encodings of one input frame.
     */
    private static final class EncodedFrame {
        /**
         * The sub-frame encoding.
         */
        FrameInfo subFrame;

        /**
         * The key frame encoding.
         */
        FrameInfo keyFrame;

        /**
         * Whether the key frame encoding is chosen.
         */
        boolean isKeyFrame;

        /**
         * Forgets both encodings.
         */
        void release() {
            subFrame = null;
            keyFrame = null;
            isKeyFrame = false;
        }
    }

    /**
     * Returns a waiting frame.
     *
     * @param position the position relative to {@link #start}
     * @return the frame
     */
    private EncodedFrame frameAt(int position) {
        return encodedFrames[start + position];
    }

    /**
     * Adds a frame.
     *
     * @param rgba the frame's RGBA samples
     * @param stride the distance in bytes between rows
     * @param useAlpha whether the alpha channel is meaningful
     * @param previousDuration the duration of the previous frame, ignored for the first frame
     */
    public void add(byte[] rgba, int stride, boolean useAlpha, int previousDuration) {
        if (!isFirstFrame) {
            increasePreviousDuration(previousDuration);
            if (count == frameCapacity) {
                flushFrames();
            }
        }
        var opaque = useAlpha ? 0 : 0xFF000000;
        for (var y = 0; y < canvasHeight; y++) {
            var p = y * stride;
            var d = y * canvasWidth;
            for (var x = 0; x < canvasWidth; x++, p += 4) {
                var abgr = (int) RGBA.get(rgba, p);
                currCanvas[d + x] = (abgr & 0xFF00FF00) | ((abgr >>> 16) & 0xFF) | ((abgr & 0xFF) << 16) | opaque;
            }
        }
        currCanvasCopyModified = true;
        copyCurrentCanvas();
        cacheFrame();
        flushFrames();
        currCanvasCopyModified = true;
    }

    /**
     * Finishes the animation and assembles the file.
     *
     * @param lastDuration the duration of the last frame
     * @return the WebP file
     */
    public byte[] finish(int lastDuration) {
        if (!isFirstFrame) {
            increasePreviousDuration(lastDuration);
        }
        flushCount = count;
        flushFrames();
        var file = assemble(output);
        if (output.size() == 1 && !covers(output.getFirst())) {
            var single = optimizeSingleFrame(output.getFirst());
            if (single != null && single.length < file.length) {
                return single;
            }
        }
        return file;
    }

    /**
     * Returns whether a frame covers the whole canvas.
     *
     * @param frame the emitted frame
     * @return whether its rectangle is the canvas
     */
    private boolean covers(FrameInfo frame) {
        return frame.width == canvasWidth && frame.height == canvasHeight;
    }

    /**
     * Copies the added frame into the working copy when the copy was modified.
     */
    private void copyCurrentCanvas() {
        if (currCanvasCopyModified) {
            System.arraycopy(currCanvas, 0, currCanvasCopy, 0, currCanvas.length);
            currCanvasCopyModified = false;
        }
    }

    /**
     * Returns the largest per-channel difference tolerated as "unchanged" at a lossy quality.
     *
     * @param quality the lossy quality, from 0 to 100
     * @return the difference
     */
    private static int qualityToMaxDiff(float quality) {
        var val = Math.pow(quality / 100., 0.5);
        var maxDiff = 31 * (1 - val) + val;
        return (int) (maxDiff + 0.5);
    }

    /**
     * Returns whether two pixels are close enough to be treated as equal in lossy coding.
     *
     * @param src the first pixel
     * @param dst the second pixel
     * @param maxAllowed the tolerated difference
     * @return whether they are similar
     */
    private static boolean similar(int src, int dst, int maxAllowed) {
        var srcA = src >>> 24;
        var dstA = dst >>> 24;
        if (srcA != dstA) {
            return false;
        }
        var limit = maxAllowed * 255;
        return Math.abs(((src >>> 16) & 0xFF) - ((dst >>> 16) & 0xFF)) * dstA <= limit
                && Math.abs(((src >>> 8) & 0xFF) - ((dst >>> 8) & 0xFF)) * dstA <= limit
                && Math.abs((src & 0xFF) - (dst & 0xFF)) * dstA <= limit;
    }

    /**
     * Shrinks a rectangle to the bounding box of the pixels where two canvases differ.
     *
     * @implNote This implementation scans row by row, where libwebp's
     * {@code MinimizeChangeRectangle} shrinks the left and right columns first and then the top
     * and bottom rows: whether a pixel differs does not depend on the other pixels, so both
     * give the bounding box of the differing pixels, and scanning rows reads memory in order
     * instead of striding down columns.
     *
     * @param src the previous canvas
     * @param dst the current canvas
     * @param rect the starting rectangle
     * @param lossless whether an exact match is required
     * @param quality the lossy quality
     * @return the shrunk rectangle, empty at the origin when nothing changed
     */
    private Rect minimizeChangeRectangle(int[] src, int[] dst, Rect rect, boolean lossless, float quality) {
        var maxAllowed = lossless ? 0 : qualityToMaxDiff(quality);
        var x0 = rect.x();
        var x1 = rect.x() + rect.width();
        var minX = x1;
        var maxX = x0 - 1;
        var minY = -1;
        var maxY = -1;
        for (var y = rect.y(); y < rect.y() + rect.height(); y++) {
            var row = y * canvasWidth;
            int first;
            int last;
            if (lossless) {
                var mismatch = Arrays.mismatch(src, row + x0, row + x1, dst, row + x0, row + x1);
                if (mismatch < 0) {
                    continue;
                }
                first = x0 + mismatch;
                last = x1 - 1;
                while (src[row + last] == dst[row + last]) {
                    --last;
                }
            } else {
                first = x0;
                while (first < x1 && similar(src[row + first], dst[row + first], maxAllowed)) {
                    ++first;
                }
                if (first == x1) {
                    continue;
                }
                last = x1 - 1;
                while (similar(src[row + last], dst[row + last], maxAllowed)) {
                    --last;
                }
            }
            minX = Math.min(minX, first);
            maxX = Math.max(maxX, last);
            if (minY < 0) {
                minY = y;
            }
            maxY = y;
        }
        if (minY < 0) {
            return new Rect(0, 0, 0, 0);
        }
        return new Rect(minX, minY, maxX - minX + 1, maxY - minY + 1);
    }

    /**
     * Computes the rectangle of a frame for one coding.
     *
     * @param prev the previous canvas
     * @param isKeyFrame whether the frame is a key frame
     * @param emptyRectAllowed whether an unchanged frame may be skipped
     * @param lossless whether an exact match is required
     * @return the rectangle; empty only when allowed
     */
    private Rect subRect(int[] prev, boolean isKeyFrame, boolean emptyRectAllowed, boolean lossless, Rect seed) {
        var rect = seed;
        if (!isKeyFrame || isFirstFrame) {
            rect = minimizeChangeRectangle(prev, currCanvasCopy, rect, lossless, quality);
        }
        if (rect.empty()) {
            if (emptyRectAllowed) {
                return rect;
            }
            rect = new Rect(0, 0, 1, 1);
        }
        return rect.snapToEven();
    }

    /**
     * Holds the rectangles of a frame for both codings under one disposal assumption.
     */
    private static final class SubFrameParams {
        /**
         * Whether this disposal is tried.
         */
        boolean shouldTry;

        /**
         * Whether an unchanged frame may be skipped.
         */
        final boolean emptyRectAllowed;

        /**
         * The rectangle for lossless coding.
         */
        Rect rectLl;

        /**
         * The rectangle for lossy coding.
         */
        Rect rectLossy;

        /**
         * Constructs the parameters.
         *
         * @param shouldTry whether this disposal is tried
         * @param emptyRectAllowed whether an unchanged frame may be skipped
         */
        SubFrameParams(boolean shouldTry, boolean emptyRectAllowed) {
            this.shouldTry = shouldTry;
            this.emptyRectAllowed = emptyRectAllowed;
        }
    }

    /**
     * Computes both rectangles of a frame against a previous canvas.
     *
     * @param prev the previous canvas
     * @param isKeyFrame whether the frame is a key frame
     * @param params the destination
     */
    private void subRects(int[] prev, boolean isKeyFrame, SubFrameParams params) {
        params.rectLl = subRect(prev, isKeyFrame, params.emptyRectAllowed, true, new Rect(0, 0, canvasWidth, canvasHeight));
        params.rectLossy = subRect(prev, isKeyFrame, params.emptyRectAllowed, false, params.rectLl);
    }

    /**
     * Returns whether blending can reproduce the frame exactly: every non-opaque pixel of the
     * frame equals the previous pixel.
     *
     * @param src the previous canvas
     * @param rect the rectangle
     * @return whether lossless blending is possible
     */
    private boolean losslessBlendingPossible(int[] src, Rect rect) {
        for (var j = rect.y(); j < rect.y() + rect.height(); j++) {
            for (var i = rect.x(); i < rect.x() + rect.width(); i++) {
                var srcPixel = src[j * canvasWidth + i];
                var dstPixel = currCanvasCopy[j * canvasWidth + i];
                if ((dstPixel >>> 24) != 0xFF && srcPixel != dstPixel) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Returns whether blending can reproduce the frame within the lossy tolerance.
     *
     * @param src the previous canvas
     * @param rect the rectangle
     * @param quality the lossy quality
     * @return whether lossy blending is possible
     */
    private boolean lossyBlendingPossible(int[] src, Rect rect, float quality) {
        var maxAllowed = qualityToMaxDiff(quality);
        for (var j = rect.y(); j < rect.y() + rect.height(); j++) {
            for (var i = rect.x(); i < rect.x() + rect.width(); i++) {
                var srcPixel = src[j * canvasWidth + i];
                var dstPixel = currCanvasCopy[j * canvasWidth + i];
                if ((dstPixel >>> 24) != 0xFF && !similar(srcPixel, dstPixel, maxAllowed)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Makes the pixels equal to the previous canvas transparent.
     *
     * @param src the previous canvas
     * @param rect the rectangle
     * @return whether a pixel changed
     */
    private boolean increaseTransparency(int[] src, Rect rect) {
        var modified = false;
        for (var j = rect.y(); j < rect.y() + rect.height(); j++) {
            for (var i = rect.x(); i < rect.x() + rect.width(); i++) {
                var p = j * canvasWidth + i;
                if (src[p] == currCanvasCopy[p] && currCanvasCopy[p] != TRANSPARENT) {
                    currCanvasCopy[p] = TRANSPARENT;
                    modified = true;
                }
            }
        }
        return modified;
    }

    /**
     * Replaces 8x8 blocks similar to the previous canvas with their transparent average.
     *
     * @param src the previous canvas
     * @param rect the rectangle
     * @param quality the lossy quality
     * @return whether a pixel changed
     */
    private boolean flattenSimilarBlocks(int[] src, Rect rect, float quality) {
        var maxAllowed = qualityToMaxDiff(quality);
        var modified = false;
        var blockSize = 8;
        var yStart = (rect.y() + blockSize) & ~(blockSize - 1);
        var yEnd = (rect.y() + rect.height()) & ~(blockSize - 1);
        var xStart = (rect.x() + blockSize) & ~(blockSize - 1);
        var xEnd = (rect.x() + rect.width()) & ~(blockSize - 1);
        for (var j = yStart; j < yEnd; j += blockSize) {
            for (var i = xStart; i < xEnd; i += blockSize) {
                var cnt = 0;
                var avgR = 0;
                var avgG = 0;
                var avgB = 0;
                for (var y = 0; y < blockSize; y++) {
                    for (var x = 0; x < blockSize; x++) {
                        var p = (j + y) * canvasWidth + i + x;
                        var srcPixel = src[p];
                        if ((srcPixel >>> 24) == 0xFF && similar(srcPixel, currCanvasCopy[p], maxAllowed)) {
                            ++cnt;
                            avgR += (srcPixel >>> 16) & 0xFF;
                            avgG += (srcPixel >>> 8) & 0xFF;
                            avgB += srcPixel & 0xFF;
                        }
                    }
                }
                if (cnt == blockSize * blockSize) {
                    var color = ((avgR / cnt) << 16) | ((avgG / cnt) << 8) | (avgB / cnt);
                    for (var y = 0; y < blockSize; y++) {
                        for (var x = 0; x < blockSize; x++) {
                            currCanvasCopy[(j + y) * canvasWidth + i + x] = color;
                        }
                    }
                    modified = true;
                }
            }
        }
        return modified;
    }

    /**
     * Holds one candidate encode: the snapshot of its rectangle and, once run, its result.
     */
    private static final class Candidate {
        /**
         * The frame placement, filled with the encoded image when the candidate is resolved.
         */
        final FrameInfo info;

        /**
         * Whether the candidate is coded losslessly.
         */
        final boolean lossless;

        /**
         * The ARGB snapshot of a lossless candidate, or {@code null}.
         */
        final int[] argb;

        /**
         * The RGBA snapshot of a lossy candidate, or {@code null}.
         */
        final byte[] rgba;

        /**
         * The alpha plane of a lossy candidate with transparency, or {@code null}.
         */
        final byte[] alphaPlane;

        /**
         * An earlier candidate of the same batch with identical input, whose encode this one
         * reuses, or {@code null}.
         */
        Candidate same;

        /**
         * The encode producing the bitstream ({@code byte[]} for lossy, the encoded image for
         * lossless), or {@code null} when reused from {@link #same}.
         */
        ParallelBands.Pending<Object> bitstream;

        /**
         * The encode producing the alpha payload, or {@code null} without transparency.
         */
        ParallelBands.Pending<byte[]> alpha;

        /**
         * Whether {@link #info} holds the encoded image.
         */
        boolean resolved;

        /**
         * Creates a candidate.
         *
         * @param info the frame placement
         * @param lossless whether the candidate is coded losslessly
         * @param argb the lossless snapshot, or {@code null}
         * @param rgba the lossy snapshot, or {@code null}
         * @param alphaPlane the lossy alpha plane, or {@code null}
         */
        Candidate(FrameInfo info, boolean lossless, int[] argb, byte[] rgba, byte[] alphaPlane) {
            this.info = info;
            this.lossless = lossless;
            this.argb = argb;
            this.rgba = rgba;
            this.alphaPlane = alphaPlane;
        }

        /**
         * Returns whether another candidate encodes to the same bytes.
         *
         * @param other the other candidate
         * @return whether the coding, size, blending and pixels are equal
         */
        boolean sameInput(Candidate other) {
            return lossless == other.lossless && info.width == other.info.width && info.height == other.info.height
                    && info.blend == other.info.blend
                    && (lossless ? Arrays.equals(argb, other.argb) : Arrays.equals(rgba, other.rgba));
        }
    }

    /**
     * Holds an alpha plane and the encode of its {@code ALPH} payload, remembered across frames.
     *
     * @param width the plane width
     * @param height the plane height
     * @param plane the alpha samples, row by row
     * @param payload the encode of the payload, possibly still running
     */
    private record AlphaEntry(int width, int height, byte[] plane, ParallelBands.Pending<byte[]> payload) {
    }

    /**
     * Snapshots a rectangle of the working copy as a candidate to encode.
     *
     * <p>The snapshot, including the lossless coding's clearing of transparent pixels in the
     * working copy, happens now, so later changes to the working copy do not affect the
     * candidate.
     *
     * @param rect the rectangle
     * @param lossless whether to encode losslessly
     * @param useBlending whether the frame will be blended
     * @return the candidate
     */
    private Candidate snapshot(Rect rect, boolean lossless, boolean useBlending) {
        var info = new FrameInfo();
        info.x = rect.x();
        info.y = rect.y();
        info.width = rect.width();
        info.height = rect.height();
        info.blend = useBlending;
        var w = rect.width();
        var h = rect.height();
        if (lossless) {
            var argb = new int[w * h];
            for (var y = 0; y < h; y++) {
                var row = (rect.y() + y) * canvasWidth + rect.x();
                for (var x = 0; x < w; x++) {
                    var pixel = currCanvasCopy[row + x];
                    if ((pixel >>> 24) == 0) {
                        pixel = 0;
                        currCanvasCopy[row + x] = 0;
                    }
                    argb[y * w + x] = pixel;
                }
            }
            var candidate = new Candidate(info, true, argb, null, null);
            info.source = candidate;
            return candidate;
        }
        var rgba = new byte[w * h * 4];
        var alpha = new byte[w * h];
        var opaque = 0xFF;
        for (var y = 0; y < h; y++) {
            var row = (rect.y() + y) * canvasWidth + rect.x();
            for (var x = 0; x < w; x++) {
                var pixel = currCanvasCopy[row + x];
                RGBA.set(rgba, (y * w + x) * 4, (pixel & 0xFF00FF00) | ((pixel >>> 16) & 0xFF) | ((pixel & 0xFF) << 16));
                alpha[y * w + x] = (byte) (pixel >>> 24);
                opaque &= pixel >>> 24;
            }
        }
        var candidate = new Candidate(info, false, null, rgba, opaque == 0xFF ? null : alpha);
        info.source = candidate;
        return candidate;
    }

    /**
     * Starts the encodes of candidates without waiting for them: identical inputs are encoded
     * once, the alpha planes of lossy candidates are coded separately from their color and
     * looked up in {@link #alphaMemo} first, and every remaining encode is submitted to the
     * module's shared helpers through {@link ParallelBands#submit}. {@link #resolve} waits for a
     * candidate's result, encoding it on the calling thread when no helper has started it.
     *
     * @implNote This implementation produces the bytes of encoding every candidate on its own:
     * each encode is a deterministic function of its snapshot and the fixed settings, the
     * color coding ignores the alpha plane, and the alpha plane is the snapshot's alpha channel
     * whenever some pixel is not opaque, which is exactly the plane the color conversion
     * extracts. Starting the encodes early lets the next frames' snapshots and encodes proceed
     * while they run; the results are awaited only where a decision needs a size.
     *
     * @param candidates the candidate encodings of one frame
     */
    private void encodeCandidates(List<Candidate> candidates) {
        for (var i = 0; i < candidates.size(); i++) {
            var candidate = candidates.get(i);
            for (var j = 0; j < i; j++) {
                var earlier = candidates.get(j);
                if (earlier.same == null && candidate.sameInput(earlier)) {
                    candidate.same = earlier;
                    break;
                }
            }
            if (candidate.same != null) {
                continue;
            }
            var w = candidate.info.width;
            var h = candidate.info.height;
            if (candidate.lossless) {
                var argb = candidate.argb;
                candidate.bitstream = ParallelBands.submit(() -> FrameEncoder.encodeLossless(argb, w, h, (int) quality, method, false));
                continue;
            }
            var rgba = candidate.rgba;
            var disableFilter = candidate.info.blend;
            candidate.bitstream = ParallelBands.submit(() -> {
                var yuv = FrameEncoder.prepareLossy(rgba, w * 4, w, h, true, false, limits);
                return FrameEncoder.encodeVp8(yuv, quality, method, disableFilter);
            });
            var plane = candidate.alphaPlane;
            if (plane != null) {
                candidate.alpha = rememberedAlpha(w, h, plane);
                if (candidate.alpha == null) {
                    candidate.alpha = ParallelBands.submit(() -> AlphaEncoder.encode(plane, w, h, 100, AlphaEncoder.FILTER_FAST, method));
                    rememberAlpha(w, h, plane, candidate.alpha);
                }
            }
        }
    }

    /**
     * Waits for the encode of a frame placement and stores the image and size in it; does
     * nothing when they are already stored or the placement has no pending encode.
     *
     * @param info the placement
     */
    private static void resolve(FrameInfo info) {
        var candidate = info.source;
        if (candidate == null || candidate.resolved) {
            return;
        }
        var source = candidate.same == null ? candidate : candidate.same;
        if (source.lossless) {
            info.image = (FrameEncoder.EncodedImage) source.bitstream.join();
            info.fileSize = 12 + info.image.size();
        } else {
            var alph = source.alpha == null ? null : source.alpha.join();
            info.image = new FrameEncoder.EncodedImage((byte[]) source.bitstream.join(), false, alph, alph != null);
            info.fileSize = 12 + info.image.size() + (alph != null ? 18 : 0);
        }
        candidate.resolved = true;
    }

    /**
     * Returns the remembered payload of an alpha plane.
     *
     * @param width the plane width
     * @param height the plane height
     * @param plane the alpha samples, row by row
     * @return the encode of the payload, or {@code null} when the plane was not coded recently
     */
    private ParallelBands.Pending<byte[]> rememberedAlpha(int width, int height, byte[] plane) {
        for (var entry : alphaMemo) {
            if (entry.width() == width && entry.height() == height && Arrays.equals(entry.plane(), plane)) {
                return entry.payload();
            }
        }
        return null;
    }

    /**
     * Remembers the payload of an alpha plane, forgetting the oldest entry beyond
     * {@link #ALPHA_MEMO_SIZE}.
     *
     * @param width the plane width
     * @param height the plane height
     * @param plane the alpha samples, row by row
     * @param payload the encode of the payload
     */
    private void rememberAlpha(int width, int height, byte[] plane, ParallelBands.Pending<byte[]> payload) {
        if (rememberedAlpha(width, height, plane) != null) {
            return;
        }
        if (alphaMemo.size() == ALPHA_MEMO_SIZE) {
            alphaMemo.removeFirst();
        }
        alphaMemo.addLast(new AlphaEntry(width, height, plane, payload));
    }

    /**
     * Returns the number of distinct colors of a rectangle, up to 257.
     *
     * @param rect the rectangle
     * @return the color count, 257 meaning more than 256
     */
    private int colorCount(Rect rect) {
        var hashSize = 1024;
        var inUse = new boolean[hashSize];
        var colors = new int[hashSize];
        var numColors = 0;
        for (var j = rect.y(); j < rect.y() + rect.height(); j++) {
            for (var i = rect.x(); i < rect.x() + rect.width(); i++) {
                var pixel = currCanvasCopy[j * canvasWidth + i];
                var key = (pixel * 0x1E35A7BD) >>> 22;
                while (true) {
                    if (!inUse[key]) {
                        inUse[key] = true;
                        colors[key] = pixel;
                        if (++numColors > 256) {
                            return 257;
                        }
                        break;
                    } else if (colors[key] == pixel) {
                        break;
                    }
                    key = (key + 1) & (hashSize - 1);
                }
            }
        }
        return numColors;
    }

    /**
     * Holds the candidates of one frame configuration until their encodes are known.
     */
    private static final class FramePlan {
        /**
         * Whether the plan encodes a key frame.
         */
        final boolean isKeyFrame;

        /**
         * The encoded candidates: lossless/none, lossless/background, lossy/none,
         * lossy/background; {@code null} when not evaluated.
         */
        final FrameInfo[] candidates = new FrameInfo[4];

        /**
         * The candidate slot of each snapshot.
         */
        final List<Integer> slots = new ArrayList<>(4);

        /**
         * The snapshots to encode.
         */
        final List<Candidate> snapshots = new ArrayList<>(4);

        /**
         * Creates an empty plan.
         *
         * @param isKeyFrame whether the plan encodes a key frame
         */
        FramePlan(boolean isKeyFrame) {
            this.isKeyFrame = isKeyFrame;
        }

        /**
         * Adds a candidate.
         *
         * @param slot the candidate slot
         * @param candidate the snapshot
         */
        void add(int slot, Candidate candidate) {
            slots.add(slot);
            snapshots.add(candidate);
        }
    }

    /**
     * Plans the candidates of one disposal assumption.
     *
     * @param plan the plan receiving the candidate tasks
     * @param disposeNone whether the previous frame is assumed not disposed
     * @param isLossless whether the configuration is lossless
     * @param isKeyFrame whether the frame is a key frame
     * @param params the rectangles
     */
    private void generateCandidates(FramePlan plan, boolean disposeNone, boolean isLossless, boolean isKeyFrame,
                                    SubFrameParams params) {
        var prev = disposeNone ? prevCanvas : prevCanvasDisposed;
        copyCurrentCanvas();
        var useBlendingLl = !isKeyFrame && losslessBlendingPossible(prev, params.rectLl);
        var useBlendingLossy = !isKeyFrame && lossyBlendingPossible(prev, params.rectLossy, quality);
        boolean evaluateLl;
        boolean evaluateLossy;
        if (!allowMixed) {
            evaluateLl = isLossless;
            evaluateLossy = !isLossless;
        } else if (minimize) {
            evaluateLl = true;
            evaluateLossy = true;
        } else {
            var numColors = colorCount(params.rectLl);
            evaluateLl = numColors < MAX_COLORS_LOSSLESS;
            evaluateLossy = numColors >= MIN_COLORS_LOSSY;
        }
        if (evaluateLl) {
            copyCurrentCanvas();
            if (useBlendingLl) {
                currCanvasCopyModified = increaseTransparency(prev, params.rectLl);
            }
            plan.add(disposeNone ? 0 : 1, snapshot(params.rectLl, true, useBlendingLl));
        }
        if (evaluateLossy) {
            copyCurrentCanvas();
            if (useBlendingLossy) {
                currCanvasCopyModified = flattenSimilarBlocks(prev, params.rectLossy, quality);
            }
            plan.add(disposeNone ? 2 : 3, snapshot(params.rectLossy, false, useBlendingLossy));
            currCanvasCopyModified = true;
        }
    }

    /**
     * Encodes the current frame as a sub-frame or a key frame.
     *
     * @param isKeyFrame whether to encode a key frame
     * @param encodedFrame the destination
     * @return whether the frame was skipped because nothing changed
     */
    private boolean setFrame(boolean isKeyFrame, EncodedFrame encodedFrame) {
        var plan = planFrame(isKeyFrame);
        if (plan == null) {
            return true;
        }
        runPlans(plan, null);
        pickBestCandidate(plan.candidates, isKeyFrame, encodedFrame);
        return false;
    }

    /**
     * Computes the rectangles of the current frame for one configuration and snapshots its
     * candidates, without encoding them.
     *
     * @param isKeyFrame whether to plan a key frame
     * @return the plan, or {@code null} when the frame is skipped because nothing changed
     */
    private FramePlan planFrame(boolean isKeyFrame) {
        var plan = new FramePlan(isKeyFrame);
        var considerLossless = lossless || allowMixed;
        var considerLossy = !lossless || allowMixed;
        var disposeBgPossible = !isKeyFrame && !prevCandidateUndecided;
        var disposeNoneParams = new SubFrameParams(true, !isFirstFrame);
        var disposeBgParams = new SubFrameParams(false, false);
        lastConfigLossless = lossless;
        subRects(prevCanvas, isKeyFrame, disposeNoneParams);
        if ((considerLossless && disposeNoneParams.rectLl.empty()) || (considerLossy && disposeNoneParams.rectLossy.empty())) {
            return null;
        }
        if (disposeBgPossible) {
            System.arraycopy(prevCanvas, 0, prevCanvasDisposed, 0, prevCanvas.length);
            clearRect(prevCanvasDisposed, prevRect);
            subRects(prevCanvasDisposed, isKeyFrame, disposeBgParams);
            if (minimize) {
                disposeBgParams.shouldTry = true;
                disposeNoneParams.shouldTry = true;
            } else if ((lossless && disposeBgParams.rectLl.area() < disposeNoneParams.rectLl.area())
                    || (!lossless && disposeBgParams.rectLossy.area() < disposeNoneParams.rectLossy.area())) {
                disposeBgParams.shouldTry = true;
                disposeNoneParams.shouldTry = false;
            }
        }
        if (disposeNoneParams.shouldTry) {
            generateCandidates(plan, true, lossless, isKeyFrame, disposeNoneParams);
        }
        if (disposeBgParams.shouldTry) {
            generateCandidates(plan, false, lossless, isKeyFrame, disposeBgParams);
        }
        return plan;
    }

    /**
     * Encodes the candidates of one or two plans together and stores them in their plans.
     *
     * @param first the first plan
     * @param second the second plan, or {@code null}
     */
    private void runPlans(FramePlan first, FramePlan second) {
        var all = new ArrayList<Candidate>(first.snapshots);
        if (second != null) {
            all.addAll(second.snapshots);
        }
        encodeCandidates(all);
        for (var i = 0; i < first.snapshots.size(); i++) {
            first.candidates[first.slots.get(i)] = first.snapshots.get(i).info;
        }
        if (second != null) {
            for (var i = 0; i < second.snapshots.size(); i++) {
                second.candidates[second.slots.get(i)] = second.snapshots.get(i).info;
            }
        }
    }

    /**
     * Clears a rectangle of a canvas to transparent.
     *
     * @param canvas the ARGB canvas, modified in place
     * @param rect the rectangle
     */
    private void clearRect(int[] canvas, Rect rect) {
        for (var j = rect.y(); j < rect.y() + rect.height(); j++) {
            Arrays.fill(canvas, j * canvasWidth + rect.x(), j * canvasWidth + rect.x() + rect.width(), TRANSPARENT);
        }
    }

    /**
     * Keeps the smallest candidate and records the previous frame's disposal it assumes.
     *
     * <p>A lone candidate wins without its size, so its encode keeps running; competing
     * candidates are awaited.
     *
     * @param candidates the candidates, {@code null} when not evaluated
     * @param isKeyFrame whether the frame is a key frame
     * @param encodedFrame the destination
     */
    private void pickBestCandidate(FrameInfo[] candidates, boolean isKeyFrame, EncodedFrame encodedFrame) {
        var evaluated = 0;
        for (var candidate : candidates) {
            if (candidate != null) {
                ++evaluated;
            }
        }
        if (evaluated > 1) {
            for (var candidate : candidates) {
                if (candidate != null) {
                    resolve(candidate);
                }
            }
        }
        var bestIdx = -1;
        var bestSize = Long.MAX_VALUE;
        for (var i = 0; i < 4; i++) {
            if (candidates[i] != null && candidates[i].fileSize < bestSize) {
                bestIdx = i;
                bestSize = candidates[i].fileSize;
            }
        }
        var best = candidates[bestIdx];
        if (isKeyFrame) {
            encodedFrame.keyFrame = best;
        } else {
            encodedFrame.subFrame = best;
            setPreviousDisposeMethod(bestIdx == 1 || bestIdx == 3);
        }
        prevRect = new Rect(best.x, best.y, best.width, best.height);
    }

    /**
     * Sets the disposal of the previous frame.
     *
     * @param background whether it is disposed to transparent
     */
    private void setPreviousDisposeMethod(boolean background) {
        var prev = frameAt(count - 2);
        if (prevCandidateUndecided) {
            if (prev.subFrame != null) {
                prev.subFrame.disposeBackground = background;
            }
            if (prev.keyFrame != null) {
                prev.keyFrame.disposeBackground = background;
            }
        } else {
            var info = prev.isKeyFrame ? prev.keyFrame : prev.subFrame;
            info.disposeBackground = background;
        }
    }

    /**
     * Adds time to the previous frame, emitting a transparent 1x1 frame when the duration would
     * overflow its field.
     *
     * @param duration the time to add, below {@link #MAX_DURATION}
     */
    private void increasePreviousDuration(int duration) {
        var prev = frameAt(count - 1);
        var current = prev.isKeyFrame ? prev.keyFrame : prev.subFrame;
        var newDuration = current.duration + duration;
        if (newDuration >= MAX_DURATION) {
            var canUseLossless = lastConfigLossless || allowMixed;
            var frame = encodedFrames[start + count];
            frame.release();
            var info = new FrameInfo();
            info.width = 1;
            info.height = 1;
            info.blend = true;
            info.duration = duration;
            info.image = canUseLossless ? new FrameEncoder.EncodedImage(LOSSLESS_1X1, true, null, true)
                    : new FrameEncoder.EncodedImage(LOSSY_1X1_VP8, false, LOSSY_1X1_ALPH, true);
            frame.subFrame = info;
            frame.isKeyFrame = false;
            ++count;
            ++countSinceKeyFrame;
            flushCount = count - 1;
            prevCandidateUndecided = false;
            prevRect = new Rect(0, 0, 1, 1);
        } else {
            if (prev.subFrame != null) {
                prev.subFrame.duration = newDuration;
            }
            if (prev.keyFrame != null) {
                prev.keyFrame.duration = newDuration;
            }
        }
    }

    /**
     * Encodes the current frame and decides whether it or an earlier frame becomes a key frame.
     */
    private void cacheFrame() {
        var position = count;
        var encodedFrame = frameAt(position);
        encodedFrame.release();
        ++count;
        boolean skipped;
        if (isFirstFrame) {
            skipped = setFrame(true, encodedFrame);
            encodedFrame.isKeyFrame = true;
            flushCount = 0;
            countSinceKeyFrame = 0;
            prevCandidateUndecided = false;
        } else {
            ++countSinceKeyFrame;
            if (countSinceKeyFrame <= kmin) {
                skipped = setFrame(false, encodedFrame);
                if (!skipped) {
                    encodedFrame.isKeyFrame = false;
                    flushCount = count - 1;
                    prevCandidateUndecided = false;
                }
            } else {
                var subPlan = planFrame(false);
                skipped = subPlan == null;
                if (!skipped) {
                    var keyPlan = planFrame(true);
                    runPlans(subPlan, keyPlan);
                    pickBestCandidate(subPlan.candidates, false, encodedFrame);
                    var prevRectSub = prevRect;
                    pickBestCandidate(keyPlan.candidates, true, encodedFrame);
                    var prevRectKey = prevRect;
                    resolve(encodedFrame.keyFrame);
                    resolve(encodedFrame.subFrame);
                    var currDelta = encodedFrame.keyFrame.fileSize - encodedFrame.subFrame.fileSize;
                    if (currDelta <= bestDelta) {
                        if (keyframe != KEYFRAME_NONE) {
                            frameAt(keyframe).isKeyFrame = false;
                        }
                        encodedFrame.isKeyFrame = true;
                        prevCandidateUndecided = true;
                        keyframe = position;
                        bestDelta = currDelta;
                        flushCount = count - 1;
                    } else {
                        encodedFrame.isKeyFrame = false;
                        prevCandidateUndecided = false;
                    }
                    if (countSinceKeyFrame >= kmax) {
                        flushCount = count - 1;
                        countSinceKeyFrame = 0;
                        keyframe = KEYFRAME_NONE;
                        bestDelta = DELTA_INFINITY;
                    }
                    if (!prevCandidateUndecided) {
                        prevRect = encodedFrame.isKeyFrame ? prevRectKey : prevRectSub;
                    }
                }
            }
        }
        if (skipped) {
            encodedFrame.release();
            --count;
            if (!isFirstFrame) {
                --countSinceKeyFrame;
            }
            return;
        }
        System.arraycopy(currCanvas, 0, prevCanvas, 0, currCanvas.length);
        isFirstFrame = false;
    }

    /**
     * Emits the frames whose key frame decision is final.
     */
    private void flushFrames() {
        while (flushCount > 0) {
            var curr = frameAt(0);
            var info = curr.isKeyFrame ? curr.keyFrame : curr.subFrame;
            output.add(info.copy());
            curr.release();
            ++start;
            --flushCount;
            --count;
            if (keyframe != KEYFRAME_NONE) {
                --keyframe;
            }
        }
        if (count == 1 && start != 0) {
            var temp = encodedFrames[0];
            encodedFrames[0] = encodedFrames[start];
            encodedFrames[start] = temp;
            encodedFrames[start].release();
            start = 0;
        }
    }

    /**
     * Assembles the file from emitted frames.
     *
     * @param frames the emitted frames, in display order
     * @return the WebP file
     */
    private byte[] assemble(List<FrameInfo> frames) {
        var chunks = new ArrayList<byte[]>();
        if (frames.size() == 1 && covers(frames.getFirst())) {
            var image = frames.getFirst().image;
            if (image.alph() != null) {
                chunks.add(WebpWriter.vp8x(WebpParser.FLAG_ALPHA, canvasWidth, canvasHeight));
            }
            chunks.addAll(image.chunks());
            return WebpWriter.riff(chunks);
        }
        var hasAlpha = false;
        for (var frame : frames) {
            hasAlpha |= frame.image.hasAlpha();
        }
        chunks.add(WebpWriter.vp8x(WebpParser.FLAG_ANIMATION | (hasAlpha ? WebpParser.FLAG_ALPHA : 0), canvasWidth, canvasHeight));
        var anim = new byte[WebpParser.ANIM_SIZE];
        ByteArrays.put32le(anim, 0, 0xFFFFFFFF);
        ByteArrays.put16le(anim, 4, loopCount);
        chunks.add(WebpWriter.chunk(FourCc.ANIM, anim));
        for (var frame : frames) {
            var imageChunks = frame.image.chunks();
            var payloadSize = WebpParser.ANMF_HEADER_SIZE;
            for (var chunk : imageChunks) {
                payloadSize += chunk.length;
            }
            var payload = new byte[payloadSize];
            ByteArrays.put24le(payload, 0, frame.x / 2);
            ByteArrays.put24le(payload, 3, frame.y / 2);
            ByteArrays.put24le(payload, 6, frame.width - 1);
            ByteArrays.put24le(payload, 9, frame.height - 1);
            ByteArrays.put24le(payload, 12, frame.duration);
            payload[15] = (byte) ((frame.disposeBackground ? 1 : 0) | (frame.blend ? 0 : 2));
            var pos = WebpParser.ANMF_HEADER_SIZE;
            for (var chunk : imageChunks) {
                System.arraycopy(chunk, 0, payload, pos, chunk.length);
                pos += chunk.length;
            }
            chunks.add(WebpWriter.chunk(FourCc.ANMF, payload));
        }
        return WebpWriter.riff(chunks);
    }

    /**
     * Re-encodes a single partial frame on the full canvas as a still image.
     *
     * @param frame the only emitted frame
     * @return the still image file, or {@code null} when the frame cannot be decoded
     */
    private byte[] optimizeSingleFrame(FrameInfo frame) {
        var canvas = new byte[canvasWidth * canvasHeight * 4];
        var stride = canvasWidth * 4;
        var offset = frame.y * stride + frame.x * 4;
        var image = frame.image;
        if (image.lossless()) {
            Vp8lDecoder.decodeRgbaInto(image.bitstream(), 0, image.bitstream().length, limits, canvas, offset, stride);
        } else {
            var picture = FrameDecoder.decodeLossy(image.bitstream(), image.alph(), frame.width, frame.height, limits);
            YuvConverter.toRgba(picture, canvas, offset, stride);
        }
        var full = encodeStill(canvas, lastConfigLossless);
        if (allowMixed) {
            var reversed = encodeStill(canvas, !lastConfigLossless);
            if (reversed.length < full.length) {
                full = reversed;
            }
        }
        return full;
    }

    /**
     * Encodes a full canvas as a still image.
     *
     * @param canvas the RGBA canvas
     * @param lossless whether to encode losslessly
     * @return the WebP file
     */
    private byte[] encodeStill(byte[] canvas, boolean lossless) {
        FrameEncoder.EncodedImage image;
        if (lossless) {
            var argb = FrameEncoder.toArgb(canvas, 0, canvasWidth * 4, canvasWidth, canvasHeight, true, false);
            image = FrameEncoder.encodeLossless(argb, canvasWidth, canvasHeight, (int) quality, method, false);
        } else {
            var yuv = FrameEncoder.prepareLossy(canvas, canvasWidth * 4, canvasWidth, canvasHeight, true, false, limits);
            var alph = FrameEncoder.encodeAlpha(yuv, 100, 1, method);
            image = new FrameEncoder.EncodedImage(FrameEncoder.encodeVp8(yuv, quality, method, false), false, alph, alph != null);
        }
        var chunks = new ArrayList<byte[]>();
        if (image.alph() != null) {
            chunks.add(WebpWriter.vp8x(WebpParser.FLAG_ALPHA, canvasWidth, canvasHeight));
        }
        chunks.addAll(image.chunks());
        return WebpWriter.riff(chunks);
    }
}
