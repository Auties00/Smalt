package com.github.auties00.smalt.testkit;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes Dolby Vision reference processing units (RPUs) with the syntax of the dolby_vision crate of dovi_tool (MIT),
 * so that tests can build Dolby Vision streams of any profile: a header, an identity polynomial luma mapping and
 * polynomial or MMR chroma mappings, the nonlinear quantization of an enhancement layer, the display management data
 * of a BT.2020 PQ source with CM v2.9 and optional CM v4.0 extension blocks, then the CRC-32/MPEG-2 and the
 * terminating {@code 0x80}. ffmpeg and dovi_tool parse what this class writes (see
 * {@code tools/testdata/behavior/make-dolby-vision-samples.py}, which writes the same RPUs for profiles 8 and 5).
 */
public final class DolbyVisionRpus {
    /** The header the decoders guess a profile from. */
    public enum Kind {
        /** Profile 4: {@code vdr_rpu_profile} 1, enhancement layer resampling, residual, {@code vdr_bit_depth} 14. */
        PROFILE_4,
        /** Profile 5: {@code vdr_rpu_profile} 0, full range base layer. */
        PROFILE_5,
        /** Profile 7 with a minimal enhancement layer: resampling, residual, {@code vdr_bit_depth} 12, zero NLQ. */
        PROFILE_7_MEL,
        /** Profile 7 with a full enhancement layer: as the minimal one, with a nonzero NLQ offset. */
        PROFILE_7_FEL,
        /** Profile 8: {@code vdr_rpu_profile} 1, residual disabled. */
        PROFILE_8
    }

    private final Kind kind;
    private boolean chromaMmr;
    private boolean compressedDm;
    private boolean cmV40;
    private boolean reusePrevious;
    private boolean fixedPoint;
    private int frame;

    private DolbyVisionRpus(Kind kind) {
        this.kind = kind;
    }

    /**
     * Returns a writer of RPUs of a kind, with polynomial chroma, uncompressed CM v2.9 display management data and
     * a mapping of its own.
     */
    public static DolbyVisionRpus of(Kind kind) {
        return new DolbyVisionRpus(kind);
    }

    /** Uses MMR chroma mappings of order 3 instead of polynomials. */
    public DolbyVisionRpus chromaMmr() {
        this.chromaMmr = true;
        return this;
    }

    /** Writes the compressed display management data (identifiers and extension blocks only). */
    public DolbyVisionRpus compressedDm() {
        this.compressedDm = true;
        return this;
    }

    /** Adds CM v4.0 display management data with a level 254 block. */
    public DolbyVisionRpus cmV40() {
        this.cmV40 = true;
        return this;
    }

    /** Sets {@code use_prev_vdr_rpu_flag}, so that the RPU has no mapping of its own. */
    public DolbyVisionRpus reusePrevious() {
        this.reusePrevious = true;
        return this;
    }

    /**
     * Writes {@code coefficient_data_type} 1: coefficients are 32-bit fixed-point fractions without Exp-Golomb
     * integer parts.
     */
    public DolbyVisionRpus fixedPoint() {
        this.fixedPoint = true;
        return this;
    }

    /** Sets the frame index, which moves the level 1 maximum so that consecutive RPUs differ. */
    public DolbyVisionRpus frame(int frame) {
        this.frame = frame;
        return this;
    }

    /** Returns the RPU, prefix byte {@code 0x19} first, without emulation prevention. */
    public byte[] rpu() {
        var bits = new Bits();
        var profile5 = kind == Kind.PROFILE_5;
        var residual = kind == Kind.PROFILE_4 || kind == Kind.PROFILE_7_MEL || kind == Kind.PROFILE_7_FEL;
        bits.put(8, 0x19);
        bits.put(6, 2);
        bits.put(11, 18);
        bits.put(4, profile5 ? 0 : 1);
        bits.put(4, 0);
        bits.put(1, 1);
        bits.put(1, 0);
        bits.put(2, fixedPoint ? 1 : 0);
        if (!fixedPoint) {
            bits.ue(23);
        }
        bits.put(2, 1);
        bits.put(1, profile5 ? 1 : 0);
        bits.ue(2);
        bits.ue(2);
        bits.ue(kind == Kind.PROFILE_4 ? 6 : 4);
        bits.put(1, 0);
        bits.put(3, compressedDm ? 1 : 0);
        bits.put(1, residual ? 1 : 0);
        bits.put(1, residual ? 0 : 1);
        bits.put(1, 1);
        bits.put(1, reusePrevious ? 1 : 0);
        if (reusePrevious) {
            bits.ue(0);
        } else {
            mapping(bits, residual);
        }
        displayManagement(bits);
        bits.align();
        var payload = bits.bytes();
        var crc = crc32(payload, 1, payload.length);
        var out = new ByteArrayOutputStream();
        out.writeBytes(payload);
        out.write(crc >>> 24);
        out.write(crc >>> 16);
        out.write(crc >>> 8);
        out.write(crc);
        out.write(0x80);
        return out.toByteArray();
    }

