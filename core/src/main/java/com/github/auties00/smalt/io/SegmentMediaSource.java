package com.github.auties00.smalt.io;

import com.github.auties00.smalt.internal.io.LocalSource;
import com.github.auties00.smalt.internal.io.SegmentBacked;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Objects;

/**
 * Implements {@link MediaSource} over a memory segment, native or on the heap.
 *
 * <p>The segment is shared, not copied, and reads never block, so concurrent readers need no
 * synchronization; offsets are {@code long}, so a segment larger than 2 GiB is read whole. The
 * module's own parsers see the segment through {@link SegmentBacked} and copy each payload out
 * of it with one bulk copy. The segment belongs to the caller: {@link #close()} leaves its arena
 * open.
 */
final class SegmentMediaSource implements MediaSource, SegmentBacked, LocalSource {
    /**
     * A thread that is never started, through which a segment confined to some thread is told
     * apart: such a segment is not accessible by it, while a global, automatic or shared one is.
     */
    private static final Thread NON_OWNER = Thread.ofPlatform().unstarted(() -> {
    });

    /**
     * The backing segment.
     */
    private final MemorySegment segment;

    /**
     * The segment size in bytes.
     */
    private final long size;

    /**
     * Constructs a source over {@code segment}.
     *
     * @param segment the segment to read
     * @throws NullPointerException     if {@code segment} is {@code null}
     * @throws IllegalArgumentException if the segment is confined to one thread
     * @throws IllegalStateException    if the segment's arena is already closed
     */
    SegmentMediaSource(MemorySegment segment) {
        this.segment = requireShareable(segment);
        this.size = segment.byteSize();
    }

    /**
     * Checks that every thread may read a segment.
     *
     * <p>Decoders read their sources from worker threads, so a segment of a confined arena would
     * fail with {@link WrongThreadException} at its first read off the owner thread; it is
     * rejected here instead, with the reason.
     *
     * @param segment the segment to check
     * @return {@code segment}
     * @throws NullPointerException     if {@code segment} is {@code null}
     * @throws IllegalArgumentException if the segment is confined to one thread
     * @throws IllegalStateException    if the segment's arena is already closed
     */
    static MemorySegment requireShareable(MemorySegment segment) {
        Objects.requireNonNull(segment, "segment");
        if (!segment.scope().isAlive()) {
            throw new IllegalStateException("the segment's arena is already closed");
        }
        if (!segment.isAccessibleBy(NON_OWNER)) {
            throw new IllegalArgumentException("the segment is confined to one thread, but decoders read their sources"
                    + " from worker threads: allocate it in a global, automatic or shared arena"
                    + " (Arena.global(), Arena.ofAuto() or Arena.ofShared())");
        }
        return segment;
    }

    @Override
    public long size() {
        return size;
    }

    @Override
    public int read(long position, byte[] buffer, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, buffer.length);
        if (position < 0) {
            throw new IndexOutOfBoundsException("negative position " + position);
        }
        if (position >= size) {
            return length == 0 ? 0 : -1;
        }
        var count = (int) Math.min(length, size - position);
        MemorySegment.copy(segment, ValueLayout.JAVA_BYTE, position, buffer, offset, count);
        return count;
    }

    @Override
    public MemorySegment segment() {
        return segment;
    }

    /**
     * Returns {@code true}: the bytes are in memory or in a mapped local file.
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
