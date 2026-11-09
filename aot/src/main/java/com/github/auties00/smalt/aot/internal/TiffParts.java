package com.github.auties00.smalt.aot.internal;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Writes the TIFF-structured files of the synthetic vendor raw formats: a file is a list of parts placed one after
 * the other, each built from the offsets of every part, so a directory can point at a part that follows it.
 *
 * <p>A part's size must not depend on the offsets it is given: {@link #write} builds every part once with zero
 * offsets to place them and once more with the real ones.
 */
final class TiffParts {
    /**
     * The TIFF type of an 8-bit unsigned integer.
     */
    static final int BYTE = 1;

    /**
     * The TIFF type of an ASCII string.
     */
    static final int ASCII = 2;

    /**
     * The TIFF type of a 16-bit unsigned integer.
     */
    static final int SHORT = 3;

    /**
     * The TIFF type of a 32-bit unsigned integer.
     */
    static final int LONG = 4;

    /**
     * The TIFF type of a fraction of two 32-bit unsigned integers.
     */
    static final int RATIONAL = 5;

    /**
     * The TIFF type of an opaque byte.
     */
    static final int UNDEFINED = 7;

    /**
     * Builds a part of a file.
     */
    @FunctionalInterface
    interface Part {
        /**
         * Builds the part.
         *
         * @param offsets the absolute offset of every part, in the order of the file
         * @return the part's bytes
         */
        byte[] build(long[] offsets);
    }

    /**
     * One directory entry.
     *
     * @param type the TIFF type
     * @param count the value count
     * @param value the value bytes, in the byte order of the directory
     */
    record Entry(int tag, int type, int count, byte[] value) {
    }

    /**
     * The byte order of the entries this instance creates.
     */
    private final ByteOrder order;

    /**
     * Constructs a writer of entries of a byte order.
     *
     * @param little whether the entries are little endian
     */
    TiffParts(boolean little) {
        this.order = little ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
    }

    /**
     * Returns the byte order of the entries.
     *
     * @return the order
     */
    ByteOrder order() {
        return order;
    }

    /**
     * Places the parts and returns the file.
     *
     * @param parts the parts, in file order; each starts on an even offset
     * @return the file
     */
    static byte[] write(List<Part> parts) {
        var offsets = new long[parts.size()];
        var position = 0L;
        for (var index = 0; index < parts.size(); index++) {
            offsets[index] = position;
            position += parts.get(index).build(new long[parts.size()]).length;
            position += position & 1;
        }
        var out = new ByteArrayOutputStream();
        for (var index = 0; index < parts.size(); index++) {
            var bytes = parts.get(index).build(offsets);
            out.writeBytes(bytes);
            if ((out.size() & 1) != 0) {
                out.write(0);
            }
        }
        return out.toByteArray();
    }

    /**
     * Returns a TIFF header of this byte order pointing to a first directory.
     *
     * @param magic the signature after the byte order mark: 42 for TIFF, {@code 0x4F52} for an Olympus file,
     * {@code 0x55} for a Panasonic file
     * @param first the offset of the first directory
     * @return the eight bytes of the header
     */
    byte[] header(int magic, long first) {
        var buffer = ByteBuffer.allocate(8).order(order);
        buffer.put(order == ByteOrder.LITTLE_ENDIAN ? (byte) 'I' : (byte) 'M');
        buffer.put(order == ByteOrder.LITTLE_ENDIAN ? (byte) 'I' : (byte) 'M');
        return buffer.putShort((short) magic).putInt((int) first).array();
    }

    /**
     * Returns a SHORT entry.
     *
     * @return the entry
     */
    Entry shorts(int tag, int... values) {
        var buffer = ByteBuffer.allocate(2 * values.length).order(order);
        for (var value : values) {
            buffer.putShort((short) value);
        }
        return new Entry(tag, SHORT, values.length, buffer.array());
    }

    /**
     * Returns a LONG entry.
     *
     * @return the entry
     */
    Entry longs(int tag, long... values) {
        var buffer = ByteBuffer.allocate(4 * values.length).order(order);
        for (var value : values) {
            buffer.putInt((int) value);
        }
        return new Entry(tag, LONG, values.length, buffer.array());
    }

    /**
     * Returns a RATIONAL entry of values with a common denominator.
     *
     * @param denominator the denominator of every value
     * @return the entry
     */
    Entry rationals(int tag, int denominator, int... numerators) {
        var buffer = ByteBuffer.allocate(8 * numerators.length).order(order);
        for (var numerator : numerators) {
            buffer.putInt(numerator).putInt(denominator);
        }
        return new Entry(tag, RATIONAL, numerators.length, buffer.array());
    }

    /**
     * Returns a BYTE entry.
     *
     * @return the entry
     */
    static Entry bytes(int tag, int... values) {
        var bytes = new byte[values.length];
        for (var index = 0; index < values.length; index++) {
            bytes[index] = (byte) values[index];
        }
        return new Entry(tag, BYTE, bytes.length, bytes);
    }

    /**
     * Returns an UNDEFINED entry.
     *
     * @param value the bytes
     * @return the entry
     */
    static Entry undefined(int tag, byte[] value) {
        return new Entry(tag, UNDEFINED, value.length, value);
    }

    /**
     * Returns an ASCII entry.
     *
     * @param value the string, without its terminating zero
     * @return the entry
     */
    static Entry ascii(int tag, String value) {
        var bytes = (value + "\0").getBytes(StandardCharsets.US_ASCII);
        return new Entry(tag, ASCII, bytes.length, bytes);
    }

    /**
     * Returns 16-bit values as bytes of this byte order, the way a tag stores an array in an opaque value.
     *
     * @return the bytes
     */
    byte[] u16(int... values) {
        var buffer = ByteBuffer.allocate(2 * values.length).order(order);
        for (var value : values) {
            buffer.putShort((short) value);
        }
        return buffer.array();
    }

    /**
     * Returns a directory followed by the values that do not fit its entries.
     *
     * @param entries the entries, in any order
     * @param start the offset of the directory, relative to the base its value offsets are relative to
     * @param next the offset of the next directory, or {@code 0}
     * @return the directory and its values
     */
    byte[] directory(List<Entry> entries, long start, long next) {
        var sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparingInt(Entry::tag));
        var size = 2 + 12 * sorted.size() + 4;
        var values = new ByteArrayOutputStream();
        var buffer = ByteBuffer.allocate(size).order(order);
        buffer.putShort((short) sorted.size());
        for (var entry : sorted) {
            buffer.putShort((short) entry.tag()).putShort((short) entry.type()).putInt(entry.count());
            if (entry.value().length <= 4) {
                var inline = new byte[4];
                System.arraycopy(entry.value(), 0, inline, 0, entry.value().length);
                buffer.put(inline);
            } else {
                buffer.putInt((int) (start + size + values.size()));
                values.writeBytes(entry.value());
                if ((values.size() & 1) != 0) {
                    values.write(0);
                }
            }
        }
        buffer.putInt((int) next);
        var out = new ByteArrayOutputStream();
        out.writeBytes(buffer.array());
        out.writeBytes(values.toByteArray());
        return out.toByteArray();
    }
}
