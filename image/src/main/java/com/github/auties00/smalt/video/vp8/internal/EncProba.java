/* Ports libwebp 1.6.0 (src/enc/cost_enc.c, src/dsp/cost.c, src/enc/frame_enc.c, src/enc/token_enc.c). Copyright (c)
   2010, Google Inc.; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.video.vp8.internal;

/**
 * Holds the token probabilities, their statistics, and the derived rate tables of the VP8
 * key-frame encoder, and codes, records, and prices coefficient blocks.
 *
 * <p>Token probabilities, statistics, and costs share the layout
 * {@code [type 4][band 8][context 3][...]}; costs have 68 entries per context, one per level up
 * to 67.
 *
 * @implNote This implementation keeps libwebp's quirks, for example category 5 and 6 tokens
 * record their statistics in node 9's slot, so the output matches libwebp byte for byte.
 */
final class EncProba {
    /**
     * The number of levels with a probability-dependent cost.
     */
    static final int MAX_VARIABLE_LEVEL = 67;

    /**
     * The bit that marks a token recorded with a constant probability.
     */
    static final int FIXED_PROBA_BIT = 1 << 14;

    /**
     * The segment map tree probabilities.
     */
    final int[] segments;

    /**
     * The probability that a macroblock is not skipped.
     */
    int skipProba;

    /**
     * Whether the skip flag is coded.
     */
    boolean useSkipProba;

    /**
     * The number of skipped macroblocks counted by the statistics pass.
     */
    int nbSkip;

    /**
     * Whether {@link #levelCost} must be recomputed.
     */
    boolean dirty;

    /**
     * The token probabilities.
     */
    final int[] coeffs;

    /**
     * The token statistics: the count of ones in the low 16 bits and the total count in the high
     * 16 bits.
     */
    final int[] stats = new int[1056];

    /**
     * The cost of each level, {@code [type][band][context][level 0..67]}.
     */
    final int[] levelCost;

    /**
     * The index into {@link #levelCost} of each type, scan position (0 to 16), and context.
     */
    final int[] remapped;

    /**
     * Creates the default probabilities.
     */
    EncProba() {
        segments = new int[]{255, 255, 255};
        coeffs = new int[1056];
        levelCost = new int[4 * 8 * 3 * (MAX_VARIABLE_LEVEL + 1)];
        remapped = new int[4 * 17 * 3];
        System.arraycopy(Vp8Tables.COEF_PROBS_DEFAULT, 0, coeffs, 0, coeffs.length);
        dirty = true;
    }

    /**
     * Creates a view of another instance's probabilities and level costs with statistics of its
     * own, for a thread that codes macroblocks while the other instance is not being updated.
     *
     * @param shared the instance whose probabilities, costs and segment tree the view reads
     */
    EncProba(EncProba shared) {
        segments = shared.segments;
        coeffs = shared.coeffs;
        levelCost = shared.levelCost;
        remapped = shared.remapped;
        skipProba = shared.skipProba;
        useSkipProba = shared.useSkipProba;
    }

    /**
     * Adds the statistics of the adaptive tokens of a token run, in order, as if they had been
     * recorded while the run was coded.
     *
     * @param tokens the token array
     * @param from   the first token
     * @param to     the end of the run
     */
    void replayTokens(short[] tokens, int from, int to) {
        var st = stats;
        for (var i = from; i < to; i++) {
            int t = tokens[i];
            if ((t & FIXED_PROBA_BIT) != 0) {
                continue;
            }
            var idx = t & 0x3FFF;
            if (idx % 11 == 10) {
                idx--;
            }
            recordStats(t >>> 15 & 1, st, idx);
        }
    }

    /**
     * Returns the index of the first probability of a type, band, and context.
     *
     * @param type the block type
     * @param band the coefficient band
     * @param ctx  the context
     * @return the index into {@link #coeffs} and {@link #stats}
     */
    static int index(int type, int band, int ctx) {
        return ((type * 8 + band) * 3 + ctx) * 11;
    }

    /**
     * Returns the cost of coding a bit with a probability.
     *
     * @param bit the coded bit, 0 or 1
     * @param proba the probability of a zero
     * @return the cost in 1/256 bit
     */
    static int bitCost(int bit, int proba) {
        return bit == 0 ? Vp8EncTables.ENTROPY_COST[proba] : Vp8EncTables.ENTROPY_COST[255 - proba];
    }

