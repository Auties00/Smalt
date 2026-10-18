package com.github.auties00.smalt.image.webp.internal.riff;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.io.ByteWriter;

import java.util.List;

/**
 * Assembles WebP files from chunk payloads.
 *
 * <p>Every chunk is written as its four-character tag, its little-endian 32-bit payload size and
 * the payload, followed by a zero byte when the size is odd. The RIFF header's size covers
 * everything after its first 8 bytes. This class is thread-safe.
 */
public final class WebpWriter {
    /**
     * Prevents instantiation.
     */
    private WebpWriter() {
        throw new AssertionError();
    }

    /**
     * {@return the number of bytes a chunk occupies, header and padding included}
     *
     * @param payloadSize the payload size in bytes
     */
    public static long diskSize(long payloadSize) {
        return 8 + payloadSize + (payloadSize & 1);
    }

    /**
     * Serializes one chunk.
     *
     * @param fourCc the chunk tag, packed big-endian
     * @param payload the payload bytes
     * @return the chunk bytes, padded to an even length
     * @throws MediaException.LimitExceeded if the chunk would exceed the largest Java array
     */
    public static byte[] chunk(int fourCc, byte[] payload) {
        return chunk(fourCc, payload, 0, payload.length);
    }

    /**
     * Serializes one chunk from a payload range.
     *
     * @param fourCc the chunk tag, packed big-endian
     * @param payload the array holding the payload
     * @param offset the index of the first payload byte
     * @param length the payload size in bytes
     * @return the chunk bytes, padded to an even length
     * @throws MediaException.LimitExceeded if the chunk would exceed the largest Java array
     */
    public static byte[] chunk(int fourCc, byte[] payload, int offset, int length) {
        var size = diskSize(length);
        if (size > Integer.MAX_VALUE - 16) {
            throw new MediaException.LimitExceeded("chunk of " + length + " bytes is too large");
        }
        var out = new byte[(int) size];
        ByteArrays.put32be(out, 0, fourCc);
        ByteArrays.put32le(out, 4, length);
        System.arraycopy(payload, offset, out, 8, length);
        return out;
    }

    /**
     * Assembles a RIFF WebP file from serialized chunks.
     *
     * @param chunks the chunks, each already padded
     * @return the file
     * @throws MediaException.LimitExceeded if the file would exceed the RIFF size limit
     */
    public static byte[] riff(List<byte[]> chunks) {
        long total = 12;
        for (var chunk : chunks) {
            total += chunk.length;
        }
        if (total - 8 > WebpParser.MAX_CHUNK_PAYLOAD || total > Integer.MAX_VALUE - 16) {
            throw new MediaException.LimitExceeded("WebP file of " + total + " bytes is too large");
        }
        var out = new ByteWriter((int) total);
        out.u32be(FourCc.RIFF);
        out.u32le(total - 8);
        out.u32be(FourCc.WEBP);
        for (var chunk : chunks) {
            out.bytes(chunk);
        }
        return out.size() == out.buffer().length ? out.buffer() : out.toByteArray();
    }

    /**
     * Serializes a {@code VP8X} chunk.
     *
     * @param flags the feature flags
     * @param width the canvas width, from 1 to 16777216
     * @param height the canvas height, from 1 to 16777216
     * @return the chunk bytes
     */
    public static byte[] vp8x(int flags, int width, int height) {
        var payload = new byte[WebpParser.VP8X_SIZE];
        ByteArrays.put32le(payload, 0, flags);
        ByteArrays.put24le(payload, 4, width - 1);
        ByteArrays.put24le(payload, 7, height - 1);
        return chunk(FourCc.VP8X, payload);
    }
}
