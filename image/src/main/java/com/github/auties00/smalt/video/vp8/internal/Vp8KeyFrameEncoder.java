/* Ports libwebp 1.6.0 (src/enc/webp_enc.c, analysis_enc.c, quant_enc.c, frame_enc.c, iterator_enc.c, filter_enc.c,
   tree_enc.c, syntax_enc.c). Copyright (c) 2010, Google Inc.; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.video.vp8.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.internal.task.ParallelBands;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Encodes one 4:2:0 picture as a VP8 key frame.
 *
 * <p>An instance encodes a single picture: construct it with the picture and settings, then call
 * {@link #encode()} once. With one token partition, the frame is byte-identical to libwebp's for
 * the same YUV samples and settings, in libwebp's default configuration for everything the
 * settings do not cover (one pass, no target size, strong filter, no autofilter, no
 * preprocessing). With helper threads available the analysis pass runs in bands of macroblock
 * rows and the token loop of methods 3 to 6 in a {@link RowWavefront}; the output does not depend
 * on the number of threads.
 *
 * @implNote This implementation keeps one token buffer per partition and honors the requested
 * partition count, where libwebp forces a single token partition when it buffers tokens (methods 3
 * to 6).
 */
public final class Vp8KeyFrameEncoder {
    /**
     * The number of segments the bitstream supports.
     */
    private static final int NUM_MB_SEGMENTS = 4;

    /**
     * The rate-distortion level without rate estimation.
     */
    private static final int RD_OPT_NONE = 0;

    /**
     * The rate-distortion level with rate estimation but no trellis.
     */
    private static final int RD_OPT_BASIC = 1;

    /**
     * The rate-distortion level with trellis quantization of the final decision.
     */
    private static final int RD_OPT_TRELLIS = 2;

    /**
     * The rate-distortion level with trellis quantization of every candidate.
     */
    private static final int RD_OPT_TRELLIS_ALL = 3;

    /**
     * The largest first partition size, in 1/256 bit, before the encoder restricts 4x4 modes.
     */
    private static final long PARTITION0_SIZE_LIMIT = ((1L << 19) - 2048L) << 11;

    /**
     * The quality at or below which chroma DC error diffusion is enabled.
     */
    private static final float ERROR_DIFFUSION_QUALITY = 98;

    /**
     * The histogram size used for susceptibility analysis.
     */
    private static final int MAX_COEFF_THRESH = 31;

    /**
     * The largest susceptibility.
     */
    private static final int MAX_ALPHA = 255;

    /**
     * The number of k-means iterations of segment assignment.
     */
    private static final int MAX_ITERS_K_MEANS = 6;

    /**
     * The quantizer rounding biases, {@code [luma AC, Y2, chroma][DC, AC]}.
     */
    private static final int[] BIAS_MATRICES = {96, 110, 96, 108, 110, 115};

    /**
     * The high-frequency sharpening of each raster coefficient.
     */
    private static final int[] FREQ_SHARPENING = {0, 30, 60, 90, 30, 60, 90, 90, 60, 90, 90, 90, 90, 90, 90, 90};

    /**
     * The spectral distortion weights.
     */
    private static final int[] WEIGHT_Y = {38, 32, 20, 9, 32, 28, 17, 7, 20, 17, 10, 4, 9, 7, 4, 2};

    /**
     * The trellis distortion weights.
     */
    private static final int[] WEIGHT_TRELLIS = {30, 27, 19, 11, 27, 24, 17, 10, 19, 17, 12, 8, 11, 10, 8, 6};

    /**
     * The zigzag scan order.
     */
    private static final int[] ZIGZAG = ScalarVp8EncDsp.ZIGZAG;

    /**
     * The expected bytes per macroblock of each sixteenth of the quantizer range, to size the
     * partition buffers.
     */
    private static final int[] AVERAGE_BYTES_PER_MB = {50, 24, 16, 9, 7, 5, 3, 2};

    /**
     * Sixteen {@code B_DC_PRED} subblock modes.
     */
    private static final int[] ALL_DC_MODES = new int[16];

