package com.github.auties00.smalt.container.mp4;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds ISO base media files byte by byte for edge-case tests. The file layout is
 * {@code ftyp, mdat, moov}, so chunk offsets are known before the {@code moov} is built:
 * {@link #DATA_START} is the offset of the first media byte.
 */
final class Mp4Builder {
    /** The size of the ftyp box written by {@link #file}. */
    static final int FTYP_SIZE = 20;

    /** The offset of the first media data byte in files built by {@link #file}. */
    static final int DATA_START = FTYP_SIZE + 8;

    private Mp4Builder() {
        throw new AssertionError();
    }

    static byte[] concat(byte[]... parts) {
        var out = new ByteArrayOutputStream();
        for (var part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    static byte[] u8(int value) {
        return new byte[]{(byte) value};
    }

    static byte[] u16(int value) {
        return new byte[]{(byte) (value >> 8), (byte) value};
    }

    static byte[] u32(long value) {
        return new byte[]{(byte) (value >> 24), (byte) (value >> 16), (byte) (value >> 8), (byte) value};
    }

    static byte[] u64(long value) {
        return concat(u32(value >>> 32), u32(value));
    }

    static byte[] fourCc(String code) {
        return code.getBytes(StandardCharsets.ISO_8859_1);
    }

    static byte[] zeros(int count) {
        return new byte[count];
    }

    static byte[] box(String type, byte[]... parts) {
        var body = concat(parts);
        return concat(u32(8 + body.length), fourCc(type), body);
    }

    static byte[] largeBox(String type, byte[]... parts) {
        var body = concat(parts);
        return concat(u32(1), fourCc(type), u64(16 + body.length), body);
    }

    static byte[] full(String type, int version, int flags, byte[]... parts) {
        return box(type, concat(u32(((long) version << 24) | flags), concat(parts)));
    }

    static byte[] ftyp() {
        return box("ftyp", fourCc("isom"), u32(0x200), fourCc("isom"));
    }

    static byte[] mvhd(int timescale, long duration) {
        return full("mvhd", 0, 0, u32(0), u32(0), u32(timescale), u32(duration), u32(0x10000), u16(0x100), zeros(10),
                matrix(new int[]{0x10000, 0, 0, 0, 0x10000, 0, 0, 0, 0x40000000}), zeros(24), u32(2));
    }

    static byte[] matrix(int[] values) {
        var out = new ByteArrayOutputStream();
        for (var value : values) {
            out.writeBytes(u32(value));
        }
        return out.toByteArray();
    }

    static byte[] tkhd(int id, int width, int height, int[] matrix) {
        return full("tkhd", 0, 3, u32(0), u32(0), u32(id), u32(0), u32(0), zeros(8), u16(0), u16(0), u16(0), u16(0),
                matrix(matrix), u32((long) width << 16), u32((long) height << 16));
    }

    static byte[] mdhd(int timescale, long duration) {
        return full("mdhd", 0, 0, u32(0), u32(0), u32(timescale), u32(duration), u16(0x55C4), u16(0));
    }

    static byte[] hdlr(String handler) {
        return full("hdlr", 0, 0, u32(0), fourCc(handler), zeros(12), u8(0));
    }

    static byte[] visualEntry(String format, int width, int height, byte[]... children) {
        return box(format, zeros(6), u16(1), zeros(16), u16(width), u16(height), u32(0x480000), u32(0x480000), u32(0),
                u16(1), zeros(32), u16(0x18), u16(0xFFFF), concat(children));
    }

    static byte[] audioEntry(String format, int channels, int rate, byte[]... children) {
        return box(format, zeros(6), u16(1), u16(0), u16(0), u32(0), u16(channels), u16(16), u16(0), u16(0),
                u32((long) rate << 16), concat(children));
    }

    static byte[] stsd(byte[] entry) {
        return full("stsd", 0, 0, u32(1), entry);
    }

    /** Builds a table box of 32-bit entries prefixed by their count; {@code width} values per entry. */
    static byte[] table(String type, int version, int width, long... values) {
        var out = new ByteArrayOutputStream();
        out.writeBytes(u32(values.length / width));
        for (var value : values) {
            out.writeBytes(u32(value));
        }
        return full(type, version, 0, out.toByteArray());
    }

    static byte[] stsz(int constant, int... sizes) {
        var out = new ByteArrayOutputStream();
        out.writeBytes(u32(constant));
        out.writeBytes(u32(sizes.length));
        if (constant == 0) {
            for (var size : sizes) {
                out.writeBytes(u32(size));
            }
        }
        return full("stsz", 0, 0, out.toByteArray());
    }

    static byte[] stszCount(int constant, long count) {
        return full("stsz", 0, 0, u32(constant), u32(count));
    }

    static byte[] co64(long... offsets) {
        var out = new ByteArrayOutputStream();
        out.writeBytes(u32(offsets.length));
        for (var offset : offsets) {
            out.writeBytes(u64(offset));
        }
        return full("co64", 0, 0, out.toByteArray());
    }

    static byte[] trak(int id, String handler, int timescale, long duration, byte[] entry, byte[]... tables) {
        var minfHeader = handler.equals("soun") ? full("smhd", 0, 0, u32(0)) : full("vmhd", 0, 1, zeros(8));
        return box("trak", tkhd(id, handler.equals("vide") ? 64 : 0, handler.equals("vide") ? 48 : 0,
                        new int[]{0x10000, 0, 0, 0, 0x10000, 0, 0, 0, 0x40000000}),
                box("mdia", mdhd(timescale, duration), hdlr(handler),
                        box("minf", minfHeader, box("stbl", stsd(entry), concat(tables)))));
    }

    static byte[] avc1() {
        return visualEntry("avc1", 64, 48, box("avcC", new byte[]{1, 66, 0, 30, (byte) 0xFF, (byte) 0xE1, 0, 4, 0x67, 66, 0, 30, 1, 0, 2, 0x68, 0x40}));
    }

    /** Builds a file: ftyp, an mdat with the given data, then a moov with the given children. */
    static byte[] file(byte[] data, byte[]... moovChildren) {
        return concat(ftyp(), box("mdat", data), box("moov", concat(moovChildren)));
    }

    /** Returns {@code count} samples of {@code size} bytes each, sample {@code i} filled with {@code i}. */
    static byte[] samples(int count, int size) {
        var out = new byte[count * size];
        for (var index = 0; index < count; index++) {
            java.util.Arrays.fill(out, index * size, (index + 1) * size, (byte) index);
        }
        return out;
    }

    static List<Long> offsets(int count, int size) {
        var result = new ArrayList<Long>();
        for (var index = 0; index < count; index++) {
            result.add((long) DATA_START + (long) index * size);
        }
        return result;
    }
}
