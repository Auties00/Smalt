/* Ports the Microsoft MPEG-4 Visual reference software of ISO/IEC 14496-5:2001 (version 2 FDAM1-2.3-001213,
 * sys/decoder/vopsedec.cpp: CVideoObjectDecoder construction and decode, sys/rrv.cpp: redefineVOLMembersRRV,
 * and the NEWPRED calls of sys/decoder/vopmbdec.cpp and errdec.cpp). Copyright 1996-2000 Microsoft Corporation,
 * Sharp Corporation, Sony Corporation, Oki Electric Industry, Samsung AIT and the contributors named in the sources;
 * ISO/IEC free license for products conforming to ISO/IEC 14496-2. Modified for Smalt. */
package com.github.auties00.smalt.video.mpeg4.internal.object;

/**
 * Decodes one video object layer with the tools that the fast engine does not implement: binary and grayscale shape,
 * shape-adaptive DCT, static sprites, sprite brightness change, N-bit samples, NEWPRED, reduced resolution VOPs and
 * the layers of spatial and temporal scalability ({@code CVideoObjectDecoder}).
 *
 * <p>The decoder takes the bytes of the layer header, then one VOP at a time; each call reports whether the VOP
 * was coded, and the caller takes the picture to display from {@link #reconCurr()}, {@link #pastReference()} or
 * {@link #futureReference()} in the order the reference software dumps them.
 */
final class ObjectDecoder extends ObjectScalable {
    static final int EOF = -1;
    static final int NOT_CODED = 0;
    static final int CODED = 1;
    private static final byte[] TAIL = {0, 0, 1, (byte) 0xB6};

    private NewPred newpred;
    private int[] newpredSlicePoints;
    private int newpredSliceCount;
    private byte[] packet = new byte[0];
    private boolean spriteInitialized;
    private final int requestedWidth;
    private final int requestedHeight;
    private final int frameWidthSS;
    private final int frameHeightSS;

