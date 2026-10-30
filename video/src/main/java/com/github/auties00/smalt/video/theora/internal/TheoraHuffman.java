/* Ports libtheora 1.2.0 (lib/huffdec.c). Copyright (C) 2002-2009 Xiph.org Foundation; BSD 3-Clause License.
   Modified for Smalt. */
package com.github.auties00.smalt.video.theora.internal;

import com.github.auties00.smalt.MediaException;

/**
 * Decodes the DCT tokens of one of the 80 Huffman codes of a Theora setup header.
 *
 * <p>The tree holds at most 32 leaves, so at most 31 internal nodes; each internal node {@code n} stores its two
 * children at {@code 2n} and {@code 2n + 1}, a non-negative child being an internal node and a negative child
 * {@code -1 - token} a leaf. A code of a single token has no internal node and reads no bits.
 *
 * <p>Instances are immutable after construction.
 */
final class TheoraHuffman {
    private static final int MAX_LEAVES = 32;

    private static final int MAX_DEPTH = 32;

    private final int[] children = new int[2 * (MAX_LEAVES - 1)];

    private final int rootToken;

    /**
     * Reads a code from the setup header: depth-first, a 0 bit an internal node whose 0 child comes first, a 1 bit
     * a leaf followed by its 5-bit token.
     *
     * @param bits the setup header, positioned at the code
     * @throws MediaException.Malformed if the code has more than 32 tokens or a codeword longer than 32 bits, or
     *                                  the header ends inside it
     */
    TheoraHuffman(TheoraBits bits) {
        var stack = new int[MAX_DEPTH + 1];
        var depth = 0;
        var nodes = 0;
        var leaves = 0;
        var root = -1;
        while (true) {
            var bit = bits.bit();
            if (bits.bytesLeft() < 0) {
                throw new MediaException.Malformed("Theora Huffman code truncated");
            }
            int child;
            if (bit == 0) {
                if (depth >= MAX_DEPTH || nodes >= MAX_LEAVES - 1) {
                    throw new MediaException.Malformed("Theora Huffman code too deep");
                }
                child = nodes++;
            } else {
                if (++leaves > MAX_LEAVES) {
                    throw new MediaException.Malformed("Theora Huffman code with more than 32 tokens");
                }
                child = -1 - (int) bits.read(5);
            }
            if (depth == 0) {
                root = child;
            } else {
                var slot = stack[depth - 1];
                children[slot] = child;
                if ((slot & 1) == 0) {
                    stack[depth - 1] = slot + 1;
                } else {
                    stack[depth - 1] = -1;
                }
            }
            if (child >= 0) {
                stack[depth++] = 2 * child;
                continue;
            }
            while (depth > 0 && stack[depth - 1] < 0) {
                depth--;
            }
            if (depth == 0) {
                break;
            }
        }
        rootToken = root < 0 ? -1 - root : -1;
    }

    /**
     * {@return the next token, from 0 to 31}
     *
     * @param bits the frame, positioned at a codeword
     */
    int decode(TheoraBits bits) {
        if (rootToken >= 0) {
            return rootToken;
        }
        var node = 0;
        while (true) {
            var child = children[2 * node + bits.bit()];
            if (child < 0) {
                return -1 - child;
            }
            node = child;
        }
    }
}
