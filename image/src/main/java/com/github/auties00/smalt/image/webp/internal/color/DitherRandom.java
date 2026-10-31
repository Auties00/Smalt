/* Ports libwebp 1.6.0 (src/utils/random_utils.c, random_utils.h). Copyright 2013 Google Inc.; BSD 3-Clause
   License. Modified for Smalt. */
package com.github.auties00.smalt.image.webp.internal.color;

/**
 * Generates the centered pseudo-random rounding terms of dithered color conversion with Knuth's
 * subtractive generator ({@code VP8Random}).
 *
 * <p>The sequence is fixed: a generator created with the same amplitude returns the same values
 * in the same order. Instances are not thread-safe.
 */
final class DitherRandom {
    /**
     * The fixed-point precision of the amplitude.
     */
    private static final int DITHER_FIX = 8;

    /**
     * The number of entries of the lagged state.
     */
    private static final int TABLE_SIZE = 55;

    /**
     * The initial state, 31-bit values.
     */
    private static final int[] INITIAL = {
            0x0de15230, 0x03b31886, 0x775faccb, 0x1c88626a, 0x68385c55, 0x14b3b828,
            0x4a85fef8, 0x49ddb84b, 0x64fcf397, 0x5c550289, 0x4a290000, 0x0d7ec1da,
            0x5940b7ab, 0x5492577d, 0x4e19ca72, 0x38d38c69, 0x0c01ee65, 0x32a1755f,
            0x5437f652, 0x5abb2c32, 0x0faa57b1, 0x73f533e7, 0x685feeda, 0x7563cce2,
            0x6e990e83, 0x4730a7ed, 0x4fc0d9c6, 0x496b153c, 0x4f1403fa, 0x541afb0c,
            0x73990b32, 0x26d7cb1c, 0x6fcc3706, 0x2cbb77d8, 0x75762f2a, 0x6425ccdd,
            0x24b35461, 0x0a7d8715, 0x220414a8, 0x141ebf67, 0x56b41583, 0x73e502e3,
            0x44cab16f, 0x28264d42, 0x73baaefb, 0x0a50ebed, 0x1d6ab6fb, 0x0d3ad40b,
            0x35db3b68, 0x2b081e83, 0x77ce6b95, 0x5181e5f0, 0x78853bbc, 0x009f9494,
            0x27e5ed3c
    };

    /**
     * The lagged state.
     */
    private final int[] table = INITIAL.clone();

    /**
     * The amplitude, in 1/256.
     */
    private final int amplitude;

    /**
     * The index of the first lag.
     */
    private int index1;

    /**
     * The index of the second lag.
     */
    private int index2 = 31;

    /**
     * Constructs a generator ({@code VP8InitRandom}).
     *
     * @param dithering the amplitude, from 0 to 1; values outside are clamped
     */
    DitherRandom(float dithering) {
        this.amplitude = dithering < 0.0 ? 0
                : dithering > 1.0 ? 1 << DITHER_FIX
                : (int) ((1 << DITHER_FIX) * dithering);
    }

    /**
     * Returns the next rounding term: half of {@code 1 << bits} plus a centered random offset
     * scaled by the amplitude ({@code VP8RandomBits}).
     *
     * @param bits the precision of the term, from 1 to 23
     * @return the term
     */
    int next(int bits) {
        var diff = table[index1] - table[index2];
        if (diff < 0) {
            diff += 1 << 31;
        }
        table[index1] = diff;
        if (++index1 == TABLE_SIZE) {
            index1 = 0;
        }
        if (++index2 == TABLE_SIZE) {
            index2 = 0;
        }
        diff = (diff << 1) >> (32 - bits);
        diff = (diff * amplitude) >> DITHER_FIX;
        return diff + (1 << (bits - 1));
    }
}
