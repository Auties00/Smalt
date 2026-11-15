/* Ports jxrlib 1.1 (image/sys/strcodec.c: the bit reader). Copyright (c) Microsoft Corporation; BSD 2-Clause
   License. Modified for Smalt. */
package com.github.auties00.smalt.image.jxr.internal;

/**
 * Reads a JPEG XR bit-stream, most significant bit first, from a range of a byte array.
 *
 * <p>Reads past the end of the range return zero bits, as a decoder reading a truncated stream sees padding; the
 * caller detects truncation through {@link #overrun()}.
 */
final class JxrBits {
    /**
     * The bytes.
     */
    private final byte[] data;

    /**
     * The index of the first byte of the stream.
     */
    private int start;

    /**
     * The index one past the last byte of the stream.
     */
    private final int end;

    /**
     * The position of the next bit, counted from {@link #start}.
     */
    private long position;

    /**
     * Starts reading a stream.
     *
     * @param data the bytes
     * @param start the index of the first byte
     * @param end the index one past the last byte
     */
    JxrBits(byte[] data, int start, int end) {
        this.data = data;
        this.start = start;
        this.end = Math.min(end, data.length);
    }

    /**
     * Restarts reading at another byte of the array, up to the same end.
     *
     * @param start the index of the first byte
     */
    void attach(int start) {
        this.start = start;
        position = 0;
    }

    /**
     * {@return the next bits without consuming them}
     *
     * @param count the number of bits, from 0 to 25
     */
    int peek(int count) {
        if (count == 0) {
            return 0;
        }
        var at = start + (int) (position >>> 3);
        var shift = (int) (position & 7);
        int word;
        if (at + 4 <= end) {
            word = (data[at] & 0xFF) << 24 | (data[at + 1] & 0xFF) << 16 | (data[at + 2] & 0xFF) << 8
                    | data[at + 3] & 0xFF;
        } else {
            word = 0;
            for (var index = 0; index < 4; index++) {
                word = word << 8 | (at + index < end ? data[at + index] & 0xFF : 0);
            }
        }
        return (word << shift) >>> (32 - count);
    }

    /**
     * Consumes bits.
     *
     * @param count the number of bits
     */
    void skip(int count) {
        position += count;
    }

    /**
     * {@return the next bits, consumed}
     *
     * @param count the number of bits, from 0 to 32
     */
    int read(int count) {
        if (count > 16) {
            var high = peek(16);
            position += 16;
            var low = peek(count - 16);
            position += count - 16;
            return high << (count - 16) | low;
        }
        var value = peek(count);
        position += count;
        return value;
    }

    /**
     * {@return the next bit, consumed}
     */
    int bit() {
        var value = peek(1);
        position++;
        return value;
    }

    /**
     * {@return -1 when the next bit is set and 0 otherwise, the bit consumed}
     */
    int sign() {
        return -bit();
    }

    /**
     * Reads a value of {@code count} magnitude bits followed, when it is not zero, by a sign bit.
     *
     * @param count the number of magnitude bits
     * @return the value
     */
    int signedMagnitude(int count) {
        var raw = peek(count + 1);
        var value = ((raw >> 1) ^ -(raw & 1)) + (raw & 1);
        position += count + (value != 0 ? 1 : 0);
        return value;
    }

    /**
     * Skips to the next byte boundary.
     */
    void alignToByte() {
        position = (position + 7) & ~7L;
    }

    /**
     * {@return the index in the array of the byte holding the next bit}
     */
    long bytePosition() {
        return start + (position >>> 3);
    }

    /**
     * {@return whether more bits were read than the stream holds}
     */
    boolean overrun() {
        return start + ((position + 7) >>> 3) > end;
    }
}
