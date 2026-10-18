/* Ports libvpx 1.17.0 (vp8/encoder/onyx_if.c, encodeframe.c, rdopt.c, picklpf.c, encodemv.c, modecosts.c,
   segmentation.c, vp8/common/vp8_skin_detection.c). Copyright (c) 2010 The WebM project authors; BSD 3-Clause License.
   Modified for Smalt. */
package com.github.auties00.smalt.video.vp8.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaRuntime;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.video.vp8.Vp8EncoderOptions;

import java.util.Arrays;

import static com.github.auties00.smalt.video.vp8.internal.Vp8FrameDecoder.ALTREF_FRAME;
import static com.github.auties00.smalt.video.vp8.internal.Vp8FrameDecoder.B_PRED;
import static com.github.auties00.smalt.video.vp8.internal.Vp8FrameDecoder.GOLDEN_FRAME;
import static com.github.auties00.smalt.video.vp8.internal.Vp8FrameDecoder.INTRA_FRAME;
import static com.github.auties00.smalt.video.vp8.internal.Vp8FrameDecoder.LAST_FRAME;
import static com.github.auties00.smalt.video.vp8.internal.Vp8FrameDecoder.ZEROMV;

/**
 * Encodes pictures into a real-time VP8 stream (RFC 6386): key frames and inter frames that
 * predict from the last frame, the golden frame and the alternate reference frame, at a constant
 * bitrate.
 *
 * <p>Each call of {@link #encode} codes one frame or drops it: it decides the frame type, its
 * size and quantizer ({@link RtRateControl}), the cyclic refresh segment and the speed
 * features, codes the macroblocks ({@link RtMbCoder}), picks and applies the loop filter to the
 * reconstruction, updates the reference frames, and packs the bitstream ({@link RtBitstream}).
 * The reconstruction is bit-exact with what a VP8 decoder produces from the returned frame.
 *
 * <p>The macroblock rows of a frame are coded by the calling thread and, when the encoder may
 * use several threads, by helpers of the module's shared pool in a {@link RowWavefront}: a row's
 * macroblock waits until the row above has coded the macroblock above and to its right. Since
 * rows decide independently of each other (see {@link RtRow}), the stream does not depend on the
 * threads. With several threads, a helper also filters the reconstruction while the calling
 * thread packs the bitstream, two steps that share no state. Instances are used by one thread at
 * a time.
 *
 * @implNote This implementation follows libvpx's real-time path at {@code cpu-used} -4 to -16 with
 * libvpx's defaults for every setting it does not expose (no noise reduction, no screen content
 * mode, sharpness 0), and its temporal layers follow {@code vpx_temporal_svc_encoder} patterns 1
 * and 5 with WebRTC's per-layer bitrate split. Three constants deliberately differ from libvpx's,
 * chosen by measurement: the quantizer rounds at {@link #LUMA_ROUNDING} and
 * {@link #CHROMA_ROUNDING} over 128 instead of 48, the rate-distortion constant is
 * {@link #RD_CONSTANT} instead of 2.80, and an inter frame's loop filter level may be lowered by
 * up to {@link #FILTER_RELAX_STEPS} steps.
 */
public final class RtEncoder {
    /**
     * The full-pixel search method by hexagons, libvpx's {@code HEX}.
     */
    static final int SEARCH_HEX = 2;

    /**
     * The full-pixel search method by eight-point diamonds, libvpx's {@code NSTEP}.
     */
    static final int SEARCH_DIAMOND = 1;

    /**
     * The fractional search of libvpx's {@code vp8_find_best_sub_pixel_step_iteratively}.
     */
    static final int SUBPEL_ITERATIVE = 0;

    /**
     * The fractional search of libvpx's {@code vp8_find_best_sub_pixel_step}.
     */
    static final int SUBPEL_QUARTER = 1;

    /**
     * The fractional search of libvpx's {@code vp8_find_best_half_pixel_step}.
     */
    static final int SUBPEL_HALF = 2;

    /**
     * No fractional search, libvpx's {@code vp8_skip_fractional_mv_step}.
     */
    static final int SUBPEL_NONE = 3;

    /**
     * The rounding offset of the luma and Y2 quantizers, in 1/128 of a step.
     */
    private static final int LUMA_ROUNDING = 44;

    /**
     * The rounding offset of the chroma quantizer, in 1/128 of a step.
     */
    private static final int CHROMA_ROUNDING = 46;

    /**
     * The multiplier of the squared quantizer step in the rate-distortion constant (libvpx uses
     * 2.80), which weighs the rate of a mode against its distortion.
     */
    private static final double RD_CONSTANT = 1.5;

    /**
     * The error the loop filter search may give up for a weaker filter, in 1/10000 of the best
     * level's error.
     */
    private static final int FILTER_RELAX_TOLERANCE = 15;

    /**
     * The most steps the loop filter level is lowered by the relaxation.
     */
    private static final int FILTER_RELAX_STEPS = 2;

    /**
     * The largest full-pixel motion vector component of the SAD cost table, libvpx's
     * {@code mvfp_max}.
     */
    static final int MVFP_MAX = 255;

    /**
     * The most threads an automatic thread count ({@link Vp8EncoderOptions#threads()} 0) takes.
     */
    private static final int MAX_AUTOMATIC_THREADS = 64;

    /**
     * The number of 10 MHz timestamp ticks per second, libvpx's {@code TICKS_PER_SEC}.
     */
    public static final long TICKS_PER_SECOND = 10_000_000L;

    /**
     * The fraction of macroblock rows the fast loop filter search filters, libvpx's
     * {@code PARTIAL_FRAME_FRACTION}.
     */
    private static final int PARTIAL_FRAME_FRACTION = 8;

    /**
     * The flag of the last frame in reference flag sets.
     */
    static final int FLAG_LAST = 1;

    /**
     * The flag of the golden frame in reference flag sets.
     */
    static final int FLAG_GOLDEN = 2;

    /**
     * The flag of the alternate reference frame in reference flag sets.
     */
    static final int FLAG_ALTREF = 4;

    /**
     * The loop filter delta of each reference, libvpx's {@code set_default_lf_deltas}.
     */
    static final int[] REF_LF_DELTAS = {2, 0, -2, -2};

    /**
     * The loop filter delta of each mode class ({@code B_PRED}, zero motion, new motion, split)
     * in real-time mode, libvpx's {@code set_default_lf_deltas}.
     */
    static final int[] MODE_LF_DELTAS = {4, -12, 2, 4};

    /**
     * The loop filter mode class of each macroblock mode.
     */
    private static final int[] MODE_LF_CLASS = {1, 1, 1, 1, 0, 2, 2, 1, 2, 3};

    /**
     * The skin model's mean (Cb, Cr) of each of its five clusters, in Q6.
     */
    private static final int[] SKIN_MEAN = {7463, 9614, 6400, 10240, 7040, 10240, 8320, 9280, 6800, 9614};

    /**
     * The skin model's inverse covariance, in Q16.
     */
    private static final int[] SKIN_INV_COV = {4107, 1663, 1663, 2157};

    /**
     * The skin model's thresholds, in Q18.
     */
    private static final int[] SKIN_THRESHOLD = {1570636, 1400000, 800000, 800000, 800000, 800000};

    /**
     * The picture width in pixels.
     */
    final int width;

    /**
     * The picture height in pixels.
     */
    final int height;

    /**
     * The width in macroblocks.
     */
    final int mbCols;

    /**
     * The height in macroblocks.
     */
    final int mbRows;

    /**
     * The number of macroblocks.
     */
    final int mbs;

    /**
     * The stride of the mode info arrays, which have a border column and row.
     */
    final int miStride;

    /**
     * The number of temporal layers.
     */
    final int layers;

    /**
     * The number of token partitions: 1, 2, 4 or 8.
     */
    final int partitions;

    /**
     * The most threads that code macroblock rows.
     */
    final int threads;

    /**
     * The frame buffers: the frame being coded and the references.
     */
    final FrameBuffer[] buffers = new FrameBuffer[4];

    /**
     * The reference flags of each frame buffer.
     */
    private final int[] bufferFlags = new int[4];

    /**
     * The buffer holding the last frame.
     */
    int lastIdx;

    /**
     * The buffer holding the golden frame.
     */
    int goldenIdx;

    /**
     * The buffer holding the alternate reference frame.
     */
    int altIdx;

    /**
     * The buffer of the frame being coded.
     */
    private int newIdx;

    /**
     * The frame being coded.
     */
    FrameBuffer cur;

    /**
     * The source picture: the picture's own planes when its size is a whole number of
     * macroblocks, otherwise {@link #paddedSource}.
     */
    FrameBuffer source;

    /**
     * The source picture padded to whole macroblocks by repeating its last column and row, for
     * pictures whose size is not a whole number of macroblocks.
     */
    private final FrameBuffer paddedSource;

    /**
     * The scratch frame of the loop filter level search.
     */
    private final FrameBuffer filterScratch;

    /**
     * The reference frame each reference index searches, or {@code null} when the reference is
     * not used by the current frame.
     */
    final FrameBuffer[] refs = new FrameBuffer[4];

    /**
     * The references the current frame may predict from.
     */
    int refFrameFlags;

    /**
     * The frame number each reference was coded at.
     */
    private final long[] currentRefFrames = new long[4];

    /**
     * Whether the current frame is a key frame.
     */
    boolean keyFrame;

    /**
     * Whether the last coded frame was a key frame.
     */
    boolean lastFrameKey;

    /**
     * The number of frames coded or dropped.
     */
    private long frameCount;

    /**
     * The temporal layer of the current frame.
     */
    int currentLayer;

    /**
     * The position of the current frame in the temporal layer pattern.
     */
    private int patternCounter;

    /**
     * The macroblock mode of each macroblock, with a border.
     */
    final byte[] mbMode;

    /**
     * The chroma mode of each macroblock, with a border.
     */
    final byte[] mbUvMode;

    /**
     * The reference of each macroblock, with a border.
     */
    final byte[] mbRef;

    /**
     * Whether each macroblock has no coefficients, with a border.
     */
    final byte[] mbSkip;

    /**
     * The segment of each macroblock, with a border.
     */
    final byte[] mbSegment;

    /**
     * The packed motion vector of each macroblock, with a border.
     */
    final int[] mbMv;

    /**
     * The 16 subblock modes of each macroblock, with a border.
     */
    final byte[] bModes;

    /**
     * The motion vectors of the last inter frame, laid out with a border of one macroblock on
     * every side.
     */
    final int[] lfMv;

    /**
     * The references of the last inter frame, laid out like {@link #lfMv}.
     */
    final byte[] lfRef;

    /**
     * The segment the cyclic refresh assigns to each macroblock.
     */
    final byte[] segmentationMap;

    /**
     * The cyclic refresh state of each macroblock: {@code 0} a candidate for refresh,
     * {@code 1} not, negative refreshed recently.
     */
    final byte[] cyclicRefreshMap;

    /**
     * The number of consecutive frames each macroblock was coded with last-frame zero motion.
     */
    final byte[] consecZeroLast;

