/* Ports libvpx 1.17.0 (vp9/encoder/vp9_encoder.c, vp9_encodeframe.c, vp9_rd.c, vp9_picklpf.c). Copyright (c) 2010,
   The WebM Project authors; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.video.vp9.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.plane.Planes;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.video.vp9.Vp9EncoderOptions;

import java.util.Arrays;

import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.ALTREF_FRAME;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.GOLDEN_FRAME;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.LAST_FRAME;

/**
 * Codes the frames of a real-time VP9 stream: profile 0, 8-bit 4:2:0, one-pass constant
 * bitrate, with the decisions of libvpx's real-time speeds 5 to 9.
 *
 * <p>Each frame runs the rate control, which picks the frame type, target and quantizer; the
 * cyclic refresh, which marks the blocks to refresh at a lower quantizer; the superblock coding
 * of every tile, row by row and possibly on several threads; the loop filter; and the bitstream
 * writer. The reconstruction becomes the last frame and, periodically, the golden frame, the two
 * references of later frames. The output is the same for every thread count.
 *
 * @implNote This implementation follows libvpx's one-pass real-time flow
 * ({@code vp9/encoder/vp9_encoder.c}: {@code vp9_get_compressed_data},
 * {@code encode_frame_to_data_rate}, {@code encode_without_recode_loop},
 * {@code setup_frame}, {@code loopfilter_frame}, {@code vp9_update_reference_frames};
 * {@code vp9_encodeframe.c}: {@code encode_frame_internal};
 * {@code vp9_rd.c}: {@code vp9_initialize_rd_consts}; {@code vp9_picklpf.c}:
 * {@code LPF_PICK_FROM_Q}). Rows are coded with per-row adaptive thresholds, as
 * libvpx does with row multithreading, whatever the thread count.
 */
public final class Vp9RtEncoder {
    /**
     * The timestamp ticks per second, 10 MHz as libvpx's.
     */
    public static final long TICKS_PER_SECOND = 10_000_000L;

    /**
     * The sum-of-differences weight of the motion search per quantizer index
     * ({@code sad_per_bit16lut_8}).
     */
    static final int[] SAD_PER_BIT16 = new int[256];

    /**
     * The adaptive threshold base factor of each block size ({@code rd_thresh_block_size_factor}).
     */
    private static final int[] RD_THRESH_BLOCK_SIZE_FACTOR = {2, 3, 3, 4, 6, 6, 8, 12, 12, 16, 24, 24, 32};

    static {
        for (var i = 0; i < 256; i++) {
            var q = Vp9EncTables.qindexToQ(i);
            SAD_PER_BIT16[i] = (int) (0.0418 * q + 2.4107);
        }
    }

    /**
     * The frame width in samples.
     */
    final int width;

    /**
     * The frame height in samples.
     */
    final int height;

    /**
     * The number of 8x8 block columns.
     */
    final int miCols;

    /**
     * The number of 8x8 block rows.
     */
    final int miRows;

    /**
     * The number of superblock columns.
     */
    final int sbCols;

    /**
     * The number of superblock rows.
     */
    final int sbRows;

    /**
     * The number of 16x16 macroblocks, the unit of the rate model.
     */
    final int mbs;

    /**
     * The number of temporal layers, 1 to 3.
     */
    final int temporalLayers;

    /**
     * The number of threads coding superblock rows.
     */
    final int threads;

    /**
     * The log2 of the number of tile columns.
     */
    int log2TileCols;

    /**
     * The smallest log2 tile column count the frame width allows.
     */
    final int minLog2TileCols;

    /**
     * The largest log2 tile column count the frame width allows.
     */
    final int maxLog2TileCols;

    /**
     * The first 8x8 column of each tile column, followed by the column count.
     */
    private final int[] tileStarts;

    /**
     * The speed features of the current frame.
     */
    final Vp9RtSpeed sf = new Vp9RtSpeed();

    /**
     * The rate control of the current frame's layer.
     */
    final Vp9RtRateControl rc = new Vp9RtRateControl();

    /**
     * The cyclic refresh.
     */
    final Vp9RtCyclicRefresh cr;

    /**
     * The source noise estimate.
     */
    final Vp9RtNoiseEstimate noise;

    /**
     * The skin detector.
     */
    final Vp9RtContent content;

    /**
     * The quantizer parameters of every quantizer index.
     */
    final Vp9Quantizer quant = new Vp9Quantizer();

    /**
     * The probabilities the current frame is coded with.
     */
    final Vp9FrameContext fc = new Vp9FrameContext();

    /**
     * The four saved probability contexts frames can load and refresh.
     */
    private final Vp9FrameContext[] frameContexts = {new Vp9FrameContext(), new Vp9FrameContext(),
            new Vp9FrameContext(), new Vp9FrameContext()};

    /**
     * The symbol counts of the current frame, merged from every thread's counts.
     */
    final Vp9EncCounts frameCounts = new Vp9EncCounts();

    /**
     * The bordered copy of the frame being coded.
     */
    Vp9EncPicture source;

    /**
     * The bordered copy of the previous source frame, or {@code null} before the second frame.
     */
    Vp9EncPicture lastSource;

    /**
     * The reconstruction of the frame being coded, in the decoder's layout so that its loop filter
     * applies.
     */
    final Vp9Frame recon = new Vp9Frame();

    /**
     * The eight reference slots of the bitstream.
     */
    private final Vp9EncPicture[] slots = new Vp9EncPicture[8];

    /**
     * The reference pictures, reused once no slot holds them.
     */
    private final Vp9EncPicture[] pool = new Vp9EncPicture[10];

    /**
     * The slot of the last, golden and altref references.
     */
    final int[] refSlot = {0, 1, 2};

    /**
     * The mode information of every 8x8 block, one object per block.
     */
    final ModeInfo[] miPool;

    /**
     * The mode information covering each 8x8 block, shared by all the blocks of a coded block.
     */
    final ModeInfo[] miGrid;

    /**
     * The references and motion vectors of the current frame, four ints per 8x8 block.
     */
    int[] curFrameMvs;

    /**
     * The references and motion vectors of the previous frame, four ints per 8x8 block.
     */
    int[] prevFrameMvs;

    /**
     * The partition context above the current superblock row.
     */
    final byte[] abovePartition;

    /**
     * The nonzero contexts above the current superblock row, per plane.
     */
    final byte[][] aboveEntropy = new byte[3][];

    /**
     * The segment of each 8x8 block chosen by the cyclic refresh.
     */
    final byte[] segmentationMap;

    /**
     * The segment of each 8x8 block in the last frame that coded the map.
     */
    final byte[] lastSegMap;

    /**
     * Whether each segment has a quantizer delta.
     */
    final boolean[] segAltQEnabled = new boolean[8];

    /**
     * The quantizer delta of each segment.
     */
    final int[] segAltQ = new int[8];

    /**
     * Whether the current frame uses segmentation.
     */
    boolean segEnabled;

    /**
     * Whether the current frame codes the segment map.
     */
    boolean segUpdateMap;

    /**
     * Whether the current frame codes the segment features.
     */
    boolean segUpdateData;

    /**
     * The inter mode context of each coded block, by its top-left 8x8 block, for the bitstream
     * writer.
     */
    final int[] blockModeContext;

    /**
     * The best reference motion vector of each coded block, by its top-left 8x8 block, against
     * which the bitstream writer codes new motion vectors.
     */
    final int[] blockRefMv;

    /**
     * The block size of each 8x8 block in the previous frame, for partition copying.
     */
    final byte[] prevPartition;

    /**
     * The segment of each superblock in the previous frame, for partition copying.
     */
    final byte[] prevSegmentId;

    /**
     * The low temporal variance flags of each superblock in the previous frame, 25 per superblock.
     */
    final boolean[] prevVarianceLow;

    /**
     * The number of consecutive frames each superblock copied its partition.
     */
    final int[] copiedFrameCnt;

    /**
     * The number of consecutive frames each superblock had a low source difference.
     */
    final int[] contentStateSbFd;

    /**
     * The number of consecutive frames each 8x8 block kept a zero motion vector on the last frame,
     * saturating at 255.
     */
    final byte[] consecZeroMv;

    /**
     * The variance thresholds of the partition choice, for 64x64, 32x32, 16x16 and 8x8 blocks.
     */
    final long[] vbpThresholds = new long[4];

    /**
     * The sum-of-differences threshold below which a superblock keeps 64x64.
     */
    int vbpThresholdSad;

    /**
     * The sum-of-differences threshold below which a superblock copies its previous partition.
     */
    int vbpThresholdCopy;

    /**
     * The smallest block size of the variance partitioning.
     */
    int vbpBsizeMin;

    /**
     * The threshold of the 8x8 min-max split of 16x16 blocks.
     */
    int vbpThresholdMinmax;

    /**
     * The mode thresholds by segment, block size and mode.
     */
    final int[] rdThreshes = new int[8 * 13 * Vp9RtPickMode.MAX_MODES];

    /**
     * The threshold multiplier of each mode.
     */
    private final int[] threshMult = new int[Vp9RtPickMode.MAX_MODES];

    /**
     * The adaptive threshold factors of each tile superblock row, by block size and mode.
     */
    private final int[][] rowThreshFact;

    /**
     * The cost of each key frame luma mode, by above and left mode.
     */
    final int[] kfYModeCosts = new int[10 * 10 * 10];

    /**
     * The cost of each inter frame luma intra mode.
     */
    final int[] mbmodeCost = new int[10];

    /**
     * The cost of each interpolation filter, by context.
     */
    final int[] switchableInterpCosts = new int[4 * 3];

    /**
     * The cost of each inter mode, by mode context.
     */
    final int[] interModeCost = new int[7 * 4];

    /**
     * The cost of each motion vector joint.
     */
    final int[] mvJointCost = new int[4];

    /**
     * The cost of each row motion vector component at the current precision.
     */
    int[] mvCostRow;

    /**
     * The cost of each column motion vector component at the current precision.
     */
    int[] mvCostCol;

