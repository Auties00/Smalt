/* Ports Esri LERC 4.2.0 (fpl_Lerc2Ext.cpp, fpl_EsriHuffman.cpp, fpl_Compression.cpp, fpl_Predictor.cpp,
 * fpl_UnitTypes.cpp). Copyright 2015-2026 Esri; Apache License 2.0. Modified for Smalt. */
package com.github.auties00.smalt.image.internal.lerc;

import java.util.zip.DataFormatException;

/**
 * Decodes the lossless floating-point coding of Lerc2 version 6: each byte of the values in its own
 * Huffman, run, PackBits or raw stream, with byte deltas, and a row or row-and-column predictor on the
 * values.
 */
final class LosslessFloat {
    private static final int MAX_DELTA = 5;
    private static final int HUFFMAN_NORMAL = 0;
    private static final int HUFFMAN_RLE = 1;
    private static final int HUFFMAN_NO_ENCODING = 2;
    private static final int HUFFMAN_PACKBITS = 3;
    private static final int FLT_MANT_MASK = 0x007FFFFF;
    private static final int FLT_9BIT_MASK = 0xFF800000;
    private static final long DBL_MANT_MASK = 0x000FFFFFFFFFFFFFL;

    private LosslessFloat() {
        throw new AssertionError();
    }

    /**
     * Decodes a band into values.
     *
     * @param src the input
     * @param pos the first byte of the coded band
     * @param end the end of the input
     * @param values the output, every pixel written
     * @param base the first value of the band
     * @param isDouble whether the values are 64-bit
     * @param width the values across a row of the predictor: the columns, or the depth when the band has
     * several values per pixel
     * @param height the rows of the predictor: the rows, or the pixels when the band has several values
     * per pixel
     * @return the position after the band
     * @throws DataFormatException if the data is invalid
     */
    static int decode(byte[] src, int pos, int end, double[] values, int base, boolean isDouble, int width,
                      int height) throws DataFormatException {
        if (pos >= end) {
            throw new DataFormatException("truncated Lerc2 lossless floating-point data");
        }
        var predictor = src[pos++] & 0xFF;
        if (predictor > 2) {
            throw new DataFormatException("invalid Lerc2 floating-point predictor");
        }
        var bytes = isDouble ? 8 : 4;
        var count = width * height;
        var planes = new byte[bytes][];
        var indexes = new int[bytes];
        for (var b = 0; b < bytes; b++) {
            if (end - pos < 6) {
                throw new DataFormatException("truncated Lerc2 lossless floating-point data");
            }
            var index = src[pos] & 0xFF;
            var level = src[pos + 1] & 0xFF;
            var size = LercDecoder.intLE(src, pos + 2) & 0xFFFFFFFFL;
            pos += 6;
            if (index >= bytes || level > MAX_DELTA || size > end - pos) {
                throw new DataFormatException("invalid Lerc2 floating-point byte plane");
            }
            var plane = plane(src, pos, (int) size, count);
            pos += (int) size;
            for (var l = level; l > 0; l--) {
                for (var i = l; i < count; i++) {
                    plane[i] += plane[i - 1];
                }
            }
            planes[b] = plane;
            indexes[b] = index;
        }
        if (isDouble) {
            var data = new long[count];
            for (var b = 0; b < bytes; b++) {
                var plane = planes[b];
                var shift = 8 * indexes[b];
                var clear = ~(0xFFL << shift);
                for (var i = 0; i < count; i++) {
                    data[i] = (data[i] & clear) | (plane[i] & 0xFFL) << shift;
                }
            }
            if (predictor == 2) {
                for (var column = 0; column < width; column++) {
                    for (var row = 1; row < height; row++) {
                        var i = row * width + column;
                        data[i] = add(data[i], data[i - width]);
                    }
                }
            }
            if (predictor > 0) {
                for (var row = 0; row < height; row++) {
                    for (var i = 1; i < width; i++) {
                        var at = row * width + i;
                        data[at] = add(data[at], data[at - 1]);
                    }
                }
            }
            for (var i = 0; i < count; i++) {
                values[base + i] = Double.longBitsToDouble(data[i]);
            }
        } else {
            var data = new int[count];
            for (var b = 0; b < bytes; b++) {
                var plane = planes[b];
                var shift = 8 * indexes[b];
                var clear = ~(0xFF << shift);
                for (var i = 0; i < count; i++) {
                    data[i] = (data[i] & clear) | (plane[i] & 0xFF) << shift;
                }
            }
            if (predictor == 2) {
                for (var column = 0; column < width; column++) {
                    for (var row = 1; row < height; row++) {
                        var i = row * width + column;
                        data[i] = add(data[i], data[i - width]);
                    }
                }
            }
            if (predictor > 0) {
                for (var row = 0; row < height; row++) {
                    for (var i = 1; i < width; i++) {
                        var at = row * width + i;
                        data[at] = add(data[at], data[at - 1]);
                    }
                }
            }
            for (var i = 0; i < count; i++) {
                var a = data[i];
                var bits = (a & FLT_MANT_MASK) | ((a >>> 24) & 0xFF) << 23 | ((a >>> 23) & 1) << 31;
                values[base + i] = Float.intBitsToFloat(bits);
            }
        }
        return pos;
    }

