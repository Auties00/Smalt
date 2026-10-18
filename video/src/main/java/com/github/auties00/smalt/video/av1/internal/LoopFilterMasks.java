package com.github.auties00.smalt.video.av1.internal;

import java.util.Arrays;

/**
 * Builds the deblocking state of a frame: the edge masks of every coded block, the per-block
 * filter level cache, the filter limit table and the per-segment filter levels (AV1
 * specification sections 7.14.2 to 7.14.4).
 *
 * <p>The block decoder calls {@link #createLfMaskIntra} or {@link #createLfMaskInter} once per
 * coded block, in decoding order, with the {@link LfMask} of the block's 128x128 area. Each call
 * records the block's outer edges and inner transform edges in the masks at the filter width the
 * transform sizes on both sides allow, writes the block's filter levels into the level cache, and
 * updates the above and left transform-size contexts the next blocks compare against.
 *
 * <p>Layouts are the {@link SequenceHeader} {@code LAYOUT_} numbers. Block sizes and transform
 * sizes are dav1d's {@code BlockSize} (0 = 128x128 to 21 = 4x4) and {@code RectTxfmSize} (0 =
 * 4x4 to 18 = 64x16) numbers.
 *
 * @implNote This implementation ports dav1d's {@code src/lf_mask.c}
 * ({@code dav1d_create_lf_mask_intra}, {@code dav1d_create_lf_mask_inter},
 * {@code dav1d_calc_eih}, {@code dav1d_calc_lf_values} and their static helpers)
 * operation by operation, including the transform decomposition scratch layout.
 */
public final class LoopFilterMasks {
    /**
     * The minimum length of the scratch buffer {@link #createLfMaskInter} takes: dav1d's
     * {@code txa[2][2][32][32]} transform decomposition array.
     */
    public static final int TX_SCRATCH_LENGTH = 2 * 2 * 32 * 32;

    /**
     * The length of the per-segment filter level table {@link #calcLfValues} fills:
     * {@code [8 segments][4 directions][8 references][2 modes]}.
     */
    public static final int LF_VALUES_LENGTH = 8 * 4 * 8 * 2;

    /**
     * The width and height in 4x4 units of every block size, as {@code [bs * 2 + 0 = width,
     * bs * 2 + 1 = height]}; dav1d's {@code dav1d_block_dimensions} (first two columns).
     */
    private static final int[] BLOCK_DIMENSIONS = {
            32, 32, 32, 16, 16, 32, 16, 16, 16, 8, 16, 4, 8, 16, 8, 8, 8, 4, 8, 2, 4, 16,
            4, 8, 4, 4, 4, 2, 4, 1, 2, 8, 2, 4, 2, 2, 2, 1, 1, 4, 1, 2, 1, 1
    };

    /**
     * The geometry of every transform size as {@code [tx * 5 + {0 = width, 1 = height, 2 =
     * log2 width, 3 = log2 height, 4 = split size}]}, dimensions in 4x4 units; dav1d's
     * {@code dav1d_txfm_dimensions} ({@code w}, {@code h}, {@code lw}, {@code lh}, {@code sub}).
     */
    private static final int[] TX_DIMENSIONS = {
            1, 1, 0, 0, 0,
            2, 2, 1, 1, 0,
            4, 4, 2, 2, 1,
            8, 8, 3, 3, 2,
            16, 16, 4, 4, 3,
            1, 2, 0, 1, 0,
            2, 1, 1, 0, 0,
            2, 4, 1, 2, 1,
            4, 2, 2, 1, 1,
            4, 8, 2, 3, 2,
            8, 4, 3, 2, 2,
            8, 16, 3, 4, 3,
            16, 8, 4, 3, 3,
            1, 4, 0, 2, 5,
            4, 1, 2, 0, 6,
            2, 8, 1, 3, 7,
            8, 2, 3, 1, 8,
            4, 16, 2, 4, 9,
            16, 4, 4, 2, 10
    };

    /**
     * Prevents instantiation.
     */
    private LoopFilterMasks() {
        throw new AssertionError();
    }

