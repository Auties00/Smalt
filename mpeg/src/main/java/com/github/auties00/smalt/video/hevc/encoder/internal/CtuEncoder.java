package com.github.auties00.smalt.video.hevc.encoder.internal;

import com.github.auties00.smalt.video.hevc.encoder.HevcEncoderSpeed;
import com.github.auties00.smalt.video.hevc.internal.CabacTables;
import com.github.auties00.smalt.video.hevc.internal.HevcDsp;
import com.github.auties00.smalt.video.hevc.internal.HevcPps;
import com.github.auties00.smalt.video.hevc.internal.HevcSliceHeader;
import com.github.auties00.smalt.video.hevc.internal.HevcSps;
import com.github.auties00.smalt.video.hevc.internal.HevcTransform;
import com.github.auties00.smalt.video.hevc.internal.InterPredictor;
import com.github.auties00.smalt.video.hevc.internal.IntraPredictor;
import com.github.auties00.smalt.video.hevc.internal.MotionPredictor;
import com.github.auties00.smalt.video.hevc.internal.NeighborAvailability;
import com.github.auties00.smalt.video.hevc.internal.PictureState;

import java.util.Arrays;

/**
 * Decides and reconstructs the coding tree units of a picture: the rate-distortion choice of the coding
 * quadtree, of intra prediction modes, merge and skip candidates and motion vectors, with the transform and
 * quantization of every residual.
 *
 * <p>Each candidate is reconstructed in place in the picture with the decoder's prediction, inverse
 * transform and motion vector derivation, and costed as its distortion plus lambda times the bits its
 * syntax takes under the context states the coding tree unit starts with; the best candidate of each depth
 * is kept in a {@link RegionStore}. After a coding tree unit is decided its loop filter information is
 * recorded. Instances are not thread-safe; each wavefront row worker owns one.
 */
final class CtuEncoder implements NeighborAvailability {
    /**
     * The largest number of merge candidates.
     */
    private static final int MERGE_CANDIDATES = 5;

    /**
     * The settings.
     */
    private final EncoderSettings s;

    /**
     * The syntax coder the costs are estimated with.
     */
    private final SyntaxCoder syntax = new SyntaxCoder();

    /**
     * The counter of the cost estimates.
     */
    private final CabacCounter counter = new CabacCounter();

    /**
     * The context states at the start of the coding tree unit being decided.
     */
    private final byte[] ctuStates = new byte[CabacTables.COUNT];

    /**
     * The best candidates of each depth.
     */
    private final RegionStore store = new RegionStore();

    /**
     * The intra predictor.
     */
    private final IntraPredictor intra = new IntraPredictor();

    /**
     * The inverse transform.
     */
    private final HevcTransform inverse = new HevcTransform();

    /**
     * The forward transform.
     */
    private final ForwardTransform forward = new ForwardTransform();

    /**
     * The merge and motion vector predictor derivation.
     */
    private final MotionPredictor motion = new MotionPredictor();

    /**
     * The motion compensation.
     */
    private final InterPredictor inter = new InterPredictor();

    /**
     * The motion of the prediction unit being decided.
     */
    private final MotionPredictor.PuMotion pu = new MotionPredictor.PuMotion();

    /**
     * The motion record the merge candidates and motion vector predictors are derived into.
     */
    private final MotionPredictor.PuMotion probe = new MotionPredictor.PuMotion();

    /**
     * {@code QpC} of Table 8-10 for {@code qPi} 30 to 43.
     */
    private static final int[] QPC_TABLE = {29, 30, 31, 32, 33, 33, 34, 34, 35, 35, 36, 36, 37, 37};

    /**
     * The motion search.
     */
    private MotionSearch search;

    /**
     * The picture.
     */
    private PictureData d;

    /**
     * The sequence parameter set.
     */
    private HevcSps sps;

    /**
     * The picture parameter set.
     */
    private HevcPps pps;

    /**
     * The slice segment header.
     */
    private HevcSliceHeader header;

    /**
     * The coding information.
     */
    private PictureState pic;

    /**
     * The padded reference luma planes, indexed {@code [list][refIdx]}.
     */
    private short[][][] refPlanes;

    /**
     * {@code SliceQpY}.
     */
    private int qpY;

    /**
     * {@code qP} of luma, including the bit depth offset.
     */
    private int qpLuma;

    /**
     * {@code qP} of chroma, including the bit depth offset.
     */
    private int qpChroma;

    /**
     * The Lagrange multiplier of squared errors.
     */
    private double lambda;

    /**
     * The Lagrange multiplier of absolute and Hadamard differences, {@code sqrt(lambda)}.
     */
    private double sqrtLambda;

    /**
     * The weight of chroma squared errors against luma ones.
     */
    private double chromaWeight;

    /**
     * Whether the slice is intra.
     */
    private boolean intraSlice;

    /**
     * Whether the slice is bi-predictive.
     */
    private boolean bSlice;

    /**
     * The number of reference pictures searched per list.
     */
    private int searchRefs;

    /**
     * The neighboring samples of an intra block.
     */
    private final int[] boundary = new int[4 * 64 + 1];

    /**
     * The availability of {@link #boundary}.
     */
    private final boolean[] boundaryAvailable = new boolean[4 * 64 + 1];

    /**
     * The prediction of a transform block.
     */
    private final short[] pred = new short[32 * 32];

    /**
     * A residual block.
     */
    private final int[] residual = new int[32 * 32];

    /**
     * A coefficient block.
     */
    private final int[] coeffs = new int[32 * 32];

    /**
     * The rounding errors of the quantization of a block, for sign data hiding.
     */
    private final int[] deltaU = new int[32 * 32];

    /**
     * The SATD scratch.
     */
    private final int[] satdScratch = new int[64];

    /**
     * The rough costs of the intra modes.
     */
    private final double[] modeCost = new double[35];

    /**
     * The intra modes in order of rough cost.
     */
    private final int[] modeOrder = new int[35];

    /**
     * The most probable modes of a prediction block.
     */
    private final int[] mpm = new int[3];

    /**
     * The merge candidates: {@code predFlagL0 | predFlagL1 << 1}.
     */
    private final int[] candDir = new int[MERGE_CANDIDATES];

    /**
     * The merge candidates' reference indices.
     */
    private final int[][] candRef = new int[2][MERGE_CANDIDATES];

    /**
     * The merge candidates' motion vectors, packed.
     */
    private final int[][] candMv = new int[2][MERGE_CANDIDATES];

    /**
     * The merge candidates' rough costs.
     */
    private final double[] candCost = new double[MERGE_CANDIDATES];

    /**
     * The start candidates of the motion search.
     */
    private final int[] starts = new int[16];

    /**
     * The result of the motion search.
     */
    private final int[] found = new int[2];

    /**
     * The intermediate prediction of list 0 for the bi-prediction estimate.
     */
    private final short[] biPred0 = new short[64 * 64];

    /**
     * The intermediate prediction of list 1 for the bi-prediction estimate.
     */
    private final short[] biPred1 = new short[64 * 64];

    /**
     * A uni-directional luma prediction for the merge estimates.
     */
    private final short[] uniPred = new short[64 * 64];

    /**
     * The best motion vector of each list, packed.
     */
    private final int[] bestMv = new int[2];

    /**
     * The reference index of {@link #bestMv}.
     */
    private final int[] bestRef = new int[2];

    /**
     * The predictor flag of {@link #bestMv}.
     */
    private final int[] bestMvp = new int[2];

    /**
     * The rough cost of {@link #bestMv}.
     */
    private final double[] bestCost = new double[2];

    /**
     * The approximate motion bits of {@link #bestMv}.
     */
    private final int[] bestBits = new int[2];

    /**
     * The motion vector differences of the chosen motion, packed.
     */
    private final int[] mvd = new int[2];

    /**
     * The prediction of a coding unit while its residual choices are tried: the luma block, then the two
     * chroma blocks.
     */
    private final short[] savedPrediction = new short[64 * 64 + 2 * 32 * 32];

    /**
     * Constructs an encoder.
     *
     * @param s the settings
     */
    CtuEncoder(EncoderSettings s) {
        this.s = s;
    }

