/* Ports libvpx 1.17.0 (vp9/encoder/vp9_encodeframe.c, vp9_encodemb.c, vp9_tokenize.c, vp9_pickmode.c, vp9_rdopt.c).
   Copyright (c) 2010, The WebM Project authors; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.video.vp9.internal;

import java.util.Arrays;

import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.BLOCK_16X16;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.BLOCK_4X4;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.BLOCK_64X64;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.BLOCK_8X8;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.DCT_DCT;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.DC_PRED;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.H_PRED;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.INTRA_FRAME;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.NEARESTMV;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.NEWMV;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.NO_REF_FRAME;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.PARTITION_HORZ;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.PARTITION_NONE;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.PARTITION_SPLIT;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.PARTITION_VERT;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.SWITCHABLE;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.SWITCHABLE_FILTERS;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.TX_16X16;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.TX_32X32;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.TX_4X4;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.TX_MODE_SELECT;

/**
 * Codes the superblock rows of a tile of the real-time VP9 encoder: it partitions each
 * superblock, decides the modes of its blocks, reconstructs them into the frame, and tokenizes
 * their coefficients.
 *
 * <p>One coder exists per thread of a frame, each with its own counts, contexts and buffers. A
 * row of a tile starts from fresh left contexts and from the adaptive mode thresholds the same
 * row ended the previous frame with, so the decisions of a row depend only on the frame, the
 * rows above it and that row's own history: the stream is the same for every thread count.
 *
 * @implNote This implementation ports the real-time paths of libvpx's
 * {@code vp9/encoder/vp9_encodeframe.c} ({@code encode_nonrd_sb_row},
 * {@code nonrd_use_partition}, {@code nonrd_pick_sb_modes}, {@code encode_b_rt},
 * {@code update_state_rt}, {@code encode_superblock}, {@code update_stats}),
 * {@code vp9_encodemb.c} ({@code vp9_encode_sb}, {@code vp9_encode_block_intra}),
 * {@code vp9_tokenize.c} and {@code vp9_pick_intra_mode} of {@code vp9_pickmode.c}.
 * The adaptive mode thresholds are kept per superblock row and tile, as libvpx does
 * with {@code adaptive_rd_thresh_row_mt}, at every speed. Key frames choose intra modes
 * with the non-RD search and without 4x4 partitions at every speed, as libvpx does from
 * speed 8; below speed 8 libvpx searches blocks smaller than 16x16 with the full
 * rate-distortion intra search of {@code vp9_rdopt.c}, which is not ported, so key
 * frames at speeds 5 to 7 take more bits than libvpx's for the same quality.
 */
final class Vp9RtCoder {
    /**
     * The number of 4x4 columns of each block size.
     */
    static final int[] NUM_4X4_WIDE = Vp9Constants.NUM_4X4_WIDE;

    /**
     * The number of 4x4 rows of each block size.
     */
    static final int[] NUM_4X4_HIGH = Vp9Constants.NUM_4X4_HIGH;

    /**
     * The number of 8x8 columns of each block size.
     */
    static final int[] NUM_8X8_WIDE = Vp9Constants.NUM_8X8_WIDE;

    /**
     * The number of 8x8 rows of each block size.
     */
    static final int[] NUM_8X8_HIGH = Vp9Constants.NUM_8X8_HIGH;

    /**
     * The log2 of the width of each block size in 4x4 units.
     */
    static final int[] B_WIDTH_LOG2 = Vp9Constants.B_WIDTH_LOG2;

    /**
     * The log2 of the height of each block size in 4x4 units.
     */
    static final int[] B_HEIGHT_LOG2 = Vp9Constants.B_HEIGHT_LOG2;

    /**
     * The log2 of the width of each block size in 8x8 units ({@code mi_width_log2_lookup}).
     */
    static final int[] MI_WIDTH_LOG2 = {0, 0, 0, 0, 0, 1, 1, 1, 2, 2, 2, 3, 3};

    /**
     * The block size covering each transform size ({@code txsize_to_bsize}).
     */
    static final int[] TXSIZE_TO_BSIZE = {BLOCK_4X4, BLOCK_8X8, BLOCK_16X16, Vp9Constants.BLOCK_32X32};

    /**
     * The partition of a square block size into each sub-block size, indexed by
     * {@code [width log2 in 4x4 units][sub-block size]} ({@code partition_lookup}).
     */
    private static final int[][] PARTITION_LOOKUP = {
            {PARTITION_NONE, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4},
            {PARTITION_SPLIT, PARTITION_VERT, PARTITION_HORZ, PARTITION_NONE, 4, 4, 4, 4, 4, 4, 4, 4, 4},
            {PARTITION_SPLIT, PARTITION_SPLIT, PARTITION_SPLIT, PARTITION_SPLIT, PARTITION_VERT, PARTITION_HORZ,
                    PARTITION_NONE, 4, 4, 4, 4, 4, 4},
            {PARTITION_SPLIT, PARTITION_SPLIT, PARTITION_SPLIT, PARTITION_SPLIT, PARTITION_SPLIT, PARTITION_SPLIT,
                    PARTITION_SPLIT, PARTITION_VERT, PARTITION_HORZ, PARTITION_NONE, 4, 4, 4},
            {PARTITION_SPLIT, PARTITION_SPLIT, PARTITION_SPLIT, PARTITION_SPLIT, PARTITION_SPLIT, PARTITION_SPLIT,
                    PARTITION_SPLIT, PARTITION_SPLIT, PARTITION_SPLIT, PARTITION_SPLIT, PARTITION_VERT, PARTITION_HORZ,
                    PARTITION_NONE}
    };

    /**
     * The scan order of each transform size and type, {@code [tx size * 4 + tx type]}.
     */
    static final short[][] SCANS = scans();

    /**
     * The scan position plus one of each raster coefficient, {@code [tx size * 4 + tx type]}, so
     * that the end of block of a quantized block is the largest entry of its nonzero
     * coefficients.
     */
    static final int[][] ISCANS = iscans();

    /**
     * The scan neighbors of each transform size and type, {@code [tx size * 4 + tx type]}.
     */
    static final short[][] NEIGHBORS = neighbors();

    /**
     * The decoder's coefficient layout of each transform size.
     */
    private static final short[][] LAYOUTS = {
            Vp9Dsp.INSTANCE.coefficientLayout(0), Vp9Dsp.INSTANCE.coefficientLayout(1),
            Vp9Dsp.INSTANCE.coefficientLayout(2), Vp9Dsp.INSTANCE.coefficientLayout(3)
    };

    /**
     * The scalar decoder kernels' coefficient layout of each transform size.
     */
    private static final short[][] SCALAR_LAYOUTS = scalarLayouts();

    /**
     * The average calls per inter frame, over a window of {@link #VECTOR_TRANSFORM_FRAMES}
     * frames, from which a transform size and type moves to the decoder's vector kernels.
     *
     * <p>A Vector API kernel is fast only once the C2 compiler has compiled it, which takes
     * thousands of calls; a transform this encoder calls a few times per frame would run on the
     * allocating tiers for minutes. Such transforms stay on the scalar kernels, whose output is
     * identical. Key frames, whose intra blocks use transforms inter frames rarely need, do not
     * count. The 4x4 transforms always run on the scalar kernels, which are as fast on 16
     * coefficients as the decoder's 64-bit vector ones.
     */
    static final int VECTOR_TRANSFORM_CALLS = 128;

    /**
     * The inter frames over which the transform calls are counted.
     */
    static final int VECTOR_TRANSFORM_FRAMES = 30;

    /**
     * The frame-level encoder.
     */
    final Vp9RtEncoder enc;

    /**
     * The decoder's pixel kernels.
     */
    final Vp9Dsp dsp = Vp9Dsp.INSTANCE;

    /**
     * The encoder's pixel metrics.
     */
    final Vp9EncDsp edsp = Vp9EncDsp.INSTANCE;

    /**
     * The decoder's scalar kernels, for the inverse transforms the frame does not run on
     * {@link #dsp}.
     */
    private final ScalarVp9Dsp scalarDsp = new ScalarVp9Dsp();

    /**
     * The number of inverse transforms of each size and type, {@code [tx size * 4 + tx type]},
     * that went beyond the DC coefficient in the current frame.
     */
    final int[] transformCalls = new int[16];

    /**
     * The predictor building inter predictions.
     */
    final Vp9RtPredictor predictor = new Vp9RtPredictor();

    /**
     * The predictor building intra predictions.
     */
    private final Vp9IntraPredictor intraPredictor = new Vp9IntraPredictor();

    /**
     * The full-precision block coding of a 10 or 12-bit frame, or {@code null} for an 8-bit one.
     */
    private final Vp9HighBlockCoder high;

    /**
     * The variance partitioning of superblocks.
     */
    final Vp9RtPartition partition;

    /**
     * The inter mode decision.
     */
    final Vp9RtPickMode pick;

    /**
     * The motion search.
     */
    final Vp9RtMotion motion;

    /**
     * The rate-distortion search of good-quality frames.
     */
    final Vp9RdCoder rd;

    /**
     * Whether the block being coded only feeds the search of the blocks after it: it is
     * reconstructed and its nonzero contexts are set, but it adds no tokens and no counts.
     */
    boolean dryRun;

    /**
     * The largest motion vector component, in full samples, of the new vectors the coded blocks
     * of the current frame chose.
     */
    int maxMvMagnitude;

    /**
     * The symbols this coder counted in the frame.
     */
    final Vp9EncCounts counts = new Vp9EncCounts();

    /**
     * The tile being coded.
     */
    int tile;

    /**
     * The first 8x8 column of the tile.
     */
    int miColStart;

    /**
     * The 8x8 column past the tile.
     */
    int miColEnd;

    /**
     * The token sink of the row being coded.
     */
    Vp9RtTokens tokens;

    /**
     * The adaptive mode threshold factors of the row being coded, {@code [block size][mode]}.
     */
    int[] threshFact;

    /**
     * The block's 8x8 row.
     */
    int miRow;

    /**
     * The block's 8x8 column.
     */
    int miCol;

    /**
     * The block's mode information.
     */
    ModeInfo mi;

    /**
     * The mode information above the block, or {@code null} at the top of the frame.
     */
    ModeInfo aboveMi;

