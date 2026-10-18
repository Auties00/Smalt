package com.github.auties00.smalt.internal.io;

import com.github.auties00.smalt.io.MediaSource;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Objects;

/**
 * Serves small positional reads of a {@link MediaSource} from a window that is refilled in large
 * blocks.
 *
 * <p>{@link #read(long, byte[], int, int)} has the contract of
 * {@link MediaSource#read(long, byte[], int, int)}, except that it may return fewer bytes than
 * the source would have and that it reports every read of zero bytes from the source as the end
 * of the input. Parsers that probe a few bytes at scattered but mostly increasing offsets, such as
 * frame headers of an elementary stream, use it to replace one source read per probe with one
 * source read per window, which matters for file and network sources where every read is a
 * system call or a request. An in-memory source ({@link ArrayBacked} or {@link SegmentBacked}) is
 * copied from directly, with one bulk copy per read and without a window. A cache is not
 * thread-safe.
 *
 * <p>When a read runs ahead of the window, the window slides forward but keeps the last
 * look-behind bytes before the new position, so a parser that probes ahead and then reads a
 * little behind the probe (a frame's payload after the next frame's header) is served without
 * reading the source backwards; the source is then read strictly sequentially.
 *
 * <p>A cache may be given a limit, the end of the region its parser reads: the window then never
 * extends past it, so the source is not read beyond the region, and reads at or past the limit
 * go straight to the source.
 */
public final class ReadAheadCache {
    /**
     * The source being read.
     */
    private final MediaSource source;

    /**
     * The segment behind a segment-backed source, or {@code null}.
     */
    private final MemorySegment segment;

    /**
     * The window of buffered bytes, or {@code null} for an in-memory source.
     */
    private final byte[] window;

    /**
     * The number of bytes kept behind a position that makes the window slide.
     */
    private final int lookBehind;

    /**
     * The array behind an in-memory source, or {@code null}.
     */
    private final ArrayBacked backing;

    /**
     * The size of an in-memory source.
     */
    private final long backingSize;

    /**
     * The offset the window never extends past, or {@link Long#MAX_VALUE}.
     */
    private final long limit;

    /**
     * The source offset of {@code window[0]}.
     */
    private long windowStart;

    /**
     * The number of valid bytes in {@link #window}.
     */
    private int windowLength;

    /**
     * Constructs an empty cache.
     *
     * @param source     the source to read
     * @param size       the window size in bytes; positive
     * @param lookBehind the number of bytes kept behind a position that makes the window slide;
     *                   from {@code 0} to less than half of {@code size}
     * @throws NullPointerException     if {@code source} is {@code null}
     * @throws IllegalArgumentException if {@code size} or {@code lookBehind} is out of range
     */
    public ReadAheadCache(MediaSource source, int size, int lookBehind) {
        this(source, size, lookBehind, Long.MAX_VALUE);
    }

    /**
     * Constructs an empty cache whose window never extends past a limit.
     *
     * @param source     the source to read
     * @param size       the window size in bytes; positive
     * @param lookBehind the number of bytes kept behind a position that makes the window slide;
     *                   from {@code 0} to less than half of {@code size}
     * @param limit      the offset the window never extends past, or {@link Long#MAX_VALUE}
     * @throws NullPointerException     if {@code source} is {@code null}
     * @throws IllegalArgumentException if {@code size} or {@code lookBehind} is out of range
     */
    public ReadAheadCache(MediaSource source, int size, int lookBehind, long limit) {
        this.source = Objects.requireNonNull(source, "source");
        this.limit = limit;
        if (size <= 0) {
            throw new IllegalArgumentException("window size " + size + " is not positive");
        }
        if (lookBehind < 0 || lookBehind >= size / 2) {
            throw new IllegalArgumentException("look-behind " + lookBehind + " is outside 0.." + (size / 2 - 1));
        }
        this.lookBehind = lookBehind;
        this.backing = ArrayBacked.of(source);
        var segmentBacked = SegmentBacked.of(source);
        this.segment = segmentBacked != null ? segmentBacked.segment() : null;
        this.backingSize = backing != null || segment != null ? source.size() : 0;
        this.window = backing != null || segment != null ? null : new byte[size];
    }

