package com.github.auties00.smalt.video.av1.internal.encoder;

import com.github.auties00.smalt.video.av1.internal.Av1Block;
import com.github.auties00.smalt.video.av1.internal.BlockContext;
import com.github.auties00.smalt.video.av1.internal.Cdf;
import com.github.auties00.smalt.video.av1.internal.CoefficientSource;
import com.github.auties00.smalt.video.av1.internal.EncoderBridge;
import com.github.auties00.smalt.video.av1.internal.FrameHeader;
import com.github.auties00.smalt.video.av1.internal.IntraEdge;
import com.github.auties00.smalt.video.av1.internal.IpredDsp;
import com.github.auties00.smalt.video.av1.internal.ItxDsp;
import com.github.auties00.smalt.video.av1.internal.LoopFilterMasks;
import com.github.auties00.smalt.video.av1.internal.SequenceHeader;
import com.github.auties00.smalt.video.av1.internal.Tables;
import com.github.auties00.smalt.video.av1.internal.TaskContext;
import com.github.auties00.smalt.video.av1.internal.TileState;

import java.util.Arrays;

import static com.github.auties00.smalt.video.av1.internal.Tables.BLOCK_DIMENSIONS;

/**
 * Encodes one tile of an intra-only frame: searches the partition tree and the coding of every
 * block of each superblock, then codes the superblock through the decoder's own block
 * reconstruction while recording its symbols.
 *
 * <p>The partition search is a recursive rate-distortion search: at every node the unsplit block,
 * the horizontal and vertical halves and the four-way split are coded and the cheapest kept, down
 * to 4x4 blocks. The search leaves a plan of the superblock: its partitions and block decisions in
 * coding order. The commit then walks the plan exactly as the decoder walks the bitstream: it
 * writes each partition and mode symbol, and runs the decoder's intra reconstruction with this
 * encoder installed as the coefficient source, which quantizes and writes each transform block
 * when the decoder asks for it. The picture, the loop filter masks and every context are therefore
 * what a decoder produces.
 *
 * <p>The symbols of each superblock are recorded into a {@link SymbolLog} and adapt the tile's
 * CDFs, which the next superblocks' searches cost against; the frame codes the logs once the
 * in-loop filters are chosen, supplying the CDEF index each superblock defers.
 *
 * <p>An instance is used by one thread at a time; tiles of one frame may run on different threads
 * with different instances.
 *
 * @implNote This implementation follows libaom's {@code rd_pick_partition}
 * ({@code av1/encoder/partition_search.c}) without its pruning models, and dav1d's
 * {@code decode_sb}, {@code decode_b} and {@code dav1d_decode_tile_sbrow}
 * ({@code src/decode.c}) for the coding order the commit reproduces.
 */
final class StillTileEncoder implements CoefficientSource {
    /**
     * The 64x64 block level.
     */
    private static final int BL_64X64 = 1;

    /**
     * The 8x8 block level.
     */
    private static final int BL_8X8 = 4;

    /**
     * The block level of 16x16 nodes.
     */
    private static final int BL_16X16 = 3;

    /**
     * The 4x4 block size.
     */
    private static final int BS_4X4 = 21;

    /**
     * The unsplit partition.
     */
    private static final int PARTITION_NONE = 0;

    /**
     * The horizontal partition.
     */
    private static final int PARTITION_H = 1;

    /**
     * The vertical partition.
     */
    private static final int PARTITION_V = 2;

    /**
     * The four-way split partition.
     */
    private static final int PARTITION_SPLIT = 3;

    /**
     * The frame.
     */
    private final StillFrameEncoder frame;

    /**
     * The decoder-side state of the tile.
     */
    final TaskContext t = new TaskContext();

    /**
     * The tile's bounds, CDFs and loop filter levels.
     */
    final TileState ts = new TileState();

    /**
     * The coder that records the committed symbols.
     */
    private final RangeEncoder ec = new RangeEncoder();

    /**
     * The block mode decision.
     */
    private final StillBlockSearch search;

    /**
     * The coefficient writer of the commit.
     */
    private final CoefWriter coefWriter = new CoefWriter();

    /**
     * The partitions of the planned superblock, in the order the commit visits the nodes.
     */
    private int[] partitions = new int[256];

    /**
     * The number of planned partitions.
     */
    private int nPartitions;

    /**
     * The next planned partition the commit reads.
     */
    private int partitionCursor;

    /**
     * The blocks of the planned superblock, in coding order.
     */
    private StillBlock[] blocks = new StillBlock[256];

    /**
     * The number of planned blocks.
     */
    private int nBlocks;

    /**
     * The next planned block the commit reads.
     */
    private int blockCursor;

    /**
     * The block the commit is reconstructing.
     */
    private StillBlock committing;

    /**
     * The next luma transform type of {@link #committing}.
     */
    private int typeCursor;

    /**
     * The state saved at the start of each partition node, by block level.
     */
    private final Snapshot[] start = new Snapshot[5];

    /**
     * The state of the best partition of each node so far, by block level.
     */
    private final Snapshot[] best = new Snapshot[5];

    /**
     * The tile column, for the transform edges the loop filter reads across tiles.
     */
    private int tileCol;

    /**
     * The partition network of the superblock being searched.
     */
    private final IntraPartitionCnn cnn = new IntraPartitionCnn();

    /**
     * Whether {@link #cnn} holds the current superblock's prediction.
     */
    private boolean cnnReady;

    /**
     * The resolution class of the partition network's thresholds.
     */
    private int cnnResolution;

    /**
     * The pruning level of the partition network in this frame, as {@link StillSettings#cnnPartition}.
     */
    private int cnnLevel;

    /**
     * Creates a tile encoder for a frame.
     *
     * @param frame the frame encoder
     */
    StillTileEncoder(StillFrameEncoder frame) {
        this.frame = frame;
        t.ts = ts;
        this.search = new StillBlockSearch(frame, t, ts);
        for (var i = 0; i < 5; i++) {
            start[i] = new Snapshot();
            best[i] = new Snapshot();
        }
    }

    /**
     * Encodes one tile: every superblock is searched, committed and its symbols recorded into the
     * frame's log of that superblock.
     *
     * @param tileRow the tile row
     * @param tileCol the tile column
     */
    void encode(int tileRow, int tileCol) {
        bind(tileRow, tileCol);
        var f = frame.f;
        var hdr = f.frameHdr;
        for (var sby = hdr.rowStartSb[tileRow]; sby < hdr.rowStartSb[tileRow + 1] && sby < f.sbh; sby++) {
            encodeRow(sby, hdr.colStartSb[tileCol], null, 0, -1);
        }
    }