    /**
     * The mode information left of the block, or {@code null} at the left of the tile.
     */
    ModeInfo leftMi;

    /**
     * The distance of the block's left edge from the frame's, negative, in 1/8 pixel.
     */
    int mbToLeftEdge;

    /**
     * The distance of the block's right edge from the frame's, in 1/8 pixel; negative when the
     * block extends past it.
     */
    int mbToRightEdge;

    /**
     * The distance of the block's top edge from the frame's, negative, in 1/8 pixel.
     */
    int mbToTopEdge;

    /**
     * The distance of the block's bottom edge from the frame's, in 1/8 pixel.
     */
    int mbToBottomEdge;

    /**
     * The smallest full-pixel motion vector column the block may use.
     */
    int mvColMin;

    /**
     * The largest full-pixel motion vector column the block may use.
     */
    int mvColMax;

    /**
     * The smallest full-pixel motion vector row the block may use.
     */
    int mvRowMin;

    /**
     * The largest full-pixel motion vector row the block may use.
     */
    int mvRowMax;

    /**
     * The index of the block's top-left sample in each source plane.
     */
    final int[] srcOff = new int[3];

    /**
     * The index of the block's top-left sample in each reconstruction plane.
     */
    final int[] dstOff = new int[3];

    /**
     * The left nonzero contexts of each plane, in 4x4 units of the superblock.
     */
    final byte[][] leftEntropy = {new byte[16], new byte[16], new byte[16]};

    /**
     * The coefficient optimization of good-quality blocks, whose rates the 8-bit token costs of
     * the frame give.
     */
    final Vp9CoefficientOptimizer optimizer;

    /**
     * The coefficient optimization of the full-precision coding of a high bit depth frame, whose
     * category 6 tokens carry the extra bits of the bit depth.
     */
    final Vp9CoefficientOptimizer highOptimizer;

    /**
     * The above nonzero contexts the coefficient optimization of a plane being coded updates, one
     * per 4x4 column of the block.
     */
    final byte[] codingAbove = new byte[16];

    /**
     * The left nonzero contexts the coefficient optimization of a plane being coded updates.
     */
    final byte[] codingLeft = new byte[16];

    /**
     * The left partition contexts, one per 8x8 row of the superblock.
     */
    final byte[] leftPartition = new byte[8];

    /**
     * The quantizer index of the block's segment.
     */
    int qindex;

    /**
     * The rate distortion multiplier.
     */
    int rdmult;

    /**
     * The rate distortion distortion shift ({@code RDDIV_BITS}).
     */
    static final int RDDIV = 7;

    /**
     * The motion search's error per bit.
     */
    int errorPerBit;

    /**
     * The motion search's sum of absolute differences per bit.
     */
    int sadPerBit16;

    /**
     * The motion search's sum-of-differences weight of sub-8x8 blocks ({@code sadperbit4}).
     */
    int sadPerBit4;

    /**
     * How much of the luma residual can be skipped: 0 none, 1 everything
     * ({@code SKIP_TXFM_AC_DC}), 2 the AC coefficients ({@code SKIP_TXFM_AC_ONLY}).
     */
    int skipTxfm;

    /**
     * Whether the block is coded without residual, set by the mode decision's breakout.
     */
    boolean skip;

    /**
     * The source variance of the block, or {@code -1} when not computed.
     */
    long sourceVariance;

    /**
     * Whether the chroma planes of the superblock deserve a rate distortion estimate.
     */
    final boolean[] colorSensitivity = new boolean[2];

    /**
     * Whether the superblock is mostly skin.
     */
    boolean sbIsSkin;

    /**
     * Whether the superblock's source barely changed since the previous frame.
     */
    boolean skipLowSourceSad;

    /**
     * Whether the superblock's change is a uniform lighting change.
     */
    boolean lowvarHighsumdiff;

    /**
     * How many frames the superblock's source has stayed nearly static.
     */
    int lastSbHighContent;

    /**
     * Whether the superblock's integral projection motion vector stands in for the search.
     */
    boolean sbUseMvPart;

    /**
     * The superblock's integral projection motion vector column, in 1/8 pixel.
     */
    int sbMvcolPart;

    /**
     * The superblock's integral projection motion vector row, in 1/8 pixel.
     */
    int sbMvrowPart;

    /**
     * Whether the superblock's source is identical to the previous frame's.
     */
    boolean zeroTempSadSource;

    /**
     * The content state of the superblock: one of the {@code CONTENT_*} constants.
     */
    int contentStateSb;

    /**
     * Whether each region of the superblock has low temporal variance
     * ({@code variance_low}), 25 entries.
     */
    final boolean[] varianceLow = new boolean[25];

    /**
     * The motion vector the previous block size predicted for each reference, packed, or
     * {@link #INVALID_PRED_MV}.
     */
    final int[] predMv = new int[4];

    /**
     * The sum of absolute differences of the best predicted motion vector of each reference.
     */
    final int[] predMvSad = new int[4];

    /**
     * The index of the best predicted motion vector of each reference: 0 or 1 for the
     * candidates, 2 for {@link #predMv}.
     */
    final int[] mvBestRefIndex = new int[4];

    /**
     * The sub-pixel search's sum of squared errors of each reference.
     */
    final int[] predSse = new int[4];

    /**
     * The two candidate motion vectors of each reference ({@code ref_mvs}), packed.
     */
    final int[] refMvs = new int[8];

    /**
     * The inter mode context of each reference.
     */
    final int[] modeContext = new int[4];

    /**
     * Whether the source of the block was used for intra estimates instead of the reconstruction.
     */
    boolean skipEncode;

    /**
     * The residual of the block's current plane.
     */
    final short[] srcDiff = new short[64 * 64];

    /**
     * The transform coefficients of each plane, 16 per 4x4 unit.
     */
    final int[][] coeff = {new int[4096], new int[4096], new int[4096]};

    /**
     * The quantized coefficients of each plane.
     */
    final int[][] qcoeff = {new int[4096], new int[4096], new int[4096]};

    /**
     * The dequantized coefficients of each plane.
     */
    final int[][] dqcoeff = {new int[4096], new int[4096], new int[4096]};

    /**
     * The end of block of each transform block of each plane, by 4x4 unit index.
     */
    final int[][] eobs = {new int[256], new int[256], new int[256]};

    /**
     * The coefficients of one transform block in the decoder's layout.
     */
    private final int[] layoutCoef = new int[1024];

    /**
     * Scratch memory of the inverse transforms.
     */
    private final int[] transformScratch = new int[Vp9Dsp.TRANSFORM_SCRATCH];

    /**
     * Scratch memory of the forward transforms.
     */
    final long[] fdctScratch = new long[1200];

    /**
     * The {@code int} scratch of the vector forward transforms.
     */
    final int[] fdctInts = new int[Vp9EncDsp.FDCT_SCRATCH];

    /**
     * The energy class of each tokenized coefficient.
     */
    private final byte[] tokenCache = new byte[1024];

    /**
     * Scratch memory of the sub-pixel variance.
     */
    final short[] subpelScratch = new short[Vp9EncDsp.SUBPEL_SCRATCH];

    /**
     * Scratch memory of the Hadamard transforms.
     */
    final short[] hadamardScratch = new short[64];

    /**
     * A motion vector marking a reference whose prediction is unknown ({@code INT16_MAX} rows and
     * columns).
     */
    static final int INVALID_PRED_MV = ModeInfo.packMv(Short.MAX_VALUE, Short.MAX_VALUE);

    /**
     * A motion vector marking an intra block ({@code INVALID_MV}).
     */
    static final int INVALID_MV = 0x80008000;

    /**
     * The content state of a superblock whose change is unknown.
     */
    static final int CONTENT_INVALID = 0;

    /**
     * A superblock with little change and little mean change ({@code kLowSadLowSumdiff}).
     */
    static final int CONTENT_LOW_SAD_LOW_SUMDIFF = 1;

    /**
     * A superblock with little change but a mean change ({@code kLowSadHighSumdiff}).
     */
    static final int CONTENT_LOW_SAD_HIGH_SUMDIFF = 2;

    /**
     * A superblock with change but little mean change ({@code kHighSadLowSumdiff}).
     */
    static final int CONTENT_HIGH_SAD_LOW_SUMDIFF = 3;

    /**
     * A superblock with change and mean change ({@code kHighSadHighSumdiff}).
     */
    static final int CONTENT_HIGH_SAD_HIGH_SUMDIFF = 4;

    /**
     * A superblock whose change is a uniform lighting change ({@code kLowVarHighSumdiff}).
     */
    static final int CONTENT_LOW_VAR_HIGH_SUMDIFF = 5;

    /**
     * A superblock with very large change ({@code kVeryHighSad}).
     */
    static final int CONTENT_VERY_HIGH_SAD = 6;

    /**
     * Creates a coder for an encoder.
     *
     * @param enc the encoder
     */
    Vp9RtCoder(Vp9RtEncoder enc) {
        this.enc = enc;
        this.partition = new Vp9RtPartition(this);
        this.motion = new Vp9RtMotion(this);
        this.pick = new Vp9RtPickMode(this);
        this.high = enc.bitDepth > 8 ? new Vp9HighBlockCoder(this) : null;
        this.optimizer = new Vp9CoefficientOptimizer(enc.rdCosts, enc.rdCosts);
        this.highOptimizer = enc.bitDepth > 8 ? new Vp9CoefficientOptimizer(enc.rdCosts, new Vp9RdCosts(enc.bitDepth))
                : optimizer;
        this.rd = new Vp9RdCoder(this);
    }

    /**
     * Returns whether the coding of the chosen blocks optimizes their quantized coefficients,
     * libvpx's {@code x->optimize}: good-quality frames of a two-pass encode that are not
     * lossless.
     *
     * @return whether it does
     */
    boolean optimizesCoding() {
        return enc.good && enc.optimizeCoefficients && !enc.lossless;
    }

