/* Ports libvpx 1.15.1 (vp8/encoder/rdopt.c: vp8_rd_pick_inter_mode, vp8_rd_pick_intra_mode, macro_block_yrd,
   rd_pick_intra4x4block, rd_pick_intra4x4mby_modes, rd_pick_intra16x16mby_mode, rd_pick_intra_mbuv_mode,
   rd_inter16x16_uv, rd_inter4x4_uv, labels2mode, rd_check_segment, vp8_rd_pick_best_mbsegmentation,
   evaluate_inter_mode_rd, calculate_final_rd_costs, rd_update_mvcount; vp8/encoder/mcomp.c:
   vp8_diamond_search_sad_c, vp8_full_search_sad_c, vp8_refining_search_sad_c,
   vp8_find_best_sub_pixel_step_iteratively;
   vp8/common/reconinter.c: build_inter4x4_predictors_mb, build_uvmvs). Copyright (c) 2010 The WebM project
   authors; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.video.vp8.internal;

import static com.github.auties00.smalt.video.vp8.internal.Vp8FrameDecoder.ALTREF_FRAME;
import static com.github.auties00.smalt.video.vp8.internal.Vp8FrameDecoder.B_DC_PRED;
import static com.github.auties00.smalt.video.vp8.internal.Vp8FrameDecoder.B_HU_PRED;
import static com.github.auties00.smalt.video.vp8.internal.Vp8FrameDecoder.B_PRED;
import static com.github.auties00.smalt.video.vp8.internal.Vp8FrameDecoder.DC_PRED;
import static com.github.auties00.smalt.video.vp8.internal.Vp8FrameDecoder.INTRA_FRAME;
import static com.github.auties00.smalt.video.vp8.internal.Vp8FrameDecoder.NEARESTMV;
import static com.github.auties00.smalt.video.vp8.internal.Vp8FrameDecoder.NEARMV;
import static com.github.auties00.smalt.video.vp8.internal.Vp8FrameDecoder.NEWMV;
import static com.github.auties00.smalt.video.vp8.internal.Vp8FrameDecoder.SPLITMV;
import static com.github.auties00.smalt.video.vp8.internal.Vp8FrameDecoder.TM_PRED;
import static com.github.auties00.smalt.video.vp8.internal.Vp8FrameDecoder.ZEROMV;

/**
 * Chooses the modes of the macroblocks of a good-quality VP8 frame by their rate and distortion:
 * every candidate mode is predicted, transformed and quantized, its rate counted from the token
 * costs of the frame and its distortion measured on the quantized coefficients, as libvpx's
 * good-quality speeds 0 to 3 do.
 *
 * <p>An instance belongs to one {@link RtMbCoder}, whose macroblock state and scratch it uses:
 * the choice is left in the encoder's mode info, the subblock levels of {@code B_PRED} and the
 * reconstruction of its luma in the frame being coded, and {@link #encodeSplit} codes a
 * {@code SPLITMV} macroblock the decoder's way.
 *
 * <p>Speed 0 decides with the regular quantizer, whose zero bins widen with each candidate mode,
 * and the other speeds with the fast quantizer, as libvpx's {@code use_fastquant_for_pick}
 * does.
 *
 * @implNote This implementation searches split vectors without libvpx's exhaustive search of its
 * best-quality mode.
 */
final class RdMbCoder {

    /**
     * The split into two 16x8 halves.
     */
    private static final int SPLIT_16X8 = 0;

    /**
     * The split into two 8x16 halves.
     */
    private static final int SPLIT_8X16 = 1;

    /**
     * The split into four 8x8 quarters.
     */
    private static final int SPLIT_8X8 = 2;

    /**
     * The split into sixteen 4x4 subblocks.
     */
    private static final int SPLIT_4X4 = 3;

    /**
     * The partition mode taking the vector of the subblock to the left.
     */
    private static final int LEFT4X4 = 0;

    /**
     * The partition mode taking the vector of the subblock above.
     */
    private static final int ABOVE4X4 = 1;

    /**
     * The partition mode without motion.
     */
    private static final int ZERO4X4 = 2;

    /**
     * The partition mode with a vector of its own.
     */
    private static final int NEW4X4 = 3;

    /**
     * The partition of each subblock in each split configuration, libvpx's {@code vp8_mbsplits},
     * {@code [configuration 4][subblock 16]}.
     */
    private static final int[] MBSPLITS = {
            0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1,
            0, 0, 1, 1, 0, 0, 1, 1, 0, 0, 1, 1, 0, 0, 1, 1,
            0, 0, 1, 1, 0, 0, 1, 1, 2, 2, 3, 3, 2, 2, 3, 3,
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15
    };

    /**
     * The number of partitions of each split configuration.
     */
    private static final int[] MBSPLIT_COUNT = {2, 2, 4, 16};

    /**
     * The width of a partition of each split configuration.
     */
    private static final int[] SPLIT_WIDTH = {16, 8, 8, 4};

    /**
     * The height of a partition of each split configuration.
     */
    private static final int[] SPLIT_HEIGHT = {8, 16, 8, 4};

    /**
     * The shift of a partition's squared error to the per-pixel scale of a 16x16 block, libvpx's
     * {@code segmentation_to_sseshift}.
     */
    private static final int[] SEGMENTATION_TO_SSESHIFT = {3, 3, 2, 0};

    /**
     * The shifted error of a partition's diamond match above which the best quality searches
     * its vector exhaustively, as libvpx's {@code rd_check_segment} does.
     */
    private static final int FULL_SEARCH_THRESHOLD = 4000;

    /**
     * The reach of the exhaustive search of a partition's vector, in full pixels, as libvpx's
     * {@code rd_check_segment} passes to {@code vp8_full_search_sad}.
     */
    private static final int FULL_SEARCH_DISTANCE = 16;

    /**
     * The largest first diamond step, in full pixels, libvpx's {@code MAX_FIRST_STEP}.
     */
    private static final int MAX_FIRST_STEP = 128;

    /**
     * The number of diamond steps, libvpx's {@code MAX_MVSEARCH_STEPS}.
     */
    private static final int MAX_MVSEARCH_STEPS = 8;

    /**
     * The row direction of each of the eight diamond sites, libvpx's
     * {@code vp8_init3smotion_compensation} order.
     */
    private static final int[] DIAMOND_ROWS = {-1, 1, 0, 0, -1, -1, 1, 1};

    /**
     * The column direction of each of the eight diamond sites.
     */
    private static final int[] DIAMOND_COLS = {0, 0, -1, 1, -1, 1, -1, 1};

    /**
     * The four neighbors of the refining search, libvpx's {@code neighbors} in
     * {@code vp8_refining_search_sad_c}, as row, column pairs.
     */
    private static final int[] REFINE_NEIGHBORS = {-1, 0, 0, -1, 0, 1, 1, 0};

    /**
     * The bilinear taps of each eighth-pixel offset.
     */
    private static final int[] BILINEAR = {128, 112, 96, 80, 64, 48, 32, 16};

    /**
     * The coder whose macroblock this instance decides.
     */
    private final RtMbCoder c;

    /**
     * The encoder.
     */
    private final RtEncoder enc;

    /**
     * The original luma coefficients of a 16x16 evaluation, before quantization.
     */
    private final int[] origLuma = new int[2 * RtDsp.GROUP];

    /**
     * The original chroma coefficients of an evaluation, before quantization.
     */
    private final int[] origChroma = new int[RtDsp.GROUP];

    /**
     * The original Y2 coefficients of a 16x16 evaluation.
     */
    private final short[] origY2 = new short[16];

    /**
     * The coefficients of a 4x4 block evaluation, dequantized after quantization.
     */
    private final short[] blockCoef = new short[16];

    /**
     * The original coefficients of a 4x4 block evaluation.
     */
    private final short[] blockOrig = new short[16];

    /**
     * The levels of a 4x4 block evaluation in zigzag order.
     */
    private final short[] blockLevels = new short[16];

    /**
     * The best dequantized coefficients of the 4x4 block being searched.
     */
    private final short[] bestBlockCoef = new short[16];

    /**
     * The best prediction of the 4x4 block being searched, four rows of four bytes.
     */
    private final byte[] bestBlockPred = new byte[16];

    /**
     * The above entropy contexts of a rate evaluation: four luma, two U, two V and one Y2.
     */
    private final int[] above = new int[9];

    /**
     * The left entropy contexts of a rate evaluation.
     */
    private final int[] left = new int[9];

    /**
     * The above contexts of the candidate being evaluated in the split search.
     */
    private final int[] aboveS = new int[9];

    /**
     * The left contexts of the candidate being evaluated in the split search.
     */
    private final int[] leftS = new int[9];

    /**
     * The above contexts after the best candidate of a split partition.
     */
    private final int[] aboveB = new int[9];

    /**
     * The left contexts after the best candidate of a split partition.
     */
    private final int[] leftB = new int[9];

    /**
     * The above contexts after the partitions of the split candidate decided so far.
     */
    private final int[] segmentAbove = new int[9];

    /**
     * The left contexts after the partitions of the split candidate decided so far.
     */
    private final int[] segmentLeft = new int[9];

    /**
     * The subblock vectors of the split candidate being evaluated.
     */
    private final int[] blockMvs = new int[16];

    /**
     * The partition modes of the split candidate being evaluated, by subblock.
     */
    private final int[] blockModes = new int[16];

    /**
     * The end of block of each subblock of the split candidate being evaluated.
     */
    private final int[] blockEobs = new int[16];

    /**
     * The vectors of the best split, by subblock.
     */
    private final int[] bestSplitMvs = new int[16];

    /**
     * The partition modes of the best split, by subblock.
     */
    private final int[] bestSplitModes = new int[16];

    /**
     * The end of block of each subblock of the best split.
     */
    private final int[] bestSplitEobs = new int[16];

    /**
     * The vector of each mode of a split partition: left, above, zero and new.
     */
    private final int[] partitionMvs = new int[4];

    /**
     * The best vectors of the 8x8 split, which start the searches of the 16x8 and 8x16 splits.
     */
    private final int[] savedMvp = new int[4];

    /**
     * The first diamond steps of the two halves of the 16x8 and 8x16 splits.
     */
    private final int[] savedStep = new int[2];

    /**
     * The chroma vectors of a split macroblock, one per 8x8 luma quarter.
     */
    private final int[] uvMvs = new int[4];

    /**
     * The subblock modes of the best macroblock mode, for {@code B_PRED}.
     */
    private final byte[] bestBModes = new byte[16];

    /**
     * The intermediate rows of the scalar sub-pixel variance.
     */
    private final int[] bilinearRows = new int[17 * 16];

    /**
     * The best split of the last {@link #pickSegmentation}: its configuration.
     */
    private int segmentNum;

    /**
     * The rate of the last {@link #pickSegmentation}'s best split.
     */
    private int segmentRate;

    /**
     * The luma token rate of the last {@link #pickSegmentation}'s best split.
     */
    private int segmentYRate;

    /**
     * The distortion of the last {@link #pickSegmentation}'s best split.
     */
    private int segmentDistortion;

    /**
     * The score of the best split found so far.
     */
    private int segmentRd;

    /**
     * The reference vector the split search codes new vectors against.
     */
    private int splitRefMv;

    /**
     * The vector the search of the next split partition starts from.
     */
    private int splitMvp;

