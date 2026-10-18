package com.github.auties00.smalt.io;

import com.github.auties00.smalt.internal.io.ArrayBacked;
import com.github.auties00.smalt.internal.io.LocalSource;

import java.util.Objects;

/**
 * Implements {@link MediaSource} over a range of a byte array.
 *
 * <p>The array is shared, not copied, and reads never block, so concurrent readers need no
 * synchronization. The module's own parsers see the array through {@link ArrayBacked} and read
 * it in place.
 */
final class ByteArrayMediaSource implements MediaSource, ArrayBacked, LocalSource {
    /**
     * The backing array.
     */
    private final byte[] data;

    /**
     * The index in {@link #data} of source offset zero.
     */
    private final int offset;

    /**
     * The number of bytes exposed by this source.
     */
    private final int length;

    /**
     * Constructs a source over {@code data[offset, offset + length)}.
     *
     * @param data   the backing array
     * @param offset the index of source offset zero
     * @param length the number of exposed bytes
     * @throws NullPointerException if {@code data} is {@code null}
     */
    ByteArrayMediaSource(byte[] data, int offset, int length) {
        this.data = Objects.requireNonNull(data, "data");
        this.offset = offset;
        this.length = length;
    }

    @Override
    public long size() {
        return length;
    }

    @Override
    public int read(long position, byte[] buffer, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, buffer.length);
        if (position < 0) {
            throw new IndexOutOfBoundsException("negative position " + position);
        }
        if (position >= this.length) {
            return length == 0 ? 0 : -1;
        }
        var count = (int) Math.min(length, this.length - position);
        System.arraycopy(data, this.offset + (int) position, buffer, offset, count);
        return count;
    }

    @Override
    public byte[] array() {
        return data;
    }

    @Override
    public int arrayOffset() {
        return offset;
    }

    /**
     * Returns {@code true}: the bytes are in memory.
     *
     * @return {@code true}
     */
    @Override
    public boolean isLocal() {
        return true;
    }

    @Override
    public void close() {
    }
}
