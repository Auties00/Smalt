/* Ports libvpx 1.17.0 (vp9/encoder/vp9_pickmode.c, vp9_rd.c, vp9/common/vp9_mvref_common.c). Copyright (c) 2010,
   The WebM Project authors; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.video.vp9.internal;

import java.util.Arrays;

import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.BLOCK_16X16;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.BLOCK_16X32;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.BLOCK_32X16;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.BLOCK_32X32;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.BLOCK_32X64;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.BLOCK_64X32;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.BLOCK_64X64;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.BLOCK_8X8;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.DC_PRED;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.EIGHTTAP;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.EIGHTTAP_SMOOTH;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.GOLDEN_FRAME;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.H_PRED;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.INTRA_FRAME;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.LAST_FRAME;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.NEARESTMV;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.NEARMV;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.NEWMV;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.NO_REF_FRAME;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.SWITCHABLE;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.SWITCHABLE_FILTERS;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.TM_PRED;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.TX_16X16;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.TX_32X32;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.TX_4X4;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.TX_8X8;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.TX_MODE_SELECT;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.V_PRED;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.ZEROMV;

/**
 * Decides the mode of an inter frame block of the real-time VP9 encoder without trial
 * encoding: each candidate reference and motion vector is costed from a model of the
 * prediction residual, and intra prediction is tried when no inter mode is cheap.
 *
 * <p>The candidates are tried in a fixed order (zero, nearest, near and new motion for the last
 * frame, then the golden frame), skipping those the adaptive thresholds, the reference masking
 * or the low temporal variance of the superblock rule out. The new motion vector comes from a
 * full-pixel search followed by a sub-pixel refinement; sub-pixel vectors also try the smooth
 * interpolation filter. The luma prediction of the chosen inter mode is left in the
 * reconstruction for the coder to reuse.
 *
 * @implNote This implementation ports {@code vp9_pick_inter_mode} of libvpx's
 * {@code vp9/encoder/vp9_pickmode.c} with its helpers ({@code model_rd_for_sb_y},
 * {@code model_rd_for_sb_y_large}, {@code block_yrd}, {@code calculate_tx_size},
 * {@code search_filter_ref}, {@code search_new_mv}, {@code combined_motion_search},
 * {@code encode_breakout_test}, {@code vp9_NEWMV_diff_bias}, {@code mv_refs_rt},
 * {@code find_predictors}) and {@code vp9_mv_pred}, {@code vp9_find_mv_refs} and
 * {@code vp9_find_best_ref_mvs} from {@code vp9_rd.c} and
 * {@code vp9/common/vp9_mvref_common.c}. Spatial layers, compound modes, the
 * alternate reference, screen content and the denoiser are not supported, so their
 * branches are absent.
 */
final class Vp9RtPickMode {
    /**
     * The adaptive threshold index of each reference's nearest, near, zero and new modes, and
     * of the intra DC, V, H and TM modes for {@link Vp9Constants#INTRA_FRAME}
     * ({@code mode_idx}).
     */
    static final int[][] MODE_IDX = {
            {3, 23, 22, 15},
            {0, 7, 10, 4},
            {2, 9, 11, 6},
            {1, 8, 12, 5}
    };

    /**
     * The number of adaptive threshold modes ({@code MAX_MODES}).
     */
    static final int MAX_MODES = 30;

    /**
     * The initial adaptive threshold factor ({@code RD_THRESH_INIT_FACT}).
     */
    static final int RD_THRESH_INIT_FACT = 32;

    /**
     * The candidate order of the mode search: reference and mode pairs ({@code ref_mode_set}).
     */
    private static final int[] REF_MODE_SET = {
            LAST_FRAME, ZEROMV, LAST_FRAME, NEARESTMV, GOLDEN_FRAME, ZEROMV, LAST_FRAME, NEARMV,
            LAST_FRAME, NEWMV, GOLDEN_FRAME, NEARESTMV, GOLDEN_FRAME, NEARMV, GOLDEN_FRAME, NEWMV
    };

    /**
     * The candidate order of the mode search with temporal layers ({@code ref_mode_set_svc}).
     */
    private static final int[] REF_MODE_SET_SVC = {
            LAST_FRAME, ZEROMV, LAST_FRAME, NEARESTMV, LAST_FRAME, NEARMV, GOLDEN_FRAME, ZEROMV,
            GOLDEN_FRAME, NEARESTMV, GOLDEN_FRAME, NEARMV, LAST_FRAME, NEWMV, GOLDEN_FRAME, NEWMV
    };

    /**
     * The intra modes tried in inter frames ({@code intra_mode_list}).
     */
    private static final int[] INTRA_MODE_LIST = {DC_PRED, V_PRED, H_PRED, TM_PRED};

    /**
     * The index into the low temporal variance flags of each 16x16 position of a superblock
     * ({@code pos_shift_16x16}).
     */
    private static final int[][] POS_SHIFT_16X16 = {
            {9, 10, 13, 14}, {11, 12, 15, 16}, {17, 18, 21, 22}, {19, 20, 23, 24}
    };

    /**
     * No part of the residual can be skipped ({@code SKIP_TXFM_NONE}).
     */
    static final int SKIP_TXFM_NONE = 0;

    /**
     * The whole residual can be skipped ({@code SKIP_TXFM_AC_DC}).
     */
    static final int SKIP_TXFM_AC_DC = 1;

    /**
     * The AC coefficients can be skipped ({@code SKIP_TXFM_AC_ONLY}).
     */
    static final int SKIP_TXFM_AC_ONLY = 2;

    /**
     * The coder owning this decision.
     */
    private final Vp9RtCoder coder;

    /**
     * The rate of the chosen mode.
     */
    long bestRate;

    /**
     * The distortion of the chosen mode.
     */
    long bestDist;

    /**
     * Receives the sum of quantized magnitudes and the squared error of one transform block of
     * {@link #blockYrd}.
     */
    private final long[] rdResult = new long[2];

    /**
     * The rate of the last {@link #blockYrd}.
     */
    long yrdRate;

    /**
     * The distortion of the last {@link #blockYrd}.
     */
    long yrdDist;

    /**
     * The scaled sum of squared errors the last {@link #blockYrd} left, or
     * {@link Long#MAX_VALUE}.
     */
    long yrdSse;

    /**
     * The rate of the last model estimate.
     */
    long modelRate;

    /**
     * The distortion of the last model estimate.
     */
    long modelDist;

    /**
     * The luma variance of the last model estimate.
     */
    long varY;

    /**
     * The luma sum of squared errors of the last model estimate.
     */
    long sseY;

    /**
     * Whether the last large-block model found the whole block skippable.
     */
    private boolean earlyTerm;

    /**
     * Whether each chroma prediction of the current mode was built.
     */
    private final boolean[] predUvComputed = new boolean[2];

    /**
     * The candidate motion vectors of each mode and reference, {@code [(mode - NEARESTMV) * 4
     * + reference]}, packed.
     */
    private final int[] frameMv = new int[16];

    /**
     * Whether each mode and reference was evaluated.
     */
    private final boolean[] modeChecked = new boolean[16];

    /**
     * Whether each reference's candidate list came from neighbors moving alike.
     */
    private final boolean[] constMotion = new boolean[4];

    /**
     * Whether each reference was skipped when finding its predictors.
     */
    private final boolean[] skipRefFindPred = new boolean[4];

    /**
     * The cost of signaling each reference.
     */
    private final int[] refFrameCost = new int[4];

    /**
     * The prediction buffers of the mode search; a fourth buffer is the reconstruction.
     */
    private final byte[][] tmp = {new byte[64 * 64], new byte[64 * 64], new byte[64 * 64]};

    /**
     * Whether each prediction buffer holds a prediction still needed.
     */
    private final boolean[] tmpInUse = new boolean[3];

    /**
     * The plane of the current prediction.
     */
    private byte[] predBuf;

    /**
     * The index of the block in the current prediction.
     */
    private int predOff;

    /**
     * The row stride of the current prediction.
     */
    private int predStride;

    /**
     * The 8x8 sums of squares of the large-block model.
     */
    private final long[] sse8x8 = new long[64];

    /**
     * The 8x8 sums of the large-block model.
     */
    private final int[] sum8x8 = new int[64];

    /**
     * The 8x8 variances of the large-block model.
     */
    private final long[] var8x8 = new long[64];

    /**
     * The 16x16 sums of squares of the large-block model.
     */
    private final long[] sse16x16 = new long[16];

    /**
     * The 16x16 sums of the large-block model.
     */
    private final int[] sum16x16 = new int[16];

    /**
     * The 16x16 variances of the large-block model.
     */
    private final long[] var16x16 = new long[16];

    /**
     * The 32x32 sums of squares of the large-block model.
     */
    private final long[] sse32x32 = new long[4];

    /**
     * The 32x32 sums of the large-block model.
     */
    private final int[] sum32x32 = new int[4];

    /**
     * The 32x32 variances of the large-block model.
     */
    private final long[] var32x32 = new long[4];

    /**
     * The motion vector candidate list being built.
     */
    final int[] mvList = new int[2];

    /**
     * The number of candidates in {@link #mvList}.
     */
    private int found;

    /**
     * A flat 128 picture, the reference of the source variance.
     */
    private static final byte[] FLAT = flat();

    /**
     * Creates a mode decision.
     *
     * @param coder the superblock coder whose blocks are decided
     */
    Vp9RtPickMode(Vp9RtCoder coder) {
        this.coder = coder;
    }

    /**
     * Builds a 64x64 picture of 128s.
     *
     * @return the picture
     */
    private static byte[] flat() {
        var b = new byte[64 * 64];
        Arrays.fill(b, (byte) 128);
        return b;
    }

    /**
     * Returns the variance of the block's luma source per sample
     * ({@code vp9_get_sby_perpixel_variance}).
     *
     * @param bsize the block size
     * @return the variance per sample, rounded
     */
    private long sourceVariance(int bsize) {
        var src = coder.enc.source;
        var w = Vp9RtCoder.NUM_4X4_WIDE[bsize] * 4;
        var h = Vp9RtCoder.NUM_4X4_HIGH[bsize] * 4;
        var n = log2(w * h);
        var packed = coder.edsp.variance(src.planes[0], coder.srcOff[0], src.strides[0], FLAT, 0, 64, w, h);
        var v = Vp9EncDsp.variance(packed, n);
        return (v + (1L << (n - 1))) >> n;
    }

    /**
     * Returns the log2 of a power of two.
     *
     * @param n the power of two
     * @return the exponent
     */
    private static int log2(int n) {
        return 31 - Integer.numberOfLeadingZeros(n);
    }

    /**
     * Returns the index of a candidate motion vector in {@link #frameMv}.
     *
     * @param mode the inter mode
     * @param ref the reference
     * @return the index
     */
    private static int fmv(int mode, int ref) {
        return (mode - NEARESTMV) * 4 + ref;
    }