    /**
     * Encodes one superblock row of a tile with the cost model started from the initial CDFs of
     * the frame, so the row depends on the rows above only through the reconstruction and the
     * contexts above it: the rows of a tile may be encoded at once on several encoders, each
     * waiting for the row above to be two superblocks ahead.
     *
     * @param tileRow the tile row
     * @param tileCol the tile column
     * @param sby the superblock row
     * @param wavefront the wavefront the rows synchronize on
     * @param unit the wavefront unit of the row
     * @param above the wavefront unit of the row above in the tile, or -1 for the first row
     */
    void encodeWavefrontRow(int tileRow, int tileCol, int sby, SbRowWavefront wavefront, int unit, int above) {
        bind(tileRow, tileCol);
        encodeRow(sby, frame.f.frameHdr.colStartSb[tileCol], wavefront, unit, above);
    }

    /**
     * Binds the encoder to a tile of the frame and starts its cost model from the initial CDFs of
     * the frame.
     *
     * @param tileRow the tile row
     * @param tileCol the tile column
     */
    private void bind(int tileRow, int tileCol) {
        var f = frame.f;
        var hdr = f.frameHdr;
        this.tileCol = tileCol;
        t.f = f;
        var colSbStart = hdr.colStartSb[tileCol];
        var colSbEnd = hdr.colStartSb[tileCol + 1];
        var rowSbStart = hdr.rowStartSb[tileRow];
        var rowSbEnd = hdr.rowStartSb[tileRow + 1];
        System.arraycopy(f.inCdf, 0, ts.cdf, 0, Cdf.SIZE);
        ts.lastQidx = hdr.quantYac;
        ts.lastDeltaLf[0] = ts.lastDeltaLf[1] = ts.lastDeltaLf[2] = ts.lastDeltaLf[3] = 0;
        ts.dq = f.dq;
        ts.lflvl = f.lvl;
        ts.row = tileRow;
        ts.col = tileCol;
        ts.colStart = colSbStart << f.sbShift;
        ts.colEnd = Math.min(colSbEnd << f.sbShift, f.bw);
        ts.rowStart = rowSbStart << f.sbShift;
        ts.rowEnd = Math.min(rowSbEnd << f.sbShift, f.bh);
        ec.reset(false);
        search.frameStarted();
    }

