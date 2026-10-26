/* Ports libvpx 1.15.1 (vp8/encoder/firstpass.c: vp8_first_pass, zz_motion_search, first_pass_motion_search,
   simple_weight; vp8/encoder/encodeintra.c: vp8_encode_intra; vp8/encoder/encodemb.c: vp8_encode_inter16x16y;
   vp8/encoder/mcomp.c: vp8_diamond_search_sad_c). Copyright (c) 2010 The WebM project authors; BSD
   3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.video.vp8.internal;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.internal.task.ParallelBands;

/**
 * Measures the first-pass statistics of VP8 pictures: for each picture, how well its macroblocks
 * predict from intra neighbors, from the previous picture and from an older golden picture, and
 * how its motion vectors are distributed.
 *
 * <p>Each call of {@link #analyze} writes one {@link #FIELDS}-value record. Macroblock rows are
 * measured on up to the given number of threads and summed in row order, so the records do not
 * depend on the threads. Instances are used by one thread at a time.
 *
 * <p>Like libvpx's first pass, the analyzer codes the luma of each picture at quantizer index 26
 * and predicts from that reconstruction: the intra predictions of a macroblock from the
 * reconstruction of its neighbors, the inter predictions from the reconstruction of the previous
 * and golden pictures. Macroblock rows run in a wavefront, each waiting for the reconstruction of
 * the row above. As in libvpx, the first pass quantizes with the fast quantizer at every speed.
 */
public final class FirstPassAnalyzer {
    /**
     * The number of values of a record.
     */
    public static final int FIELDS = 18;

    /**
     * The display index of the frame.
     */
    static final int FRAME = 0;

    /**
     * The intra prediction error of the frame, a sum of squares over 256.
     */
    static final int INTRA_ERROR = 1;

    /**
     * The best of the intra and inter prediction errors, summed like {@link #INTRA_ERROR}.
     */
    static final int CODED_ERROR = 2;

    /**
     * The coded error weighted by the brightness of the frame.
     */
    static final int SSIM_WEIGHTED_PRED_ERR = 3;

    /**
     * The share of macroblocks that predict best from the previous frame.
     */
    static final int PCNT_INTER = 4;

    /**
     * The share of macroblocks that predict best from the previous frame with a nonzero vector.
     */
    static final int PCNT_MOTION = 5;

    /**
     * The share of macroblocks that predict better from the golden frame than from the previous
     * frame and from intra neighbors.
     */
    static final int PCNT_SECOND_REF = 6;

    /**
     * The share of macroblocks whose intra and inter errors are close and small.
     */
    static final int PCNT_NEUTRAL = 7;

    /**
     * The mean row component of the nonzero vectors.
     */
    static final int MVR = 8;

    /**
     * The mean magnitude of the row components of the nonzero vectors.
     */
    static final int MVR_ABS = 9;

    /**
     * The mean column component of the nonzero vectors.
     */
    static final int MVC = 10;

    /**
     * The mean magnitude of the column components of the nonzero vectors.
     */
    static final int MVC_ABS = 11;

    /**
     * The variance of the row components of the nonzero vectors.
     */
    static final int MVRV = 12;

    /**
     * The variance of the column components of the nonzero vectors.
     */
    static final int MVCV = 13;

    /**
     * The balance of the vectors pointing towards the frame center over those pointing away,
     * from -1 to 1.
     */
    static final int MV_IN_OUT_COUNT = 14;

    /**
     * The number of nonzero vectors that differ from the previous nonzero vector.
     */
    static final int NEW_MV_COUNT = 15;

    /**
     * The duration of the frame in {@link RtEncoder#TICKS_PER_SECOND} ticks.
     */
    static final int DURATION = 16;

    /**
     * The number of frames of the record, 1.
     */
    static final int COUNT = 17;

    /**
     * The cost added to the intra error of every macroblock, matching that of a zero vector.
     */
    private static final int INTRA_PENALTY = 256;

    /**
     * The cost added to the error of a searched vector, for its mode and vector bits.
     */
    private static final int NEW_MV_MODE_PENALTY = 256;

    /**
     * The first diamond step of the search, 16 pixels.
     */
    private static final int STEP_PARAM = 3;

    /**
     * The number of diamond steps, libvpx's {@code MAX_MVSEARCH_STEPS}.
     */
    private static final int MAX_MVSEARCH_STEPS = 8;

    /**
     * The quantizer index the pictures are coded at, libvpx's {@code Pass1Encode}.
     */
    private static final int QINDEX = 26;

