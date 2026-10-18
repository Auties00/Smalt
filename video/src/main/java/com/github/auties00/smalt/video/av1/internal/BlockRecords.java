package com.github.auties00.smalt.video.av1.internal;

import java.util.Arrays;

import static com.github.auties00.smalt.video.av1.internal.BlockDecoder.fill;
import static com.github.auties00.smalt.video.av1.internal.Tables.BLOCK_DIMENSIONS;

/**
 * Records what the parsing pass of two-pass decoding learns about every block of a tile superblock
 * row, and replays it in the reconstruction pass.
 *
 * <p>The parsing pass ({@link TaskContext#PASS_PARSE}) runs the whole entropy decoding of
 * {@link BlockDecoder} and {@link TileDecoder}, including every context, motion vector, loop filter
 * mask and restoration unit, but skips prediction and inverse transforms. For each block it appends
 * to {@link FrameContext#records} a block record holding what reconstruction needs, followed by one
 * coefficient entry for every transform block in reconstruction order. The reconstruction pass
 * ({@link TaskContext#PASS_RECON}) walks the same partition trees, taking each partition from the
 * next block record, and reconstructs every block from its record with the same {@link Recon} code,
 * so both ways of decoding produce the same picture.
 *
 * <p>Every entry is one or more {@code int}s:
 * <ul>
 *   <li>the block header: the block level in bits 0 to 2, the partition in bits 3 to 6, the block
 *       size in bits 7 to 11, and the flags {@link #INTRA}, {@link #SKIP}, {@link #PALETTE} and
 *       {@link #WARP};</li>
 *   <li>for an intra block: the luma and chroma modes and angles, the transform sizes and palette
 *       sizes, the chroma-from-luma factors and, with a palette, the 24 palette colors and the
 *       packed color indices;</li>
 *   <li>for an inter or intra block copy block: the modes, compound type, references and filters,
 *       both motion vectors, the transform sizes and splits and, with a local warp, its model;</li>
 *   <li>for a transform block: {@code eob + 1} in bits 0 to 10 ({@code 0} for an all-zero block),
 *       the transform type in bits 11 to 15 and the number {@code n} of non-zero coefficients in bits
 *       16 to 26, followed by {@code n} coefficients, each the dequantized value shifted left by 10
 *       or'ed with its position.</li>
 * </ul>
 *
 * <p>The parsing pass also tracks, per tile superblock row and reference, the lowest sample row its
 * motion compensation reads ({@link FrameContext#lowestPx}), so that a frame decoded concurrently
 * with its references waits only for the rows it needs.
 *
 * @implNote This implementation follows the two-pass decoding of dav1d's frame threading
 *           ({@code decode_b} with {@code t->frame_thread.pass}, {@code read_coef_blocks},
 *           {@code mc_lowest_px}, {@code affine_lowest_px} and {@code obmc_lowest_px} in
 *           {@code src/decode.c}, and the pass-2 branches of {@code src/recon_tmpl.c}), with the
 *           per-4x4 block and coefficient arrays replaced by one sequential stream per tile
 *           superblock row holding only the non-zero coefficients.
 */
final class BlockRecords {
    /**
     * The block header flag of an intra block.
     */
    static final int INTRA = 1 << 12;

    /**
     * The block header flag of a block without residual.
     */
    static final int SKIP = 1 << 13;

    /**
     * The block header flag of an intra block with a palette.
     */
    static final int PALETTE = 1 << 14;

    /**
     * The block header flag of an inter block with a local warp model.
     */
    static final int WARP = 1 << 15;

    /**
     * The block header flag of the first block of a superblock whose loop filter deltas follow
     * the block's mode entries, one byte each.
     */
    static final int DELTA_LF = 1 << 16;

    /**
     * The position in the block header of the block's segment.
     */
    private static final int SEGMENT_SHIFT = 17;

    /**
     * The number of {@code int}s a tile superblock row's records start with.
     */
    private static final int INITIAL_CAPACITY = 1 << 12;

    /**
     * Prevents instantiation.
     */
    private BlockRecords() {
        throw new AssertionError();
    }

    /**
     * Points a parsing context at the records of a tile superblock row and clears the row's lowest
     * reference rows.
     *
     * @param t   the task context, in {@link TaskContext#PASS_PARSE}
     * @param f   the frame
     * @param idx the index of the tile superblock row, {@code sby * tilingCols + tileCol}
     */
    static void beginParse(TaskContext t, FrameContext f, int idx) {
        var records = f.records[idx];
        if (records == null) {
            records = new int[INITIAL_CAPACITY];
            f.records[idx] = records;
        }
        t.records = records;
        t.recordPos = 0;
        Arrays.fill(f.lowestPx, idx * 14, idx * 14 + 14, Integer.MIN_VALUE);
    }

