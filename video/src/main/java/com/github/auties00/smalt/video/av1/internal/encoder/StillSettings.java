package com.github.auties00.smalt.video.av1.internal.encoder;

/**
 * Holds the quantizer and the search effort of an intra-only frame: which coding tools the frame
 * enables and how much of each search the encoder runs, derived from a speed level.
 *
 * <p>Speed 0 searches the most, speed 10 the least; every speed produces a valid stream. Speeds 0
 * to 4 add loop restoration, 64x64 blocks and wider rectangular partitions; speed 6, the default,
 * mirrors libaom's all-intra speed 6 (32x32 largest blocks, the partition network, gradient
 * histogram mode pruning, the luma-winner chroma search, four CDEF strengths, loop filter levels
 * from the quantizer); speeds 7 to 10 drop rectangular partitions, transform type search, angle
 * deltas, palettes and 4x4 blocks in turn.
 *
 * <p>Settings are created with {@link #of} and adjusted with the setters before the frame encoder
 * is created, which never changes them; the search tunables are package-private and mutable so that
 * development tools can override them too. Instances are not thread-safe.
 *
 * @implNote This implementation derives the tunables from libaom's
 * {@code set_allintra_speed_features_framesize_independent} and
 * {@code set_allintra_speed_feature_framesize_dependent} ({@code av1/encoder/speed_features.c}),
 * adjusted by rate-distortion and run-time measurements of this encoder.
 */
public final class StillSettings {
    /**
     * The base quantizer index, 0 (lossless) to 255.
     */
    final int qindex;

    /**
     * The speed level, 0 to 10.
     */
    final int speed;

    /**
     * The quantizer index delta of the chroma DC and AC coefficients, 0 by default.
     */
    int chromaDelta;

    /**
     * Whether the frame enables the screen content tools (palette mode), {@code false} by default.
     */
    boolean screenContent;

    /**
     * Whether a frame without {@link #screenContent} enables the screen content tools when its
     * source looks like screen content, {@code true} by default.
     */
    boolean detectScreenContent = true;

    /**
     * The base-2 logarithm of the tile columns requested, 0 by default.
     */
    int tileColsLog2;

    /**
     * The base-2 logarithm of the tile rows requested, 0 by default.
     */
    int tileRowsLog2;

    /**
     * The number of threads: 0 for the shared pool (the default), 1 for the caller only.
     */
    int threads;

    /**
     * Whether the sequence header is the reduced still picture header, {@code false} by default.
     */
    boolean reducedHeader;

    /**
     * Whether the sequence header signals film grain, {@code false} by default.
     */
    boolean filmGrain;

    /**
     * Sets whether the sequence header signals film grain, which the frames then carry
     * ({@link StillFrameEncoder#filmGrain}); the reduced still picture header signals none.
     *
     * @param filmGrain whether film grain is signaled
     * @return these settings
     */
    public StillSettings filmGrain(boolean filmGrain) {
        this.filmGrain = filmGrain;
        return this;
    }

    /**
     * The smallest block size the partition search reaches: 4 or 8 samples.
     */
    int minBlock;

    /**
     * The largest block size the partition search codes unsplit: 32 or 64 samples.
     */
    int maxBlock;

    /**
     * The largest partition node, in samples, whose rectangular partitions are searched
     * ({@code use_square_partition_only_threshold}).
     */
    int rectMaxBlock;

    /**
     * The smallest block size of pictures whose shorter side is at least 1080 samples.
     */
    int minBlockLarge;

    /**
     * The scale of the flatness threshold below which an unsplit block that codes no residual
     * ends the partition search, in sixteenths of the squared quantizer step per sample; 0
     * disables the breakout.
     */
    int breakoutScale;

    /**
     * Whether rectangular (horizontal and vertical) partitions are searched.
     */
    boolean rectPartitions;

    /**
     * Whether the rectangular partitions of a node are skipped when splitting it costs less than
     * leaving it whole ({@code less_rectangular_check_level}).
     */
    boolean rectPruning;

    /**
     * Whether the rectangular partitions of nodes smaller than a quantizer-dependent size are
     * skipped outside screen content: below 32 samples up to quantizer index 85, below 16 up to
     * 170 ({@code prune_rectangular_split_based_on_qidx}).
     */
    boolean rectQindexPrune;

