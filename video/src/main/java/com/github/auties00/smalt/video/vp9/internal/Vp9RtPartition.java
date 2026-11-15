/* Ports libvpx 1.17.0 (vp9/encoder/vp9_encodeframe.c). Copyright (c) 2010, The WebM Project authors; BSD 3-Clause
   License. Modified for Smalt. */
package com.github.auties00.smalt.video.vp9.internal;

import java.util.Arrays;

import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.BILINEAR;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.BLOCK_16X16;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.BLOCK_32X32;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.BLOCK_32X64;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.BLOCK_4X4;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.BLOCK_64X32;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.BLOCK_64X64;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.BLOCK_8X8;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.GOLDEN_FRAME;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.LAST_FRAME;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.NO_REF_FRAME;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.PARTITION_HORZ;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.PARTITION_NONE;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.PARTITION_SPLIT;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.PARTITION_VERT;

/**
 * Chooses the partition of each superblock of the real-time VP9 encoder from the variance of
 * the source against a cheap prediction.
 *
 * <p>The superblock is compared with the previous frame displaced by a motion vector found from
 * integral projections, or with a flat gray picture on key frames, on 8x8 (or 4x4) averages.
 * The variances of the 64x64, 32x32 and 16x16 regions, and of their halves, are compared with
 * thresholds that grow with the quantizer: the largest region whose variance stays below its
 * threshold becomes a block. The chosen block sizes are written into the mode information grid,
 * where the coder reads them.
 *
 * @implNote This implementation ports {@code choose_partitioning} and its helpers
 * ({@code set_vt_partitioning}, {@code set_vbp_thresholds},
 * {@code vp9_set_variance_partition_thresholds}, {@code fill_variance_8x8avg},
 * {@code fill_variance_4x4avg}, {@code compute_minmax_8x8}, {@code chroma_check},
 * {@code set_low_temp_var_flag}, {@code copy_partitioning},
 * {@code update_prev_partition}) from libvpx's {@code vp9/encoder/vp9_encodeframe.c},
 * BSD-3. Spatial layers, screen content and the denoiser are not supported, so their
 * branches are absent. The low temporal variance test reads the motion vector of the
 * superblock's own prediction where libvpx reads the mode information of the last block
 * it sized.
 */
final class Vp9RtPartition {
    /**
     * The index of the first horizontal-half variance of a node.
     */
    private static final int HORZ = 1;

    /**
     * The index of the first vertical-half variance of a node.
     */
    private static final int VERT = 3;

    /**
     * The coder owning this partitioner.
     */
    private final Vp9RtCoder coder;

    /**
     * The tree of the superblock: levels 64x64, 32x32, 16x16, 8x8 and 4x4.
     */
    private final VarianceTree tree = new VarianceTree(true);

    /**
     * The secondary tree of 16x16 regions measured on 4x4 averages in inter frames.
     */
    private final VarianceTree tree2 = new VarianceTree(false);

    /**
     * Whether each region is forced to split: 0 the superblock, 1 to 4 the 32x32 regions, 5 to
     * 20 the 16x16 regions.
     */
    private final boolean[] forceSplit = new boolean[21];

    /**
     * Whether each 16x16 region was measured on 4x4 averages.
     */
    private final boolean[] variance4x4 = new boolean[16];

    /**
     * The sum of the 16x16 variances of each 32x32 region.
     */
    private final int[] avg16 = new int[4];

    /**
     * The largest 16x16 variance of each 32x32 region.
     */
    private final int[] maxVar16 = new int[4];

    /**
     * The smallest 16x16 variance of each 32x32 region.
     */
    private final int[] minVar16 = new int[4];

    /**
     * The split thresholds of the superblock: 64x64, 32x32, 16x16, 8x8.
     */
    private final long[] thresholds = new long[4];

    /**
     * The luma prediction of the superblock, 64x64.
     */
    final byte[] predY = new byte[64 * 64];

    /**
     * The chroma predictions of the superblock, 32x32 each.
     */
    final byte[][] predUv = {new byte[64 * 64], new byte[64 * 64]};

    /**
     * The 8x8 averages of a 16x16 source block, from {@link Vp9EncDsp#avg8x8Quad}.
     */
    private final int[] sourceAverages = new int[4];

    /**
     * The 8x8 averages of a 16x16 prediction block, from {@link Vp9EncDsp#avg8x8Quad}.
     */
    private final int[] predictionAverages = new int[4];

    /**
     * Creates a partitioner.
     *
     * @param coder the superblock coder whose blocks are partitioned
     */
    Vp9RtPartition(Vp9RtCoder coder) {
        this.coder = coder;
    }

    /**
     * Holds the variance tree of a superblock as flat arrays, five variances per node (the
     * region, its horizontal halves and its vertical halves).
     */
    private static final class VarianceTree {
        /**
         * The sums of squares of each level.
         */
        final long[][] sse = new long[5][];

        /**
         * The sums of each level.
         */
        final int[][] sum = new int[5][];

        /**
         * The log2 of the sample counts of each level.
         */
        final int[][] log2 = new int[5][];

        /**
         * The variances of each level.
         */
        final int[][] variance = new int[5][];

        /**
         * Creates a tree.
         *
         * @param full whether the levels above 16x16 exist
         */
        VarianceTree(boolean full) {
            for (var level = 0; level < 5; level++) {
                if (!full && level < 2) {
                    continue;
                }
                var nodes = 1 << (2 * level);
                sse[level] = new long[nodes * 5];
                sum[level] = new int[nodes * 5];
                log2[level] = new int[nodes * 5];
                variance[level] = new int[nodes * 5];
            }
        }

