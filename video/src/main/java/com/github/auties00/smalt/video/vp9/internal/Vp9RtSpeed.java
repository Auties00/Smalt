/* Ports libvpx 1.17.0 (vp9_speed_features.c). Copyright (c) 2010, The WebM Project authors; BSD 3-Clause License.
   Modified for Smalt. */
package com.github.auties00.smalt.video.vp9.internal;

import java.util.Arrays;

/**
 * Holds the speed features of the real-time VP9 encoder: the shortcuts each {@code cpu-used}
 * speed from 5 to 9 takes in the mode decision, motion search and partitioning.
 *
 * <p>{@link #set} recomputes the features before every frame from the speed, the frame type and
 * the rate control's state. Fields that libvpx never resets keep their values from frame to
 * frame, as they do there.
 *
 * @implNote This implementation follows libvpx's {@code set_rt_speed_feature_framesize_independent}
 * and {@code set_rt_speed_feature_framesize_dependent} ({@code vp9_speed_features.c},
 * BSD-3) for one-pass constant bitrate coding of camera content (not screen content),
 * without the features of spatial layers, dynamic resizing, the denoiser and the
 * machine-learned partitioning of small frames at speed 8, for which the variance
 * partitioning stands in. Speed 5's reference partition search is replaced by the
 * variance partitioning of speed 6.
 */
final class Vp9RtSpeed {
    /**
     * The full-pixel search with eight points per step ({@code NSTEP}).
     */
    static final int NSTEP = 1;

    /**
     * The hexagonal pattern search ({@code FAST_HEX}).
     */
    static final int FAST_HEX = 5;

    /**
     * The diamond pattern search ({@code FAST_DIAMOND}).
     */
    static final int FAST_DIAMOND = 6;

    /**
     * Stops the sub-pixel search at 1/8 pixel.
     */
    static final int EIGHTH_PEL = 0;

    /**
     * Stops the sub-pixel search at 1/4 pixel.
     */
    static final int QUARTER_PEL = 1;

    /**
     * Stops the sub-pixel search at 1/2 pixel.
     */
    static final int HALF_PEL = 2;

    /**
     * No reaction to a scene change.
     */
    static final int NO_DETECTION = 0;

    /**
     * Raises the quantizer of a scene change before coding it ({@code FAST_DETECTION_MAXQ}).
     */
    static final int FAST_DETECTION_MAXQ = 1;

    /**
     * Codes a scene change again at a higher quantizer when it overshoots
     * ({@code RE_ENCODE_MAXQ}).
     */
    static final int RE_ENCODE_MAXQ = 2;

    /**
     * Mask of the inter modes {@code NEARESTMV}, {@code ZEROMV} and {@code NEWMV}, by mode
     * number.
     */
    static final int INTER_NEAREST_NEW_ZERO = (1 << Vp9Constants.NEARESTMV) | (1 << Vp9Constants.ZEROMV)
            | (1 << Vp9Constants.NEWMV);

    /**
     * Mask of every inter mode.
     */
    static final int INTER_ALL = INTER_NEAREST_NEW_ZERO | (1 << Vp9Constants.NEARMV);

    /**
     * Mask of the DC intra mode only.
     */
    static final int INTRA_DC = 1 << Vp9Constants.DC_PRED;

    /**
     * Mask of the DC, vertical and horizontal intra modes.
     */
    static final int INTRA_DC_H_V = INTRA_DC | (1 << Vp9Constants.V_PRED) | (1 << Vp9Constants.H_PRED);

    /**
     * The motion search method: {@link #NSTEP}, {@link #FAST_HEX} or {@link #FAST_DIAMOND}.
     */
    int searchMethod;

    /**
     * The step parameter of the full-pixel search, larger meaning smaller first steps.
     */
    int fullpelSearchStepParam;

    /**
     * The precision the sub-pixel search stops at.
     */
    int subpelForceStop;

    /**
     * Whether the sub-pixel stopping precision depends on the motion's size.
     */
    boolean adaptiveSubpelForceStop;

    /**
     * The full-pixel component magnitude from which the coarser stop applies.
     */
    int adaptiveSubpelMvThresh;

    /**
     * The stopping precision below {@link #adaptiveSubpelMvThresh}.
     */
    int adaptiveSubpelBelow;

    /**
     * The stopping precision from {@link #adaptiveSubpelMvThresh} up.
     */
    int adaptiveSubpelAbove;

    /**
     * The refinement iterations of each sub-pixel step ({@code subpel_search_level}).
     */
    int subpelIters;

    /**
     * Whether the rate distortion thresholds adapt to the modes chosen, and by which factor.
     */
    int adaptiveRdThresh;

    /**
     * The inter modes tested at each block size, as masks by mode number.
     */
    final int[] interModeMask = new int[13];

    /**
     * The intra modes tested in inter frames at each block size, as masks by mode number.
     */
    final int[] intraYModeBsizeMask = new int[13];

    /**
     * The largest block size tested for intra modes in inter frames.
     */
    int maxIntraBsize;

