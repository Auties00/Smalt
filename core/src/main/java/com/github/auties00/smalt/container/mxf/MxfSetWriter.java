package com.github.auties00.smalt.container.mxf;

import com.github.auties00.smalt.internal.io.ByteWriter;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Writes KLV packets and the local sets of header metadata and index tables (SMPTE ST 336 and ST 377-1).
 *
 * <p>Values are big-endian. Every set starts with its key and a four-byte BER length that {@link #end()} fills in.
 */
final class MxfSetWriter {
    /**
     * The bytes written.
     */
    private final ByteWriter out;

    /**
     * The offset of the length of the open set, or {@code -1}.
     */
    private int lengthAt = -1;

    MxfSetWriter(ByteWriter out) {
        this.out = out;
    }

    /**
     * Opens a header metadata set.
     *
     * @param kind the set byte (byte 13 of the key)
     * @param instance the instance UID, 16 bytes
     */
    void begin(int kind, byte[] instance) {
        var key = MxfLabels.SET_PREFIX.clone();
        out.bytes(key);
        out.u8(kind);
        out.u8(0);
        lengthAt = out.size();
        out.u8(0x83);
        out.u24be(0);
        bytes(0x3C0A, instance);
    }

    /**
     * Opens a set with a key of its own, such as an index table segment.
     *
     * @param key the 16-byte key
     */
    void beginKey(byte[] key) {
        out.bytes(key);
        lengthAt = out.size();
        out.u8(0x83);
        out.u24be(0);
    }

    /**
     * Closes the open set, filling in its length.
     *
     * @throws IllegalStateException if the set is longer than a three-byte BER length holds
     */
    void end() {
        var length = out.size() - lengthAt - 4;
        if (length >= 1 << 24) {
            throw new IllegalStateException("MXF set of " + length + " bytes");
        }
        var buffer = out.buffer();
        buffer[lengthAt + 1] = (byte) (length >>> 16);
        buffer[lengthAt + 2] = (byte) (length >>> 8);
        buffer[lengthAt + 3] = (byte) length;
        lengthAt = -1;
    }

    void bytes(int tag, byte[] value) {
        item(tag, value.length);
        out.bytes(value);
    }

    void u8(int tag, int value) {
        item(tag, 1);
        out.u8(value);
    }

    void u16(int tag, int value) {
        item(tag, 2);
        out.u16be(value);
    }

    void u32(int tag, long value) {
        item(tag, 4);
        out.u32be(value);
    }

    void s64(int tag, long value) {
        item(tag, 8);
        out.u64be(value);
    }

    void rational(int tag, long numerator, long denominator) {
        item(tag, 8);
        out.u32be(numerator);
        out.u32be(denominator);
    }

    /**
     * Writes a UTF-16BE string item without a terminator.
     *
     * @param tag the local tag
     * @param value the string
     */
    void string(int tag, String value) {
        bytes(tag, value.getBytes(StandardCharsets.UTF_16BE));
    }

    /**
     * Writes a batch or array of 16-byte elements.
     *
     * @param tag the local tag
     * @param elements the 16-byte elements, in order
     */
    void batch(int tag, List<byte[]> elements) {
        item(tag, 8 + 16 * elements.size());
        out.u32be(elements.size());
        out.u32be(16);
        for (var element : elements) {
            out.bytes(element);
        }
    }

    /**
     * Writes an array of 32-bit integers.
     *
     * @param tag the local tag
     * @param values the integers, in order
     */
    void int32Array(int tag, long... values) {
        item(tag, 8 + 4 * values.length);
        out.u32be(values.length);
        out.u32be(4);
        for (var value : values) {
            out.u32be(value);
        }
    }

    /**
     * Writes an MXF timestamp: year, month, day, hour, minute, second and quarter milliseconds.
     *
     * @param tag the local tag
     * @param timestamp the 8 bytes
     */
    void timestamp(int tag, byte[] timestamp) {
        bytes(tag, timestamp);
    }

    /**
     * Writes the header of a local item.
     *
     * @param tag the local tag
     * @param length the value length
     * @throws IllegalArgumentException if the value is longer than 65535 bytes
     */
    private void item(int tag, int length) {
        if (length > 0xFFFF) {
            throw new IllegalArgumentException("MXF local item of " + length + " bytes");
        }
        out.u16be(tag);
        out.u16be(length);
    }

    /**
     * Writes a KLV fill item that pads to a multiple of the alignment grid, measured from a base offset.
     *
     * @param out the writer
     * @param position the offset of the next byte of the output
     * @param kag the KLV alignment grid
     */
    static void fill(ByteWriter out, long position, int kag) {
        if (kag <= 1) {
            return;
        }
        var gap = (int) Math.floorMod(-position, (long) kag);
        if (gap == 0) {
            return;
        }
        while (gap < 20) {
            gap += kag;
        }
        out.bytes(MxfLabels.FILL);
        out.u8(0x83);
        out.u24be(gap - 20);
        out.zeros(gap - 20);
    }
}
