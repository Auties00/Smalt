/* Ports libvpx 1.17.0 (vp9/encoder/vp9_aq_cyclicrefresh.c). Copyright (c) 2010, The WebM Project authors; BSD
   3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.video.vp9.internal;

import java.util.Arrays;

/**
 * Refreshes the background of a real-time VP9 stream a slice at a time by coding a rotating set
 * of superblocks at a lower quantizer through segmentation (adaptive quantization mode 3).
 *
 * <p>Each frame marks about a tenth of the frame's blocks, those not refreshed recently and
 * still static, as segment 1, whose quantizer is lowered so that its rate doubles or triples;
 * while coding, static blocks that are cheap move to segment 2 with an even lower quantizer and
 * blocks whose mode makes the refresh useless return to segment 0. Over a cycle every static
 * area gets coded at high quality once, which a low-delay stream without golden frame boosts
 * would otherwise never do.
 *
 * @implNote This implementation ports libvpx's {@code vp9/encoder/vp9_aq_cyclicrefresh.c},
 * BSD-3, with content mode on and without screen content, VBR or spatial layers.
 */
final class Vp9RtCyclicRefresh {
    /**
     * The segment of blocks coded at the frame quantizer.
     */
    static final int SEGMENT_BASE = 0;

    /**
     * The segment of refreshed blocks.
     */
    static final int SEGMENT_BOOST1 = 1;

    /**
     * The segment of refreshed static blocks with a stronger boost.
     */
    static final int SEGMENT_BOOST2 = 2;

    /**
     * The largest rate ratio of segment 2 ({@code CR_MAX_RATE_TARGET_RATIO}).
     */
    private static final double MAX_RATE_TARGET_RATIO = 4.0;

    /**
     * The largest quantizer index ({@code MAXQ}).
     */
    private static final int MAXQ = 255;

    /**
     * The refresh state of each 8x8 block: positive when not a candidate, zero when a candidate,
     * negative while recently refreshed.
     */
    byte[] map;

    /**
     * The quantizer each 8x8 block was last coded with.
     */
    byte[] lastCodedQMap;

    /**
     * The superblock the next refresh scan starts at.
     */
    int sbIndex;

    /**
     * The percentage of blocks refreshed per frame.
     */
    int percentRefresh;

    /**
     * The largest quantizer decrease, in percent of the quantizer.
     */
    int maxQdeltaPerc;

    /**
     * The frames a refreshed block waits before it can be refreshed again.
     */
    int timeForRefresh;

    /**
     * The largest motion vector component of a refreshed block with high distortion.
     */
    int motionThresh;

    /**
     * The rate boost of segment 2 relative to segment 1, in tenths.
     */
    int rateBoostFac;

    /**
     * The rate ratio of segment 1.
     */
    double rateRatioQdelta;

    /**
     * The rate below which a large static block moves to segment 2.
     */
    long threshRateSb;

    /**
     * The distortion above which a moving or intra block leaves the refresh.
     */
    long threshDistSb;

    /**
     * The quantizer index delta of each segment.
     */
    final int[] qindexDelta = new int[3];

    /**
     * The rate distortion multiplier of segment 1.
     */
    int rdmult;

    /**
     * Whether the refresh applies to the frame.
     */
    boolean apply;

    /**
     * Whether too few candidates were found and the refresh rate halves.
     */
    boolean reduceRefresh;

    /**
     * The share of boosted blocks the rate model assumes.
     */
    double weightSegment;

    /**
     * The number of segment 1 blocks of the last frame.
     */
    int actualNumSeg1Blocks;

    /**
     * The number of segment 2 blocks of the last frame.
     */
    int actualNumSeg2Blocks;

    /**
     * The number of blocks marked for refresh in the frame.
     */
    int targetNumSegBlocks;

    /**
     * The recursive average share of low-motion blocks.
     */
    double lowContentAvg;

    /**
     * The frames since a scene change was coded at a high quantizer.
     */
    int counterEncodeMaxqSceneChange;

