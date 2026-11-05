package com.github.auties00.smalt.video.hevc.internal;

/**
 * Derives the motion vectors and reference indices of prediction units (ITU-T H.265 8.5.3.2):
 * merge mode with its spatial, temporal, combined bi-predictive and zero candidates, and motion
 * vector prediction with spatial and temporal predictors.
 *
 * <p>Instances are reused across slices and are not thread-safe.
 */
public final class MotionPredictor {
    /**
     * {@code l0CandIdx} of Table 8-7.
     */
    private static final int[] L0_CAND = {0, 1, 0, 2, 1, 2, 0, 3, 1, 3, 2, 3};

    /**
     * {@code l1CandIdx} of Table 8-7.
     */
    private static final int[] L1_CAND = {1, 0, 2, 0, 2, 1, 3, 0, 3, 1, 3, 2};

    /**
     * The motion of one prediction unit: the parsed syntax and the derived motion.
     */
    public static final class PuMotion {
        /**
         * {@code merge_flag}.
         */
        public boolean merge;

        /**
         * {@code merge_idx}.
         */
        public int mergeIdx;

        /**
         * {@code PartMode} of the coding unit.
         */
        public int partMode;

        /**
         * {@code predFlagL0} and {@code predFlagL1}.
         */
        public final boolean[] predFlag = new boolean[2];

        /**
         * {@code refIdxL0} and {@code refIdxL1}.
         */
        public final int[] refIdx = new int[2];

        /**
         * The horizontal motion vector differences of both lists.
         */
        public final int[] mvdX = new int[2];

        /**
         * The vertical motion vector differences of both lists.
         */
        public final int[] mvdY = new int[2];

        /**
         * {@code mvp_l0_flag} and {@code mvp_l1_flag}.
         */
        public final int[] mvpFlag = new int[2];

        /**
         * The horizontal motion vectors of both lists, in quarter luma samples.
         */
        public final int[] mvX = new int[2];

        /**
         * The vertical motion vectors of both lists, in quarter luma samples.
         */
        public final int[] mvY = new int[2];

        /**
         * Constructs an empty motion record.
         */
        public PuMotion() {
        }

        /**
         * Clears the record for a new prediction unit.
         */
        public void clear() {
            merge = false;
            mergeIdx = 0;
            for (var i = 0; i < 2; i++) {
                predFlag[i] = false;
                refIdx[i] = -1;
                mvdX[i] = 0;
                mvdY[i] = 0;
                mvpFlag[i] = 0;
                mvX[i] = 0;
                mvY[i] = 0;
            }
        }
    }

    /**
     * The merge candidates' list 0 motion vectors, packed.
     */
    private final int[] candMv0 = new int[5];

    /**
     * The merge candidates' list 1 motion vectors, packed.
     */
    private final int[] candMv1 = new int[5];

    /**
     * The merge candidates' list 0 reference indices, -1 when unused.
     */
    private final int[] candRef0 = new int[5];

    /**
     * The merge candidates' list 1 reference indices, -1 when unused.
     */
    private final int[] candRef1 = new int[5];

    /**
     * The output of {@link #temporal}: the packed motion vector.
     */
    private int temporalMv;

    /**
     * The 4x4 block indices of the spatial candidates of {@link #predictor}: A0, A1, then B0, B1
     * and B2.
     */
    private final int[] candidateBlocks = new int[5];

    /**
     * The availability of the spatial candidates of {@link #predictor}, in the order of
     * {@link #candidateBlocks}.
     */
    private final boolean[] candidateAvailable = new boolean[5];

    /**
     * The motion vector predictor candidate list of {@link #predictor}.
     */
    private final int[] mvpCandidates = new int[2];

    /**
     * The frame being decoded.
     */
    private HevcFrame frame;

    /**
     * The coding information of the picture being decoded.
     */
    private PictureState pic;

    /**
     * The slice segment header.
     */
    private HevcSliceHeader header;

    /**
     * The reference picture lists.
     */
    private HevcFrame[][] refList;

