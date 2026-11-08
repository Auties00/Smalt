/* Ports the advanced residual prediction and illumination compensation of HTM-16.3 (source/Lib/TLibCommon/
   TComPrediction.cpp, TComYuv.cpp, TComInterpolationFilter.cpp). Copyright (c) 2010-2016, ITU/ISO/IEC; BSD 3-Clause
   License. Modified for Smalt. */
package com.github.auties00.smalt.video.hevc.internal;

import com.github.auties00.smalt.MediaException;

/**
 * Computes the inter prediction samples of the prediction units of 3D-HEVC that use advanced
 * residual prediction or illumination compensation (ITU-T H.265 I.8.5.3.3): the bilinear sample
 * interpolation and the residual predicted from the reference view (I.8.5.3.3.3), and the
 * illumination compensated sample prediction with parameters derived from the neighboring samples
 * of the coding block and of the reference block (I.8.5.3.3.2), followed by the default or
 * explicit weighted sample prediction (8.5.3.3.4).
 *
 * <p>The processes follow the reference decoder where the text of Annex I differs from it: the
 * residual of a prediction unit predicted from an inter-view reference takes its temporal motion
 * from the other list or from the reference block, the motion vectors are clipped to the picture
 * neighborhood before the residual's vectors are summed, the illumination compensated samples are
 * clipped after the offset is added and two compensated lists are averaged as in the default
 * weighted prediction.
 *
 * <p>The samples are processed one by one in 32-bit arithmetic; instances are reused across
 * slices and are not thread-safe.
 */
final class Hevc3dPredictor {
    /**
     * The largest prediction block side.
     */
    private static final int MAX = HevcDsp.MAX_BLOCK;

    /**
     * {@code ( 1 << 15 + i / 2 ) / i}, the reciprocals of the illumination compensation weight
     * derivation (Table I.3).
     */
    private static final int[] DIV_COEFF = new int[64];

    static {
        for (var i = 1; i < 64; i++) {
            DIV_COEFF[i] = ((1 << 15) + i / 2) / i;
        }
    }

    /**
     * The reference footprint of a block, edges extended.
     */
    private final short[] footprint = new short[(MAX + 7) * (MAX + 7)];

    /**
     * The interpolation scratch.
     */
    private final int[] scratch = new int[HevcDsp.SCRATCH_SIZE];

    /**
     * The intermediate prediction samples of each list and component, indexed
     * {@code [list][cIdx]}.
     */
    private final int[][][] prediction = {
            {new int[MAX * MAX], new int[MAX * MAX], new int[MAX * MAX]},
            {new int[MAX * MAX], new int[MAX * MAX], new int[MAX * MAX]}
    };

    /**
     * The first block of a residual predictor.
     */
    private final int[] base0 = new int[MAX * MAX];

    /**
     * The second block of a residual predictor.
     */
    private final int[] base1 = new int[(MAX + 1) * MAX];

    /**
     * The intermediate rows of the bilinear interpolation.
     */
    private final int[] rows = new int[(MAX + 1) * MAX];

    /**
     * The slice segment header.
     */
    private HevcSliceHeader header;

    /**
     * The reference picture lists.
     */
    private HevcFrame[][] refList;

    /**
     * The variables of the 3D-HEVC tools of the slice.
     */
    private InterViewContext views;

    /**
     * The picture being decoded.
     */
    private HevcFrame target;

    /**
     * The motion vector {@link #blockMotion} derived for the residual of an inter-view predicted
     * list.
     */
    private int baseMv;

    /**
     * The picture order count of the temporal reference {@link #blockMotion} chose.
     */
    private int baseTargetPoc;

    /**
     * The reference picture of the slice {@link #blockMotion} chose for the residual,
     * {@code RefPicListX[ RpRefIdxLX ]} of the list of the motion it found.
     */
    private HevcFrame baseCurrent;

    /**
     * Constructs a predictor.
     */
    Hevc3dPredictor() {
    }

    /**
     * Prepares the predictor for a slice.
     *
     * @param header the slice segment header
     * @param refList the reference picture lists
     */
    void setSlice(HevcSliceHeader header, HevcFrame[][] refList) {
        this.header = header;
        this.refList = refList;
        this.views = header.interView;
    }

