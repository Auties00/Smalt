/* Ports libvpx 1.17.0 (vp9/encoder/vp9_ratectrl.c). Copyright (c) 2010, The WebM Project authors; BSD 3-Clause
   License. Modified for Smalt. */
package com.github.auties00.smalt.video.vp9.internal;

/**
 * Holds the one-pass constant bitrate rate control state of the real-time VP9 encoder and
 * computes frame targets, quantizer choices and buffer updates.
 *
 * <p>A leaky bucket models the decoder buffer: each frame adds the average frame budget and
 * removes the coded size. The frame target leans away from the budget in proportion to the
 * buffer's distance from its optimal level, the quantizer comes from a bits-per-macroblock model
 * corrected by the error of past frames, and the worst allowed quantizer rises as the buffer
 * drains. With temporal layers each layer keeps its own copy of this state.
 *
 * @implNote This implementation ports the one-pass CBR functions of libvpx's
 * {@code vp9/encoder/vp9_ratectrl.c} ({@code vp9_rc_init},
 * {@code vp9_rc_get_one_pass_cbr_params}, {@code vp9_calc_pframe_target_size_one_pass_cbr},
 * {@code vp9_calc_iframe_target_size_one_pass_cbr},
 * {@code rc_pick_q_and_bounds_one_pass_cbr},
 * {@code calc_active_worst_quality_one_pass_cbr}, {@code vp9_rc_regulate_q},
 * {@code adjust_q_cbr}, {@code vp9_rc_update_rate_correction_factors},
 * {@code vp9_rc_postencode_update}, {@code vp9_test_drop},
 * {@code vp9_rc_update_framerate}, {@code vp9_scene_detection_onepass} and
 * {@code vp9_encodedframe_overshoot}), without lag, two-pass, VBR, spatial
 * layers or dynamic resize.
 */
final class Vp9RtRateControl {
    /**
     * The quantizer index range ({@code QINDEX_RANGE}).
     */
    static final int QINDEX_RANGE = 256;

    /**
     * The smallest frame target in bits ({@code FRAME_OVERHEAD_BITS}).
     */
    static final int FRAME_OVERHEAD_BITS = 200;

    /**
     * The fixed-point precision of bits per macroblock ({@code BPER_MB_NORMBITS}).
     */
    static final int BPER_MB_NORMBITS = 9;

    /**
     * The smallest rate correction factor ({@code MIN_BPB_FACTOR}).
     */
    static final double MIN_BPB_FACTOR = 0.005;

    /**
     * The largest rate correction factor ({@code MAX_BPB_FACTOR}).
     */
    static final double MAX_BPB_FACTOR = 50;

    /**
     * The key frame boost of one-pass coding ({@code DEFAULT_KF_BOOST}).
     */
    static final int DEFAULT_KF_BOOST = 2000;

    /**
     * The golden frame boost of one-pass coding ({@code DEFAULT_GF_BOOST}).
     */
    static final int DEFAULT_GF_BOOST = 2000;

    /**
     * The rate correction factor index of key frames ({@code KF_STD}).
     */
    static final int KF_STD = 4;

    /**
     * The rate correction factor index of inter frames ({@code INTER_NORMAL}).
     */
    static final int INTER_NORMAL = 0;

    /**
     * The shortest default golden frame interval ({@code MIN_GF_INTERVAL}).
     */
    static final int MIN_GF_INTERVAL = 4;

    /**
     * The longest default golden frame interval ({@code MAX_GF_INTERVAL}).
     */
    static final int MAX_GF_INTERVAL = 16;

    /**
     * The real-time minimum quantizer of each worst quantizer ({@code rtc_minq_8}).
     */
    static final int[] RTC_MINQ = new int[QINDEX_RANGE];

    /**
     * The low-motion key frame minimum quantizer ({@code kf_low_motion_minq_8}).
     */
    static final int[] KF_LOW_MOTION_MINQ = new int[QINDEX_RANGE];

    /**
     * The high-motion key frame minimum quantizer ({@code kf_high_motion_minq_8}).
     */
    static final int[] KF_HIGH_MOTION_MINQ = new int[QINDEX_RANGE];

