package com.github.auties00.smalt.video.h264.internal;

import java.util.ArrayList;
import java.util.List;

/**
 * Holds one layer representation of a scalable access unit: the slices with one value of
 * {@code DQId} and the variables ITU-T H.264 F.7.4.3.4 derives for it, among them the geometry of
 * its reference layer representation (F.6.1 and F.6.3).
 *
 * <p>Only frames are described: every layer has {@code frame_mbs_only_flag} equal to 1.
 */
final class SvcRepresentation {
    /**
     * The {@code DQId}.
     */
    int dqId;

    /**
     * The slices, in decoding order.
     */
    final List<SvcSlice> slices = new ArrayList<>();

    /**
     * The sequence parameter set of the representation.
     */
    H264Sps sps;

    /**
     * The picture parameter set of the first slice.
     */
    H264Pps pps;

    /**
     * The width in macroblocks.
     */
    int widthMbs;

    /**
     * The height in macroblocks.
     */
    int heightMbs;

    /**
     * The {@code MaxRefLayerDQId}.
     */
    int maxRefLayerDqId;

    /**
     * The {@code MinNoInterLayerPredFlag}.
     */
    boolean minNoInterLayerPred;

    /**
     * The {@code MaxTCoeffLevelPredFlag}.
     */
    boolean maxTcoeffLevelPred;

    /**
     * The {@code SpatialResolutionChangeFlag}.
     */
    boolean spatialResolutionChange;

    /**
     * The {@code RestrictedSpatialResolutionChangeFlag}.
     */
    boolean restrictedSpatialResolutionChange;

    /**
     * The {@code CroppingChangeFlag}.
     */
    boolean croppingChange;

    /**
     * The {@code ScaledRefLayerLeftOffset} in luma samples.
     */
    int scaledLeft;

    /**
     * The {@code ScaledRefLayerTopOffset} in luma samples.
     */
    int scaledTop;

    /**
     * The {@code ScaledRefLayerRightOffset} in luma samples.
     */
    int scaledRight;

    /**
     * The {@code ScaledRefLayerBottomOffset} in luma samples.
     */
    int scaledBottom;

    /**
     * The {@code ScaledRefLayerPicWidthInSamplesL}.
     */
    int scaledWidth;

    /**
     * The {@code ScaledRefLayerPicHeightInSamplesL}.
     */
    int scaledHeight;

    /**
     * The {@code RefLayerPicWidthInMbs}.
     */
    int refWidthMbs;

    /**
     * The {@code RefLayerPicHeightInMbs}.
     */
    int refHeightMbs;

    /**
     * The {@code RefLayerPicWidthInSamplesL}.
     */
    int refWidth;

    /**
     * The {@code RefLayerPicHeightInSamplesL}.
     */
    int refHeight;

    /**
     * The {@code chroma_phase_x_plus1_flag} of the representation.
     */
    int chromaPhaseX;

    /**
     * The {@code chroma_phase_y_plus1} of the representation.
     */
    int chromaPhaseY;

    /**
     * The {@code ref_layer_chroma_phase_x_plus1_flag} of the slices with inter-layer prediction.
     */
    int refChromaPhaseX;

    /**
     * The {@code ref_layer_chroma_phase_y_plus1} of the slices with inter-layer prediction.
     */
    int refChromaPhaseY;

    /**
     * The {@code MbToSliceGroupMap}, or {@code null} for a single slice group.
     */
    int[] groupMap;

    /**
     * The slice of the representation that coded each macroblock, {@code null} for the
     * macroblocks no slice covers.
     */
    SvcSlice[] mbSlice = new SvcSlice[0];

    /**
     * The scaled reference layer window {@code {left, top, right, bottom}} in luma samples, the
     * right and bottom bounds exclusive.
     */
    final int[] crop = new int[4];

    /**
     * The {@code shiftX} of the luma reference location derivation of ITU-T H.264 F.6.1.
     */
    private int shiftX;

    /**
     * The {@code shiftY} of the luma reference location derivation of ITU-T H.264 F.6.1.
     */
    private int shiftY;

    /**
     * The {@code scaleX} of the luma reference location derivation of ITU-T H.264 F.6.1.
     */
    private int scaleX;

    /**
     * The {@code scaleY} of the luma reference location derivation of ITU-T H.264 F.6.1.
     */
    private int scaleY;

    /**
     * The {@code shiftX} of the sample location derivation of ITU-T H.264 F.6.3, for luma (index 0)
     * and chroma (index 1).
     */
    private final int[] rsShiftX = new int[2];

    /**
     * The {@code shiftY} of ITU-T H.264 F.6.3, for luma and chroma.
     */
    private final int[] rsShiftY = new int[2];

    /**
     * The {@code scaleX} of ITU-T H.264 F.6.3, for luma and chroma.
     */
    private final int[] rsScaleX = new int[2];

    /**
     * The {@code scaleY} of ITU-T H.264 F.6.3, for luma and chroma.
     */
    private final int[] rsScaleY = new int[2];

    /**
     * The left offset of the scaled reference layer window in samples of the component, for luma
     * and chroma ({@code offsetX} of ITU-T H.264 F.6.3).
     */
    private final int[] rsOffsetX = new int[2];

