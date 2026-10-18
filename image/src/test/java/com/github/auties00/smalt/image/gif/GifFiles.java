package com.github.auties00.smalt.image.gif;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;

/**
 * A tiny test-only GIF writer: logical screen, color tables, graphic control, application and
 * comment extensions, images with an LZW encoder that can clear or defer when the table fills, and
 * raw bytes for deliberately broken structures.
 */
final class GifFiles {
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    private GifFiles() {
    }

    /** Starts a GIF89a with a logical screen and an optional global color table (RGB ints). */
    static GifFiles create(int width, int height, int[] globalColors) {
        var files = new GifFiles();
        files.out.writeBytes("GIF89a".getBytes(StandardCharsets.US_ASCII));
        files.u16(width);
        files.u16(height);
        if (globalColors != null) {
            files.out.write(0x80 | 0x70 | (tableBits(globalColors.length) - 1));
        } else {
            files.out.write(0x70);
        }
        files.out.write(0);
        files.out.write(0);
        if (globalColors != null) {
            files.colors(globalColors);
        }
        return files;
    }

    static int tableBits(int count) {
        var bits = 1;
        while ((1 << bits) < count) {
            bits++;
        }
        return bits;
    }

    private void colors(int[] colors) {
        var size = 1 << tableBits(colors.length);
        for (var i = 0; i < size; i++) {
            var color = i < colors.length ? colors[i] : 0;
            out.write(color >>> 16);
            out.write(color >>> 8);
            out.write(color);
        }
    }

    private void u16(int value) {
        out.write(value);
        out.write(value >>> 8);
    }

    GifFiles loop(String application, int count) {
        out.write(0x21);
        out.write(0xFF);
        out.write(11);
        out.writeBytes(application.getBytes(StandardCharsets.US_ASCII));
        out.write(3);
        out.write(1);
        u16(count);
        out.write(0);
        return this;
    }

    GifFiles control(int disposal, int delay, int transparentIndex) {
        out.write(0x21);
        out.write(0xF9);
        out.write(4);
        out.write((disposal & 7) << 2 | (transparentIndex >= 0 ? 1 : 0));
        u16(delay);
        out.write(Math.max(transparentIndex, 0));
        out.write(0);
        return this;
    }

    GifFiles comment(String text) {
        out.write(0x21);
        out.write(0xFE);
        subBlocks(text.getBytes(StandardCharsets.US_ASCII));
        return this;
    }

    GifFiles plainText() {
        out.write(0x21);
        out.write(0x01);
        out.write(12);
        out.writeBytes(new byte[12]);
        subBlocks("hello".getBytes(StandardCharsets.US_ASCII));
        return this;
    }

    /** Writes an image whose indices are given in display (row-major) order. */
    GifFiles image(int left, int top, int width, int height, int[] localColors, boolean interlaced, byte[] indices,
                   int minimumCodeSize, boolean deferClear) {
        return imageData(left, top, width, height, localColors, interlaced, minimumCodeSize,
                lzw(interlaced ? interlace(indices, width, height) : indices, minimumCodeSize, deferClear));
    }

    /** Writes an image descriptor followed by caller-supplied LZW data bytes. */
    GifFiles imageData(int left, int top, int width, int height, int[] localColors, boolean interlaced,
                       int minimumCodeSize, byte[] lzw) {
        out.write(0x2C);
        u16(left);
        u16(top);
        u16(width);
        u16(height);
        var packed = (interlaced ? 0x40 : 0) | (localColors != null ? 0x80 | (tableBits(localColors.length) - 1) : 0);
        out.write(packed);
        if (localColors != null) {
            colors(localColors);
        }
        out.write(minimumCodeSize);
        subBlocks(lzw);
        return this;
    }

    GifFiles trailer() {
        out.write(0x3B);
        return this;
    }

    GifFiles raw(byte... bytes) {
        out.writeBytes(bytes);
        return this;
    }

    byte[] bytes() {
        return out.toByteArray();
    }

    private void subBlocks(byte[] data) {
        for (var offset = 0; offset < data.length; offset += 255) {
            var size = Math.min(255, data.length - offset);
            out.write(size);
            out.write(data, offset, size);
        }
        out.write(0);
    }

    static byte[] interlace(byte[] indices, int width, int height) {
        var out = new byte[indices.length];
        var row = 0;
        int[][] passes = {{0, 8}, {4, 8}, {2, 4}, {1, 2}};
        for (var pass : passes) {
            for (var y = pass[0]; y < height; y += pass[1]) {
                System.arraycopy(indices, y * width, out, row++ * width, width);
            }
        }
        return out;
    }

    /** LZW-encodes indices; when the table fills, emits a clear code or (deferClear) keeps going with 12-bit codes. */
    static byte[] lzw(byte[] indices, int minimumCodeSize, boolean deferClear) {
        var writer = new BitWriter();
        var clear = 1 << minimumCodeSize;
        var codeSize = minimumCodeSize + 1;
        var maxCode = clear + 1;
        var table = new HashMap<Integer, Integer>();
        writer.write(clear, codeSize);
        var current = -1;
        var full = false;
        for (var value : indices) {
            var index = value & 0xFF;
            if (current < 0) {
                current = index;
                continue;
            }
            var key = current << 8 | index;
            var found = table.get(key);
            if (found != null) {
                current = found;
                continue;
            }
            writer.write(current, codeSize);
            if (!full) {
                table.put(key, ++maxCode);
                if (maxCode >= (1 << codeSize) && codeSize < 12) {
                    codeSize++;
                }
                if (maxCode == 4095) {
                    if (deferClear) {
                        full = true;
                    } else {
                        writer.write(clear, codeSize);
                        table.clear();
                        codeSize = minimumCodeSize + 1;
                        maxCode = clear + 1;
                    }
                }
            }
            current = index;
        }
        if (current >= 0) {
            writer.write(current, codeSize);
        }
        writer.write(clear + 1, codeSize);
        return writer.bytes();
    }

    /** Writes LZW codes of explicit sizes, for hand-crafted streams. */
    static final class BitWriter {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private long bits;
        private int count;

        BitWriter write(int code, int size) {
            bits |= (long) code << count;
            count += size;
            while (count >= 8) {
                out.write((int) bits & 0xFF);
                bits >>>= 8;
                count -= 8;
            }
            return this;
        }

        byte[] bytes() {
            if (count > 0) {
                out.write((int) bits & 0xFF);
                bits = 0;
                count = 0;
            }
            return out.toByteArray();
        }
    }
}