    /**
     * The same count as {@link #consecZeroLast}, restarted by the dot artifact checks.
     */
    final byte[] consecZeroLastMvBias;

    /**
     * Whether each macroblock looks like skin.
     */
    final byte[] skinMap;

    /**
     * The nonzero flags of the blocks along the bottom edge of each macroblock column.
     */
    final byte[] aboveContext;

    /**
     * Whether segmentation is enabled for the current frame.
     */
    boolean segmentationEnabled;

    /**
     * The quantizer delta of each segment.
     */
    final int[] segmentQuant = new int[4];

    /**
     * The segment tree probabilities of the current frame.
     */
    final int[] segmentTreeProbs = {255, 255, 255};

    /**
     * Whether the cyclic background refresh is enabled.
     */
    boolean cyclicRefreshEnabled;

    /**
     * The most macroblocks the cyclic refresh refreshes per frame.
     */
    private int cyclicRefreshMaxMbs;

    /**
     * The macroblock where the next cyclic refresh scan starts.
     */
    private int cyclicRefreshIndex;

    /**
     * The luma quantization matrix of each segment.
     */
    final QuantMatrix[] y1Matrices = new QuantMatrix[4];

    /**
     * The Y2 quantization matrix of each segment.
     */
    final QuantMatrix[] y2Matrices = new QuantMatrix[4];

    /**
     * The chroma quantization matrix of each segment.
     */
    final QuantMatrix[] uvMatrices = new QuantMatrix[4];

    /**
     * The base quantizer index of the current frame.
     */
    int baseQ;

    /**
     * The Y2 DC quantizer delta of the current frame.
     */
    int y2DcDelta;

    /**
     * The cost of each reference, in 1/256 bit.
     */
    final int[] refFrameCost = new int[4];

    /**
     * The cost of each luma intra mode of the current frame type, in 1/256 bit.
     */
    final int[] mbModeCost = new int[5];

    /**
     * The cost of each key-frame subblock mode given the modes above and to the left,
     * {@code [above 10][left 10][mode 10]}.
     */
    final int[] kfBmodeCosts = new int[1000];

    /**
     * The cost of each inter-frame subblock mode.
     */
    final int[] interBmodeCosts = new int[10];

    /**
     * The cost of each motion vector component value, rows then columns, each offset by
     * {@link RtStats#MV_MAX}.
     */
    final int[] mvCost = new int[2 * RtStats.MV_VALS];

    /**
     * The cost of each full-pixel motion vector component value, offset by {@link #MVFP_MAX},
     * libvpx's {@code cal_mvsadcosts}.
     */
    final int[] mvSadCost = new int[2 * MVFP_MAX + 1];

    /**
     * The rate multiplier of rate-distortion scores.
     */
    int rdMult;

    /**
     * The distortion multiplier of rate-distortion scores.
     */
    int rdDiv;

    /**
     * The motion vector cost of one bit in distortion units.
     */
    int errorPerBit;

    /**
     * The motion vector cost of one bit in SAD units.
     */
    int sadPerBit16;

    /**
     * The baseline threshold of each mode of the current frame.
     */
    final int[] rdBaselineThresh = new int[RtTables.MAX_MODES];

    /**
     * The threshold multipliers carried from frame to frame.
     */
    private final int[] rdThreshMult = new int[RtTables.MAX_MODES];

    /**
     * The test frequency of each mode.
     */
    final int[] modeCheckFreq = new int[RtTables.MAX_MODES];

    /**
     * The threshold multiplier of each mode before scaling.
     */
    private final int[] threshMult = new int[RtTables.MAX_MODES];

    /**
     * The real-time speed, 4 to 16.
     */
    int speed = 6;

    /**
     * The full-pixel search method.
     */
    int searchMethod;

    /**
     * The fractional search method.
     */
    int subpelMethod;

    /**
     * Whether new motion vectors are searched from neighbors' vectors sorted by SAD.
     */
    boolean improvedMvPred;

    /**
     * Whether the loop filter level is searched over the whole frame rather than a band of it.
     */
    private boolean fullFilterSearch;

    /**
     * Whether the frame uses the simple loop filter.
     */
    private boolean simpleFilter;

    /**
     * The reference closest in time to the current frame.
     */
    int closestReferenceFrame = LAST_FRAME;

    /**
     * The percentage of macroblocks the last frame coded with last-frame zero motion.
     */
    int lfZeromvPct;

    /**
     * The number of macroblocks the last frame coded with last-frame zero motion.
     */
    private int zeromvCount;

    /**
     * Whether golden frames are refreshed only by key frames, entropy updates last one frame, and
     * loop filter deltas are repeated in every frame.
     */
    boolean errorResilient;

    /**
     * The maximum distance between key frames, or {@code 0} for none.
     */
    private int keyframeInterval;

    /**
     * The probability of an intra macroblock assumed by the mode search.
     */
    int probIntraCoded = 63;

    /**
     * The probability of a last-frame macroblock among inter macroblocks.
     */
    int probLastCoded = 128;

    /**
     * The probability of a golden macroblock among golden and alternate macroblocks.
     */
    int probGfCoded = 128;

    /**
     * The number of frames since the golden frame was refreshed.
     */
    private int framesSinceGolden;

    /**
     * Whether the current frame refreshes the last frame.
     */
    boolean refreshLast = true;

    /**
     * Whether the current frame refreshes the golden frame.
     */
    boolean refreshGolden;

    /**
     * Whether the current frame refreshes the alternate reference frame.
     */
    boolean refreshAlt;

    /**
     * Which buffer, if any, the alternate reference frame is copied from: {@code 0} none,
     * {@code 1} the last frame, {@code 2} the golden frame.
     */
    int copyToAlt;

    /**
     * Whether the current frame's entropy updates persist to the next frame.
     */
    boolean refreshEntropy = true;

    /**
     * Whether the loop filter deltas are sent with the current frame.
     */
    boolean lfDeltaUpdate;

    /**
     * The loop filter deltas the decoder holds, per reference.
     */
    final int[] lastRefLfDeltas = new int[4];

    /**
     * The loop filter deltas the decoder holds, per mode class.
     */
    final int[] lastModeLfDeltas = new int[4];

    /**
     * The loop filter level of the current frame.
     */
    int filterLevel;

    /**
     * The token probabilities.
     */
    final int[] coefProbs = new int[1056];

    /**
     * The inter-frame luma mode probabilities.
     */
    final int[] ymodeProbs = new int[4];

    /**
     * The inter-frame chroma mode probabilities.
     */
    final int[] uvModeProbs = new int[3];

    /**
     * The motion vector probabilities, rows then columns.
     */
    final int[] mvProbs = new int[38];

    /**
     * The probabilities saved while the current frame's updates last one frame.
     */
    private final int[] savedCoefProbs = new int[1056];

    /**
     * The saved luma mode probabilities.
     */
    private final int[] savedYmodeProbs = new int[4];

    /**
     * The saved chroma mode probabilities.
     */
    private final int[] savedUvModeProbs = new int[3];

    /**
     * The saved motion vector probabilities.
     */
    private final int[] savedMvProbs = new int[38];

    /**
     * Whether {@link #mvCost} must be rebuilt from {@link #mvProbs}.
     */
    private boolean mvCostsDirty = true;

    /**
     * The motion vector probabilities the motion vector costs were last built from: error
     * resilient frames restore the probabilities every frame, usually to the ones the costs
     * already reflect, which needs no rebuild. Probabilities are never zero, so the initial zeros
     * force the first build.
     */
    private final int[] builtMvProbs = new int[38];

    /**
     * The rate control.
     */
    final RtRateControl rc;

    /**
     * The state of each macroblock row.
     */
    final RtRow[] rows;

    /**
     * The macroblock coder of each thread.
     */
    private final RtMbCoder[] coders;

    /**
     * The statistics of each thread.
     */
    private final RtStats[] threadStats;

    /**
     * The statistics of the frame, summed over the threads.
     */
    final RtStats stats = new RtStats();

    /**
     * The row wavefront of the parallel macroblock and loop filter loops.
     */
    private final RowWavefront wavefront;

    /**
     * Codes a row on a participant of {@link #wavefront}.
     */
    private final RowWavefront.RowBody codeBody;

    /**
     * Filters a row on a participant of {@link #wavefront}.
     */
    private final RowWavefront.RowBody filterBody;

    /**
     * The bitstream writer.
     */
    private final RtBitstream bitstream;

    /**
     * The parts of packing that may run concurrently with each other and with the loop filter:
     * the macroblock modes of the first partition, then each token partition.
     */
    private final Runnable[] packTasks;

    /**
     * The frame {@link #finishFrame} packed, until {@link #encode} takes it.
     */
    private byte[] packedFrame;

    /**
     * The loop filter kernels.
     */
    private final Vp8Dsp dsp = Vp8Dsp.INSTANCE;

    /**
     * The block metric kernels.
     */
    private final RtDsp rtDsp = RtDsp.INSTANCE;

    /**
     * The scratch of the loop filter kernels.
     */
    private final byte[] filterScratchBytes = new byte[Vp8Dsp.SCRATCH_SIZE];

    /**
     * The loop filter level of each segment, reference and mode class of the frame being
     * filtered, {@code [segment 4][reference 4][mode class 4]}.
     */
    private final int[] filterLevels = new int[64];

    /**
     * The first timestamp seen, in 10 MHz ticks.
     */
    private long firstTimeStampEver = Long.MAX_VALUE;

    /**
     * The start of the last frame, in 10 MHz ticks.
     */
    private long lastTimeStampSeen;

    /**
     * The end of the last frame, in 10 MHz ticks.
     */
    private long lastEndTimeStampSeen;

    /**
     * The frame rate measured from the timestamps.
     */
    private double refFramerate;

    /**
     * The cumulative bitrate of each temporal layer.
     */
    private final int[] layerBitrates;

    /**
     * The frame rate divisor of each temporal layer.
     */
    private final int[] layerDecimators;

    /**
     * Whether the encoder keeps the reconstruction of every frame, including frames no
     * reference keeps.
     */
    private boolean keepReconstruction;

    /**
     * The static threshold of the encode breakout, libvpx's {@code encode_breakout}; {@code 0}
     * disables it.
     */
    int encodeBreakout;

    /**
     * The quantizer index of the last coded frame.
     */
    private int lastQuantizer;

