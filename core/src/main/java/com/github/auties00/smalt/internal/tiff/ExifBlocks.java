package com.github.auties00.smalt.internal.tiff;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.internal.io.ByteArrays;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Edits EXIF blocks (TIFF structures starting with their byte-order mark) in place, for the encoders and
 * the transcoder of every artifact.
 *
 * <p>Every edit returns a new array and leaves its input unchanged, or returns the input itself when
 * the edit changes nothing (including when the block is not a readable TIFF structure). Values that stay
 * keep their offsets, so maker notes, whose internal offsets count from the start of the block, stay
 * valid; the directories and values an edit removes are zeroed (unless a directory that stays references
 * the same bytes), and removed bytes that end the block are cut off.
 *
 * <p>This class is thread-safe.
 */
public final class ExifBlocks {
    /**
     * The tag of the IFD0 entry holding the offset of the Exif directory.
     */
    public static final int EXIF_POINTER = 0x8769;

    /**
     * The tag of the IFD0 entry holding the offset of the GPS directory.
     */
    public static final int GPS_POINTER = 0x8825;

    /**
     * The tag of the Exif directory entry holding the offset of the interoperability directory.
     */
    public static final int INTEROP_POINTER = 0xA005;

    /**
     * The {@code PixelXDimension} tag of the Exif directory.
     */
    public static final int PIXEL_X_DIMENSION = 0xA002;

    /**
     * The {@code PixelYDimension} tag of the Exif directory.
     */
    public static final int PIXEL_Y_DIMENSION = 0xA003;

    /**
     * The {@code JPEGInterchangeFormat} tag of the thumbnail directory.
     */
    private static final int THUMBNAIL_OFFSET = 0x0201;

    /**
     * The {@code JPEGInterchangeFormatLength} tag of the thumbnail directory.
     */
    private static final int THUMBNAIL_LENGTH = 0x0202;

    /**
     * The {@code StripOffsets} tag.
     */
    private static final int STRIP_OFFSETS = 0x0111;

    /**
     * The {@code StripByteCounts} tag.
     */
    private static final int STRIP_BYTE_COUNTS = 0x0117;

    /**
     * The most entries a directory read here holds.
     */
    private static final int MAX_ENTRIES = 4096;

    /**
     * The field type {@code SHORT}.
     */
    private static final int SHORT = 3;

    /**
     * The field type {@code LONG}.
     */
    private static final int LONG = 4;

    /**
     * Prevents instantiation.
     */
    private ExifBlocks() {
        throw new AssertionError();
    }

    /**
     * Returns a block whose IFD0 {@code Orientation} entry holds an orientation.
     *
     * <p>An existing entry of type {@code SHORT} or {@code LONG} has its first value patched; an entry of
     * another type, or one whose values lie outside the block, is rewritten in place as one {@code SHORT}. A
     * block without the entry gets a new IFD0,
     * holding the old entries plus the orientation, appended at its end (the old table is zeroed), except
     * for {@link Orientation#NORMAL}, which an absent entry already means. A block that is not a readable
     * TIFF structure is replaced by a minimal big-endian block holding only the orientation.
     *
     * @param tiff the block
     * @param orientation the orientation to record
     * @return the edited block, or {@code tiff} when it already records the orientation
     */
    public static byte[] withOrientation(byte[] tiff, Orientation orientation) {
        var block = Block.read(tiff);
        if (block == null) {
            return minimal(orientation);
        }
        var ifd0 = block.directory(block.firstIfd);
        if (ifd0 == null) {
            return minimal(orientation);
        }
        var index = ifd0.find(TiffExif.ORIENTATION);
        var code = orientation.exifCode();
        if (index >= 0) {
            var entry = ifd0.entryOffset(index);
            var type = block.u16(entry + 2);
            var count = block.u32(entry + 4);
            var at = (type == SHORT || type == LONG) && count >= 1 ? ifd0.valuePosition(index) : -1;
            if (at >= 0) {
                var current = type == SHORT ? block.u16((int) at) : block.u32((int) at);
                if (current == code) {
                    return tiff;
                }
                var copy = tiff.clone();
                if (type == SHORT) {
                    ByteArrays.put16(copy, (int) at, code, block.little);
                } else {
                    ByteArrays.put32(copy, (int) at, code, block.little);
                }
                return copy;
            }
            var copy = tiff.clone();
            ByteArrays.put16(copy, entry + 2, SHORT, block.little);
            ByteArrays.put32(copy, entry + 4, 1, block.little);
            Arrays.fill(copy, entry + 8, entry + 12, (byte) 0);
            ByteArrays.put16(copy, entry + 8, code, block.little);
            return copy;
        }
        if (orientation == Orientation.NORMAL) {
            return tiff;
        }
        var count = ifd0.count;
        var start = (tiff.length + 1) & ~1;
        var copy = Arrays.copyOf(tiff, start + 2 + 12 * (count + 1) + 4);
        var position = start + 2;
        var inserted = false;
        for (var i = 0; i < count; i++) {
            var entry = ifd0.entryOffset(i);
            if (!inserted && block.u16(entry) > TiffExif.ORIENTATION) {
                writeShortEntry(copy, position, TiffExif.ORIENTATION, code, block.little);
                position += 12;
                inserted = true;
            }
            System.arraycopy(tiff, entry, copy, position, 12);
            position += 12;
        }
        if (!inserted) {
            writeShortEntry(copy, position, TiffExif.ORIENTATION, code, block.little);
            position += 12;
        }
        ByteArrays.put16(copy, start, count + 1, block.little);
        ByteArrays.put32(copy, position, ifd0.next, block.little);
        Arrays.fill(copy, ifd0.offset, ifd0.end(), (byte) 0);
        ByteArrays.put32(copy, 4, start, block.little);
        return copy;
    }

