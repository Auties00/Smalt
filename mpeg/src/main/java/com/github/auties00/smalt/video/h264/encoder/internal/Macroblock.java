package com.github.auties00.smalt.video.h264.encoder.internal;

import java.util.Arrays;

/**
 * Holds everything needed to entropy code and reconstruct one macroblock: its type, prediction
 * parameters, motion, quantizer, coded block pattern and quantized levels.
 *
 * <p>Instances are mutable scratch objects reused across macroblocks; mode decision fills a
 * candidate, and the chosen candidate is written to the bitstream and committed to the
 * {@link PictureInfo}. Per-4x4 arrays are in raster order ({@code y4 * 4 + x4}) unless noted.
 */
final class Macroblock {
    /**
     * The macroblock type, one of the {@link MbType} constants.
     */
    int type;

    /**
     * The Intra_16x16 prediction mode.
     */
    int intra16x16Mode;

    /**
     * The Intra_4x4 prediction modes by {@code luma4x4BlkIdx}, or the Intra_8x8 modes by
     * {@code luma8x8BlkIdx} in the first four entries.
     */
    final int[] intraModes = new int[16];

    /**
     * The intra chroma prediction mode.
     */
    int chromaMode;

    /**
     * Whether the 8x8 transform is used.
     */
    boolean transform8x8;

    /**
     * The sub-macroblock types of a {@code P_8x8} macroblock, by {@code mbPartIdx}.
     */
    final int[] subTypes = new int[4];

    /**
     * The reference index of each 8x8 quadrant, in raster order.
     */
    final int[] refs = new int[4];

    /**
     * The horizontal motion vector of each 4x4 block, in quarter samples.
     */
    final int[] mvX = new int[16];

    /**
     * The vertical motion vector of each 4x4 block, in quarter samples.
     */
    final int[] mvY = new int[16];

    /**
     * The horizontal motion vector difference of the partition covering each 4x4 block.
     */
    final int[] mvdX = new int[16];

    /**
     * The vertical motion vector difference of the partition covering each 4x4 block.
     */
    final int[] mvdY = new int[16];

    /**
     * The list 1 reference index of each 8x8 quadrant of a B macroblock, {@code -1} when the quadrant
     * does not use list 1.
     */
    final int[] refs1 = new int[4];

    /**
     * The horizontal list 1 vector of each 4x4 block in raster order, in quarter samples.
     */
    final int[] mvX1 = new int[16];

    /**
     * The vertical list 1 vector of each 4x4 block in raster order, in quarter samples.
     */
    final int[] mvY1 = new int[16];

    /**
     * The horizontal list 1 vector difference of each 4x4 block in raster order.
     */
    final int[] mvdX1 = new int[16];

    /**
     * The vertical list 1 vector difference of each 4x4 block in raster order.
     */
    final int[] mvdY1 = new int[16];

    /**
     * The luma quantization parameter the residual was quantized with.
     */
    int qp;

    /**
     * The coded block pattern: luma bits 0 to 3 by {@code luma8x8BlkIdx}, chroma in bits 4 and 5.
     */
    int cbp;

    /**
     * The Intra_16x16 DC levels in raster order of the 4x4 DC matrix.
     */
    final int[] lumaDc = new int[16];

    /**
     * The number of non-zero Intra_16x16 DC levels.
     */
    int lumaDcCount;

    /**
     * The luma levels in raster order within each block: sixteen 4x4 blocks by
     * {@code luma4x4BlkIdx} with 16 levels each, or four 8x8 blocks by {@code luma8x8BlkIdx}
     * with 64 levels each.
     */
    final int[] luma = new int[256];

    /**
     * The number of non-zero levels of each 4x4 block by {@code luma4x4BlkIdx} (AC levels for
     * Intra_16x16); with the 8x8 transform, each of the four entries of an 8x8 block holds that
     * block's count.
     */
    final int[] lumaCount = new int[16];

    /**
     * The chroma DC levels, Cb in entries 0 to 3 and Cr in 4 to 7.
     */
    final int[] chromaDc = new int[8];

