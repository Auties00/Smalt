/* Ports libvpx 1.15.1 (vp8/encoder/rdopt.c: fill_token_costs, cost_coeffs; vp8/encoder/tokenize.c:
   fill_value_tokens). Copyright (c) 2010 The WebM project authors; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.video.vp8.internal;

/**
 * Holds the costs of coding the coefficient tokens of a VP8 frame, from the token probabilities
 * the rate-distortion mode decision assumes, and computes the cost of a block's levels.
 *
 * <p>The table holds the cost of each token in each block type, band and context; the cost of a
 * level also counts its extra bits and its sign. Instances are filled once per frame and then
 * read by every thread.
 */
final class RdCosts {
    /**
     * The number of tokens: {@code ZERO} to {@code FOUR}, the six categories, and the end of
     * block.
     */
    private static final int TOKENS = 12;

    /**
     * The end-of-block token.
     */
    static final int EOB = 11;

    /**
     * The largest magnitude of a level.
     */
    private static final int MAX_VALUE = 2048;

    /**
     * The token of each level, by level plus {@link #MAX_VALUE}.
     */
    private static final byte[] VALUE_TOKEN = new byte[2 * MAX_VALUE];

    /**
     * The cost of the extra bits and the sign of each level, by level plus {@link #MAX_VALUE},
     * in 1/256 bit.
     */
    private static final int[] VALUE_COST = new int[2 * MAX_VALUE];

    /**
     * The context the token of each level leaves for the next position: 0 after {@code ZERO}, 1
     * after {@code ONE}, 2 after every larger token.
     */
    static final int[] PREV_CLASS = {0, 1, 2, 2, 2, 2, 2, 2, 2, 2, 2, 0};

    /**
     * The smallest level of each token's range.
     */
    private static final int[] BASE = {0, 1, 2, 3, 4, 5, 7, 11, 19, 35, 67};

    /**
     * The probabilities of the extra bits of each category, most significant first.
     */
    private static final int[][] CATEGORY_PROBS = {
            {159}, {165, 145}, {173, 148, 140}, {176, 155, 140, 135}, {180, 157, 141, 134, 130},
            {254, 254, 243, 230, 196, 177, 153, 140, 133, 130, 129}
    };

    static {
        for (var v = -MAX_VALUE; v < MAX_VALUE; v++) {
            var a = Math.abs(v);
            int token;
            if (a > 4) {
                token = 5;
                while (token < 10 && BASE[token + 1] <= a) {
                    token++;
                }
            } else {
                token = a;
            }
            VALUE_TOKEN[v + MAX_VALUE] = (byte) token;
            var cost = 0;
            if (token >= 5) {
                var probs = CATEGORY_PROBS[token - 5];
                var extra = a - BASE[token];
                for (var k = 0; k < probs.length; k++) {
                    cost += RtTables.bitCost(probs[k], (extra >> (probs.length - 1 - k)) & 1);
                }
            }
            if (a != 0) {
                cost += RtTables.bitCost(128, v < 0 ? 1 : 0);
            }
            VALUE_COST[v + MAX_VALUE] = cost;
        }
    }

    /**
     * The cost of each token by block type, band and context, in 1/256 bit,
     * {@code [type 4][band 8][context 3][token 12]}.
     */
    private final int[] costs = new int[4 * 8 * 3 * TOKENS];

