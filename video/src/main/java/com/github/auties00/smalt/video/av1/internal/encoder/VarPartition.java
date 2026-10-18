package com.github.auties00.smalt.video.av1.internal.encoder;

import com.github.auties00.smalt.video.av1.internal.McDsp;

import java.util.Arrays;

/**
 * Chooses the partition of a 64x64 superblock from the variance of its prediction error at zero
 * or estimated motion (inter frames) or of the source itself (key frames), and records the
 * superblock statistics the mode decision reads: the source change class, the color
 * sensitivity of the chroma planes and the reference used for partitioning.
 *
 * <p>An instance belongs to one tile encoder.
 *
 * @implNote This implementation ports libaom's {@code av1/encoder/var_based_part.c}
 * ({@code av1_choose_var_based_partitioning}, {@code set_vbp_thresholds} and its tuning
 * helpers, {@code fill_variance_tree_leaves}, {@code set_vt_partitioning},
 * {@code setup_planes}, {@code chroma_check}, {@code evaluate_neighbour_mvs}) and
 * {@code av1_source_content_sb} ({@code av1/encoder/encodeframe_utils.c}) for 64x64
 * superblocks without spatial layers, noise estimation or screen content.
 */
final class VarPartition {
    // TODO: estimate the source noise and detect screen content, which adjust libaom's thresholds

    /**
     * The source change class of a static superblock.
     */
    static final int ZERO_SAD = 0;

    /**
     * The source change class of a nearly static superblock.
     */
    static final int VERY_LOW_SAD = 1;

    /**
     * The source change class of a low-motion superblock.
     */
    static final int LOW_SAD = 2;

    /**
     * The default source change class.
     */
    static final int MED_SAD = 3;

    /**
     * The source change class of a high-motion superblock.
     */
    static final int HIGH_SAD = 4;

    /**
     * No forced decision.
     */
    private static final int EVAL_ALL = 0;

    /**
     * Forced split.
     */
    private static final int EVAL_ONLY_SPLIT = 1;

    /**
     * Forced no split.
     */
    private static final int EVAL_ONLY_NONE = 2;

    /**
     * The quantizer index above which large blocks are preferred ({@code QINDEX_LARGE_BLOCK_THR}).
     */
    private static final int QINDEX_LARGE_BLOCK_THR = 100;

    /**
     * The frame encoder.
     */
    private final FrameEncoder frame;

    /**
     * The tile encoder the partitioning belongs to.
     */
    private final TileEncoder tile;

    /**
     * The kernels.
     */
    private EncDsp dsp;

    /**
     * The superblock motion estimate.
     */
    private final MotionSearch search;

    /**
     * The partition chosen at each node: {@code [0]} the 64x64 node, {@code [1..4]} the 32x32
     * nodes, {@code [5..20]} the 16x16 nodes, as the decoder's partition codes.
     */
    final int[] part = new int[21];

    /**
     * The source change class of the superblock.
     */
    int sourceSadNonrd;

    /**
     * Whether the superblock has little difference from the previous source.
     */
    boolean lowSumdiff;

    /**
     * Whether the superblock shows a large lighting change.
     */
    boolean lightingChange;

    /**
     * The color sensitivity of the U and V planes: 0 off, 1 on, 2 undecided.
     */
    final int[] colorSensitivity = new int[2];

    /**
     * The color sensitivity of the U and V planes against the golden reference.
     */
    final boolean[] colorSensitivityG = new boolean[2];

    /**
     * The color sensitivity of the U and V planes against the altref reference.
     */
    final boolean[] colorSensitivityAlt = new boolean[2];

    /**
     * The reference used to partition: 0 {@code LAST}, 3 {@code GOLDEN}, 6 {@code ALTREF}.
     */
    int refForPartition;

    /**
     * The motion vector of the partitioning, packed full-sample {@code row << 16 | col}.
     */
    int partMv;

    /**
     * The per-sample source variance of the superblock, or {@link Integer#MAX_VALUE} when not
     * computed.
     */
    int sourceVariance;

    /**
     * The golden and altref pruning level of the superblock's mode decision.
     */
    int nonrdPruneRefFrameSearch;

    /**
     * The variance tree: sums of squared errors, sums of errors and sample count logarithms of
     * the 4x4 or 8x8 leaves and of every node.
     */
    private final long[] leafSse = new long[256];

    /**
     * The sums of errors of the leaves.
     */
    private final int[] leafSum = new int[256];

    /**
     * The prediction of the superblock at the partitioning motion vector.
     */
    private final short[] pred = new short[64 * 64];

    /**
     * The scratch of the motion compensation.
     */
    private final short[] mcScratch = new short[McDsp.SCRATCH_SHORTS];

    /**
     * The scratch of the integral projection search.
     */
    private final int[] sadOut = new int[2];

    /**
     * The forced decisions of the nodes, indexed like {@link #part}.
     */
    private final int[] force = new int[21];

