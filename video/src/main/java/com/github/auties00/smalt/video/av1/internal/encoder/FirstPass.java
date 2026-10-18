package com.github.auties00.smalt.video.av1.internal.encoder;

import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.internal.task.ParallelBands;

/**
 * Measures how well each frame of the lookahead predicts from its predecessors, the statistics the
 * good-quality encoder places key frames, sizes golden frame groups, boosts alternate references
 * and spreads a variable bitrate with.
 *
 * <p>Every 16x16 luma block of a frame is predicted three ways: intra, with 4x4 DC predictions
 * from the source samples above and to the left; inter from the previous frame, with a full-sample
 * diamond motion search; and inter at zero motion from the frame before that (the second
 * reference). The sums of the squared prediction errors, normalized per block, and the counts of
 * blocks each prediction wins make a frame's {@link Stats}.
 *
 * @implNote This implementation follows libaom's first pass ({@code av1/encoder/firstpass.c}:
 * {@code firstpass_intra_prediction}, {@code firstpass_inter_prediction},
 * {@code accumulate_mv_stats}, {@code update_firstpass_stats}) on the source
 * pictures instead of a first-pass reconstruction, with the constants
 * {@code INTRA_MODE_PENALTY}, {@code NCOUNT_INTRA_THRESH}, {@code NCOUNT_INTRA_FACTOR}
 * and the {@code 200 * sqrt(blocks)} error floor; the second reference is the frame two
 * back rather than the last golden frame.
 */
final class FirstPass {
    /**
     * The cost added to the intra error, matching the overhead of a zero motion vector
     * ({@code INTRA_MODE_PENALTY}).
     */
    private static final int INTRA_MODE_PENALTY = 1024;

    /**
     * The intra error above which a close inter error counts as neutral ({@code NCOUNT_INTRA_THRESH}).
     */
    private static final int NCOUNT_INTRA_THRESH = 8192;

    /**
     * The ratio of intra to inter error below which a block counts as neutral
     * ({@code NCOUNT_INTRA_FACTOR}).
     */
    private static final int NCOUNT_INTRA_FACTOR = 3;

    /**
     * The largest full-sample motion vector component searched.
     */
    private static final int RANGE = 48;

    /**
     * The first-pass statistics of one frame.
     */
    static final class Stats {
        /**
         * The intra prediction error per 16x16 block.
         */
        double intraError;

        /**
         * The error of the better of intra and inter prediction from the previous frame, per block.
         */
        double codedError;

        /**
         * The error of the better of intra and zero-motion inter prediction from the second
         * reference, per block.
         */
        double srCodedError;

        /**
         * The share of blocks better predicted from the previous frame than intra.
         */
        double pcntInter;

        /**
         * The share of blocks predicted from the previous frame with a non-zero motion vector.
         */
        double pcntMotion;

        /**
         * The share of blocks better predicted from the second reference than from the previous
         * frame and intra.
         */
        double pcntSecondRef;

        /**
         * The share of blocks whose intra and inter errors are close.
         */
        double pcntNeutral;

        /**
         * The balance of motion vectors pointing into and out of the frame, -1 to 1.
         */
        double mvInOutCount;

        /**
         * The mean motion vector row, in samples, over the frame height.
         */
        double mvr;

        /**
         * The mean absolute motion vector row, over the frame height.
         */
        double mvrAbs;

        /**
         * The mean motion vector column, over the frame width.
         */
        double mvc;

        /**
         * The mean absolute motion vector column, over the frame width.
         */
        double mvcAbs;

        /**
         * Creates the statistics of a frame that has no predecessor: every block is intra.
         */
        Stats() {
        }
    }

    /**
     * Prevents instantiation.
     */
    private FirstPass() {
        throw new AssertionError();
    }

    /**
     * Widens a picture's luma plane to 16-bit samples.
     *
     * @param picture the 4:2:0 picture
     * @return the plane, of stride the picture width
     */
    static short[] luma(YuvPicture picture) {
        var w = picture.width();
        var h = picture.height();
        var out = new short[w * h];
        var y = picture.y();
        var stride = picture.yStride();
        for (var r = 0; r < h; r++) {
            var s = r * stride;
            var d = r * w;
            for (var c = 0; c < w; c++) {
                out[d + c] = (short) (y[s + c] & 0xFF);
            }
        }
        return out;
    }