    /**
     * Predicts the samples of a prediction unit with residual prediction or illumination
     * compensation into the picture being decoded.
     *
     * @param frame the picture being decoded
     * @param xPb the luma x of the prediction block
     * @param yPb the luma y of the prediction block
     * @param width the prediction block width
     * @param height the prediction block height
     * @param m the motion of the prediction unit
     */
    void predict(HevcFrame frame, int xPb, int yPb, int width, int height, MotionPredictor.PuMotion m) {
        target = frame;
        var sps = header.sps;
        var chroma = sps.chromaArrayType != 0;
        for (var x = 0; x < 2; x++) {
            if (!m.predFlag[x]) {
                continue;
            }
            if (m.residualWeight != 0) {
                if (refList[x][m.refIdx[x]].poc == frame.poc) {
                    interViewResidual(xPb, yPb, width, height, m, x);
                } else {
                    temporalResidual(xPb, yPb, width, height, m, x);
                }
            } else {
                var ref = refList[x][m.refIdx[x]];
                var mvX = m.mvX[x];
                var mvY = m.mvY[x];
                interpolate(ref, 0, xPb, yPb, width, height, mvX, mvY, false, prediction[x][0]);
                if (chroma) {
                    for (var c = 1; c <= 2; c++) {
                        interpolate(ref, c, xPb, yPb, width, height, mvX, mvY, false, prediction[x][c]);
                    }
                }
                if (m.illuminationCompensation && views.refViewIdx[x][m.refIdx[x]] != views.tools.viewIdx) {
                    compensate(ref, 0, xPb, yPb, width, height, mvX, mvY, m, prediction[x][0]);
                    if (chroma && width > 8) {
                        for (var c = 1; c <= 2; c++) {
                            compensate(ref, c, xPb, yPb, width, height, mvX, mvY, m, prediction[x][c]);
                        }
                    }
                }
            }
        }
        store(0, xPb, yPb, width, height, m);
        if (chroma) {
            var sx = sps.chromaShiftX;
            var sy = sps.chromaShiftY;
            for (var c = 1; c <= 2; c++) {
                store(c, xPb >> sx, yPb >> sy, width >> sx, height >> sy, m);
            }
        }
    }

    /**
     * Predicts one list of a prediction unit from a temporal reference with its residual from the
     * reference view (I.8.5.3.3.3.1 with {@code ivRefFlagLX} equal to 0).
     *
     * @param xPb the luma x of the prediction block
     * @param yPb the luma y of the prediction block
     * @param width the prediction block width
     * @param height the prediction block height
     * @param m the motion of the prediction unit
     * @param x the list
     */
    private void temporalResidual(int xPb, int yPb, int width, int height, MotionPredictor.PuMotion m, int x) {
        var refIdx = m.refIdx[x];
        var mv = pack(m.mvX[x], m.mvY[x]);
        var weight = m.residualWeight;
        var view = m.refViewIdx;
        var arpRefIdx = views.rpRefIdx[x];
        HevcFrame baseCol = null;
        HevcFrame baseRef = null;
        if (arpRefIdx < 0 || view < 0 || view >= InterViewContext.MAX_VIEWS || !views.refRpRefAvail[x][view]) {
            weight = 0;
        } else {
            if (arpRefIdx != refIdx) {
                var poc = target.poc;
                mv = MotionPredictor.scale(mv, poc - refList[x][refIdx].poc, poc - refList[x][arpRefIdx].poc);
                refIdx = arpRefIdx;
            }
            baseCol = views.texture[view];
            baseRef = views.texture(view, refList[x][arpRefIdx].poc);
            if (baseCol == null || baseRef == null) {
                throw new MediaException.Malformed("residual prediction without the pictures of its reference view");
            }
        }
        mv = clip(mv, m);
        var ref = refList[x][refIdx];
        predictBilinear(ref, xPb, yPb, width, height, mv, x);
        if (weight == 0) {
            return;
        }
        var disparity = m.dispVec;
        var withDisparity = clip(add(mv, disparity), m);
        addResidual(baseCol, clip(disparity, m), baseRef, withDisparity, xPb, yPb, width, height, weight, x);
    }

