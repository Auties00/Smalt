package com.github.auties00.smalt.container.mp4;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Serializes boxes into a growable big-endian byte buffer.
 *
 * <p>{@link #begin(int)} writes a placeholder header and remembers where the box starts;
 * {@link #end()} patches the size once the payload is written, so nested boxes are produced in a
 * single pass. Boxes built this way are at most {@code 2^31 - 1} bytes, which bounds a
 * {@code moov} of any file this package writes.
 */
final class BoxWriter {
    /**
     * The deepest box nesting the writer supports.
     */
    private static final int MAX_DEPTH = 16;

    /**
     * The written bytes; only the first {@link #size} are valid.
     */
    private byte[] buffer;

    /**
     * The number of valid bytes in {@link #buffer}.
     */
    private int size;

    /**
     * The start offsets of the boxes that are open.
     */
    private final int[] open;

    /**
     * The number of open boxes.
     */
    private int depth;

    /**
     * Constructs an empty writer.
     *
     * @param capacity the initial buffer capacity in bytes
     */
    BoxWriter(int capacity) {
        this.buffer = new byte[Math.max(capacity, 64)];
        this.open = new int[MAX_DEPTH];
    }

    /**
     * Returns the number of bytes written.
     *
     * @return the size
     */
    int size() {
        return size;
    }

    /**
     * {@return a copy of the written bytes}
     */
    byte[] toByteArray() {
        return Arrays.copyOf(buffer, size);
    }

    /**
     * Returns the backing array, valid up to {@link #size()}.
     *
     * @return the internal buffer, not a copy
     */
    byte[] buffer() {
        return buffer;
    }

    /**
     * Grows the buffer to hold {@code extra} more bytes.
     *
     * @param extra the number of bytes about to be written
     */
    private void ensure(int extra) {
        var needed = size + extra;
        if (needed > buffer.length) {
            buffer = Arrays.copyOf(buffer, Math.max(needed, buffer.length * 2));
        }
    }

    /**
     * Opens a box: writes a placeholder size and the type.
     *
     * @param type the four-character type code
     * @throws IllegalStateException if the nesting exceeds the supported depth
     */
    void begin(int type) {
        if (depth == MAX_DEPTH) {
            throw new IllegalStateException("box nesting too deep");
        }
        open[depth++] = size;
        u32(0);
        u32(type);
    }

    /**
     * Opens a full box: writes a placeholder size, the type, the version and the flags.
     *
     * @param type the four-character type code
     * @param version the box version
     * @param flags the 24-bit flags
     */
    void beginFull(int type, int version, int flags) {
        begin(type);
        u32(((long) version << 24) | (flags & 0xFFFFFF));
    }

    /**
     * Closes the innermost open box by patching its size.
     *
     * @throws IllegalStateException if no box is open
     */
    void end() {
        if (depth == 0) {
            throw new IllegalStateException("no open box");
        }
        var start = open[--depth];
        patch32(start, size - start);
    }

    /**
     * Writes an unsigned byte.
     *
     * @param value the value; bits above 8 are ignored
     */
    void u8(int value) {
        ensure(1);
        buffer[size++] = (byte) value;
    }

    /**
     * Writes a 16-bit value.
     *
     * @param value the value; bits above 16 are ignored
     */
    void u16(int value) {
        ensure(2);
        buffer[size] = (byte) (value >>> 8);
        buffer[size + 1] = (byte) value;
        size += 2;
    }

    /**
     * Writes a 24-bit value.
     *
     * @param value the value; bits above 24 are ignored
     */
    void u24(int value) {
        ensure(3);
        buffer[size] = (byte) (value >>> 16);
        buffer[size + 1] = (byte) (value >>> 8);
        buffer[size + 2] = (byte) value;
        size += 3;
    }

    /**
     * Writes a 32-bit value.
     *
     * @param value the value; bits above 32 are ignored
     */
    void u32(long value) {
        ensure(4);
        buffer[size] = (byte) (value >>> 24);
        buffer[size + 1] = (byte) (value >>> 16);
        buffer[size + 2] = (byte) (value >>> 8);
        buffer[size + 3] = (byte) value;
        size += 4;
    }

    /**
     * Writes a 64-bit value.
     *
     * @param value the value, as two's complement
     */
    void u64(long value) {
        u32(value >>> 32);
        u32(value);
    }

    /**
     * Writes a run of zero bytes.
     *
     * @param count the number of zero bytes
     */
    void zeros(int count) {
        ensure(count);
        Arrays.fill(buffer, size, size + count, (byte) 0);
        size += count;
    }

    /**
     * Writes bytes.
     *
     * @param bytes the bytes to append
     */
    void bytes(byte[] bytes) {
        ensure(bytes.length);
        System.arraycopy(bytes, 0, buffer, size, bytes.length);
        size += bytes.length;
    }

    /**
     * Writes a UTF-8 string followed by a NUL terminator.
     *
     * @param value the string
     */
    void cString(String value) {
        bytes(value.getBytes(StandardCharsets.UTF_8));
        u8(0);
    }

    /**
     * Overwrites a 32-bit value at an absolute offset.
     *
     * @param offset the offset of the value
     * @param value the value, in its low 32 bits
     */
    void patch32(int offset, long value) {
        buffer[offset] = (byte) (value >>> 24);
        buffer[offset + 1] = (byte) (value >>> 16);
        buffer[offset + 2] = (byte) (value >>> 8);
        buffer[offset + 3] = (byte) value;
    }
}
