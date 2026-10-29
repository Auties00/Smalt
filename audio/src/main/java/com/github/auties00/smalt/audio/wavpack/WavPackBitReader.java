/* Ports WavPack 5.8.1 (src/wavpack_local.h, src/open_utils.c, src/read_words.c). Copyright (c) 1998 - 2025 David
   Bryant; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.wavpack;

/**
 * Reads a WavPack bitstream: bits least significant first from 16-bit little-endian units, wrapping to the start
 * of the bitstream (and flagging the error) when a read runs past its end, as the reference reader does.
 *
 * <p>Instances are not thread-safe.
 */
final class WavPackBitReader {
    private byte[] data;

    private int start;

    private int end;

    /**
     * The offset of the current 16-bit unit.
     */
    private int position;

    /**
     * The number of bits left in {@link #shift}.
     */
    private int count;

    private int shift;

    private boolean open;

    private boolean error;

    /**
     * Opens the bitstream held in {@code data[start, end)}; the length is even and positive.
     */
    void open(byte[] data, int start, int end) {
        this.data = data;
        this.start = start;
        this.end = end;
        this.position = start - 2;
        this.count = 0;
        this.shift = 0;
        this.open = true;
        this.error = false;
    }

    /**
     * Closes the bitstream ({@code bs_close_read}).
     */
    void close() {
        open = false;
        data = null;
    }

    /**
     * {@return whether the bitstream is open}
     */
    boolean isOpen() {
        return open;
    }

    /**
     * {@return whether a read ran past the end of the bitstream}
     */
    boolean error() {
        return error;
    }

    private int nextUnit() {
        position += 2;
        if (position == end) {
            position = start;
            error = true;
        }
        return (data[position] & 0xFF) | (data[position + 1] & 0xFF) << 8;
    }

    /**
     * Reads one bit ({@code getbit}).
     *
     * @return the bit, 0 or 1
     */
    int bit() {
        if (count != 0) {
            count--;
        } else {
            shift = nextUnit();
            count = 15;
        }
        var bit = shift & 1;
        shift >>>= 1;
        return bit;
    }

    /**
     * Reads {@code bits} bits ({@code getbits}); the bits above {@code bits} of the result are unspecified.
     *
     * @param bits the count, from 1 to 32
     * @return the bits, least significant first
     */
    int bits(int bits) {
        while (bits > count) {
            shift |= nextUnit() << count;
            count += 16;
        }
        var value = shift;
        if (count > 32) {
            count -= bits;
            shift = ((data[position] & 0xFF) | (data[position + 1] & 0xFF) << 8) >>> (16 - count);
        } else {
            count -= bits;
            shift = bits == 32 ? 0 : shift >>> bits;
        }
        return value;
    }

    /**
     * Reads a value from 0 to {@code maxCode}, in the fewest bits that can hold it plus one more bit for the upper
     * codes when the code count is not a power of two ({@code read_code}).
     *
     * @param maxCode the largest value, unsigned below {@code 2^31}
     * @return the value
     */
    int code(int maxCode) {
        if (maxCode < 2) {
            return maxCode != 0 ? bit() : 0;
        }
        var bitCount = WavPackTables.countBits(maxCode);
        var extras = (int) ((1L << bitCount) - maxCode - 1);
        var local = shift & 0xFFFFFFFFL;
        while (count < bitCount) {
            local |= (long) nextUnit() << count;
            count += 16;
        }
        var code = (int) (local & ((1L << (bitCount - 1)) - 1));
        if (Integer.compareUnsigned(code, extras) >= 0) {
            code = (code << 1) - extras + (int) ((local >>> (bitCount - 1)) & 1);
        } else {
            bitCount--;
        }
        count -= bitCount;
        shift = (int) (local >>> bitCount);
        return code;
    }
}
