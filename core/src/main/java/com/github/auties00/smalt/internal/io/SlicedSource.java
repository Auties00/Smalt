package com.github.auties00.smalt.internal.io;

import com.github.auties00.smalt.io.MediaSource;

import java.util.Objects;

/**
 * Exposes a byte range of another {@link MediaSource} as a source of its own, so a reader of an
 * embedded stream (MPEG audio in a WAVE {@code data} chunk, a preview JPEG in a raw file) can read
 * it without copying.
 *
 * <p>Offset zero of this source is offset {@code start} of the parent. A read at or past the end
 * of a bounded range returns {@code -1}, or {@code 0} when it asks for no bytes. Closing this source
 * does not close the parent. Whether a range is local, and the watermark of a parent that reads
 * its input once, pass through to the parent at the shifted offsets.
 */
public final class SlicedSource implements MediaSource, LocalSource, ForwardOnlySource {
    /**
     * The parent source.
     */
    private final MediaSource parent;

    /**
     * The parent offset of this source's first byte.
     */
    private final long start;

    /**
     * The number of bytes exposed, or {@code -1} when the range runs to the parent's unknown end.
     */
    private final long length;

    /**
     * Constructs a view of {@code parent[start, start + length)}.
     *
     * @param parent the source the range is taken from
     * @param start the parent offset of the first exposed byte, non-negative
     * @param length the number of exposed bytes, or {@code -1} to expose everything after {@code start}
     */
    public SlicedSource(MediaSource parent, long start, long length) {
        this.parent = Objects.requireNonNull(parent, "parent");
        this.start = start;
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
        var count = length;
        if (this.length >= 0) {
            if (position >= this.length) {
                return length == 0 ? 0 : -1;
            }
            count = (int) Math.min(count, this.length - position);
        }
        return parent.read(start + position, buffer, offset, count);
    }

    @Override
    public boolean isLocal() {
        return LocalSource.isLocal(parent);
    }

    @Override
    public boolean isLocal(long from, long to) {
        return LocalSource.isLocal(parent, start + from, start + to);
    }

    @Override
    public void release(long position) {
        ForwardOnlySource.release(parent, start + position);
    }

    @Override
    public int readOnce(long position, byte[] buffer, int offset, int length) {
        var count = this.length >= 0 ? (int) Math.max(0, Math.min(length, this.length - position)) : length;
        return ForwardOnlySource.readOnce(parent, start + position, buffer, offset, count);
    }

    @Override
    public boolean fitsInMemory(long end) {
        return ForwardOnlySource.isCheapToReach(parent, start + end);
    }

    @Override
    public boolean keeps(long from, long to) {
        return from >= to || ForwardOnlySource.keeps(parent, start + from, start + to);
    }

    @Override
    public void close() {
    }
}
