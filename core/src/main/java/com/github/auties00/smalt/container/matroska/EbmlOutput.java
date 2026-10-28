package com.github.auties00.smalt.container.matroska;

import com.github.auties00.smalt.internal.tags.Ebml;
import com.github.auties00.smalt.internal.tags.EbmlIds;
import com.github.auties00.smalt.io.MediaSink;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Serializes EBML elements (RFC 8794) into a growable array.
 *
 * <p>Master elements are opened with {@link #begin(int)} and closed with {@link #end()}, which
 * writes the content size with the shortest variable-size integer that holds it, moving the
 * content when the reserved size field was longer. Integers are written in the fewest bytes that
 * hold them, floats as 8-byte IEEE 754 doubles, and strings as UTF-8 without a terminator. Instances
 * are not thread-safe.
 */
final class EbmlOutput {
    /**
     * The size field length reserved by {@link #begin(int)}.
     */
    private static final int RESERVED_SIZE = 8;

    /**
     * The largest nesting depth of master elements.
     */
    private static final int MAX_DEPTH = 16;

    /**
     * The encoded "unknown" size of length 8.
     */
    static final long UNKNOWN_SIZE = 0x01FFFFFFFFFFFFFFL;

    /**
     * The bytes written, valid up to {@link #size}.
     */
    private byte[] buffer;

    /**
     * The number of bytes written.
     */
    private int size;

    /**
     * The offsets of the reserved size fields of the open master elements.
     */
    private final int[] open = new int[MAX_DEPTH];

    /**
     * The number of open master elements.
     */
    private int depth;

    /**
     * The positions recorded by {@link #mark()}, kept pointing at the same bytes when
     * {@link #end()} moves content.
     */
    private int[] marks = new int[4];

    /**
     * The number of positions in {@link #marks}.
     */
    private int markCount;

    /**
     * Constructs an empty output.
     *
     * @param capacity the initial capacity in bytes
     */
    EbmlOutput(int capacity) {
        this.buffer = new byte[Math.max(64, capacity)];
    }

    /**
     * {@return the number of bytes written}
     */
    int size() {
        return size;
    }

    /**
     * {@return the array holding the bytes, not copied; only the first {@link #size()} are valid}
     */
    byte[] buffer() {
        return buffer;
    }

    /**
     * Discards every byte written, keeping the capacity.
     */
    void reset() {
        size = 0;
        depth = 0;
        markCount = 0;
    }

    /**
     * Records the current end of the output as a position that later {@link #end()} calls keep
     * pointing at the same byte.
     *
     * @return the handle {@link #markPosition(int)} takes
     */
    int mark() {
        if (markCount == marks.length) {
            marks = Arrays.copyOf(marks, marks.length * 2);
        }
        marks[markCount] = size;
        return markCount++;
    }

    /**
     * Returns the current index of a position recorded by {@link #mark()}.
     *
     * @param handle the handle {@code mark()} returned
     * @return the index of the marked byte in {@link #buffer()}
     */
    int markPosition(int handle) {
        return marks[handle];
    }

    /**
     * Writes the bytes written so far to a sink at its position.
     *
     * @param sink the destination, written from its position
     */
    void writeTo(MediaSink sink) {
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
                throw new IllegalStateException("EBML element larger than " + (Integer.MAX_VALUE - 8) + " bytes");
            }
            buffer = Arrays.copyOf(buffer, (int) Math.max(needed, Math.min(Integer.MAX_VALUE - 8, 2L * buffer.length)));
        }
    }

    /**
     * Writes an element identifier.
     *
     * @param id the identifier
     */
    void id(int id) {
        var length = Ebml.idLength(id);
        ensure(length);
        for (var shift = (length - 1) * 8; shift >= 0; shift -= 8) {
            buffer[size++] = (byte) (id >>> shift);
        }
    }

    /**
     * Writes a size as the shortest variable-size integer that holds it.
     *
     * @param value the size, non-negative and below {@code 2^56 - 1}
     */
    void size(long value) {
        writeSize(value, Ebml.sizeLength(value));
    }

    /**
     * Writes a size as a variable-size integer of a given length.
     *
     * @param value the size
     * @param length the length in bytes, from 1 to 8
     */
    void writeSize(long value, int length) {
        ensure(length);
        var marked = value | (1L << (7 * length));
        for (var shift = (length - 1) * 8; shift >= 0; shift -= 8) {
            buffer[size++] = (byte) (marked >>> shift);
        }
    }

    /**
     * Writes raw bytes.
     *
     * @param data the bytes
     * @param offset the index of the first byte
     * @param length the number of bytes
     */
    void raw(byte[] data, int offset, int length) {
        ensure(length);
        System.arraycopy(data, offset, buffer, size, length);
        size += length;
    }

    /**
     * Writes one raw byte.
     *
     * @param value the byte in the low 8 bits
     */
    void rawByte(int value) {
        ensure(1);
        buffer[size++] = (byte) value;
    }

    /**
     * Writes an unsigned integer element.
     *
     * @param id the identifier
     * @param value the value, treated as unsigned
     */
    void unsigned(int id, long value) {
        var length = 1;
        while (length < 8 && Long.compareUnsigned(value, 1L << (8 * length)) >= 0) {
            length++;
        }
        id(id);
        size(length);
        ensure(length);
        for (var shift = (length - 1) * 8; shift >= 0; shift -= 8) {
            buffer[size++] = (byte) (value >>> shift);
        }
    }

    /**
     * Writes a signed integer element.
     *
     * @param id the identifier
     * @param value the integer, written in the fewest bytes that hold it
     */
    void signed(int id, long value) {
        var length = 1;
        while (length < 8 && (value < -(1L << (8 * length - 1)) || value >= 1L << (8 * length - 1))) {
            length++;
        }
        id(id);
        size(length);
        ensure(length);
        for (var shift = (length - 1) * 8; shift >= 0; shift -= 8) {
            buffer[size++] = (byte) (value >>> shift);
        }
    }

    /**
     * Writes an 8-byte float element.
     *
     * @param id the identifier
     * @param value the number, written as an IEEE 754 double
     * @return the offset of the 8 value bytes, for a later patch
     */
    int floating(int id, double value) {
        id(id);
        size(8);
        var offset = size;
        ensure(8);
        var bits = Double.doubleToLongBits(value);
        for (var shift = 56; shift >= 0; shift -= 8) {
            buffer[size++] = (byte) (bits >>> shift);
        }
        return offset;
    }

    /**
     * Writes a UTF-8 string element.
     *
     * @param id the identifier
     * @param value the string
     */
    void string(int id, String value) {
        binary(id, value.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Writes a binary element.
     *
     * @param id the identifier
     * @param value the payload
     */
    void binary(int id, byte[] value) {
        id(id);
        size(value.length);
        raw(value, 0, value.length);
    }

    /**
     * Writes a {@code Void} element occupying exactly {@code total} bytes, header included.
     *
     * @param total the element length, at least 2
     * @throws IllegalArgumentException if {@code total} is below 2
     */
    void voidElement(int total) {
        if (total < 2) {
            throw new IllegalArgumentException("a Void element needs at least 2 bytes, not " + total);
        }
        id(EbmlIds.VOID);
        var lengthBytes = total - 1 <= 127 ? 1 : 8;
        var payload = total - 1 - lengthBytes;
        if (lengthBytes == 1 && payload >= 127) {
            lengthBytes = 8;
            payload = total - 9;
        }
        writeSize(payload, lengthBytes);
        ensure(payload);
        Arrays.fill(buffer, size, size + payload, (byte) 0);
        size += payload;
    }

    /**
     * Opens a master element whose size is written by {@link #end()}.
     *
     * @param id the identifier
     */
    void begin(int id) {
        if (depth == MAX_DEPTH) {
            throw new IllegalStateException("EBML nesting deeper than " + MAX_DEPTH);
        }
        id(id);
        open[depth++] = size;
        ensure(RESERVED_SIZE);
        size += RESERVED_SIZE;
    }

    /**
     * Closes the innermost master element, writing its size in the fewest bytes and moving its
     * content back over the unused part of the reserved size field.
     */
    void end() {
        if (depth == 0) {
            throw new IllegalStateException("no open EBML master element");
        }
        var field = open[--depth];
        var contentStart = field + RESERVED_SIZE;
        var content = size - contentStart;
        var length = Ebml.sizeLength(content);
        var marked = content | (1L << (7 * length));
        for (var i = 0; i < length; i++) {
            buffer[field + i] = (byte) (marked >>> ((length - 1 - i) * 8));
        }
        if (length < RESERVED_SIZE) {
            System.arraycopy(buffer, contentStart, buffer, field + length, content);
            size -= RESERVED_SIZE - length;
            for (var index = 0; index < markCount; index++) {
                if (marks[index] >= contentStart) {
                    marks[index] -= RESERVED_SIZE - length;
                }
            }
        }
    }

    /**
     * Returns the total length of an element with a payload of a given size.
     *
     * @param id the identifier
     * @param payload the payload size
     * @return the element length, header included
     */
    static long elementLength(int id, long payload) {
        return Ebml.idLength(id) + Ebml.sizeLength(payload) + payload;
    }
}
