package com.github.auties00.smalt.testkit.audio;

import com.github.auties00.smalt.io.MediaSource;

/**
 * A byte-array source that hides its length ({@link #size()} is {@code -1}) and delivers at most
 * {@code chunk} bytes per read, like a network stream, to exercise the unknown-size and short-read
 * paths of demuxers.
 */
public final class UnsizedSource implements MediaSource {
    private final byte[] data;
    private final int chunk;

    public UnsizedSource(byte[] data, int chunk) {
        this.data = data;
        this.chunk = chunk;
    }

    @Override
    public long size() {
        return -1;
    }

    @Override
    public int read(long position, byte[] buffer, int offset, int length) {
        java.util.Objects.checkFromIndexSize(offset, length, buffer.length);
        if (position >= data.length) {
            return length == 0 ? 0 : -1;
        }
        var count = (int) Math.min(Math.min(length, chunk), data.length - position);
        System.arraycopy(data, (int) position, buffer, offset, count);
        return count;
    }

    @Override
    public void close() {
    }
}