    /**
     * Prepares the encoder for a picture.
     *
     * @param data the picture, whose header and reference lists are set
     * @param sliceIndex the index of the slice in the frame's slice tables
     * @param refPlanes the padded luma planes of the references, indexed {@code [list][refIdx]}, or
     * {@code null} for an intra slice
     * @param lambdaFactor the factor of the Lagrange multiplier of the picture type
     */
    void setPicture(PictureData data, int sliceIndex, short[][][] refPlanes, double lambdaFactor) {
        this.d = data;
        this.header = data.header;
        this.sps = header.sps;
        this.pps = header.pps;
        this.pic = data.pic;
        this.refPlanes = refPlanes;
        this.qpY = header.sliceQp;
        var bdOffset = 6 * (s.bitDepth - 8);
        this.qpLuma = qpY + bdOffset;
        var qPi = Math.clamp(qpY + pps.cbQpOffset + header.cbQpOffset, -bdOffset, 57);
        var qpC = qPi < 30 ? qPi : qPi > 43 ? qPi - 6 : QPC_TABLE[qPi - 30];
        this.qpChroma = qpC + bdOffset;
        this.lambda = lambdaFactor * StrictMath.pow(2.0, (qpY + bdOffset - 12) / 3.0);
        this.sqrtLambda = Math.sqrt(lambda);
        this.chromaWeight = StrictMath.pow(2.0, (qpY - qpC) / 3.0);
        this.intraSlice = header.sliceType == HevcSliceHeader.I;
        this.bSlice = header.sliceType == HevcSliceHeader.B;
        this.searchRefs = switch (s.speed) {
            case FASTEST, FAST -> 1;
            case BALANCED -> 2;
            case SLOW -> 3;
        };
        syntax.setPicture(data);
        if (search == null) {
            search = new MotionSearch(pic.dsp);
        }
        search.configure(sqrtLambda, s.bitDepth);
        if (!intraSlice) {
            motion.setSlice(data.frame, pic, header, data.refList, sliceIndex);
            inter.setSlice(header, data.refList, pic.dsp);
        }
    }

    @Override
    public boolean available(int xCurr, int yCurr, int xNb, int yNb) {
        return syntax.available(xCurr, yCurr, xNb, yNb);
    }

    /**
     * Decides and reconstructs one coding tree unit, then advances a counter over its syntax.
     *
     * @param ctbAddr the raster address of the coding tree block
     * @param row the counter that follows the context states of the coding tree block's row
     */
    void encode(int ctbAddr, CabacCounter row) {
        row.storeStates(ctuStates, 0);
        var x0 = (ctbAddr % sps.widthInCtbs) << sps.log2CtbSize;
        var y0 = (ctbAddr / sps.widthInCtbs) << sps.log2CtbSize;
        compress(x0, y0, sps.log2CtbSize, 0);
        finish(x0, y0);
        syntax.setCoder(row);
        syntax.codingTreeUnit(ctbAddr, false);
    }

    /**
     * Decides a coding quadtree node and leaves the best choice reconstructed in place.
     *
     * @param x0 the luma x
     * @param y0 the luma y
     * @param log2Size the node size as a power of two
     * @param depth the coding tree depth
     * @return the rate-distortion cost of the node
     */
    private double compress(int x0, int y0, int log2Size, int depth) {
        var size = 1 << log2Size;
        var inside = x0 + size <= s.codedWidth && y0 + size <= s.codedHeight;
        var canSplit = log2Size > sps.log2MinCbSize;
        if (!inside) {
            return split(x0, y0, log2Size, depth, false);
        }
        var best = Double.MAX_VALUE;
        var skipped = false;
        var largest = s.speed == HevcEncoderSpeed.FASTEST ? 5 : 6;
        if (log2Size <= largest || !canSplit) {
            if (!intraSlice) {
                best = interCu(x0, y0, log2Size, depth);
                store.save(d, depth, x0, y0, size);
                skipped = (pic.flags[d.block(x0, y0)] & PictureState.SKIP) != 0;
                if (!skipped && s.speed.compareTo(HevcEncoderSpeed.BALANCED) >= 0) {
                    for (var partMode = SyntaxCoder.PART_2NxN; partMode <= SyntaxCoder.PART_Nx2N; partMode++) {
                        var cost = partitionedCu(x0, y0, log2Size, depth, partMode);
                        if (cost < best) {
                            best = cost;
                            store.save(d, depth, x0, y0, size);
                        }
                    }
                }
            }
            var tryIntra = log2Size <= 5 && (intraSlice || !skipped || s.speed == HevcEncoderSpeed.SLOW);
            if (tryIntra) {
                var cost = intraCu(x0, y0, log2Size, depth);
                if (cost < best) {
                    best = cost;
                    store.save(d, depth, x0, y0, size);
                }
                if (log2Size == sps.log2MinCbSize && (intraSlice || s.speed.compareTo(HevcEncoderSpeed.BALANCED) >= 0)) {
                    cost = intraNxN(x0, y0, depth);
                    if (cost < best) {
                        best = cost;
                        store.save(d, depth, x0, y0, size);
                    }
                }
            }
        }
        if (canSplit && !(skipped && s.speed != HevcEncoderSpeed.SLOW)) {
            var cost = split(x0, y0, log2Size, depth, true);
            if (cost < best) {
                return cost;
            }
        }
        store.restore(d, depth, x0, y0, size);
        return best;
    }

    /**
     * Decides the four quadrants of a coding quadtree node.
     *
     * @param x0 the luma x
     * @param y0 the luma y
     * @param log2Size the node size as a power of two
     * @param depth the coding tree depth of the node
     * @param coded whether {@code split_cu_flag} is coded
     * @return the cost of the split, with the split flag
     */
    private double split(int x0, int y0, int log2Size, int depth, boolean coded) {
        var half = 1 << (log2Size - 1);
        var cost = coded ? lambda * splitBits(x0, y0, depth, 1) : 0;
        for (var i = 0; i < 4; i++) {
            var x = x0 + (i & 1) * half;
            var y = y0 + (i >> 1) * half;
            if (x < s.codedWidth && y < s.codedHeight) {
                cost += compress(x, y, log2Size - 1, depth + 1);
            }
        }
        return cost;
    }

    /**
     * Returns the bits of a {@code split_cu_flag}.
     *
     * @param x0 the luma x of the node
     * @param y0 the luma y of the node
     * @param depth the coding tree depth
     * @param bin the flag
     * @return the bits
     */
    private double splitBits(int x0, int y0, int depth, int bin) {
        counter.loadStates(ctuStates, 0);
        return counter.cost(CabacTables.SPLIT_CU + syntax.splitContext(x0, y0, depth), bin)
                / (double) CabacCounter.ONE_BIT;
    }

    /**
     * Returns the cost of a decided coding unit: its distortion plus lambda times the bits of its syntax and
     * of the split flag that ends the quadtree at it.
     *
     * @param x0 the luma x
     * @param y0 the luma y
     * @param log2Size the coding block size as a power of two
     * @param depth the coding tree depth
     * @return the cost
     */
    private double cuCost(int x0, int y0, int log2Size, int depth) {
        var size = 1 << log2Size;
        counter.loadStates(ctuStates, 0);
        counter.bits = 0;
        if (log2Size > sps.log2MinCbSize) {
            counter.bin(CabacTables.SPLIT_CU + syntax.splitContext(x0, y0, depth), 0);
        }
        syntax.setCoder(counter);
        syntax.codingUnit(x0, y0, log2Size);
        var bits = counter.bits / (double) CabacCounter.ONE_BIT;
        return distortion(x0, y0, size) + lambda * bits;
    }

    /**
     * Returns the weighted squared error of the reconstruction of a square area against the source.
     *
     * @param x0 the luma x
     * @param y0 the luma y
     * @param size the side in luma samples
     * @return the distortion
     */
    private double distortion(int x0, int y0, int size) {
        var w = s.codedWidth;
        var luma = Distortion.sse(d.sourceY, y0 * w + x0, w, d.frame.y, y0 * w + x0, w, size, size);
        var cw = s.chromaWidth;
        var offset = (y0 >> 1) * cw + (x0 >> 1);
        var half = size >> 1;
        var chroma = Distortion.sse(d.sourceCb, offset, cw, d.frame.cb, offset, cw, half, half)
                + Distortion.sse(d.sourceCr, offset, cw, d.frame.cr, offset, cw, half, half);
        return luma + chromaWeight * chroma;
    }

    /**
     * Writes the decisions shared by every candidate of a coding unit over its area.
     *
     * @param x0 the luma x
     * @param y0 the luma y
     * @param size the coding block size
     * @param depth the coding tree depth
     * @param flags the {@link PictureState} flags
     * @param partMode the partitioning mode
     * @param trafoDepth the transform tree depth of every leaf
     */
    private void markCu(int x0, int y0, int size, int depth, int flags, int partMode, int trafoDepth) {
        var w4 = pic.width4;
        var start = d.block(x0, y0);
        var blocks = size >> 2;
        for (var j = 0; j < blocks; j++) {
            var row = start + j * w4;
            Arrays.fill(pic.flags, row, row + blocks, (byte) flags);
            Arrays.fill(pic.depth, row, row + blocks, (byte) depth);
            Arrays.fill(d.cuInfo, row, row + blocks, (byte) partMode);
            Arrays.fill(d.tuInfo, row, row + blocks, (byte) trafoDepth);
            Arrays.fill(d.puInfo, row, row + blocks, 0);
            Arrays.fill(d.mvd0, row, row + blocks, 0);
            Arrays.fill(d.mvd1, row, row + blocks, 0);
            if ((flags & PictureState.INTRA) != 0) {
                Arrays.fill(d.frame.ref0, row, row + blocks, (byte) -1);
                Arrays.fill(d.frame.ref1, row, row + blocks, (byte) -1);
                Arrays.fill(d.frame.mv0, row, row + blocks, 0);
                Arrays.fill(d.frame.mv1, row, row + blocks, 0);
            }
        }
    }

