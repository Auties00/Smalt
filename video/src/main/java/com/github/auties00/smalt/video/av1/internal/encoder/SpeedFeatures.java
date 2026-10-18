package com.github.auties00.smalt.video.av1.internal.encoder;

/**
 * Holds the real-time speed features of one speed level and picture size: the switches and
 * thresholds that trade compression for encoding time in the partitioning, the mode decision and
 * the motion search.
 *
 * <p>The good-quality levels ({@link #good}) drive the same mode decision with its real-time
 * prunings relaxed: every reference of a pyramid is searched, new motion vectors are searched for
 * every reference and block size, and intra modes are always tested. They also choose each
 * superblock's partitions by the mode decision's cost estimates instead of by the variance
 * partitioning, and optimize the quantized levels of every transform block by rate and distortion.
 *
 * @implNote This implementation ports the subset of libaom's
 * {@code set_rt_speed_features_framesize_independent} and
 * {@code set_rt_speed_feature_framesize_dependent} ({@code av1/encoder/speed_features.c})
 * for speeds 7 to 10 that the encoder implements, for camera content without spatial
 * layers. The good-quality levels are not libaom's good-quality features, whose
 * rate-distortion mode search this encoder does not implement: they keep the real-time
 * speed 7 features and switch off the real-time prunings, more of them at slower levels,
 * and search the partitions the way libaom's {@code rd_pick_partition} recurses over
 * the unsplit, rectangular and split candidates with the non-RD mode decision's costs.
 */
final class SpeedFeatures {
    /**
     * The quarter-sample motion vector precision stop.
     */
    static final int QUARTER_PEL = 1;

    /**
     * The half-sample motion vector precision stop.
     */
    static final int HALF_PEL = 2;

    /**
     * The full-sample motion vector precision stop.
     */
    static final int FULL_PEL = 3;

    /**
     * The eighth-sample motion vector precision stop.
     */
    static final int EIGHTH_PEL = 0;

    /**
     * The speed level, 7 to 10.
     */
    final int speed;

    /**
     * The aggressiveness of terminating the inter mode search on the prediction error, 0 off.
     */
    final int sseEarlyTermInterSearch;

    /**
     * The aggressiveness of pruning the golden and altref references, 0 to 3.
     */
    final int nonrdPruneRefFrameSearch;

    /**
     * Whether the altref reference is searched.
     */
    final boolean useNonrdAltrefFrame;

    /**
     * When intra modes are skipped in inter frames: 1 skips them after a skipped non-NEWMV best
     * mode, 2 after any skipped best mode.
     */
    final int skipIntraPred;

    /**
     * Whether only DC is searched for intra blocks below 32x32 in inter frames.
     */
    final boolean intraDcOnly;

    /**
     * The extra left shift of the 8x8 split threshold of the variance partitioning.
     */
    final int varPartSplitThresholdShift;

    /**
     * The quantizer-dependent tuning of the partition thresholds, 0 to 4.
     */
    final int varPartBasedOnQidx;

    /**
     * The preference for large partitions, 0 to 3.
     */
    final int preferLargePartitionBlocks;

    /**
     * The superblock motion estimate of the partitioning: 0 none, 1 or 2 integral projection,
     * 3 neighboring vectors only.
     */
    final int estimateMotionForVarPartition;

    /**
     * Whether key frames prefer large partitions.
     */
    final boolean forceLargePartitionBlocksIntra;

    /**
     * The quantizer-step based transform size and skip level, 0 to 2.
     */
    final int txSizeLevelBasedOnQstep;

    /**
     * Whether the mode search ends at the first skipped mode.
     */
    final boolean nonrdAggressiveSkip;

    /**
     * The precision reduction of large motion vectors, 0 to 3.
     */
    final int reduceMvPelPrecisionHighmotion;

    /**
     * The precision reduction of static low-complexity areas, 0 to 2.
     */
    final int reduceMvPelPrecisionLowcomplex;

    /**
     * Whether the sub-sample search downgrades to its pruned variant for easy blocks.
     */
    final boolean useAdaptiveSubpelSearch;

    /**
     * Whether the sub-sample search uses its most pruned variant.
     */
    final boolean subpelPrunedMore;

    /**
     * The finest motion vector precision searched.
     */
    final int subpelForceStop;

    /**
     * Whether low temporal variance short-circuits the reference search.
     */
    final boolean shortCircuitLowTempVar;

    /**
     * The source variance from which key frame blocks below 16x16 get the rate-distortion intra
     * search ({@code hybrid_intra_pickmode}), {@link Integer#MAX_VALUE} for never.
     */
    final int hybridIntraVarThresh;

    /**
     * Whether the regular and smooth interpolation filters are searched for sub-sample motion.
     */
    final boolean useNonrdFilterSearch;

    /**
     * The checkerboard prediction of the interpolation filter from the neighbors, 0 off to 2.
     */
    final int cbPredFilterSearch;

    /**
     * The largest block size searched for intra modes in inter frames, in the decoder's block
     * size order (smaller index is larger).
     */
    final int maxIntraBsize;

    /**
     * The good-quality level, 2 (slowest) to 6, or 0 for the real-time features.
     */
    final int goodLevel;

