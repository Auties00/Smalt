/* Ports libvpx 1.17.0 (vp9/encoder/vp9_mcomp.c). Copyright (c) 2010, The WebM Project authors; BSD 3-Clause
   License. Modified for Smalt. */
package com.github.auties00.smalt.video.vp9.internal;

/**
 * Searches the motion vectors of the real-time VP9 encoder: the integral projection estimate of
 * a superblock, the full-pixel searches and the sub-pixel refinement of a block.
 *
 * <p>Motion vectors are handled as separate row and column components; full-pixel searches work
 * in whole samples and the sub-pixel refinement in eighth samples. The cost of a vector is
 * modeled from the frame's motion vector probabilities, and every search keeps within the limits
 * of the block, so the prediction never reads further than the reference picture's border.
 *
 * @implNote This implementation ports the real-time searches of libvpx's
 * {@code vp9/encoder/vp9_mcomp.c} ({@code vp9_int_pro_motion_estimation},
 * {@code vp9_full_pixel_search} with {@code full_pixel_diamond},
 * {@code vp9_diamond_search_sad_c}, {@code vp9_refining_search_sad},
 * {@code fast_hex_search}, {@code fast_dia_search}, {@code vp9_pattern_search},
 * {@code vp9_pattern_search_sad}, {@code vp9_find_best_sub_pixel_tree} and
 * {@code vp9_find_best_sub_pixel_tree_pruned_evenmore}). The sub-pixel search
 * uses the bilinear variance, as libvpx does in real time
 * ({@code USE_2_TAPS}); the exhaustive mesh search is not needed in real time.
 */
final class Vp9RtMotion {
    /**
     * The row offsets of the four neighbors in the order up, left, right, down.
     */
    private static final int[] CROSS_ROWS = {-1, 0, 0, 1};

    /**
     * The column offsets of the four neighbors in the order up, left, right, down.
     */
    private static final int[] CROSS_COLS = {0, -1, 1, 0};

    /**
     * The row offsets of the four neighbors in the order left, down, right, up.
     */
    private static final int[] RING_ROWS = {0, 1, 0, -1};

    /**
     * The column offsets of the four neighbors in the order left, down, right, up.
     */
    private static final int[] RING_COLS = {-1, 0, 1, 0};

    /**
     * The largest full-pixel vector component ({@code MAX_FULL_PEL_VAL}).
     */
    static final int MAX_FULL_PEL_VAL = (1 << 10) - 1;

    /**
     * The number of search scales ({@code MAX_MVSEARCH_STEPS}).
     */
    static final int MAX_MVSEARCH_STEPS = 11;

    /**
     * The smallest eighth-pixel vector component plus one ({@code MV_LOW + 1}).
     */
    private static final int MV_LOW = -(1 << 14);

    /**
     * The largest eighth-pixel vector component minus one ({@code MV_UPP - 1}).
     */
    private static final int MV_UPP = 1 << 14;

    /**
     * The weight of the vector cost in the rate ({@code MV_COST_WEIGHT}).
     */
    static final int MV_COST_WEIGHT = 108;

    /**
     * The value a search returns when no candidate was valid.
     */
    static final int INT_MAX = Integer.MAX_VALUE;

    /**
     * The row offsets of the eight sites per scale of the n-step search
     * ({@code vp9_init3smotion_compensation}).
     */
    private static final int[] SS_ROW = new int[8 * MAX_MVSEARCH_STEPS];

    /**
     * The column offsets of the eight sites per scale of the n-step search.
     */
    private static final int[] SS_COL = new int[8 * MAX_MVSEARCH_STEPS];

    static {
        var count = 0;
        for (var len = 1 << (MAX_MVSEARCH_STEPS - 1); len > 0; len /= 2) {
            int[][] sites = {{-len, 0}, {len, 0}, {0, -len}, {0, len}, {-len, -len}, {-len, len}, {len, -len},
                    {len, len}};
            for (var site : sites) {
                SS_ROW[count] = site[0];
                SS_COL[count] = site[1];
                count++;
            }
        }
    }

    /**
     * The number of candidates of each scale of the hexagon search.
     */
    private static final int[] HEX_COUNTS = {8, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6};

    /**
     * The candidates of each scale of the hexagon search, as row and column pairs.
     */
    private static final int[][] HEX = hexCandidates();

    /**
     * The number of candidates of each scale of the big diamond search.
     */
    private static final int[] DIA_COUNTS = {4, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8};

    /**
     * The candidates of each scale of the big diamond search, as row and column pairs.
     */
    private static final int[][] DIA = diamondCandidates();

    /**
     * The sub-pixel steps of the tree search: left, right, up, down at half, quarter and eighth
     * precision, as row and column pairs ({@code search_step_table}).
     */
    private static final int[] STEP_TABLE = {
            0, -4, 0, 4, -4, 0, 4, 0,
            0, -2, 0, 2, -2, 0, 2, 0,
            0, -1, 0, 1, -1, 0, 1, 0
    };

    /**
     * The coder owning this search.
     */
    private final Vp9RtCoder coder;

    /**
     * The integral projections of the reference rows.
     */
    private final short[] hbuf = new short[128];

    /**
     * The integral projections of the reference columns.
     */
    private final short[] vbuf = new short[128];

    /**
     * The integral projections of the source rows.
     */
    private final short[] srcHbuf = new short[64];

    /**
     * The integral projections of the source columns.
     */
    private final short[] srcVbuf = new short[64];

    /**
     * The four sums of a batched search.
     */
    private final int[] sads = new int[4];

    /**
     * The costs of the four directions and the diagonal of a sub-pixel step.
     */
    private final long[] costArray = new long[5];

    /**
     * The source plane of the search.
     */
    private byte[] src;

    /**
     * The index of the block in the source.
     */
    private int srcOff;

    /**
     * The source row stride.
     */
    private int srcStride;

    /**
     * The reference plane of the search.
     */
    private byte[] ref;

    /**
     * The index of the block's position in the reference.
     */
    private int refBase;

    /**
     * The reference row stride.
     */
    private int refStride;

    /**
     * The block width.
     */
    private int bw;

    /**
     * The block height.
     */
    private int bh;

    /**
     * The smallest full-pixel column of the search.
     */
    int colMin;

    /**
     * The largest full-pixel column of the search.
     */
    int colMax;

    /**
     * The smallest full-pixel row of the search.
     */
    int rowMin;

    /**
     * The largest full-pixel row of the search.
     */
    int rowMax;

    /**
     * The row of the vector a search found.
     */
    int bestRow;

    /**
     * The column of the vector a search found.
     */
    int bestCol;

    /**
     * The distortion of the vector the sub-pixel search found.
     */
    int distortion;

    /**
     * The sum of squared errors of the vector the sub-pixel search found.
     */
    long sse1;

    /**
     * The integer cost list around the best full-pixel vector, or {@code null} when the
     * sub-pixel search does not use it.
     */
    final int[] costList = new int[5];