    /**
     * Creates the refresh state of a frame size ({@code vp9_cyclic_refresh_alloc}).
     *
     * @param miRows the 8x8 rows
     * @param miCols the 8x8 columns
     */
    Vp9RtCyclicRefresh(int miRows, int miCols) {
        map = new byte[miRows * miCols];
        lastCodedQMap = new byte[miRows * miCols];
        Arrays.fill(lastCodedQMap, (byte) MAXQ);
    }

    /**
     * Returns whether a segment is boosted ({@code cyclic_refresh_segment_id_boosted}).
     *
     * @param segmentId the segment
     * @return whether it is segment 1 or 2
     */
    static boolean boosted(int segmentId) {
        return segmentId == SEGMENT_BOOST1 || segmentId == SEGMENT_BOOST2;
    }

    /**
     * Returns the segment a coded block should take ({@code candidate_refresh_aq}).
     *
     * @param mi the block's mode information
     * @param rate the block's estimated rate
     * @param dist the block's estimated distortion
     * @param bsize the block size
     * @return the segment
     */
    private int candidate(ModeInfo mi, long rate, long dist, int bsize) {
        var mvRow = mi.mv0 >> 16;
        var mvCol = (short) mi.mv0;
        if (dist > threshDistSb && (mvRow > motionThresh || mvRow < -motionThresh || mvCol > motionThresh
                || mvCol < -motionThresh || !mi.isInter())) {
            return SEGMENT_BASE;
        }
        if (bsize >= Vp9Constants.BLOCK_16X16 && rate < threshRateSb && mi.isInter() && mi.mv0 == 0
                && rateBoostFac > 10) {
            return SEGMENT_BOOST2;
        }
        return SEGMENT_BOOST1;
    }

    /**
     * Updates a coded block's segment and the refresh map ({@code vp9_cyclic_refresh_update_segment}).
     *
     * @param coder the coder on the block
     * @param bsize the block size
     * @param rate the block's estimated rate
     * @param dist the block's estimated distortion
     * @param skip whether the block is coded without residual
     */
    void updateSegment(Vp9RtCoder coder, int bsize, long rate, long dist, boolean skip) {
        var enc = coder.enc;
        var mi = coder.mi;
        var miCols = enc.miCols;
        var xmis = Math.min(miCols - coder.miCol, Vp9RtCoder.NUM_8X8_WIDE[bsize]);
        var ymis = Math.min(enc.miRows - coder.miRow, Vp9RtCoder.NUM_8X8_HIGH[bsize]);
        var blockIndex = coder.miRow * miCols + coder.miCol;
        var refresh = candidate(mi, rate, dist, bsize);
        if (refresh == 0 && bsize <= Vp9Constants.BLOCK_16X16 && enc.useSkinDetection
                && enc.content.isSkinBlock(coder.srcOff, bsize)) {
            refresh = 1;
        }
        var newValue = map[blockIndex];
        if (boosted(mi.segmentId)) {
            mi.segmentId = refresh;
            if (skip) {
                mi.segmentId = SEGMENT_BASE;
            }
        }
        if (boosted(mi.segmentId)) {
            newValue = (byte) -timeForRefresh;
        } else if (refresh != 0) {
            if (map[blockIndex] == 1) {
                newValue = 0;
            }
        } else {
            newValue = 1;
        }
        var segMap = enc.segmentationMap;
        for (var y = 0; y < ymis; y++) {
            for (var x = 0; x < xmis; x++) {
                var offset = blockIndex + y * miCols + x;
                map[offset] = newValue;
                segMap[offset] = (byte) mi.segmentId;
            }
        }
    }

