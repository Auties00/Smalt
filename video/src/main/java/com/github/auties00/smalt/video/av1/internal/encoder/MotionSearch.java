package com.github.auties00.smalt.video.av1.internal.encoder;

/**
 * Searches the luma motion vector of one block against one edge-extended reference: the full
 * sample diamond search, the sub-sample tree search with bilinear estimates, and the integral
 * projection estimate of the variance partitioning.
 *
 * <p>The caller sets the block, the reference and the vector predictor with {@link #setBlock}
 * before searching. Motion vectors are in eighth samples unless named full-sample. An instance
 * holds scratch buffers and is used by one thread at a time.
 *
 * @implNote This implementation ports libaom's {@code av1/encoder/mcomp.c}: the
 * {@code FAST_DIAMOND} pattern search with the big diamond sites
 * ({@code pattern_search}, {@code init_motion_compensation_bigdia}), the motion vector
 * cost terms ({@code mvsad_err_cost}, {@code mv_err_cost}),
 * {@code av1_find_best_sub_pixel_tree_pruned} and its {@code _more} variant with the
 * bilinear estimate ({@code check_better_fast}), and
 * {@code av1_int_pro_motion_estimation} with {@code av1_vector_match}.
 */
final class MotionSearch {
    /**
     * The largest full-sample distance from the predictor ({@code MAX_FULL_PEL_VAL}).
     */
    private static final int MAX_FULL_PEL_VAL = (1 << 10) - 1;

    /**
     * The motion vector limit in eighth samples ({@code MV_UPP}).
     */
    private static final int MV_UPP = 1 << 14;

    /**
     * The number of stages of the n-step search ({@code init_motion_compensation_nstep} at level 0).
     */
    static final int NSTEP_STAGES = 15;

    /**
     * The sites of each n-step stage, as row, column pairs, smallest radius first.
     */
    private static final int[][] NSTEP = nstepSites();

    /**
     * The candidates of each big diamond scale, as row, column pairs.
     */
    private static final int[][] BIGDIA = {
            {0, -1, 1, 0, 0, 1, -1, 0},
            {-1, -1, 0, -2, 1, -1, 2, 0, 1, 1, 0, 2, -1, 1, -2, 0},
    };

    /**
     * The kernels.
     */
    private EncDsp dsp;

    /**
     * The rate tables of the frame.
     */
    private RdCost rd;

    /**
     * The source plane.
     */
    private short[] src;

    /**
     * The offset of the block in the source plane.
     */
    private int srcOff;

    /**
     * The source stride.
     */
    private int srcStride;

    /**
     * The edge-extended reference plane.
     */
    private short[] ref;

    /**
     * The offset of the block's co-located position in the reference plane.
     */
    private int refOff;

    /**
     * The reference stride.
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
     * The smallest full-sample column offset.
     */
    int colMin;

    /**
     * The largest full-sample column offset.
     */
    int colMax;

    /**
     * The smallest full-sample row offset.
     */
    int rowMin;

    /**
     * The largest full-sample row offset.
     */
    int rowMax;

    /**
     * The predictor row in eighth samples.
     */
    private int refRow;

    /**
     * The predictor column in eighth samples.
     */
    private int refCol;

    /**
     * The SAD weight of a motion vector bit.
     */
    private int sadPerBit;

    /**
     * The error weight of a motion vector bit.
     */
    private int errorPerBit;

    /**
     * The costs of the four full-sample neighbors of the last full-sample result, as libaom's
     * {@code cost_list}.
     */
    final int[] costList = new int[5];

    /**
     * The three diamond sites around the last best one, in search order.
     */
    private final int[] next = new int[3];

    /**
     * The distortion of the last search result.
     */
    int distortion;

    /**
     * The sum of squared errors of the last search result.
     */
    int sse;

    /**
     * The variance-based cost of the last full-sample result.
     */
    int fullVarCost;

    /**
     * The scratch of the bilinear filter.
     */
    private final short[] scratch = new short[65 * 64];