    /**
     * Records the deblocking state of an intra-coded block.
     *
     * <p>The level cache is a frame-wide array of four bytes per 4x4 block (luma column-edge
     * level, luma row-edge level, Cb level, Cr level), the entry of block {@code (bx, by)} at index
     * {@code 4 * (by * b4Stride + bx)}; chroma entries use chroma 4x4 coordinates in the same
     * array. The filter levels of the block are read from {@code filterLevel} at
     * {@code filterLevelOff + 16 * k} for direction {@code k} (0 and 1 luma, 2 Cb, 3 Cr), the
     * layout of the {@link #calcLfValues} table. The context arrays hold one entry per 4x4 row or
     * column of the tile: {@code ay}/{@code ly} start at the block's first luma column/row and
     * {@code auv}/{@code luv} at its first chroma column/row; pass {@code null} for {@code auv}
     * when the block has no chroma.
     *
     * @param lflvl the masks of the block's 128x128 area
     * @param levelCache the frame's level cache
     * @param b4Stride the level cache row stride, in 4x4 blocks
     * @param filterLevel the per-segment filter level table
     * @param filterLevelOff the index of the block's level for luma column edges
     * @param bx the block's luma column, in 4x4 units from the frame's left edge
     * @param by the block's luma row, in 4x4 units from the frame's top edge
     * @param iw the frame width, in 4x4 units
     * @param ih the frame height, in 4x4 units
     * @param bs the block size
     * @param ytx the luma transform size
     * @param uvtx the chroma transform size
     * @param layout the pixel layout
     * @param ay the above luma transform-size context
     * @param ayOff the index of the block's first column in {@code ay}
     * @param ly the left luma transform-size context
     * @param lyOff the index of the block's first row in {@code ly}
     * @param auv the above chroma transform-size context, or {@code null}
     * @param auvOff the index of the block's first chroma column in {@code auv}
     * @param luv the left chroma transform-size context, or {@code null}
     * @param luvOff the index of the block's first chroma row in {@code luv}
     */
    public static void createLfMaskIntra(LfMask lflvl, byte[] levelCache, int b4Stride,
                                         byte[] filterLevel, int filterLevelOff,
                                         int bx, int by, int iw, int ih, int bs, int ytx, int uvtx, int layout,
                                         byte[] ay, int ayOff, byte[] ly, int lyOff,
                                         byte[] auv, int auvOff, byte[] luv, int luvOff) {
        var bw = BLOCK_DIMENSIONS[bs * 2];
        var bh = BLOCK_DIMENSIONS[bs * 2 + 1];
        var bw4 = Math.min(iw - bx, bw);
        var bh4 = Math.min(ih - by, bh);
        var bx4 = bx & 31;
        var by4 = by & 31;
        if (bw4 > 0 && bh4 > 0) {
            fillLevelCacheBlock(levelCache, 4 * (by * b4Stride + bx), b4Stride, bw4, bh4,
                    filterLevel[filterLevelOff], filterLevel[filterLevelOff + 16]);
            maskEdgesIntra(lflvl.filterY, by4, bx4, bw4, bh4, ytx, ay, ayOff, ly, lyOff);
        }
        if (auv == null) {
            return;
        }
        var ssVer = layout == SequenceHeader.LAYOUT_I420 ? 1 : 0;
        var ssHor = layout != SequenceHeader.LAYOUT_I444 ? 1 : 0;
        var cbw4 = Math.min(((iw + ssHor) >> ssHor) - (bx >> ssHor), (bw + ssHor) >> ssHor);
        var cbh4 = Math.min(((ih + ssVer) >> ssVer) - (by >> ssVer), (bh + ssVer) >> ssVer);
        if (cbw4 <= 0 || cbh4 <= 0) {
            return;
        }
        var cbx4 = bx4 >> ssHor;
        var cby4 = by4 >> ssVer;
        fillLevelCacheBlock(levelCache, 4 * ((by >> ssVer) * b4Stride + (bx >> ssHor)) + 2, b4Stride, cbw4, cbh4,
                filterLevel[filterLevelOff + 32], filterLevel[filterLevelOff + 48]);
        maskEdgesChroma(lflvl.filterUv, cby4, cbx4, cbw4, cbh4, false, uvtx, auv, auvOff, luv, luvOff, ssHor, ssVer);
    }

