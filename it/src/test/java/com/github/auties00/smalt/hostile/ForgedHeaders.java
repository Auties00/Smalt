package com.github.auties00.smalt.hostile;

import java.io.ByteArrayOutputStream;

/**
 * Builds minimal, syntactically valid parameter sets and sequence headers that declare an arbitrary picture size,
 * followed by a slice or frame whose payload is junk: a decoder that checks the declared size against its limits
 * rejects the stream when it activates the header, before it allocates a picture.
 */
final class ForgedHeaders {
    private ForgedHeaders() {
        throw new AssertionError();
    }

    /** An H.264 stream: its {@code avcC} record and one IDR access unit, length-prefixed with four bytes. */
    record Stream(byte[] config, byte[] packet) {
    }

    static Stream h264(int width, int height) {
        var nals = h264Nals(width, height);
        var config = new ByteArrayOutputStream();
        config.writeBytes(new byte[]{1, 66, (byte) 0xC0, 51, (byte) 0xFF, (byte) 0xE1});
        u16(config, nals[0].length);
        config.writeBytes(nals[0]);
        config.write(1);
        u16(config, nals[1].length);
        config.writeBytes(nals[1]);
        return new Stream(config.toByteArray(), lengthPrefixed(nals[2]));
    }

    /** Returns the SPS, PPS and IDR slice of {@link #h264} as an Annex B byte stream. */
    static byte[] h264AnnexB(int width, int height) {
        var out = new ByteArrayOutputStream();
        for (var nal : h264Nals(width, height)) {
            out.writeBytes(new byte[]{0, 0, 0, 1});
            out.writeBytes(nal);
        }
        return out.toByteArray();
    }

    private static byte[][] h264Nals(int width, int height) {
        var sps = new Hostile.MsbBits()
                .u(8, 66).u(8, 0xC0).u(8, 51)
                .ue(0).ue(0).ue(2).ue(1).u(1, 0)
                .ue(Math.ceilDiv(width, 16) - 1).ue(Math.ceilDiv(height, 16) - 1)
                .u(1, 1).u(1, 1).u(1, 0).u(1, 0)
                .trailing();
        var pps = new Hostile.MsbBits()
                .ue(0).ue(0).u(1, 0).u(1, 0).ue(0).ue(0).ue(0).u(1, 0).u(2, 0)
                .se(0).se(0).se(0).u(1, 1).u(1, 0).u(1, 0)
                .trailing();
        var slice = new Hostile.MsbBits()
                .ue(0).ue(7).ue(0).u(4, 0).ue(0).u(1, 0).u(1, 0).se(0).ue(1)
                .u(32, 0x5A5A_5A5AL).u(32, 0x1234_5678L)
                .trailing();
        return new byte[][]{nal(0x67, sps), nal(0x68, pps), nal(0x65, slice)};
    }

    static Stream hevc(int width, int height) {
        var vps = new Hostile.MsbBits().u(4, 0).u(1, 1).u(1, 1).u(6, 0).u(3, 0).u(1, 1).u(16, 0xFFFF);
        profileTierLevel(vps);
        var vpsRbsp = vps.u(1, 1).ue(0).ue(0).ue(0).u(6, 0).ue(0).u(1, 0).u(1, 0).trailing();
        var sps = new Hostile.MsbBits().u(4, 0).u(3, 0).u(1, 1);
        profileTierLevel(sps);
        var spsRbsp = sps.ue(0).ue(1).ue(width).ue(height).u(1, 0).ue(0).ue(0).ue(4)
                .u(1, 1).ue(0).ue(0).ue(0)
                .ue(0).ue(1).ue(0).ue(2).ue(0).ue(0)
                .u(1, 0).u(1, 0).u(1, 0).u(1, 0)
                .ue(0).u(1, 0).u(1, 0).u(1, 0).u(1, 0).u(1, 0)
                .trailing();
        var ppsRbsp = new Hostile.MsbBits()
                .ue(0).ue(0).u(1, 0).u(1, 0).u(3, 0).u(1, 0).u(1, 0).ue(0).ue(0).se(0)
                .u(1, 0).u(1, 0).u(1, 0).se(0).se(0).u(1, 0).u(1, 0).u(1, 0).u(1, 0).u(1, 0).u(1, 0)
                .u(1, 0).u(1, 0).u(1, 0).u(1, 0).ue(0).u(1, 0).u(1, 0)
                .trailing();
        var sliceRbsp = new Hostile.MsbBits()
                .u(1, 1).u(1, 0).ue(0).ue(2).se(0).byteAlignment()
                .u(32, 0x5A5A_5A5AL).u(32, 0x1234_5678L)
                .trailing();
        var vpsNal = nal2(32, vpsRbsp);
        var spsNal = nal2(33, spsRbsp);
        var ppsNal = nal2(34, ppsRbsp);
        var config = new ByteArrayOutputStream();
        config.write(1);
        config.write(1);
        u32(config, 0x6000_0000L);
        config.writeBytes(new byte[]{(byte) 0x90, 0, 0, 0, 0, 0});
        config.write(186);
        u16(config, 0xF000);
        config.write(0xFC);
        config.write(0xFD);
        config.write(0xF8);
        config.write(0xF8);
        u16(config, 0);
        config.write(0x0F);
        config.write(3);
        for (var nal : new byte[][]{vpsNal, spsNal, ppsNal}) {
            config.write(0x80 | (nal[0] >> 1 & 0x3F));
            u16(config, 1);
            u16(config, nal.length);
            config.writeBytes(nal);
        }
        return new Stream(config.toByteArray(), lengthPrefixed(nal2(19, sliceRbsp)));
    }