    /**
     * Builds the decoder of a video object layer.
     *
     * @param volHeader the bytes of the layer, from its start code on
     * @param displayWidth the width of the display, used when the layer is not rectangular
     * @param displayHeight the height of the display, used when the layer is not rectangular
     * @param spatialFrameWidth the width of the enhancement layer of spatial scalability, or 0
     * @param spatialFrameHeight the height of the enhancement layer of spatial scalability, or 0
     */
    ObjectDecoder(byte[] volHeader, int offset, int length, int displayWidth, int displayHeight,
                  int spatialFrameWidth, int spatialFrameHeight) {
        load(volHeader, offset, length);
        if (bits.getBits(24) != 1) {
            throw CorruptStream.INSTANCE;
        }
        var code = bits.getBits(8);
        if (code < 0x20 || code > 0x2F) {
            throw CorruptStream.INSTANCE;
        }
        decodeVOLHead(code & 0xF);
        this.requestedWidth = displayWidth;
        this.requestedHeight = displayHeight;
        this.frameWidthSS = spatialFrameWidth;
        this.frameHeightSS = spatialFrameHeight;
        t = tPastRef = tFutureRef = 0;
        bCount = 0;
        vopmd.vopConstantAlphaValue = 255;
        linkIsBroken = false;
        useGov = false;
        setClipTab();
        if (volmd.volType == VolMode.ENHN_LAYER) {
            volmd.spatialScalability = volmd.hierarchyType == 0 || volmd.shapeOnly;
        }
        if (volmd.alphaUsage == VopPlanes.RECTANGLE) {
            if (volmd.volType == VolMode.ENHN_LAYER && (volmd.horSamplingFactorN != volmd.horSamplingFactorM
                    || volmd.verSamplingFactorN != volmd.verSamplingFactorM)) {
                displayWidth = volWidth;
                displayHeight = volHeight;
                volmd.frmWidthSS = displayWidth;
                volmd.frmHeightSS = displayHeight;
            } else {
                displayWidth = volWidth;
                displayHeight = volHeight;
            }
        } else if (volmd.alphaUsage == VopPlanes.ONE_BIT) {
            if (volmd.volType == VolMode.ENHN_LAYER
                    && (volmd.horSamplingFactorNShape != volmd.horSamplingFactorMShape
                    || volmd.verSamplingFactorNShape != volmd.verSamplingFactorMShape)) {
                volmd.frmWidthSS = spatialFrameWidth > 0 ? spatialFrameWidth : displayWidth;
                volmd.frmHeightSS = spatialFrameHeight > 0 ? spatialFrameHeight : displayHeight;
                volmd.spatialScalability = true;
            } else {
                volmd.spatialScalability = volmd.volType == VolMode.ENHN_LAYER && volmd.hierarchyType == 0;
            }
        }
        if (displayWidth <= 0 || displayHeight <= 0 || displayWidth > 8192 || displayHeight > 8192) {
            throw CorruptStream.INSTANCE;
        }
        displayWindow.set(0, 0, displayWidth, displayHeight);
        if (uiSprite == 1) {
            displayWidth = rctSpt.width;
            displayHeight = rctSpt.height();
            if (displayWidth <= 0 || displayHeight <= 0 || displayWidth > 8192 || displayHeight > 8192) {
                throw CorruptStream.INSTANCE;
            }
        }
        var widthRound = (displayWidth + MB_SIZE - 1) >> 4 << 4;
        var heightRound = (displayHeight + MB_SIZE - 1) >> 4 << 4;
        rctRefFrameY.set(-EXPANDY_REF_FRAME, -EXPANDY_REF_FRAME, EXPANDY_REF_FRAME + widthRound,
                EXPANDY_REF_FRAME + heightRound);
        rctRefFrameUV.set(rctRefFrameY.downSampleBy2());
        allocateVOLMembers(displayWidth, displayHeight);
        var scale = vopmd.rrvEnable == 1 ? 2 : 1;
        if (volmd.alphaUsage == VopPlanes.RECTANGLE) {
            if (uiSprite == 0 || uiSprite == 2) {
                rctCurrVOPY.set(0, 0, widthRound, heightRound);
            } else {
                rctCurrVOPY.set(rctSpt);
            }
            rctCurrVOPUV.set(rctCurrVOPY.downSampleBy2());
            rctRefVOPY0.set(rctCurrVOPY);
            rctRefVOPY0.expand(EXPANDY_REFVOP * scale);
            rctRefVOPUV0.set(rctRefVOPY0.downSampleBy2());
            rctRefVOPY1.set(rctRefVOPY0);
            rctRefVOPUV1.set(rctRefVOPUV0);
            computeVOLConstMembers();
        } else if (volmd.alphaUsage == VopPlanes.ONE_BIT) {
            rctCurrVOPY.set(uiSprite == 0 ? new Rct(0, 0, widthRound, heightRound) : rctSpt);
            rctCurrVOPUV.set(rctCurrVOPY.downSampleBy2());
            rctRefVOPY0.set(rctCurrVOPY);
            rctRefVOPY0.expand(EXPANDY_REFVOP);
            rctRefVOPUV0.set(rctRefVOPY0.downSampleBy2());
            rctRefVOPY1.set(rctRefVOPY0);
            rctRefVOPUV1.set(rctRefVOPUV0);
        }
        if (volmd.newpredEnable) {
            vopmd.numBitsVopId = Math.min(numBitsTimeIncr + 3, 15);
            newpred = new NewPred(numBitsTimeIncr, displayWidth, displayHeight, null, 0, volmd.newpredSegmentType,
                    volmd.shapeOnly, rctRefFrameY, rctRefFrameUV);
        }
        if (volmd.volType == VolMode.ENHN_LAYER) {
            allocateEnhancementBuffers();
        }
        vopmd.spriteXmitMode = VopMode.STOP;
    }

    /**
     * {@return the width of the pictures this layer outputs}
     */
    int displayWidth() {
        return displayWindow.width;
    }

    /**
     * {@return the height of the pictures this layer outputs}
     */
    int displayHeight() {
        return displayWindow.height();
    }

    VolMode volMode() {
        return volmd;
    }

    VopMode vopMode() {
        return vopmd;
    }

    int sprite() {
        return uiSprite;
    }

    int time() {
        return t;
    }

    /**
     * {@return the past reference VOP ({@code pvopcRefQPrev})}
     */
    VopPlanes pastReference() {
        return refQ0;
    }

    /**
     * {@return the future reference VOP ({@code pvopcRefQLater})}
     */
    VopPlanes futureReference() {
        return refQ1;
    }

