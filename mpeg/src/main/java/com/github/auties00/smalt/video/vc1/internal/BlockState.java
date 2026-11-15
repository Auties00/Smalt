package com.github.auties00.smalt.video.vc1.internal;

import java.util.Arrays;

/**
 * Holds what neighboring blocks of one plane read from a decoded block of the current picture: the quantized DC and
 * the first row and column of quantized AC coefficients for intra prediction, the quantizer steps for their scaling,
 * whether the block is intra coded, its transform and coded subblocks for the loop filter, and its motion vector.
 *
 * <p>Blocks are 8x8 samples, indexed by row times {@link #columns} plus column. Instances are not thread-safe.
 */
final class BlockState {
    /**
     * The block columns of the plane.
     */
    int columns;

    /**
     * The block rows of the plane.
     */
    int rows;

    /**
     * The quantized DC coefficient of each intra block.
     */
    int[] dc = new int[0];

    /**
     * The quantized AC coefficients of each intra block, 14 per block: the first row (coefficients 1 to 7) then the
     * first column (coefficients 8, 16, ..., 56).
     */
    short[] ac = new short[0];

    /**
     * Whether each block is intra coded.
     */
    boolean[] intra = new boolean[0];

    /**
     * The DC step size each intra block was quantized with.
     */
    int[] dcStep = new int[0];

    /**
     * The AC step size (twice the quantizer, plus HALFQP, minus 1) each intra block was quantized with.
     */
    int[] acStep = new int[0];

    /**
     * The transform of each block, a {@code Vc1Codes.TT_*} value.
     */
    byte[] transform = new byte[0];

    /**
     * The coded 4x4 quadrants of each block: bit 0 top-left, bit 1 top-right, bit 2 bottom-left, bit 3 bottom-right.
     */
    byte[] coded = new byte[0];

    /**
     * The motion vector of each block, horizontal in the low 16 bits, vertical in the high 16 bits, quarter samples.
     */
    int[] mv = new int[0];

    /**
     * Whether the motion vector of each block of an interlace field picture refers to the field of the opposite
     * polarity.
     */
    byte[] opposite = new byte[0];

    /**
     * Sizes the arrays for a picture and clears the per picture state.
     *
     * @param columns the block columns
     * @param rows the block rows
     */
    void reset(int columns, int rows) {
        this.columns = columns;
        this.rows = rows;
        var count = columns * rows;
        if (dc.length < count) {
            dc = new int[count];
            ac = new short[count * 14];
            intra = new boolean[count];
            dcStep = new int[count];
            acStep = new int[count];
            transform = new byte[count];
            coded = new byte[count];
            mv = new int[count];
            opposite = new byte[count];
        }
        Arrays.fill(intra, 0, count, false);
        Arrays.fill(transform, 0, count, (byte) 0);
        Arrays.fill(coded, 0, count, (byte) 0);
        Arrays.fill(mv, 0, count, 0);
        Arrays.fill(opposite, 0, count, (byte) 0);
    }

    /**
     * Packs a motion vector.
     *
     * @param x the horizontal component
     * @param y the vertical component
     * @return the vector, horizontal in the low 16 bits and vertical in the high 16 bits
     */
    static int pack(int x, int y) {
        return (x & 0xFFFF) | (y << 16);
    }

    /**
     * Returns the horizontal component of a packed motion vector.
     *
     * @param packed the vector
     * @return the component, sign extended
     */
    static int mvx(int packed) {
        return (short) packed;
    }

    /**
     * Returns the vertical component of a packed motion vector.
     *
     * @param packed the vector
     * @return the component, sign extended
     */
    static int mvy(int packed) {
        return packed >> 16;
    }
}