    /**
     * Records the deblocking state of an inter-coded block.
     *
     * <p>Arguments are as for {@link #createLfMaskIntra}; the luma transform partition is
     * described by the largest transform size and dav1d's two split masks: bit
     * {@code y * 4 + x} of {@code txSplit0} splits the depth-0 transform at {@code (x, y)} and of
     * {@code txSplit1} the depth-1 transform at {@code (x, y)}. A skipped block records only its
     * outer edges.
     *
     * @param lflvl the masks of the block's 128x128 area
     * @param levelCache the frame's level cache
     * @param b4Stride the level cache row stride, in 4x4 blocks
     * @param filterLevel the per-segment filter level table
     * @param filterLevelOff the index of the block's level for luma column edges
     * @param bx the block's luma column, in 4x4 units from the frame's left edge
     * @param by the block's luma row, in 4x4 units from the frame's top edge
     * @param iw the frame width, in 4x4 units
     * @param ih the frame height, in 4x4 units
     * @param skip whether the block has no residual (and is not intra)
     * @param bs the block size
     * @param maxYtx the largest luma transform size of the block
     * @param txSplit0 the depth-0 split mask
     * @param txSplit1 the depth-1 split mask
     * @param uvtx the chroma transform size
     * @param layout the pixel layout
     * @param ay the above luma transform-size context
     * @param ayOff the index of the block's first column in {@code ay}
     * @param ly the left luma transform-size context
     * @param lyOff the index of the block's first row in {@code ly}
     * @param auv the above chroma transform-size context, or {@code null}
     * @param auvOff the index of the block's first chroma column in {@code auv}
     * @param luv the left chroma transform-size context, or {@code null}
     * @param luvOff the index of the block's first chroma row in {@code luv}
     * @param txa a scratch buffer of at least {@link #TX_SCRATCH_LENGTH} bytes
     */
    public static void createLfMaskInter(LfMask lflvl, byte[] levelCache, int b4Stride,
                                         byte[] filterLevel, int filterLevelOff,
                                         int bx, int by, int iw, int ih, boolean skip, int bs,
                                         int maxYtx, int txSplit0, int txSplit1, int uvtx, int layout,
                                         byte[] ay, int ayOff, byte[] ly, int lyOff,
                                         byte[] auv, int auvOff, byte[] luv, int luvOff, byte[] txa) {
        var bw = BLOCK_DIMENSIONS[bs * 2];
        var bh = BLOCK_DIMENSIONS[bs * 2 + 1];
        var bw4 = Math.min(iw - bx, bw);
        var bh4 = Math.min(ih - by, bh);
        var bx4 = bx & 31;
        var by4 = by & 31;
        if (bw4 > 0 && bh4 > 0) {
            fillLevelCacheBlock(levelCache, 4 * (by * b4Stride + bx), b4Stride, bw4, bh4,
                    filterLevel[filterLevelOff], filterLevel[filterLevelOff + 16]);
            maskEdgesInter(lflvl.filterY, by4, bx4, bw4, bh4, skip, maxYtx, txSplit0, txSplit1,
                    ay, ayOff, ly, lyOff, txa);
        }
        if (auv == null) {
            return;
        }
        var ssVer = layout == SequenceHeader.LAYOUT_I420 ? 1 : 0;
        var ssHor = layout != SequenceHeader.LAYOUT_I444 ? 1 : 0;
        var cbw4 = Math.min(((iw + ssHor) >> ssHor) - (bx >> ssHor), (bw + ssHor) >> ssHor);
        var cbh4 = Math.min(((ih + ssVer) >> ssVer) - (by >> ssVer), (bh + ssVer) >> ssVer);
        if (cbw4 <= 0 || cbh4 <= 0) {
            return;
        }
        var cbx4 = bx4 >> ssHor;
        var cby4 = by4 >> ssVer;
        fillLevelCacheBlock(levelCache, 4 * ((by >> ssVer) * b4Stride + (bx >> ssHor)) + 2, b4Stride, cbw4, cbh4,
                filterLevel[filterLevelOff + 32], filterLevel[filterLevelOff + 48]);
        maskEdgesChroma(lflvl.filterUv, cby4, cbx4, cbw4, cbh4, skip, uvtx, auv, auvOff, luv, luvOff, ssHor, ssVer);
    }

