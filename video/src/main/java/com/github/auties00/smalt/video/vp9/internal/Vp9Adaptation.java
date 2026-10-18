/* Ports libvpx (vp9/common/vp9_entropy.c, vp9_entropymode.c, vp9_entropymv.c, vpx_dsp/prob.h, prob.c). Copyright
   (c) 2010, The WebM Project authors; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.video.vp9.internal;

/**
 * Adapts the VP9 probabilities to the symbol counts of a decoded frame (backward adaptation).
 *
 * <p>Each adapted probability blends the probability the frame started from (in the saved
 * context) with the probability its counts imply, weighted by how many symbols were counted.
 *
 * @implNote This implementation follows libvpx's {@code vp9_adapt_coef_probs}
 * ({@code vp9/common/vp9_entropy.c}), {@code vp9_adapt_mode_probs}
 * ({@code vp9_entropymode.c}), {@code vp9_adapt_mv_probs} ({@code vp9_entropymv.c})
 * and the merging helpers of {@code vpx_dsp/prob.h} and {@code prob.c}.
 */
final class Vp9Adaptation {
    /**
     * The count at which a coefficient probability adapts fully.
     */
    private static final int COEF_COUNT_SAT = 24;

    /**
     * The update factor of coefficient probabilities in frames that follow an inter frame.
     */
    private static final int COEF_MAX_UPDATE_FACTOR = 112;

    /**
     * The update factor of coefficient probabilities in intra frames.
     */
    private static final int COEF_MAX_UPDATE_FACTOR_KEY = 112;

    /**
     * The update factor of coefficient probabilities in the frame that follows a key frame.
     */
    private static final int COEF_MAX_UPDATE_FACTOR_AFTER_KEY = 128;

    /**
     * The count at which a mode or motion vector probability adapts fully.
     */
    private static final int MODE_MV_COUNT_SAT = 20;

    /**
     * Maps a saturated mode or motion vector count to its update factor, {@code 128 * count / 20}
     * ({@code prob.h} {@code count_to_update_factor}).
     */
    private static final int[] COUNT_TO_UPDATE_FACTOR = {
            0, 6, 12, 19, 25, 32, 38, 44, 51, 57, 64, 70, 76, 83, 89, 96, 102, 108, 115, 121, 128
    };

    /**
     * Prevents instantiation.
     */
    private Vp9Adaptation() {
        throw new AssertionError();
    }

    /**
     * Returns the probability of a zero given its count and the total count ({@code get_prob}).
     *
     * @param zeros the count of zeros, at most {@code total}
     * @param total the total count, not negative
     * @return the probability, from 1 to 255, or 128 when {@code total} is zero
     */
    static int probability(long zeros, long total) {
        if (total == 0) {
            return 128;
        }
        var p = (int) ((zeros * 256 + (total >> 1)) / total);
        return Math.clamp(p, 1, 255);
    }

    /**
     * Returns the weighted average of two probabilities ({@code weighted_prob}).
     *
     * @param previous the previous probability
     * @param counted the probability the counts imply
     * @param factor the weight of {@code counted}, out of 256
     * @return the blended probability
     */
    private static int weighted(int previous, int counted, int factor) {
        return (previous * (256 - factor) + counted * factor + 128) >> 8;
    }

    /**
     * Adapts a coefficient probability ({@code merge_probs}).
     *
     * @param previous the previous probability
     * @param zeros the count of zero branches
     * @param ones the count of one branches
     * @param countSat the count at which the probability adapts fully
     * @param maxFactor the update factor at saturation
     * @return the adapted probability
     */
    private static int mergeCoef(int previous, int zeros, int ones, int countSat, int maxFactor) {
        var total = zeros + ones;
        var counted = probability(zeros & 0xFFFFFFFFL, total & 0xFFFFFFFFL);
        var count = Math.min(total, countSat);
        var factor = maxFactor * count / countSat;
        return weighted(previous, counted, factor);
    }

