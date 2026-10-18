package com.github.auties00.smalt.video.hevc.internal;

/**
 * Applies the deblocking filter of ITU-T H.265 8.7.2 to a decoded picture: the boundary strength
 * derivation of the transform and prediction block edges on the 8x8 grid, and the luma and chroma
 * edge filters, vertical edges first and then horizontal edges.
 *
 * <p>A picture may be filtered one row of coding tree blocks at a time, top to bottom: the
 * filtering of a row (its vertical edges, then its horizontal edges including the edge with the
 * row above) gives the same samples as the picture-wide order of the specification, because edges
 * lie 8 samples apart and a filter reads at most 4 and modifies at most 3 samples on each side.
 * Filtering a row modifies its samples and the bottom 3 sample rows of the row above.
 *
 * <p>Instances are not thread-safe.
 */
public final class Deblocker {
    /**
     * {@code beta'} of Table 8-12, indexed by {@code Q} from 0 to 51.
     */
    private static final int[] BETA = {
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 20, 22, 24,
            26, 28, 30, 32, 34, 36, 38, 40, 42, 44, 46, 48, 50, 52, 54, 56, 58, 60, 62, 64
    };

    /**
     * {@code tC'} of Table 8-12, indexed by {@code Q} from 0 to 53.
     */
    private static final int[] TC = {
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 3,
            3, 3, 3, 4, 4, 4, 5, 5, 6, 6, 7, 8, 9, 10, 11, 13, 14, 16, 18, 20, 22, 24
    };

    /**
     * {@code QpC} of Table 8-10 for {@code qPi} 30 to 43.
     */
    private static final int[] QPC_TABLE = {29, 30, 31, 32, 33, 33, 34, 34, 35, 35, 36, 36, 37, 37};

    /**
     * The boundary strengths of the vertical edges, one per 4x4 block (for its left edge).
     */
    private byte[] bsVertical = new byte[0];

    /**
     * The boundary strengths of the horizontal edges, one per 4x4 block (for its top edge).
     */
    private byte[] bsHorizontal = new byte[0];

    /**
     * The per-column {@code tC} values and filter flags of a group of 16 columns of a horizontal
     * edge of an 8-bit picture, as {@link HevcDsp#deblockLuma} takes them.
     */
    private final short[] lumaParams = new short[32];

    /**
     * The scratch buffer of {@link HevcDsp#deblockLumaVertical}.
     */
    private final byte[] lumaScratch = new byte[HevcDsp.DEBLOCK_SCRATCH];

    /**
     * Constructs a deblocker.
     */
    public Deblocker() {
    }

    /**
     * Deblocks rows of coding tree blocks of a decoded picture; the rows above them must have been
     * filtered already.
     *
     * @param frame the picture
     * @param pic   the coding information of the picture
     * @param from  the first row of coding tree blocks
     * @param to    the row after the last
     */
    public void filterRows(HevcFrame frame, PictureState pic, int from, int to) {
        var blocks = pic.width4 * pic.height4;
        if (bsVertical.length < blocks) {
            bsVertical = new byte[blocks];
            bsHorizontal = new byte[blocks];
        }
        var shift = pic.sps.log2CtbSize - 2;
        var by0 = Math.min(from << shift, pic.height4);
        var by1 = Math.min(to << shift, pic.height4);
        var any = computeStrengths(frame, pic, by0, by1);
        if (!any) {
            return;
        }
        filterLuma(frame, pic, true, by0, by1);
        if (frame.chromaFormat != 0) {
            filterChroma(frame, pic, true, by0, by1);
        }
        filterLuma(frame, pic, false, by0, by1);
        if (frame.chromaFormat != 0) {
            filterChroma(frame, pic, false, by0, by1);
        }
    }

