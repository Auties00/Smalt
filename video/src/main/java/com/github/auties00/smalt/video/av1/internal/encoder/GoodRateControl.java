package com.github.auties00.smalt.video.av1.internal.encoder;

/**
 * Chooses the quantizer of every frame of the good-quality encoder from its place in the frame
 * pyramid, at a constant quality or at a variable bitrate.
 *
 * <p>At a constant quality, the frames that nothing references (leaves and coded overlays) take the
 * quality's quantizer index; an alternate reference takes a finer one, the finer the more its group
 * predicts from it ({@code gfuBoost}); each deeper level of internal alternate references halves
 * the distance to the leaf quantizer; a key frame takes a finer one again, from its own boost.
 *
 * <p>At a variable bitrate, each frame group gets a share of the bitrate, corrected by the bits the
 * stream has spent above or below its target so far, and the leaf quantizer of the group is the
 * one whose predicted size, with every frame's quantizer derived from the leaf quantizer as at a
 * constant quality, fits that share. The size prediction is a bits-per-block model inversely
 * proportional to the quantizer step, with one correction factor per frame class that learns from
 * the coded sizes.
 *
 * <p>In the second pass of a two-pass encode, the bits left for the stream are shared between the
 * groups in proportion to their frames' first-pass coded errors moved halfway to the stream's mean,
 * and a group's leaf quantizer is solved again before each of its frames against the bits its
 * coded frames left, so that sizes the model mispredicts are made up within the group.
 *
 * @implNote This implementation ports libaom's constant-quality quantizer choice
 * ({@code av1/encoder/ratectrl.c}: {@code rc_pick_q_and_bounds_q_mode},
 * {@code get_active_best_quality}, {@code get_intra_q_and_bounds},
 * {@code get_kf_active_quality}, {@code get_gf_active_quality}, the
 * {@code init_minq_luts} polynomials and {@code av1_rc_bits_per_mb}) and the
 * error-based size estimate and group allocation of its two-pass rate control
 * ({@code av1/encoder/pass2_strategy.c}: {@code calc_correction_factor},
 * {@code calculate_modified_err}, {@code calculate_total_gf_group_bits}). The frames of a group
 * keep the constant-quality pyramid offsets from one leaf quantizer instead of libaom's per-frame
 * bit targets.
 */
final class GoodRateControl {
    /**
     * The boost below which alternate references use the high motion quantizer table
     * ({@code gf_low}).
     */
    private static final int GF_LOW = 300;

    /**
     * The boost above which alternate references use the low motion quantizer table
     * ({@code gf_high}).
     */
    private static final int GF_HIGH = 2400;

    /**
     * The divisor of the error-based bits-per-block estimate at every sixteenth quantizer index
     * ({@code q_div_term}).
     */
    private static final double[] Q_DIV_TERM = {18.0, 30.0, 38.0, 44.0, 47.0, 50.0, 52.0, 54.0, 56.0, 58.0, 60.0,
            62.0, 64.0, 66.0, 68.0, 70.0, 72.0};

    /**
     * The scale of the error-based bits-per-block estimate ({@code EPMB_SCALER}).
     */
    private static final double EPMB_SCALER = 1250000;

    /**
     * The mean first-pass coded error per block of the group being coded, which scales the
     * predicted frame sizes, or 0 before the first group.
     */
    private double groupErrorPerBlock;

    /**
     * The leaf quantizer index of the previous group at a variable bitrate, or -1 before the
     * first group.
     */
    private int previousLeafQindex = -1;

    /**
     * The most quantizer indices a group's leaf quantizer may be finer than the previous group's
     * at a variable bitrate: frames coded much finer than their references cost more than the
     * size model predicts.
     */
    private static final int MAX_LEAF_STEP = 24;

    /**
     * The best alternate reference quantizer of each worst quantizer for low motion.
     */
    private static final int[] ARF_LOW_MOTION = new int[256];

    /**
     * The best alternate reference quantizer of each worst quantizer for high motion.
     */
    private static final int[] ARF_HIGH_MOTION = new int[256];

