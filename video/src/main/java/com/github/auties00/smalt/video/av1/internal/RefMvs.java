package com.github.auties00.smalt.video.av1.internal;

/**
 * Predicts motion vectors: builds the candidate list of a block from its spatial neighbors, the
 * projected motion of reference frames and global motion (AV1 specification sections 7.10.2 and
 * 7.9), and keeps the per-block motion of the current frame for later blocks and frames.
 *
 * <p>Motion vectors are packed into one {@code int} as dav1d's {@code union mv} lays them out in
 * memory: the vertical component in the low 16 bits and the horizontal one in the high 16 bits.
 * Per-block motion records ({@code refmvs_block}) take {@link #BLOCK_INTS} ints of
 * {@link Frame#r}: the two motion vectors, then the two references, the block size and the
 * motion flags packed one per byte.
 *
 * @implNote This implementation ports dav1d's {@code src/refmvs.c} and the motion vector helpers
 *           of {@code src/env.h}, keeping its row-pointer ring of block records and its temporal
 *           projection buffers.
 */
public final class RefMvs {
    /**
     * The packed motion vector marking an intra block or an unprojected temporal cell.
     */
    public static final int INVALID_MV = 0x80008000;

    /**
     * The reference-to-current distance marking a reference without usable temporal motion.
     */
    public static final int INVALID_REF2CUR = -32;

    /**
     * The number of ints of one block record.
     */
    public static final int BLOCK_INTS = 3;

    /**
     * Holds the multipliers that approximate division by small distances
     * ({@code Div_Mult}, AV1 specification section 7.9.3).
     */
    private static final int[] DIV_MULT = {
            0, 16384, 8192, 5461, 4096, 3276, 2730, 2340,
            2048, 1820, 1638, 1489, 1365, 1260, 1170, 1092,
            1024, 963, 910, 862, 819, 780, 744, 712,
            682, 655, 630, 606, 585, 564, 546, 528,
    };

    /**
     * Prevents instantiation.
     */
    private RefMvs() {
        throw new AssertionError();
    }

    /**
     * Packs a motion vector.
     *
     * @param y the vertical component
     * @param x the horizontal component
     * @return the packed vector
     */
    public static int mv(int y, int x) {
        return (x << 16) | (y & 0xFFFF);
    }

    /**
     * Returns the vertical component of a packed motion vector.
     *
     * @param mv the packed vector
     * @return the component
     */
    public static int mvY(int mv) {
        return (short) mv;
    }

    /**
     * Returns the horizontal component of a packed motion vector.
     *
     * @param mv the packed vector
     * @return the component
     */
    public static int mvX(int mv) {
        return mv >> 16;
    }

    /**
     * Packs the third int of a block record.
     *
     * @param ref0 the first reference plus one (0 for intra and intra block copy)
     * @param ref1 the second reference plus one, or {@code -1} for single prediction
     * @param bs   the block size ({@code BlockSize})
     * @param mf   the motion flags: 1 for global motion with a warp, 2 for a new motion vector
     * @return the packed fields
     */
    public static int packInfo(int ref0, int ref1, int bs, int mf) {
        return (ref0 & 0xFF) | (ref1 & 0xFF) << 8 | bs << 16 | mf << 24;
    }

    /**
     * Returns the first reference of a packed block record.
     *
     * @param info the third int of the record
     * @return the reference plus one, or 0 for intra
     */
    public static int ref0(int info) {
        return (byte) info;
    }

    /**
     * Returns the second reference of a packed block record.
     *
     * @param info the third int of the record
     * @return the reference plus one, or {@code -1}
     */
    public static int ref1(int info) {
        return (byte) (info >> 8);
    }

    /**
     * Returns the block size of a packed block record.
     *
     * @param info the third int of the record
     * @return the block size
     */
    public static int bs(int info) {
        return (info >>> 16) & 0xFF;
    }

    /**
     * Returns the motion flags of a packed block record.
     *
     * @param info the third int of the record
     * @return the flags
     */
    public static int mf(int info) {
        return info >>> 24;
    }

    /**
     * Rounds a motion vector to whole samples (AV1 specification section 7.10.2.14).
     *
     * @param mv the packed vector
     * @return the rounded vector
     */
    public static int fixIntMvPrecision(int mv) {
        var x = mvX(mv);
        var y = mvY(mv);
        x = (x - (x >> 15) + 3) & ~7;
        y = (y - (y >> 15) + 3) & ~7;
        return mv(y, x);
    }

    /**
     * Reduces a motion vector to the precision a frame allows.
     *
     * @param hdr the frame header
     * @param mv  the packed vector
     * @return the vector at the frame's precision
     */
    public static int fixMvPrecision(FrameHeader hdr, int mv) {
        if (hdr.forceIntegerMv) {
            return fixIntMvPrecision(mv);
        }
        if (!hdr.hp) {
            var x = mvX(mv);
            var y = mvY(mv);
            x = (x - (x >> 15)) & ~1;
            y = (y - (y >> 15)) & ~1;
            return mv(y, x);
        }
        return mv;
    }

    /**
     * Returns the motion vector a global motion model assigns to a block (AV1 specification section
     * 7.10.2.1).
     *
     * @param gmv the global motion model
     * @param bx4 the block column in 4-sample units
     * @param by4 the block row in 4-sample units
     * @param bw4 the block width in 4-sample units
     * @param bh4 the block height in 4-sample units
     * @param hdr the frame header
     * @return the packed vector
     */
    public static int gmv2d(WarpedMotionParams gmv, int bx4, int by4, int bw4, int bh4, FrameHeader hdr) {
        switch (gmv.type) {
            case WarpedMotionParams.TRANSLATION -> {
                var res = mv(gmv.matrix[0] >> 13, gmv.matrix[1] >> 13);
                return hdr.forceIntegerMv ? fixIntMvPrecision(res) : res;
            }
            case WarpedMotionParams.IDENTITY -> {
                return 0;
            }
            default -> {
                var x = bx4 * 4 + bw4 * 2 - 1;
                var y = by4 * 4 + bh4 * 2 - 1;
                var xc = (gmv.matrix[2] - (1 << 16)) * x + gmv.matrix[3] * y + gmv.matrix[0];
                var yc = (gmv.matrix[5] - (1 << 16)) * y + gmv.matrix[4] * x + gmv.matrix[1];
                var hp = hdr.hp ? 1 : 0;
                var shift = 16 - (3 - (hp ^ 1));
                var round = (1 << shift) >> 1;
                var ry = ((Math.abs(yc) + round) >> shift) << (hp ^ 1);
                var rx = ((Math.abs(xc) + round) >> shift) << (hp ^ 1);
                var res = mv(yc < 0 ? -ry : ry, xc < 0 ? -rx : rx);
                return hdr.forceIntegerMv ? fixIntMvPrecision(res) : res;
            }
        }
    }