    /**
     * The row component costs with eighth-sample precision.
     */
    private final int[] mvCostRowHp = new int[Vp9EncMv.MV_VALS];

    /**
     * The column component costs with eighth-sample precision.
     */
    private final int[] mvCostColHp = new int[Vp9EncMv.MV_VALS];

    /**
     * The row component costs with quarter-sample precision.
     */
    private final int[] mvCostRowLp = new int[Vp9EncMv.MV_VALS];

    /**
     * The column component costs with quarter-sample precision.
     */
    private final int[] mvCostColLp = new int[Vp9EncMv.MV_VALS];

    /**
     * The superblock coder of each thread.
     */
    private final Vp9RtCoder[] coders;

    /**
     * The tokens of each tile superblock row.
     */
    private final Vp9RtTokens[] tokens;

    /**
     * The bitstream writer.
     */
    private final Vp9RtBitstream bitstream;

    /**
     * The levels, thresholds and blocks the loop filters read.
     */
    private final Vp9LoopFilterParams lfParams = new Vp9LoopFilterParams();

    /**
     * The loop filters, one for each coder, so that the threads coding a frame can also filter
     * its rows.
     */
    private final Vp9LoopFilter[] loopFilters;

    /**
     * The progress of the rows the loop filter has finished, which orders the filtering of
     * consecutive rows on different threads.
     */
    private final Vp9Wavefront filterFront;

    /**
     * Whether the threads coding the current frame also filtered it.
     */
    private boolean filteredInLoop;

    /**
     * The scheduler of superblock rows over threads.
     */
    private final Vp9RtWavefront wavefront;

    /**
     * The scene detection of each superblock row: the sum of the sampled superblocks' sums of
     * absolute differences, the number of samples and the number of samples without difference,
     * three entries per row.
     */
    private long[] sceneRows = new long[0];

    /**
     * The number of bands {@link #sceneDetection()} splits the superblock rows into.
     */
    private int sceneBands;

    /**
     * Runs one band of {@link #sceneDetection()}, created once so that a frame allocates no band.
     */
    private final ParallelBands.Band sceneBand = this::sceneBandRows;

    /**
     * The picture {@link #copyReference} copies the reconstruction into.
     */
    private Vp9EncPicture copyTarget;

    /**
     * Copies the reconstruction into {@link #copyTarget} while the bitstream is packed, created
     * once so that a frame allocates no task body.
     */
    private final Runnable copyReference = this::copyReference;

    /**
     * The speed setting, 5 to 9.
     */
    int speed;

    /**
     * Whether frames are coded error resilient, without dependence on previous probabilities and
     * motion vectors.
     */
    boolean errorResilient;

    /**
     * Whether the cyclic refresh is enabled.
     */
    boolean cyclicRefreshEnabled;

    /**
     * The frames between automatic key frames, or zero for none.
     */
    int keyframeInterval;

    /**
     * Whether frames are dropped when the buffer underflows.
     */
    boolean dropFrames;

    /**
     * The undershoot percentage of the frame target.
     */
    int underShootPct = 50;

    /**
     * The overshoot percentage of the frame target.
     */
    int overShootPct = 50;

    /**
     * The target bitrate in bits per second.
     */
    long targetBandwidth;

    /**
     * The frame rate measured from the timestamps.
     */
    double framerate;

    /**
     * The start timestamp of the first frame, or {@link Long#MIN_VALUE} before it.
     */
    private long firstTimeStamp = Long.MIN_VALUE;

    /**
     * The start timestamp of the previous frame.
     */
    private long lastTimeStampSeen;

    /**
     * The end timestamp of the previous frame.
     */
    private long lastEndTimeStampSeen;

    /**
     * The number of frames received, coded or dropped.
     */
    long currentFrame;

    /**
     * Whether the current frame is a key frame.
     */
    boolean keyFrame;

    /**
     * Whether the previous coded frame was a key frame.
     */
    boolean lastKeyFrame;

    /**
     * The quantizer index of the current frame.
     */
    int baseQindex;

    /**
     * The transform mode of the current frame.
     */
    int txMode;

    /**
     * Whether the current frame is lossless: its quantizer index is 0, which with the zero delta
     * quantizers this encoder signals makes every block use the 4x4 Walsh-Hadamard transform.
     */
    boolean lossless;

    /**
     * The interpolation filter of the current frame.
     */
    int interpFilter;

    /**
     * Whether the current frame codes motion vectors with eighth-sample precision.
     */
    boolean allowHp;

    /**
     * Whether the current frame refreshes the last reference.
     */
    boolean refreshLast;

    /**
     * Whether the current frame refreshes the golden reference.
     */
    boolean refreshGolden;

    /**
     * Whether the current frame refreshes the altref reference.
     */
    boolean refreshAlt;

    /**
     * The slots the current frame refreshes.
     */
    int refreshMask;

    /**
     * The references the current frame may use, bit 0 for last, 1 for golden and 2 for altref.
     */
    int refFrameFlags = 7;

    /**
     * The probability context the current frame loads and refreshes.
     */
    int frameContextIdx;

    /**
     * Whether the current frame saves its adapted probabilities.
     */
    boolean refreshFrameContext;

    /**
     * The loop filter level of the current frame.
     */
    int filterLevel;

    /**
     * The loop filter level deltas by reference.
     */
    final int[] lfRefDeltas = {1, 0, -1, -1};

    /**
     * The loop filter level deltas by mode.
     */
    final int[] lfModeDeltas = {0, 0};

    /**
     * The reference deltas last written to the bitstream.
     */
    final int[] lfLastRefDeltas = new int[4];

    /**
     * The mode deltas last written to the bitstream.
     */
    final int[] lfLastModeDeltas = new int[2];

    /**
     * Whether the current frame writes the loop filter deltas.
     */
    boolean lfDeltaUpdate;

    /**
     * Whether the current frame predicts motion vectors from the previous frame's.
     */
    boolean usePrevFrameMvs;

    /**
     * Whether the superblock source differences are computed for the current frame.
     */
    boolean computeSourceSad;

    /**
     * Whether the current frame detects skin.
     */
    boolean useSkinDetection;

    /**
     * Whether the coefficient probabilities of 16x16 and 32x32 transforms are left unchanged.
     */
    boolean txSizeSearchTx8x8;

    /**
     * The rate-distortion multiplier of the current frame.
     */
    int rdMult;

    /**
     * The temporal layer of the current frame.
     */
    int temporalLayerId;

    /**
     * Whether the current frame refreshes no reference.
     */
    boolean nonReferenceFrame;

    /**
     * Whether the previous frame was dropped.
     */
    private boolean lastFrameDropped;

    /**
     * The quantizer index of the previous coded frame.
     */
    private int lastQuantizer;

    /**
     * Whether the previous coded frame was a key frame, as reported to the caller.
     */
    private boolean lastFrameKey;

    /**
     * Whether any frame was coded.
     */
    private boolean anyFrameCoded;

    /**
     * The references the next frame may use, from the slots after the current frame.
     */
    private int nextRefFrameFlags = 7;

    /**
     * The number of coded frames.
     */
    private long codedFrames;

    /**
     * Whether each inverse transform size and type, {@code [tx size * 4 + tx type]}, runs on the
     * decoder's vector kernels rather than its scalar ones; set for good once the inter frames of
     * a window have called it {@link Vp9RtCoder#VECTOR_TRANSFORM_CALLS} times per frame on
     * average, and read by the coders only while frames are coded.
     */
    final boolean[] vectorTransforms = new boolean[16];

    /**
     * The inverse transform calls of each size and type in the inter frames of the current
     * window.
     */
    private final int[] transformWindow = new int[16];

    /**
     * The number of inter frames in the current window.
     */
    private int transformWindowFrames;

    /**
     * The bits per macroblock of each quantizer index with the cyclic refresh applied, rebuilt
     * every frame.
     */
    private final int[] crModel = new int[256];

    /**
     * {@return the number of frames coded so far, dropped frames excluded}
     */
    long codedFrames() {
        return codedFrames;
    }

    /**
     * The rate control of each temporal layer.
     */
    private final Vp9RtRateControl[] layerRc = {new Vp9RtRateControl(), new Vp9RtRateControl(),
            new Vp9RtRateControl()};

    /**
     * The cumulative target bitrate of each temporal layer.
     */
    private final long[] layerTarget = new long[3];

    /**
     * The cumulative frame rate of each temporal layer.
     */
    private final double[] layerFramerate = new double[3];

    /**
     * The average frame size of each temporal layer alone.
     */
    private final int[] layerAvgFrameSize = new int[3];

    /**
     * The position of the current frame in the layer pattern, restarted by key frames.
     */
    private long frameInLayer;

    /**
     * The number of frames coded with temporal layers.
     */
    private long currentSuperframe;