    /**
     * Picks the mode of the block the coder is on, leaving it in the block's mode information,
     * the rate and distortion in {@link #bestRate} and {@link #bestDist}, the luma prediction of
     * an inter mode in the reconstruction, and the skip decisions in the coder
     * ({@code vp9_pick_inter_mode}).
     *
     * @param bsize the block size, at least 8x8
     */
    void pickInterMode(int bsize) {
        var c = coder;
        var enc = c.enc;
        var sf = enc.sf;
        var mi = c.mi;
        var miRow = c.miRow;
        var miCol = c.miCol;
        var rdmult = c.rdmult;
        var useSvc = enc.temporalLayers > 1;
        var recon = enc.recon;
        var origBuf = recon.planes8[0];
        var origOff = c.dstOff[0];
        var origStride = recon.strides[0];
        var bw = Vp9RtCoder.NUM_4X4_WIDE[bsize] * 4;
        var bh = Vp9RtCoder.NUM_4X4_HIGH[bsize] * 4;
        var intraCostPenalty = intraCostPenalty(bsize, enc.baseQindex);
        var interModeThresh = Vp9RtCoder.rdCost(rdmult, intraCostPenalty, 0);
        var rdThreshes = enc.rdThreshes;
        var threshBase = (mi.segmentId * 13 + bsize) * MAX_MODES;
        var threshFact = c.threshFact;
        var factBase = bsize * MAX_MODES;
        var predFilterSearch = enc.interpFilter == SWITCHABLE;
        var sceneChange = enc.rc.highSourceSad;
        var bestMode = ZEROMV;
        var bestRef = LAST_FRAME;
        var bestTxSize = 4;
        var bestIntraTxSize = 4;
        var bestPredFilter = sf.defaultInterpFilter == Vp9Constants.BILINEAR ? Vp9Constants.BILINEAR : EIGHTTAP;
        var bestModeSkipTxfm = SKIP_TXFM_NONE;
        var bestPred = -1;
        var bestEarlyTerm = false;
        var bestPredSad = Integer.MAX_VALUE;
        long sseZeromvNormalized = 0xFFFFFFFFL;
        long bestSseSofar = 0xFFFFFFFFL;
        var thisModePred = -1;
        var reuseInterPred = sf.reuseInterPredSby;
        c.sourceVariance = 0xFFFFFFFFL;
        var gfTemporalRef = !useSvc;
        var threshSkipGolden = 500L;
        var threshSvcSkipGolden = gfTemporalRef && enc.rc.avgFrameLowMotion <= 70 ? 0L : 500L;
        initRefFrameCost();
        Arrays.fill(modeChecked, false);
        Arrays.fill(constMotion, false);
        Arrays.fill(tmpInUse, false);
        predBuf = origBuf;
        predOff = origOff;
        predStride = origStride;
        c.skipEncode = enc.sf.skipEncodeFrame && c.qindex < 115;
        c.skip = false;
        if (sf.cbPredFilterSearch != 0) {
            var bsl = Vp9RtCoder.MI_WIDTH_LOG2[bsize];
            predFilterSearch = enc.interpFilter == SWITCHABLE
                    && ((((miRow + miCol) >> bsl) + (int) (enc.currentFrame & 1)) & 1) != 0;
        }
        var filterRef = enc.interpFilter;
        if (sf.defaultInterpFilter != Vp9Constants.BILINEAR) {
            if (c.aboveMi != null && c.aboveMi.isInter()) {
                filterRef = c.aboveMi.interpFilter;
            } else if (c.leftMi != null && c.leftMi.isInter()) {
                filterRef = c.leftMi.interpFilter;
            }
        }
        long bestRdcRate = Integer.MAX_VALUE;
        long bestRdcDist = Long.MAX_VALUE;
        long bestRdcCost = Long.MAX_VALUE;
        mi.sbType = bsize;
        mi.ref0 = NO_REF_FRAME;
        mi.ref1 = NO_REF_FRAME;
        mi.txSize = Math.min(Vp9Constants.MAX_TX_SIZE[bsize], Vp9Constants.TX_MODE_TO_BIGGEST[enc.txMode]);
        if (sf.limitNewmvEarlyExit) {
            c.sourceVariance = sourceVariance(bsize);
        }
        var usableRef = enc.rc.framesSinceGolden == 0 && gfTemporalRef ? LAST_FRAME : GOLDEN_FRAME;
        var forceSkipLowTempVar = false;
        if (sf.shortCircuitLowTempVar != 0) {
            forceSkipLowTempVar = forceSkipLowTempVar(miRow, miCol, bsize);
            if ((sf.shortCircuitLowTempVar == 1 || sf.shortCircuitLowTempVar == 3) && forceSkipLowTempVar) {
                usableRef = LAST_FRAME;
            }
        }
        if (sf.disableGoldenRef && (c.contentStateSb != Vp9RtCoder.CONTENT_VERY_HIGH_SAD
                || enc.rc.avgFrameLowMotion < 60)) {
            usableRef = LAST_FRAME;
        }
        var useGoldenNonzeromv = enc.refFrameFlagGolden() && !forceSkipLowTempVar;
        if (enc.speed >= 8 && !useSvc && (enc.rc.framesSinceGolden + 1 < c.lastSbHighContent
                || c.lastSbHighContent > 40 || enc.rc.framesSinceGolden > 120)) {
            usableRef = LAST_FRAME;
        }
        var refFrameSkipMask = 0;
        for (var ref = LAST_FRAME; ref <= usableRef; ref++) {
            skipRefFindPred[ref] = !enc.refFrameFlag(ref);
            if (!skipRefFindPred[ref]) {
                if (!findPredictors(ref, bsize, forceSkipLowTempVar)) {
                    refFrameSkipMask |= 1 << ref;
                }
            }
        }
        if (useSvc || enc.speed <= 7 || bsize < BLOCK_32X32) {
            c.sbUseMvPart = false;
        }
        var largeBlock = c.contentStateSb == Vp9RtCoder.CONTENT_VERY_HIGH_SAD
                || (c.sbIsSkin && enc.rc.avgFrameLowMotion > 70) || enc.speed < 7
                ? bsize > BLOCK_32X32 : bsize >= BLOCK_32X32;
        var useModelYrdLarge = largeBlock && !Vp9RtCyclicRefresh.boosted(mi.segmentId) && enc.baseQindex != 0;
        var set = useSvc ? REF_MODE_SET_SVC : REF_MODE_SET;
        var modes = set.length / 2;
        for (var idx = 0; idx < modes; idx++) {
            var ref = set[2 * idx];
            var thisMode = set[2 * idx + 1];
            var rateMv = 0;
            if (ref > usableRef || skipRefFindPred[ref]) {
                continue;
            }
            if (ref == GOLDEN_FRAME && (useSvc ? sseZeromvNormalized < threshSvcSkipGolden
                    : sseZeromvNormalized < threshSkipGolden)) {
                continue;
            }
            if (!enc.refFrameFlag(ref)) {
                continue;
            }
            if ((sf.interModeMask[bsize] & (1 << thisMode)) == 0) {
                continue;
            }
            if (constMotion[ref] && thisMode == NEARMV) {
                continue;
            }
            if (forceSkipLowTempVar && ref == GOLDEN_FRAME && frameMv[fmv(thisMode, ref)] != 0) {
                continue;
            }
            if (c.contentStateSb != Vp9RtCoder.CONTENT_VERY_HIGH_SAD
                    && (sf.shortCircuitLowTempVar >= 2 || (sf.shortCircuitLowTempVar == 1 && bsize == BLOCK_64X64))
                    && forceSkipLowTempVar && ref == LAST_FRAME && thisMode == NEWMV) {
                continue;
            }
            if (sf.referenceMasking && !(frameMv[fmv(thisMode, ref)] == 0 && ref == LAST_FRAME)) {
                if (!forceSkipLowTempVar && usableRef > LAST_FRAME) {
                    var other = ref == LAST_FRAME ? GOLDEN_FRAME : LAST_FRAME;
                    if (enc.refFrameFlag(other) && c.predMvSad[other] < Integer.MAX_VALUE
                            && (long) c.predMvSad[ref] > ((long) c.predMvSad[other] << 1)) {
                        refFrameSkipMask |= 1 << ref;
                    }
                }
            }
            if ((refFrameSkipMask & (1 << ref)) != 0) {
                continue;
            }
            mi.ref0 = ref;
            mi.ref1 = NO_REF_FRAME;
            var modeIndex = MODE_IDX[ref][thisMode - NEARESTMV];
            long modeRdThresh = bestModeSkipTxfm != 0 ? (long) rdThreshes[threshBase + modeIndex] << 1
                    : rdThreshes[threshBase + modeIndex];
            if (sf.biasGolden && ref == GOLDEN_FRAME && enc.rc.framesSinceGolden > 4) {
                modeRdThresh <<= 3;
            }
            if (lessThanThresh(bestRdcCost, modeRdThresh, threshFact[factBase + modeIndex])
                    && frameMv[fmv(thisMode, ref)] != 0) {
                continue;
            }
            if (thisMode == NEWMV) {
                var newMv = searchNewMv(ref, bsize, bestPredSad, bestRdcCost, gfTemporalRef);
                if (newMv < 0) {
                    continue;
                }
                rateMv = newMv;
            }
            var skipThisMv = false;
            for (var other = NEARESTMV; other <= NEWMV; other++) {
                if (other == thisMode) {
                    continue;
                }
                if (modeChecked[fmv(other, ref)] && frameMv[fmv(thisMode, ref)] == frameMv[fmv(other, ref)]
                        && frameMv[fmv(other, ref)] == 0) {
                    skipThisMv = true;
                    break;
                }
            }
            if (skipThisMv) {
                continue;
            }
            if (useGoldenNonzeromv && thisMode == NEWMV && ref == LAST_FRAME
                    && frameMv[fmv(NEWMV, LAST_FRAME)] != Vp9RtCoder.INVALID_MV) {
                var mv = frameMv[fmv(NEWMV, LAST_FRAME)];
                var last = enc.activeRef(LAST_FRAME);
                var pre = last.offset(0, miCol * 8, miRow * 8) + ((mv >> 16) >> 3) * last.strides[0]
                        + (((short) mv) >> 3);
                bestPredSad = c.edsp.sad(enc.source.planes[0], c.srcOff[0], enc.source.strides[0], last.planes[0],
                        pre, last.strides[0], bw, bh);
                c.predMvSad[LAST_FRAME] = bestPredSad;
            }
            if (thisMode != NEARESTMV && frameMv[fmv(thisMode, ref)] == frameMv[fmv(NEARESTMV, ref)]) {
                continue;
            }
            mi.mode = thisMode;
            mi.mv0 = frameMv[fmv(thisMode, ref)];
            mi.mv1 = 0;
            if (reuseInterPred) {
                if (thisModePred < 0) {
                    thisModePred = 3;
                } else {
                    thisModePred = getPredBuffer();
                    predBuf = tmp[thisModePred];
                    predOff = 0;
                    predStride = bw;
                }
            }
            earlyTerm = false;
            predUvComputed[0] = false;
            predUvComputed[1] = false;
            var mvRow = mi.mv0 >> 16;
            var mvCol = (short) mi.mv0;
            long rate;
            long dist;
            if ((thisMode == NEWMV || filterRef == SWITCHABLE) && predFilterSearch
                    && (ref == LAST_FRAME || (ref == GOLDEN_FRAME && useSvc)) && ((mvRow | mvCol) & 7) != 0) {
                thisModePred = searchFilterRef(bsize, reuseInterPred, thisModePred, useModelYrdLarge);
                rate = modelRate;
                dist = modelDist;
            } else {
                mi.interpFilter = filterRef == SWITCHABLE ? EIGHTTAP : filterRef;
                c.buildInterPredictorInto(0, bsize, ref, mi.mv0, mi.interpFilter, predBuf, predOff, predStride);
                if (useModelYrdLarge) {
                    modelRdForSbYLarge(bsize);
                } else {
                    modelRdForSbY(bsize, false);
                }
                rate = modelRate;
                dist = modelDist;
                if (ref == LAST_FRAME && frameMv[fmv(thisMode, ref)] == 0) {
                    sseZeromvNormalized = sseY >> (Vp9RtCoder.B_WIDTH_LOG2[bsize] + Vp9RtCoder.B_HEIGHT_LOG2[bsize]);
                }
                if (sseY < bestSseSofar) {
                    bestSseSofar = sseY;
                }
            }
            var thisEarlyTerm = earlyTerm;
            if (!thisEarlyTerm) {
                var skippable = blockYrd(bsize, Math.min(mi.txSize, TX_16X16), c.srcOff[0], predBuf, predOff,
                        predStride, sseY, true, false, rate, dist);
                rate = yrdRate;
                dist = yrdDist;
                var thisSse = yrdSse;
                c.skipTxfm = skippable ? SKIP_TXFM_AC_DC : SKIP_TXFM_NONE;
                if (skippable) {
                    rate = c.skipCost(1);
                } else if (Vp9RtCoder.rdCost(rdmult, rate, dist) < Vp9RtCoder.rdCost(rdmult, 0, thisSse)) {
                    rate += c.skipCost(0);
                } else {
                    rate = c.skipCost(1);
                    dist = thisSse;
                    c.skipTxfm = SKIP_TXFM_AC_DC;
                }
                if (enc.interpFilter == SWITCHABLE && ((mvRow | mvCol) & 7) != 0) {
                    rate += switchableRate(mi.interpFilter);
                }
            } else {
                if (enc.interpFilter == SWITCHABLE && ((mvRow | mvCol) & 7) != 0) {
                    rate += switchableRate(mi.interpFilter);
                }
                rate += c.skipCost(1);
            }
            if (!thisEarlyTerm && (c.colorSensitivity[0] || c.colorSensitivity[1])) {
                for (var plane = 1; plane <= 2; plane++) {
                    if (c.colorSensitivity[plane - 1] && !predUvComputed[plane - 1]) {
                        c.buildInterPredictor(plane, bsize);
                        predUvComputed[plane - 1] = true;
                    }
                }
                var uv = modelRdForSbUv(enc.uvBsize(bsize));
                rate += uv >> 40;
                dist += uv & 0xFFFFFFFFFFL;
            }
            rate += rateMv;
            rate += enc.interModeCost[c.modeContext[ref] * 4 + (thisMode - NEARESTMV)];
            rate += refFrameCost[ref];
            var rdcost = Vp9RtCoder.rdCost(rdmult, rate, dist);
            rdcost = newmvDiffBias(thisMode, rdcost, bsize, frameMv[fmv(thisMode, ref)], ref == LAST_FRAME);
            if (!sceneChange) {
                var breakout = encodeBreakoutTest(bsize, ref, thisMode);
                if (breakout) {
                    rate = breakoutRate + rateMv;
                    dist = breakoutDist;
                    rdcost = Vp9RtCoder.rdCost(rdmult, rate, dist);
                }
            }
            modeChecked[fmv(thisMode, ref)] = true;
            if (rdcost < bestRdcCost || c.skip) {
                bestRdcCost = rdcost;
                bestRdcRate = rate;
                bestRdcDist = dist;
                bestEarlyTerm = thisEarlyTerm;
                bestMode = thisMode;
                bestPredFilter = mi.interpFilter;
                bestTxSize = mi.txSize;
                bestRef = ref;
                bestModeSkipTxfm = c.skipTxfm;
                if (reuseInterPred) {
                    freePredBuffer(bestPred);
                    bestPred = thisModePred;
                }
            } else if (reuseInterPred) {
                freePredBuffer(thisModePred);
            }
            if (c.skip) {
                break;
            }
            if (bestEarlyTerm && idx > 0 && !sceneChange) {
                c.skip = true;
                break;
            }
        }
        mi.mode = bestMode;
        mi.interpFilter = bestPredFilter;
        mi.txSize = bestTxSize;
        mi.ref0 = bestRef;
        mi.mv0 = bestRef > INTRA_FRAME ? frameMv[fmv(bestMode, bestRef)] : mi.mv0;
        c.skipTxfm = bestModeSkipTxfm;
        mi.ref1 = NO_REF_FRAME;
        if (bestRdcCost == Long.MAX_VALUE || (sceneChange)
                || ((!forceSkipLowTempVar || bsize < BLOCK_32X32 || c.contentStateSb == Vp9RtCoder.CONTENT_VERY_HIGH_SAD)
                && !c.skip && bestRdcCost > interModeThresh && bsize <= sf.maxIntraBsize && !c.skipLowSourceSad
                && !c.lowvarHighsumdiff)) {
            var intraTxSize = Math.min(Vp9Constants.MAX_TX_SIZE[bsize],
                    Vp9Constants.TX_MODE_TO_BIGGEST[enc.txMode]);
            if (reuseInterPred && bestPred == 3) {
                var copy = getPredBuffer();
                copyBlock(origBuf, origOff, origStride, tmp[copy], 0, bw, bw, bh);
                bestPred = copy;
            }
            predBuf = origBuf;
            predOff = origOff;
            predStride = origStride;
            for (var i = 0; i < 4; i++) {
                var thisMode = INTRA_MODE_LIST[i];
                var modeIndex = MODE_IDX[INTRA_FRAME][i];
                long modeRdThresh = rdThreshes[threshBase + modeIndex];
                if (((1 << thisMode) & sf.intraYModeBsizeMask[bsize]) == 0) {
                    continue;
                }
                if (sf.rtIntraDcOnlyLowContent && thisMode != DC_PRED
                        && c.contentStateSb != Vp9RtCoder.CONTENT_VERY_HIGH_SAD) {
                    continue;
                }
                if (lessThanThresh(bestRdcCost, modeRdThresh, threshFact[factBase + modeIndex])) {
                    continue;
                }
                mi.mode = thisMode;
                mi.ref0 = INTRA_FRAME;
                mi.txSize = intraTxSize;
                computeIntraYPrediction(thisMode, bsize);
                modelRdForSbY(bsize, true);
                var skippable = blockYrd(bsize, Math.min(mi.txSize, TX_16X16), c.srcOff[0], origBuf, origOff,
                        origStride, Long.MAX_VALUE, true, true, modelRate, modelDist);
                long rate = yrdRate;
                var dist = yrdDist;
                int skipTxfm;
                if (skippable) {
                    skipTxfm = SKIP_TXFM_AC_DC;
                    rate = c.skipCost(1);
                } else {
                    skipTxfm = SKIP_TXFM_NONE;
                    rate += c.skipCost(0);
                }
                rate += enc.mbmodeCost[thisMode];
                rate += refFrameCost[INTRA_FRAME];
                rate += intraCostPenalty;
                var rdcost = Vp9RtCoder.rdCost(rdmult, rate, dist);
                if (rdcost < bestRdcCost) {
                    bestRdcCost = rdcost;
                    bestRdcRate = rate;
                    bestRdcDist = dist;
                    bestMode = thisMode;
                    bestIntraTxSize = mi.txSize;
                    bestRef = INTRA_FRAME;
                    mi.uvMode = thisMode;
                    mi.mv0 = Vp9RtCoder.INVALID_MV;
                    mi.mv1 = Vp9RtCoder.INVALID_MV;
                    bestModeSkipTxfm = skipTxfm;
                }
            }
            mi.txSize = bestRef != INTRA_FRAME ? bestTxSize : bestIntraTxSize;
        }
        mi.mode = bestMode;
        mi.ref0 = bestRef;
        mi.ref1 = NO_REF_FRAME;
        c.skipTxfm = bestModeSkipTxfm;
        if (!mi.isInter()) {
            mi.interpFilter = SWITCHABLE_FILTERS;
        } else {
            mi.mv0 = frameMv[fmv(bestMode, bestRef)];
        }
        if (reuseInterPred && bestPred >= 0 && bestPred != 3 && mi.isInter()) {
            copyBlock(tmp[bestPred], 0, bw, origBuf, origOff, origStride, bw, bh);
        } else if (mi.isInter() && !reuseInterPred) {
            c.buildInterPredictor(0, bsize);
        }
        if (sf.adaptiveRdThresh != 0) {
            var bestModeIdx = MODE_IDX[bestRef][modeOffset(mi.mode)];
            if (bestRef == INTRA_FRAME) {
                for (var i = 0; i < 4; i++) {
                    updateThreshFact(factBase, INTRA_FRAME, bestModeIdx, INTRA_MODE_LIST[i]);
                }
            } else {
                for (var mode = NEARESTMV; mode <= NEWMV; mode++) {
                    updateThreshFact(factBase, bestRef, bestModeIdx, mode);
                }
            }
        }
        bestRate = bestRdcRate;
        bestDist = bestRdcDist;
    }