    /**
     * The number of non-zero chroma DC levels of Cb and Cr.
     */
    final int[] chromaDcCount = new int[2];

    /**
     * The chroma AC levels in raster order: Cb blocks 0 to 3 then Cr blocks 0 to 3, 16 levels
     * each with the DC position unused.
     */
    final int[] chroma = new int[128];

    /**
     * The number of non-zero AC levels of each chroma block.
     */
    final int[] chromaCount = new int[8];

    /**
     * The distortion of the candidate, in the metric of the decision that produced it.
     */
    long distortion;

    /**
     * The estimated or exact number of bits of the candidate.
     */
    int bits;

    /**
     * The rate-distortion cost of the candidate.
     */
    long cost;

    /**
     * Constructs an empty macroblock.
     */
    Macroblock() {
    }

    /**
     * Clears the residual: levels, counts and coded block pattern.
     */
    void clearResidual() {
        cbp = 0;
        lumaDcCount = 0;
        Arrays.fill(lumaDc, 0);
        Arrays.fill(luma, 0);
        Arrays.fill(lumaCount, 0);
        Arrays.fill(chromaDc, 0);
        chromaDcCount[0] = 0;
        chromaDcCount[1] = 0;
        Arrays.fill(chroma, 0);
        Arrays.fill(chromaCount, 0);
    }

    /**
     * Clears the motion of an intra macroblock: reference indices become {@code -1} and vectors
     * zero.
     */
    void clearMotion() {
        Arrays.fill(refs, -1);
        Arrays.fill(mvX, 0);
        Arrays.fill(mvY, 0);
        Arrays.fill(mvdX, 0);
        Arrays.fill(mvdY, 0);
        Arrays.fill(refs1, -1);
        Arrays.fill(mvX1, 0);
        Arrays.fill(mvY1, 0);
        Arrays.fill(mvdX1, 0);
        Arrays.fill(mvdY1, 0);
    }

    /**
     * Copies from another macroblock the fields that its commit to the picture tables, its
     * entropy coding and the comparison of candidates read: the type and costs, the prediction
     * syntax of the type, the non-zero counts, and the levels its coded block pattern signals.
     *
     * <p>Fields the source's type and coded block pattern do not use keep stale values; every
     * reader of a decided macroblock consults only the fields listed.
     *
     * @param other the source
     */
    void copyDecisionFrom(Macroblock other) {
        var t = other.type;
        type = t;
        qp = other.qp;
        cbp = other.cbp;
        transform8x8 = other.transform8x8;
        distortion = other.distortion;
        bits = other.bits;
        cost = other.cost;
        if (t == MbType.I_PCM) {
            return;
        }
        if (MbType.isIntra(t)) {
            intra16x16Mode = other.intra16x16Mode;
            chromaMode = other.chromaMode;
            System.arraycopy(other.intraModes, 0, intraModes, 0, 16);
        } else {
            System.arraycopy(other.subTypes, 0, subTypes, 0, 4);
            System.arraycopy(other.refs, 0, refs, 0, 4);
            System.arraycopy(other.mvX, 0, mvX, 0, 16);
            System.arraycopy(other.mvY, 0, mvY, 0, 16);
            System.arraycopy(other.mvdX, 0, mvdX, 0, 16);
            System.arraycopy(other.mvdY, 0, mvdY, 0, 16);
            System.arraycopy(other.refs1, 0, refs1, 0, 4);
            System.arraycopy(other.mvX1, 0, mvX1, 0, 16);
            System.arraycopy(other.mvY1, 0, mvY1, 0, 16);
            System.arraycopy(other.mvdX1, 0, mvdX1, 0, 16);
            System.arraycopy(other.mvdY1, 0, mvdY1, 0, 16);
        }
        if (t == MbType.I16x16) {
            System.arraycopy(other.lumaDc, 0, lumaDc, 0, 16);
            lumaDcCount = other.lumaDcCount;
        }
        if ((other.cbp & 15) != 0) {
            System.arraycopy(other.luma, 0, luma, 0, 256);
        }
        System.arraycopy(other.lumaCount, 0, lumaCount, 0, 16);
        if ((other.cbp >> 4) != 0) {
            System.arraycopy(other.chromaDc, 0, chromaDc, 0, 8);
        }
        chromaDcCount[0] = other.chromaDcCount[0];
        chromaDcCount[1] = other.chromaDcCount[1];
        if ((other.cbp >> 4) == 2) {
            System.arraycopy(other.chroma, 0, chroma, 0, 128);
        }
        System.arraycopy(other.chromaCount, 0, chromaCount, 0, 8);
    }

