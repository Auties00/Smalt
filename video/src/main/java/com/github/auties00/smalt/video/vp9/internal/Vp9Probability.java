/* Ports libvpx 1.17.0 (vp9/encoder/vp9_subexp.c, vp9_treewriter.c). Copyright (c) 2010, The WebM Project authors;
   BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.video.vp9.internal;

/**
 * Chooses and writes the forward probability updates of a VP9 compressed header.
 *
 * <p>An update replaces a probability of the frame context by one closer to the frequencies the
 * frame coded, when the bits it saves exceed the bits of signaling it. The new probability is
 * coded as a remapped difference with a terminated sub-exponential code, which the decoder
 * inverts.
 *
 * @implNote This implementation ports libvpx's {@code vp9/encoder/vp9_subexp.c} and
 * {@code vp9_tree_probs_from_distribution} of {@code vp9_treewriter.c}.
 */
final class Vp9Probability {
    /**
     * The probability with which every update flag is coded ({@code DIFF_UPDATE_PROB}).
     */
    static final int DIFF_UPDATE_PROB = 252;

    /**
     * The fewest bits an update can cost ({@code MIN_DELP_BITS}).
     */
    private static final int MIN_DELP_BITS = 5;

    /**
     * The bits coding each remapped difference takes ({@code update_bits}).
     */
    private static final byte[] UPDATE_BITS = updateBits();

    /**
     * Maps each recentered difference to the index the sub-exponential code sends
     * ({@code map_table} of {@code remap_prob}).
     */
    private static final int[] MAP_TABLE = {
            20, 21, 22, 23, 24, 25, 0, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 1, 38, 39, 40, 41, 42, 43, 44,
            45, 46, 47, 48, 49, 2, 50, 51, 52, 53, 54, 55, 56, 57, 58, 59, 60, 61, 3, 62, 63, 64, 65, 66, 67, 68, 69,
            70, 71, 72, 73, 4, 74, 75, 76, 77, 78, 79, 80, 81, 82, 83, 84, 85, 5, 86, 87, 88, 89, 90, 91, 92, 93, 94,
            95, 96, 97, 6, 98, 99, 100, 101, 102, 103, 104, 105, 106, 107, 108, 109, 7, 110, 111, 112, 113, 114, 115,
            116, 117, 118, 119, 120, 121, 8, 122, 123, 124, 125, 126, 127, 128, 129, 130, 131, 132, 133, 9, 134, 135,
            136, 137, 138, 139, 140, 141, 142, 143, 144, 145, 10, 146, 147, 148, 149, 150, 151, 152, 153, 154, 155, 156,
            157, 11, 158, 159, 160, 161, 162, 163, 164, 165, 166, 167, 168, 169, 12, 170, 171, 172, 173, 174, 175, 176,
            177, 178, 179, 180, 181, 13, 182, 183, 184, 185, 186, 187, 188, 189, 190, 191, 192, 193, 14, 194, 195, 196,
            197, 198, 199, 200, 201, 202, 203, 204, 205, 15, 206, 207, 208, 209, 210, 211, 212, 213, 214, 215, 216, 217,
            16, 218, 219, 220, 221, 222, 223, 224, 225, 226, 227, 228, 229, 17, 230, 231, 232, 233, 234, 235, 236, 237,
            238, 239, 240, 241, 18, 242, 243, 244, 245, 246, 247, 248, 249, 250, 251, 252, 253, 19
    };

    /**
     * Prevents instantiation.
     */
    private Vp9Probability() {
        throw new AssertionError();
    }

    /**
     * Builds the table of update sizes: 5 bits for the first 16 indices, 6 for the next 16, 8
     * for the next 32, 10 for the next 64 and 11 above.
     *
     * @return the sizes
     */
    private static byte[] updateBits() {
        var bits = new byte[255];
        for (var i = 0; i < 254; i++) {
            bits[i] = (byte) (i < 16 ? 5 : i < 32 ? 6 : i < 64 ? 8 : i < 128 ? 10 : 11);
        }
        return bits;
    }

    /**
     * Converts the symbol counts of a tree into the zero and one counts of each node
     * ({@code vp9_tree_probs_from_distribution}).
     *
     * @param tree the tree in libvpx layout: child pairs, a leaf {@code -s} standing for symbol {@code s}
     * @param counts the symbol counts
     * @param countBase the index of symbol 0's count
     * @param branch receives the counts of node {@code i} at {@code 2 * i} (zeros) and
     * {@code 2 * i + 1} (ones)
     */
    static void treeBranchCounts(int[] tree, int[] counts, int countBase, long[] branch) {
        convert(tree, 0, counts, countBase, branch);
    }

