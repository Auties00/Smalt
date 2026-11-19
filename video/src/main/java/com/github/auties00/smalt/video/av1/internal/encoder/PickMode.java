package com.github.auties00.smalt.video.av1.internal.encoder;

import com.github.auties00.smalt.video.av1.internal.Av1Block;
import com.github.auties00.smalt.video.av1.internal.Av1Picture;
import com.github.auties00.smalt.video.av1.internal.Cdf;
import com.github.auties00.smalt.video.av1.internal.EncoderBridge;
import com.github.auties00.smalt.video.av1.internal.FrameHeader;
import com.github.auties00.smalt.video.av1.internal.IntraEdge;
import com.github.auties00.smalt.video.av1.internal.IpredDsp;
import com.github.auties00.smalt.video.av1.internal.McDsp;
import com.github.auties00.smalt.video.av1.internal.RefMvs;
import com.github.auties00.smalt.video.av1.internal.Tables;

import java.util.Arrays;

import static com.github.auties00.smalt.video.av1.internal.Tables.BLOCK_DIMENSIONS;

/**
 * Chooses the coding mode of a block without coding it: the reference, inter mode and motion
 * vector, or the intra mode, together with the transform size and whether the residual is
 * skipped, from statistical rate and distortion estimates.
 *
 * <p>An instance belongs to one tile encoder and is used by one thread at a time.
 *
 * @implNote This implementation ports libaom's non-RD mode decision ({@code av1/encoder/nonrd_pickmode.c}:
 * {@code av1_nonrd_pick_inter_mode_sb}, {@code handle_inter_mode_nonrd},
 * {@code skip_inter_mode_nonrd}, {@code get_ref_frame_use_mask},
 * {@code calculate_tx_size}, {@code model_rd_for_sb_y},
 * {@code model_skip_for_sb_y_large}, {@code search_new_mv},
 * {@code combined_motion_search}, {@code newmv_diff_bias},
 * {@code set_color_sensitivity}, {@code av1_nonrd_pick_intra_mode}) and
 * {@code av1/encoder/nonrd_opt.c} ({@code av1_block_yrd},
 * {@code av1_model_rd_for_sb_uv}, {@code av1_estimate_intra_mode}), with the luma palette
 * search of screen content frames ({@code enable_palette}, {@code handle_screen_content_mode_nonrd}
 * and {@code av1_search_palette_mode_luma}, the palette sizes searched from the largest down with
 * a weighted one-dimensional k-means over the block's distinct values).
 */
final class PickMode {
    /**
     * The most references a frame searches.
     */
    private static final int MAX_REFS = 7;

    /**
     * The distance between the two chroma planes in the chroma prediction buffers.
     */
    private static final int CHROMA_PLANE = 64 * 64;

    /**
     * The searched inter modes in order.
     */
    private static final int[] MODES = {ModeWriter.NEARESTMV, ModeWriter.NEARMV, ModeWriter.GLOBALMV,
            ModeWriter.NEWMV};

    /**
     * The forward references of the widest compound search: {@code LAST}, {@code LAST2} and
     * {@code GOLDEN}.
     */
    private static final int[] COMPOUND_FORWARD_ALL = {0, 1, 3};

    /**
     * The forward reference of the narrower compound searches: {@code LAST}.
     */
    private static final int[] COMPOUND_FORWARD_LAST = {0};

    /**
     * The backward references of the widest compound search: {@code BWDREF}, {@code ALTREF2} and
     * {@code ALTREF}.
     */
    private static final int[] COMPOUND_BACKWARD_ALL = {4, 5, 6};

    /**
     * The backward references of the narrower compound searches: {@code BWDREF} and {@code ALTREF}.
     */
    private static final int[] COMPOUND_BACKWARD_NEAR = {4, 6};

    /**
     * The compound modes of the wider compound searches.
     */
    private static final int[] COMPOUND_MODES_ALL = {ModeWriter.NEAREST_NEARESTMV, ModeWriter.NEW_NEWMV,
            ModeWriter.GLOBAL_GLOBALMV, ModeWriter.NEAR_NEARMV, ModeWriter.NEAREST_NEWMV, ModeWriter.NEW_NEARESTMV};

    /**
     * The compound modes of the narrowest compound search.
     */
    private static final int[] COMPOUND_MODES_BASIC = {ModeWriter.NEAREST_NEARESTMV, ModeWriter.NEW_NEWMV,
            ModeWriter.GLOBAL_GLOBALMV};

    /**
     * The intra modes searched in inter frames and key frames ({@code intra_mode_list}).
     */
    private static final int[] INTRA_MODES = {IpredDsp.DC_PRED, IpredDsp.VERT_PRED, IpredDsp.HOR_PRED,
            IpredDsp.SMOOTH_PRED};

    /**
     * The early termination thresholds by aggressiveness and size group ({@code early_term_thresh}).
     */
    private static final double[][] EARLY_TERM = {{0.65, 0.65, 0.65, 0.7}, {0.6, 0.65, 0.85, 0.9},
            {0.5, 0.5, 0.55, 0.6}, {0.6, 0.75, 0.85, 0.85}};

    /**
     * The 8x8 Hadamard scan, transposed like the coefficients ({@code default_scan_8x8_transpose}).
     */
    private static final int[] SCAN_8X8_T = {
            0, 8, 1, 2, 9, 16, 24, 17, 10, 3, 4, 11, 18, 25, 32, 40,
            33, 26, 19, 12, 5, 6, 13, 20, 27, 34, 41, 48, 56, 49, 42, 35,
            28, 21, 14, 7, 15, 22, 29, 36, 43, 50, 57, 58, 51, 44, 37, 30,
            23, 31, 38, 45, 52, 59, 60, 53, 46, 39, 47, 54, 61, 62, 55, 63
    };

    /**
     * The 16x16 Hadamard scan, transposed like the coefficients ({@code default_scan_lp_16x16_transpose}).
     */
    private static final int[] SCAN_16X16_T = {
            0, 8, 2, 4, 10, 16, 24, 18, 12, 6, 64, 14, 20, 26, 32,
            40, 34, 28, 22, 72, 66, 68, 74, 80, 30, 36, 42, 48, 56, 50,
            44, 38, 88, 82, 76, 70, 128, 78, 84, 90, 96, 46, 52, 58, 1,
            9, 3, 60, 54, 104, 98, 92, 86, 136, 130, 132, 138, 144, 94, 100,
            106, 112, 62, 5, 11, 17, 25, 19, 13, 7, 120, 114, 108, 102, 152,
            146, 140, 134, 192, 142, 148, 154, 160, 110, 116, 122, 65, 15, 21, 27,
            33, 41, 35, 29, 23, 73, 67, 124, 118, 168, 162, 156, 150, 200, 194,
            196, 202, 208, 158, 164, 170, 176, 126, 69, 75, 81, 31, 37, 43, 49,
            57, 51, 45, 39, 89, 83, 77, 71, 184, 178, 172, 166, 216, 210, 204,
            198, 206, 212, 218, 224, 174, 180, 186, 129, 79, 85, 91, 97, 47, 53,
            59, 61, 55, 105, 99, 93, 87, 137, 131, 188, 182, 232, 226, 220, 214,
            222, 228, 234, 240, 190, 133, 139, 145, 95, 101, 107, 113, 63, 121, 115,
            109, 103, 153, 147, 141, 135, 248, 242, 236, 230, 238, 244, 250, 193, 143,
            149, 155, 161, 111, 117, 123, 125, 119, 169, 163, 157, 151, 201, 195, 252,
            246, 254, 197, 203, 209, 159, 165, 171, 177, 127, 185, 179, 173, 167, 217,
            211, 205, 199, 207, 213, 219, 225, 175, 181, 187, 189, 183, 233, 227, 221,
            215, 223, 229, 235, 241, 191, 249, 243, 237, 231, 239, 245, 251, 253, 247,
            255
    };

    /**
     * The quantizer-band multipliers of the transform size decision ({@code calculate_tx_size}).
     */
    private static final int[] TX_MULTIPLIER = {8, 7, 6, 5};

    /**
     * The full-sample vector lengths beyond which sub-sample precision is reduced, by level and
     * block area ({@code subpel_select}).
     */
    private static final int[][] HIGHMOTION_THRESH = {{4, 8, 10}, {4, 6, 8}};

    /**
     * A flat mid-gray 64x64 block of each bit depth, 8, 10 and 12, the reference of the source
     * variance.
     */
    private static final short[][] FLAT = {flat(8), flat(10), flat(12)};

    /**
     * The scan position plus one of each coefficient of {@link #SCAN_8X8_T}.
     */
    private static final int[] ISCAN_8X8_T = CoefWriter.inverseScan(SCAN_8X8_T);

    /**
     * The scan position plus one of each coefficient of {@link #SCAN_16X16_T}.
     */
    private static final int[] ISCAN_16X16_T = CoefWriter.inverseScan(SCAN_16X16_T);

    /**
     * The libaom square size of each block size, as a transform size ({@code max_txsize_lookup}).
     */
    private static final int[] MAX_SQUARE_TX = new int[22];

    static {
        for (var bs = 0; bs < 22; bs++) {
            var w = BLOCK_DIMENSIONS[bs * 4];
            var h = BLOCK_DIMENSIONS[bs * 4 + 1];
            var m = Math.min(Math.min(w, h), 16);
            MAX_SQUARE_TX[bs] = Integer.numberOfTrailingZeros(m);
        }
    }

    /**
     * The frame encoder.
     */
    private final FrameEncoder frame;

    /**
     * The tile encoder.
     */
    private final TileEncoder tile;

    /**
     * The kernels.
     */
    private EncDsp dsp;

    /**
     * The motion search.
     */
    private final MotionSearch search;

    /**
     * The superblock statistics.
     */
    final VarPartition part;

    /**
     * The decoder reference index of each searchable reference of the frame, {@code LAST} first,
     * then in increasing index order: {@code LAST}, {@code GOLDEN} and {@code ALTREF} in real time.
     */
    private final int[] refs = new int[MAX_REFS];

    /**
     * The number of searchable references of the frame.
     */
    private int nRefs;

    /**
     * The candidate lists of each searched reference.
     */
    final RefMvs.Candidates[] cand = new RefMvs.Candidates[MAX_REFS];

    /**
     * Whether the decision searches only the references of {@link #screenRefs}, skips the
     * interpolation filter search and the compound modes.
     */
    boolean screen;

    /**
     * The references, as a bit mask over the searchable references' indices, a screening decision
     * searches besides the first.
     */
    int screenRefs = -1;

    /**
     * The index of the reference the last inter decision chose.
     */
    int lastBestRef;

    /**
     * The motion vector of each mode and searched reference, as {@code [mode][ref]}.
     */
    private final int[] frameMv = new int[4 * MAX_REFS];

    /**
     * Whether a mode and reference was evaluated.
     */
    private final boolean[] modeChecked = new boolean[4 * MAX_REFS];

    /**
     * The best SAD of the predicted vectors of each reference ({@code pred_mv_sad}).
     */
    private final int[] predMvSad = new int[MAX_REFS];

    /**
     * The SAD of the nearest vector of each reference.
     */
    private final int[] predMv0Sad = new int[MAX_REFS];

    /**
     * The SAD of the near vector of each reference.
     */
    private final int[] predMv1Sad = new int[MAX_REFS];

    /**
     * Whether each reference is searched.
     */
    private final boolean[] useRef = new boolean[MAX_REFS];

    /**
     * The candidate lists of the compound reference pairs searched.
     */
    private final RefMvs.Candidates[] compCand = {new RefMvs.Candidates(), new RefMvs.Candidates(),
            new RefMvs.Candidates(), new RefMvs.Candidates()};

    /**
     * The candidate list of the skip mode references.
     */
    private final RefMvs.Candidates skipCand = new RefMvs.Candidates();

    /**
     * Whether the frame codes the compound flag of the current block.
     */
    private boolean compFlagCoded;

    /**
     * Whether the frame codes the skip mode flag of the current block.
     */
    private boolean skipModeCoded;

    /**
     * The luma prediction of the second reference of an evaluated compound mode.
     */
    private short[] predComp = new short[64 * 64];

    /**
     * The chroma predictions of the second reference of an evaluated compound mode.
     */
    private final short[] predUvComp = new short[2 * CHROMA_PLANE];

    /**
     * The luma prediction of the evaluated mode.
     */
    private short[] predY = new short[64 * 64];

    /**
     * The luma prediction of the second interpolation filter of a filter search.
     */
    private short[] predAlt = new short[64 * 64];

    /**
     * The luma prediction of the best inter mode so far, of stride the block width.
     */
    private short[] bestPred = new short[64 * 64];

    /**
     * Whether {@link #bestPred} holds the chosen mode's luma prediction, which is exactly the
     * prediction a decoder forms, so the reconstruction can reuse it.
     */
    boolean predictionReusable;

    /**
     * The Lagrangian multiplier of the current block: the frame's, or the boosted one of a
     * cyclic refresh segment.
     */
    private int rdmult;

    /**
     * The luma transform type of the chosen intra mode, {@code DCT_DCT} unless the
     * rate-distortion intra search chose another.
     */
    int lumaTxType;

    /**
     * The rate-distortion intra search of small key frame blocks.
     */
    private IntraRd intraRd;

    /**
     * The skip flag costs of the current block, for the rate-distortion intra search.
     */
    private final int[] skipCosts = new int[2];

    /**
     * The estimated rate of the chosen mode.
     */
    long bestRate;

    /**
     * The estimated distortion of the chosen mode.
     */
    long bestDist;

    /**
     * The interpolation filter of the chosen mode: 0 regular, 1 smooth.
     */
    int filter;

    /**
     * The interpolation filter of the evaluated mode.
     */
    private int curFilter;

    /**
     * The filter the neighbors predict for blocks that skip the filter search
     * ({@code filt_select}).
     */
    private int filtSelect;

    /**
     * The chroma predictions of the evaluated mode.
     */
    private final short[] predUv = new short[2 * CHROMA_PLANE];

    /**
     * The residual of the block.
     */
    private final int[] diff = new int[64 * 64];

    /**
     * The Hadamard coefficients.
     */
    private final int[] coeff = new int[256];

    /**
     * The quantized Hadamard coefficients.
     */
    private final int[] qcoeff = new int[256];

    /**
     * The dequantized Hadamard coefficients.
     */
    private final int[] dqcoeff = new int[256];

    /**
     * The sum of level magnitudes and the squared error of the last {@link EncDsp#quantizeLpRd}.
     */
    private final long[] rdResult = new long[2];

    /**
     * The Hadamard scratch.
     */
    private final int[] hadScratch = new int[64];

    /**
     * The motion compensation scratch.
     */
    private final short[] mcScratch = new short[McDsp.SCRATCH_SHORTS];

    /**
     * The rate and distortion of a model call.
     */
    private final long[] model = new long[2];

    /**
     * The color sensitivity of the block's U and V planes.
     */
    private final int[] colorSens = new int[2];

    /**
     * The per-sample source variance of the block.
     */
    private int sourceVariance;

    /**
     * Whether the chosen mode skips the residual.
     */
    boolean skipTxfm;

