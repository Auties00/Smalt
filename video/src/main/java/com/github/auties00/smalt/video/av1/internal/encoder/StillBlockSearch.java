package com.github.auties00.smalt.video.av1.internal.encoder;

import com.github.auties00.smalt.video.av1.internal.BlockContext;
import com.github.auties00.smalt.video.av1.internal.Cdf;
import com.github.auties00.smalt.video.av1.internal.EncoderBridge;
import com.github.auties00.smalt.video.av1.internal.FrameHeader;
import com.github.auties00.smalt.video.av1.internal.IntraEdge;
import com.github.auties00.smalt.video.av1.internal.IpredDsp;
import com.github.auties00.smalt.video.av1.internal.ItxDsp;
import com.github.auties00.smalt.video.av1.internal.SequenceHeader;
import com.github.auties00.smalt.video.av1.internal.Tables;
import com.github.auties00.smalt.video.av1.internal.TaskContext;
import com.github.auties00.smalt.video.av1.internal.TileState;

import java.util.Arrays;
import java.util.HashMap;

import static com.github.auties00.smalt.video.av1.internal.Tables.BLOCK_DIMENSIONS;

/**
 * Chooses the coding of one intra block of a still frame by rate-distortion optimization: its
 * luma mode (every prediction mode, the angle deltas of the directional modes and filter intra),
 * its transform size and the transform type of each transform block, and its chroma mode
 * (chroma-from-luma included).
 *
 * <p>Candidates are first ranked by a model cost, the Hadamard-transformed prediction error plus
 * the mode's symbol cost, and the best ones are coded in full: predicted transform block by
 * transform block from the reconstructed neighbors, transformed, quantized and reconstructed,
 * with the distortion measured on the reconstruction and the rate counted with the tile's current
 * CDFs. The search leaves the chosen coding reconstructed in the picture and the block's contexts
 * updated as the decoder updates them.
 *
 * <p>An instance belongs to one tile encoder and is used by one thread at a time.
 *
 * @implNote This implementation follows libaom's intra mode search ({@code av1/encoder/intra_mode_search.c}:
 * {@code av1_rd_pick_intra_sby_mode} with the model-based pruning of
 * {@code prune_intra_y_mode}, {@code rd_pick_filter_intra_sby},
 * {@code av1_rd_pick_intra_sbuv_mode} and {@code cfl_rd_pick_alpha};
 * {@code av1/encoder/tx_search.c}: {@code av1_pick_uniform_tx_size_type_yrd}) with
 * pixel-domain distortion and rates counted from the adapted CDFs.
 */
final class StillBlockSearch {
    /**
     * The largest number of luma candidates the model ranks: 13 modes plus 6 deltas for each of
     * the 8 directional ones.
     */
    private static final int MAX_LUMA_CANDIDATES = 61;

    /**
     * The intra transform types of the 16x16 class and of the reduced set, in symbol order.
     */
    private static final int[] SET_INTRA2 = {9, 0, 3, 1, 2};

    /**
     * The intra transform types of the smaller sizes, in symbol order.
     */
    private static final int[] SET_INTRA1 = {9, 0, 10, 11, 3, 1, 2};

    /**
     * The single transform type of the largest sizes and of lossless blocks.
     */
    private static final int[] SET_DCT = {0};

    /**
     * The filter intra mode matching each luma mode, -1 for none: vertical, horizontal, D157 and
     * Paeth have filter counterparts ({@code av1_derived_filter_intra_mode_used_flag}).
     */
    private static final int[] FILTER_FOR_MODE = {0, 1, 2, -1, -1, -1, 3, -1, -1, -1, -1, -1, 4, -1};

    /**
     * The transform types of {@link #SET_INTRA1} searched for each luma mode when the search is
     * reduced: the four sinusoidal types and the one-dimensional or identity type the mode's
     * direction favors ({@code av1_reduced_intra_tx_used_flag}).
     */
    private static final int[][] REDUCED_INTRA1 = reducedIntra1();

    /**
     * The frame.
     */
    private final StillFrameEncoder frame;

    /**
     * The tile's task context.
     */
    private final TaskContext t;

    /**
     * The tile's state.
     */
    private final TileState ts;

    /**
     * The coder that counts symbol costs.
     */
    private final RangeEncoder counter = new RangeEncoder();

    /**
     * The coefficient writer used to count coefficient costs.
     */
    private final CoefWriter coefWriter = new CoefWriter();

    /**
     * The transform block coder.
     */
    final TxbCoder txb;

    /**
     * The gradient histogram pruner of the directional luma modes.
     */
    private final IntraHog hogY = new IntraHog();

    /**
     * The gradient histogram pruner of the directional chroma modes.
     */
    private final IntraHog hogUv = new IntraHog();

    /**
     * The model prediction of a whole block.
     */
    private final short[] pred = new short[64 * 64];

    /**
     * The model residual of a whole block.
     */
    private final int[] modelResidual = new int[64 * 64];

    /**
     * The saved luma samples of the block being searched.
     */
    private final short[] savedY = new short[64 * 64];

    /**
     * The saved chroma samples of the block being searched, U then V.
     */
    private final short[] savedUv = new short[2 * 64 * 64];

    /**
     * The saved above luma coefficient contexts.
     */
    private final byte[] savedALcoef = new byte[32];

    /**
     * The saved left luma coefficient contexts.
     */
    private final byte[] savedLLcoef = new byte[32];

    /**
     * The saved above chroma coefficient contexts.
     */
    private final byte[] savedACcoef = new byte[64];

    /**
     * The saved left chroma coefficient contexts.
     */
    private final byte[] savedLCcoef = new byte[64];

    /**
     * The model cost of each luma candidate.
     */
    private final long[] candCost = new long[MAX_LUMA_CANDIDATES];

    /**
     * The mode of each luma candidate.
     */
    private final int[] candMode = new int[MAX_LUMA_CANDIDATES];

    /**
     * The angle delta of each luma candidate.
     */
    private final int[] candAngle = new int[MAX_LUMA_CANDIDATES];

    /**
     * The transform types chosen by the luma pass being evaluated.
     */
    private int[] passTypes = new int[256];

    /**
     * The number of entries of {@link #passTypes}.
     */
    private int passTypeCount;

    /**
     * The rate of the last luma or chroma pass, in {@code 1 / 512} bits.
     */
    private long passRate;

    /**
     * The squared error of the last luma or chroma pass, at 8-bit scale.
     */
    private long passDist;

    /**
     * Whether every coefficient of the last pass is zero.
     */
    private boolean passZero;

    /**
     * The chroma-from-luma contribution of the block being searched.
     */
    private final short[] ac = new short[32 * 32];

    /**
     * The palette syntax writer, used to count palette costs.
     */
    final PaletteWriter paletteWriter = new PaletteWriter();

    /**
     * Whether the next luma or chroma pass predicts from the palette in {@link TaskContext#pal}
     * and the packed indices in {@link #packedY} or {@link #packedUv}.
     */
    private boolean palettePass;

    /**
     * The luma palette indices of the candidate being evaluated, one per byte.
     */
    private final byte[] mapY = new byte[64 * 64];

    /**
     * The chroma palette indices of the candidate being evaluated, one per byte.
     */
    private final byte[] mapUv = new byte[64 * 64];

    /**
     * The packed luma palette indices of the candidate being evaluated.
     */
    final byte[] packedY = new byte[32 * 64];

    /**
     * The packed chroma palette indices of the candidate being evaluated.
     */
    final byte[] packedUv = new byte[32 * 64];

    /**
     * The sample counts of the distinct values of a block, indexed by value.
     */
    private final int[] histogram = new int[1 << 12];

    /**
     * The distinct values or value pairs of a block.
     */
    private final int[] distinct = new int[4096];

    /**
     * Creates the search of a tile.
     *
     * @param frame the frame encoder
     * @param t the tile's task context
     * @param ts the tile's state
     */
    StillBlockSearch(StillFrameEncoder frame, TaskContext t, TileState ts) {
        this.frame = frame;
        this.t = t;
        this.ts = ts;
        this.txb = new TxbCoder(frame);
        counter.reset(false);
        counter.startCounting();
    }

    /**
     * Converts a symbol cost to the scale of the model costs: the Hadamard sums, normalized to
     * absolute differences, are weighed against the bits with the square root of the
     * Lagrangian multiplier of squared errors.
     *
     * @param rate the cost in {@code 1 / 512} bits
     * @return the model cost of the rate
     */
    private long modelRate(long rate) {
        return (long) (rate * modelLambda) >> 9;
    }

    /**
     * The weight of one bit against the normalized Hadamard sums, the square root of the
     * Lagrangian multiplier of squared errors.
     */
    private double modelLambda;

    /**
     * Prepares the search of a new frame: the model weight of its Lagrangian multiplier, and no
     * cached source gradients.
     */
    void frameStarted() {
        modelLambda = Math.sqrt(frame.rdmult / 2048.0);
        hogY.reset();
        hogUv.reset();
    }

    /**
     * Returns the cost of a multi-symbol with the tile's current CDFs.
     *
     * @param off the offset of the CDF
     * @param val the symbol
     * @param n the number of symbols minus one
     * @return the cost in {@code 1 / 512} bits
     */
    int symbolCost(int off, int val, int n) {
        counter.startCounting();
        counter.encodeSymbol(ts.cdf, off, val, n);
        return (int) counter.cost();
    }

    /**
     * Returns the cost of a binary symbol with the tile's current CDFs.
     *
     * @param off the offset of the CDF
     * @param bit the bit, 0 or 1
     * @return the cost in {@code 1 / 512} bits
     */
    int boolCost(int off, int bit) {
        counter.startCounting();
        counter.encodeBool(ts.cdf, off, bit);
        return (int) counter.cost();
    }

    /**
     * Returns the cost of a binary symbol of a fixed probability.
     *
     * @param bit the bit, 0 or 1
     * @param f the inverse probability of a zero, scaled by 32768
     * @return the cost in {@code 1 / 512} bits
     */
    int fixedCost(int bit, int f) {
        counter.startCounting();
        counter.encodeBoolFixed(bit, f);
        return (int) counter.cost();
    }

    /**
     * Returns the Lagrangian cost of a rate and an 8-bit-scale squared error.
     *
     * @param rate the rate in {@code 1 / 512} bits
     * @param sse the squared error
     * @return the cost
     */
    long rd(long rate, long sse) {
        return RdCost.rdcost(frame.rdmult, rate, sse << 4);
    }

    /**
     * Converts a squared error at the frame's bit depth to the 8-bit scale.
     *
     * @param sse the squared error
     * @return the scaled error
     */
    private long scale(long sse) {
        var shift = frame.distShift;
        return shift == 0 ? sse : (sse + (1L << (shift - 1))) >> shift;
    }

    /**
     * Returns whether a block has chroma samples coded with it.
     *
     * @param bs the block size
     * @return {@code true} when the block codes chroma
     */
    boolean hasChroma(int bs) {
        if (frame.layout == SequenceHeader.LAYOUT_I400) {
            return false;
        }
        var bw4 = BLOCK_DIMENSIONS[bs * 4];
        var bh4 = BLOCK_DIMENSIONS[bs * 4 + 1];
        return (bw4 > frame.ssHor || (t.bx & 1) != 0) && (bh4 > frame.ssVer || (t.by & 1) != 0);
    }

    /**
     * Builds {@link #REDUCED_INTRA1} from libaom's per-mode masks of transform types.
     *
     * @return the types of each mode, in symbol order
     */
    private static int[][] reducedIntra1() {
        int[] masks = {0x080F, 0x040F, 0x080F, 0x020F, 0x080F, 0x040F, 0x080F, 0x080F, 0x040F, 0x080F, 0x040F,
                0x080F, 0x0C0E};
        var sets = new int[masks.length][];
        for (var mode = 0; mode < masks.length; mode++) {
            var n = 0;
            var set = new int[SET_INTRA1.length];
            for (var type : SET_INTRA1) {
                if ((masks[mode] & (1 << type)) != 0) {
                    set[n++] = type;
                }
            }
            sets[mode] = Arrays.copyOf(set, n);
        }
        return sets;
    }

    /**
     * Returns the transform types the syntax can code for a luma transform size of an intra block.
     *
     * @param tx the transform size
     * @return the types, in symbol order
     */
    private int[] lumaTypeSet(int tx) {
        if (frame.quant.lossless() || Tables.TX_MAX[tx] + 1 >= 4) {
            return SET_DCT;
        }
        return Tables.TX_MIN[tx] == 2 ? SET_INTRA2 : SET_INTRA1;
    }