    /**
     * The column projections of the reference.
     */
    private final int[] hbuf = new int[64 + 2 * 512];

    /**
     * The row projections of the reference.
     */
    private final int[] vbuf = new int[64 + 2 * 512];

    /**
     * The column projections of the source.
     */
    private final int[] srcHbuf = new int[64];

    /**
     * The row projections of the source.
     */
    private final int[] srcVbuf = new int[64];

    /**
     * Creates a search with its scratch buffers.
     *
     * @param dsp the kernels
     */
    MotionSearch(EncDsp dsp) {
        this.dsp = dsp;
    }

    /**
     * Sets the kernels of the next tile.
     *
     * @param dsp the kernels
     */
    void dsp(EncDsp dsp) {
        this.dsp = dsp;
    }

    /**
     * Sets the block, the reference and the predictor of the next searches.
     *
     * @param rd the frame's rate tables
     * @param src the source luma plane
     * @param srcOff the offset of the block in the source
     * @param srcStride the source stride
     * @param frame the reference
     * @param px the block column in samples
     * @param py the block row in samples
     * @param bw the block width
     * @param bh the block height
     * @param alignedW the picture width rounded up to 8
     * @param alignedH the picture height rounded up to 8
     * @param predRow the predictor row in eighth samples
     * @param predCol the predictor column in eighth samples
     * @param sadPerBit the SAD weight of a motion vector bit
     * @param errorPerBit the error weight of a motion vector bit
     */
    void setBlock(RdCost rd, short[] src, int srcOff, int srcStride, RefFrame frame, int px, int py, int bw, int bh,
                  int alignedW, int alignedH, int predRow, int predCol, int sadPerBit, int errorPerBit) {
        this.rd = rd;
        this.src = src;
        this.srcOff = srcOff;
        this.srcStride = srcStride;
        this.ref = frame.padded[0];
        this.refStride = frame.stride[0];
        this.refOff = frame.origin[0] + py * refStride + px;
        this.bw = bw;
        this.bh = bh;
        this.refRow = predRow;
        this.refCol = predCol;
        this.sadPerBit = sadPerBit;
        this.errorPerBit = errorPerBit;
        var border = RefFrame.BORDER - 16;
        colMin = -(px + border);
        rowMin = -(py + border);
        colMax = alignedW - px - bw + border;
        rowMax = alignedH - py - bh + border;
        var cMin = ((predCol + 7) >> 3) - MAX_FULL_PEL_VAL;
        var rMin = ((predRow + 7) >> 3) - MAX_FULL_PEL_VAL;
        var cMax = (predCol >> 3) + MAX_FULL_PEL_VAL;
        var rMax = (predRow >> 3) + MAX_FULL_PEL_VAL;
        cMin = Math.max(cMin, (-MV_UPP >> 3) + 1);
        rMin = Math.max(rMin, (-MV_UPP >> 3) + 1);
        cMax = Math.min(cMax, (MV_UPP >> 3) - 1);
        rMax = Math.min(rMax, (MV_UPP >> 3) - 1);
        colMin = Math.max(colMin, cMin);
        colMax = Math.max(colMin, Math.min(colMax, cMax));
        rowMin = Math.max(rowMin, rMin);
        rowMax = Math.max(rowMin, Math.min(rowMax, rMax));
    }

    /**
     * The most SADs one {@link #fastDiamond} remembers.
     */
    private static final int MEMO_SIZE = 48;

    /**
     * The positions, packed as {@code row << 16 | (col & 0xFFFF)}, whose SAD the current
     * {@link #fastDiamond} computed.
     */
    private final int[] memoPositions = new int[MEMO_SIZE];

    /**
     * The SADs at {@link #memoPositions}.
     */
    private final int[] memoSads = new int[MEMO_SIZE];

    /**
     * The number of remembered SADs.
     */
    private int memoCount;

    /**
     * Whether the full-sample searches compare every other row only.
     */
    boolean skipRows;