    /**
     * Returns the adaptive threshold offset of a mode ({@code mode_offset}).
     *
     * @param mode the inter or intra mode
     * @return the offset, from 0 to 3
     */
    private static int modeOffset(int mode) {
        if (mode >= NEARESTMV) {
            return mode - NEARESTMV;
        }
        return switch (mode) {
            case DC_PRED -> 0;
            case V_PRED -> 1;
            case H_PRED -> 2;
            default -> 3;
        };
    }

    /**
     * Returns whether a cost is below a mode's adaptive threshold ({@code rd_less_than_thresh}).
     *
     * @param bestRd the best cost
     * @param thresh the mode's threshold
     * @param fact the mode's adaptive factor
     * @return whether the mode can be skipped
     */
    private static boolean lessThanThresh(long bestRd, long thresh, int fact) {
        return bestRd < ((thresh * fact) >> 5) || thresh == Integer.MAX_VALUE;
    }

    /**
     * Adapts a mode's threshold factor after a decision ({@code update_thresh_freq_fact_row_mt}).
     *
     * @param factBase the block size's first factor
     * @param ref the reference of the mode
     * @param bestModeIdx the threshold index of the chosen mode
     * @param mode the mode the block chose
     */
    private void updateThreshFact(int factBase, int ref, int bestModeIdx, int mode) {
        var sf = coder.enc.sf;
        var thrModeIdx = MODE_IDX[ref][modeOffset(mode)];
        var fact = coder.threshFact;
        var i = factBase + thrModeIdx;
        if (thrModeIdx == bestModeIdx) {
            fact[i] -= fact[i] >> 4;
        } else if (sf.limitNewmvEarlyExit && mode == NEWMV && ref == LAST_FRAME && coder.sourceVariance < 5) {
            fact[i] = Math.min(fact[i] + 1, 32);
        } else {
            fact[i] = Math.min(fact[i] + 1, sf.adaptiveRdThresh * 64);
        }
    }