    /**
     * Stores the records a parsing context wrote, which may have outgrown their array.
     *
     * @param t   the task context, in {@link TaskContext#PASS_PARSE}
     * @param f   the frame
     * @param idx the index of the tile superblock row
     */
    static void endParse(TaskContext t, FrameContext f, int idx) {
        f.records[idx] = t.records;
    }

    /**
     * Points a reconstruction context at the records of a tile superblock row.
     *
     * @param t   the task context, in {@link TaskContext#PASS_RECON}
     * @param f   the frame
     * @param idx the index of the tile superblock row
     */
    static void beginRecon(TaskContext t, FrameContext f, int idx) {
        t.records = f.records[idx];
        t.recordPos = 0;
    }

    /**
     * Makes room for {@code n} more entries and returns the records array.
     *
     * @param t the task context, in {@link TaskContext#PASS_PARSE}
     * @param n the number of {@code int}s about to be written
     * @return the records array, with at least {@code n} free entries from
     *         {@link TaskContext#recordPos}
     */
    static int[] reserve(TaskContext t, int n) {
        var records = t.records;
        var needed = t.recordPos + n;
        if (needed > records.length) {
            records = Arrays.copyOf(records, Math.max(needed, records.length * 2));
            t.records = records;
        }
        return records;
    }

    /**
     * Appends one {@code int} to the records.
     *
     * @param t the task context, in {@link TaskContext#PASS_PARSE}
     * @param v the value
     */
    static void put(TaskContext t, int v) {
        var records = t.records;
        var pos = t.recordPos;
        if (pos == records.length) {
            records = reserve(t, 1);
        }
        records[pos] = v;
        t.recordPos = pos + 1;
    }

    /**
     * Returns the block header of the next block record without consuming it.
     *
     * @param t the task context, in {@link TaskContext#PASS_RECON}
     * @return the header
     */
    static int peek(TaskContext t) {
        return t.records[t.recordPos];
    }

    /**
     * Consumes one coefficient entry: scatters its coefficients into {@code cf}, which must be all
     * zero, and sets {@link TaskContext#txtp}.
     *
     * @param t  the task context, in {@link TaskContext#PASS_RECON}
     * @param cf the coefficient buffer
     * @return the end-of-block position, or {@code -1} when every coefficient is zero
     */
    static int fetchCoefs(TaskContext t, int[] cf) {
        var records = t.records;
        var pos = t.recordPos;
        var header = records[pos++];
        var eob = (header & 0x7FF) - 1;
        if (eob >= 0) {
            t.txtp = (header >>> 11) & 31;
            var end = pos + (header >>> 16);
            for (; pos < end; pos++) {
                var v = records[pos];
                cf[v & 1023] = v >> 10;
            }
        }
        t.recordPos = pos;
        return eob;
    }

    /**
     * Returns the common block header of a block.
     *
     * @param b the block
     * @return the header without the type flags
     */
    private static int header(Av1Block b) {
        return b.bl | b.bp << 3 | b.bs << 7 | (b.skip ? SKIP : 0) | b.segId << SEGMENT_SHIFT;
    }

    /**
     * Appends the loop filter deltas of the current superblock when the block being recorded is
     * the first of a superblock that signals them, flagging its header.
     *
     * @param t      the task context, in {@link TaskContext#PASS_PARSE}
     * @param header the position of the block header
     */
    private static void putDeltaLf(TaskContext t, int header) {
        if (!t.recordDeltaLf) {
            return;
        }
        t.recordDeltaLf = false;
        var d = t.ts.lastDeltaLf;
        var records = reserve(t, 1);
        records[header] |= DELTA_LF;
        records[t.recordPos++] = (d[0] & 0xFF) | (d[1] & 0xFF) << 8 | (d[2] & 0xFF) << 16 | d[3] << 24;
    }

