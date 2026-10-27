/* Ports Esri LERC 4.2.0 (Huffman.cpp, Huffman.h). Copyright 2015-2026 Esri; Apache License 2.0. Modified for Smalt. */
package com.github.auties00.smalt.image.internal.lerc;

import java.util.Arrays;
import java.util.zip.DataFormatException;

/**
 * Reads a Lerc2 Huffman code table and decodes values with it.
 *
 * <p>Codes are read explicitly from the table, so this decoder decodes them as Esri's library does: a
 * lookup of up to 12 bits, and a tree, entered after the leading zero bits all long codes share, for
 * longer codes. Instances are not thread-safe.
 */
final class LercHuffman {
    private static final int MAX_HISTOGRAM = 1 << 15;
    private static final int MAX_LOOKUP_BITS = 12;

    /** The code length and code of each value. */
    private int[] lengths = new int[0];
    private int[] codes = new int[0];
    private int size;
    private int first;
    private int last;

    /** The lookup table: code length (-1 when absent) and value of each prefix. */
    private short[] lookupLengths = new short[0];
    private short[] lookupValues = new short[0];
    private int lookupBits;

    /** The tree of long codes: children (-1 when absent) and value (-1 for inner nodes) of each node. */
    private int[] child0 = new int[0];
    private int[] child1 = new int[0];
    private int[] nodeValues = new int[0];
    private int nodes;
    private int skipBits;

    /**
     * Reads the code table.
     *
     * @param stuffer the input, positioned at the table and advanced past it
     * @param version the Lerc2 version the code lengths are packed with
     * @throws DataFormatException if the table is invalid
     */
    void readTable(BitStuffer stuffer, int version) throws DataFormatException {
        var src = stuffer.src;
        var pos = stuffer.pos;
        if (stuffer.end - pos < 16) {
            throw new DataFormatException("truncated Lerc2 Huffman table");
        }
        var tableVersion = LercDecoder.intLE(src, pos);
        size = LercDecoder.intLE(src, pos + 4);
        first = LercDecoder.intLE(src, pos + 8);
        last = LercDecoder.intLE(src, pos + 12);
        stuffer.pos = pos + 16;
        if (tableVersion < 2 || first >= last || first < 0 || size < 0 || size > MAX_HISTOGRAM
                || wrap(first) >= size || wrap(last - 1) >= size) {
            throw new DataFormatException("invalid Lerc2 Huffman table");
        }
        var count = stuffer.decode(last - first, version);
        if (count != last - first) {
            throw new DataFormatException("invalid Lerc2 Huffman code lengths");
        }
        if (lengths.length < size) {
            lengths = new int[size];
            codes = new int[size];
        }
        Arrays.fill(lengths, 0, size, 0);
        Arrays.fill(codes, 0, size, 0);
        var packed = stuffer.values();
        for (var i = first; i < last; i++) {
            lengths[wrap(i)] = packed[i - first];
        }
        unstuffCodes(stuffer);
    }

    private int wrap(int i) {
        return i - (i < size ? 0 : size);
    }

    /**
     * Reads the codes, packed most significant bit first in little-endian words.
     *
     * @param stuffer the input
     * @throws DataFormatException if the codes are truncated
     */
    private void unstuffCodes(BitStuffer stuffer) throws DataFormatException {
        var src = stuffer.src;
        var start = stuffer.pos;
        var p = start;
        var remaining = (long) stuffer.end - start;
        var bitPos = 0;
        for (var i = first; i < last; i++) {
            var k = wrap(i);
            var length = lengths[k];
            if (length <= 0) {
                continue;
            }
            if (remaining < 4 || length > 32) {
                throw new DataFormatException("truncated Lerc2 Huffman codes");
            }
            var temp = LercDecoder.intLE(src, p);
            codes[k] = (temp << bitPos) >>> (32 - length);
            if (32 - bitPos >= length) {
                bitPos += length;
                if (bitPos == 32) {
                    bitPos = 0;
                    p += 4;
                    remaining -= 4;
                }
            } else {
                bitPos += length - 32;
                p += 4;
                remaining -= 4;
                if (remaining < 4) {
                    throw new DataFormatException("truncated Lerc2 Huffman codes");
                }
                codes[k] |= LercDecoder.intLE(src, p) >>> (32 - bitPos);
            }
        }
        var consumed = p - start + (bitPos > 0 ? 4 : 0);
        if (stuffer.end - start < consumed) {
            throw new DataFormatException("truncated Lerc2 Huffman codes");
        }
        stuffer.pos = start + consumed;
    }

