package com.github.auties00.smalt.audio.dts.internal;

import java.io.ByteArrayOutputStream;

/**
 * Writes a bit stream most significant bit first, for building synthetic DTS frames.
 */
public final class DtsBitWriter {
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private int current;
    private int filled;
    private long position;

    public void write(long value, int bits) {
        for (var i = bits - 1; i >= 0; i--) {
            current = current << 1 | (int) (value >>> i & 1);
            filled++;
            position++;
            if (filled == 8) {
                out.write(current);
                current = 0;
                filled = 0;
            }
        }
    }

    public void writeSigned(int value, int bits) {
        write(value & ((1L << bits) - 1), bits);
    }

    public void alignTo(int bits) {
        while (position % bits != 0) {
            write(0, 1);
        }
    }

    public long position() {
        return position;
    }

    public void append(byte[] bytes) {
        if (filled != 0) {
            throw new IllegalStateException("not byte aligned");
        }
        out.writeBytes(bytes);
        position += bytes.length * 8L;
    }

    public byte[] toByteArray() {
        if (filled != 0) {
            alignTo(8);
        }
        return out.toByteArray();
    }
}
