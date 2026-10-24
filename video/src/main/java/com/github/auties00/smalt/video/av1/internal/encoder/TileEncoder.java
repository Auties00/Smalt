package com.github.auties00.smalt.video.av1.internal.encoder;

import com.github.auties00.smalt.video.av1.internal.Av1Block;
import com.github.auties00.smalt.video.av1.internal.Av1Picture;
import com.github.auties00.smalt.video.av1.internal.Cdf;
import com.github.auties00.smalt.video.av1.internal.EncoderBridge;
import com.github.auties00.smalt.video.av1.internal.FrameContext;
import com.github.auties00.smalt.video.av1.internal.FrameHeader;
import com.github.auties00.smalt.video.av1.internal.IntraEdge;
import com.github.auties00.smalt.video.av1.internal.IpredDsp;
import com.github.auties00.smalt.video.av1.internal.LoopFilterMasks;
import com.github.auties00.smalt.video.av1.internal.McDsp;
import com.github.auties00.smalt.video.av1.internal.RefMvs;
import com.github.auties00.smalt.video.av1.internal.Scans;
import com.github.auties00.smalt.video.av1.internal.SequenceHeader;
import com.github.auties00.smalt.video.av1.internal.Tables;
import com.github.auties00.smalt.video.av1.internal.TaskContext;
import com.github.auties00.smalt.video.av1.internal.TileState;

import java.util.Arrays;

import static com.github.auties00.smalt.video.av1.internal.Tables.BLOCK_DIMENSIONS;

/**
 * Encodes one tile of an AV1 frame: walks its superblocks, decides and writes the partition tree,
 * predicts, transforms, quantizes and reconstructs every block, and writes its symbols with the
 * tile's own entropy coder and CDFs.
 *
 * <p>Reconstruction writes into the frame's picture with the decoder's prediction and inverse
 * transform kernels, in the decoder's order, so the picture equals what a decoder reconstructs
 * before the in-loop filters; the per-block loop filter masks, CDEF indices and motion records
 * the filters and later blocks need are recorded in the shared {@link FrameContext} exactly as the
 * decoder records them.
 *
 * <p>An instance is used by one thread at a time; tiles of one frame may run on different threads
 * with different instances.
 */
final class TileEncoder {
    /**
     * The 64x64 block level.
     */
    static final int BL_64X64 = 1;

    /**
     * The 8x8 block level.
     */
    static final int BL_8X8 = 4;

    /**
     * The unsplit partition.
     */
    static final int PARTITION_NONE = 0;

    /**
     * The horizontal partition.
     */
    static final int PARTITION_H = 1;

    /**
     * The vertical partition.
     */
    static final int PARTITION_V = 2;

    /**
     * The four-way split partition.
     */
    static final int PARTITION_SPLIT = 3;

    /**
     * The distance between rows of the emulated edge buffer.
     */
    private static final int EMU_STRIDE = 192;

    /**
     * The frame being encoded.
     */
    private final FrameEncoder frame;

    /**
     * The decoder-side state of the tile: contexts, motion records and scratch buffers.
     */
    final TaskContext t = new TaskContext();

    /**
     * The tile's bounds, CDFs and loop filter levels.
     */
    final TileState ts = new TileState();

    /**
     * The tile's entropy coder.
     */
    final RangeEncoder ec = new RangeEncoder();

    /**
     * The writer of the palette syntax of the tile's palette blocks.
     */
    private final PaletteWriter palette = new PaletteWriter();

    /**
     * The coefficient writer.
     */
    private final CoefWriter coefWriter = new CoefWriter();

    /**
     * The forward transform.
     */
    private final FwdTxfm fwd = new FwdTxfm();

    /**
     * The analysis kernels.
     */
    private EncDsp dsp = EncDsp.select();

    /**
     * The motion search.
     */
    private final MotionSearch search = new MotionSearch(dsp);

    /**
     * The superblock partitioning.
     */
    final VarPartition part;

    /**
     * The block mode decision.
     */
    final PickMode pick;

    /**
     * The candidate list of the chosen reference of the current inter block.
     */
    private RefMvs.Candidates chosen;

    /**
     * Whether the current block's residual is skipped without being coded.
     */
    private boolean forceSkip;

    /**
     * The number of 4x4 units coded from {@code LAST} with a motion vector below one sample.
     */
    long zeroMvCount;

    /**
     * The number of 4x4 units coded in the cyclic refresh's first and second segments.
     */
    final long[] segmentCounts = new long[2];

    /**
     * The segment of the current block after its mode decision, before a skip makes it the
     * predicted one.
     */
    private int decidedSegment;

    /**
     * The residual of the transform block being coded.
     */
    private final int[] residual = new int[64 * 64];

    /**
     * The forward transform output.
     */
    private final int[] coef = new int[32 * 32];

    /**
     * The dequantized coefficients passed to the inverse transform.
     */
    private final int[] cf = new int[32 * 32];

    /**
     * The quantized levels of every transform block of the current block, in coding order.
     */
    private final int[] levels = new int[3 * 64 * 64];

    /**
     * The number of transform blocks recorded for the current block.
     */
    private int nTx;

    /**
     * The plane of each recorded transform block.
     */
    private final int[] txPlane = new int[64 * 3];

    /**
     * The transform size of each recorded transform block.
     */
    private final int[] txSize = new int[64 * 3];

    /**
     * The above context offset of each recorded transform block.
     */
    private final int[] txAOff = new int[64 * 3];

    /**
     * The left context offset of each recorded transform block.
     */
    private final int[] txLOff = new int[64 * 3];

    /**
     * The above context width of each recorded transform block, clipped to the frame.
     */
    private final int[] txCtxW = new int[64 * 3];

    /**
     * The left context height of each recorded transform block, clipped to the frame.
     */
    private final int[] txCtxH = new int[64 * 3];

    /**
     * The end of block of each recorded transform block.
     */
    private final int[] txEob = new int[64 * 3];

    /**
     * The offset of each recorded transform block's levels in {@link #levels}.
     */
    private final int[] txLevelOff = new int[64 * 3];

    /**
     * The transform type of each recorded transform block.
     */
    private final int[] txType = new int[64 * 3];

    /**
     * The tile column, for the transform edges the loop filter reads across tiles.
     */
    private int tileCol;

    /**
     * The costs of each partition symbol of the node being checked.
     */
    private final int[] partitionCosts = new int[10];

    /**
     * The sizes, positions and levels of the blocks of the current superblock whose cost estimates
     * {@link #evalCosts} holds.
     */
    private final int[] evalKeys = new int[1024];

    /**
     * The transform types of the pruned inter search: {@code DCT_DCT}, {@code ADST_DCT},
     * {@code DCT_ADST}, {@code ADST_ADST}, {@code IDTX}, {@code V_DCT} and {@code H_DCT}.
     */
    private static final int[] PRUNED_INTER_TYPES = {0, 1, 2, 3, 9, 10, 11};

    /**
     * The {@code IDTX} transform type.
     */
    private static final int IDTX = 9;

    /**
     * The type without flipping of each transform type, and in bits 4 and 5 whether the type
     * flips the residual's rows upside down and its columns left to right
     * ({@code get_flip_cfg}).
     */
    private static final int[] FLIP = {0, 1, 2, 3, 1 | 16, 2 | 32, 3 | 48, 3 | 32, 3 | 16, 9, 10, 11, 12, 13,
            12 | 16, 13 | 32};

    /**
     * The flipped residual of a flipping transform type.
     */
    private final int[] flipped = new int[64 * 64];

    /**
     * The coder that costs the coefficients of transform type candidates.
     */
    private final RangeEncoder counter = new RangeEncoder();

    /**
     * The transform coefficients of a transform type candidate.
     */
    private final int[] trialCoef = new int[32 * 32];

    /**
     * The quantized levels of a transform type candidate.
     */
    private final int[] trialLevels = new int[32 * 32];

    /**
     * The rate-distortion quantization optimizer of the transform blocks.
     */
    private final Rdoq rdoq = new Rdoq();

    /**
     * Whether the luma transform types of the current inter block are searched.
     */
    private boolean searchInterType;

    /**
     * The luma transform type of each 4x4 unit of the superblock's coded inter blocks,
     * {@code DCT_DCT} where the luma transform block has no coefficients, as
     * {@code [y * 32 + x]}: the types the chroma of an inter block derives its own from.
     */
    private final byte[] lumaTypeMap = new byte[32 * 32];

    /**
     * The luma transform type co-located with the chroma transform block being coded.
     */
    private int chromaLumaTxtp;

    /**
     * The rate and distortion cost of the type the last transform type search chose.
     */
    private long lastTxCost;

    /**
     * The cost of the transform size the last depth search chose.
     */
    private long lastDepthCost;

    /**
     * Whether the transform search of a measured candidate is the reduced one: the pruned set of
     * transform types and at most one split of the block's largest transform.
     */
    private boolean trialLite;

    /**
     * The cost estimates of the blocks of {@link #evalKeys}.
     */
    private final long[] evalCosts = new long[1024];

    /**
     * The number of cached cost estimates of the current superblock.
     */
    private int evalCount;

    /**
     * Creates a tile encoder for a frame encoder.
     *
     * @param frame the frame encoder
     */
    TileEncoder(FrameEncoder frame) {
        this.frame = frame;
        this.dsp = frame.dsp;
        search.dsp(dsp);
        this.part = new VarPartition(frame, this, dsp, search);
        this.pick = new PickMode(frame, this, dsp, search, part);
        t.ts = ts;
    }

    /**
     * Encodes one tile of the current frame, superblock row after superblock row, coding its
     * symbols in place.
     *
     * @param tileRow the tile row
     * @param tileCol the tile column
     * @return the number of coded bytes, left in {@link RangeEncoder#buffer()}
     */
    int encode(int tileRow, int tileCol) {
        bind(tileRow, tileCol, true);
        resetCounts();
        var f = frame.f;
        var hdr = f.frameHdr;
        var rowSbEnd = hdr.rowStartSb[tileRow + 1];
        for (var sby = hdr.rowStartSb[tileRow]; sby < rowSbEnd && sby < f.sbh; sby++) {
            encodeRow(sby, null, 0, -1);
        }
        return ec.finish();
    }

    /**
     * Points this encoder at a tile of the current frame: its bounds and quantizer state, and,
     * when it codes the tile's symbols, the frame's starting CDFs and an empty coder.
     *
     * @param tileRow the tile row
     * @param tileCol the tile column
     * @param entropy whether this encoder codes the tile's symbols
     */
    void bind(int tileRow, int tileCol, boolean entropy) {
        var f = frame.f;
        var hdr = f.frameHdr;
        this.tileCol = tileCol;
        t.f = f;
        var colSbStart = hdr.colStartSb[tileCol];
        var colSbEnd = hdr.colStartSb[tileCol + 1];
        var rowSbStart = hdr.rowStartSb[tileRow];
        var rowSbEnd = hdr.rowStartSb[tileRow + 1];
        ts.lastQidx = hdr.quantYac;
        ts.dq = f.dq;
        ts.lflvl = f.lvl;
        ts.row = tileRow;
        ts.col = tileCol;
        ts.colStart = colSbStart << f.sbShift;
        ts.colEnd = Math.min(colSbEnd << f.sbShift, f.bw);
        ts.rowStart = rowSbStart << f.sbShift;
        ts.rowEnd = Math.min(rowSbEnd << f.sbShift, f.bh);
        if (entropy) {
            System.arraycopy(f.inCdf, 0, ts.cdf, 0, Cdf.SIZE);
            ec.reset(hdr.disableCdfUpdate);
        }
        if (frame.speed.rdoq) {
            rdoq.setup(f.inCdf, frame.bitDepth);
        }
        var selected = frame.dsp;
        if (selected != dsp) {
            dsp = selected;
            search.dsp(dsp);
            part.dsp(dsp);
            pick.dsp(dsp);
        }
    }

    /**
     * Zeroes the motion and segment counts the frame encoder sums after the frame.
     */
    void resetCounts() {
        zeroMvCount = 0;
        segmentCounts[0] = 0;
        segmentCounts[1] = 0;
    }

