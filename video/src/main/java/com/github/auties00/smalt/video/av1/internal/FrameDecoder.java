package com.github.auties00.smalt.video.av1.internal;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.task.ParallelBands;

/**
 * Decodes one frame whose headers and tile data were collected: prepares the frame state, and
 * either decodes the tiles superblock row by superblock row with the in-loop filters behind them,
 * or provides the steps of two-pass decoding that {@link FrameScheduler} runs as tasks.
 *
 * <p>A frame decodes either on the calling thread, interleaving each superblock row's decoding and
 * filtering ({@link #decodeMain}), or, when {@link FrameContext#twoPass} is set, in two passes
 * ({@link BlockRecords}): {@link #parseRow} entropy decodes a superblock row of every tile,
 * {@link #reconRow} reconstructs a parsed row, {@link #deblockRow} deblocks a reconstructed row and
 * saves the lines the later filters read across row boundaries, and {@link #postFilterRow} runs
 * CDEF, super-resolution and loop restoration on a deblocked row. Both ways produce the same
 * picture.
 *
 * @implNote This implementation ports dav1d's {@code dav1d_decode_frame_init},
 * {@code dav1d_decode_frame_init_cdf} and {@code dav1d_decode_frame_main}
 * ({@code src/decode.c}) and {@code dav1d_filter_sbrow} with its stages
 * ({@code src/recon_tmpl.c}). The steps of two-pass decoding follow the task types of
 * dav1d's task threading ({@code src/thread_task.c}): a row is deblocked once every tile
 * has reconstructed it and the row above is deblocked, and its CDEF and loop restoration
 * need only its own deblocking and the line backups of the rows above, which the buffer
 * layout of dav1d's tile-threaded mode keeps per row.
 */
final class FrameDecoder {
    /**
     * Holds the distance weight pairs of distance-weighted compound prediction.
     */
    private static final int[][] QUANT_DIST_WEIGHT = {{2, 3}, {2, 5}, {2, 7}};

    /**
     * Holds the distance weight lookup of distance-weighted compound prediction.
     */
    private static final int[][] QUANT_DIST_LOOKUP = {{9, 7}, {11, 5}, {12, 4}, {13, 3}};

    /**
     * Prevents instantiation.
     */
    private FrameDecoder() {
        throw new AssertionError();
    }

