package com.github.auties00.smalt.image.jp2.internal;

import java.util.Arrays;

/**
 * Codes a two-dimensional array of non-negative integers with a tag tree (ISO/IEC 15444-1
 * B.10.2), as packet headers code code-block inclusion and zero bit-planes.
 *
 * @implNote This implementation follows OpenJPEG's {@code tgt.c}: nodes are stored leaves first,
 * then each coarser level, and every node keeps the lower bound already established for it.
 */
final class TagTree {
    /**
     * The value of a node not yet known, larger than any coded value.
     */
    private static final int UNKNOWN = 999;

    /**
     * The parent of each node, or {@code -1} for the root.
     */
    private final int[] parents;

    /**
     * The value of each node; for an encoder the minimum over its leaves, for a decoder the value
     * once decoded.
     */
    private final int[] values;

    /**
     * The lower bound already established for each node.
     */
    private final int[] lows;

    /**
     * Whether each node's value has been signaled, for the encoder.
     */
    private final boolean[] known;

    /**
     * The levels' node path from a leaf to the root, reused by every call.
     */
    private final int[] path = new int[32];

    /**
     * Constructs a tree over a grid of leaves.
     *
     * @param width  the number of leaf columns, positive
     * @param height the number of leaf rows, positive
     */
    TagTree(int width, int height) {
        var levelWidths = new int[32];
        var levelHeights = new int[32];
        levelWidths[0] = width;
        levelHeights[0] = height;
        var levels = 0;
        var nodes = 0;
        int count;
        do {
            count = levelWidths[levels] * levelHeights[levels];
            levelWidths[levels + 1] = (levelWidths[levels] + 1) / 2;
            levelHeights[levels + 1] = (levelHeights[levels] + 1) / 2;
            nodes += count;
            levels++;
        } while (count > 1);
        parents = new int[nodes];
        values = new int[nodes];
        lows = new int[nodes];
        known = new boolean[nodes];
        var node = 0;
        var parent = width * height;
        var parentRowStart = parent;
        for (var level = 0; level < levels - 1; level++) {
            for (var row = 0; row < levelHeights[level]; row++) {
                var k = levelWidths[level];
                while (--k >= 0) {
                    parents[node++] = parent;
                    if (--k >= 0) {
                        parents[node++] = parent;
                    }
                    parent++;
                }
                if ((row & 1) != 0 || row == levelHeights[level] - 1) {
                    parentRowStart = parent;
                } else {
                    parent = parentRowStart;
                    parentRowStart += levelWidths[level + 1];
                }
            }
        }
        parents[node] = -1;
        reset();
    }

    /**
     * Resets every node to the unknown state.
     */
    void reset() {
        Arrays.fill(values, UNKNOWN);
        Arrays.fill(lows, 0);
        Arrays.fill(known, false);
    }

    /**
     * Sets the value of a leaf, propagating the minimum up the tree.
     *
     * @param leaf  the leaf index, row-major
     * @param value the value
     */
    void setValue(int leaf, int value) {
        var node = leaf;
        while (node >= 0 && values[node] > value) {
            values[node] = value;
            node = parents[node];
        }
    }

    /**
     * Decodes whether a leaf's value is below a threshold, reading as many bits as needed.
     *
     * @param reader    the packet header bits
     * @param leaf      the leaf index, row-major
     * @param threshold the threshold
     * @return whether the leaf's value is below {@code threshold}
     */
    boolean decode(PacketBits reader, int leaf, int threshold) {
        var depth = 0;
        var node = leaf;
        while (parents[node] >= 0) {
            path[depth++] = node;
            node = parents[node];
        }
        var low = 0;
        while (true) {
            if (low > lows[node]) {
                lows[node] = low;
            } else {
                low = lows[node];
            }
            while (low < threshold && low < values[node]) {
                if (reader.bit() != 0) {
                    values[node] = low;
                } else {
                    low++;
                }
            }
            lows[node] = low;
            if (depth == 0) {
                break;
            }
            node = path[--depth];
        }
        return values[node] < threshold;
    }

    /**
     * Returns the decoded value of a leaf.
     *
     * @param leaf the leaf index
     * @return the value, or a large number while it is unknown
     */
    int value(int leaf) {
        return values[leaf];
    }

    /**
     * Encodes a leaf's value up to a threshold.
     *
     * @param writer    the packet header bits
     * @param leaf      the leaf index, row-major
     * @param threshold the threshold
     */
    void encode(PacketBitWriter writer, int leaf, int threshold) {
        var depth = 0;
        var node = leaf;
        while (parents[node] >= 0) {
            path[depth++] = node;
            node = parents[node];
        }
        var low = 0;
        while (true) {
            if (low > lows[node]) {
                lows[node] = low;
            } else {
                low = lows[node];
            }
            while (low < threshold) {
                if (low >= values[node]) {
                    if (!known[node]) {
                        writer.bit(1);
                        known[node] = true;
                    }
                    break;
                }
                writer.bit(0);
                low++;
            }
            lows[node] = low;
            if (depth == 0) {
                break;
            }
            node = path[--depth];
        }
    }
}