    /**
     * Returns the intra cost penalty of a block ({@code vp9_get_intra_cost_penalty}).
     *
     * @param bsize the block size
     * @param qindex the frame's quantizer index
     * @return the penalty
     */
    private int intraCostPenalty(int bsize, int qindex) {
        var reduction = bsize <= BLOCK_16X16 ? (bsize <= BLOCK_8X8 ? 4 : 2) : 0;
        if (coder.enc.noise.enabled && coder.enc.noise.level == Vp9RtNoiseEstimate.HIGH) {
            reduction = 0;
        }
        return (20 * Vp9EncTables.dcQuant(qindex, 0)) >> reduction;
    }

    /**
     * Computes the cost of signaling each reference in the block's context
     * ({@code init_ref_frame_cost}).
     */
    private void initRefFrameCost() {
        var c = coder;
        var probs = c.enc.fc.probs;
        var intraInter = probs[Vp9FrameContext.INTRA_INTER + Vp9PredContexts.intraInter(c.aboveMi, c.leftMi)];
        var p1 = probs[Vp9FrameContext.SINGLE_REF + Vp9PredContexts.singleRefP1(c.aboveMi, c.leftMi) * 2];
        var p2 = probs[Vp9FrameContext.SINGLE_REF + Vp9PredContexts.singleRefP2(c.aboveMi, c.leftMi) * 2 + 1];
        refFrameCost[INTRA_FRAME] = Vp9EncTables.costBit(intraInter, 0);
        var inter = Vp9EncTables.costBit(intraInter, 1);
        refFrameCost[LAST_FRAME] = inter + Vp9EncTables.costBit(p1, 0);
        refFrameCost[GOLDEN_FRAME] = inter + Vp9EncTables.costBit(p1, 1) + Vp9EncTables.costBit(p2, 0);
        refFrameCost[Vp9Constants.ALTREF_FRAME] = inter + Vp9EncTables.costBit(p1, 1) + Vp9EncTables.costBit(p2, 1);
    }

    /**
     * Returns the cost of signaling an interpolation filter in the block's context
     * ({@code vp9_get_switchable_rate}).
     *
     * @param filter the interpolation filter, from {@code EIGHTTAP} to {@code EIGHTTAP_SHARP}
     * @return the cost
     */
    private int switchableRate(int filter) {
        var ctx = Vp9PredContexts.switchableInterp(coder.aboveMi, coder.leftMi);
        return coder.enc.switchableInterpCosts[ctx * 3 + filter];
    }

    /**
     * Returns whether the superblock region holding the block has a low temporal variance
     * ({@code get_force_skip_low_temp_var}).
     *
     * @param miRow the block's 8x8 row
     * @param miCol the block's 8x8 column
     * @param bsize the block size
     * @return whether the golden reference and new motion may be skipped
     */
    private boolean forceSkipLowTempVar(int miRow, int miCol, int bsize) {
        var low = coder.varianceLow;
        var i = (miRow & 7) >> 1;
        var j = (miCol & 7) >> 1;
        var rowIn = (miRow & 7) != 0;
        var colIn = (miCol & 7) != 0;
        return switch (bsize) {
            case BLOCK_64X64 -> low[0];
            case BLOCK_64X32 -> !colIn && !rowIn ? low[1] : (!colIn ? low[2] : false);
            case BLOCK_32X64 -> !colIn && !rowIn ? low[3] : (colIn && !rowIn ? low[4] : false);
            case BLOCK_32X32 -> low[5 + (colIn ? 1 : 0) + (rowIn ? 2 : 0)];
            case BLOCK_16X16 -> low[POS_SHIFT_16X16[i][j]];
            case BLOCK_32X16 -> low[POS_SHIFT_16X16[i][j]] && low[POS_SHIFT_16X16[i][((miCol + 2) & 7) >> 1]];
            case BLOCK_16X32 -> low[POS_SHIFT_16X16[i][j]] && low[POS_SHIFT_16X16[((miRow + 2) & 7) >> 1][j]];
            default -> false;
        };
    }

    /**
     * Finds the candidate motion vectors of a reference and measures them on the block
     * ({@code find_predictors}).
     *
     * @param ref the reference
     * @param bsize the block size
     * @param forceSkipLowTempVar whether the golden reference's measure is skipped
     * @return whether the reference exists
     */
    private boolean findPredictors(int ref, int bsize, boolean forceSkipLowTempVar) {
        var c = coder;
        var enc = c.enc;
        c.predMvSad[ref] = Integer.MAX_VALUE;
        frameMv[fmv(NEWMV, ref)] = Vp9RtCoder.INVALID_MV;
        frameMv[fmv(ZEROMV, ref)] = 0;
        var picture = enc.activeRef(ref);
        if (!enc.refFrameFlag(ref) || picture == null) {
            return false;
        }
        constMotion[ref] = findMvRefs(ref, bsize, !enc.usePrevFrameMvs);
        var allowHp = enc.allowHp;
        var nearest = lowerPrecision(mvList[0], allowHp);
        var near = lowerPrecision(mvList[1], allowHp);
        c.refMvs[ref * 2] = nearest;
        c.refMvs[ref * 2 + 1] = near;
        frameMv[fmv(NEARESTMV, ref)] = nearest;
        frameMv[fmv(NEARMV, ref)] = near;
        if (bsize >= BLOCK_8X8 && !(forceSkipLowTempVar && ref == GOLDEN_FRAME)) {
            mvPred(picture, ref, bsize);
        }
        return true;
    }

    /**
     * Lowers a vector to quarter-pixel precision when eighth-pixel precision is not usable
     * ({@code lower_mv_precision}).
     *
     * @param mv the packed vector
     * @param allowHp whether the frame allows eighth-pixel vectors
     * @return the packed vector
     */
    static int lowerPrecision(int mv, boolean allowHp) {
        var row = mv >> 16;
        var col = (short) mv;
        var useHp = allowHp && Math.abs(row) < 64 && Math.abs(col) < 64;
        if (!useHp) {
            if ((row & 1) != 0) {
                row += row > 0 ? -1 : 1;
            }
            if ((col & 1) != 0) {
                col += col > 0 ? -1 : 1;
            }
        }
        return ModeInfo.packMv(row, col);
    }

    /**
     * Adds a vector to the candidate list unless it repeats the first, and returns whether the
     * list is full ({@code ADD_MV_REF_LIST}).
     *
     * @param mv the packed vector
     * @return whether two candidates were found
     */
    private boolean add(int mv) {
        if (found != 0) {
            if (mv != mvList[0]) {
                mvList[found++] = mv;
                return true;
            }
            return false;
        }
        mvList[found++] = mv;
        return false;
    }

    /**
     * Finds the two candidate motion vectors of a reference and the inter mode context, as the
     * decoder does ({@code vp9_find_mv_refs}), or with the real-time shortcut of
     * {@code mv_refs_rt}, which stops at the first candidate.
     *
     * @param refFrame the reference
     * @param bsize the block size
     * @param rt whether to use the real-time shortcut, allowed only without the previous
     * frame's vectors
     * @return whether the shortcut found the neighbors moving alike ({@code const_motion})
     */
    boolean findMvRefs(int refFrame, int bsize, boolean rt) {
        return findMvRefs(refFrame, bsize, rt, -1);
    }

    /**
     * The sub-block of a sub-8x8 neighbor whose vector a sub-block's candidate list takes, by the
     * sub-block and whether the neighbor lies above, libvpx's {@code idx_n_column_to_subblock}.
     */
    private static final int[] COLUMN_TO_SUBBLOCK = {1, 2, 1, 3, 3, 2, 3, 3};

    /**
     * Finds the candidate motion vectors of a reference like {@link #findMvRefs(int, int,
     * boolean)}, for one sub-block of a sub-8x8 block or for the whole block
     * ({@code find_mv_refs_idx}): the two nearest neighbors of a sub-block contribute the vectors
     * of their adjacent sub-blocks when they are smaller than 8x8.
     *
     * @param refFrame the reference
     * @param bsize the block size
     * @param rt whether to use the real-time shortcut
     * @param block the sub-block, from 0 to 3, or {@code -1} for the whole block
     * @return whether the shortcut found the neighbors moving alike
     */
    boolean findMvRefs(int refFrame, int bsize, boolean rt, int block) {
        var c = coder;
        var enc = c.enc;
        mvList[0] = 0;
        mvList[1] = 0;
        found = 0;
        var searchBase = bsize * 16;
        var differentRefFound = false;
        var counter = 0;
        var grid = enc.miGrid;
        var miCols = enc.miCols;
        var miRow = c.miRow;
        var miCol = c.miCol;
        var constMotion = false;
        var done = false;
        var i = 0;
        for (; i < 2 && !done; i++) {
            var row = Vp9Constants.MV_REF_BLOCKS[searchBase + i * 2];
            var col = Vp9Constants.MV_REF_BLOCKS[searchBase + i * 2 + 1];
            if (isInside(miRow, miCol, row, col)) {
                var candidate = grid[(miRow + row) * miCols + miCol + col];
                counter += Vp9Constants.MODE_2_COUNTER[candidate.mode];
                differentRefFound = true;
                var sub = block >= 0 && candidate.sbType < Vp9Constants.BLOCK_8X8
                        ? COLUMN_TO_SUBBLOCK[block * 2 + (col == 0 ? 1 : 0)] : -1;
                if (candidate.ref0 == refFrame) {
                    done = add(sub >= 0 ? candidate.subMv0[sub] : candidate.mv0);
                } else if (candidate.ref1 == refFrame) {
                    done = add(sub >= 0 ? candidate.subMv1[sub] : candidate.mv1);
                }
            }
        }
        if (!done) {
            if (i < 2) {
                i = 2;
            }
            constMotion = rt;
            for (; i < 8 && !done; i++) {
                if (rt && found > 0) {
                    break;
                }
                var row = Vp9Constants.MV_REF_BLOCKS[searchBase + i * 2];
                var col = Vp9Constants.MV_REF_BLOCKS[searchBase + i * 2 + 1];
                if (isInside(miRow, miCol, row, col)) {
                    var candidate = grid[(miRow + row) * miCols + miCol + col];
                    differentRefFound = true;
                    if (candidate.ref0 == refFrame) {
                        done = add(candidate.mv0);
                    } else if (candidate.ref1 == refFrame) {
                        done = add(candidate.mv1);
                    }
                }
            }
        }
        var prevMvs = !rt && enc.usePrevFrameMvs ? enc.prevFrameMvs : null;
        var prevIndex = (miRow * miCols + miCol) * 4;
        if (!done && prevMvs != null) {
            if (prevMvs[prevIndex] == refFrame) {
                done = add(prevMvs[prevIndex + 2]);
            } else if (prevMvs[prevIndex + 1] == refFrame) {
                done = add(prevMvs[prevIndex + 3]);
            }
        }
        if (!done && differentRefFound && !(rt && found > 0)) {
            for (i = 0; i < 8 && !done; i++) {
                var row = Vp9Constants.MV_REF_BLOCKS[searchBase + i * 2];
                var col = Vp9Constants.MV_REF_BLOCKS[searchBase + i * 2 + 1];
                if (isInside(miRow, miCol, row, col)) {
                    var candidate = grid[(miRow + row) * miCols + miCol + col];
                    if (candidate.isInter()) {
                        if (candidate.ref0 != refFrame) {
                            done = add(scaleMv(candidate.mv0, candidate.ref0, refFrame));
                        }
                        if (!done && candidate.hasSecondRef() && candidate.ref1 != refFrame
                                && candidate.mv1 != candidate.mv0) {
                            done = add(scaleMv(candidate.mv1, candidate.ref1, refFrame));
                        }
                    }
                }
            }
        }
        if (!done && prevMvs != null) {
            var prevRef0 = prevMvs[prevIndex];
            var prevRef1 = prevMvs[prevIndex + 1];
            if (prevRef0 != refFrame && prevRef0 > INTRA_FRAME) {
                done = add(scaleMv(prevMvs[prevIndex + 2], prevRef0, refFrame));
            }
            if (!done && prevRef1 > INTRA_FRAME && prevRef1 != refFrame
                    && prevMvs[prevIndex + 3] != prevMvs[prevIndex + 2]) {
                add(scaleMv(prevMvs[prevIndex + 3], prevRef1, refFrame));
            }
        }
        c.modeContext[refFrame] = Vp9Constants.COUNTER_TO_CONTEXT[counter];
        var minCol = c.mbToLeftEdge - 128;
        var maxCol = c.mbToRightEdge + 128;
        var minRow = c.mbToTopEdge - 128;
        var maxRow = c.mbToBottomEdge + 128;
        for (var k = 0; k < 2; k++) {
            var mv = mvList[k];
            mvList[k] = ModeInfo.packMv(Math.clamp(mv >> 16, minRow, maxRow), Math.clamp((short) mv, minCol, maxCol));
        }
        return constMotion;
    }