    /**
     * Returns the cost of a level with a cost table.
     *
     * @param table the index of the table in {@link #levelCost}
     * @param level the level magnitude
     * @return the cost in 1/256 bit
     */
    int levelCost(int table, int level) {
        return Vp8EncTables.LEVEL_FIXED_COSTS[level] + levelCost[table + Math.min(level, MAX_VARIABLE_LEVEL)];
    }

    /**
     * Recomputes the level cost tables when the probabilities changed.
     */
    void calculateLevelCosts() {
        if (!dirty) {
            return;
        }
        for (var type = 0; type < 4; type++) {
            for (var band = 0; band < 8; band++) {
                for (var ctx = 0; ctx < 3; ctx++) {
                    var p = index(type, band, ctx);
                    var table = ((type * 8 + band) * 3 + ctx) * (MAX_VARIABLE_LEVEL + 1);
                    var cost0 = ctx > 0 ? bitCost(1, coeffs[p]) : 0;
                    var costBase = bitCost(1, coeffs[p + 1]) + cost0;
                    levelCost[table] = bitCost(0, coeffs[p + 1]) + cost0;
                    for (var v = 1; v <= MAX_VARIABLE_LEVEL; v++) {
                        levelCost[table + v] = costBase + variableLevelCost(v, p);
                    }
                }
            }
            for (var n = 0; n < 17; n++) {
                for (var ctx = 0; ctx < 3; ctx++) {
                    remapped[(type * 17 + n) * 3 + ctx] =
                            ((type * 8 + Vp8Tables.BANDS[n]) * 3 + ctx) * (MAX_VARIABLE_LEVEL + 1);
                }
            }
        }
        dirty = false;
    }

    /**
     * Returns the probability-dependent cost of the token path of a level.
     *
     * @param level the level, {@code [1, 67]}
     * @param p     the index of the context's probabilities
     * @return the cost in 1/256 bit
     */
    private int variableLevelCost(int level, int p) {
        var pattern = Vp8EncTables.LEVEL_CODES[(level - 1) * 2];
        var bits = Vp8EncTables.LEVEL_CODES[(level - 1) * 2 + 1];
        var cost = 0;
        for (var i = 2; pattern != 0; i++) {
            if ((pattern & 1) != 0) {
                cost += bitCost(bits & 1, coeffs[p + i]);
            }
            bits >>= 1;
            pattern >>= 1;
        }
        return cost;
    }

    /**
     * Records one bit in a statistics counter, halving the counts before they overflow.
     *
     * @param bit the coded bit, 0 or 1
     * @param stats the counters
     * @param index the counter index
     * @return {@code bit}
     */
    static int recordStats(int bit, int[] stats, int index) {
        var p = stats[index];
        if (Integer.compareUnsigned(p, 0xFFFE0000) >= 0) {
            p = ((p + 1) >>> 1) & 0x7FFF7FFF;
        }
        p += 0x00010000 + bit;
        stats[index] = p;
        return bit;
    }

    /**
     * Returns the new probability of a node from its statistics.
     *
     * @param nb    the number of ones
     * @param total the total count
     * @return the probability of a zero
     */
    private static int calcTokenProba(int nb, int total) {
        return nb != 0 ? (255 - nb * 255 / total) : 255;
    }

    /**
     * Returns the cost of coding a sequence of bits with a probability.
     *
     * @param nb    the number of ones
     * @param total the number of bits
     * @param proba the probability
     * @return the cost in 1/256 bit
     */
    private static int branchCost(int nb, int total, int proba) {
        return nb * bitCost(1, proba) + (total - nb) * bitCost(0, proba);
    }

    /**
     * Chooses the probabilities worth updating from the statistics and stores them in
     * {@link #coeffs}.
     *
     * @return the cost of coding the probability updates, in 1/256 bit
     */
    int finalizeTokenProbas() {
        var hasChanged = false;
        var size = 0;
        for (var i = 0; i < 1056; i++) {
            var s = stats[i];
            var nb = s & 0xFFFF;
            var total = (s >>> 16) & 0xFFFF;
            var update = Vp8Tables.COEF_UPDATE_PROBS[i];
            var oldP = Vp8Tables.COEF_PROBS_DEFAULT[i];
            var newP = calcTokenProba(nb, total);
            var oldCost = branchCost(nb, total, oldP) + bitCost(0, update);
            var newCost = branchCost(nb, total, newP) + bitCost(1, update) + 8 * 256;
            var useNew = oldCost > newCost;
            size += bitCost(useNew ? 1 : 0, update);
            if (useNew) {
                coeffs[i] = newP;
                hasChanged |= newP != oldP;
                size += 8 * 256;
            } else {
                coeffs[i] = oldP;
            }
        }
        dirty = hasChanged;
        return size;
    }