    /**
     * Derives the boundary strength of every edge on the 8x8 luma grid in a range of 4x4 block
     * rows (8.7.2.2 to 8.7.2.4). Only the entries of edges on the grid inside the picture are
     * written, and only those are read by the filters.
     *
     * @param frame the picture
     * @param pic   the coding information
     * @param by0   the first 4x4 block row
     * @param by1   the 4x4 block row after the last
     * @return whether any edge has a non-zero strength
     */
    private boolean computeStrengths(HevcFrame frame, PictureState pic, int by0, int by1) {
        var sps = pic.sps;
        var w4 = pic.width4;
        var any = false;
        for (var by = by0; by < by1; by++) {
            var row = by * w4;
            for (var bx = 2; bx < w4; bx += 2) {
                var index = row + bx;
                var edges = pic.edges[index];
                var v = 0;
                if ((edges & (PictureState.EDGE_TU_V | PictureState.EDGE_PU_V)) != 0) {
                    v = strength(frame, pic, sps, bx << 2, by << 2, index - 1, index,
                            (edges & PictureState.EDGE_TU_V) != 0, true);
                    any |= v != 0;
                }
                bsVertical[index] = (byte) v;
            }
            if ((by & 1) != 0 || by == 0) {
                continue;
            }
            for (var bx = 0; bx < w4; bx++) {
                var index = row + bx;
                var edges = pic.edges[index];
                var h = 0;
                if ((edges & (PictureState.EDGE_TU_H | PictureState.EDGE_PU_H)) != 0) {
                    h = strength(frame, pic, sps, bx << 2, by << 2, index - w4, index,
                            (edges & PictureState.EDGE_TU_H) != 0, false);
                    any |= h != 0;
                }
                bsHorizontal[index] = (byte) h;
            }
        }
        return any;
    }

    /**
     * Derives the boundary strength of one edge segment of 4 samples, including the slice, tile and
     * picture boundary rules that switch filtering off.
     *
     * @param frame    the picture
     * @param pic      the coding information
     * @param sps      the sequence parameter set
     * @param x        the luma x of the q0 sample
     * @param y        the luma y of the q0 sample
     * @param p        the index of the 4x4 block holding p0
     * @param q        the index of the 4x4 block holding q0
     * @param tuEdge   whether the edge is a transform block edge
     * @param vertical whether the edge is vertical
     * @return the boundary strength, 0 to 2
     */
    private static int strength(HevcFrame frame, PictureState pic, HevcSps sps, int x, int y, int p, int q,
                                boolean tuEdge, boolean vertical) {
        var log2Ctb = sps.log2CtbSize;
        var ctbQ = (y >> log2Ctb) * sps.widthInCtbs + (x >> log2Ctb);
        var px = vertical ? x - 1 : x;
        var py = vertical ? y : y - 1;
        var ctbP = (py >> log2Ctb) * sps.widthInCtbs + (px >> log2Ctb);
        var headerQ = pic.ctbHeader[ctbQ];
        var headerP = pic.ctbHeader[ctbP];
        if (headerQ == null || headerP == null || headerQ.deblockingDisabled) {
            return 0;
        }
        if (ctbP != ctbQ) {
            if (pic.ctbSliceAddr[ctbP] != pic.ctbSliceAddr[ctbQ] && !headerQ.loopFilterAcrossSlices) {
                return 0;
            }
            var pps = headerQ.pps;
            if (!pps.loopFilterAcrossTiles && pps.tileId[pps.ctbAddrRsToTs[ctbP]] != pps.tileId[pps.ctbAddrRsToTs[ctbQ]]) {
                return 0;
            }
        }
        var flagsP = pic.flags[p];
        var flagsQ = pic.flags[q];
        if (((flagsP | flagsQ) & PictureState.INTRA) != 0) {
            return 2;
        }
        if (tuEdge && ((flagsP | flagsQ) & PictureState.CODED) != 0) {
            return 1;
        }
        var sliceP = frame.ctbSlice[ctbP];
        var sliceQ = frame.ctbSlice[ctbQ];
        var idsP = frame.sliceRefId[sliceP];
        var idsQ = frame.sliceRefId[sliceQ];
        var refP0 = frame.ref0[p] >= 0 ? idsP[frame.ref0[p]] : -1;
        var refP1 = frame.ref1[p] >= 0 ? idsP[16 + frame.ref1[p]] : -1;
        var refQ0 = frame.ref0[q] >= 0 ? idsQ[frame.ref0[q]] : -1;
        var refQ1 = frame.ref1[q] >= 0 ? idsQ[16 + frame.ref1[q]] : -1;
        var mvP0 = frame.ref0[p] >= 0 ? frame.mv0[p] : 0;
        var mvP1 = frame.ref1[p] >= 0 ? frame.mv1[p] : 0;
        var mvQ0 = frame.ref0[q] >= 0 ? frame.mv0[q] : 0;
        var mvQ1 = frame.ref1[q] >= 0 ? frame.mv1[q] : 0;
        if ((refP0 == refQ0 && refP1 == refQ1) || (refP0 == refQ1 && refP1 == refQ0)) {
            if (refP0 != refP1) {
                if (refP0 == refQ0) {
                    return far(mvP0, mvQ0) || far(mvP1, mvQ1) ? 1 : 0;
                }
                return far(mvP0, mvQ1) || far(mvP1, mvQ0) ? 1 : 0;
            }
            return (far(mvP0, mvQ0) || far(mvP1, mvQ1)) && (far(mvP0, mvQ1) || far(mvP1, mvQ0)) ? 1 : 0;
        }
        return 1;
    }