    /**
     * Returns the transform type the decoder implies for a chroma transform block of an intra
     * block.
     *
     * @param uvtx the chroma transform size
     * @param uvMode the chroma mode
     * @return the type
     */
    int chromaType(int uvtx, int uvMode) {
        if (frame.quant.lossless()) {
            return ItxDsp.WHT_WHT;
        }
        if (Tables.TX_MAX[uvtx] + 1 >= 4) {
            return 0;
        }
        return Tables.TXTP_FROM_UVMODE[uvMode];
    }

    /**
     * Returns the luma mode a filter intra mode stands for in the contexts of the transform type.
     *
     * @param yMode the luma mode
     * @param yAngle the angle delta or filter mode
     * @return the mode without filter intra
     */
    static int modeNoFilter(int yMode, int yAngle) {
        return yMode == IpredDsp.FILTER_PRED ? Tables.FILTER_MODE_TO_Y_MODE[yAngle] : yMode;
    }

    /**
     * Returns the sum of absolute Hadamard-transformed differences of a residual, in 8x8 blocks
     * where the residual allows and 4x4 blocks otherwise ({@code aom_hadamard_lp_8x8} and
     * {@code aom_hadamard_4x4} with {@code aom_satd}), scaled to twice the orthonormal transform
     * as the JM reference software's SATD is.
     *
     * @param res the residual, row-major
     * @param w the width, a multiple of 4
     * @param h the height, a multiple of 4
     * @return the sum
     */
    private long satd(int[] res, int w, int h) {
        long sum = 0;
        if ((w & 7) == 0 && (h & 7) == 0) {
            var kernels = txb.dsp;
            for (var by = 0; by < h; by += 8) {
                for (var bx = 0; bx < w; bx += 8) {
                    kernels.hadamard8x8(res, by * w + bx, w, hadamard, 0, hadamardScratch);
                    sum += kernels.satd(hadamard, 64) >> 2;
                }
            }
        } else {
            for (var by = 0; by < h; by += 4) {
                for (var bx = 0; bx < w; bx += 4) {
                    sum += hadamard4(res, by * w + bx, w) >> 1;
                }
            }
        }
        return sum;
    }

    /**
     * The Hadamard coefficients of the block being measured.
     */
    private final int[] hadamard = new int[64];

    /**
     * The Hadamard scratch.
     */
    private final int[] hadamardScratch = new int[64];

    /**
     * Returns the sum of absolute values of the 4x4 Hadamard transform of a residual block.
     *
     * @param res the residual
     * @param off the offset of the block
     * @param stride the residual stride
     * @return the sum
     */
    private int hadamard4(int[] res, int off, int stride) {
        var m = hadamardScratch;
        for (var i = 0; i < 4; i++) {
            var p = off + i * stride;
            var a0 = res[p] + res[p + 1];
            var a1 = res[p] - res[p + 1];
            var a2 = res[p + 2] + res[p + 3];
            var a3 = res[p + 2] - res[p + 3];
            m[i * 4] = a0 + a2;
            m[i * 4 + 1] = a1 + a3;
            m[i * 4 + 2] = a0 - a2;
            m[i * 4 + 3] = a1 - a3;
        }
        var sum = 0;
        for (var j = 0; j < 4; j++) {
            var a0 = m[j] + m[4 + j];
            var a1 = m[j] - m[4 + j];
            var a2 = m[8 + j] + m[12 + j];
            var a3 = m[8 + j] - m[12 + j];
            sum += Math.abs(a0 + a2) + Math.abs(a1 + a3) + Math.abs(a0 - a2) + Math.abs(a1 - a3);
        }
        return sum;
    }

