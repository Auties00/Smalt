/* Ports libvpx 1.17.0 (vp8/encoder/pickinter.c, mcomp.c, rdopt.c, encodeframe.c, encodemb.c, encodeintra.c,
   tokenize.c). Copyright (c) 2010 The WebM project authors; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.video.vp8.internal;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

import static com.github.auties00.smalt.video.vp8.internal.Vp8FrameDecoder.ALTREF_FRAME;
import static com.github.auties00.smalt.video.vp8.internal.Vp8FrameDecoder.B_DC_PRED;
import static com.github.auties00.smalt.video.vp8.internal.Vp8FrameDecoder.B_HE_PRED;
import static com.github.auties00.smalt.video.vp8.internal.Vp8FrameDecoder.B_HU_PRED;
import static com.github.auties00.smalt.video.vp8.internal.Vp8FrameDecoder.B_PRED;
import static com.github.auties00.smalt.video.vp8.internal.Vp8FrameDecoder.B_TM_PRED;
import static com.github.auties00.smalt.video.vp8.internal.Vp8FrameDecoder.B_VE_PRED;
import static com.github.auties00.smalt.video.vp8.internal.Vp8FrameDecoder.DC_PRED;
import static com.github.auties00.smalt.video.vp8.internal.Vp8FrameDecoder.GOLDEN_FRAME;
import static com.github.auties00.smalt.video.vp8.internal.Vp8FrameDecoder.H_PRED;
import static com.github.auties00.smalt.video.vp8.internal.Vp8FrameDecoder.INTRA_FRAME;
import static com.github.auties00.smalt.video.vp8.internal.Vp8FrameDecoder.LAST_FRAME;
import static com.github.auties00.smalt.video.vp8.internal.Vp8FrameDecoder.NEARESTMV;
import static com.github.auties00.smalt.video.vp8.internal.Vp8FrameDecoder.NEARMV;
import static com.github.auties00.smalt.video.vp8.internal.Vp8FrameDecoder.NEWMV;
import static com.github.auties00.smalt.video.vp8.internal.Vp8FrameDecoder.SPLITMV;
import static com.github.auties00.smalt.video.vp8.internal.Vp8FrameDecoder.TM_PRED;
import static com.github.auties00.smalt.video.vp8.internal.Vp8FrameDecoder.V_PRED;
import static com.github.auties00.smalt.video.vp8.internal.Vp8FrameDecoder.ZEROMV;

/**
 * Codes the macroblocks of the real-time VP8 encoder: chooses each macroblock's mode, reference
 * and motion vector, predicts, transforms and quantizes it, reconstructs it into the frame being
 * coded, and records its coefficient tokens.
 *
 * <p>One instance belongs to one thread and holds that thread's scratch buffers; it reads the
 * frame-level state of its {@link RtEncoder} and writes the mode information, the reconstruction
 * and the entropy contexts of the macroblocks it codes. A macroblock may be coded once the
 * macroblock to its left, and the macroblocks above, above-left and above-right of it, are.
 * Prediction uses the decoder's own kernels ({@link Vp8Dsp}, {@link IntraPredictor}) and
 * reconstruction the decoder's inverse transforms on dequantized coefficients, so the
 * reconstruction is bit-exact with what any VP8 decoder produces from the stream.
 *
 * @implNote This implementation indexes the motion vector cost tables symmetrically, keeps the
 * adaptive mode thresholds per row, and codes the residual with the key-frame encoder's
 * transforms and quantizer; these and the other deliberate differences from libvpx are listed in
 * the note at the top of the class body.
 */
final class RtMbCoder {
    /*
     * The residual of whole-block modes is coded by RtDsp's group kernels, eight blocks at a time
     * (two luma groups and one chroma group), on interleaved copies of the macroblock's source,
     * prediction and reconstruction; the inverse transform runs on every block of a group whose
     * blocks carry any coefficient, which reconstructs blocks without coefficients exactly as the
     * decoder's DC-only and empty paths do. A group whose levels are all zero skips quantization and,
     * when the Y2 block gives its blocks no DC, reconstruction. The subblocks of B_PRED reuse the
     * key-frame encoder's Vp8EncDsp on copies laid out with Vp8EncDsp.BPS rows.
     *
     * Deliberate differences from libvpx 1.17.0:
     * - the motion vector cost tables are indexed symmetrically, where libvpx clamps negative
     *   differences to the cost of zero in vp8_mv_bit_cost and mv_err_cost;
     * - the adaptive mode thresholds and the dot-artifact budget are kept per macroblock row (see
     *   RtRow), so rows can be coded in parallel with the same result;
     * - the forward transform, Walsh-Hadamard transform and quantizer are the key-frame encoder's
     *   (libwebp's, with libvpx's fast quantizer rounding of 48/128), whose coefficients differ from
     *   libvpx's only in rounding;
     * - the encode breakout (check_for_encode_breakout) runs only when its static threshold is
     *   positive: with libvpx's default of zero it never skips, so it is not evaluated;
     * - the DC and vertical intra predictions of the mode search are measured from the source's sums
     *   rather than formed;
     * - token statistics count only the branches that are coded, where libvpx also counts the
     *   end-of-block branch after zero tokens.
     */

    /**
     * The row stride of the macroblock work buffers.
     */
    static final int BPS = Vp8EncDsp.BPS;

    /**
     * The offset of U in a work buffer.
     */
    static final int U_OFF = 16;

    /**
     * The offset of V in a work buffer.
     */
    static final int V_OFF = 24;

    /**
     * The largest full-pixel distance of a searched motion vector from its predictor, libvpx's
     * {@code MAX_FULL_PEL_VAL}.
     */
    static final int MAX_FULL_PEL_VAL = 255;

    /**
     * The width in bits of long motion vector components, libvpx's {@code mvlong_width}.
     */
    private static final int MV_LONG_WIDTH = 10;

    /**
     * The lowest threshold multiplier, libvpx's {@code MIN_THRESHMULT}.
     */
    static final int MIN_THRESHMULT = 32;

    /**
     * The highest threshold multiplier, libvpx's {@code MAX_THRESHMULT}.
     */
    static final int MAX_THRESHMULT = 512;

    /**
     * The widening of the zero bins of zero motion from the golden or alternate reference, in
     * 1/128 of the AC step, libvpx's {@code GF_ZEROMV_ZBIN_BOOST}.
     */
    private static final int GF_ZEROMV_ZBIN_BOOST = 12;

    /**
     * The widening of the zero bins of zero motion from the last frame, libvpx's
     * {@code LF_ZEROMV_ZBIN_BOOST}.
     */
    private static final int LF_ZEROMV_ZBIN_BOOST = 6;

    /**
     * The widening of the zero bins of the other inter modes but split motion, libvpx's
     * {@code MV_ZBIN_BOOST}.
     */
    private static final int MV_ZBIN_BOOST = 4;

    /**
     * The offsets of the hexagon around the best point, libvpx's {@code hex} as row, column
     * pairs.
     */
    private static final int[] HEX = {-1, -2, 1, -2, 2, 0, 1, 2, -1, 2, -2, 0};

    /**
     * The three new points of each hexagon after a move, libvpx's {@code next_chkpts}, as
     * {@code [direction 6][point 3][row, column]}.
     */
    private static final int[] NEXT_CHECK_POINTS = {
            -2, 0, -1, -2, 1, -2,
            -1, -2, 1, -2, 2, 0,
            1, -2, 2, 0, 1, 2,
            2, 0, 1, 2, -1, 2,
            1, 2, -1, 2, -2, 0,
            -1, 2, -2, 0, -1, -2
    };

    /**
     * The four neighbors of the final small search, libvpx's {@code neighbors} as row, column
     * pairs.
     */
    private static final int[] NEIGHBORS = {0, -1, -1, 0, 1, 0, 0, 1};

    /**
     * The subblock context implied by each whole-block luma mode in key frames.
     */
    private static final int[] BMODE_OF_MODE = {B_DC_PRED, B_VE_PRED, B_HE_PRED, B_TM_PRED, B_DC_PRED};