    /**
     * The picture order counts of the reference pictures, indexed {@code [list][refIdx]}.
     */
    private final int[][] refPoc = new int[2][16];

    /**
     * Whether each reference picture is a long-term reference, indexed {@code [list][refIdx]}.
     */
    private final boolean[][] refLongTerm = new boolean[2][16];

    /**
     * The collocated picture, or {@code null} when temporal prediction is off.
     */
    private HevcFrame colPic;

    /**
     * {@code NoBackwardPredFlag}.
     */
    private boolean noBackwardPred;

    /**
     * The availability of the neighbors of the coding tree being decoded or encoded.
     */
    private NeighborAvailability slice;

    /**
     * {@code TwoVersionsOfCurrDecPicFlag} of the slice, which restricts the bi-prediction of 8x8
     * prediction blocks with fractional motion vectors.
     */
    private boolean twoVersions;

    /**
     * Constructs a predictor.
     */
    public MotionPredictor() {
    }

    /**
     * Prepares the predictor for a slice.
     *
     * @param frame the frame being decoded
     * @param pic the coding information of the picture
     * @param header the slice segment header
     * @param refList the reference picture lists, or {@code null} for an intra slice
     * @param sliceIndex the index of the slice in the frame's slice tables, which hold the
     * marking of its references when the slice was received
     */
    public void setSlice(HevcFrame frame, PictureState pic, HevcSliceHeader header, HevcFrame[][] refList,
                         int sliceIndex) {
        this.frame = frame;
        this.pic = pic;
        this.header = header;
        this.refList = refList;
        this.colPic = null;
        this.twoVersions = header.pps.twoVersions(header.sps);
        if (refList == null) {
            return;
        }
        noBackwardPred = true;
        for (var list = 0; list < 2; list++) {
            for (var i = 0; i < header.numRefIdx[list]; i++) {
                var ref = refList[list][i];
                refPoc[list][i] = ref.poc;
                refLongTerm[list][i] = frame.sliceRefLongTerm[sliceIndex][list * 16 + i];
                if (ref.poc > frame.poc) {
                    noBackwardPred = false;
                }
            }
        }
        if (header.temporalMvpEnabled) {
            var list = header.collocatedFromL0 ? 0 : 1;
            if (header.collocatedRefIdx < header.numRefIdx[list]) {
                colPic = refList[list][header.collocatedRefIdx];
                if (colPic == frame) {
                    throw new com.github.auties00.smalt.MediaException.Malformed(
                            "the collocated picture is the current picture");
                }
                if (colPic.generated || colPic.width != frame.width || colPic.height != frame.height) {
                    colPic = null;
                }
            }
        }
    }

    /**
     * Derives the motion of a prediction unit (8.5.3.2.1).
     *
     * @param xCb the luma x of the coding block
     * @param yCb the luma y of the coding block
     * @param nCbS the coding block size
     * @param xPb the luma x of the prediction block
     * @param yPb the luma y of the prediction block
     * @param nPbW the prediction block width
     * @param nPbH the prediction block height
     * @param partIdx the partition index
     * @param m the parsed syntax, receiving the motion
     * @param slice the availability of neighbors
     */
    public void derive(int xCb, int yCb, int nCbS, int xPb, int yPb, int nPbW, int nPbH, int partIdx, PuMotion m,
                       NeighborAvailability slice) {
        this.slice = slice;
        if (m.merge) {
            merge(xCb, yCb, nCbS, xPb, yPb, nPbW, nPbH, partIdx, m);
        } else {
            for (var x = 0; x < 2; x++) {
                if (!m.predFlag[x]) {
                    continue;
                }
                if (m.refIdx[x] >= header.numRefIdx[x]) {
                    throw new com.github.auties00.smalt.MediaException.Malformed("reference index out of range");
                }
                var mvp = predictor(xCb, yCb, nCbS, xPb, yPb, nPbW, nPbH, partIdx, x, m.refIdx[x], m.mvpFlag[x],
                        m.partMode);
                if (integerMv(x, m.refIdx[x])) {
                    // equations 8-98 to 8-101: the difference counts whole samples
                    m.mvX[x] = (short) ((((short) mvp >> 2) + m.mvdX[x]) << 2);
                    m.mvY[x] = (short) ((((mvp >> 16) >> 2) + m.mvdY[x]) << 2);
                } else {
                    m.mvX[x] = (short) ((short) mvp + m.mvdX[x]);
                    m.mvY[x] = (short) ((mvp >> 16) + m.mvdY[x]);
                }
            }
        }
        if (twoVersions && m.predFlag[0] && m.predFlag[1] && nPbW <= 8 && nPbH <= 8) {
            var integer0 = ((m.mvX[0] | m.mvY[0]) & 3) == 0;
            var integer1 = ((m.mvX[1] | m.mvY[1]) & 3) == 0;
            var identical = m.mvX[0] == m.mvX[1] && m.mvY[0] == m.mvY[1]
                    && refPoc[0][m.refIdx[0]] == refPoc[1][m.refIdx[1]];
            if (!integer0 && !integer1 && !identical) {
                m.predFlag[1] = false;
                m.refIdx[1] = -1;
                m.mvX[1] = 0;
                m.mvY[1] = 0;
            }
        }
    }