    /**
     * Computes the statistics of a frame.
     *
     * @param cur the frame's luma, of stride {@code width}
     * @param last the previous frame's luma, or {@code null} for the first frame
     * @param second the luma of the frame before the previous one, or {@code null}
     * @param width the picture width
     * @param height the picture height
     * @param threads the most threads to use
     * @return the statistics
     */
    static Stats measure(short[] cur, short[] last, short[] second, int width, int height, int threads) {
        var cols = width >> 4;
        var rows = height >> 4;
        var stats = new Stats();
        if (cols == 0 || rows == 0) {
            stats.intraError = 1;
            stats.codedError = 1;
            stats.srCodedError = 1;
            return stats;
        }
        var bands = Math.max(1, Math.min(threads, rows));
        var partial = new long[bands][];
        ParallelBands.run(bands, band -> {
            var acc = new long[14];
            var dsp = EncDsp.select();
            var lastMv = new int[2];
            var first = ParallelBands.start(band, bands, rows);
            var end = ParallelBands.start(band + 1, bands, rows);
            for (var r = first; r < end; r++) {
                lastMv[0] = 0;
                lastMv[1] = 0;
                for (var c = 0; c < cols; c++) {
                    block(dsp, cur, last, second, width, height, r, c, rows, cols, acc, lastMv);
                }
            }
            partial[band] = acc;
        });
        var sum = new long[14];
        for (var p : partial) {
            for (var i = 0; i < sum.length; i++) {
                sum[i] += p[i];
            }
        }
        var blocks = (double) rows * cols;
        var minErr = 200 * Math.sqrt(blocks);
        stats.intraError = (sum[0] >> 8) / blocks + minErr / blocks;
        stats.codedError = (sum[1] >> 8) / blocks + minErr / blocks;
        stats.srCodedError = (sum[2] >> 8) / blocks + minErr / blocks;
        stats.pcntInter = sum[3] / blocks;
        stats.pcntSecondRef = sum[4] / blocks;
        stats.pcntNeutral = sum[5] / 1024.0 / blocks;
        var mvCount = sum[6];
        if (mvCount > 0) {
            stats.mvr = (double) sum[7] / mvCount / height;
            stats.mvrAbs = (double) sum[8] / mvCount / height;
            stats.mvc = (double) sum[9] / mvCount / width;
            stats.mvcAbs = (double) sum[10] / mvCount / width;
            stats.mvInOutCount = (double) sum[11] / (mvCount * 2);
            stats.pcntMotion = mvCount / blocks;
        }
        return stats;
    }

    /**
     * Predicts one 16x16 block and adds its errors and counts to the accumulators.
     *
     * @param dsp the kernels
     * @param cur the frame's luma
     * @param last the previous frame's luma, or {@code null}
     * @param second the luma of the frame two back, or {@code null}
     * @param width the picture width, the stride of the planes
     * @param height the picture height
     * @param r the block row
     * @param c the block column
     * @param rows the number of block rows
     * @param cols the number of block columns
     * @param acc the accumulators: intra, coded and second reference errors, inter, second
     * reference and neutral (in 1/1024) counts, motion vector count, row sum,
     * absolute row sum, column sum, absolute column sum, in-out balance
     * @param lastMv the motion vector of the previous block of the row, updated
     */
    private static void block(EncDsp dsp, short[] cur, short[] last, short[] second, int width, int height, int r,
                              int c, int rows, int cols, long[] acc, int[] lastMv) {
        var x0 = c << 4;
        var y0 = r << 4;
        var off = y0 * width + x0;
        var intra = intraError(cur, width, x0, y0) + INTRA_MODE_PENALTY;
        acc[0] += intra;
        if (last == null) {
            acc[1] += intra;
            acc[2] += intra;
            return;
        }
        var bestRow = 0;
        var bestCol = 0;
        var best = sse(dsp, cur, off, last, off, width);
        if (best > 0) {
            var startRow = lastMv[0];
            var startCol = lastMv[1];
            if (startRow != 0 || startCol != 0) {
                var start = searchSse(dsp, cur, last, width, height, x0, y0, startRow, startCol);
                if (start < best) {
                    best = start;
                    bestRow = startRow;
                    bestCol = startCol;
                }
            }
            var step = 16;
            while (step >= 1) {
                var moved = false;
                for (var d = 0; d < 4; d++) {
                    var row = bestRow + (d == 0 ? -step : d == 1 ? step : 0);
                    var col = bestCol + (d == 2 ? -step : d == 3 ? step : 0);
                    if (Math.abs(row) > RANGE || Math.abs(col) > RANGE) {
                        continue;
                    }
                    var e = searchSse(dsp, cur, last, width, height, x0, y0, row, col);
                    if (e < best) {
                        best = e;
                        bestRow = row;
                        bestCol = col;
                        moved = true;
                    }
                }
                if (!moved) {
                    step >>= 1;
                }
            }
        }
        var motion = best;
        var srError = motion;
        if (second != null) {
            srError = sse(dsp, cur, off, second, off, width);
            if (srError < motion && srError < intra) {
                acc[4]++;
            }
            acc[2] += Math.min(srError, intra);
        } else {
            acc[2] += motion;
        }
        if (motion <= intra) {
            if ((intra - INTRA_MODE_PENALTY) * 9L <= motion * 10L && intra < 2 * INTRA_MODE_PENALTY) {
                acc[5] += 1024;
            } else if (intra > NCOUNT_INTRA_THRESH && intra < (long) NCOUNT_INTRA_FACTOR * motion) {
                acc[5] += (long) (1024.0 * motion / intra);
            }
            acc[1] += motion;
            acc[3]++;
            if (bestRow != 0 || bestCol != 0) {
                acc[6]++;
                acc[7] += bestRow;
                acc[8] += Math.abs(bestRow);
                acc[9] += bestCol;
                acc[10] += Math.abs(bestCol);
                acc[11] += inOut(bestRow, r, rows) + inOut(bestCol, c, cols);
            }
            lastMv[0] = bestRow;
            lastMv[1] = bestCol;
        } else {
            acc[1] += intra;
            lastMv[0] = 0;
            lastMv[1] = 0;
        }
    }

