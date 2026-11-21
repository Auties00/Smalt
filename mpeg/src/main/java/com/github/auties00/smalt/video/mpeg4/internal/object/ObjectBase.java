/* Ports the Microsoft MPEG-4 Visual reference software of ISO/IEC 14496-5:2001 (version 2 FDAM1-2.3-001213,
 * sys/vopses.hpp and vopses.cpp: CVideoObject). Copyright 1996-2000 Microsoft Corporation and the contributors named
 * in the sources; ISO/IEC free license for products conforming to ISO/IEC 14496-2. Modified for Smalt. */
package com.github.auties00.smalt.video.mpeg4.internal.object;

import java.util.Arrays;

/**
 * The state of the object decoder and its frame-level helpers ({@code CVideoObject}): the layer and VOP settings,
 * the reference, current and macroblock buffers, the macroblock modes and motion vectors, and the padding of
 * reference VOPs. The subclasses add the tools of the decoding process one after the other.
 *
 * <p>Pointers of the reference software become an array and an offset; a macroblock buffer has the luma width of a
 * macroblock as its stride, the frame planes the width of their rectangle.
 */
abstract class ObjectBase {
    static final int MB_SIZE = 16;
    static final int BLOCK_SIZE = 8;
    static final int MB_SQUARE_SIZE = 256;
    static final int BLOCK_SQUARE_SIZE = 64;
    static final int EXPANDY_REFVOP = 16;
    static final int EXPANDUV_REFVOP = 8;
    static final int EXPANDY_REF_FRAME = 32;
    static final int EXPANDUV_REF_FRAME = 16;
    static final int PVOP_MV_PER_REF_PER_MB = 9;
    static final int BVOP_MV_PER_REF_PER_MB = 5;
    static final int MC_BAB_SIZE = 18;
    static final int BAB_SIZE = 20;
    static final int BAB_BORDER = 2;
    static final int MC_BAB_BORDER = 1;
    static final int TOTAL_BAB_SIZE = 20;
    static final int OFFSET_BLK1 = 8;
    static final int OFFSET_BLK2 = 128;
    static final int OFFSET_BLK3 = 136;

    static final int Y_BLOCK1 = 1;
    static final int Y_BLOCK2 = 2;
    static final int Y_BLOCK3 = 3;
    static final int Y_BLOCK4 = 4;
    static final int U_BLOCK = 5;
    static final int V_BLOCK = 6;
    static final int A_BLOCK1 = 7;
    static final int A_BLOCK2 = 8;
    static final int A_BLOCK3 = 9;
    static final int A_BLOCK4 = 10;
    static final int ALL_Y_BLOCKS = 0;

    static final int BASIC_SPRITE = 0;
    static final int LOW_LATENCY = 1;
    static final int PIECE_OBJECT = 2;
    static final int PIECE_UPDATE = 3;

    final VolMode volmd = new VolMode();
    final VopMode vopmd = new VopMode();

    int t;
    int tPastRef;
    int tFutureRef;
    int bCount;
    boolean codedFutureRef = true;
    boolean codedBaseRef;
    final Rct displayWindow = new Rct();
    int numBitsTimeIncr;
    int tModuloBaseDisp;
    int tModuloBaseDecd;
    boolean useGov;
    boolean linkIsBroken;

    VopPlanes predMB;
    int[] predMBY;
    int[] predMBU;
    int[] predMBV;
    int[][] predMBA;
    int[] errorMBY;
    int[] errorMBU;
    int[] errorMBV;
    int[][] errorMBA;
    VopPlanes predMBBack;
    int[] predMBBackY;
    int[] predMBBackU;
    int[] predMBBackV;
    int[][] predMBBackA;

    ArCodec arCodec;
    Plane predBAB;
    int[] predBABDown2;
    int[] predBABDown4;
    int[] reconCurrBAB;
    int widthCurrBAB;
    int[] currMBBYDown4;
    int[] currMBBYDown2;
    int[] refShapeModes;
    int[] baseShapeModes;
    int refShpNumMBX;
    int refShpNumMBY;
    VopPlanes currMB;
    int[] currMBY;
    int[] currMBU;
    int[] currMBV;
    int[] currMBBY;
    int[] currMBBUV;
    int[][] currMBA;

    final boolean[] emptyRowArray = new boolean[MB_SIZE];

