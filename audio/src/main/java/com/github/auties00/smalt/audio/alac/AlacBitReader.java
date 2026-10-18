package com.github.auties00.smalt.audio.alac;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Reads the most-significant-bit-first bit stream of an ALAC packet.
 *
 * <p>The packet is copied into a buffer with sixteen zero bytes of padding, so every read, even
 * one past the end of a corrupt packet, stays inside the buffer; {@link #overrun()} reports
 * whether a read went past the packet's last bit.
 */
final class AlacBitReader {
    /**
     * The zero bytes after the packet.
     */
    private static final int PADDING = 16;

    /**
     * Reads eight bytes as a big-endian {@code long}.
     */
    private static final VarHandle LONG_BE = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.BIG_ENDIAN);

    /**
     * The packet bytes followed by the padding.
     */
    private byte[] buffer;

    /**
     * The number of packet bits.
     */
    private long limit;

    /**
     * The position of the next bit.
     */
    private long position;

    /**
     * Creates an empty reader.
     */
    AlacBitReader() {
        this.buffer = new byte[PADDING];
    }

    /**
     * Starts reading a packet.
     *
     * @param data the packet
     */
    void reset(byte[] data) {
        if (buffer.length < data.length + PADDING) {
            buffer = new byte[data.length + PADDING];
        }
        System.arraycopy(data, 0, buffer, 0, data.length);
        Arrays.fill(buffer, data.length, data.length + PADDING, (byte) 0);
        limit = (long) data.length * 8;
        position = 0;
    }

    /**
     * Returns the 64 bits starting at the current position.
     *
     * @return the bits, the next bit in the most significant position; zeros past the packet
     */
    long peek64() {
        var index = (int) Math.min(position >>> 3, (limit >>> 3) + 1);
        var buf = buffer;
        var value = (long) LONG_BE.get(buf, index);
        var offset = (int) (position & 7);
        if (offset != 0) {
            value = (value << offset) | ((buf[index + 8] & 0xFF) >>> (8 - offset));
        }
        return position >= limit + 8 ? 0 : value;
    }

    /**
     * Reads an unsigned value.
     *
     * @param bits the number of bits, from 0 to 32
     * @return the value
     */
    int read(int bits) {
        if (bits == 0) {
            return 0;
        }
        var value = (int) (peek64() >>> (64 - bits));
        position += bits;
        return value;
    }

    /**
     * Reads a two's complement signed value.
     *
     * @param bits the number of bits, from 1 to 32
     * @return the sign-extended value
     */
    int readSigned(int bits) {
        var value = read(bits);
        return (value << (32 - bits)) >> (32 - bits);
    }

    /**
     * Skips bits.
     *
     * @param bits the number of bits to skip, non-negative
     */
    void skip(long bits) {
        position += bits;
    }

    /**
     * Advances to the next byte boundary.
     */
    void alignToByte() {
        position = (position + 7) & ~7L;
    }

    /**
     * Returns the position of the next bit.
     *
     * @return the bit index from the start of the packet
     */
    long position() {
        return position;
    }

    /**
     * Moves to a bit position.
     *
     * @param position the bit index from the start of the packet
     */
    void position(long position) {
        this.position = position;
    }

    /**
     * Returns whether every bit of the packet has been read.
     *
     * @return {@code true} when the position is at or past the packet's end
     */
    boolean exhausted() {
        return position >= limit;
    }

    /**
     * Returns whether a read went past the packet's last bit.
     *
     * @return {@code true} after an overrun
     */
    boolean overrun() {
        return position > limit;
    }
}
