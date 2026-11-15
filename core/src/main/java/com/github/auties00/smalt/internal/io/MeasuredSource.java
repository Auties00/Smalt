package com.github.auties00.smalt.internal.io;

import com.github.auties00.smalt.io.MediaSource;

import java.util.Objects;

/**
 * Gives a source of unknown size that has random access the size its end shows, so a parser that
 * needs the size (to read an index or the last pages of a file) works as it does over a sized source.
 *
 * <p>The end is found by probing: one-byte reads at doubling offsets until one reports the end, then a
 * bisection between the last two, so measuring costs about twice the base-two logarithm of the size in
 * reads. A source that reads its input once is never measured, because a read far ahead would take
 * every byte before it from the input. The size is sampled once; bytes the source gains afterwards are
 * not read. Reads and locality pass through to the measured source; closing the view does not close it.
 */
public final class MeasuredSource implements MediaSource, LocalSource {
    /**
     * The first offset probed.
     */
    private static final long FIRST_PROBE = 1 << 16;

    /**
     * The measured source.
     */
    private final MediaSource source;

    /**
     * The size found.
     */
    private final long size;

    private MeasuredSource(MediaSource source, long size) {
        this.source = source;
        this.size = size;
    }

    /**
     * Returns a sized view of a source whose size is unknown and that has random access, or the
     * source itself when its size is known or it reads its input once.
     *
     * @param source the source to measure, not closed
     * @return the source with a known size, or {@code source}
     * @throws com.github.auties00.smalt.MediaException.Io if a probing read fails
     */
    public static MediaSource measure(MediaSource source) {
        Objects.requireNonNull(source, "source");
        if (source.size() >= 0 || ForwardOnlySource.isForwardOnly(source)) {
            return source;
        }
        var probe = new byte[1];
        var below = 0L;
        var above = FIRST_PROBE;
        while (source.read(above - 1, probe, 0, 1) > 0) {
            below = above;
            if (above > Long.MAX_VALUE / 2) {
                return source;
            }
            above *= 2;
        }
        // the size is in (below - 1, above - 1]: the byte at below - 1 exists, the one at above - 1 does not
        var low = below;
        var high = above - 1;
        while (low < high) {
            var middle = low + (high - low) / 2;
            if (source.read(middle, probe, 0, 1) > 0) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return new MeasuredSource(source, low);
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
        if (length == 0) {
            return 0;
        }
        if (position >= size) {
            return -1;
        }
        return source.read(position, buffer, offset, (int) Math.min(length, size - position));
    }

    @Override
    public boolean isLocal() {
        return LocalSource.isLocal(source);
    }

    @Override
    public boolean isLocal(long from, long to) {
        return LocalSource.isLocal(source, from, to);
    }

    @Override
    public void close() {
    }
}