    /**
     * Loads the nonzero contexts of each transform block of a plane of the current block, the
     * contexts of its 4x4 columns and rows merged over the transform's width and height
     * ({@code vp9_get_entropy_contexts}).
     *
     * @param plane the plane index
     * @param planeBsize the block size in the plane
     * @param txSize the transform size
     * @param ta receives the above contexts, at the 4x4 column of each transform block
     * @param tl receives the left contexts, at the 4x4 row of each transform block
     */
    void entropyContexts(int plane, int planeBsize, int txSize, byte[] ta, byte[] tl) {
        var sx = plane == 0 ? 0 : enc.ssX;
        var sy = plane == 0 ? 0 : enc.ssY;
        var num4w = NUM_4X4_WIDE[planeBsize];
        var num4h = NUM_4X4_HIGH[planeBsize];
        var step = 1 << txSize;
        var above = enc.aboveEntropy[plane];
        var left = leftEntropy[plane];
        var aboveBase = (miCol * 2) >> sx;
        var leftBase = ((miRow * 2) & 15) >> sy;
        for (var i = 0; i < num4w; i += step) {
            var v = 0;
            for (var k = 0; k < step && i + k < 16; k++) {
                v |= above[aboveBase + i + k];
            }
            ta[i] = (byte) (v != 0 ? 1 : 0);
        }
        for (var i = 0; i < num4h; i += step) {
            var v = 0;
            for (var k = 0; k < step && i + k < 16; k++) {
                v |= left[leftBase + i + k];
            }
            tl[i] = (byte) (v != 0 ? 1 : 0);
        }
    }

    /**
     * Optimizes the quantized coefficients of one transform block of the current block with the
     * dequantization of a quantizer ({@code vp9_optimize_b}).
     *
     * @param optimizer the optimizer of the coefficients' bit depth
     * @param q the quantizer the block was quantized with
     * @param bitDepth the bit depth of the coefficients
     * @param plane the plane index
     * @param off the index of the block's first coefficient
     * @param eob the end of block of the quantization
     * @param txSize the transform size
     * @param txType the transform type
     * @param ctx the block's nonzero context, from 0 to 2
     * @return the end of block after the optimization
     */
    int optimizeBlock(Vp9CoefficientOptimizer optimizer, Vp9Quantizer q, int bitDepth, int plane, int off, int eob,
                      int txSize, int txType, int ctx) {
        if (eob == 0) {
            return 0;
        }
        var pidx = qindex * 2;
        var dequant = plane == 0 ? q.yDequant : q.uvDequant;
        return optimizer.optimize(coeff[plane], qcoeff[plane], dqcoeff[plane], off, eob, txSize, txType, plane,
                mi.isInter(), ctx, dequant[pidx], dequant[pidx + 1], rdmult, bitDepth);
    }

    /**
     * Builds the scan order table.
     *
     * @return the scans by transform size and type
     */
    private static short[][] scans() {
        var s = new short[16][];
        for (var t = 0; t < 4; t++) {
            s[t] = Vp9ScanTables.DEFAULT_SCAN_4X4;
        }
        s[1] = Vp9ScanTables.ROW_SCAN_4X4;
        s[2] = Vp9ScanTables.COL_SCAN_4X4;
        s[4] = Vp9ScanTables.DEFAULT_SCAN_8X8;
        s[5] = Vp9ScanTables.ROW_SCAN_8X8;
        s[6] = Vp9ScanTables.COL_SCAN_8X8;
        s[7] = Vp9ScanTables.DEFAULT_SCAN_8X8;
        s[8] = Vp9ScanTables.DEFAULT_SCAN_16X16;
        s[9] = Vp9ScanTables.ROW_SCAN_16X16;
        s[10] = Vp9ScanTables.COL_SCAN_16X16;
        s[11] = Vp9ScanTables.DEFAULT_SCAN_16X16;
        for (var t = 12; t < 16; t++) {
            s[t] = Vp9ScanTables.DEFAULT_SCAN_32X32;
        }
        return s;
    }

    /**
     * Returns the scalar decoder kernels' coefficient layout of each transform size.
     *
     * @return the layouts
     */
    private static short[][] scalarLayouts() {
        var scalar = new ScalarVp9Dsp();
        return new short[][]{scalar.coefficientLayout(0), scalar.coefficientLayout(1), scalar.coefficientLayout(2),
                scalar.coefficientLayout(3)};
    }

    /**
     * Builds the inverse scan table from {@link #SCANS}.
     *
     * @return the inverse scans by transform size and type
     */
    private static int[][] iscans() {
        var scans = scans();
        var result = new int[16][];
        for (var t = 0; t < 16; t++) {
            var scan = scans[t];
            var inverse = new int[scan.length];
            for (var i = 0; i < scan.length; i++) {
                inverse[scan[i]] = i + 1;
            }
            result[t] = inverse;
        }
        return result;
    }

    /**
     * Builds the scan neighbor table.
     *
     * @return the neighbors by transform size and type
     */
    private static short[][] neighbors() {
        var n = new short[16][];
        for (var t = 0; t < 4; t++) {
            n[t] = Vp9NeighborTables.DEFAULT_4X4;
        }
        n[1] = Vp9NeighborTables.ROW_4X4;
        n[2] = Vp9NeighborTables.COL_4X4;
        n[4] = Vp9NeighborTables.DEFAULT_8X8;
        n[5] = Vp9NeighborTables.ROW_8X8;
        n[6] = Vp9NeighborTables.COL_8X8;
        n[7] = Vp9NeighborTables.DEFAULT_8X8;
        n[8] = Vp9NeighborTables.DEFAULT_16X16;
        n[9] = Vp9NeighborTables.ROW_16X16;
        n[10] = Vp9NeighborTables.COL_16X16;
        n[11] = Vp9NeighborTables.DEFAULT_16X16;
        for (var t = 12; t < 16; t++) {
            n[t] = Vp9NeighborTables.DEFAULT_32X32;
        }
        return n;
    }

    /**
     * Prepares the coder for a frame.
     */
    void startFrame() {
        counts.clear();
        maxMvMagnitude = 0;
    }

    /**
     * Codes one superblock row of a tile ({@code encode_nonrd_sb_row}).
     *
     * @param tile the tile column
     * @param sbRow the superblock row
     * @param tokens receives the row's tokens
     * @param wavefront the synchronization of the rows, or {@code null} when one thread codes
     * every row in order
     */
    void codeRow(int tile, int sbRow, Vp9RtTokens tokens, Vp9RtWavefront wavefront) {
        this.tile = tile;
        this.miColStart = enc.tileMiColStart(tile);
        this.miColEnd = enc.tileMiColStart(tile + 1);
        this.tokens = tokens;
        this.threshFact = enc.rowThreshFact(tile, sbRow);
        tokens.clear();
        for (var plane = 0; plane < 3; plane++) {
            Arrays.fill(leftEntropy[plane], (byte) 0);
        }
        Arrays.fill(leftPartition, (byte) 0);
        var row = sbRow << 3;
        var sbColInTile = 0;
        for (var col = miColStart; col < miColEnd; col += 8, sbColInTile++) {
            if (wavefront != null && sbRow > 0) {
                wavefront.await(tile, sbRow - 1, sbColInTile + 1);
            }
            if (enc.good) {
                rd.codeSuperblock(row, col, threshFact);
            } else {
                codeSuperblock(row, col);
            }
            if (wavefront != null) {
                wavefront.publish(tile, sbRow, sbColInTile + 1);
            }
        }
        if (wavefront != null) {
            wavefront.publish(tile, sbRow, Vp9RtWavefront.ROW_DONE);
        }
    }

    /**
     * Codes one superblock: resets its statistics, partitions it and codes its blocks.
     *
     * @param row the superblock's 8x8 row
     * @param col the superblock's 8x8 column
     */
    private void codeSuperblock(int row, int col) {
        sourceVariance = -1;
        Arrays.fill(predMv, INVALID_PRED_MV);
        colorSensitivity[0] = false;
        colorSensitivity[1] = false;
        sbIsSkin = false;
        skipLowSourceSad = false;
        lowvarHighsumdiff = false;
        contentStateSb = CONTENT_INVALID;
        zeroTempSadSource = false;
        sbUseMvPart = false;
        sbMvcolPart = 0;
        sbMvrowPart = 0;
        lastSbHighContent = 0;
        if (enc.useSkinDetection) {
            enc.content.computeSkinSuperblock(row, col);
        }
        if (enc.computeSourceSad && enc.sf.useSourceSad) {
            avgSourceSad(row, col);
        }
        partition.choose(row, col);
        usePartition(row, col, BLOCK_64X64);
    }

    /**
     * Measures how the superblock's source changed since the previous frame and classifies it
     * ({@code avg_source_sad}).
     *
     * @param row the superblock's 8x8 row
     * @param col the superblock's 8x8 column
     */
    private void avgSourceSad(int row, int col) {
        var src = enc.source;
        var last = enc.lastSource;
        var offset = src.offset(0, col << 3, row << 3);
        var stride = src.strides[0];
        var tmpSad = edsp.sad(src.planes[0], offset, stride, last.planes[0], offset, stride, 64, 64);
        var packed = edsp.variance(src.planes[0], offset, stride, last.planes[0], offset, stride, 64, 64);
        var tmpSse = Vp9EncDsp.sse(packed);
        var tmpVariance = Vp9EncDsp.variance(packed, 12);
        if (tmpSad < 10000) {
            contentStateSb = (tmpSse - tmpVariance) < 25 ? CONTENT_LOW_SAD_LOW_SUMDIFF : CONTENT_LOW_SAD_HIGH_SUMDIFF;
        } else {
            contentStateSb = (tmpSse - tmpVariance) < 25 ? CONTENT_HIGH_SAD_LOW_SUMDIFF : CONTENT_HIGH_SAD_HIGH_SUMDIFF;
        }
        if (tmpVariance < (tmpSse >> 3) && (tmpSse - tmpVariance) > 10000) {
            contentStateSb = CONTENT_LOW_VAR_HIGH_SUMDIFF;
        } else if (tmpSad > 20000) {
            contentStateSb = CONTENT_VERY_HIGH_SAD;
        }
        var sbOffset = enc.sbOffset(row, col);
        var fd = enc.contentStateSbFd;
        if (fd != null) {
            if (tmpSad < 12000) {
                if (fd[sbOffset] < 255) {
                    fd[sbOffset]++;
                }
            } else {
                fd[sbOffset] = 0;
            }
        }
        if (tmpSad == 0) {
            zeroTempSadSource = true;
        }
    }

