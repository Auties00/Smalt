package com.github.auties00.smalt.internal.tiff;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.limits.LimitChecks;

import java.util.Arrays;

/**
 * Holds one image file directory of a TIFF structure (TIFF 6.0 section 2): its entries, the
 * offset of the next directory, and bounded access to the entries' values.
 *
 * <p>Offsets inside a directory are relative to a base, the absolute position of the TIFF header
 * in the input: zero for a TIFF file, the header position for an EXIF block or a maker note. The
 * readers of the TIFF family differ in a few policies, which {@link Policy} selects:
 * <ul>
 *   <li>how many entries a directory may declare;</li>
 *   <li>whether a directory cut off by the end of the input keeps its whole entries or is
 *       rejected;</li>
 *   <li>the size of a value of an unknown field type: 0, which makes the entry unusable and
 *       hides it from {@link #find(int)}, or 1, which reads it as bytes.</li>
 * </ul>
 * A next-directory pointer cut off by the end of the input reads as 0.
 *
 * <p>Instances are immutable; reading the values outside the directory is as thread-safe as the
 * input.
 */
public final class Ifd {
    /**
     * The input the directory was read from.
     */
    private final TiffInput input;

    /**
     * The absolute position of the TIFF header the offsets are relative to.
     */
    private final long base;

    /**
     * The header of the structure.
     */
    private final TiffHeader header;

    /**
     * The offset of the directory, relative to {@link #base}.
     */
    private final long offset;

    /**
     * The raw entries.
     */
    private final byte[] entries;

    /**
     * The number of usable entries.
     */
    private final int count;

    /**
     * The offset of the next directory, or 0.
     */
    private final long next;

    /**
     * The policy the directory was read with.
     */
    private final Policy policy;

    /**
     * Constructs a directory over its raw entries.
     *
     * @param input the bytes the directory is read from
     * @param base the absolute position of the TIFF header
     * @param header the header of the structure
     * @param offset the offset of the directory
     * @param entries the raw entries
     * @param count the number of usable entries
     * @param next the offset of the next directory, or 0
     * @param policy the policy of the reader
     */
    private Ifd(TiffInput input, long base, TiffHeader header, long offset, byte[] entries, int count, long next,
                Policy policy) {
        this.input = input;
        this.base = base;
        this.header = header;
        this.offset = offset;
        this.entries = entries;
        this.count = count;
        this.next = next;
        this.policy = policy;
    }

    /**
     * Selects how a reader of the TIFF family treats the cases its format leaves open.
     *
     * @param maxEntries the largest entry count a directory may declare
     * @param clampTruncated whether a directory cut off by the end of the input keeps its whole
     * entries rather than being rejected
     * @param unknownTypeSize the size of a value of an unknown field type: 0 or 1
     * @param maxValueBytes the largest value {@link Ifd#value(int, MediaLimits)} reads, in bytes, on top of
     * {@link MediaLimits#maxBytes()}
     */
    public record Policy(int maxEntries, boolean clampTruncated, int unknownTypeSize, long maxValueBytes) {
        /**
         * The policy of TIFF image files: up to 65536 entries, truncated directories rejected,
         * unknown types ignored, values bounded only by the limits.
         */
        public static final Policy STRICT = new Policy(1 << 16, false, 0, Long.MAX_VALUE);

        /**
         * The policy of camera raw files and EXIF blocks: up to 4096 entries, truncated
         * directories clamped to their whole entries, unknown types read as bytes, values of at
         * most 256 MiB.
         */
        public static final Policy LENIENT = new Policy(4096, true, 1, 256L << 20);

        /**
         * Checks the policy.
         *
         * @throws IllegalArgumentException if {@code maxEntries} or {@code maxValueBytes} is not
         * positive or {@code unknownTypeSize} is neither 0 nor 1
         */
        public Policy {
            if (maxEntries <= 0 || maxValueBytes <= 0 || (unknownTypeSize != 0 && unknownTypeSize != 1)) {
                throw new IllegalArgumentException("invalid TIFF policy");
            }
        }
    }

