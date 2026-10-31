package com.github.auties00.smalt.image.jp2.internal;

/**
 * Places one node of an arbitrary decomposition (ISO/IEC 15444-2 annex F) in a tile-component: its area in its own
 * coordinates, where its samples sit in the coefficient buffer, and its children.
 *
 * <p>A split node stores its children low-pass first along each split axis: a joint split puts LL, HL, LH and HH
 * at the top-left, top-right, bottom-left and bottom-right of its area, a horizontal split LX left of HX and a
 * vertical split XL above XH, so reconstructing the children in place and then lifting the node's rows and columns
 * rebuilds the node, as the sub-band layout of ISO/IEC 15444-1 B.5 does for the dyadic decomposition.
 *
 * @param split the split: {@link Decomposition#LEAF}, {@link Decomposition#JOINT}, {@link Decomposition#HORIZONTAL}
 * or {@link Decomposition#VERTICAL}
 * @param x0 the left edge
 * @param y0 the top edge
 * @param x1 the right edge (exclusive)
 * @param y1 the bottom edge (exclusive)
 * @param offsetX the buffer column of the first sample
 * @param offsetY the buffer row of the first sample
 * @param children the children in the order of Tables F.3 and F.4, or {@code null} for a leaf or a resolution node
 * of a lower resolution
 */
record SubbandNode(int split, long x0, long y0, long x1, long y1, int offsetX, int offsetY, SubbandNode[] children) {
    /**
     * {@return the width in samples}
     */
    int width() {
        return (int) (x1 - x0);
    }

    /**
     * {@return the height in samples}
     */
    int height() {
        return (int) (y1 - y0);
    }
}
