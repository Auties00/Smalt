package com.github.auties00.smalt.image.png;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.internal.zlib.Zlib;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.io.ByteWriter;

import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Writes a PNG datastream: the signature, then chunks framed with their length, type and CRC.
 *
 * <p>The datastream must fit a Java array; a chunk that would make it larger fails with
 * {@link MediaException.LimitExceeded} and is not written. Instances are not thread-safe.
 */
final class PngChunkWriter {
    /**
     * The largest datastream written, the largest Java array.
     */
    private static final long MAX_SIZE = Integer.MAX_VALUE - 16;

    /**
     * The keyword of the {@code iCCP} chunks written.
     */
    private static final byte[] ICC_NAME = "ICC profile".getBytes(StandardCharsets.ISO_8859_1);

    /**
     * The bytes written.
     */
    private final ByteWriter out;

    /**
     * The CRC calculator reused for every chunk.
     */
    private final CRC32 crc = new CRC32();

    /**
     * The largest datastream accepted.
     */
    private final long maxSize;

    /**
     * Constructs a writer and writes the signature.
     *
     * @param capacity the initial capacity in bytes
     */
    PngChunkWriter(int capacity) {
        this(capacity, MAX_SIZE);
    }

    /**
     * Constructs a writer with a smaller size limit and writes the signature.
     *
     * @param capacity the initial capacity in bytes
     * @param maxSize the largest datastream accepted, at most the largest Java array
     */
    PngChunkWriter(int capacity, long maxSize) {
        this.out = new ByteWriter(capacity);
        this.maxSize = Math.min(maxSize, MAX_SIZE);
        out.bytes(PngChunks.SIGNATURE);
    }

    /**
     * Writes the chunks before the image data: {@code IHDR} of an 8-bit non-interlaced image,
     * {@code iCCP} when a profile is given, then {@code PLTE} and, when some entry is translucent,
     * {@code tRNS} for an indexed image.
     *
     * @param width the image width in pixels
     * @param height the image height in pixels
     * @param colorType the color type: indexed, truecolor or truecolor with alpha
     * @param palette the ordered palette of an indexed image, or {@code null}
     * @param iccProfile the ICC profile to embed, or {@code null} for none
     * @throws MediaException.LimitExceeded if the datastream would exceed the size limit
     */
    void header(int width, int height, int colorType, PngPalette palette, byte[] iccProfile) {
        var ihdr = new byte[13];
        ByteArrays.put32be(ihdr, 0, width);
        ByteArrays.put32be(ihdr, 4, height);
        ihdr[8] = 8;
        ihdr[9] = (byte) colorType;
        chunk(PngChunks.IHDR, ihdr, 0, ihdr.length);
        if (iccProfile != null) {
            var compressed = Zlib.deflate(iccProfile, Deflater.BEST_COMPRESSION);
            var data = new byte[ICC_NAME.length + 2 + compressed.length];
            System.arraycopy(ICC_NAME, 0, data, 0, ICC_NAME.length);
            System.arraycopy(compressed, 0, data, ICC_NAME.length + 2, compressed.length);
            chunk(PngChunks.ICCP, data, 0, data.length);
        }
        if (palette != null) {
            var plte = new byte[palette.size() * 3];
            for (var i = 0; i < palette.size(); i++) {
                var color = palette.color(i);
                plte[i * 3] = (byte) color;
                plte[i * 3 + 1] = (byte) (color >>> 8);
                plte[i * 3 + 2] = (byte) (color >>> 16);
            }
            chunk(PngChunks.PLTE, plte, 0, plte.length);
            if (palette.translucentCount() > 0) {
                var trns = new byte[palette.translucentCount()];
                for (var i = 0; i < trns.length; i++) {
                    trns[i] = (byte) (palette.color(i) >>> 24);
                }
                chunk(PngChunks.TRNS, trns, 0, trns.length);
            }
        }
    }

    /**
     * Writes a chunk.
     *
     * @param type the chunk type
     * @param data the array holding the chunk data
     * @param offset the index of the first data byte
     * @param length the number of data bytes
     * @throws MediaException.LimitExceeded if the datastream would exceed the size limit
     */
    void chunk(int type, byte[] data, int offset, int length) {
        chunk(type, -1, data, offset, length);
    }

    /**
     * Writes a chunk whose data starts with a sequence number, as {@code fcTL} and {@code fdAT}
     * do.
     *
     * @param type the chunk type
     * @param sequence the sequence number written before the data, or -1 for none
     * @param data the array holding the rest of the chunk data
     * @param offset the index of the first data byte
     * @param length the number of data bytes
     * @throws MediaException.LimitExceeded if the datastream would exceed the size limit
     */
    void chunk(int type, int sequence, byte[] data, int offset, int length) {
        var prefix = sequence >= 0 ? 4 : 0;
        var start = out.size();
        if (start + 12L + prefix + length > maxSize) {
            throw new MediaException.LimitExceeded("encoded PNG exceeds " + maxSize + " bytes");
        }
        out.u32be(length + prefix);
        out.u32be(type);
        if (prefix > 0) {
            out.u32be(sequence);
        }
        out.bytes(data, offset, length);
        crc.reset();
        crc.update(out.buffer(), start + 4, length + prefix + 4);
        out.u32be(crc.getValue());
    }

    /**
     * Writes image data split into chunks.
     *
     * @param type {@code IDAT} or {@code fdAT}
     * @param data the zlib stream
     * @param sequence the sequence number of the first {@code fdAT}, or -1 for {@code IDAT}
     * @param chunkSize the largest number of data bytes per chunk
     * @return the sequence number after the last chunk, or -1 for {@code IDAT}
     * @throws MediaException.LimitExceeded if the datastream would exceed the size limit
     */
    int data(int type, byte[] data, int sequence, int chunkSize) {
        var offset = 0;
        do {
            var length = Math.min(chunkSize, data.length - offset);
            chunk(type, sequence, data, offset, length);
            offset += length;
            if (sequence >= 0) {
                sequence++;
            }
        } while (offset < data.length);
        return sequence;
    }

    /**
     * Writes the {@code IEND} chunk.
     *
     * @throws MediaException.LimitExceeded if the datastream would exceed the size limit
     */
    void end() {
        chunk(PngChunks.IEND, new byte[0], 0, 0);
    }

    /**
     * {@return a copy of the datastream written so far}
     */
    byte[] toByteArray() {
        return out.toByteArray();
    }
}