    /**
     * Creates an encoder for pictures of one size, configured with {@link #configure}.
     *
     * @param width the picture width, from 1 to 16383
     * @param height the picture height, from 1 to 16383
     * @param options the settings; the temporal layers, token partitions, thread count and limits
     * are fixed for the encoder's lifetime
     * @throws MediaException.LimitExceeded if a frame buffer exceeds the options' limits
     */
    public RtEncoder(int width, int height, Vp8EncoderOptions options) {
        this.width = width;
        this.height = height;
        this.mbCols = (width + 15) >> 4;
        this.mbRows = (height + 15) >> 4;
        this.mbs = mbCols * mbRows;
        this.miStride = mbCols + 1;
        this.layers = options.temporalLayers();
        this.partitions = options.tokenPartitions();
        this.threads = options.threads() == 0
                ? Math.min(MAX_AUTOMATIC_THREADS, MediaRuntime.parallelism())
                : options.threads();
        var limits = options.limits();
        for (var i = 0; i < 4; i++) {
            buffers[i] = new FrameBuffer(mbCols, mbRows, limits);
        }
        paddedSource = new FrameBuffer(mbCols, mbRows, limits);
        source = paddedSource;
        filterScratch = new FrameBuffer(mbCols, mbRows, limits);
        var miSize = miStride * (mbRows + 1);
        mbMode = new byte[miSize];
        mbUvMode = new byte[miSize];
        mbRef = new byte[miSize];
        mbSkip = new byte[miSize];
        mbSegment = new byte[miSize];
        mbMv = new int[miSize];
        bModes = new byte[miSize * 16];
        lfMv = new int[(mbRows + 2) * (mbCols + 2)];
        lfRef = new byte[(mbRows + 2) * (mbCols + 2)];
        segmentationMap = new byte[mbs];
        cyclicRefreshMap = new byte[mbs];
        consecZeroLast = new byte[mbs];
        consecZeroLastMvBias = new byte[mbs];
        skinMap = new byte[mbs];
        aboveContext = new byte[mbCols * 9];
        for (var i = 0; i < 4; i++) {
            y1Matrices[i] = new QuantMatrix();
            y2Matrices[i] = new QuantMatrix();
            uvMatrices[i] = new QuantMatrix();
        }
        rc = new RtRateControl(mbs, layers);
        rows = new RtRow[mbRows];
        for (var r = 0; r < mbRows; r++) {
            rows[r] = new RtRow(mbCols);
        }
        var participants = Math.max(1, Math.min(threads, mbRows));
        coders = new RtMbCoder[participants];
        threadStats = new RtStats[participants];
        for (var i = 0; i < participants; i++) {
            coders[i] = new RtMbCoder(this);
            threadStats[i] = new RtStats();
        }
        wavefront = new RowWavefront(mbRows);
        codeBody = (participant, row) -> codeRow(coders[participant], threadStats[participant], row, wavefront);
        filterBody = (participant, row) -> filterRow(coders[participant].filterScratch, row, wavefront);
        bitstream = new RtBitstream(this);
        packTasks = new Runnable[1 + partitions];
        packTasks[0] = bitstream::packModes;
        for (var p = 0; p < partitions; p++) {
            var partition = p;
            packTasks[1 + p] = () -> bitstream.packPartition(partition);
        }
        Arrays.fill(rdThreshMult, 128);
        mvSadCost[MVFP_MAX] = 300;
        for (var i = 1; i <= MVFP_MAX; i++) {
            var z = (int) (256 * (2 * (Math.log(8.0 * i) / Math.log(2) + 0.6)));
            mvSadCost[MVFP_MAX + i] = z;
            mvSadCost[MVFP_MAX - i] = z;
        }
        cyclicRefreshMaxMbs = layers == 1 ? mbs / 20 : layers == 2 ? mbs / 10 : mbs / 7;
        layerBitrates = new int[layers];
        layerDecimators = new int[layers];
        for (var i = 0; i < layers; i++) {
            layerDecimators[i] = 1 << (layers - 1 - i);
        }
        lastIdx = 0;
        goldenIdx = 0;
        altIdx = 0;
    }

    /**
     * Applies the settings that may change while the stream runs; the first call starts the
     * stream, later calls change the rate, quantizer range, key frame interval, speed, frame
     * dropping, buffer model, encode breakout and rate tolerances of the running stream.
     *
     * @param options the settings; its temporal layers, token partitions, threads and limits are
     * those the encoder was created with
     */
    public void configure(Vp8EncoderOptions options) {
        var bitrate = (int) options.bitrate();
        var frameRate = options.frameRate().toDouble();
        var running = frameCount > 0;
        this.speed = options.speed().level();
        this.errorResilient = options.errorResilient() || layers > 1;
        this.keyframeInterval = options.keyframeInterval().orElse(0);
        if (!running) {
            refFramerate = frameRate;
        }
        var rate = running ? rc.framerate : frameRate;
        if (layers > 1) {
            var fractions = layers == 2 ? new double[]{0.6, 1.0} : new double[]{0.4, 0.6, 1.0};
            for (var i = 0; i < layers; i++) {
                layerBitrates[i] = (int) Math.round(bitrate * fractions[i]);
            }
            if (running) {
                rc.saveLayer(currentLayer);
            }
        }
        rc.configure(bitrate, options.minQuantizer(), options.maxQuantizer(), (int) options.bufferSize().toMillis(),
                (int) options.initialBufferLevel().toMillis(), (int) options.optimalBufferLevel().toMillis(),
                options.dropFrames(), running ? refFramerate : frameRate, running);
        rc.keyFrameFrequency = keyframeInterval;
        if (!running) {
            rc.start();
            cyclicRefreshEnabled = true;
            rc.gfUpdateOnepassCbr = !this.errorResilient;
            rc.gfNoboostOnepassCbr = true;
            var interval = cyclicRefreshMaxMbs > 0 ? 2 * mbs / cyclicRefreshMaxMbs : 10;
            rc.framesTillGfUpdateInterval = Math.clamp(interval, 6, 40);
            rc.baselineGfInterval = this.errorResilient ? 7 : rc.framesTillGfUpdateInterval;
            if (layers > 1) {
                rc.initLayers(layerBitrates, layerDecimators, frameRate);
            }
        } else if (layers > 1) {
            rc.updateLayers(layerBitrates, layerDecimators, refFramerate);
            rc.restoreLayer(currentLayer);
        }
        if (!running) {
            Arrays.fill(lastRefLfDeltas, 0);
            Arrays.fill(lastModeLfDeltas, 0);
        }
        lfDeltaUpdate = true;
        if (rate <= 0) {
            rc.newFramerate(frameRate);
        }
        encodeBreakout = options.encodeBreakout();
        rc.underShootPct = options.undershootPercent();
        rc.overShootPct = options.overshootPercent();
        rc.maxIntraBitratePct = options.maxKeyframeBitratePercent().orElse(0);
    }

    /**
     * Makes {@link #encode} filter and keep the reconstruction of frames that no reference
     * keeps, so a test can compare every frame with a decoder's output.
     *
     * @param keep whether to keep every reconstruction
     */
    public void keepReconstruction(boolean keep) {
        keepReconstruction = keep;
    }

    /**
     * Copies the reconstruction of the last coded frame into a picture.
     *
     * @param out the 4:2:0 picture of the encoder's size to fill
     */
    public void copyReconstruction(YuvPicture out) {
        cur.copyTo(out, width, height);
    }

    /**
     * Returns whether the last coded frame was a key frame.
     *
     * @return {@code true} for a key frame
     */
    public boolean lastKeyFrame() {
        return lastFrameKey;
    }

    /**
     * Returns the quantizer index of the last coded frame.
     *
     * @return the index, {@code [0, 127]}
     */
    public int lastQuantizer() {
        return lastQuantizer;
    }

    /**
     * Returns the temporal layer of the last coded or dropped frame.
     *
     * @return the layer, from 0
     */
    public int lastLayer() {
        return currentLayer;
    }

    /**
     * Returns the buffer level of the rate control, in bits.
     *
     * @return the level; negative when the stream spent more than the channel delivered
     */
    public long bufferLevel() {
        return rc.bufferLevel;
    }

    /**
     * Encodes one picture, or drops it when the rate control's buffer is empty and dropping is
     * enabled.
     *
     * @param picture  the 4:2:0 picture of the encoder's size
     * @param start    the picture's start time in 10 MHz ticks
     * @param end      the picture's end time in 10 MHz ticks, after {@code start}
     * @param forceKey whether the frame must be a key frame
     * @return the coded frame, or {@code null} when the frame was dropped
     */
    public byte[] encode(YuvPicture picture, long start, long end, boolean forceKey) {
        loadSource(picture);
        updateFramerate(start, end);
        var forcedKey = forceKey || frameCount == 0
                || (keyframeInterval > 0 && rc.framesSinceKey % keyframeInterval == 0);
        if (layers > 1) {
            if (forcedKey) {
                patternCounter = 0;
            }
            var layer = layerOf(patternCounter);
            rc.updateLayers(layerBitrates, layerDecimators, refFramerate);
            rc.restoreLayer(layer);
            currentLayer = layer;
        }
        lfZeromvPct = mbs == 0 ? 0 : zeromvCount * 100 / mbs;
        keyFrame = forcedKey;
        try {
            return encodeFrame(forceKey && frameCount > 0);
        } finally {
            if (layers > 1) {
                rc.saveLayer(currentLayer);
            }
            patternCounter++;
        }
    }

    /**
     * Returns the temporal layer of a position of the layer pattern.
     *
     * @param counter the position
     * @return the layer
     */
    private int layerOf(int counter) {
        if (layers == 2) {
            return counter & 1;
        }
        return switch (counter & 3) {
            case 0 -> 0;
            case 2 -> 1;
            default -> 2;
        };
    }

    /**
     * Sets the reference and refresh flags of the current inter frame, from the temporal layer
     * pattern or the golden frame bookkeeping.
     */
    private void setReferenceFlags() {
        if (layers == 1) {
            refFrameFlags = FLAG_LAST | FLAG_GOLDEN | FLAG_ALTREF;
            if (goldIsLast) {
                refFrameFlags &= ~FLAG_GOLDEN;
            }
            if (altIsLast || goldIsAlt) {
                refFrameFlags &= ~FLAG_ALTREF;
            }
            refreshLast = true;
            refreshGolden = false;
            refreshAlt = false;
            return;
        }
        refreshAlt = false;
        if (currentLayer == 0) {
            refFrameFlags = FLAG_LAST;
            refreshLast = true;
            refreshGolden = false;
        } else if (layers == 3 && currentLayer == 2) {
            refFrameFlags = FLAG_LAST | FLAG_GOLDEN;
            refreshLast = false;
            refreshGolden = false;
        } else {
            refFrameFlags = FLAG_LAST | FLAG_GOLDEN;
            refreshLast = false;
            refreshGolden = true;
        }
    }

    /**
     * Whether the golden frame buffer holds the last frame.
     */
    private boolean goldIsLast;

    /**
     * Whether the alternate reference buffer holds the last frame.
     */
    private boolean altIsLast;

    /**
     * Whether the golden and alternate reference buffers hold the same frame.
     */
    private boolean goldIsAlt;

