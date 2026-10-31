/* Ports libvpx 1.15.1 (vp9/encoder/vp9_temporal_filter.c, vp9/encoder/vp9_mcomp.c). Copyright (c) 2010, The WebM
   Project authors; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.video.vp9.internal;

import com.github.auties00.smalt.internal.task.ParallelBands;

/**
 * Builds the source of a VP9 alternate reference: a weighted average of a lookahead picture and
 * its neighbors, each neighbor motion compensated block by block and weighted by how well it
 * matches, so that noise averages out while moving detail stays sharp.
 *
 * <p>The picture is filtered in 32x32 blocks. Each neighbor is searched for the block as a
 * whole and for its four 16x16 quarters; the better of the two predictions, built with a 12-tap
 * interpolation filter, weighs each sample by the local error around it, as a non-local mean
 * does. A 10 or 12-bit picture is searched on its 8-bit proxies and filtered at its own
 * precision. Rows of blocks are filtered on up to the given number of threads; the result does
 * not depend on the thread count. Instances are used by one thread at a time.
 *
 * @implNote This implementation ports libvpx's {@code vp9_temporal_filter},
 * {@code adjust_arnr_filter}, {@code vp9_temporal_filter_iterate_row_c},
 * {@code temporal_filter_find_matching_mb_c}, {@code temporal_filter_predictors_mb_c},
 * {@code vp9_apply_temporal_filter_c} and {@code vp9_highbd_apply_temporal_filter_c}; the
 * sub-sample refinement measures the bilinear sub-sample variance of each candidate where
 * libvpx predicts it with the 8-tap sharp filter.
 */
final class Vp9TemporalFilter {
    /**
     * The block size.
     */
    private static final int BW = 32;

    /**
     * The samples of a luma block.
     */
    private static final int BLK_PELS = BW * BW;

    /**
     * The interpolation margin the filter keeps from the border ({@code TF_INTERP_EXTEND}).
     */
    private static final int INTERP_EXTEND = 6;

    /**
     * The quantizer index of the motion search's cost weights ({@code ARNR_FILT_QINDEX}).
     */
    private static final int FILTER_QINDEX = 128;

    /**
     * The motion-compensated error below which a block weighs most ({@code thresh_low}).
     */
    private static final int THRESH_LOW = 10000;

    /**
     * The motion-compensated error below which a block still weighs ({@code thresh_high}).
     */
    private static final int THRESH_HIGH = 20000;

    /**
     * The 12-tap interpolation filters of each sixteenth-sample phase ({@code sub_pel_filters_12}).
     */
    private static final short[][] FILTERS_12 = {
            {0, 0, 0, 0, 0, 128, 0, 0, 0, 0, 0, 0},
            {0, 1, -2, 3, -7, 127, 8, -4, 2, -1, 1, 0},
            {-1, 2, -3, 6, -13, 124, 18, -8, 4, -2, 2, -1},
            {-1, 3, -4, 8, -18, 120, 28, -12, 7, -4, 2, -1},
            {-1, 3, -6, 10, -21, 115, 38, -15, 8, -5, 3, -1},
            {-2, 4, -6, 12, -24, 108, 49, -18, 10, -6, 3, -2},
            {-2, 4, -7, 13, -25, 100, 60, -21, 11, -7, 4, -2},
            {-2, 4, -7, 13, -26, 91, 71, -24, 13, -7, 4, -2},
            {-2, 4, -7, 13, -25, 81, 81, -25, 13, -7, 4, -2},
            {-2, 4, -7, 13, -24, 71, 91, -26, 13, -7, 4, -2},
            {-2, 4, -7, 11, -21, 60, 100, -25, 13, -7, 4, -2},
            {-2, 3, -6, 10, -18, 49, 108, -24, 12, -6, 4, -2},
            {-1, 3, -5, 8, -15, 38, 115, -21, 10, -6, 3, -1},
            {-1, 2, -4, 7, -12, 28, 120, -18, 8, -4, 3, -1},
            {-1, 2, -2, 4, -8, 18, 124, -13, 6, -3, 2, -1},
            {0, 1, -1, 2, -4, 8, 127, -7, 3, -2, 1, 0}
    };