    /**
     * Returns whether two packed motion vectors differ by 4 quarter samples or more in either
     * component.
     *
     * @param a the first vector
     * @param b the second vector
     * @return {@code true} when the vectors are far apart
     */
    private static boolean far(int a, int b) {
        return Math.abs((short) a - (short) b) >= 4 || Math.abs((a >> 16) - (b >> 16)) >= 4;
    }

    /**
     * Filters the luma edges of one direction in a range of 4x4 block rows (8.7.2.5.1 to 8.7.2.5.4,
     * 8.7.2.5.6 and 8.7.2.5.7).
     *
     * @param frame    the picture
     * @param pic      the coding information
     * @param vertical whether to filter the vertical edges
     * @param by0      the first 4x4 block row
     * @param by1      the 4x4 block row after the last
     */
    private void filterLuma(HevcFrame frame, PictureState pic, boolean vertical, int by0, int by1) {
        var sps = pic.sps;
        var plane = frame.y;
        var plane8 = frame.y8;
        var stride = frame.width;
        var w4 = pic.width4;
        var bs = vertical ? bsVertical : bsHorizontal;
        var bitDepth = sps.bitDepthLuma;
        var max = (1 << bitDepth) - 1;
        var pcmProtected = sps.pcmEnabled && sps.pcmLoopFilterDisabled;
        var log2Ctb = sps.log2CtbSize;
        var columnStep = vertical ? 2 : 1;
        for (var by = by0; by < by1; by++) {
            if (!vertical && ((by & 1) != 0 || by == 0)) {
                continue;
            }
            if (!vertical && plane8 != null) {
                horizontalLuma8(pic, plane8, stride, by);
                continue;
            }
            if (vertical && plane8 != null) {
                verticalLuma8(pic, plane8, stride, by, Math.min(by + 4, by1));
                by += 3;
                continue;
            }
            for (var bx = vertical ? 2 : 0; bx < w4; bx += columnStep) {
                var index = by * w4 + bx;
                var strength = bs[index];
                if (strength == 0) {
                    continue;
                }
                var x = bx << 2;
                var y = by << 2;
                var p = vertical ? index - 1 : index - w4;
                var header = pic.ctbHeader[(y >> log2Ctb) * sps.widthInCtbs + (x >> log2Ctb)];
                var qpL = (pic.qp[index] + pic.qp[p] + 1) >> 1;
                var betaQ = Math.clamp(qpL + (header.betaOffsetDiv2 << 1), 0, 51);
                var beta = BETA[betaQ] << (bitDepth - 8);
                var tcQ = Math.clamp(qpL + 2 * (strength - 1) + (header.tcOffsetDiv2 << 1), 0, 53);
                var tc = TC[tcQ] << (bitDepth - 8);
                var noP = (pic.flags[p] & PictureState.BYPASS) != 0 || (pcmProtected && (pic.flags[p] & PictureState.PCM) != 0);
                var noQ = (pic.flags[index] & PictureState.BYPASS) != 0 || (pcmProtected && (pic.flags[index] & PictureState.PCM) != 0);
                var origin = y * stride + x;
                var step = vertical ? 1 : stride;
                var along = vertical ? stride : 1;
                if (plane8 != null) {
                    edge(plane8, origin, step, along, beta, tc, noP, noQ);
                } else {
                    edge(plane, origin, step, along, beta, tc, max, noP, noQ);
                }
            }
        }
    }

