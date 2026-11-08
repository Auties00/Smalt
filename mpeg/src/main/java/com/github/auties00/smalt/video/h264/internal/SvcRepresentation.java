package com.github.auties00.smalt.video.h264.internal;

import java.util.ArrayList;
import java.util.List;

/**
 * Holds one layer representation of a scalable access unit: the slices with one value of
 * {@code DQId} and the variables ITU-T H.264 F.7.4.3.4 derives for it, among them the geometry of
 * its reference layer representation (F.6.1 and F.6.3).
 *
 * <p>A representation is a frame, a frame with {@code MbaffFrameFlag} 1 or a field: its picture
 * height {@link #heightMbs} is {@code PicHeightInMbs}, half the frame height for a field.
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
     * The {@code PicHeightInMbs}.
     */
    int heightMbs;

    /**
     * The {@code frame_mbs_only_flag}.
     */
    boolean frameMbsOnly;

    /**
     * The {@code field_pic_flag}.
     */
    boolean fieldPic;

    /**
     * The {@code bottom_field_flag}.
     */
    boolean bottomField;

    /**
     * The {@code MbaffFrameFlag}.
     */
    boolean mbaff;

    /**
     * The {@code RefLayerFrameMbsOnlyFlag}.
     */
    boolean refFrameMbsOnly = true;

    /**
     * The {@code RefLayerFieldPicFlag}.
     */
    boolean refFieldPic;

    /**
     * The {@code RefLayerBottomFieldFlag}.
     */
    boolean refBottomField;

    /**
     * The {@code RefLayerMbaffFrameFlag}.
     */
    boolean refMbaff;

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
     * The {@code ScaledRefLayerTopOffset} in luma samples of a frame.
     */
    int scaledTop;

    /**
     * The {@code ScaledRefLayerRightOffset} in luma samples.
     */
    int scaledRight;

    /**
     * The {@code ScaledRefLayerBottomOffset} in luma samples of a frame.
     */
    int scaledBottom;

    /**
     * The {@code ScaledRefLayerPicWidthInSamplesL}.
     */
    int scaledWidth;

    /**
     * The {@code ScaledRefLayerPicHeightInSamplesL}, in samples of the picture (a field for a
     * field picture).
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
     * The {@code offsetX} of ITU-T H.264 F.6.3, for luma and chroma.
     */
    private final int[] rsOffsetX = new int[2];

    /**
     * The {@code offsetY} of ITU-T H.264 F.6.3, for luma and chroma.
     */
    private final int[] rsOffsetY = new int[2];

    /**
     * The {@code addX} of ITU-T H.264 F.6.3, for luma and chroma.
     */
    private final int[] rsAddX = new int[2];

    /**
     * The {@code addY} of ITU-T H.264 F.6.3, indexed {@code chroma * 2 + botFieldFlag}.
     */
    private final int[] rsAddY = new int[4];

    /**
     * The {@code deltaX} of ITU-T H.264 F.6.3, for luma and chroma.
     */
    private final int[] rsDeltaX = new int[2];

    /**
     * The {@code deltaY} of ITU-T H.264 F.6.3, indexed {@code chroma * 2 + botFieldFlag}.
     */
    private final int[] rsDeltaY = new int[4];

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
     * Returns whether the representation or its reference layer representation is interlaced:
     * {@code RefLayerFrameMbsOnlyFlag} or {@code frame_mbs_only_flag} is 0.
     *
     * @return {@code true} when the field-based resampling rules apply
     */
    boolean interlacedResampling() {
        return !refFrameMbsOnly || !frameMbsOnly;
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
        frameMbsOnly = sps.frameMbsOnly;
        fieldPic = first.header.fieldPic;
        bottomField = first.header.bottomField;
        mbaff = sps.mbAdaptiveFrameField && !fieldPic;
        heightMbs = fieldPic ? sps.frameHeightInMbs / 2 : sps.frameHeightInMbs;
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
        var interlaceFactor = frameMbsOnly ? 1 : 2;
        scaledLeft = 2 * source.scaledLeft;
        scaledRight = 2 * source.scaledRight;
        scaledTop = 2 * source.scaledTop * interlaceFactor;
        scaledBottom = 2 * source.scaledBottom * interlaceFactor;
        scaledWidth = widthMbs * 16 - scaledLeft - scaledRight;
        scaledHeight = heightMbs * 16 - (scaledTop + scaledBottom) / (fieldPic ? 2 : 1);
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
            SliceGroupMap.compute(pps, sps, first.header.sliceGroupChangeCycle, fieldPic, mbaff, groupMap);
        }
        spatialResolutionChange = false;
        restrictedSpatialResolutionChange = true;
        croppingChange = false;
        if (ref == null) {
            refWidthMbs = 0;
            refHeightMbs = 0;
            refWidth = 0;
            refHeight = 0;
            refFrameMbsOnly = true;
            refFieldPic = false;
            refBottomField = false;
            refMbaff = false;
            return;
        }
        refWidthMbs = ref.widthMbs;
        refHeightMbs = ref.heightMbs;
        refWidth = ref.widthMbs * 16;
        refHeight = ref.heightMbs * 16;
        refFrameMbsOnly = ref.frameMbsOnly;
        refFieldPic = ref.fieldPic;
        refBottomField = ref.bottomField;
        refMbaff = ref.mbaff;
        croppingChange = !minNoInterLayerPred && qualityId() == 0 && svc != null && svc.extendedSpatialScalability == 2;
        var field = fieldPic ? 1 : 0;
        if (!minNoInterLayerPred && qualityId() == 0) {
            spatialResolutionChange = croppingChange || scaledWidth != refWidth || scaledHeight != refHeight
                    || scaledLeft % 16 != 0 || scaledTop % (16 * (1 + field + (mbaff ? 1 : 0))) != 0
                    || fieldPic != refFieldPic || mbaff != refMbaff
                    || sps.chromaFormatIdc != ref.sps.chromaFormatIdc || chromaPhaseX != refChromaPhaseX
                    || chromaPhaseY != refChromaPhaseY;
        }
        restrictedSpatialResolutionChange = !spatialResolutionChange
                || ((scaledWidth == refWidth || scaledWidth == 2 * refWidth)
                && (scaledHeight == refHeight || scaledHeight == 2 * refHeight)
                && scaledLeft % 16 == 0 && scaledTop % (16 * (1 + field)) == 0 && !mbaff && !refMbaff
                && fieldPic == refFieldPic);
        var level = sps.levelIdc;
        shiftX = level <= 30 ? 16 : 31 - ceilLog2(refWidth);
        shiftY = level <= 30 ? 16 : 31 - ceilLog2(refHeight);
        scaleX = (int) ((((long) refWidth << shiftX) + (scaledWidth >> 1)) / scaledWidth);
        scaleY = (int) ((((long) refHeight << shiftY) + (scaledHeight >> 1)) / scaledHeight);
        var interlaced = interlacedResampling();
        for (var chroma = 0; chroma < 2; chroma++) {
            var sub = chroma == 0 ? 1 : 2;
            var refW = refWidth / sub;
            var refH = refHeight / sub * (refFieldPic ? 2 : 1);
            var scaledW = scaledWidth / sub;
            var scaledH = scaledHeight / sub * (fieldPic ? 2 : 1);
            if (!frameMbsOnly && refFrameMbsOnly) {
                scaledH /= 2;
            }
            var refPhaseX = chroma == 0 ? 0 : refChromaPhaseX - 1;
            var phaseX = chroma == 0 ? 0 : chromaPhaseX - 1;
            var sx = level <= 30 ? 16 : 31 - ceilLog2(refW);
            var sy = level <= 30 ? 16 : 31 - ceilLog2(refH);
            rsShiftX[chroma] = sx;
            rsShiftY[chroma] = sy;
            rsScaleX[chroma] = (int) ((((long) refW << sx) + (scaledW >> 1)) / scaledW);
            rsScaleY[chroma] = (int) ((((long) refH << sy) + (scaledH >> 1)) / scaledH);
            rsOffsetX[chroma] = scaledLeft / sub;
            rsAddX[chroma] = (int) (((((long) refW * (2 + phaseX)) << (sx - 2)) + (scaledW >> 1)) / scaledW
                    + (1 << (sx - 5)));
            rsDeltaX[chroma] = 4 * (2 + refPhaseX);
            rsOffsetY[chroma] = interlaced ? scaledTop / (2 * sub) : scaledTop / sub;
            for (var bottom = 0; bottom < 2; bottom++) {
                var refPhaseY = chroma == 0 ? 0 : refChromaPhaseY - 1;
                var phaseY = chroma == 0 ? 0 : chromaPhaseY - 1;
                if (interlaced) {
                    if (refFrameMbsOnly) {
                        phaseY = phaseY + 4 * bottom + 3 - sub;
                        refPhaseY = 2 * refPhaseY + 2;
                    } else {
                        phaseY = phaseY + 4 * bottom;
                        refPhaseY = refPhaseY + 4 * bottom;
                    }
                    rsAddY[chroma * 2 + bottom] = (int) (((((long) refH * (2 + phaseY)) << (sy - 3)) + (scaledH >> 1))
                            / scaledH + (1 << (sy - 5)));
                    rsDeltaY[chroma * 2 + bottom] = 2 * (2 + refPhaseY);
                } else {
                    rsAddY[chroma * 2 + bottom] = (int) (((((long) refH * (2 + phaseY)) << (sy - 2)) + (scaledH >> 1))
                            / scaledH + (1 << (sy - 5)));
                    rsDeltaY[chroma * 2 + bottom] = 4 * (2 + refPhaseY);
                }
            }
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
     * Returns whether a macroblock lies inside the window of the scaled reference layer, the
     * {@code InCropWindow()} function of ITU-T H.264 F.7.4.6 for a slice that predicts from a
     * reference layer.
     *
     * @param mbAddr the macroblock address
     * @return {@code true} when inter-layer prediction applies to the macroblock
     */
    boolean inCropWindow(int mbAddr) {
        var mbX = (mbAddr / (mbaff ? 2 : 1)) % widthMbs;
        int mbY0;
        int mbY1;
        if (!mbaff) {
            mbY0 = mbAddr / widthMbs;
            mbY1 = mbY0;
        } else {
            mbY0 = 2 * ((mbAddr / widthMbs) / 2);
            mbY1 = mbY0 + 1;
        }
        var scalMbH = fieldPic ? 32 : 16;
        var frameHeight = scaledHeight * (fieldPic ? 2 : 1);
        return mbX >= (scaledLeft + 15) / 16 && mbX < (scaledLeft + scaledWidth) / 16
                && mbY0 >= (scaledTop + scalMbH - 1) / scalMbH && mbY1 < (scaledTop + frameHeight) / scalMbH;
    }

    /**
     * Returns the luma location of the upper-left sample of a macroblock in its picture (ITU-T
     * H.264 6.4.1), for a macroblock taken as a field or a frame macroblock.
     *
     * @param mbAddr the macroblock address
     * @param fieldMb whether the macroblock is a field macroblock of a frame with
     * {@code MbaffFrameFlag} 1
     * @return {@code (x << 16) | y}
     */
    int macroblockLocation(int mbAddr, boolean fieldMb) {
        if (!mbaff) {
            return ((mbAddr % widthMbs) * 16) << 16 | (mbAddr / widthMbs) * 16;
        }
        var pair = mbAddr >> 1;
        var x = (pair % widthMbs) * 16;
        var y = (pair / widthMbs) * 32 + (fieldMb ? (mbAddr & 1) : (mbAddr & 1) * 16);
        return x << 16 | y;
    }

    /**
     * Derives the reference layer macroblock and the location inside it that correspond to a
     * luma location of a macroblock (ITU-T H.264 F.6.1, F.6.1.1 and F.6.1.2).
     *
     * @param mbAddr the macroblock address
     * @param xP the horizontal location inside the macroblock
     * @param yP the vertical location inside the macroblock
     * @param fieldMb the {@code fieldMbFlag} of the macroblock
     * @param ref the arrays of the reference layer representation, whose {@code fieldMbFlag} and
     * {@code mbType} the macroblock-adaptive conversions read
     * @return {@code (mbAddrRefLayer << 8) | (xB << 4) | yB}
     */
    int referenceLuma(int mbAddr, int xP, int yP, boolean fieldMb, SvcLayer ref) {
        var location = macroblockLocation(mbAddr, fieldMb && mbaff);
        var fieldFlag = fieldMb ? 1 : 0;
        var fieldPicFlag = fieldPic ? 1 : 0;
        var xC = (location >>> 16) + xP;
        var yC = (location & 0xFFFF) + yP * (1 + fieldFlag - fieldPicFlag);
        var offsetY = scaledTop / (1 + fieldPicFlag);
        var xRef = (int) (((long) (xC - scaledLeft) * scaleX + (1 << (shiftX - 1))) >> shiftX);
        var yRef = (int) (((long) (yC - offsetY) * scaleY + (1 << (shiftY - 1))) >> shiftY);
        xRef = Math.clamp(xRef, 0, refWidth - 1);
        yRef = Math.clamp(yRef, 0, refHeight - 1);
        if (!mbaff && !refMbaff) {
            var mb = (yRef >> 4) * refWidthMbs + (xRef >> 4);
            return (mb << 8) | ((xRef & 15) << 4) | (yRef & 15);
        }
        int virtual;
        if (refMbaff) {
            virtual = 2 * ((yRef / 32) * refWidthMbs + (xRef / 16)) + (yRef % 32) / 16;
        } else {
            virtual = (yRef / 16) * refWidthMbs + (xRef / 16);
        }
        var refField = ref.fieldMb[virtual];
        int mbRef;
        int yB;
        if (!fieldMb && refField) {
            var top = virtual - (virtual % 2);
            mbRef = SvcMb.intra(ref.mbType[top]) ? top + 1 : top;
            yB = 8 * ((yRef / 16) % 2) + 4 * ((yRef % 16) / 8);
        } else if (fieldMb && !refField) {
            mbRef = virtual;
            yB = yRef % 16;
        } else {
            mbRef = ((virtual >> fieldFlag) << fieldFlag) + (mbAddr % 2) * fieldFlag;
            yB = (yRef % (16 << fieldFlag)) >> fieldFlag;
        }
        return (mbRef << 8) | ((xRef & 15) << 4) | yB;
    }

    /**
     * Derives the horizontal reference layer sample location of a sample column in units of
     * 1/16 sample (ITU-T H.264 F.6.3).
     *
     * @param chroma 0 for luma, 1 for chroma
     * @param xC the column in the current layer picture, {@code xC} of equation F-58
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
     * @param yC the row {@code yC} of equations F-59 and F-60
     * @param bottom the {@code botFieldFlag}
     * @return {@code yRef16}
     */
    int refY16(int chroma, int yC, int bottom) {
        var shift = rsShiftY[chroma];
        return (int) ((((long) (yC - rsOffsetY[chroma]) * rsScaleY[chroma] + rsAddY[chroma * 2 + bottom])
                >> (shift - 4)) - rsDeltaY[chroma * 2 + bottom]);
    }

    /**
     * Returns the row {@code yC} of a sample of a macroblock for the sample location derivation of
     * resampling (ITU-T H.264 equations F-59 and F-60).
     *
     * @param chroma 0 for luma, 1 for chroma
     * @param yM the vertical luma location of the upper-left sample of the macroblock (ITU-T
     * H.264 6.4.1)
     * @param yP the row inside the macroblock, in samples of the component
     * @param fieldMb the {@code fieldMbFlag} of the macroblock
     * @return {@code yC}
     */
    int sampleRow(int chroma, int yM, int yP, boolean fieldMb) {
        var fieldFlag = fieldMb ? 1 : 0;
        var yC = yP + (yM >> (chroma + fieldFlag - (fieldPic ? 1 : 0)));
        if (interlacedResampling()) {
            yC >>= 1 - fieldFlag;
        }
        return yC;
    }
}