    /**
     * The vector threshold of the split search: a partition already scoring below it searches
     * no new vector.
     */
    private int splitMvThresh;

    /**
     * The rate of the last rate-distortion evaluation: its luma tokens.
     */
    private int rateY;

    /**
     * The chroma token rate of the last evaluation.
     */
    private int rateUv;

    /**
     * The luma distortion of the last evaluation.
     */
    private int distortionY;

    /**
     * The chroma distortion of the last evaluation.
     */
    private int distortionUv;

    /**
     * The rate of the last subblock search.
     */
    private int subRate;

    /**
     * The luma token rate of the last subblock search.
     */
    private int subRateY;

    /**
     * The distortion of the last subblock search.
     */
    private int subDistortion;

    /**
     * Whether the last inter evaluation took the encode breakout.
     */
    private boolean skip;

    /**
     * Creates the decider of a coder.
     *
     * @param coder the macroblock coder whose state the decisions read and write
     */
    RdMbCoder(RtMbCoder coder) {
        this.c = coder;
        this.enc = coder.enc;
    }

    /**
     * Loads the entropy contexts of the current macroblock into {@link #above} and
     * {@link #left}.
     *
     * @param row the macroblock's row
     */
    private void loadContexts(RtRow row) {
        var ctxOff = c.mbCol * 9;
        for (var i = 0; i < 9; i++) {
            above[i] = enc.aboveContext[ctxOff + i];
            left[i] = row.left[i];
        }
    }

    /**
     * Returns the cost of the 16 luma blocks and the Y2 block of the current levels, from copies
     * of the contexts ({@code vp8_rdcost_mby}).
     *
     * @return the cost in 1/256 bit
     */
    private int lumaRate() {
        var costs = enc.rdCosts;
        var a0 = above[0];
        var a1 = above[1];
        var a2 = above[2];
        var a3 = above[3];
        var l0 = left[0];
        var l1 = left[1];
        var l2 = left[2];
        var l3 = left[3];
        var cost = 0;
        var levels = c.levels;
        var eob = c.eob;
        for (var b = 0; b < 16; b++) {
            var col = b & 3;
            var rowIdx = b >> 2;
            var a = col == 0 ? a0 : col == 1 ? a1 : col == 2 ? a2 : a3;
            var l = rowIdx == 0 ? l0 : rowIdx == 1 ? l1 : rowIdx == 2 ? l2 : l3;
            var e = eob[b];
            cost += costs.blockCost(levels, b, RtDsp.LEVEL_STRIDE, 1, e, 0, a + l);
            var nz = e > 1 ? 1 : 0;
            if (col == 0) {
                a0 = nz;
            } else if (col == 1) {
                a1 = nz;
            } else if (col == 2) {
                a2 = nz;
            } else {
                a3 = nz;
            }
            if (rowIdx == 0) {
                l0 = nz;
            } else if (rowIdx == 1) {
                l1 = nz;
            } else if (rowIdx == 2) {
                l2 = nz;
            } else {
                l3 = nz;
            }
        }
        cost += costs.blockCost(levels, 24, RtDsp.LEVEL_STRIDE, 0, eob[24], 1, above[8] + left[8]);
        return cost;
    }

    /**
     * Returns the cost of the eight chroma blocks of the current levels, from copies of the
     * contexts ({@code rd_cost_mbuv}).
     *
     * @return the cost in 1/256 bit
     */
    private int chromaRate() {
        var costs = enc.rdCosts;
        var aU0 = above[4];
        var aU1 = above[5];
        var aV0 = above[6];
        var aV1 = above[7];
        var lU0 = left[4];
        var lU1 = left[5];
        var lV0 = left[6];
        var lV1 = left[7];
        var cost = 0;
        for (var b = 16; b < 24; b++) {
            var v = b >= 20;
            var col = b & 1;
            var row = (b & 3) >> 1;
            int a;
            int l;
            if (v) {
                a = col == 0 ? aV0 : aV1;
                l = row == 0 ? lV0 : lV1;
            } else {
                a = col == 0 ? aU0 : aU1;
                l = row == 0 ? lU0 : lU1;
            }
            var e = c.eob[b];
            cost += costs.blockCost(c.levels, b, RtDsp.LEVEL_STRIDE, 0, e, 2, a + l);
            var nz = e > 0 ? 1 : 0;
            if (v) {
                if (col == 0) {
                    aV0 = nz;
                } else {
                    aV1 = nz;
                }
                if (row == 0) {
                    lV0 = nz;
                } else {
                    lV1 = nz;
                }
            } else {
                if (col == 0) {
                    aU0 = nz;
                } else {
                    aU1 = nz;
                }
                if (row == 0) {
                    lU0 = nz;
                } else {
                    lU1 = nz;
                }
            }
        }
        return cost;
    }

    /**
     * Transforms and quantizes the luma prediction of the current macroblock, which the frame
     * being coded holds, and returns its rate-distortion score ({@code macro_block_yrd}); sets
     * {@link #rateY} and {@link #distortionY}.
     *
     * @return the score of the luma tokens and distortion
     */
    private int macroBlockYrd() {
        var cur = enc.cur;
        var rt = c.rtDsp;
        rt.groupMacroblock(cur.y, c.yOff, cur.yStride, cur.u, cur.v, c.uvOff, cur.uvStride, c.predGroups);
        var coefs = c.coefGroups;
        var upper = rt.forwardDct8(c.srcGroups, 0, c.predGroups, 0, coefs, 0);
        var lower = rt.forwardDct8(c.srcGroups, RtDsp.GROUP, c.predGroups, RtDsp.GROUP, coefs, RtDsp.GROUP);
        System.arraycopy(coefs, 0, origLuma, 0, 2 * RtDsp.GROUP);
        c.forwardWht(coefs, c.y2Coef);
        System.arraycopy(c.y2Coef, 0, origY2, 0, 16);
        c.quantizeLumaGroup(0, upper);
        c.quantizeLumaGroup(1, lower);
        c.eob[24] = c.quantizeY2Block();
        long d = 0;
        for (var g = 0; g < 2; g++) {
            var zero = c.zeroGroups[g];
            var base = g * RtDsp.GROUP;
            for (var p = 8; p < RtDsp.GROUP; p++) {
                var diff = origLuma[base + p] - (zero ? 0 : coefs[base + p]);
                d += (long) diff * diff;
            }
        }
        d <<= 2;
        for (var k = 0; k < 16; k++) {
            var diff = origY2[k] - c.y2Coef[k];
            d += (long) diff * diff;
        }
        distortionY = (int) Math.min(d >> 4, Integer.MAX_VALUE);
        rateY = lumaRate();
        return c.rdCost(rateY, distortionY);
    }

    /**
     * Transforms and quantizes the chroma prediction of the current macroblock, which the frame
     * being coded holds, and returns its rate-distortion score ({@code rd_inter16x16_uv} without
     * the prediction); sets {@link #rateUv} and {@link #distortionUv}.
     *
     * @return the score
     */
    private int chromaRd() {
        var cur = enc.cur;
        RtMbCoder.groupChroma(cur.u, cur.v, c.uvOff, cur.uvStride, c.predGroups);
        var coefs = c.coefGroups;
        var max = c.rtDsp.forwardDct8(c.srcGroups, 2 * RtDsp.GROUP, c.predGroups, 2 * RtDsp.GROUP, coefs,
                2 * RtDsp.GROUP);
        System.arraycopy(coefs, 2 * RtDsp.GROUP, origChroma, 0, RtDsp.GROUP);
        c.quantizeChromaGroup(max);
        var zero = c.zeroGroups[2];
        long d = 0;
        for (var p = 0; p < RtDsp.GROUP; p++) {
            var diff = origChroma[p] - (zero ? 0 : coefs[2 * RtDsp.GROUP + p]);
            d += (long) diff * diff;
        }
        distortionUv = (int) Math.min(d / 4, Integer.MAX_VALUE);
        rateUv = chromaRate();
        return c.rdCost(rateUv, distortionUv);
    }

    /**
     * The chroma mode of the last {@link #pickIntraUv}.
     */
    private int uvIntraMode;

    /**
     * The rate of the last {@link #pickIntraUv}, mode included.
     */
    private int uvIntraRate;

    /**
     * The token rate of the last {@link #pickIntraUv}.
     */
    private int uvIntraRateTokenOnly;

    /**
     * The distortion of the last {@link #pickIntraUv}.
     */
    private int uvIntraDistortion;

    /**
     * The sum of the chroma ends of block of the last {@link #pickIntraUv}'s mode.
     */
    private int uvIntraTteob;

    /**
     * Chooses the chroma intra mode of the current macroblock ({@code rd_pick_intra_mbuv_mode}).
     */
    private void pickIntraUv() {
        var cur = enc.cur;
        var bestRd = Integer.MAX_VALUE;
        for (var mode = DC_PRED; mode <= TM_PRED; mode++) {
            IntraPredictor.predictBlock(mode, cur.u, c.uvOff, cur.uvStride, 8, c.mbRow > 0, c.mbCol > 0);
            IntraPredictor.predictBlock(mode, cur.v, c.uvOff, cur.uvStride, 8, c.mbRow > 0, c.mbCol > 0);
            chromaRd();
            var rate = rateUv + enc.uvModeCost[mode];
            var rd = c.rdCost(rate, distortionUv);
            if (rd < bestRd) {
                bestRd = rd;
                uvIntraMode = mode;
                uvIntraRate = rate;
                uvIntraRateTokenOnly = rateUv;
                uvIntraDistortion = distortionUv;
                var tteob = 0;
                for (var i = 16; i < 24; i++) {
                    tteob += c.eob[i];
                }
                uvIntraTteob = tteob;
            }
        }
        enc.mbUvMode[c.idx] = (byte) uvIntraMode;
    }

    /**
     * The luma mode of the last {@link #pickIntra16x16}.
     */
    private int intra16Mode;

    /**
     * The rate of the last {@link #pickIntra16x16}, mode included.
     */
    private int intra16Rate;

    /**
     * The token rate of the last {@link #pickIntra16x16}.
     */
    private int intra16RateY;

    /**
     * The distortion of the last {@link #pickIntra16x16}.
     */
    private int intra16Distortion;

    /**
     * Chooses the whole-block luma intra mode of the current macroblock
     * ({@code rd_pick_intra16x16mby_mode}).
     *
     * @return the score of the chosen mode
     */
    private int pickIntra16x16() {
        var cur = enc.cur;
        var bestRd = Integer.MAX_VALUE;
        for (var mode = DC_PRED; mode <= TM_PRED; mode++) {
            IntraPredictor.predictBlock(mode, cur.y, c.yOff, cur.yStride, 16, c.mbRow > 0, c.mbCol > 0);
            macroBlockYrd();
            var rate = rateY + enc.mbModeCost[mode];
            var rd = c.rdCost(rate, distortionY);
            if (rd < bestRd) {
                bestRd = rd;
                intra16Mode = mode;
                intra16Rate = rate;
                intra16RateY = rateY;
                intra16Distortion = distortionY;
            }
        }
        return bestRd;
    }

