/* Ports libvpx 1.17.0 (vp8/encoder/ratectrl.c, onyx_if.c). Copyright (c) 2010 The WebM project authors; BSD 3-Clause
   License. Modified for Smalt. */
package com.github.auties00.smalt.video.vp8.internal;

/**
 * Chooses the size and quantizer of every frame of the real-time VP8 encoder so the stream holds
 * a constant bitrate through a buffer model, and learns from the size of every coded frame.
 *
 * <p>The buffer model counts the bits the channel delivers ({@code bitrate / frameRate} per
 * frame) minus the bits the frames cost; it starts at the initial buffer level and is capped at
 * the buffer size. Below the optimal level, frame targets shrink and the worst quantizer the
 * frame may use grows; above it, targets grow and quantizers fall. The quantizer of a frame comes
 * from a model of bits per macroblock at each quantizer index, scaled by correction factors
 * (key frames, golden frames and other frames separately) that are nudged after every frame
 * towards the observed size. Key frames get a boost that later frames repay.
 *
 * <p>With temporal layers, every layer keeps its own buffer, quantizer statistics and
 * correction factors, saved and restored around each frame with {@link #saveLayer(int)} and
 * {@link #restoreLayer(int)}; the bits of a frame count against its own layer and every layer
 * above it. Instances are used by one thread at a time.
 *
 * @implNote This implementation differs from libvpx in four ways that serve changing bitrates: a
 * bitrate change keeps the buffer's fullness in milliseconds and moves the quantizer history to
 * the new bitrate ({@link #shiftQuantizerHistory(double)}), key frames after the first stay at the
 * stream's recent quantizer when they fit ({@link #ambientKeyFrameQ(int)}), and frames are dropped
 * only when the buffer is empty, never by libvpx's water-mark decimation.
 */
final class RtRateControl {
    /**
     * The smallest rate correction factor.
     */
    private static final double MIN_BPB_FACTOR = 0.01;

    /**
     * The largest rate correction factor.
     */
    private static final double MAX_BPB_FACTOR = 50;

    /**
     * The number of fractional bits of {@link RtTables#BITS_PER_MB}.
     */
    private static final int BPER_MB_NORMBITS = 9;

    /**
     * The largest quantizer index.
     */
    static final int MAXQ = 127;

    /**
     * The largest zero-bin over-quantization, libvpx's {@code ZBIN_OQ_MAX}.
     */
    private static final int ZBIN_OQ_MAX = 192;

    /**
     * The number of key frame distances averaged by {@link #estimateKeyframeFrequency()}.
     */
    private static final int KEY_FRAME_CONTEXT = 5;

    /**
     * The largest percentage by which a frame target shrinks below the per-frame bandwidth while
     * the buffer is below its optimal level, libvpx's {@code rc_undershoot_pct}; the target
     * shrinks by half of the buffer's shortfall in percent, up to this limit.
     */
    int underShootPct = 100;

    /**
     * The largest percentage by which a frame target grows above the per-frame bandwidth while
     * the buffer is above its optimal level, libvpx's {@code rc_overshoot_pct}.
     */
    int overShootPct = 100;

    /**
     * The largest key frame target as a percentage of the per-frame bandwidth, libvpx's
     * {@code rc_max_intra_bitrate_pct}, or {@code 0} for no limit.
     */
    int maxIntraBitratePct;

    /**
     * The number of macroblocks of a frame.
     */
    final int mbs;

    /**
     * The frame rate of the stream (or of the current temporal layer), in frames per second.
     */
    double framerate;

    /**
     * The frame rate the per-frame bandwidth is computed from.
     */
    double outputFramerate;

    /**
     * The target bitrate in bits per second of the current layer, including the layers below.
     */
    int targetBandwidth;

    /**
     * The buffer level the model starts at, in bits.
     */
    long startingBufferLevel;

    /**
     * The buffer level the model aims for, in bits.
     */
    long optimalBufferLevel;

    /**
     * The largest buffer level, in bits.
     */
    long maximumBufferSize;

    /**
     * The configured initial buffer level, in milliseconds.
     */
    int startingBufferMillis;

    /**
     * The configured optimal buffer level, in milliseconds.
     */
    int optimalBufferMillis;

    /**
     * The configured buffer size, in milliseconds.
     */
    int maximumBufferMillis;

    /**
     * The current buffer level, in bits; negative when the frames spent more than the channel
     * delivered.
     */
    long bufferLevel;

    /**
     * The bits delivered minus the bits spent since the stream started, plus the initial level.
     */
    long bitsOffTarget;

    /**
     * The total size of the coded frames, in bits.
     */
    long totalActualBits;

    /**
     * The total size of the coded frames, in bytes.
     */
    long totalByteCount;

    /**
     * The largest quantizer index allowed.
     */
    int worstQuality;

    /**
     * The smallest quantizer index allowed.
     */
    int bestQuality;

    /**
     * The largest quantizer index the current frame may use.
     */
    int activeWorstQuality;

    /**
     * The smallest quantizer index the current frame may use.
     */
    int activeBestQuality;

    /**
     * The damped average quantizer index of inter frames.
     */
    int niAvQi;

    /**
     * The sum of the quantizer indices of inter frames.
     */
    long niTotQi;

    /**
     * The number of inter frames that are neither key nor golden frames.
     */
    int niFrames;

    /**
     * The recent average quantizer index of inter frames.
     */
    int avgFrameQindex;

    /**
     * The scale of the bits-per-macroblock model for inter frames.
     */
    double rateCorrectionFactor = 1.0;

    /**
     * The scale of the bits-per-macroblock model for key frames.
     */
    double keyFrameRateCorrectionFactor = 1.0;

    /**
     * The scale of the bits-per-macroblock model for boosted golden frames.
     */
    double gfRateCorrectionFactor = 1.0;

    /**
     * The zero-bin widening applied beyond the largest quantizer, in 1/128 of a step.
     */
    int zbinOverQuant;

    /**
     * The target of the last inter frame, before golden frame adjustments.
     */
    int interFrameTarget;

    /**
     * The loop filter level of the last frame of the layer.
     */
    int filterLevel;

    /**
     * The percentage of intra macroblocks of the last frame.
     */
    int lastFramePercentIntra;