    /** Returns the RPU as an HEVC NAL unit of type 62: the header {@code 7C 01}, then the escaped RPU. */
    public byte[] hevcNal() {
        var rpu = rpu();
        var escaped = escape(rpu);
        var nal = new byte[2 + escaped.length];
        nal[0] = 0x7C;
        nal[1] = 0x01;
        System.arraycopy(escaped, 0, nal, 2, escaped.length);
        return nal;
    }

    /** Returns an HEVC access unit of four-byte length-prefixed NAL units with a NAL unit appended. */
    public static byte[] appendNal(byte[] accessUnit, byte[] nal) {
        var out = new byte[accessUnit.length + 4 + nal.length];
        System.arraycopy(accessUnit, 0, out, 0, accessUnit.length);
        var length = nal.length;
        out[accessUnit.length] = (byte) (length >>> 24);
        out[accessUnit.length + 1] = (byte) (length >>> 16);
        out[accessUnit.length + 2] = (byte) (length >>> 8);
        out[accessUnit.length + 3] = (byte) length;
        System.arraycopy(nal, 0, out, accessUnit.length + 4, nal.length);
        return out;
    }

    /** Returns the NAL units of type 62 of a four-byte length-prefixed HEVC access unit, in order. */
    public static List<byte[]> rpuNals(byte[] accessUnit) {
        var result = new ArrayList<byte[]>();
        for (var offset = 0; offset + 4 <= accessUnit.length; ) {
            var length = (accessUnit[offset] & 0xFF) << 24 | (accessUnit[offset + 1] & 0xFF) << 16
                    | (accessUnit[offset + 2] & 0xFF) << 8 | (accessUnit[offset + 3] & 0xFF);
            var start = offset + 4;
            if (length <= 0 || start + length > accessUnit.length) {
                break;
            }
            if (((accessUnit[start] >> 1) & 0x3F) == 62) {
                var nal = new byte[length];
                System.arraycopy(accessUnit, start, nal, 0, length);
                result.add(nal);
            }
            offset = start + length;
        }
        return result;
    }