    /**
     * Adapts a mode or motion vector probability ({@code mode_mv_merge_probs}).
     *
     * @param previous the previous probability
     * @param zeros the count of zero branches
     * @param ones the count of one branches
     * @return the adapted probability
     */
    static int mergeModeMv(int previous, int zeros, int ones) {
        var total = zeros + ones;
        if (total == 0) {
            return previous;
        }
        var factor = COUNT_TO_UPDATE_FACTOR[Math.min(total, MODE_MV_COUNT_SAT)];
        return weighted(previous, probability(zeros & 0xFFFFFFFFL, total & 0xFFFFFFFFL), factor);
    }

    /**
     * Adapts the node probabilities of a tree from its symbol counts ({@code vpx_tree_merge_probs}).
     *
     * @param tree the tree in libvpx layout: child pairs, a leaf {@code -s} standing for symbol {@code s}
     * @param previous the previous probabilities
     * @param counts the symbol counts
     * @param countsBase the index of the count of symbol 0
     * @param probs the probabilities to write
     * @param probsBase the index of the first node probability, in both probability arrays
     */
    static void mergeTree(int[] tree, int[] previous, int[] counts, int countsBase, int[] probs, int probsBase) {
        mergeNode(0, tree, previous, counts, countsBase, probs, probsBase);
    }

    /**
     * Adapts the probability of one tree node and of the nodes below it.
     *
     * @param node the index of the node
     * @param tree the tree in libvpx layout: child pairs, a leaf {@code -s} standing for symbol {@code s}
     * @param previous the previous probabilities
     * @param counts the symbol counts
     * @param countsBase the index of the count of symbol 0
     * @param probs the probabilities to write
     * @param probsBase the index of the first node probability
     * @return the total count of the symbols below the node
     */
    private static int mergeNode(int node, int[] tree, int[] previous, int[] counts, int countsBase, int[] probs,
                                 int probsBase) {
        var left = tree[node];
        var leftCount = left <= 0 ? counts[countsBase - left]
                : mergeNode(left, tree, previous, counts, countsBase, probs, probsBase);
        var right = tree[node + 1];
        var rightCount = right <= 0 ? counts[countsBase - right]
                : mergeNode(right, tree, previous, counts, countsBase, probs, probsBase);
        var index = probsBase + (node >> 1);
        probs[index] = mergeModeMv(previous[index], leftCount, rightCount);
        return leftCount + rightCount;
    }

    /**
     * Adapts the coefficient probabilities of a frame.
     *
     * @param previous the probabilities the frame started from
     * @param context the probabilities to adapt, holding the frame's
     * @param counts the frame's counts
     * @param intraOnly whether the frame is a key frame or an intra-only frame
     * @param afterKeyFrame whether the previous frame was a key frame
     */
    static void adaptCoefProbs(Vp9FrameContext previous, Vp9FrameContext context, Vp9FrameCounts counts,
                               boolean intraOnly, boolean afterKeyFrame) {
        int updateFactor;
        if (intraOnly) {
            updateFactor = COEF_MAX_UPDATE_FACTOR_KEY;
        } else if (afterKeyFrame) {
            updateFactor = COEF_MAX_UPDATE_FACTOR_AFTER_KEY;
        } else {
            updateFactor = COEF_MAX_UPDATE_FACTOR;
        }
        var pre = previous.probs;
        var probs = context.probs;
        var c = counts.counts;
        for (var set = 0; set < 4 * 2 * 2 * 6; set++) {
            var band = set % 6;
            var contexts = band == 0 ? 3 : 6;
            for (var ctx = 0; ctx < contexts; ctx++) {
                var unit = set * 6 + ctx;
                var countBase = Vp9FrameCounts.COEF + unit * 4;
                var n0 = c[countBase];
                var n1 = c[countBase + 1];
                var n2 = c[countBase + 2];
                var neob = c[countBase + 3];
                var eobBranch = c[Vp9FrameCounts.EOB_BRANCH + unit];
                var probBase = Vp9FrameContext.COEF + unit * 3;
                probs[probBase] = mergeCoef(pre[probBase], neob, eobBranch - neob, COEF_COUNT_SAT, updateFactor);
                probs[probBase + 1] = mergeCoef(pre[probBase + 1], n0, n1 + n2, COEF_COUNT_SAT, updateFactor);
                probs[probBase + 2] = mergeCoef(pre[probBase + 2], n1, n2, COEF_COUNT_SAT, updateFactor);
            }
        }
    }