    /**
     * Creates an encoder for a frame size.
     *
     * <p>The tile column count is clamped to the range the width allows.
     *
     * @param width the frame width
     * @param height the frame height
     * @param temporalLayers the number of temporal layers, 1 to 3
     * @param tileColumnsLog2 the requested log2 of the tile column count
     * @param threads the number of threads
     * @param limits the limits of the frame allocations
     * @throws MediaException.LimitExceeded if the frame size exceeds {@code limits}
     */
    public Vp9RtEncoder(int width, int height, int temporalLayers, int tileColumnsLog2, int threads,
                        MediaLimits limits) {
        LimitChecks.checkPicture(limits, width, height);
        this.width = width;
        this.height = height;
        this.temporalLayers = temporalLayers;
        this.threads = threads;
        this.miCols = (width + 7) >> 3;
        this.miRows = (height + 7) >> 3;
        this.sbCols = (miCols + 7) >> 3;
        this.sbRows = (miRows + 7) >> 3;
        this.mbs = ((miRows + 1) >> 1) * ((miCols + 1) >> 1);
        var minLog2 = 0;
        while ((64 << minLog2) < sbCols) {
            minLog2++;
        }
        var maxLog2 = 1;
        while ((sbCols >> maxLog2) >= 4) {
            maxLog2++;
        }
        maxLog2--;
        this.minLog2TileCols = minLog2;
        this.maxLog2TileCols = Math.max(maxLog2, minLog2);
        this.log2TileCols = Math.clamp(tileColumnsLog2, minLog2TileCols, maxLog2TileCols);
        var maxTiles = 1 << maxLog2TileCols;
        this.tileStarts = new int[maxTiles + 1];
        var units = miRows * miCols;
        miPool = new ModeInfo[units];
        miGrid = new ModeInfo[units];
        for (var i = 0; i < units; i++) {
            miPool[i] = new ModeInfo();
            miGrid[i] = miPool[i];
        }
        curFrameMvs = new int[units * 4];
        prevFrameMvs = new int[units * 4];
        var alignedCols = (miCols + 7) & ~7;
        abovePartition = new byte[alignedCols + 8];
        for (var plane = 0; plane < 3; plane++) {
            aboveEntropy[plane] = new byte[2 * alignedCols + 16];
        }
        segmentationMap = new byte[units];
        lastSegMap = new byte[units];
        blockModeContext = new int[units];
        blockRefMv = new int[units];
        prevPartition = new byte[units];
        var sbs = sbCols * sbRows;
        prevSegmentId = new byte[sbs];
        prevVarianceLow = new boolean[sbs * 25];
        copiedFrameCnt = new int[sbs];
        contentStateSbFd = new int[sbs];
        consecZeroMv = new byte[units];
        cr = new Vp9RtCyclicRefresh(miRows, miCols);
        noise = new Vp9RtNoiseEstimate(width, height);
        content = new Vp9RtContent(this);
        content.resize(miRows, miCols);
        rowThreshFact = new int[maxTiles * sbRows][];
        for (var i = 0; i < rowThreshFact.length; i++) {
            rowThreshFact[i] = new int[13 * Vp9RtPickMode.MAX_MODES];
            Arrays.fill(rowThreshFact[i], Vp9RtPickMode.RD_THRESH_INIT_FACT);
        }
        coders = new Vp9RtCoder[Math.max(1, threads)];
        for (var i = 0; i < coders.length; i++) {
            coders[i] = new Vp9RtCoder(this);
        }
        tokens = new Vp9RtTokens[maxTiles * sbRows];
        for (var i = 0; i < tokens.length; i++) {
            tokens[i] = new Vp9RtTokens(1024);
        }
        bitstream = new Vp9RtBitstream(this);
        recon.allocate(sbCols * 64, sbRows * 64, 1, 1, 8, limits);
        recon.width = width;
        recon.height = height;
        recon.alignedWidth = miCols * 8;
        recon.alignedHeight = miRows * 8;
        for (var plane = 0; plane < 3; plane++) {
            var ss = plane == 0 ? 0 : 1;
            recon.planeWidths[plane] = (miCols * 8) >> ss;
            recon.planeHeights[plane] = (miRows * 8) >> ss;
            recon.cropWidths[plane] = (width + ss) >> ss;
            recon.cropHeights[plane] = (height + ss) >> ss;
        }
        lfParams.setGrid(miGrid, miRows, miCols);
        loopFilters = new Vp9LoopFilter[coders.length];
        for (var i = 0; i < loopFilters.length; i++) {
            loopFilters[i] = new Vp9LoopFilter(lfParams);
        }
        filterFront = new Vp9Wavefront(sbRows, true);
        wavefront = new Vp9RtWavefront(this, maxTiles * sbRows);
        mvCostRow = mvCostRowLp;
        mvCostCol = mvCostColLp;
        computeTiles();
    }

    /**
     * Computes the first column of each tile column.
     */
    private void computeTiles() {
        var tiles = 1 << log2TileCols;
        for (var i = 0; i <= tiles; i++) {
            tileStarts[i] = Math.min(((i * sbCols) >> log2TileCols) << 3, miCols);
        }
    }

    /**
     * Applies a configuration, before the first frame or between frames.
     *
     * <p>Before the first frame the rate control starts from the configured frame rate; later the
     * measured frame rate stays and the buffer levels are clamped to the new buffer size. Error
     * resilience is forced when temporal layers are used. The temporal layers, tile columns,
     * threads and limits of {@code options} are ignored: the constructor fixed them.
     *
     * @param options the bitrate, frame rate, quantizer window, key frame interval, speed, error
     * resilience, cyclic refresh, frame dropping and buffer model to apply
     */
    public void configure(Vp9EncoderOptions options) {
        var bitrate = (int) options.bitrate();
        var frameRate = options.frameRate().toDouble();
        var running = anyFrameCoded;
        this.speed = options.speed().level();
        this.errorResilient = options.errorResilient() || temporalLayers > 1;
        this.cyclicRefreshEnabled = options.adaptiveQuantization();
        this.keyframeInterval = options.keyframeInterval().orElse(0);
        this.dropFrames = options.dropFrames();
        this.targetBandwidth = bitrate;
        rc.bestQuality = Vp9EncTables.quantizerToQindex(options.minQuantizer());
        rc.worstQuality = Vp9EncTables.quantizerToQindex(options.maxQuantizer());
        rc.startingBufferLevel = options.initialBufferLevel().toMillis() * bitrate / 1000;
        rc.optimalBufferLevel = options.optimalBufferLevel().toMillis() * bitrate / 1000;
        rc.maximumBufferSize = options.bufferSize().toMillis() * bitrate / 1000;
        rc.bitsOffTarget = Math.min(rc.bitsOffTarget, rc.maximumBufferSize);
        rc.bufferLevel = Math.min(rc.bufferLevel, rc.maximumBufferSize);
        if (!running) {
            framerate = frameRate;
            rc.updateFramerate(bitrate, frameRate, mbs);
            rc.init(rc.bestQuality, rc.worstQuality, frameRate, width, height);
        } else {
            rc.baselineGfInterval = (Vp9RtRateControl.MIN_GF_INTERVAL + Vp9RtRateControl.MAX_GF_INTERVAL) / 2;
            rc.updateFramerate(bitrate, framerate, mbs);
            rc.setGfIntervalRange(framerate, width, height);
        }
        if (temporalLayers > 1) {
            configureLayers(running);
        }
        checkResetRcFlag();
    }

    /**
     * Resets the buffers to their optimal level after a large change of the frame budget, a rise
     * by more than half or a fall by more than half ({@code vp9_check_reset_rc_flag} and
     * {@code vp9_svc_check_reset_layer_rc_flag}).
     *
     * <p>Without the reset, the buffer level in bits carried over from the old bitrate would lie
     * far from the new optimal level, and the rate control would spend seconds draining or
     * refilling it instead of following the new target.
     */
    private void checkResetRcFlag() {
        if (currentFrame <= 1) {
            return;
        }
        var check = temporalLayers > 1 ? layerRc[temporalLayers - 1] : rc;
        if (check.avgFrameBandwidth / 3 <= (check.lastAvgFrameBandwidth >> 1)
                && check.avgFrameBandwidth >= (check.lastAvgFrameBandwidth >> 1)) {
            return;
        }
        if (temporalLayers > 1) {
            for (var tl = 0; tl < temporalLayers; tl++) {
                resetBuffer(layerRc[tl]);
            }
        } else {
            resetBuffer(rc);
        }
    }

    /**
     * Sets a rate control's buffer to its optimal level and forgets its last quantizer
     * adjustments.
     *
     * @param state the rate control
     */
    private static void resetBuffer(Vp9RtRateControl state) {
        state.rc1Frame = 0;
        state.rc2Frame = 0;
        state.bitsOffTarget = state.optimalBufferLevel;
        state.bufferLevel = state.optimalBufferLevel;
    }

    /**
     * Distributes the bitrate and buffers over the temporal layers ({@code
     * vp9_update_layer_context_change_config}).
     *
     * @param running whether frames were already coded, in which case the layer buffers are kept
     */
    private void configureLayers(boolean running) {
        var fractions = temporalLayers == 2 ? new double[]{0.6, 1.0} : new double[]{0.4, 0.6, 1.0};
        for (var tl = 0; tl < temporalLayers; tl++) {
            layerTarget[tl] = Math.round(targetBandwidth * fractions[tl]);
            var lrc = layerRc[tl];
            if (!running) {
                lrc.copyFrom(rc);
                lrc.lastQInter = rc.worstQuality;
                lrc.avgFrameQindexInter = rc.worstQuality;
                lrc.avgFrameQindexKey = rc.worstQuality;
                lrc.bufferLevel = rc.startingBufferLevel * layerTarget[tl] / Math.max(1, targetBandwidth);
                lrc.bitsOffTarget = lrc.bufferLevel;
            }
            var alloc = (double) layerTarget[tl] / targetBandwidth;
            lrc.startingBufferLevel = (long) (rc.startingBufferLevel * alloc + 0.5);
            lrc.optimalBufferLevel = (long) (rc.optimalBufferLevel * alloc + 0.5);
            lrc.maximumBufferSize = (long) (rc.maximumBufferSize * alloc + 0.5);
            lrc.bitsOffTarget = Math.min(lrc.bitsOffTarget, lrc.maximumBufferSize);
            lrc.bufferLevel = Math.min(lrc.bufferLevel, lrc.maximumBufferSize);
            layerFramerate[tl] = framerate / (1 << (temporalLayers - 1 - tl));
            lrc.avgFrameBandwidth = (int) Math.round(layerTarget[tl] / layerFramerate[tl]);
            lrc.maxFrameBandwidth = rc.maxFrameBandwidth;
            lrc.worstQuality = rc.worstQuality;
            lrc.bestQuality = rc.bestQuality;
        }
    }

    /**
     * Updates the frame rate and frame budgets of a temporal layer ({@code
     * vp9_update_temporal_layer_framerate}).
     *
     * @param tl the layer
     */
    private void updateTemporalLayerFramerate(int tl) {
        var lrc = layerRc[tl];
        layerFramerate[tl] = framerate / (1 << (temporalLayers - 1 - tl));
        lrc.avgFrameBandwidth = (int) Math.round(layerTarget[tl] / layerFramerate[tl]);
        lrc.maxFrameBandwidth = rc.maxFrameBandwidth;
        if (tl == 0) {
            layerAvgFrameSize[tl] = lrc.avgFrameBandwidth;
        } else {
            var prevFramerate = framerate / (1 << (temporalLayers - tl));
            layerAvgFrameSize[tl] = (int) Math.round((layerTarget[tl] - layerTarget[tl - 1])
                    / (layerFramerate[tl] - prevFramerate));
        }
    }