    /**
     * Updates the measured frame rate from a frame's timestamps, the frame rate logic of libvpx's
     * {@code vp8_get_compressed_data}.
     *
     * @param start the frame's start in 10 MHz ticks
     * @param end   the frame's end in 10 MHz ticks
     */
    private void updateFramerate(long start, long end) {
        if (start < firstTimeStampEver) {
            firstTimeStampEver = start;
            lastEndTimeStampSeen = start;
        }
        long thisDuration;
        var step = 0L;
        if (start == firstTimeStampEver) {
            thisDuration = end - start;
            step = 1;
        } else {
            thisDuration = end - lastEndTimeStampSeen;
            var lastDuration = lastEndTimeStampSeen - lastTimeStampSeen;
            thisDuration = Math.min(thisDuration, Long.MAX_VALUE / 10);
            if (lastDuration != 0) {
                step = (thisDuration - lastDuration) * 10 / lastDuration;
            }
        }
        if (thisDuration > 0) {
            if (step != 0) {
                refFramerate = (double) TICKS_PER_SECOND / thisDuration;
            } else {
                var interval = (double) (end - firstTimeStampEver);
                if (interval > TICKS_PER_SECOND) {
                    interval = TICKS_PER_SECOND;
                }
                var avgDuration = TICKS_PER_SECOND / refFramerate;
                avgDuration *= (interval - avgDuration + thisDuration);
                avgDuration /= interval;
                refFramerate = TICKS_PER_SECOND / avgDuration;
            }
            if (refFramerate < 0.1 || Double.isNaN(refFramerate) || Double.isInfinite(refFramerate)) {
                refFramerate = 30;
            }
            if (layers == 1) {
                rc.newFramerate(refFramerate);
            }
        }
        lastTimeStampSeen = start;
        lastEndTimeStampSeen = end;
    }

    /**
     * Makes a picture the {@link #source}: wraps its planes when its size is a whole number of
     * macroblocks, otherwise copies it into {@link #paddedSource}, repeating its last column and
     * row up to whole macroblocks.
     *
     * @param picture the 4:2:0 picture
     */
    private void loadSource(YuvPicture picture) {
        if (width == mbCols * 16 && height == mbRows * 16) {
            source = FrameBuffer.wrap(picture.y(), picture.yStride(), picture.u(), picture.v(), picture.uvStride(),
                    mbCols, mbRows);
            return;
        }
        var s = paddedSource;
        source = s;
        var w16 = mbCols * 16;
        var h16 = mbRows * 16;
        copyPadded(picture.y(), picture.yStride(), width, height, s.y, s.yOrigin, s.yStride, w16, h16);
        var cw = (width + 1) >> 1;
        var ch = (height + 1) >> 1;
        copyPadded(picture.u(), picture.uvStride(), cw, ch, s.u, s.uvOrigin, s.uvStride, w16 / 2, h16 / 2);
        copyPadded(picture.v(), picture.uvStride(), cw, ch, s.v, s.uvOrigin, s.uvStride, w16 / 2, h16 / 2);
    }

    /**
     * Copies a plane, repeating its last column and row to fill a larger plane.
     *
     * @param src       the source plane
     * @param srcStride the source row stride
     * @param w         the source width
     * @param h         the source height
     * @param dst       the destination plane
     * @param dstOff    the index of the destination's first sample
     * @param dstStride the destination row stride
     * @param dw        the destination width
     * @param dh        the destination height
     */
    private static void copyPadded(byte[] src, int srcStride, int w, int h, byte[] dst, int dstOff, int dstStride,
                                   int dw, int dh) {
        for (var r = 0; r < h; r++) {
            var d = dstOff + r * dstStride;
            System.arraycopy(src, r * srcStride, dst, d, w);
            if (dw > w) {
                Arrays.fill(dst, d + w, d + dw, dst[d + w - 1]);
            }
        }
        for (var r = h; r < dh; r++) {
            System.arraycopy(dst, dstOff + (h - 1) * dstStride, dst, dstOff + r * dstStride, dw);
        }
    }

    /**
     * Codes the current frame, libvpx's {@code encode_frame_to_data_rate} on its real-time
     * one-pass path.
     *
     * @param forced whether the frame is a key frame the caller requested
     * @return the coded frame, or {@code null} when the frame was dropped
     */
    private byte[] encodeFrame(boolean forced) {
        copyToAlt = 0;
        if (keyFrame) {
            refFrameFlags = 0;
            refreshLast = true;
            refreshGolden = true;
            refreshAlt = true;
        } else {
            setReferenceFlags();
        }
        closestReferenceFrame = LAST_FRAME;
        if (!keyFrame && layers > 1) {
            var closest = (refFrameFlags & FLAG_LAST) != 0 ? LAST_FRAME : GOLDEN_FRAME;
            for (var ref = LAST_FRAME; ref <= ALTREF_FRAME; ref++) {
                if ((refFrameFlags & (1 << (ref - 1))) != 0
                        && frameCount - currentRefFrames[ref] < frameCount - currentRefFrames[closest]) {
                    closest = ref;
                }
            }
            closestReferenceFrame = closest;
        }
        if (keyFrame) {
            lfDeltaUpdate = true;
            Arrays.fill(lastRefLfDeltas, 0);
            Arrays.fill(lastModeLfDeltas, 0);
            Arrays.fill(rdThreshMult, 128);
            Arrays.fill(consecZeroLast, (byte) 0);
            Arrays.fill(consecZeroLastMvBias, (byte) 0);
        }
        updateRdRefFrameProbs();
        if (keyFrame) {
            rc.calcIframeTargetSize(frameCount == 0, forced);
        } else {
            if (rc.calcPframeTargetSize(errorResilient, zeromvCount)) {
                refreshGolden = true;
            }
            if (rc.dropFrame) {
                rc.dropFrame = false;
                frameCount++;
                rc.framesSinceKey++;
                return null;
            }
        }
        rc.setActiveQuality(keyFrame, refreshGolden);
        var q = rc.regulateQ(keyFrame, false, rc.thisFrameTarget);
        computeSkinMap();
        if (cyclicRefreshEnabled && currentLayer == 0) {
            cyclicBackgroundRefresh(q);
        } else {
            segmentationEnabled = false;
        }
        setQuantizer(q);
        if (keyFrame) {
            setupKeyFrame();
        }
        refreshEntropy = !errorResilient;
        newIdx = freeBuffer();
        cur = buffers[newIdx];
        setupReferences();
        encodeMacroblocks();
        rc.projectedFrameSize = (int) Math.min(stats.totalRate >> 8, Integer.MAX_VALUE);
        var correctionChanged = rc.relaxWorstQuality(q, keyFrame, refreshGolden);
        afterMacroblocks();
        if (keyFrame) {
            refreshLast = true;
        }
        copyToAlt = !errorResilient && refreshGolden && !keyFrame ? 2 : 0;
        if (!filterLevelPicked) {
            pickLoopFilterLevel();
        }
        updateReferenceFrames();
        finishFrame();
        var frame = packedFrame;
        packedFrame = null;
        lastFrameKey = keyFrame;
        lastQuantizer = q;
        rc.filterLevel = filterLevel;
        rc.frameCoded(frame.length, keyFrame, refreshGolden, q, correctionChanged);
        if (refreshLast && refreshGolden) {
            goldIsLast = true;
        } else if (refreshLast ^ refreshGolden) {
            goldIsLast = false;
        }
        if (refreshLast && refreshAlt) {
            altIsLast = true;
        } else if (refreshLast ^ refreshAlt) {
            altIsLast = false;
        }
        if (refreshAlt && refreshGolden) {
            goldIsAlt = true;
        } else if (refreshAlt ^ refreshGolden) {
            goldIsAlt = false;
        }
        if (!errorResilient) {
            if (rc.goldenFrameCoded(keyFrame, refreshGolden)) {
                framesSinceGolden = 0;
            } else {
                framesSinceGolden++;
            }
        }
        var refUsage = stats.refUsage;
        var total = refUsage[0] + refUsage[1] + refUsage[2] + refUsage[3];
        rc.lastFramePercentIntra = keyFrame ? 100 : total == 0 ? 0 : refUsage[INTRA_FRAME] * 100 / total;
        System.arraycopy(refUsage, 0, rc.refFrameUsage, 0, 4);
        lfDeltaUpdate = false;
        frameCount++;
        rc.framesSinceKey++;
        if (!refreshEntropy) {
            restoreEntropy();
        }
        refreshEntropy = true;
        return frame;
    }

    /**
     * Updates the reference probabilities the mode search assumes, libvpx's
     * {@code update_rd_ref_frame_probs}.
     */
    private void updateRdRefFrameProbs() {
        var rfct = rc.refFrameUsage;
        var rfIntra = rfct[INTRA_FRAME];
        var rfInter = rfct[LAST_FRAME] + rfct[GOLDEN_FRAME] + rfct[ALTREF_FRAME];
        if (keyFrame) {
            probIntraCoded = 255;
            probLastCoded = 128;
            probGfCoded = 128;
        } else if (rfIntra + rfInter == 0) {
            probIntraCoded = 63;
            probLastCoded = 128;
            probGfCoded = 128;
        }
        if (layers == 1) {
            if (framesSinceGolden == 0) {
                probLastCoded = 214;
            } else if (framesSinceGolden == 1) {
                probLastCoded = 192;
                probGfCoded = 220;
            }
            probGfCoded = 255;
        }
    }

    /**
     * Returns a frame buffer no reference holds.
     *
     * @return the buffer index
     */
    private int freeBuffer() {
        for (var i = 0; i < 4; i++) {
            if (bufferFlags[i] == 0) {
                return i;
            }
        }
        throw new IllegalStateException("no free vp8 frame buffer");
    }

    /**
     * Selects the reference frames the current frame searches.
     */
    private void setupReferences() {
        Arrays.fill(refs, null);
        if (keyFrame) {
            return;
        }
        if ((refFrameFlags & FLAG_LAST) != 0) {
            refs[LAST_FRAME] = buffers[lastIdx];
        }
        if ((refFrameFlags & FLAG_GOLDEN) != 0) {
            refs[GOLDEN_FRAME] = buffers[goldenIdx];
        }
        if ((refFrameFlags & FLAG_ALTREF) != 0) {
            refs[ALTREF_FRAME] = buffers[altIdx];
        }
    }

    /**
     * Prepares the state of a key frame, libvpx's {@code vp8_setup_key_frame} and the key frame
     * resets of the decoder: default probabilities, motion vector costs and the initial loop
     * filter level.
     */
    private void setupKeyFrame() {
        System.arraycopy(Vp8Tables.COEF_PROBS_DEFAULT, 0, coefProbs, 0, 1056);
        System.arraycopy(Vp8Tables.YMODE_PROBS_DEFAULT, 0, ymodeProbs, 0, 4);
        System.arraycopy(Vp8Tables.UV_MODE_PROBS_DEFAULT, 0, uvModeProbs, 0, 3);
        System.arraycopy(Vp8Tables.MV_PROBS_DEFAULT, 0, mvProbs, 0, 38);
        mvCostsDirty = true;
        filterLevel = baseQ * 3 / 8;
        rc.framesTillGfUpdateDue = rc.baselineGfInterval;
        refreshGolden = true;
        refreshAlt = true;
    }

    /**
     * Saves the probabilities the next frame starts from when the current frame's updates last
     * one frame.
     */
    void saveEntropy() {
        System.arraycopy(coefProbs, 0, savedCoefProbs, 0, 1056);
        System.arraycopy(ymodeProbs, 0, savedYmodeProbs, 0, 4);
        System.arraycopy(uvModeProbs, 0, savedUvModeProbs, 0, 3);
        System.arraycopy(mvProbs, 0, savedMvProbs, 0, 38);
    }