        /**
         * Sets a variance's sums ({@code fill_variance}).
         *
         * @param level the tree level, 0 for 8x8 nodes up to 3 for the 64x64 root
         * @param index the variance index
         * @param s2 the sum of squares
         * @param s the sum
         * @param c the log2 of the sample count
         */
        void fill(int level, int index, long s2, int s, int c) {
            sse[level][index] = s2;
            sum[level][index] = s;
            log2[level][index] = c;
        }

        /**
         * Computes a variance from its sums ({@code get_variance}).
         *
         * @param level the tree level, 0 for 8x8 nodes up to 3 for the 64x64 root
         * @param index the variance index
         * @return the variance
         */
        int compute(int level, int index) {
            var c = log2[level][index];
            var s = (long) sum[level][index];
            var v = (int) ((256 * (sse[level][index] - ((s * s) >> c))) >> c);
            variance[level][index] = v;
            return v;
        }

        /**
         * Sums the four children of a node into its region and halves ({@code fill_variance_tree}).
         *
         * @param level the node's level
         * @param node the index of the node within its level
         */
        void fillNode(int level, int node) {
            var cl = level + 1;
            var c0 = (node * 4) * 5;
            var base = node * 5;
            sum2(cl, c0, c0 + 5, level, base + HORZ);
            sum2(cl, c0 + 10, c0 + 15, level, base + HORZ + 1);
            sum2(cl, c0, c0 + 10, level, base + VERT);
            sum2(cl, c0 + 5, c0 + 15, level, base + VERT + 1);
            sse[level][base] = sse[level][base + VERT] + sse[level][base + VERT + 1];
            sum[level][base] = sum[level][base + VERT] + sum[level][base + VERT + 1];
            log2[level][base] = log2[level][base + VERT] + 1;
        }

        /**
         * Sums two child variances into a variance of the level above ({@code sum_2_variances}).
         *
         * @param cl the children's level
         * @param a the first child variance
         * @param b the second child variance
         * @param level the target level
         * @param target the target variance
         */
        private void sum2(int cl, int a, int b, int level, int target) {
            sse[level][target] = sse[cl][a] + sse[cl][b];
            sum[level][target] = sum[cl][a] + sum[cl][b];
            log2[level][target] = log2[cl][a] + 1;
        }
    }

    /**
     * Computes the frame-level partition thresholds ({@code vp9_set_variance_partition_thresholds}).
     *
     * @param enc the encoder
     * @param q the frame's quantizer index
     */
    static void setFrameThresholds(Vp9RtEncoder enc, int q) {
        computeThresholds(enc, enc.vbpThresholds, q, 0);
        var w = enc.width;
        var h = enc.height;
        var ac = Vp9EncTables.acQuant(q, 0);
        if (enc.keyFrame) {
            enc.vbpThresholdSad = 0;
            enc.vbpThresholdCopy = 0;
            enc.vbpBsizeMin = BLOCK_8X8;
        } else {
            enc.vbpThresholdSad = w <= 352 && h <= 288 ? 10 : Math.max(ac << 1, 1000);
            enc.vbpBsizeMin = BLOCK_16X16;
            if (w <= 352 && h <= 288) {
                enc.vbpThresholdCopy = 4000;
            } else if (w <= 640 && h <= 360) {
                enc.vbpThresholdCopy = 8000;
            } else {
                enc.vbpThresholdCopy = Math.max(ac << 3, 8000);
            }
            if (enc.rc.highSourceSad) {
                enc.vbpThresholdSad = 0;
                enc.vbpThresholdCopy = 0;
            }
        }
        enc.vbpThresholdMinmax = 15 + (q >> 3);
    }

    /**
     * Computes the split thresholds of a quantizer and content state ({@code set_vbp_thresholds}).
     *
     * @param enc the encoder
     * @param thresholds receives the four thresholds
     * @param q the quantizer index
     * @param contentState the superblock's content state
     */
    static void computeThresholds(Vp9RtEncoder enc, long[] thresholds, int q, int contentState) {
        var w = enc.width;
        var h = enc.height;
        var speed = enc.speed;
        var multiplier = enc.keyFrame ? 20 : enc.sf.variancePartThreshMult;
        long base = (long) multiplier * Vp9EncTables.acQuant(q, 0);
        if (enc.keyFrame) {
            thresholds[0] = base;
            thresholds[1] = base >> 2;
            thresholds[2] = base >> 2;
            thresholds[3] = base << 2;
            return;
        }
        if (enc.noise.enabled && w >= 640 && h >= 480) {
            var level = enc.noise.extractLevel();
            if (level == Vp9RtNoiseEstimate.HIGH) {
                base = 3 * base;
            } else if (level == Vp9RtNoiseEstimate.MEDIUM) {
                base = base << 1;
            } else if (level < Vp9RtNoiseEstimate.LOW) {
                base = (7 * base) >> 3;
            }
        }
        base = scaleSumdiff(base, speed, w, h, contentState);
        thresholds[0] = base;
        thresholds[2] = base << speed;
        if (w >= 1280 && h >= 720 && speed < 7) {
            thresholds[2] = thresholds[2] << 1;
        }
        if (w <= 352 && h <= 288) {
            thresholds[0] = base >> 3;
            thresholds[1] = base >> 1;
            thresholds[2] = base << 3;
            if (enc.rc.avgFrameQindexInter > 220) {
                thresholds[2] = thresholds[2] << 2;
            } else if (enc.rc.avgFrameQindexInter > 200) {
                thresholds[2] = thresholds[2] << 1;
            }
        } else if (w < 1280 && h < 720) {
            thresholds[1] = (5 * base) >> 2;
        } else if (w < 1920 && h < 1080) {
            thresholds[1] = base << 1;
        } else {
            thresholds[1] = (5 * base) >> 1;
        }
        if (enc.sf.disable16x16PartNonkey) {
            thresholds[2] = Long.MAX_VALUE;
        }
    }

