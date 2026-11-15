package com.github.auties00.smalt.video.vvc.internal;

/**
 * A coding unit of the picture being decoded, with the syntax values and derived modes that later coding units, the
 * reconstruction and the loop filters read.
 *
 * <p>Positions and sizes are in luma samples, also for coding units of a chroma tree.
 */
final class VvcCu {
    /**
     * Constructs a coding unit, set up by {@link #reset}.
     */
    VvcCu() {
    }

    /**
     * The prediction mode of an inter coding unit ({@code MODE_INTER}).
     */
    static final int MODE_INTER = 0;
    /**
     * The prediction mode of an intra coding unit ({@code MODE_INTRA}).
     */
    static final int MODE_INTRA = 1;
    /**
     * The prediction mode of an intra block copy coding unit ({@code MODE_IBC}).
     */
    static final int MODE_IBC = 2;

    /**
     * The tree type of a coding unit of the single tree ({@code SINGLE_TREE}).
     */
    static final int TREE_SINGLE = 0;
    /**
     * The tree type of a coding unit of the luma tree of a dual tree ({@code DUAL_TREE_LUMA}).
     */
    static final int TREE_LUMA = 1;
    /**
     * The tree type of a coding unit of the chroma tree of a dual tree ({@code DUAL_TREE_CHROMA}).
     */
    static final int TREE_CHROMA = 2;

    /**
     * {@code IntraSubPartitionsSplitType} without sub-partitions ({@code ISP_NO_SPLIT}).
     */
    static final int ISP_NONE = 0;
    /**
     * {@code IntraSubPartitionsSplitType} of horizontal sub-partitions ({@code ISP_HOR_SPLIT}).
     */
    static final int ISP_HOR = 1;
    /**
     * {@code IntraSubPartitionsSplitType} of vertical sub-partitions ({@code ISP_VER_SPLIT}).
     */
    static final int ISP_VER = 2;
    /**
     * The {@link #imv} value of half-sample AMVR; 0 is quarter-sample, 1 integer-sample and 2 four-sample precision
     * (for affine coding units 1 is sixteenth-sample and 2 integer-sample precision).
     */
    static final int IMV_HALF = 3;

