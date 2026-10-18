package com.github.auties00.smalt.video.av1.internal;

/**
 * Describes, for every block of the recursive partition of a superblock, which of its top-right
 * and bottom-left neighbors are already decoded and so available to intra prediction.
 *
 * <p>Each node of the tree carries the edge flags ({@code IpredDsp.EDGE_*}) of the blocks a
 * partition of its square produces; the roots {@link #SB128} and {@link #SB64} cover the two
 * superblock sizes.
 *
 * @implNote This implementation ports dav1d's {@code src/intra_edge.c}, building the same node
 *           tree as objects instead of offset-linked structures.
 */
public final class IntraEdge {
    /**
     * The flag of a top-right neighbor available at 4:4:4.
     */
    public static final int I444_TOP_HAS_RIGHT = 1;

    /**
     * The flag of a top-right neighbor available at 4:2:2.
     */
    public static final int I422_TOP_HAS_RIGHT = 1 << 1;

    /**
     * The flag of a top-right neighbor available at 4:2:0.
     */
    public static final int I420_TOP_HAS_RIGHT = 1 << 2;

    /**
     * The flag of a bottom-left neighbor available at 4:4:4.
     */
    public static final int I444_LEFT_HAS_BOTTOM = 1 << 3;

    /**
     * The flag of a bottom-left neighbor available at 4:2:2.
     */
    public static final int I422_LEFT_HAS_BOTTOM = 1 << 4;

    /**
     * The flag of a bottom-left neighbor available at 4:2:0.
     */
    public static final int I420_LEFT_HAS_BOTTOM = 1 << 5;

    /**
     * The flags of a top-right neighbor available at every subsampling.
     */
    public static final int ALL_TOP_HAS_RIGHT = I444_TOP_HAS_RIGHT | I422_TOP_HAS_RIGHT | I420_TOP_HAS_RIGHT;

    /**
     * The flags of a bottom-left neighbor available at every subsampling.
     */
    public static final int ALL_LEFT_HAS_BOTTOM = I444_LEFT_HAS_BOTTOM | I422_LEFT_HAS_BOTTOM | I420_LEFT_HAS_BOTTOM;

    /**
     * Every flag.
     */
    public static final int ALL_TR_AND_BL = ALL_TOP_HAS_RIGHT | ALL_LEFT_HAS_BOTTOM;

    /**
     * The root for 128x128 superblocks.
     */
    public static final Node SB128 = new Node();

    /**
     * The root for 64x64 superblocks.
     */
    public static final Node SB64 = new Node();

    static {
        initModeNode(SB128, 0, true, false);
        initModeNode(SB64, 1, true, false);
    }

    /**
     * Prevents instantiation.
     */
    private IntraEdge() {
        throw new AssertionError();
    }

    /**
     * One node of the edge tree: a square at some block level.
     */
    public static final class Node {
        /**
         * The flags of the unpartitioned block.
         */
        public int o;

        /**
         * The flags of the top half of a horizontal partition.
         */
        public int h0;

        /**
         * The flags of the bottom half of a horizontal partition.
         */
        public int h1;

        /**
         * The flags of the left half of a vertical partition.
         */
        public int v0;

        /**
         * The flags of the right half of a vertical partition.
         */
        public int v1;

        /**
         * The flags of the second quarter of a four-way horizontal partition (branch nodes).
         */
        public int h4;

        /**
         * The flags of the second quarter of a four-way vertical partition (branch nodes).
         */
        public int v4;

        /**
         * The flags of the three last 4x4 blocks of a split 8x8 square (tip nodes).
         */
        public final int[] split = new int[3];

        /**
         * The four quadrants of a split square (branch nodes), or {@code null} at the 8x8 level.
         */
        public Node[] children;

        /**
         * Creates an empty node.
         */
        Node() {
        }
    }

    /**
     * Fills the flags of one node.
     *
     * @param node      the node to fill
     * @param bl        its block level ({@code BlockLevel})
     * @param edgeFlags the flags of the square itself
     */
    private static void initEdges(Node node, int bl, int edgeFlags) {
        node.o = edgeFlags;
        node.h0 = edgeFlags | ALL_LEFT_HAS_BOTTOM;
        node.v0 = edgeFlags | ALL_TOP_HAS_RIGHT;
        if (bl == 4) {
            node.h1 = edgeFlags & (ALL_LEFT_HAS_BOTTOM | I420_TOP_HAS_RIGHT);
            node.v1 = edgeFlags & (ALL_TOP_HAS_RIGHT | I420_LEFT_HAS_BOTTOM | I422_LEFT_HAS_BOTTOM);
            node.split[0] = (edgeFlags & ALL_TOP_HAS_RIGHT) | I422_LEFT_HAS_BOTTOM;
            node.split[1] = edgeFlags | I444_TOP_HAS_RIGHT;
            node.split[2] = edgeFlags & (I420_TOP_HAS_RIGHT | I420_LEFT_HAS_BOTTOM | I422_LEFT_HAS_BOTTOM);
        } else {
            node.h1 = edgeFlags & ALL_LEFT_HAS_BOTTOM;
            node.v1 = edgeFlags & ALL_TOP_HAS_RIGHT;
            node.h4 = ALL_LEFT_HAS_BOTTOM;
            node.v4 = ALL_TOP_HAS_RIGHT;
            if (bl == 3) {
                node.h4 |= edgeFlags & I420_TOP_HAS_RIGHT;
                node.v4 |= edgeFlags & (I420_LEFT_HAS_BOTTOM | I422_LEFT_HAS_BOTTOM);
            }
        }
    }

    /**
     * Fills a branch node and its subtree.
     *
     * @param node          the branch node to fill
     * @param bl            its block level ({@code BlockLevel}), from 0 to 3
     * @param topHasRight   whether the square's top-right neighbor is available
     * @param leftHasBottom whether the square's bottom-left neighbor is available
     */
    private static void initModeNode(Node node, int bl, boolean topHasRight, boolean leftHasBottom) {
        initEdges(node, bl, (topHasRight ? ALL_TOP_HAS_RIGHT : 0) | (leftHasBottom ? ALL_LEFT_HAS_BOTTOM : 0));
        node.children = new Node[4];
        for (var n = 0; n < 4; n++) {
            var child = new Node();
            node.children[n] = child;
            var tr = !(n == 3 || (n == 1 && !topHasRight));
            var lb = n == 0 || (n == 2 && leftHasBottom);
            if (bl == 3) {
                initEdges(child, bl + 1, (tr ? ALL_TOP_HAS_RIGHT : 0) | (lb ? ALL_LEFT_HAS_BOTTOM : 0));
            } else {
                initModeNode(child, bl + 1, tr, lb);
            }
        }
    }
}
