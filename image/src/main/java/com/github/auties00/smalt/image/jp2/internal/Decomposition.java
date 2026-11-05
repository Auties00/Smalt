package com.github.auties00.smalt.image.jp2.internal;

import com.github.auties00.smalt.MediaException;

import java.util.ArrayList;
import java.util.List;

/**
 * Describes an arbitrary wavelet decomposition of a tile-component (ISO/IEC 15444-2 annex F): how each resolution
 * forms from the one above it and how each decomposition level splits into sub-bands, as the {@code DFS} and
 * {@code ADS} segments signal them.
 *
 * <p>The structure is a tree of splits rooted at the tile-component ({@code a_0LL}). Each decomposition level
 * {@code lev} splits the resolution node {@code R(lev - 1)} once, jointly or in one direction ({@code d_R}), and may
 * split the other resulting sub-bands again, up to {@code theta(lev)} sub-levels in all ({@code d_S}); the low-pass
 * sub-band of the first split is the next resolution node {@code R(lev)}.
 *
 * @implNote This implementation reads the {@code d_R} and {@code d_S} element strings in the order the procedures
 * {@code INIT_S_R} and {@code LEV_S} of F.2.5 consume them. When a string ends before every level is described, the
 * last element of {@code d_R} applies to the remaining levels and the elements {@code d_S} gave the last level that
 * consumed any are read again for the next one, which is the repetition the procedures' resets express.
 */
final class Decomposition {
    /**
     * The split of a leaf: none.
     */
    static final int LEAF = 0;

    /**
     * A joint horizontal and vertical split into LL, HL, LH and HH sub-bands.
     */
    static final int JOINT = 1;

    /**
     * A horizontal split into LX and HX sub-bands.
     */
    static final int HORIZONTAL = 2;

    /**
     * A vertical split into XL and XH sub-bands.
     */
    static final int VERTICAL = 3;

    /**
     * The filtering of a node along one axis: low-pass.
     */
    static final int LOW = 0;

    /**
     * The filtering of a node along one axis: high-pass.
     */
    static final int HIGH = 1;

    /**
     * The filtering of a node along one axis: none.
     */
    static final int NONE = 2;

    /**
     * The resolution formation elements {@code d_R}, or {@code null} for joint splits at every level.
     */
    private final int[] resolutionSplits;

    /**
     * The maximum sub-level elements {@code d_theta}, or {@code null} for one sub-level at every level.
     */
    private final int[] levelDepths;

    /**
     * The sub-band split elements {@code d_S}, or {@code null} for joint splits everywhere.
     */
    private final int[] structure;

    /**
     * Constructs a decomposition.
     *
     * @param resolutionSplits the {@code d_R} elements of a {@code DFS} segment, or {@code null}
     * @param levelDepths the {@code d_theta} elements of an {@code ADS} segment, or {@code null}
     * @param structure the {@code d_S} elements of an {@code ADS} segment, or {@code null}
     */
    Decomposition(int[] resolutionSplits, int[] levelDepths, int[] structure) {
        this.resolutionSplits = resolutionSplits == null || resolutionSplits.length == 0 ? null : resolutionSplits;
        this.levelDepths = levelDepths == null || levelDepths.length == 0 ? null : levelDepths;
        this.structure = structure == null || structure.length == 0 ? null : structure;
    }

    /**
     * Holds one node of the decomposition tree: a sub-band, possibly split further.
     */
    static final class Node {
        /**
         * The split of this node: {@link #LEAF}, {@link #JOINT}, {@link #HORIZONTAL} or {@link #VERTICAL}.
         */
        int split = LEAF;

        /**
         * The child sub-bands, in the order of Tables F.3 and F.4, or {@code null} for a leaf.
         */
        Node[] children;

        /**
         * The last horizontal filtering that produced this node: {@link #LOW}, {@link #HIGH} or {@link #NONE}.
         */
        final int horizontal;

        /**
         * The last vertical filtering that produced this node.
         */
        final int vertical;

        /**
         * The decomposition level of the node, 0 for the tile-component.
         */
        final int level;

        /**
         * The orientation of the first sub-level sub-band of its level that spawns this node
         * ({@code PrimeOrient}): 0 for LL, LH, LX, XL and XH, 1 for HL and HX, 3 for HH, as the code-block coder's
         * contexts distinguish them (Table F.1).
         */
        final int primary;