    /**
     * Returns the partition of a square block whose top-left unit holds a given block size.
     *
     * @param bsize the square block size
     * @param subsize the size held by the top-left unit
     * @return the partition
     */
    static int partitionOf(int bsize, int subsize) {
        return PARTITION_LOOKUP[B_WIDTH_LOG2[bsize]][subsize];
    }

    /**
     * Codes the blocks of a square region along the partition the variance partitioning chose
     * ({@code nonrd_use_partition}).
     *
     * @param row the region's 8x8 row
     * @param col the region's 8x8 column
     * @param bsize the region's square block size
     */
    private void usePartition(int row, int col, int bsize) {
        var miRows = enc.miRows;
        var miCols = enc.miCols;
        if (row >= miRows || col >= miCols) {
            return;
        }
        var hbs = NUM_8X8_WIDE[bsize] >> 1;
        var subsize = bsize >= BLOCK_8X8 ? enc.miGrid[row * miCols + col].sbType : BLOCK_4X4;
        var part = partitionOf(bsize, subsize);
        if (bsize != BLOCK_4X4) {
            var ctx = partitionContext(row, col, bsize);
            counts.counts[Vp9EncCounts.PARTITION + ctx * 4 + part]++;
        }
        switch (part) {
            case PARTITION_NONE -> codeBlock(row, col, subsize);
            case PARTITION_VERT -> {
                codeBlock(row, col, subsize);
                if (col + hbs < miCols && bsize > BLOCK_8X8) {
                    codeBlock(row, col + hbs, subsize);
                }
            }
            case PARTITION_HORZ -> {
                codeBlock(row, col, subsize);
                if (row + hbs < miRows && bsize > BLOCK_8X8) {
                    codeBlock(row + hbs, col, subsize);
                }
            }
            default -> {
                var sub = Vp9Constants.SUBSIZE[PARTITION_SPLIT * 13 + bsize];
                if (bsize == BLOCK_8X8) {
                    codeBlock(row, col, sub);
                } else {
                    usePartition(row, col, sub);
                    usePartition(row, col + hbs, sub);
                    usePartition(row + hbs, col, sub);
                    usePartition(row + hbs, col + hbs, sub);
                }
            }
        }
        if (part != PARTITION_SPLIT || bsize == BLOCK_8X8) {
            updatePartitionContext(row, col, subsize, bsize);
        }
    }

    /**
     * Returns the partition context of a square block ({@code partition_plane_context}).
     *
     * @param row the block's 8x8 row
     * @param col the block's 8x8 column
     * @param bsize the block size
     * @return the context
     */
    int partitionContext(int row, int col, int bsize) {
        var bsl = MI_WIDTH_LOG2[bsize];
        var above = (enc.abovePartition[col] >> bsl) & 1;
        var left = (leftPartition[row & 7] >> bsl) & 1;
        return left * 2 + above + bsl * 4;
    }

    /**
     * Records the partition of a coded square block in the partition contexts
     * ({@code update_partition_context}).
     *
     * @param row the block's 8x8 row
     * @param col the block's 8x8 column
     * @param subsize the coded sub-block size
     * @param bsize the square block size
     */
    void updatePartitionContext(int row, int col, int subsize, int bsize) {
        var bs = NUM_8X8_WIDE[bsize];
        var above = (byte) Vp9Constants.PARTITION_CONTEXT[subsize * 2];
        var left = (byte) Vp9Constants.PARTITION_CONTEXT[subsize * 2 + 1];
        var abovePartition = enc.abovePartition;
        for (var i = 0; i < bs; i++) {
            abovePartition[col + i] = above;
            leftPartition[(row & 7) + i] = left;
        }
    }

    /**
     * Points the coder at a block: its mode information, neighbors, edge distances, motion
     * limits and sample positions ({@code set_offsets}).
     *
     * @param row the block's 8x8 row
     * @param col the block's 8x8 column
     * @param bsize the block size
     */
    void setOffsets(int row, int col, int bsize) {
        var miCols = enc.miCols;
        var miRows = enc.miRows;
        miRow = row;
        miCol = col;
        var index = row * miCols + col;
        mi = enc.miPool[index];
        enc.miGrid[index] = mi;
        var bw = NUM_8X8_WIDE[bsize];
        var bh = NUM_8X8_HIGH[bsize];
        mvRowMin = -(((row + bh) * 8) + 4);
        mvColMin = -(((col + bw) * 8) + 4);
        mvRowMax = (miRows - row) * 8 + 4;
        mvColMax = (miCols - col) * 8 + 4;
        mbToTopEdge = -((row * 8) * 8);
        mbToBottomEdge = ((miRows - bh - row) * 8) * 8;
        mbToLeftEdge = -((col * 8) * 8);
        mbToRightEdge = ((miCols - bw - col) * 8) * 8;
        aboveMi = row != 0 ? enc.miGrid[index - miCols] : null;
        leftMi = col > miColStart ? enc.miGrid[index - 1] : null;
        var src = enc.source;
        var recon = enc.recon;
        for (var plane = 0; plane < 3; plane++) {
            var x = (col * 8) >> (plane == 0 ? 0 : enc.ssX);
            var y = (row * 8) >> (plane == 0 ? 0 : enc.ssY);
            srcOff[plane] = src.offset(plane, x, y);
            dstOff[plane] = y * recon.strides[plane] + x;
        }
        rdmult = enc.rdMult;
    }

    /**
     * Sets the block's segment from the segmentation map and selects its quantizers
     * ({@code set_segment_index}).
     *
     * @param bsize the block size
     */
    void setSegmentIndex(int bsize) {
        mi.segmentId = 0;
        if (enc.segEnabled) {
            mi.segmentId = enc.segmentIdOf(enc.segUpdateMap ? enc.segmentationMap : enc.lastSegMap, bsize, miRow,
                    miCol);
        }
        initPlaneQuantizers();
    }

    /**
     * Selects the quantizers, the motion search's error weights and the encode breakout of the
     * block's segment ({@code vp9_init_plane_quantizers}).
     */
    void initPlaneQuantizers() {
        qindex = enc.segQindex(mi.segmentId);
        var segRdmult = enc.computeRdMult(qindex);
        errorPerBit = Math.max(1, segRdmult >> 6);
        sadPerBit16 = Vp9RtEncoder.SAD_PER_BIT16[qindex];
        sadPerBit4 = Vp9RtEncoder.SAD_PER_BIT4[qindex];
    }

    /**
     * Picks the modes of a block and codes it ({@code nonrd_pick_sb_modes} followed by
     * {@code encode_b_rt}).
     *
     * @param row the block's 8x8 row
     * @param col the block's 8x8 column
     * @param bsize the block size
     */
    private void codeBlock(int row, int col, int bsize) {
        setOffsets(row, col, bsize);
        setSegmentIndex(bsize);
        mi.sbType = bsize;
        if (enc.cyclicRefreshActive() && Vp9RtCyclicRefresh.boosted(mi.segmentId)) {
            rdmult = enc.cr.rdmult;
        }
        long rate;
        long dist;
        if (enc.keyFrame) {
            pickIntraMode(bsize);
            rate = pickRate;
            dist = pickDist;
        } else {
            pick.pickInterMode(bsize);
            rate = pick.bestRate;
            dist = pick.bestDist;
        }
        duplicateModeInfo(bsize);
        encodeBlock(bsize, rate, dist);
    }

    /**
     * Codes a block whose modes the rate-distortion search chose and copied into its mode
     * information ({@code encode_b}): reconstructs it and, when coding into the bitstream,
     * tokenizes it and counts its symbols; otherwise only sets the contexts the blocks after it
     * read.
     *
     * @param bsize the block size
     * @param output whether the block is coded into the bitstream
     */
    void encodeChosenBlock(int bsize, boolean output) {
        duplicateModeInfo(bsize);
        var m = mi;
        if (output) {
            var inter = m.isInter();
            if (inter) {
                if (bsize < BLOCK_8X8) {
                    var w4 = NUM_4X4_WIDE[bsize];
                    var h4 = NUM_4X4_HIGH[bsize];
                    for (var idy = 0; idy < 2; idy += h4) {
                        for (var idx = 0; idx < 2; idx += w4) {
                            var j = idy * 2 + idx;
                            if (m.subModes[j] == NEWMV) {
                                countNewMv(m.subMv0[j], m.subMv1[j]);
                            }
                        }
                    }
                } else if (m.mode == NEWMV) {
                    countNewMv(m.mv0, m.mv1);
                }
                if (enc.interpFilter == SWITCHABLE) {
                    var ctx = Vp9PredContexts.switchableInterp(aboveMi, leftMi);
                    counts.counts[Vp9EncCounts.SWITCHABLE_INTERP + ctx * 3 + m.interpFilter]++;
                }
            }
            var index = miRow * enc.miCols + miCol;
            enc.blockModeContext[index] = inter ? modeContext[m.ref0] : 0;
            enc.blockRefMv[index] = inter ? refMvs[m.ref0 * 2] : 0;
            enc.blockRefMv1[index] = inter && m.hasSecondRef() ? refMvs[m.ref1 * 2] : 0;
            if (!enc.keyFrame) {
                enc.storeFrameMvs(m, miRow, miCol, bsize);
            }
        }
        dryRun = !output;
        try {
            encodeSuperblock(bsize);
        } finally {
            dryRun = false;
        }
        if (output) {
            updateStats();
            tokens.endBlock();
        }
    }

    /**
     * Counts the vectors of a new motion vector against the block's best predicted vectors and
     * tracks the largest magnitude ({@code vp9_update_mv_count}, {@code vp9_encode_mv}).
     *
     * @param mv0 the packed vector of the first reference
     * @param mv1 the packed vector of the second reference, ignored without one
     */
    private void countNewMv(int mv0, int mv1) {
        var m = mi;
        var ref = refMvs[m.ref0 * 2];
        counts.countMv((mv0 >> 16) - (ref >> 16), (short) mv0 - (short) ref);
        maxMvMagnitude = Math.max(maxMvMagnitude, Math.max(Math.abs(mv0 >> 16), Math.abs((short) mv0)) >> 3);
        if (m.hasSecondRef()) {
            var ref1 = refMvs[m.ref1 * 2];
            counts.countMv((mv1 >> 16) - (ref1 >> 16), (short) mv1 - (short) ref1);
        }
    }

