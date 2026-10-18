package com.github.auties00.smalt.image.jpeg.internal;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Writes a JPEG file into a growable array: marker segments byte by byte and entropy-coded data
 * bit by bit, with {@code 0xFF} bytes stuffed by a zero byte.
 *
 * <p>Bits accumulate in a 64-bit register and are written eight bytes at a time; a word that
 * holds a {@code 0xFF} byte is written byte by byte with stuffing, which is rare in practice. A
 * writer created without stuffing collects raw entropy-coded bits for a part of the scan, which
 * {@link #appendRaw} later appends, stuffed, to the file's writer.
 *
 * @implNote This implementation follows the 64-bit put buffer of libjpeg-turbo's jchuff.c,
 * including its padding of the last partial byte with one bits.
 */
public final class JpegBitWriter {
    /**
     * Writes a big-endian {@code long} into a byte array at any offset.
     */
    private static final VarHandle LONG_BE = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.BIG_ENDIAN);

    /**
     * A {@code long} with every byte equal to {@code 0x01}.
     */
    private static final long ONES = 0x0101010101010101L;

    /**
     * A {@code long} with the high bit of every byte set.
     */
    private static final long HIGHS = 0x8080808080808080L;

    /**
     * The output bytes.
     */
    private byte[] buffer;

    /**
     * The number of bytes written.
     */
    private int size;

    /**
     * The pending bits, right-aligned; bits above the {@code 64 - free} valid ones are ignored.
     */
    private long bits;

    /**
     * The number of bit positions still free in {@link #bits}, from 1 to 64.
     */
    private int free = 64;

    /**
     * Whether {@code 0xFF} bytes of entropy-coded data are followed by a zero byte.
     */
    private final boolean stuffing;

    /**
     * Constructs a writer.
     *
     * @param capacity the initial capacity in bytes
     */
    public JpegBitWriter(int capacity) {
        this(capacity, true);
    }

    /**
     * Constructs a writer, with or without byte stuffing.
     *
     * @param capacity the initial capacity in bytes
     * @param stuffing whether {@code 0xFF} bytes of entropy-coded data are followed by a zero byte;
     * a writer without stuffing only takes entropy-coded bits, which it stores in whole 64-bit
     * words plus the pending bits
     */
    JpegBitWriter(int capacity, boolean stuffing) {
        this.buffer = new byte[Math.max(capacity, 64)];
        this.stuffing = stuffing;
    }

    /**
     * Appends the entropy-coded bits collected by a writer without stuffing, as if they had been
     * written to this writer directly.
     *
     * @param raw the writer without stuffing
     */
    void appendRaw(JpegBitWriter raw) {
        var data = raw.buffer;
        for (var i = 0; i < raw.size; i += 8) {
            var word = (long) LONG_BE.get(data, i);
            put((int) (word >>> 32), 32);
            put((int) word, 32);
        }
        var pending = 64 - raw.free;
        var value = raw.bits;
        if (pending > 32) {
            put((int) (value >>> 32) & (int) ((1L << (pending - 32)) - 1), pending - 32);
            pending = 32;
        }
        if (pending > 0) {
            put((int) (value & ((1L << pending) - 1)), pending);
        }
    }

    /**
     * Writes a field of bits.
     *
     * @param value the field, right-aligned, with no bit set above {@code length}
     * @param length the number of bits, from 0 to 32
     */
    public void put(int value, int length) {
        if (length < free) {
            bits = (bits << length) | (value & 0xFFFFFFFFL);
            free -= length;
            return;
        }
        var rest = length - free;
        var word = (bits << free) | ((value & 0xFFFFFFFFL) >>> rest);
        flushWord(word);
        bits = value & 0xFFFFFFFFL;
        free = 64 - rest;
    }

    /**
     * Codes one block of a sequential scan from its stored form (T.81 F.1.2): the DC difference,
     * then every nonzero AC coefficient in zigzag order with its zero run, ZRL codes for runs of
     * 16, and EOB when the block ends in zeros.
     *
     * @param dc the DC table
     * @param ac the AC table
     * @param mask the zigzag-order mask of the nonzero AC coefficients, bit 0 clear
     * @param values the stored values
     * @param start the index in {@code values} of the block's DC difference
     * @return the index in {@code values} just after the block
     */
    public int writeStoredBlock(HuffmanEncodingTable dc, HuffmanEncodingTable ac, long mask, short[] values, int start) {
        var bits = this.bits;
        var free = this.free;
        int diff = values[start];
        var next = start + 1;
        var magnitude = diff < 0 ? -diff : diff;
        var size = 32 - Integer.numberOfLeadingZeros(magnitude);
        var value = (dc.codes[size] << size) | ((diff < 0 ? diff - 1 : diff) & ((1 << size) - 1));
        var length = dc.lengths[size] + size;
        if (length < free) {
            bits = (bits << length) | value;
            free -= length;
        } else {
            var rest = length - free;
            flushWord((bits << free) | ((value & 0xFFFFFFFFL) >>> rest));
            bits = value & 0xFFFFFFFFL;
            free = 64 - rest;
        }
        var codes = ac.codes;
        var lengths = ac.lengths;
        var previous = 0;
        while (mask != 0) {
            var k = Long.numberOfTrailingZeros(mask);
            mask &= mask - 1;
            var run = k - previous - 1;
            previous = k;
            while (run > 15) {
                length = lengths[0xF0];
                value = codes[0xF0];
                if (length < free) {
                    bits = (bits << length) | value;
                    free -= length;
                } else {
                    var rest = length - free;
                    flushWord((bits << free) | ((value & 0xFFFFFFFFL) >>> rest));
                    bits = value & 0xFFFFFFFFL;
                    free = 64 - rest;
                }
                run -= 16;
            }
            int coefficient = values[next++];
            magnitude = coefficient < 0 ? -coefficient : coefficient;
            size = 32 - Integer.numberOfLeadingZeros(magnitude);
            var symbol = (run << 4) | size;
            value = (codes[symbol] << size) | ((coefficient < 0 ? coefficient - 1 : coefficient) & ((1 << size) - 1));
            length = lengths[symbol] + size;
            if (length < free) {
                bits = (bits << length) | value;
                free -= length;
            } else {
                var rest = length - free;
                flushWord((bits << free) | ((value & 0xFFFFFFFFL) >>> rest));
                bits = value & 0xFFFFFFFFL;
                free = 64 - rest;
            }
        }
        if (previous != 63) {
            length = lengths[0];
            value = codes[0];
            if (length < free) {
                bits = (bits << length) | value;
                free -= length;
            } else {
                var rest = length - free;
                flushWord((bits << free) | ((value & 0xFFFFFFFFL) >>> rest));
                bits = value & 0xFFFFFFFFL;
                free = 64 - rest;
            }
        }
        this.bits = bits;
        this.free = free;
        return next;
    }

    /**
     * Writes 64 completed bits.
     *
     * @param word the bits
     */
    private void flushWord(long word) {
        ensure(16);
        if (!stuffing) {
            LONG_BE.set(buffer, size, word);
            size += 8;
            return;
        }
        var inverted = ~word;
        if (((inverted - ONES) & word & HIGHS) == 0) {
            LONG_BE.set(buffer, size, word);
            size += 8;
            return;
        }
        for (var shift = 56; shift >= 0; shift -= 8) {
            var value = (byte) (word >>> shift);
            buffer[size++] = value;
            if (value == (byte) 0xFF) {
                buffer[size++] = 0;
            }
        }
    }

    /**
     * Writes the pending bits, padding the last partial byte with one bits, and leaves the writer
     * byte-aligned.
     */
    public void flushBits() {
        var valid = 64 - free;
        ensure(16);
        while (valid >= 8) {
            valid -= 8;
            var value = (byte) (bits >>> valid);
            buffer[size++] = value;
            if (value == (byte) 0xFF) {
                buffer[size++] = 0;
            }
        }
        if (valid > 0) {
            var value = (byte) ((bits << (8 - valid)) | (0xFF >>> valid));
            buffer[size++] = value;
            if (value == (byte) 0xFF) {
                buffer[size++] = 0;
            }
        }
        bits = 0;
        free = 64;
    }

    /**
     * Writes one byte without stuffing; the writer must be byte-aligned.
     *
     * @param value the byte; bits above 8 are ignored
     */
    public void writeByte(int value) {
        ensure(1);
        buffer[size++] = (byte) value;
    }

    /**
     * Writes a big-endian 16-bit value without stuffing; the writer must be byte-aligned.
     *
     * @param value the value; bits above 16 are ignored
     */
    public void writeShort(int value) {
        ensure(2);
        buffer[size++] = (byte) (value >>> 8);
        buffer[size++] = (byte) value;
    }

    /**
     * Writes a marker, {@code 0xFF} followed by its code; the writer must be byte-aligned.
     *
     * @param code the marker code
     */
    public void writeMarker(int code) {
        ensure(2);
        buffer[size++] = (byte) 0xFF;
        buffer[size++] = (byte) code;
    }

    /**
     * Writes bytes without stuffing; the writer must be byte-aligned.
     *
     * @param data the bytes
     */
    public void writeBytes(byte[] data) {
        ensure(data.length);
        System.arraycopy(data, 0, buffer, size, data.length);
        size += data.length;
    }

    /**
     * Writes a DQT segment holding one quantization table; the writer must be byte-aligned.
     *
     * @param slot the table slot, from 0 to 3
     * @param table the 64 quantizer values in natural order
     * @param sixteenBit whether the values are written on 16 bits rather than 8
     */
    public void writeDqt(int slot, int[] table, boolean sixteenBit) {
        writeMarker(0xDB);
        writeShort(2 + 1 + (sixteenBit ? 128 : 64));
        writeByte(slot | (sixteenBit ? 0x10 : 0));
        for (var k = 0; k < 64; k++) {
            var value = table[JpegTables.ZIGZAG[k]];
            if (sixteenBit) {
                writeByte(value >> 8);
            }
            writeByte(value);
        }
    }

    /**
     * Writes a DHT segment holding one Huffman table; the writer must be byte-aligned.
     *
     * @param classAndSlot the table class and slot, {@code (class << 4) | slot}, class 0 for DC and
     * 1 for AC
     * @param spec the table
     */
    public void writeDht(int classAndSlot, HuffmanSpec spec) {
        writeMarker(0xC4);
        writeShort(2 + 1 + 16 + spec.size());
        writeByte(classAndSlot);
        for (var length = 1; length <= 16; length++) {
            writeByte(spec.count(length));
        }
        for (var i = 0; i < spec.size(); i++) {
            writeByte(spec.value(i));
        }
    }

    /**
     * Writes a DRI segment; the writer must be byte-aligned.
     *
     * @param interval the restart interval in MCUs, from 0 to 65535
     */
    public void writeDri(int interval) {
        writeMarker(0xDD);
        writeShort(4);
        writeShort(interval);
    }

    /**
     * Grows the buffer so that at least {@code bytes} more bytes fit.
     *
     * @param bytes the number of bytes about to be written
     */
    private void ensure(int bytes) {
        if (size + bytes > buffer.length) {
            var grown = Math.max((long) buffer.length * 2, (long) size + bytes);
            if (grown > Integer.MAX_VALUE - 16) {
                grown = Integer.MAX_VALUE - 16;
                if (grown < (long) size + bytes) {
                    throw new IllegalStateException("JPEG output exceeds the largest Java array");
                }
            }
            buffer = Arrays.copyOf(buffer, (int) grown);
        }
    }

    /**
     * Returns a copy of the written bytes.
     *
     * @return the bytes; pending bits that were not flushed are not included
     */
    public byte[] toByteArray() {
        return Arrays.copyOf(buffer, size);
    }

    /**
     * Returns the number of bytes written.
     *
     * @return the size in bytes
     */
    public int size() {
        return size;
    }
}