    /**
     * The most single-reference candidates the mode search keeps for a rate-distortion check.
     */
    static final int FINALISTS = 4;

    /**
     * The number of candidates kept by the last single-reference search.
     */
    int finalistCount;

    /**
     * The reference slot (index of the searched references) of each kept candidate.
     */
    private final int[] finRef = new int[FINALISTS];

    /**
     * The inter mode of each kept candidate.
     */
    private final int[] finMode = new int[FINALISTS];

    /**
     * The motion vector of each kept candidate.
     */
    private final int[] finMv = new int[FINALISTS];

    /**
     * The interpolation filter of each kept candidate.
     */
    private final int[] finFilter = new int[FINALISTS];

    /**
     * The estimated transform size of each kept candidate.
     */
    private final int[] finTx = new int[FINALISTS];

    /**
     * The signaling rate of each kept candidate: mode, vectors, reference and filter.
     */
    private final int[] finSig = new int[FINALISTS];

    /**
     * The estimated cost of each kept candidate.
     */
    private final long[] finRd = new long[FINALISTS];

    /**
     * Whether each kept candidate skips its residual by the estimate.
     */
    private final boolean[] finSkip = new boolean[FINALISTS];

    /**
     * Returns whether a kept candidate skips its residual by the estimate.
     *
     * @param j the candidate
     * @return {@code true} when it skips
     */
    boolean finalistSkip(int j) {
        return finSkip[j];
    }

    /**
     * The rate of the evaluated mode.
     */
    private int thisRate;

    /**
     * The distortion of the evaluated mode.
     */
    private long thisDist;

    /**
     * The sum of squared errors of the evaluated mode.
     */
    private long thisSse;

    /**
     * Whether the evaluated mode skips its residual.
     */
    private boolean thisSkip;

    /**
     * The transform size of the evaluated mode.
     */
    private int thisTx;

    /**
     * The number of luma palette colors of the decision, 0 when the block predicts without a
     * palette.
     */
    int paletteSize;

    /**
     * The luma palette colors of the decision, strictly ascending.
     */
    final short[] paletteColors = new short[8];

    /**
     * The luma color index of each visible sample of the decision, rows at the block width.
     */
    final byte[] paletteMap = new byte[64 * 64];

    /**
     * The color indices of the palette being evaluated, rows at the block width.
     */
    private final byte[] paletteTrial = new byte[64 * 64];

    /**
     * The colors of the palette being evaluated.
     */
    private final short[] paletteTrialColors = new short[8];

    /**
     * The count of each sample value in the block, zero between searches; allocated at the first
     * palette search.
     */
    private int[] histogram;

    /**
     * The distinct sample values of the block, ascending after counting.
     */
    private final int[] distinct = new int[MAX_PALETTE_COLORS];

    /**
     * The count of each distinct value.
     */
    private final long[] weights = new long[MAX_PALETTE_COLORS];

    /**
     * The rounded, distinct cluster centers of the last k-means, ascending.
     */
    private final int[] centroids = new int[8];

    /**
     * The cluster means of the k-means iterations.
     */
    private final double[] means = new double[8];

    /**
     * The weighted value sums of the k-means clusters.
     */
    private final double[] clusterSums = new double[8];

    /**
     * The weights of the k-means clusters.
     */
    private final long[] clusterWeights = new long[8];

    /**
     * The coder that counts the cost of the palette syntax.
     */
    private final RangeEncoder paletteCounter = new RangeEncoder();

    /**
     * The palette syntax writer the costs are counted with.
     */
    private final PaletteWriter paletteWriter = new PaletteWriter();

    /**
     * The most distinct values a block may hold for its palette to be searched
     * ({@code color_palette_thresh} at its largest).
     */
    private static final int MAX_PALETTE_COLORS = 64;

    /**
     * The k-means iterations of a palette size.
     */
    private static final int KMEANS_ITERATIONS = 10;

