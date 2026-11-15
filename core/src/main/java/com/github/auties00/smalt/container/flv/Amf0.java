package com.github.auties00.smalt.container.flv;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.io.ByteWriter;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads and writes the Action Message Format 0 values of FLV script data tags (FLV specification E.4.4).
 *
 * <p>Values decode to {@link Double} (number), {@link Boolean}, {@link String} (string, long string and XML
 * document), {@link Map} with insertion order (object, typed object and ECMA array), {@link List} (strict array),
 * {@link Date} and {@code null} (null, undefined, reference and unsupported). A value nested deeper than
 * {@value #MAX_DEPTH} levels, more than {@value #MAX_VALUES} values or {@value #MAX_CONTAINERS} objects and arrays
 * in one reader, a size past the end of the data, or an unknown or AMF3 type marker is malformed.
 */
final class Amf0 {
    /**
     * The deepest nesting of objects and arrays read.
     */
    static final int MAX_DEPTH = 32;

    /**
     * The most values one reader reads, which bounds the memory of the decoded values of a tag of tiny values.
     */
    static final int MAX_VALUES = 1 << 21;

    /**
     * The most objects and arrays one reader reads.
     */
    static final int MAX_CONTAINERS = 1 << 16;

    /**
     * The type marker of a number.
     */
    static final int NUMBER = 0;

    /**
     * The type marker of a boolean.
     */
    static final int BOOLEAN = 1;

    /**
     * The type marker of a string of at most 65535 bytes.
     */
    static final int STRING = 2;

    /**
     * The type marker of an anonymous object.
     */
    static final int OBJECT = 3;

    /**
     * The type marker of null.
     */
    static final int NULL = 5;

    /**
     * The type marker of an ECMA (associative) array.
     */
    static final int ECMA_ARRAY = 8;

    /**
     * The type marker of the object end, after an empty property name.
     */
    static final int OBJECT_END = 9;

    /**
     * The type marker of a strict array.
     */
    static final int STRICT_ARRAY = 10;

    /**
     * The type marker of a long string.
     */
    static final int LONG_STRING = 12;

    /**
     * A date: milliseconds since 1970-01-01 UTC and the local offset in minutes.
     *
     * @param millis the instant in milliseconds since the epoch
     * @param offsetMinutes the local time offset from UTC in minutes
     */
    record Date(double millis, int offsetMinutes) {
    }

    /**
     * The bytes read.
     */
    private final byte[] data;

    /**
     * The end of the readable bytes.
     */
    private final int end;

    /**
     * The index of the next byte.
     */
    private int position;

    /**
     * The number of values read.
     */
    private int values;

    /**
     * The number of objects and arrays read.
     */
    private int containers;

    /**
     * Constructs a reader over a range of bytes.
     *
     * @param data the bytes
     * @param offset the index of the first value
     * @param end the end of the readable bytes
     */
    Amf0(byte[] data, int offset, int end) {
        this.data = data;
        this.position = offset;
        this.end = end;
    }

    /**
     * {@return the index of the next byte}
     */
    int position() {
        return position;
    }

    /**
     * {@return whether bytes remain}
     */
    boolean hasMore() {
        return position < end;
    }

    /**
     * Reads one value.
     *
     * @return the value, or {@code null}
     * @throws MediaException.Malformed if the value is corrupt or truncated
     */
    Object read() {
        return read(0);
    }

    /**
     * Reads one value at a nesting depth.
     *
     * @param depth the nesting depth of the value
     * @return the value, or {@code null}
     */
    private Object read(int depth) {
        if (depth > MAX_DEPTH) {
            throw new MediaException.Malformed("FLV script data nested deeper than " + MAX_DEPTH + " levels");
        }
        if (++values > MAX_VALUES) {
            throw new MediaException.Malformed("FLV script data of more than " + MAX_VALUES + " values");
        }
        var type = u8();
        return switch (type) {
            case NUMBER -> Double.longBitsToDouble(u64());
            case BOOLEAN -> u8() != 0;
            case STRING -> string(u16());
            case OBJECT -> properties(new LinkedHashMap<>(), depth);
            case 4, NULL, 6, 13 -> null;
            case 7 -> {
                u16();
                yield null;
            }
            case ECMA_ARRAY -> {
                u32();
                yield properties(new LinkedHashMap<>(), depth);
            }
            case STRICT_ARRAY -> {
                var count = u32();
                if (count > end - position) {
                    throw new MediaException.Malformed("FLV strict array of " + count + " values past the tag end");
                }
                container();
                var list = new ArrayList<>((int) count);
                for (var index = 0; index < count; index++) {
                    list.add(read(depth + 1));
                }
                yield list;
            }
            case 11 -> {
                var millis = Double.longBitsToDouble(u64());
                yield new Date(millis, (short) u16());
            }
            case LONG_STRING, 15 -> string(u32());
            case 16 -> {
                string(u16());
                yield properties(new LinkedHashMap<>(), depth);
            }
            default -> throw new MediaException.Malformed("unsupported AMF0 type marker " + type);
        };
    }

    /**
     * Reads the properties of an object or ECMA array up to its end marker, or up to the end of the data, which
     * some writers reach without an end marker.
     *
     * @param target receives the properties
     * @param depth the nesting depth of the object
     * @return {@code target}
     */
    private Map<String, Object> properties(Map<String, Object> target, int depth) {
        container();
        while (position < end) {
            if (end - position >= 3 && data[position] == 0 && data[position + 1] == 0
                    && data[position + 2] == OBJECT_END) {
                position += 3;
                return target;
            }
            var name = string(u16());
            target.put(name, read(depth + 1));
        }
        return target;
    }

    /**
     * Counts an object or array.
     *
     * @throws MediaException.Malformed if the reader has read {@value #MAX_CONTAINERS} of them
     */
    private void container() {
        if (++containers > MAX_CONTAINERS) {
            throw new MediaException.Malformed("FLV script data of more than " + MAX_CONTAINERS
                    + " objects and arrays");
        }
    }

    /**
     * Reads a UTF-8 string.
     *
     * @param length the length in bytes
     * @return the string
     */
    private String string(long length) {
        if (length > end - position) {
            throw new MediaException.Malformed("FLV script string of " + length + " bytes past the tag end");
        }
        var value = new String(data, position, (int) length, StandardCharsets.UTF_8);
        position += (int) length;
        return value;
    }

    /**
     * {@return the next byte}
     */
    private int u8() {
        need(1);
        return data[position++] & 0xFF;
    }

    /**
     * {@return the next big-endian 16-bit value}
     */
    private int u16() {
        need(2);
        var value = ((data[position] & 0xFF) << 8) | (data[position + 1] & 0xFF);
        position += 2;
        return value;
    }

    /**
     * {@return the next big-endian 32-bit unsigned value}
     */
    private long u32() {
        return ((long) u16() << 16) | u16();
    }

    /**
     * {@return the next big-endian 64-bit value}
     */
    private long u64() {
        return (u32() << 32) | u32();
    }

    /**
     * Checks that bytes remain.
     *
     * @param count the number of bytes needed
     * @throws MediaException.Malformed if fewer remain
     */
    private void need(int count) {
        if (end - position < count) {
            throw new MediaException.Malformed("truncated FLV script data");
        }
    }

    /**
     * Writes a number value.
     *
     * @param out the destination
     * @param value the number
     */
    static void writeNumber(ByteWriter out, double value) {
        out.u8(NUMBER);
        out.u64be(Double.doubleToRawLongBits(value));
    }

    /**
     * Writes a boolean value.
     *
     * @param out the destination
     * @param value the boolean
     */
    static void writeBoolean(ByteWriter out, boolean value) {
        out.u8(BOOLEAN);
        out.u8(value ? 1 : 0);
    }

    /**
     * Writes a string value, as a long string when its UTF-8 form exceeds 65535 bytes.
     *
     * @param out the destination
     * @param value the string
     */
    static void writeString(ByteWriter out, String value) {
        var bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 0xFFFF) {
            out.u8(LONG_STRING);
            out.u32be(bytes.length);
        } else {
            out.u8(STRING);
            out.u16be(bytes.length);
        }
        out.bytes(bytes);
    }

    /**
     * Writes a property name: a string without its type marker.
     *
     * @param out the destination
     * @param name the name, at most 65535 bytes in UTF-8
     * @throws IllegalArgumentException if the name is longer
     */
    static void writeName(ByteWriter out, String name) {
        var bytes = name.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 0xFFFF) {
            throw new IllegalArgumentException("AMF0 property name longer than 65535 bytes");
        }
        out.u16be(bytes.length);
        out.bytes(bytes);
    }

    /**
     * Writes the end marker of an object or ECMA array.
     *
     * @param out the destination
     */
    static void writeObjectEnd(ByteWriter out) {
        out.u16be(0);
        out.u8(OBJECT_END);
    }

    /**
     * Writes a strict array of numbers.
     *
     * @param out the destination
     * @param values the numbers
     * @param count the number of values written from the start of {@code values}
     */
    static void writeNumbers(ByteWriter out, double[] values, int count) {
        out.u8(STRICT_ARRAY);
        out.u32be(count);
        for (var index = 0; index < count; index++) {
            writeNumber(out, values[index]);
        }
    }
}
