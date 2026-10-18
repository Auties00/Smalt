package com.github.auties00.smalt.video.hevc.internal;

import java.util.Arrays;

/**
 * Computes the inter prediction samples of prediction units (ITU-T H.265 8.5.3.3): the 8-tap luma
 * and 4-tap chroma fractional sample interpolation with the reference picture edges extended, and
 * the default and explicit weighted sample prediction.
 *
 * <p>The interpolation and the weighted prediction of components of up to 12 bits run on the
 * picture's {@link HevcDsp} kernels with 16-bit intermediate samples; deeper components, and the
 * luma blocks whose intermediate samples leave 16 bits, use the 32-bit processes of
 * {@link ScalarHevcDsp}. A reference block whose filter footprint lies inside the reference picture
 * is read in place; otherwise the footprint is first copied with its coordinates clamped to the
 * picture, as equations 8-222, 8-223, 8-239 and 8-240 require. A single list without weights at a
 * full-sample position copies the reference samples.
 *
 * <p>Instances are reused across slices and are not thread-safe.
 */
public final class InterPredictor {
    /**
     * The largest prediction block side.
     */
    private static final int MAX = HevcDsp.MAX_BLOCK;

    /**
     * The reference footprint of a block that crosses the reference picture's edges, edges
     * extended.
     */
    private final short[] edge = new short[(MAX + 7) * (MAX + 7)];

    /**
     * The extended reference area of a block of an 8-bit picture that reaches outside its reference
     * picture, with the padding the 8-bit kernels may read past it.
     */
    private final byte[] edge8 = new byte[(MAX + 7) * (MAX + 7) + HevcFrame.PADDING];

    /**
     * The block the 32-bit prediction processes write for an 8-bit picture before it is stored.
     */
    private short[] wideBlock;

    /**
     * The interpolation scratch of the kernels.
     */
    private final short[] scratch = new short[HevcDsp.SCRATCH_SIZE];

    /**
     * The 16-bit intermediate prediction samples of each list, for one component.
     */
    private final short[][] prediction = {new short[MAX * MAX], new short[MAX * MAX]};

    /**
     * The interpolation scratch of the 32-bit processes, allocated on first use.
     */
    private int[] wideScratch;

    /**
     * The 32-bit intermediate prediction samples of each list, allocated on first use.
     */
    private int[][] widePrediction;

    /**
     * The slice segment header.
     */
    private HevcSliceHeader header;

    /**
     * The reference picture lists.
     */
    private HevcFrame[][] refList;

    /**
     * Whether the slice uses explicit weighted prediction.
     */
    private boolean weighted;

    /**
     * The kernels of the picture being decoded.
     */
    private HevcDsp dsp = HevcDsp.SCALAR;

    /**
     * Constructs a predictor.
     */
    public InterPredictor() {
    }

    /**
     * Prepares the predictor for a slice.
     *
     * @param header the slice segment header
     * @param refList the reference picture lists, or {@code null} for an intra slice
     * @param dsp the kernels of the picture
     */
    public void setSlice(HevcSliceHeader header, HevcFrame[][] refList, HevcDsp dsp) {
        this.header = header;
        this.refList = refList;
        this.weighted = header.weighted;
        this.dsp = dsp;
    }

    /**
     * Predicts the samples of a prediction unit into the picture being decoded.
     *
     * @param frame the picture being decoded
     * @param xPb the luma x of the prediction block
     * @param yPb the luma y of the prediction block
     * @param width the prediction block width
     * @param height the prediction block height
     * @param m the motion of the prediction unit
     */
    public void predict(HevcFrame frame, int xPb, int yPb, int width, int height, MotionPredictor.PuMotion m) {
        if (frame.y8 != null) {
            predict8(frame, xPb, yPb, width, height, m);
            return;
        }
        var sps = header.sps;
        // A single list without weights at a full-sample position predicts the reference samples.
        var copyList = weighted || m.predFlag[0] == m.predFlag[1] ? -1 : m.predFlag[0] ? 0 : 1;
        if (copyList >= 0 && ((m.mvX[copyList] | m.mvY[copyList]) & 3) == 0) {
            var ref = refList[copyList][m.refIdx[copyList]];
            var xInt = xPb + (m.mvX[copyList] >> 2);
            var yInt = yPb + (m.mvY[copyList] >> 2);
            ref.awaitRows(Math.max(1, yInt + height));
            copyBlock(ref.y, ref.width, ref.height, xInt, yInt, frame.y, frame.width, xPb, yPb, width, height);
        } else {
            lumaPrediction(frame, xPb, yPb, width, height, m, sps.bitDepthLuma);
        }
        if (sps.chromaArrayType == 0) {
            return;
        }
        var sx = sps.chromaShiftX;
        var sy = sps.chromaShiftY;
        var cw = width >> sx;
        var ch = height >> sy;
        var cx = xPb >> sx;
        var cy = yPb >> sy;
        if (copyList >= 0) {
            var mvcX = m.mvX[copyList] * 2 >> sx;
            var mvcY = m.mvY[copyList] * 2 >> sy;
            if (((mvcX | mvcY) & 7) == 0) {
                var ref = refList[copyList][m.refIdx[copyList]];
                var xInt = cx + (mvcX >> 3);
                var yInt = cy + (mvcY >> 3);
                ref.awaitRows(Math.max(1, yInt + ch) << sy);
                copyBlock(ref.cb, ref.chromaWidth, ref.chromaHeight, xInt, yInt, frame.cb, frame.chromaWidth, cx, cy, cw,
                        ch);
                copyBlock(ref.cr, ref.chromaWidth, ref.chromaHeight, xInt, yInt, frame.cr, frame.chromaWidth, cx, cy, cw,
                        ch);
                return;
            }
        }
        for (var c = 1; c <= 2; c++) {
            chromaPrediction(frame, c, cx, cy, cw, ch, m, sps.bitDepthChroma, sx, sy);
        }
    }