    /**
     * Reads the time of the VOP that starts at a group of VOP or VOP start code without decoding it
     * ({@code senseTime}); the VOP type and the time bases of a group of VOP header are kept, as the reference
     * decoder keeps them.
     *
     * @param data the bytes, or {@code null} at the end of the layer
     * @param offset the index of the start code
     * @param length the length up to the next VOP
     * @return the time in ticks of the clock of the enhancement layer, or {@link #EOF}
     */
    int senseTime(byte[] data, int offset, int length) {
        if (data == null) {
            return EOF;
        }
        load(data, offset, length);
        if (!findStartCode(length)) {
            return EOF;
        }
        var code = bits.getBits(8);
        if (code == 0xB3) {
            decodeGovHeader();
            if (!findStartCode(length)) {
                return EOF;
            }
            code = bits.getBits(8);
        }
        if (code != 0xB6) {
            return EOF;
        }
        vopmd.predType = bits.getBits(2);
        var moduloInc = 0;
        while (bits.getBits(1) != 0) {
            if (++moduloInc > 1 << 16) {
                throw CorruptStream.INSTANCE;
            }
        }
        var fromDecoded = vopmd.predType != VopMode.BVOP || volmd.volType == VolMode.ENHN_LAYER;
        var currSec = moduloInc + (fromDecoded ? tModuloBaseDecd : tModuloBaseDisp);
        bits.getBits(1);
        var increment = numBitsTimeIncr != 0 ? bits.getBits(numBitsTimeIncr) : 0;
        return currSec * volmd.clockRate * clockRateScale + increment * clockRateScale;
    }

    /**
     * Makes the times of this base layer count in ticks of the clock of its enhancement layer
     * ({@code setClockRateScale}).
     */
    void setClockRateScale(ObjectDecoder enhancement) {
        if (volmd.clockRate == 0 || enhancement.volmd.clockRate % volmd.clockRate != 0) {
            throw CorruptStream.INSTANCE;
        }
        clockRateScale = enhancement.volmd.clockRate / volmd.clockRate;
    }

    /**
     * Makes this enhancement layer decode as the spatial enhancement of its reference layer at its own size, as the
     * reference decoder's spatial scalability mode sets it up.
     */
    void enableSpatialScalability() {
        volmd.spatialScalability = true;
        volmd.frmWidthSS = volWidth;
        volmd.frmHeightSS = volHeight;
    }

    /**
     * Makes this enhancement layer decode as a layer of temporal scalability ({@code bSpatialScalability} false).
     */
    void disableSpatialScalability() {
        volmd.spatialScalability = false;
    }

    /**
     * {@return the time of the VOP decoded last}
     */
    int lastTime() {
        return t;
    }

    /**
     * Gives this enhancement layer of object-based spatial scalability the shape of its reference layer VOP: the
     * shape modes and shape vectors of its macroblocks and its bounding rectangle ({@code setShapeMode},
     * {@code setmvBaseBY}, {@code setMBXYRef}, {@code setBaseRct}).
     */
    void takeBaseShape(int[] modes, MotionVector[] vectors, int mbx, int mby, Rct rect) {
        baseShapeModes = modes;
        mvBaseBY = vectors;
        numMBBaseXRef = mbx;
        numMBBaseYRef = mby;
        rctBase.set(rect);
    }

    /**
     * Reads the header of a VOP of an arbitrarily shaped layer, without decoding it, for its bounding rectangle.
     *
     * @param data the bytes
     * @param offset the index of the group of VOP or VOP start code
     * @param length the length up to the next VOP
     * @return the right and bottom edges of the VOP, or {@code null} when the VOP is not coded or carries no
     * bounding rectangle
     */
    int[] vopExtent(byte[] data, int offset, int length) {
        load(data, offset, length);
        if (!findStartCode(length)) {
            return null;
        }
        var code = bits.getBits(8);
        if (code == 0xB3) {
            decodeGovHeader();
            if (!findStartCode(length)) {
                return null;
            }
            code = bits.getBits(8);
        }
        if (code != 0xB6 || volmd.alphaUsage == VopPlanes.RECTANGLE) {
            return null;
        }
        if (!decodeVOPHead() || (uiSprite == 1 && vopmd.predType == VopMode.IVOP)) {
            return null;
        }
        return new int[]{rctCurrVOPY.left + vopWidthCoded, rctCurrVOPY.top + vopHeightCoded};
    }

