package com.github.auties00.smalt.video.h264.internal;

import java.util.Arrays;

/**
 * Derives the motion of the macroblocks of the scalable decoding process: the inter-layer
 * predictors of macroblock types, reference indices and motion vectors (ITU-T H.264 F.8.6.1) and
 * the motion vectors and reference indices of each macroblock (F.8.4.1), including the spatial
 * and temporal direct predictions of the base layer (8.4.1.2.2 and 8.4.1.2.3) and the direct
 * prediction of the enhancement layers (F.8.4.1.2).
 *
 * <p>Frame and field macroblocks of frames, of frames with {@code MbaffFrameFlag} 1 and of fields
 * are handled; the motion of a field macroblock is in field units.
 */
final class SvcMotion {
    /**
     * The status of a neighboring partition that is not available.
     */
    private static final int UNAVAILABLE = Integer.MIN_VALUE;

    /**
     * Whether every 4x4 block of the current macroblock is predicted from intra-coded reference
     * layer macroblocks: {@code intraILPredFlag}.
     */
    boolean intraIL;

    /**
     * The predicted macroblock type {@code mbTypeILPred}, valid when {@link #intraIL} is 0 or
     * the macroblock type is I_BL.
     */
    int ilType;

    /**
     * The predicted sub-macroblock types {@code subMbTypeILPred}.
     */
    final int[] ilSub = new int[4];

    /**
     * The inter-layer reference index predictors {@code refIdxILPredLX}, indexed
     * {@code [list][quadrant]}.
     */
    final int[][] ilRef = {new int[4], new int[4]};

    /**
     * The inter-layer motion vector predictors {@code mvILPredLX}, indexed
     * {@code [list][raster * 2 + component]}.
     */
    final int[][] ilMv = {new int[32], new int[32]};

    /**
     * The reference layer partition of each 4x4 block of the current macroblock in raster order,
     * {@code refMbAddr * 16 + block}, or -1 for a block over an intra-coded or unavailable
     * reference layer macroblock ({@code refLayerPartIdc} of ITU-T H.264 F.8.6.1.1).
     */
    private final int[] partIdc = new int[16];

    /**
     * The scaled reference index of each 4x4 block for each list, before the merging of the
     * reference indices within each 8x8 block (ITU-T H.264 F.8.6.1, {@code tempRefIdxPredLX}).
     */
    private final int[][] tempRef = {new int[16], new int[16]};

    /**
     * The reference indices of the neighbors A, B and C of the partition being predicted, with slot
     * 3 holding the last neighbor read.
     */
    private final int[] neighborRef = new int[4];

    /**
     * The motion vectors of the neighbors in {@link #neighborRef}, two components each.
     */
    private final int[] neighborMv = new int[8];

    /**
     * The reference indices of a direct prediction for list 0 and list 1.
     */
    private final int[] directRefs = new int[2];

    /**
     * The motion vector predictors of a spatial direct prediction for list 0 and list 1, the
     * horizontal component in the upper 16 bits.
     */
    private final int[] directPredictors = new int[2];

    /**
     * The arrays of the layer whose motion is derived.
     */
    private SvcLayer layer;

    /**
     * The address of the current macroblock.
     */
    private int addr;

    /**
     * The macroblock type code of the current macroblock.
     */
    private int type;

    /**
     * The sub-macroblock type codes of the four 8x8 partitions of the current macroblock.
     */
    private final int[] subTypes = new int[4];

    /**
     * The neighbor derivation of the current macroblock.
     */
    private SvcNeighbors neighbors;

    /**
     * Whether the current macroblock is a field macroblock of a frame with {@code MbaffFrameFlag}
     * 1, whose neighbors of the other kind have their motion scaled.
     */
    private boolean fieldInFrame;

    /**
     * Whether the current layer is a frame with {@code MbaffFrameFlag} 1.
     */
    private boolean mbaff;

    /**
     * The decoding order of the 4x4 block being predicted within the current macroblock: blocks of
     * the macroblock that come later are not available as neighbors.
     */
    private int currentOrder;

    /**
     * Constructs a motion derivation.
     */
    SvcMotion() {
    }

    /**
     * Clears the inter-layer predictors, for a macroblock without inter-layer motion prediction.
     */
    void clearInterLayer() {
        intraIL = false;
        ilType = SvcMb.NONE;
        Arrays.fill(ilSub, 0);
        for (var list = 0; list < 2; list++) {
            Arrays.fill(ilRef[list], -1);
            Arrays.fill(ilMv[list], 0);
        }
    }