    /**
     * Predicts the luma samples of a prediction unit: in 16-bit intermediate samples up to 12
     * bits, unless the interpolation reports a sample that does not fit, and in 32-bit
     * intermediate samples otherwise.
     *
     * @param frame the picture being decoded
     * @param xPb the luma x of the prediction block
     * @param yPb the luma y of the prediction block
     * @param width the prediction block width
     * @param height the prediction block height
     * @param m the motion of the prediction unit
     * @param bitDepth the luma bit depth
     */
    private void lumaPrediction(HevcFrame frame, int xPb, int yPb, int width, int height, MotionPredictor.PuMotion m,
                                int bitDepth) {
        if (bitDepth <= 12) {
            var fits = true;
            for (var x = 0; x < 2; x++) {
                if (m.predFlag[x]) {
                    var ref = refList[x][m.refIdx[x]];
                    fits &= luma(ref, xPb, yPb, width, height, m.mvX[x], m.mvY[x], bitDepth, prediction[x]);
                }
            }
            if (fits) {
                combine(frame.y, frame.width, xPb, yPb, width, height, m, 0, bitDepth);
                return;
            }
        }
        var wide = widePrediction();
        for (var x = 0; x < 2; x++) {
            if (m.predFlag[x]) {
                var ref = refList[x][m.refIdx[x]];
                wideLuma(ref, xPb, yPb, width, height, m.mvX[x], m.mvY[x], bitDepth, wide[x]);
            }
        }
        wideCombine(frame.y, frame.width, xPb, yPb, width, height, m, 0, bitDepth);
    }

    /**
     * Predicts the samples of one chroma component of a prediction unit.
     *
     * @param frame the picture being decoded
     * @param c the component, 1 or 2
     * @param cx the chroma x of the prediction block
     * @param cy the chroma y of the prediction block
     * @param cw the chroma block width
     * @param ch the chroma block height
     * @param m the motion of the prediction unit
     * @param bitDepth the chroma bit depth
     * @param sx the horizontal chroma shift
     * @param sy the vertical chroma shift
     */
    private void chromaPrediction(HevcFrame frame, int c, int cx, int cy, int cw, int ch, MotionPredictor.PuMotion m,
                                  int bitDepth, int sx, int sy) {
        var plane = c == 1 ? frame.cb : frame.cr;
        if (bitDepth <= 12) {
            var fits = true;
            for (var x = 0; x < 2; x++) {
                if (m.predFlag[x]) {
                    var ref = refList[x][m.refIdx[x]];
                    var mvcX = m.mvX[x] * 2 >> sx;
                    var mvcY = m.mvY[x] * 2 >> sy;
                    ref.awaitRows(Math.max(1, cy + (mvcY >> 3) + ch + 2) << sy);
                    fits &= chroma(c == 1 ? ref.cb : ref.cr, ref.chromaWidth, ref.chromaHeight, cx, cy, cw, ch, mvcX,
                            mvcY, bitDepth, prediction[x]);
                }
            }
            if (fits) {
                combine(plane, frame.chromaWidth, cx, cy, cw, ch, m, c, bitDepth);
                return;
            }
        }
        var wide = widePrediction();
        for (var x = 0; x < 2; x++) {
            if (m.predFlag[x]) {
                var ref = refList[x][m.refIdx[x]];
                var mvcX = m.mvX[x] * 2 >> sx;
                var mvcY = m.mvY[x] * 2 >> sy;
                ref.awaitRows(Math.max(1, cy + (mvcY >> 3) + ch + 2) << sy);
                wideChroma(c == 1 ? ref.cb : ref.cr, ref.chromaWidth, ref.chromaHeight, cx, cy, cw, ch, mvcX, mvcY,
                        bitDepth, wide[x]);
            }
        }
        wideCombine(plane, frame.chromaWidth, cx, cy, cw, ch, m, c, bitDepth);
    }

