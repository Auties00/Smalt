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
     * B_Skip.
     */
    static final int BSKIP = 6;

    /**
     * B_Direct_16x16.
     */
    static final int BDIRECT = 7;

    /**
     * B_L0_16x16.
     */
    static final int BL0 = 8;

    /**
     * B_L1_16x16.
     */
    static final int BL1 = 9;

    /**
     * B_Bi_16x16.
     */
    static final int BBI = 10;

    /**
     * Returns whether a macroblock type is a skipped type.
     *
     * @param type the macroblock type, one of the constants above
     * @return whether it is {@link #PSKIP} or {@link #BSKIP}
     */
    static boolean skipped(int type) {
        return type == PSKIP || type == BSKIP;
    }

    /**
     * Returns whether a macroblock type takes its motion from spatial direct prediction.
     *
     * @param type the macroblock type, one of the constants above
     * @return whether it is {@link #BSKIP} or {@link #BDIRECT}
     */
    static boolean direct(int type) {
        return type == BSKIP || type == BDIRECT;
    }

    /**
     * Returns whether a macroblock of a type predicts from a reference list.
     *
     * @param type the inter type
     * @param list 0 or 1
     * @return whether the list is used; for the direct types, see {@link #directRef}
     */
    static boolean usesList(int type, int list) {
        return switch (type) {
            case P16X16, PSKIP, BL0 -> list == 0;
            case BL1 -> list == 1;
            case BBI -> true;
            default -> false;
        };
    }

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
     * The list 1 reference index of {@link #BL1} and {@link #BBI} macroblocks.
     */
    int ref1;

    /**
     * The list 1 horizontal motion vector in quarter samples.
     */
    int mvX1;

    /**
     * The list 1 vertical motion vector in quarter samples.
     */
    int mvY1;

    /**
     * The list 1 horizontal motion vector difference.
     */
    int mvdX1;

    /**
     * The list 1 vertical motion vector difference.
     */
    int mvdY1;

    /**
     * The spatial direct reference index of each list, -1 for a list the direct prediction does not use.
     */
    final int[] directRef = new int[2];

    /**
     * The spatial direct horizontal vector of each 8x8 quadrant, list 0 at 0 to 3 and list 1 at 4 to 7.
     */
    final int[] directMvX = new int[8];

    /**
     * The spatial direct vertical vector of each 8x8 quadrant, laid out as {@link #directMvX}.
     */
    final int[] directMvY = new int[8];

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
        ref1 = other.ref1;
        mvX1 = other.mvX1;
        mvY1 = other.mvY1;
        mvdX1 = other.mvdX1;
        mvdY1 = other.mvdY1;
        System.arraycopy(other.directRef, 0, directRef, 0, 2);
        System.arraycopy(other.directMvX, 0, directMvX, 0, 8);
        System.arraycopy(other.directMvY, 0, directMvY, 0, 8);
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