    /**
     * Projects a motion vector across a different temporal distance (AV1 specification section
     * 7.9.3).
     *
     * @param mv  the packed vector
     * @param num the target distance
     * @param den the distance the vector spans, from 1 to 31
     * @return the projected vector
     */
    static int mvProjection(int mv, int num, int den) {
        var frac = num * DIV_MULT[den];
        var y = mvY(mv) * frac;
        var x = mvX(mv) * frac;
        return mv(Math.clamp((y + 8192 + (y >> 31)) >> 14, -0x3fff, 0x3fff),
                Math.clamp((x + 8192 + (x >> 31)) >> 14, -0x3fff, 0x3fff));
    }

    /**
     * Holds the temporal motion records of one frame: one motion vector and reference per 8x8
     * block, which later frames project ({@code refmvs_temporal_block}).
     */
    public static final class Temporal {
        /**
         * The packed motion vector of each 8x8 block.
         */
        public final int[] mv;

        /**
         * The reference of each 8x8 block, 0 when there is none.
         */
        public final byte[] ref;

        /**
         * Allocates records.
         *
         * @param size the number of 8x8 blocks
         */
        public Temporal(int size) {
            this.mv = new int[size];
            this.ref = new byte[size];
        }

        /**
         * Returns the number of records.
         *
         * @return the capacity
         */
        public int size() {
            return mv.length;
        }
    }

    /**
     * Holds the motion prediction state of one frame ({@code refmvs_frame}).
     */
    public static final class Frame {
        /**
         * The frame header.
         */
        public FrameHeader hdr;

        /**
         * The frame width in 4-sample units, rounded up to 8 samples.
         */
        public int iw4;

        /**
         * The frame height in 4-sample units, rounded up to 8 samples.
         */
        public int ih4;

        /**
         * The frame width in 8-sample units.
         */
        public int iw8;

        /**
         * The frame height in 8-sample units.
         */
        public int ih8;

        /**
         * The superblock size in 4-sample units.
         */
        public int sbsz;

        /**
         * Whether temporal candidates are used.
         */
        public boolean useRefFrameMvs;

        /**
         * Whether each reference lies in the future.
         */
        public final byte[] signBias = new byte[7];

        /**
         * Whether each reference lies in the past, the sign of saved temporal motion.
         */
        public final byte[] mfmvSign = new byte[7];

        /**
         * The clamped order hint distance from the current frame to each reference.
         */
        public final int[] pocdiff = new int[7];

        /**
         * The references whose motion is projected.
         */
        public final int[] mfmvRef = new int[3];

        /**
         * The distance from each projected reference to the current frame.
         */
        public final int[] mfmvRef2cur = new int[3];

        /**
         * The distance from each projected reference to each of its own references.
         */
        public final int[][] mfmvRef2ref = new int[3][7];

        /**
         * The number of projected references.
         */
        public int nMfmvs;

        /**
         * The number of temporal records per tile row band.
         */
        public int nBlocks;

        /**
         * The temporal records the current frame saves.
         */
        public Temporal rp;

        /**
         * The temporal records of each reference, or {@code null}.
         */
        public final Temporal[] rpRef = new Temporal[7];

        /**
         * The projected temporal candidates, 16 rows per tile row band.
         */
        public Temporal rpProj;

        /**
         * The number of temporal records per row.
         */
        public int rpStride;

        /**
         * The block records of the superblock rows being decoded, 35 rows per tile row band.
         */
        public int[] r;

        /**
         * The number of tile row bands decoded concurrently.
         */
        public int nTileThreads;

        /**
         * Creates an empty state.
         */
        public Frame() {
        }

        /**
         * Prepares the state for a frame ({@code dav1d_refmvs_init_frame}).
         *
         * @param seq          the sequence header
         * @param hdr          the frame header
         * @param refPoc       the order hint of each reference
         * @param rp           the records the frame saves, or {@code null}
         * @param refRefPoc    the order hints of the references of each reference
         * @param rpRefs       the saved records of each reference, or {@code null} entries
         * @param nTileThreads the number of tile row bands decoded concurrently
         */
        public void init(SequenceHeader seq, FrameHeader hdr, int[] refPoc, Temporal rp, int[][] refRefPoc,
                         Temporal[] rpRefs, int nTileThreads) {
            var stride = ((hdr.codedWidth + 127) & ~127) >> 3;
            var nTileRows = nTileThreads > 1 ? hdr.tilingRows : 1;
            var blocks = stride * nTileRows;
            this.sbsz = 16 << (seq.sb128 ? 1 : 0);
            this.hdr = hdr;
            this.iw8 = (hdr.codedWidth + 7) >> 3;
            this.ih8 = (hdr.height + 7) >> 3;
            this.iw4 = iw8 << 1;
            this.ih4 = ih8 << 1;
            this.rp = rp;
            this.rpStride = stride;
            this.nTileThreads = nTileThreads;
            if (blocks != nBlocks || r == null) {
                r = new int[35 * 2 * blocks * BLOCK_INTS];
                rpProj = new Temporal(16 * blocks);
                nBlocks = blocks;
            }
            var bits = seq.orderHintNBits;
            var poc = hdr.frameOffset;
            for (var i = 0; i < 7; i++) {
                var diff = FrameHeader.pocDiff(bits, refPoc[i], poc);
                signBias[i] = (byte) (diff > 0 ? 1 : 0);
                mfmvSign[i] = (byte) (diff < 0 ? 1 : 0);
                pocdiff[i] = Math.clamp(FrameHeader.pocDiff(bits, poc, refPoc[i]), -31, 31);
            }
            nMfmvs = 0;
            for (var i = 0; i < 7; i++) {
                rpRef[i] = rpRefs != null ? rpRefs[i] : null;
            }
            if (hdr.useRefFrameMvs && bits != 0) {
                var total = 2;
                if (rpRef[0] != null && refRefPoc[0][6] != refPoc[3]) {
                    mfmvRef[nMfmvs++] = 0;
                    total = 3;
                }
                if (rpRef[4] != null && FrameHeader.pocDiff(bits, refPoc[4], hdr.frameOffset) > 0) {
                    mfmvRef[nMfmvs++] = 4;
                }
                if (rpRef[5] != null && FrameHeader.pocDiff(bits, refPoc[5], hdr.frameOffset) > 0) {
                    mfmvRef[nMfmvs++] = 5;
                }
                if (nMfmvs < total && rpRef[6] != null
                        && FrameHeader.pocDiff(bits, refPoc[6], hdr.frameOffset) > 0) {
                    mfmvRef[nMfmvs++] = 6;
                }
                if (nMfmvs < total && rpRef[1] != null) {
                    mfmvRef[nMfmvs++] = 1;
                }
                for (var n = 0; n < nMfmvs; n++) {
                    var rpoc = refPoc[mfmvRef[n]];
                    var diff1 = FrameHeader.pocDiff(bits, rpoc, hdr.frameOffset);
                    if (Math.abs(diff1) > 31) {
                        mfmvRef2cur[n] = INVALID_REF2CUR;
                    } else {
                        mfmvRef2cur[n] = mfmvRef[n] < 4 ? -diff1 : diff1;
                        for (var m = 0; m < 7; m++) {
                            var rrpoc = refRefPoc[mfmvRef[n]][m];
                            var diff2 = FrameHeader.pocDiff(bits, rpoc, rrpoc);
                            mfmvRef2ref[n][m] = Integer.compareUnsigned(diff2, 31) > 0 ? 0 : diff2;
                        }
                    }
                }
            }
            useRefFrameMvs = nMfmvs > 0;
        }

