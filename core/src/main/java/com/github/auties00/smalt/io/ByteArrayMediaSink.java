package com.github.auties00.smalt.io;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;

/**
 * Collects the output of a muxer or an encoder in memory.
 *
 * <p>The sink is seekable and grows as needed; a gap left by a repositioning past the end reads
 * as zeros. {@link #toByteArray()} returns the bytes up to the furthest position written, which
 * is what {@link #size()} reports. Created by {@link MediaSink#memory()}.
 *
 * <p>An instance is not thread-safe. {@link #close()} releases nothing, so the bytes stay
 * available after it.
 */
public final class ByteArrayMediaSink implements MediaSink {
    /**
     * The initial capacity of the buffer.
     */
    private static final int INITIAL_CAPACITY = 8192;

    /**
     * The largest array the JVM reliably allocates.
     */
    private static final int MAX_CAPACITY = Integer.MAX_VALUE - 8;

    /**
     * The buffer holding the bytes, larger than {@link #size} after growth.
     */
    private byte[] buffer;

    /**
     * The index the next write starts at.
     */
    private int position;

    /**
     * The number of bytes up to the furthest position written.
     */
    private int size;

    /**
     * Constructs an empty sink.
     */
    ByteArrayMediaSink() {
        this.buffer = new byte[INITIAL_CAPACITY];
    }

    @Override
    public long position() {
        return position;
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if {@code position} is negative or exceeds the largest
     *                                  array the sink can hold
     */
    @Override
    public void position(long position) {
        if (position < 0 || position > MAX_CAPACITY) {
            throw new IllegalArgumentException("position " + position + " is outside 0.." + MAX_CAPACITY);
        }
        this.position = (int) position;
    }

    /**
     * Returns {@code true}: the bytes are in memory.
     *
     * @return {@code true}
     */
    @Override
    public boolean isSeekable() {
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalStateException if the output would exceed the largest array the sink can
     *                               hold
     */
    @Override
    public void write(ByteBuffer data) {
        Objects.requireNonNull(data, "data");
        var length = data.remaining();
        var end = (long) position + length;
        if (end > MAX_CAPACITY) {
            throw new IllegalStateException("the output exceeds " + MAX_CAPACITY + " bytes");
        }
        ensureCapacity((int) end);
        data.get(buffer, position, length);
        position = (int) end;
        size = Math.max(size, position);
    }

    /**
     * Returns the number of bytes up to the furthest position written.
     *
     * @return the size in bytes
     */
    public int size() {
        return size;
    }

    /**
     * Returns a copy of the bytes up to the furthest position written.
     *
     * @return the bytes
     */
    public byte[] toByteArray() {
        return Arrays.copyOf(buffer, size);
    }

    /**
     * Does nothing: the bytes stay available.
     */
    @Override
    public void close() {
    }

    /**
     * Grows the buffer to hold at least {@code capacity} bytes.
     *
     * @param capacity the required capacity
     */
    private void ensureCapacity(int capacity) {
        if (capacity <= buffer.length) {
            return;
        }
        var grown = (int) Math.min(MAX_CAPACITY, Math.max(capacity, 2L * buffer.length));
        buffer = Arrays.copyOf(buffer, grown);
    }
}
