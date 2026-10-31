/* Ports LibAtrac9 (C/src/bit_reader.c, C/src/utility.c) at commit efca2e3. Copyright 2018 Alex Barney; MIT License.
   Modified for Smalt. */
package com.github.auties00.smalt.audio.atrac9;

/**
 * Reads the most significant bit first from a range of a byte array; bits past the end of the range read as zero.
 *
 * <p>Arguments are not checked; values outside the documented ranges give unspecified results. Instances are not
 * thread-safe.
 */
final class Atrac9BitReader {
    private byte[] data;

    private int offset;

    private int end;

    /**
     * The position in bits from {@link #offset}.
     */
    private int position;

    /**
     * Starts reading a range.
     *
     * @param data the bytes
     * @param offset the index of the first byte
     * @param length the number of bytes
     */
    void reset(byte[] data, int offset, int length) {
        this.data = data;
        this.offset = offset;
        this.end = offset + length;
        this.position = 0;
    }

    /**
     * {@return the position, in bits from the start of the range}
     */
    int position() {
        return position;
    }

    /**
     * Returns the next bits without consuming them.
     *
     * @param bits the number of bits, from 0 to 25
     * @return the bits, as an unsigned value
     */
    int peek(int bits) {
        if (bits == 0) {
            return 0;
        }
        var index = offset + (position >>> 3);
        var window = byteAt(index) << 24 | byteAt(index + 1) << 16 | byteAt(index + 2) << 8 | byteAt(index + 3);
        return (window << (position & 7)) >>> (32 - bits);
    }

    private int byteAt(int index) {
        return index < end ? data[index] & 0xFF : 0;
    }

    /**
     * Reads an unsigned value ({@code ReadInt}).
     *
     * @param bits the width, from 0 to 25
     * @return the value
     */
    int read(int bits) {
        var value = peek(bits);
        position += bits;
        return value;
    }

    /**
     * Reads a two's complement value ({@code ReadSignedInt}).
     *
     * @param bits the width, from 1 to 25
     * @return the value
     */
    int readSigned(int bits) {
        return signExtend(read(bits), bits);
    }

    /**
     * Reads a value stored with an offset of half its range ({@code ReadOffsetBinary}).
     *
     * @param bits the width, from 1 to 25
     * @return the value minus {@code 2^(bits - 1)}
     */
    int readOffsetBinary(int bits) {
        return read(bits) - (1 << (bits - 1));
    }

    /**
     * Skips bits.
     *
     * @param bits the number of bits, non-negative
     */
    void skip(int bits) {
        position += bits;
    }

    /**
     * Moves the position to the next multiple of eight bits, unless it is on one.
     */
    void alignByte() {
        position = (position + 7) & ~7;
    }

    /**
     * Sign-extends the low bits of a value ({@code SignExtend32}).
     *
     * @param value the bits to extend, right-aligned
     * @param bits the width of its two's complement form, from 1 to 32
     * @return the sign-extended value
     */
    static int signExtend(int value, int bits) {
        var shift = 32 - bits;
        return value << shift >> shift;
    }
}
