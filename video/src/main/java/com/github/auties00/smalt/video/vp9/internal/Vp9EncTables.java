/* Ports libvpx 1.17.0 (vp9/encoder/vp9_cost.c, vp9_tokenize.c, vp9_rd.c, vp9_quantize.c, vp9/common/vp9_entropy.c).
   Copyright (c) 2010, The WebM Project authors; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.video.vp9.internal;

/**
 * Holds the constant tables of the VP9 encoder: bit costs of probabilities, the token alphabet
 * of coefficients, the rate and distortion model of the mode decision, and the quantizer scale
 * of the configuration.
 *
 * @implNote This implementation copies the tables from libvpx ({@code vp9/encoder/vp9_cost.c},
 * {@code vp9_tokenize.c}, {@code vp9_rd.c}, {@code vp9_quantize.c} and
 * {@code vp9/common/vp9_entropy.c}).
 */
final class Vp9EncTables {
    /**
     * Prevents instantiation.
     */
    private Vp9EncTables() {
        throw new AssertionError();
    }

    /**
     * The scale of bit costs: a cost of {@code 1 << PROB_COST_SHIFT} is one bit
     * ({@code VP9_PROB_COST_SHIFT}).
     */
    static final int PROB_COST_SHIFT = 9;

    /**
     * The cost of coding a zero with each probability, {@code round(-log2(p / 256) * 512)},
     * with entry 0 repeating entry 1 ({@code vp9_prob_cost}).
     */
    static final int[] PROB_COST = {
            4096, 4096, 3584, 3284, 3072, 2907, 2772, 2659, 2560, 2473, 2395, 2325, 2260, 2201, 2147, 2096, 2048, 2003,
            1961, 1921, 1883, 1847, 1813, 1780, 1748, 1718, 1689, 1661, 1635, 1609, 1584, 1559, 1536, 1513, 1491, 1470,
            1449, 1429, 1409, 1390, 1371, 1353, 1335, 1318, 1301, 1284, 1268, 1252, 1236, 1221, 1206, 1192, 1177, 1163,
            1149, 1136, 1123, 1110, 1097, 1084, 1072, 1059, 1047, 1036, 1024, 1013, 1001, 990, 979, 968, 958, 947, 937,
            927, 917, 907, 897, 887, 878, 868, 859, 850, 841, 832, 823, 814, 806, 797, 789, 780, 772, 764, 756, 748,
            740, 732, 724, 717, 709, 702, 694, 687, 680, 673, 665, 658, 651, 644, 637, 631, 624, 617, 611, 604, 598,
            591, 585, 578, 572, 566, 560, 554, 547, 541, 535, 530, 524, 518, 512, 506, 501, 495, 489, 484, 478, 473,
            467, 462, 456, 451, 446, 441, 435, 430, 425, 420, 415, 410, 405, 400, 395, 390, 385, 380, 375, 371, 366,
            361, 356, 352, 347, 343, 338, 333, 329, 324, 320, 316, 311, 307, 302, 298, 294, 289, 285, 281, 277, 273,
            268, 264, 260, 256, 252, 248, 244, 240, 236, 232, 228, 224, 220, 216, 212, 209, 205, 201, 197, 194, 190,
            186, 182, 179, 175, 171, 168, 164, 161, 157, 153, 150, 146, 143, 139, 136, 132, 129, 125, 122, 119, 115,
            112, 109, 105, 102, 99, 95, 92, 89, 86, 82, 79, 76, 73, 70, 66, 63, 60, 57, 54, 51, 48, 45, 42, 38, 35,
            32, 29, 26, 23, 20, 18, 15, 12, 9, 6, 3
    };

    /**
     * Returns the cost of coding a zero with a probability ({@code vp9_cost_zero}).
     *
     * @param prob the probability of a zero, from 1 to 255
     * @return the cost
     */
    static int costZero(int prob) {
        return PROB_COST[prob];
    }

    /**
     * Returns the cost of coding a one with a probability ({@code vp9_cost_one}).
     *
     * @param prob the probability of a zero, from 1 to 255
     * @return the cost
     */
    static int costOne(int prob) {
        return PROB_COST[256 - prob];
    }

