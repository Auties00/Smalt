package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.io.ByteArrays;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Walks the directories of a TIFF-based raw file and extracts what every format stores the same
 * way: the camera and shot from the first directory and the EXIF directory, the EXIF block handed
 * to callers, and the JPEG previews.
 *
 * <p>The walk visits the main chain, the {@code SubIFDs} of every directory down to a fixed depth,
 * and nothing else; it is bounded in the number of directories, and a directory that cannot be
 * read is skipped.
 */
final class TiffTree {
    /**
     * The tag of the subfile type.
     */
    static final int NEW_SUBFILE_TYPE = 254;

    /**
     * The tag of the child directories.
     */
    static final int SUB_IFDS = 330;

    /**
     * The tag of the EXIF directory.
     */
    static final int EXIF_IFD = 34665;

    /**
     * The tag of the GPS directory.
     */
    static final int GPS_IFD = 34853;

    /**
     * The tag of the offset of an embedded JPEG stream.
     */
    static final int JPEG_OFFSET = 513;

    /**
     * The tag of the length of an embedded JPEG stream.
     */
    static final int JPEG_LENGTH = 514;

    /**
     * The tag of the maker note.
     */
    static final int MAKER_NOTE = 37500;

    /**
     * The most directories visited.
     */
    private static final int MAX_DIRECTORIES = 64;

    /**
     * The deepest level of child directories visited.
     */
    private static final int MAX_DEPTH = 3;

    /**
     * The directories of the file in visiting order, the first directory first.
     */
    final List<Ifd> directories = new ArrayList<>();

    /**
     * The first directory.
     */
    final Ifd first;

    /**
     * The EXIF directory, or {@code null}.
     */
    final Ifd exif;

    /**
     * Walks the directories of a TIFF structure.
     *
     * @param reader the structure
     * @throws MediaException.Malformed if the first directory cannot be read
     */
    TiffTree(TiffReader reader) {
        this.first = reader.directory(reader.firstDirectory());
        var seen = new HashSet<Long>();
        visit(first, 0, seen);
        var next = first.next();
        while (next > 0 && directories.size() < MAX_DIRECTORIES && !seen.contains(next)) {
            Ifd chained;
            try {
                chained = reader.directory(next);
            } catch (MediaException.Malformed exception) {
                break;
            }
            visit(chained, 0, seen);
            next = chained.next();
        }
        this.exif = first.child(EXIF_IFD);
    }

    /**
     * Records a directory and its children.
     *
     * @param ifd the directory
     * @param depth its depth below the main chain
     * @param seen the offsets already visited
     */
    private void visit(Ifd ifd, int depth, HashSet<Long> seen) {
        if (directories.size() >= MAX_DIRECTORIES || !seen.add(ifd.offset())) {
            return;
        }
        directories.add(ifd);
        if (depth >= MAX_DEPTH) {
            return;
        }
        var type = ifd.type(SUB_IFDS);
        if (type != 4 && type != 13) {
            return;
        }
        var children = ifd.longs(SUB_IFDS);
        if (children == null) {
            return;
        }
        for (var child : children) {
            if (child <= 0 || directories.size() >= MAX_DIRECTORIES) {
                continue;
            }
            try {
                visit(ifd.reader().directory(child), depth + 1, seen);
            } catch (MediaException.Malformed exception) {
                // a child that cannot be read is skipped
            }
        }
    }

    /**
     * Fills the camera, the shot, the orientation, the EXIF block and the JPEG previews.
     *
     * @param file the description to fill
     */
    void describe(RawFile file) {
        file.make = first.string(271);
        file.model = first.string(272);
        file.orientation = first.intValue(274, 1);
        if (exif != null) {
            shot(exif, file);
        }
        shot(first, file);
        file.exif = ExifBlock.build(first, exif, first.child(GPS_IFD));
        for (var ifd : directories) {
            preview(ifd, file);
        }
    }