    /**
     * Returns the 32-bit prediction buffers, allocating them and their scratch on first use.
     *
     * @return the buffers of the two lists
     */
    private int[][] widePrediction() {
        if (widePrediction == null) {
            widePrediction = new int[][]{new int[MAX * MAX], new int[MAX * MAX]};
            wideScratch = new int[HevcDsp.SCRATCH_SIZE];
        }
        return widePrediction;
    }

    /**
     * Interpolates the luma prediction of one list in 16-bit intermediate samples (8.5.3.3.3.2).
     *
     * @param ref the reference picture
     * @param xPb the luma x of the prediction block
     * @param yPb the luma y of the prediction block
     * @param width the block width
     * @param height the block height
     * @param mvX the horizontal motion vector, quarter samples
     * @param mvY the vertical motion vector, quarter samples
     * @param bitDepth the luma bit depth, at most 12
     * @param out receives the prediction, {@code width} per row
     * @return {@code true} when every intermediate sample fits 16 bits
     */
    private boolean luma(HevcFrame ref, int xPb, int yPb, int width, int height, int mvX, int mvY, int bitDepth,
                         short[] out) {
        var xInt = xPb + (mvX >> 2);
        var yInt = yPb + (mvY >> 2);
        ref.awaitRows(Math.max(1, yInt + height + 4));
        var stride = ref.width;
        if (xInt >= 3 && yInt >= 3 && xInt + width + 4 <= stride && yInt + height + 4 <= ref.height) {
            return dsp.lumaMc(ref.y, yInt * stride + xInt, stride, out, width, height, mvX & 3, mvY & 3, bitDepth,
                    scratch);
        }
        var sw = width + 7;
        extend(ref.y, stride, ref.height, xInt - 3, yInt - 3, sw, height + 7);
        return dsp.lumaMc(edge, 3 * sw + 3, sw, out, width, height, mvX & 3, mvY & 3, bitDepth, scratch);
    }

    /**
     * Interpolates the luma prediction of one list in 32-bit intermediate samples (8.5.3.3.3.2).
     *
     * @param ref the reference picture
     * @param xPb the luma x of the prediction block
     * @param yPb the luma y of the prediction block
     * @param width the block width
     * @param height the block height
     * @param mvX the horizontal motion vector, quarter samples
     * @param mvY the vertical motion vector, quarter samples
     * @param bitDepth the luma bit depth
     * @param out receives the prediction, {@code width} per row
     */
    private void wideLuma(HevcFrame ref, int xPb, int yPb, int width, int height, int mvX, int mvY, int bitDepth,
                          int[] out) {
        var xInt = xPb + (mvX >> 2);
        var yInt = yPb + (mvY >> 2);
        ref.awaitRows(Math.max(1, yInt + height + 4));
        var stride = ref.width;
        if (xInt >= 3 && yInt >= 3 && xInt + width + 4 <= stride && yInt + height + 4 <= ref.height) {
            ScalarHevcDsp.wideLumaMc(ref.y, yInt * stride + xInt, stride, out, width, height, mvX & 3, mvY & 3,
                    bitDepth, wideScratch);
            return;
        }
        var sw = width + 7;
        extend(ref.y, stride, ref.height, xInt - 3, yInt - 3, sw, height + 7);
        ScalarHevcDsp.wideLumaMc(edge, 3 * sw + 3, sw, out, width, height, mvX & 3, mvY & 3, bitDepth, wideScratch);
    }