    /**
     * Searches the coding of the block at the tile's current position, leaves it reconstructed
     * with its contexts updated, records it in a decision and returns its Lagrangian cost.
     *
     * @param bl the block level
     * @param bs the block size
     * @param bp the partition the block came from
     * @param edgeFlags the block's intra edge flags
     * @param d receives the decisions
     * @param budget the cost above which the search may give up
     * @return the cost, including every symbol of the block but its partition, or
     * {@link Long#MAX_VALUE} when it exceeds the budget; the block and its contexts are
     * then left in an unspecified state
     */
    long search(int bl, int bs, int bp, int edgeFlags, StillBlock d, long budget) {
        var f = frame.f;
        d.bs = bs;
        d.bp = bp;
        d.bl = bl;
        d.edgeFlags = edgeFlags;
        d.palSz[0] = 0;
        d.palSz[1] = 0;
        d.cflAlpha[0] = 0;
        d.cflAlpha[1] = 0;
        d.uvAngle = 0;
        d.uvMode = IpredDsp.DC_PRED;
        var bx4 = t.bx & 31;
        var by4 = t.by & 31;
        var bw4 = BLOCK_DIMENSIONS[bs * 4];
        var bh4 = BLOCK_DIMENSIONS[bs * 4 + 1];
        var w4 = Math.min(bw4, f.bw - t.bx);
        var h4 = Math.min(bh4, f.bh - t.by);
        saveLuma(bw4, bh4);
        if (searchLuma(bs, edgeFlags, d, w4, h4, bx4, by4, budget) == Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        var rate = passRate;
        var dist = passDist;
        var zero = passZero;
        var zeroFlags = passZeroFlags;
        var chroma = hasChroma(bs);
        if (chroma) {
            searchChroma(bs, edgeFlags, d);
            rate += passRate;
            dist += passDist;
            zero &= passZero;
            zeroFlags += passZeroFlags;
        }
        var sctx = t.a.skip[bx4] + t.l.skip[by4];
        d.skip = zero;
        rate += boolCost(Cdf.SKIP + sctx * 2, zero ? 1 : 0);
        if (zero) {
            rate -= zeroFlags;
        }
        updateContexts(d, chroma, bx4, by4, bw4, bh4);
        lastDist = dist;
        return rd(rate, dist);
    }

    /**
     * The squared error of the last searched block, at the 8-bit scale.
     */
    private long lastDist;

    /**
     * Returns the squared error of the last block {@link #search} coded, at the 8-bit scale.
     *
     * @return the squared error
     */
    long lastDist() {
        return lastDist;
    }

    /**
     * The cost of the all-zero flags of the transform blocks of the last pass, which a skipped
     * block does not code.
     */
    private long passZeroFlags;

    /**
     * Saves the luma samples and coefficient contexts of the block.
     *
     * @param bw4 the block width in 4-sample units
     * @param bh4 the block height in 4-sample units
     */
    private void saveLuma(int bw4, int bh4) {
        var cur = frame.f.cur;
        var stride = cur.stride[0];
        var off = 4 * (t.by * stride + t.bx);
        var w = bw4 * 4;
        for (var y = 0; y < bh4 * 4; y++) {
            System.arraycopy(cur.data[0], off + y * stride, savedY, y * w, w);
        }
        var base = t.bx & 16;
        System.arraycopy(t.a.lcoef, base, savedALcoef, base, 16);
        System.arraycopy(t.l.lcoef, 0, savedLLcoef, 0, 32);
    }

    /**
     * Restores the luma samples and coefficient contexts saved by {@link #saveLuma}.
     *
     * @param bw4 the block width in 4-sample units
     * @param bh4 the block height in 4-sample units
     */
    private void restoreLuma(int bw4, int bh4) {
        var cur = frame.f.cur;
        var stride = cur.stride[0];
        var off = 4 * (t.by * stride + t.bx);
        var w = bw4 * 4;
        for (var y = 0; y < bh4 * 4; y++) {
            System.arraycopy(savedY, y * w, cur.data[0], off + y * stride, w);
        }
        var base = t.bx & 16;
        System.arraycopy(savedALcoef, base, t.a.lcoef, base, 16);
        System.arraycopy(savedLLcoef, 0, t.l.lcoef, 0, 32);
    }

    /**
     * Searches the luma coding of the block and leaves the best one reconstructed.
     *
     * @param bs the block size
     * @param edgeFlags the block's intra edge flags
     * @param d receives the luma decisions
     * @param w4 the visible width in 4-sample units
     * @param h4 the visible height in 4-sample units
     * @param bx4 the column within the above context
     * @param by4 the row within the left context
     * @param budget the cost above which the search may give up
     * @return the cost of the chosen luma coding, or {@link Long#MAX_VALUE} when every candidate
     * exceeds the budget; the block is then left unreconstructed
     */
    private long searchLuma(int bs, int edgeFlags, StillBlock d, int w4, int h4, int bx4, int by4, long budget) {
        var settings = frame.settings;
        var bw4 = BLOCK_DIMENSIONS[bs * 4];
        var bh4 = BLOCK_DIMENSIONS[bs * 4 + 1];
        var bwl = BLOCK_DIMENSIONS[bs * 4 + 2];
        var bhl = BLOCK_DIMENSIONS[bs * 4 + 3];
        var lossless = frame.quant.lossless();
        var maxTx = Tables.MAX_TXFM_SIZE_FOR_BS[bs * 4];
        var aCtx = Tables.INTRA_MODE_CONTEXT[t.a.mode[bx4]];
        var lCtx = Tables.INTRA_MODE_CONTEXT[t.l.mode[by4]];
        var ymodeCdf = Cdf.KFYM + (aCtx * 5 + lCtx) * 16;
        var angles = bwl + bhl >= 2;
        var filterAllowed = frame.seq.filterIntra && Math.max(bwl, bhl) <= 3 && settings.filterIntra;
        var filterSyntax = frame.seq.filterIntra && Math.max(bwl, bhl) <= 3;
        var paletteSyntax = frame.hdr.allowScreenContentTools && Math.max(bw4, bh4) <= 16 && bw4 + bh4 >= 4;
        var paletteCtx = (t.a.palSz[bx4] > 0 ? 1 : 0) + (t.l.palSz[by4] > 0 ? 1 : 0);
        var szCtx = bwl + bhl - 2;
        var txCoded = !lossless && Tables.TX_MAX[maxTx] > 0
                && frame.hdr.txfmMode == FrameHeader.TX_SWITCHABLE;
        var depthMax = txCoded ? Math.min(Math.min(Tables.TX_MAX[maxTx], 2), settings.maxTxDepth) : 0;
        var tctx = (t.l.txIntra[by4] >= Tables.TX_LH[maxTx] ? 1 : 0)
                + (t.a.txIntra[bx4] >= Tables.TX_LW[maxTx] ? 1 : 0);
        var txCdf = Cdf.TXSZ + ((Math.max(Tables.TX_MAX[maxTx], 1) - 1) * 3 + tctx) * 4;
        var txSymbols = Math.min(Tables.TX_MAX[maxTx], 2);
        var hogMask = Float.isNaN(settings.hogThreshold) ? 0 : hogY.skipMask(frame.source.data[0],
                frame.source.stride[0], frame.width, frame.height, 4 * t.bx, 4 * t.by,
                Math.min(bh4 * 4, frame.height - 4 * t.by), Math.min(bw4 * 4, frame.width - 4 * t.bx), 1,
                settings.hogThreshold);
        if (settings.noSmoothHv) {
            hogMask |= (1 << IpredDsp.SMOOTH_V_PRED) | (1 << IpredDsp.SMOOTH_H_PRED);
        }
        var nCand = 0;
        for (var mode = 0; mode < IpredDsp.N_INTRA_PRED_MODES; mode++) {
            if ((hogMask & (1 << mode)) != 0) {
                continue;
            }
            candCost[nCand] = lumaModel(bs, edgeFlags, w4, h4, bx4, by4, mode, 0)
                    + modelRate(lumaModeRate(mode, 0, ymodeCdf, angles, paletteSyntax, filterSyntax,
                    bs, szCtx, paletteCtx));
            candMode[nCand] = mode;
            candAngle[nCand] = 0;
            nCand++;
        }
        sortCandidates(nCand, nCand);
        if (angles && settings.angleDeltas) {
            var base = nCand;
            var directional = 0;
            for (var i = 0; i < base && directional < settings.angleModes; i++) {
                var mode = candMode[i];
                if (mode < IpredDsp.VERT_PRED || mode > IpredDsp.VERT_LEFT_PRED) {
                    continue;
                }
                directional++;
                for (var delta = -3; delta <= 3; delta++) {
                    if (delta == 0) {
                        continue;
                    }
                    candCost[nCand] = lumaModel(bs, edgeFlags, w4, h4, bx4, by4, mode, delta)
                            + modelRate(lumaModeRate(mode, delta, ymodeCdf, angles, paletteSyntax,
                            filterSyntax, bs, szCtx, paletteCtx));
                    candMode[nCand] = mode;
                    candAngle[nCand] = delta;
                    nCand++;
                }
            }
        }
        var keep = Math.min(settings.lumaCandidates, nCand);
        sortCandidates(nCand, keep);
        var bestCost = Long.MAX_VALUE;
        var bestMode = IpredDsp.DC_PRED;
        var bestAngle = 0;
        var startDepth = Math.min(depthMax, settings.startTxDepth);
        var startTx = maxTx;
        for (var i = 0; i < startDepth; i++) {
            startTx = Tables.TX_SUB[startTx];
        }
        var startTxRate = txCoded ? symbolCost(txCdf, startDepth, txSymbols) : 0;
        for (var i = 0; i < keep; i++) {
            var mode = candMode[i];
            var delta = candAngle[i];
            var modeRate = lumaModeRate(mode, delta, ymodeCdf, angles, paletteSyntax, filterSyntax, bs, szCtx,
                    paletteCtx);
            var cost = lumaPass(mode, delta, lossless ? 0 : startTx, false, true, bs, edgeFlags,
                    Math.min(bestCost, budget), modeRate + startTxRate);
            restoreLuma(bw4, bh4);
            if (cost < bestCost) {
                bestCost = cost;
                bestMode = mode;
                bestAngle = delta;
            }
        }
        if (bestCost == Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        var bestTx = lossless ? 0 : startTx;
        if (depthMax > 0) {
            var modeRate = lumaModeRate(bestMode, bestAngle, ymodeCdf, angles, paletteSyntax, filterSyntax, bs, szCtx,
                    paletteCtx);
            var tx = maxTx;
            for (var depth = 0; depth <= depthMax; depth++, tx = Tables.TX_SUB[tx]) {
                if (depth == startDepth) {
                    continue;
                }
                var cost = lumaPass(bestMode, bestAngle, tx, false, true, bs, edgeFlags, bestCost,
                        modeRate + symbolCost(txCdf, depth, txSymbols));
                restoreLuma(bw4, bh4);
                if (cost < bestCost) {
                    bestCost = cost;
                    bestTx = tx;
                }
            }
        }
        if (filterAllowed) {
            var dcRate = (long) symbolCost(ymodeCdf, IpredDsp.DC_PRED, 12)
                    + (paletteSyntax ? boolCost(Cdf.PAL_Y + (szCtx * 3 + paletteCtx) * 2, 0) : 0)
                    + boolCost(Cdf.USE_FILTER_INTRA + bs * 2, 1);
            var txRate = txCoded ? symbolCost(txCdf, depthOf(maxTx, bestTx), txSymbols) : 0;
            var nFilter = 0;
            for (var fm = 0; fm < 5; fm++) {
                if (settings.filterDerived && fm != 0 && FILTER_FOR_MODE[bestMode] != fm) {
                    continue;
                }
                candCost[nFilter] = settings.filterCandidates >= 5 || settings.filterDerived ? 0
                        : lumaModel(bs, edgeFlags, w4, h4, bx4, by4, IpredDsp.FILTER_PRED, fm)
                        + modelRate(dcRate + symbolCost(Cdf.FILTER_INTRA, fm, 4));
                candMode[nFilter] = fm;
                candAngle[nFilter] = 0;
                nFilter++;
            }
            var keepFilter = settings.filterDerived ? nFilter : Math.min(settings.filterCandidates, nFilter);
            sortCandidates(nFilter, keepFilter);
            for (var i = 0; i < keepFilter; i++) {
                var fm = candMode[i];
                var rate = dcRate + symbolCost(Cdf.FILTER_INTRA, fm, 4) + txRate;
                var cost = lumaPass(IpredDsp.FILTER_PRED, fm, bestTx, false, true, bs, edgeFlags, bestCost, rate);
                restoreLuma(bw4, bh4);
                if (cost < bestCost) {
                    bestCost = cost;
                    bestMode = IpredDsp.FILTER_PRED;
                    bestAngle = fm;
                }
            }
        }
        var palette = paletteSyntax && settings.palette
                && lumaPalette(bs, edgeFlags, w4, h4, bx4, by4, bestTx, bestCost, ymodeCdf, szCtx, paletteCtx,
                txCoded ? symbolCost(txCdf, depthOf(maxTx, bestTx), txSymbols) : 0, d);
        if (palette) {
            System.arraycopy(d.pal, 0, t.pal, 0, 8);
            PaletteWriter.pack(packedY, d.palMapY, bw4 * 4, bw4 * 4, bh4 * 4, w4 * 4, h4 * 4);
            palettePass = true;
            var cost = lumaPass(IpredDsp.DC_PRED, 0, bestTx, settings.txTypeSearch, false, bs, edgeFlags,
                    Long.MAX_VALUE, paletteRateY + (txCoded ? symbolCost(txCdf, depthOf(maxTx, bestTx), txSymbols) : 0));
            palettePass = false;
            d.yMode = IpredDsp.DC_PRED;
            d.yAngle = 0;
            d.tx = bestTx;
            d.nTypes = 0;
            for (var i = 0; i < passTypeCount; i++) {
                d.addType(passTypes[i]);
            }
            return cost;
        }
        d.palSz[0] = 0;
        var finalRate = bestMode == IpredDsp.FILTER_PRED
                ? (long) symbolCost(ymodeCdf, IpredDsp.DC_PRED, 12)
                + (paletteSyntax ? boolCost(Cdf.PAL_Y + (szCtx * 3 + paletteCtx) * 2, 0) : 0)
                + boolCost(Cdf.USE_FILTER_INTRA + bs * 2, 1) + symbolCost(Cdf.FILTER_INTRA, bestAngle, 4)
                : lumaModeRate(bestMode, bestAngle, ymodeCdf, angles, paletteSyntax, filterSyntax, bs, szCtx,
                paletteCtx);
        if (txCoded) {
            finalRate += symbolCost(txCdf, depthOf(maxTx, bestTx), txSymbols);
        }
        var cost = lumaPass(bestMode, bestAngle, bestTx, settings.txTypeSearch, false, bs, edgeFlags, Long.MAX_VALUE,
                finalRate);
        d.yMode = bestMode;
        d.yAngle = bestAngle;
        d.tx = bestTx;
        d.nTypes = 0;
        for (var i = 0; i < passTypeCount; i++) {
            d.addType(passTypes[i]);
        }
        return cost;
    }

    /**
     * Returns the model cost of a luma mode: the Hadamard-transformed error of the prediction of
     * the block at its largest transform size, at the 8-bit scale.
     *
     * @param bs the block size
     * @param edgeFlags the block's intra edge flags
     * @param w4 the visible width in 4-sample units
     * @param h4 the visible height in 4-sample units
     * @param bx4 the column within the above context
     * @param by4 the row within the left context
     * @param mode the prediction mode
     * @param delta the angle delta
     * @return the model cost
     */
    private long lumaModel(int bs, int edgeFlags, int w4, int h4, int bx4, int by4, int mode, int delta) {
        var f = frame.f;
        var maxTx = Tables.MAX_TXFM_SIZE_FOR_BS[bs * 4];
        var cur = f.cur;
        var plane = cur.data[0];
        var stride = cur.stride[0];
        var dst = 4 * (t.by * stride + t.bx);
        var src = frame.source.data[0];
        var srcStride = frame.source.stride[0];
        var srcOff = 4 * (t.by * srcStride + t.bx);
        var intraEdgeFilter = frame.seq.intraEdgeFilter;
        var smooth = t.a.smFlag(bx4) | t.l.smFlag(by4)
                | (intraEdgeFilter ? IpredDsp.ANGLE_USE_EDGE_FILTER_FLAG : 0);
        var flags = edgeFlags & (IntraEdge.I444_TOP_HAS_RIGHT | IntraEdge.I444_LEFT_HAS_BOTTOM);
        var mtw = Tables.TX_W[maxTx];
        var mth = Tables.TX_H[maxTx];
        var angle = t.angle;
        long model = 0;
        for (var y = 0; y < h4; y += mth) {
            for (var x = 0; x < w4; x += mtw) {
                var bx = t.bx + x;
                var by = t.by + y;
                var o = dst + 4 * (y * stride + x);
                angle[0] = delta;
                var m = IpredDsp.prepareIntraEdges(bx, bx > ts.colStart, by, by > ts.rowStart, ts.colEnd,
                        ts.rowEnd, x == 0 && y == 0 ? flags : 0, plane, o, stride, null, 0, mode, angle, mtw,
                        mth, intraEdgeFilter, t.edge, IpredDsp.EDGE_TOPLEFT, frame.bdMax);
                IpredDsp.INSTANCE.intraPred(m, pred, 0, mtw * 4, t.edge, IpredDsp.EDGE_TOPLEFT, mtw * 4,
                        mth * 4, angle[0] | smooth, 4 * f.bw - 4 * bx, 4 * f.bh - 4 * by, t.ipredScratch,
                        frame.bdMax);
                var so = srcOff + 4 * (y * srcStride + x);
                var w = mtw * 4;
                for (var r = 0; r < mth * 4; r++) {
                    var sr = so + r * srcStride;
                    var pr = r * w;
                    for (var c = 0; c < w; c++) {
                        modelResidual[pr + c] = src[sr + c] - pred[pr + c];
                    }
                }
                model += satd(modelResidual, w, mth * 4);
            }
        }
        return frame.distShift == 0 ? model : model >> (frame.distShift >> 1);
    }

    /**
     * Returns the number of splits from a block's largest transform size to a transform size.
     *
     * @param maxTx the largest size
     * @param tx the size
     * @return the depth
     */
    private static int depthOf(int maxTx, int tx) {
        var depth = 0;
        for (var s = maxTx; s != tx && depth < 4; s = Tables.TX_SUB[s]) {
            depth++;
        }
        return depth;
    }

    /**
     * Returns the cost of the luma mode symbols of a non-filter, non-palette mode.
     *
     * @param mode the prediction mode
     * @param delta the angle delta
     * @param ymodeCdf the offset of the mode CDF
     * @param angles whether the block codes angle deltas
     * @param paletteSyntax whether the block codes the palette flag
     * @param filterSyntax whether the block codes the filter intra flag
     * @param bs the block size
     * @param szCtx the palette size context
     * @param paletteCtx the palette flag context
     * @return the cost
     */
    private long lumaModeRate(int mode, int delta, int ymodeCdf, boolean angles, boolean paletteSyntax,
                              boolean filterSyntax, int bs, int szCtx, int paletteCtx) {
        long rate = symbolCost(ymodeCdf, mode, 12);
        if (mode >= IpredDsp.VERT_PRED && mode <= IpredDsp.VERT_LEFT_PRED && angles) {
            rate += symbolCost(Cdf.ANGLE_DELTA + (mode - IpredDsp.VERT_PRED) * 8, delta + 3, 6);
        }
        if (mode == IpredDsp.DC_PRED && paletteSyntax) {
            rate += boolCost(Cdf.PAL_Y + (szCtx * 3 + paletteCtx) * 2, 0);
        }
        if (mode == IpredDsp.DC_PRED && filterSyntax) {
            rate += boolCost(Cdf.USE_FILTER_INTRA + bs * 2, 0);
        }
        return rate;
    }

    /**
     * Moves the {@code keep} cheapest candidates to the front, cheapest first.
     *
     * @param n the number of candidates
     * @param keep the number to select
     */
    private void sortCandidates(int n, int keep) {
        for (var i = 0; i < keep; i++) {
            var best = i;
            for (var j = i + 1; j < n; j++) {
                if (candCost[j] < candCost[best]) {
                    best = j;
                }
            }
            if (best != i) {
                var c = candCost[i];
                candCost[i] = candCost[best];
                candCost[best] = c;
                var m = candMode[i];
                candMode[i] = candMode[best];
                candMode[best] = m;
                var a = candAngle[i];
                candAngle[i] = candAngle[best];
                candAngle[best] = a;
            }
        }
    }

    /**
     * Predicts and codes the luma transform blocks of the block in the decoder's order and returns
     * the Lagrangian cost; the samples and coefficient contexts keep the result.
     *
     * <p>A measuring pass of a block coded as one transform block of at most 32 samples per side,
     * entirely inside the picture, measures the distortion in the transform domain and leaves the
     * prediction in the samples instead of the reconstruction.
     *
     * @param mode the luma mode, {@code FILTER_PRED} for filter intra
     * @param angleArg the angle delta, or the filter intra mode
     * @param tx the transform size
     * @param typeSearch whether each transform block searches its type; otherwise the mode's
     * default type is used
     * @param measure whether only the cost matters, the samples being restored afterwards
     * @param bs the block size
     * @param edgeFlags the block's intra edge flags
     * @param budget the cost above which the pass may stop early
     * @param modeRate the cost of the block's luma mode and transform size symbols
     * @return the cost, or {@link Long#MAX_VALUE} when the pass stopped early
     */
    long lumaPass(int mode, int angleArg, int tx, boolean typeSearch, boolean measure, int bs, int edgeFlags,
                  long budget, long modeRate) {
        var f = frame.f;
        var cur = f.cur;
        var plane = cur.data[0];
        var stride = cur.stride[0];
        var src = frame.source.data[0];
        var srcStride = frame.source.stride[0];
        var bx4 = t.bx & 31;
        var by4 = t.by & 31;
        var bw4 = BLOCK_DIMENSIONS[bs * 4];
        var bh4 = BLOCK_DIMENSIONS[bs * 4 + 1];
        var w4 = Math.min(bw4, f.bw - t.bx);
        var h4 = Math.min(bh4, f.bh - t.by);
        var txW = Tables.TX_W[tx];
        var txH = Tables.TX_H[tx];
        var intraEdgeFilter = frame.seq.intraEdgeFilter;
        var intraFlags = t.a.smFlag(bx4) | t.l.smFlag(by4)
                | (intraEdgeFilter ? IpredDsp.ANGLE_USE_EDGE_FILTER_FLAG : 0);
        var sbHasTr = edgeFlags & IntraEdge.I444_TOP_HAS_RIGHT;
        var sbHasBl = edgeFlags & IntraEdge.I444_LEFT_HAS_BOTTOM;
        var modeNoFilter = modeNoFilter(mode, angleArg);
        var types = typeSearch ? lumaTypeSet(tx) : null;
        if (types == SET_INTRA1 && frame.settings.reducedIntraTypes) {
            types = REDUCED_INTRA1[modeNoFilter];
        }
        var defaultType = lumaTypeSet(tx) == SET_DCT ? 0 : Tables.TXTP_FROM_UVMODE[modeNoFilter];
        var qidx = frame.hdr.quantYac;
        var reduced = frame.hdr.reducedTxtpSet;
        var angle = t.angle;
        long rate = modeRate;
        long dist = 0;
        var zero = true;
        long zeroFlags = 0;
        passTypeCount = 0;
        var estimate = measure && !typeSearch && !palettePass && frame.settings.txDomainTypes && !frame.quant.lossless()
                && txW >= w4 && txH >= h4 && txW <= 8 && txH <= 8 && 4 * (t.bx + txW) <= frame.width
                && 4 * (t.by + txH) <= frame.height;
        if (palettePass) {
            IpredDsp.INSTANCE.palPred(plane, 4 * (t.by * stride + t.bx), stride, t.pal, 0, packedY, 0, bw4 * 4,
                    bh4 * 4);
        }
        for (var y = 0; y < h4; y += txH) {
            for (var x = 0; x < w4; x += txW) {
                var bx = t.bx + x;
                var by = t.by + y;
                var dst = 4 * (by * stride + bx);
                if (!palettePass) {
                    angle[0] = angleArg;
                    var ef = (((y > 0 || sbHasTr == 0) && (x + txW >= w4)) ? 0 : IntraEdge.I444_TOP_HAS_RIGHT)
                            | ((x > 0 || (sbHasBl == 0 && y + txH >= h4)) ? 0 : IntraEdge.I444_LEFT_HAS_BOTTOM);
                    var m = IpredDsp.prepareIntraEdges(bx, bx > ts.colStart, by, by > ts.rowStart, ts.colEnd,
                            ts.rowEnd, ef, plane, dst, stride, null, 0, mode, angle, txW, txH, intraEdgeFilter, t.edge,
                            IpredDsp.EDGE_TOPLEFT, frame.bdMax);
                    IpredDsp.INSTANCE.intraPred(m, plane, dst, stride, t.edge, IpredDsp.EDGE_TOPLEFT, txW * 4,
                            txH * 4, angle[0] | intraFlags, 4 * f.bw - 4 * bx, 4 * f.bh - 4 * by, t.ipredScratch,
                            frame.bdMax);
                }
                var visW = Math.max(0, Math.min(txW * 4, frame.width - 4 * bx));
                var visH = Math.max(0, Math.min(txH * 4, frame.height - 4 * by));
                var so = 4 * (by * srcStride + bx);
                var aOff = bx4 + x;
                var lOff = by4 + y;
                int chosen;
                long txRate;
                long txDist;
                int ctx;
                if (types == null || types.length == 1) {
                    chosen = types == null ? defaultType : types[0];
                    txDist = estimate ? scale(txb.trial(0, tx, chosen, src, so, srcStride, plane, dst, stride))
                            : scale(txb.code(0, tx, chosen, src, so, srcStride, plane, dst, stride, visW, visH, true));
                    counter.startCounting();
                    ctx = coefWriter.write(counter, ts.cdf, t.a.lcoef, aOff, t.l.lcoef, lOff, tx, bs, 0, true,
                            modeNoFilter, reduced, qidx, chosen, txb.levels, 0, txb.eob, frame.ssHor, frame.ssVer);
                    txRate = counter.cost();
                } else {
                    chosen = 0;
                    var bestTx = Long.MAX_VALUE;
                    var trialDomain = frame.settings.txDomainTypes && !frame.quant.lossless() && visW == txW * 4
                            && visH == txH * 4;
                    txRate = 0;
                    txDist = 0;
                    var exact = frame.settings.typeExact;
                    if (exact > 0 && types.length > exact && trialDomain) {
                        chosen = pickType(types, exact, tx, bs, src, so, srcStride, plane, dst, stride, aOff, lOff,
                                modeNoFilter, reduced, qidx);
                    } else {
                        for (var type : types) {
                            var sse = trialDomain
                                    ? scale(txb.trial(0, tx, type, src, so, srcStride, plane, dst, stride))
                                    : scale(txb.code(0, tx, type, src, so, srcStride, plane, dst, stride, visW, visH,
                                    false));
                            counter.startCounting();
                            coefWriter.write(counter, ts.cdf, t.a.lcoef, aOff, t.l.lcoef, lOff, tx, bs, 0, true,
                                    modeNoFilter, reduced, qidx, type, txb.levels, 0, txb.eob, frame.ssHor,
                                    frame.ssVer);
                            var r = counter.cost();
                            var c = rd(r, sse);
                            if (c < bestTx) {
                                bestTx = c;
                                chosen = type;
                                txRate = r;
                                txDist = sse;
                            }
                        }
                    }
                    txDist = scale(txb.code(0, tx, chosen, src, so, srcStride, plane, dst, stride, visW, visH, true));
                    counter.startCounting();
                    ctx = coefWriter.write(counter, ts.cdf, t.a.lcoef, aOff, t.l.lcoef, lOff, tx, bs, 0, true,
                            modeNoFilter, reduced, qidx, chosen, txb.levels, 0, txb.eob, frame.ssHor, frame.ssVer);
                    txRate = counter.cost();
                }
                if (txb.eob != 0) {
                    zero = false;
                } else {
                    zeroFlags += txRate;
                }
                rate += txRate;
                dist += txDist;
                Arrays.fill(t.a.lcoef, aOff, aOff + (Math.min(txW, f.bw - bx)), (byte) ctx);
                Arrays.fill(t.l.lcoef, lOff, lOff + (Math.min(txH, f.bh - by)), (byte) ctx);
                if (passTypeCount == passTypes.length) {
                    passTypes = Arrays.copyOf(passTypes, passTypeCount * 2);
                }
                passTypes[passTypeCount++] = txb.eob == 0 ? 0 : chosen;
                if (rd(rate, dist) > budget) {
                    return Long.MAX_VALUE;
                }
            }
        }
        passRate = rate;
        passDist = dist;
        passZero = zero;
        passZeroFlags = zeroFlags;
        return rd(rate, dist);
    }

    /**
     * Picks the transform type of a luma transform block in two stages: every type is quantized
     * and ranked by its transform-domain distortion and a rate estimate from its levels, and only
     * the best ranked types are costed exactly.
     *
     * @param types the types to choose from
     * @param exact the number of best ranked types costed exactly
     * @param tx the transform size, at most 16 samples per side
     * @param bs the block size
     * @param src the source plane
     * @param so the offset of the transform block in the source
     * @param srcStride the source stride
     * @param plane the plane holding the prediction
     * @param dst the offset of the transform block in the plane
     * @param stride the plane stride
     * @param aOff the offset of the block in the above coefficient context
     * @param lOff the offset of the block in the left coefficient context
     * @param modeNoFilter the luma mode, filter intra mapped to its equivalent
     * @param reduced whether the frame uses the reduced transform sets
     * @param qidx the quantizer index
     * @return the chosen type
     */
    private int pickType(int[] types, int exact, int tx, int bs, short[] src, int so, int srcStride, short[] plane,
                         int dst, int stride, int aOff, int lOff, int modeNoFilter, boolean reduced, int qidx) {
        var n = Tables.TX_W[tx] * Tables.TX_H[tx] * 16;
        var count = types.length;
        for (var i = 0; i < count; i++) {
            var sse = scale(txb.trial(0, tx, types[i], src, so, srcStride, plane, dst, stride));
            typeSse[i] = sse;
            typeEob[i] = txb.eob;
            System.arraycopy(txb.levels, 0, typeLevels, i * 256, n);
            long rate = 0;
            var levels = txb.levels;
            for (var k = 0; k < n; k++) {
                var level = Math.abs(levels[k]);
                if (level != 0) {
                    rate += 1024 + (31 - Integer.numberOfLeadingZeros(level)) * 1024L;
                }
            }
            typeEst[i] = rd(rate + (txb.eob == 0 ? 0 : 2048), sse);
            typeOrder[i] = i;
        }
        for (var i = 0; i < exact; i++) {
            var best = i;
            for (var j = i + 1; j < count; j++) {
                if (typeEst[typeOrder[j]] < typeEst[typeOrder[best]]) {
                    best = j;
                }
            }
            var swap = typeOrder[i];
            typeOrder[i] = typeOrder[best];
            typeOrder[best] = swap;
        }
        var chosen = types[typeOrder[0]];
        var bestCost = Long.MAX_VALUE;
        for (var i = 0; i < exact; i++) {
            var index = typeOrder[i];
            System.arraycopy(typeLevels, index * 256, txb.levels, 0, n);
            counter.startCounting();
            coefWriter.write(counter, ts.cdf, t.a.lcoef, aOff, t.l.lcoef, lOff, tx, bs, 0, true, modeNoFilter, reduced,
                    qidx, types[index], txb.levels, 0, typeEob[index], frame.ssHor, frame.ssVer);
            var cost = rd(counter.cost(), typeSse[index]);
            if (cost < bestCost) {
                bestCost = cost;
                chosen = types[index];
            }
        }
        return chosen;
    }

    /**
     * The transform-domain distortion of each type of the two-stage type search.
     */
    private final long[] typeSse = new long[8];

    /**
     * The end of block of each type of the two-stage type search.
     */
    private final int[] typeEob = new int[8];

    /**
     * The estimated cost of each type of the two-stage type search.
     */
    private final long[] typeEst = new long[8];

    /**
     * The ranking of the types of the two-stage type search.
     */
    private final int[] typeOrder = new int[8];

    /**
     * The levels of each type of the two-stage type search, 256 per type.
     */
    private final int[] typeLevels = new int[8 * 256];

    /**
     * Searches the chroma coding of the block, after its luma coding, and leaves the best one
     * reconstructed; {@link #passRate}, {@link #passDist} and {@link #passZero} describe it.
     *
     * @param bs the block size
     * @param edgeFlags the block's intra edge flags
     * @param d receives the chroma decisions; its luma decisions are set
     */
    private void searchChroma(int bs, int edgeFlags, StillBlock d) {
        var f = frame.f;
        var settings = frame.settings;
        var layout = frame.layout;
        var ssHor = frame.ssHor;
        var ssVer = frame.ssVer;
        var bw4 = BLOCK_DIMENSIONS[bs * 4];
        var bh4 = BLOCK_DIMENSIONS[bs * 4 + 1];
        var bwl = BLOCK_DIMENSIONS[bs * 4 + 2];
        var bhl = BLOCK_DIMENSIONS[bs * 4 + 3];
        var cbw4 = (bw4 + ssHor) >> ssHor;
        var cbh4 = (bh4 + ssVer) >> ssVer;
        var lossless = frame.quant.lossless();
        var cflAllowed = Tables.cflAllowed(bs, lossless, cbw4, cbh4);
        var cfl = cflAllowed ? 1 : 0;
        var uvCdf = Cdf.UV_MODE + (cfl * 13 + (d.yMode == IpredDsp.FILTER_PRED ? IpredDsp.DC_PRED : d.yMode)) * 16;
        var uvSymbols = 13 - (cfl ^ 1);
        var angles = bwl + bhl >= 2;
        var paletteSyntax = frame.hdr.allowScreenContentTools && Math.max(bw4, bh4) <= 16 && bw4 + bh4 >= 4;
        saveChroma(bs);
        var hogMask = 0;
        if (angles && !settings.chromaLumaWinner && !Float.isNaN(settings.chromaHogThreshold)) {
            var cx = (t.bx & ~ssHor) * 4 >> ssHor;
            var cy = (t.by & ~ssVer) * 4 >> ssVer;
            var cStride = frame.source.stride[1];
            var cWidth = (frame.width + ssHor) >> ssHor;
            var cHeight = (frame.height + ssVer) >> ssVer;
            hogMask = hogUv.skipMask(frame.source.data[1], cStride, cWidth, cHeight, cx, cy,
                    Math.min(chromaH(bs), cHeight - cy), Math.min(chromaW(bs), cWidth - cx),
                    (1 + ssHor) * (1 + ssVer), settings.chromaHogThreshold);
        }
        if (settings.chromaLumaWinner) {
            var lumaMode = d.yMode == IpredDsp.FILTER_PRED ? IpredDsp.DC_PRED : d.yMode;
            hogMask = ~((1 << IpredDsp.DC_PRED) | (1 << IpredDsp.SMOOTH_PRED) | (1 << lumaMode));
        }
        var nCand = 0;
        for (var mode = 0; mode < IpredDsp.N_INTRA_PRED_MODES; mode++) {
            if ((hogMask & (1 << mode)) != 0) {
                continue;
            }
            candCost[nCand] = chromaModel(bs, edgeFlags, mode, 0)
                    + modelRate(chromaModeRate(uvCdf, uvSymbols, mode, 0, angles, paletteSyntax, d));
            candMode[nCand] = mode;
            candAngle[nCand] = 0;
            nCand++;
        }
        sortCandidates(nCand, nCand);
        if (angles && settings.angleDeltas) {
            var base = nCand;
            var directional = 0;
            for (var i = 0; i < base && directional < settings.chromaAngleModes; i++) {
                var mode = candMode[i];
                if (mode < IpredDsp.VERT_PRED || mode > IpredDsp.VERT_LEFT_PRED) {
                    continue;
                }
                directional++;
                for (var delta = -3; delta <= 3; delta++) {
                    if (delta == 0) {
                        continue;
                    }
                    candCost[nCand] = chromaModel(bs, edgeFlags, mode, delta) + modelRate(
                            chromaModeRate(uvCdf, uvSymbols, mode, delta, angles, paletteSyntax, d));
                    candMode[nCand] = mode;
                    candAngle[nCand] = delta;
                    nCand++;
                }
            }
        }
        var keep = Math.min(settings.chromaCandidates, nCand);
        sortCandidates(nCand, keep);
        var bestCost = Long.MAX_VALUE;
        var bestMode = IpredDsp.DC_PRED;
        var bestAngle = 0;
        var bestU = 0;
        var bestV = 0;
        for (var i = 0; i < keep; i++) {
            var mode = candMode[i];
            var delta = candAngle[i];
            long rate = symbolCost(uvCdf, mode, uvSymbols);
            if (mode >= IpredDsp.VERT_PRED && mode <= IpredDsp.VERT_LEFT_PRED && angles) {
                rate += symbolCost(Cdf.ANGLE_DELTA + (mode - IpredDsp.VERT_PRED) * 8, delta + 3, 6);
            }
            if (mode == IpredDsp.DC_PRED && paletteSyntax) {
                rate += boolCost(Cdf.PAL_UV + (d.palSz[0] > 0 ? 1 : 0) * 2, 0);
            }
            var cost = chromaPass(bs, edgeFlags, mode, delta, 0, 0, d.tx, true, bestCost, rate);
            restoreChroma(bs);
            if (cost < bestCost) {
                bestCost = cost;
                bestMode = mode;
                bestAngle = delta;
            }
        }
        if (cflAllowed) {
            var alphas = cflAlphas(bs, d.tx);
            var au = alphas >> 16;
            var av = (short) alphas;
            if (au != 0 || av != 0) {
                var rate = (long) symbolCost(uvCdf, IpredDsp.CFL_PRED, uvSymbols) + cflAlphaRate(au, av);
                var cost = chromaPass(bs, edgeFlags, IpredDsp.CFL_PRED, 0, au, av, d.tx, true, bestCost, rate);
                restoreChroma(bs);
                if (cost < bestCost) {
                    bestCost = cost;
                    bestMode = IpredDsp.CFL_PRED;
                    bestAngle = 0;
                    bestU = au;
                    bestV = av;
                }
            }
        }
        if (paletteSyntax && settings.palette && chromaPalette(bs, edgeFlags, uvCdf, uvSymbols, bestCost, d)) {
            System.arraycopy(d.pal, 8, t.pal, 8, 16);
            var cbw = chromaW(bs);
            var cbh = chromaH(bs);
            var visW = Math.min(cbw, ((frame.f.bw - (t.bx & ~frame.ssHor)) * 4 + frame.ssHor) >> frame.ssHor);
            var visH = Math.min(cbh, ((frame.f.bh - (t.by & ~frame.ssVer)) * 4 + frame.ssVer) >> frame.ssVer);
            PaletteWriter.pack(packedUv, d.palMapUv, cbw, cbw, cbh, visW, visH);
            palettePass = true;
            chromaPass(bs, edgeFlags, IpredDsp.DC_PRED, 0, 0, 0, d.tx, false, Long.MAX_VALUE, paletteRateUv);
            palettePass = false;
            d.uvMode = IpredDsp.DC_PRED;
            d.uvAngle = 0;
            d.cflAlpha[0] = 0;
            d.cflAlpha[1] = 0;
            return;
        }
        d.palSz[1] = 0;
        long finalRate;
        if (bestMode == IpredDsp.CFL_PRED) {
            finalRate = symbolCost(uvCdf, IpredDsp.CFL_PRED, uvSymbols) + cflAlphaRate(bestU, bestV);
        } else {
            finalRate = symbolCost(uvCdf, bestMode, uvSymbols);
            if (bestMode >= IpredDsp.VERT_PRED && bestMode <= IpredDsp.VERT_LEFT_PRED && angles) {
                finalRate += symbolCost(Cdf.ANGLE_DELTA + (bestMode - IpredDsp.VERT_PRED) * 8, bestAngle + 3, 6);
            }
            if (bestMode == IpredDsp.DC_PRED && paletteSyntax) {
                finalRate += boolCost(Cdf.PAL_UV + (d.palSz[0] > 0 ? 1 : 0) * 2, 0);
            }
        }
        chromaPass(bs, edgeFlags, bestMode, bestAngle, bestU, bestV, d.tx, false, Long.MAX_VALUE, finalRate);
        d.uvMode = bestMode;
        d.uvAngle = bestAngle;
        d.cflAlpha[0] = bestU;
        d.cflAlpha[1] = bestV;
    }

    /**
     * Returns the cost of the symbols of a chroma mode other than chroma-from-luma.
     *
     * @param uvCdf the offset of the chroma mode CDF
     * @param uvSymbols the number of chroma modes minus one
     * @param mode the prediction mode
     * @param delta the angle delta
     * @param angles whether the block codes angle deltas
     * @param paletteSyntax whether the block codes the palette flags
     * @param d the block's luma decisions
     * @return the cost
     */
    private long chromaModeRate(int uvCdf, int uvSymbols, int mode, int delta, boolean angles, boolean paletteSyntax,
                                StillBlock d) {
        long rate = symbolCost(uvCdf, mode, uvSymbols);
        if (mode >= IpredDsp.VERT_PRED && mode <= IpredDsp.VERT_LEFT_PRED && angles) {
            rate += symbolCost(Cdf.ANGLE_DELTA + (mode - IpredDsp.VERT_PRED) * 8, delta + 3, 6);
        }
        if (mode == IpredDsp.DC_PRED && paletteSyntax) {
            rate += boolCost(Cdf.PAL_UV + (d.palSz[0] > 0 ? 1 : 0) * 2, 0);
        }
        return rate;
    }

    /**
     * Returns the cost of the chroma-from-luma sign and scale symbols.
     *
     * @param au the U scale
     * @param av the V scale
     * @return the cost
     */
    private long cflAlphaRate(int au, int av) {
        var signU = au == 0 ? 0 : au < 0 ? 1 : 2;
        var signV = av == 0 ? 0 : av < 0 ? 1 : 2;
        var joint = signU * 3 + signV - 1;
        long rate = symbolCost(Cdf.CFL_SIGN, joint, 7);
        if (signU != 0) {
            var ctx = (signU == 2 ? 3 : 0) + signV;
            rate += symbolCost(Cdf.CFL_ALPHA + ctx * 16, Math.abs(au) - 1, 15);
        }
        if (signV != 0) {
            var ctx = (signV == 2 ? 3 : 0) + signU;
            rate += symbolCost(Cdf.CFL_ALPHA + ctx * 16, Math.abs(av) - 1, 15);
        }
        return rate;
    }

    /**
     * Saves the chroma samples and coefficient contexts of the block.
     *
     * @param bs the block size
     */
    private void saveChroma(int bs) {
        var cur = frame.f.cur;
        var stride = cur.stride[1];
        var cw = chromaW(bs);
        var ch = chromaH(bs);
        var off = chromaOrigin(stride);
        for (var pl = 0; pl < 2; pl++) {
            for (var y = 0; y < ch; y++) {
                System.arraycopy(cur.data[1 + pl], off + y * stride, savedUv, pl * 4096 + y * cw, cw);
            }
        }
        var base = (t.bx & 16) >> frame.ssHor;
        var length = 16 >> frame.ssHor;
        System.arraycopy(t.a.ccoef, base, savedACcoef, base, length);
        System.arraycopy(t.a.ccoef, 32 + base, savedACcoef, 32 + base, length);
        System.arraycopy(t.l.ccoef, 0, savedLCcoef, 0, 64);
    }

    /**
     * Restores the chroma samples and coefficient contexts saved by {@link #saveChroma}.
     *
     * @param bs the block size
     */
    private void restoreChroma(int bs) {
        var cur = frame.f.cur;
        var stride = cur.stride[1];
        var cw = chromaW(bs);
        var ch = chromaH(bs);
        var off = chromaOrigin(stride);
        for (var pl = 0; pl < 2; pl++) {
            for (var y = 0; y < ch; y++) {
                System.arraycopy(savedUv, pl * 4096 + y * cw, cur.data[1 + pl], off + y * stride, cw);
            }
        }
        var base = (t.bx & 16) >> frame.ssHor;
        var length = 16 >> frame.ssHor;
        System.arraycopy(savedACcoef, base, t.a.ccoef, base, length);
        System.arraycopy(savedACcoef, 32 + base, t.a.ccoef, 32 + base, length);
        System.arraycopy(savedLCcoef, 0, t.l.ccoef, 0, 64);
    }

    /**
     * Returns the chroma block width in samples.
     *
     * @param bs the block size
     * @return the width
     */
    private int chromaW(int bs) {
        return ((BLOCK_DIMENSIONS[bs * 4] + frame.ssHor) >> frame.ssHor) * 4;
    }

    /**
     * Returns the chroma block height in samples.
     *
     * @param bs the block size
     * @return the height
     */
    private int chromaH(int bs) {
        return ((BLOCK_DIMENSIONS[bs * 4 + 1] + frame.ssVer) >> frame.ssVer) * 4;
    }

    /**
     * Returns the offset of the chroma block's top-left sample.
     *
     * @param stride the chroma stride
     * @return the offset
     */
    private int chromaOrigin(int stride) {
        return 4 * ((t.by >> frame.ssVer) * stride + (t.bx >> frame.ssHor));
    }

    /**
     * Returns the model cost of a chroma mode: the Hadamard-transformed prediction error of both
     * chroma planes, the chroma block predicted whole.
     *
     * @param bs the block size
     * @param edgeFlags the block's intra edge flags
     * @param mode the chroma mode
     * @param delta the angle delta
     * @return the model cost
     */
    private long chromaModel(int bs, int edgeFlags, int mode, int delta) {
        var f = frame.f;
        var cur = f.cur;
        var layout = frame.layout;
        var ssHor = frame.ssHor;
        var ssVer = frame.ssVer;
        var stride = cur.stride[1];
        var srcStride = frame.source.stride[1];
        var uvtx = frame.quant.lossless() ? 0 : Tables.MAX_TXFM_SIZE_FOR_BS[bs * 4 + layout];
        var uvW = Tables.TX_W[uvtx];
        var uvH = Tables.TX_H[uvtx];
        var cw4 = chromaW(bs) >> 2;
        var ch4 = chromaH(bs) >> 2;
        var visCw4 = Math.min(cw4, ((f.bw - (t.bx & ~ssHor)) + ssHor) >> ssHor);
        var visCh4 = Math.min(ch4, ((f.bh - (t.by & ~ssVer)) + ssVer) >> ssVer);
        var smUvFl = t.a.smUvFlag((t.bx & 31) >> ssHor) | t.l.smUvFlag((t.by & 31) >> ssVer);
        var flags = ((edgeFlags & (IntraEdge.I420_TOP_HAS_RIGHT >> (layout - 1))) != 0 ? IntraEdge.I444_TOP_HAS_RIGHT : 0)
                | ((edgeFlags & (IntraEdge.I420_LEFT_HAS_BOTTOM >> (layout - 1))) != 0 ? IntraEdge.I444_LEFT_HAS_BOTTOM : 0);
        var filterFlag = frame.seq.intraEdgeFilter ? IpredDsp.ANGLE_USE_EDGE_FILTER_FLAG : 0;
        var angle = t.angle;
        long model = 0;
        for (var pl = 0; pl < 2; pl++) {
            var plane = cur.data[1 + pl];
            var src = frame.source.data[1 + pl];
            for (var y = 0; y < visCh4; y += uvH) {
                for (var x = 0; x < visCw4; x += uvW) {
                    var xpos = (t.bx >> ssHor) + x;
                    var ypos = (t.by >> ssVer) + y;
                    var dst = 4 * (ypos * stride + xpos);
                    angle[0] = delta;
                    var m = IpredDsp.prepareIntraEdges(xpos, xpos > (ts.colStart >> ssHor), ypos,
                            ypos > (ts.rowStart >> ssVer), ts.colEnd >> ssHor, ts.rowEnd >> ssVer,
                            x == 0 && y == 0 ? flags : 0, plane, dst, stride, null, 0, mode, angle, uvW, uvH,
                            frame.seq.intraEdgeFilter, t.edge, IpredDsp.EDGE_TOPLEFT, frame.bdMax);
                    IpredDsp.INSTANCE.intraPred(m, pred, 0, uvW * 4, t.edge, IpredDsp.EDGE_TOPLEFT, uvW * 4, uvH * 4,
                            (angle[0] | filterFlag) | smUvFl, ((4 * f.bw + ssHor) >> ssHor) - 4 * xpos,
                            ((4 * f.bh + ssVer) >> ssVer) - 4 * ypos, t.ipredScratch, frame.bdMax);
                    var so = 4 * (ypos * srcStride + xpos);
                    var w = uvW * 4;
                    for (var r = 0; r < uvH * 4; r++) {
                        for (var c = 0; c < w; c++) {
                            modelResidual[r * w + c] = src[so + r * srcStride + c] - pred[r * w + c];
                        }
                    }
                    model += satd(modelResidual, w, uvH * 4);
                }
            }
        }
        return frame.distShift == 0 ? model : model >> (frame.distShift >> 1);
    }

    /**
     * Chooses the chroma-from-luma scales of the block from the prediction error of each plane,
     * with the luma reconstruction in place.
     *
     * @param bs the block size
     * @param tx the luma transform size
     * @return the U scale in the high 16 bits and the V scale in the low 16 bits
     */
    private int cflAlphas(int bs, int tx) {
        var f = frame.f;
        var cur = f.cur;
        var layout = frame.layout;
        var ssHor = frame.ssHor;
        var ssVer = frame.ssVer;
        var bw4 = BLOCK_DIMENSIONS[bs * 4];
        var bh4 = BLOCK_DIMENSIONS[bs * 4 + 1];
        var w4 = Math.min(bw4, f.bw - t.bx);
        var h4 = Math.min(bh4, f.bh - t.by);
        var cw4 = (w4 + ssHor) >> ssHor;
        var ch4 = (h4 + ssVer) >> ssVer;
        var cbw4 = (bw4 + ssHor) >> ssHor;
        var cbh4 = (bh4 + ssVer) >> ssVer;
        var uvtx = frame.quant.lossless() ? 0 : Tables.MAX_TXFM_SIZE_FOR_BS[bs * 4 + layout];
        var uvW = Tables.TX_W[uvtx];
        var uvH = Tables.TX_H[uvtx];
        var stride = cur.stride[1];
        var tW = Tables.TX_W[tx];
        var tH = Tables.TX_H[tx];
        var ySrc = 4 * (t.bx & ~ssHor) + 4 * (t.by & ~ssVer) * cur.stride[0];
        var uvOff = 4 * ((t.bx >> ssHor) + (t.by >> ssVer) * stride);
        var furthestR = ((cw4 << ssHor) + tW - 1) & ~(tW - 1);
        var furthestB = ((ch4 << ssVer) + tH - 1) & ~(tH - 1);
        IpredDsp.INSTANCE.cflAc(layout, ac, cur.data[0], ySrc, cur.stride[0], cbw4 - (furthestR >> ssHor),
                cbh4 - (furthestB >> ssVer), cbw4 * 4, cbh4 * 4);
        var srcStride = frame.source.stride[1];
        var srcOff = 4 * ((t.by >> ssVer) * srcStride + (t.bx >> ssHor));
        var visW = Math.max(0, Math.min(uvW * 4, ((frame.width + ssHor) >> ssHor) - 4 * (t.bx >> ssHor)));
        var visH = Math.max(0, Math.min(uvH * 4, ((frame.height + ssVer) >> ssVer) - 4 * (t.by >> ssVer)));
        var result = 0;
        var angle = t.angle;
        for (var pl = 0; pl < 2; pl++) {
            var plane = cur.data[1 + pl];
            var src = frame.source.data[1 + pl];
            angle[0] = 0;
            var xpos = t.bx >> ssHor;
            var ypos = t.by >> ssVer;
            var m = IpredDsp.prepareIntraEdges(xpos, xpos > (ts.colStart >> ssHor), ypos, ypos > (ts.rowStart >> ssVer),
                    ts.colEnd >> ssHor, ts.rowEnd >> ssVer, 0, plane, uvOff, stride, null, 0, IpredDsp.DC_PRED, angle,
                    uvW, uvH, false, t.edge, IpredDsp.EDGE_TOPLEFT, frame.bdMax);
            var bestAlpha = 0;
            var bestSse = Long.MAX_VALUE;
            var w = uvW * 4;
            IpredDsp.INSTANCE.cflPred(m, pred, 0, w, t.edge, IpredDsp.EDGE_TOPLEFT, w, uvH * 4, ac, 0, frame.bdMax);
            long num = 0;
            long den = 0;
            for (var r = 0; r < visH; r++) {
                for (var c = 0; c < visW; c++) {
                    var a = ac[r * w + c];
                    num += (long) (src[srcOff + r * srcStride + c] - pred[r * w + c]) * a;
                    den += (long) a * a;
                }
            }
            var estimate = den == 0 ? 0 : (int) Math.clamp(Math.round(64.0 * num / den), -16, 16);
            for (var alpha = Math.max(-16, estimate - 1); alpha <= Math.min(16, estimate + 1); alpha++) {
                IpredDsp.INSTANCE.cflPred(m, pred, 0, w, t.edge, IpredDsp.EDGE_TOPLEFT, w, uvH * 4, ac, alpha,
                        frame.bdMax);
                long sse = 0;
                for (var r = 0; r < visH; r++) {
                    for (var c = 0; c < visW; c++) {
                        var diff = src[srcOff + r * srcStride + c] - pred[r * w + c];
                        sse += diff * diff;
                    }
                }
                if (sse < bestSse) {
                    bestSse = sse;
                    bestAlpha = alpha;
                }
            }
            result = pl == 0 ? bestAlpha << 16 : result | (bestAlpha & 0xFFFF);
        }
        return result;
    }

    /**
     * Predicts and codes the chroma transform blocks of the block in the decoder's order and
     * returns the Lagrangian cost; the samples and coefficient contexts keep the result.
     *
     * <p>A measuring pass of a block whose chroma is one transform block of at most 32 samples per
     * side, entirely inside the picture, measures the distortion in the transform domain and
     * leaves the prediction in the samples instead of the reconstruction.
     *
     * @param bs the block size
     * @param edgeFlags the block's intra edge flags
     * @param uvMode the chroma mode
     * @param uvAngle the angle delta
     * @param alphaU the chroma-from-luma U scale
     * @param alphaV the chroma-from-luma V scale
     * @param tx the luma transform size
     * @param measure whether only the cost matters, the samples being restored afterwards
     * @param budget the cost above which the pass may stop early
     * @param modeRate the cost of the chroma mode symbols
     * @return the cost, or {@link Long#MAX_VALUE} when the pass stopped early
     */
    long chromaPass(int bs, int edgeFlags, int uvMode, int uvAngle, int alphaU, int alphaV, int tx, boolean measure,
                    long budget, long modeRate) {
        var f = frame.f;
        var cur = f.cur;
        var layout = frame.layout;
        var ssHor = frame.ssHor;
        var ssVer = frame.ssVer;
        var bw4 = BLOCK_DIMENSIONS[bs * 4];
        var bh4 = BLOCK_DIMENSIONS[bs * 4 + 1];
        var w4 = Math.min(bw4, f.bw - t.bx);
        var h4 = Math.min(bh4, f.bh - t.by);
        var cw4 = (w4 + ssHor) >> ssHor;
        var ch4 = (h4 + ssVer) >> ssVer;
        var cbw4 = (bw4 + ssHor) >> ssHor;
        var cbh4 = (bh4 + ssVer) >> ssVer;
        var stride = cur.stride[1];
        var uvtx = frame.quant.lossless() ? 0 : Tables.MAX_TXFM_SIZE_FOR_BS[bs * 4 + layout];
        var uvW = Tables.TX_W[uvtx];
        var uvH = Tables.TX_H[uvtx];
        var angle = t.angle;
        var bdMax = frame.bdMax;
        if (uvMode == IpredDsp.CFL_PRED) {
            var tW = Tables.TX_W[tx];
            var tH = Tables.TX_H[tx];
            var ySrc = 4 * (t.bx & ~ssHor) + 4 * (t.by & ~ssVer) * cur.stride[0];
            var uvOff = 4 * ((t.bx >> ssHor) + (t.by >> ssVer) * stride);
            var furthestR = ((cw4 << ssHor) + tW - 1) & ~(tW - 1);
            var furthestB = ((ch4 << ssVer) + tH - 1) & ~(tH - 1);
            IpredDsp.INSTANCE.cflAc(layout, ac, cur.data[0], ySrc, cur.stride[0], cbw4 - (furthestR >> ssHor),
                    cbh4 - (furthestB >> ssVer), cbw4 * 4, cbh4 * 4);
            for (var pl = 0; pl < 2; pl++) {
                var alpha = pl == 0 ? alphaU : alphaV;
                if (alpha == 0) {
                    continue;
                }
                angle[0] = 0;
                var xpos = t.bx >> ssHor;
                var ypos = t.by >> ssVer;
                var m = IpredDsp.prepareIntraEdges(xpos, xpos > (ts.colStart >> ssHor), ypos,
                        ypos > (ts.rowStart >> ssVer), ts.colEnd >> ssHor, ts.rowEnd >> ssVer, 0, cur.data[1 + pl],
                        uvOff, stride, null, 0, IpredDsp.DC_PRED, angle, uvW, uvH, false, t.edge,
                        IpredDsp.EDGE_TOPLEFT, bdMax);
                IpredDsp.INSTANCE.cflPred(m, cur.data[1 + pl], uvOff, stride, t.edge, IpredDsp.EDGE_TOPLEFT, uvW * 4,
                        uvH * 4, ac, alpha, bdMax);
            }
        }
        var cbx4 = (t.bx & 31) >> ssHor;
        var cby4 = (t.by & 31) >> ssVer;
        var smUvFl = t.a.smUvFlag(cbx4) | t.l.smUvFlag(cby4);
        var uvSbHasTr = (16 >> ssHor) < cw4 ? 1 : edgeFlags & (IntraEdge.I420_TOP_HAS_RIGHT >> (layout - 1));
        var uvSbHasBl = (16 >> ssVer) < ch4 ? 1 : edgeFlags & (IntraEdge.I420_LEFT_HAS_BOTTOM >> (layout - 1));
        var subCw4 = Math.min(cw4, 16 >> ssHor);
        var subCh4 = Math.min(ch4, 16 >> ssVer);
        var intraEdgeFilterFlag = frame.seq.intraEdgeFilter ? IpredDsp.ANGLE_USE_EDGE_FILTER_FLAG : 0;
        var srcStride = frame.source.stride[1];
        var cWidth = (frame.width + ssHor) >> ssHor;
        var cHeight = (frame.height + ssVer) >> ssVer;
        var qidx = frame.hdr.quantYac;
        var reduced = frame.hdr.reducedTxtpSet;
        long rate = modeRate;
        long dist = 0;
        var zero = true;
        long zeroFlags = 0;
        var predicted = uvMode == IpredDsp.CFL_PRED;
        if (palettePass) {
            var uvDstOff = 4 * ((t.bx >> ssHor) + (t.by >> ssVer) * stride);
            IpredDsp.INSTANCE.palPred(cur.data[1], uvDstOff, stride, t.pal, 8, packedUv, 0, cbw4 * 4, cbh4 * 4);
            IpredDsp.INSTANCE.palPred(cur.data[2], uvDstOff, stride, t.pal, 16, packedUv, 0, cbw4 * 4, cbh4 * 4);
        }
        for (var pl = 0; pl < 2; pl++) {
            var plane = cur.data[1 + pl];
            var src = frame.source.data[1 + pl];
            var alpha = pl == 0 ? alphaU : alphaV;
            for (var y = 0; y < subCh4; y += uvH) {
                for (var x = 0; x < subCw4; x += uvW) {
                    var lbx = t.bx + (x << ssHor);
                    var lby = t.by + (y << ssVer);
                    var dst = 4 * ((lby >> ssVer) * stride + (lbx >> ssHor));
                    if (!(predicted && alpha != 0) && !palettePass) {
                        angle[0] = uvAngle;
                        var ef = (((y > 0 || uvSbHasTr == 0) && (x + uvW >= subCw4)) ? 0 : IntraEdge.I444_TOP_HAS_RIGHT)
                                | ((x > 0 || (uvSbHasBl == 0 && y + uvH >= subCh4)) ? 0 : IntraEdge.I444_LEFT_HAS_BOTTOM);
                        var mode = uvMode == IpredDsp.CFL_PRED ? IpredDsp.DC_PRED : uvMode;
                        var xpos = lbx >> ssHor;
                        var ypos = lby >> ssVer;
                        var m = IpredDsp.prepareIntraEdges(xpos, xpos > (ts.colStart >> ssHor), ypos,
                                ypos > (ts.rowStart >> ssVer), ts.colEnd >> ssHor, ts.rowEnd >> ssVer, ef, plane, dst,
                                stride, null, 0, mode, angle, uvW, uvH, frame.seq.intraEdgeFilter, t.edge,
                                IpredDsp.EDGE_TOPLEFT, bdMax);
                        angle[0] |= intraEdgeFilterFlag;
                        IpredDsp.INSTANCE.intraPred(m, plane, dst, stride, t.edge, IpredDsp.EDGE_TOPLEFT, uvW * 4,
                                uvH * 4, angle[0] | smUvFl, (4 * f.bw + ssHor - 4 * (lbx & ~ssHor)) >> ssHor,
                                (4 * f.bh + ssVer - 4 * (lby & ~ssVer)) >> ssVer, t.ipredScratch, bdMax);
                    }
                    var xpos = lbx >> ssHor;
                    var ypos = lby >> ssVer;
                    var visW = Math.max(0, Math.min(uvW * 4, cWidth - 4 * xpos));
                    var visH = Math.max(0, Math.min(uvH * 4, cHeight - 4 * ypos));
                    var so = 4 * (ypos * srcStride + xpos);
                    var txtp = chromaType(uvtx, uvMode);
                    var estimate = measure && !palettePass && frame.settings.txDomainTypes && !frame.quant.lossless()
                            && uvW >= subCw4 && uvH >= subCh4 && uvW <= 8 && uvH <= 8 && visW == uvW * 4
                            && visH == uvH * 4;
                    var sse = estimate ? scale(txb.trial(1 + pl, uvtx, txtp, src, so, srcStride, plane, dst, stride))
                            : scale(txb.code(1 + pl, uvtx, txtp, src, so, srcStride, plane, dst, stride, visW, visH,
                            true));
                    var cctx = pl * 32;
                    var aOff = cctx + cbx4 + x;
                    var lOff = cctx + cby4 + y;
                    counter.startCounting();
                    var ctx = coefWriter.write(counter, ts.cdf, t.a.ccoef, aOff, t.l.ccoef, lOff, uvtx, bs, 1 + pl,
                            true, 0, reduced, qidx, txtp, txb.levels, 0, txb.eob, ssHor, ssVer);
                    var r = counter.cost();
                    if (txb.eob != 0) {
                        zero = false;
                    } else {
                        zeroFlags += r;
                    }
                    rate += r;
                    dist += sse;
                    Arrays.fill(t.a.ccoef, aOff, aOff + (Math.min(uvW, (f.bw - lbx + ssHor) >> ssHor)), (byte) ctx);
                    Arrays.fill(t.l.ccoef, lOff, lOff + (Math.min(uvH, (f.bh - lby + ssVer) >> ssVer)), (byte) ctx);
                    if (rd(rate, dist) > budget) {
                        return Long.MAX_VALUE;
                    }
                }
            }
        }
        passRate = rate;
        passDist = dist;
        passZero = zero;
        passZeroFlags = zeroFlags;
        return rd(rate, dist);
    }

    /**
     * Updates the block contexts after the block as the decoder does: the transform size, mode,
     * palette, skip and chroma mode of every 4x4 position the block covers.
     *
     * @param d the block's decisions
     * @param chroma whether the block codes chroma
     * @param bx4 the column within the above context
     * @param by4 the row within the left context
     * @param bw4 the block width in 4-sample units
     * @param bh4 the block height in 4-sample units
     */
    void updateContexts(StillBlock d, boolean chroma, int bx4, int by4, int bw4, int bh4) {
        var yModeNofilt = d.yMode == IpredDsp.FILTER_PRED ? IpredDsp.DC_PRED : d.yMode;
        var palUv = chroma ? d.palSz[1] : 0;
        updateEdge(t.a, bx4, bw4, Tables.TX_LW[d.tx], yModeNofilt, d);
        updateEdge(t.l, by4, bh4, Tables.TX_LH[d.tx], yModeNofilt, d);
        Arrays.fill(t.palSzUv, bx4, bx4 + bw4, (byte) palUv);
        Arrays.fill(t.palSzUv, 32 + by4, 32 + by4 + bh4, (byte) palUv);
        if (d.palSz[0] != 0) {
            System.arraycopy(d.pal, 0, t.pal, 0, 8);
            EncoderBridge.copyPalBlockY(t, bx4, by4, bw4, bh4);
        }
        if (chroma) {
            var cbx4 = bx4 >> frame.ssHor;
            var cby4 = by4 >> frame.ssVer;
            var cbw4 = (bw4 + frame.ssHor) >> frame.ssHor;
            var cbh4 = (bh4 + frame.ssVer) >> frame.ssVer;
            Arrays.fill(t.a.uvmode, cbx4, cbx4 + cbw4, (byte) d.uvMode);
            Arrays.fill(t.l.uvmode, cby4, cby4 + cbh4, (byte) d.uvMode);
            if (d.palSz[1] != 0) {
                System.arraycopy(d.pal, 8, t.pal, 8, 16);
                EncoderBridge.copyPalBlockUv(t, bx4, by4, bw4, bh4);
            }
        }
    }

    /**
     * The cost of the luma mode, palette flag, colors and indices of the palette chosen by
     * {@link #lumaPalette}.
     */
    private long paletteRateY;

    /**
     * The cost of the chroma mode, palette flag, colors and indices of the palette chosen by
     * {@link #chromaPalette}.
     */
    private long paletteRateUv;

    /**
     * Searches a luma palette for the block: the distinct source values when few enough, or
     * k-means clusters of them, at each palette size from 8 down; keeps the best one in the
     * decision when it beats a cost.
     *
     * @param bs the block size
     * @param edgeFlags the block's intra edge flags
     * @param w4 the visible width in 4-sample units
     * @param h4 the visible height in 4-sample units
     * @param bx4 the column within the above context
     * @param by4 the row within the left context
     * @param tx the transform size
     * @param bestCost the cost to beat
     * @param ymodeCdf the offset of the luma mode CDF
     * @param szCtx the palette size context
     * @param paletteCtx the palette flag context
     * @param txRate the cost of the transform size symbol
     * @param d receives the palette when it wins
     * @return whether a palette beat the cost
     * @implNote This implementation follows libaom's {@code av1_rd_pick_palette_intra_sby}
     * ({@code av1/encoder/palette.c}): blocks with at most 64 distinct values, k-means
     * over the value histogram, and an early stop once two smaller palettes cost more.
     */
    private boolean lumaPalette(int bs, int edgeFlags, int w4, int h4, int bx4, int by4, int tx, long bestCost,
                                int ymodeCdf, int szCtx, int paletteCtx, long txRate, StillBlock d) {
        var bw = BLOCK_DIMENSIONS[bs * 4] * 4;
        var bh = BLOCK_DIMENSIONS[bs * 4 + 1] * 4;
        var visW = w4 * 4;
        var visH = h4 * 4;
        var src = frame.source.data[0];
        var srcStride = frame.source.stride[0];
        var srcOff = 4 * (t.by * srcStride + t.bx);
        var count = 0;
        for (var y = 0; y < visH && count <= 64; y++) {
            for (var x = 0; x < visW; x++) {
                var v = src[srcOff + y * srcStride + x];
                if (histogram[v]++ == 0) {
                    distinct[count++] = v;
                    if (count > 64) {
                        break;
                    }
                }
            }
        }
        var colors = count;
        if (colors > 64 || colors < 2) {
            clearHistogram(colors);
            return false;
        }
        Arrays.sort(distinct, 0, colors);
        var centroids = new int[8];
        var weights = new long[colors];
        for (var i = 0; i < colors; i++) {
            weights[i] = histogram[distinct[i]];
        }
        clearHistogram(colors);
        var won = false;
        var baseRate = (long) symbolCost(ymodeCdf, IpredDsp.DC_PRED, 12)
                + boolCost(Cdf.PAL_Y + (szCtx * 3 + paletteCtx) * 2, 1);
        var worse = 0;
        var lastCost = Long.MAX_VALUE;
        var palette = new short[8];
        for (var n = Math.min(colors, 8); n >= 2 && worse < 2; n--) {
            var k = kmeans1d(distinct, weights, colors, n, centroids);
            if (k < 2 || (k < n && n != Math.min(colors, 8))) {
                continue;
            }
            for (var i = 0; i < k; i++) {
                palette[i] = (short) centroids[i];
            }
            for (var y = 0; y < visH; y++) {
                for (var x = 0; x < visW; x++) {
                    mapY[y * bw + x] = (byte) nearest(palette, k, src[srcOff + y * srcStride + x]);
                }
            }
            counter.startCounting();
            paletteWriter.writeColors(counter, ts.cdf, t, 0, szCtx, bx4, by4, palette, 0, k, frame.bitDepth);
            paletteWriter.writeIndices(counter, ts.cdf, mapY, bw, k, 0, visW, visH);
            var rate = baseRate + counter.cost();
            System.arraycopy(palette, 0, t.pal, 0, k);
            PaletteWriter.pack(packedY, mapY, bw, bw, bh, visW, visH);
            palettePass = true;
            var cost = lumaPass(IpredDsp.DC_PRED, 0, tx, false, false, bs, edgeFlags, bestCost, rate + txRate);
            palettePass = false;
            restoreLuma(BLOCK_DIMENSIONS[bs * 4], BLOCK_DIMENSIONS[bs * 4 + 1]);
            if (cost < bestCost) {
                bestCost = cost;
                won = true;
                d.palSz[0] = k;
                System.arraycopy(palette, 0, d.pal, 0, k);
                d.palMapY = new byte[bw * bh];
                for (var y = 0; y < visH; y++) {
                    System.arraycopy(mapY, y * bw, d.palMapY, y * bw, visW);
                }
                paletteRateY = rate;
            }
            worse = cost >= lastCost ? worse + 1 : 0;
            lastCost = cost;
        }
        return won;
    }

    /**
     * Clears the histogram entries of the distinct values found.
     *
     * @param count the number of distinct values recorded
     */
    private void clearHistogram(int count) {
        for (var i = 0; i < Math.min(count, distinct.length); i++) {
            histogram[distinct[i]] = 0;
        }
    }

    /**
     * Clusters weighted values into at most {@code n} centroids with k-means, initialized at
     * evenly spaced weighted quantiles, and returns the distinct rounded centroids ascending.
     *
     * @param values the distinct values, ascending
     * @param weights the weight of each value
     * @param count the number of values
     * @param n the number of clusters
     * @param centroids receives the centroids
     * @return the number of distinct centroids
     */
    private static int kmeans1d(int[] values, long[] weights, int count, int n, int[] centroids) {
        if (count <= n) {
            System.arraycopy(values, 0, centroids, 0, count);
            return count;
        }
        long total = 0;
        for (var i = 0; i < count; i++) {
            total += weights[i];
        }
        var means = new double[n];
        long acc = 0;
        var c = 0;
        for (var i = 0; i < count && c < n; i++) {
            acc += weights[i];
            while (c < n && acc * n >= (2L * c + 1) * total / 2) {
                means[c++] = values[i];
            }
        }
        while (c < n) {
            means[c] = values[count - 1];
            c++;
        }
        var sums = new double[n];
        var counts = new long[n];
        for (var iter = 0; iter < 10; iter++) {
            Arrays.fill(sums, 0);
            Arrays.fill(counts, 0);
            for (var i = 0; i < count; i++) {
                var best = 0;
                var bestD = Double.MAX_VALUE;
                for (var j = 0; j < n; j++) {
                    var dd = Math.abs(values[i] - means[j]);
                    if (dd < bestD) {
                        bestD = dd;
                        best = j;
                    }
                }
                sums[best] += values[i] * (double) weights[i];
                counts[best] += weights[i];
            }
            for (var j = 0; j < n; j++) {
                if (counts[j] > 0) {
                    means[j] = sums[j] / counts[j];
                }
            }
        }
        var k = 0;
        var rounded = new int[n];
        for (var j = 0; j < n; j++) {
            rounded[j] = (int) Math.round(means[j]);
        }
        Arrays.sort(rounded);
        for (var j = 0; j < n; j++) {
            if (k == 0 || centroids[k - 1] != rounded[j]) {
                centroids[k++] = rounded[j];
            }
        }
        return k;
    }

    /**
     * Returns the index of the palette color nearest a value.
     *
     * @param palette the colors
     * @param n the number of colors
     * @param v the value
     * @return the index
     */
    private static int nearest(short[] palette, int n, int v) {
        var best = 0;
        var bestD = Integer.MAX_VALUE;
        for (var i = 0; i < n; i++) {
            var dd = Math.abs(palette[i] - v);
            if (dd < bestD) {
                bestD = dd;
                best = i;
            }
        }
        return best;
    }

    /**
     * Searches a chroma palette for the block: the distinct U and V pairs of the source when
     * few enough, or k-means clusters of them, at each palette size from 8 down; keeps the best
     * one in the decision when it beats a cost.
     *
     * @param bs the block size
     * @param edgeFlags the block's intra edge flags
     * @param uvCdf the offset of the chroma mode CDF
     * @param uvSymbols the number of chroma modes minus one
     * @param bestCost the cost to beat
     * @param d receives the palette when it wins; its luma decisions are set
     * @return whether a palette beat the cost
     * @implNote This implementation follows libaom's {@code av1_rd_pick_palette_intra_sbuv}
     * ({@code av1/encoder/palette.c}) with a two-dimensional k-means.
     */
    private boolean chromaPalette(int bs, int edgeFlags, int uvCdf, int uvSymbols, long bestCost, StillBlock d) {
        var f = frame.f;
        var cbw = chromaW(bs);
        var cbh = chromaH(bs);
        var visW = Math.min(cbw, ((f.bw - (t.bx & ~frame.ssHor)) * 4 + frame.ssHor) >> frame.ssHor);
        var visH = Math.min(cbh, ((f.bh - (t.by & ~frame.ssVer)) * 4 + frame.ssVer) >> frame.ssVer);
        var srcStride = frame.source.stride[1];
        var srcOff = 4 * ((t.by >> frame.ssVer) * srcStride + (t.bx >> frame.ssHor));
        var srcU = frame.source.data[1];
        var srcV = frame.source.data[2];
        var count = 0;
        var pairs = new HashMap<Integer, Integer>();
        for (var y = 0; y < visH && pairs.size() <= 64; y++) {
            for (var x = 0; x < visW; x++) {
                var key = srcU[srcOff + y * srcStride + x] << 16 | srcV[srcOff + y * srcStride + x];
                pairs.merge(key, 1, Integer::sum);
            }
        }
        count = pairs.size();
        if (count > 64 || count < 2) {
            return false;
        }
        var keys = new int[count];
        var weights = new long[count];
        var i0 = 0;
        for (var e : pairs.entrySet()) {
            keys[i0] = e.getKey();
            weights[i0++] = e.getValue();
        }
        var szCtx = BLOCK_DIMENSIONS[bs * 4 + 2] + BLOCK_DIMENSIONS[bs * 4 + 3] - 2;
        var bx4 = t.bx & 31;
        var by4 = t.by & 31;
        var baseRate = (long) symbolCost(uvCdf, IpredDsp.DC_PRED, uvSymbols)
                + boolCost(Cdf.PAL_UV + (d.palSz[0] > 0 ? 1 : 0) * 2, 1);
        var palette = new short[24];
        var won = false;
        var worse = 0;
        var lastCost = Long.MAX_VALUE;
        for (var n = Math.min(count, 8); n >= 2 && worse < 2; n--) {
            var k = kmeans2d(keys, weights, count, n, palette);
            if (k < 2) {
                continue;
            }
            for (var y = 0; y < visH; y++) {
                for (var x = 0; x < visW; x++) {
                    var u = srcU[srcOff + y * srcStride + x];
                    var v = srcV[srcOff + y * srcStride + x];
                    var best = 0;
                    var bestD = Long.MAX_VALUE;
                    for (var j = 0; j < k; j++) {
                        long du = u - palette[8 + j];
                        long dv = v - palette[16 + j];
                        var dd = du * du + dv * dv;
                        if (dd < bestD) {
                            bestD = dd;
                            best = j;
                        }
                    }
                    mapUv[y * cbw + x] = (byte) best;
                }
            }
            counter.startCounting();
            paletteWriter.writeColors(counter, ts.cdf, t, 1, szCtx, bx4, by4, palette, 8, k, frame.bitDepth);
            PaletteWriter.writeV(counter, palette, 16, k, frame.bitDepth);
            paletteWriter.writeIndices(counter, ts.cdf, mapUv, cbw, k, 1, visW, visH);
            var rate = baseRate + counter.cost();
            System.arraycopy(palette, 8, t.pal, 8, 16);
            PaletteWriter.pack(packedUv, mapUv, cbw, cbw, cbh, visW, visH);
            palettePass = true;
            var cost = chromaPass(bs, edgeFlags, IpredDsp.DC_PRED, 0, 0, 0, d.tx, false, bestCost, rate);
            palettePass = false;
            restoreChroma(bs);
            if (cost < bestCost) {
                bestCost = cost;
                won = true;
                d.palSz[1] = k;
                System.arraycopy(palette, 8, d.pal, 8, 16);
                d.palMapUv = new byte[cbw * cbh];
                for (var y = 0; y < visH; y++) {
                    System.arraycopy(mapUv, y * cbw, d.palMapUv, y * cbw, visW);
                }
                paletteRateUv = rate;
            }
            worse = cost >= lastCost ? worse + 1 : 0;
            lastCost = cost;
        }
        return won;
    }

    /**
     * Clusters weighted U and V pairs into at most {@code n} centroids with k-means and stores
     * the distinct rounded centroids ordered by U, then V, as a chroma palette.
     *
     * @param keys the pairs, U in the high 16 bits and V in the low ones
     * @param weights the weight of each pair
     * @param count the number of pairs
     * @param n the number of clusters
     * @param palette receives U at 8 to 15 and V at 16 to 23
     * @return the number of distinct centroids
     */
    private static int kmeans2d(int[] keys, long[] weights, int count, int n, short[] palette) {
        var mu = new double[n];
        var mv = new double[n];
        // Heaviest first, ties in index order: weights are at most 64 * 64, so the inverted weight in
        // the high half and the index in the low half sort as the stable descending order.
        var order = new long[count];
        for (var i = 0; i < count; i++) {
            order[i] = (long) (Integer.MAX_VALUE - weights[i]) << 32 | i;
        }
        Arrays.sort(order);
        for (var j = 0; j < n; j++) {
            var key = keys[(int) order[Math.min(j, count - 1)]];
            mu[j] = key >>> 16;
            mv[j] = key & 0xFFFF;
        }
        if (count > n) {
            var su = new double[n];
            var sv = new double[n];
            var cnt = new long[n];
            for (var iter = 0; iter < 10; iter++) {
                Arrays.fill(su, 0);
                Arrays.fill(sv, 0);
                Arrays.fill(cnt, 0);
                for (var i = 0; i < count; i++) {
                    var u = keys[i] >>> 16;
                    var v = keys[i] & 0xFFFF;
                    var best = 0;
                    var bestD = Double.MAX_VALUE;
                    for (var j = 0; j < n; j++) {
                        var du = u - mu[j];
                        var dv = v - mv[j];
                        var dd = du * du + dv * dv;
                        if (dd < bestD) {
                            bestD = dd;
                            best = j;
                        }
                    }
                    su[best] += u * (double) weights[i];
                    sv[best] += v * (double) weights[i];
                    cnt[best] += weights[i];
                }
                for (var j = 0; j < n; j++) {
                    if (cnt[j] > 0) {
                        mu[j] = su[j] / cnt[j];
                        mv[j] = sv[j] / cnt[j];
                    }
                }
            }
        }
        var packed = new long[n];
        for (var j = 0; j < n; j++) {
            packed[j] = Math.round(mu[j]) << 16 | Math.round(mv[j]);
        }
        Arrays.sort(packed);
        var k = 0;
        for (var j = 0; j < n; j++) {
            if (k > 0 && packed[j] == ((long) palette[8 + k - 1] << 16 | palette[16 + k - 1])) {
                continue;
            }
            palette[8 + k] = (short) (packed[j] >>> 16);
            palette[16 + k] = (short) (packed[j] & 0xFFFF);
            k++;
        }
        return k;
    }

    /**
     * Updates one edge of the contexts after an intra block.
     *
     * @param edge the above or left context
     * @param off the position within the context
     * @param n the number of entries
     * @param txLsz the base-2 logarithm of the transform size along the edge
     * @param yModeNofilt the luma mode with filter intra mapped to DC
     * @param d the block's decisions
     */
    private static void updateEdge(BlockContext edge, int off, int n, int txLsz, int yModeNofilt, StillBlock d) {
        Arrays.fill(edge.txIntra, off, off + n, (byte) txLsz);
        Arrays.fill(edge.tx, off, off + n, (byte) txLsz);
        Arrays.fill(edge.mode, off, off + n, (byte) yModeNofilt);
        Arrays.fill(edge.palSz, off, off + n, (byte) d.palSz[0]);
        Arrays.fill(edge.segPred, off, off + n, (byte) 0);
        Arrays.fill(edge.skipMode, off, off + n, (byte) 0);
        Arrays.fill(edge.intra, off, off + n, (byte) 1);
        Arrays.fill(edge.skip, off, off + n, (byte) (d.skip ? 1 : 0));
    }
}