    /**
     * Creates the mode decision of a tile.
     *
     * @param frame the frame encoder
     * @param tile the tile encoder
     * @param dsp the kernels
     * @param search the motion search
     * @param part the superblock partitioning
     */
    PickMode(FrameEncoder frame, TileEncoder tile, EncDsp dsp, MotionSearch search, VarPartition part) {
        this.frame = frame;
        this.tile = tile;
        this.dsp = dsp;
        this.search = search;
        this.part = part;
        for (var i = 0; i < MAX_REFS; i++) {
            cand[i] = new RefMvs.Candidates();
        }
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
     * Returns a 64x64 block of mid-gray samples.
     *
     * @param bitDepth the bit depth of the samples
     * @return the block
     */
    private static short[] flat(int bitDepth) {
        var out = new short[64 * 64];
        Arrays.fill(out, (short) (1 << (bitDepth - 1)));
        return out;
    }

    /**
     * Forgets the cost of the last decision, so {@link #bestCost} reports none until the next
     * {@link #decide} costs a mode.
     */
    void resetCost() {
        bestRate = Integer.MAX_VALUE;
        bestDist = Long.MAX_VALUE;
    }

    /**
     * Returns the estimated rate-distortion cost of the mode {@link #decide} chose last.
     *
     * @return the cost, or {@link Long#MAX_VALUE} when no mode was costed
     */
    long bestCost() {
        if (bestRate >= Integer.MAX_VALUE || bestDist == Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        return RdCost.rdcost(rdmult, (int) bestRate, bestDist);
    }

    /**
     * Returns the luma prediction of the chosen inter mode, of stride the block width; valid when
     * {@link #predictionReusable}.
     *
     * @return the prediction
     */
    short[] bestPrediction() {
        return bestPred;
    }

    /**
     * Returns the size group of a block size ({@code size_group_lookup}).
     *
     * @param bs the block size
     * @return the size group, 0 to 3
     */
    private static int sizeGroup(int bs) {
        return Tables.YMODE_SIZE_CONTEXT[bs];
    }

    /**
     * Returns the per-sample variance of the source block against a flat 128
     * ({@code av1_get_perpixel_variance}).
     *
     * @param px the block column in samples
     * @param py the block row in samples
     * @param bw the block width
     * @param bh the block height
     * @return the variance
     */
    private int perPixelVariance(int px, int py, int bw, int bh) {
        var src = frame.source.data[0];
        var stride = frame.source.stride[0];
        var packed = dsp.sseSum(src, py * stride + px, stride, FLAT[(frame.bitDepth - 8) >> 1], 0, 64, bw, bh);
        var log2n = Integer.numberOfTrailingZeros(bw) + Integer.numberOfTrailingZeros(bh);
        return (int) ((EncDsp.variance(packed, log2n) & 0xFFFFFFFFL) >> log2n);
    }

    /**
     * Estimates the luma rate and distortion of a block's residual with Hadamard transforms
     * ({@code av1_block_yrd}); the result is left in {@link #thisRate}, {@link #thisDist} and
     * {@link #thisSkip}, and {@link #thisSse} is scaled by 16.
     *
     * @param src the source plane
     * @param srcOff the offset of the block in the source
     * @param srcStride the source stride
     * @param pred the prediction
     * @param predOff the offset of the block in the prediction
     * @param predStride the prediction stride
     * @param bw the block width
     * @param bh the block height
     * @param visW the visible width of the block
     * @param visH the visible height of the block
     * @param tx the transform size: 1 8x8 or 2 16x16
     */
    private void blockYrd(short[] src, int srcOff, int srcStride, short[] pred, int predOff, int predStride, int bw,
                          int bh, int visW, int visH, int tx) {
        dsp.subtract(src, srcOff, srcStride, pred, predOff, predStride, diff, bw, bh);
        var q = frame.quantizer(tile.t.b.segId, 0);
        var n = tx == 2 ? 256 : 64;
        var size = tx == 2 ? 16 : 8;
        var scan = tx == 2 ? SCAN_16X16_T : SCAN_8X8_T;
        var iscan = tx == 2 ? ISCAN_16X16_T : ISCAN_8X8_T;
        var skippable = true;
        long rate = 0;
        long dist = 0;
        var eobCost = 0;
        for (var r = 0; r < visH; r += size) {
            for (var c = 0; c < visW; c += size) {
                if (tx == 2) {
                    dsp.hadamard16x16(diff, r * bw + c, bw, coeff, 0, hadScratch);
                } else {
                    dsp.hadamard8x8(diff, r * bw + c, bw, coeff, 0, hadScratch);
                }
                var eob = dsp.quantizeLpRd(coeff, n, q.roundFp, q.quantFp, q.dequant, scan, iscan, qcoeff,
                        dqcoeff, rdResult);
                skippable &= eob == 0;
                eobCost += 31 - Integer.numberOfLeadingZeros(eob + 1);
                rate += rdResult[0];
                dist += rdResult[1] >> 2;
            }
        }
        thisSkip = skippable;
        thisSse = (thisSse << 6) >> 2;
        if (skippable) {
            thisDist = thisSse;
            thisRate = 0;
            return;
        }
        thisDist = dist;
        thisRate = (int) Math.min(Integer.MAX_VALUE, (rate << (2 + RdCost.PROB_COST_SHIFT))
                + ((long) eobCost << RdCost.PROB_COST_SHIFT));
    }

    /**
     * Chooses the transform size of an inter block from its prediction error
     * ({@code calculate_tx_size}).
     *
     * @param bs the block size
     * @param var the variance of the prediction error
     * @param sse the sum of squared errors
     * @param boosted whether the block's segment is boosted by the cyclic refresh
     * @return the transform size, 8x8 or 16x16, and bit 8 set to force skipping
     */
    private int txSize(int bs, long var, long sse, boolean boosted) {
        var sf = frame.speed;
        var q = frame.quantizer(tile.t.b.segId, 0);
        var multiplier = 8;
        long varThresh = 0;
        var isHighVar = true;
        var forceSkip = false;
        if (sf.txSizeLevelBasedOnQstep != 0) {
            var qband = frame.baseQindex >> 6;
            multiplier = TX_MULTIPLIER[qband];
            var qstep = q.analysisDequant[1] >> 3;
            long qstepSq = (long) qstep * qstep;
            varThresh = qstepSq * 2;
            if (sf.txSizeLevelBasedOnQstep >= 2) {
                if (sse < qstepSq && sourceVariance < qstepSq && colorSens[0] == 0 && colorSens[1] == 0) {
                    forceSkip = true;
                }
                isHighVar = var >= varThresh;
            }
        }
        int tx;
        if (sse > ((var * multiplier) >> 2) || var < varThresh) {
            tx = MAX_SQUARE_TX[bs];
        } else {
            tx = 1;
        }
        if (boosted && isHighVar) {
            tx = 1;
        } else if (tx > 2) {
            tx = 2;
        }
        var bw4 = BLOCK_DIMENSIONS[bs * 4];
        var bh4 = BLOCK_DIMENSIONS[bs * 4 + 1];
        if (bw4 * bh4 > 64) {
            tx = 2;
        }
        tx = Math.max(1, Math.min(tx, Math.min(MAX_SQUARE_TX[bs], 2)));
        return tx | (forceSkip ? 256 : 0);
    }

    /**
     * Decides the mode of the block at the tile's current position.
     *
     * @param bs the block size
     * @param edgeFlags the block's intra edge flags
     */
    void decide(int bs, int edgeFlags) {
        var t = tile.t;
        var b = t.b;
        var f = frame;
        var bw4 = BLOCK_DIMENSIONS[bs * 4];
        var bh4 = BLOCK_DIMENSIONS[bs * 4 + 1];
        var px = t.bx * 4;
        var py = t.by * 4;
        var bw = bw4 * 4;
        var bh = bh4 * 4;
        b.uvtx = Tables.MAX_TXFM_SIZE_FOR_BS[bs * 4 + frame.layout];
        b.maxYtx = Tables.MAX_TXFM_SIZE_FOR_BS[bs * 4];
        b.yAngle = 0;
        b.uvAngle = 0;
        skipTxfm = false;
        lumaTxType = 0;
        paletteSize = 0;
        rdmult = f.rd.segmentRdmult[b.segId];
        sourceVariance = perPixelVariance(px, py, bw, bh);
        if (f.f.frameHdr.isKeyOrIntra()) {
            pickIntraKey(bs, edgeFlags, bw, bh, px, py);
            return;
        }
        pickInter(bs, edgeFlags, bw, bh, px, py);
    }

    /**
     * Returns the transform size of a given square size inside a block, following the block's
     * maximum transform split chain.
     *
     * @param bs the block size
     * @param square the square transform size, 1 8x8 to 3 32x32
     * @return the transform size
     */
    private static int txOnChain(int bs, int square) {
        var tx = Tables.MAX_TXFM_SIZE_FOR_BS[bs * 4];
        for (var depth = 0; depth < 2; depth++) {
            if (Math.max(Tables.TX_LW[tx], Tables.TX_LH[tx]) <= square) {
                return tx;
            }
            tx = Tables.TX_SUB[tx];
        }
        return tx;
    }

    /**
     * Predicts an intra mode over a block into the reconstruction picture, transform block by
     * transform block in raster order, as libaom's estimate does.
     *
     * @param mode the intra mode
     * @param tx the transform size of the prediction units
     * @param bs the block size
     */
    private void predictIntraLuma(int mode, int tx, int bs) {
        var t = tile.t;
        var ts = tile.ts;
        var f = frame.f;
        var cur = f.cur;
        var stride = cur.stride[0];
        var plane = cur.data[0];
        var txW = Tables.TX_W[tx];
        var txH = Tables.TX_H[tx];
        var bw4 = BLOCK_DIMENSIONS[bs * 4];
        var bh4 = BLOCK_DIMENSIONS[bs * 4 + 1];
        var w4 = Math.min(bw4, f.bw - t.bx);
        var h4 = Math.min(bh4, f.bh - t.by);
        var bx0 = t.bx;
        var by0 = t.by;
        for (var y = 0; y < h4; y += txH) {
            for (var x = 0; x < w4; x += txW) {
                var bx = bx0 + x;
                var by = by0 + y;
                var dst = 4 * (by * stride + bx);
                t.angle[0] = 0;
                var flags = (x + txW < w4 && y == 0 ? IntraEdge.I444_TOP_HAS_RIGHT : 0)
                        | (x == 0 && y + txH < h4 ? IntraEdge.I444_LEFT_HAS_BOTTOM : 0);
                var m = IpredDsp.prepareIntraEdges(bx, bx > ts.colStart, by, by > ts.rowStart, ts.colEnd, ts.rowEnd,
                        flags, plane, dst, stride, null, 0, mode, t.angle, txW, txH, f.seqHdr.intraEdgeFilter, t.edge,
                        IpredDsp.EDGE_TOPLEFT, frame.bdMax);
                IpredDsp.INSTANCE.intraPred(m, plane, dst, stride, t.edge, IpredDsp.EDGE_TOPLEFT, txW * 4, txH * 4,
                        t.angle[0], 4 * f.bw - 4 * bx, 4 * f.bh - 4 * by, t.ipredScratch, frame.bdMax);
            }
        }
    }

    /**
     * Chooses the intra mode of a key frame block ({@code av1_nonrd_pick_intra_mode}).
     *
     * @param bs the block size
     * @param edgeFlags the block's intra edge flags
     * @param bw the block width
     * @param bh the block height
     * @param px the block column
     * @param py the block row
     */
    private void pickIntraKey(int bs, int edgeFlags, int bw, int bh, int px, int py) {
        var t = tile.t;
        var b = t.b;
        var f = frame;
        var cur = f.f.cur;
        var stride = cur.stride[0];
        var src = f.source.data[0];
        var srcStride = f.source.stride[0];
        var visW = Math.min(bw, f.f.bw * 4 - px);
        var visH = Math.min(bh, f.f.bh * 4 - py);
        var square = Math.min(Integer.numberOfTrailingZeros(Math.min(bw, bh)) - 2, 3);
        var tx = f.f.frameHdr.txfmMode == FrameHeader.TX_SWITCHABLE ? txOnChain(bs, square)
                : Tables.MAX_TXFM_SIZE_FOR_BS[bs * 4];
        var yrdTx = Math.min(Math.max(Tables.TX_LW[tx], 1), 2);
        var aCtx = Tables.INTRA_MODE_CONTEXT[t.a.mode[t.bx & 31]];
        var lCtx = Tables.INTRA_MODE_CONTEXT[t.l.mode[t.by & 31]];
        var bestMode = IpredDsp.DC_PRED;
        var bestRd = Long.MAX_VALUE;
        var skipCtx = t.a.skip[t.bx & 31] + t.l.skip[t.by & 31];
        for (var mode : INTRA_MODES) {
            if (sourceVariance == 0 && px == 0 && py == 0 && bw >= 32 && bh >= 32 && mode != IpredDsp.DC_PRED) {
                continue;
            }
            predictIntraLuma(mode, tx, bs);
            thisSse = EncDsp.sse(dsp.sseSum(src, py * srcStride + px, srcStride, cur.data[0], py * stride + px, stride,
                    visW, visH)) & 0xFFFFFFFFL;
            blockYrd(src, py * srcStride + px, srcStride, cur.data[0], py * stride + px, stride, bw, bh, visW, visH,
                    yrdTx);
            var rate = thisSkip ? f.rd.skip[skipCtx * 2 + 1] : thisRate + f.rd.skip[skipCtx * 2];
            rate += f.rd.kfYMode[(aCtx * 5 + lCtx) * 13 + mode];
            var rd = RdCost.rdcost(rdmult, rate, thisDist);
            if (rd < bestRd) {
                bestRd = rd;
                bestMode = mode;
            }
        }
        if (f.f.frameHdr.allowScreenContentTools && bw >= 8 && bh >= 8 && bw <= 16 && bh <= 16
                && sourceVariance > 200) {
            searchPalette(bs, bw, bh, px, py, yrdTx, f.rd.kfYMode[(aCtx * 5 + lCtx) * 13 + IpredDsp.DC_PRED],
                    f.rd.skip[skipCtx * 2 + 1], f.rd.skip[skipCtx * 2], bestRd, MAX_PALETTE_COLORS);
            if (paletteSize > 0) {
                bestMode = IpredDsp.DC_PRED;
            }
        }
        if (paletteSize == 0 && bw == 8 && bh == 8 && sourceVariance >= f.speed.hybridIntraVarThresh) {
            if (intraRd == null) {
                intraRd = new IntraRd(frame, tile);
            }
            skipCosts[0] = f.rd.skip[skipCtx * 2];
            skipCosts[1] = f.rd.skip[skipCtx * 2 + 1];
            bestMode = intraRd.search(bs, edgeFlags, rdmult, skipCosts);
            lumaTxType = intraRd.txType;
        }
        b.intra = true;
        b.yMode = bestMode;
        b.uvMode = IpredDsp.DC_PRED;
        b.tx = tx;
    }

    /**
     * Returns the cost of a single-reference inter mode ({@code cost_mv_ref} with the drl cost).
     *
     * @param mode the inter mode
     * @param c the candidate list
     * @param drl the candidate index
     * @return the cost
     */
    private int interModeCost(int mode, RefMvs.Candidates c, int drl) {
        var rd = frame.rd;
        var ctx = c.ctx;
        var newCtx = ctx & 7;
        if (mode == ModeWriter.NEWMV) {
            var cost = rd.newmv[newCtx * 2];
            for (var idx = 0; idx < 2; idx++) {
                if (c.count > idx + 1) {
                    var dctx = ModeWriter.drlCtx(c, idx);
                    cost += rd.drl[dctx * 2 + (drl != idx ? 1 : 0)];
                    if (drl == idx) {
                        break;
                    }
                }
            }
            return cost;
        }
        var cost = rd.newmv[newCtx * 2 + 1];
        var gctx = (ctx >> 3) & 1;
        if (mode == ModeWriter.GLOBALMV) {
            return cost + rd.globalmv[gctx * 2];
        }
        cost += rd.globalmv[gctx * 2 + 1];
        var rctx = (ctx >> 4) & 15;
        cost += rd.refmv[rctx * 2 + (mode != ModeWriter.NEARESTMV ? 1 : 0)];
        if (mode == ModeWriter.NEARMV) {
            for (var idx = 1; idx < 3; idx++) {
                if (c.count > idx + 1) {
                    var dctx = ModeWriter.drlCtx(c, idx);
                    cost += rd.drl[dctx * 2 + (drl != idx ? 1 : 0)];
                    if (drl == idx) {
                        break;
                    }
                }
            }
        }
        return cost;
    }

    /**
     * Returns the cost of signaling an inter block's single reference, with the block's actual
     * contexts.
     *
     * @param ref the decoder reference index
     * @return the cost, including the inter flag
     */
    private int refCost(int ref) {
        return singleRefCost(ref) + (compFlagCoded ? compFlagCost(0) : 0) + skipModeCost(0);
    }

    /**
     * Returns the cost of the skip mode flag of the current block, 0 when the frame does not code
     * it.
     *
     * @param flag 1 for a skip mode block, else 0
     * @return the cost
     */
    private int skipModeCost(int flag) {
        if (!skipModeCoded) {
            return 0;
        }
        var t = tile.t;
        var ctx = t.a.skipMode[t.bx & 31] + t.l.skipMode[t.by & 31];
        return frame.rd.skipMode[ctx * 2 + flag];
    }

    /**
     * Evaluates skip mode for the current block, the averaged prediction from the frame's two skip
     * mode references at their nearest vectors with no residual, against the best mode so far,
     * and takes it when cheaper ({@code rd_pick_skip_mode}).
     *
     * @param bs the block size
     * @param edgeFlags the block's intra edge flags
     * @param px the block column
     * @param py the block row
     * @param bw the block width
     * @param bh the block height
     * @param visW the visible width
     * @param visH the visible height
     * @param bestRd the best cost so far
     * @return whether skip mode was taken
     */
    private boolean searchSkipMode(int bs, int edgeFlags, int px, int py, int bw, int bh, int visW, int visH,
                                   long bestRd) {
        var f = frame;
        var t = tile.t;
        var b = t.b;
        var hdr = f.f.frameHdr;
        var ref0 = hdr.skipModeRefs[0];
        var ref1 = hdr.skipModeRefs[1];
        if (!f.refEnabled(ref0) || !f.refEnabled(ref1)) {
            return false;
        }
        var c = skipCand;
        RefMvs.find(t.rt, c, ref0 + 1, ref1 + 1, bs, edgeFlags, t.by, t.bx);
        var mv0 = RefMvs.fixMvPrecision(hdr, c.mv0[0]);
        var mv1 = RefMvs.fixMvPrecision(hdr, c.mv1[0]);
        if (!mvUsable(mv0, px, py, bw, bh) || !mvUsable(mv1, px, py, bw, bh)) {
            return false;
        }
        curFilter = 0;
        predictLuma(f.ref(ref0), mv0, px, py, bw, bh, predY);
        predictLuma(f.ref(ref1), mv1, px, py, bw, bh, predComp);
        dsp.average(predY, 0, predComp, 0, bw * bh);
        var src = f.source.data[0];
        var srcStride = f.source.stride[0];
        long sse = EncDsp.sse(dsp.sseSum(src, py * srcStride + px, srcStride, predY, 0, bw, visW, visH)) & 0xFFFFFFFFL;
        if (f.planes > 1) {
            var ssHor = f.ssHor;
            var ssVer = f.ssVer;
            predictChroma(f.ref(ref1), mv1, px, py, bw, bh);
            var cw = bw >> ssHor;
            var ch = bh >> ssVer;
            System.arraycopy(predUv, 0, predUvComp, 0, cw * ch);
            System.arraycopy(predUv, CHROMA_PLANE, predUvComp, CHROMA_PLANE, cw * ch);
            predictChroma(f.ref(ref0), mv0, px, py, bw, bh);
            for (var pl = 1; pl < 3; pl++) {
                var o = (pl - 1) * CHROMA_PLANE;
                dsp.average(predUv, o, predUvComp, o, cw * ch);
                var s = f.source.data[pl];
                var stride = f.source.stride[1];
                sse += EncDsp.sse(dsp.sseSum(s, (py >> ssVer) * stride + (px >> ssHor), stride, predUv, o, cw,
                        Math.max(1, visW >> ssHor), Math.max(1, visH >> ssVer))) & 0xFFFFFFFFL;
            }
        }
        var rd = RdCost.rdcost(rdmult, skipModeCost(1), sse << 4);
        if (rd >= bestRd) {
            return false;
        }
        b.skipMode = true;
        b.ref[0] = ref0;
        b.ref[1] = ref1;
        b.interMode = ModeWriter.NEAREST_NEARESTMV;
        b.drlIdx = 0;
        b.mv[0] = mv0;
        b.mv[1] = mv1;
        b.compType = ModeWriter.COMP_INTER_AVG;
        filter = 0;
        b.filter2d = Tables.FILTER_2D[0];
        b.tx = Tables.MAX_TXFM_SIZE_FOR_BS[bs * 4];
        skipTxfm = true;
        predictionReusable = false;
        bestRate = skipModeCost(1);
        bestDist = sse << 4;
        tile.chosenCandidates(c);
        return true;
    }

    /**
     * Returns the cost of the compound flag of the current block.
     *
     * @param comp 1 for a compound block, else 0
     * @return the cost
     */
    private int compFlagCost(int comp) {
        var t = tile.t;
        var ctx = EncoderBridge.compContext(t, t.by & 31, t.bx & 31, t.by > tile.ts.rowStart, t.bx > tile.ts.colStart);
        return frame.rd.compFlag[ctx * 2 + comp];
    }

    /**
     * Returns the cost of signaling an inter block's references as a bidirectional compound pair,
     * with the inter and compound flags.
     *
     * @param ref0 the forward reference, 0 to 3
     * @param ref1 the backward reference, 4 to 6
     * @return the cost
     */
    private int compoundRefCost(int ref0, int ref1) {
        var t = tile.t;
        var rd = frame.rd;
        var bx4 = t.bx & 31;
        var by4 = t.by & 31;
        var haveLeft = t.bx > tile.ts.colStart;
        var haveTop = t.by > tile.ts.rowStart;
        var cost = rd.intraInter[ModeWriter.intraCtx(t.a, t.l, by4, bx4, haveTop, haveLeft) * 2 + 1];
        cost += compFlagCost(1) + skipModeCost(0);
        cost += rd.compDir[EncoderBridge.compDirContext(t, by4, bx4, haveTop, haveLeft) * 2 + 1];
        var ctx1 = ModeWriter.refCtx(t.a, t.l, by4, bx4, haveTop, haveLeft, 0, 1, 2, 3);
        var high = ref0 >= 2 ? 1 : 0;
        cost += rd.compFwdRef[ctx1 * 2 + high];
        if (high != 0) {
            var ctx2 = ModeWriter.refCtx(t.a, t.l, by4, bx4, haveTop, haveLeft, 2, 2, 3, 3);
            cost += rd.compFwdRef[(6 + ctx2) * 2 + ref0 - 2];
        } else {
            var ctx2 = ModeWriter.refCtx(t.a, t.l, by4, bx4, haveTop, haveLeft, 0, 0, 1, 1);
            cost += rd.compFwdRef[(3 + ctx2) * 2 + ref0];
        }
        var ctx3 = ModeWriter.refCtx(t.a, t.l, by4, bx4, haveTop, haveLeft, 4, 5, 6, 6);
        cost += rd.compBwdRef[ctx3 * 2 + (ref1 == 6 ? 1 : 0)];
        if (ref1 != 6) {
            var ctx4 = ModeWriter.refCtx(t.a, t.l, by4, bx4, haveTop, haveLeft, 4, 4, 5, 5);
            cost += rd.compBwdRef[(3 + ctx4) * 2 + ref1 - 4];
        }
        return cost;
    }

    /**
     * Returns the cost of a compound mode, its reference index and its new vectors.
     *
     * @param mode the compound mode
     * @param c the pair's candidate list
     * @param drl the candidate index
     * @param mv0 the first reference's vector
     * @param mv1 the second reference's vector
     * @return the cost
     */
    private int compoundModeCost(int mode, RefMvs.Candidates c, int drl, int mv0, int mv1) {
        var rd = frame.rd;
        var cost = rd.compMode[c.ctx * 8 + mode];
        if (mode == ModeWriter.NEW_NEWMV) {
            for (var idx = 0; idx < 2; idx++) {
                if (c.count > idx + 1) {
                    cost += rd.drl[ModeWriter.drlCtx(c, idx) * 2 + (drl != idx ? 1 : 0)];
                    if (drl == idx) {
                        break;
                    }
                }
            }
        } else if (ModeWriter.compoundNear(mode)) {
            for (var idx = 1; idx < 3; idx++) {
                if (c.count > idx + 1) {
                    cost += rd.drl[ModeWriter.drlCtx(c, idx) * 2 + (drl != idx ? 1 : 0)];
                    if (drl == idx) {
                        break;
                    }
                }
            }
        }
        if (ModeWriter.compoundNew(mode, 0)) {
            var pred = c.mv0[drl];
            cost += rd.mvBitCost(RefMvs.mvY(mv0), RefMvs.mvX(mv0), RefMvs.mvY(pred), RefMvs.mvX(pred),
                    RdCost.MV_COST_WEIGHT);
        }
        if (ModeWriter.compoundNew(mode, 1)) {
            var pred = c.mv1[drl];
            cost += rd.mvBitCost(RefMvs.mvY(mv1), RefMvs.mvX(mv1), RefMvs.mvY(pred), RefMvs.mvX(pred),
                    RdCost.MV_COST_WEIGHT);
        }
        return cost;
    }

    /**
     * Keeps a single-reference candidate when it is among the cheapest by the mode decision's
     * estimate.
     *
     * @param r the index of the candidate's reference among the searched references
     * @param mode the inter mode
     * @param mv the motion vector
     * @param filt the interpolation filter
     * @param tx the estimated transform size
     * @param sig the rate of signaling the mode, vectors, reference and filter
     * @param rd the estimated cost
     * @param skip whether the candidate skips its residual
     */
    private void offerFinalist(int r, int mode, int mv, int filt, int tx, int sig, long rd, boolean skip) {
        var limit = Math.min(frame.speed.finalists, FINALISTS);
        for (var i = 0; i < finalistCount; i++) {
            if (finRef[i] == r && finMode[i] == mode && finMv[i] == mv && finFilter[i] == filt) {
                return;
            }
        }
        var pos = finalistCount;
        while (pos > 0 && finRd[pos - 1] > rd) {
            pos--;
        }
        if (pos >= limit) {
            return;
        }
        var end = Math.min(finalistCount, limit - 1);
        for (var i = end; i > pos; i--) {
            finRef[i] = finRef[i - 1];
            finMode[i] = finMode[i - 1];
            finMv[i] = finMv[i - 1];
            finFilter[i] = finFilter[i - 1];
            finTx[i] = finTx[i - 1];
            finSig[i] = finSig[i - 1];
            finRd[i] = finRd[i - 1];
            finSkip[i] = finSkip[i - 1];
        }
        finSkip[pos] = skip;
        finRef[pos] = r;
        finMode[pos] = mode;
        finMv[pos] = mv;
        finFilter[pos] = filt;
        finTx[pos] = tx;
        finSig[pos] = sig;
        finRd[pos] = rd;
        finalistCount = Math.min(finalistCount + 1, limit);
    }

    /**
     * Returns the estimated cost of a kept candidate.
     *
     * @param j the candidate
     * @return the cost
     */
    long finalistRd(int j) {
        return finRd[j];
    }

    /**
     * Returns the rate of signaling the current block's interpolation filter.
     *
     * @return the rate
     */
    int currentFilterRate() {
        return filterCost(tile.t.b.ref[0], filter);
    }

    /**
     * Returns the signaling rate of a kept candidate.
     *
     * @param j the candidate
     * @return the rate
     */
    int finalistRate(int j) {
        return finSig[j];
    }

    /**
     * Makes a kept candidate the current block's mode.
     *
     * @param j the candidate
     * @param bs the block size
     */
    void applyFinalist(int j, int bs) {
        var b = tile.t.b;
        var r = finRef[j];
        b.intra = false;
        b.ref[0] = refs[r];
        b.ref[1] = -1;
        b.interMode = finMode[j];
        b.drlIdx = finMode[j] == ModeWriter.NEARMV ? 1 : 0;
        b.mv[0] = finMv[j];
        var hdr = frame.f.frameHdr;
        filter = hdr.subpelFilterMode == FrameHeader.FILTER_SWITCHABLE ? finFilter[j] : hdr.subpelFilterMode;
        b.filter2d = Tables.FILTER_2D[filter * 4 + filter];
        b.tx = txOnChain(bs, finTx[j]);
        predictionReusable = false;
        tile.chosenCandidates(cand[r]);
        lastBestRef = r;
    }

    /**
     * Returns the position of a reference in the frame's searchable list when it is searched for
     * the current block.
     *
     * @param ref the decoder reference index
     * @return the position, or -1
     */
    private int searched(int ref) {
        for (var r = 0; r < nRefs; r++) {
            if (refs[r] == ref) {
                return useRef[r] && frame.refEnabled(ref) ? r : -1;
            }
        }
        return -1;
    }

    /**
     * Evaluates the averaged bidirectional compound modes of the current block against the best
     * single-reference mode, and takes the best compound mode when it is cheaper: the nearest
     * vectors, the single-reference searches' new vectors and global motion of the nearest past
     * reference with {@code BWDREF} and with {@code ALTREF}, and at the slower levels also the
     * near vectors, mixed nearest and new vectors and the other pairs.
     *
     * @param bs the block size
     * @param edgeFlags the block's intra edge flags
     * @param px the block column
     * @param py the block row
     * @param bw the block width
     * @param bh the block height
     * @param visW the visible width
     * @param visH the visible height
     * @param bestRd the best cost so far
     * @param switchable whether the frame codes the interpolation filter per block
     * @param skipCost the cost of a skipped residual
     * @param noSkipCost the cost of a coded residual
     * @return the best cost after the compound modes
     */
    private long searchCompound(int bs, int edgeFlags, int px, int py, int bw, int bh, int visW, int visH,
                                long bestRd, boolean switchable, int skipCost, int noSkipCost) {
        var f = frame;
        var t = tile.t;
        var b = t.b;
        var hdr = f.f.frameHdr;
        var level = f.speed.compound;
        var forward = level >= 3 ? COMPOUND_FORWARD_ALL : COMPOUND_FORWARD_LAST;
        var backward = level >= 3 ? COMPOUND_BACKWARD_ALL : COMPOUND_BACKWARD_NEAR;
        var modes = level >= 2 ? COMPOUND_MODES_ALL : COMPOUND_MODES_BASIC;
        var src = f.source.data[0];
        var srcStride = f.source.stride[0];
        var srcOff = py * srcStride + px;
        var log2 = Integer.numberOfTrailingZeros(visW) + Integer.numberOfTrailingZeros(visH);
        var pairs = 0;
        var bestPair = -1;
        var bestMode = 0;
        var bestDrl = 0;
        var bestMv0 = 0;
        var bestMv1 = 0;
        var bestRef0 = 0;
        var bestRef1 = 0;
        var bestTxLocal = 1;
        var bestSkipLocal = false;
        for (var ref0 : forward) {
            var r0 = searched(ref0);
            if (r0 < 0) {
                continue;
            }
            for (var ref1 : backward) {
                var r1 = searched(ref1);
                if (r1 < 0 || pairs >= compCand.length || f.ref(ref0) == f.ref(ref1)) {
                    continue;
                }
                var c = compCand[pairs];
                RefMvs.find(t.rt, c, ref0 + 1, ref1 + 1, bs, edgeFlags, t.by, t.bx);
                var refCost = compoundRefCost(ref0, ref1);
                var new0 = frameMv[ModeWriter.NEWMV * MAX_REFS + r0];
                var new1 = frameMv[ModeWriter.NEWMV * MAX_REFS + r1];
                for (var mode : modes) {
                    var drl = ModeWriter.compoundNear(mode) ? 1 : 0;
                    if (drl > 0 && c.count < 2) {
                        continue;
                    }
                    int mv0;
                    int mv1;
                    if (mode == ModeWriter.GLOBAL_GLOBALMV) {
                        mv0 = 0;
                        mv1 = 0;
                    } else {
                        mv0 = ModeWriter.compoundNew(mode, 0) ? new0 : RefMvs.fixMvPrecision(hdr, c.mv0[drl]);
                        mv1 = ModeWriter.compoundNew(mode, 1) ? new1 : RefMvs.fixMvPrecision(hdr, c.mv1[drl]);
                    }
                    if (mv0 == RefMvs.INVALID_MV || mv1 == RefMvs.INVALID_MV || !mvUsable(mv0, px, py, bw, bh)
                            || !mvUsable(mv1, px, py, bw, bh)) {
                        continue;
                    }
                    curFilter = 0;
                    predictLuma(f.ref(ref0), mv0, px, py, bw, bh, predY);
                    predictLuma(f.ref(ref1), mv1, px, py, bw, bh, predComp);
                    dsp.average(predY, 0, predComp, 0, bw * bh);
                    var packed = dsp.sseSum(src, srcOff, srcStride, predY, 0, bw, visW, visH);
                    long sse = EncDsp.sse(packed) & 0xFFFFFFFFL;
                    long var = EncDsp.variance(packed, log2) & 0xFFFFFFFFL;
                    var txs = txSize(bs, var, sse, f.segmentBoosted(b.segId));
                    var tx = txs & 0xFF;
                    thisSse = sse;
                    blockYrd(src, srcOff, srcStride, predY, 0, bw, bw, bh, visW, visH, tx);
                    if (thisSkip || RdCost.rdcost(rdmult, thisRate, thisDist) >= RdCost.rdcost(rdmult, 0, thisSse)) {
                        thisRate = skipCost;
                        thisSkip = true;
                        thisDist = thisSse;
                    } else {
                        thisRate += noSkipCost;
                    }
                    if (colorSens[0] != 0 || colorSens[1] != 0) {
                        predictChroma(f.ref(ref1), mv1, px, py, bw, bh);
                        var chromaSamples = (bw >> f.ssHor) * (bh >> f.ssVer);
                        System.arraycopy(predUv, 0, predUvComp, 0, chromaSamples);
                        System.arraycopy(predUv, CHROMA_PLANE, predUvComp, CHROMA_PLANE, chromaSamples);
                        predictChroma(f.ref(ref0), mv0, px, py, bw, bh);
                        dsp.average(predUv, 0, predUvComp, 0, chromaSamples);
                        dsp.average(predUv, CHROMA_PLANE, predUvComp, CHROMA_PLANE, chromaSamples);
                        var uvSse = modelUv(px, py, bw, bh, model);
                        var uvSkip = uvSse < 0;
                        thisRate += (int) model[0];
                        thisDist += model[1];
                        thisSkip = thisSkip && uvSkip;
                    }
                    var rate = thisRate + refCost + compoundModeCost(mode, c, drl, mv0, mv1);
                    if (switchable && mode != ModeWriter.GLOBAL_GLOBALMV) {
                        var fctx = EncoderBridge.filterContext(t, 1, ref0, t.by & 31, t.bx & 31);
                        rate += f.rd.interpFilter[fctx * 3];
                    }
                    var rd = RdCost.rdcost(rdmult, rate, thisDist);
                    if (rd < bestRd) {
                        bestRd = rd;
                        bestRate = rate;
                        bestDist = thisDist;
                        bestPair = pairs;
                        bestMode = mode;
                        bestDrl = drl;
                        bestMv0 = mv0;
                        bestMv1 = mv1;
                        bestRef0 = ref0;
                        bestRef1 = ref1;
                        bestTxLocal = tx;
                        bestSkipLocal = thisSkip;
                    }
                }
                pairs++;
            }
        }
        if (bestPair >= 0) {
            b.ref[0] = bestRef0;
            b.ref[1] = bestRef1;
            b.interMode = bestMode;
            b.drlIdx = bestDrl;
            b.mv[0] = bestMv0;
            b.mv[1] = bestMv1;
            b.compType = ModeWriter.COMP_INTER_AVG;
            filter = 0;
            b.filter2d = Tables.FILTER_2D[0];
            b.tx = txOnChain(bs, bestTxLocal);
            skipTxfm = bestSkipLocal;
            predictionReusable = false;
            tile.chosenCandidates(compCand[bestPair]);
        }
        return bestRd;
    }

    /**
     * Returns the cost of signaling an inter block's single reference, with the block's actual
     * contexts, without the compound flag.
     *
     * @param ref the decoder reference index
     * @return the cost, including the inter flag
     */
    private int singleRefCost(int ref) {
        if (ref == 6) {
            return altrefCost();
        }
        if (ref >= 4) {
            return backwardRefCost(ref);
        }
        var t = tile.t;
        var rd = frame.rd;
        var bx4 = t.bx & 31;
        var by4 = t.by & 31;
        var haveLeft = t.bx > tile.ts.colStart;
        var haveTop = t.by > tile.ts.rowStart;
        var ictx = ModeWriter.intraCtx(t.a, t.l, by4, bx4, haveTop, haveLeft);
        var cost = rd.intraInter[ictx * 2 + 1];
        var ctx1 = ModeWriter.refCtx(t.a, t.l, by4, bx4, haveTop, haveLeft, 0, 3, 4, 6);
        cost += rd.singleRef[ctx1 * 2];
        var ctx2 = ModeWriter.refCtx(t.a, t.l, by4, bx4, haveTop, haveLeft, 0, 1, 2, 3);
        var high = ref >= 2 ? 1 : 0;
        cost += rd.singleRef[(6 + ctx2) * 2 + high];
        if (high != 0) {
            var ctx3 = ModeWriter.refCtx(t.a, t.l, by4, bx4, haveTop, haveLeft, 2, 2, 3, 3);
            cost += rd.singleRef[(12 + ctx3) * 2 + (ref - 2)];
        } else {
            var ctx3 = ModeWriter.refCtx(t.a, t.l, by4, bx4, haveTop, haveLeft, 0, 0, 1, 1);
            cost += rd.singleRef[(9 + ctx3) * 2 + ref];
        }
        return cost;
    }

    /**
     * Returns the cost of the {@code BWDREF} or {@code ALTREF2} reference with the block's
     * contexts.
     *
     * @param ref the decoder reference index, 4 or 5
     * @return the cost, including the inter flag
     */
    private int backwardRefCost(int ref) {
        var t = tile.t;
        var rd = frame.rd;
        var bx4 = t.bx & 31;
        var by4 = t.by & 31;
        var haveLeft = t.bx > tile.ts.colStart;
        var haveTop = t.by > tile.ts.rowStart;
        var ictx = ModeWriter.intraCtx(t.a, t.l, by4, bx4, haveTop, haveLeft);
        var cost = rd.intraInter[ictx * 2 + 1];
        var ctx1 = ModeWriter.refCtx(t.a, t.l, by4, bx4, haveTop, haveLeft, 0, 3, 4, 6);
        cost += rd.singleRef[ctx1 * 2 + 1];
        var ctx2 = ModeWriter.refCtx(t.a, t.l, by4, bx4, haveTop, haveLeft, 4, 5, 6, 6);
        cost += rd.singleRef[(3 + ctx2) * 2];
        var ctx3 = ModeWriter.refCtx(t.a, t.l, by4, bx4, haveTop, haveLeft, 4, 4, 5, 5);
        cost += rd.singleRef[(15 + ctx3) * 2 + (ref - 4)];
        return cost;
    }

    /**
     * Returns the threshold above which a reference's best predicted-vector SAD skips it in a
     * good-quality frame: twice the smallest such SAD of any searched reference, since in a
     * pyramid the closest past frame is not always the best predictor.
     *
     * @param threshold the real-time threshold from the {@code LAST} reference
     * @return the threshold
     */
    private long goodRefPrune(long threshold) {
        var best = Integer.MAX_VALUE;
        for (var r = 0; r < nRefs; r++) {
            if (useRef[r]) {
                best = Math.min(best, predMvSad[r]);
            }
        }
        return best == Integer.MAX_VALUE ? threshold : ((long) best << 1) + (best >> 2);
    }

    /**
     * Returns the cost of the {@code ALTREF} reference with the block's contexts.
     *
     * @return the cost, including the inter flag
     */
    private int altrefCost() {
        var t = tile.t;
        var rd = frame.rd;
        var bx4 = t.bx & 31;
        var by4 = t.by & 31;
        var haveLeft = t.bx > tile.ts.colStart;
        var haveTop = t.by > tile.ts.rowStart;
        var ictx = ModeWriter.intraCtx(t.a, t.l, by4, bx4, haveTop, haveLeft);
        var cost = rd.intraInter[ictx * 2 + 1];
        var ctx1 = ModeWriter.refCtx(t.a, t.l, by4, bx4, haveTop, haveLeft, 0, 3, 4, 6);
        cost += rd.singleRef[ctx1 * 2 + 1];
        var ctx2 = ModeWriter.refCtx(t.a, t.l, by4, bx4, haveTop, haveLeft, 4, 5, 6, 6);
        cost += rd.singleRef[(3 + ctx2) * 2 + 1];
        return cost;
    }

    /**
     * Builds the luma inter prediction of a block from a padded reference.
     *
     * @param ref the reference
     * @param mv the packed motion vector
     * @param px the block column
     * @param py the block row
     * @param bw the block width
     * @param bh the block height
     * @param dst the destination, of stride {@code bw}
     */
    private void predictLuma(RefFrame ref, int mv, int px, int py, int bw, int bh, short[] dst) {
        var mvx = RefMvs.mvX(mv);
        var mvy = RefMvs.mvY(mv);
        var s = ref.stride[0];
        var off = ref.origin[0] + (py + (mvy >> 3)) * s + px + (mvx >> 3);
        McDsp.select().mc(Tables.FILTER_2D[curFilter * 4 + curFilter], dst, 0, bw, ref.padded[0], off, s, bw, bh,
                (mvx & 7) << 1, (mvy & 7) << 1, mcScratch, frame.bdMax);
    }

    /**
     * Returns whether and how the block searches the interpolation filter
     * ({@code is_filter_search_enabled_blk}), setting {@link #filtSelect}.
     *
     * @param bs the block size
     * @param cb the checkerboard prediction level of the block, 0 to 2
     * @return 0 no search, 1 a search depending on the mode, 2 a forced search
     */
    private int filterSearchLevel(int bs, int cb) {
        filtSelect = 0;
        if (cb == 0) {
            return 1;
        }
        var t = tile.t;
        var bx4 = t.bx & 31;
        var by4 = t.by & 31;
        if (t.bx <= tile.ts.colStart || t.by <= tile.ts.rowStart) {
            return 2;
        }
        if (t.l.intra[by4] != 0 || t.a.intra[bx4] != 0) {
            return 2;
        }
        var left = t.l.filter[by4];
        if (left != t.a.filter[bx4] || (cb == 1 && left != 0)) {
            return 2;
        }
        if (left == 1) {
            filtSelect = 1;
        }
        if (frame.segmentBoosted(t.b.segId)) {
            return 1;
        }
        var bsl = BLOCK_DIMENSIONS[bs * 4 + 2];
        return ((((t.by + t.bx) >> bsl) + (frame.params().orderHint & 1)) & 1);
    }

    /**
     * Returns the cost of coding an interpolation filter for the current block
     * ({@code av1_get_switchable_rate}).
     *
     * @param ref the decoder reference index
     * @param filter the interpolation filter
     * @return the cost
     */
    private int filterCost(int ref, int filter) {
        var t = tile.t;
        var ctx = ModeWriter.filterCtx(t.a, t.l, ref, t.by & 31, t.bx & 31);
        return frame.rd.interpFilter[ctx * 3 + filter];
    }

    /**
     * Searches the regular and smooth interpolation filters of a sub-sample motion vector with
     * the curve-fit rate model ({@code search_filter_ref}), leaving the chosen filter in
     * {@link #curFilter} and its prediction in {@link #predY}.
     *
     * @param bs the block size
     * @param ref the reference
     * @param refIdx the decoder reference index
     * @param mv the motion vector
     * @param px the block column
     * @param py the block row
     * @param bw the block width
     * @param bh the block height
     * @param visW the visible width
     * @param visH the visible height
     */
    private void searchFilter(int bs, RefFrame ref, int refIdx, int mv, int px, int py, int bw, int bh, int visW,
                              int visH) {
        var f = frame;
        var src = f.source.data[0];
        var srcStride = f.source.stride[0];
        var srcOff = py * srcStride + px;
        var q = f.quantizer(tile.t.b.segId, 0);
        var log2 = Integer.numberOfTrailingZeros(visW) + Integer.numberOfTrailingZeros(visH);
        var bestCost = Long.MAX_VALUE;
        var best = 0;
        for (var fi = 0; fi < 2; fi++) {
            curFilter = fi;
            var dst = fi == 0 ? predY : predAlt;
            predictLuma(ref, mv, px, py, bw, bh, dst);
            var packed = dsp.sseSum(src, srcOff, srcStride, dst, 0, bw, visW, visH);
            long sse = EncDsp.sse(packed) & 0xFFFFFFFFL;
            long var = EncDsp.variance(packed, log2) & 0xFFFFFFFFL;
            var txs = txSize(bs, var, sse, f.segmentBoosted(tile.t.b.segId));
            long rate;
            long dist;
            if ((txs & 256) != 0) {
                rate = 0;
                dist = sse << 4;
            } else {
                RdCost.modelCurvfit(rdmult, bs, sse, visW * visH, q.analysisDequant[1], model);
                rate = model[0];
                dist = model[1];
            }
            rate += filterCost(refIdx, fi);
            var cost = RdCost.rdcost(rdmult, (int) Math.min(rate, Integer.MAX_VALUE), dist);
            if (cost < bestCost) {
                bestCost = cost;
                best = fi;
            }
        }
        curFilter = best;
        if (best == 1) {
            var tmp = predY;
            predY = predAlt;
            predAlt = tmp;
        }
    }

    /**
     * Builds the chroma inter predictions of a block from a padded reference.
     *
     * @param ref the reference
     * @param mv the packed motion vector
     * @param px the block column in luma samples
     * @param py the block row in luma samples
     * @param bw the block width in luma samples
     * @param bh the block height in luma samples
     */
    private void predictChroma(RefFrame ref, int mv, int px, int py, int bw, int bh) {
        var mvx = RefMvs.mvX(mv);
        var mvy = RefMvs.mvY(mv);
        var ssHor = frame.ssHor;
        var ssVer = frame.ssVer;
        var cw = bw >> ssHor;
        var ch = bh >> ssVer;
        var mx = (mvx & (15 >> (ssHor ^ 1))) << (ssHor ^ 1);
        var my = (mvy & (15 >> (ssVer ^ 1))) << (ssVer ^ 1);
        for (var pl = 1; pl < 3; pl++) {
            var s = ref.stride[pl];
            var off = ref.origin[pl] + ((py >> ssVer) + (mvy >> (3 + ssVer))) * s + (px >> ssHor)
                    + (mvx >> (3 + ssHor));
            McDsp.select().mc(Tables.FILTER_2D[curFilter * 4 + curFilter], predUv, (pl - 1) * CHROMA_PLANE, cw,
                    ref.padded[pl], off, s, cw, ch, mx, my, mcScratch, frame.bdMax);
        }
    }

    /**
     * Estimates the chroma rate and distortion of a prediction ({@code av1_model_rd_for_sb_uv})
     * for the color-sensitive planes.
     *
     * @param px the block column in luma samples
     * @param py the block row in luma samples
     * @param bw the block width in luma samples
     * @param bh the block height in luma samples
     * @param out receives the rate in {@code [0]}, the distortion in {@code [1]}
     * @return the sum of squared errors, with the skip decision in the sign: negative when the
     * chroma residual is skipped
     */
    private long modelUv(int px, int py, int bw, int bh, long[] out) {
        var f = frame;
        var cw = bw >> f.ssHor;
        var ch = bh >> f.ssVer;
        long rate = 0;
        long dist = 0;
        long totSse = 0;
        var log2n = Integer.numberOfTrailingZeros(cw) + Integer.numberOfTrailingZeros(ch);
        for (var pl = 1; pl < 3; pl++) {
            if (colorSens[pl - 1] == 0) {
                continue;
            }
            var src = f.source.data[pl];
            var stride = f.source.stride[1];
            var packed = dsp.sseSum(src, (py >> f.ssVer) * stride + (px >> f.ssHor), stride, predUv,
                    (pl - 1) * CHROMA_PLANE, cw, cw, ch);
            var sse = EncDsp.sse(packed) & 0xFFFFFFFFL;
            var var = EncDsp.variance(packed, log2n) & 0xFFFFFFFFL;
            totSse += sse;
            var q = f.quantizer(tile.t.b.segId, pl);
            RdCost.modelLapndz(sse - var, log2n, q.analysisDequant[0] >> 3, model);
            rate += model[0] >> 1;
            dist += model[1] << 3;
            RdCost.modelLapndz(var, log2n, q.analysisDequant[1] >> 3, model);
            rate += model[0];
            dist += model[1] << 4;
        }
        var skip = rate == 0;
        if (RdCost.rdcost(rdmult, (int) Math.min(rate, Integer.MAX_VALUE), dist) >= RdCost.rdcost(rdmult, 0, totSse << 4)) {
            rate = 0;
            dist = totSse << 4;
            skip = true;
        }
        out[0] = rate;
        out[1] = dist;
        return skip ? -1 - totSse : totSse;
    }

    /**
     * Refines the color sensitivity of a block below superblock size ({@code set_color_sensitivity}).
     *
     * @param ySad the luma SAD of the closest predicted vector
     * @param px the block column
     * @param py the block row
     * @param bw the block width
     * @param bh the block height
     */
    private void setColorSensitivity(int ySad, int px, int py, int bw, int bh) {
        var f = frame;
        var sad = part.sourceSadNonrd;
        var highRes = (long) f.width * f.height >= 640L * 360;
        if (bw == 64 && bh == 64) {
            for (var i = 0; i < 2; i++) {
                if (colorSens[i] == 2) {
                    colorSens[i] = sad >= VarPartition.MED_SAD ? 1 : 0;
                }
            }
            return;
        }
        var shift = 3;
        if (sad >= VarPartition.MED_SAD && sourceVariance > 0 && highRes) {
            shift = 4;
        }
        var log2 = Integer.numberOfTrailingZeros(bw) + Integer.numberOfTrailingZeros(bh) - 4;
        var normSad = ySad >> log2;
        if (sourceVariance > 1000 && normSad < 50) {
            colorSens[0] = 0;
            colorSens[1] = 0;
            return;
        }
        var last = f.ref(0);
        for (var pl = 1; pl < 3; pl++) {
            if (colorSens[pl - 1] == 2 || (colorSens[pl - 1] == 0 && sad >= VarPartition.MED_SAD && highRes)) {
                var src = f.source.data[pl];
                var stride = f.source.stride[1];
                var s = last.stride[pl];
                var ssHor = f.ssHor;
                var ssVer = f.ssVer;
                var uvSad = dsp.sad(src, (py >> ssVer) * stride + (px >> ssHor), stride, last.padded[pl],
                        last.origin[pl] + (py >> ssVer) * s + (px >> ssHor), s, bw >> ssHor, bh >> ssVer);
                var normUv = uvSad >> (log2 - ssHor - ssVer + 2);
                colorSens[pl - 1] = uvSad > (ySad >> shift) && normUv > 40 ? 1 : 0;
                if (sourceVariance < 50 && normUv > 100) {
                    colorSens[pl - 1] = 1;
                }
            }
        }
    }

    /**
     * Finds the predicted vectors of a reference and their SADs ({@code find_predictors} with
     * {@code av1_mv_pred}).
     *
     * @param r the searched reference index
     * @param bs the block size
     * @param edgeFlags the block's intra edge flags
     * @param px the block column
     * @param py the block row
     * @param bw the block width
     * @param bh the block height
     * @param doPredMv whether the SADs are computed
     */
    private void findPredictors(int r, int bs, int edgeFlags, int px, int py, int bw, int bh, boolean doPredMv) {
        var t = tile.t;
        var hdr = frame.f.frameHdr;
        var c = cand[r];
        RefMvs.find(t.rt, c, refs[r] + 1, -1, bs, edgeFlags, t.by, t.bx);
        frameMv[ModeWriter.NEARESTMV * MAX_REFS + r] = ModeWriter.refMv(hdr, c, 0);
        frameMv[ModeWriter.NEARMV * MAX_REFS + r] = ModeWriter.refMv(hdr, c, 1);
        frameMv[ModeWriter.GLOBALMV * MAX_REFS + r] = 0;
        frameMv[ModeWriter.NEWMV * MAX_REFS + r] = RefMvs.INVALID_MV;
        predMvSad[r] = Integer.MAX_VALUE;
        predMv0Sad[r] = Integer.MAX_VALUE;
        predMv1Sad[r] = Integer.MAX_VALUE;
        if (!doPredMv) {
            return;
        }
        var ref = frame.ref(refs[r]);
        var src = frame.source.data[0];
        var srcStride = frame.source.stride[0];
        var s = ref.stride[0];
        var zeroSeen = false;
        var best = Integer.MAX_VALUE;
        var mv0 = c.count > 0 ? c.mv0[0] : 0;
        var mv1 = c.count > 1 ? c.mv0[1] : 0;
        var n = mv0 != mv1 ? 2 : 1;
        for (var i = 0; i < n; i++) {
            var mv = i == 0 ? mv0 : mv1;
            var row = clampMvRow(RefMvs.mvY(mv), py, bh);
            var col = clampMvCol(RefMvs.mvX(mv), px, bw);
            var fpRow = (row + 3 + (row >= 0 ? 1 : 0)) >> 3;
            var fpCol = (col + 3 + (col >= 0 ? 1 : 0)) >> 3;
            if (fpRow == 0 && fpCol == 0 && zeroSeen) {
                continue;
            }
            zeroSeen |= fpRow == 0 && fpCol == 0;
            var sad = dsp.sad(src, py * srcStride + px, srcStride, ref.padded[0],
                    ref.origin[0] + (py + fpRow) * s + px + fpCol, s, bw, bh);
            best = Math.min(best, sad);
            if (i == 0) {
                predMv0Sad[r] = sad;
            } else {
                predMv1Sad[r] = sad;
            }
        }
        predMvSad[r] = best;
    }

    /**
     * Clamps a motion vector row so the block stays within the padded reference
     * ({@code enc_clamp_mv}).
     *
     * @param row the row in eighth samples
     * @param py the block row
     * @param bh the block height
     * @return the clamped row
     */
    private int clampMvRow(int row, int py, int bh) {
        var margin = (RefFrame.BORDER - 24) * 8;
        return Math.clamp(row, -(py * 8) - margin, (frame.alignedHeight() - py - bh) * 8 + margin);
    }

    /**
     * Clamps a motion vector column so the block stays within the padded reference.
     *
     * @param col the column in eighth samples
     * @param px the block column
     * @param bw the block width
     * @return the clamped column
     */
    private int clampMvCol(int col, int px, int bw) {
        var margin = (RefFrame.BORDER - 24) * 8;
        return Math.clamp(col, -(px * 8) - margin, (frame.alignedWidth() - px - bw) * 8 + margin);
    }

    /**
     * Returns whether a motion vector keeps the block inside the padded reference and the coded
     * range.
     *
     * @param mv the packed motion vector
     * @param px the block column
     * @param py the block row
     * @param bw the block width
     * @param bh the block height
     * @return {@code true} when usable
     */
    private boolean mvUsable(int mv, int px, int py, int bw, int bh) {
        var row = RefMvs.mvY(mv);
        var col = RefMvs.mvX(mv);
        return row == clampMvRow(row, py, bh) && col == clampMvCol(col, px, bw);
    }

    /**
     * Decides which references the block searches ({@code get_ref_frame_use_mask}).
     *
     * @param bs the block size
     */
    private void refUseMask(int bs) {
        var f = frame;
        var useGolden = f.refEnabled(3) && !(f.framesSinceGolden == 0);
        var useAlt = f.refEnabled(6) && f.speed.useNonrdAltrefFrame;
        var prune = part.nonrdPruneRefFrameSearch;
        var bw = BLOCK_DIMENSIONS[bs * 4];
        if (prune > 2) {
            useGolden = false;
            useAlt = false;
        }
        if (sourceVariance < 200 && part.sourceSadNonrd >= VarPartition.LOW_SAD) {
            if (part.colorSensitivityG[0] || part.colorSensitivityG[1]) {
                useGolden = false;
            }
            if (part.colorSensitivityAlt[0] || part.colorSensitivityAlt[1]) {
                useAlt = false;
            }
        }
        if (!useGolden && !useAlt && predMvSad[0] != Integer.MAX_VALUE && prune > 2 && !part.colorSensitivityG[0]
                && !part.colorSensitivityG[1] && f.refEnabled(3)) {
            var thr = (long) f.width * f.height > 352L * 288 ? 100 : 150;
            var bh = BLOCK_DIMENSIONS[bs * 4 + 1];
            var log2 = Integer.numberOfTrailingZeros(bw) + Integer.numberOfTrailingZeros(bh) + 4;
            if ((predMvSad[0] >> log2) > thr) {
                useGolden = true;
            }
        }
        var good = f.speed.goodLevel > 0;
        for (var r = 0; r < nRefs; r++) {
            var ref = refs[r];
            useRef[r] = ref == 0 || (ref == 3 ? useGolden : ref == 6 ? useAlt : good);
            if (screen && ref != 0 && ((screenRefs >> r) & 1) == 0) {
                useRef[r] = false;
            }
        }
    }

    /**
     * Collects the frame's searchable references from its search mask, {@code LAST} first.
     */
    private void collectRefs() {
        var mask = frame.params().refSearch | 1;
        var n = 0;
        for (var ref = 0; ref < MAX_REFS; ref++) {
            if ((mask & (1 << ref)) != 0) {
                refs[n++] = ref;
            }
        }
        nRefs = n;
    }

    /**
     * Chooses the mode of an inter frame block ({@code av1_nonrd_pick_inter_mode_sb}).
     *
     * @param bs the block size
     * @param edgeFlags the block's intra edge flags
     * @param bw the block width
     * @param bh the block height
     * @param px the block column
     * @param py the block row
     */
    private void pickInter(int bs, int edgeFlags, int bw, int bh, int px, int py) {
        // TODO: evaluate the LAST + ALTREF compound mode of libaom's real-time search
        var t = tile.t;
        var b = t.b;
        var f = frame;
        var sf = f.speed;
        var rd = f.rd;
        var src = f.source.data[0];
        var srcStride = f.source.stride[0];
        var srcOff = py * srcStride + px;
        var visW = Math.min(bw, f.f.bw * 4 - px);
        var visH = Math.min(bh, f.f.bh * 4 - py);
        var log2n = Integer.numberOfTrailingZeros(bw) + Integer.numberOfTrailingZeros(bh);
        colorSens[0] = part.colorSensitivity[0];
        var switchable = f.f.frameHdr.subpelFilterMode == FrameHeader.FILTER_SWITCHABLE;
        var filterSearch = 0;
        filtSelect = 0;
        if (switchable && sf.useNonrdFilterSearch) {
            filterSearch = screen ? 0 : filterSearchLevel(bs, part.sourceSadNonrd > VarPartition.VERY_LOW_SAD
                    ? sf.cbPredFilterSearch : 0);
        }
        var cbPred = switchable && sf.cbPredFilterSearch != 0 && part.sourceSadNonrd > VarPartition.VERY_LOW_SAD;
        var bestFilter = 0;
        colorSens[1] = part.colorSensitivity[1];
        Arrays.fill(modeChecked, false);
        finalistCount = 0;
        compFlagCoded = f.f.frameHdr.switchableCompRefs && bw >= 8 && bh >= 8;
        skipModeCoded = f.f.frameHdr.skipModeEnabled && bw >= 8 && bh >= 8;
        collectRefs();
        findPredictors(0, bs, edgeFlags, px, py, bw, bh, true);
        refUseMask(bs);
        var skipPredMv = part.nonrdPruneRefFrameSearch > 2 && colorSens[0] != 2 && colorSens[1] != 2;
        for (var r = 1; r < nRefs; r++) {
            if (useRef[r]) {
                findPredictors(r, bs, edgeFlags, px, py, bw, bh, !skipPredMv);
            }
        }
        long threshSadPred = Long.MAX_VALUE;
        if (predMvSad[0] != Integer.MAX_VALUE) {
            threshSadPred = (long) predMvSad[0] << 1;
            if (sf.nonrdPruneRefFrameSearch == 1) {
                threshSadPred += predMvSad[0] >> 2;
            }
        }
        if (sf.goodLevel > 0) {
            threshSadPred = goodRefPrune(threshSadPred);
        }
        var useModelLarge = bw >= 32 && bh >= 32 && f.baseQindex != 0 && !f.segmentBoosted(b.segId);
        var skipCtx = t.a.skip[t.bx & 31] + t.l.skip[t.by & 31];
        var skipCost = rd.skip[skipCtx * 2 + 1];
        var noSkipCost = rd.skip[skipCtx * 2];
        var bestRd = Long.MAX_VALUE;
        var bestSse = Long.MAX_VALUE;
        var bestMode = ModeWriter.GLOBALMV;
        var bestRef = 0;
        var bestTx = 1;
        var bestSkip = false;
        var bestEarlyTerm = false;
        var bestInitialSkip = false;
        var checkGlobalmv = true;
        long sseZeromvNorm = Long.MAX_VALUE;
        var colorChecked = false;
        var sizeGroup = sizeGroup(bs);
        for (var idx = 0; idx < 4 * nRefs; idx++) {
            var r = idx >> 2;
            var mode = MODES[idx & 3];
            var refIdx = refs[r];
            if (!useRef[r]) {
                continue;
            }
            if (idx == 0 && predMv0Sad[0] != Integer.MAX_VALUE) {
                var ySad = predMv0Sad[0];
                if (predMv1Sad[0] != Integer.MAX_VALUE) {
                    var near = frameMv[ModeWriter.NEARMV * MAX_REFS];
                    var nearest = frameMv[ModeWriter.NEARESTMV * MAX_REFS];
                    if (Math.abs(RefMvs.mvX(near)) + Math.abs(RefMvs.mvY(near))
                            < Math.abs(RefMvs.mvX(nearest)) + Math.abs(RefMvs.mvY(nearest))) {
                        ySad = predMv1Sad[0];
                    }
                }
                setColorSensitivity(ySad, px, py, bw, bh);
                colorChecked = true;
            }
            if (modeChecked[mode * MAX_REFS + r]) {
                continue;
            }
            if (!checkGlobalmv && mode == ModeWriter.GLOBALMV) {
                continue;
            }
            var prune = part.nonrdPruneRefFrameSearch;
            if (refIdx != 0 && sseZeromvNorm < 500 && mode == ModeWriter.NEWMV) {
                continue;
            }
            if (sf.nonrdAggressiveSkip && mode == ModeWriter.NEARMV) {
                continue;
            }
            if (prune != 0) {
                if (prune > 1 && refIdx != 0 && bw * bh > 256 && mode == ModeWriter.NEWMV) {
                    continue;
                }
                if (refIdx != 0 && mode == ModeWriter.NEARMV) {
                    continue;
                }
            }
            if ((prune > 0 || sf.goodLevel > 0) && predMvSad[r] != Integer.MAX_VALUE && refIdx != 0
                    && predMvSad[r] > threshSadPred) {
                continue;
            }
            if (mode == ModeWriter.NEARMV && predMv1Sad[r] != Integer.MAX_VALUE
                    && predMv1Sad[r] > ((long) predMv0Sad[r] << 1)) {
                continue;
            }
            var ref = f.ref(refIdx);
            var c = cand[r];
            var rateMv = 0;
            if (mode == ModeWriter.NEWMV) {
                if (refIdx != 0 && !sf.newmvAllRefs) {
                    if (bw * bh < 256) {
                        continue;
                    }
                }
                var mv = searchNewMv(r, px, py, bw, bh, bestRd);
                if (mv == RefMvs.INVALID_MV) {
                    continue;
                }
                frameMv[ModeWriter.NEWMV * MAX_REFS + r] = mv;
                var pred = ModeWriter.newmvPredictor(f.f.frameHdr, c, 0);
                rateMv = rd.mvBitCost(RefMvs.mvY(mv), RefMvs.mvX(mv), RefMvs.mvY(pred), RefMvs.mvX(pred),
                        RdCost.MV_COST_WEIGHT);
            }
            var thisMv = frameMv[mode * MAX_REFS + r];
            if (!mvUsable(thisMv, px, py, bw, bh)) {
                continue;
            }
            var dup = false;
            for (var m = 0; m < 4; m++) {
                if (m != mode && modeChecked[m * MAX_REFS + r] && frameMv[m * MAX_REFS + r] == thisMv) {
                    dup = true;
                    break;
                }
            }
            if (dup) {
                continue;
            }
            var subpel = ((RefMvs.mvX(thisMv) | RefMvs.mvY(thisMv)) & 7) != 0;
            if (subpel && (filterSearch == 2 || (filterSearch == 1 && (refIdx == 0
                    || part.nonrdPruneRefFrameSearch == 0)))) {
                searchFilter(bs, ref, refIdx, thisMv, px, py, bw, bh, visW, visH);
            } else {
                curFilter = subpel && cbPred ? filtSelect : 0;
                predictLuma(ref, thisMv, px, py, bw, bh, predY);
            }
            var packed = dsp.sseSum(src, srcOff, srcStride, predY, 0, bw, visW, visH);
            long sse = EncDsp.sse(packed) & 0xFFFFFFFFL;
            long var = EncDsp.variance(packed, Integer.numberOfTrailingZeros(visW) + Integer.numberOfTrailingZeros(visH))
                    & 0xFFFFFFFFL;
            var txs = txSize(bs, var, sse, f.segmentBoosted(b.segId));
            var earlyTerm = (txs & 256) != 0;
            thisTx = txs & 0xFF;
            if (useModelLarge && bw > 32 && bh > 32) {
                thisTx = 2;
            }
            if (!earlyTerm && sf.sseEarlyTermInterSearch != 0) {
                var thr = EARLY_TERM[sf.sseEarlyTermInterSearch - 1][sizeGroup];
                if (sf.sseEarlyTermInterSearch == 4 && (mode == ModeWriter.NEWMV || mode == ModeWriter.NEARESTMV)) {
                    thr = 0.3;
                }
                if (thr * sse > bestSse) {
                    continue;
                }
            }
            if (!earlyTerm && useModelLarge) {
                earlyTerm = acDcSkipTest(bw, bh, sse, var, packed, px, py, ref, thisMv);
            }
            if (refIdx == 0 && thisMv == 0) {
                sseZeromvNorm = sse >> log2n;
            }
            thisSse = sse;
            long nonskipRd = -1;
            int nonskipRate = 0;
            long nonskipDist = 0;
            if (earlyTerm) {
                thisSkip = true;
                thisRate = skipCost;
                thisDist = sse << 4;
                thisSse = sse << 4;
            } else {
                blockYrd(src, srcOff, srcStride, predY, 0, bw, bw, bh, visW, visH, thisTx);
                if (thisSkip || RdCost.rdcost(rdmult, thisRate, thisDist) >= RdCost.rdcost(rdmult, 0, thisSse)) {
                    if (!thisSkip) {
                        nonskipRate = thisRate + noSkipCost;
                        nonskipDist = thisDist;
                        nonskipRd = 0;
                    }
                    thisRate = skipCost;
                    thisSkip = true;
                    thisDist = thisSse;
                } else {
                    thisRate += noSkipCost;
                }
                if (colorSens[0] != 0 || colorSens[1] != 0) {
                    predictChroma(ref, thisMv, px, py, bw, bh);
                    var uvSse = modelUv(px, py, bw, bh, model);
                    var uvSkip = uvSse < 0;
                    thisSse += uvSkip ? -1 - uvSse : uvSse;
                    if (thisSkip && !uvSkip && nonskipRd >= 0) {
                        thisRate = nonskipRate;
                        thisDist = nonskipDist;
                        thisSkip = false;
                    }
                    thisRate += (int) model[0];
                    thisDist += model[1];
                    thisSkip = thisSkip && uvSkip;
                }
            }
            var thisBest = mode;
            var drl = mode == ModeWriter.NEARMV ? 1 : 0;
            var modeCost = interModeCost(mode, c, drl);
            if (mode != ModeWriter.GLOBALMV && thisMv == 0) {
                var globalCost = interModeCost(ModeWriter.GLOBALMV, c, 0);
                if (globalCost <= modeCost + rateMv) {
                    thisBest = ModeWriter.GLOBALMV;
                    modeCost = globalCost;
                    rateMv = 0;
                }
            }
            var residualRate = thisRate;
            thisRate += rateMv + modeCost + refCost(refIdx);
            if (switchable && thisBest != ModeWriter.GLOBALMV) {
                thisRate += filterCost(refIdx, curFilter);
            }
            var thisRd = RdCost.rdcost(rdmult, thisRate, thisDist);
            thisRd = newmvDiffBias(thisBest, thisRd, bs, bw, RefMvs.mvY(thisMv), RefMvs.mvX(thisMv));
            if (f.speed.finalists > 1) {
                offerFinalist(r, thisBest, thisBest == ModeWriter.GLOBALMV ? 0 : thisMv,
                        thisBest == ModeWriter.GLOBALMV ? 0 : curFilter, thisTx, thisRate - residualRate, thisRd, thisSkip);
            }
            modeChecked[mode * MAX_REFS + r] = true;
            modeChecked[thisBest * MAX_REFS + r] = true;
            if (checkGlobalmv && Math.abs(RefMvs.mvY(thisMv)) + Math.abs(RefMvs.mvX(thisMv)) < 2) {
                checkGlobalmv = false;
            }
            if (thisRd < bestRd) {
                bestRd = thisRd;
                bestRate = thisRate;
                bestDist = thisDist;
                bestSse = sse;
                bestMode = thisBest;
                bestRef = r;
                bestTx = thisTx;
                bestSkip = thisSkip;
                bestEarlyTerm = earlyTerm;
                bestInitialSkip = nonskipRd < 0 && thisSkip;
                b.mv[0] = thisMv;
                bestFilter = thisBest == ModeWriter.GLOBALMV ? 0 : curFilter;
                var swap = bestPred;
                bestPred = predY;
                predY = swap;
            }
            if (bestEarlyTerm && (idx > 0 || sf.nonrdAggressiveSkip)) {
                break;
            }
        }
        if (!colorChecked) {
            setColorSensitivity(Integer.MAX_VALUE, px, py, bw, bh);
        }
        b.intra = false;
        b.ref[0] = refs[bestRef];
        b.ref[1] = -1;
        b.interMode = bestMode;
        b.drlIdx = bestMode == ModeWriter.NEARMV ? 1 : 0;
        if (bestMode == ModeWriter.GLOBALMV) {
            b.mv[0] = 0;
        }
        var hdr = f.f.frameHdr;
        filter = switchable ? bestFilter : hdr.subpelFilterMode;
        b.filter2d = Tables.FILTER_2D[filter * 4 + filter];
        b.tx = txOnChain(bs, bestTx);
        skipTxfm = bestSkip;
        predictionReusable = bestRd != Long.MAX_VALUE && !f.scaled(b.ref[0]);
        tile.chosenCandidates(cand[bestRef]);
        lastBestRef = bestRef;
        if (compFlagCoded && sf.compound > 0 && !screen) {
            bestRd = searchCompound(bs, edgeFlags, px, py, bw, bh, visW, visH, bestRd, switchable, skipCost,
                    noSkipCost);
        }
        if (skipModeCoded && searchSkipMode(bs, edgeFlags, px, py, bw, bh, visW, visH, bestRd)) {
            return;
        }
        var best = estimateIntra(bs, bw, bh, px, py, bestRd, bestSkip && bestInitialSkip, bestEarlyTerm, bestRef);
        if (f.f.frameHdr.allowScreenContentTools && bw >= 8 && bh >= 8 && bw <= 16 && bh <= 16
                && interPalette(bw, bh, b.intra, bestSse)) {
            var refCostIntra = rd.intraInter[ModeWriter.intraCtx(t.a, t.l, t.by & 31, t.bx & 31,
                    t.by > tile.ts.rowStart, t.bx > tile.ts.colStart) * 2] + skipModeCost(0);
            var square = Math.min(MAX_SQUARE_TX[bs], 2);
            var rate = refCostIntra + rd.yMode[Tables.YMODE_SIZE_CONTEXT[bs] * 13 + IpredDsp.DC_PRED];
            searchPalette(bs, bw, bh, px, py, square, rate, -1, -1, best, 32);
            if (paletteSize > 0) {
                predictionReusable = false;
                b.intra = true;
                b.yMode = IpredDsp.DC_PRED;
                b.uvMode = IpredDsp.DC_PRED;
                b.tx = txOnChain(bs, square);
                skipTxfm = false;
                lumaTxType = 0;
            }
        }
    }

    /**
     * Returns whether an inter frame block searches a luma palette ({@code enable_palette} with
     * the screen content test of {@code force_palette_test}).
     *
     * @param bw the block width
     * @param bh the block height
     * @param intra whether the best mode so far is an intra mode
     * @param bestSse the luma sum of squared errors of the best inter mode
     * @return {@code true} to search
     */
    private boolean interPalette(int bw, int bh, boolean intra, long bestSse) {
        if (sourceVariance == 0) {
            return false;
        }
        var high = frame.highSourceSad;
        var force = false;
        if (part.sourceSadNonrd != VarPartition.ZERO_SAD && bestSse != Long.MAX_VALUE) {
            var perUnit = bestSse >> (Integer.numberOfTrailingZeros(bw) + Integer.numberOfTrailingZeros(bh) - 4);
            force = perUnit > (high ? 15000 : 200000) && sourceVariance > (high ? 50 : 200);
        }
        return (intra || force) && (high || sourceVariance > 300);
    }

    /**
     * Searches a luma palette for the block at the tile's current position and keeps the best one
     * in {@link #paletteSize}, {@link #paletteColors} and {@link #paletteMap} when it beats a
     * cost ({@code av1_rd_pick_palette_intra_sby}): from the most colors down to two, each size
     * clusters the block's distinct values, maps every sample to its nearest color, predicts the
     * block from the map into the reconstruction picture and estimates the residual; the search
     * stops after two sizes in a row cost more than the one before.
     *
     * @param bs the block size
     * @param bw the block width
     * @param bh the block height
     * @param px the block column
     * @param py the block row
     * @param tx the transform size of the residual estimate: 1 8x8 or 2 16x16
     * @param modeRate the cost of the block's mode symbols before the palette syntax
     * @param skipCost the cost of a skipped residual, or -1 when skip costs are not counted
     * @param noSkipCost the cost of a coded residual, or -1 when skip costs are not counted
     * @param bestRd the cost to beat
     * @param maxColors the most distinct values the block may hold, at most 64
     * @return the cost of the palette kept, or {@code bestRd} when none beats it
     */
    private long searchPalette(int bs, int bw, int bh, int px, int py, int tx, long modeRate, int skipCost,
                               int noSkipCost, long bestRd, int maxColors) {
        var f = frame;
        var t = tile.t;
        var src = f.source.data[0];
        var srcStride = f.source.stride[0];
        var srcOff = py * srcStride + px;
        var cur = f.f.cur;
        var plane = cur.data[0];
        var stride = cur.stride[0];
        var predOff = py * stride + px;
        var visW = Math.min(bw, f.f.bw * 4 - px);
        var visH = Math.min(bh, f.f.bh * 4 - py);
        if (histogram == null) {
            histogram = new int[1 << f.bitDepth];
        }
        var colors = 0;
        var overflow = false;
        for (var y = 0; y < visH && !overflow; y++) {
            var row = srcOff + y * srcStride;
            for (var x = 0; x < visW; x++) {
                var v = src[row + x];
                if (histogram[v] == 0) {
                    if (colors == maxColors) {
                        overflow = true;
                        break;
                    }
                    distinct[colors++] = v;
                }
                histogram[v]++;
            }
        }
        for (var i = 0; i < colors; i++) {
            weights[i] = histogram[distinct[i]];
        }
        for (var i = 0; i < colors; i++) {
            histogram[distinct[i]] = 0;
        }
        if (overflow || colors < 2) {
            return bestRd;
        }
        sortByValue(colors);
        var cdf = tile.ts.cdf;
        var bx4 = t.bx & 31;
        var by4 = t.by & 31;
        var szCtx = BLOCK_DIMENSIONS[bs * 4 + 2] + BLOCK_DIMENSIONS[bs * 4 + 3] - 2;
        var palCtx = (t.a.palSz[bx4] > 0 ? 1 : 0) + (t.l.palSz[by4] > 0 ? 1 : 0);
        var largest = Math.min(colors, 8);
        var worse = 0;
        var lastRd = Long.MAX_VALUE;
        for (var n = largest; n >= 2 && worse < 2; n--) {
            var k = kmeans(colors, n);
            if (k < 2 || (k < n && n != largest)) {
                continue;
            }
            for (var i = 0; i < k; i++) {
                paletteTrialColors[i] = (short) centroids[i];
            }
            for (var y = 0; y < visH; y++) {
                var row = srcOff + y * srcStride;
                for (var x = 0; x < visW; x++) {
                    paletteTrial[y * bw + x] = (byte) nearest(k, src[row + x]);
                }
            }
            for (var y = 0; y < bh; y++) {
                var mapRow = Math.min(y, visH - 1) * bw;
                var dst = predOff + y * stride;
                for (var x = 0; x < bw; x++) {
                    plane[dst + x] = paletteTrialColors[paletteTrial[mapRow + Math.min(x, visW - 1)]];
                }
            }
            thisSse = EncDsp.sse(dsp.sseSum(src, srcOff, srcStride, plane, predOff, stride, visW, visH)) & 0xFFFFFFFFL;
            blockYrd(src, srcOff, srcStride, plane, predOff, stride, bw, bh, visW, visH, tx);
            paletteCounter.startCounting();
            paletteCounter.encodeBool(cdf, Cdf.PAL_Y + (szCtx * 3 + palCtx) * 2, 1);
            paletteWriter.writeColors(paletteCounter, cdf, t, 0, szCtx, bx4, by4, paletteTrialColors, 0, k,
                    f.bitDepth);
            paletteWriter.writeIndices(paletteCounter, cdf, paletteTrial, bw, k, 0, visW, visH);
            var rate = modeRate + paletteCounter.cost();
            if (skipCost < 0) {
                rate += thisRate;
            } else {
                rate += thisSkip ? skipCost : thisRate + noSkipCost;
            }
            var rd = RdCost.rdcost(rdmult, rate, thisDist);
            if (rd < bestRd) {
                bestRd = rd;
                bestRate = rate;
                bestDist = thisDist;
                paletteSize = k;
                System.arraycopy(paletteTrialColors, 0, paletteColors, 0, k);
                for (var y = 0; y < visH; y++) {
                    System.arraycopy(paletteTrial, y * bw, paletteMap, y * bw, visW);
                }
            }
            worse = rd >= lastRd ? worse + 1 : 0;
            lastRd = rd;
        }
        return bestRd;
    }

    /**
     * Sorts the first distinct values ascending, moving their weights along.
     *
     * @param count the number of values
     */
    private void sortByValue(int count) {
        for (var i = 1; i < count; i++) {
            var v = distinct[i];
            var w = weights[i];
            var j = i - 1;
            while (j >= 0 && distinct[j] > v) {
                distinct[j + 1] = distinct[j];
                weights[j + 1] = weights[j];
                j--;
            }
            distinct[j + 1] = v;
            weights[j + 1] = w;
        }
    }

    /**
     * Clusters the weighted distinct values into at most {@code n} centers with k-means,
     * initialized at evenly spaced weighted quantiles, and leaves the distinct rounded centers
     * ascending in {@link #centroids}.
     *
     * @param count the number of distinct values, ascending in {@link #distinct}
     * @param n the number of clusters, at most 8
     * @return the number of distinct centers
     */
    private int kmeans(int count, int n) {
        if (count <= n) {
            System.arraycopy(distinct, 0, centroids, 0, count);
            return count;
        }
        long total = 0;
        for (var i = 0; i < count; i++) {
            total += weights[i];
        }
        long acc = 0;
        var c = 0;
        for (var i = 0; i < count && c < n; i++) {
            acc += weights[i];
            while (c < n && acc * n >= (2L * c + 1) * total / 2) {
                means[c++] = distinct[i];
            }
        }
        while (c < n) {
            means[c++] = distinct[count - 1];
        }
        for (var iter = 0; iter < KMEANS_ITERATIONS; iter++) {
            Arrays.fill(clusterSums, 0, n, 0);
            Arrays.fill(clusterWeights, 0, n, 0);
            for (var i = 0; i < count; i++) {
                var best = 0;
                var bestD = Double.MAX_VALUE;
                for (var j = 0; j < n; j++) {
                    var d = Math.abs(distinct[i] - means[j]);
                    if (d < bestD) {
                        bestD = d;
                        best = j;
                    }
                }
                clusterSums[best] += distinct[i] * (double) weights[i];
                clusterWeights[best] += weights[i];
            }
            for (var j = 0; j < n; j++) {
                if (clusterWeights[j] > 0) {
                    means[j] = clusterSums[j] / clusterWeights[j];
                }
            }
        }
        var k = 0;
        for (var j = 0; j < n; j++) {
            var v = (int) Math.round(means[j]);
            var at = k;
            while (at > 0 && centroids[at - 1] > v) {
                at--;
            }
            if (at > 0 && centroids[at - 1] == v) {
                continue;
            }
            System.arraycopy(centroids, at, centroids, at + 1, k - at);
            centroids[at] = v;
            k++;
        }
        return k;
    }

    /**
     * Returns the index of the color of the palette being evaluated nearest a value.
     *
     * @param n the number of colors
     * @param v the value
     * @return the index
     */
    private int nearest(int n, int v) {
        var best = 0;
        var bestD = Integer.MAX_VALUE;
        for (var i = 0; i < n; i++) {
            var d = Math.abs(paletteTrialColors[i] - v);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    /**
     * Returns whether the luma and chroma of a large block quantize to zero
     * ({@code set_early_term_based_on_uv_plane} on the 16x16 or 8x8 sub-blocks).
     *
     * @param bw the block width
     * @param bh the block height
     * @param sse the luma sum of squared errors
     * @param var the luma variance
     * @param packed the luma sums
     * @param px the block column
     * @param py the block row
     * @param ref the reference
     * @param mv the motion vector
     * @return {@code true} when the block can skip its residual
     */
    private boolean acDcSkipTest(int bw, int bh, long sse, long var, long packed, int px, int py,
                                 RefFrame ref, int mv) {
        var f = frame;
        var q = f.quantizer(tile.t.b.segId, 0);
        long dcThr = ((long) q.analysisDequant[0] * q.analysisDequant[0]) >> 6;
        long acThr = ((long) q.analysisDequant[1] * q.analysisDequant[1]) >> 6;
        var bwl = Integer.numberOfTrailingZeros(bw) - 2;
        var bhl = Integer.numberOfTrailingZeros(bh) - 2;
        var normSum = Math.abs(EncDsp.sum(packed)) >> (bwl + bhl);
        if (f.speed.speed >= 8 && normSum < 5) {
            acThr *= f.width <= 640 && f.height <= 480 ? 4 : 2;
        }
        var sub = thisTx == 2 ? 16 : 8;
        var src = f.source.data[0];
        var srcStride = f.source.stride[0];
        var log2 = Integer.numberOfTrailingZeros(sub) * 2;
        for (var y = 0; y < bh; y += sub) {
            for (var x = 0; x < bw; x += sub) {
                var p = dsp.sseSum(src, (py + y) * srcStride + px + x, srcStride, predY, y * bw + x, bw, sub, sub);
                long s = EncDsp.sse(p) & 0xFFFFFFFFL;
                long v = EncDsp.variance(p, log2) & 0xFFFFFFFFL;
                if (!(v < acThr || var == 0)) {
                    return false;
                }
                if (!(s - v < dcThr || sse == var)) {
                    return false;
                }
            }
        }
        if (colorSens[0] == 0 && colorSens[1] == 0) {
            return true;
        }
        predictChroma(ref, mv, px, py, bw, bh);
        var cw = bw >> f.ssHor;
        var ch = bh >> f.ssVer;
        var log2c = Integer.numberOfTrailingZeros(cw) + Integer.numberOfTrailingZeros(ch);
        for (var pl = 1; pl < 3; pl++) {
            if (colorSens[pl - 1] == 0) {
                continue;
            }
            var qu = f.quantizer(tile.t.b.segId, pl);
            long uvDc = ((long) qu.analysisDequant[0] * qu.analysisDequant[0]) >> 3;
            long uvAc = ((long) qu.analysisDequant[1] * qu.analysisDequant[1]) >> 3;
            var src2 = f.source.data[pl];
            var stride = f.source.stride[1];
            var p = dsp.sseSum(src2, (py >> f.ssVer) * stride + (px >> f.ssHor), stride, predUv, (pl - 1) * CHROMA_PLANE,
                    cw, cw, ch);
            long s = EncDsp.sse(p) & 0xFFFFFFFFL;
            long v = EncDsp.variance(p, log2c) & 0xFFFFFFFFL;
            if (!((v < uvAc || v == 0) && (s - v < uvDc || s == v))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Biases against new motion vectors far from the neighbors' ({@code newmv_diff_bias}).
     *
     * <p>The good-quality levels apply no bias: it guards the real-time search against vectors
     * that fit noise, and penalizes the large uniform motion of a camera pan fourfold.
     *
     * @param mode the inter mode
     * @param rdCost the cost
     * @param bs the block size
     * @param bw the block width
     * @param mvRow the motion vector row
     * @param mvCol the motion vector column
     * @return the biased cost
     */
    private long newmvDiffBias(int mode, long rdCost, int bs, int bw, int mvRow, int mvCol) {
        if (frame.speed.goodLevel > 0) {
            return rdCost;
        }
        if (mode == ModeWriter.NEWMV) {
            if (bw >= 64 && part.sourceSadNonrd != VarPartition.HIGH_SAD && sourceVariance < 300
                    && (mvRow > 16 || mvRow < -16 || mvCol > 16 || mvCol < -16)) {
                return rdCost << 2;
            }
            var above = tile.blockAboveMv();
            var left = tile.blockLeftMv();
            int avgRow;
            int avgCol;
            if (above != RefMvs.INVALID_MV && left != RefMvs.INVALID_MV) {
                avgRow = (RefMvs.mvY(above) + RefMvs.mvY(left) + 1) >> 1;
                avgCol = (RefMvs.mvX(above) + RefMvs.mvX(left) + 1) >> 1;
            } else if (above != RefMvs.INVALID_MV) {
                avgRow = RefMvs.mvY(above);
                avgCol = RefMvs.mvX(above);
            } else if (left != RefMvs.INVALID_MV) {
                avgRow = RefMvs.mvY(left);
                avgCol = RefMvs.mvX(left);
            } else {
                avgRow = 0;
                avgCol = 0;
            }
            var rowDiff = avgRow - mvRow;
            var colDiff = avgCol - mvCol;
            if (rowDiff > 80 || rowDiff < -80 || colDiff > 80 || colDiff < -80) {
                return bw >= 32 && BLOCK_DIMENSIONS[bs * 4 + 1] >= 8 ? rdCost << 1 : (5 * rdCost) >> 2;
            }
        } else if (frame.speed.speed >= 8 && sourceVariance < 150
                && (mvRow > 64 || mvRow < -64 || mvCol > 64 || mvCol < -64)) {
            return (5 * rdCost) >> 2;
        }
        return rdCost;
    }

    /**
     * Searches the new motion vector of a reference ({@code search_new_mv} with
     * {@code combined_motion_search}).
     *
     * @param r the searched reference index
     * @param px the block column
     * @param py the block row
     * @param bw the block width
     * @param bh the block height
     * @param bestRd the best cost so far
     * @return the packed vector, or {@link RefMvs#INVALID_MV} when the search is abandoned
     */
    private int searchNewMv(int r, int px, int py, int bw, int bh, long bestRd) {
        var f = frame;
        var hdr = f.f.frameHdr;
        var c = cand[r];
        var pred = ModeWriter.newmvPredictor(hdr, c, 0);
        var predRow = RefMvs.mvY(pred);
        var predCol = RefMvs.mvX(pred);
        var src = f.source.data[0];
        var srcStride = f.source.stride[0];
        var ref = f.ref(refs[r]);
        search.setBlock(f.rd, src, py * srcStride + px, srcStride, ref, px, py, bw, bh, f.alignedWidth(),
                f.alignedHeight(), predRow, predCol, f.rd.sadPerBit, f.rd.errorPerBit);
        search.skipRows = f.speed.rowSkipSad && bh >= 16 && (bh & 3) == 0;
        var startRow = predRow >> 3;
        var startCol = predCol >> 3;
        int full;
        if (f.speed.nstepStages > 0) {
            var start = goodStart(c, startRow, startCol, refs[r]);
            var coarse = search.nstep(start >> 16, (short) start, f.speed.nstepStages);
            full = search.fastDiamond(coarse >> 16, (short) coarse);
        } else {
            full = search.fastDiamond(startRow, startCol);
        }
        search.skipRows = false;
        var fr = full >> 16;
        var fc = (short) full;
        var rateMv = f.rd.mvBitCost(fr * 8, fc * 8, predRow, predCol, RdCost.MV_COST_WEIGHT);
        if (RdCost.rdcost(rdmult, rateMv, 0) > bestRd) {
            return RefMvs.INVALID_MV;
        }
        var fullVar = search.fullVarCost;
        var wellFull = (bw == 64 && bh == 64 && fullVar * 40L < 62267L * 7) || (bw == 32 && bh == 32 && fullVar * 8L < 42380)
                || (bw == 16 && bh == 16 && fullVar * 8L < 10127);
        var forcedStop = subpelSelect(bw, bh, fr, fc, predRow, predCol, startRow, startCol, wellFull);
        var prunedMore = f.speed.subpelPrunedMore || (f.speed.useAdaptiveSubpelSearch && aggressiveSubpel(wellFull));
        var mv = search.subpelTree(fr, fc, forcedStop, hdr.hp, prunedMore, true);
        var row = mv >> 16;
        var col = (short) mv;
        if (row == predRow && col == predCol) {
            return RefMvs.INVALID_MV;
        }
        return RefMvs.mv(row, col);
    }

    /**
     * Returns the start of a good-quality new motion vector search: the full-sample position, among
     * the predictor, the second candidate of the reference's list and zero motion, with the
     * smallest SAD.
     *
     * @param c the reference's candidate list
     * @param predRow the predictor's full-sample row
     * @param predCol the predictor's full-sample column
     * @param refIndex the decoder reference index
     * @return the start, packed as {@code row << 16 | (col & 0xFFFF)}
     */
    private int goodStart(RefMvs.Candidates c, int predRow, int predCol, int refIndex) {
        var bestRow = Math.clamp(predRow, search.rowMin, search.rowMax);
        var bestCol = Math.clamp(predCol, search.colMin, search.colMax);
        var best = search.sadAt(bestRow, bestCol);
        if (bestRow != 0 || bestCol != 0) {
            var zr = Math.clamp(0, search.rowMin, search.rowMax);
            var zc = Math.clamp(0, search.colMin, search.colMax);
            var sad = search.sadAt(zr, zc);
            if (sad < best) {
                best = sad;
                bestRow = zr;
                bestCol = zc;
            }
        }
        if (c.count > 1) {
            var mv = c.mv0[1];
            var r = Math.clamp(RefMvs.mvY(mv) >> 3, search.rowMin, search.rowMax);
            var col = Math.clamp(RefMvs.mvX(mv) >> 3, search.colMin, search.colMax);
            if (r != bestRow || col != bestCol) {
                var sad = search.sadAt(r, col);
                if (sad < best) {
                    best = sad;
                    bestRow = r;
                    bestCol = col;
                }
            }
        }
        var global = frame.globalStart(refIndex);
        if (global != RefMvs.INVALID_MV) {
            var r = Math.clamp(global >> 16, search.rowMin, search.rowMax);
            var col = Math.clamp((short) global, search.colMin, search.colMax);
            if (r != bestRow || col != bestCol) {
                var sad = search.sadAt(r, col);
                if (sad < best) {
                    bestRow = r;
                    bestCol = col;
                }
            }
        }
        return bestRow << 16 | (bestCol & 0xFFFF);
    }

    /**
     * Returns whether the aggressive sub-sample search applies ({@code use_aggressive_subpel_search_method}).
     *
     * @param fullWell whether the full-sample search found a good match
     * @return {@code true} when the most pruned search runs
     */
    private boolean aggressiveSubpel(boolean fullWell) {
        var qband = frame.baseQindex >> 6;
        return qband > 0 && (fullWell || part.sourceSadNonrd <= VarPartition.LOW_SAD || sourceVariance < 100);
    }

    /**
     * Chooses the finest precision of a new motion vector ({@code subpel_select}): whole samples
     * when the frame forces whole-sample motion.
     *
     * @param bw the block width
     * @param bh the block height
     * @param fr the full-sample row
     * @param fc the full-sample column
     * @param refRow the predictor row
     * @param refCol the predictor column
     * @param startRow the full-sample start row
     * @param startCol the full-sample start column
     * @param fullWell whether the full-sample search found a good match
     * @return the precision stop
     */
    private int subpelSelect(int bw, int bh, int fr, int fc, int refRow, int refCol, int startRow,
                             int startCol, boolean fullWell) {
        if (frame.f.frameHdr.forceIntegerMv) {
            return SpeedFeatures.FULL_PEL;
        }
        var sf = frame.speed;
        var lowMotion = frame.avgFrameLowMotion;
        if (sf.reduceMvPelPrecisionHighmotion >= 3) {
            var lowRes = (long) frame.width * frame.height <= 320L * 240;
            var area = bw * bh;
            var thresh = area > 1024 ? 2 : area > 256 ? 4 : 6;
            if (lowMotion > 0 && lowMotion < 40) {
                thresh = 12;
            }
            thresh = lowRes ? thresh >> 1 : thresh;
            if (Math.abs(fr) >= thresh || Math.abs(fc) >= thresh) {
                return SpeedFeatures.HALF_PEL;
            }
        } else if (sf.reduceMvPelPrecisionHighmotion >= 1) {
            var idx = sf.reduceMvPelPrecisionHighmotion - 1;
            int thresh;
            if (lowMotion > 0 && lowMotion < 40) {
                thresh = 12;
            } else {
                var area = bw * bh;
                var th = HIGHMOTION_THRESH[idx];
                thresh = area >= 1024 ? th[0] : area >= 256 ? th[1] : th[2];
            }
            if (Math.abs(fr) >= (thresh << 1) || Math.abs(fc) >= (thresh << 1)) {
                return SpeedFeatures.FULL_PEL;
            } else if (Math.abs(fr) >= thresh || Math.abs(fc) >= thresh) {
                return SpeedFeatures.HALF_PEL;
            }
        }
        if (sf.reduceMvPelPrecisionLowcomplex >= 2) {
            var qband = frame.baseQindex >> 6;
            if (part.sourceSadNonrd <= VarPartition.VERY_LOW_SAD && bw * bh > 256 && qband != 0) {
                if (sourceVariance < 500) {
                    return SpeedFeatures.FULL_PEL;
                } else if (sourceVariance < 5000) {
                    return SpeedFeatures.HALF_PEL;
                }
            }
        } else if (sf.reduceMvPelPrecisionLowcomplex >= 1) {
            if (fullWell && refRow == 0 && refCol == 0 && startRow == 0 && startCol == 0) {
                return SpeedFeatures.HALF_PEL;
            }
        }
        return sf.subpelForceStop;
    }

    /**
     * Tests the intra modes of an inter frame block against the best inter mode
     * ({@code av1_estimate_intra_mode}).
     *
     * @param bs the block size
     * @param bw the block width
     * @param bh the block height
     * @param px the block column
     * @param py the block row
     * @param bestRd the best inter cost
     * @param initialSkip whether the best inter mode skipped its residual from the start
     * @param bestEarlyTerm whether the best inter mode terminated early
     * @param bestRef the best inter reference index
     * @return the cost of the best mode, intra or inter
     */
    private long estimateIntra(int bs, int bw, int bh, int px, int py, long bestRd, boolean initialSkip,
                               boolean bestEarlyTerm, int bestRef) {
        var f = frame;
        var sf = f.speed;
        var t = tile.t;
        var b = t.b;
        var rd = f.rd;
        var refCostIntra = rd.intraInter[ModeWriter.intraCtx(t.a, t.l, t.by & 31, t.bx & 31,
                t.by > tile.ts.rowStart, t.bx > tile.ts.colStart) * 2] + skipModeCost(0);
        var penalty = 20 * Quantizer.dcQuant(f.baseQindex, 0);
        var interThresh = RdCost.rdcost(rdmult, refCostIntra + penalty, 0);
        var perform = true;
        var forceIntra = false;
        var spatialThresh = 50;
        var motionThresh = 32;
        if (!sf.useNonrdAltrefFrame && sf.nonrdPruneRefFrameSearch > 0) {
            spatialThresh = 150;
            motionThresh = 0;
        }
        var earlyExitRd = true;
        if (sourceVariance < spatialThresh) {
            if (bestRd != Long.MAX_VALUE && (refs[bestRef] != 0 || Math.abs(RefMvs.mvY(b.mv[0])) >= motionThresh
                    || Math.abs(RefMvs.mvX(b.mv[0])) >= motionThresh)) {
                penalty >>= 2;
                interThresh = RdCost.rdcost(rdmult, refCostIntra + penalty, 0);
                earlyExitRd = false;
            }
            if (sourceVariance < Math.max(50, spatialThresh >> 1) && part.sourceSadNonrd >= VarPartition.HIGH_SAD) {
                forceIntra = true;
            }
            if (bw >= 32 && bh >= 32) {
                bestEarlyTerm = false;
            }
        } else if (part.sourceSadNonrd <= VarPartition.LOW_SAD) {
            perform = false;
        }
        if (skipTxfm && initialSkip) {
            if (sf.skipIntraPred == 1 && b.interMode != ModeWriter.NEWMV) {
                perform = false;
            } else if (sf.skipIntraPred == 2) {
                perform = false;
            }
        }
        var maxIntra = bw <= 32 && bh <= 32;
        if (!(bestRd == Long.MAX_VALUE || forceIntra || (perform && !bestEarlyTerm && maxIntra))) {
            return bestRd;
        }
        if (interThresh > bestRd) {
            return bestRd;
        }
        var src = f.source.data[0];
        var srcStride = f.source.stride[0];
        var cur = f.f.cur;
        var stride = cur.stride[0];
        var visW = Math.min(bw, f.f.bw * 4 - px);
        var visH = Math.min(bh, f.f.bh * 4 - py);
        var square = Math.min(MAX_SQUARE_TX[bs], 2);
        var tx = txOnChain(bs, square);
        var bestIntraRd = bestRd;
        var chosen = -1;
        var chosenSkip = false;
        for (var midx = 0; midx < 4; midx++) {
            var mode = INTRA_MODES[midx];
            if (midx > 2 || !forceIntra) {
                var dcOnly = sf.intraDcOnly || bw >= 32 || bh >= 32;
                if (mode == IpredDsp.SMOOTH_PRED) {
                    continue;
                }
                if (dcOnly && mode != IpredDsp.DC_PRED) {
                    continue;
                }
            }
            if (earlyExitRd && bestIntraRd < interThresh) {
                continue;
            }
            predictIntraLuma(mode, tx, bs);
            thisSse = EncDsp.sse(dsp.sseSum(src, py * srcStride + px, srcStride, cur.data[0], py * stride + px, stride,
                    visW, visH)) & 0xFFFFFFFFL;
            blockYrd(src, py * srcStride + px, srcStride, cur.data[0], py * stride + px, stride, bw, bh, visW, visH,
                    square);
            var rate = thisRate + refCostIntra + penalty;
            if (BLOCK_DIMENSIONS[bs * 4 + 2] + BLOCK_DIMENSIONS[bs * 4 + 3] >= 2 && mode >= IpredDsp.VERT_PRED
                    && mode <= IpredDsp.VERT_LEFT_PRED) {
                rate += 0;
            }
            rate += rd.yMode[Tables.YMODE_SIZE_CONTEXT[bs] * 13 + mode];
            var thisRd = RdCost.rdcost(rdmult, rate, thisDist);
            if (thisRd < bestIntraRd) {
                bestIntraRd = thisRd;
                bestRate = rate;
                bestDist = thisDist;
                chosen = mode;
                chosenSkip = thisSkip;
            }
        }
        if (chosen >= 0) {
            predictionReusable = false;
            b.intra = true;
            b.yMode = chosen;
            b.uvMode = chosen;
            b.tx = tx;
            skipTxfm = false;
            if (chosenSkip) {
                skipTxfm = false;
            }
        }
        return bestIntraRd;
    }
}
