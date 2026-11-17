package com.github.auties00.smalt.video.hevc.encoder.internal;

import java.util.Arrays;

/**
 * Assembles the NAL units of an access unit, each preceded by its four-byte big-endian length (ISO/IEC
 * 14496-15) or by the four-byte start code {@code 00 00 00 01} of the ITU-T H.265 Annex B byte stream.
 *
 * <p>Each unit is the two-byte NAL unit header (7.3.1.2) followed by its raw byte sequence payload with
 * emulation prevention applied (7.4.2): an {@code emulation_prevention_three_byte} follows every two
 * consecutive zero bytes that precede a byte of value 3 or less, and a payload ending in a zero byte gets
 * a final {@code 0x03}. Instances are not thread-safe.
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
     * The index of the prefix of the unit being written.
     */
    private int unitStart;

    /**
     * The number of consecutive zero bytes that end the escaped payload of the unit being written.
     */
    private int zeros;

    /**
     * The number of payload bytes of the unit being written, before emulation prevention.
     */
    private int payloadBytes;

    /**
     * The last payload byte of the unit being written.
     */
    private byte lastByte;

    /**
     * Constructs a writer.
     *
     * @param capacity the initial capacity in bytes
     * @param annexB whether units are preceded by an Annex B start code rather than by their length
     */
    NalWriter(int capacity, boolean annexB) {
        this.buffer = new byte[Math.max(64, capacity)];
        this.annexB = annexB;
    }

    /**
     * Appends one NAL unit of the base layer.
     *
     * @param type the {@code nal_unit_type}
     * @param temporalId {@code TemporalId}, from 0 to 6
     * @param payload the raw byte sequence payload
     * @param offset the index of the first payload byte
     * @param length the number of payload bytes
     */
    void append(int type, int temporalId, byte[] payload, int offset, int length) {
        begin(type, temporalId);
        write(payload, offset, length);
        end();
    }

    /**
     * Starts a NAL unit of the base layer whose payload follows in one or more {@link #write} calls and ends
     * with {@link #end()}.
     *
     * @param type the {@code nal_unit_type}
     * @param temporalId {@code TemporalId}, from 0 to 6
     */
    void begin(int type, int temporalId) {
        ensure(4 + 2);
        unitStart = size;
        size += 4;
        buffer[size++] = (byte) (type << 1);
        buffer[size++] = (byte) (temporalId + 1);
        zeros = 0;
        payloadBytes = 0;
    }

    /**
     * Appends payload bytes to the NAL unit started by {@link #begin}, with emulation prevention across the
     * bytes of earlier calls.
     *
     * @param payload the raw byte sequence payload bytes
     * @param offset the index of the first byte
     * @param length the number of bytes
     */
    void write(byte[] payload, int offset, int length) {
        if (length == 0) {
            return;
        }
        ensure(length + length / 2 + 1);
        var out = buffer;
        var position = size;
        var run = zeros;
        for (var i = offset; i < offset + length; i++) {
            var value = payload[i] & 0xFF;
            if (run >= 2 && value <= 3) {
                out[position++] = 3;
                run = 0;
            }
            out[position++] = (byte) value;
            run = value == 0 ? run + 1 : 0;
        }
        size = position;
        zeros = run;
        payloadBytes += length;
        lastByte = payload[offset + length - 1];
    }

    /**
     * Ends the NAL unit started by {@link #begin} and writes its prefix.
     */
    void end() {
        if (payloadBytes > 0 && lastByte == 0) {
            ensure(1);
            buffer[size++] = 3;
        }
        var start = unitStart;
        if (annexB) {
            buffer[start] = 0;
            buffer[start + 1] = 0;
            buffer[start + 2] = 0;
            buffer[start + 3] = 1;
        } else {
            var unit = size - start - 4;
            buffer[start] = (byte) (unit >>> 24);
            buffer[start + 1] = (byte) (unit >>> 16);
            buffer[start + 2] = (byte) (unit >>> 8);
            buffer[start + 3] = (byte) unit;
        }
    }

    /**
     * Appends one NAL unit whose whole payload is in an array.
     *
     * @param type the {@code nal_unit_type}
     * @param temporalId {@code TemporalId}
     * @param payload the raw byte sequence payload
     */
    void append(int type, int temporalId, byte[] payload) {
        append(type, temporalId, payload, 0, payload.length);
    }

    /**
     * Returns the access unit assembled so far and starts a new one.
     *
     * @return the bytes, a new array
     */
    byte[] take() {
        var data = Arrays.copyOf(buffer, size);
        size = 0;
        return data;
    }

    /**
     * Drops the access unit assembled so far.
     */
    void discard() {
        size = 0;
    }

    /**
     * Returns the number of bytes assembled so far.
     *
     * @return the size
     */
    int size() {
        return size;
    }

    /**
     * Returns a NAL unit with its header and emulation prevention, without a prefix, as a decoder
     * configuration record stores it.
     *
     * @param type the {@code nal_unit_type}
     * @param payload the raw byte sequence payload
     * @return the escaped unit, header included
     */
    static byte[] unit(int type, byte[] payload) {
        var out = new byte[2 + payload.length + payload.length / 2 + 2];
        out[0] = (byte) (type << 1);
        out[1] = 1;
        var end = escape(payload, 0, payload.length, out, 2);
        return Arrays.copyOf(out, end);
    }

    /**
     * Returns the number of bytes a run of payload bytes occupies after emulation prevention, when the
     * byte before it is not zero.
     *
     * @param data the payload
     * @param offset the index of the first byte
     * @param length the number of bytes
     * @return the escaped length
     */
    static int escapedLength(byte[] data, int offset, int length) {
        var zeros = 0;
        var extra = 0;
        for (var i = offset; i < offset + length; i++) {
            var value = data[i] & 0xFF;
            if (zeros >= 2 && value <= 3) {
                extra++;
                zeros = 0;
            }
            zeros = value == 0 ? zeros + 1 : 0;
        }
        return length + extra;
    }

    /**
     * Copies payload bytes with emulation prevention.
     *
     * @param in the payload
     * @param offset the index of the first payload byte
     * @param length the number of payload bytes
     * @param out the destination, large enough for the escaped bytes
     * @param position the index of the first destination byte
     * @return the index after the last byte written
     */
    private static int escape(byte[] in, int offset, int length, byte[] out, int position) {
        var zeros = 0;
        for (var i = offset; i < offset + length; i++) {
            var value = in[i] & 0xFF;
            if (zeros >= 2 && value <= 3) {
                out[position++] = 3;
                zeros = 0;
            }
            out[position++] = (byte) value;
            zeros = value == 0 ? zeros + 1 : 0;
        }
        if (length > 0 && in[offset + length - 1] == 0) {
            out[position++] = 3;
        }
        return position;
    }

    /**
     * Grows the buffer to hold more bytes.
     *
     * @param extra the number of bytes about to be appended, at most
     */
    private void ensure(int extra) {
        if (size + extra > buffer.length) {
            buffer = Arrays.copyOf(buffer, Math.max(buffer.length * 2, size + extra));
        }
    }
}
