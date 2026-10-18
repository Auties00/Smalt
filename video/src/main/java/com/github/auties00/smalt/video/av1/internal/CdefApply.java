package com.github.auties00.smalt.video.av1.internal;

/**
 * Applies the constrained directional enhancement filter to superblock rows (AV1 specification
 * section 7.15), keeping the pre-filter samples it reads across block, superblock and superblock
 * row boundaries.
 *
 * @implNote This implementation ports dav1d's {@code src/cdef_apply_tmpl.c}
 *           ({@code dav1d_cdef_brow}, {@code backup2lines}, {@code backup2x8},
 *           {@code adjust_strength}) and {@code dav1d_filter_sbrow_cdef}
 *           ({@code src/recon_tmpl.c}).
 */
final class CdefApply {
    /**
     * The flag of a luma left column backup.
     */
    private static final int BACKUP_2X8_Y = 1;

    /**
     * The flag of a chroma left column backup.
     */
    private static final int BACKUP_2X8_UV = 2;

    /**
     * Holds the chroma direction of each luma direction for 4:2:0 / 4:4:4 ({@code [0]}) and 4:2:2
     * ({@code [1]}).
     */
    private static final int[][] UV_DIRS = {{0, 1, 2, 3, 4, 5, 6, 7}, {7, 0, 2, 4, 5, 6, 6, 6}};

    /**
     * Prevents instantiation.
     */
    private CdefApply() {
        throw new AssertionError();
    }

    /**
     * Applies CDEF to the 8x8 block rows of a superblock row that are complete
     * ({@code dav1d_filter_sbrow_cdef}).
     *
     * @param tc  the task context providing scratch
     * @param f   the frame
     * @param sby the superblock row
     */
    static void filterSbrow(TaskContext tc, FrameContext f, int sby) {
        var sbsz = f.sbStep;
        var y = sby * sbsz * 4;
        var cur = f.cur;
        var ssVer = cur.layout == SequenceHeader.LAYOUT_I420 ? 1 : 0;
        var shift = f.seqHdr.sb128 ? 0 : 1;
        var p0 = y * cur.stride[0];
        var p1 = (y * cur.stride[1]) >> ssVer;
        var start = sby * sbsz;
        if (sby != 0) {
            var prevMask = ((sby - 1) >> shift) * f.sb128w;
            brow(tc, f, p0 - 8 * cur.stride[0], p1 - ((8 * cur.stride[1]) >> ssVer), prevMask, start - 2, start, true,
                    sby);
        }
        var nBlks = sbsz - 2 * (sby + 1 < f.sbh ? 1 : 0);
        var end = Math.min(start + nBlks, f.bh);
        brow(tc, f, p0, p1, (sby >> shift) * f.sb128w, start, end, false, sby);
    }

    /**
     * Returns the primary strength adjusted for the block's directional variance
     * ({@code adjust_strength}).
     *
     * @param strength the primary strength
     * @param var      the variance
     * @return the adjusted strength
     */
    private static int adjustStrength(int strength, int var) {
        if (var == 0) {
            return 0;
        }
        var v = var >>> 6;
        var i = v != 0 ? Math.min(31 - Integer.numberOfLeadingZeros(v), 12) : 0;
        return (strength * (4 + i) + 8) >> 4;
    }

    /**
     * Saves the bottom two rows of an 8-row stripe of every plane before filtering
     * ({@code backup2lines}).
     *
     * @param f    the frame
     * @param tf   the line buffer toggle to write
     * @param sby  the superblock row
     * @param y0   the luma offset of the stripe
     * @param uv0  the chroma offset of the stripe
     */
    private static void backup2lines(FrameContext f, int tf, int sby, int y0, int uv0) {
        var cur = f.cur;
        var yStride = cur.stride[0];
        var yOff = f.cdefLineOff[tf][0] + (f.haveTt ? sby * 4 * yStride : 0);
        System.arraycopy(cur.data[0], y0 + 6 * yStride, f.cdefLine[0], yOff, 2 * yStride);
        if (cur.layout != SequenceHeader.LAYOUT_I400) {
            var uvStride = cur.stride[1];
            var uvSrc = cur.layout == SequenceHeader.LAYOUT_I420 ? 2 : 6;
            var tt = f.haveTt ? sby * 8 * uvStride : 0;
            System.arraycopy(cur.data[1], uv0 + uvSrc * uvStride, f.cdefLine[1], f.cdefLineOff[tf][1] + tt,
                    2 * uvStride);
            System.arraycopy(cur.data[2], uv0 + uvSrc * uvStride, f.cdefLine[2], f.cdefLineOff[tf][2] + tt,
                    2 * uvStride);
        }
    }