    static {
        for (var i = 0; i < QINDEX_RANGE; i++) {
            var maxq = Vp9EncTables.qindexToQ(i);
            KF_LOW_MOTION_MINQ[i] = minqIndex(maxq, 0.000001, -0.0004, 0.150);
            KF_HIGH_MOTION_MINQ[i] = minqIndex(maxq, 0.0000021, -0.00125, 0.45);
            RTC_MINQ[i] = minqIndex(maxq, 0.00000271, -0.00113, 0.70);
        }
    }

    /**
     * The key frame boost below which the high-motion table applies ({@code kf_low}).
     */
    private static final int KF_LOW = 300;

    /**
     * The key frame boost above which the low-motion table applies ({@code kf_high}).
     */
    private static final int KF_HIGH = 4800;

    /**
     * The average quantizer index of key frames and inter frames ({@code avg_frame_qindex}).
     */
    int avgFrameQindexKey;

    /**
     * The average quantizer index of inter frames.
     */
    int avgFrameQindexInter;

    /**
     * The quantizer index of the last key frame ({@code last_q[KEY_FRAME]}).
     */
    int lastQKey;

    /**
     * The quantizer index of the last inter frame ({@code last_q[INTER_FRAME]}).
     */
    int lastQInter;

    /**
     * The buffer level in bits.
     */
    long bufferLevel;

    /**
     * The bits saved against the target ({@code bits_off_target}).
     */
    long bitsOffTarget;

    /**
     * The starting buffer level in bits.
     */
    long startingBufferLevel;

    /**
     * The optimal buffer level in bits.
     */
    long optimalBufferLevel;

    /**
     * The buffer size in bits.
     */
    long maximumBufferSize;

    /**
     * The average frame budget in bits.
     */
    int avgFrameBandwidth;

    /**
     * The average frame budget when the previous frame was coded or dropped.
     */
    int lastAvgFrameBandwidth;

    /**
     * The smallest frame target.
     */
    int minFrameBandwidth;

    /**
     * The largest frame target.
     */
    int maxFrameBandwidth;

    /**
     * The target of the frame being coded.
     */
    int thisFrameTarget;

    /**
     * The coded size of the last frame in bits.
     */
    int projectedFrameSize;

    /**
     * The frame target per 64x64 superblock, used by cyclic refresh.
     */
    int sb64TargetRate;

    /**
     * The rate correction factors per rate factor level.
     */
    final double[] rateCorrectionFactors = {1.0, 1.0, 1.0, 1.0, 1.0};

    /**
     * Whether the damped adjustment applies to each level.
     */
    final boolean[] dampedAdjustment = new boolean[5];

    /**
     * The quantizer index of the previous frame ({@code q_1_frame}).
     */
    int q1Frame;

    /**
     * The quantizer index of the frame before ({@code q_2_frame}).
     */
    int q2Frame;

    /**
     * The rate deviation sign of the previous frame ({@code rc_1_frame}).
     */
    int rc1Frame;

    /**
     * The rate deviation sign of the frame before ({@code rc_2_frame}).
     */
    int rc2Frame;

    /**
     * The best allowed quantizer index.
     */
    int bestQuality;

    /**
     * The worst allowed quantizer index.
     */
    int worstQuality;

    /**
     * The frames since the last key frame.
     */
    int framesSinceKey = 8;

    /**
     * The frames until the next key frame.
     */
    int framesToKey;

    /**
     * The frames since the last golden frame refresh.
     */
    int framesSinceGolden;

    /**
     * The frames until the next golden frame refresh.
     */
    int framesTillGfUpdateDue;

    /**
     * The golden frame interval.
     */
    int baselineGfInterval;

    /**
     * The smallest golden frame interval.
     */
    int minGfInterval;

    /**
     * The largest golden frame interval.
     */
    int maxGfInterval;

    /**
     * The key frame boost.
     */
    int kfBoost;

    /**
     * The golden frame boost.
     */
    int gfuBoost;