    /**
     * The luma column of the top-left sample.
     */
    int x;
    /**
     * The luma row of the top-left sample.
     */
    int y;
    /**
     * The width in luma samples.
     */
    int w;
    /**
     * The height in luma samples.
     */
    int h;
    /**
     * The tree type: {@link #TREE_SINGLE}, {@link #TREE_LUMA} or {@link #TREE_CHROMA}.
     */
    int treeType;
    /**
     * The quadtree depth ({@code cqtDepth}).
     */
    int qtDepth;
    /**
     * The splits of the coding tree above the coding unit, 3 bits per depth from the CTU down.
     */
    long splitSeries;
    /**
     * The coding tree depth, quadtree and multi-type tree splits together.
     */
    int depth;
    /**
     * The multi-type tree depth ({@code mttDepth}).
     */
    int mttDepth;
    /**
     * The index of the slice holding the coding unit.
     */
    int slice;
    /**
     * {@code CuPredMode}: {@link #MODE_INTER}, {@link #MODE_INTRA} or {@link #MODE_IBC}.
     */
    int predMode;
    /**
     * {@code cu_skip_flag}.
     */
    boolean skip;
    /**
     * {@code general_merge_flag}.
     */
    boolean merge;
    /**
     * {@code inter_affine_flag}, or a merge candidate that is affine.
     */
    boolean affine;
    /**
     * {@code cu_affine_type_flag}: six-parameter affine motion.
     */
    boolean sixParam;
    /**
     * {@code mmvd_merge_flag}.
     */
    boolean mmvd;
    /**
     * {@code ciip_flag}.
     */
    boolean ciip;
    /**
     * Whether the coding unit uses geometric partitioning.
     */
    boolean gpm;
    /**
     * {@code merge_subblock_flag}.
     */
    boolean subblockMerge;
    /**
     * Whether the motion derivation selected the subblock-based temporal merge candidate.
     */
    boolean sbtmvp;
    /**
     * {@code intra_mip_flag}.
     */
    boolean mip;
    /**
     * {@code intra_mip_transposed_flag}.
     */
    boolean mipTranspose;
    /**
     * {@code intra_mip_mode}.
     */
    int mipMode;
    /**
     * The luma intra prediction mode (planar 0, DC 1, angular 2 to 66).
     */
    int intraLuma;
    /**
     * {@code intra_chroma_pred_mode} after binarization: 0 to 3 for the candidate list, 4 for DM, 5 to 7 for CCLM.
     */
    int intraChromaSyntax;
    /**
     * The derived chroma intra prediction mode ({@code IntraPredModeC}): 0 to 66, or 81 to 83 for CCLM.
     */
    int intraChroma;
    /**
     * {@code intra_luma_ref_idx}: the reference line of the luma prediction.
     */
    int multiRefIdx;
    /**
     * {@code IntraSubPartitionsSplitType}: {@link #ISP_NONE}, {@link #ISP_HOR} or {@link #ISP_VER}.
     */
    int ispMode;
    /**
     * {@code BdpcmFlag} and {@code BdpcmDir} of luma: 0 off, 1 horizontal, 2 vertical.
     */
    int bdpcmLuma;
    /**
     * {@code BdpcmFlag} and {@code BdpcmDir} of chroma: 0 off, 1 horizontal, 2 vertical.
     */
    int bdpcmChroma;
    /**
     * {@code lfnst_idx}.
     */
    int lfnstIdx;
    /**
     * {@code mts_idx}.
     */
    int mtsIdx;
    /**
     * The subblock transform: 0 off, 1 half split, 2 quarter split.
     */
    int sbtIdx;
    /**
     * {@code cu_sbt_pos_flag}.
     */
    int sbtPos;
    /**
     * {@code cu_sbt_horizontal_flag}.
     */
    boolean sbtHor;
    /**
     * {@code cu_sbt_quad_flag}.
     */
    boolean sbtQuad;
    /**
     * {@code QpY} of the coding unit.
     */
    int qpY;
    /**
     * The chroma QP offset index selected by {@code cu_chroma_qp_offset_idx}, 0 for none.
     */
    int chromaQpAdj;
    /**
     * {@code cu_coded_flag}: the coding unit has a residual.
     */
    boolean rootCbf;
    /**
     * Whether a luma transform block of the coding unit has coded coefficients.
     */
    boolean cbfY;
    /**
     * Whether a Cb transform block of the coding unit has coded coefficients.
     */
    boolean cbfCb;
    /**
     * Whether a Cr transform block of the coding unit has coded coefficients.
     */
    boolean cbfCr;
    /**
     * The motion vector resolution of {@code amvr_flag} and {@code amvr_precision_idx}: 0 for the default, then 1 for
     * integer, 2 for four-sample and 3 for half-sample resolution (VVdeC's numbering).
     */
    int imv;
    /**
     * {@code bcw_idx}.
     */
    int bcwIdx;
    /**
     * {@code inter_pred_idc} as a mask: bit 0 list 0, bit 1 list 1.
     */
    int interDir;
    /**
     * The reference index of each list, -1 when the list is unused.
     */
    final int[] refIdx = new int[2];
    /**
     * The motion vectors of each list: index 0 the translational vector or the top-left control point, 1 and 2 the
     * other control points of affine coding units.
     */
    final int[][] mvx = new int[2][3];
    /**
     * The vertical motion vectors of each list, laid out as the horizontal ones.
     */
    final int[][] mvy = new int[2][3];
    /**
     * {@code mvp_l0_flag} and {@code mvp_l1_flag}.
     */
    final int[] mvpIdx = new int[2];
    /**
     * {@code merge_idx}, or the merge index of the subblock, MMVD, CIIP or IBC merge list.
     */
    int mergeIdx;
    /**
     * {@code mmvd_distance_idx * 4 + mmvd_direction_idx}.
     */
    int mmvdIdx;
    /**
     * {@code merge_gpm_partition_idx}.
     */
    int gpmSplit;
    /**
     * {@code merge_gpm_idx0}.
     */
    int gpmIdx0;
    /**
     * {@code merge_gpm_idx1}.
     */
    int gpmIdx1;
    /**
     * {@code sym_mvd_flag}.
     */
    boolean smvd;
    /**
     * Whether the motion uses the half-sample interpolation filter ({@code hpelIfIdx}).
     */
    boolean halfPel;
    /**
     * Whether the mode constraint of the parent split limits the coding unit to intra prediction.
     */
    boolean modeIntraConstrained;
    /**
     * Whether the mode constraint of the parent split limits the coding unit to inter prediction.
     */
    boolean modeInterConstrained;
    /**
     * The number of motion subblocks in each direction, {@code NumSbX} and {@code NumSbY}, set by the motion
     * derivation of affine and subblock merge coding units and 1 otherwise.
     */
    int numSbX;
    /**
     * {@code NumSbY}, the number of motion subblocks vertically.
     */
    int numSbY;
    /**
     * The uni-directional motion of the two geometric partitions: the list (0 or 1), reference index and vector.
     */
    final int[] gpmList = new int[2];
    /**
     * The reference index of the motion of each geometric partition.
     */
    final int[] gpmRef = new int[2];
    /**
     * The horizontal vector of the motion of each geometric partition.
     */
    final int[] gpmMvx = new int[2];
    /**
     * The vertical vector of the motion of each geometric partition.
     */
    final int[] gpmMvy = new int[2];

