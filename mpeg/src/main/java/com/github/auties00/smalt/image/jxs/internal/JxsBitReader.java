package com.github.auties00.smalt.image.jxs.internal;

/**
 * Reads the bits of a JPEG XS codestream most significant bit first.
 *
 * <p>Bits past the end of the data read as zero; callers compare {@link #bitPosition()} with the end of the unit they
 * parse. Instances are not thread-safe.
 */
final class JxsBitReader {
    private final byte[] data;
    private final int end;
    private int next;
    private long cache;
    private int count;

    /**
     * Constructs a reader over {@code data[0, end)}.
     */
    JxsBitReader(byte[] data, int end) {
        this.data = data;
        this.end = end;
    }

    /**
     * Moves to the start of byte {@code position}.
     */
    void seek(int position) {
        next = position;
        cache = 0;
        count = 0;
    }

    /**
     * {@return the position of the next bit, in bits from the start of the data}
     */
    long bitPosition() {
        return (long) next * 8 - count;
    }

    /**
     * {@return the position of the next byte, the current one when the reader is inside a byte}
     */
    int bytePosition() {
        return (int) ((bitPosition() + 7) >>> 3);
    }

    /**
     * Skips to the next byte boundary.
     */
    void align() {
        var skip = (int) (-bitPosition() & 7);
        if (skip > 0) {
            skip(skip);
        }
    }

    /**
     * Skips {@code bits} bits.
     */
    void skip(long bits) {
        if (bits <= count) {
            cache <<= bits;
            count -= (int) bits;
            return;
        }
        var position = bitPosition() + bits;
        seek((int) (position >>> 3));
        var rest = (int) (position & 7);
        if (rest > 0) {
            read(rest);
        }
    }

    private void refill() {
        while (count <= 56) {
            var value = next < end ? data[next] & 0xFF : 0;
            next++;
            cache |= (long) value << (56 - count);
            count += 8;
        }
    }

    /**
     * Reads {@code bits} bits, from 1 to 32, as an unsigned value.
     */
    int read(int bits) {
        if (count < bits) {
            refill();
        }
        var value = (int) (cache >>> (64 - bits));
        cache <<= bits;
        count -= bits;
        return value;
    }

    /**
     * Reads one bit.
     */
    int bit() {
        if (count == 0) {
            refill();
        }
        var value = (int) (cache >>> 63);
        cache <<= 1;
        count--;
        return value;
    }

    /**
     * Reads a unary code: the number of one bits before the next zero bit, or -1 when more than 31 one bits follow.
     */
    int unary() {
        if (count < 32) {
            refill();
        }
        var ones = Long.numberOfLeadingZeros(~cache);
        if (ones > 31) {
            return -1;
        }
        cache <<= ones + 1;
        count -= ones + 1;
        return ones;
    }
}