    /**
     * Restores the probabilities saved by {@link #saveEntropy()}.
     */
    private void restoreEntropy() {
        System.arraycopy(savedCoefProbs, 0, coefProbs, 0, 1056);
        System.arraycopy(savedYmodeProbs, 0, ymodeProbs, 0, 4);
        System.arraycopy(savedUvModeProbs, 0, uvModeProbs, 0, 3);
        System.arraycopy(savedMvProbs, 0, mvProbs, 0, 38);
        mvCostsDirty = true;
    }

    /**
     * Marks the motion vector costs for rebuilding after the probabilities changed.
     */
    void invalidateMvCosts() {
        mvCostsDirty = true;
    }

    /**
     * Sets the frame quantizer and the quantization matrices of every segment, libvpx's
     * {@code vp8_set_quantizer} and the dequantization factors of the decoder.
     *
     * @param q the base quantizer index
     */
    private void setQuantizer(int q) {
        baseQ = q;
        y2DcDelta = q < 4 ? 4 - q : 0;
        for (var s = 0; s < 4; s++) {
            var segQ = segmentationEnabled ? Math.clamp(q + segmentQuant[s], 0, 127) : q;
            setMatrix(y1Matrices[s], Vp8Tables.DC_QLOOKUP[segQ], Vp8Tables.AC_QLOOKUP[segQ], LUMA_ROUNDING);
            var y2ac = (Vp8Tables.AC_QLOOKUP[segQ] * 101581) >> 16;
            setMatrix(y2Matrices[s], Vp8Tables.DC_QLOOKUP[Math.clamp(segQ + y2DcDelta, 0, 127)] * 2, Math.max(y2ac, 8),
                    LUMA_ROUNDING);
            setMatrix(uvMatrices[s], Math.min(Vp8Tables.DC_QLOOKUP[segQ], 132), Vp8Tables.AC_QLOOKUP[segQ],
                    CHROMA_ROUNDING);
        }
    }

    /**
     * Fills a quantization matrix with the fast quantizer of libvpx, whose levels round a
     * magnitude to the nearest step except for an offset below one half, and no sharpening.
     *
     * @implNote This implementation rounds at {@link #LUMA_ROUNDING} or {@link #CHROMA_ROUNDING}
     * over 128 of a step where libvpx rounds at 48/128, a wider dead zone.
     *
     * @param m        the matrix
     * @param dc       the DC step
     * @param ac       the AC step
     * @param rounding the rounding offset in 1/128 of a step
     */
    private static void setMatrix(QuantMatrix m, int dc, int ac, int rounding) {
        for (var i = 0; i < 16; i++) {
            var q = i == 0 ? dc : ac;
            m.q[i] = q;
            m.iq[i] = (1 << QuantMatrix.QFIX) / q;
            m.bias[i] = rounding << (QuantMatrix.QFIX - 7);
            m.zthresh[i] = ((1 << QuantMatrix.QFIX) - 1 - m.bias[i]) / m.iq[i];
            m.sharpen[i] = 0;
        }
    }

    /**
     * Assigns the cyclic background refresh segment to the next background macroblocks, libvpx's
     * {@code cyclic_background_refresh}.
     *
     * @param q the frame quantizer index
     */
    private void cyclicBackgroundRefresh(int q) {
        var map = segmentationMap;
        Arrays.fill(map, (byte) 0);
        var blockCount = cyclicRefreshMaxMbs;
        if (!keyFrame && blockCount > 0) {
            var i = cyclicRefreshIndex;
            do {
                if (cyclicRefreshMap[i] == 0) {
                    map[i] = 1;
                    blockCount--;
                } else if (cyclicRefreshMap[i] < 0) {
                    cyclicRefreshMap[i]++;
                }
                i++;
                if (i == mbs) {
                    i = 0;
                }
            } while (blockCount != 0 && i != cyclicRefreshIndex);
            cyclicRefreshIndex = i;
        }
        segmentationEnabled = true;
        segmentQuant[0] = 0;
        segmentQuant[1] = q / 2 - q;
        segmentQuant[2] = 0;
        segmentQuant[3] = 0;
    }

    /**
     * Marks the macroblocks that look like skin, libvpx's {@code compute_skin_map}.
     */
    private void computeSkinMap() {
        var s = source;
        var small = width * height <= 352 * 288;
        for (var r = 0; r < mbRows; r++) {
            for (var c = 0; c < mbCols; c++) {
                var i = r * mbCols + c;
                var y = s.yOrigin + r * 16 * s.yStride + c * 16;
                var uv = s.uvOrigin + r * 8 * s.uvStride + c * 8;
                skinMap[i] = (byte) (skinBlock(s, y, uv, small, consecZeroLast[i] & 0xFF) ? 1 : 0);
            }
        }
        for (var r = 1; r < mbRows - 1; r++) {
            for (var c = 1; c < mbCols - 1; c++) {
                var i = r * mbCols + c;
                var neighbors = 0;
                for (var dr = -1; dr <= 1; dr++) {
                    for (var dc = -1; dc <= 1; dc++) {
                        if (skinMap[(r + dr) * mbCols + c + dc] != 0) {
                            neighbors++;
                        }
                    }
                }
                if (skinMap[i] != 0 && neighbors < 2) {
                    skinMap[i] = 0;
                }
                if (skinMap[i] == 0 && neighbors == 8) {
                    skinMap[i] = 1;
                }
            }
        }
    }

    /**
     * Returns whether a macroblock looks like skin, libvpx's {@code vp8_compute_skin_block}.
     *
     * @param s             the source
     * @param y             the index of the macroblock's luma samples
     * @param uv            the index of the macroblock's chroma samples
     * @param small         whether the picture is at most 352x288, which tests 8x8 blocks
     * @param consecZeroMv  the macroblock's consecutive zero-motion frames
     * @return {@code true} for skin
     */
    private static boolean skinBlock(FrameBuffer s, int y, int uv, boolean small, int consecZeroMv) {
        if (consecZeroMv > 60) {
            return false;
        }
        var motion = consecZeroMv > 25 ? 0 : 1;
        var stride = s.yStride;
        var uvStride = s.uvStride;
        if (!small) {
            var ys = avg2x2(s.y, y + 7 * stride + 7, stride);
            var us = avg2x2(s.u, uv + 3 * uvStride + 3, uvStride);
            var vs = avg2x2(s.v, uv + 3 * uvStride + 3, uvStride);
            return skinPixel(ys, us, vs, motion);
        }
        var skin = 0;
        for (var i = 0; i < 2; i++) {
            for (var j = 0; j < 2; j++) {
                var ys = avg2x2(s.y, y + 3 * stride + 3, stride);
                var us = avg2x2(s.u, uv + uvStride + 1, uvStride);
                var vs = avg2x2(s.v, uv + uvStride + 1, uvStride);
                skin += skinPixel(ys, us, vs, motion) ? 1 : 0;
                if (skin >= 2) {
                    return true;
                }
                y += 8;
                uv += 4;
            }
            y += (stride << 3) - 16;
            uv += (uvStride << 2) - 8;
        }
        return false;
    }

    /**
     * Returns the rounded average of a 2x2 block.
     *
     * @param p      the plane
     * @param off    the index of the block
     * @param stride the row stride
     * @return the average
     */
    private static int avg2x2(byte[] p, int off, int stride) {
        var sum = (p[off] & 0xFF) + (p[off + 1] & 0xFF) + (p[off + stride] & 0xFF) + (p[off + stride + 1] & 0xFF);
        return (sum + 2) >> 2;
    }

    /**
     * Returns whether a color looks like skin, libvpx's {@code vpx_skin_pixel} with its
     * five-cluster model.
     *
     * @param y      the luma
     * @param cb     the Cb chroma
     * @param cr     the Cr chroma
     * @param motion {@code 0} for a long-static block, which tightens the test
     * @return {@code true} for skin
     */
    private static boolean skinPixel(int y, int cb, int cr, int motion) {
        if (y < 40 || y > 220) {
            return false;
        }
        if (cb == 128 && cr == 128) {
            return false;
        }
        if (cb > 150 && cr < 110) {
            return false;
        }
        for (var i = 0; i < 5; i++) {
            var diff = skinColorDifference(cb, cr, i);
            var threshold = SKIN_THRESHOLD[i + 1];
            if (diff < threshold) {
                if (y < 60 && diff > 3 * (threshold >> 2)) {
                    return false;
                }
                return motion != 0 || diff <= (threshold >> 1);
            }
            if (diff > (threshold << 3)) {
                return false;
            }
        }
        return false;
    }

    /**
     * Returns the Mahalanobis distance of a chroma pair from one cluster of the skin model,
     * libvpx's {@code vpx_evaluate_skin_color_difference}.
     *
     * @param cb  the Cb chroma
     * @param cr  the Cr chroma
     * @param idx the cluster
     * @return the distance in Q18
     */
    private static int skinColorDifference(int cb, int cr, int idx) {
        var cbQ6 = cb << 6;
        var crQ6 = cr << 6;
        var cbDiffQ12 = (cbQ6 - SKIN_MEAN[2 * idx]) * (cbQ6 - SKIN_MEAN[2 * idx]);
        var cbcrDiffQ12 = (cbQ6 - SKIN_MEAN[2 * idx]) * (crQ6 - SKIN_MEAN[2 * idx + 1]);
        var crDiffQ12 = (crQ6 - SKIN_MEAN[2 * idx + 1]) * (crQ6 - SKIN_MEAN[2 * idx + 1]);
        var cbDiffQ2 = (cbDiffQ12 + (1 << 9)) >> 10;
        var cbcrDiffQ2 = (cbcrDiffQ12 + (1 << 9)) >> 10;
        var crDiffQ2 = (crDiffQ12 + (1 << 9)) >> 10;
        return SKIN_INV_COV[0] * cbDiffQ2 + SKIN_INV_COV[1] * cbcrDiffQ2 + SKIN_INV_COV[2] * cbcrDiffQ2
                + SKIN_INV_COV[3] * crDiffQ2;
    }

