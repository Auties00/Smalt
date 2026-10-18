package com.github.auties00.smalt.video.h264.encoder.internal;

import com.github.auties00.smalt.video.h264.internal.H264Tables;

import java.util.Arrays;

/**
 * Stores the coded parameters of every macroblock of the picture being encoded, the neighbor
 * context of prediction, entropy coding and the deblocking filter.
 *
 * <p>A macroblock's entries are written once, when it is decided, and read by the neighbors
 * decided after it, which the wavefront of {@link EncoderCore} orders; the quantizer is written
 * by the {@link SliceWriter}, since it depends on the raster order, and read only by the
 * deblocking filter once the picture is written. Per-4x4 tables hold 16 entries per macroblock
 * in raster order, per-8x8 tables four.
 */
final class PictureInfo {
    /**
     * The picture width in macroblocks.
     */
    final int mbWidth;

    /**
     * The picture height in macroblocks.
     */
    final int mbHeight;

    /**
     * The macroblock types, {@link MbType} constants.
     */
    final byte[] type;

    /**
     * The luma quantization parameter {@code QPY} of each macroblock.
     */
    final byte[] qp;

    /**
     * The coded block pattern of each macroblock.
     */
    final byte[] cbp;

    /**
     * Whether each macroblock uses the 8x8 transform.
     */
    final boolean[] transform8x8;

    /**
     * The intra chroma prediction mode of each macroblock, {@code 0} for inter macroblocks.
     */
    final byte[] chromaMode;

    /**
     * The DC coded-block flags of each macroblock: bit 0 luma (Intra_16x16), bit 1 Cb, bit 2 Cr.
     */
    final byte[] dcFlags;

    /**
     * The number of non-zero levels of each luma 4x4 block; 16 for {@code I_PCM}.
     */
    final byte[] totalCoeff;

    /**
     * The number of non-zero AC levels of each chroma 4x4 block, Cb blocks 0 to 3 then Cr 0 to 3
     * per macroblock in raster order; 16 for {@code I_PCM}.
     */
    final byte[] chromaTotalCoeff;

    /**
     * The Intra_4x4 or Intra_8x8 prediction mode covering each 4x4 block; {@code 2} (DC) for
     * macroblocks of other types.
     */
    final byte[] intraModes;

    /**
     * The horizontal motion vector of each 4x4 block.
     */
    final short[] mvX;

    /**
     * The vertical motion vector of each 4x4 block.
     */
    final short[] mvY;

    /**
     * The reference index of each 8x8 quadrant, {@code -1} for intra macroblocks.
     */
    final byte[] refs;

    /**
     * The absolute horizontal motion vector difference of each 4x4 block, capped at 64.
     */
    final byte[] mvdX;

    /**
     * The absolute vertical motion vector difference of each 4x4 block, capped at 64.
     */
    final byte[] mvdY;

    /**
     * The list 1 reference index of each 8x8 quadrant of each macroblock, {@code -1} when the
     * quadrant does not use list 1; only B pictures fill it.
     */
    final byte[] refs1;

    /**
     * The horizontal list 1 vector of each 4x4 block of each macroblock.
     */
    final short[] mvX1;

    /**
     * The vertical list 1 vector of each 4x4 block of each macroblock.
     */
    final short[] mvY1;

    /**
     * The clamped absolute horizontal list 1 vector difference of each 4x4 block of each macroblock.
     */
    final byte[] mvdX1;

    /**
     * The clamped absolute vertical list 1 vector difference of each 4x4 block of each macroblock.
     */
    final byte[] mvdY1;

    /**
     * Whether the tables describe a B picture, whose macroblocks carry motion for two lists.
     */
    boolean bPicture;

    /**
     * Allocates the tables of a picture.
     *
     * @param mbWidth the picture width in macroblocks
     * @param mbHeight the picture height in macroblocks
     */
    PictureInfo(int mbWidth, int mbHeight) {
        this.mbWidth = mbWidth;
        this.mbHeight = mbHeight;
        var count = mbWidth * mbHeight;
        this.type = new byte[count];
        this.qp = new byte[count];
        this.cbp = new byte[count];
        this.transform8x8 = new boolean[count];
        this.chromaMode = new byte[count];
        this.dcFlags = new byte[count];
        this.totalCoeff = new byte[count * 16];
        this.chromaTotalCoeff = new byte[count * 8];
        this.intraModes = new byte[count * 16];
        this.mvX = new short[count * 16];
        this.mvY = new short[count * 16];
        this.refs = new byte[count * 4];
        this.mvdX = new byte[count * 16];
        this.mvdY = new byte[count * 16];
        this.refs1 = new byte[count * 4];
        this.mvX1 = new short[count * 16];
        this.mvY1 = new short[count * 16];
        this.mvdX1 = new byte[count * 16];
        this.mvdY1 = new byte[count * 16];
    }