    int uiVOId;
    int uiSprite;
    int warpingAccuracy;
    int numOfPnts;
    double[] srcQx;
    double[] srcQy;
    double[] dstQx;
    double[] dstQy;
    VopPlanes sptQ;
    final Rct rctSpt = new Rct();
    final Rct rctSptDisp = new Rct();
    int sptMode;

    VopPlanes refQ0;
    VopPlanes refQ1;
    VopPlanes currQ;
    int startInRefToCurrRctY;
    int startInRefToCurrRctUV;

    final int[] forwardMvPredBVOPx = new int[2];
    final int[] forwardMvPredBVOPy = new int[2];
    final int[] backwardMvPredBVOPx = new int[2];
    final int[] backwardMvPredBVOPy = new int[2];

    final Rct rctRefFrameY = new Rct();
    final Rct rctRefFrameUV = new Rct();
    int frameWidthYxMBSize;
    int frameWidthYxBlkSize;
    int frameWidthUVxBlkSize;
    int frameWidthY;
    int frameWidthUV;
    int volWidth;
    int volHeight;

    final Rct rctCurrVOPY = new Rct();
    final Rct rctCurrVOPUV = new Rct();
    final Rct rctRefVOPY0 = new Rct();
    final Rct rctRefVOPUV0 = new Rct();
    final Rct rctRefVOPY1 = new Rct();
    final Rct rctRefVOPUV1 = new Rct();
    int offsetForPadY;
    int offsetForPadUV;
    final Rct rctPrevNoExpandY = new Rct();
    final Rct rctPrevNoExpandUV = new Rct();
    final Rct rctBVOPRefVOPY1 = new Rct();
    final Rct rctBVOPRefVOPUV1 = new Rct();
    int bvopOffsetForPadY;
    int bvopOffsetForPadUV;
    final Rct rctBVOPPrevNoExpandY = new Rct();
    final Rct rctBVOPPrevNoExpandUV = new Rct();
    int vopWidthY;
    int vopWidthUV;
    int numMB;
    int numMBX;
    int numMBY;
    int numOfTotalMVPerRow;
    int sessNumMB;

    MbMode[] mbmd;
    MotionVector[] mv;
    int mvBackward;
    MotionVector[] mvBY;
    MotionVector[] mvBaseBY;
    final Rct rctBase = new Rct();
    int numMBBaseXRef;
    int numMBBaseYRef;
    MbMode[] mbmdRef;
    MotionVector[] mvRef;
    int numMBRef;
    int numMBXRef;
    int numMBYRef;

    int[] clipTab;
    int clipOffset;

    int vpMBnum;

    int[][] coefQ;
    int[][][] mbmAbove;
    int[][][] mbmCurr;
    final int[] dctCoef = new int[BLOCK_SQUARE_SIZE];
    InvDct idct;
    int[][] currMBCoeffWidth;

    int rrvScale = 1;

    /**
     * {@return the clipped value of a reconstructed sample ({@code m_rgiClipTab})}
     */
    final int clip(int value) {
        var index = value + clipOffset;
        if (index < 0) {
            return 0;
        }
        if (index >= clipTab.length) {
            return clipTab[clipTab.length - 1];
        }
        return clipTab[index];
    }

    /**
     * Builds the clipping table of the sample depth ({@code setClipTab}).
     */
    final void setClipTab() {
        var size = 1 << (volmd.nBits + 2);
        var max = (1 << volmd.nBits) - 1;
        clipOffset = size / 2;
        clipTab = new int[size];
        for (var i = -clipOffset; i < clipOffset; i++) {
            clipTab[i + clipOffset] = i < 0 ? 0 : Math.min(i, max);
        }
    }

