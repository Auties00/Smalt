package com.github.auties00.smalt.image.tiff.internal;

import com.github.auties00.smalt.internal.tiff.TiffTypes;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

/**
 * Builds one TIFF image file directory, with its out-of-line values and any sub-directories it
 * points to, as bytes ready to be written at a known file offset.
 *
 * <p>Entries are written in ascending tag order, as TIFF 6.0 requires; values that do not fit in
 * an entry's value field follow the entry table, each starting on a word boundary, and
 * sub-directories follow the values. Classic directories have 12-byte entries and 4-byte offsets,
 * BigTIFF ones 20-byte entries and 8-byte offsets.
 */
public final class IfdBuilder {
    /**
     * Whether values are written little-endian.
     */
    private final boolean little;

    /**
     * Whether the directory is a BigTIFF directory.
     */
    private final boolean big;

    /**
     * The entries by tag.
     */
    private final Map<Integer, Field> fields = new TreeMap<>();

    /**
     * Holds one entry.
     *
     * @param type the field type
     * @param count the number of values
     * @param value the values in the directory's byte order, or {@code null} for a sub-directory
     * @param child the sub-directory, or {@code null}
     */
    private record Field(int type, long count, byte[] value, IfdBuilder child) {
    }

    /**
     * Constructs an empty directory.
     *
     * @param little whether values are written little-endian
     * @param big whether the directory is a BigTIFF directory
     */
    public IfdBuilder(boolean little, boolean big) {
        this.little = little;
        this.big = big;
    }

    /**
     * Returns whether the directory has no entry.
     *
     * @return {@code true} when empty
     */
    public boolean isEmpty() {
        return fields.isEmpty();
    }

    /**
     * Adds an entry with raw values.
     *
     * @param tag the tag number
     * @param type the field type
     * @param count the number of values
     * @param value the values, already in the directory's byte order
     */
    public void raw(int tag, int type, long count, byte[] value) {
        fields.put(tag, new Field(type, count, value, null));
    }

    /**
     * Adds a SHORT entry.
     *
     * @param tag the tag number
     * @param values the values, each from 0 to 65535
     */
    public void shorts(int tag, int... values) {
        var bytes = new byte[values.length * 2];
        for (var i = 0; i < values.length; i++) {
            put(bytes, i * 2, values[i], 2);
        }
        raw(tag, TiffTypes.SHORT, values.length, bytes);
    }

    /**
     * Adds a LONG entry.
     *
     * @param tag the tag number
     * @param values the values, each from 0 to {@code 2^32 - 1}
     */
    public void longs(int tag, long... values) {
        var bytes = new byte[values.length * 4];
        for (var i = 0; i < values.length; i++) {
            put(bytes, i * 4, values[i], 4);
        }
        raw(tag, TiffTypes.LONG, values.length, bytes);
    }

    /**
     * Adds an entry of file offsets or byte counts: LONG in a classic file, LONG8 in a BigTIFF.
     *
     * @param tag the tag number
     * @param values the offsets or byte counts, not negative
     */
    public void offsets(int tag, long... values) {
        if (!big) {
            longs(tag, values);
            return;
        }
        var bytes = new byte[values.length * 8];
        for (var i = 0; i < values.length; i++) {
            put(bytes, i * 8, values[i], 8);
        }
        raw(tag, TiffTypes.LONG8, values.length, bytes);
    }

    /**
     * Adds a RATIONAL entry with one value.
     *
     * @param tag the tag number
     * @param numerator the numerator, from 0 to {@code 2^32 - 1}
     * @param denominator the denominator, from 0 to {@code 2^32 - 1}
     */
    public void rational(int tag, long numerator, long denominator) {
        var bytes = new byte[8];
        put(bytes, 0, numerator, 4);
        put(bytes, 4, denominator, 4);
        raw(tag, TiffTypes.RATIONAL, 1, bytes);
    }

    /**
     * Adds a RATIONAL entry with several values.
     *
     * @param tag the tag number
     * @param values the numerator and denominator of each value, alternating, each from 0 to
     * {@code 2^32 - 1}
     */
    public void rationals(int tag, long... values) {
        var bytes = new byte[values.length * 4];
        for (var i = 0; i < values.length; i++) {
            put(bytes, i * 4, values[i], 4);
        }
        raw(tag, TiffTypes.RATIONAL, values.length / 2, bytes);
    }