    /**
     * Fills the filter limit table for a sharpness (AV1 specification section 7.14.4).
     *
     * @param lut the table to fill
     * @param sharpness the frame's {@code loop_filter_sharpness}, from 0 to 7
     */
    public static void calcEih(FilterLut lut, int sharpness) {
        for (var level = 0; level < 64; level++) {
            var limit = level;
            if (sharpness > 0) {
                limit >>= (sharpness + 3) >> 2;
                limit = Math.min(limit, 9 - sharpness);
            }
            limit = Math.max(limit, 1);
            lut.i[level] = limit;
            lut.e[level] = 2 * (level + 2) + limit;
        }
        lut.sharp[0] = (sharpness + 3) >> 2;
        lut.sharp[1] = sharpness != 0 ? 9 - sharpness : 0xFF;
    }

    /**
     * Computes the filter level of every segment, direction, reference frame and mode (AV1
     * specification section 7.14.4, the filter level derivation).
     *
     * <p>{@code values} is dav1d's {@code uint8_t [8][4][8][2]}: the level of segment {@code s},
     * direction {@code d} (0 luma column edges, 1 luma row edges, 2 Cb, 3 Cr), reference
     * {@code r} (0 intra, 1 to 7 {@code LAST} to {@code ALTREF}) and mode {@code m} (1 for
     * blocks predicted with a global motion vector, 0 otherwise; intra uses both) is at index
     * {@code ((s * 4 + d) * 8 + r) * 2 + m}. Only the first segment is written when segmentation
     * is disabled.
     *
     * <p>The arguments are the frame header fields dav1d's {@code dav1d_calc_lf_values} reads:
     * {@code loop_filter_level[0..3]}, {@code loop_filter_delta_enabled} with the mode and
     * reference deltas, {@code segmentation_enabled} with the per-segment loop filter deltas, and
     * {@code delta_lf_multi}; plus the current block-level loop filter deltas.
     *
     * @param values the table to fill, at least {@link #LF_VALUES_LENGTH} bytes
     * @param levelY0 the luma column-edge (vertical) level
     * @param levelY1 the luma row-edge (horizontal) level
     * @param levelU the Cb level
     * @param levelV the Cr level
     * @param modeRefDeltaEnabled whether the mode and reference deltas apply
     * @param modeDeltas the two mode deltas ({@code loop_filter_mode_deltas})
     * @param refDeltas the eight reference deltas ({@code loop_filter_ref_deltas},
     * intra first)
     * @param segmentationEnabled whether segmentation is enabled
     * @param segDeltaLf the per-segment level deltas, {@code 4 * segment + d} for
     * {@code d} = 0 luma vertical, 1 luma horizontal, 2 Cb, 3 Cr; read
     * only when segmentation is enabled
     * @param deltaLfMulti whether each direction has its own block-level delta
     * @param lfDelta the four block-level loop filter deltas ({@code DeltaLF})
     */
    public static void calcLfValues(byte[] values, int levelY0, int levelY1, int levelU, int levelV,
                                    boolean modeRefDeltaEnabled, int[] modeDeltas, int[] refDeltas,
                                    boolean segmentationEnabled, int[] segDeltaLf,
                                    boolean deltaLfMulti, int[] lfDelta) {
        var segments = segmentationEnabled ? 8 : 1;
        if (levelY0 == 0 && levelY1 == 0) {
            Arrays.fill(values, 0, segments * 64, (byte) 0);
            return;
        }
        var deltas = modeRefDeltaEnabled;
        for (var s = 0; s < segments; s++) {
            var base = s * 64;
            calcLfValue(values, base, levelY0, lfDelta[0],
                    segmentationEnabled ? segDeltaLf[4 * s] : 0, deltas, modeDeltas, refDeltas);
            calcLfValue(values, base + 16, levelY1, lfDelta[deltaLfMulti ? 1 : 0],
                    segmentationEnabled ? segDeltaLf[4 * s + 1] : 0, deltas, modeDeltas, refDeltas);
            calcLfValueChroma(values, base + 32, levelU, lfDelta[deltaLfMulti ? 2 : 0],
                    segmentationEnabled ? segDeltaLf[4 * s + 2] : 0, deltas, modeDeltas, refDeltas);
            calcLfValueChroma(values, base + 48, levelV, lfDelta[deltaLfMulti ? 3 : 0],
                    segmentationEnabled ? segDeltaLf[4 * s + 3] : 0, deltas, modeDeltas, refDeltas);
        }
    }