    /**
     * Whether the superblock rows of a tile are searched at once, each costed from the frame's
     * initial CDFs adapted along the row alone, instead of in order with the CDFs adapted along
     * the whole tile; the stream then does not depend on the number of threads that search the
     * rows.
     */
    boolean rowWavefront;

    /**
     * Whether the rectangular partition across the dominant direction of the unsplit block's mode
     * is skipped: horizontal halves after a near-vertical mode, vertical halves after a
     * near-horizontal one ({@code prune_rect_part_using_none_pred_mode}).
     */
    boolean rectModePrune;

    /**
     * The pruning of the partition network on natural content: 0 none, 1 split decisions only (the
     * unsplit block is always searched), 2 also skipping the unsplit block of nodes predicted to
     * split ({@code intra_cnn_based_part_prune_level}); screen content is pruned at level 1 from
     * speed 5 and not at all below.
     */
    int cnnPartition;

    /**
     * Whether 8x8 nodes skip their split into 4x4 blocks next to a larger neighbor outside
     * screen content ({@code prune_sub_8x8_partition_level} 1).
     */
    boolean sub8x8Prune;

    /**
     * Whether the vertical and horizontal smooth luma modes are skipped
     * ({@code disable_smooth_intra}).
     */
    boolean noSmoothHv;

    /**
     * The number of luma mode candidates that get a full rate-distortion search after the model
     * pruning.
     */
    int lumaCandidates;

    /**
     * Whether the chroma search tries only DC, smooth, chroma-from-luma and the luma block's mode
     * ({@code prune_chroma_modes_using_luma_winner}).
     */
    boolean chromaLumaWinner;

    /**
     * The number of chroma mode candidates that get a full rate-distortion search.
     */
    int chromaCandidates;

    /**
     * Whether the directional modes are searched with every angle delta.
     */
    boolean angleDeltas;

    /**
     * The number of best directional luma modes whose angle deltas are searched.
     */
    int angleModes;

    /**
     * The number of best directional chroma modes whose angle deltas are searched.
     */
    int chromaAngleModes;

    /**
     * The score at or below which the gradient histogram rules out a directional luma mode, or
     * {@code NaN} to search every mode ({@code intra_pruning_with_hog}).
     */
    float hogThreshold;

    /**
     * The score at or below which the gradient histogram rules out a directional chroma mode, or
     * {@code NaN} to search every mode ({@code chroma_intra_pruning_with_hog}).
     */
    float chromaHogThreshold;

    /**
     * The transform split depth the luma mode candidates are compared at.
     */
    int startTxDepth;

    /**
     * The deepest transform split searched: 0 to 2.
     */
    int maxTxDepth;

    /**
     * Whether the transform type of every transform block is searched.
     */
    boolean txTypeSearch;

    /**
     * Whether the transform type search of the smaller sizes tries five of the seven types,
     * chosen by the luma mode ({@code use_reduced_intra_txset}).
     */
    boolean reducedIntraTypes;

    /**
     * The number of transform types costed exactly after ranking every type by distortion and a
     * rate estimate; 0 costs every type exactly.
     */
    int typeExact;

    /**
     * Whether filter intra is searched.
     */
    boolean filterIntra;

    /**
     * The number of filter intra modes, ranked by the prediction model, that get a full
     * rate-distortion search: 1 to 5.
     */
    int filterCandidates;

    /**
     * Whether filter intra tries only its DC mode and the mode matching the best luma mode, with no
     * model ranking ({@code prune_filter_intra_level} 1); overrides {@link #filterCandidates}.
     */
    boolean filterDerived;

    /**
     * Whether the transform types of a transform block are compared by their distortion in the
     * transform domain instead of after the inverse transform.
     */
    boolean txDomainTypes;

    /**
     * Whether palettes are searched when the frame enables the screen content tools.
     */
    boolean palette;

    /**
     * The CDEF search: 0 the quantizer model ({@code CDEF_PICK_FROM_Q}), 1 to 5 libaom's fast
     * search levels 5 to 1 (2, 4, 10, 20 and 32 strengths), 6 every strength.
     */
    int cdefSearch;

    /**
     * Whether the loop filter levels are searched instead of derived from the quantizer.
     */
    boolean loopFilterSearch;