    /**
     * The sum of the 16x16 variances of each 32x32 node.
     */
    private final int[] avg16 = new int[4];

    /**
     * The largest 16x16 variance of each 32x32 node.
     */
    private final int[] max16 = new int[4];

    /**
     * The smallest 16x16 variance of each 32x32 node.
     */
    private final int[] min16 = new int[4];

    /**
     * The thresholds of the 64, 32, 16 and 8 levels and the minmax threshold.
     */
    private final long[] thresholds = new long[5];

    /**
     * Creates the partitioning of a tile.
     *
     * @param frame the frame encoder
     * @param tile the tile encoder
     * @param dsp the kernels
     * @param search the motion search of the tile
     */
    VarPartition(FrameEncoder frame, TileEncoder tile, EncDsp dsp, MotionSearch search) {
        this.frame = frame;
        this.tile = tile;
        this.dsp = dsp;
        this.search = search;
    }

    /**
     * Sets the kernels of the next tile.
     *
     * @param dsp the kernels
     */
    void dsp(EncDsp dsp) {
        this.dsp = dsp;
    }

    /**
     * Classifies the change of a superblock's source since the previous frame
     * ({@code av1_source_content_sb}).
     *
     * @param px the superblock column in samples
     * @param py the superblock row in samples
     */
    void sourceContent(int px, int py) {
        sourceSadNonrd = MED_SAD;
        lowSumdiff = false;
        lightingChange = false;
        var last = frame.lastSource;
        if (last == null) {
            return;
        }
        var src = frame.source;
        var stride = src.stride[0];
        var off = py * stride + px;
        var packed = dsp.sseSum(src.data[0], off, stride, last.data[0], off, stride, 64, 64);
        var sse = (long) EncDsp.sse(packed) & 0xFFFFFFFFL;
        var var = (long) EncDsp.variance(packed, 12) & 0xFFFFFFFFL;
        if (sse == 0) {
            sourceSadNonrd = ZERO_SAD;
            return;
        }
        if (sse < 10000) {
            sourceSadNonrd = VERY_LOW_SAD;
        } else if (sse < 100000) {
            sourceSadNonrd = LOW_SAD;
        } else if (sse > 1000000) {
            sourceSadNonrd = HIGH_SAD;
        }
        if (var < (sse >> 1) && (sse - var) > 10000) {
            lightingChange = true;
        }
        if ((sse - var) < 5000) {
            lowSumdiff = true;
        }
    }

    /**
     * Sets the partition thresholds of a superblock ({@code set_vbp_thresholds}).
     *
     * @param key whether the frame is a key frame
     * @param qindex the quantizer index of the superblock
     * @param baseQ the frame's base quantizer index
     * @param blockSad the superblock's SAD against the previous source
     */
    private void setThresholds(boolean key, int qindex, int baseQ, long blockSad) {
        var sf = frame.speed;
        var acQ = Quantizer.acQuant(qindex, 0);
        long base = (key ? 120L : 1L) * acQ;
        var numPixels = (long) frame.width * frame.height;
        var t = thresholds;
        if (key) {
            if (sf.forceLargePartitionBlocksIntra) {
                base <<= sf.varPartSplitThresholdShift - 8;
            }
            t[0] = base;
            t[1] = base;
            if (numPixels < 1280L * 720) {
                t[2] = base / 3;
                t[3] = base >> 1;
            } else {
                var shift = sf.forceLargePartitionBlocksIntra ? 0 : 2;
                t[2] = base >> shift;
                t[3] = base >> shift;
            }
            t[4] = base << 2;
            return;
        }
        if (sf.speed >= 8) {
            base = (5 * base) >> 2;
        }
        t[0] = base >> 1;
        t[1] = base;
        t[3] = base << sf.varPartSplitThresholdShift;
        if (numPixels >= 1280L * 720) {
            t[3] <<= 1;
        }
        if (numPixels <= 352L * 288) {
            int[][] qindexThr = {{200, 220}, {140, 170}, {120, 150}, {200, 210}, {170, 220}};
            var thIdx = 0;
            if (sf.varPartBasedOnQidx >= 1) {
                thIdx = sourceSadNonrd <= LOW_SAD ? sf.varPartBasedOnQidx : 0;
            }
            if (sf.varPartBasedOnQidx >= 3) {
                thIdx = sf.varPartBasedOnQidx;
            }
            var low = qindexThr[thIdx][0];
            var high = qindexThr[thIdx][1];
            if (baseQ >= high) {
                base = (5 * base) >> 1;
                t[1] = base >> 3;
                t[2] = base << 2;
                t[3] = base << 5;
            } else if (baseQ < low) {
                t[1] = base >> 3;
                t[2] = base >> 1;
                t[3] = base << 3;
            } else {
                long diffLow = baseQ - low;
                long diffHigh = high - baseQ;
                long diff = Math.max(high - low, 1);
                var baseHigh = (5 * base) >> 1;
                base = (diffLow * baseHigh + diffHigh * base) / diff;
                t[1] = base >> 3;
                t[2] = (diffLow * base + diffHigh * (base >> 1)) / diff;
                t[3] = (diffLow * (base << 5) + diffHigh * (base << 3)) / diff;
            }
        } else if (numPixels < 1280L * 720) {
            t[2] = (5 * base) >> 2;
        } else if (numPixels < 1920L * 1080) {
            t[2] = base << 1;
        } else {
            t[2] = sf.speed > 7 ? 6 * base : 3 * base;
        }
        tuneOnQindex(baseQ, blockSad, numPixels);
    }