    /**
     * Chooses whether to code the skip flag from the number of skipped macroblocks.
     *
     * @param mbCount the number of macroblocks
     */
    void finalizeSkipProba(int mbCount) {
        skipProba = mbCount != 0 ? (int) ((long) (mbCount - nbSkip) * 255 / mbCount) : 255;
        useSkipProba = skipProba < 250;
    }

    /**
     * Describes the coefficients of one block for costing, recording, and coding.
     *
     * @implNote This implementation mirrors libwebp's {@code VP8Residual}.
     */
    static final class Residual {
        /**
         * The first coded scan position.
         */
        int first;

        /**
         * The last nonzero scan position, or {@code -1}.
         */
        int last;

        /**
         * The block type.
         */
        int type;

        /**
         * The levels in scan order.
         */
        short[] coeffs;

        /**
         * The index of the first level in {@link #coeffs}.
         */
        int offset;

        /**
         * Creates an empty description.
         */
        Residual() {
        }

        /**
         * Starts describing blocks of a type.
         *
         * @param first the first coded scan position
         * @param type  the block type
         */
        void init(int first, int type) {
            this.first = first;
            this.type = type;
        }

        /**
         * Points at a block's levels and finds its last nonzero one.
         *
         * @param levels the levels in scan order
         * @param off    the index of the first level
         */
        void setCoeffs(short[] levels, int off) {
            coeffs = levels;
            offset = off;
            last = Vp8EncDsp.INSTANCE.lastNonZero(levels, off);
        }
    }

    /**
     * Returns the cost of coding a block's levels.
     *
     * @param ctx0 the nonzero context
     * @param res  the block
     * @return the cost in 1/256 bit
     */
    int residualCost(int ctx0, Residual res) {
        var n = res.first;
        var type = res.type;
        var p0 = coeffs[index(type, n, ctx0)];
        if (res.last < 0) {
            return bitCost(0, p0);
        }
        var cost = ctx0 == 0 ? bitCost(1, p0) : 0;
        var t = remapped[(type * 17 + n) * 3 + ctx0];
        var levels = res.coeffs;
        var off = res.offset;
        for (; n < res.last; n++) {
            var v = Math.abs(levels[off + n]);
            var ctx = v >= 2 ? 2 : v;
            cost += levelCost(t, v);
            t = remapped[(type * 17 + n + 1) * 3 + ctx];
        }
        var v = Math.abs(levels[off + n]);
        cost += levelCost(t, v);
        if (n < 15) {
            var b = Vp8Tables.BANDS[n + 1];
            var ctx = v == 1 ? 1 : 2;
            cost += bitCost(0, coeffs[index(type, b, ctx)]);
        }
        return cost;
    }

    /**
     * Records the token statistics of a block without coding it, like libwebp's
     * {@code VP8RecordCoeffs}.
     *
     * @param ctx the nonzero context
     * @param res the block
     * @return {@code 1} when the block has a nonzero level, {@code 0} otherwise
     */
    int recordCoeffs(int ctx, Residual res) {
        var n = res.first;
        var type = res.type;
        var s = index(type, n, ctx);
        var st = stats;
        if (res.last < 0) {
            recordStats(0, st, s);
            return 0;
        }
        var levels = res.coeffs;
        var off = res.offset;
        while (n <= res.last) {
            int v;
            recordStats(1, st, s);
            while ((v = levels[off + n++]) == 0) {
                recordStats(0, st, s + 1);
                s = index(type, Vp8Tables.BANDS[n], 0);
            }
            recordStats(1, st, s + 1);
            if (recordStats(Integer.compareUnsigned(2, v + 1) < 0 ? 1 : 0, st, s + 2) == 0) {
                s = index(type, Vp8Tables.BANDS[n], 1);
            } else {
                v = Math.abs(v);
                if (v > MAX_VARIABLE_LEVEL) {
                    v = MAX_VARIABLE_LEVEL;
                }
                var bits = Vp8EncTables.LEVEL_CODES[(v - 1) * 2 + 1];
                var pattern = Vp8EncTables.LEVEL_CODES[(v - 1) * 2];
                for (var i = 0; (pattern >>= 1) != 0; i++) {
                    var mask = 2 << i;
                    if ((pattern & 1) != 0) {
                        recordStats((bits & mask) != 0 ? 1 : 0, st, s + 3 + i);
                    }
                }
                s = index(type, Vp8Tables.BANDS[n], 2);
            }
        }
        if (n < 16) {
            recordStats(0, st, s);
        }
        return 1;
    }