    /**
     * Returns the SAD of the block at a full-sample offset, computing it only the first time the
     * current {@link #fastDiamond} asks for that offset: the diamond steps and the final neighbor
     * list revisit positions, and a SAD depends only on its position.
     *
     * @param row the row offset
     * @param col the column offset
     * @return the SAD
     */
    private int memoSad(int row, int col) {
        var key = row << 16 | (col & 0xFFFF);
        for (var i = 0; i < memoCount; i++) {
            if (memoPositions[i] == key) {
                return memoSads[i];
            }
        }
        var sad = sadAt(row, col);
        if (memoCount < MEMO_SIZE) {
            memoPositions[memoCount] = key;
            memoSads[memoCount++] = sad;
        }
        return sad;
    }

    /**
     * Returns the SAD of the block at a full-sample offset.
     *
     * @param row the row offset
     * @param col the column offset
     * @return the SAD
     */
    int sadAt(int row, int col) {
        if (skipRows) {
            return 2 * dsp.sad(src, srcOff, 2 * srcStride, ref, refOff + row * refStride + col, 2 * refStride, bw,
                    bh >> 1);
        }
        return dsp.sad(src, srcOff, srcStride, ref, refOff + row * refStride + col, refStride, bw, bh);
    }

    /**
     * Returns the cost of a full-sample vector during the full-sample search
     * ({@code mvsad_err_cost}).
     *
     * @param row the row offset
     * @param col the column offset
     * @return the cost
     */
    private int mvsadCost(int row, int col) {
        var fullRefRow = refRow >> 3;
        var fullRefCol = refCol >> 3;
        var dr = Math.clamp((row - fullRefRow) * 8L, -RdCost.MV_MAX, RdCost.MV_MAX);
        var dc = Math.clamp((col - fullRefCol) * 8L, -RdCost.MV_MAX, RdCost.MV_MAX);
        var joint = (dr != 0 ? 2 : 0) | (dc != 0 ? 1 : 0);
        var cost = rd.mvJoint[joint] + rd.mvComp[0][RdCost.MV_MAX + (int) dr] + rd.mvComp[1][RdCost.MV_MAX + (int) dc];
        return (int) (((long) cost * sadPerBit + (1 << (RdCost.PROB_COST_SHIFT - 1))) >> RdCost.PROB_COST_SHIFT);
    }

    /**
     * Returns the cost of a vector during the error-based searches ({@code mv_err_cost}).
     *
     * @param row the row in eighth samples
     * @param col the column in eighth samples
     * @return the cost
     */
    int mvErrCost(int row, int col) {
        var dr = Math.clamp(row - refRow, -RdCost.MV_MAX, RdCost.MV_MAX);
        var dc = Math.clamp(col - refCol, -RdCost.MV_MAX, RdCost.MV_MAX);
        var joint = (dr != 0 ? 2 : 0) | (dc != 0 ? 1 : 0);
        var cost = rd.mvJoint[joint] + rd.mvComp[0][RdCost.MV_MAX + dr] + rd.mvComp[1][RdCost.MV_MAX + dc];
        return (int) (((long) cost * errorPerBit + (1 << 13)) >> 14);
    }

    /**
     * Returns whether a full-sample vector is inside the search limits.
     *
     * @param row the row offset
     * @param col the column offset
     * @return {@code true} when inside
     */
    private boolean inRange(int row, int col) {
        return row >= rowMin && row <= rowMax && col >= colMin && col <= colMax;
    }

    /**
     * Builds the sites of the n-step search: eight sites at radii up to 5, then twelve with
     * tangential offsets of 0.41 times the radius, the radius growing by half each stage up to the
     * thirteenth ({@code init_motion_compensation_nstep}).
     *
     * @return the sites of each stage
     */
    private static int[][] nstepSites() {
        var out = new int[NSTEP_STAGES][];
        var radius = 1;
        for (var stage = 0; stage < NSTEP_STAGES; stage++) {
            var r = radius;
            if (r <= 5) {
                out[stage] = new int[]{-r, 0, r, 0, 0, -r, 0, r, -r, -r, r, r, -r, r, r, -r};
            } else {
                var t = Math.max((int) (0.41 * r), 1);
                out[stage] = new int[]{-r, 0, r, 0, 0, -r, 0, r, -r, -t, r, t, -t, r, t, -r, -r, t, r, -t, t, r, -t, -r};
            }
            if (stage < 12) {
                radius = (int) Math.max(radius * 1.5 + 0.5, radius + 1);
            }
        }
        return out;
    }