    /**
     * {@return whether a block starts with a classic TIFF header, {@code II*\0} or {@code MM\0*}, the form an
     * EXIF block takes}
     *
     * @param block the bytes
     */
    public static boolean isTiff(byte[] block) {
        if (block.length < TiffHeader.SIZE) {
            return false;
        }
        return block[0] == 'I' && block[1] == 'I' && block[2] == 42 && block[3] == 0
                || block[0] == 'M' && block[1] == 'M' && block[2] == 0 && block[3] == 42;
    }

    /**
     * Returns a block without its GPS directory: the IFD0 pointer entry is removed and the directory
     * and its values are zeroed.
     *
     * @param tiff the block
     * @return the edited block, or {@code tiff} when it has no GPS pointer or is not readable
     */
    public static byte[] withoutGps(byte[] tiff) {
        var block = Block.read(tiff);
        if (block == null) {
            return tiff;
        }
        var ifd0 = block.directory(block.firstIfd);
        if (ifd0 == null) {
            return tiff;
        }
        var index = ifd0.find(GPS_POINTER);
        if (index < 0) {
            return tiff;
        }
        var gpsOffset = ifd0.pointer(index);
        var copy = tiff.clone();
        var removed = new boolean[tiff.length];
        var gps = gpsOffset > 0 ? block.directory(gpsOffset) : null;
        if (gps != null) {
            gps.mark(removed);
        }
        removeEntry(copy, ifd0, index, block.little);
        var kept = block.keptRanges(copy, true);
        zero(copy, removed, kept);
        return trim(copy, removed, kept);
    }

    /**
     * {@return whether a block has a GPS directory pointer in IFD0}
     *
     * @param tiff the block
     */
    public static boolean hasGps(byte[] tiff) {
        var block = Block.read(tiff);
        var ifd0 = block == null ? null : block.directory(block.firstIfd);
        return ifd0 != null && ifd0.find(GPS_POINTER) >= 0;
    }

    /**
     * Returns a block without its thumbnail: IFD0 no longer links the thumbnail directory (IFD1), and that
     * directory, its values and the thumbnail data are zeroed.
     *
     * @param tiff the block
     * @return the edited block, or {@code tiff} when it has no thumbnail directory or is not readable
     */
    public static byte[] withoutThumbnail(byte[] tiff) {
        var block = Block.read(tiff);
        if (block == null) {
            return tiff;
        }
        var ifd0 = block.directory(block.firstIfd);
        if (ifd0 == null || ifd0.next == 0) {
            return tiff;
        }
        var copy = tiff.clone();
        var removed = new boolean[tiff.length];
        var ifd1 = block.directory(ifd0.next);
        if (ifd1 != null) {
            ifd1.mark(removed);
            ifd1.markThumbnail(removed);
        }
        ByteArrays.put32(copy, ifd0.end() - 4, 0, block.little);
        var kept = block.keptRanges(copy, false);
        zero(copy, removed, kept);
        return trim(copy, removed, kept);
    }