    /**
     * Adds two values of the transformed float layout (sign and exponent in the top nine bits) field
     * by field, each field wrapping.
     */
    private static int add(int a, int b) {
        var mantissa = (a + b) & FLT_MANT_MASK;
        var high = (((a & FLT_9BIT_MASK) >>> 23) + ((b & FLT_9BIT_MASK) >>> 23)) & 0x1FF;
        return mantissa | high << 23;
    }

    /**
     * Adds two doubles bit patterns field by field (52-bit mantissa, 12-bit sign and exponent), each field
     * wrapping.
     */
    private static long add(long a, long b) {
        var mantissa = ((a & DBL_MANT_MASK) + (b & DBL_MANT_MASK)) & DBL_MANT_MASK;
        var high = ((a >>> 52) + (b >>> 52)) & 0xFFF;
        return mantissa | high << 52;
    }

    /**
     * Decodes one byte plane.
     *
     * @param src the input
     * @param pos the first byte of the plane
     * @param size the coded size
     * @param count the number of bytes of the plane
     * @return the plane
     * @throws DataFormatException if the plane is invalid
     */
    private static byte[] plane(byte[] src, int pos, int size, int count) throws DataFormatException {
        if (size < 1) {
            throw new DataFormatException("empty Lerc2 floating-point byte plane");
        }
        var plane = new byte[count];
        switch (src[pos] & 0xFF) {
            case HUFFMAN_RLE -> {
                if (size < 6) {
                    throw new DataFormatException("truncated Lerc2 floating-point byte plane");
                }
                java.util.Arrays.fill(plane, src[pos + 1]);
            }
            case HUFFMAN_NO_ENCODING -> {
                if (size - 1 < count) {
                    throw new DataFormatException("truncated Lerc2 floating-point byte plane");
                }
                System.arraycopy(src, pos + 1, plane, 0, count);
            }
            case HUFFMAN_PACKBITS -> {
                var current = 0;
                var i = pos + 1;
                var limit = pos + size;
                while (i < limit) {
                    var header = src[i++] & 0xFF;
                    if (header <= 127) {
                        var run = header + 1;
                        if (current + header >= count || limit - i < run) {
                            throw new DataFormatException("invalid Lerc2 floating-point PackBits data");
                        }
                        System.arraycopy(src, i, plane, current, run);
                        current += run;
                        i += run;
                    } else {
                        var run = header - 127 + 1;
                        if (current + header - 127 >= count || i >= limit) {
                            throw new DataFormatException("invalid Lerc2 floating-point PackBits data");
                        }
                        java.util.Arrays.fill(plane, current, current + run, src[i++]);
                        current += run;
                    }
                }
                if (current != count) {
                    throw new DataFormatException("invalid Lerc2 floating-point PackBits data");
                }
            }
            case HUFFMAN_NORMAL -> {
                var stuffer = new BitStuffer();
                stuffer.src = src;
                stuffer.pos = pos + 1;
                stuffer.end = pos + size;
                var huffman = new LercHuffman();
                huffman.readTable(stuffer, 5);
                huffman.build();
                var reader = huffman.reader(src, stuffer.pos, pos + size, true);
                for (var i = 0; i < count; i++) {
                    plane[i] = (byte) reader.next();
                }
            }
            default -> throw new DataFormatException("invalid Lerc2 floating-point byte plane coding");
        }
        return plane;
    }
}
