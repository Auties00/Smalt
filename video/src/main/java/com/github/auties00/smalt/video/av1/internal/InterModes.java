package com.github.auties00.smalt.video.av1.internal;

import static com.github.auties00.smalt.video.av1.internal.BlockDecoder.COMP_INTER_AVG;
import static com.github.auties00.smalt.video.av1.internal.BlockDecoder.COMP_INTER_NONE;
import static com.github.auties00.smalt.video.av1.internal.BlockDecoder.COMP_INTER_SEG;
import static com.github.auties00.smalt.video.av1.internal.BlockDecoder.COMP_INTER_WEDGE;
import static com.github.auties00.smalt.video.av1.internal.BlockDecoder.COMP_INTER_WEIGHTED_AVG;
import static com.github.auties00.smalt.video.av1.internal.BlockDecoder.GLOBALMV;
import static com.github.auties00.smalt.video.av1.internal.BlockDecoder.GLOBALMV_GLOBALMV;
import static com.github.auties00.smalt.video.av1.internal.BlockDecoder.INTER_INTRA_BLEND;
import static com.github.auties00.smalt.video.av1.internal.BlockDecoder.INTER_INTRA_NONE;
import static com.github.auties00.smalt.video.av1.internal.BlockDecoder.MM_TRANSLATION;
import static com.github.auties00.smalt.video.av1.internal.BlockDecoder.MM_WARP;
import static com.github.auties00.smalt.video.av1.internal.BlockDecoder.NEARESTMV;
import static com.github.auties00.smalt.video.av1.internal.BlockDecoder.NEARESTMV_NEARESTMV;
import static com.github.auties00.smalt.video.av1.internal.BlockDecoder.NEARMV;
import static com.github.auties00.smalt.video.av1.internal.BlockDecoder.NEWMV;
import static com.github.auties00.smalt.video.av1.internal.BlockDecoder.NEWMV_NEWMV;
import static com.github.auties00.smalt.video.av1.internal.BlockDecoder.fill;
import static com.github.auties00.smalt.video.av1.internal.Tables.BLOCK_DIMENSIONS;

/**
 * Decodes the modes, references and motion vectors of inter blocks (AV1 specification section
 * 5.11.23 to 5.11.32), reconstructs them and updates the contexts.
 *
 * @implNote This implementation ports the inter branch of dav1d's {@code decode_b}
 *           ({@code src/decode.c}).
 */
final class InterModes {
    /**
     * The nearest candidate index.
     */
    private static final int NEAREST_DRL = 0;

    /**
     * The second candidate index.
     */
    private static final int NEARER_DRL = 1;

    /**
     * The third candidate index.
     */
    private static final int NEAR_DRL = 2;

    /**
     * Prevents instantiation.
     */
    private InterModes() {
        throw new AssertionError();
    }

