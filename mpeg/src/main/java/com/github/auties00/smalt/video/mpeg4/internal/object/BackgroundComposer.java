/* Ports the Microsoft MPEG-4 Visual reference software of ISO/IEC 14496-5:2001 (version 2 FDAM1-2.3-001213,
 * sys/tps_bfshape.cpp: convertYuv, convertSeg, bg_comp_each, bg_comp_each_mode3 and pre_pad, and
 * sys/decoder/vopsedec.cpp: BackgroundComposition and BackgroundCompositionSS). Copyright 1997-2000 Sharp
 * Corporation, Samsung AIT, Microsoft Corporation and the contributors named in the sources; ISO/IEC free license for
 * products conforming to ISO/IEC 14496-2. Modified for Smalt. */
package com.github.auties00.smalt.video.mpeg4.internal.object;

import java.util.Arrays;

/**
 * Composes the pictures of the enhancement layers that enhance a part of their reference layer
 * ({@code enhancement_type} 1 with {@code background_composition}, ISO/IEC 14496-2 7.9.2.2 and 7.9.3): the
 * enhanced object over a background taken from the reference layer, as the reference decoder of ISO/IEC 14496-5
 * composes the pictures it writes.
 *
 * <p>For temporal scalability the background comes from the base layer VOPs before and after the enhancement VOP:
 * where the forward shape (the object in the previous base VOP) does not cover a sample it comes from the previous
 * VOP, where only the backward shape (the object in the next base VOP) leaves it uncovered from the next one, and
 * where both shapes cover it the samples around are padded in; without {@code background_composition} the previous
 * base VOP alone is the background. For spatial scalability the enhanced VOP replaces the upsampled reference layer
 * VOP (or the previous enhancement VOP when the enhancement layer predicts its shape on its own) inside its bounding
 * rectangle.
 *
 * <p>An instance keeps its work buffers between pictures; it is not thread-safe.
 */
final class BackgroundComposer {
    private static final int OPAQUE = 255;

    private int width;
    private int height;
    private int[] currY = new int[0];
    private int[] currU = new int[0];
    private int[] currV = new int[0];
    private int[] currBY = new int[0];
    private int[] currBUV = new int[0];
    private int[] currBUVTmp = new int[0];
    private int[] prevY = new int[0];
    private int[] prevU = new int[0];
    private int[] prevV = new int[0];
    private int[] prevBY = new int[0];
    private int[] prevBUV = new int[0];
    private int[] nextY = new int[0];
    private int[] nextU = new int[0];
    private int[] nextV = new int[0];
    private int[] nextBY = new int[0];
    private int[] nextBUV = new int[0];
    private int[] outImage = new int[0];
    private int[] outMask = new int[0];
    private int[] overlap = new int[0];
    private int[] flagBlk = new int[0];
    private int[] maskBlk = new int[0];
    private int[] horiFlag = new int[0];
    private int[] vertFlag = new int[0];
    private double[] currBlk = new double[0];
    private double[] horiBlk = new double[0];
    private double[] vertBlk = new double[0];
    private boolean masked;

    /**
     * {@return the width of the composed picture}
     */
    int width() {
        return width;
    }

    /**
     * {@return the height of the composed picture}
     */
    int height() {
        return height;
    }

    /**
     * {@return the composed luminance, {@code width * height} samples}
     */
    int[] y() {
        return currY;
    }

    /**
     * {@return the composed Cb samples, {@code (width / 2) * (height / 2)} of them}
     */
    int[] u() {
        return currU;
    }

    /**
     * {@return the composed Cr samples}
     */
    int[] v() {
        return currV;
    }

    /**
     * {@return the composed luminance shape (0 or 255 per sample)}
     */
    int[] shape() {
        return currBY;
    }

    /**
     * {@return the composed chrominance shape}
     */
    int[] chromaShape() {
        return currBUV;
    }

    /**
     * {@return whether the samples outside the composed shape are blanked in the picture handed out
     * ({@code write420_jnt_withMask})}
     */
    boolean masked() {
        return masked;
    }

    private void size(int width, int height) {
        this.width = width;
        this.height = height;
        var luma = width * height;
        var chroma = (width / 2) * (height / 2);
        if (currY.length != luma) {
            currY = new int[luma];
            currBY = new int[luma];
            prevY = new int[luma];
            prevBY = new int[luma];
            nextY = new int[luma];
            nextBY = new int[luma];
            outImage = new int[luma];
            outMask = new int[luma];
            overlap = new int[luma];
            flagBlk = new int[luma];
            maskBlk = new int[luma];
            horiFlag = new int[luma];
            vertFlag = new int[luma];
            currBlk = new double[luma];
            horiBlk = new double[luma];
            vertBlk = new double[luma];
        }
        if (currU.length != chroma) {
            currU = new int[chroma];
            currV = new int[chroma];
            currBUV = new int[chroma];
            currBUVTmp = new int[chroma];
            prevU = new int[chroma];
            prevV = new int[chroma];
            prevBUV = new int[chroma];
            nextU = new int[chroma];
            nextV = new int[chroma];
            nextBUV = new int[chroma];
        }
    }

