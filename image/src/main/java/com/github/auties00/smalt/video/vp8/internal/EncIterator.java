/* Ports libwebp 1.6.0 (src/enc/iterator_enc.c, VP8EncIterator). Copyright (c) 2010, Google Inc.; BSD 3-Clause License.
   Modified for Smalt. */
package com.github.auties00.smalt.video.vp8.internal;

import com.github.auties00.smalt.internal.task.ParallelBands;

import java.util.Arrays;

/**
 * Walks the macroblocks of the picture being encoded, holding the current macroblock's source
 * samples, reconstructions, predictions, and neighboring context.
 *
 * <p>Work buffers have rows of {@link Vp8EncDsp#BPS} bytes: luma occupies columns 0 to 15 of 16
 * rows, U columns 16 to 23 and V columns 24 to 31 of the first 8 rows.
 */
final class EncIterator {
    /**
     * The row stride of the work buffers.
     */
    static final int BPS = Vp8EncDsp.BPS;

    /**
     * The size of one work buffer.
     */
    static final int YUV_SIZE = BPS * 16;

    /**
     * The offset of luma in a work buffer.
     */
    static final int Y_OFF = 0;

    /**
     * The offset of U in a work buffer.
     */
    static final int U_OFF = 16;

    /**
     * The offset of V in a work buffer.
     */
    static final int V_OFF = 24;

    /**
     * The offsets of the 16 luma subblocks in a work buffer.
     */
    static final int[] SCAN = {
            0, 4, 8, 12,
            4 * BPS, 4 + 4 * BPS, 8 + 4 * BPS, 12 + 4 * BPS,
            8 * BPS, 4 + 8 * BPS, 8 + 8 * BPS, 12 + 8 * BPS,
            12 * BPS, 4 + 12 * BPS, 8 + 12 * BPS, 12 + 12 * BPS
    };

    /**
     * The offsets of the 8 chroma subblocks, U then V, relative to {@link #U_OFF}.
     */
    static final int[] SCAN_UV = {0, 4, 4 * BPS, 4 + 4 * BPS, 8, 12, 8 + 4 * BPS, 12 + 4 * BPS};

    /**
     * The index in the subblock boundary of the first sample above each subblock.
     */
    private static final int[] TOP_LEFT_I4 = {17, 21, 25, 29, 13, 17, 21, 25, 9, 13, 17, 21, 5, 9, 13, 17};

    /**
     * The encoder being iterated.
     */
    private final Vp8KeyFrameEncoder enc;

    /**
     * The macroblock column.
     */
    int x;

    /**
     * The macroblock row.
     */
    int y;

    /**
     * The source samples of the current macroblock.
     */
    final byte[] yuvIn = new byte[YUV_SIZE];

    /**
     * The reconstruction of the current macroblock.
     */
    byte[] yuvOut = new byte[YUV_SIZE];

    /**
     * A second reconstruction buffer swapped with {@link #yuvOut}.
     */
    byte[] yuvOut2 = new byte[YUV_SIZE];

    /**
     * The candidate predictions.
     */
    final byte[] yuvP = new byte[EncPredict.PRED_SIZE];

    /**
     * The index of the current macroblock's token partition.
     */
    int part;

    /**
     * The index in the encoder's mode map of the current macroblock's top-left subblock.
     */
    int preds;

    /**
     * The index in the encoder's nonzero map of the current macroblock.
     */
    int nz;

    /**
     * The index of the current macroblock in the encoder's per-macroblock arrays.
     */
    int mb;

    /**
     * The boundary samples of the current subblock: left column bottom to top, corner, above and
     * above-right samples.
     */
    final byte[] i4Boundary = new byte[37];

    /**
     * The index in {@link #i4Boundary} of the first sample above the current subblock.
     */
    int i4Top;

    /**
     * The current subblock.
     */
    int i4;

    /**
     * The above nonzero contexts: luma 0 to 3, U 4 and 5, V 6 and 7, Y2 8.
     */
    final int[] topNz = new int[9];

    /**
     * The left nonzero contexts, same layout as {@link #topNz}.
     */
    final int[] leftNz = new int[9];

    /**
     * Whether trellis quantization is used.
     */
    boolean doTrellis;

    /**
     * The number of macroblocks left.
     */
    int countDown;

    /**
     * The left chroma DC diffusion errors, {@code [plane][top/left]}.
     */
    final int[] leftDerr = new int[4];

    /**
     * The left luma samples; index 0 is the corner, 1 to 16 the column.
     */
    final byte[] yLeft = new byte[17];

    /**
     * The left U samples; index 0 is the corner, 1 to 8 the column.
     */
    final byte[] uLeft = new byte[9];

    /**
     * The left V samples; index 0 is the corner, 1 to 8 the column.
     */
    final byte[] vLeft = new byte[9];

