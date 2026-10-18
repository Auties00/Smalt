package com.github.auties00.smalt.image.webp.internal.lossless;

import com.github.auties00.smalt.internal.task.ParallelBands;

import java.util.Arrays;

/**
 * Finds, for every pixel, the longest earlier match of the pixels that follow it, as the
 * starting point of LZ77 parsing.
 *
 * <p>The result is packed per pixel: the distance of the best match in the upper 20 bits and its
 * length (at most {@link #MAX_LENGTH}) in the lower 12 bits.
 *
 * @implNote This implementation follows libwebp's {@code VP8LHashChainFill} of
 * {@code src/enc/backward_references_enc.c} (BSD-3): pixel pairs are hashed into an 18-bit
 * table chaining earlier positions, runs of one color are hashed by color and run length, the
 * search tries the pixel above and the previous pixel first, the number of chain steps and the
 * window grow with the quality, and a match found at one position is extended leftwards to the
 * preceding positions.
 */
final class HashChain {
    /**
     * The width in bits of match lengths.
     */
    static final int MAX_LENGTH_BITS = 12;

    /**
     * The longest match length.
     */
    static final int MAX_LENGTH = (1 << MAX_LENGTH_BITS) - 1;

    /**
     * The width in bits of the pixel-pair hash.
     */
    private static final int HASH_BITS = 18;

    /**
     * The size of the pixel-pair hash table.
     */
    private static final int HASH_SIZE = 1 << HASH_BITS;

    /**
     * The largest match distance: the 20-bit field minus the 120 plane codes.
     */
    static final int WINDOW_SIZE = (1 << 20) - 120;

    /**
     * The smallest image, in pixels, whose matches are searched speculatively on several threads.
     */
    private static final int PARALLEL_MIN_SIZE = 1 << 17;

    /**
     * The number of positions searched at once ahead of the sequential pass.
     */
    private static final int PARALLEL_CHUNK = 1 << 15;

    /**
     * The ratio of searched positions to positions the sequential pass uses above which the
     * remaining positions are searched one at a time.
     */
    private static final int WASTE_LIMIT = 2;

    /**
     * The packed offset and length of the best match of every pixel.
     */
    final int[] offsetLength;

    /**
     * Constructs a chain for up to {@code size} pixels.
     *
     * @param size the pixel capacity
     */
    HashChain(int size) {
        this.offsetLength = new int[Math.max(size, 1)];
    }

    /**
     * Returns the distance of the best match at a position.
     *
     * @param pos the pixel position
     * @return the distance, {@code 0} when there is none
     */
    int offset(int pos) {
        return offsetLength[pos] >>> MAX_LENGTH_BITS;
    }

    /**
     * Returns the length of the best match at a position.
     *
     * @param pos the pixel position
     * @return the length
     */
    int length(int pos) {
        return offsetLength[pos] & MAX_LENGTH;
    }

    /**
     * Hashes a pair of pixels.
     *
     * @param first  the first pixel
     * @param second the second pixel
     * @return the 18-bit hash
     */
    private static int pairHash(int first, int second) {
        var key = second * 0xC6A4A793 + first * 0x5BD1E996;
        return key >>> (32 - HASH_BITS);
    }

    /**
     * Returns the number of chain steps searched at a quality.
     *
     * @param quality the encoder quality, from 0 to 100
     * @return the step count
     */
    private static int maxItersForQuality(int quality) {
        return 8 + (quality * quality) / 128;
    }

    /**
     * Returns the search window at a quality.
     *
     * @param quality the encoder quality, from 0 to 100
     * @param xsize   the image width
     * @return the largest distance searched
     */
    private static int windowSizeForHashChain(int quality, int xsize) {
        long maxWindow = quality > 75 ? WINDOW_SIZE : quality > 50 ? ((long) xsize << 8)
                : quality > 25 ? ((long) xsize << 6) : ((long) xsize << 4);
        return (int) Math.min(maxWindow, WINDOW_SIZE);
    }