    /**
     * Searches the best full-sample vector with the n-step diamond: from the largest of the given
     * number of stages down to radius one, the best site of each stage around the best vector so
     * far, by SAD plus motion vector cost ({@code diamond_search_sad}). The result is a start for
     * {@link #fastDiamond}, which refines it and computes the statistics the sub-sample search
     * needs.
     *
     * @param startRow the start row offset
     * @param startCol the start column offset
     * @param stages the number of stages, 1 to {@link #NSTEP_STAGES}
     * @return the best vector, packed as {@code row << 16 | (col & 0xFFFF)}
     */
    int nstep(int startRow, int startCol, int stages) {
        var br = Math.clamp(startRow, rowMin, rowMax);
        var bc = Math.clamp(startCol, colMin, colMax);
        var best = sadAt(br, bc) + mvsadCost(br, bc);
        var previousRadius = Integer.MAX_VALUE;
        for (var stage = Math.min(stages, NSTEP_STAGES) - 1; stage >= 0; stage--) {
            var sites = NSTEP[stage];
            if (sites[2] == previousRadius) {
                continue;
            }
            previousRadius = sites[2];
            var bestSite = -1;
            for (var j = 0; j < sites.length; j += 2) {
                var r = br + sites[j];
                var c = bc + sites[j + 1];
                if (!inRange(r, c)) {
                    continue;
                }
                var sad = sadAt(r, c);
                if (sad < best) {
                    var cost = sad + mvsadCost(r, c);
                    if (cost < best) {
                        best = cost;
                        bestSite = j;
                    }
                }
            }
            if (bestSite >= 0) {
                br += sites[bestSite];
                bc += sites[bestSite + 1];
            }
        }
        return br << 16 | (bc & 0xFFFF);
    }

