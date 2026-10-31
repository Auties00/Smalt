/* Ports libvpx 1.15.1 (vp9/encoder/vp9_speed_features.c). Copyright (c) 2010, The WebM Project authors; BSD 3-Clause
   License. Modified for Smalt. */
package com.github.auties00.smalt.video.vp9.internal;

import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.BLOCK_16X16;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.BLOCK_32X32;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.BLOCK_4X4;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.BLOCK_64X64;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.BLOCK_8X8;

/**
 * Holds the search settings of one good-quality or best-quality VP9 frame: which partitions,
 * modes, transform sizes, filters and motion vectors the rate-distortion search tests at each
 * speed.
 *
 * @implNote This implementation follows libvpx's {@code set_good_speed_feature_framesize_dependent}
 * and {@code set_good_speed_feature_framesize_independent} for speeds 0 to 4 and the best-quality
 * defaults of {@code vp9_set_speed_features_framesize_independent} for the best quality. It does
 * not port the features of the neural network partition pruning
 * ({@code rd_ml_partition}), the film content tuning, the graphics content mesh searches, and the
 * shortcuts {@code enhanced_full_pixel_motion_search}, {@code use_downsampled_sad},
 * {@code prune_ref_frame_for_rect_partitions}, {@code adaptive_interp_filter_search},
 * {@code early_term_interp_search_plane_rd}, {@code prune_single_mode_based_on_mv_diff_mode_rate},
 * {@code schedule_mode_search}, {@code cb_partition_search}, {@code allow_skip_recode},
 * {@code motion_field_mode_search}, {@code use_fast_coef_costing} and {@code use_lp32x32fdct}; its
 * searches at a speed are therefore not those of libvpx step for step.
 */
final class Vp9GoodSpeed {
    /**
     * The transform size search that tests every size with a rate-distortion cost
     * ({@code USE_FULL_RD}).
     */
    static final int TX_FULL_RD = 0;

    /**
     * The transform size search that always uses the largest size ({@code USE_LARGESTALL}).
     */
    static final int TX_LARGEST = 1;

    /**
     * Skips the oblique intra modes far from the best intra mode so far
     * ({@code FLAG_SKIP_INTRA_DIRMISMATCH}).
     */
    static final int SKIP_INTRA_DIRMISMATCH = 1;

    /**
     * Skips the oblique intra modes once an inter mode is the best ({@code FLAG_SKIP_INTRA_BESTINTER}).
     */
    static final int SKIP_INTRA_BESTINTER = 2;

    /**
     * Skips compound modes once an intra mode is the best ({@code FLAG_SKIP_COMP_BESTINTRA}).
     */
    static final int SKIP_COMP_BESTINTRA = 4;

    /**
     * Skips the intra modes other than DC on flat blocks ({@code FLAG_SKIP_INTRA_LOWVAR}).
     */
    static final int SKIP_INTRA_LOWVAR = 8;

    /**
     * Stops the mode search once an inter mode's distortion is below the quantizer's noise
     * ({@code FLAG_EARLY_TERMINATE}).
     */
    static final int EARLY_TERMINATE = 16;

    /**
     * Recodes no frame.
     */
    static final int RECODE_NONE = 0;

    /**
     * Recodes key frames and frames over the largest frame size ({@code ALLOW_RECODE_KFMAXBW}).
     */
    static final int RECODE_KF_MAX_BW = 1;

    /**
     * Recodes key, golden and alternate reference frames ({@code ALLOW_RECODE_KFARFGF}).
     */
    static final int RECODE_KF_ARF_GF = 2;

    /**
     * Recodes key, golden and alternate reference frames and, in a two-pass encode, the first
     * inter frame of each golden frame group ({@code ALLOW_RECODE_FIRST}).
     */
    static final int RECODE_FIRST = 3;

    /**
     * The good-quality speed, from 0 to 4.
     */
    int speed;

    /**
     * Whether the frame is coded at the best quality, whose search ignores the speed.
     */
    boolean best;

