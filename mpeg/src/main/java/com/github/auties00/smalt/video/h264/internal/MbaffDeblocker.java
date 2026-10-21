package com.github.auties00.smalt.video.h264.internal;

import java.util.Arrays;

/**
 * Applies the H.264 deblocking filter to an MBAFF frame (ITU-T H.264 8.7), whose macroblock
 * pairs are each coded as two frame or two field macroblocks.
 *
 * <p>A macroblock whose left and top neighbor pairs are of its own kind, frame or field, is
 * filtered like a macroblock of a frame or a field picture by {@link Deblocker}. The edges of the
 * other macroblocks are filtered one line of samples at a time with the boundary strength of that
 * line, since across the edges between a frame and a field macroblock pair the macroblock on the
 * other side of the edge, and with it the strength and the quantization parameter, can change
 * from one line to the next. Edges are filtered in the order of the standard: macroblocks in
 * increasing address order, and in each macroblock the vertical luma edges, the horizontal luma
 * edges, then the chroma edges likewise.
 *
 * <p>An instance filters one picture at a time and is confined to one thread while it does.
 * Arguments are not checked; values outside the documented ranges give unspecified results.
 */
final class MbaffDeblocker {
    /**
     * The strengths of the left edge per luma line of the macroblock.
     */
    private final int[] leftStrength = new int[16];

    /**
     * The macroblock holding sample {@code p0} of each luma line of the left edge.
     */
    private final int[] leftMb = new int[16];

    /**
     * The strengths of the internal vertical edges 1 to 3 per luma line, indexed
     * {@code [edge - 1][line]}.
     */
    private final int[][] innerVertical = new int[3][16];

    /**
     * The strengths of the top edge per luma column, for one or two filtering passes, indexed
     * {@code [pass][column]}.
     */
    private final int[][] topStrength = new int[2][16];

    /**
     * The macroblock holding sample {@code p0} of the top edge in each filtering pass.
     */
    private final int[] topMb = new int[2];

    /**
     * The strengths of the internal horizontal edges 1 to 3 per luma column, indexed
     * {@code [edge - 1][column]}.
     */
    private final int[][] innerHorizontal = new int[3][16];

    /**
     * The filter of the macroblocks whose edges each border a single macroblock of their kind.
     */
    private final Deblocker deblocker;

    /**
     * The picture being filtered.
     */
    private DecoderFrame frame;

    /**
     * The per-macroblock state of the picture.
     */
    private PictureState pic;

    /**
     * The first slice number of the picture; macroblocks with a lower number were not decoded in
     * it.
     */
    private int firstSlice;

    /**
     * Constructs a deblocker.
     *
     * @param deblocker the filter of the macroblocks whose edges each border a single macroblock
     * of their kind, prepared for the picture by {@link Deblocker#startPicture}
     */
    MbaffDeblocker(Deblocker deblocker) {
        this.deblocker = deblocker;
    }

    /**
     * Filters every macroblock of one row of macroblock pairs of an MBAFF frame.
     *
     * <p>Rows must be filtered in increasing order, each once both it and the row below it are
     * decoded, since the filter edits samples the intra prediction of the row below reads. The
     * filtering of a row edits its own samples and at most the last 6 luma and 2 chroma sample
     * rows of the row above.
     *
     * @param frame the picture being filtered
     * @param pic the per-macroblock state of the picture
     * @param firstSlice the number of the picture's first slice
     * @param pairY the row of macroblock pairs
     */
    void filterPairRow(DecoderFrame frame, PictureState pic, int firstSlice, int pairY) {
        this.frame = frame;
        this.pic = pic;
        this.firstSlice = firstSlice;
        var width = frame.mbWidth;
        for (var pairX = 0; pairX < width; pairX++) {
            filterMacroblock(pairX, pairY, 0);
            filterMacroblock(pairX, pairY, 1);
        }
        this.frame = null;
        this.pic = null;
    }

