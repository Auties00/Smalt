package com.github.auties00.smalt.image.internal.zlib;

import java.util.Arrays;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Compresses and decompresses small zlib streams in one call, for metadata payloads such as an
 * ICC profile and for data an encoder keeps in memory.
 *
 * <p>Bulk image data goes through {@link ZlibInflater} and {@link ZlibDeflater}; these helpers use
 * the platform's zlib, whose output for a given level is what most PNG writers produce for their
 * ancillary chunks. This class is thread-safe.
 */
public final class Zlib {
    /**
     * Prevents instantiation.
     */
    private Zlib() {
        throw new AssertionError();
    }

    /**
     * Compresses bytes into a zlib stream.
     *
     * @param data the bytes to compress
     * @param level the compression level, from 0 to 9, as {@link Deflater#setLevel(int)} accepts it
     * @return the zlib stream
     */
    public static byte[] deflate(byte[] data, int level) {
        var deflater = new Deflater(level);
        try {
            deflater.setInput(data);
            deflater.finish();
            var output = new byte[Math.max(64, data.length / 4 + 64)];
            var size = 0;
            while (!deflater.finished()) {
                if (size == output.length) {
                    output = Arrays.copyOf(output, (int) Math.min(Integer.MAX_VALUE - 16, output.length * 2L));
                }
                size += deflater.deflate(output, size, output.length - size);
            }
            return Arrays.copyOf(output, size);
        } finally {
            deflater.end();
        }
    }

    /**
     * Decompresses a zlib stream whose output is expected to be small, giving up past a bound.
     *
     * @param data the array holding the stream
     * @param offset the index of the first byte of the stream
     * @param length the number of bytes of the stream
     * @param bound the largest output accepted, in bytes
     * @return the output, or {@code null} when the stream is corrupt, truncated, needs a preset
     * dictionary or decompresses to more than {@code bound} bytes
     */
    public static byte[] inflate(byte[] data, int offset, int length, int bound) {
        return inflate(data, offset, length, (int) Math.min(bound, Math.max(1024, length * 4L)), bound);
    }

    /**
     * Decompresses a zlib stream whose output size is known.
     *
     * @param data the stream
     * @param size the size of the output in bytes
     * @return the output, or {@code null} when the stream is corrupt or does not decompress to
     * exactly {@code size} bytes
     */
    public static byte[] inflate(byte[] data, int size) {
        var output = inflate(data, 0, data.length, size, size);
        return output == null || output.length != size ? null : output;
    }

    /**
     * Decompresses a zlib stream into an array that grows from an initial capacity up to a bound.
     *
     * @param data the array holding the stream
     * @param offset the index of the first byte of the stream
     * @param length the number of bytes of the stream
     * @param capacity the initial capacity of the output, at most {@code bound}
     * @param bound the largest output accepted, in bytes
     * @return the output, or {@code null} when the stream is corrupt, truncated, needs a preset
     * dictionary or exceeds the bound
     */
    private static byte[] inflate(byte[] data, int offset, int length, int capacity, int bound) {
        var inflater = new Inflater();
        try {
            inflater.setInput(data, offset, length);
            var output = new byte[capacity];
            var size = 0;
            while (!inflater.finished()) {
                if (size == output.length) {
                    if (size >= bound) {
                        // a full output is accepted only when the stream ends without another byte
                        return inflater.inflate(new byte[1]) == 0 && inflater.finished() ? output : null;
                    }
                    output = Arrays.copyOf(output, (int) Math.min(bound, output.length * 2L));
                }
                var count = inflater.inflate(output, size, output.length - size);
                if (count == 0 && !inflater.finished() && (inflater.needsInput() || inflater.needsDictionary())) {
                    return null;
                }
                size += count;
            }
            return size == output.length ? output : Arrays.copyOf(output, size);
        } catch (DataFormatException exception) {
            return null;
        } finally {
            inflater.end();
        }
    }
}