    /**
     * Sets the speed features of the current frame, libvpx's {@code vp8_set_speed_features}
     * for real-time mode.
     */
    private void setSpeedFeatures() {
        var continuous = speed + 7;
        var refFrames = 1 + Integer.bitCount(keyFrame ? 0 : refFrameFlags);
        Arrays.fill(threshMult, 0);
        Arrays.fill(modeCheckFreq, 0);
        var znn = RtTables.speedMap(continuous, RtTables.THRESH_ZNN);
        threshMult[RtTables.THR_ZERO2] = znn;
        threshMult[RtTables.THR_ZERO3] = znn;
        threshMult[RtTables.THR_NEAREST2] = znn;
        threshMult[RtTables.THR_NEAREST3] = znn;
        threshMult[RtTables.THR_NEAR2] = znn;
        threshMult[RtTables.THR_NEAR3] = znn;
        var vh = RtTables.speedMap(continuous, RtTables.THRESH_VH);
        threshMult[RtTables.THR_V_PRED] = vh;
        threshMult[RtTables.THR_H_PRED] = vh;
        threshMult[RtTables.THR_B_PRED] = RtTables.speedMap(continuous, RtTables.THRESH_BPRED);
        threshMult[RtTables.THR_TM] = RtTables.speedMap(continuous, RtTables.THRESH_TM);
        threshMult[RtTables.THR_NEW1] = RtTables.speedMap(continuous, RtTables.THRESH_NEW1);
        var new2 = RtTables.speedMap(continuous, RtTables.THRESH_NEW2);
        threshMult[RtTables.THR_NEW2] = new2;
        threshMult[RtTables.THR_NEW3] = new2;
        threshMult[16] = RtTables.MAX;
        threshMult[17] = RtTables.MAX;
        threshMult[18] = RtTables.MAX;
        if (speed <= 6 && layers > 1 && (refFrameFlags & FLAG_LAST) != 0 && (refFrameFlags & FLAG_GOLDEN) != 0) {
            var shift = closestReferenceFrame == GOLDEN_FRAME ? 3 : 1;
            threshMult[RtTables.THR_ZERO2] >>= shift;
            threshMult[RtTables.THR_NEAREST2] >>= shift;
            threshMult[RtTables.THR_NEAR2] >>= shift;
        }
        var zn2 = RtTables.speedMap(continuous, RtTables.FREQ_ZN2);
        modeCheckFreq[RtTables.THR_ZERO2] = zn2;
        modeCheckFreq[RtTables.THR_ZERO3] = zn2;
        modeCheckFreq[RtTables.THR_NEAREST2] = zn2;
        modeCheckFreq[RtTables.THR_NEAREST3] = zn2;
        var near2 = RtTables.speedMap(continuous, RtTables.FREQ_NEAR2);
        modeCheckFreq[RtTables.THR_NEAR2] = near2;
        modeCheckFreq[RtTables.THR_NEAR3] = near2;
        var vhb = RtTables.speedMap(continuous, RtTables.FREQ_VHB);
        modeCheckFreq[RtTables.THR_V_PRED] = vhb;
        modeCheckFreq[RtTables.THR_H_PRED] = vhb;
        modeCheckFreq[RtTables.THR_B_PRED] = vhb;
        modeCheckFreq[RtTables.THR_NEW1] = RtTables.speedMap(speed == 10 ? 16 : continuous, RtTables.FREQ_NEW1);
        var freqNew2 = RtTables.speedMap(continuous, RtTables.FREQ_NEW2);
        modeCheckFreq[RtTables.THR_NEW2] = freqNew2;
        modeCheckFreq[RtTables.THR_NEW3] = freqNew2;
        fullFilterSearch = speed <= 4;
        searchMethod = speed > 4 ? SEARCH_HEX : SEARCH_DIAMOND;
        improvedMvPred = true;
        if (speed > 4) {
            subpelMethod = SUBPEL_QUARTER;
        } else {
            subpelMethod = SUBPEL_ITERATIVE;
        }
        if (speed > 6) {
            var bins = stats.errorBins;
            var min = 2000 >> 7;
            var sum = 0;
            var i = 0;
            for (; i < min; i++) {
                sum += bins[i];
            }
            var totalSkip = sum;
            sum = 0;
            for (; i < RtStats.ERROR_BINS; i++) {
                sum += bins[i];
                if (10L * sum >= (long) (speed - 6) * (mbs - totalSkip)) {
                    break;
                }
            }
            i--;
            var thresh = Math.max(i << 7, 2000);
            if (refFrames > 1) {
                threshMult[RtTables.THR_NEW1] = thresh;
                threshMult[RtTables.THR_NEAREST1] = thresh >> 1;
                threshMult[RtTables.THR_NEAR1] = thresh >> 1;
            }
            if (refFrames > 2) {
                threshMult[RtTables.THR_NEW2] = thresh << 1;
                threshMult[RtTables.THR_NEAREST2] = thresh;
                threshMult[RtTables.THR_NEAR2] = thresh;
            }
            if (refFrames > 3) {
                threshMult[RtTables.THR_NEW3] = thresh << 1;
                threshMult[RtTables.THR_NEAREST3] = thresh;
                threshMult[RtTables.THR_NEAR3] = thresh;
            }
            improvedMvPred = false;
        }
        if (speed > 8) {
            subpelMethod = SUBPEL_HALF;
        }
        simpleFilter = speed >= 14;
        if (speed >= 15) {
            subpelMethod = SUBPEL_NONE;
        }
    }

    /**
     * Computes the rate-distortion constants, mode thresholds and costs of the current frame,
     * libvpx's {@code vp8_initialize_rd_consts}, {@code vp8cx_initialize_me_consts},
     * {@code vp8_init_mode_costs} and the reference costs of {@code init_encode_frame_mb_context}.
     */
    private void initializeRdConsts() {
        var qValue = Vp8Tables.DC_QLOOKUP[baseQ];
        var cappedQ = Math.min(qValue, 160);
        var rdconst = RD_CONSTANT;
        var mult = (int) (rdconst * ((double) cappedQ * cappedQ));
        if (rc.zbinOverQuant > 0) {
            var oqFactor = 1.0 + 0.0015625 * rc.zbinOverQuant;
            var modq = (double) (int) (cappedQ * oqFactor);
            mult = (int) (rdconst * (modq * modq));
        }
        errorPerBit = Math.max(1, mult / 110);
        setSpeedFeatures();
        var q = Math.max(8, (int) Math.pow(qValue, 1.25));
        if (mult > 1000) {
            rdDiv = 1;
            rdMult = mult / 100;
            for (var i = 0; i < RtTables.MAX_MODES; i++) {
                rdBaselineThresh[i] = threshMult[i] < Integer.MAX_VALUE ? threshMult[i] * q / 100 : Integer.MAX_VALUE;
            }
        } else {
            rdDiv = 100;
            rdMult = mult;
            for (var i = 0; i < RtTables.MAX_MODES; i++) {
                rdBaselineThresh[i] = threshMult[i] < Integer.MAX_VALUE / q ? threshMult[i] * q : Integer.MAX_VALUE;
            }
        }
        sadPerBit16 = RtTables.SAD_PER_BIT16[baseQ];
        if (keyFrame) {
            for (var mode = 0; mode < 5; mode++) {
                mbModeCost[mode] = RtTables.treeCost(RtTables.KF_YMODE_CODES, Vp8Tables.KF_YMODE_TREE,
                        Vp8Tables.KF_YMODE_PROBS, 0, mode);
            }
            for (var a = 0; a < 10; a++) {
                for (var l = 0; l < 10; l++) {
                    for (var mode = 0; mode < 10; mode++) {
                        kfBmodeCosts[(a * 10 + l) * 10 + mode] = RtTables.treeCost(RtTables.BMODE_CODES,
                                Vp8Tables.BMODE_TREE, Vp8Tables.KF_BMODE_PROBS, (a * 10 + l) * 9, mode);
                    }
                }
            }
        } else {
            for (var mode = 0; mode < 5; mode++) {
                mbModeCost[mode] = RtTables.treeCost(RtTables.YMODE_CODES, Vp8Tables.YMODE_TREE, ymodeProbs, 0, mode);
            }
            for (var mode = 0; mode < 10; mode++) {
                interBmodeCosts[mode] = RtTables.treeCost(RtTables.BMODE_CODES, Vp8Tables.BMODE_TREE,
                        Vp8Tables.BMODE_PROBS, 0, mode);
            }
        }
        if (refFrameFlags == FLAG_LAST) {
            calcRefFrameCosts(probIntraCoded, 255, 128);
        } else if (layers > 1 && refFrameFlags == FLAG_GOLDEN) {
            calcRefFrameCosts(probIntraCoded, 1, 255);
        } else if (layers > 1 && refFrameFlags == FLAG_ALTREF) {
            calcRefFrameCosts(probIntraCoded, 1, 1);
        } else {
            calcRefFrameCosts(probIntraCoded, probLastCoded, probGfCoded);
        }
        if (mvCostsDirty) {
            if (!Arrays.equals(mvProbs, builtMvProbs)) {
                buildMvCosts();
                System.arraycopy(mvProbs, 0, builtMvProbs, 0, builtMvProbs.length);
            }
            mvCostsDirty = false;
        }
    }

    /**
     * Sets the cost of each reference from the reference probabilities, libvpx's
     * {@code vp8_calc_ref_frame_costs}.
     *
     * @param probIntra the probability of intra
     * @param probLast  the probability of the last frame among inter references
     * @param probGf    the probability of the golden frame among the other two
     */
    private void calcRefFrameCosts(int probIntra, int probLast, int probGf) {
        refFrameCost[INTRA_FRAME] = RtTables.bitCost(probIntra, 0);
        refFrameCost[LAST_FRAME] = RtTables.bitCost(probIntra, 1) + RtTables.bitCost(probLast, 0);
        refFrameCost[GOLDEN_FRAME] = RtTables.bitCost(probIntra, 1) + RtTables.bitCost(probLast, 1)
                + RtTables.bitCost(probGf, 0);
        refFrameCost[ALTREF_FRAME] = RtTables.bitCost(probIntra, 1) + RtTables.bitCost(probLast, 1)
                + RtTables.bitCost(probGf, 1);
    }

    /**
     * Builds the motion vector component costs from the motion vector probabilities, libvpx's
     * {@code vp8_build_component_cost_table}.
     */
    private void buildMvCosts() {
        for (var component = 0; component < 2; component++) {
            var base = component * 19;
            var off = component * RtStats.MV_VALS + RtStats.MV_MAX;
            mvCost[off] = mvComponentCost(0, base);
            var sign0 = RtTables.bitCost(mvProbs[base + 1], 0);
            var sign1 = RtTables.bitCost(mvProbs[base + 1], 1);
            for (var i = 1; i <= RtStats.MV_MAX; i++) {
                var cost = mvComponentCost(i, base);
                mvCost[off + i] = cost + sign0;
                mvCost[off - i] = cost + sign1;
            }
        }
    }

    /**
     * Returns the cost of a motion vector component magnitude without its sign, libvpx's
     * {@code cost_mvcomponent}.
     *
     * @param v    the magnitude in quarter pixels
     * @param base the index of the component's probabilities
     * @return the cost in 1/256 bit
     */
    private int mvComponentCost(int v, int base) {
        var p = mvProbs;
        int cost;
        if (v < 8) {
            cost = RtTables.bitCost(p[base], 0) + RtTables.treeCost(RtTables.SMALL_MV_CODES, Vp8Tables.SMALL_MV_TREE, p,
                    base + 2, v);
            return cost;
        }
        cost = RtTables.bitCost(p[base], 1);
        for (var i = 0; i < 3; i++) {
            cost += RtTables.bitCost(p[base + 9 + i], (v >> i) & 1);
        }
        for (var i = 9; i > 3; i--) {
            cost += RtTables.bitCost(p[base + 9 + i], (v >> i) & 1);
        }
        if ((v & 0xFFF0) != 0) {
            cost += RtTables.bitCost(p[base + 9 + 3], (v >> 3) & 1);
        }
        return cost;
    }