    /**
     * Fills the branch counts of a subtree and returns its total.
     *
     * @param tree the tree in libvpx layout: child pairs, a leaf {@code -s} standing for symbol {@code s}
     * @param node the subtree's node index
     * @param counts the symbol counts
     * @param countBase the index of symbol 0's count
     * @param branch the branch counts being filled
     * @return the number of symbols coded under the node
     */
    private static long convert(int[] tree, int node, int[] counts, int countBase, long[] branch) {
        var l = tree[node];
        var left = l <= 0 ? counts[countBase - l] : convert(tree, l, counts, countBase, branch);
        var r = tree[node + 1];
        var right = r <= 0 ? counts[countBase - r] : convert(tree, r, counts, countBase, branch);
        branch[node] = left;
        branch[node + 1] = right;
        return left + right;
    }

    /**
     * Recenters a value around a center ({@code recenter_nonneg}).
     *
     * @param v the value
     * @param m the center
     * @return the recentered value
     */
    private static int recenterNonneg(int v, int m) {
        if (v > (m << 1)) {
            return v;
        } else if (v >= m) {
            return (v - m) << 1;
        }
        return ((m - v) << 1) - 1;
    }

    /**
     * Maps a new probability to the index the update codes ({@code remap_prob}).
     *
     * @param newP the new probability, from 1 to 255
     * @param oldP the old probability, from 1 to 255
     * @return the index, from 0 to 253
     */
    private static int remapProb(int newP, int oldP) {
        var v = newP - 1;
        var m = oldP - 1;
        int i;
        if ((m << 1) <= 255) {
            i = recenterNonneg(v, m) - 1;
        } else {
            i = recenterNonneg(254 - v, 254 - m) - 1;
        }
        return MAP_TABLE[i];
    }

    /**
     * Returns the cost of coding an update ({@code prob_diff_update_cost}).
     *
     * @param newP the new probability
     * @param oldP the old probability
     * @return the cost
     */
    private static int updateCost(int newP, int oldP) {
        return UPDATE_BITS[remapProb(newP, oldP)] << Vp9EncTables.PROB_COST_SHIFT;
    }

    /**
     * Writes an update of a probability ({@code vp9_write_prob_diff_update}).
     *
     * @param w the boolean encoder
     * @param newP the new probability
     * @param oldP the old probability
     */
    static void writeUpdate(Vp9BoolEncoder w, int newP, int oldP) {
        var word = remapProb(newP, oldP);
        if (!writeGte(w, word, 16)) {
            w.writeLiteral(word, 4);
        } else if (!writeGte(w, word, 32)) {
            w.writeLiteral(word - 16, 4);
        } else if (!writeGte(w, word, 64)) {
            w.writeLiteral(word - 32, 5);
        } else {
            var v = word - 64;
            var m = (1 << 8) - 191;
            if (v < m) {
                w.writeLiteral(v, 7);
            } else {
                w.writeLiteral(m + ((v - m) >> 1), 7);
                w.writeLiteral((v - m) & 1, 1);
            }
        }
    }

    /**
     * Writes whether a value reaches a threshold.
     *
     * @param w the boolean encoder
     * @param word the value
     * @param test the threshold
     * @return whether the value reaches it
     */
    private static boolean writeGte(Vp9BoolEncoder w, int word, int test) {
        var gte = word >= test;
        w.writeLiteral(gte ? 1 : 0, 1);
        return gte;
    }

    /**
     * Searches for the probability that saves the most bits over the old one for a node's
     * counts ({@code vp9_prob_diff_update_savings_search}).
     *
     * @param n0 the number of zeros
     * @param n1 the number of ones
     * @param oldP the old probability
     * @param bestP the starting candidate, usually the probability matching the counts
     * @param result receives the chosen probability at index 0
     * @return the bits saved, positive when an update pays off
     */
    static long savingsSearch(long n0, long n1, int oldP, int bestP, int[] result) {
        var oldB = Vp9EncTables.costBranch(n0, n1, oldP);
        long bestSavings = 0;
        var bestNewP = oldP;
        var step = bestP > oldP ? -1 : 1;
        var updCost = Vp9EncTables.costOne(DIFF_UPDATE_PROB) - Vp9EncTables.costZero(DIFF_UPDATE_PROB);
        if (oldB > updCost + ((long) MIN_DELP_BITS << Vp9EncTables.PROB_COST_SHIFT)) {
            for (var newP = bestP; newP != oldP; newP += step) {
                var newB = Vp9EncTables.costBranch(n0, n1, newP);
                var updateB = updateCost(newP, oldP) + updCost;
                var savings = oldB - newB - updateB;
                if (savings > bestSavings) {
                    bestSavings = savings;
                    bestNewP = newP;
                }
            }
        }
        result[0] = bestNewP;
        return bestSavings;
    }

