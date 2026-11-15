package com.github.auties00.smalt.aot.internal;

import java.io.ByteArrayOutputStream;

/**
 * Writes bits most significant first, for the coded sensor data of the synthetic raw files. Instances are not
 * thread-safe.
 */
final class BitWriter {
    /**
     * The bytes written.
     */
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    /**
     * Whether a zero byte follows every {@code FF} byte.
     */
    private final boolean stuffing;

    /**
     * The pending bits, in the low {@link #count} bits.
     */
    private long buffer;

    /**
     * The number of pending bits, less than 8 between calls.
     */
    private int count;

    /**
     * The number of bytes of data written, stuffed bytes excluded.
     */
    private int written;

    /**
     * Constructs a writer.
     *
     * @param stuffing whether a zero byte follows every {@code FF} byte
     */
    BitWriter(boolean stuffing) {
        this.stuffing = stuffing;
    }

    /**
     * Writes a code.
     *
     * @param code the code and its length, or {@code null} for a code whose value is unknown
     * @throws IllegalStateException if the code is {@code null}
     */
    void code(int[] code) {
        if (code == null) {
            throw new IllegalStateException("the synthetic picture needs a code whose value is unknown");
        }
        write(code[0], code[1]);
    }

    /**
     * Writes the low bits of a value.
     *
     * @param value the bits, in its low {@code bits} bits
     * @param bits the number of bits, from 0 to 32
     */
    void write(int value, int bits) {
        buffer = (buffer << bits) | (value & ((1L << bits) - 1));
        count += bits;
        while (count >= 8) {
            count -= 8;
            put((int) (buffer >>> count) & 0xFF);
        }
    }

    /**
     * Returns the number of bits written so far.
     *
     * @return the count, stuffed bytes excluded
     */
    long bits() {
        return (long) written * 8 + count;
    }

    /**
     * Writes a byte.
     *
     * @param value the byte
     */
    private void put(int value) {
        out.write(value);
        written++;
        if (stuffing && value == 0xFF) {
            out.write(0);
        }
    }

    /**
     * Pads the last byte with one bits when stuffing, zero bits otherwise, and returns the bytes.
     *
     * @return the bytes written
     */
    byte[] bytes() {
        if (count > 0) {
            write(stuffing ? (1 << (8 - count)) - 1 : 0, 8 - count);
        }
        return out.toByteArray();
    }
}