    /**
     * Tunes the thresholds toward larger partitions at high quantizers
     * ({@code tune_thresh_based_on_qindex}).
     *
     * @param q the frame's base quantizer index
     * @param blockSad the superblock's SAD against the previous source
     * @param numPixels the number of samples of the picture
     */
    private void tuneOnQindex(int q, long blockSad, long numPixels) {
        var sf = frame.speed;
        var t = thresholds;
        if (sf.preferLargePartitionBlocks >= 3) {
            var win = 20;
            double weight;
            if (q < QINDEX_LARGE_BLOCK_THR - win) {
                weight = 1.0;
            } else if (q > QINDEX_LARGE_BLOCK_THR + win) {
                weight = 0.0;
            } else {
                weight = 1.0 - (q - QINDEX_LARGE_BLOCK_THR + win) / (2.0 * win);
            }
            if (numPixels > 640L * 480) {
                for (var i = 0; i < 4; i++) {
                    t[i] <<= 1;
                }
            }
            if (numPixels <= 352L * 288) {
                t[3] = Long.MAX_VALUE;
                t[1] <<= 2;
                t[2] <<= sourceSadNonrd <= LOW_SAD ? 5 : 4;
                if (frame.avgSourceSad < 25000 && blockSad > 25000 && blockSad < 50000 && !lightingChange) {
                    t[2] = (3 * t[2]) >> 2;
                    t[3] = t[2] << 3;
                }
            } else if (numPixels > 640L * 480 && (sourceSadNonrd != HIGH_SAD || frame.avgSourceSad > 50000)) {
                t[0] = (3 * t[0]) >> 1;
                t[3] = Long.MAX_VALUE;
                if (q > QINDEX_LARGE_BLOCK_THR) {
                    t[1] = (long) ((1 - weight) * (t[1] << 1) + weight * t[1]);
                    t[2] = (long) ((1 - weight) * (t[2] << 1) + weight * t[2]);
                }
            } else if (q > QINDEX_LARGE_BLOCK_THR && (sourceSadNonrd != HIGH_SAD || frame.avgSourceSad > 50000)) {
                t[1] = (long) ((1 - weight) * (t[1] << 2) + weight * t[1]);
                t[2] = (long) ((1 - weight) * (t[2] << 4) + weight * t[2]);
                t[3] = Long.MAX_VALUE;
            }
        } else if (sf.preferLargePartitionBlocks >= 2) {
            t[1] <<= sourceSadNonrd <= LOW_SAD ? 2 : 0;
            t[2] = sourceSadNonrd <= LOW_SAD ? 3 * t[2] : t[2];
        } else if (sf.preferLargePartitionBlocks >= 1) {
            var fac = sourceSadNonrd <= LOW_SAD ? 2 : 1;
            double weight;
            if (q < QINDEX_LARGE_BLOCK_THR - 45) {
                weight = 1.0;
            } else if (q > QINDEX_LARGE_BLOCK_THR + 45) {
                weight = 0.0;
            } else {
                weight = 1.0 - (q - QINDEX_LARGE_BLOCK_THR + 45) / 90.0;
            }
            t[1] = (long) ((1 - weight) * (t[1] << 1) + weight * t[1]);
            t[2] = (long) ((1 - weight) * (t[2] << 1) + weight * t[2]);
            if (t[3] != Long.MAX_VALUE) {
                t[3] = (long) ((1 - weight) * (t[3] << fac) + weight * t[3]);
            }
        }
    }

    /**
     * Returns the variance of a node from its sums, scaled by 256 ({@code get_variance}).
     *
     * @param sse the sum of squared errors
     * @param sum the sum of errors
     * @param log2n the base-2 logarithm of the number of leaves
     * @return the variance
     */
    private static int variance(long sse, long sum, int log2n) {
        return (int) ((256 * (sse - ((sum * sum) >> log2n))) >> log2n);
    }