    /**
     * Consumes the loop filter deltas of a block whose header carries {@link #DELTA_LF} and derives
     * the loop filter levels of its superblock.
     *
     * @param t the task context, in {@link TaskContext#PASS_RECON}
     */
    private static void takeDeltaLf(TaskContext t) {
        var v = t.records[t.recordPos++];
        var d = t.reconDeltaLf;
        var d0 = (byte) v;
        var d1 = (byte) (v >> 8);
        var d2 = (byte) (v >> 16);
        var d3 = v >> 24;
        if (d0 == d[0] && d1 == d[1] && d2 == d[2] && d3 == d[3]) {
            return;
        }
        d[0] = d0;
        d[1] = d1;
        d[2] = d2;
        d[3] = d3;
        if (d0 == 0 && d1 == 0 && d2 == 0 && d3 == 0) {
            t.reconLflvl = t.f.lvl;
        } else {
            LfApply.calcLfValues(t.reconLflvlMem, t.f.frameHdr, d);
            t.reconLflvl = t.reconLflvlMem;
        }
    }

    /**
     * Records an intra block whose modes, palette and transform sizes are decoded.
     *
     * @param t         the task context, in {@link TaskContext#PASS_PARSE}
     * @param b         the block
     * @param hasChroma whether the block has chroma
     */
    static void writeIntra(TaskContext t, Av1Block b, boolean hasChroma) {
        var palette = b.palSz[0] != 0 || (hasChroma && b.palSz[1] != 0);
        var records = reserve(t, 4);
        var pos = t.recordPos;
        records[pos] = header(b) | INTRA | (palette ? PALETTE : 0);
        records[pos + 1] = b.yMode | (b.yAngle & 0xFF) << 8 | b.uvMode << 16 | (b.uvAngle & 0xFF) << 24;
        records[pos + 2] = b.tx | b.uvtx << 8 | b.palSz[0] << 16 | (hasChroma ? b.palSz[1] : 0) << 24;
        records[pos + 3] = (b.cflAlpha[0] & 0xFFFF) | b.cflAlpha[1] << 16;
        t.recordPos = pos + 4;
        putDeltaLf(t, pos);
        if (palette) {
            var bw4 = BLOCK_DIMENSIONS[b.bs * 4];
            var bh4 = BLOCK_DIMENSIONS[b.bs * 4 + 1];
            var layout = t.f.cur.layout;
            var ssVer = layout == SequenceHeader.LAYOUT_I420 ? 1 : 0;
            var ssHor = layout != SequenceHeader.LAYOUT_I444 ? 1 : 0;
            records = reserve(t, 12);
            pos = t.recordPos;
            var pal = t.pal;
            for (var i = 0; i < 12; i++) {
                records[pos + i] = (pal[2 * i] & 0xFFFF) | pal[2 * i + 1] << 16;
            }
            t.recordPos = pos + 12;
            if (b.palSz[0] != 0) {
                putBytes(t, t.palIdxY, bw4 * bh4 * 8);
            }
            if (hasChroma && b.palSz[1] != 0) {
                var cbw4 = (bw4 + ssHor) >> ssHor;
                var cbh4 = (bh4 + ssVer) >> ssVer;
                putBytes(t, t.palIdxUv, cbw4 * cbh4 * 8);
            }
        }
    }

    /**
     * Records an inter or intra block copy block whose modes, motion and transform sizes are
     * decoded.
     *
     * @param t       the task context, in {@link TaskContext#PASS_PARSE}
     * @param b       the block
     * @param filter0 the horizontal interpolation filter the contexts record
     * @param filter1 the vertical interpolation filter the contexts record
     * @param warp    whether the block uses {@link TaskContext#warpmv}
     */
    static void writeInter(TaskContext t, Av1Block b, int filter0, int filter1, boolean warp) {
        var records = reserve(t, warp ? 17 : 6);
        var pos = t.recordPos;
        records[pos] = header(b) | (warp ? WARP : 0);
        records[pos + 1] = b.compType | b.interMode << 3 | b.motionMode << 6 | b.interintraType << 8
                | b.interintraMode << 10 | b.wedgeIdx << 12 | b.maskSign << 16 | filter0 << 17 | filter1 << 19
                | b.ref[0] << 21 | (b.ref[1] + 1) << 24 | b.filter2d << 27;
        records[pos + 2] = b.mv[0];
        records[pos + 3] = b.mv[1];
        records[pos + 4] = b.maxYtx | b.uvtx << 8 | b.txSplit0 << 16;
        records[pos + 5] = b.txSplit1;
        t.recordPos = pos + 6;
        putDeltaLf(t, pos);
        records = t.records;
        pos = t.recordPos;
        if (warp) {
            records = reserve(t, 11);
            var w = t.warpmv;
            records[pos] = w.type;
            System.arraycopy(w.matrix, 0, records, pos + 1, 6);
            for (var i = 0; i < 4; i++) {
                records[pos + 7 + i] = w.abcd[i];
            }
            pos += 11;
        }
        t.recordPos = pos;
    }

