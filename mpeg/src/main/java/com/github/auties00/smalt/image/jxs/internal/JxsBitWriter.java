package com.github.auties00.smalt.image.jxs.internal;

import java.util.Arrays;

/**
 * Writes bits most significant bit first into a growing byte array.
 *
 * <p>Instances are not thread-safe.
 */
final class JxsBitWriter {
    private byte[] data;
    private int length;
    private long cache;
    private int count;

    JxsBitWriter(int capacity) {
        data = new byte[Math.max(capacity, 64)];
    }

    /**
     * Writes the low {@code bits} bits of {@code value}, from 1 to 32.
     */
    void write(long value, int bits) {
        cache = cache << bits | value & ((1L << bits) - 1);
        count += bits;
        while (count >= 8) {
            count -= 8;
            put((byte) (cache >>> count));
        }
    }

    /**
     * Writes a unary code: {@code value} one bits and a zero bit.
     */
    void unary(int value) {
        while (value >= 31) {
            write(0x7FFFFFFFL, 31);
            value -= 31;
        }
        write(((1L << value) - 1) << 1, value + 1);
    }

    /**
     * Pads with zero bits to the next byte boundary.
     */
    void align() {
        if (count > 0) {
            write(0, 8 - count);
        }
    }

    /**
     * Writes whole bytes; the writer must be at a byte boundary.
     */
    void bytes(byte[] source, int offset, int size) {
        ensure(size);
        System.arraycopy(source, offset, data, length, size);
        length += size;
    }

    /**
     * {@return the number of complete bytes written}
     */
    int size() {
        return length;
    }

    /**
     * Overwrites a byte already written.
     */
    void set(int position, int value) {
        data[position] = (byte) value;
    }

    /**
     * {@return the bytes written, a copy}
     */
    byte[] toByteArray() {
        return Arrays.copyOf(data, length);
    }

    /**
     * {@return the backing array, valid up to {@link #size()}}
     */
    byte[] array() {
        return data;
    }

    /**
     * Discards everything after the first {@code size} bytes; the writer must be at a byte boundary.
     */
    void truncate(int size) {
        length = size;
    }

    private void put(byte value) {
        if (length == data.length) {
            data = Arrays.copyOf(data, data.length * 2);
        }
        data[length++] = value;
    }

    private void ensure(int extra) {
        if (length + extra > data.length) {
            data = Arrays.copyOf(data, Math.max(data.length * 2, length + extra));
        }
    }
}