    /**
     * Returns the length of the common prefix of two pixel runs, after a quick check at the
     * current best length.
     *
     * @param argb      the pixels
     * @param a         the start of the first run
     * @param b         the start of the second run
     * @param bestLen   the length to check first
     * @param maxLimit  the largest length examined
     * @return the match length, {@code 0} when the pixels at {@code bestLen} differ
     */
    static int findMatchLength(int[] argb, int a, int b, int bestLen, int maxLimit) {
        if (argb[a + bestLen] != argb[b + bestLen]) {
            return 0;
        }
        return vectorMismatch(argb, a, b, maxLimit);
    }

    /**
     * Returns the length of the common prefix of two pixel runs.
     *
     * @param argb   the pixels
     * @param a      the start of the first run
     * @param b      the start of the second run
     * @param length the largest length examined
     * @return the match length
     */
    static int vectorMismatch(int[] argb, int a, int b, int length) {
        var mismatch = Arrays.mismatch(argb, a, a + length, argb, b, b + length);
        return mismatch < 0 ? length : mismatch;
    }

    /**
     * Computes the best match of every pixel.
     *
     * @param quality   the encoder quality, from 0 to 100
     * @param argb      the pixels
     * @param xsize     the image width
     * @param ysize     the image height
     * @param lowEffort whether to skip the above/left heuristics
     */
    void fill(int quality, int[] argb, int xsize, int ysize, boolean lowEffort) {
        fill(quality, argb, xsize, ysize, lowEffort, true);
    }

    /**
     * Computes the best match of every pixel, searching on several threads when allowed.
     *
     * <p>The result does not depend on the threading.
     *
     * @param quality   the encoder quality, from 0 to 100
     * @param argb      the pixels
     * @param xsize     the image width
     * @param ysize     the image height
     * @param lowEffort whether to skip the above/left heuristics
     * @param threads   whether the search may use several threads
     */
    void fill(int quality, int[] argb, int xsize, int ysize, boolean lowEffort, boolean threads) {
        var size = xsize * ysize;
        var iterMax = maxItersForQuality(quality);
        var windowSize = windowSizeForHashChain(quality, xsize);
        if (size <= 2) {
            offsetLength[0] = 0;
            offsetLength[size - 1] = 0;
            return;
        }
        var hashToFirstIndex = new int[HASH_SIZE];
        Arrays.fill(hashToFirstIndex, -1);
        var chain = offsetLength;
        var argbComp = argb[0] == argb[1];
        var pos = 0;
        while (pos < size - 2) {
            var argbCompNext = argb[pos + 1] == argb[pos + 2];
            if (argbComp && argbCompNext) {
                var color = argb[pos];
                var len = 1;
                while (pos + len + 2 < size && argb[pos + len + 2] == color) {
                    ++len;
                }
                if (len > MAX_LENGTH) {
                    Arrays.fill(chain, pos, pos + len - MAX_LENGTH, -1);
                    pos += len - MAX_LENGTH;
                    len = MAX_LENGTH;
                }
                while (len != 0) {
                    var hash = pairHash(color, len--);
                    chain[pos] = hashToFirstIndex[hash];
                    hashToFirstIndex[hash] = pos++;
                }
                argbComp = false;
            } else {
                var hash = pairHash(argb[pos], argb[pos + 1]);
                chain[pos] = hashToFirstIndex[hash];
                hashToFirstIndex[hash] = pos++;
                argbComp = argbCompNext;
            }
        }
        chain[pos] = hashToFirstIndex[pairHash(argb[pos], argb[pos + 1])];
        offsetLength[0] = 0;
        offsetLength[size - 1] = 0;
        var parallel = threads && size >= PARALLEL_MIN_SIZE && ParallelBands.enabled();
        var speculative = parallel ? new int[size] : null;
        var specLow = size - 1;
        var specTop = size - 1;
        var used = 0;
        for (var basePosition = size - 2; basePosition > 0; ) {
            int found;
            if (parallel) {
                if (basePosition < specLow) {
                    var computed = specTop - specLow + 1;
                    if (computed > 0 && computed > WASTE_LIMIT * Math.max(1, used)) {
                        parallel = false;
                    } else {
                        specTop = basePosition;
                        specLow = Math.max(1, basePosition - PARALLEL_CHUNK + 1);
                        used = 0;
                        var from = specLow;
                        var count = specTop - specLow + 1;
                        var bands = ParallelBands.bands(count, (long) count * 64);
                        var table = speculative;
                        ParallelBands.run(bands, band -> {
                            var end = from + ParallelBands.start(band + 1, bands, count);
                            for (var position = from + ParallelBands.start(band, bands, count); position < end; position++) {
                                table[position] = search(argb, chain, position, size, xsize, iterMax, windowSize, lowEffort);
                            }
                        });
                    }
                }
                found = parallel ? speculative[basePosition] : search(argb, chain, basePosition, size, xsize, iterMax,
                        windowSize, lowEffort);
                used++;
            } else {
                found = search(argb, chain, basePosition, size, xsize, iterMax, windowSize, lowEffort);
            }
            var bestLength = found & MAX_LENGTH;
            var bestDistance = found >>> MAX_LENGTH_BITS;
            var maxBasePosition = basePosition;
            while (true) {
                offsetLength[basePosition] = (bestDistance << MAX_LENGTH_BITS) | bestLength;
                --basePosition;
                if (bestDistance == 0 || basePosition == 0) {
                    break;
                }
                if (basePosition < bestDistance || argb[basePosition - bestDistance] != argb[basePosition]) {
                    break;
                }
                if (bestLength == MAX_LENGTH && bestDistance != 1 && basePosition + MAX_LENGTH < maxBasePosition) {
                    break;
                }
                if (bestLength < MAX_LENGTH) {
                    ++bestLength;
                    maxBasePosition = basePosition;
                }
            }
        }
    }