    /**
     * The cost of a vector bit in squared error units: libvpx's {@code RDMULT / 110}, with
     * {@code RDMULT} 2.80 times the square of the luma DC step at {@link #QINDEX}.
     */
    private static final int ERROR_PER_BIT = Math.max(1,
            (int) (2.80 * Vp8Tables.DC_QLOOKUP[QINDEX] * Vp8Tables.DC_QLOOKUP[QINDEX]) / 110);

    /**
     * The number of integer sums of a macroblock row.
     */
    private static final int ROW_VALUES = 18;

    /**
     * The row stride of the macroblock scratch blocks.
     */
    private static final int BPS = Vp8EncDsp.BPS;

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
     * The motion vector costs of the default probabilities, in 1/256 bit.
     */
    private static final int[] MV_COST = new int[2 * RtStats.MV_VALS];

    /**
     * The luma quantization matrix at {@link #QINDEX}.
     */
    private static final QuantMatrix Y1_MATRIX = new QuantMatrix();

    /**
     * The Y2 quantization matrix at {@link #QINDEX}.
     */
    private static final QuantMatrix Y2_MATRIX = new QuantMatrix();

    static {
        RtEncoder.buildMvCosts(Vp8Tables.MV_PROBS_DEFAULT, MV_COST);
        var dc = Vp8Tables.DC_QLOOKUP[QINDEX];
        var ac = Vp8Tables.AC_QLOOKUP[QINDEX];
        RtEncoder.setMatrix(Y1_MATRIX, dc, ac, RtEncoder.GOOD_ROUNDING);
        RtEncoder.setMatrix(Y2_MATRIX, dc * 2, Math.max((ac * 101581) >> 16, 8), RtEncoder.GOOD_ROUNDING);
    }

    /**
     * The picture width.
     */
    private final int width;

    /**
     * The picture height.
     */
    private final int height;

    /**
     * The number of macroblock columns.
     */
    private final int mbCols;

    /**
     * The number of macroblock rows.
     */
    private final int mbRows;

    /**
     * The picture being measured, padded to whole macroblocks.
     */
    private final FrameBuffer source;

    /**
     * The reconstruction of the picture being measured.
     */
    private FrameBuffer recon;

    /**
     * The reconstruction of the previous picture.
     */
    private FrameBuffer last;

    /**
     * The reconstruction of the golden picture.
     */
    private FrameBuffer golden;

    /**
     * The integer sums of each macroblock row.
     */
    private final long[] rowValues;

    /**
     * The brightness weight of each macroblock row.
     */
    private final double[] rowWeights;

    /**
     * The block metric kernels.
     */
    private final RtDsp dsp = RtDsp.INSTANCE;

    /**
     * The transform and quantization kernels.
     */
    private final Vp8EncDsp encDsp = Vp8EncDsp.INSTANCE;

    /**
     * The inverse transform kernels.
     */
    private final Vp8Dsp decDsp = Vp8Dsp.INSTANCE;

    /**
     * The scratch of each participant of the wavefront.
     */
    private Scratch[] scratch = {new Scratch()};

    /**
     * The wavefront that runs the rows on several threads.
     */
    private final RowWavefront wavefront;

    /**
     * The measurement of one row in the wavefront.
     */
    private final RowWavefront.RowBody rowBody = this::wavefrontRow;

    /**
     * The number of pictures measured.
     */
    private int frames;

    /**
     * The scratch of one thread measuring macroblocks.
     */
    private static final class Scratch {
        /**
         * The search result: the vector found and the steps that did not move.
         */
        final int[] search = new int[2];

        /**
         * The source macroblock, {@link #BPS} samples a row.
         */
        final byte[] src = new byte[16 * BPS];

        /**
         * The prediction of the macroblock, laid out like {@link #src}.
         */
        final byte[] pred = new byte[16 * BPS];

        /**
         * The coefficients of the 16 luma blocks, 16 a block in raster order.
         */
        final short[] coef = new short[256];

        /**
         * The levels of one block in zigzag order.
         */
        final short[] levels = new short[16];

        /**
         * The DC coefficients of the 16 luma blocks before the Walsh-Hadamard transform.
         */
        final int[] dcIn = new int[16];

        /**
         * The Y2 coefficients.
         */
        final short[] y2 = new short[16];

        /**
         * The reconstructed DC coefficients of the 16 luma blocks.
         */
        final short[] dc = new short[16];

        /**
         * The scratch of the Walsh-Hadamard transforms.
         */
        final int[] wht = new int[16];
    }

