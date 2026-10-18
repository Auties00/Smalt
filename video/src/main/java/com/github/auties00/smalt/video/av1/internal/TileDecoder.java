package com.github.auties00.smalt.video.av1.internal;

import static com.github.auties00.smalt.video.av1.internal.BlockDecoder.BL_8X8;
import static com.github.auties00.smalt.video.av1.internal.BlockDecoder.BS_4X4;
import static com.github.auties00.smalt.video.av1.internal.BlockDecoder.PARTITION_H;
import static com.github.auties00.smalt.video.av1.internal.BlockDecoder.PARTITION_H4;
import static com.github.auties00.smalt.video.av1.internal.BlockDecoder.PARTITION_NONE;
import static com.github.auties00.smalt.video.av1.internal.BlockDecoder.PARTITION_SPLIT;
import static com.github.auties00.smalt.video.av1.internal.BlockDecoder.PARTITION_T_BOTTOM_SPLIT;
import static com.github.auties00.smalt.video.av1.internal.BlockDecoder.PARTITION_T_LEFT_SPLIT;
import static com.github.auties00.smalt.video.av1.internal.BlockDecoder.PARTITION_T_RIGHT_SPLIT;
import static com.github.auties00.smalt.video.av1.internal.BlockDecoder.PARTITION_T_TOP_SPLIT;
import static com.github.auties00.smalt.video.av1.internal.BlockDecoder.PARTITION_V;
import static com.github.auties00.smalt.video.av1.internal.BlockDecoder.PARTITION_V4;
import static com.github.auties00.smalt.video.av1.internal.BlockDecoder.decodeB;
import static com.github.auties00.smalt.video.av1.internal.BlockDecoder.fill;

/**
 * Decodes the superblock rows of tiles: the partition trees, the restoration unit parameters, and
 * the per-row bookkeeping intra prediction and the loop filter need.
 *
 * @implNote This implementation ports dav1d's {@code decode_sb}, {@code setup_tile},
 * {@code read_restoration_info} and {@code dav1d_decode_tile_sbrow} ({@code src/decode.c})
 * and {@code dav1d_backup_ipred_edge} ({@code src/recon_tmpl.c}).
 */
final class TileDecoder {
    /**
     * Prevents instantiation.
     */
    private TileDecoder() {
        throw new AssertionError();
    }

    /**
     * Returns the probability of a horizontal split when only the bottom half is outside the frame
     * ({@code gather_top_partition_prob}).
     *
     * @param cdf the CDFs
     * @param pc the offset of the partition CDF
     * @param bl the block level
     * @return the probability, as CDF entries store it
     */
    private static int gatherTopPartitionProb(char[] cdf, int pc, int bl) {
        var out = cdf[pc + PARTITION_V - 1] - cdf[pc + PARTITION_T_TOP_SPLIT];
        out += cdf[pc + PARTITION_T_LEFT_SPLIT - 1];
        if (bl != 0) {
            out += cdf[pc + PARTITION_V4 - 1] - cdf[pc + PARTITION_T_RIGHT_SPLIT];
        }
        return out;
    }

    /**
     * Returns the probability of a vertical split when only the right half is outside the frame
     * ({@code gather_left_partition_prob}).
     *
     * @param cdf the CDFs
     * @param pc the offset of the partition CDF
     * @param bl the block level
     * @return the probability, as CDF entries store it
     */
    private static int gatherLeftPartitionProb(char[] cdf, int pc, int bl) {
        var out = cdf[pc + PARTITION_H - 1] - cdf[pc + PARTITION_H];
        out += cdf[pc + PARTITION_SPLIT - 1] - cdf[pc + PARTITION_T_LEFT_SPLIT];
        if (bl != 0) {
            out += cdf[pc + PARTITION_H4 - 1] - cdf[pc + PARTITION_H4];
        }
        return out;
    }