    /**
     * Chooses the partition of the superblock at a position.
     *
     * @param px the superblock column in samples
     * @param py the superblock row in samples
     * @param key whether the frame is a key frame
     * @param segment the superblock's segment
     */
    void choose(int px, int py, boolean key, int segment) {
        var f = frame;
        var qindex = f.segmentQindex(segment);
        var src = f.source.data[0];
        var srcStride = f.source.stride[0];
        var srcOff = py * srcStride + px;
        var pixelsWide = Math.min(64, f.width - px);
        var pixelsHigh = Math.min(64, f.height - py);
        var blockSad = f.blockSourceSad(px, py);
        setThresholds(key, qindex, f.baseQindex, blockSad);
        sourceVariance = Integer.MAX_VALUE;
        if (sourceSadNonrd > LOW_SAD) {
            var packed = flatSseSum(src, srcOff, srcStride);
            sourceVariance = EncDsp.variance(packed, 12) >>> 12;
        }
        refForPartition = 0;
        partMv = 0;
        nonrdPruneRefFrameSearch = f.speed.nonrdPruneRefFrameSearch;
        colorSensitivity[0] = colorSensitivity[1] = 0;
        colorSensitivityG[0] = colorSensitivityG[1] = false;
        colorSensitivityAlt[0] = colorSensitivityAlt[1] = false;
        short[] dst = null;
        var dstOff = 0;
        var dstStride = 0;
        if (!key) {
            var ySad = setupPlanes(px, py, srcOff);
            var ref = f.ref(refForPartition);
            if (partMv != 0) {
                predictSb(ref, px, py);
                dst = pred;
                dstOff = 0;
                dstStride = 64;
            } else {
                dst = ref.padded[0];
                dstStride = ref.stride[0];
                dstOff = ref.origin[0] + py * dstStride + px;
            }
            chromaCheck(px, py, ySad, partMv == 0);
        }
        var force = this.force;
        Arrays.fill(force, EVAL_ALL);
        var avg16 = this.avg16;
        var max16 = this.max16;
        var min16 = this.min16;
        fillLeaves(key, src, srcOff, srcStride, dst, dstOff, dstStride, pixelsWide, pixelsHigh, force, avg16, max16,
                min16);
        var maxVar32 = 0;
        var minVar32 = Integer.MAX_VALUE;
        for (var i32 = 0; i32 < 4; i32++) {
            if (key) {
                for (var i16 = 0; i16 < 4; i16++) {
                    var n16 = i32 * 4 + i16;
                    var v = nodeVariance(2, n16);
                    if (v > thresholds[3]) {
                        force[5 + n16] = EVAL_ONLY_SPLIT;
                        force[1 + i32] = EVAL_ONLY_SPLIT;
                        force[0] = EVAL_ONLY_SPLIT;
                    }
                }
            }
            if (force[1 + i32] == EVAL_ALL) {
                var var32 = nodeVariance(1, i32);
                maxVar32 = Math.max(maxVar32, var32);
                minVar32 = Math.min(minVar32, var32);
                var diff16 = max16[i32] - min16[i32];
                var is360OrSmaller = (long) f.width * f.height <= 640L * 360;
                if (var32 > thresholds[2] || (!key && var32 > (thresholds[2] >> 1) && var32 > (avg16[i32] >> 1))) {
                    force[1 + i32] = EVAL_ONLY_SPLIT;
                    force[0] = EVAL_ONLY_SPLIT;
                } else if (!key && is360OrSmaller && ((diff16 > (thresholds[2] >> 1) && max16[i32] > thresholds[2])
                        || (f.speed.preferLargePartitionBlocks != 0 && sourceSadNonrd > LOW_SAD
                        && f.frameSourceSad < 20000 && max16[i32] > (thresholds[2] >> 4)
                        && max16[i32] > ((long) min16[i32] << 2)))) {
                    force[1 + i32] = EVAL_ONLY_SPLIT;
                    force[0] = EVAL_ONLY_SPLIT;
                }
            }
        }
        if (force[0] == EVAL_ALL) {
            var maxMinDiff = maxVar32 - minVar32;
            var checkMax = maxVar32 > thresholds[1] >> 1;
            var checkNoise = f.speed.preferLargePartitionBlocks != 0;
            if (!key && maxMinDiff > 3 * (thresholds[1] >> 3) && checkMax && checkNoise) {
                force[0] = EVAL_ONLY_SPLIT;
            }
        }
        var mi = f.miCols();
        var miRows = f.miRows();
        var miCol = px >> 2;
        var miRow = py >> 2;
        if (!setPartition(key, 0, 0, miCol, miRow, 16, thresholds[1], force[0], mi, miRows)) {
            part[0] = TileEncoder.PARTITION_SPLIT;
            for (var i32 = 0; i32 < 4; i32++) {
                var x32 = (i32 & 1) * 8;
                var y32 = (i32 >> 1) * 8;
                if (setPartition(key, 1, i32, miCol + x32, miRow + y32, 8, thresholds[2], force[1 + i32], mi,
                        miRows)) {
                    continue;
                }
                part[1 + i32] = TileEncoder.PARTITION_SPLIT;
                for (var i16 = 0; i16 < 4; i16++) {
                    var x16 = x32 + (i16 & 1) * 4;
                    var y16 = y32 + (i16 >> 1) * 4;
                    var n16 = i32 * 4 + i16;
                    if (!setPartition(key, 2, n16, miCol + x16, miRow + y16, 4, thresholds[3], force[5 + n16], mi,
                            miRows)) {
                        part[5 + n16] = TileEncoder.PARTITION_SPLIT;
                    }
                }
            }
        }
    }

