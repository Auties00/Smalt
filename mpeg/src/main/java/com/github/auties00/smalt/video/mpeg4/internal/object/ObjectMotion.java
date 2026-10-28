/* Ports the Microsoft MPEG-4 Visual reference software of ISO/IEC 14496-5:2001 (version 2 FDAM1-2.3-001213,
 * sys/mc.cpp). Copyright 1996-2000 Microsoft Corporation, General Instrument Corp., RWTH Aachen, Hitachi, Ltd.,
 * Fujitsu Laboratories Ltd. and the contributors named in the sources; ISO/IEC free license for products conforming
 * to ISO/IEC 14496-2. Modified for Smalt. */
package com.github.auties00.smalt.video.mpeg4.internal.object;

/**
 * Motion compensation (7.6): half and quarter sample prediction of blocks and macroblocks, field prediction,
 * interlaced and progressive direct mode, overlapped block motion compensation, the binary shape prediction, and the
 * copies of not-coded macroblocks. A reference plane is addressed from the top-left of the reference frame; a
 * prediction buffer has the macroblock width as its stride.
 */
abstract class ObjectMotion extends ObjectGmc {
    private static final int[] BLK_OFFSET_X = {0, BLOCK_SIZE, 0, BLOCK_SIZE};
    private static final int[] BLK_OFFSET_Y = {0, 0, BLOCK_SIZE, BLOCK_SIZE};
    private static final int[] BLK_OFFSET_PIXEL = {0, OFFSET_BLK1, OFFSET_BLK2, OFFSET_BLK3};
    private static final int[] TR_OFFSET_TOP = {0, 0, 1, 1, 0, 0, -1, -1};
    private static final int[] TR_OFFSET_BOT = {-1, 0, -1, 0, 1, 0, 1, 0};
    private static final int[] DIRECT_BLK_X = {0, 2 * BLOCK_SIZE, 0, 2 * BLOCK_SIZE};
    private static final int[] DIRECT_BLK_Y = {0, 0, 2 * BLOCK_SIZE, 2 * BLOCK_SIZE};
    private static final int[] DIRECT_MB_OFFSET = {0, BLOCK_SIZE, MB_SIZE * BLOCK_SIZE, MB_SIZE * BLOCK_SIZE
            + BLOCK_SIZE};

    final int[] limitXY = new int[2];
    private final int[] qpelBlock = new int[MB_SIZE * MB_SIZE];
    private final int[] qpelC = new int[BLOCK_SQUARE_SIZE];
    private final int[] qpelT = new int[BLOCK_SQUARE_SIZE];
    private final int[] qpelB = new int[BLOCK_SQUARE_SIZE];
    private final int[] qpelR = new int[BLOCK_SQUARE_SIZE];
    private final int[] qpelL = new int[BLOCK_SQUARE_SIZE];
    private final int[] overlapPred = new int[64];
    private final int[] overlapPredX2 = new int[256];
    private final int[] rrvBlock = new int[256];
    private static final MotionVector[] ZERO_VECTORS = MotionVector.array(PVOP_MV_PER_REF_PER_MB);

    final void limitFullPel(int x, int y, Rct rect, int blkSize) {
        if (rect != null) {
            if (x < rect.left) {
                x = rect.left;
            } else if (x > rect.right - blkSize) {
                x = rect.right - blkSize;
            }
            if (y < rect.top) {
                y = rect.top;
            } else if (y > rect.bottom - blkSize) {
                y = rect.bottom - blkSize;
            }
        }
        limitXY[0] = x;
        limitXY[1] = y;
    }

    final void limitHalfPel(int x, int y, Rct rect, int blkSize) {
        if (rect != null) {
            if (x < rect.left * 2) {
                x = rect.left * 2;
            } else if (x > (rect.right - blkSize) * 2) {
                x = (rect.right - blkSize) * 2;
            }
            if (y < rect.top * 2) {
                y = rect.top * 2;
            } else if (y > (rect.bottom - blkSize) * 2) {
                y = (rect.bottom - blkSize) * 2;
            }
        }
        limitXY[0] = x;
        limitXY[1] = y;
    }

    final void limitQuarterPel(int x, int y, Rct rect, int blkSize) {
        if (rect != null) {
            var sx = blkSize == 0 ? MB_SIZE : blkSize;
            var sy = blkSize == 0 ? MB_SIZE : blkSize;
            if (x < (rect.left + EXPANDY_REFVOP - sx) * 4) {
                x = (rect.left + EXPANDY_REFVOP - sx) * 4;
            } else if (x > (rect.right - EXPANDY_REFVOP) * 4) {
                x = (rect.right - EXPANDY_REFVOP) * 4;
            }
            if (y < (rect.top + EXPANDY_REFVOP - sy) * 4) {
                y = (rect.top + EXPANDY_REFVOP - sy) * 4;
            } else if (y > (rect.bottom - EXPANDY_REFVOP) * 4) {
                y = (rect.bottom - EXPANDY_REFVOP) * 4;
            }
        }
        limitXY[0] = x;
        limitXY[1] = y;
    }