    /**
     * Derives the inter-layer predictors of a macroblock (ITU-T H.264 F.8.6.1).
     *
     * @param rep the current layer representation
     * @param ref the arrays of the reference layer representation
     * @param mbAddr the current macroblock address
     * @param sliceType the {@code slice_type % 5} of the current slice
     * @param tcoeffPrediction the {@code tcoeff_level_prediction_flag} of the current slice
     * @param direct8x8Inference the {@code direct_8x8_inference_flag} of the current layer
     * @param lists the reference picture lists of the current slice when {@code CroppingChangeFlag}
     * is 1, indexed {@code [list][refIdx]}, or {@code null}
     * @param fieldMb the {@code fieldMbFlag} of the current macroblock
     */
    void predictInterLayer(SvcRepresentation rep, SvcLayer ref, int mbAddr, int sliceType, boolean tcoeffPrediction,
                           boolean direct8x8Inference, SvcPicture[][] lists, boolean fieldMb) {
        var restricted = rep.restrictedSpatialResolutionChange;
        var allIntra = true;
        for (var y = 0; y < 4; y++) {
            for (var x = 0; x < 4; x++) {
                var location = rep.referenceLuma(mbAddr, 4 * x + 1, 4 * y + 1, fieldMb, ref);
                var refMb = location >> 8;
                var xB = (location >> 4) & 15;
                var yB = location & 15;
                if (SvcMb.intra(ref.mbType[refMb])) {
                    partIdc[y * 4 + x] = -1;
                } else {
                    partIdc[y * 4 + x] = refMb * 16 + (yB >> 2) * 4 + (xB >> 2);
                    allIntra = false;
                }
            }
        }
        intraIL = allIntra;
        if (allIntra) {
            for (var list = 0; list < 2; list++) {
                Arrays.fill(ilRef[list], -1);
                Arrays.fill(ilMv[list], 0);
            }
            Arrays.fill(ilSub, 0);
            ilType = tcoeffPrediction ? ref.mbType[mbAddr] : SvcMb.IBL;
            return;
        }
        if (!restricted) {
            fillIntraBlocks();
        }
        var b = sliceType == H264SliceHeader.B;
        var fieldFlag = fieldMb ? 1 : 0;
        var fieldPicFlag = rep.fieldPic ? 1 : 0;
        var refFieldPicFlag = rep.refFieldPic ? 1 : 0;
        var location = rep.macroblockLocation(mbAddr, fieldMb && rep.mbaff);
        for (var list = 0; list < 2; list++) {
            var refs = list == 0 ? ref.refIdx0 : ref.refIdx1;
            var mvs = list == 0 ? ref.mv0 : ref.mv1;
            var temp = tempRef[list];
            var out = ilMv[list];
            for (var i = 0; i < 16; i++) {
                var idc = partIdc[i];
                var refMb = idc >> 4;
                var block = idc & 15;
                var quadrant = (block >> 3) * 2 + ((block & 3) >> 1);
                var refIdx = idc < 0 ? -1 : refs[refMb * 4 + quadrant];
                if (refIdx < 0) {
                    temp[i] = -1;
                    out[2 * i] = 0;
                    out[2 * i + 1] = 0;
                    continue;
                }
                var refFieldFlag = ref.fieldMb[refMb] ? 1 : 0;
                temp[i] = refIdx * (1 + fieldFlag - fieldPicFlag) / (1 + refFieldFlag - refFieldPicFlag);
                int mx = mvs[refMb * 32 + block * 2];
                int my = mvs[refMb * 32 + block * 2 + 1] * (1 + refFieldFlag);
                var dOX = 0;
                var dOY = 0;
                var dSW = 0;
                var dSH = 0;
                SvcPicture picture = null;
                if (rep.croppingChange && lists != null && temp[i] < lists[list].length) {
                    picture = lists[list][temp[i]];
                }
                if (picture != null) {
                    dOX = rep.scaledLeft - picture.scaledLeft;
                    dOY = rep.scaledTop - picture.scaledTop;
                    dSW = rep.scaledRight - picture.scaledRight + dOX;
                    dSH = rep.scaledBottom - picture.scaledBottom + dOY;
                }
                var scaledW = rep.scaledWidth;
                var scaledH = rep.scaledHeight * (1 + fieldPicFlag);
                var refW = rep.refWidth;
                var refH = rep.refHeight * (1 + refFieldPicFlag);
                var scaleX = (int) ((((long) (scaledW + dSW) << 16) + (refW >> 1)) / refW);
                var scaleY = (int) ((((long) (scaledH + dSH) << 16) + (refH >> 1)) / refH);
                mx = (int) (((long) mx * scaleX + 32768) >> 16);
                my = (int) (((long) my * scaleY + 32768) >> 16);
                if (rep.croppingChange) {
                    var xFrm = (location >>> 16) + 4 * (i & 3) + 1;
                    var yFrm = ((location & 0xFFFF) + (4 * (i >> 2) + 1) * (1 + fieldFlag - fieldPicFlag))
                            * (1 + fieldPicFlag);
                    var sx = (int) ((((long) (4 * dSW) << 16) + (scaledW >> 1)) / scaledW);
                    var sy = (int) ((((long) (4 * dSH) << 16) + (scaledH >> 1)) / scaledH);
                    mx += (int) (((long) (xFrm - rep.scaledLeft) * sx + 32768) >> 16) - 4 * dOX;
                    my += (int) (((long) (yFrm - rep.scaledTop) * sy + 32768) >> 16) - 4 * dOY;
                }
                out[2 * i] = mx;
                out[2 * i + 1] = my / (1 + fieldFlag);
            }
            var refOut = ilRef[list];
            for (var q = 0; q < 4; q++) {
                var x0 = (q & 1) * 2;
                var y0 = (q >> 1) * 2;
                var value = temp[y0 * 4 + x0];
                if (!restricted) {
                    for (var s = 0; s < 4; s++) {
                        value = minPositive(value, temp[(y0 + (s >> 1)) * 4 + x0 + (s & 1)]);
                    }
                    for (var s = 0; s < 4; s++) {
                        var xs = s & 1;
                        var ys = s >> 1;
                        var at = (y0 + ys) * 4 + x0 + xs;
                        if (temp[at] == value) {
                            continue;
                        }
                        var horizontal = (y0 + ys) * 4 + x0 + 1 - xs;
                        var vertical = (y0 + 1 - ys) * 4 + x0 + xs;
                        var diagonal = (y0 + 1 - ys) * 4 + x0 + 1 - xs;
                        var source = temp[horizontal] == value ? horizontal : temp[vertical] == value ? vertical : diagonal;
                        out[2 * at] = out[2 * source];
                        out[2 * at + 1] = out[2 * source + 1];
                    }
                }
                refOut[q] = value;
            }
            if (!restricted && b && direct8x8Inference) {
                for (var q = 0; q < 4; q++) {
                    var x0 = (q & 1) * 2;
                    var y0 = (q >> 1) * 2;
                    var corner = (3 * (q >> 1)) * 4 + 3 * (q & 1);
                    var cx = out[2 * corner];
                    var cy = out[2 * corner + 1];
                    for (var s = 0; s < 4; s++) {
                        var at = (y0 + (s >> 1)) * 4 + x0 + (s & 1);
                        out[2 * at] = cx;
                        out[2 * at + 1] = cy;
                    }
                }
            }
        }
        if (!restricted) {
            mergeSubPartitions(b);
        }
        deriveTypes(b);
    }