    /**
     * Whether the full-pixel motion search of shown frames starts from a range that follows the
     * largest vector of the previous frame ({@code auto_mv_step_size}).
     */
    boolean autoMvStepSize;

    /**
     * Whether the sub-8x8 motion search predicts each block's vector from the previous block's
     * ({@code oxcf.mode != BEST}).
     */
    boolean sub8x8MvFromPrevious;

    /**
     * Whether the quarters of the temporal filter's blocks are searched with the exhaustive mesh
     * search rather than the stepwise search ({@code temporal_filter_search_method}).
     */
    boolean meshTemporalFilter;

    /**
     * Whether rectangular partitions are left out outside {@link #squareOnlyLow} to
     * {@link #squareOnlyHigh}.
     */
    boolean useSquarePartitionOnly;

    /**
     * The largest block size that still tests rectangular partitions when
     * {@link #useSquarePartitionOnly} is set.
     */
    int squareOnlyHigh;

    /**
     * The smallest block size that still tests rectangular partitions when
     * {@link #useSquarePartitionOnly} is set.
     */
    int squareOnlyLow;

    /**
     * Whether rectangular partitions are skipped once the whole block wins over its split.
     */
    boolean lessRectangularCheck;

    /**
     * The distortion of a 64x64 block below which the partition search stops splitting.
     */
    long breakoutDist;

    /**
     * The rate per sample below which, with a small distortion, the partition search stops.
     */
    int breakoutRate;

    /**
     * Whether the partition range follows the neighbors' block sizes ({@code RELAXED_NEIGHBORING_MIN_MAX}).
     */
    boolean autoMinMaxPartition;

    /**
     * The smallest block size the automatic partition range goes down to.
     */
    int autoPartitionMinLimit;

    /**
     * The transform size search, {@link #TX_FULL_RD} or {@link #TX_LARGEST}.
     */
    int txSizeSearch;

    /**
     * Whether the transform size search stops once a smaller size costs more.
     */
    boolean txSizeSearchBreakout;

    /**
     * The mode search shortcuts, a combination of the {@code SKIP_*} flags and {@link #EARLY_TERMINATE}.
     */
    int modeSearchSkipFlags;

    /**
     * The mode index after which the references other than the best's are skipped.
     */
    int modeSkipStart;

    /**
     * The adaptive mode threshold level, 0 for none.
     */
    int adaptiveRdThresh;

    /**
     * The intra luma modes tested at each transform size, a bit mask of modes.
     */
    final int[] intraYModeMask = new int[4];

    /**
     * The intra chroma modes tested at each transform size, a bit mask of modes.
     */
    final int[] intraUvModeMask = new int[4];

    /**
     * The largest block size that tests intra modes in inter frames.
     */
    int maxIntraBsize;

    /**
     * The full-pixel motion search method, a {@link Vp9RtSpeed} search method.
     */
    int searchMethod;

    /**
     * The extra checks per step of the sub-pixel search.
     */
    int subpelIters;

    /**
     * The source variance below which the interpolation filter is not searched.
     */
    int disableFilterSearchVarThresh;

    /**
     * Whether the interpolation filter search is limited to a checkerboard of the blocks.
     */
    boolean checkerboardFilterSearch;

    /**
     * Whether the distortion of complex blocks may be measured in the transform domain
     * ({@code allow_txfm_domain_distortion}).
     */
    boolean transformDomainDistortion;

    /**
     * The logarithm of the source variance from which a block's distortion is measured in the
     * transform domain ({@code tx_domain_thresh}).
     */
    double transformDomainThreshold;

    /**
     * How the rate-distortion search decides whether to optimize the quantized coefficients of a
     * transform block, a {@code TRELLIS_*} method ({@code trellis_opt_tx_rd.method}).
     */
    int trellisMethod;

    /**
     * The threshold of {@link #trellisMethod} ({@code trellis_opt_tx_rd.thresh}).
     */
    double trellisThreshold;

    /**
     * The trellis method that never optimizes ({@code DISABLE_TRELLIS_OPT}).
     */
    static final int TRELLIS_DISABLED = 0;