    /**
     * Decodes an inter block, reconstructs it and updates the contexts.
     *
     * @param t              the task context
     * @param bs             the block size
     * @param intraEdgeFlags the block's intra edge flags
     * @param segPred        the temporal segment prediction flag
     * @param seg            the segment whose features apply, or {@code -1}
     * @param haveTop        whether the top neighbor is available
     * @param haveLeft       whether the left neighbor is available
     * @return {@code false} when reconstruction fails
     */
    static boolean decodeInterBlock(TaskContext t, int bs, int intraEdgeFlags, int segPred, int seg, boolean haveTop,
                                    boolean haveLeft) {
        var ts = t.ts;
        var f = t.f;
        var hdr = f.frameHdr;
        var b = t.b;
        var msac = ts.msac;
        var cdf = ts.cdf;
        var bx4 = t.bx & 31;
        var by4 = t.by & 31;
        var layout = f.cur.layout;
        var ssVer = layout == SequenceHeader.LAYOUT_I420 ? 1 : 0;
        var ssHor = layout != SequenceHeader.LAYOUT_I444 ? 1 : 0;
        var cbx4 = bx4 >> ssHor;
        var cby4 = by4 >> ssVer;
        var bw4 = BLOCK_DIMENSIONS[bs * 4];
        var bh4 = BLOCK_DIMENSIONS[bs * 4 + 1];
        var w4 = Math.min(bw4, f.bw - t.bx);
        var h4 = Math.min(bh4, f.bh - t.by);
        var cbw4 = (bw4 + ssHor) >> ssHor;
        var cbh4 = (bh4 + ssVer) >> ssVer;
        var hasChroma = layout != SequenceHeader.LAYOUT_I400 && (bw4 > ssHor || (t.bx & 1) != 0)
                && (bh4 > ssVer || (t.by & 1) != 0);
        boolean isComp;
        boolean hasSubpelFilter;
        if (b.skipMode) {
            isComp = true;
        } else if ((seg < 0 || (hdr.segRef[seg] == -1 && !hdr.segGlobalmv[seg] && !hdr.segSkip[seg]))
                && hdr.switchableCompRefs && Math.min(bw4, bh4) > 1) {
            var ctx = BlockDecoder.compContext(t, by4, bx4, haveTop, haveLeft);
            isComp = msac.decodeBoolAdapt(cdf, Cdf.COMP + ctx * 2) != 0;
        } else {
            isComp = false;
        }
        var c = t.cand;
        if (b.skipMode) {
            b.ref[0] = hdr.skipModeRefs[0];
            b.ref[1] = hdr.skipModeRefs[1];
            b.compType = COMP_INTER_AVG;
            b.interMode = NEARESTMV_NEARESTMV;
            b.drlIdx = NEAREST_DRL;
            hasSubpelFilter = false;
            RefMvs.find(t.rt, c, b.ref[0] + 1, b.ref[1] + 1, bs, intraEdgeFlags, t.by, t.bx);
            b.mv[0] = RefMvs.fixMvPrecision(hdr, c.mv0[0]);
            b.mv[1] = RefMvs.fixMvPrecision(hdr, c.mv1[0]);
        } else if (isComp) {
            hasSubpelFilter = decodeCompound(t, bs, intraEdgeFlags, haveTop, haveLeft, bw4, bh4, by4, bx4);
        } else {
            hasSubpelFilter = decodeSingle(t, bs, intraEdgeFlags, seg, haveTop, haveLeft, bw4, bh4, w4, h4, by4, bx4);
        }
        int filter0;
        int filter1;
        if (hdr.subpelFilterMode == FrameHeader.FILTER_SWITCHABLE) {
            if (hasSubpelFilter) {
                var comp = b.compType != COMP_INTER_NONE ? 1 : 0;
                var ctx1 = BlockDecoder.filterContext(t, comp, 0, b.ref[0], by4, bx4);
                filter0 = msac.decodeSymbolAdapt(cdf, Cdf.FILTER + ctx1 * 4, 2);
                if (f.seqHdr.dualFilter) {
                    var ctx2 = BlockDecoder.filterContext(t, comp, 1, b.ref[0], by4, bx4);
                    filter1 = msac.decodeSymbolAdapt(cdf, Cdf.FILTER + (8 + ctx2) * 4, 2);
                } else {
                    filter1 = filter0;
                }
            } else {
                filter0 = 0;
                filter1 = 0;
            }
        } else {
            filter0 = hdr.subpelFilterMode;
            filter1 = hdr.subpelFilterMode;
        }
        b.filter2d = Tables.FILTER_2D[filter1 * 4 + filter0];
        BlockDecoder.readVartxTree(t, b, bs, bx4, by4);
        if (t.pass == TaskContext.PASS_PARSE) {
            BlockRecords.writeInter(t, b, filter0, filter1,
                    b.compType == COMP_INTER_NONE && b.motionMode == BlockDecoder.MM_WARP);
            BlockRecords.trackLowest(t, b, bs, w4, h4, hasChroma);
        }
        if (!Recon.reconBInter(t, bs, b)) {
            return false;
        }
        if ((hdr.loopfilterLevelY[0] != 0 || hdr.loopfilterLevelY[1] != 0) && t.pass != TaskContext.PASS_PARSE) {
            var isGlobalmv = b.interMode == (isComp ? GLOBALMV_GLOBALMV : GLOBALMV);
            var ytx = b.maxYtx;
            var uvtx = b.uvtx;
            if (hdr.segLossless[b.segId]) {
                ytx = 0;
                uvtx = 0;
            }
            LoopFilterMasks.createLfMaskInter(t.lfMask, f.lfLevel, f.b4Stride, ts.lflvl,
                    b.segId * 64 + (b.ref[0] + 1) * 2 + (isGlobalmv ? 0 : 1),
                    t.bx, t.by, f.w4, f.h4, b.skip, bs, ytx, b.txSplit0, b.txSplit1, uvtx, layout,
                    t.a.txLpfY, bx4, t.l.txLpfY, by4,
                    hasChroma ? t.a.txLpfUv : null, cbx4, hasChroma ? t.l.txLpfUv : null, cby4, t.lfTxa);
        }
        if (isComp) {
            var mode = b.interMode;
            var mf = (mode == GLOBALMV_GLOBALMV ? 1 : 0) | (((1 << mode) & 0xbc) != 0 ? 2 : 0);
            BlockDecoder.splatMv(t, bw4, bh4, b.mv[0], b.mv[1], RefMvs.packInfo(b.ref[0] + 1, b.ref[1] + 1, bs, mf));
        } else {
            var mode = b.interMode;
            var mf = (mode == GLOBALMV && Math.min(bw4, bh4) >= 2 ? 1 : 0) | (mode == NEWMV ? 2 : 0);
            BlockDecoder.splatMv(t, bw4, bh4, b.mv[0], 0,
                    RefMvs.packInfo(b.ref[0] + 1, b.interintraType != INTER_INTRA_NONE ? 0 : -1, bs, mf));
        }
        updateInterCtx(t, t.a, bx4, bw4, BLOCK_DIMENSIONS[bs * 4 + 2], b, segPred, filter0, filter1, 0);
        updateInterCtx(t, t.l, by4, bh4, BLOCK_DIMENSIONS[bs * 4 + 3], b, segPred, filter0, filter1, 1);
        if (hasChroma) {
            fill(t.a.uvmode, cbx4, cbw4, IpredDsp.DC_PRED);
            fill(t.l.uvmode, cby4, cbh4, IpredDsp.DC_PRED);
        }
        return true;
    }