    /**
     * Codes every macroblock of the current frame, libvpx's {@code vp8_encode_frame}, on the
     * calling thread or in a wavefront with helpers of the module's shared pool.
     */
    private void encodeMacroblocks() {
        initializeRdConsts();
        filterLevelPicked = false;
        earlyFilterRow = fullFilterSearch ? -1
                : Math.min(mbRows * 16 / 32 + Math.max(1, mbRows / PARTIAL_FRAME_FRACTION) - 1, mbRows - 1);
        stats.clear();
        for (var s : threadStats) {
            s.clear();
        }
        for (var r = 0; r < mbRows; r++) {
            rows[r].reset(rdThreshMult, rdBaselineThresh);
        }
        Arrays.fill(aboveContext, (byte) 0);
        cur.setupIntraTopBorder();
        for (var r = 0; r < mbRows; r++) {
            cur.setupIntraLeftBorder(r);
        }
        var helpers = helpers();
        if (helpers == 0) {
            for (var row = 0; row < mbRows; row++) {
                codeRow(coders[0], threadStats[0], row, null);
                ParallelBands.checkpoint();
            }
        } else {
            resetWavefront();
            wavefront.run(1 + helpers, 0, mbRows, codeBody);
        }
        for (var s : threadStats) {
            stats.add(s);
        }
        System.arraycopy(rows[mbRows - 1].rdThreshMult, 0, rdThreshMult, 0, RtTables.MAX_MODES);
        if (segmentationEnabled) {
            var counts = stats.segmentCounts;
            segmentTreeProbs[0] = segmentTreeProbs[1] = segmentTreeProbs[2] = 255;
            var total = counts[0] + counts[1] + counts[2] + counts[3];
            if (total > 0) {
                segmentTreeProbs[0] = (counts[0] + counts[1]) * 255 / total;
                var t = counts[0] + counts[1];
                if (t > 0) {
                    segmentTreeProbs[1] = counts[0] * 255 / t;
                }
                t = counts[2] + counts[3];
                if (t > 0) {
                    segmentTreeProbs[2] = counts[2] * 255 / t;
                }
                for (var i = 0; i < 3; i++) {
                    if (segmentTreeProbs[i] == 0) {
                        segmentTreeProbs[i] = 1;
                    }
                }
            }
        }
    }

    /**
     * Returns how many helpers of the shared pool join a wavefront of the frame's rows.
     *
     * @return the helper count, 0 to run the rows on the calling thread
     */
    private int helpers() {
        if (coders.length > 1 && mbRows > 1 && ParallelBands.enabled()) {
            return Math.min(coders.length - 1, ParallelBands.available());
        }
        return 0;
    }

    /**
     * Sets the progress of every row of {@link #wavefront} to none.
     */
    private void resetWavefront() {
        for (var row = 0; row < mbRows; row++) {
            wavefront.reset(row, 0);
        }
    }

    /**
     * Codes one macroblock row on the calling participant, then extends its bottom samples for
     * the above-right prediction of the next row.
     *
     * @param coder the participant's coder
     * @param stats the participant's statistics
     * @param row the macroblock row
     * @param wait how to wait for the row above, or {@code null} on a single thread
     */
    private void codeRow(RtMbCoder coder, RtStats stats, int row, RowWavefront wait) {
        var state = rows[row];
        var above = 0;
        for (var col = 0; col < mbCols; col++) {
            if (wait != null && row > 0) {
                var needed = Math.min(col + 2, mbCols + 1);
                if (above < needed) {
                    above = wait.await(row - 1, needed);
                }
            }
            coder.code(state, stats, row, col);
            if (wait != null && col < mbCols - 1) {
                wait.publish(row, col + 1);
            }
        }
        cur.extendRowAboveRight(row);
        if (wait != null) {
            wait.publish(row, mbCols + 1);
        }
        if (row == earlyFilterRow) {
            pickLoopFilterLevel();
            filterLevelPicked = true;
        }
    }

    /**
     * The macroblock row after which the band the fast loop filter search measures is coded, so
     * the search runs as soon as that row is done, while other threads code the rows below; or
     * {@code -1} with the full search, which needs the whole frame.
     */
    private int earlyFilterRow = -1;

    /**
     * Whether the loop filter level of the frame being coded is already picked.
     */
    private boolean filterLevelPicked;

    /**
     * Records the per-frame bookkeeping that follows the macroblocks: the motion vectors of the
     * frame for the next frame's predictions and the count of last-frame zero motion.
     */
    private void afterMacroblocks() {
        if (!keyFrame) {
            var lfStride = mbCols + 2;
            for (var r = 0; r <= mbRows; r++) {
                for (var c = 0; c <= mbCols; c++) {
                    var mi = r * miStride + c;
                    var lf = r * lfStride + c;
                    if (mbRef[mi] != INTRA_FRAME) {
                        lfMv[lf] = mbMv[mi];
                    }
                    lfRef[lf] = mbRef[mi];
                }
            }
        }
        zeromvCount = 0;
        if (!keyFrame) {
            for (var r = 0; r < mbRows; r++) {
                for (var c = 0; c < mbCols; c++) {
                    var mi = (r + 1) * miStride + c + 1;
                    if (mbMode[mi] == ZEROMV && mbRef[mi] == LAST_FRAME) {
                        zeromvCount++;
                    }
                }
            }
        }
    }

    /**
     * Picks the loop filter level of the frame, the search of libvpx's
     * {@code vp8_loopfilter_frame}.
     */
    private void pickLoopFilterLevel() {
        Arrays.fill(filterErrors, -1);
        if (layers > 1) {
            filterLevel = rc.filterLevel;
        }
        if (keyFrame) {
            filterLevel = baseQ * 3 / 8;
        }
        if (fullFilterSearch) {
            pickFilterLevelFull();
        } else {
            pickFilterLevelFast();
        }
        if (!keyFrame) {
            relaxFilterLevel();
        }
    }

    /**
     * Lowers the loop filter level of an inter frame by up to {@link #FILTER_RELAX_STEPS} of the
     * search's steps while the luma error of the lower level stays within
     * {@link #FILTER_RELAX_TOLERANCE} of the error at the picked level.
     *
     * <p>The squared error is flat around its minimum for the static scenes of calls while the
     * structural similarity of the picture keeps rising as the filter weakens, so a level that
     * costs a hundredth of a percent of error is preferred to the strongest one.
     *
     * @implNote This implementation has no counterpart in libvpx, whose search keeps the level of
     * the smallest error.
     */
    private void relaxFilterLevel() {
        var min = minFilterLevel();
        if (filterLevel <= min) {
            return;
        }
        var best = partialFilterError(filterLevel);
        var limit = best + best * FILTER_RELAX_TOLERANCE / 10000;
        var level = filterLevel;
        for (var step = 0; step < FILTER_RELAX_STEPS; step++) {
            var candidate = level - (1 + (level > 10 ? 1 : 0));
            if (candidate < min || partialFilterError(candidate) > limit) {
                break;
            }
            level = candidate;
        }
        filterLevel = level;
    }

    /**
     * Runs the two independent final steps of a frame: packs the bitstream into
     * {@link #packedFrame}, and filters the reconstruction with the picked level when it becomes
     * a reference, the filtering of libvpx's {@code vp8_loopfilter_frame}, then extends its
     * borders. With more than one thread the other threads filter rows in a wavefront while the
     * calling thread packs.
     */
    private void finishFrame() {
        var updatesReference = refreshLast || refreshGolden || refreshAlt || copyToAlt != 0;
        var filter = filterLevel > 0 && (updatesReference || keepReconstruction);
        if (filter) {
            setupFilterLevels(filterLevel);
        }
        if (filter && threads > 1) {
            try {
                bitstream.packStart();
                filterWhilePacking();
                packedFrame = bitstream.assemble();
            } finally {
                if (updatesReference) {
                    extendBorders();
                }
            }
            return;
        }
        if (filter) {
            for (var r = 0; r < mbRows; r++) {
                filterRow(cur, r, false, filterScratchBytes);
            }
        }
        if (updatesReference) {
            extendBorders();
        }
        packedFrame = bitstream.pack();
    }

    /**
     * Runs the packing tasks while filtering the rows of the frame being coded, and returns once
     * every task has run and every row is filtered.
     *
     * <p>A row filters a macroblock once the row above has filtered the macroblock above-right of
     * it, the order in which the filter of each macroblock sees exactly the samples the raster
     * order gives it.
     */
    private void filterWhilePacking() {
        var helpers = helpers();
        if (helpers == 0) {
            for (var row = 0; row < mbRows; row++) {
                filterRow(coders[0].filterScratch, row, null);
            }
            for (var task : packTasks) {
                task.run();
            }
            return;
        }
        resetWavefront();
        wavefront.run(1 + helpers, 0, mbRows, filterBody, packTasks);
    }

    /**
     * Extends the borders of the frame being coded, which becomes a reference.
     */
    private void extendBorders() {
        for (var r = 0; r < mbRows; r++) {
            cur.extendRowSides(r);
        }
        cur.extendTopBottom();
    }

    /**
     * Returns the smallest loop filter level of the frame quantizer, libvpx's
     * {@code get_min_filter_level}.
     *
     * @return the level
     */
    private int minFilterLevel() {
        if (baseQ <= 6) {
            return 0;
        }
        if (baseQ <= 16) {
            return 1;
        }
        return baseQ / 8;
    }

    /**
     * Searches the loop filter level by filtering a band of rows in the middle of the frame,
     * libvpx's {@code vp8cx_pick_filter_level_fast}.
     */
    private void pickFilterLevelFast() {
        var min = minFilterLevel();
        var max = 63;
        filterLevel = Math.clamp(filterLevel, min, max);
        var filtVal = filterLevel;
        var bestFiltVal = filtVal;
        var bestErr = partialFilterError(filtVal);
        filtVal -= 1 + (filtVal > 10 ? 1 : 0);
        while (filtVal >= min) {
            var err = partialFilterError(filtVal);
            if (err < bestErr) {
                bestErr = err;
                bestFiltVal = filtVal;
            } else {
                break;
            }
            filtVal -= 1 + (filtVal > 10 ? 1 : 0);
        }
        filtVal = filterLevel + 1 + (filtVal > 10 ? 1 : 0);
        if (bestFiltVal == filterLevel) {
            bestErr -= bestErr >> 10;
            while (filtVal < max) {
                var err = partialFilterError(filtVal);
                if (err < bestErr) {
                    bestErr = err - (err >> 10);
                    bestFiltVal = filtVal;
                } else {
                    break;
                }
                filtVal += 1 + (filtVal > 10 ? 1 : 0);
            }
        }
        filterLevel = Math.clamp(bestFiltVal, min, max);
    }