    /**
     * Whether new motion vectors are searched for every reference and block size, not only for
     * {@code LAST} below 16x16.
     */
    final boolean newmvAllRefs;

    /**
     * The compound prediction search: 0 none, 1 the nearest past and future references with the
     * nearest, new and global modes, 2 also the near and mixed nearest and new modes, 3 also the
     * other past and future pairs.
     */
    final int compound;

    /**
     * The number of stages of the n-step full-sample search that precedes the fast diamond of a
     * new motion vector, 0 for the fast diamond alone.
     */
    final int nstepStages;

    /**
     * Whether the partitions of each superblock of an inter frame are searched by the mode
     * decision's cost estimates instead of following the variance partitioning.
     */
    final boolean partitionSearch;

    /**
     * The block levels, as a bit mask over the decoder's levels (bit 1 64x64, bit 2 32x32, bit 3
     * 16x16), whose horizontal and vertical halves the partition search costs.
     */
    final int searchRectLevels;

    /**
     * The block level from which the partition search does not cost the halves of a node whose
     * unsplit block codes without a residual.
     */
    final int searchRectSkipLevel;

    /**
     * The block level from which the partition search does not cost the quarters of a node whose
     * unsplit block codes without a residual.
     */
    final int searchSplitSkipLevel;

    /**
     * Whether the halves of a node are costed against the unsplit block's best reference only.
     */
    final boolean searchScreenRect;

    /**
     * Whether the full-sample motion searches of blocks at least 16 samples tall compare every
     * other row.
     */
    final boolean rowSkipSad;

    /**
     * The percentage of the mode decision's multiplier the level optimization uses.
     */
    final int rdoqLambdaScale;

    /**
     * The transform type search of inter luma blocks coded with one transform: 0 codes
     * {@code DCT_DCT}, 1 searches {@code DCT_DCT}, the ADST pairs, {@code IDTX} and the
     * one-dimensional DCTs, 2 searches every type the transform size allows.
     */
    final int interTxTypes;

    /**
     * Whether the transform size of an inter block is searched among the uniform depths of its
     * largest transform by rate and distortion, instead of following the prediction error.
     */
    final boolean interTxSize;

    /**
     * Whether the levels of each transform block are optimized by rate and distortion after
     * quantization.
     */
    final boolean rdoq;

    /**
     * The number of single-reference candidates of the mode decision whose rate and distortion are
     * measured by coding them, 0 or 1 for none.
     */
    final int finalists;

    /**
     * The percentage by which a candidate's estimated cost may exceed the cheapest candidate's for
     * the candidate to be measured.
     */
    final int finalistMargin;

    /**
     * Whether the candidates are measured with the speed's full transform search instead of the
     * reduced one.
     */
    final boolean finalistFull;

    /**
     * Whether blocks are also coded with a local warp of their reference.
     */
    final boolean warp;

    /**
     * The squared error per sample, in millionths of the Lagrangian multiplier, below which an
     * unsplit 64x64 block without a residual ends the search of the partitions of its node when
     * the variance partitioning left the node whole, or 0 to search them all.
     */
    final int partitionBreakout;

    /**
     * Returns the features of a good-quality level.
     *
     * @param level the level, from 2 (slowest) to 6; values outside are clamped
     * @return the features
     */
    static SpeedFeatures good(int level) {
        return new SpeedFeatures(Math.clamp(level, 2, 6));
    }

    /**
     * Returns the features of a real-time speed level and picture size.
     *
     * @param speed the speed level, from 7 to 10; values outside are clamped
     * @param width the picture width
     * @param height the picture height
     * @return the features
     */
    static SpeedFeatures realtime(int speed, int width, int height) {
        return new SpeedFeatures(Math.clamp(speed, 7, 10), width, height);
    }

    /**
     * Creates the features of a good-quality level.
     *
     * @param level the level, 2 to 6
     */
    private SpeedFeatures(int level) {
        speed = 7;
        goodLevel = level;
        sseEarlyTermInterSearch = level >= 5 ? 1 : 0;
        nonrdPruneRefFrameSearch = 0;
        useNonrdAltrefFrame = true;
        skipIntraPred = level >= 6 ? 1 : 0;
        intraDcOnly = false;
        varPartSplitThresholdShift = level >= 5 ? 7 : 6;
        varPartBasedOnQidx = 3;
        preferLargePartitionBlocks = 0;
        estimateMotionForVarPartition = 2;
        forceLargePartitionBlocksIntra = false;
        txSizeLevelBasedOnQstep = 0;
        nonrdAggressiveSkip = false;
        reduceMvPelPrecisionHighmotion = 0;
        reduceMvPelPrecisionLowcomplex = 0;
        useAdaptiveSubpelSearch = false;
        subpelPrunedMore = false;
        subpelForceStop = EIGHTH_PEL;
        shortCircuitLowTempVar = false;
        hybridIntraVarThresh = 0;
        useNonrdFilterSearch = true;
        cbPredFilterSearch = 0;
        maxIntraBsize = 7;
        newmvAllRefs = level <= 4;
        compound = level <= 4 ? 3 : 2;
        nstepStages = 7;
        partitionSearch = true;
        searchRectLevels = level == 6 ? 0b1100 : 0b1110;
        searchRectSkipLevel = level == 6 ? 1 : level == 5 ? 2 : Integer.MAX_VALUE;
        searchSplitSkipLevel = level >= 4 ? 3 : Integer.MAX_VALUE;
        searchScreenRect = level >= 5;
        rowSkipSad = true;
        rdoqLambdaScale = 150;
        interTxTypes = level <= 4 ? 2 : 1;
        interTxSize = true;
        rdoq = true;
        finalists = 3;
        finalistMargin = level >= 5 ? 5 : level >= 3 ? 15 : 100;
        finalistFull = level <= 3;
        warp = true;
        partitionBreakout = level == 6 ? 130 : 0;
    }

