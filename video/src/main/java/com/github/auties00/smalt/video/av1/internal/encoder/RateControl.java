package com.github.auties00.smalt.video.av1.internal.encoder;

/**
 * Chooses the frame size targets and quantizers of a one-pass constant bitrate stream from a
 * leaky bucket model of the decoder's buffer, and learns how many bits each quantizer costs.
 *
 * <p>A frame goes through {@link #dropFrame}, {@link #frameTarget}, {@link #pickQindex} and then
 * {@link #postEncode} once it is coded, or {@link #postDrop} when it is dropped. With a key frame
 * limit, a key frame that overshoots its target may be coded again at the quantizer
 * {@link #recodeKeyQindex} returns; a
 * scene change is coded at the quantizer {@link #sceneChangeQindex} returns, and a coded frame may
 * still be dropped when {@link #dropCoded} says so. With a key frame limit, a key frame after the
 * first may reach the worst quantizer, stopped at the quantizer that spends twice the worst
 * quantizer's rate, so the limit holds at low bitrates. The state survives a change of picture
 * size: {@link #resize} rescales it for the new size.
 *
 * @implNote This implementation ports libaom's one-pass real-time CBR rate control
 * ({@code av1/encoder/ratectrl.c}, from {@code av1_rc_init} to {@code av1_postencode_drop_cbr}) for
 * one spatial layer without golden boost. The key frame recode, which libaom's real-time mode does
 * not have, is the overshoot branch of its two-pass recode loop ({@code av1/encoder/rc_utils.h})
 * restricted to key frames: a key frame several times its target starves the frames after it.
 */
final class RateControl {
    /**
     * The smallest number of bits a frame is assumed to cost.
     */
    private static final int FRAME_OVERHEAD_BITS = 200;

    /**
     * The fixed-point shift of the bits per macroblock.
     */
    static final int BPER_MB_NORMBITS = 9;

    /**
     * The smallest rate correction factor.
     */
    private static final double MIN_BPB_FACTOR = 0.005;

    /**
     * The largest rate correction factor.
     */
    private static final double MAX_BPB_FACTOR = 50;

    /**
     * The key frame boost below which the high-motion minimum quantizers apply.
     */
    static final int KF_LOW = 400;

    /**
     * The key frame boost above which the low-motion minimum quantizers apply.
     */
    static final int KF_HIGH = 5000;

    /**
     * The key frame boost of the real-time encoder ({@code DEFAULT_KF_BOOST_RT}).
     */
    private static final int KF_BOOST_RT = 2300;

    /**
     * The tolerance, in percent of the target, above which a key frame is coded again
     * ({@code recode_tolerance} of libaom's speed features at the faster two-pass speeds).
     */
    private static final int KEY_RECODE_TOLERANCE = 50;

    /**
     * The minimum key frame quantizer index of each quantizer index, for low motion
     * ({@code kf_low_motion_minq}).
     */
    static final int[] KF_LOW_MINQ = new int[256];

    /**
     * The minimum key frame quantizer index of each quantizer index, for high motion
     * ({@code kf_high_motion_minq}).
     */
    static final int[] KF_HIGH_MINQ = new int[256];

    /**
     * The minimum inter frame quantizer index of each quantizer index ({@code rtc_minq}).
     */
    private static final int[] RTC_MINQ = new int[256];

    static {
        for (var i = 0; i < 256; i++) {
            var maxq = qindexToQ(i);
            KF_LOW_MINQ[i] = minqIndex(maxq, 0.000001, -0.0004, 0.150);
            KF_HIGH_MINQ[i] = minqIndex(maxq, 0.0000021, -0.00125, 0.45);
            RTC_MINQ[i] = minqIndex(maxq, 0.00000271, -0.00113, 0.70);
        }
    }

    /**
     * The frame rate.
     */
    private double framerate;

    /**
     * The smallest quantizer index.
     */
    private int bestQuality;

    /**
     * The largest quantizer index.
     */
    private int worstQuality = 255;

    /**
     * The percentage of the buffer deficit a frame target may give back.
     */
    private int underShootPct = 50;

    /**
     * The percentage of the buffer surplus a frame target may spend.
     */
    private int overShootPct = 50;

    /**
     * The largest key frame target as a percentage of the average frame size, 0 for no limit.
     */
    private int maxIntraBitratePct;

    /**
     * The buffer fullness, as a percentage of the optimal level, below which frames are dropped;
     * 0 disables dropping.
     */
    private int dropWaterMark;

    /**
     * The largest number of consecutive dropped frames, 0 for no limit.
     */
    private int maxConsecDrop;

    /**
     * The number of 16x16 macroblocks of a frame.
     */
    private int mbs;

    /**
     * The number of luma samples of a frame.
     */
    private long pixels;

    /**
     * The average frame size in bits.
     */
    private int avgFrameBandwidth;

    /**
     * The average frame size of the previous frame's configuration.
     */
    private int prevAvgFrameBandwidth;

