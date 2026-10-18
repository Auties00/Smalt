package com.github.auties00.smalt.video.h264.internal;

import java.util.Arrays;

/**
 * Holds the per-macroblock syntax state of the picture being decoded that neighboring
 * macroblocks and the deblocking filter read, but later pictures do not.
 *
 * <p>Motion data that later pictures read (temporal direct prediction) lives in
 * {@link DecoderFrame} instead.
 */
final class PictureState {
    /**
     * The number of entries per macroblock in {@link #nnz}: 16 luma, 4 Cb and 4 Cr blocks.
     */
    static final int NNZ_STRIDE = 24;

    /**
     * The width in macroblocks.
     */
    final int mbWidth;

    /**
     * The height in macroblocks.
     */
    final int mbHeight;

    /**
     * The number of the slice each macroblock belongs to; a macroblock is available to another
     * only when both carry the same number, and numbers are never reused, so stale entries of
     * earlier pictures never match.
     */
    final int[] sliceNum;

    /**
     * The luma quantization parameter of each macroblock as the deblocking filter uses it (0 for
     * I_PCM).
     */
    final byte[] qp;

    /**
     * The Cb quantization parameter {@code QPc} of each macroblock for the deblocking filter.
     */
    final byte[] qpCb;

    /**
     * The Cr quantization parameter {@code QPc} of each macroblock for the deblocking filter.
     */
    final byte[] qpCr;

    /**
     * The deblocking parameters of each macroblock's slice:
     * {@code disable_deblocking_filter_idc | (FilterOffsetA + 12) << 2 | (FilterOffsetB + 12) << 8}.
     */
    final int[] filter;

    /**
     * The per-4x4-block coefficient counts ({@code TotalCoeff}) under CAVLC or
     * {@code coded_block_flag} values under CABAC, indexed {@code mbAddr * NNZ_STRIDE + block}
     * with luma blocks in raster order 0 to 15, then Cb blocks 16 to 19 and Cr blocks 20 to 23.
     */
    final byte[] nnz;

    /**
     * The luma 4x4 blocks that contain non-zero coefficients, one bit per raster block index
     * (whole 8x8 quadrants for the 8x8 transform), for the deblocking boundary strength.
     */
    final int[] nonZero;

    /**
     * The Intra4x4PredMode or Intra8x8PredMode of each 4x4 block in raster order, indexed
     * {@code mbAddr * 16 + block}; meaningful only for Intra_NxN macroblocks, whose readers
     * treat every other macroblock as predicting DC (mode 2).
     */
    final byte[] intraModes;

    /**
     * The absolute motion vector differences of each list per 4x4 block, packed
     * {@code min(|y|, 127) << 8 | min(|x|, 127)}, for CABAC context selection; meaningful only
     * for inter macroblocks that are not skipped, whose readers treat every other macroblock as
     * having zero differences.
     */
    final short[][] mvd;

    /**
     * The coded block pattern of each macroblock ({@code CodedBlockPatternLuma} in bits 0 to 3,
     * {@code CodedBlockPatternChroma} in bits 4 and 5) with the DC {@code coded_block_flag}s of
     * luma, Cb and Cr in bits 6, 7 and 8.
     */
    final int[] cbp;

    /**
     * The {@code intra_chroma_pred_mode} of each macroblock.
     */
    final byte[] chromaMode;

    /**
     * Allocates the state of a picture size.
     *
     * @param mbWidth the width in macroblocks
     * @param mbHeight the height in macroblocks
     */
    PictureState(int mbWidth, int mbHeight) {
        this.mbWidth = mbWidth;
        this.mbHeight = mbHeight;
        var count = mbWidth * mbHeight;
        this.sliceNum = new int[count];
        Arrays.fill(sliceNum, -1);
        this.qp = new byte[count];
        this.qpCb = new byte[count];
        this.qpCr = new byte[count];
        this.filter = new int[count];
        this.nnz = new byte[count * NNZ_STRIDE];
        this.nonZero = new int[count];
        this.intraModes = new byte[count * 16];
        this.mvd = new short[2][count * 16];
        this.cbp = new int[count];
        this.chromaMode = new byte[count];
    }
}