    /**
     * Points every unit of the block at its mode information ({@code duplicate_mode_info_in_sb}).
     *
     * @param bsize the block size
     */
    private void duplicateModeInfo(int bsize) {
        var miCols = enc.miCols;
        var w = Math.min(NUM_8X8_WIDE[bsize], miCols - miCol);
        var h = Math.min(NUM_8X8_HIGH[bsize], enc.miRows - miRow);
        var grid = enc.miGrid;
        var base = miRow * miCols + miCol;
        for (var y = 0; y < h; y++) {
            var start = base + y * miCols;
            for (var x = 0; x < w; x++) {
                grid[start + x] = mi;
            }
        }
    }

    /**
     * Returns the skip context of the block ({@code vp9_get_skip_context}).
     *
     * @return the context, from 0 to 2
     */
    int skipContext() {
        return (aboveMi != null ? aboveMi.skip : 0) + (leftMi != null ? leftMi.skip : 0);
    }

    /**
     * Returns the cost of the block's skip flag.
     *
     * @param skipFlag the flag
     * @return the cost
     */
    int skipCost(int skipFlag) {
        return Vp9EncTables.costBit(enc.fc.probs[Vp9FrameContext.SKIP + skipContext()], skipFlag);
    }

    /**
     * Picks the intra mode of a key frame block among DC, vertical and horizontal prediction
     * ({@code vp9_pick_intra_mode}).
     *
     * <p>The rate and distortion of the chosen mode are left in {@link #pickRate} and
     * {@link #pickDist}.
     *
     * @param bsize the block size, at least 8x8
     */
    private void pickIntraMode(int bsize) {
        var m = mi;
        var intraTxSize = Math.min(Vp9Constants.MAX_TX_SIZE[bsize], Vp9Constants.TX_MODE_TO_BIGGEST[enc.txMode]);
        var above = aboveMi == null || aboveMi.isInter() ? DC_PRED : aboveMi.yMode(2);
        var left = leftMi == null || leftMi.isInter() ? DC_PRED : leftMi.yMode(1);
        var bmodeCosts = enc.kfYModeCosts;
        var costBase = (above * 10 + left) * 10;
        m.ref0 = INTRA_FRAME;
        m.ref1 = NO_REF_FRAME;
        m.interpFilter = SWITCHABLE_FILTERS;
        m.mv0 = INVALID_MV;
        m.mv1 = 0;
        m.uvMode = DC_PRED;
        skipTxfm = 0;
        skipEncode = false;
        var bestRd = Long.MAX_VALUE;
        long bestRate = 0;
        long bestDist = 0;
        var bestMode = DC_PRED;
        for (var mode = DC_PRED; mode <= H_PRED; mode++) {
            m.txSize = intraTxSize;
            estimateIntraLuma(bsize, mode, intraTxSize);
            var rate = estRate;
            var dist = estDist;
            var skippable = estSkippable;
            if (skippable) {
                rate = skipCost(1);
            } else {
                rate += skipCost(0);
            }
            rate += bmodeCosts[costBase + mode];
            var rd = rdCost(rdmult, rate, dist);
            if (rd < bestRd) {
                bestRd = rd;
                bestRate = rate;
                bestDist = dist;
                bestMode = mode;
                skipTxfm = skippable ? 1 : 0;
            }
        }
        m.mode = bestMode;
        m.txSize = intraTxSize;
        pickRate = bestRate;
        pickDist = bestDist;
    }

    /**
     * The rate of the last {@link #estimateIntraLuma}.
     */
    private long estRate;

    /**
     * The distortion of the last {@link #estimateIntraLuma}.
     */
    private long estDist;

    /**
     * Whether the last transform block of the last {@link #estimateIntraLuma} quantized to zero.
     */
    private boolean estSkippable;

    /**
     * The rate of the mode {@link #pickIntraMode} chose.
     */
    private long pickRate;

    /**
     * The distortion of the mode {@link #pickIntraMode} chose.
     */
    private long pickDist;

    /**
     * Estimates the rate and distortion of predicting the luma of a block with one intra mode,
     * transform block by transform block, predicting from the reconstruction
     * ({@code estimate_block_intra} over the luma plane).
     *
     * @implNote This implementation keeps the skippable flag of the last transform block only,
     * as libvpx's {@code block_yrd} resets it on every call.
     * @param bsize the block size
     * @param mode the intra mode
     * <p>The results are left in {@link #estRate}, {@link #estDist} and {@link #estSkippable}.
     *
     * @param txSize the transform size
     */
    void estimateIntraLuma(int bsize, int mode, int txSize) {
        var num4w = NUM_4X4_WIDE[bsize];
        var num4h = NUM_4X4_HIGH[bsize];
        var maxWide = num4w + (mbToRightEdge >= 0 ? 0 : mbToRightEdge >> 5);
        var maxHigh = num4h + (mbToBottomEdge >= 0 ? 0 : mbToBottomEdge >> 5);
        var step = 1 << txSize;
        long rate = 0;
        long dist = 0;
        var skippable = true;
        var recon = enc.recon;
        var dstPlane = recon.planes8[0];
        var dstStride = recon.strides[0];
        var src = enc.source;
        var bwl = B_WIDTH_LOG2[bsize];
        var bsizeTx = TXSIZE_TO_BSIZE[txSize];
        for (var r = 0; r < maxHigh; r += step) {
            for (var c = 0; c < maxWide; c += step) {
                var dOff = dstOff[0] + 4 * (r * dstStride + c);
                var sOff = srcOff[0] + 4 * (r * src.strides[0] + c);
                predictIntraBlock(0, bwl, txSize, mode, c, r, dOff, skipEncode ? src.planes[0] : dstPlane,
                        skipEncode ? sOff : dOff, skipEncode ? src.strides[0] : dstStride);
                skippable = pick.blockYrd(bsizeTx, Math.min(txSize, TX_16X16), sOff, dstPlane, dOff, dstStride,
                        Long.MAX_VALUE, false, true, 0, 0);
                rate += pick.yrdRate;
                dist += pick.yrdDist;
            }
        }
        estRate = rate;
        estDist = dist;
        estSkippable = skippable;
    }

    /**
     * Predicts one transform block of a block with an intra mode ({@code vp9_predict_intra_block}).
     *
     * @param plane the plane index, 0 for luma and 1 or 2 for chroma
     * @param bwl the log2 of the plane block's width in 4x4 units
     * @param txSize the transform size
     * @param mode the intra mode
     * @param col the transform block's column in the block, in 4x4 units
     * @param row the transform block's row in the block, in 4x4 units
     * @param dOff the index of the transform block in the reconstruction plane
     * @param ref the plane holding the neighbors
     * @param refOff the index of the transform block in {@code ref}
     * @param refStride the row stride of {@code ref}
     */
    void predictIntraBlock(int plane, int bwl, int txSize, int mode, int col, int row, int dOff, byte[] ref,
                           int refOff, int refStride) {
        var recon = enc.recon;
        var bw = 1 << bwl;
        var txw = 1 << txSize;
        var haveTop = row != 0 || aboveMi != null;
        var haveLeft = col != 0 || leftMi != null;
        var haveRight = col + txw < bw;
        var x0 = (-mbToLeftEdge >> (3 + (plane == 0 ? 0 : enc.ssX))) + col * 4;
        var y0 = (-mbToTopEdge >> (3 + (plane == 0 ? 0 : enc.ssY))) + row * 4;
        intraPredictor.predict(ref, refStride, recon.planes8[plane], dOff, recon.strides[plane], refOff, mode, txSize,
                haveTop, haveLeft, haveRight, x0, y0, recon.planeWidths[plane], recon.planeHeights[plane],
                mbToRightEdge < 0, mbToBottomEdge < 0);
    }

    /**
     * Builds the inter prediction of a plane of the block into the reconstruction
     * ({@code vp9_build_inter_predictors_sbp}).
     *
     * @param plane the plane index, 0 for luma and 1 or 2 for chroma
     * @param bsize the block size, at least 8x8
     */
    void buildInterPredictor(int plane, int bsize) {
        var recon = enc.recon;
        var sx = plane == 0 ? 0 : enc.ssX;
        var sy = plane == 0 ? 0 : enc.ssY;
        var w = (NUM_4X4_WIDE[bsize] * 4) >> sx;
        var h = (NUM_4X4_HIGH[bsize] * 4) >> sy;
        var x0 = (miCol * 8) >> sx;
        var y0 = (miRow * 8) >> sy;
        var m = mi;
        if (m.sbType < BLOCK_8X8) {
            var stride = recon.strides[plane];
            var n4w = Math.max(1, 2 >> sx);
            var n4h = Math.max(1, 2 >> sy);
            for (var ref = 0; ref < (m.ref1 > INTRA_FRAME ? 2 : 1); ref++) {
                var picture = enc.activeRef(ref == 0 ? m.ref0 : m.ref1);
                var i = 0;
                for (var y = 0; y < n4h; y++) {
                    for (var x = 0; x < n4w; x++) {
                        var mv = averageSplitMvs(m, plane, ref, i++, sx, sy);
                        predictor.predictInter(picture, plane, x0 + 4 * x, y0 + 4 * y, mv, m.interpFilter,
                                recon.planes8[plane], dstOff[plane] + 4 * (y * stride + x), stride, 4, 4, ref == 1);
                    }
                }
            }
            return;
        }
        predictor.predictInter(enc.activeRef(m.ref0), plane, x0, y0, m.mv0, m.interpFilter, recon.planes8[plane],
                dstOff[plane], recon.strides[plane], w, h, false);
        if (m.ref1 > INTRA_FRAME) {
            predictor.predictInter(enc.activeRef(m.ref1), plane, x0, y0, m.mv1, m.interpFilter, recon.planes8[plane],
                    dstOff[plane], recon.strides[plane], w, h, true);
        }
    }

