/* Ports libtheora 1.2.0 (lib/bitpack.c). Copyright (C) 2002-2009 Xiph.org Foundation; BSD 3-Clause License.
   Modified for Smalt. */
package com.github.auties00.smalt.video.theora.internal;

/**
 * Reads the bits of a Theora packet, most significant first; reads past the end return zero bits and mark the
 * reader exhausted, as the reference reader does.
 *
 * <p>Instances are not thread-safe.
 */
public final class TheoraBits {
    private byte[] data;

    private int end;

    private long position;

    private long limit;

    /**
     * Starts reading {@code length} bytes of {@code data} from {@code offset}.
     *
     * @param data the packet bytes
     * @param offset the first byte
     * @param length the number of bytes
     */
    public void reset(byte[] data, int offset, int length) {
        this.data = data;
        this.position = (long) offset << 3;
        this.end = offset + length;
        this.limit = (long) end << 3;
    }

    /**
     * {@return the next {@code count} bits, from 0 to 32, as an unsigned value}
     */
    public long read(int count) {
        long value = 0;
        for (var i = 0; i < count; i++) {
            value = value << 1 | bit();
        }
        return value;
    }

    /**
     * {@return the next bit, 0 past the end}
     */
    public int bit() {
        if (position >= limit) {
            position++;
            return 0;
        }
        var bit = (data[(int) (position >>> 3)] >> (7 - (int) (position & 7))) & 1;
        position++;
        return bit;
    }

    /**
     * {@return whether a read ran past the end of the packet}
     */
    public boolean exhausted() {
        return position > limit;
    }

    /**
     * {@return the number of whole bytes not yet read, negative after reading past the end}
     */
    public long bytesLeft() {
        return (limit - position) >> 3;
    }
}