    /**
     * The last quantizer index of key frames ({@code [0]}) and inter frames ({@code [1]}).
     */
    final int[] lastQ = new int[2];

    /**
     * The number of macroblocks coded from each reference in the last frame of the layer.
     */
    final int[] refFrameUsage = new int[4];

    /**
     * The average bits per frame the target bandwidth allows.
     */
    int perFrameBandwidth;

    /**
     * The average bits per frame, before any per-frame adjustment.
     */
    int avPerFrameBandwidth;

    /**
     * The bits key frames overspent that later frames still have to repay.
     */
    long kfOverspendBits;

    /**
     * The bits each frame repays of {@link #kfOverspendBits}.
     */
    int kfBitrateAdjustment;

    /**
     * The bits golden frames overspent that later frames still have to repay.
     */
    long gfOverspendBits;

    /**
     * The bits each frame repays of {@link #gfOverspendBits}.
     */
    int nonGfBitrateAdjustment;

    /**
     * The number of frames since the last key frame.
     */
    int framesSinceKey = 8;

    /**
     * The number of key frames coded.
     */
    int keyFrameCount;

    /**
     * The distances between the last key frames.
     */
    private final int[] priorKeyFrameDistance = new int[KEY_FRAME_CONTEXT];

    /**
     * The target size of the current frame, in bits.
     */
    int thisFrameTarget;

    /**
     * The size of the last frame, in bits; during a frame, its size estimated from the mode
     * decisions.
     */
    int projectedFrameSize;

    /**
     * The number of frames until the next golden frame update.
     */
    int framesTillGfUpdateDue;

    /**
     * The interval between golden frame updates.
     */
    int baselineGfInterval;

    /**
     * Whether golden frames are refreshed periodically, at {@link #baselineGfInterval}, as
     * libvpx does for one-pass constant bitrate without error resilience.
     */
    boolean gfUpdateOnepassCbr;

    /**
     * The maximum key frame interval in frames, or {@code 0} for none.
     */
    int keyFrameFrequency;

    /**
     * Whether frames may be dropped when the buffer is empty.
     */
    boolean dropFramesAllowed;

    /**
     * Whether the current frame is to be dropped.
     */
    boolean dropFrame;

    /**
     * The number of temporal layers.
     */
    int layers = 1;

    /**
     * The temporal layer of the current frame.
     */
    int currentLayer;

    /**
     * The saved state of each temporal layer.
     */
    private final LayerContext[] layerContexts;

    /**
     * Creates the rate control of a stream.
     *
     * @param mbs       the number of macroblocks of a frame
     * @param maxLayers the number of temporal layers, from 1 to 3
     */
    RtRateControl(int mbs, int maxLayers) {
        this.mbs = mbs;
        this.layerContexts = new LayerContext[maxLayers];
        for (var i = 0; i < maxLayers; i++) {
            layerContexts[i] = new LayerContext();
        }
    }

    /**
     * Sets the frame rate and recomputes the per-frame bandwidth, libvpx's
     * {@code vp8_new_framerate}.
     *
     * @param rate the frame rate in frames per second; values below 0.1 select 30
     */
    void newFramerate(double rate) {
        if (rate < 0.1) {
            rate = 30;
        }
        framerate = rate;
        outputFramerate = rate;
        perFrameBandwidth = (int) Math.min(Math.round(targetBandwidth / outputFramerate), Integer.MAX_VALUE);
        avPerFrameBandwidth = perFrameBandwidth;
    }

    /**
     * Applies a rate configuration, libvpx's {@code vp8_change_config} for the rate fields.
     *
     * <p>The buffer levels in bits follow the new bitrate; when the bitrate changes on a running
     * stream, the current buffer level keeps its fullness in milliseconds and the quantizer
     * history moves to the quantizers the new bitrate is expected to need (see
     * {@link #shiftQuantizerHistory(double)}).
     *
     * @param bitrate        the target bitrate in bits per second, positive
     * @param minQuantizer   the smallest quantizer on the {@code [0, 63]} scale
     * @param maxQuantizer   the largest quantizer on the {@code [0, 63]} scale
     * @param bufferMillis   the buffer size in milliseconds
     * @param initialMillis  the initial buffer level in milliseconds
     * @param optimalMillis  the optimal buffer level in milliseconds
     * @param dropFrames     whether frames may be dropped when the buffer is empty
     * @param rate           the frame rate in frames per second
     * @param running        whether the stream has started, so the buffer level is rescaled
     */
    void configure(int bitrate, int minQuantizer, int maxQuantizer, int bufferMillis, int initialMillis,
                   int optimalMillis, boolean dropFrames, double rate, boolean running) {
        var previous = targetBandwidth;
        targetBandwidth = bitrate;
        startingBufferMillis = initialMillis;
        optimalBufferMillis = optimalMillis;
        maximumBufferMillis = bufferMillis;
        startingBufferLevel = rescale(initialMillis, bitrate);
        optimalBufferLevel = optimalMillis == 0 ? bitrate / 8 : rescale(optimalMillis, bitrate);
        maximumBufferSize = bufferMillis == 0 ? bitrate / 8 : rescale(bufferMillis, bitrate);
        if (running && previous > 0 && previous != bitrate) {
            bitsOffTarget = bitsOffTarget * bitrate / previous;
            bufferLevel = bufferLevel * bitrate / previous;
            shiftQuantizerHistory((double) bitrate / previous);
        }
        if (bitsOffTarget > maximumBufferSize) {
            bitsOffTarget = maximumBufferSize;
            bufferLevel = bitsOffTarget;
        }
        newFramerate(rate);
        worstQuality = RtTables.Q_TRANS[maxQuantizer];
        bestQuality = RtTables.Q_TRANS[minQuantizer];
        if (activeWorstQuality > worstQuality) {
            activeWorstQuality = worstQuality;
        } else if (activeWorstQuality < bestQuality) {
            activeWorstQuality = bestQuality;
        }
        if (activeBestQuality < bestQuality) {
            activeBestQuality = bestQuality;
        } else if (activeBestQuality > worstQuality) {
            activeBestQuality = worstQuality;
        }
        dropFramesAllowed = dropFrames;
    }