    private static void profileTierLevel(Hostile.MsbBits bits) {
        bits.u(2, 0).u(1, 0).u(5, 1).u(32, 0x6000_0000L).u(1, 1).u(1, 0).u(1, 0).u(1, 1).u(32, 0).u(11, 0).u(1, 0)
                .u(8, 186);
    }

    /**
     * Returns an AV1 sequence header OBU (with its size field) declaring the largest frame size, the general
     * (not reduced) syntax, 8-bit 4:2:0.
     */
    static byte[] av1SequenceHeader(int width, int height) {
        var payload = new Hostile.MsbBits()
                .u(3, 0).u(1, 0).u(1, 0)
                .u(1, 0).u(1, 0).u(5, 0).u(12, 0).u(5, 8).u(1, 0)
                .u(4, 15).u(4, 15).u(16, width - 1).u(16, height - 1)
                .u(1, 0).u(1, 0).u(1, 0).u(1, 0)
                .u(1, 0).u(1, 0).u(1, 0).u(1, 0).u(1, 0)
                .u(1, 1).u(1, 1)
                .u(1, 0).u(1, 0).u(1, 0)
                .u(1, 0).u(1, 0).u(1, 0).u(1, 0).u(2, 0).u(1, 0)
                .u(1, 0)
                .trailing();
        return obu(1, payload);
    }

    /** Returns an AV1 temporal unit: a temporal delimiter, the sequence header and a junk frame OBU. */
    static byte[] av1TemporalUnit(int width, int height) {
        var out = new ByteArrayOutputStream();
        out.writeBytes(obu(2, new byte[0]));
        out.writeBytes(av1SequenceHeader(width, height));
        out.writeBytes(obu(6, new byte[]{0x10, 0x5A, 0x5A, 0x5A, 0x12, 0x34, 0x56, 0x78}));
        return out.toByteArray();
    }

    /** Returns the {@code av1C} record carrying a sequence header. */
    static byte[] av1Config(int width, int height) {
        var out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{(byte) 0x81, 0x08, 0x0C, 0});
        out.writeBytes(av1SequenceHeader(width, height));
        return out.toByteArray();
    }

    /** Returns an MPEG-4 Part 2 visual object sequence header, object and object layer, then a junk I-VOP. */
    static Stream mpeg4(int width, int height) {
        var out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{0, 0, 1, (byte) 0xB0, 1});
        out.writeBytes(new byte[]{0, 0, 1, (byte) 0xB5, 0x09});
        out.writeBytes(new byte[]{0, 0, 1, 0});
        out.writeBytes(new byte[]{0, 0, 1, 0x20});
        var vol = new Hostile.MsbBits()
                .u(1, 0).u(8, 1).u(1, 0).u(4, 1).u(1, 0).u(2, 0).u(1, 1).u(16, 25).u(1, 1).u(1, 0).u(1, 1)
                .u(13, width).u(1, 1).u(13, height).u(1, 1)
                .u(1, 0).u(1, 1).u(1, 0).u(1, 0).u(1, 0).u(1, 1).u(1, 1).u(1, 0).u(1, 0);
        out.writeBytes(stuffed(vol));
        var config = out.toByteArray();
        var vop = new ByteArrayOutputStream();
        vop.writeBytes(new byte[]{0, 0, 1, (byte) 0xB6});
        var header = new Hostile.MsbBits().u(2, 0).u(1, 0).u(1, 1).u(5, 0).u(1, 1).u(1, 1).u(3, 0).u(5, 8)
                .u(32, 0x5A5A_5A5AL);
        vop.writeBytes(stuffed(header));
        return new Stream(config, vop.toByteArray());
    }

    private static byte[] stuffed(Hostile.MsbBits bits) {
        bits.u(1, 0);
        return bits.alignOnes();
    }

    private static byte[] obu(int type, byte[] payload) {
        var out = new ByteArrayOutputStream();
        out.write(type << 3 | 2);
        var size = payload.length;
        do {
            var low = size & 0x7F;
            size >>>= 7;
            out.write(size != 0 ? low | 0x80 : low);
        } while (size != 0);
        out.writeBytes(payload);
        return out.toByteArray();
    }

    private static byte[] nal(int header, byte[] rbsp) {
        var out = new ByteArrayOutputStream();
        out.write(header);
        escape(rbsp, out);
        return out.toByteArray();
    }

    private static byte[] nal2(int type, byte[] rbsp) {
        var out = new ByteArrayOutputStream();
        out.write(type << 1);
        out.write(1);
        escape(rbsp, out);
        return out.toByteArray();
    }

    private static void escape(byte[] rbsp, ByteArrayOutputStream out) {
        var zeros = 0;
        for (var value : rbsp) {
            if (zeros >= 2 && (value & 0xFF) <= 3) {
                out.write(3);
                zeros = 0;
            }
            out.write(value);
            zeros = value == 0 ? zeros + 1 : 0;
        }
    }

    private static byte[] lengthPrefixed(byte[] nal) {
        var out = new ByteArrayOutputStream();
        u32(out, nal.length);
        out.writeBytes(nal);
        return out.toByteArray();
    }

    private static void u16(ByteArrayOutputStream out, int value) {
        out.write(value >>> 8);
        out.write(value);
    }

    private static void u32(ByteArrayOutputStream out, long value) {
        out.write((int) (value >>> 24));
        out.write((int) (value >>> 16));
        out.write((int) (value >>> 8));
        out.write((int) value);
    }
}
