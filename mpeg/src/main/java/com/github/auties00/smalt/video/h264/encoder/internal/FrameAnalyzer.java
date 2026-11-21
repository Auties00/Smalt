package com.github.auties00.smalt.video.h264.encoder.internal;

import java.util.Arrays;

/**
 * Estimates the coding cost of each source picture on a half-resolution copy, before it is
 * encoded.
 *
 * <p>For every macroblock the analyzer measures an intra cost (the best of DC, horizontal and
 * vertical prediction from the neighboring half-resolution samples) and an inter cost against
 * the previous source picture (a small predictive search), both as SATD. The sums drive rate
 * control, the ratio of inter to intra cost detects scene cuts, and the per-macroblock
 * half-resolution vectors seed the full-resolution motion search. The analyzer also measures
 * each macroblock's luma activity for adaptive quantization.
 *
 * <p>The work is split into macroblock rows that several workers can share in a wavefront:
 * {@link #analyzeRow} downscales a row's source samples and analyzes its macroblocks, each
 * waiting until the row above has analyzed the macroblock above it, because a block's search
 * starts from the vector of the block above. The result does not depend on the number of
 * workers or on which worker analyzes which row.
 */
final class FrameAnalyzer {
    /**
     * The padding of the half-resolution planes, in samples.
     */
    private static final int PAD = 16;

    /**
     * The pixel kernels.
     */
    private PixelKernels pixels;

    /**
     * The picture width in macroblocks.
     */
    private final int mbWidth;

    /**
     * The picture height in macroblocks.
     */
    private final int mbHeight;

    /**
     * The half-resolution width.
     */
    private final int width;

    /**
     * The half-resolution height.
     */
    private final int height;

    /**
     * The half-resolution row stride.
     */
    private final int stride;

    /**
     * The index of half-resolution sample {@code (0, 0)}.
     */
    private final int origin;

    /**
     * The half-resolution luma of the current picture.
     */
    private byte[] current;

    /**
     * The half-resolution luma of the previous picture.
     */
    private byte[] previous;

    /**
     * Whether {@link #previous} holds a picture.
     */
    private boolean hasPrevious;

    /**
     * Whether the analysis in progress runs the inter search.
     */
    private boolean doInter;

    /**
     * Whether the analysis in progress measures the luma {@link #activity} of the macroblocks;
     * when it does not, {@link #activity} keeps the values of an earlier picture.
     */
    private boolean doActivity;

    /**
     * The intra cost of each macroblock.
     */
    final int[] intraCost;

    /**
     * The inter cost of each macroblock, or the intra cost when no previous picture exists.
     */
    final int[] interCost;

    /**
     * The half-resolution vector of each macroblock, packed as by
     * {@link MotionPredictor#pack(int, int)} in half-resolution full samples.
     */
    final int[] vectors;

    /**
     * The luma activity of each macroblock: the AC energy of its four 8x8 blocks (the sum of
     * their variances times 64) plus 64.
     */
    final int[] activity;

    /**
     * The brightness of each macroblock, the sum of its 256 luma samples, measured with
     * {@link #activity}.
     */
    final int[] brightness;

    /**
     * The sum of {@link #intraCost}.
     */
    long intraSum;

    /**
     * The sum over macroblocks of the smaller of the inter and intra cost.
     */
    long interSum;

    /**
     * The partial {@link #intraSum} of each macroblock row.
     */
    private final long[] intraSums;

    /**
     * The partial {@link #interSum} of each macroblock row.
     */
    private final long[] interSums;

    /**
     * Constructs an analyzer.
     *
     * @param pixels the pixel kernels
     * @param mbWidth the picture width in macroblocks
     * @param mbHeight the picture height in macroblocks
     */
    FrameAnalyzer(PixelKernels pixels, int mbWidth, int mbHeight) {
        this.pixels = pixels;
        this.intraSums = new long[mbHeight];
        this.interSums = new long[mbHeight];
        this.mbWidth = mbWidth;
        this.mbHeight = mbHeight;
        this.width = mbWidth * 8;
        this.height = mbHeight * 8;
        this.stride = width + 2 * PAD;
        this.origin = PAD * stride + PAD;
        this.current = new byte[stride * (height + 2 * PAD)];
        this.previous = new byte[stride * (height + 2 * PAD)];
        var count = mbWidth * mbHeight;
        this.intraCost = new int[count];
        this.interCost = new int[count];
        this.vectors = new int[count];
        this.activity = new int[count];
        this.brightness = new int[count];
    }

    /**
     * Replaces the kernels the analyzer measures with, between two pictures.
     *
     * @param pixels the pixel kernels
     */
    void useKernels(PixelKernels pixels) {
        this.pixels = pixels;
    }

    /**
     * Starts the analysis of a source picture, before its rows are analyzed.
     *
     * @param searchInter whether to run the inter search; when {@code false}, or when no
     * previous picture exists, inter costs equal intra costs
     * @param measureActivity whether to measure the luma activity, which only adaptive
     * quantization reads
     */
    void start(boolean searchInter, boolean measureActivity) {
        var swap = previous;
        previous = current;
        current = swap;
        doInter = searchInter && hasPrevious;
        doActivity = measureActivity;
    }

