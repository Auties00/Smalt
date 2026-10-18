package com.github.auties00.smalt.video.av1.internal;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

import static com.github.auties00.smalt.video.av1.internal.Tables.BLOCK_DIMENSIONS;

/**
 * Decodes the partition trees and block mode information of a tile superblock row and drives the
 * reconstruction of every block (AV1 specification sections 5.11 and 6.10).
 *
 * <p>Methods return {@code false} when the bitstream is invalid; the caller then abandons the
 * frame.
 *
 * @implNote This implementation ports dav1d's {@code src/decode.c} ({@code decode_sb},
 * {@code decode_b} and their helpers, {@code read_restoration_info} and
 * {@code dav1d_decode_tile_sbrow}) for single-pass decoding, together with the context
 * derivations of {@code src/env.h}.
 */
final class BlockDecoder {
    /**
     * Writes two context bytes at once.
     */
    private static final VarHandle SHORTS = MethodHandles.byteArrayViewVarHandle(short[].class,
            ByteOrder.LITTLE_ENDIAN);

    /**
     * Writes four context bytes at once.
     */
    private static final VarHandle INTS = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);

    /**
     * Writes eight context bytes at once.
     */
    private static final VarHandle LONGS = MethodHandles.byteArrayViewVarHandle(long[].class,
            ByteOrder.LITTLE_ENDIAN);

    /**
     * The 128x128 block level.
     */
    static final int BL_128X128 = 0;

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
     * The partition splitting the top half.
     */
    static final int PARTITION_T_TOP_SPLIT = 4;

    /**
     * The partition splitting the bottom half.
     */
    static final int PARTITION_T_BOTTOM_SPLIT = 5;

    /**
     * The partition splitting the left half.
     */
    static final int PARTITION_T_LEFT_SPLIT = 6;

    /**
     * The partition splitting the right half.
     */
    static final int PARTITION_T_RIGHT_SPLIT = 7;

    /**
     * The four horizontal stripes partition.
     */
    static final int PARTITION_H4 = 8;

    /**
     * The four vertical stripes partition.
     */
    static final int PARTITION_V4 = 9;

    /**
     * The 128x128 block size.
     */
    static final int BS_128X128 = 0;

    /**
     * The 64x64 block size.
     */
    static final int BS_64X64 = 3;

    /**
     * The 4x4 block size.
     */
    static final int BS_4X4 = 21;

    /**
     * The nearest motion vector mode.
     */
    static final int NEARESTMV = 0;

    /**
     * The near motion vector mode.
     */
    static final int NEARMV = 1;

    /**
     * The global motion vector mode.
     */
    static final int GLOBALMV = 2;

    /**
     * The new motion vector mode.
     */
    static final int NEWMV = 3;

    /**
     * The compound global motion mode.
     */
    static final int GLOBALMV_GLOBALMV = 6;

    /**
     * The compound new motion mode.
     */
    static final int NEWMV_NEWMV = 7;

    /**
     * The compound nearest-nearest mode.
     */
    static final int NEARESTMV_NEARESTMV = 0;

    /**
     * Single prediction.
     */
    static final int COMP_INTER_NONE = 0;

    /**
     * Distance-weighted compound prediction.
     */
    static final int COMP_INTER_WEIGHTED_AVG = 1;

    /**
     * Averaged compound prediction.
     */
    static final int COMP_INTER_AVG = 2;

    /**
     * Difference-weighted compound prediction.
     */
    static final int COMP_INTER_SEG = 3;

    /**
     * Wedge compound prediction.
     */
    static final int COMP_INTER_WEDGE = 4;

    /**
     * No inter-intra prediction.
     */
    static final int INTER_INTRA_NONE = 0;

    /**
     * Smooth inter-intra blending.
     */
    static final int INTER_INTRA_BLEND = 1;

    /**
     * Wedge inter-intra blending.
     */
    static final int INTER_INTRA_WEDGE = 2;

    /**
     * Translational motion.
     */
    static final int MM_TRANSLATION = 0;

    /**
     * Overlapped block motion compensation.
     */
    static final int MM_OBMC = 1;

    /**
     * Local warped motion.
     */
    static final int MM_WARP = 2;

    /**
     * The block sizes that allow wedge prediction, as a bit mask.
     */
    static final int WEDGE_ALLOWED_MASK = (1 << 7) | (1 << 8) | (1 << 9) | (1 << 11) | (1 << 12) | (1 << 13)
            | (1 << 15) | (1 << 16) | (1 << 17);

    /**
     * The block sizes that allow inter-intra prediction, as a bit mask.
     */
    static final int INTERINTRA_ALLOWED_MASK = (1 << 7) | (1 << 8) | (1 << 11) | (1 << 12) | (1 << 13)
            | (1 << 16) | (1 << 17);

    /**
     * Prevents instantiation.
     */
    private BlockDecoder() {
        throw new AssertionError();
    }

    /**
     * Fills a run of context bytes.
     *
     * @implNote This implementation writes the power-of-two runs of up to 32 bytes that blocks fill
     * with one to four word stores, as dav1d's {@code dav1d_memset_pow2} does, instead of a
     * byte loop whose setup dominates such short runs. Runs of 8 bytes and more share one
     * loop of 8-byte stores, which keeps the method small enough to inline at the dozens of
     * call sites of each block without exhausting the compiler's inlining budget.
     *
     * @param arr the context
     * @param off the first index
     * @param n the number of bytes
     * @param v the value
     */
    static void fill(byte[] arr, int off, int n, int v) {
        var word = (v & 0xFFL) * 0x0101010101010101L;
        if (n >= 8 && (n & 7) == 0) {
            var i = 0;
            do {
                LONGS.set(arr, off + i, word);
                i += 8;
            } while (i < n);
        } else if (n == 4) {
            INTS.set(arr, off, (int) word);
        } else if (n == 2) {
            SHORTS.set(arr, off, (short) word);
        } else {
            for (var i = 0; i < n; i++) {
                arr[off + i] = (byte) v;
            }
        }
    }

    /**
     * Derives the dequantizers of every segment for a quantizer index ({@code init_quant_tables}).
     *
     * @param seq the sequence header
     * @param hdr the frame header
     * @param qidx the base quantizer index
     * @param dq the dequantizers to fill, as {@code [segment 8][plane 3][dc/ac 2]}
     */
    static void initQuantTables(SequenceHeader seq, FrameHeader hdr, int qidx, int[] dq) {
        var tbl = DequantTables.DQ;
        var base = seq.hbd * 512;
        var n = hdr.segmentationEnabled ? 8 : 1;
        for (var i = 0; i < n; i++) {
            var yac = hdr.segmentationEnabled ? Math.clamp(qidx + hdr.segDeltaQ[i], 0, 255) : qidx;
            var ydc = Math.clamp(yac + hdr.quantYdcDelta, 0, 255);
            var uac = Math.clamp(yac + hdr.quantUacDelta, 0, 255);
            var udc = Math.clamp(yac + hdr.quantUdcDelta, 0, 255);
            var vac = Math.clamp(yac + hdr.quantVacDelta, 0, 255);
            var vdc = Math.clamp(yac + hdr.quantVdcDelta, 0, 255);
            var o = i * 6;
            dq[o] = tbl[base + ydc * 2];
            dq[o + 1] = tbl[base + yac * 2 + 1];
            dq[o + 2] = tbl[base + udc * 2];
            dq[o + 3] = tbl[base + uac * 2 + 1];
            dq[o + 4] = tbl[base + vdc * 2];
            dq[o + 5] = tbl[base + vac * 2 + 1];
        }
    }

    /**
     * Decodes one motion vector component difference.
     *
     * @param msac the decoder
     * @param cdf the CDFs
     * @param comp the offset of the component's CDFs
     * @param mvPrec the precision: {@code -1} integer, 0 quarter sample, 1 eighth sample
     * @return the difference in 1/8 samples
     */
    private static int readMvComponentDiff(Msac msac, char[] cdf, int comp, int mvPrec) {
        var sign = msac.decodeBoolAdapt(cdf, comp + Cdf.MV_SIGN);
        var cl = msac.decodeSymbolAdapt(cdf, comp + Cdf.MV_CLASSES, 10);
        int up;
        var fp = 3;
        var hp = 1;
        if (cl == 0) {
            up = msac.decodeBoolAdapt(cdf, comp + Cdf.MV_CLASS0);
            if (mvPrec >= 0) {
                fp = msac.decodeSymbolAdapt(cdf, comp + Cdf.MV_CLASS0_FP + up * 4, 3);
                if (mvPrec > 0) {
                    hp = msac.decodeBoolAdapt(cdf, comp + Cdf.MV_CLASS0_HP);
                }
            }
        } else {
            up = 1 << cl;
            for (var n = 0; n < cl; n++) {
                up |= msac.decodeBoolAdapt(cdf, comp + Cdf.MV_CLASSN + n * 2) << n;
            }
            if (mvPrec >= 0) {
                fp = msac.decodeSymbolAdapt(cdf, comp + Cdf.MV_CLASSN_FP, 3);
                if (mvPrec > 0) {
                    hp = msac.decodeBoolAdapt(cdf, comp + Cdf.MV_CLASSN_HP);
                }
            }
        }
        var diff = ((up << 3) | (fp << 1) | hp) + 1;
        return sign != 0 ? -diff : diff;
    }

    /**
     * Decodes a motion vector difference and adds it to a predicted vector.
     *
     * @param ts the tile state
     * @param refMv the packed predicted vector
     * @param mvPrec the precision
     * @return the packed vector
     */
    private static int readMvResidual(TileState ts, int refMv, int mvPrec) {
        var msac = ts.msac;
        var cdf = ts.cdf;
        var joint = msac.decodeSymbolAdapt(cdf, Cdf.MV_JOINT, 3);
        var y = RefMvs.mvY(refMv);
        var x = RefMvs.mvX(refMv);
        if ((joint & 2) != 0) {
            y += readMvComponentDiff(msac, cdf, 0, mvPrec);
        }
        if ((joint & 1) != 0) {
            x += readMvComponentDiff(msac, cdf, Cdf.MV_COMP_STRIDE, mvPrec);
        }
        return RefMvs.mv(y, x);
    }

    /**
     * Decodes the variable transform split tree of one transform block ({@code read_tx_tree}).
     *
     * @param t the task context
     * @param from the transform size
     * @param depth the split depth
     * @param masks the split flags of each depth
     * @param xOff the horizontal position in transform units
     * @param yOff the vertical position in transform units
     */
    private static void readTxTree(TaskContext t, int from, int depth, int[] masks, int xOff, int yOff) {
        var f = t.f;
        var bx4 = t.bx & 31;
        var by4 = t.by & 31;
        var txw = Tables.TX_LW[from];
        var txh = Tables.TX_LH[from];
        boolean isSplit;
        if (depth < 2 && from > 0) {
            var cat = 2 * (4 - Tables.TX_MAX[from]) - depth;
            var a = t.a.tx[bx4] < txw ? 1 : 0;
            var l = t.l.tx[by4] < txh ? 1 : 0;
            isSplit = t.ts.msac.decodeBoolAdapt(t.ts.cdf, Cdf.TXPART + (cat * 3 + a + l) * 2) != 0;
            if (isSplit) {
                masks[depth] |= 1 << (yOff * 4 + xOff);
            }
        } else {
            isSplit = false;
        }
        if (isSplit && Tables.TX_MAX[from] > 1) {
            var sub = Tables.TX_SUB[from];
            var txsw = Tables.TX_W[sub];
            var txsh = Tables.TX_H[sub];
            readTxTree(t, sub, depth + 1, masks, xOff * 2, yOff * 2);
            t.bx += txsw;
            if (txw >= txh && t.bx < f.bw) {
                readTxTree(t, sub, depth + 1, masks, xOff * 2 + 1, yOff * 2);
            }
            t.bx -= txsw;
            t.by += txsh;
            if (txh >= txw && t.by < f.bh) {
                readTxTree(t, sub, depth + 1, masks, xOff * 2, yOff * 2 + 1);
                t.bx += txsw;
                if (txw >= txh && t.bx < f.bw) {
                    readTxTree(t, sub, depth + 1, masks, xOff * 2 + 1, yOff * 2 + 1);
                }
                t.bx -= txsw;
            }
            t.by -= txsh;
        } else {
            fill(t.a.tx, bx4, 1 << Tables.TX_LW[from], isSplit ? 0 : txw);
            fill(t.l.tx, by4, 1 << Tables.TX_LH[from], isSplit ? 0 : txh);
        }
    }

    /**
     * Maps a coded segment difference back to a segment identifier ({@code neg_deinterleave}).
     *
     * @param diff the coded difference
     * @param ref the predicted segment
     * @param max the number of segments
     * @return the segment identifier
     */
    private static int negDeinterleave(int diff, int ref, int max) {
        if (ref == 0) {
            return diff;
        }
        if (ref >= max - 1) {
            return max - diff - 1;
        }
        if (2 * ref < max) {
            if (diff <= 2 * ref) {
                return (diff & 1) != 0 ? ref + ((diff + 1) >> 1) : ref - (diff >> 1);
            }
            return diff;
        }
        if (diff <= 2 * (max - ref - 1)) {
            return (diff & 1) != 0 ? ref + ((diff + 1) >> 1) : ref - (diff >> 1);
        }
        return max - (diff + 1);
    }

    /**
     * Returns the block size stored in the block record at a ring row and column.
     *
     * @param t the task context
     * @param row the ring row
     * @param col the column in 4-sample units
     * @return the packed info int of the record
     */
    private static int info(TaskContext t, int row, int col) {
        return t.f.rf.r[(t.rt.r[row] + col) * RefMvs.BLOCK_INTS + 2];
    }

    /**
     * Returns the first motion vector stored in the block record at a ring row and column.
     *
     * @param t the task context
     * @param row the ring row
     * @param col the column in 4-sample units
     * @return the packed motion vector
     */
    private static int mv0(TaskContext t, int row, int col) {
        return t.f.rf.r[(t.rt.r[row] + col) * RefMvs.BLOCK_INTS];
    }

    /**
     * Returns whether a neighbor uses exactly one reference, the given one.
     *
     * @param info the neighbor's packed info
     * @param ref the reference
     * @return {@code true} when it matches
     */
    private static boolean matches(int info, int ref) {
        return RefMvs.ref0(info) == ref + 1 && RefMvs.ref1(info) == -1;
    }

    /**
     * Finds the neighbors predicting from the same single reference, for local warp
     * ({@code find_matching_ref}); the result is left in {@link TaskContext#warpMasks}.
     *
     * @param t the task context
     * @param intraEdgeFlags the block's edge flags
     * @param bw4 the block width
     * @param bh4 the block height
     * @param w4 the visible block width
     * @param h4 the visible block height
     * @param haveLeft whether the left neighbor is available
     * @param haveTop whether the top neighbor is available
     * @param ref the block's reference
     */
    private static void findMatchingRef(TaskContext t, int intraEdgeFlags, int bw4, int bh4, int w4, int h4,
                                        boolean haveLeft, boolean haveTop, int ref) {
        var masks = t.warpMasks;
        masks[0] = 0;
        masks[1] = 0;
        var r = (t.by & 31) + 5;
        var count = 0;
        var haveTopleft = haveTop && haveLeft;
        var haveTopright = Math.max(bw4, bh4) < 32 && haveTop && t.bx + bw4 < t.ts.colEnd
                && (intraEdgeFlags & IntraEdge.I444_TOP_HAS_RIGHT) != 0;
        if (haveTop) {
            var x2 = t.bx;
            var inf = info(t, r - 1, x2);
            if (matches(inf, ref)) {
                masks[0] |= 1;
                count = 1;
            }
            var aw4 = BLOCK_DIMENSIONS[RefMvs.bs(inf) * 4];
            if (aw4 >= bw4) {
                var off = t.bx & (aw4 - 1);
                if (off != 0) {
                    haveTopleft = false;
                }
                if (aw4 - off > bw4) {
                    haveTopright = false;
                }
            } else {
                var mask = 1L << aw4;
                for (var x = aw4; x < w4; x += aw4) {
                    x2 += aw4;
                    inf = info(t, r - 1, x2);
                    if (matches(inf, ref)) {
                        masks[0] |= mask;
                        if (++count >= 8) {
                            return;
                        }
                    }
                    aw4 = BLOCK_DIMENSIONS[RefMvs.bs(inf) * 4];
                    mask <<= aw4;
                }
            }
        }
        if (haveLeft) {
            var r2 = r;
            var inf = info(t, r2, t.bx - 1);
            if (matches(inf, ref)) {
                masks[1] |= 1;
                if (++count >= 8) {
                    return;
                }
            }
            var lh4 = BLOCK_DIMENSIONS[RefMvs.bs(inf) * 4 + 1];
            if (lh4 >= bh4) {
                if ((t.by & (lh4 - 1)) != 0) {
                    haveTopleft = false;
                }
            } else {
                var mask = 1L << lh4;
                for (var y = lh4; y < h4; y += lh4) {
                    r2 += lh4;
                    inf = info(t, r2, t.bx - 1);
                    if (matches(inf, ref)) {
                        masks[1] |= mask;
                        if (++count >= 8) {
                            return;
                        }
                    }
                    lh4 = BLOCK_DIMENSIONS[RefMvs.bs(inf) * 4 + 1];
                    mask <<= lh4;
                }
            }
        }
        if (haveTopleft && matches(info(t, r - 1, t.bx - 1), ref)) {
            masks[1] |= 1L << 32;
            if (++count >= 8) {
                return;
            }
        }
        if (haveTopright && matches(info(t, r - 1, t.bx + bw4), ref)) {
            masks[0] |= 1L << 32;
        }
    }

    /**
     * Adds one neighbor sample to the local warp fit.
     *
     * @param t the task context
     * @param np the number of samples so far
     * @param dx the neighbor column offset in 4-sample units
     * @param dy the neighbor row offset in 4-sample units
     * @param sx the horizontal direction of the neighbor's center
     * @param sy the vertical direction of the neighbor's center
     * @param row the neighbor's ring row
     * @param col the neighbor's column
     * @return the new number of samples
     */
    private static int addSample(TaskContext t, int np, int dx, int dy, int sx, int sy, int row, int col) {
        var inf = info(t, row, col);
        var bs = RefMvs.bs(inf);
        var mv = mv0(t, row, col);
        var pts = t.warpPts;
        var p0 = 16 * (2 * dx + sx * BLOCK_DIMENSIONS[bs * 4]) - 8;
        var p1 = 16 * (2 * dy + sy * BLOCK_DIMENSIONS[bs * 4 + 1]) - 8;
        pts[np * 4] = p0;
        pts[np * 4 + 1] = p1;
        pts[np * 4 + 2] = p0 + RefMvs.mvX(mv);
        pts[np * 4 + 3] = p1 + RefMvs.mvY(mv);
        return np + 1;
    }

    /**
     * Derives the local warp model of a block from its neighbors ({@code derive_warpmv}).
     *
     * @param t the task context
     * @param bw4 the block width
     * @param bh4 the block height
     * @param mv the block's packed motion vector
     * @param wmp the model to write
     */
    private static void deriveWarpmv(TaskContext t, int bw4, int bh4, int mv, WarpedMotionParams wmp) {
        var masks = t.warpMasks;
        var r = (t.by & 31) + 5;
        var np = 0;
        if ((int) masks[0] == 1 && (masks[1] >>> 32) == 0) {
            var off = t.bx & (BLOCK_DIMENSIONS[RefMvs.bs(info(t, r - 1, t.bx)) * 4] - 1);
            np = addSample(t, np, -off, 0, 1, -1, r - 1, t.bx);
        } else {
            var xmask = (int) masks[0];
            for (var off = 0; np < 8 && xmask != 0; ) {
                var tz = Integer.numberOfTrailingZeros(xmask);
                off += tz;
                xmask >>>= tz;
                np = addSample(t, np, off, 0, 1, -1, r - 1, t.bx + off);
                xmask &= ~1;
            }
        }
        if (np < 8 && masks[1] == 1) {
            var off = t.by & (BLOCK_DIMENSIONS[RefMvs.bs(info(t, r, t.bx - 1)) * 4 + 1] - 1);
            np = addSample(t, np, 0, -off, -1, 1, r - off, t.bx - 1);
        } else {
            var ymask = (int) masks[1];
            for (var off = 0; np < 8 && ymask != 0; ) {
                var tz = Integer.numberOfTrailingZeros(ymask);
                off += tz;
                ymask >>>= tz;
                np = addSample(t, np, 0, off, -1, 1, r + off, t.bx - 1);
                ymask &= ~1;
            }
        }
        if (np < 8 && (masks[1] >>> 32) != 0) {
            np = addSample(t, np, 0, 0, -1, -1, r - 1, t.bx - 1);
        }
        if (np < 8 && (masks[0] >>> 32) != 0) {
            np = addSample(t, np, bw4, 0, 1, -1, r - 1, t.bx + bw4);
        }
        var pts = t.warpPts;
        var mvd = t.warpMvd;
        var ret = 0;
        var thresh = 4 * Math.clamp(Math.max(bw4, bh4), 4, 28);
        var mvx = RefMvs.mvX(mv);
        var mvy = RefMvs.mvY(mv);
        for (var i = 0; i < np; i++) {
            mvd[i] = Math.abs(pts[i * 4 + 2] - pts[i * 4] - mvx) + Math.abs(pts[i * 4 + 3] - pts[i * 4 + 1] - mvy);
            if (mvd[i] > thresh) {
                mvd[i] = -1;
            } else {
                ret++;
            }
        }
        if (ret == 0) {
            ret = 1;
        } else {
            for (int i = 0, j = np - 1, k = 0; k < np - ret; k++, i++, j--) {
                while (mvd[i] != -1) {
                    i++;
                }
                while (mvd[j] == -1) {
                    j--;
                }
                if (i > j) {
                    break;
                }
                mvd[i] = mvd[j];
                System.arraycopy(pts, j * 4, pts, i * 4, 4);
            }
        }
        if (!WarpMv.findAffineInt(pts, ret, bw4, bh4, mvy, mvx, wmp, t.bx, t.by) && !WarpMv.getShearParams(wmp)) {
            wmp.type = WarpedMotionParams.AFFINE;
        } else {
            wmp.type = WarpedMotionParams.IDENTITY;
        }
    }

    /**
     * Returns the smallest segment identifier of the previous frame's map over a block
     * ({@code get_prev_frame_segid}).
     *
     * @param f the frame
     * @param by the block row
     * @param bx the block column
     * @param w4 the visible block width
     * @param h4 the visible block height
     * @return the segment identifier, or 8 when the map holds an invalid value
     */
    private static int prevFrameSegid(FrameContext f, int by, int bx, int w4, int h4) {
        var map = f.prevSegmap;
        var stride = f.b4Stride;
        var segId = 8;
        var p = by * stride + bx;
        do {
            for (var x = 0; x < w4; x++) {
                segId = Math.min(segId, map[p + x] & 0xFF);
            }
            p += stride;
        } while (--h4 > 0 && segId != 0);
        return segId;
    }

    /**
     * Predicts the segment identifier of a block from its decoded neighbors
     * ({@code get_cur_frame_segid}).
     *
     * @param t the task context
     * @param haveTop whether the top neighbor is available
     * @param haveLeft whether the left neighbor is available
     * @param segCtx receives the segment identifier context in {@code [0]}
     * @return the predicted segment identifier
     */
    private static int curFrameSegid(TaskContext t, boolean haveTop, boolean haveLeft, int[] segCtx) {
        var f = t.f;
        var map = f.curSegmap;
        var stride = f.b4Stride;
        var p = t.bx + t.by * stride;
        if (haveLeft && haveTop) {
            var l = map[p - 1];
            var a = map[p - stride];
            var al = map[p - stride - 1];
            if (l == a && al == l) {
                segCtx[0] = 2;
            } else if (l == a || al == l || a == al) {
                segCtx[0] = 1;
            } else {
                segCtx[0] = 0;
            }
            return a == al ? a : l;
        }
        segCtx[0] = 0;
        return haveLeft ? map[p - 1] : haveTop ? map[p - stride] : 0;
    }

    /**
     * Returns the intra flag context of a block ({@code get_intra_ctx}).
     *
     * @param a the above context
     * @param l the left context
     * @param yb4 the row within the left context
     * @param xb4 the column within the above context
     * @param haveTop whether the top neighbor is available
     * @param haveLeft whether the left neighbor is available
     * @return the context
     */
    private static int intraCtx(BlockContext a, BlockContext l, int yb4, int xb4, boolean haveTop, boolean haveLeft) {
        if (haveLeft) {
            if (haveTop) {
                var ctx = l.intra[yb4] + a.intra[xb4];
                return ctx + (ctx == 2 ? 1 : 0);
            }
            return l.intra[yb4] * 2;
        }
        return haveTop ? a.intra[xb4] * 2 : 0;
    }

    /**
     * Returns the transform size context of a block ({@code get_tx_ctx}).
     *
     * @param a the above context
     * @param l the left context
     * @param maxTx the largest transform size of the block
     * @param yb4 the row within the left context
     * @param xb4 the column within the above context
     * @return the context
     */
    private static int txCtx(BlockContext a, BlockContext l, int maxTx, int yb4, int xb4) {
        return (l.txIntra[yb4] >= Tables.TX_LH[maxTx] ? 1 : 0) + (a.txIntra[xb4] >= Tables.TX_LW[maxTx] ? 1 : 0);
    }

    /**
     * Returns the interpolation filter context ({@code get_filter_ctx}).
     *
     * @param a the above context
     * @param l the left context
     * @param comp whether the block is compound
     * @param dir the filter direction
     * @param ref the block's first reference
     * @param yb4 the row within the left context
     * @param xb4 the column within the above context
     * @return the context
     */
    private static int filterCtx(BlockContext a, BlockContext l, int comp, int dir, int ref, int yb4, int xb4) {
        var aFilter = (a.ref[xb4] == ref || a.ref[32 + xb4] == ref) ? a.filter[dir * 32 + xb4] : 3;
        var lFilter = (l.ref[yb4] == ref || l.ref[32 + yb4] == ref) ? l.filter[dir * 32 + yb4] : 3;
        if (aFilter == lFilter) {
            return comp * 4 + aFilter;
        } else if (aFilter == 3) {
            return comp * 4 + lFilter;
        } else if (lFilter == 3) {
            return comp * 4 + aFilter;
        }
        return comp * 4 + 3;
    }

    /**
     * Returns the compound flag context ({@code get_comp_ctx}).
     *
     * @param a the above context
     * @param l the left context
     * @param yb4 the row within the left context
     * @param xb4 the column within the above context
     * @param haveTop whether the top neighbor is available
     * @param haveLeft whether the left neighbor is available
     * @return the context
     */
    private static int compCtx(BlockContext a, BlockContext l, int yb4, int xb4, boolean haveTop, boolean haveLeft) {
        if (haveTop) {
            if (haveLeft) {
                if (a.compType[xb4] != 0) {
                    if (l.compType[yb4] != 0) {
                        return 4;
                    }
                    return 2 + (Integer.compareUnsigned(l.ref[yb4], 4) >= 0 ? 1 : 0);
                } else if (l.compType[yb4] != 0) {
                    return 2 + (Integer.compareUnsigned(a.ref[xb4], 4) >= 0 ? 1 : 0);
                }
                return (l.ref[yb4] >= 4 ? 1 : 0) ^ (a.ref[xb4] >= 4 ? 1 : 0);
            }
            return a.compType[xb4] != 0 ? 3 : (a.ref[xb4] >= 4 ? 1 : 0);
        } else if (haveLeft) {
            return l.compType[yb4] != 0 ? 3 : (l.ref[yb4] >= 4 ? 1 : 0);
        }
        return 1;
    }

    /**
     * Returns whether a neighbor's two references lie on the same side of the current frame.
     *
     * @param edge the neighbor context
     * @param off the position within the context
     * @return 1 for unidirectional compound, 0 otherwise
     */
    private static int hasUniComp(BlockContext edge, int off) {
        return (edge.ref[off] < 4) == (edge.ref[32 + off] < 4) ? 1 : 0;
    }

    /**
     * Returns the compound direction context ({@code get_comp_dir_ctx}).
     *
     * @param a the above context
     * @param l the left context
     * @param yb4 the row within the left context
     * @param xb4 the column within the above context
     * @param haveTop whether the top neighbor is available
     * @param haveLeft whether the left neighbor is available
     * @return the context
     */
    private static int compDirCtx(BlockContext a, BlockContext l, int yb4, int xb4, boolean haveTop, boolean haveLeft) {
        if (haveTop && haveLeft) {
            var aIntra = a.intra[xb4] != 0;
            var lIntra = l.intra[yb4] != 0;
            if (aIntra && lIntra) {
                return 2;
            }
            if (aIntra || lIntra) {
                var edge = aIntra ? l : a;
                var off = aIntra ? yb4 : xb4;
                if (edge.compType[off] == COMP_INTER_NONE) {
                    return 2;
                }
                return 1 + 2 * hasUniComp(edge, off);
            }
            var aComp = a.compType[xb4] != COMP_INTER_NONE;
            var lComp = l.compType[yb4] != COMP_INTER_NONE;
            var aRef0 = a.ref[xb4];
            var lRef0 = l.ref[yb4];
            if (!aComp && !lComp) {
                return 1 + 2 * ((aRef0 >= 4) == (lRef0 >= 4) ? 1 : 0);
            } else if (!aComp || !lComp) {
                var edge = aComp ? a : l;
                var off = aComp ? xb4 : yb4;
                if (hasUniComp(edge, off) == 0) {
                    return 1;
                }
                return 3 + ((aRef0 >= 4) == (lRef0 >= 4) ? 1 : 0);
            } else {
                var aUni = hasUniComp(a, xb4);
                var lUni = hasUniComp(l, yb4);
                if (aUni == 0 && lUni == 0) {
                    return 0;
                }
                if (aUni == 0 || lUni == 0) {
                    return 2;
                }
                return 3 + ((aRef0 == 4) == (lRef0 == 4) ? 1 : 0);
            }
        } else if (haveTop || haveLeft) {
            var edge = haveLeft ? l : a;
            var off = haveLeft ? yb4 : xb4;
            if (edge.intra[off] != 0) {
                return 2;
            }
            if (edge.compType[off] == COMP_INTER_NONE) {
                return 2;
            }
            return 4 * hasUniComp(edge, off);
        }
        return 2;
    }

    /**
     * Returns the distance-weighted compound context ({@code get_jnt_comp_ctx}).
     *
     * @param orderHintNBits the order hint length
     * @param poc the current order hint
     * @param ref0poc the first reference's order hint
     * @param ref1poc the second reference's order hint
     * @param a the above context
     * @param l the left context
     * @param yb4 the row within the left context
     * @param xb4 the column within the above context
     * @return the context
     */
    private static int jntCompCtx(int orderHintNBits, int poc, int ref0poc, int ref1poc, BlockContext a,
                                  BlockContext l, int yb4, int xb4) {
        var d0 = Math.abs(FrameHeader.pocDiff(orderHintNBits, ref0poc, poc));
        var d1 = Math.abs(FrameHeader.pocDiff(orderHintNBits, poc, ref1poc));
        var offset = d0 == d1 ? 1 : 0;
        var aCtx = a.compType[xb4] >= COMP_INTER_AVG || a.ref[xb4] == 6 ? 1 : 0;
        var lCtx = l.compType[yb4] >= COMP_INTER_AVG || l.ref[yb4] == 6 ? 1 : 0;
        return 3 * offset + aCtx + lCtx;
    }

    /**
     * Returns the masked compound context ({@code get_mask_comp_ctx}).
     *
     * @param a the above context
     * @param l the left context
     * @param yb4 the row within the left context
     * @param xb4 the column within the above context
     * @return the context
     */
    private static int maskCompCtx(BlockContext a, BlockContext l, int yb4, int xb4) {
        var aCtx = a.compType[xb4] >= COMP_INTER_SEG ? 1 : a.ref[xb4] == 6 ? 3 : 0;
        var lCtx = l.compType[yb4] >= COMP_INTER_SEG ? 1 : l.ref[yb4] == 6 ? 3 : 0;
        return Math.min(aCtx + lCtx, 5);
    }

    /**
     * Counts the neighbor references in a range, for the reference contexts.
     *
     * @param edge the neighbor context
     * @param off the position
     * @param lo the lowest reference counted
     * @param hi the highest reference counted
     * @param cnt the counters, indexed by reference minus {@code lo}
     */
    private static void countRefs(BlockContext edge, int off, int lo, int hi, int[] cnt) {
        int r0 = edge.ref[off];
        if (r0 >= lo && r0 <= hi) {
            cnt[r0 - lo]++;
        }
        if (edge.compType[off] != 0) {
            int r1 = edge.ref[32 + off];
            if (r1 >= lo && r1 <= hi) {
                cnt[r1 - lo]++;
            }
        }
    }

    /**
     * Returns a reference context comparing the counts of two reference groups.
     *
     * @param a the above context
     * @param l the left context
     * @param yb4 the row within the left context
     * @param xb4 the column within the above context
     * @param haveTop whether the top neighbor is available
     * @param haveLeft whether the left neighbor is available
     * @param lo0 the first reference of the first group
     * @param hi0 the last reference of the first group
     * @param lo1 the first reference of the second group
     * @param hi1 the last reference of the second group
     * @param cnt a scratch array of at least 7 counters
     * @return 1 when equal, 0 when the first group is rarer, 2 otherwise
     */
    private static int refCtx(BlockContext a, BlockContext l, int yb4, int xb4, boolean haveTop, boolean haveLeft,
                              int lo0, int hi0, int lo1, int hi1, int[] cnt) {
        for (var i = 0; i < 7; i++) {
            cnt[i] = 0;
        }
        if (haveTop && a.intra[xb4] == 0) {
            countRefs(a, xb4, 0, 6, cnt);
        }
        if (haveLeft && l.intra[yb4] == 0) {
            countRefs(l, yb4, 0, 6, cnt);
        }
        var c0 = 0;
        for (var i = lo0; i <= hi0; i++) {
            c0 += cnt[i];
        }
        var c1 = 0;
        for (var i = lo1; i <= hi1; i++) {
            c1 += cnt[i];
        }
        return c0 == c1 ? 1 : c0 < c1 ? 0 : 2;
    }

    /**
     * Returns the reference motion vector index context ({@code get_drl_context}).
     *
     * @param c the candidate list
     * @param refIdx the candidate index
     * @return the context
     */
    private static int drlContext(RefMvs.Candidates c, int refIdx) {
        if (c.weight[refIdx] >= 640) {
            return c.weight[refIdx + 1] < 640 ? 1 : 0;
        }
        return c.weight[refIdx + 1] < 640 ? 2 : 0;
    }

    /**
     * Returns whether an odd entry of a context run is zero ({@code findoddzero}).
     *
     * @param buf the context
     * @param off the first index
     * @param len the number of pairs
     * @return {@code true} when an entry at an even distance is zero
     */
    private static boolean findOddZero(byte[] buf, int off, int len) {
        for (var n = 0; n < len; n++) {
            if (buf[off + n * 2] == 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Writes one block record over the block's area of the motion ring.
     *
     * @param t the task context
     * @param bw4 the block width
     * @param bh4 the block height
     * @param mv0 the first motion vector
     * @param mv1 the second motion vector
     * @param info the packed references, size and flags
     */
    private static void splat(TaskContext t, int bw4, int bh4, int mv0, int mv1, int info) {
        t.rt.splat((t.by & 31) + 5, t.bx, bw4, bh4, mv0, mv1, info);
    }

    /**
     * Decodes the mode information of one block and reconstructs it ({@code decode_b}).
     *
     * @param t the task context, positioned at the block
     * @param bl the block level
     * @param bs the block size
     * @param bp the partition the block came from
     * @param intraEdgeFlags the block's intra edge flags
     * @return {@code false} when the bitstream is invalid
     */
    static boolean decodeB(TaskContext t, int bl, int bs, int bp, int intraEdgeFlags) {
        if (t.pass == TaskContext.PASS_RECON) {
            BlockRecords.reconstruct(t, bl, bs, bp, intraEdgeFlags);
            return true;
        }
        var ts = t.ts;
        var f = t.f;
        var hdr = f.frameHdr;
        var b = t.b;
        var bx4 = t.bx & 31;
        var by4 = t.by & 31;
        var bw4 = BLOCK_DIMENSIONS[bs * 4];
        var bh4 = BLOCK_DIMENSIONS[bs * 4 + 1];
        var w4 = Math.min(bw4, f.bw - t.bx);
        var h4 = Math.min(bh4, f.bh - t.by);
        var haveLeft = t.bx > ts.colStart;
        var haveTop = t.by > ts.rowStart;
        var msac = ts.msac;
        var cdf = ts.cdf;
        b.bl = bl;
        b.bp = bp;
        b.bs = bs;
        var seg = -1;
        var segPred = 0;
        var segCtx = t.angle;
        if (hdr.segmentationEnabled) {
            if (!hdr.segmentationUpdateMap) {
                if (f.prevSegmap != null) {
                    var segId = prevFrameSegid(f, t.by, t.bx, w4, h4);
                    if (segId >= 8) {
                        return false;
                    }
                    b.segId = segId;
                } else {
                    b.segId = 0;
                }
                seg = b.segId;
            } else if (hdr.segPreskip) {
                if (hdr.segmentationTemporal
                        && (segPred = msac.decodeBoolAdapt(cdf, Cdf.SEG_PRED + (t.a.segPred[bx4] + t.l.segPred[by4]) * 2)) != 0) {
                    if (f.prevSegmap != null) {
                        var segId = prevFrameSegid(f, t.by, t.bx, w4, h4);
                        if (segId >= 8) {
                            return false;
                        }
                        b.segId = segId;
                    } else {
                        b.segId = 0;
                    }
                } else {
                    var predSegId = curFrameSegid(t, haveTop, haveLeft, segCtx);
                    var diff = msac.decodeSymbolAdapt(cdf, Cdf.SEG_ID + segCtx[0] * 8, 7);
                    var lastActive = hdr.segLastActiveSegid;
                    b.segId = negDeinterleave(diff, predSegId, lastActive + 1);
                    if (b.segId > lastActive || b.segId >= 8 || b.segId < 0) {
                        b.segId = 0;
                    }
                }
                seg = b.segId;
            }
        } else {
            b.segId = 0;
        }
        if ((seg < 0 || (!hdr.segGlobalmv[seg] && hdr.segRef[seg] == -1 && !hdr.segSkip[seg]))
                && hdr.skipModeEnabled && Math.min(bw4, bh4) > 1) {
            var smctx = t.a.skipMode[bx4] + t.l.skipMode[by4];
            b.skipMode = msac.decodeBoolAdapt(cdf, Cdf.SKIP_MODE + smctx * 2) != 0;
        } else {
            b.skipMode = false;
        }
        if (b.skipMode || (seg >= 0 && hdr.segSkip[seg])) {
            b.skip = true;
        } else {
            var sctx = t.a.skip[bx4] + t.l.skip[by4];
            b.skip = msac.decodeBoolAdapt(cdf, Cdf.SKIP + sctx * 2) != 0;
        }
        if (hdr.segmentationEnabled && hdr.segmentationUpdateMap && !hdr.segPreskip) {
            if (!b.skip && hdr.segmentationTemporal
                    && (segPred = msac.decodeBoolAdapt(cdf, Cdf.SEG_PRED + (t.a.segPred[bx4] + t.l.segPred[by4]) * 2)) != 0) {
                if (f.prevSegmap != null) {
                    var segId = prevFrameSegid(f, t.by, t.bx, w4, h4);
                    if (segId >= 8) {
                        return false;
                    }
                    b.segId = segId;
                } else {
                    b.segId = 0;
                }
            } else {
                var predSegId = curFrameSegid(t, haveTop, haveLeft, segCtx);
                if (b.skip) {
                    b.segId = predSegId;
                } else {
                    var diff = msac.decodeSymbolAdapt(cdf, Cdf.SEG_ID + segCtx[0] * 8, 7);
                    var lastActive = hdr.segLastActiveSegid;
                    b.segId = negDeinterleave(diff, predSegId, lastActive + 1);
                    if (b.segId > lastActive) {
                        b.segId = 0;
                    }
                }
                if (b.segId >= 8 || b.segId < 0) {
                    b.segId = 0;
                }
            }
            seg = b.segId;
        }
        if (!b.skip) {
            var idx = f.seqHdr.sb128 ? ((t.bx & 16) >> 4) + ((t.by & 16) >> 3) : 0;
            var cdefIdx = t.lfMask.cdefIdx;
            var base = t.curSbCdefIdx;
            if (cdefIdx[base + idx] == -1) {
                var v = msac.decodeBools(hdr.cdefNBits);
                cdefIdx[base + idx] = (byte) v;
                if (bw4 > 16) {
                    cdefIdx[base + idx + 1] = (byte) v;
                }
                if (bh4 > 16) {
                    cdefIdx[base + idx + 2] = (byte) v;
                }
                if (bw4 == 32 && bh4 == 32) {
                    cdefIdx[base + idx + 3] = (byte) v;
                }
            }
        }
        if (((t.bx | t.by) & (31 >> (f.seqHdr.sb128 ? 0 : 1))) == 0) {
            decodeDeltaQLf(t, bs, b);
            t.recordDeltaLf = hdr.deltaLfPresent;
        }
        if (b.skipMode) {
            b.intra = false;
        } else if (hdr.isInterOrSwitch()) {
            if (seg >= 0 && (hdr.segRef[seg] >= 0 || hdr.segGlobalmv[seg])) {
                b.intra = hdr.segRef[seg] == 0;
            } else {
                var ictx = intraCtx(t.a, t.l, by4, bx4, haveTop, haveLeft);
                b.intra = msac.decodeBoolAdapt(cdf, Cdf.INTRA + ictx * 2) == 0;
            }
        } else if (hdr.allowIntrabc) {
            b.intra = msac.decodeBoolAdapt(cdf, Cdf.INTRABC) == 0;
        } else {
            b.intra = true;
        }
        boolean ok;
        if (b.intra) {
            decodeIntraBlock(t, bs, intraEdgeFlags, segPred);
            ok = true;
        } else if (hdr.isKeyOrIntra()) {
            ok = decodeIntrabcBlock(t, bs, intraEdgeFlags, segPred);
        } else {
            ok = InterModes.decodeInterBlock(t, bs, intraEdgeFlags, segPred, seg, haveTop, haveLeft);
        }
        if (!ok) {
            return false;
        }
        if (hdr.segmentationEnabled && hdr.segmentationUpdateMap) {
            var map = f.curSegmap;
            var p = t.by * f.b4Stride + t.bx;
            for (var y = 0; y < bh4; y++) {
                fill(map, p, bw4, b.segId);
                p += f.b4Stride;
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
        return true;
    }

    /**
     * Decodes the superblock quantizer and loop filter deltas and updates the tile's dequantizers and
     * loop filter levels.
     *
     * @param t the task context
     * @param bs the block size
     * @param b the block
     */
    private static void decodeDeltaQLf(TaskContext t, int bs, Av1Block b) {
        var ts = t.ts;
        var f = t.f;
        var hdr = f.frameHdr;
        var msac = ts.msac;
        var cdf = ts.cdf;
        var prevQidx = ts.lastQidx;
        var haveDeltaQ = hdr.deltaQPresent && (bs != (f.seqHdr.sb128 ? BS_128X128 : BS_64X64) || !b.skip);
        var d = ts.lastDeltaLf;
        var prev0 = d[0];
        var prev1 = d[1];
        var prev2 = d[2];
        var prev3 = d[3];
        if (haveDeltaQ) {
            var deltaQ = msac.decodeSymbolAdapt(cdf, Cdf.DELTA_Q, 3);
            if (deltaQ == 3) {
                var nBits = 1 + msac.decodeBools(3);
                deltaQ = msac.decodeBools(nBits) + 1 + (1 << nBits);
            }
            if (deltaQ != 0) {
                if (msac.decodeBoolEqui() != 0) {
                    deltaQ = -deltaQ;
                }
                deltaQ *= 1 << hdr.deltaQResLog2;
            }
            ts.lastQidx = Math.clamp(ts.lastQidx + deltaQ, 1, 255);
            if (hdr.deltaLfPresent) {
                var nLfs = hdr.deltaLfMulti ? (f.cur.layout != SequenceHeader.LAYOUT_I400 ? 4 : 2) : 1;
                for (var i = 0; i < nLfs; i++) {
                    var deltaLf = msac.decodeSymbolAdapt(cdf, Cdf.DELTA_LF + (i + (hdr.deltaLfMulti ? 1 : 0)) * 4, 3);
                    if (deltaLf == 3) {
                        var nBits = 1 + msac.decodeBools(3);
                        deltaLf = msac.decodeBools(nBits) + 1 + (1 << nBits);
                    }
                    if (deltaLf != 0) {
                        if (msac.decodeBoolEqui() != 0) {
                            deltaLf = -deltaLf;
                        }
                        deltaLf *= 1 << hdr.deltaLfResLog2;
                    }
                    d[i] = Math.clamp(d[i] + deltaLf, -63, 63);
                }
            }
        }
        if (ts.lastQidx == hdr.quantYac) {
            ts.dq = f.dq;
        } else if (ts.lastQidx != prevQidx) {
            initQuantTables(f.seqHdr, hdr, ts.lastQidx, ts.dqmem);
            ts.dq = ts.dqmem;
        }
        if (d[0] == 0 && d[1] == 0 && d[2] == 0 && d[3] == 0) {
            ts.lflvl = f.lvl;
        } else if (d[0] != prev0 || d[1] != prev1 || d[2] != prev2 || d[3] != prev3) {
            ts.lflvl = ts.lflvlmem;
            LfApply.calcLfValues(ts.lflvlmem, hdr, d);
        }
    }

    /**
     * Decodes an intra block's modes, reconstructs it and updates the contexts.
     *
     * @param t the task context
     * @param bs the block size
     * @param intraEdgeFlags the block's intra edge flags
     * @param segPred the temporal segment prediction flag
     */
    private static void decodeIntraBlock(TaskContext t, int bs, int intraEdgeFlags, int segPred) {
        var ts = t.ts;
        var f = t.f;
        var hdr = f.frameHdr;
        var b = t.b;
        var msac = ts.msac;
        var cdf = ts.cdf;
        var bx4 = t.bx & 31;
        var by4 = t.by & 31;
        var layout = f.cur.layout;
        var ssVer = layout == SequenceHeader.LAYOUT_I420 ? 1 : 0;
        var ssHor = layout != SequenceHeader.LAYOUT_I444 ? 1 : 0;
        var cbx4 = bx4 >> ssHor;
        var cby4 = by4 >> ssVer;
        var bw4 = BLOCK_DIMENSIONS[bs * 4];
        var bh4 = BLOCK_DIMENSIONS[bs * 4 + 1];
        var bwl = BLOCK_DIMENSIONS[bs * 4 + 2];
        var bhl = BLOCK_DIMENSIONS[bs * 4 + 3];
        var w4 = Math.min(bw4, f.bw - t.bx);
        var h4 = Math.min(bh4, f.bh - t.by);
        var cbw4 = (bw4 + ssHor) >> ssHor;
        var cbh4 = (bh4 + ssVer) >> ssVer;
        var cw4 = (w4 + ssHor) >> ssHor;
        var ch4 = (h4 + ssVer) >> ssVer;
        var hasChroma = layout != SequenceHeader.LAYOUT_I400 && (bw4 > ssHor || (t.bx & 1) != 0)
                && (bh4 > ssVer || (t.by & 1) != 0);
        var ymodeCdf = hdr.isInterOrSwitch()
                ? Cdf.Y_MODE + Tables.YMODE_SIZE_CONTEXT[bs] * 16
                : Cdf.KFYM + (Tables.INTRA_MODE_CONTEXT[t.a.mode[bx4]] * 5 + Tables.INTRA_MODE_CONTEXT[t.l.mode[by4]]) * 16;
        b.yMode = msac.decodeSymbolAdapt(cdf, ymodeCdf, 12);
        if (bwl + bhl >= 2 && b.yMode >= IpredDsp.VERT_PRED && b.yMode <= IpredDsp.VERT_LEFT_PRED) {
            b.yAngle = msac.decodeSymbolAdapt(cdf, Cdf.ANGLE_DELTA + (b.yMode - IpredDsp.VERT_PRED) * 8, 6) - 3;
        } else {
            b.yAngle = 0;
        }
        if (hasChroma) {
            var cfl = Tables.cflAllowed(bs, hdr.segLossless[b.segId], cbw4, cbh4) ? 1 : 0;
            b.uvMode = msac.decodeSymbolAdapt(cdf, Cdf.UV_MODE + (cfl * 13 + b.yMode) * 16, 13 - (cfl ^ 1));
            b.uvAngle = 0;
            if (b.uvMode == IpredDsp.CFL_PRED) {
                var sign = msac.decodeSymbolAdapt(cdf, Cdf.CFL_SIGN, 7) + 1;
                var signU = sign * 0x56 >> 8;
                var signV = sign - signU * 3;
                if (signU != 0) {
                    var ctx = (signU == 2 ? 3 : 0) + signV;
                    b.cflAlpha[0] = msac.decodeSymbolAdapt(cdf, Cdf.CFL_ALPHA + ctx * 16, 15) + 1;
                    if (signU == 1) {
                        b.cflAlpha[0] = -b.cflAlpha[0];
                    }
                } else {
                    b.cflAlpha[0] = 0;
                }
                if (signV != 0) {
                    var ctx = (signV == 2 ? 3 : 0) + signU;
                    b.cflAlpha[1] = msac.decodeSymbolAdapt(cdf, Cdf.CFL_ALPHA + ctx * 16, 15) + 1;
                    if (signV == 1) {
                        b.cflAlpha[1] = -b.cflAlpha[1];
                    }
                } else {
                    b.cflAlpha[1] = 0;
                }
            } else if (bwl + bhl >= 2 && b.uvMode >= IpredDsp.VERT_PRED && b.uvMode <= IpredDsp.VERT_LEFT_PRED) {
                b.uvAngle = msac.decodeSymbolAdapt(cdf, Cdf.ANGLE_DELTA + (b.uvMode - IpredDsp.VERT_PRED) * 8, 6) - 3;
            }
        }
        b.palSz[0] = 0;
        b.palSz[1] = 0;
        if (hdr.allowScreenContentTools && Math.max(bw4, bh4) <= 16 && bw4 + bh4 >= 4) {
            var szCtx = bwl + bhl - 2;
            if (b.yMode == IpredDsp.DC_PRED) {
                var palCtx = (t.a.palSz[bx4] > 0 ? 1 : 0) + (t.l.palSz[by4] > 0 ? 1 : 0);
                if (msac.decodeBoolAdapt(cdf, Cdf.PAL_Y + (szCtx * 3 + palCtx) * 2) != 0) {
                    Palette.readPalPlane(t, b, 0, szCtx, bx4, by4);
                }
            }
            if (hasChroma && b.uvMode == IpredDsp.DC_PRED) {
                var palCtx = b.palSz[0] > 0 ? 1 : 0;
                if (msac.decodeBoolAdapt(cdf, Cdf.PAL_UV + palCtx * 2) != 0) {
                    Palette.readPalUv(t, b, szCtx, bx4, by4);
                }
            }
        }
        if (b.yMode == IpredDsp.DC_PRED && b.palSz[0] == 0 && Math.max(bwl, bhl) <= 3 && f.seqHdr.filterIntra) {
            if (msac.decodeBoolAdapt(cdf, Cdf.USE_FILTER_INTRA + bs * 2) != 0) {
                b.yMode = IpredDsp.FILTER_PRED;
                b.yAngle = msac.decodeSymbolAdapt(cdf, Cdf.FILTER_INTRA, 4);
            }
        }
        if (b.palSz[0] != 0) {
            Palette.readPalIndices(t, t.palIdxY, b.palSz[0], 0, w4, h4, bw4, bh4);
        }
        if (hasChroma && b.palSz[1] != 0) {
            Palette.readPalIndices(t, t.palIdxUv, b.palSz[1], 1, cw4, ch4, cbw4, cbh4);
        }
        int tx;
        if (hdr.segLossless[b.segId]) {
            b.tx = b.uvtx = 0;
            tx = 0;
        } else {
            b.tx = Tables.MAX_TXFM_SIZE_FOR_BS[bs * 4];
            b.uvtx = Tables.MAX_TXFM_SIZE_FOR_BS[bs * 4 + layout];
            tx = b.tx;
            if (hdr.txfmMode == FrameHeader.TX_SWITCHABLE && Tables.TX_MAX[tx] > 0) {
                var tctx = txCtx(t.a, t.l, tx, by4, bx4);
                var depth = msac.decodeSymbolAdapt(cdf, Cdf.TXSZ + ((Tables.TX_MAX[tx] - 1) * 3 + tctx) * 4,
                        Math.min(Tables.TX_MAX[tx], 2));
                while (depth-- > 0) {
                    b.tx = Tables.TX_SUB[tx];
                    tx = b.tx;
                }
            }
        }
        if (t.pass == TaskContext.PASS_PARSE) {
            BlockRecords.writeIntra(t, b, hasChroma);
        }
        Recon.reconBIntra(t, bs, intraEdgeFlags, b);
        if ((hdr.loopfilterLevelY[0] != 0 || hdr.loopfilterLevelY[1] != 0) && t.pass != TaskContext.PASS_PARSE) {
            LoopFilterMasks.createLfMaskIntra(t.lfMask, f.lfLevel, f.b4Stride, ts.lflvl, b.segId * 64,
                    t.bx, t.by, f.w4, f.h4, bs, b.tx, b.uvtx, layout,
                    t.a.txLpfY, bx4, t.l.txLpfY, by4,
                    hasChroma ? t.a.txLpfUv : null, cbx4, hasChroma ? t.l.txLpfUv : null, cby4);
        }
        var yModeNofilt = b.yMode == IpredDsp.FILTER_PRED ? IpredDsp.DC_PRED : b.yMode;
        var inter = hdr.isInterOrSwitch();
        var palUv = hasChroma ? b.palSz[1] : 0;
        updateIntraCtx(t, t.a, bx4, bw4, Tables.TX_LW[tx], yModeNofilt, b, segPred, palUv, 0, inter);
        updateIntraCtx(t, t.l, by4, bh4, Tables.TX_LH[tx], yModeNofilt, b, segPred, palUv, 1, inter);
        if (b.palSz[0] != 0) {
            Palette.copyPalBlockY(t, bx4, by4, bw4, bh4);
        }
        if (hasChroma) {
            fill(t.a.uvmode, cbx4, cbw4, b.uvMode);
            fill(t.l.uvmode, cby4, cbh4, b.uvMode);
            if (b.palSz[1] != 0) {
                Palette.copyPalBlockUv(t, bx4, by4, bw4, bh4);
            }
        }
        if (inter || hdr.allowIntrabc) {
            splat(t, bw4, bh4, RefMvs.INVALID_MV, 0, RefMvs.packInfo(0, -1, bs, 0));
        }
    }

    /**
     * Updates one edge of the contexts after an intra block.
     *
     * @param t the task context
     * @param edge the above or left context
     * @param off the position within the context
     * @param n the number of entries
     * @param txLsz the base-2 logarithm of the transform size along the edge
     * @param yModeNofilt the luma mode with filter intra mapped to DC
     * @param b the block
     * @param segPred the temporal segment prediction flag
     * @param palUv the chroma palette size to record
     * @param side 0 for the above edge, 1 for the left edge
     * @param inter whether the frame is an inter frame
     */
    private static void updateIntraCtx(TaskContext t, BlockContext edge, int off, int n, int txLsz, int yModeNofilt,
                                       Av1Block b, int segPred, int palUv, int side, boolean inter) {
        fill(edge.txIntra, off, n, txLsz);
        fill(edge.tx, off, n, txLsz);
        fill(edge.mode, off, n, yModeNofilt);
        fill(edge.palSz, off, n, b.palSz[0]);
        fill(edge.segPred, off, n, segPred);
        fill(edge.skipMode, off, n, 0);
        fill(edge.intra, off, n, 1);
        fill(edge.skip, off, n, b.skip ? 1 : 0);
        fill(t.palSzUv, side * 32 + off, n, palUv);
        if (inter) {
            fill(edge.compType, off, n, COMP_INTER_NONE);
            fill(edge.ref, off, n, -1);
            fill(edge.ref, 32 + off, n, -1);
            fill(edge.filter, off, n, 3);
            fill(edge.filter, 32 + off, n, 3);
        }
    }

    /**
     * Decodes an intra block copy block, reconstructs it and updates the contexts.
     *
     * @param t the task context
     * @param bs the block size
     * @param intraEdgeFlags the block's intra edge flags
     * @param segPred the temporal segment prediction flag
     * @return {@code false} when the motion vector is invalid
     */
    private static boolean decodeIntrabcBlock(TaskContext t, int bs, int intraEdgeFlags, int segPred) {
        var ts = t.ts;
        var f = t.f;
        var b = t.b;
        var layout = f.cur.layout;
        var ssVer = layout == SequenceHeader.LAYOUT_I420 ? 1 : 0;
        var ssHor = layout != SequenceHeader.LAYOUT_I444 ? 1 : 0;
        var bx4 = t.bx & 31;
        var by4 = t.by & 31;
        var cbx4 = bx4 >> ssHor;
        var cby4 = by4 >> ssVer;
        var bw4 = BLOCK_DIMENSIONS[bs * 4];
        var bh4 = BLOCK_DIMENSIONS[bs * 4 + 1];
        var cbw4 = (bw4 + ssHor) >> ssHor;
        var cbh4 = (bh4 + ssVer) >> ssVer;
        var hasChroma = layout != SequenceHeader.LAYOUT_I400 && (bw4 > ssHor || (t.bx & 1) != 0)
                && (bh4 > ssVer || (t.by & 1) != 0);
        var sb128 = f.seqHdr.sb128 ? 1 : 0;
        var c = t.cand;
        RefMvs.find(t.rt, c, 0, -1, bs, intraEdgeFlags, t.by, t.bx);
        if (c.mv0[0] != 0) {
            b.mv[0] = c.mv0[0];
        } else if (c.mv0[1] != 0) {
            b.mv[0] = c.mv0[1];
        } else if (t.by - (16 << sb128) < ts.rowStart) {
            b.mv[0] = RefMvs.mv(0, -(512 << sb128) - 2048);
        } else {
            b.mv[0] = RefMvs.mv(-(512 << sb128), 0);
        }
        b.mv[0] = readMvResidual(ts, b.mv[0], -1);
        var borderLeft = ts.colStart * 4;
        var borderTop = ts.rowStart * 4;
        if (hasChroma) {
            if (bw4 < 2 && ssHor != 0) {
                borderLeft += 4;
            }
            if (bh4 < 2 && ssVer != 0) {
                borderTop += 4;
            }
        }
        var srcLeft = t.bx * 4 + (RefMvs.mvX(b.mv[0]) >> 3);
        var srcTop = t.by * 4 + (RefMvs.mvY(b.mv[0]) >> 3);
        var srcRight = srcLeft + bw4 * 4;
        var srcBottom = srcTop + bh4 * 4;
        var borderRight = ((ts.colEnd + (bw4 - 1)) & ~(bw4 - 1)) * 4;
        if (srcLeft < borderLeft) {
            srcRight += borderLeft - srcLeft;
            srcLeft += borderLeft - srcLeft;
        } else if (srcRight > borderRight) {
            srcLeft -= srcRight - borderRight;
            srcRight -= srcRight - borderRight;
        }
        if (srcTop < borderTop) {
            srcBottom += borderTop - srcTop;
            srcTop += borderTop - srcTop;
        }
        var sbx = (t.bx >> (4 + sb128)) << (6 + sb128);
        var sby = (t.by >> (4 + sb128)) << (6 + sb128);
        var sbSize = 1 << (6 + sb128);
        if (srcBottom > sby && srcRight > sbx) {
            if (srcTop - borderTop >= srcBottom - sby) {
                srcTop -= srcBottom - sby;
                srcBottom -= srcBottom - sby;
            } else if (srcLeft - borderLeft >= srcRight - sbx) {
                srcLeft -= srcRight - sbx;
                srcRight -= srcRight - sbx;
            }
        }
        if (srcBottom > sby + sbSize) {
            srcTop -= srcBottom - (sby + sbSize);
            srcBottom -= srcBottom - (sby + sbSize);
        }
        if (srcBottom > sby && srcRight > sbx) {
            return false;
        }
        b.mv[0] = RefMvs.mv((srcTop - t.by * 4) * 8, (srcLeft - t.bx * 4) * 8);
        readVartxTree(t, b, bs, bx4, by4);
        if (t.pass == TaskContext.PASS_PARSE) {
            BlockRecords.writeInter(t, b, 0, 0, false);
        }
        if (!Recon.reconBInter(t, bs, b)) {
            return false;
        }
        splat(t, bw4, bh4, b.mv[0], 0, RefMvs.packInfo(0, -1, bs, 0));
        updateIntrabcCtx(t, t.a, bx4, bw4, BLOCK_DIMENSIONS[bs * 4 + 2], b, segPred, 0);
        updateIntrabcCtx(t, t.l, by4, bh4, BLOCK_DIMENSIONS[bs * 4 + 3], b, segPred, 1);
        if (hasChroma) {
            fill(t.a.uvmode, cbx4, cbw4, IpredDsp.DC_PRED);
            fill(t.l.uvmode, cby4, cbh4, IpredDsp.DC_PRED);
        }
        return true;
    }

    /**
     * Updates one edge of the contexts after an intra block copy block.
     *
     * @param t the task context
     * @param edge the above or left context
     * @param off the position within the context
     * @param n the number of entries
     * @param lsz the base-2 logarithm of the block size along the edge
     * @param b the block
     * @param segPred the temporal segment prediction flag
     * @param side 0 for the above edge, 1 for the left edge
     */
    private static void updateIntrabcCtx(TaskContext t, BlockContext edge, int off, int n, int lsz, Av1Block b,
                                         int segPred, int side) {
        fill(edge.txIntra, off, n, lsz);
        fill(edge.mode, off, n, IpredDsp.DC_PRED);
        fill(edge.palSz, off, n, 0);
        fill(t.palSzUv, side * 32 + off, n, 0);
        fill(edge.segPred, off, n, segPred);
        fill(edge.skipMode, off, n, 0);
        fill(edge.intra, off, n, 0);
        fill(edge.skip, off, n, b.skip ? 1 : 0);
    }

    /**
     * Decodes the variable transform tree of an inter block ({@code read_vartx_tree}).
     *
     * @param t the task context
     * @param b the block
     * @param bs the block size
     * @param bx4 the column within the above context
     * @param by4 the row within the left context
     */
    static void readVartxTree(TaskContext t, Av1Block b, int bs, int bx4, int by4) {
        var f = t.f;
        var hdr = f.frameHdr;
        var bw4 = BLOCK_DIMENSIONS[bs * 4];
        var bh4 = BLOCK_DIMENSIONS[bs * 4 + 1];
        var bwl = BLOCK_DIMENSIONS[bs * 4 + 2];
        var bhl = BLOCK_DIMENSIONS[bs * 4 + 3];
        var txSplit = t.warpMvd;
        txSplit[0] = 0;
        txSplit[1] = 0;
        b.maxYtx = Tables.MAX_TXFM_SIZE_FOR_BS[bs * 4];
        if (!b.skip && (hdr.segLossless[b.segId] || b.maxYtx == 0)) {
            b.maxYtx = b.uvtx = 0;
            if (hdr.txfmMode == FrameHeader.TX_SWITCHABLE) {
                fill(t.a.tx, bx4, 1 << bwl, 0);
                fill(t.l.tx, by4, 1 << bhl, 0);
            }
        } else if (hdr.txfmMode != FrameHeader.TX_SWITCHABLE || b.skip) {
            if (hdr.txfmMode == FrameHeader.TX_SWITCHABLE) {
                fill(t.a.tx, bx4, 1 << bwl, bwl);
                fill(t.l.tx, by4, 1 << bhl, bhl);
            }
            b.uvtx = Tables.MAX_TXFM_SIZE_FOR_BS[bs * 4 + f.cur.layout];
        } else {
            var ytx = b.maxYtx;
            var tw = Tables.TX_W[ytx];
            var th = Tables.TX_H[ytx];
            int y;
            int x = 0;
            int yOff;
            int xOff;
            for (y = 0, yOff = 0; y < bh4; y += th, yOff++) {
                for (x = 0, xOff = 0; x < bw4; x += tw, xOff++) {
                    readTxTree(t, ytx, 0, txSplit, xOff, yOff);
                    t.bx += tw;
                }
                t.bx -= x;
                t.by += th;
            }
            t.by -= y;
            b.uvtx = Tables.MAX_TXFM_SIZE_FOR_BS[bs * 4 + f.cur.layout];
        }
        b.txSplit0 = txSplit[0] & 0xFF;
        b.txSplit1 = txSplit[1] & 0xFFFF;
    }

    /**
     * Returns the motion vector reading helper for inter mode decoding.
     *
     * @param ts the tile state
     * @param refMv the packed predicted vector
     * @param mvPrec the precision
     * @return the packed vector
     */
    static int readMv(TileState ts, int refMv, int mvPrec) {
        return readMvResidual(ts, refMv, mvPrec);
    }

    /**
     * Returns the compound flag context for inter mode decoding.
     *
     * @param t the task context
     * @param by4 the row within the left context
     * @param bx4 the column within the above context
     * @param haveTop whether the top neighbor is available
     * @param haveLeft whether the left neighbor is available
     * @return the context
     */
    static int compContext(TaskContext t, int by4, int bx4, boolean haveTop, boolean haveLeft) {
        return compCtx(t.a, t.l, by4, bx4, haveTop, haveLeft);
    }

    /**
     * Returns the compound direction context for inter mode decoding.
     *
     * @param t the task context
     * @param by4 the row within the left context
     * @param bx4 the column within the above context
     * @param haveTop whether the top neighbor is available
     * @param haveLeft whether the left neighbor is available
     * @return the context
     */
    static int compDirContext(TaskContext t, int by4, int bx4, boolean haveTop, boolean haveLeft) {
        return compDirCtx(t.a, t.l, by4, bx4, haveTop, haveLeft);
    }

    /**
     * Returns a reference context comparing two reference groups for inter mode decoding.
     *
     * @param t the task context
     * @param by4 the row within the left context
     * @param bx4 the column within the above context
     * @param haveTop whether the top neighbor is available
     * @param haveLeft whether the left neighbor is available
     * @param lo0 the first reference of the first group
     * @param hi0 the last reference of the first group
     * @param lo1 the first reference of the second group
     * @param hi1 the last reference of the second group
     * @return the context
     */
    static int refContext(TaskContext t, int by4, int bx4, boolean haveTop, boolean haveLeft, int lo0, int hi0,
                          int lo1, int hi1) {
        return refCtx(t.a, t.l, by4, bx4, haveTop, haveLeft, lo0, hi0, lo1, hi1, t.warpMvd);
    }

    /**
     * Returns the reference motion vector index context for inter mode decoding.
     *
     * @param c the candidate list
     * @param refIdx the candidate index
     * @return the context
     */
    static int drlCtx(RefMvs.Candidates c, int refIdx) {
        return drlContext(c, refIdx);
    }

    /**
     * Returns the distance-weighted compound context for inter mode decoding.
     *
     * @param t the task context
     * @param by4 the row within the left context
     * @param bx4 the column within the above context
     * @return the context
     */
    static int jntCompContext(TaskContext t, int by4, int bx4) {
        var f = t.f;
        var b = t.b;
        return jntCompCtx(f.seqHdr.orderHintNBits, f.frameHdr.frameOffset, f.refp[b.ref[0]].frameHdr.frameOffset,
                f.refp[b.ref[1]].frameHdr.frameOffset, t.a, t.l, by4, bx4);
    }

    /**
     * Returns the masked compound context for inter mode decoding.
     *
     * @param t the task context
     * @param by4 the row within the left context
     * @param bx4 the column within the above context
     * @return the context
     */
    static int maskCompContext(TaskContext t, int by4, int bx4) {
        return maskCompCtx(t.a, t.l, by4, bx4);
    }

    /**
     * Returns the interpolation filter context for inter mode decoding.
     *
     * @param t the task context
     * @param comp whether the block is compound
     * @param dir the filter direction
     * @param ref the block's first reference
     * @param by4 the row within the left context
     * @param bx4 the column within the above context
     * @return the context
     */
    static int filterContext(TaskContext t, int comp, int dir, int ref, int by4, int bx4) {
        return filterCtx(t.a, t.l, comp, dir, ref, by4, bx4);
    }

    /**
     * Returns whether a block has an overlappable neighbor, for overlapped block motion
     * compensation.
     *
     * @param t the task context
     * @param by4 the row within the left context
     * @param bx4 the column within the above context
     * @param w4 the visible block width
     * @param h4 the visible block height
     * @param haveTop whether the top neighbor is available
     * @param haveLeft whether the left neighbor is available
     * @return {@code true} when an inter neighbor can be overlapped
     */
    static boolean hasOverlappable(TaskContext t, int by4, int bx4, int w4, int h4, boolean haveTop,
                                   boolean haveLeft) {
        return (haveLeft && findOddZero(t.l.intra, by4 + 1, h4 >> 1))
                || (haveTop && findOddZero(t.a.intra, bx4 + 1, w4 >> 1));
    }

    /**
     * Finds the neighbors sharing a block's reference for local warp.
     *
     * @param t the task context
     * @param intraEdgeFlags the block's edge flags
     * @param bw4 the block width
     * @param bh4 the block height
     * @param w4 the visible block width
     * @param h4 the visible block height
     * @param haveLeft whether the left neighbor is available
     * @param haveTop whether the top neighbor is available
     * @param ref the block's reference
     */
    static void matchingRefs(TaskContext t, int intraEdgeFlags, int bw4, int bh4, int w4, int h4, boolean haveLeft,
                             boolean haveTop, int ref) {
        findMatchingRef(t, intraEdgeFlags, bw4, bh4, w4, h4, haveLeft, haveTop, ref);
    }

    /**
     * Derives the local warp model of a block.
     *
     * @param t the task context
     * @param bw4 the block width
     * @param bh4 the block height
     * @param mv the block's packed motion vector
     */
    static void warpmv(TaskContext t, int bw4, int bh4, int mv) {
        deriveWarpmv(t, bw4, bh4, mv, t.warpmv);
    }

    /**
     * Writes a block's motion record over its area.
     *
     * @param t the task context
     * @param bw4 the block width
     * @param bh4 the block height
     * @param mv0 the first motion vector
     * @param mv1 the second motion vector
     * @param info the packed references, size and flags
     */
    static void splatMv(TaskContext t, int bw4, int bh4, int mv0, int mv1, int info) {
        splat(t, bw4, bh4, mv0, mv1, info);
    }
}