    /**
     * The trellis method that always optimizes ({@code ENABLE_TRELLIS_OPT}).
     */
    static final int TRELLIS_ALWAYS = 1;

    /**
     * The trellis method that optimizes blocks whose source variance is low
     * ({@code ENABLE_TRELLIS_OPT_TX_RD_SRC_VAR}).
     */
    static final int TRELLIS_SOURCE_VARIANCE = 2;

    /**
     * The trellis method that optimizes blocks whose residual is small for the quantizer step
     * ({@code ENABLE_TRELLIS_OPT_TX_RD_RESIDUAL_MSE}).
     */
    static final int TRELLIS_RESIDUAL_MSE = 3;

    /**
     * The trellis thresholds of the source variance method by speed, libvpx's
     * {@code qopt_thresholds}.
     */
    private static final double[] TRELLIS_THRESHOLDS = {99.0, 12.0, 10.0, 4.0, 2.0};

    /**
     * The transform domain thresholds by speed, libvpx's {@code tx_dom_thresholds}.
     */
    private static final double[] TRANSFORM_DOMAIN_THRESHOLDS = {99.0, 14.0, 12.0, 8.0, 4.0};

    /**
     * Whether the inter mode search breaks out on modeled costs far above the best.
     */
    boolean useRdBreakout;

    /**
     * Whether the motion search starts nearer to the predicted vector for small blocks.
     */
    boolean adaptiveMotionSearch;

    /**
     * Whether the fixed-vector modes of poor references are skipped.
     */
    boolean referenceMasking;

    /**
     * Whether alternate reference overlays search the alternate reference only.
     */
    boolean altRefSearchFp;

    /**
     * Whether the golden reference is skipped when its prediction is far worse than the last's.
     */
    boolean adaptiveModeSearch;

    /**
     * The frames that are coded again when they miss their size target, a {@code RECODE_*} level.
     */
    int recodeLoop;

    /**
     * The tolerance below a frame's size target before it is recoded, in percent.
     */
    int recodeToleranceLow;

    /**
     * The tolerance above a frame's size target before it is recoded, in percent.
     */
    int recodeToleranceHigh;

    /**
     * The level of the joint motion search of the compound new motion vector mode
     * ({@code comp_inter_joint_search_iter_level}): 0 searches up to four times, 1 twice for
     * blocks up to 16x16, 2 not at all.
     */
    int compoundJointSearchIterations;

    /**
     * How the search of a block smaller than 8x8 picks its interpolation filter
     * ({@code adaptive_pred_interp_filter}): 0 searches the filters, 1 takes the filter of the
     * whole 8x8 block.
     */
    int adaptivePredInterpFilter;

    /**
     * The sub-pixel motion search of new vectors, a {@code SUBPEL_*} method
     * ({@code subpel_search_method}).
     */
    int subpelSearchMethod;

    /**
     * The full sub-pixel tree search ({@code SUBPEL_TREE}).
     */
    static final int SUBPEL_TREE = 0;

    /**
     * The tree search whose first step follows the integer cost list ({@code SUBPEL_TREE_PRUNED}).
     */
    static final int SUBPEL_TREE_PRUNED = 1;

    /**
     * The tree search whose first step goes to the modeled minimum of the integer cost list
     * ({@code SUBPEL_TREE_PRUNED_MORE}).
     */
    static final int SUBPEL_TREE_PRUNED_MORE = 2;

    /**
     * The taps of the filters the sub-pixel motion search predicts with
     * ({@code use_accurate_subpel_search}): 8 or 4 for the regular eight-tap or the four-tap
     * kernels, 2 for the bilinear filters of the variance kernels.
     */
    int subpelSearchTaps;