    /**
     * Returns whether the motion vector of a list of the prediction unit has integer resolution:
     * with {@code use_integer_mv_flag}, or when it refers to the current picture.
     *
     * @param list the reference picture list
     * @param refIdx the reference index
     * @return {@code true} for whole-sample motion
     */
    private boolean integerMv(int list, int refIdx) {
        return header.useIntegerMv || header.currentPictureEntry[list][refIdx];
    }

    /**
     * Returns whether a neighboring prediction block is available (6.4.2) and not intra.
     *
     * @param xCb the luma x of the coding block
     * @param yCb the luma y of the coding block
     * @param nCbS the coding block size
     * @param xPb the luma x of the prediction block
     * @param yPb the luma y of the prediction block
     * @param nPbW the prediction block width
     * @param nPbH the prediction block height
     * @param partIdx the partition index
     * @param xNb the luma x of the neighbor
     * @param yNb the luma y of the neighbor
     * @return {@code true} when the neighbor's motion may be used
     */
    private boolean pbAvailable(int xCb, int yCb, int nCbS, int xPb, int yPb, int nPbW, int nPbH, int partIdx,
                                int xNb, int yNb) {
        var sameCb = xCb <= xNb && yCb <= yNb && xCb + nCbS > xNb && yCb + nCbS > yNb;
        boolean available;
        if (!sameCb) {
            available = slice.available(xPb, yPb, xNb, yNb);
        } else {
            available = !((nPbW << 1) == nCbS && (nPbH << 1) == nCbS && partIdx == 1 && yCb + nPbH <= yNb
                    && xCb + nPbW > xNb);
        }
        if (available && (pic.flags[(yNb >> 2) * pic.width4 + (xNb >> 2)] & PictureState.INTRA) != 0) {
            available = false;
        }
        return available;
    }

