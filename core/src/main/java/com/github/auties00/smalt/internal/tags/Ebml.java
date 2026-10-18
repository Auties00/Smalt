package com.github.auties00.smalt.internal.tags;

import com.github.auties00.smalt.internal.io.ByteWriter;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads and writes EBML elements, the structure of Matroska and WebM (RFC 8794).
 *
 * <p>An element is a variable-length identifier (one to four bytes, marker bit kept), a
 * variable-length size (one to eight bytes, marker bit removed, all value bits set meaning an
 * unknown size) and the data.
 */
public final class Ebml {
    /**
     * The value of a size whose length is unknown.
     */
    public static final long UNKNOWN = -1;

    /**
     * The largest number of children listed for one element.
     */
    private static final int MAX_CHILDREN = 1 << 18;
    private Ebml() {
        throw new AssertionError();
    }

    /**
     * Decodes an element header.
     *
     * @param data the bytes
     * @param offset the index of the identifier
     * @param end the end of the readable region
     * @return the header, or {@code null} when it is malformed or truncated
     */
    public static Header header(byte[] data, int offset, int end) {
        if (offset >= end) {
            return null;
        }
        var first = data[offset] & 0xFF;
        var idLength = Integer.numberOfLeadingZeros(first) - 23;
        if (first == 0 || idLength > 4 || offset + idLength > end) {
            return null;
        }
        var id = 0;
        for (var i = 0; i < idLength; i++) {
            id = (id << 8) | (data[offset + i] & 0xFF);
        }
        var sizeOffset = offset + idLength;
        if (sizeOffset >= end) {
            return null;
        }
        var sizeFirst = data[sizeOffset] & 0xFF;
        if (sizeFirst == 0) {
            return null;
        }
        var sizeLength = Integer.numberOfLeadingZeros(sizeFirst) - 23;
        if (sizeOffset + sizeLength > end) {
            return null;
        }
        var size = (long) (sizeFirst & (0xFF >>> sizeLength));
        var allOnes = size == (0xFF >>> sizeLength);
        for (var i = 1; i < sizeLength; i++) {
            var b = data[sizeOffset + i] & 0xFF;
            allOnes &= b == 0xFF;
            size = (size << 8) | b;
        }
        return new Header(id, idLength + sizeLength, allOnes ? UNKNOWN : size);
    }

    /**
     * Lists the children of an element.
     *
     * <p>Listing stops at the first malformed child, a child of unknown size, or a child that
     * runs past the region.
     *
     * @param data the bytes
     * @param start the index of the first child
     * @param end the end of the parent's data
     * @return the children in order
     */
    public static List<Element> children(byte[] data, int start, int end) {
        var result = new ArrayList<Element>();
        var position = start;
        while (position < end && result.size() < MAX_CHILDREN) {
            var header = header(data, position, end);
            if (header == null || header.size() == UNKNOWN || header.size() > end - position - header.length()) {
                break;
            }
            result.add(new Element(header.id(), position, header.length(), (int) header.size()));
            position += header.length() + (int) header.size();
        }
        return result;
    }

    /**
     * Finds the first child with an identifier.
     *
     * @param elements the children to search
     * @param id the identifier
     * @return the element, or {@code null}
     */
    public static Element find(List<Element> elements, int id) {
        for (var element : elements) {
            if (element.id() == id) {
                return element;
            }
        }
        return null;
    }

    /**
     * Reads an unsigned integer element.
     *
     * @param data the bytes
     * @param element an element of at most 8 data bytes, in {@code data}
     * @return the value, or {@code 0} for an empty or oversized element
     */
    public static long unsigned(byte[] data, Element element) {
        if (element.size() > 8) {
            return 0;
        }
        var value = 0L;
        for (var i = 0; i < element.size(); i++) {
            value = (value << 8) | (data[element.data() + i] & 0xFF);
        }
        return value;
    }

    /**
     * Reads a float element.
     *
     * @param data the bytes
     * @param element an element of 0, 4 or 8 data bytes, in {@code data}
     * @return the value, or {@code NaN} for an element of another size than 4 or 8
     */
    public static double floating(byte[] data, Element element) {
        return switch (element.size()) {
            case 4 -> Float.intBitsToFloat((int) unsigned(data, element));
            case 8 -> Double.longBitsToDouble(unsigned(data, element));
            default -> Double.NaN;
        };
    }

    /**
     * Reads a string element, stopping at the first NUL.
     *
     * @param data the bytes
     * @param element a string element, in {@code data}
     * @return the string
     */
    public static String string(byte[] data, Element element) {
        var end = element.data();
        while (end < element.end() && data[end] != 0) {
            end++;
        }
        return new String(data, element.data(), end - element.data(), StandardCharsets.UTF_8);
    }