    /**
     * Allocates the buffers of a layer for a session size ({@code allocateVOLMembers}).
     */
    void allocateVOLMembers(int sessionWidth, int sessionHeight) {
        var scale = volmd.reducedResolutionVopEnable ? 2 : 1;
        var mbRect = new Rct(0, 0, MB_SIZE * scale, MB_SIZE * scale);
        var aux = volmd.auxCompCount;
        currMB = new VopPlanes(volmd.alphaUsage, mbRect, aux);
        currMBY = currMB.y.pixels;
        currMBU = currMB.u.pixels;
        currMBV = currMB.v.pixels;
        currMBBY = currMB.by != null ? currMB.by.pixels : null;
        currMBBUV = currMB.buv != null ? currMB.buv.pixels : null;
        currMBA = new int[aux][];
        for (var i = 0; i < aux; i++) {
            currMBA[i] = currMB.a[i].pixels;
        }
        predMB = new VopPlanes(volmd.alphaUsage, mbRect, aux);
        predMBY = predMB.y.pixels;
        predMBU = predMB.u.pixels;
        predMBV = predMB.v.pixels;
        predMBA = new int[aux][];
        for (var i = 0; i < aux; i++) {
            predMBA[i] = predMB.a[i].pixels;
        }
        predMBBack = new VopPlanes(volmd.alphaUsage, mbRect, aux);
        predMBBackY = predMBBack.y.pixels;
        predMBBackU = predMBBack.u.pixels;
        predMBBackV = predMBBack.v.pixels;
        predMBBackA = new int[aux][];
        for (var i = 0; i < aux; i++) {
            predMBBackA[i] = predMBBack.a[i].pixels;
        }
        var lumaArea = mbRect.area();
        errorMBY = new int[lumaArea];
        errorMBU = new int[lumaArea / 4];
        errorMBV = new int[lumaArea / 4];
        errorMBA = new int[aux][lumaArea];
        if (uiSprite == 0 || uiSprite == 2) {
            currQ = new VopPlanes(volmd.alphaUsage, rctRefFrameY, aux);
        } else {
            var rect = new Rct(-EXPANDY_REF_FRAME, -EXPANDY_REF_FRAME, EXPANDY_REF_FRAME + displayWindow.width,
                    EXPANDY_REF_FRAME + displayWindow.height());
            currQ = new VopPlanes(volmd.alphaUsage, rect, aux);
        }
        refQ0 = new VopPlanes(volmd.alphaUsage, rctRefFrameY, aux);
        refQ1 = new VopPlanes(volmd.alphaUsage, rctRefFrameY, aux);
        frameWidthY = refQ0.whereY.width;
        frameWidthUV = refQ0.whereUV.width;
        frameWidthYxMBSize = MB_SIZE * frameWidthY;
        frameWidthYxBlkSize = BLOCK_SIZE * frameWidthY;
        frameWidthUVxBlkSize = BLOCK_SIZE * frameWidthUV;
        var blocks = volmd.alphaUsage == VopPlanes.EIGHT_BIT ? 6 + aux * 4 : 6;
        coefQ = new int[blocks][BLOCK_SQUARE_SIZE];
        if (volmd.alphaUsage != VopPlanes.RECTANGLE && !volmd.sadctDisable) {
            idct = new InvSadct(volmd.nBits);
            currMBCoeffWidth = new int[11][];
            for (var i = Y_BLOCK1; i <= U_BLOCK; i++) {
                currMBCoeffWidth[i] = new int[BLOCK_SIZE];
            }
            currMBCoeffWidth[V_BLOCK] = currMBCoeffWidth[U_BLOCK];
            for (int i = A_BLOCK1, j = Y_BLOCK1; i <= A_BLOCK4; i++, j++) {
                currMBCoeffWidth[i] = currMBCoeffWidth[j];
            }
        } else {
            idct = new InvDct(volmd.nBits);
        }
        var mbx = (sessionWidth + MB_SIZE - 1) / MB_SIZE;
        var mby = (sessionHeight + MB_SIZE - 1) / MB_SIZE;
        sessNumMB = mbx * mby;
        mbmd = MbMode.array(sessNumMB, aux);
        var vectors = Math.max(PVOP_MV_PER_REF_PER_MB, 2 * BVOP_MV_PER_REF_PER_MB) * sessNumMB;
        mv = MotionVector.array(vectors);
        mvBackward = BVOP_MV_PER_REF_PER_MB * sessNumMB;
        mvRef = MotionVector.array(vectors);
        mvBY = MotionVector.array(sessNumMB);
        mbmdRef = MbMode.array(sessNumMB, aux);
        mvBaseBY = MotionVector.array(sessNumMB);
        if (volmd.volType == VolMode.ENHN_LAYER && !(volmd.spatialScalability && volmd.hierarchyType == 0)) {
            refShapeModes = new int[sessNumMB];
        }
        if (volmd.alphaUsage != VopPlanes.RECTANGLE) {
            predBAB = new Plane(new Rct(0, 0, MC_BAB_SIZE, MC_BAB_SIZE));
            reconCurrBAB = new int[BAB_SIZE * BAB_SIZE];
            arCodec = new ArCodec();
            currMBBYDown4 = new int[8 * 8];
            currMBBYDown2 = new int[12 * 12];
            predBABDown4 = new int[6 * 6];
            predBABDown2 = new int[10 * 10];
        }
    }