    /**
     * The multipliers of the 8-bit weight of each neighbor count ({@code index_mult}).
     */
    private static final long[] INDEX_MULT = {0, 0, 0, 0, 49152, 39322, 32768, 28087, 24576, 21846, 19661, 17874, 0,
            15124};

    /**
     * The multipliers of the high bit depth weight of each neighbor count ({@code highbd_index_mult}).
     */
    private static final long[] HIGHBD_INDEX_MULT = {0L, 0L, 0L, 0L, 3221225472L, 2576980378L, 2147483648L,
            1840700270L, 1610612736L, 1431655766L, 1288490189L, 1171354718L, 0L, 991146300L};

    /**
     * The reciprocal of each weight sum, in 1/2^19 ({@code fixed_divide}).
     */
    private static final int[] FIXED_DIVIDE = fixedDivide();

    /**
     * The mesh search patterns of the whole block, range then interval ({@code good_quality_mesh_patterns}).
     */
    private static final int[] MESH = {64, 8, 28, 4, 15, 1, 7, 1};

    /**
     * The row offsets of the eight sites of a diamond step.
     */
    private static final int[] SITE_ROWS = {-1, 1, 0, 0, -1, -1, 1, 1};

    /**
     * The column offsets of the eight sites of a diamond step.
     */
    private static final int[] SITE_COLS = {0, 0, -1, 1, -1, 1, -1, 1};

    /**
     * The picture width.
     */
    private final int width;

    /**
     * The picture height.
     */
    private final int height;

    /**
     * The horizontal chroma subsampling shift.
     */
    private final int ssX;

    /**
     * The vertical chroma subsampling shift.
     */
    private final int ssY;

    /**
     * The bit depth.
     */
    private final int bitDepth;

    /**
     * The number of block columns of a picture.
     */
    private final int blockCols;

    /**
     * The number of block rows of a picture.
     */
    private final int blockRows;

    /**
     * The weight of a vector's cost in the sum of absolute differences.
     */
    private final int sadPerBit;

    /**
     * The filtered picture.
     */
    private final Vp9EncPicture output;

    /**
     * The pictures of the filtering in progress.
     */
    private Vp9EncPicture[] frames;

    /**
     * The number of pictures of the filtering in progress.
     */
    private int frameCount;

    /**
     * The index of the picture being filtered.
     */
    private int center;

    /**
     * The strength of the filtering in progress.
     */
    private int strength;

    /**
     * Whether the quarters are searched with the mesh search.
     */
    private boolean meshSubBlocks;

    /**
     * The scratch of each thread, created on first use.
     */
    private Worker[] workers = new Worker[0];

    /**
     * Creates a filter of a picture format.
     *
     * @param width the picture width
     * @param height the picture height
     * @param ssX the horizontal chroma subsampling shift
     * @param ssY the vertical chroma subsampling shift
     * @param bitDepth the bit depth
     */
    Vp9TemporalFilter(int width, int height, int ssX, int ssY, int bitDepth) {
        this.width = width;
        this.height = height;
        this.ssX = ssX;
        this.ssY = ssY;
        this.bitDepth = bitDepth;
        this.blockCols = (width + BW - 1) / BW;
        this.blockRows = (height + BW - 1) / BW;
        this.sadPerBit = Vp9RtEncoder.SAD_PER_BIT16[FILTER_QINDEX];
        this.output = new Vp9EncPicture(width, height, ssX, ssY, bitDepth);
    }

    /**
     * Builds the reciprocals of the weight sums.
     *
     * @return the table
     */
    private static int[] fixedDivide() {
        var table = new int[512];
        for (var i = 1; i < 512; i++) {
            table[i] = 0x80000 / i;
        }
        return table;
    }

