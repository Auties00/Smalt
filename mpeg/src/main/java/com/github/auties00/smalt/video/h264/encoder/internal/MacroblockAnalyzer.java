package com.github.auties00.smalt.video.h264.encoder.internal;

import com.github.auties00.smalt.internal.bits.MsbBitWriter;
import com.github.auties00.smalt.video.h264.internal.H264Dsp;
import com.github.auties00.smalt.video.h264.internal.H264Tables;

import java.util.Arrays;

/**
 * Decides and reconstructs the macroblocks of a picture, one row at a time.
 *
 * <p>For every macroblock the analyzer evaluates the candidate modes the speed allows, decides by
 * Lagrangian cost (SATD plus weighted header bits, refined by true rate and squared error at the
 * slower speeds), codes the residual, writes the reconstruction into the picture and records the
 * macroblock's parameters in the picture tables; a {@link SliceWriter} entropy codes the decided
 * macroblocks afterwards in raster order. A macroblock reads only the reconstruction and the
 * parameters of its left, top-left, top and top-right neighbors, so several analyzers can work
 * on consecutive rows at once when each stays at least two macroblocks behind the row above.
 *
 * <p>Decisions never depend on the state of the slice's entropy coder, which only exists in
 * raster order: the rate of a candidate is estimated with CABAC context states that follow the
 * current row, starting from the states the row above had after its second macroblock (the
 * wavefront synchronization of HEVC), and the quantizer difference and skip run a candidate
 * would carry are counted from the row's first macroblock. The decisions, and therefore the
 * stream, are thus the same whatever the number of threads. Instances keep their scratch buffers
 * across pictures and are not thread-safe.
 *
 * <p>A macroblock whose estimated coded size comes close to the {@code 128 + RawMbBits} limit of
 * Annex A is replaced by an {@code I_PCM} macroblock carrying the source samples; the writer
 * checks the exact size and reports the rare macroblock that still exceeds it, which the picture
 * then codes as {@code I_PCM} on a second pass.
 */
final class MacroblockAnalyzer {
    /**
     * The estimated macroblock size below which the size limit is not checked.
     */
    private static final int SAFE_MB_BITS = 2400;

    /**
     * The estimated CABAC macroblock size above which the macroblock is coded as {@code I_PCM}.
     *
     * @implNote This implementation keeps a margin of 256 bits below the {@code 3200 - 16} bits
     * the writer allows, because the estimate uses the row's context states rather than those of
     * the slice's coder.
     */
    private static final int CABAC_PCM_BITS = 3200 - 16 - 256;

    /**
     * The exact CAVLC macroblock layer size above which the macroblock is coded as
     * {@code I_PCM}, 32 bits below the limit to cover a different {@code mb_qp_delta}.
     */
    private static final int CAVLC_PCM_BITS = 3200 - 32;

    /**
     * The multiple of the quantizer step above which an 8x8 partition's distortion justifies
     * searching its sub-8x8 partitionings.
     */
    private static final double SUB_PARTITION_FACTOR = 96;

    /**
     * The skip probe result of a macroblock whose skip residual quantizes to nothing and is
     * small: the macroblock is skipped without further analysis.
     */
    private static final int SKIP_FREE = 0;

    /**
     * The skip probe result of a macroblock whose skip residual quantizes to nothing although
     * its SAD exceeds eight quantizer steps per 4x4 block: only the 16x16 motion is searched.
     */
    private static final int SKIP_STATIC = 1;

    /**
     * The skip probe result of a macroblock whose skip residual would be coded: every mode is
     * analyzed.
     */
    private static final int SKIP_CODED = 2;

    /**
     * The number of weighted bits by which a {@code P_L0_16x16} candidate's SATD cost must beat
     * the skip prediction's for a macroblock whose skip residual quantizes to nothing to be
     * analyzed further, see {@link #skipNearlyAsGood}.
     */
    private static final int STATIC_SKIP_MARGIN_BITS = 2;

    /**
     * The fewest bits a CAVLC inter macroblock takes in a P slice: one each for
     * {@code mb_skip_run}, {@code mb_type}, the two components of a vector difference and
     * {@code coded_block_pattern}.
     */
    private static final int CAVLC_MIN_INTER_BITS = 5;

    /**
     * The 16x16 distortion above which smaller partitions and further references are searched,
     * 128 quantizer steps, per quantizer.
     */
    private static final int[] SPLIT_THRESHOLD = new int[52];

    /**
     * The 8x8 partition distortion above which the sub-8x8 partitionings are searched, per
     * quantizer.
     */
    private static final int[] SUB_PARTITION_THRESHOLD = new int[52];

    static {
        for (var q = 0; q < 52; q++) {
            SPLIT_THRESHOLD[q] = (int) (128 * RateController.qscale(q));
            SUB_PARTITION_THRESHOLD[q] = (int) (SUB_PARTITION_FACTOR * RateController.qscale(q));
        }
    }

    /**
     * The stream settings.
     */
    private final EncoderSettings settings;

    /**
     * The analysis parameters of the configured speed.
     */
    private final EncoderSettings.Analysis analysis;

    /**
     * Whether the slice data is CABAC coded.
     */
    private final boolean cabacMode;

    /**
     * Whether the 8x8 transform is enabled.
     */
    private final boolean transform8x8Mode;

    /**
     * The pixel kernels.
     */
    private PixelKernels pixels;

    /**
     * The interpolation kernels.
     */
    private InterpolationKernels interpolation;

    /**
     * The intra predictor.
     */
    private final IntraPredictor intra = new IntraPredictor();

    /**
     * The residual coder.
     */
    private final ResidualCoder residual;

    /**
     * The motion vector predictor.
     */
    private final MotionPredictor predictor = new MotionPredictor();

    /**
     * The motion search.
     */
    private final MotionSearch search;

    /**
     * The CABAC bit estimator of rate-distortion decisions.
     */
    private final CabacEstimator estimator = new CabacEstimator();

    /**
     * The CABAC context states along the current row, advanced by every decided macroblock.
     */
    private final CabacEstimator rowStates = new CabacEstimator();

    /**
     * The row context states saved before a guarded macroblock is counted.
     */
    private final byte[] savedRowStates = new byte[CabacTables.CONTEXTS];

    /**
     * The context states the row would have after {@link #rdBest}, valid when
     * {@link #rdBestStatesValid}.
     */
    private final byte[] rdBestStates = new byte[CabacTables.CONTEXTS];

    /**
     * The estimated cost of {@link #rdBest}'s macroblock layer, without the skip flag, in 1/256
     * bits, valid when {@link #rdBestStatesValid}.
     */
    private int rdBestLayerCost;

    /**
     * Whether {@link #rdBestStates} and {@link #rdBestLayerCost} describe the current
     * macroblock's {@link #rdBest}.
     */
    private boolean rdBestStatesValid;

    /**
     * The estimated cost of the skip flag in the last CABAC count of {@link #countBits}, in 1/256
     * bits.
     */
    private int countedFlagCost;

    /**
     * The CAVLC writer used to count bits of candidates.
     */
    private final CavlcWriter cavlc = new CavlcWriter();

    /**
     * The scratch writer used to count CAVLC bits of candidates.
     */
    private final MsbBitWriter counter = new MsbBitWriter(1024);

    /**
     * The current macroblock's position and neighbors.
     */
    private final MbContext ctx = new MbContext();

    /**
     * The candidate {@code P_Skip} macroblock.
     */
    private final Macroblock skipMb = new Macroblock();

    /**
     * The candidate {@code P_L0_16x16} macroblock.
     */
    private final Macroblock p16Mb = new Macroblock();

    /**
     * The candidate {@code P_L0_L0_16x8} macroblock.
     */
    private final Macroblock p16x8Mb = new Macroblock();

    /**
     * The candidate {@code P_L0_L0_8x16} macroblock.
     */
    private final Macroblock p8x16Mb = new Macroblock();

    /**
     * The candidate {@code P_8x8} macroblock.
     */
    private final Macroblock p8x8Mb = new Macroblock();

    /**
     * The candidate Intra_16x16 macroblock.
     */
    private final Macroblock i16Mb = new Macroblock();

    /**
     * The candidate Intra_4x4 macroblock.
     */
    private final Macroblock i4Mb = new Macroblock();

    /**
     * The candidate Intra_8x8 macroblock.
     */
    private final Macroblock i8Mb = new Macroblock();

    /**
     * The macroblock chosen by the last rate-distortion comparison.
     */
    private final Macroblock rdBest = new Macroblock();

    /**
     * The {@code I_PCM} macroblock used when a coded macroblock exceeds the size limit.
     */
    private final Macroblock pcmMb = new Macroblock();

    /**
     * The Intra_16x16 predictions of the four modes.
     */
    private final byte[][] predictions16 = new byte[4][256];

    /**
     * The chroma predictions of the four modes: Cb then Cr, 64 samples each.
     */
    private final byte[][] predictionsChroma = new byte[4][128];

    /**
     * Scratch prediction of a 4x4 or 8x8 intra block.
     */
    private final byte[] blockPrediction = new byte[64];

    /**
     * The luma inter prediction of the macroblock being coded.
     */
    private final byte[] interLuma = new byte[256];

    /**
     * The Cb inter prediction of the macroblock being coded.
     */
    private final byte[] interCb = new byte[64];

    /**
     * The Cr inter prediction of the macroblock being coded.
     */
    private final byte[] interCr = new byte[64];

    /**
     * The luma prediction of the {@code P_Skip} candidate.
     */
    private final byte[] skipLuma = new byte[256];

    /**
     * The Cb prediction of the {@code P_Skip} candidate.
     */
    private final byte[] skipCb = new byte[64];

    /**
     * The Cr prediction of the {@code P_Skip} candidate.
     */
    private final byte[] skipCr = new byte[64];

    /**
     * Whether {@link #skipLuma} holds the current macroblock's skip prediction.
     */
    private boolean skipLumaReady;

    /**
     * Whether {@link #skipCb} and {@link #skipCr} hold the current macroblock's skip prediction.
     */
    private boolean skipChromaReady;

    /**
     * The saved reconstruction of the best rate-distortion candidate: luma, Cb, Cr.
     */
    private final byte[] savedReconstruction = new byte[384];

    /**
     * The extra search candidates passed to partition searches.
     */
    private final int[] hints = new int[4];

    /**
     * The picture being encoded.
     */
    private PictureContext picture;

    /**
     * The macroblock tables of the picture.
     */
    private PictureInfo pi;

    /**
     * The quantizer of the macroblock being coded.
     */
    private int qp;

    /**
     * The SAD-domain Lagrange multiplier of the current macroblock, times 256.
     */
    private int lambdaSad;

    /**
     * The squared-error Lagrange multiplier of the current macroblock, times 256.
     */
    private long lambdaSsd;

    /**
     * The quantizer of the previous macroblock of the row, which the rate estimates take the
     * next {@code mb_qp_delta} to be relative to.
     */
    private int previousQp;

    /**
     * Whether the previous macroblock of the row coded a non-zero {@code mb_qp_delta}.
     */
    private boolean previousQpDeltaNonZero;

    /**
     * The number of skipped macroblocks of the row since its last coded one.
     */
    private int skipRun;

    /**
     * The luma sample index of the current macroblock in the source.
     */
    private int srcLuma;

    /**
     * The chroma sample index of the current macroblock in the source.
     */
    private int srcChroma;

    /**
     * The luma sample index of the current macroblock in the reconstruction.
     */
    private int recLuma;

    /**
     * The chroma sample index of the current macroblock in the reconstruction.
     */
    private int recChroma;

    /**
     * The intra chroma mode of the current macroblock, or {@code -1} before it is decided.
     */
    private int chromaMode;

    /**
     * The reference index chosen for the 16x16 partition of the current macroblock.
     */
    private int bestRef16;

    /**
     * Whether the {@code P_8x8} candidate was analyzed for the current macroblock.
     */
    private boolean analyzed8x8;

    /**
     * The Intra_4x4 and Intra_8x8 modes in angular order, from diagonal down-left to horizontal-up.
     */
    private static final int[] ANGLES = {3, 7, 0, 5, 4, 6, 1, 8};

    /**
     * The position of each Intra_4x4 and Intra_8x8 mode in {@link #ANGLES}; the DC mode has none.
     */
    private static final int[] ANGLE_POSITION = {2, 6, -1, 0, 4, 3, 5, 1, 7};

    /**
     * Whether the block under intra mode evaluation is 8x8.
     */
    private boolean nxnEight;

    /**
     * Whether the samples above the block under evaluation are available.
     */
    private boolean nxnTop;

    /**
     * Whether the samples above-right of the block under evaluation are available.
     */
    private boolean nxnTopRight;

    /**
     * Whether the samples left of the block under evaluation are available.
     */
    private boolean nxnLeft;

    /**
     * Whether the sample above-left of the block under evaluation is available.
     */
    private boolean nxnTopLeft;

    /**
     * The predicted mode of the block under evaluation.
     */
    private int nxnPredicted;

    /**
     * The reconstruction index of the block under evaluation.
     */
    private int nxnRecOffset;

    /**
     * The source index of the block under evaluation.
     */
    private int nxnSrcOffset;

    /**
     * The modes already evaluated for the block, as a bit set.
     */
    private int nxnTried;

    /**
     * The best mode found for the block.
     */
    private int nxnBestMode;

    /**
     * The 4x4 Hadamard coefficients of the source of the block under evaluation, valid when
     * {@link #nxnHadamardReady}.
     */
    private final int[] nxnHadamard = new int[16];

    /**
     * The sum of the absolute values of {@link #nxnHadamard}.
     */
    private int nxnHadamardSum;

    /**
     * Whether {@link #nxnHadamard} holds the coefficients of the block under evaluation.
     */
    private boolean nxnHadamardReady;

    /**
     * The SATD cost of {@link #nxnBestMode}.
     */
    private int nxnBestCost;