    /**
     * Returns the luma error of a band of rows in the middle of the frame filtered at a level,
     * libvpx's {@code yv12_copy_partial_frame}, {@code vp8_loop_filter_partial_frame} and
     * {@code calc_partial_ssl_err}.
     *
     * @param level the frame filter level
     * @return the sum of squared errors against the source
     */
    private long partialFilterError(int level) {
        var cached = filterErrors[level];
        if (cached >= 0) {
            return cached;
        }
        var error = computePartialFilterError(level);
        filterErrors[level] = error;
        return error;
    }

    /**
     * The luma error of the band of rows for each loop filter level of the frame being searched,
     * or {@code -1} when the level was not tried.
     */
    private final long[] filterErrors = new long[64];

    /**
     * Computes the luma error of a band of rows in the middle of the frame filtered at a level.
     *
     * @param level the frame filter level
     * @return the sum of squared errors against the source
     */
    private long computePartialFilterError(int level) {
        var rowsToFilter = Math.max(1, mbRows / PARTIAL_FRAME_FRACTION);
        var firstRow = (mbRows * 16 >> 5);
        var scratch = filterScratch;
        var stride = cur.yStride;
        var first = firstRow * 16 - 4;
        var lines = rowsToFilter * 16 + 4;
        for (var l = 0; l < lines; l++) {
            var y = first + l;
            var off = cur.yOrigin + (y < 0 ? 0 : y) * stride - FrameBuffer.LUMA_BORDER;
            System.arraycopy(cur.y, off, scratch.y, scratch.yOrigin + y * stride - FrameBuffer.LUMA_BORDER, stride);
        }
        setupFilterLevels(level);
        for (var r = 0; r < rowsToFilter; r++) {
            filterRow(scratch, firstRow + r, true, filterScratchBytes);
        }
        long total = 0;
        var src = source;
        for (var r = 0; r < rowsToFilter; r++) {
            for (var c = 0; c < mbCols; c++) {
                var y = (firstRow + r) * 16;
                var a = src.yOrigin + y * src.yStride + c * 16;
                var b = scratch.yOrigin + y * stride + c * 16;
                total += RtDsp.sse(rtDsp.variance16(src.y, a, src.yStride, scratch.y, b, stride));
            }
        }
        return total;
    }

    /**
     * Searches the loop filter level by filtering the whole luma plane at levels approaching the
     * best by halving steps, libvpx's {@code vp8cx_pick_filter_level}.
     */
    private void pickFilterLevelFull() {
        var min = minFilterLevel();
        var max = 63;
        var ssErr = new long[64];
        var filtMid = Math.clamp(filterLevel, min, max);
        var filterStep = filtMid < 16 ? 4 : filtMid / 4;
        var bestErr = fullFilterError(filtMid);
        ssErr[filtMid] = bestErr;
        var filtBest = filtMid;
        var filtDirection = 0;
        while (filterStep > 0) {
            var filtHigh = Math.min(filtMid + filterStep, max);
            var filtLow = Math.max(filtMid - filterStep, min);
            if (filtDirection <= 0 && filtLow != filtMid) {
                var err = ssErr[filtLow] == 0 ? fullFilterError(filtLow) : ssErr[filtLow];
                ssErr[filtLow] = err;
                if (err < bestErr) {
                    bestErr = err;
                    filtBest = filtLow;
                }
            }
            if (filtDirection >= 0 && filtHigh != filtMid) {
                var err = ssErr[filtHigh] == 0 ? fullFilterError(filtHigh) : ssErr[filtHigh];
                ssErr[filtHigh] = err;
                if (err < bestErr) {
                    bestErr = err;
                    filtBest = filtHigh;
                }
            }
            if (filtBest == filtMid) {
                filterStep /= 2;
                filtDirection = 0;
            } else {
                filtDirection = filtBest < filtMid ? -1 : 1;
                filtMid = filtBest;
            }
        }
        filterLevel = filtBest;
    }

    /**
     * Returns the luma error of the whole frame filtered at a level.
     *
     * @param level the frame filter level
     * @return the sum of squared errors against the source
     */
    private long fullFilterError(int level) {
        var scratch = filterScratch;
        System.arraycopy(cur.y, 0, scratch.y, 0, cur.y.length);
        setupFilterLevels(level);
        if (level > 0) {
            for (var r = 0; r < mbRows; r++) {
                filterRow(scratch, r, false, filterScratchBytes);
            }
        }
        long total = 0;
        var src = source;
        for (var r = 0; r < mbRows; r++) {
            for (var c = 0; c < mbCols; c++) {
                var a = src.yOrigin + r * 16 * src.yStride + c * 16;
                var b = scratch.yOrigin + r * 16 * scratch.yStride + c * 16;
                total += RtDsp.sse(rtDsp.variance16(src.y, a, src.yStride, scratch.y, b, scratch.yStride));
            }
        }
        return total;
    }

    /**
     * Computes the loop filter level of every segment, reference and mode class from a frame
     * level, libvpx's {@code vp8_loop_filter_frame_init}.
     *
     * @param level the frame filter level
     */
    private void setupFilterLevels(int level) {
        for (var seg = 0; seg < 4; seg++) {
            var lvlSeg = level;
            if (segmentationEnabled) {
                lvlSeg = Math.clamp(lvlSeg, 0, 63);
            }
            var base = seg * 16;
            var lvlRef = lvlSeg + REF_LF_DELTAS[INTRA_FRAME];
            filterLevels[base] = Math.clamp(lvlRef + MODE_LF_DELTAS[0], 0, 63);
            filterLevels[base + 1] = Math.clamp(lvlRef, 0, 63);
            for (var ref = 1; ref < 4; ref++) {
                lvlRef = lvlSeg + REF_LF_DELTAS[ref];
                for (var mode = 1; mode < 4; mode++) {
                    filterLevels[base + ref * 4 + mode] = Math.clamp(lvlRef + MODE_LF_DELTAS[mode], 0, 63);
                }
            }
        }
    }

    /**
     * Applies the loop filter to one macroblock row of the frame being coded on a participant of
     * the filter wavefront, waiting for the row above as {@link #filterWhilePacking} describes.
     *
     * @param scratch the participant's filter scratch
     * @param row     the macroblock row
     * @param wait    the wavefront, or {@code null} on a single thread
     */
    private void filterRow(byte[] scratch, int row, RowWavefront wait) {
        if (wait == null) {
            filterRow(cur, row, false, scratch);
            return;
        }
        var above = 0;
        for (var col = 0; col < mbCols; col++) {
            if (row > 0) {
                var needed = Math.min(col + 2, mbCols);
                if (above < needed) {
                    above = wait.await(row - 1, needed);
                }
            }
            filterMacroblock(cur, row, col, false, scratch);
            wait.publish(row, col + 1);
        }
    }

    /**
     * Applies the loop filter to one macroblock row of a frame, like the decoder.
     *
     * @param frame    the frame to filter
     * @param row      the macroblock row
     * @param forceTop whether to filter the top macroblock edges even on the first row, as the
     *                 band search of libvpx's {@code vp8_loop_filter_partial_frame} does
     * @param scratch  the filter scratch
     */
    private void filterRow(FrameBuffer frame, int row, boolean forceTop, byte[] scratch) {
        for (var col = 0; col < mbCols; col++) {
            filterMacroblock(frame, row, col, forceTop, scratch);
        }
    }

    /**
     * Applies the loop filter to the edges of one macroblock of a frame, like the decoder.
     *
     * @param frame    the frame to filter
     * @param row      the macroblock row
     * @param col      the macroblock column
     * @param forceTop whether to filter the top macroblock edge even on the first row
     * @param scratch  the filter scratch
     */
    private void filterMacroblock(FrameBuffer frame, int row, int col, boolean forceTop, byte[] scratch) {
        var yStride = frame.yStride;
        var uvStride = frame.uvStride;
        var yOff = frame.yOrigin + row * 16 * yStride + col * 16;
        var uvOff = frame.uvOrigin + row * 8 * uvStride + col * 8;
        var idx = (row + 1) * miStride + col + 1;
        var mode = mbMode[idx];
        var skipInner = mode != B_PRED && mbSkip[idx] != 0;
        var level = filterLevels[mbSegment[idx] * 16 + mbRef[idx] * 4 + MODE_LF_CLASS[mode]];
        if (level == 0) {
            return;
        }
        var interior = level;
        var subLimit = 2 * level + interior;
        var mbLimit = 2 * (level + 2) + interior;
        var edges = (col > 0 ? Vp8Dsp.EDGE_LEFT : 0) | (row > 0 || forceTop ? Vp8Dsp.EDGE_TOP : 0)
                | (skipInner ? 0 : Vp8Dsp.EDGE_INNER);
        if (simpleFilter) {
            dsp.filterSimple(frame.y, yOff, yStride, mbLimit, subLimit, edges, scratch);
        } else {
            int hev;
            if (keyFrame) {
                hev = level >= 40 ? 2 : (level >= 15 ? 1 : 0);
            } else {
                hev = level >= 40 ? 3 : (level >= 20 ? 2 : (level >= 15 ? 1 : 0));
            }
            dsp.filterNormal(frame.y, yOff, yStride, frame.u, frame.v, uvOff, uvStride,
                    mbLimit, subLimit, interior, hev, edges, scratch);
        }
    }

    /**
     * Makes the coded frame the references it refreshes, libvpx's
     * {@code update_reference_frames}.
     */
    private void updateReferenceFrames() {
        if (keyFrame) {
            bufferFlags[goldenIdx] &= ~FLAG_GOLDEN;
            bufferFlags[altIdx] &= ~FLAG_ALTREF;
            bufferFlags[newIdx] |= FLAG_GOLDEN | FLAG_ALTREF;
            goldenIdx = newIdx;
            altIdx = newIdx;
            currentRefFrames[GOLDEN_FRAME] = frameCount;
            currentRefFrames[ALTREF_FRAME] = frameCount;
        } else {
            if (refreshAlt) {
                bufferFlags[altIdx] &= ~FLAG_ALTREF;
                bufferFlags[newIdx] |= FLAG_ALTREF;
                altIdx = newIdx;
                currentRefFrames[ALTREF_FRAME] = frameCount;
            } else if (copyToAlt == 2 && altIdx != goldenIdx) {
                bufferFlags[altIdx] &= ~FLAG_ALTREF;
                bufferFlags[goldenIdx] |= FLAG_ALTREF;
                altIdx = goldenIdx;
                currentRefFrames[ALTREF_FRAME] = currentRefFrames[GOLDEN_FRAME];
            }
            if (refreshGolden) {
                bufferFlags[goldenIdx] &= ~FLAG_GOLDEN;
                bufferFlags[newIdx] |= FLAG_GOLDEN;
                goldenIdx = newIdx;
                currentRefFrames[GOLDEN_FRAME] = frameCount;
            }
        }
        if (refreshLast) {
            bufferFlags[lastIdx] &= ~FLAG_LAST;
            bufferFlags[newIdx] |= FLAG_LAST;
            lastIdx = newIdx;
            currentRefFrames[LAST_FRAME] = frameCount;
        }
    }
}