    /**
     * Interpolates the chroma prediction of one list in 16-bit intermediate samples
     * (8.5.3.3.3.3).
     *
     * @param plane the reference chroma plane
     * @param stride the plane width
     * @param planeHeight the plane height
     * @param xc the chroma x of the prediction block
     * @param yc the chroma y of the prediction block
     * @param width the chroma block width
     * @param height the chroma block height
     * @param mvX the horizontal chroma motion vector, eighth samples
     * @param mvY the vertical chroma motion vector, eighth samples
     * @param bitDepth the chroma bit depth, at most 12
     * @param out receives the prediction, {@code width} per row
     * @return {@code true} when every intermediate sample fits 16 bits
     */
    private boolean chroma(short[] plane, int stride, int planeHeight, int xc, int yc, int width, int height, int mvX,
                           int mvY, int bitDepth, short[] out) {
        var xInt = xc + (mvX >> 3);
        var yInt = yc + (mvY >> 3);
        if (xInt >= 1 && yInt >= 1 && xInt + width + 2 <= stride && yInt + height + 2 <= planeHeight) {
            return dsp.chromaMc(plane, yInt * stride + xInt, stride, out, width, height, mvX & 7, mvY & 7, bitDepth,
                    scratch);
        }
        var sw = width + 3;
        extend(plane, stride, planeHeight, xInt - 1, yInt - 1, sw, height + 3);
        return dsp.chromaMc(edge, sw + 1, sw, out, width, height, mvX & 7, mvY & 7, bitDepth, scratch);
    }

    /**
     * Interpolates the chroma prediction of one list in 32-bit intermediate samples
     * (8.5.3.3.3.3).
     *
     * @param plane the reference chroma plane
     * @param stride the plane width
     * @param planeHeight the plane height
     * @param xc the chroma x of the prediction block
     * @param yc the chroma y of the prediction block
     * @param width the chroma block width
     * @param height the chroma block height
     * @param mvX the horizontal chroma motion vector, eighth samples
     * @param mvY the vertical chroma motion vector, eighth samples
     * @param bitDepth the chroma bit depth
     * @param out receives the prediction, {@code width} per row
     */
    private void wideChroma(short[] plane, int stride, int planeHeight, int xc, int yc, int width, int height, int mvX,
                            int mvY, int bitDepth, int[] out) {
        var xInt = xc + (mvX >> 3);
        var yInt = yc + (mvY >> 3);
        if (xInt >= 1 && yInt >= 1 && xInt + width + 2 <= stride && yInt + height + 2 <= planeHeight) {
            ScalarHevcDsp.wideChromaMc(plane, yInt * stride + xInt, stride, out, width, height, mvX & 7, mvY & 7,
                    bitDepth, wideScratch);
            return;
        }
        var sw = width + 3;
        extend(plane, stride, planeHeight, xInt - 1, yInt - 1, sw, height + 3);
        ScalarHevcDsp.wideChromaMc(edge, sw + 1, sw, out, width, height, mvX & 7, mvY & 7, bitDepth, wideScratch);
    }

    /**
     * Copies a block of a reference plane into the picture being decoded, clamping the reference
     * coordinates to the plane: the prediction of a single list without weights at a full-sample
     * position, which equals the reference samples (equations 8-228 and 8-262).
     *
     * @param plane the reference plane
     * @param stride the reference plane width
     * @param planeHeight the reference plane height
     * @param xRef the x of the reference block, possibly outside the plane
     * @param yRef the y of the reference block, possibly outside the plane
     * @param dst the destination plane
     * @param dstStride the destination plane width
     * @param x0 the x of the block in the destination plane
     * @param y0 the y of the block in the destination plane
     * @param width the block width
     * @param height the block height
     */
    private void copyBlock(short[] plane, int stride, int planeHeight, int xRef, int yRef, short[] dst, int dstStride,
                           int x0, int y0, int width, int height) {
        if (xRef >= 0 && yRef >= 0 && xRef + width <= stride && yRef + height <= planeHeight) {
            for (var y = 0; y < height; y++) {
                System.arraycopy(plane, (yRef + y) * stride + xRef, dst, (y0 + y) * dstStride + x0, width);
            }
            return;
        }
        extend(plane, stride, planeHeight, xRef, yRef, width, height);
        for (var y = 0; y < height; y++) {
            System.arraycopy(edge, y * width, dst, (y0 + y) * dstStride + x0, width);
        }
    }

    /**
     * Copies a block of a reference plane into {@link #edge}, clamping the coordinates to the
     * plane.
     *
     * @param plane the reference plane
     * @param stride the plane width
     * @param planeHeight the plane height
     * @param x0 the x of the block's top-left sample, possibly outside the plane
     * @param y0 the y of the block's top-left sample, possibly outside the plane
     * @param width the block width, also the row stride of the copy
     * @param height the block height
     */
    private void extend(short[] plane, int stride, int planeHeight, int x0, int y0, int width, int height) {
        var dst = edge;
        var maxX = stride - 1;
        var maxY = planeHeight - 1;
        var left = Math.clamp(-x0, 0, width);
        var right = Math.clamp(x0 + width - stride, 0, width - left);
        var inside = width - left - right;
        for (var y = 0; y < height; y++) {
            var row = Math.clamp(y0 + y, 0, maxY) * stride;
            var out = y * width;
            if (left > 0) {
                Arrays.fill(dst, out, out + left, plane[row]);
            }
            if (inside > 0) {
                System.arraycopy(plane, row + x0 + left, dst, out + left, inside);
            }
            if (right > 0) {
                Arrays.fill(dst, out + width - right, out + width, plane[row + maxX]);
            }
        }
    }

