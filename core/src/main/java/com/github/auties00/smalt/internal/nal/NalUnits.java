package com.github.auties00.smalt.internal.nal;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Frames and unframes the NAL units of H.264 and HEVC streams: Annex B byte streams (units after
 * {@code 00 00 01} start codes), length-prefixed access units (each unit after a big-endian length
 * of 1 to 4 bytes, as MP4 and Matroska store them), and the emulation prevention bytes inside a
 * unit (the {@code 03} of every {@code 00 00 03} sequence, ITU-T H.264 clause 7.4.1).
 *
 * <p>The byte formats are identical for both codecs; only the NAL unit header differs, so methods
 * that read unit types take an {@code hevc} flag. This class is thread-safe.
 */
public final class NalUnits {
    /**
     * The four-byte start code that {@link #toAnnexB} and {@link #annexBParameterSets} write before
     * every unit.
     */
    private static final byte[] START_CODE = {0, 0, 0, 1};

    /**
     * Prevents instantiation.
     */
    private NalUnits() {
        throw new AssertionError();
    }

    /**
     * Receives one NAL unit found by a walk over a byte stream or an access unit.
     */
    @FunctionalInterface
    public interface Sink {
        /**
         * Receives a unit, valid only during the call.
         *
         * @param data the array holding the unit
         * @param offset the index of the first byte of the unit, its NAL unit header
         * @param length the unit size in bytes, zero for an empty length-prefixed unit
         */
        void unit(byte[] data, int offset, int length);
    }

    /**
     * Finds the next three-byte start code prefix {@code 00 00 01}.
     *
     * @param data the byte stream
     * @param from the index to start searching at
     * @param end the index one past the last byte to search
     * @return the index of the first {@code 00} of the prefix, or {@code -1} when there is none
     */
    public static int findStartCode(byte[] data, int from, int end) {
        var i = from;
        var limit = end - 2;
        while (i < limit) {
            var third = data[i + 2];
            if (third > 1 || third < 0) {
                i += 3;
            } else if (third == 1) {
                if (data[i + 1] == 0 && data[i] == 0) {
                    return i;
                }
                i += 3;
            } else {
                i++;
            }
        }
        return -1;
    }

    /**
     * Returns whether a byte stream starts with an Annex B start code prefix, optionally preceded
     * by more zero bytes.
     *
     * @param data the bytes to test
     * @return {@code true} when the first non-zero byte is a {@code 01} preceded by at least two
     * zero bytes
     */
    public static boolean looksLikeAnnexB(byte[] data) {
        var zeros = 0;
        for (var value : data) {
            if (value == 0) {
                zeros++;
            } else {
                return value == 1 && zeros >= 2;
            }
        }
        return false;
    }

    /**
     * Passes every unit of an Annex B byte stream to a sink, in stream order.
     *
     * <p>A unit runs from the byte after a start code to the next start code or the end of the
     * stream, without the zero bytes that end it (trailing zeros and the leading zero of a
     * four-byte start code). Bytes before the first start code and empty units are skipped.
     *
     * @param data the byte stream
     * @param sink receives each unit
     */
    public static void forEachAnnexB(byte[] data, Sink sink) {
        var position = findStartCode(data, 0, data.length);
        while (position >= 0) {
            var start = position + 3;
            var next = findStartCode(data, start, data.length);
            var end = next < 0 ? data.length : next;
            while (end > start && data[end - 1] == 0) {
                end--;
            }
            if (end > start) {
                sink.unit(data, start, end - start);
            }
            position = next;
        }
    }

    /**
     * Reads one big-endian NAL unit length prefix.
     *
     * @param data the access unit
     * @param position the index of the prefix
     * @param lengthSize the prefix size, from 1 to 4 bytes
     * @return the unit length, from 0 to {@code 2^32 - 1}
     * @throws IndexOutOfBoundsException if the prefix runs past the end of {@code data}
     */
    public static long readLength(byte[] data, int position, int lengthSize) {
        Objects.checkFromIndexSize(position, lengthSize, data.length);
        long length = 0;
        for (var i = 0; i < lengthSize; i++) {
            length = (length << 8) | (data[position + i] & 0xFF);
        }
        return length;
    }

    /**
     * Returns whether an access unit parses as length-prefixed units that cover it exactly.
     *
     * @param data the access unit
     * @param lengthSize the prefix size, from 1 to 4 bytes
     * @return {@code true} when every prefix is complete and no unit runs past the end
     */
    public static boolean isLengthPrefixed(byte[] data, int lengthSize) {
        return forEachLengthPrefixed(data, lengthSize, (_, _, _) -> {
        }) == data.length;
    }