    /**
     * Prepares the frame state ({@code dav1d_decode_frame_init}).
     *
     * @param f the frame, with headers, pictures and references set
     * @param limits the resource limits checked before large allocations
     */
    static void init(FrameContext f, MediaLimits limits) {
        f.mc = McDsp.select();
        f.itx = ItxDsp.select();
        f.lf = LoopFilterDsp.select();
        f.cdef = CdefDsp.select();
        f.lr = LoopRestorationDsp.select();
        var hdr = f.frameHdr;
        var seq = f.seqHdr;
        if (f.startOfTileRow.length < f.sbh) {
            f.startOfTileRow = new byte[f.sbh];
        }
        var sby = 0;
        for (var tileRow = 0; tileRow < hdr.tilingRows; tileRow++) {
            f.startOfTileRow[sby++] = (byte) tileRow;
            while (sby < hdr.rowStartSb[tileRow + 1]) {
                f.startOfTileRow[sby++] = 0;
            }
        }
        var nTs = hdr.tilingCols * hdr.tilingRows;
        if (f.ts.length != nTs) {
            f.ts = new TileState[nTs];
            for (var i = 0; i < nTs; i++) {
                f.ts[i] = new TileState();
            }
        }
        var aSz = f.sb128w * hdr.tilingRows;
        if (f.a.length != aSz) {
            f.a = new BlockContext[aSz];
            for (var i = 0; i < aSz; i++) {
                f.a[i] = new BlockContext();
            }
        }
        if (f.twoPass) {
            initTwoPass(f, limits, aSz);
        }
        var cur = f.cur;
        var yStride = cur.stride[0];
        var uvStride = cur.stride[1];
        var ttRows = f.haveTt ? f.sbh : 1;
        var cdefY = yStride * 4 * ttRows;
        if (f.cdefLine[0] == null || f.cdefLine[0].length != cdefY) {
            LimitChecks.checkAllocation(limits, 2L * cdefY);
            f.cdefLine[0] = new short[cdefY];
        }
        var cdefUv = Math.max(uvStride * 8 * ttRows, 1);
        if (f.cdefLine[1] == null || f.cdefLine[1].length != cdefUv) {
            LimitChecks.checkAllocation(limits, 2L * cdefUv);
            var uv = new short[cdefUv];
            f.cdefLine[1] = uv;
            f.cdefLine[2] = uv;
        }
        f.cdefLineOff[0][0] = 0;
        f.cdefLineOff[1][0] = yStride * 2;
        f.cdefLineOff[0][1] = 0;
        f.cdefLineOff[0][2] = uvStride * 2;
        f.cdefLineOff[1][1] = uvStride * 4;
        f.cdefLineOff[1][2] = uvStride * 6;
        if (f.haveTt && f.resized()) {
            var n = yStride * 4 * f.sbh;
            if (f.cdefLpfLine[0] == null || f.cdefLpfLine[0].length != n) {
                f.cdefLpfLine[0] = new short[n];
            }
            var nUv = Math.max(uvStride * 4 * f.sbh, 1);
            if (f.cdefLpfLine[1] == null || f.cdefLpfLine[1].length != nUv) {
                f.cdefLpfLine[1] = new short[nUv];
                f.cdefLpfLine[2] = new short[nUv];
            }
        }
        var sb128 = seq.sb128 ? 1 : 0;
        var numLines = f.haveTt ? f.sbh * 4 << sb128 : 12;
        var srYStride = f.srCur.stride[0];
        var srUvStride = f.srCur.stride[1];
        var lrY = srYStride * numLines + 64;
        if (f.lrLpfLine[0] == null || f.lrLpfLine[0].length != lrY) {
            LimitChecks.checkAllocation(limits, 2L * lrY);
            f.lrLpfLine[0] = new short[lrY];
        }
        var lrUv = srUvStride * numLines + 64;
        if (f.lrLpfLine[1] == null || f.lrLpfLine[1].length != lrUv) {
            LimitChecks.checkAllocation(limits, 4L * lrUv);
            f.lrLpfLine[1] = new short[lrUv];
            f.lrLpfLine[2] = new short[lrUv];
        }
        var numSb128 = f.sb128w * f.sb128h;
        if (f.lfMask.length != numSb128) {
            LimitChecks.checkAllocation(limits, numSb128 * 2048L);
            f.lfMask = new LfMask[numSb128];
            for (var i = 0; i < numSb128; i++) {
                f.lfMask[i] = new LfMask();
            }
            f.lfLevel = new byte[numSb128 * 32 * 32 * 4 + 12];
        }
        f.srSb128w = (f.srCur.w + 127) >> 7;
        var lrMaskSz = f.srSb128w * f.sb128h * 12;
        if (f.lrMask.length != lrMaskSz) {
            LimitChecks.checkAllocation(limits, lrMaskSz * 48L);
            f.lrMask = new RestorationUnit[lrMaskSz];
            for (var i = 0; i < lrMaskSz; i++) {
                f.lrMask[i] = new RestorationUnit();
            }
        }
        f.restorePlanes = (hdr.restorationType[0] != FrameHeader.RESTORATION_NONE ? 1 : 0)
                | (hdr.restorationType[1] != FrameHeader.RESTORATION_NONE ? 2 : 0)
                | (hdr.restorationType[2] != FrameHeader.RESTORATION_NONE ? 4 : 0);
        if (hdr.sharpness != f.lastSharpness) {
            LoopFilterMasks.calcEih(f.limLut, hdr.sharpness);
            f.lastSharpness = hdr.sharpness;
        }
        LfApply.calcLfValues(f.lvl, hdr, new int[4]);
        for (var mask : f.lfMask) {
            mask.clear();
        }
        var ipredEdgeSz = f.sbh * f.sb128w * 128;
        if (f.ipredEdge[0] == null || f.ipredEdge[0].length != ipredEdgeSz) {
            LimitChecks.checkAllocation(limits, 6L * ipredEdgeSz);
            for (var p = 0; p < 3; p++) {
                f.ipredEdge[p] = new short[ipredEdgeSz];
            }
        }
        var reSz = f.sb128h * hdr.tilingCols * 32;
        if (f.txLpfRightEdge[0] == null || f.txLpfRightEdge[0].length != reSz) {
            f.txLpfRightEdge[0] = new byte[reSz];
            f.txLpfRightEdge[1] = new byte[reSz];
        }
        if (hdr.isInterOrSwitch() || hdr.allowIntrabc) {
            f.rf.init(seq, hdr, f.refpoc, f.mvs, f.refrefpoc, f.refMvs, f.haveTt ? 2 : 1);
        }
        BlockDecoder.initQuantTables(seq, hdr, hdr.quantYac, f.dq);
        if (hdr.quantQm) {
            for (var i = 0; i < 19; i++) {
                f.qm[i * 3] = QuantMatrices.get(hdr.quantQmY, 0, i);
                f.qm[i * 3 + 1] = QuantMatrices.get(hdr.quantQmU, 1, i);
                f.qm[i * 3 + 2] = QuantMatrices.get(hdr.quantQmV, 1, i);
            }
        } else {
            java.util.Arrays.fill(f.qm, null);
        }
        if (hdr.switchableCompRefs) {
            for (var i = 0; i < 7; i++) {
                var ref0poc = f.refp[i].frameHdr.frameOffset;
                for (var j = i + 1; j < 7; j++) {
                    var ref1poc = f.refp[j].frameHdr.frameOffset;
                    var d1 = Math.min(Math.abs(FrameHeader.pocDiff(seq.orderHintNBits, ref0poc, hdr.frameOffset)), 31);
                    var d0 = Math.min(Math.abs(FrameHeader.pocDiff(seq.orderHintNBits, ref1poc, hdr.frameOffset)), 31);
                    var order = d0 <= d1 ? 1 : 0;
                    int k;
                    for (k = 0; k < 3; k++) {
                        var c0 = QUANT_DIST_WEIGHT[k][order];
                        var c1 = QUANT_DIST_WEIGHT[k][order ^ 1];
                        var d0c0 = d0 * c0;
                        var d1c1 = d1 * c1;
                        if ((d0 > d1 && d0c0 < d1c1) || (d0 <= d1 && d0c0 > d1c1)) {
                            break;
                        }
                    }
                    f.jntWeights[i][j] = QUANT_DIST_LOOKUP[k][order];
                }
            }
        }
    }