    /**
     * Encodes one superblock row of the tile this encoder is bound to. In a wavefront, each
     * superblock waits until the row above has encoded the superblock above and to its right, and
     * publishes its own progress once done; the row publishes {@link SbRowWavefront#DONE} at its
     * end.
     *
     * @param sby the superblock row
     * @param wavefront the wavefront, or {@code null} when rows run in order on this thread
     * @param unit this row's index in the wavefront
     * @param above the wavefront index of the row above in the same tile, or {@code -1} when
     * this row is the tile's first
     */
    void encodeRow(int sby, SbRowWavefront wavefront, int unit, int above) {
        var f = frame.f;
        var hdr = f.frameHdr;
        var colSbStart = hdr.colStartSb[tileCol];
        var colSb128Start = colSbStart >> 1;
        var inter = hdr.isInterOrSwitch();
        var sbCount = (ts.colEnd - ts.colStart + f.sbStep - 1) >> f.sbShift;
        t.by = sby << f.sbShift;
        if (inter) {
            t.rt.sbrowInit(frame.refMvs(tileCol), ts.colStart, ts.colEnd, ts.rowStart, ts.rowEnd, sby, ts.row);
        }
        t.l.reset(hdr.isKeyOrIntra());
        var sb128y = t.by >> 5;
        t.aIdx = colSb128Start + ts.row * f.sb128w;
        t.lfMaskIdx = sb128y * f.sb128w + colSb128Start;
        var sbIndex = 0;
        for (t.bx = ts.colStart; t.bx < ts.colEnd; t.bx += f.sbStep, sbIndex++) {
            if (wavefront != null && above >= 0) {
                wavefront.await(above, Math.min(sbIndex + 2, sbCount));
            }
            t.a = f.a[t.aIdx];
            t.lfMask = f.lfMask[t.lfMaskIdx];
            t.curSbCdefIdx = ((t.bx & 16) >> 4) + ((t.by & 16) >> 3);
            t.lfMask.cdefIdx[t.curSbCdefIdx] = -1;
            var px = t.bx * 4;
            var py = t.by * 4;
            if (inter && hdr.useRefFrameMvs) {
                t.rt.rf.loadTmvs(ts.row, t.bx >> 1, Math.min((t.bx + f.sbStep) >> 1, ts.colEnd >> 1), t.by >> 1,
                        (t.by + f.sbStep) >> 1);
            }
            if (inter) {
                part.sourceContent(px, py);
            } else {
                part.sourceSadNonrd = VarPartition.MED_SAD;
                part.lowSumdiff = false;
                part.lightingChange = false;
            }
            var cr = frame.cyclicRefresh;
            part.choose(px, py, !inter, cr != null && hdr.segmentationEnabled
                    ? cr.segmentOf(t.by, t.bx, f.sbStep, f.sbStep) : 0);
            evalCount = 0;
            if (inter && frame.speed.partitionSearch) {
                searchPartition(BL_64X64, IntraEdge.SB64);
                evalCount = 0;
            }
            encodeSb(BL_64X64, IntraEdge.SB64);
            if (f.seqHdr.refFrameMvs && hdr.refreshFrameFlags != 0 && inter) {
                t.rt.rf.saveTmvs(t.rt, t.bx >> 1, Math.min((t.bx + f.sbStep) >> 1, ts.colEnd >> 1), t.by >> 1,
                        (t.by + f.sbStep) >> 1);
            }
            if ((t.bx & 16) != 0) {
                t.aIdx++;
                t.lfMaskIdx++;
            }
            if (wavefront != null) {
                wavefront.publish(unit, sbIndex + 1);
            }
        }
        var alignH = (f.bh + 31) & ~31;
        System.arraycopy(t.l.txLpfY, t.by & 16, f.txLpfRightEdge[0], alignH * tileCol + t.by, f.sbStep);
        var ssVer = frame.ssVer;
        alignH >>= ssVer;
        System.arraycopy(t.l.txLpfUv, (t.by & 16) >> ssVer, f.txLpfRightEdge[1], alignH * tileCol + (t.by >> ssVer),
                f.sbStep >> ssVer);
        if (wavefront != null) {
            wavefront.complete(unit);
        }
    }

    /**
     * Sets the candidate list of the chosen reference of the current inter block.
     *
     * @param c the candidate list
     */
    void chosenCandidates(RefMvs.Candidates c) {
        chosen = c;
    }

    /**
     * Returns the motion vector of the {@code LAST}-predicted block above or left of the current
     * superblock, for the partitioning's motion estimate.
     *
     * @param above whether the block above is returned, else the block on the left
     * @return the vector in eighth samples packed as {@code row << 16 | (col & 0xFFFF)}, or
     * {@link Integer#MIN_VALUE} when the neighbor is unavailable, intra or predicted from
     * another reference
     */
    int neighborMv(boolean above) {
        var p = neighbor(above);
        if (p < 0) {
            return Integer.MIN_VALUE;
        }
        var blocks = t.rt.rf.r;
        if (RefMvs.ref0(blocks[p + 2]) != 1) {
            return Integer.MIN_VALUE;
        }
        var mv = blocks[p];
        return RefMvs.mvY(mv) << 16 | (RefMvs.mvX(mv) & 0xFFFF);
    }

    /**
     * Returns the motion vector of the block above the current block.
     *
     * @return the packed vector, or {@link RefMvs#INVALID_MV} when unavailable or intra
     */
    int blockAboveMv() {
        var p = neighbor(true);
        return p < 0 ? RefMvs.INVALID_MV : t.rt.rf.r[p];
    }

    /**
     * Returns the motion vector of the block left of the current block.
     *
     * @return the packed vector, or {@link RefMvs#INVALID_MV} when unavailable or intra
     */
    int blockLeftMv() {
        var p = neighbor(false);
        return p < 0 ? RefMvs.INVALID_MV : t.rt.rf.r[p];
    }

    /**
     * Returns the index of the motion record above or left of the current position.
     *
     * @param above whether the record above is returned, else the one on the left
     * @return the index in the frame's motion records, or {@code -1} outside the tile
     */
    private int neighbor(boolean above) {
        if (above ? t.by <= ts.rowStart : t.bx <= ts.colStart) {
            return -1;
        }
        var row = (t.by & 31) + (above ? 4 : 5);
        var col = above ? t.bx : t.bx - 1;
        return (t.rt.r[row] + col) * RefMvs.BLOCK_INTS;
    }

    /**
     * Returns the encoder of the frame this tile belongs to.
     *
     * @return the frame encoder
     */
    FrameEncoder frame() {
        return frame;
    }

    /**
     * Returns the probability that a partition at a frame's bottom edge splits
     * ({@code gather_top_partition_prob}).
     *
     * @param cdf the CDFs
     * @param pc the offset of the partition CDF
     * @param bl the block level
     * @return the inverse probability of no split, for a binary symbol
     */
    static int topPartitionProb(char[] cdf, int pc, int bl) {
        var out = cdf[pc + PARTITION_V - 1] - cdf[pc + 4];
        out += cdf[pc + 6 - 1];
        if (bl != 0) {
            out += cdf[pc + 9 - 1] - cdf[pc + 7];
        }
        return out;
    }

    /**
     * Returns the probability that a partition at a frame's right edge splits
     * ({@code gather_left_partition_prob}).
     *
     * @param cdf the CDFs
     * @param pc the offset of the partition CDF
     * @param bl the block level
     * @return the inverse probability of no split, for a binary symbol
     */
    static int leftPartitionProb(char[] cdf, int pc, int bl) {
        var out = cdf[pc + PARTITION_H - 1] - cdf[pc + PARTITION_H];
        out += cdf[pc + PARTITION_SPLIT - 1] - cdf[pc + 6];
        if (bl != 0) {
            out += cdf[pc + 8 - 1] - cdf[pc + 8];
        }
        return out;
    }

    /**
     * Encodes one partition node: decides and writes its partition, then encodes its blocks.
     *
     * @param bl the block level
     * @param node the intra edge node of the position
     */
    private void encodeSb(int bl, IntraEdge.Node node) {
        var f = frame.f;
        var hsz = 16 >> bl;
        var haveHSplit = f.bw > t.bx + hsz;
        var haveVSplit = f.bh > t.by + hsz;
        if (!haveHSplit && !haveVSplit) {
            encodeSb(bl + 1, node.children[0]);
            return;
        }
        var bp = bl == BL_8X8 ? PARTITION_NONE : part.partition(bl, t.bx & 15, t.by & 15);
        if (!haveVSplit) {
            bp = bp == PARTITION_NONE || bp == PARTITION_H ? PARTITION_H : PARTITION_SPLIT;
        } else if (!haveHSplit) {
            bp = (bp == PARTITION_NONE || bp == PARTITION_V) && frame.layout != SequenceHeader.LAYOUT_I422
                    ? PARTITION_V : PARTITION_SPLIT;
        }
        var bx8 = (t.bx & 31) >> 1;
        var by8 = (t.by & 31) >> 1;
        var ctx = ((t.a.partition[bx8] >> (4 - bl)) & 1) + (((t.l.partition[by8] >> (4 - bl)) & 1) << 1);
        var pc = Cdf.PARTITION + (bl * 4 + ctx) * 16;
        var cdf = ts.cdf;
        if (haveHSplit && haveVSplit) {
            ec.encodeSymbol(cdf, pc, bp, Tables.PARTITION_TYPE_COUNT[bl]);
        } else {
            var bit = bp == PARTITION_SPLIT ? 1 : 0;
            var log = ec.log();
            if (log != null) {
                log.edge(pc, bl, haveHSplit, bit);
            } else if (haveHSplit) {
                ec.encodeBoolFixed(bit, topPartitionProb(cdf, pc, bl));
            } else {
                ec.encodeBoolFixed(bit, leftPartitionProb(cdf, pc, bl));
            }
        }
        var b0 = Tables.BLOCK_SIZES[(bl * 10 + bp) * 2];
        switch (bp) {
            case PARTITION_NONE -> encodeBlock(bl, b0, PARTITION_NONE, node.o);
            case PARTITION_H -> {
                encodeBlock(bl, b0, PARTITION_H, node.h0);
                if (haveVSplit) {
                    t.by += hsz;
                    encodeBlock(bl, b0, PARTITION_H, node.h1);
                    t.by -= hsz;
                }
            }
            case PARTITION_V -> {
                encodeBlock(bl, b0, PARTITION_V, node.v0);
                if (haveHSplit) {
                    t.bx += hsz;
                    encodeBlock(bl, b0, PARTITION_V, node.v1);
                    t.bx -= hsz;
                }
            }
            case PARTITION_SPLIT -> {
                if (bl == BL_8X8) {
                    throw new IllegalStateException("4x4 blocks are not supported");
                }
                encodeSb(bl + 1, node.children[0]);
                if (haveHSplit) {
                    t.bx += hsz;
                    encodeSb(bl + 1, node.children[1]);
                    t.bx -= hsz;
                }
                if (haveVSplit) {
                    t.by += hsz;
                    encodeSb(bl + 1, node.children[2]);
                    if (haveHSplit) {
                        t.bx += hsz;
                        encodeSb(bl + 1, node.children[3]);
                        t.bx -= hsz;
                    }
                    t.by -= hsz;
                }
            }
            default -> throw new IllegalStateException("unsupported partition " + bp);
        }
        if (bp != PARTITION_SPLIT || bl == BL_8X8) {
            Arrays.fill(t.a.partition, bx8, bx8 + hsz, (byte) Tables.AL_PART_CTX[bl * 10 + bp]);
            Arrays.fill(t.l.partition, by8, by8 + hsz, (byte) Tables.AL_PART_CTX[50 + bl * 10 + bp]);
        }
    }