    /**
     * Raises a threshold for content whose change is uniform ({@code scale_part_thresh_sumdiff}).
     *
     * @param base the base threshold
     * @param speed the speed, from 5 to 9
     * @param width the frame width
     * @param height the frame height
     * @param contentState the content state
     * @return the threshold
     */
    private static long scaleSumdiff(long base, int speed, int width, int height, int contentState) {
        var lowSumdiff = contentState == Vp9RtCoder.CONTENT_LOW_SAD_LOW_SUMDIFF
                || contentState == Vp9RtCoder.CONTENT_HIGH_SAD_LOW_SUMDIFF
                || contentState == Vp9RtCoder.CONTENT_LOW_VAR_HIGH_SUMDIFF;
        if (speed >= 8) {
            if ((width <= 640 && height <= 480) || lowSumdiff) {
                return (5 * base) >> 2;
            }
        } else if (speed == 7 && lowSumdiff) {
            return (5 * base) >> 2;
        }
        return base;
    }

    /**
     * Sets the size of the block at a position, if it is inside the frame ({@code set_block_size}).
     *
     * @param row the 8x8 row
     * @param col the 8x8 column
     * @param bsize the block size
     */
    private void setBlockSize(int row, int col, int bsize) {
        var enc = coder.enc;
        if (enc.miCols > col && enc.miRows > row) {
            var index = row * enc.miCols + col;
            var mi = enc.miPool[index];
            enc.miGrid[index] = mi;
            mi.sbType = bsize;
        }
    }

