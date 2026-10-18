package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaException;

import java.util.Arrays;

/**
 * Converts Huffman tables given as explicit codes into the {@code DHT} form
 * {@link MsbBits.Table} reads: sixteen code counts by length, then the values in code order.
 */
final class Huffman {
    /**
     * Prevents instantiation.
     */
    private Huffman() {
        throw new AssertionError();
    }

    /**
     * Converts explicit codes, which must form a canonical prefix code.
     *
     * @param codes the code of each value, left-aligned on {@code width} bits
     * @param lengths the bit length of each value's code, from 1 to 16
     * @param width the bits the codes are aligned on
     * @return the counts followed by the values
     * @throws MediaException.Malformed if the codes are not canonical
     */
    static int[] fromCodes(int[] codes, int[] lengths, int width) {
        var count = codes.length;
        var order = new Integer[count];
        for (var index = 0; index < count; index++) {
            order[index] = index;
        }
        Arrays.sort(order, (a, b) -> lengths[a] != lengths[b] ? Integer.compare(lengths[a], lengths[b])
                : Integer.compare(codes[a] >>> (width - lengths[a]), codes[b] >>> (width - lengths[b])));
        var spec = new int[16 + count];
        var code = 0;
        var length = 1;
        for (var rank = 0; rank < count; rank++) {
            var value = order[rank];
            var size = lengths[value];
            if (size < 1 || size > 16 || size > width) {
                throw new MediaException.Malformed("invalid Huffman code length " + size);
            }
            while (length < size) {
                code <<= 1;
                length++;
            }
            if ((codes[value] >>> (width - size)) != code) {
                throw new MediaException.Malformed("non-canonical Huffman table");
            }
            spec[size - 1]++;
            spec[16 + rank] = value;
            code++;
        }
        return spec;
    }
}