    /**
     * Sets a field of the {@link PictureData#tuInfo} or {@link PictureData#cuInfo} of a square area.
     *
     * @param array the per-4x4 information array to change
     * @param x0 the luma x
     * @param y0 the luma y
     * @param size the side in luma samples
     * @param clear the bits to clear
     * @param set the bits to set
     */
    private void setBits(byte[] array, int x0, int y0, int size, int clear, int set) {
        var w4 = pic.width4;
        var start = d.block(x0, y0);
        var blocks = size >> 2;
        for (var j = 0; j < blocks; j++) {
            var row = start + j * w4;
            for (var i = 0; i < blocks; i++) {
                array[row + i] = (byte) ((array[row + i] & ~clear) | set);
            }
        }
    }

    /**
     * Sets the luma intra mode of a square area.
     *
     * @param x0 the luma x
     * @param y0 the luma y
     * @param size the side
     * @param mode the intra prediction mode, 0 to 34
     */
    private void setIntraMode(int x0, int y0, int size, int mode) {
        var w4 = pic.width4;
        var start = d.block(x0, y0);
        var blocks = size >> 2;
        for (var j = 0; j < blocks; j++) {
            var row = start + j * w4;
            Arrays.fill(pic.intraMode, row, row + blocks, (byte) mode);
        }
    }

    /**
     * Decides an intra coding unit with one prediction block.
     *
     * @param x0 the luma x
     * @param y0 the luma y
     * @param log2Size the coding block size as a power of two, at most 5
     * @param depth the coding tree depth
     * @return the cost
     */
    private double intraCu(int x0, int y0, int log2Size, int depth) {
        var size = 1 << log2Size;
        markCu(x0, y0, size, depth, PictureState.INTRA, SyntaxCoder.PART_2Nx2N, 0);
        var lumaMode = decideLuma(x0, y0, log2Size, 0);
        decideChroma(x0, y0, log2Size, lumaMode, x0 >> 1, y0 >> 1, log2Size - 1);
        return cuCost(x0, y0, log2Size, depth);
    }

    /**
     * Decides an 8x8 intra coding unit with four 4x4 luma prediction blocks.
     *
     * @param x0 the luma x
     * @param y0 the luma y
     * @param depth the coding tree depth
     * @return the cost
     */
    private double intraNxN(int x0, int y0, int depth) {
        markCu(x0, y0, 8, depth, PictureState.INTRA, SyntaxCoder.PART_NxN, 1);
        var first = 0;
        for (var i = 0; i < 4; i++) {
            var mode = decideLuma(x0 + (i & 1) * 4, y0 + (i >> 1) * 4, 2, 1);
            if (i == 0) {
                first = mode;
            }
        }
        decideChroma(x0, y0, 3, first, x0 >> 1, y0 >> 1, 2);
        return cuCost(x0, y0, 3, depth);
    }

    /**
     * Decides and reconstructs the luma intra mode of one prediction block that is one transform block.
     *
     * @param x0 the luma x
     * @param y0 the luma y
     * @param log2Size the block size as a power of two, 2 to 5
     * @param trafoDepth the transform tree depth of the block
     * @return the chosen mode
     */
    private int decideLuma(int x0, int y0, int log2Size, int trafoDepth) {
        var size = 1 << log2Size;
        var w = s.codedWidth;
        var offset = y0 * w + x0;
        syntax.mpmIndex(x0, y0, -1, mpm);
        var count = 0;
        for (var mode = 0; mode < 35; mode++) {
            if (!roughCandidate(mode)) {
                continue;
            }
            predictIntra(x0, y0, log2Size, 0, mode, pred, size);
            var satd = Distortion.satd(d.sourceY, offset, w, pred, 0, size, size, size, satdScratch);
            modeCost[count] = satd + sqrtLambda * modeBits(mode);
            modeOrder[count] = mode;
            count++;
        }
        sortByCost(count);
        var keep = switch (s.speed) {
            case FASTEST -> 1;
            case FAST -> 2;
            case BALANCED -> 3;
            case SLOW -> 4;
        };
        keep = Math.min(keep, count);
        if (s.speed != HevcEncoderSpeed.FASTEST && !contains(modeOrder, keep, mpm[0])) {
            modeOrder[keep++] = mpm[0];
        }
        var bestMode = modeOrder[0];
        var bestCost = Double.MAX_VALUE;
        var last = -1;
        for (var k = 0; k < keep && keep > 1; k++) {
            var mode = modeOrder[k];
            var cost = lumaCost(x0, y0, log2Size, trafoDepth, mode);
            last = mode;
            if (cost < bestCost) {
                bestCost = cost;
                bestMode = mode;
            }
        }
        if (last != bestMode) {
            lumaCost(x0, y0, log2Size, trafoDepth, bestMode);
        }
        return bestMode;
    }

    /**
     * Returns whether a mode takes part in the rough intra mode search of the speed.
     *
     * @param mode the intra prediction mode, 0 to 34
     * @return {@code true} to evaluate the mode
     */
    private boolean roughCandidate(int mode) {
        return switch (s.speed) {
            case FASTEST -> mode < 2 || mode % 8 == 2 || mode == mpm[0] || mode == mpm[1] || mode == mpm[2];
            case FAST -> mode < 2 || mode % 2 == 0 || mode == mpm[0] || mode == mpm[1] || mode == mpm[2];
            default -> true;
        };
    }

    /**
     * Returns whether one of the first entries of an array holds a value.
     *
     * @param array the values searched
     * @param count the number of entries
     * @param value the value looked for
     * @return {@code true} when it is present
     */
    private static boolean contains(int[] array, int count, int value) {
        for (var i = 0; i < count; i++) {
            if (array[i] == value) {
                return true;
            }
        }
        return false;
    }

    /**
     * Sorts the first entries of {@link #modeOrder} by {@link #modeCost}.
     *
     * @param count the number of entries
     */
    private void sortByCost(int count) {
        for (var i = 1; i < count; i++) {
            var cost = modeCost[i];
            var mode = modeOrder[i];
            var j = i - 1;
            while (j >= 0 && modeCost[j] > cost) {
                modeCost[j + 1] = modeCost[j];
                modeOrder[j + 1] = modeOrder[j];
                j--;
            }
            modeCost[j + 1] = cost;
            modeOrder[j + 1] = mode;
        }
    }

    /**
     * Returns the approximate bits of a luma mode given the most probable modes in {@link #mpm}.
     *
     * @param mode the intra prediction mode, 0 to 34
     * @return the bits
     */
    private double modeBits(int mode) {
        counter.loadStates(ctuStates, 0);
        if (mode == mpm[0]) {
            return (counter.cost(CabacTables.PREV_INTRA_LUMA, 1) + CabacCounter.ONE_BIT) / (double) CabacCounter.ONE_BIT;
        }
        if (mode == mpm[1] || mode == mpm[2]) {
            return (counter.cost(CabacTables.PREV_INTRA_LUMA, 1) + 2.0 * CabacCounter.ONE_BIT)
                    / CabacCounter.ONE_BIT;
        }
        return (counter.cost(CabacTables.PREV_INTRA_LUMA, 0) + 5.0 * CabacCounter.ONE_BIT) / CabacCounter.ONE_BIT;
    }

    /**
     * Reconstructs a luma intra block with a mode and returns its luma cost.
     *
     * @param x0 the luma x
     * @param y0 the luma y
     * @param log2Size the block size as a power of two
     * @param trafoDepth the transform tree depth
     * @param mode the intra prediction mode, 0 to 34
     * @return the luma distortion plus lambda times the bits of the mode and the residual
     */
    private double lumaCost(int x0, int y0, int log2Size, int trafoDepth, int mode) {
        var size = 1 << log2Size;
        setIntraMode(x0, y0, size, mode);
        predictIntra(x0, y0, log2Size, 0, mode, pred, size);
        var cbf = codeBlock(x0, y0, log2Size, 0, true, log2Size == 2, mode);
        setBits(d.tuInfo, x0, y0, size, PictureData.TU_CBF_Y, cbf ? PictureData.TU_CBF_Y : 0);
        var w = s.codedWidth;
        var dist = Distortion.sse(d.sourceY, y0 * w + x0, w, d.frame.y, y0 * w + x0, w, size, size);
        counter.loadStates(ctuStates, 0);
        counter.bits = 0;
        syntax.setCoder(counter);
        var index = syntax.mpmIndex(x0, y0, mode, mpm);
        counter.bin(CabacTables.PREV_INTRA_LUMA, index >= 0 ? 1 : 0);
        counter.bypassBins(0, index < 0 ? 5 : index == 0 ? 1 : 2);
        counter.bin(CabacTables.CBF_LUMA + (trafoDepth == 0 ? 1 : 0), cbf ? 1 : 0);
        if (cbf) {
            syntax.residual(d.levelY, w, y0 * w + x0, log2Size, 0, SyntaxCoder.scanIndex(log2Size, 0, mode));
        }
        syntax.mpmIndex(x0, y0, -1, mpm);
        return dist + lambda * counter.bits / CabacCounter.ONE_BIT;
    }