    /**
     * The percentage of recent blocks with low motion ({@code avg_frame_low_motion}).
     */
    int avgFrameLowMotion;

    /**
     * Whether a scene change was detected for the frame.
     */
    boolean highSourceSad;

    /**
     * Whether many blocks of the frame move.
     */
    boolean highNumBlocksWithMotion;

    /**
     * Whether the rate control was reset by a scene change.
     */
    boolean resetHighSourceSad;

    /**
     * Whether the next frame is forced to the worst quantizer.
     */
    boolean forceMaxQ;

    /**
     * Whether the frame is re-encoded at a high quantizer after a scene change.
     */
    boolean reEncodeMaxqSceneChange;

    /**
     * The average luma difference of superblocks between consecutive frames
     * ({@code avg_source_sad[0]}).
     */
    long avgSourceSad;

    /**
     * The quantizer index of the last boosted frame.
     */
    int lastBoostedQindex;

    /**
     * The quantizer index of the last key frame.
     */
    int lastKfQindex;

    /**
     * The rolling average of frame targets.
     */
    int rollingTargetBits;

    /**
     * The rolling average of coded sizes.
     */
    int rollingActualBits;

    /**
     * The total coded bits.
     */
    long totalActualBits;

    /**
     * The total budgeted bits.
     */
    long totalTargetBits;

    /**
     * The frames dropped in a row.
     */
    int framesDroppedInRow;

    /**
     * Creates an empty rate control state.
     */
    Vp9RtRateControl() {
    }

    /**
     * Returns the quantizer index whose step first reaches a cubic fit of a maximum step
     * ({@code get_minq_index}).
     *
     * @param maxq the maximum step
     * @param x3 the cubic coefficient
     * @param x2 the square coefficient
     * @param x1 the linear coefficient
     * @return the quantizer index
     */
    private static int minqIndex(double maxq, double x3, double x2, double x1) {
        var target = Math.min(((x3 * maxq + x2) * maxq + x1) * maxq, maxq);
        if (target <= 2.0) {
            return 0;
        }
        for (var i = 0; i < QINDEX_RANGE; i++) {
            if (target <= Vp9EncTables.qindexToQ(i)) {
                return i;
            }
        }
        return QINDEX_RANGE - 1;
    }

    /**
     * Copies every field of another state.
     *
     * @param o the other state
     */
    void copyFrom(Vp9RtRateControl o) {
        avgFrameQindexKey = o.avgFrameQindexKey;
        avgFrameQindexInter = o.avgFrameQindexInter;
        lastQKey = o.lastQKey;
        lastQInter = o.lastQInter;
        bufferLevel = o.bufferLevel;
        bitsOffTarget = o.bitsOffTarget;
        startingBufferLevel = o.startingBufferLevel;
        optimalBufferLevel = o.optimalBufferLevel;
        maximumBufferSize = o.maximumBufferSize;
        avgFrameBandwidth = o.avgFrameBandwidth;
        lastAvgFrameBandwidth = o.lastAvgFrameBandwidth;
        minFrameBandwidth = o.minFrameBandwidth;
        maxFrameBandwidth = o.maxFrameBandwidth;
        thisFrameTarget = o.thisFrameTarget;
        projectedFrameSize = o.projectedFrameSize;
        sb64TargetRate = o.sb64TargetRate;
        System.arraycopy(o.rateCorrectionFactors, 0, rateCorrectionFactors, 0, 5);
        System.arraycopy(o.dampedAdjustment, 0, dampedAdjustment, 0, 5);
        q1Frame = o.q1Frame;
        q2Frame = o.q2Frame;
        rc1Frame = o.rc1Frame;
        rc2Frame = o.rc2Frame;
        bestQuality = o.bestQuality;
        worstQuality = o.worstQuality;
        framesSinceKey = o.framesSinceKey;
        framesToKey = o.framesToKey;
        framesSinceGolden = o.framesSinceGolden;
        framesTillGfUpdateDue = o.framesTillGfUpdateDue;
        baselineGfInterval = o.baselineGfInterval;
        minGfInterval = o.minGfInterval;
        maxGfInterval = o.maxGfInterval;
        kfBoost = o.kfBoost;
        gfuBoost = o.gfuBoost;
        avgFrameLowMotion = o.avgFrameLowMotion;
        highSourceSad = o.highSourceSad;
        highNumBlocksWithMotion = o.highNumBlocksWithMotion;
        resetHighSourceSad = o.resetHighSourceSad;
        forceMaxQ = o.forceMaxQ;
        reEncodeMaxqSceneChange = o.reEncodeMaxqSceneChange;
        avgSourceSad = o.avgSourceSad;
        lastBoostedQindex = o.lastBoostedQindex;
        lastKfQindex = o.lastKfQindex;
        rollingTargetBits = o.rollingTargetBits;
        rollingActualBits = o.rollingActualBits;
        totalActualBits = o.totalActualBits;
        totalTargetBits = o.totalTargetBits;
        framesDroppedInRow = o.framesDroppedInRow;
    }