    /**
     * Saves the two columns left of a block's position of each plane ({@code backup2x8}).
     *
     * @param f     the frame
     * @param dst   the column buffer
     * @param bit   the buffer half to write
     * @param by0   the luma offset of the block row
     * @param uv0   the chroma offset of the block row
     * @param xOff  the luma column of the columns' right edge
     * @param flag  which planes to save
     */
    private static void backup2x8(FrameContext f, short[] dst, int bit, int by0, int uv0, int xOff, int flag) {
        var cur = f.cur;
        if ((flag & BACKUP_2X8_Y) != 0) {
            var yStride = cur.stride[0];
            var src = cur.data[0];
            for (var y = 0; y < 8; y++) {
                var s = by0 + y * yStride + xOff - 2;
                var d = ((bit * 3) * 8 + y) * 2;
                dst[d] = src[s];
                dst[d + 1] = src[s + 1];
            }
        }
        if (cur.layout == SequenceHeader.LAYOUT_I400 || (flag & BACKUP_2X8_UV) == 0) {
            return;
        }
        var ssVer = cur.layout == SequenceHeader.LAYOUT_I420 ? 1 : 0;
        var ssHor = cur.layout != SequenceHeader.LAYOUT_I444 ? 1 : 0;
        var uvStride = cur.stride[1];
        var x = xOff >> ssHor;
        for (var y = 0; y < (8 >> ssVer); y++) {
            var s = uv0 + y * uvStride + x - 2;
            var d1 = ((bit * 3 + 1) * 8 + y) * 2;
            var d2 = ((bit * 3 + 2) * 8 + y) * 2;
            dst[d1] = cur.data[1][s];
            dst[d1 + 1] = cur.data[1][s + 1];
            dst[d2] = cur.data[2][s];
            dst[d2 + 1] = cur.data[2][s + 1];
        }
    }