    /**
     * Composes the picture of an enhancement layer VOP of temporal scalability that was just decoded
     * ({@code BackgroundComposition}).
     *
     * @param enhancement the decoder of the enhancement layer
     * @param width the width of the display
     * @param height the height of the display
     * @throws CorruptStream if the base layer VOPs the composition takes are missing
     */
    void composeTemporal(ObjectDecoder enhancement, int width, int height) {
        size(width, height);
        masked = false;
        var current = enhancement.t;
        var prevBuffer = !enhancement.buffB1.empty() ? enhancement.buffB1 : enhancement.buffP1;
        var nextBuffer = !enhancement.buffB2.empty() ? enhancement.buffB2 : enhancement.buffP2;
        if (prevBuffer.empty() || nextBuffer.empty()) {
            throw CorruptStream.INSTANCE;
        }
        var recon = enhancement.reconCurr();
        convertYuv(recon, currY, currU, currV);
        if (recon.by != null) {
            convertSeg(recon, currBY, currBUV, enhancement.rctCurrVOPY);
        } else {
            Arrays.fill(currBY, 0);
            Arrays.fill(currBUV, 0);
        }
        var forward = enhancement.shapeDecoders()[1];
        var backward = enhancement.shapeDecoders()[0];
        convertYuv(prevBuffer.vop, prevY, prevU, prevV);
        convertSeg(forward.reconCurr(), prevBY, prevBUV, forward.rctCurrVOPY);
        convertYuv(nextBuffer.vop, nextY, nextU, nextV);
        convertSeg(backward.reconCurr(), nextBY, nextBUV, backward.rctCurrVOPY);
        var core = !enhancement.vopmd.bgComposition;
        var prevTime = prevBuffer.time;
        var nextTime = nextBuffer.time;
        var chromaWidth = width / 2;
        var chromaHeight = height / 2;
        compose(currY, prevY, nextY, currBY, prevBY, nextBY, current, prevTime, nextTime, width, height, core);
        compose(currU, prevU, nextU, currBUV, prevBUV, nextBUV, current, prevTime, nextTime, chromaWidth,
                chromaHeight, core);
        compose(currV, prevV, nextV, currBUV, prevBUV, nextBUV, current, prevTime, nextTime, chromaWidth,
                chromaHeight, core);
    }

    /**
     * Composes the picture of an enhancement layer VOP of spatial scalability with background composition that was
     * just decoded ({@code BackgroundCompositionSS}): over the upsampled reference layer VOP when the enhancement
     * layer takes its shape from the reference layer, over its own previous VOP otherwise.
     *
     * @implNote This implementation composes the shape as the reference decoder does: over a rectangular reference
     * layer the samples outside the enhanced VOP are opaque, over an arbitrarily shaped one they are transparent,
     * because the reference decoder reads the upsampled reference layer's shape inside that VOP's bounding rectangle,
     * which it leaves empty.
     *
     * @param enhancement the decoder of the enhancement layer
     * @param reference the reference layer VOP the enhancement VOP was decoded with
     * @param width the width of the enhancement layer's display
     * @param height the height of the enhancement layer's display
     */
    void composeSpatial(ObjectDecoder enhancement, VopPlanes reference, int width, int height) {
        size(width, height);
        var volmd = enhancement.volMode();
        var referenceShape = volmd.enhnType == 1 && volmd.useRefShape == 1;
        masked = !referenceShape;
        var recon = enhancement.reconCurr();
        convertYuv(recon, currY, currU, currV);
        Arrays.fill(currBUV, 0);
        if (recon.by != null) {
            convertSeg(recon, currBY, currBUV, enhancement.rctCurrVOPY);
        } else {
            Arrays.fill(currBY, OPAQUE);
        }
        if (referenceShape) {
            var upsampled = enhancement.upsampleReference(reference);
            convertYuv(upsampled, prevY, prevU, prevV);
            Arrays.fill(prevBY, upsampled.alphaUsage != VopPlanes.RECTANGLE ? 0 : OPAQUE);
            Arrays.fill(prevBUV, 0);
        } else {
            var previous = enhancement.vopmd.predType != VopMode.BVOP ? enhancement.refQ0 : enhancement.currQ;
            convertYuv(previous, prevY, prevU, prevV);
            convertSeg(previous, prevBY, prevBUV, previous.boundY);
        }
        System.arraycopy(currBUV, 0, currBUVTmp, 0, currBUV.length);
        var chromaWidth = width / 2;
        var chromaHeight = height / 2;
        var rectY = enhancement.rctCurrVOPY;
        var rectUV = enhancement.rctCurrVOPUV;
        composeRect(currY, prevY, currBY, prevBY, width, height, rectY, referenceShape);
        composeRect(currU, prevU, currBUVTmp, prevBUV, chromaWidth, chromaHeight, rectUV, referenceShape);
        composeRect(currV, prevV, currBUV, prevBUV, chromaWidth, chromaHeight, rectUV, referenceShape);
    }