    /**
     * Returns the sums of a 64x64 source block against a flat 128 reference, packed like
     * {@link EncDsp#sseSum}.
     *
     * @param src the source
     * @param off the offset of the block
     * @param stride the row stride
     * @return the packed sums
     */
    private static long flatSseSum(short[] src, int off, int stride) {
        long sse = 0;
        long sum = 0;
        for (var y = 0; y < 64; y++) {
            var p = off + y * stride;
            for (var x = 0; x < 64; x++) {
                var d = src[p + x] - 128;
                sum += d;
                sse += d * d;
            }
        }
        return sse << 32 | (sum & 0xFFFFFFFFL);
    }

    /**
     * Fills the variance tree leaves: 4x4 source averages against 128 for key frames, 8x8 average
     * differences against the prediction for inter frames ({@code fill_variance_tree_leaves}).
     *
     * @param key whether the frame is a key frame
     * @param src the source
     * @param srcOff the offset of the superblock in the source
     * @param srcStride the source stride
     * @param dst the prediction, {@code null} for key frames
     * @param dstOff the offset of the superblock in the prediction
     * @param dstStride the prediction stride
     * @param pixelsWide the visible width of the superblock
     * @param pixelsHigh the visible height of the superblock
     * @param force the forced decisions
     * @param avg16 receives the sum of the 16x16 variances of each 32x32 node
     * @param max16 receives the largest 16x16 variance of each 32x32 node
     * @param min16 receives the smallest 16x16 variance of each 32x32 node
     */
    private void fillLeaves(boolean key, short[] src, int srcOff, int srcStride, short[] dst, int dstOff, int dstStride,
                            int pixelsWide, int pixelsHigh, int[] force, int[] avg16, int[] max16, int[] min16) {
        leafLevel = key ? 4 : 3;
        for (var i32 = 0; i32 < 4; i32++) {
            var x32 = (i32 & 1) * 32;
            var y32 = (i32 >> 1) * 32;
            avg16[i32] = 0;
            max16[i32] = 0;
            min16[i32] = Integer.MAX_VALUE;
            for (var i16 = 0; i16 < 4; i16++) {
                var x16 = x32 + (i16 & 1) * 16;
                var y16 = y32 + (i16 >> 1) * 16;
                var n16 = i32 * 4 + i16;
                for (var i8 = 0; i8 < 4; i8++) {
                    var x8 = x16 + (i8 & 1) * 8;
                    var y8 = y16 + (i8 >> 1) * 8;
                    var n8 = n16 * 4 + i8;
                    if (key) {
                        for (var i4 = 0; i4 < 4; i4++) {
                            var x4 = x8 + (i4 & 1) * 4;
                            var y4 = y8 + (i4 >> 1) * 4;
                            var leaf = n8 * 4 + i4;
                            var sum = 0;
                            if (x4 < pixelsWide && y4 < pixelsHigh) {
                                sum = dsp.avg4x4(src, srcOff + y4 * srcStride + x4, srcStride) - 128;
                            }
                            leafSum[leaf] = sum;
                            leafSse[leaf] = (long) sum * sum;
                        }
                    } else {
                        var sum = 0;
                        if (x8 < pixelsWide && y8 < pixelsHigh) {
                            sum = dsp.avgDiff8x8(src, srcOff + y8 * srcStride + x8, srcStride, dst,
                                    dstOff + y8 * dstStride + x8, dstStride);
                        }
                        leafSum[n8] = sum;
                        leafSse[n8] = (long) sum * sum;
                    }
                }
                if (!key) {
                    var v = nodeVariance(2, n16);
                    avg16[i32] += v;
                    min16[i32] = Math.min(min16[i32], v);
                    max16[i32] = Math.max(max16[i32], v);
                    if (v > thresholds[3]) {
                        force[5 + n16] = EVAL_ONLY_SPLIT;
                        force[1 + i32] = EVAL_ONLY_SPLIT;
                        force[0] = EVAL_ONLY_SPLIT;
                    }
                }
            }
        }
    }

    /**
     * The tree level of the leaves: 3 for 8x8 leaves, 4 for 4x4 leaves.
     */
    private int leafLevel;

    /**
     * Returns the sums of a node, packed as the sum of squared errors and the sum.
     *
     * @param level the node level: 0 64x64, 1 32x32, 2 16x16, 3 8x8
     * @param index the node index within its level, in raster order of quadrants
     * @param out receives the sum of squared errors in {@code [0]} and the sum in {@code [1]}
     * @return the base-2 logarithm of the number of leaves
     */
    private int nodeSums(int level, int index, long[] out) {
        var depth = leafLevel - level;
        var n = 1 << (2 * depth);
        var first = index << (2 * depth);
        long sse = 0;
        long sum = 0;
        for (var i = 0; i < n; i++) {
            sse += leafSse[first + i];
            sum += leafSum[first + i];
        }
        out[0] = sse;
        out[1] = sum;
        return 2 * depth;
    }

