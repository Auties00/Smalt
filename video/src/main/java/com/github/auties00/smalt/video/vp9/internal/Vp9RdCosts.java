/* Ports libvpx 1.15.1 (vp9/encoder/vp9_rd.c, vp9/encoder/vp9_rdopt.c, vp9/encoder/vp9_tokenize.c). Copyright (c) 2010,
   The WebM Project authors; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.video.vp9.internal;

/**
 * Holds the symbol costs the rate-distortion search of a frame weighs its choices with: the
 * coefficient token costs of every transform size, plane type, reference type, band and context,
 * and the costs of the intra modes, the partitions and the transform sizes, all from the
 * probabilities the frame is coded with.
 *
 * <p>Costs are in 1/512 bit, the unit of {@link Vp9EncTables#PROB_COST}. The tables are rebuilt
 * once per frame and only read while the frame's blocks are searched, so the threads coding a
 * frame share them.
 *
 * @implNote This implementation follows libvpx's {@code fill_token_costs}, {@code fill_mode_costs}
 * and the partition costs of {@code vp9_initialize_rd_consts}, and computes the extra-bit costs of
 * the category tokens from their probabilities instead of storing libvpx's precomputed
 * {@code dct_cat_lt_10_value_cost} and {@code vp9_cat6_*_cost} tables, which hold the same
 * values.
 */
final class Vp9RdCosts {
    /**
     * The number of token cost entries of one context: the 11 value tokens and the end of block.
     */
    static final int TOKENS = 12;

    /**
     * The largest magnitude {@link #valueCost} covers without the category 6 computation.
     */
    private static final int SMALL_VALUES = Vp9EncTables.CAT6_MIN_VAL;

    /**
     * The token cost of each transform size, plane type, reference type, band, end-of-block
     * availability and context, {@code [((((((tx * 2 + type) * 2 + ref) * 6 + band) * 2 + noEob)
     * * 6 + ctx) * 12 + token]}; the entries with {@code noEob} 1 leave out the branch that tells
     * the token from the end of block, as after a zero token.
     */
    final int[] token = new int[4 * 2 * 2 * 6 * 2 * 6 * TOKENS];

    /**
     * The cost of the sign and extra bits of each magnitude below {@value Vp9EncTables#CAT6_MIN_VAL}.
     */
    private static final int[] SMALL_VALUE_COSTS = smallValueCosts();

    /**
     * The token of each magnitude below {@value Vp9EncTables#CAT6_MIN_VAL}.
     */
    private static final int[] SMALL_VALUE_TOKENS = smallValueTokens();

    /**
     * The cost of each key frame luma mode, {@code [above][left][mode]}.
     */
    final int[] kfYMode = new int[10 * 10 * 10];

    /**
     * The cost of each luma mode of an intra block in an inter frame.
     */
    final int[] yMode = new int[10];

    /**
     * The cost of each chroma mode, {@code [inter frame][luma mode][chroma mode]}.
     */
    final int[] uvMode = new int[2 * 10 * 10];

    /**
     * The cost of each partition, {@code [context][partition]}.
     */
    final int[] partition = new int[16 * 4];

    /**
     * The cost of each transform size, {@code [largest size - 1][context][size]}.
     */
    final int[] txSize = new int[3 * 2 * 4];

    /**
     * The bit depth of the coefficients, which sizes the category 6 tokens.
     */
    private final int bitDepth;

    /**
     * The 11 node probabilities of one coefficient context, completed from the Pareto model.
     */
    private final int[] fullProbs = new int[11];

    /**
     * The token costs of one coefficient context.
     */
    private final int[] tokenScratch = new int[TOKENS];

    /**
     * Creates the cost tables of a bit depth.
     *
     * @param bitDepth the bit depth, 8, 10 or 12
     */
    Vp9RdCosts(int bitDepth) {
        this.bitDepth = bitDepth;
    }