    /**
     * Builds the lookup table and the tree from the codes.
     *
     * @throws DataFormatException if the code lengths are invalid
     */
    void build() throws DataFormatException {
        if (size == 0 || size >= MAX_HISTOGRAM) {
            throw new DataFormatException("invalid Lerc2 Huffman table size");
        }
        var i0 = 0;
        while (i0 < size && lengths[i0] == 0) {
            i0++;
        }
        var i1 = size - 1;
        while (i1 >= 0 && lengths[i1] == 0) {
            i1--;
        }
        i1++;
        if (i1 <= i0) {
            throw new DataFormatException("empty Lerc2 Huffman table");
        }
        var gapStart = 0;
        var gapLength = 0;
        for (var j = 0; j < size; ) {
            while (j < size && lengths[j] > 0) {
                j++;
            }
            var k0 = j;
            while (j < size && lengths[j] == 0) {
                j++;
            }
            if (j - k0 > gapLength) {
                gapStart = k0;
                gapLength = j - k0;
            }
        }
        if (size - gapLength < i1 - i0) {
            i0 = gapStart + gapLength;
            i1 = gapStart + size;
        }
        if (i1 <= i0) {
            throw new DataFormatException("empty Lerc2 Huffman table");
        }
        var maxLength = 0;
        for (var i = i0; i < i1; i++) {
            maxLength = Math.max(maxLength, lengths[wrap(i)]);
        }
        if (maxLength <= 0 || maxLength > 32) {
            throw new DataFormatException("invalid Lerc2 Huffman code length");
        }
        var needTree = maxLength > MAX_LOOKUP_BITS;
        lookupBits = Math.min(maxLength, MAX_LOOKUP_BITS);
        var lookupSize = 1 << lookupBits;
        if (lookupLengths.length < lookupSize) {
            lookupLengths = new short[lookupSize];
            lookupValues = new short[lookupSize];
        }
        Arrays.fill(lookupLengths, 0, lookupSize, (short) -1);
        Arrays.fill(lookupValues, 0, lookupSize, (short) -1);
        var minZeroBits = 32;
        for (var i = i0; i < i1; i++) {
            var k = wrap(i);
            var length = lengths[k];
            if (length == 0) {
                continue;
            }
            var code = codes[k];
            if (length <= lookupBits) {
                if (length < 32 && (code >>> length) != 0) {
                    throw new DataFormatException("invalid Lerc2 Huffman code");
                }
                var base = code << (lookupBits - length);
                var entries = 1 << (lookupBits - length);
                for (var j = 0; j < entries; j++) {
                    lookupLengths[base | j] = (short) length;
                    lookupValues[base | j] = (short) k;
                }
            } else {
                var shift = 1;
                var value = code;
                while ((value >>>= 1) != 0) {
                    shift++;
                }
                minZeroBits = Math.min(minZeroBits, length - shift);
            }
        }
        skipBits = needTree ? minZeroBits : 0;
        nodes = 0;
        if (!needTree) {
            return;
        }
        newNode();
        for (var i = i0; i < i1; i++) {
            var k = wrap(i);
            var length = lengths[k];
            if (length > lookupBits) {
                var code = codes[k];
                var node = 0;
                var j = length - skipBits;
                while (--j >= 0) {
                    if ((code & (1 << j)) != 0) {
                        if (child1[node] < 0) {
                            // newNode may grow the arrays, so the new node is made before the store
                            var created = newNode();
                            child1[node] = created;
                        }
                        node = child1[node];
                    } else {
                        if (child0[node] < 0) {
                            // newNode may grow the arrays, so the new node is made before the store
                            var created = newNode();
                            child0[node] = created;
                        }
                        node = child0[node];
                    }
                    if (j == 0) {
                        nodeValues[node] = k;
                    }
                }
            }
        }
    }

    private int newNode() {
        if (nodes == child0.length) {
            var capacity = Math.max(64, nodes * 2);
            child0 = Arrays.copyOf(child0, capacity);
            child1 = Arrays.copyOf(child1, capacity);
            nodeValues = Arrays.copyOf(nodeValues, capacity);
        }
        child0[nodes] = -1;
        child1[nodes] = -1;
        nodeValues[nodes] = -1;
        return nodes++;
    }

    /**
     * Returns a reader of values coded with this table.
     *
     * @param src the input
     * @param pos the first word of the coded values
     * @param end the end of the input
     * @param padded whether words past the end read as zero instead of failing
     * @return the reader
     */
    Reader reader(byte[] src, int pos, int end, boolean padded) {
        return new Reader(src, pos, end, padded);
    }

    /**
     * Reads values coded most significant bit first in little-endian words.
     */
    final class Reader {
        private final byte[] src;
        private final int end;
        private final boolean padded;
        private int pos;
        private int bitPos;

        private Reader(byte[] src, int pos, int end, boolean padded) {
            this.src = src;
            this.pos = pos;
            this.end = end;
            this.padded = padded;
        }

        /** {@return the position of the word holding the next bit} */
        int position() {
            return pos;
        }

        /** {@return the bits of the current word already read} */
        int bitPosition() {
            return bitPos;
        }

        private int word(int at) throws DataFormatException {
            if (at + 4 <= end) {
                return LercDecoder.intLE(src, at);
            }
            if (!padded) {
                throw new DataFormatException("truncated Lerc2 Huffman data");
            }
            var value = 0;
            for (var b = 0; b < 4; b++) {
                if (at + b < end) {
                    value |= (src[at + b] & 0xFF) << (8 * b);
                }
            }
            return value;
        }

        /**
         * Decodes the next value.
         *
         * @return the value
         * @throws DataFormatException if the code is invalid or the data ends
         */
        int next() throws DataFormatException {
            var temp = word(pos);
            var index = (temp << bitPos) >>> (32 - lookupBits);
            if (32 - bitPos < lookupBits) {
                index |= word(pos + 4) >>> (64 - bitPos - lookupBits);
            }
            if (lookupLengths[index] >= 0) {
                advance(lookupLengths[index]);
                return lookupValues[index] & 0xFFFF;
            }
            if (nodes == 0) {
                throw new DataFormatException("invalid Lerc2 Huffman code");
            }
            advance(skipBits);
            var node = 0;
            while (true) {
                var bit = (word(pos) << bitPos) >>> 31;
                advance(1);
                node = bit != 0 ? child1[node] : child0[node];
                if (node < 0) {
                    throw new DataFormatException("invalid Lerc2 Huffman code");
                }
                if (nodeValues[node] >= 0) {
                    return nodeValues[node];
                }
            }
        }

        private void advance(int bits) {
            bitPos += bits;
            if (bitPos >= 32) {
                bitPos -= 32;
                pos += 4;
            }
        }
    }
}
