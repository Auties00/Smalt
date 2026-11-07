package com.github.auties00.smalt.video.dv.internal;

/**
 * Holds a string of bits gathered from parts of a frame, most significant bit first, for the passes of a video
 * segment that continue a block in the unused space of others.
 *
 * <p>Instances are not thread-safe.
 */
final class DvBitBuffer {
    /**
     * The bits, packed from the most significant bit of byte 0; three spare bytes let a 16-bit window be read at
     * any position.
     */
    final byte[] data;

    /**
     * The number of bits held.
     */
    int length;

    /**
     * Constructs an empty buffer.
     *
     * @param capacity the largest number of bits the buffer holds
     */
    DvBitBuffer(int capacity) {
        this.data = new byte[(capacity + 7) / 8 + 3];
    }

    /**
     * Empties the buffer.
     */
    void clear() {
        java.util.Arrays.fill(data, 0, Math.min(data.length, (length + 7) / 8 + 1), (byte) 0);
        length = 0;
    }

    /**
     * Appends a run of bits of an array.
     *
     * @param source the array
     * @param from the position of the first bit, counted from the most significant bit of byte 0
     * @param to the position one past the last bit
     */
    void append(byte[] source, int from, int to) {
        var position = from;
        while (position < to && (position & 7) != 0) {
            appendBit((source[position >>> 3] >>> (7 - (position & 7))) & 1);
            position++;
        }
        if ((length & 7) == 0) {
            while (position + 8 <= to) {
                data[length >>> 3] = source[position >>> 3];
                length += 8;
                position += 8;
            }
        } else {
            var shift = length & 7;
            while (position + 8 <= to) {
                var value = source[position >>> 3] & 0xFF;
                data[length >>> 3] |= (byte) (value >>> shift);
                data[(length >>> 3) + 1] = (byte) (value << (8 - shift));
                length += 8;
                position += 8;
            }
        }
        while (position < to) {
            appendBit((source[position >>> 3] >>> (7 - (position & 7))) & 1);
            position++;
        }
    }

    /**
     * Appends the low bits of a value, the most significant first.
     *
     * @param value the bits, in its low bits
     * @param count the number of bits, from 0 to 31
     */
    void appendBits(int value, int count) {
        for (var shift = count - 1; shift >= 0; shift--) {
            appendBit((value >>> shift) & 1);
        }
    }

    /**
     * Appends one bit.
     *
     * @param bit the bit, 0 or 1
     */
    private void appendBit(int bit) {
        var index = length >>> 3;
        if ((length & 7) == 0) {
            data[index] = 0;
        }
        data[index] |= (byte) (bit << (7 - (length & 7)));
        length++;
    }

    /**
     * Returns the 16 bits that start at a position of an array, the bits past its end read as zero.
     *
     * @param data the array
     * @param position the bit position
     * @return the bits, the first in bit 15
     */
    static int peek16(byte[] data, int position) {
        var index = position >>> 3;
        var b0 = index < data.length ? data[index] & 0xFF : 0;
        var b1 = index + 1 < data.length ? data[index + 1] & 0xFF : 0;
        var b2 = index + 2 < data.length ? data[index + 2] & 0xFF : 0;
        var word = b0 << 16 | b1 << 8 | b2;
        return (word >>> (8 - (position & 7))) & 0xFFFF;
    }
}