    /**
     * Creates a search.
     *
     * @param coder the superblock coder whose block the search serves
     */
    Vp9RtMotion(Vp9RtCoder coder) {
        this.coder = coder;
    }

    /**
     * Builds the hexagon candidates.
     *
     * @return the candidates of each scale
     */
    private static int[][] hexCandidates() {
        var result = new int[MAX_MVSEARCH_STEPS][];
        result[0] = new int[]{-1, -1, 0, -1, 1, -1, 1, 0, 1, 1, 0, 1, -1, 1, -1, 0};
        for (var s = 1; s < MAX_MVSEARCH_STEPS; s++) {
            var a = 1 << (s - 1);
            var b = 1 << s;
            result[s] = new int[]{-a, -b, a, -b, b, 0, a, b, -a, b, -b, 0};
        }
        return result;
    }

    /**
     * Builds the big diamond candidates.
     *
     * @return the candidates of each scale
     */
    private static int[][] diamondCandidates() {
        var result = new int[MAX_MVSEARCH_STEPS][];
        result[0] = new int[]{0, -1, 1, 0, 0, 1, -1, 0};
        for (var s = 1; s < MAX_MVSEARCH_STEPS; s++) {
            var a = 1 << (s - 1);
            var b = 1 << s;
            result[s] = new int[]{-a, -a, 0, -b, a, -a, b, 0, a, a, 0, b, -a, a, -b, 0};
        }
        return result;
    }

    /**
     * Points the search at the block the coder is on and at a reference.
     *
     * @param bsize the block size
     * @param reference the reference picture
     */
    void setup(int bsize, Vp9EncPicture reference) {
        var c = coder;
        var source = c.enc.source;
        src = source.planes[0];
        srcOff = c.srcOff[0];
        srcStride = source.strides[0];
        ref = reference.planes[0];
        refStride = reference.strides[0];
        refBase = reference.offset(0, c.miCol * 8, c.miRow * 8);
        bw = Vp9RtCoder.NUM_4X4_WIDE[bsize] * 4;
        bh = Vp9RtCoder.NUM_4X4_HIGH[bsize] * 4;
        colMin = c.mvColMin;
        colMax = c.mvColMax;
        rowMin = c.mvRowMin;
        rowMax = c.mvRowMax;
    }

    /**
     * Returns the sum of absolute differences of the block at a full-pixel vector.
     *
     * @param row the vector row
     * @param col the vector column
     * @return the sum
     */
    private int sadAt(int row, int col) {
        return coder.edsp.sad(src, srcOff, srcStride, ref, refBase + row * refStride + col, refStride, bw, bh);
    }