    /**
     * Reads a directory.
     *
     * @param input the bytes the directory is read from
     * @param base the absolute position of the TIFF header
     * @param header the header of the structure
     * @param offset the offset of the directory, relative to {@code base}
     * @param policy the policy of the reader
     * @return the directory
     * @throws MediaException.Malformed if the offset is negative or points past the input, the
     * count exceeds the policy's maximum, or the directory is truncated and the policy rejects
     * that
     */
    public static Ifd read(TiffInput input, long base, TiffHeader header, long offset, Policy policy) {
        var countSize = header.big() ? 8 : 2;
        var entrySize = header.entrySize();
        var position = base + offset;
        var size = input.size();
        if (offset < 0 || position < 0 || (size >= 0 && position + countSize > size)) {
            throw new MediaException.Malformed("TIFF directory offset " + offset + " is outside the input");
        }
        var countBytes = new byte[countSize];
        if (input.readAvailable(position, countBytes, 0, countSize) < countSize) {
            throw new MediaException.Malformed("truncated TIFF directory");
        }
        var declared = header.big() ? int64(countBytes, 0, header.little())
                : ByteArrays.u16(countBytes, 0, header.little());
        if (declared < 0 || declared > policy.maxEntries()) {
            throw new MediaException.Malformed("TIFF directory declares " + declared + " entries");
        }
        var count = (int) declared;
        if (policy.clampTruncated() && size >= 0) {
            count = (int) Math.min(count, Math.max(0, (size - position - countSize) / entrySize));
        }
        var pointerSize = header.fieldSize();
        var bytes = new byte[count * entrySize + pointerSize];
        var read = input.readAvailable(position + countSize, bytes, 0, bytes.length);
        var whole = Math.min(count, read / entrySize);
        if (whole < count && !policy.clampTruncated()) {
            throw new MediaException.Malformed("truncated TIFF directory");
        }
        var next = 0L;
        if (whole == declared && read == bytes.length) {
            var at = whole * entrySize;
            next = header.big() ? int64(bytes, at, header.little()) : ByteArrays.u32(bytes, at, header.little());
        }
        return new Ifd(input, base, header, offset, bytes, whole, next, policy);
    }

    /**
     * {@return the offset of the directory, relative to the base}
     */
    public long offset() {
        return offset;
    }

    /**
     * {@return the absolute position of the TIFF header the offsets are relative to}
     */
    public long base() {
        return base;
    }

    /**
     * {@return the header of the structure}
     */
    public TiffHeader header() {
        return header;
    }

    /**
     * {@return the offset of the next directory relative to the base, or 0 at the end of the
     * chain or when the pointer is cut off}
     */
    public long next() {
        return next;
    }

    /**
     * {@return the number of usable entries}
     */
    public int count() {
        return count;
    }

    /**
     * {@return the tag of an entry}
     *
     * @param index the entry index, from 0 to {@link #count()} minus one
     */
    public int tag(int index) {
        return ByteArrays.u16(entries, index * header.entrySize(), header.little());
    }

    /**
     * {@return the field type code of an entry}
     *
     * @param index the entry index
     */
    public int type(int index) {
        return ByteArrays.u16(entries, index * header.entrySize() + 2, header.little());
    }

    /**
     * {@return the number of values of an entry, negative when a BigTIFF count exceeds
     * {@link Long#MAX_VALUE}}
     *
     * @param index the entry index
     */
    public long valueCount(int index) {
        var at = index * header.entrySize() + 4;
        return header.big() ? int64(entries, at, header.little()) : ByteArrays.u32(entries, at, header.little());
    }

    /**
     * Returns the size of an entry's values.
     *
     * @param index the entry index
     * @return the size in bytes, or {@code -1} when the type is unknown and the policy ignores
     * unknown types, or the size overflows
     */
    public long byteLength(int index) {
        var size = TiffTypes.size(type(index));
        if (size == 0) {
            size = policy.unknownTypeSize();
        }
        var values = valueCount(index);
        if (size == 0 || values < 0 || values > Long.MAX_VALUE / 8) {
            return -1;
        }
        return values * size;
    }

    /**
     * {@return whether an entry's values fit in its value field}
     *
     * @param index the entry index
     */
    public boolean isInline(int index) {
        var length = byteLength(index);
        return length >= 0 && length <= header.fieldSize();
    }

    /**
     * Returns where an entry's values are.
     *
     * @param index the entry index
     * @return the absolute position of the values in the input: inside the directory when they
     * are inline, otherwise the base plus the entry's offset field
     */
    public long valuePosition(int index) {
        if (isInline(index)) {
            return base + offset + (header.big() ? 8 : 2) + fieldIndex(index);
        }
        return base + valueOffset(index);
    }

    /**
     * {@return the offset field of an entry, relative to the base}
     *
     * @param index the entry index
     */
    public long valueOffset(int index) {
        var at = fieldIndex(index);
        return header.big() ? int64(entries, at, header.little()) : ByteArrays.u32(entries, at, header.little());
    }

    /**
     * Returns the index of the first entry with a tag.
     *
     * @param tag the tag to look for
     * @return the entry index, or {@code -1} when no entry has the tag (entries of an unknown type
     * do not count when the policy ignores unknown types)
     */
    public int find(int tag) {
        for (var index = 0; index < count; index++) {
            if (tag(index) == tag && (policy.unknownTypeSize() != 0 || TiffTypes.size(type(index)) != 0)) {
                return index;
            }
        }
        return -1;
    }