    /**
     * Whether loop restoration is enabled and searched.
     */
    boolean restoration;

    /**
     * Whether several restoration unit sizes are tried; otherwise one is derived from the
     * picture size and quantizer.
     */
    boolean restorationSizeSearch;

    /**
     * Whether the self-guided search tries a pruned subset of the parameter sets
     * ({@code enable_sgr_ep_pruning}).
     */
    boolean sgrPruning;

    /**
     * Whether two-pass self-guided filters pay a 1% cost penalty ({@code dual_sgr_penalty_level}).
     */
    boolean dualSgrPenalty;

    /**
     * The source variance threshold below which the Wiener search is skipped, in sixteenths of
     * the squared quantizer step; 0 disables it ({@code prune_wiener_based_on_src_var}).
     */
    int wienerVarPrune;

    /**
     * The self-guided pruning from the Wiener outcome: 0 none, 1 when Wiener costs 1% more than
     * no restoration, 2 when Wiener loses ({@code prune_sgr_based_on_wiener}).
     */
    int sgrFromWiener;

    /**
     * Whether the luma Wiener filter is searched with 5 taps instead of 7
     * ({@code reduce_wiener_window_size}).
     */
    boolean reducedWiener;

    /**
     * Creates the settings of a quantizer and speed.
     *
     * @param qindex the base quantizer index, 0 to 255
     * @param speed the speed level, 0 to 10
     */
    private StillSettings(int qindex, int speed) {
        this.qindex = qindex;
        this.speed = speed;
        var s = speed;
        this.minBlock = s <= 7 ? 4 : 8;
        this.minBlockLarge = s <= 5 ? minBlock : 8;
        this.maxBlock = s <= 5 ? 64 : 32;
        this.rectMaxBlock = s <= 1 ? 64 : s <= 5 ? 32 : 16;
        this.breakoutScale = s <= 2 ? 0 : s <= 5 ? 1 : s <= 7 ? 2 : 4;
        this.rectPartitions = s <= 7;
        this.rectPruning = s >= 7;
        this.rectQindexPrune = s >= 6;
        this.rectModePrune = s >= 6;
        this.cnnPartition = s < 1 ? 0 : 2;
        this.sub8x8Prune = s >= 6;
        this.noSmoothHv = s >= 2;
        this.lumaCandidates = s <= 1 ? 8 : s <= 3 ? 5 : s <= 6 ? 3 : 2;
        this.chromaCandidates = s <= 1 ? 6 : s <= 6 ? 3 : 2;
        this.chromaLumaWinner = s >= 4;
        this.angleDeltas = s <= 6;
        this.angleModes = s <= 1 ? 8 : s <= 3 ? 3 : 2;
        this.chromaAngleModes = s <= 1 ? 8 : s <= 3 ? 2 : 1;
        this.hogThreshold = s <= 1 ? Float.NaN : s == 2 ? -1.2f : s <= 5 ? -0.6f : 0.4f;
        this.chromaHogThreshold = s <= 2 ? Float.NaN : s <= 5 ? -0.6f : 0.4f;
        this.startTxDepth = 0;
        this.maxTxDepth = s <= 3 ? 2 : s <= 8 ? 1 : 0;
        this.txTypeSearch = s <= 7;
        this.reducedIntraTypes = s >= 1;
        this.typeExact = s <= 3 ? 0 : s <= 5 ? 3 : 2;
        this.filterIntra = s <= 6;
        this.filterCandidates = s <= 3 ? 5 : s <= 6 ? 2 : 1;
        this.filterDerived = s >= 2;
        this.txDomainTypes = s >= 2;
        this.palette = s <= 8;
        this.cdefSearch = s == 0 ? 6 : s <= 3 ? 5 : s <= 5 ? 3 : s == 6 ? 2 : 0;
        this.loopFilterSearch = s <= 5;
        this.restoration = s <= 4;
        this.restorationSizeSearch = s == 0;
        this.sgrPruning = s >= 1;
        this.dualSgrPenalty = s >= 1;
        this.wienerVarPrune = s <= 1 ? 0 : s == 2 ? 1 : 2;
        this.sgrFromWiener = s <= 1 ? 0 : s == 2 ? 1 : 2;
        this.reducedWiener = s >= 3;
    }

