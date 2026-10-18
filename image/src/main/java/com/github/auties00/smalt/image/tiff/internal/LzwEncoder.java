package com.github.auties00.smalt.image.tiff.internal;

import java.util.Arrays;

/**
 * Encodes TIFF 6.0 LZW data (compression 5): most significant bit first codes of 9 to 12 bits
 * with the early code-width change, starting with a clear code and ending with the
 * end-of-information code.
 *
 * <p>The string table is reset with a clear code whenever it fills. An instance reuses its hash
 * table across calls and is not thread-safe.
 *
 * @implNote This implementation follows libtiff's tif_lzw.c {@code LZWEncode} and
 * {@code LZWPostEncode} for the code-width schedule and the point at which the table is cleared
 * (when the next free code reaches 4094), so its output decodes with every TIFF reader; it does
 * not clear the table early when the compression ratio drops, and it hashes with open addressing
 * over a power-of-two table instead of libtiff's double hashing.
 */
public final class LzwEncoder {
    /**
     * The clear code.
     */
    private static final int CLEAR = 256;

    /**
     * The end-of-information code.
     */
    private static final int EOI = 257;

    /**
     * The first code assigned to a string.
     */
    private static final int FIRST = 258;

    /**
     * The next free code at which the table is cleared.
     */
    private static final int LIMIT = 4094;

    /**
     * The size of the hash table, a power of two above twice the table size.
     */
    private static final int HASH_SIZE = 1 << 13;

    /**
     * The key of each hash slot, {@code prefix << 8 | byte} plus one, or zero when empty.
     */
    private final int[] keys = new int[HASH_SIZE];

    /**
     * The code of each hash slot.
     */
    private final short[] codes = new short[HASH_SIZE];

    /**
     * The encoded bytes.
     */
    private byte[] out = new byte[4096];

    /**
     * The number of encoded bytes.
     */
    private int length;

    /**
     * The pending bits, aligned to the least significant end.
     */
    private long buffer;

    /**
     * The number of pending bits.
     */
    private int bits;

    /**
     * Constructs an encoder.
     */
    public LzwEncoder() {
    }

    /**
     * Encodes a block of bytes as one complete LZW stream.
     *
     * @param input  the bytes
     * @param offset the index of the first byte
     * @param count  the number of bytes
     * @return the encoded stream, in a new array
     */
    public byte[] encode(byte[] input, int offset, int count) {
        length = 0;
        buffer = 0;
        bits = 0;
        Arrays.fill(keys, 0);
        var width = 9;
        var maxCode = 511;
        var next = FIRST;
        put(CLEAR, width);
        if (count == 0) {
            put(EOI, width);
            return flush();
        }
        var prefix = input[offset] & 0xFF;
        var end = offset + count;
        for (var i = offset + 1; i < end; i++) {
            var c = input[i] & 0xFF;
            var key = (prefix << 8 | c) + 1;
            var slot = hash(key);
            var found = -1;
            while (keys[slot] != 0) {
                if (keys[slot] == key) {
                    found = codes[slot];
                    break;
                }
                slot = (slot + 1) & (HASH_SIZE - 1);
            }
            if (found >= 0) {
                prefix = found;
                continue;
            }
            put(prefix, width);
            keys[slot] = key;
            codes[slot] = (short) next++;
            prefix = c;
            if (next == LIMIT) {
                put(CLEAR, width);
                Arrays.fill(keys, 0);
                next = FIRST;
                width = 9;
                maxCode = 511;
            } else if (next > maxCode) {
                width++;
                maxCode = (1 << width) - 1;
            }
        }
        put(prefix, width);
        next++;
        if (next == LIMIT) {
            put(CLEAR, width);
            width = 9;
        } else if (next > maxCode) {
            width++;
        }
        put(EOI, width);
        return flush();
    }

    /**
     * Returns the hash slot of a key.
     *
     * @param key the key
     * @return the first slot to probe
     */
    private static int hash(int key) {
        return (key * 0x9E3779B1 >>> 19) & (HASH_SIZE - 1);
    }

    /**
     * Appends one code.
     *
     * @param code  the code
     * @param width the code width in bits
     */
    private void put(int code, int width) {
        buffer = (buffer << width) | code;
        bits += width;
        while (bits >= 8) {
            bits -= 8;
            if (length == out.length) {
                out = Arrays.copyOf(out, out.length * 2);
            }
            out[length++] = (byte) (buffer >>> bits);
        }
    }

    /**
     * Writes the pending bits, padded with zeros to a byte boundary, and returns the stream.
     *
     * @return the encoded stream
     */
    private byte[] flush() {
        if (bits > 0) {
            if (length == out.length) {
                out = Arrays.copyOf(out, out.length + 1);
            }
            out[length++] = (byte) (buffer << (8 - bits));
            bits = 0;
        }
        return Arrays.copyOf(out, length);
    }
}
