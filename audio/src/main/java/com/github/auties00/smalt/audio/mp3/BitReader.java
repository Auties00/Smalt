package com.github.auties00.smalt.audio.mp3;

/**
 * Reads MSB-first bit fields from a byte range, returning zero past the end.
 *
 * <p>A read that would cross the limit still advances the position and returns zero, so callers
 * detect truncation afterwards with {@link #overrun()} instead of checking every field. Reads
 * never touch bytes outside the range.
 *
 * @implNote This implementation mirrors minimp3's {@code bs_t} and {@code get_bits}.
 */
final class BitReader {
    /**
     * The bytes being read.
     */
    byte[] data;

    /**
     * The index of the first byte of the range.
     */
    int start;

    /**
     * The position of the next bit, relative to {@link #start}.
     */
    int position;

    /**
     * The number of bits in the range.
     */
    int limit;

    /**
     * Points the reader at a byte range.
     *
     * @param data   the bytes
     * @param offset the index of the first byte
     * @param length the number of bytes; non-negative
     */
    void init(byte[] data, int offset, int length) {
        this.data = data;
        this.start = offset;
        this.position = 0;
        this.limit = Math.max(0, length) * 8;
    }

    /**
     * Reads an unsigned field.
     *
     * @param bits the field width, from 1 to 24
     * @return the value, or zero when the field crosses the limit
     */
    int read(int bits) {
        var shift = position & 7;
        var index = start + (position >> 3);
        position += bits;
        if (position > limit) {
            return 0;
        }
        var left = bits + shift;
        var next = data[index++] & (255 >> shift);
        var cache = 0;
        while ((left -= 8) > 0) {
            cache |= next << left;
            next = data[index++] & 0xFF;
        }
        return cache | (next >>> -left);
    }

    /**
     * Returns whether a read crossed the limit.
     *
     * @return {@code true} once the position is past the end of the range
     */
    boolean overrun() {
        return position > limit;
    }
}
