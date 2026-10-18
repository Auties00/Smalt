package com.github.auties00.smalt.image.tiff.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.io.SourceBytes;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.tiff.Ifd;
import com.github.auties00.smalt.internal.tiff.TiffHeader;
import com.github.auties00.smalt.internal.tiff.TiffInput;
import com.github.auties00.smalt.io.MediaSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

/**
 * Reads the structure of a TIFF or BigTIFF file: its header and the chain of image file
 * directories that make up its pages.
 *
 * <p>The chain is followed from the header's first directory through each directory's next
 * pointer. A loop, a pointer past the end of the file or a directory that cannot be read ends the
 * chain after the pages read so far; only a first directory that cannot be read is malformed. The
 * page count is checked against {@link MediaLimits#maxFrames()}. Instances are not thread-safe.
 */
public final class TiffFile {
    /**
     * The input.
     */
    private final MediaSource source;

    /**
     * The input as the directory reader sees it.
     */
    private final TiffInput input;

    /**
     * The size of the input in bytes, or -1 when unknown.
     */
    private final long size;

    /**
     * The header.
     */
    private final TiffHeader header;

    /**
     * The limits bounding every allocation.
     */
    private final MediaLimits limits;

    /**
     * The directories of the main chain, one per page.
     */
    private final List<TiffDirectory> pages;

    /**
     * Reads the header and the directory chain.
     *
     * @param source the input
     * @param limits the bounds of every allocation and of the page count
     * @throws MediaException.Malformed if the header or the first directory is invalid
     * @throws MediaException.Unsupported if the BigTIFF offset size is not 8
     * @throws MediaException.LimitExceeded if the page count exceeds the frame limit
     */
    private TiffFile(MediaSource source, MediaLimits limits) {
        this.source = source;
        this.input = TiffInput.of(source);
        this.size = source.size();
        this.limits = limits;
        this.header = readHeader();
        var chain = new ArrayList<TiffDirectory>();
        var seen = new HashSet<Long>();
        var offset = header.firstIfd();
        while (offset != 0 && seen.add(offset)) {
            TiffDirectory directory;
            try {
                directory = readDirectory(offset);
            } catch (MediaException.Malformed exception) {
                if (chain.isEmpty()) {
                    throw exception;
                }
                break;
            }
            chain.add(directory);
            LimitChecks.checkFrames(limits, chain.size());
            offset = directory.next();
        }
        if (chain.isEmpty()) {
            throw new MediaException.Malformed("the TIFF file has no image file directory");
        }
        this.pages = List.copyOf(chain);
    }

    /**
     * Reads the header and the directory chain of a TIFF or BigTIFF file.
     *
     * @param source the input
     * @param limits the bounds of every allocation and of the page count
     * @return the file structure
     * @throws MediaException.Malformed if the header or the first directory is invalid
     * @throws MediaException.Unsupported if the BigTIFF offset size is not 8
     * @throws MediaException.LimitExceeded if the page count exceeds the frame limit
     * @throws MediaException.Io if reading the source fails
     */
    public static TiffFile open(MediaSource source, MediaLimits limits) {
        return new TiffFile(source, limits);
    }

    /**
     * Reads the header, telling apart the ways it can be invalid.
     *
     * @return the header
     * @throws MediaException.Malformed if the header is truncated or invalid
     * @throws MediaException.Unsupported if the BigTIFF offset size is not 8
     */
    private TiffHeader readHeader() {
        var bytes = new byte[TiffHeader.BIG_SIZE];
        var available = readAvailable(0, bytes, 0, bytes.length);
        if (available < TiffHeader.SIZE) {
            throw new MediaException.Malformed("truncated TIFF header");
        }
        if ((bytes[0] != 'I' || bytes[1] != 'I') && (bytes[0] != 'M' || bytes[1] != 'M')) {
            throw new MediaException.Malformed("invalid TIFF byte order mark");
        }
        var header = TiffHeader.parse(bytes, 0, available);
        if (header != null) {
            return header;
        }
        var little = bytes[0] == 'I';
        var version = little ? (bytes[2] & 0xFF) | (bytes[3] & 0xFF) << 8 : (bytes[2] & 0xFF) << 8 | (bytes[3] & 0xFF);
        throw new MediaException.Malformed(version == 43 ? "truncated BigTIFF header" : "invalid TIFF version " + version);
    }

    /**
     * {@return the directories of the main chain, at least one}
     */
    public List<TiffDirectory> pages() {
        return pages;
    }

    /**
     * {@return whether multi-byte values are little-endian, as in {@code II} files}
     */
    public boolean little() {
        return header.little();
    }

    /**
     * {@return the limits bounding every allocation}
     */
    public MediaLimits limits() {
        return limits;
    }

    /**
     * {@return the input}
     */
    public MediaSource source() {
        return source;
    }

    /**
     * {@return the size of the input in bytes, or -1 when unknown}
     */
    public long size() {
        return size;
    }

    /**
     * Reads the directory at an offset.
     *
     * @param offset the absolute offset of the directory's entry count
     * @return the directory
     * @throws MediaException.Malformed if the directory overlaps the header, lies past the end of
     * the file, declares more than 65536 entries or is truncated
     */
    public TiffDirectory readDirectory(long offset) {
        if (offset < TiffHeader.SIZE) {
            throw new MediaException.Malformed("TIFF directory offset " + offset + " is out of bounds");
        }
        return new TiffDirectory(this, Ifd.read(input, 0, header, offset, Ifd.Policy.STRICT));
    }

    /**
     * Reads up to {@code length} bytes at a position, stopping early only at the end of the
     * source.
     *
     * @param position the absolute offset of the first byte; a negative position reads nothing
     * @param target the destination
     * @param offset the index of the first destination byte
     * @param length the number of bytes wanted
     * @return the number of bytes read
     */
    public int readAvailable(long position, byte[] target, int offset, int length) {
        return SourceBytes.readAvailable(source, position, target, offset, length);
    }

    /**
     * Reads a block of bytes, checking its size against the limits first.
     *
     * @param position the absolute offset of the block
     * @param length the number of bytes
     * @return the bytes; shorter than {@code length} when the source ends early
     * @throws MediaException.Malformed if the position or the length is negative
     * @throws MediaException.LimitExceeded if the block exceeds the byte limit
     */
    public byte[] readBlock(long position, long length) {
        if (length < 0 || position < 0) {
            throw new MediaException.Malformed("invalid TIFF data block at " + position + " of " + length + " bytes");
        }
        var available = size < 0 ? length : Math.max(0, Math.min(length, size - position));
        LimitChecks.checkAllocation(limits, available);
        var block = new byte[(int) available];
        var read = readAvailable(position, block, 0, block.length);
        return read == block.length ? block : Arrays.copyOf(block, read);
    }
}
