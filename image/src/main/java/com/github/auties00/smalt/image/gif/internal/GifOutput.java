package com.github.auties00.smalt.image.gif.internal;

import com.github.auties00.smalt.MediaException;

import java.util.Arrays;

/**
 * Accumulates the bytes of a GIF datastream in memory.
 *
 * <p>Multi-byte fields are written little-endian, as GIF stores them. Instances are not
 * thread-safe.
 */
public final class GifOutput {
    /**
     * The largest datastream an instance holds, a little below the largest Java array.
     */
    private static final int MAX_SIZE = Integer.MAX_VALUE - 16;

    /**
     * The bytes written so far.
     */
    private byte[] buffer;

    /**
     * The number of valid bytes in {@link #buffer}.
     */
    private int size;

    /**
     * Constructs an output with an initial capacity.
     *
     * @param capacity the initial capacity in bytes, at least 16
     */
    public GifOutput(int capacity) {
        this.buffer = new byte[Math.max(16, capacity)];
    }

    /**
     * Appends one byte.
     *
     * @param value the byte, in its low 8 bits
     * @throws MediaException.LimitExceeded if the datastream would exceed the largest Java array
     */
    public void write(int value) {
        if (size == buffer.length) {
            reserve(1);
        }
        buffer[size++] = (byte) value;
    }

    /**
     * Appends a 16-bit little-endian value.
     *
     * @param value the value, in its low 16 bits
     * @throws MediaException.LimitExceeded if the datastream would exceed the largest Java array
     */
    public void write16(int value) {
        reserve(2);
        buffer[size] = (byte) value;
        buffer[size + 1] = (byte) (value >>> 8);
        size += 2;
    }

    /**
     * Appends bytes.
     *
     * @param data the source
     * @param offset the index of the first byte
     * @param length the number of bytes
     * @throws MediaException.LimitExceeded if the datastream would exceed the largest Java array
     */
    public void write(byte[] data, int offset, int length) {
        reserve(length);
        System.arraycopy(data, offset, buffer, size, length);
        size += length;
    }

    /**
     * Returns the number of bytes written.
     *
     * @return the size in bytes
     */
    public int size() {
        return size;
    }

    /**
     * Returns the datastream written so far.
     *
     * @return a copy of the bytes
     */
    public byte[] toByteArray() {
        return Arrays.copyOf(buffer, size);
    }

    /**
     * Grows the buffer to hold {@code extra} more bytes.
     *
     * @param extra the number of bytes about to be appended
     * @throws MediaException.LimitExceeded if the datastream would exceed the largest Java array
     */
    private void reserve(long extra) {
        var needed = size + extra;
        if (needed > MAX_SIZE) {
            throw new MediaException.LimitExceeded("encoded GIF exceeds the largest Java array");
        }
        if (needed > buffer.length) {
            buffer = Arrays.copyOf(buffer, (int) Math.min(MAX_SIZE, Math.max(needed, buffer.length * 2L)));
        }
    }
}