    /**
     * Filters the edges of one macroblock.
     *
     * @param pairX the column of its pair
     * @param pairY the row of its pair
     * @param bottom 1 for the bottom macroblock of the pair, 0 for the top one
     */
    private void filterMacroblock(int pairX, int pairY, int bottom) {
        var f = frame;
        var width = f.mbWidth;
        var mb = (2 * pairY + bottom) * width + pairX;
        var params = pic.filter[mb];
        var idc = params & 3;
        var slices = pic.sliceNum;
        var slice = slices[mb];
        if (idc == 1 || slice < firstSlice) {
            return;
        }
        var offsetA = ((params >> 2) & 0x3F) - 12;
        var offsetB = ((params >> 8) & 0x3F) - 12;
        var types = f.mbType;
        var type = types[mb];
        var field = (type & DecoderFrame.MB_FIELD) != 0;
        var transform8x8 = (type & DecoderFrame.MB_T8X8) != 0;
        var pairTop = 2 * pairY * width + pairX;
        var leftTop = pairTop - 1;
        var filterLeft = pairX > 0 && (idc == 0 ? slices[leftTop] >= firstSlice : slices[leftTop] == slice);
        boolean filterTop;
        var aboveTop = pairTop - 2 * width;
        if (!field && bottom == 1) {
            filterTop = true;
        } else if (pairY == 0) {
            filterTop = false;
        } else {
            filterTop = idc == 0 ? slices[aboveTop] >= firstSlice : slices[aboveTop] == slice;
        }
        var leftSame = !filterLeft || ((types[leftTop] & DecoderFrame.MB_FIELD) != 0) == field;
        var topSame = !filterTop || (!field && bottom == 1)
                || ((types[aboveTop] & DecoderFrame.MB_FIELD) != 0) == field;
        if (leftSame && topSame) {
            deblocker.filterMbaffMacroblock(f, pic, mb, pairX, pairY, bottom, field, filterLeft, filterTop);
            return;
        }
        var lumaStride = f.lumaStride;
        var dy = field ? 2 : 1;
        var lumaY = field ? 32 * pairY + bottom : 32 * pairY + 16 * bottom;
        var luma = f.lumaOrigin + lumaY * lumaStride + 16 * pairX;
        var limit = field ? 1 : 3;
        if (filterLeft) {
            var leftField = (types[leftTop] & DecoderFrame.MB_FIELD) != 0;
            var mixed = leftField != field;
            for (var k = 0; k < 16; k++) {
                var row = lumaY + dy * k - 32 * pairY;
                int pMb;
                int pRow;
                if (leftField) {
                    pMb = leftTop + (row & 1) * width;
                    pRow = row >> 1;
                } else {
                    pMb = leftTop + (row >> 4) * width;
                    pRow = row & 15;
                }
                leftMb[k] = pMb;
                leftStrength[k] = !mixed && (k & 3) != 0 ? leftStrength[k - 1]
                        : strength(pMb, (pRow >> 2) * 4 + 3, mb, (k >> 2) * 4, true, true, mixed, limit);
            }
            for (var k = 0; k < 16; k++) {
                var bs = leftStrength[k];
                if (bs != 0) {
                    lumaLine(luma + dy * k * lumaStride, 1, bs, pic.qp[leftMb[k]], pic.qp[mb], offsetA, offsetB);
                }
            }
        }
        for (var edge = 1; edge < 4; edge++) {
            var strengths = innerVertical[edge - 1];
            if (transform8x8 && edge != 2) {
                Arrays.fill(strengths, 0);
                continue;
            }
            for (var k = 0; k < 16; k++) {
                var block = (k >> 2) * 4 + edge;
                strengths[k] = (k & 3) != 0 ? strengths[k - 1]
                        : strength(mb, block - 1, mb, block, false, true, false, limit);
            }
            for (var k = 0; k < 16; k++) {
                var bs = strengths[k];
                if (bs != 0) {
                    lumaLine(luma + dy * k * lumaStride + 4 * edge, 1, bs, pic.qp[mb], pic.qp[mb], offsetA, offsetB);
                }
            }
        }
        var passes = 0;
        var topStep = dy * lumaStride;
        if (filterTop) {
            var aboveField = pairY > 0 && (types[aboveTop] & DecoderFrame.MB_FIELD) != 0;
            if (!field && bottom == 0 && aboveField) {
                passes = 2;
                for (var pass = 0; pass < 2; pass++) {
                    var pMb = aboveTop + pass * width;
                    topMb[pass] = pMb;
                    for (var x = 0; x < 16; x++) {
                        topStrength[pass][x] = (x & 3) != 0 ? topStrength[pass][x - 1]
                                : strength(pMb, 12 + (x >> 2), mb, x >> 2, true, false, true, limit);
                    }
                }
                topStep = 2 * lumaStride;
            } else {
                passes = 1;
                int pMb;
                int pRow;
                var mixed = false;
                if (!field && bottom == 1) {
                    pMb = pairTop;
                    pRow = 15;
                } else {
                    var row = lumaY - dy - 32 * (pairY - 1);
                    if (aboveField) {
                        pMb = aboveTop + (row & 1) * width;
                        pRow = row >> 1;
                    } else {
                        pMb = aboveTop + (row >> 4) * width;
                        pRow = row & 15;
                    }
                    mixed = aboveField != field;
                }
                topMb[0] = pMb;
                for (var x = 0; x < 16; x++) {
                    topStrength[0][x] = (x & 3) != 0 ? topStrength[0][x - 1]
                            : strength(pMb, (pRow >> 2) * 4 + (x >> 2), mb, x >> 2, true, false, mixed, limit);
                }
            }
            for (var pass = 0; pass < passes; pass++) {
                var q0 = luma + (passes == 2 ? pass * lumaStride : 0);
                var pMb = topMb[pass];
                for (var x = 0; x < 16; x++) {
                    var bs = topStrength[pass][x];
                    if (bs != 0) {
                        lumaLine(q0 + x, topStep, bs, pic.qp[pMb], pic.qp[mb], offsetA, offsetB);
                    }
                }
            }
        }
        var chroma422 = f.chromaFormat == 2;
        for (var edge = 1; edge < 4; edge++) {
            var strengths = innerHorizontal[edge - 1];
            var lumaEdge = !transform8x8 || edge == 2;
            if (!lumaEdge && !chroma422) {
                Arrays.fill(strengths, 0);
                continue;
            }
            for (var x = 0; x < 16; x++) {
                var block = edge * 4 + (x >> 2);
                strengths[x] = (x & 3) != 0 ? strengths[x - 1]
                        : strength(mb, block - 4, mb, block, false, false, false, limit);
            }
            if (!lumaEdge) {
                continue;
            }
            var q0 = luma + dy * 4 * edge * lumaStride;
            for (var x = 0; x < 16; x++) {
                var bs = strengths[x];
                if (bs != 0) {
                    lumaLine(q0 + x, dy * lumaStride, bs, pic.qp[mb], pic.qp[mb], offsetA, offsetB);
                }
            }
        }
        if (!f.hasChroma) {
            return;
        }
        var chromaStride = f.chromaStride;
        var chromaRows = f.chromaMbHeight;
        var chromaY = field ? 2 * chromaRows * pairY + bottom : 2 * chromaRows * pairY + chromaRows * bottom;
        var chroma = f.chromaOrigin + chromaY * chromaStride + 8 * pairX;
        for (var plane = 0; plane < 2; plane++) {
            var qps = plane == 0 ? pic.qpCb : pic.qpCr;
            if (filterLeft) {
                var leftField = (types[leftTop] & DecoderFrame.MB_FIELD) != 0;
                for (var k = 0; k < chromaRows; k++) {
                    var bs = leftStrength[lumaLineOfChroma(field, chroma422, k)];
                    if (bs == 0) {
                        continue;
                    }
                    var row = chromaY + dy * k - 2 * chromaRows * pairY;
                    var pMb = leftField ? leftTop + (row & 1) * width : leftTop + (row / chromaRows) * width;
                    chromaLine(plane, chroma + dy * k * chromaStride, 1, bs, qps[pMb], qps[mb], offsetA, offsetB);
                }
            }
            for (var k = 0; k < chromaRows; k++) {
                var bs = innerVertical[1][lumaLineOfChroma(field, chroma422, k)];
                if (bs != 0) {
                    chromaLine(plane, chroma + dy * k * chromaStride + 4, 1, bs, qps[mb], qps[mb], offsetA, offsetB);
                }
            }
            if (passes > 0) {
                var step = (passes == 2 ? 2 : dy) * chromaStride;
                for (var pass = 0; pass < passes; pass++) {
                    var q0 = chroma + (passes == 2 ? pass * chromaStride : 0);
                    var pMb = topMb[pass];
                    for (var x = 0; x < 8; x++) {
                        var bs = topStrength[pass][2 * x];
                        if (bs != 0) {
                            chromaLine(plane, q0 + x, step, bs, qps[pMb], qps[mb], offsetA, offsetB);
                        }
                    }
                }
            }
            for (var edge = 1; edge < chromaRows >> 2; edge++) {
                var strengths = innerHorizontal[chroma422 ? edge - 1 : 1];
                var q0 = chroma + dy * 4 * edge * chromaStride;
                for (var x = 0; x < 8; x++) {
                    var bs = strengths[2 * x];
                    if (bs != 0) {
                        chromaLine(plane, q0 + x, dy * chromaStride, bs, qps[mb], qps[mb], offsetA, offsetB);
                    }
                }
            }
        }
    }

