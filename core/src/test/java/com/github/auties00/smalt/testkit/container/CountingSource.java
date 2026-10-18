package com.github.auties00.smalt.testkit.container;

import com.github.auties00.smalt.io.MediaSource;

import java.util.Objects;

/**
 * A sized source over an array that is neither in memory nor local to the parsers reading it, and
 * that records how it is read: the number of reads and bytes, the reads that start behind the end
 * of the previous one, the furthest offset reached, and the transfer an HTTP source fetching the
 * array with range requests would perform for the same reads. The modeled HTTP source keeps one
 * response open at a time, skips forward on it for a read up to 256 KiB ahead, opens a new response
 * for any other jump, and keeps the first 256 KiB once read.
 * Reads may be capped at a chunk size, as network sources deliver them.
 */
public final class CountingSource implements MediaSource {
    /** The largest forward gap the modeled HTTP source skips on its open response. */
    static final int HTTP_SKIP_LIMIT = 256 * 1024;

    /** The number of leading bytes the modeled HTTP source keeps once read. */
    static final int HTTP_HEAD_CACHE = 256 * 1024;

    private final byte[] data;
    private final int chunk;
    public long reads;
    public long bytes;
    public long backward;
    public long requests = 1;
    public long downloaded;
    public long furthest;
    private long lastEnd = -1;
    private long streamPosition;
    private long headLength;

    public CountingSource(byte[] data) {
        this(data, Integer.MAX_VALUE);
    }

    public CountingSource(byte[] data, int chunk) {
        this.data = Objects.requireNonNull(data);
        this.chunk = chunk;
    }

    /** Clears the counters, keeping the modeled HTTP state. */
    public void reset() {
        reads = 0;
        bytes = 0;
        backward = 0;
        furthest = 0;
    }

    @Override
    public long size() {
        return data.length;
    }

    @Override
    public int read(long position, byte[] buffer, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, buffer.length);
        if (position < 0) {
            throw new IndexOutOfBoundsException("negative position " + position);
        }
        if (length == 0) {
            return 0;
        }
        if (position >= data.length) {
            return -1;
        }
        reads++;
        if (lastEnd >= 0 && position < lastEnd) {
            backward++;
        }
        int count;
        if (position < headLength) {
            count = (int) Math.min(Math.min(length, chunk), headLength - position);
        } else {
            if (position != streamPosition) {
                if (position > streamPosition && position - streamPosition <= HTTP_SKIP_LIMIT) {
                    downloaded += position - streamPosition;
                    remember(streamPosition, position - streamPosition);
                } else {
                    requests++;
                }
                streamPosition = position;
            }
            count = (int) Math.min(Math.min(length, chunk), data.length - position);
            downloaded += count;
            remember(position, count);
            streamPosition += count;
        }
        System.arraycopy(data, (int) position, buffer, offset, count);
        bytes += count;
        lastEnd = position + count;
        furthest = Math.max(furthest, lastEnd);
        return count;
    }

    private void remember(long position, long count) {
        if (position > headLength || position >= HTTP_HEAD_CACHE) {
            return;
        }
        var skip = headLength - position;
        headLength += Math.max(0, Math.min(count - skip, HTTP_HEAD_CACHE - headLength));
    }

    @Override
    public String toString() {
        return reads + " reads, " + bytes + " bytes of " + data.length + ", " + backward + " backward, " + requests
                + " http requests, " + downloaded + " bytes downloaded";
    }

    @Override
    public void close() {
    }
}
