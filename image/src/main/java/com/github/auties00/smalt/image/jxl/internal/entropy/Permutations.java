/* Ports jxl-oxide 0.12.6 (crates/jxl-coding: read_permutation). Copyright Wonwoo Choi and the jxl-oxide contributors;
   MIT License. Modified for Smalt. */
package com.github.auties00.smalt.image.jxl.internal.entropy;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.jxl.internal.bitstream.JxlBitReader;

/**
 * Reads the Lehmer-coded permutations of a JPEG XL codestream (ISO/IEC 18181-1 section C.3.2),
 * used by the table of contents and the coefficient orders.
 *
 * <p>Every method is a pure function of its arguments and the bitstream.
 */
public final class Permutations {
    /**
     * Prevents instantiation.
     */
    private Permutations() {
        throw new AssertionError();
    }

    /**
     * Reads a permutation of {@code size} elements whose first {@code skip} elements stay in
     * place.
     *
     * @param br the bitstream
     * @param decoder the entropy decoder, with eight contexts
     * @param size the number of elements
     * @param skip the number of leading elements that are not permuted
     * @return the permutation: element {@code i} of the result is the original index placed at
     * position {@code i}
     * @throws MediaException.Malformed if the Lehmer code is out of range
     */
    public static int[] read(JxlBitReader br, EntropyDecoder decoder, int size, int skip) {
        var end = decoder.readUint(br, context(size));
        if (Integer.compareUnsigned(end, size - skip) > 0) {
            throw new MediaException.Malformed("invalid permutation length");
        }
        var lehmer = new int[end];
        var prev = 0;
        for (var i = 0; i < end; i++) {
            var value = decoder.readUint(br, context(prev));
            if (Integer.compareUnsigned(value, size - skip - i) >= 0) {
                throw new MediaException.Malformed("invalid permutation entry");
            }
            lehmer[i] = value;
            prev = value;
        }
        var count = size - skip;
        var tree = new int[count + 1];
        for (var i = 1; i <= count; i++) {
            tree[i] += 1;
            var parent = i + (i & -i);
            if (parent <= count) {
                tree[parent] += tree[i];
            }
        }
        var topBit = count == 0 ? 0 : Integer.highestOneBit(count);
        var permutation = new int[size];
        for (var i = 0; i < skip; i++) {
            permutation[i] = i;
        }
        var taken = new boolean[count];
        var out = skip;
        for (var index : lehmer) {
            var element = kthRemaining(tree, count, topBit, index);
            taken[element] = true;
            permutation[out++] = skip + element;
            for (var j = element + 1; j <= count; j += j & -j) {
                tree[j]--;
            }
        }
        for (var element = 0; element < count && out < size; element++) {
            if (!taken[element]) {
                permutation[out++] = skip + element;
            }
        }
        return permutation;
    }

    /**
     * Finds the element of rank {@code k} among the elements still present in a Fenwick tree of
     * presence counts.
     *
     * @param tree the Fenwick tree, one-based
     * @param count the number of elements
     * @param topBit the highest power of two not above {@code count}
     * @param k the zero-based rank
     * @return the zero-based element
     */
    private static int kthRemaining(int[] tree, int count, int topBit, int k) {
        var position = 0;
        var remaining = k + 1;
        for (var step = topBit; step > 0; step >>>= 1) {
            var next = position + step;
            if (next <= count && tree[next] < remaining) {
                position = next;
                remaining -= tree[next];
            }
        }
        return position;
    }

    /**
     * Returns the context of a Lehmer code entry.
     *
     * @param x the previous value, or the size for the length
     * @return the context, from 0 to 7
     */
    private static int context(int x) {
        return Math.min(EntropyDecoder.addLog2Ceil(x), 7);
    }
}