    /**
     * Creates an analyzer of a picture size.
     *
     * @param width the picture width
     * @param height the picture height
     * @param limits the allocation limits
     */
    public FirstPassAnalyzer(int width, int height, MediaLimits limits) {
        this.width = width;
        this.height = height;
        this.mbCols = (width + 15) >> 4;
        this.mbRows = (height + 15) >> 4;
        this.source = new FrameBuffer(mbCols, mbRows, limits);
        this.recon = new FrameBuffer(mbCols, mbRows, limits);
        this.last = new FrameBuffer(mbCols, mbRows, limits);
        this.golden = new FrameBuffer(mbCols, mbRows, limits);
        this.rowValues = new long[mbRows * ROW_VALUES];
        this.rowWeights = new double[mbRows];
        this.wavefront = new RowWavefront(mbRows);
    }

    /**
     * Measures a picture against the pictures before it and writes its record.
     *
     * @param picture the picture, of the analyzer's size in limited-range BT.601 4:2:0
     * @param durationTicks the picture's duration in {@link RtEncoder#TICKS_PER_SECOND} ticks
     * @param out receives the record
     * @param off the index of the record's first value
     * @param threads the most threads to use
     */
    public void analyze(YuvPicture picture, long durationTicks, double[] out, int off, int threads) {
        load(picture);
        var participants = ParallelBands.enabled() ? Math.max(1, Math.min(threads, mbRows)) : 1;
        if (participants > 1) {
            if (scratch.length < participants) {
                var grown = java.util.Arrays.copyOf(scratch, participants);
                for (var i = scratch.length; i < participants; i++) {
                    grown[i] = new Scratch();
                }
                scratch = grown;
            }
            for (var r = 0; r < mbRows; r++) {
                wavefront.reset(r, 0);
            }
            wavefront.run(participants, 0, mbRows, rowBody);
        } else {
            for (var r = 0; r < mbRows; r++) {
                row(r, scratch[0], null);
            }
        }
        for (var r = 0; r < mbRows; r++) {
            recon.extendRowSides(r);
        }
        recon.extendTopBottom();
        long intraError = 0;
        long codedError = 0;
        long interCount = 0;
        long secondRefCount = 0;
        long neutralCount = 0;
        long mvCount = 0;
        long sumMvr = 0;
        long sumMvrAbs = 0;
        long sumMvc = 0;
        long sumMvcAbs = 0;
        long sumMvrs = 0;
        long sumMvcs = 0;
        long sumInVectors = 0;
        long newMvCount = 0;
        var lastMv = 0L;
        var weight = 0.0;
        for (var r = 0; r < mbRows; r++) {
            var base = r * ROW_VALUES;
            intraError += rowValues[base];
            codedError += rowValues[base + 1];
            interCount += rowValues[base + 2];
            secondRefCount += rowValues[base + 3];
            neutralCount += rowValues[base + 4];
            mvCount += rowValues[base + 5];
            sumMvr += rowValues[base + 6];
            sumMvrAbs += rowValues[base + 7];
            sumMvc += rowValues[base + 8];
            sumMvcAbs += rowValues[base + 9];
            sumMvrs += rowValues[base + 10];
            sumMvcs += rowValues[base + 11];
            sumInVectors += rowValues[base + 12];
            newMvCount += rowValues[base + 13];
            if (rowValues[base + 16] != 0) {
                if (rowValues[base + 14] != lastMv) {
                    newMvCount++;
                }
                lastMv = rowValues[base + 15];
            }
            weight += rowWeights[r];
        }
        weight /= (double) mbRows * 16 * mbCols * 16;
        if (weight < 0.1) {
            weight = 0.1;
        }
        var mbs = (double) mbRows * mbCols;
        java.util.Arrays.fill(out, off, off + FIELDS, 0.0);
        out[off + FRAME] = frames;
        out[off + INTRA_ERROR] = intraError >> 8;
        out[off + CODED_ERROR] = codedError >> 8;
        out[off + SSIM_WEIGHTED_PRED_ERR] = out[off + CODED_ERROR] * weight;
        out[off + COUNT] = 1.0;
        out[off + PCNT_INTER] = interCount / mbs;
        out[off + PCNT_SECOND_REF] = secondRefCount / mbs;
        out[off + PCNT_NEUTRAL] = neutralCount / mbs;
        if (mvCount > 0) {
            var n = (double) mvCount;
            out[off + MVR] = sumMvr / n;
            out[off + MVR_ABS] = sumMvrAbs / n;
            out[off + MVC] = sumMvc / n;
            out[off + MVC_ABS] = sumMvcAbs / n;
            out[off + MVRV] = (sumMvrs - out[off + MVR] * out[off + MVR] / n) / n;
            out[off + MVCV] = (sumMvcs - out[off + MVC] * out[off + MVC] / n) / n;
            out[off + MV_IN_OUT_COUNT] = sumInVectors / (n * 2);
            out[off + NEW_MV_COUNT] = newMvCount;
            out[off + PCNT_MOTION] = n / mbs;
        }
        out[off + DURATION] = durationTicks;
        if (frames > 0 && out[off + PCNT_INTER] > 0.20
                && out[off + INTRA_ERROR] / divideCheck(out[off + CODED_ERROR]) > 2.0) {
            var swap = golden;
            golden = last;
            last = swap;
        }
        var swap = last;
        last = recon;
        recon = swap;
        if (frames == 0) {
            copyFrame(last, golden);
        }
        frames++;
    }