    /**
     * Returns the luma line of a macroblock whose left or internal vertical edge strength a chroma
     * line uses: the line holding the luma sample co-sited with the chroma sample in the same
     * field (ITU-T H.264 8.7.2), which for 4:2:2 is the line of the same number.
     *
     * @param field whether the macroblock is a field macroblock
     * @param chroma422 whether the chroma is 4:2:2, with as many lines as luma
     * @param line the chroma line, 0 to 7 for 4:2:0 and 0 to 15 for 4:2:2
     * @return the luma line, 0 to 15
     */
    private static int lumaLineOfChroma(boolean field, boolean chroma422, int line) {
        if (chroma422) {
            return line;
        }
        return field ? 2 * line : 4 * (line >> 1) + (line & 1);
    }

    /**
     * Returns the boundary strength of one line of an edge (ITU-T H.264 8.7.2.1).
     *
     * @param mbP the macroblock holding sample {@code p0}
     * @param blockP the raster 4x4 index of the block holding {@code p0}
     * @param mbQ the macroblock holding sample {@code q0}
     * @param blockQ the raster 4x4 index of the block holding {@code q0}
     * @param mbEdge whether the edge is a macroblock edge
     * @param vertical whether the edge is vertical
     * @param mixed whether {@code p0} and {@code q0} lie in macroblock pairs of which one is a
     * frame and the other a field pair ({@code mixedModeEdgeFlag})
     * @param limit the vertical motion vector difference limit minus one of the current
     * macroblock: 3 for a frame macroblock, 1 for a field macroblock
     * @return the strength, 0 to 4
     */
    private int strength(int mbP, int blockP, int mbQ, int blockQ, boolean mbEdge, boolean vertical, boolean mixed,
                         int limit) {
        var types = frame.mbType;
        var typeP = types[mbP];
        var typeQ = types[mbQ];
        if (((typeP | typeQ) & DecoderFrame.MB_INTRA) != 0) {
            if (mbEdge && (vertical || ((typeP | typeQ) & DecoderFrame.MB_FIELD) == 0)) {
                return 4;
            }
            return 3;
        }
        if (((pic.nonZero[mbP] >> blockP) & 1) != 0 || ((pic.nonZero[mbQ] >> blockQ) & 1) != 0) {
            return 2;
        }
        if (mixed) {
            return 1;
        }
        return Deblocker.motion(limit, frame, mbP, blockP, mbQ, blockQ);
    }