    /**
     * Computes the 16 levels (8 references by 2 modes) of one segment and direction.
     *
     * @param values the level table
     * @param off the index of the first of the 16 levels
     * @param baseLevel the frame level of the direction
     * @param lfDelta the block-level delta of the direction
     * @param segDelta the segment delta of the direction
     * @param deltas whether the mode and reference deltas apply
     * @param modeDeltas the two mode deltas
     * @param refDeltas the eight reference deltas
     */
    private static void calcLfValue(byte[] values, int off, int baseLevel, int lfDelta, int segDelta,
                                    boolean deltas, int[] modeDeltas, int[] refDeltas) {
        var base = Math.clamp(Math.clamp(baseLevel + lfDelta, 0, 63) + segDelta, 0, 63);
        if (!deltas) {
            Arrays.fill(values, off, off + 16, (byte) base);
            return;
        }
        var sh = base >= 32 ? 1 : 0;
        var intra = (byte) Math.clamp(base + (refDeltas[0] * (1 << sh)), 0, 63);
        values[off] = intra;
        values[off + 1] = intra;
        for (var r = 1; r < 8; r++) {
            for (var m = 0; m < 2; m++) {
                var delta = modeDeltas[m] + refDeltas[r];
                values[off + r * 2 + m] = (byte) Math.clamp(base + (delta * (1 << sh)), 0, 63);
            }
        }
    }

    /**
     * Computes the 16 levels of one segment and chroma direction: all zero when the frame level
     * of the plane is zero, otherwise as {@link #calcLfValue}.
     *
     * @param values the level table
     * @param off the index of the first of the 16 levels
     * @param baseLevel the frame level of the plane
     * @param lfDelta the block-level delta of the plane
     * @param segDelta the segment delta of the plane
     * @param deltas whether the mode and reference deltas apply
     * @param modeDeltas the two mode deltas
     * @param refDeltas the eight reference deltas
     */
    private static void calcLfValueChroma(byte[] values, int off, int baseLevel, int lfDelta, int segDelta,
                                          boolean deltas, int[] modeDeltas, int[] refDeltas) {
        if (baseLevel == 0) {
            Arrays.fill(values, off, off + 16, (byte) 0);
        } else {
            calcLfValue(values, off, baseLevel, lfDelta, segDelta, deltas, modeDeltas, refDeltas);
        }
    }

    /**
     * Writes a pair of level bytes into every entry of a block of the level cache.
     *
     * @param cache the level cache
     * @param off the index of the first byte to write in the block's top-left entry
     * @param b4Stride the cache row stride, in entries of four bytes
     * @param bw the block width, in entries
     * @param bh the block height, in entries
     * @param v0 the first byte of each pair
     * @param v1 the second byte of each pair
     */
    private static void fillLevelCacheBlock(byte[] cache, int off, int b4Stride, int bw, int bh, byte v0, byte v1) {
        for (var y = 0; y < bh; y++, off += 4 * b4Stride) {
            for (var x = 0; x < bw; x++) {
                cache[off + 4 * x] = v0;
                cache[off + 4 * x + 1] = v1;
            }
        }
    }