    /**
     * Returns a block whose Exif directory {@code PixelXDimension} and {@code PixelYDimension} entries hold
     * the size of a picture; entries the block lacks are not added, and a {@code SHORT} entry too small for
     * its value is rewritten in place as a {@code LONG}.
     *
     * @param tiff the block
     * @param width the picture width, positive
     * @param height the picture height, positive
     * @return the edited block, or {@code tiff} when nothing changes or it is not readable
     */
    public static byte[] withPixelDimensions(byte[] tiff, int width, int height) {
        var block = Block.read(tiff);
        if (block == null) {
            return tiff;
        }
        var ifd0 = block.directory(block.firstIfd);
        var pointer = ifd0 == null ? -1 : ifd0.find(EXIF_POINTER);
        var exif = pointer < 0 ? null : block.directory(ifd0.pointer(pointer));
        if (exif == null) {
            return tiff;
        }
        var result = tiff;
        for (var tag : new int[]{PIXEL_X_DIMENSION, PIXEL_Y_DIMENSION}) {
            var index = exif.find(tag);
            if (index < 0) {
                continue;
            }
            var value = tag == PIXEL_X_DIMENSION ? width : height;
            var entry = exif.entryOffset(index);
            var type = block.u16(entry + 2);
            var count = block.u32(entry + 4);
            long current = -1;
            if (count == 1 && type == SHORT) {
                current = block.u16(entry + 8);
            } else if (count == 1 && type == LONG) {
                current = block.u32(entry + 8);
            }
            if (current == value) {
                continue;
            }
            if (result == tiff) {
                result = tiff.clone();
            }
            Arrays.fill(result, entry + 8, entry + 12, (byte) 0);
            if (value <= 0xFFFF && type == SHORT) {
                ByteArrays.put16(result, entry + 8, value, block.little);
            } else {
                ByteArrays.put16(result, entry + 2, LONG, block.little);
                ByteArrays.put32(result, entry + 8, value, block.little);
            }
            ByteArrays.put32(result, entry + 4, 1, block.little);
        }
        return result;
    }

    /**
     * Writes a one-value {@code SHORT} entry.
     *
     * @param data the block
     * @param at the offset of the entry
     * @param tag the tag of the entry
     * @param value the one SHORT value
     * @param little whether the block is little-endian
     */
    private static void writeShortEntry(byte[] data, int at, int tag, int value, boolean little) {
        ByteArrays.put16(data, at, tag, little);
        ByteArrays.put16(data, at + 2, SHORT, little);
        ByteArrays.put32(data, at + 4, 1, little);
        Arrays.fill(data, at + 8, at + 12, (byte) 0);
        ByteArrays.put16(data, at + 8, value, little);
    }

    /**
     * {@return a big-endian block holding only an orientation entry}
     *
     * @param orientation the orientation to record
     */
    private static byte[] minimal(Orientation orientation) {
        var block = new byte[TiffHeader.SIZE + 2 + 12 + 4];
        block[0] = 'M';
        block[1] = 'M';
        block[3] = 42;
        ByteArrays.put32be(block, 4, TiffHeader.SIZE);
        ByteArrays.put16be(block, TiffHeader.SIZE, 1);
        writeShortEntry(block, TiffHeader.SIZE + 2, TiffExif.ORIENTATION, orientation.exifCode(), false);
        return block;
    }

    /**
     * Removes an entry from a directory in place, moving the later entries and the next-directory offset
     * up and zeroing the freed tail of the table.
     *
     * @param data the block
     * @param directory the directory, as read before the edit
     * @param index the index of the entry
     * @param little whether the block is little-endian
     */
    private static void removeEntry(byte[] data, Directory directory, int index, boolean little) {
        var entry = directory.entryOffset(index);
        var end = directory.end();
        System.arraycopy(data, entry + 12, data, entry, end - entry - 12);
        Arrays.fill(data, end - 12, end, (byte) 0);
        ByteArrays.put16(data, directory.offset, directory.count - 1, little);
    }

    /**
     * Zeroes the removed bytes that no remaining directory references.
     *
     * @param data the block
     * @param removed the bytes of the removed directories and values
     * @param kept the bytes the remaining directories reference
     */
    private static void zero(byte[] data, boolean[] removed, boolean[] kept) {
        for (var i = TiffHeader.SIZE; i < data.length; i++) {
            if (removed[i] && !kept[i]) {
                data[i] = 0;
            }
        }
    }