    /**
     * Whether the best inter prediction is kept for coding the block.
     */
    boolean reuseInterPredSby;

    /**
     * Whether the coefficients of inter frames are quantized with the fast quantizer.
     */
    boolean useQuantFp;

    /**
     * Whether coefficient probability updates skip the dry run.
     */
    boolean oneLoopReducedCoefUpdates;

    /**
     * The probability step of the coefficient model update search.
     */
    int coeffProbApproxStep;

    /**
     * Whether key frames use the fast intra mode decision for every block size.
     */
    boolean nonrdKeyframe;

    /**
     * Whether blocks of low temporal variance skip golden and large motion tests, and how
     * aggressively (0 to 3).
     */
    int shortCircuitLowTempVar;

    /**
     * Whether the new motion search of flat blocks ends early.
     */
    boolean limitNewmvEarlyExit;

    /**
     * Whether the golden reference is tested with a raised threshold.
     */
    boolean biasGolden;

    /**
     * Whether small blocks estimate their rate and distortion from the variance only.
     */
    boolean useSimpleBlockYrd;

    /**
     * How the interpolation filter search is reduced: 0 none, otherwise a checkerboard.
     */
    int cbPredFilterSearch;

    /**
     * Whether superblock partitions are copied from the previous frame where the source is
     * static.
     */
    boolean copyPartitionFlag;

    /**
     * Whether the source difference with the previous frame steers the partitioning.
     */
    boolean useSourceSad;

    /**
     * Whether partitions below 16x16 are disabled in inter frames.
     */
    boolean disable16x16PartNonkey;

    /**
     * Whether the golden reference is disabled.
     */
    boolean disableGoldenRef;

    /**
     * The multiplier of the variance partitioning thresholds.
     */
    int variancePartThreshMult;

    /**
     * Whether only DC intra prediction is tested outside high-motion superblocks.
     */
    boolean rtIntraDcOnlyLowContent;

    /**
     * How the encoder reacts to a scene change: {@link #NO_DETECTION},
     * {@link #FAST_DETECTION_MAXQ} or {@link #RE_ENCODE_MAXQ}.
     */
    int overshootDetectionCbrRt;

    /**
     * Whether the coding of blocks that need no residual is skipped in mode decisions.
     */
    boolean skipEncodeSb;

    /**
     * Whether the mode decision of this frame predicts intra blocks from the source; set from
     * the previous frame's statistics and never reset.
     */
    boolean skipEncodeFrame;

    /**
     * The default interpolation filter of the frame: {@link Vp9Constants#SWITCHABLE} or
     * {@link Vp9Constants#BILINEAR}.
     */
    int defaultInterpFilter;

    /**
     * Whether the reference frames are masked by their prediction errors.
     */
    boolean referenceMasking;

    /**
     * The maximum number of frames a superblock partition is copied in a row.
     */
    int maxCopiedFrame;

    /**
     * Whether the sub-pixel search is the most pruned tree search, used by non-reference frames
     * of upper temporal layers ({@code SUBPEL_TREE_PRUNED_EVENMORE}).
     */
    boolean subpelPrunedEvenMore;

    /**
     * Creates features with libvpx's initial values.
     */
    Vp9RtSpeed() {
    }