    /**
     * Records the tokens of a block into a token buffer, updating the statistics, like libwebp's
     * {@code VP8RecordCoeffTokens}.
     *
     * @param ctx    the nonzero context
     * @param res    the block
     * @param tokens the token buffer
     * @return {@code 1} when the block has a nonzero level, {@code 0} otherwise
     */
    int recordTokens(int ctx, Residual res, TokenBuffer tokens) {
        var levels = res.coeffs;
        var off = res.offset;
        var type = res.type;
        var last = res.last;
        var n = res.first;
        var base = index(type, n, ctx);
        var st = stats;
        if (addToken(tokens, last >= 0 ? 1 : 0, base, st, base) == 0) {
            return 0;
        }
        while (n < 16) {
            int c = levels[off + n++];
            var sign = c < 0 ? 1 : 0;
            var v = sign != 0 ? -c : c;
            if (addToken(tokens, v != 0 ? 1 : 0, base + 1, st, base + 1) == 0) {
                base = index(type, Vp8Tables.BANDS[n], 0);
                continue;
            }
            if (addToken(tokens, v > 1 ? 1 : 0, base + 2, st, base + 2) == 0) {
                base = index(type, Vp8Tables.BANDS[n], 1);
            } else {
                if (addToken(tokens, v > 4 ? 1 : 0, base + 3, st, base + 3) == 0) {
                    if (addToken(tokens, v != 2 ? 1 : 0, base + 4, st, base + 4) != 0) {
                        addToken(tokens, v == 4 ? 1 : 0, base + 5, st, base + 5);
                    }
                } else if (addToken(tokens, v > 10 ? 1 : 0, base + 6, st, base + 6) == 0) {
                    if (addToken(tokens, v > 6 ? 1 : 0, base + 7, st, base + 7) == 0) {
                        tokens.add((v == 6 ? 1 << 15 : 0) | FIXED_PROBA_BIT | 159);
                    } else {
                        tokens.add((v >= 9 ? 1 << 15 : 0) | FIXED_PROBA_BIT | 165);
                        tokens.add(((v & 1) == 0 ? 1 << 15 : 0) | FIXED_PROBA_BIT | 145);
                    }
                } else {
                    int mask;
                    int[] tab;
                    var residue = v - 3;
                    if (residue < (8 << 1)) {
                        addToken(tokens, 0, base + 8, st, base + 8);
                        addToken(tokens, 0, base + 9, st, base + 9);
                        residue -= 8;
                        mask = 1 << 2;
                        tab = Vp8Tables.CAT3_PROBS;
                    } else if (residue < (8 << 2)) {
                        addToken(tokens, 0, base + 8, st, base + 8);
                        addToken(tokens, 1, base + 9, st, base + 9);
                        residue -= 8 << 1;
                        mask = 1 << 3;
                        tab = Vp8Tables.CAT4_PROBS;
                    } else if (residue < (8 << 3)) {
                        addToken(tokens, 1, base + 8, st, base + 8);
                        addToken(tokens, 0, base + 10, st, base + 9);
                        residue -= 8 << 2;
                        mask = 1 << 4;
                        tab = Vp8Tables.CAT5_PROBS;
                    } else {
                        addToken(tokens, 1, base + 8, st, base + 8);
                        addToken(tokens, 1, base + 10, st, base + 9);
                        residue -= 8 << 3;
                        mask = 1 << 10;
                        tab = Vp8Tables.CAT6_PROBS;
                    }
                    var t = 0;
                    while (mask != 0) {
                        tokens.add(((residue & mask) != 0 ? 1 << 15 : 0) | FIXED_PROBA_BIT | tab[t++]);
                        mask >>= 1;
                    }
                }
                base = index(type, Vp8Tables.BANDS[n], 2);
            }
            tokens.add((sign << 15) | FIXED_PROBA_BIT | 128);
            if (n == 16 || addToken(tokens, n <= last ? 1 : 0, base, st, base) == 0) {
                return 1;
            }
        }
        return 1;
    }

