package com.github.auties00.smalt.audio.flac;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Writes the most-significant-bit-first bit stream of a FLAC frame into a growable byte array.
 *
 * <p>Bits collect in a 64-bit accumulator that is stored to the array as one big-endian word
 * whenever it fills, so a Rice-coded residual sample costs one accumulator update, occasionally
 * followed by a word store. The array grows on demand;
 * a writer is reused across frames through {@link #reset()}.
 *
 * @implNote This implementation follows the structure of libFLAC's {@code bitwriter.c}
 * (BSD-3-Clause): a word accumulator, a Rice block writer that merges the unary stop bit and the
 * binary part of every sample into one put, and zero-word runs for long unary prefixes.
 */
final class FlacBitWriter {
    /**
     * The initial capacity of the byte array.
     */
    private static final int INITIAL_CAPACITY = 1 << 14;

    /**
     * Stores eight bytes as a big-endian {@code long}.
     */
    private static final VarHandle LONG_BE = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.BIG_ENDIAN);

    /**
     * The bytes written so far, followed by spare capacity.
     */
    private byte[] buffer;

    /**
     * The number of whole bytes flushed to {@link #buffer}.
     */
    private int position;

    /**
     * The pending bits, right-aligned: the oldest pending bit is bit {@code pending - 1}.
     */
    private long accumulator;

    /**
     * The number of pending bits in {@link #accumulator}, from 0 to 63.
     */
    private int pending;

    /**
     * Creates an empty writer.
     */
    FlacBitWriter() {
        this.buffer = new byte[INITIAL_CAPACITY];
    }

    /**
     * Discards everything written, keeping the capacity.
     */
    void reset() {
        position = 0;
        accumulator = 0;
        pending = 0;
    }

    /**
     * Returns the number of bits written.
     *
     * @return the bit count
     */
    long bitCount() {
        return (long) position * 8 + pending;
    }

    /**
     * Returns whether the bits written end on a byte boundary.
     *
     * @return {@code true} when the bit count is a multiple of eight
     */
    boolean isByteAligned() {
        return (pending & 7) == 0;
    }

    /**
     * Writes the low {@code bits} bits of a value.
     *
     * @param value the value; bits above {@code bits} are ignored
     * @param bits  the number of bits, from 0 to 32
     */
    void write(int value, int bits) {
        if (bits == 0) {
            return;
        }
        put(value & (-1L >>> (64 - bits)), bits);
    }

    /**
     * Writes the low {@code bits} bits of a value.
     *
     * @param value the value; bits above {@code bits} are ignored
     * @param bits  the number of bits, from 0 to 64
     */
    void writeLong(long value, int bits) {
        if (bits == 0) {
            return;
        }
        if (bits > 32) {
            put((value >>> 32) & (-1L >>> (96 - bits)), bits - 32);
            put(value & 0xFFFFFFFFL, 32);
        } else {
            put(value & (-1L >>> (64 - bits)), bits);
        }
    }

    /**
     * Writes a unary-coded value: {@code value} zero bits followed by a one bit.
     *
     * @param value the value, non-negative
     */
    void writeUnary(int value) {
        var zeros = value;
        while (zeros >= 32) {
            put(0, 32);
            zeros -= 32;
        }
        put(1, zeros + 1);
    }

    /**
     * Writes the UTF-8-like coded form of a frame or sample number (RFC 9639 section 9.1.5).
     *
     * @param value the number, below {@code 2^36}
     */
    void writeUtf8(long value) {
        if (value < 0x80) {
            put(value, 8);
            return;
        }
        var length = value < 0x800 ? 2 : value < 0x10000 ? 3 : value < 0x200000 ? 4
                : value < 0x4000000 ? 5 : value < 0x80000000L ? 6 : 7;
        var lead = length == 7 ? 0xFE : (0xFF00 >>> length) & 0xFF;
        put(lead | (value >>> (6 * (length - 1))), 8);
        for (var i = length - 2; i >= 0; i--) {
            put(0x80 | ((value >>> (6 * i)) & 0x3F), 8);
        }
    }

    /**
     * Writes a block of signed values Rice-coded with one parameter (RFC 9639 section 9.2.7.1).
     *
     * <p>Each value is folded to {@code (v << 1) ^ (v >> 31)}, whose quotient by
     * {@code 2^parameter} is written in unary and whose remainder in {@code parameter} bits.
     *
     * @param values    the values, none equal to {@link Integer#MIN_VALUE}
     * @param from      the index of the first value
     * @param to        the index after the last value
     * @param parameter the Rice parameter, from 0 to 30
     */
    void writeRiceBlock(int[] values, int from, int to, int parameter) {
        var mask = (1L << parameter) - 1;
        var stop = 1L << parameter;
        for (var i = from; i < to; i++) {
            var value = values[i];
            var folded = ((long) (value << 1) ^ (value >> 31)) & 0xFFFFFFFFL;
            var quotient = folded >>> parameter;
            if (quotient + 1 + parameter <= 56) {
                put(stop | (folded & mask), (int) quotient + 1 + parameter);
            } else {
                var zeros = quotient;
                while (zeros >= 24) {
                    put(0, 24);
                    zeros -= 24;
                }
                put(stop | (folded & mask), (int) zeros + 1 + parameter);
            }
        }
    }

    /**
     * Writes a block of signed values in two's complement with a fixed width.
     *
     * @param values the values, each representable in {@code bits} bits
     * @param from   the index of the first value
     * @param to     the index after the last value
     * @param bits   the width, from 0 to 32
     */
    void writeSignedBlock(int[] values, int from, int to, int bits) {
        if (bits == 0) {
            return;
        }
        var mask = -1L >>> (64 - bits);
        for (var i = from; i < to; i++) {
            put(values[i] & mask, bits);
        }
    }

    /**
     * Writes a block of signed values in two's complement with a fixed width of up to 33 bits.
     *
     * @param values the values, each representable in {@code bits} bits
     * @param from   the index of the first value
     * @param to     the index after the last value
     * @param bits   the width, from 1 to 33
     */
    void writeSignedBlock(long[] values, int from, int to, int bits) {
        for (var i = from; i < to; i++) {
            writeLong(values[i], bits);
        }
    }

    /**
     * Pads with zero bits to the next byte boundary.
     */
    void alignToByte() {
        var padding = (8 - (pending & 7)) & 7;
        if (padding != 0) {
            put(0, padding);
        }
    }

    /**
     * Returns the array holding the bytes written, valid up to {@link #byteCount()} after
     * {@link #flush()}.
     *
     * @return the internal array, not a copy
     */
    byte[] buffer() {
        return buffer;
    }

    /**
     * Returns the number of whole bytes written, once flushed.
     *
     * @return the byte count
     */
    int byteCount() {
        return position;
    }

    /**
     * Moves every pending whole byte into the array; the writer must be byte aligned.
     */
    void flush() {
        ensure(8);
        while (pending >= 8) {
            pending -= 8;
            buffer[position++] = (byte) (accumulator >>> pending);
        }
        accumulator &= (1L << pending) - 1;
    }

    /**
     * Returns a copy of the bytes written; the writer must be byte aligned.
     *
     * @return the bytes
     */
    byte[] toByteArray() {
        flush();
        return Arrays.copyOf(buffer, position);
    }

    /**
     * Appends up to 57 bits.
     *
     * @param value the bits, already masked to {@code bits}
     * @param bits  the number of bits, from 1 to 57
     */
    private void put(long value, int bits) {
        var free = 64 - pending;
        if (bits < free) {
            accumulator = (accumulator << bits) | value;
            pending += bits;
            return;
        }
        // Complete the accumulator to a full word with the value's high bits and store it.
        var rest = bits - free;
        ensure(8);
        LONG_BE.set(buffer, position, (accumulator << free) | (value >>> rest));
        position += 8;
        accumulator = value & ((1L << rest) - 1);
        pending = rest;
    }

    /**
     * Grows the array so that {@code extra} more bytes fit.
     *
     * @param extra the number of bytes about to be written
     */
    private void ensure(int extra) {
        if (position + extra > buffer.length) {
            buffer = Arrays.copyOf(buffer, Math.max(buffer.length * 2, position + extra));
        }
    }
}