    /**
     * The scratch of {@link #nodeSums}.
     */
    private final long[] sums = new long[2];

    /**
     * Returns the variance of a node.
     *
     * @param level the node level
     * @param index the node index
     * @return the variance
     */
    private int nodeVariance(int level, int index) {
        var log2n = nodeSums(level, index, sums);
        return variance(sums[0], sums[1], log2n);
    }

    /**
     * Returns the variance of two quadrants of a node, the halves of a horizontal or vertical
     * split.
     *
     * @param level the node level
     * @param index the node index
     * @param q0 the first quadrant
     * @param q1 the second quadrant
     * @return the variance
     */
    private int halfVariance(int level, int index, int q0, int q1) {
        var child = level + 1;
        var a = nodeSums(child, index * 4 + q0, sums);
        var sse = sums[0];
        var sum = sums[1];
        nodeSums(child, index * 4 + q1, sums);
        return variance(sse + sums[0], sum + sums[1], a + 1);
    }

    /**
     * Decides whether a node is coded unsplit or as two halves ({@code set_vt_partitioning}).
     *
     * @param key whether the frame is a key frame
     * @param level the node level: 0 64x64, 1 32x32, 2 16x16
     * @param index the node index
     * @param miCol the node column in 4-sample units
     * @param miRow the node row in 4-sample units
     * @param size the node size in 4-sample units
     * @param threshold the variance threshold
     * @param force the forced decision
     * @param miCols the picture width in 4-sample units, rounded to 8 samples
     * @param miRows the picture height in 4-sample units, rounded to 8 samples
     * @return {@code true} when the node is not split further
     */
    private boolean setPartition(boolean key, int level, int index, int miCol, int miRow, int size, long threshold,
                                 int force, int miCols, int miRows) {
        var slot = level == 0 ? 0 : level == 1 ? 1 + index : 5 + index;
        var widthCheck = (size >> 1) + 1;
        var heightCheck = (size >> 1) + 1;
        var widthVertCheck = (size >> 2) + 1;
        var heightHorzCheck = (size >> 2) + 1;
        var fitsW = miCol + widthCheck <= miCols;
        var fitsH = miRow + heightCheck <= miRows;
        if (fitsW && fitsH && force == EVAL_ONLY_NONE) {
            part[slot] = TileEncoder.PARTITION_NONE;
            return true;
        }
        if (force == EVAL_ONLY_SPLIT) {
            return false;
        }
        var none = nodeVariance(level, index);
        if (key && (level == 0 || none > (threshold << 4))) {
            return false;
        }
        if (fitsW && fitsH && none < threshold) {
            part[slot] = TileEncoder.PARTITION_NONE;
            return true;
        }
        if (fitsH && miCol + widthVertCheck <= miCols) {
            if (halfVariance(level, index, 0, 2) < threshold && halfVariance(level, index, 1, 3) < threshold) {
                part[slot] = TileEncoder.PARTITION_V;
                return true;
            }
        }
        if (fitsW && miRow + heightHorzCheck <= miRows) {
            if (halfVariance(level, index, 0, 1) < threshold && halfVariance(level, index, 2, 3) < threshold) {
                part[slot] = TileEncoder.PARTITION_H;
                return true;
            }
        }
        return false;
    }

