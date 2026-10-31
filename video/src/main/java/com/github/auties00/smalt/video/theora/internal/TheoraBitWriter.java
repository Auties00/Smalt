package com.github.auties00.smalt.video.theora.internal;

import java.util.Arrays;

/**
 * Writes the bits of a Theora packet, most significant first, into a growing buffer.
 *
 * <p>Instances are not thread-safe.
 */
final class TheoraBitWriter {
    private byte[] data = new byte[4096];

    private long position;

    /**
     * Discards the bits written.
     */
    void reset() {
        Arrays.fill(data, 0, (int) ((position + 7) >>> 3), (byte) 0);
        position = 0;
    }

    /**
     * Writes the low {@code count} bits of {@code value}, from 0 to 32, most significant first.
     *
     * @param value the bits
     * @param count the number of bits
     */
    void write(long value, int count) {
        for (var i = count - 1; i >= 0; i--) {
            bit((int) (value >>> i) & 1);
        }
    }

    /**
     * Writes one bit.
     *
     * @param bit the bit, 0 or 1
     */
    void bit(int bit) {
        var index = (int) (position >>> 3);
        if (index == data.length) {
            data = Arrays.copyOf(data, data.length * 2);
        }
        if (bit != 0) {
            data[index] |= (byte) (0x80 >>> (int) (position & 7));
        }
        position++;
    }

    /**
     * {@return the bytes written, the last one padded with zero bits}
     */
    byte[] toByteArray() {
        return Arrays.copyOf(data, (int) ((position + 7) >>> 3));
    }
}