    /**
     * Filters a range of 8x8 block rows ({@code dav1d_cdef_brow}).
     *
     * @param tc          the task context
     * @param f           the frame
     * @param p0          the luma offset of the first block row
     * @param p1          the chroma offset of the first block row
     * @param maskBase    the index of the first loop filter mask of the superblock row
     * @param byStart     the first block row in 4-sample units
     * @param byEnd       the block row after the last one
     * @param sbrowStart  whether the range is the end of the previous superblock row
     * @param sby         the superblock row
     */
    private static void brow(TaskContext tc, FrameContext f, int p0, int p1, int maskBase, int byStart, int byEnd,
                             boolean sbrowStart, int sby) {
        var hdr = f.frameHdr;
        var cur = f.cur;
        var dsp = f.cdef;
        var bitdepthMin8 = cur.bpc - 8;
        var edges = CdefDsp.HAVE_BOTTOM | (byStart > 0 ? CdefDsp.HAVE_TOP : 0);
        var sbsz = 16;
        var sb64w = f.sb128w << 1;
        var damping = hdr.cdefDamping + bitdepthMin8;
        var layout = cur.layout;
        var uvIdx = SequenceHeader.LAYOUT_I444 - layout;
        var ssVer = layout == SequenceHeader.LAYOUT_I420 ? 1 : 0;
        var ssHor = layout != SequenceHeader.LAYOUT_I444 ? 1 : 0;
        var uvDir = UV_DIRS[layout == SequenceHeader.LAYOUT_I422 ? 1 : 0];
        var haveTt = f.haveTt;
        var sb128 = f.seqHdr.sb128 ? 1 : 0;
        var resize = f.resized();
        var yStride = cur.stride[0];
        var uvStride = cur.stride[1];
        var lrBak = tc.cdefLrBak;
        var variance = tc.cdefVar;
        var bdMax = f.bitdepthMax;
        var ptr0 = p0;
        var ptr1 = p1;
        var bit = 0;
        for (var by = byStart; by < byEnd; by += 2, edges |= CdefDsp.HAVE_TOP) {
            var tf = tc.topPreCdefToggle;
            var byIdx = (by & 30) >> 1;
            if (by + 2 >= f.bh) {
                edges &= ~CdefDsp.HAVE_BOTTOM;
            }
            if ((!haveTt || sbrowStart || by + 2 < byEnd) && (edges & CdefDsp.HAVE_BOTTOM) != 0) {
                backup2lines(f, tf ^ 1, sby, ptr0, ptr1);
            }
            var iptr0 = ptr0;
            var iptr1 = ptr1;
            edges &= ~CdefDsp.HAVE_LEFT;
            edges |= CdefDsp.HAVE_RIGHT;
            var prevFlag = 0;
            for (var sbx = 0; sbx < sb64w; sbx++, edges |= CdefDsp.HAVE_LEFT) {
                var sb128x = sbx >> 1;
                var sb64Idx = ((by & sbsz) >> 3) + (sbx & 1);
                var lf = f.lfMask[maskBase + sb128x];
                var cdefIdx = lf.cdefIdx[sb64Idx];
                if (cdefIdx == -1 || (hdr.cdefYStrength[cdefIdx] == 0 && hdr.cdefUvStrength[cdefIdx] == 0)) {
                    prevFlag = 0;
                    iptr0 += sbsz * 4;
                    iptr1 += sbsz * 4 >> ssHor;
                    continue;
                }
                var noskipMask = lf.noskipMask[byIdx * 2 + 1] << 16 | lf.noskipMask[byIdx * 2];
                var yLvl = hdr.cdefYStrength[cdefIdx];
                var uvLvl = hdr.cdefUvStrength[cdefIdx];
                var flag = (yLvl != 0 ? 1 : 0) + ((uvLvl != 0 ? 1 : 0) << 1);
                var yPriLvl = (yLvl >> 2) << bitdepthMin8;
                var ySecLvl = yLvl & 3;
                ySecLvl += ySecLvl == 3 ? 1 : 0;
                ySecLvl <<= bitdepthMin8;
                var uvPriLvl = (uvLvl >> 2) << bitdepthMin8;
                var uvSecLvl = uvLvl & 3;
                uvSecLvl += uvSecLvl == 3 ? 1 : 0;
                uvSecLvl <<= bitdepthMin8;
                var bptr0 = iptr0;
                var bptr1 = iptr1;
                for (var bx = sbx * sbsz; bx < Math.min((sbx + 1) * sbsz, f.bw); bx += 2, edges |= CdefDsp.HAVE_LEFT) {
                    if (bx + 2 >= f.bw) {
                        edges &= ~CdefDsp.HAVE_RIGHT;
                    }
                    var bxMask = 3 << (bx & 30);
                    if ((noskipMask & bxMask) == 0) {
                        prevFlag = 0;
                        bptr0 += 8;
                        bptr1 += 8 >> ssHor;
                        continue;
                    }
                    var doLeft = (prevFlag ^ flag) & flag;
                    prevFlag = flag;
                    if (doLeft != 0 && (edges & CdefDsp.HAVE_LEFT) != 0) {
                        backup2x8(f, lrBak, bit, bptr0, bptr1, 0, doLeft);
                    }
                    if ((edges & CdefDsp.HAVE_RIGHT) != 0) {
                        backup2x8(f, lrBak, bit ^ 1, bptr0, bptr1, 8, flag);
                    }
                    var dir = 0;
                    if (yPriLvl != 0 || uvPriLvl != 0) {
                        dir = dsp.dir(cur.data[0], bptr0, yStride, variance, bdMax);
                    }
                    short[] topArr;
                    int topOff;
                    short[] botArr;
                    int botOff;
                    if (haveTt && sbrowStart && by == byStart) {
                        if (resize) {
                            topArr = f.cdefLpfLine[0];
                            topOff = (sby - 1) * 4 * yStride + bx * 4;
                        } else {
                            topArr = f.lrLpfLine[0];
                            topOff = (sby * (4 << sb128) - 4) * f.srCur.stride[0] + bx * 4;
                        }
                        botArr = cur.data[0];
                        botOff = bptr0 + 8 * yStride;
                    } else if (haveTt && !sbrowStart && by + 2 >= byEnd) {
                        topArr = f.cdefLine[0];
                        topOff = f.cdefLineOff[tf][0] + sby * 4 * yStride + bx * 4;
                        if (resize) {
                            botArr = f.cdefLpfLine[0];
                            botOff = (sby * 4 + 2) * yStride + bx * 4;
                        } else {
                            var line = sby * (4 << sb128) + 4 * sb128 + 2;
                            botArr = f.lrLpfLine[0];
                            botOff = line * f.srCur.stride[0] + bx * 4;
                        }
                    } else {
                        topArr = f.cdefLine[0];
                        topOff = f.cdefLineOff[tf][0] + (haveTt ? sby * 4 * yStride : 0) + bx * 4;
                        botArr = cur.data[0];
                        botOff = bptr0 + 8 * yStride;
                    }
                    if (yPriLvl != 0) {
                        var adjYPriLvl = adjustStrength(yPriLvl, variance[0]);
                        if (adjYPriLvl != 0 || ySecLvl != 0) {
                            dsp.filter(CdefDsp.LAYOUT_8X8, cur.data[0], bptr0, yStride, lrBak, (bit * 3) * 16, topArr,
                                    topOff, botArr, botOff, adjYPriLvl, ySecLvl, dir, damping, edges, tc.cdefScratch,
                                    bdMax);
                        }
                    } else if (ySecLvl != 0) {
                        dsp.filter(CdefDsp.LAYOUT_8X8, cur.data[0], bptr0, yStride, lrBak, (bit * 3) * 16, topArr, topOff,
                                botArr, botOff, 0, ySecLvl, 0, damping, edges, tc.cdefScratch, bdMax);
                    }
                    if (uvLvl != 0) {
                        var uvdir = uvPriLvl != 0 ? uvDir[dir] : 0;
                        for (var pl = 1; pl <= 2; pl++) {
                            if (haveTt && sbrowStart && by == byStart) {
                                if (resize) {
                                    topArr = f.cdefLpfLine[pl];
                                    topOff = (sby - 1) * 4 * uvStride + (bx * 4 >> ssHor);
                                } else {
                                    var line = sby * (4 << sb128) - 4;
                                    topArr = f.lrLpfLine[pl];
                                    topOff = line * f.srCur.stride[1] + (bx * 4 >> ssHor);
                                }
                                botArr = cur.data[pl];
                                botOff = bptr1 + (8 >> ssVer) * uvStride;
                            } else if (haveTt && !sbrowStart && by + 2 >= byEnd) {
                                topArr = f.cdefLine[pl];
                                topOff = f.cdefLineOff[tf][pl] + sby * 8 * uvStride + (bx * 4 >> ssHor);
                                if (resize) {
                                    botArr = f.cdefLpfLine[pl];
                                    botOff = (sby * 4 + 2) * uvStride + (bx * 4 >> ssHor);
                                } else {
                                    var line = sby * (4 << sb128) + 4 * sb128 + 2;
                                    botArr = f.lrLpfLine[pl];
                                    botOff = line * f.srCur.stride[1] + (bx * 4 >> ssHor);
                                }
                            } else {
                                topArr = f.cdefLine[pl];
                                topOff = f.cdefLineOff[tf][pl] + (haveTt ? sby * 8 * uvStride : 0) + (bx * 4 >> ssHor);
                                botArr = cur.data[pl];
                                botOff = bptr1 + (8 >> ssVer) * uvStride;
                            }
                            dsp.filter(uvIdx, cur.data[pl], bptr1, uvStride, lrBak, (bit * 3 + pl) * 16, topArr, topOff,
                                    botArr, botOff, uvPriLvl, uvSecLvl, uvdir, damping - 1, edges, tc.cdefScratch, bdMax);
                        }
                    }
                    bit ^= 1;
                    bptr0 += 8;
                    bptr1 += 8 >> ssHor;
                }
                iptr0 += sbsz * 4;
                iptr1 += sbsz * 4 >> ssHor;
            }
            ptr0 += 8 * yStride;
            ptr1 += (8 * uvStride) >> ssVer;
            tc.topPreCdefToggle ^= 1;
        }
    }
}