    private void load(byte[] data, int offset, int length) {
        if (packet.length < length + TAIL.length) {
            packet = new byte[length + TAIL.length];
        }
        System.arraycopy(data, offset, packet, 0, length);
        System.arraycopy(TAIL, 0, packet, length, TAIL.length);
        bits.load(packet, 0, length + TAIL.length);
    }

    /**
     * Finds the next start code, skipping user data ({@code findStartCode}).
     *
     * @return whether a start code is ahead
     */
    private boolean findStartCode(int length) {
        bits.flush();
        boolean userData;
        do {
            userData = false;
            while (bits.peekBits(24) != 1) {
                bits.getBits(8);
                if (bits.position() >= (long) length * 8) {
                    return false;
                }
            }
            bits.getBits(24);
            if (bits.peekBits(8) == 0xB2) {
                userData = true;
            }
        } while (userData);
        return true;
    }

    /**
     * Decodes the VOP in a packet, preceded by any group of VOP header ({@code decode}).
     *
     * @param base the reconstructed VOP of the reference layer for spatial scalability, or {@code null}
     * @return {@link #CODED}, {@link #NOT_CODED} or {@link #EOF} when the packet holds no VOP
     */
    int decode(byte[] data, int offset, int length, VopPlanes base) {
        load(data, offset, length);
        if (uiSprite == 1 && !spriteInitialized) {
            spriteInitialized = true;
            if (sptMode == BASIC_SPRITE) {
                decodeInitSprite(length);
                return NOT_CODED;
            }
            initSpritePieces();
        }
        return decode(length, base);
    }