    /**
     * Initializes the state for a quantizer window ({@code vp9_rc_init}).
     *
     * @param best the best quantizer index
     * @param worst the worst quantizer index
     * @param framerate the frame rate
     * @param width the frame width
     * @param height the frame height
     */
    void init(int best, int worst, double framerate, int width, int height) {
        bestQuality = best;
        worstQuality = worst;
        avgFrameQindexKey = worst;
        avgFrameQindexInter = worst;
        lastQKey = best;
        lastQInter = worst;
        bufferLevel = startingBufferLevel;
        bitsOffTarget = startingBufferLevel;
        rollingTargetBits = avgFrameBandwidth;
        rollingActualBits = avgFrameBandwidth;
        avgFrameLowMotion = 0;
        highSourceSad = false;
        resetHighSourceSad = false;
        framesToKey = 0;
        framesSinceKey = 8;
        framesTillGfUpdateDue = 0;
        for (var i = 0; i < 5; i++) {
            rateCorrectionFactors[i] = 1.0;
            dampedAdjustment[i] = false;
        }
        setGfIntervalRange(framerate, width, height);
        baselineGfInterval = (minGfInterval + maxGfInterval) / 2;
        forceMaxQ = false;
    }

    /**
     * Sets the golden frame interval range of a frame rate ({@code vp9_rc_set_gf_interval_range}
     * with the default intervals).
     *
     * @param framerate the frame rate
     * @param width the frame width
     * @param height the frame height
     */
    void setGfIntervalRange(double framerate, int width, int height) {
        var factorSafe = 3840.0 * 2160 * 20.0;
        var factor = (double) width * height * framerate;
        var defaultInterval = Math.clamp(Math.round(framerate * 0.125), MIN_GF_INTERVAL, MAX_GF_INTERVAL);
        minGfInterval = factor <= factorSafe ? (int) defaultInterval
                : Math.max((int) defaultInterval, (int) Math.round(4 * factor / factorSafe));
        var interval = Math.min(MAX_GF_INTERVAL, (int) Math.round(framerate * 0.75));
        interval += interval & 1;
        maxGfInterval = Math.max(interval, minGfInterval);
        maxGfInterval = Math.min(maxGfInterval, 250);
        minGfInterval = Math.min(minGfInterval, maxGfInterval);
    }

    /**
     * Updates the frame budget for a bitrate and frame rate ({@code vp9_rc_update_framerate}).
     *
     * @param targetBandwidth the bitrate in bits per second
     * @param framerate the frame rate
     * @param mbs the number of 16x16 macroblocks of the frame
     */
    void updateFramerate(long targetBandwidth, double framerate, int mbs) {
        avgFrameBandwidth = (int) Math.min(Integer.MAX_VALUE, Math.round(targetBandwidth / framerate));
        minFrameBandwidth = FRAME_OVERHEAD_BITS;
        maxFrameBandwidth = Math.max(Math.max(mbs * 250, 4000000), (int) Math.min((long) avgFrameBandwidth * 2000 / 100,
                Integer.MAX_VALUE));
    }

