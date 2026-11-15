/* Ports libvpx 1.15.1 (vp9/encoder/vp9_encodeframe.c, vp9/encoder/vp9_rdopt.c, vp9/encoder/vp9_rd.c). Copyright (c)
   2010, The WebM Project authors; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.video.vp9.internal;

import java.util.Arrays;

import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.ALTREF_FRAME;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.BLOCK_16X16;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.BLOCK_64X64;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.BLOCK_8X8;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.D117_PRED;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.D135_PRED;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.D153_PRED;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.D207_PRED;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.D45_PRED;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.D63_PRED;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.DCT_DCT;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.DC_PRED;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.EIGHTTAP;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.GOLDEN_FRAME;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.H_PRED;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.INTRA_FRAME;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.LAST_FRAME;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.NEARESTMV;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.NEARMV;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.NEWMV;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.NO_REF_FRAME;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.PARTITION_HORZ;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.PARTITION_NONE;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.PARTITION_SPLIT;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.PARTITION_VERT;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.SWITCHABLE;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.SWITCHABLE_FILTERS;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.TM_PRED;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.TX_32X32;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.TX_4X4;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.TX_MODE_SELECT;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.V_PRED;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.ZEROMV;

/**
 * Codes the superblocks of a good-quality VP9 frame for a {@link Vp9RtCoder}: a recursive
 * rate-distortion search over the partitions of each superblock, and in each block a
 * rate-distortion search over the intra modes, the inter modes of every reference, the
 * interpolation filters and the transform sizes, the rate of every choice measured from the
 * tokens it codes; then the chosen blocks are coded with the coder's own reconstruction and
 * tokenization.
 *
 * <p>An 8x8 region may also be split into 4x4, 8x4 or 4x8 sub-blocks, coded as one block with a
 * mode, and for inter blocks a vector, per sub-block: their intra modes are searched sub-block by
 * sub-block, and their inter modes under each reference combination and interpolation filter,
 * each sub-block's nearest, near, zero and new vectors in turn.
 *
 * <p>The search takes its decisions on the 8-bit samples, the proxies of a high bit depth
 * frame, as the real-time decisions do. Instances belong to one coder and are not thread-safe.
 *
 * @implNote This implementation ports libvpx's {@code rd_pick_partition}, {@code rd_pick_sb_modes},
 * {@code encode_sb} ({@code vp9_encodeframe.c}), {@code vp9_rd_pick_intra_mode_sb},
 * {@code vp9_rd_pick_inter_mode_sb}, {@code vp9_rd_pick_inter_mode_sub8x8},
 * {@code rd_pick_best_sub8x8_mode}, {@code rd_pick_intra_sub_8x8_y_mode},
 * {@code rd_pick_intra4x4block}, {@code encode_inter_mb_segment}, {@code handle_inter_mode},
 * {@code single_motion_search}, {@code joint_motion_search}, {@code super_block_yrd},
 * {@code super_block_uvrd}, {@code block_rd_txfm}, {@code dist_block} and {@code cost_coeffs}
 * ({@code vp9_rdopt.c}) with the trellis quantization of {@code do_trellis_opt}, without the
 * neural network partition pruning.
 */
final class Vp9RdCoder {

    /**
     * The number of modes the inter mode search orders ({@code MAX_MODES}).
     */
    static final int MAX_MODES = 30;

    /**
     * The index of the last new motion vector mode in {@link #MODE_ORDER}, below which no mode
     * threshold applies ({@code LAST_NEW_MV_INDEX}).
     */
    private static final int LAST_NEW_MV_INDEX = 6;

    /**
     * The index from which the early termination may stop the search ({@code MIN_EARLY_TERM_INDEX}).
     */
    private static final int MIN_EARLY_TERM_INDEX = 3;

    /**
     * The divisor of the rate of a discounted new motion vector ({@code NEW_MV_DISCOUNT_FACTOR}).
     */
    private static final int NEW_MV_DISCOUNT_FACTOR = 8;

    /**
     * The order the inter mode search tests the modes in, three entries per mode: the mode, the
     * first reference and the second reference ({@code vp9_mode_order}).
     */
    static final int[] MODE_ORDER = {
            NEARESTMV, LAST_FRAME, NO_REF_FRAME,
            NEARESTMV, ALTREF_FRAME, NO_REF_FRAME,
            NEARESTMV, GOLDEN_FRAME, NO_REF_FRAME,
            DC_PRED, INTRA_FRAME, NO_REF_FRAME,
            NEWMV, LAST_FRAME, NO_REF_FRAME,
            NEWMV, ALTREF_FRAME, NO_REF_FRAME,
            NEWMV, GOLDEN_FRAME, NO_REF_FRAME,
            NEARMV, LAST_FRAME, NO_REF_FRAME,
            NEARMV, ALTREF_FRAME, NO_REF_FRAME,
            NEARMV, GOLDEN_FRAME, NO_REF_FRAME,
            ZEROMV, LAST_FRAME, NO_REF_FRAME,
            ZEROMV, GOLDEN_FRAME, NO_REF_FRAME,
            ZEROMV, ALTREF_FRAME, NO_REF_FRAME,
            NEARESTMV, LAST_FRAME, ALTREF_FRAME,
            NEARESTMV, GOLDEN_FRAME, ALTREF_FRAME,
            TM_PRED, INTRA_FRAME, NO_REF_FRAME,
            NEARMV, LAST_FRAME, ALTREF_FRAME,
            NEWMV, LAST_FRAME, ALTREF_FRAME,
            NEARMV, GOLDEN_FRAME, ALTREF_FRAME,
            NEWMV, GOLDEN_FRAME, ALTREF_FRAME,
            ZEROMV, LAST_FRAME, ALTREF_FRAME,
            ZEROMV, GOLDEN_FRAME, ALTREF_FRAME,
            H_PRED, INTRA_FRAME, NO_REF_FRAME,
            V_PRED, INTRA_FRAME, NO_REF_FRAME,
            D135_PRED, INTRA_FRAME, NO_REF_FRAME,
            D207_PRED, INTRA_FRAME, NO_REF_FRAME,
            D153_PRED, INTRA_FRAME, NO_REF_FRAME,
            D63_PRED, INTRA_FRAME, NO_REF_FRAME,
            D117_PRED, INTRA_FRAME, NO_REF_FRAME,
            D45_PRED, INTRA_FRAME, NO_REF_FRAME
    };

    /**
     * The threshold multiplier of each mode of {@link #MODE_ORDER} ({@code vp9_set_rd_speed_thresholds}
     * with the adaptive thresholds).
     */
    static final int[] THRESH_MULT = {
            300, 300, 300, 1000, 1000, 1000, 1000, 1000, 1000, 1000,
            2000, 2000, 2000, 1000, 1000, 1000, 1500, 2000, 1500, 2000,
            2500, 2500, 2000, 2000, 2500, 2500, 2500, 2500, 2500, 2500
    };

    /**
     * The increment of an adaptive threshold factor after a frame did not pick its mode
     * ({@code RD_THRESH_INC}).
     */
    private static final int RD_THRESH_INC = 1;

    /**
     * The largest adaptive threshold factor per level ({@code RD_THRESH_MAX_FACT}).
     */
    private static final int RD_THRESH_MAX_FACT = 64;

    /**
     * The references whose modes {@link #MODE_ORDER} skips from {@link Vp9GoodSpeed#modeSkipStart}
     * on when the best mode so far predicts from a given reference ({@code LAST_FRAME_MODE_MASK}
     * and its siblings), by reference.
     */
    private static final int[] REF_MODE_MASK = {
            0,
            (1 << GOLDEN_FRAME) | (1 << ALTREF_FRAME) | (1 << INTRA_FRAME),
            (1 << LAST_FRAME) | (1 << ALTREF_FRAME) | (1 << INTRA_FRAME),
            (1 << LAST_FRAME) | (1 << GOLDEN_FRAME) | (1 << INTRA_FRAME)
    };

    /**
     * The smallest block of the relaxed partition range of each block size, by the smallest size
     * the neighbors used ({@code min_partition_size}).
     */
    private static final int[] RELAXED_MIN = {0, 0, 0, 0, 0, 0, 3, 3, 3, 6, 6, 6, 6};

    /**
     * The largest block of the relaxed partition range of each block size, by the largest size
     * the neighbors used ({@code max_partition_size}).
     */
    private static final int[] RELAXED_MAX = {3, 6, 6, 6, 9, 9, 9, 12, 12, 12, 12, 12, 12};

    /**
     * The largest square block size no larger than each block size ({@code next_square_size}).
     */
    private static final int[] NEXT_SQUARE = {0, 0, 0, 3, 3, 3, 6, 6, 6, 9, 9, 9, 12};

    /**
     * The smallest block size the partition search of the current superblock tests.
     */
    private int minPartition;

    /**
     * The largest block size the partition search of the current superblock tests.
     */
    private int maxPartition;

    /**
     * A 64x64 block of mid-gray samples, against which the source variance is measured.
     */
    private static final byte[] FLAT = flat();

    /**
     * The decision for one block of the partition search.
     */
    static final class Choice {
        /**
         * The luma prediction mode.
         */
        int mode;

        /**
         * The chroma intra mode.
         */
        int uvMode;

        /**
         * The first reference, {@link Vp9Constants#INTRA_FRAME} for an intra block.
         */
        int ref0;

        /**
         * The second reference, or {@link Vp9Constants#NO_REF_FRAME}.
         */
        int ref1;

        /**
         * The packed motion vector of the first reference.
         */
        int mv0;

        /**
         * The packed motion vector of the second reference.
         */
        int mv1;

        /**
         * The interpolation filter.
         */
        int interpFilter;

        /**
         * The transform size.
         */
        int txSize;

        /**
         * Whether the block is coded without residual.
         */
        boolean skip;

        /**
         * The rate of the block in cost units, or {@link Integer#MAX_VALUE} when no mode beat the
         * budget.
         */
        int rate;

        /**
         * The distortion of the block.
         */
        long dist;

        /**
         * The rate-distortion cost, or {@link Long#MAX_VALUE}.
         */
        long rd;

        /**
         * The modes of the four 4x4 sub-blocks of a block smaller than 8x8.
         */
        final int[] subModes = new int[4];

        /**
         * The first-reference vectors of the four sub-blocks, packed.
         */
        final int[] subMv0 = new int[4];

        /**
         * The second-reference vectors of the four sub-blocks, packed.
         */
        final int[] subMv1 = new int[4];

        /**
         * The interpolation filter the search of a block smaller than 8x8 starts from, the filter
         * of the whole 8x8 block ({@code pred_interp_filter}).
         */
        int predInterpFilter = EIGHTTAP;

        /**
         * Copies the block's mode information into a mode information object.
         *
         * @param m the target
         */
        void apply(ModeInfo m) {
            m.mode = mode;
            m.uvMode = uvMode;
            m.ref0 = ref0;
            m.ref1 = ref1;
            m.mv0 = mv0;
            m.mv1 = mv1;
            m.interpFilter = interpFilter;
            m.txSize = txSize;
            System.arraycopy(subModes, 0, m.subModes, 0, 4);
            System.arraycopy(subMv0, 0, m.subMv0, 0, 4);
            System.arraycopy(subMv1, 0, m.subMv1, 0, 4);
        }

        /**
         * Copies a mode information object into this decision.
         *
         * @param m the source
         */
        void capture(ModeInfo m) {
            mode = m.mode;
            uvMode = m.uvMode;
            ref0 = m.ref0;
            ref1 = m.ref1;
            mv0 = m.mv0;
            mv1 = m.mv1;
            interpFilter = m.interpFilter;
            txSize = m.txSize;
            System.arraycopy(m.subModes, 0, subModes, 0, 4);
            System.arraycopy(m.subMv0, 0, subMv0, 0, 4);
            System.arraycopy(m.subMv1, 0, subMv1, 0, 4);
        }
    }

    /**
     * One square region of the partition search with the decisions of each partition.
     */
    static final class Node {
        /**
         * The partition chosen for the region.
         */
        int partitioning;

        /**
         * The region's index among its parent's four quarters.
         */
        int index;

        /**
         * The decision of the whole region.
         */
        final Choice none = new Choice();

        /**
         * The decisions of the two horizontal halves.
         */
        final Choice[] horizontal = {new Choice(), new Choice()};

        /**
         * The decisions of the two vertical halves.
         */
        final Choice[] vertical = {new Choice(), new Choice()};

        /**
         * The four quarters, or {@code null} for an 8x8 region.
         */
        final Node[] split;

        /**
         * The decision of an 8x8 region split into 4x4 blocks, one block of size
         * {@code BLOCK_4X4}.
         */
        final Choice leafSplit = new Choice();

        /**
         * The predicted vectors of each reference after the region's whole-block search, from
         * which its partitions start ({@code store_pred_mv}, {@code load_pred_mv}).
         */
        final int[] predMv = new int[4];

        /**
         * Creates a region of a block size and its quarters down to 8x8.
         *
         * @param bsize the square block size
         */
        Node(int bsize) {
            if (bsize > BLOCK_8X8) {
                var sub = Vp9Constants.SUBSIZE[PARTITION_SPLIT * 13 + bsize];
                split = new Node[4];
                for (var i = 0; i < 4; i++) {
                    split[i] = new Node(sub);
                    split[i].index = i;
                }
            } else {
                split = null;
            }
        }
    }

    /**
     * The coder whose blocks the search codes.
     */
    private final Vp9RtCoder c;

    /**
     * The encoder of the frame.
     */
    private final Vp9RtEncoder enc;

    /**
     * The partition search tree of a superblock.
     */
    private final Node root = new Node(BLOCK_64X64);

    /**
     * The saved above nonzero contexts of each search depth, 16 per plane.
     */
    private final byte[][] savedAbove = new byte[5][48];

    /**
     * The saved left nonzero contexts of each search depth.
     */
    private final byte[][] savedLeft = new byte[5][48];

    /**
     * The saved above partition contexts of each search depth.
     */
    private final byte[][] savedAbovePartition = new byte[5][8];

    /**
     * The saved left partition contexts of each search depth.
     */
    private final byte[][] savedLeftPartition = new byte[5][8];

    /**
     * The above nonzero contexts of the plane being measured, one per transform column.
     */
    private final byte[] tAbove = new byte[16];

    /**
     * The left nonzero contexts of the plane being measured, one per transform row.
     */
    private final byte[] tLeft = new byte[16];

    /**
     * The energy class of each coefficient of the transform block being costed.
     */
    private final byte[] tokenCache = new byte[1024];

    /**
     * The interpolation filter each single reference mode chose, {@code [mode - NEARESTMV][ref]}.
     */
    private final int[] singleFilter = new int[4 * 4];

    /**
     * The candidate vectors of each mode and reference of the current block,
     * {@code [mode - NEARESTMV][ref]}.
     */
    private final int[] frameMv = new int[4 * 4];

    /**
     * Whether each reference is usable in the current block.
     */
    private final boolean[] refUsable = new boolean[4];

    /**
     * The inter mode context of each reference of the current block.
     */
    private final int[] refModeContext = new int[4];

    /**
     * The best predicted vector of each reference of the current block, against which new
     * vectors are coded.
     */
    private final int[] refBestMv = new int[4];

    /**
     * The vector of each reference's single reference new motion vector search, which the joint
     * search of a compound block starts from ({@code single_newmv}).
     */
    private final int[] singleNewMv = new int[4];

    /**
     * The prediction from the other reference of a joint motion search.
     */
    private final byte[] secondPred = new byte[64 * 64];

    /**
     * Scratch memory of the second prediction.
     */
    private final byte[] secondScratch = new byte[Vp9Dsp.CONVOLVE_SCRATCH];

    /**
     * The vectors of the two references after each iteration of a joint motion search.
     */
    private final int[] iterMvs = new int[2 * MAX_JOINT_ITERS];

    /**
     * The most iterations of a joint motion search ({@code MAX_JOINT_MV_SEARCH_ITERS}).
     */
    private static final int MAX_JOINT_ITERS = 4;

    /**
     * The search range of the full-pixel refinement of a joint motion search.
     */
    private static final int JOINT_SEARCH_RANGE = 3;

    /**
     * The largest candidate vector component of each reference, for the motion search range.
     */
    private final int[] refMaxMv = new int[4];

    /**
     * The chroma intra results of each chroma transform size in the current block: rate,
     * token-only rate, distortion, skippable and mode, five entries per size.
     */
    private final long[] uvIntra = new long[4 * 5];

    /**
     * The cached chroma intra results' validity by transform size.
     */
    private final boolean[] uvIntraDone = new boolean[4];

    /**
     * The rate of the last plane or block measure, or {@link Integer#MAX_VALUE} when it went over
     * its budget.
     */
    private int rdRate;

    /**
     * The distortion of the last measure.
     */
    private long rdDist;

    /**
     * The sum of squared residuals of the last measure.
     */
    private long rdSse;

    /**
     * Whether every transform block of the last measure quantized to zero.
     */
    private boolean rdSkippable;

    /**
     * The source variance per sample of the current block.
     */
    private long sourceVariance;

    /**
     * The natural logarithm of one plus the variance of the current block's source luma, scaled
     * to 256 samples ({@code log_block_src_var}).
     */
    private double logBlockSrcVar;

    /**
     * Whether the distortion of the current block is measured in the transform domain
     * ({@code block_tx_domain}).
     */
    private boolean blockTxDomain;

    /**
     * The rate the partition search returned.
     */
    private int resultRate;

    /**
     * The distortion the partition search returned.
     */
    private long resultDist;

    /**
     * The adaptive mode threshold factors of the row being coded, {@code [block size][mode]}.
     */
    private int[] threshFact;

    /**
     * Creates the search of a coder.
     *
     * @param coder the coder whose superblocks the search codes
     */
    Vp9RdCoder(Vp9RtCoder coder) {
        this.c = coder;
        this.enc = coder.enc;
    }

    /**
     * Builds the mid-gray block.
     *
     * @return the samples
     */
    private static byte[] flat() {
        var block = new byte[64 * 64 + 64];
        Arrays.fill(block, (byte) 128);
        return block;
    }

    /**
     * Searches and codes one superblock of the current row.
     *
     * @param row the superblock's 8x8 row
     * @param col the superblock's 8x8 column
     * @param threshFact the adaptive threshold factors of the row
     */
    void codeSuperblock(int row, int col, int[] threshFact) {
        this.threshFact = threshFact;
        Arrays.fill(c.predMv, Vp9RtCoder.INVALID_PRED_MV);
        minPartition = Vp9Constants.BLOCK_4X4;
        maxPartition = BLOCK_64X64;
        if (enc.goodSpeed.autoMinMaxPartition) {
            autoPartitionRange(row, col);
        }
        pickPartition(row, col, BLOCK_64X64, Integer.MAX_VALUE, Long.MAX_VALUE, root, 0);
        encodeTree(row, col, BLOCK_64X64, root, true);
    }

    /**
     * Narrows the partition search of a superblock to the block sizes around those the
     * superblocks on its left and above used ({@code rd_auto_partition_range} with
     * {@code RELAXED_NEIGHBORING_MIN_MAX}).
     *
     * @implNote This implementation does not look at the superblock's blocks in the previous
     * frame, whose block sizes it does not keep.
     *
     * @param row the superblock's 8x8 row
     * @param col the superblock's 8x8 column
     */
    private void autoPartitionRange(int row, int col) {
        var left = col > c.miColStart;
        var above = row > 0;
        var minSize = Vp9Constants.BLOCK_4X4;
        var maxSize = BLOCK_64X64;
        if (left || above) {
            minSize = BLOCK_64X64;
            maxSize = Vp9Constants.BLOCK_4X4;
            var grid = enc.miGrid;
            var miCols = enc.miCols;
            if (left) {
                for (var y = row; y < Math.min(row + 8, enc.miRows); y++) {
                    for (var x = col - 8; x < col; x++) {
                        var size = grid[y * miCols + x].sbType;
                        minSize = Math.min(minSize, size);
                        maxSize = Math.max(maxSize, size);
                    }
                }
            }
            if (above) {
                for (var y = row - 8; y < row; y++) {
                    for (var x = col; x < Math.min(col + 8, enc.miCols); x++) {
                        var size = grid[y * miCols + x].sbType;
                        minSize = Math.min(minSize, size);
                        maxSize = Math.max(maxSize, size);
                    }
                }
            }
            minSize = RELAXED_MIN[minSize];
            maxSize = RELAXED_MAX[maxSize];
        }
        var rows = enc.miRows - row;
        var cols = enc.miCols - col;
        while (maxSize > 2 && (Vp9RtCoder.NUM_8X8_HIGH[maxSize] > rows || Vp9RtCoder.NUM_8X8_WIDE[maxSize] > cols)) {
            maxSize -= 3;
        }
        minSize = Math.min(enc.goodSpeed.autoPartitionMinLimit, Math.min(minSize, maxSize));
        if (enc.goodSpeed.useSquarePartitionOnly && NEXT_SQUARE[maxSize] < minSize) {
            minSize = NEXT_SQUARE[maxSize];
        }
        minPartition = minSize;
        maxPartition = maxSize;
    }