    /**
     * Chooses the mode of each 4x4 luma subblock of the current macroblock, coding and
     * reconstructing each in turn ({@code rd_pick_intra4x4mby_modes}); sets {@link #subRate},
     * {@link #subRateY} and {@link #subDistortion}, and leaves the levels in the coder's subblock
     * state.
     *
     * @param bestRd the score above which the search gives up
     * @return the score, or {@link Integer#MAX_VALUE} when the search gave up
     */
    private int pickIntra4x4(int bestRd) {
        var src = c.src;
        var bps = RtMbCoder.BPS;
        for (var r = 0; r < 16; r++) {
            System.arraycopy(c.srcY, c.srcYOff + r * c.srcYStride, src, r * bps, 16);
        }
        var cur = enc.cur;
        var y = cur.y;
        var yStride = cur.yStride;
        var aboveRight = c.yOff - yStride + 16;
        for (var r = 4; r < 16; r += 4) {
            System.arraycopy(y, aboveRight, y, aboveRight + r * yStride, 4);
        }
        var cost = enc.mbModeCost[B_PRED];
        var distortion = 0;
        var totRateY = 0;
        long totalRd = 0;
        var aCtx = aboveS;
        var lCtx = leftS;
        System.arraycopy(above, 0, aCtx, 0, 9);
        System.arraycopy(left, 0, lCtx, 0, 9);
        var keyFrame = enc.keyFrame;
        var costs = enc.rdCosts;
        var pred = c.pred;
        for (var i = 0; i < 16; i++) {
            var blockOff = c.yOff + (i >> 2) * 4 * yStride + (i & 3) * 4;
            var srcOff = (i >> 2) * 4 * bps + (i & 3) * 4;
            int[] modeCosts;
            int costBase;
            if (keyFrame) {
                modeCosts = enc.kfBmodeCosts;
                costBase = (c.aboveBlockMode(i) * 10 + c.leftBlockMode(i)) * 10;
            } else {
                modeCosts = enc.interBmodeCosts;
                costBase = 0;
            }
            var a = i & 3;
            var l = i >> 2;
            var ctx = aCtx[a] + lCtx[l];
            var best = Integer.MAX_VALUE;
            var bestMode = B_DC_PRED;
            var bestRate = 0;
            var bestRateY = 0;
            var bestDist = 0;
            var bestEob = 0;
            for (var mode = B_DC_PRED; mode <= B_HU_PRED; mode++) {
                IntraPredictor.predictSubblock(mode, y, blockOff, yStride);
                for (var r = 0; r < 4; r++) {
                    System.arraycopy(y, blockOff + r * yStride, pred, srcOff + r * bps, 4);
                }
                var coef = blockCoef;
                c.encDsp.fTransform(src, srcOff, pred, srcOff, coef, 0);
                System.arraycopy(coef, 0, blockOrig, 0, 16);
                var e = c.quantizeSubblock(coef, blockLevels, 0);
                var ratey = costs.blockCost(blockLevels, 0, 1, 0, e, 3, ctx);
                var rate = modeCosts[costBase + mode] + ratey;
                var d = 0;
                for (var k = 0; k < 16; k++) {
                    var diff = blockOrig[k] - coef[k];
                    d += diff * diff;
                }
                d >>= 2;
                var rd = c.rdCost(rate, d);
                if (rd < best) {
                    best = rd;
                    bestMode = mode;
                    bestRate = rate;
                    bestRateY = ratey;
                    bestDist = d;
                    bestEob = e;
                    System.arraycopy(coef, 0, bestBlockCoef, 0, 16);
                    System.arraycopy(blockLevels, 0, c.subLevels, i * 16, 16);
                    for (var r = 0; r < 4; r++) {
                        System.arraycopy(pred, srcOff + r * bps, bestBlockPred, r * 4, 4);
                    }
                }
            }
            for (var r = 0; r < 4; r++) {
                System.arraycopy(bestBlockPred, r * 4, y, blockOff + r * yStride, 4);
                System.arraycopy(bestBlockPred, r * 4, pred, srcOff + r * bps, 4);
            }
            if (bestEob > 1) {
                c.dsp.idctAdd(bestBlockCoef, 0, y, blockOff, yStride);
            } else {
                c.dsp.dcOnlyAdd(bestBlockCoef[0], y, blockOff, yStride);
            }
            c.subModes[i] = bestMode;
            c.subEobs[i] = bestEob;
            enc.bModes[c.idx * 16 + i] = (byte) bestMode;
            var nz = bestEob > 0 ? 1 : 0;
            aCtx[a] = nz;
            lCtx[l] = nz;
            totalRd += best;
            cost += bestRate;
            distortion += bestDist;
            totRateY += bestRateY;
            if (totalRd >= bestRd) {
                return Integer.MAX_VALUE;
            }
        }
        subRate = cost;
        subRateY = totRateY;
        subDistortion = distortion;
        return c.rdCost(cost, distortion);
    }

    /**
     * Chooses the intra modes of a key-frame macroblock ({@code vp8_rd_pick_intra_mode}).
     *
     * @param row the macroblock's row
     * @return the rate of the chosen modes, in 1/256 bit
     */
    int pickIntraKey(RtRow row) {
        var idx = c.idx;
        enc.mbRef[idx] = INTRA_FRAME;
        enc.mbMv[idx] = 0;
        loadContexts(row);
        pickIntraUv();
        var error16x16 = pickIntra16x16();
        var error4x4 = pickIntra4x4(error16x16);
        if (error4x4 < error16x16) {
            enc.mbMode[idx] = (byte) B_PRED;
            return uvIntraRate + subRate;
        }
        enc.mbMode[idx] = (byte) intra16Mode;
        return uvIntraRate + intra16Rate;
    }

    /**
     * Returns the cost of signaling an inter mode with the current neighbor counts, libvpx's
     * {@code vp8_cost_mv_ref}.
     *
     * @param mode the inter mode, {@code NEARESTMV} to {@code SPLITMV}
     * @return the cost in 1/256 bit
     */
    private int costMvRef(int mode) {
        if (mode != SPLITMV) {
            return c.costMvRef(mode);
        }
        var mc = Vp8Tables.MODE_CONTEXTS;
        var cnt = c.mdCounts;
        return RtTables.bitCost(mc[cnt[0] * 4], 1) + RtTables.bitCost(mc[cnt[1] * 4 + 1], 1)
                + RtTables.bitCost(mc[cnt[2] * 4 + 2], 1) + RtTables.bitCost(mc[cnt[3] * 4 + 3], 1);
    }

    /**
     * Returns the cost of a vector against a reference vector with a weight, libvpx's
     * {@code vp8_mv_bit_cost}.
     *
     * @param mv the packed vector
     * @param ref the packed reference vector
     * @param weight the weight, in 1/128
     * @return the cost in 1/256 bit
     */
    private int mvBitCost(int mv, int ref, int weight) {
        return (c.mvBitCost(mv, ref) * weight) >> 7;
    }