    /**
     * Returns the cost of coding a bit with a probability ({@code vp9_cost_bit}).
     *
     * @param prob the probability of a zero, from 1 to 255
     * @param bit the coded bit, 0 or 1
     * @return the cost
     */
    static int costBit(int prob, int bit) {
        return PROB_COST[bit != 0 ? 256 - prob : prob];
    }

    /**
     * Returns the cost of coding a branch's counts with a probability ({@code cost_branch256}).
     *
     * @param zeros the number of zeros
     * @param ones the number of ones
     * @param prob the probability of a zero
     * @return the cost
     */
    static long costBranch(long zeros, long ones, int prob) {
        return zeros * PROB_COST[prob] + ones * PROB_COST[256 - prob];
    }

    /**
     * Returns the probability of a zero matching branch counts ({@code get_binary_prob}).
     *
     * @param n0 the number of zeros
     * @param n1 the number of ones
     * @return the probability, from 1 to 255, or 128 when both counts are zero
     */
    static int binaryProb(long n0, long n1) {
        return Vp9Adaptation.probability(n0, n0 + n1);
    }

    /**
     * Computes the path to every symbol of a tree ({@code vp9_tokens_from_tree}).
     *
     * @param tree the tree, in libvpx's layout
     * @param symbols the number of symbols
     * @return each symbol's path bits shifted left by 5, plus the path length
     */
    static int[] treeTokens(int[] tree, int symbols) {
        var tokens = new int[symbols];
        fill(tokens, tree, 0, 0, 0);
        return tokens;
    }

    /**
     * Records the paths of a subtree.
     *
     * @param tokens the paths being built
     * @param tree the tree in libvpx layout: child pairs, a leaf {@code -s} standing for symbol {@code s}
     * @param node the subtree's node index
     * @param value the path to the node
     * @param length the length of that path
     */
    private static void fill(int[] tokens, int[] tree, int node, int value, int length) {
        value += value;
        length++;
        for (var bit = 0; bit < 2; bit++) {
            var next = tree[node + bit];
            if (next <= 0) {
                tokens[-next] = ((value + bit) << 5) | length;
            } else {
                fill(tokens, tree, next, value + bit, length);
            }
        }
    }

    /**
     * Computes the cost of every symbol of a tree with given node probabilities
     * ({@code vp9_cost_tokens}).
     *
     * @param costs receives the cost of each symbol
     * @param costOff the index of symbol 0's cost
     * @param probs the array holding the node probabilities
     * @param probBase the index of the root node's probability
     * @param tree the tree in libvpx layout: child pairs, a leaf {@code -s} standing for symbol {@code s}
     */
    static void costTokens(int[] costs, int costOff, int[] probs, int probBase, int[] tree) {
        costSubtree(costs, costOff, probs, probBase, tree, 0, 0);
    }

    /**
     * Computes the costs of the symbols of a subtree.
     *
     * @param costs receives the cost of each symbol
     * @param costOff the index of symbol 0's cost
     * @param probs the node probabilities
     * @param probBase the index of the root node's probability
     * @param tree the tree in libvpx layout: child pairs, a leaf {@code -s} standing for symbol {@code s}
     * @param node the subtree's node index
     * @param cost the cost of reaching the node
     */
    private static void costSubtree(int[] costs, int costOff, int[] probs, int probBase, int[] tree, int node,
                                    int cost) {
        var prob = probs[probBase + (node >> 1)];
        for (var bit = 0; bit < 2; bit++) {
            var cc = cost + costBit(prob, bit);
            var next = tree[node + bit];
            if (next <= 0) {
                costs[costOff - next] = cc;
            } else {
                costSubtree(costs, costOff, probs, probBase, tree, next, cc);
            }
        }
    }

    /**
     * The token of a zero coefficient.
     */
    static final int ZERO_TOKEN = 0;

    /**
     * The token of a coefficient of magnitude one.
     */
    static final int ONE_TOKEN = 1;

    /**
     * The token of a coefficient of magnitude two.
     */
    static final int TWO_TOKEN = 2;

    /**
     * The token of coefficients from 5 to 6.
     */
    static final int CATEGORY1_TOKEN = 5;

    /**
     * The token of coefficients of magnitude 67 and above.
     */
    static final int CATEGORY6_TOKEN = 10;

    /**
     * The token ending the coefficients of a block.
     */
    static final int EOB_TOKEN = 11;

    /**
     * The marker the encoder places after the tokens of each block; never coded.
     */
    static final int EOSB_TOKEN = 15;

