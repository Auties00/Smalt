package com.github.auties00.smalt.container.mpeg;

import com.github.auties00.smalt.internal.header.MpegAudioHeader;
import com.github.auties00.smalt.internal.tags.Id3v2Tag;
import com.github.auties00.smalt.internal.io.ArrayBacked;
import com.github.auties00.smalt.io.MediaSource;

/**
 * Walks the frames of an MPEG audio elementary stream, resynchronizing after garbage.
 *
 * <p>The cursor keeps a reference header. A frame is accepted without further checks when its
 * header is compatible with the reference (same version, layer and sample rate, see
 * {@link MpegAudioHeader#isCompatible(int, int)}); a frame that runs past the end of the region is
 * returned cut to the end.
 * Anything else starts a resynchronization: the bytes are scanned for a valid header that begins
 * a chain of {@value #CHAIN} further compatible headers at the positions their frame sizes
 * predict, and the first header of such a chain becomes the new reference, which may change the
 * format of the stream. A chain may be shorter when it runs into the end of the region, except
 * during the initial synchronization of a stream that does not start exactly at the first byte,
 * where a full chain is required so that a stray sync word near the end of a non-MPEG file is not
 * taken for audio. ID3v2 tags met while scanning are skipped whole.
 *
 * <p>Free-format frames, whose header carries no bitrate, are sized like minimp3 does it: the
 * distance to the next compatible header, confirmed by a third compatible header after it, minus
 * the padding slot; that size then applies to every free-format frame of the stream.
 *
 * <p>Reads go through a sliding window that keeps some bytes behind the scan position, so the
 * look-ahead of a chain check never forces the window to be reloaded when the scan resumes; the
 * kept bytes move to the front of the window, so the source is read sequentially and never twice.
 * An in-memory source ({@link ArrayBacked}) is its own window: its array is read in place and
 * never written.
 */
final class FrameCursor {
    /**
     * The number of compatible headers that must follow a candidate during resynchronization.
     */
    static final int CHAIN = 3;

    /**
     * The largest frame accepted, in bytes, which bounds the free-format search.
     */
    static final int MAX_FRAME = 8192;

    /**
     * The size of the read window.
     */
    private static final int WINDOW = 256 * 1024;

    /**
     * The number of bytes kept before a reload position, larger than the look-ahead of a chain.
     */
    private static final int LOOK_BEHIND = 48 * 1024;

    /**
     * The source being read.
     */
    private final MediaSource source;

    /**
     * The offset of the first byte of the audio region.
     */
    private final long start;

    /**
     * The offset of the end of the audio region, lowered when the source ends early.
     */
    private long end;

    /**
     * The window of buffered bytes, or the array of an in-memory source.
     */
    private final byte[] window;

    /**
     * Whether {@link #window} is the whole array of an in-memory source, which is never reloaded.
     */
    private final boolean direct;

    /**
     * The source offset of {@code window[0]}.
     */
    private long windowStart;

    /**
     * The number of valid bytes in {@link #window}.
     */
    private int windowLength;

    /**
     * The offset where the next frame is expected.
     */
    private long position;

    /**
     * The header of the stream's current format, or {@code 0} before the first frame.
     */
    private int reference;

    /**
     * The size of free-format frames without padding, or {@code 0} when unknown.
     */
    private int freeFormatBytes;

    /**
     * The offset of the frame returned by the last successful {@link #next()}.
     */
    long frameOffset;

    /**
     * The header of the frame returned by the last successful {@link #next()}.
     */
    int frameHeader;

    /**
     * The size of the frame returned by the last successful {@link #next()}, padding included.
     */
    int frameLength;

    /**
     * Constructs a cursor over the region {@code [start, end)}.
     *
     * @param source the input holding the frames
     * @param start the offset of the first byte of the region
     * @param end the offset of the end of the region, or {@link Long#MAX_VALUE} when unknown
     */
    FrameCursor(MediaSource source, long start, long end) {
        this.source = source;
        this.start = start;
        this.end = end;
        this.position = start;
        var backing = ArrayBacked.of(source);
        if (backing != null) {
            this.window = backing.array();
            this.windowStart = -backing.arrayOffset();
            this.windowLength = backing.arrayOffset() + (int) source.size();
            this.direct = true;
        } else {
            this.window = new byte[WINDOW];
            this.windowStart = start;
            this.direct = false;
        }
    }

    /**
     * Constructs a cursor that continues from the state of another, reading independently.
     *
     * <p>The new cursor starts with a copy of the other's window, so it reads on from where the
     * other's buffered bytes end instead of reading them again.
     *
     * @param other the cursor to copy
     */
    FrameCursor(FrameCursor other) {
        this(other.source, other.start, other.end);
        copyFrom(other);
    }