    /**
     * Decides and reconstructs the chroma intra mode of an intra coding unit.
     *
     * @param x0 the luma x of the coding block
     * @param y0 the luma y of the coding block
     * @param log2Size the coding block size as a power of two
     * @param lumaMode {@code IntraPredModeY} of the first prediction block
     * @param xc the chroma x of the chroma block
     * @param yc the chroma y of the chroma block
     * @param log2SizeC the chroma block size as a power of two
     */
    private void decideChroma(int x0, int y0, int log2Size, int lumaMode, int xc, int yc, int log2SizeC) {
        var candidates = s.speed.compareTo(HevcEncoderSpeed.BALANCED) >= 0 ? 5 : 1;
        var bestSyntax = 4;
        var bestCost = Double.MAX_VALUE;
        var last = -1;
        for (var k = 0; k < candidates && candidates > 1; k++) {
            var syntaxValue = k == 0 ? 4 : k - 1;
            var cost = chromaCost(x0, y0, log2Size, lumaMode, syntaxValue, xc, yc, log2SizeC);
            last = syntaxValue;
            if (cost < bestCost) {
                bestCost = cost;
                bestSyntax = syntaxValue;
            }
        }
        if (last != bestSyntax) {
            chromaCost(x0, y0, log2Size, lumaMode, bestSyntax, xc, yc, log2SizeC);
        }
    }

    /**
     * Reconstructs the chroma blocks of an intra coding unit with one chroma mode and returns their cost.
     *
     * @param x0 the luma x of the coding block
     * @param y0 the luma y of the coding block
     * @param log2Size the coding block size as a power of two
     * @param lumaMode {@code IntraPredModeY} of the first prediction block
     * @param syntaxValue {@code intra_chroma_pred_mode}
     * @param xc the chroma x
     * @param yc the chroma y
     * @param log2SizeC the chroma block size as a power of two
     * @return the chroma distortion plus lambda times the bits of the mode, the flags and the residuals
     */
    private double chromaCost(int x0, int y0, int log2Size, int lumaMode, int syntaxValue, int xc, int yc,
                              int log2SizeC) {
        var size = 1 << log2Size;
        var sizeC = 1 << log2SizeC;
        var mode = SyntaxCoder.chromaPredMode(syntaxValue, lumaMode);
        setBits(d.cuInfo, x0, y0, size, 7 << PictureData.CU_CHROMA_SHIFT, syntaxValue << PictureData.CU_CHROMA_SHIFT);
        var xl = xc << 1;
        var yl = yc << 1;
        predictIntra(xl, yl, log2SizeC, 1, mode, pred, sizeC);
        var cbfCb = codeBlock(xc, yc, log2SizeC, 1, true, false, mode);
        predictIntra(xl, yl, log2SizeC, 2, mode, pred, sizeC);
        var cbfCr = codeBlock(xc, yc, log2SizeC, 2, true, false, mode);
        var flags = (cbfCb ? PictureData.TU_CBF_CB : 0) | (cbfCr ? PictureData.TU_CBF_CR : 0);
        setBits(d.tuInfo, x0, y0, size, PictureData.TU_CBF_CB | PictureData.TU_CBF_CR, flags);
        var cw = s.chromaWidth;
        var offset = yc * cw + xc;
        var dist = Distortion.sse(d.sourceCb, offset, cw, d.frame.cb, offset, cw, sizeC, sizeC)
                + Distortion.sse(d.sourceCr, offset, cw, d.frame.cr, offset, cw, sizeC, sizeC);
        counter.loadStates(ctuStates, 0);
        counter.bits = 0;
        syntax.setCoder(counter);
        syntax.chromaMode(syntaxValue);
        counter.bin(CabacTables.CBF_CHROMA, cbfCb ? 1 : 0);
        counter.bin(CabacTables.CBF_CHROMA, cbfCr ? 1 : 0);
        var scan = SyntaxCoder.scanIndex(log2SizeC, 1, mode);
        if (cbfCb) {
            syntax.residual(d.levelCb, cw, offset, log2SizeC, 1, scan);
        }
        if (cbfCr) {
            syntax.residual(d.levelCr, cw, offset, log2SizeC, 2, scan);
        }
        return chromaWeight * dist + lambda * counter.bits / CabacCounter.ONE_BIT;
    }

    /**
     * Predicts an intra block from its reconstructed neighbors (8.4.4.2) into a buffer.
     *
     * @param xLuma the luma x of the block
     * @param yLuma the luma y of the block
     * @param log2Size the block size of the component as a power of two
     * @param cIdx the component
     * @param mode the intra prediction mode
     * @param out receives the prediction, {@code stride} samples per row
     * @param stride the row stride of {@code out}
     */
    private void predictIntra(int xLuma, int yLuma, int log2Size, int cIdx, int mode, short[] out, int stride) {
        var size = 1 << log2Size;
        var shift = cIdx == 0 ? 0 : 1;
        var plane = d.recon(cIdx);
        var planeStride = d.stride(cIdx);
        var xc = xLuma >> shift;
        var yc = yLuma >> shift;
        var count = 4 * size + 1;
        var corner = 2 * size;
        var all = true;
        var block = Integer.MIN_VALUE;
        var avail = false;
        for (var y = 2 * size - 1; y >= -1; y--) {
            var index = corner - 1 - y;
            var yNb = (yc + y) << shift;
            if (yNb >> 2 != block) {
                block = yNb >> 2;
                avail = available(xLuma, yLuma, (xc - 1) << shift, yNb);
            }
            boundaryAvailable[index] = avail;
            if (avail) {
                boundary[index] = plane[(yc + y) * planeStride + xc - 1];
            } else {
                all = false;
            }
        }
        block = Integer.MIN_VALUE;
        for (var x = 0; x < 2 * size; x++) {
            var index = corner + 1 + x;
            var xNb = (xc + x) << shift;
            if (xNb >> 2 != block) {
                block = xNb >> 2;
                avail = available(xLuma, yLuma, xNb, (yc - 1) << shift);
            }
            boundaryAvailable[index] = avail;
            if (avail) {
                boundary[index] = plane[(yc - 1) * planeStride + xc + x];
            } else {
                all = false;
            }
        }
        if (!all) {
            IntraPredictor.substitute(boundary, boundaryAvailable, count, s.bitDepth);
        }
        intra.predict(boundary, log2Size, mode, cIdx == 0, cIdx == 0, true, cIdx == 0, true, s.bitDepth, out, 0,
                stride);
    }

    /**
     * Transforms, quantizes and reconstructs one transform block whose prediction is in {@link #pred}: the
     * levels go to the picture's level plane and the reconstruction to its sample plane.
     *
     * @param x the x of the block in samples of the component
     * @param y the y of the block in samples of the component
     * @param log2Size the block size as a power of two
     * @param cIdx the component
     * @param intraBlock whether the block is intra
     * @param dst whether the block uses the DST
     * @param mode the intra mode, unused for inter blocks
     * @return whether the block has non-zero levels
     */
    private boolean codeBlock(int x, int y, int log2Size, int cIdx, boolean intraBlock, boolean dst, int mode) {
        var size = 1 << log2Size;
        var stride = d.stride(cIdx);
        var offset = y * stride + x;
        var source = d.source(cIdx);
        var recon = d.recon(cIdx);
        var levels = d.levels(cIdx);
        for (var j = 0; j < size; j++) {
            var row = offset + j * stride;
            for (var i = 0; i < size; i++) {
                residual[j * size + i] = source[row + i] - pred[j * size + i];
            }
        }
        forward.forward(residual, log2Size, dst, s.bitDepth, coeffs);
        var qp = cIdx == 0 ? qpLuma : qpChroma;
        var scanIdx = pps.signDataHiding ? SyntaxCoder.scanIndex(log2Size, cIdx, intraBlock ? mode : -1) : -1;
        var sum = ForwardTransform.quantize(coeffs, log2Size, qp, s.bitDepth, intraBlock, levels, offset, stride,
                scanIdx, deltaU);
        if (sum == 0) {
            for (var j = 0; j < size; j++) {
                System.arraycopy(pred, j * size, recon, offset + j * stride, size);
            }
            return false;
        }
        var extent = ForwardTransform.dequantize(levels, offset, stride, log2Size, qp, s.bitDepth, coeffs);
        var columns = dst ? size : extent & 0xFF;
        var rows = dst ? size : extent >> 8;
        inverse.inverse(coeffs, log2Size, dst, 20 - s.bitDepth, columns, rows, residual, pic.dsp);
        var max = (1 << s.bitDepth) - 1;
        for (var j = 0; j < size; j++) {
            var row = offset + j * stride;
            for (var i = 0; i < size; i++) {
                recon[row + i] = (short) Math.clamp(pred[j * size + i] + residual[j * size + i], 0, max);
            }
        }
        return true;
    }

