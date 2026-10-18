package com.github.auties00.smalt.image.webp.internal.lossless;

import java.util.Arrays;

/**
 * Counts the symbols of the five prefix codes of one lossless coding context: green with length
 * prefixes and cache indices, red, blue, alpha, and distance prefixes; plus the cached costs used
 * while clustering.
 *
 * @implNote This implementation follows libwebp's {@code VP8LHistogram} of
 * {@code src/enc/histogram_enc.c} (BSD-3).
 */
final class Histogram {
    /**
     * The index of the literal (green, length and cache) population.
     */
    static final int LITERAL = 0;

    /**
     * The index of the red population.
     */
    static final int RED = 1;

    /**
     * The index of the blue population.
     */
    static final int BLUE = 2;

    /**
     * The index of the alpha population.
     */
    static final int ALPHA = 3;

    /**
     * The index of the distance population.
     */
    static final int DISTANCE = 4;

    /**
     * The number of distance prefix codes.
     */
    static final int NUM_DISTANCE_CODES = 40;

    /**
     * The green literals, length prefixes and color cache indices.
     */
    final int[] literal;

    /**
     * The red literals.
     */
    final int[] red = new int[256];

    /**
     * The blue literals.
     */
    final int[] blue = new int[256];

    /**
     * The alpha literals.
     */
    final int[] alpha = new int[256];

    /**
     * The distance prefixes.
     */
    final int[] distance = new int[NUM_DISTANCE_CODES];

    /**
     * The color cache width in bits.
     */
    final int paletteCodeBits;

    /**
     * The only used symbol of each population, or {@link EntropyMath#NON_TRIVIAL_SYM}.
     */
    final int[] trivialSymbol = new int[5];

    /**
     * Whether each population has a non-zero count.
     */
    final boolean[] isUsed = new boolean[5];

    /**
     * The cost of each population.
     */
    final long[] costs = new long[5];

    /**
     * The total cost.
     */
    long bitCost;

    /**
     * The entropy bin of the histogram.
     */
    int binId;

    /**
     * Constructs a zeroed histogram.
     *
     * @param cacheBits the color cache width in bits
     */
    Histogram(int cacheBits) {
        this.paletteCodeBits = cacheBits;
        this.literal = new int[numCodes(cacheBits)];
        statsClear();
    }

    /**
     * Returns the size of the literal alphabet for a color cache width.
     *
     * @param cacheBits the color cache width in bits
     * @return {@code 256 + 24 + cache size}
     */
    static int numCodes(int cacheBits) {
        return 256 + 24 + (cacheBits > 0 ? 1 << cacheBits : 0);
    }

    /**
     * Resets the cached statistics: every population is assumed used and non-trivial.
     */
    void statsClear() {
        Arrays.fill(trivialSymbol, EntropyMath.NON_TRIVIAL_SYM);
        Arrays.fill(isUsed, true);
        Arrays.fill(costs, 0);
        bitCost = 0;
    }

    /**
     * Zeroes every count and resets the statistics.
     */
    void clear() {
        Arrays.fill(literal, 0);
        Arrays.fill(red, 0);
        Arrays.fill(blue, 0);
        Arrays.fill(alpha, 0);
        Arrays.fill(distance, 0);
        binId = 0;
        statsClear();
    }

    /**
     * Copies the counts and statistics of another histogram with the same cache width.
     *
     * @param src the histogram to copy
     */
    void copyFrom(Histogram src) {
        System.arraycopy(src.literal, 0, literal, 0, literal.length);
        System.arraycopy(src.red, 0, red, 0, 256);
        System.arraycopy(src.blue, 0, blue, 0, 256);
        System.arraycopy(src.alpha, 0, alpha, 0, 256);
        System.arraycopy(src.distance, 0, distance, 0, NUM_DISTANCE_CODES);
        System.arraycopy(src.trivialSymbol, 0, trivialSymbol, 0, 5);
        System.arraycopy(src.isUsed, 0, isUsed, 0, 5);
        System.arraycopy(src.costs, 0, costs, 0, 5);
        bitCost = src.bitCost;
        binId = src.binId;
    }

    /**
     * Returns one of the five populations.
     *
     * @param index the population index
     * @return the counts
     */
    int[] population(int index) {
        return switch (index) {
            case LITERAL -> literal;
            case RED -> red;
            case BLUE -> blue;
            case ALPHA -> alpha;
            default -> distance;
        };
    }

    /**
     * Returns the number of symbols of one of the five populations.
     *
     * @param index the population index
     * @return the alphabet size
     */
    int populationLength(int index) {
        return switch (index) {
            case LITERAL -> literal.length;
            case DISTANCE -> NUM_DISTANCE_CODES;
            default -> 256;
        };
    }

    /**
     * Counts one symbol.
     *
     * @param v      the symbol
     * @param xsize  the image width, used to map distances to plane codes when
     *               {@code planeCode} is set
     * @param planeCode whether distances are converted to plane codes before counting
     */
    void add(long v, int xsize, boolean planeCode) {
        switch (BackwardRefs.mode(v)) {
            case BackwardRefs.LITERAL -> {
                var argb = BackwardRefs.value(v);
                ++alpha[argb >>> 24];
                ++red[(argb >>> 16) & 0xFF];
                ++literal[(argb >>> 8) & 0xFF];
                ++blue[argb & 0xFF];
            }
            case BackwardRefs.CACHE_IDX -> ++literal[256 + 24 + BackwardRefs.value(v)];
            default -> {
                ++literal[256 + Prefix.code(BackwardRefs.length(v))];
                var dist = BackwardRefs.value(v);
                if (planeCode) {
                    dist = Prefix.distanceToPlaneCode(xsize, dist);
                }
                ++distance[Prefix.code(dist)];
            }
        }
    }

