package com.github.auties00.smalt.video.av1.internal.encoder;

import java.util.Arrays;

/**
 * Refreshes a cycling share of the picture at a lower quantizer every inter frame (cyclic refresh
 * adaptive quantization): segment 1 codes the superblocks due for refresh at a boosted quality,
 * segment 2 boosts static blocks further, and segment 0 codes the rest at the frame's quantizer.
 * Static areas so converge to a high quality over a few dozen frames without the cost of a key
 * frame, and errors from lost frames heal.
 *
 * <p>Each frame goes through {@link #updateParameters}, {@link #setup}, then, per block,
 * {@link #segmentOf} before the mode decision and {@link #updateSegment} after it, and finally
 * {@link #skippedSegment} for the blocks that end up without residual.
 *
 * @implNote This implementation ports libaom's {@code av1/encoder/aq_cyclicrefresh.c}
 * ({@code av1_cyclic_refresh_update_parameters}, {@code av1_cyclic_refresh_setup},
 * {@code cyclic_refresh_update_map}, {@code candidate_refresh_aq},
 * {@code av1_cyclic_refresh_update_segment}, {@code av1_cyclic_reset_segment_skip},
 * {@code av1_cyclic_refresh_rc_bits_per_mb} and
 * {@code av1_cyclic_refresh_estimate_bits_at_q}) for one spatial and temporal layer of
 * camera content, without the noise estimate, active maps or the boosted segments'
 * Lagrangian multiplier.
 */
final class CyclicRefresh {
    /**
     * The segment of the blocks coded at the frame's quantizer.
     */
    static final int BASE = 0;

    /**
     * The segment of the refreshed blocks.
     */
    static final int BOOST1 = 1;

    /**
     * The segment of the refreshed static blocks, boosted further.
     */
    static final int BOOST2 = 2;

    /**
     * The largest rate ratio of the second segment's quantizer delta ({@code CR_MAX_RATE_TARGET_RATIO}).
     */
    private static final double MAX_RATE_TARGET_RATIO = 4.0;

    /**
     * The superblock size in 4x4 units.
     */
    private static final int SB_MI = 16;

    /**
     * The number of 4x4 columns.
     */
    private final int miCols;

    /**
     * The number of 4x4 rows.
     */
    private final int miRows;

    /**
     * The refresh state of each 4x4 unit: 0 a refresh candidate, 1 not a candidate, negative the
     * frames left until it becomes one again ({@code cr->map}).
     */
    private final byte[] map;

    /**
     * The segment the cycle assigns to each 4x4 unit before the mode decision.
     */
    private final byte[] segmentMap;

    /**
     * Whether the frame being coded uses the refresh.
     */
    boolean apply;

    /**
     * The share of the picture refreshed per frame, in percent.
     */
    int percentRefresh;

    /**
     * The adjustment of {@link #percentRefresh} learned from the rate control.
     */
    private int percentRefreshAdjustment = 5;

    /**
     * The rate ratio of the first segment's quantizer delta.
     */
    private double rateRatioQdelta;

    /**
     * The adjustment of {@link #rateRatioQdelta} learned from the rate control.
     */
    private double rateRatioQdeltaAdjustment = 0.25;

    /**
     * The largest quantizer reduction, in percent of the base quantizer index.
     */
    private int maxQdeltaPerc;

    /**
     * The motion vector component beyond which a block is not refreshed.
     */
    private int motionThresh;

    /**
     * The boost of the second segment over the first, in tenths.
     */
    private int rateBoostFac;

    /**
     * The block rate below which a static block joins the second segment.
     */
    private long threshRateSb;

    /**
     * The block distortion above which a moving or intra block leaves the refresh.
     */
    private long threshDistSb;

    /**
     * The superblock where the next cycle starts.
     */
    private int sbIndex;

    /**
     * The number of 4x4 units the cycle assigned to the refresh for this frame.
     */
    private int targetNumSegBlocks;