    /**
     * Reads the values of an entry.
     *
     * @param index the entry index
     * @param limits the bounds of the allocation
     * @return the value bytes, in the structure's byte order
     * @throws MediaException.Malformed if the entry's type is unusable, its size overflows, or its
     * values lie outside the input or are cut off
     * @throws MediaException.LimitExceeded if the values exceed the policy's maximum
     */
    public byte[] value(int index, MediaLimits limits) {
        var length = byteLength(index);
        if (length < 0) {
            throw new MediaException.Malformed("TIFF tag " + tag(index) + " has an invalid value count");
        }
        if (length <= header.fieldSize()) {
            var at = fieldIndex(index);
            return Arrays.copyOfRange(entries, at, at + (int) length);
        }
        var position = base + valueOffset(index);
        var size = input.size();
        if (valueOffset(index) < 0 || position < 0 || (size >= 0 && (position > size || length > size - position))) {
            throw new MediaException.Malformed("TIFF tag " + tag(index) + " points past the end of the input");
        }
        if (length > policy.maxValueBytes()) {
            throw new MediaException.LimitExceeded("TIFF tag " + tag(index) + " holds " + length + " bytes");
        }
        LimitChecks.checkAllocation(limits, length);
        var data = new byte[(int) length];
        if (input.readAvailable(position, data, 0, data.length) < data.length) {
            throw new MediaException.Malformed("TIFF tag " + tag(index) + " is truncated");
        }
        return data;
    }

    /**
     * Reads the values of an integer entry.
     *
     * @param index the entry index
     * @param limits the bounds of the allocation
     * @return the values, signed types sign-extended
     * @throws MediaException.Malformed if the entry is not of an integer type or its values cannot
     * be read
     * @throws MediaException.LimitExceeded if the values exceed the limits
     */
    public long[] integers(int index, MediaLimits limits) {
        var type = type(index);
        if (!TiffTypes.isInteger(type)) {
            throw new MediaException.Malformed("TIFF tag " + tag(index) + " has non-integer type " + type);
        }
        var values = valueCount(index);
        if (values < 0 || values > Integer.MAX_VALUE / 8) {
            throw new MediaException.LimitExceeded("TIFF tag " + tag(index) + " has " + values + " values");
        }
        LimitChecks.checkAllocation(limits, values * 8);
        var data = value(index, limits);
        var size = TiffTypes.size(type);
        var result = new long[(int) values];
        for (var i = 0; i < result.length; i++) {
            result[i] = TiffTypes.integer(data, i * size, type, header.little());
        }
        return result;
    }

    /**
     * Reads the first value of an integer entry.
     *
     * @param index the entry index
     * @param fallback the value returned for an entry without values
     * @return the first value, signed types sign-extended
     * @throws MediaException.Malformed if the entry is not of an integer type, its values lie outside the input,
     * or its first value cannot be read
     */
    public long firstInteger(int index, long fallback) {
        var type = type(index);
        if (!TiffTypes.isInteger(type)) {
            throw new MediaException.Malformed("TIFF tag " + tag(index) + " has non-integer type " + type);
        }
        if (valueCount(index) == 0) {
            return fallback;
        }
        var size = TiffTypes.size(type);
        if (isInline(index)) {
            return TiffTypes.integer(entries, fieldIndex(index), type, header.little());
        }
        // the whole value list must lie in the input, as value() requires: a count that runs past the end makes
        // the offset as untrustworthy as the count
        var position = base + valueOffset(index);
        var length = byteLength(index);
        var inputSize = input.size();
        if (length < 0 || valueOffset(index) < 0 || position < 0
                || (inputSize >= 0 && (position > inputSize || length > inputSize - position))) {
            throw new MediaException.Malformed("TIFF tag " + tag(index) + " points past the end of the input");
        }
        var data = new byte[size];
        if (input.readAvailable(position, data, 0, size) < size) {
            throw new MediaException.Malformed("TIFF tag " + tag(index) + " is truncated");
        }
        return TiffTypes.integer(data, 0, type, header.little());
    }

    /**
     * {@return a 64-bit value in either byte order}
     *
     * @param data the bytes
     * @param at the index of the first byte
     * @param little whether the value is little endian
     */
    private static long int64(byte[] data, int at, boolean little) {
        return little ? ByteArrays.i64le(data, at) : ByteArrays.i64be(data, at);
    }

    /**
     * Returns the index of an entry's value field in the raw entries.
     *
     * @param index the entry index
     * @return the index of the first byte of the field
     */
    private int fieldIndex(int index) {
        return index * header.entrySize() + (header.big() ? 12 : 8);
    }
}