    /**
     * The smallest magnitude of the category 6 token.
     */
    static final int CAT6_MIN_VAL = 67;

    /**
     * The full coefficient token tree ({@code vp9_coef_tree}).
     */
    static final int[] COEF_TREE = {
            -EOB_TOKEN, 2, -ZERO_TOKEN, 4, -ONE_TOKEN, 6, 8, 12, -TWO_TOKEN, 10, -3, -4, 14, 16, -5, -6, 18, 20, -7, -8,
            -9, -10
    };

    /**
     * The path of each token in {@link #COEF_TREE}, the bits shifted left by 5 plus the length
     * ({@code vp9_coef_encodings}).
     */
    static final int[] COEF_ENCODINGS = {
            (2 << 5) | 2, (6 << 5) | 3, (28 << 5) | 5, (58 << 5) | 6, (59 << 5) | 6, (60 << 5) | 6, (61 << 5) | 6,
            (124 << 5) | 7, (125 << 5) | 7, (126 << 5) | 7, (127 << 5) | 7, 1
    };

    /**
     * The subtree of the tokens from {@code TWO_TOKEN} up, coded with the Pareto model
     * ({@code vp9_coef_con_tree}).
     */
    static final int[] COEF_CON_TREE = {2, 6, -2, 4, -3, -4, 8, 10, -5, -6, 12, 14, -7, -8, -9, -10};


    /**
     * The number of extra bits of each token, excluding the sign.
     */
    static final int[] EXTRA_BITS = {0, 0, 0, 0, 0, 1, 2, 3, 4, 5, 14, 0};

    /**
     * The probabilities of the extra bits of the category tokens of 8-bit video, most significant
     * bit first: one bit for category 1, then 2, 3, 4, 5 and 14 bits ({@code vp9_cat1_prob} to
     * {@code vp9_cat6_prob}), indexed by {@link Vp9Constants#EXTRA_START}. The decoder's table
     * also holds the 4 leading category 6 bits of 12-bit video.
     */
    static final int[] EXTRA_PROBS = {
            159,
            165, 145,
            173, 148, 140,
            176, 155, 140, 135,
            180, 157, 141, 134, 130,
            254, 254, 254, 252, 249, 243, 230, 196, 177, 153, 140, 133, 130, 129
    };


    /**
     * The probabilities of the 18 extra bits of a 12-bit category 6 token, most significant first;
     * 10-bit tokens use the last 16 ({@code vp9_cat6_prob_high12}).
     */
    static final int[] CAT6_PROBS_HIGH12 = {
            255, 255, 255, 255, 254, 254, 254, 252, 249, 243, 230, 196, 177, 153, 140, 133, 130, 129
    };

    /**
     * Returns the number of extra bits of a category 6 token.
     *
     * @param bitDepth the bit depth, 8, 10 or 12
     * @return 14, 16 or 18
     */
    static int cat6Bits(int bitDepth) {
        return bitDepth == 12 ? 18 : bitDepth == 10 ? 16 : 14;
    }

    /**
     * Returns the token of a quantized coefficient ({@code vp9_get_token}).
     *
     * @param v the coefficient
     * @return the token
     */
    static int token(int v) {
        var a = v < 0 ? -v : v;
        if (a >= CAT6_MIN_VAL) {
            return CATEGORY6_TOKEN;
        }
        if (a < 5) {
            return a;
        }
        if (a < 7) {
            return 5;
        }
        if (a < 11) {
            return 6;
        }
        if (a < 19) {
            return 7;
        }
        if (a < 35) {
            return 8;
        }
        return 9;
    }

    /**
     * Returns the extra value coded with a token: the offset of the magnitude from the token's
     * base shifted left by one, plus the sign ({@code vp9_get_token_extra}).
     *
     * @param v the coefficient
     * @param token the coefficient's token
     * @return the extra value
     */
    static int tokenExtra(int v, int token) {
        var sign = v < 0 ? 1 : 0;
        var a = v < 0 ? -v : v;
        return ((a - Vp9Constants.TOKEN_BASE[token]) << 1) | sign;
    }

