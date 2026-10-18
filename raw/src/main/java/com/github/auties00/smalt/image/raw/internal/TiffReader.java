package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.io.SourceBytes;
import com.github.auties00.smalt.internal.tiff.TiffHeader;
import com.github.auties00.smalt.internal.tiff.TiffInput;
import com.github.auties00.smalt.io.MediaSource;

/**
 * Reads the image file directories of a TIFF structure inside a source: a whole TIFF-based raw
 * file, or a structure embedded in one (a maker note with its own byte order or offset base, the
 * {@code CMT} blocks of a CR3 file, the EXIF block of a preview).
 *
 * <p>Offsets stored in the directories are relative to {@link #base()}. Directories are read with
 * the lenient policy of camera raw files: every read is bounded by the source size and by the
 * limits, so a corrupt offset raises {@link MediaException.Malformed} rather than reading out of
 * range, and an oversized value raises {@link MediaException.LimitExceeded}.
 */
public final class TiffReader {
    /**
     * The source.
     */
    private final MediaSource source;

    /**
     * The source as the TIFF directory walker reads it.
     */
    private final TiffInput input;

    /**
     * The byte order of the structure; raw formats replace the TIFF signature with their own, so
     * the header's first-directory offset is not read from it.
     */
    private final TiffHeader header;

    /**
     * The absolute offset that the directories' offsets are relative to.
     */
    private final long base;

    /**
     * The size of the source, or {@code -1} when unknown.
     */
    private final long size;

    /**
     * The resource bounds.
     */
    private final MediaLimits limits;

    /**
     * Constructs a reader.
     *
     * @param source the source, not closed by the reader
     * @param base the absolute offset offsets are relative to
     * @param little whether integers are little endian
     * @param limits the resource bounds
     */
    public TiffReader(MediaSource source, long base, boolean little, MediaLimits limits) {
        this.source = source;
        this.input = TiffInput.of(source);
        this.header = new TiffHeader(little, false, 0);
        this.base = base;
        this.size = source.size();
        this.limits = limits;
    }

    /**
     * Opens the TIFF structure at an offset of a source, reading its byte order mark; the
     * signature that follows is not checked, since raw formats use their own.
     *
     * @param source the source, not closed by the reader
     * @param base the absolute offset of the byte order mark
     * @param limits the resource bounds
     * @return the reader
     * @throws MediaException.Malformed if the byte order mark is missing
     */
    public static TiffReader open(MediaSource source, long base, MediaLimits limits) {
        var mark = new byte[2];
        source.readFully(base, mark, 0, 2);
        if (mark[0] == 'I' && mark[1] == 'I') {
            return new TiffReader(source, base, true, limits);
        }
        if (mark[0] == 'M' && mark[1] == 'M') {
            return new TiffReader(source, base, false, limits);
        }
        throw new MediaException.Malformed("missing TIFF byte order mark at offset " + base);
    }

    /**
     * Returns a reader over the same source with another base and byte order.
     *
     * @param base the absolute offset offsets are relative to
     * @param little whether integers are little endian
     * @return the reader
     */
    public TiffReader with(long base, boolean little) {
        return new TiffReader(source, base, little, limits);
    }

    /**
     * {@return the source}
     */
    public MediaSource source() {
        return source;
    }

    /**
     * {@return the absolute offset the directories' offsets are relative to}
     */
    public long base() {
        return base;
    }

    /**
     * {@return whether integers are little endian ({@code II})}
     */
    public boolean little() {
        return header.little();
    }

    /**
     * {@return the size of the source, or {@code -1} when unknown}
     */
    public long size() {
        return size;
    }

    /**
     * {@return the resource bounds}
     */
    public MediaLimits limits() {
        return limits;
    }

    /**
     * {@return the offset of the first directory relative to the base, read from the header}
     */
    public long firstDirectory() {
        return u32(base + 4);
    }

    /**
     * Reads the directory at an offset.
     *
     * <p>A directory cut off by the end of the source keeps its whole entries, and its
     * next-directory pointer reads as 0.
     *
     * @param offset the offset relative to the base
     * @return the directory
     * @throws MediaException.Malformed if the directory lies outside the source or declares more
     * entries than the lenient policy allows
     */
    public Ifd directory(long offset) {
        var directory = com.github.auties00.smalt.internal.tiff.Ifd.read(input, base, header, offset,
                com.github.auties00.smalt.internal.tiff.Ifd.Policy.LENIENT);
        return new Ifd(this, directory);
    }

    /**
     * Reads bytes at an absolute offset, failing when the source ends first.
     *
     * @param position the absolute offset
     * @param length the number of bytes
     * @return the bytes
     * @throws MediaException.Malformed if the range lies outside the source
     * @throws MediaException.LimitExceeded if the range is too large to hold in memory
     */
    public byte[] bytes(long position, long length) {
        if (length < 0 || position < 0 || (size >= 0 && (position > size || length > size - position))) {
            throw new MediaException.Malformed("range of " + length + " bytes at " + position + " is outside the file");
        }
        if (length > com.github.auties00.smalt.internal.tiff.Ifd.Policy.LENIENT.maxValueBytes()
                || length > limits.maxBytes()) {
            throw new MediaException.LimitExceeded("value of " + length + " bytes exceeds the limits");
        }
        var data = new byte[(int) length];
        source.readFully(position, data, 0, data.length);
        return data;
    }

    /**
     * Reads as many bytes as the source holds at an absolute offset, up to a count.
     *
     * @param position the absolute offset; a negative offset reads nothing
     * @param buffer the destination
     * @param offset the first index written
     * @param length the most bytes to read
     * @return the number of bytes read
     */
    public int readAvailable(long position, byte[] buffer, int offset, int length) {
        return SourceBytes.readAvailable(source, position, buffer, offset, length);
    }

    /**
     * Reads an unsigned 16-bit integer at an absolute offset.
     *
     * @param position the absolute offset
     * @return the value
     * @throws MediaException.Malformed if the source ends first
     */
    public int u16(long position) {
        var data = new byte[2];
        source.readFully(position, data, 0, 2);
        return ByteArrays.u16(data, 0, header.little());
    }

    /**
     * Reads an unsigned 32-bit integer at an absolute offset.
     *
     * @param position the absolute offset
     * @return the value
     * @throws MediaException.Malformed if the source ends first
     */
    public long u32(long position) {
        var data = new byte[4];
        source.readFully(position, data, 0, 4);
        return ByteArrays.u32(data, 0, header.little());
    }
}