    /**
     * Moves the quantizer history of the stream towards the quantizers the new bitrate is expected
     * to need: to the quantizers at which the inter frame model predicts frames
     * {@code sqrt(ratio)} times as large, halfway in the model's logarithmic size.
     *
     * <p>The long-term average quantizer of inter frames ({@link #niAvQi}, through
     * {@link #niTotQi}) and the recent average ({@link #avgFrameQindex}) bound the quantizers of
     * the following frames once 150 inter frames are coded: the average becomes the active worst
     * quantizer and selects the floor of the active best quantizer. Kept at the old bitrate's
     * values, they hold the quantizer near the old one after a raise, which undershoots the new
     * target by up to 40 percent for about a second, and let it fall too low after a cut. Each
     * average moves to the quantizer whose modeled bits per macroblock are {@code sqrt(ratio)}
     * times those of the average, within the configured quantizer range.
     *
     * @implNote This implementation moves the history halfway, chosen by measurement: the full move
     * the bits per macroblock model predicts overshoots after a bitrate cut.
     * @param ratio the new bitrate divided by the old one, positive
     */
    private void shiftQuantizerHistory(double ratio) {
        var step = Math.sqrt(ratio);
        if (niFrames > 0) {
            var average = (int) (niTotQi / niFrames);
            var shifted = shiftQuantizer(average, step);
            niTotQi += (long) (shifted - average) * niFrames;
            niAvQi = niFrames > 150 ? shifted : (shifted + worstQuality + 1) / 2;
        }
        avgFrameQindex = shiftQuantizer(avgFrameQindex, step);
    }

    /**
     * Returns the inter frame quantizer whose modeled bits per macroblock are closest to
     * {@code ratio} times those of another quantizer, within the configured quantizer range.
     *
     * @param q     the quantizer index
     * @param ratio the size ratio, positive
     * @return the quantizer index
     */
    private int shiftQuantizer(int q, double ratio) {
        var target = RtTables.BITS_PER_MB[128 + Math.clamp(q, 0, MAXQ)] * ratio;
        var best = bestQuality;
        var bestError = Double.MAX_VALUE;
        for (var i = bestQuality; i <= worstQuality; i++) {
            var error = Math.abs(Math.log(RtTables.BITS_PER_MB[128 + i] / target));
            if (error < bestError) {
                bestError = error;
                best = i;
            }
        }
        return best;
    }

    /**
     * Resets the quality bookkeeping and the buffer to their initial state, libvpx's
     * {@code init_config} for the rate fields.
     */
    void start() {
        activeWorstQuality = worstQuality;
        activeBestQuality = bestQuality;
        avgFrameQindex = worstQuality;
        niAvQi = worstQuality;
        bufferLevel = startingBufferLevel;
        bitsOffTarget = startingBufferLevel;
        totalActualBits = 0;
        keyFrameCount = 1;
        java.util.Arrays.fill(priorKeyFrameDistance, (int) outputFramerate);
    }

    /**
     * Converts a duration in milliseconds at a bitrate to bits, libvpx's {@code rescale}.
     *
     * @param millis  the duration
     * @param bitrate the bitrate in bits per second
     * @return the bits, at most {@link Integer#MAX_VALUE}
     */
    private static long rescale(int millis, int bitrate) {
        return Math.min((long) millis * bitrate / 1000, Integer.MAX_VALUE);
    }

    /**
     * Returns the number of bits a frame is expected to take at a quantizer, libvpx's
     * {@code estimate_bits_at_q}.
     *
     * @param frameKind        {@code 0} for key frames, {@code 1} for inter frames
     * @param q                the quantizer index
     * @param correctionFactor the model scale
     * @return the bits
     */
    private int estimateBitsAtQ(int frameKind, int q, double correctionFactor) {
        var bpm = (int) (0.5 + correctionFactor * RtTables.BITS_PER_MB[frameKind * 128 + q]);
        if (mbs > (1 << 11)) {
            return (bpm >> BPER_MB_NORMBITS) * mbs;
        }
        return (bpm * mbs) >> BPER_MB_NORMBITS;
    }

    /**
     * Sets the target of a key frame, libvpx's {@code calc_iframe_target_size}, at most
     * {@link #maxIntraBitratePct} percent of the per-frame bandwidth when that limit is set.
     *
     * @param firstFrame whether this is the first frame of the stream
     * @param forced     whether the key frame was requested rather than periodic
     */
    void calcIframeTargetSize(boolean firstFrame, boolean forced) {
        long target;
        if (firstFrame) {
            target = startingBufferLevel / 2;
            if (target > targetBandwidth * 3L / 2) {
                target = targetBandwidth * 3L / 2;
            }
        } else {
            var q = forced ? avgFrameQindex : niAvQi;
            var initialBoost = 32;
            int kfBoost;
            if (layers == 1) {
                kfBoost = (int) Math.max(initialBoost, Math.round(2 * outputFramerate - 16));
                kfBoost = Math.min(kfBoost, 2000);
            } else {
                kfBoost = initialBoost;
            }
            kfBoost = kfBoost * RtTables.KF_BOOST_QADJUSTMENT[q] / 100;
            if (framesSinceKey < outputFramerate / 2) {
                kfBoost = (int) (kfBoost * framesSinceKey / (outputFramerate / 2));
            }
            if (kfBoost < 16) {
                kfBoost = 16;
            }
            target = ((16L + kfBoost) * perFrameBandwidth) >> 4;
            target = Math.min(Integer.MAX_VALUE, target);
        }
        if (maxIntraBitratePct > 0) {
            target = Math.min(target, Math.min(Integer.MAX_VALUE, (long) perFrameBandwidth * maxIntraBitratePct / 100));
        }
        thisFrameTarget = (int) target;
        activeWorstQuality = worstQuality;
    }

