package com.github.auties00.smalt.internal.io;

import com.github.auties00.smalt.io.MediaSource;

import java.lang.foreign.MemorySegment;

/**
 * Exposes the memory segment behind an in-memory {@link MediaSource} whose bytes are not a byte
 * array, so a parser can copy a payload straight out of the segment instead of staging it in a
 * buffer of its own first.
 *
 * <p>Source offset {@code p} is the byte of {@link #segment()} at offset {@code p}, for {@code p}
 * below {@link MediaSource#size()}, which equals the segment size. The segment is accessible from
 * every thread. A parser must never write into the segment and must never hand it, or a slice of
 * it, to its caller: every payload it returns is a copy, made with one bulk
 * {@link MemorySegment#copy(MemorySegment, java.lang.foreign.ValueLayout.OfByte, long, Object, int, int)}.
 */
public interface SegmentBacked {
    /**
     * Returns the backing segment.
     *
     * @return the segment, shared with the source
     */
    MemorySegment segment();

    /**
     * Returns the segment behind a source, when there is one.
     *
     * @param source the source
     * @return the source as a {@link SegmentBacked}, or {@code null} when it is not segment backed
     */
    static SegmentBacked of(MediaSource source) {
        return source instanceof SegmentBacked backed ? backed : null;
    }

    /**
     * Returns whether every byte of a source is in memory, behind an array or a segment, so that
     * reading any range again costs only a copy.
     *
     * @param source the source
     * @return {@code true} for an {@link ArrayBacked} or {@link SegmentBacked} source
     */
    static boolean isInMemory(MediaSource source) {
        return source instanceof ArrayBacked || source instanceof SegmentBacked;
    }
}