    /**
     * Returns the number of pictures before and after the center the filter averages and its
     * strength for an alternate reference ({@code adjust_arnr_filter}).
     *
     * @param distance the alternate reference's distance from the next shown frame
     * @param lookahead the number of pictures in the lookahead
     * @param groupBoost the boost of the alternate reference's group
     * @param maxFrames the most pictures to average ({@code arnr_max_frames})
     * @param baseStrength the configured strength, from 0 to 6, with its two-pass adjustment
     * @param avgQ the quantizer step of recent frames
     * @return the pictures before the center in bits 0 to 7, those after it in bits 8 to 15, and
     * the strength in bits 16 to 23
     */
    static int plan(int distance, int lookahead, int groupBoost, int maxFrames, int baseStrength, double avgQ) {
        var maxFwd = Math.max(lookahead - distance - 1, 0);
        var maxBwd = Math.max(distance, 0);
        var frames = Math.max(maxFrames, 1);
        baseStrength = Math.clamp(baseStrength, 0, 6);
        var q = (int) avgQ;
        var strength = q > 16 ? baseStrength : Math.max(0, baseStrength - (16 - q) / 2);
        frames = Math.min(frames, groupBoost / 150);
        strength = Math.min(strength, groupBoost / 300);
        int backward;
        int forward;
        if (Math.min(maxFwd, maxBwd) >= frames / 2) {
            backward = frames / 2;
            forward = (frames - 1) / 2;
        } else if (maxFwd < frames / 2) {
            forward = maxFwd;
            backward = Math.min(frames - 1 - forward, maxBwd);
        } else {
            backward = maxBwd;
            forward = Math.min(frames - 1 - backward, maxFwd);
        }
        if (backward + 1 + forward <= 1) {
            backward = 0;
            forward = 0;
        }
        backward = Math.max(backward, 0);
        forward = Math.max(forward, 0);
        return backward | (forward << 8) | (Math.max(strength, 0) << 16);
    }

    /**
     * Filters a picture with its neighbors.
     *
     * @param pictures the pictures in display order, of the filter's format
     * @param count the number of pictures
     * @param centerIndex the index of the picture to filter
     * @param filterStrength the strength, from 0 to 6
     * @param meshQuarters whether the quarters are searched with the exhaustive mesh search,
     * as at the best quality, rather than with the diamond search of the good-quality speeds
     * @param threads the most threads to use
     * @return the filtered picture, owned by the filter and overwritten by the next filtering
     */
    Vp9EncPicture filter(Vp9EncPicture[] pictures, int count, int centerIndex, int filterStrength,
                         boolean meshQuarters, int threads) {
        this.frames = pictures;
        this.frameCount = count;
        this.center = centerIndex;
        this.strength = filterStrength;
        this.meshSubBlocks = meshQuarters;
        try {
            var bands = Math.max(1, Math.min(threads, blockRows));
            if (workers.length < bands) {
                var grown = java.util.Arrays.copyOf(workers, bands);
                for (var i = workers.length; i < bands; i++) {
                    grown[i] = new Worker();
                }
                workers = grown;
            }
            if (bands > 1) {
                ParallelBands.run(bands, band -> {
                    var worker = workers[band];
                    for (var r = ParallelBands.start(band, bands, blockRows);
                         r < ParallelBands.start(band + 1, bands, blockRows); r++) {
                        worker.row(r);
                    }
                });
            } else {
                for (var r = 0; r < blockRows; r++) {
                    workers[0].row(r);
                }
            }
            output.extendBorders();
            return output;
        } finally {
            this.frames = null;
        }
    }

    /**
     * The scratch of one thread of the filter.
     */
    private final class Worker {
        private final int[] accumulator = new int[BLK_PELS * 3];
        private final int[] count = new int[BLK_PELS * 3];
        private final int[] predictor = new int[BLK_PELS * 3];
        private final int[] temp = new int[BW * (BW + 11)];
        private final long[] yDiff = new long[BLK_PELS];
        private final long[] uDiff = new long[BLK_PELS];
        private final long[] vDiff = new long[BLK_PELS];
        private final int[] blockMvs = new int[4];
        private final int[] blockErrors = new int[4];
        private final int[] blockWeights = new int[4];
        private final short[] subpelScratch = new short[Vp9EncDsp.SUBPEL_SCRATCH];
        private int rowMin;
        private int rowMax;
        private int colMin;
        private int colMax;
        private int bestRow;
        private int bestCol;