    /**
     * Whether the samples above-right of the macroblock must not be used for prediction, because intra
     * refresh has not refreshed them yet.
     */
    private boolean avoidTopRight;

    /**
     * Whether the block under intra mode evaluation is the top-right 4x4 block of a macroblock that
     * must not predict from the samples above-right of it.
     */
    private boolean nxnAvoidTopRight;

    /**
     * The largest horizontal vector, in quarter samples, of a macroblock that intra refresh keeps from
     * predicting from columns it has not refreshed, or {@link Integer#MAX_VALUE} for no limit.
     */
    private int refreshLimitX = Integer.MAX_VALUE;

    /**
     * Constructs an analyzer.
     *
     * @param settings the stream settings
     * @param pixels the pixel kernels
     * @param transform the transform kernels
     * @param interpolation the interpolation kernels
     */
    MacroblockAnalyzer(EncoderSettings settings, PixelKernels pixels, TransformKernels transform,
                       InterpolationKernels interpolation) {
        this.settings = settings;
        this.analysis = settings.analysis();
        this.cabacMode = settings.cabac();
        this.transform8x8Mode = settings.transform8x8();
        this.pixels = pixels;
        this.interpolation = interpolation;
        this.residual = new ResidualCoder(transform, pixels, cabacMode ? 16383 : CavlcWriter.MAX_LEVEL,
            settings.decimationBlockThreshold(), settings.decimationMacroblockThreshold());
        this.search = new MotionSearch(pixels, analysis.partitions() >= 1);
        pcmMb.type = MbType.I_PCM;
        pcmMb.clearMotion();
        skipMb.clearResidual();
        i16Mb.clearMotion();
        i4Mb.clearMotion();
        i8Mb.clearMotion();
    }

    /**
     * Replaces the kernels the analyzer and its coders compute with, between two pictures.
     *
     * @param pixels the pixel kernels
     * @param transform the transform kernels
     * @param interpolation the interpolation kernels
     */
    void useKernels(PixelKernels pixels, TransformKernels transform, InterpolationKernels interpolation) {
        this.pixels = pixels;
        this.interpolation = interpolation;
        residual.useKernels(transform, pixels);
        search.useKernels(pixels);
        intra.useKernels(pixels);
    }

    /**
     * Prepares the analysis of a picture.
     *
     * @param picture the picture being encoded
     */
    void startPicture(PictureContext picture) {
        this.picture = picture;
        this.pi = picture.info;
        predictor.bind(pi, ctx);
    }

    /**
     * Prepares the analysis of a macroblock row.
     *
     * @param sync the CABAC context states of the row above after its second macroblock, or
     * {@code null} for the first row of a slice
     */
    void startRow(byte[] sync) {
        previousQp = picture.sliceQp;
        previousQpDeltaNonZero = false;
        skipRun = 0;
        if (cabacMode) {
            if (sync == null) {
                rowStates.initContexts(picture.pSlice || picture.bSlice ? 1 : 0, picture.sliceQp);
            } else {
                System.arraycopy(sync, 0, rowStates.states, 0, CabacTables.CONTEXTS);
            }
        }
    }

    /**
     * Prepares the analysis of a slice that starts in the middle of a macroblock row: the rate
     * estimates start from the slice's initial state.
     */
    void startSlice() {
        startRow(null);
    }

    /**
     * Copies the row's current CABAC context states.
     *
     * @param destination receives the states
     */
    void saveRowStates(byte[] destination) {
        if (cabacMode) {
            System.arraycopy(rowStates.states, 0, destination, 0, CabacTables.CONTEXTS);
        }
    }

    /**
     * Decides, codes and reconstructs one macroblock of the current row and records it in the
     * picture tables.
     *
     * <p>The returned macroblock is one of the analyzer's candidates and stays valid until the
     * next call. Its reconstruction is in the picture; every table entry except the quantizer,
     * which depends on the raster order, is committed.
     *
     * <p>Intra refresh, when the picture has a refresh band, codes the macroblocks of the band as
     * intra macroblocks that do not predict from the samples above-right of the band, and keeps the
     * vectors of the macroblocks left of the band inside the columns the reference picture refreshed.
     *
     * @param mbX the macroblock column
     * @param mbY the macroblock row
     * @param forcePcm whether the macroblock must be coded as {@code I_PCM}
     * @param sliceStart the address of the first macroblock of the macroblock's slice
     * @return the decided macroblock
     */
    Macroblock analyze(int mbX, int mbY, boolean forcePcm, int sliceStart) {
        ctx.moveTo(mbX, mbY, settings.mbWidth(), sliceStart);
        var source = picture.source;
        var rec = picture.reconstruction;
        srcLuma = source.lumaOrigin + mbY * 16 * source.lumaStride + mbX * 16;
        srcChroma = source.chromaOrigin + mbY * 8 * source.chromaStride + mbX * 8;
        recLuma = rec.lumaOrigin + mbY * 16 * rec.lumaStride + mbX * 16;
        recChroma = rec.chromaOrigin + mbY * 8 * rec.chromaStride + mbX * 8;
        qp = picture.mbQp[ctx.address];
        lambdaSad = Lambda.SAD[qp];
        lambdaSsd = Lambda.SSD[qp];
        residual.setTrellis(analysis.trellis() ? lambdaSsd / 256.0 : 0);
        chromaMode = -1;
        search.startMacroblock();
        analyzed8x8 = false;
        rdBestStatesValid = false;
        skipLumaReady = false;
        skipChromaReady = false;
        var refreshing = picture.pSlice && mbX >= picture.refreshStart && mbX < picture.refreshEnd;
        avoidTopRight = refreshing && mbX == picture.refreshEnd - 1 && mbX < settings.mbWidth() - 1;
        refreshLimitX = picture.pSlice && mbX < picture.refreshStart
                ? 64 * (picture.refreshStart - mbX) - REFRESH_MARGIN : Integer.MAX_VALUE;
        search.setHorizontalLimit(refreshLimitX);
        Macroblock chosen;
        if (forcePcm) {
            chosen = preparePcm();
        } else if (refreshing) {
            chosen = decideIntraSlice();
        } else {
            chosen = picture.pSlice ? asSkip(decideInter()) : picture.bSlice ? decideB() : decideIntraSlice();
        }
        return commit(chosen);
    }

    /**
     * Applies the size limit to a decided macroblock, advances the row's rate-estimation state
     * past it and commits it to the picture tables.
     *
     * <p>The winner of a rate-distortion decision takes the context states its own count reached
     * (see {@link #keepBest}) instead of being counted again.
     *
     * @param mb the decided macroblock, reconstructed in the picture
     * @return the macroblock to write, {@code mb} or its {@code I_PCM} replacement
     */
    private Macroblock commit(Macroblock mb) {
        var skip = MbType.isSkip(mb.type);
        var guarded = !skip && mb.type != MbType.I_PCM && worstCaseBits(mb) > SAFE_MB_BITS;
        if (cabacMode) {
            if (mb == rdBest && rdBestStatesValid && !MbType.isSkip(mb.type)) {
                if (guarded && (rdBestLayerCost + 255) >> 8 > CABAC_PCM_BITS) {
                    mb = preparePcm();
                    advanceRowStates(mb);
                } else {
                    System.arraycopy(rdBestStates, 0, rowStates.states, 0, CabacTables.CONTEXTS);
                }
            } else {
                if (guarded) {
                    System.arraycopy(rowStates.states, 0, savedRowStates, 0, CabacTables.CONTEXTS);
                }
                if (advanceRowStates(mb) > CABAC_PCM_BITS && guarded) {
                    System.arraycopy(savedRowStates, 0, rowStates.states, 0, CabacTables.CONTEXTS);
                    mb = preparePcm();
                    advanceRowStates(mb);
                }
            }
        } else if (guarded) {
            counter.reset();
            cavlc.writeMacroblock(counter, pi, ctx, mb, picture.pSlice, picture.refCount, qpDelta(mb),
                    transform8x8Mode);
            if (counter.bitCount() > CAVLC_PCM_BITS) {
                mb = preparePcm();
            }
        }
        if (MbType.isSkip(mb.type)) {
            skipRun++;
            previousQpDeltaNonZero = false;
        } else {
            skipRun = 0;
            if (mb.cbp != 0 || mb.type == MbType.I16x16) {
                previousQpDeltaNonZero = qpDelta(mb) != 0;
                previousQp = mb.qp;
            } else {
                previousQpDeltaNonZero = false;
            }
        }
        pi.commit(ctx.address, mb, previousQp);
        return mb;
    }

    /**
     * Advances the row's CABAC context states past the syntax of a macroblock.
     *
     * @param mb the macroblock
     * @return the estimated size of its macroblock layer in bits
     */
    private int advanceRowStates(Macroblock mb) {
        var states = rowStates;
        if (picture.pSlice || picture.bSlice) {
            CabacWriter.writeSkipFlag(states, pi, ctx, MbType.isSkip(mb.type), picture.bSlice);
        }
        if (MbType.isSkip(mb.type)) {
            return 0;
        }
        states.cost = 0;
        CabacWriter.writeMacroblock(states, pi, ctx, mb, picture.pSlice, picture.bSlice, picture.refCount, qpDelta(mb),
                previousQpDeltaNonZero, transform8x8Mode);
        return (states.cost + 255) >> 8;
    }

    /**
     * Returns the skip candidate in place of a chosen P_L0_16x16 macroblock that carries no
     * residual, uses reference 0 and moves exactly by the skip vector, since both reconstruct
     * the same samples and the skip costs no bits; returns the chosen macroblock otherwise.
     *
     * @param chosen the macroblock chosen for a P slice, reconstructed in the picture
     * @return the macroblock to write
     */
    private Macroblock asSkip(Macroblock chosen) {
        var skip = skipMb;
        if (chosen.type == MbType.P16x16 && chosen.cbp == 0 && chosen.refs[0] == 0
                && chosen.mvX[0] == skip.mvX[0] && chosen.mvY[0] == skip.mvY[0]) {
            return skip;
        }
        return chosen;
    }

    /**
     * Returns the {@code mb_qp_delta} a macroblock signals.
     *
     * @param mb the macroblock
     * @return the difference to the previous quantizer, wrapped to the syntax range, or
     * {@code 0} when the macroblock carries none
     */
    private int qpDelta(Macroblock mb) {
        if (mb.cbp == 0 && mb.type != MbType.I16x16) {
            return 0;
        }
        return clampDelta(mb.qp - previousQp);
    }

    /**
     * Returns a generous upper estimate of a macroblock's coded size.
     *
     * @param mb the macroblock
     * @return the estimate in bits
     */
    private static int worstCaseBits(Macroblock mb) {
        var bits = 160;
        var chroma = mb.cbp >> 4;
        if ((mb.cbp & 15) != 0) {
            bits += levelBits(mb.luma, 256);
        }
        if (mb.type == MbType.I16x16) {
            bits += levelBits(mb.lumaDc, 16);
        }
        if (chroma == 2) {
            bits += levelBits(mb.chroma, 128);
        }
        if (chroma != 0) {
            bits += levelBits(mb.chromaDc, 8);
        }
        return bits;
    }

    /**
     * Returns a generous estimate of the bits of a run of levels.
     *
     * @param levels the quantized levels of the block
     * @param count the number of levels
     * @return the estimate in bits
     */
    private static int levelBits(int[] levels, int count) {
        var bits = 0;
        for (var i = 0; i < count; i++) {
            var level = levels[i];
            if (level != 0) {
                bits += 8 + 2 * (32 - Integer.numberOfLeadingZeros(Math.abs(level)));
            }
        }
        return bits;
    }

    /**
     * Replaces the current macroblock's reconstruction with its source samples and returns the
     * {@code I_PCM} macroblock that codes them.
     *
     * @return the {@code I_PCM} macroblock
     */
    private Macroblock preparePcm() {
        var source = picture.source;
        var rec = picture.reconstruction;
        pixels.copy(source.y, srcLuma, source.lumaStride, rec.y, recLuma, rec.lumaStride, 16, 16);
        pixels.copy(source.u, srcChroma, source.chromaStride, rec.u, recChroma, rec.chromaStride, 8, 8);
        pixels.copy(source.v, srcChroma, source.chromaStride, rec.v, recChroma, rec.chromaStride, 8, 8);
        pcmMb.clearResidual();
        pcmMb.qp = previousQp;
        pcmMb.chromaMode = 0;
        return pcmMb;
    }

    /**
     * Decides and codes a macroblock of an I slice, leaving its reconstruction in the picture.
     *
     * @return the chosen macroblock
     */
    private Macroblock decideIntraSlice() {
        var i16 = analyzeIntra16x16();
        if (analysis.rdMode() >= 1) {
            codeIntra16x16(i16);
            rdCost(i16);
            keepBest(i16);
            tryIntraNxNRd();
            restoreReconstruction();
            return rdBest;
        }
        var nxn = analyzeIntraNxNBest(i16.cost);
        if (nxn.cost < i16.cost) {
            codeIntraChroma(nxn);
            return nxn;
        }
        codeIntra16x16(i16);
        return i16;
    }

    /**
     * The quarter samples that the horizontal vector limit of intra refresh keeps from the first
     * column a reference picture did not refresh: 22 luma samples, for the 16 samples of the
     * macroblock, the 3 to the right that the six-tap filter reads and the 3 left of a band edge that
     * the deblocking filter alters from the samples of an unrefreshed column, less 3 quarter samples
     * of fractional position.
     */
    private static final int REFRESH_MARGIN = 85;