    /**
     * Predicts the luminance (or an alpha plane) of a macroblock ({@code motionCompMB}).
     *
     * @param pred the prediction buffer, 16 (32 with reduced resolution) samples wide
     * @param ref the reference plane
     * @param mvs the motion vectors
     * @param mvOff the index of the macroblock's vectors
     * @param mbIndex the index of the macroblock's mode
     */
    final void motionCompMB(int[] pred, int[] ref, MotionVector[] mvs, int mvOff, int mbIndex, int mbX, int mbY,
                            int x, int y, boolean skipNonOBMC, boolean alphaMB, Rct limit) {
        var mode = mbmd[mbIndex];
        if (!alphaMB && !volmd.advPredDisable && !mode.fieldMV && !mode.mcsel) {
            motionCompOverLap(pred, ref, mvs, mvOff, mbIndex, mbX, mbY, x, y, limit);
            return;
        }
        if (skipNonOBMC && !mode.mcsel) {
            return;
        }
        if (!mode.has4MVForward && !mode.fieldMV && !mode.mcsel) {
            var mv = mvs[mvOff];
            if (volmd.quarterSample) {
                motionCompQuarterSample(pred, 0, ref, 0, MB_SIZE, x * 4 + mv.trueX, y * 4 + mv.trueY,
                        vopmd.roundingControl, limit);
            } else if (vopmd.rrvOnOff == 1) {
                motionComp(pred, 0, ref, 0, MB_SIZE * 2, x * 2 + mv.trueX2, y * 2 + mv.trueY2, vopmd.roundingControl,
                        limit);
            } else {
                motionComp(pred, 0, ref, 0, MB_SIZE, x * 2 + mv.trueX, y * 2 + mv.trueY, vopmd.roundingControl,
                        limit);
            }
        } else if (mode.fieldMV) {
            var top = mvOff + 5 + (mode.forwardTop ? 1 : 0);
            var topRef = mode.forwardTop ? frameWidthY : 0;
            fieldPrediction(pred, 0, ref, topRef, mvs[top], x, y, limit);
            var bottom = mvOff + 7 + (mode.forwardBottom ? 1 : 0);
            var bottomRef = mode.forwardBottom ? frameWidthY : 0;
            fieldPrediction(pred, MB_SIZE, ref, bottomRef, mvs[bottom], x, y, limit);
        } else if (mode.mcsel) {
            findGlobalPredForGMC(x, y, pred, 0, ref);
        } else if (vopmd.rrvOnOff == 1) {
            var blkX = x + BLOCK_SIZE * 2;
            var blkY = y + BLOCK_SIZE * 2;
            var block = rrvBlock;
            var mv = mvs[mvOff + 1];
            motionComp(block, 0, ref, 0, BLOCK_SIZE * 2, x * 2 + mv.trueX2, y * 2 + mv.trueY2, vopmd.roundingControl,
                    limit);
            RrvUtil.writeCubicRct(BLOCK_SIZE * 2, MB_SIZE * 2, block, 0, pred, 0);
            mv = mvs[mvOff + 2];
            motionComp(block, 0, ref, 0, BLOCK_SIZE * 2, blkX * 2 + mv.trueX2, y * 2 + mv.trueY2,
                    vopmd.roundingControl, limit);
            RrvUtil.writeCubicRct(BLOCK_SIZE * 2, MB_SIZE * 2, block, 0, pred, BLOCK_SIZE * 2);
            mv = mvs[mvOff + 3];
            motionComp(block, 0, ref, 0, BLOCK_SIZE * 2, x * 2 + mv.trueX2, blkY * 2 + mv.trueY2,
                    vopmd.roundingControl, limit);
            RrvUtil.writeCubicRct(BLOCK_SIZE * 2, MB_SIZE * 2, block, 0, pred, MB_SIZE * MB_SIZE * 2);
            mv = mvs[mvOff + 4];
            motionComp(block, 0, ref, 0, BLOCK_SIZE * 2, blkX * 2 + mv.trueX2, blkY * 2 + mv.trueY2,
                    vopmd.roundingControl, limit);
            RrvUtil.writeCubicRct(BLOCK_SIZE * 2, MB_SIZE * 2, block, 0, pred, MB_SIZE * MB_SIZE * 2
                    + BLOCK_SIZE * 2);
        } else {
            for (var i = 0; i < 4; i++) {
                if (mode.transpStatus[Y_BLOCK1 + i] == MbMode.ALL) {
                    continue;
                }
                var mv = mvs[mvOff + 1 + i];
                var bx = x + BLK_OFFSET_X[i];
                var byy = y + BLK_OFFSET_Y[i];
                if (volmd.quarterSample) {
                    motionCompQuarterSample(pred, BLK_OFFSET_PIXEL[i], ref, 0, BLOCK_SIZE,
                            bx * 4 + 2 * mv.mvx + mv.halfX, byy * 4 + 2 * mv.mvy + mv.halfY, vopmd.roundingControl,
                            limit);
                } else {
                    motionComp(pred, BLK_OFFSET_PIXEL[i], ref, 0, BLOCK_SIZE, bx * 2 + mv.trueX, byy * 2 + mv.trueY,
                            vopmd.roundingControl, limit);
                }
            }
        }
    }

    private void fieldPrediction(int[] pred, int predOff, int[] ref, int refOff, MotionVector mv, int x, int y,
                                 Rct limit) {
        if (volmd.quarterSample) {
            motionCompQuarterSample(pred, predOff, ref, refOff, 0, x * 4 + mv.trueX, y * 4 + mv.trueY,
                    vopmd.roundingControl, limit);
        } else {
            motionCompYField(pred, predOff, ref, refOff, x * 2 + mv.trueX, y * 2 + mv.trueY, limit);
        }
    }

    /**
     * Predicts a block or macroblock at half sample accuracy ({@code motionComp}).
     *
     * @param size 8 or 16, or 32 for a macroblock of a reduced resolution VOP
     * @param xRef the horizontal position in half samples
     * @param yRef the vertical position in half samples
     */
    final void motionComp(int[] pred, int predOff, int[] ref, int refOff, int size, int xRef, int yRef,
                          int roundingControl, Rct limit) {
        limitHalfPel(xRef, yRef, limit, size);
        xRef = limitXY[0];
        yRef = limitXY[1];
        var r = refOff + ((yRef >> 1) + EXPANDY_REF_FRAME) * frameWidthY + (xRef >> 1) + EXPANDY_REF_FRAME;
        var stride = size == 8 || size == 16 ? MB_SIZE : vopmd.rrvOnOff == 1 ? size : MB_SIZE;
        var p = predOff;
        if ((yRef & 1) == 0) {
            if ((xRef & 1) == 0) {
                for (var iy = 0; iy < size; iy++) {
                    System.arraycopy(ref, r, pred, p, size);
                    r += frameWidthY;
                    p += stride;
                }
            } else {
                var round = 1 - roundingControl;
                for (var iy = 0; iy < size; iy++) {
                    for (var ix = 0; ix < size; ix++) {
                        pred[p + ix] = (ref[r + ix] + ref[r + ix + 1] + round) >> 1;
                    }
                    r += frameWidthY;
                    p += stride;
                }
            }
        } else if ((xRef & 1) == 0) {
            var round = 1 - roundingControl;
            for (var iy = 0; iy < size; iy++) {
                var bottom = r + frameWidthY;
                for (var ix = 0; ix < size; ix++) {
                    pred[p + ix] = (ref[r + ix] + ref[bottom + ix] + round) >> 1;
                }
                r = bottom;
                p += stride;
            }
        } else {
            var round = 2 - roundingControl;
            for (var iy = 0; iy < size; iy++) {
                var bottom = r + frameWidthY;
                for (var ix = 0; ix < size; ix++) {
                    pred[p + ix] = (ref[r + ix + 1] + ref[r + ix] + ref[bottom + ix + 1] + ref[bottom + ix]
                            + round) >> 2;
                }
                r = bottom;
                p += stride;
            }
        }
    }

    /**
     * Predicts a block at quarter sample accuracy ({@code motionCompQuarterSample}).
     *
     * @param size 8 or 16, or 0 for a 16x8 field block whose rows go to every other row of the prediction
     */
    final void motionCompQuarterSample(int[] pred, int predOff, int[] ref, int refOff, int size, int xRef, int yRef,
                                       int roundingControl, Rct limit) {
        limitQuarterPel(xRef, yRef, limit, size);
        xRef = limitXY[0];
        yRef = limitXY[1];
        var block = qpelBlock;
        blkInterpolateY(ref, refOff, size, xRef, yRef, block, roundingControl);
        if (size == 0) {
            var p = predOff;
            for (var iy = 0; iy < 16; iy++) {
                if ((iy & 1) == 0) {
                    System.arraycopy(block, iy * 16, pred, p, 16);
                }
                p += 16;
            }
        } else {
            var p = predOff;
            for (var iy = 0; iy < size; iy++) {
                System.arraycopy(block, iy * size, pred, p, size);
                p += MB_SIZE;
            }
        }
    }