    /**
     * The array holding the luma samples above the current macroblock.
     */
    byte[] yTop;

    /**
     * The index in {@link #yTop} of the first sample above.
     */
    int yTopOff;

    /**
     * The array holding the chroma samples above (8 U then 8 V).
     */
    byte[] uvTop;

    /**
     * The index in {@link #uvTop} of the first sample above.
     */
    int uvTopOff;

    /**
     * Creates an iterator over an encoder's picture.
     *
     * @param enc the encoder
     */
    EncIterator(Vp8KeyFrameEncoder enc) {
        this.enc = enc;
    }

    /**
     * Restarts the iteration at the first macroblock and clears the top contexts, like
     * {@code VP8IteratorInit}.
     */
    void init() {
        setRow(0);
        countDown = enc.mbW * enc.mbH;
        Arrays.fill(enc.yTop, (byte) 127);
        Arrays.fill(enc.uvTop, (byte) 127);
        Arrays.fill(enc.nz, 1, 1 + enc.mbW, 0);
        if (enc.topDerr != null) {
            Arrays.fill(enc.topDerr, 0);
        }
        doTrellis = false;
    }

    /**
     * Moves to the first macroblock of a row.
     *
     * @param row the macroblock row
     */
    void setRow(int row) {
        x = 0;
        y = row;
        part = row & (enc.numParts - 1);
        preds = enc.predsBase + row * 4 * enc.predsW;
        nz = 1;
        mb = row * enc.mbW;
        yTop = enc.yTop;
        yTopOff = 0;
        uvTop = enc.uvTop;
        uvTopOff = 0;
        initLeft();
    }

    /**
     * Resets the left context at the start of a row.
     */
    private void initLeft() {
        var corner = (byte) (y > 0 ? 129 : 127);
        yLeft[0] = corner;
        uLeft[0] = corner;
        vLeft[0] = corner;
        Arrays.fill(yLeft, 1, 17, (byte) 129);
        Arrays.fill(uLeft, 1, 9, (byte) 129);
        Arrays.fill(vLeft, 1, 9, (byte) 129);
        leftNz[8] = 0;
        if (enc.topDerr != null) {
            Arrays.fill(leftDerr, 0);
        }
    }

    /**
     * Copies the current macroblock's source samples into {@link #yuvIn}, replicating the last
     * row and column past the picture edge.
     *
     * @param tmp32 when not {@code null}, also imports the source boundary samples into the left
     *              arrays and this 32-byte top scratch (used by the analysis pass)
     */
    void importSamples(byte[] tmp32) {
        var pic = enc.picture;
        var ys = pic.yStride();
        var uvs = pic.uvStride();
        var ySrc = y * 16 * ys + x * 16;
        var uvSrc = y * 8 * uvs + x * 8;
        var w = Math.min(pic.width() - x * 16, 16);
        var h = Math.min(pic.height() - y * 16, 16);
        var uvW = (w + 1) >> 1;
        var uvH = (h + 1) >> 1;
        importBlock(pic.y(), ySrc, ys, yuvIn, Y_OFF, w, h, 16);
        importBlock(pic.u(), uvSrc, uvs, yuvIn, U_OFF, uvW, uvH, 8);
        importBlock(pic.v(), uvSrc, uvs, yuvIn, V_OFF, uvW, uvH, 8);
        if (tmp32 == null) {
            return;
        }
        if (x == 0) {
            initLeft();
        } else {
            if (y == 0) {
                yLeft[0] = 127;
                uLeft[0] = 127;
                vLeft[0] = 127;
            } else {
                yLeft[0] = pic.y()[ySrc - 1 - ys];
                uLeft[0] = pic.u()[uvSrc - 1 - uvs];
                vLeft[0] = pic.v()[uvSrc - 1 - uvs];
            }
            importLine(pic.y(), ySrc - 1, ys, yLeft, 1, h, 16);
            importLine(pic.u(), uvSrc - 1, uvs, uLeft, 1, uvH, 8);
            importLine(pic.v(), uvSrc - 1, uvs, vLeft, 1, uvH, 8);
        }
        yTop = tmp32;
        yTopOff = 0;
        uvTop = tmp32;
        uvTopOff = 16;
        if (y == 0) {
            Arrays.fill(tmp32, (byte) 127);
        } else {
            importLine(pic.y(), ySrc - ys, 1, tmp32, 0, w, 16);
            importLine(pic.u(), uvSrc - uvs, 1, tmp32, 16, uvW, 8);
            importLine(pic.v(), uvSrc - uvs, 1, tmp32, 24, uvW, 8);
        }
    }