    /**
     * Analyzes Intra_4x4, and Intra_8x8 when enabled, leaving the better one's luma
     * reconstruction in the picture.
     *
     * <p>Without the 8x8 transform the caller only uses the Intra_4x4 candidate when its cost is
     * below {@code limit}, so its analysis stops as soon as its cost reaches the limit; the
     * returned candidate then costs at least {@code limit} and its reconstruction is partial,
     * which the caller overwrites when it codes the winner.
     *
     * @param limit the cost the candidate must stay below to be used
     * @return the better of the two candidates by SATD cost
     */
    private Macroblock analyzeIntraNxNBest(long limit) {
        if (!transform8x8Mode || avoidTopRight) {
            return analyzeIntraNxN(i4Mb, false, limit);
        }
        var i4 = analyzeIntraNxN(i4Mb, false);
        var i4Cost = i4.cost;
        var i8 = analyzeIntraNxN(i8Mb, true);
        if (i8.cost < i4Cost) {
            return i8;
        }
        return analyzeIntraNxN(i4Mb, false);
    }

    /**
     * Codes Intra_4x4 and Intra_8x8 candidates and keeps them in {@link #rdBest} when their
     * rate-distortion cost is lower.
     */
    private void tryIntraNxNRd() {
        var i4 = analyzeIntraNxN(i4Mb, false);
        codeIntraChroma(i4);
        rdCost(i4, rdBest.cost);
        if (i4.cost < rdBest.cost) {
            keepBest(i4);
        }
        if (transform8x8Mode && !avoidTopRight) {
            var i8 = analyzeIntraNxN(i8Mb, true);
            codeIntraChroma(i8);
            rdCost(i8, rdBest.cost);
            if (i8.cost < rdBest.cost) {
                keepBest(i8);
            }
        }
    }

    /**
     * Returns the intra chroma prediction mode of the current macroblock, deciding it by SATD on
     * first use and filling the four chroma predictions.
     *
     * @return the chosen mode
     */
    private int chromaMode() {
        if (chromaMode >= 0) {
            return chromaMode;
        }
        var source = picture.source;
        var rec = picture.reconstruction;
        var stride = rec.chromaStride;
        var bestMode = 0;
        var bestCost = Integer.MAX_VALUE;
        for (var mode = 0; mode < 4; mode++) {
            if (!chromaModeAvailable(mode)) {
                continue;
            }
            var prediction = predictionsChroma[mode];
            intra.predictChroma(mode, rec.u, recChroma, stride, ctx.availB, ctx.availA, prediction, 0, 8);
            intra.predictChroma(mode, rec.v, recChroma, stride, ctx.availB, ctx.availA, prediction, 64, 8);
            var cost = pixels.satd(source.u, srcChroma, source.chromaStride, prediction, 0, 8, 8, 8)
                    + pixels.satd(source.v, srcChroma, source.chromaStride, prediction, 64, 8, 8, 8)
                    + ((lambdaSad * MotionSearch.seBits(mode) + 128) >> 8);
            if (cost < bestCost) {
                bestCost = cost;
                bestMode = mode;
            }
        }
        chromaMode = bestMode;
        return bestMode;
    }

    /**
     * Returns whether a chroma prediction mode's neighbors are available.
     *
     * @param mode the chroma mode
     * @return whether the mode can be used
     */
    private boolean chromaModeAvailable(int mode) {
        return switch (mode) {
            case IntraPredictor.CHROMA_DC -> true;
            case IntraPredictor.CHROMA_HORIZONTAL -> ctx.availA;
            case IntraPredictor.CHROMA_VERTICAL -> ctx.availB;
            default -> ctx.availA && ctx.availB && ctx.availD;
        };
    }

    /**
     * Chooses the Intra_16x16 mode by luma SATD, filling the candidate's mode and cost.
     *
     * @return the candidate
     */
    private Macroblock analyzeIntra16x16() {
        var mb = i16Mb;
        mb.type = MbType.I16x16;
        mb.transform8x8 = false;
        mb.qp = qp;
        var source = picture.source;
        var rec = picture.reconstruction;
        var bestCost = Long.MAX_VALUE;
        var headerBits = picture.pSlice || picture.bSlice ? 9 : 5;
        for (var mode = 0; mode < 4; mode++) {
            if (!IntraPredictor.isAvailable16x16(mode, ctx.availB, ctx.availA, ctx.availD)) {
                continue;
            }
            intra.predict16x16(mode, rec.y, recLuma, rec.lumaStride, ctx.availB, ctx.availA, predictions16[mode], 0,
                    16);
            var cost = pixels.satd(source.y, srcLuma, source.lumaStride, predictions16[mode], 0, 16, 16, 16)
                    + ((lambdaSad * (headerBits + (mode == 0 ? 0 : 1))) >> 8);
            if (cost < bestCost) {
                bestCost = cost;
                mb.intra16x16Mode = mode;
            }
        }
        mb.cost = bestCost;
        return mb;
    }

    /**
     * Codes an Intra_16x16 candidate: luma residual and chroma, reconstructed into the picture.
     *
     * @param mb the candidate, whose mode is chosen
     */
    private void codeIntra16x16(Macroblock mb) {
        var source = picture.source;
        var rec = picture.reconstruction;
        mb.qp = qp;
        pixels.copy(predictions16[mb.intra16x16Mode], 0, 16, rec.y, recLuma, rec.lumaStride, 16, 16);
        residual.lumaIntra16x16(mb, source.y, srcLuma, source.lumaStride, rec.y, recLuma, rec.lumaStride, qp);
        codeIntraChroma(mb);
    }

    /**
     * Codes the chroma of an intra candidate with the macroblock's chroma mode.
     *
     * @param mb the candidate
     */
    private void codeIntraChroma(Macroblock mb) {
        var source = picture.source;
        var rec = picture.reconstruction;
        var mode = chromaMode();
        mb.chromaMode = mode;
        var prediction = predictionsChroma[mode];
        pixels.copy(prediction, 0, 8, rec.u, recChroma, rec.chromaStride, 8, 8);
        pixels.copy(prediction, 64, 8, rec.v, recChroma, rec.chromaStride, 8, 8);
        residual.chroma(mb, source.u, source.v, srcChroma, source.chromaStride, rec.u, rec.v, recChroma,
                rec.chromaStride, rec.u, rec.v, recChroma, rec.chromaStride, Tables.chromaQp(qp, 0), true);
    }

    /**
     * Chooses the Intra_4x4 or Intra_8x8 modes block by block and codes the luma, leaving the
     * luma reconstruction in the picture.
     *
     * @param mb the candidate to fill
     * @param eight whether to use 8x8 blocks
     * @return the candidate, with its SATD-based cost
     */
    private Macroblock analyzeIntraNxN(Macroblock mb, boolean eight) {
        return analyzeIntraNxN(mb, eight, Long.MAX_VALUE);
    }

    /**
     * Chooses the Intra_4x4 or Intra_8x8 modes block by block and codes the luma, leaving the
     * luma reconstruction in the picture, unless the cost reaches a limit first.
     *
     * <p>The cost only grows from block to block, so once it reaches {@code limit} the candidate
     * cannot win a comparison against a cost of {@code limit}: the analysis stops there, leaving
     * the candidate with a cost of at least {@code limit} and a partial reconstruction.
     *
     * @param mb the candidate to fill
     * @param eight whether to use 8x8 blocks
     * @param limit the cost at which the analysis stops
     * @return the candidate, with its SATD-based cost
     */
    private Macroblock analyzeIntraNxN(Macroblock mb, boolean eight, long limit) {
        var source = picture.source;
        var rec = picture.reconstruction;
        var stride = rec.lumaStride;
        mb.type = eight ? MbType.I8x8 : MbType.I4x4;
        mb.transform8x8 = eight;
        mb.qp = qp;
        long total = (lambdaSad * (picture.pSlice || picture.bSlice ? 6L : 2L)) >> 8;
        var blocks = eight ? 4 : 16;
        var size = eight ? 8 : 4;
        var cbp = 0;
        for (var blk = 0; blk < blocks; blk++) {
            int x4;
            int y4;
            if (eight) {
                x4 = (blk & 1) * 2;
                y4 = (blk >> 1) * 2;
            } else {
                x4 = H264Tables.BLOCK_X[blk];
                y4 = H264Tables.BLOCK_Y[blk];
            }
            var hasTop = y4 > 0 || ctx.availB;
            var hasLeft = x4 > 0 || ctx.availA;
            boolean hasTopLeft;
            if (x4 > 0 && y4 > 0) {
                hasTopLeft = true;
            } else if (y4 > 0) {
                hasTopLeft = ctx.availA;
            } else if (x4 > 0) {
                hasTopLeft = ctx.availB;
            } else {
                hasTopLeft = ctx.availD;
            }
            var hasTopRight = eight ? topRight8x8(blk) : topRight4x4(x4, y4);
            var predicted = Neighbors.predictedIntraMode(pi, ctx, mb, x4, y4);
            var recOffset = recLuma + y4 * 4 * stride + x4 * 4;
            var srcOffset = srcLuma + y4 * 4 * source.lumaStride + x4 * 4;
            nxnEight = eight;
            nxnAvoidTopRight = avoidTopRight && x4 == 3 && y4 == 0;
            nxnTop = hasTop;
            nxnTopRight = hasTopRight;
            nxnLeft = hasLeft;
            nxnTopLeft = hasTopLeft;
            nxnPredicted = predicted;
            nxnRecOffset = recOffset;
            nxnSrcOffset = srcOffset;
            nxnTried = 0;
            nxnHadamardReady = false;
            nxnBestMode = -1;
            nxnBestCost = Integer.MAX_VALUE;
            if (analysis.intraModeSearch() >= 2) {
                for (var mode = 0; mode < 9; mode++) {
                    tryNxNMode(mode);
                }
            } else {
                tryNxNMode(IntraPredictor.DC);
                tryNxNMode(IntraPredictor.VERTICAL);
                tryNxNMode(IntraPredictor.HORIZONTAL);
                tryNxNMode(predicted);
                if (analysis.intraModeSearch() == 1 && nxnBestMode != IntraPredictor.DC) {
                    var position = ANGLE_POSITION[nxnBestMode];
                    var before = nxnBestMode;
                    tryNxNMode(position > 0 ? ANGLES[position - 1] : -1);
                    tryNxNMode(position < 7 ? ANGLES[position + 1] : -1);
                    if (nxnBestMode != before) {
                        var moved = ANGLE_POSITION[nxnBestMode];
                        var next = moved + (moved - position);
                        tryNxNMode(next >= 0 && next < 8 ? ANGLES[next] : -1);
                    }
                }
            }
            var bestMode = nxnBestMode;
            mb.intraModes[blk] = bestMode;
            total += nxnBestCost;
            if (total >= limit) {
                mb.cost = total;
                return mb;
            }
            if (eight) {
                intra.predict8x8(bestMode, rec.y, recOffset, stride, hasTop, hasTopRight, hasLeft, hasTopLeft,
                        rec.y, recOffset, stride);
                if (residual.lumaIntra8x8(mb, blk, source.y, srcOffset, source.lumaStride, rec.y, recOffset, stride,
                        qp) != 0) {
                    cbp |= 1 << blk;
                }
            } else {
                intra.predict4x4(bestMode, rec.y, recOffset, stride, hasTop, hasTopRight, hasLeft, hasTopLeft,
                        rec.y, recOffset, stride);
                if (residual.lumaIntra4x4(mb, blk, source.y, srcOffset, source.lumaStride, rec.y, recOffset, stride,
                        qp) != 0) {
                    cbp |= 1 << (blk >> 2);
                }
            }
        }
        if (!eight) {
            for (var b8 = 0; b8 < 4; b8++) {
                if ((cbp & (1 << b8)) == 0) {
                    for (var i = 0; i < 4; i++) {
                        mb.lumaCount[b8 * 4 + i] = 0;
                    }
                }
            }
        }
        mb.cbp = cbp;
        mb.cost = total;
        return mb;
    }

    /**
     * Evaluates one Intra_4x4 or Intra_8x8 mode of the block described by the {@code nxn}
     * fields, keeping it when its SATD cost is the lowest so far; unavailable, repeated and
     * negative modes are ignored.
     *
     * @param mode the prediction mode, or {@code -1} for none
     */
    private void tryNxNMode(int mode) {
        if (mode < 0 || (nxnTried & (1 << mode)) != 0
                || !IntraPredictor.isAvailableNxN(mode, nxnTop, nxnLeft, nxnTopLeft)
                || nxnAvoidTopRight && (mode == 3 || mode == 7)) {
            return;
        }
        nxnTried |= 1 << mode;
        var rec = picture.reconstruction;
        var source = picture.source;
        int satd;
        if (nxnEight) {
            intra.predict8x8(mode, rec.y, nxnRecOffset, rec.lumaStride, nxnTop, nxnTopRight, nxnLeft, nxnTopLeft,
                    blockPrediction, 0, 8);
            satd = pixels.satd(source.y, nxnSrcOffset, source.lumaStride, blockPrediction, 0, 8, 8, 8);
        } else if (mode <= IntraPredictor.DC) {
            satd = flatModeSatd(mode);
        } else {
            intra.predict4x4(mode, rec.y, nxnRecOffset, rec.lumaStride, nxnTop, nxnTopRight, nxnLeft, nxnTopLeft,
                    blockPrediction, 0, 8);
            satd = pixels.satd(source.y, nxnSrcOffset, source.lumaStride, blockPrediction, 0, 8, 4, 4);
        }
        var cost = satd + ((lambdaSad * (mode == nxnPredicted ? 1 : 4)) >> 8);
        if (cost < nxnBestCost) {
            nxnBestCost = cost;
            nxnBestMode = mode;
        }
    }

