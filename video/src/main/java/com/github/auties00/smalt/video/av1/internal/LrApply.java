package com.github.auties00.smalt.video.av1.internal;

/**
 * Applies loop restoration to superblock rows (AV1 specification section 7.17), stripe by stripe
 * and unit by unit.
 *
 * @implNote This implementation ports dav1d's {@code src/lr_apply_tmpl.c} ({@code dav1d_lr_sbrow},
 *           {@code lr_sbrow}, {@code lr_stripe}, {@code backup4xU}).
 */
final class LrApply {
    /**
     * Prevents instantiation.
     */
    private LrApply() {
        throw new AssertionError();
    }

    /**
     * Restores the stripes of one restoration unit of a superblock row ({@code lr_stripe}).
     *
     * @param tc      the task context providing scratch
     * @param f       the frame
     * @param p       the plane
     * @param pOff    the offset of the unit's first sample in the superblock row
     * @param leftOff the offset of the unit's left columns in the border buffer
     * @param x       the unit's first column
     * @param y       the first row of the superblock row's stripes
     * @param plane   the plane index
     * @param unitW   the unit width
     * @param rowH    the row after the last one to restore
     * @param lr      the unit's parameters
     * @param edges   the edge flags
     */
    private static void stripe(TaskContext tc, FrameContext f, short[] p, int pOff, int leftOff, int x, int y,
                               int plane, int unitW, int rowH, RestorationUnit lr, int edges) {
        var dsp = f.lr;
        var chroma = plane != 0 ? 1 : 0;
        var ssVer = chroma != 0 && f.srCur.layout == SequenceHeader.LAYOUT_I420 ? 1 : 0;
        var stride = f.srCur.stride[chroma];
        var sb128 = f.seqHdr.sb128 ? 1 : 0;
        var sby = (y + (y != 0 ? 8 << ssVer : 0)) >> (6 - ssVer + sb128);
        var lpf = f.lrLpfLine[plane];
        var lpfOff = (f.haveTt ? (sby * (4 << sb128) - 4) * stride : 0) + x;
        var stripeH = Math.min((64 - 8 * (y == 0 ? 1 : 0)) >> ssVer, rowH - y);
        var params = tc.lrParams;
        var wiener = lr.type == FrameHeader.RESTORATION_WIENER;
        int kind;
        if (wiener) {
            params.setWiener(lr.filterH[0], lr.filterH[1], lr.filterH[2], lr.filterV[0], lr.filterV[1], lr.filterV[2]);
            kind = (lr.filterH[0] | lr.filterV[0]) == 0 ? LoopRestorationDsp.WIENER_5TAP : LoopRestorationDsp.WIENER_7TAP;
        } else {
            var sgrIdx = lr.type - FrameHeader.RESTORATION_SGRPROJ;
            var s0 = Tables.SGR_PARAMS[sgrIdx * 2];
            var s1 = Tables.SGR_PARAMS[sgrIdx * 2 + 1];
            params.s0 = s0;
            params.s1 = s1;
            params.w0 = lr.sgrWeights[0];
            params.w1 = 128 - (lr.sgrWeights[0] + lr.sgrWeights[1]);
            kind = (s0 != 0 ? 1 : 0) + (s1 != 0 ? 2 : 0) - 1;
        }
        var left = tc.preLrBorder;
        while (y + stripeH <= rowH) {
            var bottom = sby + 1 != f.sbh || y + stripeH != rowH;
            edges = bottom ? edges | LoopRestorationDsp.HAVE_BOTTOM : edges & ~LoopRestorationDsp.HAVE_BOTTOM;
            if (wiener) {
                dsp.wiener(kind, p, pOff, stride, left, leftOff, lpf, lpfOff, unitW, stripeH, params, edges,
                        tc.lrScratch, f.bitdepthMax);
            } else {
                dsp.sgr(kind, p, pOff, stride, left, leftOff, lpf, lpfOff, unitW, stripeH, params, edges, tc.lrScratch,
                        f.bitdepthMax);
            }
            leftOff += stripeH * 4;
            y += stripeH;
            pOff += stripeH * stride;
            edges |= LoopRestorationDsp.HAVE_TOP;
            stripeH = Math.min(64 >> ssVer, rowH - y);
            if (stripeH == 0) {
                break;
            }
            lpfOff += 4 * stride;
        }
    }