    /**
     * Filters one line of luma samples of the frame being filtered across an edge.
     *
     * @param q0 the index of sample {@code q0}
     * @param step the index distance from {@code q0} to {@code q1}, and from {@code p0} to
     * {@code q0}
     * @param bs the boundary strength, 1 to 4
     * @param qpP the quantization parameter of the macroblock holding {@code p0}
     * @param qpQ the quantization parameter of the macroblock holding {@code q0}
     * @param offsetA the {@code FilterOffsetA}
     * @param offsetB the {@code FilterOffsetB}
     */
    private void lumaLine(int q0, int step, int bs, int qpP, int qpQ, int offsetA, int offsetB) {
        var f = frame;
        if (f.wide) {
            filterLuma(f.luma16, q0, step, bs, qpP, qpQ, offsetA, offsetB, f.bitDepthLuma - 8);
        } else {
            filterLuma(f.luma, q0, step, bs, qpP, qpQ, offsetA, offsetB);
        }
    }

    /**
     * Filters one line of chroma samples of the frame being filtered across an edge.
     *
     * @param plane 0 for Cb, 1 for Cr
     * @param q0 the index of sample {@code q0}
     * @param step the index distance from {@code q0} to {@code q1}, and from {@code p0} to
     * {@code q0}
     * @param bs the boundary strength, 1 to 4
     * @param qpP the chroma quantization parameter of the macroblock holding {@code p0}
     * @param qpQ the chroma quantization parameter of the macroblock holding {@code q0}
     * @param offsetA the {@code FilterOffsetA}
     * @param offsetB the {@code FilterOffsetB}
     */
    private void chromaLine(int plane, int q0, int step, int bs, int qpP, int qpQ, int offsetA, int offsetB) {
        var f = frame;
        if (f.wide) {
            filterChroma(plane == 0 ? f.cb16 : f.cr16, q0, step, bs, qpP, qpQ, offsetA, offsetB,
                    f.bitDepthChroma - 8);
        } else {
            filterChroma(plane == 0 ? f.cb : f.cr, q0, step, bs, qpP, qpQ, offsetA, offsetB);
        }
    }