    /**
     * Returns the bits per macroblock the model predicts at a quantizer
     * ({@code vp9_rc_bits_per_mb}).
     *
     * @param keyFrame whether the frame is a key frame
     * @param qindex the quantizer index
     * @param correctionFactor the rate correction factor
     * @return the bits per macroblock, scaled by {@code 1 << BPER_MB_NORMBITS}
     */
    static int bitsPerMb(boolean keyFrame, int qindex, double correctionFactor) {
        var q = Vp9EncTables.qindexToQ(qindex);
        var enumerator = keyFrame ? 2700000 : 1800000;
        enumerator += (int) (enumerator * q) >> 12;
        return (int) (enumerator * correctionFactor / q);
    }

    /**
     * Returns the frame size the model predicts at a quantizer ({@code vp9_estimate_bits_at_q}).
     *
     * @param keyFrame whether the frame is a key frame
     * @param q the quantizer index
     * @param mbs the number of macroblocks
     * @param correctionFactor the rate correction factor
     * @return the size in bits
     */
    static int estimateBitsAtQ(boolean keyFrame, int q, int mbs, double correctionFactor) {
        var bpm = bitsPerMb(keyFrame, q, correctionFactor);
        return Math.max(FRAME_OVERHEAD_BITS, (int) (((long) bpm * mbs) >> BPER_MB_NORMBITS));
    }

    /**
     * Returns the quantizer index delta that changes the step from one value to another
     * ({@code vp9_compute_qdelta}).
     *
     * @param qstart the starting step
     * @param qtarget the target step
     * @return the delta
     */
    int computeQdelta(double qstart, double qtarget) {
        var startIndex = worstQuality;
        var targetIndex = worstQuality;
        for (var i = bestQuality; i < worstQuality; i++) {
            startIndex = i;
            if (Vp9EncTables.qindexToQ(i) >= qstart) {
                break;
            }
        }
        for (var i = bestQuality; i < worstQuality; i++) {
            targetIndex = i;
            if (Vp9EncTables.qindexToQ(i) >= qtarget) {
                break;
            }
        }
        return targetIndex - startIndex;
    }

    /**
     * Returns the quantizer index delta that scales the modeled rate by a ratio
     * ({@code vp9_compute_qdelta_by_rate}).
     *
     * @param keyFrame whether the frame is a key frame
     * @param qindex the base quantizer index
     * @param rateTargetRatio the rate ratio
     * @return the delta
     */
    int computeQdeltaByRate(boolean keyFrame, int qindex, double rateTargetRatio) {
        var targetIndex = worstQuality;
        var base = bitsPerMb(keyFrame, qindex, 1.0);
        var target = (int) (rateTargetRatio * base);
        for (var i = bestQuality; i < worstQuality; i++) {
            if (bitsPerMb(keyFrame, i, 1.0) <= target) {
                targetIndex = i;
                break;
            }
        }
        return targetIndex - qindex;
    }

    /**
     * Returns the rate correction factor of the frame type ({@code get_rate_correction_factor}).
     *
     * @param keyFrame whether the frame is a key frame
     * @return the factor
     */
    double correctionFactor(boolean keyFrame) {
        var rcf = rateCorrectionFactors[keyFrame ? KF_STD : INTER_NORMAL];
        return Math.clamp(rcf, MIN_BPB_FACTOR, MAX_BPB_FACTOR);
    }

