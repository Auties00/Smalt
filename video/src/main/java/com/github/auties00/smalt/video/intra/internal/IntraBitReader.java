package com.github.auties00.smalt.video.intra.internal;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/**
 * Reads a most-significant-bit-first bitstream out of a byte array, as the ProRes and VC-3
 * (DNxHD) entropy decoders consume it.
 *
 * <p>The reader covers a window of the array between a start and an end byte, and keeps its
 * position in bits from the beginning of the array. Reads past the end of the window are allowed
 * and return the bytes that follow it, or zero bits past the end of the array; the caller checks
 * {@link #overrun()} once a syntax structure is complete. Bit positions are {@code int}s, so the
 * array must be shorter than {@link #MAX_BYTES} bytes.
 *
 * <p>Instances are not thread-safe; every decoding thread owns its reader.
 */
public final class IntraBitReader {
    /**
     * The largest array length a reader accepts, so that every bit position fits an {@code int}.
     */
    public static final int MAX_BYTES = 1 << 28;

    /**
     * Reads eight bytes of an array as one big-endian {@code long}.
     */
    private static final VarHandle LONG_BE = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.BIG_ENDIAN);

    /**
     * The array being read.
     */
    private byte[] data;

    /**
     * The position of the next bit, counted from the first bit of {@link #data}.
     */
    private int position;

    /**
     * The bit position at which the window ends.
     */
    private int end;

    /**
     * Constructs a reader over an empty array.
     */
    public IntraBitReader() {
        this.data = new byte[0];
    }

    /**
     * Positions the reader on a window of an array.
     *
     * @param data the array, shorter than {@link #MAX_BYTES} bytes
     * @param start the index of the first byte of the window
     * @param end the index one past the last byte of the window
     */
    public void reset(byte[] data, int start, int end) {
        this.data = data;
        this.position = start << 3;
        this.end = end << 3;
    }

    /**
     * Returns the 64 bits that start at the current position, without consuming them.
     *
     * <p>Bits past the end of the array read as zero.
     *
     * @return the bits, the next one in the most significant position
     */
    public long window() {
        return word(data, position >>> 3) << (position & 7);
    }

    /**
     * Returns the array being read, for decoders that keep their own bit cache between two calls
     * of {@link #seek(int)}.
     *
     * @return the array
     */
    public byte[] array() {
        return data;
    }

    /**
     * Reads eight bytes of an array as one big-endian {@code long}.
     *
     * <p>Bytes past the end of the array read as zero.
     *
     * @param bytes the array
     * @param index the index of the first byte, non-negative
     * @return the eight bytes, the first in the most significant position
     */
    public static long word(byte[] bytes, int index) {
        if (index <= bytes.length - 8) {
            return (long) LONG_BE.get(bytes, index);
        }
        var word = 0L;
        for (var i = 0; i < 8; i++) {
            var at = index + i;
            word = (word << 8) | (at < bytes.length ? bytes[at] & 0xFF : 0);
        }
        return word;
    }

    /**
     * Consumes bits.
     *
     * @param count the number of bits, non-negative
     */
    public void skip(int count) {
        position += count;
    }

    /**
     * Reads an unsigned value.
     *
     * @param count the number of bits, from 0 to 32
     * @return the value
     */
    public int read(int count) {
        if (count == 0) {
            return 0;
        }
        var value = (int) (window() >>> (64 - count));
        position += count;
        return value;
    }

    /**
     * Reads one bit.
     *
     * @return {@code 0} or {@code 1}
     */
    public int readBit() {
        var value = (int) (window() >>> 63);
        position++;
        return value;
    }

    /**
     * Returns the position of the next bit, counted from the first bit of the array.
     *
     * @return the bit position
     */
    public int position() {
        return position;
    }

    /**
     * Moves to a bit position.
     *
     * @param position the bit position, counted from the first bit of the array
     */
    public void seek(int position) {
        this.position = position;
    }

    /**
     * Returns the number of bits between the current position and the end of the window.
     *
     * @return the remaining bits, negative once the reader has moved past the end
     */
    public int remaining() {
        return end - position;
    }

    /**
     * Returns whether the reader has moved past the end of its window.
     *
     * @return {@code true} after an overrun
     */
    public boolean overrun() {
        return position > end;
    }

    /**
     * Advances to the next byte boundary.
     */
    public void alignToByte() {
        position = (position + 7) & ~7;
    }
}
