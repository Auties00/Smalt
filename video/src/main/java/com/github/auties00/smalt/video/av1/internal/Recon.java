package com.github.auties00.smalt.video.av1.internal;

import static com.github.auties00.smalt.video.av1.internal.BlockDecoder.fill;
import static com.github.auties00.smalt.video.av1.internal.Tables.BLOCK_DIMENSIONS;

/**
 * Reconstructs blocks: intra prediction, palette and chroma-from-luma prediction, motion
 * compensation with every inter tool, and the residual of every transform block (AV1 specification
 * section 7.11 and 7.13).
 *
 * @implNote This implementation ports dav1d's {@code dav1d_recon_b_intra},
 * {@code dav1d_recon_b_inter}, {@code read_coef_tree}, {@code mc}, {@code obmc} and
 * {@code warp_affine} ({@code src/recon_tmpl.c}) for single-pass decoding.
 */
final class Recon {
    /**
     * The sample stride of the edge-extension buffer for unscaled prediction.
     */
    private static final int EMU_STRIDE = 192;

    /**
     * The sample stride of the edge-extension buffer for scaled prediction.
     */
    private static final int EMU_STRIDE_SCALED = 320;

    /**
     * Prevents instantiation.
     */
    private Recon() {
        throw new AssertionError();
    }

    /**
     * Fills a run of coefficient contexts.
     *
     * @param ctx the context array
     * @param off the first entry
     * @param n the number of entries
     * @param v the value
     */
    private static void setCtx(byte[] ctx, int off, int n, int v) {
        fill(ctx, off, n, v);
    }

    /**
     * Returns the offset of the top superblock edge of a block row, or {@code -1} when the block is
     * not at the top of a superblock row.
     *
     * @param f the frame
     * @param by the block row (luma 4-sample units)
     * @param y the row to test
     * @return the offset in the frame's intra edge buffer, or {@code -1}
     */
    private static int topSbEdge(FrameContext f, int by, int y) {
        if ((y & (f.sbStep - 1)) != 0) {
            return -1;
        }
        var sby = by >> f.sbShift;
        return f.sb128w * 128 * (sby - 1);
    }

    /**
     * Reconstructs an intra block ({@code dav1d_recon_b_intra}).
     *
     * @param t the task context
     * @param bs the block size
     * @param intraEdgeFlags the block's intra edge flags
     * @param b the block
     */
    static void reconBIntra(TaskContext t, int bs, int intraEdgeFlags, Av1Block b) {
        var ts = t.ts;
        var f = t.f;
        var ipred = IpredDsp.INSTANCE;
        var itx = t.f.itx;
        var bdMax = f.bitdepthMax;
        var cur = f.cur;
        var bx4 = t.bx & 31;
        var by4 = t.by & 31;
        var layout = cur.layout;
        var ssVer = layout == SequenceHeader.LAYOUT_I420 ? 1 : 0;
        var ssHor = layout != SequenceHeader.LAYOUT_I444 ? 1 : 0;
        var cbx4 = bx4 >> ssHor;
        var cby4 = by4 >> ssVer;
        var bw4 = BLOCK_DIMENSIONS[bs * 4];
        var bh4 = BLOCK_DIMENSIONS[bs * 4 + 1];
        var w4 = Math.min(bw4, f.bw - t.bx);
        var h4 = Math.min(bh4, f.bh - t.by);
        var cw4 = (w4 + ssHor) >> ssHor;
        var ch4 = (h4 + ssVer) >> ssVer;
        var hasChroma = layout != SequenceHeader.LAYOUT_I400 && (bw4 > ssHor || (t.bx & 1) != 0)
                && (bh4 > ssVer || (t.by & 1) != 0);
        var tx = b.tx;
        var txW = Tables.TX_W[tx];
        var txH = Tables.TX_H[tx];
        var uvtx = b.uvtx;
        var uvW = Tables.TX_W[uvtx];
        var uvH = Tables.TX_H[uvtx];
        var edge = t.edge;
        var edgeOff = IpredDsp.EDGE_TOPLEFT;
        var cbw4 = (bw4 + ssHor) >> ssHor;
        var cbh4 = (bh4 + ssVer) >> ssVer;
        var intraEdgeFilter = f.seqHdr.intraEdgeFilter;
        var intraEdgeFilterFlag = intraEdgeFilter ? IpredDsp.ANGLE_USE_EDGE_FILTER_FLAG : 0;
        var yStride = cur.stride[0];
        var yPlane = cur.data[0];
        var angle = t.angle;
        var pass = t.pass;
        var predict = pass != TaskContext.PASS_PARSE;
        for (var initY = 0; initY < h4; initY += 16) {
            var subH4 = Math.min(h4, 16 + initY);
            var subCh4 = Math.min(ch4, (initY + 16) >> ssVer);
            for (var initX = 0; initX < w4; initX += 16) {
                if (predict && b.palSz[0] != 0) {
                    var dst = 4 * (t.by * yStride + t.bx);
                    ipred.palPred(yPlane, dst, yStride, t.pal, 0, t.palIdxY, 0, bw4 * 4, bh4 * 4);
                }
                var intraFlags = t.a.smFlag(bx4) | t.l.smFlag(by4) | intraEdgeFilterFlag;
                var sbHasTr = initX + 16 < w4 ? 1 : initY != 0 ? 0 : intraEdgeFlags & IntraEdge.I444_TOP_HAS_RIGHT;
                var sbHasBl = initX != 0 ? 0 : initY + 16 < h4 ? 1 : intraEdgeFlags & IntraEdge.I444_LEFT_HAS_BOTTOM;
                var subW4 = Math.min(w4, initX + 16);
                int y;
                int x = 0;
                for (y = initY, t.by += initY; y < subH4; y += txH, t.by += txH) {
                    var dst = 4 * (t.by * yStride + t.bx + initX);
                    for (x = initX, t.bx += initX; x < subW4; x += txW, t.bx += txW) {
                        if (predict && b.palSz[0] == 0) {
                            angle[0] = b.yAngle;
                            var edgeFlags = (((y > initY || sbHasTr == 0) && (x + txW >= subW4))
                                    ? 0 : IntraEdge.I444_TOP_HAS_RIGHT)
                                    | ((x > initX || (sbHasBl == 0 && y + txH >= subH4))
                                    ? 0 : IntraEdge.I444_LEFT_HAS_BOTTOM);
                            var top = topSbEdge(f, t.by, t.by);
                            var m = IpredDsp.prepareIntraEdges(t.bx, t.bx > ts.colStart, t.by, t.by > ts.rowStart,
                                    ts.colEnd, ts.rowEnd, edgeFlags, yPlane, dst, yStride,
                                    top >= 0 ? f.ipredEdge[0] : null, top, b.yMode, angle, txW, txH,
                                    intraEdgeFilter, edge, edgeOff, bdMax);
                            ipred.intraPred(m, yPlane, dst, yStride, edge, edgeOff, txW * 4, txH * 4,
                                    angle[0] | intraFlags, 4 * f.bw - 4 * t.bx, 4 * f.bh - 4 * t.by,
                                    t.ipredScratch, bdMax);
                        }
                        if (!b.skip) {
                            int eob;
                            if (pass == TaskContext.PASS_RECON) {
                                eob = BlockRecords.fetchCoefs(t, t.cf);
                            } else {
                                eob = Coefs.decode(t, t.a.lcoef, bx4 + x, t.l.lcoef, by4 + y, tx, bs, b, true, 0, t.cf);
                                setCtx(t.a.lcoef, bx4 + x, Math.min(txW, f.bw - t.bx), t.cfCtx);
                                setCtx(t.l.lcoef, by4 + y, Math.min(txH, f.bh - t.by), t.cfCtx);
                            }
                            if (eob >= 0 && predict) {
                                itx.itxfmAdd(tx, t.txtp, yPlane, dst, yStride, t.cf, 0, eob, t.itxScratch, bdMax);
                            }
                        } else {
                            setCtx(t.a.lcoef, bx4 + x, 1 << Tables.TX_LW[tx], 0x40);
                            setCtx(t.l.lcoef, by4 + y, 1 << Tables.TX_LH[tx], 0x40);
                        }
                        dst += 4 * txW;
                    }
                    t.bx -= x;
                }
                t.by -= y;
                if (!hasChroma) {
                    continue;
                }
                reconIntraChroma(t, bs, intraEdgeFlags, b, initX, initY, subCh4, cw4, ch4, cbw4, cbh4, cbx4, cby4,
                        intraEdgeFilterFlag);
            }
        }
    }