    private int decode(int length, VopPlanes base) {
        rrvScale = 1;
        if (!findStartCode(length)) {
            return EOF;
        }
        var code = bits.getBits(8);
        if (code == 0xB1) {
            return EOF;
        }
        if (code == 0xB3) {
            decodeGovHeader();
            if (!findStartCode(length)) {
                return EOF;
            }
            code = bits.getBits(8);
        }
        if (code != 0xB6) {
            return EOF;
        }
        var coded = decodeVOPHead();
        var prevRefCoded = codedFutureRef;
        if (vopmd.predType == VopMode.IVOP || vopmd.predType == VopMode.PVOP
                || (uiSprite == 2 && vopmd.predType == VopMode.SPRITE)) {
            codedFutureRef = coded;
        }
        if (vopmd.predType == VopMode.SPRITE && uiSprite == 1) {
            if (!coded) {
                return NOT_CODED;
            }
            decodeSpt();
            return CODED;
        }
        updateTimes(prevRefCoded, base);
        if (prevRefCoded) {
            if (volmd.volType == VolMode.BASE_LAYER) {
                updateAllRefVOPs();
            } else if (base == null && !volmd.spatialScalability) {
                updateRefVOPsEnhc();
            } else if (base != null && volmd.spatialScalability) {
                updateAllRefVOPs(base);
            }
        } else if (volmd.volType == VolMode.ENHN_LAYER && volmd.spatialScalability && base != null) {
            updateAllRefVOPs(base);
        }
        if (vopmd.predType == VopMode.IVOP && linkIsBroken && useGov) {
            linkIsBroken = false;
        }
        if (!coded) {
            if (vopmd.predType != VopMode.BVOP && volmd.alphaUsage != VopPlanes.RECTANGLE && prevRefCoded) {
                numMBX = numMBY = numMB = 1;
                saveShapeMode();
            }
            if (volmd.alphaUsage != VopPlanes.RECTANGLE) {
                numMBX = numMBY = numMB = 1;
                saveBaseShapeMode();
            }
            return NOT_CODED;
        }
        if (volmd.alphaUsage != VopPlanes.RECTANGLE) {
            validateVop();
            resetBYPlane();
            setRefStartingPointers();
            computeVOPMembers();
        }
        redefineVOLMembersRRV();
        decodeVOP();
        if (volmd.volType == VolMode.ENHN_LAYER && vopmd.predType == VopMode.BVOP && vopmd.refSelectCode == 0) {
            var temp = currQ;
            currQ = refQ1;
            refQ1 = temp;
        }
        if (currQ.alphaUsage == VopPlanes.RECTANGLE && currQ.alphaUsage != refQ1.alphaUsage) {
            currQ = new VopPlanes(volmd.alphaUsage, rctRefFrameY, volmd.auxCompCount);
        }
        var reference = vopmd.predType != VopMode.BVOP
                || (volmd.volType == VolMode.ENHN_LAYER && vopmd.refSelectCode == 0);
        if (reference) {
            if (volmd.alphaUsage != VopPlanes.RECTANGLE && prevRefCoded) {
                saveShapeMode();
            }
            if (volmd.alphaUsage != VopPlanes.RECTANGLE) {
                saveBaseShapeMode();
            }
            var modes = mbmd;
            mbmd = mbmdRef;
            mbmdRef = modes;
            var vectors = mv;
            mv = mvRef;
            mvRef = vectors;
            mvBackward = BVOP_MV_PER_REF_PER_MB * sessNumMB;
        } else if (volmd.volType == VolMode.BASE_LAYER && volmd.alphaUsage != VopPlanes.RECTANGLE) {
            saveBaseShapeMode();
        }
        if (volmd.alphaUsage != VopPlanes.RECTANGLE) {
            if (reference) {
                numMBRef = numMB;
                numMBXRef = numMBX;
                numMBYRef = numMBY;
                offsetForPadY = rctRefFrameY.offset(rctCurrVOPY.left, rctCurrVOPY.top);
                offsetForPadUV = rctRefFrameUV.offset(rctCurrVOPUV.left, rctCurrVOPUV.top);
                rctPrevNoExpandY.set(rctCurrVOPY);
                rctPrevNoExpandUV.set(rctCurrVOPUV);
                rctRefVOPY1.set(rctCurrVOPY);
                rctRefVOPY1.expand(EXPANDY_REFVOP);
                rctRefVOPUV1.set(rctCurrVOPUV);
                rctRefVOPUV1.expand(EXPANDUV_REFVOP);
                refQ1.setBoundRct(rctRefVOPY1);
            } else {
                if (volmd.volType == VolMode.BASE_LAYER && vopmd.predType == VopMode.BVOP && !volmd.shapeOnly) {
                    offsetForPadY = rctRefFrameY.offset(rctCurrVOPY.left, rctCurrVOPY.top);
                    offsetForPadUV = rctRefFrameUV.offset(rctCurrVOPUV.left, rctCurrVOPUV.top);
                    rctPrevNoExpandY.set(rctCurrVOPY);
                    rctPrevNoExpandUV.set(rctCurrVOPUV);
                }
                bvopOffsetForPadY = rctRefFrameY.offset(rctCurrVOPY.left, rctCurrVOPY.top);
                bvopOffsetForPadUV = rctRefFrameUV.offset(rctCurrVOPUV.left, rctCurrVOPUV.top);
                rctBVOPPrevNoExpandY.set(rctCurrVOPY);
                rctBVOPPrevNoExpandUV.set(rctCurrVOPUV);
                rctBVOPRefVOPY1.set(rctCurrVOPY);
                rctBVOPRefVOPY1.expand(EXPANDY_REFVOP);
                rctBVOPRefVOPUV1.set(rctCurrVOPUV);
                rctBVOPRefVOPUV1.expand(EXPANDUV_REFVOP);
            }
        } else {
            bvopOffsetForPadY = offsetForPadY;
            bvopOffsetForPadUV = offsetForPadUV;
            rctBVOPPrevNoExpandY.set(rctPrevNoExpandY);
            rctBVOPPrevNoExpandUV.set(rctPrevNoExpandUV);
            rctBVOPRefVOPY1.set(rctRefVOPY1);
            rctBVOPRefVOPUV1.set(rctRefVOPUV1);
        }
        if (reference) {
            if (!volmd.shapeOnly) {
                repeatPadYOrA(refQ1.y.pixels, offsetForPadY, refQ1);
                repeatPadUV(refQ1);
            }
            if (volmd.alphaUsage == VopPlanes.EIGHT_BIT) {
                for (var aux = 0; aux < volmd.auxCompCount; aux++) {
                    repeatPadYOrA(refQ1.a[aux].pixels, offsetForPadY, refQ1);
                }
            }
        }
        if (volmd.volType == VolMode.BASE_LAYER && vopmd.predType == VopMode.BVOP) {
            if (!volmd.shapeOnly) {
                repeatPadYOrA(currQ.y.pixels, offsetForPadY, currQ);
                repeatPadUV(currQ);
            }
            if (volmd.alphaUsage == VopPlanes.EIGHT_BIT) {
                for (var aux = 0; aux < volmd.auxCompCount; aux++) {
                    repeatPadYOrA(currQ.a[aux].pixels, offsetForPadY, currQ);
                }
            }
        }
        if (volmd.volType != VolMode.BASE_LAYER) {
            updateBuffVOPsEnhc();
        }
        return CODED;
    }