    /**
     * Returns the SATD of the 4x4 block under evaluation against its vertical, horizontal or DC
     * Intra_4x4 prediction, without forming the prediction.
     *
     * <p>The Hadamard transform of these predictions is zero except for its first row (vertical,
     * four times the transform of the samples above), its first column (horizontal, four times
     * the transform of the samples on the left) or its DC coefficient (sixteen times the DC
     * value), so the SATD follows from the source's coefficients by correcting those few terms.
     * The result equals the SATD of the predicted block.
     *
     * @param mode {@link IntraPredictor#VERTICAL}, {@link IntraPredictor#HORIZONTAL} or
     * {@link IntraPredictor#DC}, available for the block
     * @return the SATD
     */
    private int flatModeSatd(int mode) {
        var h = nxnHadamard;
        if (!nxnHadamardReady) {
            var source = picture.source;
            PixelKernels.hadamard4x4(source.y, nxnSrcOffset, source.lumaStride, h);
            var sum = 0;
            for (var i = 0; i < 16; i++) {
                sum += Math.abs(h[i]);
            }
            nxnHadamardSum = sum;
            nxnHadamardReady = true;
        }
        var rec = picture.reconstruction;
        var plane = rec.y;
        var stride = rec.lumaStride;
        var offset = nxnRecOffset;
        var total = nxnHadamardSum;
        if (mode == IntraPredictor.VERTICAL) {
            var t = offset - stride;
            var s01 = (plane[t] & 0xFF) + (plane[t + 1] & 0xFF);
            var d01 = (plane[t] & 0xFF) - (plane[t + 1] & 0xFF);
            var s23 = (plane[t + 2] & 0xFF) + (plane[t + 3] & 0xFF);
            var d23 = (plane[t + 2] & 0xFF) - (plane[t + 3] & 0xFF);
            total += Math.abs(h[0] - 4 * (s01 + s23)) - Math.abs(h[0])
                    + Math.abs(h[1] - 4 * (s01 - s23)) - Math.abs(h[1])
                    + Math.abs(h[2] - 4 * (d01 - d23)) - Math.abs(h[2])
                    + Math.abs(h[3] - 4 * (d01 + d23)) - Math.abs(h[3]);
        } else if (mode == IntraPredictor.HORIZONTAL) {
            var l0 = plane[offset - 1] & 0xFF;
            var l1 = plane[offset + stride - 1] & 0xFF;
            var l2 = plane[offset + 2 * stride - 1] & 0xFF;
            var l3 = plane[offset + 3 * stride - 1] & 0xFF;
            var s01 = l0 + l1;
            var d01 = l0 - l1;
            var s23 = l2 + l3;
            var d23 = l2 - l3;
            total += Math.abs(h[0] - 4 * (s01 + s23)) - Math.abs(h[0])
                    + Math.abs(h[4] - 4 * (s01 - s23)) - Math.abs(h[4])
                    + Math.abs(h[8] - 4 * (d01 - d23)) - Math.abs(h[8])
                    + Math.abs(h[12] - 4 * (d01 + d23)) - Math.abs(h[12]);
        } else {
            var sum = 0;
            if (nxnTop) {
                var t = offset - stride;
                sum += (plane[t] & 0xFF) + (plane[t + 1] & 0xFF) + (plane[t + 2] & 0xFF) + (plane[t + 3] & 0xFF);
            }
            if (nxnLeft) {
                sum += (plane[offset - 1] & 0xFF) + (plane[offset + stride - 1] & 0xFF)
                        + (plane[offset + 2 * stride - 1] & 0xFF) + (plane[offset + 3 * stride - 1] & 0xFF);
            }
            int dc;
            if (nxnTop && nxnLeft) {
                dc = (sum + 4) >> 3;
            } else if (nxnTop || nxnLeft) {
                dc = (sum + 2) >> 2;
            } else {
                dc = 128;
            }
            total += Math.abs(h[0] - 16 * dc) - Math.abs(h[0]);
        }
        return total >> 1;
    }

    /**
     * Returns whether the samples above-right of a 4x4 block are available.
     *
     * @param x4 the block column
     * @param y4 the block row
     * @return whether they are available
     */
    private boolean topRight4x4(int x4, int y4) {
        if (y4 == 0) {
            return x4 < 3 ? ctx.availB : ctx.availC;
        }
        if (x4 == 3) {
            return false;
        }
        return H264Tables.BLOCK_RASTER[(y4 - 1) * 4 + x4 + 1] < H264Tables.BLOCK_RASTER[y4 * 4 + x4];
    }

    /**
     * Returns whether the samples above-right of an 8x8 block are available.
     *
     * @param b8 the {@code luma8x8BlkIdx}
     * @return whether they are available
     */
    private boolean topRight8x8(int b8) {
        return switch (b8) {
            case 0 -> ctx.availB;
            case 1 -> ctx.availC;
            case 2 -> true;
            default -> false;
        };
    }

    /**
     * Decides and codes a macroblock of a P slice, leaving its reconstruction in the picture.
     *
     * <p>A macroblock whose skip residual quantizes to nothing is skipped outright when that
     * residual is small; when it is larger, only the 16x16 partitioning is searched and compared
     * with the skip, since smaller partitions and intra prediction rarely improve on a
     * prediction the quantizer already cannot tell from the source. Such a macroblock is skipped
     * without coding the candidate when the search returns to the skip prediction or barely
     * improves on it.
     *
     * @return the chosen macroblock
     */
    private Macroblock decideInter() {
        var skip = prepareSkip();
        var skipUsable = insideWindow(skip.mvX[0], skip.mvY[0]);
        if (skipUsable) {
            var probe = probeSkip();
            if (probe == SKIP_FREE) {
                finishSkip(skip);
                return skip;
            }
            if (probe == SKIP_STATIC) {
                var p16 = analyze16x16();
                if (movesLikeSkip(p16, skip) || skipNearlyAsGood(p16, skip)) {
                    finishSkip(skip);
                    return skip;
                }
                return analysis.rdMode() >= 1 ? decideInterRd(skip, true, p16, null) : codeInterOrSkip(skip, true, p16);
            }
        }
        var p16 = analyze16x16();
        Macroblock bestInter = p16;
        var worthSplitting = p16.distortion > splitThreshold();
        if (analysis.partitions() >= 2 && worthSplitting) {
            var p8x8 = analyze8x8();
            if (p8x8.cost < bestInter.cost) {
                bestInter = p8x8;
            }
            if (p8x8.cost < p16.cost) {
                bestInter = tryRectangles(bestInter, true);
            }
        } else if (analysis.partitions() == 1 && worthSplitting) {
            bestInter = tryRectangles(bestInter, false);
        }
        if (analysis.rdMode() <= 1 && 2L * picture.lowresInter[ctx.address] < picture.lowresIntra[ctx.address]) {
            if (analysis.rdMode() == 1) {
                return decideInterRd(skip, skipUsable, bestInter, null);
            }
            return codeInterOrSkip(skip, skipUsable, bestInter);
        }
        var i16 = analyzeIntra16x16();
        if (analysis.rdMode() >= 1) {
            return decideInterRd(skip, skipUsable, bestInter, i16);
        }
        Macroblock intraBest = i16;
        var nxnLimit = analysis.intraModeSearch() >= 1 ? bestInter.cost * 5 / 4 : bestInter.cost;
        if (analysis.intra4x4() && i16.cost < nxnLimit) {
            var nxn = analyzeIntraNxNBest(intraBest.cost);
            if (nxn.cost < intraBest.cost) {
                intraBest = nxn;
            }
        }
        if (intraBest.cost < bestInter.cost) {
            if (intraBest == i16) {
                codeIntra16x16(i16);
            } else {
                codeIntraChroma(intraBest);
            }
            return intraBest;
        }
        return codeInterOrSkip(skip, skipUsable, bestInter);
    }

    /**
     * Returns whether a {@code P_L0_16x16} candidate predicts exactly like the skip candidate:
     * reference 0 and the skip vector.
     *
     * <p>When the skip residual quantizes to nothing, such a candidate codes no residual either
     * and is written as the skip it equals (see {@link #asSkip}), so its analysis can stop.
     *
     * @param p16 the searched {@code P_L0_16x16} candidate
     * @param skip the prepared skip candidate
     * @return whether both predict from the same samples
     */
    private static boolean movesLikeSkip(Macroblock p16, Macroblock skip) {
        return p16.refs[0] == 0 && p16.mvX[0] == skip.mvX[0] && p16.mvY[0] == skip.mvY[0];
    }

    /**
     * Returns whether the skip prediction's SATD cost comes within
     * {@link #STATIC_SKIP_MARGIN_BITS} weighted bits of a {@code P_L0_16x16} candidate's.
     *
     * <p>The skip vector is costed against the candidate's predictor when both use reference 0
     * and as free otherwise. For a macroblock whose skip residual quantizes to nothing, a
     * candidate that barely improves on the skip prediction almost never wins the final decision,
     * so the analysis stops and the macroblock is skipped.
     *
     * @implNote This implementation's margin was chosen on the Xiph test sequences, where skipping
     * these macroblocks changes the compression by less than 0.05% (Bjontegaard rate) while
     * sparing their inter coding and rate-distortion comparison.
     *
     * @param p16 the searched {@code P_L0_16x16} candidate
     * @param skip the prepared skip candidate
     * @return whether the candidate is not worth analyzing further
     */
    private boolean skipNearlyAsGood(Macroblock p16, Macroblock skip) {
        predictSkip(false);
        var source = picture.source;
        var satd = pixels.satd(source.y, srcLuma, source.lumaStride, skipLuma, 0, 16, 16, 16);
        var mvpX = skip.mvX[0];
        var mvpY = skip.mvY[0];
        if (p16.refs[0] == 0) {
            mvpX = p16.mvX[0] - p16.mvdX[0];
            mvpY = p16.mvY[0] - p16.mvdY[0];
        }
        long skipCost = satd + ((lambdaSad * (MotionSearch.mvBits(skip.mvX[0] - mvpX)
                + MotionSearch.mvBits(skip.mvY[0] - mvpY)) + 128) >> 8);
        var candidateCost = p16.cost - (lambdaSad >> 8);
        return (skipCost - candidateCost) * 256 < (long) STATIC_SKIP_MARGIN_BITS * lambdaSad;
    }

    /**
     * Codes the best inter candidate of a SATD decision and returns it, or returns the skip
     * candidate, reconstructed, when skipping is cheaper in rate-distortion terms.
     *
     * <p>The motion search compares predictions by SATD, which ignores that a skipped macroblock
     * costs a single bit; at coarse quantizers a small residual is often worth dropping. The
     * coded candidate's exact cost is therefore compared with the distortion of the bare skip
     * prediction plus one bit. The coded candidate's bits are only counted when its distortion
     * plus the fewest bits an inter macroblock can take does not already exceed that.
     *
     * @param skip the skip candidate, prepared
     * @param skipUsable whether the skip vector stays within the reference padding
     * @param bestInter the best inter candidate by SATD cost
     * @return the macroblock to write, reconstructed in the picture
     */
    private Macroblock codeInterOrSkip(Macroblock skip, boolean skipUsable, Macroblock bestInter) {
        codeInter(bestInter);
        if (!skipUsable) {
            return bestInter;
        }
        var source = picture.source;
        var rec = picture.reconstruction;
        predictSkip(true);
        var skipCost = (pixels.ssd(source.y, srcLuma, source.lumaStride, skipLuma, 0, 16, 16, 16)
                + pixels.ssd(source.u, srcChroma, source.chromaStride, skipCb, 0, 8, 8, 8)
                + pixels.ssd(source.v, srcChroma, source.chromaStride, skipCr, 0, 8, 8, 8)) * 256 + lambdaSsd;
        var interSsd = pixels.ssd(source.y, srcLuma, source.lumaStride, rec.y, recLuma, rec.lumaStride, 16, 16)
                + pixels.ssd(source.u, srcChroma, source.chromaStride, rec.u, recChroma, rec.chromaStride, 8, 8)
                + pixels.ssd(source.v, srcChroma, source.chromaStride, rec.v, recChroma, rec.chromaStride, 8, 8);
        if (skipCost < interSsd * 256 + lambdaSsd * (cabacMode ? 0 : CAVLC_MIN_INTER_BITS)) {
            finishSkip(skip);
            return skip;
        }
        var bits = countBits(bestInter);
        bestInter.distortion = interSsd;
        bestInter.bits = bits;
        bestInter.cost = interSsd * 256 + lambdaSsd * bits;
        if (skipCost < bestInter.cost) {
            finishSkip(skip);
            return skip;
        }
        return bestInter;
    }

    /**
     * Returns the 16x16 SATD above which smaller partitions and further references are searched.
     *
     * @return the threshold, 128 quantizer steps
     */
    private int splitThreshold() {
        return SPLIT_THRESHOLD[qp];
    }

    /**
     * Returns the 8x8 partition distortion below which the sub-8x8 partitionings are not searched.
     *
     * @return the SATD threshold
     */
    private int subPartitionThreshold() {
        return SUB_PARTITION_THRESHOLD[qp];
    }

    /**
     * Searches the 16x8 and 8x16 partitionings and returns the best inter candidate.
     *
     * @param bestInter the best candidate so far
     * @param fromEightByEight whether the 8x8 vectors are available as search candidates
     * @return the best candidate
     */
    private Macroblock tryRectangles(Macroblock bestInter, boolean fromEightByEight) {
        var p16x8 = analyzeRectangles(p16x8Mb, true, fromEightByEight);
        if (p16x8.cost < bestInter.cost) {
            bestInter = p16x8;
        }
        var p8x16 = analyzeRectangles(p8x16Mb, false, fromEightByEight);
        if (p8x16.cost < bestInter.cost) {
            bestInter = p8x16;
        }
        return bestInter;
    }

