package com.github.auties00.smalt.image.exif;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.Orientation;

import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Writes EXIF blocks: a fresh, compact layout of an {@link ExifData}, or an edit of an existing
 * block that keeps every unchanged value where it was.
 *
 * <p>Both forms sort the entries of each directory by tag, as TIFF requires, and recompute the
 * pointer entries ({@link ExifData#EXIF_IFD_POINTER}, {@link ExifData#GPS_IFD_POINTER},
 * {@link ExifData#INTEROP_IFD_POINTER}) and the thumbnail location
 * ({@link ExifData#THUMBNAIL_OFFSET} and {@link ExifData#THUMBNAIL_LENGTH} for a JPEG thumbnail,
 * {@link ExifData#STRIP_OFFSETS} and {@link ExifData#STRIP_BYTE_COUNTS} for an uncompressed one,
 * which is written as a single strip): a directory is written when it has entries (the Exif
 * directory also when the interoperability directory has some, the thumbnail directory also when
 * there is a thumbnail) and its pointer is dropped otherwise. The location entries of the other
 * thumbnail kind are dropped, and so is a {@link ExifData#ROWS_PER_STRIP} smaller than the
 * thumbnail's {@code ImageLength}, which a single strip contradicts.
 *
 * <p>{@link #rewrite(byte[], ExifData)} is the safe way to edit a camera's block. Maker notes
 * hold offsets relative to the start of the block that a relocation would break; the rewrite
 * leaves every unchanged out-of-line value, the maker note included, at its original offset,
 * reuses the space of each directory when the new one fits, and appends what changed. Every byte
 * of a value, directory or thumbnail that the edit removes is zeroed, so removing the GPS
 * directory leaves no trace of the position in the block.
 *
 * <p>This class is thread-safe.
 */
public final class ExifWriter {
    /**
     * The largest EXIF block written, the most a TIFF offset can address.
     */
    private static final long MAX_BLOCK = Integer.MAX_VALUE - 16;

    /**
     * The tags of IFD0 that hold offsets a fresh layout cannot relocate.
     */
    private static final Set<Integer> UNMOVABLE_IFD0 = Set.of(0x014A);

    /**
     * The tags of IFD1 that the writer computes from the thumbnail.
     */
    private static final Set<Integer> THUMBNAIL_LOCATION = Set.of(ExifData.THUMBNAIL_OFFSET, ExifData.THUMBNAIL_LENGTH,
            ExifData.STRIP_OFFSETS, ExifData.STRIP_BYTE_COUNTS);

    /**
     * The tag of the {@code ImageLength} entry of IFD1.
     */
    private static final int IMAGE_LENGTH = 0x0101;

    /**
     * Prevents instantiation.
     */
    private ExifWriter() {
        throw new AssertionError();
    }

    /**
     * Writes a fresh big-endian block.
     *
     * @param data the directories and thumbnail
     * @return the block, starting with its byte-order mark
     * @throws MediaException.LimitExceeded if the block exceeds what TIFF offsets address
     */
    public static byte[] write(ExifData data) {
        return write(data, ByteOrder.BIG_ENDIAN);
    }

    /**
     * Writes a fresh block in a byte order.
     *
     * <p>The directories follow the header in the order IFD0, Exif, interoperability, GPS and
     * IFD1, each followed by its out-of-line values, and the thumbnail comes last. The
     * sub-directory offsets of IFD0 ({@code SubIFDs}) and every entry of type
     * {@link ExifValue.Type#IFD}, which point at data this class does not hold, are left out; a
     * maker note is copied as is, which breaks the manufacturers' maker notes that use offsets
     * relative to the block.
     *
     * @param data the directories and thumbnail
     * @param order the byte order
     * @return the block, starting with its byte-order mark
     * @throws MediaException.LimitExceeded if the block exceeds what TIFF offsets address
     */
    public static byte[] write(ExifData data, ByteOrder order) {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(order, "order");
        var little = order == ByteOrder.LITTLE_ENDIAN;
        var directories = layout(data, true, little);
        var cursor = (long) ExifReader.HEADER_SIZE;
        var offsets = new long[directories.size()];
        for (var index = 0; index < directories.size(); index++) {
            offsets[index] = cursor;
            cursor += directories.get(index).size();
            cursor = (cursor + 1) & ~1L;
        }
        var thumbnailOffset = cursor;
        var thumbnail = data.thumbnail().orElse(null);
        if (thumbnail != null) {
            cursor += thumbnail.length;
        }
        if (cursor > MAX_BLOCK) {
            throw new MediaException.LimitExceeded("EXIF block of " + cursor + " bytes is too large");
        }
        var block = new byte[(int) cursor];
        writeHeader(block, little, offsets[0]);
        var pointers = pointers(directories, offsets, thumbnailOffset, thumbnail);
        for (var index = 0; index < directories.size(); index++) {
            var directory = directories.get(index);
            var valueOffset = offsets[index] + directory.tableSize();
            var placements = new long[directory.entries().size()];
            for (var i = 0; i < placements.length; i++) {
                var value = directory.entries().get(i).bytes();
                if (value != null && value.length > 4) {
                    placements[i] = valueOffset;
                    valueOffset += (value.length + 1) & ~1;
                }
            }
            writeDirectory(block, offsets[index], directory, placements, pointers, little);
        }
        if (thumbnail != null) {
            System.arraycopy(thumbnail, 0, block, (int) thumbnailOffset, thumbnail.length);
        }
        return block;
    }

    /**
     * Returns a copy of a block whose IFD0 {@code Orientation} entry holds an orientation,
     * patching the bytes in place rather than laying the block out again.
     *
     * <p>The input array is never modified, and every other entry, offset and maker note of the
     * block keeps its bytes. When IFD0 already has an {@code Orientation} entry of type
     * {@code SHORT} or {@code LONG}, only that value changes; an entry of any other type is
     * rewritten in place as a single {@code SHORT}. When IFD0 has no such entry, a new IFD0 holding
     * the original entries plus the orientation is appended at the end of the copy and the header
     * points at it. When {@code tiff} is not a readable TIFF structure, the result is a new minimal
     * big-endian block holding only the orientation.
     *
     * @apiNote Prefer this to {@link #rewrite(byte[], ExifData)} when only the orientation changes:
     * it cannot disturb data that {@link ExifData} does not model.
     * @param tiff the EXIF block, starting with its byte-order mark
     * @param orientation the orientation to record
     * @return the patched block
     */
    public static byte[] withOrientation(byte[] tiff, Orientation orientation) {
        return ExifReader.withOrientation(tiff, orientation);
    }

    /**
     * Edits an existing block so that it holds new directories, keeping unchanged values at
     * their offsets and zeroing what is removed.
     *
     * <p>A block that is not a readable TIFF structure is replaced by {@link #write(ExifData)}.
     *
     * @param original the existing block; never modified
     * @param edited the directories and thumbnail to store, usually derived from
     * {@code ExifReader.parse(original)}
     * @return the edited block, in the byte order of the original
     * @throws MediaException.LimitExceeded if the block exceeds what TIFF offsets address
     */
    public static byte[] rewrite(byte[] original, ExifData edited) {
        Objects.requireNonNull(original, "original");
        Objects.requireNonNull(edited, "edited");
        var order = ExifReader.byteOrder(original);
        if (order == 0) {
            return write(edited);
        }
        var little = order == 1;
        var ifd0Offset = ExifReader.u32(original, 4, little);
        if (ifd0Offset < ExifReader.HEADER_SIZE || ifd0Offset + 2 > original.length) {
            return write(edited, little ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
        }
        var old = scan(original, little, ifd0Offset);
        var directories = layout(edited, false, little);
        var keep = new boolean[original.length];
        var length = new long[]{original.length};
        var placements = new ArrayList<long[]>();
        for (var directory : directories) {
            var previous = old.get(directory.kind());
            var places = new long[directory.entries().size()];
            for (var i = 0; i < places.length; i++) {
                var entry = directory.entries().get(i);
                var bytes = entry.bytes();
                if (bytes == null || bytes.length <= 4) {
                    continue;
                }
                var before = previous != null ? previous.entries().get(entry.tag()) : null;
                // a value kept in place is written back there, so it must not overlap the new header
                if (before != null && before.valueOffset() >= ExifReader.HEADER_SIZE
                        && before.type() == entry.value().type()
                        && before.count() == entry.count() && before.valueOffset() + bytes.length <= original.length
                        && Arrays.equals(original, (int) before.valueOffset(), (int) before.valueOffset() + bytes.length,
                        bytes, 0, bytes.length)) {
                    places[i] = before.valueOffset();
                    mark(keep, before.valueOffset(), bytes.length);
                } else {
                    places[i] = append(length, bytes.length);
                }
            }
            placements.add(places);
        }
        var offsets = new long[directories.size()];
        for (var index = 0; index < directories.size(); index++) {
            var directory = directories.get(index);
            var previous = old.get(directory.kind());
            if (previous != null && directory.tableSize() <= previous.tableSize()
                    && free(keep, previous.offset(), directory.tableSize())) {
                offsets[index] = previous.offset();
                mark(keep, previous.offset(), directory.tableSize());
            } else {
                offsets[index] = append(length, directory.tableSize());
            }
        }
        var thumbnail = edited.thumbnail().orElse(null);
        var thumbnailOffset = -1L;
        if (thumbnail != null) {
            var before = old.thumbnail();
            if (before != null && old.uncompressed() == edited.hasUncompressedThumbnail() && before[1] == thumbnail.length
                    && Arrays.equals(original, (int) before[0], (int) (before[0] + before[1]), thumbnail, 0, thumbnail.length)) {
                thumbnailOffset = before[0];
                mark(keep, before[0], before[1]);
            } else {
                thumbnailOffset = append(length, thumbnail.length);
            }
        }
        if (length[0] > MAX_BLOCK) {
            throw new MediaException.LimitExceeded("EXIF block of " + length[0] + " bytes is too large");
        }
        var block = Arrays.copyOf(original, (int) length[0]);
        for (var region : old.regions()) {
            for (var at = region[0]; at < region[0] + region[1] && at < original.length; at++) {
                if (!keep[(int) at]) {
                    block[(int) at] = 0;
                }
            }
        }
        writeHeader(block, little, offsets[0]);
        var pointers = pointers(directories, offsets, thumbnailOffset, thumbnail);
        for (var index = 0; index < directories.size(); index++) {
            writeDirectory(block, offsets[index], directories.get(index), placements.get(index), pointers, little);
        }
        if (thumbnail != null) {
            System.arraycopy(thumbnail, 0, block, (int) thumbnailOffset, thumbnail.length);
        }
        return block;
    }

    /**
     * Returns whether a range holds no byte marked as kept.
     *
     * @param keep the kept bytes
     * @param offset the start of the range
     * @param length the length of the range
     * @return {@code true} when the range lies inside the original and is free
     */
    private static boolean free(boolean[] keep, long offset, long length) {
        if (offset < 0 || offset + length > keep.length) {
            return false;
        }
        for (var at = offset; at < offset + length; at++) {
            if (keep[(int) at]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Marks a range as kept.
     *
     * @param keep the kept bytes
     * @param offset the start of the range
     * @param length the length of the range
     */
    private static void mark(boolean[] keep, long offset, long length) {
        for (var at = offset; at < offset + length && at < keep.length; at++) {
            keep[(int) at] = true;
        }
    }

    /**
     * Reserves space at the end of the block, aligned to an even offset.
     *
     * @param length a one-element array holding the current block length, updated
     * @param size the size to reserve
     * @return the offset of the reserved space
     */
    private static long append(long[] length, long size) {
        var offset = (length[0] + 1) & ~1L;
        length[0] = offset + size;
        return offset;
    }

    /**
     * Writes the TIFF header.
     *
     * @param block the block being written
     * @param little whether to write little endian
     * @param ifd0Offset the offset of IFD0
     */
    private static void writeHeader(byte[] block, boolean little, long ifd0Offset) {
        block[0] = (byte) (little ? 'I' : 'M');
        block[1] = block[0];
        ExifReader.put16(block, 2, ExifReader.TIFF_MAGIC, little);
        ExifReader.put32(block, 4, ifd0Offset, little);
    }

    /**
     * Computes the values of the pointer and thumbnail entries.
     *
     * @param directories the directories to write
     * @param offsets their offsets, parallel to {@code directories}
     * @param thumbnailOffset the offset of the thumbnail
     * @param thumbnail the thumbnail, or {@code null}
     * @return the values keyed by the kind of directory holding the entry and the tag
     */
    private static Map<Long, Long> pointers(List<Directory> directories, long[] offsets, long thumbnailOffset,
                                            byte[] thumbnail) {
        var result = new HashMap<Long, Long>();
        for (var index = 0; index < directories.size(); index++) {
            switch (directories.get(index).kind()) {
                case EXIF -> result.put(key(ExifData.Directory.IFD0, ExifData.EXIF_IFD_POINTER), offsets[index]);
                case GPS -> result.put(key(ExifData.Directory.IFD0, ExifData.GPS_IFD_POINTER), offsets[index]);
                case INTEROP -> result.put(key(ExifData.Directory.EXIF, ExifData.INTEROP_IFD_POINTER), offsets[index]);
                case THUMBNAIL -> result.put(key(ExifData.Directory.IFD0, -1), offsets[index]);
                default -> {
                }
            }
        }
        if (thumbnail != null) {
            result.put(key(ExifData.Directory.THUMBNAIL, ExifData.THUMBNAIL_OFFSET), thumbnailOffset);
            result.put(key(ExifData.Directory.THUMBNAIL, ExifData.THUMBNAIL_LENGTH), (long) thumbnail.length);
            result.put(key(ExifData.Directory.THUMBNAIL, ExifData.STRIP_OFFSETS), thumbnailOffset);
            result.put(key(ExifData.Directory.THUMBNAIL, ExifData.STRIP_BYTE_COUNTS), (long) thumbnail.length);
        }
        return result;
    }

    /**
     * Combines a directory kind and a tag into a map key.
     *
     * @param kind the directory
     * @param tag the tag, or {@code -1} for the next-directory pointer
     * @return the key
     */
    private static long key(ExifData.Directory kind, int tag) {
        return ((long) kind.ordinal() << 32) | (tag & 0xFFFFFFFFL);
    }

    /**
     * Serializes one directory and its out-of-line values.
     *
     * @param block the block being written
     * @param offset the offset of the directory
     * @param directory the laid-out directory to write
     * @param placements the offset of each entry's out-of-line value
     * @param pointers the pointer and thumbnail values
     * @param little whether to write little endian
     */
    private static void writeDirectory(byte[] block, long offset, Directory directory, long[] placements,
                                       Map<Long, Long> pointers, boolean little) {
        var at = (int) offset;
        ExifReader.put16(block, at, directory.entries().size(), little);
        for (var index = 0; index < directory.entries().size(); index++) {
            var entry = directory.entries().get(index);
            var position = at + 2 + index * ExifReader.ENTRY_SIZE;
            ExifReader.put16(block, position, entry.tag(), little);
            var pointer = pointers.get(key(directory.kind(), entry.tag()));
            if (entry.value() == null) {
                ExifReader.put16(block, position + 2, ExifValue.Type.LONG.code(), little);
                ExifReader.put32(block, position + 4, 1, little);
                ExifReader.put32(block, position + 8, pointer != null ? pointer : 0, little);
                continue;
            }
            var bytes = entry.bytes();
            ExifReader.put16(block, position + 2, entry.value().type().code(), little);
            ExifReader.put32(block, position + 4, entry.count(), little);
            ExifReader.put32(block, position + 8, 0, little);
            if (bytes.length <= 4) {
                System.arraycopy(bytes, 0, block, position + 8, bytes.length);
            } else {
                ExifReader.put32(block, position + 8, placements[index], little);
                System.arraycopy(bytes, 0, block, (int) placements[index], bytes.length);
            }
        }
        var next = directory.kind() == ExifData.Directory.IFD0 ? pointers.get(key(ExifData.Directory.IFD0, -1)) : null;
        ExifReader.put32(block, at + 2 + directory.entries().size() * ExifReader.ENTRY_SIZE, next != null ? next : 0, little);
    }

    /**
     * Plans the directories of a block.
     *
     * @param data the directories and thumbnail
     * @param fresh whether the layout is fresh, which leaves out the entries pointing at data it
     * cannot relocate
     * @param little whether the values are encoded little endian
     * @return the directories to write, IFD0 first
     */
    private static List<Directory> layout(ExifData data, boolean fresh, boolean little) {
        var hasThumbnail = data.thumbnail().isPresent();
        var interop = !data.interop().isEmpty();
        var exif = !data.exif().isEmpty() || interop;
        var gps = !data.gps().isEmpty();
        var ifd1 = !data.thumbnailIfd().isEmpty() || hasThumbnail;
        var result = new ArrayList<Directory>();
        var ifd0 = entries(data.ifd0(), Set.of(ExifData.EXIF_IFD_POINTER, ExifData.GPS_IFD_POINTER),
                fresh ? UNMOVABLE_IFD0 : Set.of(), fresh);
        if (exif) {
            ifd0.put(ExifData.EXIF_IFD_POINTER, null);
        }
        if (gps) {
            ifd0.put(ExifData.GPS_IFD_POINTER, null);
        }
        result.add(new Directory(ExifData.Directory.IFD0, list(ifd0, little)));
        if (exif) {
            var entries = entries(data.exif(), Set.of(ExifData.INTEROP_IFD_POINTER), Set.of(), fresh);
            if (interop) {
                entries.put(ExifData.INTEROP_IFD_POINTER, null);
            }
            result.add(new Directory(ExifData.Directory.EXIF, list(entries, little)));
        }
        if (interop) {
            result.add(new Directory(ExifData.Directory.INTEROP, list(entries(data.interop(), Set.of(), Set.of(), fresh),
                    little)));
        }
        if (gps) {
            result.add(new Directory(ExifData.Directory.GPS, list(entries(data.gps(), Set.of(), Set.of(), fresh), little)));
        }
        if (ifd1) {
            var entries = entries(data.thumbnailIfd(), THUMBNAIL_LOCATION, Set.of(), fresh);
            if (data.hasUncompressedThumbnail()) {
                entries.put(ExifData.STRIP_OFFSETS, null);
                entries.put(ExifData.STRIP_BYTE_COUNTS, null);
                var rows = first(entries.get(ExifData.ROWS_PER_STRIP));
                var height = first(entries.get(IMAGE_LENGTH));
                if (rows >= 0 && height >= 0 && rows < height) {
                    entries.remove(ExifData.ROWS_PER_STRIP);
                }
            } else if (hasThumbnail) {
                entries.put(ExifData.THUMBNAIL_OFFSET, null);
                entries.put(ExifData.THUMBNAIL_LENGTH, null);
            }
            result.add(new Directory(ExifData.Directory.THUMBNAIL, list(entries, little)));
        }
        return result;
    }

    /**
     * Returns the first element of an integer entry.
     *
     * @param value the entry, or {@code null}
     * @return the element, or {@code -1} when the entry is absent, empty or not an integer
     */
    private static long first(ExifValue value) {
        return value instanceof ExifValue.Integers integers && integers.count() > 0 ? integers.get(0) : -1;
    }

    /**
     * Collects the entries of a directory sorted by tag, leaving out computed and unmovable tags.
     *
     * @param map the entries
     * @param computed the tags the writer computes
     * @param unmovable the tags left out because they point at data that cannot move
     * @param fresh whether the layout is fresh, which also leaves out the entries of type
     * {@link ExifValue.Type#IFD}
     * @return the entries keyed by tag, a {@code null} value marking a computed entry
     */
    private static TreeMap<Integer, ExifValue> entries(Map<Integer, ExifValue> map, Set<Integer> computed,
                                                       Set<Integer> unmovable, boolean fresh) {
        var result = new TreeMap<Integer, ExifValue>();
        for (var entry : map.entrySet()) {
            var tag = entry.getKey();
            if (fresh && entry.getValue().type() == ExifValue.Type.IFD) {
                continue;
            }
            if (tag >= 0 && tag <= 0xFFFF && !computed.contains(tag) && !unmovable.contains(tag)) {
                result.put(tag, entry.getValue());
            }
        }
        return result;
    }

    /**
     * Turns sorted entries into a list, encoding each value once.
     *
     * @param entries the entries keyed by tag
     * @param little whether the values are encoded little endian
     * @return the list
     */
    private static List<Entry> list(TreeMap<Integer, ExifValue> entries, boolean little) {
        var result = new ArrayList<Entry>(entries.size());
        for (var entry : entries.entrySet()) {
            var value = entry.getValue();
            result.add(new Entry(entry.getKey(), value, value == null ? null : encode(value, little)));
        }
        return result;
    }

    /**
     * Encodes a value in a byte order.
     *
     * @param value the value to serialize
     * @param little whether to write little endian
     * @return the bytes
     */
    static byte[] encode(ExifValue value, boolean little) {
        return switch (value) {
            case ExifValue.Ascii ascii -> {
                var text = ascii.value().getBytes(StandardCharsets.ISO_8859_1);
                yield Arrays.copyOf(text, text.length + 1);
            }
            case ExifValue.Utf8 utf8 -> utf8.encode();
            case ExifValue.Undefined undefined -> undefined.bytes();
            case ExifValue.Integers integers -> {
                var size = integers.type().size();
                var values = integers.values();
                var bytes = new byte[values.length * size];
                for (var i = 0; i < values.length; i++) {
                    switch (size) {
                        case 1 -> bytes[i] = (byte) values[i];
                        case 2 -> ExifReader.put16(bytes, 2 * i, (int) values[i], little);
                        default -> ExifReader.put32(bytes, 4 * i, values[i], little);
                    }
                }
                yield bytes;
            }
            case ExifValue.Rationals rationals -> {
                var numerators = rationals.numerators();
                var denominators = rationals.denominators();
                var bytes = new byte[numerators.length * 8];
                for (var i = 0; i < numerators.length; i++) {
                    ExifReader.put32(bytes, 8 * i, numerators[i], little);
                    ExifReader.put32(bytes, 8 * i + 4, denominators[i], little);
                }
                yield bytes;
            }
            case ExifValue.Reals reals -> {
                var values = reals.values();
                var size = reals.type() == ExifValue.Type.FLOAT ? 4 : 8;
                var bytes = new byte[values.length * size];
                for (var i = 0; i < values.length; i++) {
                    if (size == 4) {
                        ExifReader.put32(bytes, 4 * i, Float.floatToRawIntBits((float) values[i]) & 0xFFFFFFFFL, little);
                    } else {
                        var bits = Double.doubleToRawLongBits(values[i]);
                        ExifReader.put32(bytes, 8 * i, little ? bits & 0xFFFFFFFFL : bits >>> 32, little);
                        ExifReader.put32(bytes, 8 * i + 4, little ? bits >>> 32 : bits & 0xFFFFFFFFL, little);
                    }
                }
                yield bytes;
            }
        };
    }

    /**
     * Scans the directories of an existing block with their offsets.
     *
     * @param tiff the block
     * @param little whether it is little endian
     * @param ifd0Offset the offset of IFD0
     * @return the directories found, their regions and the thumbnail range
     */
    private static Scan scan(byte[] tiff, boolean little, long ifd0Offset) {
        var directories = new HashMap<ExifData.Directory, Scanned>();
        var regions = new ArrayList<long[]>();
        var visited = new java.util.HashSet<Long>();
        var ifd0 = scanDirectory(tiff, little, ifd0Offset, regions, visited);
        directories.put(ExifData.Directory.IFD0, ifd0);
        var exifOffset = pointerOf(ifd0, ExifData.EXIF_IFD_POINTER);
        var exif = scanDirectory(tiff, little, exifOffset, regions, visited);
        if (exif != null) {
            directories.put(ExifData.Directory.EXIF, exif);
            var interop = scanDirectory(tiff, little, pointerOf(exif, ExifData.INTEROP_IFD_POINTER), regions,
                    visited);
            if (interop != null) {
                directories.put(ExifData.Directory.INTEROP, interop);
            }
        }
        var gps = scanDirectory(tiff, little, pointerOf(ifd0, ExifData.GPS_IFD_POINTER), regions, visited);
        if (gps != null) {
            directories.put(ExifData.Directory.GPS, gps);
        }
        long[] thumbnail = null;
        var uncompressed = false;
        var ifd1 = scanDirectory(tiff, little, ExifReader.nextDirectory(tiff, (int) ifd0Offset, little), regions,
                visited);
        if (ifd1 != null) {
            directories.put(ExifData.Directory.THUMBNAIL, ifd1);
            var parsed = ExifReader.parse(tiff).thumbnailIfd();
            if (ExifData.describesStrips(parsed)) {
                uncompressed = true;
                var strips = ExifReader.stripRanges(tiff, parsed.get(ExifData.STRIP_OFFSETS),
                        parsed.get(ExifData.STRIP_BYTE_COUNTS));
                if (strips != null) {
                    var contiguous = true;
                    var total = 0L;
                    for (var strip : strips) {
                        contiguous &= strip[0] == strips[0][0] + total;
                        total += strip[1];
                        regions.add(strip);
                    }
                    thumbnail = contiguous ? new long[]{strips[0][0], total} : null;
                }
            } else {
                var start = pointerOf(ifd1, ExifData.THUMBNAIL_OFFSET);
                var length = pointerOf(ifd1, ExifData.THUMBNAIL_LENGTH);
                if (start >= ExifReader.HEADER_SIZE && length > 0 && start + length <= tiff.length) {
                    thumbnail = new long[]{start, length};
                    regions.add(thumbnail);
                }
            }
        }
        return new Scan(directories, regions, thumbnail, uncompressed);
    }

    /**
     * Returns the single integer of a pointer entry.
     *
     * @param directory the scanned directory, or {@code null}
     * @param tag the tag number of the pointer entry
     * @return the value, or {@code -1}
     */
    private static long pointerOf(Scanned directory, int tag) {
        if (directory == null) {
            return -1;
        }
        var entry = directory.entries().get(tag);
        return entry != null && entry.count() >= 1 ? entry.inline() : -1;
    }

    /**
     * Scans one directory.
     *
     * @param tiff the block
     * @param little whether it is little endian
     * @param offset the offset of the directory, or a negative value when absent
     * @param regions receives the regions of the directory and its out-of-line values
     * @param visited the directory offsets already scanned
     * @return the directory, or {@code null} when absent, out of range or already scanned
     */
    private static Scanned scanDirectory(byte[] tiff, boolean little, long offset, List<long[]> regions,
                                         Set<Long> visited) {
        if (offset < ExifReader.HEADER_SIZE || offset + 2 > tiff.length || !visited.add(offset)) {
            return null;
        }
        var count = ExifReader.availableEntries(tiff, (int) offset, little);
        var declared = ExifReader.u16(tiff, (int) offset, little);
        var tableSize = 2L + (long) declared * ExifReader.ENTRY_SIZE + 4;
        regions.add(new long[]{offset, Math.min(tableSize, tiff.length - offset)});
        var entries = new HashMap<Integer, Original>();
        for (var i = 0; i < count; i++) {
            var at = (int) offset + 2 + i * ExifReader.ENTRY_SIZE;
            var tag = ExifReader.u16(tiff, at, little);
            var type = ExifValue.Type.find(ExifReader.u16(tiff, at + 2, little));
            if (type == null || entries.containsKey(tag)) {
                continue;
            }
            var elements = ExifReader.u32(tiff, at + 4, little);
            var size = elements * type.size();
            long valueOffset;
            if (size <= 4) {
                valueOffset = -1;
            } else {
                valueOffset = ExifReader.u32(tiff, at + 8, little);
                if (valueOffset + size > tiff.length) {
                    continue;
                }
                regions.add(new long[]{valueOffset, size});
            }
            var inline = switch (type) {
                case SHORT -> (long) ExifReader.u16(tiff, at + 8, little);
                case LONG, IFD -> ExifReader.u32(tiff, at + 8, little);
                default -> -1L;
            };
            entries.put(tag, new Original(type, elements, valueOffset, inline));
        }
        return new Scanned(offset, tableSize, entries);
    }

    /**
     * Holds one directory to write.
     *
     * @param kind which directory it is
     * @param entries the entries sorted by tag; a {@code null} value marks a computed entry
     */
    private record Directory(ExifData.Directory kind, List<Entry> entries) {
        /**
         * Returns the size of the directory table.
         *
         * @return the entry count, the entries and the next-directory pointer, in bytes
         */
        long tableSize() {
            return 2L + (long) entries.size() * ExifReader.ENTRY_SIZE + 4;
        }

        /**
         * Returns the size of the table and its out-of-line values.
         *
         * @return the size in bytes, each value padded to an even size
         */
        long size() {
            var size = tableSize();
            for (var entry : entries) {
                var bytes = entry.bytes();
                if (bytes != null && bytes.length > 4) {
                    size += (bytes.length + 1) & ~1;
                }
            }
            return size;
        }
    }

    /**
     * Holds one entry to write.
     *
     * @param tag the 16-bit tag number
     * @param value the value, or {@code null} for a computed pointer
     * @param bytes the value encoded in the block's byte order, or {@code null} for a computed
     * pointer
     */
    private record Entry(int tag, ExifValue value, byte[] bytes) {
        /**
         * {@return the element count written in the entry: the encoded length of an ASCII or UTF-8
         * value, its terminator included, the value's count otherwise}
         */
        long count() {
            return value instanceof ExifValue.Ascii || value instanceof ExifValue.Utf8 ? bytes.length : value.count();
        }
    }

    /**
     * Holds one entry of an existing directory.
     *
     * @param type the stored type
     * @param count the stored element count
     * @param valueOffset the offset of the out-of-line value, or {@code -1} when inline
     * @param inline the first {@code SHORT} or {@code LONG} of an inline value, the form pointer
     * entries take, or {@code -1} for another type
     */
    private record Original(ExifValue.Type type, long count, long valueOffset, long inline) {
    }

    /**
     * Holds one existing directory.
     *
     * @param offset the offset of the directory
     * @param tableSize the size of its table as declared
     * @param entries the entries keyed by tag
     */
    private record Scanned(long offset, long tableSize, Map<Integer, Original> entries) {
    }

    /**
     * Holds the result of scanning an existing block.
     *
     * @param directories the directories found
     * @param regions the offset and length of every directory table, out-of-line value and
     * thumbnail
     * @param thumbnail the offset and length of the thumbnail, or {@code null} when there is none or
     * its strips are not contiguous
     * @param uncompressed whether the thumbnail directory describes an uncompressed thumbnail
     */
    private record Scan(Map<ExifData.Directory, Scanned> directories, List<long[]> regions, long[] thumbnail,
                        boolean uncompressed) {
        /**
         * Returns a directory.
         *
         * @param kind which directory
         * @return the directory, or {@code null} when absent
         */
        Scanned get(ExifData.Directory kind) {
            return directories.get(kind);
        }
    }
}
