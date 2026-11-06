package com.github.auties00.smalt.internal.io;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.io.MediaSource;

import java.util.Objects;

/**
 * Lets a parser tell a {@link MediaSource} whose bytes arrive once, front to back, which bytes it
 * will never read again, so the source keeps only what can still be read.
 *
 * <p>Such a source keeps every byte it has taken from its input, so a backward read never reaches
 * the input again. A parser that reads front to back raises the source's watermark with
 * {@link #release(long)} as it goes: the source drops what lies below the watermark and holds a
 * small window instead of the whole input. A read below the watermark fails with
 * {@link MediaException.Unsupported}.
 *
 * <p>Every method of this interface has a static counterpart taking any source, which does
 * nothing special for a source that does not implement it, so parsers call the static methods
 * unconditionally.
 *
 * @implSpec
 * {@link #release(long)} must be safe for concurrent calls with reads and must never lower the
 * watermark.
 */
public interface ForwardOnlySource {
    /**
     * Raises the watermark: tells this source that no read will start below {@code position}
     * again.
     *
     * <p>A position below the current watermark changes nothing. A position past the bytes taken
     * so far is allowed: those bytes are then delivered to the read that takes them and kept no
     * further.
     *
     * @param position the offset below which nothing is read again
     */
    void release(long position);

    /**
     * Reads up to {@code length} bytes at {@code position} that will be read only this once:
     * the watermark rises to the end of the range read.
     *
     * <p>The bytes past what this source already holds go from the input to {@code buffer}
     * without being kept, so a single read of a whole input holds no more than a small window.
     *
     * @param position the absolute offset of the first byte; not below the watermark
     * @param buffer   the destination array
     * @param offset   the index in {@code buffer} of the first byte written
     * @param length   the number of bytes wanted; non-negative
     * @return the number of bytes read, less than {@code length} only at the end of the input
     * @throws IndexOutOfBoundsException  if the {@code buffer} range is invalid or
     *                                    {@code position} is negative
     * @throws MediaException.Unsupported if {@code position} is below the watermark
     * @throws MediaException.Io          if reading the input fails
     */
    int readOnce(long position, byte[] buffer, int offset, int length);

    /**
     * Returns whether reading up to {@code end} keeps every byte this source would hold in
     * memory, so that a parser can decide whether a probe far ahead, such as a tag at the end of
     * the input, is cheap.
     *
     * @param end the offset just past the last byte the parser wants to read
     * @return {@code true} when the bytes from the watermark to {@code end} fit the memory budget
     *         of this source or are already held
     */
    boolean fitsInMemory(long end);

    /**
     * Returns whether every byte of a range can still be read: it was taken from the input and
     * this source has not dropped it, although it may lie below the watermark.
     *
     * @param from the offset of the first byte of the range
     * @param to   the offset just past its last byte
     * @return {@code true} when reading the range succeeds without reaching the input, and for an
     *         empty range
     */
    boolean keeps(long from, long to);

    /**
     * Raises the watermark of a source that reads its input once, front to back; does nothing for
     * any other source.
     *
     * @param source   the source
     * @param position the offset below which nothing is read again
     */
    static void release(MediaSource source, long position) {
        if (source instanceof ForwardOnlySource forward) {
            forward.release(position);
        }
    }

    /**
     * Reads up to {@code length} bytes that will be read only this once, as
     * {@link #readOnce(long, byte[], int, int)} does for a source that reads its input once and
     * as repeated {@link MediaSource#read(long, byte[], int, int)} calls do for any other.
     *
     * @param source   the source
     * @param position the absolute offset of the first byte
     * @param buffer   the destination array
     * @param offset   the index in {@code buffer} of the first byte written
     * @param length   the number of bytes wanted; non-negative
     * @return the number of bytes read, less than {@code length} only at the end of the input
     * @throws IndexOutOfBoundsException  if the {@code buffer} range is invalid or
     *                                    {@code position} is negative
     * @throws MediaException.Unsupported if the source reads its input once and {@code position}
     *                                    is below its watermark
     * @throws MediaException.Io          if reading fails
     */
    static int readOnce(MediaSource source, long position, byte[] buffer, int offset, int length) {
        if (source instanceof ForwardOnlySource forward) {
            return forward.readOnce(position, buffer, offset, length);
        }
        Objects.checkFromIndexSize(offset, length, buffer.length);
        var done = 0;
        while (done < length) {
            var read = source.read(position + done, buffer, offset + done, length - done);
            if (read <= 0) {
                break;
            }
            done += read;
        }
        return done;
    }

    /**
     * Returns whether reading a source up to {@code end} is cheap: always for a source with
     * random access, and for a source that reads its input once only when the bytes it would
     * hold fit its memory budget, as {@link #fitsInMemory(long)} tells.
     *
     * @param source the source
     * @param end    the offset just past the last byte the parser wants to read
     * @return {@code false} only for a source that reads its input once and would have to spill
     *         to reach {@code end}
     */
    static boolean isCheapToReach(MediaSource source, long end) {
        return !(source instanceof ForwardOnlySource forward) || forward.fitsInMemory(end);
    }

    /**
     * Returns whether a parser can read a range of a source again: always for a source with
     * random access, and for a source that reads its input once when it still keeps the range, as
     * {@link #keeps(long, long)} tells.
     *
     * @param source the source whose range is read again
     * @param from   the offset of the first byte of the range
     * @param to     the offset just past its last byte
     * @return {@code false} only for a source that reads its input once and no longer keeps, or
     *         has not yet taken, every byte of the range
     */
    static boolean keeps(MediaSource source, long from, long to) {
        return !(source instanceof ForwardOnlySource forward) || forward.keeps(from, to);
    }

    /**
     * Returns whether a source reads its input once, front to back.
     *
     * @param source the source
     * @return {@code true} when the source implements this interface
     */
    static boolean isForwardOnly(MediaSource source) {
        return source instanceof ForwardOnlySource;
    }
}
