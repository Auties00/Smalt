/* Ports OpenEXR 3.3.5 (internal_rle.c, internal_zip.c, internal_pxr24.c, internal_b44.c,
   b44ExpLogTable.cpp). Copyright Contributors to the OpenEXR Project;
   BSD-3-Clause. Modified for Smalt. */
package com.github.auties00.smalt.image.exr.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;

import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Decompresses the OpenEXR chunks of every method into the uncompressed layout of {@link ExrBlock}: RLE, ZIPS, ZIP,
 * PXR24 and B44 here, PIZ, DWA, the HTJ2K methods and ZSTD through their own classes.
 */
public final class ExrCodecs {
    private ExrCodecs() {
        throw new AssertionError();
    }

    /**
     * Decompresses a chunk.
     *
     * <p>A chunk whose compressed size equals its uncompressed size is stored uncompressed, whatever the method.
     *
     * @param method the compression method
     * @param data the bytes
     * @param offset the offset of the compressed chunk
     * @param length the compressed bytes
     * @param block the chunk's pixels
     * @param limits the limits bounding the decode of a JPEG 2000 codestream
     * @return the uncompressed chunk
     * @throws MediaException.Malformed if the chunk is invalid
     * @throws MediaException.Unsupported if the method is unknown
     */
    public static byte[] decompress(int method, byte[] data, int offset, int length, ExrBlock block,
                                    MediaLimits limits) {
        var expected = block.bytes();
        if (expected > Integer.MAX_VALUE - 16) {
            throw new MediaException.LimitExceeded("an OpenEXR chunk of " + expected + " bytes");
        }
        var size = (int) expected;
        if (length == size || method == 0) {
            if (length < size) {
                throw new MediaException.Malformed("the OpenEXR chunk holds " + length + " of " + size + " bytes");
            }
            return java.util.Arrays.copyOfRange(data, offset, offset + size);
        }
        return switch (method) {
            case 1 -> unpredict(rle(data, offset, length, size));
            case 2, 3 -> unpredict(inflate(data, offset, length, size, true));
            case 4 -> ExrPiz.decompress(data, offset, length, block);
            case 5 -> pxr24(data, offset, length, block, size);
            case 6, 7 -> b44(data, offset, length, block, size);
            case 8, 9 -> ExrDwa.decompress(data, offset, length, block, size);
            case 10, 11, 12 -> ExrHt.decompress(method, data, offset, length, block, size, limits);
            case 13 -> ExrZstd.flat(data, offset, length, block, size);
            default -> throw new MediaException.Unsupported("exr", "compression method " + method);
        };
    }

    /**
     * Undoes the run-length coding: a negative count byte copies that many literal bytes, another repeats the next
     * byte count plus one times.
     */
    static byte[] rle(byte[] data, int offset, int length, int size) {
        var out = new byte[size];
        var position = offset;
        var end = offset + length;
        var written = 0;
        while (position < end) {
            int count = data[position++];
            if (count < 0) {
                count = -count;
                if (position + count > end || written + count > size) {
                    throw new MediaException.Malformed("an OpenEXR RLE literal run passes its chunk");
                }
                System.arraycopy(data, position, out, written, count);
                position += count;
                written += count;
            } else {
                if (position >= end || written + count + 1 > size) {
                    throw new MediaException.Malformed("an OpenEXR RLE repeat run passes its chunk");
                }
                java.util.Arrays.fill(out, written, written + count + 1, data[position++]);
                written += count + 1;
            }
        }
        if (written != size) {
            throw new MediaException.Malformed("the OpenEXR RLE chunk holds " + written + " of " + size + " bytes");
        }
        return out;
    }