    static {
        for (var i = 0; i < 256; i++) {
            var maxq = RateControl.qindexToQ(i);
            ARF_LOW_MOTION[i] = RateControl.minqIndex(maxq, 0.0000015, -0.0009, 0.30);
            ARF_HIGH_MOTION[i] = RateControl.minqIndex(maxq, 0.0000021, -0.00125, 0.55);
        }
    }

    /**
     * The smallest quantizer index the rate control may choose.
     */
    private int minQindex = 1;

    /**
     * The largest quantizer index the rate control may choose.
     */
    private int maxQindex = 255;

    /**
     * The leaf quantizer index of constant quality, or -1 for a variable bitrate.
     */
    private int cqQindex = -1;

    /**
     * The target bitrate of a variable bitrate, in bits per second.
     */
    private long bitrate = 1_000_000;

    /**
     * The frame rate.
     */
    private double frameRate = 30;

    /**
     * The size correction factor of each rate control class ({@link FrameUpdateType#rateClass}).
     */
    private final double[] correction = {1.0, 1.0, 1.0, 1.0};

    /**
     * The bits the coded frames were given as targets, summed.
     */
    private long targetBits;

    /**
     * The bits the coded frames took, summed.
     */
    private long actualBits;

    /**
     * The sum of the first-pass coded errors of the frames seen, for the complexity of a group
     * relative to the stream.
     */
    private double errorSum;

    /**
     * The number of frames whose coded error {@link #errorSum} holds.
     */
    private long errorCount;

    /**
     * The leaf quantizer index chosen for the current group.
     */
    private int groupLeafQindex = 128;

    /**
     * The share of an alternate reference's quantizer boost the current group keeps: 1, or 0.2
     * for the last group before a key frame or the end of the stream ({@code arf_boost_factor}).
     */
    private double arfBoostFactor = 1.0;

    /**
     * Sets whether the current group is the last before a key frame or the end of the stream,
     * whose alternate reference predicts no later group and keeps a fifth of its boost
     * ({@code LAST_ALR_BOOST_FACTOR}).
     *
     * @param last whether the group is the last
     */
    void lastGroup(boolean last) {
        arfBoostFactor = last ? 0.2 : 1.0;
    }

    /**
     * Creates a rate control at constant quality index 128.
     */
    GoodRateControl() {
    }

    /**
     * The largest share of a section's bits, in average frames per frame ({@code vbrmax_section}
     * of 2000%).
     */
    private static final double MAX_SECTION_RATIO = 20.0;

    /**
     * The power of the frames' complexity in their share of the bits ({@code vbrbias} of 50).
     */
    private static final double VBR_BIAS = 0.5;

    /**
     * The modified error of each frame of a second pass, the complexity its bits follow, or
     * {@code null} in a single pass.
     */
    private double[] modifiedErrors;

    /**
     * The modified error of the frames not yet given to a group.
     */
    private double modifiedErrorLeft;

    /**
     * The bits the stream may still spend in a second pass.
     */
    private long bitsLeft;

    /**
     * The coding step of each frame of the group a second pass planned, in coding order, or
     * {@code null} when the group was not planned from the first pass.
     */
    private FrameUpdateType[] planTypes;

    /**
     * The pyramid depth of each frame of {@link #planTypes}.
     */
    private int[] planDepths;

    /**
     * The index in {@link #planTypes} of the next frame to code.
     */
    private int planIndex;

    /**
     * The bits of the group a second pass planned.
     */
    private double planBudget;

    /**
     * The bits the planned group's coded frames took.
     */
    private long planSpent;

    /**
     * The boosts, picture size and last-frame flag of the planned group, for its replanning.
     */
    private int planGfuBoost;

    /**
     * The key frame boost of the planned group.
     */
    private int planKfBoost;

    /**
     * The number of 16x16 blocks of a picture of the planned group.
     */
    private long planBlocks;

    /**
     * The number of luma samples of a picture of the planned group.
     */
    private long planPixels;

    /**
     * Whether a key frame of the planned group is the stream's last frame.
     */
    private boolean planLastFrame;

