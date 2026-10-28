package com.github.auties00.smalt.video.hevc.internal;

/**
 * Answers the z-scan order availability of ITU-T H.265 6.4.1 for the coding tree block being decoded or
 * encoded: whether a neighboring location precedes the current one in coding order, in the same slice and
 * tile.
 */
public interface NeighborAvailability {
    /**
     * Returns whether a neighboring location is available for the current location.
     *
     * @param xCurr the current luma x
     * @param yCurr the current luma y
     * @param xNb the neighboring luma x
     * @param yNb the neighboring luma y
     * @return {@code true} when the neighbor is coded, in the same slice and in the same tile
     */
    boolean available(int xCurr, int yCurr, int xNb, int yNb);
}