    /**
     * Filters one line of 16-bit luma samples across an edge, with the thresholds and
     * {@code tC0} scaled to the bit depth (ITU-T H.264 8.7.2.2 to 8.7.2.4).
     *
     * @param plane the luma plane, edited in place
     * @param q0 the index of sample {@code q0}
     * @param step the index distance from {@code q0} to {@code q1}, and from {@code p0} to
     * {@code q0}
     * @param bs the boundary strength, 1 to 4
     * @param qpP the quantization parameter of the macroblock holding {@code p0}
     * @param qpQ the quantization parameter of the macroblock holding {@code q0}
     * @param offsetA the {@code FilterOffsetA}
     * @param offsetB the {@code FilterOffsetB}
     * @param shift the luma bit depth minus 8
     */
    private static void filterLuma(short[] plane, int q0, int step, int bs, int qpP, int qpQ, int offsetA,
                                   int offsetB, int shift) {
        var qpAv = (qpP + qpQ + 1) >> 1;
        var indexA = Math.clamp(qpAv + offsetA, 0, 51);
        var alpha = (H264Tables.ALPHA[indexA] & 0xFF) << shift;
        var beta = (H264Tables.BETA[Math.clamp(qpAv + offsetB, 0, 51)] & 0xFF) << shift;
        if (bs < 4) {
            ScalarH264WideDsp.filterLumaNormal(plane, q0, step, alpha, beta, H264Tables.TC0[indexA * 4 + bs] << shift,
                    (1 << (8 + shift)) - 1);
        } else {
            ScalarH264WideDsp.filterLumaStrong(plane, q0, step, alpha, beta);
        }
    }