    /**
     * Copies a block, replicating its last column and row up to the block size.
     *
     * @param src       the source plane
     * @param srcOff    the index of the first source sample
     * @param srcStride the source row stride
     * @param dst       the work buffer
     * @param dstOff    the index of the first destination sample
     * @param w         the valid width
     * @param h         the valid height
     * @param size      the block size
     */
    private static void importBlock(byte[] src, int srcOff, int srcStride, byte[] dst, int dstOff, int w, int h, int size) {
        for (var i = 0; i < h; i++) {
            var d = dstOff + i * BPS;
            System.arraycopy(src, srcOff + i * srcStride, dst, d, w);
            if (w < size) {
                Arrays.fill(dst, d + w, d + size, dst[d + w - 1]);
            }
        }
        for (var i = h; i < size; i++) {
            System.arraycopy(dst, dstOff + (i - 1) * BPS, dst, dstOff + i * BPS, size);
        }
    }

    /**
     * Copies a line of samples with a step, replicating the last one up to the total length.
     *
     * @param src      the source plane
     * @param srcOff   the index of the first source sample
     * @param step     the distance between source samples
     * @param dst      the destination
     * @param dstOff   the index of the first destination sample
     * @param len      the valid length
     * @param totalLen the total length
     */
    private static void importLine(byte[] src, int srcOff, int step, byte[] dst, int dstOff, int len, int totalLen) {
        var i = 0;
        for (; i < len; i++, srcOff += step) {
            dst[dstOff + i] = src[srcOff];
        }
        for (; i < totalLen; i++) {
            dst[dstOff + i] = dst[dstOff + len - 1];
        }
    }

    /**
     * Unpacks the current macroblock's neighboring nonzero flags into {@link #topNz} and
     * {@link #leftNz}, like {@code VP8IteratorNzToBytes}.
     */
    void nzToBytes() {
        var tnz = enc.nz[nz];
        var lnz = enc.nz[nz - 1];
        topNz[0] = (tnz >>> 12) & 1;
        topNz[1] = (tnz >>> 13) & 1;
        topNz[2] = (tnz >>> 14) & 1;
        topNz[3] = (tnz >>> 15) & 1;
        topNz[4] = (tnz >>> 18) & 1;
        topNz[5] = (tnz >>> 19) & 1;
        topNz[6] = (tnz >>> 22) & 1;
        topNz[7] = (tnz >>> 23) & 1;
        topNz[8] = (tnz >>> 24) & 1;
        leftNz[0] = (lnz >>> 3) & 1;
        leftNz[1] = (lnz >>> 7) & 1;
        leftNz[2] = (lnz >>> 11) & 1;
        leftNz[3] = (lnz >>> 15) & 1;
        leftNz[4] = (lnz >>> 17) & 1;
        leftNz[5] = (lnz >>> 19) & 1;
        leftNz[6] = (lnz >>> 21) & 1;
        leftNz[7] = (lnz >>> 23) & 1;
    }

    /**
     * Packs {@link #topNz} and {@link #leftNz} into the current macroblock's nonzero word, like
     * {@code VP8IteratorBytesToNz}.
     */
    void bytesToNz() {
        var v = 0;
        v |= (topNz[0] << 12) | (topNz[1] << 13);
        v |= (topNz[2] << 14) | (topNz[3] << 15);
        v |= (topNz[4] << 18) | (topNz[5] << 19);
        v |= (topNz[6] << 22) | (topNz[7] << 23);
        v |= topNz[8] << 24;
        v |= (leftNz[0] << 3) | (leftNz[1] << 7);
        v |= leftNz[2] << 11;
        v |= (leftNz[4] << 17) | (leftNz[6] << 21);
        enc.nz[nz] = v;
    }

    /**
     * Saves the bottom row and right column of the reconstruction as the context of the next
     * macroblocks.
     */
    void saveBoundary() {
        var out = yuvOut;
        if (x < enc.mbW - 1) {
            for (var i = 0; i < 16; i++) {
                yLeft[1 + i] = out[Y_OFF + 15 + i * BPS];
            }
            for (var i = 0; i < 8; i++) {
                uLeft[1 + i] = out[U_OFF + 7 + i * BPS];
                vLeft[1 + i] = out[U_OFF + 15 + i * BPS];
            }
            yLeft[0] = yTop[yTopOff + 15];
            uLeft[0] = uvTop[uvTopOff + 7];
            vLeft[0] = uvTop[uvTopOff + 15];
        }
        if (y < enc.mbH - 1) {
            System.arraycopy(out, Y_OFF + 15 * BPS, yTop, yTopOff, 16);
            System.arraycopy(out, U_OFF + 7 * BPS, uvTop, uvTopOff, 16);
        }
    }

