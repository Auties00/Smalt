/* Ports libjxl 0.11.2 (lib/jxl/enc_huffman_tree.cc: CreateHuffmanTree, WriteHuffmanTree,
   ConvertBitDepthsToSymbols), itself from Brotli. Copyright the JPEG XL Project Authors and the Brotli Authors;
   BSD 3-Clause License and MIT License. Modified for Smalt. */
package com.github.auties00.smalt.image.jxl.internal.encode;

import java.util.Arrays;

/**
 * Builds length-limited prefix codes and their Brotli code-length representation.
 */
final class HuffmanTree {
    private HuffmanTree() {
        throw new AssertionError();
    }

    /**
     * Computes code lengths for a histogram, no longer than a limit.
     *
     * @param histogram the symbol counts
     * @param length the alphabet size
     * @param limit the longest allowed code
     * @param depth receives the code length of each symbol, 0 for an unused symbol
     */
    static void create(int[] histogram, int length, int limit, int[] depth) {
        Arrays.fill(depth, 0, length, 0);
        var used = 0;
        for (var i = 0; i < length; i++) {
            if (histogram[i] != 0) {
                used++;
            }
        }
        if (used == 0) {
            return;
        }
        if (used == 1) {
            for (var i = 0; i < length; i++) {
                if (histogram[i] != 0) {
                    depth[i] = 1;
                }
            }
            return;
        }
        for (long countMin = 1; ; countMin *= 2) {
            var symbols = new int[used];
            var weights = new long[2 * used];
            var parent = new int[2 * used];
            var n = 0;
            for (var i = length - 1; i >= 0; i--) {
                if (histogram[i] != 0) {
                    symbols[n++] = i;
                }
            }
            var order = new Integer[used];
            for (var i = 0; i < used; i++) {
                order[i] = i;
                weights[i] = Math.max(histogram[symbols[i]], countMin);
            }
            Arrays.sort(order, (a, b) -> Long.compare(weights[a], weights[b]));
            var leaves = new int[used];
            for (var i = 0; i < used; i++) {
                leaves[i] = order[i];
            }
            var leaf = 0;
            var nextInternal = used;
            var internalHead = used;
            for (var k = 0; k < used - 1; k++) {
                var a = pick(weights, leaves, used, nextInternal, internalHead, leaf);
                if (a < used) {
                    leaf++;
                } else {
                    internalHead++;
                }
                var b = pick(weights, leaves, used, nextInternal, internalHead, leaf);
                if (b < used) {
                    leaf++;
                } else {
                    internalHead++;
                }
                var na = a < used ? leaves[a] : a;
                var nb = b < used ? leaves[b] : b;
                weights[nextInternal] = weights[na] + weights[nb];
                parent[na] = nextInternal;
                parent[nb] = nextInternal;
                nextInternal++;
            }
            var root = nextInternal - 1;
            var max = 0;
            var nodeDepth = new int[2 * used];
            for (var node = root - 1; node >= 0; node--) {
                nodeDepth[node] = nodeDepth[parent[node]] + 1;
            }
            for (var i = 0; i < used; i++) {
                depth[symbols[i]] = nodeDepth[i];
                max = Math.max(max, nodeDepth[i]);
            }
            if (max <= limit) {
                return;
            }
        }
    }

    /**
     * Returns the next node of the two-queue Huffman construction: a leaf position below {@code used}, or an
     * internal node index.
     */
    private static int pick(long[] weights, int[] leaves, int used, int nextInternal, int internalHead, int leaf) {
        if (leaf < used && (internalHead >= nextInternal || weights[leaves[leaf]] <= weights[internalHead])) {
            return leaf;
        }
        return internalHead;
    }

    /**
     * Assigns canonical codes to code lengths, bit-reversed for least-significant-bit-first writing.
     *
     * @param depth the code lengths
     * @param length the alphabet size
     * @param bits receives the codes
     */
    static void toCodes(int[] depth, int length, int[] bits) {
        var counts = new int[16];
        for (var i = 0; i < length; i++) {
            counts[depth[i]]++;
        }
        counts[0] = 0;
        var next = new int[16];
        var code = 0;
        for (var i = 1; i < 16; i++) {
            code = (code + counts[i - 1]) << 1;
            next[i] = code;
        }
        for (var i = 0; i < length; i++) {
            if (depth[i] != 0) {
                bits[i] = reverse(next[depth[i]]++, depth[i]);
            }
        }
    }

    private static int reverse(int value, int count) {
        return Integer.reverse(value) >>> (32 - count);
    }

    /**
     * Writes code lengths as the Brotli code-length alphabet: lengths 0 to 15, 16 to repeat the previous
     * non-zero length and 17 to repeat zero.
     *
     * @param depth the code lengths
     * @param length the alphabet size
     * @param tree receives the code-length symbols
     * @param extra receives the extra bits of each repeat symbol
     * @return the number of symbols written
     */
    static int writeTree(int[] depth, int length, int[] tree, int[] extra) {
        var previous = 8;
        var newLength = length;
        while (newLength > 0 && depth[newLength - 1] == 0) {
            newLength--;
        }
        var size = new int[1];
        var i = 0;
        while (i < newLength) {
            var value = depth[i];
            var reps = 1;
            for (var k = i + 1; k < newLength && depth[k] == value; k++) {
                reps++;
            }
            if (value == 0) {
                repeatZeros(reps, tree, extra, size);
            } else {
                repeat(previous, value, reps, tree, extra, size);
                previous = value;
            }
            i += reps;
        }
        return size[0];
    }

    private static void repeat(int previous, int value, int reps, int[] tree, int[] extra, int[] size) {
        if (previous != value) {
            emit(tree, extra, size, value, 0);
            reps--;
        }
        if (reps == 7) {
            emit(tree, extra, size, value, 0);
            reps--;
        }
        if (reps < 3) {
            for (var i = 0; i < reps; i++) {
                emit(tree, extra, size, value, 0);
            }
            return;
        }
        var startIndex = size[0];
        reps -= 3;
        while (true) {
            emit(tree, extra, size, 16, reps & 3);
            reps >>= 2;
            if (reps == 0) {
                break;
            }
            reps--;
        }
        reverseRange(tree, extra, startIndex, size[0]);
    }

    private static void repeatZeros(int reps, int[] tree, int[] extra, int[] size) {
        if (reps == 11) {
            emit(tree, extra, size, 0, 0);
            reps--;
        }
        if (reps < 3) {
            for (var i = 0; i < reps; i++) {
                emit(tree, extra, size, 0, 0);
            }
            return;
        }
        var startIndex = size[0];
        reps -= 3;
        while (true) {
            emit(tree, extra, size, 17, reps & 7);
            reps >>= 3;
            if (reps == 0) {
                break;
            }
            reps--;
        }
        reverseRange(tree, extra, startIndex, size[0]);
    }

    private static void emit(int[] tree, int[] extra, int[] size, int symbol, int bits) {
        tree[size[0]] = symbol;
        extra[size[0]] = bits;
        size[0]++;
    }

    private static void reverseRange(int[] tree, int[] extra, int from, int to) {
        for (int a = from, b = to - 1; a < b; a++, b--) {
            var t = tree[a];
            tree[a] = tree[b];
            tree[b] = t;
            t = extra[a];
            extra[a] = extra[b];
            extra[b] = t;
        }
    }
}