    /**
     * Makes the rate control a second pass over first-pass coded errors: each group's share of the
     * bits left is its share of the modified error left, the frames' coded errors moved halfway to
     * their mean ({@code calculate_modified_err} with the default bias, minimum and maximum
     * sections).
     *
     * @param codedErrors the first-pass coded error of every frame, in display order
     * @param totalBits the bits the frames may take
     */
    void twoPass(double[] codedErrors, long totalBits) {
        var mean = 0.0;
        for (var e : codedErrors) {
            mean += e;
        }
        mean /= Math.max(1, codedErrors.length);
        var average = Math.max(mean, 1e-6);
        modifiedErrors = new double[codedErrors.length];
        modifiedErrorLeft = 0;
        for (var i = 0; i < codedErrors.length; i++) {
            var modified = average * StrictMath.pow(codedErrors[i] / average, VBR_BIAS);
            modifiedErrors[i] = Math.clamp(modified, 0, average * MAX_SECTION_RATIO);
            modifiedErrorLeft += modifiedErrors[i];
        }
        bitsLeft = totalBits;
    }

    /**
     * Configures the rate control.
     *
     * @param cqQindex the leaf quantizer index of constant quality, or -1 for a variable bitrate
     * @param bitrate the target bitrate of a variable bitrate
     * @param frameRate the frame rate
     * @param minQindex the smallest quantizer index
     * @param maxQindex the largest quantizer index
     */
    void configure(int cqQindex, long bitrate, double frameRate, int minQindex, int maxQindex) {
        this.cqQindex = cqQindex;
        this.bitrate = bitrate;
        this.frameRate = frameRate;
        this.minQindex = Math.clamp(minQindex, 0, 255);
        this.maxQindex = Math.clamp(maxQindex, this.minQindex, 255);
        if (cqQindex >= 0) {
            groupLeafQindex = Math.clamp(cqQindex, this.minQindex, this.maxQindex);
        }
    }

    /**
     * Returns the leaf quantizer index of the current group.
     *
     * @return the quantizer index
     */
    int leafQindex() {
        return groupLeafQindex;
    }

    /**
     * Interpolates between a low motion and a high motion quantizer by boost
     * ({@code get_active_quality}).
     *
     * @param q the worst quantizer index
     * @param boost the frame's boost
     * @param low the boost below which the high motion table applies
     * @param high the boost above which the low motion table applies
     * @param lowMotion the low motion table
     * @param highMotion the high motion table
     * @return the quantizer index
     */
    private static int activeQuality(int q, int boost, int low, int high, int[] lowMotion, int[] highMotion) {
        if (boost > high) {
            return lowMotion[q];
        }
        if (boost < low) {
            return highMotion[q];
        }
        var gap = high - low;
        var offset = high - boost;
        var diff = highMotion[q] - lowMotion[q];
        return lowMotion[q] + (offset * diff + (gap >> 1)) / gap;
    }

    /**
     * Returns the quantizer index of an alternate reference of a group whose leaves use a quantizer
     * ({@code get_active_best_quality} for {@code ARF_UPDATE}).
     *
     * @param leaf the leaf quantizer index
     * @param gfuBoost the group's boost
     * @return the quantizer index
     */
    int arfQindex(int leaf, int gfuBoost) {
        var best = activeQuality(leaf, gfuBoost, GF_LOW, GF_HIGH, ARF_LOW_MOTION, ARF_HIGH_MOTION);
        var minBoost = ARF_HIGH_MOTION[leaf];
        best = minBoost - (int) ((minBoost - best) * arfBoostFactor);
        if (leaf > 0) {
            best = Math.max(1, best);
        }
        return Math.clamp(best, minQindex, maxQindex);
    }

    /**
     * Returns the quantizer index of an internal alternate reference at a pyramid depth: the
     * alternate reference's quantizer moved halfway to the leaf quantizer for each level below the
     * first.
     *
     * @param arf the alternate reference's quantizer index
     * @param leaf the leaf quantizer index
     * @param depth the pyramid depth, 2 or more
     * @return the quantizer index
     */
    int internalQindex(int arf, int leaf, int depth) {
        var q = arf;
        for (var h = depth; h > 1; h--) {
            q = (q + leaf + 1) / 2;
        }
        return Math.clamp(q, minQindex, maxQindex);
    }