    /**
     * The normalized rate of a Laplacian source quantized with a uniform quantizer, in 1/1024
     * bits per sample, sampled at the points of {@link #XSQ_IQ_Q10} ({@code rate_tab_q10}).
     */
    private static final int[] RATE_TAB_Q10 = {
            65536, 6086, 5574, 5275, 5063, 4899, 4764, 4651, 4553, 4389, 4255, 4142, 4044, 3958, 3881, 3811, 3748,
            3635, 3538, 3453, 3376, 3307, 3244, 3186, 3133, 3037, 2952, 2877, 2809, 2747, 2690, 2638, 2589, 2501, 2423,
            2353, 2290, 2232, 2179, 2130, 2084, 2001, 1928, 1862, 1802, 1748, 1698, 1651, 1608, 1530, 1460, 1398, 1342,
            1290, 1243, 1199, 1159, 1086, 1021, 963, 911, 864, 821, 781, 745, 680, 623, 574, 530, 490, 455, 424, 395,
            345, 304, 269, 239, 213, 190, 171, 154, 126, 104, 87, 73, 61, 52, 44, 38, 28, 21, 16, 12, 10, 8, 6, 5, 3,
            2, 1, 1, 1, 0, 0
    };

    /**
     * The normalized distortion of a Laplacian source quantized with a uniform quantizer, in
     * 1/1024 of its variance, sampled at the points of {@link #XSQ_IQ_Q10}
     * ({@code dist_tab_q10}).
     */
    private static final int[] DIST_TAB_Q10 = {
            0, 0, 1, 1, 1, 2, 2, 2, 3, 3, 4, 5, 5, 6, 7, 7, 8, 9, 11, 12, 13, 15, 16, 17, 18, 21, 24, 26, 29, 31, 34,
            36, 39, 44, 49, 54, 59, 64, 69, 73, 78, 88, 97, 106, 115, 124, 133, 142, 151, 167, 184, 200, 215, 231, 245,
            260, 274, 301, 327, 351, 375, 397, 418, 439, 458, 495, 528, 559, 587, 613, 637, 659, 680, 717, 749, 777,
            801, 823, 842, 859, 874, 899, 919, 936, 949, 960, 969, 977, 983, 994, 1001, 1006, 1010, 1013, 1015, 1017,
            1018, 1020, 1022, 1022, 1023, 1023, 1023, 1024
    };

    /**
     * The sample points of the model tables: squared quantizer step over variance, in 1/1024
     * ({@code xsq_iq_q10}).
     */
    private static final int[] XSQ_IQ_Q10 = {
            0, 4, 8, 12, 16, 20, 24, 28, 32, 40, 48, 56, 64, 72, 80, 88, 96, 112, 128, 144, 160, 176, 192, 208, 224,
            256, 288, 320, 352, 384, 416, 448, 480, 544, 608, 672, 736, 800, 864, 928, 992, 1120, 1248, 1376, 1504, 1632,
            1760, 1888, 2016, 2272, 2528, 2784, 3040, 3296, 3552, 3808, 4064, 4576, 5088, 5600, 6112, 6624, 7136, 7648,
            8160, 9184, 10208, 11232, 12256, 13280, 14304, 15328, 16352, 18400, 20448, 22496, 24544, 26592, 28640,
            30688, 32736, 36832, 40928, 45024, 49120, 53216, 57312, 61408, 65504, 73696, 81888, 90080, 98272, 106464,
            114656, 122848, 131040, 147424, 163808, 180192, 196576, 212960, 229344, 245728
    };

    /**
     * The largest squared step over variance of the model ({@code MAX_XSQ_Q10}).
     */
    private static final int MAX_XSQ_Q10 = 245727;

    /**
     * Models the rate and distortion of coding a block of a given variance with a quantizer step
     * ({@code vp9_model_rd_from_var_lapndz}).
     *
     * @param var the variance of the block's residual, the sum of squares about the mean
     * @param nLog2 the log2 of the number of samples
     * @param qstep the quantizer step
     * @return the rate in cost units in the high 32 bits and the distortion in the low 32 bits;
     * zero when the variance is zero
     */
    static long modelRdFromVar(long var, int nLog2, int qstep) {
        if (var == 0) {
            return 0;
        }
        var xsq64 = (((long) qstep * qstep << (nLog2 + 10)) + (var >> 1)) / var;
        var xsq = (int) Math.min(xsq64, MAX_XSQ_Q10);
        var tmp = (xsq >> 2) + 8;
        var k = (31 - Integer.numberOfLeadingZeros(tmp)) - 3;
        var xq = (k << 3) + ((tmp >> k) & 7);
        var a = ((xsq - XSQ_IQ_Q10[xq]) << 10) >> (2 + k);
        var b = 1024 - a;
        var r = (RATE_TAB_Q10[xq] * b + RATE_TAB_Q10[xq + 1] * a) >> 10;
        var d = (DIST_TAB_Q10[xq] * b + DIST_TAB_Q10[xq + 1] * a) >> 10;
        var rate = ((r << nLog2) + (1 << (10 - PROB_COST_SHIFT - 1))) >> (10 - PROB_COST_SHIFT);
        var dist = (var * d + 512) >> 10;
        return ((long) rate << 32) | (dist & 0xFFFFFFFFL);
    }