    /**
     * Chooses the partitions of the nodes of the superblock at the current position by comparing
     * the mode decision's cost estimates of the unsplit block, its horizontal and vertical halves
     * and its four recursively partitioned quarters, and records them in the variance
     * partitioning's tree. The speed prunes the comparison: the halves are costed only at the
     * levels it allows and, when the unsplit block of a node codes without a residual, the node's
     * halves, or its quarters, are not costed from the levels it names; the halves reuse the
     * unsplit block's best reference alone when it asks for it. A 64x64 node whose unsplit block
     * codes without a residual and with a squared error below {@link SpeedFeatures#partitionBreakout}
     * is kept whole when the variance partitioning kept it whole too.
     *
     * @param bl the node's block level
     * @param node the intra edge node of the position
     * @return the estimated cost of the best partition of the node, or {@link Long#MAX_VALUE} when
     * the node lies partly outside the frame or a block had no costed mode
     */
    private long searchPartition(int bl, IntraEdge.Node node) {
        var f = frame.f;
        var sf = frame.speed;
        var hsz = 16 >> bl;
        var sbX = t.bx & 15;
        var sbY = t.by & 15;
        var inside = f.bw >= t.bx + 2 * hsz && f.bh >= t.by + 2 * hsz;
        if (!inside) {
            if (bl < BL_8X8 && part.partition(bl, sbX, sbY) == PARTITION_SPLIT) {
                searchChildren(bl, node, hsz, Long.MAX_VALUE);
            }
            return Long.MAX_VALUE;
        }
        var bx8 = (t.bx & 31) >> 1;
        var by8 = (t.by & 31) >> 1;
        var ctx = ((t.a.partition[bx8] >> (4 - bl)) & 1) + (((t.l.partition[by8] >> (4 - bl)) & 1) << 1);
        var pc = Cdf.PARTITION + (bl * 4 + ctx) * 16;
        RdCost.costsFromCdf(partitionCosts, 0, f.inCdf, pc, Tables.PARTITION_TYPE_COUNT[bl]);
        var rdmult = frame.rd.rdmult;
        var none = evaluate(bl, Tables.BLOCK_SIZES[(bl * 10 + PARTITION_NONE) * 2], node.o);
        if (none == Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        var best = none + RdCost.rdcost(rdmult, partitionCosts[PARTITION_NONE], 0);
        var bestPartition = PARTITION_NONE;
        var noneSkips = pick.skipTxfm && !t.b.intra;
        var whole = sf.partitionBreakout > 0 && bl == BL_64X64 && noneSkips
                && part.partition(bl, sbX, sbY) == PARTITION_NONE
                && pick.bestDist * 1_000_000L < (long) sf.partitionBreakout * 16 * 64 * 64 * rdmult;
        if (bl < BL_8X8 && !whole) {
            var noneRefs = 1 << pick.lastBestRef;
            if ((sf.searchRectLevels & (1 << bl)) != 0 && !(noneSkips && bl >= sf.searchRectSkipLevel)) {
                var lastRect = frame.layout == SequenceHeader.LAYOUT_I422 ? PARTITION_H : PARTITION_V;
                for (var bp = PARTITION_H; bp <= lastRect; bp++) {
                    var cost = searchRect(bl, bp, node, hsz, noneRefs, best - RdCost.rdcost(rdmult, partitionCosts[bp], 0));
                    if (cost != Long.MAX_VALUE) {
                        best = cost + RdCost.rdcost(rdmult, partitionCosts[bp], 0);
                        bestPartition = bp;
                    }
                }
            }
            if (!(noneSkips && bl >= sf.searchSplitSkipLevel)) {
                var split = RdCost.rdcost(rdmult, partitionCosts[PARTITION_SPLIT], 0);
                var quarters = searchChildren(bl, node, hsz, best - split);
                if (quarters != Long.MAX_VALUE && split + quarters < best) {
                    best = split + quarters;
                    bestPartition = PARTITION_SPLIT;
                }
            }
        }
        part.setPartition(bl, sbX, sbY, bestPartition);
        return best;
    }

    /**
     * Costs the two halves of a node.
     *
     * @param bl the node's block level
     * @param bp the partition, {@link #PARTITION_H} or {@link #PARTITION_V}
     * @param node the intra edge node
     * @param hsz the half size of the node in 4-sample units
     * @param noneRefs the references the unsplit block chose
     * @param bound the cost from which the halves are no longer of interest
     * @return the sum of the halves' costs, or {@link Long#MAX_VALUE} when it reaches the bound or
     * a half had no costed mode
     */
    private long searchRect(int bl, int bp, IntraEdge.Node node, int hsz, int noneRefs, long bound) {
        var sf = frame.speed;
        var bs = Tables.BLOCK_SIZES[(bl * 10 + bp) * 2];
        var horizontal = bp == PARTITION_H;
        pick.screen = sf.searchScreenRect;
        pick.screenRefs = noneRefs;
        var first = evaluate(bl, bs, horizontal ? node.h0 : node.v0);
        if (first == Long.MAX_VALUE || first >= bound) {
            pick.screen = false;
            return Long.MAX_VALUE;
        }
        if (horizontal) {
            t.by += hsz;
        } else {
            t.bx += hsz;
        }
        var second = evaluate(bl, bs, horizontal ? node.h1 : node.v1);
        if (horizontal) {
            t.by -= hsz;
        } else {
            t.bx -= hsz;
        }
        pick.screen = false;
        if (second == Long.MAX_VALUE || first + second >= bound) {
            return Long.MAX_VALUE;
        }
        return first + second;
    }

    /**
     * Searches the four quarters of a partition node.
     *
     * @param bl the node's block level
     * @param node the intra edge node
     * @param hsz the half size of the node in 4-sample units
     * @param bound the cost from which the sum is no longer of interest
     * @return the sum of the quarters' costs, or {@link Long#MAX_VALUE} when it reaches the bound
     * or a quarter had no costed mode
     */
    private long searchChildren(int bl, IntraEdge.Node node, int hsz, long bound) {
        var f = frame.f;
        long total = 0;
        for (var i = 0; i < 4; i++) {
            var dx = (i & 1) * hsz;
            var dy = (i >> 1) * hsz;
            if (t.bx + dx >= f.bw || t.by + dy >= f.bh) {
                continue;
            }
            t.bx += dx;
            t.by += dy;
            var cost = searchPartition(bl + 1, node.children[i]);
            t.bx -= dx;
            t.by -= dy;
            if (cost == Long.MAX_VALUE) {
                total = Long.MAX_VALUE;
            } else if (total != Long.MAX_VALUE) {
                total += cost;
            }
            if (total >= bound) {
                return Long.MAX_VALUE;
            }
        }
        return total;
    }

    /**
     * Returns the mode decision's cost estimate of a block at the current position, from the
     * superblock's cache when the same block was estimated before.
     *
     * @param bl the block level
     * @param bs the block size
     * @param edgeFlags the block's intra edge flags
     * @return the cost, or {@link Long#MAX_VALUE} when no mode was costed
     */
    private long evaluate(int bl, int bs, int edgeFlags) {
        var key = bs << 12 | (t.by & 15) << 8 | (t.bx & 15) << 4 | bl;
        for (var i = 0; i < evalCount; i++) {
            if (evalKeys[i] == key) {
                return evalCosts[i];
            }
        }
        var b = t.b;
        b.bl = bl;
        b.bs = bs;
        b.bp = PARTITION_NONE;
        b.segId = 0;
        b.skipMode = false;
        b.motionMode = 0;
        b.compType = 0;
        b.ref[1] = -1;
        pick.resetCost();
        pick.decide(bs, edgeFlags);
        var cost = pick.bestCost();
        if (evalCount < evalKeys.length) {
            evalKeys[evalCount] = key;
            evalCosts[evalCount++] = cost;
        }
        return cost;
    }

    /**
     * Decides, reconstructs and writes one block.
     *
     * @param bl the block level
     * @param bs the block size
     * @param bp the partition the block came from
     * @param edgeFlags the block's intra edge flags
     */
    private void encodeBlock(int bl, int bs, int bp, int edgeFlags) {
        var b = t.b;
        b.bl = bl;
        b.bs = bs;
        b.bp = bp;
        b.segId = 0;
        b.skipMode = false;
        b.palSz[0] = 0;
        b.palSz[1] = 0;
        b.motionMode = 0;
        b.interintraType = 0;
        b.compType = 0;
        b.ref[1] = -1;
        var cr = frame.cyclicRefresh;
        var segmented = cr != null && frame.f.frameHdr.segmentationEnabled;
        var bw4 = BLOCK_DIMENSIONS[bs * 4];
        var bh4 = BLOCK_DIMENSIONS[bs * 4 + 1];
        if (segmented) {
            b.segId = cr.segmentOf(t.by, t.bx, bh4, bw4);
        }
        pick.decide(bs, edgeFlags);
        if (b.intra && pick.paletteSize > 0) {
            b.palSz[0] = pick.paletteSize;
            System.arraycopy(pick.paletteColors, 0, t.pal, 0, pick.paletteSize);
            PaletteWriter.pack(t.palIdxY, pick.paletteMap, bw4 * 4, bw4 * 4, bh4 * 4,
                    Math.min(bw4, frame.f.bw - t.bx) * 4, Math.min(bh4, frame.f.bh - t.by) * 4);
        }
        if (segmented) {
            var segment = cr.updateSegment(t.by, t.bx, bh4, bw4, b.segId, b.intra, RefMvs.mvY(b.mv[0]),
                    RefMvs.mvX(b.mv[0]), Math.min(bw4, bh4) >= 4, pick.bestRate, pick.bestDist,
                    pick.skipTxfm && !b.intra);
            if (segment > frame.f.frameHdr.segLastActiveSegid) {
                segment = CyclicRefresh.BOOST1;
            }
            b.segId = segment;
        }
        refinedValid = false;
        if (!b.intra && b.ref[1] < 0 && !b.skipMode && !segmented && frame.speed.finalists > 1
                && pick.finalistCount > 1) {
            refineInter(bs);
        }
        if (frame.speed.warp && !segmented) {
            chooseMotionMode(bs, edgeFlags);
        }
        decidedSegment = b.segId;
        forceSkip = pick.skipTxfm && !b.intra;
        nTx = 0;
        if (!b.intra && b.ref[0] == 0 && Math.abs(RefMvs.mvX(b.mv[0])) < 8 && Math.abs(RefMvs.mvY(b.mv[0])) < 8) {
            var f = frame.f;
            zeroMvCount += (long) Math.min(BLOCK_DIMENSIONS[bs * 4], f.bw - t.bx)
                    * Math.min(BLOCK_DIMENSIONS[bs * 4 + 1], f.bh - t.by);
        }
        if (b.intra) {
            reconIntra(bs, edgeFlags, b);
        } else {
            reconInter(bs, b);
        }
        var skip = true;
        for (var i = 0; i < nTx; i++) {
            if (txEob[i] != 0) {
                skip = false;
                break;
            }
        }
        b.skip = skip;
        writeBlock(bs, edgeFlags, b);
    }

    /**
     * Records one transform block of the current block.
     *
     * @param plane the plane index: 0 luma, 1 U, 2 V
     * @param tx the transform size
     * @param aOff the above context offset
     * @param lOff the left context offset
     * @param ctxW the above context width, clipped to the frame
     * @param ctxH the left context height, clipped to the frame
     * @param eob the end of block
     * @param off the offset of the levels
     */
    private void record(int plane, int tx, int aOff, int lOff, int ctxW, int ctxH, int eob, int off) {
        var i = nTx++;
        txPlane[i] = plane;
        txSize[i] = tx;
        txAOff[i] = aOff;
        txLOff[i] = lOff;
        txCtxW[i] = ctxW;
        txCtxH[i] = ctxH;
        txEob[i] = eob;
        txLevelOff[i] = off;
        txType[i] = 0;
    }

    /**
     * Returns the transform type of a transform block: for intra luma the type the mode decision
     * chose ({@code DCT_DCT} unless the rate-distortion intra search chose another), for inter
     * luma {@code DCT_DCT} unless the inter type search chooses another, for inter chroma the
     * type the decoder derives from the luma type, and for the chroma of an intra block the type
     * its chroma mode implies; the syntax codes neither chroma type.
     *
     * @param plane the plane index: 0 luma, 1 U, 2 V
     * @param tx the transform size
     * @param b the block
     * @return the transform type
     */
    private int txType(int plane, int tx, Av1Block b) {
        if (!b.intra) {
            return plane == 0 || Tables.TX_MAX[tx] >= 4 ? 0 : uvInterTxtp(tx, chromaLumaTxtp);
        }
        if (Tables.TX_MAX[tx] + 1 >= 4) {
            return 0;
        }
        if (plane == 0) {
            return frame.f.frameHdr.segQidx[b.segId] == 0 ? 0 : pick.lumaTxType;
        }
        return Tables.TXTP_FROM_UVMODE[b.uvMode];
    }

    /**
     * Returns the offset where the next transform block's levels are stored.
     *
     * @return the offset
     */
    private int nextLevelOff() {
        if (nTx == 0) {
            return 0;
        }
        var last = nTx - 1;
        var tx = txSize[last];
        return txLevelOff[last] + Math.min(Tables.TX_W[tx] * 4, 32) * Math.min(Tables.TX_H[tx] * 4, 32);
    }

    /**
     * Transforms, quantizes and reconstructs one transform block whose prediction is already in
     * the picture, and records it.
     *
     * @param plane the plane index: 0 luma, 1 U, 2 V
     * @param tx the transform size
     * @param dst the picture plane
     * @param dstOff the offset of the block's top-left sample
     * @param stride the plane stride
     * @param aOff the above context offset
     * @param lOff the left context offset
     * @param ctxW the above context width, clipped to the frame
     * @param ctxH the left context height, clipped to the frame
     */
    private void codeTx(int plane, int tx, short[] dst, int dstOff, int stride, int aOff, int lOff, int ctxW, int ctxH) {
        var src = frame.source.data[plane];
        if (forceSkip) {
            record(plane, tx, aOff, lOff, ctxW, ctxH, 0, nextLevelOff());
            return;
        }
        var w = Tables.TX_W[tx] * 4;
        var h = Tables.TX_H[tx] * 4;
        var res = residual;
        for (var y = 0; y < h; y++) {
            var s = dstOff + y * stride;
            var r = y * w;
            for (var x = 0; x < w; x++) {
                res[r + x] = src[s + x] - dst[s + x];
            }
        }
        var txtp = txType(plane, tx, t.b);
        if (plane == 0 && searchInterType && !t.b.intra && Tables.TX_MAX[tx] < 4) {
            txtp = searchInterType(tx, w, h, aOff, lOff);
        }
        forward(tx, txtp, w, h, coef);
        var off = nextLevelOff();
        var q = frame.quantizer(t.b.segId, plane);
        var scan = TxbCoder.scanOf(tx, txtp);
        var iscan = TxbCoder.inverseScanOf(tx, txtp);
        var n = Math.min(w, 32) * Math.min(h, 32);
        var dqShift = Math.max(0, Tables.TX_CTX[tx] - 2);
        var lv = levels;
        var qc = coef;
        int eob;
        if (frame.speed.rdoq && Tables.TX_TYPE_CLASS[txtp] == CoefWriter.TX_CLASS_2D) {
            qc = trialLevels;
            eob = q.quantizeB(coef, n, scan, iscan, dqShift, qc, q.roundRd);
            if (eob > 0) {
                var ab = plane == 0 ? t.a.lcoef : t.a.ccoef;
                var lb = plane == 0 ? t.l.lcoef : t.l.ccoef;
                eob = rdoq.optimize(coef, qc, eob, scan, tx, plane,
                        (plane != 0 ? 3 : 0) + CoefWriter.dcSignCtx(tx, ab, aOff, lb, lOff),
                        CoefWriter.skipCtx(tx, t.b.bs, ab, aOff, lb, lOff, plane != 0, frame.ssHor, frame.ssVer),
                        q.dequant(false),
                        q.dequant(true), dqShift,
                        (int) ((long) frame.rd.rdmult * frame.speed.rdoqLambdaScale / 100));
            }
        } else {
            eob = q.quantizeB(qc, n, scan, iscan, dqShift, qc);
        }
        System.arraycopy(qc, 0, lv, off, n);
        record(plane, tx, aOff, lOff, ctxW, ctxH, eob, off);
        txType[nTx - 1] = txtp;
        if (plane == 0 && !t.b.intra) {
            var type = (byte) (eob > 0 ? txtp : 0);
            for (var y = 0; y < Tables.TX_H[tx]; y++) {
                var row = ((t.by & 31) + y) * 32 + (t.bx & 31);
                Arrays.fill(lumaTypeMap, row, row + Tables.TX_W[tx], type);
            }
        }
        if (eob > 0) {
            q.dequantize(qc, eob, scan, dqShift, cf);
            frame.f.itx.itxfmAdd(tx, txtp, dst, dstOff, stride, cf, 0, eob - 1, t.itxScratch, frame.bdMax);
        }
    }

    /**
     * Returns the luma transform type of an inter transform block with the lowest rate and
     * distortion cost among the candidates of the speed: the residual is transformed and
     * quantized with each type, its rate counted with the frame's initial CDFs and the contexts
     * around the block, and its distortion measured on the coefficients.
     *
     * @implNote This implementation follows the role of libaom's transform type search
     * ({@code search_tx_type} in {@code av1/encoder/tx_search.c}) with a
     * coefficient-domain distortion and without its pruning models.
     * @param tx the transform size
     * @param w the transform width
     * @param h the transform height
     * @param aOff the above context offset
     * @param lOff the left context offset
     * @return the transform type
     */
    private int searchInterType(int tx, int w, int h, int aOff, int lOff) {
        var f = frame.f;
        var hdr = f.frameHdr;
        var qidx = hdr.segQidx[t.b.segId];
        var q = frame.quantizer(t.b.segId, 0);
        var n = Math.min(w, 32) * Math.min(h, 32);
        var logScale = Math.max(0, Tables.TX_CTX[tx] - 2);
        var dcDq = q.dequant(false);
        var acDq = q.dequant(true);
        var rdmult = frame.rd.rdmult;
        int first;
        int count;
        if (Tables.TX_MAX[tx] == 3) {
            first = -1;
            count = 2;
        } else if (Tables.TX_MIN[tx] == 2) {
            first = 12;
            count = 12;
        } else {
            first = 24;
            count = 16;
        }
        var full = !trialLite && frame.speed.interTxTypes >= 2;
        var best = 0;
        var bestCost = Long.MAX_VALUE;
        var candidates = full || first < 0 ? count : PRUNED_INTER_TYPES.length;
        for (var k = 0; k < candidates; k++) {
            int type;
            if (first < 0) {
                type = k == 0 ? 0 : IDTX;
            } else if (full) {
                type = Tables.TX_TYPES_PER_SET[first + k];
            } else {
                type = PRUNED_INTER_TYPES[k];
                if (!inSet(first, count, type)) {
                    continue;
                }
            }
            forward(tx, type, w, h, trialCoef);
            var eob = q.quantizeB(trialCoef, n, TxbCoder.scanOf(tx, type), TxbCoder.inverseScanOf(tx, type), logScale,
                    trialLevels);
            var zero = eob == 0 && type == 0;
            var error = dsp.levelError(trialCoef, trialLevels, n, dcDq, acDq, logScale);
            counter.startCounting();
            coefWriter.write(counter, f.inCdf, t.a.lcoef, aOff, t.l.lcoef, lOff, tx, t.b.bs, 0, false, 0,
                    hdr.reducedTxtpSet, qidx, type, trialLevels, 0, eob);
            var cost = RdCost.rdcost(rdmult, counter.cost(), (error >> (6 - 2 * logScale)) << 4);
            if (zero) {
                lastTxCost = cost;
                return 0;
            }
            if (cost < bestCost) {
                bestCost = cost;
                best = type;
            }
        }
        lastTxCost = bestCost;
        return best;
    }

    /**
     * Returns the luma transform size of an inter block whose prediction is in the picture: the
     * uniform depth of the block's largest transform, up to two splits and at most 32 samples a
     * side, whose transform blocks cost the least by the transform type search, with each block's
     * contexts taken from the block's edges.
     *
     * @param maxYtx the block's largest transform
     * @param dst the offset of the block's top-left luma sample
     * @param stride the luma stride
     * @param w4 the visible block width in 4-sample units
     * @param h4 the visible block height in 4-sample units
     * @return the transform size
     */
    private int chooseTxDepth(int maxYtx, int dst, int stride, int w4, int h4) {
        var src = frame.source.data[0];
        var cur = frame.f.cur.data[0];
        var bx4 = t.bx & 31;
        var by4 = t.by & 31;
        var best = -1;
        var bestCost = Long.MAX_VALUE;
        var tx = maxYtx;
        var previous = -1;
        for (var depth = 0; depth <= (trialLite ? 1 : 2) && tx != previous; depth++, previous = tx, tx = Tables.TX_SUB[tx]) {
            if (Tables.TX_MAX[tx] >= 4) {
                continue;
            }

            var w = Tables.TX_W[tx] * 4;
            var h = Tables.TX_H[tx] * 4;
            long total = 0;
            for (var y = 0; y < h4 && total < bestCost; y += Tables.TX_H[tx]) {
                for (var x = 0; x < w4 && total < bestCost; x += Tables.TX_W[tx]) {
                    var off = dst + 4 * (y * stride + x);
                    for (var r = 0; r < h; r++) {
                        var s = off + r * stride;
                        var d = r * w;
                        for (var c = 0; c < w; c++) {
                            residual[d + c] = src[s + c] - cur[s + c];
                        }
                    }
                    searchInterType(tx, w, h, bx4 + x, by4 + y);
                    total += lastTxCost;
                }
            }
            if (total < bestCost) {
                bestCost = total;
                best = tx;
            }
        }
        lastDepthCost = bestCost;
        return best;
    }

    /**
     * Applies the forward transform of a type to the residual; a flipping type transforms the
     * flipped residual with its unflipped type, which equals flipping the input of each
     * one-dimensional pass.
     *
     * @param tx the transform size
     * @param type the transform type
     * @param w the transform width
     * @param h the transform height
     * @param out receives the coefficients
     */
    private void forward(int tx, int type, int w, int h, int[] out) {
        var flip = FLIP[type];
        if (flip < 16) {
            dsp.forwardTransform(fwd, tx, type, residual, 0, w, out);
            return;
        }
        var ud = (flip & 16) != 0;
        var lr = (flip & 32) != 0;
        for (var y = 0; y < h; y++) {
            var s = (ud ? h - 1 - y : y) * w;
            var d = y * w;
            for (var x = 0; x < w; x++) {
                flipped[d + x] = residual[s + (lr ? w - 1 - x : x)];
            }
        }
        dsp.forwardTransform(fwd, tx, flip & 15, flipped, 0, w, out);
    }

    /**
     * Returns whether a transform set holds a type.
     *
     * @param first the first entry of the set in {@link Tables#TX_TYPES_PER_SET}
     * @param count the number of types in the set
     * @param type the transform type
     * @return {@code true} when the set holds the type
     */
    private static boolean inSet(int first, int count, int type) {
        for (var i = 0; i < count; i++) {
            if (Tables.TX_TYPES_PER_SET[first + i] == type) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the chroma transform type of an inter block, which the decoder derives from the
     * co-located luma type ({@code get_uv_inter_txtp}).
     *
     * @param uvtx the chroma transform size
     * @param ytxtp the luma transform type
     * @return the chroma transform type
     */
    private static int uvInterTxtp(int uvtx, int ytxtp) {
        if (Tables.TX_MAX[uvtx] == 3) {
            return ytxtp == IDTX ? IDTX : 0;
        }
        if (Tables.TX_MIN[uvtx] == 2 && ((1 << ytxtp) & ((1 << 15) | (1 << 14) | (1 << 13) | (1 << 12))) != 0) {
            return 0;
        }
        return ytxtp;
    }

    /**
     * Predicts and reconstructs an intra block in the decoder's order ({@code recon_b_intra}).
     *
     * @param bs the block size
     * @param edgeFlags the block's intra edge flags
     * @param b the block
     */
    private void reconIntra(int bs, int edgeFlags, Av1Block b) {
        var f = frame.f;
        var ipred = IpredDsp.INSTANCE;
        var cur = f.cur;
        var bdMax = frame.bdMax;
        var ssHor = frame.ssHor;
        var ssVer = frame.ssVer;
        var bx4 = t.bx & 31;
        var by4 = t.by & 31;
        var bw4 = BLOCK_DIMENSIONS[bs * 4];
        var bh4 = BLOCK_DIMENSIONS[bs * 4 + 1];
        var w4 = Math.min(bw4, f.bw - t.bx);
        var h4 = Math.min(bh4, f.bh - t.by);
        var cw4 = (w4 + ssHor) >> ssHor;
        var ch4 = (h4 + ssVer) >> ssVer;
        var tx = b.tx;
        var txW = Tables.TX_W[tx];
        var txH = Tables.TX_H[tx];
        var edge = t.edge;
        var edgeOff = IpredDsp.EDGE_TOPLEFT;
        var intraEdgeFilter = f.seqHdr.intraEdgeFilter;
        var intraEdgeFilterFlag = intraEdgeFilter ? IpredDsp.ANGLE_USE_EDGE_FILTER_FLAG : 0;
        var yStride = cur.stride[0];
        var yPlane = cur.data[0];
        var angle = t.angle;
        for (var initY = 0; initY < h4; initY += 16) {
            var subH4 = Math.min(h4, 16 + initY);
            var subCh4 = Math.min(ch4, (initY + 16) >> ssVer);
            for (var initX = 0; initX < w4; initX += 16) {
                var intraFlags = t.a.smFlag(bx4) | t.l.smFlag(by4) | intraEdgeFilterFlag;
                var sbHasTr = initX + 16 < w4 ? 1 : initY != 0 ? 0 : edgeFlags & IntraEdge.I444_TOP_HAS_RIGHT;
                var sbHasBl = initX != 0 ? 0 : initY + 16 < h4 ? 1 : edgeFlags & IntraEdge.I444_LEFT_HAS_BOTTOM;
                var subW4 = Math.min(w4, initX + 16);
                if (b.palSz[0] != 0) {
                    ipred.palPred(yPlane, 4 * (t.by * yStride + t.bx), yStride, t.pal, 0, t.palIdxY, 0, bw4 * 4,
                            bh4 * 4);
                }
                int y;
                int x = 0;
                for (y = initY, t.by += initY; y < subH4; y += txH, t.by += txH) {
                    var dst = 4 * (t.by * yStride + t.bx + initX);
                    for (x = initX, t.bx += initX; x < subW4; x += txW, t.bx += txW) {
                        if (b.palSz[0] == 0) {
                            angle[0] = b.yAngle;
                            var flags = (((y > initY || sbHasTr == 0) && (x + txW >= subW4)) ? 0
                                    : IntraEdge.I444_TOP_HAS_RIGHT)
                                    | ((x > initX || (sbHasBl == 0 && y + txH >= subH4)) ? 0
                                    : IntraEdge.I444_LEFT_HAS_BOTTOM);
                            var m = IpredDsp.prepareIntraEdges(t.bx, t.bx > ts.colStart, t.by, t.by > ts.rowStart,
                                    ts.colEnd, ts.rowEnd, flags, yPlane, dst, yStride, null, 0, b.yMode, angle, txW,
                                    txH, intraEdgeFilter, edge, edgeOff, bdMax);
                            ipred.intraPred(m, yPlane, dst, yStride, edge, edgeOff, txW * 4, txH * 4,
                                    angle[0] | intraFlags, 4 * f.bw - 4 * t.bx, 4 * f.bh - 4 * t.by, t.ipredScratch,
                                    bdMax);
                        }
                        codeTx(0, tx, yPlane, dst, yStride, bx4 + x, by4 + y, Math.min(txW, f.bw - t.bx),
                                Math.min(txH, f.bh - t.by));
                        dst += 4 * txW;
                    }
                    t.bx -= x;
                }
                t.by -= y;
                if (frame.planes > 1) {
                    reconIntraChroma(bs, edgeFlags, b, initX, initY, subCh4, cw4, ch4, intraEdgeFilterFlag);
                }
            }
        }
    }

    /**
     * Predicts and reconstructs the chroma of one 64x64 luma area of an intra block.
     *
     * @param bs the block size
     * @param edgeFlags the block's intra edge flags
     * @param b the block
     * @param initX the area's column offset in 4-sample units
     * @param initY the area's row offset in 4-sample units
     * @param subCh4 the chroma row after the area
     * @param cw4 the visible chroma width of the block
     * @param ch4 the visible chroma height of the block
     * @param intraEdgeFilterFlag the edge filter flag of the angle argument
     */
    private void reconIntraChroma(int bs, int edgeFlags, Av1Block b, int initX, int initY, int subCh4, int cw4,
                                  int ch4, int intraEdgeFilterFlag) {
        var f = frame.f;
        var ipred = IpredDsp.INSTANCE;
        var cur = f.cur;
        var bdMax = frame.bdMax;
        var layout = frame.layout;
        var ssHor = frame.ssHor;
        var ssVer = frame.ssVer;
        var stride = cur.stride[1];
        var uvtx = b.uvtx;
        var uvW = Tables.TX_W[uvtx];
        var uvH = Tables.TX_H[uvtx];
        var edge = t.edge;
        var edgeOff = IpredDsp.EDGE_TOPLEFT;
        var angle = t.angle;
        var cbx4 = (t.bx & 31) >> ssHor;
        var cby4 = (t.by & 31) >> ssVer;
        var smUvFl = t.a.smUvFlag(cbx4) | t.l.smUvFlag(cby4);
        var uvSbHasTr = ((initX + 16) >> ssHor) < cw4 ? 1 : initY != 0 ? 0
                : edgeFlags & (IntraEdge.I420_TOP_HAS_RIGHT >> (layout - 1));
        var uvSbHasBl = initX != 0 ? 0 : ((initY + 16) >> ssVer) < ch4 ? 1
                : edgeFlags & (IntraEdge.I420_LEFT_HAS_BOTTOM >> (layout - 1));
        var subCw4 = Math.min(cw4, (initX + 16) >> ssHor);
        for (var pl = 0; pl < 2; pl++) {
            var plane = cur.data[1 + pl];
            int y;
            int x = 0;
            for (y = initY >> ssVer, t.by += initY; y < subCh4; y += uvH, t.by += uvH << ssVer) {
                var dst = 4 * ((t.by >> ssVer) * stride + ((t.bx + initX) >> ssHor));
                for (x = initX >> ssHor, t.bx += initX; x < subCw4; x += uvW, t.bx += uvW << ssHor) {
                    angle[0] = b.uvAngle;
                    var flags = (((y > (initY >> ssVer) || uvSbHasTr == 0) && (x + uvW >= subCw4))
                            ? 0 : IntraEdge.I444_TOP_HAS_RIGHT)
                            | ((x > (initX >> ssHor) || (uvSbHasBl == 0 && y + uvH >= subCh4))
                            ? 0 : IntraEdge.I444_LEFT_HAS_BOTTOM);
                    var xpos = t.bx >> ssHor;
                    var ypos = t.by >> ssVer;
                    var xstart = ts.colStart >> ssHor;
                    var ystart = ts.rowStart >> ssVer;
                    var m = IpredDsp.prepareIntraEdges(xpos, xpos > xstart, ypos, ypos > ystart, ts.colEnd >> ssHor,
                            ts.rowEnd >> ssVer, flags, plane, dst, stride, null, 0, b.uvMode, angle, uvW, uvH,
                            f.seqHdr.intraEdgeFilter, edge, edgeOff, bdMax);
                    angle[0] |= intraEdgeFilterFlag;
                    ipred.intraPred(m, plane, dst, stride, edge, edgeOff, uvW * 4, uvH * 4, angle[0] | smUvFl,
                            (4 * f.bw + ssHor - 4 * (t.bx & ~ssHor)) >> ssHor,
                            (4 * f.bh + ssVer - 4 * (t.by & ~ssVer)) >> ssVer, t.ipredScratch, bdMax);
                    codeTx(1 + pl, uvtx, plane, dst, stride, pl * 32 + cbx4 + x, pl * 32 + cby4 + y,
                            Math.min(uvW, (f.bw - t.bx + ssHor) >> ssHor), Math.min(uvH, (f.bh - t.by + ssVer) >> ssVer));
                    dst += uvW * 4;
                }
                t.bx -= x << ssHor;
            }
            t.by -= y << ssVer;
        }
    }

    /**
     * Predicts one plane of a single-reference inter block from a reference picture
     * ({@code mc}), with the decoder's scaled prediction when the reference has another size.
     *
     * @param dst the destination plane
     * @param dstOff the offset of the block's top-left sample
     * @param dstStride the destination stride
     * @param bw4 the block width in 4-sample units of luma
     * @param bh4 the block height in 4-sample units of luma
     * @param bx the block column in 4-sample units
     * @param by the block row in 4-sample units
     * @param pl the plane
     * @param mv the packed motion vector
     * @param refp the reference picture
     * @param refidx the decoder reference index of the reference
     * @param filter2d the interpolation filter pair
     */
    void mc(short[] dst, int dstOff, int dstStride, int bw4, int bh4, int bx, int by, int pl, int mv,
            Av1Picture refp, int refidx, int filter2d) {
        var f = frame.f;
        if (frame.scaled(refidx)) {
            EncoderBridge.predictSingle(t, dst, dstOff, dstStride, bw4, bh4, bx, by, pl, mv, refp, refidx, filter2d);
            return;
        }
        var dsp = f.mc;
        var ssx = pl != 0 ? frame.ssHor : 0;
        var ssy = pl != 0 ? frame.ssVer : 0;
        var hMul = 4 >> ssx;
        var vMul = 4 >> ssy;
        var mvx = RefMvs.mvX(mv);
        var mvy = RefMvs.mvY(mv);
        var mx = mvx & (15 >> (ssx ^ 1));
        var my = mvy & (15 >> (ssy ^ 1));
        var refStride = refp.stride[pl != 0 ? 1 : 0];
        var refData = refp.data[pl];
        var dx = bx * hMul + (mvx >> (3 + ssx));
        var dy = by * vMul + (mvy >> (3 + ssy));
        var w = (f.cur.w + ssx) >> ssx;
        var h = (f.cur.h + ssy) >> ssy;
        var hasMx = mx != 0 ? 1 : 0;
        var hasMy = my != 0 ? 1 : 0;
        short[] ref;
        int refOff;
        if (dx < hasMx * 3 || dy < hasMy * 3 || dx + bw4 * hMul + hasMx * 4 > w || dy + bh4 * vMul + hasMy * 4 > h) {
            dsp.emuEdge(bw4 * hMul + hasMx * 7, bh4 * vMul + hasMy * 7, w, h, dx - hasMx * 3, dy - hasMy * 3,
                    t.emuEdge, 0, EMU_STRIDE, refData, 0, refStride);
            ref = t.emuEdge;
            refOff = EMU_STRIDE * hasMy * 3 + hasMx * 3;
            refStride = EMU_STRIDE;
        } else {
            ref = refData;
            refOff = refStride * dy + dx;
        }
        dsp.mc(filter2d, dst, dstOff, dstStride, ref, refOff, refStride, bw4 * hMul, bh4 * vMul,
                mx << (ssx ^ 1), my << (ssy ^ 1), t.mcScratch, frame.bdMax);
    }

    /**
     * The motion mode of a block predicted with a local warp.
     */
    private static final int MOTION_MODE_WARP = 2;

    /**
     * Predicts the current inter block into the reconstruction picture with its decided
     * reference, vector, filter and motion mode.
     *
     * @param bs the block size
     * @param b the block
     */
    private void predictInter(int bs, Av1Block b) {
        var f = frame.f;
        var cur = f.cur;
        var bw4 = BLOCK_DIMENSIONS[bs * 4];
        var bh4 = BLOCK_DIMENSIONS[bs * 4 + 1];
        var yStride = cur.stride[0];
        var uvStride = cur.stride[1];
        var dst = 4 * (t.by * yStride + t.bx);
        var ssHor = frame.ssHor;
        var ssVer = frame.ssVer;
        var planes = frame.planes;
        var uvdstoff = 4 * ((t.bx >> ssHor) + (t.by >> ssVer) * uvStride);
        var refp = f.refp[b.ref[0]];
        if (b.ref[1] >= 0) {
            EncoderBridge.predictCompound(t, bs, b, dst, uvdstoff);
        } else if (b.motionMode == MOTION_MODE_WARP) {
            EncoderBridge.predictWarp(t, cur.data[0], dst, yStride, bs, 0, refp);
            for (var pl = 1; pl < planes; pl++) {
                if (Math.min((bw4 + ssHor) >> ssHor, (bh4 + ssVer) >> ssVer) > 1) {
                    EncoderBridge.predictWarp(t, cur.data[pl], uvdstoff, uvStride, bs, pl, refp);
                } else {
                    mc(cur.data[pl], uvdstoff, uvStride, bw4, bh4, t.bx & ~ssHor, t.by & ~ssVer, pl, b.mv[0], refp,
                            b.ref[0], b.filter2d);
                }
            }
            return;
        } else if (pick.predictionReusable) {
            var pred = pick.bestPrediction();
            var bw = bw4 * 4;
            for (var y = 0; y < bh4 * 4; y++) {
                System.arraycopy(pred, y * bw, cur.data[0], dst + y * yStride, bw);
            }
        } else {
            mc(cur.data[0], dst, yStride, bw4, bh4, t.bx, t.by, 0, b.mv[0], refp, b.ref[0], b.filter2d);
        }
        for (var pl = 1; pl < planes && b.ref[1] < 0; pl++) {
            mc(cur.data[pl], uvdstoff, uvStride, bw4, bh4, t.bx & ~ssHor, t.by & ~ssVer, pl, b.mv[0], refp,
                    b.ref[0], b.filter2d);
        }
    }

    /**
     * Costs each single-reference candidate the mode decision kept by coding it and keeps the
     * cheapest as the block's mode.
     *
     * @param bs the block size
     */
    private void refineInter(int bs) {
        var f = frame.f;
        var b = t.b;
        var hdr = f.frameHdr;
        var skipCtx = t.a.skip[t.bx & 31] + t.l.skip[t.by & 31];
        var skipRate = frame.rd.skip[skipCtx * 2 + 1];
        var noSkipRate = frame.rd.skip[skipCtx * 2];
        var rdmult = frame.rd.segmentRdmult[b.segId];
        var bw4 = BLOCK_DIMENSIONS[bs * 4];
        var bh4 = BLOCK_DIMENSIONS[bs * 4 + 1];
        var w4 = Math.min(bw4, f.bw - t.bx);
        var h4 = Math.min(bh4, f.bh - t.by);
        var maxYtx = Tables.MAX_TXFM_SIZE_FOR_BS[bs * 4];
        var yStride = f.cur.stride[0];
        var dst = 4 * (t.by * yStride + t.bx);
        searchInterType = frame.speed.interTxTypes > 0 && !hdr.reducedTxtpSet && hdr.segQidx[b.segId] > 0;
        var best = -1;
        var bestCost = Long.MAX_VALUE;
        var bestSkip = false;
        var any = false;
        for (var j = 0; j < pick.finalistCount; j++) {
            any |= !pick.finalistSkip(j);
        }
        if (!any) {
            return;
        }
        trialLite = !frame.speed.finalistFull;
        for (var j = 0; j < pick.finalistCount; j++) {
            if (pick.finalistRd(j) > pick.finalistRd(0) / 100 * (100 + frame.speed.finalistMargin)) {
                break;
            }
            pick.applyFinalist(j, bs);
            predictInter(bs, b);
            var sse = predictionError(w4, h4);
            long skipCost = RdCost.rdcost(rdmult, skipRate, sse << 4);
            long cost = Long.MAX_VALUE;
            if (searchInterType && frame.speed.interTxSize) {
                var tx = chooseTxDepth(maxYtx, dst, yStride, w4, h4);
                if (tx >= 0) {
                    cost = lastDepthCost + chromaTrialCost(bs, w4, h4) + RdCost.rdcost(rdmult, noSkipRate, 0);
                }
            }
            var skip = skipCost <= cost;
            var total = Math.min(skipCost, cost);
            var residual = total;
            total += RdCost.rdcost(rdmult, pick.finalistRate(j), 0);
            if (total < bestCost) {
                bestCost = total;
                best = j;
                bestSkip = skip;
                refinedResidual = residual;
            }
        }
        trialLite = false;
        if (best >= 0) {
            pick.applyFinalist(best, bs);
            pick.skipTxfm = bestSkip;
            refinedValid = true;
            refinedCoded = !bestSkip;
        }
    }

    /**
     * The residual cost of the finalist the last refinement kept, valid when {@link #refinedValid}.
     */
    private long refinedResidual;

    /**
     * Whether the finalist the last refinement kept codes its residual.
     */
    private boolean refinedCoded;

    /**
     * Whether the current block's mode comes from a refinement that measured its residual cost.
     */
    private boolean refinedValid;

    /**
     * Whether the last {@link #residualCost} found coding the residual cheaper than skipping it.
     */
    private boolean residualCodedCheaper;

    /**
     * Returns the rate-distortion cost of the current inter block's residual: the cheaper of
     * skipping it and coding it with the searched transforms, without the block's mode signaling.
     *
     * @param bs the block size
     * @return the cost, or {@link Long#MAX_VALUE} when the residual is not searched
     */
    private long residualCost(int bs) {
        var f = frame.f;
        var b = t.b;
        var hdr = f.frameHdr;
        searchInterType = frame.speed.interTxTypes > 0 && !hdr.reducedTxtpSet && hdr.segQidx[b.segId] > 0;
        if (!searchInterType || !frame.speed.interTxSize) {
            return Long.MAX_VALUE;
        }
        var skipCtx = t.a.skip[t.bx & 31] + t.l.skip[t.by & 31];
        var skipRate = frame.rd.skip[skipCtx * 2 + 1];
        var noSkipRate = frame.rd.skip[skipCtx * 2];
        var rdmult = frame.rd.segmentRdmult[b.segId];
        var bw4 = BLOCK_DIMENSIONS[bs * 4];
        var bh4 = BLOCK_DIMENSIONS[bs * 4 + 1];
        var w4 = Math.min(bw4, f.bw - t.bx);
        var h4 = Math.min(bh4, f.bh - t.by);
        var maxYtx = Tables.MAX_TXFM_SIZE_FOR_BS[bs * 4];
        var yStride = f.cur.stride[0];
        var dst = 4 * (t.by * yStride + t.bx);
        trialLite = !frame.speed.finalistFull;
        predictInter(bs, b);
        var sse = predictionError(w4, h4);
        long skipCost = RdCost.rdcost(rdmult, skipRate, sse << 4);
        long cost = Long.MAX_VALUE;
        var tx = chooseTxDepth(maxYtx, dst, yStride, w4, h4);
        if (tx >= 0) {
            cost = lastDepthCost + chromaTrialCost(bs, w4, h4) + RdCost.rdcost(rdmult, noSkipRate, 0);
        }
        trialLite = false;
        residualCodedCheaper = cost < skipCost;
        return Math.min(skipCost, cost);
    }

    /**
     * Chooses between the translation and the local warp for the current single-reference block
     * whose reference, vector and filter are decided, by coding it both ways, and leaves the choice
     * in the block.
     *
     * @param bs the block size
     * @param edgeFlags the block's intra edge flags
     */
    private void chooseMotionMode(int bs, int edgeFlags) {
        var b = t.b;
        if (b.intra || b.ref[1] >= 0 || b.skipMode || !frame.f.frameHdr.switchableMotionMode) {
            return;
        }
        if (EncoderBridge.motionModeKind(t, bs, edgeFlags, b.ref[0]) != 3 || !EncoderBridge.deriveWarp(t, bs, b.mv[0])) {
            return;
        }
        var f = frame.f;
        var rdmult = frame.rd.segmentRdmult[b.segId];
        RdCost.costsFromCdf(motionModeCosts, 0, f.inCdf, Cdf.MOTION_MODE + bs * 4, 3);
        var filterRate = f.frameHdr.subpelFilterMode == FrameHeader.FILTER_SWITCHABLE
                && b.interMode != ModeWriter.GLOBALMV ? pick.currentFilterRate() : 0;
        b.motionMode = 0;
        var translation = refinedValid ? refinedResidual : residualCost(bs);
        if (translation == Long.MAX_VALUE) {
            return;
        }
        var translationCoded = refinedValid ? refinedCoded : residualCodedCheaper;
        translation += RdCost.rdcost(rdmult, motionModeCosts[0] + filterRate, 0);
        var filter2d = b.filter2d;
        b.motionMode = MOTION_MODE_WARP;
        b.filter2d = Tables.FILTER_2D[0];
        var warp = residualCost(bs);
        warp += RdCost.rdcost(rdmult, motionModeCosts[2], 0);
        if (warp < translation) {
            pick.filter = 0;
            pick.skipTxfm = !residualCodedCheaper;
            pick.predictionReusable = false;
        } else {
            b.motionMode = 0;
            b.filter2d = filter2d;
            pick.skipTxfm = !translationCoded;
            pick.predictionReusable = false;
        }
    }

    /**
     * The costs of the three motion modes of the current block.
     */
    private final int[] motionModeCosts = new int[4];

    /**
     * Returns the sum of squared errors of the current block's prediction in the picture against
     * the source, over the luma and chroma samples inside the frame.
     *
     * @param w4 the visible width in 4-sample units
     * @param h4 the visible height in 4-sample units
     * @return the error
     */
    private long predictionError(int w4, int h4) {
        var f = frame.f;
        var cur = f.cur;
        long sse = 0;
        for (var pl = 0; pl < frame.planes; pl++) {
            var ssx = pl == 0 ? 0 : frame.ssHor;
            var ssy = pl == 0 ? 0 : frame.ssVer;
            var stride = cur.stride[Math.min(pl, 1)];
            var plane = cur.data[pl];
            var src = frame.source.data[pl];
            var x0 = t.bx * 4 >> ssx;
            var y0 = t.by * 4 >> ssy;
            var w = Math.max(1, (w4 * 4) >> ssx);
            var h = Math.max(1, (h4 * 4) >> ssy);
            for (var y = 0; y < h; y++) {
                var o = (y0 + y) * stride + x0;
                for (var x = 0; x < w; x++) {
                    long d = src[o + x] - plane[o + x];
                    sse += d * d;
                }
            }
        }
        return sse >> frame.distShift();
    }

    /**
     * Returns the cost of coding the chroma of the current inter block with {@code DCT_DCT}
     * transforms: the rate of the levels with the frame's initial CDFs and the distortion of the
     * coefficients.
     *
     * @param bs the block size
     * @param w4 the visible width in 4-sample units
     * @param h4 the visible height in 4-sample units
     * @return the cost
     */
    private long chromaTrialCost(int bs, int w4, int h4) {
        if (frame.planes == 1) {
            return 0;
        }
        var f = frame.f;
        var hdr = f.frameHdr;
        var b = t.b;
        var cur = f.cur;
        var ssHor = frame.ssHor;
        var ssVer = frame.ssVer;
        var uvStride = cur.stride[1];
        var uvtx = b.uvtx;
        var uvW = Tables.TX_W[uvtx];
        var uvH = Tables.TX_H[uvtx];
        var cw4 = (w4 + ssHor) >> ssHor;
        var ch4 = (h4 + ssVer) >> ssVer;
        var cbx4 = (t.bx & 31) >> ssHor;
        var cby4 = (t.by & 31) >> ssVer;
        var uvdstoff = 4 * ((t.bx >> ssHor) + (t.by >> ssVer) * uvStride);
        var w = uvW * 4;
        var h = uvH * 4;
        var n = Math.min(w, 32) * Math.min(h, 32);
        var logScale = Math.max(0, Tables.TX_CTX[uvtx] - 2);
        var qidx = hdr.segQidx[b.segId];
        var rdmult = frame.rd.segmentRdmult[b.segId];
        long total = 0;
        for (var pl = 0; pl < 2; pl++) {
            var q = frame.quantizer(b.segId, 1 + pl);
            var dcDq = q.dequant(false);
            var acDq = q.dequant(true);
            var plane = cur.data[1 + pl];
            var src = frame.source.data[1 + pl];
            var scan = TxbCoder.scanOf(uvtx, 0);
            var iscan = TxbCoder.inverseScanOf(uvtx, 0);
            for (var y = 0; y < ch4; y += uvH) {
                for (var x = 0; x < cw4; x += uvW) {
                    var off = uvdstoff + 4 * (y * uvStride + x);
                    for (var r = 0; r < h; r++) {
                        var s = off + r * uvStride;
                        var d = r * w;
                        for (var c = 0; c < w; c++) {
                            residual[d + c] = src[s + c] - plane[s + c];
                        }
                    }
                    forward(uvtx, 0, w, h, trialCoef);
                    var eob = q.quantizeB(trialCoef, n, scan, iscan, logScale, trialLevels);
                    var error = dsp.levelError(trialCoef, trialLevels, n, dcDq, acDq, logScale);
                    counter.startCounting();
                    coefWriter.write(counter, f.inCdf, t.a.ccoef, pl * 32 + cbx4 + x, t.l.ccoef, pl * 32 + cby4 + y,
                            uvtx, bs, 1 + pl, false, 0, hdr.reducedTxtpSet, qidx, 0, trialLevels, 0, eob, ssHor,
                            ssVer);
                    total += RdCost.rdcost(rdmult, counter.cost(), (error >> (6 - 2 * logScale)) << 4);
                }
            }
        }
        return total;
    }

    /**
     * Predicts an inter block into the picture and codes its residual ({@code recon_b_inter}).
     *
     * @param bs the block size
     * @param b the block
     */
    private void reconInter(int bs, Av1Block b) {
        var f = frame.f;
        var cur = f.cur;
        var bw4 = BLOCK_DIMENSIONS[bs * 4];
        var bh4 = BLOCK_DIMENSIONS[bs * 4 + 1];
        var yStride = cur.stride[0];
        var uvStride = cur.stride[1];
        var dst = 4 * (t.by * yStride + t.bx);
        var ssHor = frame.ssHor;
        var ssVer = frame.ssVer;
        var uvdstoff = 4 * ((t.bx >> ssHor) + (t.by >> ssVer) * uvStride);
        predictInter(bs, b);
        var bx4 = t.bx & 31;
        var by4 = t.by & 31;
        var w4 = Math.min(bw4, f.bw - t.bx);
        var h4 = Math.min(bh4, f.bh - t.by);
        var cw4 = (w4 + ssHor) >> ssHor;
        var ch4 = (h4 + ssVer) >> ssVer;
        var cbx4 = bx4 >> ssHor;
        var cby4 = by4 >> ssVer;
        var uvtx = b.uvtx;
        var uvW = Tables.TX_W[uvtx];
        var uvH = Tables.TX_H[uvtx];
        var maxYtx = Tables.MAX_TXFM_SIZE_FOR_BS[bs * 4];
        var hdr = f.frameHdr;
        searchInterType = frame.speed.interTxTypes > 0 && !hdr.reducedTxtpSet && hdr.segQidx[b.segId] > 0;
        if (searchInterType && frame.speed.interTxSize && !forceSkip) {
            var tx = chooseTxDepth(maxYtx, dst, yStride, w4, h4);
            if (tx >= 0) {
                b.tx = tx;
            }
        }
        var target = 0;
        for (var tx = maxYtx; tx != b.tx; tx = Tables.TX_SUB[tx]) {
            target++;
        }
        var yW = Tables.TX_W[maxYtx];
        var yH = Tables.TX_H[maxYtx];
        for (var initY = 0; initY < bh4; initY += 16) {
            for (var initX = 0; initX < bw4; initX += 16) {
                int y;
                for (y = initY, t.by += initY; y < Math.min(h4, initY + 16); y += yH) {
                    int x;
                    for (x = initX, t.bx += initX; x < Math.min(w4, initX + 16); x += yW) {
                        codeTxTree(maxYtx, 0, target, 4 * (t.by * yStride + t.bx));
                        t.bx += yW;
                    }
                    t.bx -= x;
                    t.by += yH;
                }
                t.by -= y;
                for (var pl = 0; pl < frame.planes - 1; pl++) {
                    var plane = cur.data[1 + pl];
                    for (y = initY >> ssVer; y < Math.min(ch4, (initY + 16) >> ssVer); y += uvH) {
                        for (var x = initX >> ssHor; x < Math.min(cw4, (initX + 16) >> ssHor); x += uvW) {
                            var bx = t.bx + (x << ssHor);
                            var by = t.by + (y << ssVer);
                            chromaLumaTxtp = lumaTypeMap[(by & 31) * 32 + (bx & 31)];
                            codeTx(1 + pl, uvtx, plane, uvdstoff + 4 * (y * uvStride + x), uvStride,
                                    pl * 32 + cbx4 + x, pl * 32 + cby4 + y,
                                    Math.min(uvW, (f.bw - bx + ssHor) >> ssHor),
                                    Math.min(uvH, (f.bh - by + ssVer) >> ssVer));
                        }
                    }
                }
            }
        }
    }

    /**
     * Codes the luma transform blocks of one transform tree node of an inter block in the
     * decoder's order ({@code read_coef_tree}), splitting every node above the target depth.
     *
     * @param ytx the node's transform size
     * @param depth the node's depth
     * @param target the depth of the leaves
     * @param dst the offset of the node's top-left luma sample
     */
    private void codeTxTree(int ytx, int depth, int target, int dst) {
        var f = frame.f;
        var txw = Tables.TX_W[ytx];
        var txh = Tables.TX_H[ytx];
        var stride = f.cur.stride[0];
        if (depth < 2 && ytx > 0 && depth < target) {
            var sub = Tables.TX_SUB[ytx];
            var txsw = Tables.TX_W[sub];
            var txsh = Tables.TX_H[sub];
            codeTxTree(sub, depth + 1, target, dst);
            t.bx += txsw;
            if (txw >= txh && t.bx < f.bw) {
                codeTxTree(sub, depth + 1, target, dst + 4 * txsw);
            }
            t.bx -= txsw;
            t.by += txsh;
            if (txh >= txw && t.by < f.bh) {
                var below = dst + 4 * txsh * stride;
                codeTxTree(sub, depth + 1, target, below);
                t.bx += txsw;
                if (txw >= txh && t.bx < f.bw) {
                    codeTxTree(sub, depth + 1, target, below + 4 * txsw);
                }
                t.bx -= txsw;
            }
            t.by -= txsh;
        } else {
            codeTx(0, ytx, f.cur.data[0], dst, stride, t.bx & 31, t.by & 31, Math.min(txw, f.bw - t.bx),
                    Math.min(txh, f.bh - t.by));
        }
    }

    /**
     * Writes a block's symbols and updates the contexts, loop filter masks and motion records.
     *
     * @param bs the block size
     * @param edgeFlags the block's intra edge flags
     * @param b the block, decided and reconstructed
     */
    private void writeBlock(int bs, int edgeFlags, Av1Block b) {
        var f = frame.f;
        var hdr = f.frameHdr;
        var cdf = ts.cdf;
        var bx4 = t.bx & 31;
        var by4 = t.by & 31;
        var bw4 = BLOCK_DIMENSIONS[bs * 4];
        var bh4 = BLOCK_DIMENSIONS[bs * 4 + 1];
        var bwl = BLOCK_DIMENSIONS[bs * 4 + 2];
        var bhl = BLOCK_DIMENSIONS[bs * 4 + 3];
        var ssHor = frame.ssHor;
        var ssVer = frame.ssVer;
        var cbx4 = bx4 >> ssHor;
        var cby4 = by4 >> ssVer;
        var cbw4 = (bw4 + ssHor) >> ssHor;
        var cbh4 = (bh4 + ssVer) >> ssVer;
        var chroma = frame.planes > 1;
        var haveLeft = t.bx > ts.colStart;
        var haveTop = t.by > ts.rowStart;
        if (hdr.skipModeEnabled && Math.min(bw4, bh4) > 1) {
            var smctx = t.a.skipMode[bx4] + t.l.skipMode[by4];
            ec.encodeBool(cdf, Cdf.SKIP_MODE + smctx * 2, b.skipMode ? 1 : 0);
        }
        if (!b.skipMode) {
            var sctx = t.a.skip[bx4] + t.l.skip[by4];
            ec.encodeBool(cdf, Cdf.SKIP + sctx * 2, b.skip ? 1 : 0);
        }
        if (hdr.segmentationEnabled && hdr.segmentationUpdateMap) {
            writeSegment(b, haveTop, haveLeft);
        }
        if (!b.skip) {
            var cdefIdx = t.lfMask.cdefIdx;
            var base = t.curSbCdefIdx;
            if (cdefIdx[base] == -1) {
                if (frame.deferCdef) {
                    ec.encodeDeferred((t.by >> 4) * frame.sbCols() + (t.bx >> 4));
                    cdefIdx[base] = 0;
                } else {
                    var v = frame.cdefIndex();
                    ec.encodeBits(v, hdr.cdefNBits);
                    cdefIdx[base] = (byte) v;
                }
            }
        }
        var inter = hdr.isInterOrSwitch();
        if (inter && !b.skipMode) {
            var ictx = ModeWriter.intraCtx(t.a, t.l, by4, bx4, haveTop, haveLeft);
            ec.encodeBool(cdf, Cdf.INTRA + ictx * 2, b.intra ? 0 : 1);
        }
        var layout = frame.layout;
        if (b.intra) {
            var yModeCdf = inter ? Cdf.Y_MODE + Tables.YMODE_SIZE_CONTEXT[bs] * 16
                    : ModeWriter.kfYModeCdf(t.a, t.l, bx4, by4);
            ModeWriter.writeIntraModes(ec, cdf, yModeCdf, bs, b, chroma,
                    Tables.cflAllowed(bs, hdr.segLossless[b.segId], cbw4, cbh4));
            if (hdr.allowScreenContentTools && Math.max(bw4, bh4) <= 16 && bw4 + bh4 >= 4) {
                var szCtx = bwl + bhl - 2;
                if (b.yMode == IpredDsp.DC_PRED) {
                    var palCtx = (t.a.palSz[bx4] > 0 ? 1 : 0) + (t.l.palSz[by4] > 0 ? 1 : 0);
                    ec.encodeBool(cdf, Cdf.PAL_Y + (szCtx * 3 + palCtx) * 2, b.palSz[0] > 0 ? 1 : 0);
                    if (b.palSz[0] > 0) {
                        palette.writeColors(ec, cdf, t, 0, szCtx, bx4, by4, t.pal, 0, b.palSz[0], frame.bitDepth);
                    }
                }
                if (chroma && b.uvMode == IpredDsp.DC_PRED) {
                    // TODO: search chroma palettes and intra block copy, as the still encoder searches
                    // chroma palettes; this encoder's frames code luma palettes only
                    ec.encodeBool(cdf, Cdf.PAL_UV + (b.palSz[0] > 0 ? 2 : 0), 0);
                }
            }
            if (f.seqHdr.filterIntra && b.yMode == IpredDsp.DC_PRED && b.palSz[0] == 0 && Math.max(bwl, bhl) <= 3) {
                ec.encodeBool(cdf, Cdf.USE_FILTER_INTRA + bs * 2, 0);
            }
            if (b.palSz[0] > 0) {
                palette.writeIndices(ec, cdf, pick.paletteMap, bw4 * 4, b.palSz[0], 0,
                        Math.min(bw4, f.bw - t.bx) * 4, Math.min(bh4, f.bh - t.by) * 4);
            }
            if (hdr.txfmMode == FrameHeader.TX_SWITCHABLE) {
                ModeWriter.writeIntraTxSize(ec, cdf, t.a, t.l, bx4, by4, bs, b.tx);
            }
            writeCoefs(bs, b, true);
            if (hdr.loopfilterLevelY[0] != 0 || hdr.loopfilterLevelY[1] != 0) {
                LoopFilterMasks.createLfMaskIntra(t.lfMask, f.lfLevel, f.b4Stride, ts.lflvl, b.segId * 64,
                        t.bx, t.by, f.w4, f.h4, bs, b.tx, b.uvtx, layout,
                        t.a.txLpfY, bx4, t.l.txLpfY, by4, chroma ? t.a.txLpfUv : null, cbx4,
                        chroma ? t.l.txLpfUv : null, cby4);
            }
            updateIntraCtx(t.a, bx4, bw4, Tables.TX_LW[b.tx], b, inter);
            updateIntraCtx(t.l, by4, bh4, Tables.TX_LH[b.tx], b, inter);
            if (b.palSz[0] > 0) {
                EncoderBridge.copyPalBlockY(t, bx4, by4, bw4, bh4);
            }
            if (chroma) {
                Arrays.fill(t.a.uvmode, cbx4, cbx4 + cbw4, (byte) b.uvMode);
                Arrays.fill(t.l.uvmode, cby4, cby4 + cbh4, (byte) b.uvMode);
            }
            if (inter) {
                t.rt.splat((t.by & 31) + 5, t.bx, bw4, bh4, RefMvs.INVALID_MV, 0, RefMvs.packInfo(0, -1, bs, 0));
            }
        } else {
            var comp = b.ref[1] >= 0;
            if (hdr.switchableCompRefs && Math.min(bw4, bh4) > 1 && !b.skipMode) {
                var cctx = EncoderBridge.compContext(t, by4, bx4, haveTop, haveLeft);
                ec.encodeBool(cdf, Cdf.COMP + cctx * 2, comp ? 1 : 0);
            }
            if (b.skipMode) {
                b.skip = true;
            } else if (comp) {
                ModeWriter.writeCompoundRefs(ec, cdf, t, bx4, by4, haveTop, haveLeft, b.ref[0], b.ref[1]);
                ModeWriter.writeCompoundMode(ec, cdf, hdr, chosen, b.interMode, b.drlIdx, b.mv[0], b.mv[1]);
            } else {
                ModeWriter.writeSingleRef(ec, cdf, t.a, t.l, bx4, by4, haveTop, haveLeft, b.ref[0]);
                ModeWriter.writeInterMode(ec, cdf, hdr, chosen, b.interMode, b.drlIdx, b.mv[0]);
                if (hdr.switchableMotionMode) {
                    var kind = EncoderBridge.motionModeKind(t, bs, edgeFlags, b.ref[0]);
                    if (kind == 3) {
                        ec.encodeSymbol(cdf, Cdf.MOTION_MODE + bs * 4, b.motionMode, 2);
                    } else if (kind == 1) {
                        ec.encodeBool(cdf, Cdf.OBMC + bs * 2, 0);
                    }
                }
            }
            var filter = pick.filter;
            if (hdr.subpelFilterMode == FrameHeader.FILTER_SWITCHABLE) {
                if (!b.skipMode && b.interMode != (comp ? ModeWriter.GLOBAL_GLOBALMV : ModeWriter.GLOBALMV)
                        && b.motionMode != MOTION_MODE_WARP) {
                    var fctx = comp ? EncoderBridge.filterContext(t, 1, b.ref[0], by4, bx4)
                            : ModeWriter.filterCtx(t.a, t.l, b.ref[0], by4, bx4);
                    ec.encodeSymbol(cdf, Cdf.FILTER + fctx * 4, filter, 2);
                } else {
                    filter = 0;
                }
            }
            writeVartx(bs, b, bx4, by4, bwl, bhl);
            writeCoefs(bs, b, false);
            if (hdr.loopfilterLevelY[0] != 0 || hdr.loopfilterLevelY[1] != 0) {
                var isGlobalmv = b.interMode == (comp ? ModeWriter.GLOBAL_GLOBALMV : ModeWriter.GLOBALMV);
                LoopFilterMasks.createLfMaskInter(t.lfMask, f.lfLevel, f.b4Stride, ts.lflvl,
                        b.segId * 64 + (b.ref[0] + 1) * 2 + (isGlobalmv ? 0 : 1),
                        t.bx, t.by, f.w4, f.h4, b.skip, bs, b.maxYtx, b.txSplit0, b.txSplit1, b.uvtx, layout,
                        t.a.txLpfY, bx4, t.l.txLpfY, by4, chroma ? t.a.txLpfUv : null, cbx4,
                        chroma ? t.l.txLpfUv : null, cby4, t.lfTxa);
            }
            var mode = b.interMode;
            if (comp) {
                var mf = (mode == ModeWriter.GLOBAL_GLOBALMV ? 1 : 0) | (((1 << mode) & 0xbc) != 0 ? 2 : 0);
                t.rt.splat((t.by & 31) + 5, t.bx, bw4, bh4, b.mv[0], b.mv[1],
                        RefMvs.packInfo(b.ref[0] + 1, b.ref[1] + 1, bs, mf));
            } else {
                var mf = (mode == ModeWriter.GLOBALMV && Math.min(bw4, bh4) >= 2 ? 1 : 0) | (mode == ModeWriter.NEWMV ? 2 : 0);
                t.rt.splat((t.by & 31) + 5, t.bx, bw4, bh4, b.mv[0], 0, RefMvs.packInfo(b.ref[0] + 1, -1, bs, mf));
            }
            updateInterCtx(t.a, bx4, bw4, bwl, b, filter);
            updateInterCtx(t.l, by4, bh4, bhl, b, filter);
            if (chroma) {
                Arrays.fill(t.a.uvmode, cbx4, cbx4 + cbw4, (byte) IpredDsp.DC_PRED);
                Arrays.fill(t.l.uvmode, cby4, cby4 + cbh4, (byte) IpredDsp.DC_PRED);
            }
        }
        if (!b.skip) {
            var noskip = t.lfMask.noskipMask;
            var mask = (char) ((-1 >>> (32 - bw4)) << (bx4 & 15));
            var bxIdx = (bx4 & 16) >> 4;
            for (int y = 0, row = by4 >> 1; y < bh4; y += 2, row++) {
                noskip[row * 2 + bxIdx] |= mask;
                if (bw4 == 32) {
                    noskip[row * 2 + 1] |= mask;
                }
            }
        }
    }

    /**
     * Writes a block's segment after its skip flag, or makes a skipped block take its predicted
     * segment, records the segment in the frame's segment map and counts the refreshed area
     * ({@code write_segment_id} without temporal prediction).
     *
     * @param b the block
     * @param haveTop whether the top neighbor is available
     * @param haveLeft whether the left neighbor is available
     */
    private void writeSegment(Av1Block b, boolean haveTop, boolean haveLeft) {
        var f = frame.f;
        var map = f.curSegmap;
        var stride = f.b4Stride;
        var p = t.bx + t.by * stride;
        int pred;
        int ctx;
        if (haveLeft && haveTop) {
            var l = map[p - 1];
            var a = map[p - stride];
            var al = map[p - stride - 1];
            ctx = l == a && al == l ? 2 : l == a || al == l || a == al ? 1 : 0;
            pred = a == al ? a : l;
        } else {
            ctx = 0;
            pred = haveLeft ? map[p - 1] : haveTop ? map[p - stride] : 0;
        }
        if (b.skip) {
            b.segId = pred;
        } else {
            var max = f.frameHdr.segLastActiveSegid + 1;
            ec.encodeSymbol(ts.cdf, Cdf.SEG_ID + ctx * 8, negInterleave(b.segId, pred, max), 7);
        }
        var bw4 = BLOCK_DIMENSIONS[b.bs * 4];
        var bh4 = BLOCK_DIMENSIONS[b.bs * 4 + 1];
        for (var y = 0; y < bh4; y++) {
            Arrays.fill(map, p + y * stride, p + y * stride + bw4, (byte) b.segId);
        }
        var cr = frame.cyclicRefresh;
        if (cr != null) {
            cr.skippedSegment(t.by, t.bx, bh4, bw4, decidedSegment, b.segId, segmentCounts);
        }
    }

    /**
     * Maps a segment to the coded difference from its prediction ({@code av1_neg_interleave}).
     *
     * @param x the segment
     * @param ref the predicted segment
     * @param max the number of segments
     * @return the coded value
     */
    static int negInterleave(int x, int ref, int max) {
        var diff = x - ref;
        if (ref == 0) {
            return x;
        }
        if (ref >= max - 1) {
            return -x + max - 1;
        }
        if (2 * ref < max) {
            if (Math.abs(diff) <= ref) {
                return diff > 0 ? (diff << 1) - 1 : (-diff) << 1;
            }
            return x;
        }
        if (Math.abs(diff) < max - ref) {
            return diff > 0 ? (diff << 1) - 1 : (-diff) << 1;
        }
        return max - x - 1;
    }

    /**
     * Writes the transform split tree of an inter block when the frame switches transform sizes
     * and fills the transform size contexts ({@code read_vartx_tree}).
     *
     * @param bs the block size
     * @param b the block
     * @param bx4 the column within the above context
     * @param by4 the row within the left context
     * @param bwl the base-2 logarithm of the block width in 4-sample units
     * @param bhl the base-2 logarithm of the block height in 4-sample units
     */
    private void writeVartx(int bs, Av1Block b, int bx4, int by4, int bwl, int bhl) {
        var hdr = frame.f.frameHdr;
        var maxYtx = Tables.MAX_TXFM_SIZE_FOR_BS[bs * 4];
        b.maxYtx = maxYtx;
        b.txSplit0 = 0;
        b.txSplit1 = 0;
        if (hdr.txfmMode != FrameHeader.TX_SWITCHABLE || b.skip) {
            if (hdr.txfmMode == FrameHeader.TX_SWITCHABLE) {
                Arrays.fill(t.a.tx, bx4, bx4 + (1 << bwl), (byte) bwl);
                Arrays.fill(t.l.tx, by4, by4 + (1 << bhl), (byte) bhl);
            }
            return;
        }
        var depth = 0;
        for (var tx = maxYtx; tx != b.tx; tx = Tables.TX_SUB[tx]) {
            depth++;
        }
        var bw4 = BLOCK_DIMENSIONS[bs * 4];
        var bh4 = BLOCK_DIMENSIONS[bs * 4 + 1];
        var tw = Tables.TX_W[maxYtx];
        var th = Tables.TX_H[maxYtx];
        int y;
        int x = 0;
        int yOff;
        int xOff;
        var masks = t.warpMvd;
        masks[0] = 0;
        masks[1] = 0;
        for (y = 0, yOff = 0; y < bh4; y += th, yOff++) {
            for (x = 0, xOff = 0; x < bw4; x += tw, xOff++) {
                writeTxTree(maxYtx, 0, depth, masks, xOff, yOff);
                t.bx += tw;
            }
            t.bx -= x;
            t.by += th;
        }
        t.by -= y;
        b.txSplit0 = masks[0] & 0xFF;
        b.txSplit1 = masks[1] & 0xFFFF;
    }

    /**
     * Writes the split flags of one transform tree node and its children ({@code read_tx_tree}).
     *
     * @param from the node's transform size
     * @param depth the node's depth
     * @param target the depth of the leaves
     * @param masks the split flags of each depth
     * @param xOff the node's column in transform units
     * @param yOff the node's row in transform units
     */
    private void writeTxTree(int from, int depth, int target, int[] masks, int xOff, int yOff) {
        var f = frame.f;
        var bx4 = t.bx & 31;
        var by4 = t.by & 31;
        var txw = Tables.TX_LW[from];
        var txh = Tables.TX_LH[from];
        var isSplit = false;
        if (depth < 2 && from > 0) {
            isSplit = depth < target;
            var cat = 2 * (4 - Tables.TX_MAX[from]) - depth;
            var a = t.a.tx[bx4] < txw ? 1 : 0;
            var l = t.l.tx[by4] < txh ? 1 : 0;
            ec.encodeBool(ts.cdf, Cdf.TXPART + (cat * 3 + a + l) * 2, isSplit ? 1 : 0);
            if (isSplit) {
                masks[depth] |= 1 << (yOff * 4 + xOff);
            }
        }
        if (isSplit && Tables.TX_MAX[from] > 1) {
            var sub = Tables.TX_SUB[from];
            var txsw = Tables.TX_W[sub];
            var txsh = Tables.TX_H[sub];
            writeTxTree(sub, depth + 1, target, masks, xOff * 2, yOff * 2);
            t.bx += txsw;
            if (txw >= txh && t.bx < f.bw) {
                writeTxTree(sub, depth + 1, target, masks, xOff * 2 + 1, yOff * 2);
            }
            t.bx -= txsw;
            t.by += txsh;
            if (txh >= txw && t.by < f.bh) {
                writeTxTree(sub, depth + 1, target, masks, xOff * 2, yOff * 2 + 1);
                t.bx += txsw;
                if (txw >= txh && t.bx < f.bw) {
                    writeTxTree(sub, depth + 1, target, masks, xOff * 2 + 1, yOff * 2 + 1);
                }
                t.bx -= txsw;
            }
            t.by -= txsh;
        } else {
            Arrays.fill(t.a.tx, bx4, bx4 + (1 << Tables.TX_LW[from]), (byte) (isSplit ? 0 : txw));
            Arrays.fill(t.l.tx, by4, by4 + (1 << Tables.TX_LH[from]), (byte) (isSplit ? 0 : txh));
        }
    }

    /**
     * Writes the recorded transform blocks of a block in coding order and fills the coefficient
     * contexts.
     *
     * @param bs the block size
     * @param b the block
     * @param intra whether the block is intra coded
     */
    private void writeCoefs(int bs, Av1Block b, boolean intra) {
        var hdr = frame.f.frameHdr;
        var a = t.a;
        var l = t.l;
        if (!intra && b.skip) {
            var bx4 = t.bx & 31;
            var by4 = t.by & 31;
            var bw4 = BLOCK_DIMENSIONS[bs * 4];
            var bh4 = BLOCK_DIMENSIONS[bs * 4 + 1];
            Arrays.fill(a.lcoef, bx4, bx4 + bw4, (byte) 0x40);
            Arrays.fill(l.lcoef, by4, by4 + bh4, (byte) 0x40);
            if (frame.planes == 1) {
                return;
            }
            var cbx4 = bx4 >> frame.ssHor;
            var cby4 = by4 >> frame.ssVer;
            var cbw4 = (bw4 + frame.ssHor) >> frame.ssHor;
            var cbh4 = (bh4 + frame.ssVer) >> frame.ssVer;
            Arrays.fill(a.ccoef, cbx4, cbx4 + cbw4, (byte) 0x40);
            Arrays.fill(a.ccoef, 32 + cbx4, 32 + cbx4 + cbw4, (byte) 0x40);
            Arrays.fill(l.ccoef, cby4, cby4 + cbh4, (byte) 0x40);
            Arrays.fill(l.ccoef, 32 + cby4, 32 + cby4 + cbh4, (byte) 0x40);
            return;
        }
        var qidx = hdr.segQidx[b.segId];
        for (var i = 0; i < nTx; i++) {
            var plane = txPlane[i];
            var tx = txSize[i];
            var ab = plane == 0 ? a.lcoef : a.ccoef;
            var lb = plane == 0 ? l.lcoef : l.ccoef;
            if (b.skip) {
                Arrays.fill(ab, txAOff[i], txAOff[i] + (1 << Tables.TX_LW[tx]), (byte) 0x40);
                Arrays.fill(lb, txLOff[i], txLOff[i] + (1 << Tables.TX_LH[tx]), (byte) 0x40);
                continue;
            }
            var ctx = coefWriter.write(ec, ts.cdf, ab, txAOff[i], lb, txLOff[i], tx, bs, plane, intra,
                    b.yMode, hdr.reducedTxtpSet, qidx, txType[i], levels, txLevelOff[i], txEob[i], frame.ssHor,
                    frame.ssVer);
            Arrays.fill(ab, txAOff[i], txAOff[i] + txCtxW[i], (byte) ctx);
            Arrays.fill(lb, txLOff[i], txLOff[i] + txCtxH[i], (byte) ctx);
        }
    }

    /**
     * Updates one edge of the contexts after an intra block.
     *
     * @param edge the above or left context
     * @param off the position within the context
     * @param n the number of entries
     * @param txLsz the base-2 logarithm of the transform size along the edge
     * @param b the block
     * @param inter whether the frame is an inter frame
     */
    private static void updateIntraCtx(com.github.auties00.smalt.video.av1.internal.BlockContext edge, int off, int n,
                                       int txLsz, Av1Block b, boolean inter) {
        Arrays.fill(edge.txIntra, off, off + n, (byte) txLsz);
        Arrays.fill(edge.tx, off, off + n, (byte) txLsz);
        Arrays.fill(edge.mode, off, off + n, (byte) b.yMode);
        Arrays.fill(edge.palSz, off, off + n, (byte) b.palSz[0]);
        Arrays.fill(edge.segPred, off, off + n, (byte) 0);
        Arrays.fill(edge.skipMode, off, off + n, (byte) 0);
        Arrays.fill(edge.intra, off, off + n, (byte) 1);
        Arrays.fill(edge.skip, off, off + n, (byte) (b.skip ? 1 : 0));
        if (inter) {
            Arrays.fill(edge.compType, off, off + n, (byte) 0);
            Arrays.fill(edge.ref, off, off + n, (byte) -1);
            Arrays.fill(edge.ref, 32 + off, 32 + off + n, (byte) -1);
            Arrays.fill(edge.filter, off, off + n, (byte) 3);
            Arrays.fill(edge.filter, 32 + off, 32 + off + n, (byte) 3);
        }
    }

    /**
     * Updates one edge of the contexts after an inter block.
     *
     * @param edge the above or left context
     * @param off the position within the context
     * @param n the number of entries
     * @param lsz the base-2 logarithm of the block size along the edge
     * @param b the block
     * @param filter the interpolation filter the decoder records for the block
     */
    private static void updateInterCtx(com.github.auties00.smalt.video.av1.internal.BlockContext edge, int off, int n,
                                       int lsz, Av1Block b, int filter) {
        Arrays.fill(edge.segPred, off, off + n, (byte) 0);
        Arrays.fill(edge.skipMode, off, off + n, (byte) (b.skipMode ? 1 : 0));
        Arrays.fill(edge.intra, off, off + n, (byte) 0);
        Arrays.fill(edge.skip, off, off + n, (byte) (b.skip ? 1 : 0));
        Arrays.fill(edge.palSz, off, off + n, (byte) 0);
        Arrays.fill(edge.txIntra, off, off + n, (byte) lsz);
        Arrays.fill(edge.compType, off, off + n, (byte) (b.ref[1] >= 0 ? ModeWriter.COMP_INTER_AVG : 0));
        Arrays.fill(edge.filter, off, off + n, (byte) filter);
        Arrays.fill(edge.filter, 32 + off, 32 + off + n, (byte) filter);
        Arrays.fill(edge.mode, off, off + n, (byte) b.interMode);
        Arrays.fill(edge.ref, off, off + n, (byte) b.ref[0]);
        Arrays.fill(edge.ref, 32 + off, 32 + off + n, (byte) b.ref[1]);
    }
}
