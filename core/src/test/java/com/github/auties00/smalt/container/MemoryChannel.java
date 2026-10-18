package com.github.auties00.smalt.container;

import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.util.Arrays;

/**
 * An in-memory {@link SeekableByteChannel} for muxer tests.
 */
public final class MemoryChannel implements SeekableByteChannel {
    private byte[] data = new byte[1024];
    private long size;
    private long position;
    private boolean open = true;

    @Override
    public int read(ByteBuffer destination) {
        if (position >= size) {
            return -1;
        }
        var count = (int) Math.min(destination.remaining(), size - position);
        destination.put(data, (int) position, count);
        position += count;
        return count;
    }

    @Override
    public int write(ByteBuffer source) {
        var count = source.remaining();
        var end = position + count;
        if (end > data.length) {
            data = Arrays.copyOf(data, (int) Math.max(end, data.length * 2L));
        }
        source.get(data, (int) position, count);
        position = end;
        size = Math.max(size, end);
        return count;
    }

    @Override
    public long position() {
        return position;
    }

    @Override
    public SeekableByteChannel position(long newPosition) {
        position = newPosition;
        return this;
    }

    @Override
    public long size() {
        return size;
    }

    @Override
    public SeekableByteChannel truncate(long newSize) {
        size = Math.min(size, newSize);
        position = Math.min(position, size);
        return this;
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public void close() {
        open = false;
    }

    /** Returns the written bytes. */
    public byte[] toByteArray() {
        return Arrays.copyOf(data, (int) size);
    }
}
