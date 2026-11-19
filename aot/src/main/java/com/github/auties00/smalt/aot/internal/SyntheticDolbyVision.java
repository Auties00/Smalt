package com.github.auties00.smalt.aot.internal;

import com.github.auties00.smalt.track.Packet;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes the reference processing units of a profile 5 Dolby Vision stream for the reconstruction workload: a mapping
 * of a polynomial luma piece and regression chroma pieces of order 3, and the display management matrices of IPT-PQ-c2.
 * The syntax is that of ETSI GS CCM 001 and of the dolby_vision crate of dovi_tool.
 */
final class SyntheticDolbyVision {
    /**
     * The fractional bits of the coefficients.
     */
    private static final int DENOMINATOR = 23;

    private SyntheticDolbyVision() {
        throw new AssertionError();
    }

    /**
     * Appends an RPU NAL unit to each length-prefixed HEVC access unit.
     *
     * @param packets the access units
     * @return the access units with their RPUs
     */
    static List<Packet> withRpus(List<Packet> packets) {
        var result = new ArrayList<Packet>(packets.size());
        for (var index = 0; index < packets.size(); index++) {
            var nal = nal(index);
            var out = new ByteArrayOutputStream();
            out.writeBytes(packets.get(index).data());
            out.write(nal.length >>> 24);
            out.write(nal.length >>> 16);
            out.write(nal.length >>> 8);
            out.write(nal.length);
            out.writeBytes(nal);
            result.add(packets.get(index).withData(out.toByteArray()));
        }
        return result;
    }

    /**
     * {@return an HEVC NAL unit of type 62 holding the RPU of a frame, whose mapping changes every other frame}
     *
     * @param frame the frame index
     */
    private static byte[] nal(int frame) {
        var bits = new ArrayList<Integer>(2048);
        put(bits, 8, 0x19);
        put(bits, 6, 2);
        put(bits, 11, 18);
        put(bits, 8, 0);
        put(bits, 2, 2);
        put(bits, 2, 0);
        ue(bits, DENOMINATOR);
        put(bits, 2, 1);
        put(bits, 1, 1);
        ue(bits, 2);
        ue(bits, 2);
        ue(bits, 4);
        put(bits, 5, 0);
        put(bits, 2, 3);
        put(bits, 1, 0);
        mapping(bits, frame);
        displayManagement(bits);
        while (bits.size() % 8 != 0) {
            bits.add(0);
        }
        var payload = new byte[bits.size() / 8];
        for (var index = 0; index < bits.size(); index++) {
            payload[index >> 3] |= (byte) (bits.get(index) << (7 - (index & 7)));
        }
        var crc = crc32(payload);
        var out = new ByteArrayOutputStream();
        out.write(0x7C);
        out.write(0x01);
        var zeros = 0;
        var rpu = new ByteArrayOutputStream();
        rpu.writeBytes(payload);
        rpu.write(crc >>> 24);
        rpu.write(crc >>> 16);
        rpu.write(crc >>> 8);
        rpu.write(crc);
        rpu.write(0x80);
        for (var value : rpu.toByteArray()) {
            var unsigned = value & 0xFF;
            if (zeros >= 2 && unsigned <= 3) {
                out.write(3);
                zeros = 0;
            }
            out.write(unsigned);
            zeros = unsigned == 0 ? zeros + 1 : 0;
        }
        return out.toByteArray();
    }

    /**
     * Writes the reshaping curves.
     *
     * @param bits the output
     * @param frame the frame index, from which the coefficients vary
     */
    private static void mapping(List<Integer> bits, int frame) {
        ue(bits, frame & 1);
        ue(bits, 0);
        ue(bits, 0);
        for (var component = 0; component < 3; component++) {
            ue(bits, 0);
            put(bits, 10, 0);
            put(bits, 10, 1023);
        }
        ue(bits, 0);
        ue(bits, 0);
        var one = 1L << DENOMINATOR;
        ue(bits, 0);
        ue(bits, 1);
        coefficient(bits, one / 256);
        coefficient(bits, one + (frame & 3));
        coefficient(bits, -one / 64);
        for (var component = 1; component < 3; component++) {
            ue(bits, 1);
            put(bits, 2, 2);
            coefficient(bits, one / 128);
            for (var index = 0; index < 21; index++) {
                coefficient(bits, index == component ? one : (index % 3 - 1) * one / 256);
            }
        }
    }

    /**
     * Writes the display management data of IPT-PQ-c2, without extension blocks.
     *
     * @param bits the output
     */
    private static void displayManagement(List<Integer> bits) {
        ue(bits, 0);
        ue(bits, 0);
        ue(bits, 0);
        for (var value : new int[]{8192, 799, 1681, 8192, -933, 1091, 8192, 267, -5545}) {
            put(bits, 16, value & 0xFFFF);
        }
        put(bits, 32, 0);
        put(bits, 32, 134217728);
        put(bits, 32, 134217728);
        for (var value : new int[]{17081, -349, -349, -349, 17081, -349, -349, -349, 17081}) {
            put(bits, 16, value & 0xFFFF);
        }
        put(bits, 16, 65535);
        put(bits, 16, 0);
        put(bits, 16, 0);
        put(bits, 32, 0);
        put(bits, 5, 12);
        put(bits, 2, 0);
        put(bits, 2, 2);
        put(bits, 2, 1);
        put(bits, 12, 7);
        put(bits, 12, 3079);
        put(bits, 10, 42);
        ue(bits, 0);
    }

    /**
     * Writes a fixed-point coefficient: the Exp-Golomb integer part, then the fraction.
     *
     * @param bits the output
     * @param value the coefficient with {@link #DENOMINATOR} fractional bits
     */
    private static void coefficient(List<Integer> bits, long value) {
        var integer = value >> DENOMINATOR;
        ue(bits, integer > 0 ? 2 * integer - 1 : -2 * integer);
        put(bits, DENOMINATOR, value - (integer << DENOMINATOR));
    }

    /**
     * Writes an unsigned Exp-Golomb code.
     *
     * @param bits the output
     * @param value the unsigned value
     */
    private static void ue(List<Integer> bits, long value) {
        var code = value + 1;
        var length = 64 - Long.numberOfLeadingZeros(code);
        put(bits, length - 1, 0);
        put(bits, length, code);
    }

    /**
     * Writes the low bits of a value, the most significant first.
     *
     * @param bits the output
     * @param count the number of bits
     * @param value the unsigned value
     */
    private static void put(List<Integer> bits, int count, long value) {
        for (var shift = count - 1; shift >= 0; shift--) {
            bits.add((int) (value >>> shift) & 1);
        }
    }

    /**
     * {@return the CRC-32/MPEG-2 of an RPU payload after its prefix byte}
     *
     * @param data the payload
     */
    private static int crc32(byte[] data) {
        var crc = 0xFFFFFFFF;
        for (var index = 1; index < data.length; index++) {
            crc ^= (data[index] & 0xFF) << 24;
            for (var bit = 0; bit < 8; bit++) {
                crc = crc < 0 ? crc << 1 ^ 0x04C11DB7 : crc << 1;
            }
        }
        return crc;
    }
}