    /**
     * Loads the rate control of a temporal layer, keeping the key frame counters ({@code
     * vp9_restore_layer_context}).
     *
     * @param tl the layer
     */
    private void restoreLayer(int tl) {
        var sinceKey = rc.framesSinceKey;
        var toKey = rc.framesToKey;
        rc.copyFrom(layerRc[tl]);
        rc.framesSinceKey = sinceKey;
        rc.framesToKey = toKey;
    }

    /**
     * Selects the layer, references and refreshes of the next frame in the layer pattern ({@code
     * vp9_one_pass_svc_start_layer} with the 0101 and 0212 patterns of {@code
     * set_flags_and_fb_idx_for_temporal_mode2} and {@code 3}).
     *
     * @param key whether the frame is a key frame, which restarts the pattern
     */
    private void startLayer(boolean key) {
        if (key) {
            frameInLayer = 0;
        }
        if (temporalLayers == 2) {
            temporalLayerId = (int) (frameInLayer & 1);
            refreshLast = temporalLayerId == 0;
            refreshAlt = temporalLayerId == 1;
            refSlot[0] = 0;
            refSlot[1] = 0;
            refSlot[2] = temporalLayerId == 0 ? 0 : 1;
        } else {
            var f = (int) (frameInLayer % 4);
            temporalLayerId = (f & 1) != 0 ? 2 : f >> 1;
            refreshLast = temporalLayerId == 0;
            refreshAlt = temporalLayerId == 1;
            refSlot[0] = f == 3 ? 1 : 0;
            refSlot[1] = f == 3 ? 1 : 0;
            refSlot[2] = temporalLayerId == 0 ? 0 : 1;
        }
        refreshGolden = false;
        refFrameFlags = 1;
        nonReferenceFrame = !key && !refreshLast && !refreshAlt;
        updateTemporalLayerFramerate(temporalLayerId);
        restoreLayer(temporalLayerId);
    }

    /**
     * Computes the frame type and target of a temporal layer frame and charges the buffers of the
     * layers it belongs to ({@code vp9_rc_get_svc_params} and {@code
     * update_layer_buffer_level_preencode}).
     *
     * @param key whether the frame is a key frame
     */
    private void getSvcParams(boolean key) {
        keyFrame = key;
        int target;
        if (key) {
            rc.kfBoost = Vp9RtRateControl.DEFAULT_KF_BOOST;
            rc.framesToKey = keyframeInterval > 0 ? keyframeInterval : Integer.MAX_VALUE / 2;
            refFrameFlags = 0;
            target = rc.iframeTarget(currentFrame == 0, layerFramerate[temporalLayerId], 0);
        } else {
            target = rc.pframeTarget(underShootPct, overShootPct, 0, layerAvgFrameSize[temporalLayerId]);
        }
        if (cyclicRefreshEnabled) {
            cr.updateParameters(this);
        }
        rc.setFrameTarget(target, width, height);
        for (var i = temporalLayerId; i < temporalLayers; i++) {
            var lrc = layerRc[i];
            lrc.bitsOffTarget += Math.round(layerTarget[i] / layerFramerate[i]);
            lrc.bitsOffTarget = Math.min(lrc.bitsOffTarget, lrc.maximumBufferSize);
            lrc.bufferLevel = lrc.bitsOffTarget;
            if (i == temporalLayerId) {
                rc.bitsOffTarget = lrc.bitsOffTarget;
                rc.bufferLevel = lrc.bufferLevel;
            }
        }
    }

    /**
     * Stores the rate control of the current layer and advances the layer pattern.
     */
    private void saveLayer() {
        layerRc[temporalLayerId].copyFrom(rc);
        frameInLayer++;
        currentSuperframe++;
    }

    /**
     * {@return whether the last coded frame was a key frame}
     */
    public boolean lastKeyFrame() {
        return lastFrameKey;
    }

    /**
     * Returns the quantizer index of the last coded frame.
     *
     * @return the quantizer index, 0 to 255
     */
    public int lastQuantizer() {
        return lastQuantizer;
    }

    /**
     * Returns the temporal layer of the last frame.
     *
     * @return the layer, 0 for the base
     */
    public int lastLayer() {
        return temporalLayerId;
    }

    /**
     * Returns the rate control buffer level.
     *
     * @return the buffer level in bits, negative when underflowing
     */
    public long bufferLevel() {
        return rc.bufferLevel;
    }

    /**
     * Copies the reconstruction of the last coded frame, the picture a decoder outputs.
     *
     * @param out receives the reconstruction; its size must be the encoder's
     */
    public void copyReconstruction(YuvPicture out) {
        for (var plane = 0; plane < 3; plane++) {
            var dst = plane == 0 ? out.y() : plane == 1 ? out.u() : out.v();
            var dstStride = plane == 0 ? out.yStride() : out.uvStride();
            Planes.copy(recon.planes8[plane], 0, recon.strides[plane], dst, 0, dstStride, recon.cropWidths[plane],
                    recon.cropHeights[plane]);
        }
    }

    /**
     * Returns the first 8x8 column of a tile column.
     *
     * @param tile the tile column, or the tile count for the end
     * @return the column
     */
    int tileMiColStart(int tile) {
        return tileStarts[tile];
    }

    /**
     * Returns the adaptive threshold factors of a tile superblock row.
     *
     * @param tile the tile column
     * @param sbRow the superblock row
     * @return the factors by block size and mode
     */
    int[] rowThreshFact(int tile, int sbRow) {
        return rowThreshFact[tile * sbRows + sbRow];
    }

    /**
     * Returns the token buffer of a tile superblock row.
     *
     * @param tile the tile column
     * @param sbRow the superblock row
     * @return the token buffer
     */
    Vp9RtTokens rowTokens(int tile, int sbRow) {
        return tokens[tile * sbRows + sbRow];
    }

    /**
     * Returns the index of the superblock holding an 8x8 block.
     *
     * @param row the 8x8 row
     * @param col the 8x8 column
     * @return the superblock index
     */
    int sbOffset(int row, int col) {
        return sbCols * (row >> 3) + (col >> 3);
    }

    /**
     * Returns the picture of a reference.
     *
     * @param ref the reference, {@link Vp9Constants#LAST_FRAME} to {@link
     * Vp9Constants#ALTREF_FRAME}
     * @return the picture, or {@code null} before the first frame
     */
    Vp9EncPicture activeRef(int ref) {
        return slots[refSlot[ref - 1]];
    }

    /**
     * Returns the picture of a reference slot.
     *
     * @param slot the reference slot, from 0 to 7
     * @return the picture, or {@code null} if never refreshed
     */
    Vp9EncPicture slotPicture(int slot) {
        return slots[slot];
    }

    /**
     * Returns whether the current frame may use a reference.
     *
     * @param ref the reference
     * @return whether the reference is usable
     */
    boolean refFrameFlag(int ref) {
        return (refFrameFlags & (1 << (ref - 1))) != 0;
    }

    /**
     * Returns whether the current frame may use the golden reference.
     *
     * @return whether the golden reference is usable
     */
    boolean refFrameFlagGolden() {
        return refFrameFlag(GOLDEN_FRAME);
    }

    /**
     * Returns whether the current frame is a key frame of a layered stream.
     *
     * @return whether it is a layered key frame
     */
    boolean layerKeyFrame() {
        return temporalLayers > 1 && keyFrame;
    }

    /**
     * Returns whether the cyclic refresh segments the current frame.
     *
     * @return whether it is active
     */
    boolean cyclicRefreshActive() {
        return cyclicRefreshEnabled && segEnabled;
    }

    /**
     * Returns the smallest segment a block covers in a segment map ({@code get_segment_id}).
     *
     * @param map the segment map
     * @param bsize the block size
     * @param row the block's 8x8 row
     * @param col the block's 8x8 column
     * @return the segment
     */
    int segmentIdOf(byte[] map, int bsize, int row, int col) {
        var xmis = Math.min(miCols - col, Vp9RtCoder.NUM_8X8_WIDE[bsize]);
        var ymis = Math.min(miRows - row, Vp9RtCoder.NUM_8X8_HIGH[bsize]);
        var id = Integer.MAX_VALUE;
        var base = row * miCols + col;
        for (var y = 0; y < ymis; y++) {
            for (var x = 0; x < xmis; x++) {
                id = Math.min(id, map[base + y * miCols + x]);
            }
        }
        return id;
    }

    /**
     * Returns the quantizer index of a segment ({@code vp9_get_qindex}).
     *
     * @param segmentId the segment
     * @return the quantizer index
     */
    int segQindex(int segmentId) {
        if (segEnabled && segAltQEnabled[segmentId]) {
            return Math.clamp(baseQindex + segAltQ[segmentId], 0, 255);
        }
        return baseQindex;
    }

    /**
     * Enables segmentation with map and data updates and clears every segment feature.
     */
    void enableSegmentation() {
        segEnabled = true;
        segUpdateMap = true;
        segUpdateData = true;
        Arrays.fill(segAltQEnabled, false);
        Arrays.fill(segAltQ, 0);
    }

    /**
     * Disables segmentation.
     */
    void disableSegmentation() {
        segEnabled = false;
        segUpdateMap = false;
        segUpdateData = false;
    }

    /**
     * Sets the quantizer delta of a segment.
     *
     * @param segment the segment, from 0 to 7
     * @param delta the quantizer index delta
     */
    void setSegmentAltQ(int segment, int delta) {
        segAltQEnabled[segment] = true;
        segAltQ[segment] = delta;
    }