    /**
     * Sets the VOP members that rectangular layers keep for the whole layer ({@code computeVOLConstMembers}).
     */
    void computeVOLConstMembers() {
        offsetForPadY = rctRefFrameY.offset(rctCurrVOPY.left, rctCurrVOPY.top);
        offsetForPadUV = rctRefFrameUV.offset(rctCurrVOPUV.left, rctCurrVOPUV.top);
        rctPrevNoExpandY.set(rctCurrVOPY);
        rctPrevNoExpandUV.set(rctCurrVOPUV);
        vopWidthY = rctCurrVOPY.width;
        vopWidthUV = rctCurrVOPUV.width;
        numMBX = numMBXRef = vopWidthY / MB_SIZE;
        numMBY = numMBYRef = rctCurrVOPY.height() / MB_SIZE;
        numMB = numMBRef = numMBX * numMBY;
        numOfTotalMVPerRow = PVOP_MV_PER_REF_PER_MB * numMBX;
        setRefStartingPointers();
        currQ.setBoundRct(rctCurrVOPY);
        refQ0.setBoundRct(rctRefVOPY0);
        refQ1.setBoundRct(rctRefVOPY1);
        allocateBlockMemory();
    }

    /**
     * Sets the members of the VOP being decoded from its rectangle ({@code computeVOPMembers}).
     */
    void computeVOPMembers() {
        vopWidthY = rctCurrVOPY.width;
        vopWidthUV = rctCurrVOPUV.width;
        numMBX = vopWidthY / MB_SIZE;
        numMBY = rctCurrVOPY.height() / MB_SIZE;
        numMB = numMBX * numMBY;
        numOfTotalMVPerRow = PVOP_MV_PER_REF_PER_MB * numMBX;
        allocateBlockMemory();
    }

    private void allocateBlockMemory() {
        var blocks = volmd.alphaUsage == VopPlanes.EIGHT_BIT ? 6 + volmd.auxCompCount * 4 : 6;
        mbmAbove = new int[numMBX][blocks][(BLOCK_SIZE << 1) - 1];
        mbmCurr = new int[numMBX][blocks][(BLOCK_SIZE << 1) - 1];
    }

    final void swapBlockMemoryRows() {
        var temp = mbmAbove;
        mbmAbove = mbmCurr;
        mbmCurr = temp;
    }

    /**
     * Extends the samples of a reference plane beyond its bounding box by repetition ({@code repeatPadYOrA}).
     *
     * @param plane the plane of the reference VOP
     * @param oldLeft the index of the top-left sample of the bounding box
     * @param ref the reference VOP
     */
    final void repeatPadYOrA(int[] plane, int oldLeft, VopPlanes ref) {
        var scale = vopmd.rrvEnable == 1 ? 2 : 1;
        var expand = EXPANDY_REFVOP * scale;
        var stride = ref.whereY.width;
        var oldWidth = volmd.alphaUsage == VopPlanes.RECTANGLE ? volWidth : rctPrevNoExpandY.width;
        var oldHeight = volmd.alphaUsage == VopPlanes.RECTANGLE ? volHeight : rctPrevNoExpandY.height();
        repeatPad(plane, oldLeft, stride, oldWidth, oldHeight, expand, MB_SIZE);
    }

    /**
     * Extends the chrominance samples of a reference VOP beyond its bounding box by repetition
     * ({@code repeatPadUV}).
     */
    final void repeatPadUV(VopPlanes ref) {
        var scale = vopmd.rrvEnable == 1 ? 2 : 1;
        var expand = EXPANDUV_REFVOP * scale;
        var stride = ref.whereUV.width;
        var oldWidth = volmd.alphaUsage == VopPlanes.RECTANGLE ? volWidth / 2 : rctPrevNoExpandUV.width;
        var oldHeight = volmd.alphaUsage == VopPlanes.RECTANGLE ? volHeight / 2 : rctPrevNoExpandUV.height();
        repeatPad(ref.u.pixels, offsetForPadUV, stride, oldWidth, oldHeight, expand, MB_SIZE >> 1);
        repeatPad(ref.v.pixels, offsetForPadUV, stride, oldWidth, oldHeight, expand, MB_SIZE >> 1);
    }