        /**
         * The base-2 logarithm of the node's gain relative to its level's resolution node.
         */
        final int gain;

        /**
         * Twice the number of decomposition levels {@code n_b} from the tile-component to the node.
         */
        final int doubledDepth;

        /**
         * The number of splits along each axis from the level's resolution node to this node.
         */
        final int splitsX;

        /**
         * The number of vertical splits from the level's resolution node to this node.
         */
        final int splitsY;

        /**
         * Whether this node is a resolution node {@code R(lev)}.
         */
        boolean resolution;

        /**
         * Constructs a node.
         *
         * @param horizontal the last horizontal filtering
         * @param vertical the last vertical filtering
         * @param level the decomposition level
         * @param primary the primary orientation
         * @param gain the gain exponent
         * @param doubledDepth twice {@code n_b}
         * @param splitsX the horizontal splits from the resolution node
         * @param splitsY the vertical splits from the resolution node
         */
        Node(int horizontal, int vertical, int level, int primary, int gain, int doubledDepth, int splitsX,
             int splitsY) {
            this.horizontal = horizontal;
            this.vertical = vertical;
            this.level = level;
            this.primary = primary;
            this.gain = gain;
            this.doubledDepth = doubledDepth;
            this.splitsX = splitsX;
            this.splitsY = splitsY;
        }

        /**
         * Splits the node.
         *
         * @param kind the split
         * @param resolutionSplit whether this is the first split of a level, which sets the children's primary
         * orientation and restarts their gains and split counts
         */
        void split(int kind, boolean resolutionSplit) {
            split = kind;
            var level = resolutionSplit ? this.level + 1 : this.level;
            var gainBase = resolutionSplit ? 0 : gain;
            var baseX = resolutionSplit ? 0 : splitsX;
            var baseY = resolutionSplit ? 0 : splitsY;
            children = switch (kind) {
                case JOINT -> new Node[]{
                        child(LOW, LOW, level, resolutionSplit ? 0 : primary, gainBase, 2, baseX + 1, baseY + 1),
                        child(HIGH, LOW, level, resolutionSplit ? 1 : primary, gainBase + 1, 2, baseX + 1, baseY + 1),
                        child(LOW, HIGH, level, resolutionSplit ? 0 : primary, gainBase + 1, 2, baseX + 1, baseY + 1),
                        child(HIGH, HIGH, level, resolutionSplit ? 3 : primary, gainBase + 2, 2, baseX + 1, baseY + 1)};
                case HORIZONTAL -> new Node[]{
                        child(LOW, NONE, level, resolutionSplit ? 0 : primary, gainBase, 1, baseX + 1, baseY),
                        child(HIGH, NONE, level, resolutionSplit ? 1 : primary, gainBase + 1, 1, baseX + 1, baseY)};
                default -> new Node[]{
                        child(NONE, LOW, level, resolutionSplit ? 0 : primary, gainBase, 1, baseX, baseY + 1),
                        child(NONE, HIGH, level, resolutionSplit ? 0 : primary, gainBase + 1, 1, baseX, baseY + 1)};
            };
        }

        /**
         * Creates a child node.
         *
         * @param horizontal the horizontal filtering
         * @param vertical the vertical filtering
         * @param level the decomposition level
         * @param primary the primary orientation
         * @param gain the gain exponent
         * @param depthStep the increase of twice {@code n_b}: 2 for a joint split, 1 for a split in one direction
         * @param splitsX the horizontal splits from the resolution node
         * @param splitsY the vertical splits from the resolution node
         * @return the child
         */
        private Node child(int horizontal, int vertical, int level, int primary, int gain, int depthStep, int splitsX,
                           int splitsY) {
            return new Node(horizontal, vertical, level, primary, gain, doubledDepth + depthStep, splitsX, splitsY);
        }
    }