    /**
     * Inflates a zlib stream.
     *
     * @param exact whether the stream must hold exactly {@code size} bytes
     */
    static byte[] inflate(byte[] data, int offset, int length, int size, boolean exact) {
        var out = new byte[size];
        var inflater = new Inflater();
        try {
            inflater.setInput(data, offset, length);
            var written = 0;
            while (written < size && !inflater.finished()) {
                var count = inflater.inflate(out, written, size - written);
                if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    break;
                }
                written += count;
            }
            if (exact && written != size) {
                throw new MediaException.Malformed("the OpenEXR zlib stream holds " + written + " of " + size
                        + " bytes");
            }
            return out;
        } catch (DataFormatException exception) {
            throw new MediaException.Malformed("invalid OpenEXR zlib stream: " + exception.getMessage());
        } finally {
            inflater.end();
        }
    }

    /**
     * Undoes the byte predictor and the split of the bytes into their even and odd halves.
     */
    static byte[] unpredict(byte[] packed) {
        var size = packed.length;
        for (var index = 1; index < size; index++) {
            packed[index] = (byte) (packed[index - 1] + packed[index] - 128);
        }
        var out = new byte[size];
        var first = 0;
        var second = (size + 1) / 2;
        var written = 0;
        while (written < size) {
            out[written++] = packed[first++];
            if (written < size) {
                out[written++] = packed[second++];
            }
        }
        return out;
    }

    /**
     * Decompresses a PXR24 chunk: deflated planes of the bytes of each line's differences, floats cut to 24 bits.
     */
    static byte[] pxr24(byte[] data, int offset, int length, ExrBlock block, int size) {
        var planes = inflate(data, offset, length, size, false);
        var out = new byte[size];
        var input = 0;
        var written = 0;
        var channels = block.channels();
        for (var row = 0; row < block.height(); row++) {
            var y = block.y() + row;
            for (var channel = 0; channel < channels.size(); channel++) {
                if (!block.samples(channel, y) || block.channelHeight(channel) == 0) {
                    continue;
                }
                var width = block.channelWidth(channel);
                var type = channels.get(channel).pixelType();
                var planeCount = type == 0 ? 4 : type == 1 ? 2 : 3;
                if (input + (long) width * planeCount > planes.length) {
                    throw new MediaException.Malformed("the OpenEXR PXR24 chunk ends early");
                }
                var pixel = 0;
                for (var x = 0; x < width; x++) {
                    int difference;
                    if (type == 1) {
                        difference = (planes[input + x] & 0xFF) << 8 | (planes[input + width + x] & 0xFF);
                    } else if (type == 0) {
                        difference = (planes[input + x] & 0xFF) << 24 | (planes[input + width + x] & 0xFF) << 16
                                | (planes[input + 2 * width + x] & 0xFF) << 8 | (planes[input + 3 * width + x] & 0xFF);
                    } else {
                        difference = (planes[input + x] & 0xFF) << 24 | (planes[input + width + x] & 0xFF) << 16
                                | (planes[input + 2 * width + x] & 0xFF) << 8;
                    }
                    pixel += difference;
                    if (type == 1) {
                        out[written++] = (byte) pixel;
                        out[written++] = (byte) (pixel >>> 8);
                    } else {
                        out[written++] = (byte) pixel;
                        out[written++] = (byte) (pixel >>> 8);
                        out[written++] = (byte) (pixel >>> 16);
                        out[written++] = (byte) (pixel >>> 24);
                    }
                }
                input += width * planeCount;
            }
        }
        return out;
    }

    /**
     * Decompresses a B44 or B44A chunk: each 4 by 4 block of a half channel coded in 14 bytes, or in 3 when flat,
     * the other channels stored.
     */
    static byte[] b44(byte[] data, int offset, int length, ExrBlock block, int size) {
        var channels = block.channels();
        var planes = new short[channels.size()][];
        var raw = new byte[channels.size()][];
        var position = offset;
        var end = offset + length;
        var values = new int[16];
        for (var channel = 0; channel < channels.size(); channel++) {
            var nx = block.channelWidth(channel);
            var ny = block.channelHeight(channel);
            var bytes = nx * ny * channels.get(channel).bytes();
            if (bytes == 0) {
                continue;
            }
            if (channels.get(channel).pixelType() != 1) {
                if (position + bytes > end) {
                    throw new MediaException.Malformed("the OpenEXR B44 chunk ends early");
                }
                raw[channel] = java.util.Arrays.copyOfRange(data, position, position + bytes);
                position += bytes;
                continue;
            }
            var plane = new short[nx * ny];
            var linear = channels.get(channel).linear();
            for (var y = 0; y < ny; y += 4) {
                for (var x = 0; x < nx; x += 4) {
                    if (position + 3 > end) {
                        throw new MediaException.Malformed("the OpenEXR B44 chunk ends early");
                    }
                    if ((data[position + 2] & 0xFF) >= (13 << 2)) {
                        unpack3(data, position, values);
                        position += 3;
                    } else {
                        if (position + 14 > end) {
                            throw new MediaException.Malformed("the OpenEXR B44 chunk ends early");
                        }
                        unpack14(data, position, values);
                        position += 14;
                    }
                    if (linear) {
                        for (var index = 0; index < 16; index++) {
                            values[index] = Log.TABLE[values[index]] & 0xFFFF;
                        }
                    }
                    for (var dy = 0; dy < 4 && y + dy < ny; dy++) {
                        for (var dx = 0; dx < 4 && x + dx < nx; dx++) {
                            plane[(y + dy) * nx + x + dx] = (short) values[dy * 4 + dx];
                        }
                    }
                }
            }
            planes[channel] = plane;
        }
        var out = new byte[size];
        var written = 0;
        var lines = new int[channels.size()];
        for (var row = 0; row < block.height(); row++) {
            var y = block.y() + row;
            for (var channel = 0; channel < channels.size(); channel++) {
                var nx = block.channelWidth(channel);
                if (!block.samples(channel, y) || nx == 0 || block.channelHeight(channel) == 0) {
                    continue;
                }
                var line = lines[channel]++;
                if (planes[channel] != null) {
                    for (var x = 0; x < nx; x++) {
                        var value = planes[channel][line * nx + x];
                        out[written++] = (byte) value;
                        out[written++] = (byte) (value >> 8);
                    }
                } else {
                    var bytes = nx * channels.get(channel).bytes();
                    System.arraycopy(raw[channel], line * bytes, out, written, bytes);
                    written += bytes;
                }
            }
        }
        return out;
    }

    /**
     * Unpacks a 14-byte block: a 16-bit base, a shift, and 15 six-bit differences.
     */
    private static void unpack14(byte[] b, int at, int[] s) {
        s[0] = (b[at] & 0xFF) << 8 | (b[at + 1] & 0xFF);
        var shift = (b[at + 2] & 0xFF) >> 2;
        var bias = 0x20 << shift;
        s[4] = s[0] + ((((b[at + 2] & 0xFF) << 4 | (b[at + 3] & 0xFF) >> 4) & 0x3F) << shift) - bias;
        s[8] = s[4] + ((((b[at + 3] & 0xFF) << 2 | (b[at + 4] & 0xFF) >> 6) & 0x3F) << shift) - bias;
        s[12] = s[8] + (((b[at + 4] & 0xFF) & 0x3F) << shift) - bias;
        s[1] = s[0] + (((b[at + 5] & 0xFF) >> 2) << shift) - bias;
        s[5] = s[4] + ((((b[at + 5] & 0xFF) << 4 | (b[at + 6] & 0xFF) >> 4) & 0x3F) << shift) - bias;
        s[9] = s[8] + ((((b[at + 6] & 0xFF) << 2 | (b[at + 7] & 0xFF) >> 6) & 0x3F) << shift) - bias;
        s[13] = s[12] + (((b[at + 7] & 0xFF) & 0x3F) << shift) - bias;
        s[2] = s[1] + (((b[at + 8] & 0xFF) >> 2) << shift) - bias;
        s[6] = s[5] + ((((b[at + 8] & 0xFF) << 4 | (b[at + 9] & 0xFF) >> 4) & 0x3F) << shift) - bias;
        s[10] = s[9] + ((((b[at + 9] & 0xFF) << 2 | (b[at + 10] & 0xFF) >> 6) & 0x3F) << shift) - bias;
        s[14] = s[13] + (((b[at + 10] & 0xFF) & 0x3F) << shift) - bias;
        s[3] = s[2] + (((b[at + 11] & 0xFF) >> 2) << shift) - bias;
        s[7] = s[6] + ((((b[at + 11] & 0xFF) << 4 | (b[at + 12] & 0xFF) >> 4) & 0x3F) << shift) - bias;
        s[11] = s[10] + ((((b[at + 12] & 0xFF) << 2 | (b[at + 13] & 0xFF) >> 6) & 0x3F) << shift) - bias;
        s[15] = s[14] + (((b[at + 13] & 0xFF) & 0x3F) << shift) - bias;
        for (var index = 0; index < 16; index++) {
            s[index] &= 0xFFFF;
        }
        for (var index = 0; index < 16; index++) {
            s[index] = (s[index] & 0x8000) != 0 ? s[index] & 0x7FFF : ~s[index] & 0xFFFF;
        }
    }

    /**
     * Unpacks a 3-byte flat block.
     */
    private static void unpack3(byte[] b, int at, int[] s) {
        var value = (b[at] & 0xFF) << 8 | (b[at + 1] & 0xFF);
        value = (value & 0x8000) != 0 ? value & 0x7FFF : ~value & 0xFFFF;
        java.util.Arrays.fill(s, value);
    }

    /**
     * {@return the B44 logarithm table}
     */
    static short[] logTable() {
        return Log.TABLE;
    }

    /**
     * Holds the B44 table mapping a half to eight times its natural logarithm, which B44 codes linear channels in.
     */
    private static final class Log {
        static final short[] TABLE = table();

        private static short[] table() {
            var table = new short[1 << 16];
            for (var bits = 0; bits < table.length; bits++) {
                var value = Float.float16ToFloat((short) bits);
                float result;
                if (!Float.isFinite(value) || value < 0) {
                    result = 0;
                } else {
                    result = 8 * (float) StrictMath.log(value);
                }
                table[bits] = Float.floatToFloat16(result);
            }
            return table;
        }
    }
}