    /**
     * Substitutes the partition identifications of the 4x4 blocks predicted from intra-coded
     * reference layer macroblocks with those of their neighbors (ITU-T H.264 F.8.6.1.1, equations
     * F-212 to F-217).
     */
    private void fillIntraBlocks() {
        for (var q = 0; q < 4; q++) {
            var x0 = (q & 1) * 2;
            var y0 = (q >> 1) * 2;
            var processed = 0;
            for (var s = 0; s < 4; s++) {
                var xs = s & 1;
                var ys = s >> 1;
                var at = (y0 + ys) * 4 + x0 + xs;
                if (partIdc[at] != -1) {
                    continue;
                }
                processed |= 1 << s;
                var horizontal = (y0 + ys) * 4 + x0 + 1 - xs;
                var vertical = (y0 + 1 - ys) * 4 + x0 + xs;
                var diagonal = (y0 + 1 - ys) * 4 + x0 + 1 - xs;
                if ((processed & (1 << (ys * 2 + 1 - xs))) == 0 && partIdc[horizontal] != -1) {
                    partIdc[at] = partIdc[horizontal];
                } else if ((processed & (1 << ((1 - ys) * 2 + xs))) == 0 && partIdc[vertical] != -1) {
                    partIdc[at] = partIdc[vertical];
                } else if ((processed & (1 << ((1 - ys) * 2 + 1 - xs))) == 0 && partIdc[diagonal] != -1) {
                    partIdc[at] = partIdc[diagonal];
                }
            }
        }
        var processed = 0;
        for (var q = 0; q < 4; q++) {
            var xp = q & 1;
            var yp = q >> 1;
            if (partIdc[(2 * yp) * 4 + 2 * xp] != -1) {
                continue;
            }
            processed |= 1 << q;
            if ((processed & (1 << (yp * 2 + 1 - xp))) == 0 && partIdc[(2 * yp) * 4 + 2 - xp] != -1) {
                for (var s = 0; s < 4; s++) {
                    var ys = s >> 1;
                    var xs = s & 1;
                    partIdc[(2 * yp + ys) * 4 + 2 * xp + xs] = partIdc[(2 * yp + ys) * 4 + 2 - xp];
                }
            } else if ((processed & (1 << ((1 - yp) * 2 + xp))) == 0 && partIdc[(2 - yp) * 4 + 2 * xp] != -1) {
                for (var s = 0; s < 4; s++) {
                    var ys = s >> 1;
                    var xs = s & 1;
                    partIdc[(2 * yp + ys) * 4 + 2 * xp + xs] = partIdc[(2 - yp) * 4 + 2 * xp + xs];
                }
            } else if ((processed & (1 << ((1 - yp) * 2 + 1 - xp))) == 0 && partIdc[(2 - yp) * 4 + 2 - xp] != -1) {
                var source = partIdc[(2 - yp) * 4 + 2 - xp];
                for (var s = 0; s < 4; s++) {
                    var ys = s >> 1;
                    var xs = s & 1;
                    partIdc[(2 * yp + ys) * 4 + 2 * xp + xs] = source;
                }
            }
        }
    }

    /**
     * Merges the inter-layer motion vector predictors of the 4x4 blocks of each 8x8 block into
     * larger sub-partitions when they differ by at most one quarter sample (ITU-T H.264 F.8.6.1.2,
     * equations F-253 to F-263).
     *
     * @param b whether the slice is an EB slice
     */
    private void mergeSubPartitions(boolean b) {
        var lists = b ? 2 : 1;
        for (var q = 0; q < 4; q++) {
            var o = ((q >> 1) * 2) * 4 + (q & 1) * 2;
            var all8x8 = true;
            var all8x4 = true;
            var all4x8 = true;
            for (var list = 0; list < lists; list++) {
                var mv = ilMv[list];
                all8x8 &= diff(mv, o, o + 1) <= 1 && diff(mv, o, o + 4) <= 1 && diff(mv, o, o + 5) <= 1;
                all8x4 &= diff(mv, o, o + 1) <= 1 && diff(mv, o + 4, o + 5) <= 1;
                all4x8 &= diff(mv, o, o + 4) <= 1 && diff(mv, o + 1, o + 5) <= 1;
            }
            if (!all8x8 && !all8x4 && !all4x8) {
                continue;
            }
            for (var list = 0; list < lists; list++) {
                var mv = ilMv[list];
                for (var c = 0; c < 2; c++) {
                    var a = mv[2 * o + c];
                    var r = mv[2 * (o + 1) + c];
                    var d = mv[2 * (o + 4) + c];
                    var e = mv[2 * (o + 5) + c];
                    if (all8x8) {
                        var value = (a + r + d + e + 2) >> 2;
                        mv[2 * o + c] = value;
                        mv[2 * (o + 1) + c] = value;
                        mv[2 * (o + 4) + c] = value;
                        mv[2 * (o + 5) + c] = value;
                    } else if (all8x4) {
                        var top = (a + r + 1) >> 1;
                        var bottom = (d + e + 1) >> 1;
                        mv[2 * o + c] = top;
                        mv[2 * (o + 1) + c] = top;
                        mv[2 * (o + 4) + c] = bottom;
                        mv[2 * (o + 5) + c] = bottom;
                    } else {
                        var left = (a + d + 1) >> 1;
                        var right = (r + e + 1) >> 1;
                        mv[2 * o + c] = left;
                        mv[2 * (o + 4) + c] = left;
                        mv[2 * (o + 1) + c] = right;
                        mv[2 * (o + 5) + c] = right;
                    }
                }
            }
        }
    }