    /**
     * Chooses between the skip, inter and intra candidates by rate-distortion cost.
     *
     * @param skip the skip candidate, prepared
     * @param skipUsable whether the skip vector stays within the reference padding
     * @param bestInter the best inter candidate by SATD cost
     * @param i16 the Intra_16x16 candidate with its SATD cost, or {@code null} when intra
     * prediction is not worth trying
     * @return the chosen macroblock, reconstructed in the picture
     */
    private Macroblock decideInterRd(Macroblock skip, boolean skipUsable, Macroblock bestInter, Macroblock i16) {
        var interSatdCost = bestInter.cost;
        var intraSatdCost = i16 == null ? Long.MAX_VALUE : i16.cost;
        codeInter(bestInter);
        rdCost(bestInter);
        keepBest(bestInter);
        if (skipUsable) {
            finishSkip(skip);
            rdCost(skip, rdBest.cost);
            if (skip.cost < rdBest.cost) {
                keepBest(skip);
            }
        }
        if (analysis.rdMode() >= 2) {
            tryInterRd(p16Mb, bestInter);
            if (analyzed8x8) {
                tryInterRd(p8x8Mb, bestInter);
            }
        }
        if (transform8x8Mode && rdBest.type != MbType.P_SKIP && !MbType.isIntra(rdBest.type)
                && CavlcWriter.allSub8x8(rdBest)) {
            var candidate = switch (rdBest.type) {
                case MbType.P16x16 -> p16Mb;
                case MbType.P16x8 -> p16x8Mb;
                case MbType.P8x16 -> p8x16Mb;
                default -> p8x8Mb;
            };
            candidate.transform8x8 = true;
            codeInter(candidate);
            rdCost(candidate, rdBest.cost);
            if (candidate.cost < rdBest.cost) {
                keepBest(candidate);
            }
            candidate.transform8x8 = false;
        }
        var intraLimit = analysis.rdMode() >= 2 ? 2 * interSatdCost : interSatdCost + interSatdCost / 4;
        if (intraSatdCost < intraLimit) {
            codeIntra16x16(i16);
            rdCost(i16, rdBest.cost);
            if (i16.cost < rdBest.cost) {
                keepBest(i16);
            }
            if (analysis.intra4x4() && (analysis.rdMode() >= 2 || intraSatdCost < interSatdCost)) {
                tryIntraNxNRd();
            }
        }
        restoreReconstruction();
        return rdBest;
    }

    /**
     * Codes another inter candidate and keeps it in {@link #rdBest} when its rate-distortion
     * cost is lower.
     *
     * @param candidate the inter macroblock decision to evaluate
     * @param coded the candidate already evaluated, skipped when equal
     */
    private void tryInterRd(Macroblock candidate, Macroblock coded) {
        if (candidate == coded) {
            return;
        }
        codeInter(candidate);
        rdCost(candidate, rdBest.cost);
        if (candidate.cost < rdBest.cost) {
            keepBest(candidate);
        }
    }

    /**
     * Computes the exact rate-distortion cost of a candidate whose reconstruction is in the
     * picture, storing it in the candidate's {@link Macroblock#cost}.
     *
     * @param mb the candidate
     */
    private void rdCost(Macroblock mb) {
        rdCost(mb, Long.MAX_VALUE);
    }

    /**
     * Computes the rate-distortion cost of a candidate whose reconstruction is in the picture
     * like {@link #rdCost(Macroblock)}, except that a candidate whose distortion alone reaches a
     * bound gets that distortion as its cost, without counting its bits, since it cannot beat a
     * candidate of that cost.
     *
     * @param mb the candidate
     * @param bound the cost the candidate must stay below to matter
     */
    private void rdCost(Macroblock mb, long bound) {
        var source = picture.source;
        var rec = picture.reconstruction;
        var ssd = pixels.ssd(source.y, srcLuma, source.lumaStride, rec.y, recLuma, rec.lumaStride, 16, 16)
                + pixels.ssd(source.u, srcChroma, source.chromaStride, rec.u, recChroma, rec.chromaStride, 8, 8)
                + pixels.ssd(source.v, srcChroma, source.chromaStride, rec.v, recChroma, rec.chromaStride, 8, 8);
        mb.distortion = ssd;
        if (ssd * 256 >= bound) {
            mb.bits = 0;
            mb.cost = ssd * 256;
            return;
        }
        var bits = countBits(mb);
        mb.bits = bits;
        mb.cost = ssd * 256 + lambdaSsd * bits;
    }

    /**
     * Returns the number of bits a candidate would take in the slice data.
     *
     * @param mb the candidate
     * @return the bit count, rounded
     */
    private int countBits(Macroblock mb) {
        var delta = qpDelta(mb);
        if (cabacMode) {
            estimator.copyStates(rowStates);
            estimator.cost = 0;
            if (picture.pSlice || picture.bSlice) {
                CabacWriter.writeSkipFlag(estimator, pi, ctx, MbType.isSkip(mb.type), picture.bSlice);
            }
            countedFlagCost = estimator.cost;
            if (!MbType.isSkip(mb.type)) {
                CabacWriter.writeMacroblock(estimator, pi, ctx, mb, picture.pSlice, picture.bSlice, picture.refCount, delta,
                        previousQpDeltaNonZero, transform8x8Mode);
            }
            return (estimator.cost + 128) >> 8;
        }
        if (mb.type == MbType.P_SKIP) {
            return 1;
        }
        counter.reset();
        if (picture.pSlice) {
            counter.writeUe(skipRun);
        }
        cavlc.writeMacroblock(counter, pi, ctx, mb, picture.pSlice, picture.refCount, delta, transform8x8Mode);
        return (int) counter.bitCount();
    }

    /**
     * Wraps a quantizer difference into the range of {@code mb_qp_delta}.
     *
     * @param delta the difference
     * @return the wrapped difference, from -26 to 25
     */
    private static int clampDelta(int delta) {
        if (delta < -26) {
            return delta + 52;
        }
        return delta > 25 ? delta - 52 : delta;
    }

    /**
     * Makes a candidate whose rate-distortion cost {@link #rdCost} just counted in full the best
     * one: copies its decision to {@link #rdBest}, saves its reconstruction and, with CABAC,
     * keeps the row context states its count reached, which are the states the row takes on
     * when the candidate is committed.
     *
     * @param candidate the decision to compare with the best one so far
     */
    private void keepBest(Macroblock candidate) {
        rdBest.copyDecisionFrom(candidate);
        saveReconstruction();
        if (cabacMode) {
            System.arraycopy(estimator.states, 0, rdBestStates, 0, CabacTables.CONTEXTS);
            rdBestLayerCost = estimator.cost - countedFlagCost;
            rdBestStatesValid = true;
        }
    }

    /**
     * Saves the current macroblock's reconstruction.
     */
    private void saveReconstruction() {
        var rec = picture.reconstruction;
        pixels.copy(rec.y, recLuma, rec.lumaStride, savedReconstruction, 0, 16, 16, 16);
        pixels.copy(rec.u, recChroma, rec.chromaStride, savedReconstruction, 256, 8, 8, 8);
        pixels.copy(rec.v, recChroma, rec.chromaStride, savedReconstruction, 320, 8, 8, 8);
    }

    /**
     * Restores the reconstruction saved by {@link #saveReconstruction()}.
     */
    private void restoreReconstruction() {
        var rec = picture.reconstruction;
        pixels.copy(savedReconstruction, 0, 16, rec.y, recLuma, rec.lumaStride, 16, 16);
        pixels.copy(savedReconstruction, 256, 8, rec.u, recChroma, rec.chromaStride, 8, 8);
        pixels.copy(savedReconstruction, 320, 8, rec.v, recChroma, rec.chromaStride, 8, 8);
    }

    /**
     * Prepares the {@code P_Skip} candidate: its motion.
     *
     * @return the candidate
     */
    private Macroblock prepareSkip() {
        var mb = skipMb;
        mb.type = MbType.P_SKIP;
        mb.transform8x8 = false;
        mb.qp = qp;
        Arrays.fill(mb.refs, 0);
        var mv = predictor.skipVector(mb);
        mb.setMotion(0, 0, 4, 4, MotionPredictor.x(mv), MotionPredictor.y(mv), 0, 0);
        return mb;
    }

    /**
     * Classifies the skip candidate by its residual: {@link #SKIP_FREE} when it quantizes to
     * nothing and either its luma SAD is at most eight quantizer steps per 4x4 block on average
     * or its chroma SAD guarantees zero chroma levels, {@link #SKIP_STATIC} when it quantizes to
     * nothing otherwise, and {@link #SKIP_CODED} when it does not quantize to nothing.
     *
     * <p>A luma residual within the dead zone of every coefficient is not transformed; otherwise
     * the luma residual is classified with decimation, like an inter residual, from the positions
     * of its non-zero levels ({@link ResidualCoder#probeLumaInter}) without storing them, or by
     * quantizing it when rate-distortion optimized quantization is enabled. Chroma is only
     * quantized when its SAD does not guarantee zero levels.
     *
     * @return the classification
     */
    private int probeSkip() {
        var source = picture.source;
        var rec = picture.reconstruction;
        predictSkip(false);
        var sad = pixels.sad(source.y, srcLuma, source.lumaStride, skipLuma, 0, 16, 16, 16);
        var zeroResidual = sad > SPLIT_THRESHOLD[qp] ? SKIP_STATIC : SKIP_FREE;
        if (sad > ResidualCoder.zeroSad4x4(qp)) {
            boolean lumaCoded;
            if (analysis.trellis()) {
                var probe = p16Mb;
                probe.cbp = 0;
                residual.quantizeLumaInter(probe, source.y, srcLuma, source.lumaStride, skipLuma, 0, 16, qp);
                lumaCoded = (probe.cbp & 15) != 0;
            } else {
                lumaCoded = residual.probeLumaInter(source.y, srcLuma, source.lumaStride, skipLuma, 0, 16, qp);
            }
            if (lumaCoded) {
                return SKIP_CODED;
            }
        }
        predictSkip(true);
        var qpc = Tables.chromaQp(qp, 0);
        var zeroChroma = ResidualCoder.zeroSadChroma(qpc);
        if (pixels.sad(source.u, srcChroma, source.chromaStride, skipCb, 0, 8, 8, 8) <= zeroChroma
                && pixels.sad(source.v, srcChroma, source.chromaStride, skipCr, 0, 8, 8, 8) <= zeroChroma) {
            return SKIP_FREE;
        }
        var probe = p16Mb;
        probe.cbp = 0;
        residual.quantizeChroma(probe, source.u, source.v, srcChroma, source.chromaStride, skipCb, skipCr, 0, 8,
                Tables.chromaQp(qp, 0), false);
        return probe.cbp == 0 ? zeroResidual : SKIP_CODED;
    }

    /**
     * Writes the skip candidate's prediction as the reconstruction.
     *
     * @param skip the skip candidate
     */
    private void finishSkip(Macroblock skip) {
        var rec = picture.reconstruction;
        predictSkip(true);
        pixels.copy(skipLuma, 0, 16, rec.y, recLuma, rec.lumaStride, 16, 16);
        pixels.copy(skipCb, 0, 8, rec.u, recChroma, rec.chromaStride, 8, 8);
        pixels.copy(skipCr, 0, 8, rec.v, recChroma, rec.chromaStride, 8, 8);
        skip.cost = 0;
        skip.distortion = 0;
    }

    /**
     * Builds the prediction of the prepared {@code P_Skip} candidate into the skip buffers,
     * unless they already hold it.
     *
     * @param chroma whether the chroma prediction is needed too
     */
    private void predictSkip(boolean chroma) {
        var skip = skipMb;
        var ref = picture.refs[0];
        var mvx = skip.mvX[0];
        var mvy = skip.mvY[0];
        if (!skipLumaReady) {
            search.predict(ref, ctx.mbX * 16, ctx.mbY * 16, 16, 16, mvx, mvy, skipLuma, 0, 16);
            skipLumaReady = true;
        }
        if (chroma && !skipChromaReady) {
            var offset = ref.chromaOrigin + (ctx.mbY * 8 + (mvy >> 3)) * ref.chromaStride + ctx.mbX * 8 + (mvx >> 3);
            H264Dsp.INSTANCE.chromaMc(ref.u, ref.v, offset, ref.chromaStride, skipCb, skipCr, 0, 8, 8, 8, mvx & 7,
                    mvy & 7);
            skipChromaReady = true;
        }
    }

    /**
     * Returns whether a vector keeps a 16x16 block inside the reference padding.
     *
     * @param mvx the horizontal vector in quarter samples
     * @param mvy the vertical vector in quarter samples
     * @return whether motion compensation stays within the padded planes
     */
    private boolean insideWindow(int mvx, int mvy) {
        var x = ctx.mbX * 16 + (mvx >> 2);
        var y = ctx.mbY * 16 + (mvy >> 2);
        var margin = Frame.LUMA_PAD - 6;
        return x >= -margin && y >= -margin && x + 17 <= settings.codedWidth() + margin
                && y + 17 <= settings.codedHeight() + margin && mvx <= refreshLimitX;
    }

    /**
     * Returns the number of references searched for partitions smaller than 16x16.
     *
     * @return the reference count
     */
    private int subPartitionRefs() {
        return analysis.rdMode() >= 2 ? Math.min(picture.refCount, analysis.refs()) : 1;
    }

    /**
     * Returns a reference index to search for a smaller partition.
     *
     * @param index the position in the search order, below {@link #subPartitionRefs()}
     * @return the reference index
     */
    private int subPartitionRef(int index) {
        if (subPartitionRefs() == 1) {
            return bestRef16;
        }
        return index;
    }