        /**
         * Filters one row of blocks.
         *
         * @param blockRow the block row
         */
        void row(int blockRow) {
            var uvW = BW >> ssX;
            var uvH = BW >> ssY;
            rowMin = -((blockRow * BW) + (17 - 2 * INTERP_EXTEND));
            rowMax = ((blockRows - 1 - blockRow) * BW) + (17 - 2 * INTERP_EXTEND);
            var target = frames[center];
            for (var blockCol = 0; blockCol < blockCols; blockCol++) {
                java.util.Arrays.fill(accumulator, 0);
                java.util.Arrays.fill(count, 0);
                colMin = -((blockCol * BW) + (17 - 2 * INTERP_EXTEND));
                colMax = ((blockCols - 1 - blockCol) * BW) + (17 - 2 * INTERP_EXTEND);
                var x0 = blockCol * BW;
                var y0 = blockRow * BW;
                for (var f = 0; f < frameCount; f++) {
                    var use32 = false;
                    var refMv = 0;
                    if (f == center) {
                        java.util.Arrays.fill(blockWeights, 2);
                        java.util.Arrays.fill(blockMvs, 0);
                        use32 = true;
                    } else {
                        var err = findMatch(target, frames[f], x0, y0);
                        refMv = ModeInfo.packMv(bestRow, bestCol);
                        var err16 = (long) blockErrors[0] + blockErrors[1] + blockErrors[2] + blockErrors[3];
                        var maxErr = Integer.MIN_VALUE;
                        var minErr = Integer.MAX_VALUE;
                        for (var k = 0; k < 4; k++) {
                            minErr = Math.min(minErr, blockErrors[k]);
                            maxErr = Math.max(maxErr, blockErrors[k]);
                        }
                        if ((err * 15L < (err16 << 4) && (long) maxErr - minErr < 10000)
                                || (err * 14L < (err16 << 4) && (long) maxErr - minErr < 5000)) {
                            use32 = true;
                            var w = err < (THRESH_LOW << 2) ? 2 : err < (THRESH_HIGH << 2) ? 1 : 0;
                            java.util.Arrays.fill(blockWeights, w);
                        } else {
                            for (var k = 0; k < 4; k++) {
                                blockWeights[k] = blockErrors[k] < THRESH_LOW ? 2 : blockErrors[k] < THRESH_HIGH ? 1 : 0;
                            }
                        }
                        var distance = Math.abs(f - center);
                        for (var k = 0; k < 4; k++) {
                            if (distance == 1) {
                                blockWeights[k] = Math.min(blockWeights[k], 2);
                            } else if (distance == 2 || distance == 3) {
                                blockWeights[k] = Math.min(blockWeights[k], 1);
                            }
                        }
                    }
                    if ((blockWeights[0] | blockWeights[1] | blockWeights[2] | blockWeights[3]) != 0) {
                        predict(frames[f], x0, y0, uvW, uvH, refMv, use32);
                        apply(target, x0, y0, uvW, uvH, use32);
                    }
                }
                store(x0, y0, uvW, uvH);
            }
        }

        /**
         * Searches a neighbor for the block and its quarters, leaving the block's vector in
         * {@link #bestRow} and {@link #bestCol} and the quarters' in {@link #blockMvs} and
         * {@link #blockErrors} ({@code temporal_filter_find_matching_mb_c}).
         *
         * @param target the picture being filtered
         * @param ref the neighbor
         * @param x0 the block's column
         * @param y0 the block's row
         * @return the block's error
         */
        private int findMatch(Vp9EncPicture target, Vp9EncPicture ref, int x0, int y0) {
            var src = target.planes[0];
            var stride = target.strides[0];
            var off = target.offset(0, x0, y0);
            var refPlane = ref.planes[0];
            mesh(src, off, stride, refPlane, BW, 0, 0);
            var err = subpel(src, off, stride, refPlane, BW, bestRow, bestCol);
            var fullRow = bestRow >> 3;
            var fullCol = bestCol >> 3;
            var mvRow = bestRow;
            var mvCol = bestCol;
            for (var k = 0; k < 4; k++) {
                var subOff = off + (k >> 1) * 16 * stride + (k & 1) * 16;
                if (meshSubBlocks) {
                    mesh(src, subOff, stride, refPlane, 16, fullRow, fullCol);
                } else {
                    diamond(src, subOff, stride, refPlane, 16, fullRow, fullCol);
                }
                blockErrors[k] = subpel(src, subOff, stride, refPlane, 16, bestRow, bestCol);
                blockMvs[k] = ModeInfo.packMv(bestRow, bestCol);
            }
            bestRow = mvRow;
            bestCol = mvCol;
            return err;
        }

