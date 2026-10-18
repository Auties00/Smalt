package com.github.auties00.smalt.container.ogg;

import com.github.auties00.smalt.io.MediaSource;

import java.util.Arrays;
import java.util.Objects;

/**
 * Keeps the bytes read from a window of a source, so that the passes of the construction of an
 * {@link OggDemuxer} and its demux read every byte of the window from the source once.
 *
 * <p>Construction may read the same bytes several times: the search for the last pages reads
 * chunks backwards from the end, a full scan then reads the file forwards, and the demux reads it
 * forwards again from the end of the headers. Until {@link #start(long)} fixes the window, reads
 * pass straight to the source; from then on, every byte that any of them reads inside the window
 * {@code [from, from + capacity)} is kept, in blocks allocated as they are first written, and a
 * later read of kept bytes is served from memory; a read that starts before a kept range reads
 * the source only up to that range, and a read that starts before the window and reaches into it
 * keeps the part inside. Reads return exactly the bytes the source holds, possibly in shorter
 * pieces, which the {@link MediaSource} contract allows.
 *
 * <p>Once {@link #seal()} is called nothing more is kept, and the blocks wholly behind each read
 * are released, since the demux reads forwards only. A cache is not thread-safe: it serves the
 * single demuxer that created it, and it does not close the source.
 */
final class CachingSource implements MediaSource {
    /**
     * The size of one block of kept bytes.
     *
     * @implNote This implementation uses 16 KiB blocks, small enough that the blocks holding a
     * 64 KiB chunk of last pages waste little memory however the chunk is aligned, and large
     * enough that a window of several MiB needs only a few hundred of them.
     */
    private static final int BLOCK = 16 * 1024;

    /**
     * The source read.
     */
    private final MediaSource source;

    /**
     * The offset of the first byte of the window, or {@code -1} until {@link #start(long)}.
     */
    private long from;

    /**
     * The size of the window in bytes.
     */
    private final long capacity;

    /**
     * The blocks of kept bytes, {@code null} until a byte of the block is kept.
     */
    private final byte[][] blocks;

    /**
     * The window offsets where each kept range starts, in increasing order, in the first
     * {@link #ranges} entries.
     */
    private long[] starts;

    /**
     * The window offsets where each kept range ends, exclusive, matching {@link #starts}.
     */
    private long[] ends;

    /**
     * The number of kept ranges.
     */
    private int ranges;

    /**
     * Whether new bytes are no longer kept.
     */
    private boolean sealed;

    /**
     * The window offset below which the kept bytes were released.
     */
    private long released;

    /**
     * Constructs a cache over a source whose window is fixed later by {@link #start(long)}.
     *
     * @param source   the source to read
     * @param capacity the size of the window in bytes; from {@code 0} to {@code Integer.MAX_VALUE}
     *                 blocks of {@value #BLOCK} bytes
     * @throws NullPointerException if {@code source} is {@code null}
     */
    CachingSource(MediaSource source, long capacity) {
        this.source = Objects.requireNonNull(source, "source");
        this.from = -1;
        this.capacity = capacity;
        this.blocks = new byte[(int) ((capacity + BLOCK - 1) / BLOCK)][];
        this.starts = new long[4];
        this.ends = new long[4];
    }

    /**
     * Fixes the window at an offset and starts keeping the bytes read inside it.
     *
     * @param from the offset of the first byte of the window; non-negative
     * @throws IllegalStateException if the window was already fixed
     */
    void start(long from) {
        if (this.from >= 0) {
            throw new IllegalStateException("the window is already fixed");
        }
        this.from = from;
    }

    @Override
    public long size() {
        return source.size();
    }

