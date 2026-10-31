package com.github.auties00.smalt.video.ffv1.internal;

import java.util.Arrays;

/**
 * Writes the most-significant-bit-first bits of Golomb Rice coded FFV1 data.
 *
 * <p>Instances are not thread-safe; the buffer is reused across {@link #reset()} calls.
 */
final class Ffv1BitWriter {
    private byte[] out = new byte[1024];

    private int size;

    private long cache;

    private int used;

    void reset() {
        size = 0;
        cache = 0;
        used = 0;
    }

    /**
     * Writes bits.
     *
     * @param value the bits in the low places
     * @param count the number of bits, from 0 to 32
     */
    void bits(int value, int count) {
        if (count == 0) {
            return;
        }
        cache = cache << count | value & (count == 32 ? 0xFFFFFFFFL : (1L << count) - 1);
        used += count;
        while (used >= 8) {
            used -= 8;
            put((int) (cache >>> used));
        }
    }

    /**
     * Writes an unsigned Golomb Rice code, the inverse of {@link Ffv1BitReader#unsignedGolomb}.
     *
     * @param value the value, below {@code 2^escapeBits + 11}
     * @param k the number of suffix bits
     * @param escapeBits the width of the escaped value
     */
    void unsignedGolomb(int value, int k, int escapeBits) {
        var prefix = k >= 31 ? 0 : value >>> k;
        if (prefix < 12) {
            bits(1, prefix + 1);
            bits(value, k);
        } else {
            bits(0, 12);
            bits(value - 11, escapeBits);
        }
    }

    /**
     * Pads the last byte with zero bits.
     */
    void align() {
        if (used > 0) {
            bits(0, 8 - used);
        }
    }

    private void put(int value) {
        if (size == out.length) {
            out = Arrays.copyOf(out, out.length * 2);
        }
        out[size++] = (byte) value;
    }

    int size() {
        return size;
    }

    byte[] buffer() {
        return out;
    }
}