    /**
     * Filters one line of 16-bit chroma samples across an edge, with the thresholds and
     * {@code tC0} scaled to the bit depth (ITU-T H.264 8.7.2.2 to 8.7.2.4 with
     * {@code chromaStyleFilteringFlag} set).
     *
     * @param plane the chroma plane, edited in place
     * @param q0 the index of sample {@code q0}
     * @param step the index distance from {@code q0} to {@code q1}, and from {@code p0} to
     * {@code q0}
     * @param bs the boundary strength, 1 to 4
     * @param qpP the chroma quantization parameter of the macroblock holding {@code p0}
     * @param qpQ the chroma quantization parameter of the macroblock holding {@code q0}
     * @param offsetA the {@code FilterOffsetA}
     * @param offsetB the {@code FilterOffsetB}
     * @param shift the chroma bit depth minus 8
     */
    private static void filterChroma(short[] plane, int q0, int step, int bs, int qpP, int qpQ, int offsetA,
                                     int offsetB, int shift) {
        var qpAv = (qpP + qpQ + 1) >> 1;
        var indexA = Math.clamp(qpAv + offsetA, 0, 51);
        var alpha = (H264Tables.ALPHA[indexA] & 0xFF) << shift;
        var beta = (H264Tables.BETA[Math.clamp(qpAv + offsetB, 0, 51)] & 0xFF) << shift;
        if (bs < 4) {
            ScalarH264WideDsp.filterChromaNormal(plane, q0, step, alpha, beta, H264Tables.TC0[indexA * 4 + bs] << shift,
                    (1 << (8 + shift)) - 1);
        } else {
            ScalarH264WideDsp.filterChromaStrong(plane, q0, step, alpha, beta);
        }
    }

    /**
     * Filters one line of luma samples across an edge (ITU-T H.264 8.7.2.2 to 8.7.2.4).
     *
     * @param plane the luma plane, edited in place
     * @param q0 the index of sample {@code q0}
     * @param step the index distance from {@code q0} to {@code q1}, and from {@code p0} to
     * {@code q0}
     * @param bs the boundary strength, 1 to 4
     * @param qpP the quantization parameter of the macroblock holding {@code p0}
     * @param qpQ the quantization parameter of the macroblock holding {@code q0}
     * @param offsetA the {@code FilterOffsetA}
     * @param offsetB the {@code FilterOffsetB}
     */
    private static void filterLuma(byte[] plane, int q0, int step, int bs, int qpP, int qpQ, int offsetA,
                                   int offsetB) {
        var qpAv = (qpP + qpQ + 1) >> 1;
        var indexA = Math.clamp(qpAv + offsetA, 0, 51);
        var alpha = H264Tables.ALPHA[indexA] & 0xFF;
        var beta = H264Tables.BETA[Math.clamp(qpAv + offsetB, 0, 51)] & 0xFF;
        var p0 = plane[q0 - step] & 0xFF;
        var p1 = plane[q0 - 2 * step] & 0xFF;
        var p2 = plane[q0 - 3 * step] & 0xFF;
        var q0v = plane[q0] & 0xFF;
        var q1 = plane[q0 + step] & 0xFF;
        var q2 = plane[q0 + 2 * step] & 0xFF;
        if (Math.abs(p0 - q0v) >= alpha || Math.abs(p1 - p0) >= beta || Math.abs(q1 - q0v) >= beta) {
            return;
        }
        var ap = Math.abs(p2 - p0);
        var aq = Math.abs(q2 - q0v);
        if (bs < 4) {
            var tc0 = H264Tables.TC0[indexA * 4 + bs];
            var tc = tc0 + (ap < beta ? 1 : 0) + (aq < beta ? 1 : 0);
            var delta = Math.clamp((((q0v - p0) << 2) + (p1 - q1) + 4) >> 3, -tc, tc);
            plane[q0 - step] = (byte) Math.clamp(p0 + delta, 0, 255);
            plane[q0] = (byte) Math.clamp(q0v - delta, 0, 255);
            if (ap < beta) {
                plane[q0 - 2 * step] = (byte) (p1 + Math.clamp((p2 + ((p0 + q0v + 1) >> 1) - (p1 << 1)) >> 1, -tc0, tc0));
            }
            if (aq < beta) {
                plane[q0 + step] = (byte) (q1 + Math.clamp((q2 + ((p0 + q0v + 1) >> 1) - (q1 << 1)) >> 1, -tc0, tc0));
            }
            return;
        }
        var p3 = plane[q0 - 4 * step] & 0xFF;
        var q3 = plane[q0 + 3 * step] & 0xFF;
        var strong = Math.abs(p0 - q0v) < ((alpha >> 2) + 2);
        if (ap < beta && strong) {
            plane[q0 - step] = (byte) ((p2 + 2 * p1 + 2 * p0 + 2 * q0v + q1 + 4) >> 3);
            plane[q0 - 2 * step] = (byte) ((p2 + p1 + p0 + q0v + 2) >> 2);
            plane[q0 - 3 * step] = (byte) ((2 * p3 + 3 * p2 + p1 + p0 + q0v + 4) >> 3);
        } else {
            plane[q0 - step] = (byte) ((2 * p1 + p0 + q1 + 2) >> 2);
        }
        if (aq < beta && strong) {
            plane[q0] = (byte) ((p1 + 2 * p0 + 2 * q0v + 2 * q1 + q2 + 4) >> 3);
            plane[q0 + step] = (byte) ((p0 + q0v + q1 + q2 + 2) >> 2);
            plane[q0 + 2 * step] = (byte) ((2 * q3 + 3 * q2 + q1 + q0v + p0 + 4) >> 3);
        } else {
            plane[q0] = (byte) ((2 * q1 + q0v + p1 + 2) >> 2);
        }
    }