    /**
     * The number of 4x4 units coded in the first segment in the last frame.
     */
    long actualNumSeg1Blocks;

    /**
     * The number of 4x4 units coded in the second segment in the last frame.
     */
    long actualNumSeg2Blocks;

    /**
     * The number of consecutive frames coded with the refresh.
     */
    private int counterEncodeMaxqSceneChange;

    /**
     * The quantizer index delta of each segment.
     */
    final int[] qindexDelta = new int[3];

    /**
     * Whether the cycle skips every other 4x4 row when updating the map.
     */
    private boolean skipOver4x4;

    /**
     * Creates the refresh state of a picture size.
     *
     * @param miCols the number of 4x4 columns
     * @param miRows the number of 4x4 rows
     */
    CyclicRefresh(int miCols, int miRows) {
        this.miCols = miCols;
        this.miRows = miRows;
        this.map = new byte[miCols * miRows];
        this.segmentMap = new byte[miCols * miRows];
    }

    /**
     * Decides whether the next frame uses the refresh and with which strength
     * ({@code av1_cyclic_refresh_update_parameters}).
     *
     * @param key whether the frame is a key frame
     * @param sceneCut whether a scene change was detected
     * @param framesSinceKey the number of frames since the last key frame
     * @param avgQindexInter the running average quantizer index of inter frames
     * @param bestQuality the smallest allowed quantizer index
     * @param avgFrameLowMotion the running percentage of near-static blocks, 0 before the first
     * inter frame
     * @param speed the speed level
     * @param pixels the number of luma samples of a frame
     * @param avgFrameBandwidth the average frame size in bits
     * @param temporalId the frame's temporal layer
     * @param temporalLayers the number of temporal layers
     */
    void updateParameters(boolean key, boolean sceneCut, int framesSinceKey, int avgQindexInter, int bestQuality,
                          int avgFrameLowMotion, int speed, long pixels, int avgFrameBandwidth, int temporalId,
                          int temporalLayers) {
        var qpThresh = Math.max(16, bestQuality + 4);
        var qpMaxThresh = 118 * 255 >> 7;
        if (key || sceneCut) {
            percentRefreshAdjustment = 5;
            rateRatioQdeltaAdjustment = 0.25;
        }
        skipOver4x4 = speed > 9;
        apply = true;
        if (key || sceneCut || temporalId > 0 || avgQindexInter < qpThresh
                || (framesSinceKey > 20 && avgQindexInter > qpMaxThresh)
                || (avgFrameLowMotion > 0 && avgFrameLowMotion < 30 && framesSinceKey > 40)) {
            apply = false;
            return;
        }
        percentRefresh = temporalLayers > 2 ? 15 : 10 + percentRefreshAdjustment;
        maxQdeltaPerc = 60;
        motionThresh = 32;
        rateBoostFac = 15;
        if (framesSinceKey < 4 * temporalLayers * (100 / percentRefresh)) {
            rateRatioQdelta = 3.0 + rateRatioQdeltaAdjustment;
        } else {
            rateRatioQdelta = 2.25 + rateRatioQdeltaAdjustment;
        }
        if (pixels <= 352L * 288) {
            if (temporalLayers > 1) {
                motionThresh = 32;
                rateBoostFac = 13;
            } else if (avgFrameBandwidth < 3000) {
                motionThresh = 16;
                rateBoostFac = 13;
            } else {
                maxQdeltaPerc = 50;
                rateRatioQdelta = Math.max(rateRatioQdelta, 2.0);
            }
        }
    }

