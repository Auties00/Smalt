package com.github.auties00.smalt.image.jp2.internal;

/**
 * Reads the bits of a packet header (ISO/IEC 15444-1 B.10.1): most significant bit first, with a
 * stuffed zero bit after every {@code 0xFF} byte.
 *
 * <p>Reading past the end of the data yields zero bits, so every loop driven by the bits ends.
 *
 * @implNote This implementation follows OpenJPEG's {@code bio.c} decoder.
 */
final class PacketBits {
    /**
     * The bytes being read.
     */
    private byte[] data;

    /**
     * The index of the first byte of the header.
     */
    private int start;

    /**
     * The end of the readable bytes.
     */
    private int end;

    /**
     * The index of the next byte to load.
     */
    private int position;

    /**
     * The last two bytes loaded, the newer in the low byte.
     */
    private int buffer;

    /**
     * The number of bits of the newer byte not yet read.
     */
    private int count;

    /**
     * Constructs a reader with nothing to read.
     */
    PacketBits() {
    }

    /**
     * Starts reading a header.
     *
     * @param data  the bytes
     * @param start the index of the first header byte
     * @param end   the end of the readable bytes
     */
    void init(byte[] data, int start, int end) {
        this.data = data;
        this.start = start;
        this.end = end;
        this.position = start;
        this.buffer = 0;
        this.count = 0;
    }

    /**
     * Loads the next byte, or a zero byte past the end.
     */
    private void load() {
        buffer = (buffer << 8) & 0xFFFF;
        count = buffer == 0xFF00 ? 7 : 8;
        if (position < end) {
            buffer |= data[position++] & 0xFF;
        }
    }

    /**
     * Reads one bit.
     *
     * @return the bit, 0 or 1
     */
    int bit() {
        if (count == 0) {
            load();
        }
        count--;
        return (buffer >>> count) & 1;
    }

    /**
     * Reads an unsigned value.
     *
     * @param bits the number of bits, from 1 to 32
     * @return the value; a 32-bit value may be negative
     */
    int read(int bits) {
        var value = 0;
        for (var i = bits - 1; i >= 0; i--) {
            value |= bit() << i;
        }
        return value;
    }

    /**
     * Skips to the end of the header: past the stuffed byte that follows a final {@code 0xFF}.
     */
    void align() {
        if ((buffer & 0xFF) == 0xFF) {
            load();
        }
        count = 0;
    }

    /**
     * Returns the number of bytes consumed since {@link #init}.
     *
     * @return the header length so far
     */
    int consumed() {
        return position - start;
    }
}