    /**
     * Searches for the pivot probability of the coefficient model that saves the most bits,
     * costing the nodes the Pareto model derives from it too
     * ({@code vp9_prob_diff_update_savings_search_model}).
     *
     * @param branch the branch counts of the 11 nodes of the full token tree, zeros at
     * {@code 2 * node} and ones at {@code 2 * node + 1}
     * @param oldP the old pivot probability
     * @param bestP the starting candidate
     * @param stepSize the search step
     * @param result receives the chosen probability at index 0
     * @return the bits saved, positive when an update pays off
     */
    static long savingsSearchModel(long[] branch, int oldP, int bestP, int stepSize, int[] result) {
        var stepSign = bestP > oldP ? -1 : 1;
        var step = stepSize * stepSign;
        var updCost = Vp9EncTables.costOne(DIFF_UPDATE_PROB) - Vp9EncTables.costZero(DIFF_UPDATE_PROB);
        var pareto = Vp9ProbTables.PARETO;
        var oldBase = (oldP - 1) * 8;
        var oldB = Vp9EncTables.costBranch(branch[4], branch[5], oldP);
        for (var i = 3; i < 11; i++) {
            oldB += Vp9EncTables.costBranch(branch[2 * i], branch[2 * i + 1], pareto[oldBase + i - 3]);
        }
        long bestSavings = 0;
        var bestNewP = oldP;
        if (oldB > updCost + ((long) MIN_DELP_BITS << Vp9EncTables.PROB_COST_SHIFT)) {
            for (var newP = bestP; (newP - oldP) * stepSign < 0; newP += step) {
                if (newP < 1 || newP > 255) {
                    continue;
                }
                var newBase = (newP - 1) * 8;
                var newB = Vp9EncTables.costBranch(branch[4], branch[5], newP);
                for (var i = 3; i < 11; i++) {
                    newB += Vp9EncTables.costBranch(branch[2 * i], branch[2 * i + 1], pareto[newBase + i - 3]);
                }
                var updateB = updateCost(newP, oldP) + updCost;
                var savings = oldB - newB - updateB;
                if (savings > bestSavings) {
                    bestSavings = savings;
                    bestNewP = newP;
                }
            }
        }
        result[0] = bestNewP;
        return bestSavings;
    }

    /**
     * Writes whether a probability is updated from a node's counts, and the update when it saves
     * bits ({@code vp9_cond_prob_diff_update}).
     *
     * @param w the boolean encoder
     * @param probs the probabilities, updated in place
     * @param index the index of the probability
     * @param n0 the number of zeros coded with it
     * @param n1 the number of ones
     * @param result a scratch array of at least one entry
     */
    static void condUpdate(Vp9BoolEncoder w, int[] probs, int index, long n0, long n1, int[] result) {
        var oldP = probs[index];
        var newP = Vp9EncTables.binaryProb(n0, n1);
        var savings = savingsSearch(n0, n1, oldP, newP, result);
        if (savings > 0) {
            w.write(1, DIFF_UPDATE_PROB);
            writeUpdate(w, result[0], oldP);
            probs[index] = result[0];
        } else {
            w.write(0, DIFF_UPDATE_PROB);
        }
    }

    /**
     * Writes the conditional updates of the probabilities of a tree from its symbol counts
     * ({@code prob_diff_update}).
     *
     * @param w the boolean encoder
     * @param tree the tree in libvpx layout: child pairs, a leaf {@code -s} standing for symbol {@code s}
     * @param probs the probabilities, updated in place
     * @param probBase the index of the tree's first probability
     * @param counts the symbol counts
     * @param countBase the index of symbol 0's count
     * @param symbols the number of symbols
     * @param branch a scratch array of at least {@code 2 * symbols} entries
     * @param result a scratch array of at least one entry
     */
    static void treeUpdate(Vp9BoolEncoder w, int[] tree, int[] probs, int probBase, int[] counts, int countBase,
                           int symbols, long[] branch, int[] result) {
        treeBranchCounts(tree, counts, countBase, branch);
        for (var i = 0; i < symbols - 1; i++) {
            condUpdate(w, probs, probBase + i, branch[2 * i], branch[2 * i + 1], result);
        }
    }
}