    /**
     * Reconstructs the chroma planes of one 64x64 luma area of an intra block.
     *
     * @param t the task context
     * @param bs the block size
     * @param intraEdgeFlags the block's intra edge flags
     * @param b the block
     * @param initX the area's luma column offset in 4-sample units
     * @param initY the area's luma row offset in 4-sample units
     * @param subCh4 the chroma row after the area
     * @param cw4 the visible chroma width
     * @param ch4 the visible chroma height
     * @param cbw4 the chroma block width
     * @param cbh4 the chroma block height
     * @param cbx4 the chroma column within the above context
     * @param cby4 the chroma row within the left context
     * @param intraEdgeFilterFlag the edge filter flag
     */
    private static void reconIntraChroma(TaskContext t, int bs, int intraEdgeFlags, Av1Block b, int initX, int initY,
                                         int subCh4, int cw4, int ch4, int cbw4, int cbh4, int cbx4, int cby4,
                                         int intraEdgeFilterFlag) {
        var ts = t.ts;
        var f = t.f;
        var ipred = IpredDsp.INSTANCE;
        var itx = t.f.itx;
        var bdMax = f.bitdepthMax;
        var cur = f.cur;
        var layout = cur.layout;
        var ssVer = layout == SequenceHeader.LAYOUT_I420 ? 1 : 0;
        var ssHor = layout != SequenceHeader.LAYOUT_I444 ? 1 : 0;
        var stride = cur.stride[1];
        var uvtx = b.uvtx;
        var uvW = Tables.TX_W[uvtx];
        var uvH = Tables.TX_H[uvtx];
        var edge = t.edge;
        var edgeOff = IpredDsp.EDGE_TOPLEFT;
        var angle = t.angle;
        var pass = t.pass;
        var predict = pass != TaskContext.PASS_PARSE;
        if (predict && b.uvMode == IpredDsp.CFL_PRED) {
            var ac = t.ac;
            var tW = Tables.TX_W[b.tx];
            var tH = Tables.TX_H[b.tx];
            var ySrc = 4 * (t.bx & ~ssHor) + 4 * (t.by & ~ssVer) * cur.stride[0];
            var uvOff = 4 * ((t.bx >> ssHor) + (t.by >> ssVer) * stride);
            var furthestR = ((cw4 << ssHor) + tW - 1) & ~(tW - 1);
            var furthestB = ((ch4 << ssVer) + tH - 1) & ~(tH - 1);
            ipred.cflAc(layout, ac, cur.data[0], ySrc, cur.stride[0], cbw4 - (furthestR >> ssHor),
                    cbh4 - (furthestB >> ssVer), cbw4 * 4, cbh4 * 4);
            for (var pl = 0; pl < 2; pl++) {
                if (b.cflAlpha[pl] == 0) {
                    continue;
                }
                angle[0] = 0;
                var top = topSbEdge(f, t.by, t.by & ~ssVer);
                var xpos = t.bx >> ssHor;
                var ypos = t.by >> ssVer;
                var xstart = ts.colStart >> ssHor;
                var ystart = ts.rowStart >> ssVer;
                var m = IpredDsp.prepareIntraEdges(xpos, xpos > xstart, ypos, ypos > ystart, ts.colEnd >> ssHor,
                        ts.rowEnd >> ssVer, 0, cur.data[1 + pl], uvOff, stride,
                        top >= 0 ? f.ipredEdge[pl + 1] : null, top, IpredDsp.DC_PRED, angle, uvW, uvH, false,
                        edge, edgeOff, bdMax);
                ipred.cflPred(m, cur.data[1 + pl], uvOff, stride, edge, edgeOff, uvW * 4, uvH * 4, ac,
                        b.cflAlpha[pl], bdMax);
            }
        } else if (predict && b.palSz[1] != 0) {
            var uvDstOff = 4 * ((t.bx >> ssHor) + (t.by >> ssVer) * stride);
            ipred.palPred(cur.data[1], uvDstOff, stride, t.pal, 8, t.palIdxUv, 0, cbw4 * 4, cbh4 * 4);
            ipred.palPred(cur.data[2], uvDstOff, stride, t.pal, 16, t.palIdxUv, 0, cbw4 * 4, cbh4 * 4);
        }
        var cbx4b = (t.bx & 31) >> ssHor;
        var cby4b = (t.by & 31) >> ssVer;
        var smUvFl = t.a.smUvFlag(cbx4b) | t.l.smUvFlag(cby4b);
        var uvSbHasTr = ((initX + 16) >> ssHor) < cw4 ? 1 : initY != 0 ? 0
                : intraEdgeFlags & (IntraEdge.I420_TOP_HAS_RIGHT >> (layout - 1));
        var uvSbHasBl = initX != 0 ? 0 : ((initY + 16) >> ssVer) < ch4 ? 1
                : intraEdgeFlags & (IntraEdge.I420_LEFT_HAS_BOTTOM >> (layout - 1));
        var subCw4 = Math.min(cw4, (initX + 16) >> ssHor);
        for (var pl = 0; pl < 2; pl++) {
            var plane = cur.data[1 + pl];
            int y;
            int x = 0;
            for (y = initY >> ssVer, t.by += initY; y < subCh4; y += uvH, t.by += uvH << ssVer) {
                var dst = 4 * ((t.by >> ssVer) * stride + ((t.bx + initX) >> ssHor));
                for (x = initX >> ssHor, t.bx += initX; x < subCw4; x += uvW, t.bx += uvW << ssHor) {
                    if (predict && !((b.uvMode == IpredDsp.CFL_PRED && b.cflAlpha[pl] != 0) || b.palSz[1] != 0)) {
                        angle[0] = b.uvAngle;
                        var edgeFlags = (((y > (initY >> ssVer) || uvSbHasTr == 0) && (x + uvW >= subCw4))
                                ? 0 : IntraEdge.I444_TOP_HAS_RIGHT)
                                | ((x > (initX >> ssHor) || (uvSbHasBl == 0 && y + uvH >= subCh4))
                                ? 0 : IntraEdge.I444_LEFT_HAS_BOTTOM);
                        var top = topSbEdge(f, t.by, t.by & ~ssVer);
                        var uvMode = b.uvMode == IpredDsp.CFL_PRED ? IpredDsp.DC_PRED : b.uvMode;
                        var xpos = t.bx >> ssHor;
                        var ypos = t.by >> ssVer;
                        var xstart = ts.colStart >> ssHor;
                        var ystart = ts.rowStart >> ssVer;
                        var m = IpredDsp.prepareIntraEdges(xpos, xpos > xstart, ypos, ypos > ystart,
                                ts.colEnd >> ssHor, ts.rowEnd >> ssVer, edgeFlags, plane, dst, stride,
                                top >= 0 ? f.ipredEdge[1 + pl] : null, top, uvMode, angle, uvW, uvH,
                                f.seqHdr.intraEdgeFilter, edge, edgeOff, bdMax);
                        angle[0] |= intraEdgeFilterFlag;
                        ipred.intraPred(m, plane, dst, stride, edge, edgeOff, uvW * 4, uvH * 4, angle[0] | smUvFl,
                                (4 * f.bw + ssHor - 4 * (t.bx & ~ssHor)) >> ssHor,
                                (4 * f.bh + ssVer - 4 * (t.by & ~ssVer)) >> ssVer, t.ipredScratch, bdMax);
                    }
                    if (!b.skip) {
                        int eob;
                        if (pass == TaskContext.PASS_RECON) {
                            eob = BlockRecords.fetchCoefs(t, t.cf);
                        } else {
                            var cctx = pl * 32;
                            eob = Coefs.decode(t, t.a.ccoef, cctx + cbx4 + x, t.l.ccoef, cctx + cby4 + y, uvtx, bs, b,
                                    true, 1 + pl, t.cf);
                            var ctw = Math.min(uvW, (f.bw - t.bx + ssHor) >> ssHor);
                            var cth = Math.min(uvH, (f.bh - t.by + ssVer) >> ssVer);
                            setCtx(t.a.ccoef, cctx + cbx4 + x, ctw, t.cfCtx);
                            setCtx(t.l.ccoef, cctx + cby4 + y, cth, t.cfCtx);
                        }
                        if (eob >= 0 && predict) {
                            itx.itxfmAdd(uvtx, t.txtp, plane, dst, stride, t.cf, 0, eob, t.itxScratch, bdMax);
                        }
                    } else {
                        setCtx(t.a.ccoef, pl * 32 + cbx4 + x, 1 << Tables.TX_LW[uvtx], 0x40);
                        setCtx(t.l.ccoef, pl * 32 + cby4 + y, 1 << Tables.TX_LH[uvtx], 0x40);
                    }
                    dst += uvW * 4;
                }
                t.bx -= x << ssHor;
            }
            t.by -= y << ssVer;
        }
    }