    /**
     * Returns the {@code mvDiff} of two 4x4 block predictors.
     *
     * @param mv the predictors
     * @param a the first block
     * @param b the second block
     * @return the sum of the absolute component differences
     */
    private static int diff(int[] mv, int a, int b) {
        return Math.abs(mv[2 * a] - mv[2 * b]) + Math.abs(mv[2 * a + 1] - mv[2 * b + 1]);
    }

    /**
     * Derives {@link #ilType} and {@link #ilSub} from the predictors (ITU-T H.264 F.8.6.1.3).
     *
     * @param b whether the slice is an EB slice
     */
    private void deriveTypes(boolean b) {
        var lists = b ? 2 : 1;
        var size16x16 = true;
        var size16x8 = true;
        var size8x16 = true;
        for (var list = 0; list < lists; list++) {
            var r = ilRef[list];
            var mv = ilMv[list];
            size16x16 &= r[0] == r[1] && r[0] == r[2] && r[0] == r[3] && uniform(mv, 0, 4, 0, 4);
            size16x8 &= r[0] == r[1] && r[2] == r[3] && uniform(mv, 0, 4, 0, 2) && uniform(mv, 0, 4, 2, 4);
            size8x16 &= r[0] == r[2] && r[1] == r[3] && uniform(mv, 0, 2, 0, 4) && uniform(mv, 2, 4, 0, 4);
        }
        var shape = size16x16 ? SvcMb.SHAPE_16X16 : size16x8 ? SvcMb.SHAPE_16X8 : size8x16 ? SvcMb.SHAPE_8X16
                : SvcMb.SHAPE_8X8;
        Arrays.fill(ilSub, 0);
        if (!b) {
            ilType = shape == SvcMb.SHAPE_8X8 ? SvcMb.P_8X8 : SvcMb.P_BASE + shape;
        } else {
            var modeA = mode(0);
            var modeB = mode(3);
            ilType = SvcMb.bType(shape, modeA, modeB);
        }
        if (shape != SvcMb.SHAPE_8X8) {
            return;
        }
        for (var q = 0; q < 4; q++) {
            var o = ((q >> 1) * 2) * 4 + (q & 1) * 2;
            var sub8x8 = true;
            var sub8x4 = true;
            var sub4x8 = true;
            for (var list = 0; list < lists; list++) {
                var mv = ilMv[list];
                sub8x8 &= same(mv, o, o + 1) && same(mv, o, o + 4) && same(mv, o, o + 5);
                sub8x4 &= same(mv, o, o + 1) && same(mv, o + 4, o + 5);
                sub4x8 &= same(mv, o, o + 4) && same(mv, o + 1, o + 5);
            }
            var subShape = sub8x8 ? SvcMb.SHAPE_16X16 : sub8x4 ? SvcMb.SHAPE_16X8 : sub4x8 ? SvcMb.SHAPE_8X16
                    : SvcMb.SHAPE_8X8;
            ilSub[q] = b ? SvcMb.bSub(subShape, mode(q)) : subShape;
        }
    }

    /**
     * Returns the prediction mode of a quadrant of the predictors: bit 0 list 0, bit 1 list 1.
     *
     * @param quadrant the 8x8 quadrant index, from 0 to 3
     * @return the mode mask
     */
    private int mode(int quadrant) {
        return (ilRef[1][quadrant] >= 0 ? 2 : 0) + (ilRef[0][quadrant] >= 0 ? 1 : 0);
    }

    /**
     * Returns whether two 4x4 block predictors are equal.
     *
     * @param mv the predictors
     * @param a the first block
     * @param b the second block
     * @return {@code true} when both components match
     */
    private static boolean same(int[] mv, int a, int b) {
        return mv[2 * a] == mv[2 * b] && mv[2 * a + 1] == mv[2 * b + 1];
    }

