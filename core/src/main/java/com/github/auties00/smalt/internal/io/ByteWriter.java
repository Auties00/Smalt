package com.github.auties00.smalt.internal.io;

import com.github.auties00.smalt.io.MediaSink;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Assembles the header bytes of a container in a growable array, in either byte order.
 *
 * <p>Muxers build each header or chunk header here and write it to their {@link MediaSink} in one
 * call; the {@code patch} methods rewrite a value already written, for sizes computed after the
 * bytes that follow them. {@link #begin(int)} and {@link #end()} write ISO base media file format
 * boxes, whose 32-bit big-endian size is patched once the payload is written, so nested boxes are
 * produced in a single pass. Instances are not thread-safe.
 */
public final class ByteWriter {
    /**
     * The deepest box nesting {@link #begin(int)} supports.
     */
    private static final int MAX_DEPTH = 16;

    /**
     * The bytes written, valid up to {@link #size}.
     */
    private byte[] buffer;

    /**
     * The number of bytes written.
     */
    private int size;

    /**
     * The start offsets of the open boxes, valid up to {@link #depth}.
     */
    private final int[] open = new int[MAX_DEPTH];

    /**
     * The number of open boxes.
     */
    private int depth;

    /**
     * Constructs an empty writer.
     *
     * @param capacity the initial capacity in bytes, non-negative; at least 16 bytes are reserved
     */
    public ByteWriter(int capacity) {
        this.buffer = new byte[Math.max(16, capacity)];
    }

    /**
     * {@return the number of bytes written}
     */
    public int size() {
        return size;
    }

    /**
     * {@return a copy of the bytes written}
     */
    public byte[] toByteArray() {
        return Arrays.copyOf(buffer, size);
    }

    /**
     * {@return the backing array, not a copy} Only the first {@link #size()} bytes are valid, and
     * the array is replaced when a later write grows it.
     */
    public byte[] buffer() {
        return buffer;
    }

    /**
     * Discards every byte written and every open box, keeping the capacity.
     */
    public void reset() {
        size = 0;
        depth = 0;
    }

    /**
     * Writes the bytes written so far to a sink at its position.
     *
     * @param sink the destination, written at its position
     */
    public void writeTo(MediaSink sink) {
        sink.write(ByteBuffer.wrap(buffer, 0, size));
    }

    /**
     * Grows the buffer to hold {@code extra} more bytes.
     *
     * @param extra the number of bytes about to be written
     */
    private void ensure(int extra) {
        var needed = (long) size + extra;
        if (needed > buffer.length) {
            if (needed > Integer.MAX_VALUE - 8) {
                throw new IllegalStateException("header larger than " + (Integer.MAX_VALUE - 8) + " bytes");
            }
            buffer = Arrays.copyOf(buffer, (int) Math.max(needed, Math.min(Integer.MAX_VALUE - 8, 2L * buffer.length)));
        }
    }

    /**
     * Writes one byte.
     *
     * @param value the byte in the low 8 bits
     */
    public void u8(int value) {
        ensure(1);
        buffer[size++] = (byte) value;
    }

    /**
     * Writes a 16-bit value in little-endian order.
     *
     * @param value the value in the low 16 bits
     */
    public void u16le(int value) {
        ensure(2);
        buffer[size++] = (byte) value;
        buffer[size++] = (byte) (value >>> 8);
    }

    /**
     * Writes a 16-bit value in big-endian order.
     *
     * @param value the value in the low 16 bits
     */
    public void u16be(int value) {
        ensure(2);
        buffer[size++] = (byte) (value >>> 8);
        buffer[size++] = (byte) value;
    }

    /**
     * Writes a 24-bit value in big-endian order.
     *
     * @param value the value in the low 24 bits
     */
    public void u24be(int value) {
        ensure(3);
        buffer[size++] = (byte) (value >>> 16);
        buffer[size++] = (byte) (value >>> 8);
        buffer[size++] = (byte) value;
    }

    /**
     * Writes a 32-bit value in little-endian order.
     *
     * @param value the value in the low 32 bits
     */
    public void u32le(long value) {
        ensure(4);
        for (var shift = 0; shift < 32; shift += 8) {
            buffer[size++] = (byte) (value >>> shift);
        }
    }

    /**
     * Writes a 32-bit value in big-endian order.
     *
     * @param value the value in the low 32 bits
     */
    public void u32be(long value) {
        ensure(4);
        for (var shift = 24; shift >= 0; shift -= 8) {
            buffer[size++] = (byte) (value >>> shift);
        }
    }

    /**
     * Writes a 64-bit value in little-endian order.
     *
     * @param value the value to write
     */
    public void u64le(long value) {
        ensure(8);
        for (var shift = 0; shift < 64; shift += 8) {
            buffer[size++] = (byte) (value >>> shift);
        }
    }

    /**
     * Writes a 64-bit value in big-endian order.
     *
     * @param value the value to write
     */
    public void u64be(long value) {
        ensure(8);
        for (var shift = 56; shift >= 0; shift -= 8) {
            buffer[size++] = (byte) (value >>> shift);
        }
    }

    /**
     * Writes a four-character code.
     *
     * @param code the code, four ASCII characters
     * @throws IllegalArgumentException if {@code code} is not four characters long
     */
    public void fourCc(String code) {
        if (code.length() != 4) {
            throw new IllegalArgumentException("not a four-character code: " + code);
        }
        bytes(code.getBytes(StandardCharsets.ISO_8859_1));
    }

    /**
     * Writes a byte array.
     *
     * @param data the bytes
     */
    public void bytes(byte[] data) {
        bytes(data, 0, data.length);
    }

    /**
     * Writes a range of a byte array.
     *
     * @param data the bytes
     * @param offset the index of the first byte
     * @param length the number of bytes
     */
    public void bytes(byte[] data, int offset, int length) {
        ensure(length);
        System.arraycopy(data, offset, buffer, size, length);
        size += length;
    }

    /**
     * Writes zero bytes.
     *
     * @param count the number of bytes
     */
    public void zeros(int count) {
        ensure(count);
        Arrays.fill(buffer, size, size + count, (byte) 0);
        size += count;
    }

    /**
     * Writes a UTF-8 string followed by a NUL terminator.
     *
     * @param value the string
     */
    public void cString(String value) {
        bytes(value.getBytes(StandardCharsets.UTF_8));
        u8(0);
    }

    /**
     * Opens a box: writes a placeholder 32-bit size and the type, both big-endian.
     *
     * @param type the four-character type in its big-endian integer form
     * @throws IllegalStateException if 16 boxes are already open
     */
    public void begin(int type) {
        if (depth == MAX_DEPTH) {
            throw new IllegalStateException("box nesting deeper than " + MAX_DEPTH);
        }
        open[depth++] = size;
        u32be(0);
        u32be(type);
    }

    /**
     * Opens a full box: writes a placeholder size, the type, the version and the flags.
     *
     * @param type the four-character type in its big-endian integer form
     * @param version the box version, from 0 to 255
     * @param flags the box flags; bits above 24 are ignored
     * @throws IllegalStateException if 16 boxes are already open
     */
    public void beginFull(int type, int version, int flags) {
        begin(type);
        u32be(((long) version << 24) | (flags & 0xFFFFFF));
    }

    /**
     * Closes the innermost open box by patching its size to the bytes written since its
     * {@link #begin(int)}.
     *
     * @throws IllegalStateException if no box is open
     */
    public void end() {
        if (depth == 0) {
            throw new IllegalStateException("no open box");
        }
        var start = open[--depth];
        patch32be(start, size - start);
    }

    /**
     * Rewrites a 32-bit little-endian value written earlier.
     *
     * @param offset the index of the value
     * @param value the value in the low 32 bits
     */
    public void patch32le(int offset, long value) {
        for (var shift = 0; shift < 32; shift += 8) {
            buffer[offset++] = (byte) (value >>> shift);
        }
    }

    /**
     * Rewrites a 32-bit big-endian value written earlier.
     *
     * @param offset the index of the value
     * @param value the value in the low 32 bits
     */
    public void patch32be(int offset, long value) {
        for (var shift = 24; shift >= 0; shift -= 8) {
            buffer[offset++] = (byte) (value >>> shift);
        }
    }
}