    /**
     * Returns the quantizer index of a key frame whose following frames use a leaf quantizer
     * ({@code get_intra_q_and_bounds} for a key frame that is not forced).
     *
     * @param leaf the leaf quantizer index
     * @param kfBoost the key frame's boost
     * @param pixels the number of luma samples of a picture
     * @param lastFrame whether the key frame is the stream's last frame, which then takes the
     * leaf quantizer
     * @return the quantizer index
     */
    int keyQindex(int leaf, int kfBoost, long pixels, boolean lastFrame) {
        if (lastFrame) {
            return Math.clamp(leaf, minQindex, maxQindex);
        }
        var best = activeQuality(leaf, kfBoost, RateControl.KF_LOW, RateControl.KF_HIGH, RateControl.KF_LOW_MINQ,
                RateControl.KF_HIGH_MINQ);
        var adjust = 1.0;
        if (pixels <= 352L * 288) {
            adjust -= 0.25;
        }
        var q = RateControl.qindexToQ(best);
        best += RateControl.findQindex(q * adjust, 0, 255) - RateControl.findQindex(q, 0, 255);
        if (leaf > 0) {
            best = Math.max(1, best);
        }
        return Math.clamp(best, minQindex, maxQindex);
    }

    /**
     * Returns the quantizer index of a frame of a group from the group's leaf quantizer.
     *
     * @param type the frame's coding step
     * @param depth the pyramid depth of an internal alternate reference
     * @param leaf the leaf quantizer index
     * @param gfuBoost the group's boost
     * @param kfBoost the key frame's boost
     * @param pixels the number of luma samples of a picture
     * @param lastFrame whether a key frame is the stream's last frame
     * @return the quantizer index
     */
    int qindex(FrameUpdateType type, int depth, int leaf, int gfuBoost, int kfBoost, long pixels,
               boolean lastFrame) {
        return switch (type) {
            case KEY -> keyImportance > 0 ? Math.clamp(TplModel.qindex(leaf, keyImportance), minQindex, maxQindex)
                    : keyQindex(leaf, kfBoost, pixels, lastFrame);
            case ARF -> arf(leaf, gfuBoost);
            case INTERNAL_ARF -> internalQindex(arf(leaf, gfuBoost), leaf, depth);
            case LEAF, OVERLAY, INTERNAL_OVERLAY -> Math.clamp(leaf, minQindex, maxQindex);
        };
    }

    /**
     * Returns the alternate reference's quantizer index: from its dependency model importance
     * when known, else from its boost.
     *
     * @param leaf the leaf quantizer index
     * @param gfuBoost the group's boost
     * @return the quantizer index
     */
    private int arf(int leaf, int gfuBoost) {
        return arfImportance > 0 ? Math.clamp(TplModel.qindex(leaf, StrictMath.pow(arfImportance, ARF_K)), minQindex, maxQindex)
                : arfQindex(leaf, gfuBoost);
    }

    /**
     * The dependency model importance of the current group's key frame, or 0 to use its boost.
     */
    private double keyImportance;

    /**
     * The dependency model importance of the current group's alternate reference, or 0 to use
     * its boost.
     */
    private double arfImportance;

    /**
     * The exponent applied to an alternate reference's dependency model importance before its
     * quantizer step is derived: the model's propagation of costs overstates how much later
     * frames depend on a frame, so the step shrinks less than the importance alone says.
     */
    private static final double ARF_K = 0.8;

    /**
     * Sets the dependency model importances of the current group's key frame and alternate
     * reference ({@code av1_tpl_get_q_index}).
     *
     * @param key the key frame's importance, or 0 for none
     * @param arf the alternate reference's importance, or 0 for none
     */
    void importance(double key, double arf) {
        keyImportance = key;
        arfImportance = arf;
    }