    /**
     * Updates one edge of the contexts after an inter block.
     *
     * @param t       the task context
     * @param edge    the above or left context
     * @param off     the position within the context
     * @param n       the number of entries
     * @param lsz     the base-2 logarithm of the block size along the edge
     * @param b       the block
     * @param segPred the temporal segment prediction flag
     * @param filter0 the horizontal interpolation filter
     * @param filter1 the vertical interpolation filter
     * @param side    0 for the above edge, 1 for the left edge
     */
    private static void updateInterCtx(TaskContext t, BlockContext edge, int off, int n, int lsz, Av1Block b,
                                       int segPred, int filter0, int filter1, int side) {
        fill(edge.segPred, off, n, segPred);
        fill(edge.skipMode, off, n, b.skipMode ? 1 : 0);
        fill(edge.intra, off, n, 0);
        fill(edge.skip, off, n, b.skip ? 1 : 0);
        fill(edge.palSz, off, n, 0);
        fill(t.palSzUv, side * 32 + off, n, 0);
        fill(edge.txIntra, off, n, lsz);
        fill(edge.compType, off, n, b.compType);
        fill(edge.filter, off, n, filter0);
        fill(edge.filter, 32 + off, n, filter1);
        fill(edge.mode, off, n, b.interMode);
        fill(edge.ref, off, n, b.ref[0]);
        fill(edge.ref, 32 + off, n, b.ref[1]);
    }