    /**
     * Codes the residual of an inter coding unit whose prediction is in the reconstruction: one transform
     * block per component, or four for a 64x64 coding unit.
     *
     * @param x0 the luma x
     * @param y0 the luma y
     * @param log2Size the coding block size as a power of two
     * @return whether any block has non-zero levels
     */
    private boolean interResidual(int x0, int y0, int log2Size, boolean split) {
        var size = 1 << log2Size;
        var any = false;
        if (split || log2Size > sps.log2MaxTbSize) {
            var half = size >> 1;
            for (var i = 0; i < 4; i++) {
                any |= interBlock(x0 + (i & 1) * half, y0 + (i >> 1) * half, log2Size - 1);
            }
            return any;
        }
        return interBlock(x0, y0, log2Size);
    }

    /**
     * Codes the residual of an inter coding unit whose prediction is in the reconstruction with the transform
     * tree of least cost and returns that cost.
     *
     * <p>The candidates are no residual (when allowed), one transform unit (four for a 64x64 coding unit) and,
     * at {@link HevcEncoderSpeed#SLOW}, four transform units for a 16x16 or 32x32 coding unit. The coding unit's
     * transform depth and coded block flags must be those of no residual on entry.
     *
     * @param x0 the luma x
     * @param y0 the luma y
     * @param log2Size the coding block size as a power of two
     * @param depth the coding tree depth
     * @param allowNone whether the coding unit may be left without residual
     * @return the cost of the choice left in place, or {@link Double#MAX_VALUE} when no residual is not allowed
     * and no transform tree has coefficients, the coding unit then being left without residual
     */
    private double residualChoice(int x0, int y0, int log2Size, int depth, boolean allowNone) {
        var size = 1 << log2Size;
        var cbf = PictureData.TU_CBF_Y | PictureData.TU_CBF_CB | PictureData.TU_CBF_CR;
        var base = log2Size > sps.log2MaxTbSize ? 1 : 0;
        var trySplit = base == 0 && log2Size >= 4 && s.speed == HevcEncoderSpeed.SLOW;
        savePrediction(x0, y0, size);
        var best = allowNone ? cuCost(x0, y0, log2Size, depth) : Double.MAX_VALUE;
        var bestTree = -1;
        if (interResidual(x0, y0, log2Size, false)) {
            var cost = cuCost(x0, y0, log2Size, depth);
            if (cost <= best) {
                best = cost;
                bestTree = base;
            }
        }
        var current = base;
        if (trySplit) {
            restorePrediction(x0, y0, size);
            setBits(d.tuInfo, x0, y0, size, PictureData.TU_DEPTH_MASK | cbf, 1);
            current = 1;
            if (interResidual(x0, y0, log2Size, true)) {
                var cost = cuCost(x0, y0, log2Size, depth);
                if (cost < best) {
                    best = cost;
                    bestTree = 1;
                }
            }
        }
        if (bestTree != current || bestTree < 0) {
            restorePrediction(x0, y0, size);
            setBits(d.tuInfo, x0, y0, size, PictureData.TU_DEPTH_MASK | cbf, base);
            if (bestTree >= 0) {
                interResidual(x0, y0, log2Size, bestTree == 1 && base == 0);
            }
        }
        return best;
    }

    /**
     * Copies the prediction of a coding unit from the reconstruction into {@link #savedPrediction}.
     *
     * @param x0 the luma x
     * @param y0 the luma y
     * @param size the coding block size
     */
    private void savePrediction(int x0, int y0, int size) {
        copyBlock(x0, y0, size, true);
    }

    /**
     * Copies the prediction of a coding unit from {@link #savedPrediction} back into the reconstruction.
     *
     * @param x0 the luma x
     * @param y0 the luma y
     * @param size the coding block size
     */
    private void restorePrediction(int x0, int y0, int size) {
        copyBlock(x0, y0, size, false);
    }

    /**
     * Copies the three blocks of a coding unit between the reconstruction and {@link #savedPrediction}.
     *
     * @param x0 the luma x
     * @param y0 the luma y
     * @param size the coding block size
     * @param save whether to copy into {@link #savedPrediction}, otherwise out of it
     */
    private void copyBlock(int x0, int y0, int size, boolean save) {
        var position = 0;
        for (var cIdx = 0; cIdx < 3; cIdx++) {
            var side = cIdx == 0 ? size : size >> 1;
            var x = cIdx == 0 ? x0 : x0 >> 1;
            var y = cIdx == 0 ? y0 : y0 >> 1;
            var plane = d.recon(cIdx);
            var stride = d.stride(cIdx);
            for (var j = 0; j < side; j++) {
                if (save) {
                    System.arraycopy(plane, (y + j) * stride + x, savedPrediction, position, side);
                } else {
                    System.arraycopy(savedPrediction, position, plane, (y + j) * stride + x, side);
                }
                position += side;
            }
        }
    }

    /**
     * Codes the luma and chroma transform blocks of one inter transform unit.
     *
     * @param x0 the luma x
     * @param y0 the luma y
     * @param log2Size the luma transform block size as a power of two
     * @return whether any block has non-zero levels
     */
    private boolean interBlock(int x0, int y0, int log2Size) {
        var size = 1 << log2Size;
        copyPrediction(0, x0, y0, size);
        var cbfY = codeBlock(x0, y0, log2Size, 0, false, false, -1);
        var xc = x0 >> 1;
        var yc = y0 >> 1;
        copyPrediction(1, xc, yc, size >> 1);
        var cbfCb = codeBlock(xc, yc, log2Size - 1, 1, false, false, -1);
        copyPrediction(2, xc, yc, size >> 1);
        var cbfCr = codeBlock(xc, yc, log2Size - 1, 2, false, false, -1);
        var flags = (cbfY ? PictureData.TU_CBF_Y : 0) | (cbfCb ? PictureData.TU_CBF_CB : 0)
                | (cbfCr ? PictureData.TU_CBF_CR : 0);
        setBits(d.tuInfo, x0, y0, size, PictureData.TU_CBF_Y | PictureData.TU_CBF_CB | PictureData.TU_CBF_CR, flags);
        return flags != 0;
    }

    /**
     * Copies the prediction of a block from the reconstruction into {@link #pred}.
     *
     * @param cIdx the component
     * @param x the x in samples of the component
     * @param y the y in samples of the component
     * @param size the block side
     */
    private void copyPrediction(int cIdx, int x, int y, int size) {
        var stride = d.stride(cIdx);
        var plane = d.recon(cIdx);
        for (var j = 0; j < size; j++) {
            System.arraycopy(plane, (y + j) * stride + x, pred, j * size, size);
        }
    }

    /**
     * Decides an inter coding unit with one prediction block: skip, merge with a residual, or a searched
     * motion vector.
     *
     * @param x0 the luma x
     * @param y0 the luma y
     * @param log2Size the coding block size as a power of two
     * @param depth the coding tree depth
     * @return the cost of the best choice, which is left reconstructed in place
     */
    private double interCu(int x0, int y0, int log2Size, int depth) {
        var size = 1 << log2Size;
        var trafoDepth = log2Size > sps.log2MaxTbSize ? 1 : 0;
        var count = mergeCandidates(x0, y0, size);
        var keep = s.speed == HevcEncoderSpeed.FASTEST ? 1 : 2;
        var best = Double.MAX_VALUE;
        var bestKind = -1;
        var bestIndex = 0;
        for (var round = 0; round < Math.min(keep, count); round++) {
            var index = nextCandidate(count);
            if (index < 0) {
                break;
            }
            markCu(x0, y0, size, depth, PictureState.SKIP, SyntaxCoder.PART_2Nx2N, trafoDepth);
            applyMerge(x0, y0, size, index);
            var cost = cuCost(x0, y0, log2Size, depth);
            if (cost < best) {
                best = cost;
                bestKind = 0;
                bestIndex = index;
            }
            markCu(x0, y0, size, depth, 0, SyntaxCoder.PART_2Nx2N, trafoDepth);
            applyMerge(x0, y0, size, index);
            cost = residualChoice(x0, y0, log2Size, depth, false);
            if (cost < best) {
                best = cost;
                bestKind = 1;
                bestIndex = index;
            }
        }
        var amvp = amvpCu(x0, y0, log2Size, depth, trafoDepth);
        if (amvp < best) {
            return amvp;
        }
        markCu(x0, y0, size, depth, bestKind == 0 ? PictureState.SKIP : 0, SyntaxCoder.PART_2Nx2N, trafoDepth);
        applyMerge(x0, y0, size, bestIndex);
        if (bestKind == 1) {
            residualChoice(x0, y0, log2Size, depth, false);
        }
        return best;
    }