    /**
     * Converts the 0 to 63 quantizer scale of the configuration to a quantizer index
     * ({@code quantizer_to_qindex}).
     */
    private static final int[] QUANTIZER_TO_QINDEX = {
            0, 4, 8, 12, 16, 20, 24, 28, 32, 36, 40, 44, 48, 52, 56, 60, 64, 68, 72, 76, 80, 84, 88, 92, 96, 100, 104,
            108, 112, 116, 120, 124, 128, 132, 136, 140, 144, 148, 152, 156, 160, 164, 168, 172, 176, 180, 184, 188,
            192, 196, 200, 204, 208, 212, 216, 220, 224, 228, 232, 236, 240, 244, 249, 255
    };

    /**
     * Returns the quantizer index of a configuration quantizer ({@code vp9_quantizer_to_qindex}).
     *
     * @param quantizer the quantizer, from 0 to 63
     * @return the quantizer index, from 0 to 255
     */
    static int quantizerToQindex(int quantizer) {
        return QUANTIZER_TO_QINDEX[quantizer];
    }

    /**
     * Returns the luma and chroma DC quantizer step of a quantizer index for 8-bit content
     * ({@code vp9_dc_quant}).
     *
     * @param qindex the quantizer index
     * @param delta the delta added to the index
     * @return the step
     */
    static int dcQuant(int qindex, int delta) {
        return Vp9QuantTables.DC_QLOOKUP[Math.clamp(qindex + delta, 0, 255)];
    }

    /**
     * Returns the luma and chroma DC quantizer step of a quantizer index for a bit depth
     * ({@code vp9_dc_quant}).
     *
     * @param qindex the quantizer index
     * @param delta the delta added to the index
     * @param bitDepth the bit depth, 8, 10 or 12
     * @return the step
     */
    static int dcQuant(int qindex, int delta, int bitDepth) {
        var q = Math.clamp(qindex + delta, 0, 255);
        return switch (bitDepth) {
            case 10 -> Vp9QuantTables.DC_QLOOKUP_10[q];
            case 12 -> Vp9QuantTables.DC_QLOOKUP_12[q];
            default -> Vp9QuantTables.DC_QLOOKUP[q];
        };
    }

    /**
     * Returns the AC quantizer step of a quantizer index for a bit depth ({@code vp9_ac_quant}).
     *
     * @param qindex the quantizer index
     * @param delta the delta added to the index
     * @param bitDepth the bit depth, 8, 10 or 12
     * @return the step
     */
    static int acQuant(int qindex, int delta, int bitDepth) {
        var q = Math.clamp(qindex + delta, 0, 255);
        return switch (bitDepth) {
            case 10 -> Vp9QuantTables.AC_QLOOKUP_10[q];
            case 12 -> Vp9QuantTables.AC_QLOOKUP_12[q];
            default -> Vp9QuantTables.AC_QLOOKUP[q];
        };
    }

    /**
     * Returns the AC quantizer step of a quantizer index for 8-bit content ({@code vp9_ac_quant}).
     *
     * @param qindex the quantizer index
     * @param delta the delta added to the index
     * @return the step
     */
    static int acQuant(int qindex, int delta) {
        return Vp9QuantTables.AC_QLOOKUP[Math.clamp(qindex + delta, 0, 255)];
    }

    /**
     * Returns the real quantizer of a quantizer index, its AC step over 4
     * ({@code vp9_convert_qindex_to_q}).
     *
     * @param qindex the quantizer index
     * @return the quantizer
     */
    static double qindexToQ(int qindex) {
        return Vp9QuantTables.AC_QLOOKUP[qindex] / 4.0;
    }
}