    /**
     * Sets the target of an inter frame, adapts the active quantizer range to the buffer level,
     * decides whether to drop the frame and whether to refresh the golden frame, libvpx's
     * {@code calc_pframe_target_size} on its one-pass path.
     *
     * @param errorResilient whether golden frames are refreshed only by key frames
     * @param zeromvCount    the number of macroblocks the last frame coded as last-frame zero
     *                       motion
     * @return {@code true} when the golden frame is to be refreshed
     */
    boolean calcPframeTargetSize(boolean errorResilient, int zeromvCount) {
        var oldPerFrameBandwidth = perFrameBandwidth;
        if (currentLayer > 0) {
            perFrameBandwidth = layerContexts[currentLayer].avgFrameSizeForLayer;
        }
        var minFrameTarget = Math.max(0, perFrameBandwidth / 4);
        if (kfOverspendBits > 0) {
            var adjustment = (int) Math.min(kfBitrateAdjustment, kfOverspendBits);
            if (adjustment > perFrameBandwidth - minFrameTarget) {
                adjustment = perFrameBandwidth - minFrameTarget;
            }
            kfOverspendBits -= adjustment;
            thisFrameTarget = perFrameBandwidth - adjustment;
            if (thisFrameTarget < minFrameTarget) {
                thisFrameTarget = minFrameTarget;
            }
        } else {
            thisFrameTarget = perFrameBandwidth;
        }
        if (gfOverspendBits > 0 && thisFrameTarget > minFrameTarget) {
            var adjustment = (int) Math.min(nonGfBitrateAdjustment, gfOverspendBits);
            if (adjustment > thisFrameTarget - minFrameTarget) {
                adjustment = thisFrameTarget - minFrameTarget;
            }
            gfOverspendBits -= adjustment;
            thisFrameTarget -= adjustment;
        }
        if (thisFrameTarget < minFrameTarget) {
            thisFrameTarget = minFrameTarget;
        }
        interFrameTarget = thisFrameTarget;
        var onePercentBits = (int) (1 + optimalBufferLevel / 100);
        if (bufferLevel < optimalBufferLevel || bitsOffTarget < optimalBufferLevel) {
            var percentLow = 0;
            if (bufferLevel < optimalBufferLevel) {
                percentLow = (int) ((optimalBufferLevel - bufferLevel) / onePercentBits);
            } else if (bitsOffTarget < 0) {
                percentLow = (int) (100 * -bitsOffTarget / Math.max(1, totalByteCount * 8));
            }
            percentLow = Math.clamp(percentLow, 0, underShootPct);
            thisFrameTarget -= (int) ((long) thisFrameTarget * percentLow / 200);
            if (niFrames > 150) {
                var criticalBufferLevel = Math.min(bufferLevel, bitsOffTarget);
                if (criticalBufferLevel < optimalBufferLevel) {
                    if (criticalBufferLevel > (optimalBufferLevel >> 2)) {
                        long qadjustmentRange = worstQuality - niAvQi;
                        var aboveBase = criticalBufferLevel - (optimalBufferLevel >> 2);
                        activeWorstQuality = worstQuality
                                - (int) (qadjustmentRange * aboveBase / Math.max(1, optimalBufferLevel * 3 >> 2));
                    } else {
                        activeWorstQuality = worstQuality;
                    }
                } else {
                    activeWorstQuality = niAvQi;
                }
            } else {
                activeWorstQuality = worstQuality;
            }
        } else {
            var percentHigh = 0;
            long target = thisFrameTarget;
            if (bufferLevel > optimalBufferLevel) {
                percentHigh = (int) ((bufferLevel - optimalBufferLevel) / onePercentBits);
            }
            percentHigh = Math.clamp(percentHigh, 0, overShootPct);
            target += target * percentHigh / 200;
            thisFrameTarget = (int) Math.min(target, Integer.MAX_VALUE);
            activeWorstQuality = niFrames > 150 ? niAvQi : worstQuality;
        }
        activeBestQuality = bestQuality;
        if (activeWorstQuality <= activeBestQuality) {
            activeWorstQuality = activeBestQuality + 1;
        }
        if (activeWorstQuality > 127) {
            activeWorstQuality = 127;
        }
        if (dropFramesAllowed && bufferLevel < 0) {
            dropFrame = true;
            bitsOffTarget += avPerFrameBandwidth;
            if (bitsOffTarget > maximumBufferSize) {
                bitsOffTarget = maximumBufferSize;
            }
            bufferLevel = bitsOffTarget;
            propagateDroppedBits();
        }
        var refreshGolden = false;
        if (!errorResilient && framesTillGfUpdateDue == 0 && !dropFrame && gfUpdateOnepassCbr) {
            baselineGfInterval = framesTillGfUpdateInterval;
            if (zeromvCount > (mbs >> 1)) {
                refreshGolden = true;
            }
            framesTillGfUpdateDue = baselineGfInterval;
        }
        perFrameBandwidth = oldPerFrameBandwidth;
        return refreshGolden;
    }

    /**
     * The golden frame interval of one-pass constant bitrate, libvpx's
     * {@code gf_interval_onepass_cbr}.
     */
    int framesTillGfUpdateInterval;

    /**
     * Credits the bits of a dropped frame to the layers above the current one.
     */
    private void propagateDroppedBits() {
        for (var i = currentLayer + 1; i < layers; i++) {
            var lc = layerContexts[i];
            lc.bitsOffTarget += (int) (lc.targetBandwidth / lc.framerate);
            if (lc.bitsOffTarget > lc.maximumBufferSize) {
                lc.bitsOffTarget = lc.maximumBufferSize;
            }
            lc.bufferLevel = lc.bitsOffTarget;
        }
    }