    /**
     * Reads and writes eight bytes of a byte array as a little-endian {@code long}.
     */
    private static final VarHandle LONG = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    /**
     * Reads and writes four bytes of a byte array as a little-endian {@code int}.
     */
    private static final VarHandle INT = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);

    /**
     * The encoder whose frame is being coded.
     */
    final RtEncoder enc;

    /**
     * The decoder kernels used for prediction and reconstruction.
     */
    final Vp8Dsp dsp = Vp8Dsp.INSTANCE;

    /**
     * The forward transform and quantization kernels.
     */
    final Vp8EncDsp encDsp = Vp8EncDsp.INSTANCE;

    /**
     * The block metric kernels.
     */
    final RtDsp rtDsp = RtDsp.INSTANCE;

    /**
     * The chroma source samples of the current macroblock (U at {@link #U_OFF}, V at
     * {@link #V_OFF}), and its luma samples during the subblock search, rows {@link #BPS} bytes
     * apart.
     */
    final byte[] src = new byte[BPS * 16];

    /**
     * The plane holding the luma source samples of the current macroblock.
     */
    byte[] srcY;

    /**
     * The index of the current macroblock's top-left luma source sample in {@link #srcY}.
     */
    int srcYOff;

    /**
     * The row stride of {@link #srcY}.
     */
    int srcYStride;

    /**
     * The prediction of the current macroblock.
     */
    final byte[] pred = new byte[BPS * 16];

    /**
     * The source samples of the current macroblock in three interleaved groups of eight 4x4
     * blocks (see {@link RtDsp#forwardDct8}): the upper and lower halves of luma, then chroma.
     */
    final byte[] srcGroups = new byte[3 * RtDsp.GROUP];

    /**
     * The prediction of the current macroblock, grouped like {@link #srcGroups}.
     */
    final byte[] predGroups = new byte[3 * RtDsp.GROUP];

    /**
     * The reconstruction of the current macroblock, grouped like {@link #srcGroups}.
     */
    final byte[] reconGroups = new byte[3 * RtDsp.GROUP];

    /**
     * The coefficients of the three groups, dequantized after quantization.
     */
    final int[] coefGroups = new int[3 * RtDsp.GROUP];

    /**
     * The Y2 block's coefficients in raster order, dequantized after quantization.
     */
    final short[] y2Coef = new short[16];

    /**
     * The quantized levels of the 25 blocks: zigzag position {@code n} of block {@code b} is at
     * {@code n * RtDsp.LEVEL_STRIDE + b}; blocks 0 to 15 are luma, 16 to 23 chroma, 24 is Y2.
     */
    final short[] levels = new short[16 * RtDsp.LEVEL_STRIDE];

    /**
     * The end-of-block position of each block: one past its last nonzero level.
     */
    final int[] eob = new int[32];

    /**
     * The DC coefficients of the 16 luma blocks the inverse Walsh-Hadamard transform of the Y2
     * block gives.
     */
    final short[] dcs = new short[16];

    /**
     * The intermediate rows of the inverse Walsh-Hadamard transform.
     */
    private final int[] whtScratch = new int[16];

    /**
     * The scratch of the motion compensation kernels.
     */
    final byte[] mcScratch = new byte[Vp8Dsp.SCRATCH_SIZE];

    /**
     * The scratch of the loop filter kernels when this coder's thread filters rows.
     */
    final byte[] filterScratch = new byte[Vp8Dsp.SCRATCH_SIZE];

    /**
     * The scratch of the sub-pixel variance kernels.
     */
    final short[] subpelScratch = new short[RtDsp.SCRATCH_SIZE];

    /**
     * The scratch of the sub-pixel variances {@link #subpelAt} measures with one zero offset,
     * kept apart from {@link #subpelScratch} so that they never overwrite the horizontal pass it
     * caches: an implementation of {@link RtDsp#subpelVariance16} may run the identity pass of a
     * zero offset through its scratch.
     */
    private final short[] subpelAxisScratch = new short[RtDsp.SCRATCH_SIZE];

    /**
     * The sums of absolute differences of the candidate predictors of {@link #calSad}.
     */
    private final int[] nearSad = new int[8];

    /**
     * The candidate predictors of {@link #calSad} sorted by their sums.
     */
    final int[] nearSadIdx = new int[8];

    /**
     * The candidate vectors of {@link #mvPred}.
     */
    private final int[] nearMvs = new int[8];

    /**
     * The candidate references of {@link #mvPred}.
     */
    private final int[] nearRefs = new int[8];

    /**
     * The row components sorted by {@link #mvPred}.
     */
    private final int[] mvx = new int[8];

    /**
     * The column components sorted by {@link #mvPred}.
     */
    private final int[] mvy = new int[8];

    /**
     * The reference counts of {@link NearMvs#find}: intra, nearest, near and split.
     */
    final int[] mdCounts = new int[4];

    /**
     * The vectors found by {@link NearMvs#find}: best, nearest and near.
     */
    final int[] nearResult = new int[3];

    /**
     * The motion vector of each mode during the mode search, by mode.
     */
    private final int[] modeMv = new int[10];

    /**
     * The clamped nearest vector of the current macroblock for each sign bias.
     */
    final int[] nearestSb = new int[2];

    /**
     * The clamped near vector of the current macroblock for each sign bias.
     */
    final int[] nearSb = new int[2];

    /**
     * The clamped best reference vector of the current macroblock for each sign bias.
     */
    final int[] bestSb = new int[2];

    /**
     * The order in which the enabled references are searched, intra first.
     */
    final int[] refMap = new int[4];

    /**
     * The subblock modes chosen by the subblock search of the current macroblock.
     */
    final int[] subModes = new int[16];

    /**
     * The levels of the subblocks chosen by the subblock search, kept for the encode.
     */
    final short[] subLevels = new short[16 * 16];

    /**
     * The end-of-block positions of the subblocks chosen by the subblock search.
     */
    final int[] subEobs = new int[16];

    /**
     * The coefficient scratch of the subblock search.
     */
    final short[] subCoef = new short[16];

    /**
     * The motion vector found by the last full-pixel search, packed.
     */
    int searchMv;

    /**
     * The distortion found by the last fractional search.
     */
    int searchDistortion;

    /**
     * The sum of squared differences found by the last fractional search.
     */
    int searchSse;

    /**
     * The search window of motion vectors, in full pixels: smallest column.
     */
    int mvColMin;

    /**
     * The largest column of the search window.
     */
    int mvColMax;

    /**
     * The smallest row of the search window.
     */
    int mvRowMin;

    /**
     * The largest row of the search window.
     */
    int mvRowMax;

    /**
     * The distance of the macroblock from the left frame edge, in 1/8 pixel, negative.
     */
    int mbToLeftEdge;

    /**
     * The distance of the macroblock from the right frame edge, in 1/8 pixel.
     */
    int mbToRightEdge;

    /**
     * The distance of the macroblock from the top frame edge, in 1/8 pixel, negative.
     */
    int mbToTopEdge;

    /**
     * The distance of the macroblock from the bottom frame edge, in 1/8 pixel.
     */
    int mbToBottomEdge;

    /**
     * The index of the current macroblock's luma samples in the frame planes.
     */
    int yOff;

    /**
     * The index of the current macroblock's chroma samples in the frame planes.
     */
    int uvOff;

    /**
     * The mode info index of the current macroblock.
     */
    int idx;

    /**
     * The map index of the current macroblock.
     */
    int mapIdx;

    /**
     * The macroblock row.
     */
    int mbRow;

    /**
     * The macroblock column.
     */
    int mbCol;

    /**
     * The reference frame of the current inter mode.
     */
    FrameBuffer preFrame;

    /**
     * The luma plane searched by the current inter mode.
     */
    byte[] preY;

    /**
     * The quantization matrices of the current macroblock's segment: luma.
     */
    QuantMatrix y1Matrix;

    /**
     * The Y2 quantization matrix of the current macroblock's segment.
     */
    QuantMatrix y2Matrix;

    /**
     * The chroma quantization matrix of the current macroblock's segment.
     */
    QuantMatrix uvMatrix;

    /**
     * The regular luma quantizer of the current macroblock's segment.
     */
    private RegularQuantizer y1Regular;

    /**
     * The regular Y2 quantizer of the current macroblock's segment.
     */
    private RegularQuantizer y2Regular;

    /**
     * The regular chroma quantizer of the current macroblock's segment.
     */
    private RegularQuantizer uvRegular;

    /**
     * Whether blocks are quantized with the regular quantizers, which the good-quality speeds 0
     * to 2 code with and speed 0 also decides with; the fast quantizer of the matrices
     * otherwise.
     */
    boolean regular;

    /**
     * The zero-bin extra of the luma blocks of the current macroblock.
     */
    private int zbinY;

    /**
     * The zero-bin extra of the chroma blocks of the current macroblock.
     */
    private int zbinUv;

    /**
     * The zero-bin extra of the Y2 block of the current macroblock.
     */
    private int zbinY2;

    /**
     * The trellis of the coefficient optimization of the good-quality speed 0.
     */
    private final CoefficientOptimizer optimizer;

    /**
     * The coefficients of the current macroblock's groups before quantization, for the
     * coefficient optimization.
     */
    private final int[] origGroups = new int[3 * RtDsp.GROUP];

    /**
     * The coefficients of a block before quantization, or the dequantized coefficients of a
     * block being optimized, in raster order.
     */
    private final int[] blockScratch = new int[32];

    /**
     * The Y2 quantizer steps of the current macroblock's segment, in raster order.
     */
    private final int[] y2Steps = new int[16];

    /**
     * The above contexts the coefficient optimization updates as it goes through the
     * macroblock's blocks.
     */
    private final int[] trellisAbove = new int[9];

    /**
     * The left contexts the coefficient optimization updates.
     */
    private final int[] trellisLeft = new int[9];

    /**
     * The state of the row of the macroblock being coded.
     */
    private RtRow row;

    /**
     * Whether the current macroblock was examined for dot artifacts, which restarts its count of
     * consecutive zero-motion frames.
     */
    private boolean dotSuppress;

    /**
     * Creates the coder of one thread.
     *
     * @param enc the encoder
     */
    RtMbCoder(RtEncoder enc) {
        this.enc = enc;
        this.rd = new RdMbCoder(this);
        this.optimizer = new CoefficientOptimizer(enc.rdCosts);
    }

    /**
     * The rate-distortion mode decision of the good-quality speeds 0 to 3.
     */
    private final RdMbCoder rd;

    /**
     * Codes one macroblock: chooses its modes, codes and reconstructs it, and records its tokens.
     *
     * @param row    the state of the macroblock's row
     * @param stats  the statistics of the coding thread
     * @param mbRow  the macroblock row
     * @param mbCol  the macroblock column
     */
    void code(RtRow row, RtStats stats, int mbRow, int mbCol) {
        this.row = row;
        setup(mbRow, mbCol);
        var segment = 0;
        if (enc.segmentationEnabled) {
            segment = enc.segmentationMap[mapIdx];
            if (segment > 3) {
                segment = 0;
            }
        }
        setSegment(segment);
        var rdMode = enc.good && enc.speed <= 3;
        var regularCoding = enc.good && enc.speed <= 2;
        regular = regularCoding && enc.speed == 0;
        if (regularCoding) {
            setZbinBoost(0);
        }
        if (enc.keyFrame) {
            stats.totalRate += rdMode ? rd.pickIntraKey(row) : pickIntraMode();
            if (regularCoding) {
                regular = true;
                setZbinBoost(0);
            }
            encodeIntra(stats);
        } else {
            if (rdMode) {
                stats.totalRate += rd.pickInter(row, stats);
                breakout = rd.skipped();
            } else {
                stats.totalRate += pickInterMode(row, stats);
            }
            if (enc.segmentationEnabled && enc.cyclicRefreshEnabled && enc.currentLayer == 0 && segment == 1
                    && (enc.mbRef[idx] != LAST_FRAME || enc.mbMode[idx] != ZEROMV)) {
                setSegment(0);
            }
            if (regularCoding) {
                regular = true;
                setZbinBoost(modeBoost(enc.mbRef[idx], enc.mbMode[idx]));
            }
            var ref = enc.mbRef[idx];
            stats.refUsage[ref]++;
            if (ref == INTRA_FRAME) {
                encodeIntra(stats);
            } else if (enc.mbMode[idx] == SPLITMV) {
                rd.encodeSplit(stats);
            } else {
                predictInter();
                if (breakout) {
                    java.util.Arrays.fill(eob, 0);
                } else {
                    encodeResidual(false);
                }
            }
            updateMaps();
        }
        tokenize(row, stats);
        stats.segmentCounts[enc.mbSegment[idx]]++;
    }

    /**
     * Sets up the position, edges, search window and source samples of a macroblock.
     *
     * @param mbRow the macroblock row
     * @param mbCol the macroblock column
     */
    private void setup(int mbRow, int mbCol) {
        this.mbRow = mbRow;
        this.mbCol = mbCol;
        var mbCols = enc.mbCols;
        var mbRows = enc.mbRows;
        idx = (mbRow + 1) * enc.miStride + mbCol + 1;
        mapIdx = mbRow * mbCols + mbCol;
        var cur = enc.cur;
        yOff = cur.yOrigin + mbRow * 16 * cur.yStride + mbCol * 16;
        uvOff = cur.uvOrigin + mbRow * 8 * cur.uvStride + mbCol * 8;
        mbToLeftEdge = -((mbCol * 16) << 3);
        mbToRightEdge = ((mbCols - 1 - mbCol) * 16) << 3;
        mbToTopEdge = -((mbRow * 16) << 3);
        mbToBottomEdge = ((mbRows - 1 - mbRow) * 16) << 3;
        mvColMin = -((mbCol * 16) + 16);
        mvColMax = ((mbCols - 1 - mbCol) * 16) + 16;
        mvRowMin = -((mbRow * 16) + 16);
        mvRowMax = ((mbRows - 1 - mbRow) * 16) + 16;
        var source = enc.source;
        var sy = source.yOrigin + mbRow * 16 * source.yStride + mbCol * 16;
        srcY = source.y;
        srcYOff = sy;
        srcYStride = source.yStride;
        var suv = source.uvOrigin + mbRow * 8 * source.uvStride + mbCol * 8;
        for (var r = 0; r < 8; r++) {
            var row = suv + r * source.uvStride;
            LONG.set(src, r * BPS + U_OFF, (long) LONG.get(source.u, row));
            LONG.set(src, r * BPS + V_OFF, (long) LONG.get(source.v, row));
        }
        rtDsp.groupMacroblock(source.y, sy, source.yStride, source.u, source.v, suv, source.uvStride, srcGroups);
    }

    /**
     * Copies the 8 rows of both chroma blocks of a macroblock into the chroma group of a grouped
     * buffer: row {@code r} of the group holds U rows {@code r} and {@code r + 4}, then V rows
     * {@code r} and {@code r + 4}.
     *
     * @param u      the U plane
     * @param v      the V plane
     * @param off    the index of the macroblock's top-left chroma sample
     * @param stride the chroma row stride
     * @param groups the grouped buffer
     */
    static void groupChroma(byte[] u, byte[] v, int off, int stride, byte[] groups) {
        for (var r = 0; r < 4; r++) {
            var upper = off + r * stride;
            var lower = upper + 4 * stride;
            var g = 2 * RtDsp.GROUP + 32 * r;
            LONG.set(groups, g, (long) LONG.get(u, upper));
            LONG.set(groups, g + 8, (long) LONG.get(u, lower));
            LONG.set(groups, g + 16, (long) LONG.get(v, upper));
            LONG.set(groups, g + 24, (long) LONG.get(v, lower));
        }
    }

    /**
     * Copies one luma group of a grouped buffer back into its eight rows of a macroblock, the
     * inverse of the luma grouping of {@link RtDsp#groupMacroblock}.
     *
     * @param groups the grouped buffer
     * @param h      the group: {@code 0} for the upper half, {@code 1} for the lower
     * @param plane  the luma plane
     * @param off    the index of the macroblock's top-left sample
     * @param stride the row stride
     */
    static void ungroupLuma(byte[] groups, int h, byte[] plane, int off, int stride) {
        for (var r = 0; r < 4; r++) {
            var upper = off + (8 * h + r) * stride;
            var lower = upper + 4 * stride;
            var g = h * RtDsp.GROUP + 32 * r;
            LONG.set(plane, upper, (long) LONG.get(groups, g));
            LONG.set(plane, upper + 8, (long) LONG.get(groups, g + 8));
            LONG.set(plane, lower, (long) LONG.get(groups, g + 16));
            LONG.set(plane, lower + 8, (long) LONG.get(groups, g + 24));
        }
    }

    /**
     * Copies the chroma group of a grouped buffer back into the chroma rows of a macroblock, the
     * inverse of {@link #groupChroma}.
     *
     * @param groups the grouped buffer
     * @param u      the U plane
     * @param v      the V plane
     * @param off    the index of the macroblock's top-left chroma sample
     * @param stride the chroma row stride
     */
    static void ungroupChroma(byte[] groups, byte[] u, byte[] v, int off, int stride) {
        for (var r = 0; r < 4; r++) {
            var upper = off + r * stride;
            var lower = upper + 4 * stride;
            var g = 2 * RtDsp.GROUP + 32 * r;
            LONG.set(u, upper, (long) LONG.get(groups, g));
            LONG.set(u, lower, (long) LONG.get(groups, g + 8));
            LONG.set(v, upper, (long) LONG.get(groups, g + 16));
            LONG.set(v, lower, (long) LONG.get(groups, g + 24));
        }
    }

    /**
     * Assigns a segment to the current macroblock and selects its quantizers.
     *
     * @param segment the segment, {@code [0, 3]}
     */
    private void setSegment(int segment) {
        enc.mbSegment[idx] = (byte) segment;
        y1Matrix = enc.y1Matrices[segment];
        y2Matrix = enc.y2Matrices[segment];
        uvMatrix = enc.uvMatrices[segment];
        y1Regular = enc.y1Regular[segment];
        y2Regular = enc.y2Regular[segment];
        uvRegular = enc.uvRegular[segment];
    }

    /**
     * Returns the widening of the zero bins of a macroblock coded with a mode, libvpx's
     * {@code zbin_mode_boost}: zero motion from the golden or alternate reference widens them
     * most, other inter modes but split motion a little, intra modes not at all.
     *
     * @param ref the reference frame
     * @param mode the luma prediction mode
     * @return the widening in 1/128 of the AC step
     */
    int modeBoost(int ref, int mode) {
        if (!enc.zbinModeBoostEnabled || ref == INTRA_FRAME) {
            return 0;
        }
        if (mode == ZEROMV) {
            return ref != LAST_FRAME ? GF_ZEROMV_ZBIN_BOOST : LF_ZEROMV_ZBIN_BOOST;
        }
        return mode == SPLITMV ? 0 : MV_ZBIN_BOOST;
    }

    /**
     * Sets the zero-bin extras of the regular quantizers from the frame's over-quantization and
     * a mode's widening, libvpx's {@code vp8_update_zbin_extra}.
     *
     * @param modeBoost the widening of the mode, from {@link #modeBoost}
     */
    void setZbinBoost(int modeBoost) {
        var overQuant = enc.goodZbinOverQuant;
        zbinY = y1Regular.zbinExtra(overQuant + modeBoost);
        zbinUv = uvRegular.zbinExtra(overQuant + modeBoost);
        zbinY2 = y2Regular.zbinExtra(overQuant / 2 + modeBoost);
    }

    /**
     * Quantizes one luma group of the current macroblock, whose DCs the Y2 block codes, with the
     * quantizer {@link #regular} selects.
     *
     * @param group the group, {@code 0} or {@code 1}
     * @param maxima the largest magnitudes {@link RtDsp#forwardDct8} returned for the group
     */
    void quantizeLumaGroup(int group, int maxima) {
        if (!regular) {
            quantizeGroup(group, 1, y1Matrix, maxima);
            return;
        }
        zeroGroups[group] = false;
        for (var j = 0; j < 8; j++) {
            var block = 8 * group + j;
            eob[block] = y1Regular.quantize(coefGroups, group * RtDsp.GROUP + j, 8, 1, zbinY, levels, block,
                    RtDsp.LEVEL_STRIDE);
        }
    }

    /**
     * Quantizes the chroma group of the current macroblock with the quantizer {@link #regular}
     * selects.
     *
     * @param maxima the largest magnitudes {@link RtDsp#forwardDct8} returned for the group
     */
    void quantizeChromaGroup(int maxima) {
        if (!regular) {
            quantizeGroup(2, 0, uvMatrix, maxima);
            return;
        }
        zeroGroups[2] = false;
        for (var j = 0; j < 8; j++) {
            eob[16 + j] = uvRegular.quantize(coefGroups, 2 * RtDsp.GROUP + j, 8, 0, zbinUv, levels, 16 + j,
                    RtDsp.LEVEL_STRIDE);
        }
    }

    /**
     * Quantizes the Y2 block of the current macroblock with the quantizer {@link #regular}
     * selects, like {@link #quantizeY2}.
     *
     * @return the end-of-block position
     */
    int quantizeY2Block() {
        if (!regular) {
            return quantizeY2(y2Coef, y2Matrix);
        }
        return y2Regular.quantize(y2Coef, 0, 1, 0, zbinY2, levels, 24, RtDsp.LEVEL_STRIDE);
    }

    /**
     * Quantizes one 4x4 luma block that codes its DC with the quantizer {@link #regular} selects,
     * replacing its coefficients with their dequantized values.
     *
     * @param coef the coefficients in raster order
     * @param out receives the levels in zigzag order
     * @param outOff the index of the first level
     * @return the end-of-block position
     */
    int quantizeSubblock(short[] coef, short[] out, int outOff) {
        if (!regular) {
            return encDsp.quantizeBlock(coef, 0, out, outOff, y1Matrix) == 0 ? 0 : eobOf(out, outOff);
        }
        return y1Regular.quantize(coef, 0, 1, 0, zbinY, out, outOff, 1);
    }

    /**
     * Returns whether the coding of the current macroblock optimizes its quantized levels,
     * libvpx's {@code optimize} of the good-quality speed 0.
     *
     * @return whether it does
     */
    private boolean optimizing() {
        return regular && enc.good && enc.speed == 0;
    }

    /**
     * Loads the contexts the coefficient optimization starts the current macroblock from.
     */
    void loadTrellisContexts() {
        var ctxOff = mbCol * 9;
        for (var i = 0; i < 9; i++) {
            trellisAbove[i] = enc.aboveContext[ctxOff + i];
            trellisLeft[i] = row.left[i];
        }
    }

    /**
     * Quantizes one 4x4 luma block of a {@code SPLITMV} macroblock, coding its DC, and optimizes
     * its levels at the good-quality speed 0 ({@code optimize_mb} for the block), in raster
     * order after {@link #loadTrellisContexts}.
     *
     * @param coef the coefficients in raster order, replaced with their dequantized values
     * @param block the block, from 0 to 15
     * @return the end-of-block position
     */
    int quantizeSplitBlock(short[] coef, int block) {
        if (!optimizing()) {
            return quantizeSubblock(coef, subLevels, block * 16);
        }
        var s = blockScratch;
        for (var k = 0; k < 16; k++) {
            s[k] = coef[k];
        }
        var e = quantizeSubblock(coef, subLevels, block * 16);
        for (var k = 0; k < 16; k++) {
            s[16 + k] = coef[k];
        }
        var a = block & 3;
        var l = block >> 2;
        e = optimizer.optimize(subLevels, block * 16, 1, s, 16, 1, s, 0, 1, y1Regular.q, e,
                CoefficientOptimizer.TYPE_Y_WITH_DC, trellisAbove[a] + trellisLeft[l], enc.rdMult, enc.rdDiv, false);
        for (var k = 0; k < 16; k++) {
            coef[k] = (short) s[16 + k];
        }
        trellisAbove[a] = trellisLeft[l] = e > 0 ? 1 : 0;
        return e;
    }

    /**
     * Optimizes the levels of the luma and Y2 blocks of a macroblock with a Y2 block, whose
     * coefficients before quantization are in {@link #origGroups} and {@link #blockScratch}
     * ({@code optimize_mb}, {@code check_reset_2nd_coeffs}).
     */
    private void optimizeLuma() {
        var intra = enc.mbRef[idx] == INTRA_FRAME;
        for (var b = 0; b < 16; b++) {
            var at = (b >> 3) * RtDsp.GROUP + (b & 7);
            var a = b & 3;
            var l = b >> 2;
            var e = optimizer.optimize(levels, b, RtDsp.LEVEL_STRIDE, coefGroups, at, 8, origGroups, at, 8,
                    y1Regular.q, eob[b], CoefficientOptimizer.TYPE_Y_NO_DC, trellisAbove[a] + trellisLeft[l],
                    enc.rdMult, enc.rdDiv, intra);
            eob[b] = e;
            trellisAbove[a] = trellisLeft[l] = e > 1 ? 1 : 0;
        }
        var s = blockScratch;
        for (var k = 0; k < 16; k++) {
            s[16 + k] = y2Coef[k];
        }
        var e = optimizer.optimize(levels, 24, RtDsp.LEVEL_STRIDE, s, 16, 1, s, 0, 1, y2Regular.q, eob[24],
                CoefficientOptimizer.TYPE_Y2, trellisAbove[8] + trellisLeft[8], enc.rdMult, enc.rdDiv, intra);
        for (var k = 0; k < 16; k++) {
            y2Coef[k] = (short) s[16 + k];
        }
        eob[24] = CoefficientOptimizer.resetY2(levels, 24, RtDsp.LEVEL_STRIDE, y2Coef, 0, y2Regular.q, e);
        trellisAbove[8] = trellisLeft[8] = eob[24] > 0 ? 1 : 0;
    }

    /**
     * Optimizes the levels of the chroma blocks, whose coefficients before quantization are in
     * {@link #origGroups} ({@code vp8_optimize_mbuv}).
     */
    private void optimizeChroma() {
        var intra = enc.mbRef[idx] == INTRA_FRAME;
        for (var b = 16; b < 24; b++) {
            var at = 2 * RtDsp.GROUP + (b - 16);
            var plane = b > 19 ? 2 : 0;
            var a = 4 + plane + (b & 1);
            var l = 4 + plane + ((b & 3) > 1 ? 1 : 0);
            var e = optimizer.optimize(levels, b, RtDsp.LEVEL_STRIDE, coefGroups, at, 8, origGroups, at, 8,
                    uvRegular.q, eob[b], CoefficientOptimizer.TYPE_UV, trellisAbove[a] + trellisLeft[l], enc.rdMult,
                    enc.rdDiv, intra);
            eob[b] = e;
            trellisAbove[a] = trellisLeft[l] = e > 0 ? 1 : 0;
        }
    }

    /**
     * Updates the cyclic refresh and zero-motion maps after the modes of an inter macroblock
     * are final, the bookkeeping of libvpx's {@code encode_mb_row}.
     */
    void updateMaps() {
        if (enc.currentLayer != 0) {
            return;
        }
        var zeroLast = enc.mbMode[idx] == ZEROMV && enc.mbRef[idx] == LAST_FRAME;
        if (zeroLast) {
            if ((enc.consecZeroLast[mapIdx] & 0xFF) < 255) {
                enc.consecZeroLast[mapIdx]++;
            }
            if ((enc.consecZeroLastMvBias[mapIdx] & 0xFF) < 255) {
                enc.consecZeroLastMvBias[mapIdx]++;
            }
        } else {
            enc.consecZeroLast[mapIdx] = 0;
            enc.consecZeroLastMvBias[mapIdx] = 0;
        }
        if (dotSuppress) {
            enc.consecZeroLastMvBias[mapIdx] = 0;
        }
        if (enc.cyclicRefreshEnabled && enc.segmentationEnabled) {
            var segment = enc.mbSegment[idx];
            enc.segmentationMap[mapIdx] = segment;
            if (segment != 0) {
                enc.cyclicRefreshMap[mapIdx] = -1;
            } else if (zeroLast) {
                if (enc.cyclicRefreshMap[mapIdx] == 1) {
                    enc.cyclicRefreshMap[mapIdx] = 0;
                }
            } else {
                enc.cyclicRefreshMap[mapIdx] = 1;
            }
        }
    }

    /**
     * Returns the rate-distortion score of a rate and a distortion, libvpx's {@code RDCOST}.
     *
     * @param rate the rate in 1/256 bit
     * @param distortion the sum of squared errors
     * @return the score
     */
    int rdCost(int rate, int distortion) {
        return (int) (((128 + (long) rate * enc.rdMult) >> 8) + (long) enc.rdDiv * distortion);
    }

    /**
     * Chooses the luma and chroma intra modes of a key-frame macroblock, libvpx's
     * {@code vp8_pick_intra_mode}.
     *
     * @return the rate of the chosen luma mode, in 1/256 bit
     */
    private int pickIntraMode() {
        enc.mbRef[idx] = INTRA_FRAME;
        enc.mbMv[idx] = 0;
        pickIntraUvMode(rtDsp);
        var y = enc.cur.y;
        var yStride = enc.cur.yStride;
        var error16x16 = Integer.MAX_VALUE;
        var bestMode = DC_PRED;
        var bestSse = 0;
        var bestRate = 0;
        srcStatsValid = false;
        for (var mode = DC_PRED; mode <= TM_PRED; mode++) {
            var packed = intraError(mode);
            var distortion = RtDsp.variance256(packed);
            var rate = enc.mbModeCost[mode];
            var rd = rdCost(rate, distortion);
            if (error16x16 > rd) {
                error16x16 = rd;
                bestMode = mode;
                bestSse = RtDsp.sse(packed);
                bestRate = rate;
            }
        }
        enc.mbMode[idx] = (byte) bestMode;
        var cost = enc.mbModeCost[B_PRED];
        var error4x4 = pickIntra4x4(cost, bestSse);
        if (error4x4 < error16x16) {
            enc.mbMode[idx] = (byte) B_PRED;
            bestRate = subblockRate;
        }
        return bestRate;
    }

    /**
     * The column sums of the source luma of the current macroblock, valid with
     * {@link #srcStatsValid}.
     */
    private final int[] srcColumns = new int[16];

    /**
     * The sum of the squared source luma samples of the current macroblock, valid with
     * {@link #srcStatsValid}.
     */
    private int srcSquares;

    /**
     * The sum of the source luma samples of the current macroblock, valid with
     * {@link #srcStatsValid}.
     */
    private int srcSum;

    /**
     * Whether {@link #srcColumns}, {@link #srcSquares} and {@link #srcSum} describe the current
     * macroblock.
     */
    private boolean srcStatsValid;

    /**
     * Returns the packed sums of the differences between the source luma of the current
     * macroblock and its whole-block intra prediction, as {@link RtDsp#variance16} measures them
     * against the prediction {@link IntraPredictor#predictBlock} forms.
     *
     * <p>The DC and vertical predictions are measured from the source's sums without forming
     * them: against a constant {@code d} the sums are {@code S - 256 d} and
     * {@code Q - 2 d S + 256 d^2}, and against the row above {@code a} they are
     * {@code S - 16 sum(a)} and {@code Q - 2 sum(a[c] C[c]) + 16 sum(a[c]^2)}, where {@code S},
     * {@code Q} and {@code C} are the source's sum, sum of squares and column sums. The other
     * modes are formed in the frame being coded and measured.
     *
     * @param mode the mode: {@code DC_PRED}, {@code V_PRED}, {@code H_PRED} or {@code TM_PRED}
     * @return the packed sums, see {@link RtDsp#sse(long)} and {@link RtDsp#sum(long)}
     */
    private long intraError(int mode) {
        var cur = enc.cur;
        var y = cur.y;
        var stride = cur.yStride;
        if (mode != DC_PRED && mode != V_PRED) {
            IntraPredictor.predictBlock(mode, y, yOff, stride, 16, mbRow > 0, mbCol > 0);
            return rtDsp.variance16(srcY, srcYOff, srcYStride, y, yOff, stride);
        }
        if (!srcStatsValid) {
            srcSquares = rtDsp.blockStats16(srcY, srcYOff, srcYStride, srcColumns);
            var sum = 0;
            for (var c = 0; c < 16; c++) {
                sum += srcColumns[c];
            }
            srcSum = sum;
            srcStatsValid = true;
        }
        var above = yOff - stride;
        if (mode == V_PRED) {
            var aboveSum = 0;
            var cross = 0;
            var aboveSquares = 0;
            for (var c = 0; c < 16; c++) {
                var a = y[above + c] & 0xFF;
                aboveSum += a;
                cross += a * srcColumns[c];
                aboveSquares += a * a;
            }
            return RtDsp.pack(srcSquares - 2 * cross + 16 * aboveSquares, srcSum - 16 * aboveSum);
        }
        int dc;
        if (mbRow > 0 && mbCol > 0) {
            var sum = 0;
            for (var i = 0; i < 16; i++) {
                sum += (y[above + i] & 0xFF) + (y[yOff - 1 + i * stride] & 0xFF);
            }
            dc = (sum + 16) >> 5;
        } else if (mbRow > 0) {
            var sum = 0;
            for (var i = 0; i < 16; i++) {
                sum += y[above + i] & 0xFF;
            }
            dc = (sum + 8) >> 4;
        } else if (mbCol > 0) {
            var sum = 0;
            for (var i = 0; i < 16; i++) {
                sum += y[yOff - 1 + i * stride] & 0xFF;
            }
            dc = (sum + 8) >> 4;
        } else {
            dc = 128;
        }
        return RtDsp.pack(srcSquares - 2 * dc * srcSum + 256 * dc * dc, srcSum - 256 * dc);
    }

    /**
     * The rate of the last subblock search, in 1/256 bit.
     */
    private int subblockRate;

    /**
     * Chooses the mode of each 4x4 luma subblock, coding and reconstructing each in turn,
     * libvpx's {@code pick_intra4x4mby_modes}.
     *
     * <p>The luma source is first copied into {@link #src}, where the key-frame encoder's
     * kernels read it. The search stops early, returning {@link Integer#MAX_VALUE}, once the summed
     * distortion exceeds {@code bestDistortion}. On completion {@link #subModes},
     * {@link #subLevels}, {@link #subEobs}, the prediction and the reconstruction hold the
     * choices, and {@link #subblockRate} their rate.
     *
     * @param modeCost       the cost of signaling {@code B_PRED}, in 1/256 bit
     * @param bestDistortion the distortion above which the search gives up
     * @return the rate-distortion score, or {@link Integer#MAX_VALUE}
     */
    private int pickIntra4x4(int modeCost, int bestDistortion) {
        for (var r = 0; r < 16; r++) {
            var row = srcYOff + r * srcYStride;
            LONG.set(src, r * BPS, (long) LONG.get(srcY, row));
            LONG.set(src, r * BPS + 8, (long) LONG.get(srcY, row + 8));
        }
        var cur = enc.cur;
        var y = cur.y;
        var yStride = cur.yStride;
        var above = yOff - yStride + 16;
        for (var r = 4; r < 16; r += 4) {
            INT.set(y, above + r * yStride, (int) INT.get(y, above));
        }
        var cost = modeCost;
        var distortion = 0;
        var keyFrame = enc.keyFrame;
        var i = 0;
        for (; i < 16; i++) {
            var blockOff = yOff + (i >> 2) * 4 * yStride + (i & 3) * 4;
            var srcOff = (i >> 2) * 4 * BPS + (i & 3) * 4;
            int costBase;
            int[] costs;
            if (keyFrame) {
                costs = enc.kfBmodeCosts;
                costBase = (aboveBlockMode(i) * 10 + leftBlockMode(i)) * 10;
            } else {
                costs = enc.interBmodeCosts;
                costBase = 0;
            }
            var bestRd = Integer.MAX_VALUE;
            var bestMode = B_DC_PRED;
            var bestRate = 0;
            var bestDist = 0;
            for (var mode = B_DC_PRED; mode <= B_HU_PRED; mode++) {
                var rate = costs[costBase + mode];
                IntraPredictor.predictSubblock(mode, y, blockOff, yStride);
                var d = rtDsp.sse4x4(src, srcOff, BPS, y, blockOff, yStride);
                var rd = rdCost(rate, d);
                if (rd < bestRd) {
                    bestRate = rate;
                    bestDist = d;
                    bestRd = rd;
                    bestMode = mode;
                }
            }
            subModes[i] = bestMode;
            enc.bModes[idx * 16 + i] = (byte) bestMode;
            encodeSubblock(i, bestMode);
            cost += bestRate;
            distortion += bestDist;
            if (distortion > bestDistortion) {
                break;
            }
        }
        subblockRate = cost;
        return i == 16 ? rdCost(cost, distortion) : Integer.MAX_VALUE;
    }

    /**
     * Returns the subblock mode above a subblock of the current macroblock, libvpx's
     * {@code above_block_mode}.
     *
     * @param i the subblock
     * @return the mode
     */
    int aboveBlockMode(int i) {
        if (i >= 4) {
            return subModes[i - 4];
        }
        var above = idx - enc.miStride;
        var mode = enc.mbMode[above];
        return mode == B_PRED ? enc.bModes[above * 16 + i + 12] : BMODE_OF_MODE[mode];
    }

    /**
     * Returns the subblock mode left of a subblock of the current macroblock, libvpx's
     * {@code left_block_mode}.
     *
     * @param i the subblock
     * @return the mode
     */
    int leftBlockMode(int i) {
        if ((i & 3) != 0) {
            return subModes[i - 1];
        }
        var left = idx - 1;
        var mode = enc.mbMode[left];
        return mode == B_PRED ? enc.bModes[left * 16 + i + 3] : BMODE_OF_MODE[mode];
    }

    /**
     * Predicts, codes and reconstructs one 4x4 luma subblock, libvpx's
     * {@code vp8_encode_intra4x4block}, keeping its levels in {@link #subLevels}.
     *
     * @param i    the subblock
     * @param mode the subblock mode
     */
    private void encodeSubblock(int i, int mode) {
        var cur = enc.cur;
        var y = cur.y;
        var yStride = cur.yStride;
        var blockOff = yOff + (i >> 2) * 4 * yStride + (i & 3) * 4;
        var bpsOff = (i >> 2) * 4 * BPS + (i & 3) * 4;
        IntraPredictor.predictSubblock(mode, y, blockOff, yStride);
        for (var r = 0; r < 4; r++) {
            INT.set(pred, bpsOff + r * BPS, (int) INT.get(y, blockOff + r * yStride));
        }
        var c = subCoef;
        encDsp.fTransform(src, bpsOff, pred, bpsOff, c, 0);
        var e = quantizeSubblock(c, subLevels, i * 16);
        subEobs[i] = e;
        if (e > 1) {
            dsp.idctAdd(c, 0, y, blockOff, yStride);
        } else {
            dsp.dcOnlyAdd(c[0], y, blockOff, yStride);
        }
    }

    /**
     * Codes the 16 luma subblocks of the current {@code B_PRED} macroblock again with their
     * chosen modes and the regular quantizer, libvpx's {@code vp8_encode_intra4x4mby} after a
     * decision made with the fast quantizer.
     */
    private void reencodeSubblocks() {
        for (var r = 0; r < 16; r++) {
            System.arraycopy(srcY, srcYOff + r * srcYStride, src, r * BPS, 16);
        }
        for (var i = 0; i < 16; i++) {
            encodeSubblock(i, enc.bModes[idx * 16 + i]);
        }
    }

    /**
     * Returns one past the position of the last nonzero level of a block.
     *
     * @param l   the levels in zigzag order
     * @param off the index of the block's first level
     * @return the end-of-block position, {@code 0} when every level is zero
     */
    static int eobOf(short[] l, int off) {
        for (var n = 15; n >= 0; n--) {
            if (l[off + n] != 0) {
                return n + 1;
            }
        }
        return 0;
    }

    /**
     * The edge samples of the chroma intra predictions, laid out as
     * {@link RtDsp#chromaIntraErrors} reads them.
     */
    private final byte[] uvEdges = new byte[48];

    /**
     * The four chroma intra prediction errors.
     */
    private final int[] uvErrors = new int[4];

    /**
     * Chooses the chroma intra mode with the smallest prediction error, libvpx's
     * {@code pick_intra_mbuv_mode}.
     *
     * @implNote This implementation measures the errors with the kernels it is given: a key frame
     * passes the block metric kernels, since it picks the chroma mode of every
     * macroblock, while an inter frame, which picks it only for the few macroblocks it
     * codes as intra, passes {@link RtDsp#SCALAR}. A vector kernel called a few times per
     * frame would reach the C2 compiler only after hundreds of frames in a busy JVM, and
     * would allocate every vector it computes until then.
     * @param kernels the kernels that measure the prediction errors
     */
    private void pickIntraUvMode(RtDsp kernels) {
        var cur = enc.cur;
        var u = cur.u;
        var v = cur.v;
        var stride = cur.uvStride;
        var above = uvOff - stride;
        var upAvailable = mbRow > 0;
        var leftAvailable = mbCol > 0;
        int expectedUdc;
        int expectedVdc;
        if (!upAvailable && !leftAvailable) {
            expectedUdc = 128;
            expectedVdc = 128;
        } else {
            var shift = 2;
            var uAverage = 0;
            var vAverage = 0;
            if (upAvailable) {
                for (var i = 0; i < 8; i++) {
                    uAverage += u[above + i] & 0xFF;
                    vAverage += v[above + i] & 0xFF;
                }
                shift++;
            }
            if (leftAvailable) {
                for (var i = 0; i < 8; i++) {
                    uAverage += u[uvOff + i * stride - 1] & 0xFF;
                    vAverage += v[uvOff + i * stride - 1] & 0xFF;
                }
                shift++;
            }
            expectedUdc = (uAverage + (1 << (shift - 1))) >> shift;
            expectedVdc = (vAverage + (1 << (shift - 1))) >> shift;
        }
        var edges = uvEdges;
        LONG.set(edges, 0, (long) LONG.get(u, above));
        LONG.set(edges, 8, (long) LONG.get(v, above));
        for (var i = 0; i < 8; i++) {
            edges[16 + i] = u[uvOff + i * stride - 1];
            edges[24 + i] = v[uvOff + i * stride - 1];
        }
        edges[32] = u[above - 1];
        edges[33] = v[above - 1];
        var errors = uvErrors;
        kernels.chromaIntraErrors(src, U_OFF, BPS, edges, expectedUdc, expectedVdc, errors);
        long dcError = errors[0];
        long vError = errors[1];
        long hError = errors[2];
        long tmError = errors[3];
        var best = DC_PRED;
        var bestError = dcError;
        if (vError < bestError) {
            best = V_PRED;
            bestError = vError;
        }
        if (hError < bestError) {
            best = H_PRED;
            bestError = hError;
        }
        if (tmError < bestError) {
            best = TM_PRED;
        }
        enc.mbUvMode[idx] = (byte) best;
    }

    /**
     * Codes the intra modes chosen for the current macroblock: predicts each plane in place,
     * transforms, quantizes and reconstructs it, and counts the modes, libvpx's
     * {@code vp8_encode_intra16x16mby}, {@code vp8_encode_intra4x4mby} and
     * {@code vp8_encode_intra16x16mbuv}.
     *
     * @param stats the statistics of the coding thread
     */
    void encodeIntra(RtStats stats) {
        var cur = enc.cur;
        var mode = enc.mbMode[idx];
        var uvMode = enc.mbUvMode[idx];
        IntraPredictor.predictBlock(uvMode, cur.u, uvOff, cur.uvStride, 8, mbRow > 0, mbCol > 0);
        IntraPredictor.predictBlock(uvMode, cur.v, uvOff, cur.uvStride, 8, mbRow > 0, mbCol > 0);
        if (mode == B_PRED) {
            if (regular && enc.speed > 0) {
                reencodeSubblocks();
            }
            for (var i = 0; i < 16; i++) {
                for (var n = 0; n < 16; n++) {
                    levels[n * RtDsp.LEVEL_STRIDE + i] = subLevels[i * 16 + n];
                }
            }
            System.arraycopy(subEobs, 0, eob, 0, 16);
        } else {
            IntraPredictor.predictBlock(mode, cur.y, yOff, cur.yStride, 16, mbRow > 0, mbCol > 0);
        }
        encodeResidual(mode == B_PRED);
        stats.ymodeCounts[mode]++;
        stats.uvModeCounts[uvMode]++;
    }

    /**
     * Transforms, quantizes and reconstructs the residual of the current macroblock, whose
     * prediction is already in the frame being coded; for {@code B_PRED} macroblocks the luma
     * blocks were coded during the subblock search and only chroma is coded here.
     *
     * <p>The blocks are coded in three groups of eight with the grouped kernels of
     * {@link RtDsp}; a group whose blocks all quantize to zero, with no DC from the Y2 block,
     * keeps its prediction.
     *
     * @param subblocks whether the luma blocks were coded as subblocks
     */
    void encodeResidual(boolean subblocks) {
        var cur = enc.cur;
        var yStride = cur.yStride;
        var uvStride = cur.uvStride;
        var coefs = coefGroups;
        var optimize = optimizing();
        if (optimize) {
            loadTrellisContexts();
        }
        if (!subblocks) {
            rtDsp.groupMacroblock(cur.y, yOff, yStride, cur.u, cur.v, uvOff, uvStride, predGroups);
            var upper = rtDsp.forwardDct8(srcGroups, 0, predGroups, 0, coefs, 0);
            var lower = rtDsp.forwardDct8(srcGroups, RtDsp.GROUP, predGroups, RtDsp.GROUP, coefs, RtDsp.GROUP);
            forwardWht(coefs, y2Coef);
            if (optimize) {
                System.arraycopy(coefs, 0, origGroups, 0, 2 * RtDsp.GROUP);
                for (var k = 0; k < 16; k++) {
                    blockScratch[k] = y2Coef[k];
                }
            }
            quantizeLumaGroup(0, upper);
            quantizeLumaGroup(1, lower);
            eob[24] = quantizeY2Block();
            if (optimize) {
                optimizeLuma();
            }
        }
        if (subblocks) {
            groupChroma(cur.u, cur.v, uvOff, uvStride, predGroups);
        }
        var chromaMax = rtDsp.forwardDct8(srcGroups, 2 * RtDsp.GROUP, predGroups, 2 * RtDsp.GROUP, coefs,
                2 * RtDsp.GROUP);
        if (optimize) {
            System.arraycopy(coefs, 2 * RtDsp.GROUP, origGroups, 2 * RtDsp.GROUP, RtDsp.GROUP);
        }
        quantizeChromaGroup(chromaMax);
        if (optimize) {
            optimizeChroma();
        }
        if (!subblocks) {
            inverseWht(y2Coef, eob[24], dcs, whtScratch);
            var dc = dcs;
            for (var h = 0; h < 2; h++) {
                var any = false;
                for (var j = 0; j < 8; j++) {
                    var block = 8 * h + j;
                    coefs[h * RtDsp.GROUP + j] = dc[block];
                    any |= eob[block] > 1 || ((dc[block] + 4) >> 3) != 0;
                }
                if (any) {
                    if (zeroGroups[h]) {
                        java.util.Arrays.fill(coefs, h * RtDsp.GROUP + 8, (h + 1) * RtDsp.GROUP, 0);
                    }
                    rtDsp.inverseDctAdd8(coefs, h * RtDsp.GROUP, predGroups, h * RtDsp.GROUP, reconGroups,
                            h * RtDsp.GROUP);
                    ungroupLuma(reconGroups, h, cur.y, yOff, yStride);
                }
            }
        }
        var chroma = false;
        for (var i = 16; i < 24; i++) {
            chroma |= eob[i] != 0;
        }
        if (chroma) {
            rtDsp.inverseDctAdd8(coefs, 2 * RtDsp.GROUP, predGroups, 2 * RtDsp.GROUP, reconGroups, 2 * RtDsp.GROUP);
            ungroupChroma(reconGroups, cur.u, cur.v, uvOff, uvStride);
        }
    }

    /**
     * Whether each group's levels are all zero after {@link #quantizeGroup}, which then leaves
     * the group's coefficients and levels as they are.
     */
    final boolean[] zeroGroups = new boolean[3];

    /**
     * Quantizes one group of eight blocks with {@link RtDsp#quantize8}, or, when every level is
     * zero, only sets the group's ends of block to zero: the coefficients of such a group are not
     * dequantized and its levels, which no token reads, are not written.
     *
     * <p>A level is zero exactly when its coefficient's magnitude is at most the matrix's zero
     * threshold, since {@code (n * iq + bias) >> 17} is zero exactly for
     * {@code n <= (2^17 - 1 - bias) / iq}; the group is zero when its largest AC magnitude, and
     * its largest DC magnitude when the DC is coded, are within the thresholds.
     *
     * @param group  the group: {@code 0} and {@code 1} for luma, {@code 2} for chroma
     * @param first  the first zigzag position coded
     * @param m      the quantization matrix
     * @param maxima the largest magnitudes {@link RtDsp#forwardDct8} returned for the group
     */
    void quantizeGroup(int group, int first, QuantMatrix m, int maxima) {
        var coefOff = group * RtDsp.GROUP;
        if ((maxima & 0xFFFF) <= m.zthresh[1] && (first > 0 || (maxima >>> 16) <= m.zthresh[0])) {
            zeroGroups[group] = true;
            java.util.Arrays.fill(eob, 8 * group, 8 * group + 8, 0);
            return;
        }
        zeroGroups[group] = false;
        rtDsp.quantize8(coefGroups, coefOff, first, m, levels, 8 * group, eob, 8 * group);
    }

    /**
     * Applies the forward Walsh-Hadamard transform to the DC coefficients of the 16 luma blocks,
     * libwebp's {@code FTransformWHT}.
     *
     * @param coefs the grouped coefficients, whose DC of block {@code b} is at
     *              {@code (b >> 3) * RtDsp.GROUP + (b & 7)}
     * @param out   receives the 16 Y2 coefficients in raster order
     */
    void forwardWht(int[] coefs, short[] out) {
        var tmp = whtScratch;
        for (var i = 0; i < 4; i++) {
            var base = (i >> 1) * RtDsp.GROUP + (i & 1) * 4;
            var a0 = coefs[base] + coefs[base + 2];
            var a1 = coefs[base + 1] + coefs[base + 3];
            var a2 = coefs[base + 1] - coefs[base + 3];
            var a3 = coefs[base] - coefs[base + 2];
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
     * Quantizes the Y2 block, replacing its coefficients with their dequantized values and
     * storing its levels as block 24 of {@link #levels}, exactly as
     * {@link Vp8EncDsp#quantizeBlock} does with the real-time encoder's matrices, which have no
     * sharpening; the products stay below {@code 2^31} since magnitudes and reciprocals are at
     * most {@code 2^15}.
     *
     * @param c the coefficients in raster order
     * @param m the Y2 quantization matrix
     * @return the end-of-block position
     */
    int quantizeY2(short[] c, QuantMatrix m) {
        var last = 0;
        for (var n = 0; n < 16; n++) {
            var j = RtDsp.ZIGZAG[n];
            int v = c[j];
            var magnitude = Math.abs(v);
            if (magnitude <= m.zthresh[j]) {
                c[j] = 0;
                levels[n * RtDsp.LEVEL_STRIDE + 24] = 0;
                continue;
            }
            var level = Math.min((magnitude * m.iq[j] + m.bias[j]) >>> QuantMatrix.QFIX, RtDsp.MAX_LEVEL);
            if (v < 0) {
                level = -level;
            }
            c[j] = (short) (level * m.q[j]);
            levels[n * RtDsp.LEVEL_STRIDE + 24] = (short) level;
            if (level != 0) {
                last = n + 1;
            }
        }
        return last;
    }

    /**
     * Applies the inverse Walsh-Hadamard transform to a dequantized Y2 block, storing the DC
     * coefficients of the 16 luma blocks, exactly as the decoder does
     * (libvpx's {@code vp8_short_inv_walsh4x4_c} with its 16-bit intermediate values).
     *
     * @param c the dequantized Y2 coefficients in raster order
     * @param e the Y2 block's end-of-block position
     * @param dc receives the 16 luma DC coefficients in raster block order
     * @param t 16 values of scratch
     */
    static void inverseWht(short[] c, int e, short[] dc, int[] t) {
        if (e > 1) {
            for (var i = 0; i < 4; i++) {
                var a1 = c[i] + c[12 + i];
                var b1 = c[4 + i] + c[8 + i];
                var c1 = c[4 + i] - c[8 + i];
                var d1 = c[i] - c[12 + i];
                t[i] = (short) (a1 + b1);
                t[4 + i] = (short) (c1 + d1);
                t[8 + i] = (short) (a1 - b1);
                t[12 + i] = (short) (d1 - c1);
            }
            for (var i = 0; i < 4; i++) {
                var a1 = t[4 * i] + t[4 * i + 3];
                var b1 = t[4 * i + 1] + t[4 * i + 2];
                var c1 = t[4 * i + 1] - t[4 * i + 2];
                var d1 = t[4 * i] - t[4 * i + 3];
                dc[4 * i] = (short) ((a1 + b1 + 3) >> 3);
                dc[4 * i + 1] = (short) ((c1 + d1 + 3) >> 3);
                dc[4 * i + 2] = (short) ((a1 - b1 + 3) >> 3);
                dc[4 * i + 3] = (short) ((d1 - c1 + 3) >> 3);
            }
        } else if (e == 1) {
            java.util.Arrays.fill(dc, (short) ((c[0] + 3) >> 3));
        } else {
            java.util.Arrays.fill(dc, (short) 0);
        }
    }

    /**
     * Forms the inter prediction of the current macroblock in the frame being coded, from its
     * reference and motion vector, like the decoder's prediction of an unsplit macroblock.
     */
    void predictInter() {
        var ref = enc.refBuffer(enc.mbRef[idx]);
        var cur = enc.cur;
        var mv = enc.mbMv[idx];
        var row = Mv.row(mv);
        var col = Mv.col(mv);
        predictBlock(ref.y, cur.y, yOff, cur.yStride, row, col, 16);
        var uvRow = (short) (row + (1 | (row >> 31))) / 2;
        var uvCol = (short) (col + (1 | (col >> 31))) / 2;
        uvCol = 2 * uvCol < mbToLeftEdge - (19 << 3) ? (mbToLeftEdge - (16 << 3)) >> 1 : uvCol;
        uvCol = 2 * uvCol > mbToRightEdge + (18 << 3) ? (mbToRightEdge + (16 << 3)) >> 1 : uvCol;
        uvRow = 2 * uvRow < mbToTopEdge - (19 << 3) ? (mbToTopEdge - (16 << 3)) >> 1 : uvRow;
        uvRow = 2 * uvRow > mbToBottomEdge + (18 << 3) ? (mbToBottomEdge + (16 << 3)) >> 1 : uvRow;
        predictBlock(ref.u, cur.u, uvOff, cur.uvStride, uvRow, uvCol, 8);
        predictBlock(ref.v, cur.v, uvOff, cur.uvStride, uvRow, uvCol, 8);
    }

    /**
     * Predicts one block from a reference plane with the six-tap filters.
     *
     * <p>The encoder's motion vectors keep every block and its filter taps inside the
     * reference border, which both planes share with the frame being coded.
     *
     * @param ref    the reference plane
     * @param dst    the destination plane
     * @param off    the index of the block in both planes
     * @param stride the row stride of both planes
     * @param row    the vertical displacement in 1/8 pixel
     * @param col    the horizontal displacement in 1/8 pixel
     * @param size   the block side
     */
    void predictBlock(byte[] ref, byte[] dst, int off, int stride, int row, int col, int size) {
        var srcOff = off + (row >> 3) * stride + (col >> 3);
        var mx = col & 7;
        var my = row & 7;
        if ((mx | my) == 0) {
            dsp.copy(ref, srcOff, stride, dst, off, stride, size, size);
        } else {
            dsp.sixtap(ref, srcOff, stride, dst, off, stride, size, size, mx, my, mcScratch);
        }
    }

    /**
     * Records the coefficient tokens of the current macroblock, or marks it skipped when it has
     * none, libvpx's {@code vp8_tokenize_mb}.
     *
     * @param row   the state of the macroblock's row
     * @param stats the statistics of the coding thread
     */
    void tokenize(RtRow row, RtStats stats) {
        var hasY2 = enc.mbMode[idx] != B_PRED && enc.mbMode[idx] != SPLITMV;
        var skip = true;
        if (hasY2) {
            for (var i = 0; i < 16; i++) {
                if (eob[i] > 1) {
                    skip = false;
                    break;
                }
            }
            skip &= eob[24] == 0;
        } else {
            for (var i = 0; i < 16; i++) {
                if (eob[i] != 0) {
                    skip = false;
                    break;
                }
            }
        }
        for (var i = 16; i < 24 && skip; i++) {
            skip = eob[i] == 0;
        }
        var above = enc.aboveContext;
        var ctxOff = mbCol * 9;
        var left = row.left;
        enc.mbSkip[idx] = (byte) (skip ? 1 : 0);
        if (skip) {
            stats.skipTrueCount++;
            var count = hasY2 ? 9 : 8;
            for (var i = 0; i < count; i++) {
                above[ctxOff + i] = 0;
                left[i] = 0;
            }
            return;
        }
        var tokens = row.tokens;
        var counts = stats.coefCounts;
        int type;
        int first;
        if (hasY2) {
            var flag = recordBlock(tokens, counts, 24, 0, eob[24] - 1, 1, above[ctxOff + 8] + left[8]);
            above[ctxOff + 8] = (byte) flag;
            left[8] = (byte) flag;
            type = 0;
            first = 1;
        } else {
            type = 3;
            first = 0;
        }
        for (var i = 0; i < 16; i++) {
            var a = ctxOff + (i & 3);
            var l = i >> 2;
            var flag = recordBlock(tokens, counts, i, first, eob[i] - 1, type, above[a] + left[l]);
            above[a] = (byte) flag;
            left[l] = (byte) flag;
        }
        for (var i = 16; i < 24; i++) {
            var plane = i > 19 ? 2 : 0;
            var a = ctxOff + 4 + plane + (i & 1);
            var l = 4 + plane + ((i & 3) > 1 ? 1 : 0);
            var flag = recordBlock(tokens, counts, i, 0, eob[i] - 1, 2, above[a] + left[l]);
            above[a] = (byte) flag;
            left[l] = (byte) flag;
        }
    }

    /**
     * Records the tokens of one block and counts the branches they code.
     *
     * <p>Each token is a branch of the coefficient token tree (RFC 6386 section 13.2) coded with
     * the probability of its block type, band and context, or a sign or extra bit coded with a
     * constant probability. The end-of-block branch is not coded after a zero token.
     *
     * @param tokens the row's token buffer
     * @param counts the branch counts
     * @param block  the block, {@code [0, 24]}
     * @param first  the first coded position: {@code 1} for luma blocks with a Y2 block
     * @param last   the position of the last nonzero level, or a value below {@code first}
     * @param type   the block type
     * @param ctx    the context from the neighboring blocks, {@code [0, 2]}
     * @return {@code 1} when the block has a nonzero level, {@code 0} otherwise
     */
    private int recordBlock(TokenBuffer tokens, int[] counts, int block, int first, int last, int type, int ctx) {
        var buf = tokens.reserve(MAX_BLOCK_TOKENS);
        var at = tokens.size();
        var n = first;
        var base = EncProba.index(type, n, ctx);
        if (last < first) {
            buf[at++] = (short) base;
            counts[2 * base]++;
            tokens.size(at);
            return 0;
        }
        var l = levels;
        buf[at++] = (short) ((1 << 15) | base);
        counts[2 * base + 1]++;
        while (n < 16) {
            int c = l[n++ * RtDsp.LEVEL_STRIDE + block];
            var sign = c < 0 ? 1 : 0;
            var v = sign != 0 ? -c : c;
            var p = base + 1;
            if (v == 0) {
                buf[at++] = (short) p;
                counts[2 * p]++;
                base = EncProba.index(type, Vp8Tables.BANDS[n], 0);
                continue;
            }
            buf[at++] = (short) ((1 << 15) | p);
            counts[2 * p + 1]++;
            p = base + 2;
            if (v == 1) {
                buf[at++] = (short) p;
                counts[2 * p]++;
                base = EncProba.index(type, Vp8Tables.BANDS[n], 1);
            } else {
                buf[at++] = (short) ((1 << 15) | p);
                counts[2 * p + 1]++;
                var big = v > 4 ? 1 : 0;
                p = base + 3;
                buf[at++] = (short) ((big << 15) | p);
                counts[2 * p + big]++;
                if (big == 0) {
                    var notTwo = v != 2 ? 1 : 0;
                    p = base + 4;
                    buf[at++] = (short) ((notTwo << 15) | p);
                    counts[2 * p + notTwo]++;
                    if (notTwo != 0) {
                        var four = v == 4 ? 1 : 0;
                        p = base + 5;
                        buf[at++] = (short) ((four << 15) | p);
                        counts[2 * p + four]++;
                    }
                } else {
                    var huge = v > 10 ? 1 : 0;
                    p = base + 6;
                    buf[at++] = (short) ((huge << 15) | p);
                    counts[2 * p + huge]++;
                    if (huge == 0) {
                        var cat2 = v > 6 ? 1 : 0;
                        p = base + 7;
                        buf[at++] = (short) ((cat2 << 15) | p);
                        counts[2 * p + cat2]++;
                        if (cat2 == 0) {
                            buf[at++] = (short) ((v == 6 ? 1 << 15 : 0) | EncProba.FIXED_PROBA_BIT | 159);
                        } else {
                            buf[at++] = (short) ((v >= 9 ? 1 << 15 : 0) | EncProba.FIXED_PROBA_BIT | 165);
                            buf[at++] = (short) (((v & 1) == 0 ? 1 << 15 : 0) | EncProba.FIXED_PROBA_BIT | 145);
                        }
                    } else {
                        int mask;
                        int[] tab;
                        var residue = v - 3;
                        int high;
                        int low;
                        if (residue < (8 << 1)) {
                            high = 0;
                            low = 0;
                            residue -= 8;
                            mask = 1 << 2;
                            tab = Vp8Tables.CAT3_PROBS;
                        } else if (residue < (8 << 2)) {
                            high = 0;
                            low = 1;
                            residue -= 8 << 1;
                            mask = 1 << 3;
                            tab = Vp8Tables.CAT4_PROBS;
                        } else if (residue < (8 << 3)) {
                            high = 1;
                            low = 0;
                            residue -= 8 << 2;
                            mask = 1 << 4;
                            tab = Vp8Tables.CAT5_PROBS;
                        } else {
                            high = 1;
                            low = 1;
                            residue -= 8 << 3;
                            mask = 1 << 10;
                            tab = Vp8Tables.CAT6_PROBS;
                        }
                        p = base + 8;
                        buf[at++] = (short) ((high << 15) | p);
                        counts[2 * p + high]++;
                        p = base + 9 + high;
                        buf[at++] = (short) ((low << 15) | p);
                        counts[2 * p + low]++;
                        var t = 0;
                        while (mask != 0) {
                            buf[at++] = (short) (((residue & mask) != 0 ? 1 << 15 : 0) | EncProba.FIXED_PROBA_BIT
                                    | tab[t++]);
                            mask >>= 1;
                        }
                    }
                }
                base = EncProba.index(type, Vp8Tables.BANDS[n], 2);
            }
            buf[at++] = (short) ((sign << 15) | EncProba.FIXED_PROBA_BIT | 128);
            if (n == 16) {
                break;
            }
            var more = n <= last ? 1 : 0;
            buf[at++] = (short) ((more << 15) | base);
            counts[2 * base + more]++;
            if (more == 0) {
                break;
            }
        }
        tokens.size(at);
        return 1;
    }

    /**
     * The most tokens one block records: the first end-of-block branch, then for each of the 16
     * coefficients at most four tree branches, two category branches, eleven extra bits, a sign
     * and an end-of-block branch.
     */
    private static final int MAX_BLOCK_TOKENS = 1 + 16 * 19;

    /**
     * Chooses the mode, reference and motion vector of an inter-frame macroblock, libvpx's
     * {@code vp8_pick_inter_mode}.
     *
     * @param row   the state of the macroblock's row
     * @param stats the statistics of the coding thread
     * @return the rate of the chosen mode, in 1/256 bit
     */
    private int pickInterMode(RtRow row, RtStats stats) {
        dotSuppress = false;
        var dotArtifact = false;
        if (enc.refs[LAST_FRAME] != null) {
            dotArtifact = checkDotArtifact(row);
        }
        var isSkin = enc.skinMap[mapIdx] != 0;
        var bestRefMv = 0;
        java.util.Arrays.fill(modeMv, 0);
        var refs = refMap;
        var n = 0;
        refs[n++] = INTRA_FRAME;
        for (var ref = LAST_FRAME; ref <= ALTREF_FRAME; ref++) {
            if (enc.refs[ref] != null) {
                refs[n++] = ref;
            }
        }
        for (; n < 4; n++) {
            refs[n] = -1;
        }
        var biasMask = enc.signBiasMask();
        var bias = 0;
        if (refs[1] > 0) {
            NearMvs.find(enc.mbRef, enc.mbMv, enc.mbMode, idx, enc.miStride, biasMask, 0, mdCounts, nearResult);
            nearestSb[0] = clampMv2(nearResult[1]);
            nearSb[0] = clampMv2(nearResult[2]);
            bestSb[0] = clampMv2(nearResult[0]);
            if (biasMask != 0) {
                NearMvs.find(enc.mbRef, enc.mbMv, enc.mbMode, idx, enc.miStride, biasMask, 1, mdCounts, nearResult);
                nearestSb[1] = clampMv2(nearResult[1]);
                nearSb[1] = clampMv2(nearResult[2]);
                bestSb[1] = clampMv2(nearResult[0]);
            } else {
                nearestSb[1] = nearestSb[0];
                nearSb[1] = nearSb[0];
                bestSb[1] = bestSb[0];
            }
            modeMv[NEARESTMV] = nearestSb[0];
            modeMv[NEARMV] = nearSb[0];
            bestRefMv = bestSb[0];
        }
        var zeroAltRefOnly = enc.srcFrameAltRef && enc.forceZeroAltRef;
        row.mbsTested++;
        srcStatsValid = false;
        var rdAdjustment = 100;
        if (enc.speed < 12) {
            rdAdjustment = zeroMvRdAdjustment();
        }
        if (dotArtifact) {
            rdAdjustment = 150;
        }
        var bestRd = Integer.MAX_VALUE;
        var bestIntraRd = Integer.MAX_VALUE;
        var bestModeIndex = 0;
        var bestMode = ZEROMV;
        var bestRef = INTRA_FRAME;
        var bestMv = 0;
        var returnRate = 0;
        var returnDistortion = 0;
        var bestRdSse = Integer.MAX_VALUE;
        var sadDone = false;
        breakout = false;
        var thresholds = row.rdThreshes;
        var mults = row.rdThreshMult;
        var hits = row.modeTestHitCounts;
        var baseline = enc.rdBaselineThresh;
        var checkFreq = enc.modeCheckFreq;
        var cur = enc.cur;
        for (var modeIndex = 0; modeIndex < RtTables.MAX_MODES; modeIndex++) {
            var thisRef = refs[RtTables.REF_FRAME_ORDER[modeIndex]];
            if (bestRd <= thresholds[modeIndex] || thisRef < 0) {
                continue;
            }
            var thisMode = RtTables.MODE_ORDER[modeIndex];
            if (thisMode == SPLITMV) {
                continue;
            }
            if (zeroAltRefOnly && (thisMode != ZEROMV || thisRef != ALTREF_FRAME)) {
                continue;
            }
            if (thisRef != INTRA_FRAME) {
                var refBias = (biasMask >> thisRef) & 1;
                if (refBias != bias) {
                    bias = refBias;
                    modeMv[NEARESTMV] = nearestSb[bias];
                    modeMv[NEARMV] = nearSb[bias];
                    bestRefMv = bestSb[bias];
                }
            }
            if (hits[modeIndex] != 0 && checkFreq[modeIndex] > 1
                    && row.mbsTested <= checkFreq[modeIndex] * hits[modeIndex]) {
                mults[modeIndex] = Math.min(mults[modeIndex] + 4, MAX_THRESHMULT);
                thresholds[modeIndex] = threshold(baseline[modeIndex], mults[modeIndex]);
                continue;
            }
            hits[modeIndex]++;
            var rate2 = enc.refFrameCost[thisRef];
            int distortion2;
            int sse;
            int thisRd;
            var thisMv = 0;
            if (thisMode == B_PRED) {
                var intra = pickIntra4x4(enc.mbModeCost[B_PRED], bestRdSse);
                if (intra == Integer.MAX_VALUE) {
                    thisRd = Integer.MAX_VALUE;
                    distortion2 = 0;
                    sse = bestRdSse;
                } else {
                    rate2 += subblockRate;
                    var packed = rtDsp.variance16(srcY, srcYOff, srcYStride, pred, 0, BPS);
                    distortion2 = RtDsp.variance256(packed);
                    sse = RtDsp.sse(packed);
                    thisRd = rdCost(rate2, distortion2);
                    if (thisRd < bestIntraRd) {
                        bestIntraRd = thisRd;
                    }
                }
            } else if (thisMode <= TM_PRED) {
                var packed = intraError(thisMode);
                distortion2 = RtDsp.variance256(packed);
                sse = RtDsp.sse(packed);
                rate2 += enc.mbModeCost[thisMode];
                thisRd = rdCost(rate2, distortion2);
                if (thisRd < bestIntraRd) {
                    bestIntraRd = thisRd;
                }
            } else {
                preFrame = enc.refs[thisRef];
                preY = preFrame.y;
                var subpelDone = false;
                distortion2 = 0;
                sse = 0;
                if (thisMode == NEWMV) {
                    if (enc.improvedMvPred && !sadDone) {
                        calSad();
                        sadDone = true;
                    }
                    newMvSearch(thisRef, bestRefMv);
                    modeMv[NEWMV] = clampMv2(searchMv);
                    rate2 += mvBitCost(modeMv[NEWMV], bestRefMv);
                    subpelDone = enc.subpelMethod != RtEncoder.SUBPEL_NONE;
                    distortion2 = searchDistortion;
                    sse = searchSse;
                }
                if (thisMode != ZEROMV && modeMv[thisMode] == 0) {
                    continue;
                }
                thisMv = modeMv[thisMode];
                if ((Mv.row(thisMv) >> 3) < mvRowMin || (Mv.row(thisMv) >> 3) > mvRowMax
                        || (Mv.col(thisMv) >> 3) < mvColMin || (Mv.col(thisMv) >> 3) > mvColMax) {
                    continue;
                }
                rate2 += costMvRef(thisMode);
                if (!subpelDone) {
                    var packed = interPredError(thisMv);
                    distortion2 = RtDsp.variance256(packed);
                    sse = RtDsp.sse(packed);
                }
                thisRd = rdCost(rate2, distortion2);
                if (thisMode == ZEROMV && thisRef == LAST_FRAME && enc.closestReferenceFrame == LAST_FRAME) {
                    var adjustment = isSkin ? 100 : rdAdjustment;
                    thisRd = (int) ((long) thisRd * adjustment / 100);
                }
                if (enc.encodeBreakout > 0) {
                    breakout = checkEncodeBreakout(sse, thisMv);
                }
            }
            if (thisRd < bestRd || breakout) {
                bestModeIndex = modeIndex;
                returnRate = rate2;
                returnDistortion = distortion2;
                bestRdSse = sse;
                bestRd = thisRd;
                bestMode = thisMode;
                bestRef = thisRef;
                bestMv = thisMv;
                mults[modeIndex] = mults[modeIndex] >= MIN_THRESHMULT + 2 ? mults[modeIndex] - 2 : MIN_THRESHMULT;
            } else {
                mults[modeIndex] = Math.min(mults[modeIndex] + 4, MAX_THRESHMULT);
            }
            thresholds[modeIndex] = threshold(baseline[modeIndex], mults[modeIndex]);
            if (breakout) {
                break;
            }
        }
        if (baseline[bestModeIndex] > 0 && baseline[bestModeIndex] < (Integer.MAX_VALUE >> 2)) {
            var adjustment = mults[bestModeIndex] >> 3;
            mults[bestModeIndex] = mults[bestModeIndex] >= MIN_THRESHMULT + adjustment
                    ? mults[bestModeIndex] - adjustment : MIN_THRESHMULT;
            thresholds[bestModeIndex] = threshold(baseline[bestModeIndex], mults[bestModeIndex]);
        }
        stats.errorBins[Math.min(returnDistortion >> 7, RtStats.ERROR_BINS - 1)]++;
        if (enc.srcFrameAltRef && (bestMode != ZEROMV || bestRef != ALTREF_FRAME)) {
            enc.mbMode[idx] = (byte) ZEROMV;
            enc.mbRef[idx] = (byte) ALTREF_FRAME;
            enc.mbMv[idx] = 0;
            enc.mbUvMode[idx] = (byte) DC_PRED;
            return returnRate;
        }
        enc.mbMode[idx] = (byte) bestMode;
        enc.mbRef[idx] = (byte) bestRef;
        enc.mbMv[idx] = bestRef == INTRA_FRAME ? 0 : bestMv;
        if (bestRef == INTRA_FRAME) {
            pickIntraUvMode(RtDsp.SCALAR);
        } else if (bestMode == NEWMV) {
            var best = bestSb[(biasMask >> bestRef) & 1];
            var rowIdx = RtStats.MV_MAX + ((Mv.row(bestMv) - Mv.row(best)) >> 1);
            var colIdx = RtStats.MV_MAX + ((Mv.col(bestMv) - Mv.col(best)) >> 1);
            if (rowIdx >= 0 && rowIdx < RtStats.MV_VALS && colIdx >= 0 && colIdx < RtStats.MV_VALS) {
                stats.mvCounts[rowIdx]++;
                stats.mvCounts[RtStats.MV_VALS + colIdx]++;
            }
        }
        return returnRate;
    }

    /**
     * Whether the encode breakout ended the mode search of the current macroblock, which is then
     * coded without residual.
     */
    private boolean breakout;

    /**
     * Returns whether an inter mode's prediction is good enough to end the mode search and skip
     * the residual, libvpx's {@code check_for_encode_breakout}: its luma sum of squared
     * differences is below both the breakout threshold and a sixteenth of the squared luma AC
     * step, and twice its chroma sum of squared differences is below the breakout threshold.
     *
     * @param sse the luma sum of squared differences of the mode
     * @param mv  the packed vector of the mode, in 1/8 pixel
     * @return {@code true} when the macroblock should skip its residual
     */
    private boolean checkEncodeBreakout(int sse, int mv) {
        var step = y1Matrix.q[1];
        var threshold = Math.max((step * step) >> 4, enc.encodeBreakout);
        if (Integer.compareUnsigned(sse, threshold) >= 0) {
            return false;
        }
        return 2L * chromaSse(mv) < enc.encodeBreakout;
    }

    /**
     * Returns the chroma sum of squared differences between the source and the bilinear
     * prediction from {@link #preY}'s frame with a luma vector, libvpx's {@code VP8_UVSSE}:
     * the vector is halved away from zero and not clamped.
     *
     * @param mv the packed luma vector, in 1/8 pixel
     * @return the sum over both chroma planes
     */
    long chromaSse(int mv) {
        var ref = preFrame;
        var row = Mv.row(mv);
        var col = Mv.col(mv);
        row = (row < 0 ? row - 1 : row + 1) / 2;
        col = (col < 0 ? col - 1 : col + 1) / 2;
        var stride = ref.uvStride;
        var off = uvOff + (row >> 3) * stride + (col >> 3);
        return bilinearSse8(ref.u, off, stride, col & 7, row & 7, U_OFF)
                + bilinearSse8(ref.v, off, stride, col & 7, row & 7, V_OFF);
    }

    /**
     * Returns the sum of squared differences between an 8x8 block of the source chroma and a
     * reference block filtered with the bilinear filters, libvpx's
     * {@code vpx_sub_pixel_variance8x8} sum of squares ({@code vpx_variance8x8} without offsets).
     *
     * @param ref     the reference plane
     * @param off     the index of the reference block's top-left sample
     * @param stride  the reference row stride
     * @param xOffset the horizontal offset in 1/8 pixel
     * @param yOffset the vertical offset in 1/8 pixel
     * @param srcCol  the column of the plane in {@link #src}: {@code U_OFF} or {@code V_OFF}
     * @return the sum of squared differences
     */
    private long bilinearSse8(byte[] ref, int off, int stride, int xOffset, int yOffset, int srcCol) {
        var h0 = RtDsp.BILINEAR_TAPS[xOffset * 2];
        var h1 = RtDsp.BILINEAR_TAPS[xOffset * 2 + 1];
        var v0 = RtDsp.BILINEAR_TAPS[yOffset * 2];
        var v1 = RtDsp.BILINEAR_TAPS[yOffset * 2 + 1];
        var first = chromaScratch;
        for (var r = 0; r < 9; r++) {
            var at = off + r * stride;
            for (var c = 0; c < 8; c++) {
                first[r * 8 + c] = ((ref[at + c] & 0xFF) * h0 + (ref[at + c + 1] & 0xFF) * h1 + 64) >> 7;
            }
        }
        var sse = 0L;
        for (var r = 0; r < 8; r++) {
            for (var c = 0; c < 8; c++) {
                var p = (first[r * 8 + c] * v0 + first[(r + 1) * 8 + c] * v1 + 64) >> 7;
                var d = p - (src[r * BPS + srcCol + c] & 0xFF);
                sse += d * d;
            }
        }
        return sse;
    }

    /**
     * The intermediate rows of {@link #bilinearSse8}.
     */
    private final int[] chromaScratch = new int[9 * 8];

    /**
     * Returns a mode's threshold from its baseline and multiplier, libvpx's
     * {@code (rd_baseline_thresh >> 7) * rd_thresh_mult}.
     *
     * @param baseline the baseline threshold
     * @param mult     the multiplier in 1/128
     * @return the threshold, at most {@link Integer#MAX_VALUE}
     */
    static int threshold(int baseline, int mult) {
        return (int) Math.min((long) (baseline >> 7) * mult, Integer.MAX_VALUE);
    }

    /**
     * Returns whether the current macroblock, coded with zero motion from the last frame for
     * many frames, shows the corner gradients of a dot artifact, libvpx's
     * {@code check_dot_artifact_candidate} for the three planes.
     *
     * @param row the state of the macroblock's row
     * @return {@code true} when zero motion should be discouraged
     */
    private boolean checkDotArtifact(RtRow row) {
        var numFrames = enc.layers > 1 ? 20 : 30;
        var maxNum = Math.max(1, enc.mbCols / 10);
        if (enc.currentLayer != 0 || (enc.consecZeroLastMvBias[mapIdx] & 0xFF) <= numFrames || row.dotSuppressed >= maxNum) {
            return false;
        }
        dotSuppress = true;
        var last = enc.refs[LAST_FRAME];
        var source = enc.source;
        if (dotCandidate(source.y, source.yOrigin + mbRow * 16 * source.yStride + mbCol * 16, source.yStride,
                last.y, yOff, last.yStride, 15)) {
            row.dotSuppressed++;
            return true;
        }
        var suv = source.uvOrigin + mbRow * 8 * source.uvStride + mbCol * 8;
        if (dotCandidate(source.u, suv, source.uvStride, last.u, uvOff, last.uvStride, 7)
                || dotCandidate(source.v, suv, source.uvStride, last.v, uvOff, last.uvStride, 7)) {
            row.dotSuppressed++;
            return true;
        }
        return false;
    }

    /**
     * Returns whether one plane of the current macroblock has a strong corner gradient in the
     * last frame and a weak one in the source.
     *
     * @param target       the source plane
     * @param targetOff    the index of the block in the source plane
     * @param targetStride the source row stride
     * @param last         the last frame's plane
     * @param lastOff      the index of the block in the last frame's plane
     * @param lastStride   the last frame's row stride
     * @param shift        the offset of the last row and column: 15 for luma, 7 for chroma
     * @return {@code true} when any corner qualifies
     */
    private static boolean dotCandidate(byte[] target, int targetOff, int targetStride, byte[] last, int lastOff,
                                        int lastStride, int shift) {
        return dotCorner(target, targetOff, targetStride, last, lastOff, lastStride, 0, 0, 1, 1)
                || dotCorner(target, targetOff, targetStride, last, lastOff, lastStride, 0, shift, 1, -1)
                || dotCorner(target, targetOff, targetStride, last, lastOff, lastStride, shift, 0, -1, 1)
                || dotCorner(target, targetOff, targetStride, last, lastOff, lastStride, shift, shift, -1, -1);
    }

    /**
     * Returns whether one corner has a last-frame gradient of at least 6 and a source gradient
     * of at most 3, libvpx's corner test of {@code check_dot_artifact_candidate}.
     *
     * @param target       the source plane
     * @param targetOff    the index of the block in the source plane
     * @param targetStride the source row stride
     * @param last         the last frame's plane
     * @param lastOff      the index of the block in the last frame's plane
     * @param lastStride   the last frame's row stride
     * @param r            the corner's row in the block
     * @param c            the corner's column in the block
     * @param dr           the direction into the block along rows
     * @param dc           the direction into the block along columns
     * @return {@code true} when the corner qualifies
     */
    private static boolean dotCorner(byte[] target, int targetOff, int targetStride, byte[] last, int lastOff,
                                     int lastStride, int r, int c, int dr, int dc) {
        return cornerGradient(last, lastOff, lastStride, r, c, dr, dc) >= 6
                && cornerGradient(target, targetOff, targetStride, r, c, dr, dc) <= 3;
    }

    /**
     * Returns the largest difference between a corner sample and its three neighbors towards
     * the inside of the block, libvpx's {@code macroblock_corner_grad}.
     *
     * @param p      the plane
     * @param off    the index of the block
     * @param stride the row stride
     * @param r      the corner's row
     * @param c      the corner's column
     * @param dr     the direction into the block along rows
     * @param dc     the direction into the block along columns
     * @return the gradient
     */
    private static int cornerGradient(byte[] p, int off, int stride, int r, int c, int dr, int dc) {
        var y1 = p[off + r * stride + c] & 0xFF;
        var y2 = p[off + r * stride + c + dc] & 0xFF;
        var y3 = p[off + (r + dr) * stride + c] & 0xFF;
        var y4 = p[off + (r + dr) * stride + c + dc] & 0xFF;
        return Math.max(Math.max(Math.abs(y1 - y2), Math.abs(y1 - y3)), Math.abs(y1 - y4));
    }

    /**
     * Returns the percentage that scales the score of last-frame zero motion when the neighbors
     * move little and the last frame was mostly static, libvpx's
     * {@code calculate_zeromv_rd_adjustment}.
     *
     * @return {@code 80}, {@code 90} or {@code 100}
     */
    private int zeroMvRdAdjustment() {
        if (enc.lfZeromvPct <= 40) {
            return 100;
        }
        var above = idx - enc.miStride;
        var local = smallMotion(idx - 1) + smallMotion(above - 1) + smallMotion(above);
        if (((mbRow == 0 || mbCol == 0) && local > 0) || local > 2) {
            return 80;
        }
        return local > 0 ? 90 : 100;
    }

    /**
     * Returns whether a neighboring macroblock is inter coded with a vector shorter than one
     * pixel in both components.
     *
     * @param neighbor the neighbor's mode info index
     * @return {@code 1} when it is, {@code 0} otherwise
     */
    private int smallMotion(int neighbor) {
        if (enc.mbRef[neighbor] == INTRA_FRAME) {
            return 0;
        }
        var mv = enc.mbMv[neighbor];
        return Math.abs(Mv.row(mv)) < 8 && Math.abs(Mv.col(mv)) < 8 ? 1 : 0;
    }

    /**
     * Clamps a motion vector to the frame plus a 16-pixel margin, libvpx's
     * {@code vp8_clamp_mv2}.
     *
     * @param mv the packed vector
     * @return the clamped packed vector
     */
    int clampMv2(int mv) {
        return Mv.clamp(mv, mbToLeftEdge, mbToRightEdge, mbToTopEdge, mbToBottomEdge);
    }

    /**
     * Returns the cost of signaling an inter mode with the current macroblock's neighbor
     * counts, libvpx's {@code vp8_cost_mv_ref}.
     *
     * @param mode the mode: {@code NEARESTMV}, {@code NEARMV}, {@code ZEROMV} or {@code NEWMV}
     * @return the cost in 1/256 bit
     */
    int costMvRef(int mode) {
        var mc = Vp8Tables.MODE_CONTEXTS;
        var cnt = mdCounts;
        var p0 = mc[cnt[0] * 4];
        if (mode == ZEROMV) {
            return RtTables.bitCost(p0, 0);
        }
        var cost = RtTables.bitCost(p0, 1);
        var p1 = mc[cnt[1] * 4 + 1];
        if (mode == NEARESTMV) {
            return cost + RtTables.bitCost(p1, 0);
        }
        cost += RtTables.bitCost(p1, 1);
        var p2 = mc[cnt[2] * 4 + 2];
        if (mode == NEARMV) {
            return cost + RtTables.bitCost(p2, 0);
        }
        return cost + RtTables.bitCost(p2, 1) + RtTables.bitCost(mc[cnt[3] * 4 + 3], 0);
    }

    /**
     * Returns the cost of coding a motion vector against its reference, libvpx's
     * {@code vp8_mv_bit_cost} with a weight of 128.
     *
     * @param mv  the packed vector
     * @param ref the packed reference vector
     * @return the cost in 1/256 bit
     */
    int mvBitCost(int mv, int ref) {
        var cost = enc.mvCost;
        var r = Math.clamp((Mv.row(mv) - Mv.row(ref)) >> 1, -RtStats.MV_MAX, RtStats.MV_MAX);
        var c = Math.clamp((Mv.col(mv) - Mv.col(ref)) >> 1, -RtStats.MV_MAX, RtStats.MV_MAX);
        return cost[RtStats.MV_MAX + r] + cost[RtStats.MV_VALS + RtStats.MV_MAX + c];
    }

    /**
     * Returns the motion vector cost of a vector in distortion units, libvpx's
     * {@code mv_err_cost}.
     *
     * @param mv  the packed vector
     * @param ref the packed reference vector
     * @return the cost
     */
    int mvErrCost(int mv, int ref) {
        return (int) (((long) mvBitCost(mv, ref) * enc.errorPerBit + 128) >> 8);
    }

    /**
     * Returns the full-pixel motion vector cost of a search point, libvpx's
     * {@code mvsad_err_cost}.
     *
     * @param row     the point's row in full pixels
     * @param col     the point's column in full pixels
     * @param centerRow the search center's row in full pixels
     * @param centerCol the search center's column in full pixels
     * @param sadPerBit the cost of a bit in SAD units
     * @return the cost
     */
    int mvSadErrCost(int row, int col, int centerRow, int centerCol, int sadPerBit) {
        var cost = enc.mvSadCost;
        var r = Math.clamp(row - centerRow, -RtEncoder.MVFP_MAX, RtEncoder.MVFP_MAX);
        var c = Math.clamp(col - centerCol, -RtEncoder.MVFP_MAX, RtEncoder.MVFP_MAX);
        return (int) (((long) (cost[RtEncoder.MVFP_MAX + r] + cost[RtEncoder.MVFP_MAX + c]) * sadPerBit + 128) >> 8);
    }

    /**
     * Returns the packed sums of the difference between the source macroblock and its
     * prediction from {@link #preY} with a motion vector, using the bilinear filters for
     * fractional vectors, libvpx's {@code vp8_get_inter_mbpred_error}.
     *
     * @param mv the packed vector in 1/8 pixel
     * @return the packed sums
     */
    private long interPredError(int mv) {
        var stride = enc.cur.yStride;
        var row = Mv.row(mv);
        var col = Mv.col(mv);
        var off = yOff + (row >> 3) * stride + (col >> 3);
        var xOffset = col & 7;
        var yOffset = row & 7;
        if ((xOffset | yOffset) != 0) {
            return rtDsp.subpelVariance16(preY, off, stride, xOffset, yOffset, srcY, srcYOff, srcYStride, subpelScratch);
        }
        return rtDsp.variance16(srcY, srcYOff, srcYStride, preY, off, stride);
    }

    /**
     * Searches a new motion vector for the current macroblock against {@link #preY}: a full-pixel
     * hexagon or diamond search from the predicted vector, then the fractional refinement of the
     * speed, the {@code NEWMV} case of libvpx's {@code vp8_pick_inter_mode}.
     *
     * <p>The result is in {@link #searchMv} (1/8 pixel), {@link #searchDistortion} and
     * {@link #searchSse}.
     *
     * @param ref       the reference searched
     * @param bestRefMv the packed reference vector new vectors are coded against
     */
    private void newMvSearch(int ref, int bestRefMv) {
        var sadPerBit = enc.sadPerBit16;
        var colMin = ((Mv.col(bestRefMv) + 7) >> 3) - MAX_FULL_PEL_VAL;
        var rowMin = ((Mv.row(bestRefMv) + 7) >> 3) - MAX_FULL_PEL_VAL;
        var colMax = (Mv.col(bestRefMv) >> 3) + MAX_FULL_PEL_VAL;
        var rowMax = (Mv.row(bestRefMv) >> 3) + MAX_FULL_PEL_VAL;
        var savedColMin = mvColMin;
        var savedColMax = mvColMax;
        var savedRowMin = mvRowMin;
        var savedRowMax = mvRowMax;
        var speed = enc.speed;
        var speedAdjust = speed > 5 ? (speed >= 8 ? 3 : 2) : 1;
        var stepParam = 1 + speedAdjust;
        int mvpRow;
        int mvpCol;
        if (enc.improvedMvPred) {
            var mvp = mvPred(ref);
            var sr = predictedRange + speedAdjust;
            if (sr > stepParam) {
                stepParam = sr;
            }
            mvpRow = Mv.row(mvp) >> 3;
            mvpCol = Mv.col(mvp) >> 3;
        } else {
            mvpRow = Mv.row(bestRefMv) >> 3;
            mvpCol = Mv.col(bestRefMv) >> 3;
        }
        mvColMin = Math.max(mvColMin, colMin);
        mvColMax = Math.min(mvColMax, colMax);
        mvRowMin = Math.max(mvRowMin, rowMin);
        mvRowMax = Math.min(mvRowMax, rowMax);
        var furtherSteps = speed >= 8 ? 0 : 8 - 1 - stepParam;
        if (enc.searchMethod == RtEncoder.SEARCH_HEX) {
            hexSearch(mvpRow, mvpCol, sadPerBit, bestRefMv);
        } else {
            var bestSme = diamondSearch(mvpRow, mvpCol, stepParam, sadPerBit, bestRefMv);
            var best = searchMv;
            var num00 = diamondNum00;
            var n = num00;
            num00 = 0;
            while (n < furtherSteps) {
                n++;
                if (num00 != 0) {
                    num00--;
                } else {
                    var sme = diamondSearch(mvpRow, mvpCol, stepParam + n, sadPerBit, bestRefMv);
                    num00 = diamondNum00;
                    if (sme < bestSme) {
                        bestSme = sme;
                        best = searchMv;
                    }
                }
            }
            searchMv = best;
        }
        mvColMin = savedColMin;
        mvColMax = savedColMax;
        mvRowMin = savedRowMin;
        mvRowMax = savedRowMax;
        switch (enc.subpelMethod) {
            case RtEncoder.SUBPEL_ITERATIVE -> subpelIterative(bestRefMv);
            case RtEncoder.SUBPEL_QUARTER -> subpelQuarter(bestRefMv);
            case RtEncoder.SUBPEL_HALF -> subpelHalf(bestRefMv);
            default -> searchMv = Mv.pack(Mv.row(searchMv) * 8, Mv.col(searchMv) * 8);
        }
    }

    /**
     * The search range suggested by the last {@link #mvPred}.
     */
    int predictedRange;

    /**
     * Computes the sums of absolute differences between the source macroblock and its
     * reconstructed neighbors in the frame being coded and in the last frame, and sorts the
     * candidates by them, libvpx's {@code vp8_cal_sad}.
     */
    void calSad() {
        var sad = nearSad;
        var order = nearSadIdx;
        for (var i = 0; i < 8; i++) {
            sad[i] = 0;
            order[i] = i;
        }
        var cur = enc.cur;
        var stride = cur.yStride;
        if (mbRow == 0 && mbCol == 0) {
            sad[0] = sad[1] = sad[2] = Integer.MAX_VALUE;
        } else if (mbRow == 0) {
            sad[0] = sad[2] = Integer.MAX_VALUE;
            sad[1] = rtDsp.sad16(srcY, srcYOff, srcYStride, cur.y, yOff - 16, stride);
        } else if (mbCol == 0) {
            sad[1] = sad[2] = Integer.MAX_VALUE;
            sad[0] = rtDsp.sad16(srcY, srcYOff, srcYStride, cur.y, yOff - stride * 16, stride);
        } else {
            rtDsp.sad16x3(srcY, srcYOff, srcYStride, cur.y, yOff - stride * 16, yOff - 16, yOff - stride * 16 - 16, stride, sad, 0);
        }
        var lastKey = enc.lastFrameKey;
        if (!lastKey) {
            var last = enc.buffers[enc.lastIdx];
            var pre = last.y;
            if (mbRow == 0) {
                sad[4] = Integer.MAX_VALUE;
            }
            if (mbCol == 0) {
                sad[5] = Integer.MAX_VALUE;
            }
            if (mbCol == enc.mbCols - 1) {
                sad[6] = Integer.MAX_VALUE;
            }
            if (mbRow == enc.mbRows - 1) {
                sad[7] = Integer.MAX_VALUE;
            }
            if (mbRow > 0 && mbCol > 0 && mbCol < enc.mbCols - 1 && mbRow < enc.mbRows - 1) {
                rtDsp.sad16x4(srcY, srcYOff, srcYStride, pre, yOff - stride * 16, yOff - 16, yOff + 16, yOff + stride * 16, stride, sad,
                        4);
                sad[3] = rtDsp.sad16(srcY, srcYOff, srcYStride, pre, yOff, stride);
                insertionSort(sad, order, 8);
                return;
            }
            if (sad[4] != Integer.MAX_VALUE) {
                sad[4] = rtDsp.sad16(srcY, srcYOff, srcYStride, pre, yOff - stride * 16, stride);
            }
            if (sad[5] != Integer.MAX_VALUE) {
                sad[5] = rtDsp.sad16(srcY, srcYOff, srcYStride, pre, yOff - 16, stride);
            }
            sad[3] = rtDsp.sad16(srcY, srcYOff, srcYStride, pre, yOff, stride);
            if (sad[6] != Integer.MAX_VALUE) {
                sad[6] = rtDsp.sad16(srcY, srcYOff, srcYStride, pre, yOff + 16, stride);
            }
            if (sad[7] != Integer.MAX_VALUE) {
                sad[7] = rtDsp.sad16(srcY, srcYOff, srcYStride, pre, yOff + stride * 16, stride);
            }
        }
        insertionSort(sad, order, lastKey ? 3 : 8);
    }

    /**
     * Sorts values in ascending order together with their indices, keeping equal values in
     * their order, the result of libvpx's {@code insertsortsad}.
     *
     * @implNote This implementation is a plain stable insertion sort: libvpx's version inserts
     * each value before the first larger one and then compares it against the rest of the sorted
     * prefix to no effect, so both produce the one stable order.
     * @param values the keys, sorted in place
     * @param indices the indices, moved with the keys
     * @param length the number of entries to sort
     */
    private static void insertionSort(int[] values, int[] indices, int length) {
        for (var i = 1; i < length; i++) {
            var value = values[i];
            var index = indices[i];
            var k = i;
            for (; k > 0 && values[k - 1] > value; k--) {
                values[k] = values[k - 1];
                indices[k] = indices[k - 1];
            }
            values[k] = value;
            indices[k] = index;
        }
    }

    /**
     * Sorts values in ascending order, the result of libvpx's {@code insertsortmv}.
     *
     * @param values the keys, sorted in place
     * @param length the number of entries to sort
     */
    private static void insertionSort(int[] values, int length) {
        for (var i = 1; i < length; i++) {
            var value = values[i];
            var k = i;
            for (; k > 0 && values[k - 1] > value; k--) {
                values[k] = values[k - 1];
            }
            values[k] = value;
        }
    }

    /**
     * Predicts the motion vector of the current macroblock for a reference from its neighbors in
     * this frame and in the last frame, in the order {@link #calSad} sorted them, libvpx's
     * {@code vp8_mv_pred}; sets {@link #predictedRange}.
     *
     * @param ref the reference
     * @return the packed predicted vector, clamped
     */
    int mvPred(int ref) {
        var mvs = nearMvs;
        var refs = nearRefs;
        for (var i = 0; i < 8; i++) {
            mvs[i] = 0;
            refs[i] = 0;
        }
        var mbRef = enc.mbRef;
        var mbMv = enc.mbMv;
        var mask = enc.signBiasMask();
        var bias = (mask >> ref) & 1;
        var above = idx - enc.miStride;
        var left = idx - 1;
        var aboveLeft = above - 1;
        var vcnt = 0;
        if (mbRef[above] != INTRA_FRAME) {
            mvs[vcnt] = NearMvs.biased(mbMv[above], mbRef[above], mask, bias);
            refs[vcnt] = mbRef[above];
        }
        vcnt++;
        if (mbRef[left] != INTRA_FRAME) {
            mvs[vcnt] = NearMvs.biased(mbMv[left], mbRef[left], mask, bias);
            refs[vcnt] = mbRef[left];
        }
        vcnt++;
        if (mbRef[aboveLeft] != INTRA_FRAME) {
            mvs[vcnt] = NearMvs.biased(mbMv[aboveLeft], mbRef[aboveLeft], mask, bias);
            refs[vcnt] = mbRef[aboveLeft];
        }
        vcnt++;
        if (!enc.lastFrameKey) {
            var lfStride = enc.mbCols + 2;
            var offset = (mbRow + 1) * lfStride + mbCol + 1;
            var lfRef = enc.lfRef;
            var lfMv = enc.lfMv;
            vcnt = lastFrameCandidate(lfRef, lfMv, offset, vcnt, bias);
            vcnt = lastFrameCandidate(lfRef, lfMv, offset - lfStride, vcnt, bias);
            vcnt = lastFrameCandidate(lfRef, lfMv, offset - 1, vcnt, bias);
            vcnt = lastFrameCandidate(lfRef, lfMv, offset + 1, vcnt, bias);
            vcnt = lastFrameCandidate(lfRef, lfMv, offset + lfStride, vcnt, bias);
        }
        var order = nearSadIdx;
        for (var i = 0; i < vcnt; i++) {
            var candidate = order[i];
            if (refs[candidate] != INTRA_FRAME && refs[candidate] == ref) {
                predictedRange = i < 3 ? 3 : 2;
                return clampMv2(mvs[candidate]);
            }
        }
        for (var i = 0; i < vcnt; i++) {
            mvx[i] = Mv.row(mvs[i]);
            mvy[i] = Mv.col(mvs[i]);
        }
        insertionSort(mvx, vcnt);
        insertionSort(mvy, vcnt);
        predictedRange = 0;
        return clampMv2(Mv.pack(mvx[vcnt / 2], mvy[vcnt / 2]));
    }

    /**
     * Adds a candidate of the last frame to {@link #mvPred}'s list.
     *
     * @param lfRef  the last frame's references
     * @param lfMv   the last frame's vectors
     * @param offset the candidate's index in the last frame arrays
     * @param vcnt   the number of candidates so far
     * @param bias   the sign bias of the reference predicted, which negates candidates of the other
     * @return the number of candidates after this one
     */
    private int lastFrameCandidate(byte[] lfRef, int[] lfMv, int offset, int vcnt, int bias) {
        if (offset >= 0 && offset < lfRef.length && lfRef[offset] != INTRA_FRAME) {
            var mv = lfMv[offset];
            nearMvs[vcnt] = enc.lfSignBias[offset] != bias ? Mv.pack(-Mv.row(mv), -Mv.col(mv)) : mv;
            nearRefs[vcnt] = lfRef[offset];
        }
        return vcnt + 1;
    }

    /**
     * Returns the sum of absolute differences between the source macroblock and the block of
     * {@link #preY} at a full-pixel vector.
     *
     * @param row the vector's row in full pixels
     * @param col the vector's column in full pixels
     * @return the sum
     */
    int sadAt(int row, int col) {
        var stride = enc.cur.yStride;
        return rtDsp.sad16(srcY, srcYOff, srcYStride, preY, yOff + row * stride + col, stride);
    }

    /**
     * Searches the full-pixel vector with the lowest SAD plus vector cost by hexagon steps and a
     * final small diamond, libvpx's {@code vp8_hex_search} without multi-resolution search
     * ranges; the result is in {@link #searchMv} in full pixels.
     *
     * @param startRow  the start row in full pixels, clamped to the search window
     * @param startCol  the start column in full pixels, clamped to the search window
     * @param sadPerBit the cost of a vector bit in SAD units
     * @param center    the packed reference vector the costs are measured from
     */
    private void hexSearch(int startRow, int startCol, int sadPerBit, int center) {
        var centerRow = Mv.row(center) >> 3;
        var centerCol = Mv.col(center) >> 3;
        var br = Math.clamp(startRow, mvRowMin, mvRowMax);
        var bc = Math.clamp(startCol, mvColMin, mvColMax);
        var bestSad = sadAt(br, bc) + mvSadErrCost(br, bc, centerRow, centerCol, sadPerBit);
        var bestSite = -1;
        var stride = enc.cur.yStride;
        var sads = searchSads;
        var allIn = br - 2 >= mvRowMin && br + 2 <= mvRowMax && bc - 2 >= mvColMin && bc + 2 <= mvColMax;
        if (allIn) {
            var at = yOff + br * stride + bc;
            rtDsp.sad16x3(srcY, srcYOff, srcYStride, preY, at + HEX[0] * stride + HEX[1], at + HEX[2] * stride + HEX[3],
                    at + HEX[4] * stride + HEX[5], stride, sads, 0);
            rtDsp.sad16x3(srcY, srcYOff, srcYStride, preY, at + HEX[6] * stride + HEX[7], at + HEX[8] * stride + HEX[9],
                    at + HEX[10] * stride + HEX[11], stride, sads, 3);
        }
        for (var i = 0; i < 6; i++) {
            var r = br + HEX[2 * i];
            var c = bc + HEX[2 * i + 1];
            if (!allIn && (c < mvColMin || c > mvColMax || r < mvRowMin || r > mvRowMax)) {
                continue;
            }
            var sad = allIn ? sads[i] : sadAt(r, c);
            if (sad < bestSad) {
                sad += mvSadErrCost(r, c, centerRow, centerCol, sadPerBit);
                if (sad < bestSad) {
                    bestSad = sad;
                    bestSite = i;
                }
            }
        }
        if (bestSite != -1) {
            br += HEX[2 * bestSite];
            bc += HEX[2 * bestSite + 1];
            var k = bestSite;
            for (var j = 1; j < 127; j++) {
                bestSite = -1;
                allIn = br - 2 >= mvRowMin && br + 2 <= mvRowMax && bc - 2 >= mvColMin && bc + 2 <= mvColMax;
                if (allIn) {
                    var at = yOff + br * stride + bc;
                    var points = k * 6;
                    rtDsp.sad16x3(srcY, srcYOff, srcYStride, preY,
                            at + NEXT_CHECK_POINTS[points] * stride + NEXT_CHECK_POINTS[points + 1],
                            at + NEXT_CHECK_POINTS[points + 2] * stride + NEXT_CHECK_POINTS[points + 3],
                            at + NEXT_CHECK_POINTS[points + 4] * stride + NEXT_CHECK_POINTS[points + 5], stride, sads, 0);
                }
                for (var i = 0; i < 3; i++) {
                    var r = br + NEXT_CHECK_POINTS[k * 6 + 2 * i];
                    var c = bc + NEXT_CHECK_POINTS[k * 6 + 2 * i + 1];
                    if (!allIn && (c < mvColMin || c > mvColMax || r < mvRowMin || r > mvRowMax)) {
                        continue;
                    }
                    var sad = allIn ? sads[i] : sadAt(r, c);
                    if (sad < bestSad) {
                        sad += mvSadErrCost(r, c, centerRow, centerCol, sadPerBit);
                        if (sad < bestSad) {
                            bestSad = sad;
                            bestSite = i;
                        }
                    }
                }
                if (bestSite == -1) {
                    break;
                }
                br += NEXT_CHECK_POINTS[k * 6 + 2 * bestSite];
                bc += NEXT_CHECK_POINTS[k * 6 + 2 * bestSite + 1];
                k += 5 + bestSite;
                if (k >= 12) {
                    k -= 12;
                } else if (k >= 6) {
                    k -= 6;
                }
            }
        }
        for (var j = 0; j < 8; j++) {
            bestSite = -1;
            allIn = br - 1 >= mvRowMin && br + 1 <= mvRowMax && bc - 1 >= mvColMin && bc + 1 <= mvColMax;
            if (allIn) {
                var at = yOff + br * stride + bc;
                rtDsp.sad16x4(srcY, srcYOff, srcYStride, preY, at - 1, at - stride, at + stride, at + 1, stride, sads, 0);
            }
            for (var i = 0; i < 4; i++) {
                var r = br + NEIGHBORS[2 * i];
                var c = bc + NEIGHBORS[2 * i + 1];
                if (!allIn && (c < mvColMin || c > mvColMax || r < mvRowMin || r > mvRowMax)) {
                    continue;
                }
                var sad = allIn ? sads[i] : sadAt(r, c);
                if (sad < bestSad) {
                    sad += mvSadErrCost(r, c, centerRow, centerCol, sadPerBit);
                    if (sad < bestSad) {
                        bestSad = sad;
                        bestSite = i;
                    }
                }
            }
            if (bestSite == -1) {
                break;
            }
            br += NEIGHBORS[2 * bestSite];
            bc += NEIGHBORS[2 * bestSite + 1];
        }
        searchMv = Mv.pack(br, bc);
    }

    /**
     * The sums of absolute differences of the points of one step of {@link #hexSearch}.
     */
    private final int[] searchSads = new int[8];

    /**
     * The number of steps of the last {@link #diamondSearch} that did not move from its start.
     */
    int diamondNum00;

    /**
     * Searches the full-pixel vector with the lowest SAD plus vector cost by shrinking
     * eight-point steps, libvpx's {@code vp8_diamond_search_sad_c} with the sites of
     * {@code vp8_init3smotion_compensation}; the result is in {@link #searchMv} in full pixels.
     *
     * @param startRow   the start row in full pixels, clamped to the search window
     * @param startCol   the start column in full pixels, clamped to the search window
     * @param stepParam  the index of the first step: the first step is {@code 128 >> stepParam}
     * @param sadPerBit  the cost of a vector bit in SAD units
     * @param center     the packed reference vector the costs are measured from
     * @return the variance at the result plus its vector cost
     */
    int diamondSearch(int startRow, int startCol, int stepParam, int sadPerBit, int center) {
        var centerRow = Mv.row(center) >> 3;
        var centerCol = Mv.col(center) >> 3;
        var refRow = Math.clamp(startRow, mvRowMin, mvRowMax);
        var refCol = Math.clamp(startCol, mvColMin, mvColMax);
        diamondNum00 = 0;
        var bestRow = refRow;
        var bestCol = refCol;
        var bestSad = sadAt(bestRow, bestCol) + mvSadErrCost(bestRow, bestCol, centerRow, centerCol, sadPerBit);
        var totSteps = 8 - stepParam;
        for (var step = 0; step < totSteps; step++) {
            var len = 128 >> (stepParam + step);
            var bestDr = 0;
            var bestDc = 0;
            for (var site = 0; site < 8; site++) {
                var dr = DIAMOND_ROWS[site] * len;
                var dc = DIAMOND_COLS[site] * len;
                var r = bestRow + dr;
                var c = bestCol + dc;
                if (c > mvColMin && c < mvColMax && r > mvRowMin && r < mvRowMax) {
                    var sad = sadAt(r, c);
                    if (sad < bestSad) {
                        sad += mvSadErrCost(r, c, centerRow, centerCol, sadPerBit);
                        if (sad < bestSad) {
                            bestSad = sad;
                            bestDr = dr;
                            bestDc = dc;
                        }
                    }
                }
            }
            if (bestDr != 0 || bestDc != 0) {
                bestRow += bestDr;
                bestCol += bestDc;
            } else if (bestRow == refRow && bestCol == refCol) {
                diamondNum00++;
            }
        }
        searchMv = Mv.pack(bestRow, bestCol);
        var stride = enc.cur.yStride;
        var variance = RtDsp.variance256(rtDsp.variance16(srcY, srcYOff, srcYStride, preY, yOff + bestRow * stride + bestCol, stride));
        return variance + mvErrCost(Mv.pack(bestRow * 8, bestCol * 8), center);
    }

    /**
     * The row direction of each of the eight diamond sites, libvpx's
     * {@code vp8_init3smotion_compensation} order.
     */
    private static final int[] DIAMOND_ROWS = {-1, 1, 0, 0, -1, -1, 1, 1};

    /**
     * The column direction of each of the eight diamond sites.
     */
    private static final int[] DIAMOND_COLS = {0, 0, -1, 1, -1, 1, -1, 1};

    /**
     * Returns the variance plus vector cost of a fractional vector of the iterative search, or
     * {@link Long#MAX_VALUE} outside its window, the {@code CHECK_BETTER} of libvpx's
     * {@code vp8_find_best_sub_pixel_step_iteratively} without the comparison.
     *
     * @param r      the row in quarter pixels
     * @param c      the column in quarter pixels
     * @param ref    the packed reference vector
     * @param bounds the window: minimum column, maximum column, minimum row, maximum row
     * @return the score, or {@link Long#MAX_VALUE}
     */
    private long iterativeScore(int r, int c, int ref, int[] bounds) {
        if (c < bounds[0] || c > bounds[1] || r < bounds[2] || r > bounds[3]) {
            return Long.MAX_VALUE;
        }
        var stride = enc.cur.yStride;
        var off = yOff + (r >> 2) * stride + (c >> 2);
        var packed = rtDsp.subpelVariance16(preY, off, stride, (c & 3) << 1, (r & 3) << 1, srcY, srcYOff, srcYStride, subpelScratch);
        lastVariance = RtDsp.variance256(packed);
        lastSse = RtDsp.sse(packed);
        var rr = Mv.row(ref) >> 1;
        var rc = Mv.col(ref) >> 1;
        var cost = enc.mvCost;
        var mvc = ((long) cost[RtStats.MV_MAX + Math.clamp(r - rr, -RtStats.MV_MAX, RtStats.MV_MAX)]
                + cost[RtStats.MV_VALS + RtStats.MV_MAX + Math.clamp(c - rc, -RtStats.MV_MAX, RtStats.MV_MAX)])
                * enc.errorPerBit + 128 >> 8;
        return mvc + lastVariance;
    }

    /**
     * The variance of the last scored fractional vector.
     */
    private int lastVariance;

    /**
     * The sum of squared differences of the last scored fractional vector.
     */
    private int lastSse;

    /**
     * The index in {@link #preY} of the block whose horizontal pass {@link #subpelScratch}
     * holds for {@link #subpelAt}, or {@code -1}.
     */
    private int filteredOff = -1;

    /**
     * The horizontal offset of the pass {@link #subpelScratch} holds for {@link #subpelAt}.
     */
    private int filteredX;

    /**
     * The packed sums of the two half-pixel neighbors of the fractional search.
     */
    private final long[] halfpelPair = new long[2];

    /**
     * The window of the iterative fractional search.
     */
    private final int[] iterativeBounds = new int[4];

    /**
     * Refines {@link #searchMv} from full to quarter pixels by up to three half-pixel and three
     * quarter-pixel rounds of four directions and one diagonal, libvpx's
     * {@code vp8_find_best_sub_pixel_step_iteratively}.
     *
     * @param ref the packed reference vector the costs are measured from
     */
    void subpelIterative(int ref) {
        var bounds = iterativeBounds;
        var maxDistance = (1 << MV_LONG_WIDTH) - 1;
        bounds[0] = Math.max(mvColMin * 4, (Mv.col(ref) >> 1) - maxDistance);
        bounds[1] = Math.min(mvColMax * 4, (Mv.col(ref) >> 1) + maxDistance);
        bounds[2] = Math.max(mvRowMin * 4, (Mv.row(ref) >> 1) - maxDistance);
        bounds[3] = Math.min(mvRowMax * 4, (Mv.row(ref) >> 1) + maxDistance);
        var br = Mv.row(searchMv) * 4;
        var bc = Mv.col(searchMv) * 4;
        var stride = enc.cur.yStride;
        var packed = rtDsp.variance16(preY, yOff + Mv.row(searchMv) * stride + Mv.col(searchMv), stride, srcY, srcYOff, srcYStride);
        searchDistortion = RtDsp.variance256(packed);
        searchSse = RtDsp.sse(packed);
        var bestErr = (long) searchDistortion + mvErrCost(Mv.pack(br * 2, bc * 2), ref);
        var tr = br;
        var tc = bc;
        for (var step = 2; step >= 1; step--) {
            for (var iter = 0; iter < 3; iter++) {
                var left = iterativeScore(tr, tc - step, ref, bounds);
                if (left < bestErr) {
                    bestErr = left;
                    br = tr;
                    bc = tc - step;
                    searchDistortion = lastVariance;
                    searchSse = lastSse;
                }
                var right = iterativeScore(tr, tc + step, ref, bounds);
                if (right < bestErr) {
                    bestErr = right;
                    br = tr;
                    bc = tc + step;
                    searchDistortion = lastVariance;
                    searchSse = lastSse;
                }
                var up = iterativeScore(tr - step, tc, ref, bounds);
                if (up < bestErr) {
                    bestErr = up;
                    br = tr - step;
                    bc = tc;
                    searchDistortion = lastVariance;
                    searchSse = lastSse;
                }
                var down = iterativeScore(tr + step, tc, ref, bounds);
                if (down < bestErr) {
                    bestErr = down;
                    br = tr + step;
                    bc = tc;
                    searchDistortion = lastVariance;
                    searchSse = lastSse;
                }
                var dr = up < down ? -step : step;
                var dc = left < right ? -step : step;
                var diag = iterativeScore(tr + dr, tc + dc, ref, bounds);
                if (diag < bestErr) {
                    bestErr = diag;
                    br = tr + dr;
                    bc = tc + dc;
                    searchDistortion = lastVariance;
                    searchSse = lastSse;
                }
                if (tr == br && tc == bc) {
                    break;
                }
                tr = br;
                tc = bc;
            }
        }
        searchMv = Mv.pack(br * 2, bc * 2);
    }

    /**
     * Returns the variance of the bilinear prediction at a reference sample with sub-pixel
     * offsets, recording it in {@link #lastVariance} and {@link #lastSse}.
     *
     * @param off     the index in {@link #preY} of the full-pixel sample the filter starts at
     * @param xOffset the horizontal offset in 1/8 pixel
     * @param yOffset the vertical offset in 1/8 pixel
     * @return the variance
     */
    private int subpelAt(int off, int xOffset, int yOffset) {
        long packed;
        if (xOffset != 0 && yOffset != 0) {
            if (off != filteredOff || xOffset != filteredX) {
                rtDsp.horizontalPass16(preY, off, enc.cur.yStride, xOffset, subpelScratch);
                filteredOff = off;
                filteredX = xOffset;
            }
            packed = rtDsp.verticalPassVariance16(subpelScratch, yOffset, srcY, srcYOff, srcYStride);
        } else {
            packed = rtDsp.subpelVariance16(preY, off, enc.cur.yStride, xOffset, yOffset, srcY, srcYOff, srcYStride,
                    subpelAxisScratch);
        }
        lastVariance = RtDsp.variance256(packed);
        lastSse = RtDsp.sse(packed);
        return lastVariance;
    }

    /**
     * Refines {@link #searchMv} from full to half pixels around the best full-pixel point, and,
     * when {@code quarter} is set, to quarter pixels around the best half-pixel point, the
     * shared steps of libvpx's {@code vp8_find_best_sub_pixel_step} and
     * {@code vp8_find_best_half_pixel_step}.
     *
     * @param ref     the packed reference vector the costs are measured from
     * @param quarter whether to refine to quarter pixels
     */
    private void subpelSteps(int ref, boolean quarter) {
        filteredOff = -1;
        var stride = enc.cur.yStride;
        var y = yOff + Mv.row(searchMv) * stride + Mv.col(searchMv);
        var startRow = Mv.row(searchMv) * 8;
        var startCol = Mv.col(searchMv) * 8;
        var packed = rtDsp.variance16(preY, y, stride, srcY, srcYOff, srcYStride);
        searchDistortion = RtDsp.variance256(packed);
        searchSse = RtDsp.sse(packed);
        var bestRow = startRow;
        var bestCol = startCol;
        var bestMse = (long) searchDistortion + mvErrCost(Mv.pack(startRow, startCol), ref);
        var pair = halfpelPair;
        rtDsp.halfpelHorizontalPair(preY, y, stride, srcY, srcYOff, srcYStride, pair);
        var thisCol = (startCol - 8) | 4;
        long left = RtDsp.variance256(pair[0]) + mvErrCost(Mv.pack(startRow, thisCol), ref);
        if (left < bestMse) {
            bestRow = startRow;
            bestCol = thisCol;
            bestMse = left;
            searchDistortion = RtDsp.variance256(pair[0]);
            searchSse = RtDsp.sse(pair[0]);
        }
        thisCol += 8;
        long right = RtDsp.variance256(pair[1]) + mvErrCost(Mv.pack(startRow, thisCol), ref);
        if (right < bestMse) {
            bestRow = startRow;
            bestCol = thisCol;
            bestMse = right;
            searchDistortion = RtDsp.variance256(pair[1]);
            searchSse = RtDsp.sse(pair[1]);
        }
        rtDsp.halfpelVerticalPair(preY, y, stride, srcY, srcYOff, srcYStride, pair);
        var thisRow = (startRow - 8) | 4;
        long up = RtDsp.variance256(pair[0]) + mvErrCost(Mv.pack(thisRow, startCol), ref);
        if (up < bestMse) {
            bestRow = thisRow;
            bestCol = startCol;
            bestMse = up;
            searchDistortion = RtDsp.variance256(pair[0]);
            searchSse = RtDsp.sse(pair[0]);
        }
        thisRow += 8;
        long down = RtDsp.variance256(pair[1]) + mvErrCost(Mv.pack(thisRow, startCol), ref);
        if (down < bestMse) {
            bestRow = thisRow;
            bestCol = startCol;
            bestMse = down;
            searchDistortion = RtDsp.variance256(pair[1]);
            searchSse = RtDsp.sse(pair[1]);
        }
        var whichDir = (left < right ? 0 : 1) + (up < down ? 0 : 2);
        int diagRow;
        int diagCol;
        int diagVariance;
        switch (whichDir) {
            case 0 -> {
                diagCol = (startCol - 8) | 4;
                diagRow = (startRow - 8) | 4;
                diagVariance = subpelAt(y - 1 - stride, 4, 4);
            }
            case 1 -> {
                diagCol = startCol + 4;
                diagRow = (startRow - 8) | 4;
                diagVariance = subpelAt(y - stride, 4, 4);
            }
            case 2 -> {
                diagCol = (startCol - 8) | 4;
                diagRow = startRow + 4;
                diagVariance = subpelAt(y - 1, 4, 4);
            }
            default -> {
                diagCol = startCol + 4;
                diagRow = startRow + 4;
                diagVariance = subpelAt(y, 4, 4);
            }
        }
        long diag = diagVariance + mvErrCost(Mv.pack(diagRow, diagCol), ref);
        if (diag < bestMse) {
            bestRow = diagRow;
            bestCol = diagCol;
            bestMse = diag;
            searchDistortion = lastVariance;
            searchSse = lastSse;
        }
        if (!quarter) {
            searchMv = Mv.pack(bestRow, bestCol);
            return;
        }
        if (bestRow < startRow) {
            y -= stride;
        }
        if (bestCol < startCol) {
            y--;
        }
        startRow = bestRow;
        startCol = bestCol;
        int thisMseRow;
        int thisMseCol;
        int variance;
        thisMseRow = startRow;
        if ((startCol & 7) != 0) {
            thisMseCol = startCol - 2;
            variance = subpelAt(y, thisMseCol & 7, thisMseRow & 7);
        } else {
            thisMseCol = (startCol - 8) | 6;
            variance = subpelAt(y - 1, 6, thisMseRow & 7);
        }
        left = variance + mvErrCost(Mv.pack(thisMseRow, thisMseCol), ref);
        if (left < bestMse) {
            bestRow = thisMseRow;
            bestCol = thisMseCol;
            bestMse = left;
            searchDistortion = lastVariance;
            searchSse = lastSse;
        }
        thisMseCol += 4;
        variance = subpelAt(y, thisMseCol & 7, thisMseRow & 7);
        right = variance + mvErrCost(Mv.pack(thisMseRow, thisMseCol), ref);
        if (right < bestMse) {
            bestRow = thisMseRow;
            bestCol = thisMseCol;
            bestMse = right;
            searchDistortion = lastVariance;
            searchSse = lastSse;
        }
        thisMseCol = startCol;
        if ((startRow & 7) != 0) {
            thisMseRow = startRow - 2;
            variance = subpelAt(y, thisMseCol & 7, thisMseRow & 7);
        } else {
            thisMseRow = (startRow - 8) | 6;
            variance = subpelAt(y - stride, thisMseCol & 7, 6);
        }
        up = variance + mvErrCost(Mv.pack(thisMseRow, thisMseCol), ref);
        if (up < bestMse) {
            bestRow = thisMseRow;
            bestCol = thisMseCol;
            bestMse = up;
            searchDistortion = lastVariance;
            searchSse = lastSse;
        }
        thisMseRow += 4;
        variance = subpelAt(y, thisMseCol & 7, thisMseRow & 7);
        down = variance + mvErrCost(Mv.pack(thisMseRow, thisMseCol), ref);
        if (down < bestMse) {
            bestRow = thisMseRow;
            bestCol = thisMseCol;
            bestMse = down;
            searchDistortion = lastVariance;
            searchSse = lastSse;
        }
        whichDir = (left < right ? 0 : 1) + (up < down ? 0 : 2);
        thisMseRow = startRow;
        thisMseCol = startCol;
        switch (whichDir) {
            case 0 -> {
                if ((startRow & 7) != 0) {
                    thisMseRow -= 2;
                    if ((startCol & 7) != 0) {
                        thisMseCol -= 2;
                        variance = subpelAt(y, thisMseCol & 7, thisMseRow & 7);
                    } else {
                        thisMseCol = (startCol - 8) | 6;
                        variance = subpelAt(y - 1, 6, thisMseRow & 7);
                    }
                } else {
                    thisMseRow = (startRow - 8) | 6;
                    if ((startCol & 7) != 0) {
                        thisMseCol -= 2;
                        variance = subpelAt(y - stride, thisMseCol & 7, 6);
                    } else {
                        thisMseCol = (startCol - 8) | 6;
                        variance = subpelAt(y - stride - 1, 6, 6);
                    }
                }
            }
            case 1 -> {
                thisMseCol += 2;
                if ((startRow & 7) != 0) {
                    thisMseRow -= 2;
                    variance = subpelAt(y, thisMseCol & 7, thisMseRow & 7);
                } else {
                    thisMseRow = (startRow - 8) | 6;
                    variance = subpelAt(y - stride, thisMseCol & 7, 6);
                }
            }
            case 2 -> {
                thisMseRow += 2;
                if ((startCol & 7) != 0) {
                    thisMseCol -= 2;
                    variance = subpelAt(y, thisMseCol & 7, thisMseRow & 7);
                } else {
                    thisMseCol = (startCol - 8) | 6;
                    variance = subpelAt(y - 1, 6, thisMseRow & 7);
                }
            }
            default -> {
                thisMseCol += 2;
                thisMseRow += 2;
                variance = subpelAt(y, thisMseCol & 7, thisMseRow & 7);
            }
        }
        diag = variance + mvErrCost(Mv.pack(thisMseRow, thisMseCol), ref);
        if (diag < bestMse) {
            bestRow = thisMseRow;
            bestCol = thisMseCol;
            searchDistortion = lastVariance;
            searchSse = lastSse;
        }
        searchMv = Mv.pack(bestRow, bestCol);
    }

    /**
     * Refines {@link #searchMv} to quarter pixels, libvpx's
     * {@code vp8_find_best_sub_pixel_step}.
     *
     * @param ref the packed reference vector the costs are measured from
     */
    private void subpelQuarter(int ref) {
        subpelSteps(ref, true);
    }

    /**
     * Refines {@link #searchMv} to half pixels, libvpx's {@code vp8_find_best_half_pixel_step}.
     *
     * @param ref the packed reference vector the costs are measured from
     */
    private void subpelHalf(int ref) {
        subpelSteps(ref, false);
    }
}