    /**
     * The largest frame size in bits.
     */
    private int maxFrameBandwidth;

    /**
     * The initial buffer level in bits.
     */
    private long startingBufferLevel;

    /**
     * The optimal buffer level in bits.
     */
    private long optimalBufferLevel;

    /**
     * The buffer size in bits.
     */
    private long maximumBufferSize;

    /**
     * The buffer level in bits.
     */
    private long bufferLevel;

    /**
     * The average quantizer index of key frames.
     */
    private int avgQindexKey;

    /**
     * The average quantizer index of inter frames.
     */
    private int avgQindexInter;

    /**
     * The rate correction factor of key frames.
     */
    private double rcfKey = 1.0;

    /**
     * The rate correction factor of inter frames.
     */
    private double rcfInter = 0.7;

    /**
     * The quantizer index of the previous frame.
     */
    private int q1Frame;

    /**
     * The quantizer index of the frame before the previous one.
     */
    private int q2Frame;

    /**
     * Whether the previous frame overshot (-1), undershot (1) or met (0) its estimate.
     */
    private int rc1Frame;

    /**
     * The estimate outcome of the frame before the previous one.
     */
    private int rc2Frame;

    /**
     * The number of coded frames since the last key frame.
     */
    int framesSinceKey = 8;

    /**
     * The number of coded frames.
     */
    private long frameNumber;

    /**
     * The target of the frame being coded, in bits.
     */
    private int thisFrameTarget;

    /**
     * The current frame-drop decimation factor.
     */
    private int decimationFactor;

    /**
     * The frames left to drop in the current decimation.
     */
    private int decimationCount;

    /**
     * The number of consecutive dropped frames.
     */
    private int dropCountConsec;

    /**
     * The average size of this temporal layer's own frames, the target of its inter frames, or 0
     * without temporal layers ({@code avg_frame_size} of the layer context).
     */
    int layerFrameSize;

    /**
     * The cyclic refresh whose boosted segments the rate model weights in, or {@code null}.
     */
    CyclicRefresh cyclicRefresh;

    /**
     * The coded shares of the boosted segments of the last frame.
     */
    private final double[] weights = new double[2];

    /**
     * The number of temporal layers of the stream, which scales the first key frame's target.
     */
    int temporalLayers = 1;

    /**
     * The mean variance per sample of the 64x64 luma blocks of the scene change being coded
     * ({@code frame_spatial_variance}), set through {@link #spatialActivity}.
     */
    private int frameSpatialVariance;

    /**
     * The quantizer index of the last inter frame ({@code last_q[INTER_FRAME]}).
     */
    private int lastQindexInter = 255;

    /**
     * Whether the frame being coded is a scene change coded at the overshoot quantizer, whose
     * size does not update the rate correction factors.
     */
    private boolean sceneChangeMaxQ;

    /**
     * Whether the next frame is coded at the worst quantizer after a frame was dropped once coded
     * ({@code force_max_q}).
     */
    private boolean forceMaxQ;

    /**
     * The smallest quantizer index a key frame recode may use ({@code bottom_index}).
     */
    private int recodeLow;

    /**
     * The largest quantizer index a key frame recode may use ({@code top_index}).
     */
    private int recodeHigh;

    /**
     * Creates a rate control.
     */
    RateControl() {
    }

    /**
     * Converts a quantizer index to the quantizer scale the rate model uses
     * ({@code av1_convert_qindex_to_q}).
     *
     * @param qindex the quantizer index
     * @return the scale
     */
    static double qindexToQ(int qindex) {
        return Quantizer.acQuant(qindex, 0) / 4.0;
    }