    /**
     * Predicts one prediction block of a picture with 8-bit planes, as {@link #predict} does.
     *
     * @param frame the picture being decoded, with 8-bit planes
     * @param xPb the luma x of the block
     * @param yPb the luma y of the block
     * @param width the luma width of the block
     * @param height the luma height of the block
     * @param m the motion of the block
     */
    private void predict8(HevcFrame frame, int xPb, int yPb, int width, int height, MotionPredictor.PuMotion m) {
        var sps = header.sps;
        var copyList = weighted || m.predFlag[0] == m.predFlag[1] ? -1 : m.predFlag[0] ? 0 : 1;
        if (copyList >= 0 && ((m.mvX[copyList] | m.mvY[copyList]) & 3) == 0) {
            var ref = refList[copyList][m.refIdx[copyList]];
            var xInt = xPb + (m.mvX[copyList] >> 2);
            var yInt = yPb + (m.mvY[copyList] >> 2);
            ref.awaitRows(Math.max(1, yInt + height));
            copyBlock8(ref.y8, ref.width, ref.height, xInt, yInt, frame.y8, frame.width, xPb, yPb, width, height);
        } else {
            lumaPrediction8(frame, xPb, yPb, width, height, m);
        }
        if (sps.chromaArrayType == 0) {
            return;
        }
        var sx = sps.chromaShiftX;
        var sy = sps.chromaShiftY;
        var cw = width >> sx;
        var ch = height >> sy;
        var cx = xPb >> sx;
        var cy = yPb >> sy;
        if (copyList >= 0) {
            var mvcX = m.mvX[copyList] * 2 >> sx;
            var mvcY = m.mvY[copyList] * 2 >> sy;
            if (((mvcX | mvcY) & 7) == 0) {
                var ref = refList[copyList][m.refIdx[copyList]];
                var xInt = cx + (mvcX >> 3);
                var yInt = cy + (mvcY >> 3);
                ref.awaitRows(Math.max(1, yInt + ch) << sy);
                copyBlock8(ref.cb8, ref.chromaWidth, ref.chromaHeight, xInt, yInt, frame.cb8, frame.chromaWidth, cx,
                        cy, cw, ch);
                copyBlock8(ref.cr8, ref.chromaWidth, ref.chromaHeight, xInt, yInt, frame.cr8, frame.chromaWidth, cx,
                        cy, cw, ch);
                return;
            }
        }
        for (var c = 1; c <= 2; c++) {
            var plane = c == 1 ? frame.cb8 : frame.cr8;
            for (var x = 0; x < 2; x++) {
                if (m.predFlag[x]) {
                    var ref = refList[x][m.refIdx[x]];
                    var mvcX = m.mvX[x] * 2 >> sx;
                    var mvcY = m.mvY[x] * 2 >> sy;
                    ref.awaitRows(Math.max(1, cy + (mvcY >> 3) + ch + 2) << sy);
                    chroma8(c == 1 ? ref.cb8 : ref.cr8, ref.chromaWidth, ref.chromaHeight, cx, cy, cw, ch, mvcX, mvcY,
                            prediction[x]);
                }
            }
            combine8(plane, frame.chromaWidth, cx, cy, cw, ch, m, c);
        }
    }

    /**
     * Predicts the luma block of a prediction block of a picture with 8-bit planes, falling back
     * to the 32-bit processes when an intermediate sample leaves 16 bits.
     *
     * @param frame the picture being decoded
     * @param xPb the luma x of the block
     * @param yPb the luma y of the block
     * @param width the width of the block
     * @param height the height of the block
     * @param m the motion of the block
     */
    private void lumaPrediction8(HevcFrame frame, int xPb, int yPb, int width, int height,
                                 MotionPredictor.PuMotion m) {
        var fits = true;
        for (var x = 0; x < 2; x++) {
            if (m.predFlag[x]) {
                var ref = refList[x][m.refIdx[x]];
                fits &= luma8(ref, xPb, yPb, width, height, m.mvX[x], m.mvY[x], prediction[x]);
            }
        }
        if (fits) {
            combine8(frame.y8, frame.width, xPb, yPb, width, height, m, 0);
            return;
        }
        var wide = widePrediction();
        for (var x = 0; x < 2; x++) {
            if (m.predFlag[x]) {
                var ref = refList[x][m.refIdx[x]];
                var xInt = xPb + (m.mvX[x] >> 2);
                var yInt = yPb + (m.mvY[x] >> 2);
                var sw = width + 7;
                widen(ref.y8, ref.width, ref.height, xInt - 3, yInt - 3, sw, height + 7);
                ScalarHevcDsp.wideLumaMc(edge, 3 * sw + 3, sw, wide[x], width, height, m.mvX[x] & 3, m.mvY[x] & 3, 8,
                        wideScratch);
            }
        }
        if (wideBlock == null) {
            wideBlock = new short[MAX * MAX];
        }
        wideCombine(wideBlock, width, 0, 0, width, height, m, 0, 8);
        for (var y = 0; y < height; y++) {
            var row = (yPb + y) * frame.width + xPb;
            for (var x = 0; x < width; x++) {
                frame.y8[row + x] = (byte) wideBlock[y * width + x];
            }
        }
    }