    /**
     * Records the quantizer a coded block used ({@code vp9_cyclic_refresh_update_sb_postencode}).
     *
     * @param enc the encoder
     * @param mi the block's mode information
     * @param miRow the block's 8x8 row
     * @param miCol the block's 8x8 column
     * @param bsize the block size
     */
    void updateSbPostencode(Vp9RtEncoder enc, ModeInfo mi, int miRow, int miCol, int bsize) {
        var miCols = enc.miCols;
        var xmis = Math.min(miCols - miCol, Vp9RtCoder.NUM_8X8_WIDE[bsize]);
        var ymis = Math.min(enc.miRows - miRow, Vp9RtCoder.NUM_8X8_HIGH[bsize]);
        var blockIndex = miRow * miCols + miCol;
        if (mi.segmentId > SEGMENT_BOOST2) {
            return;
        }
        var q = Math.clamp(enc.baseQindex + qindexDelta[mi.segmentId], 0, MAXQ);
        for (var y = 0; y < ymis; y++) {
            for (var x = 0; x < xmis; x++) {
                var offset = blockIndex + y * miCols + x;
                if (!mi.isInter() || mi.skip == 0) {
                    lastCodedQMap[offset] = (byte) q;
                } else {
                    lastCodedQMap[offset] = (byte) Math.min(q, lastCodedQMap[offset] & 0xFF);
                }
            }
        }
    }

    /**
     * Counts the boosted blocks of the coded frame and vetoes a golden refresh of a frame with
     * too much motion ({@code vp9_cyclic_refresh_postencode}).
     *
     * @param enc the encoder
     */
    void postencode(Vp9RtEncoder enc) {
        var segMap = enc.segmentationMap;
        var grid = enc.miGrid;
        var n = enc.miRows * enc.miCols;
        actualNumSeg1Blocks = 0;
        actualNumSeg2Blocks = 0;
        var lowContent = 0;
        for (var i = 0; i < n; i++) {
            var seg = segMap[i];
            if (seg == SEGMENT_BOOST1) {
                actualNumSeg1Blocks++;
            } else if (seg == SEGMENT_BOOST2) {
                actualNumSeg2Blocks++;
            }
            var mi = grid[i];
            if (mi.isInter() && Math.abs(mi.mv0 >> 16) < 16 && Math.abs((short) mi.mv0) < 16) {
                lowContent++;
            }
        }
        if (enc.temporalLayers == 1) {
            var fractionLow = (double) lowContent / n;
            lowContentAvg = (fractionLow + 3 * lowContentAvg) / 4;
            var rc = enc.rc;
            if (enc.refreshGolden && rc.framesSinceKey > rc.framesSinceGolden + 1) {
                if (fractionLow < 0.65 || lowContentAvg < 0.6) {
                    enc.refreshGolden = false;
                }
                lowContentAvg = fractionLow;
            }
        }
    }

    /**
     * Sets the golden frame interval to a multiple of the refresh period
     * ({@code vp9_cyclic_refresh_set_golden_update}).
     *
     * @param rc the rate control
     */
    void setGoldenUpdate(Vp9RtRateControl rc) {
        rc.baselineGfInterval = percentRefresh > 0 ? Math.min(4 * (100 / percentRefresh), 40) : 40;
        if (rc.avgFrameLowMotion < 50 && rc.framesSinceKey > 40) {
            rc.baselineGfInterval = 10;
        }
    }

    /**
     * Decides whether the refresh applies to the next frame and sets its strength
     * ({@code vp9_cyclic_refresh_update_parameters}).
     *
     * @param enc the encoder
     */
    void updateParameters(Vp9RtEncoder enc) {
        var rc = enc.rc;
        var num8x8 = enc.mbs << 2;
        var qpThresh = Math.min(20, rc.bestQuality << 1);
        var qpMaxThresh = 117 * MAXQ >> 7;
        apply = true;
        if (enc.keyFrame || enc.temporalLayerId > 0 || rc.avgFrameQindexInter < qpThresh
                || (enc.temporalLayers > 1 && enc.layerKeyFrame())
                || (enc.temporalLayers == 1 && rc.avgFrameLowMotion < 20 && rc.framesSinceKey > 40)
                || (enc.temporalLayers == 1 && rc.avgFrameQindexInter > qpMaxThresh && rc.framesSinceKey > 20)) {
            apply = false;
            return;
        }
        percentRefresh = reduceRefresh ? 5 : 10;
        maxQdeltaPerc = 60;
        timeForRefresh = 0;
        motionThresh = 32;
        rateBoostFac = 15;
        if (rc.framesSinceKey < (4 * enc.temporalLayers) * (100 / percentRefresh)) {
            rateRatioQdelta = 3.0;
        } else {
            rateRatioQdelta = 2.0;
            if (enc.noise.enabled && enc.noise.level >= Vp9RtNoiseEstimate.MEDIUM) {
                rateRatioQdelta = 1.7;
                rateBoostFac = 13;
            }
        }
        if ((long) enc.width * enc.height <= 352 * 288) {
            if (rc.avgFrameBandwidth < 3000) {
                motionThresh = 64;
                rateBoostFac = 13;
            } else {
                maxQdeltaPerc = 70;
                rateRatioQdelta = Math.max(rateRatioQdelta, 2.5);
            }
        }
        var targetRefresh = percentRefresh * enc.miRows * enc.miCols / 100;
        var weightTarget = (double) targetRefresh / num8x8;
        var weight = (double) ((targetRefresh + actualNumSeg1Blocks + actualNumSeg2Blocks) >> 1) / num8x8;
        if (weightTarget < 7 * weight / 8) {
            weight = weightTarget;
        }
        weightSegment = weight;
    }

