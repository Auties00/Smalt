package com.github.auties00.smalt.video.av1.internal.encoder;

import java.util.Arrays;

/**
 * Holds the coding decisions of one intra block of a still frame: its luma and chroma modes,
 * transform size and types, chroma-from-luma scales and palettes, as the mode decision chose them
 * and the bitstream writer codes them.
 */
final class StillBlock {
    /**
     * The block size.
     */
    int bs;

    /**
     * The partition the block came from.
     */
    int bp;

    /**
     * The block level of the partition node.
     */
    int bl;

    /**
     * The intra edge flags of the block.
     */
    int edgeFlags;

    /**
     * The luma mode: an intra prediction mode, or {@code FILTER_PRED} for filter intra.
     */
    int yMode;

    /**
     * The luma angle delta, -3 to 3, or the filter intra mode, 0 to 4.
     */
    int yAngle;

    /**
     * The chroma mode, {@code CFL_PRED} included.
     */
    int uvMode;

    /**
     * The chroma angle delta, -3 to 3.
     */
    int uvAngle;

    /**
     * The chroma-from-luma scale of each chroma plane, in 1/8 units, -16 to 16.
     */
    final int[] cflAlpha = new int[2];

    /**
     * The luma transform size.
     */
    int tx;

    /**
     * Whether every coefficient of the block is zero.
     */
    boolean skip;

    /**
     * The transform type of each luma transform block, in coding order.
     */
    int[] txTypes = new int[16];

    /**
     * The number of luma transform blocks.
     */
    int nTypes;

    /**
     * The luma and chroma palette sizes, 0 when the plane uses no palette.
     */
    final int[] palSz = new int[2];

    /**
     * The palette colors: eight luma, eight U and eight V entries.
     */
    final short[] pal = new short[24];

    /**
     * The luma palette indices of the visible area, one per byte in raster order with a stride of
     * the block width, or {@code null}; never written after the decision is made, so copies of the
     * decision may share it.
     */
    byte[] palMapY;

    /**
     * The chroma palette indices of the visible area, one per byte in raster order with a stride
     * of the chroma block width, or {@code null}; never written after the decision is made, so
     * copies of the decision may share it.
     */
    byte[] palMapUv;

    /**
     * Creates an empty decision.
     */
    StillBlock() {
    }

    /**
     * Records a luma transform type.
     *
     * @param txtp the type
     */
    void addType(int txtp) {
        if (nTypes == txTypes.length) {
            txTypes = Arrays.copyOf(txTypes, nTypes * 2);
        }
        txTypes[nTypes++] = txtp;
    }
}