    /**
     * Predicts one field of a macroblock at half sample accuracy ({@code motionCompYField}).
     */
    final void motionCompYField(int[] pred, int predOff, int[] ref, int refOff, int xRef, int yRef, Rct limit) {
        limitHalfPel(xRef, yRef, limit, MB_SIZE);
        xRef = limitXY[0];
        yRef = limitXY[1];
        var r = refOff + (((yRef >> 1) & ~1) + EXPANDY_REF_FRAME) * frameWidthY + (xRef >> 1) + EXPANDY_REF_FRAME;
        var round = 1 - vopmd.roundingControl;
        var fieldStep = 2 * frameWidthY;
        var p = predOff;
        if ((yRef & 2) == 0) {
            if ((xRef & 1) == 0) {
                for (var iy = 0; iy < MB_SIZE; iy += 2) {
                    System.arraycopy(ref, r, pred, p, MB_SIZE);
                    r += fieldStep;
                    p += MB_SIZE * 2;
                }
            } else {
                for (var iy = 0; iy < MB_SIZE; iy += 2) {
                    for (var ix = 0; ix < MB_SIZE; ix++) {
                        pred[p + ix] = (ref[r + ix] + ref[r + ix + 1] + round) >> 1;
                    }
                    r += fieldStep;
                    p += MB_SIZE * 2;
                }
            }
        } else if ((xRef & 1) == 0) {
            for (var iy = 0; iy < MB_SIZE; iy += 2) {
                var bottom = r + fieldStep;
                for (var ix = 0; ix < MB_SIZE; ix++) {
                    pred[p + ix] = (ref[r + ix] + ref[bottom + ix] + round) >> 1;
                }
                r = bottom;
                p += MB_SIZE * 2;
            }
        } else {
            round++;
            for (var iy = 0; iy < MB_SIZE; iy += 2) {
                var bottom = r + fieldStep;
                for (var ix = 0; ix < MB_SIZE; ix++) {
                    pred[p + ix] = (ref[r + ix + 1] + ref[r + ix] + ref[bottom + ix + 1] + ref[bottom + ix]
                            + round) >> 2;
                }
                r = bottom;
                p += MB_SIZE * 2;
            }
        }
    }

    /**
     * Predicts one field of the chrominance of a macroblock ({@code motionCompFieldUV}).
     */
    final void motionCompFieldUV(int[] predU, int[] predV, int predOff, VopPlanes ref, int x, int y, int xRefUV,
                                 int yRefUV, int refFieldSelect, Rct limit) {
        limitFullPel(x + xRefUV, y + yRefUV, limit, MB_SIZE);
        xRefUV = limitXY[0] - x;
        yRefUV = limitXY[1] - y;
        var loc = (y / 2 + ((yRefUV >> 1) & ~1) + EXPANDUV_REF_FRAME) * frameWidthUV + ((x + xRefUV) >> 1)
                + EXPANDUV_REF_FRAME;
        var u = ref.u.pixels;
        var v = ref.v.pixels;
        var r = loc + refFieldSelect * frameWidthUV;
        var round = 1 - vopmd.roundingControl;
        var fieldStep = 2 * frameWidthUV;
        var p = predOff;
        if ((yRefUV & 2) == 0) {
            if ((xRefUV & 1) == 0) {
                for (var iy = 0; iy < BLOCK_SIZE; iy += 2) {
                    System.arraycopy(u, r, predU, p, BLOCK_SIZE);
                    System.arraycopy(v, r, predV, p, BLOCK_SIZE);
                    r += fieldStep;
                    p += 2 * BLOCK_SIZE;
                }
            } else {
                for (var iy = 0; iy < BLOCK_SIZE; iy += 2) {
                    for (var ix = 0; ix < BLOCK_SIZE; ix++) {
                        predU[p + ix] = (u[r + ix + 1] + u[r + ix] + round) >> 1;
                        predV[p + ix] = (v[r + ix + 1] + v[r + ix] + round) >> 1;
                    }
                    r += fieldStep;
                    p += 2 * BLOCK_SIZE;
                }
            }
        } else if ((xRefUV & 1) == 0) {
            for (var iy = 0; iy < BLOCK_SIZE; iy += 2) {
                var bottom = r + fieldStep;
                for (var ix = 0; ix < BLOCK_SIZE; ix++) {
                    predU[p + ix] = (u[r + ix] + u[bottom + ix] + round) >> 1;
                    predV[p + ix] = (v[r + ix] + v[bottom + ix] + round) >> 1;
                }
                p += 2 * BLOCK_SIZE;
                r = bottom;
            }
        } else {
            round++;
            for (var iy = 0; iy < BLOCK_SIZE; iy += 2) {
                var bottom = r + fieldStep;
                for (var ix = 0; ix < BLOCK_SIZE; ix++) {
                    predU[p + ix] = (u[r + ix + 1] + u[r + ix] + u[bottom + ix + 1] + u[bottom + ix] + round) >> 2;
                    predV[p + ix] = (v[r + ix + 1] + v[r + ix] + v[bottom + ix + 1] + v[bottom + ix] + round) >> 2;
                }
                p += 2 * BLOCK_SIZE;
                r = bottom;
            }
        }
    }

    /**
     * Predicts the chrominance of a macroblock at half sample accuracy ({@code motionCompUV}).
     */
    final void motionCompUV(int[] predU, int[] predV, VopPlanes ref, int x, int y, int xRefUV, int yRefUV,
                            int roundingControl, Rct limit) {
        limitFullPel(x + xRefUV, y + yRefUV, limit, MB_SIZE * rrvScale);
        xRefUV = limitXY[0] - x;
        yRefUV = limitXY[1] - y;
        var loc = (((y + yRefUV) >> 1) + EXPANDUV_REF_FRAME) * frameWidthUV + ((x + xRefUV) >> 1)
                + EXPANDUV_REF_FRAME;
        var u = ref.u.pixels;
        var v = ref.v.pixels;
        var size = BLOCK_SIZE * rrvScale;
        var r = loc;
        var p = 0;
        if ((yRefUV & 1) == 0) {
            if ((xRefUV & 1) == 0) {
                for (var iy = 0; iy < size; iy++) {
                    System.arraycopy(u, r, predU, p, size);
                    System.arraycopy(v, r, predV, p, size);
                    r += frameWidthUV;
                    p += size;
                }
            } else {
                var round = 1 - roundingControl;
                for (var iy = 0; iy < size; iy++) {
                    for (var ix = 0; ix < size; ix++) {
                        predU[p + ix] = (u[r + ix + 1] + u[r + ix] + round) >> 1;
                        predV[p + ix] = (v[r + ix + 1] + v[r + ix] + round) >> 1;
                    }
                    r += frameWidthUV;
                    p += size;
                }
            }
        } else if ((xRefUV & 1) == 0) {
            var round = 1 - roundingControl;
            for (var iy = 0; iy < size; iy++) {
                var bottom = r + frameWidthUV;
                for (var ix = 0; ix < size; ix++) {
                    predU[p + ix] = (u[r + ix] + u[bottom + ix] + round) >> 1;
                    predV[p + ix] = (v[r + ix] + v[bottom + ix] + round) >> 1;
                }
                p += size;
                r = bottom;
            }
        } else {
            var round = 2 - roundingControl;
            for (var iy = 0; iy < size; iy++) {
                var bottom = r + frameWidthUV;
                for (var ix = 0; ix < size; ix++) {
                    predU[p + ix] = (u[r + ix + 1] + u[r + ix] + u[bottom + ix + 1] + u[bottom + ix] + round) >> 2;
                    predV[p + ix] = (v[r + ix + 1] + v[r + ix] + v[bottom + ix + 1] + v[bottom + ix] + round) >> 2;
                }
                p += size;
                r = bottom;
            }
        }
    }

