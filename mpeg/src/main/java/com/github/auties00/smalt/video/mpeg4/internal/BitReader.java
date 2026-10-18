package com.github.auties00.smalt.video.mpeg4.internal;

import com.github.auties00.smalt.MediaException;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Reads the most-significant-bit-first bit strings of MPEG-4 Visual and H.263 bitstreams.
 *
 * <p>The reader works on a private copy of the coded data followed by eight zero bytes, so a
 * peek never leaves the array: bits past the end of the data read as zeros. Reading past the end
 * is not an error by itself; {@link #overrun()} reports it, and decoders check it at macroblock
 * boundaries, where a truncated stream is detected without a test in every symbol read. The
 * position is a bit index that callers may save and restore, which the data-partitioned syntax
 * and the resynchronization searches use.
 *
 * <p>An instance is not thread-safe; it is reloaded with {@link #load(byte[], int, int)} for every
 * coded picture.
 */
public final class BitReader {
    /**
     * Reads eight bytes as one big-endian {@code long}.
     */
    private static final VarHandle LONG_BE =
            MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.BIG_ENDIAN);

    /**
     * The number of zero bytes kept after the data so that a peek never leaves the array.
     */
    private static final int PADDING = 16;

    /**
     * The coded data followed by {@link #PADDING} zero bytes.
     */
    private byte[] buffer = new byte[256];

    /**
     * The number of bits of coded data.
     */
    private long limit;

    /**
     * The largest byte index a peek reads eight bytes from.
     */
    private long lastWord;

    /**
     * The index of the next bit to read.
     */
    private long position;

    /**
     * Creates an empty reader.
     */
    public BitReader() {
    }

    /**
     * Loads a range of coded data and moves to its first bit.
     *
     * @param data the array holding the data; it is copied
     * @param offset the index of the first byte
     * @param length the number of bytes
     */
    public void load(byte[] data, int offset, int length) {
        if (buffer.length < length + PADDING) {
            buffer = new byte[length + PADDING + (length >> 2)];
        }
        System.arraycopy(data, offset, buffer, 0, length);
        Arrays.fill(buffer, length, Math.min(buffer.length, length + PADDING), (byte) 0);
        limit = (long) length << 3;
        lastWord = length + PADDING - 8;
        position = 0;
    }

    /**
     * Returns the array the bits are read from, zero padded after the data.
     *
     * @return the array
     */
    byte[] buffer() {
        return buffer;
    }

    /**
     * Returns the last byte index from which an eight-byte word can be read; {@link #peek} yields
     * zero bits beyond it.
     *
     * @return the index
     */
    long lastWord() {
        return lastWord;
    }

    /**
     * Returns the next {@code count} bits without consuming them.
     *
     * @param count the number of bits, from 1 to 32
     * @return the bits as an unsigned value
     */
    public int peek(int count) {
        var at = position;
        var index = at >>> 3;
        if (index > lastWord) {
            return 0;
        }
        var word = (long) LONG_BE.get(buffer, (int) index);
        return (int) ((word << (at & 7)) >>> (64 - count));
    }

    /**
     * Consumes bits.
     *
     * @param count the number of bits, not negative
     */
    public void skip(int count) {
        position += count;
    }

    /**
     * Reads an unsigned value.
     *
     * @param count the number of bits, from 1 to 32
     * @return the value
     */
    public int read(int count) {
        var value = peek(count);
        position += count;
        return value;
    }

    /**
     * Reads one bit.
     *
     * @return {@code 0} or {@code 1}
     */
    public int readBit() {
        var value = peek(1);
        position++;
        return value;
    }

    /**
     * Reads one bit as a flag.
     *
     * @return {@code true} when the bit is one
     */
    public boolean readFlag() {
        return readBit() != 0;
    }

    /**
     * Reads a marker bit, which must be one.
     *
     * @param name the syntax element, for the error message
     * @throws MediaException.Malformed if the bit is zero
     */
    public void marker(String name) {
        if (readBit() != 1) {
            throw new MediaException.Malformed("missing marker bit after " + name);
        }
    }

    /**
     * Returns the index of the next bit to read.
     *
     * @return the bit position
     */
    public long position() {
        return position;
    }

    /**
     * Moves to a bit position.
     *
     * @param position the bit index, not negative
     */
    public void seek(long position) {
        this.position = position;
    }

    /**
     * Returns the number of bits of coded data.
     *
     * @return the length in bits
     */
    public long limit() {
        return limit;
    }

    /**
     * Returns the number of coded bits left, negative after an overrun.
     *
     * @return the remaining bits
     */
    public long remaining() {
        return limit - position;
    }

    /**
     * Returns whether more bits were consumed than the data holds.
     *
     * @return {@code true} after an overrun
     */
    public boolean overrun() {
        return position > limit;
    }

    /**
     * Returns whether the position is on a byte boundary.
     *
     * @return {@code true} when aligned
     */
    public boolean aligned() {
        return (position & 7) == 0;
    }

    /**
     * Moves to the next byte boundary, or stays when already on one.
     */
    public void align() {
        position = (position + 7) & ~7L;
    }

    /**
     * Returns the bit position from which {@code nextbits_bytealigned()} of ISO/IEC 14496-2 5.2.3
     * reads: the next byte boundary, past one byte of stuffing {@code 01111111} when the position
     * is already aligned and that byte follows.
     *
     * @return the bit position
     */
    public long byteAlignedPosition() {
        if ((position & 7) == 0) {
            return peek(8) == 0x7F ? position + 8 : position;
        }
        return (position + 7) & ~7L;
    }

    /**
     * Returns bits at an absolute position without moving.
     *
     * @param at the bit position
     * @param count the number of bits, from 1 to 32
     * @return the bits as an unsigned value
     */
    public int peekAt(long at, int count) {
        var saved = position;
        position = at;
        var value = peek(count);
        position = saved;
        return value;
    }

    /**
     * Returns the coded byte at an index, or zero past the end.
     *
     * @param index the byte index
     * @return the byte as an unsigned value
     */
    public int byteAt(int index) {
        return index < 0 || index >= limit >>> 3 ? 0 : buffer[index] & 0xFF;
    }

    /**
     * Returns the number of coded bytes.
     *
     * @return the length in bytes
     */
    public int byteLength() {
        return (int) (limit >>> 3);
    }
}