    /**
     * Encodes one superblock row of the tile.
     *
     * @param sby the superblock row
     * @param colSbStart the first superblock column of the tile
     * @param wavefront the wavefront the row synchronizes on, or {@code null} when the rows of the
     * tile are encoded in order
     * @param unit the wavefront unit of the row
     * @param above the wavefront unit of the row above, or -1
     */
    private void encodeRow(int sby, int colSbStart, SbRowWavefront wavefront, int unit, int above) {
        var f = frame.f;
        var sbCount = (ts.colEnd - ts.colStart + f.sbStep - 1) >> f.sbShift;
        var sbInRow = 0;
        t.by = sby << f.sbShift;
        t.l.reset(true);
        Arrays.fill(t.palSzUv, 32, 64, (byte) 0);
        var sb128y = t.by >> 5;
        t.aIdx = (colSbStart >> 1) + ts.row * f.sb128w;
        t.lfMaskIdx = sb128y * f.sb128w + (colSbStart >> 1);
        refreshKernels();
        for (t.bx = ts.colStart; t.bx < ts.colEnd; t.bx += f.sbStep, sbInRow++) {
            if (wavefront != null && above >= 0) {
                wavefront.await(above, Math.min(sbInRow + 2, sbCount));
            }
            t.a = f.a[t.aIdx];
            t.lfMask = f.lfMask[t.lfMaskIdx];
            t.curSbCdefIdx = ((t.bx & 16) >> 4) + ((t.by & 16) >> 3);
            t.lfMask.cdefIdx[t.curSbCdefIdx] = -1;
            var sbIndex = sby * frame.sbCols + (t.bx >> f.sbShift);
            start[0].save(t, frame, 0);
            nPartitions = 0;
            nBlocks = 0;
            prepareCnn();
            searchNode(BL_64X64, IntraEdge.SB64, Long.MAX_VALUE, 0);
            start[0].restoreContexts(t, frame);
            partitionCursor = 0;
            blockCursor = 0;
            var log = frame.log(sbIndex);
            ec.record(log, true);
            commitNode(BL_64X64, IntraEdge.SB64);
            ec.record(null);
            if (wavefront != null) {
                EncoderBridge.backupIpredEdge(t, t.bx, Math.min(t.bx + f.sbStep, ts.colEnd));
            }
            if ((t.bx & 16) != 0) {
                t.aIdx++;
                t.lfMaskIdx++;
            }
            if (wavefront != null) {
                wavefront.publish(unit, sbInRow + 1);
            }
        }
        EncoderBridge.backupIpredEdge(t);
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
     * Selects the analysis and inverse transform kernels again, so the vector kernels take over
     * once they are compiled; every implementation gives the same results.
     */
    private void refreshKernels() {
        var f = frame.f;
        f.itx = ItxDsp.select();
        search.txb.itx = f.itx;
        search.txb.dsp = EncDsp.select();
    }

    /**
     * Appends a partition to the plan.
     *
     * @param bp the partition
     */
    private void addPartition(int bp) {
        if (nPartitions == partitions.length) {
            partitions = Arrays.copyOf(partitions, nPartitions * 2);
        }
        partitions[nPartitions++] = bp;
    }

    /**
     * Returns a decision object for the next planned block, reusing the storage of discarded
     * ones.
     *
     * @return the decision, appended to the plan
     */
    private StillBlock addBlock() {
        if (nBlocks == blocks.length) {
            blocks = Arrays.copyOf(blocks, nBlocks * 2);
        }
        var d = blocks[nBlocks];
        if (d == null) {
            d = new StillBlock();
            blocks[nBlocks] = d;
        }
        nBlocks++;
        return d;
    }

    /**
     * Returns the cost of a partition symbol with the tile's current CDFs.
     *
     * @param bl the block level
     * @param pc the offset of the partition CDF
     * @param bp the partition
     * @param haveH whether the right half lies inside the frame
     * @param haveV whether the bottom half lies inside the frame
     * @return the cost
     */
    private long partitionRate(int bl, int pc, int bp, boolean haveH, boolean haveV) {
        if (haveH && haveV) {
            return search.symbolCost(pc, bp, Tables.PARTITION_TYPE_COUNT[bl]);
        }
        var bit = bp == PARTITION_SPLIT ? 1 : 0;
        return haveH ? search.fixedCost(bit, TileEncoder.topPartitionProb(ts.cdf, pc, bl))
                : search.fixedCost(bit, TileEncoder.leftPartitionProb(ts.cdf, pc, bl));
    }

    /**
     * Searches the partition of one node of the partition tree at the tile's current position and
     * leaves the cheapest coding in the picture, the contexts and the plan.
     *
     * @param bl the block level
     * @param node the intra edge node of the position
     * @param budget the cost above which the search may give up
     * @param quad the node's index in the quad tree of the superblock: 0 for the superblock,
     * {@code 4 * p + i + 1} for child {@code i} of node {@code p}
     * @return the cost of the node, or {@link Long#MAX_VALUE} when no partition stays within the
     * budget; the picture, contexts and plan are then left in an unspecified state
     */
    private long searchNode(int bl, IntraEdge.Node node, long budget, int quad) {
        var f = frame.f;
        var hsz = 16 >> bl;
        var haveH = f.bw > t.bx + hsz;
        var haveV = f.bh > t.by + hsz;
        if (!haveH && !haveV) {
            addPartition(PARTITION_SPLIT);
            return searchNode(bl + 1, node.children[0], budget, 4 * quad + 1);
        }
        var bx8 = (t.bx & 31) >> 1;
        var by8 = (t.by & 31) >> 1;
        var ctx = ((t.a.partition[bx8] >> (4 - bl)) & 1) + (((t.l.partition[by8] >> (4 - bl)) & 1) << 1);
        var pc = Cdf.PARTITION + (bl * 4 + ctx) * 16;
        var settings = frame.settings;
        var i422 = frame.layout == SequenceHeader.LAYOUT_I422;
        var small = bl == BL_8X8 && (frame.minBlock > 4 || pruneSub8x8());
        var nodeSize = hsz * 8;
        var candidates = new int[4];
        var n = 0;
        var decision = IntraPartitionCnn.UNDECIDED;
        if (cnnReady && !small && t.bx + 2 * hsz <= frame.f.bw && t.by + 2 * hsz <= frame.f.bh) {
            decision = cnn.decide(bl, quad, cnnResolution);
        }
        if (nodeSize > settings.maxBlock) {
            candidates[n++] = PARTITION_SPLIT;
        } else if (decision == IntraPartitionCnn.SPLIT_ONLY) {
            if (cnnLevel == 1) {
                candidates[n++] = PARTITION_NONE;
            }
            candidates[n++] = PARTITION_SPLIT;
        } else if (haveH && haveV) {
            candidates[n++] = PARTITION_NONE;
            if (!small && decision != IntraPartitionCnn.NO_SPLIT) {
                candidates[n++] = PARTITION_SPLIT;
            }
            var rectAllowed = settings.rectPartitions && nodeSize <= settings.rectMaxBlock && !small;
            if (rectAllowed && settings.rectQindexPrune && !frame.hdr.allowScreenContentTools) {
                rectAllowed = nodeSize >= 32 >> (settings.qindex * 3 / 256);
            }
            if (rectAllowed) {
                candidates[n++] = PARTITION_H;
                if (!i422) {
                    candidates[n++] = PARTITION_V;
                }
            }
        } else if (haveH) {
            candidates[n++] = PARTITION_H;
            candidates[n++] = PARTITION_SPLIT;
        } else {
            if (!i422) {
                candidates[n++] = PARTITION_V;
            }
            candidates[n++] = PARTITION_SPLIT;
        }
        var level = bl;
        var s0 = start[level];
        var sb = best[level];
        s0.save(t, frame, hsz * 2);
        var markP = nPartitions;
        var markB = nBlocks;
        var bestCost = Long.MAX_VALUE;
        var bestIndex = -1;
        var noneCost = Long.MAX_VALUE;
        var pruneH = false;
        var pruneV = false;
        for (var i = 0; i < n; i++) {
            var bp = candidates[i];
            if ((bp == PARTITION_H && pruneH) || (bp == PARTITION_V && pruneV)) {
                continue;
            }
            if (i > 0) {
                s0.restore(t, frame, hsz * 2);
                nPartitions = markP;
                nBlocks = markB;
            }
            addPartition(bp);
            var cost = search.rd(partitionRate(bl, pc, bp, haveH, haveV), 0);
            var limit = Math.min(bestCost, budget);
            cost = cost >= limit ? Long.MAX_VALUE : add(cost, evalPartition(bl, bp, node, hsz, haveH, haveV,
                    limit - cost, quad));
            if (bp != PARTITION_SPLIT || bl == BL_8X8) {
                Arrays.fill(t.a.partition, bx8, bx8 + hsz, (byte) Tables.AL_PART_CTX[bl * 10 + bp]);
                Arrays.fill(t.l.partition, by8, by8 + hsz, (byte) Tables.AL_PART_CTX[50 + bl * 10 + bp]);
            }
            if (bp == PARTITION_NONE) {
                noneCost = cost;
                if (cost != Long.MAX_VALUE && settings.rectModePrune && haveH && haveV) {
                    var mode = blocks[markB].yMode;
                    pruneH = mode == IpredDsp.VERT_LEFT_PRED || mode == IpredDsp.VERT_PRED
                            || mode == IpredDsp.VERT_RIGHT_PRED;
                    pruneV = mode == IpredDsp.HOR_DOWN_PRED || mode == IpredDsp.HOR_PRED
                            || mode == IpredDsp.HOR_UP_PRED;
                }
            } else if (bp == PARTITION_SPLIT && settings.rectPruning && haveH && haveV
                    && noneCost != Long.MAX_VALUE && cost < noneCost) {
                n = i + 1;
            }
            if (cost < bestCost) {
                bestCost = cost;
                bestIndex = i;
                if (i < n - 1) {
                    sb.save(t, frame, hsz * 2);
                    sb.savePlan(partitions, markP, nPartitions, blocks, markB, nBlocks);
                }
                if (bp == PARTITION_NONE && breakout(bl)) {
                    n = i + 1;
                    break;
                }
            }
        }
        if (bestIndex < 0) {
            return Long.MAX_VALUE;
        }
        if (bestIndex != n - 1) {
            sb.restore(t, frame, hsz * 2);
            nPartitions = markP;
            nBlocks = markB;
            nPartitions = sb.restorePartitions(this, markP);
            nBlocks = sb.restoreBlocks(this, markB);
        }
        return bestCost;
    }

    /**
     * Returns whether the 8x8 node at the current position skips its split into 4x4 blocks
     * because a neighbor is larger: the block above is at least 16 wide or the block to the
     * left at least 16 tall, both inside the tile ({@code prune_sub_8x8_partition_level} 1).
     *
     * @return {@code true} to search the node unsplit only
     */
    private boolean pruneSub8x8() {
        if (!frame.settings.sub8x8Prune || frame.hdr.allowScreenContentTools || t.bx <= ts.colStart
                || t.by <= ts.rowStart) {
            return false;
        }
        var above = (t.a.partition[(t.bx & 31) >> 1] >> (4 - BL_16X16)) & 1;
        var left = (t.l.partition[(t.by & 31) >> 1] >> (4 - BL_16X16)) & 1;
        return above == 0 || left == 0;
    }

    /**
     * Runs the partition network on the current superblock when the settings enable it and the
     * superblock lies entirely inside the frame; otherwise no node of the superblock is pruned by
     * it. Screen content uses a milder level, and none below speed 5.
     */
    private void prepareCnn() {
        var settings = frame.settings;
        var f = frame.f;
        cnnLevel = frame.hdr.allowScreenContentTools ? (settings.speed >= 5 ? Math.min(1, settings.cnnPartition) : 0)
                : settings.cnnPartition;
        cnnReady = cnnLevel > 0 && t.bx + 16 <= f.bw && t.by + 16 <= f.bh;
        if (cnnReady) {
            var shorter = Math.min(frame.width, frame.height);
            cnnResolution = shorter >= 720 ? 0 : shorter >= 480 ? 1 : 2;
            cnn.prepare(frame.source.data[0], frame.source.stride[0], frame.width, frame.height, 4 * t.bx, 4 * t.by,
                    frame.bitDepth, frame.quant.dequant(0, false) >> (frame.bitDepth - 8));
        }
    }

    /**
     * Grows the partition plan to hold a size.
     *
     * @param size the needed capacity
     * @return the plan's storage
     */
    int[] partitionStorage(int size) {
        if (partitions.length < size) {
            partitions = Arrays.copyOf(partitions, Math.max(size, partitions.length * 2));
        }
        return partitions;
    }

    /**
     * Grows the block plan to hold a size.
     *
     * @param size the needed capacity
     * @return the plan's storage
     */
    StillBlock[] blockStorage(int size) {
        if (blocks.length < size) {
            blocks = Arrays.copyOf(blocks, Math.max(size, blocks.length * 2));
        }
        return blocks;
    }

    /**
     * Adds two costs, saturating at {@link Long#MAX_VALUE}.
     *
     * @param a the first cost
     * @param b the second cost
     * @return the sum
     */
    private static long add(long a, long b) {
        var s = a + b;
        return s < 0 || a == Long.MAX_VALUE || b == Long.MAX_VALUE ? Long.MAX_VALUE : s;
    }

    /**
     * Codes the blocks of one partition of a node and returns their cost.
     *
     * @param bl the block level
     * @param bp the partition
     * @param node the intra edge node
     * @param hsz half the node size in 4-sample units
     * @param haveH whether the right half lies inside the frame
     * @param haveV whether the bottom half lies inside the frame
     * @param budget the cost above which the evaluation may give up
     * @param quad the node's index in the quad tree of the superblock
     * @return the cost, or {@link Long#MAX_VALUE} when it exceeds the budget
     */
    private long evalPartition(int bl, int bp, IntraEdge.Node node, int hsz, boolean haveH, boolean haveV,
                               long budget, int quad) {
        var b0 = Tables.BLOCK_SIZES[(bl * 10 + bp) * 2];
        long cost;
        switch (bp) {
            case PARTITION_NONE -> cost = searchBlock(bl, b0, PARTITION_NONE, node.o, budget);
            case PARTITION_H -> {
                cost = searchBlock(bl, b0, PARTITION_H, node.h0, budget);
                if (haveV && cost != Long.MAX_VALUE) {
                    t.by += hsz;
                    cost = add(cost, searchBlock(bl, b0, PARTITION_H, node.h1, budget - cost));
                    t.by -= hsz;
                }
            }
            case PARTITION_V -> {
                cost = searchBlock(bl, b0, PARTITION_V, node.v0, budget);
                if (haveH && cost != Long.MAX_VALUE) {
                    t.bx += hsz;
                    cost = add(cost, searchBlock(bl, b0, PARTITION_V, node.v1, budget - cost));
                    t.bx -= hsz;
                }
            }
            default -> {
                if (bl == BL_8X8) {
                    cost = searchBlock(bl, BS_4X4, PARTITION_SPLIT, IntraEdge.ALL_TR_AND_BL, budget);
                    if (cost != Long.MAX_VALUE) {
                        t.bx++;
                        cost = add(cost, searchBlock(bl, BS_4X4, PARTITION_SPLIT, node.split[0], budget - cost));
                        t.bx--;
                    }
                    if (cost != Long.MAX_VALUE) {
                        t.by++;
                        cost = add(cost, searchBlock(bl, BS_4X4, PARTITION_SPLIT, node.split[1], budget - cost));
                        t.by--;
                    }
                    if (cost != Long.MAX_VALUE) {
                        t.bx++;
                        t.by++;
                        cost = add(cost, searchBlock(bl, BS_4X4, PARTITION_SPLIT, node.split[2], budget - cost));
                        t.bx--;
                        t.by--;
                    }
                } else {
                    cost = searchNode(bl + 1, node.children[0], budget, 4 * quad + 1);
                    if (haveH && cost != Long.MAX_VALUE) {
                        t.bx += hsz;
                        cost = add(cost, searchNode(bl + 1, node.children[1], budget - cost, 4 * quad + 2));
                        t.bx -= hsz;
                    }
                    if (haveV && cost != Long.MAX_VALUE) {
                        t.by += hsz;
                        cost = add(cost, searchNode(bl + 1, node.children[2], budget - cost, 4 * quad + 3));
                        if (haveH && cost != Long.MAX_VALUE) {
                            t.bx += hsz;
                            cost = add(cost, searchNode(bl + 1, node.children[3], budget - cost, 4 * quad + 4));
                            t.bx -= hsz;
                        }
                        t.by -= hsz;
                    }
                }
            }
        }
        return cost;
    }

    /**
     * Returns whether the unsplit block just searched is flat enough that splitting it cannot pay:
     * it codes no residual and its prediction error per sample is small next to the quantizer
     * step ({@code partition_search_breakout_dist_thr}).
     *
     * @param bl the block level
     * @return {@code true} to stop the partition search at this node
     */
    private boolean breakout(int bl) {
        var d = blocks[nBlocks - 1];
        if (!d.skip || bl >= BL_8X8) {
            return false;
        }
        var pixels = (long) Tables.BLOCK_DIMENSIONS[d.bs * 4] * Tables.BLOCK_DIMENSIONS[d.bs * 4 + 1] * 16;
        var q = (long) frame.quant.dequant(0, true) >> (frame.bitDepth - 8);
        return search.lastDist() * 16 <= pixels * q * q * frame.settings.breakoutScale / 16;
    }

    /**
     * Searches one block and appends its decisions to the plan.
     *
     * @param bl the block level
     * @param bs the block size
     * @param bp the partition the block came from
     * @param edgeFlags the block's intra edge flags
     * @param budget the cost above which the search may give up
     * @return the block's cost, or {@link Long#MAX_VALUE} when it exceeds the budget
     */
    private long searchBlock(int bl, int bs, int bp, int edgeFlags, long budget) {
        var d = addBlock();
        return search.search(bl, bs, bp, edgeFlags, d, budget);
    }

    /**
     * Codes one node of the planned partition tree as the decoder reads it.
     *
     * @param bl the block level
     * @param node the intra edge node
     */
    private void commitNode(int bl, IntraEdge.Node node) {
        var f = frame.f;
        var hsz = 16 >> bl;
        var haveH = f.bw > t.bx + hsz;
        var haveV = f.bh > t.by + hsz;
        var bp = partitions[partitionCursor++];
        if (!haveH && !haveV) {
            commitNode(bl + 1, node.children[0]);
            return;
        }
        var bx8 = (t.bx & 31) >> 1;
        var by8 = (t.by & 31) >> 1;
        var ctx = ((t.a.partition[bx8] >> (4 - bl)) & 1) + (((t.l.partition[by8] >> (4 - bl)) & 1) << 1);
        var pc = Cdf.PARTITION + (bl * 4 + ctx) * 16;
        var b0 = Tables.BLOCK_SIZES[(bl * 10 + bp) * 2];
        if (haveH && haveV) {
            ec.encodeSymbol(ts.cdf, pc, bp, Tables.PARTITION_TYPE_COUNT[bl]);
            switch (bp) {
                case PARTITION_NONE -> commitBlock(bl, b0, PARTITION_NONE, node.o);
                case PARTITION_H -> {
                    commitBlock(bl, b0, PARTITION_H, node.h0);
                    t.by += hsz;
                    commitBlock(bl, b0, PARTITION_H, node.h1);
                    t.by -= hsz;
                }
                case PARTITION_V -> {
                    commitBlock(bl, b0, PARTITION_V, node.v0);
                    t.bx += hsz;
                    commitBlock(bl, b0, PARTITION_V, node.v1);
                    t.bx -= hsz;
                }
                default -> {
                    if (bl == BL_8X8) {
                        commitBlock(bl, BS_4X4, PARTITION_SPLIT, IntraEdge.ALL_TR_AND_BL);
                        t.bx++;
                        commitBlock(bl, BS_4X4, PARTITION_SPLIT, node.split[0]);
                        t.bx--;
                        t.by++;
                        commitBlock(bl, BS_4X4, PARTITION_SPLIT, node.split[1]);
                        t.bx++;
                        commitBlock(bl, BS_4X4, PARTITION_SPLIT, node.split[2]);
                        t.bx--;
                        t.by--;
                    } else {
                        commitNode(bl + 1, node.children[0]);
                        t.bx += hsz;
                        commitNode(bl + 1, node.children[1]);
                        t.bx -= hsz;
                        t.by += hsz;
                        commitNode(bl + 1, node.children[2]);
                        t.bx += hsz;
                        commitNode(bl + 1, node.children[3]);
                        t.bx -= hsz;
                        t.by -= hsz;
                    }
                }
            }
        } else if (haveH) {
            ec.log().edge(pc, bl, true, bp == PARTITION_SPLIT ? 1 : 0);
            if (bp == PARTITION_SPLIT) {
                commitNode(bl + 1, node.children[0]);
                t.bx += hsz;
                commitNode(bl + 1, node.children[1]);
                t.bx -= hsz;
            } else {
                commitBlock(bl, b0, PARTITION_H, node.h0);
            }
        } else {
            ec.log().edge(pc, bl, false, bp == PARTITION_SPLIT ? 1 : 0);
            if (bp == PARTITION_SPLIT) {
                commitNode(bl + 1, node.children[0]);
                t.by += hsz;
                commitNode(bl + 1, node.children[2]);
                t.by -= hsz;
            } else {
                commitBlock(bl, b0, PARTITION_V, node.v0);
            }
        }
        if (bp != PARTITION_SPLIT || bl == BL_8X8) {
            Arrays.fill(t.a.partition, bx8, bx8 + hsz, (byte) Tables.AL_PART_CTX[bl * 10 + bp]);
            Arrays.fill(t.l.partition, by8, by8 + hsz, (byte) Tables.AL_PART_CTX[50 + bl * 10 + bp]);
        }
    }

    /**
     * Codes one planned block as the decoder reads it: its skip flag, CDEF index, modes,
     * transform size and, through the decoder's reconstruction, its coefficients; then updates the
     * loop filter masks and contexts as the decoder does.
     *
     * @param bl the block level
     * @param bs the block size
     * @param bp the partition the block came from
     * @param edgeFlags the block's intra edge flags
     */
    private void commitBlock(int bl, int bs, int bp, int edgeFlags) {
        var d = blocks[blockCursor++];
        var f = frame.f;
        var hdr = f.frameHdr;
        var cdf = ts.cdf;
        var b = t.b;
        var bx4 = t.bx & 31;
        var by4 = t.by & 31;
        var bw4 = BLOCK_DIMENSIONS[bs * 4];
        var bh4 = BLOCK_DIMENSIONS[bs * 4 + 1];
        var bwl = BLOCK_DIMENSIONS[bs * 4 + 2];
        var bhl = BLOCK_DIMENSIONS[bs * 4 + 3];
        var layout = frame.layout;
        var ssHor = frame.ssHor;
        var ssVer = frame.ssVer;
        var cbx4 = bx4 >> ssHor;
        var cby4 = by4 >> ssVer;
        var hasChroma = search.hasChroma(bs);
        b.bl = bl;
        b.bp = bp;
        b.bs = bs;
        b.segId = 0;
        b.skipMode = false;
        b.intra = true;
        b.skip = d.skip;
        ec.encodeBool(cdf, Cdf.SKIP + (t.a.skip[bx4] + t.l.skip[by4]) * 2, d.skip ? 1 : 0);
        if (!d.skip) {
            var cdefIdx = t.lfMask.cdefIdx;
            if (cdefIdx[t.curSbCdefIdx] == -1) {
                ec.encodeDeferred();
                cdefIdx[t.curSbCdefIdx] = 0;
            }
        }
        var yCoded = d.yMode == IpredDsp.FILTER_PRED ? IpredDsp.DC_PRED : d.yMode;
        var angles = bwl + bhl >= 2;
        b.yMode = yCoded;
        b.yAngle = yCoded >= IpredDsp.VERT_PRED && yCoded <= IpredDsp.VERT_LEFT_PRED && angles ? d.yAngle : 0;
        b.uvMode = IpredDsp.DC_PRED;
        b.uvAngle = 0;
        b.cflAlpha[0] = 0;
        b.cflAlpha[1] = 0;
        var cfl = false;
        if (hasChroma) {
            cfl = Tables.cflAllowed(bs, frame.quant.lossless(), (bw4 + ssHor) >> ssHor, (bh4 + ssVer) >> ssVer);
            b.uvMode = d.uvMode;
            if (d.uvMode == IpredDsp.CFL_PRED) {
                b.cflAlpha[0] = d.cflAlpha[0];
                b.cflAlpha[1] = d.cflAlpha[1];
            } else if (angles && d.uvMode >= IpredDsp.VERT_PRED && d.uvMode <= IpredDsp.VERT_LEFT_PRED) {
                b.uvAngle = d.uvAngle;
            }
        }
        ModeWriter.writeIntraModes(ec, cdf, ModeWriter.kfYModeCdf(t.a, t.l, bx4, by4), bs, b, hasChroma, cfl);
        b.palSz[0] = 0;
        b.palSz[1] = 0;
        var palette = search.paletteWriter;
        if (hdr.allowScreenContentTools && Math.max(bw4, bh4) <= 16 && bw4 + bh4 >= 4) {
            var szCtx = bwl + bhl - 2;
            if (yCoded == IpredDsp.DC_PRED) {
                var palCtx = (t.a.palSz[bx4] > 0 ? 1 : 0) + (t.l.palSz[by4] > 0 ? 1 : 0);
                ec.encodeBool(cdf, Cdf.PAL_Y + (szCtx * 3 + palCtx) * 2, d.palSz[0] > 0 ? 1 : 0);
                if (d.palSz[0] > 0) {
                    palette.writeColors(ec, cdf, t, 0, szCtx, bx4, by4, d.pal, 0, d.palSz[0], frame.bitDepth);
                    b.palSz[0] = d.palSz[0];
                }
            }
            if (hasChroma && b.uvMode == IpredDsp.DC_PRED) {
                ec.encodeBool(cdf, Cdf.PAL_UV + (b.palSz[0] > 0 ? 2 : 0), d.palSz[1] > 0 ? 1 : 0);
                if (d.palSz[1] > 0) {
                    palette.writeColors(ec, cdf, t, 1, szCtx, bx4, by4, d.pal, 8, d.palSz[1], frame.bitDepth);
                    PaletteWriter.writeV(ec, d.pal, 16, d.palSz[1], frame.bitDepth);
                    b.palSz[1] = d.palSz[1];
                }
            }
        }
        if (yCoded == IpredDsp.DC_PRED && b.palSz[0] == 0 && Math.max(bwl, bhl) <= 3 && frame.seq.filterIntra) {
            var filter = d.yMode == IpredDsp.FILTER_PRED;
            ec.encodeBool(cdf, Cdf.USE_FILTER_INTRA + bs * 2, filter ? 1 : 0);
            if (filter) {
                b.yMode = IpredDsp.FILTER_PRED;
                b.yAngle = d.yAngle;
                ec.encodeSymbol(cdf, Cdf.FILTER_INTRA, d.yAngle, 4);
            }
        }
        var w4 = Math.min(bw4, f.bw - t.bx);
        var h4 = Math.min(bh4, f.bh - t.by);
        if (b.palSz[0] != 0) {
            palette.writeIndices(ec, cdf, d.palMapY, bw4 * 4, b.palSz[0], 0, w4 * 4, h4 * 4);
            System.arraycopy(d.pal, 0, t.pal, 0, 8);
            PaletteWriter.pack(t.palIdxY, d.palMapY, bw4 * 4, bw4 * 4, bh4 * 4, w4 * 4, h4 * 4);
        }
        if (hasChroma && b.palSz[1] != 0) {
            var cbw4 = (bw4 + ssHor) >> ssHor;
            var cbh4 = (bh4 + ssVer) >> ssVer;
            var cw4 = (w4 + ssHor) >> ssHor;
            var ch4 = (h4 + ssVer) >> ssVer;
            palette.writeIndices(ec, cdf, d.palMapUv, cbw4 * 4, b.palSz[1], 1, cw4 * 4, ch4 * 4);
            System.arraycopy(d.pal, 8, t.pal, 8, 16);
            PaletteWriter.pack(t.palIdxUv, d.palMapUv, cbw4 * 4, cbw4 * 4, cbh4 * 4, cw4 * 4, ch4 * 4);
        }
        if (frame.quant.lossless()) {
            b.tx = 0;
            b.uvtx = 0;
        } else {
            var maxTx = Tables.MAX_TXFM_SIZE_FOR_BS[bs * 4];
            b.tx = d.tx;
            b.uvtx = Tables.MAX_TXFM_SIZE_FOR_BS[bs * 4 + layout];
            if (hdr.txfmMode == FrameHeader.TX_SWITCHABLE && Tables.TX_MAX[maxTx] > 0) {
                var tctx = (t.l.txIntra[by4] >= Tables.TX_LH[maxTx] ? 1 : 0)
                        + (t.a.txIntra[bx4] >= Tables.TX_LW[maxTx] ? 1 : 0);
                var depth = 0;
                for (var s = maxTx; s != d.tx; s = Tables.TX_SUB[s]) {
                    depth++;
                }
                ec.encodeSymbol(cdf, Cdf.TXSZ + ((Tables.TX_MAX[maxTx] - 1) * 3 + tctx) * 4, depth,
                        Math.min(Tables.TX_MAX[maxTx], 2));
            }
        }
        committing = d;
        typeCursor = 0;
        t.coefSource = this;
        try {
            EncoderBridge.reconIntra(t, bs, edgeFlags, b);
        } finally {
            t.coefSource = null;
        }
        LoopFilterMasks.createLfMaskIntra(t.lfMask, f.lfLevel, f.b4Stride, ts.lflvl, 0, t.bx, t.by, f.w4, f.h4, bs,
                b.tx, b.uvtx, layout, t.a.txLpfY, bx4, t.l.txLpfY, by4, hasChroma ? t.a.txLpfUv : null, cbx4,
                hasChroma ? t.l.txLpfUv : null, cby4);
        search.updateContexts(d, hasChroma, bx4, by4, bw4, bh4);
        if (!d.skip) {
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

    @Override
    public int code(TaskContext tc, byte[] a, int aOff, byte[] l, int lOff, int tx, int bs, Av1Block b,
                    boolean intra, int plane, int[] cf) {
        var d = committing;
        var f = frame.f;
        var lossless = frame.quant.lossless();
        int txtp;
        if (plane == 0) {
            txtp = typeCursor < d.nTypes ? d.txTypes[typeCursor] : 0;
            typeCursor++;
            if (lossless || Tables.TX_MAX[tx] + 1 >= 4) {
                txtp = 0;
            }
        } else {
            txtp = lossless ? 0 : search.chromaType(tx, b.uvMode);
        }
        int x;
        int y;
        if (plane == 0) {
            x = 4 * tc.bx;
            y = 4 * tc.by;
        } else {
            x = 4 * (tc.bx >> frame.ssHor);
            y = 4 * (tc.by >> frame.ssVer);
        }
        var cur = f.cur;
        var dstStride = cur.stride[plane != 0 ? 1 : 0];
        var srcStride = frame.source.stride[plane != 0 ? 1 : 0];
        var txb = search.txb;
        txb.quantize(plane, tx, txtp, frame.source.data[plane], y * srcStride + x, srcStride, cur.data[plane],
                y * dstStride + x, dstStride);
        var codedType = lossless ? ItxDsp.WHT_WHT : txtp;
        var ctx = coefWriter.write(ec, ts.cdf, a, aOff, l, lOff, tx, bs, plane, true,
                StillBlockSearch.modeNoFilter(b.yMode, b.yAngle), f.frameHdr.reducedTxtpSet, f.frameHdr.segQidx[0],
                codedType, txb.levels, 0, txb.eob, frame.ssHor, frame.ssVer);
        tc.cfCtx = ctx;
        if (txb.eob == 0) {
            tc.txtp = lossless ? ItxDsp.WHT_WHT : 0;
            return -1;
        }
        tc.txtp = codedType;
        txb.dequantizeInto(plane, tx, cf);
        return txb.eob - 1;
    }

    /**
     * Holds the state a partition candidate changes: the samples of a square region, the block
     * contexts of the tile row and column and the palette contexts, plus a copy of a plan's tail.
     */
    private static final class Snapshot {
        /**
         * The saved samples of each plane.
         */
        private final short[][] samples = {new short[64 * 64], new short[64 * 64], new short[64 * 64]};

        /**
         * The saved above context.
         */
        private final BlockContext above = new BlockContext();

        /**
         * The saved left context.
         */
        private final BlockContext left = new BlockContext();

        /**
         * The saved chroma palette sizes of the neighbors.
         */
        private final byte[] palSzUv = new byte[64];

        /**
         * The saved neighbor palettes.
         */
        private final short[] alPal = new short[2 * 32 * 3 * 8];

        /**
         * The saved partitions of a plan.
         */
        private int[] partitions = new int[64];

        /**
         * The number of saved partitions.
         */
        private int nPartitions;

        /**
         * The saved blocks of a plan, deep copies.
         */
        private StillBlock[] blocks = new StillBlock[64];

        /**
         * The number of saved blocks.
         */
        private int nBlocks;

        /**
         * Creates a snapshot.
         */
        Snapshot() {
        }

        /**
         * Saves the samples of a square region at the task context's position and every context.
         *
         * @param t the task context
         * @param frame the frame encoder
         * @param size4 the region size in 4-sample units, 0 to save only the contexts
         */
        void save(TaskContext t, StillFrameEncoder frame, int size4) {
            copyAbove(t.a, above, t.bx & 16, frame.ssHor);
            copyContext(t.l, left);
            System.arraycopy(t.palSzUv, 0, palSzUv, 0, 64);
            if (frame.hdr.allowScreenContentTools) {
                System.arraycopy(t.alPal, 0, alPal, 0, alPal.length);
            }
            if (size4 > 0) {
                copyRegion(t, frame, size4, true);
            }
        }

        /**
         * Restores the samples of the region and every context.
         *
         * @param t the task context
         * @param frame the frame encoder
         * @param size4 the region size in 4-sample units
         */
        void restore(TaskContext t, StillFrameEncoder frame, int size4) {
            restoreContexts(t, frame);
            copyRegion(t, frame, size4, false);
        }

        /**
         * Restores every context but not the samples.
         *
         * @param t the task context
         * @param frame the frame encoder
         */
        void restoreContexts(TaskContext t, StillFrameEncoder frame) {
            copyAbove(above, t.a, t.bx & 16, frame.ssHor);
            copyContext(left, t.l);
            System.arraycopy(palSzUv, 0, t.palSzUv, 0, 64);
            if (frame.hdr.allowScreenContentTools) {
                System.arraycopy(alPal, 0, t.alPal, 0, alPal.length);
            }
        }

        /**
         * Copies the samples of a square region between the picture and this snapshot.
         *
         * @param t the task context
         * @param frame the frame encoder
         * @param size4 the region size in 4-sample units
         * @param save whether the picture is copied into the snapshot
         */
        private void copyRegion(TaskContext t, StillFrameEncoder frame, int size4, boolean save) {
            var cur = frame.f.cur;
            var planes = frame.layout == SequenceHeader.LAYOUT_I400 ? 1 : 3;
            for (var p = 0; p < planes; p++) {
                var ssx = p == 0 ? 0 : frame.ssHor;
                var ssy = p == 0 ? 0 : frame.ssVer;
                var stride = cur.stride[p == 0 ? 0 : 1];
                var x0 = (4 * t.bx) >> ssx;
                var y0 = (4 * t.by) >> ssy;
                var w = Math.max(4, (4 * size4) >> ssx);
                var h = Math.max(4, (4 * size4) >> ssy);
                var plane = cur.data[p];
                var buf = samples[p];
                for (var y = 0; y < h; y++) {
                    var off = (y0 + y) * stride + x0;
                    if (save) {
                        System.arraycopy(plane, off, buf, y * w, w);
                    } else {
                        System.arraycopy(buf, y * w, plane, off, w);
                    }
                }
            }
        }

        /**
         * Saves the tail of a plan.
         *
         * @param parts the partitions
         * @param pFrom the first partition to save
         * @param pTo the end of the partitions
         * @param plan the blocks
         * @param bFrom the first block to save
         * @param bTo the end of the blocks
         */
        void savePlan(int[] parts, int pFrom, int pTo, StillBlock[] plan, int bFrom, int bTo) {
            nPartitions = pTo - pFrom;
            if (partitions.length < nPartitions) {
                partitions = new int[nPartitions * 2];
            }
            System.arraycopy(parts, pFrom, partitions, 0, nPartitions);
            nBlocks = bTo - bFrom;
            if (blocks.length < nBlocks) {
                blocks = Arrays.copyOf(blocks, nBlocks * 2);
            }
            for (var i = 0; i < nBlocks; i++) {
                var src = plan[bFrom + i];
                var dst = blocks[i];
                if (dst == null) {
                    dst = new StillBlock();
                    blocks[i] = dst;
                }
                copyBlock(src, dst);
            }
        }

        /**
         * Appends the saved partitions to the encoder's plan.
         *
         * @param encoder the tile encoder whose plan receives them
         * @param at the position to append at
         * @return the new plan size
         */
        int restorePartitions(StillTileEncoder encoder, int at) {
            var storage = encoder.partitionStorage(at + nPartitions);
            System.arraycopy(partitions, 0, storage, at, nPartitions);
            return at + nPartitions;
        }

        /**
         * Appends the saved blocks to the encoder's plan.
         *
         * @param encoder the tile encoder whose plan receives them
         * @param at the position to append at
         * @return the new plan size
         */
        int restoreBlocks(StillTileEncoder encoder, int at) {
            var storage = encoder.blockStorage(at + nBlocks);
            for (var i = 0; i < nBlocks; i++) {
                var dst = storage[at + i];
                if (dst == null) {
                    dst = new StillBlock();
                    storage[at + i] = dst;
                }
                copyBlock(blocks[i], dst);
            }
            return at + nBlocks;
        }

        /**
         * Copies one block decision into another.
         *
         * @param src the source
         * @param dst the destination
         */
        private static void copyBlock(StillBlock src, StillBlock dst) {
            dst.bs = src.bs;
            dst.bp = src.bp;
            dst.bl = src.bl;
            dst.edgeFlags = src.edgeFlags;
            dst.yMode = src.yMode;
            dst.yAngle = src.yAngle;
            dst.uvMode = src.uvMode;
            dst.uvAngle = src.uvAngle;
            dst.cflAlpha[0] = src.cflAlpha[0];
            dst.cflAlpha[1] = src.cflAlpha[1];
            dst.tx = src.tx;
            dst.skip = src.skip;
            if (dst.txTypes.length < src.nTypes) {
                dst.txTypes = new int[src.txTypes.length];
            }
            System.arraycopy(src.txTypes, 0, dst.txTypes, 0, src.nTypes);
            dst.nTypes = src.nTypes;
            dst.palSz[0] = src.palSz[0];
            dst.palSz[1] = src.palSz[1];
            System.arraycopy(src.pal, 0, dst.pal, 0, 24);
            dst.palMapY = src.palMapY;
            dst.palMapUv = src.palMapUv;
        }

        /**
         * Copies the entries of an above context that one 64x64 superblock owns, leaving those of
         * the other half of the 128-sample context alone, which another tile may be coding.
         *
         * @param src the source
         * @param dst the destination
         * @param base the superblock's first entry, 0 or 16, in 4-sample columns
         * @param ssHor the horizontal chroma subsampling
         */
        private static void copyAbove(BlockContext src, BlockContext dst, int base, int ssHor) {
            System.arraycopy(src.mode, base, dst.mode, base, 16);
            System.arraycopy(src.lcoef, base, dst.lcoef, base, 16);
            System.arraycopy(src.segPred, base, dst.segPred, base, 16);
            System.arraycopy(src.skip, base, dst.skip, base, 16);
            System.arraycopy(src.skipMode, base, dst.skipMode, base, 16);
            System.arraycopy(src.intra, base, dst.intra, base, 16);
            System.arraycopy(src.txIntra, base, dst.txIntra, base, 16);
            System.arraycopy(src.tx, base, dst.tx, base, 16);
            System.arraycopy(src.txLpfY, base, dst.txLpfY, base, 16);
            System.arraycopy(src.palSz, base, dst.palSz, base, 16);
            System.arraycopy(src.partition, base >> 1, dst.partition, base >> 1, 8);
            var chroma = base >> ssHor;
            var chromaLength = 16 >> ssHor;
            System.arraycopy(src.uvmode, chroma, dst.uvmode, chroma, chromaLength);
            System.arraycopy(src.txLpfUv, chroma, dst.txLpfUv, chroma, chromaLength);
            System.arraycopy(src.ccoef, chroma, dst.ccoef, chroma, chromaLength);
            System.arraycopy(src.ccoef, 32 + chroma, dst.ccoef, 32 + chroma, chromaLength);
        }

        /**
         * Copies every array of a block context.
         *
         * @param src the source
         * @param dst the destination
         */
        private static void copyContext(BlockContext src, BlockContext dst) {
            System.arraycopy(src.mode, 0, dst.mode, 0, 32);
            System.arraycopy(src.lcoef, 0, dst.lcoef, 0, 32);
            System.arraycopy(src.ccoef, 0, dst.ccoef, 0, 64);
            System.arraycopy(src.segPred, 0, dst.segPred, 0, 32);
            System.arraycopy(src.skip, 0, dst.skip, 0, 32);
            System.arraycopy(src.skipMode, 0, dst.skipMode, 0, 32);
            System.arraycopy(src.intra, 0, dst.intra, 0, 32);
            System.arraycopy(src.txIntra, 0, dst.txIntra, 0, 32);
            System.arraycopy(src.tx, 0, dst.tx, 0, 32);
            System.arraycopy(src.txLpfY, 0, dst.txLpfY, 0, 32);
            System.arraycopy(src.txLpfUv, 0, dst.txLpfUv, 0, 32);
            System.arraycopy(src.partition, 0, dst.partition, 0, 16);
            System.arraycopy(src.uvmode, 0, dst.uvmode, 0, 32);
            System.arraycopy(src.palSz, 0, dst.palSz, 0, 32);
        }
    }
}