    /**
     * Predicts the luminance of a macroblock in interlaced (field) or progressive direct mode
     * ({@code motionCompDirectMode}).
     *
     * @param mvRefs the vectors of the co-located macroblock of the backward reference
     * @param mvRefOff the index of the co-located macroblock's vectors
     * @param plane 0 for the texture, 1 for the alpha planes
     */
    final void motionCompDirectMode(int x, int y, int mbIndex, MotionVector[] mvRefs, int mvRefOff, Rct limitFwd,
                                    Rct limitBak, int plane) {
        var mode = mbmd[mbIndex];
        int mbx;
        int mby;
        if (volmd.alphaUsage != VopPlanes.RECTANGLE) {
            mbx = (x - rctCurrVOPY.left) / MB_SIZE;
            mby = (y - rctCurrVOPY.top) / MB_SIZE;
        } else {
            mbx = x / MB_SIZE;
            mby = y / MB_SIZE;
        }
        var refIndex = mbx + mby * numMBXRef;
        var modeRef = refIndex >= 0 && refIndex < mbmdRef.length ? mbmdRef[refIndex] : mbmdRef[0];
        if (modeRef.transpStatus[0] == MbMode.ALL) {
            mvRefs = ZERO_VECTORS;
            mvRefOff = 0;
        }
        if (mbx < numMBXRef && mbx >= 0 && mby < numMBYRef && mby >= 0 && modeRef.fieldMV
                && modeRef.transpStatus[0] != MbMode.ALL) {
            fieldDirect(x, y, mode, modeRef, mvRefs, mvRefOff, limitFwd, limitBak, plane);
            return;
        }
        var tempRefD = tFutureRef - tPastRef;
        var tempRefB = t - tPastRef;
        var chromaFwdX = 0;
        var chromaFwdY = 0;
        var chromaBakX = 0;
        var chromaBakY = 0;
        var divisor = 0;
        if (modeRef.dctMd == MbMode.INTRA || modeRef.dctMd == MbMode.INTRAQ) {
            mvRefs = ZERO_VECTORS;
            mvRefOff = 0;
        }
        int xRefUVF;
        int yRefUVF;
        int xRefUVB;
        int yRefUVB;
        var dx = mode.directDeltaX;
        var dy = mode.directDeltaY;
        if (mbx < numMBXRef && mbx >= 0 && mby < numMBYRef && mby >= 0) {
            if (modeRef.has4MVForward || volmd.quarterSample) {
                for (var blk = 0; blk < 4; blk++) {
                    if (mode.transpStatus[blk + 1] == MbMode.ALL) {
                        continue;
                    }
                    var ref = mvRefs[mvRefOff + blk + 1];
                    var fwdX = (ref.trueX * tempRefB) / tempRefD + dx;
                    var fwdY = (ref.trueY * tempRefB) / tempRefD + dy;
                    var bakX = dx != 0 ? fwdX - ref.trueX : (ref.trueX * (tempRefB - tempRefD)) / tempRefD;
                    var bakY = dy != 0 ? fwdY - ref.trueY : (ref.trueY * (tempRefB - tempRefD)) / tempRefD;
                    directBlock(blk, x, y, fwdX, fwdY, bakX, bakY, limitFwd, limitBak);
                    if (volmd.quarterSample) {
                        chromaFwdX += fwdX / 2;
                        chromaFwdY += fwdY / 2;
                        chromaBakX += bakX / 2;
                        chromaBakY += bakY / 2;
                    } else {
                        chromaFwdX += fwdX;
                        chromaFwdY += fwdY;
                        chromaBakX += bakX;
                        chromaBakY += bakY;
                    }
                    divisor += 4;
                }
                var round = mvRound(divisor);
                xRefUVF = roundChroma(chromaFwdX, round, divisor);
                yRefUVF = roundChroma(chromaFwdY, round, divisor);
                xRefUVB = roundChroma(chromaBakX, round, divisor);
                yRefUVB = roundChroma(chromaBakY, round, divisor);
            } else {
                var ref = mvRefs[mvRefOff];
                var fwdX = (ref.trueX * tempRefB) / tempRefD + dx;
                var fwdY = (ref.trueY * tempRefB) / tempRefD + dy;
                var bakX = dx != 0 ? fwdX - ref.trueX : (ref.trueX * (tempRefB - tempRefD)) / tempRefD;
                var bakY = dy != 0 ? fwdY - ref.trueY : (ref.trueY * (tempRefB - tempRefD)) / tempRefD;
                directMacroblock(x, y, fwdX, fwdY, bakX, bakY, limitFwd, limitBak);
                xRefUVF = chroma4(volmd.quarterSample ? fwdX / 2 : fwdX);
                yRefUVF = chroma4(volmd.quarterSample ? fwdY / 2 : fwdY);
                xRefUVB = chroma4(volmd.quarterSample ? bakX / 2 : bakX);
                yRefUVB = chroma4(volmd.quarterSample ? bakY / 2 : bakY);
            }
        } else {
            var fwdX = dx;
            var fwdY = dy;
            var bakX = dx != 0 ? fwdX : 0;
            var bakY = dy != 0 ? fwdY : 0;
            directMacroblock(x, y, fwdX, fwdY, bakX, bakY, limitFwd, limitBak);
            xRefUVF = chroma4(volmd.quarterSample ? fwdX / 2 : fwdX);
            yRefUVF = chroma4(volmd.quarterSample ? fwdY / 2 : fwdY);
            xRefUVB = chroma4(volmd.quarterSample ? bakX / 2 : bakX);
            yRefUVB = chroma4(volmd.quarterSample ? bakY / 2 : bakY);
        }
        motionCompUV(predMBU, predMBV, refQ0, x, y, xRefUVF, yRefUVF, vopmd.roundingControl, limitFwd);
        motionCompUV(predMBBackU, predMBBackV, refQ1, x, y, xRefUVB, yRefUVB, vopmd.roundingControl, limitBak);
    }

    private void directBlock(int blk, int x, int y, int fwdX, int fwdY, int bakX, int bakY, Rct limitFwd,
                             Rct limitBak) {
        if (volmd.quarterSample) {
            motionCompQuarterSample(predMBY, DIRECT_MB_OFFSET[blk], refQ0.y.pixels, 0, BLOCK_SIZE,
                    x * 4 + DIRECT_BLK_X[blk] * 2 + fwdX, y * 4 + DIRECT_BLK_Y[blk] * 2 + fwdY, vopmd.roundingControl,
                    limitFwd);
            motionCompQuarterSample(predMBBackY, DIRECT_MB_OFFSET[blk], refQ1.y.pixels, 0, BLOCK_SIZE,
                    x * 4 + DIRECT_BLK_X[blk] * 2 + bakX, y * 4 + DIRECT_BLK_Y[blk] * 2 + bakY, vopmd.roundingControl,
                    limitBak);
        } else {
            motionComp(predMBY, DIRECT_MB_OFFSET[blk], refQ0.y.pixels, 0, BLOCK_SIZE,
                    x * 2 + DIRECT_BLK_X[blk] + fwdX, y * 2 + DIRECT_BLK_Y[blk] + fwdY, vopmd.roundingControl,
                    limitFwd);
            motionComp(predMBBackY, DIRECT_MB_OFFSET[blk], refQ1.y.pixels, 0, BLOCK_SIZE,
                    x * 2 + DIRECT_BLK_X[blk] + bakX, y * 2 + DIRECT_BLK_Y[blk] + bakY, vopmd.roundingControl,
                    limitBak);
        }
    }