    /**
     * Returns the rate-distortion multiplier of a quantizer index for the current frame type
     * ({@code vp9_compute_rd_mult_based_on_qindex} with the one-pass real-time factors).
     *
     * @param qindex the quantizer index
     * @return the multiplier, at least one
     */
    int computeRdMult(int qindex) {
        var q = Vp9EncTables.dcQuant(qindex, 0);
        var rdmult = q * q;
        double mult;
        if (keyFrame) {
            mult = 4.35 + 0.001 * qindex;
        } else if (refreshGolden || refreshAlt) {
            mult = 4.25 + 0.001 * qindex;
        } else {
            mult = 4.15 + 0.001 * qindex;
        }
        rdmult = (int) ((double) rdmult * mult);
        return rdmult > 0 ? rdmult : 1;
    }

    /**
     * Records the references and motion vectors of a coded block for the next frame's predictions,
     * unless frames are error resilient.
     *
     * @param m the block's mode information
     * @param row the block's 8x8 row
     * @param col the block's 8x8 column
     * @param bsize the block size
     */
    void storeFrameMvs(ModeInfo m, int row, int col, int bsize) {
        if (errorResilient) {
            return;
        }
        var xmis = Math.min(miCols - col, Vp9RtCoder.NUM_8X8_WIDE[bsize]);
        var ymis = Math.min(miRows - row, Vp9RtCoder.NUM_8X8_HIGH[bsize]);
        var mvs = curFrameMvs;
        for (var y = 0; y < ymis; y++) {
            for (var x = 0; x < xmis; x++) {
                var i = ((row + y) * miCols + col + x) * 4;
                mvs[i] = m.ref0;
                mvs[i + 1] = m.ref1;
                mvs[i + 2] = m.mv0;
                mvs[i + 3] = m.mv1;
            }
        }
    }

    /**
     * Updates the zero motion counters of the 8x8 blocks a coded block covers ({@code
     * update_zeromv_cnt}).
     *
     * @param m the block's mode information
     * @param row the block's 8x8 row
     * @param col the block's 8x8 column
     * @param bsize the block size
     */
    void updateZeroMvCount(ModeInfo m, int row, int col, int bsize) {
        var xmis = Math.min(miCols - col, Vp9RtCoder.NUM_8X8_WIDE[bsize]);
        var ymis = Math.min(miRows - row, Vp9RtCoder.NUM_8X8_HIGH[bsize]);
        var mvRow = m.mv0 >> 16;
        var mvCol = (short) m.mv0;
        var zero = m.ref0 == LAST_FRAME && Math.abs(mvRow) < 8 && Math.abs(mvCol) < 8;
        for (var y = 0; y < ymis; y++) {
            for (var x = 0; x < xmis; x++) {
                var i = (row + y) * miCols + col + x;
                if (zero) {
                    if ((consecZeroMv[i] & 0xFF) < 255) {
                        consecZeroMv[i]++;
                    }
                } else {
                    consecZeroMv[i] = 0;
                }
            }
        }
    }

    /**
     * Codes a frame.
     *
     * <p>The frame is a key frame when it is the first, when forced or when the key frame interval
     * elapses. It is dropped, returning {@code null}, only when dropping is enabled and the buffer
     * underflows.
     *
     * @param picture the frame, of the encoder's size
     * @param start the start timestamp in {@link #TICKS_PER_SECOND} ticks
     * @param end the end timestamp in ticks
     * @param forceKey whether to code a key frame
     * @return the coded frame, or {@code null} if it was dropped
     */
    public byte[] encode(YuvPicture picture, long start, long end, boolean forceKey) {
        var previous = lastSource;
        lastSource = source;
        source = previous != null ? previous : new Vp9EncPicture(width, height);
        source.copyFrom(picture, coders.length);
        adjustFrameRate(start, end);
        refreshLast = true;
        refreshGolden = false;
        refreshAlt = false;
        refreshFrameContext = !errorResilient;
        nonReferenceFrame = false;
        temporalLayerId = 0;
        if (temporalLayers > 1) {
            var key = currentFrame == 0 || forceKey
                    || (keyframeInterval > 0 && currentSuperframe % keyframeInterval == 0);
            startLayer(key);
            getSvcParams(key);
        } else {
            refFrameFlags = nextRefFrameFlags;
            getOnePassCbrParams(forceKey);
        }
        var size = encodeFrameToDataRate();
        if (temporalLayers > 1) {
            saveLayer();
        }
        if (size < 0) {
            lastFrameDropped = true;
            return null;
        }
        lastFrameDropped = false;
        anyFrameCoded = true;
        codedFrames++;
        return Arrays.copyOf(bitstream.buffer(), Math.max(1, size));
    }

    /**
     * Updates the frame rate from a frame's timestamps ({@code adjust_frame_rate}).
     *
     * @param start the start timestamp
     * @param end the end timestamp
     */
    private void adjustFrameRate(long start, long end) {
        long thisDuration;
        var step = 0;
        if (firstTimeStamp == Long.MIN_VALUE) {
            firstTimeStamp = start;
        }
        if (start == firstTimeStamp) {
            thisDuration = end - start;
            step = 1;
        } else {
            var lastDuration = lastEndTimeStampSeen - lastTimeStampSeen;
            thisDuration = end - lastEndTimeStampSeen;
            if (lastDuration != 0) {
                step = (int) ((thisDuration - lastDuration) * 10 / lastDuration);
            }
        }
        if (thisDuration > 0) {
            if (step != 0) {
                newFramerate((double) TICKS_PER_SECOND / thisDuration);
            } else {
                var interval = Math.min((double) (end - firstTimeStamp), (double) TICKS_PER_SECOND);
                var avgDuration = TICKS_PER_SECOND / framerate;
                avgDuration *= (interval - avgDuration + thisDuration);
                avgDuration /= interval;
                newFramerate(TICKS_PER_SECOND / avgDuration);
            }
        }
        lastTimeStampSeen = start;
        lastEndTimeStampSeen = end;
    }

    /**
     * Applies a measured frame rate to the rate control ({@code vp9_new_framerate}).
     *
     * @param rate the frame rate
     */
    private void newFramerate(double rate) {
        framerate = rate < 0.1 ? 30 : rate;
        rc.updateFramerate(targetBandwidth, framerate, mbs);
        rc.setGfIntervalRange(framerate, width, height);
    }

    /**
     * Computes the frame type, golden refresh and target of a single layer frame ({@code
     * vp9_rc_get_one_pass_cbr_params}).
     *
     * @param forceKey whether a key frame is forced
     */
    private void getOnePassCbrParams(boolean forceKey) {
        var autoKey = keyframeInterval > 0;
        if (currentFrame == 0 || forceKey || (autoKey && rc.framesToKey == 0)) {
            keyFrame = true;
            rc.framesToKey = autoKey ? keyframeInterval : Integer.MAX_VALUE / 2;
            rc.kfBoost = Vp9RtRateControl.DEFAULT_KF_BOOST;
        } else {
            keyFrame = false;
        }
        if (rc.framesTillGfUpdateDue == 0) {
            if (cyclicRefreshEnabled) {
                cr.setGoldenUpdate(rc);
            } else {
                rc.baselineGfInterval = (rc.minGfInterval + rc.maxGfInterval) / 2;
            }
            rc.framesTillGfUpdateDue = rc.baselineGfInterval;
            if (rc.framesTillGfUpdateDue > rc.framesToKey) {
                rc.framesTillGfUpdateDue = rc.framesToKey;
            }
            refreshGolden = true;
            rc.gfuBoost = Vp9RtRateControl.DEFAULT_GF_BOOST;
        }
        if (cyclicRefreshEnabled) {
            cr.updateParameters(this);
        }
        int target;
        if (keyFrame) {
            target = rc.iframeTarget(currentFrame == 0, framerate, 0);
        } else {
            target = rc.pframeTarget(underShootPct, overShootPct, 0, 0);
        }
        rc.setFrameTarget(target, width, height);
        rc.updateBufferPreencode();
    }

    /**
     * Codes, filters and writes the current frame and updates the references and rate control
     * ({@code encode_frame_to_data_rate}).
     *
     * @return the frame size in bytes, or -1 if the frame was dropped
     */
    private int encodeFrameToDataRate() {
        lfDeltaUpdate = false;
        if (keyFrame) {
            disableSegmentation();
            Arrays.fill(segAltQEnabled, false);
            Arrays.fill(segAltQ, 0);
        }
        if (!encodeWithoutRecodeLoop()) {
            return -1;
        }
        if (keyFrame) {
            refreshLast = true;
        }
        if (!filteredInLoop) {
            pickFilterLevel();
            applyLoopFilter();
        }
        refreshMask = keyFrame ? 0xFF : (refreshLast ? 1 << refSlot[0] : 0) | (refreshGolden ? 1 << refSlot[1] : 0)
                | (refreshAlt ? 1 << refSlot[2] : 0);
        var reference = refreshMask == 0 ? null : freePicture();
        copyTarget = reference;
        var copy = reference != null && coders.length > 1 ? ParallelBands.start(copyReference) : null;
        int size;
        try {
            size = bitstream.pack();
        } finally {
            if (copy != null) {
                copy.join();
            }
        }
        if (segUpdateMap) {
            for (var i = 0; i < miRows * miCols; i++) {
                lastSegMap[i] = (byte) miGrid[i].segmentId;
            }
        }
        updateReferenceFrames(reference, copy == null);
        if (refreshFrameContext) {
            frameContexts[frameContextIdx].copyFrom(fc);
        }
        nextRefFrameFlags = refFrameFlagsAfter();
        lastKeyFrame = keyFrame;
        lastFrameKey = keyFrame;
        lastQuantizer = baseQindex;
        postencodeUpdate(size);
        if (!keyFrame) {
            computeFrameLowMotion();
        }
        if (!errorResilient) {
            var swap = prevFrameMvs;
            prevFrameMvs = curFrameMvs;
            curFrameMvs = swap;
        }
        currentFrame++;
        return size;
    }