    /**
     * Predicts one list of a prediction unit from an inter-view reference with its residual from
     * the temporal references (I.8.5.3.3.3.1 with {@code ivRefFlagLX} equal to 1), as the
     * reference decoder derives it: the temporal motion of the other list when it refers to a
     * picture of the current view, that of the reference block of the other list's inter-view
     * reference when both lists are inter-view and the list is list 1, that of the reference block,
     * or a zero vector.
     *
     * @param xPb the luma x of the prediction block
     * @param yPb the luma y of the prediction block
     * @param width the prediction block width
     * @param height the prediction block height
     * @param m the motion of the prediction unit
     * @param x the list
     */
    private void interViewResidual(int xPb, int yPb, int width, int height, MotionPredictor.PuMotion m, int x) {
        var refIdx = m.refIdx[x];
        var dmv = pack(m.mvX[x], m.mvY[x]);
        var weight = m.residualWeight;
        var baseCol = refList[x][refIdx];
        var baseView = baseCol.viewIdx;
        var view = views.tools.viewIdx;
        HevcFrame baseTRef = null;
        HevcFrame currTRef = null;
        var available = false;
        var baseTmv = 0;
        if (header.sliceType == HevcSliceHeader.B && !views.tools.depth) {
            var other = 1 - x;
            var otherRef = m.predFlag[other] ? m.refIdx[other] : -1;
            if (otherRef >= 0 && views.refViewIdx[other][otherRef] == view) {
                available = true;
                var currRefPoc = refList[other][otherRef].poc;
                var currRef = views.rpRefIdx[other];
                if (currRef >= 0) {
                    currTRef = refList[other][currRef];
                    var targetPoc = currTRef.poc;
                    baseTRef = views.texture(baseView, targetPoc);
                    if (baseTRef != null) {
                        baseTmv = MotionPredictor.scale(pack(m.mvX[other], m.mvY[other]), target.poc - currRefPoc,
                                target.poc - targetPoc);
                    } else {
                        weight = 0;
                    }
                } else {
                    weight = 0;
                }
            }
            if (otherRef >= 0 && !available) {
                available = x != 0 && views.refViewIdx[other][otherRef] != view;
                if (available) {
                    if (biInterViewMotion(xPb, yPb, width, height, m)) {
                        currTRef = baseCurrent;
                        baseTmv = baseMv;
                        baseTRef = views.texture(baseView, baseTargetPoc);
                        if (baseTRef == null) {
                            weight = 0;
                        }
                    } else {
                        weight = 0;
                    }
                }
            }
        }
        if (!available && blockMotion(baseCol, xPb, yPb, width, height, dmv, true)) {
            currTRef = baseCurrent;
            baseTRef = views.texture(baseView, baseTargetPoc);
            baseTmv = baseMv;
            available = true;
        }
        if (!available) {
            var first = views.rpRefIdx[x];
            var slice = sliceAt(baseCol, Math.clamp(xPb + width / 2 + (((short) dmv + 2) >> 2), 0, target.width - 1),
                    Math.clamp(yPb + height / 2 + (((dmv >> 16) + 2) >> 2), 0, target.height - 1));
            if (first < 0 || slice < 0 || baseCol.sliceRefId[slice][x * 16 + first] < 0) {
                weight = 0;
            } else {
                currTRef = refList[x][first];
                var entry = x * 16 + first;
                var entryView = views.viewOfReference(baseCol, slice, entry);
                baseTRef = views.texture(entryView, baseCol.sliceRefPoc[slice][entry]);
                if (baseTRef == null) {
                    weight = 0;
                }
            }
            baseTmv = 0;
        }
        predictBilinear(baseCol, xPb, yPb, width, height, clip(dmv, m), x);
        if (weight == 0) {
            return;
        }
        var withTemporal = clip(add(dmv, baseTmv), m);
        addResidual(currTRef, clip(baseTmv, m), baseTRef, withTemporal, xPb, yPb, width, height, weight, x);
    }

    /**
     * Derives the temporal motion of the residual of a list 1 inter-view prediction whose list 0
     * is an inter-view prediction too, from the reference block of the list 0 vector, or a zero
     * vector to {@code RefPicList0[ RpRefIdxL0 ]}.
     *
     * @param xPb the luma x of the prediction block
     * @param yPb the luma y of the prediction block
     * @param width the prediction block width
     * @param height the prediction block height
     * @param m the motion of the prediction unit
     * @return whether a motion was found, then in {@link #baseMv}, {@link #baseTargetPoc} and
     * {@link #baseCurrent}
     */
    private boolean biInterViewMotion(int xPb, int yPb, int width, int height, MotionPredictor.PuMotion m) {
        var baseCol = refList[0][m.refIdx[0]];
        if (blockMotion(baseCol, xPb, yPb, width, height, pack(m.mvX[0], m.mvY[0]), false)) {
            return true;
        }
        var first = views.rpRefIdx[0];
        if (first >= 0) {
            baseMv = 0;
            baseCurrent = refList[0][first];
            baseTargetPoc = baseCurrent.poc;
            return true;
        }
        return false;
    }