    /**
     * Searches the best 16x16 partition over the reference pictures.
     *
     * @return the {@code P_L0_16x16} candidate with its SATD cost
     */
    private Macroblock analyze16x16() {
        var mb = p16Mb;
        mb.type = MbType.P16x16;
        mb.transform8x8 = false;
        mb.qp = qp;
        var bestCost = Integer.MAX_VALUE;
        var bestRef = 0;
        var bestMv = 0;
        var bestMvp = 0;
        var bestDistortion = 0;
        for (var ref = 0; ref < Math.min(picture.refCount, analysis.refs()); ref++) {
            if (ref > 0 && bestDistortion <= splitThreshold()) {
                break;
            }
            Arrays.fill(mb.refs, ref);
            var mvp = predictor.predict(mb, 0, 0, 4, ref, MotionPredictor.SHAPE_MEDIAN);
            searchPartition(ref, 0, 0, 16, 16, mvp, 0, ref == 0 ? analysis.searchMethod() : analysis.refSearchMethod(),
                    ref == 0 ? analysis.searchRange() : analysis.searchRange() / 2,
                    ref == 0 ? analysis.subpel16() : analysis.subpelOther());
            var cost = search.bestCost + refCost(ref);
            if (cost < bestCost) {
                bestCost = cost;
                bestRef = ref;
                bestMv = MotionPredictor.pack(search.bestX, search.bestY);
                bestMvp = mvp;
                bestDistortion = search.bestDistortion;
            }
        }
        bestRef16 = bestRef;
        Arrays.fill(mb.refs, bestRef);
        var mvx = MotionPredictor.x(bestMv);
        var mvy = MotionPredictor.y(bestMv);
        mb.setMotion(0, 0, 4, 4, mvx, mvy, mvx - MotionPredictor.x(bestMvp), mvy - MotionPredictor.y(bestMvp));
        mb.cost = bestCost + (lambdaSad >> 8);
        mb.distortion = bestDistortion;
        return mb;
    }

    /**
     * Returns the weighted cost of coding a reference index.
     *
     * @param ref the reference index
     * @return the cost
     */
    private int refCost(int ref) {
        var count = picture.refCount;
        if (count <= 1) {
            return 0;
        }
        var bits = count == 2 ? 1 : 2 * (31 - Integer.numberOfLeadingZeros(ref + 1)) + 1;
        return (lambdaSad * bits) >> 8;
    }

    /**
     * Runs the motion search of one partition from its predictor candidates.
     *
     * @param ref the reference index
     * @param x the partition's horizontal offset in the macroblock
     * @param y the partition's vertical offset in the macroblock
     * @param width the partition width
     * @param height the partition height
     * @param mvp the packed predictor
     * @param hintCount the number of extra candidates in {@link #hints}
     * @param method the integer search method, see {@link MotionSearch#searchInteger(int, int)}
     * @param range the integer search radius
     * @param subpel the fractional refinement level
     */
    private void searchPartition(int ref, int x, int y, int width, int height, int mvp, int hintCount, int method,
                                 int range, int subpel) {
        var source = picture.source;
        var frame = picture.refs[ref];
        var blockX = ctx.mbX * 16 + x;
        var blockY = ctx.mbY * 16 + y;
        search.setup(source.y, srcLuma + y * source.lumaStride + x, source.lumaStride, frame, ref, blockX, blockY, width,
                height, mvp, lambdaSad, settings.maxMvY());
        tryCandidate(mvp);
        tryCandidate(0);
        for (var i = 0; i < hintCount; i++) {
            tryCandidate(hints[i]);
        }
        if (ctx.availA) {
            tryCandidate(MotionPredictor.pack(pi.mvX[ctx.addrA * 16 + 3], pi.mvY[ctx.addrA * 16 + 3]));
        }
        if (ctx.availB) {
            tryCandidate(MotionPredictor.pack(pi.mvX[ctx.addrB * 16 + 12], pi.mvY[ctx.addrB * 16 + 12]));
        }
        if (ctx.availC) {
            tryCandidate(MotionPredictor.pack(pi.mvX[ctx.addrC * 16 + 12], pi.mvY[ctx.addrC * 16 + 12]));
        }
        tryCandidate(frame.motion[ctx.address]);
        if (ref == 0 && picture.seed != null) {
            tryCandidate(picture.seed[ctx.address]);
        }
        search.searchInteger(method, range);
        search.refineSubpel(subpel);
    }

    /**
     * Evaluates a packed candidate vector rounded to full samples.
     *
     * @param packed the packed vector in quarter samples
     */
    private void tryCandidate(int packed) {
        var clamped = search.clamp(packed);
        search.tryInteger(search.roundX(MotionPredictor.x(clamped)), (MotionPredictor.y(clamped) + 2) >> 2);
    }

    /**
     * Searches the two partitions of a 16x8 or 8x16 macroblock.
     *
     * @param mb the candidate to fill
     * @param horizontal {@code true} for 16x8, {@code false} for 8x16
     * @param fromEightByEight whether the 8x8 vectors are available as search candidates
     * @return the candidate with its SATD cost
     */
    private Macroblock analyzeRectangles(Macroblock mb, boolean horizontal, boolean fromEightByEight) {
        mb.type = horizontal ? MbType.P16x8 : MbType.P8x16;
        mb.transform8x8 = false;
        mb.qp = qp;
        long total = (lambdaSad * 3L) >> 8;
        long distortion = 0;
        var subpel = analysis.subpelOther();
        for (var part = 0; part < 2; part++) {
            var x4 = horizontal ? 0 : part * 2;
            var y4 = horizontal ? part * 2 : 0;
            var w4 = horizontal ? 4 : 2;
            var h4 = horizontal ? 2 : 4;
            var hintCount = 0;
            hints[hintCount++] = MotionPredictor.pack(p16Mb.mvX[0], p16Mb.mvY[0]);
            if (fromEightByEight) {
                var first = horizontal ? part * 2 : part;
                var second = horizontal ? part * 2 + 1 : part + 2;
                hints[hintCount++] = quadrantVector(first);
                hints[hintCount++] = quadrantVector(second);
            }
            var bestCost = Integer.MAX_VALUE;
            var bestRef = 0;
            var bestMv = 0;
            var bestMvp = 0;
            var bestDistortion = 0;
            for (var r = 0; r < subPartitionRefs(); r++) {
                var ref = subPartitionRef(r);
                setPartitionRef(mb, x4, y4, w4, h4, ref);
                var mvp = predictor.predict(mb, x4, y4, w4, ref,
                        horizontal ? MotionPredictor.SHAPE_16x8 : MotionPredictor.SHAPE_8x16);
                searchPartition(ref, x4 * 4, y4 * 4, w4 * 4, h4 * 4, mvp, hintCount, analysis.searchMethod(),
                        analysis.searchRange() / 3, subpel);
                var cost = search.bestCost + refCost(ref);
                if (cost < bestCost) {
                    bestCost = cost;
                    bestRef = ref;
                    bestMv = MotionPredictor.pack(search.bestX, search.bestY);
                    bestMvp = mvp;
                    bestDistortion = search.bestDistortion;
                }
            }
            setPartitionRef(mb, x4, y4, w4, h4, bestRef);
            var mvx = MotionPredictor.x(bestMv);
            var mvy = MotionPredictor.y(bestMv);
            mb.setMotion(x4, y4, w4, h4, mvx, mvy, mvx - MotionPredictor.x(bestMvp), mvy - MotionPredictor.y(bestMvp));
            total += bestCost;
            distortion += bestDistortion;
        }
        mb.cost = total;
        mb.distortion = distortion;
        return mb;
    }

    /**
     * Returns the packed vector of an 8x8 quadrant of the {@code P_8x8} candidate.
     *
     * @param quadrant the quadrant in raster order
     * @return the packed vector of the quadrant's top-left 4x4 block
     */
    private int quadrantVector(int quadrant) {
        var index = (quadrant >> 1) * 8 + (quadrant & 1) * 2;
        return MotionPredictor.pack(p8x8Mb.mvX[index], p8x8Mb.mvY[index]);
    }

    /**
     * Sets the reference index of the 8x8 quadrants a partition covers.
     *
     * @param mb the macroblock
     * @param x4 the partition's left column in 4x4 units
     * @param y4 the partition's top row in 4x4 units
     * @param w4 the partition width in 4x4 units
     * @param h4 the partition height in 4x4 units
     * @param ref the reference index
     */
    private static void setPartitionRef(Macroblock mb, int x4, int y4, int w4, int h4, int ref) {
        for (var qy = y4 >> 1; qy <= (y4 + h4 - 1) >> 1; qy++) {
            for (var qx = x4 >> 1; qx <= (x4 + w4 - 1) >> 1; qx++) {
                mb.refs[qy * 2 + qx] = ref;
            }
        }
    }

    /**
     * The vectors of the sub-partitions of the last evaluated sub-macroblock type.
     */
    private final int[] subMvX = new int[4];

    /**
     * The vectors of the sub-partitions of the last evaluated sub-macroblock type.
     */
    private final int[] subMvY = new int[4];

    /**
     * The vector differences of the sub-partitions of the last evaluated sub-macroblock type.
     */
    private final int[] subMvdX = new int[4];

    /**
     * The vector differences of the sub-partitions of the last evaluated sub-macroblock type.
     */
    private final int[] subMvdY = new int[4];

    /**
     * The vectors of the best sub-macroblock type.
     */
    private final int[] subBestX = new int[4];

    /**
     * The vectors of the best sub-macroblock type.
     */
    private final int[] subBestY = new int[4];

    /**
     * The vector differences of the best sub-macroblock type.
     */
    private final int[] subBestDx = new int[4];

    /**
     * The vector differences of the best sub-macroblock type.
     */
    private final int[] subBestDy = new int[4];

    /**
     * The distortion of the last evaluated sub-macroblock type.
     */
    private long lastSubDistortion;

    /**
     * Searches the four 8x8 sub-macroblocks, and their sub-partitions when the speed allows.
     *
     * @return the {@code P_8x8} candidate with its SATD cost
     */
    private Macroblock analyze8x8() {
        var mb = p8x8Mb;
        analyzed8x8 = true;
        mb.type = MbType.P8x8;
        mb.transform8x8 = false;
        mb.qp = qp;
        long total = (lambdaSad * 5L) >> 8;
        long distortion = 0;
        var mvLimit = settings.level().maxMvsPer2Mb() == 0 ? 16 : settings.level().maxMvsPer2Mb() / 2;
        var allowSub = analysis.partitions() >= 3 && mvLimit > 4;
        var mvCount = 4;
        var subpel = analysis.subpelOther();
        for (var sub = 0; sub < 4; sub++) {
            var x4 = (sub & 1) * 2;
            var y4 = (sub >> 1) * 2;
            var bestCost = Integer.MAX_VALUE;
            var bestRef = 0;
            var bestMv = 0;
            var bestMvp = 0;
            var bestDistortion = 0;
            hints[0] = MotionPredictor.pack(p16Mb.mvX[y4 * 4 + x4], p16Mb.mvY[y4 * 4 + x4]);
            for (var r = 0; r < subPartitionRefs(); r++) {
                var ref = subPartitionRef(r);
                if (ref > bestRef16 + 1) {
                    break;
                }
                mb.refs[sub] = ref;
                var mvp = predictor.predict(mb, x4, y4, 2, ref, MotionPredictor.SHAPE_MEDIAN);
                searchPartition(ref, x4 * 4, y4 * 4, 8, 8, mvp, 1, Math.min(analysis.searchMethod(), 1),
                        analysis.searchRange() / 2, subpel);
                var cost = search.bestCost + refCost(ref);
                if (cost < bestCost) {
                    bestCost = cost;
                    bestRef = ref;
                    bestMv = MotionPredictor.pack(search.bestX, search.bestY);
                    bestMvp = mvp;
                    bestDistortion = search.bestDistortion;
                }
            }
            mb.refs[sub] = bestRef;
            mb.subTypes[sub] = MbType.SUB_8x8;
            var mvx = MotionPredictor.x(bestMv);
            var mvy = MotionPredictor.y(bestMv);
            mb.setMotion(x4, y4, 2, 2, mvx, mvy, mvx - MotionPredictor.x(bestMvp), mvy - MotionPredictor.y(bestMvp));
            var subCost = (long) bestCost + (lambdaSad >> 8);
            var subDistortion = (long) bestDistortion;
            if (allowSub && bestDistortion > subPartitionThreshold()) {
                for (var subType = MbType.SUB_8x4; subType <= MbType.SUB_4x4; subType++) {
                    var extra = MbType.subPartitions(subType) - 1;
                    if (mvCount + extra > mvLimit || subType == MbType.SUB_4x4 && mb.subTypes[sub] == MbType.SUB_8x8) {
                        continue;
                    }
                    var candidate = analyzeSubPartitions(mb, sub, bestRef, subType, bestMv);
                    if (candidate < subCost) {
                        subCost = candidate;
                        subDistortion = lastSubDistortion;
                        mb.subTypes[sub] = subType;
                        System.arraycopy(subMvX, 0, subBestX, 0, 4);
                        System.arraycopy(subMvY, 0, subBestY, 0, 4);
                        System.arraycopy(subMvdX, 0, subBestDx, 0, 4);
                        System.arraycopy(subMvdY, 0, subBestDy, 0, 4);
                    }
                }
                applySubPartitions(mb, sub, mb.subTypes[sub], bestMv, bestMvp);
                mvCount += MbType.subPartitions(mb.subTypes[sub]) - 1;
            }
            total += subCost;
            distortion += subDistortion;
        }
        mb.cost = total;
        mb.distortion = distortion;
        return mb;
    }

