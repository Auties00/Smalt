package com.github.auties00.smalt.video.av1.internal;

/**
 * Applies the deblocking loop filter to superblock rows (AV1 specification section 7.14) and saves
 * the deblocked lines CDEF and loop restoration read across superblock rows.
 *
 * @implNote This implementation ports dav1d's {@code src/lf_apply_tmpl.c}
 *           ({@code dav1d_loopfilter_sbrow_cols}, {@code dav1d_loopfilter_sbrow_rows},
 *           {@code dav1d_copy_lpf} and {@code backup_lpf}).
 */
final class LfApply {
    /**
     * Prevents instantiation.
     */
    private LfApply() {
        throw new AssertionError();
    }

    /**
     * Computes the loop filter levels of every segment, direction, reference and mode
     * ({@code dav1d_calc_lf_values}).
     *
     * @param values  the table to fill
     * @param hdr     the frame header
     * @param lfDelta the four block-level loop filter deltas
     */
    static void calcLfValues(byte[] values, FrameHeader hdr, int[] lfDelta) {
        var segDeltaLf = new int[32];
        for (var s = 0; s < 8; s++) {
            segDeltaLf[4 * s] = hdr.segDeltaLfYV[s];
            segDeltaLf[4 * s + 1] = hdr.segDeltaLfYH[s];
            segDeltaLf[4 * s + 2] = hdr.segDeltaLfU[s];
            segDeltaLf[4 * s + 3] = hdr.segDeltaLfV[s];
        }
        LoopFilterMasks.calcLfValues(values, hdr.loopfilterLevelY[0], hdr.loopfilterLevelY[1], hdr.loopfilterLevelU,
                hdr.loopfilterLevelV, hdr.modeRefDeltaEnabled, hdr.modeDelta, hdr.refDelta, hdr.segmentationEnabled,
                segDeltaLf, hdr.deltaLfMulti, lfDelta);
    }

    /**
     * Filters the vertical edges (between columns) of a superblock row
     * ({@code dav1d_loopfilter_sbrow_cols}).
     *
     * @param f               the frame
     * @param sby             the superblock row
     * @param startOfTileRow  the tile row starting at this superblock row, or 0
     */
    static void sbrowCols(FrameContext f, int sby, int startOfTileRow) {
        sbrowCols(f, sby, startOfTileRow, f.lfScratch);
    }