        /**
         * Projects the motion of the references onto a range of 8x8 rows and columns of the current
         * frame ({@code load_tmvs_c}).
         *
         * @param tileRowIdx the tile row band
         * @param colStart8  the first column
         * @param colEnd8    the column after the last one
         * @param rowStart8  the first row
         * @param rowEnd8    the row after the last one
         */
        public void loadTmvs(int tileRowIdx, int colStart8, int colEnd8, int rowStart8, int rowEnd8) {
            if (nTileThreads == 1) {
                tileRowIdx = 0;
            }
            rowEnd8 = Math.min(rowEnd8, ih8);
            var colStart8i = Math.max(colStart8 - 8, 0);
            var colEnd8i = Math.min(colEnd8 + 8, iw8);
            var stride = rpStride;
            var projMv = rpProj.mv;
            var projRef = rpProj.ref;
            var base = 16 * stride * tileRowIdx;
            var row = base + (rowStart8 & 15) * stride;
            for (var y = rowStart8; y < rowEnd8; y++) {
                for (var x = colStart8; x < colEnd8; x++) {
                    projMv[row + x] = INVALID_MV;
                }
                row += stride;
            }
            for (var n = 0; n < nMfmvs; n++) {
                var ref2cur = mfmvRef2cur[n];
                if (ref2cur == INVALID_REF2CUR) {
                    continue;
                }
                var ref = mfmvRef[n];
                var refSign = ref - 4;
                var refMvs = rpRef[ref];
                var rMv = refMvs.mv;
                var rRef = refMvs.ref;
                var r = rowStart8 * stride;
                for (var y = rowStart8; y < rowEnd8; y++) {
                    var ySbAlign = y & ~7;
                    var yProjStart = Math.max(ySbAlign, rowStart8);
                    var yProjEnd = Math.min(ySbAlign + 8, rowEnd8);
                    for (var x = colStart8i; x < colEnd8i; x++) {
                        var rb = r + x;
                        int bRef = rRef[rb];
                        if (bRef == 0) {
                            continue;
                        }
                        var ref2ref = mfmvRef2ref[n][bRef - 1];
                        if (ref2ref == 0) {
                            continue;
                        }
                        var bMv = rMv[rb];
                        var offset = mvProjection(bMv, ref2cur, ref2ref);
                        var ox = mvX(offset);
                        var oy = mvY(offset);
                        var dx = Math.abs(ox) >> 6;
                        var dy = Math.abs(oy) >> 6;
                        var posX = x + ((ox ^ refSign) < 0 ? -dx : dx);
                        var posY = y + ((oy ^ refSign) < 0 ? -dy : dy);
                        if (posY >= yProjStart && posY < yProjEnd) {
                            var pos = base + (posY & 15) * stride;
                            for (; ; ) {
                                var xSbAlign = x & ~7;
                                if (posX >= Math.max(xSbAlign - 8, colStart8) && posX < Math.min(xSbAlign + 16, colEnd8)) {
                                    projMv[pos + posX] = rMv[rb];
                                    projRef[pos + posX] = (byte) ref2ref;
                                }
                                if (++x >= colEnd8i) {
                                    break;
                                }
                                rb++;
                                if (rRef[rb] != bRef || rMv[rb] != bMv) {
                                    break;
                                }
                                posX++;
                            }
                        } else {
                            for (; ; ) {
                                if (++x >= colEnd8i) {
                                    break;
                                }
                                rb++;
                                if (rRef[rb] != bRef || rMv[rb] != bMv) {
                                    break;
                                }
                            }
                        }
                        x--;
                    }
                    r += stride;
                }
            }
        }

        /**
         * Saves the projectable motion of a range of the current frame for later frames
         * ({@code dav1d_refmvs_save_tmvs}).
         *
         * @param rt        the tile state holding the block record rows
         * @param colStart8 the first 8x8 column
         * @param colEnd8   the column after the last one
         * @param rowStart8 the first 8x8 row
         * @param rowEnd8   the row after the last one
         */
        public void saveTmvs(Tile rt, int colStart8, int colEnd8, int rowStart8, int rowEnd8) {
            rowEnd8 = Math.min(rowEnd8, ih8);
            colEnd8 = Math.min(colEnd8, iw8);
            var stride = rpStride;
            var rpMv = rp.mv;
            var rpRefs = rp.ref;
            var rpOff = rowStart8 * stride;
            var blocks = r;
            for (var y = rowStart8; y < rowEnd8; y++) {
                var b = rt.r[6 + (y & 15) * 2];
                for (var x = colStart8; x < colEnd8; ) {
                    var cand = (b + x * 2 + 1) * BLOCK_INTS;
                    var info = blocks[cand + 2];
                    var bw8 = (Tables.BLOCK_DIMENSIONS[bs(info) * 4] + 1) >> 1;
                    var r1 = ref1(info);
                    var r0 = ref0(info);
                    var mv1 = blocks[cand + 1];
                    var mv0 = blocks[cand];
                    if (r1 > 0 && mfmvSign[r1 - 1] != 0 && (Math.abs(mvY(mv1)) | Math.abs(mvX(mv1))) < 4096) {
                        for (var n = 0; n < bw8; n++, x++) {
                            rpMv[rpOff + x] = mv1;
                            rpRefs[rpOff + x] = (byte) r1;
                        }
                    } else if (r0 > 0 && mfmvSign[r0 - 1] != 0 && (Math.abs(mvY(mv0)) | Math.abs(mvX(mv0))) < 4096) {
                        for (var n = 0; n < bw8; n++, x++) {
                            rpMv[rpOff + x] = mv0;
                            rpRefs[rpOff + x] = (byte) r0;
                        }
                    } else {
                        for (var n = 0; n < bw8; n++, x++) {
                            rpMv[rpOff + x] = 0;
                            rpRefs[rpOff + x] = 0;
                        }
                    }
                }
                rpOff += stride;
            }
        }
    }

    /**
     * Holds the motion prediction state of one tile and superblock row ({@code refmvs_tile}).
     */
    public static final class Tile {
        /**
         * The frame state.
         */
        public Frame rf;