    /**
     * Prepares the buffers of two-pass decoding: the block records of every tile superblock row,
     * the lowest reference rows, the above contexts and the whole-frame motion records of the
     * reconstruction pass. Buffers are kept across frames and only grow.
     *
     * @param f the frame, with its geometry set
     * @param limits the resource limits checked before large allocations
     * @param aSz the number of above contexts
     */
    private static void initTwoPass(FrameContext f, MediaLimits limits, int aSz) {
        var rows = f.sbh * f.frameHdr.tilingCols;
        if (f.records.length < rows) {
            f.records = java.util.Arrays.copyOf(f.records, rows);
        }
        if (f.lowestPx.length < rows * 14) {
            f.lowestPx = new int[rows * 14];
        }
        if (f.reconA.length != aSz) {
            f.reconA = new BlockContext[aSz];
            for (var i = 0; i < aSz; i++) {
                f.reconA[i] = new BlockContext();
            }
        }
        var blockRows = f.sbh * f.sbStep;
        var size = (long) blockRows * f.b4Stride * RefMvs.BLOCK_INTS;
        if (f.reconBlocks.length < size) {
            LimitChecks.checkAllocation(limits, 4 * size);
            f.reconBlocks = new int[(int) size];
        }
        f.reconBlocksStride = f.b4Stride;
        f.reconBlocksRows = blockRows;
    }