    /**
     * Moves a value away from zero, so it can divide ({@code DOUBLE_DIVIDE_CHECK}).
     *
     * @param x the value
     * @return the value moved by a millionth away from zero
     */
    private static double divideCheck(double x) {
        return x < 0 ? x - 0.000001 : x + 0.000001;
    }

    /**
     * Copies the luma plane of a picture into {@link #source} and pads it to whole macroblocks.
     *
     * @param picture the picture to measure
     */
    private void load(YuvPicture picture) {
        var frame = source;
        copyPadded(picture.y(), picture.yStride(), width, height, frame.y, frame.yOrigin, frame.yStride, mbCols * 16,
                mbRows * 16);
    }

    /**
     * Copies a plane into a larger one, replicating its last column and row into the padding.
     *
     * @param src the source plane
     * @param srcStride the source row stride
     * @param w the source width
     * @param h the source height
     * @param dst the destination plane
     * @param dstOff the index of the destination's first sample
     * @param dstStride the destination row stride
     * @param dw the padded width
     * @param dh the padded height
     */
    private static void copyPadded(byte[] src, int srcStride, int w, int h, byte[] dst, int dstOff, int dstStride,
                                   int dw, int dh) {
        for (var r = 0; r < h; r++) {
            var d = dstOff + r * dstStride;
            System.arraycopy(src, r * srcStride, dst, d, w);
            if (dw > w) {
                java.util.Arrays.fill(dst, d + w, d + dw, dst[d + w - 1]);
            }
        }
        for (var r = h; r < dh; r++) {
            System.arraycopy(dst, dstOff + (h - 1) * dstStride, dst, dstOff + r * dstStride, dw);
        }
    }

    /**
     * Copies every plane of a frame, borders included.
     *
     * @param from the frame copied
     * @param to the frame overwritten
     */
    private static void copyFrame(FrameBuffer from, FrameBuffer to) {
        System.arraycopy(from.y, 0, to.y, 0, from.y.length);
        System.arraycopy(from.u, 0, to.u, 0, from.u.length);
        System.arraycopy(from.v, 0, to.v, 0, from.v.length);
    }

    /**
     * Measures and reconstructs one macroblock row as a participant of {@link #wavefront}.
     *
     * @param participant the index of the participant, from zero
     * @param mbRow the macroblock row
     */
    private void wavefrontRow(int participant, int mbRow) {
        row(mbRow, scratch[participant], wavefront);
    }