    /**
     * Searches the sub-partitions of one sub-macroblock for one sub-macroblock type, leaving the
     * vectors in the {@code subMv} arrays and the macroblock's motion set accordingly.
     *
     * @param mb the {@code P_8x8} candidate
     * @param sub the sub-macroblock index
     * @param ref the sub-macroblock's reference index
     * @param subType the sub-macroblock type
     * @param hintMv the packed 8x8 vector used as a candidate
     * @return the SATD cost of the sub-macroblock with this type
     */
    private long analyzeSubPartitions(Macroblock mb, int sub, int ref, int subType, int hintMv) {
        var x4 = (sub & 1) * 2;
        var y4 = (sub >> 1) * 2;
        var parts = MbType.subPartitions(subType);
        var w4 = subType == MbType.SUB_4x8 || subType == MbType.SUB_4x4 ? 1 : 2;
        var h4 = subType == MbType.SUB_8x4 || subType == MbType.SUB_4x4 ? 1 : 2;
        long total = (lambdaSad * 3L) >> 8;
        long distortion = 0;
        for (var part = 0; part < parts; part++) {
            var px = x4 + (w4 == 1 ? part & 1 : 0);
            var py = y4 + (h4 == 1 ? (w4 == 1 ? part >> 1 : part) : 0);
            var mvp = predictor.predict(mb, px, py, w4, ref, MotionPredictor.SHAPE_MEDIAN);
            hints[0] = hintMv;
            searchPartition(ref, px * 4, py * 4, w4 * 4, h4 * 4, mvp, 1, 0, 4, analysis.subpelOther());
            subMvX[part] = search.bestX;
            subMvY[part] = search.bestY;
            subMvdX[part] = search.bestX - MotionPredictor.x(mvp);
            subMvdY[part] = search.bestY - MotionPredictor.y(mvp);
            mb.setMotion(px, py, w4, h4, search.bestX, search.bestY, subMvdX[part], subMvdY[part]);
            total += search.bestCost;
            distortion += search.bestDistortion;
        }
        lastSubDistortion = distortion;
        return total;
    }

    /**
     * Writes the motion of the chosen sub-macroblock type into the macroblock.
     *
     * @param mb the {@code P_8x8} candidate
     * @param sub the sub-macroblock index
     * @param subType the chosen sub-macroblock type
     * @param mv the packed 8x8 vector
     * @param mvp the packed 8x8 predictor
     */
    private void applySubPartitions(Macroblock mb, int sub, int subType, int mv, int mvp) {
        var x4 = (sub & 1) * 2;
        var y4 = (sub >> 1) * 2;
        if (subType == MbType.SUB_8x8) {
            var mvx = MotionPredictor.x(mv);
            var mvy = MotionPredictor.y(mv);
            mb.setMotion(x4, y4, 2, 2, mvx, mvy, mvx - MotionPredictor.x(mvp), mvy - MotionPredictor.y(mvp));
            return;
        }
        var w4 = subType == MbType.SUB_4x8 || subType == MbType.SUB_4x4 ? 1 : 2;
        var h4 = subType == MbType.SUB_8x4 || subType == MbType.SUB_4x4 ? 1 : 2;
        var parts = MbType.subPartitions(subType);
        for (var part = 0; part < parts; part++) {
            var px = x4 + (w4 == 1 ? part & 1 : 0);
            var py = y4 + (h4 == 1 ? (w4 == 1 ? part >> 1 : part) : 0);
            mb.setMotion(px, py, w4, h4, subBestX[part], subBestY[part], subBestDx[part], subBestDy[part]);
        }
    }

    /**
     * Codes an inter candidate: builds its prediction and codes the luma and chroma residual into
     * the reconstruction.
     *
     * <p>A {@code P_L0_16x16} candidate that moves like the skip candidate reuses the skip
     * prediction instead of building its own.
     *
     * @param mb the candidate with its motion set
     */
    private void codeInter(Macroblock mb) {
        var source = picture.source;
        var rec = picture.reconstruction;
        byte[] luma;
        byte[] cb;
        byte[] cr;
        if (mb.type == MbType.P16x16 && movesLikeSkip(mb, skipMb)) {
            predictSkip(true);
            luma = skipLuma;
            cb = skipCb;
            cr = skipCr;
        } else {
            predictInter(mb);
            luma = interLuma;
            cb = interCb;
            cr = interCr;
        }
        mb.qp = qp;
        if (mb.transform8x8) {
            residual.lumaInter8x8(mb, source.y, srcLuma, source.lumaStride, luma, 0, 16, rec.y, recLuma,
                    rec.lumaStride, qp);
            if ((mb.cbp & 15) == 0) {
                mb.transform8x8 = false;
            }
        } else {
            residual.lumaInter(mb, source.y, srcLuma, source.lumaStride, luma, 0, 16, rec.y, recLuma, rec.lumaStride,
                    qp);
        }
        residual.chroma(mb, source.u, source.v, srcChroma, source.chromaStride, cb, cr, 0, 8, rec.u, rec.v,
                recChroma, rec.chromaStride, Tables.chromaQp(qp, 0), false);
    }

    /**
     * Builds the luma and chroma inter prediction of a candidate into the scratch buffers, one
     * motion-compensated block per partition.
     *
     * @param mb the candidate with its motion set
     */
    private void predictInter(Macroblock mb) {
        switch (mb.type) {
            case MbType.B_DIRECT, MbType.B_SKIP, MbType.B_L0, MbType.B_L1, MbType.B_BI ->
                    predictB(mb, interLuma, interCb, interCr);
            case MbType.P16x16, MbType.P_SKIP -> predictBlock(mb, 0, 0, 4, 4);
            case MbType.P16x8 -> {
                predictBlock(mb, 0, 0, 4, 2);
                predictBlock(mb, 0, 2, 4, 2);
            }
            case MbType.P8x16 -> {
                predictBlock(mb, 0, 0, 2, 4);
                predictBlock(mb, 2, 0, 2, 4);
            }
            default -> {
                for (var sub = 0; sub < 4; sub++) {
                    var x4 = (sub & 1) * 2;
                    var y4 = (sub >> 1) * 2;
                    switch (mb.subTypes[sub]) {
                        case MbType.SUB_8x8 -> predictBlock(mb, x4, y4, 2, 2);
                        case MbType.SUB_8x4 -> {
                            predictBlock(mb, x4, y4, 2, 1);
                            predictBlock(mb, x4, y4 + 1, 2, 1);
                        }
                        case MbType.SUB_4x8 -> {
                            predictBlock(mb, x4, y4, 1, 2);
                            predictBlock(mb, x4 + 1, y4, 1, 2);
                        }
                        default -> {
                            predictBlock(mb, x4, y4, 1, 1);
                            predictBlock(mb, x4 + 1, y4, 1, 1);
                            predictBlock(mb, x4, y4 + 1, 1, 1);
                            predictBlock(mb, x4 + 1, y4 + 1, 1, 1);
                        }
                    }
                }
            }
        }
    }

    /**
     * Motion-compensates one partition into the scratch prediction buffers.
     *
     * @param mb the candidate
     * @param x4 the partition's left column in 4x4 units
     * @param y4 the partition's top row in 4x4 units
     * @param w4 the partition width in 4x4 units
     * @param h4 the partition height in 4x4 units
     */
    private void predictBlock(Macroblock mb, int x4, int y4, int w4, int h4) {
        predictBlockLuma(mb, x4, y4, w4, h4);
        predictBlockChroma(mb, x4, y4, w4, h4);
    }

    /**
     * Motion-compensates the luma of one partition into the scratch prediction buffer.
     *
     * @param mb the candidate
     * @param x4 the partition's left column in 4x4 units
     * @param y4 the partition's top row in 4x4 units
     * @param w4 the partition width in 4x4 units
     * @param h4 the partition height in 4x4 units
     */
    private void predictBlockLuma(Macroblock mb, int x4, int y4, int w4, int h4) {
        var index = y4 * 4 + x4;
        var ref = picture.refs[Math.max(0, mb.refs[(y4 >> 1) * 2 + (x4 >> 1)])];
        var x = x4 * 4;
        var y = y4 * 4;
        search.predict(ref, ctx.mbX * 16 + x, ctx.mbY * 16 + y, w4 * 4, h4 * 4, mb.mvX[index], mb.mvY[index], interLuma,
                y * 16 + x, 16);
    }

    /**
     * Motion-compensates the chroma of one partition into the scratch prediction buffers.
     *
     * @param mb the candidate
     * @param x4 the partition's left column in 4x4 units
     * @param y4 the partition's top row in 4x4 units
     * @param w4 the partition width in 4x4 units
     * @param h4 the partition height in 4x4 units
     */
    private void predictBlockChroma(Macroblock mb, int x4, int y4, int w4, int h4) {
        var index = y4 * 4 + x4;
        var ref = picture.refs[Math.max(0, mb.refs[(y4 >> 1) * 2 + (x4 >> 1)])];
        var mvx = mb.mvX[index];
        var mvy = mb.mvY[index];
        var x = x4 * 4;
        var y = y4 * 4;
        var chromaX = ctx.mbX * 8 + x / 2;
        var chromaY = ctx.mbY * 8 + y / 2;
        var offset = ref.chromaOrigin + (chromaY + (mvy >> 3)) * ref.chromaStride + chromaX + (mvx >> 3);
        var dst = (y / 2) * 8 + x / 2;
        H264Dsp.INSTANCE.chromaMc(ref.u, ref.v, offset, ref.chromaStride, interCb, interCr, dst, 8, w4 * 2, h4 * 2,
                mvx & 7, mvy & 7);
    }

    /**
     * The B candidate with spatial direct motion that carries a residual.
     */
    private final Macroblock bDirectMb = new Macroblock();

    /**
     * The {@code B_L0_16x16} candidate.
     */
    private final Macroblock bL0Mb = new Macroblock();

    /**
     * The {@code B_L1_16x16} candidate.
     */
    private final Macroblock bL1Mb = new Macroblock();

    /**
     * The {@code B_Bi_16x16} candidate.
     */
    private final Macroblock bBiMb = new Macroblock();

    /**
     * The list 0 luma prediction scratch of a bi-predicted block.
     */
    private final byte[] biLuma0 = new byte[256];

    /**
     * The list 1 luma prediction scratch of a bi-predicted block.
     */
    private final byte[] biLuma1 = new byte[256];

    /**
     * The averaged luma prediction of the bi-predicted 16x16 candidate.
     */
    private final byte[] biLumaMean = new byte[256];

    /**
     * The list 0 Cb prediction scratch of a bi-predicted block.
     */
    private final byte[] biCb0 = new byte[64];

    /**
     * The list 0 Cr prediction scratch of a bi-predicted block.
     */
    private final byte[] biCr0 = new byte[64];

    /**
     * The list 1 Cb prediction scratch of a bi-predicted block.
     */
    private final byte[] biCb1 = new byte[64];

    /**
     * The list 1 Cr prediction scratch of a bi-predicted block.
     */
    private final byte[] biCr1 = new byte[64];

    /**
     * Whether the spatial direct prediction of the macroblock stays inside the reference padding.
     */
    private boolean directUsable;

    /**
     * Returns the coding cost in weighted bits of the type syntax of a B macroblock.
     *
     * @param type the macroblock type
     * @return the cost
     */
    private int bTypeCost(int type) {
        var bits = switch (type) {
            case MbType.B_DIRECT -> 1;
            case MbType.B_L0, MbType.B_L1 -> 3;
            default -> 5;
        };
        return (lambdaSad * bits) >> 8;
    }

    /**
     * Decides a macroblock of a B slice: spatial direct or skip, a 16x16 partition predicted from
     * either list or both, or an intra type.
     *
     * @return the chosen macroblock
     */
    private Macroblock decideB() {
        var skip = prepareDirectSkip();
        var skipUsable = directUsable;
        if (skipUsable) {
            if (probeSkip() == SKIP_FREE) {
                finishSkip(skip);
                return skip;
            }
        }
        var l0 = searchB(0, bL0Mb);
        var l1 = searchB(1, bL1Mb);
        var bi = buildBi(l0, l1);
        var best = l0;
        if (l1.cost < best.cost) {
            best = l1;
        }
        if (bi.cost < best.cost) {
            best = bi;
        }
        var interSatdCost = best.cost;
        var skipIntra = picture.lightB && 2L * picture.lowresInter[ctx.address] < picture.lowresIntra[ctx.address];
        var i16 = skipIntra ? null : analyzeIntra16x16();
        var intraSatdCost = i16 == null ? Long.MAX_VALUE : i16.cost;
        codeInter(best);
        rdCost(best);
        keepBest(best);
        if (skipUsable) {
            finishSkip(skip);
            rdCost(skip, rdBest.cost);
            if (skip.cost < rdBest.cost) {
                keepBest(skip);
            }
            var direct = bDirectMb;
            direct.type = MbType.B_DIRECT;
            direct.transform8x8 = false;
            direct.qp = qp;
            direct.copyMotionFrom(skip);
            codeInter(direct);
            rdCost(direct, rdBest.cost);
            if (direct.cost < rdBest.cost) {
                keepBest(direct);
            }
        }
        if (transform8x8Mode && !MbType.isSkip(rdBest.type) && !MbType.isIntra(rdBest.type)) {
            var candidate = switch (rdBest.type) {
                case MbType.B_DIRECT -> bDirectMb;
                case MbType.B_L0 -> bL0Mb;
                case MbType.B_L1 -> bL1Mb;
                default -> bBiMb;
            };
            candidate.transform8x8 = true;
            codeInter(candidate);
            rdCost(candidate, rdBest.cost);
            if (candidate.cost < rdBest.cost) {
                keepBest(candidate);
            }
            candidate.transform8x8 = false;
        }
        var intraLimit = analysis.rdMode() >= 2 ? 2 * interSatdCost : interSatdCost + interSatdCost / 4;
        if (intraSatdCost < intraLimit) {
            codeIntra16x16(i16);
            rdCost(i16, rdBest.cost);
            if (i16.cost < rdBest.cost) {
                keepBest(i16);
            }
            if (analysis.intra4x4() && (analysis.rdMode() >= 2 || intraSatdCost < interSatdCost)) {
                tryIntraNxNRd();
            }
        }
        restoreReconstruction();
        return rdBest;
    }

    /**
     * Derives the spatial direct motion of the macroblock into the skip candidate and builds its
     * prediction into the skip buffers.
     *
     * @return the {@code B_Skip} candidate
     */
    private Macroblock prepareDirectSkip() {
        var mb = skipMb;
        mb.type = MbType.B_SKIP;
        mb.transform8x8 = false;
        mb.qp = qp;
        deriveDirect(mb);
        directUsable = true;
        for (var q = 0; q < 4 && directUsable; q++) {
            var index = (q >> 1) * 8 + (q & 1) * 2;
            if (mb.refs[q] >= 0 && !insideWindow(mb.mvX[index], mb.mvY[index])
                    || mb.refs1[q] >= 0 && !insideWindow(mb.mvX1[index], mb.mvY1[index])) {
                directUsable = false;
            }
        }
        if (directUsable) {
            predictB(mb, skipLuma, skipCb, skipCr);
        }
        skipLumaReady = true;
        skipChromaReady = true;
        return mb;
    }