    /**
     * Splits the tile groups into tiles and prepares each tile ({@code dav1d_decode_frame_init_cdf}).
     *
     * @param f the frame
     * @return the index of the tile whose CDFs the frame stores, or {@code -1}
     * @throws IllegalStateException when the tile sizes are inconsistent
     */
    static int initTiles(FrameContext f) {
        var hdr = f.frameHdr;
        if (hdr.refreshContext) {
            System.arraycopy(f.inCdf, 0, f.outCdf, 0, Cdf.SIZE);
        }
        var tileRow = 0;
        var tileCol = 0;
        var updateTile = -1;
        for (var i = 0; i < f.nTileData; i++) {
            var data = f.tileData[i];
            var pos = f.tileOffset[i];
            var size = f.tileSize[i];
            for (var j = f.tileStart[i]; j <= f.tileEnd[i]; j++) {
                int tileSz;
                if (j == f.tileEnd[i]) {
                    tileSz = size;
                } else {
                    if (hdr.tilingNBytes > size) {
                        throw new IllegalStateException("truncated tile size");
                    }
                    tileSz = 0;
                    for (var k = 0; k < hdr.tilingNBytes; k++) {
                        tileSz |= (data[pos++] & 0xFF) << (k * 8);
                    }
                    tileSz++;
                    size -= hdr.tilingNBytes;
                    if (tileSz <= 0 || tileSz > size) {
                        throw new IllegalStateException("tile size exceeds the tile group");
                    }
                }
                TileDecoder.setupTile(f.ts[j], f, data, pos, tileSz, tileRow, tileCol++);
                if (tileCol == hdr.tilingCols) {
                    tileCol = 0;
                    tileRow++;
                }
                if (j == hdr.tilingUpdate && hdr.refreshContext) {
                    updateTile = j;
                }
                pos += tileSz;
                size -= tileSz;
            }
        }
        return updateTile;
    }

    /**
     * Decodes every superblock row of every tile and filters each superblock row behind it
     * ({@code dav1d_decode_frame_main}).
     *
     * @param f the frame
     * @param t the task context
     * @return {@code false} when the bitstream is invalid
     */
    static boolean decodeMain(FrameContext f, TaskContext t) {
        var hdr = f.frameHdr;
        t.f = f;
        t.pass = TaskContext.PASS_SINGLE;
        var keyOrIntra = hdr.isKeyOrIntra();
        for (var n = 0; n < f.sb128w * hdr.tilingRows; n++) {
            f.a[n].reset(keyOrIntra);
        }
        for (var tileRow = 0; tileRow < hdr.tilingRows; tileRow++) {
            var sbhEnd = Math.min(hdr.rowStartSb[tileRow + 1], f.sbh);
            for (var sby = hdr.rowStartSb[tileRow]; sby < sbhEnd; sby++) {
                t.by = sby << (4 + (f.seqHdr.sb128 ? 1 : 0));
                var byEnd = (t.by + f.sbStep) >> 1;
                if (hdr.useRefFrameMvs) {
                    f.rf.loadTmvs(tileRow, 0, f.bw >> 1, t.by >> 1, byEnd);
                }
                for (var tileCol = 0; tileCol < hdr.tilingCols; tileCol++) {
                    t.ts = f.ts[tileRow * hdr.tilingCols + tileCol];
                    if (!TileDecoder.decodeTileSbrow(t)) {
                        return false;
                    }
                }
                if (hdr.isInterOrSwitch()) {
                    f.rf.saveTmvs(t.rt, 0, f.bw >> 1, t.by >> 1, byEnd);
                }
                filterSbrow(t, f, sby);
            }
        }
        return true;
    }

