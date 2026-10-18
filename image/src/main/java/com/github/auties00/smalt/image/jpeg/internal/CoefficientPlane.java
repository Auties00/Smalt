package com.github.auties00.smalt.image.jpeg.internal;

/**
 * Holds the quantized DCT coefficients of one component as a grid of blocks.
 *
 * <p>Each block holds 64 coefficients in natural (row-major) order; blocks are stored in raster
 * order, {@link #blocksWide} per row. A read outside the grid yields an all-zero block.
 */
final class CoefficientPlane {
    /**
     * The number of blocks per row.
     */
    final int blocksWide;

    /**
     * The number of block rows.
     */
    final int blocksHigh;

    /**
     * The coefficients, 64 per block.
     */
    final short[] data;

    /**
     * Creates a zeroed plane.
     *
     * @param blocksWide the number of blocks per row
     * @param blocksHigh the number of block rows
     */
    CoefficientPlane(int blocksWide, int blocksHigh) {
        this(blocksWide, blocksHigh, new short[Math.multiplyExact(Math.multiplyExact(blocksWide, blocksHigh), 64)]);
    }

    /**
     * Wraps existing coefficients.
     *
     * @param blocksWide the number of blocks per row
     * @param blocksHigh the number of block rows
     * @param data       the coefficients, at least {@code 64 * blocksWide * blocksHigh} of them
     */
    CoefficientPlane(int blocksWide, int blocksHigh, short[] data) {
        this.blocksWide = blocksWide;
        this.blocksHigh = blocksHigh;
        this.data = data;
    }

    /**
     * Returns whether a block lies inside the grid.
     *
     * @param column the block column
     * @param row    the block row
     * @return {@code true} if the block is stored
     */
    boolean contains(int column, int row) {
        return column >= 0 && row >= 0 && column < blocksWide && row < blocksHigh;
    }

    /**
     * Returns the index of a block's first coefficient.
     *
     * @param column the block column, inside the grid
     * @param row    the block row, inside the grid
     * @return the index into {@link #data}
     */
    int offset(int column, int row) {
        return (row * blocksWide + column) << 6;
    }

    /**
     * Returns the DC coefficient of a block, zero outside the grid.
     *
     * @param column the block column
     * @param row    the block row
     * @return the coefficient
     */
    int dc(int column, int row) {
        return contains(column, row) ? data[offset(column, row)] : 0;
    }
}