    /**
     * Derives the merge candidates of a 2Nx2N prediction unit and their rough costs.
     *
     * @param x0 the luma x
     * @param y0 the luma y
     * @param size the coding block size
     * @return the number of candidates
     */
    private int mergeCandidates(int x0, int y0, int size) {
        var count = header.maxNumMergeCand;
        var m = probe;
        for (var index = 0; index < count; index++) {
            m.clear();
            m.merge = true;
            m.mergeIdx = index;
            m.partMode = SyntaxCoder.PART_2Nx2N;
            motion.derive(x0, y0, size, x0, y0, size, size, 0, m, this);
            candDir[index] = (m.predFlag[0] ? 1 : 0) | (m.predFlag[1] ? 2 : 0);
            for (var list = 0; list < 2; list++) {
                candRef[list][index] = m.predFlag[list] ? m.refIdx[list] : -1;
                candMv[list][index] = m.predFlag[list] ? (m.mvY[list] << 16) | (m.mvX[list] & 0xFFFF) : 0;
            }
            var duplicate = false;
            for (var other = 0; other < index && !duplicate; other++) {
                duplicate = candDir[other] == candDir[index] && candRef[0][other] == candRef[0][index]
                        && candRef[1][other] == candRef[1][index] && candMv[0][other] == candMv[0][index]
                        && candMv[1][other] == candMv[1][index];
            }
            candCost[index] = duplicate ? Double.MAX_VALUE : roughCost(x0, y0, size, size, candDir[index],
                    candRef[0][index], candMv[0][index], candRef[1][index], candMv[1][index])
                    + sqrtLambda * Math.min(index + 1, count - 1);
        }
        return count;
    }

    /**
     * Returns the index of the cheapest merge candidate not yet taken, and marks it taken.
     *
     * @param count the number of candidates
     * @return the index, or -1 when none is left
     */
    private int nextCandidate(int count) {
        var best = -1;
        for (var index = 0; index < count; index++) {
            if (candCost[index] != Double.MAX_VALUE && (best < 0 || candCost[index] < candCost[best])) {
                best = index;
            }
        }
        if (best >= 0) {
            candCost[best] = Double.MAX_VALUE;
        }
        return best;
    }

    /**
     * Returns the luma SATD of a motion hypothesis.
     *
     * @param x0 the luma x of the block
     * @param y0 the luma y of the block
     * @param width the block width
     * @param height the block height
     * @param dir {@code predFlagL0 | predFlagL1 << 1}
     * @param ref0 the list 0 reference index
     * @param mv0 the list 0 vector, packed
     * @param ref1 the list 1 reference index
     * @param mv1 the list 1 vector, packed
     * @return the SATD
     */
    private double roughCost(int x0, int y0, int width, int height, int dir, int ref0, int mv0, int ref1, int mv1) {
        var w = s.codedWidth;
        var stride = s.codedWidth + 2 * MotionSearch.PAD;
        var origin = MotionSearch.PAD * stride + MotionSearch.PAD;
        var offset = y0 * w + x0;
        if (dir == 3) {
            var ok = search.intermediate(refPlanes[0][ref0], origin, stride, x0, y0, width, height, clampMv((short) mv0),
                    clampMv(mv0 >> 16), biPred0)
                    && search.intermediate(refPlanes[1][ref1], origin, stride, x0, y0, width, height,
                    clampMv((short) mv1), clampMv(mv1 >> 16), biPred1);
            return ok ? search.biCost(d.sourceY, offset, w, biPred0, biPred1, width, height) : Double.MAX_VALUE / 4;
        }
        var list = dir == 1 ? 0 : 1;
        var mv = list == 0 ? mv0 : mv1;
        var ref = list == 0 ? ref0 : ref1;
        if (!search.interpolate(refPlanes[list][ref], origin, stride, x0, y0, width, height, clampMv((short) mv),
                clampMv(mv >> 16), uniPred)) {
            return Double.MAX_VALUE / 4;
        }
        return Distortion.satd(d.sourceY, offset, w, uniPred, 0, width, width, height, satdScratch);
    }

    /**
     * Clamps a motion vector component to the padding of the search planes, for the rough estimates.
     *
     * @param component the component in quarter samples
     * @return the clamped component
     */
    private static int clampMv(int component) {
        var limit = (MotionSearch.RANGE - 1) << 2;
        return Math.clamp(component, -limit, limit);
    }

    /**
     * Stores the motion of a merge candidate for a 2Nx2N prediction unit and predicts it.
     *
     * @param x0 the luma x
     * @param y0 the luma y
     * @param size the coding block size
     * @param index the merge index
     */
    private void applyMerge(int x0, int y0, int size, int index) {
        pu.clear();
        pu.merge = true;
        pu.mergeIdx = index;
        pu.partMode = SyntaxCoder.PART_2Nx2N;
        for (var list = 0; list < 2; list++) {
            pu.predFlag[list] = (candDir[index] & (1 << list)) != 0;
            pu.refIdx[list] = candRef[list][index];
            pu.mvX[list] = (short) candMv[list][index];
            pu.mvY[list] = candMv[list][index] >> 16;
        }
        storeMotion(x0, y0, size, size, PictureData.PU_MERGE | (index << PictureData.PU_MERGE_IDX_SHIFT), 0, 0);
        inter.predict(d.frame, x0, y0, size, size, pu);
    }

    /**
     * Writes the motion of {@link #pu} and its syntax to the arrays of a prediction block.
     *
     * @param x0 the luma x
     * @param y0 the luma y
     * @param width the block width
     * @param height the block height
     * @param info the {@link PictureData#puInfo} value
     * @param mvd0 the list 0 difference, packed
     * @param mvd1 the list 1 difference, packed
     */
    private void storeMotion(int x0, int y0, int width, int height, int info, int mvd0, int mvd1) {
        var w4 = pic.width4;
        var start = d.block(x0, y0);
        var mv0 = (pu.mvY[0] << 16) | (pu.mvX[0] & 0xFFFF);
        var mv1 = (pu.mvY[1] << 16) | (pu.mvX[1] & 0xFFFF);
        var r0 = (byte) (pu.predFlag[0] ? pu.refIdx[0] : -1);
        var r1 = (byte) (pu.predFlag[1] ? pu.refIdx[1] : -1);
        var columns = width >> 2;
        for (var j = 0; j < height >> 2; j++) {
            var row = start + j * w4;
            Arrays.fill(d.frame.mv0, row, row + columns, mv0);
            Arrays.fill(d.frame.mv1, row, row + columns, mv1);
            Arrays.fill(d.frame.ref0, row, row + columns, r0);
            Arrays.fill(d.frame.ref1, row, row + columns, r1);
            Arrays.fill(d.puInfo, row, row + columns, info);
            Arrays.fill(d.mvd0, row, row + columns, mvd0);
            Arrays.fill(d.mvd1, row, row + columns, mvd1);
        }
    }