    /**
     * Returns the references the next frame may use: those in distinct slots, without golden when
     * it is never refreshed.
     *
     * @return the reference flags
     */
    private int refFrameFlagsAfter() {
        var lst = slots[refSlot[0]];
        var gld = slots[refSlot[1]];
        var alt = slots[refSlot[2]];
        var flags = 7;
        if (gld == lst) {
            flags &= ~2;
        }
        if (rc.framesTillGfUpdateDue == Integer.MAX_VALUE && temporalLayers == 1) {
            flags &= ~2;
        }
        if (alt == lst) {
            flags &= ~4;
        }
        if (gld == alt) {
            flags &= ~4;
        }
        return flags;
    }

    /**
     * Picks the quantizer and codes the superblocks of the current frame, coding it again at a
     * higher quantizer after an overshoot on a scene change ({@code encode_without_recode_loop}).
     *
     * @return whether the frame was coded, {@code false} if it was dropped
     */
    private boolean encodeWithoutRecodeLoop() {
        filteredInLoop = false;
        computeSourceSad = lastSource != null;
        if (temporalLayers > 1 && (temporalLayerId < temporalLayers - 1 || currentSuperframe < 1)) {
            computeSourceSad = false;
            Arrays.fill(contentStateSbFd, 0);
        }
        if (keyFrame) {
            Arrays.fill(consecZeroMv, (byte) 0);
        }
        rc.highSourceSad = false;
        if (speed >= 5) {
            sceneDetection();
        }
        noise.update(this);
        if (!keyFrame && !rc.highSourceSad && dropFrames && rc.bufferLevel < 0) {
            postencodeUpdateDropFrame();
            return false;
        }
        var noiseHigh = noise.enabled && width >= 1280 && height >= 720
                && noise.extractLevel() >= Vp9RtNoiseEstimate.MEDIUM;
        sf.set(speed, keyFrame, width, height, temporalLayers > 1, temporalLayerId, temporalLayers, nonReferenceFrame,
                noiseHigh, lastFrameDropped, rc.avgFrameLowMotion, rc.avgFrameQindexInter, currentFrame);
        setRdSpeedThresholds();
        interpFilter = sf.defaultInterpFilter;
        txSizeSearchTx8x8 = !keyFrame;
        var cbrMbs = cyclicRefreshEnabled && cr.apply ? crBitsPerMb() : null;
        var q = rc.pickQ(keyFrame, currentFrame, temporalLayers, width, height, cbrMbs, mbs);
        if (rc.forceMaxQ) {
            q = rc.worstQuality;
            rc.forceMaxQ = false;
        }
        if (!keyFrame) {
            allowHp = q < 200;
            mvCostRow = allowHp ? mvCostRowHp : mvCostRowLp;
            mvCostCol = allowHp ? mvCostColHp : mvCostColLp;
        }
        useSkinDetection = cyclicRefreshEnabled && speed >= 5;
        baseQindex = q;
        Vp9RtPartition.setFrameThresholds(this, q);
        setupFrame();
        if (sf.overshootDetectionCbrRt == Vp9RtSpeed.FAST_DETECTION_MAXQ && rc.highSourceSad) {
            var newQ = encodedFrameOvershoot(-1, q);
            if (newQ >= 0) {
                baseQindex = newQ;
                Vp9RtPartition.setFrameThresholds(this, newQ);
            }
        }
        if (cyclicRefreshEnabled) {
            cr.setup(this);
        }
        encodeFrame(!(sf.overshootDetectionCbrRt == Vp9RtSpeed.RE_ENCODE_MAXQ && rc.highSourceSad));
        if (sf.overshootDetectionCbrRt == Vp9RtSpeed.RE_ENCODE_MAXQ && rc.highSourceSad) {
            var savedProbs = fc.probs.clone();
            var savedRefDeltas = lfLastRefDeltas.clone();
            var savedModeDeltas = lfLastModeDeltas.clone();
            var savedFilter = interpFilter;
            refreshMask = (refreshLast ? 1 << refSlot[0] : 0) | (refreshGolden ? 1 << refSlot[1] : 0)
                    | (refreshAlt ? 1 << refSlot[2] : 0);
            var frameSize = bitstream.pack() << 3;
            System.arraycopy(savedProbs, 0, fc.probs, 0, savedProbs.length);
            System.arraycopy(savedRefDeltas, 0, lfLastRefDeltas, 0, 4);
            System.arraycopy(savedModeDeltas, 0, lfLastModeDeltas, 0, 2);
            interpFilter = savedFilter;
            var newQ = encodedFrameOvershoot(frameSize, baseQindex);
            if (newQ >= 0) {
                baseQindex = newQ;
                Vp9RtPartition.setFrameThresholds(this, newQ);
                if (cyclicRefreshEnabled) {
                    Arrays.fill(segmentationMap, (byte) 0);
                    Arrays.fill(cr.lastCodedQMap, (byte) 255);
                    cr.sbIndex = 0;
                    disableSegmentation();
                }
                encodeFrame(true);
            }
        }
        if (cyclicRefreshEnabled && segEnabled && !keyFrame) {
            cr.postencode(this);
        }
        return true;
    }

    /**
     * Returns the bits per macroblock the rate model predicts at each quantizer index with the
     * cyclic refresh applied.
     *
     * @return the bits per macroblock by quantizer index
     */
    private int[] crBitsPerMb() {
        var model = crModel;
        Arrays.fill(model, 0);
        var cf = rc.correctionFactor(keyFrame);
        for (var i = rc.bestQuality; i <= rc.worstQuality; i++) {
            model[i] = cr.bitsPerMb(this, i, cf);
        }
        return model;
    }

    /**
     * Resets the past state of key and error resilient frames and loads the probabilities ({@code
     * setup_frame}).
     */
    private void setupFrame() {
        if (keyFrame || errorResilient) {
            setupPastIndependence();
        } else {
            frameContextIdx = 0;
        }
        if (keyFrame) {
            refreshGolden = true;
            refreshAlt = true;
        } else {
            fc.copyFrom(frameContexts[frameContextIdx]);
        }
    }

    /**
     * Resets the segmentation, loop filter deltas and probabilities to their defaults ({@code
     * vp9_setup_past_independence}).
     */
    private void setupPastIndependence() {
        Arrays.fill(segAltQEnabled, false);
        Arrays.fill(segAltQ, 0);
        Arrays.fill(lastSegMap, (byte) 0);
        Arrays.fill(lfLastRefDeltas, 0);
        Arrays.fill(lfLastModeDeltas, 0);
        lfRefDeltas[0] = 1;
        lfRefDeltas[1] = 0;
        lfRefDeltas[2] = -1;
        lfRefDeltas[3] = -1;
        lfModeDeltas[0] = 0;
        lfModeDeltas[1] = 0;
        lfDeltaUpdate = true;
        fc.setDefaults();
        for (var context : frameContexts) {
            context.copyFrom(fc);
        }
        frameContextIdx = 0;
    }

    /**
     * Sets the threshold multiplier of each mode ({@code vp9_set_rd_speed_thresholds}).
     */
    private void setRdSpeedThresholds() {
        Arrays.fill(threshMult, 0);
        if (sf.adaptiveRdThresh != 0) {
            threshMult[0] = 300;
            threshMult[2] = 300;
            threshMult[1] = 300;
        }
        threshMult[3] += 1000;
        threshMult[4] += 1000;
        threshMult[5] += 1000;
        threshMult[6] += 1000;
        threshMult[7] += 1000;
        threshMult[8] += 1000;
        threshMult[13] += 1000;
        threshMult[14] += 1000;
        threshMult[15] += 1000;
        threshMult[16] += 1500;
        threshMult[17] += 2000;
        threshMult[9] += 1000;
        threshMult[18] += 1500;
        threshMult[19] += 2000;
        threshMult[10] += 2000;
        threshMult[11] += 2000;
        threshMult[12] += 2000;
        threshMult[20] += 2500;
        threshMult[21] += 2500;
        threshMult[22] += 2000;
        threshMult[23] += 2000;
        for (var i = 24; i < 30; i++) {
            threshMult[i] += 2500;
        }
    }

    /**
     * Computes the mode thresholds of every segment and block size ({@code set_block_thresholds}).
     */
    private void setBlockThresholds() {
        for (var seg = 0; seg < 8; seg++) {
            var qindex = Math.clamp(segQindex(seg), 0, 255);
            var q = Math.max((int) (Math.pow(Vp9EncTables.dcQuant(qindex, 0) / 4.0, 1.25) * 5.12), 8);
            for (var bsize = 0; bsize < 13; bsize++) {
                var t = q * RD_THRESH_BLOCK_SIZE_FACTOR[bsize];
                var threshMax = Integer.MAX_VALUE / t;
                var base = (seg * 13 + bsize) * Vp9RtPickMode.MAX_MODES;
                for (var i = 0; i < Vp9RtPickMode.MAX_MODES; i++) {
                    rdThreshes[base + i] = threshMult[i] < threshMax ? threshMult[i] * t / 4 : Integer.MAX_VALUE;
                }
            }
        }
    }

    /**
     * Computes the rate-distortion multiplier, thresholds and, every eighth frame and on key
     * frames, the mode and motion vector costs ({@code vp9_initialize_rd_consts}).
     */
    private void initializeRdConsts() {
        rdMult = computeRdMult(baseQindex);
        setBlockThresholds();
        if ((currentFrame & 7) == 1 || keyFrame) {
            fillModeCosts();
            if (!keyFrame) {
                var probs = fc.probs;
                Vp9EncMv.buildCostTable(probs, allowHp, mvJointCost, mvCostRow, mvCostCol);
                for (var i = 0; i < 7; i++) {
                    Vp9EncTables.costTokens(interModeCost, i * 4, probs, Vp9FrameContext.INTER_MODE + i * 3,
                            Vp9Trees.INTER_MODE);
                }
            }
        }
    }

