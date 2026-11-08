/* Ports OpenEXR 3.3.5 (src/lib/OpenEXRCore/internal_piz.c). Copyright Contributors to the OpenEXR Project;
   BSD-3-Clause. Modified for Smalt. */
package com.github.auties00.smalt.image.exr.internal;

import com.github.auties00.smalt.MediaException;

/**
 * Decompresses PIZ chunks: a bitmap of the 16-bit values present, the Huffman-coded indices of the values in that
 * bitmap after a two-dimensional Haar wavelet transform of each channel, channel after channel.
 */
public final class ExrPiz {
    private static final int RANGE = 1 << 16;

    private static final int BITMAP_SIZE = RANGE >> 3;

    private ExrPiz() {
        throw new AssertionError();
    }

    /**
     * Decompresses a chunk.
     *
     * @param data the bytes
     * @param offset the offset of the compressed chunk
     * @param length the compressed bytes
     * @param block the chunk's pixels
     * @return the uncompressed chunk
     * @throws MediaException.Malformed if the chunk is invalid
     */
    public static byte[] decompress(byte[] data, int offset, int length, ExrBlock block) {
        var total = (int) block.bytes();
        var out = new byte[total];
        if (total == 0) {
            return out;
        }
        if (length < 4) {
            throw new MediaException.Malformed("the OpenEXR PIZ chunk is shorter than its range");
        }
        var end = offset + length;
        var minNonZero = (data[offset] & 0xFF) | (data[offset + 1] & 0xFF) << 8;
        var maxNonZero = (data[offset + 2] & 0xFF) | (data[offset + 3] & 0xFF) << 8;
        var position = offset + 4;
        if (maxNonZero >= BITMAP_SIZE) {
            throw new MediaException.Malformed("invalid OpenEXR PIZ bitmap range");
        }
        var bitmap = new byte[BITMAP_SIZE];
        if (minNonZero <= maxNonZero) {
            var bytes = maxNonZero - minNonZero + 1;
            if (position + bytes > end) {
                throw new MediaException.Malformed("the OpenEXR PIZ bitmap runs past its chunk");
            }
            System.arraycopy(data, position, bitmap, minNonZero, bytes);
            position += bytes;
        }
        var lut = new short[RANGE];
        var maxValue = reverseLut(bitmap, lut);
        if (position + 4 > end) {
            throw new MediaException.Malformed("the OpenEXR PIZ chunk has no Huffman length");
        }
        var huffman = ExrHeader.i32(data, position);
        position += 4;
        if (huffman < 0 || position + (long) huffman > end) {
            throw new MediaException.Malformed("the OpenEXR PIZ Huffman block runs past its chunk");
        }
        var values = new short[total / 2];
        ExrHuffman.decode(data, position, huffman, values, values.length);
        var start = 0;
        for (var channel = 0; channel < block.channels().size(); channel++) {
            var nx = block.channelWidth(channel);
            var ny = block.channelHeight(channel);
            var words = block.channels().get(channel).bytes() / 2;
            for (var word = 0; word < words; word++) {
                wavelet(values, start + word, nx, words, ny, words * nx, maxValue);
            }
            start += nx * ny * words;
        }
        for (var index = 0; index < values.length; index++) {
            values[index] = lut[values[index] & 0xFFFF];
        }
        var written = 0;
        var planeStart = new int[block.channels().size()];
        var plane = 0;
        for (var channel = 0; channel < block.channels().size(); channel++) {
            planeStart[channel] = plane;
            plane += block.channelWidth(channel) * block.channelHeight(channel) * block.channels().get(channel).bytes()
                    / 2;
        }
        var linesDone = new int[block.channels().size()];
        for (var row = 0; row < block.height(); row++) {
            var y = block.y() + row;
            for (var channel = 0; channel < block.channels().size(); channel++) {
                if (!block.samples(channel, y) || block.channelWidth(channel) == 0) {
                    continue;
                }
                var words = block.channelWidth(channel) * block.channels().get(channel).bytes() / 2;
                var from = planeStart[channel] + linesDone[channel]++ * words;
                for (var index = 0; index < words; index++) {
                    var value = values[from + index];
                    out[written++] = (byte) value;
                    out[written++] = (byte) (value >> 8);
                }
            }
        }
        if (written != total) {
            throw new MediaException.Malformed("the OpenEXR PIZ chunk holds " + written + " of " + total + " bytes");
        }
        return out;
    }