    /**
     * Decides a 2Nx2N inter coding unit with motion vectors coded against their predictors.
     *
     * @param x0 the luma x
     * @param y0 the luma y
     * @param log2Size the coding block size as a power of two
     * @param depth the coding tree depth
     * @param trafoDepth the transform tree depth of every leaf
     * @return the cost, the coding unit left reconstructed in place
     */
    private double amvpCu(int x0, int y0, int log2Size, int depth, int trafoDepth) {
        var size = 1 << log2Size;
        var lists = bSlice ? 2 : 1;
        var bestMv = this.bestMv;
        var bestRef = this.bestRef;
        var bestMvp = this.bestMvp;
        var bestCost = this.bestCost;
        var bestBits = this.bestBits;
        bestCost[0] = Double.MAX_VALUE;
        bestCost[1] = Double.MAX_VALUE;
        var subpel = s.speed == HevcEncoderSpeed.FASTEST ? 1 : 2;
        var range = switch (s.speed) {
            case FASTEST -> 8;
            case FAST -> 16;
            case BALANCED -> 32;
            case SLOW -> 48;
        };
        var w = s.codedWidth;
        var stride = s.codedWidth + 2 * MotionSearch.PAD;
        for (var list = 0; list < lists; list++) {
            var refs = Math.min(searchRefs, header.numRefIdx[list]);
            for (var ref = 0; ref < refs; ref++) {
                var mvp0 = predictor(x0, y0, size, list, ref, 0);
                var mvp1 = predictor(x0, y0, size, list, ref, 1);
                var startCount = 0;
                starts[startCount++] = mvp0;
                starts[startCount++] = mvp1;
                starts[startCount++] = 0;
                for (var index = 0; index < header.maxNumMergeCand && startCount < starts.length; index++) {
                    if (candRef[list][index] == ref) {
                        starts[startCount++] = candMv[list][index];
                    }
                }
                search.search(d.sourceY, w, x0, y0, size, size, refPlanes[list][ref], stride, starts, startCount,
                        (short) mvp0, mvp0 >> 16, range, subpel, found);
                var mv = found[0];
                var cost0 = search.mvCost((short) mv, mv >> 16, (short) mvp0, mvp0 >> 16);
                var cost1 = search.mvCost((short) mv, mv >> 16, (short) mvp1, mvp1 >> 16);
                var mvpFlag = cost1 < cost0 ? 1 : 0;
                var satd = found[1] - cost0;
                var refBits = header.numRefIdx[list] > 1 ? ref + 1 : 0;
                var bits = MotionSearch.componentBits((short) mv - (short) (mvpFlag == 0 ? mvp0 : mvp1))
                        + MotionSearch.componentBits((mv >> 16) - ((mvpFlag == 0 ? mvp0 : mvp1) >> 16)) + refBits;
                var cost = satd + search.bitCost(bits);
                if (cost < bestCost[list]) {
                    bestCost[list] = cost;
                    bestMv[list] = mv;
                    bestRef[list] = ref;
                    bestMvp[list] = mvpFlag;
                    bestBits[list] = bits;
                }
            }
        }
        var dir = bSlice && bestCost[1] < bestCost[0] ? 1 : 0;
        if (bSlice) {
            var bi = roughCost(x0, y0, size, size, 3, bestRef[0], bestMv[0], bestRef[1], bestMv[1])
                    + search.bitCost(bestBits[0] + bestBits[1] + 2);
            if (bi < Math.min(bestCost[0], bestCost[1])) {
                dir = 2;
            }
        }
        pu.clear();
        pu.partMode = SyntaxCoder.PART_2Nx2N;
        var mvd = this.mvd;
        mvd[0] = 0;
        mvd[1] = 0;
        for (var list = 0; list < 2; list++) {
            var used = dir == 2 || dir == list;
            if (!used) {
                continue;
            }
            var mvp = predictor(x0, y0, size, list, bestRef[list], bestMvp[list]);
            pu.predFlag[list] = true;
            pu.refIdx[list] = bestRef[list];
            pu.mvX[list] = (short) bestMv[list];
            pu.mvY[list] = bestMv[list] >> 16;
            mvd[list] = (((bestMv[list] >> 16) - (mvp >> 16)) << 16) | (((short) bestMv[list] - (short) mvp) & 0xFFFF);
        }
        var info = (dir << PictureData.PU_DIR_SHIFT) | (bestMvp[0] << PictureData.PU_MVP_SHIFT)
                | (bestMvp[1] << (PictureData.PU_MVP_SHIFT + 1));
        markCu(x0, y0, size, depth, 0, SyntaxCoder.PART_2Nx2N, trafoDepth);
        storeMotion(x0, y0, size, size, info, mvd[0], mvd[1]);
        inter.predict(d.frame, x0, y0, size, size, pu);
        return residualChoice(x0, y0, log2Size, depth, true);
    }

    /**
     * Decides an inter coding unit of two prediction units, {@code PART_2NxN} or {@code PART_Nx2N}: each takes
     * the cheaper of its best merge candidate and its searched motion vector, then the residual is coded when
     * it lowers the cost.
     *
     * @param x0 the luma x
     * @param y0 the luma y
     * @param log2Size the coding block size as a power of two
     * @param depth the coding tree depth
     * @param partMode the partitioning mode
     * @return the cost, the coding unit left reconstructed in place
     */
    private double partitionedCu(int x0, int y0, int log2Size, int depth, int partMode) {
        var size = 1 << log2Size;
        var half = size >> 1;
        var trafoDepth = log2Size > sps.log2MaxTbSize ? 1 : 0;
        markCu(x0, y0, size, depth, 0, partMode, trafoDepth);
        for (var partIdx = 0; partIdx < 2; partIdx++) {
            var horizontal = partMode == SyntaxCoder.PART_2NxN;
            var xP = horizontal ? x0 : x0 + partIdx * half;
            var yP = horizontal ? y0 + partIdx * half : y0;
            var width = horizontal ? size : half;
            var height = horizontal ? half : size;
            decidePart(x0, y0, size, xP, yP, width, height, partIdx, partMode);
        }
        return residualChoice(x0, y0, log2Size, depth, true);
    }

    /**
     * Decides the motion of one prediction unit of a partitioned coding unit, stores it and predicts the unit.
     *
     * <p>The merge candidates are compared by their prediction error and index bits, the searched motion by
     * its prediction error and motion bits; bi-prediction is not considered for 8x4 and 4x8 units, which may
     * not use it.
     *
     * @param x0 the luma x of the coding block
     * @param y0 the luma y of the coding block
     * @param size the coding block size
     * @param xP the luma x of the prediction block
     * @param yP the luma y of the prediction block
     * @param width the prediction block width
     * @param height the prediction block height
     * @param partIdx the index of the prediction unit in the coding unit
     * @param partMode the partitioning mode
     */
    private void decidePart(int x0, int y0, int size, int xP, int yP, int width, int height, int partIdx,
                            int partMode) {
        var count = header.maxNumMergeCand;
        var m = probe;
        var bestMerge = -1;
        var bestMergeCost = Double.MAX_VALUE;
        for (var index = 0; index < count; index++) {
            m.clear();
            m.merge = true;
            m.mergeIdx = index;
            m.partMode = partMode;
            motion.derive(x0, y0, size, xP, yP, width, height, partIdx, m, this);
            candDir[index] = (m.predFlag[0] ? 1 : 0) | (m.predFlag[1] ? 2 : 0);
            for (var list = 0; list < 2; list++) {
                candRef[list][index] = m.predFlag[list] ? m.refIdx[list] : -1;
                candMv[list][index] = m.predFlag[list] ? (m.mvY[list] << 16) | (m.mvX[list] & 0xFFFF) : 0;
            }
            var cost = roughCost(xP, yP, width, height, candDir[index], candRef[0][index], candMv[0][index],
                    candRef[1][index], candMv[1][index]) + search.bitCost(1 + Math.min(index + 1, count - 1));
            if (cost < bestMergeCost) {
                bestMergeCost = cost;
                bestMerge = index;
            }
        }
        var lists = bSlice ? 2 : 1;
        bestCost[0] = Double.MAX_VALUE;
        bestCost[1] = Double.MAX_VALUE;
        var subpel = s.speed == HevcEncoderSpeed.FASTEST ? 1 : 2;
        var range = s.speed == HevcEncoderSpeed.SLOW ? 32 : 16;
        var w = s.codedWidth;
        var stride = s.codedWidth + 2 * MotionSearch.PAD;
        for (var list = 0; list < lists; list++) {
            var refs = Math.min(searchRefs, header.numRefIdx[list]);
            for (var ref = 0; ref < refs; ref++) {
                var mvp0 = partPredictor(x0, y0, size, xP, yP, width, height, partIdx, partMode, list, ref, 0);
                var mvp1 = partPredictor(x0, y0, size, xP, yP, width, height, partIdx, partMode, list, ref, 1);
                var startCount = 0;
                starts[startCount++] = mvp0;
                starts[startCount++] = mvp1;
                starts[startCount++] = 0;
                for (var index = 0; index < count && startCount < starts.length; index++) {
                    if (candRef[list][index] == ref) {
                        starts[startCount++] = candMv[list][index];
                    }
                }
                search.search(d.sourceY, w, xP, yP, width, height, refPlanes[list][ref], stride, starts, startCount,
                        (short) mvp0, mvp0 >> 16, range, subpel, found);
                var mv = found[0];
                var cost0 = search.mvCost((short) mv, mv >> 16, (short) mvp0, mvp0 >> 16);
                var cost1 = search.mvCost((short) mv, mv >> 16, (short) mvp1, mvp1 >> 16);
                var mvpFlag = cost1 < cost0 ? 1 : 0;
                var satd = found[1] - cost0;
                var mvp = mvpFlag == 0 ? mvp0 : mvp1;
                var bits = MotionSearch.componentBits((short) mv - (short) mvp)
                        + MotionSearch.componentBits((mv >> 16) - (mvp >> 16))
                        + (header.numRefIdx[list] > 1 ? ref + 1 : 0) + 2;
                var cost = satd + search.bitCost(bits);
                if (cost < bestCost[list]) {
                    bestCost[list] = cost;
                    bestMv[list] = mv;
                    bestRef[list] = ref;
                    bestMvp[list] = mvpFlag;
                    bestBits[list] = bits;
                }
            }
        }
        var dir = bSlice && bestCost[1] < bestCost[0] ? 1 : 0;
        var amvpCost = bestCost[dir];
        if (bSlice && width + height != 12) {
            var bi = roughCost(xP, yP, width, height, 3, bestRef[0], bestMv[0], bestRef[1], bestMv[1])
                    + search.bitCost(bestBits[0] + bestBits[1]);
            if (bi < amvpCost) {
                dir = 2;
                amvpCost = bi;
            }
        }
        pu.clear();
        pu.partMode = partMode;
        if (bestMerge >= 0 && bestMergeCost <= amvpCost) {
            pu.merge = true;
            pu.mergeIdx = bestMerge;
            for (var list = 0; list < 2; list++) {
                pu.predFlag[list] = (candDir[bestMerge] & (1 << list)) != 0;
                pu.refIdx[list] = candRef[list][bestMerge];
                pu.mvX[list] = (short) candMv[list][bestMerge];
                pu.mvY[list] = candMv[list][bestMerge] >> 16;
            }
            storeMotion(xP, yP, width, height, PictureData.PU_MERGE | (bestMerge << PictureData.PU_MERGE_IDX_SHIFT),
                    0, 0);
        } else {
            var mvd = this.mvd;
            mvd[0] = 0;
            mvd[1] = 0;
            for (var list = 0; list < 2; list++) {
                if (dir != 2 && dir != list) {
                    continue;
                }
                var mvp = partPredictor(x0, y0, size, xP, yP, width, height, partIdx, partMode, list, bestRef[list],
                        bestMvp[list]);
                pu.predFlag[list] = true;
                pu.refIdx[list] = bestRef[list];
                pu.mvX[list] = (short) bestMv[list];
                pu.mvY[list] = bestMv[list] >> 16;
                mvd[list] = (((bestMv[list] >> 16) - (mvp >> 16)) << 16)
                        | (((short) bestMv[list] - (short) mvp) & 0xFFFF);
            }
            storeMotion(xP, yP, width, height, (dir << PictureData.PU_DIR_SHIFT)
                    | (bestMvp[0] << PictureData.PU_MVP_SHIFT) | (bestMvp[1] << (PictureData.PU_MVP_SHIFT + 1)),
                    mvd[0], mvd[1]);
        }
        inter.predict(d.frame, xP, yP, width, height, pu);
    }

