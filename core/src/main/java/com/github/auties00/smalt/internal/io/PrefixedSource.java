package com.github.auties00.smalt.internal.io;

import com.github.auties00.smalt.io.MediaSource;

import java.util.Objects;

/**
 * Exposes a few bytes followed by a byte range of another {@link MediaSource} as a source of its
 * own, so a reader of an embedded stream whose container strips its leading header (FLAC frames
 * in a WAVE {@code data} chunk without their {@code fLaC} marker, AMR frames without their storage
 * magic) can read the restored stream without copying it.
 *
 * <p>Offsets below the prefix length read the prefix; offset {@code prefix.length + k} is offset
 * {@code start + k} of the parent. A read at or past the end of a bounded range returns {@code -1},
 * or {@code 0} when it asks for no bytes. Closing this source does not close the parent. Whether a
 * range is local, and the watermark of a parent that reads its input once, pass through to the
 * parent at the shifted offsets.
 */
public final class PrefixedSource implements MediaSource, LocalSource, ForwardOnlySource {
    /**
     * The bytes before the parent range.
     */
    private final byte[] prefix;

    /**
     * The view of the parent range.
     */
    private final SlicedSource range;

    /**
     * Constructs a view of {@code prefix} followed by {@code parent[start, start + length)}.
     *
     * @param prefix the leading bytes, copied
     * @param parent the source the range is taken from
     * @param start the parent offset of the first byte after the prefix, non-negative
     * @param length the number of parent bytes exposed, or {@code -1} to expose everything after {@code start}
     */
    public PrefixedSource(byte[] prefix, MediaSource parent, long start, long length) {
        this.prefix = prefix.clone();
        this.range = new SlicedSource(Objects.requireNonNull(parent, "parent"), start, length);
    }

    @Override
    public long size() {
        var length = range.size();
        return length < 0 ? -1 : prefix.length + length;
    }

    @Override
    public int read(long position, byte[] buffer, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, buffer.length);
        if (position < 0) {
            throw new IndexOutOfBoundsException("negative position " + position);
        }
        if (position >= prefix.length) {
            return range.read(position - prefix.length, buffer, offset, length);
        }
        var count = (int) Math.min(length, prefix.length - position);
        System.arraycopy(prefix, (int) position, buffer, offset, count);
        return count;
    }

    @Override
    public boolean isLocal() {
        return range.isLocal();
    }

    @Override
    public boolean isLocal(long from, long to) {
        return to <= prefix.length || range.isLocal(Math.max(0, from - prefix.length), to - prefix.length);
    }

    @Override
    public void release(long position) {
        if (position > prefix.length) {
            range.release(position - prefix.length);
        }
    }

    @Override
    public int readOnce(long position, byte[] buffer, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, buffer.length);
        if (position >= prefix.length) {
            return range.readOnce(position - prefix.length, buffer, offset, length);
        }
        var count = (int) Math.min(length, prefix.length - position);
        System.arraycopy(prefix, (int) position, buffer, offset, count);
        if (count == length) {
            return count;
        }
        var rest = range.readOnce(0, buffer, offset + count, length - count);
        return count + Math.max(0, rest);
    }

    @Override
    public boolean fitsInMemory(long end) {
        return end <= prefix.length || range.fitsInMemory(end - prefix.length);
    }

    @Override
    public void close() {
    }
}