    /**
     * Picks the reference and motion vector the inter partitioning compares against
     * ({@code setup_planes}) and returns the superblock's SAD against it.
     *
     * @param px the superblock column
     * @param py the superblock row
     * @param srcOff the offset of the superblock in the source
     * @return the SAD
     */
    private int setupPlanes(int px, int py, int srcOff) {
        var f = frame;
        var src = f.source.data[0];
        var srcStride = f.source.stride[0];
        var ySadG = Integer.MAX_VALUE;
        var ySadAlt = Integer.MAX_VALUE;
        if (f.refEnabled(3) && sourceSadNonrd != ZERO_SAD && f.ref(3) != f.ref(0)) {
            ySadG = zeroSad(f.ref(3), src, srcOff, srcStride, px, py);
        }
        var useAlt = f.speed.useNonrdAltrefFrame;
        if (useAlt && f.refEnabled(6) && sourceSadNonrd != ZERO_SAD && f.ref(6) != f.ref(0)) {
            ySadAlt = zeroSad(f.ref(6), src, srcOff, srcStride, px, py);
        }
        var last = f.ref(0);
        var ySad = Integer.MAX_VALUE;
        partMv = 0;
        var est = f.speed.estimateMotionForVarPartition;
        if (est > 2 && sourceSadNonrd > MED_SAD) {
            est = 2;
        }
        var inside = px + 64 <= f.width && py + 64 <= f.height;
        if ((est == 1 || est == 2) && inside && sourceVariance != Integer.MAX_VALUE && sourceVariance > 100
                && sourceSadNonrd > LOW_SAD) {
            search.setBlock(f.rd, src, srcOff, srcStride, last, px, py, 64, 64, f.alignedWidth(), f.alignedHeight(), 0,
                    0, 0, 0);
            var mv = search.intProMotion(32, 32, px, py, f.width, f.height, sadOut);
            ySad = sadOut[1];
            partMv = mv;
        }
        if (ySad == Integer.MAX_VALUE) {
            ySad = zeroSad(last, src, srcOff, srcStride, px, py);
        }
        if (est >= 2 && (py > 0 || px > 0)) {
            ySad = evaluateNeighborMvs(px, py, srcOff, ySad, est);
        }
        if (ySadG < 0.9 * ySad && ySadG < ySadAlt) {
            refForPartition = 3;
            partMv = 0;
            ySad = ySadG;
            nonrdPruneRefFrameSearch = 0;
        } else if (ySadAlt < 0.9 * ySad && ySadAlt < ySadG) {
            refForPartition = 6;
            partMv = 0;
            ySad = ySadAlt;
            nonrdPruneRefFrameSearch = 0;
        } else {
            refForPartition = 0;
        }
        lastYSadG = ySadG;
        lastYSadAlt = ySadAlt;
        return ySad;
    }

    /**
     * The golden SAD of the last {@link #setupPlanes} call.
     */
    private int lastYSadG;

    /**
     * The altref SAD of the last {@link #setupPlanes} call.
     */
    private int lastYSadAlt;

    /**
     * Returns the SAD of the superblock against a reference at zero motion.
     *
     * @param ref the reference
     * @param src the source
     * @param srcOff the offset of the superblock in the source
     * @param srcStride the source stride
     * @param px the superblock column
     * @param py the superblock row
     * @return the SAD
     */
    private int zeroSad(RefFrame ref, short[] src, int srcOff, int srcStride, int px, int py) {
        var s = ref.stride[0];
        return dsp.sad(src, srcOff, srcStride, ref.padded[0], ref.origin[0] + py * s + px, s, 64, 64);
    }

    /**
     * Tests the motion vectors of the blocks above and left of the superblock
     * ({@code evaluate_neighbour_mvs}).
     *
     * @param px the superblock column
     * @param py the superblock row
     * @param srcOff the offset of the superblock in the source
     * @param ySad the SAD of the current estimate
     * @param est the motion estimation level
     * @return the SAD of the chosen vector
     */
    private int evaluateNeighborMvs(int px, int py, int srcOff, int ySad, int est) {
        if (est > 2 && sourceSadNonrd > MED_SAD) {
            return ySad;
        }
        var f = frame;
        var last = f.ref(0);
        var src = f.source.data[0];
        var srcStride = f.source.stride[0];
        var bestRow = (short) (partMv >> 16);
        var bestCol = (short) partMv;
        var multi = est > 2 && sourceSadNonrd > LOW_SAD ? 7 : 8;
        var aboveSad = Integer.MAX_VALUE;
        var leftSad = Integer.MAX_VALUE;
        var aboveMv = 0;
        var leftMv = 0;
        search.setBlock(f.rd, src, srcOff, srcStride, last, px, py, 64, 64, f.alignedWidth(), f.alignedHeight(), 0, 0,
                0, 0);
        var above = tile.neighborMv(true);
        if (above != Integer.MIN_VALUE) {
            var r = Math.clamp((short) (above >> 16) >> 3, search.rowMin, search.rowMax);
            var c = Math.clamp((short) above >> 3, search.colMin, search.colMax);
            aboveMv = r << 16 | (c & 0xFFFF);
            if (Math.abs(r - bestRow) + Math.abs(c - bestCol) > 0) {
                aboveSad = search.sadAt(r, c);
            }
        }
        var left = tile.neighborMv(false);
        if (left != Integer.MIN_VALUE) {
            var r = Math.clamp((short) (left >> 16) >> 3, search.rowMin, search.rowMax);
            var c = Math.clamp((short) left >> 3, search.colMin, search.colMax);
            leftMv = r << 16 | (c & 0xFFFF);
            var ar = (short) (aboveMv >> 16);
            var ac = (short) aboveMv;
            if (Math.abs(r - bestRow) + Math.abs(c - bestCol) > 0 && Math.abs(r - ar) + Math.abs(c - ac) > 0) {
                leftSad = search.sadAt(r, c);
            }
        }
        if (aboveSad < ((multi * (long) ySad) >> 3) && aboveSad < leftSad) {
            ySad = aboveSad;
            partMv = aboveMv;
        }
        if (leftSad < ((multi * (long) ySad) >> 3) && leftSad < aboveSad) {
            ySad = leftSad;
            partMv = leftMv;
        }
        return ySad;
    }