        /**
         * The block records the rows of {@link #r} index: {@link Frame#r} after
         * {@link #sbrowInit}, the whole-frame records of the reconstruction pass after
         * {@link #reconInit}.
         */
        public int[] blocks;

        /**
         * The block index, in {@link Frame#r} block units, of each row of the ring: rows 5 to 36 are
         * the rows of the superblock row, rows 0, 2 and 4 the odd rows above it; {@code -1} marks an
         * unused row.
         */
        public final int[] r = new int[37];

        /**
         * The offset of the tile row band in {@link Frame#rpProj}.
         */
        public int rpProj;

        /**
         * The first column of the tile, in 4-sample units.
         */
        public int colStart;

        /**
         * The column after the tile, in 4-sample units.
         */
        public int colEnd;

        /**
         * The first row of the tile, in 4-sample units.
         */
        public int rowStart;

        /**
         * The row after the tile, in 4-sample units.
         */
        public int rowEnd;

        /**
         * Creates an empty state.
         */
        public Tile() {
        }

        /**
         * Points the row ring at the block records of a superblock row
         * ({@code dav1d_refmvs_tile_sbrow_init}).
         *
         * @param frame         the frame state
         * @param tileColStart4 the first column of the tile
         * @param tileColEnd4   the column after the tile
         * @param tileRowStart4 the first row of the tile
         * @param tileRowEnd4   the row after the tile
         * @param sby           the superblock row
         * @param tileRowIdx    the tile row band
         */
        public void sbrowInit(Frame frame, int tileColStart4, int tileColEnd4, int tileRowStart4, int tileRowEnd4,
                              int sby, int tileRowIdx) {
            if (frame.nTileThreads == 1) {
                tileRowIdx = 0;
            }
            rpProj = 16 * frame.rpStride * tileRowIdx;
            var rStride = frame.rpStride * 2;
            var row = 35 * rStride * tileRowIdx;
            var sbsz = frame.sbsz;
            var off = (sbsz * sby) & 16;
            for (var i = 0; i < sbsz; i++, row += rStride) {
                r[off + 5 + i] = row;
            }
            r[off] = row;
            row += rStride;
            r[off + 1] = -1;
            r[off + 2] = row;
            row += rStride;
            r[off + 3] = -1;
            r[off + 4] = row;
            if ((sby & 1) != 0) {
                swap(off, off + sbsz);
                swap(off + 2, off + sbsz + 2);
                swap(off + 4, off + sbsz + 4);
            }
            this.rf = frame;
            this.blocks = frame.r;
            this.rowStart = tileRowStart4;
            this.rowEnd = Math.min(tileRowEnd4, frame.ih4);
            this.colStart = tileColStart4;
            this.colEnd = Math.min(tileColEnd4, frame.iw4);
        }

        /**
         * Swaps two rows of the ring.
         *
         * @param a the first row
         * @param b the second row
         */
        private void swap(int a, int b) {
            var t = r[a];
            r[a] = r[b];
            r[b] = t;
        }

        /**
         * Writes one block record over a rectangle of 4x4 positions ({@code splat_mv_c}).
         *
         * @param row0 the ring row of the top of the rectangle
         * @param bx4  the first column
         * @param bw4  the width
         * @param bh4  the height
         * @param mv0  the first motion vector
         * @param mv1  the second motion vector
         * @param info the packed references, block size and flags
         */
        public void splat(int row0, int bx4, int bw4, int bh4, int mv0, int mv1, int info) {
            var blocks = this.blocks;
            for (var y = 0; y < bh4; y++) {
                var p = (r[row0 + y] + bx4) * BLOCK_INTS;
                for (var x = 0; x < bw4; x++, p += BLOCK_INTS) {
                    blocks[p] = mv0;
                    blocks[p + 1] = mv1;
                    blocks[p + 2] = info;
                }
            }
        }

        /**
         * Points the rows of the ring at the whole-frame block records of the reconstruction pass
         * of two-pass decoding, for the superblock row {@code sby}: ring row {@code i} becomes
         * record row {@code ((sby * sbsz) & ~31) + i - 5}, or {@code -1} outside the records.
         *
         * @param records the whole-frame block records
         * @param stride  the row stride of the records, in blocks
         * @param rows    the number of record rows
         * @param sbsz    the superblock size in 4-sample units
         * @param sby     the superblock row
         */
        void reconInit(int[] records, int stride, int rows, int sbsz, int sby) {
            var base = (sby * sbsz) & ~31;
            for (var i = 0; i < r.length; i++) {
                var row = base + i - 5;
                r[i] = row >= 0 && row < rows ? row * stride : -1;
            }
            this.blocks = records;
        }

        /**
         * Writes the first motion vector and the packed information of a block over its bottom row
         * and right column, the only positions the reconstruction of later blocks reads.
         *
         * @param row0 the ring row of the top of the block
         * @param bx4  the first column
         * @param bw4  the width
         * @param bh4  the height
         * @param mv0  the first motion vector
         * @param info the packed references, block size and flags
         */
        void splatEdges(int row0, int bx4, int bw4, int bh4, int mv0, int info) {
            var blocks = this.blocks;
            var p = (r[row0 + bh4 - 1] + bx4) * BLOCK_INTS;
            for (var x = 0; x < bw4; x++, p += BLOCK_INTS) {
                blocks[p] = mv0;
                blocks[p + 2] = info;
            }
            var right = bx4 + bw4 - 1;
            for (var y = 0; y < bh4 - 1; y++) {
                var q = (r[row0 + y] + right) * BLOCK_INTS;
                blocks[q] = mv0;
                blocks[q + 2] = info;
            }
        }
    }

    /**
     * Holds the candidate list of one block ({@code refmvs_candidate[8]}).
     */
    public static final class Candidates {
        /**
         * The first motion vector of each candidate.
         */
        public final int[] mv0 = new int[8];

        /**
         * The second motion vector of each candidate.
         */
        public final int[] mv1 = new int[8];

        /**
         * The weight of each candidate.
         */
        public final int[] weight = new int[8];

        /**
         * The number of candidates.
         */
        public int count;

        /**
         * The mode context derived with the list.
         */
        public int ctx;

        /**
         * Whether a spatial candidate used a new motion vector.
         */
        int haveNewmv;

        /**
         * Whether a top neighbor matched the references.
         */
        int haveRowMvs;

        /**
         * Whether a left neighbor matched the references.
         */
        int haveColMvs;

        /**
         * The global motion vectors of the two references for warped global motion, or
         * {@link #INVALID_MV}.
         */
        final int[] gmv = new int[2];

        /**
         * The global motion vectors of the two references.
         */
        final int[] tgmv = new int[2];

        /**
         * The number of extended candidates found per reference, then per opposite reference.
         */
        final int[] sameCount = new int[4];

        /**
         * Creates an empty list.
         */
        public Candidates() {
        }