    /**
     * Prepares the frame's segments: their quantizer deltas, the refresh thresholds and the
     * segment of every 4x4 unit ({@code av1_cyclic_refresh_setup}).
     *
     * @param rc the rate control, for the rate model
     * @param key whether the frame is a key frame
     * @param sceneCut whether a scene change was detected
     * @param baseQindex the frame's quantizer index
     * @param frameTarget the frame's size target in bits
     * @param pixels the number of luma samples of a frame
     * @param speed the speed level
     * @param frame the frame encoder, for the 64x64 source SADs
     * @param framesSinceKey the number of frames since the last key frame
     */
    void setup(RateControl rc, boolean key, boolean sceneCut, int baseQindex, int frameTarget, long pixels, int speed,
               FrameEncoder frame, int framesSinceKey) {
        if (!apply) {
            Arrays.fill(segmentMap, (byte) BASE);
            if (key || sceneCut) {
                sbIndex = 0;
                counterEncodeMaxqSceneChange = 0;
                actualNumSeg1Blocks = 0;
                actualNumSeg2Blocks = 0;
            }
            return;
        }
        counterEncodeMaxqSceneChange++;
        var q = RateControl.qindexToQ(baseQindex);
        var sb64TargetRate = (int) Math.min(Integer.MAX_VALUE, ((long) frameTarget << 12) / pixels);
        threshRateSb = ((long) sb64TargetRate << 8) << 2;
        threshDistSb = ((long) (q * q)) << 2;
        if (speed <= 7 || pixels < 640L * 360) {
            threshDistSb = 0;
            threshRateSb = Long.MAX_VALUE;
        }
        qindexDelta[BASE] = 0;
        qindexDelta[BOOST1] = deltaq(rc, baseQindex, rateRatioQdelta);
        qindexDelta[BOOST2] = deltaq(rc, baseQindex,
                Math.min(MAX_RATE_TARGET_RATIO, 0.1 * rateBoostFac * rateRatioQdelta));
        updateMap(frame, framesSinceKey, pixels);
    }

    /**
     * Returns the quantizer delta that scales an inter frame quantizer's estimated rate by a ratio,
     * capped ({@code compute_deltaq}).
     *
     * @param rc the rate control
     * @param q the quantizer index
     * @param factor the rate ratio
     * @return the delta, zero or negative
     */
    private int deltaq(RateControl rc, int q, double factor) {
        var deltaq = rc.qdeltaByRate(false, q, factor);
        if (-deltaq > maxQdeltaPerc * q / 100) {
            deltaq = -maxQdeltaPerc * q / 100;
        }
        return deltaq;
    }

    /**
     * Assigns the next superblocks of the cycle to the first segment ({@code cyclic_refresh_update_map}).
     *
     * @param frame the frame encoder, for the 64x64 source SADs
     * @param framesSinceKey the number of frames since the last key frame
     * @param pixels the number of luma samples of a frame
     */
    private void updateMap(FrameEncoder frame, int framesSinceKey, long pixels) {
        Arrays.fill(segmentMap, (byte) BASE);
        var sbCols = (miCols + SB_MI - 1) / SB_MI;
        var sbRows = (miRows + SB_MI - 1) / SB_MI;
        var sbsInFrame = sbCols * sbRows;
        var blockCount = percentRefresh * miRows * miCols / 100;
        if (sbIndex >= sbsInFrame) {
            sbIndex = 0;
        }
        var i = sbIndex;
        targetNumSegBlocks = 0;
        var useSad = framesSinceKey > 30 && counterEncodeMaxqSceneChange > 30;
        do {
            var sumMap = 0;
            var sbRow = i / sbCols;
            var sbCol = i - sbRow * sbCols;
            var miRow = sbRow * SB_MI;
            var miCol = sbCol * SB_MI;
            var blIndex = miRow * miCols + miCol;
            var xmis = Math.min(miCols - miCol, SB_MI);
            var ymis = Math.min(miRows - miRow, SB_MI);
            long sbSad = 0;
            long threshSadLow = 0;
            long threshSad = Long.MAX_VALUE;
            if (useSad) {
                sbSad = frame.blockSourceSad(miCol * 4, miRow * 4);
                var scale = pixels < 640L * 360 ? 6 : 8;
                threshSad = scale * 64L * 64;
                threshSadLow = 2L * 64 * 64;
            }
            for (var y = 0; y < ymis; y += 2) {
                for (var x = 0; x < xmis; x += 2) {
                    var idx = blIndex + y * miCols + x;
                    if (map[idx] == 0 || sbSad < threshSadLow) {
                        sumMap += 4;
                    } else if (map[idx] < 0) {
                        map[idx]++;
                    }
                }
            }
            if (sumMap >= (xmis * ymis) >> 1 && sbSad < threshSad) {
                for (var y = 0; y < ymis; y++) {
                    Arrays.fill(segmentMap, blIndex + y * miCols, blIndex + y * miCols + xmis, (byte) BOOST1);
                }
                targetNumSegBlocks += xmis * ymis;
            }
            i++;
            if (i == sbsInFrame) {
                i = 0;
            }
        } while (targetNumSegBlocks < blockCount && i != sbIndex);
        sbIndex = i;
        if (targetNumSegBlocks == 0) {
            apply = false;
        }
    }