    /**
     * Decodes and adds the residual of one transform block of an inter block, splitting it as its
     * transform tree requires ({@code read_coef_tree}).
     *
     * @param t the task context
     * @param bs the block size
     * @param b the block
     * @param ytx the transform size at this depth
     * @param depth the split depth
     * @param txSplit0 the split flags of depth 0
     * @param txSplit1 the split flags of depth 1
     * @param xOff the horizontal position in transform units
     * @param yOff the vertical position in transform units
     * @param dst the offset of the block's top-left sample in the luma plane
     */
    private static void readCoefTree(TaskContext t, int bs, Av1Block b, int ytx, int depth, int txSplit0, int txSplit1,
                                     int xOff, int yOff, int dst) {
        var f = t.f;
        var txw = Tables.TX_W[ytx];
        var txh = Tables.TX_H[ytx];
        var split = depth == 0 ? txSplit0 : depth == 1 ? txSplit1 : 0;
        if (depth < 2 && split != 0 && (split & (1 << (yOff * 4 + xOff))) != 0) {
            var sub = Tables.TX_SUB[ytx];
            var txsw = Tables.TX_W[sub];
            var txsh = Tables.TX_H[sub];
            var stride = f.cur.stride[0];
            readCoefTree(t, bs, b, sub, depth + 1, txSplit0, txSplit1, xOff * 2, yOff * 2, dst);
            t.bx += txsw;
            if (txw >= txh && t.bx < f.bw) {
                readCoefTree(t, bs, b, sub, depth + 1, txSplit0, txSplit1, xOff * 2 + 1, yOff * 2, dst + 4 * txsw);
            }
            t.bx -= txsw;
            t.by += txsh;
            if (txh >= txw && t.by < f.bh) {
                dst += 4 * txsh * stride;
                readCoefTree(t, bs, b, sub, depth + 1, txSplit0, txSplit1, xOff * 2, yOff * 2 + 1, dst);
                t.bx += txsw;
                if (txw >= txh && t.bx < f.bw) {
                    readCoefTree(t, bs, b, sub, depth + 1, txSplit0, txSplit1, xOff * 2 + 1, yOff * 2 + 1,
                            dst + 4 * txsw);
                }
                t.bx -= txsw;
            }
            t.by -= txsh;
        } else {
            if (t.pass == TaskContext.PASS_RECON) {
                var eob = BlockRecords.fetchCoefs(t, t.cf);
                if (eob >= 0) {
                    t.f.itx.itxfmAdd(ytx, t.txtp, f.cur.data[0], dst, f.cur.stride[0], t.cf, 0, eob,
                            t.itxScratch, f.bitdepthMax);
                }
                return;
            }
            var bx4 = t.bx & 31;
            var by4 = t.by & 31;
            var eob = Coefs.decode(t, t.a.lcoef, bx4, t.l.lcoef, by4, ytx, bs, b, false, 0, t.cf);
            setCtx(t.a.lcoef, bx4, Math.min(txw, f.bw - t.bx), t.cfCtx);
            setCtx(t.l.lcoef, by4, Math.min(txh, f.bh - t.by), t.cfCtx);
            var map = t.txtpMap;
            var n = 1 << Tables.TX_LW[ytx];
            for (int y = 0, p = by4 * 32 + bx4; y < txh; y++, p += 32) {
                fill(map, p, n, t.txtp);
            }
            if (eob >= 0 && t.pass != TaskContext.PASS_PARSE) {
                t.f.itx.itxfmAdd(ytx, t.txtp, f.cur.data[0], dst, f.cur.stride[0], t.cf, 0, eob,
                        t.itxScratch, f.bitdepthMax);
            }
        }
    }