    /**
     * Returns the vector of one 4x4 block of a plane of a sub-8x8 block, averaging the luma
     * sub-block vectors that a subsampled chroma block covers ({@code average_split_mvs}).
     *
     * @param m the block
     * @param plane the plane index
     * @param ref 0 for the first reference, 1 for the second
     * @param block the index of the plane's 4x4 block within the 8x8 block
     * @param sx the plane's horizontal subsampling shift
     * @param sy the plane's vertical subsampling shift
     * @return the packed vector
     */
    static int averageSplitMvs(ModeInfo m, int plane, int ref, int block, int sx, int sy) {
        var mvs = ref == 0 ? m.subMv0 : m.subMv1;
        if (plane == 0 || (sx == 0 && sy == 0)) {
            return mvs[block];
        }
        if (sx == 0) {
            return averageQ2(mvs[block], mvs[block + 2]);
        }
        if (sy == 0) {
            return averageQ2(mvs[block], mvs[block + 1]);
        }
        var row = (mvs[0] >> 16) + (mvs[1] >> 16) + (mvs[2] >> 16) + (mvs[3] >> 16);
        var col = (short) mvs[0] + (short) mvs[1] + (short) mvs[2] + (short) mvs[3];
        return ModeInfo.packMv((row < 0 ? row - 2 : row + 2) / 4, (col < 0 ? col - 2 : col + 2) / 4);
    }

    /**
     * Returns the rounded average of two packed vectors ({@code mi_mv_pred_q2}).
     *
     * @param a the first vector
     * @param b the second vector
     * @return the packed average
     */
    private static int averageQ2(int a, int b) {
        var row = (a >> 16) + (b >> 16);
        var col = (short) a + (short) b;
        return ModeInfo.packMv((row < 0 ? row - 1 : row + 1) / 2, (col < 0 ? col - 1 : col + 1) / 2);
    }

    /**
     * Builds the inter prediction of a plane of the block into a buffer.
     *
     * @param plane the plane index, 0 for luma and 1 or 2 for chroma
     * @param bsize the block size, at least 8x8
     * @param ref the reference frame
     * @param mv the packed motion vector
     * @param filter the interpolation filter
     * @param dst the buffer
     * @param dOff the index of the block in the buffer
     * @param dstStride the buffer's row stride
     */
    void buildInterPredictorInto(int plane, int bsize, int ref, int mv, int filter, byte[] dst, int dOff,
                                 int dstStride) {
        var sx = plane == 0 ? 0 : enc.ssX;
        var sy = plane == 0 ? 0 : enc.ssY;
        var w = (NUM_4X4_WIDE[bsize] * 4) >> sx;
        var h = (NUM_4X4_HIGH[bsize] * 4) >> sy;
        predictor.predictInter(enc.activeRef(ref), plane, (miCol * 8) >> sx, (miRow * 8) >> sy, mv, filter, dst, dOff,
                dstStride, w, h, false);
    }

    /**
     * Codes the block whose modes were picked: updates the segment, counts the modes,
     * reconstructs the block and tokenizes it ({@code encode_b_rt}).
     *
     * @param bsize the block size
     * @param rate the rate the mode decision estimated
     * @param dist the distortion the mode decision estimated
     */
    private void encodeBlock(int bsize, long rate, long dist) {
        var m = mi;
        if (enc.segEnabled && enc.cyclicRefreshEnabled) {
            enc.cr.updateSegment(this, bsize, rate, dist, skip);
            initPlaneQuantizers();
        }
        var inter = m.isInter();
        if (inter) {
            if (m.mode == NEWMV) {
                var ref = refMvs[m.ref0 * 2];
                counts.countMv((m.mv0 >> 16) - (ref >> 16), (short) m.mv0 - (short) ref);
            }
            if (enc.interpFilter == SWITCHABLE) {
                var ctx = Vp9PredContexts.switchableInterp(aboveMi, leftMi);
                counts.counts[Vp9EncCounts.SWITCHABLE_INTERP + ctx * 3 + m.interpFilter]++;
            }
        }
        var index = miRow * enc.miCols + miCol;
        enc.blockModeContext[index] = inter ? modeContext[m.ref0] : 0;
        enc.blockRefMv[index] = inter ? refMvs[m.ref0 * 2] : 0;
        if (!enc.keyFrame) {
            enc.storeFrameMvs(m, miRow, miCol, bsize);
        }
        skipTxfm = m.segmentId != 0 ? 0 : skipTxfm;
        encodeSuperblock(bsize);
        updateStats();
        tokens.endBlock();
    }

    /**
     * Counts the mode symbols of an inter frame block ({@code update_stats}).
     *
     */
    private void updateStats() {
        if (enc.keyFrame) {
            return;
        }
        var m = mi;
        var c = counts.counts;
        var inter = m.isInter();
        c[Vp9EncCounts.INTRA_INTER + Vp9PredContexts.intraInter(aboveMi, leftMi) * 2 + (inter ? 1 : 0)]++;
        if (inter) {
            var ref0 = m.ref0;
            if (enc.referenceMode == Vp9RtEncoder.REFERENCE_MODE_SELECT) {
                c[Vp9EncCounts.COMP_INTER + Vp9PredContexts.referenceMode(aboveMi, leftMi, enc.compFixedRef) * 2
                        + (m.hasSecondRef() ? 1 : 0)]++;
            }
            if (m.hasSecondRef()) {
                var fixedIdx = enc.signBias[enc.compFixedRef];
                var ctx = Vp9PredContexts.compRef(aboveMi, leftMi, fixedIdx, enc.compVarRef0, enc.compVarRef1,
                        enc.compFixedRef);
                var varRef = fixedIdx == 1 ? m.ref0 : m.ref1;
                c[Vp9EncCounts.COMP_REF + ctx * 2 + (varRef == enc.compVarRef1 ? 1 : 0)]++;
            } else {
                var ctx1 = Vp9PredContexts.singleRefP1(aboveMi, leftMi);
                c[Vp9EncCounts.SINGLE_REF + ctx1 * 4 + (ref0 != Vp9Constants.LAST_FRAME ? 1 : 0)]++;
                if (ref0 != Vp9Constants.LAST_FRAME) {
                    var ctx2 = Vp9PredContexts.singleRefP2(aboveMi, leftMi);
                    c[Vp9EncCounts.SINGLE_REF + ctx2 * 4 + 2 + (ref0 != Vp9Constants.GOLDEN_FRAME ? 1 : 0)]++;
                }
            }
            if (m.sbType < BLOCK_8X8) {
                var w4 = NUM_4X4_WIDE[m.sbType];
                var h4 = NUM_4X4_HIGH[m.sbType];
                for (var idy = 0; idy < 2; idy += h4) {
                    for (var idx = 0; idx < 2; idx += w4) {
                        c[Vp9EncCounts.INTER_MODE + modeContext[ref0] * 4 + (m.subModes[idy * 2 + idx] - NEARESTMV)]++;
                    }
                }
            } else {
                c[Vp9EncCounts.INTER_MODE + modeContext[ref0] * 4 + (m.mode - NEARESTMV)]++;
            }
        }
    }

    /**
     * Reconstructs and tokenizes the block ({@code encode_superblock}).
     *
     * @param bsize the block size
     */
    private void encodeSuperblock(int bsize) {
        var m = mi;
        var codeSize = Math.max(bsize, BLOCK_8X8);
        if (!m.isInter()) {
            m.skip = 1;
            for (var plane = 0; plane < 3; plane++) {
                if (high != null) {
                    high.encodeIntraPlane(codeSize, plane);
                } else {
                    encodeIntraPlane(codeSize, plane);
                }
            }
            if (!enc.keyFrame && !dryRun) {
                if (bsize < BLOCK_8X8) {
                    var w4 = NUM_4X4_WIDE[bsize];
                    var h4 = NUM_4X4_HIGH[bsize];
                    for (var idy = 0; idy < 2; idy += h4) {
                        for (var idx = 0; idx < 2; idx += w4) {
                            counts.counts[Vp9EncCounts.Y_MODE + m.subModes[idy * 2 + idx]]++;
                        }
                    }
                } else {
                    counts.counts[Vp9EncCounts.Y_MODE + Vp9Constants.SIZE_GROUP[bsize] * 10 + m.mode]++;
                }
            }
        } else if (high != null) {
            high.encodeInter(codeSize);
        } else {
            if (!enc.sf.reuseInterPredSby) {
                buildInterPredictor(0, codeSize);
            }
            buildInterPredictor(1, codeSize);
            buildInterPredictor(2, codeSize);
            encodeInter(codeSize);
        }
        if (high != null) {
            high.updateProxy(codeSize);
        }
        tokenizeBlock(codeSize);
        if (dryRun) {
            if (m.isInter() && m.skip != 0 && enc.txMode != TX_MODE_SELECT) {
                m.txSize = Math.min(Vp9Constants.TX_MODE_TO_BIGGEST[enc.txMode], Vp9Constants.MAX_TX_SIZE[bsize]);
            }
            return;
        }
        var c = counts.counts;
        if (enc.txMode == TX_MODE_SELECT && bsize >= BLOCK_8X8 && !(m.isInter() && m.skip != 0)) {
            var maxTx = Vp9Constants.MAX_TX_SIZE[bsize];
            var ctx = Vp9PredContexts.txSize(aboveMi, leftMi, maxTx);
            switch (maxTx) {
                case 1 -> c[Vp9EncCounts.TX_8X8 + ctx * 2 + m.txSize]++;
                case 2 -> c[Vp9EncCounts.TX_16X16 + ctx * 3 + m.txSize]++;
                default -> c[Vp9EncCounts.TX_32X32 + ctx * 4 + m.txSize]++;
            }
        } else if (m.isInter()) {
            m.txSize = Math.min(Vp9Constants.TX_MODE_TO_BIGGEST[enc.txMode], Vp9Constants.MAX_TX_SIZE[bsize]);
        } else {
            m.txSize = bsize >= BLOCK_8X8 ? m.txSize : TX_4X4;
        }
        c[Vp9EncCounts.TX_TOTALS + m.txSize]++;
        c[Vp9EncCounts.TX_TOTALS + enc.uvTxSize(m)]++;
        if (enc.segEnabled && enc.cyclicRefreshEnabled) {
            enc.cr.updateSbPostencode(enc, m, miRow, miCol, bsize);
        }
        if (enc.temporalLayerId == 0 && !enc.keyFrame) {
            enc.updateZeroMvCount(m, miRow, miCol, bsize);
        }
    }