    /**
     * Appends bytes to the records, four per {@code int} in little-endian order.
     *
     * @param t   the task context
     * @param src the bytes
     * @param n   the number of bytes, a multiple of 4
     */
    private static void putBytes(TaskContext t, byte[] src, int n) {
        var records = reserve(t, n >> 2);
        var pos = t.recordPos;
        for (var i = 0; i < n; i += 4) {
            records[pos++] = (src[i] & 0xFF) | (src[i + 1] & 0xFF) << 8 | (src[i + 2] & 0xFF) << 16 | src[i + 3] << 24;
        }
        t.recordPos = pos;
    }

    /**
     * Consumes bytes appended by {@link #putBytes}.
     *
     * @param t   the task context
     * @param dst the bytes
     * @param n   the number of bytes, a multiple of 4
     */
    private static void getBytes(TaskContext t, byte[] dst, int n) {
        var records = t.records;
        var pos = t.recordPos;
        for (var i = 0; i < n; i += 4) {
            var v = records[pos++];
            dst[i] = (byte) v;
            dst[i + 1] = (byte) (v >> 8);
            dst[i + 2] = (byte) (v >> 16);
            dst[i + 3] = (byte) (v >> 24);
        }
        t.recordPos = pos;
    }

    /**
     * Reconstructs the next recorded block and updates the contexts and motion records the
     * reconstruction of later blocks reads ({@code decode_b} in its second pass).
     *
     * @param t              the task context, in {@link TaskContext#PASS_RECON}, positioned at the
     *                       block
     * @param bl             the block level
     * @param bs             the block size
     * @param bp             the partition the block came from
     * @param intraEdgeFlags the block's intra edge flags
     */
    static void reconstruct(TaskContext t, int bl, int bs, int bp, int intraEdgeFlags) {
        var f = t.f;
        var hdr = f.frameHdr;
        var b = t.b;
        var records = t.records;
        var pos = t.recordPos;
        var header = records[pos];
        b.bl = bl;
        b.bs = bs;
        b.bp = bp;
        b.skip = (header & SKIP) != 0;
        b.segId = (header >>> SEGMENT_SHIFT) & 7;
        var lf = hdr.loopfilterLevelY[0] != 0 || hdr.loopfilterLevelY[1] != 0;
        var bx4 = t.bx & 31;
        var by4 = t.by & 31;
        var bw4 = BLOCK_DIMENSIONS[bs * 4];
        var bh4 = BLOCK_DIMENSIONS[bs * 4 + 1];
        var layout = f.cur.layout;
        var ssVer = layout == SequenceHeader.LAYOUT_I420 ? 1 : 0;
        var ssHor = layout != SequenceHeader.LAYOUT_I444 ? 1 : 0;
        var cbx4 = bx4 >> ssHor;
        var cby4 = by4 >> ssVer;
        var cbw4 = (bw4 + ssHor) >> ssHor;
        var cbh4 = (bh4 + ssVer) >> ssVer;
        var hasChroma = layout != SequenceHeader.LAYOUT_I400 && (bw4 > ssHor || (t.bx & 1) != 0)
                && (bh4 > ssVer || (t.by & 1) != 0);
        var inter = hdr.isInterOrSwitch();
        if ((header & INTRA) != 0) {
            b.intra = true;
            var modes = records[pos + 1];
            var sizes = records[pos + 2];
            var cfl = records[pos + 3];
            b.yMode = modes & 0xFF;
            b.yAngle = (byte) (modes >> 8);
            b.uvMode = (modes >> 16) & 0xFF;
            b.uvAngle = (byte) (modes >> 24);
            b.tx = sizes & 0xFF;
            b.uvtx = (sizes >> 8) & 0xFF;
            b.palSz[0] = (sizes >> 16) & 0xFF;
            b.palSz[1] = sizes >>> 24;
            b.cflAlpha[0] = (short) cfl;
            b.cflAlpha[1] = cfl >> 16;
            t.recordPos = pos + 4;
            if ((header & DELTA_LF) != 0) {
                takeDeltaLf(t);
            }
            if ((header & PALETTE) != 0) {
                pos = t.recordPos;
                var pal = t.pal;
                for (var i = 0; i < 12; i++) {
                    var v = records[pos + i];
                    pal[2 * i] = (short) v;
                    pal[2 * i + 1] = (short) (v >> 16);
                }
                t.recordPos = pos + 12;
                if (b.palSz[0] != 0) {
                    getBytes(t, t.palIdxY, bw4 * bh4 * 8);
                }
                if (b.palSz[1] != 0) {
                    getBytes(t, t.palIdxUv, cbw4 * cbh4 * 8);
                }
            }
            Recon.reconBIntra(t, bs, intraEdgeFlags, b);
            if (lf) {
                LoopFilterMasks.createLfMaskIntra(t.lfMask, f.lfLevel, f.b4Stride, t.reconLflvl, b.segId * 64,
                        t.bx, t.by, f.w4, f.h4, bs, b.tx, b.uvtx, layout,
                        t.a.txLpfY, bx4, t.l.txLpfY, by4,
                        hasChroma ? t.a.txLpfUv : null, cbx4, hasChroma ? t.l.txLpfUv : null, cby4);
            }
            var yModeNofilt = b.yMode == IpredDsp.FILTER_PRED ? IpredDsp.DC_PRED : b.yMode;
            fill(t.a.mode, bx4, bw4, yModeNofilt);
            fill(t.l.mode, by4, bh4, yModeNofilt);
            fill(t.a.intra, bx4, bw4, 1);
            fill(t.l.intra, by4, bh4, 1);
            if (inter) {
                t.rt.splatEdges((t.by & 31) + 5, t.bx, bw4, bh4, RefMvs.INVALID_MV, RefMvs.packInfo(0, -1, bs, 0));
            }
            if (hasChroma) {
                fill(t.a.uvmode, cbx4, cbw4, b.uvMode);
                fill(t.l.uvmode, cby4, cbh4, b.uvMode);
            }
            return;
        }
        b.intra = false;
        var modes = records[pos + 1];
        b.compType = modes & 7;
        b.interMode = (modes >> 3) & 7;
        b.motionMode = (modes >> 6) & 3;
        b.interintraType = (modes >> 8) & 3;
        b.interintraMode = (modes >> 10) & 3;
        b.wedgeIdx = (modes >> 12) & 15;
        b.maskSign = (modes >> 16) & 1;
        var filter0 = (modes >> 17) & 3;
        var filter1 = (modes >> 19) & 3;
        b.ref[0] = (modes >> 21) & 7;
        b.ref[1] = ((modes >> 24) & 7) - 1;
        b.filter2d = modes >>> 27;
        b.mv[0] = records[pos + 2];
        b.mv[1] = records[pos + 3];
        var sizes = records[pos + 4];
        b.maxYtx = sizes & 0xFF;
        b.uvtx = (sizes >> 8) & 0xFF;
        b.txSplit0 = sizes >>> 16;
        b.txSplit1 = records[pos + 5];
        t.recordPos = pos + 6;
        if ((header & DELTA_LF) != 0) {
            takeDeltaLf(t);
        }
        pos = t.recordPos;
        if ((header & WARP) != 0) {
            var w = t.warpmv;
            w.type = records[pos];
            System.arraycopy(records, pos + 1, w.matrix, 0, 6);
            for (var i = 0; i < 4; i++) {
                w.abcd[i] = (short) records[pos + 7 + i];
            }
            pos += 11;
        }
        t.recordPos = pos;
        Recon.reconBInter(t, bs, b);
        if (lf) {
            var ytx = b.maxYtx;
            var uvtx = b.uvtx;
            if (hdr.segLossless[b.segId]) {
                ytx = 0;
                uvtx = 0;
            }
            var isGlobalmv = b.interMode == (b.compType != BlockDecoder.COMP_INTER_NONE
                    ? BlockDecoder.GLOBALMV_GLOBALMV : BlockDecoder.GLOBALMV);
            LoopFilterMasks.createLfMaskInter(t.lfMask, f.lfLevel, f.b4Stride, t.reconLflvl,
                    b.segId * 64 + (b.ref[0] + 1) * 2 + (isGlobalmv ? 0 : 1),
                    t.bx, t.by, f.w4, f.h4, b.skip, bs, ytx, b.txSplit0, b.txSplit1, uvtx, layout,
                    t.a.txLpfY, bx4, t.l.txLpfY, by4,
                    hasChroma ? t.a.txLpfUv : null, cbx4, hasChroma ? t.l.txLpfUv : null, cby4, t.lfTxa);
        }
        if (inter) {
            fill(t.a.filter, bx4, bw4, filter0);
            fill(t.a.filter, 32 + bx4, bw4, filter1);
            fill(t.l.filter, by4, bh4, filter0);
            fill(t.l.filter, 32 + by4, bh4, filter1);
            t.rt.splatEdges((t.by & 31) + 5, t.bx, bw4, bh4, b.mv[0], RefMvs.packInfo(b.ref[0] + 1, -1, bs, 0));
        }
        fill(t.a.intra, bx4, bw4, 0);
        fill(t.l.intra, by4, bh4, 0);
        if (hasChroma) {
            fill(t.a.uvmode, cbx4, cbw4, IpredDsp.DC_PRED);
            fill(t.l.uvmode, cby4, cbh4, IpredDsp.DC_PRED);
        }
    }