    /**
     * Decodes the references, mode, motion vectors and compound type of a compound block.
     *
     * @param t              the task context
     * @param bs             the block size
     * @param intraEdgeFlags the block's intra edge flags
     * @param haveTop        whether the top neighbor is available
     * @param haveLeft       whether the left neighbor is available
     * @param bw4            the block width
     * @param bh4            the block height
     * @param by4            the row within the left context
     * @param bx4            the column within the above context
     * @return whether the block codes an interpolation filter
     */
    private static boolean decodeCompound(TaskContext t, int bs, int intraEdgeFlags, boolean haveTop,
                                          boolean haveLeft, int bw4, int bh4, int by4, int bx4) {
        var ts = t.ts;
        var f = t.f;
        var hdr = f.frameHdr;
        var b = t.b;
        var msac = ts.msac;
        var cdf = ts.cdf;
        var dirCtx = BlockDecoder.compDirContext(t, by4, bx4, haveTop, haveLeft);
        if (msac.decodeBoolAdapt(cdf, Cdf.COMP_DIR + dirCtx * 2) != 0) {
            var ctx1 = BlockDecoder.refContext(t, by4, bx4, haveTop, haveLeft, 0, 1, 2, 3);
            if (msac.decodeBoolAdapt(cdf, Cdf.COMP_FWD_REF + ctx1 * 2) != 0) {
                var ctx2 = BlockDecoder.refContext(t, by4, bx4, haveTop, haveLeft, 2, 2, 3, 3);
                b.ref[0] = 2 + msac.decodeBoolAdapt(cdf, Cdf.COMP_FWD_REF + (6 + ctx2) * 2);
            } else {
                var ctx2 = BlockDecoder.refContext(t, by4, bx4, haveTop, haveLeft, 0, 0, 1, 1);
                b.ref[0] = msac.decodeBoolAdapt(cdf, Cdf.COMP_FWD_REF + (3 + ctx2) * 2);
            }
            var ctx3 = BlockDecoder.refContext(t, by4, bx4, haveTop, haveLeft, 4, 5, 6, 6);
            if (msac.decodeBoolAdapt(cdf, Cdf.COMP_BWD_REF + ctx3 * 2) != 0) {
                b.ref[1] = 6;
            } else {
                var ctx4 = BlockDecoder.refContext(t, by4, bx4, haveTop, haveLeft, 4, 4, 5, 5);
                b.ref[1] = 4 + msac.decodeBoolAdapt(cdf, Cdf.COMP_BWD_REF + (3 + ctx4) * 2);
            }
        } else {
            var uctxP = BlockDecoder.refContext(t, by4, bx4, haveTop, haveLeft, 0, 3, 4, 6);
            if (msac.decodeBoolAdapt(cdf, Cdf.COMP_UNI_REF + uctxP * 2) != 0) {
                b.ref[0] = 4;
                b.ref[1] = 6;
            } else {
                var uctxP1 = BlockDecoder.refContext(t, by4, bx4, haveTop, haveLeft, 1, 1, 2, 3);
                b.ref[0] = 0;
                b.ref[1] = 1 + msac.decodeBoolAdapt(cdf, Cdf.COMP_UNI_REF + (3 + uctxP1) * 2);
                if (b.ref[1] == 2) {
                    var uctxP2 = BlockDecoder.refContext(t, by4, bx4, haveTop, haveLeft, 2, 2, 3, 3);
                    b.ref[1] += msac.decodeBoolAdapt(cdf, Cdf.COMP_UNI_REF + (6 + uctxP2) * 2);
                }
            }
        }
        var c = t.cand;
        RefMvs.find(t.rt, c, b.ref[0] + 1, b.ref[1] + 1, bs, intraEdgeFlags, t.by, t.bx);
        b.interMode = msac.decodeSymbolAdapt(cdf, Cdf.COMP_INTER_MODE + c.ctx * 8, 7);
        var im0 = Tables.COMP_INTER_PRED_MODES[b.interMode * 2];
        var im1 = Tables.COMP_INTER_PRED_MODES[b.interMode * 2 + 1];
        b.drlIdx = NEAREST_DRL;
        var nMvs = c.count;
        if (b.interMode == NEWMV_NEWMV) {
            if (nMvs > 1) {
                b.drlIdx += msac.decodeBoolAdapt(cdf, Cdf.DRL_BIT + BlockDecoder.drlCtx(c, 0) * 2);
                if (b.drlIdx == NEARER_DRL && nMvs > 2) {
                    b.drlIdx += msac.decodeBoolAdapt(cdf, Cdf.DRL_BIT + BlockDecoder.drlCtx(c, 1) * 2);
                }
            }
        } else if (im0 == NEARMV || im1 == NEARMV) {
            b.drlIdx = NEARER_DRL;
            if (nMvs > 2) {
                b.drlIdx += msac.decodeBoolAdapt(cdf, Cdf.DRL_BIT + BlockDecoder.drlCtx(c, 1) * 2);
                if (b.drlIdx == NEAR_DRL && nMvs > 3) {
                    b.drlIdx += msac.decodeBoolAdapt(cdf, Cdf.DRL_BIT + BlockDecoder.drlCtx(c, 2) * 2);
                }
            }
        }
        var hasSubpelFilter = Math.min(bw4, bh4) == 1 || b.interMode != GLOBALMV_GLOBALMV;
        hasSubpelFilter |= assignCompMv(t, 0, im0, bw4, bh4);
        hasSubpelFilter |= assignCompMv(t, 1, im1, bw4, bh4);
        var isSegwedge = 0;
        if (f.seqHdr.maskedCompound) {
            var maskCtx = BlockDecoder.maskCompContext(t, by4, bx4);
            isSegwedge = msac.decodeBoolAdapt(cdf, Cdf.MASK_COMP + maskCtx * 2);
        }
        if (isSegwedge == 0) {
            if (f.seqHdr.jntComp) {
                var jntCtx = BlockDecoder.jntCompContext(t, by4, bx4);
                b.compType = COMP_INTER_WEIGHTED_AVG + msac.decodeBoolAdapt(cdf, Cdf.JNT_COMP + jntCtx * 2);
            } else {
                b.compType = COMP_INTER_AVG;
            }
        } else {
            if ((BlockDecoder.WEDGE_ALLOWED_MASK & (1 << bs)) != 0) {
                var ctx = Tables.WEDGE_CTX_LUT[bs];
                b.compType = COMP_INTER_WEDGE - msac.decodeBoolAdapt(cdf, Cdf.WEDGE_COMP + ctx * 2);
                if (b.compType == COMP_INTER_WEDGE) {
                    b.wedgeIdx = msac.decodeSymbolAdapt(cdf, Cdf.WEDGE_IDX + ctx * 16, 15);
                }
            } else {
                b.compType = COMP_INTER_SEG;
            }
            b.maskSign = msac.decodeBoolEqui();
        }
        return hasSubpelFilter;
    }