    /**
     * Stores the parameters of a coded macroblock.
     *
     * @param address the macroblock address
     * @param mb the coded macroblock
     * @param qpY the macroblock's {@code QPY}: the quantizer it signaled, or the predicted
     * quantizer when it carried no {@code mb_qp_delta}
     */
    void commit(int address, Macroblock mb, int qpY) {
        var t = mb.type;
        type[address] = (byte) t;
        qp[address] = (byte) qpY;
        cbp[address] = (byte) mb.cbp;
        transform8x8[address] = mb.transform8x8;
        chromaMode[address] = (byte) (MbType.isIntra(t) ? mb.chromaMode : 0);
        var base = address * 16;
        if (t == MbType.I_PCM) {
            dcFlags[address] = 7;
            Arrays.fill(totalCoeff, base, base + 16, (byte) 16);
            Arrays.fill(chromaTotalCoeff, address * 8, address * 8 + 8, (byte) 16);
        } else {
            dcFlags[address] = (byte) ((t == MbType.I16x16 && mb.lumaDcCount != 0 ? 1 : 0)
                    | (mb.chromaDcCount[0] != 0 ? 2 : 0) | (mb.chromaDcCount[1] != 0 ? 4 : 0));
            for (var blk = 0; blk < 16; blk++) {
                totalCoeff[base + H264Tables.BLOCK_RASTER[blk]] = (byte) mb.lumaCount[blk];
            }
            for (var i = 0; i < 8; i++) {
                chromaTotalCoeff[address * 8 + i] = (byte) mb.chromaCount[i];
            }
        }
        if (t == MbType.I4x4) {
            for (var blk = 0; blk < 16; blk++) {
                intraModes[base + H264Tables.BLOCK_RASTER[blk]] = (byte) mb.intraModes[blk];
            }
        } else if (t == MbType.I8x8) {
            for (var r = 0; r < 16; r++) {
                intraModes[base + r] = (byte) mb.intraModes[((r >> 3) << 1) | ((r & 3) >> 1)];
            }
        } else {
            Arrays.fill(intraModes, base, base + 16, (byte) 2);
        }
        if (MbType.isIntra(t)) {
            Arrays.fill(mvX, base, base + 16, (short) 0);
            Arrays.fill(mvY, base, base + 16, (short) 0);
            Arrays.fill(mvdX, base, base + 16, (byte) 0);
            Arrays.fill(mvdY, base, base + 16, (byte) 0);
            Arrays.fill(refs, address * 4, address * 4 + 4, (byte) -1);
            Arrays.fill(mvX1, base, base + 16, (short) 0);
            Arrays.fill(mvY1, base, base + 16, (short) 0);
            Arrays.fill(mvdX1, base, base + 16, (byte) 0);
            Arrays.fill(mvdY1, base, base + 16, (byte) 0);
            Arrays.fill(refs1, address * 4, address * 4 + 4, (byte) -1);
        } else {
            for (var i = 0; i < 16; i++) {
                mvX[base + i] = (short) mb.mvX[i];
                mvY[base + i] = (short) mb.mvY[i];
                mvdX[base + i] = (byte) Math.min(64, Math.abs(mb.mvdX[i]));
                mvdY[base + i] = (byte) Math.min(64, Math.abs(mb.mvdY[i]));
            }
            for (var i = 0; i < 4; i++) {
                refs[address * 4 + i] = (byte) mb.refs[i];
            }
            if (MbType.isB(t)) {
                for (var i = 0; i < 16; i++) {
                    mvX1[base + i] = (short) mb.mvX1[i];
                    mvY1[base + i] = (short) mb.mvY1[i];
                    mvdX1[base + i] = (byte) Math.min(64, Math.abs(mb.mvdX1[i]));
                    mvdY1[base + i] = (byte) Math.min(64, Math.abs(mb.mvdY1[i]));
                }
                for (var i = 0; i < 4; i++) {
                    refs1[address * 4 + i] = (byte) mb.refs1[i];
                }
            }
        }
    }
}