    /**
     * Computes the costs of the intra modes and interpolation filters ({@code
     * vp9_fill_mode_costs}).
     */
    private void fillModeCosts() {
        for (var i = 0; i < 10; i++) {
            for (var j = 0; j < 10; j++) {
                Vp9EncTables.costTokens(kfYModeCosts, (i * 10 + j) * 10, Vp9ProbTables.KF_Y_MODE_PROBS,
                        (i * 10 + j) * 9, Vp9Trees.INTRA_MODE);
            }
        }
        var probs = fc.probs;
        Vp9EncTables.costTokens(mbmodeCost, 0, probs, Vp9FrameContext.Y_MODE + 9, Vp9Trees.INTRA_MODE);
        for (var i = 0; i < 4; i++) {
            Vp9EncTables.costTokens(switchableInterpCosts, i * 3, probs, Vp9FrameContext.SWITCHABLE_INTERP + i * 2,
                    Vp9Trees.SWITCHABLE_INTERP);
        }
    }

    /**
     * Codes every superblock of the current frame and merges the threads' symbol counts ({@code
     * encode_frame_internal}).
     *
     * <p>When the frame is coded on several threads and this is its final coding pass, the same
     * threads also apply the loop filter, each row once the rows it reads are coded, and
     * {@link #filteredInLoop} records it.
     *
     * @param finalPass whether the frame will not be coded again, so its reconstruction can be
     * filtered as soon as it is coded
     */
    private void encodeFrame(boolean finalPass) {
        frameCounts.clear();
        lossless = baseQindex == 0;
        txMode = lossless ? 0 : keyFrame ? 2 : Vp9Constants.TX_MODE_SELECT;
        initializeRdConsts();
        var alignedCols = (miCols + 7) & ~7;
        for (var plane = 0; plane < 3; plane++) {
            Arrays.fill(aboveEntropy[plane], (byte) 0);
        }
        Arrays.fill(abovePartition, (byte) 0);
        usePrevFrameMvs = !errorResilient && anyFrameCoded && !lastKeyFrame;
        if (!keyFrame && rc.framesSinceGolden == 0 && temporalLayers == 1) {
            refFrameFlags &= ~2;
        }
        for (var i = 0; i < miGrid.length; i++) {
            miGrid[i] = miPool[i];
        }
        for (var coder : coders) {
            coder.startFrame();
        }
        var filter = finalPass && coders.length > 1 && prepareLoopFilter();
        filteredInLoop = wavefront.run(coders, 1 << log2TileCols, filter);
        for (var coder : coders) {
            frameCounts.add(coder.counts);
        }
        updateVectorTransforms();
        if (sf.skipEncodeSb) {
            var intra = 0L;
            var inter = 0L;
            var c = frameCounts.counts;
            for (var j = 0; j < 4; j++) {
                intra += c[Vp9EncCounts.INTRA_INTER + j * 2];
                inter += c[Vp9EncCounts.INTRA_INTER + j * 2 + 1];
            }
            sf.skipEncodeFrame = (intra << 2) < inter && !keyFrame;
        } else {
            sf.skipEncodeFrame = false;
        }
    }

    /**
     * Adds the inverse transform calls of an inter frame to the window, clears the coders' counts,
     * and at the end of a window moves the transforms called often enough to the decoder's vector
     * kernels.
     */
    private void updateVectorTransforms() {
        for (var kind = 0; kind < 16; kind++) {
            for (var coder : coders) {
                if (!keyFrame) {
                    transformWindow[kind] += coder.transformCalls[kind];
                }
                coder.transformCalls[kind] = 0;
            }
        }
        if (keyFrame || ++transformWindowFrames < Vp9RtCoder.VECTOR_TRANSFORM_FRAMES) {
            return;
        }
        for (var kind = 0; kind < 16; kind++) {
            if (transformWindow[kind] >= Vp9RtCoder.VECTOR_TRANSFORM_CALLS * Vp9RtCoder.VECTOR_TRANSFORM_FRAMES) {
                vectorTransforms[kind] = true;
            }
            transformWindow[kind] = 0;
        }
        transformWindowFrames = 0;
    }

    /**
     * Codes a tile superblock row.
     *
     * @param coder the coder of the calling thread
     * @param tile the tile column
     * @param sbRow the superblock row
     * @param wait the scheduler, which orders the row after the one above
     */
    void codeRow(Vp9RtCoder coder, int tile, int sbRow, Vp9RtWavefront wait) {
        coder.codeRow(tile, sbRow, rowTokens(tile, sbRow), wait);
    }

    /**
     * The amount an inter frame's loop filter level is lowered below the level libvpx derives
     * from the quantizer.
     *
     * @implNote This implementation filters inter frames one level less than libvpx: with the
     * rounding of {@link Vp9Quantizer} it lowered the bitrate at equal SSIM-Y by 0.8%
     * and at equal PSNR-Y by 0.3% on two clips.
     */
    private static final int INTER_FILTER_LEVEL_REDUCTION = 1;

    /**
     * Picks the loop filter level from the quantizer ({@code vp9_pick_filter_level} with {@code
     * LPF_PICK_FROM_Q}); a lossless frame is not filtered.
     */
    private void pickFilterLevel() {
        if (lossless) {
            filterLevel = 0;
            return;
        }
        var q = Vp9EncTables.acQuant(baseQindex, 0);
        var guess = (q * 20723 + 1015158 + (1 << 17)) >> 18;
        if (cyclicRefreshEnabled && segEnabled && (baseQindex < 200 || width * height > 320 * 240) && !keyFrame) {
            guess = (5 * guess) >> 3;
        }
        if (keyFrame) {
            guess -= 4;
        } else {
            guess -= INTER_FILTER_LEVEL_REDUCTION;
        }
        filterLevel = Math.clamp(guess, 0, 63);
    }

    /**
     * Picks the loop filter level and sets the levels of every segment, reference and mode, so
     * that the rows can be filtered while the frame is coded.
     *
     * @return whether the frame is filtered at all
     */
    private boolean prepareLoopFilter() {
        pickFilterLevel();
        if (filterLevel == 0) {
            return false;
        }
        setFilterLevels();
        filterFront.reset(sbRows);
        return true;
    }

    /**
     * Filters one superblock row of the reconstruction as one row of the filter wavefront.
     *
     * @param participant the index of the calling thread's coder
     * @param sbRow the superblock row
     * @throws java.util.concurrent.CancellationException if another thread failed
     */
    void filterRow(int participant, int sbRow) {
        loopFilters[participant].filterRow(recon, sbRow << 3, filterFront);
    }

    /**
     * Wakes the threads waiting on the filter wavefront after a failure.
     */
    void abortFilter() {
        filterFront.abort();
    }

    /**
     * Filters the reconstruction with the current level and deltas.
     */
    private void applyLoopFilter() {
        if (filterLevel == 0) {
            return;
        }
        setFilterLevels();
        for (var sbRow = 0; sbRow < sbRows; sbRow++) {
            loopFilters[0].filterRow(recon, sbRow << 3, null);
        }
    }

    /**
     * Sets the loop filter levels of every segment, reference and mode from the current level and
     * deltas.
     */
    private void setFilterLevels() {
        var scale = 1 << (filterLevel >> 5);
        var levels = lfParams.levels;
        for (var seg = 0; seg < 8; seg++) {
            var intraLevel = filterLevel + lfRefDeltas[0] * scale;
            levels[seg * 8] = Math.clamp(intraLevel, 0, 63);
            for (var ref = 1; ref < 4; ref++) {
                for (var mode = 0; mode < 2; mode++) {
                    var interLevel = filterLevel + lfRefDeltas[ref] * scale + lfModeDeltas[mode] * scale;
                    levels[(seg * 4 + ref) * 2 + mode] = Math.clamp(interLevel, 0, 63);
                }
            }
        }
    }

    /**
     * Stores the reconstruction in the slots the current frame refreshes.
     *
     * <p>With several threads, {@link #encodeFrameToDataRate()} copies the reconstruction into the
     * free picture while the calling thread writes the bitstream, which reads neither of them, so
     * the copy is already done here.
     *
     * @param picture the free picture that receives the reconstruction, or {@code null} when the
     * frame refreshes no slot
     * @param copy whether the reconstruction still has to be copied into {@code picture}
     */
    private void updateReferenceFrames(Vp9EncPicture picture, boolean copy) {
        if (picture == null) {
            return;
        }
        if (copy) {
            picture.copyFrom(recon, coders.length);
        }
        for (var i = 0; i < 8; i++) {
            if ((refreshMask & (1 << i)) != 0) {
                slots[i] = picture;
            }
        }
    }

    /**
     * Returns a reference picture no slot holds, allocating one when needed.
     *
     * @return the free picture
     * @throws IllegalStateException if every picture is in use, which the pool size rules out
     */
    private Vp9EncPicture freePicture() {
        for (var i = 0; i < pool.length; i++) {
            var candidate = pool[i];
            if (candidate == null) {
                candidate = new Vp9EncPicture(width, height);
                pool[i] = candidate;
                return candidate;
            }
            var used = false;
            for (var slot : slots) {
                if (slot == candidate) {
                    used = true;
                    break;
                }
            }
            if (!used && candidate != lastSource && candidate != source) {
                return candidate;
            }
        }
        throw new IllegalStateException("no free reference picture");
    }

