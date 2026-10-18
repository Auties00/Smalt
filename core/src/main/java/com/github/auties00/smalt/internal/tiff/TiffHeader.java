package com.github.auties00.smalt.internal.tiff;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.io.ByteArrays;

/**
 * Holds the header of a TIFF structure: its byte order, whether it is a BigTIFF, and where its
 * first image file directory is.
 *
 * {@snippet :
 * classic: "II" or "MM" | 42 (16 bits) | first IFD offset (32 bits)
 * BigTIFF: "II" or "MM" | 43 (16 bits) | 8 (16 bits) | 0 (16 bits) | first IFD offset (64 bits)
 * }
 *
 * <p>Instances are immutable and thread-safe.
 *
 * @param little whether the structure is little endian ({@code II})
 * @param big whether the structure is a BigTIFF, with 8-byte offsets and 20-byte entries
 * @param firstIfd the offset of the first directory, relative to the start of the header
 */
public record TiffHeader(boolean little, boolean big, long firstIfd) {
    /**
     * The size of a classic header.
     */
    public static final int SIZE = 8;

    /**
     * The size of a BigTIFF header.
     */
    public static final int BIG_SIZE = 16;

    /**
     * Reads the header at a position of an input.
     *
     * @param input the bytes the header is read from
     * @param position the absolute position of the byte order mark
     * @return the header, or {@code null} when the bytes are not a TIFF header (a missing or
     * truncated byte order mark, version or offset)
     * @throws MediaException.Unsupported if the header is a BigTIFF whose offsets are not 8 bytes
     */
    public static TiffHeader read(TiffInput input, long position) {
        var header = new byte[BIG_SIZE];
        var available = input.readAvailable(position, header, 0, BIG_SIZE);
        return available < SIZE ? null : parse(header, 0, available);
    }

    /**
     * Parses the header at an offset of an array.
     *
     * @param data the bytes
     * @param offset the index of the byte order mark
     * @param length the number of bytes available from {@code offset}
     * @return the header, or {@code null} when the bytes are not a TIFF header
     * @throws MediaException.Unsupported if the header is a BigTIFF whose offsets are not 8 bytes
     */
    public static TiffHeader parse(byte[] data, int offset, int length) {
        if (offset < 0 || length < SIZE || offset + SIZE > data.length) {
            return null;
        }
        boolean little;
        if (data[offset] == 'I' && data[offset + 1] == 'I') {
            little = true;
        } else if (data[offset] == 'M' && data[offset + 1] == 'M') {
            little = false;
        } else {
            return null;
        }
        var version = ByteArrays.u16(data, offset + 2, little);
        if (version == 42) {
            return new TiffHeader(little, false, ByteArrays.u32(data, offset + 4, little));
        }
        if (version != 43 || length < BIG_SIZE || offset + BIG_SIZE > data.length) {
            return null;
        }
        var offsetSize = ByteArrays.u16(data, offset + 4, little);
        if (offsetSize != 8 || ByteArrays.u16(data, offset + 6, little) != 0) {
            throw new MediaException.Unsupported("tiff", "BigTIFF with " + offsetSize + "-byte offsets is not supported");
        }
        var first = little ? ByteArrays.i64le(data, offset + 8) : ByteArrays.i64be(data, offset + 8);
        return new TiffHeader(little, true, first);
    }

    /**
     * {@return the size of a directory entry: 12, or 20 in a BigTIFF}
     */
    public int entrySize() {
        return big ? 20 : 12;
    }

    /**
     * {@return the size of the value field of an entry: 4, or 8 in a BigTIFF}
     */
    public int fieldSize() {
        return big ? 8 : 4;
    }
}