    /**
     * Filters the vertical edges (between columns) of a superblock row with the given kernel
     * scratch ({@code dav1d_loopfilter_sbrow_cols}). The vertical edges of a superblock row touch
     * only its own samples, so different superblock rows may be filtered concurrently, each with
     * its own scratch.
     *
     * @param f              the frame
     * @param sby            the superblock row
     * @param startOfTileRow the tile row starting at this superblock row, or 0
     * @param scratch        the kernel scratch, at least {@link LoopFilterDsp#SCRATCH_SHORTS}
     */
    static void sbrowCols(FrameContext f, int sby, int startOfTileRow, short[] scratch) {
        var hdr = f.frameHdr;
        var cur = f.cur;
        var dsp = f.lf;
        var isSb64 = f.seqHdr.sb128 ? 0 : 1;
        var starty4 = (sby & isSb64) << 4;
        var sbsz = 32 >> isSb64;
        var sbl2 = 5 - isSb64;
        var halign = (f.bh + 31) & ~31;
        var ssVer = cur.layout == SequenceHeader.LAYOUT_I420 ? 1 : 0;
        var ssHor = cur.layout != SequenceHeader.LAYOUT_I444 ? 1 : 0;
        var vmask = 16 >> ssVer;
        var hmask = 16 >> ssHor;
        var vmax = 1 << vmask;
        var hmax = 1 << hmask;
        var endy4 = starty4 + Math.min(f.h4 - sby * sbsz, sbsz);
        var uvEndy4 = (endy4 + ssVer) >> ssVer;
        var maskBase = (sby >> (f.seqHdr.sb128 ? 0 : 1)) * f.sb128w;
        var lpfY = sby << sbl2;
        var lpfUv = sby << (sbl2 - ssVer);
        var rightY = f.txLpfRightEdge[0];
        var rightUv = f.txLpfRightEdge[1];
        for (var tileCol = 1; ; tileCol++) {
            var x = hdr.colStartSb[tileCol];
            if ((x << sbl2) >= f.bw) {
                break;
            }
            var bx4 = (x & isSb64) != 0 ? 16 : 0;
            var cbx4 = bx4 >> ssHor;
            x >>= isSb64;
            var lf = f.lfMask[maskBase + x];
            var fy = lf.filterY;
            for (int y = starty4, mask = 1 << y; y < endy4; y++, mask <<= 1) {
                var sidx = Integer.compareUnsigned(mask, 0x10000) >= 0 ? 1 : 0;
                var smask = mask >>> (sidx << 4);
                var i0 = LfMask.yIndex(0, bx4, 0, sidx);
                var i1 = LfMask.yIndex(0, bx4, 1, sidx);
                var i2 = LfMask.yIndex(0, bx4, 2, sidx);
                var idx = 2 * ((fy[i2] & smask) != 0 ? 1 : 0) + ((fy[i1] & smask) != 0 ? 1 : 0);
                fy[i2] &= (char) ~smask;
                fy[i1] &= (char) ~smask;
                fy[i0] &= (char) ~smask;
                var k = Math.min(idx, rightY[lpfY + y - starty4]);
                fy[LfMask.yIndex(0, bx4, k, sidx)] |= (char) smask;
            }
            if (cur.layout != SequenceHeader.LAYOUT_I400) {
                var fuv = lf.filterUv;
                for (int y = starty4 >> ssVer, uvMask = 1 << y; y < uvEndy4; y++, uvMask <<= 1) {
                    var sidx = Integer.compareUnsigned(uvMask, vmax) >= 0 ? 1 : 0;
                    var smask = uvMask >>> (sidx << (4 - ssVer));
                    var i0 = LfMask.uvIndex(0, cbx4, 0, sidx);
                    var i1 = LfMask.uvIndex(0, cbx4, 1, sidx);
                    var idx = (fuv[i1] & smask) != 0 ? 1 : 0;
                    fuv[i1] &= (char) ~smask;
                    fuv[i0] &= (char) ~smask;
                    var k = Math.min(idx, rightUv[lpfUv + y - (starty4 >> ssVer)]);
                    fuv[LfMask.uvIndex(0, cbx4, k, sidx)] |= (char) smask;
                }
            }
            lpfY += halign;
            lpfUv += halign >> ssVer;
        }
        if (startOfTileRow != 0) {
            for (var x = 0; x < f.sb128w; x++) {
                var a = (f.twoPass ? f.reconA : f.a)[f.sb128w * (startOfTileRow - 1) + x];
                var lf = f.lfMask[maskBase + x];
                var fy = lf.filterY;
                var w = Math.min(32, f.w4 - (x << 5));
                for (int mask = 1, i = 0; i < w; mask <<= 1, i++) {
                    var sidx = Integer.compareUnsigned(mask, 0x10000) >= 0 ? 1 : 0;
                    var smask = mask >>> (sidx << 4);
                    var i0 = LfMask.yIndex(1, starty4, 0, sidx);
                    var i1 = LfMask.yIndex(1, starty4, 1, sidx);
                    var i2 = LfMask.yIndex(1, starty4, 2, sidx);
                    var idx = 2 * ((fy[i2] & smask) != 0 ? 1 : 0) + ((fy[i1] & smask) != 0 ? 1 : 0);
                    fy[i2] &= (char) ~smask;
                    fy[i1] &= (char) ~smask;
                    fy[i0] &= (char) ~smask;
                    var k = Math.min(idx, a.txLpfY[i]);
                    fy[LfMask.yIndex(1, starty4, k, sidx)] |= (char) smask;
                }
                if (cur.layout != SequenceHeader.LAYOUT_I400) {
                    var cw = (w + ssHor) >> ssHor;
                    var fuv = lf.filterUv;
                    for (int uvMask = 1, i = 0; i < cw; uvMask <<= 1, i++) {
                        var sidx = Integer.compareUnsigned(uvMask, hmax) >= 0 ? 1 : 0;
                        var smask = uvMask >>> (sidx << (4 - ssHor));
                        var i0 = LfMask.uvIndex(1, starty4 >> ssVer, 0, sidx);
                        var i1 = LfMask.uvIndex(1, starty4 >> ssVer, 1, sidx);
                        var idx = (fuv[i1] & smask) != 0 ? 1 : 0;
                        fuv[i1] &= (char) ~smask;
                        fuv[i0] &= (char) ~smask;
                        var k = Math.min(idx, a.txLpfUv[i]);
                        fuv[LfMask.uvIndex(1, starty4 >> ssVer, k, sidx)] |= (char) smask;
                    }
                }
            }
        }
        var bdMax = f.bitdepthMax;
        var level = f.lfLevel;
        var yStride = cur.stride[0];
        var y0 = sby * f.sbStep * 4 * yStride;
        var levelBase = 4 * (f.b4Stride * sby * sbsz);
        for (var x = 0; x < f.sb128w; x++) {
            var lf = f.lfMask[maskBase + x];
            var fy = lf.filterY;
            var w = Math.min(32, f.w4 - x * 32);
            var dst = y0 + x * 128;
            var lvl = levelBase + 4 * 32 * x;
            for (var xx = 0; xx < w; xx++) {
                if (x == 0 && xx == 0) {
                    continue;
                }
                int m0;
                int m1;
                int m2;
                if (starty4 == 0) {
                    m0 = fy[LfMask.yIndex(0, xx, 0, 0)];
                    m1 = fy[LfMask.yIndex(0, xx, 1, 0)];
                    m2 = fy[LfMask.yIndex(0, xx, 2, 0)];
                    if (endy4 > 16) {
                        m0 |= fy[LfMask.yIndex(0, xx, 0, 1)] << 16;
                        m1 |= fy[LfMask.yIndex(0, xx, 1, 1)] << 16;
                        m2 |= fy[LfMask.yIndex(0, xx, 2, 1)] << 16;
                    }
                } else {
                    m0 = fy[LfMask.yIndex(0, xx, 0, 1)];
                    m1 = fy[LfMask.yIndex(0, xx, 1, 1)];
                    m2 = fy[LfMask.yIndex(0, xx, 2, 1)];
                }
                dsp.loopFilterSb(0, 0, cur.data[0], dst + xx * 4, yStride, m0, m1, m2, level, lvl + 4 * xx,
                        f.b4Stride, f.limLut, endy4 - starty4, scratch, bdMax);
            }
        }
        if (hdr.loopfilterLevelU == 0 && hdr.loopfilterLevelV == 0) {
            return;
        }
        var uvStride = cur.stride[1];
        var uv0 = (sby * f.sbStep * 4 * uvStride) >> ssVer;
        levelBase = 4 * (f.b4Stride * (sby * sbsz >> ssVer));
        for (var x = 0; x < f.sb128w; x++) {
            var lf = f.lfMask[maskBase + x];
            var fuv = lf.filterUv;
            var w = (Math.min(32, f.w4 - x * 32) + ssHor) >> ssHor;
            var uvOff = uv0 + x * (128 >> ssHor);
            var lvl = levelBase + 4 * x * (32 >> ssHor);
            var sy = starty4 >> ssVer;
            for (var xx = 0; xx < w; xx++) {
                if (x == 0 && xx == 0) {
                    continue;
                }
                int m0;
                int m1;
                if (sy == 0) {
                    m0 = fuv[LfMask.uvIndex(0, xx, 0, 0)];
                    m1 = fuv[LfMask.uvIndex(0, xx, 1, 0)];
                    if (uvEndy4 > (16 >> ssVer)) {
                        m0 |= fuv[LfMask.uvIndex(0, xx, 0, 1)] << (16 >> ssVer);
                        m1 |= fuv[LfMask.uvIndex(0, xx, 1, 1)] << (16 >> ssVer);
                    }
                } else {
                    m0 = fuv[LfMask.uvIndex(0, xx, 0, 1)];
                    m1 = fuv[LfMask.uvIndex(0, xx, 1, 1)];
                }
                dsp.loopFilterSb(1, 0, cur.data[1], uvOff + xx * 4, uvStride, m0, m1, 0, level, lvl + 4 * xx + 2,
                        f.b4Stride, f.limLut, uvEndy4 - sy, scratch, bdMax);
                dsp.loopFilterSb(1, 0, cur.data[2], uvOff + xx * 4, uvStride, m0, m1, 0, level, lvl + 4 * xx + 3,
                        f.b4Stride, f.limLut, uvEndy4 - sy, scratch, bdMax);
            }
        }
    }

