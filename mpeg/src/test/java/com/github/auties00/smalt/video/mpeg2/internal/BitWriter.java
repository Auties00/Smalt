package com.github.auties00.smalt.video.mpeg2.internal;

import java.io.ByteArrayOutputStream;

/**
 * Writes bits most significant first, for the syntax streams of the MPEG video tests.
 */
public final class BitWriter {
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private int current;
    private int filled;

    /** Writes the low {@code count} bits of a value, from 0 to 32 bits. */
    public BitWriter put(long value, int count) {
        for (var bit = count - 1; bit >= 0; bit--) {
            current = current << 1 | (int) (value >>> bit & 1);
            if (++filled == 8) {
                out.write(current);
                current = 0;
                filled = 0;
            }
        }
        return this;
    }

    /** Writes a flag. */
    public BitWriter flag(boolean value) {
        return put(value ? 1 : 0, 1);
    }

    /** Pads with zero bits to the next byte boundary. */
    public BitWriter align() {
        while (filled != 0) {
            put(0, 1);
        }
        return this;
    }

    /** Pads to a byte boundary and writes a start code. */
    public BitWriter startCode(int code) {
        align();
        out.write(0);
        out.write(0);
        out.write(1);
        out.write(code);
        return this;
    }

    /** Writes a byte-aligned run of bytes. */
    public BitWriter bytes(byte[] data) {
        align();
        out.writeBytes(data);
        return this;
    }

    /** Returns the bytes written, padded to a byte boundary. */
    public byte[] toByteArray() {
        align();
        return out.toByteArray();
    }
}