    /**
     * Adds an ASCII entry.
     *
     * @param tag the tag number
     * @param text the text, written as UTF-8 with a terminating NUL
     */
    public void ascii(int tag, String text) {
        var encoded = text.getBytes(StandardCharsets.UTF_8);
        var bytes = new byte[encoded.length + 1];
        System.arraycopy(encoded, 0, bytes, 0, encoded.length);
        raw(tag, TiffTypes.ASCII, bytes.length, bytes);
    }

    /**
     * Adds an UNDEFINED entry.
     *
     * @param tag the tag number
     * @param bytes the value bytes, not copied
     */
    public void undefined(int tag, byte[] bytes) {
        raw(tag, TiffTypes.UNDEFINED, bytes.length, bytes);
    }

    /**
     * Adds a pointer to a sub-directory.
     *
     * @param tag the tag number
     * @param child the sub-directory, written after this directory's values
     */
    public void child(int tag, IfdBuilder child) {
        fields.put(tag, new Field(big ? TiffTypes.IFD8 : TiffTypes.LONG, 1, null, child));
    }

    /**
     * Returns the size of the entry table and the next pointer.
     *
     * @return the size in bytes
     */
    private long tableSize() {
        return (big ? 8 : 2) + (long) fields.size() * (big ? 20 : 12) + (big ? 8 : 4);
    }

    /**
     * Returns the size of the value field of an entry.
     *
     * @return 4 or 8
     */
    private int fieldSize() {
        return big ? 8 : 4;
    }

    /**
     * Returns the serialized size of the directory, its values and its sub-directories.
     *
     * @return the size in bytes, even
     */
    public long size() {
        var size = tableSize();
        for (var field : fields.values()) {
            if (field.child != null) {
                size += field.child.size();
            } else if (field.value.length > fieldSize()) {
                size += field.value.length + (field.value.length & 1);
            }
        }
        return size;
    }

    /**
     * Serializes the directory.
     *
     * @param offset the absolute file offset the directory is written at, even
     * @param next the offset of the next directory in the chain, or {@code 0}
     * @return the bytes, {@link #size()} long
     */
    public byte[] write(long offset, long next) {
        var out = new byte[(int) size()];
        write(out, 0, offset, next);
        return out;
    }

    /**
     * Serializes the directory into an array.
     *
     * @param out the destination
     * @param at the index of the directory in {@code out}
     * @param offset the absolute file offset of {@code out[at]}
     * @param next the offset of the next directory, or {@code 0}
     */
    private void write(byte[] out, int at, long offset, long next) {
        var countSize = big ? 8 : 2;
        var entrySize = big ? 20 : 12;
        var fieldSize = fieldSize();
        put(out, at, fields.size(), countSize);
        var data = at + (int) tableSize();
        var entry = at + countSize;
        for (var field : fields.entrySet()) {
            var value = field.getValue();
            put(out, entry, field.getKey(), 2);
            put(out, entry + 2, value.type, 2);
            put(out, entry + 4, value.count, big ? 8 : 4);
            var valueAt = entry + 4 + (big ? 8 : 4);
            if (value.child == null && value.value.length <= fieldSize) {
                System.arraycopy(value.value, 0, out, valueAt, value.value.length);
            } else if (value.child == null) {
                put(out, valueAt, offset + (data - at), fieldSize);
                System.arraycopy(value.value, 0, out, data, value.value.length);
                data += value.value.length + (value.value.length & 1);
            }
            entry += entrySize;
        }
        put(out, entry, next, big ? 8 : 4);
        entry = at + countSize;
        for (var field : fields.values()) {
            if (field.child != null) {
                var childOffset = offset + (data - at);
                put(out, entry + 4 + (big ? 8 : 4), childOffset, fieldSize);
                field.child.write(out, data, childOffset, 0);
                data += (int) field.child.size();
            }
            entry += entrySize;
        }
    }

    /**
     * Writes an unsigned value in the directory's byte order.
     *
     * @param out the destination
     * @param at the index of the first byte
     * @param value the unsigned value; bits above {@code 8 * bytes} are ignored
     * @param bytes the number of bytes: 2, 4 or 8
     */
    private void put(byte[] out, int at, long value, int bytes) {
        for (var i = 0; i < bytes; i++) {
            var shift = little ? 8 * i : 8 * (bytes - 1 - i);
            out[at + i] = (byte) (value >>> shift);
        }
    }
}