    /**
     * Fills the table from token probabilities ({@code fill_token_costs}): a context of zero
     * past the first band cannot follow with an end of block, so its tokens do not cost the end
     * of block branch.
     *
     * @param probs the token probabilities, {@code [type 4][band 8][context 3][node 11]}
     */
    void build(int[] probs) {
        for (var type = 0; type < 4; type++) {
            for (var band = 0; band < 8; band++) {
                for (var ctx = 0; ctx < 3; ctx++) {
                    var p = ((type * 8 + band) * 3 + ctx) * 11;
                    var c = ((type * 8 + band) * 3 + ctx) * TOKENS;
                    var skipEob = ctx == 0 && band > (type == 0 ? 1 : 0);
                    var head = skipEob ? 0 : RtTables.bitCost(probs[p], 1);
                    costs[c + EOB] = RtTables.bitCost(probs[p], 0);
                    costs[c] = head + RtTables.bitCost(probs[p + 1], 0);
                    var notZero = head + RtTables.bitCost(probs[p + 1], 1);
                    costs[c + 1] = notZero + RtTables.bitCost(probs[p + 2], 0);
                    var big = notZero + RtTables.bitCost(probs[p + 2], 1);
                    var small = big + RtTables.bitCost(probs[p + 3], 0);
                    costs[c + 2] = small + RtTables.bitCost(probs[p + 4], 0);
                    var threeFour = small + RtTables.bitCost(probs[p + 4], 1);
                    costs[c + 3] = threeFour + RtTables.bitCost(probs[p + 5], 0);
                    costs[c + 4] = threeFour + RtTables.bitCost(probs[p + 5], 1);
                    var category = big + RtTables.bitCost(probs[p + 3], 1);
                    var low = category + RtTables.bitCost(probs[p + 6], 0);
                    costs[c + 5] = low + RtTables.bitCost(probs[p + 7], 0);
                    costs[c + 6] = low + RtTables.bitCost(probs[p + 7], 1);
                    var high = category + RtTables.bitCost(probs[p + 6], 1);
                    var mid = high + RtTables.bitCost(probs[p + 8], 0);
                    costs[c + 7] = mid + RtTables.bitCost(probs[p + 9], 0);
                    costs[c + 8] = mid + RtTables.bitCost(probs[p + 9], 1);
                    var top = high + RtTables.bitCost(probs[p + 8], 1);
                    costs[c + 9] = top + RtTables.bitCost(probs[p + 10], 0);
                    costs[c + 10] = top + RtTables.bitCost(probs[p + 10], 1);
                }
            }
        }
    }

    /**
     * Returns the cost of coding a block's levels ({@code cost_coeffs}).
     *
     * @param levels the levels in zigzag order
     * @param off the index of the level of zigzag position 0
     * @param stride the distance between consecutive zigzag positions
     * @param first the first coded position: 1 for luma blocks with a Y2 block, 0 otherwise
     * @param eob one past the last nonzero position, or a value not above {@code first} for a block
     * without levels
     * @param type the block type
     * @param ctx the context from the neighboring blocks, from 0 to 2
     * @return the cost in 1/256 bit
     */
    int blockCost(short[] levels, int off, int stride, int first, int eob, int type, int ctx) {
        var cost = 0;
        var pt = ctx;
        var c = first;
        var typeBase = type * 8;
        for (; c < eob; c++) {
            var v = Math.clamp(levels[off + c * stride], -MAX_VALUE, MAX_VALUE - 1) + MAX_VALUE;
            var t = VALUE_TOKEN[v];
            cost += costs[((typeBase + Vp8Tables.BANDS[c]) * 3 + pt) * TOKENS + t] + VALUE_COST[v];
            pt = PREV_CLASS[t];
        }
        if (c < 16) {
            cost += costs[((typeBase + Vp8Tables.BANDS[c]) * 3 + pt) * TOKENS + EOB];
        }
        return cost;
    }

    /**
     * Returns the cost of one token of a block type, band and context.
     *
     * @param type the block type
     * @param band the coefficient band
     * @param ctx the context, from 0 to 2
     * @param token the token, from {@code ZERO} to the end of block
     * @return the cost in 1/256 bit
     */
    int tokenCost(int type, int band, int ctx, int token) {
        return costs[((type * 8 + band) * 3 + ctx) * TOKENS + token];
    }

    /**
     * Returns the token of a level.
     *
     * @param level the signed quantized coefficient
     * @return the token, from {@code ZERO} to {@code DCT_VAL_CATEGORY6}
     */
    static int valueToken(int level) {
        return VALUE_TOKEN[Math.clamp(level, -MAX_VALUE, MAX_VALUE - 1) + MAX_VALUE];
    }

    /**
     * Returns the cost of the extra bits and the sign of a level.
     *
     * @param level the signed quantized coefficient
     * @return the cost in 1/256 bit
     */
    static int valueCost(int level) {
        return VALUE_COST[Math.clamp(level, -MAX_VALUE, MAX_VALUE - 1) + MAX_VALUE];
    }
}