    /**
     * Creates the features of a real-time speed level and picture size.
     *
     * @param speed the speed level, 7 to 10
     * @param width the picture width
     * @param height the picture height
     */
    private SpeedFeatures(int speed, int width, int height) {
        this.speed = speed;
        goodLevel = 0;
        newmvAllRefs = false;
        compound = 0;
        nstepStages = 0;
        partitionSearch = false;
        searchRectLevels = 0;
        searchRectSkipLevel = Integer.MAX_VALUE;
        searchSplitSkipLevel = Integer.MAX_VALUE;
        searchScreenRect = false;
        rowSkipSad = false;
        rdoqLambdaScale = 100;
        interTxTypes = 0;
        interTxSize = false;
        rdoq = false;
        finalists = 0;
        finalistMargin = 0;
        finalistFull = false;
        warp = false;
        partitionBreakout = 0;
        var minDim = Math.min(width, height);
        var is360 = minDim >= 360;
        var is720 = minDim >= 720;
        var is1080 = minDim >= 1080;
        sseEarlyTermInterSearch = speed >= 10 ? 4 : speed >= 9 ? 3 : speed >= 8 ? 2 : 1;
        var prune = speed >= 10 ? 3 : speed >= 8 ? 2 : 1;
        nonrdPruneRefFrameSearch = prune;
        var altref = speed == 7 || (speed == 8 && is360);
        useNonrdAltrefFrame = altref;
        var skipIntra = speed >= 9 ? 2 : 1;
        if (!is360 && speed >= 10) {
            skipIntra = 2;
        }
        skipIntraPred = skipIntra;
        intraDcOnly = speed >= 9;
        varPartSplitThresholdShift = speed >= 10 ? 10 : speed >= 9 ? 9 : speed >= 8 ? 8 : 7;
        varPartBasedOnQidx = speed >= 9 ? 0 : speed >= 8 ? 4 : 3;
        var large = speed >= 9 ? 3 : 0;
        if (!is360) {
            if (speed == 7) {
                large = 2;
            } else if (speed == 8) {
                large = 1;
            }
        } else if (speed == 7) {
            large = is720 ? 0 : 1;
        }
        preferLargePartitionBlocks = large;
        var est = speed >= 9 ? 3 : 2;
        if (!is360 && speed >= 10) {
            est = 0;
        }
        estimateMotionForVarPartition = est;
        forceLargePartitionBlocksIntra = !is720 && speed >= 9;
        var txq = speed >= 8 ? (is360 ? 2 : 1) : 0;
        if (is360 && speed >= 10) {
            txq = 0;
        }
        txSizeLevelBasedOnQstep = txq;
        nonrdAggressiveSkip = (!is360 && speed >= 9) || (!is1080 && speed >= 10);
        var high = 0;
        var low = 0;
        if (!is360) {
            if (speed >= 10) {
                low = 1;
                high = 2;
            }
        } else {
            if (speed >= 9) {
                high = 2;
            }
            if (speed >= 10) {
                high = 3;
            }
            if (is720) {
                if (speed >= 7) {
                    low = 2;
                    high = 1;
                }
                if (speed >= 9) {
                    low = 0;
                    high = 2;
                }
                if (speed >= 10) {
                    high = 3;
                }
            }
        }
        if (is1080 && speed >= 7) {
            high = 0;
        }
        reduceMvPelPrecisionHighmotion = high;
        reduceMvPelPrecisionLowcomplex = low;
        var adaptive = speed >= 7 && speed < 10;
        if (!is360 && speed >= 9) {
            adaptive = false;
        }
        if (is1080 && speed >= 7) {
            adaptive = false;
        }
        useAdaptiveSubpelSearch = adaptive;
        subpelPrunedMore = speed >= 10;
        subpelForceStop = QUARTER_PEL;
        shortCircuitLowTempVar = speed >= 8 && !(speed == 8 && is360);
        maxIntraBsize = 7;
        useNonrdFilterSearch = is360 || speed < 10;
        var hybrid = speed >= 10 ? (is360 ? 2 : 3) : 1;
        hybridIntraVarThresh = new int[]{0, 101, 201}[hybrid - 1];
        cbPredFilterSearch = !is360 || is1080 ? 0 : speed >= 10 ? 2 : speed >= 9 ? 1 : 0;
    }

}