    /**
     * Reconstructs one plane of an intra block, transform block by transform block
     * ({@code vp9_encode_intra_block_plane}).
     *
     * @param bsize the block size, at least 8x8
     * @param plane the plane index, 0 for luma and 1 or 2 for chroma
     */
    private void encodeIntraPlane(int bsize, int plane) {
        var m = mi;
        var sx = plane == 0 ? 0 : enc.ssX;
        var sy = plane == 0 ? 0 : enc.ssY;
        var planeBsize = plane == 0 ? bsize : enc.uvBsize(bsize);
        var txSize = plane == 0 ? m.txSize : enc.uvTxSize(m);
        var num4w = NUM_4X4_WIDE[planeBsize];
        var num4h = NUM_4X4_HIGH[planeBsize];
        var maxWide = num4w + (mbToRightEdge >= 0 ? 0 : mbToRightEdge >> (5 + sx));
        var maxHigh = num4h + (mbToBottomEdge >= 0 ? 0 : mbToBottomEdge >> (5 + sy));
        var step = 1 << txSize;
        var bwl = B_WIDTH_LOG2[planeBsize];
        var recon = enc.recon;
        var dstPlane = recon.planes8[plane];
        var dstStride = recon.strides[plane];
        var src = enc.source;
        var srcPlane = src.planes[plane];
        var srcStride = src.strides[plane];
        var q = enc.quant;
        var y = plane == 0;
        var pidx = qindex * 2;
        var size = 4 << txSize;
        var n = size * size;
        var eob = eobs[plane];
        var cf = coeff[plane];
        var qc = qcoeff[plane];
        var dq = dqcoeff[plane];
        var optimize = optimizesCoding();
        if (optimize) {
            entropyContexts(plane, planeBsize, txSize, codingAbove, codingLeft);
        }
        for (var r = 0; r < maxHigh; r += step) {
            for (var c = 0; c < maxWide; c += step) {
                var block = r * num4w + (c << txSize);
                var mode = plane == 0 ? (txSize == TX_4X4 ? m.yMode((r << 1) + c) : m.mode) : m.uvMode;
                var dOff = dstOff[plane] + 4 * (r * dstStride + c);
                var sOff = srcOff[plane] + 4 * (r * srcStride + c);
                predictIntraBlock(plane, bwl, txSize, mode, c, r, dOff, dstPlane, dOff, dstStride);
                var txType = plane == 0 && txSize != TX_32X32 && !enc.lossless ? Vp9Constants.MODE_TO_TX_TYPE[mode]
                        : DCT_DCT;
                edsp.subtract(srcDiff, 0, size, srcPlane, sOff, srcStride, dstPlane, dOff, dstStride, size, size);
                var off = block * 16;
                forwardTransform(txSize, txType, cf, off);
                var scan = SCANS[txSize * 4 + txType];
                int e;
                if (y) {
                    e = Vp9Quantizer.quantizeB(cf, off, n, qc, dq, scan, q.yZbin, q.yRound, q.yQuant, q.yQuantShift,
                            q.yDequant, pidx, txSize == TX_32X32);
                } else {
                    e = Vp9Quantizer.quantizeB(cf, off, n, qc, dq, scan, q.uvZbin, q.uvRound, q.uvQuant, q.uvQuantShift,
                            q.uvDequant, pidx, txSize == TX_32X32);
                }
                if (optimize) {
                    e = optimizeBlock(optimizer, q, 8, plane, off, e, txSize, txType, codingAbove[c] + codingLeft[r]);
                    codingAbove[c] = codingLeft[r] = (byte) (e > 0 ? 1 : 0);
                }
                eob[block] = e;
                if (e > 0) {
                    reconstruct(txSize, txType, e, dq, off, scan, dstPlane, dOff, dstStride);
                    m.skip = 0;
                }
            }
        }
    }

    /**
     * Computes the forward transform of the residual in {@link #srcDiff}.
     *
     * @param txSize the transform size
     * @param txType the transform type
     * @param out receives the coefficients
     * @param off the index of the first coefficient
     */
    private void forwardTransform(int txSize, int txType, int[] out, int off) {
        forwardTransform(txSize, txType, srcDiff, 0, 4 << txSize, out, off);
    }

    /**
     * Computes the forward transform of a residual block.
     *
     * @param txSize the transform size
     * @param txType the transform type
     * @param diff the residuals
     * @param diffOff the index of the block's first residual
     * @param diffStride the residual row stride
     * @param out receives the coefficients
     * @param off the index of the first coefficient
     */
    void forwardTransform(int txSize, int txType, short[] diff, int diffOff, int diffStride, int[] out, int off) {
        switch (txSize) {
            case TX_4X4 -> {
                if (enc.lossless) {
                    Vp9FwdTxfm.fwht4x4(diff, diffOff, diffStride, out, off);
                } else if (txType == DCT_DCT) {
                    edsp.fdct4x4(diff, diffOff, diffStride, out, off, fdctScratch);
                } else {
                    Vp9FwdTxfm.fht4x4(diff, diffOff, diffStride, out, off, txType, fdctScratch);
                }
            }
            case 1 -> {
                if (txType == DCT_DCT) {
                    edsp.fdct8x8(diff, diffOff, diffStride, out, off, fdctScratch);
                } else {
                    Vp9FwdTxfm.fht8x8(diff, diffOff, diffStride, out, off, txType, fdctScratch);
                }
            }
            case TX_16X16 -> {
                if (txType == DCT_DCT) {
                    edsp.fdct16x16(diff, diffOff, diffStride, out, off, fdctScratch, fdctInts);
                } else {
                    Vp9FwdTxfm.fht16x16(diff, diffOff, diffStride, out, off, txType, fdctScratch);
                }
            }
            default -> edsp.fdct32x32(diff, diffOff, diffStride, out, off, true, fdctScratch, fdctInts);
        }
    }

    /**
     * Adds the inverse transform of dequantized coefficients to the prediction, as the decoder
     * does: the inverse Walsh-Hadamard transform in a lossless frame.
     *
     * @param txSize the transform size
     * @param txType the transform type
     * @param eob the end of block
     * @param dq the dequantized coefficients in raster order
     * @param off the index of the first coefficient
     * @param scan the scan order
     * @param dst the plane holding the prediction
     * @param dOff the index of the block's top-left sample
     * @param dstStride the plane's row stride
     */
    void reconstruct(int txSize, int txType, int eob, int[] dq, int off, short[] scan, byte[] dst, int dOff,
                     int dstStride) {
        if (enc.lossless) {
            var lc = layoutCoef;
            for (var i = 0; i < eob; i++) {
                var rc = scan[i];
                lc[rc] = dq[off + rc];
            }
            scalarDsp.inverseWhtAdd(eob, lc, 0, dst, dOff, dstStride, transformScratch);
            for (var i = 0; i < eob; i++) {
                lc[scan[i]] = 0;
            }
            return;
        }
        var kind = txSize * 4 + txType;
        var vector = txSize != TX_4X4 && (eob == 1 && txType == DCT_DCT || enc.vectorTransforms[kind]);
        if (eob > 1 || txType != DCT_DCT) {
            transformCalls[kind]++;
        }
        var layout = vector ? LAYOUTS[txSize] : SCALAR_LAYOUTS[txSize];
        var lc = layoutCoef;
        for (var i = 0; i < eob; i++) {
            var rc = scan[i];
            lc[layout[rc]] = dq[off + rc];
        }
        (vector ? dsp : scalarDsp).inverseTransformAdd(txSize, txType, eob, lc, 0, dst, dOff, dstStride,
                transformScratch);
        for (var i = 0; i < eob; i++) {
            lc[layout[scan[i]]] = 0;
        }
    }

    /**
     * Quantizes and reconstructs the residual of an inter block whose prediction is in the
     * reconstruction ({@code vp9_encode_sb}).
     *
     * @param bsize the block size, at least 8x8
     */
    private void encodeInter(int bsize) {
        var m = mi;
        m.skip = 1;
        if (skip) {
            return;
        }
        var q = enc.quant;
        var pidx = qindex * 2;
        var fp = enc.sf.useQuantFp;
        var recon = enc.recon;
        var src = enc.source;
        for (var plane = 0; plane < 3; plane++) {
            var sx = plane == 0 ? 0 : enc.ssX;
            var sy = plane == 0 ? 0 : enc.ssY;
            var planeBsize = plane == 0 ? bsize : enc.uvBsize(bsize);
            var bw = NUM_4X4_WIDE[planeBsize] * 4;
            var bh = NUM_4X4_HIGH[planeBsize] * 4;
            var dstPlane = recon.planes8[plane];
            var dstStride = recon.strides[plane];
            edsp.subtract(srcDiff, 0, bw, src.planes[plane], srcOff[plane], src.strides[plane], dstPlane,
                    dstOff[plane], dstStride, bw, bh);
            var txSize = plane == 0 ? m.txSize : enc.uvTxSize(m);
            var num4w = NUM_4X4_WIDE[planeBsize];
            var maxWide = num4w + (mbToRightEdge >= 0 ? 0 : mbToRightEdge >> (5 + sx));
            var maxHigh = NUM_4X4_HIGH[planeBsize] + (mbToBottomEdge >= 0 ? 0 : mbToBottomEdge >> (5 + sy));
            var step = 1 << txSize;
            var size = 4 << txSize;
            var n = size * size;
            var scan = SCANS[txSize * 4];
            var eob = eobs[plane];
            var cf = coeff[plane];
            var qc = qcoeff[plane];
            var dq = dqcoeff[plane];
            var y = plane == 0;
            var optimize = optimizesCoding();
            if (optimize) {
                entropyContexts(plane, planeBsize, txSize, codingAbove, codingLeft);
            }
            for (var r = 0; r < maxHigh; r += step) {
                for (var c = 0; c < maxWide; c += step) {
                    var block = r * num4w + (c << txSize);
                    var off = block * 16;
                    if (fp && skipTxfm == 1 && y) {
                        eob[block] = 0;
                        continue;
                    }
                    var diffOff = 4 * (r * bw + c);
                    switch (txSize) {
                        case TX_4X4 -> {
                            if (enc.lossless) {
                                Vp9FwdTxfm.fwht4x4(srcDiff, diffOff, bw, cf, off);
                            } else {
                                edsp.fdct4x4(srcDiff, diffOff, bw, cf, off, fdctScratch);
                            }
                        }
                        case 1 -> edsp.fdct8x8(srcDiff, diffOff, bw, cf, off, fdctScratch);
                        case TX_16X16 -> edsp.fdct16x16(srcDiff, diffOff, bw, cf, off, fdctScratch, fdctInts);
                        default -> edsp.fdct32x32(srcDiff, diffOff, bw, cf, off, true, fdctScratch, fdctInts);
                    }
                    int e;
                    if (fp) {
                        var iscan = ISCANS[txSize * 4];
                        e = y ? edsp.quantizeFp(cf, off, n, qc, dq, scan, iscan, q.yRoundFp, q.yQuantFp, q.yDequant,
                                pidx, txSize == TX_32X32)
                                : edsp.quantizeFp(cf, off, n, qc, dq, scan, iscan, q.uvRoundFp, q.uvQuantFp,
                                q.uvDequant, pidx, txSize == TX_32X32);
                    } else {
                        e = y ? Vp9Quantizer.quantizeB(cf, off, n, qc, dq, scan, q.yZbin, q.yRound, q.yQuant,
                                q.yQuantShift, q.yDequant, pidx, txSize == TX_32X32)
                                : Vp9Quantizer.quantizeB(cf, off, n, qc, dq, scan, q.uvZbin, q.uvRound, q.uvQuant,
                                q.uvQuantShift, q.uvDequant, pidx, txSize == TX_32X32);
                    }
                    if (optimize) {
                        e = optimizeBlock(optimizer, q, 8, plane, off, e, txSize, DCT_DCT,
                                codingAbove[c] + codingLeft[r]);
                        codingAbove[c] = codingLeft[r] = (byte) (e > 0 ? 1 : 0);
                    }
                    eob[block] = e;
                    if (e > 0) {
                        m.skip = 0;
                        reconstruct(txSize, DCT_DCT, e, dq, off, scan, dstPlane,
                                dstOff[plane] + 4 * (r * dstStride + c), dstStride);
                    }
                }
            }
        }
    }