    /**
     * Returns the inter frame target ({@code vp9_calc_pframe_target_size_one_pass_cbr}).
     *
     * @param underShootPct the largest decrease in percent
     * @param overShootPct the largest increase in percent
     * @param maxInterPct the largest inter frame size as a percentage of the budget, or 0
     * @param layerFrameSize the layer's average frame size with temporal layers, or 0
     * @return the target in bits
     */
    int pframeTarget(int underShootPct, int overShootPct, int maxInterPct, int layerFrameSize) {
        var diff = optimalBufferLevel - bufferLevel;
        var onePctBits = 1 + optimalBufferLevel / 100;
        var minFrameTarget = Math.max(avgFrameBandwidth >> 4, FRAME_OVERHEAD_BITS);
        long target = avgFrameBandwidth;
        if (layerFrameSize > 0) {
            target = layerFrameSize;
            minFrameTarget = Math.max(layerFrameSize >> 4, FRAME_OVERHEAD_BITS);
        }
        if (diff > 0) {
            var pctLow = (int) Math.min(diff / onePctBits, underShootPct);
            target -= (target * pctLow) / 200;
        } else if (diff < 0) {
            var pctHigh = (int) Math.min(-diff / onePctBits, overShootPct);
            target += (target * pctHigh) / 200;
        }
        if (maxInterPct > 0) {
            target = Math.min(target, (long) avgFrameBandwidth * maxInterPct / 100);
        }
        target = Math.min(target, Integer.MAX_VALUE);
        return Math.max(minFrameTarget, (int) target);
    }

    /**
     * Returns the key frame target ({@code vp9_calc_iframe_target_size_one_pass_cbr}).
     *
     * @param firstFrame whether this is the first frame
     * @param framerate the frame rate, of the layer with temporal layers
     * @param maxIntraPct the largest key frame size as a percentage of the budget, or 0
     * @return the target in bits
     */
    int iframeTarget(boolean firstFrame, double framerate, int maxIntraPct) {
        long target;
        if (firstFrame) {
            target = startingBufferLevel / 2;
        } else {
            var boost = Math.max(32, (int) Math.round(2 * framerate - 16));
            if (framesSinceKey < framerate / 2) {
                boost = (int) Math.round(boost * framesSinceKey / (framerate / 2));
            }
            target = ((long) (16 + boost) * avgFrameBandwidth) >> 4;
        }
        target = Math.min(Integer.MAX_VALUE, target);
        if (maxIntraPct > 0) {
            target = Math.min(target, (long) avgFrameBandwidth * maxIntraPct / 100);
        }
        if (target > maxFrameBandwidth) {
            target = maxFrameBandwidth;
        }
        return (int) target;
    }

    /**
     * Sets the frame target and the derived superblock target ({@code vp9_rc_set_frame_target}).
     *
     * @param target the target in bits
     * @param width the frame width
     * @param height the frame height
     */
    void setFrameTarget(int target, int width, int height) {
        thisFrameTarget = target;
        sb64TargetRate = (int) Math.min(((long) target * 64 * 64) / ((long) width * height), Integer.MAX_VALUE);
    }

    /**
     * Adds the frame budget to the buffer before coding ({@code vp9_update_buffer_level_preencode}).
     */
    void updateBufferPreencode() {
        bitsOffTarget += avgFrameBandwidth;
        bitsOffTarget = Math.min(bitsOffTarget, maximumBufferSize);
        bufferLevel = bitsOffTarget;
    }

    /**
     * Returns the worst quantizer the buffer allows for the frame
     * ({@code calc_active_worst_quality_one_pass_cbr}).
     *
     * @param keyFrame whether the frame is a key frame
     * @param currentFrame the number of frames coded
     * @param temporalLayers the number of temporal layers
     * @return the quantizer index
     */
    int activeWorstQuality(boolean keyFrame, long currentFrame, int temporalLayers) {
        var criticalLevel = optimalBufferLevel >> 3;
        if (keyFrame || resetHighSourceSad || forceMaxQ) {
            return worstQuality;
        }
        var numFramesWeightKey = 5L * temporalLayers;
        var ambientQp = currentFrame < numFramesWeightKey ? Math.min(avgFrameQindexInter, avgFrameQindexKey)
                : avgFrameQindexInter;
        var active = Math.min(worstQuality, (ambientQp * 5) >> 2);
        if (bufferLevel > optimalBufferLevel) {
            var maxAdjustmentDown = active / 3;
            if (maxAdjustmentDown != 0) {
                var step = (maximumBufferSize - optimalBufferLevel) / maxAdjustmentDown;
                var adjustment = 0;
                if (step != 0) {
                    adjustment = (int) ((bufferLevel - optimalBufferLevel) / step);
                }
                active -= adjustment;
            }
        } else if (bufferLevel > criticalLevel) {
            if (criticalLevel != 0) {
                var step = optimalBufferLevel - criticalLevel;
                var adjustment = 0;
                if (step != 0) {
                    adjustment = (int) ((worstQuality - ambientQp) * (optimalBufferLevel - bufferLevel) / step);
                }
                active = ambientQp + adjustment;
            }
        } else {
            active = worstQuality;
        }
        return active;
    }

