package com.github.auties00.smalt.video.h264.encoder.internal;

import java.util.Arrays;

/**
 * Assembles NAL units into an access unit, each unit preceded by its four-byte big-endian length
 * as in ISO/IEC 14496-15 or by the four-byte start code {@code 00 00 00 01} of the ITU-T H.264
 * Annex B byte stream, or into the plain escaped form used inside a decoder configuration record.
 *
 * <p>Each unit is the one-byte NAL header followed by its payload with emulation prevention
 * applied (clause 7.4.1): an {@code emulation_prevention_three_byte} is inserted after every two
 * consecutive zero bytes that precede a byte of value 3 or less, and after a payload ending in a
 * zero byte. The NAL unit types are those of
 * {@link com.github.auties00.smalt.video.h264.internal.H264Nal}.
 */
final class NalWriter {
    /**
     * Whether units are preceded by an Annex B start code rather than by their length.
     */
    private final boolean annexB;

    /**
     * The access unit being assembled.
     */
    private byte[] buffer;

    /**
     * The number of bytes assembled.
     */
    private int size;

    /**
     * The index of the header byte of each unit assembled.
     */
    private int[] unitStarts = new int[16];

    /**
     * The length of each unit assembled.
     */
    private int[] unitLengths = new int[16];

    /**
     * The number of units assembled.
     */
    private int units;

    /**
     * Constructs a writer.
     *
     * @param capacity the initial capacity in bytes
     * @param annexB whether units are preceded by an Annex B start code rather than by their
     * length
     */
    NalWriter(int capacity, boolean annexB) {
        this.buffer = new byte[Math.max(64, capacity)];
        this.annexB = annexB;
    }

    /**
     * Appends one NAL unit with a four-byte prefix: its big-endian length, or the Annex B start
     * code.
     *
     * @param nalRefIdc the {@code nal_ref_idc}, from 0 to 3
     * @param nalUnitType the {@code nal_unit_type}
     * @param payload the raw byte sequence payload
     * @param length the number of payload bytes
     */
    void appendUnit(int nalRefIdc, int nalUnitType, byte[] payload, int length) {
        ensure(4 + 1 + length + length / 2 + 4);
        var start = size;
        size += 4;
        buffer[size++] = (byte) ((nalRefIdc << 5) | nalUnitType);
        size = escape(payload, length, buffer, size);
        writePrefix(start, size - start - 4);
        record(start + 4, size - start - 4);
    }

    /**
     * Appends one NAL unit that is already escaped, header included, with a four-byte prefix: its
     * big-endian length, or the Annex B start code.
     *
     * @param unit the escaped NAL unit, as returned by {@link #unit(int, int, byte[])}
     */
    void appendEscapedUnit(byte[] unit) {
        ensure(4 + unit.length);
        var start = size;
        System.arraycopy(unit, 0, buffer, start + 4, unit.length);
        size = start + 4 + unit.length;
        writePrefix(start, unit.length);
        record(start + 4, unit.length);
    }

    /**
     * Records the position of a unit just appended.
     *
     * @param start the index of its header byte
     * @param length its length
     */
    private void record(int start, int length) {
        if (units == unitStarts.length) {
            unitStarts = Arrays.copyOf(unitStarts, 2 * units);
            unitLengths = Arrays.copyOf(unitLengths, 2 * units);
        }
        unitStarts[units] = start;
        unitLengths[units] = length;
        units++;
    }

    /**
     * {@return the number of units assembled}
     */
    int unitCount() {
        return units;
    }

    /**
     * Returns the index in {@link #buffer()} of the header byte of a unit assembled.
     *
     * @param index the unit's position, from 0
     * @return the index
     */
    int unitStart(int index) {
        return unitStarts[index];
    }

    /**
     * Returns the escaped length of a unit assembled, header byte included.
     *
     * @param index the unit's position, from 0
     * @return the length in bytes
     */
    int unitLength(int index) {
        return unitLengths[index];
    }

    /**
     * {@return the buffer holding the assembled units, valid until the next append}
     */
    byte[] buffer() {
        return buffer;
    }

    /**
     * Writes the four-byte prefix of a unit.
     *
     * @param start the index of the prefix
     * @param unitLength the length of the escaped unit that follows the prefix
     */
    private void writePrefix(int start, int unitLength) {
        if (annexB) {
            buffer[start] = 0;
            buffer[start + 1] = 0;
            buffer[start + 2] = 0;
            buffer[start + 3] = 1;
            return;
        }
        buffer[start] = (byte) (unitLength >>> 24);
        buffer[start + 1] = (byte) (unitLength >>> 16);
        buffer[start + 2] = (byte) (unitLength >>> 8);
        buffer[start + 3] = (byte) unitLength;
    }

    /**
     * Returns the escaped NAL unit, header included, without a length prefix.
     *
     * @param nalRefIdc the {@code nal_ref_idc}, from 0 to 3
     * @param nalUnitType the {@code nal_unit_type}
     * @param payload the raw byte sequence payload
     * @return the NAL unit bytes
     */
    static byte[] unit(int nalRefIdc, int nalUnitType, byte[] payload) {
        var out = new byte[1 + payload.length + payload.length / 2 + 4];
        out[0] = (byte) ((nalRefIdc << 5) | nalUnitType);
        var end = escape(payload, payload.length, out, 1);
        return Arrays.copyOf(out, end);
    }

    /**
     * Copies a payload while inserting emulation prevention bytes.
     *
     * @param payload the raw byte sequence payload
     * @param length the number of payload bytes
     * @param out the destination, large enough for the worst case {@code length * 3 / 2 + 1}
     * @param offset the index in {@code out} of the first byte to write
     * @return the index after the last byte written
     */
    private static int escape(byte[] payload, int length, byte[] out, int offset) {
        var zeros = 0;
        var position = offset;
        for (var i = 0; i < length; i++) {
            var value = payload[i];
            if (zeros >= 2 && (value & 0xFF) <= 3) {
                out[position++] = 3;
                zeros = 0;
            }
            out[position++] = value;
            zeros = value == 0 ? zeros + 1 : 0;
        }
        if (length > 0 && payload[length - 1] == 0) {
            out[position++] = 3;
        }
        return position;
    }

    /**
     * Returns the number of bytes assembled.
     *
     * @return the access unit size
     */
    int size() {
        return size;
    }

    /**
     * Returns a copy of the assembled access unit and clears the writer.
     *
     * @return the access unit bytes
     */
    byte[] take() {
        var result = Arrays.copyOf(buffer, size);
        size = 0;
        units = 0;
        return result;
    }

    /**
     * Discards the bytes assembled after a size.
     *
     * @param size the number of bytes to keep, at most {@link #size()}
     */
    void truncate(int size) {
        this.size = size;
        while (units > 0 && unitStarts[units - 1] >= size) {
            units--;
        }
    }

    /**
     * Discards everything assembled.
     */
    void clear() {
        size = 0;
        units = 0;
    }

    /**
     * Grows the buffer to fit more bytes.
     *
     * @param extra the number of bytes about to be appended
     */
    private void ensure(int extra) {
        if (size + extra > buffer.length) {
            buffer = Arrays.copyOf(buffer, Math.max(buffer.length * 2, size + extra + 64));
        }
    }
}