    /**
     * Derives the merge motion of a prediction unit (8.5.3.2.2 to 8.5.3.2.5).
     *
     * @param xCb the luma x of the coding block
     * @param yCb the luma y of the coding block
     * @param nCbS the coding block size
     * @param xPb the luma x of the prediction block
     * @param yPb the luma y of the prediction block
     * @param nPbW the prediction block width
     * @param nPbH the prediction block height
     * @param partIdx the partition index
     * @param m the motion record
     */
    private void merge(int xCb, int yCb, int nCbS, int xPb, int yPb, int nPbW, int nPbH, int partIdx, PuMotion m) {
        var origW = nPbW;
        var origH = nPbH;
        var partMode = m.partMode;
        var log2Par = header.pps.log2ParallelMergeLevel;
        if (log2Par > 2 && nCbS == 8) {
            xPb = xCb;
            yPb = yCb;
            nPbW = nCbS;
            nPbH = nCbS;
            partIdx = 0;
            partMode = SliceDecoder.PART_2Nx2N;
        }
        var w4 = frame.width4;
        var count = 0;
        var target = m.mergeIdx;

        var xA1 = xPb - 1;
        var yA1 = yPb + nPbH - 1;
        var availableA1 = pbAvailable(xCb, yCb, nCbS, xPb, yPb, nPbW, nPbH, partIdx, xA1, yA1)
                && !(xPb >> log2Par == xA1 >> log2Par && yPb >> log2Par == yA1 >> log2Par)
                && !((partMode == SliceDecoder.PART_Nx2N || partMode == SliceDecoder.PART_nLx2N
                || partMode == SliceDecoder.PART_nRx2N) && partIdx == 1);
        var a1 = availableA1 ? (yA1 >> 2) * w4 + (xA1 >> 2) : -1;
        if (availableA1) {
            count = add(count, a1);
        }
        var xB1 = xPb + nPbW - 1;
        var yB1 = yPb - 1;
        var availableB1 = pbAvailable(xCb, yCb, nCbS, xPb, yPb, nPbW, nPbH, partIdx, xB1, yB1)
                && !(xPb >> log2Par == xB1 >> log2Par && yPb >> log2Par == yB1 >> log2Par)
                && !((partMode == SliceDecoder.PART_2NxN || partMode == SliceDecoder.PART_2NxnU
                || partMode == SliceDecoder.PART_2NxnD) && partIdx == 1);
        var b1 = availableB1 ? (yB1 >> 2) * w4 + (xB1 >> 2) : -1;
        var flagB1 = availableB1 && !(availableA1 && sameMotion(a1, b1));
        if (flagB1) {
            count = add(count, b1);
        }
        var xB0 = xPb + nPbW;
        var yB0 = yPb - 1;
        var availableB0 = pbAvailable(xCb, yCb, nCbS, xPb, yPb, nPbW, nPbH, partIdx, xB0, yB0)
                && !(xPb >> log2Par == xB0 >> log2Par && yPb >> log2Par == yB0 >> log2Par);
        var b0 = availableB0 ? (yB0 >> 2) * w4 + (xB0 >> 2) : -1;
        var flagB0 = availableB0 && !(availableB1 && sameMotion(b1, b0));
        if (flagB0) {
            count = add(count, b0);
        }
        var xA0 = xPb - 1;
        var yA0 = yPb + nPbH;
        var availableA0 = pbAvailable(xCb, yCb, nCbS, xPb, yPb, nPbW, nPbH, partIdx, xA0, yA0)
                && !(xPb >> log2Par == xA0 >> log2Par && yPb >> log2Par == yA0 >> log2Par);
        var a0 = availableA0 ? (yA0 >> 2) * w4 + (xA0 >> 2) : -1;
        var flagA0 = availableA0 && !(availableA1 && sameMotion(a1, a0));
        if (flagA0) {
            count = add(count, a0);
        }
        var xB2 = xPb - 1;
        var yB2 = yPb - 1;
        var availableB2 = pbAvailable(xCb, yCb, nCbS, xPb, yPb, nPbW, nPbH, partIdx, xB2, yB2)
                && !(xPb >> log2Par == xB2 >> log2Par && yPb >> log2Par == yB2 >> log2Par);
        var b2 = availableB2 ? (yB2 >> 2) * w4 + (xB2 >> 2) : -1;
        var flagB2 = availableB2 && !(availableA1 && sameMotion(a1, b2)) && !(availableB1 && sameMotion(b1, b2))
                && count < 4;
        if (flagB2) {
            count = add(count, b2);
        }
        if (count <= target) {
            var colAvailable = false;
            var col0 = 0;
            var col1 = 0;
            var colRef1 = -1;
            if (temporal(xPb, yPb, nPbW, nPbH, 0, 0)) {
                colAvailable = true;
                col0 = temporalMv;
            }
            var ref0 = colAvailable ? 0 : -1;
            if (header.sliceType == HevcSliceHeader.B && temporal(xPb, yPb, nPbW, nPbH, 0, 1)) {
                colAvailable = true;
                col1 = temporalMv;
                colRef1 = 0;
            }
            if (colAvailable) {
                candMv0[count] = ref0 >= 0 ? col0 : 0;
                candRef0[count] = ref0;
                candMv1[count] = colRef1 >= 0 ? col1 : 0;
                candRef1[count] = colRef1;
                count++;
            }
        }
        var max = header.maxNumMergeCand;
        if (count <= target && header.sliceType == HevcSliceHeader.B && count > 1 && count < max) {
            var original = count;
            for (var combIdx = 0; combIdx < original * (original - 1) && count < max; combIdx++) {
                var l0 = L0_CAND[combIdx];
                var l1 = L1_CAND[combIdx];
                // 8.5.3.2.4 compares the references by picture order count alone, so two inter-layer
                // references of one access unit count as the same picture
                if (candRef0[l0] >= 0 && candRef1[l1] >= 0
                        && (refPoc[0][candRef0[l0]] != refPoc[1][candRef1[l1]]
                        || candMv0[l0] != candMv1[l1])) {
                    candMv0[count] = candMv0[l0];
                    candRef0[count] = candRef0[l0];
                    candMv1[count] = candMv1[l1];
                    candRef1[count] = candRef1[l1];
                    count++;
                }
            }
        }
        if (count <= target) {
            var numRefIdx = header.sliceType == HevcSliceHeader.P ? header.numRefIdx[0]
                    : Math.min(header.numRefIdx[0], header.numRefIdx[1]);
            var zeroIdx = 0;
            while (count <= target) {
                var ref = zeroIdx < numRefIdx ? zeroIdx : 0;
                candMv0[count] = 0;
                candRef0[count] = ref;
                candMv1[count] = 0;
                candRef1[count] = header.sliceType == HevcSliceHeader.P ? -1 : ref;
                count++;
                zeroIdx++;
            }
        }
        var mv0 = candMv0[target];
        var mv1 = candMv1[target];
        m.refIdx[0] = candRef0[target];
        m.refIdx[1] = candRef1[target];
        m.predFlag[0] = m.refIdx[0] >= 0;
        m.predFlag[1] = m.refIdx[1] >= 0;
        if (m.predFlag[0] && m.predFlag[1] && origW + origH == 12) {
            m.refIdx[1] = -1;
            m.predFlag[1] = false;
        }
        m.mvX[0] = m.predFlag[0] ? (short) mv0 : 0;
        m.mvY[0] = m.predFlag[0] ? mv0 >> 16 : 0;
        m.mvX[1] = m.predFlag[1] ? (short) mv1 : 0;
        m.mvY[1] = m.predFlag[1] ? mv1 >> 16 : 0;
        for (var x = 0; x < 2; x++) {
            if (m.predFlag[x] && integerMv(x, m.refIdx[x])) {
                // equations 8-124 and 8-125
                m.mvX[x] = (m.mvX[x] >> 2) << 2;
                m.mvY[x] = (m.mvY[x] >> 2) << 2;
            }
        }
    }