    /**
     * Reads the shot's settings from an EXIF or first directory, keeping values already found.
     *
     * @param ifd the directory
     * @param file the description to fill
     */
    static void shot(Ifd ifd, RawFile file) {
        if (file.iso == 0) {
            file.iso = Math.max(0, ifd.longValue(34855, 0));
        }
        if (file.exposureTime == 0) {
            file.exposureTime = Math.max(0, ifd.doubleValue(33434, 0));
        }
        if (file.fNumber == 0) {
            file.fNumber = Math.max(0, ifd.doubleValue(33437, 0));
        }
        if (file.focalLength == 0) {
            file.focalLength = Math.max(0, ifd.doubleValue(37386, 0));
        }
        if (file.lens == null) {
            file.lens = ifd.string(42036);
        }
        if (file.captureTime == null) {
            file.captureTime = ifd.string(36867);
        }
    }

    /**
     * Records the JPEG preview a directory holds, if any.
     *
     * @param ifd the directory
     * @param file the description to fill
     */
    static void preview(Ifd ifd, RawFile file) {
        long offset;
        long length;
        if (ifd.has(JPEG_OFFSET) && ifd.has(JPEG_LENGTH)) {
            offset = ifd.longValue(JPEG_OFFSET, 0);
            length = ifd.longValue(JPEG_LENGTH, 0);
        } else {
            var compression = ifd.intValue(TiffSamples.COMPRESSION, 1);
            var photometric = ifd.intValue(TiffSamples.PHOTOMETRIC, 6);
            if ((compression != 6 && compression != 7) || photometric == 32803 || photometric == 34892
                    || ifd.has(TiffSamples.TILE_OFFSETS)) {
                return;
            }
            var offsets = ifd.longs(TiffSamples.STRIP_OFFSETS);
            var counts = ifd.longs(TiffSamples.STRIP_BYTE_COUNTS);
            if (offsets == null || counts == null || offsets.length != 1 || counts.length != 1) {
                return;
            }
            offset = offsets[0];
            length = counts[0];
        }
        addPreview(file, ifd.reader(), ifd.reader().base() + offset, length);
    }

    /**
     * Records a JPEG preview when the bytes at an offset start a JPEG stream.
     *
     * @param file the description to fill
     * @param reader any reader over the file
     * @param offset the absolute offset of the stream
     * @param length the length of the stream
     */
    static void addPreview(RawFile file, TiffReader reader, long offset, long length) {
        addPreview(file, reader, offset, length, false);
    }

    /**
     * Records a JPEG preview when the bytes at an offset start a JPEG stream, optionally one whose
     * first byte is stored damaged.
     *
     * @param file the description to fill
     * @param reader any reader over the file
     * @param offset the absolute offset of the stream
     * @param length the length of the stream
     * @param damagedStart whether the stream's first byte is to be read as {@code 0xFF}
     */
    static void addPreview(RawFile file, TiffReader reader, long offset, long length, boolean damagedStart) {
        var size = reader.size();
        if (offset <= 0 || length < 128 || (size >= 0 && (offset > size || length > size - offset))) {
            return;
        }
        for (var preview : file.previews) {
            if (preview.offset() == offset) {
                return;
            }
        }
        var dimensions = JpegSize.read(reader, offset, length, damagedStart);
        if (dimensions != null) {
            file.previews.add(new RawFile.Preview(offset, length, dimensions[0], dimensions[1], damagedStart));
        }
    }

    /**
     * Walks the TIFF structure of the EXIF segment of an embedded JPEG stream.
     *
     * @param reader any reader over the file
     * @param offset the absolute offset of the JPEG stream
     * @param length the length of the stream
     * @return the tree, or {@code null} when the stream has no readable EXIF segment before its
     * image data
     */
    static TiffTree ofJpegExif(TiffReader reader, long offset, long length) {
        var head = new byte[(int) Math.min(length, 1 << 16)];
        var read = reader.readAvailable(offset, head, 0, head.length);
        var position = 2;
        while (position + 10 <= read && (head[position] & 0xFF) == 0xFF) {
            var marker = head[position + 1] & 0xFF;
            var size = ByteArrays.u16be(head, position + 2);
            if (marker == 0xE1 && head[position + 4] == 'E' && head[position + 5] == 'x' && head[position + 6] == 'i'
                    && head[position + 7] == 'f') {
                try {
                    return new TiffTree(TiffReader.open(reader.source(), offset + position + 10, reader.limits()));
                } catch (MediaException.Malformed exception) {
                    // a damaged EXIF segment leaves the metadata out
                    return null;
                }
            }
            if (marker == 0xDA || size < 2) {
                return null;
            }
            position += 2 + size;
        }
        return null;
    }
}