    /**
     * Moves this cursor to a position with the scan state it had there, keeping its buffered
     * bytes; the next call to {@link #next()} continues from that position.
     *
     * @param position the offset the next frame search starts at
     * @param reference the reference header at that position, or {@code 0}
     * @param freeFormatBytes the free-format frame size at that position, or {@code 0}
     * @param end the region end at that position
     */
    void restore(long position, int reference, int freeFormatBytes, long end) {
        this.position = position;
        this.reference = reference;
        this.freeFormatBytes = freeFormatBytes;
        this.end = end;
    }

    /**
     * Moves this cursor to the state of another cursor over the same region: its position,
     * reference header, free-format size, region end, current frame and buffered bytes.
     *
     * @param other the cursor to copy, reading the same source and region
     */
    void copyFrom(FrameCursor other) {
        this.position = other.position;
        this.reference = other.reference;
        this.freeFormatBytes = other.freeFormatBytes;
        this.end = other.end;
        this.frameOffset = other.frameOffset;
        this.frameHeader = other.frameHeader;
        this.frameLength = other.frameLength;
        if (!direct) {
            System.arraycopy(other.window, 0, window, 0, other.windowLength);
            this.windowStart = other.windowStart;
            this.windowLength = other.windowLength;
        }
    }

    /**
     * {@return the offset of the end of the region, possibly lowered after the source ended early}
     */
    long end() {
        return end;
    }

    /**
     * {@return the offset where the next frame is expected}
     */
    long position() {
        return position;
    }

    /**
     * Returns the lowest offset a later scan from the position may read from the source: a
     * reload keeps {@link #LOOK_BEHIND} bytes before the offset it needs.
     *
     * @return the offset below which the source is not read again unless the cursor is restored to an earlier position
     */
    long lowestRead() {
        return Math.max(start, position - LOOK_BEHIND);
    }

    /**
     * {@return the reference header, the header of the current format, or {@code 0} before the first frame}
     */
    int reference() {
        return reference;
    }

    /**
     * {@return the free-format frame size of the stream, without padding, or {@code 0} when unknown}
     */
    int freeFormatBytes() {
        return freeFormatBytes;
    }

    /**
     * Makes {@code [offset, offset + length)} available in the window.
     *
     * @param offset the first byte needed
     * @param length the number of bytes needed, at most {@link #WINDOW} minus {@link #LOOK_BEHIND}
     * @return {@code true} when every byte is inside the region and was read
     */
    private boolean ensure(long offset, int length) {
        if (offset < start || offset + length > end) {
            return false;
        }
        if (offset >= windowStart && offset + length <= windowStart + windowLength) {
            return true;
        }
        if (direct) {
            return false;
        }
        var reload = Math.max(start, offset - LOOK_BEHIND);
        if (offset >= windowStart && offset < windowStart + windowLength && offset - windowStart < LOOK_BEHIND) {
            reload = windowStart;
        }
        var keep = 0;
        if (reload >= windowStart && reload < windowStart + windowLength) {
            // the bytes already buffered from reload on are kept, so the source is never read twice
            keep = (int) (windowStart + windowLength - reload);
            System.arraycopy(window, (int) (reload - windowStart), window, 0, keep);
        }
        windowStart = reload;
        windowLength = keep;
        var wanted = (int) Math.min(WINDOW, end - reload);
        while (windowLength < wanted) {
            var read = source.read(reload + windowLength, window, windowLength, wanted - windowLength);
            if (read <= 0) {
                end = reload + windowLength;
                break;
            }
            windowLength += read;
        }
        return offset + length <= windowStart + windowLength;
    }

    /**
     * Returns the byte at an offset.
     *
     * @param offset the offset, which must have been made available by {@link #ensure(long, int)}
     * @return the byte
     */
    private byte byteAt(long offset) {
        return window[(int) (offset - windowStart)];
    }

    /**
     * Reads a header.
     *
     * @param offset the offset of the first header byte
     * @return the header, or {@code 0} (never a valid header) when fewer than four bytes remain
     */
    int header(long offset) {
        if (!ensure(offset, MpegAudioHeader.SIZE)) {
            return 0;
        }
        return MpegAudioHeader.read(window, (int) (offset - windowStart));
    }

    /**
     * Copies bytes of the region into an array.
     *
     * @param offset the offset of the first byte
     * @param target the destination
     * @param length the number of bytes, at most {@link #MAX_FRAME}
     * @return {@code true} when every byte was copied
     */
    boolean copy(long offset, byte[] target, int length) {
        if (!ensure(offset, length)) {
            return false;
        }
        System.arraycopy(window, (int) (offset - windowStart), target, 0, length);
        return true;
    }

    /**
     * Returns the size of a frame, padding included.
     *
     * @param header the frame header
     * @return the size, or {@code 0} for a free-format frame of unknown size
     */
    private int size(int header) {
        var bytes = MpegAudioHeader.frameBytes(header, freeFormatBytes);
        return bytes == 0 ? 0 : bytes + MpegAudioHeader.padding(header);
    }

