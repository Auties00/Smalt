package com.github.auties00.smalt.image.png;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Builds PNG and APNG datastreams chunk by chunk, so tests can produce exactly the structure
 * (valid or deliberately broken) they need.
 */
final class PngFiles {
    static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    private PngFiles() {
        out.writeBytes(SIGNATURE);
    }

    static PngFiles create() {
        return new PngFiles();
    }

    static PngFiles withoutSignature() {
        var files = new PngFiles();
        files.out.reset();
        return files;
    }

    PngFiles chunk(String type, byte[] data) {
        return chunk(type, data, false);
    }

    PngFiles chunk(String type, byte[] data, boolean corruptCrc) {
        var typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        out.writeBytes(be32(data.length));
        out.writeBytes(typeBytes);
        out.writeBytes(data);
        var crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        out.writeBytes(be32((int) crc.getValue() ^ (corruptCrc ? 1 : 0)));
        return this;
    }

    PngFiles raw(byte[] bytes) {
        out.writeBytes(bytes);
        return this;
    }

    PngFiles ihdr(int width, int height, int depth, int colorType, int interlace) {
        var data = new byte[13];
        put32(data, 0, width);
        put32(data, 4, height);
        data[8] = (byte) depth;
        data[9] = (byte) colorType;
        data[12] = (byte) interlace;
        return chunk("IHDR", data);
    }

    PngFiles idat(byte[] zlib) {
        return chunk("IDAT", zlib);
    }

    PngFiles iend() {
        return chunk("IEND", new byte[0]);
    }

    PngFiles actl(int frames, int plays) {
        var data = new byte[8];
        put32(data, 0, frames);
        put32(data, 4, plays);
        return chunk("acTL", data);
    }

    PngFiles fctl(int sequence, int width, int height, int x, int y, int delayNum, int delayDen, int dispose, int blend) {
        var data = new byte[26];
        put32(data, 0, sequence);
        put32(data, 4, width);
        put32(data, 8, height);
        put32(data, 12, x);
        put32(data, 16, y);
        data[20] = (byte) (delayNum >>> 8);
        data[21] = (byte) delayNum;
        data[22] = (byte) (delayDen >>> 8);
        data[23] = (byte) delayDen;
        data[24] = (byte) dispose;
        data[25] = (byte) blend;
        return chunk("fcTL", data);
    }

    PngFiles fdat(int sequence, byte[] zlib) {
        var data = new byte[4 + zlib.length];
        put32(data, 0, sequence);
        System.arraycopy(zlib, 0, data, 4, zlib.length);
        return chunk("fdAT", data);
    }

    byte[] bytes() {
        return out.toByteArray();
    }

    static byte[] be32(int value) {
        return new byte[]{(byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value};
    }

    static void put32(byte[] data, int offset, int value) {
        System.arraycopy(be32(value), 0, data, offset, 4);
    }

    static byte[] zlib(byte[] raw) {
        var deflater = new Deflater(9);
        deflater.setInput(raw);
        deflater.finish();
        var out = new ByteArrayOutputStream();
        var buffer = new byte[4096];
        while (!deflater.finished()) {
            out.write(buffer, 0, deflater.deflate(buffer));
        }
        deflater.end();
        return out.toByteArray();
    }

    /** Prefixes every row of tightly packed samples with filter type 0. */
    static byte[] unfiltered(byte[] samples, int rowBytes) {
        var rows = samples.length / rowBytes;
        var out = new byte[rows * (rowBytes + 1)];
        for (var y = 0; y < rows; y++) {
            System.arraycopy(samples, y * rowBytes, out, y * (rowBytes + 1) + 1, rowBytes);
        }
        return out;
    }

    /** Builds a complete non-interlaced RGBA8 PNG of the given pixels. */
    static byte[] rgba(int width, int height, byte[] pixels) {
        return create().ihdr(width, height, 8, 6, 0).idat(zlib(unfiltered(pixels, width * 4))).iend().bytes();
    }

    /** Builds a 1x1 RGBA8 PNG with one extra chunk before IDAT. */
    static byte[] rgbaWithChunk(String type, byte[] data) {
        return create().ihdr(1, 1, 8, 6, 0).chunk(type, data).idat(zlib(new byte[]{0, 1, 2, 3, 4})).iend().bytes();
    }

    static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.ISO_8859_1);
    }
}