    private void directMacroblock(int x, int y, int fwdX, int fwdY, int bakX, int bakY, Rct limitFwd,
                                  Rct limitBak) {
        if (volmd.quarterSample) {
            for (var blk = 0; blk < 4; blk++) {
                directBlock(blk, x, y, fwdX, fwdY, bakX, bakY, limitFwd, limitBak);
            }
        } else {
            motionComp(predMBY, 0, refQ0.y.pixels, 0, MB_SIZE, x * 2 + fwdX, y * 2 + fwdY, vopmd.roundingControl,
                    limitFwd);
            motionComp(predMBBackY, 0, refQ1.y.pixels, 0, MB_SIZE, x * 2 + bakX, y * 2 + bakY, vopmd.roundingControl,
                    limitBak);
        }
    }

    private void fieldDirect(int x, int y, MbMode mode, MbMode modeRef, MotionVector[] mvRefs, int mvRefOff,
                             Rct limitFwd, Rct limitBak, int plane) {
        var code = (vopmd.topFieldFirst ? 1 : 0) << 2;
        var topRefFldOffset = 0;
        var botRefFldOffset = 0;
        mode.fieldMV = true;
        MotionVector refTop;
        MotionVector refBot;
        if (modeRef.forwardTop) {
            code |= 2;
            topRefFldOffset = 1;
            refTop = mvRefs[mvRefOff + 6];
        } else {
            refTop = mvRefs[mvRefOff + 5];
        }
        if (modeRef.forwardBottom) {
            code |= 1;
            botRefFldOffset = 1;
            refBot = mvRefs[mvRefOff + 8];
        } else {
            refBot = mvRefs[mvRefOff + 7];
        }
        var tempRefDTop = 2 * (tFutureRef - tPastRef) + TR_OFFSET_TOP[code];
        var tempRefDBot = 2 * (tFutureRef - tPastRef) + TR_OFFSET_BOT[code];
        var tempRefBTop = 2 * (t - tPastRef) + TR_OFFSET_TOP[code];
        var tempRefBBot = 2 * (t - tPastRef) + TR_OFFSET_BOT[code];
        if (tempRefDTop <= 0 || tempRefDBot <= 0 || tempRefBTop <= 0 || tempRefBBot <= 0) {
            throw CorruptStream.INSTANCE;
        }
        var dx = mode.directDeltaX;
        var dy = mode.directDeltaY;
        var xFwdTop = (refTop.trueX * tempRefBTop) / tempRefDTop + dx;
        var yFwdTop = (refTop.trueY * tempRefBTop) / tempRefDTop + dy;
        var xBakTop = dx != 0 ? xFwdTop - refTop.trueX : (refTop.trueX * (tempRefBTop - tempRefDTop)) / tempRefDTop;
        var yBakTop = dy != 0 ? yFwdTop - refTop.trueY : (refTop.trueY * (tempRefBTop - tempRefDTop)) / tempRefDTop;
        var xFwdBot = (refBot.trueX * tempRefBBot) / tempRefDBot + dx;
        var yFwdBot = (refBot.trueY * tempRefBBot) / tempRefDBot + dy;
        var xBakBot = dx != 0 ? xFwdBot - refBot.trueX : (refBot.trueX * (tempRefBBot - tempRefDBot)) / tempRefDBot;
        var yBakBot = dy != 0 ? yFwdBot - refBot.trueY : (refBot.trueY * (tempRefBBot - tempRefDBot)) / tempRefDBot;
        var qpel = volmd.quarterSample;
        var xFTQSUV = xFwdTop / 2;
        var yFTQSUV = yFwdTop / 2;
        var xBTQSUV = xBakTop / 2;
        var yBTQSUV = yBakTop / 2;
        var xFBQSUV = xFwdBot / 2;
        var yFBQSUV = yFwdBot / 2;
        var xBBQSUV = xBakBot / 2;
        var yBBQSUV = yBakBot / 2;
        if (qpel) {
            yFwdTop &= ~1;
            yBakTop &= ~1;
            yFwdBot &= ~1;
            yBakBot &= ~1;
        }
        var widthY = frameWidthY;
        fieldDirectOne(plane, qpel, refQ0, topRefFldOffset * widthY, predMBY, predMBA, 0, x, y, xFwdTop, yFwdTop,
                qpel ? xFTQSUV : xFwdTop, qpel ? yFTQSUV : yFwdTop, predMBU, predMBV, 0, topRefFldOffset, limitFwd);
        fieldDirectOne(plane, qpel, refQ1, 0, predMBBackY, predMBBackA, 0, x, y, xBakTop, yBakTop,
                qpel ? xBTQSUV : xBakTop, qpel ? yBTQSUV : yBakTop, predMBBackU, predMBBackV, 0, 0, limitBak);
        fieldDirectOne(plane, qpel, refQ0, botRefFldOffset * widthY, predMBY, predMBA, MB_SIZE, x, y, xFwdBot,
                yFwdBot, qpel ? xFBQSUV : xFwdBot, qpel ? yFBQSUV : yFwdBot, predMBU, predMBV, BLOCK_SIZE,
                botRefFldOffset, limitFwd);
        fieldDirectOne(plane, qpel, refQ1, widthY, predMBBackY, predMBBackA, MB_SIZE, x, y, xBakBot, yBakBot,
                qpel ? xBBQSUV : xBakBot, qpel ? yBBQSUV : yBakBot, predMBBackU, predMBBackV, BLOCK_SIZE, 1,
                limitBak);
    }

    /**
     * Predicts one field of one direction of an interlaced direct mode macroblock.
     */
    private void fieldDirectOne(int plane, boolean qpel, VopPlanes ref, int refOff, int[] predY, int[][] predA,
                                int predOff, int x, int y, int mvx, int mvy, int uvx, int uvy, int[] predU,
                                int[] predV, int predUVOff, int fieldSelect, Rct limit) {
        if (plane == 0) {
            if (qpel) {
                motionCompQuarterSample(predY, predOff, ref.y.pixels, refOff, 0, mvx + 4 * x, mvy + 4 * y,
                        vopmd.roundingControl, limit);
            } else {
                motionCompYField(predY, predOff, ref.y.pixels, refOff, mvx + 2 * x, mvy + 2 * y, limit);
            }
            motionCompFieldUV(predU, predV, predUVOff, ref, x, y, (uvx & 3) != 0 ? ((uvx >> 1) | 1) : (uvx >> 1),
                    (uvy & 6) != 0 ? ((uvy >> 1) | 2) : (uvy >> 1), fieldSelect, limit);
        } else {
            for (var i = 0; i < volmd.auxCompCount; i++) {
                if (qpel) {
                    motionCompQuarterSample(predA[i], predOff, ref.a[i].pixels, refOff, 0, mvx + 4 * x, mvy + 4 * y,
                            vopmd.roundingControl, limit);
                } else {
                    motionCompYField(predA[i], predOff, ref.a[i].pixels, refOff, mvx + 2 * x, mvy + 2 * y, limit);
                }
            }
        }
    }

    static int[] mvRound(int divisor) {
        return switch (divisor) {
            case 4 -> RefTables.MV_ROUND4;
            case 8 -> RefTables.MV_ROUND8;
            case 12 -> RefTables.MV_ROUND12;
            default -> RefTables.MV_ROUND16;
        };
    }