    /**
     * Searches the best full-sample vector with the fast diamond pattern ({@code fast_dia_search}),
     * fills {@link #costList} and {@link #fullVarCost}.
     *
     * @param startRow the start row offset
     * @param startCol the start column offset
     * @return the best vector, packed as {@code row << 16 | (col & 0xFFFF)}
     */
    int fastDiamond(int startRow, int startCol) {
        memoCount = 0;
        var br = Math.clamp(startRow, rowMin, rowMax);
        var bc = Math.clamp(startCol, colMin, colMax);
        var rawBest = memoSad(br, bc);
        var best = rawBest + mvsadCost(br, bc);
        var k = -1;
        var s = 1;
        var numCand = 8;
        var sites = BIGDIA[1];
        var bestSite = -1;
        for (var j = 0; j < numCand; j++) {
            var r = br + sites[j * 2];
            var c = bc + sites[j * 2 + 1];
            if (!inRange(r, c)) {
                continue;
            }
            var sad = memoSad(r, c);
            if (sad < best) {
                var cost = sad + mvsadCost(r, c);
                if (cost < best) {
                    best = cost;
                    rawBest = sad;
                    bestSite = j;
                }
            }
        }
        if (bestSite != -1) {
            br += sites[bestSite * 2];
            bc += sites[bestSite * 2 + 1];
            k = bestSite;
            do {
                bestSite = -1;
                var cr = br;
                var cc = bc;
                next[0] = k == 0 ? numCand - 1 : k - 1;
                next[1] = k;
                next[2] = k == numCand - 1 ? 0 : k + 1;
                for (var idx = 0; idx < 3; idx++) {
                    var j = next[idx];
                    var r = cr + sites[j * 2];
                    var c = cc + sites[j * 2 + 1];
                    if (!inRange(r, c)) {
                        continue;
                    }
                    var sad = memoSad(r, c);
                    if (sad < best) {
                        var cost = sad + mvsadCost(r, c);
                        if (cost < best) {
                            best = cost;
                            rawBest = sad;
                            bestSite = idx;
                        }
                    }
                }
                if (bestSite != -1) {
                    k = next[bestSite];
                    br += sites[k * 2];
                    bc += sites[k * 2 + 1];
                }
            } while (bestSite != -1);
        }
        sites = BIGDIA[0];
        numCand = 4;
        costList[0] = rawBest;
        bestSite = -1;
        for (var j = 0; j < numCand; j++) {
            var r = br + sites[j * 2];
            var c = bc + sites[j * 2 + 1];
            if (!inRange(r, c)) {
                costList[j + 1] = Integer.MAX_VALUE;
                continue;
            }
            var sad = memoSad(r, c);
            costList[j + 1] = sad;
            if (sad < best) {
                var cost = sad + mvsadCost(r, c);
                if (cost < best) {
                    best = cost;
                    rawBest = sad;
                    bestSite = j;
                }
            }
        }
        if (bestSite != -1) {
            br += sites[bestSite * 2];
            bc += sites[bestSite * 2 + 1];
            k = bestSite;
            while (bestSite != -1) {
                bestSite = -1;
                next[0] = k == 0 ? numCand - 1 : k - 1;
                next[1] = k;
                next[2] = k == numCand - 1 ? 0 : k + 1;
                for (var i = 1; i <= 4; i++) {
                    costList[i] = Integer.MAX_VALUE;
                }
                costList[((k + 2) % 4) + 1] = costList[0];
                costList[0] = rawBest;
                var cr = br;
                var cc = bc;
                for (var idx = 0; idx < 3; idx++) {
                    var j = next[idx];
                    var r = cr + sites[j * 2];
                    var c = cc + sites[j * 2 + 1];
                    if (!inRange(r, c)) {
                        continue;
                    }
                    var sad = memoSad(r, c);
                    costList[j + 1] = sad;
                    if (sad < best) {
                        var cost = sad + mvsadCost(r, c);
                        if (cost < best) {
                            best = cost;
                            rawBest = sad;
                            bestSite = idx;
                        }
                    }
                }
                if (bestSite != -1) {
                    k = next[bestSite];
                    br += sites[k * 2];
                    bc += sites[k * 2 + 1];
                }
            }
        }
        // libaom recomputes the full neighbor SAD list with costs (calc_int_sad_list).
        costList[0] = rawBest;
        for (var j = 0; j < 4; j++) {
            var r = br + sites[j * 2];
            var c = bc + sites[j * 2 + 1];
            costList[j + 1] = inRange(r, c) ? memoSad(r, c) : Integer.MAX_VALUE;
        }
        costList[0] += mvsadCost(br, bc);
        for (var j = 0; j < 4; j++) {
            if (costList[j + 1] != Integer.MAX_VALUE) {
                costList[j + 1] += mvsadCost(br + sites[j * 2], bc + sites[j * 2 + 1]);
            }
        }
        var packed = dsp.sseSum(src, srcOff, srcStride, ref, refOff + br * refStride + bc, refStride, bw, bh);
        var log2n = Integer.numberOfTrailingZeros(bw) + Integer.numberOfTrailingZeros(bh);
        var var = EncDsp.variance(packed, log2n);
        distortion = var;
        sse = EncDsp.sse(packed);
        fullVarCost = var + mvErrCost(br * 8, bc * 8);
        return br << 16 | (bc & 0xFFFF);
    }

    /**
     * Returns the bilinear prediction error of a sub-sample vector, or {@link Integer#MAX_VALUE}
     * when outside the limits, and records its sum of squared errors in {@link #lastSse}.
     *
     * @param row the row in eighth samples
     * @param col the column in eighth samples
     * @return the error
     */
    private int prefError(int row, int col) {
        var off = refOff + (row >> 3) * refStride + (col >> 3);
        var packed = dsp.subpelSseSum(ref, off, refStride, col & 7, row & 7, src, srcOff, srcStride, bw, bh, scratch);
        var log2n = Integer.numberOfTrailingZeros(bw) + Integer.numberOfTrailingZeros(bh);
        lastSse = EncDsp.sse(packed);
        return EncDsp.variance(packed, log2n);
    }