    /**
     * Finds the best match of one pixel, a function of the pixels and of the chain links of the
     * positions before it only.
     *
     * @param argb        the pixels
     * @param chain       the chain links
     * @param basePosition the pixel position, at least {@code 1} and at most {@code size - 2}
     * @param size        the pixel count
     * @param xsize       the image width
     * @param iterMax     the most chain steps
     * @param windowSize  the largest distance searched
     * @param lowEffort   whether to skip the above/left heuristics
     * @return the packed distance and length
     */
    private static int search(int[] argb, int[] chain, int basePosition, int size, int xsize, int iterMax,
                              int windowSize, boolean lowEffort) {
        var maxLen = Math.min(size - 1 - basePosition, MAX_LENGTH);
        var argbStart = basePosition;
        var iter = iterMax;
        var bestLength = 0;
        var bestDistance = 0;
        var minPos = basePosition > windowSize ? basePosition - windowSize : 0;
        var lengthMax = Math.min(maxLen, 256);
        var pos = chain[basePosition];
        if (!lowEffort) {
            if (basePosition >= xsize) {
                var currLength = findMatchLength(argb, argbStart - xsize, argbStart, bestLength, maxLen);
                if (currLength > bestLength) {
                    bestLength = currLength;
                    bestDistance = xsize;
                }
                --iter;
            }
            var currLength = findMatchLength(argb, argbStart - 1, argbStart, bestLength, maxLen);
            if (currLength > bestLength) {
                bestLength = currLength;
                bestDistance = 1;
            }
            --iter;
            if (bestLength == MAX_LENGTH) {
                pos = minPos - 1;
            }
        }
        var bestArgb = argb[argbStart + bestLength];
        for (; pos >= minPos && --iter != 0; pos = chain[pos]) {
            if (argb[pos + bestLength] != bestArgb) {
                continue;
            }
            var currLength = vectorMismatch(argb, pos, argbStart, maxLen);
            if (bestLength < currLength) {
                bestLength = currLength;
                bestDistance = basePosition - pos;
                bestArgb = argb[argbStart + bestLength];
                if (bestLength >= lengthMax) {
                    break;
                }
            }
        }
        return (bestDistance << MAX_LENGTH_BITS) | bestLength;
    }
}
