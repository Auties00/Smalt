/* Ports libtiff 4.7.1 (tif_pixarlog.c, PixarLogMakeTables and the horizontalAccumulate functions).
 * Copyright 1996-1997 Sam Leffler, 1996 Pixar; libtiff license. Modified for Smalt. */
package com.github.auties00.smalt.image.tiff.internal;

import com.github.auties00.smalt.image.internal.zlib.ZlibInflater;

import java.util.zip.DataFormatException;

/**
 * Decodes PixarLog data (TIFF compression 32909): zlib-compressed 16-bit words holding the horizontal
 * differences of 11-bit companded codes, linear up to a seam and logarithmic above it, which convert
 * to 8-bit, 16-bit or floating-point samples through the PixarLog tables.
 */
final class PixarLog {
    /** The number of 11-bit codes. */
    private static final int TABLE_SIZE = 2048;

    /** The code of the value 1.0. */
    private static final int ONE = 1250;

    /** The ratio between consecutive codes of the logarithmic part. */
    private static final double RATIO = 1.004;

    /** The mask of a code. */
    private static final int CODE_MASK = 0x7FF;

    /** The linear value of each code. */
    static final float[] TO_LINEAR_FLOAT = new float[TABLE_SIZE + 1];

    /** The 16-bit sample of each code. */
    static final int[] TO_LINEAR_16 = new int[TABLE_SIZE + 1];

    /** The 8-bit sample of each code. */
    static final int[] TO_LINEAR_8 = new int[TABLE_SIZE + 1];

    static {
        var c = StrictMath.log(RATIO);
        var nlin = (int) (1.0 / c);
        c = 1.0 / nlin;
        var b = StrictMath.exp(-c * ONE);
        var linstep = b * c * StrictMath.exp(1.0);
        var j = 0;
        for (var i = 0; i < nlin; i++) {
            TO_LINEAR_FLOAT[j++] = (float) (i * linstep);
        }
        for (var i = nlin; i < TABLE_SIZE; i++) {
            TO_LINEAR_FLOAT[j++] = (float) (b * StrictMath.exp(c * i));
        }
        TO_LINEAR_FLOAT[TABLE_SIZE] = TO_LINEAR_FLOAT[TABLE_SIZE - 1];
        for (var i = 0; i <= TABLE_SIZE; i++) {
            var v = TO_LINEAR_FLOAT[i] * 65535.0 + 0.5;
            TO_LINEAR_16[i] = v > 65535.0 ? 65535 : (int) v;
            v = TO_LINEAR_FLOAT[i] * 255.0 + 0.5;
            TO_LINEAR_8[i] = v > 255.0 ? 255 : (int) v;
        }
    }

    private PixarLog() {
        throw new AssertionError();
    }

    /**
     * Decodes one strip or tile.
     *
     * @param raw the stored bytes
     * @param length the number of stored bytes
     * @param chunk receives the samples, in the file's byte order
     * @param columns the columns of a row
     * @param rows the number of rows of the strip or tile
     * @param stride the samples per pixel of the chunk
     * @param bits the bits per sample: 8, 16, or 32 for floating point
     * @param little whether the file is little-endian
     * @param inflater the zlib decompressor to use
     * @param tokens a scratch buffer for the coded words, grown when needed
     * @return the scratch buffer, possibly a new one
     * @throws DataFormatException if the zlib stream is invalid before any complete row
     */
    static byte[] decode(byte[] raw, int length, byte[] chunk, int columns, int rows, int stride, int bits,
                         boolean little, ZlibInflater inflater, byte[] tokens) throws DataFormatException {
        var rowSamples = columns * stride;
        var needed = rowSamples * rows * 2;
        if (tokens.length < needed) {
            tokens = new byte[needed];
        }
        inflater.reset();
        inflater.setInput(raw, 0, length);
        var produced = 0;
        try {
            while (produced < needed) {
                var count = inflater.inflate(tokens, produced, needed - produced);
                if (count == 0 && (inflater.finished() || inflater.needsInput() || inflater.needsDictionary())) {
                    break;
                }
                produced += count;
            }
        } catch (DataFormatException exception) {
            if (produced < rowSamples * 2) {
                throw exception;
            }
        }
        var complete = produced / (rowSamples * 2);
        var sampleBytes = bits / 8;
        for (var row = 0; row < complete; row++) {
            var in = row * rowSamples * 2;
            var out = row * rowSamples * sampleBytes;
            for (var s = 0; s < stride; s++) {
                var accumulator = 0;
                for (var x = 0; x < columns; x++) {
                    var at = in + 2 * (x * stride + s);
                    var word = little ? (tokens[at] & 0xFF) | (tokens[at + 1] & 0xFF) << 8
                            : (tokens[at] & 0xFF) << 8 | (tokens[at + 1] & 0xFF);
                    accumulator = x == 0 ? word : accumulator + word;
                    var code = accumulator & CODE_MASK;
                    var target = out + (x * stride + s) * sampleBytes;
                    switch (bits) {
                        case 8 -> chunk[target] = (byte) TO_LINEAR_8[code];
                        case 16 -> put(chunk, target, TO_LINEAR_16[code], 2, little);
                        default -> put(chunk, target, Float.floatToRawIntBits(TO_LINEAR_FLOAT[code]), 4, little);
                    }
                }
            }
        }
        return tokens;
    }

    private static void put(byte[] chunk, int at, int value, int size, boolean little) {
        for (var i = 0; i < size; i++) {
            chunk[at + i] = (byte) (value >>> (8 * (little ? i : size - 1 - i)));
        }
    }
}