    /**
     * Filters the horizontal edges (between rows) of a superblock row
     * ({@code dav1d_loopfilter_sbrow_rows}).
     *
     * @param f   the frame
     * @param sby the superblock row
     */
    static void sbrowRows(FrameContext f, int sby) {
        var hdr = f.frameHdr;
        var cur = f.cur;
        var dsp = f.lf;
        var haveTop = sby > 0;
        var isSb64 = f.seqHdr.sb128 ? 0 : 1;
        var starty4 = (sby & isSb64) << 4;
        var sbsz = 32 >> isSb64;
        var ssVer = cur.layout == SequenceHeader.LAYOUT_I420 ? 1 : 0;
        var ssHor = cur.layout != SequenceHeader.LAYOUT_I444 ? 1 : 0;
        var endy4 = starty4 + Math.min(f.h4 - sby * sbsz, sbsz);
        var uvEndy4 = (endy4 + ssVer) >> ssVer;
        var maskBase = (sby >> isSb64) * f.sb128w;
        var bdMax = f.bitdepthMax;
        var level = f.lfLevel;
        var yStride = cur.stride[0];
        var y0 = sby * f.sbStep * 4 * yStride;
        var levelBase = 4 * (f.b4Stride * sby * sbsz);
        for (var x = 0; x < f.sb128w; x++) {
            var fy = f.lfMask[maskBase + x].filterY;
            var w = Math.min(32, f.w4 - x * 32);
            var dst = y0 + x * 128;
            var lvl = levelBase + 4 * 32 * x;
            for (var y = starty4; y < endy4; y++, dst += 4 * yStride, lvl += 4 * f.b4Stride) {
                if (!haveTop && y == 0) {
                    continue;
                }
                var m0 = fy[LfMask.yIndex(1, y, 0, 0)] | fy[LfMask.yIndex(1, y, 0, 1)] << 16;
                var m1 = fy[LfMask.yIndex(1, y, 1, 0)] | fy[LfMask.yIndex(1, y, 1, 1)] << 16;
                var m2 = fy[LfMask.yIndex(1, y, 2, 0)] | fy[LfMask.yIndex(1, y, 2, 1)] << 16;
                dsp.loopFilterSb(0, 1, cur.data[0], dst, yStride, m0, m1, m2, level, lvl + 1, f.b4Stride, f.limLut, w,
                        f.lfScratch, bdMax);
            }
        }
        if (hdr.loopfilterLevelU == 0 && hdr.loopfilterLevelV == 0) {
            return;
        }
        var uvStride = cur.stride[1];
        var uv0 = (sby * f.sbStep * 4 * uvStride) >> ssVer;
        levelBase = 4 * (f.b4Stride * (sby * sbsz >> ssVer));
        for (var x = 0; x < f.sb128w; x++) {
            var fuv = f.lfMask[maskBase + x].filterUv;
            var w = (Math.min(32, f.w4 - x * 32) + ssHor) >> ssHor;
            var uvOff = uv0 + x * (128 >> ssHor);
            var lvl = levelBase + 4 * x * (32 >> ssHor);
            var offL = 0;
            for (var y = starty4 >> ssVer; y < uvEndy4; y++, offL += 4 * uvStride, lvl += 4 * f.b4Stride) {
                if (!haveTop && y == 0) {
                    continue;
                }
                var m0 = fuv[LfMask.uvIndex(1, y, 0, 0)] | fuv[LfMask.uvIndex(1, y, 0, 1)] << (16 >> ssHor);
                var m1 = fuv[LfMask.uvIndex(1, y, 1, 0)] | fuv[LfMask.uvIndex(1, y, 1, 1)] << (16 >> ssHor);
                dsp.loopFilterSb(1, 1, cur.data[1], uvOff + offL, uvStride, m0, m1, 0, level, lvl + 2, f.b4Stride,
                        f.limLut, w, f.lfScratch, bdMax);
                dsp.loopFilterSb(1, 1, cur.data[2], uvOff + offL, uvStride, m0, m1, 0, level, lvl + 3, f.b4Stride,
                        f.limLut, w, f.lfScratch, bdMax);
            }
        }
    }