    /**
     * Returns the settings of a quantizer and speed, with no chroma quantizer delta, the screen
     * content tools off, one tile, the shared pool and the full sequence header.
     *
     * @param qindex the base quantizer index, from 0 (lossless) to 255; values outside are clamped
     * @param speed the speed level, from 0 (slowest) to 10; values outside are clamped
     * @return the settings
     */
    public static StillSettings of(int qindex, int speed) {
        return new StillSettings(Math.clamp(qindex, 0, 255), Math.clamp(speed, 0, 10));
    }

    /**
     * Sets the quantizer index delta of the chroma DC and AC coefficients; a lossless frame keeps
     * none.
     *
     * @param delta the delta, from -64 to 63; values outside are clamped
     * @return these settings
     */
    public StillSettings chromaDelta(int delta) {
        this.chromaDelta = qindex == 0 ? 0 : Math.clamp(delta, -64, 63);
        return this;
    }

    /**
     * Sets whether the frame enables the screen content tools (palette mode) whatever the source;
     * otherwise they are enabled when the source looks like screen content. The self-guided
     * restoration search of speeds 3 and above prunes less with them on.
     *
     * @param screenContent whether the tools are enabled
     * @return these settings
     */
    public StillSettings screenContent(boolean screenContent) {
        this.screenContent = screenContent;
        if (speed >= 3) {
            sgrFromWiener = screenContent ? 1 : 2;
        }
        return this;
    }

    /**
     * Sets whether a frame that does not enable the screen content tools whatever the source
     * enables them when the source looks like screen content.
     *
     * @param detect whether the source is tested
     * @return these settings
     */
    public StillSettings detectScreenContent(boolean detect) {
        this.detectScreenContent = detect;
        return this;
    }

    /**
     * Sets the requested uniform tile layout; the frame clamps it to what the picture allows.
     *
     * @param columnsLog2 the base-2 logarithm of the tile columns, from 0 to 6; values outside are
     * clamped
     * @param rowsLog2 the base-2 logarithm of the tile rows, from 0 to 6; values outside are clamped
     * @return these settings
     */
    public StillSettings tiles(int columnsLog2, int rowsLog2) {
        this.tileColsLog2 = Math.clamp(columnsLog2, 0, 6);
        this.tileRowsLog2 = Math.clamp(rowsLog2, 0, 6);
        return this;
    }

    /**
     * Sets the number of threads.
     *
     * @param threads the count: 0 for the shared pool, 1 for the caller only; negative values mean 0
     * @return these settings
     */
    public StillSettings threads(int threads) {
        this.threads = Math.max(0, threads);
        return this;
    }

    /**
     * Sets whether the sequence header is the reduced still picture header of a still image,
     * rather than the full header of an image sequence or video.
     *
     * @param reducedHeader whether the header is reduced
     * @return these settings
     */
    public StillSettings reducedHeader(boolean reducedHeader) {
        this.reducedHeader = reducedHeader;
        return this;
    }

    /**
     * Widens the searches of a speed 6 key frame to those of the first levels below it that pay
     * most: the rectangular partitions of small nodes and after near-directional modes, the 8x8
     * split next to larger neighbors, the gradient histogram's pruning of directional modes and
     * the CDEF search width. The key frame of the good-quality encoder is a large share of the
     * stream's bits and is coded once, so it can afford what an all-intra image at speed 6 cannot.
     * It also searches its superblock rows in a wavefront ({@link #rowWavefront}), because the
     * serial search of a key frame bounds the time of the encoder on several threads.
     *
     * @return these settings
     */
    StillSettings forGoodKey() {
        rowWavefront = true;
        if (speed == 6) {
            rectQindexPrune = false;
            rectModePrune = false;
            sub8x8Prune = false;
            hogThreshold = -0.6f;
            chromaHogThreshold = -0.6f;
            cdefSearch = 3;
        }
        return this;
    }

    /**
     * Returns the smallest block size the partition search reaches in a picture.
     *
     * @param width the picture width
     * @param height the picture height
     * @return 4 or 8 samples
     */
    int minBlock(int width, int height) {
        return Math.min(width, height) >= 1080 ? Math.max(minBlock, minBlockLarge) : minBlock;
    }
}