    /**
     * Tokenizes the coefficients of every transform block of the block, or records a skipped
     * block, updating the nonzero contexts ({@code vp9_tokenize_sb}).
     *
     * @param bsize the block size, at least 8x8
     */
    private void tokenizeBlock(int bsize) {
        var m = mi;
        var ctx = skipContext();
        var c = counts.counts;
        if (m.skip != 0) {
            if (!dryRun) {
                c[Vp9EncCounts.SKIP + ctx * 2 + 1]++;
            }
            resetSkipContext(bsize);
            return;
        }
        if (!dryRun) {
            c[Vp9EncCounts.SKIP + ctx * 2]++;
        }
        for (var plane = 0; plane < 3; plane++) {
            var sx = plane == 0 ? 0 : enc.ssX;
            var sy = plane == 0 ? 0 : enc.ssY;
            var planeBsize = plane == 0 ? bsize : enc.uvBsize(bsize);
            var txSize = plane == 0 ? m.txSize : enc.uvTxSize(m);
            var num4w = NUM_4X4_WIDE[planeBsize];
            var num4h = NUM_4X4_HIGH[planeBsize];
            var maxWide = num4w + (mbToRightEdge >= 0 ? 0 : mbToRightEdge >> (5 + sx));
            var maxHigh = num4h + (mbToBottomEdge >= 0 ? 0 : mbToBottomEdge >> (5 + sy));
            var step = 1 << txSize;
            var above = enc.aboveEntropy[plane];
            var left = leftEntropy[plane];
            var aboveBase = (miCol * 2) >> sx;
            var leftBase = ((miRow * 2) & 15) >> sy;
            for (var r = 0; r < maxHigh; r += step) {
                for (var col = 0; col < maxWide; col += step) {
                    var block = r * num4w + (col << txSize);
                    var txType = DCT_DCT;
                    if (plane == 0 && !m.isInter() && txSize != TX_32X32 && !enc.lossless) {
                        var mode = txSize == TX_4X4 ? m.yMode((r << 1) + col) : m.mode;
                        txType = Vp9Constants.MODE_TO_TX_TYPE[mode];
                    }
                    var eob = eobs[plane][block];
                    if (!dryRun) {
                        tokenizeTxBlock(plane, txSize, txType, block * 16, eob, above, aboveBase + col, left,
                                leftBase + r);
                    }
                    setContexts(above, aboveBase + col, left, leftBase + r, txSize, eob > 0, maxWide, maxHigh, col, r);
                }
            }
        }
    }

    /**
     * Tokenizes the coefficients of one transform block ({@code tokenize_b}).
     *
     * @param plane the plane index, 0 for luma and 1 or 2 for chroma
     * @param txSize the transform size
     * @param txType the transform type, which selects the scan
     * @param off the index of the block's first coefficient
     * @param eob the block's end of block
     * @param above the above nonzero contexts
     * @param aOff the index of the block's first above context
     * @param left the left nonzero contexts
     * @param lOff the index of the block's first left context
     */
    private void tokenizeTxBlock(int plane, int txSize, int txType, int off, int eob, byte[] above, int aOff,
                                 byte[] left, int lOff) {
        var size = 1 << txSize;
        var aboveNonzero = 0;
        var leftNonzero = 0;
        for (var i = 0; i < size; i++) {
            aboveNonzero |= above[aOff + i];
            leftNonzero |= left[lOff + i];
        }
        var pt = (aboveNonzero != 0 ? 1 : 0) + (leftNonzero != 0 ? 1 : 0);
        var type = plane == 0 ? 0 : 1;
        var ref = mi.isInter() ? 1 : 0;
        var set = (txSize * 2 + type) * 2 + ref;
        var probBase = Vp9FrameContext.COEF + set * 6 * 6 * 3;
        var countBase = Vp9EncCounts.COEF + set * 6 * 6 * 12;
        var eobBase = Vp9EncCounts.EOB_BRANCH + set * 6 * 6;
        var bands = txSize == TX_4X4 ? Vp9ProbTables.BAND_4X4 : Vp9ProbTables.BAND_8X8_PLUS;
        var scan = SCANS[txSize * 4 + txType];
        var nb = NEIGHBORS[txSize * 4 + txType];
        var maxEob = 16 << (txSize << 1);
        var qc = qcoeff[plane];
        var cache = tokenCache;
        var c = counts.counts;
        var t = tokens;
        var i = 0;
        while (i < eob) {
            var v = qc[off + scan[i]];
            var unit = bands[i] * 6 + pt;
            c[eobBase + unit]++;
            while (v == 0) {
                t.add(probBase + unit * 3, Vp9EncTables.ZERO_TOKEN, 0);
                c[countBase + unit * 12]++;
                cache[scan[i]] = 0;
                i++;
                pt = (1 + cache[nb[2 * i]] + cache[nb[2 * i + 1]]) >> 1;
                unit = bands[i] * 6 + pt;
                v = qc[off + scan[i]];
            }
            var token = Vp9EncTables.token(v);
            t.add(probBase + unit * 3, token, Vp9EncTables.tokenExtra(v, token));
            c[countBase + unit * 12 + token]++;
            cache[scan[i]] = Vp9Constants.ENERGY_CLASS[token];
            i++;
            if (i < maxEob) {
                pt = (1 + cache[nb[2 * i]] + cache[nb[2 * i + 1]]) >> 1;
            }
        }
        if (i < maxEob) {
            var unit = bands[i] * 6 + pt;
            c[eobBase + unit]++;
            t.add(probBase + unit * 3, Vp9EncTables.EOB_TOKEN, 0);
            c[countBase + unit * 12 + Vp9EncTables.EOB_TOKEN]++;
        }
    }

    /**
     * Sets the nonzero contexts a transform block leaves, clearing those past the frame edge
     * ({@code vp9_set_contexts}).
     *
     * @param above the above contexts
     * @param aOff the index of the block's first above context
     * @param left the left contexts
     * @param lOff the index of the block's first left context
     * @param txSize the transform size
     * @param hasEob whether the block has coefficients
     * @param maxWide the number of visible 4x4 columns of the plane block
     * @param maxHigh the number of visible 4x4 rows of the plane block
     * @param col the block's column in the plane block, in 4x4 units
     * @param row the block's row in the plane block, in 4x4 units
     */
    private void setContexts(byte[] above, int aOff, byte[] left, int lOff, int txSize, boolean hasEob, int maxWide,
                             int maxHigh, int col, int row) {
        var size = 1 << txSize;
        var value = (byte) (hasEob ? 1 : 0);
        var aboveCount = size;
        var leftCount = size;
        if (hasEob && mbToRightEdge < 0 && aboveCount + col > maxWide) {
            aboveCount = maxWide - col;
        }
        if (hasEob && mbToBottomEdge < 0 && leftCount + row > maxHigh) {
            leftCount = maxHigh - row;
        }
        for (var i = 0; i < size; i++) {
            above[aOff + i] = i < aboveCount ? value : 0;
            left[lOff + i] = i < leftCount ? value : 0;
        }
    }

    /**
     * Clears the nonzero contexts a skipped block covers ({@code reset_skip_context}).
     *
     * @param bsize the block size, at least 8x8
     */
    private void resetSkipContext(int bsize) {
        for (var plane = 0; plane < 3; plane++) {
            var sx = plane == 0 ? 0 : enc.ssX;
            var sy = plane == 0 ? 0 : enc.ssY;
            var planeBsize = plane == 0 ? bsize : enc.uvBsize(bsize);
            var w = NUM_4X4_WIDE[planeBsize];
            var h = NUM_4X4_HIGH[planeBsize];
            Arrays.fill(enc.aboveEntropy[plane], (miCol * 2) >> sx, ((miCol * 2) >> sx) + w, (byte) 0);
            var lb = ((miRow * 2) & 15) >> sy;
            Arrays.fill(leftEntropy[plane], lb, lb + h, (byte) 0);
        }
    }

    /**
     * Returns the rate distortion cost of a rate and a distortion ({@code RDCOST}).
     *
     * @param rdmult the multiplier
     * @param rate the rate in cost units
     * @param dist the distortion
     * @return the cost
     */
    static long rdCost(int rdmult, long rate, long dist) {
        return ((rate * rdmult + 256) >> Vp9EncTables.PROB_COST_SHIFT) + (dist << RDDIV);
    }
}