    /**
     * Interpolates the luma prediction of one list from an 8-bit reference picture, extending the
     * reference when the filter reaches outside it.
     *
     * @param ref the reference picture
     * @param xPb the luma x of the block
     * @param yPb the luma y of the block
     * @param width the width of the block
     * @param height the height of the block
     * @param mvX the horizontal motion vector, in quarter samples
     * @param mvY the vertical motion vector, in quarter samples
     * @param out receives the prediction
     * @return {@code true} when every intermediate sample fits 16 bits
     */
    private boolean luma8(HevcFrame ref, int xPb, int yPb, int width, int height, int mvX, int mvY, short[] out) {
        var xInt = xPb + (mvX >> 2);
        var yInt = yPb + (mvY >> 2);
        ref.awaitRows(Math.max(1, yInt + height + 4));
        var stride = ref.width;
        if (xInt >= 3 && yInt >= 3 && xInt + width + 4 <= stride && yInt + height + 4 <= ref.height) {
            return dsp.lumaMc(ref.y8, yInt * stride + xInt, stride, out, width, height, mvX & 3, mvY & 3, scratch);
        }
        var sw = width + 7;
        extend8(ref.y8, stride, ref.height, xInt - 3, yInt - 3, sw, height + 7);
        return dsp.lumaMc(edge8, 3 * sw + 3, sw, out, width, height, mvX & 3, mvY & 3, scratch);
    }

    /**
     * Interpolates the chroma prediction of one list from an 8-bit reference plane, extending the
     * reference when the filter reaches outside it.
     *
     * @param plane the reference chroma plane
     * @param stride the plane's row stride, its width
     * @param planeHeight the plane's height
     * @param xc the chroma x of the block
     * @param yc the chroma y of the block
     * @param width the chroma width of the block
     * @param height the chroma height of the block
     * @param mvX the horizontal chroma motion vector, in eighth samples
     * @param mvY the vertical chroma motion vector, in eighth samples
     * @param out receives the prediction
     */
    private void chroma8(byte[] plane, int stride, int planeHeight, int xc, int yc, int width, int height, int mvX,
                         int mvY, short[] out) {
        var xInt = xc + (mvX >> 3);
        var yInt = yc + (mvY >> 3);
        if (xInt >= 1 && yInt >= 1 && xInt + width + 2 <= stride && yInt + height + 2 <= planeHeight) {
            dsp.chromaMc(plane, yInt * stride + xInt, stride, out, width, height, mvX & 7, mvY & 7, scratch);
            return;
        }
        var sw = width + 3;
        extend8(plane, stride, planeHeight, xInt - 1, yInt - 1, sw, height + 3);
        dsp.chromaMc(edge8, sw + 1, sw, out, width, height, mvX & 7, mvY & 7, scratch);
    }

    /**
     * Copies a block of an 8-bit reference plane at an integer position, extending the reference
     * when the block reaches outside it.
     *
     * @param plane the reference plane
     * @param stride the plane's row stride, its width
     * @param planeHeight the plane's height
     * @param xRef the x of the block in the reference
     * @param yRef the y of the block in the reference
     * @param dst the plane being decoded
     * @param dstStride its row stride
     * @param x0 the x of the block in {@code dst}
     * @param y0 the y of the block in {@code dst}
     * @param width the block width
     * @param height the block height
     */
    private void copyBlock8(byte[] plane, int stride, int planeHeight, int xRef, int yRef, byte[] dst, int dstStride,
                            int x0, int y0, int width, int height) {
        if (xRef >= 0 && yRef >= 0 && xRef + width <= stride && yRef + height <= planeHeight) {
            for (var y = 0; y < height; y++) {
                System.arraycopy(plane, (yRef + y) * stride + xRef, dst, (y0 + y) * dstStride + x0, width);
            }
            return;
        }
        extend8(plane, stride, planeHeight, xRef, yRef, width, height);
        for (var y = 0; y < height; y++) {
            System.arraycopy(edge8, y * width, dst, (y0 + y) * dstStride + x0, width);
        }
    }