    /**
     * Returns the segment the cycle assigns to a block: the smallest over its 4x4 units
     * ({@code get_segment_id}).
     *
     * @param miRow the block row in 4x4 units
     * @param miCol the block column in 4x4 units
     * @param bh4 the block height in 4x4 units
     * @param bw4 the block width in 4x4 units
     * @return the segment
     */
    int segmentOf(int miRow, int miCol, int bh4, int bw4) {
        if (!apply) {
            return BASE;
        }
        var xmis = Math.min(miCols - miCol, bw4);
        var ymis = Math.min(miRows - miRow, bh4);
        var seg = 7;
        for (var y = 0; y < ymis; y++) {
            var p = (miRow + y) * miCols + miCol;
            for (var x = 0; x < xmis; x++) {
                seg = Math.min(seg, segmentMap[p + x]);
            }
        }
        return seg;
    }

    /**
     * Returns the segment a block keeps after its mode decision and updates the refresh state of
     * its 4x4 units ({@code candidate_refresh_aq} and {@code av1_cyclic_refresh_update_segment}).
     *
     * @param miRow the block row in 4x4 units
     * @param miCol the block column in 4x4 units
     * @param bh4 the block height in 4x4 units
     * @param bw4 the block width in 4x4 units
     * @param segment the segment the cycle assigned
     * @param intra whether the block is intra coded
     * @param mvRow the motion vector row
     * @param mvCol the motion vector column
     * @param large whether the block is at least 16x16
     * @param rate the decision's estimated rate
     * @param dist the decision's estimated distortion
     * @param skip whether the decision skips the residual
     * @return the segment
     */
    int updateSegment(int miRow, int miCol, int bh4, int bw4, int segment, boolean intra, int mvRow, int mvCol,
                      boolean large, long rate, long dist, boolean skip) {
        if (!apply) {
            return BASE;
        }
        int refresh;
        if (dist > threshDistSb && (mvRow > motionThresh || mvRow < -motionThresh || mvCol > motionThresh
                || mvCol < -motionThresh || intra)) {
            refresh = BASE;
        } else if (large && rate < threshRateSb && !intra && mvRow == 0 && mvCol == 0 && rateBoostFac > 10) {
            refresh = BOOST2;
        } else {
            refresh = BOOST1;
        }
        if (segment != BASE) {
            segment = skip ? BASE : refresh;
        }
        var xmis = Math.min(miCols - miCol, bw4);
        var ymis = Math.min(miRows - miRow, bh4);
        var index = miRow * miCols + miCol;
        int value = map[index];
        if (segment != BASE) {
            value = 0;
        } else if (refresh != BASE) {
            if (map[index] == 1) {
                value = 0;
            }
        } else {
            value = 1;
        }
        var step = skipOver4x4 ? 2 : 1;
        for (var y = 0; y < ymis; y += step) {
            var p = index + y * miCols;
            Arrays.fill(map, p, p + xmis, (byte) value);
        }
        return segment;
    }

