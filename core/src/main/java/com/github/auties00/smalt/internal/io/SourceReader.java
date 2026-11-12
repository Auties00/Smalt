package com.github.auties00.smalt.internal.io;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.io.MediaSource;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Objects;

/**
 * Reads a {@link MediaSource} sequentially through a buffer, with seeking, as container parsers
 * need.
 *
 * <p>Every multi-byte read comes in big-endian and little-endian flavors and fails with
 * {@link MediaException.Malformed} when the source ends before the value is complete. A reader is
 * not thread-safe; parsers that read a source concurrently use one reader each.
 *
 * @implNote This implementation reads an in-memory source ({@link ArrayBacked}) in place: the
 * buffer becomes a window over the source's array, and every refill only moves the window's
 * bounds exactly as a copy of that many bytes would have, so positions, short reads and
 * end-of-input failures are the same as through a copied buffer while no byte is copied twice.
 * A source behind a native memory segment ({@link SegmentBacked}) is buffered in small refills
 * for the fixed-size reads, while {@link #readFully(byte[], int, int)} copies straight from the
 * segment with one bulk copy, so a payload is copied once, as from an array.
 */
public final class SourceReader {
    /**
     * The default buffer size in bytes.
     */
    private static final int DEFAULT_BUFFER_SIZE = 64 * 1024;

    /**
     * The largest refill of the buffer from a segment-backed source, which keeps the bytes copied
     * into the buffer and then skipped by a direct payload copy few.
     */
    private static final int SEGMENT_REFILL = 4096;

    /**
     * The source being read.
     */
    private final MediaSource source;

    /**
     * The array holding the buffered bytes: the reader's own buffer, or the array of an
     * in-memory source.
     */
    private final byte[] data;

    /**
     * The index in {@link #data} of buffer index zero; always zero for the reader's own buffer.
     */
    private int base;

    /**
     * The buffer capacity in bytes.
     */
    private final int capacity;

    /**
     * The array behind an in-memory source, or {@code null} when the reader copies into a buffer
     * of its own.
     */
    private final ArrayBacked backing;

    /**
     * The size of an in-memory source.
     */
    private final long backingSize;

    /**
     * The segment behind a segment-backed source, or {@code null}.
     */
    private final MemorySegment segment;

    /**
     * The source offset of buffer index zero.
     */
    private long bufferStart;

    /**
     * The number of valid bytes in the buffer.
     */
    private int bufferLength;

    /**
     * The index in the buffer of the next byte to read, which is
     * {@code data[base + index]}.
     */
    private int index;

    /**
     * Constructs a reader positioned at offset zero with the default buffer size.
     *
     * @param source the source to read
     * @throws NullPointerException if {@code source} is {@code null}
     */
    public SourceReader(MediaSource source) {
        this(source, DEFAULT_BUFFER_SIZE);
    }

    /**
     * Constructs a reader positioned at offset zero.
     *
     * @param source the source to read
     * @param bufferSize the buffer size in bytes; at least 16
     * @throws NullPointerException if {@code source} is {@code null}
     * @throws IllegalArgumentException if {@code bufferSize} is below 16
     */
    public SourceReader(MediaSource source, int bufferSize) {
        this.source = Objects.requireNonNull(source, "source");
        if (bufferSize < 16) {
            throw new IllegalArgumentException("buffer size " + bufferSize + " is below 16");
        }
        this.capacity = bufferSize;
        this.backing = ArrayBacked.of(source);
        var segmentBacked = SegmentBacked.of(source);
        this.segment = segmentBacked != null ? segmentBacked.segment() : null;
        if (backing != null) {
            this.data = backing.array();
            this.backingSize = source.size();
            rebase();
        } else {
            this.data = new byte[bufferSize];
            this.backingSize = 0;
        }
    }

    /**
     * Points buffer index zero of an in-memory source at {@link #bufferStart}.
     */
    private void rebase() {
        if (backing != null && bufferStart <= backingSize) {
            base = backing.arrayOffset() + (int) bufferStart;
        }
    }

    /**
     * Refills the buffer from its current length, as a source read of up to {@code length}
     * bytes at {@code bufferStart + bufferLength} would.
     *
     * @param length the free capacity
     * @return the number of bytes added, {@code 0} when {@code length} is {@code 0}, or {@code -1} at the end of the
     * input
     */
    private int refill(int length) {
        var position = bufferStart + bufferLength;
        if (backing == null) {
            return source.read(position, data, bufferLength, segment != null ? Math.min(length, SEGMENT_REFILL) : length);
        }
        if (position >= backingSize) {
            return length == 0 ? 0 : -1;
        }
        return (int) Math.min(length, backingSize - position);
    }

    /**
     * Returns the source being read.
     *
     * @return the source
     */
    public MediaSource source() {
        return source;
    }

    /**
     * Returns the total size of the source.
     *
     * @return the size in bytes, or {@code -1} when unknown
     */
    public long size() {
        return source.size();
    }