    /**
     * Raises the lowest sample row of a reference that a translational prediction reads
     * ({@code mc_lowest_px}).
     *
     * @param f      the frame
     * @param slot   the index of the entry in {@link FrameContext#lowestPx}
     * @param by4    the block row in 4-sample luma units
     * @param bh4    the block height in 4-sample luma units
     * @param mvy    the vertical motion vector component
     * @param ssVer  whether the plane is vertically subsampled
     * @param refIdx the reference
     */
    private static void mcLowest(FrameContext f, int slot, int by4, int bh4, int mvy, int ssVer, int refIdx) {
        var vMul = 4 >> ssVer;
        var scale = f.svcScale[refIdx * 2 + 1];
        int bottom;
        if (scale == 0) {
            var my = mvy >> (3 + ssVer);
            var dy = mvy & (15 >> (ssVer ^ 1));
            bottom = (by4 + bh4) * vMul + my + (dy != 0 ? 4 : 0);
        } else {
            var y = (by4 * vMul << 4) + mvy * (1 << (ssVer ^ 1));
            var tmp = (long) y * scale + (scale - 0x4000) * 8L;
            var r = (int) ((Math.abs(tmp) + 128) >> 8);
            y = (tmp < 0 ? -r : r) + 32;
            bottom = ((y + (bh4 * vMul - 1) * f.svcStep[refIdx * 2 + 1]) >> 10) + 1 + 4;
        }
        var lowest = f.lowestPx;
        if (bottom > lowest[slot]) {
            lowest[slot] = bottom;
        }
    }

