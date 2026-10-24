package com.github.auties00.smalt.video.h264.encoder.internal;

/**
 * Holds the decided syntax of one macroblock of the extended engine: its type, prediction, coded
 * block pattern, quantizer and coefficient levels, ready for entropy coding.
 *
 * <p>Levels are stored in coding order. A 4x4 block keeps its 16 levels at {@code 16 * blkIdx}
 * of its component's array ({@code luma4x4BlkIdx} order), the levels of an AC-only block (an
 * Intra_16x16 or chroma AC block) at positions 1 to 15; an 8x8 block keeps its 64 levels at
 * {@code 64 * b8} in 8x8 scan order.
 */
final class WideMb {
    /**
     * Intra_4x4.
     */
    static final int I4X4 = 0;

    /**
     * Intra_8x8.
     */
    static final int I8X8 = 1;

    /**
     * Intra_16x16.
     */
    static final int I16X16 = 2;

    /**
     * I_PCM.
     */
    static final int IPCM = 3;

    /**
     * P_L0_16x16.
     */
    static final int P16X16 = 4;

    /**
     * P_Skip.
     */
    static final int PSKIP = 5;

    /**
     * The macroblock type, one of the constants above.
     */
    int type;

    /**
     * The Intra_16x16 prediction mode, 0 to 3.
     */
    int i16Mode;

    /**
     * The {@code intra_chroma_pred_mode}, 0 to 3, of 4:2:0 and 4:2:2 intra macroblocks.
     */
    int chromaMode;

    /**
     * The Intra_4x4 modes by {@code luma4x4BlkIdx}, or the Intra_8x8 modes by
     * {@code luma8x8BlkIdx} in the first four entries.
     */
    final int[] modes = new int[16];

    /**
     * The predicted Intra_4x4 or Intra_8x8 mode of each block, as {@link #modes} is indexed.
     */
    final int[] predictedModes = new int[16];

    /**
     * The {@code coded_block_pattern}: luma in bits 0 to 3, chroma in bits 4 and 5.
     */
    int cbp;

    /**
     * The quantizer {@code QPY}.
     */
    int qp;

    /**
     * Whether the residual uses the 8x8 transform.
     */
    boolean transform8x8;

    /**
     * The reference index of an inter macroblock.
     */
    int ref;

    /**
     * The horizontal motion vector in quarter samples.
     */
    int mvX;

    /**
     * The vertical motion vector in quarter samples.
     */
    int mvY;

    /**
     * The horizontal motion vector difference.
     */
    int mvdX;

    /**
     * The vertical motion vector difference.
     */
    int mvdY;

    /**
     * The levels of the 4x4 or 8x8 blocks of each component coded like luma.
     */
    final int[][] levels = new int[3][256];

    /**
     * The Intra_16x16 DC levels of each component coded like luma, 16 in coding order.
     */
    final int[][] dc = new int[3][16];

    /**
     * The chroma DC levels of 4:2:0 and 4:2:2 macroblocks, 4 or 8 per component in coding order.
     */
    final int[][] chromaDc = new int[2][8];

    /**
     * The chroma AC levels of 4:2:0 and 4:2:2 macroblocks: 16 per block at {@code 16 * blkIdx},
     * positions 1 to 15.
     */
    final int[][] chromaAc = new int[2][128];

    /**
     * The 4x4 blocks with a non-zero level of each component coded like luma, one bit per
     * {@code luma4x4BlkIdx}; for the 8x8 transform the four bits of each coded 8x8 block.
     */
    final int[] coded = new int[3];

    /**
     * The chroma AC blocks with a non-zero level, one bit per {@code chroma4x4BlkIdx}.
     */
    final int[] codedChroma = new int[2];

    /**
     * Whether each chroma DC block has a non-zero level.
     */
    final boolean[] codedChromaDc = new boolean[2];

    /**
     * Whether the Intra_16x16 DC block of each component has a non-zero level.
     */
    final boolean[] codedDc = new boolean[3];

    /**
     * Whether the macroblock is intra.
     *
     * @return whether the type is an intra type
     */
    boolean intra() {
        return type <= IPCM;
    }

    /**
     * Copies another macroblock's decision into this one.
     *
     * @param other the macroblock to copy
     */
    void copyFrom(WideMb other) {
        type = other.type;
        i16Mode = other.i16Mode;
        chromaMode = other.chromaMode;
        System.arraycopy(other.modes, 0, modes, 0, 16);
        System.arraycopy(other.predictedModes, 0, predictedModes, 0, 16);
        cbp = other.cbp;
        qp = other.qp;
        transform8x8 = other.transform8x8;
        ref = other.ref;
        mvX = other.mvX;
        mvY = other.mvY;
        mvdX = other.mvdX;
        mvdY = other.mvdY;
        for (var c = 0; c < 3; c++) {
            System.arraycopy(other.levels[c], 0, levels[c], 0, 256);
            System.arraycopy(other.dc[c], 0, dc[c], 0, 16);
            coded[c] = other.coded[c];
            codedDc[c] = other.codedDc[c];
        }
        for (var c = 0; c < 2; c++) {
            System.arraycopy(other.chromaDc[c], 0, chromaDc[c], 0, 8);
            System.arraycopy(other.chromaAc[c], 0, chromaAc[c], 0, 128);
            codedChroma[c] = other.codedChroma[c];
            codedChromaDc[c] = other.codedChromaDc[c];
        }
    }
}
