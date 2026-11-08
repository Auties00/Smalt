package com.github.auties00.smalt.internal.cenc;

import java.io.ByteArrayOutputStream;

/**
 * Writes the most-significant-bit-first fields of hand-made VP9 and AV1 headers.
 */
final class HeaderBits {
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private int current;
    private int count;

    HeaderBits bits(long value, int width) {
        for (var bit = width - 1; bit >= 0; bit--) {
            current = current << 1 | (int) (value >>> bit & 1);
            if (++count == 8) {
                out.write(current);
                current = 0;
                count = 0;
            }
        }
        return this;
    }

    HeaderBits flag(boolean value) {
        return bits(value ? 1 : 0, 1);
    }

    /**
     * Pads with zero bits to a byte boundary.
     */
    HeaderBits align() {
        while (count != 0) {
            bits(0, 1);
        }
        return this;
    }

    HeaderBits bytes(byte[] data) {
        align();
        out.writeBytes(data);
        return this;
    }

    /**
     * Ends an OBU payload with its trailing one bit and zero bits to the byte boundary.
     */
    HeaderBits trailing() {
        bits(1, 1);
        return align();
    }

    int bitLength() {
        return out.size() * 8 + count;
    }

    byte[] toByteArray() {
        align();
        return out.toByteArray();
    }
}
