/* Ports libvpx 1.17.0 (vp9/encoder/vp9_encodemv.c, vp9/encoder/vp9_encoder.c). Copyright (c) 2010, The WebM Project
   authors; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.video.vp9.internal;

/**
 * Codes and costs the motion vectors of the VP9 encoder.
 *
 * <p>A motion vector is coded as its difference from a reference vector, in 1/8 pixel: a joint
 * symbol tells which components are nonzero, and each nonzero component is coded as a sign, a
 * magnitude class and the offset within the class. The cost tables map a component difference
 * to its cost and hold {@link #MV_VALS} entries, entry {@link #MV_MAX} being the zero
 * difference.
 *
 * @implNote This implementation ports libvpx's {@code vp9/encoder/vp9_encodemv.c} and the cost
 * tables of {@code vp9/encoder/vp9_encoder.c} ({@code cal_nmvsadcosts}).
 */
final class Vp9EncMv {
    /**
     * The largest component magnitude a cost table holds ({@code MV_MAX}).
     */
    static final int MV_MAX = (1 << 14) - 1;

    /**
     * The number of entries of a cost table ({@code MV_VALS}).
     */
    static final int MV_VALS = 2 * MV_MAX + 1;

    /**
     * The probability with which every motion vector probability update is signaled
     * ({@code MV_UPDATE_PROB}).
     */
    private static final int MV_UPDATE_PROB = 252;

    /**
     * The paths of the joint symbols.
     */
    private static final int[] JOINT_TOKENS = Vp9EncTables.treeTokens(Vp9Trees.MV_JOINT, 4);

    /**
     * The paths of the class symbols.
     */
    private static final int[] CLASS_TOKENS = Vp9EncTables.treeTokens(Vp9Trees.MV_CLASS, 11);

    /**
     * The paths of the fractional symbols.
     */
    private static final int[] FP_TOKENS = Vp9EncTables.treeTokens(Vp9Trees.MV_FP, 4);

    /**
     * The cost of each joint symbol in the motion search's sum of absolute differences
     * ({@code cal_nmvjointsadcost}).
     */
    static final int[] JOINT_SAD_COST = {600, 300, 300, 300};

    /**
     * The cost of each full-pixel component difference in the motion search's sum of absolute
     * differences, centered on {@link #MV_MAX} ({@code cal_nmvsadcosts}).
     */
    static final int[] SAD_COST = sadCosts();

    /**
     * Prevents instantiation.
     */
    private Vp9EncMv() {
        throw new AssertionError();
    }

    /**
     * Builds the motion search's component costs.
     *
     * @return the costs
     */
    private static int[] sadCosts() {
        var costs = new int[MV_VALS];
        for (var i = 1; i <= MV_MAX; i++) {
            var z = (int) (256 * (2 * (log2f(8 * i) + 0.6)));
            costs[MV_MAX + i] = z;
            costs[MV_MAX - i] = z;
        }
        return costs;
    }

    /**
     * Returns the base-2 logarithm rounded to single precision, as {@code log2f} computes it.
     *
     * @param value the argument
     * @return the logarithm
     */
    private static double log2f(int value) {
        return (float) (StrictMath.log(value) / StrictMath.log(2));
    }

    /**
     * Returns the class of a component magnitude minus one ({@code vp9_get_mv_class}).
     *
     * @param z the magnitude minus one, in 1/8 pixel
     * @return the class, from 0 to 10
     */
    static int mvClass(int z) {
        if (z >= 2 * 4096) {
            return 10;
        }
        var n = z >> 3;
        return n <= 1 ? 0 : 31 - Integer.numberOfLeadingZeros(n);
    }

    /**
     * Returns the smallest magnitude minus one of a class ({@code mv_class_base}).
     *
     * @param mvClass the class
     * @return the base
     */
    static int classBase(int mvClass) {
        return mvClass != 0 ? 2 << (mvClass + 2) : 0;
    }

    /**
     * Returns whether a reference vector allows 1/8 pixel precision ({@code use_mv_hp}).
     *
     * @param ref the packed reference vector
     * @return {@code true} when both components are below 64
     */
    static boolean useHp(int ref) {
        var row = ref >> 16;
        var col = (short) ref;
        return Math.abs(row) < 64 && Math.abs(col) < 64;
    }