    /**
     * Returns the predicted size of a frame: the bits per block the group's first-pass coded
     * error implies at the quantizer ({@code calc_correction_factor}), or before the first group
     * the content-independent estimate ({@code av1_rc_bits_per_mb}), times the frame class's
     * learned correction and the blocks.
     *
     * @param type the frame's coding step
     * @param qindex the quantizer index
     * @param blocks the number of 16x16 blocks of a picture
     * @return the size in bits
     */
    long predictBits(FrameUpdateType type, int qindex, long blocks) {
        double enumerator;
        if (groupErrorPerBlock > 0) {
            var index = qindex >> 4;
            var divisor = Q_DIV_TERM[index] + (Q_DIV_TERM[index + 1] - Q_DIV_TERM[index]) * (qindex & 15) / 16.0;
            enumerator = EPMB_SCALER * StrictMath.pow(groupErrorPerBlock, 0.9) / divisor;
        } else {
            enumerator = type == FrameUpdateType.KEY ? 2000000.0 : 1500000.0;
        }
        var bpm = enumerator * correction[type.rateClass] / RateControl.qindexToQ(qindex);
        return (long) (bpm * blocks) >> RateControl.BPER_MB_NORMBITS;
    }

    /**
     * Records a frame's first-pass coded error, for the complexity of later groups relative to
     * the stream.
     *
     * @param codedError the coded error per block
     */
    void observe(double codedError) {
        errorSum += codedError;
        errorCount++;
    }

    /**
     * Chooses the leaf quantizer of a group at a variable bitrate, or takes the constant-quality one.
     *
     * <p>At a variable bitrate the leaf quantizer is the coarsest whose predicted group size fits
     * the group's share of the bitrate, corrected by the bits spent so far, but never finer than
     * half the previous group's ({@code active_worst_quality}) or than the previous group's by
     * more than {@link #MAX_LEAF_STEP} indices, nor finer than the previous group's when the group
     * has no key frame or alternate reference to anchor its frames.
     *
     * @param types the coding step of each frame of the group, in coding order
     * @param depths the pyramid depth of each frame
     * @param shown the number of frames the group shows
     * @param groupError the mean first-pass coded error of the group's frames
     * @param gfuBoost the group's boost
     * @param kfBoost the key frame's boost
     * @param blocks the number of 16x16 blocks of a picture
     * @param pixels the number of luma samples of a picture
     * @param lastFrame whether a key frame of the group is the stream's last frame
     * @param firstDisplay the display index of the group's first shown frame
     * @return the leaf quantizer index
     */
    int planGroup(FrameUpdateType[] types, int[] depths, int shown, double groupError, int gfuBoost, int kfBoost,
                  long blocks, long pixels, boolean lastFrame, int firstDisplay) {
        if (cqQindex >= 0) {
            groupLeafQindex = Math.clamp(cqQindex, minQindex, maxQindex);
            return groupLeafQindex;
        }
        // TODO: regulate each frame's quantizer against its own bit target with per-class rate
        // correction (libaom's av1_rc_regulate_q) instead of one leaf quantizer per group; groups
        // whose alternate reference lands much finer than the previous ones still overshoot.
        groupErrorPerBlock = groupError;
        var perFrame = bitrate / frameRate;
        double budget;
        planTypes = null;
        if (modifiedErrors != null && firstDisplay + shown <= modifiedErrors.length) {
            var groupModified = 0.0;
            for (var i = firstDisplay; i < firstDisplay + shown; i++) {
                groupModified += modifiedErrors[i];
            }
            budget = modifiedErrorLeft > 0 ? bitsLeft * (groupModified / modifiedErrorLeft) : perFrame * shown;
            modifiedErrorLeft -= groupModified;
            budget = Math.min(budget, perFrame * shown * MAX_SECTION_RATIO);
            planTypes = types;
            planDepths = depths;
            planIndex = 0;
            planBudget = budget;
            planSpent = 0;
            planGfuBoost = gfuBoost;
            planKfBoost = kfBoost;
            planBlocks = blocks;
            planPixels = pixels;
            planLastFrame = lastFrame;
            groupLeafQindex = solveLeaf(types, depths, 0, budget, gfuBoost, kfBoost, blocks, pixels, lastFrame);
            previousLeafQindex = groupLeafQindex;
            return groupLeafQindex;
        } else {
            budget = perFrame * shown;
            if (errorCount > 0 && groupError > 0) {
                var mean = errorSum / errorCount;
                budget *= Math.clamp(StrictMath.pow(groupError / mean, 0.3), 0.7, 1.4);
            }
            var debt = targetBits - actualBits;
            var horizon = Math.max(shown, 2 * frameRate);
            budget += debt * shown / horizon;
        }
        budget = Math.max(budget, perFrame * shown * 0.25);
        var low = solveLeaf(types, depths, 0, budget, gfuBoost, kfBoost, blocks, pixels, lastFrame);
        if (previousLeafQindex >= 0) {
            low = Math.max(low, Math.max(previousLeafQindex >> 1, previousLeafQindex - MAX_LEAF_STEP));
            var anchored = false;
            for (var type : types) {
                anchored |= type == FrameUpdateType.KEY || type == FrameUpdateType.ARF;
            }
            if (!anchored) {
                low = Math.max(low, previousLeafQindex);
            }
        }
        previousLeafQindex = low;
        groupLeafQindex = low;
        targetBits += (long) budget;
        return low;
    }