    /**
     * Prepares a frame for two-pass decoding by {@link FrameScheduler}: resets the above contexts
     * of both passes, sets the passes of the parsing contexts and starts the reconstruction
     * wavefront. The frame must have been prepared with {@link FrameContext#haveTt} and
     * {@link FrameContext#twoPass} set.
     *
     * @param f the frame
     * @param contexts the task contexts of the parsing pass, at least
     * {@link #tileRowBands(FrameContext)} times {@link #decodeBands(FrameContext)}
     */
    static void beginTwoPass(FrameContext f, TaskContext[] contexts) {
        var hdr = f.frameHdr;
        var keyOrIntra = hdr.isKeyOrIntra();
        for (var n = 0; n < f.sb128w * hdr.tilingRows; n++) {
            f.a[n].reset(keyOrIntra);
            f.reconA[n].reset(keyOrIntra);
        }
        for (var i = 0; i < tileRowBands(f) * decodeBands(f); i++) {
            contexts[i].pass = TaskContext.PASS_PARSE;
            contexts[i].f = f;
        }
        f.wavefront.begin(f.sbh, (f.bw + f.sbStep - 1) >> f.sbShift, hdr.allowIntrabc, f.sbh * hdr.tilingCols);
    }

    /**
     * Parses one superblock row of every tile column of its tile row in the first pass of two-pass
     * decoding, the tile columns in parallel bands.
     *
     * @param f the frame
     * @param contexts the task contexts of the parsing pass
     * @param first the index of the first context of the tile row's band
     * @param sby the superblock row
     * @return {@code false} when the bitstream is invalid
     */
    static boolean parseRow(FrameContext f, TaskContext[] contexts, int first, int sby) {
        var hdr = f.frameHdr;
        var cols = hdr.tilingCols;
        var row = tileRow(f, sby);
        var by = sby << f.sbShift;
        var bands = decodeBands(f);
        if (bands == 1) {
            var t = contexts[first];
            t.by = by;
            for (var tileCol = 0; tileCol < cols; tileCol++) {
                t.ts = f.ts[row * cols + tileCol];
                if (!TileDecoder.decodeTileSbrow(t)) {
                    return false;
                }
            }
            return true;
        }
        var valid = new boolean[]{true};
        ParallelBands.run(bands, band -> {
            var t = contexts[first + band];
            t.by = by;
            for (var tileCol = band; tileCol < cols; tileCol += bands) {
                t.ts = f.ts[row * cols + tileCol];
                if (!TileDecoder.decodeTileSbrow(t)) {
                    valid[0] = false;
                    return;
                }
            }
        });
        return valid[0];
    }

    /**
     * Returns the tile row a superblock row belongs to.
     *
     * @param f the frame
     * @param sby the superblock row
     * @return the tile row
     */
    static int tileRow(FrameContext f, int sby) {
        var hdr = f.frameHdr;
        var tileRow = 0;
        while (tileRow + 1 < hdr.tilingRows && hdr.rowStartSb[tileRow + 1] <= sby) {
            tileRow++;
        }
        return tileRow;
    }

    /**
     * Reconstructs one superblock row in the second pass of two-pass decoding, tile by tile, as
     * far as the row above and the parsing of the row let it ({@link ReconWavefront}), then
     * deblocks its vertical edges, which touch no other row; the parsing of the row must have
     * started and the rows of the references it reads must be final ({@link #referencesReady}).
     * The row stops early, unreconstructed, when the parsing of the frame stops before reaching
     * its end.
     *
     * @param f the frame
     * @param t the task context of the row, in {@link TaskContext#PASS_RECON}
     * @param sby the superblock row
     */
    static void reconRow(FrameContext f, TaskContext t, int sby) {
        var hdr = f.frameHdr;
        var cols = hdr.tilingCols;
        var row = tileRow(f, sby);
        t.f = f;
        t.by = sby << f.sbShift;
        for (var tileCol = 0; tileCol < cols; tileCol++) {
            t.ts = f.ts[row * cols + tileCol];
            if (!TileDecoder.reconTileSbrow(t, f.wavefront)) {
                return;
            }
        }
        if (hdr.loopfilterLevelY[0] != 0 || hdr.loopfilterLevelY[1] != 0) {
            LfApply.sbrowCols(f, sby, f.startOfTileRow[sby], t.lfScratch);
        }
    }