    /**
     * Appends the motion of a neighboring 4x4 block to the merge candidates.
     *
     * @param count the number of candidates so far
     * @param block the index of the neighbor in the motion field
     * @return the new number of candidates
     */
    private int add(int count, int block) {
        candRef0[count] = frame.ref0[block];
        candRef1[count] = frame.ref1[block];
        candMv0[count] = candRef0[count] >= 0 ? frame.mv0[block] : 0;
        candMv1[count] = candRef1[count] >= 0 ? frame.mv1[block] : 0;
        return count + 1;
    }

    /**
     * Returns whether two 4x4 blocks of the current picture have the same motion vectors and
     * reference indices.
     *
     * @param a the index of the first block
     * @param b the index of the second block
     * @return {@code true} when the motion is identical
     */
    private boolean sameMotion(int a, int b) {
        var f = frame;
        if (f.ref0[a] != f.ref0[b] || f.ref1[a] != f.ref1[b]) {
            return false;
        }
        return (f.ref0[a] < 0 || f.mv0[a] == f.mv0[b]) && (f.ref1[a] < 0 || f.mv1[a] == f.mv1[b]);
    }

    /**
     * Derives the motion vector predictor of a list (8.5.3.2.6 and 8.5.3.2.7).
     *
     * @param xCb the luma x of the coding block
     * @param yCb the luma y of the coding block
     * @param nCbS the coding block size
     * @param xPb the luma x of the prediction block
     * @param yPb the luma y of the prediction block
     * @param nPbW the prediction block width
     * @param nPbH the prediction block height
     * @param partIdx the partition index
     * @param x the list
     * @param refIdx the reference index of the list
     * @param mvpFlag {@code mvp_lX_flag}
     * @param partMode the partitioning mode
     * @return the packed predictor
     */
    private int predictor(int xCb, int yCb, int nCbS, int xPb, int yPb, int nPbW, int nPbH, int partIdx, int x,
                          int refIdx, int mvpFlag, int partMode) {
        var w4 = frame.width4;
        var y = 1 - x;
        var targetPoc = refPoc[x][refIdx];
        var targetLongTerm = refLongTerm[x][refIdx];
        var xA0 = xPb - 1;
        var yA0 = yPb + nPbH;
        var xA1 = xA0;
        var yA1 = yA0 - 1;
        var availableA0 = pbAvailable(xCb, yCb, nCbS, xPb, yPb, nPbW, nPbH, partIdx, xA0, yA0);
        var availableA1 = pbAvailable(xCb, yCb, nCbS, xPb, yPb, nPbW, nPbH, partIdx, xA1, yA1);
        var isScaled = availableA0 || availableA1;
        var availableFlagA = false;
        var mvA = 0;
        var blocks = candidateBlocks;
        var available = candidateAvailable;
        blocks[0] = (yA0 >> 2) * w4 + (xA0 >> 2);
        blocks[1] = (yA1 >> 2) * w4 + (xA1 >> 2);
        available[0] = availableA0;
        available[1] = availableA1;
        for (var k = 0; k < 2 && !availableFlagA; k++) {
            if (!available[k]) {
                continue;
            }
            var b = blocks[k];
            if (refOf(b, x) >= 0 && refPoc[x][refOf(b, x)] == targetPoc) {
                availableFlagA = true;
                mvA = mvOf(b, x);
            } else if (refOf(b, y) >= 0 && refPoc[y][refOf(b, y)] == targetPoc) {
                availableFlagA = true;
                mvA = mvOf(b, y);
            }
        }
        if (!availableFlagA) {
            for (var k = 0; k < 2 && !availableFlagA; k++) {
                if (!available[k]) {
                    continue;
                }
                var b = blocks[k];
                var list = -1;
                if (refOf(b, x) >= 0 && refLongTerm[x][refOf(b, x)] == targetLongTerm) {
                    list = x;
                } else if (refOf(b, y) >= 0 && refLongTerm[y][refOf(b, y)] == targetLongTerm) {
                    list = y;
                }
                if (list >= 0) {
                    availableFlagA = true;
                    mvA = mvOf(b, list);
                    var neighborPoc = refPoc[list][refOf(b, list)];
                    if (neighborPoc != targetPoc && !refLongTerm[list][refOf(b, list)] && !targetLongTerm) {
                        mvA = scale(mvA, frame.poc - neighborPoc, frame.poc - targetPoc);
                    }
                }
            }
        }
        var yB = yPb - 1;
        for (var k = 0; k < 3; k++) {
            var xB = k == 0 ? xPb + nPbW : k == 1 ? xPb + nPbW - 1 : xPb - 1;
            available[2 + k] = pbAvailable(xCb, yCb, nCbS, xPb, yPb, nPbW, nPbH, partIdx, xB, yB);
            blocks[2 + k] = (yB >> 2) * w4 + (xB >> 2);
        }
        var availableFlagB = false;
        var mvB = 0;
        for (var k = 2; k < 5 && !availableFlagB; k++) {
            if (!available[k]) {
                continue;
            }
            var b = blocks[k];
            if (refOf(b, x) >= 0 && refPoc[x][refOf(b, x)] == targetPoc) {
                availableFlagB = true;
                mvB = mvOf(b, x);
            } else if (refOf(b, y) >= 0 && refPoc[y][refOf(b, y)] == targetPoc) {
                availableFlagB = true;
                mvB = mvOf(b, y);
            }
        }
        if (!isScaled && availableFlagB) {
            availableFlagA = true;
            mvA = mvB;
        }
        if (!isScaled) {
            availableFlagB = false;
            for (var k = 2; k < 5 && !availableFlagB; k++) {
                if (!available[k]) {
                    continue;
                }
                var b = blocks[k];
                var list = -1;
                if (refOf(b, x) >= 0 && refLongTerm[x][refOf(b, x)] == targetLongTerm) {
                    list = x;
                } else if (refOf(b, y) >= 0 && refLongTerm[y][refOf(b, y)] == targetLongTerm) {
                    list = y;
                }
                if (list >= 0) {
                    availableFlagB = true;
                    mvB = mvOf(b, list);
                    var neighborPoc = refPoc[list][refOf(b, list)];
                    if (neighborPoc != targetPoc && !refLongTerm[list][refOf(b, list)] && !targetLongTerm) {
                        mvB = scale(mvB, frame.poc - neighborPoc, frame.poc - targetPoc);
                    }
                }
            }
        }
        var candidates = mvpCandidates;
        var count = 0;
        if (availableFlagA) {
            candidates[count++] = mvA;
            if (availableFlagB && mvA != mvB) {
                candidates[count++] = mvB;
            }
        } else if (availableFlagB) {
            candidates[count++] = mvB;
        }
        if (count < 2 && !(availableFlagA && availableFlagB && mvA != mvB)) {
            if (temporal(xPb, yPb, nPbW, nPbH, refIdx, x)) {
                candidates[count++] = temporalMv;
            }
        }
        while (count < 2) {
            candidates[count++] = 0;
        }
        return candidates[mvpFlag];
    }

