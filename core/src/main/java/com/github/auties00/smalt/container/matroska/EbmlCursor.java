package com.github.auties00.smalt.container.matroska;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.tags.Ebml;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Reads EBML elements from a range of a byte array that holds one master element read into
 * memory, per RFC 8794.
 *
 * <p>Element identifiers are one to four bytes and keep their length marker; data sizes are one
 * to eight bytes with the marker removed. Every read validates the remaining length and raises
 * {@link MediaException.Malformed} for truncated or invalid variable-size integers.
 */
final class EbmlCursor {
    /**
     * The marker value of a data size whose value bits are all set: an unknown size.
     */
    static final long UNKNOWN_SIZE = Ebml.UNKNOWN;

    /**
     * The backing array.
     */
    private final byte[] data;

    /**
     * The index one past the last readable byte.
     */
    private final int limit;

    /**
     * The index of the next byte to read.
     */
    private int position;

    /**
     * Constructs a cursor over {@code data[offset, limit)}.
     *
     * @param data the backing array
     * @param offset the first readable index
     * @param limit the index one past the last readable byte
     */
    EbmlCursor(byte[] data, int offset, int limit) {
        this.data = data;
        this.position = offset;
        this.limit = limit;
    }

    /**
     * Constructs a cursor over a whole array.
     *
     * @param data the backing array
     */
    EbmlCursor(byte[] data) {
        this(data, 0, data.length);
    }

    /**
     * {@return whether bytes remain below the limit}
     */
    boolean hasMore() {
        return position < limit;
    }

    /**
     * {@return the number of bytes left before the limit}
     */
    int remaining() {
        return limit - position;
    }

    /**
     * Reads the header of the next child element and returns a cursor over its data.
     *
     * @return the element
     * @throws MediaException.Malformed if the header is invalid or the size runs past the end of this cursor's range
     */
    Element next() {
        var header = Ebml.header(data, position, limit);
        if (header == null) {
            throw new MediaException.Malformed("invalid or truncated EBML element header");
        }
        position += header.length();
        var size = header.size();
        if (size == UNKNOWN_SIZE || size > remaining()) {
            throw new MediaException.Malformed("EBML element 0x" + Integer.toHexString(header.id()) + " of size " + size
                    + " does not fit its " + remaining() + " byte parent");
        }
        var start = position;
        position += (int) size;
        return new Element(header.id(), new EbmlCursor(data, start, start + (int) size));
    }

    /**
     * Returns the unread range of this cursor as an element of the given identifier, for the
     * readers that walk byte ranges.
     *
     * @param id the identifier of the element this cursor reads
     * @return the element, whose data is the unread range of {@link #array()}
     */
    Ebml.Element asElement(int id) {
        return new Ebml.Element(id, position, 0, limit - position);
    }

    /**
     * {@return the backing array, not copied}
     */
    byte[] array() {
        return data;
    }

    /**
     * Reads one byte.
     *
     * @return the value, from 0 to 255
     * @throws MediaException.Malformed if no byte remains
     */
    int u8() {
        if (position >= limit) {
            throw new MediaException.Malformed("EBML element truncated");
        }
        return data[position++] & 0xFF;
    }

    /**
     * Reads the rest of the range as a big-endian unsigned integer.
     *
     * @return the value
     * @throws MediaException.Malformed if the integer is longer than eight bytes
     */
    long unsigned() {
        if (remaining() > 8) {
            throw new MediaException.Malformed("EBML integer of " + remaining() + " bytes");
        }
        var value = 0L;
        while (hasMore()) {
            value = (value << 8) | u8();
        }
        return value;
    }

    /**
     * Reads the rest of the range as a big-endian signed integer.
     *
     * @return the value
     * @throws MediaException.Malformed if the integer is longer than eight bytes
     */
    long signed() {
        var length = remaining();
        if (length == 0) {
            return 0;
        }
        var value = unsigned();
        return length == 8 ? value : (value << (64 - 8 * length)) >> (64 - 8 * length);
    }

    /**
     * Reads the rest of the range as an IEEE float of zero, four or eight bytes.
     *
     * @return the value
     * @throws MediaException.Malformed if the length is not 0, 4 or 8
     */
    double floating() {
        return switch (remaining()) {
            case 0 -> 0;
            case 4 -> Float.intBitsToFloat((int) unsigned());
            case 8 -> Double.longBitsToDouble(unsigned());
            default -> throw new MediaException.Malformed("EBML float of " + remaining() + " bytes");
        };
    }

    /**
     * Reads the rest of the range as a string, stopping at the first NUL byte.
     *
     * @return the decoded UTF-8 string
     */
    String string() {
        var end = position;
        while (end < limit && data[end] != 0) {
            end++;
        }
        var result = new String(data, position, end - position, StandardCharsets.UTF_8);
        position = limit;
        return result;
    }

    /**
     * Reads the rest of the range into a new array.
     *
     * @return the bytes
     */
    byte[] bytes() {
        var result = Arrays.copyOfRange(data, position, limit);
        position = limit;
        return result;
    }

    /**
     * Holds one element: its identifier and a cursor over its data.
     *
     * @param id the identifier with its length marker
     * @param payload the cursor over the element data
     */
    record Element(long id, EbmlCursor payload) {
    }
}