    static int roundChroma(int value, int[] round, int divisor) {
        if (divisor == 0) {
            throw CorruptStream.INSTANCE;
        }
        var abs = Math.abs(value);
        var magnitude = round[abs % divisor] + (abs / divisor) * 2;
        return value > 0 ? magnitude : -magnitude;
    }

    static int chroma4(int value) {
        return roundChroma(value, RefTables.MV_ROUND4, 4);
    }

    /**
     * Predicts one direction of a B-VOP macroblock with frame or field vectors ({@code motionCompOneBVOPReference}).
     *
     * @param backward whether the prediction is the backward one
     */
    final void motionCompOneBVOPReference(VopPlanes pred, boolean backward, int x, int y, MbMode mode,
                                          MotionVector[] mvs, int mvOff, Rct limit) {
        VopPlanes ref;
        int topRef;
        int botRef;
        if (backward) {
            ref = refQ1;
            topRef = mode.backwardTop ? 1 : 0;
            botRef = mode.backwardBottom ? 1 : 0;
        } else {
            ref = refQ0;
            topRef = mode.forwardTop ? 1 : 0;
            botRef = mode.forwardBottom ? 1 : 0;
        }
        var predY = pred.y.pixels;
        var predU = pred.u.pixels;
        var predV = pred.v.pixels;
        if (mode.fieldMV) {
            var top = mvs[mvOff + 1 + topRef];
            var bot = mvs[mvOff + 3 + botRef];
            int mvx;
            int mvy;
            if (volmd.quarterSample) {
                motionCompQuarterSample(predY, 0, ref.y.pixels, topRef * frameWidthY, 0, 4 * x + top.trueX,
                        4 * y + top.trueY, vopmd.roundingControl, limit);
                mvx = 2 * x + top.mvx;
                mvy = 2 * y + top.mvy;
            } else {
                mvx = 2 * x + top.trueX;
                mvy = 2 * y + top.trueY;
                motionCompYField(predY, 0, ref.y.pixels, topRef * frameWidthY, mvx, mvy, limit);
            }
            mvx -= 2 * x;
            mvy -= 2 * y;
            motionCompFieldUV(predU, predV, 0, ref, x, y, (mvx & 3) != 0 ? ((mvx >> 1) | 1) : (mvx >> 1),
                    (mvy & 6) != 0 ? ((mvy >> 1) | 2) : (mvy >> 1), topRef, limit);
            if (volmd.quarterSample) {
                motionCompQuarterSample(predY, MB_SIZE, ref.y.pixels, botRef * frameWidthY, 0, 4 * x + bot.trueX,
                        4 * y + bot.trueY, vopmd.roundingControl, limit);
                mvx = 2 * x + bot.mvx;
                mvy = 2 * y + bot.mvy;
            } else {
                mvx = 2 * x + bot.trueX;
                mvy = 2 * y + bot.trueY;
                motionCompYField(predY, MB_SIZE, ref.y.pixels, botRef * frameWidthY, mvx, mvy, limit);
            }
            mvx -= 2 * x;
            mvy -= 2 * y;
            motionCompFieldUV(predU, predV, BLOCK_SIZE, ref, x, y, (mvx & 3) != 0 ? ((mvx >> 1) | 1) : (mvx >> 1),
                    (mvy & 6) != 0 ? ((mvy >> 1) | 2) : (mvy >> 1), botRef, limit);
        } else {
            var mv = mvs[mvOff];
            if (volmd.quarterSample) {
                motionCompQuarterSample(predY, 0, ref.y.pixels, 0, MB_SIZE, x * 4 + mv.trueX, y * 4 + mv.trueY,
                        vopmd.roundingControl, limit);
                motionCompUV(predU, predV, ref, x, y, (mv.mvx & 3) != 0 ? ((mv.mvx >> 1) | 1) : (mv.mvx >> 1),
                        (mv.mvy & 3) != 0 ? ((mv.mvy >> 1) | 1) : (mv.mvy >> 1), vopmd.roundingControl, limit);
            } else {
                motionComp(predY, 0, ref.y.pixels, 0, MB_SIZE, x * 2 + mv.trueX, y * 2 + mv.trueY,
                        vopmd.roundingControl, limit);
                motionCompUV(predU, predV, ref, x, y, (mv.trueX & 3) != 0 ? ((mv.trueX >> 1) | 1) : (mv.trueX >> 1),
                        (mv.trueY & 3) != 0 ? ((mv.trueY >> 1) | 1) : (mv.trueY >> 1), vopmd.roundingControl, limit);
            }
        }
    }

    /**
     * Predicts the luminance of a macroblock with overlapped block motion compensation (7.6.6,
     * {@code motionCompOverLap}).
     */
    final void motionCompOverLap(int[] pred, int[] ref, MotionVector[] mvs, int mvOff, int mbIndex, int mbX,
                                 int mbY, int x, int y, Rct limit) {
        var mode = mbmd[mbIndex];
        boolean topBndry;
        boolean leftBndry;
        boolean rightBndry;
        if (volmd.newpredEnable && !volmd.newpredSegmentType) {
            topBndry = mbY >= 1 ? mode.npSegmentNumber != mbmd[mbIndex - numMBX].npSegmentNumber : mbY == 0;
            leftBndry = mbX >= 1 ? mode.npSegmentNumber != mbmd[mbIndex - 1].npSegmentNumber : mbX == 0;
            rightBndry = mbX < numMBX - 1 ? mode.npSegmentNumber != mbmd[mbIndex + 1].npSegmentNumber
                    : mbX == numMBX - 1;
        } else {
            topBndry = mbY == 0;
            leftBndry = mbX == 0;
            rightBndry = mbX == numMBX - 1;
        }
        MbMode top = null;
        MbMode left = null;
        MbMode right = null;
        var intraT = false;
        var intraL = false;
        var intraR = false;
        var topMv = 0;
        var leftMv = 0;
        var rightMv = 0;
        if (!topBndry) {
            top = mbmd[mbIndex - numMBX];
            intraT = top.dctMd == MbMode.INTRA || top.dctMd == MbMode.INTRAQ || top.mcsel;
            topMv = mvOff - numOfTotalMVPerRow;
        }
        if (!leftBndry) {
            left = mbmd[mbIndex - 1];
            intraL = left.dctMd == MbMode.INTRA || left.dctMd == MbMode.INTRAQ || left.mcsel;
            leftMv = mvOff - PVOP_MV_PER_REF_PER_MB;
        }
        if (!rightBndry) {
            right = mbmd[mbIndex + 1];
            intraR = right.dctMd == MbMode.INTRA || right.dctMd == MbMode.INTRAQ || right.mcsel;
            rightMv = mvOff + PVOP_MV_PER_REF_PER_MB;
        }
        var ts = mode.transpStatus;
        for (var i = 1; i < 5; i++) {
            if (ts[i] == MbMode.ALL) {
                continue;
            }
            var c = mvOff + i;
            int t;
            int b;
            int r;
            int l;
            switch (i) {
                case 1 -> {
                    b = ts[3] == MbMode.ALL ? mvOff + 1 : mvOff + 3;
                    r = ts[2] == MbMode.ALL ? mvOff + 1 : mvOff + 2;
                    t = topBndry || intraT || top.transpStatus[3] == MbMode.ALL ? mvOff + 1 : topMv + 3;
                    l = leftBndry || intraL || left.transpStatus[2] == MbMode.ALL ? mvOff + 1 : leftMv + 2;
                }
                case 2 -> {
                    b = ts[4] == MbMode.ALL ? mvOff + 2 : mvOff + 4;
                    l = ts[1] == MbMode.ALL ? mvOff + 2 : mvOff + 1;
                    t = topBndry || intraT || top.transpStatus[4] == MbMode.ALL ? mvOff + 2 : topMv + 4;
                    r = rightBndry || intraR || right.transpStatus[1] == MbMode.ALL ? mvOff + 2 : rightMv + 1;
                }
                case 3 -> {
                    t = ts[1] == MbMode.ALL ? mvOff + 3 : mvOff + 1;
                    b = mvOff + 3;
                    r = ts[4] == MbMode.ALL ? mvOff + 3 : mvOff + 4;
                    l = leftBndry || intraL || left.transpStatus[4] == MbMode.ALL ? mvOff + 3 : leftMv + 4;
                }
                default -> {
                    t = ts[2] == MbMode.ALL ? mvOff + 4 : mvOff + 2;
                    b = mvOff + 4;
                    l = ts[3] == MbMode.ALL ? mvOff + 4 : mvOff + 3;
                    r = rightBndry || intraR || right.transpStatus[3] == MbMode.ALL ? mvOff + 4 : rightMv + 3;
                }
            }
            var dxc = (((i - 1) & 1) << 3) * rrvScale;
            var dyc = (((i - 1) & 2) << 2) * rrvScale;
            var nxcY = (x + dxc) << 1;
            var nycY = (y + dyc) << 1;
            if (volmd.quarterSample) {
                overlapQuarterSample(pred, ref, mvs[c], mvs[t], mvs[b], mvs[r], mvs[l], nxcY, nycY, dxc, dyc, limit);
            } else {
                overlapHalfSample(pred, ref, mvs[c], mvs[t], mvs[b], mvs[r], mvs[l], nxcY, nycY, dxc, dyc, limit);
            }
        }
    }