        /**
         * Runs the exhaustive mesh search around a full-sample vector, leaving the best vector in
         * eighth samples in {@link #bestRow} and {@link #bestCol} ({@code full_pixel_exhaustive}).
         *
         * @param src the source luma
         * @param off the index of the block
         * @param stride the row stride
         * @param ref the reference luma
         * @param size the block size
         * @param startRow the full-sample start row, also the cost center
         * @param startCol the full-sample start column
         */
        private void mesh(byte[] src, int off, int stride, byte[] ref, int size, int startRow, int startCol) {
            var range = MESH[0];
            var interval = MESH[1];
            var divisor = range / interval;
            range = Math.max(range, (5 * Math.max(Math.abs(startRow), Math.abs(startCol))) / 4);
            range = Math.min(range, 1 << 12);
            interval = Math.max(interval, range / divisor);
            var row = Math.clamp(startRow, rowMin, rowMax);
            var col = Math.clamp(startCol, colMin, colMax);
            var best = cost(src, off, stride, ref, size, row, col, startRow, startCol);
            for (var step = 0; step < 4; step++) {
                var r0 = row;
                var c0 = col;
                var rng = step == 0 ? range : MESH[step * 2];
                var inc = step == 0 ? interval : MESH[step * 2 + 1];
                for (var dr = Math.max(-rng, rowMin - r0); dr <= Math.min(rng, rowMax - r0); dr += inc) {
                    for (var dc = Math.max(-rng, colMin - c0); dc <= Math.min(rng, colMax - c0); dc += inc) {
                        var r = r0 + dr;
                        var c = c0 + dc;
                        var e = cost(src, off, stride, ref, size, r, c, startRow, startCol);
                        if (e < best) {
                            best = e;
                            row = r;
                            col = c;
                        }
                    }
                }
                if ((step > 0 && MESH[step * 2 + 1] == 1) || (step == 0 && (interval <= 1 || range <= 7))) {
                    break;
                }
            }
            bestRow = row * 8;
            bestCol = col * 8;
        }

        /**
         * Runs the eight-site diamond search from a full-sample vector at every scale, leaving
         * the best vector in eighth samples in {@link #bestRow} and {@link #bestCol}.
         *
         * @param src the source luma
         * @param off the index of the block
         * @param stride the row stride
         * @param ref the reference luma
         * @param size the block size
         * @param startRow the full-sample start row, also the cost center
         * @param startCol the full-sample start column
         */
        private void diamond(byte[] src, int off, int stride, byte[] ref, int size, int startRow, int startCol) {
            var row = Math.clamp(startRow, rowMin, rowMax);
            var col = Math.clamp(startCol, colMin, colMax);
            var best = cost(src, off, stride, ref, size, row, col, startRow, startCol);
            for (var step = 0; step < 11; step++) {
                var len = 1 << (10 - step);
                var site = -1;
                for (var j = 0; j < 8; j++) {
                    var r = row + SITE_ROWS[j] * len;
                    var c = col + SITE_COLS[j] * len;
                    if (r < rowMin || r > rowMax || c < colMin || c > colMax) {
                        continue;
                    }
                    var e = cost(src, off, stride, ref, size, r, c, startRow, startCol);
                    if (e < best) {
                        best = e;
                        site = j;
                    }
                }
                if (site >= 0) {
                    row += SITE_ROWS[site] * len;
                    col += SITE_COLS[site] * len;
                }
            }
            bestRow = row * 8;
            bestCol = col * 8;
        }