    /**
     * Predicts one block from one reference with translational motion ({@code mc}).
     *
     * @param t the task context
     * @param dst8 the destination plane for a direct prediction, or {@code null}
     * @param dst16 the intermediate buffer for a compound prediction, or {@code null}
     * @param dstOff the destination offset
     * @param dstStride the destination stride
     * @param bw4 the block width in 4-sample luma units
     * @param bh4 the block height in 4-sample luma units
     * @param bx the block column in 4-sample luma units
     * @param by the block row in 4-sample luma units
     * @param pl the plane
     * @param mv the packed motion vector
     * @param refp the reference picture
     * @param refidx the reference index, for scaling
     * @param filter2d the interpolation filter
     */
    static void mc(TaskContext t, short[] dst8, short[] dst16, int dstOff, int dstStride, int bw4, int bh4, int bx,
                   int by, int pl, int mv, Av1Picture refp, int refidx, int filter2d) {
        var f = t.f;
        var dsp = t.f.mc;
        var ssVer = pl != 0 && f.cur.layout == SequenceHeader.LAYOUT_I420 ? 1 : 0;
        var ssHor = pl != 0 && f.cur.layout != SequenceHeader.LAYOUT_I444 ? 1 : 0;
        var hMul = 4 >> ssHor;
        var vMul = 4 >> ssVer;
        var mvx = RefMvs.mvX(mv);
        var mvy = RefMvs.mvY(mv);
        var mx = mvx & (15 >> (ssHor ^ 1));
        var my = mvy & (15 >> (ssVer ^ 1));
        var refStride = refp.stride[pl != 0 ? 1 : 0];
        var refData = refp.data[pl];
        var bdMax = f.bitdepthMax;
        short[] ref;
        int refOff;
        if (refp.w == f.cur.w && refp.h == f.cur.h) {
            var dx = bx * hMul + (mvx >> (3 + ssHor));
            var dy = by * vMul + (mvy >> (3 + ssVer));
            int w;
            int h;
            if (refp != f.cur) {
                w = (f.cur.w + ssHor) >> ssHor;
                h = (f.cur.h + ssVer) >> ssVer;
            } else {
                w = f.bw * 4 >> ssHor;
                h = f.bh * 4 >> ssVer;
            }
            var hasMx = mx != 0 ? 1 : 0;
            var hasMy = my != 0 ? 1 : 0;
            if (dx < hasMx * 3 || dy < hasMy * 3 || dx + bw4 * hMul + hasMx * 4 > w || dy + bh4 * vMul + hasMy * 4 > h) {
                dsp.emuEdge(bw4 * hMul + hasMx * 7, bh4 * vMul + hasMy * 7, w, h, dx - hasMx * 3, dy - hasMy * 3,
                        t.emuEdge, 0, EMU_STRIDE, refData, 0, refStride);
                ref = t.emuEdge;
                refOff = EMU_STRIDE * hasMy * 3 + hasMx * 3;
                refStride = EMU_STRIDE;
            } else {
                ref = refData;
                refOff = refStride * dy + dx;
            }
            if (dst8 != null) {
                dsp.mc(filter2d, dst8, dstOff, dstStride, ref, refOff, refStride, bw4 * hMul, bh4 * vMul,
                        mx << (ssHor ^ 1), my << (ssVer ^ 1), t.mcScratch, bdMax);
            } else {
                dsp.mct(filter2d, dst16, dstOff, ref, refOff, refStride, bw4 * hMul, bh4 * vMul,
                        mx << (ssHor ^ 1), my << (ssVer ^ 1), t.mcScratch, bdMax);
            }
        } else {
            var origPosY = (by * vMul << 4) + mvy * (1 << (ssVer ^ 1));
            var origPosX = (bx * hMul << 4) + mvx * (1 << (ssHor ^ 1));
            var scaleX = f.svcScale[refidx * 2];
            var scaleY = f.svcScale[refidx * 2 + 1];
            var stepX = f.svcStep[refidx * 2];
            var stepY = f.svcStep[refidx * 2 + 1];
            var posX = scaleMv(origPosX, scaleX);
            var posY = scaleMv(origPosY, scaleY);
            var left = posX >> 10;
            var top = posY >> 10;
            var right = ((posX + (bw4 * hMul - 1) * stepX) >> 10) + 1;
            var bottom = ((posY + (bh4 * vMul - 1) * stepY) >> 10) + 1;
            var w = (refp.w + ssHor) >> ssHor;
            var h = (refp.h + ssVer) >> ssVer;
            if (left < 3 || top < 3 || right + 4 > w || bottom + 4 > h) {
                dsp.emuEdge(right - left + 7, bottom - top + 7, w, h, left - 3, top - 3, t.emuEdge, 0,
                        EMU_STRIDE_SCALED, refData, 0, refStride);
                ref = t.emuEdge;
                refOff = EMU_STRIDE_SCALED * 3 + 3;
                refStride = EMU_STRIDE_SCALED;
            } else {
                ref = refData;
                refOff = refStride * top + left;
            }
            if (dst8 != null) {
                dsp.mcScaled(filter2d, dst8, dstOff, dstStride, ref, refOff, refStride, bw4 * hMul, bh4 * vMul,
                        posX & 0x3ff, posY & 0x3ff, stepX, stepY, t.mcScratch, bdMax);
            } else {
                dsp.mctScaled(filter2d, dst16, dstOff, ref, refOff, refStride, bw4 * hMul, bh4 * vMul,
                        posX & 0x3ff, posY & 0x3ff, stepX, stepY, t.mcScratch, bdMax);
            }
        }
    }

    /**
     * Scales a position into a reference of another size.
     *
     * @param val the position in 1/16 samples
     * @param scale the scale in 1/16384 units
     * @return the scaled position in 1/1024 samples
     */
    private static int scaleMv(int val, int scale) {
        var tmp = (long) val * scale + (scale - 0x4000) * 8L;
        var r = (int) ((Math.abs(tmp) + 128) >> 8);
        return (tmp < 0 ? -r : r) + 32;
    }