    /**
     * The sum of squared errors of the last {@link #prefError} call.
     */
    private int lastSse;

    /**
     * The best vector of the running sub-sample search, packed.
     */
    private int bestMv;

    /**
     * The best cost of the running sub-sample search.
     */
    private int bestErr;

    /**
     * Tries a sub-sample vector and keeps it when it lowers the cost ({@code check_better_fast}).
     *
     * @param row the row in eighth samples
     * @param col the column in eighth samples
     * @return the cost, or {@link Integer#MAX_VALUE} when outside the limits
     */
    private int checkBetterFast(int row, int col) {
        if (row < rowMin * 8 || row > rowMax * 8 || col < colMin * 8 || col > colMax * 8) {
            return Integer.MAX_VALUE;
        }
        var err = prefError(row, col);
        var cost = mvErrCost(row, col) + err;
        if (Integer.compareUnsigned(cost, bestErr) < 0) {
            bestErr = cost;
            bestMv = row << 16 | (col & 0xFFFF);
            distortion = err;
            sse = lastSse;
        }
        return cost;
    }

    /**
     * Searches the four cardinal neighbors at a step and the best diagonal, then the chess
     * pattern of the best quadrant ({@code two_level_checks_fast}).
     *
     * @param row the center row
     * @param col the center column
     * @param hstep the step in eighth samples
     * @param iters the iterations per step
     */
    private void twoLevelChecksFast(int row, int col, int hstep, int iters) {
        var left = checkBetterFast(row, col - hstep);
        var right = checkBetterFast(row, col + hstep);
        var up = checkBetterFast(row - hstep, col);
        var down = checkBetterFast(row + hstep, col);
        var diagRow = Integer.compareUnsigned(up, down) <= 0 ? -hstep : hstep;
        var diagCol = Integer.compareUnsigned(left, right) <= 0 ? -hstep : hstep;
        checkBetterFast(row + diagRow, col + diagCol);
        if (iters > 1) {
            var br = bestMv >> 16;
            var bc = (short) bestMv;
            if (row != br && col != bc) {
                checkBetterFast(br, bc + diagCol);
                checkBetterFast(br + diagRow, bc);
            } else if (row == br && col != bc) {
                checkBetterFast(br + hstep, bc + diagCol);
                checkBetterFast(br - hstep, bc + diagCol);
                checkBetterFast(br - diagRow, bc);
            } else if (row != br) {
                checkBetterFast(br + diagRow, bc + hstep);
                checkBetterFast(br + diagRow, bc - hstep);
                checkBetterFast(br, bc - diagCol);
            }
        }
    }