        /**
         * Returns the sum of absolute differences of a block at a full-sample vector plus the
         * vector's cost ({@code mvsad_err_cost}).
         */
        private int cost(byte[] src, int off, int stride, byte[] ref, int size, int row, int col, int centerRow,
                         int centerCol) {
            var sad = Vp9EncDsp.INSTANCE.sad(src, off, stride, ref, off + row * stride + col, stride, size, size);
            var dr = row - centerRow;
            var dc = col - centerCol;
            var mvCost = Vp9EncMv.JOINT_SAD_COST[Vp9EncMv.joint(dr, dc)] + Vp9EncMv.SAD_COST[Vp9EncMv.MV_MAX + dr]
                    + Vp9EncMv.SAD_COST[Vp9EncMv.MV_MAX + dc];
            return sad + (int) (((long) mvCost * sadPerBit + 256) >> 9);
        }

        /**
         * Refines a vector to eighth samples by halving steps around it, by the sub-sample
         * variance of the block, leaving it in {@link #bestRow} and {@link #bestCol}.
         *
         * @param src the source luma
         * @param off the index of the block
         * @param stride the row stride
         * @param ref the reference luma
         * @param size the block size
         * @param row the eighth-sample start row, a whole sample
         * @param col the eighth-sample start column
         * @return the variance of the best vector
         */
        private int subpel(byte[] src, int off, int stride, byte[] ref, int size, int row, int col) {
            var best = variance(src, off, stride, ref, size, row, col);
            for (var step = 4; step >= 1; step >>= 1) {
                var r0 = row;
                var c0 = col;
                for (var j = 0; j < 8; j++) {
                    var r = r0 + SITE_ROWS[j] * step;
                    var c = c0 + SITE_COLS[j] * step;
                    if ((r >> 3) < rowMin || (r >> 3) > rowMax || (c >> 3) < colMin || (c >> 3) > colMax) {
                        continue;
                    }
                    var e = variance(src, off, stride, ref, size, r, c);
                    if (e < best) {
                        best = e;
                        row = r;
                        col = c;
                    }
                }
            }
            bestRow = row;
            bestCol = col;
            return best;
        }

        /**
         * Returns the variance of a block at an eighth-sample vector, with bilinear sub-sample
         * interpolation.
         */
        private int variance(byte[] src, int off, int stride, byte[] ref, int size, int row, int col) {
            var refOff = off + (row >> 3) * stride + (col >> 3);
            var packed = Vp9EncDsp.INSTANCE.subPixelVariance(ref, refOff, stride, col & 7, row & 7, src, off, stride,
                    size, size, subpelScratch);
            var log2 = size == 32 ? 10 : 8;
            return (int) Math.min(Integer.MAX_VALUE, Vp9EncDsp.variance(packed, log2));
        }

        /**
         * Builds the 12-tap prediction of the block from a neighbor, whole or by quarters
         * ({@code temporal_filter_predictors_mb_c}).
         */
        private void predict(Vp9EncPicture ref, int x0, int y0, int uvW, int uvH, int mv, boolean use32) {
            var uvQ4 = uvW == (BW >> 1);
            for (var plane = 0; plane < 3; plane++) {
                var w = plane == 0 ? BW : uvW;
                var h = plane == 0 ? BW : uvH;
                var px = plane == 0 ? x0 : x0 >> ssX;
                var py = plane == 0 ? y0 : y0 >> ssY;
                var base = plane * BLK_PELS;
                var q4 = plane != 0 && uvQ4;
                if (use32) {
                    predictBlock(ref, plane, px, py, mv, q4, base, w, 0, 0, w, h);
                } else {
                    for (var k = 0; k < 4; k++) {
                        var ox = (k & 1) * (w >> 1);
                        var oy = (k >> 1) * (h >> 1);
                        predictBlock(ref, plane, px + ox, py + oy, blockMvs[k], q4, base, w, ox, oy, w >> 1, h >> 1);
                    }
                }
            }
        }