    /**
     * Cuts off the removed bytes that end a block.
     *
     * @param data the edited block
     * @param removed the bytes of the removed directories and values
     * @param kept the bytes the remaining directories reference
     * @return the block, shortened when it ends with removed bytes
     */
    private static byte[] trim(byte[] data, boolean[] removed, boolean[] kept) {
        var length = data.length;
        while (length > TiffHeader.SIZE && removed[length - 1] && !kept[length - 1]) {
            length--;
        }
        return length == data.length ? data : Arrays.copyOf(data, length);
    }

    /**
     * {@return the size of one value of a field type; 1 for an unknown type}
     *
     * @param type the field type
     */
    private static int typeSize(int type) {
        return switch (type) {
            case 3, 8 -> 2;
            case 4, 9, 11, 13 -> 4;
            case 5, 10, 12 -> 8;
            default -> 1;
        };
    }

    /**
     * Holds a readable classic TIFF structure.
     *
     * @param data the block
     * @param little whether it is little-endian
     * @param firstIfd the offset of IFD0
     */
    private record Block(byte[] data, boolean little, long firstIfd) {
        /**
         * {@return the structure, or {@code null} when the bytes are not a classic TIFF structure}
         *
         * @param data the bytes
         */
        static Block read(byte[] data) {
            TiffHeader header;
            try {
                header = TiffHeader.parse(data, 0, data.length);
            } catch (MediaException exception) {
                return null;
            }
            if (header == null || header.big() || header.firstIfd() < TiffHeader.SIZE
                    || header.firstIfd() + 2 > data.length) {
                return null;
            }
            return new Block(data, header.little(), header.firstIfd());
        }

        int u16(int at) {
            return ByteArrays.u16(data, at, little);
        }

        long u32(int at) {
            return ByteArrays.u32(data, at, little);
        }

        /**
         * {@return the directory at an offset, or {@code null} when its table does not fit the block}
         *
         * @param offset the offset of the directory
         */
        Directory directory(long offset) {
            if (offset < TiffHeader.SIZE || offset + 2 > data.length) {
                return null;
            }
            var at = (int) offset;
            var count = u16(at);
            if (count > MAX_ENTRIES || at + 2L + 12L * count + 4 > data.length) {
                return null;
            }
            return new Directory(this, at, count, u32(at + 2 + 12 * count));
        }

        /**
         * Marks the bytes that the directories left in an edited block reference: IFD0, the Exif,
         * interoperability and GPS directories it links, the thumbnail directory and its thumbnail when
         * kept, and all their values.
         *
         * @param edited the block after the edit, with the same layout of directories
         * @param thumbnail whether the thumbnail directory is kept
         * @return the marks, one per byte
         */
        boolean[] keptRanges(byte[] edited, boolean thumbnail) {
            var kept = new boolean[edited.length];
            Arrays.fill(kept, 0, Math.min(TiffHeader.SIZE, kept.length), true);
            var view = new Block(edited, little, firstIfd);
            var ifd0 = view.directory(firstIfd);
            if (ifd0 == null) {
                return kept;
            }
            var visited = new ArrayList<Integer>();
            ifd0.mark(kept);
            visited.add(ifd0.offset);
            for (var tag : new int[]{EXIF_POINTER, GPS_POINTER}) {
                var index = ifd0.find(tag);
                var sub = index < 0 ? null : view.directory(ifd0.pointer(index));
                if (sub == null || visited.contains(sub.offset)) {
                    continue;
                }
                sub.mark(kept);
                visited.add(sub.offset);
                if (tag == EXIF_POINTER) {
                    var interop = sub.find(INTEROP_POINTER);
                    var directory = interop < 0 ? null : view.directory(sub.pointer(interop));
                    if (directory != null && !visited.contains(directory.offset)) {
                        directory.mark(kept);
                        visited.add(directory.offset);
                    }
                }
            }
            if (thumbnail && ifd0.next != 0) {
                var ifd1 = view.directory(ifd0.next);
                if (ifd1 != null && !visited.contains(ifd1.offset)) {
                    ifd1.mark(kept);
                    ifd1.markThumbnail(kept);
                }
            }
            return kept;
        }
    }