    /**
     * The best quantizer of the last {@link #pickQ}.
     */
    int bottomIndex;

    /**
     * The worst quantizer of the last {@link #pickQ}.
     */
    int topIndex;

    /**
     * Picks the frame quantizer and its bounds ({@code rc_pick_q_and_bounds_one_pass_cbr}).
     *
     * @param keyFrame whether the frame is a key frame
     * @param currentFrame the number of frames coded
     * @param temporalLayers the number of temporal layers
     * @param width the frame width
     * @param height the frame height
     * @param bitsPerMb the bits-per-macroblock model at each quantizer, or {@code null} for
     * the plain model
     * @param mbs the number of macroblocks
     * @return the quantizer index
     */
    int pickQ(boolean keyFrame, long currentFrame, int temporalLayers, int width, int height, int[] bitsPerMb,
              int mbs) {
        var activeWorst = activeWorstQuality(keyFrame, currentFrame, temporalLayers);
        int activeBest;
        if (keyFrame) {
            activeBest = bestQuality;
            if (currentFrame > 0) {
                activeBest = kfActiveQuality(avgFrameQindexKey);
                var qAdj = 1.0;
                if ((long) width * height <= 352 * 288) {
                    qAdj -= 0.25;
                }
                var qVal = Vp9EncTables.qindexToQ(activeBest);
                activeBest += computeQdelta(qVal, qVal * qAdj);
            }
        } else if (currentFrame > 1) {
            activeBest = avgFrameQindexInter < activeWorst ? RTC_MINQ[avgFrameQindexInter] : RTC_MINQ[activeWorst];
        } else {
            activeBest = avgFrameQindexKey < activeWorst ? RTC_MINQ[avgFrameQindexKey] : RTC_MINQ[activeWorst];
        }
        activeBest = Math.clamp(activeBest, bestQuality, worstQuality);
        activeWorst = Math.clamp(activeWorst, activeBest, worstQuality);
        topIndex = activeWorst;
        bottomIndex = activeBest;
        var q = regulateQ(keyFrame, thisFrameTarget, activeBest, activeWorst, bitsPerMb, mbs);
        if (q > topIndex) {
            if (thisFrameTarget >= maxFrameBandwidth) {
                topIndex = q;
            } else {
                q = topIndex;
            }
        }
        return q;
    }

    /**
     * Returns the key frame best quantizer of a quantizer ({@code get_kf_active_quality}).
     *
     * @param q the quantizer index
     * @return the best quantizer index
     */
    private int kfActiveQuality(int q) {
        if (kfBoost > KF_HIGH) {
            return KF_LOW_MOTION_MINQ[q];
        }
        if (kfBoost < KF_LOW) {
            return KF_HIGH_MOTION_MINQ[q];
        }
        var gap = KF_HIGH - KF_LOW;
        var offset = KF_HIGH - kfBoost;
        var qdiff = KF_HIGH_MOTION_MINQ[q] - KF_LOW_MOTION_MINQ[q];
        var adjustment = ((offset * qdiff) + (gap >> 1)) / gap;
        return KF_LOW_MOTION_MINQ[q] + adjustment;
    }