        /**
         * Predicts one rectangle of a plane with the 12-tap filter ({@code vp9_build_inter_predictor_12}).
         */
        private void predictBlock(Vp9EncPicture ref, int plane, int x, int y, int mv, boolean q4, int base, int stride,
                                  int ox, int oy, int w, int h) {
            var mvRow = mv >> 16;
            var mvCol = (short) mv;
            var rowQ4 = q4 ? mvRow : mvRow * 2;
            var colQ4 = q4 ? mvCol : mvCol * 2;
            var refStride = ref.strides[plane];
            var off = ref.offset(plane, x + (colQ4 >> 4), y + (rowQ4 >> 4));
            var fx = FILTERS_12[colQ4 & 15];
            var fy = FILTERS_12[rowQ4 & 15];
            var high = bitDepth > 8;
            var max = (1 << bitDepth) - 1;
            var rows = h + 11;
            var start = off - 5 * refStride - 5;
            for (var r = 0; r < rows; r++) {
                var s = start + r * refStride;
                for (var c = 0; c < w; c++) {
                    var sum = 0;
                    if (high) {
                        var p = ref.planes16[plane];
                        for (var k = 0; k < 12; k++) {
                            sum += p[s + c + k] * fx[k];
                        }
                    } else {
                        var p = ref.planes[plane];
                        for (var k = 0; k < 12; k++) {
                            sum += (p[s + c + k] & 0xFF) * fx[k];
                        }
                    }
                    temp[r * BW + c] = Math.clamp((sum + 64) >> 7, 0, max);
                }
            }
            for (var c = 0; c < w; c++) {
                for (var r = 0; r < h; r++) {
                    var sum = 0;
                    for (var k = 0; k < 12; k++) {
                        sum += temp[(r + k) * BW + c] * fy[k];
                    }
                    predictor[base + (oy + r) * stride + ox + c] = Math.clamp((sum + 64) >> 7, 0, max);
                }
            }
        }

        /**
         * Adds the weighted prediction of a neighbor to the accumulators ({@code vp9_apply_temporal_filter_c}
         * and its high bit depth twin).
         */
        private void apply(Vp9EncPicture target, int x0, int y0, int uvW, int uvH, boolean use32) {
            var high = bitDepth > 8;
            var adjStrength = high ? strength + 2 * (bitDepth - 8) : strength;
            var rounding = (1 << adjStrength) >> 1;
            var yStride = target.strides[0];
            var yOff = target.offset(0, x0, y0);
            var uvStride = target.strides[1];
            var uOff = target.offset(1, x0 >> ssX, y0 >> ssY);
            var vOff = target.offset(2, x0 >> ssX, y0 >> ssY);
            for (var i = 0; i < BW; i++) {
                for (var j = 0; j < BW; j++) {
                    long diff = sample(target, 0, yOff + i * yStride + j) - predictor[i * BW + j];
                    yDiff[i * BW + j] = diff * diff;
                }
            }
            for (var i = 0; i < uvH; i++) {
                for (var j = 0; j < uvW; j++) {
                    long du = sample(target, 1, uOff + i * uvStride + j) - predictor[BLK_PELS + i * uvW + j];
                    long dv = sample(target, 2, vOff + i * uvStride + j) - predictor[2 * BLK_PELS + i * uvW + j];
                    uDiff[i * uvW + j] = du * du;
                    vDiff[i * uvW + j] = dv * dv;
                }
            }
            for (var i = 0; i < BW; i++) {
                for (var j = 0; j < BW; j++) {
                    var weight = use32 ? blockWeights[0] : blockWeights[(i < BW / 2 ? 0 : 2) + (j < BW / 2 ? 0 : 1)];
                    var pixel = predictor[i * BW + j];
                    var uvR = i >> ssY;
                    var uvC = j >> ssX;
                    long modifier = 0;
                    var index = 0;
                    for (var dy = -1; dy <= 1; dy++) {
                        for (var dx = -1; dx <= 1; dx++) {
                            var r = i + dy;
                            var c = j + dx;
                            if (r >= 0 && r < BW && c >= 0 && c < BW) {
                                modifier += yDiff[r * BW + c];
                                index++;
                            }
                        }
                    }
                    modifier += uDiff[uvR * uvW + uvC] + vDiff[uvR * uvW + uvC];
                    index += 2;
                    var mod = modIndex(modifier, index, rounding, adjStrength, weight, high);
                    count[i * BW + j] += mod;
                    accumulator[i * BW + j] += mod * pixel;
                    if ((i & ssY) == 0 && (j & ssX) == 0) {
                        long uMod = 0;
                        long vMod = 0;
                        var crIndex = 0;
                        for (var dy = -1; dy <= 1; dy++) {
                            for (var dx = -1; dx <= 1; dx++) {
                                var r = uvR + dy;
                                var c = uvC + dx;
                                if (r >= 0 && r < uvH && c >= 0 && c < uvW) {
                                    uMod += uDiff[r * uvW + c];
                                    vMod += vDiff[r * uvW + c];
                                    crIndex++;
                                }
                            }
                        }
                        long ySum = 0;
                        for (var dy = 0; dy < 1 + ssY; dy++) {
                            for (var dx = 0; dx < 1 + ssX; dx++) {
                                ySum += yDiff[((uvR << ssY) + dy) * BW + (uvC << ssX) + dx];
                                crIndex++;
                            }
                        }
                        var u = modIndex(uMod + ySum, crIndex, rounding, adjStrength, weight, high);
                        var v = modIndex(vMod + ySum, crIndex, rounding, adjStrength, weight, high);
                        var m = uvR * uvW + uvC;
                        count[BLK_PELS + m] += u;
                        accumulator[BLK_PELS + m] += u * predictor[BLK_PELS + m];
                        count[2 * BLK_PELS + m] += v;
                        accumulator[2 * BLK_PELS + m] += v * predictor[2 * BLK_PELS + m];
                    }
                }
            }
        }

