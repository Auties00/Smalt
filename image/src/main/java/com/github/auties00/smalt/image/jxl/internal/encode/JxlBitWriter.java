package com.github.auties00.smalt.image.jxl.internal.encode;

import java.util.Arrays;

/**
 * Writes a JPEG XL bitstream, least significant bit first, with the field codings of ISO/IEC 18181-1 clause B.
 *
 * <p>Instances are not thread-safe.
 */
public final class JxlBitWriter {
    private byte[] data;

    private long bits;

    private long pending;

    private int pendingCount;

    /**
     * Constructs an empty writer.
     */
    public JxlBitWriter() {
        this(256);
    }

    /**
     * Constructs an empty writer with room for the given number of bytes.
     *
     * @param capacity the initial capacity in bytes
     */
    public JxlBitWriter(int capacity) {
        data = new byte[Math.max(16, capacity)];
    }

    /**
     * Writes the low bits of a value.
     *
     * @param count the number of bits, from 0 to 56
     * @param value the value, whose bits above {@code count} must be zero
     */
    public void write(int count, long value) {
        if (count == 0) {
            return;
        }
        pending |= value << pendingCount;
        pendingCount += count;
        bits += count;
        while (pendingCount >= 8) {
            put((byte) pending);
            pending >>>= 8;
            pendingCount -= 8;
        }
    }

    private void put(byte b) {
        var index = (int) ((bits - pendingCount) >>> 3);
        if (index >= data.length) {
            data = Arrays.copyOf(data, Math.max(data.length * 2, index + 16));
        }
        data[index] = b;
    }

    /**
     * Writes one bit.
     *
     * @param value the bit
     */
    public void writeBool(boolean value) {
        write(1, value ? 1 : 0);
    }

    /**
     * Writes a {@code U32} field with the cheapest selector that holds the value.
     *
     * @param value the value to write
     * @param c0 the offset of selector 0
     * @param u0 the bit count of selector 0
     * @param c1 the offset of selector 1
     * @param u1 the bit count of selector 1
     * @param c2 the offset of selector 2
     * @param u2 the bit count of selector 2
     * @param c3 the offset of selector 3
     * @param u3 the bit count of selector 3
     * @throws IllegalArgumentException if no selector holds the value
     */
    public void writeU32(int value, int c0, int u0, int c1, int u1, int c2, int u2, int c3, int u3) {
        int[] offsets = {c0, c1, c2, c3};
        int[] counts = {u0, u1, u2, u3};
        var best = -1;
        for (var i = 0; i < 4; i++) {
            var delta = (value & 0xFFFF_FFFFL) - (offsets[i] & 0xFFFF_FFFFL);
            if (delta >= 0 && delta < 1L << counts[i] && (best < 0 || counts[i] < counts[best])) {
                best = i;
            }
        }
        if (best < 0) {
            throw new IllegalArgumentException("value " + value + " has no U32 selector");
        }
        write(2, best);
        write(counts[best], (value - offsets[best]) & 0xFFFF_FFFFL);
    }

    /**
     * Writes a {@code U64} field.
     *
     * @param value the value, as an unsigned 64-bit pattern
     */
    public void writeU64(long value) {
        if (value == 0) {
            write(2, 0);
        } else if (Long.compareUnsigned(value, 16) <= 0) {
            write(2, 1);
            write(4, value - 1);
        } else if (Long.compareUnsigned(value, 272) <= 0) {
            write(2, 2);
            write(8, value - 17);
        } else {
            write(2, 3);
            write(12, value & 0xFFF);
            var rest = value >>> 12;
            var shift = 12;
            while (rest != 0) {
                write(1, 1);
                if (shift == 60) {
                    write(4, rest & 0xF);
                    return;
                }
                write(8, rest & 0xFF);
                rest >>>= 8;
                shift += 8;
            }
            write(1, 0);
        }
    }

    /**
     * Writes an {@code Enum} field.
     *
     * @param value the enumerator, from 0 to 63
     */
    public void writeEnum(int value) {
        writeU32(value, 0, 0, 1, 0, 2, 4, 18, 6);
    }

    /**
     * Writes an {@code F16} field, rounding the value to the nearest binary16 value.
     *
     * @param value the value, finite and within the binary16 range
     */
    public void writeF16(float value) {
        write(16, Float.floatToFloat16(value) & 0xFFFF);
    }

    /**
     * Writes zero bits up to the next byte boundary.
     */
    public void zeroPadToByte() {
        var pad = (int) ((8 - (bits & 7)) & 7);
        write(pad, 0);
    }

    /**
     * Appends every bit of another writer, without aligning either.
     *
     * @param other the writer whose bits follow
     */
    public void append(JxlBitWriter other) {
        var full = (int) (other.bits >>> 3);
        if ((bits & 7) == 0) {
            ensure((int) (bits >>> 3) + full + 8);
            System.arraycopy(other.data, 0, data, (int) (bits >>> 3), full);
            bits += 8L * full;
        } else {
            ensure((int) (bits >>> 3) + full + 8);
            for (var i = 0; i < full; i++) {
                write(8, other.data[i] & 0xFF);
            }
        }
        var rest = (int) (other.bits & 7);
        if (rest != 0) {
            write(rest, other.pending & ((1L << rest) - 1));
        }
    }

    /**
     * Appends whole bytes; the writer must be byte-aligned.
     *
     * @param bytes the source
     * @param offset the first byte
     * @param length the number of bytes
     */
    public void appendBytes(byte[] bytes, int offset, int length) {
        if ((bits & 7) != 0) {
            throw new IllegalStateException("writer not byte-aligned");
        }
        ensure((int) (bits >>> 3) + length + 8);
        System.arraycopy(bytes, offset, data, (int) (bits >>> 3), length);
        bits += 8L * length;
    }

    private void ensure(int size) {
        if (size > data.length) {
            data = Arrays.copyOf(data, Math.max(size, data.length * 2));
        }
    }

    /**
     * {@return the number of bits written}
     */
    public long bitsWritten() {
        return bits;
    }

    /**
     * Returns the bytes written so far, the last one zero-padded.
     *
     * @return a new array of {@code ceil(bitsWritten() / 8)} bytes
     */
    public byte[] toByteArray() {
        var length = (int) ((bits + 7) >>> 3);
        var out = Arrays.copyOf(data, length);
        if (pendingCount > 0) {
            out[length - 1] = (byte) pending;
        }
        return out;
    }
}