    /**
     * Downscales and analyzes one macroblock row of the source picture as one step of the
     * analysis wavefront.
     *
     * <p>The row's progress, at {@code progress} row {@code base + mbY}, becomes 1 once its
     * half-resolution samples exist and {@code 1 + n} once its first {@code n} macroblocks are
     * analyzed, published every {@link Wavefront#GROUP} macroblocks and at the end of the row.
     * The row reads the half-resolution row above, so it first waits until the row above
     * reports progress 1, and with the inter search it waits before each macroblock until the
     * macroblock above is analyzed. Every row's progress must be {@code 0} when the analysis
     * starts, and each row must be analyzed by exactly one worker, after the row above was
     * handed to one.
     *
     * @param source the source picture
     * @param mbY the macroblock row
     * @param progress the wavefront holding the progress of every row
     * @param base the index of row {@code 0}'s progress in {@code progress}
     * @throws java.util.concurrent.CancellationException if the wavefront was aborted
     */
    void analyzeRow(Frame source, int mbY, Wavefront progress, int base) {
        downscaleRows(source, mbY * 8, mbY * 8 + 8);
        if (mbY == 0) {
            padTop();
        }
        if (mbY == mbHeight - 1) {
            padBottom();
        }
        progress.publish(base + mbY, 1);
        var inter = doInter;
        var above = 0;
        if (mbY > 0) {
            above = progress.awaitProgress(base + mbY - 1, 1);
        }
        long intraTotal = 0;
        long interTotal = 0;
        for (var mbX = 0; mbX < mbWidth; mbX++) {
            if (mbY > 0 && inter && above < mbX + 2) {
                above = progress.awaitProgress(base + mbY - 1, mbX + 2);
            }
            var index = mbY * mbWidth + mbX;
            var offset = origin + mbY * 8 * stride + mbX * 8;
            var intraBlock = intraCost(offset, mbX, mbY);
            intraCost[index] = intraBlock;
            intraTotal += intraBlock;
            if (inter) {
                var interBlock = interCost(offset, mbX, mbY, index);
                interCost[index] = interBlock;
                interTotal += Math.min(interBlock, intraBlock);
            } else {
                interCost[index] = intraBlock;
                vectors[index] = 0;
                interTotal += intraBlock;
            }
            if (doActivity) {
                activity[index] = activity(source, mbX, mbY, index);
            }
            if ((mbX + 1) % Wavefront.GROUP == 0 || mbX + 1 == mbWidth) {
                progress.publish(base + mbY, mbX + 2);
            }
        }
        intraSums[mbY] = intraTotal;
        interSums[mbY] = interTotal;
    }

    /**
     * Completes the analysis once every row was analyzed: sums the row costs into
     * {@link #intraSum} and {@link #interSum}.
     */
    void finish() {
        intraSum = 0;
        interSum = 0;
        for (var mbY = 0; mbY < mbHeight; mbY++) {
            intraSum += intraSums[mbY];
            interSum += interSums[mbY];
        }
        hasPrevious = true;
    }

    /**
     * Returns the intra cost of one half-resolution block.
     *
     * @param offset the index of the block's top-left sample
     * @param mbX the macroblock column
     * @param mbY the macroblock row
     * @return the smallest SATD of the DC, horizontal and vertical predictions
     */
    private int intraCost(int offset, int mbX, int mbY) {
        var sumTop = 0;
        var sumLeft = 0;
        for (var i = 0; i < 8; i++) {
            if (mbY > 0) {
                sumTop += current[offset - stride + i] & 0xFF;
            }
            if (mbX > 0) {
                sumLeft += current[offset + i * stride - 1] & 0xFF;
            }
        }
        int dc;
        if (mbX > 0 && mbY > 0) {
            dc = (sumTop + sumLeft + 8) >> 4;
        } else if (mbX > 0) {
            dc = (sumLeft + 4) >> 3;
        } else if (mbY > 0) {
            dc = (sumTop + 4) >> 3;
        } else {
            dc = 128;
        }
        return pixels.intraSatd8x8(current, offset, stride, dc, mbY > 0, mbX > 0) + 4;
    }