    /**
     * Chooses the partition of a superblock and records it in the mode information grid
     * ({@code choose_partitioning}).
     *
     * @param row the superblock's 8x8 row
     * @param col the superblock's 8x8 column
     */
    void choose(int row, int col) {
        var enc = coder.enc;
        var c = coder;
        var keyFrame = enc.keyFrame;
        var miCols = enc.miCols;
        var miRows = enc.miRows;
        var sceneChange = enc.rc.highSourceSad;
        var forceSplit64 = sceneChange;
        var computeMinmax = enc.speed < 8;
        var lowRes = enc.width <= 352 && enc.height <= 288;
        var use4x4 = false;
        var contentState = 0;
        var yuvSad = Integer.MAX_VALUE;
        var bsize = BLOCK_64X64;
        var refPartition = LAST_FRAME;
        var sbIndex = enc.sbOffset(row, col);
        c.setOffsets(row, col, BLOCK_64X64);
        c.setSegmentIndex(BLOCK_64X64);
        var segmentId = c.mi.segmentId;
        Arrays.fill(c.varianceLow, false);
        if (enc.sf.useSourceSad && !keyFrame) {
            contentState = c.contentStateSb;
            c.skipLowSourceSad = contentState == Vp9RtCoder.CONTENT_LOW_SAD_LOW_SUMDIFF
                    || contentState == Vp9RtCoder.CONTENT_LOW_SAD_HIGH_SUMDIFF;
            c.lowvarHighsumdiff = contentState == Vp9RtCoder.CONTENT_LOW_VAR_HIGH_SUMDIFF;
            if (enc.contentStateSbFd != null) {
                c.lastSbHighContent = enc.contentStateSbFd[sbIndex];
            }
            if (c.skipLowSourceSad && enc.sf.copyPartitionFlag && !forceSplit64
                    && copyPartitioning(row, col, segmentId, sbIndex)) {
                c.sbUseMvPart = true;
                return;
            }
        }
        var t = thresholds;
        if (enc.cyclicRefreshActive() && Vp9RtCyclicRefresh.boosted(segmentId)) {
            computeThresholds(enc, t, enc.segQindex(segmentId), contentState);
        } else {
            computeThresholds(enc, t, enc.baseQindex, contentState);
        }
        var threshold4x4avg = enc.speed < 8 ? t[1] << 1 : Long.MAX_VALUE;
        var pixelsWide = 64;
        var pixelsHigh = 64;
        if (c.mbToRightEdge < 0) {
            pixelsWide += c.mbToRightEdge >> 3;
        }
        if (c.mbToBottomEdge < 0) {
            pixelsHigh += c.mbToBottomEdge >> 3;
        }
        var src = enc.source;
        var s = src.planes[0];
        var sOff = c.srcOff[0];
        var sp = src.strides[0];
        byte[] d = null;
        var dp = 64;
        Arrays.fill(forceSplit, false);
        forceSplit[0] = forceSplit64;
        var m = c.mi;
        if (!keyFrame) {
            bsize = BLOCK_32X32 + (col + 4 < miCols ? 2 : 0) + (row + 4 < miRows ? 1 : 0);
            var last = enc.activeRef(LAST_FRAME);
            var ySadG = Integer.MAX_VALUE;
            var bw = Vp9RtCoder.NUM_4X4_WIDE[bsize] * 4;
            var bh = Vp9RtCoder.NUM_4X4_HIGH[bsize] * 4;
            var gold = enc.activeRef(GOLDEN_FRAME);
            if (enc.speed < 8 && gold != null && gold != last && enc.refFrameFlagGolden()) {
                ySadG = c.edsp.sad(s, sOff, sp, gold.planes[0], gold.offset(0, col * 8, row * 8), gold.strides[0], bw,
                        bh);
            }
            m.ref0 = LAST_FRAME;
            m.ref1 = NO_REF_FRAME;
            m.sbType = BLOCK_64X64;
            m.mv0 = 0;
            m.interpFilter = BILINEAR;
            int ySad;
            if (enc.speed >= 8 && !lowRes && c.contentStateSb != Vp9RtCoder.CONTENT_VERY_HIGH_SAD) {
                ySad = c.edsp.sad(s, sOff, sp, last.planes[0], last.offset(0, col * 8, row * 8), last.strides[0], bw,
                        bh);
            } else {
                ySad = c.motion.intProMotionEstimation(bsize, last, 0);
                c.sbUseMvPart = true;
                c.sbMvcolPart = (short) m.mv0;
                c.sbMvrowPart = m.mv0 >> 16;
            }
            var ySadLast = ySad;
            var ySadThr = enc.sf.shortCircuitLowTempVar != 0 ? (int) (((long) ySad * 7) >> 3) : ySad;
            var partitionRef = last;
            if (Integer.compareUnsigned(ySadG, ySadThr) < 0) {
                m.ref0 = GOLDEN_FRAME;
                m.mv0 = 0;
                ySad = ySadG;
                refPartition = GOLDEN_FRAME;
                partitionRef = gold;
            } else {
                c.predMv[LAST_FRAME] = m.mv0;
                refPartition = LAST_FRAME;
            }
            buildPrediction(partitionRef, m.mv0, row, col);
            if (enc.useSkinDetection) {
                c.sbIsSkin = enc.content.skinSuperblockSplit(row, col);
                if (c.sbIsSkin) {
                    forceSplit[0] = true;
                }
            }
            yuvSad = ySad;
            d = predY;
            if (segmentId == 0 && Integer.compareUnsigned(ySad, enc.vbpThresholdSad) < 0) {
                if (col + 4 < miCols && row + 4 < miRows) {
                    setBlockSize(row, col, BLOCK_64X64);
                    c.varianceLow[0] = true;
                    chromaCheck(bsize, ySad, false);
                    if (enc.sf.copyPartitionFlag) {
                        updatePrevPartition(segmentId, row, col, sbIndex);
                    }
                    return;
                }
            }
            if (enc.sf.copyPartitionFlag && Integer.compareUnsigned(ySadLast, enc.vbpThresholdCopy) < 0
                    && copyPartitioning(row, col, segmentId, sbIndex)) {
                chromaCheck(bsize, ySad, false);
                return;
            }
        }
        var vt = tree;
        var vt2 = tree2;
        for (var i = 0; i < 4; i++) {
            var x32 = (i & 1) << 5;
            var y32 = (i >> 1) << 5;
            var i2 = i << 2;
            forceSplit[i + 1] = false;
            avg16[i] = 0;
            maxVar16[i] = 0;
            minVar16[i] = Integer.MAX_VALUE;
            for (var j = 0; j < 4; j++) {
                var x16 = x32 + ((j & 1) << 4);
                var y16 = y32 + ((j >> 1) << 4);
                var splitIndex = 5 + i2 + j;
                var node16 = i2 + j;
                forceSplit[splitIndex] = false;
                variance4x4[node16] = false;
                if (!keyFrame) {
                    fill8x8Avg(s, sOff, sp, d, dp, x16, y16, vt, node16, pixelsWide, pixelsHigh);
                    vt.fillNode(2, node16);
                    var v = vt.compute(2, node16 * 5);
                    avg16[i] += v;
                    if (v < minVar16[i]) {
                        minVar16[i] = v;
                    }
                    if (v > maxVar16[i]) {
                        maxVar16[i] = v;
                    }
                    if (v > t[2]) {
                        forceSplit[splitIndex] = true;
                        forceSplit[i + 1] = true;
                        forceSplit[0] = true;
                    } else if (computeMinmax && v > t[1] && !Vp9RtCyclicRefresh.boosted(segmentId)) {
                        var minmax = minmax8x8(s, sOff, sp, d, dp, x16, y16, pixelsWide, pixelsHigh);
                        var threshMinmax = enc.vbpThresholdMinmax;
                        if (c.contentStateSb == Vp9RtCoder.CONTENT_VERY_HIGH_SAD) {
                            threshMinmax <<= 1;
                        }
                        if (minmax > threshMinmax) {
                            forceSplit[splitIndex] = true;
                            forceSplit[i + 1] = true;
                            forceSplit[0] = true;
                        }
                    }
                }
                if (keyFrame || (lowRes && vt.variance[2][node16 * 5] > threshold4x4avg)) {
                    forceSplit[splitIndex] = false;
                    variance4x4[node16] = true;
                    var target = keyFrame ? vt : vt2;
                    for (var k = 0; k < 4; k++) {
                        var x8 = x16 + ((k & 1) << 3);
                        var y8 = y16 + ((k >> 1) << 3);
                        fill4x4Avg(s, sOff, sp, d, dp, x8, y8, target, node16 * 4 + k, pixelsWide, pixelsHigh,
                                keyFrame);
                    }
                }
            }
        }
        var noiseLevel = enc.noise.enabled ? enc.noise.extractLevel() : Vp9RtNoiseEstimate.LOW;
        var avg32 = 0;
        var maxVar32 = 0;
        var minVar32 = Integer.MAX_VALUE;
        for (var i = 0; i < 4; i++) {
            var i2 = i << 2;
            for (var j = 0; j < 4; j++) {
                if (variance4x4[i2 + j]) {
                    var vtemp = keyFrame ? vt : vt2;
                    var node16 = i2 + j;
                    for (var k = 0; k < 4; k++) {
                        vtemp.fillNode(3, node16 * 4 + k);
                    }
                    vtemp.fillNode(2, node16);
                    if (vtemp.compute(2, node16 * 5) > t[2]) {
                        forceSplit[5 + i2 + j] = true;
                        forceSplit[i + 1] = true;
                        forceSplit[0] = true;
                    }
                }
            }
            vt.fillNode(1, i);
            if (!forceSplit[i + 1]) {
                var var32 = vt.compute(1, i * 5);
                maxVar32 = Math.max(var32, maxVar32);
                minVar32 = Math.min(var32, minVar32);
                if (var32 > t[1] || (!keyFrame && var32 > (t[1] >> 1) && var32 > (avg16[i] >> 1))) {
                    forceSplit[i + 1] = true;
                    forceSplit[0] = true;
                } else if (!keyFrame && noiseLevel < Vp9RtNoiseEstimate.LOW && enc.height <= 360
                        && (maxVar16[i] - minVar16[i]) > (t[1] >> 1) && maxVar16[i] > t[1]) {
                    forceSplit[i + 1] = true;
                    forceSplit[0] = true;
                }
                avg32 += var32;
            }
        }
        if (!forceSplit[0]) {
            vt.fillNode(0, 0);
            var var64 = vt.compute(0, 0);
            if (!keyFrame && noiseLevel >= Vp9RtNoiseEstimate.MEDIUM && var64 > (9L * avg32) >> 5) {
                forceSplit[0] = true;
            } else if (!keyFrame && noiseLevel < Vp9RtNoiseEstimate.MEDIUM
                    && (maxVar32 - minVar32) > 3 * (t[0] >> 3) && maxVar32 > t[0] >> 1) {
                forceSplit[0] = true;
            }
        }
        if (col + 8 > miCols || row + 8 > miRows
                || !setVtPartitioning(vt, 0, 0, BLOCK_64X64, row, col, t[0], BLOCK_16X16, forceSplit[0])) {
            for (var i = 0; i < 4; i++) {
                var x32 = (i & 1) << 2;
                var y32 = (i >> 1) << 2;
                var i2 = i << 2;
                if (!setVtPartitioning(vt, 1, i, BLOCK_32X32, row + y32, col + x32, t[1], BLOCK_16X16,
                        forceSplit[i + 1])) {
                    for (var j = 0; j < 4; j++) {
                        var x16 = (j & 1) << 1;
                        var y16 = (j >> 1) << 1;
                        var node16 = i2 + j;
                        var vtemp = !keyFrame && variance4x4[node16] ? vt2 : vt;
                        if (!setVtPartitioning(vtemp, 2, node16, BLOCK_16X16, row + y32 + y16, col + x32 + x16, t[2],
                                enc.vbpBsizeMin, forceSplit[5 + node16])) {
                            for (var k = 0; k < 4; k++) {
                                var x8 = k & 1;
                                var y8 = k >> 1;
                                if (use4x4) {
                                    if (!setVtPartitioning(vtemp, 3, node16 * 4 + k, BLOCK_8X8,
                                            row + y32 + y16 + y8, col + x32 + x16 + x8, t[3], BLOCK_8X8, false)) {
                                        setBlockSize(row + y32 + y16 + y8, col + x32 + x16 + x8, BLOCK_4X4);
                                    }
                                } else {
                                    setBlockSize(row + y32 + y16 + y8, col + x32 + x16 + x8, BLOCK_8X8);
                                }
                            }
                        }
                    }
                }
            }
        }
        if (!keyFrame && enc.sf.copyPartitionFlag) {
            updatePrevPartition(segmentId, row, col, sbIndex);
        }
        if (enc.sf.shortCircuitLowTempVar != 0 && !keyFrame) {
            setLowTempVarFlag(vt, refPartition, row, col);
        }
        chromaCheck(bsize, yuvSad, keyFrame);
    }

