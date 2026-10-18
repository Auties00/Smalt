package com.github.auties00.smalt.video.h264.encoder.internal;

/**
 * Describes the position of the macroblock being coded and the availability of its neighbors
 * (clause 6.4.9): {@code A} to the left, {@code B} above, {@code C} above-right and {@code D}
 * above-left. A neighbor is available when it lies inside the picture and in the current slice.
 */
final class MbContext {
    /**
     * The macroblock column.
     */
    int mbX;

    /**
     * The macroblock row.
     */
    int mbY;

    /**
     * The macroblock address.
     */
    int address;

    /**
     * Whether the left neighbor is available.
     */
    boolean availA;

    /**
     * Whether the neighbor above is available.
     */
    boolean availB;

    /**
     * Whether the neighbor above-right is available.
     */
    boolean availC;

    /**
     * Whether the neighbor above-left is available.
     */
    boolean availD;

    /**
     * The address of the left neighbor.
     */
    int addrA;

    /**
     * The address of the neighbor above.
     */
    int addrB;

    /**
     * The address of the neighbor above-right.
     */
    int addrC;

    /**
     * The address of the neighbor above-left.
     */
    int addrD;

    /**
     * Constructs a context.
     */
    MbContext() {
    }

    /**
     * Moves the context to a macroblock.
     *
     * @param mbX the macroblock column
     * @param mbY the macroblock row
     * @param mbWidth the picture width in macroblocks
     * @param sliceStart the address of the first macroblock of the current slice
     */
    void moveTo(int mbX, int mbY, int mbWidth, int sliceStart) {
        this.mbX = mbX;
        this.mbY = mbY;
        this.address = mbY * mbWidth + mbX;
        this.addrA = address - 1;
        this.addrB = address - mbWidth;
        this.addrC = addrB + 1;
        this.addrD = addrB - 1;
        this.availA = mbX > 0 && addrA >= sliceStart;
        this.availB = mbY > 0 && addrB >= sliceStart;
        this.availC = mbY > 0 && mbX < mbWidth - 1 && addrC >= sliceStart;
        this.availD = mbY > 0 && mbX > 0 && addrD >= sliceStart;
    }
}