    /**
     * Builds the luma prediction of the superblock at the partitioning vector with the bilinear
     * filter.
     *
     * @param ref the reference
     * @param px the superblock column
     * @param py the superblock row
     */
    private void predictSb(RefFrame ref, int px, int py) {
        var row = (short) (partMv >> 16);
        var col = (short) partMv;
        var s = ref.stride[0];
        var off = ref.origin[0] + (py + row) * s + px + col;
        McDsp.select().mc(McDsp.FILTER_2D_BILINEAR, pred, 0, 64, ref.padded[0], off, s, 64, 64, 0, 0, mcScratch, 255);
    }

    /**
     * Sets the color sensitivity of the superblock from its chroma SADs ({@code chroma_check}).
     *
     * @param px the superblock column
     * @param py the superblock row
     * @param ySad the luma SAD against the partitioning reference
     * @param zeroMotion whether the partitioning vector is zero
     */
    private void chromaCheck(int px, int py, int ySad, boolean zeroMotion) {
        var f = frame;
        var shiftUpper = 1;
        var shiftLower = 3;
        var facUv = (long) f.width * f.height >= 1920L * 1080 ? 3 : 5;
        if (sourceSadNonrd >= MED_SAD && sourceVariance != Integer.MAX_VALUE && sourceVariance > 500
                && (long) f.width * f.height >= 640L * 360) {
            shiftUpper = 2;
            shiftLower = sourceSadNonrd > MED_SAD ? 5 : 4;
        }
        for (var pl = 1; pl < 3; pl++) {
            var src = f.source.data[pl];
            var srcStride = f.source.stride[1];
            var srcOff = (py >> 1) * srcStride + (px >> 1);
            int uvSad;
            var refP = zeroMotion ? f.ref(0) : f.ref(refForPartition);
            var s = refP.stride[pl];
            var row = zeroMotion ? 0 : (short) (partMv >> 16) >> 1;
            var col = zeroMotion ? 0 : (short) partMv >> 1;
            uvSad = dsp.sad(src, srcOff, srcStride, refP.padded[pl],
                    refP.origin[pl] + ((py >> 1) + row) * s + (px >> 1) + col, s, 32, 32);
            var uvSadG = 0;
            var uvSadAlt = 0;
            if (lastYSadG != Integer.MAX_VALUE) {
                var g = f.ref(3);
                var gs = g.stride[pl];
                uvSadG = dsp.sad(src, srcOff, srcStride, g.padded[pl], g.origin[pl] + (py >> 1) * gs + (px >> 1), gs, 32,
                        32);
            }
            if (lastYSadAlt != Integer.MAX_VALUE) {
                var a = f.ref(6);
                var as = a.stride[pl];
                uvSadAlt = dsp.sad(src, srcOff, srcStride, a.padded[pl], a.origin[pl] + (py >> 1) * as + (px >> 1), as,
                        32, 32);
            }
            if (uvSad > (ySad >> shiftUpper)) {
                colorSensitivity[pl - 1] = 1;
            } else if (uvSad < (ySad >> shiftLower)) {
                colorSensitivity[pl - 1] = 0;
            } else {
                colorSensitivity[pl - 1] = 2;
            }
            colorSensitivityG[pl - 1] = lastYSadG != Integer.MAX_VALUE && uvSadG > lastYSadG / facUv;
            colorSensitivityAlt[pl - 1] = lastYSadAlt != Integer.MAX_VALUE && uvSadAlt > lastYSadAlt / facUv;
        }
    }

    /**
     * Records the partition of a node.
     *
     * @param level the node level
     * @param sbX the column in 4-sample units
     * @param sbY the row in 4-sample units
     * @param value the partition
     */
    void setPartition(int level, int sbX, int sbY, int value) {
        switch (level) {
            case 1 -> part[0] = value;
            case 2 -> part[1 + (sbY >> 3) * 2 + (sbX >> 3)] = value;
            case 3 -> part[5 + ((sbY >> 3) * 2 + (sbX >> 3)) * 4 + ((sbY >> 2) & 1) * 2 + ((sbX >> 2) & 1)] = value;
            default -> {
            }
        }
    }

    /**
     * Returns the partition of a node of the last chosen superblock.
     *
     * @param level the node level
     * @param sbX the node column within the superblock in 4-sample units
     * @param sbY the node row within the superblock in 4-sample units
     * @return the partition
     */
    int partition(int level, int sbX, int sbY) {
        return switch (level) {
            case 1 -> part[0];
            case 2 -> part[1 + (sbY >> 3) * 2 + (sbX >> 3)];
            case 3 -> part[5 + ((sbY >> 3) * 2 + (sbX >> 3)) * 4 + ((sbY >> 2) & 1) * 2 + ((sbX >> 2) & 1)];
            default -> TileEncoder.PARTITION_NONE;
        };
    }
}