    /**
     * Blends the predictions of the above and left neighbors' motion into the block edges
     * ({@code obmc}).
     *
     * @param t the task context
     * @param dst the destination plane
     * @param dstOff the block's offset
     * @param dstStride the stride
     * @param bs the block size
     * @param pl the plane
     * @param bx4 the column within the above context
     * @param by4 the row within the left context
     * @param w4 the visible block width
     * @param h4 the visible block height
     */
    private static void obmc(TaskContext t, short[] dst, int dstOff, int dstStride, int bs, int pl, int bx4, int by4,
                             int w4, int h4) {
        var f = t.f;
        var dsp = t.f.mc;
        var r = (t.by & 31) + 5;
        var lap = t.lap;
        var ssVer = pl != 0 && f.cur.layout == SequenceHeader.LAYOUT_I420 ? 1 : 0;
        var ssHor = pl != 0 && f.cur.layout != SequenceHeader.LAYOUT_I444 ? 1 : 0;
        var hMul = 4 >> ssHor;
        var vMul = 4 >> ssVer;
        var bw4 = BLOCK_DIMENSIONS[bs * 4];
        var bh4 = BLOCK_DIMENSIONS[bs * 4 + 1];
        var blocks = t.rt.blocks;
        if (t.by > t.ts.rowStart && (pl == 0 || bw4 * hMul + bh4 * vMul >= 16)) {
            for (int i = 0, x = 0; x < w4 && i < Math.min(BLOCK_DIMENSIONS[bs * 4 + 2], 4); ) {
                var rec = (t.rt.r[r - 1] + t.bx + x + 1) * RefMvs.BLOCK_INTS;
                var info = blocks[rec + 2];
                var aBs = RefMvs.bs(info);
                var step4 = Math.clamp(BLOCK_DIMENSIONS[aBs * 4], 2, 16);
                var ref0 = RefMvs.ref0(info);
                if (ref0 > 0) {
                    var ow4 = Math.min(step4, bw4);
                    var oh4 = Math.min(bh4, 16) >> 1;
                    var filter = Tables.FILTER_2D[t.a.filter[32 + bx4 + x + 1] * 4 + t.a.filter[bx4 + x + 1]];
                    mc(t, lap, null, 0, ow4 * hMul, ow4, (oh4 * 3 + 3) >> 2, t.bx + x, t.by, pl, blocks[rec],
                            f.refp[ref0 - 1], ref0 - 1, filter);
                    dsp.blendH(dst, dstOff + x * hMul, dstStride, lap, 0, hMul * ow4, vMul * oh4);
                    i++;
                }
                x += step4;
            }
        }
        if (t.bx > t.ts.colStart) {
            for (int i = 0, y = 0; y < h4 && i < Math.min(BLOCK_DIMENSIONS[bs * 4 + 3], 4); ) {
                var rec = (t.rt.r[r + y + 1] + t.bx - 1) * RefMvs.BLOCK_INTS;
                var info = blocks[rec + 2];
                var lBs = RefMvs.bs(info);
                var step4 = Math.clamp(BLOCK_DIMENSIONS[lBs * 4 + 1], 2, 16);
                var ref0 = RefMvs.ref0(info);
                if (ref0 > 0) {
                    var ow4 = Math.min(bw4, 16) >> 1;
                    var oh4 = Math.min(step4, bh4);
                    var filter = Tables.FILTER_2D[t.l.filter[32 + by4 + y + 1] * 4 + t.l.filter[by4 + y + 1]];
                    mc(t, lap, null, 0, hMul * ow4, ow4, oh4, t.bx, t.by + y, pl, blocks[rec],
                            f.refp[ref0 - 1], ref0 - 1, filter);
                    dsp.blendV(dst, dstOff + y * vMul * dstStride, dstStride, lap, 0, hMul * ow4, vMul * oh4);
                    i++;
                }
                y += step4;
            }
        }
    }

    /**
     * Predicts a block with an affine warp of a reference, 8x8 samples at a time
     * ({@code warp_affine}).
     *
     * @param t the task context
     * @param dst8 the destination plane for a direct prediction, or {@code null}
     * @param dst16 the intermediate buffer for a compound prediction, or {@code null}
     * @param dstOff the destination offset
     * @param dstride the destination stride
     * @param bs the block size
     * @param pl the plane
     * @param refp the reference picture
     * @param wmp the warp model
     */
    static void warpAffine(TaskContext t, short[] dst8, short[] dst16, int dstOff, int dstride, int bs, int pl,
                           Av1Picture refp, WarpedMotionParams wmp) {
        var f = t.f;
        var dsp = t.f.mc;
        var ssVer = pl != 0 && f.cur.layout == SequenceHeader.LAYOUT_I420 ? 1 : 0;
        var ssHor = pl != 0 && f.cur.layout != SequenceHeader.LAYOUT_I444 ? 1 : 0;
        var hMul = 4 >> ssHor;
        var vMul = 4 >> ssVer;
        var mat = wmp.matrix;
        var width = (refp.w + ssHor) >> ssHor;
        var height = (refp.h + ssVer) >> ssVer;
        var refData = refp.data[pl];
        var bw = BLOCK_DIMENSIONS[bs * 4] * hMul;
        var bh = BLOCK_DIMENSIONS[bs * 4 + 1] * vMul;
        var bdMax = f.bitdepthMax;
        for (var y = 0; y < bh; y += 8) {
            var srcY = t.by * 4 + ((y + 4) << ssVer);
            var mat3Y = (long) mat[3] * srcY + mat[0];
            var mat5Y = (long) mat[5] * srcY + mat[1];
            for (var x = 0; x < bw; x += 8) {
                var srcX = t.bx * 4 + ((x + 4) << ssHor);
                var mvx = ((long) mat[2] * srcX + mat3Y) >> ssHor;
                var mvy = ((long) mat[4] * srcX + mat5Y) >> ssVer;
                var dx = (int) (mvx >> 16) - 4;
                var mx = (((int) mvx & 0xffff) - wmp.abcd[0] * 4 - wmp.abcd[1] * 7) & ~0x3f;
                var dy = (int) (mvy >> 16) - 4;
                var my = (((int) mvy & 0xffff) - wmp.abcd[2] * 4 - wmp.abcd[3] * 4) & ~0x3f;
                short[] ref;
                int refOff;
                int refStride;
                if (dx < 3 || dx + 8 + 4 > width || dy < 3 || dy + 8 + 4 > height) {
                    dsp.emuEdge(15, 15, width, height, dx - 3, dy - 3, t.emuEdge, 0, 32, refData, 0,
                            refp.stride[pl != 0 ? 1 : 0]);
                    ref = t.emuEdge;
                    refOff = 32 * 3 + 3;
                    refStride = 32;
                } else {
                    ref = refData;
                    refStride = refp.stride[pl != 0 ? 1 : 0];
                    refOff = refStride * dy + dx;
                }
                if (dst16 != null) {
                    dsp.warp8x8t(dst16, dstOff + x, dstride, ref, refOff, refStride, wmp.abcd, mx, my, t.mcScratch,
                            t.warpIndices, bdMax);
                } else {
                    dsp.warp8x8(dst8, dstOff + x, dstride, ref, refOff, refStride, wmp.abcd, mx, my, t.mcScratch,
                            t.warpIndices, bdMax);
                }
            }
            dstOff += 8 * dstride;
        }
    }

