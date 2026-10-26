package com.github.auties00.smalt.image.jxl;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Builds JPEG XL containers around a codestream for the tests: boxes, {@code brob} boxes whose Brotli streams store
 * their content uncompressed (RFC 7932 meta-blocks with {@code ISUNCOMPRESSED} set), and the signature and
 * {@code ftyp} boxes.
 */
final class JxlBoxes {
    private JxlBoxes() {
    }

    static byte[] box(String type, byte[] payload) {
        var out = new ByteArrayOutputStream();
        var size = 8 + payload.length;
        out.writeBytes(new byte[]{(byte) (size >>> 24), (byte) (size >>> 16), (byte) (size >>> 8), (byte) size});
        out.writeBytes(type.getBytes(StandardCharsets.ISO_8859_1));
        out.writeBytes(payload);
        return out.toByteArray();
    }

    static byte[] brob(String type, byte[] content) {
        var out = new ByteArrayOutputStream();
        out.writeBytes(type.getBytes(StandardCharsets.ISO_8859_1));
        out.writeBytes(storedBrotli(content));
        return box("brob", out.toByteArray());
    }

    static byte[] container(byte[] codestream, byte[]... boxes) {
        var out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{0, 0, 0, 12, 'J', 'X', 'L', ' ', 13, 10, (byte) 0x87, 10});
        out.writeBytes(box("ftyp", new byte[]{'j', 'x', 'l', ' ', 0, 0, 0, 0, 'j', 'x', 'l', ' '}));
        out.writeBytes(box("jxlc", codestream));
        for (var box : boxes) {
            out.writeBytes(box);
        }
        return out.toByteArray();
    }

    /**
     * Writes a Brotli stream of uncompressed meta-blocks of at most 65536 bytes: a window of 16 bits, each block's
     * header (ISLAST 0, four nibbles of length minus one, ISUNCOMPRESSED 1) padded to a byte, its bytes, and a last
     * empty meta-block.
     */
    static byte[] storedBrotli(byte[] content) {
        var bits = new Bits();
        bits.write(1, 0);
        for (var start = 0; start < content.length; start += 65536) {
            var length = Math.min(65536, content.length - start);
            bits.write(1, 0);
            bits.write(2, 0);
            bits.write(16, length - 1);
            bits.write(1, 1);
            bits.pad();
            for (var i = 0; i < length; i++) {
                bits.write(8, content[start + i] & 0xFF);
            }
        }
        bits.write(1, 1);
        bits.write(1, 1);
        bits.pad();
        return bits.out.toByteArray();
    }

    private static final class Bits {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private int current;
        private int count;

        void write(int n, int value) {
            for (var i = 0; i < n; i++) {
                current |= ((value >>> i) & 1) << count;
                if (++count == 8) {
                    out.write(current);
                    current = 0;
                    count = 0;
                }
            }
        }

        void pad() {
            if (count > 0) {
                out.write(current);
                current = 0;
                count = 0;
            }
        }
    }
}
