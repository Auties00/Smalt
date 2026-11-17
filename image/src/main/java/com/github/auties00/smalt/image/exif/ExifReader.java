package com.github.auties00.smalt.image.exif;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.tiff.Ifd;
import com.github.auties00.smalt.internal.tiff.TiffExif;
import com.github.auties00.smalt.internal.tiff.TiffHeader;
import com.github.auties00.smalt.internal.tiff.TiffInput;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Reads EXIF metadata blocks and rewrites their orientation.
 *
 * <p>An EXIF block is a TIFF structure: a byte-order mark ({@code II} for little endian,
 * {@code MM} for big endian), the magic number 42, and the offset of the first image file
 * directory (IFD0). IFD0 may link to the Exif private directory (tag
 * {@link ExifData#EXIF_IFD_POINTER}) and to the GPS directory (tag
 * {@link ExifData#GPS_IFD_POINTER}). Every offset is relative to the start of the block, which is
 * exactly what a JPEG {@code APP1 Exif} segment, a WebP {@code EXIF} chunk, and a HEIF
 * {@code Exif} item carry after their own headers.
 *
 * <p>Every byte of the block is untrusted. Offsets and counts are checked against the block length
 * before they are followed, each directory is visited at most once, and the total size of the
 * decoded values is bounded by a small multiple of the block length, so a hostile block cannot
 * make the reader loop, overflow, or allocate more than a few times its own size.
 *
 * <p>This class is thread-safe.
 */
public final class ExifReader {
    /**
     * The size in bytes of the TIFF header.
     */
    static final int HEADER_SIZE = 8;

    /**
     * The size in bytes of one directory entry.
     */
    static final int ENTRY_SIZE = 12;

    /**
     * The TIFF magic number following the byte-order mark.
     */
    static final int TIFF_MAGIC = 42;

    /**
     * The minimum budget, in bytes, for the decoded values of one block.
     */
    private static final long MIN_VALUE_BUDGET = 1 << 16;

    /**
     * The directory policy of an EXIF block: any declared entry count, directories cut off by the
     * end of the block clamped to their whole entries, entries of unknown types skipped; the value
     * budget bounds the values.
     */
    private static final Ifd.Policy POLICY = new Ifd.Policy(0xFFFF, true, 0, Long.MAX_VALUE);

    /**
     * Prevents instantiation.
     */
    private ExifReader() {
        throw new AssertionError();
    }

    /**
     * Returns the display orientation recorded in the IFD0 {@code Orientation} entry of a block.
     *
     * <p>The entry is accepted with type {@code SHORT} or {@code LONG}; any other type, a missing
     * entry, a value outside 1..8, and a block that is not a readable TIFF structure all yield
     * {@link Orientation#NORMAL}. This method never throws.
     *
     * @param tiff the EXIF block, starting with its byte-order mark; may be {@code null}
     * @return the orientation, {@link Orientation#NORMAL} when it cannot be determined
     */
    public static Orientation orientation(byte[] tiff) {
        if (tiff == null) {
            return Orientation.NORMAL;
        }
        try {
            return TiffExif.orientation(tiff, 0);
        } catch (RuntimeException exception) {
            return Orientation.NORMAL;
        }
    }

    /**
     * Parses the IFD0, Exif, GPS, interoperability and thumbnail (IFD1) directories of a block,
     * and the thumbnail IFD1 points at.
     *
     * <p>Entries whose type is not one of {@link ExifValue.Type}, whose value lies outside the
     * block, or whose value would exceed the decoding budget are skipped; a repeated tag keeps its
     * first value. A pointer to a directory that is out of range, or that points at a directory
     * already read, yields an empty map for that directory. The thumbnail is the JPEG file IFD1's
     * {@code JPEGInterchangeFormat} entries locate or, when IFD1 describes an uncompressed
     * thumbnail ({@link ExifData#hasUncompressedThumbnail()}), its strips concatenated in order; a
     * thumbnail any part of which lies outside the block is ignored. Directories chained after IFD1
     * are not read.
     *
     * @param tiff the EXIF block, starting with its byte-order mark
     * @return the parsed directories
     * @throws MediaException.Malformed if the block has no valid TIFF header or its IFD0 offset
     * lies outside the block
     */
    public static ExifData parse(byte[] tiff) {
        Objects.requireNonNull(tiff, "tiff");
        var header = header(tiff);
        if (header == null) {
            throw new MediaException.Malformed("not a TIFF structure: missing byte-order mark or magic number");
        }
        var ifd0Offset = header.firstIfd();
        if (ifd0Offset < HEADER_SIZE || ifd0Offset + 2 > tiff.length) {
            throw new MediaException.Malformed("IFD0 offset " + ifd0Offset + " lies outside the "
                    + tiff.length + " byte EXIF block");
        }
        var input = TiffInput.of(tiff);
        var budget = new long[]{Math.max(MIN_VALUE_BUDGET, 4L * tiff.length)};
        var first = Ifd.read(input, 0, header, ifd0Offset, POLICY);
        var ifd0 = readDirectory(tiff, first, budget);
        var exifOffset = pointer(ifd0.get(ExifData.EXIF_IFD_POINTER));
        var gpsOffset = pointer(ifd0.get(ExifData.GPS_IFD_POINTER));
        Map<Integer, ExifValue> exif = Map.of();
        if (validDirectory(tiff, exifOffset) && exifOffset != ifd0Offset) {
            exif = readDirectory(tiff, Ifd.read(input, 0, header, exifOffset, POLICY), budget);
        }
        Map<Integer, ExifValue> gps = Map.of();
        if (validDirectory(tiff, gpsOffset) && gpsOffset != ifd0Offset && gpsOffset != exifOffset) {
            gps = readDirectory(tiff, Ifd.read(input, 0, header, gpsOffset, POLICY), budget);
        }
        var interopOffset = pointer(exif.get(ExifData.INTEROP_IFD_POINTER));
        Map<Integer, ExifValue> interop = Map.of();
        if (validDirectory(tiff, interopOffset) && interopOffset != ifd0Offset && interopOffset != exifOffset
                && interopOffset != gpsOffset) {
            interop = readDirectory(tiff, Ifd.read(input, 0, header, interopOffset, POLICY), budget);
        }
        var ifd1Offset = first.next() == 0 ? -1 : first.next();
        Map<Integer, ExifValue> ifd1 = Map.of();
        Optional<byte[]> thumbnail = Optional.empty();
        if (validDirectory(tiff, ifd1Offset) && ifd1Offset != ifd0Offset && ifd1Offset != exifOffset
                && ifd1Offset != gpsOffset && ifd1Offset != interopOffset) {
            ifd1 = readDirectory(tiff, Ifd.read(input, 0, header, ifd1Offset, POLICY), budget);
            if (ExifData.describesStrips(ifd1)) {
                thumbnail = Optional.ofNullable(strips(tiff, ifd1));
            } else {
                var start = pointer(ifd1.get(ExifData.THUMBNAIL_OFFSET));
                var length = pointer(ifd1.get(ExifData.THUMBNAIL_LENGTH));
                if (start >= HEADER_SIZE && length > 0 && start + length <= tiff.length) {
                    thumbnail = Optional.of(Arrays.copyOfRange(tiff, (int) start, (int) (start + length)));
                }
            }
        }
        return new ExifData(ifd0, exif, gps, interop, ifd1, thumbnail);
    }

    /**
     * Returns the strip ranges of an uncompressed thumbnail.
     *
     * @param tiff the block
     * @param offsets the {@code StripOffsets} entry, or {@code null}
     * @param counts the {@code StripByteCounts} entry, or {@code null}
     * @return the offset and length of each strip, in order, or {@code null} when the entries are
     * absent, differ in count, or a strip is empty or lies outside the block
     */
    static long[][] stripRanges(byte[] tiff, ExifValue offsets, ExifValue counts) {
        if (!(offsets instanceof ExifValue.Integers starts) || !(counts instanceof ExifValue.Integers lengths)
                || starts.count() == 0 || starts.count() != lengths.count()) {
            return null;
        }
        var result = new long[starts.count()][];
        var total = 0L;
        for (var i = 0; i < result.length; i++) {
            var start = starts.get(i);
            var length = lengths.get(i);
            if (start < HEADER_SIZE || length <= 0 || start + length > tiff.length) {
                return null;
            }
            total += length;
            result[i] = new long[]{start, length};
        }
        return total <= tiff.length ? result : null;
    }

    /**
     * Concatenates the strips of an uncompressed thumbnail.
     *
     * @param tiff the block
     * @param ifd1 the thumbnail directory
     * @return the strip data, or {@code null} when a strip lies outside the block
     */
    private static byte[] strips(byte[] tiff, Map<Integer, ExifValue> ifd1) {
        var ranges = stripRanges(tiff, ifd1.get(ExifData.STRIP_OFFSETS), ifd1.get(ExifData.STRIP_BYTE_COUNTS));
        if (ranges == null) {
            return null;
        }
        var total = 0;
        for (var range : ranges) {
            total += (int) range[1];
        }
        var result = new byte[total];
        var at = 0;
        for (var range : ranges) {
            System.arraycopy(tiff, (int) range[0], result, at, (int) range[1]);
            at += (int) range[1];
        }
        return result;
    }

    /**
     * Returns the offset of the directory chained after another.
     *
     * @param tiff the block
     * @param offset the offset of the directory's entry count, known to lie inside the block
     * @param little whether the block is little endian
     * @return the offset of the next directory, or {@code -1} when there is none or the pointer
     * lies outside the block
     */
    static long nextDirectory(byte[] tiff, int offset, boolean little) {
        var declared = u16(tiff, offset, little);
        var at = (long) offset + 2 + (long) declared * ENTRY_SIZE;
        if (at + 4 > tiff.length) {
            return -1;
        }
        var next = u32(tiff, (int) at, little);
        return next == 0 ? -1 : next;
    }

    /**
     * Returns a block equivalent to {@code tiff} whose IFD0 {@code Orientation} entry holds the
     * given orientation.
     *
     * <p>The input array is never modified. When IFD0 already has an {@code Orientation} entry of
     * type {@code SHORT} or {@code LONG}, the result is a copy with that value rewritten in place;
     * an entry of any other type is rewritten in place as a single {@code SHORT}. When IFD0 has no
     * such entry, the result is a copy with a new IFD0 appended at its end, holding the original
     * entries plus the orientation, and the header pointing at it; every other offset in the block
     * stays valid because nothing moves. When {@code tiff} is not a readable TIFF structure, the
     * result is a new minimal big-endian block holding only the orientation.
     *
     * @param tiff the EXIF block, starting with its byte-order mark
     * @param orientation the orientation to record
     * @return the rewritten block
     */
    static byte[] withOrientation(byte[] tiff, Orientation orientation) {
        Objects.requireNonNull(tiff, "tiff");
        Objects.requireNonNull(orientation, "orientation");
        var code = orientation.exifCode();
        try {
            var order = byteOrder(tiff);
            if (order == 0) {
                return minimalBlock(code);
            }
            var little = order == 1;
            var ifd = u32(tiff, 4, little);
            if (ifd < HEADER_SIZE || ifd + 2 > tiff.length) {
                return minimalBlock(code);
            }
            var entries = availableEntries(tiff, (int) ifd, little);
            for (var i = 0; i < entries; i++) {
                var entry = (int) ifd + 2 + i * ENTRY_SIZE;
                if (u16(tiff, entry, little) == ExifData.ORIENTATION) {
                    return rewriteEntry(tiff, entry, little, code);
                }
            }
            return appendDirectory(tiff, (int) ifd, entries, little, code);
        } catch (RuntimeException exception) {
            return minimalBlock(code);
        }
    }

    /**
     * Rewrites an existing {@code Orientation} entry in a copy of the block.
     *
     * @param tiff the block
     * @param entry the index of the entry's first byte
     * @param little whether the block is little endian
     * @param code the EXIF orientation code to store
     * @return the rewritten copy
     */
    private static byte[] rewriteEntry(byte[] tiff, int entry, boolean little, int code) {
        var copy = tiff.clone();
        var type = u16(tiff, entry + 2, little);
        var count = u32(tiff, entry + 4, little);
        if (type == ExifValue.Type.SHORT.code() && count >= 1) {
            var at = count <= 2 ? entry + 8 : u32(tiff, entry + 8, little);
            if (at >= 0 && at + 2 <= tiff.length) {
                put16(copy, (int) at, code, little);
                return copy;
            }
        } else if (type == ExifValue.Type.LONG.code() && count >= 1) {
            var at = count <= 1 ? entry + 8 : u32(tiff, entry + 8, little);
            if (at >= 0 && at + 4 <= tiff.length) {
                put32(copy, (int) at, code, little);
                return copy;
            }
        }
        writeOrientationEntry(copy, entry, little, code);
        return copy;
    }

    /**
     * Appends a copy of IFD0 extended with an {@code Orientation} entry and points the header at
     * it.
     *
     * @param tiff the block
     * @param ifd the offset of the original IFD0
     * @param entries the number of original entries that lie inside the block
     * @param little whether the block is little endian
     * @param code the EXIF orientation code to store
     * @return the extended copy
     */
    private static byte[] appendDirectory(byte[] tiff, int ifd, int entries, boolean little, int code) {
        if (entries >= 0xFFFF) {
            return minimalBlock(code);
        }
        var declared = u16(tiff, ifd, little);
        var start = (tiff.length + 1) & ~1;
        var length = (long) start + 2 + (long) (entries + 1) * ENTRY_SIZE + 4;
        if (length > Integer.MAX_VALUE - 16) {
            return minimalBlock(code);
        }
        var result = new byte[(int) length];
        System.arraycopy(tiff, 0, result, 0, tiff.length);
        put16(result, start, entries + 1, little);
        var out = start + 2;
        var inserted = false;
        for (var i = 0; i < entries; i++) {
            var entry = ifd + 2 + i * ENTRY_SIZE;
            if (!inserted && u16(tiff, entry, little) > ExifData.ORIENTATION) {
                writeOrientationEntry(result, out, little, code);
                out += ENTRY_SIZE;
                inserted = true;
            }
            System.arraycopy(tiff, entry, result, out, ENTRY_SIZE);
            out += ENTRY_SIZE;
        }
        if (!inserted) {
            writeOrientationEntry(result, out, little, code);
            out += ENTRY_SIZE;
        }
        var nextPointer = ifd + 2 + (long) declared * ENTRY_SIZE;
        var next = entries == declared && nextPointer + 4 <= tiff.length ? u32(tiff, (int) nextPointer, little) : 0;
        put32(result, out, next, little);
        put32(result, 4, start, little);
        return result;
    }

    /**
     * Returns a new big-endian block whose IFD0 holds only an {@code Orientation} entry.
     *
     * @param code the EXIF orientation code to store
     * @return the block
     */
    private static byte[] minimalBlock(int code) {
        var block = new byte[HEADER_SIZE + 2 + ENTRY_SIZE + 4];
        block[0] = 'M';
        block[1] = 'M';
        put16(block, 2, TIFF_MAGIC, false);
        put32(block, 4, HEADER_SIZE, false);
        put16(block, HEADER_SIZE, 1, false);
        writeOrientationEntry(block, HEADER_SIZE + 2, false, code);
        return block;
    }

    /**
     * Writes a single-{@code SHORT} {@code Orientation} entry.
     *
     * @param block the destination
     * @param entry the index of the entry's first byte
     * @param little whether the block is little endian
     * @param code the EXIF orientation code to store
     */
    private static void writeOrientationEntry(byte[] block, int entry, boolean little, int code) {
        put16(block, entry, ExifData.ORIENTATION, little);
        put16(block, entry + 2, ExifValue.Type.SHORT.code(), little);
        put32(block, entry + 4, 1, little);
        put16(block, entry + 8, code, little);
        put16(block, entry + 10, 0, little);
    }

    /**
     * Reads one directory into an order-preserving map.
     *
     * @param tiff the block
     * @param ifd the directory
     * @param budget a one-element array holding the remaining value budget in bytes, decremented
     * by every decoded value
     * @return the entries, keyed by tag
     */
    private static Map<Integer, ExifValue> readDirectory(byte[] tiff, Ifd ifd, long[] budget) {
        var little = ifd.header().little();
        var result = new LinkedHashMap<Integer, ExifValue>();
        for (var i = 0; i < ifd.count(); i++) {
            var tag = ifd.tag(i);
            if (result.containsKey(tag)) {
                continue;
            }
            var type = ExifValue.Type.find(ifd.type(i));
            if (type == null) {
                continue;
            }
            var count = ifd.valueCount(i);
            var size = count * type.size();
            var at = ifd.valuePosition(i);
            if (size > 4 && at + size > tiff.length) {
                continue;
            }
            if (size > budget[0]) {
                continue;
            }
            budget[0] -= Math.max(size, 1);
            result.put(tag, decodeValue(tiff, (int) at, type, (int) count, little));
        }
        return result;
    }

    /**
     * Decodes the value of one entry.
     *
     * @param tiff the block
     * @param at the offset of the first element
     * @param type the field type
     * @param count the number of elements; the value is known to lie inside the block
     * @param little whether the block is little endian
     * @return the decoded value
     */
    private static ExifValue decodeValue(byte[] tiff, int at, ExifValue.Type type, int count, boolean little) {
        return switch (type) {
            case ASCII -> ExifValue.Ascii.decode(tiff, at, count);
            case UTF8 -> ExifValue.Utf8.decode(tiff, at, count);
            case UNDEFINED -> {
                var bytes = new byte[count];
                System.arraycopy(tiff, at, bytes, 0, count);
                yield new ExifValue.Undefined(bytes);
            }
            case BYTE, SBYTE, SHORT, SSHORT, LONG, SLONG, IFD -> {
                var values = new long[count];
                for (var i = 0; i < count; i++) {
                    values[i] = switch (type) {
                        case BYTE -> tiff[at + i] & 0xFF;
                        case SBYTE -> tiff[at + i];
                        case SHORT -> u16(tiff, at + 2 * i, little);
                        case SSHORT -> (short) u16(tiff, at + 2 * i, little);
                        case LONG, IFD -> u32(tiff, at + 4 * i, little);
                        default -> (int) u32(tiff, at + 4 * i, little);
                    };
                }
                yield new ExifValue.Integers(type, values);
            }
            case RATIONAL, SRATIONAL -> {
                var numerators = new long[count];
                var denominators = new long[count];
                for (var i = 0; i < count; i++) {
                    var numerator = u32(tiff, at + 8 * i, little);
                    var denominator = u32(tiff, at + 8 * i + 4, little);
                    numerators[i] = type == ExifValue.Type.SRATIONAL ? (int) numerator : numerator;
                    denominators[i] = type == ExifValue.Type.SRATIONAL ? (int) denominator : denominator;
                }
                yield new ExifValue.Rationals(type, numerators, denominators);
            }
            case FLOAT, DOUBLE -> {
                var values = new double[count];
                for (var i = 0; i < count; i++) {
                    if (type == ExifValue.Type.FLOAT) {
                        values[i] = Float.intBitsToFloat((int) u32(tiff, at + 4 * i, little));
                    } else {
                        var first = u32(tiff, at + 8 * i, little);
                        var second = u32(tiff, at + 8 * i + 4, little);
                        values[i] = Double.longBitsToDouble(little ? (second << 32) | first : (first << 32) | second);
                    }
                }
                yield new ExifValue.Reals(type, values);
            }
        };
    }

    /**
     * Returns the offset held by a directory pointer entry.
     *
     * @param value the pointer entry, or {@code null} when absent
     * @return the offset, or {@code -1} when the entry is absent or not a single integer
     */
    private static long pointer(ExifValue value) {
        if (value instanceof ExifValue.Integers integers && integers.count() >= 1) {
            return integers.get(0);
        }
        return -1;
    }

    /**
     * Returns whether a directory can start at the given offset.
     *
     * @param tiff the block
     * @param offset the candidate offset
     * @return {@code true} when the entry count lies inside the block after the header
     */
    private static boolean validDirectory(byte[] tiff, long offset) {
        return offset >= HEADER_SIZE && offset + 2 <= tiff.length;
    }

    /**
     * Returns how many of the entries a directory declares lie entirely inside the block.
     *
     * @param tiff the block
     * @param offset the offset of the directory's entry count, known to lie inside the block
     * @param little whether the block is little endian
     * @return the number of readable entries
     */
    static int availableEntries(byte[] tiff, int offset, boolean little) {
        var declared = u16(tiff, offset, little);
        var room = (tiff.length - offset - 2) / ENTRY_SIZE;
        return Math.max(0, Math.min(declared, room));
    }

    /**
     * Returns the classic TIFF header a block starts with.
     *
     * @param tiff the block
     * @return the header, or {@code null} when the block does not start with a classic (not
     * BigTIFF) TIFF header
     */
    private static TiffHeader header(byte[] tiff) {
        try {
            var header = TiffHeader.parse(tiff, 0, tiff.length);
            return header == null || header.big() ? null : header;
        } catch (MediaException exception) {
            return null;
        }
    }

    /**
     * Recognizes the TIFF header.
     *
     * @param tiff the block
     * @return {@code 1} for little endian, {@code 2} for big endian, {@code 0} when the block does
     * not start with a classic TIFF header
     */
    static int byteOrder(byte[] tiff) {
        var header = header(tiff);
        return header == null ? 0 : header.little() ? 1 : 2;
    }

    /**
     * {@return an unsigned 16-bit integer, from 0 to 65535}
     *
     * @param data the block
     * @param offset the index of the first byte
     * @param little whether the value is little endian
     */
    static int u16(byte[] data, int offset, boolean little) {
        return ByteArrays.u16(data, offset, little);
    }

    /**
     * {@return an unsigned 32-bit integer, from 0 to {@code 2^32 - 1}}
     *
     * @param data the block
     * @param offset the index of the first byte
     * @param little whether the value is little endian
     */
    static long u32(byte[] data, int offset, boolean little) {
        return ByteArrays.u32(data, offset, little);
    }

    /**
     * Writes a 16-bit integer.
     *
     * @param data the destination
     * @param offset the index of the first byte
     * @param value the value; bits above 16 are ignored
     * @param little whether to write little endian
     */
    static void put16(byte[] data, int offset, int value, boolean little) {
        ByteArrays.put16(data, offset, value, little);
    }

    /**
     * Writes a 32-bit integer.
     *
     * @param data the destination
     * @param offset the index of the first byte
     * @param value the value; bits above 32 are ignored
     * @param little whether to write little endian
     */
    static void put32(byte[] data, int offset, long value, boolean little) {
        ByteArrays.put32(data, offset, value, little);
    }
}
