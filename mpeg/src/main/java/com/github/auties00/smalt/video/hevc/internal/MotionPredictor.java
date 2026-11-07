/* Ports the 3D-HEVC merging candidate list, inter-view candidates and disparity vector derivation of HTM-16.3
   (source/Lib/TLibCommon/TComDataCU.cpp). Copyright (c) 2010-2016, ITU/ISO/IEC; BSD 3-Clause License.
   Modified for Smalt. */
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
         * {@code IvMcFlag}: whether the motion is that of an inter-view predicted merging
         * candidate (3D-HEVC).
         */
        public boolean ivMc;

        /**
         * The width of the sub-block partitions of a prediction unit whose motion is given per
         * sub-block ({@code subPbMotionFlag}, 3D-HEVC), or 0 for one motion.
         */
        public int subPbWidth;

        /**
         * The height of the sub-block partitions, when {@link #subPbWidth} is not 0.
         */
        public int subPbHeight;

        /**
         * The packed list 0 motion vectors of the sub-block partitions, in raster order.
         */
        public final int[] subMv0 = new int[MAX_SUB_BLOCKS];

        /**
         * The packed list 1 motion vectors of the sub-block partitions.
         */
        public final int[] subMv1 = new int[MAX_SUB_BLOCKS];

        /**
         * The list 0 reference indices of the sub-block partitions, -1 when unused.
         */
        public final byte[] subRef0 = new byte[MAX_SUB_BLOCKS];

        /**
         * The list 1 reference indices of the sub-block partitions, -1 when unused.
         */
        public final byte[] subRef1 = new byte[MAX_SUB_BLOCKS];

        /**
         * {@code iv_res_pred_weight_idx} of the coding unit (3D-HEVC).
         */
        public int residualWeight;

        /**
         * {@code IlluCompFlag} of the coding unit (3D-HEVC).
         */
        public boolean illuminationCompensation;

        /**
         * {@code DispVec} of the coding unit, packed (3D-HEVC).
         */
        public int dispVec;

        /**
         * {@code RefViewIdx} of the coding unit, -1 when it has no disparity vector (3D-HEVC).
         */
        public int refViewIdx = -1;

        /**
         * The luma x of the coding block (3D-HEVC).
         */
        public int cuX;

        /**
         * The luma y of the coding block (3D-HEVC).
         */
        public int cuY;

        /**
         * Whether the samples above the coding block are available to the illumination
         * compensation.
         */
        public boolean icAbove;

        /**
         * Whether the samples left of the coding block are available to the illumination
         * compensation.
         */
        public boolean icLeft;

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
            ivMc = false;
            subPbWidth = 0;
            subPbHeight = 0;
            residualWeight = 0;
            illuminationCompensation = false;
        }

        /**
         * Copies the motion of a sub-block partition into another record as the motion of a
         * prediction unit, with the coding unit variables of this one.
         *
         * @param index the raster index of the sub-block partition
         * @param target the record receiving the motion
         */
        public void subBlock(int index, PuMotion target) {
            target.clear();
            target.partMode = partMode;
            target.residualWeight = residualWeight;
            target.dispVec = dispVec;
            target.refViewIdx = refViewIdx;
            target.cuX = cuX;
            target.cuY = cuY;
            target.refIdx[0] = subRef0[index];
            target.refIdx[1] = subRef1[index];
            target.predFlag[0] = subRef0[index] >= 0;
            target.predFlag[1] = subRef1[index] >= 0;
            target.mvX[0] = target.predFlag[0] ? (short) subMv0[index] : 0;
            target.mvY[0] = target.predFlag[0] ? subMv0[index] >> 16 : 0;
            target.mvX[1] = target.predFlag[1] ? (short) subMv1[index] : 0;
            target.mvY[1] = target.predFlag[1] ? subMv1[index] >> 16 : 0;
        }
    }

    /**
     * The most sub-block partitions of a prediction unit: a 64x64 block of 8x8 partitions.
     */
    public static final int MAX_SUB_BLOCKS = 64;

    /**
     * The merge candidates' list 0 motion vectors, packed.
     */
    private final int[] candMv0 = new int[6];

    /**
     * The merge candidates' list 1 motion vectors, packed.
     */
    private final int[] candMv1 = new int[6];

    /**
     * The merge candidates' list 0 reference indices, -1 when unused.
     */
    private final int[] candRef0 = new int[6];

    /**
     * The merge candidates' list 1 reference indices, -1 when unused.
     */
    private final int[] candRef1 = new int[6];

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
        this.views = header.interView;
        this.mergeTemporal3d = false;
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
        if (m.merge && views != null) {
            merge3d(xCb, yCb, nCbS, xPb, yPb, nPbW, nPbH, partIdx, m);
        } else if (m.merge) {
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
    static int scale(int mv, int td, int tb) {
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
        if (mergeTemporal3d) {
            return collocated3d(xCol, yCol, refIdx, x);
        }
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

    /**
     * The variables of the 3D-HEVC tools the slice derived at its start, or {@code null} for a
     * slice decoded without them.
     */
    private InterViewContext views;

    /**
     * Whether {@link #collocated} derives the collocated motion vectors of a merging candidate
     * with the alternative target reference indices and the view scaling of 3D-HEVC
     * (I.8.5.3.2.6).
     */
    private boolean mergeTemporal3d;

    /**
     * The reference index of the temporal merging candidate {@link #collocated3d} derived, which
     * the alternative target reference index may replace.
     */
    private int temporalRefIdx;

    /**
     * {@code DispVec} of the coding unit being decoded, packed.
     */
    private int cuDispVec;

    /**
     * {@code DispRefVec} of the coding unit being decoded, packed.
     */
    private int cuDispRefVec;

    /**
     * {@code RefViewIdx} of the coding unit being decoded, -1 when no disparity vector exists.
     */
    private int cuRefViewIdx = -1;

    /**
     * The merging candidates of the 3D-HEVC list in their order of insertion: availability.
     */
    private final boolean[] extAvailable = new boolean[EXT_COUNT];

    /**
     * The packed list 0 motion vectors of the 3D-HEVC candidates.
     */
    private final int[] extMv0 = new int[EXT_COUNT];

    /**
     * The packed list 1 motion vectors of the 3D-HEVC candidates.
     */
    private final int[] extMv1 = new int[EXT_COUNT];

    /**
     * The list 0 reference indices of the 3D-HEVC candidates, -1 when unused.
     */
    private final int[] extRef0 = new int[EXT_COUNT];

    /**
     * The list 1 reference indices of the 3D-HEVC candidates, -1 when unused.
     */
    private final int[] extRef1 = new int[EXT_COUNT];

    /**
     * The position of each spatial 3D-HEVC candidate in the base merging candidate list.
     */
    private final int[] spatialIndex = new int[EXT_COUNT];

    /**
     * The motion vectors of the inter-view candidates: IvMC, IvDC, IvMCShift and IvDCShift, two
     * lists each.
     */
    private final int[] ivMv = new int[8];

    /**
     * The reference indices of the inter-view candidates, indexed like {@link #ivMv}.
     */
    private final int[] ivRef = new int[8];

    /**
     * Whether each entry of {@link #ivMv} is available.
     */
    private final boolean[] ivAvailable = new boolean[8];

    /**
     * The sub-block partition motion of the inter-view predicted merging candidate: list 0
     * vectors.
     */
    private final int[] spMv0 = new int[MAX_SUB_BLOCKS];

    /**
     * The sub-block partition motion: list 1 vectors.
     */
    private final int[] spMv1 = new int[MAX_SUB_BLOCKS];

    /**
     * The sub-block partition motion: list 0 reference indices.
     */
    private final byte[] spRef0 = new byte[MAX_SUB_BLOCKS];

    /**
     * The sub-block partition motion: list 1 reference indices.
     */
    private final byte[] spRef1 = new byte[MAX_SUB_BLOCKS];

    /**
     * The width of the sub-block partitions of {@link #spMv0}.
     */
    private int spWidth;

    /**
     * The height of the sub-block partitions of {@link #spMv0}.
     */
    private int spHeight;

    /**
     * The motion vector and reference index {@link #interViewMotion} found for one list.
     */
    private int foundMv;

    /**
     * The reference index {@link #interViewMotion} found, -1 for none.
     */
    private int foundRef;

    /**
     * The positions of the 3D-HEVC merging candidates in their insertion order (I.8.5.3.2.7):
     * the texture candidate, the inter-view candidate, A1, B1, the view synthesis candidate, B0,
     * the disparity candidate, A0, B2 and the shifted inter-view candidate.
     */
    private static final int EXT_T = 0;

    /**
     * The inter-view predicted merging candidate.
     */
    private static final int EXT_IV = 1;

    /**
     * Spatial candidate A1.
     */
    private static final int EXT_A1 = 2;

    /**
     * Spatial candidate B1.
     */
    private static final int EXT_B1 = 3;

    /**
     * Spatial candidate B0.
     */
    private static final int EXT_B0 = 5;

    /**
     * The disparity information merging candidate.
     */
    private static final int EXT_DI = 6;

    /**
     * Spatial candidate A0.
     */
    private static final int EXT_A0 = 7;

    /**
     * Spatial candidate B2.
     */
    private static final int EXT_B2 = 8;

    /**
     * The shifted inter-view or shifted disparity candidate.
     */
    private static final int EXT_SHIFT = 9;

    /**
     * The number of candidate positions.
     */
    private static final int EXT_COUNT = 10;

    /**
     * Derives the disparity vector of a coding unit of a texture layer (I.8.5.5) and records it
     * for the coding unit: from the disparity motion vectors of the temporal neighbors in the
     * candidate pictures, of the spatial neighbors A1 and B1, from the disparity of a skipped
     * neighbor predicted from the inter-view candidate, or the default reference view with a zero
     * vector; with {@code DepthRefEnabledFlag}, {@code DispRefVec} is the vector refined from the
     * depth of the reference view when depth-based block partitioning is enabled too, and the vector
     * without its vertical component otherwise. As in the reference decoder, the neighbors of a
     * coding unit are never excluded by the parallel merge level.
     *
     * @param xCb the luma x of the coding block
     * @param yCb the luma y of the coding block
     * @param nCbS the coding block size
     * @param slice the availability of neighbors
     */
    public void deriveDisparity(int xCb, int yCb, int nCbS, NeighborAvailability slice) {
        var v = views;
        var view = v.tools.viewIdx;
        cuDispVec = 0;
        cuRefViewIdx = -1;
        var found = false;
        if (header.temporalMvpEnabled) {
            var xRef = ((xCb + (nCbS >> 1)) >> 4) << 4;
            var yRef = ((yCb + (nCbS >> 1)) >> 4) << 4;
            for (var i = 0; i < v.numDdvCandPics && !found; i++) {
                found = temporalDisparity(v.ddvCandPic[i], xRef, yRef);
            }
        }
        if (!found) {
            var w4 = frame.width4;
            var lists = header.sliceType == HevcSliceHeader.B ? 2 : 1;
            var mcpView = -1;
            var mcpVec = 0;
            for (var n = 0; n < 2 && !found; n++) {
                var xN = n == 0 ? xCb - 1 : xCb + nCbS - 1;
                var yN = n == 0 ? yCb + nCbS - 1 : yCb - 1;
                if (!slice.available(xCb, yCb, xN, yN)) {
                    continue;
                }
                var block = (yN >> 2) * w4 + (xN >> 2);
                var flags = pic.flags[block];
                if ((flags & PictureState.INTRA) != 0) {
                    continue;
                }
                var mcp = n == 0 || yN >> header.sps.log2CtbSize == yCb >> header.sps.log2CtbSize;
                for (var list = 0; list < lists; list++) {
                    var ref = refOf(block, list);
                    if (ref < 0) {
                        continue;
                    }
                    var refView = v.refViewIdx[list][ref];
                    if (refView != view) {
                        cuDispVec = mvOf(block, list);
                        cuRefViewIdx = refView;
                        found = true;
                        break;
                    }
                    if (mcp && mcpView < 0 && (flags & PictureState.IV_MC) != 0 && (flags & PictureState.SKIP) != 0) {
                        mcpVec = pic.dispRefVec[block];
                        mcpView = pic.refViewIdx[block];
                    }
                }
            }
            if (!found && mcpView >= 0) {
                cuDispVec = mcpVec;
                cuRefViewIdx = mcpView;
                found = true;
            }
            if (!found && v.dispAvail) {
                cuRefViewIdx = v.defaultRefViewIdx;
            }
        }
        cuDispRefVec = cuDispVec;
        if (v.tools.depthRef) {
            // the reference decoder refines the vector from the depth view only when depth-based block
            // partitioning is enabled too; otherwise the refined vector is the vector without its vertical
            // component
            cuDispRefVec = v.tools.dbbp && cuRefViewIdx >= 0 ? refinedDisparity(xCb, yCb, nCbS) : cuDispVec & 0xFFFF;
        }
        pic.prepareViews();
        var w4 = pic.width4;
        var start = (yCb >> 2) * w4 + (xCb >> 2);
        var blocks = nCbS >> 2;
        for (var j = 0; j < blocks; j++) {
            var row = start + j * w4;
            java.util.Arrays.fill(pic.dispRefVec, row, row + blocks, cuDispRefVec);
            java.util.Arrays.fill(pic.refViewIdx, row, row + blocks, (byte) cuRefViewIdx);
        }
    }

    /**
     * Derives the disparity vector of a coding unit of a depth layer (I.8.5.6): the disparity of
     * the middle depth value towards the default reference view. As in the reference decoder, a
     * slice without an inter-view reference picture of its access unit takes a zero vector towards
     * view 0.
     */
    public void deriveDepthDisparity() {
        cuDispVec = 0;
        cuRefViewIdx = 0;
        if (views.dispAvail) {
            cuRefViewIdx = views.defaultRefViewIdx;
            var table = views.tools.depthToDisparityB(cuRefViewIdx);
            if (table == null) {
                throw new com.github.auties00.smalt.MediaException.Malformed(
                        "depth layer disparity without the camera parameters of its reference view");
            }
            cuDispVec = table[1 << (header.sps.bitDepthLuma - 1)] & 0xFFFF;
        }
        cuDispRefVec = cuDispVec;
    }

    /**
     * {@return {@code DispVec} of the coding unit, packed}
     */
    public int disparity() {
        return cuDispVec;
    }

    /**
     * {@return {@code RefViewIdx} of the coding unit, -1 when it has no disparity vector}
     */
    public int disparityView() {
        return cuRefViewIdx;
    }

    /**
     * Derives a disparity vector from the collocated block of a candidate picture (I.8.5.5.2):
     * a motion vector of the block that refers to a picture of another view, when the lists of
     * the slice hold a picture of that view.
     *
     * @param cand the candidate picture
     * @param xRef the luma x of the 16x16 block in the candidate picture
     * @param yRef the luma y of the 8x8 block
     * @return whether a disparity vector was found, then in {@link #cuDispVec}
     */
    private boolean temporalDisparity(HevcFrame cand, int xRef, int yRef) {
        if (cand.generated || cand.width != frame.width || cand.height != frame.height) {
            return false;
        }
        cand.awaitDecodedRows(yRef + 8);
        var block = (yRef >> 2) * cand.width4 + (xRef >> 2);
        var slice = sliceOf(cand, xRef, yRef);
        if (slice < 0) {
            return false;
        }
        for (var list = 0; list < 2; list++) {
            var ref = list == 0 ? cand.ref0[block] : cand.ref1[block];
            if (ref < 0) {
                continue;
            }
            var refView = views.viewOfReference(cand, slice, list * 16 + ref);
            if (refView < 0 || refView == cand.viewIdx || !views.listsHoldView(refView, header.numRefIdx)) {
                continue;
            }
            cuDispVec = list == 0 ? cand.mv0[block] : cand.mv1[block];
            cuRefViewIdx = refView;
            return true;
        }
        return false;
    }

    /**
     * Returns the slice of another picture covering a luma location.
     *
     * @param picture the decoded picture to read
     * @param x the luma x
     * @param y the luma y
     * @return the slice index in the picture's slice tables, or -1 when none was decoded there
     */
    private static int sliceOf(HevcFrame picture, int x, int y) {
        var sps = picture.sps;
        var ctb = (y >> sps.log2CtbSize) * sps.widthInCtbs + (x >> sps.log2CtbSize);
        if (picture.ctbSlice == null || ctb >= picture.ctbSlice.length) {
            return -1;
        }
        var slice = picture.ctbSlice[ctb];
        return slice >= 0 && slice < picture.sliceCount ? slice : -1;
    }

    /**
     * Refines the disparity vector of a coding unit from the depth picture of its reference view
     * (I.8.5.7 with {@code partIdc} 0): the disparity of the largest of the four corner samples of
     * the depth block the vector points to.
     *
     * @param xCb the luma x of the coding block
     * @param yCb the luma y of the coding block
     * @param nCbS the coding block size
     * @return {@code DispRefVec}, packed
     */
    private int refinedDisparity(int xCb, int yCb, int nCbS) {
        var depth = views.depthPicture[cuRefViewIdx];
        var table = views.tools.depthToDisparityB(cuRefViewIdx);
        if (depth == null || table == null) {
            // the reference decoder keeps the vector when the depth picture is missing
            return cuDispVec;
        }
        var maxDepth = maxCornerDepth(depth, xCb, yCb, nCbS, nCbS, cuDispVec);
        return table[Math.min(maxDepth, table.length - 1)] & 0xFFFF;
    }

    /**
     * Returns the largest of the four corner samples of a block of a depth picture displaced by a
     * disparity vector, the coordinates clamped to the picture (equations I-300 to I-312).
     *
     * @param depth the depth picture
     * @param x the luma x of the block in the current picture
     * @param y the luma y of the block
     * @param width the block width
     * @param height the block height
     * @param dispVec the packed disparity vector
     * @return the largest depth value
     */
    static int maxCornerDepth(HevcFrame depth, int x, int y, int width, int height, int dispVec) {
        var dx = ((short) dispVec + 2) >> 2;
        var dy = ((dispVec >> 16) + 2) >> 2;
        var x0 = Math.clamp(x + dx, 0, depth.width - 1);
        var y0 = Math.clamp(y + dy, 0, depth.height - 1);
        var x1 = Math.clamp(x + width - 1 + dx, 0, depth.width - 1);
        var y1 = Math.clamp(y + height - 1 + dy, 0, depth.height - 1);
        depth.awaitRows(y1 + 1);
        var max = Math.max(Math.max(depthSample(depth, x0, y0), depthSample(depth, x0, y1)),
                Math.max(depthSample(depth, x1, y0), depthSample(depth, x1, y1)));
        return max;
    }

    /**
     * Returns a luma sample of a decoded picture.
     *
     * @param picture the decoded picture to read
     * @param x the luma x
     * @param y the luma y
     * @return the sample value
     */
    private static int depthSample(HevcFrame picture, int x, int y) {
        var index = y * picture.width + x;
        return picture.y8 != null ? picture.y8[index] & 0xFF : picture.y[index] & 0xFFFF;
    }

    /**
     * Derives the merge motion of a prediction unit of a layer decoded with the process of Annex I
     * (I.8.5.3.2.7): the merging candidate list of the base specification, without the combined
     * bi-predictive candidates once five candidates are found and with the temporal candidate of
     * I.8.5.3.2.5, extended with the inter-view, disparity and shifted candidates, as the reference
     * decoder builds it.
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
    private void merge3d(int xCb, int yCb, int nCbS, int xPb, int yPb, int nPbW, int nPbH, int partIdx, PuMotion m) {
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
        var tools = views.tools;
        var w4 = frame.width4;
        var max = header.maxNumMergeCand;
        var count = 0;
        java.util.Arrays.fill(extAvailable, false);

        var xA1 = xPb - 1;
        var yA1 = yPb + nPbH - 1;
        var availableA1 = pbAvailable(xCb, yCb, nCbS, xPb, yPb, nPbW, nPbH, partIdx, xA1, yA1)
                && !(xPb >> log2Par == xA1 >> log2Par && yPb >> log2Par == yA1 >> log2Par)
                && !((partMode == SliceDecoder.PART_Nx2N || partMode == SliceDecoder.PART_nLx2N
                || partMode == SliceDecoder.PART_nRx2N) && partIdx == 1);
        var a1 = availableA1 ? (yA1 >> 2) * w4 + (xA1 >> 2) : -1;
        if (availableA1) {
            spatialIndex[EXT_A1] = count;
            extAvailable[EXT_A1] = true;
            count = add(count, a1);
        }
        var xB1 = xPb + nPbW - 1;
        var yB1 = yPb - 1;
        var availableB1 = pbAvailable(xCb, yCb, nCbS, xPb, yPb, nPbW, nPbH, partIdx, xB1, yB1)
                && !(xPb >> log2Par == xB1 >> log2Par && yPb >> log2Par == yB1 >> log2Par)
                && !((partMode == SliceDecoder.PART_2NxN || partMode == SliceDecoder.PART_2NxnU
                || partMode == SliceDecoder.PART_2NxnD) && partIdx == 1);
        var b1 = availableB1 ? (yB1 >> 2) * w4 + (xB1 >> 2) : -1;
        if (availableB1 && !(availableA1 && sameMotion(a1, b1))) {
            spatialIndex[EXT_B1] = count;
            extAvailable[EXT_B1] = true;
            count = add(count, b1);
        }
        var xB0 = xPb + nPbW;
        var yB0 = yPb - 1;
        var availableB0 = pbAvailable(xCb, yCb, nCbS, xPb, yPb, nPbW, nPbH, partIdx, xB0, yB0)
                && !(xPb >> log2Par == xB0 >> log2Par && yPb >> log2Par == yB0 >> log2Par);
        var b0 = availableB0 ? (yB0 >> 2) * w4 + (xB0 >> 2) : -1;
        if (availableB0 && !(availableB1 && sameMotion(b1, b0))) {
            spatialIndex[EXT_B0] = count;
            extAvailable[EXT_B0] = true;
            count = add(count, b0);
        }
        var xA0 = xPb - 1;
        var yA0 = yPb + nPbH;
        var availableA0 = pbAvailable(xCb, yCb, nCbS, xPb, yPb, nPbW, nPbH, partIdx, xA0, yA0)
                && !(xPb >> log2Par == xA0 >> log2Par && yPb >> log2Par == yA0 >> log2Par);
        var a0 = availableA0 ? (yA0 >> 2) * w4 + (xA0 >> 2) : -1;
        if (availableA0 && !(availableA1 && sameMotion(a1, a0))) {
            spatialIndex[EXT_A0] = count;
            extAvailable[EXT_A0] = true;
            count = add(count, a0);
        }
        var xB2 = xPb - 1;
        var yB2 = yPb - 1;
        var availableB2 = pbAvailable(xCb, yCb, nCbS, xPb, yPb, nPbW, nPbH, partIdx, xB2, yB2)
                && !(xPb >> log2Par == xB2 >> log2Par && yPb >> log2Par == yB2 >> log2Par);
        var b2 = availableB2 ? (yB2 >> 2) * w4 + (xB2 >> 2) : -1;
        if (availableB2 && !(availableA1 && sameMotion(a1, b2)) && !(availableB1 && sameMotion(b1, b2))
                && count < 4) {
            spatialIndex[EXT_B2] = count;
            extAvailable[EXT_B2] = true;
            count = add(count, b2);
        }
        var numSpatial = count;
        if (count < max) {
            mergeTemporal3d = true;
            var colAvailable = false;
            var col0 = 0;
            var col1 = 0;
            var colRef0 = -1;
            var colRef1 = -1;
            if (temporal(xPb, yPb, nPbW, nPbH, 0, 0)) {
                colAvailable = true;
                col0 = temporalMv;
                colRef0 = temporalRefIdx;
            }
            if (header.sliceType == HevcSliceHeader.B && temporal(xPb, yPb, nPbW, nPbH, 0, 1)) {
                colAvailable = true;
                col1 = temporalMv;
                colRef1 = temporalRefIdx;
            }
            mergeTemporal3d = false;
            if (colAvailable) {
                candMv0[count] = colRef0 >= 0 ? col0 : 0;
                candRef0[count] = colRef0;
                candMv1[count] = colRef1 >= 0 ? col1 : 0;
                candRef1[count] = colRef1;
                count++;
            }
        }
        if (header.sliceType == HevcSliceHeader.B && count > 1 && count < max && count < 5) {
            var original = count;
            for (var combIdx = 0; combIdx < original * (original - 1) && count < max; combIdx++) {
                var l0 = L0_CAND[combIdx];
                var l1 = L1_CAND[combIdx];
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
        var numRefIdx = header.sliceType == HevcSliceHeader.P ? header.numRefIdx[0]
                : Math.min(header.numRefIdx[0], header.numRefIdx[1]);
        var zeroIdx = 0;
        while (count < max) {
            var ref = zeroIdx < numRefIdx ? zeroIdx : 0;
            candMv0[count] = 0;
            candRef0[count] = ref;
            candMv1[count] = 0;
            candRef1[count] = header.sliceType == HevcSliceHeader.P ? -1 : ref;
            count++;
            zeroIdx++;
        }
        for (var k = EXT_A1; k <= EXT_B2; k++) {
            if (extAvailable[k]) {
                var i = spatialIndex[k];
                extMv0[k] = candMv0[i];
                extMv1[k] = candMv1[i];
                extRef0[k] = candRef0[i];
                extRef1[k] = candRef1[i];
            }
        }
        var ivMvPred = tools.ivDiMc && nPbW + nPbH > 12;
        var ivSub = false;
        if (ivMvPred && cuRefViewIdx >= 0) {
            interViewCandidates(xPb, yPb, nPbW, nPbH, m.illuminationCompensation);
            var ivDir = direction(0);
            if (ivDir != 0) {
                setExt(EXT_IV, 0);
                if (!tools.depth) {
                    if (extAvailable[EXT_A1] && sameExt(EXT_IV, EXT_A1)) {
                        extAvailable[EXT_A1] = false;
                    } else if (extAvailable[EXT_B1] && sameExt(EXT_IV, EXT_B1)) {
                        extAvailable[EXT_B1] = false;
                    }
                    ivSub = true;
                }
            }
            if (!tools.depth && direction(1) != 0) {
                setExt(EXT_DI, 2);
                if (extAvailable[EXT_A1] && sameExt(EXT_DI, EXT_A1) || extAvailable[EXT_B1] && sameExt(EXT_DI, EXT_B1)) {
                    extAvailable[EXT_DI] = false;
                }
            }
            if (!tools.depth) {
                if (direction(2) != 0) {
                    setExt(EXT_SHIFT, 4);
                    if (extAvailable[EXT_IV] && sameExt(EXT_SHIFT, EXT_IV)) {
                        extAvailable[EXT_SHIFT] = false;
                    }
                } else if (direction(3) != 0) {
                    setExt(EXT_SHIFT, 6);
                }
            }
        }
        var target = m.mergeIdx;
        var chosen = -1;
        var chosenBase = -1;
        var shiftFromIv = direction(2) != 0;
        var n = 0;
        for (var k = 0; k < EXT_COUNT && n < max; k++) {
            if (extAvailable[k]) {
                if (n == target) {
                    chosen = k;
                }
                n++;
            }
        }
        if (chosen < 0) {
            for (var i = numSpatial; i < max && n < max; i++) {
                if (n == target) {
                    chosenBase = i;
                }
                n++;
            }
        }
        int mv0;
        int mv1;
        if (chosen >= 0) {
            m.refIdx[0] = extRef0[chosen];
            m.refIdx[1] = extRef1[chosen];
            mv0 = extMv0[chosen];
            mv1 = extMv1[chosen];
        } else {
            m.refIdx[0] = candRef0[chosenBase];
            m.refIdx[1] = candRef1[chosenBase];
            mv0 = candMv0[chosenBase];
            mv1 = candMv1[chosenBase];
        }
        m.ivMc = chosen == EXT_IV || chosen == EXT_SHIFT && shiftFromIv;
        m.predFlag[0] = m.refIdx[0] >= 0;
        m.predFlag[1] = m.refIdx[1] >= 0;
        // a prediction unit sharing the merging candidates of its coding unit takes the motion of the
        // candidate, which is that of its single sub-block partition
        if (chosen == EXT_IV && ivSub && origW == nPbW && origH == nPbH) {
            m.subPbWidth = spWidth;
            m.subPbHeight = spHeight;
            var blocks = (nPbW / spWidth) * (nPbH / spHeight);
            System.arraycopy(spMv0, 0, m.subMv0, 0, blocks);
            System.arraycopy(spMv1, 0, m.subMv1, 0, blocks);
            System.arraycopy(spRef0, 0, m.subRef0, 0, blocks);
            System.arraycopy(spRef1, 0, m.subRef1, 0, blocks);
        }
        if (m.predFlag[0] && m.predFlag[1] && origW + origH == 12) {
            m.refIdx[1] = -1;
            m.predFlag[1] = false;
        }
        m.mvX[0] = m.predFlag[0] ? (short) mv0 : 0;
        m.mvY[0] = m.predFlag[0] ? mv0 >> 16 : 0;
        m.mvX[1] = m.predFlag[1] ? (short) mv1 : 0;
        m.mvY[1] = m.predFlag[1] ? mv1 >> 16 : 0;
    }

    /**
     * {@return the prediction direction of an inter-view candidate: bit 0 for list 0, bit 1 for
     * list 1}
     *
     * @param candidate 0 for IvMC, 1 for IvDC, 2 for IvMCShift, 3 for IvDCShift
     */
    private int direction(int candidate) {
        return (ivAvailable[2 * candidate] ? 1 : 0) | (ivAvailable[2 * candidate + 1] ? 2 : 0);
    }

    /**
     * Copies an inter-view candidate into a position of the 3D-HEVC candidate list.
     *
     * @param position the index in the 3D-HEVC candidate list
     * @param first the index of the candidate's list 0 entry in {@link #ivMv}
     */
    private void setExt(int position, int first) {
        extAvailable[position] = true;
        extRef0[position] = ivAvailable[first] ? ivRef[first] : -1;
        extMv0[position] = ivAvailable[first] ? ivMv[first] : 0;
        extRef1[position] = ivAvailable[first + 1] ? ivRef[first + 1] : -1;
        extMv1[position] = ivAvailable[first + 1] ? ivMv[first + 1] : 0;
    }

    /**
     * Returns whether two positions of the 3D-HEVC candidate list hold the same motion: the same
     * prediction direction, and the same motion vector and reference index in each list used.
     *
     * @param a the first position
     * @param b the second position
     * @return {@code true} when the motion is identical
     */
    private boolean sameExt(int a, int b) {
        if ((extRef0[a] >= 0) != (extRef0[b] >= 0) || (extRef1[a] >= 0) != (extRef1[b] >= 0)) {
            return false;
        }
        return (extRef0[a] < 0 || extRef0[a] == extRef0[b] && extMv0[a] == extMv0[b])
                && (extRef1[a] < 0 || extRef1[a] == extRef1[b] && extMv1[a] == extMv1[b]);
    }

    /**
     * Derives the inter-view candidates of a prediction unit (I.8.5.3.2.8 to I.8.5.3.2.10 and
     * I.8.5.3.2.12): the inter-view predicted candidate with its sub-block partition motion, the
     * shifted inter-view candidate, and the disparity information and shifted disparity
     * candidates, into {@link #ivMv}, {@link #ivRef} and {@link #ivAvailable}.
     *
     * @param xPb the luma x of the prediction block
     * @param yPb the luma y of the prediction block
     * @param nPbW the prediction block width
     * @param nPbH the prediction block height
     * @param illuminationCompensation {@code IlluCompFlag} of the coding unit
     */
    private void interViewCandidates(int xPb, int yPb, int nPbW, int nPbH, boolean illuminationCompensation) {
        java.util.Arrays.fill(ivAvailable, false);
        var tools = views.tools;
        var depth = tools.depth;
        var view = cuRefViewIdx;
        var dv = tools.depthRef ? cuDispRefVec & 0xFFFF : cuDispVec;
        var dvX = (short) dv;
        var dvY = dv >> 16;
        var base = depth ? views.depthPicture[view] : views.texture[view];
        if (!illuminationCompensation && base != null && !base.generated) {
            if (!depth) {
                var size = 1 << tools.log2SubPbSize;
                var whole = nPbW % size != 0 || nPbH % size != 0;
                spWidth = whole ? nPbW : size;
                spHeight = whole ? nPbH : size;
                var columns = nPbW / spWidth;
                var rows = nPbH / spHeight;
                var xCenter = xPb + (columns >> 1) * spWidth + (spWidth >> 1);
                var yCenter = yPb + (rows >> 1) * spHeight + (spHeight >> 1);
                var defaultDir = 0;
                for (var list = 0; list < 2; list++) {
                    if (interViewMotion(base, xCenter + ((dvX + 2) >> 2), yCenter + ((dvY + 2) >> 2), list)) {
                        ivAvailable[list] = true;
                        ivMv[list] = foundMv;
                        ivRef[list] = foundRef;
                        defaultDir |= 1 << list;
                    }
                }
                if (defaultDir != 0) {
                    var index = 0;
                    for (var y = 0; y < rows; y++) {
                        for (var x = 0; x < columns; x++) {
                            var xs = xPb + x * spWidth + (spWidth >> 1) + ((dvX + 2) >> 2);
                            var ys = yPb + y * spHeight + (spHeight >> 1) + ((dvY + 2) >> 2);
                            spRef0[index] = -1;
                            spRef1[index] = -1;
                            spMv0[index] = 0;
                            spMv1[index] = 0;
                            if (interViewMotion(base, xs, ys, 0)) {
                                spRef0[index] = (byte) foundRef;
                                spMv0[index] = foundMv;
                            }
                            if (interViewMotion(base, xs, ys, 1)) {
                                spRef1[index] = (byte) foundRef;
                                spMv1[index] = foundMv;
                            }
                            if (spRef0[index] < 0 && spRef1[index] < 0) {
                                spRef0[index] = (byte) (ivAvailable[0] ? ivRef[0] : -1);
                                spMv0[index] = ivAvailable[0] ? ivMv[0] : 0;
                                spRef1[index] = (byte) (ivAvailable[1] ? ivRef[1] : -1);
                                spMv1[index] = ivAvailable[1] ? ivMv[1] : 0;
                            }
                            index++;
                        }
                    }
                }
                for (var list = 0; list < 2; list++) {
                    var xs = xPb + (nPbW >> 1) + ((dvX + nPbW * 2 + 2) >> 2);
                    var ys = yPb + (nPbH >> 1) + ((dvY + nPbH * 2 + 2) >> 2);
                    if (interViewMotion(base, xs, ys, list)) {
                        ivAvailable[4 + list] = true;
                        ivMv[4 + list] = foundMv;
                        ivRef[4 + list] = foundRef;
                    }
                }
            } else {
                for (var list = 0; list < 2; list++) {
                    if (interViewMotion(base, xPb + (nPbW >> 1) + ((dvX + 2) >> 2),
                            yPb + (nPbH >> 1) + ((dvY + 2) >> 2), list)) {
                        ivAvailable[list] = true;
                        ivMv[list] = foundMv;
                        ivRef[list] = foundRef;
                    }
                }
            }
        }
        if (!depth) {
            for (var list = 0; list < 2; list++) {
                for (var i = 0; i < header.numRefIdx[list]; i++) {
                    if (refPoc[list][i] == frame.poc && views.refViewIdx[list][i] == view) {
                        var x = (short) dv;
                        ivAvailable[2 + list] = true;
                        ivRef[2 + list] = i;
                        ivMv[2 + list] = x & 0xFFFF;
                        ivAvailable[6 + list] = true;
                        ivRef[6 + list] = i;
                        ivMv[6 + list] = (x + 4) & 0xFFFF;
                        break;
                    }
                }
            }
        }
    }

    /**
     * Derives the motion of one list of an inter-view candidate from the 8x8 block of the
     * reference view picture covering a location (I.8.5.3.2.10): the first motion vector of the
     * block, of the same list then of the other one, that refers to a temporal reference picture
     * whose picture order count a reference picture of the list has, as the reference decoder
     * derives it.
     *
     * @param base the picture of the reference view
     * @param x the luma x, before the clamping to the picture
     * @param y the luma y, before the clamping
     * @param list the list of the candidate
     * @return whether the motion was found, then in {@link #foundMv} and {@link #foundRef}
     */
    private boolean interViewMotion(HevcFrame base, int x, int y, int list) {
        var xRef = (Math.clamp(x, 0, frame.width - 1) >> 3) << 3;
        var yRef = (Math.clamp(y, 0, frame.height - 1) >> 3) << 3;
        base.awaitDecodedRows(yRef + 8);
        var block = (yRef >> 2) * base.width4 + (xRef >> 2);
        var slice = sliceOf(base, xRef, yRef);
        if (slice < 0 || base.ref0[block] < 0 && base.ref1[block] < 0) {
            return false;
        }
        for (var k = 0; k < 2; k++) {
            var y2 = k == 0 ? list : 1 - list;
            var ref = y2 == 0 ? base.ref0[block] : base.ref1[block];
            if (ref < 0) {
                continue;
            }
            var refPocBase = base.sliceRefPoc[slice][y2 * 16 + ref];
            if (refPocBase == frame.poc) {
                continue;
            }
            for (var i = 0; i < header.numRefIdx[list]; i++) {
                if (refPoc[list][i] == refPocBase) {
                    foundMv = y2 == 0 ? base.mv0[block] : base.mv1[block];
                    foundRef = i;
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Derives the collocated motion vector of a merging candidate (I.8.5.3.2.6), leaving it in
     * {@link #temporalMv} and its reference index in {@link #temporalRefIdx}: when the long-term
     * markings of the target and the collocated reference differ, the alternative target reference
     * index (I.8.3.4) replaces the target; between long-term references, with
     * {@code IvMvScalEnabledFlag}, the vector is scaled by the view identifier distances.
     *
     * @param xCol the luma x in the collocated picture
     * @param yCol the luma y in the collocated picture
     * @param refIdx the target reference index
     * @param x the target list
     * @return whether the collocated motion vector is available
     */
    private boolean collocated3d(int xCol, int yCol, int refIdx, int x) {
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
        var sliceIdx = sliceOf(col, xCol, yCol);
        if (sliceIdx < 0) {
            return false;
        }
        var entry = listCol * 16 + refIdxCol;
        var colLongTerm = col.sliceRefLongTerm[sliceIdx][entry];
        var curLongTerm = refLongTerm[x][refIdx];
        if (curLongTerm != colLongTerm) {
            var alt = views.altRefIdx[x];
            if (alt <= 0) {
                return false;
            }
            refIdx = alt;
            curLongTerm = refLongTerm[x][refIdx];
            if (curLongTerm != colLongTerm) {
                return false;
            }
        }
        temporalRefIdx = refIdx;
        if (curLongTerm) {
            temporalMv = mvCol;
            var colRefView = views.viewOfReference(col, sliceIdx, entry);
            if (views.tools.ivMvScal && colRefView >= 0) {
                var currDiff = views.viewId - views.refViewId[x][refIdx];
                var colViewId = col.viewIdx < views.viewIdVal.length ? views.viewIdVal[col.viewIdx] : 0;
                var colRefViewId = colRefView < views.viewIdVal.length ? views.viewIdVal[colRefView] : 0;
                var colDiff = colViewId - colRefViewId;
                if (currDiff != 0 && colDiff != 0 && currDiff != colDiff) {
                    temporalMv = scale(mvCol, colDiff, currDiff);
                }
            }
            return true;
        }
        var colPocDiff = col.poc - col.sliceRefPoc[sliceIdx][entry];
        var currPocDiff = frame.poc - refPoc[x][refIdx];
        temporalMv = colPocDiff == currPocDiff ? mvCol : scale(mvCol, colPocDiff, currPocDiff);
        return true;
    }
}
