package com.github.auties00.smalt.video.ffv1.internal;

/**
 * Reads the most-significant-bit-first bits of Golomb Rice coded FFV1 data; bits past the end read as zero.
 *
 * <p>Instances are not thread-safe.
 */
final class Ffv1BitReader {
    private byte[] data;

    private int end;

    /**
     * The index of the next byte to load into {@link #cache}.
     */
    private int next;

    /**
     * Up to 64 bits not read yet, left aligned.
     */
    private long cache;

    private int available;

    void reset(byte[] data, int offset, int end) {
        this.data = data;
        this.end = end;
        this.next = offset;
        this.cache = 0;
        this.available = 0;
    }

    private void fill() {
        while (available <= 56) {
            var b = next < end ? data[next] & 0xFF : 0;
            next++;
            cache |= (long) b << 56 - available;
            available += 8;
        }
    }

    /**
     * Reads bits.
     *
     * @param count the number of bits, from 0 to 32
     * @return the bits, the first read in the most significant place
     */
    int bits(int count) {
        if (count == 0) {
            return 0;
        }
        if (available < count) {
            fill();
        }
        var value = (int) (cache >>> 64 - count);
        cache <<= count;
        available -= count;
        return value;
    }

    /**
     * Reads one bit.
     *
     * @return 0 or 1
     */
    int bit() {
        if (available == 0) {
            fill();
        }
        var value = (int) (cache >>> 63);
        cache <<= 1;
        available--;
        return value;
    }

    /**
     * Reads an unsigned Golomb Rice code (RFC 9043, figure 26).
     *
     * @param k the number of suffix bits
     * @param escapeBits the width of the escaped value
     * @return the value
     */
    int unsignedGolomb(int k, int escapeBits) {
        if (available < 32) {
            fill();
        }
        var zeros = Long.numberOfLeadingZeros(cache);
        if (zeros < 12) {
            cache <<= zeros + 1;
            available -= zeros + 1;
            return bits(k) + (zeros << k);
        }
        cache <<= 12;
        available -= 12;
        return bits(escapeBits) + 11;
    }
}
