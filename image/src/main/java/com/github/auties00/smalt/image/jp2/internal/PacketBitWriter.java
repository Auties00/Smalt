package com.github.auties00.smalt.image.jp2.internal;

import java.util.Arrays;

/**
 * Writes the bits of a packet header (ISO/IEC 15444-1 B.10.1): most significant bit first, with a
 * stuffed zero bit after every {@code 0xFF} byte.
 *
 * @implNote This implementation follows OpenJPEG's {@code bio.c} encoder, so headers are
 * byte-identical to OpenJPEG's.
 */
final class PacketBitWriter {
    /**
     * The bytes written so far.
     */
    private byte[] data = new byte[256];

    /**
     * The number of bytes written.
     */
    private int length;

    /**
     * The last two bytes, the one being filled in the low byte.
     */
    private int buffer;

    /**
     * The number of bits still free in the byte being filled.
     */
    private int count;

    /**
     * Constructs a writer.
     */
    PacketBitWriter() {
        reset();
    }

    /**
     * Starts a new header.
     */
    void reset() {
        length = 0;
        buffer = 0;
        count = 8;
    }

    /**
     * Emits the byte being filled.
     */
    private void emit() {
        buffer = (buffer << 8) & 0xFFFF;
        count = buffer == 0xFF00 ? 7 : 8;
        if (length == data.length) {
            data = Arrays.copyOf(data, data.length * 2);
        }
        data[length++] = (byte) (buffer >>> 8);
    }

    /**
     * Writes one bit.
     *
     * @param bit the bit, 0 or 1
     */
    void bit(int bit) {
        if (count == 0) {
            emit();
        }
        count--;
        buffer |= bit << count;
    }

    /**
     * Writes an unsigned value.
     *
     * @param value the value
     * @param bits  the number of bits, from 1 to 32
     */
    void write(int value, int bits) {
        for (var i = bits - 1; i >= 0; i--) {
            bit((value >>> i) & 1);
        }
    }

    /**
     * Ends the header, emitting the partial byte and the stuffing byte a final {@code 0xFF}
     * needs.
     */
    void flush() {
        emit();
        if (count == 7) {
            emit();
        }
    }

    /**
     * Returns the number of bytes written.
     *
     * @return the header length
     */
    int length() {
        return length;
    }

    /**
     * Returns the bytes written.
     *
     * @return the internal buffer, valid up to {@link #length()}
     */
    byte[] data() {
        return data;
    }
}