    /**
     * Returns the joint symbol of a difference ({@code vp9_get_mv_joint}).
     *
     * @param row the row difference
     * @param col the column difference
     * @return the joint
     */
    static int joint(int row, int col) {
        return (row != 0 ? 2 : 0) | (col != 0 ? 1 : 0);
    }

    /**
     * Builds the cost of every joint symbol and component difference from the probabilities of
     * a frame context ({@code vp9_build_nmv_cost_table}).
     *
     * @param probs the frame context probabilities
     * @param usehp whether 1/8 pixel precision is allowed
     * @param jointCost receives the cost of the four joints
     * @param rowCost receives the cost of each row difference, centered on {@link #MV_MAX}
     * @param colCost receives the cost of each column difference, centered on {@link #MV_MAX}
     */
    static void buildCostTable(int[] probs, boolean usehp, int[] jointCost, int[] rowCost, int[] colCost) {
        Vp9EncTables.costTokens(jointCost, 0, probs, Vp9FrameContext.MV_JOINTS, Vp9Trees.MV_JOINT);
        buildComponent(rowCost, probs, 0, usehp);
        buildComponent(colCost, probs, 1, usehp);
    }

    /**
     * Builds the cost table of one component ({@code build_nmv_component_cost_table}).
     *
     * @param mvcost receives the costs, centered on {@link #MV_MAX}
     * @param probs the frame context probabilities
     * @param component 0 for rows, 1 for columns
     * @param usehp whether 1/8 pixel precision is allowed
     */
    private static void buildComponent(int[] mvcost, int[] probs, int component, boolean usehp) {
        var signProb = probs[Vp9FrameContext.MV_SIGN + component];
        var sign0 = Vp9EncTables.costZero(signProb);
        var sign1 = Vp9EncTables.costOne(signProb);
        var classCost = new int[11];
        Vp9EncTables.costTokens(classCost, 0, probs, Vp9FrameContext.MV_CLASSES + component * 10, Vp9Trees.MV_CLASS);
        var class0Cost = new int[2];
        Vp9EncTables.costTokens(class0Cost, 0, probs, Vp9FrameContext.MV_CLASS0 + component, Vp9Trees.MV_CLASS0);
        var bitsCost = new int[20];
        for (var i = 0; i < 10; i++) {
            var p = probs[Vp9FrameContext.MV_BITS + component * 10 + i];
            bitsCost[i * 2] = Vp9EncTables.costZero(p);
            bitsCost[i * 2 + 1] = Vp9EncTables.costOne(p);
        }
        var class0FpCost = new int[8];
        for (var i = 0; i < 2; i++) {
            Vp9EncTables.costTokens(class0FpCost, i * 4, probs, Vp9FrameContext.MV_CLASS0_FP + (component * 2 + i) * 3,
                    Vp9Trees.MV_FP);
        }
        var fpCost = new int[4];
        Vp9EncTables.costTokens(fpCost, 0, probs, Vp9FrameContext.MV_FP + component * 3, Vp9Trees.MV_FP);
        var class0Hp = probs[Vp9FrameContext.MV_CLASS0_HP + component];
        var hp = probs[Vp9FrameContext.MV_HP + component];
        var class0Hp0 = Vp9EncTables.costZero(class0Hp);
        var class0Hp1 = Vp9EncTables.costOne(class0Hp);
        var hp0 = Vp9EncTables.costZero(hp);
        var hp1 = Vp9EncTables.costOne(hp);
        mvcost[MV_MAX] = 0;
        for (var o = 0; o < 16; o++) {
            var cost = classCost[0];
            var v = o + 1;
            var d = o >> 3;
            var f = (o >> 1) & 3;
            cost += class0Cost[d];
            cost += class0FpCost[d * 4 + f];
            if (usehp) {
                cost += (o & 1) != 0 ? class0Hp1 : class0Hp0;
            }
            mvcost[MV_MAX + v] = cost + sign0;
            mvcost[MV_MAX - v] = cost + sign1;
        }
        for (var c = 1; c < 11; c++) {
            for (var d = 0; d < (1 << c); d++) {
                var wholeCost = classCost[c];
                for (var i = 0; i < c; i++) {
                    wholeCost += bitsCost[i * 2 + ((d >> i) & 1)];
                }
                for (var f = 0; f < 4; f++) {
                    var cost = wholeCost + fpCost[f];
                    var v = (2 << (c + 2)) + d * 8 + f * 2 + 1;
                    if (usehp) {
                        mvcost[MV_MAX + v] = cost + hp0 + sign0;
                        mvcost[MV_MAX - v] = cost + hp0 + sign1;
                        if (v + 1 > MV_MAX) {
                            break;
                        }
                        mvcost[MV_MAX + v + 1] = cost + hp1 + sign0;
                        mvcost[MV_MAX - v - 1] = cost + hp1 + sign1;
                    } else {
                        mvcost[MV_MAX + v] = cost + sign0;
                        mvcost[MV_MAX - v] = cost + sign1;
                        if (v + 1 > MV_MAX) {
                            break;
                        }
                        mvcost[MV_MAX + v + 1] = cost + sign0;
                        mvcost[MV_MAX - v - 1] = cost + sign1;
                    }
                }
            }
        }
    }