    /**
     * Raises the lowest sample row of a reference that an affine warp of the block reads
     * ({@code affine_lowest_px}).
     *
     * @param t     the task context
     * @param slot  the index of the entry in {@link FrameContext#lowestPx}
     * @param bs    the block size
     * @param wmp   the warp model
     * @param ssVer whether the plane is vertically subsampled
     * @param ssHor whether the plane is horizontally subsampled
     */
    private static void affineLowest(TaskContext t, int slot, int bs, WarpedMotionParams wmp, int ssVer, int ssHor) {
        var hMul = 4 >> ssHor;
        var vMul = 4 >> ssVer;
        var mat = wmp.matrix;
        var bw = BLOCK_DIMENSIONS[bs * 4] * hMul;
        var y = BLOCK_DIMENSIONS[bs * 4 + 1] * vMul - 8;
        var srcY = t.by * 4 + ((y + 4) << ssVer);
        var mat5Y = (long) mat[5] * srcY + mat[1];
        var lowest = t.f.lowestPx;
        for (var x = 0; x < bw; x += Math.max(8, bw - 8)) {
            var srcX = t.bx * 4 + ((x + 4) << ssHor);
            var mvy = ((long) mat[4] * srcX + mat5Y) >> ssVer;
            var dy = (int) (mvy >> 16) - 4;
            if (dy + 4 + 8 > lowest[slot]) {
                lowest[slot] = dy + 4 + 8;
            }
        }
    }