    /**
     * Returns the rate-distortion cost of a rate and a distortion, saturating ({@code RDCOST}).
     *
     * @param rate the rate, or {@link Integer#MAX_VALUE} for none
     * @param dist the distortion
     * @return the cost, or {@link Long#MAX_VALUE}
     */
    private long rdCost(long rate, long dist) {
        if (rate == Integer.MAX_VALUE || dist == Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        return Vp9RtCoder.rdCost(c.rdmult, rate, dist);
    }

    /**
     * Searches the partitions of a square region and leaves the best's rate and distortion in
     * {@link #resultRate} and {@link #resultDist} ({@code rd_pick_partition}).
     *
     * @param row the region's 8x8 row
     * @param col the region's 8x8 column
     * @param bsize the square block size
     * @param bestRate the rate of the best decision so far, or {@link Integer#MAX_VALUE}
     * @param bestDist the distortion of the best decision so far
     * @param node the region's search node
     * @param depth the recursion depth, 0 for the superblock
     * @return whether a decision beat the budget
     */
    private boolean pickPartition(int row, int col, int bsize, int bestRate, long bestDist, Node node, int depth) {
        var miRows = enc.miRows;
        var miCols = enc.miCols;
        var sf = enc.goodSpeed;
        var miStep = Vp9RtCoder.NUM_8X8_WIDE[bsize] / 2;
        var forceHorzSplit = row + miStep >= miRows;
        var forceVertSplit = col + miStep >= miCols;
        var ssX = enc.ssX;
        var ssY = enc.ssY;
        var noneAllowed = !forceHorzSplit && !forceVertSplit;
        var horzAllowed = !forceVertSplit && ssY <= ssX && bsize >= BLOCK_8X8;
        var vertAllowed = !forceHorzSplit && ssX <= ssY && bsize >= BLOCK_8X8;
        var doSplit = bsize >= BLOCK_8X8;
        var doRect = true;
        var pl = c.partitionContext(row, col, bsize);
        var costs = enc.rdCosts;
        var bwl = Vp9RtCoder.B_WIDTH_LOG2[bsize];
        var breakoutDist = sf.breakoutDist >> (8 - 2 * bwl);
        var breakoutRate = sf.breakoutRate * (2 * bwl + 4);
        if (sf.autoMinMaxPartition) {
            var minSize = minPartition;
            var maxSize = maxPartition;
            noneAllowed &= bsize <= maxSize;
            horzAllowed &= (bsize <= maxSize && bsize > minSize) || forceHorzSplit;
            vertAllowed &= (bsize <= maxSize && bsize > minSize) || forceVertSplit;
            doSplit &= bsize > minSize;
        }
        if (sf.useSquarePartitionOnly && (bsize > sf.squareOnlyHigh || bsize < sf.squareOnlyLow)) {
            horzAllowed &= forceHorzSplit;
            vertAllowed &= forceVertSplit;
        }
        horzAllowed &= enc.uvBsize(Math.max(BLOCK_8X8, Vp9Constants.SUBSIZE[PARTITION_HORZ * 13 + bsize]))
                != Vp9Constants.BLOCK_INVALID;
        vertAllowed &= enc.uvBsize(Math.max(BLOCK_8X8, Vp9Constants.SUBSIZE[PARTITION_VERT * 13 + bsize]))
                != Vp9Constants.BLOCK_INVALID;
        saveContext(row, col, bsize, depth);
        var bestRd = rdCost(bestRate, bestDist);
        var found = false;
        node.partitioning = PARTITION_NONE;
        if (noneAllowed) {
            var choice = node.none;
            pickBlock(row, col, bsize, choice, bestRate, bestDist);
            if (choice.rate != Integer.MAX_VALUE) {
                var rate = choice.rate + costs.partition[pl * 4 + PARTITION_NONE];
                var rd = rdCost(rate, choice.dist);
                if (rd < bestRd) {
                    bestRd = rd;
                    bestRate = rate;
                    bestDist = choice.dist;
                    found = true;
                    node.partitioning = PARTITION_NONE;
                    if ((doSplit || doRect) && !enc.lossless && choice.skip
                            && (bestDist < (breakoutDist >> 2) || (bestDist < breakoutDist && bestRate < breakoutRate))) {
                        doSplit = false;
                        doRect = false;
                    }
                }
            }
            restoreContext(row, col, bsize, depth);
            System.arraycopy(c.predMv, 0, node.predMv, 0, 4);
        } else {
            Arrays.fill(node.predMv, 0);
        }
        var predFilter = EIGHTTAP;
        if (noneAllowed && node.none.rate != Integer.MAX_VALUE && node.none.interpFilter != SWITCHABLE_FILTERS) {
            predFilter = node.none.interpFilter;
        }
        var adaptiveFilter = sf.adaptivePredInterpFilter > 0 && noneAllowed;
        if (doSplit && bsize == BLOCK_8X8) {
            System.arraycopy(node.predMv, 0, c.predMv, 0, 4);
            var leaf = node.leafSplit;
            if (adaptiveFilter) {
                leaf.predInterpFilter = predFilter;
            }
            pickBlock(row, col, Vp9Constants.BLOCK_4X4, leaf, bestRate, bestDist);
            if (leaf.rate != Integer.MAX_VALUE) {
                var sumRate = (long) leaf.rate + costs.partition[pl * 4 + PARTITION_SPLIT];
                var sumRd = rdCost(sumRate, leaf.dist);
                if (rdCost(leaf.rate, leaf.dist) < bestRd && sumRd < bestRd) {
                    bestRd = sumRd;
                    bestRate = (int) sumRate;
                    bestDist = leaf.dist;
                    found = true;
                    node.partitioning = PARTITION_SPLIT;
                    if (!enc.lossless && (bestDist < (breakoutDist >> 2)
                            || (bestDist < breakoutDist && bestRate < breakoutRate))) {
                        doRect = false;
                    }
                } else if (rdCost(leaf.rate, leaf.dist) >= bestRd && sf.lessRectangularCheck
                        && (bsize > sf.squareOnlyHigh || bestDist < breakoutDist)) {
                    doRect &= !noneAllowed;
                }
            } else if (sf.lessRectangularCheck && (bsize > sf.squareOnlyHigh || bestDist < breakoutDist)) {
                doRect &= !noneAllowed;
            }
            restoreContext(row, col, bsize, depth);
        } else if (doSplit) {
            System.arraycopy(node.predMv, 0, c.predMv, 0, 4);
            var subsize = Vp9Constants.SUBSIZE[PARTITION_SPLIT * 13 + bsize];
            var sumRate = 0L;
            var sumDist = 0L;
            var sumRd = 0L;
            var i = 0;
            for (; i < 4 && sumRd < bestRd; i++) {
                var xIdx = (i & 1) * miStep;
                var yIdx = (i >> 1) * miStep;
                if (row + yIdx >= miRows || col + xIdx >= miCols) {
                    continue;
                }
                var subBestRate = bestRate == Integer.MAX_VALUE ? Integer.MAX_VALUE
                        : (int) Math.max(0, bestRate - sumRate);
                var subBestDist = bestRate == Integer.MAX_VALUE ? Long.MAX_VALUE : bestDist - sumDist;
                if (!pickPartition(row + yIdx, col + xIdx, subsize, subBestRate, subBestDist, node.split[i],
                        depth + 1)) {
                    sumRd = Long.MAX_VALUE;
                    break;
                }
                sumRate += resultRate;
                sumDist += resultDist;
                sumRd = rdCost(sumRate, sumDist);
            }
            if (sumRd < bestRd && i == 4) {
                sumRate += costs.partition[pl * 4 + PARTITION_SPLIT];
                sumRd = rdCost(sumRate, sumDist);
                if (sumRd < bestRd) {
                    bestRd = sumRd;
                    bestRate = (int) sumRate;
                    bestDist = sumDist;
                    found = true;
                    node.partitioning = PARTITION_SPLIT;
                    if (!enc.lossless && (bestDist < (breakoutDist >> 2)
                            || (bestDist < breakoutDist && bestRate < breakoutRate))) {
                        doRect = false;
                    }
                }
            } else if (sf.lessRectangularCheck && (bsize > sf.squareOnlyHigh || bestDist < breakoutDist)) {
                doRect &= !noneAllowed;
            }
            restoreContext(row, col, bsize, depth);
        }
        if (horzAllowed && doRect) {
            System.arraycopy(node.predMv, 0, c.predMv, 0, 4);
            var subsize = Vp9Constants.SUBSIZE[PARTITION_HORZ * 13 + bsize];
            var partRate = costs.partition[pl * 4 + PARTITION_HORZ];
            var first = node.horizontal[0];
            if (adaptiveFilter && bsize == BLOCK_8X8) {
                first.predInterpFilter = predFilter;
            }
            pickBlock(row, col, subsize, first, bestRate == Integer.MAX_VALUE ? Integer.MAX_VALUE
                    : bestRate - partRate, bestDist);
            var sumRate = first.rate == Integer.MAX_VALUE ? Integer.MAX_VALUE : (long) first.rate + partRate;
            var sumDist = first.dist;
            var sumRd = rdCost(sumRate, sumDist);
            if (sumRd < bestRd && row + miStep < miRows && bsize > BLOCK_8X8) {
                encodeChoice(row, col, subsize, first, false);
                var second = node.horizontal[1];
                pickBlock(row + miStep, col, subsize, second, bestRate == Integer.MAX_VALUE ? Integer.MAX_VALUE
                        : (int) (bestRate - sumRate), bestDist - sumDist);
                if (second.rate == Integer.MAX_VALUE) {
                    sumRd = Long.MAX_VALUE;
                } else {
                    sumRate += second.rate;
                    sumDist += second.dist;
                    sumRd = rdCost(sumRate, sumDist);
                }
            }
            if (sumRd < bestRd) {
                bestRd = sumRd;
                bestRate = (int) sumRate;
                bestDist = sumDist;
                found = true;
                node.partitioning = PARTITION_HORZ;
                if (sf.lessRectangularCheck && bsize > sf.squareOnlyHigh) {
                    doRect = false;
                }
            }
            restoreContext(row, col, bsize, depth);
        }
        if (vertAllowed && doRect) {
            System.arraycopy(node.predMv, 0, c.predMv, 0, 4);
            var subsize = Vp9Constants.SUBSIZE[PARTITION_VERT * 13 + bsize];
            var partRate = costs.partition[pl * 4 + PARTITION_VERT];
            var first = node.vertical[0];
            if (adaptiveFilter && bsize == BLOCK_8X8) {
                first.predInterpFilter = predFilter;
            }
            pickBlock(row, col, subsize, first, bestRate == Integer.MAX_VALUE ? Integer.MAX_VALUE
                    : bestRate - partRate, bestDist);
            var sumRate = first.rate == Integer.MAX_VALUE ? Integer.MAX_VALUE : (long) first.rate + partRate;
            var sumDist = first.dist;
            var sumRd = rdCost(sumRate, sumDist);
            if (sumRd < bestRd && col + miStep < miCols && bsize > BLOCK_8X8) {
                encodeChoice(row, col, subsize, first, false);
                var second = node.vertical[1];
                pickBlock(row, col + miStep, subsize, second, bestRate == Integer.MAX_VALUE ? Integer.MAX_VALUE
                        : (int) (bestRate - sumRate), bestDist - sumDist);
                if (second.rate == Integer.MAX_VALUE) {
                    sumRd = Long.MAX_VALUE;
                } else {
                    sumRate += second.rate;
                    sumDist += second.dist;
                    sumRd = rdCost(sumRate, sumDist);
                }
            }
            if (sumRd < bestRd) {
                bestRd = sumRd;
                bestRate = (int) sumRate;
                bestDist = sumDist;
                found = true;
                node.partitioning = PARTITION_VERT;
            }
            restoreContext(row, col, bsize, depth);
        }
        if (bsize == BLOCK_64X64 && !found) {
            var choice = node.none;
            pickBlock(row, col, bsize, choice, Integer.MAX_VALUE, Long.MAX_VALUE);
            if (choice.rate != Integer.MAX_VALUE) {
                bestRate = choice.rate + costs.partition[pl * 4 + PARTITION_NONE];
                bestDist = choice.dist;
                found = true;
                node.partitioning = PARTITION_NONE;
            }
            restoreContext(row, col, bsize, depth);
        }
        resultRate = bestRate;
        resultDist = bestDist;
        if (found && bsize != BLOCK_64X64 && node.index != 3) {
            encodeTree(row, col, bsize, node, false);
        }
        return found;
    }

    /**
     * Saves the nonzero and partition contexts a region covers ({@code save_context}).
     *
     * @param row the region's 8x8 row
     * @param col the region's 8x8 column
     * @param bsize the square block size
     * @param depth the recursion depth, which selects the save slot
     */
    private void saveContext(int row, int col, int bsize, int depth) {
        copyContext(row, col, bsize, depth, true);
    }

    /**
     * Restores the contexts {@link #saveContext} saved ({@code restore_context}).
     *
     * @param row the region's 8x8 row
     * @param col the region's 8x8 column
     * @param bsize the square block size
     * @param depth the recursion depth
     */
    private void restoreContext(int row, int col, int bsize, int depth) {
        copyContext(row, col, bsize, depth, false);
    }

    /**
     * Copies the contexts a region covers to or from a save slot.
     *
     * @param row the region's 8x8 row
     * @param col the region's 8x8 column
     * @param bsize the square block size
     * @param depth the save slot
     * @param save whether to save rather than restore
     */
    private void copyContext(int row, int col, int bsize, int depth, boolean save) {
        var w4 = Vp9RtCoder.NUM_4X4_WIDE[bsize];
        var h4 = Vp9RtCoder.NUM_4X4_HIGH[bsize];
        var above = savedAbove[depth];
        var left = savedLeft[depth];
        for (var plane = 0; plane < 3; plane++) {
            var sx = plane == 0 ? 0 : enc.ssX;
            var sy = plane == 0 ? 0 : enc.ssY;
            var a = enc.aboveEntropy[plane];
            var l = c.leftEntropy[plane];
            var aOff = (col * 2) >> sx;
            var lOff = ((row & 7) * 2) >> sy;
            if (save) {
                System.arraycopy(a, aOff, above, plane * 16, w4 >> sx);
                System.arraycopy(l, lOff, left, plane * 16, h4 >> sy);
            } else {
                System.arraycopy(above, plane * 16, a, aOff, w4 >> sx);
                System.arraycopy(left, plane * 16, l, lOff, h4 >> sy);
            }
        }
        var mw = Vp9RtCoder.NUM_8X8_WIDE[bsize];
        if (save) {
            System.arraycopy(enc.abovePartition, col, savedAbovePartition[depth], 0, mw);
            System.arraycopy(c.leftPartition, row & 7, savedLeftPartition[depth], 0, mw);
        } else {
            System.arraycopy(savedAbovePartition[depth], 0, enc.abovePartition, col, mw);
            System.arraycopy(savedLeftPartition[depth], 0, c.leftPartition, row & 7, mw);
        }
    }

    /**
     * Codes a region along the decisions of its search node ({@code encode_sb}).
     *
     * @param row the region's 8x8 row
     * @param col the region's 8x8 column
     * @param bsize the square block size
     * @param node the region's search node
     * @param output whether the blocks are coded into the bitstream, or only reconstructed for
     * the search of the blocks that follow
     */
    private void encodeTree(int row, int col, int bsize, Node node, boolean output) {
        if (row >= enc.miRows || col >= enc.miCols) {
            return;
        }
        var hbs = Vp9RtCoder.NUM_8X8_WIDE[bsize] >> 1;
        var partition = node.partitioning;
        var subsize = Vp9Constants.SUBSIZE[partition * 13 + bsize];
        if (output) {
            var ctx = c.partitionContext(row, col, bsize);
            c.counts.counts[Vp9EncCounts.PARTITION + ctx * 4 + partition]++;
        }
        switch (partition) {
            case PARTITION_NONE -> encodeChoice(row, col, subsize, node.none, output);
            case PARTITION_VERT -> {
                encodeChoice(row, col, subsize, node.vertical[0], output);
                if (col + hbs < enc.miCols && bsize > BLOCK_8X8) {
                    encodeChoice(row, col + hbs, subsize, node.vertical[1], output);
                }
            }
            case PARTITION_HORZ -> {
                encodeChoice(row, col, subsize, node.horizontal[0], output);
                if (row + hbs < enc.miRows && bsize > BLOCK_8X8) {
                    encodeChoice(row + hbs, col, subsize, node.horizontal[1], output);
                }
            }
            default -> {
                if (bsize == BLOCK_8X8) {
                    encodeChoice(row, col, subsize, node.leafSplit, output);
                } else {
                    encodeTree(row, col, subsize, node.split[0], output);
                    encodeTree(row, col + hbs, subsize, node.split[1], output);
                    encodeTree(row + hbs, col, subsize, node.split[2], output);
                    encodeTree(row + hbs, col + hbs, subsize, node.split[3], output);
                }
            }
        }
        if (partition != PARTITION_SPLIT || bsize == BLOCK_8X8) {
            c.updatePartitionContext(row, col, subsize, bsize);
        }
    }

    /**
     * Codes one block along a decision: copies the modes into the block's mode information,
     * derives its motion vector candidates from the coded neighbors, reconstructs it and, when
     * coding into the bitstream, tokenizes it and counts its symbols ({@code encode_b}).
     *
     * @param row the block's 8x8 row
     * @param col the block's 8x8 column
     * @param bsize the block size
     * @param choice the decision
     * @param output whether the block is coded into the bitstream
     */
    private void encodeChoice(int row, int col, int bsize, Choice choice, boolean output) {
        c.setOffsets(row, col, bsize);
        c.setSegmentIndex(bsize);
        var m = c.mi;
        m.sbType = bsize;
        choice.apply(m);
        c.skipTxfm = 0;
        c.skip = choice.skip && m.isInter();
        c.skipEncode = false;
        var whole = bsize >= BLOCK_8X8;
        if (m.isInter()) {
            prepareReference(m.ref0, bsize);
            var nearest0 = frameMv[ref(NEARESTMV, m.ref0)];
            var near0 = frameMv[ref(NEARMV, m.ref0)];
            if (!whole) {
                m.mv0 = m.subMv0[3];
            } else if (m.mode == NEARESTMV) {
                m.mv0 = nearest0;
            } else if (m.mode == NEARMV) {
                m.mv0 = near0;
            } else if (m.mode == ZEROMV) {
                m.mv0 = 0;
            }
            c.refMvs[m.ref0 * 2] = nearest0;
            c.refMvs[m.ref0 * 2 + 1] = near0;
            c.modeContext[m.ref0] = refModeContext[m.ref0];
            if (m.hasSecondRef()) {
                prepareReference(m.ref1, bsize);
                var nearest1 = frameMv[ref(NEARESTMV, m.ref1)];
                var near1 = frameMv[ref(NEARMV, m.ref1)];
                if (!whole) {
                    m.mv1 = m.subMv1[3];
                } else if (m.mode == NEARESTMV) {
                    m.mv1 = nearest1;
                } else if (m.mode == NEARMV) {
                    m.mv1 = near1;
                } else if (m.mode == ZEROMV) {
                    m.mv1 = 0;
                }
                c.refMvs[m.ref1 * 2] = nearest1;
                c.refMvs[m.ref1 * 2 + 1] = near1;
            }
        }
        c.encodeChosenBlock(bsize, output);
    }

    /**
     * Returns the index of a mode and reference in {@link #frameMv} and {@link #singleFilter}.
     *
     * @param mode the inter mode
     * @param ref the reference
     * @return the index
     */
    private static int ref(int mode, int ref) {
        return (mode - NEARESTMV) * 4 + ref;
    }

    /**
     * Finds the motion vector candidates and the mode context of a reference for the current
     * block, as the decoder finds them, and measures the candidates as starts of the motion
     * search ({@code setup_buffer_inter}).
     *
     * @param ref the reference
     * @param bsize the block size
     */
    private void prepareReference(int ref, int bsize) {
        var pick = c.pick;
        pick.findMvRefs(ref, bsize, false);
        var allowHp = enc.allowHp;
        var nearest = Vp9RtPickMode.lowerPrecision(pick.mvList[0], allowHp);
        var near = Vp9RtPickMode.lowerPrecision(pick.mvList[1], allowHp);
        frameMv[ref(NEARESTMV, ref)] = nearest;
        frameMv[ref(NEARMV, ref)] = near;
        frameMv[ref(ZEROMV, ref)] = 0;
        refModeContext[ref] = c.modeContext[ref];
        refBestMv[ref] = nearest;
        refMaxMv[ref] = Math.max(Math.max(Math.abs(nearest >> 16), Math.abs((short) nearest)),
                Math.max(Math.abs(near >> 16), Math.abs((short) near))) >> 3;
        c.refMvs[ref * 2] = nearest;
        c.refMvs[ref * 2 + 1] = near;
    }

    /**
     * Searches the modes of one block and stores the best in a decision, or marks the decision
     * invalid when nothing beats the budget ({@code rd_pick_sb_modes}).
     *
     * @param row the block's 8x8 row
     * @param col the block's 8x8 column
     * @param bsize the block size
     * @param choice receives the decision
     * @param bestRate the budget's rate, or {@link Integer#MAX_VALUE} for none
     * @param bestDist the budget's distortion
     */
    private void pickBlock(int row, int col, int bsize, Choice choice, int bestRate, long bestDist) {
        c.setOffsets(row, col, bsize);
        c.setSegmentIndex(bsize);
        var m = c.mi;
        m.sbType = bsize;
        m.skip = 0;
        c.skipEncode = false;
        c.skipTxfm = 0;
        sourceVariance = perPixelVariance(bsize);
        var sf = enc.goodSpeed;
        if (sf.transformDomainThreshold > 0 || sf.trellisThreshold > 0) {
            logBlockSrcVar = StrictMath.log(blockVariance(bsize) + 1.0);
            blockTxDomain = sf.transformDomainDistortion && logBlockSrcVar >= sf.transformDomainThreshold;
        } else {
            logBlockSrcVar = 0;
            blockTxDomain = sf.transformDomainDistortion;
        }
        var bestRd = bestRate == Integer.MAX_VALUE ? Long.MAX_VALUE : rdCost(bestRate, bestDist);
        choice.rate = Integer.MAX_VALUE;
        choice.dist = Long.MAX_VALUE;
        choice.rd = Long.MAX_VALUE;
        if (bsize < BLOCK_8X8) {
            if (enc.keyFrame) {
                pickIntraSub8x8(bsize, choice, bestRd);
            } else {
                pickInterSub8x8(bsize, choice, bestRd);
            }
        } else if (enc.keyFrame) {
            pickIntraBlock(bsize, choice, bestRd);
        } else {
            pickInterBlock(bsize, choice, bestRd);
        }
    }

    /**
     * Returns the variance per sample of the current block's luma source
     * ({@code vp9_get_sby_perpixel_variance}).
     *
     * @param bsize the block size
     * @return the variance, rounded
     */
    private long perPixelVariance(int bsize) {
        var src = enc.source;
        var w = Vp9RtCoder.NUM_4X4_WIDE[bsize] * 4;
        var h = Vp9RtCoder.NUM_4X4_HIGH[bsize] * 4;
        var n = Vp9RtCoder.B_WIDTH_LOG2[bsize] + Vp9RtCoder.B_HEIGHT_LOG2[bsize] + 4;
        var packed = c.edsp.variance(src.planes[0], c.srcOff[0], src.strides[0], FLAT, 0, 64, w, h);
        var var = Vp9EncDsp.variance(packed, n);
        return (var + (1L << (n - 1))) >> n;
    }

    /**
     * Returns the variance of the visible source luma of a block scaled to 256 samples, the
     * complexity measure of {@code vp9_log_block_var} ({@code block_variance}).
     *
     * @param bsize the block size
     * @return the variance
     */
    private long blockVariance(int bsize) {
        var src = enc.source;
        var plane = src.planes[0];
        var stride = src.strides[0];
        var off = c.srcOff[0];
        var right = c.mbToRightEdge < 0 ? -c.mbToRightEdge >> 3 : 0;
        var bottom = c.mbToBottomEdge < 0 ? -c.mbToBottomEdge >> 3 : 0;
        var w = Vp9RtCoder.NUM_4X4_WIDE[bsize] * 4 - right;
        var h = Vp9RtCoder.NUM_4X4_HIGH[bsize] * 4 - bottom;
        long sum = 0;
        long sse = 0;
        for (var y = 0; y < h; y++) {
            var row = off + y * stride;
            for (var x = 0; x < w; x++) {
                var v = plane[row + x] & 0xFF;
                sum += v;
                sse += v * v;
            }
        }
        var n = (long) w * h;
        var variance = (sse - sum * sum / n) & 0xFFFFFFFFL;
        return 256 * variance / n;
    }

    /**
     * Searches the intra modes of a key frame block ({@code vp9_rd_pick_intra_mode_sb}).
     *
     * @param bsize the block size
     * @param choice receives the decision
     * @param bestRd the budget
     */
    private void pickIntraBlock(int bsize, Choice choice, long bestRd) {
        var m = c.mi;
        m.ref0 = INTRA_FRAME;
        m.ref1 = NO_REF_FRAME;
        m.interpFilter = SWITCHABLE_FILTERS;
        m.mv0 = Vp9RtCoder.INVALID_MV;
        m.mv1 = 0;
        var above = c.aboveMi == null || c.aboveMi.isInter() ? DC_PRED : c.aboveMi.yMode(2);
        var left = c.leftMi == null || c.leftMi.isInter() ? DC_PRED : c.leftMi.yMode(1);
        var costs = enc.rdCosts;
        var costBase = (above * 10 + left) * 10;
        var bestMode = DC_PRED;
        var bestTx = TX_4X4;
        var yRate = 0;
        var yRateTokens = 0;
        var yDist = 0L;
        var ySkip = false;
        var best = bestRd;
        for (var mode = DC_PRED; mode <= TM_PRED; mode++) {
            m.mode = mode;
            superBlockYrd(bsize, best);
            if (rdRate == Integer.MAX_VALUE) {
                continue;
            }
            var rate = rdRate + costs.kfYMode[costBase + mode];
            var rd = rdCost(rate, rdDist);
            if (rd < best) {
                best = rd;
                bestMode = mode;
                bestTx = m.txSize;
                yRate = rate;
                yRateTokens = rdRate;
                yDist = rdDist;
                ySkip = rdSkippable;
            }
        }
        if (best >= bestRd) {
            return;
        }
        m.mode = bestMode;
        m.txSize = bestTx;
        var maxUvTx = enc.uvTxSize(m);
        pickIntraUv(bsize, maxUvTx, false);
        var uvRate = (int) uvIntra[maxUvTx * 5];
        var uvRateTokens = (int) uvIntra[maxUvTx * 5 + 1];
        var uvDist = uvIntra[maxUvTx * 5 + 2];
        var uvSkip = uvIntra[maxUvTx * 5 + 3] != 0;
        m.uvMode = (int) uvIntra[maxUvTx * 5 + 4];
        int rate;
        if (ySkip && uvSkip) {
            rate = yRate + uvRate - yRateTokens - uvRateTokens + c.skipCost(1);
        } else {
            rate = yRate + uvRate + c.skipCost(0);
        }
        choice.capture(m);
        choice.skip = false;
        choice.rate = rate;
        choice.dist = yDist + uvDist;
        choice.rd = rdCost(rate, choice.dist);
    }

    /**
     * Searches the chroma intra modes of the current block and caches the best's rate, token-only
     * rate, distortion, skippability and mode for its chroma transform size
     * ({@code rd_pick_intra_sbuv_mode}).
     *
     * @param bsize the block size
     * @param uvTx the chroma transform size
     * @param interFrame whether the block is in an inter frame, whose chroma mode costs differ
     */
    private void pickIntraUv(int bsize, int uvTx, boolean interFrame) {
        var m = c.mi;
        var costs = enc.rdCosts;
        var mask = enc.goodSpeed.intraUvModeMask[uvTx];
        var best = Long.MAX_VALUE;
        var base = uvTx * 5;
        uvIntra[base] = Integer.MAX_VALUE;
        uvIntra[base + 4] = DC_PRED;
        for (var mode = DC_PRED; mode <= TM_PRED; mode++) {
            if ((mask & (1 << mode)) == 0) {
                continue;
            }
            m.uvMode = mode;
            superBlockUvrd(bsize, best);
            if (rdRate == Integer.MAX_VALUE) {
                continue;
            }
            var rate = rdRate + costs.uvMode[((interFrame ? 10 : 0) + m.mode) * 10 + mode];
            var rd = rdCost(rate, rdDist);
            if (rd < best) {
                best = rd;
                uvIntra[base] = rate;
                uvIntra[base + 1] = rdRate;
                uvIntra[base + 2] = rdDist;
                uvIntra[base + 3] = rdSkippable ? 1 : 0;
                uvIntra[base + 4] = mode;
            }
        }
        if (uvIntra[base] == Integer.MAX_VALUE) {
            m.uvMode = DC_PRED;
            superBlockUvrd(bsize, Long.MAX_VALUE);
            uvIntra[base] = rdRate + costs.uvMode[((interFrame ? 10 : 0) + m.mode) * 10 + DC_PRED];
            uvIntra[base + 1] = rdRate;
            uvIntra[base + 2] = rdDist;
            uvIntra[base + 3] = rdSkippable ? 1 : 0;
            uvIntra[base + 4] = DC_PRED;
        }
        m.uvMode = (int) uvIntra[base + 4];
        uvIntraDone[uvTx] = true;
    }

    /**
     * Measures the luma of the current block's mode at the transform size the speed allows, or
     * at the best of the sizes it searches, leaving the size in the mode information
     * ({@code super_block_yrd}).
     *
     * @param bsize the block size
     * @param bestRd the budget
     */
    private void superBlockYrd(int bsize, long bestRd) {
        var m = c.mi;
        var maxTx = Vp9Constants.MAX_TX_SIZE[bsize];
        var largest = Math.min(maxTx, Vp9Constants.TX_MODE_TO_BIGGEST[enc.txMode]);
        if (enc.goodSpeed.txSizeSearch == Vp9GoodSpeed.TX_LARGEST || enc.lossless || enc.txMode != TX_MODE_SELECT) {
            m.txSize = largest;
            txfmRdInPlane(0, bsize, m.txSize, bestRd);
            return;
        }
        var costs = enc.rdCosts;
        var ctx = Vp9PredContexts.txSize(c.aboveMi, c.leftMi, maxTx);
        var s0 = c.skipCost(0);
        var s1 = c.skipCost(1);
        var inter = m.isInter();
        var best = bestRd;
        var bestTx = maxTx;
        var bestRate = Integer.MAX_VALUE;
        var bestDist = Long.MAX_VALUE;
        var bestSse = Long.MAX_VALUE;
        var bestSkip = false;
        var lastRd = Long.MAX_VALUE;
        var endTx = Math.max(maxTx - 2, 0);
        if (bsize > Vp9Constants.BLOCK_32X32) {
            endTx = Math.min(endTx + 1, maxTx);
        }
        var resolved = false;
        var maxRate = Integer.MAX_VALUE;
        var maxDist = Long.MAX_VALUE;
        var maxSse = Long.MAX_VALUE;
        var maxSkip = false;
        for (var n = maxTx; n >= endTx; n--) {
            var txCost = maxTx == 0 ? 0 : costs.txSize[((maxTx - 1) * 2 + ctx) * 4 + n];
            txfmRdInPlane(0, bsize, n, best);
            var r0 = rdRate;
            if (n == maxTx) {
                maxRate = r0 == Integer.MAX_VALUE || (rdSkippable && inter) ? r0 : r0 + txCost;
                maxDist = rdDist;
                maxSse = rdSse;
                maxSkip = rdSkippable;
            }
            long rd;
            if (r0 == Integer.MAX_VALUE) {
                rd = Long.MAX_VALUE;
            } else if (rdSkippable) {
                rd = inter ? rdCost(s1, rdSse) : rdCost(s1 + txCost, rdSse);
            } else {
                rd = rdCost(r0 + txCost + s0, rdDist);
            }
            if (inter && !enc.lossless && !rdSkippable && r0 != Integer.MAX_VALUE) {
                rd = Math.min(rd, rdCost(s1, rdSse));
            }
            if (enc.goodSpeed.txSizeSearchBreakout && (rd == Long.MAX_VALUE || (n < maxTx && rd > lastRd)
                    || rdSkippable)) {
                break;
            }
            if (rd < best) {
                best = rd;
                bestTx = n;
                bestRate = rdSkippable && inter ? r0 : r0 + txCost;
                bestDist = rdDist;
                bestSse = rdSse;
                bestSkip = rdSkippable;
                resolved = true;
            }
            lastRd = rd;
        }
        if (!resolved) {
            // no size beat the budget, or the search broke out at the largest size (for instance because
            // its residual quantizes to nothing): the largest size's measures stand
            m.txSize = maxTx;
            rdRate = maxRate;
            rdDist = maxRate == Integer.MAX_VALUE ? Long.MAX_VALUE : maxDist;
            rdSse = maxRate == Integer.MAX_VALUE ? Long.MAX_VALUE : maxSse;
            rdSkippable = maxRate != Integer.MAX_VALUE && maxSkip;
            return;
        }
        m.txSize = bestTx;
        rdRate = bestRate;
        rdDist = bestDist;
        rdSse = bestSse;
        rdSkippable = bestSkip;
        if (bestRate == Integer.MAX_VALUE) {
            rdDist = Long.MAX_VALUE;
            rdSse = Long.MAX_VALUE;
        }
    }

    /**
     * Measures the two chroma planes of the current block's mode ({@code super_block_uvrd}).
     *
     * @param bsize the block size
     * @param bestRd the budget
     */
    private void superBlockUvrd(int bsize, long bestRd) {
        var m = c.mi;
        var uvTx = enc.uvTxSize(m);
        var rate = 0;
        var dist = 0L;
        var sse = 0L;
        var skippable = true;
        if (bestRd < 0) {
            rdRate = Integer.MAX_VALUE;
            rdDist = Long.MAX_VALUE;
            rdSse = Long.MAX_VALUE;
            rdSkippable = false;
            return;
        }
        if (m.isInter()) {
            subtractPlane(1, bsize);
            subtractPlane(2, bsize);
        }
        for (var plane = 1; plane < 3; plane++) {
            txfmRdInPlane(plane, bsize, uvTx, bestRd);
            if (rdRate == Integer.MAX_VALUE) {
                rdDist = Long.MAX_VALUE;
                rdSse = Long.MAX_VALUE;
                rdSkippable = false;
                return;
            }
            rate += rdRate;
            dist += rdDist;
            sse += rdSse;
            skippable &= rdSkippable;
        }
        rdRate = rate;
        rdDist = dist;
        rdSse = sse;
        rdSkippable = skippable;
    }

    /**
     * Subtracts the prediction in the reconstruction from the source over a plane of the current
     * block, into the plane's residual buffer.
     *
     * @param plane the plane index, 0 for luma and 1 or 2 for chroma
     * @param bsize the block size
     */
    private void subtractPlane(int plane, int bsize) {
        var planeBsize = plane == 0 ? bsize : enc.uvBsize(bsize);
        var bw = Vp9RtCoder.NUM_4X4_WIDE[planeBsize] * 4;
        var bh = Vp9RtCoder.NUM_4X4_HIGH[planeBsize] * 4;
        var src = enc.source;
        var recon = enc.recon;
        c.edsp.subtract(planeDiff(plane), 0, bw, src.planes[plane], c.srcOff[plane], src.strides[plane],
                recon.planes8[plane], c.dstOff[plane], recon.strides[plane], bw, bh);
    }

    /**
     * The residuals of the planes of an inter candidate, luma then Cb then Cr.
     */
    private final short[][] diffs = {new short[64 * 64], new short[64 * 64], new short[64 * 64]};

    /**
     * Returns the residual buffer of a plane.
     *
     * @param plane the plane index
     * @return the buffer
     */
    private short[] planeDiff(int plane) {
        return diffs[plane];
    }

    /**
     * Measures the rate, distortion and skippability of a plane of the current block at one
     * transform size, transform block by transform block in coding order, stopping once the cost
     * goes over the budget ({@code txfm_rd_in_plane} and {@code block_rd_txfm}).
     *
     * <p>An intra block predicts and reconstructs each transform block from the ones before it;
     * an inter block quantizes the residual of its prediction, which {@link #subtractPlane}
     * computed. The results are left in {@link #rdRate}, {@link #rdDist}, {@link #rdSse} and
     * {@link #rdSkippable}.
     *
     * @param plane the plane index, 0 for luma and 1 or 2 for chroma
     * @param bsize the block size
     * @param txSize the transform size
     * @param bestRd the budget
     */
    private void txfmRdInPlane(int plane, int bsize, int txSize, long bestRd) {
        var m = c.mi;
        var inter = m.isInter();
        var sx = plane == 0 ? 0 : enc.ssX;
        var sy = plane == 0 ? 0 : enc.ssY;
        var planeBsize = plane == 0 ? bsize : enc.uvBsize(bsize);
        var num4w = Vp9RtCoder.NUM_4X4_WIDE[planeBsize];
        var num4h = Vp9RtCoder.NUM_4X4_HIGH[planeBsize];
        var maxWide = num4w + (c.mbToRightEdge >= 0 ? 0 : c.mbToRightEdge >> (5 + sx));
        var maxHigh = num4h + (c.mbToBottomEdge >= 0 ? 0 : c.mbToBottomEdge >> (5 + sy));
        var step = 1 << txSize;
        var size = 4 << txSize;
        var n = size * size;
        var bwl = Vp9RtCoder.B_WIDTH_LOG2[planeBsize];
        var above = enc.aboveEntropy[plane];
        var left = c.leftEntropy[plane];
        var aboveBase = (c.miCol * 2) >> sx;
        var leftBase = ((c.miRow * 2) & 15) >> sy;
        for (var i = 0; i < num4w; i += step) {
            var v = 0;
            for (var k = 0; k < step && i + k < 16; k++) {
                v |= above[aboveBase + i + k];
            }
            tAbove[i] = (byte) (v != 0 ? 1 : 0);
        }
        for (var i = 0; i < num4h; i += step) {
            var v = 0;
            for (var k = 0; k < step && i + k < 16; k++) {
                v |= left[leftBase + i + k];
            }
            tLeft[i] = (byte) (v != 0 ? 1 : 0);
        }
        var recon = enc.recon;
        var dst = recon.planes8[plane];
        var dstStride = recon.strides[plane];
        var src = enc.source;
        var srcPlane = src.planes[plane];
        var srcStride = src.strides[plane];
        var q = enc.quant;
        var pidx = c.qindex * 2;
        var y = plane == 0;
        var cf = c.coeff[plane];
        var qc = c.qcoeff[plane];
        var dq = c.dqcoeff[plane];
        var diff = diffs[plane];
        var diffStride = num4w * 4;
        var shift = txSize == TX_32X32 ? 0 : 2;
        var txDomain = blockTxDomain;
        var acStep = (y ? q.yDequant : q.uvDequant)[pidx + 1];
        long rate = 0;
        long dist = 0;
        long sse = 0;
        long thisRd = 0;
        var skippable = true;
        for (var r = 0; r < maxHigh; r += step) {
            for (var col = 0; col < maxWide; col += step) {
                var off = (r * num4w + (col << txSize)) * 16;
                var ctx = tAbove[col] + tLeft[r];
                var txType = DCT_DCT;
                long blockDist;
                long blockSse;
                int eob;
                if (!inter) {
                    var mode = y ? m.mode : m.uvMode;
                    var dOff = c.dstOff[plane] + 4 * (r * dstStride + col);
                    var sOff = c.srcOff[plane] + 4 * (r * srcStride + col);
                    c.predictIntraBlock(plane, bwl, txSize, mode, col, r, dOff, dst, dOff, dstStride);
                    if (y && txSize != TX_32X32 && !enc.lossless) {
                        txType = Vp9Constants.MODE_TO_TX_TYPE[mode];
                    }
                    c.edsp.subtract(diff, 0, size, srcPlane, sOff, srcStride, dst, dOff, dstStride, size, size);
                    blockSse = visibleSquares(diff, 0, size, size, maxWide - col, maxHigh - r) * 16;
                    var trellis = trellisAllowed(blockSse >> 4, size, maxWide - col, maxHigh - r, acStep);
                    c.forwardTransform(txSize, txType, diff, 0, size, cf, off);
                    var scan = Vp9RtCoder.SCANS[txSize * 4 + txType];
                    eob = quantize(y, cf, off, n, qc, dq, scan, pidx, q, txSize);
                    if (trellis) {
                        eob = c.optimizeBlock(c.optimizer, q, 8, plane, off, eob, txSize, txType, ctx);
                    }
                    if (eob > 0) {
                        c.reconstruct(txSize, txType, eob, dq, off, scan, dst, dOff, dstStride);
                    }
                    if (txDomain && eob > 0) {
                        blockDist = transformError(cf, dq, off, n, shift);
                        blockSse = transformSse;
                    } else {
                        blockDist = visibleSse(srcPlane, sOff, srcStride, dst, dOff, dstStride, size, maxWide - col,
                                maxHigh - r) * 16;
                    }
                } else {
                    var diffOff = 4 * (r * diffStride + col);
                    var residualSse = visibleSquares(diff, diffOff, diffStride, size, maxWide - col, maxHigh - r);
                    var trellis = trellisAllowed(residualSse, size, maxWide - col, maxHigh - r, acStep);
                    c.forwardTransform(txSize, DCT_DCT, diff, diffOff, diffStride, cf, off);
                    var scan = Vp9RtCoder.SCANS[txSize * 4];
                    eob = quantize(y, cf, off, n, qc, dq, scan, pidx, q, txSize);
                    if (trellis) {
                        eob = c.optimizeBlock(c.optimizer, q, 8, plane, off, eob, txSize, DCT_DCT, ctx);
                    }
                    if (txDomain && eob > 0) {
                        blockDist = transformError(cf, dq, off, n, shift);
                        blockSse = transformSse;
                    } else if (txDomain) {
                        blockSse = residualSse * 16;
                        blockDist = blockSse;
                    } else {
                        blockSse = residualSse * 16;
                        if (eob > 0) {
                            blockDist = reconError(txSize, eob, dq, off, scan, diff, diffOff, diffStride, size,
                                    maxWide - col, maxHigh - r) * 16;
                        } else {
                            blockDist = blockSse;
                        }
                    }
                }
                var rd = rdCost(0, blockDist);
                if (thisRd + rd > bestRd) {
                    rdRate = Integer.MAX_VALUE;
                    rdDist = Long.MAX_VALUE;
                    rdSse = Long.MAX_VALUE;
                    rdSkippable = false;
                    return;
                }
                var blockRate = costCoeffs(plane, txSize, txType, off, eob, ctx, inter);
                var nonzero = (byte) (eob > 0 ? 1 : 0);
                tAbove[col] = nonzero;
                tLeft[r] = nonzero;
                var rd1 = rdCost(blockRate, blockDist);
                var rd2 = rdCost(0, blockSse);
                rate += blockRate;
                dist += blockDist;
                sse += blockSse;
                thisRd += Math.min(rd1, rd2);
                if (thisRd > bestRd) {
                    rdRate = Integer.MAX_VALUE;
                    rdDist = Long.MAX_VALUE;
                    rdSse = Long.MAX_VALUE;
                    rdSkippable = false;
                    return;
                }
                skippable &= eob == 0;
            }
        }
        rdRate = (int) Math.min(rate, Integer.MAX_VALUE - 1);
        rdDist = dist;
        rdSse = sse;
        rdSkippable = skippable;
    }

    /**
     * The sum of squared coefficients of the last {@link #transformError}, scaled like it.
     */
    private long transformSse;

    /**
     * Returns the squared error between a transform block's coefficients and their dequantized
     * values, scaled to the pixel domain of the smaller transforms, and leaves the scaled sum of
     * squared coefficients in {@link #transformSse} ({@code vp9_block_error} in
     * {@code dist_block}).
     *
     * @param cf the coefficients
     * @param dq the dequantized coefficients
     * @param off the index of the block's first coefficient
     * @param n the number of coefficients
     * @param shift the scale, 0 for 32x32 transforms and 2 otherwise
     * @return the error
     */
    private long transformError(int[] cf, int[] dq, int off, int n, int shift) {
        long err = 0;
        long ssz = 0;
        for (var k = 0; k < n; k++) {
            var a = cf[off + k];
            var d = a - dq[off + k];
            err += (long) d * d;
            ssz += (long) a * a;
        }
        transformSse = ssz >> shift;
        return err >> shift;
    }

    /**
     * Returns whether the rate-distortion search optimizes the quantized coefficients of a
     * transform block, by the method of the speed features ({@code do_trellis_opt}): every block
     * at the best quality, blocks of a low source variance, or blocks whose residual is small for
     * the quantizer step.
     *
     * @param residualSse the sum of squared residuals over the visible part of the block
     * @param size the transform width
     * @param visibleCols the visible 4x4 columns from the block's left edge
     * @param visibleRows the visible 4x4 rows from the block's top edge
     * @param acStep the AC dequantization factor
     * @return whether it does
     */
    private boolean trellisAllowed(long residualSse, int size, int visibleCols, int visibleRows, int acStep) {
        var sf = enc.goodSpeed;
        if (enc.lossless) {
            return false;
        }
        return switch (sf.trellisMethod) {
            case Vp9GoodSpeed.TRELLIS_ALWAYS -> true;
            case Vp9GoodSpeed.TRELLIS_SOURCE_VARIANCE -> sf.trellisThreshold <= 0 || logBlockSrcVar <= sf.trellisThreshold;
            case Vp9GoodSpeed.TRELLIS_RESIDUAL_MSE -> {
                var qstep = acStep >> 3;
                var w = Math.min(size, visibleCols * 4);
                var h = Math.min(size, visibleRows * 4);
                yield residualSse <= (double) w * h * qstep * qstep * sf.trellisThreshold;
            }
            default -> false;
        };
    }

    /**
     * Quantizes one transform block with the regular quantizer of the decisions.
     *
     * @param y whether the block is luma
     * @param cf the coefficients
     * @param off the index of the first coefficient
     * @param n the number of coefficients
     * @param qc receives the quantized coefficients
     * @param dq receives the dequantized coefficients
     * @param scan the scan order
     * @param pidx the index of the DC parameters
     * @param q the quantizer tables
     * @param txSize the transform size
     * @return the end of block
     */
    private static int quantize(boolean y, int[] cf, int off, int n, int[] qc, int[] dq, short[] scan, int pidx,
                                Vp9Quantizer q, int txSize) {
        return y ? Vp9Quantizer.quantizeB(cf, off, n, qc, dq, scan, q.yZbin, q.yRound, q.yQuant, q.yQuantShift,
                q.yDequant, pidx, txSize == TX_32X32)
                : Vp9Quantizer.quantizeB(cf, off, n, qc, dq, scan, q.uvZbin, q.uvRound, q.uvQuant, q.uvQuantShift,
                q.uvDequant, pidx, txSize == TX_32X32);
    }

    /**
     * The residual of a transform block after its inverse transform, for the pixel-domain
     * distortion of inter blocks.
     */
    private final byte[] reconScratch = new byte[32 * 32 + 64];

    /**
     * Returns the sum of squared errors between a residual and its quantized reconstruction over
     * the visible 4x4 blocks of a transform block, by reconstructing onto a zero prediction offset
     * by 128.
     *
     * @param txSize the transform size
     * @param eob the end of block
     * @param dq the dequantized coefficients
     * @param off the index of the first coefficient
     * @param scan the scan order
     * @param diff the residuals
     * @param diffOff the index of the block's first residual
     * @param diffStride the residual row stride
     * @param size the transform width
     * @param visibleCols the visible 4x4 columns from the block's left edge
     * @param visibleRows the visible 4x4 rows from the block's top edge
     * @return the sum
     */
    private long reconError(int txSize, int eob, int[] dq, int off, short[] scan, short[] diff, int diffOff,
                            int diffStride, int size, int visibleCols, int visibleRows) {
        var buf = reconScratch;
        Arrays.fill(buf, 0, size * size, (byte) 128);
        c.reconstruct(txSize, DCT_DCT, eob, dq, off, scan, buf, 0, size);
        var w = Math.min(size, visibleCols * 4);
        var h = Math.min(size, visibleRows * 4);
        long sum = 0;
        for (var y = 0; y < h; y++) {
            for (var x = 0; x < w; x++) {
                var rec = (buf[y * size + x] & 0xFF) - 128;
                var d = diff[diffOff + y * diffStride + x] - rec;
                sum += (long) d * d;
            }
        }
        return sum;
    }

    /**
     * Returns the sum of squares of a residual over the visible 4x4 blocks of a transform block.
     *
     * @param diff the residuals
     * @param off the index of the block's first residual
     * @param stride the residual row stride
     * @param size the transform width
     * @param visibleCols the visible 4x4 columns
     * @param visibleRows the visible 4x4 rows
     * @return the sum
     */
    private static long visibleSquares(short[] diff, int off, int stride, int size, int visibleCols, int visibleRows) {
        var w = Math.min(size, visibleCols * 4);
        var h = Math.min(size, visibleRows * 4);
        long sum = 0;
        for (var y = 0; y < h; y++) {
            var row = off + y * stride;
            for (var x = 0; x < w; x++) {
                var d = diff[row + x];
                sum += d * d;
            }
        }
        return sum;
    }

    /**
     * Returns the sum of squared differences between two planes over the visible 4x4 blocks of a
     * transform block ({@code pixel_sse}).
     *
     * @param a the first plane
     * @param aOff the index of the block in {@code a}
     * @param aStride the row stride of {@code a}
     * @param b the second plane
     * @param bOff the index of the block in {@code b}
     * @param bStride the row stride of {@code b}
     * @param size the transform width
     * @param visibleCols the visible 4x4 columns
     * @param visibleRows the visible 4x4 rows
     * @return the sum
     */
    private static long visibleSse(byte[] a, int aOff, int aStride, byte[] b, int bOff, int bStride, int size,
                                   int visibleCols, int visibleRows) {
        var w = Math.min(size, visibleCols * 4);
        var h = Math.min(size, visibleRows * 4);
        long sum = 0;
        for (var y = 0; y < h; y++) {
            for (var x = 0; x < w; x++) {
                var d = (a[aOff + y * aStride + x] & 0xFF) - (b[bOff + y * bStride + x] & 0xFF);
                sum += d * d;
            }
        }
        return sum;
    }

    /**
     * Returns the rate of the tokens of one quantized transform block ({@code cost_coeffs}).
     *
     * @param plane the plane index
     * @param txSize the transform size
     * @param txType the transform type, which selects the scan
     * @param off the index of the block's first coefficient
     * @param eob the end of block
     * @param ctx the block's nonzero context, from 0 to 2
     * @param inter whether the block is an inter block
     * @return the rate in cost units
     */
    private int costCoeffs(int plane, int txSize, int txType, int off, int eob, int ctx, boolean inter) {
        var costs = enc.rdCosts;
        var tc = costs.token;
        var qc = c.qcoeff[plane];
        var bands = txSize == TX_4X4 ? Vp9ProbTables.BAND_4X4 : Vp9ProbTables.BAND_8X8_PLUS;
        if (eob == 0) {
            return tc[Vp9RdCosts.tokenBase(txSize, plane, inter, 0, false, ctx) + Vp9EncTables.EOB_TOKEN];
        }
        var scan = Vp9RtCoder.SCANS[txSize * 4 + txType];
        var nb = Vp9RtCoder.NEIGHBORS[txSize * 4 + txType];
        var cache = tokenCache;
        var vc = costs.valueCost(qc[off + scan[0]]);
        var tok = vc & 15;
        var cost = (vc >> 4) + tc[Vp9RdCosts.tokenBase(txSize, plane, inter, bands[0], false, ctx) + tok];
        cache[scan[0]] = (byte) Vp9Constants.ENERGY_CLASS[tok];
        var prev = tok;
        var i = 1;
        for (; i < eob; i++) {
            var rc = scan[i];
            vc = costs.valueCost(qc[off + rc]);
            tok = vc & 15;
            var pt = (1 + cache[nb[2 * i]] + cache[nb[2 * i + 1]]) >> 1;
            cost += (vc >> 4) + tc[Vp9RdCosts.tokenBase(txSize, plane, inter, bands[i], prev == Vp9EncTables.ZERO_TOKEN,
                    pt) + tok];
            cache[rc] = (byte) Vp9Constants.ENERGY_CLASS[tok];
            prev = tok;
        }
        if (i < (16 << (txSize << 1))) {
            var pt = (1 + cache[nb[2 * i]] + cache[nb[2 * i + 1]]) >> 1;
            cost += tc[Vp9RdCosts.tokenBase(txSize, plane, inter, bands[i], false, pt) + Vp9EncTables.EOB_TOKEN];
        }
        return cost;
    }

    /**
     * Searches the modes of an inter frame block: the intra modes, the single reference inter
     * modes and, when the frame selects the reference mode per block, the compound modes, in the
     * order of {@link #MODE_ORDER} ({@code vp9_rd_pick_inter_mode_sb}).
     *
     * @param bsize the block size
     * @param choice receives the decision
     * @param bestRdSoFar the budget
     */
    private void pickInterBlock(int bsize, Choice choice, long bestRdSoFar) {
        var m = c.mi;
        var sf = enc.goodSpeed;
        var costs = enc.rdCosts;
        var bestRd = bestRdSoFar;
        var intraCostPenalty = (20 * Vp9EncTables.dcQuant(c.qindex, 0)) >> (bsize <= BLOCK_16X16
                ? (bsize <= BLOCK_8X8 ? 4 : 2) : 0);
        Arrays.fill(uvIntraDone, false);
        Arrays.fill(singleFilter, SWITCHABLE);
        Arrays.fill(singleSkippable, false);
        var refSkipMask = 0;
        var secondSkipMask = 0;
        var modeSkipMask = modeSkip;
        Arrays.fill(modeSkipMask, 0);
        for (var ref = LAST_FRAME; ref <= ALTREF_FRAME; ref++) {
            c.predMvSad[ref] = Integer.MAX_VALUE;
            var picture = enc.activeRef(ref);
            refUsable[ref] = enc.refFrameFlag(ref) && picture != null;
            frameMv[ref(NEWMV, ref)] = Vp9RtCoder.INVALID_MV;
            frameMv[ref(ZEROMV, ref)] = 0;
            if (refUsable[ref]) {
                prepareReference(ref, bsize);
                c.pick.mvPred(picture, ref, bsize);
                maxMvContext[ref] = refMaxMv[ref];
            }
        }
        for (var ref = LAST_FRAME; ref <= ALTREF_FRAME; ref++) {
            if (!refUsable[ref]) {
                refSkipMask |= 1 << ref;
                secondSkipMask |= SECOND_REF_FRAME_MASK;
            } else if (sf.referenceMasking) {
                for (var i = LAST_FRAME; i <= ALTREF_FRAME; i++) {
                    if (refUsable[i] && (Integer.toUnsignedLong(c.predMvSad[ref]) >> 2)
                            > Integer.toUnsignedLong(c.predMvSad[i])) {
                        modeSkipMask[ref] |= (1 << NEARESTMV) | (1 << NEARMV) | (1 << ZEROMV);
                        break;
                    }
                }
            }
        }
        if (enc.srcFrameAltRef && enc.unfilteredAltRef) {
            refSkipMask = (1 << LAST_FRAME) | (1 << GOLDEN_FRAME);
            secondSkipMask = SECOND_REF_FRAME_MASK;
            modeSkipMask[ALTREF_FRAME] = ~((1 << NEARESTMV) | (1 << NEARMV) | (1 << ZEROMV));
            if (frameMv[ref(NEARMV, ALTREF_FRAME)] != 0) {
                modeSkipMask[ALTREF_FRAME] |= 1 << NEARMV;
            }
            if (frameMv[ref(NEARESTMV, ALTREF_FRAME)] != 0) {
                modeSkipMask[ALTREF_FRAME] |= 1 << NEARESTMV;
            }
        }
        if (enc.srcFrameAltRef && sf.altRefSearchFp && refUsable[ALTREF_FRAME]) {
            modeSkipMask[ALTREF_FRAME] = 0;
            refSkipMask = ~(1 << ALTREF_FRAME);
            secondSkipMask = SECOND_REF_FRAME_MASK;
        }
        if (sf.adaptiveModeSearch && !enc.srcFrameAltRef && enc.framesSinceGolden >= 3 && refUsable[GOLDEN_FRAME]
                && Integer.toUnsignedLong(c.predMvSad[GOLDEN_FRAME])
                > (Integer.toUnsignedLong(c.predMvSad[LAST_FRAME]) << 1)) {
            modeSkipMask[GOLDEN_FRAME] |= (1 << NEARESTMV) | (1 << NEARMV) | (1 << ZEROMV) | (1 << NEWMV);
        }
        if (bsize > sf.maxIntraBsize) {
            refSkipMask |= 1;
            secondSkipMask |= 1;
        }
        modeSkipMask[INTRA_FRAME] |= ~sf.intraYModeMask[Vp9Constants.MAX_TX_SIZE[bsize]];
        var threshes = enc.rdThreshes;
        var threshBase = (m.segmentId * 13 + bsize) * MAX_MODES;
        var factBase = bsize * MAX_MODES;
        var bestModeIndex = -1;
        var bestRate = Integer.MAX_VALUE;
        var bestDist = Long.MAX_VALUE;
        var bestSkip2 = false;
        var bestIntraMode = DC_PRED;
        var bestIntraRd = Long.MAX_VALUE;
        var bestPredSse = Long.MAX_VALUE;
        var best = bestChoice;
        var bestRef0 = -1;
        var modeSkipStart = sf.modeSkipStart + 1;
        var compoundSearch = enc.referenceMode == Vp9RtEncoder.REFERENCE_MODE_SELECT;
        for (var midx = 0; midx < MAX_MODES; midx++) {
            var mode = MODE_ORDER[midx * 3];
            var ref = MODE_ORDER[midx * 3 + 1];
            var second = MODE_ORDER[midx * 3 + 2];
            var comp = second > INTRA_FRAME;
            if (midx == modeSkipStart && bestModeIndex >= 0) {
                refSkipMask |= REF_MODE_MASK[bestRef0];
                if (bestRef0 == LAST_FRAME || bestRef0 == GOLDEN_FRAME) {
                    secondSkipMask |= SECOND_REF_FRAME_MASK;
                }
            }
            if ((refSkipMask & (1 << ref)) != 0 && (secondSkipMask & (1 << Math.max(0, second))) != 0) {
                continue;
            }
            if ((modeSkipMask[ref] & (1 << mode)) != 0) {
                continue;
            }
            var threshold = ((long) threshes[threshBase + midx] * threshFact[factBase + midx]) >> 5;
            if (bestRd < threshold) {
                continue;
            }
            if (comp) {
                if (!compoundSearch || !refUsable[ref] || !refUsable[second]) {
                    continue;
                }
                if ((sf.modeSearchSkipFlags & Vp9GoodSpeed.SKIP_COMP_BESTINTRA) != 0 && bestModeIndex >= 0
                        && bestRef0 == INTRA_FRAME) {
                    continue;
                }
            }
            if (ref == INTRA_FRAME) {
                if (sf.adaptiveModeSearch && bestPredSse != Long.MAX_VALUE
                        && (sourceVariance << (Vp9RtCoder.B_WIDTH_LOG2[bsize] + Vp9RtCoder.B_HEIGHT_LOG2[bsize] + 4))
                        > bestPredSse) {
                    continue;
                }
                if (mode != DC_PRED) {
                    if ((sf.modeSearchSkipFlags & Vp9GoodSpeed.SKIP_INTRA_LOWVAR) != 0 && sourceVariance < 64) {
                        continue;
                    }
                    if ((sf.modeSearchSkipFlags & Vp9GoodSpeed.SKIP_INTRA_BESTINTER) != 0 && mode >= D45_PRED
                            && mode <= TM_PRED && bestModeIndex >= 0 && bestRef0 > INTRA_FRAME) {
                        continue;
                    }
                    if ((sf.modeSearchSkipFlags & Vp9GoodSpeed.SKIP_INTRA_DIRMISMATCH) != 0
                            && conditionalSkipIntra(mode, bestIntraMode)) {
                        continue;
                    }
                }
            } else if (!checkBestZeroMv(mode, ref, second)) {
                continue;
            }
            m.mode = mode;
            m.uvMode = DC_PRED;
            m.ref0 = ref;
            m.ref1 = second;
            m.interpFilter = enc.interpFilter == SWITCHABLE ? EIGHTTAP : enc.interpFilter;
            m.mv0 = 0;
            m.mv1 = 0;
            c.skip = false;
            int rate2;
            long dist2;
            int rateY;
            int rateUv;
            boolean skippable;
            var disableSkip = false;
            long totalSse = Long.MAX_VALUE;
            var thisSkip2 = false;
            if (ref == INTRA_FRAME) {
                superBlockYrd(bsize, bestRd);
                if (rdRate == Integer.MAX_VALUE) {
                    continue;
                }
                rateY = rdRate;
                var distY = rdDist;
                skippable = rdSkippable;
                var uvTx = enc.uvTxSize(m);
                if (!uvIntraDone[uvTx]) {
                    pickIntraUv(bsize, uvTx, true);
                }
                rateUv = (int) uvIntra[uvTx * 5 + 1];
                var distUv = uvIntra[uvTx * 5 + 2];
                skippable &= uvIntra[uvTx * 5 + 3] != 0;
                m.uvMode = (int) uvIntra[uvTx * 5 + 4];
                rate2 = rateY + costs.yMode[mode] + (int) uvIntra[uvTx * 5];
                if (mode != DC_PRED && mode != TM_PRED) {
                    rate2 += intraCostPenalty;
                }
                dist2 = distY + distUv;
            } else {
                if (!handleInterMode(bsize, bestRd)) {
                    continue;
                }
                rate2 = interRate;
                dist2 = interDist;
                rateY = interRateY;
                rateUv = interRateUv;
                skippable = interSkippable;
                disableSkip = interDisableSkip;
                totalSse = interSse;
            }
            if (ref != INTRA_FRAME && compoundSearch) {
                rate2 += Vp9EncTables.costBit(compModeProb(), comp ? 1 : 0);
            }
            rate2 += comp ? compRefCost(ref) : singleRefCost(ref);
            if (!disableSkip) {
                var skip0 = c.skipCost(0);
                var skip1 = c.skipCost(1);
                if (skippable) {
                    rate2 -= rateY + rateUv;
                    rate2 += skip1;
                } else if (ref != INTRA_FRAME && !enc.lossless) {
                    if (rdCost((long) rateY + rateUv + skip0, dist2) < rdCost(skip1, totalSse)) {
                        rate2 += skip0;
                    } else {
                        rate2 += skip1;
                        dist2 = totalSse;
                        rate2 -= rateY + rateUv;
                        thisSkip2 = true;
                    }
                } else {
                    rate2 += skip0;
                }
            }
            var thisRd = rdCost(rate2, dist2);
            if (ref == INTRA_FRAME && thisRd < bestIntraRd) {
                bestIntraRd = thisRd;
                bestIntraMode = mode;
            }
            var earlyTerm = false;
            if (thisRd < bestRd || c.skip) {
                bestModeIndex = midx;
                bestRef0 = ref;
                if (ref == INTRA_FRAME) {
                    m.mv0 = 0;
                    m.interpFilter = SWITCHABLE_FILTERS;
                } else {
                    bestPredSse = Integer.toUnsignedLong(c.predSse[ref]);
                }
                bestRate = rate2;
                bestDist = dist2;
                bestRd = thisRd;
                best.capture(m);
                bestSkip2 = thisSkip2 || c.skip;
                if ((sf.modeSearchSkipFlags & Vp9GoodSpeed.EARLY_TERMINATE) != 0 && midx > MIN_EARLY_TERM_INDEX) {
                    var qstep = enc.quant.yDequant[c.qindex * 2 + 1];
                    var scale = sourceVariance < 16 ? 3 : 4;
                    if (ref > INTRA_FRAME && dist2 * scale < (long) qstep * qstep) {
                        earlyTerm = true;
                    }
                }
            }
            if (earlyTerm || (c.skip && !comp)) {
                break;
            }
        }
        if (bestModeIndex < 0 || bestRd >= bestRdSoFar) {
            return;
        }
        if (best.mode == NEWMV) {
            var r0 = best.ref0;
            var r1 = best.ref1;
            var compBest = r1 > INTRA_FRAME;
            if (frameMv[ref(NEARESTMV, r0)] == best.mv0 && (!compBest || frameMv[ref(NEARESTMV, r1)] == best.mv1)) {
                best.mode = NEARESTMV;
            } else if (frameMv[ref(NEARMV, r0)] == best.mv0 && (!compBest || frameMv[ref(NEARMV, r1)] == best.mv1)) {
                best.mode = NEARMV;
            } else if (best.mv0 == 0 && (!compBest || best.mv1 == 0)) {
                best.mode = ZEROMV;
            }
        }
        if (!enc.srcFrameAltRef) {
            updateThreshFact(bsize, bestModeIndex);
        }
        choice.mode = best.mode;
        choice.uvMode = best.uvMode;
        choice.ref0 = best.ref0;
        choice.ref1 = best.ref1;
        choice.mv0 = best.mv0;
        choice.mv1 = best.mv1;
        choice.interpFilter = best.interpFilter;
        choice.txSize = best.txSize;
        choice.skip = bestSkip2 && best.ref0 > INTRA_FRAME;
        choice.rate = bestRate;
        choice.dist = bestDist;
        choice.rd = bestRd;
    }

    /**
     * The mode skip flags of each reference of the block being searched.
     */
    private final int[] modeSkip = new int[4];

    /**
     * Whether each single reference mode of the block being searched quantized every coefficient
     * to zero, {@code [mode - NEARESTMV][ref]}.
     */
    private final boolean[] singleSkippable = new boolean[4 * 4];

    /**
     * The second reference mask of the modes that predict from a reference without the
     * alternate reference ({@code SECOND_REF_FRAME_MASK}).
     */
    private static final int SECOND_REF_FRAME_MASK = (1 << ALTREF_FRAME) | 1;

    /**
     * The best decision of the block being searched.
     */
    private final Choice bestChoice = new Choice();

    /**
     * Returns whether an oblique intra mode is far from the best intra mode so far
     * ({@code conditional_skipintra}).
     *
     * @param mode the oblique intra mode
     * @param bestIntraMode the best intra mode so far
     * @return whether to skip it
     */
    private static boolean conditionalSkipIntra(int mode, int bestIntraMode) {
        if (mode == D117_PRED && bestIntraMode != V_PRED && bestIntraMode != D135_PRED) {
            return true;
        }
        if (mode == D63_PRED && bestIntraMode != V_PRED && bestIntraMode != D45_PRED) {
            return true;
        }
        if (mode == D207_PRED && bestIntraMode != H_PRED && bestIntraMode != D45_PRED) {
            return true;
        }
        return mode == D153_PRED && bestIntraMode != H_PRED && bestIntraMode != D135_PRED;
    }

    /**
     * Returns whether a zero-vector mode should be tested: not when another mode codes the same
     * zero vectors for less ({@code check_best_zero_mv}).
     *
     * @param mode the inter mode
     * @param ref the first reference
     * @param second the second reference, or {@link Vp9Constants#NO_REF_FRAME}
     * @return whether to test the mode
     */
    private boolean checkBestZeroMv(int mode, int ref, int second) {
        return checkBestZeroMv(frameMv, mode, ref, second);
    }

    /**
     * Returns whether a mode is worth testing given the zero vectors of a vector table, like
     * {@link #checkBestZeroMv(int, int, int)} ({@code check_best_zero_mv}).
     *
     * @param frameMv the candidate vectors, {@code [mode - NEARESTMV][ref]}
     * @param mode the inter mode, from {@code NEARESTMV} to {@code NEWMV}
     * @param ref the first reference
     * @param second the second reference, or {@link Vp9Constants#NO_REF_FRAME}
     * @return whether the mode is tested
     */
    private boolean checkBestZeroMv(int[] frameMv, int mode, int ref, int second) {
        var comp = second > INTRA_FRAME;
        if ((mode == NEARMV || mode == NEARESTMV || mode == ZEROMV) && frameMv[ref(mode, ref)] == 0
                && (!comp || frameMv[ref(mode, second)] == 0)) {
            var ic = enc.interModeCost;
            var rfc = refModeContext[ref] * 4;
            var c1 = ic[rfc + (NEARMV - NEARESTMV)];
            var c2 = ic[rfc];
            var c3 = ic[rfc + (ZEROMV - NEARESTMV)];
            if (mode == NEARMV) {
                return c1 <= c3;
            }
            if (mode == NEARESTMV) {
                return c2 <= c3;
            }
            if (!comp) {
                return !((c3 >= c2 && frameMv[ref(NEARESTMV, ref)] == 0)
                        || (c3 >= c1 && frameMv[ref(NEARMV, ref)] == 0));
            }
            return !((c3 >= c2 && frameMv[ref(NEARESTMV, ref)] == 0 && frameMv[ref(NEARESTMV, second)] == 0)
                    || (c3 >= c1 && frameMv[ref(NEARMV, ref)] == 0 && frameMv[ref(NEARMV, second)] == 0));
        }
        return true;
    }

    /**
     * Updates the adaptive mode thresholds of the block sizes around a block after its search
     * ({@code vp9_update_rd_thresh_fact}).
     *
     * @param bsize the block size
     * @param bestModeIndex the index of the chosen mode in {@link #MODE_ORDER}
     */
    private void updateThreshFact(int bsize, int bestModeIndex) {
        var level = enc.goodSpeed.adaptiveRdThresh;
        if (level <= 0) {
            return;
        }
        var minSize = Math.max(bsize - 1, Vp9Constants.BLOCK_4X4);
        var maxSize = Math.min(bsize + 2, BLOCK_64X64);
        var top = bsize < BLOCK_8X8 ? MAX_REFS : MAX_MODES;
        for (var mode = 0; mode < top; mode++) {
            for (var bs = minSize; bs <= maxSize; bs++) {
                var i = bs * MAX_MODES + mode;
                if (mode == bestModeIndex) {
                    threshFact[i] -= threshFact[i] >> 4;
                } else {
                    threshFact[i] = Math.min(threshFact[i] + RD_THRESH_INC, level * RD_THRESH_MAX_FACT);
                }
            }
        }
    }

    /**
     * Returns the cost of signaling a single reference for the current block
     * ({@code estimate_ref_frame_costs}).
     *
     * @param ref the reference, {@link Vp9Constants#INTRA_FRAME} for an intra block
     * @return the cost
     */
    private int singleRefCost(int ref) {
        var probs = enc.fc.probs;
        var intraInter = probs[Vp9FrameContext.INTRA_INTER + Vp9PredContexts.intraInter(c.aboveMi, c.leftMi)];
        if (ref == INTRA_FRAME) {
            return Vp9EncTables.costBit(intraInter, 0);
        }
        var cost = Vp9EncTables.costBit(intraInter, 1);
        if (enc.referenceMode == Vp9RtEncoder.REFERENCE_MODE_SELECT) {
            cost += Vp9EncTables.costBit(compModeProb(), 0);
        }
        var p1 = probs[Vp9FrameContext.SINGLE_REF + Vp9PredContexts.singleRefP1(c.aboveMi, c.leftMi) * 2];
        if (ref == LAST_FRAME) {
            return cost + Vp9EncTables.costBit(p1, 0);
        }
        var p2 = probs[Vp9FrameContext.SINGLE_REF + Vp9PredContexts.singleRefP2(c.aboveMi, c.leftMi) * 2 + 1];
        return cost + Vp9EncTables.costBit(p1, 1) + Vp9EncTables.costBit(p2, ref == GOLDEN_FRAME ? 0 : 1);
    }

    /**
     * Returns the probability of a compound prediction in the current block's context.
     *
     * @return the probability
     */
    private int compModeProb() {
        return enc.fc.probs[Vp9FrameContext.COMP_INTER + Vp9PredContexts.referenceMode(c.aboveMi, c.leftMi,
                enc.compFixedRef)];
    }

    /**
     * Returns the cost of signaling a compound reference pair for the current block
     * ({@code estimate_ref_frame_costs}).
     *
     * @param ref the variable reference, {@link Vp9Constants#LAST_FRAME} or
     * {@link Vp9Constants#GOLDEN_FRAME}
     * @return the cost
     */
    private int compRefCost(int ref) {
        var probs = enc.fc.probs;
        var intraInter = probs[Vp9FrameContext.INTRA_INTER + Vp9PredContexts.intraInter(c.aboveMi, c.leftMi)];
        var fixedIdx = enc.signBias[enc.compFixedRef];
        var ctx = Vp9PredContexts.compRef(c.aboveMi, c.leftMi, fixedIdx, enc.compVarRef0, enc.compVarRef1,
                enc.compFixedRef);
        return Vp9EncTables.costBit(intraInter, 1) + Vp9EncTables.costBit(compModeProb(), 1)
                + Vp9EncTables.costBit(probs[Vp9FrameContext.COMP_REF + ctx], ref == enc.compVarRef1 ? 1 : 0);
    }

    /**
     * The rate of the last {@link #handleInterMode}.
     */
    private int interRate;

    /**
     * The distortion of the last {@link #handleInterMode}.
     */
    private long interDist;

    /**
     * The luma token rate of the last {@link #handleInterMode}.
     */
    private int interRateY;

    /**
     * The chroma token rate of the last {@link #handleInterMode}.
     */
    private int interRateUv;

    /**
     * Whether the last {@link #handleInterMode} quantized every coefficient to zero.
     */
    private boolean interSkippable;

    /**
     * Whether the last {@link #handleInterMode} chose to code its prediction alone.
     */
    private boolean interDisableSkip;

    /**
     * The sum of squared prediction errors of the last {@link #handleInterMode}.
     */
    private long interSse;

    /**
     * Measures one single reference inter mode of the current block: searches the new vector,
     * picks the interpolation filter on a model of the rate and distortion, and measures the luma
     * and chroma residuals, unless the model shows the prediction needs none
     * ({@code handle_inter_mode}).
     *
     * @param bsize the block size
     * @param refBestRd the budget
     * @return whether the mode stayed within the budget; the results are in the {@code inter*}
     * fields
     */
    private boolean handleInterMode(int bsize, long refBestRd) {
        var m = c.mi;
        var sf = enc.goodSpeed;
        var mode = m.mode;
        var r0 = m.ref0;
        var r1 = m.ref1;
        var comp = r1 > INTRA_FRAME;
        var rate2 = 0;
        if (comp && (frameMv[ref(mode, r0)] == Vp9RtCoder.INVALID_MV
                || frameMv[ref(mode, r1)] == Vp9RtCoder.INVALID_MV)) {
            return false;
        }
        if (mode == NEWMV) {
            if (comp) {
                frameMv[ref(NEWMV, r0)] = singleNewMv[r0];
                frameMv[ref(NEWMV, r1)] = singleNewMv[r1];
                var iterations = jointSearchIterations(sf.compoundJointSearchIterations, bsize);
                if (iterations > 0) {
                    rate2 += jointMotionSearch(bsize, r0, r1, iterations, singleNewMv[r0], singleNewMv[r1], 0, 0);
                    frameMv[ref(NEWMV, r0)] = jointMv[0];
                    frameMv[ref(NEWMV, r1)] = jointMv[1];
                } else {
                    var motion = c.motion;
                    var first = frameMv[ref(NEWMV, r0)];
                    var second = frameMv[ref(NEWMV, r1)];
                    rate2 += motion.mvBitCost(first >> 16, (short) first, refBestMv[r0] >> 16, (short) refBestMv[r0]);
                    rate2 += motion.mvBitCost(second >> 16, (short) second, refBestMv[r1] >> 16,
                            (short) refBestMv[r1]);
                }
            } else {
                var rateMv = singleMotionSearch(bsize, r0);
                if (rateMv < 0) {
                    return false;
                }
                rate2 += discountNewMv(r0, frameMv[ref(NEWMV, r0)]) ? Math.max(rateMv / NEW_MV_DISCOUNT_FACTOR, 1)
                        : rateMv;
            }
        }
        var mv0 = frameMv[ref(mode, r0)];
        var mv1 = comp ? frameMv[ref(mode, r1)] : 0;
        if (mv0 == Vp9RtCoder.INVALID_MV || !mvInBounds(mv0) || (comp && !mvInBounds(mv1))) {
            return false;
        }
        m.mv0 = mv0;
        m.mv1 = mv1;
        var ic = enc.interModeCost;
        var modeCost = ic[refModeContext[r0] * 4 + (mode - NEARESTMV)];
        rate2 += !comp && discountNewMv(r0, mv0) ? Math.min(modeCost, ic[refModeContext[r0] * 4]) : modeCost;
        if (rdCost(rate2, 0) > refBestRd && mode != NEARESTMV) {
            return false;
        }
        var intpel = ((mv0 >> 16) & 7) == 0 && (mv0 & 7) == 0 && (!comp || (((mv1 >> 16) & 7) == 0 && (mv1 & 7) == 0));
        var switchable = enc.interpFilter == SWITCHABLE;
        var bestFilter = SWITCHABLE;
        var tmpRd = Long.MAX_VALUE;
        if (comp && sf.adaptiveModeSearch && singleFilter[ref(mode, r0)] == singleFilter[ref(mode, r1)]) {
            bestFilter = singleFilter[ref(mode, r0)];
        }
        if (sourceVariance < sf.disableFilterSearchVarThresh) {
            bestFilter = EIGHTTAP;
        } else if (switchable && bestFilter == SWITCHABLE) {
            var bestRd = Long.MAX_VALUE;
            var intpelRate = 0;
            var intpelDist = 0L;
            for (var filter = 0; filter < SWITCHABLE_FILTERS; filter++) {
                m.interpFilter = filter;
                var rsRd = rdCost(switchableRate(filter), 0);
                long rd;
                if (filter > 0 && intpel) {
                    rd = rdCost(intpelRate, intpelDist) + rsRd;
                } else {
                    modelInterRd(bsize);
                    rd = rdCost(modelRate, modelDist) + rsRd;
                    if (filter == 0 && intpel) {
                        intpelRate = modelRate;
                        intpelDist = modelDist;
                    }
                }
                if (filter == 0 && sf.useRdBreakout && refBestRd < Long.MAX_VALUE && rd / 2 > refBestRd) {
                    return false;
                }
                if (filter == 0 || rd < bestRd) {
                    bestRd = rd;
                    bestFilter = filter;
                    tmpRd = rd;
                }
            }
        }
        m.interpFilter = switchable ? (bestFilter == SWITCHABLE ? EIGHTTAP : bestFilter) : enc.interpFilter;
        var rs = switchable ? switchableRate(m.interpFilter) : 0;
        modelInterRd(bsize);
        long rd;
        if (tmpRd != Long.MAX_VALUE) {
            rd = tmpRd;
        } else {
            rd = rdCost((long) rs + modelRate, modelDist);
        }
        var skipSb = modelSkip;
        var skipSse = modelSse;
        if (!comp) {
            singleFilter[ref(mode, r0)] = m.interpFilter;
        } else if (sf.adaptiveModeSearch && singleSkippable[ref(mode, r0)] && singleSkippable[ref(mode, r1)]) {
            skipSb = true;
        }
        if (sf.useRdBreakout && refBestRd < Long.MAX_VALUE && rd / 2 > refBestRd) {
            return false;
        }
        rate2 += rs;
        interDisableSkip = false;
        if (!skipSb || enc.lossless) {
            subtractPlane(0, bsize);
            superBlockYrd(bsize, refBestRd);
            if (rdRate == Integer.MAX_VALUE) {
                return false;
            }
            var rateY = rdRate;
            var distY = rdDist;
            var sseY = rdSse;
            var skipY = rdSkippable;
            rate2 += rateY;
            var rdCostY = Math.min(rdCost(rate2, distY), rdCost(0, sseY));
            superBlockUvrd(bsize, refBestRd - rdCostY);
            if (rdRate == Integer.MAX_VALUE) {
                return false;
            }
            rate2 += rdRate;
            interRate = rate2;
            interDist = distY + rdDist;
            interRateY = rateY;
            interRateUv = rdRate;
            interSkippable = skipY && rdSkippable;
            interSse = sseY + rdSse;
            if (!comp) {
                singleSkippable[ref(mode, r0)] = interSkippable;
            }
        } else {
            c.skip = true;
            interDisableSkip = true;
            rate2 += c.skipCost(1);
            interRate = rate2;
            interDist = skipSse;
            interRateY = 0;
            interRateUv = 0;
            interSkippable = true;
            interSse = skipSse;
        }
        return true;
    }

    /**
     * Returns whether a vector keeps the current block's prediction within the motion limits
     * ({@code mv_check_bounds} negated).
     *
     * @param mv the packed vector
     * @return whether it is inside
     */
    private boolean mvInBounds(int mv) {
        var row = (mv >> 16) >> 3;
        var col = ((short) mv) >> 3;
        return row >= c.mvRowMin && row <= c.mvRowMax && col >= c.mvColMin && col <= c.mvColMax;
    }

    /**
     * Returns whether the rate of a new vector is discounted, to help a weak motion field start
     * where the nearest and near vectors are zero ({@code discount_newmv_test}).
     *
     * @param ref the reference
     * @param mv the new vector
     * @return whether to discount
     */
    private boolean discountNewMv(int ref, int mv) {
        return !enc.srcFrameAltRef && c.mi.mode == NEWMV && mv != 0
                && (frameMv[ref(NEARESTMV, ref)] == 0 || frameMv[ref(NEARESTMV, ref)] == Vp9RtCoder.INVALID_MV)
                && (frameMv[ref(NEARMV, ref)] == 0 || frameMv[ref(NEARMV, ref)] == Vp9RtCoder.INVALID_MV);
    }

    /**
     * Returns the cost of an interpolation filter in the current block's context.
     *
     * @param filter the interpolation filter
     * @return the cost
     */
    private int switchableRate(int filter) {
        var ctx = Vp9PredContexts.switchableInterp(c.aboveMi, c.leftMi);
        return enc.switchableInterpCosts[ctx * 3 + filter];
    }

    /**
     * The modeled rate of the last {@link #modelInterRd}.
     */
    private int modelRate;

    /**
     * The modeled distortion of the last {@link #modelInterRd}.
     */
    private long modelDist;

    /**
     * The sum of squared prediction errors of the last {@link #modelInterRd}, scaled as a
     * distortion.
     */
    private long modelSse;

    /**
     * Whether every unit of every plane of the last {@link #modelInterRd} predicted well enough
     * to skip the residual.
     */
    private boolean modelSkip;

    /**
     * Builds the inter prediction of the current mode into the reconstruction and models its
     * rate and distortion from the prediction errors of each unit of the largest transform
     * size, the prediction error of the luma also becoming the reference's prediction error
     * ({@code build_inter_pred_model_rd_earlyterm} without the early termination).
     *
     * @param bsize the block size
     */
    private void modelInterRd(int bsize) {
        var src = enc.source;
        var recon = enc.recon;
        var q = enc.quant;
        var pidx = c.qindex * 2;
        var selectTx = enc.goodSpeed.txSizeSearch != Vp9GoodSpeed.TX_LARGEST || enc.keyFrame;
        long rate = 0;
        long dist = 0;
        long total = 0;
        long lumaSse = 0;
        var skipFlag = true;
        for (var plane = 0; plane < 3; plane++) {
            c.buildInterPredictor(plane, bsize);
            var planeBsize = plane == 0 ? bsize : enc.uvBsize(bsize);
            var unit = Vp9RtCoder.TXSIZE_TO_BSIZE[Vp9Constants.MAX_TX_SIZE[planeBsize]];
            var unitLog2 = Vp9RtCoder.B_WIDTH_LOG2[unit];
            var unitSize = 4 << unitLog2;
            var bw = 1 << (Vp9RtCoder.B_WIDTH_LOG2[planeBsize] - unitLog2);
            var bh = 1 << (Vp9RtCoder.B_HEIGHT_LOG2[planeBsize] - unitLog2);
            var zbin = plane == 0 ? q.yZbin : q.uvZbin;
            var dcThr = ((long) zbin[pidx] * zbin[pidx]) >> 6;
            var acThr = ((long) zbin[pidx + 1] * zbin[pidx + 1]) >> 6;
            var lowDc = Math.min(50, dcThr >> 2);
            var lowAc = Math.min(80, acThr >> 2);
            var srcPlane = src.planes[plane];
            var srcStride = src.strides[plane];
            var dstPlane = recon.planes8[plane];
            var dstStride = recon.strides[plane];
            var unitLog2Pels = 2 * unitLog2 + 4;
            long sumSse = 0;
            for (var y = 0; y < bh; y++) {
                for (var x = 0; x < bw; x++) {
                    var packed = c.edsp.variance(srcPlane, c.srcOff[plane] + y * unitSize * srcStride + x * unitSize,
                            srcStride, dstPlane, c.dstOff[plane] + y * unitSize * dstStride + x * unitSize, dstStride,
                            unitSize, unitSize);
                    var sse = Vp9EncDsp.sse(packed);
                    var var = Vp9EncDsp.variance(packed, unitLog2Pels);
                    sumSse += sse;
                    var lowErrSkip = false;
                    if (!selectTx && (var < acThr || var == 0) && (sse - var < dcThr || sse == var)) {
                        lowErrSkip = sse == 0 || (var < lowAc && sse - var < lowDc);
                    }
                    skipFlag &= lowErrSkip;
                }
            }
            if (plane == 0) {
                lumaSse = sumSse;
            }
            total += sumSse;
            var dequant = plane == 0 ? q.yDequant : q.uvDequant;
            var nLog2 = Vp9RtCoder.B_WIDTH_LOG2[planeBsize] + Vp9RtCoder.B_HEIGHT_LOG2[planeBsize] + 4;
            var model = Vp9EncTables.modelRdFromVar(sumSse, nLog2, dequant[pidx + 1] >> 3);
            rate += model >>> 32;
            dist += model & 0xFFFFFFFFL;
        }
        c.predSse[c.mi.ref0] = (int) Math.min(lumaSse, Integer.MAX_VALUE);
        modelRate = (int) Math.min(rate, Integer.MAX_VALUE / 2);
        modelDist = dist << 4;
        modelSse = total << 4;
        modelSkip = skipFlag;
    }

    /**
     * Searches the new motion vector of a single reference for the current block, leaving it in
     * {@link #frameMv} ({@code single_motion_search}).
     *
     * @param bsize the block size
     * @param ref the reference
     * @return the vector's rate, or -1 when the search is skipped
     */
    private int singleMotionSearch(int bsize, int ref) {
        var sf = enc.goodSpeed;
        var motion = c.motion;
        var refMv = refBestMv[ref];
        var refRow = refMv >> 16;
        var refCol = (short) refMv;
        var picture = enc.activeRef(ref);
        var stepParam = sf.autoMvStepSize && enc.showFrame ? (initSearchRange(refMaxMv[ref]) + enc.mvStepParam) / 2
                : enc.mvStepParam;
        if (sf.adaptiveMotionSearch && bsize < BLOCK_64X64) {
            var boffset = 2 * (Vp9RtCoder.B_WIDTH_LOG2[BLOCK_64X64]
                    - Math.min(Vp9RtCoder.B_HEIGHT_LOG2[bsize], Vp9RtCoder.B_WIDTH_LOG2[bsize]));
            stepParam = Math.max(stepParam, boffset);
        }
        if (sf.adaptiveMotionSearch) {
            var tlevel = Integer.toUnsignedLong(c.predMvSad[ref])
                    >> (Vp9RtCoder.B_WIDTH_LOG2[bsize] + Vp9RtCoder.B_HEIGHT_LOG2[bsize] + 4);
            if (tlevel < 5) {
                stepParam += 2;
            }
            if (enc.showFrame) {
                for (var i = LAST_FRAME; i <= ALTREF_FRAME; i++) {
                    if ((Integer.toUnsignedLong(c.predMvSad[ref]) >> 3) > Integer.toUnsignedLong(c.predMvSad[i])) {
                        c.predMv[ref] = Vp9RtCoder.INVALID_PRED_MV;
                        return -1;
                    }
                }
            }
        }
        stepParam = Math.min(stepParam, Vp9RtMotion.MAX_MVSEARCH_STEPS - 2);
        motion.setup(bsize, picture);
        motion.setSearchRange(refRow, refCol);
        var bestIndex = c.mvBestRefIndex[ref];
        var start = bestIndex < 2 ? c.refMvs[ref * 2 + bestIndex] : c.predMv[ref];
        if ((start >> 16) == Short.MAX_VALUE) {
            start = refMv;
        }
        var bestSme = motion.fullPixelSearch((start >> 16) >> 3, ((short) start) >> 3, stepParam, sf.searchMethod,
                c.sadPerBit16, sf.subpelSearchMethod != Vp9GoodSpeed.SUBPEL_TREE, refRow, refCol);
        var resultRow = motion.bestRow * 8;
        var resultCol = motion.bestCol * 8;
        if (bestSme < Vp9RtMotion.INT_MAX) {
            var fullRow = motion.bestRow;
            var fullCol = motion.bestCol;
            motion.setup(bsize, picture);
            motion.setSubpelFilter(sf.subpelSearchTaps);
            subpelSearch(motion, fullRow, fullCol, refRow, refCol);
            resultRow = motion.bestRow;
            resultCol = motion.bestCol;
            c.predSse[ref] = (int) Math.min(motion.sse1, Integer.MAX_VALUE);
        }
        var mv = ModeInfo.packMv(resultRow, resultCol);
        frameMv[ref(NEWMV, ref)] = mv;
        singleNewMv[ref] = mv;
        c.predMv[ref] = mv;
        return motion.mvBitCost(resultRow, resultCol, refRow, refCol);
    }

    /**
     * The number of reference combinations the sub-8x8 mode search tests ({@code MAX_REFS}).
     */
    static final int MAX_REFS = 6;

    /**
     * The first reference of each combination the sub-8x8 mode search tests, in order
     * ({@code vp9_ref_order}).
     */
    private static final int[] SUB8X8_REF0 = {LAST_FRAME, GOLDEN_FRAME, ALTREF_FRAME, LAST_FRAME, GOLDEN_FRAME,
            INTRA_FRAME};

    /**
     * The second reference of each combination.
     */
    private static final int[] SUB8X8_REF1 = {NO_REF_FRAME, NO_REF_FRAME, NO_REF_FRAME, ALTREF_FRAME, ALTREF_FRAME,
            NO_REF_FRAME};

    /**
     * The weight of a sub-block vector's cost in the rate ({@code MV_COST_WEIGHT_SUB}).
     */
    private static final int MV_COST_WEIGHT_SUB = 120;

    /**
     * The largest candidate vector component of each reference at the last whole-block search,
     * the step of the motion search of the first sub-block ({@code max_mv_context}).
     */
    private final int[] maxMvContext = new int[4];

    /**
     * The rate of the last {@link #intraSub8x8Y}.
     */
    private int subRate;

    /**
     * The token rate of the last {@link #intraSub8x8Y}.
     */
    private int subRateY;

    /**
     * The distortion of the last {@link #intraSub8x8Y}.
     */
    private long subDist;

    /**
     * The best mode of the last {@link #intra4x4Block}.
     */
    private int blockBestMode;

    /**
     * The rate of the last {@link #intra4x4Block}, mode included.
     */
    private int blockRate;

    /**
     * The token rate of the last {@link #intra4x4Block}.
     */
    private int blockRateY;

    /**
     * The distortion of the last {@link #intra4x4Block}.
     */
    private long blockDist;

    /**
     * The best reconstruction of the sub-block of the last {@link #intra4x4Block}, 8 samples per
     * row.
     */
    private final byte[] bestSubRecon = new byte[64];

    /**
     * The nonzero contexts of a sub-block's mode being tested.
     */
    private final byte[] tempAbove = new byte[2];

    /**
     * The left nonzero contexts of a sub-block's mode being tested.
     */
    private final byte[] tempLeft = new byte[2];

    /**
     * The nonzero contexts of a sub-block before its modes are tested.
     */
    private final byte[] startAbove = new byte[2];

    /**
     * The left nonzero contexts of a sub-block before its modes are tested.
     */
    private final byte[] startLeft = new byte[2];

    /**
     * Searches the intra modes of a key frame block smaller than 8x8: a mode for each sub-block,
     * then the chroma mode of the whole 8x8 block ({@code vp9_rd_pick_intra_mode_sb}).
     *
     * @param bsize the block size, smaller than 8x8
     * @param choice receives the decision
     * @param bestRd the budget
     */
    private void pickIntraSub8x8(int bsize, Choice choice, long bestRd) {
        var m = c.mi;
        m.ref0 = INTRA_FRAME;
        m.ref1 = NO_REF_FRAME;
        m.interpFilter = SWITCHABLE_FILTERS;
        m.mv0 = Vp9RtCoder.INVALID_MV;
        m.mv1 = 0;
        m.txSize = TX_4X4;
        if (intraSub8x8Y(bsize, bestRd) >= bestRd) {
            return;
        }
        var uvTx = enc.uvTxSize(m);
        pickIntraUv(BLOCK_8X8, uvTx, false);
        var uvRate = (int) uvIntra[uvTx * 5];
        var uvDist = uvIntra[uvTx * 5 + 2];
        m.uvMode = (int) uvIntra[uvTx * 5 + 4];
        var rate = subRate + uvRate + c.skipCost(0);
        choice.capture(m);
        choice.skip = false;
        choice.rate = rate;
        choice.dist = subDist + uvDist;
        choice.rd = rdCost(rate, choice.dist);
    }

    /**
     * Searches the luma intra mode of each sub-block of a block smaller than 8x8, updating the
     * nonzero contexts with the chosen modes' coefficients ({@code rd_pick_intra_sub_8x8_y_mode});
     * the results are left in {@link #subRate}, {@link #subRateY}, {@link #subDist} and the
     * block's sub-block modes.
     *
     * @param bsize the block size, smaller than 8x8
     * @param bestRd the budget
     * @return the rate-distortion cost, or {@link Long#MAX_VALUE} beyond the budget
     */
    private long intraSub8x8Y(int bsize, long bestRd) {
        var m = c.mi;
        var w4 = Vp9RtCoder.NUM_4X4_WIDE[bsize];
        var h4 = Vp9RtCoder.NUM_4X4_HIGH[bsize];
        var costs = enc.rdCosts;
        var above = enc.aboveEntropy[0];
        var left = c.leftEntropy[0];
        var aboveBase = c.miCol * 2;
        var leftBase = (c.miRow * 2) & 15;
        var cost = 0;
        long totalDist = 0;
        var totalRateY = 0;
        long totalRd = 0;
        for (var idy = 0; idy < 2; idy += h4) {
            for (var idx = 0; idx < 2; idx += w4) {
                var i = idy * 2 + idx;
                int[] modeCosts;
                int modeBase;
                if (enc.keyFrame) {
                    var a = Vp9RtBitstream.aboveBlockMode(m, c.aboveMi, i);
                    var l = Vp9RtBitstream.leftBlockMode(m, c.leftMi, i);
                    modeCosts = costs.kfYMode;
                    modeBase = (a * 10 + l) * 10;
                } else {
                    modeCosts = costs.yMode;
                    modeBase = 0;
                }
                blockBestMode = DC_PRED;
                var thisRd = intra4x4Block(idy, idx, modeCosts, modeBase, above, aboveBase + idx, left, leftBase + idy,
                        bsize, bestRd == Long.MAX_VALUE ? Long.MAX_VALUE : bestRd - totalRd);
                if (thisRd == Long.MAX_VALUE || bestRd != Long.MAX_VALUE && thisRd >= bestRd - totalRd) {
                    return Long.MAX_VALUE;
                }
                totalRd += thisRd;
                cost += blockRate;
                totalDist += blockDist;
                totalRateY += blockRateY;
                m.subModes[i] = blockBestMode;
                for (var j = 1; j < h4; j++) {
                    m.subModes[i + j * 2] = blockBestMode;
                }
                for (var j = 1; j < w4; j++) {
                    m.subModes[i + j] = blockBestMode;
                }
                if (totalRd >= bestRd) {
                    return Long.MAX_VALUE;
                }
            }
        }
        subRate = cost;
        subRateY = totalRateY;
        subDist = totalDist;
        m.mode = m.subModes[3];
        return rdCost(cost, totalDist);
    }

    /**
     * Searches the intra mode of one sub-block of a block smaller than 8x8, coding each mode's
     * 4x4 transform blocks in place ({@code rd_pick_intra4x4block}); the best mode, its rates and
     * distortion are left in {@link #blockBestMode}, {@link #blockRate}, {@link #blockRateY} and
     * {@link #blockDist}, its reconstruction in place, and its nonzero contexts in the context
     * arrays when it beats the budget.
     *
     * @param row the sub-block's 4x4 row in the 8x8 block
     * @param col the sub-block's 4x4 column
     * @param modeCosts the mode costs
     * @param modeBase the index of the costs' first mode
     * @param above the above nonzero contexts
     * @param aboveOff the index of the sub-block's first above context
     * @param left the left nonzero contexts
     * @param leftOff the index of the sub-block's first left context
     * @param bsize the block size, smaller than 8x8
     * @param rdThresh the budget
     * @return the best cost, at least the budget when no mode beat it
     */
    private long intra4x4Block(int row, int col, int[] modeCosts, int modeBase, byte[] above, int aboveOff,
                               byte[] left, int leftOff, int bsize, long rdThresh) {
        var m = c.mi;
        var sf = enc.goodSpeed;
        var w4 = Vp9RtCoder.NUM_4X4_WIDE[bsize];
        var h4 = Vp9RtCoder.NUM_4X4_HIGH[bsize];
        var recon = enc.recon;
        var dst = recon.planes8[0];
        var dstStride = recon.strides[0];
        var src = enc.source;
        var srcPlane = src.planes[0];
        var srcStride = src.strides[0];
        var dstInit = c.dstOff[0] + 4 * (row * dstStride + col);
        var srcInit = c.srcOff[0] + 4 * (row * srcStride + col);
        var q = enc.quant;
        var pidx = c.qindex * 2;
        var cf = c.coeff[0];
        var qc = c.qcoeff[0];
        var dq = c.dqcoeff[0];
        var diff = diffs[0];
        System.arraycopy(above, aboveOff, startAbove, 0, w4);
        System.arraycopy(left, leftOff, startLeft, 0, h4);
        m.txSize = TX_4X4;
        var bestRd = rdThresh;
        for (var mode = DC_PRED; mode <= TM_PRED; mode++) {
            if ((sf.intraYModeMask[TX_4X4] & (1 << mode)) == 0) {
                continue;
            }
            if ((sf.modeSearchSkipFlags & Vp9GoodSpeed.SKIP_INTRA_DIRMISMATCH) != 0
                    && conditionalSkipIntra(mode, blockBestMode)) {
                continue;
            }
            var rate = modeCosts[modeBase + mode];
            var ratey = 0;
            long distortion = 0;
            System.arraycopy(startAbove, 0, tempAbove, 0, w4);
            System.arraycopy(startLeft, 0, tempLeft, 0, h4);
            var over = false;
            for (var idy = 0; idy < h4 && !over; idy++) {
                for (var idx = 0; idx < w4; idx++) {
                    var block = (row + idy) * 2 + (col + idx);
                    var dOff = dstInit + 4 * (idy * dstStride + idx);
                    var sOff = srcInit + 4 * (idy * srcStride + idx);
                    m.subModes[block] = mode;
                    c.predictIntraBlock(0, 1, TX_4X4, mode, col + idx, row + idy, dOff, dst, dOff, dstStride);
                    c.edsp.subtract(diff, 0, 4, srcPlane, sOff, srcStride, dst, dOff, dstStride, 4, 4);
                    var txType = enc.lossless ? DCT_DCT : Vp9Constants.MODE_TO_TX_TYPE[mode];
                    var off = block * 16;
                    c.forwardTransform(TX_4X4, txType, diff, 0, 4, cf, off);
                    var scan = Vp9RtCoder.SCANS[txType];
                    var eob = quantize(true, cf, off, 16, qc, dq, scan, pidx, q, TX_4X4);
                    var ctx = tempAbove[idx] + tempLeft[idy];
                    ratey += costCoeffs(0, TX_4X4, txType, off, eob, ctx, false);
                    tempAbove[idx] = tempLeft[idy] = (byte) (eob > 0 ? 1 : 0);
                    if (!enc.lossless) {
                        long err = 0;
                        for (var k = 0; k < 16; k++) {
                            var d = cf[off + k] - dq[off + k];
                            err += (long) d * d;
                        }
                        distortion += err >> 2;
                    }
                    if (rdCost(ratey, distortion) >= bestRd) {
                        over = true;
                        break;
                    }
                    if (eob > 0) {
                        c.reconstruct(TX_4X4, txType, eob, dq, off, scan, dst, dOff, dstStride);
                    }
                }
            }
            if (over) {
                continue;
            }
            rate += ratey;
            var thisRd = rdCost(rate, distortion);
            if (thisRd < bestRd) {
                blockRate = rate;
                blockRateY = ratey;
                blockDist = distortion;
                bestRd = thisRd;
                blockBestMode = mode;
                System.arraycopy(tempAbove, 0, above, aboveOff, w4);
                System.arraycopy(tempLeft, 0, left, leftOff, h4);
                for (var y = 0; y < h4 * 4; y++) {
                    System.arraycopy(dst, dstInit + y * dstStride, bestSubRecon, y * 8, w4 * 4);
                }
            }
        }
        if (bestRd >= rdThresh) {
            return bestRd;
        }
        for (var y = 0; y < h4 * 4; y++) {
            System.arraycopy(bestSubRecon, y * 8, dst, dstInit + y * dstStride, w4 * 4);
        }
        return bestRd;
    }

    /**
     * The per-filter results of the sub-block mode search of a block smaller than 8x8
     * ({@code BEST_SEG_INFO}): for each sub-block and inter mode its cost, rates, distortion,
     * prediction error, ends of block, vectors and nonzero contexts, then the totals of the
     * sub-blocks' best modes.
     */
    private static final class SegInfo {
        /**
         * The rate-distortion cost of each sub-block and mode, {@code [block * 4 + mode]}.
         */
        final long[] brdcost = new long[16];

        /**
         * The rate of each sub-block and mode, vectors and tokens.
         */
        final int[] brate = new int[16];

        /**
         * The token rate of each sub-block and mode.
         */
        final int[] byrate = new int[16];

        /**
         * The distortion of each sub-block and mode.
         */
        final long[] bdist = new long[16];

        /**
         * The prediction error of each sub-block and mode.
         */
        final long[] bsse = new long[16];

        /**
         * The end of block of each 4x4 block and mode.
         */
        final int[] eobs = new int[16];

        /**
         * The first-reference vector of each 4x4 block and mode.
         */
        final int[] mvs0 = new int[16];

        /**
         * The second-reference vector of each 4x4 block and mode.
         */
        final int[] mvs1 = new int[16];

        /**
         * The above nonzero contexts after each sub-block and mode, two per entry.
         */
        final byte[] ta = new byte[32];

        /**
         * The left nonzero contexts after each sub-block and mode, two per entry.
         */
        final byte[] tl = new byte[32];

        /**
         * The chosen mode of each 4x4 block.
         */
        final int[] modes = new int[4];

        /**
         * The total cost of the sub-blocks' best modes, or the budget while searching.
         */
        long segmentRd;

        /**
         * The total rate.
         */
        int r;

        /**
         * The total distortion.
         */
        long d;

        /**
         * The total prediction error.
         */
        long sse;

        /**
         * The total token rate.
         */
        int segmentYrate;

        /**
         * The vector the next new motion vector search starts from.
         */
        int mvp;

        /**
         * Resets every entry ({@code vp9_zero}).
         */
        void clear() {
            Arrays.fill(brdcost, 0);
            Arrays.fill(brate, 0);
            Arrays.fill(byrate, 0);
            Arrays.fill(bdist, 0);
            Arrays.fill(bsse, 0);
            Arrays.fill(eobs, 0);
            Arrays.fill(mvs0, 0);
            Arrays.fill(mvs1, 0);
            Arrays.fill(ta, (byte) 0);
            Arrays.fill(tl, (byte) 0);
            Arrays.fill(modes, 0);
            segmentRd = 0;
            r = 0;
            d = 0;
            sse = 0;
            segmentYrate = 0;
            mvp = 0;
        }
    }

    /**
     * The sub-block search results of each interpolation filter.
     */
    private final SegInfo[] segInfos = {new SegInfo(), new SegInfo(), new SegInfo()};

    /**
     * The new vector of each sub-block and reference of the current reference combination,
     * {@code [block * 4 + ref]} ({@code seg_mvs}).
     */
    private final int[] segMvs = new int[16];

    /**
     * The candidate vectors of the sub-block being searched, {@code [mode - NEARESTMV][ref]}.
     */
    private final int[] labelMv = new int[16];

    /**
     * The vectors of each mode of the sub-block being searched, {@code [mode - NEARESTMV][which]}.
     */
    private final int[] modeMv = new int[8];

    /**
     * The above nonzero contexts of the sub-block search.
     */
    private final byte[] segAbove = new byte[2];

    /**
     * The left nonzero contexts of the sub-block search.
     */
    private final byte[] segLeft = new byte[2];

    /**
     * The best decision of the sub-8x8 mode search so far.
     */
    private final Choice bestSub = new Choice();

    /**
     * The decision of the best interpolation filter of a reference combination.
     */
    private final Choice filterBest = new Choice();

    /**
     * The ends of block of the 4x4 blocks of the current block, by block.
     */
    private final int[] subEobs = new int[4];

    /**
     * The results of the last {@link #bestSub8x8Mode}: rate, token rate, distortion and
     * prediction error.
     */
    private int segRate;

    /**
     * The token rate of the last {@link #bestSub8x8Mode}.
     */
    private int segRateY;

    /**
     * The distortion of the last {@link #bestSub8x8Mode}.
     */
    private long segDist;

    /**
     * The prediction error of the last {@link #bestSub8x8Mode}.
     */
    private long segSse;

    /**
     * Whether every 4x4 block of the last {@link #bestSub8x8Mode} quantized to zero.
     */
    private boolean segSkippable;

    /**
     * Searches the modes of an inter frame block smaller than 8x8: for each reference
     * combination in the order of {@link #SUB8X8_REF0}, the best mode and vector of every
     * sub-block under the best interpolation filter, then the chroma of the 8x8 block, or the
     * intra modes of every sub-block ({@code vp9_rd_pick_inter_mode_sub8x8}).
     *
     * @param bsize the block size, smaller than 8x8
     * @param choice receives the decision
     * @param bestRdSoFar the budget
     */
    private void pickInterSub8x8(int bsize, Choice choice, long bestRdSoFar) {
        var m = c.mi;
        var sf = enc.goodSpeed;
        var bestRd = bestRdSoFar;
        var bestYrd = bestRdSoFar;
        var intraCostPenalty = (20 * Vp9EncTables.dcQuant(c.qindex, 0)) >> 4;
        Arrays.fill(segMvs, Vp9RtCoder.INVALID_MV);
        var refSkipMask = 0;
        var secondSkipMask = 0;
        for (var ref = LAST_FRAME; ref <= ALTREF_FRAME; ref++) {
            var picture = enc.activeRef(ref);
            refUsable[ref] = enc.refFrameFlag(ref) && picture != null;
            if (refUsable[ref]) {
                prepareReference(ref, bsize);
            } else {
                refSkipMask |= 1 << ref;
                secondSkipMask |= SECOND_REF_FRAME_MASK;
            }
            frameMv[ref(NEWMV, ref)] = Vp9RtCoder.INVALID_MV;
            frameMv[ref(ZEROMV, ref)] = 0;
        }
        var rateUvIntra = Integer.MAX_VALUE;
        var rateUvTokens = 0;
        long distUvIntra = 0;
        var modeUv = DC_PRED;
        var threshes = enc.rdThreshes;
        var threshBase = (m.segmentId * 13 + bsize) * MAX_MODES;
        var factBase = bsize * MAX_MODES;
        var compoundSearch = enc.referenceMode == Vp9RtEncoder.REFERENCE_MODE_SELECT;
        var switchable = enc.interpFilter == SWITCHABLE;
        var best = bestSub;
        var bestRefIndex = 0;
        var bestRef0 = INTRA_FRAME;
        var bestRate = Integer.MAX_VALUE;
        var bestDist = Long.MAX_VALUE;
        var bestSkip2 = false;
        for (var refIndex = 0; refIndex < MAX_REFS; refIndex++) {
            var ref = SUB8X8_REF0[refIndex];
            var second = SUB8X8_REF1[refIndex];
            var comp = second > INTRA_FRAME;
            if (refIndex == 3 && sf.modeSkipStart < MAX_MODES) {
                switch (bestRef0) {
                    case LAST_FRAME -> {
                        refSkipMask |= (1 << GOLDEN_FRAME) | (1 << ALTREF_FRAME);
                        secondSkipMask |= SECOND_REF_FRAME_MASK;
                    }
                    case GOLDEN_FRAME -> {
                        refSkipMask |= (1 << LAST_FRAME) | (1 << ALTREF_FRAME);
                        secondSkipMask |= SECOND_REF_FRAME_MASK;
                    }
                    case ALTREF_FRAME -> refSkipMask |= (1 << GOLDEN_FRAME) | (1 << LAST_FRAME);
                    default -> {
                    }
                }
            }
            if ((refSkipMask & (1 << ref)) != 0 && (secondSkipMask & (1 << Math.max(0, second))) != 0) {
                continue;
            }
            var threshold = threshes[threshBase + refIndex];
            if (threshold == Integer.MAX_VALUE || bestRd < (((long) threshold * threshFact[factBase + refIndex]) >> 5)) {
                continue;
            }
            if (comp) {
                if (!compoundSearch || enc.signBias[ref] == enc.signBias[second] || !refUsable[second]) {
                    continue;
                }
                if ((sf.modeSearchSkipFlags & Vp9GoodSpeed.SKIP_COMP_BESTINTRA) != 0 && bestRef0 == INTRA_FRAME) {
                    continue;
                }
            }
            if (enc.srcFrameAltRef && enc.unfilteredAltRef) {
                continue;
            }
            m.txSize = TX_4X4;
            m.uvMode = DC_PRED;
            m.ref0 = ref;
            m.ref1 = second;
            m.interpFilter = switchable ? EIGHTTAP : enc.interpFilter;
            c.skip = false;
            var rate2 = 0;
            long dist2 = 0;
            var rateY = 0;
            var rateUv = 0;
            long distUv = 0;
            var skippable = false;
            long totalSse = Integer.MAX_VALUE;
            var thisSkip2 = false;
            var compmodeCost = 0;
            if (ref == INTRA_FRAME) {
                if (intraSub8x8Y(bsize, bestRd) >= bestRd) {
                    continue;
                }
                rate2 += subRate + intraCostPenalty;
                rateY = subRateY;
                dist2 += subDist;
                if (rateUvIntra == Integer.MAX_VALUE) {
                    pickIntraUv(BLOCK_8X8, TX_4X4, true);
                    rateUvIntra = (int) uvIntra[TX_4X4 * 5];
                    rateUvTokens = (int) uvIntra[TX_4X4 * 5 + 1];
                    distUvIntra = uvIntra[TX_4X4 * 5 + 2];
                    modeUv = (int) uvIntra[TX_4X4 * 5 + 4];
                }
                rate2 += rateUvIntra;
                rateUv = rateUvTokens;
                dist2 += distUvIntra;
                distUv = distUvIntra;
                m.uvMode = modeUv;
            } else {
                var thisRdThresh = threshes[threshBase + (ref == LAST_FRAME ? 0 : ref == GOLDEN_FRAME ? 2 : 1)];
                var tmpBestFilter = EIGHTTAP;
                var tmpBestRd = Long.MAX_VALUE;
                var tmpBestRdu = Long.MAX_VALUE;
                var predExists = false;
                long tmpRd;
                if (enc.interpFilter != Vp9Constants.BILINEAR) {
                    if (sourceVariance < sf.disableFilterSearchVarThresh) {
                        tmpBestFilter = EIGHTTAP;
                    } else if (sf.adaptivePredInterpFilter == 1 && choice.predInterpFilter < SWITCHABLE) {
                        tmpBestFilter = choice.predInterpFilter;
                    } else {
                        for (var filter = 0; filter < SWITCHABLE_FILTERS; filter++) {
                            m.interpFilter = filter;
                            tmpRd = bestSub8x8Mode(bsize, bestYrd, thisRdThresh, filter);
                            if (tmpRd == Long.MAX_VALUE) {
                                continue;
                            }
                            var rsRd = rdCost(switchableRate(filter), 0);
                            if (switchable) {
                                tmpRd += rsRd;
                            }
                            var newBest = tmpRd < tmpBestRd;
                            if (newBest) {
                                tmpBestFilter = filter;
                                tmpBestRd = tmpRd;
                            }
                            if ((newBest && switchable) || (filter == enc.interpFilter && !switchable)) {
                                tmpBestRdu = tmpRd;
                                filterBest.capture(m);
                                filterBest.rate = segRate;
                                filterBest.dist = segDist;
                                filterBest.rd = segSse;
                                filterBest.skip = segSkippable;
                                filterRateY = segRateY;
                                predExists = true;
                                if (filter == 0 && sf.useRdBreakout && bestRd < Long.MAX_VALUE
                                        && tmpBestRdu / 2 > bestRd) {
                                    tmpBestFilter = filter;
                                    tmpBestRdu = Long.MAX_VALUE;
                                    break;
                                }
                            }
                        }
                    }
                }
                if (tmpBestRdu == Long.MAX_VALUE && predExists) {
                    continue;
                }
                m.interpFilter = switchable ? tmpBestFilter : enc.interpFilter;
                int rate;
                long distortion;
                if (!predExists) {
                    tmpRd = bestSub8x8Mode(bsize, bestYrd, thisRdThresh, 0);
                    if (tmpRd == Long.MAX_VALUE) {
                        continue;
                    }
                    rate = segRate;
                    rateY = segRateY;
                    distortion = segDist;
                    totalSse = segSse;
                    skippable = segSkippable;
                } else {
                    filterBest.apply(m);
                    rate = filterBest.rate;
                    rateY = filterRateY;
                    distortion = filterBest.dist;
                    totalSse = filterBest.rd;
                    skippable = filterBest.skip;
                }
                rate2 += rate;
                dist2 += distortion;
                if (switchable) {
                    rate2 += switchableRate(m.interpFilter);
                }
                compmodeCost = Vp9EncTables.costBit(compModeProb(), comp ? 1 : 0);
                var budget = bestRd == Long.MAX_VALUE ? Long.MAX_VALUE
                        : bestRd - Math.min(rdCost(rate2, dist2), rdCost(0, totalSse));
                if (budget > 0) {
                    c.buildInterPredictor(1, BLOCK_8X8);
                    c.buildInterPredictor(2, BLOCK_8X8);
                    superBlockUvrd(BLOCK_8X8, budget);
                    if (rdRate == Integer.MAX_VALUE) {
                        continue;
                    }
                    rateUv = rdRate;
                    distUv = rdDist;
                    rate2 += rateUv;
                    dist2 += distUv;
                    skippable &= rdSkippable;
                    totalSse += rdSse;
                }
            }
            if (compoundSearch) {
                rate2 += compmodeCost;
            }
            rate2 += comp ? compRefCost(ref) : singleRefCost(ref);
            var skip0 = c.skipCost(0);
            var skip1 = c.skipCost(1);
            if (ref != INTRA_FRAME && !enc.lossless) {
                if (rdCost((long) rateY + rateUv + skip0, dist2) < rdCost(skip1, totalSse)) {
                    rate2 += skip0;
                } else {
                    rate2 += skip1;
                    dist2 = totalSse;
                    rate2 -= rateY + rateUv;
                    rateUv = 0;
                    thisSkip2 = true;
                }
            } else {
                rate2 += skip0;
            }
            var thisRd = rdCost(rate2, dist2);
            var earlyTerm = false;
            if (thisRd < bestRd) {
                bestRefIndex = refIndex;
                bestRef0 = ref;
                if (ref == INTRA_FRAME) {
                    m.mv0 = 0;
                    m.interpFilter = SWITCHABLE_FILTERS;
                }
                bestRate = rate2;
                bestDist = dist2;
                bestRd = thisRd;
                bestYrd = bestRd - rdCost(rateUv, distUv);
                best.capture(m);
                bestSkip2 = thisSkip2;
                if ((sf.modeSearchSkipFlags & Vp9GoodSpeed.EARLY_TERMINATE) != 0 && refIndex > MIN_EARLY_TERM_INDEX) {
                    var qstep = enc.quant.yDequant[c.qindex * 2 + 1];
                    var scale = sourceVariance < 16 ? 3 : 4;
                    if (ref > INTRA_FRAME && dist2 * scale < (long) qstep * qstep) {
                        earlyTerm = true;
                    }
                }
            }
            if (earlyTerm) {
                break;
            }
        }
        if (bestRd >= bestRdSoFar || bestRate == Integer.MAX_VALUE) {
            return;
        }
        updateThreshFact(bsize, bestRefIndex);
        choice.capture(best.ref0 > INTRA_FRAME ? subBestInfo(best) : intraBestInfo(best));
        choice.skip = bestSkip2 && best.ref0 > INTRA_FRAME;
        choice.rate = bestRate;
        choice.dist = bestDist;
        choice.rd = bestRd;
    }

    /**
     * The token rate of the decision in {@link #filterBest}.
     */
    private int filterRateY;

    /**
     * Returns the best inter decision of a block smaller than 8x8 as mode information: the block
     * vectors are those of the last sub-block, the second-reference vectors zero without a
     * second reference.
     *
     * @param best the decision
     * @return the block's mode information, filled from the decision
     */
    private ModeInfo subBestInfo(Choice best) {
        var m = c.mi;
        best.apply(m);
        if (m.ref1 <= INTRA_FRAME) {
            Arrays.fill(m.subMv1, 0);
        }
        m.mv0 = m.subMv0[3];
        m.mv1 = m.ref1 > INTRA_FRAME ? m.subMv1[3] : 0;
        m.mode = m.subModes[3];
        return m;
    }

    /**
     * Returns the best intra decision of a block smaller than 8x8 as mode information.
     *
     * @param best the decision
     * @return the block's mode information, filled from the decision
     */
    private ModeInfo intraBestInfo(Choice best) {
        var m = c.mi;
        best.apply(m);
        m.mode = m.subModes[3];
        return m;
    }

    /**
     * Searches the best mode and vector of every sub-block of an inter block smaller than 8x8
     * under the block's interpolation filter: the nearest, near, zero and new vectors of each
     * sub-block in turn, each coded with 4x4 transforms ({@code rd_pick_best_sub8x8_mode}); the
     * sub-blocks' choices are left in the block's mode information and the totals in
     * {@link #segRate}, {@link #segRateY}, {@link #segDist}, {@link #segSse} and
     * {@link #segSkippable}.
     *
     * @param bsize the block size, smaller than 8x8
     * @param bestRdSoFar the budget
     * @param mvThresh the threshold of the block's references, above which a sub-block's best
     *                 cost skips its new vector search
     * @param filterIdx the index of the filter, which may reuse the results of the filters before
     * @return the cost, or {@link Long#MAX_VALUE} beyond the budget
     */
    private long bestSub8x8Mode(int bsize, long bestRdSoFar, int mvThresh, int filterIdx) {
        var m = c.mi;
        var sf = enc.goodSpeed;
        var bsi = segInfos[filterIdx];
        bsi.clear();
        var r0 = m.ref0;
        var r1 = m.ref1;
        var comp = r1 > INTRA_FRAME;
        var bestRef0 = refBestMv[r0];
        var bestRef1 = comp ? refBestMv[r1] : 0;
        bsi.segmentRd = bestRdSoFar;
        bsi.mvp = bestRef0;
        Arrays.fill(bsi.modes, ZEROMV);
        var above = enc.aboveEntropy[0];
        var left = c.leftEntropy[0];
        segAbove[0] = above[c.miCol * 2];
        segAbove[1] = above[c.miCol * 2 + 1];
        segLeft[0] = left[(c.miRow * 2) & 15];
        segLeft[1] = left[((c.miRow * 2) & 15) + 1];
        var w4 = Vp9RtCoder.NUM_4X4_WIDE[bsize];
        var h4 = Vp9RtCoder.NUM_4X4_HIGH[bsize];
        var labelMvThresh = mvThresh / 4;
        var pick = c.pick;
        var motion = c.motion;
        var br = 0;
        long bd = 0;
        long blockSse = 0;
        var segmentYrate = 0;
        long thisSegmentRd = 0;
        for (var idy = 0; idy < 2; idy += h4) {
            for (var idx = 0; idx < 2; idx += w4) {
                var block = idy * 2 + idx;
                var modeSelected = ZEROMV;
                var bestRd = Long.MAX_VALUE;
                for (var which = 0; which < (comp ? 2 : 1); which++) {
                    var frame = which == 0 ? r0 : r1;
                    labelMv[ref(ZEROMV, frame)] = 0;
                    pick.appendSub8x8Mvs(frame, which, bsize, block);
                    labelMv[ref(NEARESTMV, frame)] = pick.subNearest;
                    labelMv[ref(NEARMV, frame)] = pick.subNear;
                }
                for (var mode = NEARESTMV; mode <= NEWMV; mode++) {
                    var mi = block * 4 + (mode - NEARESTMV);
                    bsi.brdcost[mi] = Long.MAX_VALUE;
                    if (!checkBestZeroMv(labelMv, mode, r0, r1)) {
                        continue;
                    }
                    bsi.ta[mi * 2] = segAbove[0];
                    bsi.ta[mi * 2 + 1] = segAbove[1];
                    bsi.tl[mi * 2] = segLeft[0];
                    bsi.tl[mi * 2 + 1] = segLeft[1];
                    if (!comp && mode == NEWMV && segMvs[block * 4 + r0] == Vp9RtCoder.INVALID_MV) {
                        if (bestRd < labelMvThresh) {
                            break;
                        }
                        if (sf.sub8x8MvFromPrevious && block > 0) {
                            bsi.mvp = m.subMv0[block - 1];
                            if (block == 2) {
                                bsi.mvp = m.subMv0[block - 2];
                            }
                        }
                        var maxMv = block == 0 ? maxMvContext[r0]
                                : Math.max(Math.abs(bsi.mvp >> 16), Math.abs((short) bsi.mvp)) >> 3;
                        var stepParam = sf.autoMvStepSize && enc.showFrame ? (initSearchRange(maxMv) + enc.mvStepParam) / 2
                                : enc.mvStepParam;
                        var startRow = (bsi.mvp >> 16) >> 3;
                        var startCol = ((short) bsi.mvp) >> 3;
                        if (sf.adaptiveMotionSearch) {
                            var pred = c.predMv[r0];
                            if ((pred >> 16) != Short.MAX_VALUE && (short) pred != Short.MAX_VALUE) {
                                startRow = (pred >> 16) >> 3;
                                startCol = ((short) pred) >> 3;
                            }
                            stepParam = Math.max(stepParam, 8);
                        }
                        stepParam = Math.min(stepParam, Vp9RtMotion.MAX_MVSEARCH_STEPS - 2);
                        var picture = enc.activeRef(r0);
                        var refRow = bestRef0 >> 16;
                        var refCol = (short) bestRef0;
                        motion.setup(bsize, picture);
                        motion.shiftBlock(4 * idx, 4 * idy);
                        motion.setSearchRange(refRow, refCol);
                        var bestSme = motion.fullPixelSearch(startRow, startCol, stepParam, sf.searchMethod,
                                c.sadPerBit4, sf.subpelSearchMethod != Vp9GoodSpeed.SUBPEL_TREE, refRow, refCol);
                        var newMv = ModeInfo.packMv(motion.bestRow, motion.bestCol);
                        if (bestSme < Vp9RtMotion.INT_MAX) {
                            var fullRow = motion.bestRow;
                            var fullCol = motion.bestCol;
                            motion.setup(bsize, picture);
                            motion.shiftBlock(4 * idx, 4 * idy);
                            motion.setSubpelFilter(sf.subpelSearchTaps);
                            subpelSearch(motion, fullRow, fullCol, refRow, refCol);
                            newMv = ModeInfo.packMv(motion.bestRow, motion.bestCol);
                            c.predSse[r0] = (int) Math.min(motion.sse1, Integer.MAX_VALUE);
                            segMvs[block * 4 + r0] = newMv;
                        }
                        c.predMv[r0] = newMv;
                    }
                    if (comp && (segMvs[block * 4 + r1] == Vp9RtCoder.INVALID_MV
                            || segMvs[block * 4 + r0] == Vp9RtCoder.INVALID_MV)) {
                        continue;
                    }
                    if (comp && mode == NEWMV && m.interpFilter == EIGHTTAP) {
                        var iterations = jointSearchIterations(sf.compoundJointSearchIterations, bsize);
                        if (iterations > 0) {
                            jointMotionSearch(bsize, r0, r1, iterations, segMvs[block * 4 + r0],
                                    segMvs[block * 4 + r1], 4 * idx, 4 * idy);
                            segMvs[block * 4 + r0] = jointMv[0];
                            segMvs[block * 4 + r1] = jointMv[1];
                        }
                    }
                    bsi.brate[mi] = setAndCostBmiMvs(block, mode, bsize, bestRef0, bestRef1);
                    var mv0 = modeMv[(mode - NEARESTMV) * 2];
                    var mv1 = modeMv[(mode - NEARESTMV) * 2 + 1];
                    storeLabelMvs(bsi, block, mode, w4, h4, mv0, mv1);
                    if (!mvInBounds(mv0) || (comp && !mvInBounds(mv1))) {
                        continue;
                    }
                    if (filterIdx > 0 && reuseFilterResult(bsi, block, mode, w4, h4, filterIdx, mv0, mv1, comp)) {
                        if (bsi.brdcost[mi] < bestRd) {
                            modeSelected = mode;
                            bestRd = bsi.brdcost[mi];
                        }
                        continue;
                    }
                    var budget = bsi.segmentRd == Long.MAX_VALUE ? Long.MAX_VALUE : bsi.segmentRd - thisSegmentRd;
                    var cost = encodeInterSegment(bsize, block, budget, bsi.ta, mi * 2, bsi.tl, mi * 2);
                    bsi.brdcost[mi] = cost;
                    if (cost < Long.MAX_VALUE) {
                        bsi.byrate[mi] = segLabelRate;
                        bsi.bdist[mi] = segLabelDist;
                        bsi.bsse[mi] = segLabelSse;
                        bsi.brdcost[mi] += rdCost(bsi.brate[mi], 0);
                        bsi.brate[mi] += bsi.byrate[mi];
                        bsi.eobs[mi] = c.eobs[0][block];
                        if (w4 > 1) {
                            bsi.eobs[mi + 4] = c.eobs[0][block + 1];
                        }
                        if (h4 > 1) {
                            bsi.eobs[mi + 8] = c.eobs[0][block + 2];
                        }
                    }
                    if (bsi.brdcost[mi] < bestRd) {
                        modeSelected = mode;
                        bestRd = bsi.brdcost[mi];
                    }
                }
                if (bestRd == Long.MAX_VALUE) {
                    for (var iy = block + 1; iy < 4; iy++) {
                        for (var k = 0; k < 4; k++) {
                            bsi.brdcost[iy * 4 + k] = Long.MAX_VALUE;
                        }
                    }
                    bsi.segmentRd = Long.MAX_VALUE;
                    return Long.MAX_VALUE;
                }
                var mi = block * 4 + (modeSelected - NEARESTMV);
                segAbove[0] = bsi.ta[mi * 2];
                segAbove[1] = bsi.ta[mi * 2 + 1];
                segLeft[0] = bsi.tl[mi * 2];
                segLeft[1] = bsi.tl[mi * 2 + 1];
                setAndCostBmiMvs(block, modeSelected, bsize, bestRef0, bestRef1);
                br += bsi.brate[mi];
                bd += bsi.bdist[mi];
                blockSse += bsi.bsse[mi];
                segmentYrate += bsi.byrate[mi];
                thisSegmentRd += bsi.brdcost[mi];
                if (thisSegmentRd > bsi.segmentRd) {
                    for (var iy = block + 1; iy < 4; iy++) {
                        for (var k = 0; k < 4; k++) {
                            bsi.brdcost[iy * 4 + k] = Long.MAX_VALUE;
                        }
                    }
                    bsi.segmentRd = Long.MAX_VALUE;
                    return Long.MAX_VALUE;
                }
            }
        }
        bsi.r = br;
        bsi.d = bd;
        bsi.segmentYrate = segmentYrate;
        bsi.segmentRd = thisSegmentRd;
        bsi.sse = blockSse;
        System.arraycopy(m.subModes, 0, bsi.modes, 0, 4);
        if (bsi.segmentRd > bestRdSoFar) {
            return Long.MAX_VALUE;
        }
        var skippable = true;
        for (var i = 0; i < 4; i++) {
            var mi = i * 4 + (bsi.modes[i] - NEARESTMV);
            m.subMv0[i] = bsi.mvs0[mi];
            m.subMv1[i] = comp ? bsi.mvs1[mi] : 0;
            subEobs[i] = bsi.eobs[mi];
            c.eobs[0][i] = bsi.eobs[mi];
            m.subModes[i] = bsi.modes[i];
            skippable &= bsi.eobs[mi] == 0;
        }
        segRate = bsi.r;
        segDist = bsi.d;
        segRateY = bsi.segmentYrate;
        segSkippable = skippable;
        segSse = bsi.sse;
        m.mode = bsi.modes[3];
        return bsi.segmentRd;
    }

    /**
     * Records the vectors of a sub-block's mode for its 4x4 blocks.
     *
     * @param bsi the search results
     * @param block the sub-block
     * @param mode the inter mode, from {@code NEARESTMV} to {@code NEWMV}
     * @param w4 the sub-block width in 4x4 blocks
     * @param h4 the sub-block height in 4x4 blocks
     * @param mv0 the first-reference vector
     * @param mv1 the second-reference vector
     */
    private static void storeLabelMvs(SegInfo bsi, int block, int mode, int w4, int h4, int mv0, int mv1) {
        var mi = block * 4 + (mode - NEARESTMV);
        bsi.mvs0[mi] = mv0;
        bsi.mvs1[mi] = mv1;
        if (w4 > 1) {
            bsi.mvs0[mi + 4] = mv0;
            bsi.mvs1[mi + 4] = mv1;
        }
        if (h4 > 1) {
            bsi.mvs0[mi + 8] = mv0;
            bsi.mvs1[mi + 8] = mv1;
        }
    }

    /**
     * Copies the results of a sub-block's mode from an earlier filter's search when its vectors
     * are whole-sample and equal, which makes the filter irrelevant.
     *
     * @param bsi the current filter's results
     * @param block the sub-block
     * @param mode the inter mode, from {@code NEARESTMV} to {@code NEWMV}
     * @param w4 the sub-block width in 4x4 blocks
     * @param h4 the sub-block height in 4x4 blocks
     * @param filterIdx the current filter's index, above 0
     * @param mv0 the first-reference vector
     * @param mv1 the second-reference vector
     * @param comp whether the block has a second reference
     * @return whether the results were copied
     */
    private boolean reuseFilterResult(SegInfo bsi, int block, int mode, int w4, int h4, int filterIdx, int mv0,
                                      int mv1, boolean comp) {
        var mi = block * 4 + (mode - NEARESTMV);
        var refBsi = segInfos[0];
        var subpel = hasSubpel(mv0) || (comp && hasSubpel(mv1));
        var haveRef = refBsi.mvs0[mi] == mv0 && (!comp || refBsi.mvs1[mi] == mv1);
        if (filterIdx > 1 && !subpel && !haveRef) {
            refBsi = segInfos[1];
            haveRef = refBsi.mvs0[mi] == mv0 && (!comp || refBsi.mvs1[mi] == mv1);
        }
        if (subpel || !haveRef || refBsi.brdcost[mi] == Long.MAX_VALUE) {
            return false;
        }
        bsi.brdcost[mi] = refBsi.brdcost[mi];
        bsi.brate[mi] = refBsi.brate[mi];
        bsi.byrate[mi] = refBsi.byrate[mi];
        bsi.bdist[mi] = refBsi.bdist[mi];
        bsi.bsse[mi] = refBsi.bsse[mi];
        bsi.eobs[mi] = refBsi.eobs[mi];
        bsi.mvs0[mi] = refBsi.mvs0[mi];
        bsi.mvs1[mi] = refBsi.mvs1[mi];
        bsi.ta[mi * 2] = refBsi.ta[mi * 2];
        bsi.ta[mi * 2 + 1] = refBsi.ta[mi * 2 + 1];
        bsi.tl[mi * 2] = refBsi.tl[mi * 2];
        bsi.tl[mi * 2 + 1] = refBsi.tl[mi * 2 + 1];
        if (w4 > 1) {
            bsi.eobs[mi + 4] = refBsi.eobs[mi + 4];
        }
        if (h4 > 1) {
            bsi.eobs[mi + 8] = refBsi.eobs[mi + 8];
        }
        return true;
    }

    /**
     * Returns whether a vector has a fractional component in sixteenth samples
     * ({@code mv_has_subpel}).
     *
     * @param mv the packed eighth-pixel vector
     * @return whether it does
     */
    private static boolean hasSubpel(int mv) {
        return ((mv >> 16) & 0x0F) != 0 || ((short) mv & 0x0F) != 0;
    }

    /**
     * Sets the vectors of a sub-block's mode in the block's mode information and returns the
     * mode's rate, its vectors included ({@code set_and_cost_bmi_mvs}); the vectors are left in
     * {@link #modeMv}.
     *
     * @param block the sub-block
     * @param mode the inter mode, from {@code NEARESTMV} to {@code NEWMV}
     * @param bsize the block size, smaller than 8x8
     * @param bestRef0 the block's best predicted vector of its first reference
     * @param bestRef1 the block's best predicted vector of its second reference
     * @return the rate
     */
    private int setAndCostBmiMvs(int block, int mode, int bsize, int bestRef0, int bestRef1) {
        var m = c.mi;
        var comp = m.hasSecondRef();
        var motion = c.motion;
        var mvCost = 0;
        int mv0;
        var mv1 = 0;
        switch (mode) {
            case NEWMV -> {
                mv0 = segMvs[block * 4 + m.ref0];
                mvCost += motion.mvBitCost(mv0 >> 16, (short) mv0, bestRef0 >> 16, (short) bestRef0,
                        MV_COST_WEIGHT_SUB);
                if (comp) {
                    mv1 = segMvs[block * 4 + m.ref1];
                    mvCost += motion.mvBitCost(mv1 >> 16, (short) mv1, bestRef1 >> 16, (short) bestRef1,
                            MV_COST_WEIGHT_SUB);
                }
            }
            case NEARMV, NEARESTMV -> {
                mv0 = labelMv[ref(mode, m.ref0)];
                if (comp) {
                    mv1 = labelMv[ref(mode, m.ref1)];
                }
            }
            default -> mv0 = 0;
        }
        modeMv[(mode - NEARESTMV) * 2] = mv0;
        modeMv[(mode - NEARESTMV) * 2 + 1] = mv1;
        var w4 = Vp9RtCoder.NUM_4X4_WIDE[bsize];
        var h4 = Vp9RtCoder.NUM_4X4_HIGH[bsize];
        for (var idy = 0; idy < h4; idy++) {
            for (var idx = 0; idx < w4; idx++) {
                var j = block + idy * 2 + idx;
                m.subMv0[j] = mv0;
                if (comp) {
                    m.subMv1[j] = mv1;
                }
                m.subModes[j] = mode;
            }
        }
        return enc.interModeCost[refModeContext[m.ref0] * 4 + (mode - NEARESTMV)] + mvCost;
    }

    /**
     * The token rate of the last {@link #encodeInterSegment}.
     */
    private int segLabelRate;

    /**
     * The distortion of the last {@link #encodeInterSegment}.
     */
    private long segLabelDist;

    /**
     * The prediction error of the last {@link #encodeInterSegment}.
     */
    private long segLabelSse;

    /**
     * Predicts one sub-block of an inter block smaller than 8x8 with its vectors, then codes its
     * 4x4 transform blocks and measures them ({@code encode_inter_mb_segment}); the token rate,
     * distortion and prediction error are left in {@link #segLabelRate}, {@link #segLabelDist}
     * and {@link #segLabelSse}, the nonzero contexts in the given arrays.
     *
     * @param bsize the block size, smaller than 8x8
     * @param block the sub-block
     * @param bestYrd the budget
     * @param ta the above nonzero contexts, updated
     * @param taOff the index of the first above context
     * @param tl the left nonzero contexts, updated
     * @param tlOff the index of the first left context
     * @return the cost, or {@link Long#MAX_VALUE} beyond the budget
     */
    private long encodeInterSegment(int bsize, int block, long bestYrd, byte[] ta, int taOff, byte[] tl, int tlOff) {
        var m = c.mi;
        var w = Vp9RtCoder.NUM_4X4_WIDE[bsize] * 4;
        var h = Vp9RtCoder.NUM_4X4_HIGH[bsize] * 4;
        var recon = enc.recon;
        var dst = recon.planes8[0];
        var stride = recon.strides[0];
        var bx = 4 * (block & 1);
        var by = 4 * (block >> 1);
        var dOff = c.dstOff[0] + by * stride + bx;
        var x0 = c.miCol * 8 + bx;
        var y0 = c.miRow * 8 + by;
        var predictor = c.predictor;
        predictor.predictInter(enc.activeRef(m.ref0), 0, x0, y0, m.subMv0[block], m.interpFilter, dst, dOff, stride, w,
                h, false);
        if (m.hasSecondRef()) {
            predictor.predictInter(enc.activeRef(m.ref1), 0, x0, y0, m.subMv1[block], m.interpFilter, dst, dOff, stride,
                    w, h, true);
        }
        var src = enc.source;
        var srcStride = src.strides[0];
        var sOff = c.srcOff[0] + by * srcStride + bx;
        var diff = diffs[0];
        c.edsp.subtract(diff, by * 8 + bx, 8, src.planes[0], sOff, srcStride, dst, dOff, stride, w, h);
        var q = enc.quant;
        var pidx = c.qindex * 2;
        var cf = c.coeff[0];
        var qc = c.qcoeff[0];
        var dq = c.dqcoeff[0];
        var scan = Vp9RtCoder.SCANS[0];
        long thisDist = 0;
        long thisSse = 0;
        var thisRate = 0;
        var k = block;
        for (var idy = 0; idy < h / 4; idy++) {
            for (var idx = 0; idx < w / 4; idx++) {
                k += idy * 2 + idx;
                var ctx = ta[taOff + (k & 1)] + tl[tlOff + (k >> 1)];
                var off = k * 16;
                c.forwardTransform(TX_4X4, DCT_DCT, diff, (k >> 1) * 32 + (k & 1) * 4, 8, cf, off);
                var eob = quantize(true, cf, off, 16, qc, dq, scan, pidx, q, TX_4X4);
                c.eobs[0][k] = eob;
                long err = 0;
                long ssz = 0;
                for (var j = 0; j < 16; j++) {
                    var a = cf[off + j];
                    var d = a - dq[off + j];
                    err += (long) d * d;
                    ssz += (long) a * a;
                }
                thisDist += err;
                thisSse += ssz;
                thisRate += costCoeffs(0, TX_4X4, DCT_DCT, off, eob, ctx, true);
                ta[taOff + (k & 1)] = tl[tlOff + (k >> 1)] = (byte) (eob > 0 ? 1 : 0);
                var rd1 = rdCost(thisRate, thisDist >> 2);
                var rd2 = rdCost(0, thisSse >> 2);
                if (Math.min(rd1, rd2) >= bestYrd) {
                    return Long.MAX_VALUE;
                }
            }
        }
        segLabelDist = thisDist >> 2;
        segLabelRate = thisRate;
        segLabelSse = thisSse >> 2;
        return rdCost(segLabelRate, segLabelDist);
    }

    /**
     * Refines a full-pixel vector of the current search with the sub-pixel search of the speed
     * features ({@code find_fractional_mv_step}).
     *
     * @param motion the search, set up on the block and reference
     * @param row the full-pixel row
     * @param col the full-pixel column
     * @param refRow the eighth-pixel row of the reference vector
     * @param refCol the eighth-pixel column of the reference vector
     * @return the best cost
     */
    private long subpelSearch(Vp9RtMotion motion, int row, int col, int refRow, int refCol) {
        var sf = enc.goodSpeed;
        return switch (sf.subpelSearchMethod) {
            case Vp9GoodSpeed.SUBPEL_TREE_PRUNED -> motion.subpelPruned(row, col, refRow, refCol, enc.allowHp, 0,
                    sf.subpelIters);
            case Vp9GoodSpeed.SUBPEL_TREE_PRUNED_MORE -> motion.subpelPrunedMore(row, col, refRow, refCol,
                    enc.allowHp, 0, sf.subpelIters);
            default -> motion.subpelTree(row, col, refRow, refCol, enc.allowHp, 0, sf.subpelIters);
        };
    }

    /**
     * Returns the iterations of the joint motion search of a compound block
     * ({@code get_joint_search_iters}).
     *
     * @param level the speed feature level, {@link Vp9GoodSpeed#compoundJointSearchIterations}
     * @param bsize the block size
     * @return the iterations, 0 for none
     */
    private static int jointSearchIterations(int level, int bsize) {
        if (level >= 2) {
            return 0;
        }
        if (level == 1) {
            return bsize < BLOCK_8X8 ? 0 : bsize <= BLOCK_16X16 ? 2 : MAX_JOINT_ITERS;
        }
        return MAX_JOINT_ITERS;
    }

    /**
     * Refines the vectors of a compound block in turn, each searched against the average of its
     * prediction and the prediction of the other at its current vector, starting from the single
     * reference vectors, until a search finds no better vector or the iterations run out
     * ({@code joint_motion_search}); the vectors are left in {@link #frameMv}.
     *
     * @param bsize the block size
     * @param r0 the first reference
     * @param r1 the second reference
     * @param iterations the most iterations
     * @param start0 the first reference's start vector
     * @param start1 the second reference's start vector
     * @param offX the searched block's column offset in the current block, in samples, for a
     *             sub-block of a block smaller than 8x8
     * @param offY the searched block's row offset
     * @return the rate of the two vectors; the vectors are left in {@link #jointMv}
     */
    private int jointMotionSearch(int bsize, int r0, int r1, int iterations, int start0, int start1, int offX,
                                  int offY) {
        var sf = enc.goodSpeed;
        var motion = c.motion;
        var w = Vp9RtCoder.NUM_4X4_WIDE[bsize] * 4;
        var h = Vp9RtCoder.NUM_4X4_HIGH[bsize] * 4;
        var refs = jointRefs;
        refs[0] = r0;
        refs[1] = r1;
        var mvs = jointMv;
        mvs[0] = start0;
        mvs[1] = start1;
        iterMvs[0] = mvs[0];
        iterMvs[1] = mvs[1];
        var lastBest = jointBest;
        lastBest[0] = UINT_MAX;
        lastBest[1] = UINT_MAX;
        var filter = enc.interpFilter == SWITCHABLE ? EIGHTTAP : enc.interpFilter;
        for (var ite = 0; ite < iterations; ite++) {
            var id = ite % 2;
            if (ite >= 2 && iterMvs[(ite - 2) * 2 + 1 - id] == iterMvs[ite * 2 + 1 - id]
                    && fullPel(iterMvs[ite * 2 + id]) == fullPel(iterMvs[(ite - 2) * 2 + id])) {
                break;
            }
            predictSecond(enc.activeRef(refs[1 - id]), mvs[1 - id], filter, w, h, offX, offY);
            var ref = refs[id];
            var picture = enc.activeRef(ref);
            var refRow = refBestMv[ref] >> 16;
            var refCol = (short) refBestMv[ref];
            motion.setup(bsize, picture);
            motion.shiftBlock(offX, offY);
            motion.setSearchRange(refRow, refCol);
            motion.setSecondPrediction(secondPred);
            long bestSme = motion.refiningSearchCompound((mvs[id] >> 16) >> 3, ((short) mvs[id]) >> 3,
                    c.sadPerBit16, JOINT_SEARCH_RANGE, refRow, refCol);
            var row = motion.bestRow;
            var col = motion.bestCol;
            if (bestSme < UINT_MAX) {
                bestSme = motion.mvpredAvVar(row, col, refRow, refCol);
            }
            if (bestSme < UINT_MAX) {
                motion.setup(bsize, picture);
                motion.shiftBlock(offX, offY);
                motion.setSecondPrediction(secondPred);
                motion.setSubpelFilter(sf.subpelSearchTaps);
                Arrays.fill(motion.costList, Vp9RtMotion.INT_MAX);
                bestSme = subpelSearch(motion, row, col, refRow, refCol);
                row = motion.bestRow;
                col = motion.bestCol;
            } else {
                row *= 8;
                col *= 8;
            }
            if (bestSme < lastBest[id]) {
                mvs[id] = ModeInfo.packMv(row, col);
                lastBest[id] = bestSme;
            } else {
                break;
            }
            if (ite < iterations - 1) {
                iterMvs[(ite + 1) * 2] = mvs[0];
                iterMvs[(ite + 1) * 2 + 1] = mvs[1];
            }
        }
        return motion.mvBitCost(mvs[0] >> 16, (short) mvs[0], refBestMv[r0] >> 16, (short) refBestMv[r0])
                + motion.mvBitCost(mvs[1] >> 16, (short) mvs[1], refBestMv[r1] >> 16, (short) refBestMv[r1]);
    }

    /**
     * The references of the current joint motion search.
     */
    private final int[] jointRefs = new int[2];

    /**
     * The vectors of the current joint motion search.
     */
    private final int[] jointMv = new int[2];

    /**
     * The best cost each reference's search of the current joint motion search found.
     */
    private final long[] jointBest = new long[2];

    /**
     * The largest unsigned 32-bit cost, libvpx's {@code UINT_MAX}.
     */
    private static final long UINT_MAX = 0xFFFFFFFFL;

    /**
     * Returns a packed vector rounded down to whole samples, packed again.
     *
     * @param mv the packed eighth-pixel vector
     * @return the packed full-pixel vector
     */
    private static int fullPel(int mv) {
        return ModeInfo.packMv((mv >> 16) >> 3, ((short) mv) >> 3);
    }

    /**
     * Predicts the current block's luma from a reference at a vector into {@link #secondPred}
     * ({@code vp9_build_inter_predictor}).
     *
     * @param picture the reference
     * @param mv the packed eighth-pixel vector
     * @param filter the interpolation filter
     * @param w the block width
     * @param h the block height
     * @param offX the block's column offset in the current block, in samples
     * @param offY the block's row offset
     */
    private void predictSecond(Vp9EncPicture picture, int mv, int filter, int w, int h, int offX, int offY) {
        var row = mv >> 16;
        var col = (short) mv;
        var stride = picture.strides[0];
        var off = picture.offset(0, c.miCol * 8 + offX, c.miRow * 8 + offY) + (row >> 3) * stride + (col >> 3);
        c.dsp.convolve(picture.planes[0], off, stride, secondPred, 0, w, Vp9Constants.FILTER_KERNELS, filter * 16 * 8,
                (col & 7) << 1, 16, (row & 7) << 1, 16, w, h, false, secondScratch);
    }

    /**
     * Returns the first scale of a motion search whose candidates reach a distance
     * ({@code vp9_init_search_range}).
     *
     * @param size the distance in full pixels
     * @return the scale
     */
    static int initSearchRange(int size) {
        var sr = 0;
        size = Math.max(16, size);
        while ((size << sr) < Vp9RtMotion.MAX_FULL_PEL_VAL) {
            sr++;
        }
        return Math.min(sr, Vp9RtMotion.MAX_MVSEARCH_STEPS - 2);
    }
}
