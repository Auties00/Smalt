package com.github.auties00.smalt.image.tiff.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.tiff.Ifd;
import com.github.auties00.smalt.internal.tiff.TiffTypes;

import java.nio.charset.StandardCharsets;

/**
 * Holds one image file directory of a {@link TiffFile} and reads its values by tag.
 *
 * <p>Values are read on demand, from the directory's own bytes when they fit in the entry's value
 * field and from the file otherwise. Integer accessors accept every integer field type (signed
 * types are sign-extended), real accessors accept every numeric type. Entries of an unknown field
 * type are ignored, and when a tag appears twice the first entry wins. Instances are immutable;
 * reading values outside the directory is as thread-safe as the file's source.
 */
public final class TiffDirectory {
    /**
     * The file the directory belongs to.
     */
    private final TiffFile file;

    /**
     * The entries.
     */
    private final Ifd ifd;

    /**
     * Constructs a directory over the entries read from a file.
     *
     * @param file the file the directory was read from
     * @param ifd the entries, read with the file's header at base 0
     */
    TiffDirectory(TiffFile file, Ifd ifd) {
        this.file = file;
        this.ifd = ifd;
    }

    /**
     * {@return the file the directory belongs to}
     */
    public TiffFile file() {
        return file;
    }

    /**
     * {@return the absolute offset of the directory}
     */
    public long offset() {
        return ifd.offset();
    }

    /**
     * {@return the offset of the next directory in the chain, or 0 at the end of the chain}
     */
    public long next() {
        return ifd.next();
    }

    /**
     * {@return the number of entries}
     */
    public int count() {
        return ifd.count();
    }

    /**
     * {@return the tag of an entry}
     *
     * @param index the entry index
     */
    public int tag(int index) {
        return ifd.tag(index);
    }

    /**
     * {@return the field type code of an entry}
     *
     * @param index the entry index
     */
    public int type(int index) {
        return ifd.type(index);
    }

    /**
     * {@return the number of values of an entry, negative when a BigTIFF count exceeds
     * {@link Long#MAX_VALUE}}
     *
     * @param index the entry index
     */
    public long valueCount(int index) {
        return ifd.valueCount(index);
    }

    /**
     * {@return whether an entry's values fit in its value field}
     *
     * @param index the entry index
     */
    public boolean isInline(int index) {
        return ifd.isInline(index);
    }

    /**
     * {@return the absolute offset an entry's value field points to, meaningful when its values
     * are stored outside the entry}
     *
     * @param index the entry index
     */
    public long valueOffset(int index) {
        return ifd.valueOffset(index);
    }

    /**
     * {@return the size of an entry's values in bytes, or -1 for an unknown type or an
     * overflowing count}
     *
     * @param index the entry index
     */
    public long byteLength(int index) {
        return ifd.byteLength(index);
    }

    /**
     * {@return the index of the first entry with a tag and a known type, or -1}
     *
     * @param tag the tag number
     */
    public int find(int tag) {
        return ifd.find(tag);
    }

    /**
     * {@return whether the directory has an entry of a known type for a tag}
     *
     * @param tag the tag number
     */
    public boolean has(int tag) {
        return find(tag) >= 0;
    }

    /**
     * Returns the raw values of an entry, in the file's byte order.
     *
     * @param index the entry index
     * @return the bytes
     * @throws MediaException.Malformed if the values are truncated or out of bounds
     * @throws MediaException.LimitExceeded if the values exceed the byte limit
     */
    public byte[] raw(int index) {
        return ifd.value(index, file.limits());
    }

    /**
     * Returns the raw values of a tag.
     *
     * @param tag the tag number
     * @return the bytes in the file's byte order, or {@code null} when the tag is absent
     * @throws MediaException.Malformed if the values are truncated or out of bounds
     * @throws MediaException.LimitExceeded if the values exceed the byte limit
     */
    public byte[] bytes(int tag) {
        var index = find(tag);
        return index < 0 ? null : raw(index);
    }

    /**
     * Returns the integer values of a tag.
     *
     * @param tag the tag number
     * @return the values, or {@code null} when the tag is absent
     * @throws MediaException.Malformed if the tag has a non-integer type or truncated values
     * @throws MediaException.LimitExceeded if the values exceed the byte limit
     */
    public long[] longs(int tag) {
        var index = find(tag);
        if (index < 0) {
            return null;
        }
        checkCount(index);
        return ifd.integers(index, file.limits());
    }

    /**
     * Returns the first integer value of a tag.
     *
     * @param tag the tag number
     * @param fallback the value returned when the tag is absent or has no values
     * @return the value
     * @throws MediaException.Malformed if the tag has a non-integer type or truncated values
     */
    public long longValue(int tag, long fallback) {
        var index = find(tag);
        if (index < 0 || valueCount(index) == 0) {
            return fallback;
        }
        return ifd.firstInteger(index, fallback);
    }

    /**
     * Returns the numeric values of a tag as reals.
     *
     * @param tag the tag number
     * @return the values, or {@code null} when the tag is absent
     * @throws MediaException.Malformed if the tag has a non-numeric type or truncated values
     * @throws MediaException.LimitExceeded if the values exceed the byte limit
     */
    public double[] doubles(int tag) {
        var index = find(tag);
        if (index < 0) {
            return null;
        }
        var type = type(index);
        checkCount(index);
        var data = raw(index);
        var size = TiffTypes.size(type);
        var little = file.little();
        var result = new double[(int) valueCount(index)];
        for (var i = 0; i < result.length; i++) {
            try {
                result[i] = TiffTypes.number(data, i * size, type, little);
            } catch (IllegalArgumentException exception) {
                throw new MediaException.Malformed("TIFF tag " + tag + " has non-numeric type " + type);
            }
        }
        return result;
    }

    /**
     * Returns the first numeric value of a tag as a real.
     *
     * @param tag the tag number
     * @param fallback the value returned when the tag is absent or has no values
     * @return the value
     * @throws MediaException.Malformed if the tag has a non-numeric type or truncated values
     */
    public double doubleValue(int tag, double fallback) {
        var values = doubles(tag);
        return values == null || values.length == 0 ? fallback : values[0];
    }

    /**
     * Returns the text of an ASCII, BYTE or UNDEFINED tag, up to its first NUL.
     *
     * @param tag the tag number
     * @return the text decoded as UTF-8, or {@code null} when the tag is absent or not text
     * @throws MediaException.Malformed if the values are truncated
     */
    public String string(int tag) {
        var index = find(tag);
        if (index < 0 || (type(index) != TiffTypes.ASCII && type(index) != TiffTypes.BYTE
                && type(index) != TiffTypes.UNDEFINED)) {
            return null;
        }
        var data = raw(index);
        var end = 0;
        while (end < data.length && data[end] != 0) {
            end++;
        }
        return new String(data, 0, end, StandardCharsets.UTF_8);
    }

    /**
     * Checks that the values of an entry can be held in an array of longs or doubles.
     *
     * @param index the entry index
     * @throws MediaException.Malformed if the count is negative
     * @throws MediaException.LimitExceeded if the array would exceed the byte limit
     */
    private void checkCount(int index) {
        var values = valueCount(index);
        if (values > Integer.MAX_VALUE / 8) {
            throw new MediaException.LimitExceeded("TIFF tag " + tag(index) + " has " + values + " values");
        }
        LimitChecks.checkAllocation(file.limits(), values * 8);
    }
}