    /**
     * Chooses the modes of an inter-frame macroblock by their rate and distortion
     * ({@code vp8_rd_pick_inter_mode}).
     *
     * @param row the macroblock's row
     * @param stats the statistics of the coding thread
     * @return the rate of the chosen mode, in 1/256 bit
     */
    int pickInter(RtRow row, RtStats stats) {
        var idx = c.idx;
        loadContexts(row);
        var src = c.src;
        var bps = RtMbCoder.BPS;
        for (var r = 0; r < 16; r++) {
            System.arraycopy(c.srcY, c.srcYOff + r * c.srcYStride, src, r * bps, 16);
        }
        var refs = c.refMap;
        var n = 0;
        refs[n++] = INTRA_FRAME;
        for (var ref = 1; ref <= ALTREF_FRAME; ref++) {
            if (enc.refs[ref] != null) {
                refs[n++] = ref;
            }
        }
        for (; n < 4; n++) {
            refs[n] = -1;
        }
        var biasMask = enc.signBiasMask();
        var modeMv = modeMvs;
        java.util.Arrays.fill(modeMv, 0);
        var bestRefMv = 0;
        var bias = 0;
        if (refs[1] > 0) {
            var near = c.nearResult;
            NearMvs.find(enc.mbRef, enc.mbMv, enc.mbMode, idx, enc.miStride, biasMask, 0, c.mdCounts, near);
            c.nearestSb[0] = c.clampMv2(near[1]);
            c.nearSb[0] = c.clampMv2(near[2]);
            c.bestSb[0] = c.clampMv2(near[0]);
            if (biasMask != 0) {
                NearMvs.find(enc.mbRef, enc.mbMv, enc.mbMode, idx, enc.miStride, biasMask, 1, c.mdCounts, near);
                c.nearestSb[1] = c.clampMv2(near[1]);
                c.nearSb[1] = c.clampMv2(near[2]);
                c.bestSb[1] = c.clampMv2(near[0]);
            } else {
                c.nearestSb[1] = c.nearestSb[0];
                c.nearSb[1] = c.nearSb[0];
                c.bestSb[1] = c.bestSb[0];
            }
            modeMv[NEARESTMV] = c.nearestSb[0];
            modeMv[NEARMV] = c.nearSb[0];
            bestRefMv = c.bestSb[0];
        }
        var zeroAltRefOnly = enc.srcFrameAltRef && enc.forceZeroAltRef;
        var intraRdPenalty = 10 * Vp8Tables.DC_QLOOKUP[enc.baseQ];
        var thresholds = row.rdThreshes;
        var mults = row.rdThreshMult;
        var hits = row.modeTestHitCounts;
        var baseline = enc.rdBaselineThresh;
        var checkFreq = enc.modeCheckFreq;
        row.mbsTested++;
        var bestRd = Integer.MAX_VALUE;
        var bestYrd = Integer.MAX_VALUE;
        var bestIntraRd = Integer.MAX_VALUE;
        var bestModeIndex = 0;
        var bestMode = ZEROMV;
        var bestRef = INTRA_FRAME;
        var bestMv = 0;
        var bestUvMode = DC_PRED;
        var bestRate = 0;
        var bestIsSplit = false;
        var uvIntraDone = false;
        var sadDone = false;
        skip = false;
        var probSkip = enc.probSkipFalse;
        var skipNoCost = RtTables.bitCost(probSkip, 0);
        var skipCost = RtTables.bitCost(probSkip, 1) - skipNoCost;
        var cur = enc.cur;
        for (var modeIndex = 0; modeIndex < RtTables.MAX_MODES; modeIndex++) {
            var thisRef = refs[RtTables.REF_FRAME_ORDER[modeIndex]];
            if (bestRd <= thresholds[modeIndex] || thisRef < 0) {
                continue;
            }
            var thisMode = RtTables.MODE_ORDER[modeIndex];
            if (zeroAltRefOnly && (thisMode != ZEROMV || thisRef != ALTREF_FRAME)) {
                continue;
            }
            if (thisRef != INTRA_FRAME) {
                c.preFrame = enc.refs[thisRef];
                c.preY = c.preFrame.y;
                var refBias = (biasMask >> thisRef) & 1;
                if (refBias != bias) {
                    bias = refBias;
                    modeMv[NEARESTMV] = c.nearestSb[bias];
                    modeMv[NEARMV] = c.nearSb[bias];
                    bestRefMv = c.bestSb[bias];
                }
            }
            if (hits[modeIndex] != 0 && checkFreq[modeIndex] > 1
                    && row.mbsTested <= checkFreq[modeIndex] * hits[modeIndex]) {
                mults[modeIndex] = Math.min(mults[modeIndex] + 4, RtMbCoder.MAX_THRESHMULT);
                thresholds[modeIndex] = RtMbCoder.threshold(baseline[modeIndex], mults[modeIndex]);
                continue;
            }
            hits[modeIndex]++;
            if (c.regular) {
                c.setZbinBoost(c.modeBoost(thisRef, thisMode));
            }
            if (!uvIntraDone && thisRef == INTRA_FRAME) {
                pickIntraUv();
                uvIntraDone = true;
            }
            enc.mbMode[idx] = (byte) thisMode;
            enc.mbRef[idx] = (byte) thisRef;
            var rate2 = 0;
            var distortion2 = 0;
            var thisRd = Integer.MAX_VALUE;
            var disableSkip = false;
            var thisMv = 0;
            var tteobKnown = false;
            var tteob = 0;
            rateY = 0;
            rateUv = 0;
            distortionUv = 0;
            if (thisMode == B_PRED) {
                var tmpRd = pickIntra4x4(bestYrd);
                rate2 += subRate;
                distortion2 += subDistortion;
                rateY = subRateY;
                if (tmpRd < bestYrd) {
                    rate2 += uvIntraRate;
                    rateUv = uvIntraRateTokenOnly;
                    distortion2 += uvIntraDistortion;
                    distortionUv = uvIntraDistortion;
                    for (var i = 0; i < 16; i++) {
                        tteob += c.subEobs[i] > 0 ? 1 : 0;
                    }
                    tteob += uvIntraTteob;
                    tteobKnown = true;
                } else {
                    disableSkip = true;
                }
            } else if (thisMode == SPLITMV) {
                var refOrder = RtTables.REF_FRAME_ORDER[modeIndex];
                var splitThresh = refOrder == 1 ? thresholds[RtTables.THR_NEW1]
                        : refOrder == 2 ? thresholds[RtTables.THR_NEW2] : thresholds[RtTables.THR_NEW3];
                var tmpRd = pickSegmentation(bestRefMv, bestYrd, splitThresh);
                rate2 += segmentRate;
                distortion2 += segmentDistortion;
                rateY = segmentYRate;
                if (tmpRd < bestYrd) {
                    predictSplitChroma(bestSplitMvs);
                    chromaRd();
                    rate2 += rateUv;
                    distortion2 += distortionUv;
                    for (var i = 0; i < 16; i++) {
                        tteob += bestSplitEobs[i] > 0 ? 1 : 0;
                    }
                    for (var i = 16; i < 24; i++) {
                        tteob += c.eob[i];
                    }
                    tteobKnown = true;
                } else {
                    disableSkip = true;
                }
            } else if (thisMode <= TM_PRED) {
                IntraPredictor.predictBlock(thisMode, cur.y, c.yOff, cur.yStride, 16, c.mbRow > 0, c.mbCol > 0);
                macroBlockYrd();
                rate2 += rateY + enc.mbModeCost[thisMode] + uvIntraRate;
                distortion2 += distortionY + uvIntraDistortion;
                rateUv = uvIntraRateTokenOnly;
                distortionUv = uvIntraDistortion;
                tteob = c.eob[24];
                for (var i = 0; i < 16; i++) {
                    tteob += c.eob[i] > 1 ? 1 : 0;
                }
                tteob += uvIntraTteob;
                tteobKnown = true;
            } else {
                if (thisMode == NEWMV) {
                    if (!sadDone) {
                        c.calSad();
                        sadDone = true;
                    }
                    modeMv[NEWMV] = newMvSearch(thisRef, bestRefMv);
                    rate2 += mvBitCost(modeMv[NEWMV], bestRefMv, 96);
                }
                if (thisMode != ZEROMV) {
                    modeMv[thisMode] = c.clampMv2(modeMv[thisMode]);
                    if (thisMode != NEWMV && modeMv[thisMode] == 0) {
                        continue;
                    }
                }
                thisMv = thisMode == ZEROMV ? 0 : modeMv[thisMode];
                if ((Mv.row(thisMv) >> 3) < c.mvRowMin || (Mv.row(thisMv) >> 3) > c.mvRowMax
                        || (Mv.col(thisMv) >> 3) < c.mvColMin || (Mv.col(thisMv) >> 3) > c.mvColMax) {
                    continue;
                }
                enc.mbMv[idx] = thisMv;
                skip = false;
                c.predictInter();
                if (enc.encodeBreakout > 0 && breakout(thisMv)) {
                    skip = true;
                    disableSkip = true;
                    rate2 = 500;
                    distortion2 = breakoutDistortion;
                    rateUv = 0;
                    distortionUv = breakoutChroma;
                    thisRd = c.rdCost(rate2, distortion2);
                } else {
                    rate2 += costMvRef(thisMode);
                    macroBlockYrd();
                    rate2 += rateY;
                    distortion2 += distortionY;
                    chromaRd();
                    rate2 += rateUv;
                    distortion2 += distortionUv;
                    tteob = c.eob[24];
                    for (var i = 0; i < 16; i++) {
                        tteob += c.eob[i] > 1 ? 1 : 0;
                    }
                    for (var i = 16; i < 24; i++) {
                        tteob += c.eob[i];
                    }
                    tteobKnown = true;
                }
            }
            var otherCost = skipNoCost;
            rate2 += otherCost + enc.refFrameCost[thisRef];
            if (!disableSkip) {
                if (tteobKnown && tteob == 0) {
                    rate2 -= rateY + rateUv;
                    rateUv = 0;
                    if (probSkip != 0) {
                        rate2 += skipCost;
                        otherCost += skipCost;
                    }
                }
                thisRd = c.rdCost(rate2, distortion2);
                if (thisRd < Integer.MAX_VALUE && thisRef == INTRA_FRAME) {
                    thisRd += intraRdPenalty;
                }
            }
            if (thisRef == INTRA_FRAME && thisRd < bestIntraRd) {
                bestIntraRd = thisRd;
            }
            if (thisRd < bestRd || skip) {
                bestModeIndex = modeIndex;
                bestRate = rate2;
                bestRd = thisRd;
                bestMode = thisMode;
                bestRef = thisRef;
                bestMv = thisMode <= B_PRED ? 0 : thisMv;
                bestUvMode = uvIntraMode;
                bestIsSplit = thisMode == SPLITMV;
                bestYrd = c.rdCost(rate2 - rateUv - otherCost - enc.refFrameCost[thisRef], distortion2 - distortionUv);
                if (thisMode == B_PRED) {
                    System.arraycopy(enc.bModes, idx * 16, bestBModes, 0, 16);
                }
                if (bestIsSplit) {
                    saveSplit();
                }
                mults[modeIndex] = mults[modeIndex] >= RtMbCoder.MIN_THRESHMULT + 2 ? mults[modeIndex] - 2
                        : RtMbCoder.MIN_THRESHMULT;
            } else {
                mults[modeIndex] = Math.min(mults[modeIndex] + 4, RtMbCoder.MAX_THRESHMULT);
            }
            thresholds[modeIndex] = RtMbCoder.threshold(baseline[modeIndex], mults[modeIndex]);
            if (skip) {
                break;
            }
        }
        if (baseline[bestModeIndex] > 0 && baseline[bestModeIndex] < (Integer.MAX_VALUE >> 2)) {
            var adjustment = mults[bestModeIndex] >> 2;
            mults[bestModeIndex] = mults[bestModeIndex] >= RtMbCoder.MIN_THRESHMULT + adjustment
                    ? mults[bestModeIndex] - adjustment : RtMbCoder.MIN_THRESHMULT;
            thresholds[bestModeIndex] = RtMbCoder.threshold(baseline[bestModeIndex], mults[bestModeIndex]);
        }
        if (enc.srcFrameAltRef && (bestMode != ZEROMV || bestRef != ALTREF_FRAME)) {
            enc.mbMode[idx] = (byte) ZEROMV;
            enc.mbRef[idx] = (byte) ALTREF_FRAME;
            enc.mbMv[idx] = 0;
            enc.mbUvMode[idx] = (byte) DC_PRED;
            skip = false;
            return bestRate;
        }
        enc.mbMode[idx] = (byte) bestMode;
        enc.mbRef[idx] = (byte) bestRef;
        if (bestRef == INTRA_FRAME) {
            enc.mbMv[idx] = 0;
            enc.mbUvMode[idx] = (byte) bestUvMode;
            if (bestMode == B_PRED) {
                System.arraycopy(bestBModes, 0, enc.bModes, idx * 16, 16);
            }
            skip = false;
            return bestRate;
        }
        var best = c.bestSb[(biasMask >> bestRef) & 1];
        if (bestIsSplit) {
            restoreSplit();
            var parts = MBSPLIT_COUNT[segmentNum];
            for (var j = 0; j < parts; j++) {
                if (enc.partModes[idx * 16 + j] == NEW4X4) {
                    countMv(stats, enc.bMvs[idx * 16 + Vp8Tables.MBSPLIT_OFFSET[segmentNum * 16 + j]], best);
                }
            }
            skip = false;
        } else {
            enc.mbMv[idx] = bestMv;
            if (bestMode == NEWMV) {
                countMv(stats, bestMv, best);
            }
        }
        return bestRate;
    }

    /**
     * Returns whether the last inter evaluation took the encode breakout, which then codes the
     * macroblock without residual.
     *
     * @return whether it did
     */
    boolean skipped() {
        return skip;
    }

    /**
     * The distortion of the last encode breakout.
     */
    private int breakoutDistortion;

    /**
     * The chroma distortion of the last encode breakout.
     */
    private int breakoutChroma;

    /**
     * Returns whether a predicted inter mode is good enough to code without residual, the encode
     * breakout of {@code evaluate_inter_mode_rd}; sets {@link #breakoutDistortion} and
     * {@link #breakoutChroma}.
     *
     * @param mv the packed vector of the mode
     * @return whether it is
     */
    private boolean breakout(int mv) {
        var step = c.y1Matrix.q[1];
        var threshold = Math.max((step * step) >> 4, enc.encodeBreakout);
        var cur = enc.cur;
        var packed = c.rtDsp.variance16(c.srcY, c.srcYOff, c.srcYStride, cur.y, c.yOff, cur.yStride);
        var sse = RtDsp.sse(packed);
        var variance = RtDsp.variance256(packed);
        if (Integer.compareUnsigned(sse, threshold) >= 0) {
            return false;
        }
        var q2dc = c.y2Matrix.q[0];
        if (sse - variance < (q2dc * q2dc) >> 4 || (sse / 2 > variance && sse - variance < 64)) {
            var chroma = (int) Math.min(c.chromaSse(mv), Integer.MAX_VALUE);
            if (2L * chroma < threshold) {
                breakoutDistortion = sse + chroma;
                breakoutChroma = chroma;
                return true;
            }
        }
        return false;
    }

    /**
     * Counts a new vector's difference from its reference vector for the vector probability
     * updates ({@code rd_update_mvcount}).
     *
     * @param stats the statistics of the coding thread
     * @param mv the packed vector
     * @param ref the packed reference vector
     */
    private static void countMv(RtStats stats, int mv, int ref) {
        var rowIdx = RtStats.MV_MAX + ((Mv.row(mv) - Mv.row(ref)) >> 1);
        var colIdx = RtStats.MV_MAX + ((Mv.col(mv) - Mv.col(ref)) >> 1);
        if (rowIdx >= 0 && rowIdx < RtStats.MV_VALS && colIdx >= 0 && colIdx < RtStats.MV_VALS) {
            stats.mvCounts[rowIdx]++;
            stats.mvCounts[RtStats.MV_VALS + colIdx]++;
        }
    }