    /**
     * Writes a motion vector as its difference from a reference vector ({@code vp9_encode_mv}).
     *
     * @param w the boolean encoder
     * @param probs the frame context probabilities
     * @param mv the packed vector
     * @param ref the packed reference vector
     * @param usehp whether the frame allows 1/8 pixel precision
     */
    static void write(Vp9BoolEncoder w, int[] probs, int mv, int ref, boolean usehp) {
        var row = (mv >> 16) - (ref >> 16);
        var col = (short) mv - (short) ref;
        var j = joint(row, col);
        var hp = usehp && useHp(ref);
        var token = JOINT_TOKENS[j];
        w.writeTree(Vp9Trees.MV_JOINT, probs, Vp9FrameContext.MV_JOINTS, token >>> 5, token & 31);
        if (row != 0) {
            writeComponent(w, probs, row, 0, hp);
        }
        if (col != 0) {
            writeComponent(w, probs, col, 1, hp);
        }
    }

    /**
     * Writes one nonzero component of a difference ({@code encode_mv_component}).
     *
     * @param w the boolean encoder
     * @param probs the frame context probabilities
     * @param comp the component
     * @param component 0 for the row, 1 for the column
     * @param usehp whether the 1/8 pixel bit is coded
     */
    private static void writeComponent(Vp9BoolEncoder w, int[] probs, int comp, int component, boolean usehp) {
        var sign = comp < 0 ? 1 : 0;
        var mag = sign != 0 ? -comp : comp;
        var mvClass = mvClass(mag - 1);
        var offset = mag - 1 - classBase(mvClass);
        var d = offset >> 3;
        var fr = (offset >> 1) & 3;
        var hp = offset & 1;
        w.write(sign, probs[Vp9FrameContext.MV_SIGN + component]);
        var classToken = CLASS_TOKENS[mvClass];
        w.writeTree(Vp9Trees.MV_CLASS, probs, Vp9FrameContext.MV_CLASSES + component * 10, classToken >>> 5,
                classToken & 31);
        if (mvClass == 0) {
            w.write(d, probs[Vp9FrameContext.MV_CLASS0 + component]);
        } else {
            for (var i = 0; i < mvClass; i++) {
                w.write((d >> i) & 1, probs[Vp9FrameContext.MV_BITS + component * 10 + i]);
            }
        }
        var fpToken = FP_TOKENS[fr];
        var fpBase = mvClass == 0 ? Vp9FrameContext.MV_CLASS0_FP + (component * 2 + d) * 3
                : Vp9FrameContext.MV_FP + component * 3;
        w.writeTree(Vp9Trees.MV_FP, probs, fpBase, fpToken >>> 5, fpToken & 31);
        if (usehp) {
            w.write(hp, probs[mvClass == 0 ? Vp9FrameContext.MV_CLASS0_HP + component : Vp9FrameContext.MV_HP + component]);
        }
    }