    /**
     * Returns the smallest quantizer index of a scale at least {@code q} within a range
     * ({@code av1_find_qindex}).
     *
     * @param q the scale
     * @param best the smallest index
     * @param worst the largest index
     * @return the index
     */
    static int findQindex(double q, int best, int worst) {
        var low = best;
        var high = worst;
        while (low < high) {
            var mid = (low + high) >> 1;
            if (qindexToQ(mid) < q) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return low;
    }

    /**
     * Returns the minimum quantizer index a third-order fit gives for a maximum quantizer
     * ({@code get_minq_index}).
     *
     * @param maxq the maximum quantizer scale
     * @param x3 the cubic coefficient
     * @param x2 the quadratic coefficient
     * @param x1 the linear coefficient
     * @return the index
     */
    static int minqIndex(double maxq, double x3, double x2, double x1) {
        var target = Math.min(((x3 * maxq + x2) * maxq + x1) * maxq, maxq);
        if (target <= 2.0) {
            return 0;
        }
        return findQindex(target, 0, 255);
    }

    /**
     * Configures the targets and the buffer model; a running stream keeps its buffer level,
     * clipped to the new buffer size.
     *
     * @param bitrate the target bitrate in bits per second
     * @param framerate the frame rate
     * @param minQindex the smallest quantizer index
     * @param maxQindex the largest quantizer index
     * @param bufferMs the buffer size in milliseconds
     * @param initialMs the initial buffer level in milliseconds
     * @param optimalMs the optimal buffer level in milliseconds
     * @param dropWaterMark the drop threshold in percent of the optimal level, 0 to disable
     * @param maxConsecDrop the largest number of consecutive drops, 0 for no limit
     * @param maxIntraPct the largest key frame target in percent of the average frame size,
     * 0 for no limit
     * @param width the picture width
     * @param height the picture height
     * @param reset whether the buffer starts again at its initial level
     */
    void configure(long bitrate, double framerate, int minQindex, int maxQindex, int bufferMs, int initialMs,
                   int optimalMs, int dropWaterMark, int maxConsecDrop, int maxIntraPct, int width, int height,
                   boolean reset) {
        this.framerate = framerate;
        this.bestQuality = minQindex;
        this.worstQuality = maxQindex;
        this.dropWaterMark = dropWaterMark;
        this.maxConsecDrop = maxConsecDrop;
        this.maxIntraBitratePct = maxIntraPct;
        var miCols = ((width + 7) & ~7) >> 2;
        var miRows = ((height + 7) & ~7) >> 2;
        this.mbs = ((miCols + 2) >> 2) * ((miRows + 2) >> 2);
        this.pixels = (long) width * height;
        prevAvgFrameBandwidth = avgFrameBandwidth;
        avgFrameBandwidth = (int) Math.min(Integer.MAX_VALUE, Math.round(bitrate / framerate));
        var vbrMax = (long) avgFrameBandwidth * 2000 / 100;
        maxFrameBandwidth = (int) Math.max(Math.max((long) mbs * 250, 2025000), Math.min(vbrMax, Integer.MAX_VALUE));
        startingBufferLevel = (long) initialMs * bitrate / 1000;
        optimalBufferLevel = optimalMs == 0 ? bitrate / 8 : (long) optimalMs * bitrate / 1000;
        maximumBufferSize = bufferMs == 0 ? bitrate / 8 : (long) bufferMs * bitrate / 1000;
        if (reset) {
            bufferLevel = startingBufferLevel;
            avgQindexKey = worstQuality;
            avgQindexInter = worstQuality;
            rcfKey = 1.0;
            rcfInter = 0.7;
            framesSinceKey = 8;
            frameNumber = 0;
            q1Frame = q2Frame = 0;
            rc1Frame = rc2Frame = 0;
            prevAvgFrameBandwidth = avgFrameBandwidth;
            lastQindexInter = worstQuality;
            forceMaxQ = false;
        }
        bufferLevel = Math.min(bufferLevel, maximumBufferSize);
    }

    /**
     * Carries the state over a change of the coded picture size ({@code resize_reset_rc}): the
     * buffer restarts at its optimal level, the inter frame quantizer history moves toward the
     * worst quantizer when the picture grows, and the inter frame rate correction factor is
     * lowered when the regulated quantizer of the new size would sit near the worst one after a
     * reduction, or would jump far above the last one after a growth, and raised when it would
     * stay near the last one after a growth.
     *
     * <p>The caller has already configured the new size through {@link #configure} without a
     * reset.
     *
     * @param prevPixels the number of luma samples of the previous size
     */
    void resize(long prevPixels) {
        var scale = (double) pixels / prevPixels;
        bufferLevel = optimalBufferLevel;
        var target = frameTarget(false);
        if (scale > 4.0) {
            avgQindexInter = worstQuality;
        } else if (scale > 1.0) {
            avgQindexInter = (avgQindexInter + worstQuality) >> 1;
        }
        var worst = Math.clamp(activeWorstQuality(false), bestQuality, worstQuality);
        var targetBpm = (int) Math.min(Integer.MAX_VALUE, ((long) target << BPER_MB_NORMBITS) / mbs);
        var qindex = closestQindexByRate(false, targetBpm, rcfInter, bestQuality, worst);
        if (scale < 1.0 && qindex > 90 * worstQuality / 100) {
            rcfInter *= 0.85;
        }
        if (scale >= 1.0) {
            if (scale < 4.0 && qindex > 130 * lastQindexInter / 100) {
                rcfInter *= 0.8;
            }
            if (qindex <= 120 * lastQindexInter / 100) {
                rcfInter *= 1.5;
            }
        }
        rcfInter = Math.clamp(rcfInter, MIN_BPB_FACTOR, MAX_BPB_FACTOR);
    }

    /**
     * Records the spatial activity of the scene change being coded, which
     * {@link #sceneChangeQindex} reads ({@code rc_spatial_act_onepass_rt}).
     *
     * @param variance the mean variance per sample of the 64x64 luma blocks
     */
    void spatialActivity(int variance) {
        frameSpatialVariance = variance;
    }

    /**
     * Returns whether key frames are limited to a share of the average frame size, which also lets
     * a key frame's quantizer reach the worst one and a scene change's quantizer follow its spatial
     * activity.
     *
     * @return {@code true} when a key frame limit is set
     */
    boolean limitsKeyFrames() {
        return maxIntraBitratePct > 0;
    }

    /**
     * Returns the buffer level in bits.
     *
     * @return the level
     */
    long bufferLevel() {
        return bufferLevel;
    }

    /**
     * Returns the average frame size in bits.
     *
     * @return the size
     */
    int avgFrameBandwidth() {
        return avgFrameBandwidth;
    }

    /**
     * Returns the running average quantizer index of inter frames.
     *
     * @return the index
     */
    int avgQindexInter() {
        return avgQindexInter;
    }

    /**
     * Returns the target of the frame being coded.
     *
     * @return the target in bits
     */
    int thisFrameTarget() {
        return thisFrameTarget;
    }

    /**
     * Decides whether a frame is dropped to let the buffer refill ({@code av1_rc_drop_frame}).
     *
     * @param key whether the frame is a key frame
     * @return {@code true} when the frame is dropped
     */
    boolean dropFrame(boolean key) {
        if (key || dropWaterMark == 0 || (maxConsecDrop > 0 && dropCountConsec >= maxConsecDrop)) {
            return false;
        }
        if (bufferLevel <= -1) {
            dropCountConsec++;
            return true;
        }
        var dropMark = dropWaterMark * optimalBufferLevel / 100;
        var below = bufferLevel <= dropMark;
        if (!below && decimationFactor > 0) {
            decimationFactor--;
        } else if (below && decimationFactor == 0) {
            decimationFactor = 1;
        }
        if (decimationFactor > 0) {
            if (decimationCount > 0) {
                decimationCount--;
                dropCountConsec++;
                return true;
            }
            decimationCount = decimationFactor;
            return false;
        }
        decimationCount = 0;
        return false;
    }

    /**
     * Computes the size target of the next frame ({@code av1_calc_iframe_target_size_one_pass_cbr}
     * and {@code av1_calc_pframe_target_size_one_pass_cbr}).
     *
     * @param key whether the frame is a key frame
     * @return the target in bits
     */
    int frameTarget(boolean key) {
        long target;
        if (key) {
            if (frameNumber == 0) {
                target = startingBufferLevel / 2;
            } else {
                var kfBoost = Math.max(32, (int) Math.round(2 * framerate - 16));
                if (framesSinceKey < framerate / 2) {
                    kfBoost = (int) (kfBoost * framesSinceKey / (framerate / 2));
                }
                target = ((16L + kfBoost) * avgFrameBandwidth) >> 4;
            }
            if (frameNumber == 0 && temporalLayers > 1) {
                target <<= Math.min(2, temporalLayers - 1);
            }
            if (maxIntraBitratePct > 0) {
                target = Math.min(target, (long) avgFrameBandwidth * maxIntraBitratePct / 100);
            }
            target = Math.min(target, maxFrameBandwidth);
        } else {
            var diff = optimalBufferLevel - bufferLevel;
            var onePct = 1 + optimalBufferLevel / 100;
            var minTarget = Math.max(avgFrameBandwidth >> 4, FRAME_OVERHEAD_BITS);
            target = avgFrameBandwidth;
            if (layerFrameSize > 0) {
                target = layerFrameSize;
                minTarget = Math.max(layerFrameSize >> 4, FRAME_OVERHEAD_BITS);
            }
            if (diff > 0) {
                var pctLow = (int) Math.min(diff / onePct, underShootPct);
                target -= target * pctLow / 200;
            } else if (diff < 0) {
                var pctHigh = (int) Math.min(-diff / onePct, overShootPct);
                target += target * pctHigh / 200;
            }
            target = Math.max(minTarget, Math.min(target, Integer.MAX_VALUE));
        }
        thisFrameTarget = (int) Math.min(target, Integer.MAX_VALUE);
        return thisFrameTarget;
    }

    /**
     * Returns the estimated bits per macroblock of a quantizer index, in
     * {@code 1 << BPER_MB_NORMBITS} units ({@code av1_rc_bits_per_mb}).
     *
     * @param key whether the frame is a key frame
     * @param qindex the quantizer index
     * @param factor the rate correction factor
     * @return the estimate
     */
    private static int bitsPerMb(boolean key, int qindex, double factor) {
        var enumerator = key ? 2000000 : 1500000;
        return (int) (enumerator * factor / qindexToQ(qindex));
    }

    /**
     * Returns the estimated bits per macroblock of a quantizer index for the frame being coded,
     * weighting in the cyclic refresh's boosted segments when it applies
     * ({@code get_bits_per_mb} and {@code av1_cyclic_refresh_rc_bits_per_mb}).
     *
     * @param key whether the frame is a key frame
     * @param qindex the quantizer index
     * @param factor the rate correction factor
     * @return the estimate
     */
    private int modelBitsPerMb(boolean key, int qindex, double factor) {
        var cr = cyclicRefresh;
        if (key || cr == null || !cr.apply) {
            return bitsPerMb(key, qindex, factor);
        }
        var weight = cr.segmentWeight();
        var deltaq = qdeltaByRate(false, qindex, cr.rateRatioQdelta());
        if (-deltaq > cr.maxQdeltaPerc() * qindex / 100) {
            deltaq = -cr.maxQdeltaPerc() * qindex / 100;
        }
        return (int) Math.round((1.0 - weight) * bitsPerMb(false, qindex, factor)
                + weight * bitsPerMb(false, qindex + deltaq, factor));
    }

    /**
     * Returns the estimated size of the frame at a quantizer index ({@code av1_estimate_bits_at_q}).
     *
     * @param key whether the frame is a key frame
     * @param qindex the quantizer index
     * @param factor the rate correction factor
     * @return the estimate in bits
     */
    private int estimateBits(boolean key, int qindex, double factor) {
        var bpm = bitsPerMb(key, qindex, factor);
        return Math.max(FRAME_OVERHEAD_BITS, (int) (((long) bpm * mbs) >> BPER_MB_NORMBITS));
    }

    /**
     * Returns the quantizer index whose estimated rate is closest to a target
     * ({@code find_closest_qindex_by_rate}).
     *
     * @param key whether the frame is a key frame
     * @param bitsPerMb the target bits per macroblock
     * @param factor the rate correction factor
     * @param best the smallest index
     * @param worst the largest index
     * @return the index
     */
    private int closestQindexByRate(boolean key, int bitsPerMb, double factor, int best, int worst) {
        var low = best;
        var high = worst;
        while (low < high) {
            var mid = (low + high) >> 1;
            if (modelBitsPerMb(key, mid, factor) > bitsPerMb) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        var cur = modelBitsPerMb(key, low, factor);
        var curDiff = cur <= bitsPerMb ? bitsPerMb - cur : Integer.MAX_VALUE;
        int prevDiff;
        if (curDiff == Integer.MAX_VALUE || low == best) {
            prevDiff = Integer.MAX_VALUE;
        } else {
            prevDiff = modelBitsPerMb(key, low - 1, factor) - bitsPerMb;
        }
        return curDiff <= prevDiff ? low : low - 1;
    }

    /**
     * Returns the quantizer index difference that scales the estimated rate by a ratio
     * ({@code av1_compute_qdelta_by_rate}).
     *
     * @param key whether the frame is a key frame
     * @param qindex the starting index
     * @param ratio the rate ratio
     * @return the difference
     */
    int qdeltaByRate(boolean key, int qindex, double ratio) {
        var target = (int) (ratio * bitsPerMb(key, qindex, 1.0));
        var low = bestQuality;
        var high = worstQuality;
        while (low < high) {
            var mid = (low + high) >> 1;
            if (bitsPerMb(key, mid, 1.0) > target) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return low - qindex;
    }

    /**
     * Returns the quantizer index difference between two quantizer scales
     * ({@code av1_compute_qdelta}).
     *
     * @param qstart the starting scale
     * @param qtarget the target scale
     * @return the difference
     */
    private int qdelta(double qstart, double qtarget) {
        return findQindex(qtarget, bestQuality, worstQuality) - findQindex(qstart, bestQuality, worstQuality);
    }

    /**
     * Chooses the quantizer index of the next frame ({@code rc_pick_q_and_bounds_no_stats_cbr}).
     *
     * @param key whether the frame is a key frame
     * @param frameSourceSad the mean 64x64 SAD of the source against the previous one
     * @param avgSourceSad the running average of the source SAD
     * @param prevAvgSourceSad the running average before the current frame
     * @return the quantizer index
     */
    int pickQindex(boolean key, long frameSourceSad, long avgSourceSad, long prevAvgSourceSad) {
        var worst = activeWorstQuality(key);
        var best = activeBestQuality(key, worst);
        best = Math.clamp(best, bestQuality, worstQuality);
        worst = Math.clamp(worst, best, worstQuality);
        var top = worst;
        if (key && frameNumber != 0 && maxIntraBitratePct == 0) {
            top = Math.max(worst + qdeltaByRate(true, worst, 2.0), best);
        }
        var factor = key ? rcfKey : rcfInter;
        var targetBpm = (int) Math.min(Integer.MAX_VALUE, ((long) thisFrameTarget << BPER_MB_NORMBITS) / mbs);
        var q = closestQindexByRate(key, targetBpm, factor, best, worst);
        q = adjustQ(key, q, frameSourceSad, avgSourceSad, prevAvgSourceSad);
        if (q > top) {
            q = thisFrameTarget >= maxFrameBandwidth ? q : top;
        }
        recodeLow = best;
        recodeHigh = Math.max(top, q);
        sceneChangeMaxQ = false;
        if (forceMaxQ) {
            forceMaxQ = false;
            q = worstQuality;
        }
        return q;
    }

    /**
     * Returns the quantizer index a key frame is coded again at when a key frame limit is set and the
     * frame overshot its target by more than the recode tolerance, or the quantizer it was coded at
     * when it stays ({@code recode_loop_test} and the overshoot branch of
     * {@code recode_loop_update_q}).
     *
     * <p>A recode raises the smallest quantizer the frame may use above the one it was coded at,
     * so successive recodes of one frame only move up, and teaches the key frame rate correction
     * factor the observed size.
     *
     * @param qindex the quantizer index the key frame was coded at
     * @param bytes the coded size in bytes
     * @return the quantizer index to code the key frame at, {@code qindex} when it stays
     */
    int recodeKeyQindex(int qindex, int bytes) {
        var bits = (long) bytes * 8;
        var tolerance = Math.max(100, (long) KEY_RECODE_TOLERANCE * thisFrameTarget / 100);
        var limit = Math.max(1, Math.min(thisFrameTarget + tolerance, maxFrameBandwidth));
        if (maxIntraBitratePct == 0 || bits <= limit || qindex >= recodeHigh) {
            return qindex;
        }
        recodeLow = Math.min(qindex + 1, recodeHigh);
        var targetBpm = (int) Math.min(Integer.MAX_VALUE, ((long) thisFrameTarget << BPER_MB_NORMBITS) / mbs);
        var q = qindex;
        for (var retries = 0; retries <= 10; retries++) {
            updateCorrectionFactors(true, qindex, (int) Math.min(bits, Integer.MAX_VALUE));
            q = closestQindexByRate(true, targetBpm, rcfKey, recodeLow, recodeHigh);
            if (q >= recodeLow) {
                break;
            }
        }
        return Math.clamp(q, recodeLow, recodeHigh);
    }

    /**
     * Returns the quantizer index a scene change frame is coded at, and resets the quantizer
     * history, the buffer and the rate correction factor so the frames after it do not inherit a
     * state learned on the old scene ({@code av1_encodedframe_overshoot_cbr}).
     *
     * <p>A frame whose spatial variance was measured is coded nearer to the worst quantizer the
     * busier it is; otherwise the quantizer moves three quarters of the way to the worst.
     *
     * @param qindex the quantizer index picked for the frame
     * @param useVariance whether the frame's spatial variance was measured
     * @param frameSourceSad the mean source SAD of the frame
     * @return the quantizer index
     */
    int sceneChangeQindex(int qindex, boolean useVariance, long frameSourceSad) {
        int q;
        if (useVariance) {
            if (frameSpatialVariance < 100) {
                q = (worstQuality + qindex) >> 1;
            } else if (frameSpatialVariance < 400 || (frameSourceSad < 80000 && frameSpatialVariance < 1000)) {
                q = (3 * worstQuality + qindex) >> 2;
            } else {
                q = worstQuality;
            }
        } else {
            q = (3 * worstQuality + qindex) >> 2;
        }
        avgQindexInter = q;
        bufferLevel = optimalBufferLevel;
        rc1Frame = 0;
        rc2Frame = 0;
        var targetBpm = (int) Math.min(Integer.MAX_VALUE, ((long) avgFrameBandwidth << BPER_MB_NORMBITS) / mbs);
        var factor = (double) targetBpm * qindexToQ(q) / 1500000;
        if (factor > rcfInter) {
            rcfInter = Math.min((factor + rcfInter) / 2.0, MAX_BPB_FACTOR);
        }
        sceneChangeMaxQ = true;
        return q;
    }

    /**
     * Decides whether a coded inter frame is dropped after all because it would empty the buffer
     * ({@code av1_postencode_drop_cbr}); a dropped frame leaves the buffer as {@link #postDrop}
     * does and forces the worst quantizer on the next frame.
     *
     * <p>A frame is dropped when frame dropping is on, the buffer after it would fall below a
     * quarter of the optimal level, it costs more than eight average frames, it is not a scene
     * change and its quantizer is below three quarters of the worst one.
     *
     * @param qindex the frame's quantizer index
     * @param bytes the coded size in bytes
     * @param sceneCut whether the frame is a scene change
     * @return {@code true} when the frame is dropped
     */
    boolean dropCoded(int qindex, int bytes, boolean sceneCut) {
        var bits = (long) bytes * 8;
        var newLevel = bufferLevel + avgFrameBandwidth - bits;
        if (dropWaterMark == 0 || sceneCut || newLevel >= optimalBufferLevel >> 2
                || bits <= 8L * avgFrameBandwidth || qindex >= 3 * (worstQuality >> 2)) {
            return false;
        }
        postDrop();
        forceMaxQ = true;
        avgQindexInter = worstQuality;
        bufferLevel = optimalBufferLevel;
        return true;
    }

    /**
     * Returns the worst quantizer the buffer level allows ({@code calc_active_worst_quality_no_stats_cbr}).
     *
     * @param key whether the frame is a key frame
     * @return the quantizer index
     */
    private int activeWorstQuality(boolean key) {
        if (key) {
            return worstQuality;
        }
        var critical = optimalBufferLevel >> 3;
        var ambient = frameNumber < 5 ? Math.min(avgQindexInter, avgQindexKey) : avgQindexInter;
        ambient = Math.min(worstQuality, ambient);
        int active;
        if (bufferLevel > optimalBufferLevel) {
            active = Math.min(worstQuality, ambient * 5 / 4);
            var maxDown = active / 3;
            if (maxDown != 0) {
                var step = (maximumBufferSize - optimalBufferLevel) / maxDown;
                if (step != 0) {
                    active -= (int) ((bufferLevel - optimalBufferLevel) / step);
                }
            }
        } else if (bufferLevel > critical) {
            active = Math.min(worstQuality, ambient);
            if (critical != 0) {
                var step = optimalBufferLevel - critical;
                if (step != 0) {
                    active += (int) ((worstQuality - ambient) * (optimalBufferLevel - bufferLevel) / step);
                }
            }
        } else {
            active = worstQuality;
        }
        return active;
    }

    /**
     * Returns the best quantizer the frame may use ({@code calc_active_best_quality_no_stats_cbr}).
     *
     * @param key whether the frame is a key frame
     * @param worst the active worst quantizer
     * @return the quantizer index
     */
    private int activeBestQuality(boolean key, int worst) {
        if (key) {
            if (frameNumber == 0) {
                return bestQuality;
            }
            var q = avgQindexKey;
            int active;
            if (KF_BOOST_RT > KF_HIGH) {
                active = KF_LOW_MINQ[q];
            } else if (KF_BOOST_RT < KF_LOW) {
                active = KF_HIGH_MINQ[q];
            } else {
                var gap = KF_HIGH - KF_LOW;
                var offset = KF_HIGH - KF_BOOST_RT;
                var qdiff = KF_HIGH_MINQ[q] - KF_LOW_MINQ[q];
                active = KF_LOW_MINQ[q] + (offset * qdiff + (gap >> 1)) / gap;
            }
            var adj = pixels <= 352L * 288 ? 0.75 : 1.0;
            var qval = qindexToQ(active);
            return active + qdelta(qval, qval * adj);
        }
        var avg = frameNumber > 1 ? avgQindexInter : avgQindexKey;
        return avg < worst ? RTC_MINQ[avg] : RTC_MINQ[worst];
    }

    /**
     * Limits the change of the quantizer between frames ({@code adjust_q_cbr}).
     *
     * @param key whether the frame is a key frame
     * @param q the regulated quantizer index
     * @param frameSourceSad the mean source SAD
     * @param avgSourceSad the running average of the source SAD
     * @param prevAvgSourceSad the running average before the current frame
     * @return the adjusted quantizer index
     */
    private int adjustQ(boolean key, int q, long frameSourceSad, long avgSourceSad,
                        long prevAvgSourceSad) {
        var overshootBufferLow = rc1Frame == -1 && frameSourceSad > 1000 && bufferLevel < (optimalBufferLevel >> 1)
                && framesSinceKey > 4;
        var maxDeltaUp = overshootBufferLow ? 120 : 20;
        var maxDeltaDown = Math.min(16, Math.max(1, q1Frame / 8));
        var changeBandwidth = Math.abs(avgFrameBandwidth - prevAvgFrameBandwidth) > 0.1 * avgFrameBandwidth;
        if (!key && framesSinceKey > 1 && q1Frame > 0 && q2Frame > 0 && !changeBandwidth) {
            if (rc1Frame * rc2Frame == -1 && q1Frame != q2Frame && !overshootBufferLow) {
                var qclamp = Math.clamp(q, Math.min(q1Frame, q2Frame), Math.max(q1Frame, q2Frame));
                if (rc1Frame == -1 && q > qclamp && framesSinceKey > 10) {
                    q = (q + qclamp) >> 1;
                } else {
                    q = qclamp;
                }
            }
            if (prevAvgSourceSad > 0 && framesSinceKey > 10 && frameSourceSad > 0) {
                var delta = (double) avgSourceSad / prevAvgSourceSad - 1.0;
                if (delta < 0.0 && bufferLevel > (optimalBufferLevel >> 2) && q > (worstQuality >> 1)) {
                    var adj = 1.0 + 0.5 * StrictMath.tanh(4.0 * delta);
                    var qval = qindexToQ(q);
                    q += qdelta(qval, qval * adj);
                } else if (q1Frame - q > 0 && delta > 0.1
                        && bufferLevel < Math.min(maximumBufferSize, optimalBufferLevel << 1)) {
                    q = (3 * q + q1Frame) >> 2;
                }
            }
            if (q1Frame - q > maxDeltaDown) {
                q = q1Frame - maxDeltaDown;
            } else if (q - q1Frame > maxDeltaUp) {
                q = q1Frame + maxDeltaUp;
            }
        }
        return Math.max(Math.min(q, worstQuality), bestQuality);
    }

    /**
     * Applies the reset {@link #sceneChangeQindex} made on another temporal layer's rate control
     * to this one (the temporal layer loop of {@code av1_encodedframe_overshoot_cbr}).
     *
     * @param from the rate control of the layer that coded the scene change
     * @param qindex the scene change's quantizer index
     */
    void followSceneChange(RateControl from, int qindex) {
        avgQindexInter = qindex;
        bufferLevel = optimalBufferLevel;
        rc1Frame = 0;
        rc2Frame = 0;
        rcfInter = from.rcfInter;
    }

    /**
     * Applies the reset {@link #dropCoded} made on another temporal layer's rate control to this
     * one (the layer loop of {@code av1_postencode_drop_cbr}).
     */
    void followCodedDrop() {
        forceMaxQ = true;
        avgQindexInter = worstQuality;
        bufferLevel = optimalBufferLevel;
        rc1Frame = 0;
        rc2Frame = 0;
    }

    /**
     * Updates the rate model and the buffer after a frame is coded ({@code av1_rc_postencode_update}).
     *
     * @param key whether the frame was a key frame
     * @param qindex the frame's quantizer index
     * @param bytes the frame's size in bytes
     */
    void postEncode(boolean key, int qindex, int bytes) {
        var bits = bytes * 8;
        var cr = cyclicRefresh;
        if (sceneChangeMaxQ && !key && temporalLayers == 1 && cr != null && cr.encodedSinceSceneChange() == 0) {
            q2Frame = qindex;
            q1Frame = qindex;
            rc2Frame = 0;
            rc1Frame = 0;
        } else {
            updateCorrectionFactors(key, qindex, bits);
        }
        sceneChangeMaxQ = false;
        if (key) {
            avgQindexKey = (3 * avgQindexKey + qindex + 2) >> 2;
        } else {
            avgQindexInter = (3 * avgQindexInter + qindex + 2) >> 2;
            lastQindexInter = qindex;
        }
        updateBuffer(bits);
        prevAvgFrameBandwidth = avgFrameBandwidth;
        framesSinceKey = key ? 0 : framesSinceKey + 1;
        frameNumber++;
        dropCountConsec = 0;
    }

    /**
     * Updates the buffer after a dropped frame ({@code av1_rc_postencode_update_drop_frame}).
     */
    void postDrop() {
        updateBuffer(0);
        rc1Frame = 0;
        rc2Frame = 0;
        prevAvgFrameBandwidth = avgFrameBandwidth;
    }

    /**
     * Fills the buffer with one frame's worth of bits and drains the coded size
     * ({@code update_buffer_level}).
     *
     * @param bits the coded size in bits
     */
    void updateBuffer(int bits) {
        bufferLevel = Math.min(bufferLevel + avgFrameBandwidth - bits, maximumBufferSize);
    }

    /**
     * Moves the rate correction factor of the frame type toward the observed size
     * ({@code av1_rc_update_rate_correction_factors}).
     *
     * @param key whether the frame was a key frame
     * @param qindex the frame's quantizer index
     * @param bits the frame's size in bits
     */
    private void updateCorrectionFactors(boolean key, int qindex, int bits) {
        var rcf = key ? rcfKey : rcfInter;
        var cr = cyclicRefresh;
        var refreshed = !key && cr != null && cr.apply;
        int projected;
        if (refreshed) {
            cr.codedWeights(weights);
            projected = (int) Math.round((1.0 - weights[0] - weights[1]) * estimateBits(false, qindex, rcf)
                    + weights[0] * estimateBits(false, qindex + cr.qindexDelta[CyclicRefresh.BOOST1], rcf)
                    + weights[1] * estimateBits(false, qindex + cr.qindexDelta[CyclicRefresh.BOOST2], rcf));
        } else {
            projected = estimateBits(key, qindex, rcf);
        }
        var correction = 1.0;
        if (projected > FRAME_OVERHEAD_BITS) {
            correction = (double) bits / projected;
        }
        correction = Math.max(correction, 0.25);
        q2Frame = q1Frame;
        q1Frame = qindex;
        rc2Frame = rc1Frame;
        rc1Frame = correction > 1.1 ? -1 : correction < 0.9 ? 1 : 0;
        if (refreshed && thisFrameTarget > 0) {
            cr.adjust(correction);
        }
        var limit = 0.25 + 0.75 * Math.min(0.5, Math.abs(StrictMath.log10(correction)));
        if (correction > 1.01) {
            correction = 1.0 + (correction - 1.0) * limit;
            rcf = Math.min(rcf * correction, MAX_BPB_FACTOR);
        } else if (correction < 0.99) {
            correction = 1.0 / correction;
            correction = 1.0 + (correction - 1.0) * limit;
            rcf = Math.max(rcf / correction, MIN_BPB_FACTOR);
        }
        if (key) {
            rcfKey = rcf;
        } else {
            rcfInter = rcf;
        }
    }
}
