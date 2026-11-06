/* Ports libspeex 1.2.1 (libspeex/bits.c). Copyright 2002-2008 Xiph.org Foundation, Jean-Marc Valin; BSD 3-Clause
   License. Modified for Smalt. */
package com.github.auties00.smalt.audio.speex;

/**
 * Reads the bits of a Speex packet, most significant first; a read past the end sets the overflow state, after which
 * every read returns 0, as the reference reader does.
 *
 * <p>Instances are not thread-safe.
 */
final class SpeexBits {
    private byte[] data;

    private int start;

    private int bits;

    private int position;

    private boolean overflow;

    /**
     * Starts reading {@code length} bytes of {@code data} from {@code offset}.
     */
    void reset(byte[] data, int offset, int length) {
        this.data = data;
        this.start = offset;
        this.bits = length << 3;
        this.position = 0;
        this.overflow = false;
    }

    /**
     * {@return the next {@code count} bits as an unsigned value, or 0 once the packet overflows}
     */
    int unsigned(int count) {
        if (position + count > bits) {
            overflow = true;
        }
        if (overflow) {
            return 0;
        }
        var value = 0;
        for (var i = 0; i < count; i++) {
            var bit = (data[start + (position >> 3)] >> (7 - (position & 7))) & 1;
            value = value << 1 | bit;
            position++;
        }
        return value;
    }

    /**
     * {@return the next bit without consuming it, or 0 once the packet overflows}
     */
    int peek() {
        if (position + 1 > bits) {
            overflow = true;
        }
        if (overflow) {
            return 0;
        }
        return (data[start + (position >> 3)] >> (7 - (position & 7))) & 1;
    }

    /**
     * Skips {@code count} bits, or sets the overflow state when fewer remain.
     */
    void advance(int count) {
        if (position + count > bits || overflow) {
            overflow = true;
            return;
        }
        position += count;
    }

    /**
     * {@return the number of bits left, or -1 after an overflow}
     */
    int remaining() {
        return overflow ? -1 : bits - position;
    }
}