    /**
     * Holds the table of one directory.
     *
     * @param block the structure
     * @param offset the offset of the entry count
     * @param count the number of entries
     * @param next the offset of the next directory, 0 for none
     */
    private record Directory(Block block, int offset, int count, long next) {
        int entryOffset(int index) {
            return offset + 2 + 12 * index;
        }

        /**
         * {@return the offset just past the table, its next-directory offset included}
         */
        int end() {
            return offset + 2 + 12 * count + 4;
        }

        int find(int tag) {
            for (var i = 0; i < count; i++) {
                if (block.u16(entryOffset(i)) == tag) {
                    return i;
                }
            }
            return -1;
        }

        /**
         * {@return the offset an entry's single value points at, read as {@code LONG}}
         *
         * @param index the index of the entry
         */
        long pointer(int index) {
            return block.u32(entryOffset(index) + 8);
        }

        /**
         * {@return the length of an entry's value in bytes}
         *
         * @param index the index of the entry
         */
        long byteLength(int index) {
            var entry = entryOffset(index);
            return block.u32(entry + 4) * typeSize(block.u16(entry + 2));
        }

        /**
         * {@return the offset of an entry's value: inside the entry when it fits 4 bytes, or -1 when it lies
         * outside the block}
         *
         * @param index the index of the entry
         */
        long valuePosition(int index) {
            var length = byteLength(index);
            var entry = entryOffset(index);
            if (length <= 4) {
                return entry + 8;
            }
            var at = block.u32(entry + 8);
            return at + length <= block.data.length ? at : -1;
        }

        /**
         * Marks the table and the out-of-line values of the directory.
         *
         * @param marks the marks, one per byte of the block
         */
        void mark(boolean[] marks) {
            Arrays.fill(marks, offset, Math.min(end(), marks.length), true);
            for (var i = 0; i < count; i++) {
                var length = byteLength(i);
                if (length <= 4) {
                    continue;
                }
                var at = block.u32(entryOffset(i) + 8);
                fill(marks, at, length);
            }
        }

        /**
         * Marks the thumbnail data the directory locates: its JPEG interchange format, or its strips.
         *
         * @param marks the marks, one per byte of the block
         */
        void markThumbnail(boolean[] marks) {
            var offsetIndex = find(THUMBNAIL_OFFSET);
            var lengthIndex = find(THUMBNAIL_LENGTH);
            if (offsetIndex >= 0 && lengthIndex >= 0) {
                fill(marks, firstValue(offsetIndex), firstValue(lengthIndex));
            }
            var strips = find(STRIP_OFFSETS);
            var counts = find(STRIP_BYTE_COUNTS);
            if (strips >= 0 && counts >= 0) {
                var offsets = values(strips);
                var lengths = values(counts);
                for (var i = 0; i < Math.min(offsets.size(), lengths.size()); i++) {
                    fill(marks, offsets.get(i), lengths.get(i));
                }
            }
        }

        /**
         * {@return the first value of a {@code SHORT} or {@code LONG} entry, or -1}
         *
         * @param index the index of the entry
         */
        long firstValue(int index) {
            var values = values(index);
            return values.isEmpty() ? -1 : values.getFirst();
        }

        /**
         * {@return the values of a {@code SHORT} or {@code LONG} entry, at most {@value #MAX_ENTRIES}, empty
         * for another type or values outside the block}
         *
         * @param index the index of the entry
         */
        List<Long> values(int index) {
            var entry = entryOffset(index);
            var type = block.u16(entry + 2);
            var count = block.u32(entry + 4);
            var at = valuePosition(index);
            var result = new ArrayList<Long>();
            if ((type != SHORT && type != LONG) || at < 0) {
                return result;
            }
            for (var i = 0; i < Math.min(count, MAX_ENTRIES); i++) {
                result.add(type == SHORT ? block.u16((int) (at + 2 * i)) : block.u32((int) (at + 4 * i)));
            }
            return result;
        }

        /**
         * Marks a range of bytes, clipped to the block.
         *
         * @param marks the marks to set, one per byte of the block
         * @param at the first byte, negative for none
         * @param length the number of bytes
         */
        private static void fill(boolean[] marks, long at, long length) {
            if (at < TiffHeader.SIZE || length <= 0 || at >= marks.length) {
                return;
            }
            Arrays.fill(marks, (int) at, (int) Math.min(marks.length, at + length), true);
        }
    }
}