    /**
     * Records the outer and inner edges of an intra block's luma transforms and updates the
     * transform-size contexts.
     *
     * @param masks the luma masks
     * @param by4 the block's row within the 128x128 area
     * @param bx4 the block's column within the 128x128 area
     * @param w4 the visible block width, in 4x4 units
     * @param h4 the visible block height, in 4x4 units
     * @param tx the transform size
     * @param a the above context
     * @param aOff the index of the block's first column in {@code a}
     * @param l the left context
     * @param lOff the index of the block's first row in {@code l}
     */
    private static void maskEdgesIntra(char[] masks, int by4, int bx4, int w4, int h4, int tx,
                                       byte[] a, int aOff, byte[] l, int lOff) {
        var twl4c = Math.min(2, TX_DIMENSIONS[tx * 5 + 2]);
        var thl4c = Math.min(2, TX_DIMENSIONS[tx * 5 + 3]);
        var mask = 1 << by4;
        for (var y = 0; y < h4; y++, mask <<= 1) {
            var sidx = (mask >>> 16) != 0 ? 1 : 0;
            var smask = mask >>> (sidx << 4);
            masks[LfMask.yIndex(0, bx4, Math.min(twl4c, l[lOff + y] & 0xFF), sidx)] |= (char) smask;
        }
        mask = 1 << bx4;
        for (var x = 0; x < w4; x++, mask <<= 1) {
            var sidx = (mask >>> 16) != 0 ? 1 : 0;
            var smask = mask >>> (sidx << 4);
            masks[LfMask.yIndex(1, by4, Math.min(thl4c, a[aOff + x] & 0xFF), sidx)] |= (char) smask;
        }
        var hstep = TX_DIMENSIONS[tx * 5];
        var t = 1L << by4;
        var inner = (int) ((t << h4) - t);
        var inner1 = inner & 0xFFFF;
        var inner2 = inner >>> 16;
        for (var x = hstep; x < w4; x += hstep) {
            if (inner1 != 0) {
                masks[LfMask.yIndex(0, bx4 + x, twl4c, 0)] |= (char) inner1;
            }
            if (inner2 != 0) {
                masks[LfMask.yIndex(0, bx4 + x, twl4c, 1)] |= (char) inner2;
            }
        }
        var vstep = TX_DIMENSIONS[tx * 5 + 1];
        t = 1L << bx4;
        inner = (int) ((t << w4) - t);
        inner1 = inner & 0xFFFF;
        inner2 = inner >>> 16;
        for (var y = vstep; y < h4; y += vstep) {
            if (inner1 != 0) {
                masks[LfMask.yIndex(1, by4 + y, thl4c, 0)] |= (char) inner1;
            }
            if (inner2 != 0) {
                masks[LfMask.yIndex(1, by4 + y, thl4c, 1)] |= (char) inner2;
            }
        }
        Arrays.fill(a, aOff, aOff + w4, (byte) thl4c);
        Arrays.fill(l, lOff, lOff + h4, (byte) twl4c);
    }

    /**
     * Records the outer edges, and unless the block is skipped the inner transform edges, of an
     * inter block's luma transform partition, and updates the transform-size contexts.
     *
     * @param masks the luma masks
     * @param by4 the block's row within the 128x128 area
     * @param bx4 the block's column within the 128x128 area
     * @param w4 the visible block width, in 4x4 units
     * @param h4 the visible block height, in 4x4 units
     * @param skip whether the block has no residual
     * @param maxTx the largest transform size
     * @param txSplit0 the depth-0 split mask
     * @param txSplit1 the depth-1 split mask
     * @param a the above context
     * @param aOff the index of the block's first column in {@code a}
     * @param l the left context
     * @param lOff the index of the block's first row in {@code l}
     * @param txa the decomposition scratch, dav1d's {@code txa[2][2][32][32]}
     */
    private static void maskEdgesInter(char[] masks, int by4, int bx4, int w4, int h4, boolean skip,
                                       int maxTx, int txSplit0, int txSplit1,
                                       byte[] a, int aOff, byte[] l, int lOff, byte[] txa) {
        var txw = TX_DIMENSIONS[maxTx * 5];
        var txh = TX_DIMENSIONS[maxTx * 5 + 1];
        for (int yOff = 0, y = 0; y < h4; y += txh, yOff++) {
            for (int xOff = 0, x = 0; x < w4; x += txw, xOff++) {
                decompTx(txa, y * 32 + x, maxTx, 0, yOff, xOff, txSplit0, txSplit1);
            }
        }
        var mask = 1 << by4;
        for (var y = 0; y < h4; y++, mask <<= 1) {
            var sidx = (mask >>> 16) != 0 ? 1 : 0;
            var smask = mask >>> (sidx << 4);
            masks[LfMask.yIndex(0, bx4, Math.min(txa[y * 32], l[lOff + y] & 0xFF), sidx)] |= (char) smask;
        }
        mask = 1 << bx4;
        for (var x = 0; x < w4; x++, mask <<= 1) {
            var sidx = (mask >>> 16) != 0 ? 1 : 0;
            var smask = mask >>> (sidx << 4);
            masks[LfMask.yIndex(1, by4, Math.min(txa[2048 + x], a[aOff + x] & 0xFF), sidx)] |= (char) smask;
        }
        if (!skip) {
            mask = 1 << by4;
            for (var y = 0; y < h4; y++, mask <<= 1) {
                var sidx = (mask >>> 16) != 0 ? 1 : 0;
                var smask = mask >>> (sidx << 4);
                int ltx = txa[y * 32];
                int step = txa[1024 + y * 32];
                for (var x = step; x < w4; x += step) {
                    int rtx = txa[y * 32 + x];
                    masks[LfMask.yIndex(0, bx4 + x, Math.min(rtx, ltx), sidx)] |= (char) smask;
                    ltx = rtx;
                    step = txa[1024 + y * 32 + x];
                }
            }
            mask = 1 << bx4;
            for (var x = 0; x < w4; x++, mask <<= 1) {
                var sidx = (mask >>> 16) != 0 ? 1 : 0;
                var smask = mask >>> (sidx << 4);
                int ttx = txa[2048 + x];
                int step = txa[3072 + x];
                for (var y = step; y < h4; y += step) {
                    int btx = txa[2048 + y * 32 + x];
                    masks[LfMask.yIndex(1, by4 + y, Math.min(ttx, btx), sidx)] |= (char) smask;
                    ttx = btx;
                    step = txa[3072 + y * 32 + x];
                }
            }
        }
        for (var y = 0; y < h4; y++) {
            l[lOff + y] = txa[y * 32 + w4 - 1];
        }
        System.arraycopy(txa, 2048 + (h4 - 1) * 32, a, aOff, w4);
    }