    /**
     * Restores one plane of a superblock row ({@code lr_sbrow}).
     *
     * @param tc    the task context
     * @param f     the frame
     * @param p     the plane
     * @param pOff  the offset of the first row to restore
     * @param y     the first row to restore
     * @param w     the plane width
     * @param h     the plane height
     * @param rowH  the row after the last one to restore
     * @param plane the plane index
     */
    private static void sbrowPlane(TaskContext tc, FrameContext f, short[] p, int pOff, int y, int w, int h, int rowH,
                                   int plane) {
        var hdr = f.frameHdr;
        var chroma = plane != 0 ? 1 : 0;
        var ssVer = chroma != 0 && f.srCur.layout == SequenceHeader.LAYOUT_I420 ? 1 : 0;
        var ssHor = chroma != 0 && f.srCur.layout != SequenceHeader.LAYOUT_I444 ? 1 : 0;
        var pStride = f.srCur.stride[chroma];
        var unitSizeLog2 = hdr.restorationUnitSize[chroma];
        var unitSize = 1 << unitSizeLog2;
        var halfUnitSize = unitSize >> 1;
        var maxUnitSize = unitSize + halfUnitSize;
        var rowY = y + ((8 >> ssVer) * (y != 0 ? 1 : 0));
        var shiftHor = 7 - ssHor;
        var border = tc.preLrBorder;
        var edges = (y > 0 ? LoopRestorationDsp.HAVE_TOP : 0) | LoopRestorationDsp.HAVE_RIGHT;
        var alignedUnitPos = rowY & ~(unitSize - 1);
        if (alignedUnitPos != 0 && alignedUnitPos + halfUnitSize > h) {
            alignedUnitPos -= unitSize;
        }
        alignedUnitPos <<= ssVer;
        var sbIdx = (alignedUnitPos >> 7) * f.srSb128w;
        var unitIdx = ((alignedUnitPos >> 6) & 1) << 1;
        var lr0 = f.lrMask[(sbIdx * 3 + plane) * 4 + unitIdx];
        var lr1 = lr0;
        var restore = lr0.type != FrameHeader.RESTORATION_NONE;
        var x = 0;
        var bit = 0;
        var backupH = rowH - y;
        for (; x + maxUnitSize <= w; pOff += unitSize, edges |= LoopRestorationDsp.HAVE_LEFT, bit ^= 1) {
            var nextX = x + unitSize;
            var nextUIdx = unitIdx + ((nextX >> (shiftHor - 1)) & 1);
            var next = f.lrMask[((sbIdx + (nextX >> shiftHor)) * 3 + plane) * 4 + nextUIdx];
            if (bit == 0) {
                lr1 = next;
            } else {
                lr0 = next;
            }
            var restoreNext = next.type != FrameHeader.RESTORATION_NONE;
            if (restoreNext) {
                var dst = bit * 136 * 4;
                var src = pOff + unitSize - 4;
                for (var u = 0; u < backupH; u++, dst += 4, src += pStride) {
                    System.arraycopy(p, src, border, dst, 4);
                }
            }
            if (restore) {
                stripe(tc, f, p, pOff, (bit ^ 1) * 136 * 4, x, y, plane, unitSize, rowH, bit == 0 ? lr0 : lr1, edges);
            }
            x = nextX;
            restore = restoreNext;
        }
        if (restore) {
            edges &= ~LoopRestorationDsp.HAVE_RIGHT;
            stripe(tc, f, p, pOff, (bit ^ 1) * 136 * 4, x, y, plane, w - x, rowH, bit == 0 ? lr0 : lr1, edges);
        }
    }

    /**
     * Restores the planes of a superblock row ({@code dav1d_lr_sbrow}).
     *
     * @param tc  the task context
     * @param f   the frame
     * @param sby the superblock row
     */
    static void sbrow(TaskContext tc, FrameContext f, int sby) {
        var sr = f.srCur;
        var offsetY = 8 * (sby != 0 ? 1 : 0);
        var restorePlanes = f.restorePlanes;
        var sb128 = f.seqHdr.sb128 ? 1 : 0;
        var notLast = sby + 1 < f.sbh ? 1 : 0;
        var y0 = sby * f.sbStep * 4;
        if ((restorePlanes & 1) != 0) {
            var h = sr.h;
            var w = sr.w;
            var nextRowY = (sby + 1) << (6 + sb128);
            var rowH = Math.min(nextRowY - 8 * notLast, h);
            var yStripe = (sby << (6 + sb128)) - offsetY;
            sbrowPlane(tc, f, sr.data[0], (y0 - offsetY) * sr.stride[0], yStripe, w, h, rowH, 0);
        }
        if ((restorePlanes & 6) != 0) {
            var ssVer = sr.layout == SequenceHeader.LAYOUT_I420 ? 1 : 0;
            var ssHor = sr.layout != SequenceHeader.LAYOUT_I444 ? 1 : 0;
            var h = (sr.h + ssVer) >> ssVer;
            var w = (sr.w + ssHor) >> ssHor;
            var nextRowY = (sby + 1) << ((6 - ssVer) + sb128);
            var rowH = Math.min(nextRowY - (8 >> ssVer) * notLast, h);
            var offsetUv = offsetY >> ssVer;
            var yStripe = (sby << ((6 - ssVer) + sb128)) - offsetUv;
            var off = ((y0 * sr.stride[1]) >> ssVer) - offsetUv * sr.stride[1];
            if ((restorePlanes & 2) != 0) {
                sbrowPlane(tc, f, sr.data[1], off, yStripe, w, h, rowH, 1);
            }
            if ((restorePlanes & 4) != 0) {
                sbrowPlane(tc, f, sr.data[2], off, yStripe, w, h, rowH, 2);
            }
        }
    }
}