    /**
     * Copies an area of an 8-bit plane into {@link #edge8}, {@code width} samples per row,
     * replicating the edge samples for the positions outside the plane (8.5.3.3.3.1).
     *
     * @param plane the reference plane
     * @param stride the plane's row stride, its width
     * @param planeHeight the plane's height
     * @param x0 the x of the area, possibly negative
     * @param y0 the y of the area, possibly negative
     * @param width the area width
     * @param height the area height
     */
    private void extend8(byte[] plane, int stride, int planeHeight, int x0, int y0, int width, int height) {
        var dst = edge8;
        var maxX = stride - 1;
        var maxY = planeHeight - 1;
        var left = Math.clamp(-x0, 0, width);
        var right = Math.clamp(x0 + width - stride, 0, width - left);
        var inside = width - left - right;
        for (var y = 0; y < height; y++) {
            var row = Math.clamp(y0 + y, 0, maxY) * stride;
            var out = y * width;
            if (left > 0) {
                Arrays.fill(dst, out, out + left, plane[row]);
            }
            if (inside > 0) {
                System.arraycopy(plane, row + x0 + left, dst, out + left, inside);
            }
            if (right > 0) {
                Arrays.fill(dst, out + width - right, out + width, plane[row + maxX]);
            }
        }
    }

    /**
     * Copies an area of an 8-bit plane into the 16-bit {@link #edge} buffer, {@code width} samples
     * per row, replicating the edge samples for the positions outside the plane, for the 32-bit
     * prediction processes.
     *
     * @param plane the reference plane
     * @param stride the plane's row stride, its width
     * @param planeHeight the plane's height
     * @param x0 the x of the area, possibly negative
     * @param y0 the y of the area, possibly negative
     * @param width the area width
     * @param height the area height
     */
    private void widen(byte[] plane, int stride, int planeHeight, int x0, int y0, int width, int height) {
        var dst = edge;
        var maxX = stride - 1;
        var maxY = planeHeight - 1;
        for (var y = 0; y < height; y++) {
            var row = Math.clamp(y0 + y, 0, maxY) * stride;
            var out = y * width;
            for (var x = 0; x < width; x++) {
                dst[out + x] = (short) (plane[row + Math.clamp(x0 + x, 0, maxX)] & 0xFF);
            }
        }
    }

    /**
     * Writes the 16-bit predictions of a block into an 8-bit plane with the default or explicit
     * weighted sample prediction, as {@link #combine} does at bit depth 8.
     *
     * @param plane the plane being decoded
     * @param stride its row stride
     * @param x0 the x of the block in the plane
     * @param y0 the y of the block in the plane
     * @param width the block width
     * @param height the block height
     * @param m the motion of the block
     * @param cIdx the component
     */
    private void combine8(byte[] plane, int stride, int x0, int y0, int width, int height, MotionPredictor.PuMotion m,
                          int cIdx) {
        var p0 = prediction[0];
        var p1 = prediction[1];
        var bi = m.predFlag[0] && m.predFlag[1];
        var single = m.predFlag[0] ? p0 : p1;
        if (!weighted) {
            if (bi) {
                dsp.putBi(plane, y0 * stride + x0, stride, p0, p1, width, height);
            } else {
                dsp.putUni(plane, y0 * stride + x0, stride, single, width, height);
            }
            return;
        }
        var log2Wd = (cIdx == 0 ? header.lumaLog2WeightDenom : header.chromaLog2WeightDenom) + 6;
        var w0 = m.predFlag[0] ? header.weights[0][m.refIdx[0]][cIdx] : 0;
        var w1 = m.predFlag[1] ? header.weights[1][m.refIdx[1]][cIdx] : 0;
        var o0 = m.predFlag[0] ? header.offsets[0][m.refIdx[0]][cIdx] : 0;
        var o1 = m.predFlag[1] ? header.offsets[1][m.refIdx[1]][cIdx] : 0;
        if (bi) {
            dsp.putWeightedBi(plane, y0 * stride + x0, stride, p0, p1, width, height, w0, w1, o0 + o1, log2Wd);
        } else {
            var w = m.predFlag[0] ? w0 : w1;
            var o = m.predFlag[0] ? o0 : o1;
            dsp.putWeightedUni(plane, y0 * stride + x0, stride, single, width, height, w, o, log2Wd);
        }
    }