    private void overlapQuarterSample(int[] pred, int[] ref, MotionVector c, MotionVector t, MotionVector b,
                                      MotionVector r, MotionVector l, int nxcY, int nycY, int dxc, int dyc,
                                      Rct limit) {
        interpolateOverlap(ref, c, qpelC, nxcY, nycY, limit);
        interpolateOverlap(ref, t, qpelT, nxcY, nycY, limit);
        interpolateOverlap(ref, b, qpelB, nxcY, nycY, limit);
        interpolateOverlap(ref, r, qpelR, nxcY, nycY, limit);
        interpolateOverlap(ref, l, qpelL, nxcY, nycY, limit);
        var p = dxc + dyc * MB_SIZE;
        for (var iy = 0; iy < BLOCK_SIZE; iy++) {
            for (var ix = 0; ix < BLOCK_SIZE; ix++) {
                var i = ix + iy * BLOCK_SIZE;
                pred[p++] = (qpelC[i] * RefTables.WGHT_C[i] + qpelT[i] * RefTables.WGHT_T[i]
                        + qpelB[i] * RefTables.WGHT_B[i] + qpelR[i] * RefTables.WGHT_R[i]
                        + qpelL[i] * RefTables.WGHT_L[i] + 4) >> 3;
            }
            p += BLOCK_SIZE;
        }
    }

    private void interpolateOverlap(int[] ref, MotionVector mv, int[] block, int nxcY, int nycY, Rct limit) {
        limitQuarterPel(((nxcY + mv.mvx) << 1) + mv.halfX, ((nycY + mv.mvy) << 1) + mv.halfY, limit, BLOCK_SIZE);
        blkInterpolateY(ref, 0, BLOCK_SIZE, limitXY[0], limitXY[1], block, vopmd.roundingControl);
    }

    private void overlapHalfSample(int[] pred, int[] ref, MotionVector c, MotionVector t, MotionVector b,
                                   MotionVector r, MotionVector l, int nxcY, int nycY, int dxc, int dyc, Rct limit) {
        var rrv = vopmd.rrvOnOff != 0;
        var blk = BLOCK_SIZE * rrvScale;
        var xRefC = nxcY + (rrv ? c.trueX2 : c.trueX);
        var yRefC = nycY + (rrv ? c.trueY2 : c.trueY);
        var xRefT = nxcY + (rrv ? t.trueX2 : t.trueX);
        var yRefT = nycY + (rrv ? t.trueY2 : t.trueY);
        var xRefB = nxcY + (rrv ? b.trueX2 : b.trueX);
        var yRefB = nycY + (rrv ? b.trueY2 : b.trueY);
        var xRefR = nxcY + (rrv ? r.trueX2 : r.trueX);
        var yRefR = nycY + (rrv ? r.trueY2 : r.trueY);
        var xRefL = nxcY + (rrv ? l.trueX2 : l.trueX);
        var yRefL = nycY + (rrv ? l.trueY2 : l.trueY);
        limitHalfPel(xRefC, yRefC, limit, blk);
        xRefC = limitXY[0];
        yRefC = limitXY[1];
        limitHalfPel(xRefT, yRefT, limit, blk);
        xRefT = limitXY[0];
        yRefT = limitXY[1];
        limitHalfPel(xRefB, yRefB, limit, blk);
        xRefB = limitXY[0];
        yRefB = limitXY[1];
        limitHalfPel(xRefR, yRefR, limit, blk);
        xRefR = limitXY[0];
        yRefR = limitXY[1];
        limitHalfPel(xRefL, yRefL, limit, blk);
        xRefL = limitXY[0];
        yRefL = limitXY[1];
        var offset = rrv ? 128 : 32;
        var weightC = rrv ? RefTables.WGHT_C_RRV : RefTables.WGHT_C;
        var weightT = rrv ? RefTables.WGHT_T_RRV : RefTables.WGHT_T;
        var weightB = rrv ? RefTables.WGHT_B_RRV : RefTables.WGHT_B;
        var weightR = rrv ? RefTables.WGHT_R_RRV : RefTables.WGHT_R;
        var weightL = rrv ? RefTables.WGHT_L_RRV : RefTables.WGHT_L;
        var overlap = rrv ? overlapPredX2 : overlapPred;
        var bSize = rrv ? 16 : 8;
        var hbSize = rrv ? 8 : 4;
        var shift = rrv ? 3 : 2;
        bilinear(overlap, 0, ref, origin(xRefC, yRefC), weightC, 0, 0, bSize, 0, bSize, false, xRefC, yRefC);
        bilinear(overlap, 0, ref, origin(xRefT, yRefT), weightT, 0, 0, bSize, 0, hbSize, true, xRefT, yRefT);
        bilinear(overlap, offset, ref, origin(xRefB, yRefB) + (frameWidthY << shift), weightB, offset, 0, bSize,
                hbSize, bSize, true, xRefB, yRefB);
        bilinear(overlap, 0, ref, origin(xRefR, yRefR), weightR, 0, hbSize, bSize, 0, bSize, true, xRefR, yRefR);
        bilinear(overlap, 0, ref, origin(xRefL, yRefL), weightL, 0, 0, hbSize, 0, bSize, true, xRefL, yRefL);
        var p = dxc + dyc * (MB_SIZE * rrvScale);
        var o = 0;
        for (var iy = 0; iy < blk; iy++) {
            for (var ix = 0; ix < blk; ix++) {
                pred[p++] = (overlap[o++] + 4) >> 3;
            }
            p += blk;
        }
    }