    private static void repeatPad(int[] plane, int oldLeft, int stride, int oldWidth, int oldHeight, int expand,
                                  int mbSize) {
        var leftTop = oldLeft - expand * stride - expand;
        var expandR = expand + mbSize * ((oldWidth + mbSize - 1) / mbSize) - oldWidth;
        var expandB = expand + mbSize * ((oldHeight + mbSize - 1) / mbSize) - oldHeight;
        var newWidth = expand + oldWidth + expandR;
        var oldRight = oldLeft + oldWidth - 1;
        var oldTopLine = oldLeft - expand;
        var newLeft = oldTopLine;
        var newRight = oldRight + 1;
        var left = oldLeft;
        var right = oldRight;
        for (var y = 0; y < oldHeight; y++) {
            Arrays.fill(plane, newLeft, newLeft + expand, plane[left]);
            Arrays.fill(plane, newRight, newRight + expandR, plane[right]);
            newLeft += stride;
            newRight += stride;
            left += stride;
            right += stride;
        }
        var oldBottomLine = newLeft - stride;
        for (var y = 0; y < expand; y++) {
            System.arraycopy(plane, oldTopLine, plane, leftTop, newWidth);
            leftTop += stride;
        }
        for (var y = 0; y < expandB; y++) {
            System.arraycopy(plane, oldBottomLine, plane, newLeft, newWidth);
            newLeft += stride;
        }
    }

    /**
     * Keeps the shape modes of the reference VOP for the shape coding of B-VOPs ({@code saveShapeMode}).
     */
    final void saveShapeMode() {
        if (refShapeModes == null) {
            refShpNumMBX = numMBX;
            refShpNumMBY = numMBY;
            refShapeModes = new int[numMB];
        } else {
            if (refShpNumMBX != numMBXRef || refShpNumMBY != numMBYRef) {
                refShapeModes = new int[numMBRef];
                refShpNumMBX = numMBXRef;
                refShpNumMBY = numMBYRef;
            }
            for (var i = 0; i < numMBRef; i++) {
                refShapeModes[i] = mbmdRef[i].shpmd;
            }
        }
    }

    /**
     * Keeps the shape modes of a base layer VOP for the shape coding of the enhancement layer
     * ({@code saveBaseShapeMode}).
     */
    final void saveBaseShapeMode() {
        if (baseShapeModes == null) {
            numMBBaseXRef = numMBX;
            numMBBaseYRef = numMBY;
            if (volmd.volType == VolMode.BASE_LAYER) {
                baseShapeModes = new int[numMB];
                refShpNumMBX = numMBX;
                refShpNumMBY = numMBY;
                for (var i = 0; i < numMB; i++) {
                    baseShapeModes[i] = mbmd[i].shpmd;
                }
            }
        } else if (volmd.volType == VolMode.BASE_LAYER) {
            if (numMBBaseXRef != numMBX || numMBBaseYRef != numMBY) {
                baseShapeModes = new int[numMB];
            }
            for (var i = 0; i < numMB; i++) {
                baseShapeModes[i] = mbmd[i].shpmd;
            }
            numMBBaseXRef = numMBX;
            numMBBaseYRef = numMBY;
        }
    }

    /**
     * Clears the binary shape of the VOP about to be decoded ({@code resetBYPlane}).
     */
    final void resetBYPlane() {
        if (vopmd.predType == VopMode.PVOP || vopmd.predType == VopMode.IVOP
                || (uiSprite == 2 && vopmd.predType == VopMode.SPRITE)) {
            Arrays.fill(refQ1.by.pixels, 0);
        } else {
            Arrays.fill(currQ.by.pixels, 0);
        }
    }

    /**
     * Makes the last reference VOP the previous one ({@code updateAllRefVOPs}).
     */
    void updateAllRefVOPs() {
        if (vopmd.predType != VopMode.BVOP) {
            rctRefVOPY0.set(rctRefVOPY1);
            var temp = refQ0;
            refQ0 = refQ1;
            refQ1 = temp;
        }
    }

    final void setRefStartingPointers() {
        startInRefToCurrRctY = rctRefFrameY.offset(rctCurrVOPY.left, rctCurrVOPY.top);
        startInRefToCurrRctUV = rctRefFrameUV.offset(rctCurrVOPUV.left, rctCurrVOPUV.top);
    }