    /**
     * Updates the rate control after a coded frame ({@code vp9_rc_postencode_update}).
     *
     * @param bytes the frame size in bytes
     */
    private void postencodeUpdate(int bytes) {
        rc.projectedFrameSize = bytes << 3;
        var cf = rc.correctionFactor(keyFrame);
        int projected;
        if (cyclicRefreshEnabled && segEnabled) {
            projected = cr.estimateBitsAtQ(this, cf);
        } else {
            projected = Vp9RtRateControl.estimateBitsAtQ(keyFrame, baseQindex, mbs, cf);
        }
        rc.updateRateCorrectionFactors(keyFrame, projected, baseQindex);
        if (keyFrame) {
            rc.lastQKey = baseQindex;
            rc.avgFrameQindexKey = (3 * rc.avgFrameQindexKey + baseQindex + 2) >> 2;
        } else if (!(refreshGolden || refreshAlt) || temporalLayers > 1) {
            rc.lastQInter = baseQindex;
            rc.avgFrameQindexInter = (3 * rc.avgFrameQindexInter + baseQindex + 2) >> 2;
        }
        if (baseQindex < rc.lastBoostedQindex || keyFrame || refreshGolden) {
            rc.lastBoostedQindex = baseQindex;
        }
        if (keyFrame) {
            rc.lastKfQindex = baseQindex;
        }
        rc.updateBufferPostencode(rc.projectedFrameSize);
        if (temporalLayers > 1) {
            for (var i = temporalLayerId + 1; i < temporalLayers; i++) {
                var lrc = layerRc[i];
                lrc.bitsOffTarget -= rc.projectedFrameSize;
                lrc.bitsOffTarget = Math.min(lrc.bitsOffTarget, lrc.maximumBufferSize);
                lrc.bufferLevel = lrc.bitsOffTarget;
            }
            if (keyFrame) {
                for (var i = 0; i < temporalLayers; i++) {
                    layerRc[i].lastQKey = rc.lastQKey;
                    layerRc[i].avgFrameQindexKey = rc.avgFrameQindexKey;
                }
                if (rc.projectedFrameSize / 3 > rc.avgFrameBandwidth) {
                    rc.avgFrameQindexInter = Math.max(rc.avgFrameQindexInter, (baseQindex + rc.worstQuality) >> 1);
                    for (var i = 0; i < temporalLayers; i++) {
                        layerRc[i].avgFrameQindexInter = rc.avgFrameQindexInter;
                    }
                }
            }
        }
        if (!keyFrame) {
            rc.rollingTargetBits = (int) ((rc.rollingTargetBits * 3L + rc.thisFrameTarget + 2) >> 2);
            rc.rollingActualBits = (int) ((rc.rollingActualBits * 3L + rc.projectedFrameSize + 2) >> 2);
        }
        rc.totalActualBits += rc.projectedFrameSize;
        rc.totalTargetBits += rc.avgFrameBandwidth;
        if (temporalLayers > 1) {
            rc.framesSinceGolden++;
        } else if (refreshGolden) {
            rc.framesSinceGolden = 0;
            if (rc.framesTillGfUpdateDue > 0) {
                rc.framesTillGfUpdateDue--;
            }
        } else if (!refreshAlt) {
            if (rc.framesTillGfUpdateDue > 0) {
                rc.framesTillGfUpdateDue--;
            }
            rc.framesSinceGolden++;
        }
        if (keyFrame) {
            rc.framesSinceKey = 0;
        }
        rc.framesSinceKey++;
        rc.framesToKey--;
        if (!keyFrame) {
            rc.resetHighSourceSad = false;
        }
        rc.lastAvgFrameBandwidth = rc.avgFrameBandwidth;
    }

    /**
     * Updates the rate control after a dropped frame ({@code vp9_rc_postencode_update_drop_frame}).
     */
    private void postencodeUpdateDropFrame() {
        currentFrame++;
        rc.framesSinceKey++;
        rc.framesToKey--;
        rc.rc2Frame = 0;
        rc.rc1Frame = 0;
        rc.lastAvgFrameBandwidth = rc.avgFrameBandwidth;
        rc.lastQInter = baseQindex;
    }

    /**
     * Updates the average share of blocks with little motion ({@code
     * vp9_compute_frame_low_motion}).
     */
    private void computeFrameLowMotion() {
        var count = 0;
        var units = miRows * miCols;
        for (var i = 0; i < units; i++) {
            var mi = miGrid[i];
            if (mi.ref0 == LAST_FRAME && Math.abs(mi.mv0 >> 16) < 16 && Math.abs((short) mi.mv0) < 16) {
                count++;
            }
        }
        count = 100 * count / units;
        rc.avgFrameLowMotion = (3 * rc.avgFrameLowMotion + count) >> 2;
    }

    /**
     * Detects a scene change from the sums of differences of sampled superblocks against the
     * previous source ({@code vp9_scene_detection_onepass}).
     */
    private void sceneDetection() {
        if (lastSource == null || (temporalLayers > 1 && currentSuperframe == 0)) {
            return;
        }
        rc.highSourceSad = false;
        rc.highNumBlocksWithMotion = false;
        var minThresh = 65000L;
        var thresh = 8.0f;
        if (sceneRows.length < 3 * sbRows) {
            sceneRows = new long[3 * sbRows];
        }
        var bands = Math.min(coders.length, sbRows);
        if (bands > 1) {
            sceneBands = bands;
            ParallelBands.run(bands, sceneBand);
        } else {
            for (var r = 0; r < sbRows; r++) {
                sceneRow(r);
            }
        }
        var numSamples = 0;
        var numZero = 0;
        long avgSad = 0;
        for (var r = 0; r < sbRows; r++) {
            avgSad += sceneRows[3 * r];
            numSamples += (int) sceneRows[3 * r + 1];
            numZero += (int) sceneRows[3 * r + 2];
        }
        if (numSamples > 0) {
            avgSad /= numSamples;
        }
        var threshold = Math.max(minThresh, (long) (rc.avgSourceSad * thresh) & 0xFFFFFFFFL);
        rc.highSourceSad = avgSad > threshold && rc.framesSinceKey > 2 && numZero < 3 * (numSamples >> 2);
        rc.avgSourceSad = (3 * rc.avgSourceSad + avgSad) >> 2;
        if (numZero < (3 * numSamples >> 2)) {
            rc.highNumBlocksWithMotion = true;
        }
        if (rc.highSourceSad && rc.lastQInter == rc.bestQuality && rc.avgFrameQindexInter < (rc.bestQuality << 1)
                && rc.rateCorrectionFactors[Vp9RtRateControl.INTER_NORMAL] == Vp9RtRateControl.MIN_BPB_FACTOR) {
            rc.rateCorrectionFactors[Vp9RtRateControl.INTER_NORMAL] = 0.5;
            rc.avgFrameQindexInter = rc.worstQuality;
            rc.bufferLevel = rc.optimalBufferLevel;
            rc.bitsOffTarget = rc.optimalBufferLevel;
            rc.resetHighSourceSad = true;
        }
        if (!keyFrame && rc.resetHighSourceSad) {
            rc.thisFrameTarget = rc.avgFrameBandwidth;
        }
    }

    /**
     * Runs {@link #sceneRow} over the superblock rows of one band of {@link #sceneDetection()}.
     *
     * @param band the band, from zero to {@link #sceneBands} minus one
     */
    private void sceneBandRows(int band) {
        var bands = sceneBands;
        for (var r = ParallelBands.start(band, bands, sbRows); r < ParallelBands.start(band + 1, bands, sbRows); r++) {
            sceneRow(r);
        }
    }

    /**
     * Copies the reconstruction into {@link #copyTarget}, in one band fewer than there are coders.
     */
    private void copyReference() {
        copyTarget.copyFrom(recon, coders.length - 1);
    }

    /**
     * Samples the sums of absolute differences between the source and the last source of the
     * interior superblocks of one superblock row in a checkerboard pattern, for
     * {@link #sceneDetection()}, and stores their sum, count and number of zero sums in
     * {@link #sceneRows}.
     *
     * @param r the superblock row
     */
    private void sceneRow(int r) {
        var edsp = Vp9EncDsp.INSTANCE;
        long sum = 0;
        var samples = 0;
        var zeros = 0;
        for (var c = 0; c < sbCols; c++) {
            if (r > 0 && c > 0 && r < sbRows - 1 && c < sbCols - 1 && ((r % 2 == 0 && c % 2 == 0)
                    || (r % 2 != 0 && c % 2 != 0))) {
                var off = source.offset(0, c << 6, r << 6);
                var sad = edsp.sad(source.planes[0], off, source.strides[0], lastSource.planes[0], off,
                        lastSource.strides[0], 64, 64);
                sum += sad;
                samples++;
                if (sad == 0) {
                    zeros++;
                }
            }
        }
        sceneRows[3 * r] = sum;
        sceneRows[3 * r + 1] = samples;
        sceneRows[3 * r + 2] = zeros;
    }

    /**
     * Returns the quantizer index of a frame to code again after a large overshoot, resetting the
     * rate control for it ({@code vp9_encodedframe_overshoot}).
     *
     * @param frameSize the frame size in bits, or -1 to decide before coding
     * @param q the current quantizer index
     * @return the new quantizer index, or -1 to keep the frame
     */
    private int encodedFrameOvershoot(int frameSize, int q) {
        var threshQp = 3 * (rc.worstQuality >> 2);
        var threshRate = (long) rc.avgFrameBandwidth << 3;
        if ((sf.overshootDetectionCbrRt == Vp9RtSpeed.FAST_DETECTION_MAXQ || frameSize > threshRate)
                && baseQindex < threshQp) {
            var rcf = rc.rateCorrectionFactors[Vp9RtRateControl.INTER_NORMAL];
            var targetSize = rc.avgFrameBandwidth;
            var sadThr = 64L * 64 * 32;
            if (rc.bufferLevel > (3 * rc.optimalBufferLevel) >> 2 && rc.avgSourceSad < sadThr) {
                q = (q + rc.worstQuality) >> 1;
            } else {
                q = rc.worstQuality;
            }
            cr.counterEncodeMaxqSceneChange = 0;
            rc.reEncodeMaxqSceneChange = true;
            rc.avgFrameQindexInter = q;
            rc.bufferLevel = rc.optimalBufferLevel;
            rc.bitsOffTarget = rc.optimalBufferLevel;
            rc.rc1Frame = 0;
            rc.rc2Frame = 0;
            var targetBitsPerMb = (int) (((long) targetSize << Vp9RtRateControl.BPER_MB_NORMBITS) / mbs);
            var q2 = Vp9EncTables.qindexToQ(q);
            var enumerator = 1800000;
            enumerator += (int) (enumerator * q2) >> 12;
            var newFactor = (double) targetBitsPerMb * q2 / enumerator;
            if (newFactor > rcf) {
                rcf = Math.min(2.0 * rcf, newFactor);
                if (rcf > Vp9RtRateControl.MAX_BPB_FACTOR) {
                    rcf = Vp9RtRateControl.MAX_BPB_FACTOR;
                }
                rc.rateCorrectionFactors[Vp9RtRateControl.INTER_NORMAL] = rcf;
            }
            return q;
        }
        return -1;
    }
}