    /**
     * Measures and reconstructs one macroblock row and stores its sums.
     *
     * @param mbRow the macroblock row
     * @param sc the thread's scratch
     * @param wait how to wait for the row above, or {@code null} on a single thread
     */
    private void row(int mbRow, Scratch sc, RowWavefront wait) {
        var stride = source.yStride;
        var y = source.y;
        var rec = recon.y;
        var search = sc.search;
        long intraError = 0;
        long codedError = 0;
        long interCount = 0;
        long secondRefCount = 0;
        long neutralCount = 0;
        long mvCount = 0;
        long sumMvr = 0;
        long sumMvrAbs = 0;
        long sumMvc = 0;
        long sumMvcAbs = 0;
        long sumMvrs = 0;
        long sumMvcs = 0;
        long sumInVectors = 0;
        long newMvCount = 0;
        var firstMv = 0;
        var lastMv = 0;
        var anyMv = false;
        var bestRefMv = 0;
        var rowMin = -((mbRow * 16) + 16);
        var rowMax = ((mbRows - 1 - mbRow) * 16) + 16;
        var weight = 0.0;
        for (var r = 0; r < 16; r++) {
            var off = source.yOrigin + (mbRow * 16 + r) * stride;
            for (var c = 0; c < mbCols * 16; c++) {
                var value = y[off + c] & 0xFF;
                weight += value <= 32 ? 0.02 : value >= 64 ? 1.0 : (value - 32) / 32.0;
            }
        }
        var above = 0;
        for (var mbCol = 0; mbCol < mbCols; mbCol++) {
            if (wait != null && mbRow > 0 && above < mbCol + 1) {
                above = wait.await(mbRow - 1, mbCol + 1);
            }
            var yOff = source.yOrigin + mbRow * 16 * stride + mbCol * 16;
            for (var r = 0; r < 16; r++) {
                System.arraycopy(y, yOff + r * stride, sc.src, r * BPS, 16);
            }
            var useDcPred = (mbCol != 0 || mbRow != 0) && (mbCol == 0 || mbRow == 0);
            var thisError = (useDcPred ? intra16x16(sc, rec, yOff, stride, mbRow)
                    : intra4x4(sc, rec, yOff, stride, mbRow, mbCol)) + INTRA_PENALTY;
            intraError += thisError;
            var colMin = -((mbCol * 16) + 16);
            var colMax = ((mbCols - 1 - mbCol) * 16) + 16;
            if (frames > 0) {
                var lastY = last.y;
                var motionError = RtDsp.sse(dsp.variance16(y, yOff, stride, lastY, yOff, stride));
                var mv = 0;
                var error = motionSearch(y, yOff, stride, lastY, bestRefMv, rowMin, rowMax, colMin, colMax, search);
                if (error < motionError) {
                    motionError = error;
                    mv = search[0];
                }
                if (bestRefMv != 0) {
                    error = motionSearch(y, yOff, stride, lastY, 0, rowMin, rowMax, colMin, colMax, search);
                    if (error < motionError) {
                        motionError = error;
                        mv = search[0];
                    }
                }
                if (frames > 1) {
                    var gfError = motionSearch(y, yOff, stride, golden.y, 0, rowMin, rowMax, colMin, colMax, search);
                    if (gfError < motionError && gfError < thisError) {
                        secondRefCount++;
                    }
                }
                bestRefMv = 0;
                if (motionError <= thisError) {
                    if ((long) (thisError - INTRA_PENALTY) * 9 <= (long) motionError * 10
                            && thisError < 2 * INTRA_PENALTY) {
                        neutralCount++;
                    }
                    inter(sc, rec, yOff, stride, lastY, yOff + Mv.row(mv) * stride + Mv.col(mv));
                    var mvRow = Mv.row(mv) * 8;
                    var mvCol = Mv.col(mv) * 8;
                    mv = Mv.pack(mvRow, mvCol);
                    thisError = motionError;
                    sumMvr += mvRow;
                    sumMvrAbs += Math.abs(mvRow);
                    sumMvc += mvCol;
                    sumMvcAbs += Math.abs(mvCol);
                    sumMvrs += (long) mvRow * mvRow;
                    sumMvcs += (long) mvCol * mvCol;
                    interCount++;
                    bestRefMv = mv;
                    if (mv != 0) {
                        mvCount++;
                        if (!anyMv) {
                            firstMv = mv;
                            anyMv = true;
                        } else if (mv != lastMv) {
                            newMvCount++;
                        }
                        lastMv = mv;
                        if (mbRow < mbRows / 2) {
                            sumInVectors += mvRow > 0 ? -1 : mvRow < 0 ? 1 : 0;
                        } else if (mbRow > mbRows / 2) {
                            sumInVectors += mvRow > 0 ? 1 : mvRow < 0 ? -1 : 0;
                        }
                        if (mbCol < mbCols / 2) {
                            sumInVectors += mvCol > 0 ? -1 : mvCol < 0 ? 1 : 0;
                        } else if (mbCol > mbCols / 2) {
                            sumInVectors += mvCol > 0 ? 1 : mvCol < 0 ? -1 : 0;
                        }
                    }
                }
            }
            codedError += thisError;
            if (wait != null) {
                wait.publish(mbRow, mbCol + 1);
            }
        }
        var base = mbRow * ROW_VALUES;
        var values = rowValues;
        values[base] = intraError;
        values[base + 1] = codedError;
        values[base + 2] = interCount;
        values[base + 3] = secondRefCount;
        values[base + 4] = neutralCount;
        values[base + 5] = mvCount;
        values[base + 6] = sumMvr;
        values[base + 7] = sumMvrAbs;
        values[base + 8] = sumMvc;
        values[base + 9] = sumMvcAbs;
        values[base + 10] = sumMvrs;
        values[base + 11] = sumMvcs;
        values[base + 12] = sumInVectors;
        values[base + 13] = newMvCount;
        values[base + 14] = firstMv;
        values[base + 15] = lastMv;
        values[base + 16] = anyMv ? 1 : 0;
        rowWeights[mbRow] = weight;
    }