    /**
     * Derives one motion vector of a compound block from its single-reference mode.
     *
     * @param t   the task context
     * @param idx the reference index
     * @param im  the single-reference mode of that reference
     * @param bw4 the block width
     * @param bh4 the block height
     * @return whether the derivation requires an interpolation filter (a translational global motion)
     */
    private static boolean assignCompMv(TaskContext t, int idx, int im, int bw4, int bh4) {
        var f = t.f;
        var hdr = f.frameHdr;
        var b = t.b;
        var c = t.cand;
        var arr = idx == 0 ? c.mv0 : c.mv1;
        switch (im) {
            case NEARMV, NEARESTMV -> {
                b.mv[idx] = RefMvs.fixMvPrecision(hdr, arr[b.drlIdx]);
                return false;
            }
            case GLOBALMV -> {
                var g = hdr.gmv[b.ref[idx]];
                b.mv[idx] = RefMvs.gmv2d(g, t.bx, t.by, bw4, bh4, hdr);
                return g.type == WarpedMotionParams.TRANSLATION;
            }
            default -> {
                var mvPrec = (hdr.hp ? 1 : 0) - (hdr.forceIntegerMv ? 1 : 0);
                b.mv[idx] = BlockDecoder.readMv(t.ts, arr[b.drlIdx], mvPrec);
                return false;
            }
        }
    }

