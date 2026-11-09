/* Ports OpenEXR 3.5.2 (src/lib/OpenEXRCore/internal_zstd.c, internal_zstd_delta.c, internal_zstd_shuffle.c).
   Copyright Contributors to the OpenEXR Project; BSD-3-Clause. Modified for Smalt. */
package com.github.auties00.smalt.image.exr.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.internal.zstd.ZstdDecoder;

import java.util.zip.DataFormatException;

/**
 * Decompresses the chunks of the OpenEXR ZSTD compression (method 13).
 *
 * <p>A compressed chunk starts with a 24-byte little-endian header: the magic {@code "zstd-exr"} read as a 64-bit
 * integer, the wire version (1 or 2), the flags (0, or for version 2 bit 0 when delta coding was applied) and the
 * size of the Zstandard frame that follows. The frame decompresses to one or two segments, each a 64-bit length
 * followed by that many bytes split into byte planes: the 16-bit samples of the chunk, then its 32-bit ones. Within
 * a segment the samples run line after line, each line channel after channel, as in the uncompressed chunk; with
 * delta coding each channel's samples on a line are differences from the previous one. A chunk without the magic is
 * stored uncompressed.
 *
 * {@snippet :
 * offset 0   "zstd-exr"  0x7278652D6474737A  magic, u64 LE
 * offset 8   1 | 2                         wire version, u32 LE
 * offset 12  0 | 1                         flags, u32 LE
 * offset 16  N                             Zstandard frame size, u64 LE
 * offset 24  N bytes of Zstandard frame
 * }
 */
final class ExrZstd {
    /**
     * The magic of a compressed chunk, the bytes {@code "zstd-exr"} read as a little-endian integer.
     */
    private static final long MAGIC = 8248453963162350458L;

    /**
     * The size of the chunk header.
     */
    private static final int HEADER = 24;

    /**
     * The decoder of each thread, reused for its chunks.
     */
    private static final ThreadLocal<ZstdDecoder> DECODERS = ThreadLocal.withInitial(ZstdDecoder::new);

    private ExrZstd() {
        throw new AssertionError();
    }

    /**
     * Decompresses a chunk of flat (scanline or tiled) samples.
     *
     * @param data the bytes
     * @param offset the offset of the compressed chunk
     * @param length the compressed bytes
     * @param block the chunk's pixels
     * @param size the uncompressed bytes
     * @return the uncompressed chunk
     * @throws MediaException.Malformed if the chunk is invalid
     */
    static byte[] flat(byte[] data, int offset, int length, ExrBlock block, int size) {
        var channels = block.channels().size();
        var height = block.height();
        var counts = new int[height * channels];
        var widths = new int[channels];
        for (var row = 0; row < height; row++) {
            for (var channel = 0; channel < channels; channel++) {
                if (block.channelHeight(channel) > 0 && block.samples(channel, block.y() + row)) {
                    counts[row * channels + channel] = block.channelWidth(channel);
                }
            }
        }
        for (var channel = 0; channel < channels; channel++) {
            widths[channel] = block.channels().get(channel).bytes();
        }
        return decompress(data, offset, length, size, counts, widths, height);
    }

    /**
     * Decompresses the cumulative sample count table of a deep chunk.
     *
     * @param data the bytes
     * @param offset the offset of the compressed table
     * @param length the compressed bytes
     * @param width the columns of the chunk
     * @param height the rows of the chunk
     * @return the table, one 32-bit little-endian count per pixel
     * @throws MediaException.Malformed if the table is invalid
     */
    static byte[] sampleCounts(byte[] data, int offset, int length, int width, int height) {
        var counts = new int[height];
        java.util.Arrays.fill(counts, width);
        return decompress(data, offset, length, 4 * width * height, counts, new int[]{4}, height);
    }

    /**
     * Decompresses the samples of a deep chunk.
     *
     * @param data the bytes
     * @param offset the offset of the compressed samples
     * @param length the compressed bytes
     * @param size the uncompressed bytes
     * @param rowSamples the number of samples of each row of the chunk
     * @param widths the bytes of a sample of each channel, in channel order
     * @return the samples, row after row, each row channel after channel
     * @throws MediaException.Malformed if the samples are invalid
     */
    static byte[] deep(byte[] data, int offset, int length, int size, int[] rowSamples, int[] widths) {
        var height = rowSamples.length;
        var channels = widths.length;
        var counts = new int[height * channels];
        for (var row = 0; row < height; row++) {
            java.util.Arrays.fill(counts, row * channels, (row + 1) * channels, rowSamples[row]);
        }
        return decompress(data, offset, length, size, counts, widths, height);
    }