    /**
     * Reconstructs an inter or intra block copy block ({@code dav1d_recon_b_inter}).
     *
     * @param t the task context
     * @param bs the block size
     * @param b the block
     * @return {@code true}; prediction never fails
     */
    static boolean reconBInter(TaskContext t, int bs, Av1Block b) {
        var f = t.f;
        var hdr = f.frameHdr;
        var cur = f.cur;
        var layout = cur.layout;
        var ssVer = layout == SequenceHeader.LAYOUT_I420 ? 1 : 0;
        var ssHor = layout != SequenceHeader.LAYOUT_I444 ? 1 : 0;
        var bx4 = t.bx & 31;
        var by4 = t.by & 31;
        var bw4 = BLOCK_DIMENSIONS[bs * 4];
        var bh4 = BLOCK_DIMENSIONS[bs * 4 + 1];
        var w4 = Math.min(bw4, f.bw - t.bx);
        var h4 = Math.min(bh4, f.bh - t.by);
        var hasChroma = layout != SequenceHeader.LAYOUT_I400 && (bw4 > ssHor || (t.bx & 1) != 0)
                && (bh4 > ssVer || (t.by & 1) != 0);
        var chrLayoutIdx = layout == SequenceHeader.LAYOUT_I400 ? 0 : SequenceHeader.LAYOUT_I444 - layout;
        var cbh4 = (bh4 + ssVer) >> ssVer;
        var cbw4 = (bw4 + ssHor) >> ssHor;
        var yStride = cur.stride[0];
        var uvStride = cur.stride[1];
        var dst = 4 * (t.by * yStride + t.bx);
        var uvdstoff = 4 * ((t.bx >> ssHor) + (t.by >> ssVer) * uvStride);
        if (t.pass != TaskContext.PASS_PARSE) {
            if (hdr.isKeyOrIntra()) {
                mc(t, cur.data[0], null, dst, yStride, bw4, bh4, t.bx, t.by, 0, b.mv[0], f.srCur, 0,
                        McDsp.FILTER_2D_BILINEAR);
                if (hasChroma) {
                    for (var pl = 1; pl < 3; pl++) {
                        mc(t, cur.data[pl], null, uvdstoff, uvStride, bw4 << (bw4 == ssHor ? 1 : 0),
                                bh4 << (bh4 == ssVer ? 1 : 0), t.bx & ~ssHor, t.by & ~ssVer, pl, b.mv[0], f.srCur, 0,
                                McDsp.FILTER_2D_BILINEAR);
                    }
                }
            } else if (b.compType == BlockDecoder.COMP_INTER_NONE) {
                predictSingle(t, bs, b, dst, uvdstoff, hasChroma, chrLayoutIdx, w4, h4, cbw4, cbh4);
            } else {
                predictCompound(t, bs, b, dst, uvdstoff, hasChroma, chrLayoutIdx, cbw4, cbh4);
            }
        }
        if (b.skip) {
            if (t.pass == TaskContext.PASS_RECON) {
                return true;
            }
            setCtx(t.a.lcoef, bx4, bw4, 0x40);
            setCtx(t.l.lcoef, by4, bh4, 0x40);
            if (hasChroma) {
                var cbx4 = bx4 >> ssHor;
                var cby4 = by4 >> ssVer;
                setCtx(t.a.ccoef, cbx4, cbw4, 0x40);
                setCtx(t.a.ccoef, 32 + cbx4, cbw4, 0x40);
                setCtx(t.l.ccoef, cby4, cbh4, 0x40);
                setCtx(t.l.ccoef, 32 + cby4, cbh4, 0x40);
            }
            return true;
        }
        residualInter(t, bs, b, dst, uvdstoff, hasChroma, w4, h4);
        return true;
    }

    /**
     * Decodes and adds the residual of an inter block.
     *
     * @param t the task context
     * @param bs the block size
     * @param b the block
     * @param dst the luma offset of the block
     * @param uvdstoff the chroma offset of the block
     * @param hasChroma whether the block has chroma
     * @param w4 the visible block width
     * @param h4 the visible block height
     */
    private static void residualInter(TaskContext t, int bs, Av1Block b, int dst, int uvdstoff, boolean hasChroma,
                                      int w4, int h4) {
        var f = t.f;
        var cur = f.cur;
        var layout = cur.layout;
        var ssVer = layout == SequenceHeader.LAYOUT_I420 ? 1 : 0;
        var ssHor = layout != SequenceHeader.LAYOUT_I444 ? 1 : 0;
        var bx4 = t.bx & 31;
        var by4 = t.by & 31;
        var cbx4 = bx4 >> ssHor;
        var cby4 = by4 >> ssVer;
        var bw4 = BLOCK_DIMENSIONS[bs * 4];
        var bh4 = BLOCK_DIMENSIONS[bs * 4 + 1];
        var cw4 = (w4 + ssHor) >> ssHor;
        var ch4 = (h4 + ssVer) >> ssVer;
        var uvtx = b.uvtx;
        var uvW = Tables.TX_W[uvtx];
        var uvH = Tables.TX_H[uvtx];
        var ytx = b.maxYtx;
        var yW = Tables.TX_W[ytx];
        var yH = Tables.TX_H[ytx];
        var yStride = cur.stride[0];
        var uvStride = cur.stride[1];
        var itx = t.f.itx;
        var pass = t.pass;
        for (var initY = 0; initY < bh4; initY += 16) {
            for (var initX = 0; initX < bw4; initX += 16) {
                var yOff = initY != 0 ? 1 : 0;
                int y;
                dst += yStride * 4 * initY;
                for (y = initY, t.by += initY; y < Math.min(h4, initY + 16); y += yH, yOff++) {
                    int x;
                    var xOff = initX != 0 ? 1 : 0;
                    for (x = initX, t.bx += initX; x < Math.min(w4, initX + 16); x += yW, xOff++) {
                        readCoefTree(t, bs, b, ytx, 0, b.txSplit0, b.txSplit1, xOff, yOff, dst + x * 4);
                        t.bx += yW;
                    }
                    dst += yStride * 4 * yH;
                    t.bx -= x;
                    t.by += yH;
                }
                dst -= yStride * 4 * y;
                t.by -= y;
                if (!hasChroma) {
                    continue;
                }
                for (var pl = 0; pl < 2; pl++) {
                    var plane = cur.data[1 + pl];
                    var uvdst = uvdstoff + (uvStride * initY * 4 >> ssVer);
                    for (y = initY >> ssVer, t.by += initY; y < Math.min(ch4, (initY + 16) >> ssVer); y += uvH) {
                        int x;
                        for (x = initX >> ssHor, t.bx += initX; x < Math.min(cw4, (initX + 16) >> ssHor); x += uvW) {
                            int eob;
                            if (pass == TaskContext.PASS_RECON) {
                                eob = BlockRecords.fetchCoefs(t, t.cf);
                            } else {
                                t.txtp = t.txtpMap[(by4 + (y << ssVer)) * 32 + bx4 + (x << ssHor)];
                                var cctx = pl * 32;
                                eob = Coefs.decode(t, t.a.ccoef, cctx + cbx4 + x, t.l.ccoef, cctx + cby4 + y, uvtx, bs,
                                        b, false, 1 + pl, t.cf);
                                var ctw = Math.min(uvW, (f.bw - t.bx + ssHor) >> ssHor);
                                var cth = Math.min(uvH, (f.bh - t.by + ssVer) >> ssVer);
                                setCtx(t.a.ccoef, cctx + cbx4 + x, ctw, t.cfCtx);
                                setCtx(t.l.ccoef, cctx + cby4 + y, cth, t.cfCtx);
                            }
                            if (eob >= 0 && pass != TaskContext.PASS_PARSE) {
                                itx.itxfmAdd(uvtx, t.txtp, plane, uvdst + 4 * x, uvStride, t.cf, 0, eob, t.itxScratch,
                                        f.bitdepthMax);
                            }
                            t.bx += uvW << ssHor;
                        }
                        uvdst += uvStride * 4 * uvH;
                        t.bx -= x << ssHor;
                        t.by += uvH << ssVer;
                    }
                    t.by -= y << ssVer;
                }
            }
        }
    }