    /**
     * Reads eight bytes of a byte array as a little-endian {@code long}.
     */
    private static final VarHandle LONG = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    /**
     * Reads four bytes of a byte array as a little-endian {@code int}.
     */
    private static final VarHandle INT = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);

    /**
     * The picture being encoded.
     */
    final YuvPicture picture;

    /**
     * The requested quality.
     */
    private final float quality;

    /**
     * The speed/quality method.
     */
    private final int method;

    /**
     * The loop filter strength setting.
     */
    private final int filterStrength;

    /**
     * The loop filter sharpness setting.
     */
    private final int configSharpness;

    /**
     * The spatial noise shaping strength.
     */
    private final int snsStrength;

    /**
     * The pixel kernels.
     */
    private final Vp8EncDsp dsp = Vp8EncDsp.INSTANCE;

    /**
     * The width in macroblocks.
     */
    final int mbW;

    /**
     * The height in macroblocks.
     */
    final int mbH;

    /**
     * The row stride of {@link #preds}.
     */
    final int predsW;

    /**
     * The index in {@link #preds} of the first macroblock's first subblock mode.
     */
    final int predsBase;

    /**
     * The subblock modes with a one-entry border above and to the left.
     */
    final byte[] preds;

    /**
     * The nonzero words of the macroblock row above, preceded by a zero entry.
     */
    final int[] nz;

    /**
     * The type of each macroblock: {@code 0} for 4x4 luma modes, {@code 1} for a 16x16 mode.
     */
    final byte[] mbType;

    /**
     * The chroma mode of each macroblock.
     */
    private final byte[] mbUvMode;

    /**
     * Whether each macroblock has no coefficients.
     */
    private final byte[] mbSkip;

    /**
     * The segment of each macroblock.
     */
    private final byte[] mbSegment;

    /**
     * The susceptibility of each macroblock.
     */
    private final int[] mbAlpha;

    /**
     * The reconstructed luma samples above the current row.
     */
    final byte[] yTop;

    /**
     * The reconstructed chroma samples above the current row, 8 U then 8 V per macroblock.
     */
    final byte[] uvTop;

    /**
     * The top chroma DC diffusion errors, {@code [column][plane][2]}, or {@code null} when
     * diffusion is disabled.
     */
    final int[] topDerr;

    /**
     * The number of token partitions.
     */
    final int numParts;

    /**
     * The number of segments in use.
     */
    private int numSegments;

    /**
     * Whether the segment map is coded.
     */
    private boolean updateMap;

    /**
     * The cost of coding the segment map, in 1/256 bit.
     */
    private int segmentHdrSize;

    /**
     * Whether the simple loop filter is signaled.
     */
    private boolean filterSimple = true;

    /**
     * The frame loop filter level.
     */
    private int filterLevel;

    /**
     * The loop filter sharpness written to the header.
     */
    private int filterSharpness;

    /**
     * The bitstream version.
     */
    private final int profile;

    /**
     * The parameters of each segment.
     */
    private final SegmentInfo[] dqm;

    /**
     * The frame quantizer index.
     */
    private int baseQuant;

    /**
     * The average luma susceptibility.
     */
    private int alpha;

    /**
     * The average chroma susceptibility.
     */
    private int uvAlpha;

    /**
     * The Y2 DC quantizer delta.
     */
    private int dqY2Dc;

    /**
     * The Y1 DC quantizer delta.
     */
    private int dqY1Dc;

    /**
     * The Y2 AC quantizer delta.
     */
    private int dqY2Ac;

    /**
     * The chroma DC quantizer delta.
     */
    private int dqUvDc;

    /**
     * The chroma AC quantizer delta.
     */
    private int dqUvAc;

    /**
     * The token probabilities and costs.
     */
    private final EncProba proba;

    /**
     * The rate-distortion level of the final coding loop.
     */
    private final int rdOptLevel;

    /**
     * The budget of 4x4 mode header bits per macroblock, in 1/256 bit.
     */
    private int maxI4HeaderBits;

    /**
     * The macroblock header bit budget of distortion-only decisions.
     */
    private final long mbHeaderLimit;

    /**
     * Whether tokens are buffered until the final probabilities are known.
     */
    private final boolean useTokens;

    /**
     * The token partition writers.
     */
    private final BoolEncoder[] parts;

    /**
     * The token buffers, one per partition.
     */
    private final TokenBuffer[] tokens;

    /**
     * The macroblock iterator.
     */
    private final EncIterator it;

    /**
     * The largest first-order DC level of the blocky macroblocks this instance coded, per segment;
     * folded into {@link SegmentInfo#maxEdge} when the frame is finalized.
     */
    private final int[] maxEdgeLocal = new int[NUM_MB_SEGMENTS];

    /**
     * The workers of the last parallel pass, whose {@link #maxEdgeLocal} the finalization folds in.
     */
    private Vp8KeyFrameEncoder[] workers = new Vp8KeyFrameEncoder[0];

    /**
     * The most threads the encoder may run on, the calling thread included.
     */
    private int threadLimit = Integer.MAX_VALUE;

    /**
     * The sum of the first partition cost, in 1/256 bit, of the macroblocks this worker coded in
     * the current pass.
     */
    private long workerHeaderCost;

    /**
     * The coefficient residual description reused by the cost and coding helpers.
     */
    private final EncProba.Residual res = new EncProba.Residual();

    /**
     * The decision of the current macroblock.
     */
    private final ModeScore info = new ModeScore();

    /**
     * A scratch decision for 16x16 mode search.
     */
    private final ModeScore scoreTmp = new ModeScore();

    /**
     * The best 4x4 decision being accumulated.
     */
    private final ModeScore scoreI4Best = new ModeScore();

    /**
     * The best decision of the current subblock.
     */
    private final ModeScore scoreI4 = new ModeScore();

    /**
     * The candidate decision of the current subblock.
     */
    private final ModeScore scoreI4Tmp = new ModeScore();

    /**
     * The best chroma decision.
     */
    private final ModeScore scoreUvBest = new ModeScore();

    /**
     * The candidate chroma decision.
     */
    private final ModeScore scoreUv = new ModeScore();

    /**
     * The unfiltered reconstruction of the final coding pass, when requested with
     * {@link #captureReconstruction()}: luma of {@code 16 * mbW} by {@code 16 * mbH} samples
     * followed by U and V of half that size.
     */
    private byte[] reconstruction;

    /**
     * The transform coefficients of the 16 luma blocks.
     */
    private final short[] tmp16 = new short[256];

    /**
     * The luma DC coefficients.
     */
    private final short[] dcTmp = new short[16];

    /**
     * The transform coefficients of the 8 chroma blocks.
     */
    private final short[] tmpUv = new short[128];

    /**
     * The transform coefficients of one subblock.
     */
    private final short[] tmp4 = new short[16];

    /**
     * The quantized levels of one candidate subblock.
     */
    private final short[] tmpLevels = new short[16];

    /**
     * Scratch storage of the Walsh-Hadamard transforms.
     */
    private final int[] whtTmp = new int[16];

    /**
     * The analysis histogram.
     */
    private final int[] distribution = new int[MAX_COEFF_THRESH + 1];

    /**
     * The trellis node predecessors, {@code [position 16][node 2]}.
     */
    private final int[] nodePrev = new int[32];

    /**
     * The trellis node signs.
     */
    private final int[] nodeSign = new int[32];

    /**
     * The trellis node levels.
     */
    private final int[] nodeLevel = new int[32];

    /**
     * The trellis path scores of the two score states, {@code [state 2][node 2]}.
     */
    private final long[] stateScore = new long[4];

    /**
     * The trellis path cost tables of the two score states.
     */
    private final int[] stateCosts = new int[4];

    /**
     * The first 16x16 mode with the same prediction as each mode.
     */
    private final int[] dupI16 = new int[4];

    /**
     * The distortion of each evaluated 16x16 mode, before the flat-source doubling.
     */
    private final long[] rawDistoI16 = new long[4];

    /**
     * The spectral distortion of each evaluated 16x16 mode, before the flat-source doubling.
     */
    private final long[] rawSpectralI16 = new long[4];

    /**
     * The coefficient rate of each evaluated 16x16 mode.
     */
    private final long[] rateI16 = new long[4];

    /**
     * Whether each evaluated 16x16 mode has no nonzero AC level.
     */
    private final boolean[] flatI16 = new boolean[4];

    /**
     * The first subblock mode with the same prediction as each mode.
     */
    private final int[] dupI4 = new int[10];

    /**
     * The two 64-bit keys of each subblock mode's prediction block.
     */
    private final long[] predKeysI4 = new long[20];

    /**
     * The distortion of each evaluated subblock mode.
     */
    private final long[] distoI4 = new long[10];

    /**
     * The spectral distortion of each evaluated subblock mode.
     */
    private final long[] spectralI4 = new long[10];

    /**
     * The coefficient rate of each evaluated subblock mode, or -1 until computed.
     */
    private final long[] rateI4 = new long[10];

    /**
     * Whether each evaluated subblock mode has at most 3 nonzero AC levels.
     */
    private final boolean[] flatI4 = new boolean[10];

    /**
     * The levels of each evaluated subblock mode, 16 per mode.
     */
    private final short[] levelsI4 = new short[160];

    /**
     * The first chroma mode with the same prediction as each mode.
     */
    private final int[] dupUv = new int[4];

    /**
     * The distortion of each evaluated chroma mode.
     */
    private final long[] distoUv = new long[4];

    /**
     * The coefficient rate of each evaluated chroma mode, without the flat penalty.
     */
    private final long[] rateUv = new long[4];

    /**
     * Whether each evaluated chroma mode has at most 2 nonzero AC levels.
     */
    private final boolean[] flatUv = new boolean[4];

    /**
     * Creates an encoder for one picture.
     *
     * @param picture the 4:2:0 picture, at most 16383 pixels per side
     * @param settings the coding settings
     */
    public Vp8KeyFrameEncoder(YuvPicture picture, Vp8KeyFrameSettings settings) {
        var quality = settings.quality();
        var method = settings.method();
        var segments = settings.segments();
        var filterStrength = settings.filterStrength();
        var partitionCount = settings.partitionCount();
        this.picture = picture;
        this.quality = quality;
        this.method = method;
        this.filterStrength = filterStrength;
        this.configSharpness = settings.sharpness();
        this.snsStrength = settings.snsStrength();
        this.mbW = (picture.width() + 15) >> 4;
        this.mbH = (picture.height() + 15) >> 4;
        this.predsW = 4 * mbW + 1;
        this.predsBase = 1 + predsW;
        this.preds = new byte[predsW * (4 * mbH + 1)];
        this.nz = new int[mbW + 1];
        var count = mbW * mbH;
        this.mbType = new byte[count];
        this.mbUvMode = new byte[count];
        this.mbSkip = new byte[count];
        this.mbSegment = new byte[count];
        this.mbAlpha = new int[count];
        this.yTop = new byte[mbW * 16];
        this.uvTop = new byte[mbW * 16];
        this.topDerr = quality <= ERROR_DIFFUSION_QUALITY ? new int[mbW * 4] : null;
        this.numParts = partitionCount;
        this.profile = filterStrength > 0 ? 0 : 2;
        this.rdOptLevel = method >= 6 ? RD_OPT_TRELLIS_ALL : method >= 5 ? RD_OPT_TRELLIS
                : method >= 3 ? RD_OPT_BASIC : RD_OPT_NONE;
        this.maxI4HeaderBits = 256 * 16 * 16;
        this.mbHeaderLimit = 256L * 510 * 8 * 1024 / count;
        this.useTokens = rdOptLevel >= RD_OPT_BASIC;
        this.dqm = new SegmentInfo[NUM_MB_SEGMENTS];
        for (var i = 0; i < NUM_MB_SEGMENTS; i++) {
            dqm[i] = new SegmentInfo();
        }
        this.proba = new EncProba();
        this.numSegments = segments;
        this.updateMap = segments > 1;
        this.parts = new BoolEncoder[numParts];
        this.tokens = new TokenBuffer[numParts];
        this.it = new EncIterator(this);
    }

    /**
     * Creates a worker that codes macroblocks of the same picture with the shared state of
     * {@code parent} (the macroblock maps, the top contexts, the segment parameters and the
     * probabilities, which it only reads) and scratch state and token statistics of its own.
     *
     * @param parent the encoder to work for
     */
    private Vp8KeyFrameEncoder(Vp8KeyFrameEncoder parent) {
        this.picture = parent.picture;
        this.quality = parent.quality;
        this.method = parent.method;
        this.filterStrength = parent.filterStrength;
        this.configSharpness = parent.configSharpness;
        this.snsStrength = parent.snsStrength;
        this.mbW = parent.mbW;
        this.mbH = parent.mbH;
        this.predsW = parent.predsW;
        this.predsBase = parent.predsBase;
        this.preds = parent.preds;
        this.nz = parent.nz;
        this.mbType = parent.mbType;
        this.mbUvMode = parent.mbUvMode;
        this.mbSkip = parent.mbSkip;
        this.mbSegment = parent.mbSegment;
        this.mbAlpha = parent.mbAlpha;
        this.yTop = parent.yTop;
        this.uvTop = parent.uvTop;
        this.topDerr = parent.topDerr;
        this.numParts = parent.numParts;
        this.profile = parent.profile;
        this.rdOptLevel = parent.rdOptLevel;
        this.mbHeaderLimit = parent.mbHeaderLimit;
        this.useTokens = parent.useTokens;
        this.dqm = parent.dqm;
        this.proba = new EncProba(parent.proba);
        this.parts = parent.parts;
        this.tokens = parent.tokens;
        this.numSegments = parent.numSegments;
        this.updateMap = parent.updateMap;
        this.segmentHdrSize = parent.segmentHdrSize;
        this.filterSimple = parent.filterSimple;
        this.filterLevel = parent.filterLevel;
        this.filterSharpness = parent.filterSharpness;
        this.baseQuant = parent.baseQuant;
        this.alpha = parent.alpha;
        this.uvAlpha = parent.uvAlpha;
        this.dqY2Dc = parent.dqY2Dc;
        this.dqY1Dc = parent.dqY1Dc;
        this.dqY2Ac = parent.dqY2Ac;
        this.dqUvDc = parent.dqUvDc;
        this.dqUvAc = parent.dqUvAc;
        this.maxI4HeaderBits = parent.maxI4HeaderBits;
        this.reconstruction = parent.reconstruction;
        this.it = new EncIterator(this);
    }

    /**
     * Caps the number of threads {@link #encode()} runs on, the calling thread included. The
     * output does not depend on the cap.
     *
     * @param threads the thread cap, at least one
     */
    public void limitThreads(int threads) {
        threadLimit = Math.max(1, threads);
    }

    /**
     * Makes {@link #encode()} keep the unfiltered reconstruction of every macroblock, which a
     * decoder reproduces exactly when the loop filter is off.
     */
    public void captureReconstruction() {
        reconstruction = new byte[mbW * mbH * 384];
    }

    /**
     * Returns the reconstruction captured by the last {@link #encode()} call.
     *
     * @return the luma plane ({@code 16 * mbW} by {@code 16 * mbH}) followed by the U and V
     *         planes, or {@code null} when capture was not requested
     */
    public byte[] reconstruction() {
        return reconstruction;
    }

    /**
     * Copies the current macroblock's reconstruction into {@link #reconstruction}.
     */
    private void storeReconstruction() {
        var r = reconstruction;
        var out = it.yuvOut;
        var width = mbW * 16;
        var yBase = it.y * 16 * width + it.x * 16;
        for (var row = 0; row < 16; row++) {
            System.arraycopy(out, EncIterator.Y_OFF + row * EncIterator.BPS, r, yBase + row * width, 16);
        }
        var uvWidth = mbW * 8;
        var uBase = mbW * mbH * 256 + it.y * 8 * uvWidth + it.x * 8;
        var vBase = uBase + mbW * mbH * 64;
        for (var row = 0; row < 8; row++) {
            System.arraycopy(out, EncIterator.U_OFF + row * EncIterator.BPS, r, uBase + row * uvWidth, 8);
            System.arraycopy(out, EncIterator.V_OFF + row * EncIterator.BPS, r, vBase + row * uvWidth, 8);
        }
    }

    /**
     * Encodes the picture.
     *
     * @return the VP8 key frame: frame header, first partition, partition sizes, and token
     *         partitions
     * @throws MediaException.Unsupported if a partition exceeds the size the format allows
     */
    public byte[] encode() {
        analyze();
        var averageBytes = AVERAGE_BYTES_PER_MB[Math.min(7, baseQuantEstimate() >> 4)];
        var bytesPerPart = (int) Math.min(1 << 24, (long) mbW * mbH * averageBytes / numParts);
        for (var p = 0; p < numParts; p++) {
            parts[p] = new BoolEncoder(bytesPerPart);
            if (useTokens) {
                tokens[p] = new TokenBuffer(mbW * mbH * 64 / numParts);
            }
        }
        if (useTokens) {
            tokenLoop();
        } else {
            encLoop();
        }
        return write();
    }

    /**
     * Returns the frame quantizer the partition buffers are sized for; {@link #baseQuant} is not
     * known yet, so this is only a sizing hint.
     *
     * @return a quantizer index estimate
     */
    private int baseQuantEstimate() {
        return (int) (127 * (1 - quality / 100f));
    }

    /**
     * Collects the susceptibility of every macroblock and assigns segments, like libwebp's
     * {@code VP8EncAnalyze}.
     */
    private void analyze() {
        var doSegments = numSegments > 1 || method <= 1;
        if (!doSegments) {
            Arrays.fill(mbType, (byte) 1);
            Arrays.fill(mbUvMode, (byte) 0);
            Arrays.fill(mbSkip, (byte) 0);
            Arrays.fill(mbSegment, (byte) 0);
            Arrays.fill(mbAlpha, 0);
            dqm[0].alpha = 0;
            dqm[0].beta = 0;
            alpha = 0;
            uvAlpha = 0;
            return;
        }
        var alphas = new int[MAX_ALPHA + 1];
        var total = mbW * mbH;
        var bands = Math.min(threadLimit, ParallelBands.bands(mbH, (long) total * 384));
        var bandAlphas = new int[bands][];
        var bandSums = new long[bands][2];
        ParallelBands.run(bands, band -> {
            var worker = bands == 1 ? this : new Vp8KeyFrameEncoder(this);
            var histogram = new int[MAX_ALPHA + 1];
            bandAlphas[band] = histogram;
            worker.analyzeRows(ParallelBands.start(band, bands, mbH), ParallelBands.start(band + 1, bands, mbH),
                    histogram, bandSums[band]);
        });
        long alphaSum = 0;
        long uvAlphaSum = 0;
        for (var band = 0; band < bands; band++) {
            for (var i = 0; i <= MAX_ALPHA; i++) {
                alphas[i] += bandAlphas[band][i];
            }
            alphaSum += bandSums[band][0];
            uvAlphaSum += bandSums[band][1];
        }
        alpha = (int) (alphaSum / total);
        uvAlpha = (int) (uvAlphaSum / total);
        assignSegments(alphas);
    }

    /**
     * Analyzes the macroblocks of a range of rows, which read the source picture only.
     *
     * @param firstRow the first row
     * @param endRow   the row after the last
     * @param alphas   the susceptibility histogram to add to
     * @param sums     the sums of the luma and chroma susceptibilities to add to
     */
    private void analyzeRows(int firstRow, int endRow, int[] alphas, long[] sums) {
        var tmp32 = new byte[32];
        long alphaSum = 0;
        long uvAlphaSum = 0;
        for (var row = firstRow; row < endRow; row++) {
            it.seek(row, 0);
            for (var col = 0; col < mbW; col++) {
                it.importSamples(tmp32);
                it.setIntra16Mode(0);
                mbSkip[it.mb] = 0;
                mbSegment[it.mb] = 0;
                var bestAlpha = method <= 1 ? fastMbAnalyze() : analyzeBestIntra16Mode();
                var bestUvAlpha = analyzeBestUvMode();
                bestAlpha = (3 * bestAlpha + bestUvAlpha + 2) >> 2;
                bestAlpha = Math.clamp(MAX_ALPHA - bestAlpha, 0, MAX_ALPHA);
                alphas[bestAlpha]++;
                mbAlpha[it.mb] = bestAlpha;
                alphaSum += bestAlpha;
                uvAlphaSum += bestUvAlpha;
                it.advance();
            }
            ParallelBands.checkpoint();
        }
        sums[0] += alphaSum;
        sums[1] += uvAlphaSum;
    }

    /**
     * Returns the susceptibility of a transform-coefficient histogram, like libwebp's
     * {@code GetAlpha}.
     *
     * @param ref        the source samples
     * @param refOff     the index of the source block
     * @param pred       the prediction samples
     * @param predOff    the index of the prediction block
     * @param luma       whether to use the 16 luma blocks rather than the 8 chroma blocks
     * @return the susceptibility
     */
    private int histogramAlpha(byte[] ref, int refOff, byte[] pred, int predOff, boolean luma) {
        var dist = distribution;
        Arrays.fill(dist, 0);
        var out = tmp4;
        var count = luma ? 16 : 8;
        for (var j = 0; j < count; j++) {
            var off = luma ? EncIterator.SCAN[j] : EncIterator.SCAN_UV[j];
            dsp.fTransform(ref, refOff + off, pred, predOff + off, out, 0);
            for (var k = 0; k < 16; k++) {
                var v = Math.abs(out[k]) >> 3;
                dist[Math.min(v, MAX_COEFF_THRESH)]++;
            }
        }
        var maxValue = 0;
        var lastNonZero = 1;
        for (var k = 0; k <= MAX_COEFF_THRESH; k++) {
            var value = dist[k];
            if (value > 0) {
                if (value > maxValue) {
                    maxValue = value;
                }
                lastNonZero = k;
            }
        }
        return maxValue > 1 ? 2 * MAX_ALPHA * lastNonZero / maxValue : 0;
    }

    /**
     * Picks the better of the DC and TrueMotion 16x16 predictions by susceptibility, like
     * libwebp's {@code MBAnalyzeBestIntra16Mode}.
     *
     * @return the best susceptibility
     */
    private int analyzeBestIntra16Mode() {
        makeLuma16Preds();
        var bestAlpha = -1;
        var bestMode = 0;
        for (var mode = 0; mode < 2; mode++) {
            var a = histogramAlpha(it.yuvIn, EncIterator.Y_OFF, it.yuvP, EncPredict.I16_MODE_OFFSETS[mode], true);
            if (a > bestAlpha) {
                bestAlpha = a;
                bestMode = mode;
            }
        }
        it.setIntra16Mode(bestMode);
        return bestAlpha;
    }

    /**
     * Picks the chroma mode with the smallest susceptibility among DC and TrueMotion, like
     * libwebp's {@code MBAnalyzeBestUVMode}.
     *
     * @return the largest susceptibility
     */
    private int analyzeBestUvMode() {
        makeChroma8Preds();
        var bestAlpha = -1;
        var smallestAlpha = 0;
        var bestMode = 0;
        for (var mode = 0; mode < 2; mode++) {
            var a = histogramAlpha(it.yuvIn, EncIterator.U_OFF, it.yuvP, EncPredict.UV_MODE_OFFSETS[mode], false);
            if (a > bestAlpha) {
                bestAlpha = a;
            }
            if (mode == 0 || a < smallestAlpha) {
                smallestAlpha = a;
                bestMode = mode;
            }
        }
        mbUvMode[it.mb] = (byte) bestMode;
        return bestAlpha;
    }

    /**
     * Chooses between 16x16 DC and all-DC 4x4 prediction from the block means, like libwebp's
     * {@code FastMBAnalyze}.
     *
     * @return {@code 0}
     */
    private int fastMbAnalyze() {
        var q = (int) quality;
        var threshold = 8 + (17 - 8) * q / 100;
        long m = 0;
        long m2 = 0;
        var in = it.yuvIn;
        for (var k = 0; k < 16; k++) {
            var ref = EncIterator.Y_OFF + (k >> 2) * 4 * EncIterator.BPS + (k & 3) * 4;
            var dc = 0L;
            for (var y = 0; y < 4; y++) {
                for (var x = 0; x < 4; x++) {
                    dc += in[ref + x + y * EncIterator.BPS] & 0xFF;
                }
            }
            m += dc;
            m2 += dc * dc;
        }
        if (((threshold * m2) & 0xFFFFFFFFL) < ((m * m) & 0xFFFFFFFFL)) {
            it.setIntra16Mode(0);
        } else {
            it.setIntra4Mode(ALL_DC_MODES);
        }
        return 0;
    }

    /**
     * Assigns segments with a simplified k-means over the susceptibility histogram, like
     * libwebp's {@code AssignSegments}.
     *
     * @param alphas the number of macroblocks of each susceptibility
     */
    private void assignSegments(int[] alphas) {
        var nb = Math.min(numSegments, NUM_MB_SEGMENTS);
        var centers = new int[NUM_MB_SEGMENTS];
        var map = new int[MAX_ALPHA + 1];
        var accum = new int[NUM_MB_SEGMENTS];
        var distAccum = new int[NUM_MB_SEGMENTS];
        var weightedAverage = 0;
        var n = 0;
        while (n <= MAX_ALPHA && alphas[n] == 0) {
            n++;
        }
        var minA = n;
        n = MAX_ALPHA;
        while (n > minA && alphas[n] == 0) {
            n--;
        }
        var maxA = n;
        var rangeA = maxA - minA;
        for (int k = 0, m = 1; k < nb; k++, m += 2) {
            centers[k] = minA + (m * rangeA) / (2 * nb);
        }
        for (var k = 0; k < MAX_ITERS_K_MEANS; k++) {
            for (n = 0; n < nb; n++) {
                accum[n] = 0;
                distAccum[n] = 0;
            }
            n = 0;
            for (var a = minA; a <= maxA; a++) {
                if (alphas[a] != 0) {
                    while (n + 1 < nb && Math.abs(a - centers[n + 1]) < Math.abs(a - centers[n])) {
                        n++;
                    }
                    map[a] = n;
                    distAccum[n] += a * alphas[a];
                    accum[n] += alphas[a];
                }
            }
            var displaced = 0;
            weightedAverage = 0;
            var totalWeight = 0;
            for (n = 0; n < nb; n++) {
                if (accum[n] != 0) {
                    var newCenter = (distAccum[n] + accum[n] / 2) / accum[n];
                    displaced += Math.abs(centers[n] - newCenter);
                    centers[n] = newCenter;
                    weightedAverage += newCenter * accum[n];
                    totalWeight += accum[n];
                }
            }
            weightedAverage = (weightedAverage + totalWeight / 2) / totalWeight;
            if (displaced < 5) {
                break;
            }
        }
        for (var i = 0; i < mbW * mbH; i++) {
            var a = mbAlpha[i];
            mbSegment[i] = (byte) map[a];
            mbAlpha[i] = centers[map[a]];
        }
        var min = centers[0];
        var max = centers[0];
        if (nb > 1) {
            for (n = 0; n < nb; n++) {
                min = Math.min(min, centers[n]);
                max = Math.max(max, centers[n]);
            }
        }
        if (max == min) {
            max = min + 1;
        }
        for (n = 0; n < nb; n++) {
            var a = 255 * (centers[n] - weightedAverage) / (max - min);
            var b = 255 * (centers[n] - min) / (max - min);
            dqm[n].alpha = Math.clamp(a, -127, 127);
            dqm[n].beta = Math.clamp(b, 0, 255);
        }
    }

    /**
     * Maps a quality to a compression factor, like libwebp's {@code QualityToCompression}.
     *
     * @param c the quality divided by 100
     * @return the compression factor
     */
    private static double qualityToCompression(double c) {
        var linear = c < 0.75 ? c * (2.0 / 3.0) : 2.0 * c - 1.0;
        return Math.pow(linear, 1 / 3.0);
    }

    /**
     * Returns the loop filter level that smooths an edge step.
     *
     * @param sharpness the loop filter sharpness, from 0 to 7
     * @param delta the step
     * @return the level
     */
    private static int filterStrengthFromDelta(int sharpness, int delta) {
        var pos = delta < 64 ? delta : 63;
        return Vp8EncTables.LEVELS_FROM_DELTA[sharpness * 64 + pos];
    }

    /**
     * Sets the quantizers, filter strengths, and matrices of every segment, like libwebp's
     * {@code VP8SetSegmentParams}.
     *
     * @param q the quality
     */
    private void setSegmentParams(float q) {
        var amp = 0.9 * snsStrength / 100.0 / 128.0;
        var cBase = qualityToCompression(q / 100.0);
        for (var i = 0; i < numSegments; i++) {
            var expn = 1.0 - amp * dqm[i].alpha;
            var c = Math.pow(cBase, expn);
            var qi = (int) (127.0 * (1.0 - c));
            dqm[i].quant = Math.clamp(qi, 0, 127);
        }
        baseQuant = dqm[0].quant;
        for (var i = numSegments; i < NUM_MB_SEGMENTS; i++) {
            dqm[i].quant = baseQuant;
        }
        var dqUvAcLocal = (uvAlpha - 64) * (6 - (-4)) / (100 - 30);
        dqUvAcLocal = dqUvAcLocal * snsStrength / 100;
        dqUvAcLocal = Math.clamp(dqUvAcLocal, -4, 6);
        var dqUvDcLocal = -4 * snsStrength / 100;
        dqUvDcLocal = Math.clamp(dqUvDcLocal, -15, 15);
        dqY1Dc = 0;
        dqY2Dc = 0;
        dqY2Ac = 0;
        dqUvDc = dqUvDcLocal;
        dqUvAc = dqUvAcLocal;
        setupFilterStrength();
        if (numSegments > 1) {
            simplifySegments();
        }
        setupMatrices();
    }

    /**
     * Sets the loop filter strength of every segment, like libwebp's
     * {@code SetupFilterStrength}.
     */
    private void setupFilterStrength() {
        var level0 = 5 * filterStrength;
        for (var i = 0; i < NUM_MB_SEGMENTS; i++) {
            var m = dqm[i];
            var qstep = Vp8Tables.AC_QLOOKUP[Math.clamp(m.quant, 0, 127)] >> 2;
            var baseStrength = filterStrengthFromDelta(filterSharpness, qstep);
            var f = baseStrength * level0 / (256 + m.beta);
            m.fstrength = f < 2 ? 0 : Math.min(f, 63);
        }
        filterLevel = dqm[0].fstrength;
        filterSimple = false;
        filterSharpness = configSharpness;
    }

    /**
     * Merges segments with identical quantizer and filter strength, like libwebp's
     * {@code SimplifySegments}.
     */
    private void simplifySegments() {
        var map = new int[]{0, 1, 2, 3};
        var count = Math.min(numSegments, NUM_MB_SEGMENTS);
        var finalCount = 1;
        for (var s1 = 1; s1 < count; s1++) {
            var found = false;
            int s2;
            for (s2 = 0; s2 < finalCount; s2++) {
                if (dqm[s1].quant == dqm[s2].quant && dqm[s1].fstrength == dqm[s2].fstrength) {
                    found = true;
                    break;
                }
            }
            map[s1] = s2;
            if (!found) {
                if (finalCount != s1) {
                    dqm[finalCount].copyFrom(dqm[s1]);
                }
                finalCount++;
            }
        }
        if (finalCount < count) {
            for (var i = 0; i < mbW * mbH; i++) {
                mbSegment[i] = (byte) map[mbSegment[i]];
            }
            numSegments = finalCount;
            for (var i = finalCount; i < count; i++) {
                dqm[i].copyFrom(dqm[finalCount - 1]);
            }
        }
    }

    /**
     * Fills a quantization matrix from its DC and AC steps, like libwebp's
     * {@code ExpandMatrix}.
     *
     * @param m    the matrix with {@code q[0]} and {@code q[1]} set
     * @param type {@code 0} luma, {@code 1} Y2, {@code 2} chroma
     * @return the average step
     */
    private static int expandMatrix(QuantMatrix m, int type) {
        for (var i = 0; i < 2; i++) {
            var bias = BIAS_MATRICES[type * 2 + i];
            m.iq[i] = (1 << QuantMatrix.QFIX) / m.q[i];
            m.bias[i] = bias << (QuantMatrix.QFIX - 8);
            m.zthresh[i] = ((1 << QuantMatrix.QFIX) - 1 - m.bias[i]) / m.iq[i];
        }
        for (var i = 2; i < 16; i++) {
            m.q[i] = m.q[1];
            m.iq[i] = m.iq[1];
            m.bias[i] = m.bias[1];
            m.zthresh[i] = m.zthresh[1];
        }
        var sum = 0;
        for (var i = 0; i < 16; i++) {
            m.sharpen[i] = type == 0 ? (FREQ_SHARPENING[i] * m.q[i]) >> 11 : 0;
            sum += m.q[i];
        }
        return (sum + 8) >> 4;
    }

    /**
     * Computes the quantization matrices and rate weights of every segment, like libwebp's
     * {@code SetupMatrices}.
     */
    private void setupMatrices() {
        var tlambdaScale = method >= 4 ? snsStrength : 0;
        for (var i = 0; i < numSegments; i++) {
            var m = dqm[i];
            var q = m.quant;
            m.y1.q[0] = Vp8Tables.DC_QLOOKUP[Math.clamp(q + dqY1Dc, 0, 127)];
            m.y1.q[1] = Vp8Tables.AC_QLOOKUP[Math.clamp(q, 0, 127)];
            m.y2.q[0] = Vp8Tables.DC_QLOOKUP[Math.clamp(q + dqY2Dc, 0, 127)] * 2;
            m.y2.q[1] = Vp8EncTables.AC_TABLE2[Math.clamp(q + dqY2Ac, 0, 127)];
            m.uv.q[0] = Vp8Tables.DC_QLOOKUP[Math.clamp(q + dqUvDc, 0, 117)];
            m.uv.q[1] = Vp8Tables.AC_QLOOKUP[Math.clamp(q + dqUvAc, 0, 127)];
            var qI4 = expandMatrix(m.y1, 0);
            var qI16 = expandMatrix(m.y2, 1);
            var qUv = expandMatrix(m.uv, 2);
            m.lambdaI4 = Math.max(1, (3 * qI4 * qI4) >> 7);
            m.lambdaI16 = Math.max(1, 3 * qI16 * qI16);
            m.lambdaUv = Math.max(1, (3 * qUv * qUv) >> 6);
            m.lambdaMode = Math.max(1, (qI4 * qI4) >> 7);
            m.lambdaTrellisI4 = Math.max(1, (7 * qI4 * qI4) >> 3);
            m.lambdaTrellisI16 = Math.max(1, (qI16 * qI16) >> 2);
            m.tlambda = Math.max(1, (tlambdaScale * qI4) >> 5);
            m.minDisto = 20 * m.y1.q[0];
            m.maxEdge = 0;
            Arrays.fill(maxEdgeLocal, 0);
            m.i4Penalty = 1000L * qI4 * qI4;
        }
    }

    /**
     * Computes the segment map probabilities, like libwebp's {@code SetSegmentProbas}.
     */
    private void setSegmentProbas() {
        var p = new int[NUM_MB_SEGMENTS];
        for (var i = 0; i < mbW * mbH; i++) {
            p[mbSegment[i]]++;
        }
        if (numSegments > 1) {
            var probas = proba.segments;
            probas[0] = getProba(p[0] + p[1], p[2] + p[3]);
            probas[1] = getProba(p[0], p[1]);
            probas[2] = getProba(p[2], p[3]);
            updateMap = probas[0] != 255 || probas[1] != 255 || probas[2] != 255;
            if (!updateMap) {
                Arrays.fill(mbSegment, (byte) 0);
            }
            segmentHdrSize = p[0] * (EncProba.bitCost(0, probas[0]) + EncProba.bitCost(0, probas[1]))
                    + p[1] * (EncProba.bitCost(0, probas[0]) + EncProba.bitCost(1, probas[1]))
                    + p[2] * (EncProba.bitCost(1, probas[0]) + EncProba.bitCost(0, probas[2]))
                    + p[3] * (EncProba.bitCost(1, probas[0]) + EncProba.bitCost(1, probas[2]));
        } else {
            updateMap = false;
            segmentHdrSize = 0;
        }
    }

    /**
     * Returns the rounded probability of the first branch.
     *
     * @param a the count of the first branch
     * @param b the count of the second branch
     * @return the probability, 255 when both are zero
     */
    private static int getProba(int a, int b) {
        var total = a + b;
        return total == 0 ? 255 : (255 * a + total / 2) / total;
    }

    /**
     * Prepares the segment parameters and statistics of a coding pass, like libwebp's
     * {@code SetLoopParams}.
     *
     * @param q the quality
     */
    private void setLoopParams(float q) {
        setSegmentParams(Math.clamp(q, 0f, 100f));
        setSegmentProbas();
        proba.calculateLevelCosts();
        proba.nbSkip = 0;
    }

    /**
     * Codes every macroblock with token buffering (methods 3 to 6), like libwebp's
     * {@code VP8EncTokenLoop}.
     */
    private void tokenLoop() {
        var maxCount = Math.max(96, (mbW * mbH) >> 3);
        var participants = tokenParticipants();
        if (participants > 1) {
            tokenLoopParallel(maxCount, participants);
            return;
        }
        var passesLeft = 1;
        while (passesLeft-- > 0) {
            long sizeP0 = 0;
            var cnt = maxCount;
            it.init();
            setLoopParams(quality);
            Arrays.fill(proba.stats, 0);
            for (var t : tokens) {
                t.clear();
            }
            do {
                it.importSamples(null);
                if (--cnt < 0) {
                    proba.finalizeTokenProbas();
                    proba.calculateLevelCosts();
                    cnt = maxCount;
                }
                decimate(info, rdOptLevel);
                recordTokens(info, tokens[it.part]);
                sizeP0 += info.h;
                if (reconstruction != null) {
                    storeReconstruction();
                }
                it.saveBoundary();
            } while (it.next());
            sizeP0 += segmentHdrSize;
            if (maxI4HeaderBits > 0 && sizeP0 > PARTITION0_SIZE_LIMIT) {
                passesLeft++;
                maxI4HeaderBits >>= 1;
            }
        }
        proba.finalizeTokenProbas();
        for (var p = 0; p < numParts; p++) {
            tokens[p].emit(parts[p], proba.coeffs);
        }
        postLoopFinalize();
    }

    /**
     * Returns how many threads the token loop runs on.
     *
     * @return the participant count, one for the sequential loop
     */
    private int tokenParticipants() {
        if (!ParallelBands.enabled() || (long) mbW * mbH < 512) {
            return 1;
        }
        return Math.max(1, Math.min(Math.min(threadLimit, 1 + ParallelBands.available()), mbH));
    }

    /**
     * Codes every macroblock like {@link #tokenLoop()} with several threads, with the same output.
     *
     * <p>The loop is cut where the sequential loop refreshes the token probabilities, every
     * {@code maxCount} macroblocks; between two cuts the rows run in a wavefront (a macroblock
     * waits for the row above to code the macroblock above and to its right), each into a token
     * buffer of its own, and at a cut the token statistics are replayed in raster order, which the
     * saturating counters need to match the sequential loop.
     *
     * @param maxCount     the macroblocks between probability refreshes
     * @param participants the number of threads
     */
    private void tokenLoopParallel(int maxCount, int participants) {
        var total = mbW * mbH;
        var wave = new RowWavefront(mbH);
        var rowTokens = new TokenBuffer[mbH];
        var marks = new int[mbH];
        var carry = new EncIterator(this);
        var passesLeft = 1;
        while (passesLeft-- > 0) {
            it.init();
            setLoopParams(quality);
            Arrays.fill(proba.stats, 0);
            Arrays.fill(marks, 0);
            for (var row = 0; row < mbH; row++) {
                if (rowTokens[row] == null) {
                    rowTokens[row] = new TokenBuffer(mbW * 48);
                } else {
                    rowTokens[row].clear();
                }
            }
            var pool = new Vp8KeyFrameEncoder[participants];
            for (var i = 0; i < participants; i++) {
                pool[i] = new Vp8KeyFrameEncoder(this);
            }
            workers = pool;
            var start = 0;
            var end = Math.min(total, maxCount);
            while (start < total) {
                var firstRow = start / mbW;
                var lastRow = (end - 1) / mbW;
                var firstCol = start - firstRow * mbW;
                var endCol = end - lastRow * mbW;
                for (var row = firstRow; row <= lastRow; row++) {
                    wave.reset(row, row == firstRow ? firstCol : 0);
                }
                wave.run(participants, firstRow, lastRow + 1, (participant, row) ->
                        pool[participant].codeRow(wave, row, row == firstRow ? firstCol : 0,
                                row == lastRow ? endCol : mbW, row > firstRow, rowTokens[row], carry));
                for (var row = firstRow; row <= lastRow; row++) {
                    var buffer = rowTokens[row];
                    proba.replayTokens(buffer.array(), marks[row], buffer.size());
                    marks[row] = buffer.size();
                }
                if (end < total) {
                    proba.finalizeTokenProbas();
                    proba.calculateLevelCosts();
                }
                start = end;
                end = Math.min(total, end + maxCount + 1);
            }
            long sizeP0 = segmentHdrSize;
            for (var worker : pool) {
                sizeP0 += worker.workerHeaderCost;
            }
            if (maxI4HeaderBits > 0 && sizeP0 > PARTITION0_SIZE_LIMIT) {
                passesLeft++;
                maxI4HeaderBits >>= 1;
            }
        }
        proba.finalizeTokenProbas();
        for (var row = 0; row < mbH; row++) {
            rowTokens[row].emit(parts[row & (numParts - 1)], proba.coeffs);
        }
        postLoopFinalize();
    }

    /**
     * Codes the macroblocks of a row segment on a worker.
     *
     * @param wave   the wavefront
     * @param row    the macroblock row
     * @param first  the first column
     * @param end    the column after the last
     * @param waits  whether the macroblocks wait for the row above
     * @param buffer the token buffer of the row
     * @param carry  the holder of the left context across a cut inside the row
     */
    private void codeRow(RowWavefront wave, int row, int first, int end, boolean waits,
                         TokenBuffer buffer, EncIterator carry) {
        it.seek(row, first);
        if (first > 0) {
            it.loadLeft(carry);
        }
        long header = 0;
        for (var col = first; col < end; col++) {
            if (waits) {
                wave.await(row - 1, Math.min(col + 2, mbW));
            }
            it.importSamples(null);
            decimate(info, rdOptLevel);
            recordTokens(info, buffer);
            header += info.h;
            if (reconstruction != null) {
                storeReconstruction();
            }
            it.saveBoundary();
            it.advance();
            wave.publish(row, col + 1);
        }
        if (end < mbW) {
            it.saveLeft(carry);
        }
        workerHeaderCost += header;
    }

    /**
     * Collects the token statistics of a first pass, like libwebp's {@code StatLoop}.
     *
     * <p>When the pass reports a zero first-partition cost, which happens with distortion-only
     * decisions (methods 0 to 2) and a single segment, the probabilities keep their defaults and
     * the skip flag is not coded.
     */
    private void statLoop() {
        var fastProbe = method == 0 || method == 3;
        var rdOpt = method >= 3 ? RD_OPT_BASIC : RD_OPT_NONE;
        var nbMbs = mbW * mbH;
        if (fastProbe) {
            if (method == 3) {
                nbMbs = nbMbs > 200 ? nbMbs >> 1 : 100;
            } else {
                nbMbs = nbMbs > 200 ? nbMbs >> 2 : 50;
            }
        }
        Arrays.fill(proba.stats, 0);
        var passesLeft = 1;
        while (passesLeft-- > 0) {
            var sizeP0 = oneStatPass(rdOpt, nbMbs);
            if (sizeP0 == 0) {
                // libwebp's StatLoop returns here without finalizing the probabilities, which
                // happens when distortion-only decisions leave no header cost and one segment
                return;
            }
            if (maxI4HeaderBits > 0 && sizeP0 > PARTITION0_SIZE_LIMIT) {
                passesLeft++;
                maxI4HeaderBits >>= 1;
            }
        }
        proba.finalizeSkipProba(mbW * mbH);
        proba.finalizeTokenProbas();
        proba.calculateLevelCosts();
    }

    /**
     * Runs one statistics pass over up to {@code nbMbs} macroblocks, like libwebp's
     * {@code OneStatPass}.
     *
     * @param rdOpt the rate-distortion level
     * @param nbMbs the number of macroblocks to visit
     * @return the first partition cost, in 1/256 bit
     */
    private long oneStatPass(int rdOpt, int nbMbs) {
        long sizeP0 = 0;
        it.init();
        setLoopParams(quality);
        do {
            it.importSamples(null);
            if (decimate(info, rdOpt)) {
                proba.nbSkip++;
            }
            recordResiduals(info);
            sizeP0 += info.h;
            it.saveBoundary();
        } while (it.next() && --nbMbs > 0);
        return sizeP0 + segmentHdrSize;
    }

    /**
     * Codes every macroblock directly (methods 0 to 2), like libwebp's {@code VP8EncLoop}.
     */
    private void encLoop() {
        statLoop();
        it.init();
        do {
            it.importSamples(null);
            var dontUseSkip = !proba.useSkipProba;
            if (!decimate(info, rdOptLevel) || dontUseSkip) {
                codeResiduals(parts[it.part], info);
            } else {
                if (mbType[it.mb] == 1) {
                    nz[it.nz] = 0;
                    it.leftNz[8] = 0;
                } else {
                    nz[it.nz] &= 1 << 24;
                }
            }
            if (reconstruction != null) {
                storeReconstruction();
            }
            it.saveBoundary();
        } while (it.next());
        postLoopFinalize();
    }

    /**
     * Pads the token partitions and raises the filter strength of blocky segments, like
     * libwebp's {@code PostLoopFinalize}.
     */
    private void postLoopFinalize() {
        for (var p : parts) {
            p.finish();
        }
        if (filterStrength > 0) {
            var maxLevel = 0;
            for (var s = 0; s < NUM_MB_SEGMENTS; s++) {
                var m = dqm[s];
                var maxEdge = Math.max(m.maxEdge, maxEdgeLocal[s]);
                for (var w : workers) {
                    maxEdge = Math.max(maxEdge, w.maxEdgeLocal[s]);
                }
                m.maxEdge = maxEdge;
                var delta = (m.maxEdge * m.y2.q[1]) >> 3;
                var level = filterStrengthFromDelta(filterSharpness, delta);
                if (level > m.fstrength) {
                    m.fstrength = level;
                }
                maxLevel = Math.max(maxLevel, m.fstrength);
            }
            filterLevel = maxLevel;
        }
    }

    /**
     * Forms the 16x16 luma predictions of the current macroblock.
     */
    private void makeLuma16Preds() {
        EncPredict.luma16(it.yuvP, it.x != 0 ? it.yLeft : null, 1, it.y != 0 ? it.yTop : null, it.yTopOff);
    }

    /**
     * Forms the chroma predictions of the current macroblock.
     */
    private void makeChroma8Preds() {
        var left = it.x != 0;
        EncPredict.chroma8(it.yuvP, left ? it.uLeft : null, left ? it.vLeft : null, 1,
                it.y != 0 ? it.uvTop : null, it.uvTopOff);
    }

    /**
     * Chooses the modes of the current macroblock and quantizes its coefficients, like libwebp's
     * {@code VP8Decimate}.
     *
     * @param rd    the decision to fill
     * @param rdOpt the rate-distortion level
     * @return whether the macroblock has no coefficients
     */
    private boolean decimate(ModeScore rd, int rdOpt) {
        rd.init();
        makeLuma16Preds();
        makeChroma8Preds();
        if (rdOpt > RD_OPT_NONE) {
            it.doTrellis = rdOpt >= RD_OPT_TRELLIS_ALL;
            pickBestIntra16(rd);
            if (method >= 2) {
                pickBestIntra4(rd);
            }
            pickBestUv(rd);
            if (rdOpt == RD_OPT_TRELLIS) {
                it.doTrellis = true;
                simpleQuantize(rd);
            }
        } else {
            refineUsingDistortion(method >= 2, method >= 1, rd);
        }
        var skipped = rd.nz == 0;
        mbSkip[it.mb] = (byte) (skipped ? 1 : 0);
        return skipped;
    }

    /**
     * Returns the parameters of the current macroblock's segment.
     *
     * @return the segment
     */
    private SegmentInfo segment() {
        return dqm[mbSegment[it.mb]];
    }

    /**
     * Returns whether a 16x16 source block is uniform.
     *
     * @param src the samples
     * @param off the index of the block
     * @return {@code true} when every sample equals the first
     */
    private static boolean isFlatSource16(byte[] src, int off) {
        var v = src[off];
        for (var i = 0; i < 16; i++) {
            var row = off + i * EncIterator.BPS;
            for (var x = 0; x < 16; x++) {
                if (src[row + x] != v) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Returns whether blocks have at most {@code thresh} nonzero AC levels in total.
     *
     * @param levels    the levels in scan order
     * @param off       the index of the first block
     * @param numBlocks the number of blocks
     * @param thresh    the limit
     * @return {@code true} when the blocks are flat
     */
    private static boolean isFlat(short[] levels, int off, int numBlocks, int thresh) {
        return Vp8EncDsp.INSTANCE.countNonZeroAc(levels, off, numBlocks, thresh) <= thresh;
    }

    /**
     * Returns {@code (a * b + 128) >> 8}.
     *
     * @param a the first factor
     * @param b the second factor
     * @return the rounded product
     */
    private static int mult8b(int a, int b) {
        return (a * b + 128) >> 8;
    }

    /**
     * Transforms, quantizes, and reconstructs the luma of the current macroblock with a 16x16
     * mode, like libwebp's {@code ReconstructIntra16}.
     *
     * @param rd     the decision receiving the levels
     * @param out    the reconstruction buffer
     * @param outOff the index of the luma block in {@code out}
     * @param mode   the 16x16 mode
     * @return the nonzero flags
     */
    private int reconstructIntra16(ModeScore rd, byte[] out, int outOff, int mode) {
        var ref = it.yuvP;
        var refOff = EncPredict.I16_MODE_OFFSETS[mode];
        var src = it.yuvIn;
        var m = segment();
        var tmp = tmp16;
        var nzBits = 0;
        for (var n = 0; n < 16; n += 2) {
            var off = EncIterator.SCAN[n];
            dsp.fTransform(src, EncIterator.Y_OFF + off, ref, refOff + off, tmp, n * 16);
            dsp.fTransform(src, EncIterator.Y_OFF + off + 4, ref, refOff + off + 4, tmp, (n + 1) * 16);
        }
        fTransformWht(tmp, dcTmp);
        nzBits |= dsp.quantizeBlock(dcTmp, 0, rd.yDcLevels, 0, m.y2) << 24;
        if (it.doTrellis) {
            it.nzToBytes();
            for (int y = 0, n = 0; y < 4; y++) {
                for (var x = 0; x < 4; x++, n++) {
                    var ctx = it.topNz[x] + it.leftNz[y];
                    var nonZero = trellisQuantizeBlock(tmp, n * 16, rd.yAcLevels, n * 16, ctx, 0, m.y1, m.lambdaTrellisI16);
                    it.topNz[x] = nonZero;
                    it.leftNz[y] = nonZero;
                    rd.yAcLevels[n * 16] = 0;
                    nzBits |= nonZero << n;
                }
            }
        } else {
            for (var n = 0; n < 16; n += 2) {
                tmp[n * 16] = 0;
                tmp[(n + 1) * 16] = 0;
                nzBits |= dsp.quantizeBlock(tmp, n * 16, rd.yAcLevels, n * 16, m.y1) << n;
                nzBits |= dsp.quantizeBlock(tmp, (n + 1) * 16, rd.yAcLevels, (n + 1) * 16, m.y1) << (n + 1);
            }
        }
        inverseWht(dcTmp, tmp);
        for (var n = 0; n < 16; n++) {
            var off = EncIterator.SCAN[n];
            dsp.iTransform(ref, refOff + off, tmp, n * 16, out, outOff + off);
        }
        return nzBits;
    }

    /**
     * Transforms, quantizes, and reconstructs one luma subblock with a 4x4 mode, like libwebp's
     * {@code ReconstructIntra4}.
     *
     * @param levels    the levels destination
     * @param levelsOff the index of the first level
     * @param src       the source samples
     * @param srcOff    the index of the source subblock
     * @param out       the reconstruction buffer
     * @param outOff    the index of the reconstructed subblock
     * @param mode      the subblock mode, in libwebp order
     * @return {@code 1} when a level is nonzero, {@code 0} otherwise
     */
    private int reconstructIntra4(short[] levels, int levelsOff, byte[] src, int srcOff, byte[] out, int outOff, int mode) {
        var ref = it.yuvP;
        var refOff = EncPredict.I4_MODE_OFFSETS[mode];
        var m = segment();
        var tmp = tmp4;
        dsp.fTransform(src, srcOff, ref, refOff, tmp, 0);
        int nonZero;
        if (it.doTrellis) {
            var ctx = it.topNz[it.i4 & 3] + it.leftNz[it.i4 >> 2];
            nonZero = trellisQuantizeBlock(tmp, 0, levels, levelsOff, ctx, 3, m.y1, m.lambdaTrellisI4);
        } else {
            nonZero = dsp.quantizeBlock(tmp, 0, levels, levelsOff, m.y1);
        }
        dsp.iTransform(ref, refOff, tmp, 0, out, outOff);
        return nonZero;
    }

    /**
     * Quantizes one chroma DC coefficient, returning half its quantization error, like
     * libwebp's {@code QuantizeSingle}.
     *
     * @param tmp the coefficients
     * @param idx the index of the DC coefficient, replaced with its dequantized value
     * @param mtx the chroma matrix
     * @return the quantization error divided by 2
     */
    private static int quantizeSingle(short[] tmp, int idx, QuantMatrix mtx) {
        int v = tmp[idx];
        var sign = v < 0;
        if (sign) {
            v = -v;
        }
        if (v > mtx.zthresh[0]) {
            var qv = QuantMatrix.quantDiv(v, mtx.iq[0], mtx.bias[0]) * mtx.q[0];
            var err = v - qv;
            tmp[idx] = (short) (sign ? -qv : qv);
            return (sign ? -err : err) >> 1;
        }
        tmp[idx] = 0;
        return (sign ? -v : v) >> 1;
    }

    /**
     * Diffuses the chroma DC quantization error to the neighboring blocks, like libwebp's
     * {@code CorrectDCValues}.
     *
     * @param mtx the chroma matrix
     * @param tmp the 8 chroma coefficient blocks
     * @param rd  the decision receiving the errors
     */
    private void correctDcValues(QuantMatrix mtx, short[] tmp, ModeScore rd) {
        for (var ch = 0; ch <= 1; ch++) {
            var top = (it.x * 2 + ch) * 2;
            var left = ch * 2;
            var c = ch * 4 * 16;
            var td = topDerr;
            var ld = it.leftDerr;
            tmp[c] = (short) (tmp[c] + ((7 * td[top] + 8 * ld[left]) >> 3));
            var err0 = quantizeSingle(tmp, c, mtx);
            tmp[c + 16] = (short) (tmp[c + 16] + ((7 * td[top + 1] + 8 * err0) >> 3));
            var err1 = quantizeSingle(tmp, c + 16, mtx);
            tmp[c + 32] = (short) (tmp[c + 32] + ((7 * err0 + 8 * ld[left + 1]) >> 3));
            var err2 = quantizeSingle(tmp, c + 32, mtx);
            tmp[c + 48] = (short) (tmp[c + 48] + ((7 * err1 + 8 * err2) >> 3));
            var err3 = quantizeSingle(tmp, c + 48, mtx);
            rd.derr[ch * 3] = (byte) err1;
            rd.derr[ch * 3 + 1] = (byte) err2;
            rd.derr[ch * 3 + 2] = (byte) err3;
        }
    }

    /**
     * Stores the chroma DC errors of the chosen decision for the next macroblocks, like
     * libwebp's {@code StoreDiffusionErrors}.
     *
     * @param rd the chosen decision
     */
    private void storeDiffusionErrors(ModeScore rd) {
        for (var ch = 0; ch <= 1; ch++) {
            var top = (it.x * 2 + ch) * 2;
            var left = ch * 2;
            var ld = it.leftDerr;
            ld[left] = rd.derr[ch * 3];
            ld[left + 1] = (byte) ((3 * rd.derr[ch * 3 + 2]) >> 2);
            topDerr[top] = rd.derr[ch * 3 + 1];
            topDerr[top + 1] = (byte) (rd.derr[ch * 3 + 2] - ld[left + 1]);
        }
    }

    /**
     * Transforms, quantizes, and reconstructs the chroma of the current macroblock, like
     * libwebp's {@code ReconstructUV}.
     *
     * @param rd     the decision receiving the levels
     * @param out    the reconstruction buffer
     * @param outOff the index of the U block in {@code out}
     * @param mode   the chroma mode
     * @return the nonzero flags, shifted to bits 16 to 23
     */
    private int reconstructUv(ModeScore rd, byte[] out, int outOff, int mode) {
        var ref = it.yuvP;
        var refOff = EncPredict.UV_MODE_OFFSETS[mode];
        var src = it.yuvIn;
        var m = segment();
        var tmp = tmpUv;
        var nzBits = 0;
        for (var n = 0; n < 8; n++) {
            var off = EncIterator.SCAN_UV[n];
            dsp.fTransform(src, EncIterator.U_OFF + off, ref, refOff + off, tmp, n * 16);
        }
        if (topDerr != null) {
            correctDcValues(m.uv, tmp, rd);
        }
        for (var n = 0; n < 8; n++) {
            nzBits |= dsp.quantizeBlock(tmp, n * 16, rd.uvLevels, n * 16, m.uv) << n;
        }
        for (var n = 0; n < 8; n++) {
            var off = EncIterator.SCAN_UV[n];
            dsp.iTransform(ref, refOff + off, tmp, n * 16, out, outOff + off);
        }
        return nzBits << 16;
    }

    /**
     * Returns the cost of the current macroblock's 16x16 luma levels, like libwebp's
     * {@code VP8GetCostLuma16}.
     *
     * @param rd the decision
     * @return the cost in 1/256 bit
     */
    private int costLuma16(ModeScore rd) {
        it.nzToBytes();
        res.init(0, 1);
        res.setCoeffs(rd.yDcLevels, 0);
        var r = proba.residualCost(it.topNz[8] + it.leftNz[8], res);
        res.init(1, 0);
        for (var y = 0; y < 4; y++) {
            for (var x = 0; x < 4; x++) {
                var ctx = it.topNz[x] + it.leftNz[y];
                res.setCoeffs(rd.yAcLevels, (x + y * 4) * 16);
                r += proba.residualCost(ctx, res);
                var flag = res.last >= 0 ? 1 : 0;
                it.topNz[x] = flag;
                it.leftNz[y] = flag;
            }
        }
        return r;
    }

    /**
     * Returns the cost of one subblock's levels, like libwebp's {@code VP8GetCostLuma4}.
     *
     * @param levels the quantized levels
     * @param offset the index of the subblock's first level
     * @return the cost in 1/256 bit
     */
    private int costLuma4(short[] levels, int offset) {
        res.init(0, 3);
        var ctx = it.topNz[it.i4 & 3] + it.leftNz[it.i4 >> 2];
        res.setCoeffs(levels, offset);
        return proba.residualCost(ctx, res);
    }

    /**
     * Returns the cost of the current macroblock's chroma levels, like libwebp's
     * {@code VP8GetCostUV}.
     *
     * @param rd the decision
     * @return the cost in 1/256 bit
     */
    private int costUv(ModeScore rd) {
        it.nzToBytes();
        res.init(0, 2);
        var r = 0;
        for (var ch = 0; ch <= 2; ch += 2) {
            for (var y = 0; y < 2; y++) {
                for (var x = 0; x < 2; x++) {
                    var ctx = it.topNz[4 + ch + x] + it.leftNz[4 + ch + y];
                    res.setCoeffs(rd.uvLevels, (ch * 2 + x + y * 2) * 16);
                    r += proba.residualCost(ctx, res);
                    var flag = res.last >= 0 ? 1 : 0;
                    it.topNz[4 + ch + x] = flag;
                    it.leftNz[4 + ch + y] = flag;
                }
            }
        }
        return r;
    }

    /**
     * Chooses the best 16x16 luma mode by rate-distortion score, like libwebp's
     * {@code PickBestIntra16}.
     *
     * @implNote This implementation evaluates a mode whose prediction repeats an earlier mode's
     * only when it can win: its levels, reconstruction, distortion and rate equal the earlier
     * mode's, so its score follows from the recorded ones and its own header cost; the rare mode
     * that would become the best is evaluated in full, so the decision and every buffer match
     * evaluating each mode.
     *
     * @param rd the decision receiving the best mode
     */
    private void pickBestIntra16(ModeScore rd) {
        var m = segment();
        var lambda = m.lambdaI16;
        var tlambda = m.tlambda;
        var src = it.yuvIn;
        var cur = scoreTmp;
        var best = rd;
        var flat = isFlatSource16(src, EncIterator.Y_OFF);
        var dups = findDuplicatePredictions(EncPredict.I16_MODE_OFFSETS, 16, 16, dupI16);
        rd.modeI16 = -1;
        for (var mode = 0; mode < 4; mode++) {
            var same = dups ? dupI16[mode] : mode;
            if (same != mode) {
                var d = rawDistoI16[same];
                var sd = rawSpectralI16[same];
                var stillFlat = flat && flatI16[same];
                if (stillFlat) {
                    d *= 2;
                    sd *= 2;
                }
                var score = (rateI16[same] + Vp8EncTables.FIXED_COSTS_I16[mode]) * lambda + 256 * (d + sd);
                if (score >= best.score) {
                    if (flat) {
                        flat = flatI16[same];
                    }
                    continue;
                }
            }
            var tmpDst = it.yuvOut2;
            cur.modeI16 = mode;
            cur.nz = reconstructIntra16(cur, tmpDst, EncIterator.Y_OFF, mode);
            cur.d = dsp.sse(src, EncIterator.Y_OFF, tmpDst, EncIterator.Y_OFF, 16, 16);
            cur.sd = tlambda != 0 ? mult8b(tlambda, dsp.tDisto16x16(src, EncIterator.Y_OFF, tmpDst, EncIterator.Y_OFF, WEIGHT_Y)) : 0;
            cur.h = Vp8EncTables.FIXED_COSTS_I16[mode];
            cur.r = costLuma16(cur);
            var levelsFlat = isFlat(cur.yAcLevels, 0, 16, 0);
            rawDistoI16[mode] = cur.d;
            rawSpectralI16[mode] = cur.sd;
            rateI16[mode] = cur.r;
            flatI16[mode] = levelsFlat;
            if (flat) {
                flat = levelsFlat;
                if (flat) {
                    cur.d *= 2;
                    cur.sd *= 2;
                }
            }
            cur.setRdScore(lambda);
            if (mode == 0 || cur.score < best.score) {
                var t = cur;
                cur = best;
                best = t;
                it.swapOut();
            }
        }
        if (best != rd) {
            rd.copyFrom(best);
        }
        rd.setRdScore(m.lambdaMode);
        it.setIntra16Mode(rd.modeI16);
        if ((rd.nz & 0x100FFFF) == 0x1000000 && rd.d > m.minDisto) {
            var v0 = Math.abs(rd.yDcLevels[1]);
            var v1 = Math.abs(rd.yDcLevels[2]);
            var v2 = Math.abs(rd.yDcLevels[4]);
            var maxV = Math.max(Math.max(v0, v1), v2);
            var segment = mbSegment[it.mb];
            if (maxV > maxEdgeLocal[segment]) {
                maxEdgeLocal[segment] = maxV;
            }
        }
    }

    /**
     * Finds the modes whose prediction block repeats an earlier mode's prediction.
     *
     * @param offsets the offsets of the mode predictions in {@link EncIterator#yuvP}
     * @param width   the block width, a multiple of 8
     * @param height  the block height
     * @param dupOf   receives, per mode, the first mode with the same prediction (the mode
     *                itself when it is the first)
     * @return whether some prediction repeats
     */
    private boolean findDuplicatePredictions(int[] offsets, int width, int height, int[] dupOf) {
        var pred = it.yuvP;
        var any = false;
        for (var mode = 0; mode < offsets.length; mode++) {
            dupOf[mode] = mode;
            for (var earlier = 0; earlier < mode; earlier++) {
                if (dupOf[earlier] == earlier && samePrediction(pred, offsets[mode], offsets[earlier], width, height)) {
                    dupOf[mode] = earlier;
                    any = true;
                    break;
                }
            }
        }
        return any;
    }

    /**
     * Returns whether two prediction blocks are equal.
     *
     * @param pred   the prediction buffer
     * @param a      the offset of the first block
     * @param b      the offset of the second block
     * @param width  the block width, a multiple of 8
     * @param height the block height
     * @return whether every sample is equal
     */
    private static boolean samePrediction(byte[] pred, int a, int b, int width, int height) {
        for (var row = 0; row < height; row++) {
            var ra = a + row * EncIterator.BPS;
            var rb = b + row * EncIterator.BPS;
            for (var x = 0; x < width; x += 8) {
                if ((long) LONG.get(pred, ra + x) != (long) LONG.get(pred, rb + x)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Returns the index in {@link Vp8EncTables#FIXED_COSTS_I4} of the mode costs of the current
     * subblock given its neighbors' modes.
     *
     * @param modes the modes chosen so far in this macroblock
     * @return the index of the ten mode costs
     */
    private int costModeI4(int[] modes) {
        var x = it.i4 & 3;
        var y = it.i4 >> 2;
        var left = x == 0 ? preds[it.preds + y * predsW - 1] : modes[it.i4 - 1];
        var top = y == 0 ? preds[it.preds - predsW + x] : modes[it.i4 - 4];
        return (top * 10 + left) * 10;
    }

    /**
     * Tries to code the current macroblock with 4x4 modes, keeping them when they beat the
     * 16x16 decision, like libwebp's {@code PickBestIntra4}.
     *
     * @implNote This implementation evaluates a mode whose prediction repeats an earlier mode's
     * only when it can win, like {@link #pickBestIntra16}: the repeated mode's partial and full
     * scores follow from the earlier mode's distortions, flatness and rate, which are identical,
     * so it is skipped exactly when the full evaluation would lose. Without trellis quantization
     * each candidate is coded and measured by the single-pass {@link Vp8EncDsp#evaluateBlock},
     * and the source block's spectral sum, common to all ten modes, is computed once.
     *
     * @param rd the decision holding the 16x16 choice, replaced when 4x4 modes win
     */
    private void pickBestIntra4(ModeScore rd) {
        var m = segment();
        var lambda = m.lambdaI4;
        var tlambda = m.tlambda;
        var src0 = it.yuvIn;
        var bestBlocks = it.yuvOut2;
        var totalHeaderBits = 0;
        var best = scoreI4Best;
        if (maxI4HeaderBits == 0) {
            return;
        }
        best.init();
        best.h = 211;
        best.setRdScore(m.lambdaMode);
        it.startI4();
        do {
            var i4 = it.i4;
            var rdI4 = scoreI4;
            rdI4.init();
            var bestMode = -1;
            var src = EncIterator.Y_OFF + EncIterator.SCAN[i4];
            var modeCosts = costModeI4(rd.modesI4);
            var bestBlockArr = bestBlocks;
            var bestBlockOff = EncIterator.Y_OFF + EncIterator.SCAN[i4];
            var tmpDstArr = it.yuvP;
            var tmpDstOff = EncPredict.I4TMP;
            EncPredict.luma4(it.yuvP, it.i4Boundary, it.i4Top);
            var dups = findDuplicateI4Predictions();
            var srcSpectral = tlambda != 0 && !it.doTrellis ? dsp.spectralSum(src0, src, WEIGHT_Y) : 0;
            for (var mode = 0; mode < 10; mode++) {
                var tmp = scoreI4Tmp;
                var same = dups ? dupI4[mode] : mode;
                if (same != mode) {
                    tmp.d = distoI4[same];
                    tmp.sd = spectralI4[same];
                    tmp.h = Vp8EncTables.FIXED_COSTS_I4[modeCosts + mode];
                    tmp.r = flatI4[same] ? 140 : 0;
                    tmp.setRdScore(lambda);
                    if (tmp.score >= rdI4.score) {
                        continue;
                    }
                    if (rateI4[same] < 0) {
                        rateI4[same] = costLuma4(levelsI4, same * 16);
                    }
                    tmp.r += rateI4[same];
                    tmp.setRdScore(lambda);
                    if (tmp.score >= rdI4.score) {
                        continue;
                    }
                }
                var levels = tmpLevels;
                if (it.doTrellis) {
                    tmp.nz = reconstructIntra4(levels, 0, src0, src, tmpDstArr, tmpDstOff, mode) << i4;
                    tmp.d = dsp.sse(src0, src, tmpDstArr, tmpDstOff, 4, 4);
                    tmp.sd = tlambda != 0 ? mult8b(tlambda, dsp.tDisto4x4(src0, src, tmpDstArr, tmpDstOff, WEIGHT_Y)) : 0;
                } else {
                    var packed = dsp.evaluateBlock(src0, src, it.yuvP, EncPredict.I4_MODE_OFFSETS[mode], m.y1, levels, 0,
                            tmpDstArr, tmpDstOff, WEIGHT_Y, tmp4);
                    tmp.nz = ((int) packed & 1) << i4;
                    tmp.d = (packed >>> 1) & 0x7FFFFFFFL;
                    tmp.sd = tlambda != 0 ? mult8b(tlambda, Math.abs((int) (packed >>> 32) - srcSpectral) >> 5) : 0;
                }
                tmp.h = Vp8EncTables.FIXED_COSTS_I4[modeCosts + mode];
                var levelsFlat = isFlat(levels, 0, 1, 3);
                tmp.r = mode > 0 && levelsFlat ? 140 : 0;
                if (dups && same == mode) {
                    distoI4[mode] = tmp.d;
                    spectralI4[mode] = tmp.sd;
                    flatI4[mode] = levelsFlat;
                    rateI4[mode] = -1;
                    System.arraycopy(levels, 0, levelsI4, mode * 16, 16);
                }
                tmp.setRdScore(lambda);
                if (bestMode >= 0 && tmp.score >= rdI4.score) {
                    continue;
                }
                var rate = costLuma4(levels, 0);
                if (dups && same == mode) {
                    rateI4[mode] = rate;
                }
                tmp.r += rate;
                tmp.setRdScore(lambda);
                if (bestMode < 0 || tmp.score < rdI4.score) {
                    rdI4.copyScore(tmp);
                    bestMode = mode;
                    var ta = tmpDstArr;
                    var to = tmpDstOff;
                    tmpDstArr = bestBlockArr;
                    tmpDstOff = bestBlockOff;
                    bestBlockArr = ta;
                    bestBlockOff = to;
                    System.arraycopy(levels, 0, best.yAcLevels, i4 * 16, 16);
                }
            }
            rdI4.setRdScore(m.lambdaMode);
            best.addScore(rdI4);
            if (best.score >= rd.score) {
                return;
            }
            totalHeaderBits += (int) rdI4.h;
            if (totalHeaderBits > maxI4HeaderBits) {
                return;
            }
            var target = EncIterator.Y_OFF + EncIterator.SCAN[i4];
            if (bestBlockArr != bestBlocks || bestBlockOff != target) {
                for (var r = 0; r < 4; r++) {
                    System.arraycopy(bestBlockArr, bestBlockOff + r * EncIterator.BPS, bestBlocks,
                            target + r * EncIterator.BPS, 4);
                }
            }
            rd.modesI4[i4] = bestMode;
            var flag = rdI4.nz != 0 ? 1 : 0;
            it.topNz[i4 & 3] = flag;
            it.leftNz[i4 >> 2] = flag;
        } while (it.rotateI4(bestBlocks, EncIterator.Y_OFF));
        rd.copyScore(best);
        it.setIntra4Mode(rd.modesI4);
        it.swapOut();
        System.arraycopy(best.yAcLevels, 0, rd.yAcLevels, 0, 256);
    }

    /**
     * Finds the subblock modes whose 4x4 prediction repeats an earlier mode's prediction, into
     * {@link #dupI4}.
     *
     * @return whether some prediction repeats
     */
    private boolean findDuplicateI4Predictions() {
        var pred = it.yuvP;
        var keys = predKeysI4;
        for (var mode = 0; mode < 10; mode++) {
            var off = EncPredict.I4_MODE_OFFSETS[mode];
            keys[2 * mode] = ((int) INT.get(pred, off) & 0xFFFFFFFFL)
                    | ((long) (int) INT.get(pred, off + EncIterator.BPS) << 32);
            keys[2 * mode + 1] = ((int) INT.get(pred, off + 2 * EncIterator.BPS) & 0xFFFFFFFFL)
                    | ((long) (int) INT.get(pred, off + 3 * EncIterator.BPS) << 32);
        }
        var any = false;
        for (var mode = 0; mode < 10; mode++) {
            dupI4[mode] = mode;
            for (var earlier = 0; earlier < mode; earlier++) {
                if (dupI4[earlier] == earlier && keys[2 * mode] == keys[2 * earlier]
                        && keys[2 * mode + 1] == keys[2 * earlier + 1]) {
                    dupI4[mode] = earlier;
                    any = true;
                    break;
                }
            }
        }
        return any;
    }

    /**
     * Chooses the best chroma mode by rate-distortion score, like libwebp's
     * {@code PickBestUV}.
     *
     * @implNote This implementation evaluates a mode whose prediction repeats an earlier mode's
     * only when it can win, like {@link #pickBestIntra16}.
     *
     * @param rd the decision receiving the chroma choice
     */
    private void pickBestUv(ModeScore rd) {
        var m = segment();
        var lambda = m.lambdaUv;
        var src = it.yuvIn;
        var tmpDst = it.yuvOut2;
        var dst0 = it.yuvOut;
        var dst = dst0;
        var best = scoreUvBest;
        var dups = findDuplicatePredictions(EncPredict.UV_MODE_OFFSETS, 16, 8, dupUv);
        rd.modeUv = -1;
        best.init();
        for (var mode = 0; mode < 4; mode++) {
            var same = dups ? dupUv[mode] : mode;
            if (same != mode) {
                var r = rateUv[same] + (flatUv[same] ? 140 * 8 : 0);
                var score = (r + Vp8EncTables.FIXED_COSTS_UV[mode]) * lambda + 256 * distoUv[same];
                if (score >= best.score) {
                    continue;
                }
            }
            var uv = scoreUv;
            uv.nz = reconstructUv(uv, tmpDst, EncIterator.U_OFF, mode);
            uv.d = dsp.sse(src, EncIterator.U_OFF, tmpDst, EncIterator.U_OFF, 16, 8);
            uv.sd = 0;
            uv.h = Vp8EncTables.FIXED_COSTS_UV[mode];
            uv.r = costUv(uv);
            var levelsFlat = isFlat(uv.uvLevels, 0, 8, 2);
            distoUv[mode] = uv.d;
            rateUv[mode] = uv.r;
            flatUv[mode] = levelsFlat;
            if (mode > 0 && levelsFlat) {
                uv.r += 140 * 8;
            }
            uv.setRdScore(lambda);
            if (mode == 0 || uv.score < best.score) {
                best.copyScore(uv);
                rd.modeUv = mode;
                System.arraycopy(uv.uvLevels, 0, rd.uvLevels, 0, 128);
                if (topDerr != null) {
                    System.arraycopy(uv.derr, 0, rd.derr, 0, 6);
                }
                var t = dst;
                dst = tmpDst;
                tmpDst = t;
            }
        }
        mbUvMode[it.mb] = (byte) rd.modeUv;
        rd.addScore(best);
        if (dst != dst0) {
            for (var r = 0; r < 8; r++) {
                System.arraycopy(dst, EncIterator.U_OFF + r * EncIterator.BPS, dst0, EncIterator.U_OFF + r * EncIterator.BPS, 16);
            }
        }
        if (topDerr != null) {
            storeDiffusionErrors(rd);
        }
    }

    /**
     * Requantizes the chosen modes of the current macroblock, like libwebp's
     * {@code SimpleQuantize}.
     *
     * @param rd the decision
     */
    private void simpleQuantize(ModeScore rd) {
        int nzBits;
        if (mbType[it.mb] == 1) {
            nzBits = reconstructIntra16(rd, it.yuvOut, EncIterator.Y_OFF, preds[it.preds]);
        } else {
            nzBits = 0;
            it.startI4();
            do {
                var mode = preds[it.preds + (it.i4 & 3) + (it.i4 >> 2) * predsW];
                var off = EncIterator.Y_OFF + EncIterator.SCAN[it.i4];
                EncPredict.luma4(it.yuvP, it.i4Boundary, it.i4Top);
                nzBits |= reconstructIntra4(rd.yAcLevels, it.i4 * 16, it.yuvIn, off, it.yuvOut, off, mode) << it.i4;
            } while (it.rotateI4(it.yuvOut, EncIterator.Y_OFF));
        }
        nzBits |= reconstructUv(rd, it.yuvOut, EncIterator.U_OFF, mbUvMode[it.mb]);
        rd.nz = nzBits;
    }

    /**
     * Chooses the modes of the current macroblock by distortion only (methods 0 to 2), like
     * libwebp's {@code RefineUsingDistortion}.
     *
     * @param tryBothModes whether to compare 16x16 and 4x4 luma modes
     * @param refineUvMode whether to choose the chroma mode
     * @param rd           the decision
     */
    private void refineUsingDistortion(boolean tryBothModes, boolean refineUvMode, ModeScore rd) {
        var bestScore = ModeScore.MAX_COST;
        var nzBits = 0;
        var isI16 = tryBothModes || mbType[it.mb] == 1;
        var m = segment();
        var scoreI4 = m.i4Penalty;
        long i4BitSum = 0;
        var bitLimit = tryBothModes ? mbHeaderLimit : ModeScore.MAX_COST;
        var src = it.yuvIn;
        if (isI16) {
            var bestMode = -1;
            for (var mode = 0; mode < 4; mode++) {
                var ref = EncPredict.I16_MODE_OFFSETS[mode];
                var score = (long) dsp.sse(src, EncIterator.Y_OFF, it.yuvP, ref, 16, 16) * 256
                        + (long) Vp8EncTables.FIXED_COSTS_I16[mode] * 106;
                if (mode > 0 && Vp8EncTables.FIXED_COSTS_I16[mode] > bitLimit) {
                    continue;
                }
                if (score < bestScore) {
                    bestMode = mode;
                    bestScore = score;
                }
            }
            if (it.x == 0 || it.y == 0) {
                if (isFlatSource16(src, EncIterator.Y_OFF)) {
                    bestMode = it.x == 0 ? 0 : 2;
                    tryBothModes = false;
                }
            }
            it.setIntra16Mode(bestMode);
        }
        if (tryBothModes || !isI16) {
            isI16 = false;
            it.startI4();
            do {
                var bestI4Mode = -1;
                var bestI4Score = ModeScore.MAX_COST;
                var off = EncIterator.Y_OFF + EncIterator.SCAN[it.i4];
                var modeCosts = costModeI4(rd.modesI4);
                EncPredict.luma4(it.yuvP, it.i4Boundary, it.i4Top);
                for (var mode = 0; mode < 10; mode++) {
                    var score = (long) dsp.sse(src, off, it.yuvP, EncPredict.I4_MODE_OFFSETS[mode], 4, 4) * 256
                            + (long) Vp8EncTables.FIXED_COSTS_I4[modeCosts + mode] * 11;
                    if (score < bestI4Score) {
                        bestI4Mode = mode;
                        bestI4Score = score;
                    }
                }
                i4BitSum += Vp8EncTables.FIXED_COSTS_I4[modeCosts + bestI4Mode];
                rd.modesI4[it.i4] = bestI4Mode;
                scoreI4 += bestI4Score;
                if (scoreI4 >= bestScore || i4BitSum > bitLimit) {
                    isI16 = true;
                    break;
                }
                nzBits |= reconstructIntra4(rd.yAcLevels, it.i4 * 16, src, off, it.yuvOut2, off, bestI4Mode) << it.i4;
            } while (it.rotateI4(it.yuvOut2, EncIterator.Y_OFF));
        }
        if (!isI16) {
            it.setIntra4Mode(rd.modesI4);
            it.swapOut();
            bestScore = scoreI4;
        } else {
            nzBits = reconstructIntra16(rd, it.yuvOut, EncIterator.Y_OFF, preds[it.preds]);
        }
        if (refineUvMode) {
            var bestMode = -1;
            var bestUvScore = ModeScore.MAX_COST;
            for (var mode = 0; mode < 4; mode++) {
                var score = (long) dsp.sse(src, EncIterator.U_OFF, it.yuvP, EncPredict.UV_MODE_OFFSETS[mode], 16, 8) * 256
                        + (long) Vp8EncTables.FIXED_COSTS_UV[mode] * 120;
                if (score < bestUvScore) {
                    bestMode = mode;
                    bestUvScore = score;
                }
            }
            mbUvMode[it.mb] = (byte) bestMode;
        }
        nzBits |= reconstructUv(rd, it.yuvOut, EncIterator.U_OFF, mbUvMode[it.mb]);
        rd.nz = nzBits;
        rd.score = bestScore;
    }

    /**
     * Quantizes one block with a trellis search over each coefficient's two nearest levels,
     * minimizing rate plus weighted distortion, like libwebp's {@code TrellisQuantizeBlock}.
     *
     * @param in        the coefficients, replaced with their reconstruction
     * @param inOff     the index of the first coefficient
     * @param out       the levels destination, in scan order
     * @param outOff    the index of the first level
     * @param ctx0      the nonzero context
     * @param coeffType the block type: 0 luma after Y2, 3 luma with DC
     * @param mtx       the quantization matrix
     * @param lambda    the rate weight
     * @return {@code 1} when a level is nonzero, {@code 0} otherwise
     */
    private int trellisQuantizeBlock(short[] in, int inOff, short[] out, int outOff, int ctx0, int coeffType,
                                     QuantMatrix mtx, int lambda) {
        var first = coeffType == 0 ? 1 : 0;
        var probs = proba.coeffs;
        var remap = proba.remapped;
        var rb = coeffType * 17;
        long bestScore;
        var bestPath0 = -1;
        var bestPath1 = -1;
        var bestPath2 = -1;
        var last = first - 1;
        var thresh = mtx.q[1] * mtx.q[1] / 4;
        var lastProba = probs[EncProba.index(coeffType, Vp8Tables.BANDS[first], ctx0)];
        for (var n = 15; n >= first; n--) {
            int v = in[inOff + ZIGZAG[n]];
            if (v * v > thresh) {
                last = n;
                break;
            }
        }
        if (last < 15) {
            last++;
        }
        bestScore = (long) EncProba.bitCost(0, lastProba) * lambda;
        var cur = 0;
        var prev = 2;
        for (var m = 0; m <= 1; m++) {
            var rate = ctx0 == 0 ? EncProba.bitCost(1, lastProba) : 0;
            stateScore[cur + m] = (long) rate * lambda;
            stateCosts[cur + m] = remap[(rb + first) * 3 + ctx0];
        }
        for (var n = first; n <= last; n++) {
            var j = ZIGZAG[n];
            var q = mtx.q[j];
            var iq = mtx.iq[j];
            int c = in[inOff + j];
            var sign = c < 0 ? 1 : 0;
            var coeff0 = (sign != 0 ? -c : c) + mtx.sharpen[j];
            var level0 = QuantMatrix.quantDiv(coeff0, iq, 0);
            var threshLevel = QuantMatrix.quantDiv(coeff0, iq, 0x80 << (QuantMatrix.QFIX - 8));
            if (threshLevel > ScalarVp8EncDsp.MAX_LEVEL) {
                threshLevel = ScalarVp8EncDsp.MAX_LEVEL;
            }
            if (level0 > ScalarVp8EncDsp.MAX_LEVEL) {
                level0 = ScalarVp8EncDsp.MAX_LEVEL;
            }
            var t = cur;
            cur = prev;
            prev = t;
            for (var m = 0; m <= 1; m++) {
                var level = level0 + m;
                var ctx = level > 2 ? 2 : level;
                var band = Vp8Tables.BANDS[n + 1];
                stateCosts[cur + m] = remap[(rb + n + 1) * 3 + ctx];
                if (level < 0 || level > threshLevel) {
                    stateScore[cur + m] = ModeScore.MAX_COST;
                    continue;
                }
                var newError = coeff0 - level * q;
                var deltaError = WEIGHT_TRELLIS[j] * (newError * newError - coeff0 * coeff0);
                var baseScore = 256L * deltaError;
                var cost = proba.levelCost(stateCosts[prev], level);
                var bestCurScore = stateScore[prev] + (long) cost * lambda;
                var bestPrev = 0;
                cost = proba.levelCost(stateCosts[prev + 1], level);
                var score = stateScore[prev + 1] + (long) cost * lambda;
                if (score < bestCurScore) {
                    bestCurScore = score;
                    bestPrev = 1;
                }
                bestCurScore += baseScore;
                var node = n * 2 + m;
                nodeSign[node] = sign;
                nodeLevel[node] = level;
                nodePrev[node] = bestPrev;
                stateScore[cur + m] = bestCurScore;
                if (level != 0 && bestCurScore < bestScore) {
                    var lastPosCost = n < 15 ? EncProba.bitCost(0, probs[EncProba.index(coeffType, band, ctx)]) : 0;
                    score = bestCurScore + (long) lastPosCost * lambda;
                    if (score < bestScore) {
                        bestScore = score;
                        bestPath0 = n;
                        bestPath1 = m;
                        bestPath2 = bestPrev;
                    }
                }
            }
        }
        if (coeffType == 0) {
            Arrays.fill(in, inOff + 1, inOff + 16, (short) 0);
            Arrays.fill(out, outOff + 1, outOff + 16, (short) 0);
        } else {
            Arrays.fill(in, inOff, inOff + 16, (short) 0);
            Arrays.fill(out, outOff, outOff + 16, (short) 0);
        }
        if (bestPath0 == -1) {
            return 0;
        }
        var nzAny = 0;
        var bestNode = bestPath1;
        var n = bestPath0;
        nodePrev[n * 2 + bestNode] = bestPath2;
        for (; n >= first; n--) {
            var node = n * 2 + bestNode;
            var j = ZIGZAG[n];
            var level = nodeLevel[node];
            var v = (short) (nodeSign[node] != 0 ? -level : level);
            out[outOff + n] = v;
            nzAny |= level;
            in[inOff + j] = (short) (v * mtx.q[j]);
            bestNode = nodePrev[node];
        }
        return nzAny != 0 ? 1 : 0;
    }

    /**
     * Applies the forward Walsh-Hadamard transform to the DC coefficients of the 16 luma blocks,
     * like libwebp's {@code FTransformWHT_C}.
     *
     * @param in  the 16 blocks of coefficients
     * @param out the 16 transformed DC coefficients
     */
    private void fTransformWht(short[] in, short[] out) {
        forwardWht(in, out, whtTmp);
    }

    /**
     * Applies the forward Walsh-Hadamard transform to the DC coefficients of the 16 luma blocks,
     * like libwebp's {@code FTransformWHT_C}; shared with the real-time encoder.
     *
     * @param in  the 16 blocks of coefficients, 16 apart, in raster block order
     * @param out the 16 transformed DC coefficients
     * @param tmp 16 ints of scratch space
     */
    static void forwardWht(short[] in, short[] out, int[] tmp) {
        for (var i = 0; i < 4; i++) {
            var base = i * 64;
            var a0 = in[base] + in[base + 32];
            var a1 = in[base + 16] + in[base + 48];
            var a2 = in[base + 16] - in[base + 48];
            var a3 = in[base] - in[base + 32];
            tmp[i * 4] = a0 + a1;
            tmp[1 + i * 4] = a3 + a2;
            tmp[2 + i * 4] = a3 - a2;
            tmp[3 + i * 4] = a0 - a1;
        }
        for (var i = 0; i < 4; i++) {
            var a0 = tmp[i] + tmp[8 + i];
            var a1 = tmp[4 + i] + tmp[12 + i];
            var a2 = tmp[4 + i] - tmp[12 + i];
            var a3 = tmp[i] - tmp[8 + i];
            out[i] = (short) ((a0 + a1) >> 1);
            out[4 + i] = (short) ((a3 + a2) >> 1);
            out[8 + i] = (short) ((a3 - a2) >> 1);
            out[12 + i] = (short) ((a0 - a1) >> 1);
        }
    }

    /**
     * Applies the inverse Walsh-Hadamard transform and stores the results as the DC
     * coefficients of the 16 luma blocks, like libwebp's {@code TransformWHT_C}.
     *
     * @param in  the 16 dequantized Y2 coefficients
     * @param out the 16 blocks of coefficients
     */
    private void inverseWht(short[] in, short[] out) {
        var tmp = whtTmp;
        for (var i = 0; i < 4; i++) {
            var a0 = in[i] + in[12 + i];
            var a1 = in[4 + i] + in[8 + i];
            var a2 = in[4 + i] - in[8 + i];
            var a3 = in[i] - in[12 + i];
            tmp[i] = a0 + a1;
            tmp[8 + i] = a0 - a1;
            tmp[4 + i] = a3 + a2;
            tmp[12 + i] = a3 - a2;
        }
        for (var i = 0; i < 4; i++) {
            var dc = tmp[i * 4] + 3;
            var a0 = dc + tmp[3 + i * 4];
            var a1 = tmp[1 + i * 4] + tmp[2 + i * 4];
            var a2 = tmp[1 + i * 4] - tmp[2 + i * 4];
            var a3 = dc - tmp[3 + i * 4];
            var base = i * 64;
            out[base] = (short) ((a0 + a1) >> 3);
            out[base + 16] = (short) ((a3 + a2) >> 3);
            out[base + 32] = (short) ((a0 - a1) >> 3);
            out[base + 48] = (short) ((a3 - a2) >> 3);
        }
    }

    /**
     * Records the token statistics of the current macroblock without coding it, like libwebp's
     * {@code RecordResiduals}.
     *
     * @param rd the decision
     */
    private void recordResiduals(ModeScore rd) {
        it.nzToBytes();
        if (mbType[it.mb] == 1) {
            res.init(0, 1);
            res.setCoeffs(rd.yDcLevels, 0);
            var flag = proba.recordCoeffs(it.topNz[8] + it.leftNz[8], res);
            it.topNz[8] = flag;
            it.leftNz[8] = flag;
            res.init(1, 0);
        } else {
            res.init(0, 3);
        }
        for (var y = 0; y < 4; y++) {
            for (var x = 0; x < 4; x++) {
                var ctx = it.topNz[x] + it.leftNz[y];
                res.setCoeffs(rd.yAcLevels, (x + y * 4) * 16);
                var flag = proba.recordCoeffs(ctx, res);
                it.topNz[x] = flag;
                it.leftNz[y] = flag;
            }
        }
        res.init(0, 2);
        for (var ch = 0; ch <= 2; ch += 2) {
            for (var y = 0; y < 2; y++) {
                for (var x = 0; x < 2; x++) {
                    var ctx = it.topNz[4 + ch + x] + it.leftNz[4 + ch + y];
                    res.setCoeffs(rd.uvLevels, (ch * 2 + x + y * 2) * 16);
                    var flag = proba.recordCoeffs(ctx, res);
                    it.topNz[4 + ch + x] = flag;
                    it.leftNz[4 + ch + y] = flag;
                }
            }
        }
        it.bytesToNz();
    }

    /**
     * Records the tokens of the current macroblock, like libwebp's {@code RecordTokens}.
     *
     * @param rd     the decision
     * @param buffer the token buffer of the macroblock's partition
     */
    private void recordTokens(ModeScore rd, TokenBuffer buffer) {
        it.nzToBytes();
        if (mbType[it.mb] == 1) {
            var ctx = it.topNz[8] + it.leftNz[8];
            res.init(0, 1);
            res.setCoeffs(rd.yDcLevels, 0);
            var flag = proba.recordTokens(ctx, res, buffer);
            it.topNz[8] = flag;
            it.leftNz[8] = flag;
            res.init(1, 0);
        } else {
            res.init(0, 3);
        }
        for (var y = 0; y < 4; y++) {
            for (var x = 0; x < 4; x++) {
                var ctx = it.topNz[x] + it.leftNz[y];
                res.setCoeffs(rd.yAcLevels, (x + y * 4) * 16);
                var flag = proba.recordTokens(ctx, res, buffer);
                it.topNz[x] = flag;
                it.leftNz[y] = flag;
            }
        }
        res.init(0, 2);
        for (var ch = 0; ch <= 2; ch += 2) {
            for (var y = 0; y < 2; y++) {
                for (var x = 0; x < 2; x++) {
                    var ctx = it.topNz[4 + ch + x] + it.leftNz[4 + ch + y];
                    res.setCoeffs(rd.uvLevels, (ch * 2 + x + y * 2) * 16);
                    var flag = proba.recordTokens(ctx, res, buffer);
                    it.topNz[4 + ch + x] = flag;
                    it.leftNz[4 + ch + y] = flag;
                }
            }
        }
        it.bytesToNz();
    }

    /**
     * Codes the tokens of the current macroblock, like libwebp's {@code CodeResiduals}.
     *
     * @param bw the partition writer
     * @param rd the decision
     */
    private void codeResiduals(BoolEncoder bw, ModeScore rd) {
        it.nzToBytes();
        if (mbType[it.mb] == 1) {
            res.init(0, 1);
            res.setCoeffs(rd.yDcLevels, 0);
            var flag = proba.putCoeffs(bw, it.topNz[8] + it.leftNz[8], res);
            it.topNz[8] = flag;
            it.leftNz[8] = flag;
            res.init(1, 0);
        } else {
            res.init(0, 3);
        }
        for (var y = 0; y < 4; y++) {
            for (var x = 0; x < 4; x++) {
                var ctx = it.topNz[x] + it.leftNz[y];
                res.setCoeffs(rd.yAcLevels, (x + y * 4) * 16);
                var flag = proba.putCoeffs(bw, ctx, res);
                it.topNz[x] = flag;
                it.leftNz[y] = flag;
            }
        }
        res.init(0, 2);
        for (var ch = 0; ch <= 2; ch += 2) {
            for (var y = 0; y < 2; y++) {
                for (var x = 0; x < 2; x++) {
                    var ctx = it.topNz[4 + ch + x] + it.leftNz[4 + ch + y];
                    res.setCoeffs(rd.uvLevels, (ch * 2 + x + y * 2) * 16);
                    var flag = proba.putCoeffs(bw, ctx, res);
                    it.topNz[4 + ch + x] = flag;
                    it.leftNz[4 + ch + y] = flag;
                }
            }
        }
        it.bytesToNz();
    }

    /**
     * Writes the first partition and assembles the frame, like libwebp's {@code VP8EncWrite}.
     *
     * @return the frame bytes
     */
    private byte[] write() {
        var bw = new BoolEncoder(mbW * mbH * 7 / 8);
        bw.putBitUniform(0);
        bw.putBitUniform(0);
        if (bw.putBitUniform(numSegments > 1 ? 1 : 0) != 0) {
            bw.putBitUniform(updateMap ? 1 : 0);
            if (bw.putBitUniform(1) != 0) {
                bw.putBitUniform(1);
                for (var s = 0; s < NUM_MB_SEGMENTS; s++) {
                    bw.putSignedBits(dqm[s].quant, 7);
                }
                for (var s = 0; s < NUM_MB_SEGMENTS; s++) {
                    bw.putSignedBits(dqm[s].fstrength, 6);
                }
            }
            if (updateMap) {
                for (var s = 0; s < 3; s++) {
                    if (bw.putBitUniform(proba.segments[s] != 255 ? 1 : 0) != 0) {
                        bw.putBits(proba.segments[s], 8);
                    }
                }
            }
        }
        bw.putBitUniform(filterSimple ? 1 : 0);
        bw.putBits(filterLevel, 6);
        bw.putBits(filterSharpness, 3);
        bw.putBitUniform(0);
        bw.putBits(numParts == 8 ? 3 : numParts == 4 ? 2 : numParts == 2 ? 1 : 0, 2);
        bw.putBits(baseQuant, 7);
        bw.putSignedBits(dqY1Dc, 4);
        bw.putSignedBits(dqY2Dc, 4);
        bw.putSignedBits(dqY2Ac, 4);
        bw.putSignedBits(dqUvDc, 4);
        bw.putSignedBits(dqUvAc, 4);
        bw.putBitUniform(0);
        var probs = proba.coeffs;
        for (var i = 0; i < 1056; i++) {
            var p0 = probs[i];
            var update = p0 != Vp8Tables.COEF_PROBS_DEFAULT[i] ? 1 : 0;
            if (bw.putBit(update, Vp8Tables.COEF_UPDATE_PROBS[i]) != 0) {
                bw.putBits(p0, 8);
            }
        }
        if (bw.putBitUniform(proba.useSkipProba ? 1 : 0) != 0) {
            bw.putBits(proba.skipProba, 8);
        }
        codeIntraModes(bw);
        bw.finish();
        return BoolEncoder.assembleFrame(true, profile, picture.width(), picture.height(), bw, parts);
    }

    /**
     * Codes the segment, skip flag, and modes of every macroblock, like libwebp's
     * {@code VP8CodeIntraModes}.
     *
     * @param bw the first partition writer
     */
    private void codeIntraModes(BoolEncoder bw) {
        it.init();
        do {
            var mb = it.mb;
            var p = it.preds;
            if (updateMap) {
                var s = mbSegment[mb];
                var probs = proba.segments;
                if (bw.putBit(s >= 2 ? 1 : 0, probs[0]) != 0) {
                    bw.putBit(s & 1, probs[2]);
                } else {
                    bw.putBit(s & 1, probs[1]);
                }
            }
            if (proba.useSkipProba) {
                bw.putBit(mbSkip[mb], proba.skipProba);
            }
            if (bw.putBit(mbType[mb] != 0 ? 1 : 0, 145) != 0) {
                var mode = preds[p];
                if (bw.putBit(mode == 1 || mode == 3 ? 1 : 0, 156) != 0) {
                    bw.putBit(mode == 1 ? 1 : 0, 128);
                } else {
                    bw.putBit(mode == 2 ? 1 : 0, 163);
                }
            } else {
                var top = p - predsW;
                var row = p;
                for (var y = 0; y < 4; y++) {
                    int left = preds[row - 1];
                    for (var x = 0; x < 4; x++) {
                        var probas = (preds[top + x] * 10 + left) * 9;
                        left = putI4Mode(bw, preds[row + x], probas);
                    }
                    top = row;
                    row += predsW;
                }
            }
            var uvMode = mbUvMode[mb];
            if (bw.putBit(uvMode != 0 ? 1 : 0, 142) != 0) {
                if (bw.putBit(uvMode != 2 ? 1 : 0, 114) != 0) {
                    bw.putBit(uvMode != 3 ? 1 : 0, 183);
                }
            }
        } while (it.next());
    }

    /**
     * Codes one subblock mode with the key-frame mode tree, like libwebp's {@code PutI4Mode}.
     *
     * @param bw   the first partition writer
     * @param mode the mode, in libwebp order
     * @param p    the index of the context's probabilities in {@link Vp8EncTables#BMODES_PROBS}
     * @return {@code mode}
     */
    private static int putI4Mode(BoolEncoder bw, int mode, int p) {
        var prob = Vp8EncTables.BMODES_PROBS;
        if (bw.putBit(mode != 0 ? 1 : 0, prob[p]) != 0) {
            if (bw.putBit(mode != 1 ? 1 : 0, prob[p + 1]) != 0) {
                if (bw.putBit(mode != 2 ? 1 : 0, prob[p + 2]) != 0) {
                    if (bw.putBit(mode >= 6 ? 1 : 0, prob[p + 3]) == 0) {
                        if (bw.putBit(mode != 3 ? 1 : 0, prob[p + 4]) != 0) {
                            bw.putBit(mode != 4 ? 1 : 0, prob[p + 5]);
                        }
                    } else {
                        if (bw.putBit(mode != 6 ? 1 : 0, prob[p + 6]) != 0) {
                            if (bw.putBit(mode != 7 ? 1 : 0, prob[p + 7]) != 0) {
                                bw.putBit(mode != 8 ? 1 : 0, prob[p + 8]);
                            }
                        }
                    }
                }
            }
        }
        return mode;
    }
}