    /**
     * Finds the nearest and near vectors of one sub-block of the current sub-8x8 block from its
     * candidate list and the vectors already chosen for the block's earlier sub-blocks
     * ({@code vp9_append_sub8x8_mvs_for_idx}), leaving them in {@link #subNearest} and
     * {@link #subNear}.
     *
     * @param refFrame the reference
     * @param which 0 for the block's first reference, 1 for its second
     * @param bsize the block size, smaller than 8x8
     * @param block the sub-block, from 0 to 3
     */
    void appendSub8x8Mvs(int refFrame, int which, int bsize, int block) {
        findMvRefs(refFrame, bsize, false, block);
        var m = coder.mi;
        var mvs = which == 0 ? m.subMv0 : m.subMv1;
        var near = 0;
        int nearest;
        switch (block) {
            case 0 -> {
                nearest = mvList[0];
                near = mvList[1];
            }
            case 1, 2 -> {
                nearest = mvs[0];
                for (var n = 0; n < 2; n++) {
                    if (nearest != mvList[n]) {
                        near = mvList[n];
                        break;
                    }
                }
            }
            default -> {
                nearest = mvs[2];
                var candidates = subCandidates;
                candidates[0] = mvs[1];
                candidates[1] = mvs[0];
                candidates[2] = mvList[0];
                candidates[3] = mvList[1];
                for (var n = 0; n < 4; n++) {
                    if (nearest != candidates[n]) {
                        near = candidates[n];
                        break;
                    }
                }
            }
        }
        subNearest = nearest;
        subNear = near;
    }

    /**
     * The nearest vector of the last {@link #appendSub8x8Mvs}.
     */
    int subNearest;

    /**
     * The near vector of the last {@link #appendSub8x8Mvs}.
     */
    int subNear;

    /**
     * The candidates of the last sub-block of {@link #appendSub8x8Mvs}.
     */
    private final int[] subCandidates = new int[4];

    /**
     * Returns a candidate vector of another reference, negated when that reference lies on the
     * other side of the current frame in display order ({@code scale_mv}).
     *
     * @param mv the packed vector
     * @param candidateRef the candidate's reference
     * @param refFrame the reference the list is built for
     * @return the packed vector
     */
    private int scaleMv(int mv, int candidateRef, int refFrame) {
        var bias = coder.enc.signBias;
        if (bias[candidateRef] != bias[refFrame]) {
            return ModeInfo.packMv(-(mv >> 16), -(short) mv);
        }
        return mv;
    }

    /**
     * Returns whether a candidate position lies inside the tile's columns and the frame's rows
     * ({@code is_inside}).
     *
     * @param miRow the block's 8x8 row
     * @param miCol the block's 8x8 column
     * @param row the candidate's row offset
     * @param col the candidate's column offset
     * @return whether the candidate exists
     */
    private boolean isInside(int miRow, int miCol, int row, int col) {
        var c = coder;
        return !(miRow + row < 0 || miCol + col < c.miColStart || miRow + row >= c.enc.miRows
                || miCol + col >= c.miColEnd);
    }

    /**
     * Measures the candidate vectors of a reference on the block and remembers the best start
     * for the motion search ({@code vp9_mv_pred}).
     *
     * @param picture the reference picture
     * @param ref the reference
     * @param bsize the block size
     */
    void mvPred(Vp9EncPicture picture, int ref, int bsize) {
        var c = coder;
        var src = c.enc.source;
        var numMvRefs = 2 + (bsize < BLOCK_64X64 ? 1 : 0);
        var nearSameNearest = c.refMvs[ref * 2] == c.refMvs[ref * 2 + 1];
        var zeroSeen = false;
        var bestIndex = 0;
        var bestSad = Integer.MAX_VALUE;
        var bw = Vp9RtCoder.NUM_4X4_WIDE[bsize] * 4;
        var bh = Vp9RtCoder.NUM_4X4_HIGH[bsize] * 4;
        var base = picture.offset(0, c.miCol * 8, c.miRow * 8);
        var stride = picture.strides[0];
        for (var i = 0; i < numMvRefs; i++) {
            var mv = i < 2 ? c.refMvs[ref * 2 + i] : c.predMv[ref];
            var row = mv >> 16;
            var col = (short) mv;
            if (row == Short.MAX_VALUE || col == Short.MAX_VALUE) {
                continue;
            }
            if (i == 1 && nearSameNearest) {
                continue;
            }
            var fpRow = (row + 3 + (row >= 0 ? 1 : 0)) >> 3;
            var fpCol = (col + 3 + (col >= 0 ? 1 : 0)) >> 3;
            if (fpRow == 0 && fpCol == 0 && zeroSeen) {
                continue;
            }
            zeroSeen |= fpRow == 0 && fpCol == 0;
            var sad = c.edsp.sad(src.planes[0], c.srcOff[0], src.strides[0], picture.planes[0],
                    base + fpRow * stride + fpCol, stride, bw, bh);
            if (sad < bestSad) {
                bestSad = sad;
                bestIndex = i;
            }
        }
        c.mvBestRefIndex[ref] = bestIndex;
        c.predMvSad[ref] = bestSad;
    }

    /**
     * Searches the new motion vector of a reference and returns its rate, or {@code -1} when the
     * mode should be skipped ({@code search_new_mv} and {@code combined_motion_search}).
     *
     * @param ref the reference
     * @param bsize the block size
     * @param bestPredSad the best prediction difference of the last reference so far
     * @param bestRdSoFar the best cost so far
     * @param gfTemporalRef whether the golden reference is a long-term temporal reference
     * @return the vector's rate, or {@code -1}
     */
    private int searchNewMv(int ref, int bsize, int bestPredSad, long bestRdSoFar, boolean gfTemporalRef) {
        var c = coder;
        var enc = c.enc;
        var sf = enc.sf;
        var motion = c.motion;
        var refMv = c.refMvs[ref * 2];
        var refRow = refMv >> 16;
        var refCol = (short) refMv;
        var picture = enc.activeRef(ref);
        var subpelCosts = sf.subpelPrunedEvenMore;
        if (ref > LAST_FRAME && gfTemporalRef) {
            if (bsize < BLOCK_16X16) {
                return -1;
            }
            var saved = c.mi.mv0;
            var tmpSad = motion.intProMotionEstimation(bsize, picture, refMv);
            if (Integer.compareUnsigned(tmpSad, c.predMvSad[LAST_FRAME]) > 0) {
                c.mi.mv0 = saved;
                return -1;
            }
            if ((long) tmpSad + (Vp9RtCoder.B_WIDTH_LOG2[bsize] + Vp9RtCoder.B_HEIGHT_LOG2[bsize] + 4 << 4)
                    > bestPredSad) {
                c.mi.mv0 = saved;
                return -1;
            }
            var mv = c.mi.mv0;
            c.mi.mv0 = saved;
            var rateMv = motion.mvBitCost(mv >> 16, (short) mv, refRow, refCol);
            Arrays.fill(motion.costList, Vp9RtMotion.INT_MAX);
            subpel(motion, (mv >> 16) >> 3, ((short) mv) >> 3, refRow, refCol, sf.subpelForceStop, subpelCosts);
            c.predSse[ref] = (int) motion.sse1;
            frameMv[fmv(NEWMV, ref)] = ModeInfo.packMv(motion.bestRow, motion.bestCol);
            return rateMv;
        }
        motion.setup(bsize, picture);
        motion.setSearchRange(refRow, refCol);
        if (enc.speed > 5 && c.lowvarHighsumdiff) {
            motion.colMin = Math.max(motion.colMin, -10);
            motion.rowMin = Math.max(motion.rowMin, -10);
            motion.colMax = Math.min(motion.colMax, 10);
            motion.rowMax = Math.min(motion.rowMax, 10);
        }
        int mvpRow;
        int mvpCol;
        var bestIndex = c.mvBestRefIndex[ref];
        var start = bestIndex < 2 ? c.refMvs[ref * 2 + bestIndex] : c.predMv[ref];
        mvpRow = (start >> 16) >> 3;
        mvpCol = ((short) start) >> 3;
        int tmpRow;
        int tmpCol;
        if (c.sbUseMvPart) {
            Arrays.fill(motion.costList, Vp9RtMotion.INT_MAX);
            tmpRow = c.sbMvrowPart >> 3;
            tmpCol = c.sbMvcolPart >> 3;
        } else {
            motion.fullPixelSearch(mvpRow, mvpCol, sf.fullpelSearchStepParam, sf.searchMethod, c.sadPerBit16,
                    subpelCosts, refRow, refCol);
            tmpRow = motion.bestRow;
            tmpCol = motion.bestCol;
        }
        motion.setup(bsize, picture);
        var rateMv = motion.mvBitCost(tmpRow * 8, tmpCol * 8, refRow, refCol);
        var rateMode = enc.interModeCost[c.modeContext[ref] * 4 + (NEWMV - NEARESTMV)];
        var rv = !(Vp9RtCoder.rdCost(c.rdmult, rateMv + rateMode, 0) > bestRdSoFar);
        var searchSubpel = !(useSvcNonReference() && tmpRow == 0 && tmpCol == 0);
        var resultRow = tmpRow * 8;
        var resultCol = tmpCol * 8;
        if (rv && searchSubpel) {
            var forceStop = sf.subpelForceStop;
            if (sf.adaptiveSubpelForceStop) {
                forceStop = Math.abs(tmpRow) >= sf.adaptiveSubpelMvThresh || Math.abs(tmpCol) >= sf.adaptiveSubpelMvThresh
                        ? sf.adaptiveSubpelAbove : sf.adaptiveSubpelBelow;
            }
            subpel(motion, tmpRow, tmpCol, refRow, refCol, forceStop, subpelCosts);
            c.predSse[ref] = (int) motion.sse1;
            resultRow = motion.bestRow;
            resultCol = motion.bestCol;
            rateMv = motion.mvBitCost(resultRow, resultCol, refRow, refCol);
        }
        frameMv[fmv(NEWMV, ref)] = ModeInfo.packMv(resultRow, resultCol);
        return rv ? rateMv : -1;
    }