    /**
     * Copies the displayed samples of a VOP ({@code convertYuv}).
     */
    private void convertYuv(VopPlanes vop, int[] y, int[] u, int[] v) {
        for (var row = 0; row < height; row++) {
            System.arraycopy(vop.y.pixels, vop.y.offset(0, row), y, row * width, width);
        }
        var chromaWidth = width / 2;
        for (var row = 0; row < height / 2; row++) {
            System.arraycopy(vop.u.pixels, vop.u.offset(0, row), u, row * chromaWidth, chromaWidth);
            System.arraycopy(vop.v.pixels, vop.v.offset(0, row), v, row * chromaWidth, chromaWidth);
        }
    }

    /**
     * Copies the displayed binary shape of a VOP inside a rectangle, clearing it outside the rectangle in both the
     * copy and the VOP, and derives the chrominance shape: a chrominance sample is inside when any of its four
     * luminance samples is, and takes the value of the last nonzero shape sample ({@code convertSeg}).
     */
    private void convertSeg(VopPlanes vop, int[] by, int[] buv, Rct rect) {
        var plane = vop.by;
        var color = 0;
        for (var y = 0; y < height; y++) {
            var src = plane.offset(0, y);
            for (var x = 0; x < width; x++) {
                int value;
                if (rect.left <= x && x < rect.right && rect.top <= y && y < rect.bottom) {
                    value = plane.pixels[src + x];
                } else {
                    plane.pixels[src + x] = 0;
                    value = 0;
                }
                by[y * width + x] = value;
                if (value > 0) {
                    color = value;
                }
            }
        }
        var chromaWidth = width / 2;
        for (var y = 0; y < height / 2; y++) {
            for (var x = 0; x < chromaWidth; x++) {
                var top = 2 * y * width + 2 * x;
                var sum = by[top] + by[top + width] + by[top + 1] + by[top + width + 1];
                buv[y * chromaWidth + x] = sum > 0 ? color : 0;
            }
        }
    }

    /**
     * Composes one plane of temporal scalability ({@code bg_comp_each} of the previous and next frames).
     */
    private void compose(int[] curr, int[] prev, int[] next, int[] maskCurr, int[] maskPrev, int[] maskNext,
                         int currTime, int prevTime, int nextTime, int width, int height, boolean core) {
        var n = width * height;
        if (core) {
            for (var i = 0; i < n; i++) {
                if (maskCurr[i] == 0) {
                    curr[i] = prev[i];
                }
            }
            return;
        }
        var toPrev = Math.abs(currTime - prevTime);
        var toNext = Math.abs(currTime - nextTime);
        var nearest = toPrev > toNext && Math.abs(toPrev - toNext) < 1 ? next : prev;
        System.arraycopy(nearest, 0, outImage, 0, n);
        for (var i = 0; i < n; i++) {
            if (maskPrev[i] > 0 && maskNext[i] == 0) {
                outImage[i] = next[i];
            } else if (maskPrev[i] == 0 && maskNext[i] > 0) {
                outImage[i] = prev[i];
            }
            overlap[i] = maskPrev[i] > 0 && maskNext[i] > 0 ? 0 : 1;
        }
        prePad(overlap, outImage, width, height);
        for (var i = 0; i < n; i++) {
            if (maskCurr[i] == 0) {
                curr[i] = outImage[i];
            }
        }
    }