    /**
     * Filters one luma edge segment of four lines.
     *
     * @param s      the plane
     * @param origin the index of q0 of the first line
     * @param step   the distance between samples across the edge
     * @param along  the distance between lines along the edge
     * @param beta   {@code beta}
     * @param tc     {@code tC}
     * @param max    the largest sample value
     * @param noP    whether the p side must stay unmodified
     * @param noQ    whether the q side must stay unmodified
     */
    private static void edge(short[] s, int origin, int step, int along, int beta, int tc, int max, boolean noP,
                             boolean noQ) {
        var o0 = origin;
        var o3 = origin + 3 * along;
        var dp0 = Math.abs((s[o0 - 3 * step] & 0xFFFF) - 2 * (s[o0 - 2 * step] & 0xFFFF) + (s[o0 - step] & 0xFFFF));
        var dp3 = Math.abs((s[o3 - 3 * step] & 0xFFFF) - 2 * (s[o3 - 2 * step] & 0xFFFF) + (s[o3 - step] & 0xFFFF));
        var dq0 = Math.abs((s[o0 + 2 * step] & 0xFFFF) - 2 * (s[o0 + step] & 0xFFFF) + (s[o0] & 0xFFFF));
        var dq3 = Math.abs((s[o3 + 2 * step] & 0xFFFF) - 2 * (s[o3 + step] & 0xFFFF) + (s[o3] & 0xFFFF));
        var dpq0 = dp0 + dq0;
        var dpq3 = dp3 + dq3;
        var dp = dp0 + dp3;
        var dq = dq0 + dq3;
        var d = dpq0 + dpq3;
        if (d >= beta) {
            return;
        }
        var strong = decision(s, o0, step, 2 * dpq0, beta, tc) && decision(s, o3, step, 2 * dpq3, beta, tc);
        var dEp = dp < ((beta + (beta >> 1)) >> 3);
        var dEq = dq < ((beta + (beta >> 1)) >> 3);
        for (var k = 0; k < 4; k++) {
            var o = origin + k * along;
            var p0 = s[o - step] & 0xFFFF;
            var p1 = s[o - 2 * step] & 0xFFFF;
            var p2 = s[o - 3 * step] & 0xFFFF;
            var q0 = s[o] & 0xFFFF;
            var q1 = s[o + step] & 0xFFFF;
            var q2 = s[o + 2 * step] & 0xFFFF;
            if (strong) {
                var p3 = s[o - 4 * step] & 0xFFFF;
                var q3 = s[o + 3 * step] & 0xFFFF;
                var tc2 = 2 * tc;
                if (!noP) {
                    s[o - step] = (short) Math.clamp((p2 + 2 * p1 + 2 * p0 + 2 * q0 + q1 + 4) >> 3, p0 - tc2, p0 + tc2);
                    s[o - 2 * step] = (short) Math.clamp((p2 + p1 + p0 + q0 + 2) >> 2, p1 - tc2, p1 + tc2);
                    s[o - 3 * step] = (short) Math.clamp((2 * p3 + 3 * p2 + p1 + p0 + q0 + 4) >> 3, p2 - tc2, p2 + tc2);
                }
                if (!noQ) {
                    s[o] = (short) Math.clamp((p1 + 2 * p0 + 2 * q0 + 2 * q1 + q2 + 4) >> 3, q0 - tc2, q0 + tc2);
                    s[o + step] = (short) Math.clamp((p0 + q0 + q1 + q2 + 2) >> 2, q1 - tc2, q1 + tc2);
                    s[o + 2 * step] = (short) Math.clamp((p0 + q0 + q1 + 3 * q2 + 2 * q3 + 4) >> 3, q2 - tc2, q2 + tc2);
                }
            } else {
                var delta = (9 * (q0 - p0) - 3 * (q1 - p1) + 8) >> 4;
                if (Math.abs(delta) >= tc * 10) {
                    continue;
                }
                delta = Math.clamp(delta, -tc, tc);
                if (!noP) {
                    s[o - step] = (short) Math.clamp(p0 + delta, 0, max);
                    if (dEp) {
                        var deltaP = Math.clamp((((p2 + p0 + 1) >> 1) - p1 + delta) >> 1, -(tc >> 1), tc >> 1);
                        s[o - 2 * step] = (short) Math.clamp(p1 + deltaP, 0, max);
                    }
                }
                if (!noQ) {
                    s[o] = (short) Math.clamp(q0 - delta, 0, max);
                    if (dEq) {
                        var deltaQ = Math.clamp((((q2 + q0 + 1) >> 1) - q1 - delta) >> 1, -(tc >> 1), tc >> 1);
                        s[o + step] = (short) Math.clamp(q1 + deltaQ, 0, max);
                    }
                }
            }
        }
    }

    /**
     * Returns the strong filter decision of one line (8.7.2.5.6).
     *
     * @param s    the plane
     * @param o    the index of q0 of the line
     * @param step the distance between samples across the edge
     * @param dpq  {@code dpq}
     * @param beta {@code beta}
     * @param tc   {@code tC}
     * @return {@code dSam}
     */
    private static boolean decision(short[] s, int o, int step, int dpq, int beta, int tc) {
        var p0 = s[o - step] & 0xFFFF;
        var p3 = s[o - 4 * step] & 0xFFFF;
        var q0 = s[o] & 0xFFFF;
        var q3 = s[o + 3 * step] & 0xFFFF;
        return dpq < (beta >> 2) && Math.abs(p3 - p0) + Math.abs(q0 - q3) < (beta >> 3)
                && Math.abs(p0 - q0) < ((5 * tc + 1) >> 1);
    }