    /**
     * {@return the planes holding the reconstruction of the VOP just decoded ({@code pvopcReconCurr})}
     */
    VopPlanes reconCurr() {
        if (uiSprite == 1 && vopmd.predType == VopMode.SPRITE && numOfPnts > 0) {
            return currQ;
        } else if (uiSprite == 1 && vopmd.predType == VopMode.SPRITE && numOfPnts == 0) {
            return sptMode != BASIC_SPRITE ? sptQ : refQ1;
        } else if ((vopmd.predType == VopMode.BVOP && volmd.volType == VolMode.BASE_LAYER)
                || (vopmd.predType == VopMode.BVOP && vopmd.refSelectCode != 0)) {
            return currQ;
        }
        return refQ1;
    }

    final void computeBfShapeMembers() {
        vopWidthY = rctCurrVOPY.width;
        vopWidthUV = rctCurrVOPUV.width;
        numMBX = vopWidthY / MB_SIZE;
        numMBY = rctCurrVOPY.height() / MB_SIZE;
        numMB = numMBX * numMBY;
        numOfTotalMVPerRow = PVOP_MV_PER_REF_PER_MB * numMBX;
    }

    /**
     * Interpolates a block at quarter sample accuracy with the 8-tap filter of 7.6.2.1 and its mirroring at the block
     * edges ({@code blkInterpolateY}).
     *
     * @param ref the reference plane
     * @param refLeftTop the index of the top-left sample of the reference frame
     * @param blkSize 16 or 8, or 0 for a 16x8 field block
     * @param xRef the horizontal position in quarter samples
     * @param yRef the vertical position in quarter samples
     * @param block receives the block, with the block width as its stride (twice that for a field block)
     * @param roundingControl the rounding control
     */
    final void blkInterpolateY(int[] ref, int refLeftTop, int blkSize, int xRef, int yRef, int[] block,
                               int roundingControl) {
        var maxVal = (1 << volmd.nBits) - 1;
        var refblk = this.qpelRef;
        var tmp0 = this.qpelTmp0;
        var tmp1 = this.qpelTmp1;
        var w = MB_SIZE + 7;
        var w1 = MB_SIZE + 1;
        int blkSizeX;
        int blkSizeY;
        int widthY;
        int yInt;
        int yFrac;
        var yTmp = yRef >> 1;
        if (blkSize == 0) {
            blkSizeX = 16;
            blkSizeY = 8;
            widthY = 2 * frameWidthY;
            yInt = yTmp / 4;
            if (yTmp < 0 && yTmp % 4 != 0) {
                yInt--;
            }
            yFrac = yTmp - 4 * yInt;
        } else {
            blkSizeX = blkSize;
            blkSizeY = blkSize;
            widthY = frameWidthY;
            yInt = yRef / 4;
            if (yRef < 0 && yRef % 4 != 0) {
                yInt--;
            }
            yFrac = yRef - 4 * yInt;
        }
        var xInt = xRef / 4;
        if (xRef < 0 && xRef % 4 != 0) {
            xInt--;
        }
        var xFrac = xRef - 4 * xInt;
        var origin = refLeftTop + yInt * widthY + EXPANDY_REF_FRAME * frameWidthY + xInt + EXPANDY_REF_FRAME;
        for (var iy = 0; iy <= blkSizeY; iy++) {
            for (var ix = 0; ix <= blkSizeX; ix++) {
                refblk[(iy + 3) * w + ix + 3] = ref[origin + ix + iy * widthY] & 0xFF;
            }
        }
        for (var iy = 0; iy <= blkSizeY; iy++) {
            for (var ix = 0; ix <= 2; ix++) {
                refblk[(iy + 3) * w + ix] = refblk[(iy + 3) * w + 5 - ix];
                refblk[(iy + 3) * w + blkSizeX + ix + 4] = refblk[(iy + 3) * w + blkSizeX - ix + 3];
            }
        }
        switch (xFrac) {
            case 0 -> {
                for (var iy = 0; iy <= blkSizeY; iy++) {
                    for (var ix = 0; ix <= blkSizeX - 1; ix++) {
                        tmp0[(iy + 3) * w + ix] = refblk[(iy + 3) * w + ix + 3];
                    }
                }
            }
            case 1, 3 -> {
                for (var iy = 0; iy <= blkSizeY; iy++) {
                    for (var ix = 0; ix <= blkSizeX - 1; ix++) {
                        var row = (iy + 3) * w + ix + 3;
                        tmp1[iy * w1 + ix] = filter8(refblk, row, 1, roundingControl, maxVal);
                    }
                }
                var shift = xFrac == 1 ? 3 : 4;
                for (var iy = 0; iy <= blkSizeY; iy++) {
                    for (var ix = 0; ix <= blkSizeX - 1; ix++) {
                        tmp0[(iy + 3) * w + ix] = (refblk[(iy + 3) * w + ix + shift] + tmp1[iy * w1 + ix] + 1
                                - roundingControl) / 2;
                    }
                }
            }
            default -> {
                for (var iy = 0; iy <= blkSizeY; iy++) {
                    for (var ix = 0; ix <= blkSizeX - 1; ix++) {
                        tmp0[(iy + 3) * w + ix] = filter8(refblk, (iy + 3) * w + ix + 3, 1, roundingControl,
                                maxVal);
                    }
                }
            }
        }
        for (var iy = 0; iy <= blkSizeY; iy++) {
            for (var ix = 0; ix <= blkSizeX - 1; ix++) {
                refblk[(iy + 3) * w + ix] = tmp0[(iy + 3) * w + ix];
            }
        }
        for (var iy = 0; iy <= 2; iy++) {
            for (var ix = 0; ix <= blkSizeX - 1; ix++) {
                refblk[iy * w + ix] = refblk[(5 - iy) * w + ix];
                refblk[(blkSizeY + iy + 4) * w + ix] = refblk[(blkSizeY - iy + 3) * w + ix];
            }
        }
        switch (yFrac) {
            case 0 -> {
                for (var iy = 0; iy <= blkSizeY - 1; iy++) {
                    for (var ix = 0; ix <= blkSizeX - 1; ix++) {
                        tmp0[iy * w + ix] = refblk[(iy + 3) * w + ix];
                    }
                }
            }
            case 1, 3 -> {
                for (var iy = 0; iy <= blkSizeY - 1; iy++) {
                    for (var ix = 0; ix <= blkSizeX - 1; ix++) {
                        tmp1[iy * w1 + ix] = filter8(refblk, (iy + 3) * w + ix, w, roundingControl, maxVal);
                    }
                }
                var shift = yFrac == 1 ? 3 : 4;
                for (var iy = 0; iy <= blkSizeY - 1; iy++) {
                    for (var ix = 0; ix <= blkSizeX - 1; ix++) {
                        tmp0[iy * w + ix] = (refblk[(iy + shift) * w + ix] + tmp1[iy * w1 + ix] + 1
                                - roundingControl) / 2;
                    }
                }
            }
            default -> {
                for (var iy = 0; iy <= blkSizeY - 1; iy++) {
                    for (var ix = 0; ix <= blkSizeX - 1; ix++) {
                        tmp0[iy * w + ix] = filter8(refblk, (iy + 3) * w + ix, w, roundingControl, maxVal);
                    }
                }
            }
        }
        var outStride = blkSize == 0 ? 2 * blkSizeX : blkSizeX;
        for (var iy = 0; iy <= blkSizeY - 1; iy++) {
            for (var ix = 0; ix <= blkSizeX - 1; ix++) {
                block[ix + iy * outStride] = tmp0[iy * w + ix];
            }
        }
    }

    private final int[] qpelRef = new int[(MB_SIZE + 7) * (MB_SIZE + 7)];
    private final int[] qpelTmp0 = new int[(MB_SIZE + 7) * (MB_SIZE + 7)];
    private final int[] qpelTmp1 = new int[(MB_SIZE + 1) * (MB_SIZE + 1)];

    /**
     * Applies the 8-tap half sample filter centred between {@code at} and {@code at + step}; the samples of the
     * reference software's 8-bit buffers keep the low 8 bits of the clipped value.
     */
    private static int filter8(int[] src, int at, int step, int roundingControl, int maxVal) {
        long u = 160L * (src[at] + src[at + step]) - 48L * (src[at - step] + src[at + 2 * step])
                + 24L * (src[at - 2 * step] + src[at + 3 * step]) - 8L * (src[at - 3 * step] + src[at + 4 * step]);
        u = (u + 128 - roundingControl) / 256;
        u = Math.max(0, Math.min(maxVal, u));
        return (int) u & 0xFF;
    }

    static int auxCompCount(int shapeExtension) {
        return switch (shapeExtension) {
            case 2, 3, 6, 9, 11 -> 2;
            case 4, 10, 12 -> 3;
            default -> 1;
        };
    }
}
