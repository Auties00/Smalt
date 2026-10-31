/* Ports the Microsoft MPEG-4 Visual reference software of ISO/IEC 14496-5:2001 (version 2 FDAM1-2.3-001213,
 * tools/entropy/huffman.cpp: CHuffmanDecoder). Copyright 1996-2000 Microsoft Corporation and the contributors named
 * in the sources; ISO/IEC free license for products conforming to ISO/IEC 14496-2. Modified for Smalt. */
package com.github.auties00.smalt.video.mpeg4.internal.object;

import java.util.Arrays;

/**
 * Decodes the symbols of one variable length code by walking its code tree bit by bit ({@code CHuffmanDecoder}). An
 * invalid code decodes as {@code -1} after the bit that left the tree.
 */
final class Vlc {
    /**
     * The tree: for node {@code n}, entry {@code 2n + bit} is the next node, or {@code -2 - symbol} for a leaf, or
     * {@code -1} for no code.
     */
    private final int[] tree;

    /**
     * Builds the tree of a table of symbol and code pairs.
     */
    Vlc(Object[] table) {
        var nodes = new int[16];
        Arrays.fill(nodes, -1);
        var count = 1;
        for (var i = 0; i < table.length; i += 2) {
            var symbol = (Integer) table[i];
            var code = (String) table[i + 1];
            var node = 0;
            for (var b = 0; b < code.length(); b++) {
                var bit = code.charAt(b) - '0';
                var slot = 2 * node + bit;
                if (b == code.length() - 1) {
                    nodes[slot] = -2 - symbol;
                } else {
                    if (nodes[slot] < 0) {
                        if (2 * (count + 1) > nodes.length) {
                            var grown = Arrays.copyOf(nodes, nodes.length * 2);
                            Arrays.fill(grown, nodes.length, grown.length, -1);
                            nodes = grown;
                        }
                        nodes[slot] = count++;
                    }
                    node = nodes[slot];
                }
            }
        }
        this.tree = Arrays.copyOf(nodes, 2 * count);
    }

    int decode(InBits bits) {
        var node = 0;
        while (true) {
            var next = tree[2 * node + bits.getBits(1)];
            if (next == -1) {
                return -1;
            }
            if (next < -1) {
                return -2 - next;
            }
            node = next;
        }
    }
}