        /**
         * Returns a sample of a picture at its own precision.
         */
        private int sample(Vp9EncPicture picture, int plane, int index) {
            return bitDepth > 8 ? picture.planes16[plane][index] : picture.planes[plane][index] & 0xFF;
        }

        /**
         * Writes the normalized averages of the block into the output.
         */
        private void store(int x0, int y0, int uvW, int uvH) {
            var high = bitDepth > 8;
            for (var plane = 0; plane < 3; plane++) {
                var w = plane == 0 ? BW : uvW;
                var h = plane == 0 ? BW : uvH;
                var px = plane == 0 ? x0 : x0 >> ssX;
                var py = plane == 0 ? y0 : y0 >> ssY;
                var stride = output.strides[plane];
                var base = plane * BLK_PELS;
                var cw = Math.min(w, output.cropWidths[plane] - px);
                var ch = Math.min(h, output.cropHeights[plane] - py);
                for (var r = 0; r < ch; r++) {
                    var dst = output.offset(plane, px, py + r);
                    for (var c = 0; c < cw; c++) {
                        var k = base + r * w + c;
                        var pval = (int) (((long) accumulator[k] + (count[k] >> 1)) * FIXED_DIVIDE[count[k]] >> 19);
                        if (high) {
                            output.planes16[plane][dst + c] = (short) pval;
                        } else {
                            output.planes[plane][dst + c] = (byte) pval;
                        }
                    }
                }
            }
        }
    }

    /**
     * Returns the weight of a sample from the local error around it ({@code mod_index} and
     * {@code highbd_mod_index}).
     *
     * @param sumDist the sum of the squared differences around the sample
     * @param index the number of differences summed
     * @param rounding the rounding of the strength shift
     * @param strength the strength shift
     * @param weight the block's weight, from 0 to 2
     * @param high whether the samples are 10 or 12-bit
     * @return the weight, from 0 to 32
     */
    private static int modIndex(long sumDist, int index, int rounding, int strength, int weight, boolean high) {
        int mod;
        if (high) {
            mod = (int) ((Math.clamp(sumDist, 0, Integer.MAX_VALUE) * HIGHBD_INDEX_MULT[index]) >>> 32);
        } else {
            mod = (int) ((Math.clamp(sumDist, 0, 65535) * INDEX_MULT[index]) >> 16);
        }
        mod += rounding;
        mod >>= strength;
        mod = Math.min(16, mod);
        return (16 - mod) * weight;
    }
}