    /**
     * Derives the temporal motion of the reference block an inter-view vector points to (the 8x8
     * block covering the center of the prediction block displaced by the vector): its first
     * motion vector, in list order, that refers to a temporal picture of the reference view, when
     * the slice has a target reference picture for the residual in that list, scaled to it.
     *
     * @param baseCol the inter-view reference picture
     * @param xPb the luma x of the prediction block
     * @param yPb the luma y of the prediction block
     * @param width the prediction block width
     * @param height the prediction block height
     * @param dmv the packed inter-view vector
     * @param checkAvailability whether the list must have {@code RefRpRefAvailFlagLX} set for the
     * reference view
     * @return whether a motion was found, then in {@link #baseMv}, {@link #baseTargetPoc} and
     * {@link #baseCurrent}
     */
    private boolean blockMotion(HevcFrame baseCol, int xPb, int yPb, int width, int height, int dmv,
                                boolean checkAvailability) {
        if (baseCol.width != target.width || baseCol.height != target.height) {
            return false;
        }
        var xRef = Math.clamp(xPb + width / 2 + (((short) dmv + 2) >> 2), 0, target.width - 1);
        var yRef = Math.clamp(yPb + height / 2 + (((dmv >> 16) + 2) >> 2), 0, target.height - 1);
        xRef = (xRef >> 3) << 3;
        yRef = (yRef >> 3) << 3;
        baseCol.awaitDecodedRows(yRef + 8);
        var slice = sliceAt(baseCol, xRef, yRef);
        if (slice < 0) {
            return false;
        }
        var block = (yRef >> 2) * baseCol.width4 + (xRef >> 2);
        var view = baseCol.viewIdx;
        for (var list = 0; list < 2; list++) {
            var ref = list == 0 ? baseCol.ref0[block] : baseCol.ref1[block];
            if (ref < 0 || checkAvailability && (view >= InterViewContext.MAX_VIEWS || !views.refRpRefAvail[list][view])) {
                continue;
            }
            var refPoc = baseCol.sliceRefPoc[slice][list * 16 + ref];
            var currRef = views.rpRefIdx[list];
            if (currRef < 0 || refPoc == baseCol.poc) {
                continue;
            }
            var targetPoc = refList[list][currRef].poc;
            if (views.texture(view, targetPoc) == null) {
                continue;
            }
            var mv = list == 0 ? baseCol.mv0[block] : baseCol.mv1[block];
            baseMv = MotionPredictor.scale(mv, baseCol.poc - refPoc, baseCol.poc - targetPoc);
            baseTargetPoc = targetPoc;
            baseCurrent = refList[list][currRef];
            return true;
        }
        return false;
    }

    /**
     * Returns the slice of a picture covering a luma location.
     *
     * @param picture the decoded picture to read
     * @param x the luma x
     * @param y the luma y
     * @return the slice index, or -1 when none was decoded there
     */
    private static int sliceAt(HevcFrame picture, int x, int y) {
        var sps = picture.sps;
        var ctb = (y >> sps.log2CtbSize) * sps.widthInCtbs + (x >> sps.log2CtbSize);
        if (picture.ctbSlice == null || ctb >= picture.ctbSlice.length) {
            return -1;
        }
        var slice = picture.ctbSlice[ctb];
        return slice >= 0 && slice < picture.sliceCount ? slice : -1;
    }

    /**
     * Interpolates the bilinear prediction of one list of a prediction unit (I.8.5.3.3.3.2) for
     * every component.
     *
     * @param ref the reference picture
     * @param xPb the luma x of the prediction block
     * @param yPb the luma y of the prediction block
     * @param width the prediction block width
     * @param height the prediction block height
     * @param mv the packed luma motion vector
     * @param x the list receiving the prediction
     */
    private void predictBilinear(HevcFrame ref, int xPb, int yPb, int width, int height, int mv, int x) {
        interpolate(ref, 0, xPb, yPb, width, height, (short) mv, mv >> 16, true, prediction[x][0]);
        if (header.sps.chromaArrayType != 0) {
            for (var c = 1; c <= 2; c++) {
                interpolate(ref, c, xPb, yPb, width, height, (short) mv, mv >> 16, true, prediction[x][c]);
            }
        }
    }

    /**
     * Adds the residual predictor of a list: the difference of the bilinear predictions of two
     * pictures, halved for weight index 2, to the luma prediction and, for blocks wider than 8
     * samples, to the chroma predictions (equations I-234 to I-236).
     *
     * @param first the picture of the first prediction
     * @param firstMv the packed vector of the first prediction
     * @param second the picture of the second prediction
     * @param secondMv the packed vector of the second prediction
     * @param xPb the luma x of the prediction block
     * @param yPb the luma y of the prediction block
     * @param width the prediction block width
     * @param height the prediction block height
     * @param weight {@code iv_res_pred_weight_idx}
     * @param x the list
     */
    private void addResidual(HevcFrame first, int firstMv, HevcFrame second, int secondMv, int xPb, int yPb,
                             int width, int height, int weight, int x) {
        var shift = weight - 1;
        var components = header.sps.chromaArrayType != 0 && width > 8 ? 3 : 1;
        for (var c = 0; c < components; c++) {
            interpolate(first, c, xPb, yPb, width, height, (short) firstMv, firstMv >> 16, true, base0);
            interpolate(second, c, xPb, yPb, width, height, (short) secondMv, secondMv >> 16, true, base1);
            var count = c == 0 ? width * height
                    : (width >> header.sps.chromaShiftX) * (height >> header.sps.chromaShiftY);
            var out = prediction[x][c];
            for (var i = 0; i < count; i++) {
                out[i] += (base0[i] - base1[i]) >> shift;
            }
        }
    }