    /**
     * Predicts a single-reference inter block, with its warp, overlapped and inter-intra variants.
     *
     * @param t the task context
     * @param bs the block size
     * @param b the block
     * @param dst the luma offset of the block
     * @param uvdstoff the chroma offset of the block
     * @param hasChroma whether the block has chroma
     * @param chrLayoutIdx the chroma subsampling index of the masks
     * @param w4 the visible block width
     * @param h4 the visible block height
     * @param cbw4 the chroma block width
     * @param cbh4 the chroma block height
     */
    private static void predictSingle(TaskContext t, int bs, Av1Block b, int dst, int uvdstoff, boolean hasChroma,
                                      int chrLayoutIdx, int w4, int h4, int cbw4, int cbh4) {
        var f = t.f;
        var hdr = f.frameHdr;
        var cur = f.cur;
        var ts = t.ts;
        var layout = cur.layout;
        var ssVer = layout == SequenceHeader.LAYOUT_I420 ? 1 : 0;
        var ssHor = layout != SequenceHeader.LAYOUT_I444 ? 1 : 0;
        var bx4 = t.bx & 31;
        var by4 = t.by & 31;
        var bw4 = BLOCK_DIMENSIONS[bs * 4];
        var bh4 = BLOCK_DIMENSIONS[bs * 4 + 1];
        var yStride = cur.stride[0];
        var uvStride = cur.stride[1];
        var refp = f.refp[b.ref[0]];
        var filter2d = b.filter2d;
        var bdMax = f.bitdepthMax;
        var warp = (b.interMode == BlockDecoder.GLOBALMV && f.gmvWarpAllowed[b.ref[0]])
                || (b.motionMode == BlockDecoder.MM_WARP && t.warpmv.type > WarpedMotionParams.TRANSLATION);
        var wmp = b.motionMode == BlockDecoder.MM_WARP ? t.warpmv : hdr.gmv[b.ref[0]];
        if (Math.min(bw4, bh4) > 1 && warp) {
            warpAffine(t, cur.data[0], null, dst, yStride, bs, 0, refp, wmp);
        } else {
            mc(t, cur.data[0], null, dst, yStride, bw4, bh4, t.bx, t.by, 0, b.mv[0], refp, b.ref[0], filter2d);
            if (b.motionMode == BlockDecoder.MM_OBMC) {
                obmc(t, cur.data[0], dst, yStride, bs, 0, bx4, by4, w4, h4);
            }
        }
        if (b.interintraType != BlockDecoder.INTER_INTRA_NONE) {
            var m = b.interintraMode == 3 ? IpredDsp.SMOOTH_PRED : b.interintraMode;
            t.angle[0] = 0;
            var top = topSbEdge(f, t.by, t.by);
            m = IpredDsp.prepareIntraEdges(t.bx, t.bx > ts.colStart, t.by, t.by > ts.rowStart, ts.colEnd, ts.rowEnd,
                    0, cur.data[0], dst, yStride, top >= 0 ? f.ipredEdge[0] : null, top, m, t.angle, bw4, bh4, false,
                    t.edge, 32, bdMax);
            IpredDsp.INSTANCE.intraPred(m, t.interintra, 0, 4 * bw4, t.edge, 32, bw4 * 4, bh4 * 4, 0, 0, 0,
                    t.ipredScratch, bdMax);
            t.f.mc.blend(cur.data[0], dst, yStride, t.interintra, 0, bw4 * 4, bh4 * 4, Wedge.MASKS,
                    iiMask(0, bs, b));
        }
        if (hasChroma) {
            var isSub8x8 = bw4 == ssHor || bh4 == ssVer;
            var r = (t.by & 31) + 5;
            var blocks = t.rt.blocks;
            if (isSub8x8) {
                if (bw4 == 1) {
                    isSub8x8 &= RefMvs.ref0(blocks[(t.rt.r[r] + t.bx - 1) * RefMvs.BLOCK_INTS + 2]) > 0;
                }
                if (bh4 == ssVer) {
                    isSub8x8 &= RefMvs.ref0(blocks[(t.rt.r[r - 1] + t.bx) * RefMvs.BLOCK_INTS + 2]) > 0;
                }
                if (bw4 == 1 && bh4 == ssVer) {
                    isSub8x8 &= RefMvs.ref0(blocks[(t.rt.r[r - 1] + t.bx - 1) * RefMvs.BLOCK_INTS + 2]) > 0;
                }
            }
            if (isSub8x8) {
                var hOff = 0;
                var vOff = 0;
                if (bw4 == 1 && bh4 == ssVer) {
                    var rec = (t.rt.r[r - 1] + t.bx - 1) * RefMvs.BLOCK_INTS;
                    var ref0 = RefMvs.ref0(blocks[rec + 2]);
                    for (var pl = 0; pl < 2; pl++) {
                        mc(t, cur.data[1 + pl], null, uvdstoff, uvStride, bw4, bh4, t.bx - 1, t.by - 1, 1 + pl,
                                blocks[rec], f.refp[ref0 - 1], ref0 - 1, t.tl4x4Filter);
                    }
                    vOff = 2 * uvStride;
                    hOff = 2;
                }
                if (bw4 == 1) {
                    var leftFilter2d = Tables.FILTER_2D[t.l.filter[32 + by4] * 4 + t.l.filter[by4]];
                    var rec = (t.rt.r[r] + t.bx - 1) * RefMvs.BLOCK_INTS;
                    var ref0 = RefMvs.ref0(blocks[rec + 2]);
                    for (var pl = 0; pl < 2; pl++) {
                        mc(t, cur.data[1 + pl], null, uvdstoff + vOff, uvStride, bw4, bh4, t.bx - 1, t.by, 1 + pl,
                                blocks[rec], f.refp[ref0 - 1], ref0 - 1, leftFilter2d);
                    }
                    hOff = 2;
                }
                if (bh4 == ssVer) {
                    var topFilter2d = Tables.FILTER_2D[t.a.filter[32 + bx4] * 4 + t.a.filter[bx4]];
                    var rec = (t.rt.r[r - 1] + t.bx) * RefMvs.BLOCK_INTS;
                    var ref0 = RefMvs.ref0(blocks[rec + 2]);
                    for (var pl = 0; pl < 2; pl++) {
                        mc(t, cur.data[1 + pl], null, uvdstoff + hOff, uvStride, bw4, bh4, t.bx, t.by - 1, 1 + pl,
                                blocks[rec], f.refp[ref0 - 1], ref0 - 1, topFilter2d);
                    }
                    vOff = 2 * uvStride;
                }
                for (var pl = 0; pl < 2; pl++) {
                    mc(t, cur.data[1 + pl], null, uvdstoff + hOff + vOff, uvStride, bw4, bh4, t.bx, t.by, 1 + pl,
                            b.mv[0], refp, b.ref[0], filter2d);
                }
            } else {
                if (Math.min(cbw4, cbh4) > 1 && warp) {
                    for (var pl = 0; pl < 2; pl++) {
                        warpAffine(t, cur.data[1 + pl], null, uvdstoff, uvStride, bs, 1 + pl, refp, wmp);
                    }
                } else {
                    for (var pl = 0; pl < 2; pl++) {
                        mc(t, cur.data[1 + pl], null, uvdstoff, uvStride, bw4 << (bw4 == ssHor ? 1 : 0),
                                bh4 << (bh4 == ssVer ? 1 : 0), t.bx & ~ssHor, t.by & ~ssVer, 1 + pl, b.mv[0], refp,
                                b.ref[0], filter2d);
                        if (b.motionMode == BlockDecoder.MM_OBMC) {
                            obmc(t, cur.data[1 + pl], uvdstoff, uvStride, bs, 1 + pl, bx4, by4, w4, h4);
                        }
                    }
                }
                if (b.interintraType != BlockDecoder.INTER_INTRA_NONE) {
                    var iiMask = iiMask(chrLayoutIdx, bs, b);
                    for (var pl = 0; pl < 2; pl++) {
                        var m = b.interintraMode == 3 ? IpredDsp.SMOOTH_PRED : b.interintraMode;
                        t.angle[0] = 0;
                        var top = topSbEdge(f, t.by, t.by);
                        m = IpredDsp.prepareIntraEdges(t.bx >> ssHor, (t.bx >> ssHor) > (ts.colStart >> ssHor),
                                t.by >> ssVer, (t.by >> ssVer) > (ts.rowStart >> ssVer), ts.colEnd >> ssHor,
                                ts.rowEnd >> ssVer, 0, cur.data[1 + pl], uvdstoff, uvStride,
                                top >= 0 ? f.ipredEdge[pl + 1] : null, top, m, t.angle, cbw4, cbh4, false, t.edge, 32,
                                bdMax);
                        IpredDsp.INSTANCE.intraPred(m, t.interintra, 0, cbw4 * 4, t.edge, 32, cbw4 * 4, cbh4 * 4, 0, 0,
                                0, t.ipredScratch, bdMax);
                        t.f.mc.blend(cur.data[1 + pl], uvdstoff, uvStride, t.interintra, 0, cbw4 * 4,
                                cbh4 * 4, Wedge.MASKS, iiMask);
                    }
                }
            }
        }
        t.tl4x4Filter = filter2d;
    }