    /**
     * Passes the units of a length-prefixed access unit to a sink, in order, until the access
     * unit ends or its framing breaks.
     *
     * <p>The walk stops at the first length prefix that is cut off by the end of the access unit,
     * or whose unit runs past it; no unit from there on reaches the sink. The return value tells
     * the caller whether that happened, so a strict caller raises its own error and a lenient one
     * ignores a short tail: at a returned {@code position < data.length}, the prefix is cut off
     * when {@code position + lengthSize > data.length} and the unit overruns otherwise
     * ({@link #readLength} gives its declared length). Empty units reach the sink with length
     * zero.
     *
     * @param data the access unit
     * @param lengthSize the prefix size, from 1 to 4 bytes
     * @param sink receives each unit
     * @return {@code data.length} when every unit reached the sink, otherwise the index of the
     * prefix that broke the framing
     */
    public static int forEachLengthPrefixed(byte[] data, int lengthSize, Sink sink) {
        var position = 0;
        while (position < data.length) {
            if (position + lengthSize > data.length) {
                return position;
            }
            var length = readLength(data, position, lengthSize);
            if (length > data.length - position - lengthSize) {
                return position;
            }
            sink.unit(data, position + lengthSize, (int) length);
            position += lengthSize + (int) length;
        }
        return data.length;
    }

    /**
     * Converts a length-prefixed access unit into an Annex B access unit, with four-byte start
     * codes.
     *
     * <p>Empty units are dropped. With a {@code delimiter}, the result starts with an access unit
     * delimiter: the first unit when it is one, otherwise {@code delimiter} itself. With
     * {@code parameterSets}, those bytes follow (after the delimiter, when there is one) unless
     * the access unit already holds a parameter set (H.264 SPS or PPS; HEVC VPS, SPS or PPS).
     *
     * @param data the access unit
     * @param lengthSize the prefix size, from 1 to 4 bytes
     * @param hevc whether the units have HEVC rather than H.264 headers
     * @param parameterSets the start-code prefixed parameter sets to insert, or {@code null} for
     * none
     * @param delimiter the start-code prefixed access unit delimiter to insert, or {@code null}
     * for none
     * @return the Annex B access unit
     * @throws IllegalArgumentException if a length prefix is cut off or a unit runs past the end
     * of {@code data}
     */
    public static byte[] toAnnexB(byte[] data, int lengthSize, boolean hevc, byte[] parameterSets, byte[] delimiter) {
        var units = new int[16];
        var count = 0;
        var hasDelimiter = false;
        var hasParameterSets = false;
        for (var offset = 0; offset < data.length; ) {
            if (offset + lengthSize > data.length) {
                throw new IllegalArgumentException("truncated NAL unit length");
            }
            var length = readLength(data, offset, lengthSize);
            var start = offset + lengthSize;
            if (length > data.length - start) {
                throw new IllegalArgumentException("NAL unit of " + length + " bytes overruns its packet");
            }
            if (length > 0) {
                var type = type(data[start], hevc);
                if (count == 0 && type == (hevc ? 35 : 9)) {
                    hasDelimiter = true;
                }
                hasParameterSets |= hevc ? type >= 32 && type <= 34 : type == 7 || type == 8;
                if (2 * count + 2 > units.length) {
                    units = Arrays.copyOf(units, 2 * units.length);
                }
                units[2 * count] = start;
                units[2 * count + 1] = (int) length;
                count++;
            }
            offset = start + (int) length;
        }
        var insertSets = parameterSets != null && !hasParameterSets;
        var insertDelimiter = delimiter != null && !hasDelimiter;
        var size = (long) data.length + 4L * count + (insertSets ? parameterSets.length : 0)
                + (insertDelimiter ? delimiter.length : 0);
        var out = new byte[Math.toIntExact(size)];
        var position = 0;
        var index = 0;
        if (delimiter != null) {
            if (hasDelimiter) {
                position = put(out, position, data, units[0], units[1]);
                index = 1;
            } else {
                System.arraycopy(delimiter, 0, out, 0, delimiter.length);
                position = delimiter.length;
            }
        }
        if (insertSets) {
            System.arraycopy(parameterSets, 0, out, position, parameterSets.length);
            position += parameterSets.length;
        }
        for (; index < count; index++) {
            position = put(out, position, data, units[2 * index], units[2 * index + 1]);
        }
        return position == out.length ? out : Arrays.copyOf(out, position);
    }