    /**
     * Returns the reference index of a list of a 4x4 block of the current picture.
     *
     * @param block the block index
     * @param list the reference picture list, 0 or 1
     * @return the reference index, -1 when unused
     */
    private int refOf(int block, int list) {
        return list == 0 ? frame.ref0[block] : frame.ref1[block];
    }

    /**
     * Returns the packed motion vector of a list of a 4x4 block of the current picture.
     *
     * @param block the block index
     * @param list the reference picture list, 0 or 1
     * @return the packed motion vector
     */
    private int mvOf(int block, int list) {
        return list == 0 ? frame.mv0[block] : frame.mv1[block];
    }

    /**
     * Scales a packed motion vector by the ratio of two picture order count distances (equations
     * 8-179 to 8-183).
     *
     * @param mv the packed motion vector
     * @param td the distance of the vector's reference
     * @param tb the distance of the target reference
     * @return the scaled packed motion vector
     */
    private static int scale(int mv, int td, int tb) {
        td = Math.clamp(td, -128, 127);
        tb = Math.clamp(tb, -128, 127);
        if (td == 0) {
            return mv;
        }
        var tx = (16384 + (Math.abs(td) >> 1)) / td;
        var factor = Math.clamp((tb * tx + 32) >> 6, -4096, 4095);
        var mx = scaleComponent((short) mv, factor);
        var my = scaleComponent(mv >> 16, factor);
        return (my << 16) | (mx & 0xFFFF);
    }