    /**
     * Chooses the quantizer index whose modeled frame size is closest to a target, libvpx's
     * {@code vp8_regulate_q}, setting {@link #zbinOverQuant} when even the largest index
     * overshoots; a key frame after the first gets {@link #ambientKeyFrameQ(int)} instead when
     * that is finer.
     *
     * @param keyFrame       whether the frame is a key frame
     * @param boostedGolden  whether the frame is a golden frame with its own correction factor
     * @param targetBits     the target size in bits
     * @return the quantizer index, within the active range
     */
    int regulateQ(boolean keyFrame, boolean boostedGolden, int targetBits) {
        var q = activeWorstQuality;
        zbinOverQuant = 0;
        var correctionFactor = keyFrame ? keyFrameRateCorrectionFactor
                : boostedGolden ? gfRateCorrectionFactor : rateCorrectionFactor;
        int targetBitsPerMb;
        if (targetBits > (Integer.MAX_VALUE >> BPER_MB_NORMBITS)) {
            var temp = targetBits / mbs;
            targetBitsPerMb = temp > (Integer.MAX_VALUE >> BPER_MB_NORMBITS) ? Integer.MAX_VALUE
                    : temp << BPER_MB_NORMBITS;
        } else {
            targetBitsPerMb = (targetBits << BPER_MB_NORMBITS) / mbs;
        }
        var lastError = Integer.MAX_VALUE;
        var i = activeBestQuality;
        var kind = keyFrame ? 0 : 1;
        var bitsPerMbAtThisQ = 0;
        do {
            bitsPerMbAtThisQ = (int) (0.5 + correctionFactor * RtTables.BITS_PER_MB[kind * 128 + i]);
            if (bitsPerMbAtThisQ <= targetBitsPerMb) {
                q = (targetBitsPerMb - bitsPerMbAtThisQ) <= lastError ? i : i - 1;
                break;
            }
            lastError = bitsPerMbAtThisQ - targetBitsPerMb;
        } while (++i <= activeWorstQuality);
        if (q >= MAXQ) {
            var zbinOqMax = keyFrame ? 0 : boostedGolden ? 16 : ZBIN_OQ_MAX;
            var factor = 0.99;
            var factorAdjustment = 0.01 / 256.0;
            while (zbinOverQuant < zbinOqMax) {
                zbinOverQuant++;
                bitsPerMbAtThisQ = (int) (factor * bitsPerMbAtThisQ);
                factor += factorAdjustment;
                if (factor >= 0.999) {
                    factor = 0.999;
                }
                if (bitsPerMbAtThisQ <= targetBitsPerMb) {
                    break;
                }
            }
        }
        if (keyFrame && keyFrameCount > 1 && q > avgFrameQindex) {
            q = ambientKeyFrameQ(q);
        }
        return q;
    }

    /**
     * Returns the quantizer of a key frame after the first one: the recent quantizer of inter
     * frames ({@link #avgFrameQindex}) when the key frame model predicts that a key frame coded
     * with it fits in the buffer level and under {@link #maxIntraBitratePct}, otherwise the
     * quantizer {@link #regulateQ} chose.
     *
     * <p>The key frame target of {@link #calcIframeTargetSize} often selects a quantizer far
     * coarser than the stream's: the key frame then loses most of the quality the stream had built
     * up, and the next inter frames spend several frame budgets restoring it, after which the
     * stream codes at even coarser quantizers until the overspend is repaid.
     *
     * @implNote This implementation adapts libvpx's VP9 one-pass rate control, which keeps a key
     * frame forced by the key frame interval near the ambient quantizer; measured on conference and
     * outdoor content, it raises quality at the same bitrate without dropping more frames.
     * @param q the quantizer {@link #regulateQ} chose
     * @return the key frame quantizer
     */
    private int ambientKeyFrameQ(int q) {
        var ambient = Math.max(avgFrameQindex, activeBestQuality);
        long limit = Math.max(0, bufferLevel);
        if (maxIntraBitratePct > 0) {
            limit = Math.min(limit, (long) perFrameBandwidth * maxIntraBitratePct / 100);
        }
        return estimateBitsAtQ(0, ambient, keyFrameRateCorrectionFactor) <= limit ? ambient : q;
    }

    /**
     * Nudges the correction factor of the frame's kind towards the ratio of its actual to its
     * modeled size, libvpx's {@code vp8_update_rate_correction_factors}.
     *
     * @param keyFrame      whether the frame was a key frame
     * @param boostedGolden whether the frame was a golden frame with its own correction factor
     * @param q             the frame's quantizer index
     * @param dampVar       the damping: {@code 0} strong (0.75), {@code 1} medium (0.375),
     *                      {@code 2} weak (0.25)
     */
    void updateRateCorrectionFactors(boolean keyFrame, boolean boostedGolden, int q, int dampVar) {
        var factor = keyFrame ? keyFrameRateCorrectionFactor
                : boostedGolden ? gfRateCorrectionFactor : rateCorrectionFactor;
        var projectedSizeBasedOnQ = (int) (((0.5 + factor * RtTables.BITS_PER_MB[(keyFrame ? 0 : 128) + q]) * mbs)
                / (1 << BPER_MB_NORMBITS));
        if (zbinOverQuant > 0) {
            var z = zbinOverQuant;
            var zFactor = 0.99;
            var factorAdjustment = 0.01 / 256.0;
            while (z > 0) {
                z--;
                projectedSizeBasedOnQ = (int) (zFactor * projectedSizeBasedOnQ);
                zFactor += factorAdjustment;
                if (zFactor >= 0.999) {
                    zFactor = 0.999;
                }
            }
        }
        var correctionFactor = 100;
        if (projectedSizeBasedOnQ > 0) {
            correctionFactor = (int) ((100L * projectedFrameSize) / projectedSizeBasedOnQ);
        }
        var adjustmentLimit = switch (dampVar) {
            case 0 -> 0.75;
            case 1 -> 0.375;
            default -> 0.25;
        };
        if (correctionFactor > 102) {
            correctionFactor = (int) (100.5 + ((correctionFactor - 100) * adjustmentLimit));
            factor = factor * correctionFactor / 100;
            if (factor > MAX_BPB_FACTOR) {
                factor = MAX_BPB_FACTOR;
            }
        } else if (correctionFactor < 99) {
            correctionFactor = (int) (100.5 - ((100 - correctionFactor) * adjustmentLimit));
            factor = factor * correctionFactor / 100;
            if (factor < MIN_BPB_FACTOR) {
                factor = MIN_BPB_FACTOR;
            }
        }
        if (keyFrame) {
            keyFrameRateCorrectionFactor = factor;
        } else if (boostedGolden) {
            gfRateCorrectionFactor = factor;
        } else {
            rateCorrectionFactor = factor;
        }
    }

    /**
     * Returns the average distance between key frames, libvpx's
     * {@code estimate_keyframe_frequency}.
     *
     * @return the average distance in frames, at least 1
     */
    private int estimateKeyframeFrequency() {
        var average = 0;
        if (keyFrameCount == 1) {
            var keyFreq = keyFrameFrequency > 0 ? keyFrameFrequency : 1;
            average = 1 + (int) outputFramerate * 2;
            if (keyFrameFrequency > 0 && average > keyFreq) {
                average = keyFreq;
            }
            priorKeyFrameDistance[KEY_FRAME_CONTEXT - 1] = average;
        } else {
            var totalWeight = 0;
            var lastInterval = framesSinceKey > 0 ? framesSinceKey : 1;
            for (var i = 0; i < KEY_FRAME_CONTEXT; i++) {
                if (i < KEY_FRAME_CONTEXT - 1) {
                    priorKeyFrameDistance[i] = priorKeyFrameDistance[i + 1];
                } else {
                    priorKeyFrameDistance[i] = lastInterval;
                }
                average += RtTables.PRIOR_KEY_FRAME_WEIGHT[i] * priorKeyFrameDistance[i];
                totalWeight += RtTables.PRIOR_KEY_FRAME_WEIGHT[i];
            }
            average /= totalWeight;
        }
        return Math.max(average, 1);
    }

