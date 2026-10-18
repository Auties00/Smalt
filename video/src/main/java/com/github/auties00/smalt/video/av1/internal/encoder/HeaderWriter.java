package com.github.auties00.smalt.video.av1.internal.encoder;

import java.util.Arrays;

/**
 * Writes the bit-level syntax elements of AV1 headers, most significant bit first (AV1
 * specification section 4.10).
 */
final class HeaderWriter {
    /**
     * The output buffer.
     */
    private byte[] buf = new byte[256];

    /**
     * The number of bits written.
     */
    private int bitPos;

    /**
     * Creates an empty writer.
     */
    HeaderWriter() {
    }

    /**
     * Discards everything written.
     */
    void reset() {
        Arrays.fill(buf, 0, (bitPos + 7) >> 3, (byte) 0);
        bitPos = 0;
    }

    /**
     * Writes one bit.
     *
     * @param bit the bit, any non-zero value meaning one
     */
    void bit(int bit) {
        var index = bitPos >> 3;
        if (index >= buf.length) {
            buf = Arrays.copyOf(buf, buf.length * 2);
        }
        if (bit != 0) {
            buf[index] |= (byte) (0x80 >>> (bitPos & 7));
        }
        bitPos++;
    }

    /**
     * Writes one flag.
     *
     * @param flag the flag, written as one bit
     */
    void flag(boolean flag) {
        bit(flag ? 1 : 0);
    }

    /**
     * Writes an unsigned value ({@code f(n)}).
     *
     * @param value the value, below {@code 2^n}
     * @param n the number of bits, at most 32
     */
    void bits(long value, int n) {
        for (var i = n - 1; i >= 0; i--) {
            bit((int) (value >>> i) & 1);
        }
    }

    /**
     * Writes a signed value as a two's complement field ({@code su(1 + n)}).
     *
     * @param value the value, from {@code -2^n} to {@code 2^n - 1}
     * @param n the number of magnitude bits
     */
    void sbits(int value, int n) {
        bits(value & ((1L << (n + 1)) - 1), n + 1);
    }

    /**
     * Writes a value below {@code n} with the quasi-uniform code ({@code ns(n)}).
     *
     * @param value the value, below {@code n}
     * @param n the number of possible values
     */
    void uniform(int value, int n) {
        var w = 32 - Integer.numberOfLeadingZeros(n);
        var m = (1 << w) - n;
        if (value < m) {
            bits(value, w - 1);
        } else {
            var extra = value - m;
            bits(m + (extra >> 1), w - 1);
            bit(extra & 1);
        }
    }

    /**
     * Pads with zero bits to the next byte boundary.
     */
    void byteAlign() {
        while ((bitPos & 7) != 0) {
            bit(0);
        }
    }

    /**
     * Writes the trailing bits that end a header OBU ({@code trailing_bits()}).
     */
    void trailingBits() {
        bit(1);
        byteAlign();
    }

    /**
     * {@return the number of whole or partial bytes written}
     */
    int size() {
        return (bitPos + 7) >> 3;
    }

    /**
     * {@return the buffer holding the written bits, zero past them}
     */
    byte[] buffer() {
        return buf;
    }
}