    /**
     * Adapts the mode probabilities of an inter frame.
     *
     * @param previous the probabilities the frame started from
     * @param context the probabilities to adapt, holding the frame's
     * @param counts the frame's counts
     * @param switchable whether the frame's interpolation filter is switchable
     * @param txModeSelect whether the frame's blocks select their transform size
     */
    static void adaptModeProbs(Vp9FrameContext previous, Vp9FrameContext context, Vp9FrameCounts counts,
                               boolean switchable, boolean txModeSelect) {
        var pre = previous.probs;
        var probs = context.probs;
        var c = counts.counts;
        for (var i = 0; i < 4; i++) {
            var index = Vp9FrameContext.INTRA_INTER + i;
            var count = Vp9FrameCounts.INTRA_INTER + i * 2;
            probs[index] = mergeModeMv(pre[index], c[count], c[count + 1]);
        }
        for (var i = 0; i < 5; i++) {
            var index = Vp9FrameContext.COMP_INTER + i;
            var count = Vp9FrameCounts.COMP_INTER + i * 2;
            probs[index] = mergeModeMv(pre[index], c[count], c[count + 1]);
        }
        for (var i = 0; i < 5; i++) {
            var index = Vp9FrameContext.COMP_REF + i;
            var count = Vp9FrameCounts.COMP_REF + i * 2;
            probs[index] = mergeModeMv(pre[index], c[count], c[count + 1]);
        }
        for (var i = 0; i < 5; i++) {
            for (var j = 0; j < 2; j++) {
                var index = Vp9FrameContext.SINGLE_REF + i * 2 + j;
                var count = Vp9FrameCounts.SINGLE_REF + (i * 2 + j) * 2;
                probs[index] = mergeModeMv(pre[index], c[count], c[count + 1]);
            }
        }
        for (var i = 0; i < 7; i++) {
            mergeTree(Vp9Trees.INTER_MODE, pre, c, Vp9FrameCounts.INTER_MODE + i * 4, probs,
                    Vp9FrameContext.INTER_MODE + i * 3);
        }
        for (var i = 0; i < 4; i++) {
            mergeTree(Vp9Trees.INTRA_MODE, pre, c, Vp9FrameCounts.Y_MODE + i * 10, probs,
                    Vp9FrameContext.Y_MODE + i * 9);
        }
        for (var i = 0; i < 10; i++) {
            mergeTree(Vp9Trees.INTRA_MODE, pre, c, Vp9FrameCounts.UV_MODE + i * 10, probs,
                    Vp9FrameContext.UV_MODE + i * 9);
        }
        for (var i = 0; i < 16; i++) {
            mergeTree(Vp9Trees.PARTITION, pre, c, Vp9FrameCounts.PARTITION + i * 4, probs,
                    Vp9FrameContext.PARTITION + i * 3);
        }
        if (switchable) {
            for (var i = 0; i < 4; i++) {
                mergeTree(Vp9Trees.SWITCHABLE_INTERP, pre, c, Vp9FrameCounts.SWITCHABLE_INTERP + i * 3, probs,
                        Vp9FrameContext.SWITCHABLE_INTERP + i * 2);
            }
        }
        if (txModeSelect) {
            for (var i = 0; i < 2; i++) {
                var t8 = Vp9FrameCounts.TX_8X8 + i * 2;
                var p8 = Vp9FrameContext.TX_8X8 + i;
                probs[p8] = mergeModeMv(pre[p8], c[t8], c[t8 + 1]);
                var t16 = Vp9FrameCounts.TX_16X16 + i * 3;
                var p16 = Vp9FrameContext.TX_16X16 + i * 2;
                probs[p16] = mergeModeMv(pre[p16], c[t16], c[t16 + 1] + c[t16 + 2]);
                probs[p16 + 1] = mergeModeMv(pre[p16 + 1], c[t16 + 1], c[t16 + 2]);
                var t32 = Vp9FrameCounts.TX_32X32 + i * 4;
                var p32 = Vp9FrameContext.TX_32X32 + i * 3;
                probs[p32] = mergeModeMv(pre[p32], c[t32], c[t32 + 1] + c[t32 + 2] + c[t32 + 3]);
                probs[p32 + 1] = mergeModeMv(pre[p32 + 1], c[t32 + 1], c[t32 + 2] + c[t32 + 3]);
                probs[p32 + 2] = mergeModeMv(pre[p32 + 2], c[t32 + 2], c[t32 + 3]);
            }
        }
        for (var i = 0; i < 3; i++) {
            var index = Vp9FrameContext.SKIP + i;
            var count = Vp9FrameCounts.SKIP + i * 2;
            probs[index] = mergeModeMv(pre[index], c[count], c[count + 1]);
        }
    }

