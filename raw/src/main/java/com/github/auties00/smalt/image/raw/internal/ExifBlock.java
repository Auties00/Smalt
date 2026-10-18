package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.tiff.TiffTypes;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the stand-alone EXIF block of a raw file: a small TIFF structure, in the file's byte
 * order, whose first directory holds the file's descriptive tags and points to copies of its EXIF
 * and GPS directories, the form a JPEG {@code APP1} segment carries.
 *
 * <p>Entries are copied byte for byte. The maker note is left out, since the offsets inside it
 * refer to the raw file, and so are directory pointers other than the EXIF and GPS ones; an entry
 * whose value cannot be read is dropped, and the block is bounded in size.
 */
final class ExifBlock {
    /**
     * The tags of the first directory copied: description, make, model, orientation, resolution,
     * software, date, artist and copyright.
     */
    private static final int[] DESCRIPTIVE = {270, 271, 272, 274, 282, 283, 296, 305, 306, 315, 33432};

    /**
     * The largest block built, as one JPEG {@code APP1} segment holds.
     */
    private static final int MAX_SIZE = 65_000;

    /**
     * The largest value copied.
     */
    private static final int MAX_VALUE = 16_384;

    /**
     * Prevents instantiation.
     */
    private ExifBlock() {
        throw new AssertionError();
    }

    /**
     * One entry to write.
     *
     * @param tag the TIFF tag
     * @param type the TIFF type
     * @param count the value count
     * @param value the value bytes, or {@code null} for a directory pointer patched later
     */
    private record Entry(int tag, int type, long count, byte[] value) {
    }

    /**
     * Builds the block.
     *
     * @param first the first directory of the file
     * @param exif the EXIF directory, or {@code null}
     * @param gps the GPS directory, or {@code null}
     * @return the block, starting with its byte order mark, or {@code null} when there is nothing
     * to hold or the block would be too large
     */
    static byte[] build(Ifd first, Ifd exif, Ifd gps) {
        var little = first.reader().little();
        var root = new ArrayList<Entry>();
        for (var tag : DESCRIPTIVE) {
            copy(first, tag, root);
        }
        var exifEntries = exif != null ? copyAll(exif) : List.<Entry>of();
        var gpsEntries = gps != null ? copyAll(gps) : List.<Entry>of();
        if (!exifEntries.isEmpty()) {
            root.add(new Entry(TiffTree.EXIF_IFD, 4, 1, null));
        }
        if (!gpsEntries.isEmpty()) {
            root.add(new Entry(TiffTree.GPS_IFD, 4, 1, null));
        }
        if (root.isEmpty()) {
            return null;
        }
        root.sort((a, b) -> Integer.compare(a.tag, b.tag));
        var rootSize = size(root);
        var exifOffset = 8 + rootSize;
        var gpsOffset = exifOffset + (exifEntries.isEmpty() ? 0 : size(exifEntries));
        var total = gpsOffset + (gpsEntries.isEmpty() ? 0 : size(gpsEntries));
        if (total > MAX_SIZE) {
            return null;
        }
        var out = new ByteArrayOutputStream(total);
        out.write(little ? 'I' : 'M');
        out.write(little ? 'I' : 'M');
        write16(out, 42, little);
        write32(out, 8, little);
        write(out, root, 8, little, exifOffset, gpsOffset);
        if (!exifEntries.isEmpty()) {
            write(out, exifEntries, exifOffset, little, 0, 0);
        }
        if (!gpsEntries.isEmpty()) {
            write(out, gpsEntries, gpsOffset, little, 0, 0);
        }
        return out.toByteArray();
    }