    /**
     * Builds the bilinear prediction of the superblock's three planes from a reference.
     *
     * @param ref the reference
     * @param mv the packed motion vector
     * @param row the superblock's 8x8 row
     * @param col the superblock's 8x8 column
     */
    private void buildPrediction(Vp9EncPicture ref, int mv, int row, int col) {
        var p = coder.predictor;
        var ssX = coder.enc.ssX;
        var ssY = coder.enc.ssY;
        p.predictInter(ref, 0, col * 8, row * 8, mv, BILINEAR, predY, 0, 64, 64, 64, false);
        p.predictInter(ref, 1, (col * 8) >> ssX, (row * 8) >> ssY, mv, BILINEAR, predUv[0], 0, 64, 64 >> ssX,
                64 >> ssY, false);
        p.predictInter(ref, 2, (col * 8) >> ssX, (row * 8) >> ssY, mv, BILINEAR, predUv[1], 0, 64, 64 >> ssX,
                64 >> ssY, false);
    }

    /**
     * Records the difference of the 8x8 averages of a 16x16 region's four 8x8 blocks
     * ({@code fill_variance_8x8avg}).
     *
     * @param s the source plane
     * @param sOff the index of the superblock in the source
     * @param sp the source stride
     * @param d the prediction, or {@code null} for a flat 128
     * @param dp the prediction stride
     * @param x16 the region's column in the superblock
     * @param y16 the region's row in the superblock
     * @param vt the tree
     * @param node16 the region's 16x16 node
     * @param pixelsWide the visible width of the superblock
     * @param pixelsHigh the visible height of the superblock
     */
    private void fill8x8Avg(byte[] s, int sOff, int sp, byte[] d, int dp, int x16, int y16, VarianceTree vt,
                            int node16, int pixelsWide, int pixelsHigh) {
        var edsp = coder.edsp;
        var whole = x16 + 8 < pixelsWide && y16 + 8 < pixelsHigh;
        if (whole) {
            edsp.avg8x8Quad(s, sOff + y16 * sp + x16, sp, sourceAverages);
            if (d != null) {
                edsp.avg8x8Quad(d, y16 * dp + x16, dp, predictionAverages);
            }
        }
        for (var k = 0; k < 4; k++) {
            var x8 = x16 + ((k & 1) << 3);
            var y8 = y16 + ((k >> 1) << 3);
            long sse = 0;
            var sum = 0;
            if (whole) {
                sum = sourceAverages[k] - (d == null ? 128 : predictionAverages[k]);
                sse = (long) sum * sum;
            } else if (x8 < pixelsWide && y8 < pixelsHigh) {
                var sAvg = edsp.avg8x8(s, sOff + y8 * sp + x8, sp);
                var dAvg = d == null ? 128 : edsp.avg8x8(d, y8 * dp + x8, dp);
                sum = sAvg - dAvg;
                sse = (long) sum * sum;
            }
            vt.fill(3, (node16 * 4 + k) * 5, sse, sum, 0);
        }
    }