    /**
     * The vector of each mode of the macroblock being searched, by mode.
     */
    private final int[] modeMvs = new int[10];

    /**
     * Searches a new vector for the current macroblock against the reference of the coder: the
     * diamond steps from the predicted vector, a final one-pixel refinement and the fractional
     * refinement, the {@code NEWMV} case of {@code vp8_rd_pick_inter_mode}.
     *
     * @param ref the reference searched
     * @param bestRefMv the packed reference vector new vectors are coded against
     * @return the packed vector in 1/8 pixel
     */
    private int newMvSearch(int ref, int bestRefMv) {
        var sadPerBit = enc.sadPerBit16;
        var stepParam = enc.firstStep;
        var colMin = ((Mv.col(bestRefMv) + 7) >> 3) - RtMbCoder.MAX_FULL_PEL_VAL;
        var rowMin = ((Mv.row(bestRefMv) + 7) >> 3) - RtMbCoder.MAX_FULL_PEL_VAL;
        var colMax = (Mv.col(bestRefMv) >> 3) + RtMbCoder.MAX_FULL_PEL_VAL;
        var rowMax = (Mv.row(bestRefMv) >> 3) + RtMbCoder.MAX_FULL_PEL_VAL;
        var mvp = c.mvPred(ref);
        var sr = c.predictedRange;
        var savedColMin = c.mvColMin;
        var savedColMax = c.mvColMax;
        var savedRowMin = c.mvRowMin;
        var savedRowMax = c.mvRowMax;
        c.mvColMin = Math.max(c.mvColMin, colMin);
        c.mvColMax = Math.min(c.mvColMax, colMax);
        c.mvRowMin = Math.max(c.mvRowMin, rowMin);
        c.mvRowMax = Math.min(c.mvRowMax, rowMax);
        if (sr > stepParam) {
            stepParam = sr;
        }
        var mvpRow = Mv.row(mvp) >> 3;
        var mvpCol = Mv.col(mvp) >> 3;
        var bestSme = c.diamondSearch(mvpRow, mvpCol, stepParam, sadPerBit, bestRefMv);
        var best = c.searchMv;
        var furtherSteps = MAX_MVSEARCH_STEPS - 1 - stepParam;
        var n = c.diamondNum00;
        var num00 = 0;
        var refine = n <= furtherSteps;
        while (n < furtherSteps) {
            n++;
            if (num00 != 0) {
                num00--;
            } else {
                var sme = c.diamondSearch(mvpRow, mvpCol, stepParam + n, sadPerBit, bestRefMv);
                num00 = c.diamondNum00;
                if (num00 > furtherSteps - n) {
                    refine = false;
                }
                if (sme < bestSme) {
                    bestSme = sme;
                    best = c.searchMv;
                }
            }
        }
        if (refine) {
            var sme = refiningSearch(best, sadPerBit, 8, bestRefMv);
            if (sme < bestSme) {
                bestSme = sme;
                best = refined;
            }
        }
        c.mvColMin = savedColMin;
        c.mvColMax = savedColMax;
        c.mvRowMin = savedRowMin;
        c.mvRowMax = savedRowMax;
        c.searchMv = best;
        c.subpelIterative(bestRefMv);
        return c.searchMv;
    }

    /**
     * The vector found by the last {@link #refiningSearch}, packed in full pixels.
     */
    private int refined;

    /**
     * Refines a full-pixel vector by one-pixel steps to the four neighbors while they improve,
     * libvpx's {@code vp8_refining_search_sad_c}; sets {@link #refined}.
     *
     * @param start the packed full-pixel vector to start from
     * @param sadPerBit the cost of a vector bit in SAD units
     * @param range the most steps
     * @param center the packed reference vector in 1/8 pixel the costs are measured from
     * @return the variance at the result plus its vector cost
     */
    private int refiningSearch(int start, int sadPerBit, int range, int center) {
        var centerRow = Mv.row(center) >> 3;
        var centerCol = Mv.col(center) >> 3;
        var br = Mv.row(start);
        var bc = Mv.col(start);
        var bestSad = c.sadAt(br, bc) + c.mvSadErrCost(br, bc, centerRow, centerCol, sadPerBit);
        for (var i = 0; i < range; i++) {
            var bestSite = -1;
            for (var j = 0; j < 4; j++) {
                var r = br + REFINE_NEIGHBORS[2 * j];
                var col = bc + REFINE_NEIGHBORS[2 * j + 1];
                if (col > c.mvColMin && col < c.mvColMax && r > c.mvRowMin && r < c.mvRowMax) {
                    var sad = c.sadAt(r, col);
                    if (sad < bestSad) {
                        sad += c.mvSadErrCost(r, col, centerRow, centerCol, sadPerBit);
                        if (sad < bestSad) {
                            bestSad = sad;
                            bestSite = j;
                        }
                    }
                }
            }
            if (bestSite == -1) {
                break;
            }
            br += REFINE_NEIGHBORS[2 * bestSite];
            bc += REFINE_NEIGHBORS[2 * bestSite + 1];
        }
        refined = Mv.pack(br, bc);
        var stride = enc.cur.yStride;
        var variance = RtDsp.variance256(c.rtDsp.variance16(c.srcY, c.srcYOff, c.srcYStride, c.preY,
                c.yOff + br * stride + bc, stride));
        return variance + c.mvErrCost(Mv.pack(br * 8, bc * 8), center);
    }

    /**
     * Searches the best split of the current macroblock against the coder's reference
     * ({@code vp8_rd_pick_best_mbsegmentation}). The good-quality speeds search the 8x8 quarters
     * first, then the halves from the quarters' vectors, then the 4x4 subblocks when the quarters
     * win or the speed always tries them; the best quality searches the halves, the quarters and
     * the 4x4 subblocks in that order, each from the reference vector.
     *
     * @param bestRefMv the packed reference vector new vectors are coded against
     * @param bestRd the score of the best mode so far, which a split must beat
     * @param mvThresh the vector threshold of the split search
     * @return the score of the best split, or {@code bestRd} when none beats it
     */
    private int pickSegmentation(int bestRefMv, int bestRd, int mvThresh) {
        segmentRd = bestRd;
        segmentNum = -1;
        segmentRate = 0;
        segmentYRate = 0;
        segmentDistortion = 0;
        splitRefMv = bestRefMv;
        splitMvp = bestRefMv;
        splitMvThresh = mvThresh;
        java.util.Arrays.fill(bestSplitModes, ZERO4X4);
        java.util.Arrays.fill(bestSplitMvs, 0);
        java.util.Arrays.fill(bestSplitEobs, 0);
        if (enc.best) {
            checkSegment(SPLIT_16X8);
            checkSegment(SPLIT_8X16);
            checkSegment(SPLIT_8X8);
            checkSegment(SPLIT_4X4);
            return segmentRd;
        }
        checkSegment(SPLIT_8X8);
        if (segmentRd < bestRd) {
            var colMin = ((Mv.col(bestRefMv) + 7) >> 3) - RtMbCoder.MAX_FULL_PEL_VAL;
            var rowMin = ((Mv.row(bestRefMv) + 7) >> 3) - RtMbCoder.MAX_FULL_PEL_VAL;
            var colMax = (Mv.col(bestRefMv) >> 3) + RtMbCoder.MAX_FULL_PEL_VAL;
            var rowMax = (Mv.row(bestRefMv) >> 3) + RtMbCoder.MAX_FULL_PEL_VAL;
            var savedColMin = c.mvColMin;
            var savedColMax = c.mvColMax;
            var savedRowMin = c.mvRowMin;
            var savedRowMax = c.mvRowMax;
            c.mvColMin = Math.max(c.mvColMin, colMin);
            c.mvColMax = Math.min(c.mvColMax, colMax);
            c.mvRowMin = Math.max(c.mvRowMin, rowMin);
            c.mvRowMax = Math.min(c.mvRowMax, rowMax);
            savedMvp[0] = bestSplitMvs[0];
            savedMvp[1] = bestSplitMvs[2];
            savedMvp[2] = bestSplitMvs[8];
            savedMvp[3] = bestSplitMvs[10];
            savedStep[0] = stepParam(savedMvp[0], savedMvp[2]);
            savedStep[1] = stepParam(savedMvp[1], savedMvp[3]);
            checkSegment(SPLIT_8X16);
            savedStep[0] = stepParam(savedMvp[0], savedMvp[1]);
            savedStep[1] = stepParam(savedMvp[2], savedMvp[3]);
            checkSegment(SPLIT_16X8);
            if (enc.noSkipBlock4x4Search || segmentNum == SPLIT_8X8) {
                splitMvp = savedMvp[0];
                checkSegment(SPLIT_4X4);
            }
            c.mvColMin = savedColMin;
            c.mvColMax = savedColMax;
            c.mvRowMin = savedRowMin;
            c.mvRowMax = savedRowMax;
        }
        return segmentRd;
    }

    /**
     * Returns the first diamond step of a search between two vectors, from their distance
     * ({@code vp8_cal_step_param}).
     *
     * @param a the first packed vector
     * @param b the second packed vector
     * @return the step parameter
     */
    private static int stepParam(int a, int b) {
        var sr = Math.max(Math.abs(Mv.row(a) - Mv.row(b)) >> 3, Math.abs(Mv.col(a) - Mv.col(b)) >> 3);
        sr = Math.clamp(sr, 1, MAX_FIRST_STEP);
        var step = 0;
        while ((sr >>= 1) != 0) {
            step++;
        }
        return MAX_MVSEARCH_STEPS - 1 - step;
    }