    /**
     * Raises the lowest sample rows of the references that the overlapped prediction of a block
     * reads for its neighbors ({@code obmc_lowest_px}).
     *
     * @param t      the task context
     * @param base   the index of the tile superblock row's entries in {@link FrameContext#lowestPx}
     * @param chroma whether the chroma planes are predicted
     * @param bs     the block size
     * @param w4     the visible block width
     * @param h4     the visible block height
     */
    private static void obmcLowest(TaskContext t, int base, boolean chroma, int bs, int w4, int h4) {
        var f = t.f;
        var blocks = f.rf.r;
        var r = (t.by & 31) + 5;
        var ssVer = chroma && f.cur.layout == SequenceHeader.LAYOUT_I420 ? 1 : 0;
        var ssHor = chroma && f.cur.layout != SequenceHeader.LAYOUT_I444 ? 1 : 0;
        var hMul = 4 >> ssHor;
        var vMul = 4 >> ssVer;
        var plane = chroma ? 1 : 0;
        var bw4 = BLOCK_DIMENSIONS[bs * 4];
        var bh4 = BLOCK_DIMENSIONS[bs * 4 + 1];
        if (t.by > t.ts.rowStart && (!chroma || bw4 * hMul + bh4 * vMul >= 16)) {
            for (int i = 0, x = 0; x < w4 && i < Math.min(BLOCK_DIMENSIONS[bs * 4 + 2], 4); ) {
                var rec = (t.rt.r[r - 1] + t.bx + x + 1) * RefMvs.BLOCK_INTS;
                var info = blocks[rec + 2];
                var ref0 = RefMvs.ref0(info);
                if (ref0 > 0) {
                    var oh4 = Math.min(bh4, 16) >> 1;
                    mcLowest(f, base + (ref0 - 1) * 2 + plane, t.by, (oh4 * 3 + 3) >> 2, RefMvs.mvY(blocks[rec]),
                            ssVer, ref0 - 1);
                    i++;
                }
                x += Math.max(BLOCK_DIMENSIONS[RefMvs.bs(info) * 4], 2);
            }
        }
        if (t.bx > t.ts.colStart) {
            for (int i = 0, y = 0; y < h4 && i < Math.min(BLOCK_DIMENSIONS[bs * 4 + 3], 4); ) {
                var rec = (t.rt.r[r + y + 1] + t.bx - 1) * RefMvs.BLOCK_INTS;
                var info = blocks[rec + 2];
                var lh4 = BLOCK_DIMENSIONS[RefMvs.bs(info) * 4 + 1];
                var ref0 = RefMvs.ref0(info);
                if (ref0 > 0) {
                    var oh4 = Math.clamp(lh4, 2, bh4);
                    mcLowest(f, base + (ref0 - 1) * 2 + plane, t.by + y, oh4, RefMvs.mvY(blocks[rec]), ssVer,
                            ref0 - 1);
                    i++;
                }
                y += Math.max(lh4, 2);
            }
        }
    }