    /**
     * Returns the quantizer delta of a rate ratio, limited to the largest decrease
     * ({@code compute_deltaq}).
     *
     * @param enc the encoder
     * @param q the quantizer index
     * @param rateRatio the rate ratio
     * @return the delta
     */
    private int computeDeltaq(Vp9RtEncoder enc, int q, double rateRatio) {
        var deltaq = enc.rc.computeQdeltaByRate(enc.keyFrame, q, rateRatio);
        if (-deltaq > maxQdeltaPerc * q / 100) {
            deltaq = -maxQdeltaPerc * q / 100;
        }
        return deltaq;
    }

    /**
     * Returns the bits per macroblock the rate model predicts at a quantizer with the boosted
     * share of the frame ({@code vp9_cyclic_refresh_rc_bits_per_mb}).
     *
     * @param enc the encoder
     * @param i the quantizer index
     * @param correctionFactor the rate correction factor
     * @return the bits per macroblock
     */
    int bitsPerMb(Vp9RtEncoder enc, int i, double correctionFactor) {
        int deltaq;
        if (enc.speed < 8) {
            deltaq = computeDeltaq(enc, i, rateRatioQdelta);
        } else {
            deltaq = -(maxQdeltaPerc * i) / 200;
        }
        return (int) Math.round((1.0 - weightSegment) * Vp9RtRateControl.bitsPerMb(enc.keyFrame, i, correctionFactor)
                + weightSegment * Vp9RtRateControl.bitsPerMb(enc.keyFrame, i + deltaq, correctionFactor));
    }

    /**
     * Returns the size the rate model predicts for the coded frame with its boosted blocks
     * ({@code vp9_cyclic_refresh_estimate_bits_at_q}).
     *
     * @param enc the encoder
     * @param correctionFactor the rate correction factor
     * @return the size in bits
     */
    int estimateBitsAtQ(Vp9RtEncoder enc, double correctionFactor) {
        var mbs = enc.mbs;
        var num8x8 = mbs << 2;
        var w1 = (double) actualNumSeg1Blocks / num8x8;
        var w2 = (double) actualNumSeg2Blocks / num8x8;
        var q = enc.baseQindex;
        var k = enc.keyFrame;
        return (int) Math.round((1.0 - w1 - w2) * Vp9RtRateControl.estimateBitsAtQ(k, q, mbs, correctionFactor)
                + w1 * Vp9RtRateControl.estimateBitsAtQ(k, q + qindexDelta[1], mbs, correctionFactor)
                + w2 * Vp9RtRateControl.estimateBitsAtQ(k, q + qindexDelta[2], mbs, correctionFactor));
    }