    /**
     * Filters the horizontal luma edge of an 8-bit picture at a row of 4x4 blocks: takes the
     * decisions of every segment of 4 columns, then filters groups of 4 segments with
     * {@link HevcDsp#deblockLuma}; segments past the last whole group are filtered one at a time.
     *
     * @param pic    the coding information
     * @param plane  the luma plane
     * @param stride the plane's row stride
     * @param by     the 4x4 block row of the edge, even and not 0
     */
    private void horizontalLuma8(PictureState pic, byte[] plane, int stride, int by) {
        var sps = pic.sps;
        var w4 = pic.width4;
        var bs = bsHorizontal;
        var pcmProtected = sps.pcmEnabled && sps.pcmLoopFilterDisabled;
        var log2Ctb = sps.log2CtbSize;
        var params = lumaParams;
        var y = by << 2;
        var groups = w4 & ~3;
        for (var bx = 0; bx < w4; bx += 4) {
            var whole = bx < groups;
            var any = false;
            for (var k = 0; k < 4 && bx + k < w4; k++) {
                var segment = bx + k;
                var index = by * w4 + segment;
                var strength = bs[index];
                var flags = 0;
                var tc = 0;
                if (strength != 0) {
                    var x = segment << 2;
                    var p = index - w4;
                    var header = pic.ctbHeader[(y >> log2Ctb) * sps.widthInCtbs + (x >> log2Ctb)];
                    var qpL = (pic.qp[index] + pic.qp[p] + 1) >> 1;
                    var beta = BETA[Math.clamp(qpL + (header.betaOffsetDiv2 << 1), 0, 51)];
                    tc = TC[Math.clamp(qpL + 2 * (strength - 1) + (header.tcOffsetDiv2 << 1), 0, 53)];
                    var noP = (pic.flags[p] & PictureState.BYPASS) != 0
                            || (pcmProtected && (pic.flags[p] & PictureState.PCM) != 0);
                    var noQ = (pic.flags[index] & PictureState.BYPASS) != 0
                            || (pcmProtected && (pic.flags[index] & PictureState.PCM) != 0);
                    var origin = y * stride + x;
                    if (!whole) {
                        edge(plane, origin, stride, 1, beta, tc, noP, noQ);
                        continue;
                    }
                    flags = decide(plane, origin, stride, 1, beta, tc);
                    if (flags != 0) {
                        flags |= (noP ? 0 : HevcDsp.DEBLOCK_WRITE_P) | (noQ ? 0 : HevcDsp.DEBLOCK_WRITE_Q);
                        any = true;
                    }
                }
                if (whole) {
                    for (var i = 0; i < 4; i++) {
                        params[4 * k + i] = (short) tc;
                        params[16 + 4 * k + i] = (short) flags;
                    }
                }
            }
            if (any) {
                pic.dsp.deblockLuma(plane, y * stride + (bx << 2), stride, params);
            }
        }
    }

    /**
     * Filters the vertical luma edges of an 8-bit picture in up to 4 rows of 4x4 blocks: takes the
     * decisions of every segment of 4 rows, then filters the 16 rows of each edge with
     * {@link HevcDsp#deblockLumaVertical}; with fewer than 4 block rows, at the bottom of the
     * picture, the segments are filtered one at a time.
     *
     * @param pic    the coding information
     * @param plane  the luma plane
     * @param stride the plane's row stride
     * @param by0    the first 4x4 block row
     * @param by1    the 4x4 block row after the last, at most {@code by0 + 4}
     */
    private void verticalLuma8(PictureState pic, byte[] plane, int stride, int by0, int by1) {
        var sps = pic.sps;
        var w4 = pic.width4;
        var bs = bsVertical;
        var pcmProtected = sps.pcmEnabled && sps.pcmLoopFilterDisabled;
        var log2Ctb = sps.log2CtbSize;
        var params = lumaParams;
        var whole = by1 - by0 == 4;
        for (var bx = 2; bx < w4; bx += 2) {
            var x = bx << 2;
            var any = false;
            for (var k = 0; k < by1 - by0; k++) {
                var index = (by0 + k) * w4 + bx;
                var strength = bs[index];
                var flags = 0;
                var tc = 0;
                if (strength != 0) {
                    var y = (by0 + k) << 2;
                    var p = index - 1;
                    var header = pic.ctbHeader[(y >> log2Ctb) * sps.widthInCtbs + (x >> log2Ctb)];
                    var qpL = (pic.qp[index] + pic.qp[p] + 1) >> 1;
                    var beta = BETA[Math.clamp(qpL + (header.betaOffsetDiv2 << 1), 0, 51)];
                    tc = TC[Math.clamp(qpL + 2 * (strength - 1) + (header.tcOffsetDiv2 << 1), 0, 53)];
                    var noP = (pic.flags[p] & PictureState.BYPASS) != 0
                            || (pcmProtected && (pic.flags[p] & PictureState.PCM) != 0);
                    var noQ = (pic.flags[index] & PictureState.BYPASS) != 0
                            || (pcmProtected && (pic.flags[index] & PictureState.PCM) != 0);
                    var origin = y * stride + x;
                    if (!whole) {
                        edge(plane, origin, 1, stride, beta, tc, noP, noQ);
                        continue;
                    }
                    flags = decide(plane, origin, 1, stride, beta, tc);
                    if (flags != 0) {
                        flags |= (noP ? 0 : HevcDsp.DEBLOCK_WRITE_P) | (noQ ? 0 : HevcDsp.DEBLOCK_WRITE_Q);
                        any = true;
                    }
                }
                if (whole) {
                    for (var i = 0; i < 4; i++) {
                        params[4 * k + i] = (short) tc;
                        params[16 + 4 * k + i] = (short) flags;
                    }
                }
            }
            if (any) {
                pic.dsp.deblockLumaVertical(plane, (by0 << 2) * stride + x, stride, params, lumaScratch);
            }
        }
    }

