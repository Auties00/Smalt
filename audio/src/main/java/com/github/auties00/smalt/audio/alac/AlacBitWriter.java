package com.github.auties00.smalt.audio.alac;

import java.util.Arrays;

/**
 * Writes an ALAC packet most significant bit first into a reusable buffer.
 *
 * <p>Every bit past the write position is zero, so a write only ORs its bits in and
 * {@link #rewind(long)} clears what it takes back.
 *
 * <p>Arguments are not checked; values outside the documented ranges give unspecified results.
 * Instances are not thread-safe.
 */
final class AlacBitWriter {
    /**
     * The packet buffer.
     */
    private final byte[] buffer;

    /**
     * The write position in bits.
     */
    private long position;

    /**
     * Constructs a writer.
     *
     * @param capacity the largest packet in bytes
     */
    AlacBitWriter(int capacity) {
        this.buffer = new byte[capacity];
    }

    /**
     * Clears the packet and moves to its start.
     */
    void reset() {
        Arrays.fill(buffer, 0, (int) ((position + 7) >>> 3), (byte) 0);
        position = 0;
    }

    /**
     * {@return the write position in bits}
     */
    long position() {
        return position;
    }

    /**
     * Moves back to an earlier position, clearing every bit written after it.
     *
     * @param target the position, at most {@link #position()}
     */
    void rewind(long target) {
        var first = (int) (target >>> 3);
        var end = (int) ((position + 7) >>> 3);
        var used = (int) (target & 7);
        if (used != 0) {
            buffer[first] &= (byte) (0xFF00 >>> used);
            first++;
        }
        Arrays.fill(buffer, first, Math.max(first, end), (byte) 0);
        position = target;
    }

    /**
     * Writes the low bits of a value.
     *
     * @param value the value, of which only the low {@code bits} bits are written
     * @param bits the number of bits, from 1 to 32
     */
    void write(int value, int bits) {
        var used = (int) (position & 7);
        var index = (int) (position >>> 3);
        var total = used + bits;
        var field = (value & (-1L >>> (64 - bits))) << (64 - total);
        var buffer = this.buffer;
        for (var shift = 56; total > 0; shift -= 8, total -= 8) {
            buffer[index++] |= (byte) (field >>> shift);
        }
        position += bits;
    }

    /**
     * Writes zero bits up to the next byte boundary.
     */
    void alignToByte() {
        position = (position + 7) & ~7L;
    }

    /**
     * {@return a copy of the packet written so far, which must end on a byte boundary}
     */
    byte[] toByteArray() {
        return Arrays.copyOf(buffer, (int) (position >>> 3));
    }
}