    /**
     * Advances to the next macroblock, offering the carrier of a virtual calling thread to others
     * at the start of each row once the encode has run long (see
     * {@link ParallelBands#checkpoint()}).
     *
     * @return {@code true} while macroblocks remain
     */
    boolean next() {
        if (++x == enc.mbW) {
            setRow(++y);
            ParallelBands.checkpoint();
        } else {
            preds += 4;
            mb += 1;
            nz += 1;
            yTopOff += 16;
            uvTopOff += 16;
        }
        return 0 < --countDown;
    }

    /**
     * Records a 16x16 luma mode for the current macroblock.
     *
     * @param mode the mode, in libwebp order
     */
    void setIntra16Mode(int mode) {
        var p = preds;
        for (var i = 0; i < 4; i++, p += enc.predsW) {
            Arrays.fill(enc.preds, p, p + 4, (byte) mode);
        }
        enc.mbType[mb] = 1;
    }

    /**
     * Records the 16 subblock modes of the current macroblock.
     *
     * @param modes the modes, in libwebp order
     */
    void setIntra4Mode(int[] modes) {
        var p = preds;
        for (var i = 0; i < 4; i++, p += enc.predsW) {
            for (var k = 0; k < 4; k++) {
                enc.preds[p + k] = (byte) modes[i * 4 + k];
            }
        }
        enc.mbType[mb] = 0;
    }

    /**
     * Starts iterating the subblocks of the current macroblock, like
     * {@code VP8IteratorStartI4}.
     */
    void startI4() {
        i4 = 0;
        i4Top = TOP_LEFT_I4[0];
        for (var i = 0; i < 17; i++) {
            i4Boundary[i] = yLeft[16 - i];
        }
        for (var i = 0; i < 16; i++) {
            i4Boundary[17 + i] = yTop[yTopOff + i];
        }
        if (x < enc.mbW - 1) {
            for (var i = 16; i < 20; i++) {
                i4Boundary[17 + i] = yTop[yTopOff + i];
            }
        } else {
            for (var i = 16; i < 20; i++) {
                i4Boundary[17 + i] = i4Boundary[17 + 15];
            }
        }
        nzToBytes();
    }

    /**
     * Moves to the next subblock, updating the boundary with the reconstruction of the current
     * one, like {@code VP8IteratorRotateI4}.
     *
     * @param out    the buffer holding the reconstructed subblocks
     * @param outOff the index of the luma block in {@code out}
     * @return {@code true} while subblocks remain
     */
    boolean rotateI4(byte[] out, int outOff) {
        var blk = outOff + SCAN[i4];
        var top = i4Top;
        for (var i = 0; i <= 3; i++) {
            i4Boundary[top - 4 + i] = out[blk + i + 3 * BPS];
        }
        if ((i4 & 3) != 3) {
            for (var i = 0; i <= 2; i++) {
                i4Boundary[top + i] = out[blk + 3 + (2 - i) * BPS];
            }
        } else {
            for (var i = 0; i <= 3; i++) {
                i4Boundary[top + i] = i4Boundary[top + i + 4];
            }
        }
        i4++;
        if (i4 == 16) {
            return false;
        }
        i4Top = TOP_LEFT_I4[i4];
        return true;
    }

    /**
     * Positions the iterator on a macroblock without touching the left context, which the caller
     * restores with {@link #loadLeft(EncIterator)} when the macroblock is not the first of its row.
     *
     * @param row the macroblock row
     * @param col the macroblock column
     */
    void seek(int row, int col) {
        setRow(row);
        x = col;
        preds += 4 * col;
        mb += col;
        nz += col;
        yTopOff += 16 * col;
        uvTopOff += 16 * col;
    }

    /**
     * Moves to the next macroblock of the current row.
     */
    void advance() {
        x++;
        preds += 4;
        mb += 1;
        nz += 1;
        yTopOff += 16;
        uvTopOff += 16;
    }

    /**
     * Copies the left context of this iterator to another.
     *
     * @param dst the iterator to copy to
     */
    void saveLeft(EncIterator dst) {
        System.arraycopy(yLeft, 0, dst.yLeft, 0, yLeft.length);
        System.arraycopy(uLeft, 0, dst.uLeft, 0, uLeft.length);
        System.arraycopy(vLeft, 0, dst.vLeft, 0, vLeft.length);
        System.arraycopy(leftNz, 0, dst.leftNz, 0, leftNz.length);
        System.arraycopy(leftDerr, 0, dst.leftDerr, 0, leftDerr.length);
    }

    /**
     * Copies the left context of another iterator to this one.
     *
     * @param src the iterator to copy from
     */
    void loadLeft(EncIterator src) {
        src.saveLeft(this);
    }

    /**
     * Swaps {@link #yuvOut} and {@link #yuvOut2}.
     */
    void swapOut() {
        var t = yuvOut;
        yuvOut = yuvOut2;
        yuvOut2 = t;
    }
}