    /**
     * Takes the filter decisions of one luma segment of four lines across an edge of an 8-bit
     * plane (8.7.2.5.3 and 8.7.2.5.6).
     *
     * @param s      the plane
     * @param origin the index of {@code q0} of the first line
     * @param step   the distance between samples across the edge
     * @param along  the distance between lines along the edge
     * @param beta   {@code beta}
     * @param tc     {@code tC}
     * @return 0 when the segment is not filtered, {@link HevcDsp#DEBLOCK_STRONG}, or
     *         {@link HevcDsp#DEBLOCK_NORMAL} with {@link HevcDsp#DEBLOCK_P1} and
     *         {@link HevcDsp#DEBLOCK_Q1} as decided
     */
    private static int decide(byte[] s, int origin, int step, int along, int beta, int tc) {
        var o0 = origin;
        var o3 = origin + 3 * along;
        var dp0 = Math.abs((s[o0 - 3 * step] & 0xFF) - 2 * (s[o0 - 2 * step] & 0xFF) + (s[o0 - step] & 0xFF));
        var dp3 = Math.abs((s[o3 - 3 * step] & 0xFF) - 2 * (s[o3 - 2 * step] & 0xFF) + (s[o3 - step] & 0xFF));
        var dq0 = Math.abs((s[o0 + 2 * step] & 0xFF) - 2 * (s[o0 + step] & 0xFF) + (s[o0] & 0xFF));
        var dq3 = Math.abs((s[o3 + 2 * step] & 0xFF) - 2 * (s[o3 + step] & 0xFF) + (s[o3] & 0xFF));
        var dpq0 = dp0 + dq0;
        var dpq3 = dp3 + dq3;
        if (dpq0 + dpq3 >= beta) {
            return 0;
        }
        if (decision(s, o0, step, 2 * dpq0, beta, tc) && decision(s, o3, step, 2 * dpq3, beta, tc)) {
            return HevcDsp.DEBLOCK_STRONG;
        }
        var side = (beta + (beta >> 1)) >> 3;
        return HevcDsp.DEBLOCK_NORMAL | (dp0 + dp3 < side ? HevcDsp.DEBLOCK_P1 : 0)
                | (dq0 + dq3 < side ? HevcDsp.DEBLOCK_Q1 : 0);
    }

