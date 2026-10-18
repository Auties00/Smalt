package com.github.auties00.smalt.internal.bits;

import java.util.Arrays;

/**
 * Writes a bit stream most-significant bit first into a growable byte array, the counterpart of
 * {@link MsbBitReader}.
 *
 * <p>The writer can save its position with {@link #mark()} and discard everything written after it
 * with {@link #rollback(long)}, so that a syntax element can be coded again after an attempt turned
 * out too large. Instances are not thread-safe.
 */
public final class MsbBitWriter {
    /**
     * The completed bytes.
     */
    private byte[] buffer;

    /**
     * The number of completed bytes in {@link #buffer}.
     */
    private int size;

    /**
     * The pending bits, right-aligned: the lowest {@link #pending} bits are not yet stored.
     */
    private long accumulator;

    /**
     * The number of pending bits, always below 32 between calls.
     */
    private int pending;

    /**
     * Constructs a writer with a default initial capacity.
     */
    public MsbBitWriter() {
        this(256);
    }

    /**
     * Constructs a writer with the given initial capacity.
     *
     * @param capacity the initial capacity in bytes; non-negative
     * @throws IllegalArgumentException if {@code capacity} is negative
     */
    public MsbBitWriter(int capacity) {
        if (capacity < 0) {
            throw new IllegalArgumentException("negative capacity " + capacity);
        }
        this.buffer = new byte[Math.max(capacity, 16)];
    }

    /**
     * Writes the low {@code bits} bits of {@code value}.
     *
     * @param value the field value; bits above {@code bits} are ignored
     * @param bits the field width, from 0 to 32
     */
    public void write(int value, int bits) {
        accumulator = (accumulator << bits) | (value & ((1L << bits) - 1));
        pending += bits;
        if (pending >= 32) {
            pending -= 32;
            put32((int) (accumulator >>> pending));
        }
    }

    /**
     * Writes one bit.
     *
     * @param bit the bit, in the lowest bit of the value; higher bits are ignored
     */
    public void writeBit(int bit) {
        accumulator = (accumulator << 1) | (bit & 1);
        if (++pending == 32) {
            pending = 0;
            put32((int) accumulator);
        }
    }

    /**
     * Writes the low {@code bits} bits of {@code value}.
     *
     * @param value the field value; bits above {@code bits} are ignored
     * @param bits the field width, from 0 to 64
     */
    public void writeLong(long value, int bits) {
        if (bits > 32) {
            write((int) (value >>> 32), bits - 32);
            write((int) value, 32);
        } else {
            write((int) value, bits);
        }
    }

    /**
     * Writes an unsigned Exp-Golomb code, {@code ue(v)} in the H.264 and HEVC syntax.
     *
     * @param value the value to code, from 0 to {@code 2^32 - 2}
     * @throws IllegalArgumentException if {@code value} is out of range
     */
    public void writeUe(long value) {
        if (value < 0 || value > 0xFFFFFFFEL) {
            throw new IllegalArgumentException("Exp-Golomb value out of range: " + value);
        }
        var coded = value + 1;
        var length = 64 - Long.numberOfLeadingZeros(coded);
        if (length <= 16) {
            write((int) coded, 2 * length - 1);
        } else {
            write(0, length - 1);
            writeLong(coded, length);
        }
    }

    /**
     * Writes a signed Exp-Golomb code, {@code se(v)} in the H.264 and HEVC syntax.
     *
     * @param value the value to code
     */
    public void writeSe(int value) {
        writeUe(value > 0 ? 2L * value - 1 : -2L * value);
    }

    /**
     * Writes a stop bit and zero bits up to the next byte boundary, {@code rbsp_trailing_bits()} in
     * the H.264 and HEVC syntax.
     */
    public void writeTrailingBits() {
        writeBit(1);
        alignWithZeros();
    }

    /**
     * Pads with zero bits to the next byte boundary; does nothing when already aligned.
     */
    public void alignWithZeros() {
        var fill = -pending & 7;
        if (fill != 0) {
            write(0, fill);
        }
    }

    /**
     * Pads with one bits to the next byte boundary; does nothing when already aligned.
     */
    public void alignWithOnes() {
        var fill = -pending & 7;
        if (fill != 0) {
            write(0xFF, fill);
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
        if ((pending & 7) != 0) {
            throw new IllegalStateException("writer is not byte-aligned");
        }
        flushBytes();
        ensure(length);
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
     * Returns whether the writer sits on a byte boundary.
     *
     * @return {@code true} when the bit count is a multiple of eight
     */
    public boolean isByteAligned() {
        return (pending & 7) == 0;
    }

    /**
     * Returns a mark that {@link #rollback(long)} can return to.
     *
     * <p>A mark stays valid until the writer is reset or rolled back to an earlier position.
     *
     * @return the current position in bits
     */
    public long mark() {
        return bitCount();
    }

    /**
     * Discards everything written after a mark, keeping the bits before it.
     *
     * @param mark a value returned by {@link #mark()} on this writer, at or before the current
     * position
     */
    public void rollback(long mark) {
        flushBytes();
        if (pending > 0) {
            ensure(1);
            buffer[size] = (byte) (accumulator << (8 - pending));
        }
        var bytes = (int) (mark >>> 3);
        var bits = (int) (mark & 7);
        size = bytes;
        pending = bits;
        accumulator = bits == 0 ? 0 : (buffer[bytes] & 0xFF) >>> (8 - bits);
    }

    /**
     * {@return the number of completed bytes, which excludes a partial last byte}
     */
    public int size() {
        flushBytes();
        return size;
    }

    /**
     * {@return the backing array, not a copy} Its first {@link #size()} bytes are the completed
     * bytes; the array is replaced when a later write grows it, and a partial last byte is not
     * stored in it.
     */
    public byte[] array() {
        flushBytes();
        return buffer;
    }

    /**
     * Returns a copy of the completed bytes, padding a partial last byte with zeros.
     *
     * @return the written bytes
     */
    public byte[] toByteArray() {
        flushBytes();
        var result = Arrays.copyOf(buffer, size + (pending != 0 ? 1 : 0));
        if (pending != 0) {
            result[size] = (byte) (accumulator << (8 - pending));
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
     * Moves every completed pending byte into the buffer, leaving fewer than 8 pending bits.
     */
    private void flushBytes() {
        if (pending >= 8) {
            ensure(4);
            while (pending >= 8) {
                pending -= 8;
                buffer[size++] = (byte) (accumulator >>> pending);
            }
        }
    }

    /**
     * Appends four bytes in big-endian order.
     *
     * @param value the bytes
     */
    private void put32(int value) {
        ensure(4);
        buffer[size] = (byte) (value >>> 24);
        buffer[size + 1] = (byte) (value >>> 16);
        buffer[size + 2] = (byte) (value >>> 8);
        buffer[size + 3] = (byte) value;
        size += 4;
    }

    /**
     * Grows the buffer to fit {@code extra} more bytes.
     *
     * @param extra the number of bytes about to be appended
     */
    private void ensure(int extra) {
        if (size + extra > buffer.length) {
            buffer = Arrays.copyOf(buffer, Math.max(buffer.length * 2, size + extra + 16));
        }
    }
}