        /**
         * Swaps two candidates.
         *
         * @param a the first index
         * @param b the second index
         */
        private void swap(int a, int b) {
            var t = mv0[a];
            mv0[a] = mv0[b];
            mv0[b] = t;
            t = mv1[a];
            mv1[a] = mv1[b];
            mv1[b] = t;
            t = weight[a];
            weight[a] = weight[b];
            weight[b] = t;
        }
    }

    /**
     * Adds one spatial neighbor to the candidate list.
     *
     * @param c      the list
     * @param weight the weight to add
     * @param blocks the block records
     * @param b      the index of the neighbor's record
     * @param ref0   the first reference plus one
     * @param ref1   the second reference plus one, or {@code -1}
     * @param row    whether the neighbor is in a top row (else a left column)
     */
    private static void addSpatialCandidate(Candidates c, int weight, int[] blocks, int b, int ref0, int ref1,
                                            boolean row) {
        if (ref1 == -1) {
            addSpatialSingle(c, weight, blocks, b, ref0, row);
        } else {
            addSpatialCompound(c, weight, blocks, b, ref0, ref1, row);
        }
    }

    /**
     * Adds one spatial neighbor to the candidate list of a single-reference block.
     *
     * @param c      the list
     * @param weight the weight to add
     * @param blocks the block records
     * @param b      the index of the neighbor's record
     * @param ref0   the reference plus one
     * @param row    whether the neighbor is in a top row (else a left column)
     */
    private static void addSpatialSingle(Candidates c, int weight, int[] blocks, int b, int ref0, boolean row) {
        var bmv0 = blocks[b];
        if (bmv0 == INVALID_MV) {
            return;
        }
        var info = blocks[b + 2];
        int candMv;
        if (ref0(info) == ref0) {
            candMv = bmv0;
        } else if (ref1(info) == ref0) {
            candMv = blocks[b + 1];
        } else {
            return;
        }
        var mf = mf(info);
        if ((mf & 1) != 0 && c.gmv[0] != INVALID_MV) {
            candMv = c.gmv[0];
        }
        if (row) {
            c.haveRowMvs = 1;
        } else {
            c.haveColMvs = 1;
        }
        c.haveNewmv |= mf >> 1;
        var mv0 = c.mv0;
        var last = c.count;
        for (var m = 0; m < last; m++) {
            if (mv0[m] == candMv) {
                c.weight[m] += weight;
                return;
            }
        }
        if (last < 8) {
            mv0[last] = candMv;
            c.weight[last] = weight;
            c.count = last + 1;
        }
    }

    /**
     * Adds one spatial neighbor to the candidate list of a compound block.
     *
     * @param c      the list
     * @param weight the weight to add
     * @param blocks the block records
     * @param b      the index of the neighbor's record
     * @param ref0   the first reference plus one
     * @param ref1   the second reference plus one
     * @param row    whether the neighbor is in a top row (else a left column)
     */
    private static void addSpatialCompound(Candidates c, int weight, int[] blocks, int b, int ref0, int ref1,
                                           boolean row) {
        var bmv0 = blocks[b];
        if (bmv0 == INVALID_MV) {
            return;
        }
        var info = blocks[b + 2];
        if ((info & 0xFFFF) != ((ref0 & 0xFF) | (ref1 & 0xFF) << 8)) {
            return;
        }
        var mf = mf(info);
        var cand0 = (mf & 1) != 0 && c.gmv[0] != INVALID_MV ? c.gmv[0] : bmv0;
        var cand1 = (mf & 1) != 0 && c.gmv[1] != INVALID_MV ? c.gmv[1] : blocks[b + 1];
        if (row) {
            c.haveRowMvs = 1;
        } else {
            c.haveColMvs = 1;
        }
        c.haveNewmv |= mf >> 1;
        var last = c.count;
        for (var n = 0; n < last; n++) {
            if (c.mv0[n] == cand0 && c.mv1[n] == cand1) {
                c.weight[n] += weight;
                return;
            }
        }
        if (last < 8) {
            c.mv0[last] = cand0;
            c.mv1[last] = cand1;
            c.weight[last] = weight;
            c.count = last + 1;
        }
    }

    /**
     * Adds a top neighbor without tracking the new motion vector flag.
     *
     * @param c      the list
     * @param weight the weight to add
     * @param blocks the block records
     * @param b      the index of the neighbor's record
     * @param ref0   the first reference plus one
     * @param ref1   the second reference plus one, or {@code -1}
     * @param row    whether the neighbor is in a top row
     */
    private static void addSecondaryCandidate(Candidates c, int weight, int[] blocks, int b, int ref0, int ref1,
                                              boolean row) {
        var saved = c.haveNewmv;
        addSpatialCandidate(c, weight, blocks, b, ref0, ref1, row);
        c.haveNewmv = saved;
    }

    /**
     * Scans a row of neighbors above the block.
     *
     * @param c         the list
     * @param blocks    the block records
     * @param b         the block index of the first neighbor
     * @param ref0      the first reference plus one
     * @param ref1      the second reference plus one, or {@code -1}
     * @param bw4       the block width
     * @param w4        the visible block width
     * @param maxRows   the number of rows the scan may cover
     * @param step      the minimum stride
     * @param secondary whether this is a secondary scan, which does not set the new motion flag
     * @return the number of rows the scan covered
     */
    private static int scanRow(Candidates c, int[] blocks, int b, int ref0, int ref1, int bw4, int w4,
                               int maxRows, int step, boolean secondary) {
        var cand = b;
        var firstBs = bs(blocks[cand * BLOCK_INTS + 2]);
        var candBw4 = Tables.BLOCK_DIMENSIONS[firstBs * 4];
        var len = Math.max(step, Math.min(bw4, candBw4));
        if (bw4 <= candBw4) {
            var weight = bw4 == 1 ? 2 : Math.max(2, Math.min(2 * maxRows, Tables.BLOCK_DIMENSIONS[firstBs * 4 + 1]));
            if (secondary) {
                addSecondaryCandidate(c, len * weight, blocks, cand * BLOCK_INTS, ref0, ref1, true);
            } else {
                addSpatialCandidate(c, len * weight, blocks, cand * BLOCK_INTS, ref0, ref1, true);
            }
            return weight >> 1;
        }
        for (var x = 0; ; ) {
            if (secondary) {
                addSecondaryCandidate(c, len * 2, blocks, cand * BLOCK_INTS, ref0, ref1, true);
            } else {
                addSpatialCandidate(c, len * 2, blocks, cand * BLOCK_INTS, ref0, ref1, true);
            }
            x += len;
            if (x >= w4) {
                return 1;
            }
            cand = b + x;
            candBw4 = Tables.BLOCK_DIMENSIONS[bs(blocks[cand * BLOCK_INTS + 2]) * 4];
            len = Math.max(step, candBw4);
        }
    }

