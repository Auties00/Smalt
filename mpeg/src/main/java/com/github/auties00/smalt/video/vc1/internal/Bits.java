package com.github.auties00.smalt.video.vc1.internal;

/**
 * Reads the bits of a VC-1 bitstream data unit, most significant bit first.
 *
 * <p>Reads past the end return zero bits and set {@link #overrun()}, so a decoder checks for a truncated unit once
 * per macroblock row instead of on every read. Instances are not thread-safe; one reader is reused for every unit.
 */
final class Bits {
    /**
     * The bytes read.
     */
    private byte[] data = new byte[0];

    /**
     * The index after the last byte of the unit.
     */
    private int end;

    /**
     * The position of the next bit, counted from the start of {@link #data}.
     */
    private long position;

    /**
     * The bit position at the end of the unit.
     */
    private long limit;

    /**
     * Points the reader at a byte range.
     *
     * @param data the bytes, not copied
     * @param offset the first byte
     * @param length the number of bytes
     */
    void reset(byte[] data, int offset, int length) {
        this.data = data;
        this.end = offset + length;
        this.position = (long) offset << 3;
        this.limit = (long) end << 3;
    }

    /**
     * Reads an unsigned field.
     *
     * @param count the field width, from 0 to 32
     * @return the field
     */
    int read(int count) {
        if (count == 0) {
            return 0;
        }
        var value = peek(count);
        position += count;
        return value;
    }

    /**
     * Reads one bit.
     *
     * @return the bit
     */
    int readBit() {
        var at = position;
        position = at + 1;
        var index = (int) (at >>> 3);
        if (index >= end) {
            return 0;
        }
        return (data[index] >>> (7 - (int) (at & 7))) & 1;
    }

    /**
     * Reads one bit as a flag.
     *
     * @return whether the bit is 1
     */
    boolean readFlag() {
        return readBit() != 0;
    }

    /**
     * Returns the next bits without consuming them, padding with zeros past the end.
     *
     * @param count the number of bits, from 1 to 32
     * @return the bits
     */
    int peek(int count) {
        var index = (int) (position >>> 3);
        var shift = (int) (position & 7);
        long window;
        if (index + 5 <= end) {
            window = ((long) (data[index] & 0xFF) << 32) | ((long) (data[index + 1] & 0xFF) << 24)
                    | ((data[index + 2] & 0xFF) << 16) | ((data[index + 3] & 0xFF) << 8) | (data[index + 4] & 0xFF);
        } else {
            window = 0;
            for (var i = 0; i < 5; i++) {
                window <<= 8;
                if (index + i < end && index + i >= 0) {
                    window |= data[index + i] & 0xFF;
                }
            }
        }
        return (int) ((window << (24 + shift)) >>> (64 - count));
    }

    /**
     * Skips bits.
     *
     * @param count the number of bits
     */
    void skip(long count) {
        position += count;
    }

    /**
     * Skips to the next byte boundary.
     */
    void alignToByte() {
        position = (position + 7) & ~7L;
    }

    /**
     * {@return the bit position from the start of the array}
     */
    long position() {
        return position;
    }

    /**
     * Moves to a bit position.
     *
     * @param position the bit position from the start of the array
     */
    void seek(long position) {
        this.position = position;
    }

    /**
     * {@return the number of bits left, negative once past the end}
     */
    long remaining() {
        return limit - position;
    }

    /**
     * {@return whether a read went past the end}
     */
    boolean overrun() {
        return position > limit;
    }

    /**
     * {@return the backing array}
     */
    byte[] data() {
        return data;
    }
}