    /**
     * Sets the features of a frame.
     *
     * @param speed the good-quality speed, from 0 to 4
     * @param best whether the frame is coded at the best quality, which ignores {@code speed}
     * @param keyFrame whether the frame is a key frame
     * @param boosted whether the frame is a key, golden or alternate reference frame
     * @param width the frame width
     * @param height the frame height
     */
    void set(int speed, boolean best, boolean keyFrame, boolean boosted, int width, int height) {
        this.speed = speed;
        this.best = best;
        if (best) {
            setBest();
            return;
        }
        autoMvStepSize = true;
        sub8x8MvFromPrevious = true;
        meshTemporalFilter = false;
        var minSize = Math.min(width, height);
        var is480 = minSize >= 480;
        var is720 = minSize >= 720;
        breakoutDist = 1L << 20;
        breakoutRate = 80;
        squareOnlyHigh = Vp9Constants.BLOCK_INVALID;
        squareOnlyLow = BLOCK_4X4;
        if (!is480) {
            squareOnlyHigh = BLOCK_32X32;
        }
        altRefSearchFp = is720;
        lessRectangularCheck = true;
        useSquarePartitionOnly = !boosted;
        txSizeSearch = TX_FULL_RD;
        txSizeSearchBreakout = true;
        modeSearchSkipFlags = 0;
        modeSkipStart = Vp9RdCoder.MAX_MODES;
        adaptiveRdThresh = 1;
        for (var tx = 0; tx < 4; tx++) {
            intraYModeMask[tx] = (1 << 10) - 1;
            intraUvModeMask[tx] = (1 << 10) - 1;
        }
        intraYModeMask[3] = Vp9RtSpeed.INTRA_DC_H_V;
        maxIntraBsize = BLOCK_64X64;
        searchMethod = Vp9RtSpeed.NSTEP;
        subpelIters = 2;
        disableFilterSearchVarThresh = 0;
        checkerboardFilterSearch = false;
        transformDomainDistortion = false;
        transformDomainThreshold = 0;
        trellisMethod = TRELLIS_RESIDUAL_MSE;
        trellisThreshold = boosted ? 4.0 : 3.0;
        useRdBreakout = false;
        adaptiveMotionSearch = false;
        referenceMasking = true;
        adaptiveModeSearch = false;
        autoMinMaxPartition = false;
        autoPartitionMinLimit = BLOCK_4X4;
        recodeLoop = RECODE_FIRST;
        recodeToleranceLow = 12;
        recodeToleranceHigh = is480 ? 45 : 25;
        compoundJointSearchIterations = 1;
        subpelSearchTaps = 8;
        subpelSearchMethod = SUBPEL_TREE;
        adaptivePredInterpFilter = 1;
        if (speed >= 1) {
            squareOnlyHigh = is480 ? BLOCK_64X64 : BLOCK_32X32;
            squareOnlyLow = BLOCK_16X16;
            breakoutDist = is720 ? 1L << 22 : 1L << 21;
            useSquarePartitionOnly = !keyFrame;
            transformDomainDistortion = true;
            transformDomainThreshold = TRANSFORM_DOMAIN_THRESHOLDS[speed];
            trellisMethod = TRELLIS_SOURCE_VARIANCE;
            trellisThreshold = TRELLIS_THRESHOLDS[speed];
            useRdBreakout = true;
            adaptiveMotionSearch = true;
            adaptiveRdThresh = 2;
            subpelIters = 1;
            modeSkipStart = 10;
            intraUvModeMask[3] = Vp9RtSpeed.INTRA_DC_H_V;
            intraYModeMask[2] = Vp9RtSpeed.INTRA_DC_H_V;
            intraUvModeMask[2] = Vp9RtSpeed.INTRA_DC_H_V;
            recodeToleranceLow = 15;
            recodeToleranceHigh = 30;
            subpelSearchTaps = 4;
        }
        if (speed >= 2) {
            if (is720) {
                adaptivePredInterpFilter = 0;
            }
            squareOnlyHigh = BLOCK_4X4;
            squareOnlyLow = Vp9Constants.BLOCK_INVALID;
            breakoutDist = is720 ? 1L << 24 : 1L << 22;
            breakoutRate = is720 ? 120 : 100;
            autoPartitionMinLimit = width * height < 1280 * 720 ? BLOCK_4X4 : width * height < 1920 * 1080 ? BLOCK_8X8
                    : BLOCK_16X16;
            autoMinMaxPartition = true;
            recodeLoop = RECODE_KF_ARF_GF;
            txSizeSearch = boosted ? TX_FULL_RD : TX_LARGEST;
            modeSearchSkipFlags = keyFrame ? 0 : SKIP_INTRA_DIRMISMATCH | SKIP_INTRA_BESTINTER | SKIP_COMP_BESTINTRA
                    | SKIP_INTRA_LOWVAR;
            disableFilterSearchVarThresh = 100;
            compoundJointSearchIterations = 2;
            recodeToleranceHigh = 45;
            subpelIters = 0;
            subpelSearchTaps = 2;
        }
        if (speed >= 3) {
            adaptivePredInterpFilter = 0;
            subpelSearchMethod = SUBPEL_TREE_PRUNED;
            if (is720) {
                breakoutDist = 1L << 25;
                breakoutRate = 200;
            } else {
                maxIntraBsize = BLOCK_32X32;
                breakoutDist = 1L << 23;
                breakoutRate = 120;
            }
            useSquarePartitionOnly = !keyFrame;
            txSizeSearch = keyFrame ? TX_FULL_RD : TX_LARGEST;
            subpelIters = 0;
            adaptiveModeSearch = true;
            checkerboardFilterSearch = true;
            altRefSearchFp = true;
            recodeLoop = RECODE_KF_MAX_BW;
            adaptiveRdThresh = 3;
            modeSkipStart = 6;
            intraYModeMask[3] = Vp9RtSpeed.INTRA_DC;
            intraUvModeMask[3] = Vp9RtSpeed.INTRA_DC;
        }
        if (speed >= 4) {
            breakoutRate = 300;
            breakoutDist = is720 ? 1L << 26 : 1L << 24;
            useSquarePartitionOnly = true;
            txSizeSearch = TX_LARGEST;
            adaptiveRdThresh = 4;
            if (!keyFrame) {
                modeSearchSkipFlags |= EARLY_TERMINATE;
            }
            disableFilterSearchVarThresh = 200;
            searchMethod = Vp9RtSpeed.BIGDIA;
            subpelSearchMethod = SUBPEL_TREE_PRUNED_MORE;
        }
    }