    /**
     * Scans a column of neighbors left of the block.
     *
     * @param c         the list
     * @param rt        the tile state
     * @param ringRow   the ring row of the first neighbor
     * @param ref0      the first reference plus one
     * @param ref1      the second reference plus one, or {@code -1}
     * @param bh4       the block height
     * @param h4        the visible block height
     * @param bx4       the column of the neighbors
     * @param maxCols   the number of columns the scan may cover
     * @param step      the minimum stride
     * @param secondary whether this is a secondary scan
     * @return the number of columns the scan covered
     */
    private static int scanCol(Candidates c, Tile rt, int ringRow, int ref0, int ref1, int bh4, int h4, int bx4,
                               int maxCols, int step, boolean secondary) {
        var blocks = rt.rf.r;
        var cand = rt.r[ringRow] + bx4;
        var firstBs = bs(blocks[cand * BLOCK_INTS + 2]);
        var candBh4 = Tables.BLOCK_DIMENSIONS[firstBs * 4 + 1];
        var len = Math.max(step, Math.min(bh4, candBh4));
        if (bh4 <= candBh4) {
            var weight = bh4 == 1 ? 2 : Math.max(2, Math.min(2 * maxCols, Tables.BLOCK_DIMENSIONS[firstBs * 4]));
            if (secondary) {
                addSecondaryCandidate(c, len * weight, blocks, cand * BLOCK_INTS, ref0, ref1, false);
            } else {
                addSpatialCandidate(c, len * weight, blocks, cand * BLOCK_INTS, ref0, ref1, false);
            }
            return weight >> 1;
        }
        for (var y = 0; ; ) {
            if (secondary) {
                addSecondaryCandidate(c, len * 2, blocks, cand * BLOCK_INTS, ref0, ref1, false);
            } else {
                addSpatialCandidate(c, len * 2, blocks, cand * BLOCK_INTS, ref0, ref1, false);
            }
            y += len;
            if (y >= h4) {
                return 1;
            }
            cand = rt.r[ringRow + y] + bx4;
            candBh4 = Tables.BLOCK_DIMENSIONS[bs(blocks[cand * BLOCK_INTS + 2]) * 4 + 1];
            len = Math.max(step, candBh4);
        }
    }

    /**
     * Adds one projected temporal candidate.
     *
     * @param rf         the frame state
     * @param c          the list
     * @param rbMv       the projected motion vector
     * @param rbRef      the distance the projected vector spans
     * @param ref0       the first reference plus one
     * @param ref1       the second reference plus one, or {@code -1}
     * @param setGlobal  whether to derive the global motion context from this candidate
     * @param weight     the candidate weight added to its entry
     * @return the global motion context when {@code setGlobal}, otherwise {@code -1}
     */
    private static int addTemporalCandidate(Frame rf, Candidates c, int rbMv, int rbRef, int ref0, int ref1,
                                            boolean setGlobal, int weight) {
        if (rbMv == INVALID_MV) {
            return -1;
        }
        var mv = fixMvPrecision(rf.hdr, mvProjection(rbMv, rf.pocdiff[ref0 - 1], rbRef));
        var last = c.count;
        var result = -1;
        if (ref1 == -1) {
            if (setGlobal) {
                result = (Math.abs(mvX(mv) - mvX(c.tgmv[0])) | Math.abs(mvY(mv) - mvY(c.tgmv[0]))) >= 16 ? 1 : 0;
            }
            for (var n = 0; n < last; n++) {
                if (c.mv0[n] == mv) {
                    c.weight[n] += weight;
                    return result;
                }
            }
            if (last < 8) {
                c.mv0[last] = mv;
                c.weight[last] = weight;
                c.count = last + 1;
            }
        } else {
            var mv1 = fixMvPrecision(rf.hdr, mvProjection(rbMv, rf.pocdiff[ref1 - 1], rbRef));
            for (var n = 0; n < last; n++) {
                if (c.mv0[n] == mv && c.mv1[n] == mv1) {
                    c.weight[n] += weight;
                    return result;
                }
            }
            if (last < 8) {
                c.mv0[last] = mv;
                c.mv1[last] = mv1;
                c.weight[last] = weight;
                c.count = last + 1;
            }
        }
        return result;
    }

    /**
     * Negates a packed motion vector.
     *
     * @param mv the vector
     * @return the negated vector
     */
    private static int negate(int mv) {
        return mv(-mvY(mv), -mvX(mv));
    }

    /**
     * Adds the motion of a neighbor with other references as extended compound candidates.
     *
     * @param c        the list
     * @param same     the index of the first extended slot
     * @param blocks   the block records
     * @param cand     the index of the neighbor's record
     * @param sign0    the sign bias of the first reference
     * @param sign1    the sign bias of the second reference
     * @param ref0     the first reference plus one
     * @param ref1     the second reference plus one
     * @param signBias the sign bias of each reference
     */
    private static void addCompoundExtendedCandidate(Candidates c, int same, int[] blocks, int cand, int sign0,
                                                     int sign1, int ref0, int ref1, byte[] signBias) {
        var diff = same + 2;
        var sc = c.sameCount;
        var info = blocks[cand + 2];
        for (var n = 0; n < 2; n++) {
            var candRef = n == 0 ? ref0(info) : ref1(info);
            if (candRef <= 0) {
                break;
            }
            var candMv = blocks[cand + n];
            if (candRef == ref0) {
                if (sc[0] < 2) {
                    c.mv0[same + sc[0]++] = candMv;
                }
                if (sc[3] < 2) {
                    if ((sign1 ^ signBias[candRef - 1]) != 0) {
                        candMv = negate(candMv);
                    }
                    c.mv1[diff + sc[3]++] = candMv;
                }
            } else if (candRef == ref1) {
                if (sc[1] < 2) {
                    c.mv1[same + sc[1]++] = candMv;
                }
                if (sc[2] < 2) {
                    if ((sign0 ^ signBias[candRef - 1]) != 0) {
                        candMv = negate(candMv);
                    }
                    c.mv0[diff + sc[2]++] = candMv;
                }
            } else {
                var iCandMv = negate(candMv);
                if (sc[2] < 2) {
                    c.mv0[diff + sc[2]++] = (sign0 ^ signBias[candRef - 1]) != 0 ? iCandMv : candMv;
                }
                if (sc[3] < 2) {
                    c.mv1[diff + sc[3]++] = (sign1 ^ signBias[candRef - 1]) != 0 ? iCandMv : candMv;
                }
            }
        }
    }

    /**
     * Adds the motion of a neighbor with other references as extended single-reference candidates.
     *
     * @param c        the list
     * @param blocks   the block records
     * @param cand     the index of the neighbor's record
     * @param sign     the sign bias of the reference
     * @param signBias the sign bias of each reference
     */
    private static void addSingleExtendedCandidate(Candidates c, int[] blocks, int cand, int sign, byte[] signBias) {
        var info = blocks[cand + 2];
        for (var n = 0; n < 2; n++) {
            var candRef = n == 0 ? ref0(info) : ref1(info);
            if (candRef <= 0) {
                break;
            }
            var candMv = blocks[cand + n];
            if ((sign ^ signBias[candRef - 1]) != 0) {
                candMv = negate(candMv);
            }
            var last = c.count;
            var m = 0;
            while (m < last && candMv != c.mv0[m]) {
                m++;
            }
            if (m == last) {
                c.mv0[m] = candMv;
                c.weight[m] = 2;
                c.count = last + 1;
            }
        }
    }

