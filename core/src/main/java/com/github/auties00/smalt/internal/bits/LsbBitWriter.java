package com.github.auties00.smalt.internal.bits;

import java.util.Arrays;

/**
 * Writes a bit stream least-significant bit first into a growable byte array, the counterpart of
 * {@link LsbBitReader}.
 */
public final class LsbBitWriter {
    /**
     * The completed bytes.
     */
    private byte[] buffer;

    /**
     * The number of completed bytes in {@link #buffer}.
     */
    private int size;

    /**
     * The pending bits, right-aligned: bit 0 is the oldest pending bit.
     */
    private long accumulator;

    /**
     * The number of pending bits, always below 8 between calls.
     */
    private int pending;

    /**
     * Constructs a writer with a default initial capacity.
     */
    public LsbBitWriter() {
        this(256);
    }

    /**
     * Constructs a writer with the given initial capacity.
     *
     * @param capacity the initial capacity in bytes; non-negative
     * @throws IllegalArgumentException if {@code capacity} is negative
     */
    public LsbBitWriter(int capacity) {
        if (capacity < 0) {
            throw new IllegalArgumentException("negative capacity " + capacity);
        }
        this.buffer = new byte[Math.max(capacity, 16)];
    }

    /**
     * Writes the low {@code bits} bits of {@code value}, lowest bit first.
     *
     * @param value the field value; bits above {@code bits} are ignored
     * @param bits the field width, from 0 to 32
     */
    public void write(int value, int bits) {
        accumulator |= (value & ((1L << bits) - 1)) << pending;
        pending += bits;
        while (pending >= 8) {
            put((byte) accumulator);
            accumulator >>>= 8;
            pending -= 8;
        }
    }

    /**
     * Writes the low {@code bits} bits of {@code value}, lowest bit first.
     *
     * @param value the field value; bits above {@code bits} are ignored
     * @param bits the field width, from 0 to 64
     */
    public void writeLong(long value, int bits) {
        if (bits > 32) {
            write((int) value, 32);
            write((int) (value >>> 32), bits - 32);
        } else {
            write((int) value, bits);
        }
    }

    /**
     * Pads with zero bits to the next byte boundary; does nothing when already aligned.
     */
    public void alignWithZeros() {
        if (pending != 0) {
            write(0, 8 - pending);
        }
    }

    /**
     * Writes whole bytes; the writer must be byte-aligned.
     *
     * @param bytes the source array
     * @param offset the index of the first byte
     * @param length the number of bytes
     * @throws IllegalStateException if the writer is not byte-aligned
     */
    public void writeBytes(byte[] bytes, int offset, int length) {
        if (pending != 0) {
            throw new IllegalStateException("writer is not byte-aligned");
        }
        if (size + length > buffer.length) {
            buffer = Arrays.copyOf(buffer, Math.max(buffer.length * 2, size + length));
        }
        System.arraycopy(bytes, offset, buffer, size, length);
        size += length;
    }

    /**
     * Returns the number of bits written so far.
     *
     * @return the bit count
     */
    public long bitCount() {
        return (long) size * 8 + pending;
    }

    /**
     * Returns a copy of the completed bytes, padding a partial last byte with zeros.
     *
     * @return the written bytes
     */
    public byte[] toByteArray() {
        var result = Arrays.copyOf(buffer, size + (pending != 0 ? 1 : 0));
        if (pending != 0) {
            result[size] = (byte) accumulator;
        }
        return result;
    }

    /**
     * Discards everything written, keeping the allocated capacity.
     */
    public void reset() {
        size = 0;
        pending = 0;
        accumulator = 0;
    }

    /**
     * Appends one completed byte.
     *
     * @param value the byte
     */
    private void put(byte value) {
        if (size == buffer.length) {
            buffer = Arrays.copyOf(buffer, buffer.length * 2);
        }
        buffer[size++] = value;
    }
}