    /**
     * Returns whether the predictors of a rectangle of 4x4 blocks are all equal.
     *
     * @param mv the predictors
     * @param x0 the first column
     * @param x1 the end column
     * @param y0 the first row
     * @param y1 the end row
     * @return {@code true} when the rectangle is uniform
     */
    private static boolean uniform(int[] mv, int x0, int x1, int y0, int y1) {
        var first = y0 * 4 + x0;
        for (var y = y0; y < y1; y++) {
            for (var x = x0; x < x1; x++) {
                if (!same(mv, first, y * 4 + x)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Returns {@code MinPositive( x, y )} (ITU-T H.264 equation F-247).
     *
     * @param x the first value
     * @param y the second value
     * @return the smaller non-negative value, or the larger value when either is negative
     */
    static int minPositive(int x, int y) {
        return x >= 0 && y >= 0 ? Math.min(x, y) : Math.max(x, y);
    }

    /**
     * Derives the reference indices and motion vectors of an inter macroblock and stores them in
     * the layer arrays (ITU-T H.264 F.8.4.1).
     *
     * @param layer the current layer arrays, whose {@code mbType} and {@code subMbType} of the
     * macroblock are set
     * @param mbAddr the macroblock address
     * @param syntax the parser holding the macroblock's syntax elements
     * @param baseLayer whether the layer representation has DQId 0, whose direct macroblocks use
     * the direct predictions of clause 8
     * @param colocated the co-located motion of a base layer B slice, prepared for the slice, or
     * {@code null}
     * @param spatial the {@code direct_spatial_mv_pred_flag} of a base layer B slice
     * @param slice the slice of the macroblock, whose reference picture lists the temporal direct
     * prediction reads
     * @param direct8x8Inference the {@code direct_8x8_inference_flag}
     */
    void derive(SvcLayer layer, int mbAddr, SvcSliceParser syntax, boolean baseLayer, SvcColocated colocated,
                boolean spatial, SvcSlice slice, boolean direct8x8Inference) {
        this.layer = layer;
        this.addr = mbAddr;
        this.type = layer.mbType[mbAddr];
        for (var q = 0; q < 4; q++) {
            subTypes[q] = layer.subMbType[mbAddr * 4 + q];
        }
        this.neighbors = syntax.neighbors;
        this.mbaff = layer.mbaff;
        this.fieldInFrame = layer.mbaff && layer.fieldMb[mbAddr];
        var refBase = mbAddr * 4;
        var mvBase = mbAddr * 32;
        if (!SvcMb.inter(type)) {
            Arrays.fill(layer.refIdx0, refBase, refBase + 4, (byte) -1);
            Arrays.fill(layer.refIdx1, refBase, refBase + 4, (byte) -1);
            Arrays.fill(layer.mv0, mvBase, mvBase + 32, (short) 0);
            Arrays.fill(layer.mv1, mvBase, mvBase + 32, (short) 0);
            return;
        }
        if (type == SvcMb.P_SKIP) {
            currentOrder = 0;
            deriveSkip(layer);
            return;
        }
        if (SvcMb.direct(type)) {
            if (baseLayer && spatial) {
                spatialDirect(-1, colocated, direct8x8Inference);
            } else if (baseLayer) {
                temporalDirect(-1, colocated, slice, direct8x8Inference);
            } else {
                currentOrder = 0;
                enhancementDirect(0, 0, 0, 4);
            }
            return;
        }
        var shape = SvcMb.shape(type);
        var parts = SvcMb.parts(shape);
        for (var p = 0; p < parts; p++) {
            if (SvcMb.eightByEight(type)) {
                var sub = subTypes[p];
                var x0 = (p & 1) * 2;
                var y0 = (p >> 1) * 2;
                if (SvcMb.subDirect(sub)) {
                    if (baseLayer && spatial) {
                        spatialDirect(p, colocated, direct8x8Inference);
                    } else if (baseLayer) {
                        temporalDirect(p, colocated, slice, direct8x8Inference);
                    } else {
                        currentOrder = 4 * p;
                        enhancementDirect(p, x0, y0, 2);
                    }
                    continue;
                }
                var subShape = SvcMb.subShape(sub);
                var count = SvcMb.parts(subShape);
                var w = SvcSliceParser.partW(subShape) / 2;
                var h = SvcSliceParser.partH(subShape) / 2;
                for (var s = 0; s < count; s++) {
                    var x = x0 + (w == 2 ? 0 : s & 1);
                    var y = y0 + (h == 2 ? 0 : (w == 2 ? s : s >> 1));
                    currentOrder = 4 * p + s;
                    predictPartition(syntax, p, s, x, y, w, h, SvcMb.subMode(sub), false);
                }
            } else {
                currentOrder = 4 * p;
                predictPartition(syntax, p, 0, SvcSliceParser.partX(shape, p), SvcSliceParser.partY(shape, p),
                        SvcSliceParser.partW(shape), SvcSliceParser.partH(shape), SvcMb.mode(type, p),
                        shape == SvcMb.SHAPE_16X8 || shape == SvcMb.SHAPE_8X16);
            }
        }
    }

    /**
     * Derives and stores the motion of one partition or sub-partition with motion vector
     * differences or inter-layer predictors.
     *
     * @param syntax the parser holding the syntax elements
     * @param part the partition index
     * @param sub the sub-partition index
     * @param x the partition column in 4x4 blocks
     * @param y the partition row in 4x4 blocks
     * @param w the partition width in 4x4 blocks
     * @param h the partition height in 4x4 blocks
     * @param mode the prediction mode mask of the partition
     * @param directional whether the partition belongs to a 16x8 or 8x16 macroblock partitioning
     */
    private void predictPartition(SvcSliceParser syntax, int part, int sub, int x, int y, int w, int h, int mode,
                                  boolean directional) {
        for (var list = 0; list < 2; list++) {
            var refs = list == 0 ? layer.refIdx0 : layer.refIdx1;
            var mvs = list == 0 ? layer.mv0 : layer.mv1;
            int refIdx;
            int mx;
            int my;
            if ((mode & (1 << list)) == 0) {
                refIdx = -1;
                mx = 0;
                my = 0;
            } else if (syntax.baseMode || (list == 0 ? syntax.motionPred0 : syntax.motionPred1)[part]) {
                refIdx = ilRef[list][(y >> 1) * 2 + (x >> 1)];
                var at = (y * 4 + x) * 2;
                mx = ilMv[list][at];
                my = ilMv[list][at + 1];
                if (!syntax.baseMode) {
                    var mvd = list == 0 ? syntax.mvd0 : syntax.mvd1;
                    mx += mvd[(part * 4 + sub) * 2];
                    my += mvd[(part * 4 + sub) * 2 + 1];
                }
            } else {
                refIdx = type == SvcMb.P_8X8_REF0 ? 0 : (list == 0 ? syntax.refIdx0 : syntax.refIdx1)[part];
                var predicted = predictMv(list, x, y, w, refIdx, directional ? (w == 4 ? 1 : 2) : 0, x == 0 && y == 0);
                var mvd = list == 0 ? syntax.mvd0 : syntax.mvd1;
                mx = (short) (predicted >> 16) + mvd[(part * 4 + sub) * 2];
                my = (short) predicted + mvd[(part * 4 + sub) * 2 + 1];
            }
            if (sub == 0) {
                fillRef(refs, x, y, w, h, refIdx);
            }
            fillMv(mvs, x, y, w, h, refIdx < 0 ? 0 : mx, refIdx < 0 ? 0 : my);
        }
    }

    /**
     * Stores a reference index into the quadrants a partition covers.
     *
     * @param refs the reference index array
     * @param x the partition column in 4x4 blocks
     * @param y the partition row in 4x4 blocks
     * @param w the partition width in 4x4 blocks
     * @param h the partition height in 4x4 blocks
     * @param value the reference index
     */
    private void fillRef(byte[] refs, int x, int y, int w, int h, int value) {
        var base = addr * 4;
        for (var qy = y >> 1; qy <= (y + h - 1) >> 1; qy++) {
            for (var qx = x >> 1; qx <= (x + w - 1) >> 1; qx++) {
                refs[base + qy * 2 + qx] = (byte) value;
            }
        }
    }

    /**
     * Stores a motion vector into the 4x4 blocks of a partition.
     *
     * @param mvs the motion vector array
     * @param x the partition column in 4x4 blocks
     * @param y the partition row in 4x4 blocks
     * @param w the partition width in 4x4 blocks
     * @param h the partition height in 4x4 blocks
     * @param mx the horizontal component
     * @param my the vertical component
     */
    private void fillMv(short[] mvs, int x, int y, int w, int h, int mx, int my) {
        var base = addr * 32;
        for (var j = y; j < y + h; j++) {
            for (var i = x; i < x + w; i++) {
                mvs[base + (j * 4 + i) * 2] = (short) mx;
                mvs[base + (j * 4 + i) * 2 + 1] = (short) my;
            }
        }
    }

    /**
     * Derives the motion of a P_Skip macroblock (ITU-T H.264 8.4.1.1).
     *
     * @param layer the layer arrays
     */
    private void deriveSkip(SvcLayer layer) {
        var zero = neighbor(0, -1, 0) == UNAVAILABLE
                || (neighborRef[3] == 0 && neighborMv[6] == 0 && neighborMv[7] == 0);
        zero |= neighbor(0, 0, -1) == UNAVAILABLE
                || (neighborRef[3] == 0 && neighborMv[6] == 0 && neighborMv[7] == 0);
        int mx = 0;
        int my = 0;
        if (!zero) {
            var predicted = predictMv(0, 0, 0, 4, 0, 0, true);
            mx = (short) (predicted >> 16);
            my = (short) predicted;
        }
        fillRef(layer.refIdx0, 0, 0, 4, 4, 0);
        fillRef(layer.refIdx1, 0, 0, 4, 4, -1);
        fillMv(layer.mv0, 0, 0, 4, 4, mx, my);
        fillMv(layer.mv1, 0, 0, 4, 4, 0, 0);
    }

    /**
     * Derives the motion of a direct macroblock or 8x8 partition of an enhancement layer (ITU-T
     * H.264 F.8.4.1.2).
     *
     * @param part the partition index
     * @param x the partition column in 4x4 blocks
     * @param y the partition row in 4x4 blocks
     * @param size the partition size in 4x4 blocks: 4 for the macroblock, 2 for an 8x8 partition
     */
    private void enhancementDirect(int part, int x, int y, int size) {
        var refs = directRefs;
        for (var list = 0; list < 2; list++) {
            neighbors(list, x, y, size);
            refs[list] = minPositive(neighborRef[0], minPositive(neighborRef[1], neighborRef[2]));
        }
        if (refs[0] < 0 && refs[1] < 0) {
            refs[0] = 0;
            refs[1] = 0;
        }
        for (var list = 0; list < 2; list++) {
            var mx = 0;
            var my = 0;
            if (refs[list] >= 0) {
                var predicted = predictMv(list, x, y, size, refs[list], 0, false);
                mx = (short) (predicted >> 16);
                my = (short) predicted;
            }
            fillRef(list == 0 ? layer.refIdx0 : layer.refIdx1, x, y, size, size, refs[list]);
            fillMv(list == 0 ? layer.mv0 : layer.mv1, x, y, size, size, mx, my);
        }
    }

    /**
     * Derives the motion of a direct macroblock, or of one direct 8x8 partition, of a base layer
     * B slice with the spatial direct prediction (ITU-T H.264 8.4.1.2.2).
     *
     * @param part the direct 8x8 partition, or -1 for the whole macroblock
     * @param colocated the co-located motion of the slice, or {@code null}
     * @param direct8x8Inference the {@code direct_8x8_inference_flag}
     */
    private void spatialDirect(int part, SvcColocated colocated, boolean direct8x8Inference) {
        currentOrder = 0;
        var refs = directRefs;
        var predicted = directPredictors;
        predicted[0] = 0;
        predicted[1] = 0;

        for (var list = 0; list < 2; list++) {
            neighbors(list, 0, 0, 4);
            refs[list] = minPositive(neighborRef[0], minPositive(neighborRef[1], neighborRef[2]));
        }
        var directZero = false;
        if (refs[0] < 0 && refs[1] < 0) {
            refs[0] = 0;
            refs[1] = 0;
            directZero = true;
        }
        for (var list = 0; list < 2; list++) {
            if (!directZero && refs[list] >= 0) {
                predicted[list] = predictMv(list, 0, 0, 4, refs[list], 0, true);
            }
        }
        var first = part < 0 ? 0 : part;
        var last = part < 0 ? 3 : part;
        var shortTerm = colocated != null && colocated.firstShortTerm(fieldInFrame, addr);
        for (var q = first; q <= last; q++) {
            var x0 = (q & 1) * 2;
            var y0 = (q >> 1) * 2;
            fillRef(layer.refIdx0, x0, y0, 2, 2, refs[0]);
            fillRef(layer.refIdx1, x0, y0, 2, 2, refs[1]);
            for (var s = 0; s < 4; s++) {
                var x = x0 + (s & 1);
                var y = y0 + (s >> 1);
                var colZero = false;
                if (shortTerm) {
                    var xCol = direct8x8Inference ? 12 * (q & 1) : 4 * x;
                    var yCol = direct8x8Inference ? 12 * (q >> 1) : 4 * y;
                    colocated.locate(addr, fieldInFrame, xCol, yCol);
                    colZero = colocated.refIdxCol == 0 && Math.abs(colocated.mvColX) <= 1
                            && Math.abs(colocated.mvColY) <= 1;
                }
                for (var list = 0; list < 2; list++) {
                    var mx = 0;
                    var my = 0;
                    if (!directZero && refs[list] >= 0 && !(refs[list] == 0 && colZero)) {
                        mx = (short) (predicted[list] >> 16);
                        my = (short) predicted[list];
                    }
                    fillMv(list == 0 ? layer.mv0 : layer.mv1, x, y, 1, 1, mx, my);
                }
            }
        }
    }

    /**
     * Derives the motion of a direct macroblock, or of one direct 8x8 partition, of a base layer
     * B slice with the temporal direct prediction (ITU-T H.264 8.4.1.2.3).
     *
     * @param part the direct 8x8 partition, or -1 for the whole macroblock
     * @param colocated the co-located motion of the slice, or {@code null}
     * @param slice the slice of the macroblock
     * @param direct8x8Inference the {@code direct_8x8_inference_flag}
     */
    private void temporalDirect(int part, SvcColocated colocated, SvcSlice slice, boolean direct8x8Inference) {
        var first = part < 0 ? 0 : part;
        var last = part < 0 ? 3 : part;
        for (var q = first; q <= last; q++) {
            var x0 = (q & 1) * 2;
            var y0 = (q >> 1) * 2;
            for (var s = 0; s < 4; s++) {
                var x = x0 + (s & 1);
                var y = y0 + (s >> 1);
                var refL0 = 0;
                var mv0x = 0;
                var mv0y = 0;
                var mv1x = 0;
                var mv1y = 0;
                if (colocated != null) {
                    var xCol = direct8x8Inference ? 12 * (q & 1) : 4 * x;
                    var yCol = direct8x8Inference ? 12 * (q >> 1) : 4 * y;
                    colocated.locate(addr, fieldInFrame, xCol, yCol);
                    refL0 = colocated.refIdxCol < 0 ? 0 : colocated.mapToList0(addr, fieldInFrame);
                    var colX = colocated.mvColX;
                    var colY = colocated.mvColY;
                    if (colocated.vertMvScale == SvcColocated.FRM_TO_FLD) {
                        colY /= 2;
                    } else if (colocated.vertMvScale == SvcColocated.FLD_TO_FRM) {
                        colY *= 2;
                    }
                    var poc0 = SvcColocated.referencePoc(slice, 0, refL0, addr, fieldInFrame);
                    var poc1 = SvcColocated.referencePoc(slice, 1, 0, addr, fieldInFrame);
                    if (SvcColocated.longTerm(slice, 0, refL0, addr, fieldInFrame) || poc1 - poc0 == 0) {
                        mv0x = colX;
                        mv0y = colY;
                    } else {
                        var tb = Math.clamp(colocated.currentPoc(addr, fieldInFrame) - poc0, -128, 127);
                        var td = Math.clamp(poc1 - poc0, -128, 127);
                        var tx = (16384 + Math.abs(td / 2)) / td;
                        var scale = Math.clamp((tb * tx + 32) >> 6, -1024, 1023);
                        mv0x = (scale * colX + 128) >> 8;
                        mv0y = (scale * colY + 128) >> 8;
                        mv1x = mv0x - colX;
                        mv1y = mv0y - colY;
                    }
                }
                if (s == 0) {
                    fillRef(layer.refIdx0, x0, y0, 2, 2, refL0);
                    fillRef(layer.refIdx1, x0, y0, 2, 2, 0);
                }
                fillMv(layer.mv0, x, y, 1, 1, mv0x, mv0y);
                fillMv(layer.mv1, x, y, 1, 1, mv1x, mv1y);
            }
        }
    }

    /**
     * Gathers the motion of the neighbors A, B and C (or D) of a partition into
     * {@link #neighborRef} and {@link #neighborMv}.
     *
     * @param list the reference picture list index, 0 or 1
     * @param x the partition column in 4x4 blocks
     * @param y the partition row in 4x4 blocks
     * @param width the {@code predPartWidth} in 4x4 blocks
     * @return a bit set of the unavailable neighbors: bit 0 A, bit 1 B, bit 2 C after the
     * substitution by D
     */
    private int neighbors(int list, int x, int y, int width) {
        var missing = 0;
        if (neighbor(list, 4 * x - 1, 4 * y) == UNAVAILABLE) {
            store(0, -1, 0, 0);
            missing |= 1;
        } else {
            store(0, neighborRef[3], neighborMv[6], neighborMv[7]);
        }
        if (neighbor(list, 4 * x, 4 * y - 1) == UNAVAILABLE) {
            store(1, -1, 0, 0);
            missing |= 2;
        } else {
            store(1, neighborRef[3], neighborMv[6], neighborMv[7]);
        }
        if (neighbor(list, 4 * (x + width), 4 * y - 1) == UNAVAILABLE) {
            if (neighbor(list, 4 * x - 1, 4 * y - 1) == UNAVAILABLE) {
                store(2, -1, 0, 0);
                missing |= 4;
            } else {
                store(2, neighborRef[3], neighborMv[6], neighborMv[7]);
            }
        } else {
            store(2, neighborRef[3], neighborMv[6], neighborMv[7]);
        }
        return missing;
    }

    /**
     * Stores one neighbor's motion.
     *
     * @param n the neighbor slot: 0 A, 1 B, 2 C
     * @param ref the reference index
     * @param mx the horizontal component
     * @param my the vertical component
     */
    private void store(int n, int ref, int mx, int my) {
        neighborRef[n] = ref;
        neighborMv[2 * n] = mx;
        neighborMv[2 * n + 1] = my;
    }

    /**
     * Predicts the motion vector of a partition (ITU-T H.264 8.4.1.3).
     *
     * @param list the reference picture list index, 0 or 1
     * @param x the partition column in 4x4 blocks
     * @param y the partition row in 4x4 blocks
     * @param width the {@code predPartWidth} in 4x4 blocks
     * @param refIdx the reference index of the partition
     * @param directional 1 for the partitions of a 16x8 partitioning, 2 for those of an 8x16 one,
     * 0 otherwise
     * @param first whether the partition is the first of the macroblock, for the directional
     * prediction
     * @return the predictor, horizontal component in the high 16 bits
     */
    private int predictMv(int list, int x, int y, int width, int refIdx, int directional, boolean first) {
        var missing = neighbors(list, x, y, width);
        int mx;
        int my;
        if (directional == 1 && first && neighborRef[1] == refIdx) {
            mx = neighborMv[2];
            my = neighborMv[3];
        } else if (directional == 1 && !first && neighborRef[0] == refIdx) {
            mx = neighborMv[0];
            my = neighborMv[1];
        } else if (directional == 2 && first && neighborRef[0] == refIdx) {
            mx = neighborMv[0];
            my = neighborMv[1];
        } else if (directional == 2 && !first && neighborRef[2] == refIdx) {
            mx = neighborMv[4];
            my = neighborMv[5];
        } else {
            if ((missing & 6) == 6 && (missing & 1) == 0) {
                store(1, neighborRef[0], neighborMv[0], neighborMv[1]);
                store(2, neighborRef[0], neighborMv[0], neighborMv[1]);
            }
            var matchA = neighborRef[0] == refIdx;
            var matchB = neighborRef[1] == refIdx;
            var matchC = neighborRef[2] == refIdx;
            var matches = (matchA ? 1 : 0) + (matchB ? 1 : 0) + (matchC ? 1 : 0);
            if (matches == 1) {
                var n = matchA ? 0 : matchB ? 1 : 2;
                mx = neighborMv[2 * n];
                my = neighborMv[2 * n + 1];
            } else {
                mx = median(neighborMv[0], neighborMv[2], neighborMv[4]);
                my = median(neighborMv[1], neighborMv[3], neighborMv[5]);
            }
        }
        return (mx << 16) | (my & 0xFFFF);
    }

    /**
     * Returns the median of three values.
     *
     * @param a the first value
     * @param b the second value
     * @param c the third value
     * @return the median
     */
    private static int median(int a, int b, int c) {
        return Math.max(Math.min(a, b), Math.min(Math.max(a, b), c));
    }

    /**
     * Reads the motion of the 4x4 block holding a luma location relative to the current macroblock into
     * slot 3 of {@link #neighborRef} and {@link #neighborMv} (ITU-T H.264 6.4.11.7 and 8.4.1.3.2),
     * with the vertical component and the reference index of a neighbor of the other kind of a
     * field macroblock pair scaled to the current macroblock.
     *
     * @param list the reference picture list index, 0 or 1
     * @param xN the horizontal luma location, from -1 to 16
     * @param yN the vertical luma location, from -1 to 15
     * @return {@link #UNAVAILABLE} when the block is not available, 0 otherwise
     */
    private int neighbor(int list, int xN, int yN) {
        var mb = neighbors.locate(xN, yN, 16, 16);
        if (mb < 0) {
            return UNAVAILABLE;
        }
        var bx = neighbors.xW >> 2;
        var by = neighbors.yW >> 2;
        if (mb == addr && order(bx, by) > currentOrder) {
            return UNAVAILABLE;
        }
        if (!SvcMb.inter(layer.mbType[mb])) {
            neighborRef[3] = -1;
            neighborMv[6] = 0;
            neighborMv[7] = 0;
            return 0;
        }
        var refs = list == 0 ? layer.refIdx0 : layer.refIdx1;
        int ref = refs[mb * 4 + (by >> 1) * 2 + (bx >> 1)];
        if (ref < 0) {
            neighborRef[3] = -1;
            neighborMv[6] = 0;
            neighborMv[7] = 0;
            return 0;
        }
        var mvs = list == 0 ? layer.mv0 : layer.mv1;
        int mx = mvs[mb * 32 + (by * 4 + bx) * 2];
        int my = mvs[mb * 32 + (by * 4 + bx) * 2 + 1];
        if (mbaff && mb != addr) {
            var neighborField = layer.fieldMb[mb];
            if (fieldInFrame && !neighborField) {
                my /= 2;
                ref *= 2;
            } else if (!fieldInFrame && neighborField) {
                my *= 2;
                ref /= 2;
            }
        }
        neighborRef[3] = ref;
        neighborMv[6] = mx;
        neighborMv[7] = my;
        return 0;
    }

    /**
     * Returns the decoding order {@code 4 * mbPartIdx + subMbPartIdx} of the partition of the
     * current macroblock that covers a 4x4 block (ITU-T H.264 6.4.13.4).
     *
     * @param x the block column
     * @param y the block row
     * @return the order
     */
    private int order(int x, int y) {
        var quadrant = (y >> 1) * 2 + (x >> 1);
        if (SvcMb.eightByEight(type)) {
            var sub = subTypes[quadrant];
            if (SvcMb.subDirect(sub)) {
                return 4 * quadrant + (y & 1) * 2 + (x & 1);
            }
            var s = switch (SvcMb.subShape(sub)) {
                case SvcMb.SHAPE_16X16 -> 0;
                case SvcMb.SHAPE_16X8 -> y & 1;
                case SvcMb.SHAPE_8X16 -> x & 1;
                default -> (y & 1) * 2 + (x & 1);
            };
            return 4 * quadrant + s;
        }
        if (SvcMb.direct(type)) {
            return 4 * quadrant + (y & 1) * 2 + (x & 1);
        }
        return switch (SvcMb.shape(type)) {
            case SvcMb.SHAPE_16X8 -> 4 * (y >> 1);
            case SvcMb.SHAPE_8X16 -> 4 * (x >> 1);
            default -> 0;
        };
    }
}