    /**
     * Saves the deblocked rows above and below the loop restoration stripes of a plane
     * ({@code backup_lpf}).
     *
     * @param f        the frame
     * @param dst      the line buffer
     * @param dstOff   the offset of the line buffer rows of this superblock row
     * @param dstStride the line buffer stride
     * @param src      the plane
     * @param srcOff   the offset of the first source row
     * @param srcStride the plane stride
     * @param ssVer    the vertical subsampling
     * @param sb128    whether superblocks are 128x128
     * @param row      the first row of the superblock row's stripes, in plane rows
     * @param rowH     the last row to save
     * @param srcW     the width to copy
     * @param h        the plane height
     * @param ssHor    the horizontal subsampling
     * @param lrBackup whether the copy is for loop restoration (otherwise for CDEF)
     */
    private static void backupLpf(FrameContext f, short[] dst, int dstOff, int dstStride, short[] src, int srcOff,
                                  int srcStride, int ssVer, int sb128, int row, int rowH, int srcW, int h, int ssHor,
                                  boolean lrBackup) {
        var cdefBackup = lrBackup ? 0 : 1;
        var dstW = f.frameHdr.superResEnabled ? (f.frameHdr.upscaledWidth + ssHor) >> ssHor : srcW;
        var stripeH = ((64 << (cdefBackup & sb128)) - 8 * (row == 0 ? 1 : 0)) >> ssVer;
        srcOff += (stripeH - 2) * srcStride;
        if (!f.haveTt) {
            if (row != 0) {
                var top = 4 << sb128;
                System.arraycopy(dst, dstOff + dstStride * top, dst, dstOff, dstW);
                System.arraycopy(dst, dstOff + dstStride * (top + 1), dst, dstOff + dstStride, dstW);
                System.arraycopy(dst, dstOff + dstStride * (top + 2), dst, dstOff + dstStride * 2, dstW);
                System.arraycopy(dst, dstOff + dstStride * (top + 3), dst, dstOff + dstStride * 3, dstW);
            }
            dstOff += 4 * dstStride;
        }
        if (lrBackup && f.resized()) {
            var mc = f.mc;
            while (row + stripeH <= rowH) {
                var nLines = 4 - (row + stripeH + 1 == h ? 1 : 0);
                mc.resize(dst, dstOff, dstStride, src, srcOff, srcStride, dstW, nLines, srcW,
                        f.resizeStep[ssHor], f.resizeStart[ssHor], f.bitdepthMax);
                row += stripeH;
                stripeH = 64 >> ssVer;
                srcOff += stripeH * srcStride;
                dstOff += nLines * dstStride;
                if (nLines == 3) {
                    System.arraycopy(dst, dstOff - dstStride, dst, dstOff, dstW);
                    dstOff += dstStride;
                }
            }
        } else {
            while (row + stripeH <= rowH) {
                var nLines = 4 - (row + stripeH + 1 == h ? 1 : 0);
                for (var i = 0; i < 4; i++) {
                    if (i == nLines) {
                        System.arraycopy(dst, dstOff - dstStride, dst, dstOff, srcW);
                    } else {
                        System.arraycopy(src, srcOff, dst, dstOff, srcW);
                    }
                    dstOff += dstStride;
                    srcOff += srcStride;
                }
                row += stripeH;
                stripeH = 64 >> ssVer;
                srcOff += (stripeH - 4) * srcStride;
            }
        }
    }