    /**
     * Decomposes one transform of an inter block's partition tree into the decomposition scratch:
     * for every 4x4 position it covers, the log2 width and height (capped at 2) of the leaf
     * transform, the leaf width at the first column of each leaf row and the leaf height at the
     * first row of each leaf column.
     *
     * <p>The scratch is dav1d's {@code txa[2 edge][2 kind][32 y][32 x]}: index
     * {@code ((edge * 2 + kind) * 32 + y) * 32 + x}, kind 0 holding sizes and kind 1 steps.
     *
     * @param txa the decomposition scratch
     * @param base the index of the transform's top-left position in edge 0, kind 0
     * @param from the transform size
     * @param depth the split depth of the transform
     * @param yOff the transform's row within its depth's grid
     * @param xOff the transform's column within its depth's grid
     * @param txSplit0 the depth-0 split mask
     * @param txSplit1 the depth-1 split mask
     */
    private static void decompTx(byte[] txa, int base, int from, int depth, int yOff, int xOff,
                                 int txSplit0, int txSplit1) {
        var w = TX_DIMENSIONS[from * 5];
        var h = TX_DIMENSIONS[from * 5 + 1];
        var lwFull = TX_DIMENSIONS[from * 5 + 2];
        var lhFull = TX_DIMENSIONS[from * 5 + 3];
        var split = from != 0 && depth <= 1
                && (((depth == 0 ? txSplit0 : txSplit1) >> (yOff * 4 + xOff)) & 1) != 0;
        if (split) {
            var sub = TX_DIMENSIONS[from * 5 + 4];
            var htw4 = w >> 1;
            var hth4 = h >> 1;
            decompTx(txa, base, sub, depth + 1, yOff * 2, xOff * 2, txSplit0, txSplit1);
            if (w >= h) {
                decompTx(txa, base + htw4, sub, depth + 1, yOff * 2, xOff * 2 + 1, txSplit0, txSplit1);
            }
            if (h >= w) {
                decompTx(txa, base + hth4 * 32, sub, depth + 1, yOff * 2 + 1, xOff * 2, txSplit0, txSplit1);
                if (w >= h) {
                    decompTx(txa, base + hth4 * 32 + htw4, sub, depth + 1, yOff * 2 + 1, xOff * 2 + 1,
                            txSplit0, txSplit1);
                }
            }
        } else {
            var lw = (byte) Math.min(2, lwFull);
            var lh = (byte) Math.min(2, lhFull);
            for (var y = 0; y < h; y++) {
                var row = base + y * 32;
                Arrays.fill(txa, row, row + w, lw);
                Arrays.fill(txa, 2048 + row, 2048 + row + w, lh);
                txa[1024 + row] = (byte) w;
            }
            Arrays.fill(txa, 3072 + base, 3072 + base + w, (byte) h);
        }
    }