    /**
     * Records a coded key frame and schedules the repayment of its overspend, libvpx's
     * {@code vp8_adjust_key_frame_context}.
     */
    void adjustKeyFrameContext() {
        if (projectedFrameSize > perFrameBandwidth) {
            var overspend = projectedFrameSize - perFrameBandwidth;
            if (layers > 1) {
                kfOverspendBits += overspend;
            } else {
                kfOverspendBits += overspend * 7L / 8;
                gfOverspendBits += overspend / 8;
            }
            kfBitrateAdjustment = (int) (kfOverspendBits / estimateKeyframeFrequency());
        }
        framesSinceKey = 0;
        keyFrameCount++;
    }

    /**
     * Returns the largest frame size, in bits, that is not considered an overshoot, libvpx's
     * {@code vp8_compute_frame_size_bounds}.
     *
     * @param keyFrame        whether the frame is a key frame
     * @param refreshGolden   whether the frame refreshes the golden frame
     * @return the overshoot limit
     */
    int overShootLimit(boolean keyFrame, boolean refreshGolden) {
        long target = thisFrameTarget;
        long limit;
        if (keyFrame || layers > 1 || refreshGolden) {
            limit = target * 9 / 8;
        } else if (bufferLevel >= ((optimalBufferLevel + maximumBufferSize) >> 1)) {
            limit = target * 12 / 8;
        } else if (bufferLevel <= (optimalBufferLevel >> 1)) {
            limit = target * 10 / 8;
        } else {
            limit = target * 11 / 8;
        }
        return (int) Math.min(limit + 200, Integer.MAX_VALUE);
    }

    /**
     * Sets the active quantizer range of a frame once its target is known, the part of libvpx's
     * {@code encode_frame_to_data_rate} that runs before {@link #regulateQ}.
     *
     * @param keyFrame      whether the frame is a key frame
     * @param refreshGolden whether the frame refreshes the golden frame
     */
    void setActiveQuality(boolean keyFrame, boolean refreshGolden) {
        if (bufferLevel >= optimalBufferLevel) {
            var adjustment = activeWorstQuality / 4;
            if (adjustment != 0) {
                if (bufferLevel < maximumBufferSize) {
                    var step = (maximumBufferSize - optimalBufferLevel) / adjustment;
                    adjustment = step != 0 ? (int) ((bufferLevel - optimalBufferLevel) / step) : 0;
                }
                activeWorstQuality -= adjustment;
                if (activeWorstQuality < activeBestQuality) {
                    activeWorstQuality = activeBestQuality;
                }
            }
        }
        if (niFrames > 150) {
            var q = activeWorstQuality;
            if (keyFrame) {
                activeBestQuality = RtTables.KF_HIGH_MOTION_MINQ[q];
            } else if (layers == 1 && refreshGolden) {
                if (framesSinceKey > 1 && avgFrameQindex < activeWorstQuality) {
                    q = avgFrameQindex;
                }
                activeBestQuality = RtTables.GF_HIGH_MOTION_MINQ[q];
            } else {
                activeBestQuality = RtTables.INTER_MINQ[q];
            }
            if (bufferLevel >= maximumBufferSize) {
                activeBestQuality = bestQuality;
            } else if (bufferLevel > optimalBufferLevel) {
                var fraction = (int) (((bufferLevel - optimalBufferLevel) * 128)
                        / Math.max(1, maximumBufferSize - optimalBufferLevel));
                var minQadjustment = ((activeBestQuality - bestQuality) * fraction) / 128;
                activeBestQuality -= minQadjustment;
            }
        }
        if (activeWorstQuality > worstQuality) {
            activeWorstQuality = worstQuality;
        }
        if (activeBestQuality < bestQuality) {
            activeBestQuality = bestQuality;
        }
        if (activeWorstQuality < activeBestQuality) {
            activeWorstQuality = activeBestQuality;
        }
    }

    /**
     * Raises the active worst quantizer when a frame at that quantizer overshoots its limit, the
     * check of libvpx's {@code encode_frame_to_data_rate} after the macroblocks are coded.
     *
     * @param q             the frame's quantizer index
     * @param keyFrame      whether the frame is a key frame
     * @param refreshGolden whether the frame refreshes the golden frame
     * @return {@code true} when the active worst quantizer changed, in which case the correction
     *         factors are not updated for this frame
     */
    boolean relaxWorstQuality(int q, boolean keyFrame, boolean refreshGolden) {
        var overShootLimit = Math.max(1, overShootLimit(keyFrame, refreshGolden));
        if (q == activeWorstQuality && activeWorstQuality < worstQuality && projectedFrameSize > overShootLimit) {
            var overSizePercent = (int) (((long) projectedFrameSize - overShootLimit) * 100 / overShootLimit);
            while (activeWorstQuality < worstQuality && overSizePercent > 0) {
                activeWorstQuality++;
                overSizePercent = (int) (overSizePercent * 0.96);
            }
            return true;
        }
        return false;
    }