    /**
     * Interpolates one component of a block with the luma and chroma interpolation filters of
     * 8.5.3.3.3 or the bilinear filters of I.8.5.3.3.3.3, into 14-bit intermediate samples, the
     * reference coordinates clamped to the picture.
     *
     * @param ref the reference picture
     * @param cIdx the component
     * @param xPb the luma x of the prediction block
     * @param yPb the luma y of the prediction block
     * @param width the luma block width
     * @param height the luma block height
     * @param mvX the horizontal luma motion vector, quarter samples
     * @param mvY the vertical luma motion vector, quarter samples
     * @param bilinear whether the bilinear filters are used
     * @param out receives the block, one row of the component's width after another
     */
    private void interpolate(HevcFrame ref, int cIdx, int xPb, int yPb, int width, int height, int mvX, int mvY,
                             boolean bilinear, int[] out) {
        var sps = header.sps;
        int x0;
        int y0;
        int w;
        int h;
        int xFrac;
        int yFrac;
        int bitDepth;
        if (cIdx == 0) {
            x0 = xPb + (mvX >> 2);
            y0 = yPb + (mvY >> 2);
            w = width;
            h = height;
            xFrac = mvX & 3;
            yFrac = mvY & 3;
            bitDepth = sps.bitDepthLuma;
        } else {
            var sx = sps.chromaShiftX;
            var sy = sps.chromaShiftY;
            var mvcX = mvX * 2 >> sx;
            var mvcY = mvY * 2 >> sy;
            x0 = (xPb >> sx) + (mvcX >> 3);
            y0 = (yPb >> sy) + (mvcY >> 3);
            w = width >> sx;
            h = height >> sy;
            xFrac = mvcX & 7;
            yFrac = mvcY & 7;
            bitDepth = sps.bitDepthChroma;
        }
        var sy = cIdx == 0 ? 0 : sps.chromaShiftY;
        ref.awaitRows(Math.max(1, (y0 + h + 4) << sy));
        if (bilinear) {
            if (cIdx == 0) {
                xFrac <<= 1;
                yFrac <<= 1;
            }
            bilinear(ref, cIdx, x0, y0, w, h, xFrac, yFrac, bitDepth, out);
            return;
        }
        if (cIdx == 0) {
            var sw = w + 7;
            fetch(ref, 0, x0 - 3, y0 - 3, sw, h + 7);
            ScalarHevcDsp.wideLumaMc(footprint, 3 * sw + 3, sw, out, w, h, xFrac, yFrac, bitDepth, scratch);
        } else {
            var sw = w + 3;
            fetch(ref, cIdx, x0 - 1, y0 - 1, sw, h + 3);
            ScalarHevcDsp.wideChromaMc(footprint, sw + 1, sw, out, w, h, xFrac, yFrac, bitDepth, scratch);
        }
    }

