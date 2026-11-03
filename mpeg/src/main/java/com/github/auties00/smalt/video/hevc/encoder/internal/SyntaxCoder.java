package com.github.auties00.smalt.video.hevc.encoder.internal;

import com.github.auties00.smalt.video.hevc.internal.CabacTables;
import com.github.auties00.smalt.video.hevc.internal.HevcPps;
import com.github.auties00.smalt.video.hevc.internal.HevcSliceHeader;
import com.github.auties00.smalt.video.hevc.internal.HevcSps;
import com.github.auties00.smalt.video.hevc.internal.NeighborAvailability;
import com.github.auties00.smalt.video.hevc.internal.PictureState;
import com.github.auties00.smalt.video.hevc.internal.ScanTables;

/**
 * Writes the coding tree unit syntax of ITU-T H.265 7.3.8 from the decisions of a {@link PictureData}, bin by
 * bin, with the context selection of 9.3.4.2: the sample adaptive offset parameters, the coding quadtree, the
 * coding and prediction units, the transform tree and the residual coding.
 *
 * <p>The same walk feeds a {@link CabacWriter}, which produces the slice data, or a {@link CabacCounter},
 * which follows the context variables of the coding and estimates bits; the residual and prediction syntax
 * of a candidate is also coded alone, for the cost estimates of the mode decision. Instances are not
 * thread-safe.
 */
final class SyntaxCoder implements NeighborAvailability {
    /**
     * {@code ctxIdxMap} of Table 9-50, the significance context of each position of a 4x4 block.
     */
    private static final int[] CTX_IDX_MAP = {0, 1, 4, 5, 2, 3, 4, 5, 6, 6, 8, 8, 7, 7, 8, 8};

    /**
     * {@code sigCtx} of every position of a 4x4 sub-block by scan position, indexed by {@link #sigBase} plus
     * the scan position.
     */
    private static final byte[] SIG_CTX_BY_SCAN = new byte[2 * 4 * 3 * 4 * 2 * 16];