    /**
     * Returns whether a motion vector component points into the frame (+1), out of it (-1) or
     * neither (0), for a block in the first or second half of the rows or columns.
     *
     * @param mv the component
     * @param pos the block's row or column
     * @param count the number of block rows or columns
     * @return the balance contribution
     */
    private static int inOut(int mv, int pos, int count) {
        if (mv == 0 || pos == count / 2) {
            return 0;
        }
        var firstHalf = pos < count / 2;
        return (mv > 0) == firstHalf ? -1 : 1;
    }

    /**
     * Returns the squared error of the block at a full-sample vector, clamped so the block stays
     * inside the reference.
     *
     * @param dsp the kernels
     * @param cur the frame's luma
     * @param ref the reference luma
     * @param width the stride and width
     * @param height the picture height
     * @param x0 the block column in samples
     * @param y0 the block row in samples
     * @param row the vector row
     * @param col the vector column
     * @return the squared error, or {@link Integer#MAX_VALUE} outside the picture
     */
    private static int searchSse(EncDsp dsp, short[] cur, short[] ref, int width, int height, int x0, int y0,
                                 int row, int col) {
        var rx = x0 + col;
        var ry = y0 + row;
        if (rx < 0 || ry < 0 || rx + 16 > width || ry + 16 > height) {
            return Integer.MAX_VALUE;
        }
        return sse(dsp, cur, y0 * width + x0, ref, ry * width + rx, width);
    }

    /**
     * Returns the squared error between two 16x16 blocks.
     *
     * @param dsp the kernels
     * @param a the first plane
     * @param aOff the first block
     * @param b the second plane
     * @param bOff the second block
     * @param stride the stride of both planes
     * @return the squared error
     */
    private static int sse(EncDsp dsp, short[] a, int aOff, short[] b, int bOff, int stride) {
        return EncDsp.sse(dsp.sseSum(a, aOff, stride, b, bOff, stride, 16, 16));
    }

    /**
     * Returns the squared error of predicting each 4x4 block of a 16x16 block with the mean of
     * the four source samples above it and the four to its left, or 128 at the picture edges.
     *
     * @param src the luma
     * @param width the stride and width
     * @param x0 the block column in samples
     * @param y0 the block row in samples
     * @return the squared error
     */
    private static int intraError(short[] src, int width, int x0, int y0) {
        var err = 0;
        for (var by = y0; by < y0 + 16; by += 4) {
            for (var bx = x0; bx < x0 + 16; bx += 4) {
                var sum = 0;
                var n = 0;
                if (by > 0) {
                    var o = (by - 1) * width + bx;
                    sum += src[o] + src[o + 1] + src[o + 2] + src[o + 3];
                    n += 4;
                }
                if (bx > 0) {
                    var o = by * width + bx - 1;
                    sum += src[o] + src[o + width] + src[o + 2 * width] + src[o + 3 * width];
                    n += 4;
                }
                var dc = n == 0 ? 128 : (sum + (n >> 1)) / n;
                for (var y = by; y < by + 4; y++) {
                    var o = y * width + bx;
                    for (var x = 0; x < 4; x++) {
                        var d = src[o + x] - dc;
                        err += d * d;
                    }
                }
            }
        }
        return err;
    }
}