    /**
     * Filters one luma edge segment of four lines of an 8-bit plane.
     *
     * @param s      the plane
     * @param origin the index of q0 of the first line
     * @param step   the distance between samples across the edge
     * @param along  the distance between lines along the edge
     * @param beta   {@code beta}
     * @param tc     {@code tC}
     * @param noP    whether the p side must stay unmodified
     * @param noQ    whether the q side must stay unmodified
     */
    private static void edge(byte[] s, int origin, int step, int along, int beta, int tc, boolean noP,
                             boolean noQ) {
        var o0 = origin;
        var o3 = origin + 3 * along;
        var dp0 = Math.abs((s[o0 - 3 * step] & 0xFF) - 2 * (s[o0 - 2 * step] & 0xFF) + (s[o0 - step] & 0xFF));
        var dp3 = Math.abs((s[o3 - 3 * step] & 0xFF) - 2 * (s[o3 - 2 * step] & 0xFF) + (s[o3 - step] & 0xFF));
        var dq0 = Math.abs((s[o0 + 2 * step] & 0xFF) - 2 * (s[o0 + step] & 0xFF) + (s[o0] & 0xFF));
        var dq3 = Math.abs((s[o3 + 2 * step] & 0xFF) - 2 * (s[o3 + step] & 0xFF) + (s[o3] & 0xFF));
        var dpq0 = dp0 + dq0;
        var dpq3 = dp3 + dq3;
        var dp = dp0 + dp3;
        var dq = dq0 + dq3;
        var d = dpq0 + dpq3;
        if (d >= beta) {
            return;
        }
        var strong = decision(s, o0, step, 2 * dpq0, beta, tc) && decision(s, o3, step, 2 * dpq3, beta, tc);
        var dEp = dp < ((beta + (beta >> 1)) >> 3);
        var dEq = dq < ((beta + (beta >> 1)) >> 3);
        for (var k = 0; k < 4; k++) {
            var o = origin + k * along;
            var p0 = s[o - step] & 0xFF;
            var p1 = s[o - 2 * step] & 0xFF;
            var p2 = s[o - 3 * step] & 0xFF;
            var q0 = s[o] & 0xFF;
            var q1 = s[o + step] & 0xFF;
            var q2 = s[o + 2 * step] & 0xFF;
            if (strong) {
                var p3 = s[o - 4 * step] & 0xFF;
                var q3 = s[o + 3 * step] & 0xFF;
                var tc2 = 2 * tc;
                if (!noP) {
                    s[o - step] = (byte) Math.clamp((p2 + 2 * p1 + 2 * p0 + 2 * q0 + q1 + 4) >> 3, p0 - tc2, p0 + tc2);
                    s[o - 2 * step] = (byte) Math.clamp((p2 + p1 + p0 + q0 + 2) >> 2, p1 - tc2, p1 + tc2);
                    s[o - 3 * step] = (byte) Math.clamp((2 * p3 + 3 * p2 + p1 + p0 + q0 + 4) >> 3, p2 - tc2, p2 + tc2);
                }
                if (!noQ) {
                    s[o] = (byte) Math.clamp((p1 + 2 * p0 + 2 * q0 + 2 * q1 + q2 + 4) >> 3, q0 - tc2, q0 + tc2);
                    s[o + step] = (byte) Math.clamp((p0 + q0 + q1 + q2 + 2) >> 2, q1 - tc2, q1 + tc2);
                    s[o + 2 * step] = (byte) Math.clamp((p0 + q0 + q1 + 3 * q2 + 2 * q3 + 4) >> 3, q2 - tc2, q2 + tc2);
                }
            } else {
                var delta = (9 * (q0 - p0) - 3 * (q1 - p1) + 8) >> 4;
                if (Math.abs(delta) >= tc * 10) {
                    continue;
                }
                delta = Math.clamp(delta, -tc, tc);
                if (!noP) {
                    s[o - step] = (byte) Math.clamp(p0 + delta, 0, 255);
                    if (dEp) {
                        var deltaP = Math.clamp((((p2 + p0 + 1) >> 1) - p1 + delta) >> 1, -(tc >> 1), tc >> 1);
                        s[o - 2 * step] = (byte) Math.clamp(p1 + deltaP, 0, 255);
                    }
                }
                if (!noQ) {
                    s[o] = (byte) Math.clamp(q0 - delta, 0, 255);
                    if (dEq) {
                        var deltaQ = Math.clamp((((q2 + q0 + 1) >> 1) - q1 - delta) >> 1, -(tc >> 1), tc >> 1);
                        s[o + step] = (byte) Math.clamp(q1 + deltaQ, 0, 255);
                    }
                }
            }
        }
    }

    /**
     * Returns the strong filter decision of one line of an 8-bit plane (8.7.2.5.6).
     *
     * @param s    the plane
     * @param o    the index of q0 of the line
     * @param step the distance between samples across the edge
     * @param dpq  {@code dpq}
     * @param beta {@code beta}
     * @param tc   {@code tC}
     * @return {@code dSam}
     */
    private static boolean decision(byte[] s, int o, int step, int dpq, int beta, int tc) {
        var p0 = s[o - step] & 0xFF;
        var p3 = s[o - 4 * step] & 0xFF;
        var q0 = s[o] & 0xFF;
        var q3 = s[o + 3 * step] & 0xFF;
        return dpq < (beta >> 2) && Math.abs(p3 - p0) + Math.abs(q0 - q3) < (beta >> 3)
                && Math.abs(p0 - q0) < ((5 * tc + 1) >> 1);
    }