    /**
     * Converts an Annex B access unit into four-byte length-prefixed NAL units, the canonical packet
     * form of H.264 and HEVC.
     *
     * <p>The units are those {@link #forEachAnnexB} finds, in stream order and unchanged.
     *
     * @param data the Annex B access unit
     * @return the units, each after its big-endian four-byte length; empty when {@code data} holds
     * no unit
     */
    public static byte[] toLengthPrefixed(byte[] data) {
        var size = new long[1];
        forEachAnnexB(data, (array, offset, length) -> size[0] += 4L + length);
        var out = new byte[Math.toIntExact(size[0])];
        var position = new int[1];
        forEachAnnexB(data, (array, offset, length) -> {
            var at = position[0];
            out[at] = (byte) (length >>> 24);
            out[at + 1] = (byte) (length >>> 16);
            out[at + 2] = (byte) (length >>> 8);
            out[at + 3] = (byte) length;
            System.arraycopy(array, offset, out, at + 4, length);
            position[0] = at + 4 + length;
        });
        return out;
    }

    /**
     * Returns parameter set units as an Annex B byte stream, each after a four-byte start code, in
     * list order.
     *
     * @param parameterSets the whole NAL units, headers included
     * @return the byte stream, empty for an empty list
     */
    public static byte[] annexBParameterSets(List<byte[]> parameterSets) {
        var size = 0;
        for (var unit : parameterSets) {
            size = Math.addExact(size, START_CODE.length + unit.length);
        }
        var out = new byte[size];
        var position = 0;
        for (var unit : parameterSets) {
            position = put(out, position, unit, 0, unit.length);
        }
        return out;
    }

    /**
     * Rewrites a length-prefixed access unit with another prefix size, inserting parameter sets
     * before its units when it holds none.
     *
     * <p>Empty units are dropped. The parameter sets are inserted only when the access unit
     * holds no parameter set of its own (H.264 SPS or PPS; HEVC VPS, SPS or PPS).
     *
     * @param data the access unit
     * @param lengthSize the prefix size of {@code data}, from 1 to 4 bytes
     * @param targetSize the prefix size of the result, from 1 to 4 bytes
     * @param hevc whether the units have HEVC rather than H.264 headers
     * @param parameterSets the whole parameter set units to insert, or {@code null} for none
     * @return the rewritten access unit
     * @throws IllegalArgumentException if a length prefix is cut off, a unit runs past the end of
     * {@code data}, or a unit is too long for {@code targetSize}
     */
    public static byte[] relength(byte[] data, int lengthSize, int targetSize, boolean hevc, List<byte[]> parameterSets) {
        var size = 0L;
        var hasParameterSets = false;
        for (var offset = 0; offset < data.length; ) {
            if (offset + lengthSize > data.length) {
                throw new IllegalArgumentException("truncated NAL unit length");
            }
            var length = readLength(data, offset, lengthSize);
            var start = offset + lengthSize;
            if (length > data.length - start) {
                throw new IllegalArgumentException("NAL unit of " + length + " bytes overruns its packet");
            }
            if (length > 0) {
                var type = type(data[start], hevc);
                hasParameterSets |= hevc ? type >= 32 && type <= 34 : type == 7 || type == 8;
                size += targetSize + length;
            }
            offset = start + (int) length;
        }
        var insert = parameterSets != null && !hasParameterSets;
        if (insert) {
            for (var unit : parameterSets) {
                size += targetSize + unit.length;
            }
        }
        var out = new byte[Math.toIntExact(size)];
        var position = 0;
        if (insert) {
            for (var unit : parameterSets) {
                position = putPrefixed(out, position, targetSize, unit, 0, unit.length);
            }
        }
        for (var offset = 0; offset < data.length; ) {
            var length = (int) readLength(data, offset, lengthSize);
            if (length > 0) {
                position = putPrefixed(out, position, targetSize, data, offset + lengthSize, length);
            }
            offset += lengthSize + length;
        }
        return out;
    }

    /**
     * Writes one unit after a length prefix.
     *
     * @param out the destination
     * @param position the index of the prefix in {@code out}
     * @param lengthSize the prefix size, from 1 to 4 bytes
     * @param data the bytes holding the unit
     * @param offset the index of the unit in {@code data}
     * @param length the unit length
     * @return the index after the unit
     * @throws IllegalArgumentException if the length does not fit the prefix
     */
    private static int putPrefixed(byte[] out, int position, int lengthSize, byte[] data, int offset, int length) {
        if (lengthSize < 4 && length >= 1 << (8 * lengthSize)) {
            throw new IllegalArgumentException("NAL unit of " + length + " bytes does not fit a " + lengthSize
                    + "-byte length");
        }
        for (var index = 0; index < lengthSize; index++) {
            out[position + index] = (byte) (length >>> (8 * (lengthSize - 1 - index)));
        }
        System.arraycopy(data, offset, out, position + lengthSize, length);
        return position + lengthSize + length;
    }