    /**
     * Writes the motion vector probability updates of the compressed header and applies them
     * to the frame context ({@code vp9_write_nmv_probs}).
     *
     * @param w the boolean encoder
     * @param probs the frame context probabilities, updated in place
     * @param counts the frame's counts
     * @param usehp whether the frame allows 1/8 pixel precision
     */
    static void writeProbUpdates(Vp9BoolEncoder w, int[] probs, Vp9EncCounts counts, boolean usehp) {
        var c = counts.counts;
        var branch = new long[64];
        writeTreeUpdate(w, Vp9Trees.MV_JOINT, probs, Vp9FrameContext.MV_JOINTS, c, Vp9EncCounts.MV_JOINTS, 4, branch);
        for (var i = 0; i < 2; i++) {
            var base = Vp9EncCounts.MV_COMPS + i * Vp9EncCounts.MV_COMPONENT_SIZE;
            update(w, c[base + Vp9EncCounts.MV_SIGN], c[base + Vp9EncCounts.MV_SIGN + 1], probs,
                    Vp9FrameContext.MV_SIGN + i);
            writeTreeUpdate(w, Vp9Trees.MV_CLASS, probs, Vp9FrameContext.MV_CLASSES + i * 10, c,
                    base + Vp9EncCounts.MV_CLASSES, 11, branch);
            writeTreeUpdate(w, Vp9Trees.MV_CLASS0, probs, Vp9FrameContext.MV_CLASS0 + i, c,
                    base + Vp9EncCounts.MV_CLASS0, 2, branch);
            for (var j = 0; j < 10; j++) {
                update(w, c[base + Vp9EncCounts.MV_BITS + j * 2], c[base + Vp9EncCounts.MV_BITS + j * 2 + 1], probs,
                        Vp9FrameContext.MV_BITS + i * 10 + j);
            }
        }
        for (var i = 0; i < 2; i++) {
            var base = Vp9EncCounts.MV_COMPS + i * Vp9EncCounts.MV_COMPONENT_SIZE;
            for (var j = 0; j < 2; j++) {
                writeTreeUpdate(w, Vp9Trees.MV_FP, probs, Vp9FrameContext.MV_CLASS0_FP + (i * 2 + j) * 3, c,
                        base + Vp9EncCounts.MV_CLASS0_FP + j * 4, 4, branch);
            }
            writeTreeUpdate(w, Vp9Trees.MV_FP, probs, Vp9FrameContext.MV_FP + i * 3, c, base + Vp9EncCounts.MV_FP, 4,
                    branch);
        }
        if (usehp) {
            for (var i = 0; i < 2; i++) {
                var base = Vp9EncCounts.MV_COMPS + i * Vp9EncCounts.MV_COMPONENT_SIZE;
                update(w, c[base + Vp9EncCounts.MV_CLASS0_HP], c[base + Vp9EncCounts.MV_CLASS0_HP + 1], probs,
                        Vp9FrameContext.MV_CLASS0_HP + i);
                update(w, c[base + Vp9EncCounts.MV_HP], c[base + Vp9EncCounts.MV_HP + 1], probs,
                        Vp9FrameContext.MV_HP + i);
            }
        }
    }

    /**
     * Writes the updates of the probabilities of a tree from the counts of its symbols
     * ({@code write_mv_update}).
     *
     * @param w the boolean encoder
     * @param tree the tree in libvpx layout: child pairs, a leaf {@code -s} standing for symbol {@code s}
     * @param probs the probabilities, updated in place
     * @param probBase the index of the tree's first probability
     * @param counts the symbol counts of the tree's leaves
     * @param countBase the index of the first symbol's count
     * @param symbols the number of symbols
     * @param branch a scratch array of at least {@code 2 * symbols} entries
     */
    private static void writeTreeUpdate(Vp9BoolEncoder w, int[] tree, int[] probs, int probBase, int[] counts,
                                        int countBase, int symbols, long[] branch) {
        Vp9Probability.treeBranchCounts(tree, counts, countBase, branch);
        for (var i = 0; i < symbols - 1; i++) {
            update(w, branch[i * 2], branch[i * 2 + 1], probs, probBase + i);
        }
    }

    /**
     * Writes whether a probability is updated and, if so, its new value with 7 bits
     * ({@code update_mv}).
     *
     * @param w the boolean encoder
     * @param n0 the number of zeros coded with the probability
     * @param n1 the number of ones
     * @param probs the probabilities
     * @param index the index of the probability, updated in place
     */
    private static void update(Vp9BoolEncoder w, long n0, long n1, int[] probs, int index) {
        var cur = probs[index];
        var newP = Vp9EncTables.binaryProb(n0, n1) | 1;
        var doUpdate = Vp9EncTables.costBranch(n0, n1, cur) + Vp9EncTables.costZero(MV_UPDATE_PROB)
                > Vp9EncTables.costBranch(n0, n1, newP) + Vp9EncTables.costOne(MV_UPDATE_PROB)
                + (7L << Vp9EncTables.PROB_COST_SHIFT);
        w.write(doUpdate ? 1 : 0, MV_UPDATE_PROB);
        if (doUpdate) {
            probs[index] = newP;
            w.writeLiteral(newP >> 1, 7);
        }
    }
}