    /**
     * Evaluates one split configuration, choosing each partition's mode and vector, and keeps it
     * when it beats the best split so far ({@code rd_check_segment}).
     *
     * @param s the split configuration
     */
    private void checkSegment(int s) {
        System.arraycopy(above, 0, aboveB, 0, 9);
        System.arraycopy(left, 0, leftB, 0, 9);
        var labelCount = MBSPLIT_COUNT[s];
        var labelMvThresh = splitMvThresh / labelCount;
        var rate = enc.mbSplitCost[s] + costMvRef(SPLITMV);
        var segmentScore = c.rdCost(rate, 0);
        var br = rate;
        var bd = 0;
        var yRate = 0;
        java.util.Arrays.fill(blockMvs, 0);
        java.util.Arrays.fill(blockModes, ZERO4X4);
        java.util.Arrays.fill(blockEobs, 0);
        var currentAbove = aboveS;
        var currentLeft = leftS;
        var contextAbove = segmentAbove;
        var contextLeft = segmentLeft;
        System.arraycopy(above, 0, contextAbove, 0, 9);
        System.arraycopy(left, 0, contextLeft, 0, 9);
        for (var label = 0; label < labelCount; label++) {
            var mvs = partitionMvs;
            java.util.Arrays.fill(mvs, 0);
            var bestLabelRd = Integer.MAX_VALUE;
            var selected = ZERO4X4;
            var labelRate = 0;
            var labelDistortion = 0;
            var labelYRate = 0;
            for (var mode = LEFT4X4; mode <= NEW4X4; mode++) {
                System.arraycopy(contextAbove, 0, currentAbove, 0, 9);
                System.arraycopy(contextLeft, 0, currentLeft, 0, 9);
                if (mode == NEW4X4) {
                    if (bestLabelRd < labelMvThresh) {
                        break;
                    }
                    var stepParam = 0;
                    var mvp = splitMvp;
                    if (!enc.best && (s == SPLIT_8X16 || s == SPLIT_16X8)) {
                        mvp = savedMvp[label];
                        if (label == 1 && s == SPLIT_16X8) {
                            mvp = savedMvp[2];
                        }
                        stepParam = savedStep[label];
                    }
                    if (!enc.best && s == SPLIT_4X4 && label > 0) {
                        mvp = blockMvs[label - 1];
                        if (label == 4 || label == 8 || label == 12) {
                            mvp = blockMvs[label - 4];
                        }
                        stepParam = 2;
                    }
                    var first = Vp8Tables.MBSPLIT_OFFSET[s * 16 + label];
                    mvs[NEW4X4] = splitSearch(s, first, mvp, stepParam);
                }
                var thisRate = labels2mode(s, label, mode, mvs);
                var mv = mvs[mode];
                if ((Mv.row(mv) >> 3) < c.mvRowMin || (Mv.row(mv) >> 3) > c.mvRowMax
                        || (Mv.col(mv) >> 3) < c.mvColMin || (Mv.col(mv) >> 3) > c.mvColMax) {
                    continue;
                }
                var distortion = encodeSegment(s, label) / 4;
                var ratey = segmentRate(s, label, currentAbove, currentLeft);
                thisRate += ratey;
                var thisRd = c.rdCost(thisRate, distortion);
                if (thisRd < bestLabelRd) {
                    labelRate = thisRate;
                    labelDistortion = distortion;
                    labelYRate = ratey;
                    selected = mode;
                    bestLabelRd = thisRd;
                    System.arraycopy(currentAbove, 0, aboveB, 0, 9);
                    System.arraycopy(currentLeft, 0, leftB, 0, 9);
                }
            }
            System.arraycopy(aboveB, 0, contextAbove, 0, 9);
            System.arraycopy(leftB, 0, contextLeft, 0, 9);
            labels2mode(s, label, selected, mvs);
            encodeSegment(s, label);
            br += labelRate;
            bd += labelDistortion;
            yRate += labelYRate;
            segmentScore = bestLabelRd == Integer.MAX_VALUE ? Integer.MAX_VALUE : segmentScore + bestLabelRd;
            if (segmentScore >= segmentRd || segmentScore < 0) {
                break;
            }
        }
        if (segmentScore < segmentRd && segmentScore >= 0) {
            segmentRate = br;
            segmentDistortion = bd;
            segmentYRate = yRate;
            segmentRd = segmentScore;
            segmentNum = s;
            System.arraycopy(blockMvs, 0, bestSplitMvs, 0, 16);
            System.arraycopy(blockModes, 0, bestSplitModes, 0, 16);
            System.arraycopy(blockEobs, 0, bestSplitEobs, 0, 16);
        }
    }

    /**
     * Sets the vectors and modes of the subblocks of one partition from a partition mode and
     * returns the mode's cost ({@code labels2mode}): the partition's first subblock takes the
     * mode, its other subblocks the vector of their left or above neighbor in the partition.
     *
     * @param s the split configuration
     * @param label the partition
     * @param mode the partition mode
     * @param mvs the vector of each mode, the left, above and zero entries set here
     * @return the cost of the mode and of a new vector, in 1/256 bit
     */
    private int labels2mode(int s, int label, int mode, int[] mvs) {
        var labelBase = s * 16;
        var cost = 0;
        var thisMv = mvs[mode];
        for (var i = 0; i < 16; i++) {
            if (MBSPLITS[labelBase + i] != label) {
                continue;
            }
            var row = i >> 2;
            var col = i & 3;
            int m;
            if (col != 0 && MBSPLITS[labelBase + i - 1] == label) {
                m = LEFT4X4;
            } else if (row != 0 && MBSPLITS[labelBase + i - 4] == label) {
                m = ABOVE4X4;
            } else {
                m = mode;
                switch (mode) {
                    case NEW4X4 -> cost = mvBitCost(thisMv, splitRefMv, 102);
                    case LEFT4X4 -> thisMv = col != 0 ? blockMvs[i - 1] : leftBlockMv(i);
                    case ABOVE4X4 -> thisMv = row != 0 ? blockMvs[i - 4] : aboveBlockMv(i);
                    default -> thisMv = 0;
                }
                if (m == ABOVE4X4) {
                    var leftMv = col != 0 ? blockMvs[i - 1] : leftBlockMv(i);
                    if (leftMv == thisMv) {
                        m = LEFT4X4;
                    }
                }
                mvs[mode] = thisMv;
                cost += enc.subMvRefCost[m];
            }
            blockMvs[i] = thisMv;
            blockModes[i] = m;
        }
        return cost;
    }

    /**
     * Returns the vector of the subblock left of a left-column subblock, in the macroblock to the
     * left ({@code left_block_mv}).
     *
     * @param i the subblock
     * @return the packed vector
     */
    private int leftBlockMv(int i) {
        var left = c.idx - 1;
        return enc.mbMode[left] != SPLITMV ? enc.mbMv[left] : enc.bMvs[left * 16 + i + 3];
    }

    /**
     * Returns the vector of the subblock above a top-row subblock, in the macroblock above
     * ({@code above_block_mv}).
     *
     * @param i the subblock
     * @return the packed vector
     */
    private int aboveBlockMv(int i) {
        var above = c.idx - enc.miStride;
        return enc.mbMode[above] != SPLITMV ? enc.mbMv[above] : enc.bMvs[above * 16 + i + 12];
    }

    /**
     * The levels of the subblocks of the split candidate being evaluated, zigzag order, 16 per
     * subblock.
     */
    private final short[] splitLevels = new short[16 * 16];

    /**
     * Predicts, transforms and quantizes the subblocks of one partition with their vectors and
     * returns their summed squared coefficient error ({@code vp8_encode_inter_mb_segment}).
     *
     * @param s the split configuration
     * @param label the partition
     * @return the error
     */
    private int encodeSegment(int s, int label) {
        var labelBase = s * 16;
        var distortion = 0;
        var bps = RtMbCoder.BPS;
        var stride = enc.cur.yStride;
        for (var i = 0; i < 16; i++) {
            if (MBSPLITS[labelBase + i] != label) {
                continue;
            }
            var bpsOff = (i >> 2) * 4 * bps + (i & 3) * 4;
            var off = c.yOff + (i >> 2) * 4 * stride + (i & 3) * 4;
            var mv = blockMvs[i];
            predict(c.preY, off + (Mv.row(mv) >> 3) * stride + (Mv.col(mv) >> 3), stride, c.pred, bpsOff, bps, 4, 4,
                    Mv.col(mv) & 7, Mv.row(mv) & 7);
            var coef = blockCoef;
            c.encDsp.fTransform(c.src, bpsOff, c.pred, bpsOff, coef, 0);
            System.arraycopy(coef, 0, blockOrig, 0, 16);
            var e = c.quantizeSubblock(coef, splitLevels, i * 16);
            blockEobs[i] = e;
            for (var k = 0; k < 16; k++) {
                var diff = blockOrig[k] - coef[k];
                distortion += diff * diff;
            }
        }
        return distortion;
    }

    /**
     * Returns the token cost of the subblocks of one partition, updating the contexts
     * ({@code rdcost_mbsegment_y}).
     *
     * @param s the split configuration
     * @param label the partition
     * @param aboveCtx the above contexts, updated
     * @param leftCtx the left contexts, updated
     * @return the cost in 1/256 bit
     */
    private int segmentRate(int s, int label, int[] aboveCtx, int[] leftCtx) {
        var labelBase = s * 16;
        var cost = 0;
        var costs = enc.rdCosts;
        for (var i = 0; i < 16; i++) {
            if (MBSPLITS[labelBase + i] != label) {
                continue;
            }
            var a = i & 3;
            var l = i >> 2;
            var e = blockEobs[i];
            cost += costs.blockCost(splitLevels, i * 16, 1, 0, e, 3, aboveCtx[a] + leftCtx[l]);
            var nz = e > 0 ? 1 : 0;
            aboveCtx[a] = nz;
            leftCtx[l] = nz;
        }
        return cost;
    }

    /**
     * Searches a new vector of one split partition: diamond steps from a predicted vector, at the
     * best quality an exhaustive search around the predicted vector when the diamond's match stays
     * poor, then the fractional refinement, with the metrics of the partition's size.
     *
     * @param s the split configuration
     * @param first the partition's first subblock
     * @param mvp the packed predicted vector in 1/8 pixel
     * @param stepParam the first diamond step
     * @return the packed vector in 1/8 pixel
     */
    private int splitSearch(int s, int first, int mvp, int stepParam) {
        var w = SPLIT_WIDTH[s];
        var h = SPLIT_HEIGHT[s];
        var sadPerBit = enc.sadPerBit4;
        var startRow = Mv.row(mvp) >> 3;
        var startCol = Mv.col(mvp) >> 3;
        var furtherSteps = MAX_MVSEARCH_STEPS - 1 - stepParam;
        var bestSme = blockDiamond(first, w, h, startRow, startCol, stepParam, sadPerBit);
        var best = diamondMv;
        var n = diamondNum00;
        var num00 = 0;
        while (n < furtherSteps) {
            n++;
            if (num00 != 0) {
                num00--;
            } else {
                var sme = blockDiamond(first, w, h, startRow, startCol, stepParam + n, sadPerBit);
                num00 = diamondNum00;
                if (sme < bestSme) {
                    bestSme = sme;
                    best = diamondMv;
                }
            }
        }
        if (enc.best && (bestSme >> SEGMENTATION_TO_SSESHIFT[s]) > FULL_SEARCH_THRESHOLD) {
            var sme = blockFullSearch(first, w, h, Math.clamp(startRow, c.mvRowMin, c.mvRowMax),
                    Math.clamp(startCol, c.mvColMin, c.mvColMax), sadPerBit);
            if (sme < bestSme) {
                best = diamondMv;
            }
        }
        return blockSubpel(first, w, h, best);
    }