    /**
     * Returns the type of a NAL unit from its first header byte.
     *
     * @param header the first byte of the unit
     * @param hevc whether the unit has an HEVC rather than an H.264 header
     * @return {@code nal_unit_type}, from 0 to 31 for H.264 and from 0 to 63 for HEVC
     */
    public static int type(byte header, boolean hevc) {
        return hevc ? (header >> 1) & 0x3F : header & 0x1F;
    }

    /**
     * Removes the emulation prevention bytes of a range into a caller buffer.
     *
     * @param source the escaped bytes
     * @param offset the index of the first escaped byte
     * @param length the number of escaped bytes
     * @param destination receives the unescaped bytes from index 0; at least {@code length} bytes
     * @return the number of unescaped bytes written
     * @throws IndexOutOfBoundsException if a range is out of bounds
     */
    public static int unescape(byte[] source, int offset, int length, byte[] destination) {
        return (int) unescape(source, offset, length, destination, null);
    }

    /**
     * Removes the emulation prevention bytes of a range into a caller buffer and records where
     * they were.
     *
     * <p>The removed positions translate offsets that count the emulation prevention bytes, such
     * as the entry points of an HEVC slice header, into offsets of the unescaped bytes.
     *
     * @param source the escaped bytes
     * @param offset the index of the first escaped byte
     * @param length the number of escaped bytes
     * @param destination receives the unescaped bytes from index 0; at least {@code length} bytes
     * @param removed receives the offsets, relative to {@code offset}, of the removed bytes in
     * increasing order, at least {@code length / 3 + 1} entries; or {@code null} when not needed
     * @return the number of unescaped bytes written in the low 32 bits and the number of removed
     * bytes in the high 32 bits
     * @throws IndexOutOfBoundsException if a range is out of bounds
     */
    public static long unescape(byte[] source, int offset, int length, byte[] destination, int[] removed) {
        Objects.checkFromIndexSize(offset, length, source.length);
        var end = offset + length;
        var out = 0;
        var copyFrom = offset;
        var count = 0;
        var i = offset;
        var limit = end - 2;
        while (i < limit) {
            var third = source[i + 2];
            if (third > 3 || third < 0) {
                i += 3;
            } else if (third == 3 && source[i] == 0 && source[i + 1] == 0) {
                var run = i + 2 - copyFrom;
                System.arraycopy(source, copyFrom, destination, out, run);
                out += run;
                if (removed != null) {
                    removed[count] = i + 2 - offset;
                }
                count++;
                copyFrom = i + 3;
                i += 3;
            } else {
                i++;
            }
        }
        var run = end - copyFrom;
        if (run > 0) {
            System.arraycopy(source, copyFrom, destination, out, run);
            out += run;
        }
        return ((long) count << 32) | out;
    }

    /**
     * Returns a range without its emulation prevention bytes, in a new array.
     *
     * @param source the escaped bytes
     * @param offset the index of the first escaped byte
     * @param length the number of escaped bytes
     * @return the unescaped bytes
     * @throws IndexOutOfBoundsException if the range is out of bounds
     */
    public static byte[] unescape(byte[] source, int offset, int length) {
        var out = new byte[length];
        var size = unescape(source, offset, length, out);
        return size == length ? out : Arrays.copyOf(out, size);
    }

    /**
     * Returns the bit position of the {@code rbsp_stop_one_bit}: the last set bit of a payload.
     *
     * @param data the payload buffer
     * @param offset the index of the first payload byte
     * @param length the payload length in bytes
     * @return the bit offset of the stop bit from the start of the payload, or {@code length * 8}
     * when the payload holds no set bit
     */
    public static long stopBitPosition(byte[] data, int offset, int length) {
        for (var i = offset + length - 1; i >= offset; i--) {
            var value = data[i] & 0xFF;
            if (value != 0) {
                return (long) (i - offset) * 8 + 7 - Integer.numberOfTrailingZeros(value);
            }
        }
        return (long) length * 8;
    }

    /**
     * Writes a start code and a unit.
     *
     * @param out the destination
     * @param position the index of the start code
     * @param data the array holding the unit
     * @param offset the index of the unit
     * @param length the unit size
     * @return the index after the unit
     */
    private static int put(byte[] out, int position, byte[] data, int offset, int length) {
        System.arraycopy(START_CODE, 0, out, position, START_CODE.length);
        System.arraycopy(data, offset, out, position + START_CODE.length, length);
        return position + START_CODE.length + length;
    }
}