    /**
     * Filters the chroma edges of one direction in a range of 4x4 luma block rows (8.7.2.5.5 and
     * 8.7.2.5.8).
     *
     * @param frame    the picture
     * @param pic      the coding information
     * @param vertical whether to filter the vertical edges
     * @param by0      the first 4x4 luma block row
     * @param by1      the 4x4 luma block row after the last
     */
    private void filterChroma(HevcFrame frame, PictureState pic, boolean vertical, int by0, int by1) {
        var sps = pic.sps;
        var sx = sps.chromaShiftX;
        var sy = sps.chromaShiftY;
        var stride = frame.chromaWidth;
        var cw = frame.chromaWidth;
        var ch = frame.chromaHeight;
        var w4 = pic.width4;
        var bs = vertical ? bsVertical : bsHorizontal;
        var bitDepth = sps.bitDepthChroma;
        var max = (1 << bitDepth) - 1;
        var pcmProtected = sps.pcmEnabled && sps.pcmLoopFilterDisabled;
        var log2Ctb = sps.log2CtbSize;
        var yEnd = Math.min(ch, (by1 << 2) >> sy);
        for (var yc = (by0 << 2) >> sy; yc < yEnd; yc += 4) {
            if (!vertical && ((yc & 7) != 0 || yc == 0)) {
                continue;
            }
            for (var xc = vertical ? 8 : 0; xc < cw; xc += vertical ? 8 : 4) {
                var x = xc << sx;
                var y = yc << sy;
                var index = (y >> 2) * w4 + (x >> 2);
                if (bs[index] != 2) {
                    continue;
                }
                var p = vertical ? index - 1 : index - w4;
                var header = pic.ctbHeader[(y >> log2Ctb) * sps.widthInCtbs + (x >> log2Ctb)];
                var noP = (pic.flags[p] & PictureState.BYPASS) != 0 || (pcmProtected && (pic.flags[p] & PictureState.PCM) != 0);
                var noQ = (pic.flags[index] & PictureState.BYPASS) != 0 || (pcmProtected && (pic.flags[index] & PictureState.PCM) != 0);
                var qpAverage = (pic.qp[index] + pic.qp[p] + 1) >> 1;
                for (var c = 0; c < 2; c++) {
                    var plane = c == 0 ? frame.cb : frame.cr;
                    var plane8 = c == 0 ? frame.cb8 : frame.cr8;
                    var qPi = qpAverage + (c == 0 ? header.pps.cbQpOffset : header.pps.crQpOffset);
                    int qpC;
                    if (sps.chromaArrayType == 1) {
                        qpC = qPi < 30 ? qPi : qPi > 43 ? qPi - 6 : QPC_TABLE[qPi - 30];
                    } else {
                        qpC = Math.min(qPi, 51);
                    }
                    var tcQ = Math.clamp(qpC + 2 + (header.tcOffsetDiv2 << 1), 0, 53);
                    var tc = TC[tcQ] << (bitDepth - 8);
                    if (tc == 0) {
                        continue;
                    }
                    var origin = yc * stride + xc;
                    var step = vertical ? 1 : stride;
                    var along = vertical ? stride : 1;
                    var lines = vertical ? Math.min(4, ch - yc) : Math.min(4, cw - xc);
                    if (plane8 != null) {
                        for (var k = 0; k < lines; k++) {
                            var o = origin + k * along;
                            var p0 = plane8[o - step] & 0xFF;
                            var p1 = plane8[o - 2 * step] & 0xFF;
                            var q0 = plane8[o] & 0xFF;
                            var q1 = plane8[o + step] & 0xFF;
                            var delta = Math.clamp((((q0 - p0) << 2) + p1 - q1 + 4) >> 3, -tc, tc);
                            if (!noP) {
                                plane8[o - step] = (byte) Math.clamp(p0 + delta, 0, 255);
                            }
                            if (!noQ) {
                                plane8[o] = (byte) Math.clamp(q0 - delta, 0, 255);
                            }
                        }
                        continue;
                    }
                    for (var k = 0; k < lines; k++) {
                        var o = origin + k * along;
                        var p0 = plane[o - step] & 0xFFFF;
                        var p1 = plane[o - 2 * step] & 0xFFFF;
                        var q0 = plane[o] & 0xFFFF;
                        var q1 = plane[o + step] & 0xFFFF;
                        var delta = Math.clamp((((q0 - p0) << 2) + p1 - q1 + 4) >> 3, -tc, tc);
                        if (!noP) {
                            plane[o - step] = (short) Math.clamp(p0 + delta, 0, max);
                        }
                        if (!noQ) {
                            plane[o] = (short) Math.clamp(q0 - delta, 0, max);
                        }
                    }
                }
            }
        }
    }
}