    /**
     * Builds the motion vector candidate list and mode context of a block
     * ({@code dav1d_refmvs_find}).
     *
     * @param rt        the tile state
     * @param c         the list to fill
     * @param ref0      the first reference plus one (0 for intra block copy)
     * @param ref1      the second reference plus one, or {@code -1}
     * @param bs        the block size
     * @param edgeFlags the block's intra edge flags
     * @param by4       the block row in 4-sample units
     * @param bx4       the block column in 4-sample units
     */
    public static void find(Tile rt, Candidates c, int ref0, int ref1, int bs, int edgeFlags, int by4, int bx4) {
        var rf = rt.rf;
        var blocks = rf.r;
        var bw4 = Tables.BLOCK_DIMENSIONS[bs * 4];
        var w4 = Math.min(Math.min(bw4, 16), rt.colEnd - bx4);
        var bh4 = Tables.BLOCK_DIMENSIONS[bs * 4 + 1];
        var h4 = Math.min(Math.min(bh4, 16), rt.rowEnd - by4);
        var hdr = rf.hdr;
        c.count = 0;
        c.haveNewmv = 0;
        c.haveRowMvs = 0;
        c.haveColMvs = 0;
        if (ref0 > 0) {
            var g = hdr.gmv[ref0 - 1];
            c.tgmv[0] = gmv2d(g, bx4, by4, bw4, bh4, hdr);
            c.gmv[0] = g.type > WarpedMotionParams.TRANSLATION ? c.tgmv[0] : INVALID_MV;
        } else {
            c.tgmv[0] = 0;
            c.gmv[0] = INVALID_MV;
        }
        if (ref1 > 0) {
            var g = hdr.gmv[ref1 - 1];
            c.tgmv[1] = gmv2d(g, bx4, by4, bw4, bh4, hdr);
            c.gmv[1] = g.type > WarpedMotionParams.TRANSLATION ? c.tgmv[1] : INVALID_MV;
        }
        var maxRows = 0;
        var nRows = -1;
        var bTop = 0;
        if (by4 > rt.rowStart) {
            maxRows = Math.min((by4 - rt.rowStart + 1) >> 1, 2 + (bh4 > 1 ? 1 : 0));
            bTop = rt.r[(by4 & 31) - 1 + 5] + bx4;
            nRows = scanRow(c, blocks, bTop, ref0, ref1, bw4, w4, maxRows, bw4 >= 16 ? 4 : 1, false);
        }
        var maxCols = 0;
        var nCols = -1;
        var leftRing = (by4 & 31) + 5;
        if (bx4 > rt.colStart) {
            maxCols = Math.min((bx4 - rt.colStart + 1) >> 1, 2 + (bw4 > 1 ? 1 : 0));
            nCols = scanCol(c, rt, leftRing, ref0, ref1, bh4, h4, bx4 - 1, maxCols, bh4 >= 16 ? 4 : 1, false);
        }
        if (nRows != -1 && (edgeFlags & IntraEdge.I444_TOP_HAS_RIGHT) != 0 && Math.max(bw4, bh4) <= 16
                && bw4 + bx4 < rt.colEnd) {
            addSpatialCandidate(c, 4, blocks, (bTop + bw4) * BLOCK_INTS, ref0, ref1, true);
        }
        var nearestMatch = c.haveColMvs + c.haveRowMvs;
        var nearestCnt = c.count;
        for (var n = 0; n < nearestCnt; n++) {
            c.weight[n] += 640;
        }
        var globalmvCtx = hdr.useRefFrameMvs ? 1 : 0;
        if (rf.useRefFrameMvs) {
            var stride = rf.rpStride;
            var by8 = by4 >> 1;
            var bx8 = bx4 >> 1;
            var projMv = rf.rpProj.mv;
            var projRef = rf.rpProj.ref;
            var rbi = rt.rpProj + (by8 & 15) * stride + bx8;
            var rb = rbi;
            var stepH = bw4 >= 16 ? 2 : 1;
            var stepV = bh4 >= 16 ? 2 : 1;
            var w8 = Math.min((w4 + 1) >> 1, 8);
            var h8 = Math.min((h4 + 1) >> 1, 8);
            var run = rb;
            var runLen = 1;
            var setGlobal = true;
            for (var y = 0; y < h8; y += stepV) {
                for (var x = y != 0 ? 0 : stepH; x < w8; x += stepH) {
                    var mvn = projMv[rb + x];
                    if (mvn == projMv[run] && (mvn == INVALID_MV || projRef[rb + x] == projRef[run])) {
                        runLen++;
                        continue;
                    }
                    var g = addTemporalCandidate(rf, c, projMv[run], projRef[run], ref0, ref1, setGlobal, 2 * runLen);
                    if (setGlobal && g >= 0) {
                        globalmvCtx = g;
                    }
                    setGlobal = false;
                    run = rb + x;
                    runLen = 1;
                }
                rb += stride * stepV;
            }
            var g = addTemporalCandidate(rf, c, projMv[run], projRef[run], ref0, ref1, setGlobal, 2 * runLen);
            if (setGlobal && g >= 0) {
                globalmvCtx = g;
            }
            if (Math.min(bw4, bh4) >= 2 && Math.max(bw4, bh4) < 16) {
                var bh8 = bh4 >> 1;
                var bw8 = bw4 >> 1;
                rb = rbi + bh8 * stride;
                var hasBottom = by8 + bh8 < Math.min(rt.rowEnd >> 1, (by8 & ~7) + 8);
                if (hasBottom && bx8 - 1 >= Math.max(rt.colStart >> 1, bx8 & ~7)) {
                    addTemporalCandidate(rf, c, projMv[rb - 1], projRef[rb - 1], ref0, ref1, false, 2);
                }
                if (bx8 + bw8 < Math.min(rt.colEnd >> 1, (bx8 & ~7) + 8)) {
                    if (hasBottom) {
                        addTemporalCandidate(rf, c, projMv[rb + bw8], projRef[rb + bw8], ref0, ref1, false, 2);
                    }
                    if (by8 + bh8 - 1 < Math.min(rt.rowEnd >> 1, (by8 & ~7) + 8)) {
                        addTemporalCandidate(rf, c, projMv[rb + bw8 - stride], projRef[rb + bw8 - stride],
                                ref0, ref1, false, 2);
                    }
                }
            }
        }
        if ((nRows | nCols) != -1) {
            addSecondaryCandidate(c, 4, blocks, (bTop - 1) * BLOCK_INTS, ref0, ref1, true);
        }
        for (var n = 2; n <= 3; n++) {
            if (Integer.compareUnsigned(n, nRows) > 0 && n <= maxRows) {
                nRows += scanRow(c, blocks, rt.r[(((by4 & 31) - 2 * n + 1) | 1) + 5] + (bx4 | 1), ref0, ref1,
                        bw4, w4, 1 + maxRows - n, bw4 >= 16 ? 4 : 2, true);
            }
            if (Integer.compareUnsigned(n, nCols) > 0 && n <= maxCols) {
                nCols += scanCol(c, rt, ((by4 & 31) | 1) + 5, ref0, ref1, bh4, h4, (bx4 - n * 2 + 1) | 1,
                        1 + maxCols - n, bh4 >= 16 ? 4 : 2, true);
            }
        }
        var refMatchCount = c.haveColMvs + c.haveRowMvs;
        int refmvCtx, newmvCtx;
        switch (nearestMatch) {
            case 0 -> {
                refmvCtx = Math.min(2, refMatchCount);
                newmvCtx = refMatchCount > 0 ? 1 : 0;
            }
            case 1 -> {
                refmvCtx = Math.min(refMatchCount * 3, 4);
                newmvCtx = 3 - c.haveNewmv;
            }
            default -> {
                refmvCtx = 5;
                newmvCtx = 5 - c.haveNewmv;
            }
        }
        var len = nearestCnt;
        while (len != 0) {
            var last = 0;
            for (var n = 1; n < len; n++) {
                if (c.weight[n - 1] < c.weight[n]) {
                    c.swap(n - 1, n);
                    last = n;
                }
            }
            len = last;
        }
        len = c.count;
        while (len > nearestCnt) {
            var last = nearestCnt;
            for (var n = nearestCnt + 1; n < len; n++) {
                if (c.weight[n - 1] < c.weight[n]) {
                    c.swap(n - 1, n);
                    last = n;
                }
            }
            len = last;
        }
        if (ref1 > 0) {
            if (c.count < 2) {
                var sign0 = rf.signBias[ref0 - 1];
                var sign1 = rf.signBias[ref1 - 1];
                var sz4 = Math.min(w4, h4);
                var same = c.count;
                var sc = c.sameCount;
                sc[0] = sc[1] = sc[2] = sc[3] = 0;
                if (nRows != -1) {
                    for (var x = 0; x < sz4; ) {
                        var cand = (bTop + x) * BLOCK_INTS;
                        addCompoundExtendedCandidate(c, same, blocks, cand, sign0, sign1, ref0, ref1, rf.signBias);
                        x += Tables.BLOCK_DIMENSIONS[bs(blocks[cand + 2]) * 4];
                    }
                }
                if (nCols != -1) {
                    for (var y = 0; y < sz4; ) {
                        var cand = (rt.r[leftRing + y] + bx4 - 1) * BLOCK_INTS;
                        addCompoundExtendedCandidate(c, same, blocks, cand, sign0, sign1, ref0, ref1, rf.signBias);
                        y += Tables.BLOCK_DIMENSIONS[bs(blocks[cand + 2]) * 4 + 1];
                    }
                }
                var diff = same + 2;
                for (var n = 0; n < 2; n++) {
                    var m = sc[n];
                    if (m >= 2) {
                        continue;
                    }
                    var l = sc[2 + n];
                    var arr = n == 0 ? c.mv0 : c.mv1;
                    if (l != 0) {
                        arr[same + m] = arr[diff];
                        if (++m == 2) {
                            continue;
                        }
                        if (l == 2) {
                            arr[same + 1] = arr[diff + 1];
                            continue;
                        }
                    }
                    do {
                        arr[same + m] = c.tgmv[n];
                    } while (++m < 2);
                }
                var n = c.count;
                if (n == 1 && c.mv0[0] == c.mv0[same] && c.mv1[0] == c.mv1[same]) {
                    c.mv0[1] = c.mv0[2];
                    c.mv1[1] = c.mv1[2];
                }
                do {
                    c.weight[n] = 2;
                } while (++n < 2);
                c.count = 2;
            }
            var left = -(bx4 + bw4 + 4) * 4 * 8;
            var right = (rf.iw4 - bx4 + 4) * 4 * 8;
            var top = -(by4 + bh4 + 4) * 4 * 8;
            var bottom = (rf.ih4 - by4 + 4) * 4 * 8;
            for (var n = 0; n < c.count; n++) {
                c.mv0[n] = mv(Math.clamp(mvY(c.mv0[n]), top, bottom), Math.clamp(mvX(c.mv0[n]), left, right));
                c.mv1[n] = mv(Math.clamp(mvY(c.mv1[n]), top, bottom), Math.clamp(mvX(c.mv1[n]), left, right));
            }
            c.ctx = switch (refmvCtx >> 1) {
                case 0 -> Math.min(newmvCtx, 1);
                case 1 -> 1 + Math.min(newmvCtx, 3);
                default -> Math.clamp(3 + newmvCtx, 4, 7);
            };
            return;
        } else if (c.count < 2 && ref0 > 0) {
            var sign = rf.signBias[ref0 - 1];
            var sz4 = Math.min(w4, h4);
            if (nRows != -1) {
                for (var x = 0; x < sz4 && c.count < 2; ) {
                    var cand = (bTop + x) * BLOCK_INTS;
                    addSingleExtendedCandidate(c, blocks, cand, sign, rf.signBias);
                    x += Tables.BLOCK_DIMENSIONS[bs(blocks[cand + 2]) * 4];
                }
            }
            if (nCols != -1) {
                for (var y = 0; y < sz4 && c.count < 2; ) {
                    var cand = (rt.r[leftRing + y] + bx4 - 1) * BLOCK_INTS;
                    addSingleExtendedCandidate(c, blocks, cand, sign, rf.signBias);
                    y += Tables.BLOCK_DIMENSIONS[bs(blocks[cand + 2]) * 4 + 1];
                }
            }
        }
        var nRefmvs = c.count;
        if (nRefmvs != 0) {
            var left = -(bx4 + bw4 + 4) * 4 * 8;
            var right = (rf.iw4 - bx4 + 4) * 4 * 8;
            var top = -(by4 + bh4 + 4) * 4 * 8;
            var bottom = (rf.ih4 - by4 + 4) * 4 * 8;
            for (var n = 0; n < nRefmvs; n++) {
                c.mv0[n] = mv(Math.clamp(mvY(c.mv0[n]), top, bottom), Math.clamp(mvX(c.mv0[n]), left, right));
            }
        }
        for (var n = c.count; n < 2; n++) {
            c.mv0[n] = c.tgmv[0];
        }
        c.ctx = (refmvCtx << 4) | (globalmvCtx << 3) | newmvCtx;
    }
}