    /**
     * Sets up the segmentation of the frame: the segment quantizers and the map of blocks to
     * refresh, or disables segmentation when the refresh does not apply
     * ({@code vp9_cyclic_refresh_setup}).
     *
     * @param enc the encoder
     */
    void setup(Vp9RtEncoder enc) {
        var rc = enc.rc;
        var sceneChange = rc.highSourceSad;
        if (enc.currentFrame == 0) {
            lowContentAvg = 0.0;
        }
        if (!apply || sceneChange) {
            Arrays.fill(enc.segmentationMap, (byte) 0);
            enc.disableSegmentation();
            if ((enc.keyFrame || sceneChange) && enc.temporalLayerId == 0) {
                Arrays.fill(lastCodedQMap, (byte) MAXQ);
                sbIndex = 0;
                reduceRefresh = false;
                counterEncodeMaxqSceneChange = 0;
            }
            return;
        }
        var q = Vp9EncTables.qindexToQ(enc.baseQindex);
        counterEncodeMaxqSceneChange++;
        threshRateSb = ((long) rc.sb64TargetRate << 8) << 2;
        threshDistSb = ((long) (q * q)) << 2;
        enc.enableSegmentation();
        var delta1 = computeDeltaq(enc, enc.baseQindex, rateRatioQdelta);
        qindexDelta[1] = delta1;
        var qindex2 = Math.clamp(enc.baseQindex + delta1, 0, MAXQ);
        rdmult = enc.computeRdMult(qindex2);
        var delta2 = computeDeltaq(enc, enc.baseQindex, Math.min(MAX_RATE_TARGET_RATIO, 0.1 * rateBoostFac
                * rateRatioQdelta));
        qindexDelta[2] = delta2;
        enc.setSegmentAltQ(1, delta1);
        enc.setSegmentAltQ(2, delta2);
        updateMap(enc);
    }

    /**
     * Marks the superblocks to refresh in the segmentation map, continuing the scan where the
     * previous frame stopped ({@code cyclic_refresh_update_map}).
     *
     * @param enc the encoder
     */
    private void updateMap(Vp9RtEncoder enc) {
        var segMap = enc.segmentationMap;
        var miRows = enc.miRows;
        var miCols = enc.miCols;
        Arrays.fill(segMap, (byte) SEGMENT_BASE);
        var sbCols = (miCols + 7) / 8;
        var sbRows = (miRows + 7) / 8;
        var sbsInFrame = sbCols * sbRows;
        var blockCount = percentRefresh * miRows * miCols / 100;
        var i = sbIndex;
        targetNumSegBlocks = 0;
        var consecZeroMvThresh = 100;
        var qindexThresh = enc.segQindex(SEGMENT_BOOST1);
        var noisy = enc.noise.enabled && enc.noise.level >= Vp9RtNoiseEstimate.MEDIUM;
        if (noisy) {
            consecZeroMvThresh = 60;
            qindexThresh = Math.max(enc.segQindex(SEGMENT_BOOST1), enc.baseQindex);
        }
        var countSel = 0;
        var countTot = 0;
        var consecZeroMv = enc.consecZeroMv;
        do {
            var sumMap = 0;
            var threshBlock = consecZeroMvThresh;
            var sbRowIndex = i / sbCols;
            var sbColIndex = i - sbRowIndex * sbCols;
            var miRow = sbRowIndex * 8;
            var miCol = sbColIndex * 8;
            var blIndex = miRow * miCols + miCol;
            var xmis = Math.min(miCols - miCol, 8);
            var ymis = Math.min(miRows - miRow, 8);
            if (noisy && (xmis <= 2 || ymis <= 2)) {
                threshBlock = 4;
            }
            for (var y = 0; y < ymis; y++) {
                for (var x = 0; x < xmis; x++) {
                    var index = blIndex + y * miCols + x;
                    if (map[index] == 0) {
                        countTot++;
                        if ((lastCodedQMap[index] & 0xFF) > qindexThresh || (consecZeroMv[index] & 0xFF) < threshBlock) {
                            sumMap++;
                            countSel++;
                        }
                    } else if (map[index] < 0) {
                        map[index]++;
                    }
                }
            }
            if (sumMap >= xmis * ymis / 2) {
                for (var y = 0; y < ymis; y++) {
                    for (var x = 0; x < xmis; x++) {
                        segMap[blIndex + y * miCols + x] = (byte) SEGMENT_BOOST1;
                    }
                }
                targetNumSegBlocks += xmis * ymis;
            }
            i++;
            if (i == sbsInFrame) {
                i = 0;
            }
        } while (targetNumSegBlocks < blockCount && i != sbIndex);
        sbIndex = i;
        reduceRefresh = countSel < (3 * countTot) >> 2;
    }
}