    /**
     * Refines a full-sample vector to sub-sample precision
     * ({@code av1_find_best_sub_pixel_tree_pruned} or its {@code _more} variant).
     *
     * @param startRow the full-sample row offset of the start
     * @param startCol the full-sample column offset of the start
     * @param forcedStop the precision to stop at
     * @param allowHp whether eighth-sample precision is searched
     * @param prunedMore whether the most pruned variant runs
     * @param useCostList whether {@link #costList} from the full-sample search is valid
     * @return the best vector in eighth samples, packed as {@code row << 16 | (col & 0xFFFF)}
     */
    int subpelTree(int startRow, int startCol, int forcedStop, boolean allowHp, boolean prunedMore,
                   boolean useCostList) {
        var row = startRow * 8;
        var col = startCol * 8;
        bestMv = row << 16 | (col & 0xFFFF);
        bestErr = fullVarCost;
        if (forcedStop == SpeedFeatures.FULL_PEL) {
            return bestMv;
        }
        var hstep = 4;
        var cl = costList;
        var listValid = useCostList && cl[0] != Integer.MAX_VALUE && cl[1] != Integer.MAX_VALUE
                && cl[2] != Integer.MAX_VALUE && cl[3] != Integer.MAX_VALUE && cl[4] != Integer.MAX_VALUE;
        if (prunedMore) {
            if (listValid && cl[0] < cl[1] && cl[0] < cl[2] && cl[0] < cl[3] && cl[0] < cl[4]) {
                var ic = divideAndRound(cl[1] - cl[3], cl[1] - 2 * cl[0] + cl[3]);
                var ir = divideAndRound(cl[4] - cl[2], cl[4] - 2 * cl[0] + cl[2]);
                if (ir != 0 || ic != 0) {
                    checkBetterFast(row + ir * hstep, col + ic * hstep);
                }
            } else {
                twoLevelChecksFast(row, col, hstep, 1);
            }
        } else if (listValid) {
            var whichdir = (cl[1] < cl[3] ? 0 : 1) + (cl[2] < cl[4] ? 0 : 2);
            switch (whichdir) {
                case 0 -> {
                    checkBetterFast(row, col - hstep);
                    checkBetterFast(row + hstep, col);
                    checkBetterFast(row + hstep, col - hstep);
                }
                case 1 -> {
                    checkBetterFast(row, col + hstep);
                    checkBetterFast(row + hstep, col);
                    checkBetterFast(row + hstep, col + hstep);
                }
                case 2 -> {
                    checkBetterFast(row, col - hstep);
                    checkBetterFast(row - hstep, col);
                    checkBetterFast(row - hstep, col - hstep);
                }
                default -> {
                    checkBetterFast(row, col + hstep);
                    checkBetterFast(row - hstep, col);
                    checkBetterFast(row - hstep, col + hstep);
                }
            }
        } else {
            twoLevelChecksFast(row, col, hstep, 1);
        }
        if (forcedStop < SpeedFeatures.HALF_PEL) {
            hstep >>= 1;
            twoLevelChecksFast(bestMv >> 16, (short) bestMv, hstep, 1);
        }
        if (allowHp && forcedStop == SpeedFeatures.EIGHTH_PEL) {
            hstep >>= 1;
            twoLevelChecksFast(bestMv >> 16, (short) bestMv, hstep, 1);
        }
        return bestMv;
    }

    /**
     * Divides with rounding to the nearest integer away from zero ({@code divide_and_round}).
     *
     * @param n the numerator
     * @param d the denominator
     * @return the quotient
     */
    private static int divideAndRound(int n, int d) {
        if (d == 0) {
            return 0;
        }
        return ((n < 0) ^ (d < 0)) ? ((n - d / 2) / d) : ((n + d / 2) / d);
    }

    /**
     * Returns the variance of the projections of a reference against the source's
     * ({@code aom_vector_var}).
     *
     * @param refProj the reference projections
     * @param refOff the offset of the compared run
     * @param srcProj the source projections
     * @param bwl the base-2 logarithm of the run length divided by four
     * @return the variance
     */
    private static int vectorVar(int[] refProj, int refOff, int[] srcProj, int bwl) {
        var width = 4 << bwl;
        var sse = 0;
        var mean = 0;
        for (var i = 0; i < width; i++) {
            var diff = refProj[refOff + i] - srcProj[i];
            mean += diff;
            sse += diff * diff;
        }
        var meanAbs = (long) Math.abs(mean);
        return (int) (sse - ((meanAbs * meanAbs) >>> (bwl + 2)));
    }