    /**
     * Codes a macroblock with the whole-block DC prediction, libvpx's {@code vp8_encode_intra}
     * with {@code use_dc_pred}: the prediction averages the reconstructed row above on the frame's
     * first column, and the reconstructed column on the left on its first row.
     *
     * @param sc the thread's scratch, holding the source macroblock
     * @param rec the luma plane of the reconstruction, which receives the macroblock's
     * @param off the index of the macroblock's top-left sample
     * @param stride the row stride
     * @param mbRow the macroblock row
     * @return the sum of the squared differences from the prediction
     */
    private int intra16x16(Scratch sc, byte[] rec, int off, int stride, int mbRow) {
        var sum = 0;
        for (var i = 0; i < 16; i++) {
            sum += mbRow > 0 ? rec[off - stride + i] & 0xFF : rec[off + i * stride - 1] & 0xFF;
        }
        var dc = (sum + 8) >> 4;
        var error = 0;
        for (var r = 0; r < 16; r++) {
            java.util.Arrays.fill(sc.pred, r * BPS, r * BPS + 16, (byte) dc);
            java.util.Arrays.fill(rec, off + r * stride, off + r * stride + 16, (byte) dc);
            for (var c = 0; c < 16; c++) {
                var d = (sc.src[r * BPS + c] & 0xFF) - dc;
                error += d * d;
            }
        }
        codeLuma(sc, rec, off, stride);
        return error;
    }

    /**
     * Codes a macroblock with the {@code B_DC_PRED} prediction of each 4x4 block, libvpx's
     * {@code vp8_encode_intra} without {@code use_dc_pred}: each block predicts from the
     * reconstruction of the blocks above and on its left, or from 127 above and 129 on the left
     * outside the frame.
     *
     * @param sc the thread's scratch, holding the source macroblock
     * @param rec the luma plane of the reconstruction, which receives the macroblock's
     * @param off the index of the macroblock's top-left sample
     * @param stride the row stride
     * @param mbRow the macroblock row
     * @param mbCol the macroblock column
     * @return the sum of the squared differences from the predictions
     */
    private int intra4x4(Scratch sc, byte[] rec, int off, int stride, int mbRow, int mbCol) {
        var error = 0;
        var coef = sc.coef;
        for (var b = 0; b < 16; b++) {
            var br = b >> 2;
            var bc = b & 3;
            var blockOff = off + br * 4 * stride + bc * 4;
            var bps = br * 4 * BPS + bc * 4;
            var sum = 0;
            for (var i = 0; i < 4; i++) {
                sum += mbRow == 0 && br == 0 ? 127 : rec[blockOff - stride + i] & 0xFF;
                sum += mbCol == 0 && bc == 0 ? 129 : rec[blockOff + i * stride - 1] & 0xFF;
            }
            var dc = (sum + 4) >> 3;
            for (var r = 0; r < 4; r++) {
                java.util.Arrays.fill(sc.pred, bps + r * BPS, bps + r * BPS + 4, (byte) dc);
                java.util.Arrays.fill(rec, blockOff + r * stride, blockOff + r * stride + 4, (byte) dc);
                for (var c = 0; c < 4; c++) {
                    var d = (sc.src[bps + r * BPS + c] & 0xFF) - dc;
                    error += d * d;
                }
            }
            encDsp.fTransform(sc.src, bps, sc.pred, bps, coef, 0);
            encDsp.quantizeBlock(coef, 0, sc.levels, 0, Y1_MATRIX);
            var eob = RtMbCoder.eobOf(sc.levels, 0);
            if (eob > 1) {
                decDsp.idctAdd(coef, 0, rec, blockOff, stride);
            } else if (eob == 1) {
                decDsp.dcOnlyAdd(coef[0], rec, blockOff, stride);
            }
        }
        return error;
    }

    /**
     * Codes a macroblock predicted from the previous reconstruction by a full-pixel vector,
     * libvpx's {@code vp8_encode_inter16x16y}.
     *
     * @param sc the thread's scratch, holding the source macroblock
     * @param rec the luma plane of the reconstruction, which receives the macroblock's
     * @param off the index of the macroblock's top-left sample
     * @param stride the row stride
     * @param ref the luma plane of the previous reconstruction
     * @param refOff the index of the prediction's top-left sample in {@code ref}
     */
    private void inter(Scratch sc, byte[] rec, int off, int stride, byte[] ref, int refOff) {
        for (var r = 0; r < 16; r++) {
            System.arraycopy(ref, refOff + r * stride, sc.pred, r * BPS, 16);
            System.arraycopy(ref, refOff + r * stride, rec, off + r * stride, 16);
        }
        codeLuma(sc, rec, off, stride);
    }