    /**
     * Returns whether the frame is a temporal layer frame no other frame references.
     *
     * @return whether it is
     */
    private boolean useSvcNonReference() {
        var enc = coder.enc;
        return enc.temporalLayers > 1 && enc.nonReferenceFrame;
    }

    /**
     * Runs the sub-pixel search of the speed.
     *
     * @param motion the motion search, set up on the block
     * @param row the full-pixel row
     * @param col the full-pixel column
     * @param refRow the eighth-pixel reference row
     * @param refCol the eighth-pixel reference column
     * @param forceStop the coarsest precision
     * @param useCosts whether the pruned search with the cost list is used
     */
    private void subpel(Vp9RtMotion motion, int row, int col, int refRow, int refCol, int forceStop,
                        boolean useCosts) {
        var enc = coder.enc;
        if (useCosts) {
            motion.subpelPrunedEvenMore(row, col, refRow, refCol, enc.allowHp, forceStop, enc.sf.subpelIters);
        } else {
            motion.subpelTree(row, col, refRow, refCol, enc.allowHp, forceStop, enc.sf.subpelIters);
        }
    }

    /**
     * Returns a free prediction buffer and marks it used ({@code get_pred_buffer}).
     *
     * @return the buffer index
     */
    private int getPredBuffer() {
        for (var i = 0; i < 3; i++) {
            if (!tmpInUse[i]) {
                tmpInUse[i] = true;
                return i;
            }
        }
        throw new IllegalStateException("no free prediction buffer");
    }

    /**
     * Releases a prediction buffer ({@code free_pred_buffer}).
     *
     * @param index the buffer index, {@code 3} for the reconstruction or negative for none
     */
    private void freePredBuffer(int index) {
        if (index >= 0 && index < 3) {
            tmpInUse[index] = false;
        }
    }

    /**
     * Points the current prediction at a buffer.
     *
     * @param index the buffer index, {@code 3} for the reconstruction
     * @param bw the block width, the stride of the scratch buffers
     */
    private void selectPred(int index, int bw) {
        if (index == 3) {
            var recon = coder.enc.recon;
            predBuf = recon.planes8[0];
            predOff = coder.dstOff[0];
            predStride = recon.strides[0];
        } else {
            predBuf = tmp[index];
            predOff = 0;
            predStride = bw;
        }
    }

    /**
     * Copies a block between planes.
     *
     * @param src the source plane
     * @param srcOff the index of the source block
     * @param srcStride the source stride
     * @param dst the destination plane
     * @param dstOff the index of the destination block
     * @param dstStride the destination stride
     * @param w the width
     * @param h the height
     */
    private static void copyBlock(byte[] src, int srcOff, int srcStride, byte[] dst, int dstOff, int dstStride, int w,
                                  int h) {
        for (var y = 0; y < h; y++) {
            System.arraycopy(src, srcOff + y * srcStride, dst, dstOff + y * dstStride, w);
        }
    }

    /**
     * Tries the regular and smooth interpolation filters on the current mode and keeps the
     * cheaper ({@code search_filter_ref}).
     *
     * @param bsize the block size
     * @param reuseInterPred whether predictions are kept in buffers
     * @param thisModePred the buffer of the current mode
     * @param useModelYrdLarge whether the large-block model is used
     * @return the buffer holding the kept prediction
     */
    private int searchFilterRef(int bsize, boolean reuseInterPred, int thisModePred, boolean useModelYrdLarge) {
        var c = coder;
        var mi = c.mi;
        var bw = Vp9RtCoder.NUM_4X4_WIDE[bsize] * 4;
        long bestCost = Long.MAX_VALUE;
        var bestFilter = SWITCHABLE;
        var currentPred = thisModePred;
        long bestRate = 0;
        long bestDist = 0;
        long bestVar = 0;
        long bestSse = 0;
        var bestTx = 0;
        var bestSkipTxfm = SKIP_TXFM_NONE;
        var bestEarly = false;
        var bestUv0 = false;
        var bestUv1 = false;
        for (var filter = EIGHTTAP; filter <= EIGHTTAP_SMOOTH; filter++) {
            mi.interpFilter = filter;
            c.buildInterPredictorInto(0, bsize, mi.ref0, mi.mv0, filter, predBuf, predOff, predStride);
            if (useModelYrdLarge) {
                modelRdForSbYLarge(bsize);
            } else {
                modelRdForSbY(bsize, false);
            }
            var rate = modelRate + switchableRate(filter);
            var cost = Vp9RtCoder.rdCost(c.rdmult, rate, modelDist);
            if (cost < bestCost) {
                bestFilter = filter;
                bestCost = cost;
                bestRate = modelRate;
                bestDist = modelDist;
                bestVar = varY;
                bestSse = sseY;
                bestTx = mi.txSize;
                bestSkipTxfm = c.skipTxfm;
                bestEarly = earlyTerm;
                bestUv0 = predUvComputed[0];
                bestUv1 = predUvComputed[1];
                if (reuseInterPred) {
                    if (thisModePred != currentPred) {
                        freePredBuffer(thisModePred);
                        thisModePred = currentPred;
                    }
                    if (filter != EIGHTTAP_SMOOTH) {
                        currentPred = getPredBuffer();
                        selectPred(currentPred, bw);
                    }
                }
            }
        }
        if (reuseInterPred && thisModePred != currentPred) {
            freePredBuffer(currentPred);
        }
        mi.interpFilter = bestFilter;
        mi.txSize = bestTx;
        modelRate = bestRate;
        modelDist = bestDist;
        varY = bestVar;
        sseY = bestSse;
        c.skipTxfm = bestSkipTxfm;
        earlyTerm = bestEarly;
        predUvComputed[0] = bestUv0;
        predUvComputed[1] = bestUv1;
        if (reuseInterPred) {
            selectPred(thisModePred, bw);
        } else if (bestFilter < EIGHTTAP_SMOOTH) {
            c.buildInterPredictorInto(0, bsize, mi.ref0, mi.mv0, bestFilter, predBuf, predOff, predStride);
        }
        return thisModePred;
    }

    /**
     * Chooses the transform size of a block from its residual variance
     * ({@code calculate_tx_size}).
     *
     * @param bsize the block size
     * @param var the residual variance
     * @param sse the residual sum of squares
     * @param acThr the AC skip threshold
     * @param isIntra whether the block is intra
     * @return the transform size
     */
    private int calculateTxSize(int bsize, long var, long sse, long acThr, boolean isIntra) {
        var enc = coder.enc;
        var varThresh = isIntra ? acThr : 1;
        var limitTx = !(enc.cyclicRefreshEnabled && (coder.sourceVariance == 0 || var < varThresh));
        if (enc.txMode == TX_MODE_SELECT) {
            int tx;
            if (sse > (var << 2)) {
                tx = Math.min(Vp9Constants.MAX_TX_SIZE[bsize], Vp9Constants.TX_MODE_TO_BIGGEST[enc.txMode]);
            } else {
                tx = TX_8X8;
            }
            if (enc.cyclicRefreshEnabled && limitTx && Vp9RtCyclicRefresh.boosted(coder.mi.segmentId)) {
                tx = TX_8X8;
            } else if (tx > TX_16X16 && limitTx) {
                tx = TX_16X16;
            }
            return tx;
        }
        return Math.min(Vp9Constants.MAX_TX_SIZE[bsize], Vp9Constants.TX_MODE_TO_BIGGEST[enc.txMode]);
    }

    /**
     * Estimates the luma rate and distortion of the current prediction from its variance, and
     * picks the transform size ({@code model_rd_for_sb_y}).
     *
     * @param bsize the block size
     * @param isIntra whether the prediction is intra
     */
    private void modelRdForSbY(int bsize, boolean isIntra) {
        var c = coder;
        var enc = c.enc;
        var src = enc.source;
        var q = enc.quant;
        var pidx = c.qindex * 2;
        var zbinDc = (long) q.yZbin[pidx];
        var zbinAc = (long) q.yZbin[pidx + 1];
        var dcThr = (zbinDc * zbinDc) >> 6;
        var acThr = (zbinAc * zbinAc) >> 6;
        var dcQuant = q.yDequant[pidx];
        var acQuant = q.yDequant[pidx + 1];
        var w = Vp9RtCoder.NUM_4X4_WIDE[bsize] * 4;
        var h = Vp9RtCoder.NUM_4X4_HIGH[bsize] * 4;
        var n = log2(w * h);
        var packed = c.edsp.variance(src.planes[0], c.srcOff[0], src.strides[0], predBuf, predOff, predStride, w, h);
        var sse = Vp9EncDsp.sse(packed);
        var var = Vp9EncDsp.variance(packed, n);
        varY = var;
        sseY = sse;
        c.mi.txSize = calculateTxSize(bsize, var, sse, acThr, isIntra);
        var unit = Vp9RtCoder.TXSIZE_TO_BSIZE[c.mi.txSize];
        var numBlkLog2 = (Vp9RtCoder.B_WIDTH_LOG2[bsize] - Vp9RtCoder.B_WIDTH_LOG2[unit])
                + (Vp9RtCoder.B_HEIGHT_LOG2[bsize] - Vp9RtCoder.B_HEIGHT_LOG2[unit]);
        var sseTx = sse >> numBlkLog2;
        var varTx = var >> numBlkLog2;
        var skipDc = false;
        c.skipTxfm = SKIP_TXFM_NONE;
        if (varTx < acThr || var == 0) {
            c.skipTxfm = SKIP_TXFM_AC_ONLY;
            if (sseTx - varTx < dcThr || sse == var) {
                c.skipTxfm = SKIP_TXFM_AC_DC;
            }
        } else if (sseTx - varTx < dcThr || sse == var) {
            skipDc = true;
        }
        if (c.skipTxfm == SKIP_TXFM_AC_DC) {
            modelRate = 0;
            modelDist = sse << 4;
            return;
        }
        finishModel(sse, var, n, dcQuant, acQuant, skipDc);
    }