    /**
     * Rejects a VOP whose bounding box is empty or leaves the reference frames.
     */
    private void validateVop() {
        var r = rctCurrVOPY;
        if (r.width <= 0 || r.height() <= 0
                || r.left < rctRefFrameY.left + EXPANDY_REFVOP
                || r.top < rctRefFrameY.top + EXPANDY_REFVOP
                || r.right > rctRefFrameY.right - EXPANDY_REFVOP
                || r.bottom > rctRefFrameY.bottom - EXPANDY_REFVOP
                || r.width / MB_SIZE * (r.height() / MB_SIZE) > sessNumMB) {
            throw CorruptStream.INSTANCE;
        }
    }

    private void updateTimes(boolean prevRefCoded, VopPlanes base) {
        if (volmd.volType == VolMode.BASE_LAYER) {
            if (vopmd.predType == VopMode.IVOP || vopmd.predType == VopMode.PVOP
                    || (uiSprite == 2 && vopmd.predType == VopMode.SPRITE)) {
                if (prevRefCoded) {
                    tPastRef = tFutureRef;
                }
                tFutureRef = t;
                bCount = 0;
            }
            if (vopmd.predType == VopMode.BVOP) {
                bCount++;
            }
        } else if (base != null) {
            if (vopmd.predType == VopMode.BVOP && vopmd.refSelectCode == 0) {
                if (prevRefCoded) {
                    tPastRef = tFutureRef;
                }
                tFutureRef = t;
                bCount = 0;
            }
            if (vopmd.predType == VopMode.PVOP && vopmd.refSelectCode == 3) {
                tPastRef = t;
                tFutureRef = t;
                bCount = 0;
            }
            if (vopmd.predType == VopMode.BVOP) {
                bCount++;
            }
        }
    }

    /**
     * Sets the macroblock counts and strides of a reduced resolution VOP ({@code redefineVOLMembersRRV}).
     */
    private void redefineVOLMembersRRV() {
        rrvScale = vopmd.rrvOnOff == 1 ? 2 : 1;
        numMBX = vopWidthY / MB_SIZE / rrvScale;
        numMBY = rctCurrVOPY.height() / MB_SIZE / rrvScale;
        frameWidthYxMBSize = MB_SIZE * rrvScale * refQ0.whereY.width;
        frameWidthYxBlkSize = BLOCK_SIZE * rrvScale * refQ0.whereY.width;
        frameWidthUVxBlkSize = BLOCK_SIZE * rrvScale * refQ0.whereUV.width;
        numMB = numMBX * numMBY;
        numOfTotalMVPerRow = PVOP_MV_PER_REF_PER_MB * numMBX;
    }

    /**
     * Decodes the initial sprite of a static sprite layer ({@code decodeInitSprite}).
     */
    private void decodeInitSprite(int length) {
        var sptWidth = (rctSpt.width + MB_SIZE - 1) / MB_SIZE * MB_SIZE;
        var sptHeight = (rctSpt.height() + MB_SIZE - 1) / MB_SIZE * MB_SIZE;
        rctCurrVOPY.set(0, 0, sptWidth, sptHeight);
        rctCurrVOPUV.set(rctCurrVOPY.downSampleBy2());
        if (decode(length, null) == EOF) {
            throw CorruptStream.INSTANCE;
        }
        if (numOfPnts > 0) {
            swapRefQ1toSpt();
        }
    }