    /**
     * Records the outer and, unless the block is a skipped inter block, the inner transform edges
     * of a block's chroma transforms, and updates the chroma transform-size contexts.
     *
     * @param masks the chroma masks
     * @param cby4 the block's chroma row within the 128x128 area
     * @param cbx4 the block's chroma column within the 128x128 area
     * @param cw4 the visible chroma block width, in 4x4 units
     * @param ch4 the visible chroma block height, in 4x4 units
     * @param skipInter whether the block is a skipped inter block
     * @param tx the chroma transform size
     * @param a the above context
     * @param aOff the index of the block's first chroma column in {@code a}
     * @param l the left context
     * @param lOff the index of the block's first chroma row in {@code l}
     * @param ssHor 1 when chroma is horizontally subsampled
     * @param ssVer 1 when chroma is vertically subsampled
     */
    private static void maskEdgesChroma(char[] masks, int cby4, int cbx4, int cw4, int ch4, boolean skipInter,
                                        int tx, byte[] a, int aOff, byte[] l, int lOff, int ssHor, int ssVer) {
        var twl4c = TX_DIMENSIONS[tx * 5 + 2] != 0 ? 1 : 0;
        var thl4c = TX_DIMENSIONS[tx * 5 + 3] != 0 ? 1 : 0;
        var vbits = 4 - ssVer;
        var hbits = 4 - ssHor;
        var vmask = 16 >> ssVer;
        var hmask = 16 >> ssHor;
        var vmax = 1 << vmask;
        var hmax = 1 << hmask;
        var mask = 1 << cby4;
        for (var y = 0; y < ch4; y++, mask <<= 1) {
            var sidx = Integer.compareUnsigned(mask, vmax) >= 0 ? 1 : 0;
            var smask = mask >>> (sidx << vbits);
            masks[LfMask.uvIndex(0, cbx4, Math.min(twl4c, l[lOff + y] & 0xFF), sidx)] |= (char) smask;
        }
        mask = 1 << cbx4;
        for (var x = 0; x < cw4; x++, mask <<= 1) {
            var sidx = Integer.compareUnsigned(mask, hmax) >= 0 ? 1 : 0;
            var smask = mask >>> (sidx << hbits);
            masks[LfMask.uvIndex(1, cby4, Math.min(thl4c, a[aOff + x] & 0xFF), sidx)] |= (char) smask;
        }
        if (!skipInter) {
            var hstep = TX_DIMENSIONS[tx * 5];
            var t = 1L << cby4;
            var inner = (int) ((t << ch4) - t);
            var inner1 = inner & ((1 << vmask) - 1);
            var inner2 = inner >>> vmask;
            for (var x = hstep; x < cw4; x += hstep) {
                if (inner1 != 0) {
                    masks[LfMask.uvIndex(0, cbx4 + x, twl4c, 0)] |= (char) inner1;
                }
                if (inner2 != 0) {
                    masks[LfMask.uvIndex(0, cbx4 + x, twl4c, 1)] |= (char) inner2;
                }
            }
            var vstep = TX_DIMENSIONS[tx * 5 + 1];
            t = 1L << cbx4;
            inner = (int) ((t << cw4) - t);
            inner1 = inner & ((1 << hmask) - 1);
            inner2 = inner >>> hmask;
            for (var y = vstep; y < ch4; y += vstep) {
                if (inner1 != 0) {
                    masks[LfMask.uvIndex(1, cby4 + y, thl4c, 0)] |= (char) inner1;
                }
                if (inner2 != 0) {
                    masks[LfMask.uvIndex(1, cby4 + y, thl4c, 1)] |= (char) inner2;
                }
            }
        }
        Arrays.fill(a, aOff, aOff + cw4, (byte) thl4c);
        Arrays.fill(l, lOff, lOff + ch4, (byte) twl4c);
    }
}