    /**
     * Transforms, quantizes and reconstructs the luma residual of a whole-block prediction, with
     * the DC coefficients of the 16 blocks coded in the Y2 block.
     *
     * @param sc the thread's scratch, holding the source macroblock and its prediction
     * @param rec the luma plane holding the prediction, overwritten with the reconstruction
     * @param off the index of the macroblock's top-left sample
     * @param stride the row stride
     */
    private void codeLuma(Scratch sc, byte[] rec, int off, int stride) {
        var coef = sc.coef;
        var levels = sc.levels;
        var acBlocks = 0;
        for (var b = 0; b < 16; b++) {
            var bps = (b >> 2) * 4 * BPS + (b & 3) * 4;
            encDsp.fTransform(sc.src, bps, sc.pred, bps, coef, b * 16);
            sc.dcIn[b] = coef[b * 16];
            encDsp.quantizeBlock(coef, b * 16, levels, 0, Y1_MATRIX);
            for (var n = 1; n < 16; n++) {
                if (levels[n] != 0) {
                    acBlocks |= 1 << b;
                    break;
                }
            }
        }
        forwardWht(sc.dcIn, sc.y2, sc.wht);
        encDsp.quantizeBlock(sc.y2, 0, levels, 0, Y2_MATRIX);
        RtMbCoder.inverseWht(sc.y2, RtMbCoder.eobOf(levels, 0), sc.dc, sc.wht);
        for (var b = 0; b < 16; b++) {
            var blockOff = off + (b >> 2) * 4 * stride + (b & 3) * 4;
            coef[b * 16] = sc.dc[b];
            if ((acBlocks & (1 << b)) != 0) {
                decDsp.idctAdd(coef, b * 16, rec, blockOff, stride);
            } else if (sc.dc[b] != 0) {
                decDsp.dcOnlyAdd(sc.dc[b], rec, blockOff, stride);
            }
        }
    }