    /**
     * Returns the inter cost of one half-resolution block and stores its vector.
     *
     * @param offset the index of the block's top-left sample
     * @param mbX the macroblock column
     * @param mbY the macroblock row
     * @param index the macroblock index
     * @return the SATD of the best vector plus a small vector penalty
     */
    private int interCost(int offset, int mbX, int mbY, int index) {
        var bestX = 0;
        var bestY = 0;
        var bestSad = sadAt(offset, mbX, mbY, 0, 0);
        if (mbX > 0) {
            var v = vectors[index - 1];
            var sad = sadAt(offset, mbX, mbY, MotionPredictor.x(v), MotionPredictor.y(v));
            if (sad < bestSad) {
                bestSad = sad;
                bestX = MotionPredictor.x(v);
                bestY = MotionPredictor.y(v);
            }
        }
        if (mbY > 0) {
            var v = vectors[index - mbWidth];
            var sad = sadAt(offset, mbX, mbY, MotionPredictor.x(v), MotionPredictor.y(v));
            if (sad < bestSad) {
                bestSad = sad;
                bestX = MotionPredictor.x(v);
                bestY = MotionPredictor.y(v);
            }
        }
        var previousVector = vectors[index];
        var sadPrevious = sadAt(offset, mbX, mbY, MotionPredictor.x(previousVector), MotionPredictor.y(previousVector));
        if (sadPrevious < bestSad) {
            bestSad = sadPrevious;
            bestX = MotionPredictor.x(previousVector);
            bestY = MotionPredictor.y(previousVector);
        }
        var back = -1;
        for (var step = 0; step < 16; step++) {
            var moved = false;
            for (var d = 0; d < 4; d++) {
                // the neighbor back towards the position before the last move cost more than the best
                if (d == back) {
                    continue;
                }
                var dx = d == 0 ? -1 : d == 1 ? 1 : 0;
                var dy = d == 2 ? -1 : d == 3 ? 1 : 0;
                var sad = sadAt(offset, mbX, mbY, bestX + dx, bestY + dy);
                if (sad < bestSad) {
                    bestSad = sad;
                    bestX += dx;
                    bestY += dy;
                    moved = true;
                    back = d ^ 1;
                }
            }
            if (!moved) {
                break;
            }
        }
        vectors[index] = MotionPredictor.pack(bestX, bestY);
        var ref = offset + bestY * stride + bestX;
        return pixels.satd(current, offset, stride, previous, ref, stride, 8, 8) + 4
                + 2 * (Math.abs(bestX) + Math.abs(bestY) > 0 ? 1 : 0);
    }

    /**
     * Returns the SAD of a block against the previous picture at a vector, or a large value when
     * the vector leaves the padded area.
     *
     * @param offset the index of the block's top-left sample
     * @param mbX the macroblock column
     * @param mbY the macroblock row
     * @param mx the horizontal vector in half-resolution samples
     * @param my the vertical vector in half-resolution samples
     * @return the SAD
     */
    private int sadAt(int offset, int mbX, int mbY, int mx, int my) {
        var x = mbX * 8 + mx;
        var y = mbY * 8 + my;
        if (x < -PAD || y < -PAD || x + 8 > width + PAD || y + 8 > height + PAD) {
            return Integer.MAX_VALUE;
        }
        return pixels.sad(current, offset, stride, previous, offset + my * stride + mx, stride, 8, 8);
    }

    /**
     * Returns the luma activity of a macroblock of the full-resolution source, storing its
     * {@link #brightness}.
     *
     * @param source the source picture
     * @param mbX the macroblock column
     * @param mbY the macroblock row
     * @param index the macroblock address
     * @return the sum of the four 8x8 blocks' variances times 64, plus 64
     */
    private int activity(Frame source, int mbX, int mbY, int index) {
        long energy = 0;
        long total = 0;
        for (var block = 0; block < 4; block++) {
            var offset = source.lumaOrigin + (mbY * 16 + (block >> 1) * 8) * source.lumaStride + mbX * 16
                    + (block & 1) * 8;
            var packed = pixels.sumAndSquares(source.y, offset, source.lumaStride, 8, 8);
            var sum = packed & 0xFFFFFFFFL;
            var squares = packed >>> 32;
            energy += squares * 64 - sum * sum;
            total += sum;
        }
        brightness[index] = (int) total;
        return (int) Math.min(Integer.MAX_VALUE, (energy >> 6) + 64);
    }

    /**
     * Builds a band of rows of the half-resolution luma of a source picture, with their left
     * and right padding.
     *
     * @param source the source picture
     * @param from the first half-resolution row
     * @param to the row after the band
     */
    private void downscaleRows(Frame source, int from, int to) {
        var src = source.y;
        var srcStride = source.lumaStride;
        for (var y = from; y < to; y++) {
            var s0 = source.lumaOrigin + 2 * y * srcStride;
            var d = origin + y * stride;
            pixels.downscale(src, s0, srcStride, current, d, stride, width, 1);
            Arrays.fill(current, d - PAD, d, current[d]);
            Arrays.fill(current, d + width, d + width + PAD, current[d + width - 1]);
        }
    }

    /**
     * Replicates the first half-resolution row into the top padding.
     */
    private void padTop() {
        for (var row = 1; row <= PAD; row++) {
            System.arraycopy(current, origin - PAD, current, origin - PAD - row * stride, stride);
        }
    }

    /**
     * Replicates the last half-resolution row into the bottom padding.
     */
    private void padBottom() {
        for (var row = 1; row <= PAD; row++) {
            System.arraycopy(current, origin + (height - 1) * stride - PAD, current,
                    origin + (height - 1 + row) * stride - PAD, stride);
        }
    }
}