    /**
     * Adapts the motion vector probabilities of an inter frame.
     *
     * @param previous the probabilities the frame started from
     * @param context the probabilities to adapt, holding the frame's
     * @param counts the frame's counts
     * @param allowHighMv whether the frame allows high precision motion vectors
     */
    static void adaptMvProbs(Vp9FrameContext previous, Vp9FrameContext context, Vp9FrameCounts counts,
                             boolean allowHighMv) {
        var pre = previous.probs;
        var probs = context.probs;
        var c = counts.counts;
        mergeTree(Vp9Trees.MV_JOINT, pre, c, Vp9FrameCounts.MV_JOINTS, probs, Vp9FrameContext.MV_JOINTS);
        for (var i = 0; i < 2; i++) {
            var base = Vp9FrameCounts.MV_COMPS + i * Vp9FrameCounts.MVC_SIZE;
            var sign = Vp9FrameContext.MV_SIGN + i;
            probs[sign] = mergeModeMv(pre[sign], c[base + Vp9FrameCounts.MVC_SIGN],
                    c[base + Vp9FrameCounts.MVC_SIGN + 1]);
            mergeTree(Vp9Trees.MV_CLASS, pre, c, base + Vp9FrameCounts.MVC_CLASSES, probs,
                    Vp9FrameContext.MV_CLASSES + i * 10);
            mergeTree(Vp9Trees.MV_CLASS0, pre, c, base + Vp9FrameCounts.MVC_CLASS0, probs,
                    Vp9FrameContext.MV_CLASS0 + i);
            for (var j = 0; j < 10; j++) {
                var index = Vp9FrameContext.MV_BITS + i * 10 + j;
                var count = base + Vp9FrameCounts.MVC_BITS + j * 2;
                probs[index] = mergeModeMv(pre[index], c[count], c[count + 1]);
            }
            for (var j = 0; j < 2; j++) {
                mergeTree(Vp9Trees.MV_FP, pre, c, base + Vp9FrameCounts.MVC_CLASS0_FP + j * 4, probs,
                        Vp9FrameContext.MV_CLASS0_FP + (i * 2 + j) * 3);
            }
            mergeTree(Vp9Trees.MV_FP, pre, c, base + Vp9FrameCounts.MVC_FP, probs, Vp9FrameContext.MV_FP + i * 3);
            if (allowHighMv) {
                var class0Hp = Vp9FrameContext.MV_CLASS0_HP + i;
                probs[class0Hp] = mergeModeMv(pre[class0Hp], c[base + Vp9FrameCounts.MVC_CLASS0_HP],
                        c[base + Vp9FrameCounts.MVC_CLASS0_HP + 1]);
                var hp = Vp9FrameContext.MV_HP + i;
                probs[hp] = mergeModeMv(pre[hp], c[base + Vp9FrameCounts.MVC_HP], c[base + Vp9FrameCounts.MVC_HP + 1]);
            }
        }
    }
}