    /**
     * Returns a motion vector predictor of a prediction unit of a partitioned coding unit (8.5.3.2.6).
     *
     * @param x0 the luma x of the coding block
     * @param y0 the luma y of the coding block
     * @param size the coding block size
     * @param xP the luma x of the prediction block
     * @param yP the luma y of the prediction block
     * @param width the prediction block width
     * @param height the prediction block height
     * @param partIdx the index of the prediction unit
     * @param partMode the partitioning mode
     * @param list the reference list
     * @param ref the reference index
     * @param flag {@code mvp_lX_flag}
     * @return the predictor, packed as {@code (y << 16) | (x & 0xFFFF)}
     */
    private int partPredictor(int x0, int y0, int size, int xP, int yP, int width, int height, int partIdx,
                              int partMode, int list, int ref, int flag) {
        var m = probe;
        m.clear();
        m.partMode = partMode;
        m.predFlag[list] = true;
        m.refIdx[list] = ref;
        m.mvpFlag[list] = flag;
        motion.derive(x0, y0, size, xP, yP, width, height, partIdx, m, this);
        return (m.mvY[list] << 16) | (m.mvX[list] & 0xFFFF);
    }

    /**
     * Returns a motion vector predictor of a 2Nx2N prediction unit (8.5.3.2.6).
     *
     * @param x0 the luma x
     * @param y0 the luma y
     * @param size the block size
     * @param list the reference list
     * @param ref the reference index
     * @param flag {@code mvp_lX_flag}
     * @return the predictor, packed as {@code (y << 16) | (x & 0xFFFF)}
     */
    private int predictor(int x0, int y0, int size, int list, int ref, int flag) {
        var m = probe;
        m.clear();
        m.partMode = SyntaxCoder.PART_2Nx2N;
        m.predFlag[list] = true;
        m.refIdx[list] = ref;
        m.mvpFlag[list] = flag;
        motion.derive(x0, y0, size, x0, y0, size, size, 0, m, this);
        return (m.mvY[list] << 16) | (m.mvX[list] & 0xFFFF);
    }

    /**
     * Records the loop filter information of a decided coding tree unit: the transform and prediction
     * block edges, the coded luma blocks and the quantizer.
     *
     * @param x0 the luma x of the coding tree block
     * @param y0 the luma y of the coding tree block
     */
    private void finish(int x0, int y0) {
        var size = sps.ctbSize;
        var width = Math.min(size, s.codedWidth - x0);
        var height = Math.min(size, s.codedHeight - y0);
        var w4 = pic.width4;
        var start = d.block(x0, y0);
        for (var j = 0; j < height >> 2; j++) {
            var row = start + j * w4;
            Arrays.fill(pic.edges, row, row + (width >> 2), (byte) 0);
            Arrays.fill(pic.qp, row, row + (width >> 2), (byte) qpY);
            for (var i = 0; i < width >> 2; i++) {
                var flags = pic.flags[row + i] & ~PictureState.CODED;
                if ((d.tuInfo[row + i] & PictureData.TU_CBF_Y) != 0) {
                    flags |= PictureState.CODED;
                }
                pic.flags[row + i] = (byte) flags;
            }
        }
        markTree(x0, y0, sps.log2CtbSize, 0);
    }

    /**
     * Marks the edges of the coding units of a coding quadtree node.
     *
     * @param x0 the luma x
     * @param y0 the luma y
     * @param log2Size the node size as a power of two
     * @param depth the coding tree depth
     */
    private void markTree(int x0, int y0, int log2Size, int depth) {
        if (x0 >= s.codedWidth || y0 >= s.codedHeight) {
            return;
        }
        var size = 1 << log2Size;
        if (pic.depth[d.block(x0, y0)] > depth) {
            var half = size >> 1;
            markTree(x0, y0, log2Size - 1, depth + 1);
            markTree(x0 + half, y0, log2Size - 1, depth + 1);
            markTree(x0, y0 + half, log2Size - 1, depth + 1);
            markTree(x0 + half, y0 + half, log2Size - 1, depth + 1);
            return;
        }
        markEdges(x0, y0, size, size, PictureState.EDGE_TU_V | PictureState.EDGE_PU_V,
                PictureState.EDGE_TU_H | PictureState.EDGE_PU_H);
        var block = d.block(x0, y0);
        var partMode = d.cuInfo[block] & PictureData.CU_PART_MASK;
        if ((pic.flags[block] & PictureState.INTRA) == 0 && partMode != SyntaxCoder.PART_2Nx2N) {
            var half = size >> 1;
            if (partMode == SyntaxCoder.PART_2NxN) {
                markEdges(x0, y0 + half, size, half, PictureState.EDGE_PU_V, PictureState.EDGE_PU_H);
            } else {
                markEdges(x0 + half, y0, half, size, PictureState.EDGE_PU_V, PictureState.EDGE_PU_H);
            }
        }
        if ((d.tuInfo[block] & PictureData.TU_DEPTH_MASK) > 0) {
            var half = size >> 1;
            for (var i = 0; i < 4; i++) {
                markEdges(x0 + (i & 1) * half, y0 + (i >> 1) * half, half, half, PictureState.EDGE_TU_V,
                        PictureState.EDGE_TU_H);
            }
        }
    }

    /**
     * Records block boundaries on the left and top edges of a block.
     *
     * @param x0 the luma x
     * @param y0 the luma y
     * @param width the block width
     * @param height the block height
     * @param vertical the flags of the left edge
     * @param horizontal the flags of the top edge
     */
    private void markEdges(int x0, int y0, int width, int height, int vertical, int horizontal) {
        var w4 = pic.width4;
        var start = d.block(x0, y0);
        for (var j = 0; j < height >> 2; j++) {
            pic.edges[start + j * w4] |= (byte) vertical;
        }
        for (var i = 0; i < width >> 2; i++) {
            pic.edges[start + i] |= (byte) horizontal;
        }
    }
}