    /**
     * Records the difference of the 4x4 averages of an 8x8 block's four 4x4 blocks
     * ({@code fill_variance_4x4avg}).
     *
     * @param s the source plane
     * @param sOff the index of the superblock in the source
     * @param sp the source stride
     * @param d the prediction, or {@code null} for a flat 128
     * @param dp the prediction stride
     * @param x8 the block's column in the superblock
     * @param y8 the block's row in the superblock
     * @param vt the tree
     * @param node8 the block's 8x8 node
     * @param pixelsWide the visible width of the superblock
     * @param pixelsHigh the visible height of the superblock
     * @param keyFrame whether the frame is a key frame
     */
    private void fill4x4Avg(byte[] s, int sOff, int sp, byte[] d, int dp, int x8, int y8, VarianceTree vt, int node8,
                            int pixelsWide, int pixelsHigh, boolean keyFrame) {
        var edsp = coder.edsp;
        for (var k = 0; k < 4; k++) {
            var x4 = x8 + ((k & 1) << 2);
            var y4 = y8 + ((k >> 1) << 2);
            long sse = 0;
            var sum = 0;
            if (x4 < pixelsWide && y4 < pixelsHigh) {
                var sAvg = edsp.avg4x4(s, sOff + y4 * sp + x4, sp);
                var dAvg = keyFrame || d == null ? 128 : edsp.avg4x4(d, y4 * dp + x4, dp);
                sum = sAvg - dAvg;
                sse = (long) sum * sum;
            }
            vt.fill(4, (node8 * 4 + k) * 5, sse, sum, 0);
        }
    }

    /**
     * Returns the spread of the per-8x8 dynamic range of the difference over a 16x16 region
     * ({@code compute_minmax_8x8}).
     *
     * @param s the source plane
     * @param sOff the index of the superblock in the source
     * @param sp the source stride
     * @param d the prediction
     * @param dp the prediction stride
     * @param x16 the region's column in the superblock
     * @param y16 the region's row in the superblock
     * @param pixelsWide the visible width of the superblock
     * @param pixelsHigh the visible height of the superblock
     * @return the largest range minus the smallest range
     */
    private int minmax8x8(byte[] s, int sOff, int sp, byte[] d, int dp, int x16, int y16, int pixelsWide,
                          int pixelsHigh) {
        var minmaxMax = 0;
        var minmaxMin = 255;
        for (var k = 0; k < 4; k++) {
            var x8 = x16 + ((k & 1) << 3);
            var y8 = y16 + ((k >> 1) << 3);
            if (x8 < pixelsWide && y8 < pixelsHigh) {
                var packed = coder.edsp.minmax8x8(s, sOff + y8 * sp + x8, sp, d, y8 * dp + x8, dp);
                var range = (packed >>> 16) - (packed & 0xFFFF);
                if (range > minmaxMax) {
                    minmaxMax = range;
                }
                if (range < minmaxMin) {
                    minmaxMin = range;
                }
            }
        }
        return minmaxMax - minmaxMin;
    }