    /**
     * Completes a luma model estimate from the DC and AC energies.
     *
     * @param sse the sum of squares
     * @param var the variance
     * @param n the log2 of the sample count
     * @param dcQuant the DC quantizer step
     * @param acQuant the AC quantizer step
     * @param skipDc whether the DC coefficient quantizes to zero
     */
    private void finishModel(long sse, long var, int n, int dcQuant, int acQuant, boolean skipDc) {
        if (!skipDc) {
            var m = Vp9EncTables.modelRdFromVar(sse - var, n, dcQuant >> 3);
            modelRate = (m >>> 32) >> 1;
            modelDist = (m & 0xFFFFFFFFL) << 3;
        } else {
            modelRate = 0;
            modelDist = (sse - var) << 4;
        }
        var m = Vp9EncTables.modelRdFromVar(var, n, acQuant >> 3);
        modelRate += m >>> 32;
        modelDist += (m & 0xFFFFFFFFL) << 4;
    }

    /**
     * Estimates the luma rate and distortion of a large block from its 8x8 variances, testing
     * whether the whole block, chroma included, can skip its residual
     * ({@code model_rd_for_sb_y_large}).
     *
     * @param bsize the block size
     */
    private void modelRdForSbYLarge(int bsize) {
        var c = coder;
        var enc = c.enc;
        var src = enc.source;
        var q = enc.quant;
        var pidx = c.qindex * 2;
        var dcQuant = q.yDequant[pidx];
        var acQuant = q.yDequant[pidx + 1];
        var dcThr = ((long) dcQuant * dcQuant) >> 6;
        var acThr = ((long) acQuant * acQuant) >> 6;
        var bwl = Vp9RtCoder.B_WIDTH_LOG2[bsize];
        var bhl = Vp9RtCoder.B_HEIGHT_LOG2[bsize];
        var w = 4 << bwl;
        var h = 4 << bhl;
        var num8x8 = 1 << (bwl + bhl - 2);
        long sse = 0;
        var sum = 0;
        var k = 0;
        var s = src.planes[0];
        var sOff = c.srcOff[0];
        var sStride = src.strides[0];
        for (var i = 0; i < h; i += 8) {
            for (var j = 0; j < w; j += 8) {
                var p = c.edsp.variance(s, sOff + sStride * i + j, sStride, predBuf, predOff + predStride * i + j,
                        predStride, 8, 8);
                sse8x8[k] = Vp9EncDsp.sse(p);
                sum8x8[k] = Vp9EncDsp.sum(p);
                sse += sse8x8[k];
                sum += sum8x8[k];
                var kSqr = ((long) sum8x8[k] * sum8x8[k]) >> 6;
                var unsignedSse = sse8x8[k] & 0xFFFFFFFFL;
                var sq = kSqr & 0xFFFFFFFFL;
                var8x8[k] = unsignedSse > sq ? unsignedSse - sq : sq - unsignedSse;
                k++;
            }
        }
        var sumSqr = (((long) sum * sum) & 0xFFFFFFFFL) >>> (bwl + bhl + 4);
        var var = sse > sumSqr ? sse - sumSqr : sumSqr - sse;
        varY = var;
        sseY = sse;
        acThr *= acThrFactor(enc.speed, enc.width, enc.height, Math.abs(sum) >> (bwl + bhl));
        var tx = calculateTxSize(bsize, var, sse, acThr, false);
        if (tx < TX_8X8) {
            tx = TX_8X8;
        }
        c.mi.txSize = tx;
        var num16 = num8x8 >> 2;
        var num32 = num8x8 >> 4;
        if (tx >= TX_16X16) {
            calculateVariance(bwl, bhl, TX_8X8, sse8x8, sum8x8, var16x16, sse16x16, sum16x16);
        }
        if (tx == TX_32X32) {
            calculateVariance(bwl, bhl, TX_16X16, sse16x16, sum16x16, var32x32, sse32x32, sum32x32);
        }
        var num = tx == TX_8X8 ? num8x8 : (tx == TX_16X16 ? num16 : num32);
        var sseTx = tx == TX_8X8 ? sse8x8 : (tx == TX_16X16 ? sse16x16 : sse32x32);
        var varTx = tx == TX_8X8 ? var8x8 : (tx == TX_16X16 ? var16x16 : var32x32);
        var acTest = true;
        var dcTest = true;
        c.skipTxfm = SKIP_TXFM_NONE;
        for (var i = 0; i < num; i++) {
            if (!(varTx[i] < acThr || var == 0)) {
                acTest = false;
                break;
            }
        }
        for (var i = 0; i < num; i++) {
            if (!(((sseTx[i] - varTx[i]) & 0xFFFFFFFFL) < dcThr || sse == var)) {
                dcTest = false;
                break;
            }
        }
        var skipDc = false;
        if (acTest) {
            c.skipTxfm = SKIP_TXFM_AC_ONLY;
            if (dcTest) {
                c.skipTxfm = SKIP_TXFM_AC_DC;
            }
        } else if (dcTest) {
            skipDc = true;
        }
        if (c.skipTxfm == SKIP_TXFM_AC_DC) {
            modelRate = 0;
            modelDist = sse << 4;
            var skipUv0 = false;
            var skipUv1 = false;
            for (var i = 1; i <= 2; i++) {
                var uvTx = enc.uvTxSize(c.mi);
                var unit = Vp9RtCoder.TXSIZE_TO_BSIZE[uvTx];
                var uvBsize = enc.uvBsize(bsize);
                var uvBw = Vp9RtCoder.B_WIDTH_LOG2[uvBsize];
                var uvBh = Vp9RtCoder.B_HEIGHT_LOG2[uvBsize];
                var sfac = (uvBw - Vp9RtCoder.B_WIDTH_LOG2[unit]) + (uvBh - Vp9RtCoder.B_HEIGHT_LOG2[unit]);
                var uvDc = q.uvDequant[pidx];
                var uvAc = q.uvDequant[pidx + 1];
                var uvDcThr = ((long) uvDc * uvDc) >> (6 - sfac);
                var uvAcThr = ((long) uvAc * uvAc) >> (6 - sfac);
                c.buildInterPredictor(i, bsize);
                predUvComputed[i - 1] = true;
                var recon = enc.recon;
                var uw = Vp9RtCoder.NUM_4X4_WIDE[uvBsize] * 4;
                var uh = Vp9RtCoder.NUM_4X4_HIGH[uvBsize] * 4;
                var p = c.edsp.variance(src.planes[i], c.srcOff[i], src.strides[i], recon.planes8[i], c.dstOff[i],
                        recon.strides[i], uw, uh);
                var uvSse = Vp9EncDsp.sse(p);
                var uvVar = Vp9EncDsp.variance(p, log2(uw * uh));
                if ((uvVar < uvAcThr || uvVar == 0) && (uvSse - uvVar < uvDcThr || uvSse == uvVar)) {
                    if (i == 1) {
                        skipUv0 = true;
                    } else {
                        skipUv1 = true;
                    }
                } else {
                    break;
                }
            }
            if (skipUv0 && skipUv1) {
                earlyTerm = true;
            }
            return;
        }
        finishModel(sse, var, bwl + bhl + 4, dcQuant, acQuant, skipDc);
    }

    /**
     * Raises the AC skip threshold of flat content at high speeds ({@code ac_thr_factor}).
     *
     * @param speed the speed, from 5 to 9
     * @param width the frame width
     * @param height the frame height
     * @param normSum the mean residual magnitude
     * @return the factor
     */
    private static int acThrFactor(int speed, int width, int height, int normSum) {
        if (speed >= 8 && normSum < 5) {
            return width <= 640 && height <= 480 ? 4 : 2;
        }
        return 1;
    }

    /**
     * Sums 2x2 groups of variance units into units twice as large ({@code calculate_variance}).
     *
     * @param bwl the log2 of the block width in 4x4 units
     * @param bhl the log2 of the block height in 4x4 units
     * @param txSize the input unit transform size
     * @param sseIn the input sums of squares
     * @param sumIn the input sums
     * @param varOut receives the variances
     * @param sseOut receives the sums of squares
     * @param sumOut receives the sums
     */
    private static void calculateVariance(int bwl, int bhl, int txSize, long[] sseIn, int[] sumIn, long[] varOut,
                                          long[] sseOut, int[] sumOut) {
        var unit = Vp9RtCoder.TXSIZE_TO_BSIZE[txSize];
        var nw = 1 << (bwl - Vp9RtCoder.B_WIDTH_LOG2[unit]);
        var nh = 1 << (bhl - Vp9RtCoder.B_HEIGHT_LOG2[unit]);
        var k = 0;
        for (var i = 0; i < nh; i += 2) {
            for (var j = 0; j < nw; j += 2) {
                sseOut[k] = (sseIn[i * nw + j] + sseIn[i * nw + j + 1] + sseIn[(i + 1) * nw + j]
                        + sseIn[(i + 1) * nw + j + 1]) & 0xFFFFFFFFL;
                sumOut[k] = sumIn[i * nw + j] + sumIn[i * nw + j + 1] + sumIn[(i + 1) * nw + j]
                        + sumIn[(i + 1) * nw + j + 1];
                var kSqr = (((long) sumOut[k] * sumOut[k]) >> (Vp9RtCoder.B_WIDTH_LOG2[unit]
                        + Vp9RtCoder.B_HEIGHT_LOG2[unit] + 6)) & 0xFFFFFFFFL;
                varOut[k] = sseOut[k] > kSqr ? sseOut[k] - kSqr : kSqr - sseOut[k];
                k++;
            }
        }
    }

    /**
     * Estimates the chroma rate and distortion of the current predictions of the sensitive
     * chroma planes, adding their variances and sums of squares to {@link #varY} and
     * {@link #sseY} ({@code model_rd_for_sb_uv}).
     *
     * @param uvBsize the chroma block size
     * @return the rate in the high 24 bits and the distortion in the low 40 bits
     */
    private long modelRdForSbUv(int uvBsize) {
        var c = coder;
        var enc = c.enc;
        var src = enc.source;
        var recon = enc.recon;
        var q = enc.quant;
        var pidx = c.qindex * 2;
        var w = Vp9RtCoder.NUM_4X4_WIDE[uvBsize] * 4;
        var h = Vp9RtCoder.NUM_4X4_HIGH[uvBsize] * 4;
        var n = log2(w * h);
        long rate = 0;
        long dist = 0;
        for (var i = 1; i <= 2; i++) {
            if (!c.colorSensitivity[i - 1]) {
                continue;
            }
            var p = c.edsp.variance(src.planes[i], c.srcOff[i], src.strides[i], recon.planes8[i], c.dstOff[i],
                    recon.strides[i], w, h);
            var sse = Vp9EncDsp.sse(p);
            var var = Vp9EncDsp.variance(p, n);
            varY += var;
            sseY += sse;
            var m = Vp9EncTables.modelRdFromVar(sse - var, n, q.uvDequant[pidx] >> 3);
            rate += (m >>> 32) >> 1;
            dist += (m & 0xFFFFFFFFL) << 3;
            m = Vp9EncTables.modelRdFromVar(var, n, q.uvDequant[pidx + 1] >> 3);
            rate += m >>> 32;
            dist += (m & 0xFFFFFFFFL) << 4;
        }
        return (rate << 40) | (dist & 0xFFFFFFFFFFL);
    }

