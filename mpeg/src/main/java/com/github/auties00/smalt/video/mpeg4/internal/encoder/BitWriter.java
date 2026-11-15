package com.github.auties00.smalt.video.mpeg4.internal.encoder;

import java.util.Arrays;

/**
 * Writes a bit stream most significant bit first into a growable buffer that is reused from picture
 * to picture.
 *
 * <p>Instances are not thread-safe.
 */
public final class BitWriter {
    /**
     * The bytes written so far; only the first {@link #bytes} are valid.
     */
    private byte[] buffer = new byte[1 << 12];

    /**
     * The number of complete bytes in {@link #buffer}.
     */
    private int bytes;

    /**
     * The pending bits, right-aligned.
     */
    private long pending;

    /**
     * The number of pending bits, from 0 to 7 between calls.
     */
    private int count;

    /**
     * Constructs an empty writer.
     */
    public BitWriter() {
    }

    /**
     * Writes the low bits of a value.
     *
     * @param value the bits, right-aligned; higher bits are ignored
     * @param length the number of bits, from 0 to 32
     */
    public void write(int value, int length) {
        if (length == 0) {
            return;
        }
        pending = pending << length | (value & 0xFFFFFFFFL) & ((1L << length) - 1);
        count += length;
        while (count >= 8) {
            count -= 8;
            put((byte) (pending >>> count));
        }
        pending &= (1L << count) - 1;
    }

    /**
     * Writes one bit.
     *
     * @param bit the bit, 0 or 1
     */
    public void bit(int bit) {
        write(bit, 1);
    }

    /**
     * Writes one flag as a bit.
     *
     * @param flag the value written, 1 for true
     */
    public void flag(boolean flag) {
        write(flag ? 1 : 0, 1);
    }

    /**
     * Writes zero bits up to the next byte boundary.
     */
    public void alignWithZeros() {
        if (count > 0) {
            write(0, 8 - count);
        }
    }

    /**
     * Writes the stuffing of ISO/IEC 14496-2 {@code next_start_code()} and {@code next_resync_marker()}: a zero bit
     * then one bits up to the next byte boundary, eight bits when the stream is aligned already.
     */
    public void stuffing() {
        var length = 8 - count;
        write((1 << (length - 1)) - 1, length);
    }

    /**
     * {@return whether the stream is at a byte boundary}
     */
    public boolean aligned() {
        return count == 0;
    }

    /**
     * {@return the number of bits written}
     */
    public long position() {
        return (long) bytes * 8 + count;
    }

    /**
     * Appends the bits of another writer.
     *
     * @param other the writer whose bits are appended; left unchanged
     */
    public void append(BitWriter other) {
        if (count == 0) {
            ensure(other.bytes);
            System.arraycopy(other.buffer, 0, buffer, bytes, other.bytes);
            bytes += other.bytes;
        } else {
            for (var i = 0; i < other.bytes; i++) {
                write(other.buffer[i], 8);
            }
        }
        write((int) other.pending, other.count);
    }

    /**
     * Copies the bytes written, padding a partial last byte with zero bits.
     *
     * @return the bytes
     */
    public byte[] toByteArray() {
        var result = Arrays.copyOf(buffer, bytes + (count > 0 ? 1 : 0));
        if (count > 0) {
            result[bytes] = (byte) (pending << (8 - count));
        }
        return result;
    }

    /**
     * Copies the bytes written into an array.
     *
     * @param target the destination, at least {@code (position() + 7) / 8} bytes after {@code offset}
     * @param offset the index of the first byte in {@code target}
     * @return the number of bytes copied
     */
    public int copyTo(byte[] target, int offset) {
        System.arraycopy(buffer, 0, target, offset, bytes);
        if (count > 0) {
            target[offset + bytes] = (byte) (pending << (8 - count));
            return bytes + 1;
        }
        return bytes;
    }

    /**
     * Discards every bit written.
     */
    public void reset() {
        bytes = 0;
        pending = 0;
        count = 0;
    }

    /**
     * Appends one complete byte.
     *
     * @param value the byte
     */
    private void put(byte value) {
        if (bytes == buffer.length) {
            buffer = Arrays.copyOf(buffer, buffer.length * 2);
        }
        buffer[bytes++] = value;
    }

    /**
     * Grows the buffer to hold more complete bytes.
     *
     * @param more the number of bytes to make room for
     */
    private void ensure(int more) {
        if (bytes + more > buffer.length) {
            buffer = Arrays.copyOf(buffer, Math.max(buffer.length * 2, bytes + more));
        }
    }
}