    /**
     * Interpolates one component of a block with the bilinear filters (I.8.5.3.3.3.3).
     *
     * @param ref the reference picture
     * @param cIdx the component
     * @param x0 the x of the full-sample position of the block in the component
     * @param y0 the y of the full-sample position
     * @param w the block width in the component
     * @param h the block height in the component
     * @param xFrac the horizontal offset, eighth samples
     * @param yFrac the vertical offset, eighth samples
     * @param bitDepth the component bit depth
     * @param out receives the block
     */
    private void bilinear(HevcFrame ref, int cIdx, int x0, int y0, int w, int h, int xFrac, int yFrac, int bitDepth,
                          int[] out) {
        var shift1 = Math.min(4, bitDepth - 8);
        var shift3 = Math.max(2, 14 - bitDepth);
        var cw = cIdx == 0 ? ref.width : ref.chromaWidth;
        var chh = cIdx == 0 ? ref.height : ref.chromaHeight;
        if (xFrac == 0 && yFrac == 0) {
            for (var y = 0; y < h; y++) {
                var yy = Math.clamp(y0 + y, 0, chh - 1);
                for (var x = 0; x < w; x++) {
                    out[y * w + x] = sample(ref, cIdx, Math.clamp(x0 + x, 0, cw - 1), yy) << shift3;
                }
            }
            return;
        }
        var fx1 = 8 * xFrac;
        var fx0 = 64 - fx1;
        var fy1 = 8 * yFrac;
        var fy0 = 64 - fy1;
        if (yFrac == 0) {
            for (var y = 0; y < h; y++) {
                var yy = Math.clamp(y0 + y, 0, chh - 1);
                for (var x = 0; x < w; x++) {
                    var a = sample(ref, cIdx, Math.clamp(x0 + x, 0, cw - 1), yy);
                    var b = sample(ref, cIdx, Math.clamp(x0 + x + 1, 0, cw - 1), yy);
                    out[y * w + x] = (fx0 * a + fx1 * b) >> shift1;
                }
            }
            return;
        }
        if (xFrac == 0) {
            for (var y = 0; y < h; y++) {
                var ya = Math.clamp(y0 + y, 0, chh - 1);
                var yb = Math.clamp(y0 + y + 1, 0, chh - 1);
                for (var x = 0; x < w; x++) {
                    var xx = Math.clamp(x0 + x, 0, cw - 1);
                    out[y * w + x] = (fy0 * sample(ref, cIdx, xx, ya) + fy1 * sample(ref, cIdx, xx, yb)) >> shift1;
                }
            }
            return;
        }
        for (var y = 0; y <= h; y++) {
            var yy = Math.clamp(y0 + y, 0, chh - 1);
            for (var x = 0; x < w; x++) {
                var a = sample(ref, cIdx, Math.clamp(x0 + x, 0, cw - 1), yy);
                var b = sample(ref, cIdx, Math.clamp(x0 + x + 1, 0, cw - 1), yy);
                rows[y * w + x] = (fx0 * a + fx1 * b) >> shift1;
            }
        }
        for (var y = 0; y < h; y++) {
            for (var x = 0; x < w; x++) {
                out[y * w + x] = (fy0 * rows[y * w + x] + fy1 * rows[(y + 1) * w + x]) >> 6;
            }
        }
    }

    /**
     * Copies an area of a component of a picture into {@link #footprint}, clamping the
     * coordinates to the component.
     *
     * @param ref the picture
     * @param cIdx the component
     * @param x0 the x of the area
     * @param y0 the y of the area
     * @param w the area width
     * @param h the area height
     */
    private void fetch(HevcFrame ref, int cIdx, int x0, int y0, int w, int h) {
        var cw = cIdx == 0 ? ref.width : ref.chromaWidth;
        var ch = cIdx == 0 ? ref.height : ref.chromaHeight;
        for (var y = 0; y < h; y++) {
            var yy = Math.clamp(y0 + y, 0, ch - 1);
            for (var x = 0; x < w; x++) {
                footprint[y * w + x] = (short) sample(ref, cIdx, Math.clamp(x0 + x, 0, cw - 1), yy);
            }
        }
    }

    /**
     * Returns a decoded sample of a picture.
     *
     * @param picture the decoded picture to read
     * @param cIdx the component
     * @param x the x in the component
     * @param y the y in the component
     * @return the sample value
     */
    private static int sample(HevcFrame picture, int cIdx, int x, int y) {
        var stride = cIdx == 0 ? picture.width : picture.chromaWidth;
        var index = y * stride + x;
        if (picture.y8 != null) {
            var plane = cIdx == 0 ? picture.y8 : cIdx == 1 ? picture.cb8 : picture.cr8;
            return plane[index] & 0xFF;
        }
        var plane = cIdx == 0 ? picture.y : cIdx == 1 ? picture.cb : picture.cr;
        return plane[index] & 0xFFFF;
    }

    /**
     * Returns a sample of the picture being decoded before the in-loop filters: the saved bottom
     * line of the row of coding tree blocks above for the row just above a row of coding tree
     * blocks, the picture otherwise.
     *
     * @param cIdx the component
     * @param x the x in the component
     * @param y the y in the component
     * @return the sample value
     */
    private int unfiltered(int cIdx, int x, int y) {
        var sps = header.sps;
        var shiftY = cIdx == 0 ? 0 : sps.chromaShiftY;
        var ctbMask = (1 << (sps.log2CtbSize - shiftY)) - 1;
        if (((y + 1) & ctbMask) == 0) {
            var row = (y + 1) >> (sps.log2CtbSize - shiftY);
            var stride = cIdx == 0 ? target.width : target.chromaWidth;
            var at = (row - 1) * stride + x;
            return target.edge8 != null ? target.edge8[cIdx][at] & 0xFF : target.edge16[cIdx][at] & 0xFFFF;
        }
        return sample(target, cIdx, x, y);
    }