    /**
     * Records a coded frame: its size, quantizer statistics and buffer level, the bookkeeping of
     * libvpx's {@code encode_frame_to_data_rate} after the bitstream is packed.
     *
     * @param sizeBytes         the frame size in bytes
     * @param keyFrame          whether the frame was a key frame
     * @param refreshGolden     whether the frame refreshed the golden frame
     * @param q                 the frame's quantizer index
     * @param correctionChanged whether {@link #relaxWorstQuality} changed the active worst
     *                          quantizer, which skips the correction factor update
     */
    void frameCoded(int sizeBytes, boolean keyFrame, boolean refreshGolden, int q, boolean correctionChanged) {
        totalByteCount += sizeBytes;
        projectedFrameSize = sizeBytes << 3;
        for (var i = currentLayer + 1; i < layers; i++) {
            layerContexts[i].totalByteCount += sizeBytes;
        }
        var boostedGolden = refreshGolden && layers == 1 && !gfNoboostOnepassCbr;
        if (!correctionChanged) {
            updateRateCorrectionFactors(keyFrame, boostedGolden, q, 2);
        }
        lastQ[keyFrame ? 0 : 1] = q;
        if (keyFrame) {
            adjustKeyFrameContext();
        } else {
            avgFrameQindex = (2 + 3 * avgFrameQindex + q) >> 2;
        }
        if (!keyFrame && (layers > 1 || !refreshGolden)) {
            niFrames++;
            niTotQi += q;
            if (niFrames > 150) {
                niAvQi = (int) (niTotQi / niFrames);
            } else {
                niAvQi = (int) (((niTotQi / niFrames) + worstQuality + 1) / 2);
            }
            if (q > niAvQi) {
                niAvQi = q - 1;
            }
        }
        bitsOffTarget += avPerFrameBandwidth - projectedFrameSize;
        if (bitsOffTarget > maximumBufferSize) {
            bitsOffTarget = maximumBufferSize;
        }
        totalActualBits += projectedFrameSize;
        bufferLevel = bitsOffTarget;
        for (var i = currentLayer + 1; i < layers; i++) {
            var lc = layerContexts[i];
            var bitsOffForThisLayer = (int) Math.round(lc.targetBandwidth / lc.framerate - projectedFrameSize);
            lc.bitsOffTarget += bitsOffForThisLayer;
            if (lc.bitsOffTarget > lc.maximumBufferSize) {
                lc.bitsOffTarget = lc.maximumBufferSize;
            }
            lc.totalActualBits += projectedFrameSize;
            lc.bufferLevel = lc.bitsOffTarget;
        }
    }

    /**
     * Whether a golden frame refresh uses the inter frames' correction factor, libvpx's
     * {@code gf_noboost_onepass_cbr}.
     */
    boolean gfNoboostOnepassCbr;

    /**
     * Records the golden frame bookkeeping of a coded frame, libvpx's
     * {@code update_golden_frame_stats} for one-pass encoding.
     *
     * @param keyFrame      whether the frame was a key frame
     * @param refreshGolden whether the frame refreshed the golden frame
     * @return {@code true} when the frame refreshed the golden frame, so the caller resets its
     *         frames-since-golden count
     */
    boolean goldenFrameCoded(boolean keyFrame, boolean refreshGolden) {
        if (refreshGolden) {
            if (framesTillGfUpdateDue > 0) {
                if (!keyFrame) {
                    gfOverspendBits += projectedFrameSize - interFrameTarget;
                }
                nonGfBitrateAdjustment = (int) (gfOverspendBits / framesTillGfUpdateDue);
            }
            if (framesTillGfUpdateDue > 0) {
                framesTillGfUpdateDue--;
            }
            return true;
        }
        if (framesTillGfUpdateDue > 0) {
            framesTillGfUpdateDue--;
        }
        return false;
    }

    /**
     * Prepares the temporal layer contexts from the cumulative layer bitrates, libvpx's
     * {@code vp8_init_temporal_layer_context}.
     *
     * @param bitrates   the cumulative target bitrate of each layer, in bits per second
     * @param decimators the frame rate divisor of each layer
     * @param rate       the stream frame rate
     */
    void initLayers(int[] bitrates, int[] decimators, double rate) {
        layers = bitrates.length;
        var previousRate = 0.0;
        for (var i = 0; i < layers; i++) {
            var lc = layerContexts[i];
            lc.framerate = rate / decimators[i];
            lc.targetBandwidth = bitrates[i];
            lc.startingBufferLevel = rescale(startingBufferMillis, bitrates[i]);
            lc.optimalBufferLevel = optimalBufferMillis == 0 ? bitrates[i] / 8 : rescale(optimalBufferMillis, bitrates[i]);
            lc.maximumBufferSize = maximumBufferMillis == 0 ? bitrates[i] / 8 : rescale(maximumBufferMillis, bitrates[i]);
            if (i > 0) {
                lc.avgFrameSizeForLayer = (int) Math.round((bitrates[i] - bitrates[i - 1]) / (lc.framerate - previousRate));
            }
            lc.activeWorstQuality = worstQuality;
            lc.activeBestQuality = bestQuality;
            lc.avgFrameQindex = worstQuality;
            lc.bufferLevel = lc.startingBufferLevel;
            lc.bitsOffTarget = lc.startingBufferLevel;
            lc.totalActualBits = 0;
            lc.niAvQi = 0;
            lc.niTotQi = 0;
            lc.niFrames = 0;
            lc.rateCorrectionFactor = 1.0;
            lc.keyFrameRateCorrectionFactor = 1.0;
            lc.gfRateCorrectionFactor = 1.0;
            lc.interFrameTarget = 0;
            previousRate = lc.framerate;
        }
    }

    /**
     * Updates the layer contexts after a change of bitrates or frame rate, libvpx's
     * {@code vp8_update_layer_contexts}, rescaling every layer's buffer level to keep its
     * fullness in milliseconds.
     *
     * @param bitrates   the cumulative target bitrate of each layer, in bits per second
     * @param decimators the frame rate divisor of each layer
     * @param rate       the stream frame rate
     */
    void updateLayers(int[] bitrates, int[] decimators, double rate) {
        var previousRate = 0.0;
        for (var i = 0; i < layers; i++) {
            var lc = layerContexts[i];
            var previous = lc.targetBandwidth;
            lc.framerate = rate / decimators[i];
            lc.targetBandwidth = bitrates[i];
            lc.startingBufferLevel = rescale(startingBufferMillis, bitrates[i]);
            lc.optimalBufferLevel = optimalBufferMillis == 0 ? bitrates[i] / 8 : rescale(optimalBufferMillis, bitrates[i]);
            lc.maximumBufferSize = maximumBufferMillis == 0 ? bitrates[i] / 8 : rescale(maximumBufferMillis, bitrates[i]);
            if (previous > 0 && previous != bitrates[i]) {
                lc.bitsOffTarget = lc.bitsOffTarget * bitrates[i] / previous;
                lc.bufferLevel = lc.bufferLevel * bitrates[i] / previous;
            }
            if (lc.bitsOffTarget > lc.maximumBufferSize) {
                lc.bitsOffTarget = lc.maximumBufferSize;
                lc.bufferLevel = lc.bitsOffTarget;
            }
            if (i > 0) {
                lc.avgFrameSizeForLayer = (int) Math.round((bitrates[i] - bitrates[i - 1]) / (lc.framerate - previousRate));
            }
            previousRate = lc.framerate;
        }
    }

