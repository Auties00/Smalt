package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.tiff.TiffTypes;

import java.nio.charset.StandardCharsets;

/**
 * Holds one image file directory of a raw file, with its values looked up by tag.
 *
 * <p>Numeric accessors accept every integer, rational and floating-point TIFF type, so a tag one
 * writer stores as {@code SHORT} and another as {@code LONG} or {@code RATIONAL} reads the same;
 * {@code ASCII} values read as bytes, and values of an unknown type read as 0.
 */
public final class Ifd {
    /**
     * The reader the directory came from.
     */
    private final TiffReader reader;

    /**
     * The directory, read with the lenient policy.
     */
    private final com.github.auties00.smalt.internal.tiff.Ifd directory;

    /**
     * Constructs a view of a directory.
     *
     * @param reader the reader the directory came from
     * @param directory the directory, read with the lenient policy
     */
    Ifd(TiffReader reader, com.github.auties00.smalt.internal.tiff.Ifd directory) {
        this.reader = reader;
        this.directory = directory;
    }

    /**
     * {@return the reader the directory came from}
     */
    public TiffReader reader() {
        return reader;
    }

    /**
     * {@return the offset of the directory, relative to the reader's base}
     */
    public long offset() {
        return directory.offset();
    }

    /**
     * {@return the offset of the next directory relative to the reader's base, or 0 at the end of
     * the chain}
     */
    public long next() {
        return directory.next();
    }

    /**
     * {@return the number of entries}
     */
    public int count() {
        return directory.count();
    }

    /**
     * {@return the tag of an entry}
     *
     * @param index the entry index, from 0 to {@link #count()} minus one
     */
    public int tagAt(int index) {
        return directory.tag(index);
    }

    /**
     * Finds the entry of a tag.
     *
     * @param tag the tag to look for
     * @return the index of its first entry, or {@code -1}
     */
    public int find(int tag) {
        return directory.find(tag);
    }

    /**
     * {@return whether an entry has a tag}
     *
     * @param tag the tag to look for
     */
    public boolean has(int tag) {
        return find(tag) >= 0;
    }

    /**
     * {@return the type code of a tag's entry, or 0 when the tag is absent}
     *
     * @param tag the tag to look for
     */
    public int type(int tag) {
        var index = find(tag);
        return index < 0 ? 0 : directory.type(index);
    }

    /**
     * {@return the value count of a tag's entry, or 0 when the tag is absent}
     *
     * @param tag the tag to look for
     */
    public long valueCount(int tag) {
        var index = find(tag);
        return index < 0 ? 0 : directory.valueCount(index);
    }

    /**
     * {@return the absolute offset of a tag's value, inside the directory when it is inline, or
     * {@code -1} when the tag is absent}
     *
     * @param tag the tag to look for
     */
    public long valuePosition(int tag) {
        var index = find(tag);
        return index < 0 ? -1 : directory.valuePosition(index);
    }

    /**
     * Returns the raw bytes of a tag's value, in the file's byte order.
     *
     * @param tag the tag to look for
     * @return the bytes, or {@code null} when the tag is absent
     * @throws MediaException.Malformed if the value lies outside the source
     * @throws MediaException.LimitExceeded if the value exceeds the reader's limits
     */
    public byte[] bytes(int tag) {
        var index = find(tag);
        return index < 0 ? null : directory.value(index, reader.limits());
    }

    /**
     * Returns the integer values of a tag, of any numeric type; rationals and floating-point
     * values are truncated.
     *
     * @param tag the tag to look for
     * @return the values, or {@code null} when the tag is absent
     * @throws MediaException.Malformed if the value lies outside the source
     * @throws MediaException.LimitExceeded if the value exceeds the reader's limits
     */
    public long[] longs(int tag) {
        var data = bytes(tag);
        if (data == null) {
            return null;
        }
        var type = type(tag);
        var size = Math.max(TiffTypes.size(type), 1);
        var little = reader.little();
        var values = new long[data.length / size];
        for (var index = 0; index < values.length; index++) {
            var at = index * size;
            values[index] = TiffTypes.isInteger(type) ? TiffTypes.integer(data, at, type, little)
                    : (long) number(data, at, type, little);
        }
        return values;
    }

    /**
     * Returns the values of a tag as numbers, of any numeric type.
     *
     * @param tag the tag to look for
     * @return the values, or {@code null} when the tag is absent
     * @throws MediaException.Malformed if the value lies outside the source
     * @throws MediaException.LimitExceeded if the value exceeds the reader's limits
     */
    public double[] doubles(int tag) {
        var data = bytes(tag);
        if (data == null) {
            return null;
        }
        var type = type(tag);
        var size = Math.max(TiffTypes.size(type), 1);
        var values = new double[data.length / size];
        for (var index = 0; index < values.length; index++) {
            values[index] = number(data, index * size, type, reader.little());
        }
        return values;
    }

    /**
     * Returns the first integer value of a tag.
     *
     * @param tag the tag to look for
     * @param fallback the value returned when the tag is absent or empty
     * @return the value
     */
    public long longValue(int tag, long fallback) {
        var values = longs(tag);
        return values == null || values.length == 0 ? fallback : values[0];
    }

    /**
     * Returns the first integer value of a tag, as an {@code int}.
     *
     * @param tag the tag to look for
     * @param fallback the value returned when the tag is absent or empty
     * @return the value, clamped to the {@code int} range
     */
    public int intValue(int tag, int fallback) {
        return (int) Math.clamp(longValue(tag, fallback), Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    /**
     * Returns the first value of a tag as a number.
     *
     * @param tag the tag to look for
     * @param fallback the value returned when the tag is absent or empty
     * @return the value
     */
    public double doubleValue(int tag, double fallback) {
        var values = doubles(tag);
        return values == null || values.length == 0 ? fallback : values[0];
    }

    /**
     * Returns the string value of a tag, up to its first NUL, without leading and trailing
     * white space.
     *
     * @param tag the tag to look for
     * @return the string, or {@code null} when the tag is absent
     */
    public String string(int tag) {
        var data = bytes(tag);
        if (data == null) {
            return null;
        }
        var end = 0;
        while (end < data.length && data[end] != 0) {
            end++;
        }
        return new String(data, 0, end, StandardCharsets.ISO_8859_1).strip();
    }

    /**
     * Reads the directory a tag points to, such as the EXIF directory.
     *
     * @param tag the pointer tag
     * @return the directory, or {@code null} when the tag is absent or the directory unreadable
     */
    public Ifd child(int tag) {
        var pointer = longValue(tag, 0);
        if (pointer <= 0) {
            return null;
        }
        try {
            return reader.directory(pointer);
        } catch (MediaException.Malformed exception) {
            return null;
        }
    }

    /**
     * {@return one value as a number: {@code ASCII} as a byte, an unknown type as 0}
     *
     * @param data the value bytes
     * @param at the index of the value
     * @param type the field type code
     * @param little whether the structure is little endian
     */
    private static double number(byte[] data, int at, int type, boolean little) {
        if (type == TiffTypes.ASCII) {
            return data[at] & 0xFF;
        }
        return TiffTypes.size(type) == 0 ? 0 : TiffTypes.number(data, at, type, little);
    }
}
