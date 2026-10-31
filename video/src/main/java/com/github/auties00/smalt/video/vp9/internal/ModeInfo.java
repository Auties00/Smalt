package com.github.auties00.smalt.video.vp9.internal;

/**
 * Holds the mode information of one coded block, the {@code MODE_INFO} of the specification.
 *
 * <p>The decoder keeps one instance per 8x8 unit of the frame and reuses them from frame to
 * frame; the grid of the frame points every unit a block covers at the instance of the block's
 * top-left unit. Motion vectors are packed into one {@code int}, the row in the high 16 bits and
 * the column in the low 16 bits, so that two vectors compare equal exactly when their packed
 * values do: {@code row = mv >> 16}, {@code col = (short) mv}.
 *
 * @implNote This implementation follows libvpx's {@code MODE_INFO}
 *           ({@code vp9/common/vp9_blockd.h}, BSD-3).
 */
final class ModeInfo {
    /**
     * The block size.
     */
    int sbType;

    /**
     * The luma prediction mode; for blocks smaller than 8x8, the mode of the last sub-block.
     */
    int mode;

    /**
     * The luma transform size.
     */
    int txSize;

    /**
     * Whether the block has no residual, {@code 0} or {@code 1}.
     */
    int skip;

    /**
     * The segment.
     */
    int segmentId;

    /**
     * Whether the segment was predicted from the previous segmentation map, {@code 0} or
     * {@code 1}.
     */
    int segIdPredicted;

    /**
     * The chroma intra prediction mode.
     */
    int uvMode;

    /**
     * The interpolation filter; {@link Vp9Constants#SWITCHABLE_FILTERS} for intra blocks of inter
     * frames.
     */
    int interpFilter;

    /**
     * The first reference frame, {@link Vp9Constants#INTRA_FRAME} for intra blocks.
     */
    int ref0;

    /**
     * The second reference frame, or {@link Vp9Constants#NO_REF_FRAME}.
     */
    int ref1;

    /**
     * The motion vector of the first reference, packed.
     */
    int mv0;

    /**
     * The motion vector of the second reference, packed.
     */
    int mv1;

    /**
     * The modes of the four 4x4 sub-blocks of blocks smaller than 8x8, in raster order: the intra
     * modes of an intra block, and, in the encoder, the inter modes of an inter block.
     */
    final int[] subModes = new int[4];

    /**
     * The first-reference motion vectors of the four sub-blocks, packed.
     */
    final int[] subMv0 = new int[4];

    /**
     * The second-reference motion vectors of the four sub-blocks, packed.
     */
    final int[] subMv1 = new int[4];

    /**
     * Creates mode information with zero fields.
     */
    ModeInfo() {
    }

    /**
     * Returns whether the block is inter predicted.
     *
     * @return {@code true} when the first reference is not the intra frame
     */
    boolean isInter() {
        return ref0 > Vp9Constants.INTRA_FRAME;
    }

    /**
     * Returns whether the block averages two predictions.
     *
     * @return {@code true} when the second reference is set
     */
    boolean hasSecondRef() {
        return ref1 > Vp9Constants.INTRA_FRAME;
    }

    /**
     * Returns the luma intra mode of a sub-block, or of the block when it is not smaller than
     * 8x8 ({@code get_y_mode}).
     *
     * @param block the sub-block index, from 0 to 3
     * @return the mode
     */
    int yMode(int block) {
        return sbType < Vp9Constants.BLOCK_8X8 ? subModes[block] : mode;
    }

    /**
     * Packs a motion vector.
     *
     * @param row the vertical component
     * @param col the horizontal component
     * @return the packed vector
     */
    static int packMv(int row, int col) {
        return (row << 16) | (col & 0xFFFF);
    }
}
