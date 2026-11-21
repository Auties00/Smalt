package com.github.auties00.smalt.image.jxl.internal.encode;

/**
 * Writes a Brotli stream (RFC 7932) of uncompressed meta-blocks.
 *
 * <p>The stream decodes to the input with any Brotli decoder; it holds the bytes as they are, in meta-blocks of
 * at most 2^24 bytes followed by an empty last meta-block.
 */
final class BrotliWriter {
    private static final int MAX_BLOCK = 1 << 24;

    private BrotliWriter() {
        throw new AssertionError();
    }

    /**
     * Wraps bytes in a Brotli stream.
     *
     * @param data the bytes
     * @return the stream
     */
    static byte[] compress(byte[] data) {
        var w = new JxlBitWriter(data.length + 16);
        w.write(1, 0);
        for (var offset = 0; offset < data.length; offset += MAX_BLOCK) {
            var length = Math.min(MAX_BLOCK, data.length - offset);
            var m = length - 1;
            int nibbles;
            if (m < 1 << 16) {
                nibbles = 4;
            } else if (m < 1 << 20) {
                nibbles = 5;
            } else {
                nibbles = 6;
            }
            w.write(1, 0);
            w.write(2, nibbles - 4);
            w.write(4 * nibbles, m);
            w.write(1, 1);
            w.zeroPadToByte();
            w.appendBytes(data, offset, length);
        }
        w.write(1, 1);
        w.write(1, 1);
        w.zeroPadToByte();
        return w.toByteArray();
    }
}