    /**
     * Resets the syntax values to their inferred defaults.
     *
     * @param x the luma column of the top-left sample
     * @param y the luma row of the top-left sample
     * @param w the width in luma samples
     * @param h the height in luma samples
     * @param treeType the tree type
     */
    void reset(int x, int y, int w, int h, int treeType) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        this.treeType = treeType;
        predMode = MODE_INTRA;
        skip = false;
        merge = false;
        affine = false;
        sixParam = false;
        mmvd = false;
        ciip = false;
        gpm = false;
        subblockMerge = false;
        sbtmvp = false;
        mip = false;
        mipTranspose = false;
        mipMode = 0;
        intraLuma = 0;
        intraChromaSyntax = 4;
        intraChroma = 0;
        multiRefIdx = 0;
        ispMode = ISP_NONE;
        bdpcmLuma = 0;
        bdpcmChroma = 0;
        lfnstIdx = 0;
        mtsIdx = 0;
        sbtIdx = 0;
        sbtPos = 0;
        sbtHor = false;
        sbtQuad = false;
        chromaQpAdj = 0;
        rootCbf = false;
        cbfY = false;
        cbfCb = false;
        cbfCr = false;
        imv = 0;
        bcwIdx = 0;
        interDir = 0;
        refIdx[0] = -1;
        refIdx[1] = -1;
        mvpIdx[0] = 0;
        mvpIdx[1] = 0;
        mergeIdx = 0;
        mmvdIdx = 0;
        gpmSplit = 0;
        gpmIdx0 = 0;
        gpmIdx1 = 0;
        smvd = false;
        halfPel = false;
        numSbX = 1;
        numSbY = 1;
        for (var l = 0; l < 2; l++) {
            for (var k = 0; k < 3; k++) {
                mvx[l][k] = 0;
                mvy[l][k] = 0;
            }
        }
    }

    /**
     * {@return whether the coding unit carries luma samples}
     */
    boolean hasLuma() {
        return treeType != TREE_CHROMA;
    }

    /**
     * {@return whether the coding unit carries chroma samples}
     *
     * @param chromaFormat {@code sps_chroma_format_idc}
     */
    boolean hasChroma(int chromaFormat) {
        return chromaFormat != 0 && treeType != TREE_LUMA;
    }

    /**
     * {@return whether the coding unit belongs to a separate luma or chroma tree}
     */
    boolean separateTree() {
        return treeType != TREE_SINGLE;
    }
}