    /**
     * Copies every entry of a directory other than the maker note and nested pointers.
     *
     * @param ifd the directory
     * @return the entries
     */
    private static List<Entry> copyAll(Ifd ifd) {
        var entries = new ArrayList<Entry>();
        for (var index = 0; index < ifd.count(); index++) {
            var tag = ifd.tagAt(index);
            if (tag == TiffTree.MAKER_NOTE || tag == 40965 || tag == TiffTree.EXIF_IFD || tag == TiffTree.GPS_IFD) {
                continue;
            }
            copy(ifd, tag, entries);
        }
        entries.sort((a, b) -> Integer.compare(a.tag, b.tag));
        return entries;
    }

    /**
     * Copies one entry, when present and readable.
     *
     * @param ifd the directory
     * @param tag the tag to copy
     * @param entries the entries to add to
     */
    private static void copy(Ifd ifd, int tag, List<Entry> entries) {
        var type = ifd.type(tag);
        if (type == 0 || type > 12) {
            return;
        }
        var count = ifd.valueCount(tag);
        if (count * TiffTypes.size(type) > MAX_VALUE) {
            return;
        }
        try {
            var value = ifd.bytes(tag);
            if (value != null) {
                for (var entry : entries) {
                    if (entry.tag == tag) {
                        return;
                    }
                }
                entries.add(new Entry(tag, type, count, value));
            }
        } catch (MediaException exception) {
            // an unreadable value is dropped
        }
    }

    /**
     * Returns the bytes a directory and its out-of-line values take.
     *
     * @param entries the entries of the directory
     * @return the size
     */
    private static int size(List<Entry> entries) {
        var size = 2 + 12 * entries.size() + 4;
        for (var entry : entries) {
            if (entry.value != null && entry.value.length > 4) {
                size += entry.value.length + (entry.value.length & 1);
            }
        }
        return size;
    }

    /**
     * Writes a directory followed by its out-of-line values.
     *
     * @param out the output
     * @param entries the entries of the directory, sorted by tag
     * @param offset the offset the directory is written at
     * @param little whether to write little endian
     * @param exifOffset the offset of the EXIF directory
     * @param gpsOffset the offset of the GPS directory
     */
    private static void write(ByteArrayOutputStream out, List<Entry> entries, int offset, boolean little,
                              int exifOffset, int gpsOffset) {
        write16(out, entries.size(), little);
        var values = offset + 2 + 12 * entries.size() + 4;
        for (var entry : entries) {
            write16(out, entry.tag, little);
            write16(out, entry.type, little);
            write32(out, entry.count, little);
            if (entry.value == null) {
                write32(out, entry.tag == TiffTree.EXIF_IFD ? exifOffset : gpsOffset, little);
            } else if (entry.value.length <= 4) {
                out.write(entry.value, 0, entry.value.length);
                for (var pad = entry.value.length; pad < 4; pad++) {
                    out.write(0);
                }
            } else {
                write32(out, values, little);
                values += entry.value.length + (entry.value.length & 1);
            }
        }
        write32(out, 0, little);
        for (var entry : entries) {
            if (entry.value != null && entry.value.length > 4) {
                out.write(entry.value, 0, entry.value.length);
                if ((entry.value.length & 1) != 0) {
                    out.write(0);
                }
            }
        }
    }

    /**
     * Writes a 16-bit integer.
     *
     * @param out the output
     * @param value the integer; bits above 16 are ignored
     * @param little whether to write little endian
     */
    private static void write16(ByteArrayOutputStream out, int value, boolean little) {
        if (little) {
            out.write(value);
            out.write(value >>> 8);
        } else {
            out.write(value >>> 8);
            out.write(value);
        }
    }

    /**
     * Writes a 32-bit integer.
     *
     * @param out the output
     * @param value the integer; bits above 32 are ignored
     * @param little whether to write little endian
     */
    private static void write32(ByteArrayOutputStream out, long value, boolean little) {
        if (little) {
            write16(out, (int) value & 0xFFFF, true);
            write16(out, (int) (value >>> 16) & 0xFFFF, true);
        } else {
            write16(out, (int) (value >>> 16) & 0xFFFF, false);
            write16(out, (int) value & 0xFFFF, false);
        }
    }
}