    /**
     * Rebuilds every table from a frame's probabilities.
     *
     * @param probs the frame's probabilities, in the layout of {@link Vp9FrameContext}
     * @param keyFrame whether the frame is a key frame, whose partitions use the fixed key frame
     * probabilities
     */
    void build(int[] probs, boolean keyFrame) {
        var full = fullProbs;
        var costs = tokenScratch;
        for (var set = 0; set < 4 * 2 * 2; set++) {
            for (var band = 0; band < 6; band++) {
                for (var ctx = 0; ctx < (band == 0 ? 3 : 6); ctx++) {
                    var unit = Vp9FrameContext.COEF + ((set * 6 + band) * 6 + ctx) * 3;
                    full[0] = probs[unit];
                    full[1] = probs[unit + 1];
                    full[2] = probs[unit + 2];
                    var pareto = (Math.max(1, full[2]) - 1) * 8;
                    for (var i = 0; i < 8; i++) {
                        full[3 + i] = Vp9ProbTables.PARETO[pareto + i];
                    }
                    Vp9EncTables.costTokens(costs, 0, full, 0, Vp9EncTables.COEF_TREE);
                    var with = (((set * 6 + band) * 2) * 6 + ctx) * TOKENS;
                    var without = (((set * 6 + band) * 2 + 1) * 6 + ctx) * TOKENS;
                    var notEob = Vp9EncTables.costBit(full[0], 1);
                    for (var t = 0; t < TOKENS; t++) {
                        token[with + t] = costs[t];
                        token[without + t] = t == Vp9EncTables.EOB_TOKEN ? costs[t] : costs[t] - notEob;
                    }
                }
            }
        }
        for (var i = 0; i < 10; i++) {
            for (var j = 0; j < 10; j++) {
                Vp9EncTables.costTokens(kfYMode, (i * 10 + j) * 10, Vp9ProbTables.KF_Y_MODE_PROBS, (i * 10 + j) * 9,
                        Vp9Trees.INTRA_MODE);
            }
        }
        Vp9EncTables.costTokens(yMode, 0, probs, Vp9FrameContext.Y_MODE + 9, Vp9Trees.INTRA_MODE);
        for (var i = 0; i < 10; i++) {
            Vp9EncTables.costTokens(uvMode, i * 10, Vp9ProbTables.KF_UV_MODE_PROBS, i * 9, Vp9Trees.INTRA_MODE);
            Vp9EncTables.costTokens(uvMode, (10 + i) * 10, probs, Vp9FrameContext.UV_MODE + i * 9,
                    Vp9Trees.INTRA_MODE);
        }
        for (var ctx = 0; ctx < 16; ctx++) {
            if (keyFrame) {
                Vp9EncTables.costTokens(partition, ctx * 4, Vp9ProbTables.KF_PARTITION_PROBS, ctx * 3,
                        Vp9Trees.PARTITION);
            } else {
                Vp9EncTables.costTokens(partition, ctx * 4, probs, Vp9FrameContext.PARTITION + ctx * 3,
                        Vp9Trees.PARTITION);
            }
        }
        for (var max = 1; max < 4; max++) {
            var base = max == 1 ? Vp9FrameContext.TX_8X8 : max == 2 ? Vp9FrameContext.TX_16X16 : Vp9FrameContext.TX_32X32;
            for (var ctx = 0; ctx < 2; ctx++) {
                var p = base + ctx * max;
                for (var k = 0; k <= max; k++) {
                    var cost = 0;
                    for (var m = 0; m <= k - (k == max ? 1 : 0); m++) {
                        cost += m == k ? Vp9EncTables.costZero(probs[p + m]) : Vp9EncTables.costOne(probs[p + m]);
                    }
                    txSize[((max - 1) * 2 + ctx) * 4 + k] = cost;
                }
            }
        }
    }

    /**
     * Returns the index of the token costs of a context.
     *
     * @param txSize the transform size
     * @param plane the plane index, 0 for luma and 1 or 2 for chroma
     * @param inter whether the block is an inter block
     * @param band the coefficient band
     * @param noEob whether the end of block cannot follow, after a zero token
     * @param ctx the coefficient context, from 0 to 5
     * @return the index of the cost of token 0
     */
    static int tokenBase(int txSize, int plane, boolean inter, int band, boolean noEob, int ctx) {
        var set = (txSize * 2 + (plane == 0 ? 0 : 1)) * 2 + (inter ? 1 : 0);
        return ((((set * 6 + band) * 2 + (noEob ? 1 : 0)) * 6) + ctx) * TOKENS;
    }

    /**
     * Returns the token of a quantized coefficient and the cost of its sign and extra bits
     * ({@code vp9_get_token_cost}), the token in the low 4 bits and the cost above them.
     *
     * @param v the coefficient
     * @return {@code (cost << 4) | token}
     */
    int valueCost(int v) {
        var a = v < 0 ? -v : v;
        if (a < SMALL_VALUES) {
            return (SMALL_VALUE_COSTS[a] << 4) | SMALL_VALUE_TOKENS[a];
        }
        var extra = a - Vp9EncTables.CAT6_MIN_VAL;
        var bits = Vp9EncTables.cat6Bits(bitDepth);
        int[] probs;
        int start;
        if (bitDepth == 8) {
            probs = Vp9EncTables.EXTRA_PROBS;
            start = Vp9Constants.EXTRA_START[Vp9EncTables.CATEGORY6_TOKEN];
        } else {
            probs = Vp9EncTables.CAT6_PROBS_HIGH12;
            start = probs.length - bits;
        }
        var cost = 512;
        for (var i = 0; i < bits; i++) {
            cost += Vp9EncTables.costBit(probs[start + i], (extra >> (bits - 1 - i)) & 1);
        }
        return (cost << 4) | Vp9EncTables.CATEGORY6_TOKEN;
    }

    /**
     * Computes the sign and extra-bit costs of the small magnitudes.
     *
     * @return the costs, zero for magnitude 0, which has no sign
     */
    private static int[] smallValueCosts() {
        var costs = new int[SMALL_VALUES];
        for (var a = 1; a < SMALL_VALUES; a++) {
            var t = Vp9EncTables.token(a);
            var cost = 512;
            if (t >= Vp9EncTables.CATEGORY1_TOKEN) {
                var extra = a - Vp9Constants.TOKEN_BASE[t];
                var n = Vp9EncTables.EXTRA_BITS[t];
                var pb = Vp9Constants.EXTRA_START[t];
                for (var i = 0; i < n; i++) {
                    cost += Vp9EncTables.costBit(Vp9EncTables.EXTRA_PROBS[pb + i], (extra >> (n - 1 - i)) & 1);
                }
            }
            costs[a] = cost;
        }
        return costs;
    }

    /**
     * Lists the tokens of the small magnitudes.
     *
     * @return the tokens
     */
    private static int[] smallValueTokens() {
        var tokens = new int[SMALL_VALUES];
        for (var a = 0; a < SMALL_VALUES; a++) {
            tokens[a] = Vp9EncTables.token(a);
        }
        return tokens;
    }
}