    /**
     * Derives the spatial direct motion (clause 8.4.1.2.2) of the macroblock.
     *
     * @param mb receives the references and vectors of both lists
     */
    private void deriveDirect(Macroblock mb) {
        predictor.setList(0);
        var mvp0 = predictor.directMotion(mb);
        var ref0 = predictor.directRef();
        predictor.setList(1);
        var mvp1 = predictor.directMotion(mb);
        var ref1 = predictor.directRef();
        predictor.setList(0);
        var zero = ref0 < 0 && ref1 < 0;
        if (zero) {
            ref0 = 0;
            ref1 = 0;
        }
        var col = picture.refs[1];
        var base = ctx.address * 4;
        Arrays.fill(mb.mvdX, 0);
        Arrays.fill(mb.mvdY, 0);
        Arrays.fill(mb.mvdX1, 0);
        Arrays.fill(mb.mvdY1, 0);
        for (var q = 0; q < 4; q++) {
            var colZero = col.colRef[base + q] == 0 && Math.abs(col.colMvX[base + q]) <= 1
                    && Math.abs(col.colMvY[base + q]) <= 1;
            var x4 = (q & 1) * 2;
            var y4 = (q >> 1) * 2;
            mb.refs[q] = ref0;
            mb.refs1[q] = ref1;
            var first = y4 * 4 + x4;
            var mv0 = ref0 < 0 || zero || ref0 == 0 && colZero ? 0 : mvp0;
            var mv1 = ref1 < 0 || zero || ref1 == 0 && colZero ? 0 : mvp1;
            var x0 = MotionPredictor.x(mv0);
            var y0 = MotionPredictor.y(mv0);
            var x1 = MotionPredictor.x(mv1);
            var y1 = MotionPredictor.y(mv1);
            mb.mvX[first] = x0;
            mb.mvX[first + 1] = x0;
            mb.mvX[first + 4] = x0;
            mb.mvX[first + 5] = x0;
            mb.mvY[first] = y0;
            mb.mvY[first + 1] = y0;
            mb.mvY[first + 4] = y0;
            mb.mvY[first + 5] = y0;
            mb.mvX1[first] = x1;
            mb.mvX1[first + 1] = x1;
            mb.mvX1[first + 4] = x1;
            mb.mvX1[first + 5] = x1;
            mb.mvY1[first] = y1;
            mb.mvY1[first + 1] = y1;
            mb.mvY1[first + 4] = y1;
            mb.mvY1[first + 5] = y1;
        }
    }

    /**
     * Builds the prediction of a B candidate into the given buffers.
     *
     * @param mb the candidate with its motion set
     * @param luma receives the 16x16 luma prediction
     * @param cb receives the 8x8 Cb prediction
     * @param cr receives the 8x8 Cr prediction
     */
    private void predictB(Macroblock mb, byte[] luma, byte[] cb, byte[] cr) {
        var uniform = true;
        for (var q = 1; q < 4 && uniform; q++) {
            var index = (q >> 1) * 8 + (q & 1) * 2;
            uniform = mb.refs[q] == mb.refs[0] && mb.refs1[q] == mb.refs1[0] && mb.mvX[index] == mb.mvX[0]
                    && mb.mvY[index] == mb.mvY[0] && mb.mvX1[index] == mb.mvX1[0] && mb.mvY1[index] == mb.mvY1[0];
        }
        if (uniform) {
            predictBBlock(mb, 0, 0, 4, 4, luma, cb, cr);
            return;
        }
        for (var q = 0; q < 4; q++) {
            predictBBlock(mb, (q & 1) * 2, (q >> 1) * 2, 2, 2, luma, cb, cr);
        }
    }

    /**
     * Motion-compensates one block of a B candidate from the lists it uses, averaging the two
     * predictions of a bi-predicted block.
     *
     * @param mb the candidate
     * @param x4 the block's left column in 4x4 units
     * @param y4 the block's top row in 4x4 units
     * @param w4 the block width in 4x4 units
     * @param h4 the block height in 4x4 units
     * @param luma receives the luma prediction at stride 16
     * @param cb receives the Cb prediction at stride 8
     * @param cr receives the Cr prediction at stride 8
     */
    private void predictBBlock(Macroblock mb, int x4, int y4, int w4, int h4, byte[] luma, byte[] cb, byte[] cr) {
        var index = y4 * 4 + x4;
        var quadrant = (y4 >> 1) * 2 + (x4 >> 1);
        var use0 = mb.refs[quadrant] >= 0;
        var use1 = mb.refs1[quadrant] >= 0;
        var lumaOffset = y4 * 4 * 16 + x4 * 4;
        var chromaOffset = y4 * 2 * 8 + x4 * 2;
        var width = w4 * 4;
        var height = h4 * 4;
        if (use0 && use1) {
            predictLuma(picture.refs[0], x4, y4, w4, h4, mb.mvX[index], mb.mvY[index], biLuma0, lumaOffset);
            predictLuma(picture.refs[1], x4, y4, w4, h4, mb.mvX1[index], mb.mvY1[index], biLuma1, lumaOffset);
            pixels.average(biLuma0, lumaOffset, 16, biLuma1, lumaOffset, 16, luma, lumaOffset, 16, width, height);
            predictChroma(picture.refs[0], x4, y4, w4, h4, mb.mvX[index], mb.mvY[index], biCb0, biCr0, chromaOffset);
            predictChroma(picture.refs[1], x4, y4, w4, h4, mb.mvX1[index], mb.mvY1[index], biCb1, biCr1, chromaOffset);
            pixels.average(biCb0, chromaOffset, 8, biCb1, chromaOffset, 8, cb, chromaOffset, 8, width / 2, height / 2);
            pixels.average(biCr0, chromaOffset, 8, biCr1, chromaOffset, 8, cr, chromaOffset, 8, width / 2, height / 2);
        } else if (use0) {
            predictLuma(picture.refs[0], x4, y4, w4, h4, mb.mvX[index], mb.mvY[index], luma, lumaOffset);
            predictChroma(picture.refs[0], x4, y4, w4, h4, mb.mvX[index], mb.mvY[index], cb, cr, chromaOffset);
        } else {
            predictLuma(picture.refs[1], x4, y4, w4, h4, mb.mvX1[index], mb.mvY1[index], luma, lumaOffset);
            predictChroma(picture.refs[1], x4, y4, w4, h4, mb.mvX1[index], mb.mvY1[index], cb, cr, chromaOffset);
        }
    }

    /**
     * Motion-compensates the luma of one block from one reference.
     *
     * @param ref the reference picture
     * @param x4 the block's left column in 4x4 units
     * @param y4 the block's top row in 4x4 units
     * @param w4 the block width in 4x4 units
     * @param h4 the block height in 4x4 units
     * @param mvx the horizontal vector in quarter samples
     * @param mvy the vertical vector in quarter samples
     * @param dst receives the prediction at stride 16
     * @param offset the index of the block's first sample in {@code dst}
     */
    private void predictLuma(Frame ref, int x4, int y4, int w4, int h4, int mvx, int mvy, byte[] dst, int offset) {
        search.predict(ref, ctx.mbX * 16 + x4 * 4, ctx.mbY * 16 + y4 * 4, w4 * 4, h4 * 4, mvx, mvy, dst, offset, 16);
    }

    /**
     * Motion-compensates the chroma of one block from one reference.
     *
     * @param ref the reference picture
     * @param x4 the block's left column in 4x4 units
     * @param y4 the block's top row in 4x4 units
     * @param w4 the block width in 4x4 units
     * @param h4 the block height in 4x4 units
     * @param mvx the horizontal luma vector in quarter samples
     * @param mvy the vertical luma vector in quarter samples
     * @param dstCb receives the Cb prediction at stride 8
     * @param dstCr receives the Cr prediction at stride 8
     * @param offset the index of the block's first sample in the destinations
     */
    private void predictChroma(Frame ref, int x4, int y4, int w4, int h4, int mvx, int mvy, byte[] dstCb,
                               byte[] dstCr, int offset) {
        var chromaX = ctx.mbX * 8 + x4 * 2;
        var chromaY = ctx.mbY * 8 + y4 * 2;
        var source = ref.chromaOrigin + (chromaY + (mvy >> 3)) * ref.chromaStride + chromaX + (mvx >> 3);
        H264Dsp.INSTANCE.chromaMc(ref.u, ref.v, source, ref.chromaStride, dstCb, dstCr, offset, 8, w4 * 2, h4 * 2,
                mvx & 7, mvy & 7);
    }

    /**
     * Searches the best 16x16 vector of one reference list for a B macroblock.
     *
     * @param list the reference list, {@code 0} or {@code 1}
     * @param mb the candidate to fill
     * @return the candidate, with its SATD cost
     */
    private Macroblock searchB(int list, Macroblock mb) {
        mb.type = list == 0 ? MbType.B_L0 : MbType.B_L1;
        mb.transform8x8 = false;
        mb.qp = qp;
        predictor.setList(list);
        var mvp = predictor.predict(mb, 0, 0, 4, 0, MotionPredictor.SHAPE_MEDIAN);
        predictor.setList(0);
        var source = picture.source;
        var frame = picture.refs[list];
        search.setup(source.y, srcLuma, source.lumaStride, frame, list, ctx.mbX * 16, ctx.mbY * 16, 16, 16, mvp,
                lambdaSad, settings.maxMvY());
        tryCandidate(mvp);
        tryCandidate(0);
        var xs = list == 0 ? pi.mvX : pi.mvX1;
        var ys = list == 0 ? pi.mvY : pi.mvY1;
        if (ctx.availA) {
            tryCandidate(MotionPredictor.pack(xs[ctx.addrA * 16 + 3], ys[ctx.addrA * 16 + 3]));
        }
        if (ctx.availB) {
            tryCandidate(MotionPredictor.pack(xs[ctx.addrB * 16 + 12], ys[ctx.addrB * 16 + 12]));
        }
        if (ctx.availC) {
            tryCandidate(MotionPredictor.pack(xs[ctx.addrC * 16 + 12], ys[ctx.addrC * 16 + 12]));
        }
        var anchor = picture.refs[1].motion[ctx.address];
        var span = picture.refs[1].motionDistance;
        var scale = list == 0 ? picture.distPast : -picture.distFuture;
        tryCandidate(MotionPredictor.pack(MotionPredictor.x(anchor) * scale / span,
                MotionPredictor.y(anchor) * scale / span));
        if (list == 1 && bL0Mb.type == MbType.B_L0) {
            tryCandidate(MotionPredictor.pack(-bL0Mb.mvX[0] * picture.distFuture / picture.distPast,
                    -bL0Mb.mvY[0] * picture.distFuture / picture.distPast));
        }
        search.searchInteger(picture.lightB ? 0 : analysis.searchMethod(), analysis.searchRange());
        search.refineSubpel(picture.lightB ? 0 : analysis.subpel16());
        mb.clearMotion();
        var mvx = search.bestX;
        var mvy = search.bestY;
        var mvdx = mvx - MotionPredictor.x(mvp);
        var mvdy = mvy - MotionPredictor.y(mvp);
        if (list == 0) {
            Arrays.fill(mb.refs, 0);
            mb.setMotion(0, 0, 4, 4, mvx, mvy, mvdx, mvdy);
        } else {
            Arrays.fill(mb.refs1, 0);
            mb.setMotion1(0, 0, 4, 4, mvx, mvy, mvdx, mvdy);
        }
        mb.cost = search.bestCost + bTypeCost(mb.type);
        mb.distortion = search.bestDistortion;
        return mb;
    }

    /**
     * Builds the bi-predicted 16x16 candidate from the two searched vectors.
     *
     * @param l0 the searched list 0 candidate
     * @param l1 the searched list 1 candidate
     * @return the candidate, with its SATD cost
     */
    private Macroblock buildBi(Macroblock l0, Macroblock l1) {
        var mb = bBiMb;
        mb.type = MbType.B_BI;
        mb.transform8x8 = false;
        mb.qp = qp;
        mb.clearMotion();
        Arrays.fill(mb.refs, 0);
        Arrays.fill(mb.refs1, 0);
        mb.setMotion(0, 0, 4, 4, l0.mvX[0], l0.mvY[0], l0.mvdX[0], l0.mvdY[0]);
        mb.setMotion1(0, 0, 4, 4, l1.mvX1[0], l1.mvY1[0], l1.mvdX1[0], l1.mvdY1[0]);
        predictLuma(picture.refs[0], 0, 0, 4, 4, mb.mvX[0], mb.mvY[0], biLuma0, 0);
        predictLuma(picture.refs[1], 0, 0, 4, 4, mb.mvX1[0], mb.mvY1[0], biLuma1, 0);
        pixels.average(biLuma0, 0, 16, biLuma1, 0, 16, biLumaMean, 0, 16, 16, 16);
        var source = picture.source;
        var satd = pixels.satd(source.y, srcLuma, source.lumaStride, biLumaMean, 0, 16, 16, 16);
        var vectorBits = MotionSearch.mvBits(mb.mvdX[0]) + MotionSearch.mvBits(mb.mvdY[0])
                + MotionSearch.mvBits(mb.mvdX1[0]) + MotionSearch.mvBits(mb.mvdY1[0]);
        mb.cost = satd + ((lambdaSad * vectorBits + 128) >> 8) + bTypeCost(MbType.B_BI);
        mb.distortion = satd;
        return mb;
    }
}