    /**
     * Builds the table from bitmap indices to values and returns the largest index.
     */
    private static int reverseLut(byte[] bitmap, short[] lut) {
        bitmap[0] &= (byte) ~1;
        var count = 0;
        for (var value = 0; value < RANGE; value++) {
            if (value == 0 || (bitmap[value >> 3] & (1 << (value & 7))) != 0) {
                lut[count++] = (short) value;
            }
        }
        return count - 1;
    }

    /**
     * Inverts the two-dimensional wavelet transform of one channel word in place.
     *
     * @param values the samples
     * @param start the index of the first sample
     * @param nx the width
     * @param ox the distance between horizontally adjacent samples
     * @param ny the height
     * @param oy the distance between vertically adjacent samples
     * @param max the largest value, below 2^14 for the 14-bit transform
     */
    private static void wavelet(short[] values, int start, int nx, int ox, int ny, int oy, int max) {
        var w14 = max < (1 << 14);
        var n = Math.min(nx, ny);
        var p = 1;
        while (p <= n) {
            p <<= 1;
        }
        p >>= 1;
        var p2 = p;
        p >>= 1;
        var pair = new int[2];
        while (p >= 1) {
            var py = start;
            var ey = start + oy * (ny - p2);
            var oy1 = oy * p;
            var oy2 = oy * p2;
            var ox1 = ox * p;
            var ox2 = ox * p2;
            for (; py <= ey; py += oy2) {
                var px = py;
                var ex = py + ox * (nx - p2);
                for (; px <= ex; px += ox2) {
                    var p01 = px + ox1;
                    var p10 = px + oy1;
                    var p11 = p10 + ox1;
                    decode(values[px], values[p10], w14, pair);
                    var i00 = pair[0];
                    var i10 = pair[1];
                    decode(values[p01], values[p11], w14, pair);
                    var i01 = pair[0];
                    var i11 = pair[1];
                    decode((short) i00, (short) i01, w14, pair);
                    values[px] = (short) pair[0];
                    values[p01] = (short) pair[1];
                    decode((short) i10, (short) i11, w14, pair);
                    values[p10] = (short) pair[0];
                    values[p11] = (short) pair[1];
                }
                if ((nx & p) != 0) {
                    var p10 = px + oy1;
                    decode(values[px], values[p10], w14, pair);
                    values[px] = (short) pair[0];
                    values[p10] = (short) pair[1];
                }
            }
            if ((ny & p) != 0) {
                var px = py;
                var ex = py + ox * (nx - p2);
                for (; px <= ex; px += ox2) {
                    var p01 = px + ox1;
                    decode(values[px], values[p01], w14, pair);
                    values[px] = (short) pair[0];
                    values[p01] = (short) pair[1];
                }
            }
            p2 = p;
            p >>= 1;
        }
    }

    /**
     * Inverts one step of the transform: the 14-bit transform without modulo arithmetic, or the 16-bit one with it.
     */
    private static void decode(short low, short high, boolean w14, int[] pair) {
        if (w14) {
            int h = high;
            var a = low + (h & 1) + (h >> 1);
            pair[0] = (short) a;
            pair[1] = (short) (a - h);
        } else {
            var m = low & 0xFFFF;
            var d = high & 0xFFFF;
            var b = (m - (d >> 1)) & 0xFFFF;
            var a = (d + b - (1 << 15)) & 0xFFFF;
            pair[0] = (short) a;
            pair[1] = (short) b;
        }
    }
}
