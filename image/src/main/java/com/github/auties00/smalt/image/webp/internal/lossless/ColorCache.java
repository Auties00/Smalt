package com.github.auties00.smalt.image.webp.internal.lossless;

/**
 * Holds the color cache of the lossless encoder: a direct-mapped table of recently seen pixels
 * indexed by a multiplicative hash (RFC 9649, section 4.2.3).
 */
final class ColorCache {
    /**
     * The multiplier of the hash.
     */
    static final int HASH_MUL = 0x1E35A7BD;

    /**
     * The cached colors.
     */
    final int[] colors;

    /**
     * The right shift turning the hash into an index.
     */
    final int shift;

    /**
     * Constructs an empty cache.
     *
     * @param bits the cache width in bits, from 1 to 11
     */
    ColorCache(int bits) {
        this.colors = new int[1 << bits];
        this.shift = 32 - bits;
    }

    /**
     * Hashes a pixel.
     *
     * @param argb  the pixel
     * @param shift the right shift, {@code 32 - bits}
     * @return the index
     */
    static int hash(int argb, int shift) {
        return (argb * HASH_MUL) >>> shift;
    }

    /**
     * Returns the index of a pixel.
     *
     * @param argb the pixel
     * @return the cache index
     */
    int index(int argb) {
        return (argb * HASH_MUL) >>> shift;
    }

    /**
     * Stores a pixel at its index.
     *
     * @param argb the pixel
     */
    void insert(int argb) {
        colors[(argb * HASH_MUL) >>> shift] = argb;
    }

    /**
     * Returns the index of a pixel when the cache holds it.
     *
     * @param argb the pixel
     * @return the index, or {@code -1} when the slot holds another color
     */
    int contains(int argb) {
        var key = (argb * HASH_MUL) >>> shift;
        return colors[key] == argb ? key : -1;
    }
}