    /**
     * Undoes the framing, the byte planes, the delta coding and the sorting of a chunk.
     *
     * @param counts the samples of each channel on each line, line-major
     * @param widths the bytes of a sample of each channel
     * @param height the lines of the chunk
     */
    private static byte[] decompress(byte[] data, int offset, int length, int size, int[] counts, int[] widths,
                                     int height) {
        if (length == 0) {
            if (size != 0) {
                throw new MediaException.Malformed("an empty OpenEXR ZSTD chunk of " + size + " bytes");
            }
            return new byte[0];
        }
        if (length < HEADER || ExrHeader.i64(data, offset) != MAGIC) {
            if (length != size) {
                throw new MediaException.Malformed("an OpenEXR ZSTD chunk of " + length + " bytes stored for "
                        + size);
            }
            return java.util.Arrays.copyOfRange(data, offset, offset + size);
        }
        var version = ExrHeader.i32(data, offset + 8);
        var flags = ExrHeader.i32(data, offset + 12);
        if (!(version == 1 && flags == 0 || version == 2 && (flags & ~1) == 0)) {
            throw new MediaException.Malformed("an OpenEXR ZSTD chunk of version " + version + " and flags "
                    + flags);
        }
        var frame = ExrHeader.i64(data, offset + 16);
        if (frame != length - HEADER) {
            throw new MediaException.Malformed("an OpenEXR ZSTD chunk declares a frame of " + frame + " bytes in "
                    + (length - HEADER));
        }
        var channels = widths.length;
        var lookup = new long[counts.length];
        var split = 0L;
        for (var row = 0; row < height; row++) {
            for (var channel = 0; channel < channels; channel++) {
                if (widths[channel] == 2) {
                    lookup[row * channels + channel] = split;
                    split += 2L * counts[row * channels + channel];
                }
            }
        }
        var total = split;
        for (var row = 0; row < height; row++) {
            for (var channel = 0; channel < channels; channel++) {
                if (widths[channel] == 4) {
                    lookup[row * channels + channel] = total;
                    total += 4L * counts[row * channels + channel];
                }
            }
        }
        if (total != size) {
            throw new MediaException.Malformed("an OpenEXR ZSTD chunk lays out " + total + " of " + size
                    + " bytes");
        }
        var inner = new byte[size + 17];
        int produced;
        try {
            produced = DECODERS.get().decompress(data, offset + HEADER, (int) frame, inner, 0, inner.length);
        } catch (DataFormatException exception) {
            throw new MediaException.Malformed("invalid OpenEXR ZSTD frame: " + exception.getMessage());
        }
        var sorted = new byte[size];
        var position = 0;
        var target = 0;
        var rest = size - split;
        for (var segment = 0; segment < 2; segment++) {
            long expected;
            int element;
            if (split > 0 && rest > 0) {
                expected = segment == 0 ? split : rest;
                element = segment == 0 ? 2 : 4;
            } else if (segment == 0) {
                expected = size;
                element = split == 0 ? 4 : 2;
            } else {
                break;
            }
            if (produced - position < 8 || ExrHeader.i64(inner, position) != expected
                    || produced - position - 8 < expected) {
                throw new MediaException.Malformed("an OpenEXR ZSTD segment of another length");
            }
            position += 8;
            unshuffle(inner, position, (int) expected, element, sorted, target);
            position += (int) expected;
            target += (int) expected;
        }
        if (position != produced) {
            throw new MediaException.Malformed("an OpenEXR ZSTD frame holds " + (produced - position)
                    + " bytes past its segments");
        }
        if (version == 2 && flags == 1) {
            undelta(sorted, lookup, counts, widths, height);
        }
        var out = new byte[size];
        var read = 0;
        for (var row = 0; row < height; row++) {
            for (var channel = 0; channel < channels; channel++) {
                var bytes = counts[row * channels + channel] * widths[channel];
                System.arraycopy(sorted, (int) lookup[row * channels + channel], out, read, bytes);
                read += bytes;
            }
        }
        return out;
    }

    /**
     * Joins the byte planes of a segment back into samples.
     */
    private static void unshuffle(byte[] planes, int offset, int length, int element, byte[] out, int target) {
        var samples = length / element;
        for (var plane = 0; plane < element; plane++) {
            var from = offset + plane * samples;
            for (var index = 0; index < samples; index++) {
                out[target + index * element + plane] = planes[from + index];
            }
        }
    }

    /**
     * Undoes the delta coding of each channel's samples on each line, modulo 2^16 or 2^32.
     */
    private static void undelta(byte[] sorted, long[] lookup, int[] counts, int[] widths, int height) {
        var channels = widths.length;
        for (var row = 0; row < height; row++) {
            for (var channel = 0; channel < channels; channel++) {
                var count = counts[row * channels + channel];
                var at = (int) lookup[row * channels + channel];
                if (widths[channel] == 2) {
                    var previous = 0;
                    for (var index = 0; index < count; index++, at += 2) {
                        previous += (sorted[at] & 0xFF) | (sorted[at + 1] & 0xFF) << 8;
                        sorted[at] = (byte) previous;
                        sorted[at + 1] = (byte) (previous >>> 8);
                    }
                } else {
                    var previous = 0;
                    for (var index = 0; index < count; index++, at += 4) {
                        previous += ExrHeader.i32(sorted, at);
                        sorted[at] = (byte) previous;
                        sorted[at + 1] = (byte) (previous >>> 8);
                        sorted[at + 2] = (byte) (previous >>> 16);
                        sorted[at + 3] = (byte) (previous >>> 24);
                    }
                }
            }
        }
    }
}