    /**
     * Searches every full-pixel vector of a partition within {@link #FULL_SEARCH_DISTANCE} of a
     * start, rows and columns from the start less the distance up to, not including, the start
     * plus the distance, for the lowest SAD plus vector cost, libvpx's
     * {@code vp8_full_search_sad_c} with the metrics of the partition's size; sets
     * {@link #diamondMv}.
     *
     * @param first the partition's first subblock
     * @param w the partition width
     * @param h the partition height
     * @param startRow the start row in full pixels, inside the vector limits
     * @param startCol the start column in full pixels, inside the vector limits
     * @param sadPerBit the cost of a vector bit in SAD units
     * @return the variance at the result plus its vector cost
     */
    private int blockFullSearch(int first, int w, int h, int startRow, int startCol, int sadPerBit) {
        var centerRow = Mv.row(splitRefMv) >> 3;
        var centerCol = Mv.col(splitRefMv) >> 3;
        var stride = enc.cur.yStride;
        var srcOff = c.srcYOff + (first >> 2) * 4 * c.srcYStride + (first & 3) * 4;
        var refOff = c.yOff + (first >> 2) * 4 * stride + (first & 3) * 4;
        var bestRow = startRow;
        var bestCol = startCol;
        var bestSad = sad(srcOff, refOff + bestRow * stride + bestCol, stride, w, h)
                + c.mvSadErrCost(bestRow, bestCol, centerRow, centerCol, sadPerBit);
        var rowMin = Math.max(startRow - FULL_SEARCH_DISTANCE, c.mvRowMin);
        var rowMax = Math.min(startRow + FULL_SEARCH_DISTANCE, c.mvRowMax);
        var colMin = Math.max(startCol - FULL_SEARCH_DISTANCE, c.mvColMin);
        var colMax = Math.min(startCol + FULL_SEARCH_DISTANCE, c.mvColMax);
        for (var r = rowMin; r < rowMax; r++) {
            for (var col = colMin; col < colMax; col++) {
                var sad = sad(srcOff, refOff + r * stride + col, stride, w, h);
                if (sad < bestSad) {
                    sad += c.mvSadErrCost(r, col, centerRow, centerCol, sadPerBit);
                    if (sad < bestSad) {
                        bestSad = sad;
                        bestRow = r;
                        bestCol = col;
                    }
                }
            }
        }
        diamondMv = Mv.pack(bestRow, bestCol);
        var variance = variance(c.preY, refOff + bestRow * stride + bestCol, stride, srcOff, w, h);
        return variance + c.mvErrCost(Mv.pack(bestRow * 8, bestCol * 8), splitRefMv);
    }

    /**
     * The vector of the last {@link #blockDiamond}, packed in full pixels.
     */
    private int diamondMv;

    /**
     * The number of steps of the last {@link #blockDiamond} that did not move from its start.
     */
    private int diamondNum00;

    /**
     * Searches the full-pixel vector of a partition with the lowest SAD plus vector cost by
     * shrinking eight-point steps, libvpx's {@code vp8_diamond_search_sad_c} with the metrics of
     * the partition's size; sets {@link #diamondMv} and {@link #diamondNum00}.
     *
     * @param first the partition's first subblock
     * @param w the partition width
     * @param h the partition height
     * @param startRow the start row in full pixels
     * @param startCol the start column in full pixels
     * @param stepParam the index of the first step
     * @param sadPerBit the cost of a vector bit in SAD units
     * @return the variance at the result plus its vector cost
     */
    private int blockDiamond(int first, int w, int h, int startRow, int startCol, int stepParam, int sadPerBit) {
        var centerRow = Mv.row(splitRefMv) >> 3;
        var centerCol = Mv.col(splitRefMv) >> 3;
        var refRow = Math.clamp(startRow, c.mvRowMin, c.mvRowMax);
        var refCol = Math.clamp(startCol, c.mvColMin, c.mvColMax);
        diamondNum00 = 0;
        var stride = enc.cur.yStride;
        var srcOff = c.srcYOff + (first >> 2) * 4 * c.srcYStride + (first & 3) * 4;
        var refOff = c.yOff + (first >> 2) * 4 * stride + (first & 3) * 4;
        var bestRow = refRow;
        var bestCol = refCol;
        var bestSad = sad(srcOff, refOff + bestRow * stride + bestCol, stride, w, h)
                + c.mvSadErrCost(bestRow, bestCol, centerRow, centerCol, sadPerBit);
        var totSteps = MAX_MVSEARCH_STEPS - stepParam;
        for (var step = 0; step < totSteps; step++) {
            var len = MAX_FIRST_STEP >> (stepParam + step);
            var bestDr = 0;
            var bestDc = 0;
            for (var site = 0; site < 8; site++) {
                var dr = DIAMOND_ROWS[site] * len;
                var dc = DIAMOND_COLS[site] * len;
                var r = bestRow + dr;
                var col = bestCol + dc;
                if (col > c.mvColMin && col < c.mvColMax && r > c.mvRowMin && r < c.mvRowMax) {
                    var sad = sad(srcOff, refOff + r * stride + col, stride, w, h);
                    if (sad < bestSad) {
                        sad += c.mvSadErrCost(r, col, centerRow, centerCol, sadPerBit);
                        if (sad < bestSad) {
                            bestSad = sad;
                            bestDr = dr;
                            bestDc = dc;
                        }
                    }
                }
            }
            if (bestDr != 0 || bestDc != 0) {
                bestRow += bestDr;
                bestCol += bestDc;
            } else if (bestRow == refRow && bestCol == refCol) {
                diamondNum00++;
            }
        }
        diamondMv = Mv.pack(bestRow, bestCol);
        var variance = variance(c.preY, refOff + bestRow * stride + bestCol, stride, srcOff, w, h);
        return variance + c.mvErrCost(Mv.pack(bestRow * 8, bestCol * 8), splitRefMv);
    }

    /**
     * Refines a partition's full-pixel vector to quarter pixels by up to three half-pixel and
     * three quarter-pixel rounds, libvpx's {@code vp8_find_best_sub_pixel_step_iteratively} with
     * the metrics of the partition's size.
     *
     * @param first the partition's first subblock
     * @param w the partition width
     * @param h the partition height
     * @param mv the packed full-pixel vector
     * @return the packed vector in 1/8 pixel
     */
    private int blockSubpel(int first, int w, int h, int mv) {
        var stride = enc.cur.yStride;
        var srcOff = c.srcYOff + (first >> 2) * 4 * c.srcYStride + (first & 3) * 4;
        var refOff = c.yOff + (first >> 2) * 4 * stride + (first & 3) * 4;
        var maxDistance = (1 << 10) - 1;
        var ref = splitRefMv;
        var minc = Math.max(c.mvColMin * 4, (Mv.col(ref) >> 1) - maxDistance);
        var maxc = Math.min(c.mvColMax * 4, (Mv.col(ref) >> 1) + maxDistance);
        var minr = Math.max(c.mvRowMin * 4, (Mv.row(ref) >> 1) - maxDistance);
        var maxr = Math.min(c.mvRowMax * 4, (Mv.row(ref) >> 1) + maxDistance);
        var br = Mv.row(mv) * 4;
        var bc = Mv.col(mv) * 4;
        long bestErr = variance(c.preY, refOff + Mv.row(mv) * stride + Mv.col(mv), stride, srcOff, w, h)
                + c.mvErrCost(Mv.pack(br * 2, bc * 2), ref);
        var tr = br;
        var tc = bc;
        for (var step = 2; step >= 1; step--) {
            for (var iter = 0; iter < 3; iter++) {
                var leftScore = subpelScore(refOff, stride, srcOff, w, h, tr, tc - step, minr, maxr, minc, maxc);
                if (leftScore < bestErr) {
                    bestErr = leftScore;
                    br = tr;
                    bc = tc - step;
                }
                var rightScore = subpelScore(refOff, stride, srcOff, w, h, tr, tc + step, minr, maxr, minc, maxc);
                if (rightScore < bestErr) {
                    bestErr = rightScore;
                    br = tr;
                    bc = tc + step;
                }
                var upScore = subpelScore(refOff, stride, srcOff, w, h, tr - step, tc, minr, maxr, minc, maxc);
                if (upScore < bestErr) {
                    bestErr = upScore;
                    br = tr - step;
                    bc = tc;
                }
                var downScore = subpelScore(refOff, stride, srcOff, w, h, tr + step, tc, minr, maxr, minc, maxc);
                if (downScore < bestErr) {
                    bestErr = downScore;
                    br = tr + step;
                    bc = tc;
                }
                var dr = upScore < downScore ? -step : step;
                var dc = leftScore < rightScore ? -step : step;
                var diag = subpelScore(refOff, stride, srcOff, w, h, tr + dr, tc + dc, minr, maxr, minc, maxc);
                if (diag < bestErr) {
                    bestErr = diag;
                    br = tr + dr;
                    bc = tc + dc;
                }
                if (tr == br && tc == bc) {
                    break;
                }
                tr = br;
                tc = bc;
            }
        }
        return Mv.pack(br * 2, bc * 2);
    }

    /**
     * Returns the variance plus vector cost of a fractional partition vector, or
     * {@link Long#MAX_VALUE} outside the window.
     *
     * @param refOff the index of the partition in the reference
     * @param stride the row stride of the reference
     * @param srcOff the index of the partition in the source
     * @param w the partition width
     * @param h the partition height
     * @param r the row in quarter pixels
     * @param col the column in quarter pixels
     * @param minr the smallest row of the window
     * @param maxr the largest row of the window
     * @param minc the smallest column of the window
     * @param maxc the largest column of the window
     * @return the score, or {@link Long#MAX_VALUE}
     */
    private long subpelScore(int refOff, int stride, int srcOff, int w, int h, int r, int col, int minr, int maxr,
                             int minc, int maxc) {
        if (col < minc || col > maxc || r < minr || r > maxr) {
            return Long.MAX_VALUE;
        }
        var off = refOff + (r >> 2) * stride + (col >> 2);
        var variance = subpelVariance(c.preY, off, stride, (col & 3) << 1, (r & 3) << 1, srcOff, w, h);
        var ref = splitRefMv;
        var rr = Mv.row(ref) >> 1;
        var rc = Mv.col(ref) >> 1;
        var cost = enc.mvCost;
        var mvc = ((long) cost[RtStats.MV_MAX + Math.clamp(r - rr, -RtStats.MV_MAX, RtStats.MV_MAX)]
                + cost[RtStats.MV_VALS + RtStats.MV_MAX + Math.clamp(col - rc, -RtStats.MV_MAX, RtStats.MV_MAX)])
                * enc.errorPerBit + 128 >> 8;
        return mvc + variance;
    }

    /**
     * Returns the sum of absolute differences of a block of the source and of the reference.
     *
     * @param srcOff the index of the block in the source
     * @param refOff the index of the block in the reference
     * @param stride the row stride of the reference
     * @param w the block width
     * @param h the block height
     * @return the sum
     */
    private int sad(int srcOff, int refOff, int stride, int w, int h) {
        var srcY = c.srcY;
        var srcStride = c.srcYStride;
        var ref = c.preY;
        var sum = 0;
        for (var r = 0; r < h; r++) {
            var s = srcOff + r * srcStride;
            var p = refOff + r * stride;
            for (var col = 0; col < w; col++) {
                sum += Math.abs((srcY[s + col] & 0xFF) - (ref[p + col] & 0xFF));
            }
        }
        return sum;
    }

    /**
     * Returns the variance of a block of a reference against the source, libvpx's
     * {@code vpx_variance}.
     *
     * @param ref the reference plane
     * @param refOff the index of the block in the reference
     * @param stride the row stride of the reference
     * @param srcOff the index of the block in the source
     * @param w the block width
     * @param h the block height
     * @return the variance
     */
    private int variance(byte[] ref, int refOff, int stride, int srcOff, int w, int h) {
        var srcY = c.srcY;
        var srcStride = c.srcYStride;
        long sse = 0;
        long sum = 0;
        for (var r = 0; r < h; r++) {
            var s = srcOff + r * srcStride;
            var p = refOff + r * stride;
            for (var col = 0; col < w; col++) {
                var d = (ref[p + col] & 0xFF) - (srcY[s + col] & 0xFF);
                sse += d * d;
                sum += d;
            }
        }
        var shift = Integer.numberOfTrailingZeros(w * h);
        return (int) (sse - ((sum * sum) >> shift));
    }