    /**
     * Raises the lowest sample rows of the references that the prediction of an inter block reads
     * (the reference tracking of dav1d's {@code decode_b} in its first pass); called once the
     * block's modes, motion and local warp are decoded.
     *
     * @param t         the task context, in {@link TaskContext#PASS_PARSE}
     * @param b         the block
     * @param bs        the block size
     * @param w4        the visible block width
     * @param h4        the visible block height
     * @param hasChroma whether the block has chroma
     */
    static void trackLowest(TaskContext t, Av1Block b, int bs, int w4, int h4, boolean hasChroma) {
        var f = t.f;
        var hdr = f.frameHdr;
        var base = (((t.by >> f.sbShift) * hdr.tilingCols) + t.ts.col) * 14;
        var bw4 = BLOCK_DIMENSIONS[bs * 4];
        var bh4 = BLOCK_DIMENSIONS[bs * 4 + 1];
        var layout = f.cur.layout;
        var ssVer = layout == SequenceHeader.LAYOUT_I420 ? 1 : 0;
        var ssHor = layout != SequenceHeader.LAYOUT_I444 ? 1 : 0;
        var cbw4 = (bw4 + ssHor) >> ssHor;
        var cbh4 = (bh4 + ssVer) >> ssVer;
        if (b.compType == BlockDecoder.COMP_INTER_NONE) {
            var ref = b.ref[0];
            var warp = (b.interMode == BlockDecoder.GLOBALMV && f.gmvWarpAllowed[ref])
                    || (b.motionMode == BlockDecoder.MM_WARP && t.warpmv.type > WarpedMotionParams.TRANSLATION);
            var wmp = b.motionMode == BlockDecoder.MM_WARP ? t.warpmv : hdr.gmv[ref];
            if (Math.min(bw4, bh4) > 1 && warp) {
                affineLowest(t, base + ref * 2, bs, wmp, 0, 0);
            } else {
                mcLowest(f, base + ref * 2, t.by, bh4, RefMvs.mvY(b.mv[0]), 0, ref);
                if (b.motionMode == BlockDecoder.MM_OBMC) {
                    obmcLowest(t, base, false, bs, w4, h4);
                }
            }
            if (!hasChroma) {
                return;
            }
            var isSub8x8 = bw4 == ssHor || bh4 == ssVer;
            var blocks = f.rf.r;
            var r = (t.by & 31) + 5;
            if (isSub8x8) {
                if (bw4 == 1) {
                    isSub8x8 &= RefMvs.ref0(blocks[(t.rt.r[r] + t.bx - 1) * RefMvs.BLOCK_INTS + 2]) > 0;
                }
                if (bh4 == ssVer) {
                    isSub8x8 &= RefMvs.ref0(blocks[(t.rt.r[r - 1] + t.bx) * RefMvs.BLOCK_INTS + 2]) > 0;
                }
                if (bw4 == 1 && bh4 == ssVer) {
                    isSub8x8 &= RefMvs.ref0(blocks[(t.rt.r[r - 1] + t.bx - 1) * RefMvs.BLOCK_INTS + 2]) > 0;
                }
            }
            if (isSub8x8) {
                if (bw4 == 1 && bh4 == ssVer) {
                    var rec = (t.rt.r[r - 1] + t.bx - 1) * RefMvs.BLOCK_INTS;
                    var ref0 = RefMvs.ref0(blocks[rec + 2]);
                    mcLowest(f, base + (ref0 - 1) * 2 + 1, t.by - 1, bh4, RefMvs.mvY(blocks[rec]), ssVer, ref0 - 1);
                }
                if (bw4 == 1) {
                    var rec = (t.rt.r[r] + t.bx - 1) * RefMvs.BLOCK_INTS;
                    var ref0 = RefMvs.ref0(blocks[rec + 2]);
                    mcLowest(f, base + (ref0 - 1) * 2 + 1, t.by, bh4, RefMvs.mvY(blocks[rec]), ssVer, ref0 - 1);
                }
                if (bh4 == ssVer) {
                    var rec = (t.rt.r[r - 1] + t.bx) * RefMvs.BLOCK_INTS;
                    var ref0 = RefMvs.ref0(blocks[rec + 2]);
                    mcLowest(f, base + (ref0 - 1) * 2 + 1, t.by - 1, bh4, RefMvs.mvY(blocks[rec]), ssVer, ref0 - 1);
                }
                mcLowest(f, base + ref * 2 + 1, t.by, bh4, RefMvs.mvY(b.mv[0]), ssVer, ref);
            } else if (Math.min(cbw4, cbh4) > 1 && warp) {
                if (layout == SequenceHeader.LAYOUT_I444) {
                    affineLowest(t, base + ref * 2 + 1, bs, wmp, 0, 0);
                } else {
                    affineLowest(t, base + ref * 2 + 1, bs, wmp, ssVer, 1);
                }
            } else {
                mcLowest(f, base + ref * 2 + 1, t.by & ~ssVer, bh4 << (bh4 == ssVer ? 1 : 0), RefMvs.mvY(b.mv[0]),
                        ssVer, ref);
                if (b.motionMode == BlockDecoder.MM_OBMC) {
                    obmcLowest(t, base, true, bs, w4, h4);
                }
            }
            return;
        }
        for (var i = 0; i < 2; i++) {
            var ref = b.ref[i];
            if (b.interMode == BlockDecoder.GLOBALMV_GLOBALMV && f.gmvWarpAllowed[ref]) {
                affineLowest(t, base + ref * 2, bs, hdr.gmv[ref], 0, 0);
            } else {
                mcLowest(f, base + ref * 2, t.by, bh4, RefMvs.mvY(b.mv[i]), 0, ref);
            }
        }
        if (!hasChroma) {
            return;
        }
        for (var i = 0; i < 2; i++) {
            var ref = b.ref[i];
            if (b.interMode == BlockDecoder.GLOBALMV_GLOBALMV && Math.min(cbw4, cbh4) > 1 && f.gmvWarpAllowed[ref]) {
                if (layout == SequenceHeader.LAYOUT_I444) {
                    affineLowest(t, base + ref * 2 + 1, bs, hdr.gmv[ref], 0, 0);
                } else {
                    affineLowest(t, base + ref * 2 + 1, bs, hdr.gmv[ref], ssVer, 1);
                }
            } else {
                mcLowest(f, base + ref * 2 + 1, t.by, bh4, RefMvs.mvY(b.mv[i]), ssVer, ref);
            }
        }
    }
}
