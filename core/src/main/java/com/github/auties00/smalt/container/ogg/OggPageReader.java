package com.github.auties00.smalt.container.ogg;

import com.github.auties00.smalt.internal.io.ArrayBacked;
import com.github.auties00.smalt.io.MediaSource;

import java.util.Arrays;

/**
 * Reads the pages of an Ogg physical stream in file order, skipping damage the way libogg does.
 *
 * <p>A page is accepted only when its capture pattern, version and lacing are consistent, it is
 * complete, and its CRC matches. Anything else, including garbage between pages, a page with a
 * bad CRC, and a page cut short by the end of the input, is skipped by searching for the next
 * {@code "OggS"} capture pattern one byte after the rejected position, exactly as
 * {@code ogg_sync_pageseek} does. The fields of the last accepted page are exposed directly and
 * stay valid until the next call to {@link #next()}; the page body is a view into the reader's
 * buffer. An in-memory source ({@link ArrayBacked}) is its own buffer: its array is parsed in
 * place and never written.
 *
 * <p>A reader can record the offsets of the pages it accepts ({@link #recordPages()}), and a
 * reader over the same bytes can be told to trust them ({@link #trustPages(long[], int)}): a
 * trusted page still passes every structural check but skips the CRC, whose outcome is a pure
 * function of the bytes at that offset and is therefore already known.
 *
 * @implNote This implementation follows libogg's {@code ogg_sync_pageseek} (BSD-3) for the resynchronization rule.
 */
final class OggPageReader {
    /**
     * The size of the fixed page header.
     */
    static final int HEADER_SIZE = 27;

    /**
     * The largest possible page: the fixed header, 255 lacing values and 255 segments of 255
     * bytes.
     */
    static final int MAX_PAGE_SIZE = HEADER_SIZE + 255 + 255 * 255;

    /**
     * The header-type flag of a page that continues a packet from the previous page.
     */
    static final int FLAG_CONTINUED = 0x01;

    /**
     * The header-type flag of the first page of a logical stream.
     */
    static final int FLAG_BOS = 0x02;

    /**
     * The header-type flag of the last page of a logical stream.
     */
    static final int FLAG_EOS = 0x04;

    /**
     * The smallest page whose offset {@link #recordPages()} records.
     */
    static final int MIN_RECORDED_PAGE = 512;

    /**
     * The largest number of page offsets {@link #recordPages()} records.
     */
    static final int MAX_RECORDED_PAGES = 1 << 17;

    /**
     * The source read.
     */
    private final MediaSource source;

    /**
     * The buffered bytes, or the array of an in-memory source.
     */
    private final byte[] buffer;

    /**
     * Whether {@link #buffer} is the whole array of an in-memory source, which is never
     * compacted or refilled.
     */
    private final boolean direct;

    /**
     * The source offset of {@code buffer[0]}.
     */
    private long bufferStart;

    /**
     * The number of valid bytes in {@link #buffer}.
     */
    private int bufferLength;

    /**
     * The index in {@link #buffer} of the next byte to examine.
     */
    private int index;

    /**
     * Whether the source has been read to its end.
     */
    private boolean exhausted;

    /**
     * The offset at which pages stop being searched for, {@link Long#MAX_VALUE} for none.
     */
    private final long end;

    /**
     * The source offset of the current page.
     */
    long pageOffset;

    /**
     * The header-type flags of the current page.
     */
    int flags;

    /**
     * The granule position of the current page, {@code -1} when no packet ends on it.
     */
    long granule;

    /**
     * The bitstream serial number of the current page.
     */
    int serial;

    /**
     * The page sequence number of the current page.
     */
    int sequence;

    /**
     * The number of lacing values of the current page.
     */
    int segments;

    /**
     * The lacing values of the current page.
     */
    final int[] lacing;

    /**
     * The page checksum calculator.
     */
    private final OggCrc crc;

    /**
     * The array holding the current page body, the reader's buffer.
     */
    byte[] body;

    /**
     * The index of the current page body in {@link #body}.
     */
    int bodyOffset;

    /**
     * The size of the current page body.
     */
    int bodyLength;

    /**
     * The number of bytes skipped while resynchronizing since the reader was created.
     */
    long skippedBytes;

    /**
     * The offsets of the accepted pages recorded so far, or {@code null} when not recording.
     */
    private long[] recorded;