    @Override
    public int read(long position, byte[] buffer, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, buffer.length);
        if (position < 0) {
            throw new IndexOutOfBoundsException("negative position " + position);
        }
        var relative = position - from;
        if (from < 0 || length == 0 || relative >= capacity) {
            return source.read(position, buffer, offset, length);
        }
        if (relative < 0) {
            var request = ranges > 0 ? (int) Math.min(length, starts[0] - relative) : length;
            var count = source.read(position, buffer, offset, request);
            if (count > -relative && !sealed) {
                keep(0, buffer, offset + (int) -relative, (int) Math.min(count + relative, capacity));
            }
            return count;
        }
        if (relative < released) {
            return source.read(position, buffer, offset, length);
        }
        if (sealed) {
            release(relative);
        }
        var range = rangeAfter(relative);
        if (range > 0 && ends[range - 1] > relative) {
            var count = (int) Math.min(length, ends[range - 1] - relative);
            copyOut(relative, buffer, offset, count);
            return count;
        }
        var request = range < ranges ? (int) Math.min(length, starts[range] - relative) : length;
        var count = source.read(position, buffer, offset, request);
        if (count > 0 && !sealed) {
            keep(relative, buffer, offset, (int) Math.min(count, capacity - relative));
        }
        return count;
    }

    /**
     * Stops keeping new bytes; the kept bytes are released as the reads move past them.
     */
    void seal() {
        sealed = true;
    }

    /**
     * Returns the index of the first kept range that starts after a window offset.
     *
     * @param relative the window offset
     * @return the number of ranges starting at or before {@code relative}
     */
    private int rangeAfter(long relative) {
        var low = 0;
        var high = ranges;
        while (low < high) {
            var middle = (low + high) >>> 1;
            if (starts[middle] <= relative) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low;
    }

    /**
     * Copies kept bytes out of the blocks.
     *
     * @param relative the window offset of the first byte
     * @param target   the destination
     * @param offset   the index in {@code target} of the first byte
     * @param length   the number of bytes, all kept
     */
    private void copyOut(long relative, byte[] target, int offset, int length) {
        var done = 0;
        while (done < length) {
            var at = relative + done;
            var block = blocks[(int) (at / BLOCK)];
            var inBlock = (int) (at % BLOCK);
            var count = Math.min(length - done, BLOCK - inBlock);
            System.arraycopy(block, inBlock, target, offset + done, count);
            done += count;
        }
    }

    /**
     * Keeps bytes just read and merges their range with the kept ranges it touches.
     *
     * @param relative the window offset of the first byte
     * @param data     the bytes
     * @param offset   the index in {@code data} of the first byte
     * @param length   the number of bytes inside the window
     */
    private void keep(long relative, byte[] data, int offset, int length) {
        if (length <= 0) {
            return;
        }
        var done = 0;
        while (done < length) {
            var at = relative + done;
            var index = (int) (at / BLOCK);
            if (blocks[index] == null) {
                blocks[index] = new byte[BLOCK];
            }
            var inBlock = (int) (at % BLOCK);
            var count = Math.min(length - done, BLOCK - inBlock);
            System.arraycopy(data, offset + done, blocks[index], inBlock, count);
            done += count;
        }
        var start = relative;
        var end = relative + length;
        var first = rangeAfter(start);
        if (first > 0 && ends[first - 1] >= start) {
            first--;
            start = starts[first];
        }
        var last = first;
        while (last < ranges && starts[last] <= end) {
            end = Math.max(end, ends[last]);
            last++;
        }
        var removed = last - first;
        if (removed == 0) {
            if (ranges == starts.length) {
                starts = Arrays.copyOf(starts, ranges * 2);
                ends = Arrays.copyOf(ends, ranges * 2);
            }
            System.arraycopy(starts, first, starts, first + 1, ranges - first);
            System.arraycopy(ends, first, ends, first + 1, ranges - first);
            ranges++;
        } else if (removed > 1) {
            System.arraycopy(starts, last, starts, first + 1, ranges - last);
            System.arraycopy(ends, last, ends, first + 1, ranges - last);
            ranges -= removed - 1;
        }
        starts[first] = start;
        ends[first] = end;
    }

    /**
     * Releases the blocks wholly behind a window offset and stops serving the bytes before it.
     *
     * @param relative the window offset of the current read
     */
    private void release(long relative) {
        var block = relative / BLOCK;
        if (block * BLOCK <= released) {
            return;
        }
        for (var index = (int) (released / BLOCK); index < block; index++) {
            blocks[index] = null;
        }
        released = block * BLOCK;
        var kept = 0;
        for (var index = 0; index < ranges; index++) {
            if (ends[index] > released) {
                starts[kept] = Math.max(starts[index], released);
                ends[kept] = ends[index];
                kept++;
            }
        }
        ranges = kept;
    }

    /**
     * Does nothing: the cache does not own the source.
     */
    @Override
    public void close() {
    }
}