    /**
     * Returns the offset of the inter-intra mask of a block ({@code II_MASK}).
     *
     * @param c the chroma subsampling index
     * @param bs the block size
     * @param b the block
     * @return the offset in {@link Wedge#MASKS}
     */
    private static int iiMask(int c, int bs, Av1Block b) {
        return b.interintraType == BlockDecoder.INTER_INTRA_BLEND
                ? Wedge.iiOffset(c, bs, b.interintraMode)
                : Wedge.wedgeOffset(c, bs, 0, b.wedgeIdx);
    }

    /**
     * Predicts a compound inter block.
     *
     * @param t the task context
     * @param bs the block size
     * @param b the block
     * @param dst the luma offset of the block
     * @param uvdstoff the chroma offset of the block
     * @param hasChroma whether the block has chroma
     * @param chrLayoutIdx the chroma subsampling index of the masks
     * @param cbw4 the chroma block width
     * @param cbh4 the chroma block height
     */
    static void predictCompound(TaskContext t, int bs, Av1Block b, int dst, int uvdstoff, boolean hasChroma,
                                int chrLayoutIdx, int cbw4, int cbh4) {
        var f = t.f;
        var hdr = f.frameHdr;
        var cur = f.cur;
        var dsp = t.f.mc;
        var layout = cur.layout;
        var ssVer = layout == SequenceHeader.LAYOUT_I420 ? 1 : 0;
        var ssHor = layout != SequenceHeader.LAYOUT_I444 ? 1 : 0;
        var bw4 = BLOCK_DIMENSIONS[bs * 4];
        var bh4 = BLOCK_DIMENSIONS[bs * 4 + 1];
        var yStride = cur.stride[0];
        var uvStride = cur.stride[1];
        var filter2d = b.filter2d;
        var tmp = t.compinter;
        var bdMax = f.bitdepthMax;
        var jntWeight = 0;
        byte[] mask = null;
        var maskOff = 0;
        for (var i = 0; i < 2; i++) {
            var refp = f.refp[b.ref[i]];
            if (b.interMode == BlockDecoder.GLOBALMV_GLOBALMV && f.gmvWarpAllowed[b.ref[i]]) {
                warpAffine(t, null, tmp[i], 0, bw4 * 4, bs, 0, refp, hdr.gmv[b.ref[i]]);
            } else {
                mc(t, null, tmp[i], 0, 0, bw4, bh4, t.bx, t.by, 0, b.mv[i], refp, b.ref[i], filter2d);
            }
        }
        var ms = b.maskSign;
        switch (b.compType) {
            case BlockDecoder.COMP_INTER_AVG -> dsp.avg(cur.data[0], dst, yStride, tmp[0], 0, tmp[1], 0, bw4 * 4,
                    bh4 * 4, bdMax);
            case BlockDecoder.COMP_INTER_WEIGHTED_AVG -> {
                jntWeight = f.jntWeights[b.ref[0]][b.ref[1]];
                dsp.wAvg(cur.data[0], dst, yStride, tmp[0], 0, tmp[1], 0, bw4 * 4, bh4 * 4, jntWeight, bdMax);
            }
            case BlockDecoder.COMP_INTER_SEG -> {
                dsp.wMask(chrLayoutIdx, cur.data[0], dst, yStride, tmp[ms], 0, tmp[ms ^ 1], 0, bw4 * 4, bh4 * 4,
                        t.segMask, 0, ms, bdMax);
                mask = t.segMask;
                maskOff = 0;
            }
            default -> {
                mask = Wedge.MASKS;
                maskOff = Wedge.wedgeOffset(0, bs, 0, b.wedgeIdx);
                dsp.mask(cur.data[0], dst, yStride, tmp[ms], 0, tmp[ms ^ 1], 0, bw4 * 4, bh4 * 4, mask, maskOff,
                        bdMax);
                if (hasChroma) {
                    maskOff = Wedge.wedgeOffset(chrLayoutIdx, bs, ms, b.wedgeIdx);
                }
            }
        }
        if (!hasChroma) {
            return;
        }
        for (var pl = 0; pl < 2; pl++) {
            for (var i = 0; i < 2; i++) {
                var refp = f.refp[b.ref[i]];
                if (b.interMode == BlockDecoder.GLOBALMV_GLOBALMV && Math.min(cbw4, cbh4) > 1
                        && f.gmvWarpAllowed[b.ref[i]]) {
                    warpAffine(t, null, tmp[i], 0, bw4 * 4 >> ssHor, bs, 1 + pl, refp, hdr.gmv[b.ref[i]]);
                } else {
                    mc(t, null, tmp[i], 0, 0, bw4, bh4, t.bx, t.by, 1 + pl, b.mv[i], refp, b.ref[i], filter2d);
                }
            }
            var plane = cur.data[1 + pl];
            switch (b.compType) {
                case BlockDecoder.COMP_INTER_AVG -> dsp.avg(plane, uvdstoff, uvStride, tmp[0], 0, tmp[1], 0,
                        bw4 * 4 >> ssHor, bh4 * 4 >> ssVer, bdMax);
                case BlockDecoder.COMP_INTER_WEIGHTED_AVG -> dsp.wAvg(plane, uvdstoff, uvStride, tmp[0], 0, tmp[1], 0,
                        bw4 * 4 >> ssHor, bh4 * 4 >> ssVer, jntWeight, bdMax);
                default -> dsp.mask(plane, uvdstoff, uvStride, tmp[ms], 0, tmp[ms ^ 1], 0, bw4 * 4 >> ssHor,
                        bh4 * 4 >> ssVer, mask, maskOff, bdMax);
            }
        }
    }
}
