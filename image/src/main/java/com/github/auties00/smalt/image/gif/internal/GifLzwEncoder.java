/* Ports GIFLIB 5.2.2 (egif_lib.c: EGifSetupCompress, EGifCompressLine, EGifCompressOutput; gif_hash.c). Copyright
   Eric S. Raymond; MIT License. Modified for Smalt. */
package com.github.auties00.smalt.image.gif.internal;

import com.github.auties00.smalt.MediaException;

import java.util.Arrays;

/**
 * Compresses the color indices of a GIF image with the variable-length-code LZW of GIF89a
 * (section 22 and appendix F) and writes them as data sub-blocks.
 *
 * <p>Codes start one bit wider than the minimum code size and grow up to 12 bits; the stream
 * opens with a clear code, emits a clear code and restarts its string table whenever the table
 * holds 4095 codes, and ends with the end-of-information code. The packed codes are written in
 * sub-blocks of up to 255 bytes, followed by the zero-length block terminator.
 *
 * <p>For the same indices and minimum code size the output is byte-identical to giflib's. An
 * instance reuses its tables across calls. Instances are not thread-safe.
 *
 * @implNote This implementation keeps giflib's 8192-entry open-addressing string table keyed by
 * the prefix code and the appended index, its code-width growth rule (the width grows after a code
 * is written once the next free code reaches the current width's limit) and its clear point.
 */
public final class GifLzwEncoder {
    /**
     * The number of entries of the string table, giflib's {@code HT_SIZE}.
     */
    private static final int TABLE_SIZE = 8192;

    /**
     * The mask that reduces a hash to a table index, giflib's {@code HT_KEY_MASK}.
     */
    private static final int TABLE_MASK = TABLE_SIZE - 1;

    /**
     * The code at which the table is restarted, giflib's {@code LZ_MAX_CODE}.
     */
    private static final int MAX_CODE = 4095;

    /**
     * The string table: each occupied entry packs the 20-bit key (prefix code times 256 plus the
     * appended index) above the 12-bit code; {@code -1} marks an empty entry.
     */
    private final int[] table = new int[TABLE_SIZE];

    /**
     * The packed code bytes of the image being compressed.
     */
    private byte[] packed = new byte[4096];

    /**
     * The number of valid bytes in {@link #packed}.
     */
    private int packedSize;

    /**
     * The bits not yet written to {@link #packed}, least significant first.
     */
    private long bits;

    /**
     * The number of valid bits in {@link #bits}.
     */
    private int bitCount;

    /**
     * Constructs an encoder.
     */
    public GifLzwEncoder() {
    }

    /**
     * Writes the LZW minimum code size, the compressed indices and the block terminator of an
     * image.
     *
     * @param indices the color indices, in the order they are stored (interlaced or not)
     * @param offset the index of the first entry
     * @param length the number of indices, at least 1
     * @param minCodeSize the LZW minimum code size, from 2 to 8; every index must be below
     * {@code 1 << minCodeSize}
     * @param out the datastream
     * @throws MediaException.LimitExceeded if the datastream would exceed the largest Java array
     */
    public void encode(byte[] indices, int offset, int length, int minCodeSize, GifOutput out) {
        out.write(minCodeSize);
        packedSize = 0;
        bits = 0;
        bitCount = 0;
        var clear = 1 << minCodeSize;
        var eof = clear + 1;
        var running = eof + 1;
        var width = minCodeSize + 1;
        var limit = 1 << width;
        var table = this.table;
        Arrays.fill(table, -1);
        emit(clear, width);
        var current = indices[offset] & 0xFF;
        var end = offset + length;
        for (var i = offset + 1; i < end; i++) {
            var pixel = indices[i] & 0xFF;
            var key = (current << 8) + pixel;
            var slot = ((key >>> 12) ^ key) & TABLE_MASK;
            var entry = table[slot];
            var found = -1;
            while (entry != -1) {
                if (entry >>> 12 == key) {
                    found = entry & 0xFFF;
                    break;
                }
                slot = (slot + 1) & TABLE_MASK;
                entry = table[slot];
            }
            if (found >= 0) {
                current = found;
                continue;
            }
            emit(current, width);
            if (running >= limit) {
                limit = 1 << ++width;
            }
            current = pixel;
            if (running >= MAX_CODE) {
                emit(clear, width);
                running = eof + 1;
                width = minCodeSize + 1;
                limit = 1 << width;
                Arrays.fill(table, -1);
            } else {
                table[slot] = (key << 12) | running++;
            }
        }
        emit(current, width);
        if (running >= limit) {
            limit = 1 << ++width;
        }
        emit(eof, width);
        while (bitCount > 0) {
            put((int) bits);
            bits >>>= 8;
            bitCount -= 8;
        }
        for (var start = 0; start < packedSize; start += 255) {
            var block = Math.min(255, packedSize - start);
            out.write(block);
            out.write(packed, start, block);
        }
        out.write(0);
    }

    /**
     * Appends a code to the bit stream.
     *
     * @param code the code value, below {@code 1 << width}
     * @param width the code width in bits
     */
    private void emit(int code, int width) {
        bits |= (long) code << bitCount;
        bitCount += width;
        while (bitCount >= 8) {
            put((int) bits);
            bits >>>= 8;
            bitCount -= 8;
        }
    }

    /**
     * Appends a byte to {@link #packed}.
     *
     * @param value the byte, in its low 8 bits
     * @throws MediaException.LimitExceeded if the packed codes would exceed the largest Java array
     */
    private void put(int value) {
        if (packedSize == packed.length) {
            if (packed.length >= Integer.MAX_VALUE / 2) {
                throw new MediaException.LimitExceeded("compressed GIF image exceeds the largest Java array");
            }
            packed = Arrays.copyOf(packed, packed.length * 2);
        }
        packed[packedSize++] = (byte) value;
    }
}