    /**
     * Filters one line of chroma samples across an edge (ITU-T H.264 8.7.2.2 to 8.7.2.4 with
     * {@code chromaStyleFilteringFlag} set).
     *
     * @param plane the chroma plane, edited in place
     * @param q0 the index of sample {@code q0}
     * @param step the index distance from {@code q0} to {@code q1}, and from {@code p0} to
     * {@code q0}
     * @param bs the boundary strength, 1 to 4
     * @param qpP the chroma quantization parameter of the macroblock holding {@code p0}
     * @param qpQ the chroma quantization parameter of the macroblock holding {@code q0}
     * @param offsetA the {@code FilterOffsetA}
     * @param offsetB the {@code FilterOffsetB}
     */
    private static void filterChroma(byte[] plane, int q0, int step, int bs, int qpP, int qpQ, int offsetA,
                                     int offsetB) {
        var qpAv = (qpP + qpQ + 1) >> 1;
        var indexA = Math.clamp(qpAv + offsetA, 0, 51);
        var alpha = H264Tables.ALPHA[indexA] & 0xFF;
        var beta = H264Tables.BETA[Math.clamp(qpAv + offsetB, 0, 51)] & 0xFF;
        var p0 = plane[q0 - step] & 0xFF;
        var p1 = plane[q0 - 2 * step] & 0xFF;
        var q0v = plane[q0] & 0xFF;
        var q1 = plane[q0 + step] & 0xFF;
        if (Math.abs(p0 - q0v) >= alpha || Math.abs(p1 - p0) >= beta || Math.abs(q1 - q0v) >= beta) {
            return;
        }
        if (bs < 4) {
            var tc = H264Tables.TC0[indexA * 4 + bs] + 1;
            var delta = Math.clamp((((q0v - p0) << 2) + (p1 - q1) + 4) >> 3, -tc, tc);
            plane[q0 - step] = (byte) Math.clamp(p0 + delta, 0, 255);
            plane[q0] = (byte) Math.clamp(q0v - delta, 0, 255);
            return;
        }
        plane[q0 - step] = (byte) ((2 * p1 + p0 + q1 + 2) >> 2);
        plane[q0] = (byte) ((2 * q1 + q0v + p1 + 2) >> 2);
    }
}