    /**
     * Applies the illumination compensation of one component of one list (I.8.5.3.3.2): the
     * prediction is rounded to samples, weighted and offset with parameters fitted between the
     * neighbors of the coding block and of the reference block, and returned to the intermediate
     * precision.
     *
     * @param ref the reference picture
     * @param cIdx the component
     * @param xPb the luma x of the prediction block, the coding block
     * @param yPb the luma y of the prediction block
     * @param width the luma block width
     * @param height the luma block height
     * @param mvX the horizontal luma motion vector
     * @param mvY the vertical luma motion vector
     * @param m the motion of the prediction unit, with the neighbor availability
     * @param pred the intermediate prediction, modified
     */
    private void compensate(HevcFrame ref, int cIdx, int xPb, int yPb, int width, int height, int mvX, int mvY,
                            MotionPredictor.PuMotion m, int[] pred) {
        var sps = header.sps;
        var sx = cIdx == 0 ? 0 : sps.chromaShiftX;
        var sy = cIdx == 0 ? 0 : sps.chromaShiftY;
        var bitDepth = cIdx == 0 ? sps.bitDepthLuma : sps.bitDepthChroma;
        var xC = xPb >> sx;
        var yC = yPb >> sy;
        var w = width >> sx;
        var h = height >> sy;
        var xRef = xC + (cIdx == 0 ? (mvX + 2) >> 2 : (mvX + 4) >> 3);
        var yRef = yC + (cIdx == 0 ? (mvY + 2) >> 2 : (mvY + 4) >> 3);
        var cw = cIdx == 0 ? ref.width : ref.chromaWidth;
        var ch = cIdx == 0 ? ref.height : ref.chromaHeight;
        var precShift = Math.max(0, bitDepth - 12);
        long sumRef = 0;
        long sumCur = 0;
        long sumRefSquare = 0;
        long sumProduct = 0;
        var countShift = 0;
        var log2W = 31 - Integer.numberOfLeadingZeros(w);
        if (m.icAbove) {
            var yy = Math.clamp(yRef - 1, 0, ch - 1);
            for (var j = 0; j < w; j += 2) {
                long r = sample(ref, cIdx, Math.clamp(xRef + j, 0, cw - 1), yy);
                long c = unfiltered(cIdx, xC + j, yC - 1);
                sumRef += r;
                sumCur += c;
                if (cIdx == 0) {
                    sumRefSquare += (r * r) >> precShift;
                    sumProduct += (r * c) >> precShift;
                }
            }
            countShift += log2W - 1;
        }
        if (m.icLeft) {
            var xx = Math.clamp(xRef - 1, 0, cw - 1);
            for (var i = 0; i < h; i += 2) {
                long r = sample(ref, cIdx, xx, Math.clamp(yRef + i, 0, ch - 1));
                long c = unfiltered(cIdx, xC - 1, yC + i);
                sumRef += r;
                sumCur += c;
                if (cIdx == 0) {
                    sumRefSquare += (r * r) >> precShift;
                    sumProduct += (r * c) >> precShift;
                }
            }
            countShift += countShift > 0 ? 1 : log2W - 1;
        }
        int a;
        int b;
        if (countShift == 0) {
            a = 32;
            b = 0;
        } else if (cIdx != 0) {
            a = 32;
            b = (int) ((sumCur - sumRef + (1L << (countShift - 1))) >> countShift);
        } else {
            sumProduct += sumRefSquare >> 7;
            sumRefSquare += sumRefSquare >> 7;
            var a1 = (sumProduct << countShift) - ((sumCur * sumRef) >> precShift);
            var a2 = (sumRefSquare << countShift) - ((sumRef * sumRef) >> precShift);
            a1 = Math.min(2 * a2, Math.max(0, a1));
            var msb = a2 == 0 ? 1 : 64 - Long.numberOfLeadingZeros(Math.abs(a2));
            var shiftA2 = Math.max(0, msb - 6);
            var shiftA1 = Math.max(0, shiftA2 - 12);
            var shiftA = shiftA2 + 15 - 5 - shiftA1;
            var a2s = (int) (a2 >> shiftA2);
            var a1s = a1 >> shiftA1;
            a = (int) ((a1s * DIV_COEFF[Math.clamp(a2s, 0, 63)]) >> shiftA);
            b = (int) ((sumCur - ((a * sumRef) >> 5) + (1L << (countShift - 1))) >> countShift);
        }
        var shift = Math.max(2, 14 - bitDepth);
        var offset = 1 << (shift - 1);
        var max = (1 << bitDepth) - 1;
        var count = w * h;
        for (var i = 0; i < count; i++) {
            var value = Math.clamp((pred[i] + offset) >> shift, 0, max);
            pred[i] = Math.clamp(((a * value) >> 5) + b, 0, max) << shift;
        }
    }

