package com.github.auties00.smalt.video.intra.internal;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Writes a most-significant-bit-first bitstream into a growable byte array, as the ProRes and
 * VC-3 (DNxHD) entropy encoders produce it.
 *
 * <p>Instances are not thread-safe; every encoding thread owns its writer. A writer is reused
 * across slices or macroblock rows with {@link #reset()}.
 */
public final class IntraBitWriter {
    /**
     * Stores a big-endian word at a byte offset of a byte array.
     */
    private static final VarHandle WORD = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.BIG_ENDIAN);

    /**
     * The bytes written so far, followed by spare capacity.
     */
    private byte[] buffer;

    /**
     * The number of complete bytes in {@link #buffer}.
     */
    private int length;

    /**
     * The bits not yet stored in {@link #buffer}, in the low {@link #pending} bits.
     */
    private long accumulator;

    /**
     * The number of bits held in {@link #accumulator}, from 0 to 31.
     */
    private int pending;

    /**
     * Constructs a writer.
     *
     * @param capacity the initial capacity in bytes
     */
    public IntraBitWriter(int capacity) {
        this.buffer = new byte[Math.max(16, capacity)];
    }

    /**
     * Discards everything written.
     */
    public void reset() {
        length = 0;
        accumulator = 0;
        pending = 0;
    }

    /**
     * Writes the low bits of a value.
     *
     * @param value the value; bits above {@code count} are ignored
     * @param count the number of bits, from 0 to 32
     */
    public void write(int value, int count) {
        if (count == 0) {
            return;
        }
        accumulator = (accumulator << count) | (value & (0xFFFFFFFFL >>> (32 - count)));
        pending += count;
        if (pending >= 32) {
            pending -= 32;
            store((int) (accumulator >>> pending));
        }
    }

    /**
     * Makes room for a number of bytes and returns the buffer, for hot loops that pack bits with
     * their own accumulator and hand the state back with {@link #resume}.
     *
     * @param bytes the number of bytes the caller may append before the next call
     * @return the buffer, valid until the next call of a method of this writer
     */
    public byte[] reserve(int bytes) {
        if (length + bytes + 4 > buffer.length) {
            buffer = Arrays.copyOf(buffer, Math.max(buffer.length * 2, length + bytes + 4));
        }
        return buffer;
    }

    /**
     * Returns the number of complete bytes written, where a caller that packs bits itself appends.
     *
     * @return the byte count
     */
    public int length() {
        return length;
    }

    /**
     * Returns the bits not yet stored, in the low {@link #pending()} bits.
     *
     * @return the bits
     */
    public long accumulator() {
        return accumulator;
    }

    /**
     * Returns the number of bits not yet stored.
     *
     * @return the count, from 0 to 31
     */
    public int pending() {
        return pending;
    }

    /**
     * Replaces the state after a caller packed bits into the buffer returned by {@link #reserve}.
     *
     * @param length the number of complete bytes
     * @param accumulator the bits not yet stored, in the low {@code pending} bits
     * @param pending the number of bits not yet stored, from 0 to 31
     */
    public void resume(int length, long accumulator, int pending) {
        this.length = length;
        this.accumulator = accumulator;
        this.pending = pending;
    }

    /**
     * Stores a word big-endian at a byte offset.
     *
     * @param buffer the bytes
     * @param at the offset
     * @param word the 32 bits to store, the first in the most significant position
     */
    public static void storeWord(byte[] buffer, int at, int word) {
        WORD.set(buffer, at, word);
    }

    /**
     * Writes a long run of zero bits.
     *
     * @param count the number of bits, non-negative
     */
    public void writeZeros(int count) {
        while (count > 32) {
            write(0, 32);
            count -= 32;
        }
        write(0, count);
    }

    /**
     * Pads with zero bits up to the next byte boundary.
     */
    public void alignToByte() {
        var bits = (8 - (pending & 7)) & 7;
        write(0, bits);
    }

    /**
     * Returns the number of bits written.
     *
     * @return the bit count
     */
    public long bitCount() {
        return (long) length * 8 + pending;
    }

    /**
     * Returns the number of whole bytes written, after padding to a byte boundary.
     *
     * @return the byte count
     */
    public int byteCount() {
        return length + (pending + 7) / 8;
    }

    /**
     * Copies the bytes written, padded with zero bits to a byte boundary, into an array.
     *
     * @param destination the array
     * @param offset the index of the first byte to write
     * @return the number of bytes copied
     */
    public int copyTo(byte[] destination, int offset) {
        System.arraycopy(buffer, 0, destination, offset, length);
        var at = offset + length;
        var bits = pending;
        var tail = accumulator;
        while (bits > 0) {
            var shift = bits - 8;
            destination[at++] = (byte) (shift >= 0 ? tail >>> shift : tail << -shift);
            bits -= 8;
        }
        return at - offset;
    }

    /**
     * Returns the bytes written, padded with zero bits to a byte boundary.
     *
     * @return a new array
     */
    public byte[] toByteArray() {
        var result = new byte[byteCount()];
        copyTo(result, 0);
        return result;
    }

    /**
     * Appends four bytes to the buffer.
     *
     * @param word the bytes, most significant first
     */
    private void store(int word) {
        if (length + 4 > buffer.length) {
            buffer = Arrays.copyOf(buffer, Math.max(buffer.length * 2, length + 4));
        }
        WORD.set(buffer, length, word);
        length += 4;
    }
}