    /**
     * The top offset of the scaled reference layer window in samples of the component, for luma and
     * chroma ({@code offsetY} of ITU-T H.264 F.6.3).
     */
    private final int[] rsOffsetY = new int[2];

    /**
     * The {@code addX} of ITU-T H.264 F.6.3, for luma and chroma.
     */
    private final int[] rsAddX = new int[2];

    /**
     * The {@code addY} of ITU-T H.264 F.6.3, for luma and chroma.
     */
    private final int[] rsAddY = new int[2];

    /**
     * The {@code deltaX} of ITU-T H.264 F.6.3, for luma and chroma.
     */
    private final int[] rsDeltaX = new int[2];

    /**
     * The {@code deltaY} of ITU-T H.264 F.6.3, for luma and chroma.
     */
    private final int[] rsDeltaY = new int[2];

    /**
     * Constructs an empty representation.
     */
    SvcRepresentation() {
    }

    /**
     * Returns the first slice.
     *
     * @return the first slice of the representation
     */
    SvcSlice first() {
        return slices.getFirst();
    }

    /**
     * Returns the {@code dependency_id}.
     *
     * @return {@code DQId >> 4}
     */
    int dependencyId() {
        return dqId >> 4;
    }

    /**
     * Returns the {@code quality_id}.
     *
     * @return {@code DQId & 15}
     */
    int qualityId() {
        return dqId & 15;
    }

    /**
     * Returns the number of macroblocks.
     *
     * @return {@code PicSizeInMbs}
     */
    int mbCount() {
        return widthMbs * heightMbs;
    }

    /**
     * Derives the variables of the representation from its slices.
     *
     * @param ref the reference layer representation, with DQId {@link #maxRefLayerDqId}, or
     * {@code null} when the representation has none
     */
    void derive(SvcRepresentation ref) {
        var first = first();
        sps = first.header.sps;
        pps = first.header.pps;
        widthMbs = sps.widthInMbs;
        heightMbs = sps.frameHeightInMbs;
        minNoInterLayerPred = true;
        maxRefLayerDqId = -1;
        maxTcoeffLevelPred = false;
        SvcSlice predicted = null;
        for (var slice : slices) {
            minNoInterLayerPred &= slice.noInterLayerPred;
            maxRefLayerDqId = Math.max(maxRefLayerDqId, slice.refLayerDqId);
            maxTcoeffLevelPred |= slice.tcoeffLevelPrediction;
            if (predicted == null && !slice.noInterLayerPred) {
                predicted = slice;
            }
        }
        var source = predicted != null ? predicted : first;
        scaledLeft = 2 * source.scaledLeft;
        scaledRight = 2 * source.scaledRight;
        scaledTop = 2 * source.scaledTop;
        scaledBottom = 2 * source.scaledBottom;
        scaledWidth = widthMbs * 16 - scaledLeft - scaledRight;
        scaledHeight = heightMbs * 16 - scaledTop - scaledBottom;
        crop[0] = scaledLeft;
        crop[1] = scaledTop;
        crop[2] = scaledLeft + scaledWidth;
        crop[3] = scaledTop + scaledHeight;
        var svc = sps.svc;
        chromaPhaseX = svc != null ? svc.chromaPhaseXPlus1 : 1;
        chromaPhaseY = svc != null ? svc.chromaPhaseYPlus1 : 1;
        refChromaPhaseX = source.refLayerChromaPhaseXPlus1;
        refChromaPhaseY = source.refLayerChromaPhaseYPlus1;
        if (mbSlice.length != mbCount()) {
            mbSlice = new SvcSlice[mbCount()];
        } else {
            java.util.Arrays.fill(mbSlice, null);
        }
        groupMap = null;

        if (pps.numSliceGroups > 1) {
            groupMap = new int[mbCount()];
            SliceGroupMap.compute(pps, sps, first.header.sliceGroupChangeCycle, false, false, groupMap);
        }
        spatialResolutionChange = false;
        restrictedSpatialResolutionChange = true;
        croppingChange = false;
        if (ref == null) {
            refWidthMbs = 0;
            refHeightMbs = 0;
            refWidth = 0;
            refHeight = 0;
            return;
        }
        refWidthMbs = ref.widthMbs;
        refHeightMbs = ref.heightMbs;
        refWidth = ref.widthMbs * 16;
        refHeight = ref.heightMbs * 16;
        croppingChange = !minNoInterLayerPred && qualityId() == 0 && svc != null && svc.extendedSpatialScalability == 2;
        if (!minNoInterLayerPred && qualityId() == 0) {
            spatialResolutionChange = croppingChange || scaledWidth != refWidth || scaledHeight != refHeight
                    || scaledLeft % 16 != 0 || scaledTop % 16 != 0 || chromaPhaseX != refChromaPhaseX
                    || chromaPhaseY != refChromaPhaseY;
        }
        restrictedSpatialResolutionChange = !spatialResolutionChange
                || ((scaledWidth == refWidth || scaledWidth == 2 * refWidth)
                && (scaledHeight == refHeight || scaledHeight == 2 * refHeight)
                && scaledLeft % 16 == 0 && scaledTop % 16 == 0);
        var level = sps.levelIdc;
        shiftX = level <= 30 ? 16 : 31 - ceilLog2(refWidth);
        shiftY = level <= 30 ? 16 : 31 - ceilLog2(refHeight);
        scaleX = (int) ((((long) refWidth << shiftX) + (scaledWidth >> 1)) / scaledWidth);
        scaleY = (int) ((((long) refHeight << shiftY) + (scaledHeight >> 1)) / scaledHeight);
        for (var chroma = 0; chroma < 2; chroma++) {
            var refW = chroma == 0 ? refWidth : refWidth / 2;
            var refH = chroma == 0 ? refHeight : refHeight / 2;
            var scaledW = chroma == 0 ? scaledWidth : scaledWidth / 2;
            var scaledH = chroma == 0 ? scaledHeight : scaledHeight / 2;
            var refPhaseX = chroma == 0 ? 0 : refChromaPhaseX - 1;
            var refPhaseY = chroma == 0 ? 0 : refChromaPhaseY - 1;
            var phaseX = chroma == 0 ? 0 : chromaPhaseX - 1;
            var phaseY = chroma == 0 ? 0 : chromaPhaseY - 1;
            var sub = chroma == 0 ? 1 : 2;
            var sx = level <= 30 ? 16 : 31 - ceilLog2(refW);
            var sy = level <= 30 ? 16 : 31 - ceilLog2(refH);
            rsShiftX[chroma] = sx;
            rsShiftY[chroma] = sy;
            rsScaleX[chroma] = (int) ((((long) refW << sx) + (scaledW >> 1)) / scaledW);
            rsScaleY[chroma] = (int) ((((long) refH << sy) + (scaledH >> 1)) / scaledH);
            rsOffsetX[chroma] = scaledLeft / sub;
            rsOffsetY[chroma] = scaledTop / sub;
            rsAddX[chroma] = (int) (((((long) refW * (2 + phaseX)) << (sx - 2)) + (scaledW >> 1)) / scaledW
                    + (1 << (sx - 5)));
            rsAddY[chroma] = (int) (((((long) refH * (2 + phaseY)) << (sy - 2)) + (scaledH >> 1)) / scaledH
                    + (1 << (sy - 5)));
            rsDeltaX[chroma] = 4 * (2 + refPhaseX);
            rsDeltaY[chroma] = 4 * (2 + refPhaseY);
        }
    }