    @Override
    void decodeBackwardShapes() {
        // TODO: decode the backward and forward shapes of temporal scalability with enhancement_type 1
        //  (load_backward_shape, 6.2.5) and compose the background (7.9.2.2).
        throw new UnsupportedTool("mpeg-4 temporal scalability with background composition");
    }

    @Override
    void decodeVopForPiece() {
        decodeVOP();
    }

    @Override
    void newpredHeader(boolean vopHeader) {
        newpred.getRef(vopHeader, vopmd.predType, vopmd.vopId, vopmd.vopId4PredictionIndication != 0,
                vopmd.vopId4Prediction);
    }

    @Override
    void newpredVopId() {
        vopmd.vopId = bits.getBits(vopmd.numBitsVopId);
        vopmd.vopId4PredictionIndication = bits.getBits(1);
        if (vopmd.vopId4PredictionIndication != 0) {
            vopmd.vopId4Prediction = bits.getBits(vopmd.numBitsVopId);
        }
        bits.getBits(1);
        newpredHeader(false);
    }

    private int sliceRowY(int mby) {
        return EXPANDY_REF_FRAME * rctRefFrameY.width + mby * MB_SIZE * rrvScale * rctRefFrameY.width;
    }

    private int sliceRowUV(int mby) {
        return EXPANDY_REF_FRAME / 2 * rctRefFrameUV.width + mby * BLOCK_SIZE * rrvScale * rctRefFrameUV.width;
    }

    private void substituteSlice(int sliceNumber, int mbx, int mby) {
        newpred.copyNPtoVM(sliceNumber, sliceRowY(mby), sliceRowUV(mby), sliceRowUV(mby));
        newpred.changeRefOfSlice(refQ0, startInRefToCurrRctY + mby * MB_SIZE * rrvScale * rctRefFrameY.width,
                startInRefToCurrRctUV + mby * BLOCK_SIZE * rrvScale * rctRefFrameUV.width, mbx * rrvScale,
                mby * rrvScale);
        repeatPadYOrA(refQ0.y.pixels, offsetForPadY, refQ0);
        repeatPadUV(refQ0);
    }

    @Override
    void newpredBeginPVop() {
        newpred.copyRefToBuf(refQ0);
        substituteSlice(videoPacketNumber, 0, 0);
        for (var mby = 0; mby < numMBY; mby++) {
            for (var mbx = 0; mbx < numMBX; mbx++) {
                mbmd[mbx + numMBX * mby].npSegmentNumber = newpred.sliceNumber(mbx * rrvScale, mby * rrvScale);
            }
        }
    }

    @Override
    void newpredResync(int mbx, int mby) {
        substituteSlice(videoPacketNumber, mbx, mby);
    }

    @Override
    void newpredEndPVop() {
        newpred.setCurrent(refQ1);
        for (var i = 0; i < newpred.numSlice; i++) {
            newpred.makeNextDecBuf(newpred.currentVopId(), i);
        }
        for (var i = 0; i < newpred.numSlice; i++) {
            var mby = newpred.nowMBA(i) / (newpred.width / MB_SIZE);
            var y = startInRefToCurrRctY - EXPANDY_REF_FRAME + mby * MB_SIZE * rctRefFrameY.width;
            var uv = startInRefToCurrRctUV - EXPANDUV_REF_FRAME + mby * BLOCK_SIZE * rctRefFrameUV.width;
            newpred.copyNPtoPrev(i, refQ0.y.pixels, y, refQ0.u.pixels, uv, refQ0.v.pixels, uv);
        }
        repeatPadYOrA(refQ0.y.pixels, offsetForPadY, refQ0);
        repeatPadUV(refQ0);
    }

    @Override
    void newpredEndIVop(int[] slicePoints, int count) {
        var id = newpred.currentVopId();
        newpred = new NewPred(numBitsTimeIncr, numMBX * MB_SIZE * rrvScale, numMBY * MB_SIZE * rrvScale,
                slicePoints, count, volmd.newpredSegmentType, volmd.shapeOnly, rctRefFrameY, rctRefFrameUV);
        newpred.resetVopId(id);
        newpred.setCurrent(refQ1);
        for (var i = 0; i < newpred.numSlice; i++) {
            newpred.makeNextDecBuf(newpred.currentVopId(), i);
        }
    }
}