    /**
     * Scales one motion vector component (equation 8-181).
     *
     * @param component the motion vector component to scale
     * @param factor {@code distScaleFactor}
     * @return the scaled component
     */
    private static int scaleComponent(int component, int factor) {
        var product = factor * component;
        var magnitude = (Math.abs(product) + 127) >> 8;
        return Math.clamp(product < 0 ? -magnitude : magnitude, -32768, 32767);
    }

    /**
     * Derives the temporal motion vector predictor (8.5.3.2.8 and 8.5.3.2.9), leaving it in
     * {@link #temporalMv}.
     *
     * @param xPb the luma x of the prediction block
     * @param yPb the luma y of the prediction block
     * @param nPbW the prediction block width
     * @param nPbH the prediction block height
     * @param refIdx the target reference index
     * @param x the target list
     * @return whether the predictor is available
     */
    private boolean temporal(int xPb, int yPb, int nPbW, int nPbH, int refIdx, int x) {
        if (colPic == null) {
            return false;
        }
        var log2Ctb = header.sps.log2CtbSize;
        colPic.awaitDecodedRows(((yPb >> log2Ctb) + 1) << log2Ctb);
        var xBr = xPb + nPbW;
        var yBr = yPb + nPbH;
        if (yPb >> log2Ctb == yBr >> log2Ctb && yBr < header.sps.height && xBr < header.sps.width) {
            if (collocated(((xBr >> 4) << 4), ((yBr >> 4) << 4), refIdx, x)) {
                return true;
            }
        }
        var xCtr = xPb + (nPbW >> 1);
        var yCtr = yPb + (nPbH >> 1);
        return collocated(((xCtr >> 4) << 4), ((yCtr >> 4) << 4), refIdx, x);
    }