    /**
     * Recomputes the features for the next frame.
     *
     * <p>The defaults apply first, then the features of speeds 2 to 5, which every supported speed
     * shares, then those of each higher speed in turn up to the configured one, in the cascade of
     * speed thresholds libvpx uses.
     *
     * @param speed the speed, from 5 to 9
     * @param keyFrame whether the frame is a key frame
     * @param width the frame width
     * @param height the frame height
     * @param useSvc whether temporal layers are coded
     * @param temporalLayerId the frame's temporal layer
     * @param temporalLayers the number of temporal layers
     * @param nonReferenceFrame whether no reference is refreshed by the frame
     * @param noiseHigh whether the noise estimate is enabled and at least medium at 720p
     * and above
     * @param lastFrameDropped whether the previous frame was dropped
     * @param avgFrameLowMotion the rate control's average share of low-motion blocks
     * @param avgInterQindex the rate control's average inter frame quantizer index
     * @param currentVideoFrame the number of frames coded so far
     */
    void set(int speed, boolean keyFrame, int width, int height, boolean useSvc, int temporalLayerId,
             int temporalLayers, boolean nonReferenceFrame, boolean noiseHigh, boolean lastFrameDropped,
             int avgFrameLowMotion, int avgInterQindex, long currentVideoFrame) {
        searchMethod = NSTEP;
        fullpelSearchStepParam = 6;
        subpelForceStop = EIGHTH_PEL;
        adaptiveSubpelForceStop = false;
        subpelIters = 2;
        adaptiveRdThresh = 1;
        Arrays.fill(interModeMask, INTER_ALL);
        maxIntraBsize = Vp9Constants.BLOCK_64X64;
        reuseInterPredSby = false;
        useQuantFp = false;
        oneLoopReducedCoefUpdates = false;
        coeffProbApproxStep = 1;
        nonrdKeyframe = false;
        shortCircuitLowTempVar = 0;
        limitNewmvEarlyExit = false;
        biasGolden = false;
        useSimpleBlockYrd = false;
        cbPredFilterSearch = 0;
        copyPartitionFlag = false;
        useSourceSad = false;
        disable16x16PartNonkey = false;
        disableGoldenRef = false;
        variancePartThreshMult = 1;
        rtIntraDcOnlyLowContent = false;
        overshootDetectionCbrRt = NO_DETECTION;
        skipEncodeSb = false;
        defaultInterpFilter = Vp9Constants.SWITCHABLE;
        referenceMasking = false;
        var area = width * height;
        referenceMasking = true;
        subpelIters = 0;
        adaptiveRdThresh = 4;
        skipEncodeSb = true;
        subpelForceStop = QUARTER_PEL;
        searchMethod = FAST_HEX;
        maxIntraBsize = Vp9Constants.BLOCK_32X32;
        useQuantFp = !keyFrame;
        for (var b = Vp9Constants.BLOCK_32X32; b <= Vp9Constants.BLOCK_64X64; b++) {
            interModeMask[b] = INTER_NEAREST_NEW_ZERO;
        }
        adaptiveRdThresh = 2;
        reuseInterPredSby = true;
        coeffProbApproxStep = 4;
        oneLoopReducedCoefUpdates = !keyFrame;
        if (!keyFrame) {
            for (var b = 0; b < 13; b++) {
                intraYModeBsizeMask[b] = b > Vp9Constants.BLOCK_16X16 ? INTRA_DC : INTRA_DC_H_V;
            }
        }
        limitNewmvEarlyExit = true;
        if (!useSvc) {
            biasGolden = true;
        }
        if (!keyFrame) {
            overshootDetectionCbrRt = area <= 352 * 288 && !useSvc ? RE_ENCODE_MAXQ : FAST_DETECTION_MAXQ;
        }
        if (area > 1280 * 720) {
            cbPredFilterSearch = 2;
        }
        useSourceSad = true;
        if (speed >= 6) {
            searchMethod = NSTEP;
            skipEncodeSb = false;
            shortCircuitLowTempVar = 1;
            if (temporalLayerId > 0) {
                adaptiveRdThresh = 4;
                limitNewmvEarlyExit = false;
            }
            if (!keyFrame) {
                overshootDetectionCbrRt = FAST_DETECTION_MAXQ;
            }
        }
        if (speed >= 7) {
            adaptiveRdThresh = 3;
            searchMethod = FAST_DIAMOND;
            fullpelSearchStepParam = 10;
            if (temporalLayers > 2 && temporalLayerId == 0) {
                searchMethod = NSTEP;
                fullpelSearchStepParam = 6;
            }
            if (temporalLayerId > 0) {
                useSimpleBlockYrd = true;
            }
            maxCopiedFrame = 0;
            if (!lastFrameDropped) {
                copyPartitionFlag = true;
                maxCopiedFrame = 2;
                if (temporalLayers > 1 && temporalLayerId == temporalLayers - 1) {
                    maxCopiedFrame = 255;
                }
            }
            if (area > 640 * 480) {
                cbPredFilterSearch = 2;
            }
        }
        if (speed >= 8) {
            adaptiveRdThresh = 4;
            skipEncodeSb = true;
            nonrdKeyframe = true;
            if (!useSvc) {
                maxCopiedFrame = 4;
            }
            rtIntraDcOnlyLowContent = true;
            if (!useSvc) {
                shortCircuitLowTempVar = noiseHigh && width >= 1280 && height >= 720 ? 2 : 3;
                adaptiveRdThresh = area > 352 * 288 ? 1 : 2;
            }
            limitNewmvEarlyExit = false;
            useSimpleBlockYrd = true;
            if (area > 352 * 288) {
                cbPredFilterSearch = 2;
            }
        }
        if (speed >= 9) {
            if (!keyFrame) {
                for (var b = 0; b < 13; b++) {
                    intraYModeBsizeMask[b] = INTRA_DC;
                }
            }
            cbPredFilterSearch = 2;
            adaptiveSubpelForceStop = true;
            adaptiveSubpelMvThresh = 1;
            adaptiveSubpelBelow = QUARTER_PEL;
            adaptiveSubpelAbove = HALF_PEL;
            if (!keyFrame && width >= 320 && height >= 240) {
                disable16x16PartNonkey = true;
            }
            disableGoldenRef = true;
            if (avgFrameLowMotion < 70) {
                defaultInterpFilter = Vp9Constants.BILINEAR;
            }
            if (area >= 640 * 360) {
                variancePartThreshMult = 2;
            }
        }
        if (!keyFrame && area <= 320 * 240 && avgInterQindex > 208 && currentVideoFrame > 8) {
            disable16x16PartNonkey = true;
        }
        subpelPrunedEvenMore = speed >= 7 && temporalLayerId > 0 && nonReferenceFrame;
    }
}