    /**
     * The number of valid entries in {@link #recorded}.
     */
    private int recordedCount;

    /**
     * The increasing offsets of pages whose CRC is known to match, or {@code null}.
     */
    private long[] trusted;

    /**
     * The number of valid entries in {@link #trusted}.
     */
    private int trustedCount;

    /**
     * The index of the first entry of {@link #trusted} that may still be reached.
     */
    private int trustedIndex;

    /**
     * Constructs a reader positioned at an offset of a source.
     *
     * @param source the Ogg input
     * @param start the offset where reading begins
     */
    OggPageReader(MediaSource source, long start) {
        this(source, start, Long.MAX_VALUE);
    }

    /**
     * Constructs a reader of the pages starting in a range of a source.
     *
     * <p>{@link #next()} returns exactly the pages a reader without an end would return from
     * {@code start}, up to the first one starting at or after {@code end}, and then reports the end
     * of the input. Bytes past {@code end} are requested from the source only to complete a page
     * that starts before it.
     *
     * @param source the Ogg input
     * @param start the offset where reading begins
     * @param end the offset at which the search for pages stops
     */
    OggPageReader(MediaSource source, long start, long end) {
        this.end = end;
        this.source = source;
        this.lacing = new int[255];
        this.crc = new OggCrc();
        var backing = ArrayBacked.of(source);
        if (backing != null) {
            var size = (int) source.size();
            this.buffer = backing.array();
            this.bufferStart = -backing.arrayOffset();
            this.bufferLength = backing.arrayOffset() + size;
            this.index = backing.arrayOffset() + (int) Math.min(start, size);
            this.exhausted = true;
            this.direct = true;
        } else {
            this.buffer = new byte[2 * MAX_PAGE_SIZE];
            this.bufferStart = start;
            this.direct = false;
        }
    }

    /**
     * Starts recording the offsets of the pages this reader accepts from now on.
     *
     * <p>Only pages of at least {@value #MIN_RECORDED_PAGE} bytes are recorded, at most
     * {@value #MAX_RECORDED_PAGES} of them, which bounds the memory a file of tiny pages can
     * claim; the CRC of the others is simply computed again by the trusting reader.
     */
    void recordPages() {
        recorded = new long[64];
    }

    /**
     * Returns the offsets recorded since {@link #recordPages()}.
     *
     * @return the array holding the offsets, in increasing order, in its first {@link #recordedCount()} entries, or
     * {@code null} when not recording
     */
    long[] recordedPages() {
        return recorded;
    }

    /**
     * Returns the number of recorded page offsets.
     *
     * @return the count
     */
    int recordedCount() {
        return recordedCount;
    }

    /**
     * Makes this reader skip the CRC of the pages starting at the given offsets.
     *
     * @param offsets the increasing offsets of pages accepted by a reader over the same bytes
     * @param count the number of valid entries in {@code offsets}
     */
    void trustPages(long[] offsets, int count) {
        this.trusted = offsets;
        this.trustedCount = count;
        this.trustedIndex = 0;
    }

    /**
     * Returns whether the page at an offset is known to carry a matching CRC.
     *
     * @param offset the page offset; offsets passed to consecutive calls never decrease
     * @return {@code true} when the offset is trusted
     */
    private boolean isTrusted(long offset) {
        if (trusted == null) {
            return false;
        }
        var index = trustedIndex;
        while (index < trustedCount && trusted[index] < offset) {
            index++;
        }
        trustedIndex = index;
        return index < trustedCount && trusted[index] == offset;
    }

    /**
     * Records the offset of an accepted page when recording.
     *
     * @param offset the page offset
     * @param size the page size in bytes
     */
    private void remember(long offset, int size) {
        if (recorded == null || size < MIN_RECORDED_PAGE || recordedCount >= MAX_RECORDED_PAGES) {
            return;
        }
        if (recordedCount == recorded.length) {
            recorded = Arrays.copyOf(recorded, recordedCount * 2);
        }
        recorded[recordedCount++] = offset;
    }

    /**
     * Returns the source offset of the next byte the reader examines.
     *
     * @return the offset
     */
    long position() {
        return bufferStart + index;
    }