    /**
     * Derives the collocated motion vector of the block covering a location of the collocated
     * picture (8.5.3.2.9), leaving it in {@link #temporalMv}.
     *
     * @param xCol the luma x in the collocated picture
     * @param yCol the luma y in the collocated picture
     * @param refIdx the target reference index
     * @param x the target list
     * @return whether the collocated motion vector is available
     */
    private boolean collocated(int xCol, int yCol, int refIdx, int x) {
        var col = colPic;
        var block = (yCol >> 2) * col.width4 + (xCol >> 2);
        var r0 = col.ref0[block];
        var r1 = col.ref1[block];
        if (r0 < 0 && r1 < 0) {
            return false;
        }
        int mvCol;
        int refIdxCol;
        int listCol;
        if (r0 < 0) {
            mvCol = col.mv1[block];
            refIdxCol = r1;
            listCol = 1;
        } else if (r1 < 0) {
            mvCol = col.mv0[block];
            refIdxCol = r0;
            listCol = 0;
        } else if (noBackwardPred) {
            listCol = x;
            mvCol = x == 0 ? col.mv0[block] : col.mv1[block];
            refIdxCol = x == 0 ? r0 : r1;
        } else {
            listCol = header.collocatedFromL0 ? 1 : 0;
            mvCol = listCol == 0 ? col.mv0[block] : col.mv1[block];
            refIdxCol = listCol == 0 ? r0 : r1;
        }
        // the collocated picture may be an inter-layer reference picture of another coding tree block size
        var colSps = col.sps;
        var ctb = (yCol >> colSps.log2CtbSize) * colSps.widthInCtbs + (xCol >> colSps.log2CtbSize);
        if (col.ctbSlice == null || ctb >= col.ctbSlice.length) {
            return false;
        }
        var sliceIdx = col.ctbSlice[ctb];
        if (sliceIdx < 0 || sliceIdx >= col.sliceCount) {
            return false;
        }
        var entry = listCol * 16 + refIdxCol;
        var colLongTerm = col.sliceRefLongTerm[sliceIdx][entry];
        if (refLongTerm[x][refIdx] != colLongTerm) {
            return false;
        }
        var colPocDiff = col.poc - col.sliceRefPoc[sliceIdx][entry];
        var currPocDiff = frame.poc - refPoc[x][refIdx];
        if (refLongTerm[x][refIdx] || colPocDiff == currPocDiff) {
            temporalMv = mvCol;
        } else {
            temporalMv = scale(mvCol, colPocDiff, currPocDiff);
        }
        return true;
    }
}