    /**
     * Holds the shape of a decomposition with a given number of levels.
     *
     * @param root the tile-component node, {@code a_0LL}
     * @param resolutionNodes the resolution node {@code R(lev)} of each decomposition level, from 0 to {@code N_L}
     */
    record Shape(Node root, Node[] resolutionNodes) {
        /**
         * Returns the sub-bands of a resolution, in the order their data appears in a packet and their step sizes
         * in a quantization segment.
         *
         * @param resolution the resolution, from 0 to {@code N_L}
         * @return the sub-bands: the resolution node itself for resolution 0, otherwise the leaves of the level
         * that forms the resolution, other than the next lower resolution node, in depth-first order
         */
        List<Node> bands(int resolution) {
            var levels = resolutionNodes.length - 1;
            var result = new ArrayList<Node>();
            if (resolution == 0) {
                result.add(resolutionNodes[levels]);
                return result;
            }
            var parent = resolutionNodes[levels - resolution];
            collect(parent, resolutionNodes[levels - resolution + 1], result);
            return result;
        }

        /**
         * Collects the leaves under a node in depth-first order, skipping one node.
         *
         * @param node the node whose leaves are collected
         * @param skipped the node to skip, with its subtree
         * @param result receives the leaves
         */
        private static void collect(Node node, Node skipped, List<Node> result) {
            for (var child : node.children) {
                if (child == skipped) {
                    continue;
                }
                if (child.children == null || child.resolution) {
                    result.add(child);
                } else {
                    collect(child, skipped, result);
                }
            }
        }
    }

    /**
     * Builds the shape of the decomposition for a number of levels.
     *
     * @param levels the number of decomposition levels {@code N_L}
     * @return the shape
     * @throws MediaException.Malformed if an element is invalid, or a level allows no sub-level
     */
    Shape shape(int levels) {
        var depths = new int[levels + 1];
        var last = 1;
        for (var lev = 1; lev <= levels; lev++) {
            if (levelDepths != null && lev - 1 < levelDepths.length) {
                last = levelDepths[lev - 1];
            }
            if (last == 0) {
                throw new MediaException.Malformed("JPEG 2000 ADS segment with a level of no sub-levels");
            }
            depths[lev] = last;
        }
        var root = new Node(LOW, LOW, 0, 0, 0, 0, 0, 0);
        root.resolution = true;
        var resolutionNodes = new Node[levels + 1];
        resolutionNodes[0] = root;
        var cursor = new int[]{0, 0};
        var lastRun = -1;
        for (var lev = 1; lev <= levels; lev++) {
            var runStart = cursor[1];
            var parent = resolutionNodes[lev - 1];
            var kind = resolutionSplits == null ? JOINT
                    : resolutionSplits[Math.min(cursor[0]++, resolutionSplits.length - 1)];
            if (kind == 0) {
                throw new MediaException.Malformed("invalid JPEG 2000 DFS element 0");
            }
            parent.split(kind, true);
            for (var j = parent.children.length - 1; j >= 1; j--) {
                levelSplit(parent.children[j], 1, depths[lev], cursor, lastRun);
            }
            if (structure != null && cursor[1] > runStart && runStart < structure.length) {
                lastRun = runStart;
            }
            var next = parent.children[0];
            next.resolution = true;
            resolutionNodes[lev] = next;
        }
        return new Shape(root, resolutionNodes);
    }

    /**
     * Splits a sub-band of a level as the next {@code d_S} element says ({@code LEV_S} of F.2.5), and its children
     * recursively.
     *
     * @param node the sub-band
     * @param count the number of sub-levels already applied at this level
     * @param max the maximum number of sub-levels of the level
     * @param cursor the positions in {@code d_R} and {@code d_S}
     * @param lastRun the position in {@code d_S} where the last level that consumed elements started, or {@code -1}
     */
    private void levelSplit(Node node, int count, int max, int[] cursor, int lastRun) {
        if (count >= max) {
            return;
        }
        int kind;
        if (structure == null) {
            kind = JOINT;
        } else {
            if (cursor[1] >= structure.length) {
                if (lastRun < 0) {
                    throw new MediaException.Malformed("JPEG 2000 ADS segment with too few structure elements");
                }
                cursor[1] = lastRun;
            }
            kind = structure[cursor[1]++];
        }
        if (kind == 0) {
            return;
        }
        node.split(kind, false);
        for (var j = node.children.length - 1; j >= 0; j--) {
            levelSplit(node.children[j], count + 1, max, cursor, lastRun);
        }
    }
}