    /**
     * Saves the layer-dependent state of the current layer, libvpx's
     * {@code vp8_save_layer_context}.
     *
     * @param layer the current layer
     */
    void saveLayer(int layer) {
        var lc = layerContexts[layer];
        lc.targetBandwidth = targetBandwidth;
        lc.startingBufferLevel = startingBufferLevel;
        lc.optimalBufferLevel = optimalBufferLevel;
        lc.maximumBufferSize = maximumBufferSize;
        lc.bufferLevel = bufferLevel;
        lc.bitsOffTarget = bitsOffTarget;
        lc.totalActualBits = totalActualBits;
        lc.activeWorstQuality = activeWorstQuality;
        lc.activeBestQuality = activeBestQuality;
        lc.niAvQi = niAvQi;
        lc.niTotQi = niTotQi;
        lc.niFrames = niFrames;
        lc.avgFrameQindex = avgFrameQindex;
        lc.rateCorrectionFactor = rateCorrectionFactor;
        lc.keyFrameRateCorrectionFactor = keyFrameRateCorrectionFactor;
        lc.gfRateCorrectionFactor = gfRateCorrectionFactor;
        lc.zbinOverQuant = zbinOverQuant;
        lc.interFrameTarget = interFrameTarget;
        lc.totalByteCount = totalByteCount;
        lc.filterLevel = filterLevel;
        lc.lastFramePercentIntra = lastFramePercentIntra;
        lc.lastQ0 = lastQ[0];
        lc.lastQ1 = lastQ[1];
        System.arraycopy(refFrameUsage, 0, lc.refFrameUsage, 0, 4);
    }

    /**
     * Restores the layer-dependent state of a layer and makes it current, libvpx's
     * {@code vp8_restore_layer_context}, then applies the layer's frame rate.
     *
     * @param layer the temporal layer, from 0
     */
    void restoreLayer(int layer) {
        var lc = layerContexts[layer];
        currentLayer = layer;
        targetBandwidth = lc.targetBandwidth;
        startingBufferLevel = lc.startingBufferLevel;
        optimalBufferLevel = lc.optimalBufferLevel;
        maximumBufferSize = lc.maximumBufferSize;
        bufferLevel = lc.bufferLevel;
        bitsOffTarget = lc.bitsOffTarget;
        totalActualBits = lc.totalActualBits;
        activeWorstQuality = lc.activeWorstQuality;
        activeBestQuality = lc.activeBestQuality;
        niAvQi = lc.niAvQi;
        niTotQi = lc.niTotQi;
        niFrames = lc.niFrames;
        avgFrameQindex = lc.avgFrameQindex;
        rateCorrectionFactor = lc.rateCorrectionFactor;
        keyFrameRateCorrectionFactor = lc.keyFrameRateCorrectionFactor;
        gfRateCorrectionFactor = lc.gfRateCorrectionFactor;
        zbinOverQuant = lc.zbinOverQuant;
        interFrameTarget = lc.interFrameTarget;
        totalByteCount = lc.totalByteCount;
        filterLevel = lc.filterLevel;
        lastFramePercentIntra = lc.lastFramePercentIntra;
        lastQ[0] = lc.lastQ0;
        lastQ[1] = lc.lastQ1;
        System.arraycopy(lc.refFrameUsage, 0, refFrameUsage, 0, 4);
        newFramerate(lc.framerate);
    }

    /**
     * Holds the rate control state of one temporal layer, libvpx's {@code LAYER_CONTEXT}.
     */
    static final class LayerContext {
        /**
         * The layer's frame rate.
         */
        double framerate;

        /**
         * The cumulative target bitrate of the layer, in bits per second.
         */
        int targetBandwidth;

        /**
         * The layer's initial buffer level, in bits.
         */
        long startingBufferLevel;

        /**
         * The layer's optimal buffer level, in bits.
         */
        long optimalBufferLevel;

        /**
         * The layer's buffer size, in bits.
         */
        long maximumBufferSize;

        /**
         * The layer's buffer level, in bits.
         */
        long bufferLevel;

        /**
         * The layer's bits delivered minus bits spent.
         */
        long bitsOffTarget;

        /**
         * The layer's total coded size, in bits.
         */
        long totalActualBits;

        /**
         * The layer's total coded size, in bytes.
         */
        long totalByteCount;

        /**
         * The average size of a frame of this layer alone, in bits.
         */
        int avgFrameSizeForLayer;

        /**
         * The layer's active worst quantizer index.
         */
        int activeWorstQuality;

        /**
         * The layer's active best quantizer index.
         */
        int activeBestQuality;

        /**
         * The layer's damped average inter quantizer index.
         */
        int niAvQi;

        /**
         * The layer's sum of inter quantizer indices.
         */
        long niTotQi;

        /**
         * The layer's number of inter frames.
         */
        int niFrames;

        /**
         * The layer's recent average quantizer index.
         */
        int avgFrameQindex;

        /**
         * The layer's inter correction factor.
         */
        double rateCorrectionFactor = 1.0;

        /**
         * The layer's key frame correction factor.
         */
        double keyFrameRateCorrectionFactor = 1.0;

        /**
         * The layer's golden frame correction factor.
         */
        double gfRateCorrectionFactor = 1.0;

        /**
         * The layer's zero-bin over-quantization.
         */
        int zbinOverQuant;

        /**
         * The layer's last inter frame target.
         */
        int interFrameTarget;

        /**
         * The layer's last loop filter level.
         */
        int filterLevel;

        /**
         * The layer's last percentage of intra macroblocks.
         */
        int lastFramePercentIntra;

        /**
         * The layer's last key frame quantizer index.
         */
        int lastQ0;

        /**
         * The layer's last inter frame quantizer index.
         */
        int lastQ1;

        /**
         * The layer's last reference usage counts.
         */
        final int[] refFrameUsage = new int[4];

        /**
         * Creates an empty context.
         */
        LayerContext() {
        }
    }
}