    /**
     * Sets the motion of one rectangular partition.
     *
     * @param x4 the partition's left column in 4x4 units
     * @param y4 the partition's top row in 4x4 units
     * @param w4 the partition width in 4x4 units
     * @param h4 the partition height in 4x4 units
     * @param mvx the horizontal motion vector
     * @param mvy the vertical motion vector
     * @param mvdx the horizontal motion vector difference
     * @param mvdy the vertical motion vector difference
     */
    void setMotion(int x4, int y4, int w4, int h4, int mvx, int mvy, int mvdx, int mvdy) {
        if (w4 == 4 && h4 == 4) {
            Arrays.fill(mvX, mvx);
            Arrays.fill(mvY, mvy);
            Arrays.fill(mvdX, mvdx);
            Arrays.fill(mvdY, mvdy);
            return;
        }
        for (var y = y4; y < y4 + h4; y++) {
            for (var x = x4; x < x4 + w4; x++) {
                var i = y * 4 + x;
                mvX[i] = mvx;
                mvY[i] = mvy;
                mvdX[i] = mvdx;
                mvdY[i] = mvdy;
            }
        }
    }

    /**
     * Sets the list 1 motion of one rectangular partition.
     *
     * @param x4 the partition's left column in 4x4 units
     * @param y4 the partition's top row in 4x4 units
     * @param w4 the partition width in 4x4 units
     * @param h4 the partition height in 4x4 units
     * @param mvx the horizontal motion vector
     * @param mvy the vertical motion vector
     * @param mvdx the horizontal motion vector difference
     * @param mvdy the vertical motion vector difference
     */
    void setMotion1(int x4, int y4, int w4, int h4, int mvx, int mvy, int mvdx, int mvdy) {
        if (w4 == 4 && h4 == 4) {
            Arrays.fill(mvX1, mvx);
            Arrays.fill(mvY1, mvy);
            Arrays.fill(mvdX1, mvdx);
            Arrays.fill(mvdY1, mvdy);
            return;
        }
        for (var y = y4; y < y4 + h4; y++) {
            for (var x = x4; x < x4 + w4; x++) {
                var i = y * 4 + x;
                mvX1[i] = mvx;
                mvY1[i] = mvy;
                mvdX1[i] = mvdx;
                mvdY1[i] = mvdy;
            }
        }
    }

    /**
     * Copies the references, vectors and vector differences of both lists.
     *
     * @param other the macroblock to copy from
     */
    void copyMotionFrom(Macroblock other) {
        System.arraycopy(other.refs, 0, refs, 0, 4);
        System.arraycopy(other.mvX, 0, mvX, 0, 16);
        System.arraycopy(other.mvY, 0, mvY, 0, 16);
        System.arraycopy(other.mvdX, 0, mvdX, 0, 16);
        System.arraycopy(other.mvdY, 0, mvdY, 0, 16);
        System.arraycopy(other.refs1, 0, refs1, 0, 4);
        System.arraycopy(other.mvX1, 0, mvX1, 0, 16);
        System.arraycopy(other.mvY1, 0, mvY1, 0, 16);
        System.arraycopy(other.mvdX1, 0, mvdX1, 0, 16);
        System.arraycopy(other.mvdY1, 0, mvdY1, 0, 16);
    }
}