    /**
     * Advances to the next frame and records it in {@link #frameOffset}, {@link #frameHeader} and
     * {@link #frameLength}.
     *
     * @return {@code false} at the end of the stream
     */
    boolean next() {
        while (position + MpegAudioHeader.SIZE <= end) {
            var header = header(position);
            if (reference != 0 && MpegAudioHeader.isCompatible(reference, header)) {
                var length = size(header);
                if (length > 0 && length <= MAX_FRAME) {
                    if (position + length > end) {
                        length = (int) (end - position);
                    }
                    frameOffset = position;
                    frameHeader = header;
                    frameLength = length;
                    position += length;
                    return true;
                }
            }
            var found = resync(position, reference != 0);
            if (found < 0) {
                position = end;
                return false;
            }
            position = found;
        }
        return false;
    }

    /**
     * Returns the size of a tag starting at an offset, if one does.
     *
     * @param offset the candidate offset
     * @return the tag size, or {@code -1}
     */
    private long tagAt(long offset) {
        if (!ensure(offset, Id3v2Tag.HEADER_SIZE)) {
            return -1;
        }
        return Id3v2Tag.size(window, (int) (offset - windowStart));
    }

    /**
     * Scans for the first header that starts a chain of compatible frames and makes it the
     * reference.
     *
     * @param from the offset where the scan starts
     * @param midStream whether the stream already produced frames, which lets a chain end at the end of the region
     * anywhere
     * @return the offset of the chain's first frame, or {@code -1} when none exists
     */
    private long resync(long from, boolean midStream) {
        var limit = midStream ? end : Math.min(end, start + MpegAudioDemuxer.INITIAL_SYNC_WINDOW);
        for (var p = from; p + MpegAudioHeader.SIZE <= limit; p++) {
            if (!ensure(p, MpegAudioHeader.SIZE)) {
                return -1;
            }
            var first = byteAt(p);
            if (first == 'I') {
                var tag = tagAt(p);
                if (tag > 0) {
                    p += tag - 1;
                    continue;
                }
                continue;
            }
            if (first != (byte) 0xFF || (byteAt(p + 1) & 0xE0) != 0xE0) {
                continue;
            }
            var header = MpegAudioHeader.read(window, (int) (p - windowStart));
            if (!MpegAudioHeader.isValid(header)) {
                continue;
            }
            var freeFormat = 0;
            var length = MpegAudioHeader.frameBytes(header, 0);
            if (length == 0) {
                freeFormat = freeFormatSize(p, header);
                if (freeFormat == 0) {
                    continue;
                }
                length = freeFormat;
            }
            length += MpegAudioHeader.padding(header);
            if (chain(p, header, length, freeFormat, midStream || p == start)) {
                reference = header;
                if (freeFormat != 0) {
                    freeFormatBytes = freeFormat;
                } else if (!MpegAudioHeader.isFreeFormat(header)) {
                    freeFormatBytes = 0;
                }
                return p;
            }
        }
        return -1;
    }

    /**
     * Checks that {@value #CHAIN} compatible headers follow a candidate.
     *
     * @param offset the candidate offset
     * @param header the candidate header
     * @param length the candidate frame size, padding included
     * @param freeFormat the free-format size to assume, or {@code 0}
     * @param shortOk whether a chain cut short by the end of the region is acceptable
     * @return {@code true} when the chain holds
     */
    private boolean chain(long offset, int header, int length, int freeFormat, boolean shortOk) {
        if (offset + length > end) {
            return false;
        }
        var next = offset + length;
        for (var count = 0; count < CHAIN; count++) {
            if (next + MpegAudioHeader.SIZE > end) {
                return shortOk;
            }
            var candidate = header(next);
            if (!MpegAudioHeader.isCompatible(header, candidate)) {
                return false;
            }
            var bytes = MpegAudioHeader.frameBytes(candidate, freeFormat);
            if (bytes == 0) {
                return false;
            }
            next += bytes + MpegAudioHeader.padding(candidate);
            if (next > end) {
                return shortOk;
            }
        }
        return true;
    }

    /**
     * Finds the size of a free-format frame: the distance to the next compatible header that is
     * itself followed by a compatible header.
     *
     * @param offset the frame offset
     * @param header the frame header
     * @return the size without padding, or {@code 0} when none is found
     */
    private int freeFormatSize(long offset, int header) {
        for (var k = MpegAudioHeader.SIZE; k < MAX_FRAME && offset + 2L * k < end - MpegAudioHeader.SIZE; k++) {
            var candidate = header(offset + k);
            if (!MpegAudioHeader.isCompatible(header, candidate)) {
                continue;
            }
            var bytes = k - MpegAudioHeader.padding(header);
            var nextBytes = bytes + MpegAudioHeader.padding(candidate);
            if (bytes <= 0 || offset + k + nextBytes + MpegAudioHeader.SIZE > end
                    || !MpegAudioHeader.isCompatible(header, header(offset + k + nextBytes))) {
                continue;
            }
            return bytes;
        }
        return 0;
    }
}