    /**
     * Returns the offset of the next byte to read.
     *
     * @return the absolute position
     */
    public long position() {
        return bufferStart + index;
    }

    /**
     * Returns whether the buffer holds every byte of a range, so that reading the range takes nothing from the
     * source; a parser over a source that reads its input once can still seek back into such a range.
     *
     * @param from the offset of the first byte of the range
     * @param to the offset just past its last byte
     * @return {@code true} when the buffer holds the range
     */
    public boolean buffers(long from, long to) {
        return from >= bufferStart && to <= bufferStart + bufferLength;
    }

    /**
     * Moves to an absolute offset; moving past the end is allowed and makes the next read fail.
     *
     * @param position the new absolute position; non-negative
     * @throws IllegalArgumentException if {@code position} is negative
     */
    public void seek(long position) {
        if (position < 0) {
            throw new IllegalArgumentException("negative position " + position);
        }
        if (position >= bufferStart && position <= bufferStart + bufferLength) {
            index = (int) (position - bufferStart);
            return;
        }
        bufferStart = position;
        bufferLength = 0;
        index = 0;
        rebase();
    }

    /**
     * Advances the position.
     *
     * @param bytes the number of bytes to skip; non-negative
     * @throws IllegalArgumentException if {@code bytes} is negative
     */
    public void skip(long bytes) {
        if (bytes < 0) {
            throw new IllegalArgumentException("negative skip " + bytes);
        }
        seek(position() + bytes);
    }

    /**
     * Returns the number of bytes between the position and the end of the source.
     *
     * @return the remaining bytes, or {@link Long#MAX_VALUE} when the size is unknown
     */
    public long remaining() {
        var size = source.size();
        return size < 0 ? Long.MAX_VALUE : Math.max(0, size - position());
    }

    /**
     * Returns the number of bytes between the position and the end of the source, up to a
     * bound, reading ahead when the size of the source is unknown.
     *
     * <p>For a source of known size the result is exact. For a source of unknown size the bytes
     * are looked for: {@code atMost} is returned when that many remain, and a smaller count, exact
     * when {@code atMost} fits the buffer, when they do not.
     *
     * @param atMost the bound; non-negative
     * @return the remaining bytes, at most {@code atMost}
     */
    public long remaining(long atMost) {
        var size = source.size();
        if (size >= 0) {
            return Math.min(atMost, Math.max(0, size - position()));
        }
        if (atMost <= 0) {
            return 0;
        }
        if (atMost <= capacity) {
            tryFill((int) atMost);
            return Math.max(0, Math.min(atMost, bufferLength - index));
        }
        if (source.read(position() + atMost - 1, new byte[1], 0, 1) > 0) {
            return atMost;
        }
        tryFill(capacity);
        return Math.max(0, Math.min(atMost - 1, bufferLength - index));
    }

    /**
     * Returns whether the position is at or past the end of the source.
     *
     * @return {@code true} when no byte can be read
     */
    public boolean atEnd() {
        return !tryFill(1);
    }

    /**
     * Buffers at least {@code needed} bytes from the position, reading as much as fits.
     *
     * @param needed the number of bytes required, at most the buffer size
     * @return {@code true} when that many bytes are available, {@code false} at the end of input
     */
    private boolean tryFill(int needed) {
        var available = bufferLength - index;
        if (available >= needed) {
            return true;
        }
        if (available > 0 && index > 0 && backing == null) {
            System.arraycopy(data, index, data, 0, available);
        }
        bufferStart += index;
        index = 0;
        bufferLength = Math.max(available, 0);
        rebase();
        while (bufferLength < needed) {
            var read = refill(capacity - bufferLength);
            if (read <= 0) {
                return false;
            }
            bufferLength += read;
        }
        return true;
    }

    /**
     * Buffers exactly {@code needed} bytes or fails.
     *
     * @param needed the number of bytes required, at most the buffer size
     * @throws MediaException.Malformed if the source ends first
     */
    private void fill(int needed) {
        if (!tryFill(needed)) {
            throw new MediaException.Malformed("unexpected end of input at offset " + position());
        }
    }

    /**
     * Reads an unsigned byte.
     *
     * @return the value, from 0 to 255
     * @throws MediaException.Malformed if the source ends first
     */
    public int u8() {
        if (index >= bufferLength) {
            fill(1);
        }
        return data[base + index++] & 0xFF;
    }

    /**
     * Reads an unsigned big-endian 16-bit value.
     *
     * @return the value
     * @throws MediaException.Malformed if the source ends first
     */
    public int u16be() {
        fill(2);
        var at = base + index;
        var value = ((data[at] & 0xFF) << 8) | (data[at + 1] & 0xFF);
        index += 2;
        return value;
    }

    /**
     * Reads an unsigned little-endian 16-bit value.
     *
     * @return the value
     * @throws MediaException.Malformed if the source ends first
     */
    public int u16le() {
        fill(2);
        var at = base + index;
        var value = (data[at] & 0xFF) | ((data[at + 1] & 0xFF) << 8);
        index += 2;
        return value;
    }