    private int origin(int xRef, int yRef) {
        return ((yRef >> 1) + EXPANDY_REF_FRAME) * frameWidthY + (xRef >> 1) + EXPANDY_REF_FRAME;
    }

    /**
     * Accumulates one weighted prediction of overlapped motion compensation ({@code bilnrMCVH}, {@code bilnrMCV},
     * {@code bilnrMCH} and {@code bilnrMC}).
     */
    private void bilinear(int[] predY, int predOff, int[] ref, int prev, int[] weights, int weightOff, int xLow,
                          int xHigh, int yLow, int yHigh, boolean add, int xRef, int yRef) {
        var xSub = (xRef & 1) != 0;
        var ySub = (yRef & 1) != 0;
        var rc = vopmd.roundingControl;
        var step = BLOCK_SIZE * rrvScale;
        var p = predOff;
        var w = weightOff;
        var r = prev;
        for (var iy = yLow; iy < yHigh; iy++) {
            var bottom = r + frameWidthY;
            for (var ix = xLow; ix < xHigh; ix++) {
                int value;
                if (xSub && ySub) {
                    value = (ref[r + ix] + ref[r + ix + 1] + ref[bottom + ix] + ref[bottom + ix + 1] + 2 - rc) >> 2;
                } else if (xSub) {
                    value = (ref[r + ix] + ref[r + ix + 1] + 1 - rc) >> 1;
                } else if (ySub) {
                    value = (ref[r + ix] + ref[bottom + ix] + 1 - rc) >> 1;
                } else {
                    value = ref[r + ix];
                }
                if (add) {
                    predY[p + ix] += value * weights[w + ix];
                } else {
                    predY[p + ix] = value * weights[w + ix];
                }
            }
            r += frameWidthY;
            p += step;
            w += step;
        }
    }

    /**
     * Copies the binary shape around a position of the reference, transparent outside the reference bounding box
     * ({@code motionCompBY}).
     *
     * @param pred receives the 18x18 prediction
     */
    final void motionCompBY(int[] pred, int[] ref, int xRef, int yRef) {
        Rct bound = vopmd.predType == VopMode.BVOP && vopmd.shapeBPredDir == VopMode.B_BACKWARD ? rctRefVOPY1
                : rctRefVOPY0;
        motionCompBY(pred, ref, xRef, yRef, bound);
    }

    /**
     * Copies the binary shape of the lower layer around a position ({@code motionCompLowerBY}).
     */
    final void motionCompLowerBY(int[] pred, int[] ref, int xRef, int yRef) {
        motionCompBY(pred, ref, xRef, yRef, rctRefVOPY1);
    }

    private void motionCompBY(int[] pred, int[] ref, int xRef, int yRef, Rct bound) {
        var r = (yRef + EXPANDY_REF_FRAME) * frameWidthY + xRef + EXPANDY_REF_FRAME;
        var leftBound = Math.min(MC_BAB_SIZE, Math.max(0, bound.left - xRef));
        var rightBound = Math.min(MC_BAB_SIZE, Math.max(0, bound.right - xRef));
        var topBound = Math.min(MC_BAB_SIZE, Math.max(0, bound.top - yRef));
        var bottomBound = Math.min(MC_BAB_SIZE, Math.max(0, bound.bottom - yRef));
        var heightMax = bottomBound - topBound;
        var widthMax = rightBound - leftBound;
        var p = 0;
        if (heightMax == MC_BAB_SIZE && widthMax == MC_BAB_SIZE) {
            for (var iy = 0; iy < MC_BAB_SIZE; iy++) {
                System.arraycopy(ref, r, pred, p, MC_BAB_SIZE);
                r += frameWidthY;
                p += MC_BAB_SIZE;
            }
        } else if (widthMax == 0 || heightMax == 0) {
            java.util.Arrays.fill(pred, 0, MC_BAB_SIZE * MC_BAB_SIZE, 0);
        } else {
            for (var iy = 0; iy < MC_BAB_SIZE; iy++) {
                if (iy < topBound || iy >= bottomBound) {
                    java.util.Arrays.fill(pred, p, p + MC_BAB_SIZE, 0);
                } else {
                    if (leftBound > 0) {
                        java.util.Arrays.fill(pred, p, p + leftBound, 0);
                    }
                    if (rightBound < MC_BAB_SIZE) {
                        java.util.Arrays.fill(pred, p + rightBound, p + MC_BAB_SIZE, 0);
                    }
                    if (widthMax > 0) {
                        System.arraycopy(ref, r + leftBound, pred, p + leftBound, widthMax);
                    }
                }
                r += frameWidthY;
                p += MC_BAB_SIZE;
            }
        }
    }

    /**
     * Copies a macroblock of a reference VOP into the current one, at zero motion ({@code copyFromRefToCurrQ}).
     */
    final void copyFromRefToCurrQ(VopPlanes ref, int x, int y, int[] currY, int offY, int[] currU, int[] currV,
                                  int offUV, Rct limit) {
        limitFullPel(x, y, limit, MB_SIZE * rrvScale);
        x = limitXY[0];
        y = limitXY[1];
        var refY = (y + EXPANDY_REF_FRAME) * frameWidthY + x + EXPANDY_REF_FRAME;
        var refUV = (y / 2 + EXPANDUV_REF_FRAME) * frameWidthUV + x / 2 + EXPANDUV_REF_FRAME;
        var widthY = MB_SIZE * rrvScale;
        var widthUV = BLOCK_SIZE * rrvScale;
        for (var iy = 0; iy < BLOCK_SIZE * rrvScale; iy++) {
            System.arraycopy(ref.y.pixels, refY, currY, offY, widthY);
            System.arraycopy(ref.u.pixels, refUV, currU, offUV, widthUV);
            System.arraycopy(ref.v.pixels, refUV, currV, offUV, widthUV);
            offY += frameWidthY;
            refY += frameWidthY;
            offUV += frameWidthUV;
            refUV += frameWidthUV;
            System.arraycopy(ref.y.pixels, refY, currY, offY, widthY);
            offY += frameWidthY;
            refY += frameWidthY;
        }
    }

    /**
     * Copies the macroblock of the alpha planes of a reference VOP at zero motion ({@code copyAlphaFromRefToCurrQ}).
     *
     * @param offsets the index of the macroblock in each alpha plane of the current VOP
     */
    final void copyAlphaFromRefToCurrQ(VopPlanes ref, VopPlanes curr, int x, int y, int[] offsets, Rct limit) {
        limitFullPel(x, y, limit, MB_SIZE);
        x = limitXY[0];
        y = limitXY[1];
        var refY = (y + EXPANDY_REF_FRAME) * frameWidthY + x + EXPANDY_REF_FRAME;
        for (var i = 0; i < volmd.auxCompCount; i++) {
            var r = refY;
            var c = offsets[i];
            for (var iy = 0; iy < MB_SIZE; iy++) {
                System.arraycopy(ref.a[i].pixels, r, curr.a[i].pixels, c, MB_SIZE);
                c += frameWidthY;
                r += frameWidthY;
            }
        }
    }
}