    /**
     * Combines the intermediate predictions of one component into the picture with the default or
     * explicit weighted sample prediction (8.5.3.3.4).
     *
     * @param cIdx the component
     * @param x0 the x of the block in the component
     * @param y0 the y of the block in the component
     * @param w the block width in the component
     * @param h the block height in the component
     * @param m the motion of the prediction unit
     */
    private void store(int cIdx, int x0, int y0, int w, int h, MotionPredictor.PuMotion m) {
        var sps = header.sps;
        var bitDepth = cIdx == 0 ? sps.bitDepthLuma : sps.bitDepthChroma;
        var max = (1 << bitDepth) - 1;
        var shift1 = Math.max(2, 14 - bitDepth);
        var shift2 = Math.max(3, 15 - bitDepth);
        var p0 = prediction[0][cIdx];
        var p1 = prediction[1][cIdx];
        var bi = m.predFlag[0] && m.predFlag[1];
        var single = m.predFlag[0] ? p0 : p1;
        var stride = cIdx == 0 ? target.width : target.chromaWidth;
        var plane8 = target.y8 != null ? cIdx == 0 ? target.y8 : cIdx == 1 ? target.cb8 : target.cr8 : null;
        var plane = target.y8 == null ? cIdx == 0 ? target.y : cIdx == 1 ? target.cb : target.cr : null;
        var weighted = header.weighted;
        var log2Wd = (cIdx == 0 ? header.lumaLog2WeightDenom : header.chromaLog2WeightDenom) + shift1;
        var offsetShift = sps.highPrecisionOffsets ? 0 : bitDepth - 8;
        var w0 = 0;
        var w1 = 0;
        var o0 = 0;
        var o1 = 0;
        if (weighted) {
            w0 = m.predFlag[0] ? header.weights[0][m.refIdx[0]][cIdx] : 0;
            w1 = m.predFlag[1] ? header.weights[1][m.refIdx[1]][cIdx] : 0;
            o0 = m.predFlag[0] ? header.offsets[0][m.refIdx[0]][cIdx] << offsetShift : 0;
            o1 = m.predFlag[1] ? header.offsets[1][m.refIdx[1]][cIdx] << offsetShift : 0;
        }
        for (var y = 0; y < h; y++) {
            var row = (y0 + y) * stride + x0;
            for (var x = 0; x < w; x++) {
                var i = y * w + x;
                int value;
                if (!weighted) {
                    value = bi ? (p0[i] + p1[i] + (1 << (shift2 - 1))) >> shift2 : (single[i] + (1 << (shift1 - 1))) >> shift1;
                } else if (bi) {
                    value = (p0[i] * w0 + p1[i] * w1 + ((o0 + o1 + 1) << log2Wd)) >> (log2Wd + 1);
                } else {
                    var weight = m.predFlag[0] ? w0 : w1;
                    var offset = m.predFlag[0] ? o0 : o1;
                    value = log2Wd >= 1 ? ((single[i] * weight + (1 << (log2Wd - 1))) >> log2Wd) + offset
                            : single[i] * weight + offset;
                }
                value = Math.clamp(value, 0, max);
                if (plane8 != null) {
                    plane8[row + x] = (byte) value;
                } else {
                    plane[row + x] = (short) value;
                }
            }
        }
    }

    /**
     * Clips a motion vector as the reference decoder does before it uses or sums it: to the
     * picture extended by a coding tree block and 8 samples around the coding block.
     *
     * @param mv the packed motion vector
     * @param m the motion of the prediction unit, with its coding block position
     * @return the clipped packed vector
     */
    private int clip(int mv, MotionPredictor.PuMotion m) {
        var sps = header.sps;
        var ctb = 1 << sps.log2CtbSize;
        var maxX = (sps.width + 8 - m.cuX - 1) << 2;
        var minX = (-ctb - 8 - m.cuX + 1) << 2;
        var maxY = (sps.height + 8 - m.cuY - 1) << 2;
        var minY = (-ctb - 8 - m.cuY + 1) << 2;
        return pack(Math.clamp((short) mv, minX, maxX), Math.clamp(mv >> 16, minY, maxY));
    }

    /**
     * Packs a motion vector.
     *
     * @param x the horizontal component
     * @param y the vertical component
     * @return the packed vector
     */
    private static int pack(int x, int y) {
        return (y << 16) | (x & 0xFFFF);
    }

    /**
     * Adds two packed motion vectors component by component, wrapping each to 16 bits.
     *
     * @param a the first vector
     * @param b the second vector
     * @return the packed sum
     */
    private static int add(int a, int b) {
        return pack((short) ((short) a + (short) b), (short) ((a >> 16) + (b >> 16)));
    }
}