    /**
     * Returns {@code Ceil( Log2( value ) )}.
     *
     * @param value a positive value
     * @return the smallest {@code n} with {@code 2^n >= value}
     */
    private static int ceilLog2(int value) {
        return 32 - Integer.numberOfLeadingZeros(value - 1);
    }

    /**
     * Derives the reference layer macroblock and the location inside it that correspond to a
     * luma location of a macroblock (ITU-T H.264 F.6.1).
     *
     * @param mbAddr the macroblock address
     * @param xP the horizontal location inside the macroblock
     * @param yP the vertical location inside the macroblock
     * @return {@code (mbAddrRefLayer << 8) | (xB << 4) | yB}
     */
    int referenceLuma(int mbAddr, int xP, int yP) {
        var xC = (mbAddr % widthMbs) * 16 + xP;
        var yC = (mbAddr / widthMbs) * 16 + yP;
        var xRef = (int) (((long) (xC - scaledLeft) * scaleX + (1 << (shiftX - 1))) >> shiftX);
        var yRef = (int) (((long) (yC - scaledTop) * scaleY + (1 << (shiftY - 1))) >> shiftY);
        xRef = Math.clamp(xRef, 0, refWidth - 1);
        yRef = Math.clamp(yRef, 0, refHeight - 1);
        var mb = (yRef >> 4) * refWidthMbs + (xRef >> 4);
        return (mb << 8) | ((xRef & 15) << 4) | (yRef & 15);
    }

    /**
     * Derives the horizontal reference layer sample location of a sample column in units of
     * 1/16 sample (ITU-T H.264 F.6.3).
     *
     * @param chroma 0 for luma, 1 for chroma
     * @param xC the column in the current layer picture
     * @return {@code xRef16}
     */
    int refX16(int chroma, int xC) {
        var shift = rsShiftX[chroma];
        return (int) ((((long) (xC - rsOffsetX[chroma]) * rsScaleX[chroma] + rsAddX[chroma]) >> (shift - 4))
                - rsDeltaX[chroma]);
    }

    /**
     * Derives the vertical reference layer sample location of a sample row in units of 1/16
     * sample (ITU-T H.264 F.6.3).
     *
     * @param chroma 0 for luma, 1 for chroma
     * @param yC the row in the current layer picture
     * @return {@code yRef16}
     */
    int refY16(int chroma, int yC) {
        var shift = rsShiftY[chroma];
        return (int) ((((long) (yC - rsOffsetY[chroma]) * rsScaleY[chroma] + rsAddY[chroma]) >> (shift - 4))
                - rsDeltaY[chroma]);
    }
}
