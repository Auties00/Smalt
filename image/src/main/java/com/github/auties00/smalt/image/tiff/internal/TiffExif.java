package com.github.auties00.smalt.image.tiff.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.tiff.TiffTypes;

import java.util.HashSet;
import java.util.Set;

/**
 * Extracts the EXIF metadata of a TIFF page as a stand-alone EXIF block: a small TIFF structure
 * whose first directory holds the page's descriptive tags and points to copies of its EXIF, GPS
 * and interoperability directories, the form JPEG {@code APP1} and PNG {@code eXIf} carry.
 *
 * <p>The block keeps the page's byte order and is always a classic (not BigTIFF) structure.
 * Entries are copied byte for byte; entries whose values cannot be read, and BigTIFF-only types,
 * are dropped, and the whole block is bounded in size. Offsets stored inside values (a maker
 * note's private directories) are not rewritten, as most EXIF writers do.
 */
public final class TiffExif {
    /**
     * The tags of the first directory copied into the block.
     */
    private static final int[] DESCRIPTIVE = {
            TiffTags.IMAGE_DESCRIPTION, TiffTags.MAKE, TiffTags.MODEL, TiffTags.ORIENTATION, TiffTags.X_RESOLUTION,
            TiffTags.Y_RESOLUTION, TiffTags.RESOLUTION_UNIT, TiffTags.SOFTWARE, TiffTags.DATE_TIME, TiffTags.ARTIST,
            TiffTags.HOST_COMPUTER, TiffTags.COPYRIGHT
    };

    /**
     * The largest block extracted, in bytes, as one JPEG {@code APP1} segment can hold.
     */
    private static final long MAX_SIZE = 65_000;

    /**
     * Prevents instantiation.
     */
    private TiffExif() {
        throw new AssertionError();
    }

    /**
     * Extracts the EXIF block of a page.
     *
     * @param page the page's directory
     * @return the block, starting with its byte-order mark, or {@code null} when the page has
     * neither an EXIF nor a GPS directory, or the block would be too large
     */
    public static byte[] extract(TiffDirectory page) {
        var file = page.file();
        var exifOffset = pointer(page, TiffTags.EXIF_IFD);
        var gpsOffset = pointer(page, TiffTags.GPS_IFD);
        if (exifOffset <= 0 && gpsOffset <= 0) {
            return null;
        }
        var root = new IfdBuilder(file.little(), false);
        for (var tag : DESCRIPTIVE) {
            copy(page, page.find(tag), root);
        }
        var seen = new HashSet<Long>();
        seen.add(page.offset());
        if (exifOffset > 0) {
            var exif = copyDirectory(file, exifOffset, seen, true);
            if (exif != null) {
                root.child(TiffTags.EXIF_IFD, exif);
            }
        }
        if (gpsOffset > 0) {
            var gps = copyDirectory(file, gpsOffset, seen, false);
            if (gps != null) {
                root.child(TiffTags.GPS_IFD, gps);
            }
        }
        if (root.size() + 8 > MAX_SIZE) {
            return null;
        }
        var body = root.write(8, 0);
        var block = new byte[8 + body.length];
        if (file.little()) {
            block[0] = 'I';
            block[1] = 'I';
            block[2] = 42;
        } else {
            block[0] = 'M';
            block[1] = 'M';
            block[3] = 42;
        }
        if (file.little()) {
            block[4] = 8;
        } else {
            block[7] = 8;
        }
        System.arraycopy(body, 0, block, 8, body.length);
        return block;
    }

    /**
     * Reads the sub-directory pointer of a tag.
     *
     * @param directory the directory holding the pointer
     * @param tag the pointer tag
     * @return the offset, or {@code 0} when absent or unreadable
     */
    private static long pointer(TiffDirectory directory, int tag) {
        try {
            return directory.longValue(tag, 0);
        } catch (MediaException.Io exception) {
            throw exception;
        } catch (MediaException exception) {
            return 0;
        }
    }

    /**
     * Copies a sub-directory and, for the EXIF directory, its interoperability directory.
     *
     * @param file the file holding the sub-directory
     * @param offset the offset of the directory
     * @param seen the offsets already copied, to stop loops
     * @param nested whether the interoperability pointer is followed
     * @return the copy, or {@code null} when the directory cannot be read
     */
    private static IfdBuilder copyDirectory(TiffFile file, long offset, Set<Long> seen, boolean nested) {
        if (!seen.add(offset)) {
            return null;
        }
        TiffDirectory directory;
        try {
            directory = file.readDirectory(offset);
        } catch (MediaException.Io exception) {
            throw exception;
        } catch (MediaException exception) {
            return null;
        }
        var copy = new IfdBuilder(file.little(), false);
        for (var index = 0; index < directory.count(); index++) {
            var tag = directory.tag(index);
            if (tag == TiffTags.INTEROPERABILITY_IFD) {
                if (nested) {
                    var child = copyDirectory(file, pointer(directory, tag), seen, false);
                    if (child != null) {
                        copy.child(tag, child);
                    }
                }
                continue;
            }
            copy(directory, index, copy);
        }
        return copy;
    }

    /**
     * Copies one entry when it can be read and fits a classic structure.
     *
     * @param directory the source directory
     * @param index the entry index, or {@code -1}
     * @param target the destination directory
     */
    private static void copy(TiffDirectory directory, int index, IfdBuilder target) {
        if (index < 0) {
            return;
        }
        var type = directory.type(index);
        var length = directory.byteLength(index);
        if (type > TiffTypes.IFD || length < 0 || length > MAX_SIZE) {
            return;
        }
        try {
            target.raw(directory.tag(index), type, directory.valueCount(index), directory.raw(index));
        } catch (MediaException.Io exception) {
            throw exception;
        } catch (MediaException exception) {
            // an unreadable entry is dropped
        }
    }
}