    /**
     * Decodes one square of the partition tree ({@code decode_sb}).
     *
     * @param t the task context, positioned at the square
     * @param bl the block level of the square
     * @param node the intra edge node of the square
     * @return {@code false} when the bitstream is invalid
     */
    static boolean decodeSb(TaskContext t, int bl, IntraEdge.Node node) {
        var f = t.f;
        var ts = t.ts;
        var hsz = 16 >> bl;
        var haveHSplit = f.bw > t.bx + hsz;
        var haveVSplit = f.bh > t.by + hsz;
        if (!haveHSplit && !haveVSplit) {
            if (bl >= BL_8X8) {
                return false;
            }
            return decodeSb(t, bl + 1, node.children[0]);
        }
        var bx8 = (t.bx & 31) >> 1;
        var by8 = (t.by & 31) >> 1;
        var ctx = ((t.a.partition[bx8] >> (4 - bl)) & 1) + (((t.l.partition[by8] >> (4 - bl)) & 1) << 1);
        var pc = Cdf.PARTITION + (bl * 4 + ctx) * 16;
        var msac = ts.msac;
        var cdf = ts.cdf;
        int bp;
        var layout = f.cur.layout;
        var recon = t.pass == TaskContext.PASS_RECON;
        if (haveHSplit && haveVSplit) {
            if (recon) {
                var header = BlockRecords.peek(t);
                bp = (header & 7) == bl ? (header >> 3) & 15 : PARTITION_SPLIT;
            } else {
                bp = msac.decodeSymbolAdapt(cdf, pc, Tables.PARTITION_TYPE_COUNT[bl]);
                if (layout == SequenceHeader.LAYOUT_I422 && (bp == PARTITION_V || bp == PARTITION_V4
                        || bp == PARTITION_T_LEFT_SPLIT || bp == PARTITION_T_RIGHT_SPLIT)) {
                    return false;
                }
            }
            var b0 = Tables.BLOCK_SIZES[(bl * 10 + bp) * 2];
            var b1 = Tables.BLOCK_SIZES[(bl * 10 + bp) * 2 + 1];
            if (!decodePartition(t, bl, bp, b0, b1, hsz, node)) {
                return false;
            }
        } else if (haveHSplit) {
            var isSplit = recon ? (BlockRecords.peek(t) & 7) != bl
                    : msac.decodeBool(gatherTopPartitionProb(cdf, pc, bl)) != 0;
            if (bl >= BL_8X8) {
                return false;
            }
            if (isSplit) {
                bp = PARTITION_SPLIT;
                if (!decodeSb(t, bl + 1, node.children[0])) {
                    return false;
                }
                t.bx += hsz;
                if (!decodeSb(t, bl + 1, node.children[1])) {
                    return false;
                }
                t.bx -= hsz;
            } else {
                bp = PARTITION_H;
                if (!decodeB(t, bl, Tables.BLOCK_SIZES[(bl * 10 + PARTITION_H) * 2], PARTITION_H, node.h0)) {
                    return false;
                }
            }
        } else {
            var isSplit = recon ? (BlockRecords.peek(t) & 7) != bl
                    : msac.decodeBool(gatherLeftPartitionProb(cdf, pc, bl)) != 0;
            if (layout == SequenceHeader.LAYOUT_I422 && !isSplit) {
                return false;
            }
            if (bl >= BL_8X8) {
                return false;
            }
            if (isSplit) {
                bp = PARTITION_SPLIT;
                if (!decodeSb(t, bl + 1, node.children[0])) {
                    return false;
                }
                t.by += hsz;
                if (!decodeSb(t, bl + 1, node.children[2])) {
                    return false;
                }
                t.by -= hsz;
            } else {
                bp = PARTITION_V;
                if (!decodeB(t, bl, Tables.BLOCK_SIZES[(bl * 10 + PARTITION_V) * 2], PARTITION_V, node.v0)) {
                    return false;
                }
            }
        }
        if (!recon && (bp != PARTITION_SPLIT || bl == BL_8X8)) {
            fill(t.a.partition, bx8, hsz, Tables.AL_PART_CTX[bl * 10 + bp]);
            fill(t.l.partition, by8, hsz, Tables.AL_PART_CTX[50 + bl * 10 + bp]);
        }
        return true;
    }

