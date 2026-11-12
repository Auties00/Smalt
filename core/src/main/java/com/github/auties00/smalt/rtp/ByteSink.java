package com.github.auties00.smalt.rtp;

import com.github.auties00.smalt.MediaException;

import java.util.Arrays;

/**
 * A growable byte buffer that reassembly reuses from unit to unit, bounded by a size limit.
 *
 * <p>Instances are not thread-safe.
 */
final class ByteSink {
    private final long limit;
    private byte[] data = new byte[256];
    private int size;

    ByteSink(long limit) {
        this.limit = limit;
    }

    int size() {
        return size;
    }

    byte[] array() {
        return data;
    }

    void clear() {
        size = 0;
    }

    void truncate(int size) {
        this.size = size;
    }

    private void ensure(int extra) {
        var needed = (long) size + extra;
        if (needed > limit || needed > Integer.MAX_VALUE - 16) {
            throw new MediaException.Malformed("reassembled unit of " + needed + " bytes exceeds the limit of "
                    + limit);
        }
        if (needed > data.length) {
            data = Arrays.copyOf(data, (int) Math.min(Math.max(needed, 2L * data.length), Integer.MAX_VALUE - 16));
        }
    }

    void write(int value) {
        ensure(1);
        data[size++] = (byte) value;
    }

    void write(byte[] source, int offset, int length) {
        ensure(length);
        System.arraycopy(source, offset, data, size, length);
        size += length;
    }

    void writeInt(int value) {
        ensure(4);
        data[size] = (byte) (value >>> 24);
        data[size + 1] = (byte) (value >>> 16);
        data[size + 2] = (byte) (value >>> 8);
        data[size + 3] = (byte) value;
        size += 4;
    }

    void writeLeb128(long value) {
        do {
            var low = (int) (value & 0x7F);
            value >>>= 7;
            write(value != 0 ? low | 0x80 : low);
        } while (value != 0);
    }

    void set(int position, int value) {
        data[position] = (byte) value;
    }

    byte[] toByteArray() {
        return Arrays.copyOf(data, size);
    }
}