    /**
     * Reads up to {@code length} bytes starting at the absolute offset {@code position}.
     *
     * <p>When {@code position} lies outside the window, a request of at least a window, or one at
     * or past the limit, is read straight into {@code target}; a smaller one moves the window to
     * {@code position}, sliding it when {@code position} lies just ahead of it.
     *
     * @param position the absolute offset of the first byte to read; non-negative
     * @param target   the destination array
     * @param offset   the index in {@code target} of the first byte written
     * @param length   the maximum number of bytes to read; non-negative
     * @return the number of bytes read, {@code 0} only when {@code length} is {@code 0}, or
     *         {@code -1} at the end of the input
     * @throws IndexOutOfBoundsException if the {@code target} range is invalid or
     *                                   {@code position} is negative
     * @throws com.github.auties00.smalt.MediaException.Io if the source read fails
     */
    public int read(long position, byte[] target, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, target.length);
        if (position < 0) {
            throw new IndexOutOfBoundsException("negative position " + position);
        }
        if (length == 0) {
            return 0;
        }
        if (backing != null) {
            if (position >= backingSize) {
                return -1;
            }
            var direct = (int) Math.min(length, backingSize - position);
            System.arraycopy(backing.array(), backing.arrayOffset() + (int) position, target, offset, direct);
            return direct;
        }
        if (segment != null) {
            if (position >= backingSize) {
                return -1;
            }
            var direct = (int) Math.min(length, backingSize - position);
            MemorySegment.copy(segment, ValueLayout.JAVA_BYTE, position, target, offset, direct);
            return direct;
        }
        if (position < windowStart || position >= windowStart + windowLength) {
            if (length >= window.length || position >= limit) {
                var direct = source.read(position, target, offset, length);
                return direct <= 0 ? -1 : direct;
            }
            if (!move(position)) {
                return -1;
            }
        }
        var count = (int) Math.min(length, windowStart + windowLength - position);
        System.arraycopy(window, (int) (position - windowStart), target, offset, count);
        return count;
    }

    /**
     * Moves the window so that it holds {@code position}.
     *
     * <p>A position just ahead of the window keeps the bytes of the old window from
     * {@code position - lookBehind} on and reads only what follows them; any other position
     * refills the window from {@code position}. The window is filled completely, or up to the
     * limit, unless the source ends first.
     *
     * @param position the offset the window must hold, outside the current window
     * @return {@code false} when the source ends at or before {@code position}
     */
    private boolean move(long position) {
        var windowEnd = windowStart + windowLength;
        var start = position;
        var keep = 0;
        if (position >= windowEnd && windowLength > 0 && position - lookBehind < windowEnd) {
            start = Math.max(position - lookBehind, windowStart);
            keep = (int) (windowEnd - start);
            System.arraycopy(window, (int) (start - windowStart), window, 0, keep);
        }
        windowStart = start;
        windowLength = keep;
        var wanted = (int) Math.min(window.length, limit - windowStart);
        while (windowLength < wanted) {
            var read = source.read(windowStart + windowLength, window, windowLength, wanted - windowLength);
            if (read <= 0) {
                break;
            }
            windowLength += read;
        }
        return windowStart + windowLength > position;
    }

    /**
     * Reads exactly {@code length} bytes, or as many as exist before the end of the input.
     *
     * @param position the absolute offset of the first byte to read; non-negative
     * @param target   the destination array
     * @param offset   the index in {@code target} of the first byte written
     * @param length   the number of bytes to read; non-negative
     * @return the number of bytes read, less than {@code length} only at the end of the input
     * @throws IndexOutOfBoundsException if the {@code target} range is invalid or
     *                                   {@code position} is negative
     * @throws com.github.auties00.smalt.MediaException.Io if the source read fails
     */
    public int readUpTo(long position, byte[] target, int offset, int length) {
        var done = 0;
        while (done < length) {
            var read = read(position + done, target, offset + done, length - done);
            if (read <= 0) {
                break;
            }
            done += read;
        }
        return done;
    }
}