    /**
     * Sets the features of a best-quality frame: the full partition, mode, transform, filter and
     * motion searches with only the speed-ups that libvpx keeps at the best quality.
     */
    private void setBest() {
        breakoutDist = 1L << 19;
        breakoutRate = 80;
        squareOnlyHigh = Vp9Constants.BLOCK_INVALID;
        squareOnlyLow = BLOCK_4X4;
        altRefSearchFp = false;
        lessRectangularCheck = false;
        useSquarePartitionOnly = false;
        txSizeSearch = TX_FULL_RD;
        txSizeSearchBreakout = true;
        modeSearchSkipFlags = 0;
        modeSkipStart = Vp9RdCoder.MAX_MODES;
        adaptiveRdThresh = 1;
        for (var tx = 0; tx < 4; tx++) {
            intraYModeMask[tx] = (1 << 10) - 1;
            intraUvModeMask[tx] = (1 << 10) - 1;
        }
        maxIntraBsize = BLOCK_64X64;
        searchMethod = Vp9RtSpeed.NSTEP;
        subpelIters = 2;
        disableFilterSearchVarThresh = 0;
        checkerboardFilterSearch = false;
        transformDomainDistortion = false;
        transformDomainThreshold = 99.0;
        trellisMethod = TRELLIS_ALWAYS;
        trellisThreshold = 99.0;
        useRdBreakout = false;
        adaptiveMotionSearch = false;
        referenceMasking = false;
        adaptiveModeSearch = false;
        autoMinMaxPartition = false;
        autoPartitionMinLimit = BLOCK_4X4;
        recodeLoop = RECODE_FIRST;
        recodeToleranceLow = 12;
        recodeToleranceHigh = 25;
        compoundJointSearchIterations = 0;
        subpelSearchTaps = 8;
        subpelSearchMethod = SUBPEL_TREE;
        adaptivePredInterpFilter = 0;
        autoMvStepSize = false;
        sub8x8MvFromPrevious = false;
        meshTemporalFilter = true;
    }
}