    /**
     * Decodes the blocks of a coded partition of a square.
     *
     * @param t the task context
     * @param bl the block level
     * @param bp the partition
     * @param b0 the size of the first blocks
     * @param b1 the size of the second blocks
     * @param hsz half the square size in 4-sample units
     * @param node the intra edge node
     * @return {@code false} when the bitstream is invalid
     */
    private static boolean decodePartition(TaskContext t, int bl, int bp, int b0, int b1, int hsz, IntraEdge.Node node) {
        var f = t.f;
        switch (bp) {
            case PARTITION_NONE -> {
                return decodeB(t, bl, b0, PARTITION_NONE, node.o);
            }
            case PARTITION_H -> {
                if (!decodeB(t, bl, b0, PARTITION_H, node.h0)) {
                    return false;
                }
                t.by += hsz;
                if (!decodeB(t, bl, b0, PARTITION_H, node.h1)) {
                    return false;
                }
                t.by -= hsz;
            }
            case PARTITION_V -> {
                if (!decodeB(t, bl, b0, PARTITION_V, node.v0)) {
                    return false;
                }
                t.bx += hsz;
                if (!decodeB(t, bl, b0, PARTITION_V, node.v1)) {
                    return false;
                }
                t.bx -= hsz;
            }
            case PARTITION_SPLIT -> {
                if (bl == BL_8X8) {
                    if (!decodeB(t, bl, BS_4X4, PARTITION_SPLIT, IntraEdge.ALL_TR_AND_BL)) {
                        return false;
                    }
                    var tlFilter = t.tl4x4Filter;
                    t.bx++;
                    if (!decodeB(t, bl, BS_4X4, PARTITION_SPLIT, node.split[0])) {
                        return false;
                    }
                    t.bx--;
                    t.by++;
                    if (!decodeB(t, bl, BS_4X4, PARTITION_SPLIT, node.split[1])) {
                        return false;
                    }
                    t.bx++;
                    t.tl4x4Filter = tlFilter;
                    if (!decodeB(t, bl, BS_4X4, PARTITION_SPLIT, node.split[2])) {
                        return false;
                    }
                    t.bx--;
                    t.by--;
                } else {
                    if (!decodeSb(t, bl + 1, node.children[0])) {
                        return false;
                    }
                    t.bx += hsz;
                    if (!decodeSb(t, bl + 1, node.children[1])) {
                        return false;
                    }
                    t.bx -= hsz;
                    t.by += hsz;
                    if (!decodeSb(t, bl + 1, node.children[2])) {
                        return false;
                    }
                    t.bx += hsz;
                    if (!decodeSb(t, bl + 1, node.children[3])) {
                        return false;
                    }
                    t.bx -= hsz;
                    t.by -= hsz;
                }
            }
            case PARTITION_T_TOP_SPLIT -> {
                if (!decodeB(t, bl, b0, PARTITION_T_TOP_SPLIT, IntraEdge.ALL_TR_AND_BL)) {
                    return false;
                }
                t.bx += hsz;
                if (!decodeB(t, bl, b0, PARTITION_T_TOP_SPLIT, node.v1)) {
                    return false;
                }
                t.bx -= hsz;
                t.by += hsz;
                if (!decodeB(t, bl, b1, PARTITION_T_TOP_SPLIT, node.h1)) {
                    return false;
                }
                t.by -= hsz;
            }
            case PARTITION_T_BOTTOM_SPLIT -> {
                if (!decodeB(t, bl, b0, PARTITION_T_BOTTOM_SPLIT, node.h0)) {
                    return false;
                }
                t.by += hsz;
                if (!decodeB(t, bl, b1, PARTITION_T_BOTTOM_SPLIT, node.v0)) {
                    return false;
                }
                t.bx += hsz;
                if (!decodeB(t, bl, b1, PARTITION_T_BOTTOM_SPLIT, 0)) {
                    return false;
                }
                t.bx -= hsz;
                t.by -= hsz;
            }
            case PARTITION_T_LEFT_SPLIT -> {
                if (!decodeB(t, bl, b0, PARTITION_T_LEFT_SPLIT, IntraEdge.ALL_TR_AND_BL)) {
                    return false;
                }
                t.by += hsz;
                if (!decodeB(t, bl, b0, PARTITION_T_LEFT_SPLIT, node.h1)) {
                    return false;
                }
                t.by -= hsz;
                t.bx += hsz;
                if (!decodeB(t, bl, b1, PARTITION_T_LEFT_SPLIT, node.v1)) {
                    return false;
                }
                t.bx -= hsz;
            }
            case PARTITION_T_RIGHT_SPLIT -> {
                if (!decodeB(t, bl, b0, PARTITION_T_RIGHT_SPLIT, node.v0)) {
                    return false;
                }
                t.bx += hsz;
                if (!decodeB(t, bl, b1, PARTITION_T_RIGHT_SPLIT, node.h0)) {
                    return false;
                }
                t.by += hsz;
                if (!decodeB(t, bl, b1, PARTITION_T_RIGHT_SPLIT, 0)) {
                    return false;
                }
                t.by -= hsz;
                t.bx -= hsz;
            }
            case PARTITION_H4 -> {
                if (!decodeB(t, bl, b0, PARTITION_H4, node.h0)) {
                    return false;
                }
                t.by += hsz >> 1;
                if (!decodeB(t, bl, b0, PARTITION_H4, node.h4)) {
                    return false;
                }
                t.by += hsz >> 1;
                if (!decodeB(t, bl, b0, PARTITION_H4, IntraEdge.ALL_LEFT_HAS_BOTTOM)) {
                    return false;
                }
                t.by += hsz >> 1;
                if (t.by < f.bh && !decodeB(t, bl, b0, PARTITION_H4, node.h1)) {
                    return false;
                }
                t.by -= hsz * 3 >> 1;
            }
            case PARTITION_V4 -> {
                if (!decodeB(t, bl, b0, PARTITION_V4, node.v0)) {
                    return false;
                }
                t.bx += hsz >> 1;
                if (!decodeB(t, bl, b0, PARTITION_V4, node.v4)) {
                    return false;
                }
                t.bx += hsz >> 1;
                if (!decodeB(t, bl, b0, PARTITION_V4, IntraEdge.ALL_TOP_HAS_RIGHT)) {
                    return false;
                }
                t.bx += hsz >> 1;
                if (t.bx < f.bw && !decodeB(t, bl, b0, PARTITION_V4, node.v1)) {
                    return false;
                }
                t.bx -= hsz * 3 >> 1;
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    /**
     * Prepares a tile for decoding ({@code setup_tile}).
     *
     * @param ts the tile state to set up
     * @param f the frame
     * @param data the buffer holding the tile data
     * @param offset the offset of the tile data
     * @param size the size of the tile data
     * @param tileRow the tile row
     * @param tileCol the tile column
     */
    static void setupTile(TileState ts, FrameContext f, byte[] data, int offset, int size, int tileRow, int tileCol) {
        var hdr = f.frameHdr;
        var colSbStart = hdr.colStartSb[tileCol];
        var colSb128Start = colSbStart >> (f.seqHdr.sb128 ? 0 : 1);
        var colSbEnd = hdr.colStartSb[tileCol + 1];
        var rowSbStart = hdr.rowStartSb[tileRow];
        var rowSbEnd = hdr.rowStartSb[tileRow + 1];
        var sbShift = f.sbShift;
        System.arraycopy(f.inCdf, 0, ts.cdf, 0, Cdf.SIZE);
        ts.lastQidx = hdr.quantYac;
        ts.lastDeltaLf[0] = ts.lastDeltaLf[1] = ts.lastDeltaLf[2] = ts.lastDeltaLf[3] = 0;
        ts.dq = f.dq;
        ts.lflvl = f.lvl;
        ts.msac.init(data, offset, size, hdr.disableCdfUpdate);
        ts.row = tileRow;
        ts.col = tileCol;
        ts.colStart = colSbStart << sbShift;
        ts.colEnd = Math.min(colSbEnd << sbShift, f.bw);
        ts.rowStart = rowSbStart << sbShift;
        ts.rowEnd = Math.min(rowSbEnd << sbShift, f.bh);
        int sbIdx;
        int unitIdx;
        var resized = f.resized();
        if (resized) {
            sbIdx = (ts.rowStart >> 5) * f.srSb128w;
            unitIdx = (ts.rowStart & 16) >> 3;
        } else {
            sbIdx = (ts.rowStart >> 5) * f.sb128w + colSb128Start;
            unitIdx = ((ts.rowStart & 16) >> 3) + ((ts.colStart & 16) >> 4);
        }
        for (var p = 0; p < 3; p++) {
            ts.lrRef[p] = null;
            if (((f.restorePlanes >> p) & 1) == 0) {
                continue;
            }
            RestorationUnit ref;
            if (resized) {
                var ssHor = p != 0 && f.cur.layout != SequenceHeader.LAYOUT_I444 ? 1 : 0;
                var d = hdr.superResDenominator;
                var unitSizeLog2 = hdr.restorationUnitSize[p != 0 ? 1 : 0];
                var rnd = (8 << unitSizeLog2) - 1;
                var shift = unitSizeLog2 + 3;
                var x = ((4 * ts.colStart * d >> ssHor) + rnd) >> shift;
                var pxX = x << (unitSizeLog2 + ssHor);
                var uIdx = unitIdx + ((pxX & 64) >> 6);
                var sb128x = pxX >> 7;
                if (sb128x >= f.srSb128w) {
                    continue;
                }
                ref = f.lrMask[((sbIdx + sb128x) * 3 + p) * 4 + uIdx];
            } else {
                ref = f.lrMask[(sbIdx * 3 + p) * 4 + unitIdx];
            }
            ref.filterV[0] = 3;
            ref.filterV[1] = -7;
            ref.filterV[2] = 15;
            ref.filterH[0] = 3;
            ref.filterH[1] = -7;
            ref.filterH[2] = 15;
            ref.sgrWeights[0] = -32;
            ref.sgrWeights[1] = 31;
            ts.lrRef[p] = ref;
        }
    }

    /**
     * Decodes the parameters of one restoration unit ({@code read_restoration_info}).
     *
     * @param t the task context
     * @param lr the unit to fill
     * @param p the plane
     * @param frameType the plane's restoration type
     */
    private static void readRestorationInfo(TaskContext t, RestorationUnit lr, int p, int frameType) {
        var ts = t.ts;
        var msac = ts.msac;
        var cdf = ts.cdf;
        var lrRef = ts.lrRef[p];
        if (frameType == FrameHeader.RESTORATION_SWITCHABLE) {
            var filter = msac.decodeSymbolAdapt(cdf, Cdf.RESTORE_SWITCHABLE, 2);
            lr.type = filter + (filter != 0 ? 1 : 0);
        } else {
            var type = msac.decodeBoolAdapt(cdf, frameType == FrameHeader.RESTORATION_WIENER
                    ? Cdf.RESTORE_WIENER : Cdf.RESTORE_SGRPROJ);
            lr.type = type != 0 ? frameType : FrameHeader.RESTORATION_NONE;
        }
        if (lr.type == FrameHeader.RESTORATION_WIENER) {
            lr.filterV[0] = p != 0 ? 0 : msac.decodeSubexp(lrRef.filterV[0] + 5, 16, 1) - 5;
            lr.filterV[1] = msac.decodeSubexp(lrRef.filterV[1] + 23, 32, 2) - 23;
            lr.filterV[2] = msac.decodeSubexp(lrRef.filterV[2] + 17, 64, 3) - 17;
            lr.filterH[0] = p != 0 ? 0 : msac.decodeSubexp(lrRef.filterH[0] + 5, 16, 1) - 5;
            lr.filterH[1] = msac.decodeSubexp(lrRef.filterH[1] + 23, 32, 2) - 23;
            lr.filterH[2] = msac.decodeSubexp(lrRef.filterH[2] + 17, 64, 3) - 17;
            lr.sgrWeights[0] = lrRef.sgrWeights[0];
            lr.sgrWeights[1] = lrRef.sgrWeights[1];
            ts.lrRef[p] = lr;
        } else if (lr.type == FrameHeader.RESTORATION_SGRPROJ) {
            var idx = msac.decodeBools(4);
            var sgr0 = Tables.SGR_PARAMS[idx * 2];
            var sgr1 = Tables.SGR_PARAMS[idx * 2 + 1];
            lr.type += idx;
            lr.sgrWeights[0] = sgr0 != 0 ? msac.decodeSubexp(lrRef.sgrWeights[0] + 96, 128, 4) - 96 : 0;
            lr.sgrWeights[1] = sgr1 != 0 ? msac.decodeSubexp(lrRef.sgrWeights[1] + 32, 128, 4) - 32 : 95;
            System.arraycopy(lrRef.filterV, 0, lr.filterV, 0, 3);
            System.arraycopy(lrRef.filterH, 0, lr.filterH, 0, 3);
            ts.lrRef[p] = lr;
        }
    }

    /**
     * Decodes the restoration units starting in the current superblock.
     *
     * @param t the task context
     */
    private static void readRestorationUnits(TaskContext t) {
        var f = t.f;
        var hdr = f.frameHdr;
        var sbStep = f.sbStep;
        for (var p = 0; p < 3; p++) {
            if (((f.restorePlanes >> p) & 1) == 0) {
                continue;
            }
            var ssVer = p != 0 && f.cur.layout == SequenceHeader.LAYOUT_I420 ? 1 : 0;
            var ssHor = p != 0 && f.cur.layout != SequenceHeader.LAYOUT_I444 ? 1 : 0;
            var unitSizeLog2 = hdr.restorationUnitSize[p != 0 ? 1 : 0];
            var y = t.by * 4 >> ssVer;
            var h = (f.cur.h + ssVer) >> ssVer;
            var unitSize = 1 << unitSizeLog2;
            var mask = unitSize - 1;
            if ((y & mask) != 0) {
                continue;
            }
            var halfUnit = unitSize >> 1;
            if (y != 0 && y + halfUnit > h) {
                continue;
            }
            var frameType = hdr.restorationType[p];
            if (f.resized()) {
                var w = (f.srCur.w + ssHor) >> ssHor;
                var nUnits = Math.max(1, (w + halfUnit) >> unitSizeLog2);
                var d = hdr.superResDenominator;
                var rnd = unitSize * 8 - 1;
                var shift = unitSizeLog2 + 3;
                var x0 = ((4 * t.bx * d >> ssHor) + rnd) >> shift;
                var x1 = ((4 * (t.bx + sbStep) * d >> ssHor) + rnd) >> shift;
                for (var x = x0; x < Math.min(x1, nUnits); x++) {
                    var pxX = x << (unitSizeLog2 + ssHor);
                    var sbIdx = (t.by >> 5) * f.srSb128w + (pxX >> 7);
                    var unitIdx = ((t.by & 16) >> 3) + ((pxX & 64) >> 6);
                    readRestorationInfo(t, f.lrMask[(sbIdx * 3 + p) * 4 + unitIdx], p, frameType);
                }
            } else {
                var x = 4 * t.bx >> ssHor;
                if ((x & mask) != 0) {
                    continue;
                }
                var w = (f.cur.w + ssHor) >> ssHor;
                if (x != 0 && x + halfUnit > w) {
                    continue;
                }
                var sbIdx = (t.by >> 5) * f.srSb128w + (t.bx >> 5);
                var unitIdx = ((t.by & 16) >> 3) + ((t.bx & 16) >> 4);
                readRestorationInfo(t, f.lrMask[(sbIdx * 3 + p) * 4 + unitIdx], p, frameType);
            }
        }
    }

    /**
     * Decodes one superblock row of a tile ({@code dav1d_decode_tile_sbrow}).
     *
     * @param t the task context with {@link TaskContext#ts} and {@link TaskContext#by} set
     * @return {@code false} when the bitstream is invalid
     */
    static boolean decodeTileSbrow(TaskContext t) {
        var f = t.f;
        var hdr = f.frameHdr;
        var rootBl = f.seqHdr.sb128 ? BlockDecoder.BL_128X128 : BlockDecoder.BL_64X64;
        var root = f.seqHdr.sb128 ? IntraEdge.SB128 : IntraEdge.SB64;
        var ts = t.ts;
        var sbStep = f.sbStep;
        var tileRow = ts.row;
        var tileCol = ts.col;
        var colSbStart = hdr.colStartSb[tileCol];
        var colSb128Start = colSbStart >> (f.seqHdr.sb128 ? 0 : 1);
        if (hdr.isInterOrSwitch() || hdr.allowIntrabc) {
            t.rt.sbrowInit(f.rf, ts.colStart, ts.colEnd, ts.rowStart, ts.rowEnd, t.by >> f.sbShift, ts.row);
        }
        t.l.reset(hdr.isKeyOrIntra());
        if (f.haveTt && hdr.useRefFrameMvs) {
            f.rf.loadTmvs(ts.row, ts.colStart >> 1, ts.colEnd >> 1, t.by >> 1, (t.by + sbStep) >> 1);
        }
        for (var i = 32; i < 64; i++) {
            t.palSzUv[i] = 0;
        }
        var recordIdx = (t.by >> f.sbShift) * hdr.tilingCols + tileCol;
        var parsedSbs = 0;
        if (f.twoPass) {
            BlockRecords.beginParse(t, f, recordIdx);
        }
        var sb128y = t.by >> 5;
        t.aIdx = colSb128Start + tileRow * f.sb128w;
        t.lfMaskIdx = sb128y * f.sb128w + colSb128Start;
        for (t.bx = ts.colStart; t.bx < ts.colEnd; t.bx += sbStep) {
            t.a = f.a[t.aIdx];
            t.lfMask = f.lfMask[t.lfMaskIdx];
            var cdefIdx = t.lfMask.cdefIdx;
            if (rootBl == BlockDecoder.BL_128X128) {
                t.curSbCdefIdx = 0;
                cdefIdx[0] = cdefIdx[1] = cdefIdx[2] = cdefIdx[3] = -1;
            } else {
                t.curSbCdefIdx = ((t.bx & 16) >> 4) + ((t.by & 16) >> 3);
                cdefIdx[t.curSbCdefIdx] = -1;
            }
            if (f.restorePlanes != 0) {
                readRestorationUnits(t);
            }
            if (!decodeSb(t, rootBl, root)) {
                return false;
            }
            if (f.twoPass) {
                f.wavefront.advanceParsed(t.by >> f.sbShift, recordIdx, t.records, ++parsedSbs);
            }
            if ((t.bx & 16) != 0 || f.seqHdr.sb128) {
                t.aIdx++;
                t.lfMaskIdx++;
            }
        }
        if (f.seqHdr.refFrameMvs && f.haveTt && hdr.isInterOrSwitch()) {
            f.rf.saveTmvs(t.rt, ts.colStart >> 1, ts.colEnd >> 1, t.by >> 1, (t.by + sbStep) >> 1);
        }
        if (f.twoPass) {
            BlockRecords.endParse(t, f, recordIdx);
        } else {
            backupIpredEdge(t);
        }
        if (!f.twoPass) {
            saveRightEdge(t);
        }
        return !ts.msac.overread();
    }

    /**
     * Reconstructs one superblock row of a tile from the records the parsing pass of two-pass
     * decoding wrote for it, superblock by superblock as the parsing pass records them and the
     * wavefront allows, saving the bottom row of each superblock for the intra prediction of the
     * next superblock row.
     *
     * @param t the task context, in {@link TaskContext#PASS_RECON}, with
     * {@link TaskContext#ts} and {@link TaskContext#by} set
     * @param wavefront the progress of the superblock rows of the frame
     * @return {@code false} when the parsing pass stopped before recording the row, which is then
     * left unreconstructed
     */
    static boolean reconTileSbrow(TaskContext t, ReconWavefront wavefront) {
        var f = t.f;
        var hdr = f.frameHdr;
        var sb128 = f.seqHdr.sb128;
        var rootBl = sb128 ? BlockDecoder.BL_128X128 : BlockDecoder.BL_64X64;
        var root = sb128 ? IntraEdge.SB128 : IntraEdge.SB64;
        var ts = t.ts;
        var sbStep = f.sbStep;
        var sby = t.by >> f.sbShift;
        var colSb128Start = hdr.colStartSb[ts.col] >> (sb128 ? 0 : 1);
        if (hdr.isInterOrSwitch()) {
            t.rt.reconInit(f.reconBlocks, f.reconBlocksStride, f.reconBlocksRows, sbStep, sby);
        }
        t.l.reset(hdr.isKeyOrIntra());
        var recordIdx = sby * hdr.tilingCols + ts.col;
        BlockRecords.beginRecon(t, f, recordIdx);
        t.reconLflvl = f.lvl;
        java.util.Arrays.fill(t.reconDeltaLf, 0);
        t.aIdx = colSb128Start + ts.row * f.sb128w;
        t.lfMaskIdx = (t.by >> 5) * f.sb128w + colSb128Start;
        var sbs = 0;
        for (t.bx = ts.colStart; t.bx < ts.colEnd; t.bx += sbStep) {
            var sbx = t.bx >> f.sbShift;
            if (!wavefront.awaitParsed(sby, recordIdx, ++sbs)) {
                return false;
            }
            t.records = wavefront.records(recordIdx);
            wavefront.awaitAbove(sby, sbx);
            t.a = f.reconA[t.aIdx];
            t.lfMask = f.lfMask[t.lfMaskIdx];
            decodeSb(t, rootBl, root);
            backupIpredEdge(t, t.bx, Math.min(t.bx + sbStep, ts.colEnd));
            wavefront.advance(sby, sbx + 1);
            if ((t.bx & 16) != 0 || sb128) {
                t.aIdx++;
                t.lfMaskIdx++;
            }
        }
        saveRightEdge(t);
        return true;
    }

    /**
     * Saves the loop filter transform sizes of the right edge of a tile superblock row, which the
     * deblocking of the tile column to its right reads.
     *
     * @param t the task context, at the end of the tile superblock row
     */
    private static void saveRightEdge(TaskContext t) {
        var f = t.f;
        var sbStep = f.sbStep;
        var tileCol = t.ts.col;
        var alignH = (f.bh + 31) & ~31;
        System.arraycopy(t.l.txLpfY, t.by & 16, f.txLpfRightEdge[0], alignH * tileCol + t.by, sbStep);
        var ssVer = f.cur.layout == SequenceHeader.LAYOUT_I420 ? 1 : 0;
        alignH >>= ssVer;
        System.arraycopy(t.l.txLpfUv, (t.by & 16) >> ssVer, f.txLpfRightEdge[1], alignH * tileCol + (t.by >> ssVer),
                sbStep >> ssVer);
    }

    /**
     * Saves the bottom row of the superblock row before in-loop filtering, for the intra prediction of
     * the next superblock row ({@code dav1d_backup_ipred_edge}).
     *
     * @param t the task context
     */
    static void backupIpredEdge(TaskContext t) {
        backupIpredEdge(t, t.ts.colStart, t.ts.colEnd);
    }

    /**
     * Saves the bottom row of a range of columns of the superblock row before in-loop filtering,
     * for the intra prediction of the next superblock row.
     *
     * @param t the task context
     * @param xStart the first column, in 4-sample units
     * @param xEnd the column after the last one, in 4-sample units
     */
    static void backupIpredEdge(TaskContext t, int xStart, int xEnd) {
        var f = t.f;
        var sby = t.by >> f.sbShift;
        var sbyOff = f.sb128w * 128 * sby;
        var cur = f.cur;
        var y = xStart * 4 + ((t.by + f.sbStep) * 4 - 1) * cur.stride[0];
        System.arraycopy(cur.data[0], y, f.ipredEdge[0], sbyOff + xStart * 4, 4 * (xEnd - xStart));
        if (cur.layout != SequenceHeader.LAYOUT_I400) {
            var ssVer = cur.layout == SequenceHeader.LAYOUT_I420 ? 1 : 0;
            var ssHor = cur.layout != SequenceHeader.LAYOUT_I444 ? 1 : 0;
            var uvOff = (xStart * 4 >> ssHor) + (((t.by + f.sbStep) * 4 >> ssVer) - 1) * cur.stride[1];
            var n = 4 * (xEnd - xStart) >> ssHor;
            for (var pl = 1; pl <= 2; pl++) {
                System.arraycopy(cur.data[pl], uvOff, f.ipredEdge[pl], sbyOff + (xStart * 4 >> ssHor), n);
            }
        }
    }
}