    /**
     * Applies the forward Walsh-Hadamard transform to the DC coefficients of the 16 luma blocks,
     * as the encoder does.
     *
     * @param dc the DC coefficients in raster block order
     * @param out receives the 16 Y2 coefficients in raster order
     * @param tmp 16 values of scratch
     */
    private static void forwardWht(int[] dc, short[] out, int[] tmp) {
        for (var i = 0; i < 4; i++) {
            var base = i * 4;
            var a0 = dc[base] + dc[base + 2];
            var a1 = dc[base + 1] + dc[base + 3];
            var a2 = dc[base + 1] - dc[base + 3];
            var a3 = dc[base] - dc[base + 2];
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
     * Searches the vector of a macroblock in a reference by diamond steps from 16 pixels down,
     * libvpx's {@code first_pass_motion_search}.
     *
     * @param src the luma plane of the picture
     * @param off the index of the macroblock's top-left sample in the picture and the reference
     * @param stride the row stride of both
     * @param ref the luma plane of the reference
     * @param refMv the packed vector the search starts from and costs vectors against, in 1/8 pixel
     * @param rowMin the smallest row of the search window, in full pixels
     * @param rowMax the largest row of the search window
     * @param colMin the smallest column of the search window
     * @param colMax the largest column of the search window
     * @param result receives the packed full-pixel vector of the result at index 0; index 1 is
     * scratch
     * @return the squared error at the result plus its vector and mode costs
     */
    private int motionSearch(byte[] src, int off, int stride, byte[] ref, int refMv, int rowMin, int rowMax,
                             int colMin, int colMax, int[] result) {
        var startRow = Mv.row(refMv) >> 3;
        var startCol = Mv.col(refMv) >> 3;
        var furtherSteps = MAX_MVSEARCH_STEPS - 1 - STEP_PARAM;
        var best = Integer.MAX_VALUE;
        var bestMv = 0;
        var error = diamond(src, off, stride, ref, startRow, startCol, STEP_PARAM, refMv, rowMin, rowMax, colMin,
                colMax, result);
        error = penalize(error);
        if (error < best) {
            best = error;
            bestMv = result[0];
        }
        var n = result[1];
        var skip = 0;
        while (n < furtherSteps) {
            n++;
            if (skip != 0) {
                skip--;
            } else {
                error = diamond(src, off, stride, ref, startRow, startCol, STEP_PARAM + n, refMv, rowMin, rowMax,
                        colMin, colMax, result);
                skip = result[1];
                error = penalize(error);
                if (error < best) {
                    best = error;
                    bestMv = result[0];
                }
            }
        }
        result[0] = bestMv;
        return best;
    }

    /**
     * Adds the mode penalty of a searched vector to its error, short of overflow.
     *
     * @param error the squared error plus vector cost of the vector
     * @return the penalized error
     */
    private static int penalize(int error) {
        return error < Integer.MAX_VALUE - NEW_MV_MODE_PENALTY ? error + NEW_MV_MODE_PENALTY : error;
    }

    /**
     * Searches the full-pixel vector with the lowest SAD by shrinking eight-point steps, libvpx's
     * {@code vp8_diamond_search_sad_c} with the squared error as its final measure.
     *
     * <p>The SAD comparisons carry no vector cost: libvpx's first pass never sets the
     * {@code sadperbit16} weight of its vector costs, which stays zero.
     *
     * @param src the luma plane of the picture
     * @param off the index of the macroblock's top-left sample
     * @param stride the row stride
     * @param ref the luma plane of the reference
     * @param startRow the start row in full pixels
     * @param startCol the start column in full pixels
     * @param stepParam the index of the first step: the first step is {@code 128 >> stepParam}
     * @param center the packed vector the final cost is measured from, in 1/8 pixel
     * @param rowMin the smallest row of the search window, in full pixels
     * @param rowMax the largest row of the search window
     * @param colMin the smallest column of the search window
     * @param colMax the largest column of the search window
     * @param result receives the packed full-pixel vector of the result at index 0 and the number
     * of steps that did not move from the start at index 1
     * @return the squared error at the result plus its vector cost
     */
    private int diamond(byte[] src, int off, int stride, byte[] ref, int startRow, int startCol, int stepParam,
                        int center, int rowMin, int rowMax, int colMin, int colMax, int[] result) {
        var refRow = Math.clamp(startRow, rowMin, rowMax);
        var refCol = Math.clamp(startCol, colMin, colMax);
        var num00 = 0;
        var bestRow = refRow;
        var bestCol = refCol;
        var bestSad = dsp.sad16(src, off, stride, ref, off + bestRow * stride + bestCol, stride);
        var totSteps = MAX_MVSEARCH_STEPS - stepParam;
        for (var step = 0; step < totSteps; step++) {
            var len = 128 >> (stepParam + step);
            var bestDr = 0;
            var bestDc = 0;
            for (var site = 0; site < 8; site++) {
                var dr = DIAMOND_ROWS[site] * len;
                var dc = DIAMOND_COLS[site] * len;
                var r = bestRow + dr;
                var c = bestCol + dc;
                if (c > colMin && c < colMax && r > rowMin && r < rowMax) {
                    var sad = dsp.sad16(src, off, stride, ref, off + r * stride + c, stride);
                    if (sad < bestSad) {
                        bestSad = sad;
                        bestDr = dr;
                        bestDc = dc;
                    }
                }
            }
            if (bestDr != 0 || bestDc != 0) {
                bestRow += bestDr;
                bestCol += bestDc;
            } else if (bestRow == refRow && bestCol == refCol) {
                num00++;
            }
        }
        result[0] = Mv.pack(bestRow, bestCol);
        result[1] = num00;
        var sse = RtDsp.sse(dsp.variance16(src, off, stride, ref, off + bestRow * stride + bestCol, stride));
        return sse + mvErrCost(bestRow * 8, bestCol * 8, center);
    }

    /**
     * Returns the vector cost of a vector in squared error units, libvpx's {@code mv_err_cost}.
     *
     * @param row the vector's row in 1/8 pixel
     * @param col the vector's column in 1/8 pixel
     * @param center the packed vector the cost is measured from, in 1/8 pixel
     * @return the cost
     */
    private static int mvErrCost(int row, int col, int center) {
        var r = Math.clamp((row - Mv.row(center)) >> 1, -RtStats.MV_MAX, RtStats.MV_MAX);
        var c = Math.clamp((col - Mv.col(center)) >> 1, -RtStats.MV_MAX, RtStats.MV_MAX);
        return (int) (((long) (MV_COST[RtStats.MV_MAX + r] + MV_COST[RtStats.MV_VALS + RtStats.MV_MAX + c])
                * ERROR_PER_BIT + 128) >> 8);
    }
}