    /**
     * Estimates the luma rate and distortion of a prediction by quantizing the Hadamard (or 4x4
     * DCT) coefficients of its residual ({@code block_yrd}).
     *
     * @param bsize the block size
     * @param txSize the transform size, at most 16x16
     * @param sOff the index of the block in the source
     * @param pred the prediction plane
     * @param pOff the index of the block in the prediction
     * @param pStride the prediction stride
     * @param sse the prediction's sum of squared errors, or {@link Long#MAX_VALUE}
     * @param rdComputed whether a model estimate was already made
     * @param isIntra whether the prediction is intra
     * @param modelRate the model estimate's rate, kept by the simplified estimate
     * @param modelDist the model estimate's distortion, kept by the simplified estimate
     * @return whether every transform block quantizes to zero; the rate, distortion and scaled
     * sum of squared errors are left in {@link #yrdRate}, {@link #yrdDist} and
     * {@link #yrdSse}
     */
    boolean blockYrd(int bsize, int txSize, int sOff, byte[] pred, int pOff, int pStride, long sse,
                     boolean rdComputed, boolean isIntra, long modelRate, long modelDist) {
        var c = coder;
        var enc = c.enc;
        var num4w = Vp9RtCoder.NUM_4X4_WIDE[bsize];
        var num4h = Vp9RtCoder.NUM_4X4_HIGH[bsize];
        if (enc.sf.useSimpleBlockYrd && !enc.keyFrame && (bsize < BLOCK_32X32
                || (enc.temporalLayers > 1 && (bsize < BLOCK_32X32 || enc.temporalLayerId > 0)))) {
            if (!rdComputed) {
                modelRdForSbY(bsize, isIntra);
                modelRate = this.modelRate;
                modelDist = this.modelDist;
            }
            yrdRate = modelRate;
            yrdDist = modelDist;
            yrdSse = Integer.MAX_VALUE;
            return false;
        }
        var step = 1 << (txSize << 1);
        var blockStep = 1 << txSize;
        var maxWide = num4w + (c.mbToRightEdge >= 0 ? 0 : c.mbToRightEdge >> 5);
        var maxHigh = num4h + (c.mbToBottomEdge >= 0 ? 0 : c.mbToBottomEdge >> 5);
        var bw = 4 * num4w;
        var bh = 4 * num4h;
        var src = enc.source;
        var diff = c.srcDiff;
        c.edsp.subtract(diff, 0, bw, src.planes[0], sOff, src.strides[0], pred, pOff, pStride, bw, bh);
        var skippable = true;
        var eobCost = 0;
        var q = enc.quant;
        var pidx = c.qindex * 2;
        var cf = c.coeff[0];
        var qc = c.qcoeff[0];
        var dq = c.dqcoeff[0];
        var scan = Vp9RtCoder.SCANS[txSize * 4];
        var iscan = Vp9RtCoder.ISCANS[txSize * 4];
        var rdResult = this.rdResult;
        long rate = 0;
        long dist = 0;
        var block = 0;
        for (var r = 0; r < maxHigh; r += blockStep) {
            for (var col = 0; col < num4w; col += blockStep) {
                if (col < maxWide) {
                    var off = block * 16;
                    var diffOff = (r * bw + col) << 2;
                    int n;
                    switch (txSize) {
                        case TX_16X16 -> {
                            c.edsp.hadamard16x16(diff, diffOff, bw, cf, off, c.hadamardScratch);
                            n = 256;
                        }
                        case TX_8X8 -> {
                            c.edsp.hadamard8x8(diff, diffOff, bw, cf, off, c.hadamardScratch);
                            n = 64;
                        }
                        default -> {
                            if (c.enc.lossless) {
                                Vp9FwdTxfm.fwht4x4(diff, diffOff, bw, cf, off);
                            } else {
                                c.edsp.fdct4x4(diff, diffOff, bw, cf, off, c.fdctScratch);
                            }
                            n = 16;
                        }
                    }
                    var eob = c.edsp.quantizeFpRd(cf, off, n, qc, dq, scan, iscan, q.yRoundFp, q.yQuantFp,
                            q.yDequant, pidx, rdResult);
                    rate += rdResult[0];
                    dist += rdResult[1] >> 2;
                    skippable &= eob == 0;
                    eobCost++;
                }
                block += step;
            }
        }
        if (sse < Long.MAX_VALUE) {
            sse = (sse << 6) >> 2;
            if (skippable) {
                yrdRate = 0;
                yrdDist = sse;
                yrdSse = sse;
                return true;
            }
        }
        rate <<= 2 + Vp9EncTables.PROB_COST_SHIFT;
        rate += (long) eobCost << Vp9EncTables.PROB_COST_SHIFT;
        yrdRate = rate;
        yrdDist = dist;
        yrdSse = sse;
        return skippable;
    }

    /**
     * Predicts the block's luma with an intra mode into the reconstruction, transform block by
     * transform block at the block's largest transform size ({@code compute_intra_yprediction}).
     *
     * @param mode the intra mode
     * @param bsize the block size
     */
    private void computeIntraYPrediction(int mode, int bsize) {
        var c = coder;
        var enc = c.enc;
        var txSize = Vp9Constants.MAX_TX_SIZE[bsize];
        var num4w = Vp9RtCoder.NUM_4X4_WIDE[bsize];
        var num4h = Vp9RtCoder.NUM_4X4_HIGH[bsize];
        var maxWide = num4w + (c.mbToRightEdge >= 0 ? 0 : c.mbToRightEdge >> 5);
        var maxHigh = num4h + (c.mbToBottomEdge >= 0 ? 0 : c.mbToBottomEdge >> 5);
        var recon = enc.recon;
        var dstStride = recon.strides[0];
        var src = enc.source;
        var bwl = Vp9RtCoder.B_WIDTH_LOG2[bsize];
        for (var row = 0; row < maxHigh; row += 1 << txSize) {
            for (var col = 0; col < maxWide; col += 1 << txSize) {
                var dOff = c.dstOff[0] + 4 * (row * dstStride + col);
                var sOff = c.srcOff[0] + 4 * (row * src.strides[0] + col);
                if (c.skipEncode) {
                    c.predictIntraBlock(0, bwl, txSize, mode, col, row, dOff, src.planes[0], sOff, src.strides[0]);
                } else {
                    c.predictIntraBlock(0, bwl, txSize, mode, col, row, dOff, recon.planes8[0], dOff, dstStride);
                }
            }
        }
    }

    /**
     * Raises the cost of a new vector far from its neighbors' and lowers it for small motion in
     * uniform lighting changes ({@code vp9_NEWMV_diff_bias}).
     *
     * @param mode the inter mode being evaluated
     * @param rdcost the cost
     * @param bsize the block size
     * @param mv the packed vector
     * @param isLastFrame whether the reference is the last frame
     * @return the biased cost
     */
    private long newmvDiffBias(int mode, long rdcost, int bsize, int mv, boolean isLastFrame) {
        var c = coder;
        var mvRow = mv >> 16;
        var mvCol = (short) mv;
        if (mode == NEWMV) {
            var above = c.aboveMi;
            var left = c.leftMi;
            var aboveValid = above != null && above.mv0 != Vp9RtCoder.INVALID_MV;
            var leftValid = left != null && left.mv0 != Vp9RtCoder.INVALID_MV;
            int avgRow;
            int avgCol;
            if (aboveValid && leftValid) {
                avgRow = ((above.mv0 >> 16) + (left.mv0 >> 16) + 1) >> 1;
                avgCol = ((short) above.mv0 + (short) left.mv0 + 1) >> 1;
            } else if (aboveValid) {
                avgRow = above.mv0 >> 16;
                avgCol = (short) above.mv0;
            } else if (leftValid) {
                avgRow = left.mv0 >> 16;
                avgCol = (short) left.mv0;
            } else {
                avgRow = 0;
                avgCol = 0;
            }
            var rowDiff = avgRow - mvRow;
            var colDiff = avgCol - mvCol;
            if (rowDiff > 48 || rowDiff < -48 || colDiff > 48 || colDiff < -48) {
                rdcost = bsize > BLOCK_32X32 ? rdcost << 1 : (3 * rdcost) >> 1;
            }
        }
        var noise = c.enc.noise;
        if (noise.enabled && noise.level >= Vp9RtNoiseEstimate.MEDIUM && bsize >= BLOCK_32X32 && isLastFrame
                && mvRow < 8 && mvRow > -8 && mvCol < 8 && mvCol > -8) {
            rdcost = 7 * (rdcost >> 3);
        } else if (c.lowvarHighsumdiff && !c.sbIsSkin && bsize >= BLOCK_16X16 && isLastFrame && mvRow < 16
                && mvRow > -16 && mvCol < 16 && mvCol > -16) {
            rdcost = 7 * (rdcost >> 3);
        }
        return rdcost;
    }

    /**
     * The rate the last breakout test assigned.
     */
    private long breakoutRate;

    /**
     * The distortion the last breakout test assigned.
     */
    private long breakoutDist;

    /**
     * Tests whether the current inter prediction is exact, so that the block codes without
     * residual, setting the coder's skip flag ({@code encode_breakout_test}).
     *
     * @implNote This implementation tests with the zero thresholds of libvpx's real-time
     * configuration, whose static threshold is 0: a block breaks out only when every plane is
     * predicted exactly.
     * @param bsize the block size
     * @param ref the reference, which with temporal layers never breaks out when it is the golden
     * frame
     * @param mode the inter mode, whose cost becomes the block's rate
     * @return whether the block was found skippable
     */
    private boolean encodeBreakoutTest(int bsize, int ref, int mode) {
        var c = coder;
        var enc = c.enc;
        if (enc.temporalLayers > 1 && ref == GOLDEN_FRAME) {
            return false;
        }
        var var = varY;
        var sse = sseY;
        if (var <= 0 && sse - var <= 0) {
            if (!predUvComputed[0] || !predUvComputed[1]) {
                c.buildInterPredictor(1, bsize);
                c.buildInterPredictor(2, bsize);
            }
            var src = enc.source;
            var recon = enc.recon;
            var uvBsize = enc.uvBsize(bsize);
            var w = Vp9RtCoder.NUM_4X4_WIDE[uvBsize] * 4;
            var h = Vp9RtCoder.NUM_4X4_HIGH[uvBsize] * 4;
            var n = log2(w * h);
            var pu = c.edsp.variance(src.planes[1], c.srcOff[1], src.strides[1], recon.planes8[1], c.dstOff[1],
                    recon.strides[1], w, h);
            var varU = Vp9EncDsp.variance(pu, n);
            var sseU = Vp9EncDsp.sse(pu);
            if ((varU << 2) <= 0 && sseU - varU <= 0) {
                var pv = c.edsp.variance(src.planes[2], c.srcOff[2], src.strides[2], recon.planes8[2], c.dstOff[2],
                        recon.strides[2], w, h);
                var varV = Vp9EncDsp.variance(pv, n);
                var sseV = Vp9EncDsp.sse(pv);
                if ((varV << 2) <= 0 && sseV - varV <= 0) {
                    c.skip = true;
                    breakoutRate = enc.interModeCost[c.modeContext[ref] * 4 + (mode - NEARESTMV)];
                    breakoutDist = sse << 4;
                    return true;
                }
            }
        }
        return false;
    }
}