    /**
     * Returns whether the rows of every reference that the inter blocks of a superblock row read
     * are final, allowing for the 8 rows the in-loop filters of a reference may still change above
     * its published progress; a frame decoded on its own always finds them final.
     *
     * @param f the frame, whose superblock row is parsed
     * @param sby the superblock row
     * @return {@code true} when the row may be reconstructed
     */
    static boolean referencesReady(FrameContext f, int sby) {
        if (!f.frameThreaded || !f.frameHdr.isInterOrSwitch()) {
            return true;
        }
        var lowest = f.lowestPx;
        var cols = f.frameHdr.tilingCols;
        var ssVer = f.cur.layout == SequenceHeader.LAYOUT_I420 ? 1 : 0;
        for (var n = 0; n < 7; n++) {
            var max = Integer.MIN_VALUE;
            for (var tileCol = 0; tileCol < cols; tileCol++) {
                var base = ((sby * cols) + tileCol) * 14 + n * 2;
                var y = lowest[base];
                var uv = lowest[base + 1];
                if (y != Integer.MIN_VALUE) {
                    max = Math.max(max, y + 8);
                }
                if (uv != Integer.MIN_VALUE) {
                    max = Math.max(max, uv * (1 << ssVer) + 8);
                }
            }
            if (max == Integer.MIN_VALUE) {
                continue;
            }
            var ref = f.refp[n];
            if (ref.progress.rows() < Math.clamp(max, 1, ref.h)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns whether the superblock rows of a frame may be reconstructed while they are being
     * parsed, superblock by superblock: when one thread parses the frame row by row in order and
     * every reference is final, so that no row waits for its parsing to learn which rows of the
     * references it reads.
     *
     * @param f the frame, prepared for two-pass decoding
     * @return {@code true} when rows are handed to reconstruction as their parsing starts
     */
    static boolean earlyReconstruction(FrameContext f) {
        if (tileRowBands(f) != 1 || decodeBands(f) != 1) {
            return false;
        }
        if (!f.frameThreaded || !f.frameHdr.isInterOrSwitch()) {
            return true;
        }
        for (var ref : f.refp) {
            if (ref != null && !ref.progress.done()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Deblocks the horizontal edges of one reconstructed superblock row, whose vertical edges
     * {@link #reconRow} filtered, and saves the lines CDEF, super-resolution and loop restoration
     * read across row boundaries; the row above must be deblocked.
     *
     * @param f the frame
     * @param sby the superblock row
     */
    static void deblockRow(FrameContext f, int sby) {
        var hdr = f.frameHdr;
        if (hdr.loopfilterLevelY[0] != 0 || hdr.loopfilterLevelY[1] != 0) {
            LfApply.sbrowRows(f, sby);
        }
        if (f.seqHdr.cdef || f.restorePlanes != 0) {
            LfApply.copyLpf(f, sby);
        }
    }

    /**
     * Runs CDEF, super-resolution and loop restoration on one deblocked superblock row; rows may be
     * filtered in any order and concurrently.
     *
     * @param t the task context providing scratch
     * @param f the frame
     * @param sby the superblock row
     */
    static void postFilterRow(TaskContext t, FrameContext f, int sby) {
        t.f = f;
        if (f.seqHdr.cdef) {
            CdefApply.filterSbrow(t, f, sby);
        }
        if (f.resized()) {
            resizeSbrow(f, sby);
        }
        if (f.restorePlanes != 0) {
            LrApply.sbrow(t, f, sby);
        }
    }

    /**
     * Returns the number of parallel bands that parse the tile rows of a frame, the tiles of
     * different tile rows being independent.
     *
     * @param f the frame
     * @return the band count, at least one
     */
    static int tileRowBands(FrameContext f) {
        return Math.max(1, Math.min(f.frameHdr.tilingRows, ParallelBands.THREADS));
    }

    /**
     * Returns the number of parallel bands that parse the tile columns of a tile row.
     *
     * @param f the frame
     * @return the band count, at least one
     */
    static int decodeBands(FrameContext f) {
        return Math.max(1, Math.min(f.frameHdr.tilingCols, ParallelBands.THREADS));
    }

    /**
     * Runs every in-loop filter stage on a superblock row ({@code dav1d_filter_sbrow}).
     *
     * @param t the task context providing scratch
     * @param f the frame
     * @param sby the superblock row
     */
    static void filterSbrow(TaskContext t, FrameContext f, int sby) {
        deblockSbrow(f, sby);
        postFilterRow(t, f, sby);
    }

    /**
     * Deblocks both edge directions of one reconstructed superblock row and saves the lines CDEF,
     * super-resolution and loop restoration read across row boundaries; the row above must be
     * deblocked.
     *
     * @param f the frame
     * @param sby the superblock row
     */
    static void deblockSbrow(FrameContext f, int sby) {
        var hdr = f.frameHdr;
        if (hdr.loopfilterLevelY[0] != 0 || hdr.loopfilterLevelY[1] != 0) {
            LfApply.sbrowCols(f, sby, f.startOfTileRow[sby]);
            LfApply.sbrowRows(f, sby);
        }
        if (f.seqHdr.cdef || f.restorePlanes != 0) {
            LfApply.copyLpf(f, sby);
        }
    }

    /**
     * Upscales a superblock row into the super-resolved picture ({@code dav1d_filter_sbrow_resize}).
     *
     * @param f the frame
     * @param sby the superblock row
     */
    private static void resizeSbrow(FrameContext f, int sby) {
        var sbsz = f.sbStep;
        var y = sby * sbsz * 4;
        var cur = f.cur;
        var sr = f.srCur;
        var mc = f.mc;
        var hasChroma = cur.layout != SequenceHeader.LAYOUT_I400;
        for (var pl = 0; pl < 1 + 2 * (hasChroma ? 1 : 0); pl++) {
            var ssVer = pl != 0 && cur.layout == SequenceHeader.LAYOUT_I420 ? 1 : 0;
            var ssHor = pl != 0 && cur.layout != SequenceHeader.LAYOUT_I444 ? 1 : 0;
            var hStart = 8 * (sby != 0 ? 1 : 0) >> ssVer;
            var dstStride = sr.stride[pl != 0 ? 1 : 0];
            var srcStride = cur.stride[pl != 0 ? 1 : 0];
            var dst = ((y * dstStride) >> ssVer) - hStart * dstStride;
            var src = ((y * srcStride) >> ssVer) - hStart * srcStride;
            var hEnd = 4 * (sbsz - 2 * (sby + 1 < f.sbh ? 1 : 0)) >> ssVer;
            var dstW = (sr.w + ssHor) >> ssHor;
            var srcW = (4 * f.bw + ssHor) >> ssHor;
            var imgH = (cur.h - sbsz * 4 * sby + ssVer) >> ssVer;
            mc.resize(sr.data[pl], dst, dstStride, cur.data[pl], src, srcStride, dstW, Math.min(imgH, hEnd) + hStart,
                    srcW, f.resizeStep[pl != 0 ? 1 : 0], f.resizeStart[pl != 0 ? 1 : 0], f.bitdepthMax);
        }
    }
}