    /**
     * Decodes the reference, mode, motion vector, inter-intra and motion mode of a single-reference
     * block.
     *
     * @param t              the task context
     * @param bs             the block size
     * @param intraEdgeFlags the block's intra edge flags
     * @param seg            the segment whose features apply, or {@code -1}
     * @param haveTop        whether the top neighbor is available
     * @param haveLeft       whether the left neighbor is available
     * @param bw4            the block width
     * @param bh4            the block height
     * @param w4             the visible block width
     * @param h4             the visible block height
     * @param by4            the row within the left context
     * @param bx4            the column within the above context
     * @return whether the block codes an interpolation filter
     */
    private static boolean decodeSingle(TaskContext t, int bs, int intraEdgeFlags, int seg, boolean haveTop,
                                        boolean haveLeft, int bw4, int bh4, int w4, int h4, int by4, int bx4) {
        var ts = t.ts;
        var f = t.f;
        var hdr = f.frameHdr;
        var b = t.b;
        var msac = ts.msac;
        var cdf = ts.cdf;
        b.compType = COMP_INTER_NONE;
        if (seg >= 0 && hdr.segRef[seg] > 0) {
            b.ref[0] = hdr.segRef[seg] - 1;
        } else if (seg >= 0 && (hdr.segGlobalmv[seg] || hdr.segSkip[seg])) {
            b.ref[0] = 0;
        } else {
            var ctx1 = BlockDecoder.refContext(t, by4, bx4, haveTop, haveLeft, 0, 3, 4, 6);
            if (msac.decodeBoolAdapt(cdf, Cdf.REF + ctx1 * 2) != 0) {
                var ctx2 = BlockDecoder.refContext(t, by4, bx4, haveTop, haveLeft, 4, 5, 6, 6);
                if (msac.decodeBoolAdapt(cdf, Cdf.REF + (3 + ctx2) * 2) != 0) {
                    b.ref[0] = 6;
                } else {
                    var ctx3 = BlockDecoder.refContext(t, by4, bx4, haveTop, haveLeft, 4, 4, 5, 5);
                    b.ref[0] = 4 + msac.decodeBoolAdapt(cdf, Cdf.REF + (15 + ctx3) * 2);
                }
            } else {
                var ctx2 = BlockDecoder.refContext(t, by4, bx4, haveTop, haveLeft, 0, 1, 2, 3);
                if (msac.decodeBoolAdapt(cdf, Cdf.REF + (6 + ctx2) * 2) != 0) {
                    var ctx3 = BlockDecoder.refContext(t, by4, bx4, haveTop, haveLeft, 2, 2, 3, 3);
                    b.ref[0] = 2 + msac.decodeBoolAdapt(cdf, Cdf.REF + (12 + ctx3) * 2);
                } else {
                    var ctx3 = BlockDecoder.refContext(t, by4, bx4, haveTop, haveLeft, 0, 0, 1, 1);
                    b.ref[0] = msac.decodeBoolAdapt(cdf, Cdf.REF + (9 + ctx3) * 2);
                }
            }
        }
        b.ref[1] = -1;
        var c = t.cand;
        RefMvs.find(t.rt, c, b.ref[0] + 1, -1, bs, intraEdgeFlags, t.by, t.bx);
        var ctx = c.ctx;
        var nMvs = c.count;
        boolean hasSubpelFilter;
        var segSkipOrGlobal = seg >= 0 && (hdr.segSkip[seg] || hdr.segGlobalmv[seg]);
        if (segSkipOrGlobal || msac.decodeBoolAdapt(cdf, Cdf.NEWMV_MODE + (ctx & 7) * 2) != 0) {
            if (segSkipOrGlobal || msac.decodeBoolAdapt(cdf, Cdf.GLOBALMV_MODE + ((ctx >> 3) & 1) * 2) == 0) {
                b.interMode = GLOBALMV;
                var g = hdr.gmv[b.ref[0]];
                b.mv[0] = RefMvs.gmv2d(g, t.bx, t.by, bw4, bh4, hdr);
                hasSubpelFilter = Math.min(bw4, bh4) == 1 || g.type == WarpedMotionParams.TRANSLATION;
            } else {
                hasSubpelFilter = true;
                if (msac.decodeBoolAdapt(cdf, Cdf.REFMV_MODE + ((ctx >> 4) & 15) * 2) != 0) {
                    b.interMode = NEARMV;
                    b.drlIdx = NEARER_DRL;
                    if (nMvs > 2) {
                        b.drlIdx += msac.decodeBoolAdapt(cdf, Cdf.DRL_BIT + BlockDecoder.drlCtx(c, 1) * 2);
                        if (b.drlIdx == NEAR_DRL && nMvs > 3) {
                            b.drlIdx += msac.decodeBoolAdapt(cdf, Cdf.DRL_BIT + BlockDecoder.drlCtx(c, 2) * 2);
                        }
                    }
                } else {
                    b.interMode = NEARESTMV;
                    b.drlIdx = NEAREST_DRL;
                }
                b.mv[0] = c.mv0[b.drlIdx];
                if (b.drlIdx < NEAR_DRL) {
                    b.mv[0] = RefMvs.fixMvPrecision(hdr, b.mv[0]);
                }
            }
        } else {
            hasSubpelFilter = true;
            b.interMode = NEWMV;
            b.drlIdx = NEAREST_DRL;
            if (nMvs > 1) {
                b.drlIdx += msac.decodeBoolAdapt(cdf, Cdf.DRL_BIT + BlockDecoder.drlCtx(c, 0) * 2);
                if (b.drlIdx == NEARER_DRL && nMvs > 2) {
                    b.drlIdx += msac.decodeBoolAdapt(cdf, Cdf.DRL_BIT + BlockDecoder.drlCtx(c, 1) * 2);
                }
            }
            if (nMvs > 1) {
                b.mv[0] = c.mv0[b.drlIdx];
            } else {
                b.mv[0] = RefMvs.fixMvPrecision(hdr, c.mv0[0]);
            }
            var mvPrec = (hdr.hp ? 1 : 0) - (hdr.forceIntegerMv ? 1 : 0);
            b.mv[0] = BlockDecoder.readMv(ts, b.mv[0], mvPrec);
        }
        var iiSzGrp = Tables.YMODE_SIZE_CONTEXT[bs];
        if (f.seqHdr.interIntra && (BlockDecoder.INTERINTRA_ALLOWED_MASK & (1 << bs)) != 0
                && msac.decodeBoolAdapt(cdf, Cdf.INTERINTRA + iiSzGrp * 2) != 0) {
            b.interintraMode = msac.decodeSymbolAdapt(cdf, Cdf.INTERINTRA_MODE + iiSzGrp * 4, 3);
            var wedgeCtx = Tables.WEDGE_CTX_LUT[bs];
            b.interintraType = INTER_INTRA_BLEND + msac.decodeBoolAdapt(cdf, Cdf.INTERINTRA_WEDGE + wedgeCtx * 2);
            if (b.interintraType == BlockDecoder.INTER_INTRA_WEDGE) {
                b.wedgeIdx = msac.decodeSymbolAdapt(cdf, Cdf.WEDGE_IDX + wedgeCtx * 16, 15);
            }
        } else {
            b.interintraType = INTER_INTRA_NONE;
        }
        if (hdr.switchableMotionMode && b.interintraType == INTER_INTRA_NONE && Math.min(bw4, bh4) >= 2
                && !(!hdr.forceIntegerMv && b.interMode == GLOBALMV
                && hdr.gmv[b.ref[0]].type > WarpedMotionParams.TRANSLATION)
                && BlockDecoder.hasOverlappable(t, by4, bx4, w4, h4, haveTop, haveLeft)) {
            BlockDecoder.matchingRefs(t, intraEdgeFlags, bw4, bh4, w4, h4, haveLeft, haveTop, b.ref[0]);
            var mask = t.warpMasks;
            var allowWarp = f.svcScale[b.ref[0] * 2] == 0 && !hdr.forceIntegerMv && hdr.warpMotion
                    && (mask[0] | mask[1]) != 0;
            b.motionMode = allowWarp
                    ? msac.decodeSymbolAdapt(cdf, Cdf.MOTION_MODE + bs * 4, 2)
                    : msac.decodeBoolAdapt(cdf, Cdf.OBMC + bs * 2);
            if (b.motionMode == MM_WARP) {
                hasSubpelFilter = false;
                BlockDecoder.warpmv(t, bw4, bh4, b.mv[0]);
            }
        } else {
            b.motionMode = MM_TRANSLATION;
        }
        return hasSubpelFilter;
    }
}