    /**
     * Picks the size of a square region from its variances, or declines so that the caller
     * splits it ({@code set_vt_partitioning}).
     *
     * @param vt the tree
     * @param level the region's level
     * @param node the region's node
     * @param bsize the region's size
     * @param row the region's 8x8 row
     * @param col the region's 8x8 column
     * @param threshold the variance threshold
     * @param bsizeMin the smallest size measured with enough samples for halves
     * @param forceSplit whether the region must split
     * @return whether a size was set
     */
    private boolean setVtPartitioning(VarianceTree vt, int level, int node, int bsize, int row, int col,
                                      long threshold, int bsizeMin, boolean forceSplit) {
        if (forceSplit) {
            return false;
        }
        var enc = coder.enc;
        var miCols = enc.miCols;
        var miRows = enc.miRows;
        var half = Vp9RtCoder.NUM_8X8_WIDE[bsize] / 2;
        var base = node * 5;
        if (bsize == bsizeMin) {
            if (enc.keyFrame) {
                vt.compute(level, base);
            }
            if (col + half < miCols && row + half < miRows && vt.variance[level][base] < threshold) {
                setBlockSize(row, col, bsize);
                return true;
            }
            return false;
        }
        if (bsize > bsizeMin) {
            if (enc.keyFrame) {
                vt.compute(level, base);
                if (bsize > BLOCK_32X32 || vt.variance[level][base] > (threshold << 4)) {
                    return false;
                }
            }
            if (col + half < miCols && row + half < miRows && vt.variance[level][base] < threshold) {
                setBlockSize(row, col, bsize);
                return true;
            }
            if (row + half < miRows) {
                var subsize = Vp9Constants.SUBSIZE[PARTITION_VERT * 13 + bsize];
                if (vt.compute(level, base + VERT) < threshold && vt.compute(level, base + VERT + 1) < threshold
                        && enc.uvBsize(subsize) != Vp9Constants.BLOCK_INVALID) {
                    setBlockSize(row, col, subsize);
                    setBlockSize(row, col + half, subsize);
                    return true;
                }
            }
            if (col + half < miCols) {
                var subsize = Vp9Constants.SUBSIZE[PARTITION_HORZ * 13 + bsize];
                if (vt.compute(level, base + HORZ) < threshold && vt.compute(level, base + HORZ + 1) < threshold
                        && enc.uvBsize(subsize) != Vp9Constants.BLOCK_INVALID) {
                    setBlockSize(row, col, subsize);
                    setBlockSize(row + half, col, subsize);
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Decides whether the chroma planes of the superblock deserve a rate estimate
     * ({@code chroma_check}).
     *
     * @param bsize the size the luma difference was measured on
     * @param ySad the luma difference
     * @param keyFrame whether the frame is a key frame
     */
    private void chromaCheck(int bsize, int ySad, boolean keyFrame) {
        if (keyFrame) {
            return;
        }
        var enc = coder.enc;
        if (enc.speed > 8) {
            if (Integer.toUnsignedLong(ySad) > enc.vbpThresholds[1]
                    && (!enc.noise.enabled || enc.noise.extractLevel() < Vp9RtNoiseEstimate.MEDIUM)) {
                return;
            }
        }
        var src = enc.source;
        var uvBsize = enc.uvBsize(bsize);
        if (uvBsize == Vp9Constants.BLOCK_INVALID) {
            coder.colorSensitivity[0] = true;
            coder.colorSensitivity[1] = true;
            return;
        }
        var w = Vp9RtCoder.NUM_4X4_WIDE[uvBsize] * 4;
        var h = Vp9RtCoder.NUM_4X4_HIGH[uvBsize] * 4;
        for (var i = 1; i <= 2; i++) {
            var uvSad = coder.edsp.sad(src.planes[i], coder.srcOff[i], src.strides[i], predUv[i - 1], 0, 64, w, h);
            coder.colorSensitivity[i - 1] = Integer.compareUnsigned(uvSad, ySad >>> 2) > 0;
        }
    }

    /**
     * Flags the regions of the superblock whose temporal variance is low
     * ({@code set_low_temp_var_flag}).
     *
     * @param vt the tree
     * @param refPartition the reference the partition was measured against
     * @param row the superblock's 8x8 row
     * @param col the superblock's 8x8 column
     */
    private void setLowTempVarFlag(VarianceTree vt, int refPartition, int row, int col) {
        var enc = coder.enc;
        var t = thresholds;
        var mvThr = enc.width > 640 ? 8 : 4;
        var mv = coder.mi.mv0;
        var mvRow = mv >> 16;
        var mvCol = (short) mv;
        var shortCircuit = enc.sf.shortCircuitLowTempVar;
        if (refPartition != LAST_FRAME || !(shortCircuit == 1
                || (mvCol < mvThr && mvCol > -mvThr && mvRow < mvThr && mvRow > -mvThr))) {
            return;
        }
        var miCols = enc.miCols;
        var miRows = enc.miRows;
        var sbType = enc.miGrid[row * miCols + col].sbType;
        var low = coder.varianceLow;
        if (sbType == BLOCK_64X64) {
            if (vt.variance[0][0] < (t[0] >> 1)) {
                low[0] = true;
            }
        } else if (sbType == BLOCK_64X32) {
            for (var i = 0; i < 2; i++) {
                if (vt.variance[0][HORZ + i] < (t[0] >> 2)) {
                    low[i + 1] = true;
                }
            }
        } else if (sbType == BLOCK_32X64) {
            for (var i = 0; i < 2; i++) {
                if (vt.variance[0][VERT + i] < (t[0] >> 2)) {
                    low[i + 3] = true;
                }
            }
        } else {
            for (var i = 0; i < 4; i++) {
                var r = row + ((i >> 1) << 2);
                var cc = col + ((i & 1) << 2);
                if (miCols <= cc || miRows <= r) {
                    continue;
                }
                var type = enc.miGrid[r * miCols + cc].sbType;
                if (type == BLOCK_32X32) {
                    var threshold32 = shortCircuit == 1 || shortCircuit == 3 ? (5 * t[1]) >> 3 : t[1] >> 1;
                    if (vt.variance[1][i * 5] < threshold32) {
                        low[i + 5] = true;
                    }
                } else if (shortCircuit >= 2) {
                    if (type == BLOCK_16X16 || type == Vp9Constants.BLOCK_32X16 || type == Vp9Constants.BLOCK_16X32) {
                        for (var j = 0; j < 4; j++) {
                            if (vt.variance[2][(i * 4 + j) * 5] < (t[2] >> 8)) {
                                low[(i << 2) + j + 9] = true;
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Copies the previous frame's partition of the superblock when allowed
     * ({@code copy_partitioning}).
     *
     * @param row the superblock's 8x8 row
     * @param col the superblock's 8x8 column
     * @param segmentId the superblock's segment
     * @param sbIndex the superblock's index
     * @return whether the partition was copied
     */
    private boolean copyPartitioning(int row, int col, int segmentId, int sbIndex) {
        var enc = coder.enc;
        var framesSinceKeyThresh = 1;
        var svcCopyAllowed = true;
        if (enc.temporalLayers > 1) {
            if (enc.layerKeyFrame() || !enc.nonReferenceFrame) {
                svcCopyAllowed = false;
            }
            framesSinceKeyThresh = 2;
        }
        if (enc.rc.framesSinceKey > framesSinceKeyThresh && svcCopyAllowed && segmentId == 0
                && enc.prevSegmentId[sbIndex] == 0 && enc.copiedFrameCnt[sbIndex] < enc.sf.maxCopiedFrame) {
            copyHelper(BLOCK_64X64, row, col);
            enc.copiedFrameCnt[sbIndex]++;
            System.arraycopy(enc.prevVarianceLow, sbIndex * 25, coder.varianceLow, 0, 25);
            return true;
        }
        return false;
    }

    /**
     * Sets block sizes from the previous partition ({@code copy_partitioning_helper}).
     *
     * @param bsize the region's size
     * @param row the region's 8x8 row
     * @param col the region's 8x8 column
     */
    private void copyHelper(int bsize, int row, int col) {
        var enc = coder.enc;
        if (row >= enc.miRows || col >= enc.miCols) {
            return;
        }
        var bs = Vp9RtCoder.NUM_8X8_WIDE[bsize] >> 1;
        var part = Vp9RtCoder.partitionOf(bsize, enc.prevPartition[row * enc.miCols + col]);
        var subsize = Vp9Constants.SUBSIZE[part * 13 + bsize];
        if (subsize < BLOCK_8X8) {
            setBlockSize(row, col, bsize);
            return;
        }
        switch (part) {
            case PARTITION_NONE -> setBlockSize(row, col, bsize);
            case PARTITION_HORZ -> {
                setBlockSize(row, col, subsize);
                setBlockSize(row + bs, col, subsize);
            }
            case PARTITION_VERT -> {
                setBlockSize(row, col, subsize);
                setBlockSize(row, col + bs, subsize);
            }
            default -> {
                copyHelper(subsize, row, col);
                copyHelper(subsize, row + bs, col);
                copyHelper(subsize, row, col + bs);
                copyHelper(subsize, row + bs, col + bs);
            }
        }
    }

    /**
     * Saves the superblock's partition, segment and low variance flags for copying in later
     * frames ({@code update_prev_partition}).
     *
     * @param segmentId the superblock's segment
     * @param row the superblock's 8x8 row
     * @param col the superblock's 8x8 column
     * @param sbIndex the superblock's index
     */
    private void updatePrevPartition(int segmentId, int row, int col, int sbIndex) {
        var enc = coder.enc;
        updatePrevHelper(BLOCK_64X64, row, col);
        enc.prevSegmentId[sbIndex] = (byte) segmentId;
        System.arraycopy(coder.varianceLow, 0, enc.prevVarianceLow, sbIndex * 25, 25);
        enc.copiedFrameCnt[sbIndex] = 0;
    }

    /**
     * Records the block sizes of a region in the previous partition map
     * ({@code update_prev_partition_helper}).
     *
     * @param bsize the region's size
     * @param row the region's 8x8 row
     * @param col the region's 8x8 column
     */
    private void updatePrevHelper(int bsize, int row, int col) {
        var enc = coder.enc;
        var miCols = enc.miCols;
        if (row >= enc.miRows || col >= miCols) {
            return;
        }
        var start = row * miCols + col;
        var bs = Vp9RtCoder.NUM_8X8_WIDE[bsize] >> 1;
        var part = Vp9RtCoder.partitionOf(bsize, enc.miGrid[start].sbType);
        var subsize = Vp9Constants.SUBSIZE[part * 13 + bsize];
        var prev = enc.prevPartition;
        if (subsize < BLOCK_8X8) {
            prev[start] = (byte) bsize;
            return;
        }
        switch (part) {
            case PARTITION_NONE -> prev[start] = (byte) bsize;
            case PARTITION_HORZ -> {
                prev[start] = (byte) subsize;
                if (row + bs < enc.miRows) {
                    prev[start + bs * miCols] = (byte) subsize;
                }
            }
            case PARTITION_VERT -> {
                prev[start] = (byte) subsize;
                if (col + bs < miCols) {
                    prev[start + bs] = (byte) subsize;
                }
            }
            default -> {
                updatePrevHelper(subsize, row, col);
                updatePrevHelper(subsize, row + bs, col);
                updatePrevHelper(subsize, row, col + bs);
                updatePrevHelper(subsize, row + bs, col + bs);
            }
        }
    }
}