    /**
     * Reads an unsigned big-endian 24-bit value.
     *
     * @return the value
     * @throws MediaException.Malformed if the source ends first
     */
    public int u24be() {
        fill(3);
        var at = base + index;
        var value = ((data[at] & 0xFF) << 16) | ((data[at + 1] & 0xFF) << 8) | (data[at + 2] & 0xFF);
        index += 3;
        return value;
    }

    /**
     * Reads an unsigned little-endian 24-bit value.
     *
     * @return the value
     * @throws MediaException.Malformed if the source ends first
     */
    public int u24le() {
        fill(3);
        var at = base + index;
        var value = (data[at] & 0xFF) | ((data[at + 1] & 0xFF) << 8) | ((data[at + 2] & 0xFF) << 16);
        index += 3;
        return value;
    }

    /**
     * Reads an unsigned big-endian 32-bit value.
     *
     * @return the value, from 0 to {@code 2^32 - 1}
     * @throws MediaException.Malformed if the source ends first
     */
    public long u32be() {
        fill(4);
        var at = base + index;
        var value = ((long) (data[at] & 0xFF) << 24) | ((data[at + 1] & 0xFF) << 16)
                | ((data[at + 2] & 0xFF) << 8) | (data[at + 3] & 0xFF);
        index += 4;
        return value;
    }

    /**
     * Reads an unsigned little-endian 32-bit value.
     *
     * @return the value, from 0 to {@code 2^32 - 1}
     * @throws MediaException.Malformed if the source ends first
     */
    public long u32le() {
        fill(4);
        var at = base + index;
        var value = (data[at] & 0xFF) | ((data[at + 1] & 0xFF) << 8)
                | ((data[at + 2] & 0xFF) << 16) | ((long) (data[at + 3] & 0xFF) << 24);
        index += 4;
        return value;
    }

    /**
     * Reads a signed little-endian 32-bit value.
     *
     * @return the value
     * @throws MediaException.Malformed if the source ends first
     */
    public int s32le() {
        return (int) u32le();
    }

    /**
     * Reads a big-endian 64-bit value.
     *
     * @return the value as a two's-complement {@code long}
     * @throws MediaException.Malformed if the source ends first
     */
    public long u64be() {
        return (u32be() << 32) | u32be();
    }

    /**
     * Reads a little-endian 64-bit value.
     *
     * @return the value as a two's-complement {@code long}
     * @throws MediaException.Malformed if the source ends first
     */
    public long u64le() {
        var low = u32le();
        return (u32le() << 32) | low;
    }

    /**
     * Reads a four-character code such as an MP4 box type.
     *
     * @return the code as a big-endian 32-bit value
     * @throws MediaException.Malformed if the source ends first
     */
    public int fourCc() {
        return (int) u32be();
    }

    /**
     * Reads bytes into an array.
     *
     * @param target the destination
     * @param offset the index of the first byte written
     * @param length the number of bytes to read
     * @throws IndexOutOfBoundsException if the range is invalid
     * @throws MediaException.Malformed if the source ends first
     */
    public void readFully(byte[] target, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, target.length);
        if (segment != null) {
            var position = position();
            if (length > 0 && length > segment.byteSize() - position) {
                throw new MediaException.Malformed("unexpected end of input at offset " + Math.min(segment.byteSize(), position)
                        + " while reading " + length + " bytes");
            }
            MemorySegment.copy(segment, ValueLayout.JAVA_BYTE, position, target, offset, length);
            seek(position + length);
            return;
        }
        var buffered = Math.min(length, bufferLength - index);
        if (buffered > 0) {
            System.arraycopy(data, base + index, target, offset, buffered);
            index += buffered;
        }
        if (buffered < length) {
            var rest = length - buffered;
            source.readFully(position(), target, offset + buffered, rest);
            seek(position() + rest);
        }
    }

    /**
     * Reads bytes into a new array after checking the count against a bound.
     *
     * @param length the number of bytes to read; non-negative
     * @param maxBytes the largest length the caller accepts
     * @return the bytes
     * @throws MediaException.Malformed if {@code length} is negative, exceeds the remaining input, or the source ends
     * first
     * @throws MediaException.LimitExceeded if {@code length} exceeds {@code maxBytes}
     */
    public byte[] bytes(long length, long maxBytes) {
        if (length < 0) {
            throw new MediaException.Malformed("negative length " + length);
        }
        if (length > maxBytes || length > Integer.MAX_VALUE - 16) {
            throw new MediaException.LimitExceeded("field of " + length + " bytes exceeds the " + maxBytes + " byte limit");
        }
        if (length > remaining()) {
            throw new MediaException.Malformed("field of " + length + " bytes at offset " + position()
                    + " runs past the end of the input");
        }
        var result = new byte[(int) length];
        readFully(result, 0, result.length);
        return result;
    }
}