    /**
     * Combines the 16-bit list predictions of one component into the picture (8.5.3.3.4).
     *
     * @param plane the destination plane
     * @param stride the plane width
     * @param x0 the x of the block in the plane
     * @param y0 the y of the block in the plane
     * @param width the block width
     * @param height the block height
     * @param m the motion
     * @param cIdx the component
     * @param bitDepth the component bit depth, at most 12
     */
    private void combine(short[] plane, int stride, int x0, int y0, int width, int height, MotionPredictor.PuMotion m,
                         int cIdx, int bitDepth) {
        var p0 = prediction[0];
        var p1 = prediction[1];
        var bi = m.predFlag[0] && m.predFlag[1];
        var single = m.predFlag[0] ? p0 : p1;
        if (!weighted) {
            if (bi) {
                dsp.putBi(plane, y0 * stride + x0, stride, p0, p1, width, height, bitDepth);
            } else {
                dsp.putUni(plane, y0 * stride + x0, stride, single, width, height, bitDepth);
            }
            return;
        }
        var log2Wd = (cIdx == 0 ? header.lumaLog2WeightDenom : header.chromaLog2WeightDenom) + 14 - bitDepth;
        var offsetShift = header.sps.highPrecisionOffsets ? 0 : bitDepth - 8;
        var w0 = m.predFlag[0] ? header.weights[0][m.refIdx[0]][cIdx] : 0;
        var w1 = m.predFlag[1] ? header.weights[1][m.refIdx[1]][cIdx] : 0;
        var o0 = m.predFlag[0] ? header.offsets[0][m.refIdx[0]][cIdx] << offsetShift : 0;
        var o1 = m.predFlag[1] ? header.offsets[1][m.refIdx[1]][cIdx] << offsetShift : 0;
        if (bi) {
            dsp.putWeightedBi(plane, y0 * stride + x0, stride, p0, p1, width, height, bitDepth, w0, w1, o0 + o1, log2Wd);
        } else {
            var w = m.predFlag[0] ? w0 : w1;
            var o = m.predFlag[0] ? o0 : o1;
            dsp.putWeightedUni(plane, y0 * stride + x0, stride, single, width, height, bitDepth, w, o, log2Wd);
        }
    }

    /**
     * Combines the 32-bit list predictions of one component into the picture (8.5.3.3.4), with
     * the shifts of the range extensions, {@code shift1 = Max(2, 14 - bitDepth)}.
     *
     * @param plane the destination plane
     * @param stride the plane width
     * @param x0 the x of the block in the plane
     * @param y0 the y of the block in the plane
     * @param width the block width
     * @param height the block height
     * @param m the motion
     * @param cIdx the component
     * @param bitDepth the component bit depth
     */
    private void wideCombine(short[] plane, int stride, int x0, int y0, int width, int height,
                             MotionPredictor.PuMotion m, int cIdx, int bitDepth) {
        var p0 = widePrediction[0];
        var p1 = widePrediction[1];
        var bi = m.predFlag[0] && m.predFlag[1];
        var single = m.predFlag[0] ? p0 : p1;
        if (!weighted) {
            if (bi) {
                ScalarHevcDsp.widePutBi(plane, y0 * stride + x0, stride, p0, p1, width, height, bitDepth);
            } else {
                ScalarHevcDsp.widePutUni(plane, y0 * stride + x0, stride, single, width, height, bitDepth);
            }
            return;
        }
        var shift1 = Math.max(2, 14 - bitDepth);
        var log2Wd = (cIdx == 0 ? header.lumaLog2WeightDenom : header.chromaLog2WeightDenom) + shift1;
        var offsetShift = header.sps.highPrecisionOffsets ? 0 : bitDepth - 8;
        var w0 = m.predFlag[0] ? header.weights[0][m.refIdx[0]][cIdx] : 0;
        var w1 = m.predFlag[1] ? header.weights[1][m.refIdx[1]][cIdx] : 0;
        var o0 = m.predFlag[0] ? header.offsets[0][m.refIdx[0]][cIdx] << offsetShift : 0;
        var o1 = m.predFlag[1] ? header.offsets[1][m.refIdx[1]][cIdx] << offsetShift : 0;
        if (bi) {
            ScalarHevcDsp.widePutWeightedBi(plane, y0 * stride + x0, stride, p0, p1, width, height, bitDepth, w0, w1,
                    o0 + o1, log2Wd);
        } else {
            var w = m.predFlag[0] ? w0 : w1;
            var o = m.predFlag[0] ? o0 : o1;
            ScalarHevcDsp.widePutWeightedUni(plane, y0 * stride + x0, stride, single, width, height, bitDepth, w, o,
                    log2Wd);
        }
    }
}