    /**
     * Returns the variance of a block of a reference filtered with the bilinear sub-pixel filters
     * against the source, libvpx's {@code vpx_sub_pixel_variance}.
     *
     * @param ref the reference plane
     * @param refOff the index of the block's full-pixel position in the reference
     * @param stride the row stride of the reference
     * @param xOffset the horizontal offset in 1/8 pixel
     * @param yOffset the vertical offset in 1/8 pixel
     * @param srcOff the index of the block in the source
     * @param w the block width
     * @param h the block height
     * @return the variance
     */
    private int subpelVariance(byte[] ref, int refOff, int stride, int xOffset, int yOffset, int srcOff, int w,
                               int h) {
        var rows = bilinearRows;
        var x0 = BILINEAR[xOffset];
        var x1 = 128 - x0;
        for (var r = 0; r <= h; r++) {
            var p = refOff + r * stride;
            for (var col = 0; col < w; col++) {
                rows[r * w + col] = ((ref[p + col] & 0xFF) * x0 + (ref[p + col + 1] & 0xFF) * x1 + 64) >> 7;
            }
        }
        var y0 = BILINEAR[yOffset];
        var y1 = 128 - y0;
        var srcY = c.srcY;
        var srcStride = c.srcYStride;
        long sse = 0;
        long sum = 0;
        for (var r = 0; r < h; r++) {
            var s = srcOff + r * srcStride;
            for (var col = 0; col < w; col++) {
                var v = (rows[r * w + col] * y0 + rows[(r + 1) * w + col] * y1 + 64) >> 7;
                var d = v - (srcY[s + col] & 0xFF);
                sse += d * d;
                sum += d;
            }
        }
        var shift = Integer.numberOfTrailingZeros(w * h);
        return (int) (sse - ((sum * sum) >> shift));
    }

    /**
     * Predicts a block from a reference plane with the six-tap filters, or copies it at a
     * full-pixel vector.
     *
     * @param ref the reference plane
     * @param refOff the index of the reference sample at the block's full-pixel position
     * @param refStride the reference row stride
     * @param dst the destination
     * @param dstOff the index of the destination block
     * @param dstStride the destination row stride
     * @param w the block width
     * @param h the block height
     * @param mx the horizontal fraction in 1/8 pixel
     * @param my the vertical fraction in 1/8 pixel
     */
    private void predict(byte[] ref, int refOff, int refStride, byte[] dst, int dstOff, int dstStride, int w, int h,
                         int mx, int my) {
        if ((mx | my) == 0) {
            for (var r = 0; r < h; r++) {
                System.arraycopy(ref, refOff + r * refStride, dst, dstOff + r * dstStride, w);
            }
        } else {
            c.dsp.sixtap(ref, refOff, refStride, dst, dstOff, dstStride, w, h, mx, my, c.mcScratch);
        }
    }

    /**
     * Copies the best split found by the search into the encoder's mode info of the current
     * macroblock's candidate state, kept until the search of the next mode.
     */
    private void saveSplit() {
        System.arraycopy(bestSplitMvs, 0, savedSplitMvs, 0, 16);
        System.arraycopy(bestSplitModes, 0, savedSplitModes, 0, 16);
        savedSplitNum = segmentNum;
    }

    /**
     * Writes the split saved by {@link #saveSplit} into the encoder's mode info.
     */
    private void restoreSplit() {
        var idx = c.idx;
        var base = idx * 16;
        segmentNum = savedSplitNum;
        System.arraycopy(savedSplitMvs, 0, enc.bMvs, base, 16);
        enc.mbPartitioning[idx] = (byte) savedSplitNum;
        var parts = MBSPLIT_COUNT[savedSplitNum];
        for (var j = 0; j < parts; j++) {
            enc.partModes[base + j] = (byte) savedSplitModes[Vp8Tables.MBSPLIT_OFFSET[savedSplitNum * 16 + j]];
        }
        enc.mbMv[idx] = savedSplitMvs[15];
    }

    /**
     * The vectors of the best split of the best mode so far, by subblock.
     */
    private final int[] savedSplitMvs = new int[16];

    /**
     * The modes of the best split of the best mode so far, by subblock.
     */
    private final int[] savedSplitModes = new int[16];

    /**
     * The configuration of the best split of the best mode so far.
     */
    private int savedSplitNum;

    /**
     * Predicts the chroma of a split macroblock into the frame being coded the decoder's way,
     * from the rounded average vector of each 8x8 luma quarter ({@code build_uvmvs} and the
     * chroma half of {@code build_inter4x4_predictors_mb}).
     *
     * @param mvs the subblock vectors
     */
    private void predictSplitChroma(int[] mvs) {
        for (var i = 0; i < 2; i++) {
            for (var j = 0; j < 2; j++) {
                var y0 = i * 8 + j * 2;
                var sumRow = Mv.row(mvs[y0]) + Mv.row(mvs[y0 + 1]) + Mv.row(mvs[y0 + 4]) + Mv.row(mvs[y0 + 5]);
                sumRow += 4 + ((sumRow >> 31) * 8);
                var sumCol = Mv.col(mvs[y0]) + Mv.col(mvs[y0 + 1]) + Mv.col(mvs[y0 + 4]) + Mv.col(mvs[y0 + 5]);
                sumCol += 4 + ((sumCol >> 31) * 8);
                uvMvs[i * 2 + j] = clampUv(Mv.pack((short) (sumRow / 8), (short) (sumCol / 8)));
            }
        }
        var cur = enc.cur;
        var refFrame = enc.refBuffer(enc.mbRef[c.idx]);
        var uvStride = cur.uvStride;
        for (var plane = 0; plane < 2; plane++) {
            var src = plane == 0 ? refFrame.u : refFrame.v;
            var dst = plane == 0 ? cur.u : cur.v;
            for (var i = 0; i < 4; i += 2) {
                var mv0 = uvMvs[i];
                var mv1 = uvMvs[i + 1];
                var off = c.uvOff + (i >> 1) * 4 * uvStride;
                if (mv0 == mv1) {
                    predict(src, off + (Mv.row(mv0) >> 3) * uvStride + (Mv.col(mv0) >> 3), uvStride, dst, off, uvStride,
                            8, 4, Mv.col(mv0) & 7, Mv.row(mv0) & 7);
                } else {
                    predict(src, off + (Mv.row(mv0) >> 3) * uvStride + (Mv.col(mv0) >> 3), uvStride, dst, off, uvStride,
                            4, 4, Mv.col(mv0) & 7, Mv.row(mv0) & 7);
                    predict(src, off + 4 + (Mv.row(mv1) >> 3) * uvStride + (Mv.col(mv1) >> 3), uvStride, dst, off + 4,
                            uvStride, 4, 4, Mv.col(mv1) & 7, Mv.row(mv1) & 7);
                }
            }
        }
    }

    /**
     * Limits a chroma vector of a split macroblock that points far outside the frame, like the
     * decoder's {@code clamp_uvmv_to_umv_border}.
     *
     * @param mv the packed chroma vector
     * @return the limited vector
     */
    private int clampUv(int mv) {
        var row = Mv.row(mv);
        var col = Mv.col(mv);
        col = 2 * col < c.mbToLeftEdge - (19 << 3) ? (c.mbToLeftEdge - (16 << 3)) >> 1 : col;
        col = 2 * col > c.mbToRightEdge + (18 << 3) ? (c.mbToRightEdge + (16 << 3)) >> 1 : col;
        row = 2 * row < c.mbToTopEdge - (19 << 3) ? (c.mbToTopEdge - (16 << 3)) >> 1 : row;
        row = 2 * row > c.mbToBottomEdge + (18 << 3) ? (c.mbToBottomEdge + (16 << 3)) >> 1 : row;
        return Mv.pack((short) row, (short) col);
    }

    /**
     * Codes the current {@code SPLITMV} macroblock: predicts it the decoder's way from its
     * subblock vectors, transforms, quantizes and reconstructs its luma subblocks, then its
     * chroma.
     *
     * @param stats the statistics of the coding thread
     */
    void encodeSplit(RtStats stats) {
        var idx = c.idx;
        var base = idx * 16;
        var cur = enc.cur;
        var ref = enc.refBuffer(enc.mbRef[idx]);
        var stride = cur.yStride;
        var mvs = blockMvs;
        System.arraycopy(enc.bMvs, base, mvs, 0, 16);
        if (enc.mbPartitioning[idx] < 3) {
            for (var b = 0; b < 4; b++) {
                var block = (b >> 1) * 8 + (b & 1) * 2;
                var mv = mvs[block];
                var off = c.yOff + (block >> 2) * 4 * stride + (block & 3) * 4;
                predict(ref.y, off + (Mv.row(mv) >> 3) * stride + (Mv.col(mv) >> 3), stride, cur.y, off, stride, 8, 8,
                        Mv.col(mv) & 7, Mv.row(mv) & 7);
            }
        } else {
            for (var i = 0; i < 16; i += 2) {
                var mv0 = mvs[i];
                var mv1 = mvs[i + 1];
                var off = c.yOff + (i >> 2) * 4 * stride + (i & 3) * 4;
                if (mv0 == mv1) {
                    predict(ref.y, off + (Mv.row(mv0) >> 3) * stride + (Mv.col(mv0) >> 3), stride, cur.y, off, stride,
                            8, 4, Mv.col(mv0) & 7, Mv.row(mv0) & 7);
                } else {
                    predict(ref.y, off + (Mv.row(mv0) >> 3) * stride + (Mv.col(mv0) >> 3), stride, cur.y, off, stride,
                            4, 4, Mv.col(mv0) & 7, Mv.row(mv0) & 7);
                    predict(ref.y, off + 4 + (Mv.row(mv1) >> 3) * stride + (Mv.col(mv1) >> 3), stride, cur.y, off + 4,
                            stride, 4, 4, Mv.col(mv1) & 7, Mv.row(mv1) & 7);
                }
            }
        }
        predictSplitChroma(mvs);
        var bps = RtMbCoder.BPS;
        var src = c.src;
        for (var r = 0; r < 16; r++) {
            System.arraycopy(c.srcY, c.srcYOff + r * c.srcYStride, src, r * bps, 16);
        }
        var pred = c.pred;
        c.loadTrellisContexts();
        for (var i = 0; i < 16; i++) {
            var bpsOff = (i >> 2) * 4 * bps + (i & 3) * 4;
            var off = c.yOff + (i >> 2) * 4 * stride + (i & 3) * 4;
            for (var r = 0; r < 4; r++) {
                System.arraycopy(cur.y, off + r * stride, pred, bpsOff + r * bps, 4);
            }
            var coef = c.subCoef;
            c.encDsp.fTransform(src, bpsOff, pred, bpsOff, coef, 0);
            var e = c.quantizeSplitBlock(coef, i);
            c.subEobs[i] = e;
            if (e > 1) {
                c.dsp.idctAdd(coef, 0, cur.y, off, stride);
            } else {
                c.dsp.dcOnlyAdd(coef[0], cur.y, off, stride);
            }
        }
        for (var i = 0; i < 16; i++) {
            for (var n = 0; n < 16; n++) {
                c.levels[n * RtDsp.LEVEL_STRIDE + i] = c.subLevels[i * 16 + n];
            }
        }
        System.arraycopy(c.subEobs, 0, c.eob, 0, 16);
        c.encodeResidual(true);
    }
}