    /**
     * Returns the variance of the block at a full-pixel vector.
     *
     * @param row the vector row
     * @param col the vector column
     * @return the variance
     */
    private int varianceAt(int row, int col) {
        var packed = coder.edsp.variance(ref, refBase + row * refStride + col, refStride, src, srcOff, srcStride, bw,
                bh);
        return (int) Vp9EncDsp.variance(packed, log2(bw * bh));
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
     * Returns the motion vector cost of a difference ({@code mv_cost}).
     *
     * @param row the row difference
     * @param col the column difference
     * @return the cost
     */
    private int mvCost(int row, int col) {
        var enc = coder.enc;
        return enc.mvJointCost[Vp9EncMv.joint(row, col)] + enc.mvCostRow[Vp9EncMv.MV_MAX + row]
                + enc.mvCostCol[Vp9EncMv.MV_MAX + col];
    }

    /**
     * Returns the rate of a vector against a reference vector, in cost units
     * ({@code vp9_mv_bit_cost} with {@link #MV_COST_WEIGHT}).
     *
     * @param row the vector row
     * @param col the vector column
     * @param refRow the reference vector row
     * @param refCol the reference vector column
     * @return the rate
     */
    int mvBitCost(int row, int col, int refRow, int refCol) {
        return (mvCost(row - refRow, col - refCol) * MV_COST_WEIGHT + 64) >> 7;
    }

    /**
     * Returns the error-scaled cost of an eighth-pixel vector ({@code mv_err_cost}).
     *
     * @param row the vector row
     * @param col the vector column
     * @param refRow the reference vector row
     * @param refCol the reference vector column
     * @return the cost
     */
    private int mvErrCost(int row, int col, int refRow, int refCol) {
        var cost = (long) mvCost(row - refRow, col - refCol) * coder.errorPerBit;
        return (int) ((cost + (1 << 13)) >> 14);
    }

    /**
     * Returns the sum-of-differences-scaled cost of a full-pixel vector ({@code mvsad_err_cost}).
     *
     * @param row the vector row
     * @param col the vector column
     * @param refRow the reference vector row
     * @param refCol the reference vector column
     * @param sadPerBit the weight
     * @return the cost
     */
    private int mvsadErrCost(int row, int col, int refRow, int refCol, int sadPerBit) {
        var dr = row - refRow;
        var dc = col - refCol;
        var cost = Vp9EncMv.JOINT_SAD_COST[Vp9EncMv.joint(dr, dc)] + Vp9EncMv.SAD_COST[Vp9EncMv.MV_MAX + dr]
                + Vp9EncMv.SAD_COST[Vp9EncMv.MV_MAX + dc];
        return (int) (((long) cost * sadPerBit + 256) >> 9);
    }

    /**
     * Narrows the full-pixel limits to the vectors whose distance from a reference vector the
     * bitstream can code ({@code vp9_set_mv_search_range}).
     *
     * @param refRow the reference vector row in eighth pixels
     * @param refCol the reference vector column in eighth pixels
     */
    void setSearchRange(int refRow, int refCol) {
        var cMin = (refCol >> 3) - MAX_FULL_PEL_VAL + ((refCol & 7) != 0 ? 1 : 0);
        var rMin = (refRow >> 3) - MAX_FULL_PEL_VAL + ((refRow & 7) != 0 ? 1 : 0);
        var cMax = (refCol >> 3) + MAX_FULL_PEL_VAL;
        var rMax = (refRow >> 3) + MAX_FULL_PEL_VAL;
        cMin = Math.max(cMin, (MV_LOW >> 3) + 1);
        rMin = Math.max(rMin, (MV_LOW >> 3) + 1);
        cMax = Math.min(cMax, (MV_UPP >> 3) - 1);
        rMax = Math.min(rMax, (MV_UPP >> 3) - 1);
        colMin = Math.max(colMin, cMin);
        colMax = Math.min(colMax, cMax);
        rowMin = Math.max(rowMin, rMin);
        rowMax = Math.min(rowMax, rMax);
    }

    /**
     * Estimates the motion of a block from the integral projections of its rows and columns, and
     * stores the eighth-pixel vector in the coder's mode information
     * ({@code vp9_int_pro_motion_estimation}).
     *
     * @param bsize the block size, at least 16x16
     * @param reference the reference picture
     * @param refMv the packed vector the sub-pixel limits are centered on
     * @return the sum of absolute differences at the found vector
     */
    int intProMotionEstimation(int bsize, Vp9EncPicture reference, int refMv) {
        setup(bsize, reference);
        var edsp = coder.edsp;
        var bwl = Vp9RtCoder.B_WIDTH_LOG2[bsize];
        var bhl = Vp9RtCoder.B_HEIGHT_LOG2[bsize];
        var searchWidth = bw << 1;
        var searchHeight = bh << 1;
        var normFactor = 3 + (bw >> 5);
        var refOff = refBase - (bw >> 1);
        for (var idx = 0; idx < searchWidth; idx += 16) {
            edsp.intProRow(hbuf, idx, ref, refOff, refStride, bh);
            refOff += 16;
        }
        refOff = refBase - (bh >> 1) * refStride;
        for (var idx = 0; idx < searchHeight; idx++) {
            vbuf[idx] = (short) (edsp.intProCol(ref, refOff, bw) >> normFactor);
            refOff += refStride;
        }
        for (var idx = 0; idx < bw; idx += 16) {
            edsp.intProRow(srcHbuf, idx, src, srcOff + idx, srcStride, bh);
        }
        var s = srcOff;
        for (var idx = 0; idx < bh; idx++) {
            srcVbuf[idx] = (short) (edsp.intProCol(src, s, bw) >> normFactor);
            s += srcStride;
        }
        var col = vectorMatch(hbuf, srcHbuf, bwl);
        var row = vectorMatch(vbuf, srcVbuf, bhl);
        var thisRow = row;
        var thisCol = col;
        var bestSad = sadAt(thisRow, thisCol);
        var center = refBase + thisRow * refStride + thisCol;
        edsp.sad4(src, srcOff, srcStride, ref, center - refStride, center - 1, center + 1, center + refStride,
                refStride, bw, bh, sads);
        for (var idx = 0; idx < 4; idx++) {
            if (Integer.compareUnsigned(sads[idx], bestSad) < 0) {
                bestSad = sads[idx];
                row = CROSS_ROWS[idx] + thisRow;
                col = CROSS_COLS[idx] + thisCol;
            }
        }
        thisRow += Integer.compareUnsigned(sads[0], sads[3]) < 0 ? -1 : 1;
        thisCol += Integer.compareUnsigned(sads[1], sads[2]) < 0 ? -1 : 1;
        var tmpSad = sadAt(thisRow, thisCol);
        if (Integer.compareUnsigned(bestSad, tmpSad) > 0) {
            row = thisRow;
            col = thisCol;
            bestSad = tmpSad;
        }
        row *= 8;
        col *= 8;
        var refRow = refMv >> 16;
        var refCol = (short) refMv;
        var cMin = Math.max(Math.max(colMin * 8, refCol - MAX_FULL_PEL_VAL * 8), MV_LOW + 1);
        var cMax = Math.min(Math.min(colMax * 8, refCol + MAX_FULL_PEL_VAL * 8), MV_UPP - 1);
        var rMin = Math.max(Math.max(rowMin * 8, refRow - MAX_FULL_PEL_VAL * 8), MV_LOW + 1);
        var rMax = Math.min(Math.min(rowMax * 8, refRow + MAX_FULL_PEL_VAL * 8), MV_UPP - 1);
        row = Math.clamp(row, rMin, rMax);
        col = Math.clamp(col, cMin, cMax);
        coder.mi.mv0 = ModeInfo.packMv(row, col);
        return bestSad;
    }

    /**
     * Finds the displacement of the best match of a projection in a reference projection twice
     * as long ({@code vector_match}).
     *
     * @param refProj the reference projection
     * @param srcProj the source projection
     * @param bwl the log2 of the source length divided by 4
     * @return the displacement, from minus half to plus half the length
     */
    private int vectorMatch(short[] refProj, short[] srcProj, int bwl) {
        var edsp = coder.edsp;
        var bestSad = Integer.MAX_VALUE;
        var offset = 0;
        var width = 4 << bwl;
        for (var d = 0; d <= width; d += 16) {
            var thisSad = edsp.vectorVar(refProj, d, srcProj, 0, bwl);
            if (thisSad < bestSad) {
                bestSad = thisSad;
                offset = d;
            }
        }
        var center = offset;
        for (var step = 8; step >= 1; step >>= 1) {
            for (var d = -step; d <= step; d += 2 * step) {
                var pos = offset + d;
                if (pos < 0 || pos > width) {
                    continue;
                }
                var thisSad = edsp.vectorVar(refProj, pos, srcProj, 0, bwl);
                if (thisSad < bestSad) {
                    bestSad = thisSad;
                    center = pos;
                }
            }
            offset = center;
        }
        return center - (width >> 1);
    }

    /**
     * Runs the full-pixel search of the speed's method, leaving the vector in {@link #bestRow}
     * and {@link #bestCol} ({@code vp9_full_pixel_search}).
     *
     * @param mvpRow the full-pixel start row
     * @param mvpCol the full-pixel start column
     * @param stepParam the first scale to search
     * @param method the search method
     * @param sadPerBit the vector cost weight
     * @param withCosts whether to fill {@link #costList}
     * @param centerRow the eighth-pixel row vector costs are measured against
     * @param centerCol the eighth-pixel column vector costs are measured against
     * @return the best cost
     */
    int fullPixelSearch(int mvpRow, int mvpCol, int stepParam, int method, int sadPerBit,
                        boolean withCosts, int centerRow, int centerCol) {
        if (withCosts) {
            java.util.Arrays.fill(costList, INT_MAX);
        }
        if (method == Vp9RtSpeed.FAST_DIAMOND) {
            return patternSearch(mvpRow, mvpCol, Math.max(MAX_MVSEARCH_STEPS - 2, stepParam), sadPerBit, withCosts,
                    centerRow, centerCol, DIA_COUNTS, DIA, true);
        }
        if (method == Vp9RtSpeed.FAST_HEX) {
            return patternSearch(mvpRow, mvpCol, Math.max(MAX_MVSEARCH_STEPS - 2, stepParam), sadPerBit, withCosts,
                    centerRow, centerCol, HEX_COUNTS, HEX, false);
        }
        return fullPixelDiamond(mvpRow, mvpCol, stepParam, sadPerBit, MAX_MVSEARCH_STEPS - 1 - stepParam, withCosts,
                centerRow, centerCol);
    }

    /**
     * Returns whether a full-pixel vector lies inside the limits ({@code is_mv_in}).
     *
     * @param row the full-pixel row
     * @param col the column
     * @return whether it is inside
     */
    private boolean inside(int row, int col) {
        return col >= colMin && col <= colMax && row >= rowMin && row <= rowMax;
    }

    /**
     * Returns whether every vector within a range of a vector lies inside the limits
     * ({@code check_bounds}).
     *
     * @param row the full-pixel row
     * @param col the column
     * @param range the distance from the limits the vector must keep, in full pixels
     * @return whether all are inside
     */
    private boolean inBounds(int row, int col, int range) {
        return row - range >= rowMin && row + range <= rowMax && col - range >= colMin && col + range <= colMax;
    }

    /**
     * Runs the n-step diamond search, its repetitions at finer starting scales and the final
     * refinement ({@code full_pixel_diamond}).
     *
     * @param mvpRow the start row
     * @param mvpCol the start column
     * @param stepParam the first scale
     * @param sadPerBit the vector cost weight
     * @param furtherSteps the number of repetitions allowed
     * @param withCosts whether to fill {@link #costList}
     * @param centerRow the eighth-pixel center row
     * @param centerCol the eighth-pixel center column
     * @return the best variance plus vector cost
     */
    private int fullPixelDiamond(int mvpRow, int mvpCol, int stepParam, int sadPerBit, int furtherSteps,
                                 boolean withCosts, int centerRow, int centerCol) {
        mvpCol = Math.clamp(mvpCol, colMin, colMax);
        mvpRow = Math.clamp(mvpRow, rowMin, rowMax);
        var edsp = coder.edsp;
        var predOff = refBase + mvpRow * refStride + mvpCol;
        var even = edsp.sadSkip(src, srcOff, srcStride, ref, predOff, refStride, bw, bh);
        var odd = edsp.sadSkip(src, srcOff + srcStride, srcStride, ref, predOff + refStride, refStride, bw, bh);
        var startSad = (even + odd) >> 1;
        startSad += mvsadErrCost(mvpRow, mvpCol, centerRow >> 3, centerCol >> 3, sadPerBit);
        var doRefine = true;
        var bestSme = diamondSearch(mvpRow, mvpCol, startSad, stepParam, sadPerBit, centerRow, centerCol);
        var n = num00;
        if (bestSme < INT_MAX) {
            bestSme = mvpredVar(searchRow, searchCol, centerRow, centerCol);
        }
        var dstRow = searchRow;
        var dstCol = searchCol;
        if (n > furtherSteps) {
            doRefine = false;
        }
        var pendingNum00 = 0;
        while (n < furtherSteps) {
            n++;
            if (pendingNum00 > 0) {
                pendingNum00--;
            } else {
                var thisSme = diamondSearch(mvpRow, mvpCol, startSad, stepParam + n, sadPerBit, centerRow, centerCol);
                pendingNum00 = num00;
                if (thisSme < INT_MAX) {
                    thisSme = mvpredVar(searchRow, searchCol, centerRow, centerCol);
                }
                if (pendingNum00 > furtherSteps - n) {
                    doRefine = false;
                }
                if (thisSme < bestSme) {
                    bestSme = thisSme;
                    dstRow = searchRow;
                    dstCol = searchCol;
                }
            }
        }
        if (doRefine) {
            var thisSme = refiningSearch(dstRow, dstCol, sadPerBit, 8, centerRow, centerCol);
            if (thisSme < INT_MAX) {
                thisSme = mvpredVar(searchRow, searchCol, centerRow, centerCol);
            }
            if (thisSme < bestSme) {
                bestSme = thisSme;
                dstRow = searchRow;
                dstCol = searchCol;
            }
        }
        bestRow = dstRow;
        bestCol = dstCol;
        if (withCosts) {
            calcIntCostList(centerRow, centerCol, sadPerBit, dstRow, dstCol);
        }
        return bestSme;
    }

    /**
     * The row a diamond or refining search ended on.
     */
    private int searchRow;

    /**
     * The column a diamond or refining search ended on.
     */
    private int searchCol;

    /**
     * The number of scales at which the last diamond search stayed on its start.
     */
    private int num00;

    /**
     * Runs one n-step diamond search from a start vector ({@code vp9_diamond_search_sad_c}).
     *
     * @param startRow the start row
     * @param startCol the start column
     * @param startSad the cost of the start
     * @param searchParam the first scale
     * @param sadPerBit the vector cost weight
     * @param centerRow the eighth-pixel center row
     * @param centerCol the eighth-pixel center column
     * @return the best cost
     */
    private int diamondSearch(int startRow, int startCol, int startSad, int searchParam, int sadPerBit,
                              int centerRow, int centerCol) {
        var fRow = centerRow >> 3;
        var fCol = centerCol >> 3;
        var bestSad = startSad;
        var bestSite = -1;
        var lastSite = -1;
        var br = startRow;
        var bc = startCol;
        num00 = 0;
        var i = searchParam * 8;
        var totSteps = MAX_MVSEARCH_STEPS - searchParam;
        var edsp = coder.edsp;
        for (var step = 0; step < totSteps; step++) {
            var allIn = br + SS_ROW[i] > rowMin && br + SS_ROW[i + 1] < rowMax && bc + SS_COL[i + 2] > colMin
                    && bc + SS_COL[i + 3] < colMax;
            if (allIn) {
                var base = refBase + br * refStride + bc;
                for (var j = 0; j < 8; j += 4) {
                    edsp.sad4(src, srcOff, srcStride, ref, base + SS_ROW[i] * refStride + SS_COL[i],
                            base + SS_ROW[i + 1] * refStride + SS_COL[i + 1],
                            base + SS_ROW[i + 2] * refStride + SS_COL[i + 2],
                            base + SS_ROW[i + 3] * refStride + SS_COL[i + 3], refStride, bw, bh, sads);
                    for (var t = 0; t < 4; t++, i++) {
                        var sad = sads[t];
                        if (Integer.compareUnsigned(sad, bestSad) < 0) {
                            sad += mvsadErrCost(br + SS_ROW[i], bc + SS_COL[i], fRow, fCol, sadPerBit);
                            if (Integer.compareUnsigned(sad, bestSad) < 0) {
                                bestSad = sad;
                                bestSite = i;
                            }
                        }
                    }
                }
            } else {
                for (var j = 0; j < 8; j++, i++) {
                    var r = br + SS_ROW[i];
                    var c = bc + SS_COL[i];
                    if (inside(r, c)) {
                        var sad = sadAt(r, c);
                        if (Integer.compareUnsigned(sad, bestSad) < 0) {
                            sad += mvsadErrCost(r, c, fRow, fCol, sadPerBit);
                            if (Integer.compareUnsigned(sad, bestSad) < 0) {
                                bestSad = sad;
                                bestSite = i;
                            }
                        }
                    }
                }
            }
            if (bestSite != lastSite) {
                br += SS_ROW[bestSite];
                bc += SS_COL[bestSite];
                lastSite = bestSite;
            } else if (br == startRow && bc == startCol) {
                num00++;
            }
        }
        searchRow = br;
        searchCol = bc;
        return bestSad;
    }

    /**
     * Refines a vector by moving one sample at a time while the cost decreases
     * ({@code vp9_refining_search_sad}).
     *
     * @param row the start row
     * @param col the start column
     * @param sadPerBit the vector cost weight
     * @param searchRange the largest number of moves
     * @param centerRow the eighth-pixel center row
     * @param centerCol the eighth-pixel center column
     * @return the best cost
     */
    private int refiningSearch(int row, int col, int sadPerBit, int searchRange, int centerRow, int centerCol) {
        var fRow = centerRow >> 3;
        var fCol = centerCol >> 3;
        var bestSad = sadAt(row, col) + mvsadErrCost(row, col, fRow, fCol, sadPerBit);
        var edsp = coder.edsp;
        for (var i = 0; i < searchRange; i++) {
            var bestSite = -1;
            var allIn = row - 1 > rowMin && row + 1 < rowMax && col - 1 > colMin && col + 1 < colMax;
            if (allIn) {
                var base = refBase + row * refStride + col;
                edsp.sad4(src, srcOff, srcStride, ref, base - refStride, base - 1, base + 1, base + refStride,
                        refStride, bw, bh, sads);
                for (var j = 0; j < 4; j++) {
                    var sad = sads[j];
                    if (Integer.compareUnsigned(sad, bestSad) < 0) {
                        sad += mvsadErrCost(row + CROSS_ROWS[j], col + CROSS_COLS[j], fRow, fCol, sadPerBit);
                        if (Integer.compareUnsigned(sad, bestSad) < 0) {
                            bestSad = sad;
                            bestSite = j;
                        }
                    }
                }
            } else {
                for (var j = 0; j < 4; j++) {
                    var r = row + CROSS_ROWS[j];
                    var c = col + CROSS_COLS[j];
                    if (inside(r, c)) {
                        var sad = sadAt(r, c);
                        if (Integer.compareUnsigned(sad, bestSad) < 0) {
                            sad += mvsadErrCost(r, c, fRow, fCol, sadPerBit);
                            if (Integer.compareUnsigned(sad, bestSad) < 0) {
                                bestSad = sad;
                                bestSite = j;
                            }
                        }
                    }
                }
            }
            if (bestSite == -1) {
                break;
            }
            row += CROSS_ROWS[bestSite];
            col += CROSS_COLS[bestSite];
        }
        searchRow = row;
        searchCol = col;
        return bestSad;
    }

    /**
     * Returns the variance of the block at a full-pixel vector plus the vector's error cost
     * ({@code vp9_get_mvpred_var}).
     *
     * @param row the full-pixel row
     * @param col the column
     * @param centerRow the eighth-pixel center row
     * @param centerCol the eighth-pixel center column
     * @return the cost
     */
    private int mvpredVar(int row, int col, int centerRow, int centerCol) {
        var cost = (long) varianceAt(row, col) + mvErrCost(row * 8, col * 8, centerRow, centerCol);
        return cost >= INT_MAX ? INT_MAX : (int) cost;
    }

    /**
     * Fills {@link #costList} with the variance costs of a full-pixel vector and its four
     * neighbors ({@code calc_int_cost_list}).
     *
     * @param refRow the full-pixel row passed as the reference, shifted again as libvpx does
     * @param refCol the full-pixel column passed as the reference
     * @param sadPerBit the vector cost weight
     * @param row the vector row
     * @param col the vector column
     */
    private void calcIntCostList(int refRow, int refCol, int sadPerBit, int row, int col) {
        var fRow = refRow >> 3;
        var fCol = refCol >> 3;
        costList[0] = varianceAt(row, col) + mvsadErrCost(row, col, fRow, fCol, sadPerBit);
        var all = inBounds(row, col, 1);
        for (var i = 0; i < 4; i++) {
            var r = row + RING_ROWS[i];
            var c = col + RING_COLS[i];
            if (!all && !inside(r, c)) {
                costList[i + 1] = INT_MAX;
            } else {
                costList[i + 1] = varianceAt(r, c) + mvErrCost(r, c, fRow, fCol);
            }
        }
    }

    /**
     * Runs a multi-scale pattern search, the hexagon search ({@code vp9_pattern_search}) or the
     * big diamond search ({@code vp9_pattern_search_sad}), without the initial scale scan.
     *
     * @param startRow the start row
     * @param startCol the start column
     * @param searchParam the search parameter, selecting the first scale
     * @param sadPerBit the vector cost weight
     * @param withCosts whether to fill {@link #costList}
     * @param centerRow the eighth-pixel center row
     * @param centerCol the eighth-pixel center column
     * @param counts the number of candidates of each scale
     * @param candidates the candidates of each scale
     * @param sadVariant whether this is the big diamond variant, which builds the cost list from
     * the sums of differences of its last scale
     * @return the best cost
     */
    private int patternSearch(int startRow, int startCol, int searchParam, int sadPerBit, boolean withCosts,
                              int centerRow, int centerCol, int[] counts, int[][] candidates, boolean sadVariant) {
        var fRow = centerRow >> 3;
        var fCol = centerCol >> 3;
        var br = Math.clamp(startRow, rowMin, rowMax);
        var bc = Math.clamp(startCol, colMin, colMax);
        var bestSad = sadAt(br, bc) + mvsadErrCost(br, bc, fRow, fCol, sadPerBit);
        var bestInitS = MAX_MVSEARCH_STEPS - 1 - searchParam;
        var k = -1;
        var doSad = sadVariant && withCosts;
        var s = bestInitS;
        var bestSite = -1;
        for (; s >= (doSad ? 1 : 0); s--) {
            var cand = candidates[s];
            var num = counts[s];
            bestSite = -1;
            var all = inBounds(br, bc, 1 << s);
            for (var i = 0; i < num; i++) {
                var r = br + cand[2 * i];
                var c = bc + cand[2 * i + 1];
                if (!all && !inside(r, c)) {
                    continue;
                }
                var sad = sadAt(r, c);
                if (sad < bestSad) {
                    sad += mvsadErrCost(r, c, fRow, fCol, sadPerBit);
                    if (sad < bestSad) {
                        bestSad = sad;
                        bestSite = i;
                    }
                }
            }
            if (bestSite == -1) {
                continue;
            }
            br += cand[2 * bestSite];
            bc += cand[2 * bestSite + 1];
            k = bestSite;
            do {
                bestSite = -1;
                var prev = k == 0 ? num - 1 : k - 1;
                var next = k == num - 1 ? 0 : k + 1;
                all = inBounds(br, bc, 1 << s);
                for (var i = 0; i < 3; i++) {
                    var index = i == 0 ? prev : (i == 1 ? k : next);
                    var r = br + cand[2 * index];
                    var c = bc + cand[2 * index + 1];
                    if (!all && !inside(r, c)) {
                        continue;
                    }
                    var sad = sadAt(r, c);
                    if (sad < bestSad) {
                        sad += mvsadErrCost(r, c, fRow, fCol, sadPerBit);
                        if (sad < bestSad) {
                            bestSad = sad;
                            bestSite = index;
                        }
                    }
                }
                if (bestSite != -1) {
                    k = bestSite;
                    br += cand[2 * k];
                    bc += cand[2 * k + 1];
                }
            } while (bestSite != -1);
        }
        if (doSad && s == 0) {
            bestSad = lastScaleWithCosts(br, bc, bestSad, sadPerBit, fRow, fCol, candidates[0], counts[0]);
            br = searchRow;
            bc = searchCol;
        }
        if (withCosts) {
            if (!sadVariant) {
                calcIntCostList(fRow, fCol, sadPerBit, br, bc);
            } else if (costList[0] == INT_MAX) {
                costList[0] = bestSad;
                var all = inBounds(br, bc, 1);
                for (var i = 0; i < 4; i++) {
                    var r = br + RING_ROWS[i];
                    var c = bc + RING_COLS[i];
                    costList[i + 1] = !all && !inside(r, c) ? INT_MAX : sadAt(r, c);
                }
            } else {
                for (var i = 0; i < 4; i++) {
                    if (costList[i + 1] != INT_MAX) {
                        costList[i + 1] += mvsadErrCost(br + RING_ROWS[i], bc + RING_COLS[i], fRow, fCol, sadPerBit);
                    }
                }
            }
        }
        bestRow = br;
        bestCol = bc;
        return bestSad;
    }

    /**
     * Searches the one-sample scale of the big diamond while recording the sums of differences
     * of the neighbors in {@link #costList} (the {@code s == 0} branch of
     * {@code vp9_pattern_search_sad}).
     *
     * @param br the current row
     * @param bc the current column
     * @param bestSad the current best cost
     * @param sadPerBit the vector cost weight
     * @param fRow the full-pixel center row
     * @param fCol the full-pixel center column
     * @param cand the scale's candidates
     * @param num the number of candidates
     * @return the best cost, with the vector left in {@link #searchRow} and {@link #searchCol}
     */
    private int lastScaleWithCosts(int br, int bc, int bestSad, int sadPerBit, int fRow, int fCol, int[] cand,
                                   int num) {
        costList[0] = bestSad;
        var bestSite = -1;
        var all = inBounds(br, bc, 1);
        for (var i = 0; i < num; i++) {
            var r = br + cand[2 * i];
            var c = bc + cand[2 * i + 1];
            if (!all && !inside(r, c)) {
                continue;
            }
            var sad = sadAt(r, c);
            costList[i + 1] = sad;
            if (sad < bestSad) {
                sad += mvsadErrCost(r, c, fRow, fCol, sadPerBit);
                if (sad < bestSad) {
                    bestSad = sad;
                    bestSite = i;
                }
            }
        }
        var k = -1;
        if (bestSite != -1) {
            br += cand[2 * bestSite];
            bc += cand[2 * bestSite + 1];
            k = bestSite;
        }
        while (bestSite != -1) {
            bestSite = -1;
            var prev = k == 0 ? num - 1 : k - 1;
            var next = k == num - 1 ? 0 : k + 1;
            costList[1] = INT_MAX;
            costList[2] = INT_MAX;
            costList[3] = INT_MAX;
            costList[4] = INT_MAX;
            costList[((k + 2) % 4) + 1] = costList[0];
            costList[0] = bestSad;
            all = inBounds(br, bc, 1);
            for (var i = 0; i < 3; i++) {
                var index = i == 0 ? prev : (i == 1 ? k : next);
                var r = br + cand[2 * index];
                var c = bc + cand[2 * index + 1];
                if (!all && !inside(r, c)) {
                    costList[index + 1] = INT_MAX;
                    continue;
                }
                var sad = sadAt(r, c);
                costList[index + 1] = sad;
                if (sad < bestSad) {
                    sad += mvsadErrCost(r, c, fRow, fCol, sadPerBit);
                    if (sad < bestSad) {
                        bestSad = sad;
                        bestSite = index;
                    }
                }
            }
            if (bestSite != -1) {
                k = bestSite;
                br += cand[2 * k];
                bc += cand[2 * k + 1];
            }
        }
        searchRow = br;
        searchCol = bc;
        return bestSad;
    }

    /**
     * Returns the bilinear sub-pixel variance of the block at an eighth-pixel vector.
     *
     * @param row the eighth-pixel row
     * @param col the column
     * @return the packed sums of squares and differences
     */
    private long subpelVariance(int row, int col) {
        return coder.edsp.subPixelVariance(ref, refBase + (row >> 3) * refStride + (col >> 3), refStride, col & 7,
                row & 7, src, srcOff, srcStride, bw, bh, coder.subpelScratch);
    }

    /**
     * Refines a full-pixel vector to sub-pixel precision with the tree search
     * ({@code vp9_find_best_sub_pixel_tree}), leaving the eighth-pixel vector in
     * {@link #bestRow} and {@link #bestCol}.
     *
     * @param row the full-pixel row
     * @param col the full-pixel column
     * @param refRow the eighth-pixel reference row
     * @param refCol the eighth-pixel reference column
     * @param allowHp whether the frame allows eighth-pixel vectors
     * @param forcedStop the coarsest precision to stop at: 0 eighth, 1 quarter, 2 half
     * @param itersPerStep the number of extra checks per step
     * @return the best cost
     */
    long subpelTree(int row, int col, int refRow, int refCol, boolean allowHp, int forcedStop, int itersPerStep) {
        var n = log2(bw * bh);
        var cMin = Math.max(Math.max(colMin * 8, refCol - MAX_FULL_PEL_VAL * 8), MV_LOW + 1);
        var cMax = Math.min(Math.min(colMax * 8, refCol + MAX_FULL_PEL_VAL * 8), MV_UPP - 1);
        var rMin = Math.max(Math.max(rowMin * 8, refRow - MAX_FULL_PEL_VAL * 8), MV_LOW + 1);
        var rMax = Math.min(Math.min(rowMax * 8, refRow + MAX_FULL_PEL_VAL * 8), MV_UPP - 1);
        var round = 3 - forcedStop;
        if (!(allowHp && Vp9EncMv.useHp(ModeInfo.packMv(refRow, refCol))) && round == 3) {
            round = 2;
        }
        var br = row * 8;
        var bc = col * 8;
        var packed = coder.edsp.variance(ref, refBase + row * refStride + col, refStride, src, srcOff, srcStride, bw,
                bh);
        var dist = Vp9EncDsp.variance(packed, n);
        distortion = (int) dist;
        sse1 = Vp9EncDsp.sse(packed);
        var bestErr = dist + mvErrCost(br, bc, refRow, refCol);
        var hstep = 4;
        var tr = br;
        var tc = bc;
        var cost = costArray;
        for (var iter = 0; iter < round; iter++) {
            var bestIdx = -1;
            var stepBase = iter * 8;
            for (var idx = 0; idx < 4; idx++) {
                tr = br + STEP_TABLE[stepBase + 2 * idx];
                tc = bc + STEP_TABLE[stepBase + 2 * idx + 1];
                if (tc >= cMin && tc <= cMax && tr >= rMin && tr <= rMax) {
                    var p = subpelVariance(tr, tc);
                    var thisMse = Vp9EncDsp.variance(p, n);
                    cost[idx] = thisMse + mvErrCost(tr, tc, refRow, refCol);
                    if (cost[idx] < bestErr) {
                        bestIdx = idx;
                        bestErr = cost[idx];
                        distortion = (int) thisMse;
                        sse1 = Vp9EncDsp.sse(p);
                    }
                } else {
                    cost[idx] = 0xFFFFFFFFL;
                }
            }
            var kc = cost[0] <= cost[1] ? -hstep : hstep;
            var kr = cost[2] <= cost[3] ? -hstep : hstep;
            tc = bc + kc;
            tr = br + kr;
            if (tc >= cMin && tc <= cMax && tr >= rMin && tr <= rMax) {
                var p = subpelVariance(tr, tc);
                var thisMse = Vp9EncDsp.variance(p, n);
                cost[4] = thisMse + mvErrCost(tr, tc, refRow, refCol);
                if (cost[4] < bestErr) {
                    bestIdx = 4;
                    bestErr = cost[4];
                    distortion = (int) thisMse;
                    sse1 = Vp9EncDsp.sse(p);
                }
            }
            if (bestIdx >= 0 && bestIdx < 4) {
                br += STEP_TABLE[stepBase + 2 * bestIdx];
                bc += STEP_TABLE[stepBase + 2 * bestIdx + 1];
            } else if (bestIdx == 4) {
                br = tr;
                bc = tc;
            }
            if (itersPerStep > 0 && bestIdx != -1) {
                var br0 = br;
                var bc0 = bc;
                checkRow = br;
                checkCol = bc;
                if (tr == br && tc != bc) {
                    kc = bc - tc;
                    if (itersPerStep == 1) {
                        bestErr = checkBetter(br0, bc0 + kc, refRow, refCol, rMin, rMax, cMin, cMax, bestErr, n);
                    }
                } else if (tr != br && tc == bc) {
                    kr = br - tr;
                    if (itersPerStep == 1) {
                        bestErr = checkBetter(br0 + kr, bc0, refRow, refCol, rMin, rMax, cMin, cMax, bestErr, n);
                    }
                }
                if (itersPerStep > 1) {
                    bestErr = checkBetter(br0 + kr, bc0, refRow, refCol, rMin, rMax, cMin, cMax, bestErr, n);
                    bestErr = checkBetter(br0, bc0 + kc, refRow, refCol, rMin, rMax, cMin, cMax, bestErr, n);
                    if (br0 != br || bc0 != bc) {
                        bestErr = checkBetter(br0 + kr, bc0 + kc, refRow, refCol, rMin, rMax, cMin, cMax, bestErr, n);
                    }
                }
                br = checkRow;
                bc = checkCol;
            }
            hstep >>= 1;
        }
        bestRow = br;
        bestCol = bc;
        return bestErr;
    }

    /**
     * The row of the best vector after the last {@link #checkBetter}.
     */
    private int checkRow;

    /**
     * The column of the best vector after the last {@link #checkBetter}.
     */
    private int checkCol;

    /**
     * Evaluates a sub-pixel vector and keeps it when it beats the best cost
     * ({@code CHECK_BETTER}); the best vector is left in {@link #checkRow} and
     * {@link #checkCol}, which callers seed with the current best.
     *
     * @param r the vector row
     * @param c the vector column
     * @param refRow the reference row
     * @param refCol the reference column
     * @param rMin the smallest row
     * @param rMax the largest row
     * @param cMin the smallest column
     * @param cMax the largest column
     * @param bestErr the best cost so far
     * @param n the log2 of the block's sample count
     * @return the best cost
     */
    private long checkBetter(int r, int c, int refRow, int refCol, int rMin, int rMax, int cMin, int cMax,
                             long bestErr, int n) {
        lastCheck = 0xFFFFFFFFL;
        if (c >= cMin && c <= cMax && r >= rMin && r <= rMax) {
            var p = subpelVariance(r, c);
            var thisMse = Vp9EncDsp.variance(p, n);
            var v = mvErrCost(r, c, refRow, refCol) + thisMse;
            lastCheck = v;
            if (v < bestErr) {
                checkRow = r;
                checkCol = c;
                distortion = (int) thisMse;
                sse1 = Vp9EncDsp.sse(p);
                return v;
            }
        }
        return bestErr;
    }

    /**
     * The cost of the last vector {@link #checkBetter} evaluated, or {@code UINT_MAX} when it
     * lay outside the limits.
     */
    private long lastCheck;

    /**
     * Refines a full-pixel vector with the cheapest sub-pixel search, which models the cost
     * surface from the integer cost list when it is well behaved
     * ({@code vp9_find_best_sub_pixel_tree_pruned_evenmore}).
     *
     * @param row the full-pixel row
     * @param col the full-pixel column
     * @param refRow the eighth-pixel reference row
     * @param refCol the eighth-pixel reference column
     * @param allowHp whether the frame allows eighth-pixel vectors
     * @param forcedStop the coarsest precision to stop at
     * @param itersPerStep the number of extra checks per step
     * @return the best cost
     */
    long subpelPrunedEvenMore(int row, int col, int refRow, int refCol, boolean allowHp, int forcedStop,
                              int itersPerStep) {
        var n = log2(bw * bh);
        var cMin = Math.max(Math.max(colMin * 8, refCol - MAX_FULL_PEL_VAL * 8), MV_LOW + 1);
        var cMax = Math.min(Math.min(colMax * 8, refCol + MAX_FULL_PEL_VAL * 8), MV_UPP - 1);
        var rMin = Math.max(Math.max(rowMin * 8, refRow - MAX_FULL_PEL_VAL * 8), MV_LOW + 1);
        var rMax = Math.min(Math.min(rowMax * 8, refRow + MAX_FULL_PEL_VAL * 8), MV_UPP - 1);
        var br = row * 8;
        var bc = col * 8;
        var packed = coder.edsp.variance(ref, refBase + row * refStride + col, refStride, src, srcOff, srcStride, bw,
                bh);
        var dist = Vp9EncDsp.variance(packed, n);
        distortion = (int) dist;
        sse1 = Vp9EncDsp.sse(packed);
        var bestErr = dist + mvErrCost(br, bc, refRow, refCol);
        var cl = costList;
        checkRow = br;
        checkCol = bc;
        var hstep = 4;
        if (cl[0] != INT_MAX && cl[1] != INT_MAX && cl[2] != INT_MAX && cl[3] != INT_MAX
                && cl[4] != INT_MAX && cl[0] < cl[1] && cl[0] < cl[2] && cl[0] < cl[3] && cl[0] < cl[4]) {
            var x0 = (long) cl[1] - cl[3];
            var y0 = cl[1] - 2L * cl[0] + cl[3];
            var x1 = (long) cl[4] - cl[2];
            var y1 = cl[4] - 2L * cl[0] + cl[2];
            var ic = (int) divideAndRound(x0 * 2, y0);
            var ir = (int) divideAndRound(x1 * 2, y1);
            if (ir != 0 || ic != 0) {
                bestErr = checkBetter(br + 2 * ir, bc + 2 * ic, refRow, refCol, rMin, rMax, cMin, cMax, bestErr, n);
            }
        } else {
            bestErr = firstLevelChecks(br, bc, hstep, refRow, refCol, rMin, rMax, cMin, cMax, bestErr, n);
            if (itersPerStep > 1) {
                bestErr = secondLevelChecks(br, bc, hstep, refRow, refCol, rMin, rMax, cMin, cMax, bestErr, n);
            }
            if (forcedStop != 2) {
                hstep >>= 1;
                var tr = checkRow;
                var tc = checkCol;
                bestErr = firstLevelChecks(tr, tc, hstep, refRow, refCol, rMin, rMax, cMin, cMax, bestErr, n);
                if (itersPerStep > 1) {
                    bestErr = secondLevelChecks(tr, tc, hstep, refRow, refCol, rMin, rMax, cMin, cMax, bestErr, n);
                }
            }
        }
        if (allowHp && Vp9EncMv.useHp(ModeInfo.packMv(refRow, refCol)) && forcedStop == 0) {
            hstep >>= 1;
            var tr = checkRow;
            var tc = checkCol;
            bestErr = firstLevelChecks(tr, tc, hstep, refRow, refCol, rMin, rMax, cMin, cMax, bestErr, n);
            if (itersPerStep > 1) {
                bestErr = secondLevelChecks(tr, tc, hstep, refRow, refCol, rMin, rMax, cMin, cMax, bestErr, n);
            }
        }
        bestRow = checkRow;
        bestCol = checkCol;
        return bestErr;
    }

    /**
     * The direction the last {@link #firstLevelChecks} chose for its diagonal.
     */
    private int whichDir;

    /**
     * Checks the four neighbors of a vector at a step and the diagonal between the two better
     * ones ({@code FIRST_LEVEL_CHECKS}).
     *
     * @param tr the vector row
     * @param tc the vector column
     * @param hstep the step
     * @param refRow the reference row
     * @param refCol the reference column
     * @param rMin the smallest row
     * @param rMax the largest row
     * @param cMin the smallest column
     * @param cMax the largest column
     * @param bestErr the best cost so far
     * @param n the log2 of the block's sample count
     * @return the best cost
     */
    private long firstLevelChecks(int tr, int tc, int hstep, int refRow, int refCol, int rMin, int rMax, int cMin,
                                  int cMax, long bestErr, int n) {
        bestErr = checkBetter(tr, tc - hstep, refRow, refCol, rMin, rMax, cMin, cMax, bestErr, n);
        var left = lastCheck;
        bestErr = checkBetter(tr, tc + hstep, refRow, refCol, rMin, rMax, cMin, cMax, bestErr, n);
        var right = lastCheck;
        bestErr = checkBetter(tr - hstep, tc, refRow, refCol, rMin, rMax, cMin, cMax, bestErr, n);
        var up = lastCheck;
        bestErr = checkBetter(tr + hstep, tc, refRow, refCol, rMin, rMax, cMin, cMax, bestErr, n);
        var down = lastCheck;
        whichDir = (left < right ? 0 : 1) + (up < down ? 0 : 2);
        return switch (whichDir) {
            case 0 -> checkBetter(tr - hstep, tc - hstep, refRow, refCol, rMin, rMax, cMin, cMax, bestErr, n);
            case 1 -> checkBetter(tr - hstep, tc + hstep, refRow, refCol, rMin, rMax, cMin, cMax, bestErr, n);
            case 2 -> checkBetter(tr + hstep, tc - hstep, refRow, refCol, rMin, rMax, cMin, cMax, bestErr, n);
            default -> checkBetter(tr + hstep, tc + hstep, refRow, refCol, rMin, rMax, cMin, cMax, bestErr, n);
        };
    }

    /**
     * Checks the vectors beyond the best of a first-level check ({@code SECOND_LEVEL_CHECKS}).
     *
     * @param tr the row the first-level check started from
     * @param tc the column the first-level check started from
     * @param hstep the step
     * @param refRow the reference row
     * @param refCol the reference column
     * @param rMin the smallest row
     * @param rMax the largest row
     * @param cMin the smallest column
     * @param cMax the largest column
     * @param bestErr the best cost so far
     * @param n the log2 of the block's sample count
     * @return the best cost
     */
    private long secondLevelChecks(int tr, int tc, int hstep, int refRow, int refCol, int rMin, int rMax, int cMin,
                                   int cMax, long bestErr, int n) {
        var br = checkRow;
        var bc = checkCol;
        if (tr != br && tc != bc) {
            var kr = br - tr;
            var kc = bc - tc;
            bestErr = checkBetter(tr + kr, tc + 2 * kc, refRow, refCol, rMin, rMax, cMin, cMax, bestErr, n);
            bestErr = checkBetter(tr + 2 * kr, tc + kc, refRow, refCol, rMin, rMax, cMin, cMax, bestErr, n);
        } else if (tr == br && tc != bc) {
            var kc = bc - tc;
            bestErr = checkBetter(tr + hstep, tc + 2 * kc, refRow, refCol, rMin, rMax, cMin, cMax, bestErr, n);
            bestErr = checkBetter(tr - hstep, tc + 2 * kc, refRow, refCol, rMin, rMax, cMin, cMax, bestErr, n);
            if (whichDir < 2) {
                bestErr = checkBetter(tr + hstep, tc + kc, refRow, refCol, rMin, rMax, cMin, cMax, bestErr, n);
            } else {
                bestErr = checkBetter(tr - hstep, tc + kc, refRow, refCol, rMin, rMax, cMin, cMax, bestErr, n);
            }
        } else if (tr != br) {
            var kr = br - tr;
            bestErr = checkBetter(tr + 2 * kr, tc + hstep, refRow, refCol, rMin, rMax, cMin, cMax, bestErr, n);
            bestErr = checkBetter(tr + 2 * kr, tc - hstep, refRow, refCol, rMin, rMax, cMin, cMax, bestErr, n);
            if (whichDir == 0 || whichDir == 2) {
                bestErr = checkBetter(tr + kr, tc + hstep, refRow, refCol, rMin, rMax, cMin, cMax, bestErr, n);
            } else {
                bestErr = checkBetter(tr + kr, tc - hstep, refRow, refCol, rMin, rMax, cMin, cMax, bestErr, n);
            }
        }
        return bestErr;
    }

    /**
     * Divides rounding to the nearest integer, halves away from zero ({@code divide_and_round}).
     *
     * @param n the numerator
     * @param d the denominator
     * @return the quotient
     */
    private static long divideAndRound(long n, long d) {
        return ((n < 0) ^ (d < 0)) ? ((n - d / 2) / d) : ((n + d / 2) / d);
    }
}