    /**
     * Finds the best offset of a projection run ({@code av1_vector_match}).
     *
     * @param refProj the reference projections
     * @param srcProj the source projections
     * @param bwl the base-2 logarithm of the run length divided by four
     * @param searchSize the search radius
     * @return the offset from the center
     */
    private static int vectorMatch(int[] refProj, int[] srcProj, int bwl, int searchSize) {
        var bestSad = Integer.MAX_VALUE;
        var offset = 0;
        var bwSearch = searchSize << 1;
        for (var d = 0; d <= bwSearch; d += 16) {
            var s = vectorVar(refProj, d, srcProj, bwl);
            if (s < bestSad) {
                bestSad = s;
                offset = d;
            }
        }
        var center = offset;
        for (var step = 8; step >= 1; step >>= 1) {
            for (var d = -step; d <= step; d += 2 * step) {
                var pos = offset + d;
                if (pos < 0 || pos > bwSearch) {
                    continue;
                }
                var s = vectorVar(refProj, pos, srcProj, bwl);
                if (s < bestSad) {
                    bestSad = s;
                    center = pos;
                }
            }
            offset = center;
        }
        return center - (bwSearch >> 1);
    }

    /**
     * Estimates the motion of a 64x64 block from the row and column projections of the source and
     * the reference ({@code av1_int_pro_motion_estimation}).
     *
     * @param searchCol the horizontal search radius in samples
     * @param searchRow the vertical search radius in samples
     * @param px the block column in samples
     * @param py the block row in samples
     * @param width the picture width
     * @param height the picture height
     * @param out receives the SAD at zero motion in {@code [0]} and the best SAD in {@code [1]}
     * @return the best full-sample vector, packed as {@code row << 16 | (col & 0xFFFF)}
     */
    int intProMotion(int searchCol, int searchRow, int px, int py, int width, int height, int[] out) {
        var border = 128;
        if (px - searchCol < -border || px + searchCol > width + border) {
            searchCol = border;
        }
        if (py - searchRow < -border || py + searchRow > height + border) {
            searchRow = border;
        }
        var bwl = Integer.numberOfTrailingZeros(bw) - 2;
        var bhl = Integer.numberOfTrailingZeros(bh) - 2;
        var rowNorm = bhl + 1;
        var colNorm = 3 + (bw >> 5);
        var widthRef = (searchCol << 1) + bw;
        var heightRef = (searchRow << 1) + bh;
        dsp.intProRow(hbuf, ref, refOff - searchCol, refStride, widthRef, bh, rowNorm);
        dsp.intProCol(vbuf, ref, refOff - searchRow * refStride, refStride, bw, heightRef, colNorm);
        dsp.intProRow(srcHbuf, src, srcOff, srcStride, bw, bh, rowNorm);
        dsp.intProCol(srcVbuf, src, srcOff, srcStride, bw, bh, colNorm);
        var col = vectorMatch(hbuf, srcHbuf, bwl, searchCol);
        var row = vectorMatch(vbuf, srcVbuf, bhl, searchRow);
        var bestSad = sadAt(row, col);
        if (row != 0 || col != 0) {
            var zero = sadAt(0, 0);
            out[0] = zero;
            if (zero < bestSad) {
                row = 0;
                col = 0;
                bestSad = zero;
            }
        } else {
            out[0] = bestSad;
        }
        var thisRow = row;
        var thisCol = col;
        var up = sadAt(thisRow - 1, thisCol);
        var leftSad = sadAt(thisRow, thisCol - 1);
        var rightSad = sadAt(thisRow, thisCol + 1);
        var down = sadAt(thisRow + 1, thisCol);
        if (up < bestSad) {
            bestSad = up;
            row = thisRow - 1;
            col = thisCol;
        }
        if (leftSad < bestSad) {
            bestSad = leftSad;
            row = thisRow;
            col = thisCol - 1;
        }
        if (rightSad < bestSad) {
            bestSad = rightSad;
            row = thisRow;
            col = thisCol + 1;
        }
        if (down < bestSad) {
            bestSad = down;
            row = thisRow + 1;
            col = thisCol;
        }
        thisRow += up < down ? -1 : 1;
        thisCol += leftSad < rightSad ? -1 : 1;
        var diag = sadAt(thisRow, thisCol);
        if (bestSad > diag) {
            row = thisRow;
            col = thisCol;
            bestSad = diag;
        }
        row = Math.clamp(row, rowMin, rowMax);
        col = Math.clamp(col, colMin, colMax);
        out[1] = bestSad;
        return row << 16 | (col & 0xFFFF);
    }
}
