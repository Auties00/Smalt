package com.github.auties00.smalt.image.webp.internal.lossless;

import java.util.Arrays;

/**
 * Holds a sequence of LZ77 symbols: literals, color cache indices and backward copies.
 *
 * <p>Each symbol is packed in a {@code long}: the mode in bits 48-49, the length in bits 32-47
 * and the ARGB literal, cache index or distance in bits 0-31.
 */
final class BackwardRefs {
    /**
     * The mode of a literal pixel.
     */
    static final int LITERAL = 0;

    /**
     * The mode of a color cache index.
     */
    static final int CACHE_IDX = 1;

    /**
     * The mode of a backward copy.
     */
    static final int COPY = 2;

    /**
     * The packed symbols.
     */
    long[] data;

    /**
     * The number of symbols.
     */
    int size;

    /**
     * Constructs an empty sequence.
     *
     * @param capacity the initial capacity
     */
    BackwardRefs(int capacity) {
        this.data = new long[Math.max(capacity, 256)];
    }

    /**
     * Packs a literal.
     *
     * @param argb the pixel
     * @return the symbol
     */
    static long literal(int argb) {
        return (1L << 32) | (argb & 0xFFFFFFFFL);
    }

    /**
     * Packs a color cache index.
     *
     * @param index the cache index
     * @return the symbol
     */
    static long cacheIdx(int index) {
        return ((long) CACHE_IDX << 48) | (1L << 32) | index;
    }

    /**
     * Packs a backward copy.
     *
     * @param distance the distance or plane code
     * @param length   the number of pixels
     * @return the symbol
     */
    static long copy(int distance, int length) {
        return ((long) COPY << 48) | ((long) length << 32) | (distance & 0xFFFFFFFFL);
    }

    /**
     * Returns the mode of a symbol.
     *
     * @param v the symbol
     * @return {@link #LITERAL}, {@link #CACHE_IDX} or {@link #COPY}
     */
    static int mode(long v) {
        return (int) (v >>> 48);
    }

    /**
     * Returns the number of pixels a symbol covers.
     *
     * @param v the symbol
     * @return the length, 1 for literals and cache indices
     */
    static int length(long v) {
        return (int) (v >>> 32) & 0xFFFF;
    }

    /**
     * Returns the value of a symbol: the ARGB literal, the cache index or the distance.
     *
     * @param v the symbol
     * @return the value
     */
    static int value(long v) {
        return (int) v;
    }

    /**
     * Removes every symbol.
     */
    void clear() {
        size = 0;
    }

    /**
     * Appends a symbol.
     *
     * @param v the symbol
     */
    void add(long v) {
        if (size == data.length) {
            data = Arrays.copyOf(data, data.length * 2);
        }
        data[size++] = v;
    }

    /**
     * Replaces this sequence with a copy of another.
     *
     * @param other the sequence to copy
     */
    void copyFrom(BackwardRefs other) {
        if (data.length < other.size) {
            data = new long[other.data.length];
        }
        System.arraycopy(other.data, 0, data, 0, other.size);
        size = other.size;
    }
}