    /**
     * Composes one plane of spatial scalability: inside the rectangle of the enhanced VOP (and, over the reference
     * layer's shape, inside its shape) the enhanced samples and shape, outside the reference ones
     * ({@code bg_comp_each} of spatial scalability and {@code bg_comp_each_mode3}).
     */
    private void composeRect(int[] curr, int[] prev, int[] maskCurr, int[] maskPrev, int width, int height, Rct rect,
                             boolean shapeInside) {
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var i = y * width + x;
                var inside = x >= rect.left && x < rect.right && y >= rect.top && y < rect.bottom
                        && (!shapeInside || maskCurr[i] != 0);
                if (!inside) {
                    curr[i] = prev[i];
                    maskCurr[i] = maskPrev[i];
                }
            }
        }
    }

    /**
     * Fills the samples outside a mask from the samples inside it, averaging the repetition of the nearest inside
     * samples to the left and right and above and below, repeated until every sample is filled ({@code pre_pad}).
     */
    private void prePad(int[] mask, int[] curr, int width, int height) {
        var n = width * height;
        var flagCount = 0;
        for (var i = 0; i < n; i++) {
            maskBlk[i] = mask[i] != 0 ? 1 : 0;
            double value = maskBlk[i] != 0 ? curr[i] : 0;
            horiBlk[i] = value;
            vertBlk[i] = value;
            currBlk[i] = value;
        }
        for (var i = 0; i < n; i++) {
            flagBlk[i] = maskBlk[i];
            flagCount += flagBlk[i];
        }
        if (flagCount == 0) {
            return;
        }
        var padValue = 0.0;
        while (flagCount != 0) {
            System.arraycopy(flagBlk, 0, horiFlag, 0, n);
            System.arraycopy(flagBlk, 0, vertFlag, 0, n);
            System.arraycopy(flagBlk, 0, maskBlk, 0, n);
            System.arraycopy(currBlk, 0, horiBlk, 0, n);
            System.arraycopy(currBlk, 0, vertBlk, 0, n);
            for (var j = 0; j < height; j++) {
                var row = j * width;
                int ic;
                for (ic = 1; ic < width; ic++) {
                    if (maskBlk[row + ic - 1] - maskBlk[row + ic] == 1) {
                        break;
                    }
                }
                for (var i = ic; i < width; i++) {
                    if (maskBlk[row + i - 1] - maskBlk[row + i] == 1) {
                        padValue = horiBlk[row + i - 1];
                    }
                    if (maskBlk[row + i] == 0) {
                        horiBlk[row + i] += padValue;
                        horiFlag[row + i]++;
                    }
                }
                for (ic = width - 2; ic >= 0; ic--) {
                    if (maskBlk[row + ic + 1] - maskBlk[row + ic] == 1) {
                        break;
                    }
                }
                for (var i = ic; i >= 0; i--) {
                    if (maskBlk[row + i + 1] - maskBlk[row + i] == 1) {
                        padValue = horiBlk[row + i + 1];
                    }
                    if (maskBlk[row + i] == 0) {
                        horiBlk[row + i] += padValue;
                        horiFlag[row + i]++;
                    }
                }
            }
            for (var i = 0; i < n; i++) {
                if (horiFlag[i] != 0) {
                    horiBlk[i] = (int) (horiBlk[i] / horiFlag[i]);
                    horiFlag[i] /= horiFlag[i];
                }
            }
            for (var i = 0; i < width; i++) {
                int jc;
                for (jc = 1; jc < height; jc++) {
                    if (maskBlk[(jc - 1) * width + i] - maskBlk[jc * width + i] == 1) {
                        break;
                    }
                }
                for (var j = jc; j < height; j++) {
                    if (maskBlk[(j - 1) * width + i] - maskBlk[j * width + i] == 1) {
                        padValue = vertBlk[(j - 1) * width + i];
                    }
                    if (maskBlk[j * width + i] == 0) {
                        vertBlk[j * width + i] += padValue;
                        vertFlag[j * width + i]++;
                    }
                }
                for (jc = height - 2; jc >= 0; jc--) {
                    if (maskBlk[(jc + 1) * width + i] - maskBlk[jc * width + i] == 1) {
                        break;
                    }
                }
                for (var j = jc; j >= 0; j--) {
                    if (maskBlk[(j + 1) * width + i] - maskBlk[j * width + i] == 1) {
                        padValue = vertBlk[(j + 1) * width + i];
                    }
                    if (maskBlk[j * width + i] == 0) {
                        vertBlk[j * width + i] += padValue;
                        vertFlag[j * width + i]++;
                    }
                }
            }
            for (var i = 0; i < n; i++) {
                if (vertFlag[i] != 0) {
                    vertBlk[i] = (int) (vertBlk[i] / vertFlag[i]);
                    vertFlag[i] /= vertFlag[i];
                }
            }
            for (var i = 0; i < n; i++) {
                if (horiFlag[i] == 1 && vertFlag[i] == 1) {
                    currBlk[i] = (int) ((horiBlk[i] + vertBlk[i]) / 2.0);
                } else if (horiFlag[i] == 1) {
                    currBlk[i] = horiBlk[i];
                } else if (vertFlag[i] == 1) {
                    currBlk[i] = vertBlk[i];
                }
            }
            flagCount = n;
            for (var i = 0; i < n; i++) {
                if (horiFlag[i] == 1 || vertFlag[i] == 1) {
                    flagBlk[i] = 1;
                    flagCount--;
                }
            }
        }
        for (var i = 0; i < n; i++) {
            curr[i] = (int) currBlk[i];
        }
    }
}