    /**
     * Returns the length of an element identifier, which carries its own length marker.
     *
     * @param id the identifier
     * @return the length in bytes, from 1 to 4
     */
    public static int idLength(int id) {
        if ((id & 0xFFFFFF00) == 0) {
            return 1;
        }
        if ((id & 0xFFFF0000) == 0) {
            return 2;
        }
        return (id & 0xFF000000) == 0 ? 3 : 4;
    }

    /**
     * Returns the length of the shortest variable-size integer that holds a size; the all-ones
     * value of each length is left for the unknown size.
     *
     * @param size the size, non-negative
     * @return the length in bytes, from 1 to 8
     */
    public static int sizeLength(long size) {
        var length = 1;
        while (length < 8 && size >= (1L << (7 * length)) - 1) {
            length++;
        }
        return length;
    }

    /**
     * Writes an identifier.
     *
     * @param out the destination
     * @param id the identifier with its marker bit
     */
    public static void id(ByteWriter out, int id) {
        for (var shift = (idLength(id) - 1) * 8; shift >= 0; shift -= 8) {
            out.u8(id >>> shift);
        }
    }

    /**
     * Writes a size in its shortest form.
     *
     * @param out the destination
     * @param size the data size, non-negative
     */
    public static void size(ByteWriter out, long size) {
        size(out, size, sizeLength(size));
    }

    /**
     * Writes a size in a fixed number of bytes.
     *
     * @param out the destination
     * @param size the size, or {@link #UNKNOWN}
     * @param length the number of bytes, 1 to 8
     */
    public static void size(ByteWriter out, long size, int length) {
        var value = size == UNKNOWN ? (1L << (7 * length)) - 1 : size;
        value |= 1L << (7 * length);
        for (var i = length - 1; i >= 0; i--) {
            out.u8((int) (value >>> (8 * i)));
        }
    }

    /**
     * Writes an element.
     *
     * @param out the destination
     * @param id the identifier
     * @param data the element data
     */
    public static void element(ByteWriter out, int id, byte[] data) {
        id(out, id);
        size(out, data.length);
        out.bytes(data);
    }

    /**
     * Writes an unsigned integer element in its shortest form.
     *
     * @param out the destination
     * @param id the identifier
     * @param value the integer, treated as unsigned
     */
    public static void unsigned(ByteWriter out, int id, long value) {
        var length = 1;
        while (length < 8 && (value >>> (8 * length)) != 0) {
            length++;
        }
        unsigned(out, id, value, length);
    }

    /**
     * Writes an unsigned integer element in a fixed number of bytes.
     *
     * @param out the destination
     * @param id the identifier
     * @param value the integer, treated as unsigned; it must fit {@code length} bytes
     * @param length the number of data bytes
     */
    public static void unsigned(ByteWriter out, int id, long value, int length) {
        id(out, id);
        size(out, length);
        for (var i = length - 1; i >= 0; i--) {
            out.u8((int) (value >>> (8 * i)));
        }
    }

    /**
     * Writes a UTF-8 string element.
     *
     * @param out the destination
     * @param id the identifier
     * @param value the text
     */
    public static void string(ByteWriter out, int id, String value) {
        element(out, id, value.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Returns an element as bytes.
     *
     * @param id the identifier
     * @param data the element data
     * @return the element
     */
    public static byte[] element(int id, byte[] data) {
        var out = new ByteWriter(data.length + 12);
        element(out, id, data);
        return out.toByteArray();
    }

    /**
     * Returns a {@code Void} element of an exact total size.
     *
     * @param total the total size, at least 2
     * @return the element, its data zeroed
     * @throws IllegalArgumentException if {@code total} is below 2
     */
    public static byte[] voidElement(long total) {
        if (total < 2 || total > Integer.MAX_VALUE - 16) {
            throw new IllegalArgumentException("invalid Void size " + total);
        }
        var out = new ByteWriter((int) total);
        out.u8(EbmlIds.VOID);
        if (total - 2 <= 126) {
            size(out, total - 2, 1);
            out.zeros((int) (total - 2));
        } else {
            size(out, total - 9, 8);
            out.zeros((int) (total - 9));
        }
        return out.toByteArray();
    }

    /**
     * Holds a decoded element header.
     *
     * @param id the identifier with its marker bit
     * @param length the length of the identifier and size fields
     * @param size the data size, or {@link #UNKNOWN}
     */
    public record Header(int id, int length, long size) {
    }

    /**
     * Locates one element inside a byte array.
     *
     * @param id the identifier
     * @param offset the index of the identifier
     * @param header the length of the identifier and size fields
     * @param size the data size
     */
    public record Element(int id, int offset, int header, int size) {
        /**
         * {@return the index of the data}
         */
        public int data() {
            return offset + header;
        }

        /**
         * {@return the index of the first byte after the element}
         */
        public int end() {
            return offset + header + size;
        }
    }
}