    /**
     * Moves the reader so that the next {@link #next()} searches for a page from an offset.
     *
     * <p>Buffered bytes are discarded, and the trusted page offsets are searched from their start
     * again, so the reader may move backwards.
     *
     * @param position the source offset
     */
    void reset(long position) {
        trustedIndex = 0;
        if (direct) {
            index = (int) Math.min(bufferLength, Math.max(0, position - bufferStart));
            return;
        }
        bufferStart = position;
        bufferLength = 0;
        index = 0;
        exhausted = false;
    }

    /**
     * Makes at least {@code needed} bytes available from {@link #index}.
     *
     * @param needed the number of bytes, at most the buffer size
     * @return {@code false} when the source ends first
     */
    private boolean fill(int needed) {
        var available = bufferLength - index;
        if (available >= needed) {
            return true;
        }
        if (direct) {
            return false;
        }
        if (index > 0) {
            System.arraycopy(buffer, index, buffer, 0, available);
            bufferStart += index;
            bufferLength = available;
            index = 0;
        }
        while (bufferLength < needed && !exhausted) {
            var request = buffer.length - bufferLength;
            if (end != Long.MAX_VALUE) {
                request = (int) Math.min(request, Math.max(needed - bufferLength, end - (bufferStart + bufferLength)));
            }
            var read = source.read(bufferStart + bufferLength, buffer, bufferLength, request);
            if (read <= 0) {
                exhausted = true;
                break;
            }
            bufferLength += read;
        }
        return bufferLength >= needed;
    }

    /**
     * Advances to the next valid page.
     *
     * @return {@code true} when a page was read, {@code false} at the end of the input
     */
    boolean next() {
        while (true) {
            if (bufferStart + index >= end) {
                return false;
            }
            if (!fill(HEADER_SIZE)) {
                skippedBytes += bufferLength - index;
                index = bufferLength;
                return false;
            }
            if (buffer[index] != 'O' || buffer[index + 1] != 'g' || buffer[index + 2] != 'g' || buffer[index + 3] != 'S') {
                reject();
                continue;
            }
            if (buffer[index + 4] != 0) {
                reject();
                continue;
            }
            var count = buffer[index + 26] & 0xFF;
            if (!fill(HEADER_SIZE + count)) {
                reject();
                continue;
            }
            var size = 0;
            for (var i = 0; i < count; i++) {
                var value = buffer[index + HEADER_SIZE + i] & 0xFF;
                lacing[i] = value;
                size += value;
            }
            var total = HEADER_SIZE + count + size;
            if (!fill(total)) {
                reject();
                continue;
            }
            if (!isTrusted(bufferStart + index)) {
                var stored = (buffer[index + 22] & 0xFF) | (buffer[index + 23] & 0xFF) << 8
                        | (buffer[index + 24] & 0xFF) << 16 | (buffer[index + 25] & 0xFF) << 24;
                if (crc.page(buffer, index, total) != stored) {
                    reject();
                    continue;
                }
            }
            remember(bufferStart + index, total);
            pageOffset = bufferStart + index;
            flags = buffer[index + 5] & 0xFF;
            granule = readLong(index + 6);
            serial = readInt(index + 14);
            sequence = readInt(index + 18);
            segments = count;
            body = buffer;
            bodyOffset = index + HEADER_SIZE + count;
            bodyLength = size;
            index += total;
            return true;
        }
    }

    /**
     * Skips the byte at the current position, which does not start a valid page, and moves to the
     * next candidate capture pattern.
     */
    private void reject() {
        seekCapture(index + 1);
    }

    /**
     * Moves to the first buffered {@code 'O'} at or after {@code from}, or to the end of the
     * buffered bytes when there is none.
     *
     * @param from the index where the search starts
     */
    private void seekCapture(int from) {
        var i = from;
        while (i < bufferLength && buffer[i] != 'O') {
            i++;
        }
        skippedBytes += i - index;
        index = i;
    }

    /**
     * Reads a little-endian 32-bit value from the buffer.
     *
     * @param offset the index of the first byte
     * @return the value
     */
    private int readInt(int offset) {
        return (buffer[offset] & 0xFF) | (buffer[offset + 1] & 0xFF) << 8 | (buffer[offset + 2] & 0xFF) << 16
                | (buffer[offset + 3] & 0xFF) << 24;
    }

    /**
     * Reads a little-endian 64-bit value from the buffer.
     *
     * @param offset the index of the first byte
     * @return the value
     */
    private long readLong(int offset) {
        return (readInt(offset) & 0xFFFFFFFFL) | (long) readInt(offset + 4) << 32;
    }
}