    /**
     * Appends a token coded with an adaptive probability and records its statistics.
     *
     * @param tokens the token buffer
     * @param bit the coded bit, 0 or 1
     * @param probaIdx the index of the probability in {@link #coeffs}
     * @param st the statistics
     * @param statIdx the index of the statistics counter
     * @return {@code bit}
     */
    private static int addToken(TokenBuffer tokens, int bit, int probaIdx, int[] st, int statIdx) {
        tokens.add((bit << 15) | probaIdx);
        recordStats(bit, st, statIdx);
        return bit;
    }

    /**
     * Codes the tokens of a block, like libwebp's {@code PutCoeffs}.
     *
     * @param bw  the partition writer
     * @param ctx the nonzero context
     * @param res the block
     * @return {@code 1} when the block has a nonzero level, {@code 0} otherwise
     */
    int putCoeffs(BoolEncoder bw, int ctx, Residual res) {
        var n = res.first;
        var type = res.type;
        var levels = res.coeffs;
        var off = res.offset;
        var p = index(type, n, ctx);
        var probs = coeffs;
        if (bw.putBit(res.last >= 0 ? 1 : 0, probs[p]) == 0) {
            return 0;
        }
        while (n < 16) {
            int c = levels[off + n++];
            var sign = c < 0;
            var v = sign ? -c : c;
            if (bw.putBit(v != 0 ? 1 : 0, probs[p + 1]) == 0) {
                p = index(type, Vp8Tables.BANDS[n], 0);
                continue;
            }
            if (bw.putBit(v > 1 ? 1 : 0, probs[p + 2]) == 0) {
                p = index(type, Vp8Tables.BANDS[n], 1);
            } else {
                if (bw.putBit(v > 4 ? 1 : 0, probs[p + 3]) == 0) {
                    if (bw.putBit(v != 2 ? 1 : 0, probs[p + 4]) != 0) {
                        bw.putBit(v == 4 ? 1 : 0, probs[p + 5]);
                    }
                } else if (bw.putBit(v > 10 ? 1 : 0, probs[p + 6]) == 0) {
                    if (bw.putBit(v > 6 ? 1 : 0, probs[p + 7]) == 0) {
                        bw.putBit(v == 6 ? 1 : 0, 159);
                    } else {
                        bw.putBit(v >= 9 ? 1 : 0, 165);
                        bw.putBit((v & 1) == 0 ? 1 : 0, 145);
                    }
                } else {
                    int mask;
                    int[] tab;
                    if (v < 3 + (8 << 1)) {
                        bw.putBit(0, probs[p + 8]);
                        bw.putBit(0, probs[p + 9]);
                        v -= 3 + 8;
                        mask = 1 << 2;
                        tab = Vp8Tables.CAT3_PROBS;
                    } else if (v < 3 + (8 << 2)) {
                        bw.putBit(0, probs[p + 8]);
                        bw.putBit(1, probs[p + 9]);
                        v -= 3 + (8 << 1);
                        mask = 1 << 3;
                        tab = Vp8Tables.CAT4_PROBS;
                    } else if (v < 3 + (8 << 3)) {
                        bw.putBit(1, probs[p + 8]);
                        bw.putBit(0, probs[p + 10]);
                        v -= 3 + (8 << 2);
                        mask = 1 << 4;
                        tab = Vp8Tables.CAT5_PROBS;
                    } else {
                        bw.putBit(1, probs[p + 8]);
                        bw.putBit(1, probs[p + 10]);
                        v -= 3 + (8 << 3);
                        mask = 1 << 10;
                        tab = Vp8Tables.CAT6_PROBS;
                    }
                    var t = 0;
                    while (mask != 0) {
                        bw.putBit((v & mask) != 0 ? 1 : 0, tab[t++]);
                        mask >>= 1;
                    }
                }
                p = index(type, Vp8Tables.BANDS[n], 2);
            }
            bw.putBitUniform(sign ? 1 : 0);
            if (n == 16 || bw.putBit(n <= res.last ? 1 : 0, probs[p]) == 0) {
                return 1;
            }
        }
        return 1;
    }
}