    static {
        for (var chroma = 0; chroma < 2; chroma++) {
            for (var log2Size = 2; log2Size <= 5; log2Size++) {
                for (var scanIdx = 0; scanIdx < 3; scanIdx++) {
                    for (var prevCsbf = 0; prevCsbf < 4; prevCsbf++) {
                        for (var dcSub = 0; dcSub < 2; dcSub++) {
                            var base = sigBase(chroma, log2Size, scanIdx, prevCsbf, dcSub == 1);
                            var order = ScanTables.BY_INDEX[scanIdx][2];
                            for (var n = 0; n < 16; n++) {
                                SIG_CTX_BY_SCAN[base + n] = (byte) sigContext(chroma, log2Size, prevCsbf,
                                        dcSub == 1, order[n] & 0xFF, order[n] >> 8, scanIdx);
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * The partitioning mode {@code PART_2Nx2N}.
     */
    static final int PART_2Nx2N = 0;

    /**
     * The partitioning mode {@code PART_2NxN}.
     */
    static final int PART_2NxN = 1;

    /**
     * The partitioning mode {@code PART_Nx2N}.
     */
    static final int PART_Nx2N = 2;

    /**
     * The partitioning mode {@code PART_NxN}.
     */
    static final int PART_NxN = 3;

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
     * The coder bins go to.
     */
    private BinCoder coder;

    /**
     * Whether the coding unit being coded is intra.
     */
    private boolean cuIntra;

    /**
     * {@code IntraSplitFlag} of the coding unit being coded.
     */
    private boolean intraSplit;

    /**
     * Whether the inter coding unit being coded has more than one prediction unit.
     */
    private boolean puPartitioned;

    /**
     * {@code MaxTrafoDepth} of the coding unit being coded.
     */
    private int maxTrafoDepth;

    /**
     * {@code IntraPredModeC} of the coding unit being coded.
     */
    private int chromaMode;

    /**
     * The coded sub-block flags of the residual block being coded, indexed {@code yS * 8 + xS}.
     */
    private final boolean[] codedSubBlocks = new boolean[64];

    /**
     * The absolute levels of the significant coefficients of a sub-block, in coding order.
     */
    private final int[] absLevels = new int[16];

    /**
     * The signs of the significant coefficients of a sub-block, in coding order, 1 for negative.
     */
    private final int[] signs = new int[16];

    /**
     * The scan positions of the significant coefficients of a sub-block, in coding order.
     */
    private final int[] positions = new int[16];

    /**
     * Constructs a coder.
     */
    SyntaxCoder() {
    }

    /**
     * Prepares the coder for a picture.
     *
     * @param data the picture, whose header is set
     */
    void setPicture(PictureData data) {
        this.d = data;
        this.header = data.header;
        this.sps = header.sps;
        this.pps = header.pps;
        this.pic = data.pic;
    }

    /**
     * Sets the coder bins go to.
     *
     * @param coder the arithmetic coder or the bit counter
     */
    void setCoder(BinCoder coder) {
        this.coder = coder;
    }

    @Override
    public boolean available(int xCurr, int yCurr, int xNb, int yNb) {
        if (xNb < 0 || yNb < 0 || xNb >= sps.width || yNb >= sps.height) {
            return false;
        }
        var log2 = sps.log2MinTbSize;
        var zs = pps.minTbAddrZs;
        var stride = pps.widthInMinTbs;
        return zs[(yNb >> log2) * stride + (xNb >> log2)] <= zs[(yCurr >> log2) * stride + (xCurr >> log2)];
    }

    /**
     * Codes one coding tree unit (7.3.8.2).
     *
     * @param ctbAddr the raster address of the coding tree block
     * @param sao whether to code its sample adaptive offset parameters
     */
    void codingTreeUnit(int ctbAddr, boolean sao) {
        var widthCtbs = sps.widthInCtbs;
        var rx = ctbAddr % widthCtbs;
        var ry = ctbAddr / widthCtbs;
        if (sao && (header.saoLuma || header.saoChroma)) {
            sao(ctbAddr, rx, ry);
        }
        codingQuadtree(rx << sps.log2CtbSize, ry << sps.log2CtbSize, sps.log2CtbSize, 0);
    }

    /**
     * Codes the sample adaptive offset parameters of a coding tree block (7.3.8.3).
     *
     * @param ctbAddr the raster address
     * @param rx the coding tree block column
     * @param ry the coding tree block row
     */
    private void sao(int ctbAddr, int rx, int ry) {
        var merge = d.saoMerge[ctbAddr];
        if (rx > 0) {
            coder.bin(CabacTables.SAO_MERGE, merge == 1 ? 1 : 0);
            if (merge == 1) {
                return;
            }
        }
        if (ry > 0) {
            coder.bin(CabacTables.SAO_MERGE, merge == 2 ? 1 : 0);
            if (merge == 2) {
                return;
            }
        }
        var size = sps.sizeInCtbs;
        for (var c = 0; c < 3; c++) {
            if (!(c == 0 ? header.saoLuma : header.saoChroma)) {
                continue;
            }
            var index = c * size + ctbAddr;
            var type = pic.saoType[index];
            if (c < 2) {
                coder.bin(CabacTables.SAO_TYPE, type != 0 ? 1 : 0);
                if (type != 0) {
                    coder.bypass(type == 1 ? 0 : 1);
                }
            }
            if (type == 0) {
                continue;
            }
            var bitDepth = c == 0 ? sps.bitDepthLuma : sps.bitDepthChroma;
            var cMax = (1 << (Math.min(bitDepth, 10) - 5)) - 1;
            for (var i = 0; i < 4; i++) {
                var value = Math.abs(pic.saoOffset[index * 4 + i]);
                for (var k = 0; k < value; k++) {
                    coder.bypass(1);
                }
                if (value < cMax) {
                    coder.bypass(0);
                }
            }
            if (type == 1) {
                for (var i = 0; i < 4; i++) {
                    var value = pic.saoOffset[index * 4 + i];
                    if (value != 0) {
                        coder.bypass(value < 0 ? 1 : 0);
                    }
                }
                coder.bypassBins(pic.saoClass[index], 5);
            } else if (c < 2) {
                coder.bypassBins(pic.saoClass[index], 2);
            }
        }
    }

    /**
     * Codes a coding quadtree (7.3.8.4).
     *
     * @param x0 the luma x
     * @param y0 the luma y
     * @param log2Size the block size as a power of two
     * @param depth {@code cqtDepth}
     */
    private void codingQuadtree(int x0, int y0, int log2Size, int depth) {
        var size = 1 << log2Size;
        boolean split;
        if (x0 + size <= sps.width && y0 + size <= sps.height && log2Size > sps.log2MinCbSize) {
            split = pic.depth[d.block(x0, y0)] > depth;
            coder.bin(CabacTables.SPLIT_CU + splitContext(x0, y0, depth), split ? 1 : 0);
        } else {
            split = log2Size > sps.log2MinCbSize;
        }
        if (split) {
            var half = size >> 1;
            var x1 = x0 + half;
            var y1 = y0 + half;
            codingQuadtree(x0, y0, log2Size - 1, depth + 1);
            if (x1 < sps.width) {
                codingQuadtree(x1, y0, log2Size - 1, depth + 1);
            }
            if (y1 < sps.height) {
                codingQuadtree(x0, y1, log2Size - 1, depth + 1);
            }
            if (x1 < sps.width && y1 < sps.height) {
                codingQuadtree(x1, y1, log2Size - 1, depth + 1);
            }
        } else {
            codingUnit(x0, y0, log2Size);
        }
    }

    /**
     * Returns the context increment of {@code split_cu_flag} (9.3.4.2.2).
     *
     * @param x0 the luma x of the block
     * @param y0 the luma y of the block
     * @param depth the coding tree depth of the block
     * @return the increment, 0 to 2
     */
    int splitContext(int x0, int y0, int depth) {
        var ctx = 0;
        if (available(x0, y0, x0 - 1, y0) && pic.depth[d.block(x0 - 1, y0)] > depth) {
            ctx++;
        }
        if (available(x0, y0, x0, y0 - 1) && pic.depth[d.block(x0, y0 - 1)] > depth) {
            ctx++;
        }
        return ctx;
    }

    /**
     * Returns the context increment of {@code cu_skip_flag} (9.3.4.2.2).
     *
     * @param x0 the luma x of the coding block
     * @param y0 the luma y of the coding block
     * @return the increment, 0 to 2
     */
    int skipContext(int x0, int y0) {
        var ctx = 0;
        if (available(x0, y0, x0 - 1, y0) && (pic.flags[d.block(x0 - 1, y0)] & PictureState.SKIP) != 0) {
            ctx++;
        }
        if (available(x0, y0, x0, y0 - 1) && (pic.flags[d.block(x0, y0 - 1)] & PictureState.SKIP) != 0) {
            ctx++;
        }
        return ctx;
    }

    /**
     * Codes a coding unit (7.3.8.5).
     *
     * @param x0 the luma x
     * @param y0 the luma y
     * @param log2Size the coding block size as a power of two
     */
    void codingUnit(int x0, int y0, int log2Size) {
        var size = 1 << log2Size;
        var block = d.block(x0, y0);
        var flags = pic.flags[block];
        var skip = (flags & PictureState.SKIP) != 0;
        if (header.sliceType != HevcSliceHeader.I) {
            coder.bin(CabacTables.CU_SKIP + skipContext(x0, y0), skip ? 1 : 0);
        }
        if (skip) {
            predictionUnit(x0, y0, size, size, true);
            return;
        }
        cuIntra = (flags & PictureState.INTRA) != 0;
        if (header.sliceType != HevcSliceHeader.I) {
            coder.bin(CabacTables.PRED_MODE, cuIntra ? 1 : 0);
        }
        var info = d.cuInfo[block];
        var partMode = info & PictureData.CU_PART_MASK;
        if (!cuIntra || log2Size == sps.log2MinCbSize) {
            partMode(partMode, log2Size);
        }
        intraSplit = cuIntra && partMode == PART_NxN;
        puPartitioned = !cuIntra && partMode != PART_2Nx2N;
        var mergeAll = false;
        if (cuIntra) {
            intraModes(x0, y0, log2Size, partMode, info >> PictureData.CU_CHROMA_SHIFT);
        } else {
            var half = size >> 1;
            switch (partMode) {
                case PART_2Nx2N -> predictionUnit(x0, y0, size, size, false);
                case PART_2NxN -> {
                    predictionUnit(x0, y0, size, half, false);
                    predictionUnit(x0, y0 + half, size, half, false);
                }
                default -> {
                    predictionUnit(x0, y0, half, size, false);
                    predictionUnit(x0 + half, y0, half, size, false);
                }
            }
            mergeAll = partMode == PART_2Nx2N && (d.puInfo[block] & PictureData.PU_MERGE) != 0;
        }
        var rootCbf = true;
        if (!cuIntra && !mergeAll) {
            rootCbf = anyCoded(x0, y0, size);
            coder.bin(CabacTables.RQT_ROOT_CBF, rootCbf ? 1 : 0);
        }
        if (rootCbf) {
            maxTrafoDepth = cuIntra ? sps.maxTransformHierarchyDepthIntra + (intraSplit ? 1 : 0)
                    : sps.maxTransformHierarchyDepthInter;
            transformTree(x0, y0, x0, y0, log2Size, 0, 0, true, true);
        }
    }

    /**
     * Returns whether any transform block of a coding unit has coefficients.
     *
     * @param x0 the luma x
     * @param y0 the luma y
     * @param size the coding block size
     * @return {@code true} when a coded block flag is set
     */
    private boolean anyCoded(int x0, int y0, int size) {
        var mask = PictureData.TU_CBF_Y | PictureData.TU_CBF_CB | PictureData.TU_CBF_CR;
        return (orTuFlags(x0, y0, size) & mask) != 0;
    }

    /**
     * Returns the union of the {@link PictureData#tuInfo} flags over a square block.
     *
     * @param x0 the luma x
     * @param y0 the luma y
     * @param size the block size
     * @return the flags
     */
    private int orTuFlags(int x0, int y0, int size) {
        var w4 = pic.width4;
        var start = d.block(x0, y0);
        var blocks = size >> 2;
        var flags = 0;
        for (var j = 0; j < blocks; j++) {
            var row = start + j * w4;
            for (var i = 0; i < blocks; i++) {
                flags |= d.tuInfo[row + i];
            }
        }
        return flags;
    }

    /**
     * Codes {@code part_mode} (Table 9-43, 9.3.4.2.2) without asymmetric partitions.
     *
     * @param partMode the partitioning mode
     * @param log2Size the coding block size as a power of two
     */
    void partMode(int partMode, int log2Size) {
        coder.bin(CabacTables.PART_MODE, partMode == PART_2Nx2N ? 1 : 0);
        if (partMode == PART_2Nx2N || cuIntra) {
            return;
        }
        if (log2Size == sps.log2MinCbSize) {
            coder.bin(CabacTables.PART_MODE + 1, partMode == PART_2NxN ? 1 : 0);
            if (partMode != PART_2NxN && log2Size > 3) {
                coder.bin(CabacTables.PART_MODE + 2, partMode == PART_Nx2N ? 1 : 0);
            }
            return;
        }
        coder.bin(CabacTables.PART_MODE + 1, partMode == PART_2NxN ? 1 : 0);
    }

    /**
     * Sets whether the coding unit whose syntax the next calls code is intra; for the cost estimates of
     * single syntax elements.
     *
     * @param intra whether the coding unit is intra
     */
    void setIntra(boolean intra) {
        this.cuIntra = intra;
    }

    /**
     * Codes the intra prediction modes of a coding unit (7.3.8.5): the luma modes of its prediction blocks,
     * whose candidates are derived from the modes already in {@link PictureState#intraMode}, then the chroma
     * mode.
     *
     * @param x0 the luma x of the coding block
     * @param y0 the luma y of the coding block
     * @param log2Size the coding block size as a power of two
     * @param partMode the partitioning mode
     * @param chromaSyntax {@code intra_chroma_pred_mode}, 0 to 4
     */
    private void intraModes(int x0, int y0, int log2Size, int partMode, int chromaSyntax) {
        var size = 1 << log2Size;
        var count = partMode == PART_NxN ? 4 : 1;
        var offset = partMode == PART_NxN ? size >> 1 : size;
        var mpm = mpmIndices;
        var modes = lumaModes;
        for (var i = 0; i < count; i++) {
            var xPb = x0 + (i & 1) * offset;
            var yPb = y0 + (i >> 1) * offset;
            modes[i] = pic.intraMode[d.block(xPb, yPb)];
            mpm[i] = mpmIndex(xPb, yPb, modes[i], candidates);
            coder.bin(CabacTables.PREV_INTRA_LUMA, mpm[i] >= 0 ? 1 : 0);
        }
        for (var i = 0; i < count; i++) {
            var xPb = x0 + (i & 1) * offset;
            var yPb = y0 + (i >> 1) * offset;
            if (mpm[i] >= 0) {
                coder.bypass(mpm[i] > 0 ? 1 : 0);
                if (mpm[i] > 0) {
                    coder.bypass(mpm[i] > 1 ? 1 : 0);
                }
            } else {
                mpmIndex(xPb, yPb, modes[i], candidates);
                coder.bypassBins(remainingMode(modes[i], candidates), 5);
            }
        }
        chromaMode(chromaSyntax);
    }

    /**
     * The three most probable modes of {@link #mpmIndex}.
     */
    private final int[] candidates = new int[3];

    /**
     * The {@code mpm_idx} of each prediction block of the coding unit being coded, -1 for a remaining mode.
     */
    private final int[] mpmIndices = new int[4];

    /**
     * The luma mode of each prediction block of the coding unit being coded.
     */
    private final int[] lumaModes = new int[4];

    /**
     * Derives the most probable modes of a luma prediction block (8.4.2) and returns the index of a mode
     * among them.
     *
     * @param xPb the luma x of the prediction block
     * @param yPb the luma y of the prediction block
     * @param mode the intra prediction mode, 0 to 34
     * @param candidates receives {@code candModeList}, three entries
     * @return {@code mpm_idx}, or -1 when the mode is not a most probable mode
     */
    int mpmIndex(int xPb, int yPb, int mode, int[] candidates) {
        var candA = candidateMode(xPb, yPb, xPb - 1, yPb, false);
        var candB = candidateMode(xPb, yPb, xPb, yPb - 1, true);
        if (candA == candB) {
            if (candA < 2) {
                candidates[0] = 0;
                candidates[1] = 1;
                candidates[2] = 26;
            } else {
                candidates[0] = candA;
                candidates[1] = 2 + ((candA + 29) % 32);
                candidates[2] = 2 + ((candA - 2 + 1) % 32);
            }
        } else {
            candidates[0] = candA;
            candidates[1] = candB;
            if (candA != 0 && candB != 0) {
                candidates[2] = 0;
            } else if (candA != 1 && candB != 1) {
                candidates[2] = 1;
            } else {
                candidates[2] = 26;
            }
        }
        for (var i = 0; i < 3; i++) {
            if (candidates[i] == mode) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Returns {@code rem_intra_luma_pred_mode} of a mode that is not a most probable mode.
     *
     * @param mode the intra prediction mode, 0 to 34
     * @param candidates the most probable modes
     * @return the remaining mode, 0 to 31
     */
    static int remainingMode(int mode, int[] candidates) {
        var value = mode;
        for (var i = 0; i < 3; i++) {
            if (candidates[i] < mode) {
                value--;
            }
        }
        return value;
    }

    /**
     * Returns {@code candIntraPredModeX} of a neighbor (8.4.2).
     *
     * @param xPb the luma x of the prediction block
     * @param yPb the luma y of the prediction block
     * @param xNb the luma x of the neighbor
     * @param yNb the luma y of the neighbor
     * @param above whether the neighbor is the one above
     * @return the candidate mode
     */
    private int candidateMode(int xPb, int yPb, int xNb, int yNb, boolean above) {
        if (!available(xPb, yPb, xNb, yNb)) {
            return 1;
        }
        var index = d.block(xNb, yNb);
        if ((pic.flags[index] & PictureState.INTRA) == 0) {
            return 1;
        }
        if (above && yNb < ((yPb >> sps.log2CtbSize) << sps.log2CtbSize)) {
            return 1;
        }
        return pic.intraMode[index];
    }

    /**
     * Codes {@code intra_chroma_pred_mode} (Table 9-43).
     *
     * @param syntax the value, 0 to 4
     */
    void chromaMode(int syntax) {
        coder.bin(CabacTables.INTRA_CHROMA, syntax == 4 ? 0 : 1);
        if (syntax != 4) {
            coder.bypassBins(syntax, 2);
        }
    }

    /**
     * Returns {@code IntraPredModeC} of 4:2:0 video (8.4.3, Table 8-2).
     *
     * @param syntax {@code intra_chroma_pred_mode}
     * @param luma {@code IntraPredModeY} of the first prediction block
     * @return the chroma mode
     */
    static int chromaPredMode(int syntax, int luma) {
        if (syntax == 4) {
            return luma;
        }
        var candidate = syntax == 0 ? 0 : syntax == 1 ? 26 : syntax == 2 ? 10 : 1;
        return candidate == luma ? 34 : candidate;
    }

    /**
     * Codes a prediction unit (7.3.8.6) from the decisions stored for it.
     *
     * @param xPb the luma x of the prediction block
     * @param yPb the luma y of the prediction block
     * @param width the prediction block width
     * @param height the prediction block height
     * @param skip whether the coding unit is skipped
     */
    private void predictionUnit(int xPb, int yPb, int width, int height, boolean skip) {
        var block = d.block(xPb, yPb);
        var info = d.puInfo[block];
        var merge = (info & PictureData.PU_MERGE) != 0;
        if (!skip) {
            coder.bin(CabacTables.MERGE_FLAG, merge ? 1 : 0);
        }
        if (merge) {
            mergeIndex((info >> PictureData.PU_MERGE_IDX_SHIFT) & 7);
            return;
        }
        var dir = (info >> PictureData.PU_DIR_SHIFT) & 3;
        if (header.sliceType == HevcSliceHeader.B) {
            interPredIdc(dir, width + height, pic.depth[block]);
        }
        if (dir != 1) {
            refIdx(d.frame.ref0[block], header.numRefIdx[0]);
            mvd(d.mvd0[block]);
            coder.bin(CabacTables.MVP_FLAG, (info >> PictureData.PU_MVP_SHIFT) & 1);
        }
        if (dir != 0) {
            refIdx(d.frame.ref1[block], header.numRefIdx[1]);
            mvd(d.mvd1[block]);
            coder.bin(CabacTables.MVP_FLAG, (info >> (PictureData.PU_MVP_SHIFT + 1)) & 1);
        }
    }

    /**
     * Codes {@code merge_idx} (truncated Rice, the first bin context-coded).
     *
     * @param index the merge index
     */
    void mergeIndex(int index) {
        var max = header.maxNumMergeCand - 1;
        if (max <= 0) {
            return;
        }
        coder.bin(CabacTables.MERGE_IDX, index > 0 ? 1 : 0);
        if (index == 0) {
            return;
        }
        for (var k = 1; k < max; k++) {
            coder.bypass(index > k ? 1 : 0);
            if (index == k) {
                return;
            }
        }
    }

    /**
     * Codes {@code inter_pred_idc} (9.3.4.2.2).
     *
     * @param dir 0 for list 0, 1 for list 1, 2 for both
     * @param sides the prediction block width plus its height
     * @param depth the coding tree depth of the coding unit
     */
    void interPredIdc(int dir, int sides, int depth) {
        if (sides != 12) {
            coder.bin(CabacTables.INTER_PRED_IDC + depth, dir == 2 ? 1 : 0);
            if (dir == 2) {
                return;
            }
        }
        coder.bin(CabacTables.INTER_PRED_IDC + 4, dir);
    }

    /**
     * Codes a {@code ref_idx_lX} (truncated Rice, the first two bins context-coded).
     *
     * @param index the reference index
     * @param count {@code num_ref_idx_lX_active_minus1 + 1}
     */
    void refIdx(int index, int count) {
        if (count <= 1) {
            return;
        }
        var max = count - 1;
        for (var k = 0; k < max; k++) {
            var bin = index > k ? 1 : 0;
            if (k < 2) {
                coder.bin(CabacTables.REF_IDX + k, bin);
            } else {
                coder.bypass(bin);
            }
            if (bin == 0) {
                return;
            }
        }
    }

    /**
     * Codes an {@code mvd_coding()} structure (7.3.8.9).
     *
     * @param mvd the difference, packed as {@code (y << 16) | (x & 0xFFFF)}
     */
    void mvd(int mvd) {
        var x = (short) mvd;
        var y = mvd >> 16;
        var ax = Math.abs(x);
        var ay = Math.abs(y);
        coder.bin(CabacTables.MVD_GREATER0, ax > 0 ? 1 : 0);
        coder.bin(CabacTables.MVD_GREATER0, ay > 0 ? 1 : 0);
        if (ax > 0) {
            coder.bin(CabacTables.MVD_GREATER1, ax > 1 ? 1 : 0);
        }
        if (ay > 0) {
            coder.bin(CabacTables.MVD_GREATER1, ay > 1 ? 1 : 0);
        }
        if (ax > 0) {
            if (ax > 1) {
                expGolomb(ax - 2, 1);
            }
            coder.bypass(x < 0 ? 1 : 0);
        }
        if (ay > 0) {
            if (ay > 1) {
                expGolomb(ay - 2, 1);
            }
            coder.bypass(y < 0 ? 1 : 0);
        }
    }

    /**
     * Codes a k-th order Exp-Golomb bypass code (9.3.3.3).
     *
     * @param value the value, non-negative
     * @param k the order
     */
    private void expGolomb(int value, int k) {
        var abs = value;
        while (abs >= (1 << k)) {
            coder.bypass(1);
            abs -= 1 << k;
            k++;
        }
        coder.bypass(0);
        coder.bypassBins(abs, k);
    }

    /**
     * Codes a transform tree (7.3.8.8) from the stored transform decisions.
     *
     * @param x0 the luma x
     * @param y0 the luma y
     * @param xBase the luma x of the parent
     * @param yBase the luma y of the parent
     * @param log2Size the block size as a power of two
     * @param trafoDepth the depth
     * @param blkIdx the index within the parent
     * @param parentCb whether the parent's {@code cbf_cb} is set
     * @param parentCr whether the parent's {@code cbf_cr} is set
     */
    private void transformTree(int x0, int y0, int xBase, int yBase, int log2Size, int trafoDepth, int blkIdx,
                               boolean parentCb, boolean parentCr) {
        var size = 1 << log2Size;
        var block = d.block(x0, y0);
        boolean split;
        if (log2Size <= sps.log2MaxTbSize && log2Size > sps.log2MinTbSize && trafoDepth < maxTrafoDepth
                && !(intraSplit && trafoDepth == 0)) {
            split = (d.tuInfo[block] & PictureData.TU_DEPTH_MASK) > trafoDepth;
            coder.bin(CabacTables.SPLIT_TRANSFORM + 5 - log2Size, split ? 1 : 0);
        } else {
            var interSplit = sps.maxTransformHierarchyDepthInter == 0 && !cuIntra && trafoDepth == 0
                    && puPartitioned;
            split = log2Size > sps.log2MaxTbSize || (intraSplit && trafoDepth == 0) || interSplit;
        }
        boolean cbfCb;
        boolean cbfCr;
        if (log2Size > 2) {
            var flags = orTuFlags(x0, y0, size);
            cbfCb = false;
            cbfCr = false;
            if (trafoDepth == 0 || parentCb) {
                cbfCb = (flags & PictureData.TU_CBF_CB) != 0;
                coder.bin(CabacTables.CBF_CHROMA + trafoDepth, cbfCb ? 1 : 0);
            }
            if (trafoDepth == 0 || parentCr) {
                cbfCr = (flags & PictureData.TU_CBF_CR) != 0;
                coder.bin(CabacTables.CBF_CHROMA + trafoDepth, cbfCr ? 1 : 0);
            }
        } else {
            cbfCb = parentCb;
            cbfCr = parentCr;
        }
        if (split) {
            var half = size >> 1;
            transformTree(x0, y0, x0, y0, log2Size - 1, trafoDepth + 1, 0, cbfCb, cbfCr);
            transformTree(x0 + half, y0, x0, y0, log2Size - 1, trafoDepth + 1, 1, cbfCb, cbfCr);
            transformTree(x0, y0 + half, x0, y0, log2Size - 1, trafoDepth + 1, 2, cbfCb, cbfCr);
            transformTree(x0 + half, y0 + half, x0, y0, log2Size - 1, trafoDepth + 1, 3, cbfCb, cbfCr);
            return;
        }
        var cbfLuma = (d.tuInfo[block] & PictureData.TU_CBF_Y) != 0;
        if (cuIntra || trafoDepth != 0 || cbfCb || cbfCr) {
            coder.bin(CabacTables.CBF_LUMA + (trafoDepth == 0 ? 1 : 0), cbfLuma ? 1 : 0);
        }
        if (cbfLuma) {
            residual(d.levelY, d.s.codedWidth, y0 * d.s.codedWidth + x0, log2Size, 0,
                    scanIndex(log2Size, 0, cuIntra ? pic.intraMode[block] : -1));
        }
        var chromaModeHere = cuIntra ? chromaModeAt(block) : -1;
        if (log2Size > 2) {
            var offset = (y0 >> 1) * d.s.chromaWidth + (x0 >> 1);
            var scan = scanIndex(log2Size - 1, 1, chromaModeHere);
            if (cbfCb) {
                residual(d.levelCb, d.s.chromaWidth, offset, log2Size - 1, 1, scan);
            }
            if (cbfCr) {
                residual(d.levelCr, d.s.chromaWidth, offset, log2Size - 1, 2, scan);
            }
        } else if (blkIdx == 3) {
            var offset = (yBase >> 1) * d.s.chromaWidth + (xBase >> 1);
            var scan = scanIndex(2, 1, cuIntra ? chromaModeAt(d.block(xBase, yBase)) : -1);
            if (cbfCb) {
                residual(d.levelCb, d.s.chromaWidth, offset, 2, 1, scan);
            }
            if (cbfCr) {
                residual(d.levelCr, d.s.chromaWidth, offset, 2, 2, scan);
            }
        }
    }

    /**
     * Returns {@code IntraPredModeC} of the intra coding unit covering a 4x4 block.
     *
     * @param block the index of the 4x4 block
     * @return the chroma mode
     */
    private int chromaModeAt(int block) {
        var syntax = d.cuInfo[block] >> PictureData.CU_CHROMA_SHIFT;
        var first = firstLumaMode(block);
        return chromaPredMode(syntax, first);
    }

    /**
     * Returns the luma mode of the first prediction block of the coding unit covering a 4x4 block.
     *
     * @param block the index of the 4x4 block
     * @return the mode
     */
    private int firstLumaMode(int block) {
        // the coding unit's top-left 4x4 block: coding units are aligned to their size, and the depth gives it
        var size = sps.ctbSize >> pic.depth[block];
        var w4 = pic.width4;
        var bx = (block % w4) << 2;
        var by = (block / w4) << 2;
        var x0 = bx & ~(size - 1);
        var y0 = by & ~(size - 1);
        return pic.intraMode[d.block(x0, y0)];
    }

    /**
     * Returns {@code scanIdx} of a transform block (7.4.9.11).
     *
     * @param log2Size the transform block size of the component as a power of two
     * @param cIdx the component
     * @param predMode the intra prediction mode of the block, or -1 for inter blocks
     * @return 0 diagonal, 1 horizontal, 2 vertical
     */
    static int scanIndex(int log2Size, int cIdx, int predMode) {
        if (predMode >= 0 && (log2Size == 2 || (log2Size == 3 && cIdx == 0))) {
            if (predMode >= 6 && predMode <= 14) {
                return 2;
            }
            if (predMode >= 22 && predMode <= 30) {
                return 1;
            }
        }
        return 0;
    }

    /**
     * Codes a {@code residual_coding()} structure (7.3.8.11) of a block with at least one non-zero level.
     *
     * @param levels the quantized levels
     * @param stride the row stride of {@code levels}
     * @param offset the index of the block's top-left level
     * @param log2Size the transform block size as a power of two
     * @param cIdx the component
     * @param scanIdx the scan order
     */
    void residual(short[] levels, int stride, int offset, int log2Size, int cIdx, int scanIdx) {
        var log2Sb = log2Size - 2;
        var sbScan = ScanTables.BY_INDEX[scanIdx][log2Sb];
        var posScan = ScanTables.BY_INDEX[scanIdx][2];
        var sbCount = 1 << (2 * log2Sb);
        var lastSubBlock = -1;
        var lastScanPos = -1;
        for (var i = sbCount - 1; i >= 0 && lastSubBlock < 0; i--) {
            var sb = sbScan[i];
            var xS = sb & 0xFF;
            var yS = sb >> 8;
            for (var n = 15; n >= 0; n--) {
                var pos = posScan[n];
                var xC = (xS << 2) + (pos & 0xFF);
                var yC = (yS << 2) + (pos >> 8);
                if (levels[offset + yC * stride + xC] != 0) {
                    lastSubBlock = i;
                    lastScanPos = n;
                    break;
                }
            }
        }
        var lastSb = sbScan[lastSubBlock];
        var lastPos = posScan[lastScanPos];
        var lastX = ((lastSb & 0xFF) << 2) + (lastPos & 0xFF);
        var lastY = ((lastSb >> 8) << 2) + (lastPos >> 8);
        if (scanIdx == 2) {
            var t = lastX;
            lastX = lastY;
            lastY = t;
        }
        int ctxOffset;
        int ctxShift;
        if (cIdx == 0) {
            ctxOffset = 3 * (log2Size - 2) + ((log2Size - 1) >> 2);
            ctxShift = (log2Size + 1) >> 2;
        } else {
            ctxOffset = 15;
            ctxShift = log2Size - 2;
        }
        var maxPrefix = (log2Size << 1) - 1;
        var prefixX = lastPrefix(lastX);
        var prefixY = lastPrefix(lastY);
        for (var k = 0; k < prefixX; k++) {
            coder.bin(CabacTables.LAST_X + ctxOffset + (k >> ctxShift), 1);
        }
        if (prefixX < maxPrefix) {
            coder.bin(CabacTables.LAST_X + ctxOffset + (prefixX >> ctxShift), 0);
        }
        for (var k = 0; k < prefixY; k++) {
            coder.bin(CabacTables.LAST_Y + ctxOffset + (k >> ctxShift), 1);
        }
        if (prefixY < maxPrefix) {
            coder.bin(CabacTables.LAST_Y + ctxOffset + (prefixY >> ctxShift), 0);
        }
        if (prefixX > 3) {
            var bits = (prefixX >> 1) - 1;
            coder.bypassBins(lastX - (1 << bits) * (2 + (prefixX & 1)), bits);
        }
        if (prefixY > 3) {
            var bits = (prefixY >> 1) - 1;
            coder.bypassBins(lastY - (1 << bits) * (2 + (prefixY & 1)), bits);
        }
        var sbWidth = 1 << log2Sb;
        java.util.Arrays.fill(codedSubBlocks, 0, 64, false);
        var greater1Ctx = 1;
        var signHiding = pps.signDataHiding;
        var sigOffset = CabacTables.SIG_COEFF + (cIdx == 0 ? 0 : 27);
        var chroma = cIdx == 0 ? 0 : 1;
        for (var i = lastSubBlock; i >= 0; i--) {
            var sb = sbScan[i];
            var xS = sb & 0xFF;
            var yS = sb >> 8;
            var count = 0;
            for (var n = (i == lastSubBlock ? lastScanPos : 15); n >= 0; n--) {
                var pos = posScan[n];
                var value = levels[offset + ((yS << 2) + (pos >> 8)) * stride + (xS << 2) + (pos & 0xFF)];
                if (value != 0) {
                    positions[count] = n;
                    absLevels[count] = Math.abs(value);
                    signs[count] = value < 0 ? 1 : 0;
                    count++;
                }
            }
            var inferSbDc = false;
            if (i < lastSubBlock && i > 0) {
                var csbfCtx = 0;
                if (xS < sbWidth - 1 && codedSubBlocks[yS * 8 + xS + 1]) {
                    csbfCtx++;
                }
                if (yS < sbWidth - 1 && codedSubBlocks[(yS + 1) * 8 + xS]) {
                    csbfCtx++;
                }
                coder.bin(CabacTables.CODED_SUB_BLOCK + (cIdx == 0 ? 0 : 2) + Math.min(csbfCtx, 1),
                        count > 0 ? 1 : 0);
                inferSbDc = true;
            }
            var coded = i == lastSubBlock || i == 0 || count > 0;
            codedSubBlocks[yS * 8 + xS] = coded;
            if (!coded) {
                continue;
            }
            var prevCsbf = 0;
            if (xS < sbWidth - 1 && codedSubBlocks[yS * 8 + xS + 1]) {
                prevCsbf |= 1;
            }
            if (yS < sbWidth - 1 && codedSubBlocks[(yS + 1) * 8 + xS]) {
                prevCsbf |= 2;
            }
            var contexts = sigBase(chroma, log2Size, scanIdx, prevCsbf, i == 0);
            var next = 0;
            var start = i == lastSubBlock ? lastScanPos - 1 : 15;
            if (i == lastSubBlock) {
                next = 1;
            }
            for (var n = start; n >= 0; n--) {
                var significant = next < count && positions[next] == n;
                if (n > 0 || !inferSbDc) {
                    coder.bin(sigOffset + SIG_CTX_BY_SCAN[contexts + n], significant ? 1 : 0);
                    if (significant) {
                        inferSbDc = false;
                    }
                }
                if (significant) {
                    next++;
                }
            }
            if (count == 0) {
                continue;
            }
            var ctxSet = (i == 0 || cIdx > 0) ? 0 : 2;
            if (greater1Ctx == 0) {
                ctxSet++;
            }
            greater1Ctx = 1;
            var gt1Base = CabacTables.GREATER1 + (cIdx == 0 ? 0 : 16) + ctxSet * 4;
            var firstGreater1 = -1;
            var numGreater1 = Math.min(count, 8);
            for (var k = 0; k < numGreater1; k++) {
                var flag = absLevels[k] > 1 ? 1 : 0;
                coder.bin(gt1Base + greater1Ctx, flag);
                if (flag == 1) {
                    greater1Ctx = 0;
                    if (firstGreater1 < 0) {
                        firstGreater1 = k;
                    }
                } else if (greater1Ctx > 0 && greater1Ctx < 3) {
                    greater1Ctx++;
                }
            }
            if (firstGreater1 >= 0) {
                coder.bin(CabacTables.GREATER2 + (cIdx == 0 ? 0 : 4) + ctxSet, absLevels[firstGreater1] > 2 ? 1 : 0);
            }
            var hidden = signHiding && positions[0] - positions[count - 1] > 3;
            var signBits = count - (hidden ? 1 : 0);
            var signValue = 0;
            for (var k = 0; k < signBits; k++) {
                signValue = (signValue << 1) | signs[k];
            }
            coder.bypassBins(signValue, signBits);
            var rice = 0;
            for (var k = 0; k < count; k++) {
                var abs = absLevels[k];
                var base = k < 8 ? (abs > 1 ? (k == firstGreater1 ? (abs > 2 ? 3 : 2) : 2) : 1) : 1;
                var threshold = k < 8 ? (k == firstGreater1 ? 3 : 2) : 1;
                if (base == threshold) {
                    var remaining = abs - base;
                    remaining(remaining, rice);
                    if (abs > (3 << rice)) {
                        rice = Math.min(rice + 1, 4);
                    }
                }
            }
        }
    }

    /**
     * Returns the prefix of {@code last_sig_coeff_x_prefix} or {@code last_sig_coeff_y_prefix} of a position.
     *
     * @param position the column or row of the last significant coefficient
     * @return the prefix
     */
    private static int lastPrefix(int position) {
        if (position < 4) {
            return position;
        }
        var bits = 31 - Integer.numberOfLeadingZeros(position) - 1;
        // position lies in [2^(bits+1), 2^(bits+2)): prefix 2 * bits + 2, plus one in the upper half
        var upper = position >= (3 << bits) ? 1 : 0;
        return 2 * bits + 2 + upper;
    }

    /**
     * Codes {@code coeff_abs_level_remaining} (9.3.3.11) as {@code TEncSbac::xWriteCoefRemainExGolomb} of the
     * HM reference encoder does.
     *
     * @param value the value, non-negative
     * @param rice {@code cRiceParam}
     */
    private void remaining(int value, int rice) {
        if (value < (3 << rice)) {
            var length = value >> rice;
            coder.bypassBins((1 << (length + 1)) - 2, length + 1);
            coder.bypassBins(value & ((1 << rice) - 1), rice);
            return;
        }
        var length = rice;
        var code = value - (3 << rice);
        while (code >= (1 << length)) {
            code -= 1 << length;
            length++;
        }
        var prefix = 3 + length + 1 - rice;
        coder.bypassBins((1 << prefix) - 2, prefix);
        coder.bypassBins(code, length);
    }

    /**
     * Returns the offset in {@link #SIG_CTX_BY_SCAN} of the contexts of one sub-block.
     *
     * @param chroma 0 for luma, 1 for chroma
     * @param log2Size the transform block size as a power of two
     * @param scanIdx the scan order
     * @param prevCsbf the coded sub-block flags of the right (bit 0) and lower (bit 1) sub-blocks
     * @param dcSub whether the sub-block is the first of the block
     * @return the offset
     */
    private static int sigBase(int chroma, int log2Size, int scanIdx, int prevCsbf, boolean dcSub) {
        var variant = ((chroma * 4 + log2Size - 2) * 3 + scanIdx) * 4 + prevCsbf;
        return (variant * 2 + (dcSub ? 1 : 0)) * 16;
    }

    /**
     * Returns {@code sigCtx} of {@code sig_coeff_flag} (9.3.4.2.5) without the transform skip context.
     *
     * @param chroma 0 for luma, 1 for chroma
     * @param log2Size the transform block size as a power of two
     * @param prevCsbf the coded sub-block flags of the right (bit 0) and lower (bit 1) sub-blocks
     * @param dcSub whether the sub-block is the first of the block
     * @param xP the column within the sub-block
     * @param yP the row within the sub-block
     * @param scanIdx the scan order
     * @return {@code sigCtx}
     */
    private static int sigContext(int chroma, int log2Size, int prevCsbf, boolean dcSub, int xP, int yP,
                                  int scanIdx) {
        if (log2Size == 2) {
            return CTX_IDX_MAP[(yP << 2) + xP];
        }
        if (dcSub && xP + yP == 0) {
            return 0;
        }
        var sigCtx = switch (prevCsbf) {
            case 0 -> xP + yP == 0 ? 2 : xP + yP < 3 ? 1 : 0;
            case 1 -> yP == 0 ? 2 : yP == 1 ? 1 : 0;
            case 2 -> xP == 0 ? 2 : xP == 1 ? 1 : 0;
            default -> 2;
        };
        if (chroma == 0) {
            if (!dcSub) {
                sigCtx += 3;
            }
            return sigCtx + (log2Size == 3 ? (scanIdx == 0 ? 9 : 15) : 21);
        }
        return sigCtx + (log2Size == 3 ? 9 : 12);
    }
}