    /**
     * Finds the quantizer whose modeled size meets the frame target ({@code vp9_rc_regulate_q}
     * followed by {@code adjust_q_cbr}).
     *
     * @param keyFrame whether the frame is a key frame
     * @param target the frame target
     * @param activeBest the best allowed quantizer
     * @param activeWorst the worst allowed quantizer
     * @param model the bits-per-macroblock model at each quantizer, or {@code null} for the
     * plain model
     * @param mbs the number of macroblocks
     * @return the quantizer index
     */
    int regulateQ(boolean keyFrame, int target, int activeBest, int activeWorst, int[] model, int mbs) {
        var q = activeWorst;
        var lastError = Integer.MAX_VALUE;
        var cf = correctionFactor(keyFrame);
        var targetBitsPerMb = (int) (((long) target << BPER_MB_NORMBITS) / mbs);
        var i = activeBest;
        do {
            var bitsAtQ = model != null ? model[i] : bitsPerMb(keyFrame, i, cf);
            var diff = (int) Math.min(Math.max((long) targetBitsPerMb - bitsAtQ, -Integer.MAX_VALUE),
                    Integer.MAX_VALUE);
            if (bitsAtQ <= targetBitsPerMb) {
                q = diff <= lastError ? i : i - 1;
                break;
            }
            lastError = -diff;
        } while (++i <= activeWorst);
        return adjustQCbr(q);
    }

    /**
     * Keeps the quantizer between the two previous ones when the rate oscillates
     * ({@code adjust_q_cbr}).
     *
     * @param q the quantizer index
     * @return the adjusted index
     */
    private int adjustQCbr(int q) {
        if (!resetHighSourceSad && rc1Frame * rc2Frame == -1 && q1Frame != q2Frame) {
            var qclamp = Math.clamp(q, Math.min(q1Frame, q2Frame), Math.max(q1Frame, q2Frame));
            if (rc1Frame == -1 && q > qclamp) {
                q = (q + qclamp) >> 1;
            } else {
                q = qclamp;
            }
        }
        return Math.max(Math.min(q, worstQuality), bestQuality);
    }

    /**
     * Updates the rate correction factor from the coded size of the frame
     * ({@code vp9_rc_update_rate_correction_factors}).
     *
     * @param keyFrame whether the frame was a key frame
     * @param projectedOnQ the size the model predicted at the frame's quantizer
     * @param baseQindex the frame's quantizer index
     */
    void updateRateCorrectionFactors(boolean keyFrame, int projectedOnQ, int baseQindex) {
        var correction = 100;
        var rcf = correctionFactor(keyFrame);
        if (projectedOnQ > FRAME_OVERHEAD_BITS) {
            correction = (int) ((100L * projectedFrameSize) / projectedOnQ);
        }
        double adjustmentLimit;
        if (!dampedAdjustment[INTER_NORMAL]) {
            adjustmentLimit = 1.0;
            dampedAdjustment[INTER_NORMAL] = true;
        } else {
            adjustmentLimit = 0.25 + 0.5 * Math.min(1, Math.abs(StrictMath.log10(0.01 * correction)));
        }
        q2Frame = q1Frame;
        q1Frame = baseQindex;
        rc2Frame = rc1Frame;
        if (correction > 110) {
            rc1Frame = -1;
        } else if (correction < 90) {
            rc1Frame = 1;
        } else {
            rc1Frame = 0;
        }
        if (rc1Frame == -1 && rc2Frame == 1 && correction > 1000) {
            rc2Frame = 0;
        }
        if (correction > 102) {
            correction = (int) (100 + ((correction - 100) * adjustmentLimit));
            rcf = (rcf * correction) / 100;
            if (rcf > MAX_BPB_FACTOR) {
                rcf = MAX_BPB_FACTOR;
            }
        } else if (correction < 99) {
            correction = (int) (100 - ((100 - correction) * adjustmentLimit));
            rcf = (rcf * correction) / 100;
            if (rcf < MIN_BPB_FACTOR) {
                rcf = MIN_BPB_FACTOR;
            }
        }
        rateCorrectionFactors[keyFrame ? KF_STD : INTER_NORMAL] = Math.clamp(rcf, MIN_BPB_FACTOR, MAX_BPB_FACTOR);
    }

    /**
     * Removes the coded size from the buffer ({@code update_buffer_level_postencode}).
     *
     * @param size the coded size in bits
     */
    void updateBufferPostencode(int size) {
        bitsOffTarget -= size;
        bitsOffTarget = Math.min(bitsOffTarget, maximumBufferSize);
        bufferLevel = bitsOffTarget;
    }
}