    /**
     * Returns the finest leaf quantizer whose predicted size of a group's frames from a position
     * on fits a budget.
     *
     * @param types the coding step of each frame of the group, in coding order
     * @param depths the pyramid depth of each frame
     * @param from the index of the first frame counted
     * @param budget the bits the counted frames may take
     * @param gfuBoost the group's boost
     * @param kfBoost the key frame's boost
     * @param blocks the number of 16x16 blocks of a picture
     * @param pixels the number of luma samples of a picture
     * @param lastFrame whether a key frame of the group is the stream's last frame
     * @return the leaf quantizer index, {@link #maxQindex} when no quantizer fits
     */
    private int solveLeaf(FrameUpdateType[] types, int[] depths, int from, double budget, int gfuBoost,
                          int kfBoost, long blocks, long pixels, boolean lastFrame) {
        var low = minQindex;
        var high = maxQindex;
        while (low < high) {
            var mid = (low + high) >> 1;
            long bits = 0;
            for (var i = from; i < types.length; i++) {
                var q = qindex(types[i], depths[i], mid, gfuBoost, kfBoost, pixels, lastFrame);
                bits += predictBits(types[i], q, blocks);
            }
            if (bits > budget) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return low;
    }

    /**
     * Solves the leaf quantizer of a group a second pass planned again before its next frame,
     * against the bits its coded frames left of its budget and the size corrections learned from
     * them; does nothing for a group planned otherwise or once its frames are coded
     * ({@code av1_rc_regulate_q} on the group's remaining frames).
     */
    void replan() {
        if (planTypes == null || planIndex == 0 || planIndex >= planTypes.length) {
            return;
        }
        groupLeafQindex = solveLeaf(planTypes, planDepths, planIndex, planBudget - planSpent, planGfuBoost,
                planKfBoost, planBlocks, planPixels, planLastFrame);
        previousLeafQindex = groupLeafQindex;
    }

    /**
     * Learns from a coded frame: updates its class's size correction factor and the spent bits.
     *
     * @param type the frame's coding step
     * @param qindex the frame's quantizer index
     * @param bits the coded size in bits
     * @param blocks the number of 16x16 blocks of a picture
     */
    void update(FrameUpdateType type, int qindex, long bits, long blocks) {
        actualBits += bits;
        bitsLeft -= bits;
        if (planTypes != null) {
            planSpent += bits;
            if (type != FrameUpdateType.OVERLAY && type != FrameUpdateType.INTERNAL_OVERLAY) {
                planIndex++;
            }
        }
        var predicted = predictBits(type, qindex, blocks);
        if (predicted <= 0 || bits <= 0) {
            return;
        }
        var ratio = Math.clamp((double) bits / predicted, 0.25, 4.0);
        correction[type.rateClass] = Math.clamp(correction[type.rateClass] * Math.sqrt(ratio), 0.02, 50.0);
    }
}