    /**
     * Records the segment a block was finally coded in, for the rate model's segment weights
     * ({@code av1_cyclic_reset_segment_skip} and the block counters).
     *
     * @param miRow the block row in 4x4 units
     * @param miCol the block column in 4x4 units
     * @param bh4 the block height in 4x4 units
     * @param bw4 the block width in 4x4 units
     * @param decided the segment after the mode decision
     * @param coded the segment the block was coded in
     * @param counts receives the 4x4 units of the first and second segments
     */
    void skippedSegment(int miRow, int miCol, int bh4, int bw4, int decided, int coded, long[] counts) {
        var xmis = Math.min(miCols - miCol, bw4);
        var ymis = Math.min(miRows - miRow, bh4);
        if (decided != coded) {
            var index = miRow * miCols + miCol;
            for (var y = 0; y < ymis; y++) {
                var p = index + y * miCols;
                Arrays.fill(map, p, p + xmis, (byte) 0);
            }
        }
        if (coded == BOOST1) {
            counts[0] += (long) xmis * ymis;
        } else if (coded == BOOST2) {
            counts[1] += (long) xmis * ymis;
        }
    }

    /**
     * Returns the share of the picture the rate model weights with the boosted quantizer before
     * the frame is coded ({@code av1_cyclic_refresh_rc_bits_per_mb}).
     *
     * @return the share, 0 to 1
     */
    double segmentWeight() {
        var num4x4 = (double) miCols * miRows;
        return ((targetNumSegBlocks + actualNumSeg1Blocks + actualNumSeg2Blocks) >> 1) / num4x4;
    }

    /**
     * Returns the rate ratio of the first segment's quantizer delta.
     *
     * @return the ratio
     */
    double rateRatioQdelta() {
        return rateRatioQdelta;
    }

    /**
     * Returns the largest quantizer reduction, in percent.
     *
     * @return the percentage
     */
    int maxQdeltaPerc() {
        return maxQdeltaPerc;
    }

    /**
     * Returns the shares of the picture coded in the first and second segments in the last frame.
     *
     * @param out receives the shares
     */
    void codedWeights(double[] out) {
        var num4x4 = (double) miCols * miRows;
        out[0] = actualNumSeg1Blocks / num4x4;
        out[1] = actualNumSeg2Blocks / num4x4;
    }

    /**
     * Returns the number of consecutive frames coded with the refresh since the last key frame or
     * scene change ({@code counter_encode_maxq_scene_change}).
     *
     * @return the count
     */
    int encodedSinceSceneChange() {
        return counterEncodeMaxqSceneChange;
    }

    /**
     * Restarts the count of {@link #encodedSinceSceneChange} for a scene change coded at the
     * overshoot quantizer ({@code av1_encodedframe_overshoot_cbr}).
     */
    void restartSceneChangeCount() {
        counterEncodeMaxqSceneChange = 0;
    }

    /**
     * Adjusts the refresh strength from the rate model's error on the last frame (the cyclic
     * refresh part of {@code av1_rc_update_rate_correction_factors}).
     *
     * @param correction the ratio of the coded size to the estimate
     */
    void adjust(double correction) {
        if (correction > 1.25) {
            percentRefreshAdjustment = Math.max(percentRefreshAdjustment - 1, -5);
            rateRatioQdeltaAdjustment = Math.max(rateRatioQdeltaAdjustment - 0.05, -0.0);
        } else if (correction < 0.5) {
            percentRefreshAdjustment = Math.min(percentRefreshAdjustment + 1, 5);
            rateRatioQdeltaAdjustment = Math.min(rateRatioQdeltaAdjustment + 0.05, 0.25);
        }
    }
}