    /**
     * Counts every symbol of a sequence.
     *
     * @param refs      the symbols
     * @param xsize     the image width, used when {@code planeCode} is set
     * @param planeCode whether distances are converted to plane codes before counting
     */
    void storeRefs(BackwardRefs refs, int xsize, boolean planeCode) {
        var data = refs.data;
        for (var i = 0; i < refs.size; i++) {
            add(data[i], xsize, planeCode);
        }
    }

    /**
     * Resets this histogram and counts a sequence.
     *
     * @param refs the symbols
     */
    void create(BackwardRefs refs) {
        clear();
        storeRefs(refs, 0, false);
    }

    /**
     * Returns the estimated number of bits needed to code the counted symbols, including the
     * prefix codes and the extra bits of lengths and distances.
     *
     * @param stats scratch statistics
     * @return the fixed-point cost
     */
    long estimateBits(EntropyMath.Stats stats) {
        long cost = 0;
        for (var i = 0; i < 5; i++) {
            cost += EntropyMath.populationCost(population(i), populationLength(i), stats);
        }
        cost += (EntropyMath.extraCost(literal, 256, 24) + EntropyMath.extraCost(distance, 0, NUM_DISTANCE_CODES))
                << EntropyMath.LOG_2_PRECISION_BITS;
        return cost;
    }

    /**
     * Computes the cost, trivial symbol and usage of every population.
     *
     * @param stats scratch statistics
     */
    void computeCost(EntropyMath.Stats stats) {
        for (var i = 0; i < 5; i++) {
            costs[i] = EntropyMath.populationCost(population(i), populationLength(i), stats);
            trivialSymbol[i] = stats.nonzeros == 1 ? stats.nonzeroCode : EntropyMath.NON_TRIVIAL_SYM;
            isUsed[i] = stats.used();
        }
        bitCost = costs[LITERAL] + costs[RED] + costs[BLUE] + costs[ALPHA] + costs[DISTANCE];
    }

    /**
     * Stores {@code h1 + h2} into {@code out}, which may be {@code h2}, honoring the usage flags.
     *
     * @param h1  the first histogram
     * @param h2  the second histogram
     * @param out the destination
     */
    static void add(Histogram h1, Histogram h2, Histogram out) {
        for (var i = 0; i < 5; i++) {
            var length = h1.populationLength(i);
            var p1 = h1.population(i);
            var p2 = h2.population(i);
            var po = out.population(i);
            if (h2 == out) {
                if (h1.isUsed[i]) {
                    if (out.isUsed[i]) {
                        for (var k = 0; k < length; k++) {
                            po[k] += p1[k];
                        }
                    } else {
                        System.arraycopy(p1, 0, po, 0, length);
                    }
                }
            } else if (h1.isUsed[i]) {
                if (h2.isUsed[i]) {
                    for (var k = 0; k < length; k++) {
                        po[k] = p1[k] + p2[k];
                    }
                } else {
                    System.arraycopy(p1, 0, po, 0, length);
                }
            } else if (h2.isUsed[i]) {
                System.arraycopy(p2, 0, po, 0, length);
            } else {
                Arrays.fill(po, 0, length, 0);
            }
        }
        for (var i = 0; i < 5; i++) {
            out.trivialSymbol[i] = h1.trivialSymbol[i] == h2.trivialSymbol[i] ? h1.trivialSymbol[i] : EntropyMath.NON_TRIVIAL_SYM;
            out.isUsed[i] = h1.isUsed[i] || h2.isUsed[i];
        }
    }

    /**
     * Returns the cost of one population of {@code h1 + h2}.
     *
     * @param h1    the first histogram
     * @param h2    the second histogram
     * @param index the population index
     * @param stats scratch statistics
     * @return the fixed-point cost
     */
    static long combinedEntropy(Histogram h1, Histogram h2, int index, EntropyMath.Stats stats) {
        var used1 = h1.isUsed[index];
        var used2 = h2.isUsed[index];
        var trivial = h1.trivialSymbol[index] != EntropyMath.NON_TRIVIAL_SYM
                && h1.trivialSymbol[index] == h2.trivialSymbol[index];
        if (trivial || !used1 || !used2) {
            return used1 ? h1.costs[index] : h2.costs[index];
        }
        LosslessDsp.INSTANCE.entropyUnrefined(h1.population(index), h2.population(index), h1.populationLength(index), stats);
        return EntropyMath.bitsEntropyRefine(stats) + EntropyMath.finalHuffmanCost(stats);
    }

    /**
     * Computes the cost of {@code a + b}, stopping early once it reaches a threshold.
     *
     * @param a         the first histogram
     * @param b         the second histogram
     * @param threshold the cost bound; a non-positive bound always fails
     * @param costs     the destination of the five population costs
     * @param stats     scratch statistics
     * @return the total cost, or {@code -1} when it reaches the threshold
     */
    static long combinedHistogramEntropy(Histogram a, Histogram b, long threshold, long[] costs, EntropyMath.Stats stats) {
        if (threshold <= 0) {
            return -1;
        }
        long cost = 0;
        for (var i = 0; i < 5; i++) {
            costs[i] = combinedEntropy(a, b, i, stats);
            cost += costs[i];
            if (Long.compareUnsigned(cost, threshold) >= 0) {
                return -1;
            }
        }
        return cost;
    }

    /**
     * Adds a cost to a threshold, saturating at {@link Long#MAX_VALUE}.
     *
     * @param a         the non-negative cost
     * @param threshold the threshold
     * @return the sum
     */
    static long saturateAdd(long a, long threshold) {
        if (threshold < 0 || a <= Long.MAX_VALUE - threshold) {
            return threshold + a;
        }
        return Long.MAX_VALUE;
    }
}
