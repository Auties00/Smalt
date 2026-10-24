package com.github.auties00.smalt.video.av1.internal.encoder;

import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.video.av1.internal.McDsp;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * Estimates how much of each frame of a golden frame group later frames predict from: the temporal
 * dependency model that sets the quantizers of key frames and alternate references.
 *
 * <p>Every 16x16 luma block of every frame is predicted intra (DC, vertical and horizontal from the
 * source samples around it) and from each of the frame's references (a full-sample n-step search
 * from the better of zero motion and the left block's vector, refined to half samples by the
 * variance of the bilinear prediction, then predicted with the regular eight-tap filter), and both
 * predictions are costed by the sum of absolute Hadamard coefficients of their residual. Then,
 * from the last coded frame to the first, every block passes to the blocks of its best reference
 * that its full-sample motion-compensated block overlaps, in proportion to the overlap, the share of
 * its own cost and of what it inherited that the reference spares it:
 * {@code (intra + inherited) * (1 - inter / intra)}. A frame's importance is the geometric mean
 * over its blocks of their own cost plus what they inherited over their own cost, weighted by
 * their own cost, at least 1; the quantizer step of an important frame is the leaf step divided by
 * the square root of its importance.
 *
 * <p>A picture of at least {@link #DOWNSCALE_SAMPLES} luma samples is modeled at half its width
 * and height, each sample averaging a 2x2 square, so a block covers 32x32 samples of the picture,
 * as larger pictures need for blocks that predict with a representative motion.
 *
 * @implNote This implementation follows the structure of libaom's temporal dependency model
 * ({@code av1/encoder/tpl_model.c}: {@code mode_estimation}, {@code tpl_model_update_b}
 * with {@code av1_get_overlap_area}, {@code get_frame_importance},
 * {@code av1_tpl_get_qstep_ratio} and {@code av1_get_q_index_from_qstep_ratio}); its
 * propagation takes the form of libaom's earlier releases, which propagate costs rather
 * than rate and distortion differences, on source pictures rather than
 * reconstructions, with a lighter motion search than libaom's full search of every
 * block.
 */
final class TplModel {
    /**
     * The number of luma samples from which a picture is modeled at half its size.
     */
    static final long DOWNSCALE_SAMPLES = 500_000L;

    /**
     * The block size.
     */
    private static final int BLOCK = 16;

    /**
     * The half-sample step of the sub-sample refinement, in eighths of a sample.
     */
    private static final int HALF = 4;

    /**
     * The n-step stages of the motion search: radii up to 12, around the better of zero motion and
     * the block to the left's vector.
     */
    private static final int STAGES = 6;

    /**
     * The radius of each n-step stage, growing by half from 1.
     */
    private static final int[] RADII = radii();

    /**
     * The row offset of each of the eight sites of a search stage, in units of its radius.
     */
    private static final int[] SITE_ROW = {-1, 1, 0, 0, -1, 1, -1, 1};

    /**
     * The column offset of each of the eight sites of a search stage, in units of its radius.
     */
    private static final int[] SITE_COL = {0, 0, -1, 1, -1, 1, 1, -1};

    /**
     * One frame of the model.
     */
    static final class Frame {
        /**
         * The picture the frame is coded from.
         */
        final Picture picture;

        /**
         * The model positions of the frame's references, duplicates removed.
         */
        final int[] refs;

        /**
         * Whether the frame is coded in the group, so its importance is measured; reference-only
         * frames of earlier groups are searched but not measured.
         */
        final boolean coded;

        /**
         * The widened luma plane, while the model runs.
         */
        short[] luma;

        /**
         * The intra cost of each block.
         */
        long[] intraCost;

        /**
         * The best prediction cost of each block, at most its intra cost.
         */
        long[] interCost;

        /**
         * The model position of each block's best reference, or -1 for intra.
         */
        int[] bestRef;

        /**
         * The full-sample motion vector row of each block.
         */
        int[] mvRow;

        /**
         * The full-sample motion vector column of each block.
         */
        int[] mvCol;

        /**
         * The cost each block inherits from the frames that predict from it.
         */
        double[] inherited;

        /**
         * The importance: the weighted geometric mean of each block's own cost plus inherited
         * cost over its own cost.
         */
        double importance = 1.0;

        /**
         * Creates a frame of the model.
         *
         * @param picture the source picture
         * @param refs the model positions of its references
         * @param coded whether the frame is coded in the group
         */
        Frame(Picture picture, int[] refs, boolean coded) {
            this.picture = picture;
            this.refs = refs;
            this.coded = coded;
        }
    }

    /**
     * Returns the radius of each n-step stage.
     *
     * @return the radii
     */
    private static int[] radii() {
        var out = new int[STAGES];
        var radius = 1;
        for (var i = 0; i < STAGES; i++) {
            out[i] = radius;
            radius = (int) Math.max(radius * 1.5 + 0.5, radius + 1);
        }
        return out;
    }

    /**
     * Averages each 2x2 square of a luma plane into one sample.
     *
     * @param luma the plane, of stride {@code stride}
     * @param stride the plane's stride
     * @param width the width of the result
     * @param height the height of the result
     * @return the plane of the averages, of stride {@code width}
     */
    private static short[] halve(short[] luma, int stride, int width, int height) {
        var out = new short[width * height];
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var o = 2 * y * stride + 2 * x;
                out[y * width + x] = (short) ((luma[o] + luma[o + 1] + luma[o + stride] + luma[o + stride + 1] + 2) >> 2);
            }
        }
        return out;
    }

    /**
     * Prevents instantiation.
     */
    private TplModel() {
        throw new AssertionError();
    }

    /**
     * Runs the model on frames in coding order, setting each coded frame's importance.
     *
     * @param frames the frames, references before the frames predicting from them
     * @param pictureWidth the picture width
     * @param pictureHeight the picture height
     * @param threads the most threads to use
     */
    static void run(Frame[] frames, int pictureWidth, int pictureHeight, int threads) {
        var half = (long) pictureWidth * pictureHeight >= DOWNSCALE_SAMPLES;
        var width = half ? pictureWidth >> 1 : pictureWidth;
        var height = half ? pictureHeight >> 1 : pictureHeight;
        var cols = width / BLOCK;
        var rows = height / BLOCK;
        if (cols == 0 || rows == 0) {
            return;
        }
        for (var frame : frames) {
            frame.luma = half ? halve(FirstPass.luma(frame.picture), pictureWidth, width, height)
                    : FirstPass.luma(frame.picture);
        }
        var n = cols * rows;
        var coded = new ArrayList<Frame>();
        for (var frame : frames) {
            if (!frame.coded) {
                continue;
            }
            frame.intraCost = new long[n];
            frame.interCost = new long[n];
            frame.bestRef = new int[n];
            frame.mvRow = new int[n];
            frame.mvCol = new int[n];
            frame.inherited = new double[n];
            coded.add(frame);
        }
        var bands = Math.max(1, Math.min(threads, rows));
        ParallelBands.run(coded.size() * bands, task -> {
            var frame = coded.get(task / bands);
            var band = task % bands;
            var worker = new Worker(frames, frame, width, height, cols);
            var end = ParallelBands.start(band + 1, bands, rows);
            for (var r = ParallelBands.start(band, bands, rows); r < end; r++) {
                for (var c = 0; c < cols; c++) {
                    worker.block(r, c);
                }
            }
        });
        for (var i = frames.length - 1; i >= 0; i--) {
            var frame = frames[i];
            if (!frame.coded) {
                continue;
            }
            propagate(frames, frame, cols, rows);
        }
        for (var frame : frames) {
            if (frame.coded) {
                frame.importance = importance(frame, n);
            }
            frame.luma = null;
        }
    }

    /**
     * Returns a frame's importance: the geometric mean over its blocks of the ratio of the block's
     * own cost plus the cost it passes on to the block's own cost, weighted by the block's own
     * cost, where a block's own cost is the cheaper of its intra and inter costs.
     * {@snippet :
     * importance = exp(sum(own * log((own + inherited) / own)) / (1 + sum(own)))
     * }
     *
     * @param frame the frame of the model
     * @param n the number of blocks
     * @return the importance, at least 1
     */
    private static double importance(Frame frame, int n) {
        var weights = 1.0;
        var sum = 0.0;
        for (var b = 0; b < n; b++) {
            var own = (double) Math.max(1, Math.min(frame.intraCost[b], frame.interCost[b]));
            sum += own * StrictMath.log((own + frame.inherited[b]) / own);
            weights += own;
        }
        return Math.max(1.0, StrictMath.exp(sum / weights));
    }

    /**
     * Passes each block's share of its cost to the blocks of its best reference its prediction
     * overlaps ({@code tpl_model_update_b}).
     *
     * @param frames the frames of the model, in coding order
     * @param frame the frame whose blocks pass their costs
     * @param cols the blocks per row
     * @param rows the block rows
     */
    private static void propagate(Frame[] frames, Frame frame, int cols, int rows) {
        for (var r = 0; r < rows; r++) {
            for (var c = 0; c < cols; c++) {
                var b = r * cols + c;
                var refIndex = frame.bestRef[b];
                if (refIndex < 0) {
                    continue;
                }
                var ref = frames[refIndex];
                if (!ref.coded) {
                    continue;
                }
                var intra = frame.intraCost[b];
                var inter = frame.interCost[b];
                if (intra <= 0) {
                    continue;
                }
                var amount = (intra + frame.inherited[b]) * (1.0 - (double) inter / intra);
                var py = r * BLOCK + frame.mvRow[b];
                var px = c * BLOCK + frame.mvCol[b];
                var gy = Math.floorDiv(py, BLOCK);
                var gx = Math.floorDiv(px, BLOCK);
                for (var k = 0; k < 4; k++) {
                    var by = gy + (k >> 1);
                    var bx = gx + (k & 1);
                    if (by < 0 || by >= rows || bx < 0 || bx >= cols) {
                        continue;
                    }
                    var overlapH = BLOCK - Math.abs(py - by * BLOCK);
                    var overlapW = BLOCK - Math.abs(px - bx * BLOCK);
                    if (overlapH <= 0 || overlapW <= 0) {
                        continue;
                    }
                    ref.inherited[by * cols + bx] += amount * overlapH * overlapW / (BLOCK * BLOCK);
                }
            }
        }
    }

    /**
     * Returns the quantizer index whose DC step is a leaf quantizer's step scaled by the inverse
     * square root of an importance ({@code av1_get_q_index_from_qstep_ratio}).
     *
     * @param leaf the leaf quantizer index
     * @param importance the frame's importance, at least 1
     * @return the quantizer index
     */
    static int qindex(int leaf, double importance) {
        var ratio = Math.sqrt(1.0 / Math.max(importance, 1.0));
        var target = Quantizer.dcQuant(leaf, 0) * ratio;
        var q = leaf;
        while (q > 0 && Quantizer.dcQuant(q, 0) > target) {
            q--;
        }
        return q;
    }

    /**
     * Costs the blocks of one frame.
     */
    private static final class Worker {
        /**
         * The frames.
         */
        private final Frame[] frames;

        /**
         * The frame being costed.
         */
        private final Frame frame;

        /**
         * The picture width.
         */
        private final int width;

        /**
         * The picture height.
         */
        private final int height;

        /**
         * The blocks per row.
         */
        private final int cols;

        /**
         * The kernels.
         */
        private final EncDsp dsp = EncDsp.select();

        /**
         * The prediction of a block.
         */
        private final short[] pred = new short[BLOCK * BLOCK];

        /**
         * The motion compensation kernels.
         */
        private final McDsp mc = McDsp.select();

        /**
         * The bilinear scratch of the sub-sample estimates.
         */
        private final short[] bilinear = new short[(BLOCK + 1) * BLOCK];

        /**
         * The full-sample vector row the block to the left found for each reference.
         */
        private final int[] leftRow = new int[8];

        /**
         * The full-sample vector column the block to the left found for each reference.
         */
        private final int[] leftCol = new int[8];

        /**
         * The motion compensation scratch.
         */
        private final short[] mcScratch = new short[McDsp.SCRATCH_SHORTS];

        /**
         * The residual of a block.
         */
        private final int[] diff = new int[BLOCK * BLOCK];

        /**
         * The Hadamard coefficients.
         */
        private final int[] coeff = new int[BLOCK * BLOCK];

        /**
         * The Hadamard scratch.
         */
        private final int[] scratch = new int[64];

        /**
         * Creates a worker.
         *
         * @param frames the frames of the model, in coding order
         * @param frame the frame being costed
         * @param width the picture width
         * @param height the picture height
         * @param cols the blocks per row
         */
        Worker(Frame[] frames, Frame frame, int width, int height, int cols) {
            this.frames = frames;
            this.frame = frame;
            this.width = width;
            this.height = height;
            this.cols = cols;
        }

        /**
         * Returns the SAD of a block against a reference position, over every other row.
         *
         * @param src the source plane
         * @param srcOff the source block offset
         * @param ref the reference plane
         * @param refOff the reference block offset
         * @return the SAD scaled to the whole block
         */
        private int sad(short[] src, int srcOff, short[] ref, int refOff) {
            return 2 * dsp.sad(src, srcOff, 2 * width, ref, refOff, 2 * width, BLOCK, BLOCK >> 1);
        }

        /**
         * Costs one block intra and against each reference ({@code mode_estimation}).
         *
         * @param r the block row
         * @param c the block column
         */
        void block(int r, int c) {
            var b = r * cols + c;
            var src = frame.luma;
            var x0 = c * BLOCK;
            var y0 = r * BLOCK;
            var off = y0 * width + x0;
            var intra = intraCost(src, x0, y0);
            var best = intra;
            var bestRef = -1;
            var bestRow = 0;
            var bestCol = 0;
            for (var k = 0; k < frame.refs.length; k++) {
                var refIndex = frame.refs[k];
                var ref = frames[refIndex].luma;
                var row = 0;
                var col = 0;
                var bestSad = sad(src, off, ref, off);
                if (c > 0 && (leftRow[k] != 0 || leftCol[k] != 0) && inside(x0, y0, leftRow[k], leftCol[k], 0)) {
                    var sad = sad(src, off, ref, off + leftRow[k] * width + leftCol[k]);
                    if (sad < bestSad) {
                        bestSad = sad;
                        row = leftRow[k];
                        col = leftCol[k];
                    }
                }
                for (var st = STAGES - 1; st >= 0; st--) {
                    var rad = RADII[st];
                    var moved = -1;
                    for (var d = 0; d < 8; d++) {
                        var nr = row + SITE_ROW[d] * rad;
                        var nc = col + SITE_COL[d] * rad;
                        if (!inside(x0, y0, nr, nc, 0)) {
                            continue;
                        }
                        var sad = sad(src, off, ref, off + nr * width + nc);
                        if (sad < bestSad) {
                            bestSad = sad;
                            moved = d;
                        }
                    }
                    if (moved >= 0) {
                        row += SITE_ROW[moved] * rad;
                        col += SITE_COL[moved] * rad;
                    }
                }
                leftRow[k] = row;
                leftCol[k] = col;
                var subRow = 0;
                var subCol = 0;
                if (inside(x0, y0, row, col, 5)) {
                    var bestVar = EncDsp.variance(dsp.sseSum(src, off, width, ref, off + row * width + col, width, BLOCK,
                            BLOCK), 8);
                    for (var d = 0; d < 8; d++) {
                        var mvr = row * 8 + SITE_ROW[d] * HALF;
                        var mvc = col * 8 + SITE_COL[d] * HALF;
                        var v = EncDsp.variance(dsp.subpelSseSum(ref, off + (mvr >> 3) * width + (mvc >> 3), width,
                                mvc & 7, mvr & 7, src, off, width, BLOCK, BLOCK, bilinear), 8);
                        if (v < bestVar) {
                            bestVar = v;
                            subRow = SITE_ROW[d] * HALF;
                            subCol = SITE_COL[d] * HALF;
                        }
                    }
                }
                long cost;
                if (subRow != 0 || subCol != 0) {
                    var mvr = row * 8 + subRow;
                    var mvc = col * 8 + subCol;
                    mc.mc(McDsp.FILTER_2D_8TAP_REGULAR, pred, 0, BLOCK, ref, off + (mvr >> 3) * width + (mvc >> 3),
                            width, BLOCK, BLOCK, (mvc & 7) << 1, (mvr & 7) << 1, mcScratch, 255);
                    dsp.subtract(src, off, width, pred, 0, BLOCK, diff, BLOCK, BLOCK);
                } else {
                    dsp.subtract(src, off, width, ref, off + row * width + col, width, diff, BLOCK, BLOCK);
                }
                dsp.hadamard16x16(diff, 0, BLOCK, coeff, 0, scratch);
                cost = dsp.satd(coeff, BLOCK * BLOCK);
                if (cost < best) {
                    best = cost;
                    bestRef = refIndex;
                    bestRow = row;
                    bestCol = col;
                }
            }
            frame.intraCost[b] = Math.max(1, intra);
            frame.interCost[b] = Math.max(1, best);
            frame.bestRef[b] = bestRef;
            frame.mvRow[b] = bestRow;
            frame.mvCol[b] = bestCol;
        }

        /**
         * Returns whether a block moved by a full-sample vector, with a margin, stays inside the
         * picture.
         *
         * @param x0 the block column in samples
         * @param y0 the block row in samples
         * @param row the vector row
         * @param col the vector column
         * @param margin the margin in samples
         * @return {@code true} when inside
         */
        private boolean inside(int x0, int y0, int row, int col, int margin) {
            return y0 + row >= margin && x0 + col >= margin && y0 + row + BLOCK + margin <= height
                    && x0 + col + BLOCK + margin <= width;
        }

        /**
         * Returns the smallest Hadamard cost of the DC, vertical and horizontal predictions of a
         * block from the source samples above it and to its left.
         *
         * @param src the frame's luma
         * @param x0 the block column in samples
         * @param y0 the block row in samples
         * @return the cost
         */
        private long intraCost(short[] src, int x0, int y0) {
            var sum = 0;
            var n = 0;
            if (y0 > 0) {
                for (var x = 0; x < BLOCK; x++) {
                    sum += src[(y0 - 1) * width + x0 + x];
                }
                n += BLOCK;
            }
            if (x0 > 0) {
                for (var y = 0; y < BLOCK; y++) {
                    sum += src[(y0 + y) * width + x0 - 1];
                }
                n += BLOCK;
            }
            var dc = n == 0 ? 128 : (sum + (n >> 1)) / n;
            Arrays.fill(pred, (short) dc);
            var best = cost(src, x0, y0);
            if (y0 > 0) {
                for (var y = 0; y < BLOCK; y++) {
                    System.arraycopy(src, (y0 - 1) * width + x0, pred, y * BLOCK, BLOCK);
                }
                best = Math.min(best, cost(src, x0, y0));
            }
            if (x0 > 0) {
                for (var y = 0; y < BLOCK; y++) {
                    Arrays.fill(pred, y * BLOCK, (y + 1) * BLOCK, src[(y0 + y) * width + x0 - 1]);
                }
                best = Math.min(best, cost(src, x0, y0));
            }
            return best;
        }

        /**
         * Returns the Hadamard cost of the residual of a block against {@link #pred}.
         *
         * @param src the frame's luma
         * @param x0 the block column in samples
         * @param y0 the block row in samples
         * @return the cost
         */
        private long cost(short[] src, int x0, int y0) {
            dsp.subtract(src, y0 * width + x0, width, pred, 0, BLOCK, diff, BLOCK, BLOCK);
            dsp.hadamard16x16(diff, 0, BLOCK, coeff, 0, scratch);
            return dsp.satd(coeff, BLOCK * BLOCK);
        }
    }
}