    /** Returns the NAL unit payload with emulation prevention bytes inserted. */
    public static byte[] escape(byte[] data) {
        var out = new ByteArrayOutputStream(data.length + 16);
        var zeros = 0;
        for (var value : data) {
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

    /** Returns the CRC-32/MPEG-2 of a range. */
    public static int crc32(byte[] data, int from, int to) {
        var crc = 0xFFFFFFFF;
        for (var index = from; index < to; index++) {
            crc ^= (data[index] & 0xFF) << 24;
            for (var bit = 0; bit < 8; bit++) {
                crc = crc < 0 ? crc << 1 ^ 0x04C11DB7 : crc << 1;
            }
        }
        return crc;
    }

    private void mapping(Bits bits, boolean residual) {
        bits.ue(0);
        bits.ue(0);
        bits.ue(0);
        for (var component = 0; component < 3; component++) {
            bits.ue(component == 0 ? 1 : 0);
            bits.put(10, 0);
            if (component == 0) {
                bits.put(10, 512);
            }
            bits.put(10, 1023);
        }
        if (residual) {
            bits.put(3, 0);
            bits.put(10, 0);
            bits.put(10, 1023);
        }
        bits.ue(0);
        bits.ue(0);
        for (var component = 0; component < 3; component++) {
            var pieces = component == 0 ? 2 : 1;
            for (var piece = 0; piece < pieces; piece++) {
                if (component > 0 && chromaMmr) {
                    bits.ue(1);
                    bits.put(2, 2);
                    signed(bits, 0, 1 << 22);
                    for (var order = 0; order < 3; order++) {
                        for (var coefficient = 0; coefficient < 7; coefficient++) {
                            signed(bits, coefficient == 0 && order == 0 ? 1 : 0, coefficient * 1000 + order);
                        }
                    }
                } else {
                    bits.ue(0);
                    bits.ue(1);
                    signed(bits, 0, 0);
                    signed(bits, 1, 0);
                    signed(bits, 0, 12345);
                }
            }
        }
        if (residual) {
            var full = kind == Kind.PROFILE_7_FEL;
            for (var component = 0; component < 3; component++) {
                bits.put(10, full ? 512 : 0);
                unsigned(bits, 1, 0);
                unsigned(bits, 0, full ? 4096 : 0);
                unsigned(bits, 0, 0);
            }
        }
    }

    /** Writes a signed coefficient: its integer part unless the coefficients are fixed point, then its fraction. */
    private void signed(Bits bits, int integer, long fraction) {
        if (!fixedPoint) {
            bits.se(integer);
        }
        bits.put(fixedPoint ? 32 : 23, fraction);
    }

    /** Writes an unsigned coefficient: its integer part unless the coefficients are fixed point, then its fraction. */
    private void unsigned(Bits bits, int integer, long fraction) {
        if (!fixedPoint) {
            bits.ue(integer);
        }
        bits.put(fixedPoint ? 32 : 23, fraction);
    }

    private void displayManagement(Bits bits) {
        bits.ue(0);
        bits.ue(0);
        bits.ue(frame == 0 ? 1 : 0);
        if (!compressedDm) {
            for (var coefficient : new int[]{9574, 0, 13802, 9574, -1540, -5348, 9574, 17610, 0}) {
                bits.put(16, coefficient & 0xFFFF);
            }
            for (var offset : new long[]{16777216, 134217728, 134217728}) {
                bits.put(32, offset);
            }
            for (var coefficient : new int[]{7222, 8771, 390, 2654, 12430, 1300, 0, 422, 15962}) {
                bits.put(16, coefficient & 0xFFFF);
            }
            bits.put(16, 65535);
            bits.put(16, 0);
            bits.put(16, 0);
            bits.put(32, 0);
            bits.put(5, 12);
            bits.put(2, 0);
            bits.put(2, 0);
            bits.put(2, 1);
            bits.put(12, 62);
            bits.put(12, 3079);
            bits.put(10, 42);
        }
        bits.ue(2);
        bits.align();
        bits.ue(5);
        bits.put(8, 1);
        bits.put(12, 0);
        bits.put(12, 2081 + 40 * (frame % 16));
        bits.put(12, 1229);
        bits.put(4, 0);
        bits.ue(8);
        bits.put(8, 6);
        bits.put(16, 1000);
        bits.put(16, 1);
        bits.put(16, 1000);
        bits.put(16, 400);
        if (cmV40) {
            bits.ue(1);
            bits.align();
            bits.ue(2);
            bits.put(8, 254);
            bits.put(8, 0);
            bits.put(8, 2);
        }
    }

    private static final class Bits {
        private final List<Integer> bits = new ArrayList<>();

        void put(int count, long value) {
            for (var shift = count - 1; shift >= 0; shift--) {
                bits.add((int) (value >>> shift) & 1);
            }
        }

        void ue(long value) {
            var code = value + 1;
            var length = 64 - Long.numberOfLeadingZeros(code);
            put(length - 1, 0);
            put(length, code);
        }

        void se(long value) {
            ue(value > 0 ? 2 * value - 1 : -2 * value);
        }

        void align() {
            while (bits.size() % 8 != 0) {
                bits.add(0);
            }
        }

        byte[] bytes() {
            var out = new byte[(bits.size() + 7) / 8];
            for (var index = 0; index < bits.size(); index++) {
                out[index >> 3] |= (byte) (bits.get(index) << (7 - (index & 7)));
            }
            return out;
        }
    }
}