    /**
     * Saves the deblocked lines CDEF and loop restoration of later superblock rows read
     * ({@code dav1d_copy_lpf}).
     *
     * @param f   the frame
     * @param sby the superblock row
     */
    static void copyLpf(FrameContext f, int sby) {
        var cur = f.cur;
        var resize = f.resized();
        var offset = 8 * (sby != 0 ? 1 : 0);
        var srcStride = cur.stride;
        var lrStride = f.srCur.stride;
        var sb128 = f.seqHdr.sb128 ? 1 : 0;
        var ttOff = f.haveTt ? sby * (4 << sb128) : 0;
        var restorePlanes = f.restorePlanes;
        var cdef = f.seqHdr.cdef;
        var y = sby * f.sbStep * 4;
        if (cdef || (restorePlanes & 1) != 0) {
            var h = cur.h;
            var w = f.bw << 2;
            var rowH = Math.min((sby + 1) << (6 + sb128), h - 1);
            var yStripe = (sby << (6 + sb128)) - offset;
            var src = (y - offset) * srcStride[0];
            if ((restorePlanes & 1) != 0 || !resize) {
                backupLpf(f, f.lrLpfLine[0], ttOff * lrStride[0], lrStride[0], cur.data[0], src, srcStride[0], 0, sb128,
                        yStripe, rowH, w, h, 0, true);
            }
            if (f.haveTt && resize) {
                backupLpf(f, f.cdefLpfLine[0], sby * 4 * srcStride[0], srcStride[0], cur.data[0], src, srcStride[0], 0,
                        sb128, yStripe, rowH, w, h, 0, false);
            }
        }
        if ((cdef || (restorePlanes & 6) != 0) && cur.layout != SequenceHeader.LAYOUT_I400) {
            var ssVer = cur.layout == SequenceHeader.LAYOUT_I420 ? 1 : 0;
            var ssHor = cur.layout != SequenceHeader.LAYOUT_I444 ? 1 : 0;
            var h = (cur.h + ssVer) >> ssVer;
            var w = f.bw << (2 - ssHor);
            var rowH = Math.min((sby + 1) << ((6 - ssVer) + sb128), h - 1);
            var offsetUv = offset >> ssVer;
            var yStripe = (sby << ((6 - ssVer) + sb128)) - offsetUv;
            var cdefOffUv = sby * 4 * srcStride[1];
            var src = ((y * srcStride[1]) >> ssVer) - offsetUv * srcStride[1];
            for (var pl = 1; pl <= 2; pl++) {
                if (cdef || (restorePlanes & (1 << pl)) != 0) {
                    if ((restorePlanes & (1 << pl)) != 0 || !resize) {
                        backupLpf(f, f.lrLpfLine[pl], ttOff * lrStride[1], lrStride[1], cur.data[pl], src, srcStride[1],
                                ssVer, sb128, yStripe, rowH, w, h, ssHor, true);
                    }
                    if (f.haveTt && resize) {
                        backupLpf(f, f.cdefLpfLine[pl], cdefOffUv, srcStride[1], cur.data[pl], src, srcStride[1], ssVer,
                                sb128, yStripe, rowH, w, h, ssHor, false);
                    }
                }
            }
        }
    }
}
