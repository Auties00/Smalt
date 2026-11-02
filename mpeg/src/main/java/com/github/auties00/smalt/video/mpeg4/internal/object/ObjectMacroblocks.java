/* Ports the Microsoft MPEG-4 Visual reference software of ISO/IEC 14496-5:2001 (version 2 FDAM1-2.3-001213,
 * sys/decoder/vopmbdec.cpp, mbinterlacedec.cpp and the video packet header of errdec.cpp, without the short video
 * header). Copyright 1996-2000 Microsoft Corporation, Toshiba Corporation, Sharp Corporation, NextLevel Systems,
 * Hyundai Electronics, Samsung AIT, Fraunhofer HHI, NTT, Hitachi and the contributors named in the sources; ISO/IEC
 * free license for products conforming to ISO/IEC 14496-2. Modified for Smalt. */
package com.github.auties00.smalt.video.mpeg4.internal.object;

import com.github.auties00.smalt.MediaException;

import java.util.Arrays;

/**
 * The macroblock loops of I-, P- (and GMC sprite) and B-VOPs, with and without shape, and the video packet headers
 * (6.3.5, 7.3); data partitioned VOPs, sprite pieces and NEWPRED are delegated to the subclasses through hooks.
 */
abstract class ObjectMacroblocks extends ObjectShapeDecoder {
    private static final int NUMBITS_VP_RESYNC_MARKER = 17;
    private static final int[] FIELD_SHUFFLE_C = {1, 0, 8, 1, 4, 8, 2, 4, 0, 2, 3, 0, 9, 3, 12, 9, 6, 12, 0, 6, 5,
            0, 10, 5, 0, 10, 7, 0, 11, 7, 13, 11, 14, 13, 0, 14};
    private static final int[] FIELD_SHUFFLE_I = {16, 0, 128, 16, 64, 128, 32, 64, 0, 32, 48, 0, 144, 48, 192, 144,
            96, 192, 0, 96, 80, 0, 160, 80, 0, 160, 112, 0, 176, 112, 208, 176, 224, 208, 0, 224};

    int[] rightMBBY = new int[MB_SQUARE_SIZE];
    int[] rightMBBUV = new int[BLOCK_SQUARE_SIZE];
    int videoPacketNumber;
    int oldModuloBaseDecd;
    int oldModuloBaseDisp;
    final int[] uv = new int[4];
    final int[] gmcVector = new int[4];
    private final int[] fieldTmp = new int[MB_SIZE];
    private final boolean[] restart = new boolean[1];
    int[] alphaOffsets = new int[0];
    /**
     * The quantizers of the auxiliary components of the VOP being decoded.
     */
    private final int[] qpAlpha = new int[VolMode.MAX_MAC];
    /**
     * The buffer of {@link #slicePoints()}.
     */
    private int[] slicePointBuffer = new int[0];

    /**
     * {@return whether a sprite piece macroblock is to be decoded} The default decodes every macroblock.
     */
    boolean spritePieceNotHole(int mbx, int mby, int mbIndex) {
        return true;
    }

    /**
     * {@return whether a sprite update macroblock is to be decoded} The default decodes every macroblock.
     */
    boolean spriteUpdateNotHole(int mbx, int mby, int mbIndex) {
        return true;
    }

    void restoreMBmCurrRow(int mby) {
    }

    void saveMBmCurrRow(int mby) {
    }

    /**
     * Copies a macroblock of the current VOP to the prediction buffers ({@code CopyCurrQToPred}).
     */
    void copyCurrQToPred(int offY, int offUV) {
    }

    void decodeWarpPoints() {
        throw CorruptStream.INSTANCE;
    }

    /**
     * Prepares the NEWPRED reference of the VOP and of its slices; the default does nothing.
     */
    void newpredBeginPVop() {
    }

    void newpredResync(int mbx, int mby) {
    }

    void newpredEndPVop() {
    }

    void newpredEndIVop(int[] slicePoints, int count) {
    }

    void newpredVopId() {
        throw CorruptStream.INSTANCE;
    }

    abstract void decodeIVOP_DataPartitioning();

    abstract void decodePVOP_DataPartitioning();

    abstract void decodeIVOP_WithShape_DataPartitioning();

    abstract void decodePVOP_WithShape_DataPartitioning();

    final void swapCurrAndRightMBForShape() {
        var by = currMBBY;
        currMBBY = rightMBBY;
        rightMBBY = by;
        var buv = currMBBUV;
        currMBBUV = rightMBBUV;
        rightMBBUV = buv;
    }

    final boolean spriteActive() {
        return uiSprite == 1 && vopmd.spriteXmitMode != VopMode.STOP;
    }

    final boolean piecewiseSprite() {
        return uiSprite == 1 && sptMode != BASIC_SPRITE && vopmd.spriteXmitMode != VopMode.STOP;
    }

    final boolean predictedLike() {
        return vopmd.predType == VopMode.PVOP || (uiSprite == 2 && vopmd.predType == VopMode.SPRITE);
    }

    private int resyncMarkerLength() {
        var bits = NUMBITS_VP_RESYNC_MARKER;
        if (!volmd.shapeOnly) {
            if (predictedLike()) {
                bits += vopmd.mvInfoForward.fCode - 1;
            } else if (vopmd.predType == VopMode.BVOP) {
                bits += Math.max(vopmd.mvInfoForward.fCode, vopmd.mvInfoBackward.fCode) - 1;
            }
        }
        return bits;
    }

    /**
     * {@return whether a resync marker follows the stuffing up to the next byte boundary ({@code checkResyncMarker})}
     */
    final boolean checkResyncMarker() {
        var count = bits.bitsTillByteAlign();
        var stuffed = bits.peekBits(count);
        if (stuffed == (1 << (count - 1)) - 1) {
            return bits.peekBitsFromByteAlign(resyncMarkerLength()) == 1;
        }
        return false;
    }

    /**
     * {@return whether a start code follows the stuffing up to the next byte boundary ({@code checkStartCode})}
     */
    final boolean checkStartCode() {
        var count = bits.bitsTillByteAlign();
        var stuffed = bits.peekBits(count);
        if (stuffed == (1 << (count - 1)) - 1) {
            return bits.peekBitsFromByteAlign(24) == 1;
        }
        return false;
    }

    private static int signedField(InBits bits, int length) {
        return bits.getBits(1) == 0 ? bits.getBits(length - 1) : bits.getBits(length - 1) - (1 << (length - 1));
    }

    /**
     * Decodes a video packet header (6.2.5.2, {@code decodeVideoPacketHeader}).
     *
     * @return the quantizer of the packet, or {@code qp} for a shape-only layer
     */
    final int decodeVideoPacketHeader(int qp) {
        bits.flush(8);
        bits.getBits(resyncMarkerLength());
        var numOfMB = numMBX * numMBY * rrvScale * rrvScale;
        if (numOfMB <= 0) {
            throw CorruptStream.INSTANCE;
        }
        var length = 0;
        for (var v = numOfMB - 1; v != 0; length++) {
            v >>= 1;
        }
        var hec = 0;
        if (volmd.alphaUsage != VopPlanes.RECTANGLE) {
            hec = bits.getBits(1);
            if (hec != 0 && !(uiSprite == 1 && vopmd.predType == VopMode.IVOP)) {
                bits.getBits(13);
                bits.getBits(1);
                bits.getBits(13);
                bits.getBits(1);
                signedField(bits, 13);
                bits.getBits(1);
                signedField(bits, 13);
                bits.getBits(1);
            }
        }
        var mbNumber = numOfMB > 1 ? bits.getBits(length) : 0;
        vpMBnum = mbNumber;
        if (!volmd.shapeOnly) {
            qp = bits.getBits(volmd.quantPrecision);
        }
        if (volmd.alphaUsage == VopPlanes.RECTANGLE) {
            hec = bits.getBits(1);
        }
        if (hec != 0) {
            var moduloBits = 0;
            while (bits.getBits(1) != 0) {
                moduloBits++;
            }
            if (moduloBits > 60) {
                throw CorruptStream.INSTANCE;
            }
            bits.getBits(1);
            if (numBitsTimeIncr != 0) {
                bits.getBits(numBitsTimeIncr);
            }
            bits.getBits(1);
            if (bits.getBits(2) != vopmd.predType) {
                throw CorruptStream.INSTANCE;
            }
            if (volmd.alphaUsage != VopPlanes.RECTANGLE) {
                volmd.noCrChange = bits.getBits(1) != 0;
                if (!volmd.shapeOnly && vopmd.predType != VopMode.IVOP) {
                    vopmd.shapeCodingType = bits.getBits(1) != 0;
                }
            }
            if (!volmd.shapeOnly) {
                bits.getBits(3);
                if (uiSprite == 2 && vopmd.predType == VopMode.SPRITE && numOfPnts > 0) {
                    decodeWarpPoints();
                }
                if (volmd.reducedResolutionVopEnable && volmd.alphaUsage == VopPlanes.RECTANGLE
                        && (vopmd.predType == VopMode.PVOP || vopmd.predType == VopMode.IVOP)) {
                    bits.getBits(1);
                }
                if (predictedLike()) {
                    bits.getBits(3);
                } else if (vopmd.predType == VopMode.BVOP) {
                    bits.getBits(3);
                    bits.getBits(3);
                }
            }
        }
        if (volmd.newpredEnable) {
            newpredVopId();
        }
        return qp;
    }

    /**
     * Restores the frame order of the rows of a field DCT macroblock of the current VOP ({@code fieldDCTtoFrameC}).
     */
    final void fieldDCTtoFrameC(int[] plane, int off) {
        var tmp = fieldTmp;
        for (var i = 0; i < FIELD_SHUFFLE_C.length; i += 2) {
            var dst = FIELD_SHUFFLE_C[i + 1];
            var src = FIELD_SHUFFLE_C[i];
            if (dst != 0 && src != 0) {
                System.arraycopy(plane, off + src * frameWidthY, plane, off + dst * frameWidthY, MB_SIZE);
            } else if (dst != 0) {
                System.arraycopy(tmp, 0, plane, off + dst * frameWidthY, MB_SIZE);
            } else {
                System.arraycopy(plane, off + src * frameWidthY, tmp, 0, MB_SIZE);
            }
        }
    }

    /**
     * Restores the frame order of the rows of the residual of a field DCT macroblock ({@code fieldDCTtoFrameI}).
     */
    final void fieldDCTtoFrameI(int[] error) {
        var tmp = fieldTmp;
        for (var i = 0; i < FIELD_SHUFFLE_I.length; i += 2) {
            var dst = FIELD_SHUFFLE_I[i + 1];
            var src = FIELD_SHUFFLE_I[i];
            if (dst != 0 && src != 0) {
                System.arraycopy(error, src, error, dst, MB_SIZE);
            } else if (dst != 0) {
                System.arraycopy(tmp, 0, error, dst, MB_SIZE);
            } else {
                System.arraycopy(error, src, tmp, 0, MB_SIZE);
            }
        }
    }

    /**
     * Decodes the macroblocks of the VOP whose header was just decoded ({@code decodeVOP}).
     */
    final void decodeVOP() {
        if (volmd.alphaUsage != VopPlanes.RECTANGLE) {
            if (volmd.dataPartitioning && !volmd.shapeOnly) {
                if (predictedLike()) {
                    decodePVOP_WithShape_DataPartitioning();
                } else if (vopmd.predType == VopMode.IVOP) {
                    decodeIVOP_WithShape_DataPartitioning();
                } else {
                    decodeBVOP_WithShape();
                }
            } else if (predictedLike()) {
                if (spriteActive()) {
                    decodePVOP();
                } else {
                    decodePVOP_WithShape();
                }
            } else if (vopmd.predType == VopMode.IVOP) {
                decodeIVOP_WithShape();
            } else {
                decodeBVOP_WithShape();
            }
        } else if (volmd.dataPartitioning) {
            if (predictedLike()) {
                decodePVOP_DataPartitioning();
            } else if (vopmd.predType == VopMode.IVOP) {
                decodeIVOP_DataPartitioning();
            } else {
                decodeBVOP();
            }
        } else if (predictedLike()) {
            decodePVOP();
        } else if (vopmd.predType == VopMode.IVOP) {
            decodeIVOP();
        } else {
            decodeBVOP();
        }
    }

    final int[] alphaOffsets(int mbx, int mby) {
        if (alphaOffsets.length != volmd.auxCompCount) {
            alphaOffsets = new int[volmd.auxCompCount];
        }
        Arrays.fill(alphaOffsets, startInRefToCurrRctY + mby * frameWidthYxMBSize + mbx * MB_SIZE);
        return alphaOffsets;
    }

    /**
     * {@return the buffer of the first macroblocks of the slices of an intra VOP, of one entry per macroblock and one
     * more, whose first entry is 0}
     */
    final int[] slicePoints() {
        var count = numMBX * numMBY * rrvScale * rrvScale + 1;
        if (slicePointBuffer.length < count) {
            slicePointBuffer = new int[count];
        }
        slicePointBuffer[0] = 0;
        return slicePointBuffer;
    }

    final int qpAlphaInit(int[] values, int[] vopValues) {
        System.arraycopy(vopValues, 0, values, 0, volmd.auxCompCount);
        return 0;
    }

    /**
     * Decodes the macroblocks of an intra VOP with shape ({@code decodeIVOP_WithShape}).
     */
    final void decodeIVOP_WithShape() {
        MotionVector.zero(mv, 0, numMB * PVOP_MV_PER_REF_PER_MB);
        var ref = refQ1;
        mbmd[0].fieldDCT = false;
        var qp = vopmd.intStepI;
        var qpA = qpAlpha;
        qpAlphaInit(qpA, vopmd.intStepIAlpha);
        videoPacketNumber = 0;
        vpMBnum = 0;
        restart[0] = true;
        for (var i = 0; i < numMB; i++) {
            var v = mvBaseBY[i];
            v.mvx = 0;
            v.mvy = 0;
            v.halfX = 0;
            v.halfY = 0;
        }
        if (volmd.volType == VolMode.BASE_LAYER) {
            rctBase.set(rctCurrVOPY);
        }
        var mbIndex = 0;
        for (var mby = 0; mby < numMBY; mby++) {
            var offY = startInRefToCurrRctY + mby * frameWidthYxMBSize;
            var offUV = startInRefToCurrRctUV + mby * frameWidthUVxBlkSize;
            var notHole = true;
            if (piecewiseSprite()) {
                notHole = spritePieceNotHole(0, mby, mbIndex);
                restoreMBmCurrRow(mby);
            }
            for (var mbx = 0; mbx < numMBX; mbx++) {
                var offsets = alphaOffsets(mbx, mby);
                if (notHole) {
                    var mode = mbmd[mbIndex];
                    if (checkResyncMarker()) {
                        qp = decodeVideoPacketHeader(qp);
                        videoPacketNumber++;
                        restart[0] = true;
                    }
                    mode.videoPacketNumber = videoPacketNumber;
                    decodeIntraShape(mode, mbIndex, mbx, mby, currMBBY, ref.by.pixels, offY);
                    downSampleBY(currMBBY, currMBBUV, mode);
                    if (!volmd.shapeOnly) {
                        mode.padded = false;
                        if (mode.transpStatus[0] != MbMode.ALL) {
                            var sadct = !volmd.sadctDisable;
                            if (sadct) {
                                deriveSADCTRowLengths(currMBBY, currMBBUV, mode.transpStatus);
                            }
                            qp = decodeMBTextureHeadOfIVOP(mode, qp, restart);
                            decodeTextureIntraMB(mode, mbIndex, mbx, mby, ref.y.pixels, offY, ref.u.pixels,
                                    ref.v.pixels, offUV, sadct ? currMBBY : null, sadct ? currMBBUV : null);
                            if (mode.transpStatus[0] == MbMode.NONE && vopmd.interlace && mode.fieldDCT) {
                                fieldDCTtoFrameC(ref.y.pixels, offY);
                            }
                            if (volmd.alphaUsage == VopPlanes.EIGHT_BIT) {
                                for (var aux = 0; aux < volmd.auxCompCount; aux++) {
                                    decodeMBAlphaHeadOfIVOP(mode, qp, qpA[aux], vopmd.intStepI,
                                            vopmd.intStepIAlpha[aux], aux);
                                    decodeAlphaIntraMB(mode, mbIndex, mbx, mby, ref.a[aux].pixels, offsets[aux], aux,
                                            sadct ? currMBBY : null);
                                }
                            }
                            if ((uiSprite == 0 || uiSprite == 2 || sptMode == BASIC_SPRITE) && !vopmd.interlace) {
                                if (mode.transpStatus[0] == MbMode.PARTIAL) {
                                    mcPadCurrMB(ref, offY, offUV, offsets);
                                }
                                padNeighborTranspMBs(mbx, mby, mbIndex, ref, offY, offUV, offsets);
                            }
                        } else if ((uiSprite == 0 || uiSprite == 2 || sptMode == BASIC_SPRITE)
                                && !vopmd.interlace) {
                            padCurrAndTopTranspMBFromNeighbor(mbx, mby, mbIndex, ref, offY, offUV, offsets);
                        }
                    }
                }
                mbIndex++;
                offY += MB_SIZE;
                offUV += BLOCK_SIZE;
                notHole = !piecewiseSprite() || spritePieceNotHole(mbx + 1, mby, mbIndex);
            }
            if (uiSprite == 1 && sptMode != BASIC_SPRITE) {
                saveMBmCurrRow(mby);
            }
            swapBlockMemoryRows();
        }
        if (vopmd.interlace && !volmd.shapeOnly) {
            fieldBasedMCPadding(0, refQ1);
        }
    }

    /**
     * Decodes the macroblocks of a rectangular intra VOP ({@code decodeIVOP}).
     */
    final void decodeIVOP() {
        var slicePoints = slicePoints();
        var sliceCount = 1;
        var segmentedSlices = true;
        MotionVector.zero(mv, 0, numMB * PVOP_MV_PER_REF_PER_MB);
        var ref = refQ1;
        mbmd[0].stepSize = vopmd.intStepI;
        mbmd[0].fieldDCT = false;
        var qp = vopmd.intStepI;
        videoPacketNumber = 0;
        vpMBnum = 0;
        restart[0] = true;
        var mbIndex = 0;
        rows:
        for (var mby = 0; mby < numMBY; mby++) {
            var offY = startInRefToCurrRctY + mby * frameWidthYxMBSize;
            var offUV = startInRefToCurrRctUV + mby * frameWidthUVxBlkSize;
            var notHole = true;
            if (piecewiseSprite()) {
                notHole = spritePieceNotHole(0, mby, mbIndex);
                restoreMBmCurrRow(mby);
            }
            for (var mbx = 0; mbx < numMBX; mbx++) {
                if (notHole) {
                    var mode = mbmd[mbIndex];
                    skipAnyStuffing();
                    if (checkResyncMarker()) {
                        qp = decodeVideoPacketHeader(qp);
                        videoPacketNumber++;
                        restart[0] = true;
                        if (volmd.newpredEnable && !volmd.newpredSegmentType) {
                            slicePoints[sliceCount++] = (mby * rrvScale) * (numMBX * rrvScale) + mbx * rrvScale;
                        } else {
                            segmentedSlices = false;
                        }
                    }
                    mode.videoPacketNumber = videoPacketNumber;
                    qp = decodeMBTextureHeadOfIVOP(mode, qp, restart);
                    decodeTextureIntraMB(mode, mbIndex, mbx, mby, ref.y.pixels, offY, ref.u.pixels, ref.v.pixels,
                            offUV, null, null);
                    if (vopmd.interlace && mode.fieldDCT) {
                        fieldDCTtoFrameC(ref.y.pixels, offY);
                    }
                }
                mbIndex++;
                offY += MB_SIZE * rrvScale;
                offUV += BLOCK_SIZE * rrvScale;
                notHole = !piecewiseSprite() || spritePieceNotHole(mbx + 1, mby, mbIndex);
            }
            if (checkStartCode()) {
                break rows;
            }
            if (uiSprite == 1 && sptMode != BASIC_SPRITE) {
                saveMBmCurrRow(mby);
            }
            swapBlockMemoryRows();
        }
        skipAnyStuffing();
        if (vopmd.rrvOnOff == 1) {
            filterRrv(refQ1);
        }
        if (volmd.newpredEnable) {
            newpredEndIVop(segmentedSlices ? slicePoints : null, sliceCount);
        }
    }

    private final int[][] rrvScratch = new int[3][];

    final void filterRrv(VopPlanes vop) {
        RrvUtil.filterCodedPicture(vop.y.pixels, startInRefToCurrRctY, vop.u.pixels, vop.v.pixels,
                startInRefToCurrRctUV, vopWidthY, rctCurrVOPY.height(), numMBX, numMBY, refQ0.whereY.width,
                refQ0.whereUV.width, mbmd, !volmd.newpredEnable || volmd.newpredSegmentType, rrvScratch);
    }

    private void gmcVectors(int mvOff, int x, int y) {
        globalMv(gmcVector, x, y, vopmd.mvInfoForward.range, volmd.quarterSample);
        var v = mv[mvOff];
        v.setParts(gmcVector[0], gmcVector[1], gmcVector[2], gmcVector[3]);
        v.computeMV();
        for (var i = 1; i < PVOP_MV_PER_REF_PER_MB; i++) {
            mv[mvOff + i].set(v);
        }
    }

    private void decodeShapeOfPMacroblock(MbMode mode, int mbIndex, int mbx, int mby, int x, int y, int mvOff,
                                          int[] mbBY, int frmOff) {
        var frm = refQ1.by.pixels;
        if (vopmd.shapeCodingType) {
            if (!(volmd.spatialScalability && volmd.hierarchyType == 0)) {
                var colocated = mbmdRef[Math.min(Math.max(0, mbx), numMBXRef - 1)
                        + Math.min(Math.max(0, mby), numMBYRef - 1) * numMBXRef].shpmd;
                decodeInterShape(refQ0, mode, mbIndex, mbx, mby, x, y, mvOff, mbBY, frm, frmOff, colocated);
            } else if (volmd.volType == VolMode.ENHN_LAYER && volmd.enhnType == 1 && volmd.useRefShape == 1) {
                if (mbx == 0) {
                    mv[mvOff].setToZero();
                    mvBY[mbIndex].setToZero();
                }
                decodeInterShape(refQ0, mode, mbIndex, mbx, mby, x, y, mvOff, mbBY, frm, frmOff,
                        MbMode.ALL_OPAQUE);
            } else if (volmd.volType == VolMode.BASE_LAYER
                    || (!(volmd.enhnType == 0 || volmd.useRefShape == 0) && !volmd.shapeOnly)) {
                var colocated = mbmdRef[Math.min(Math.max(0, mbx), numMBXRef - 1)
                        + Math.min(Math.max(0, mby), numMBYRef - 1) * numMBXRef].shpmd;
                decodeInterShape(refQ0, mode, mbIndex, mbx, mby, x, y, mvOff, mbBY, frm, frmOff, colocated);
            } else if (volmd.volType == VolMode.ENHN_LAYER) {
                decodeSIShapePVOP(refQ0, mode, mbx, mby, x, y, mbBY, frm, frmOff);
            }
        } else {
            decodeIntraShape(mode, mbIndex, mbx, mby, mbBY, frm, frmOff);
        }
    }

    private void decodeHeaderAndVectorsWithShape(MbMode mode, int mbIndex, int mbx, int mby, int x, int y,
                                                 int mvOff, int qp, boolean[] restart, int[] qpOut) {
        qpOut[0] = qp;
        if (mode.transpStatus[0] != MbMode.ALL && !volmd.shapeOnly) {
            qpOut[0] = decodeMBTextureHeadOfPVOP(mode, qp, restart);
            if (!mode.mcsel) {
                decodeMVWithShape(mode, mbx, mby, mvOff, mbIndex);
            } else {
                gmcVectors(mvOff, x, y);
            }
            if (mode.has4MVForward) {
                padMotionVectors(mode, mv, mvOff);
            }
        }
    }

    private final int[] qpHolder = new int[1];

    /**
     * Decodes the macroblocks of a predicted VOP with shape ({@code decodePVOP_WithShape}).
     */
    final void decodePVOP_WithShape() {
        var y = rctCurrVOPY.top;
        if (volmd.volType == VolMode.BASE_LAYER) {
            mvBaseBY = mvBY;
            rctBase.set(rctCurrVOPY);
        }
        var ref = refQ1;
        var qp = vopmd.intStep;
        var qpA = qpAlpha;
        qpAlphaInit(qpA, vopmd.intStepPAlpha);
        videoPacketNumber = 0;
        vpMBnum = 0;
        restart[0] = true;
        var mbIndex = 0;
        for (var mby = 0; mby < numMBY; mby++, y += MB_SIZE) {
            var offY = startInRefToCurrRctY + mby * frameWidthYxMBSize;
            var offUV = startInRefToCurrRctUV + mby * frameWidthUVxBlkSize;
            var mvOff = mbIndex * PVOP_MV_PER_REF_PER_MB;
            if (mby != 0 && checkResyncMarker()) {
                qp = decodeVideoPacketHeader(qp);
                videoPacketNumber++;
                restart[0] = true;
            }
            var first = mbmd[mbIndex];
            first.videoPacketNumber = videoPacketNumber;
            decodeShapeOfPMacroblock(first, mbIndex, 0, mby, rctCurrVOPY.left, y, mvOff, currMBBY, offY);
            downSampleBY(currMBBY, currMBBUV, first);
            decodeHeaderAndVectorsWithShape(first, mbIndex, 0, mby, rctCurrVOPY.left, y, mvOff, qp, restart,
                    qpHolder);
            qp = qpHolder[0];
            var x = rctCurrVOPY.left;
            for (var mbx = 0; mbx < numMBX; mbx++, x += MB_SIZE) {
                var mode = mbmd[mbIndex];
                var offsets = alphaOffsets(mbx, mby);
                mode.padded = false;
                var sadct = !volmd.sadctDisable;
                if (mode.transpStatus[0] != MbMode.ALL && !volmd.shapeOnly) {
                    if (sadct) {
                        deriveSADCTRowLengths(currMBBY, currMBBUV, mode.transpStatus);
                    }
                    if ((mode.dctMd == MbMode.INTRA || mode.dctMd == MbMode.INTRAQ) && !mode.skip) {
                        decodeTextureIntraMB(mode, mbIndex, mbx, mby, ref.y.pixels, offY, ref.u.pixels,
                                ref.v.pixels, offUV, sadct ? currMBBY : null, sadct ? currMBBUV : null);
                        if (mode.transpStatus[0] == MbMode.NONE && vopmd.interlace && mode.fieldDCT) {
                            fieldDCTtoFrameC(ref.y.pixels, offY);
                        }
                        if (volmd.alphaUsage == VopPlanes.EIGHT_BIT) {
                            for (var aux = 0; aux < volmd.auxCompCount; aux++) {
                                decodeMBAlphaHeadOfPVOP(mode, qp, qpA[aux], aux);
                                decodeAlphaIntraMB(mode, mbIndex, mbx, mby, ref.a[aux].pixels, offsets[aux], aux,
                                        sadct ? currMBBY : null);
                            }
                        }
                    } else {
                        if (!mode.skip) {
                            decodeTextureInterMB(mode, sadct ? currMBBY : null, sadct ? currMBBUV : null);
                            if (mode.transpStatus[0] == MbMode.NONE && vopmd.interlace && mode.fieldDCT) {
                                fieldDCTtoFrameI(errorMBY);
                            }
                        }
                        if (volmd.alphaUsage == VopPlanes.EIGHT_BIT) {
                            for (var aux = 0; aux < volmd.auxCompCount; aux++) {
                                decodeMBAlphaHeadOfPVOP(mode, qp, qpA[aux], aux);
                                decodeAlphaInterMB(mode, ref.a[aux].pixels, offsets[aux], aux,
                                        sadct ? currMBBY : null);
                            }
                        }
                    }
                }
                if (mbx != numMBX - 1) {
                    var right = mbmd[mbIndex + 1];
                    if (checkResyncMarker()) {
                        qp = decodeVideoPacketHeader(qp);
                        videoPacketNumber++;
                        restart[0] = true;
                    }
                    right.videoPacketNumber = videoPacketNumber;
                    decodeShapeOfPMacroblock(right, mbIndex + 1, mbx + 1, mby, x + MB_SIZE, y,
                            mvOff + PVOP_MV_PER_REF_PER_MB, rightMBBY, offY + MB_SIZE);
                    downSampleBY(rightMBBY, rightMBBUV, right);
                    decodeHeaderAndVectorsWithShape(right, mbIndex + 1, mbx + 1, mby, x + MB_SIZE, y,
                            mvOff + PVOP_MV_PER_REF_PER_MB, qp, restart, qpHolder);
                    qp = qpHolder[0];
                }
                if (!volmd.shapeOnly) {
                    if (mode.transpStatus[0] != MbMode.ALL) {
                        if (mode.dctMd == MbMode.INTER || mode.dctMd == MbMode.INTERQ) {
                            reconstructInterWithShape(mode, mbIndex, mbx, mby, x, y, mvOff, offY, offUV, offsets);
                        }
                        if (!vopmd.interlace) {
                            if (mode.transpStatus[0] == MbMode.PARTIAL) {
                                mcPadCurrMB(ref, offY, offUV, offsets);
                            }
                            padNeighborTranspMBs(mbx, mby, mbIndex, ref, offY, offUV, offsets);
                        }
                    } else if (!vopmd.interlace) {
                        padCurrAndTopTranspMBFromNeighbor(mbx, mby, mbIndex, ref, offY, offUV, offsets);
                    }
                }
                if (mbx != numMBX - 1) {
                    swapCurrAndRightMBForShape();
                }
                mbIndex++;
                mvOff += PVOP_MV_PER_REF_PER_MB;
                offY += MB_SIZE;
                offUV += BLOCK_SIZE;
            }
            swapBlockMemoryRows();
        }
        if (vopmd.interlace && !volmd.shapeOnly) {
            fieldBasedMCPadding(0, refQ1);
        }
    }

    private void reconstructInterWithShape(MbMode mode, int mbIndex, int mbx, int mby, int x, int y, int mvOff,
                                           int offY, int offUV, int[] offsets) {
        var ref = refQ1;
        motionCompMB(predMBY, refQ0.y.pixels, mv, mvOff, mbIndex, mbx, mby, x, y, mode.skip, false, rctRefVOPY0);
        if (!(mode.skip && !mode.mcsel)) {
            if (mode.fieldMV) {
                mvLookupUV(mode, mvOff, uv);
                motionCompFieldUV(predMBU, predMBV, 0, refQ0, x, y, uv[0], uv[1], mode.forwardTop ? 1 : 0,
                        rctRefVOPY0);
                motionCompFieldUV(predMBU, predMBV, BLOCK_SIZE, refQ0, x, y, uv[2], uv[3],
                        mode.forwardBottom ? 1 : 0, rctRefVOPY0);
            } else if (mode.mcsel) {
                findGlobalChromPredForGMC(x, y, predMBU, predMBV);
            } else {
                mvLookupUVWithShape(mode, mv, mvOff, uv);
                motionCompUV(predMBU, predMBV, refQ0, x, y, uv[0], uv[1], vopmd.roundingControl, rctRefVOPY0);
            }
            if (mode.skip) {
                assignPredToCurrQ(ref.y.pixels, offY, ref.u.pixels, ref.v.pixels, offUV);
            } else {
                addErrorAndPredToCurrQ(ref.y.pixels, offY, ref.u.pixels, ref.v.pixels, offUV);
            }
        } else if (volmd.advPredDisable) {
            copyFromRefToCurrQ(refQ0, x, y, ref.y.pixels, offY, ref.u.pixels, ref.v.pixels, offUV, rctRefVOPY0);
        } else {
            copyFromPredForYAndRefForCToCurrQ(x, y, ref.y.pixels, offY, ref.u.pixels, ref.v.pixels, offUV,
                    rctRefVOPY0);
        }
        if (volmd.alphaUsage == VopPlanes.EIGHT_BIT) {
            for (var aux = 0; aux < volmd.auxCompCount; aux++) {
                if (mode.codAlpha[aux] != MbMode.ALPHA_ALL255) {
                    motionCompMB(predMBA[aux], refQ0.a[aux].pixels, mv, mvOff, mbIndex, mbx, mby, x, y, false, true,
                            rctRefVOPY0);
                    if (mode.codAlpha[aux] == MbMode.ALPHA_SKIPPED) {
                        assignAlphaPredToCurrQ(ref.a[aux].pixels, offsets[aux], aux);
                    } else {
                        addAlphaErrorAndPredToCurrQ(ref.a[aux].pixels, offsets[aux], aux);
                    }
                }
            }
        }
    }

    private void decodeRectangularHeader(MbMode mode, int mbIndex, int mbx, int mby, int x, int y, int mvOff,
                                         boolean left, boolean right, boolean top, boolean zeroMV) {
        if (uiSprite == 0 || uiSprite == 2) {
            qpHolder[0] = decodeMBTextureHeadOfPVOP(mode, qpHolder[0], restart);
            if (!mode.mcsel) {
                decodeMV(mode, mvOff, mbIndex, left, right, top, zeroMV, mbx, mby);
            } else {
                gmcVectors(mvOff, x, y);
            }
        } else if (spriteActive()) {
            if (volmd.alphaUsage != VopPlanes.RECTANGLE) {
                if (mode.transpStatus[0] != MbMode.ALL) {
                    qpHolder[0] = decodeMBTextureHeadOfPVOP(mode, qpHolder[0], restart);
                }
            } else {
                qpHolder[0] = decodeMBTextureHeadOfPVOP(mode, qpHolder[0], restart);
            }
        }
    }

    /**
     * Decodes the macroblocks of a rectangular predicted VOP, or of a sprite update ({@code decodePVOP}).
     */
    final void decodePVOP() {
        var y = 0;
        var ref = refQ1;
        qpHolder[0] = vopmd.intStep;
        videoPacketNumber = 0;
        vpMBnum = 0;
        var zeroMV = volmd.volType == VolMode.ENHN_LAYER && vopmd.refSelectCode == 3;
        restart[0] = true;
        if (volmd.newpredEnable) {
            newpredBeginPVop();
        }
        var mbIndex = 0;
        for (var mby = 0; mby < numMBY; mby++, y += MB_SIZE * rrvScale) {
            var offY = startInRefToCurrRctY + mby * frameWidthYxMBSize;
            var offUV = startInRefToCurrRctUV + mby * frameWidthUVxBlkSize;
            var mvOff = mbIndex * PVOP_MV_PER_REF_PER_MB;
            if (spriteActive()) {
                spriteUpdateNotHole(0, mby, mbIndex);
            }
            skipAnyStuffing();
            if (checkResyncMarker()) {
                qpHolder[0] = decodeVideoPacketHeader(qpHolder[0]);
                videoPacketNumber++;
                restart[0] = true;
                if (volmd.newpredEnable && !volmd.newpredSegmentType) {
                    newpredResync(0, mby);
                }
            }
            var first = mbmd[mbIndex];
            first.videoPacketNumber = videoPacketNumber;
            boolean rightBndry;
            boolean topBndry;
            if (mby == 0) {
                rightBndry = true;
                topBndry = true;
            } else {
                rightBndry = mbmd[mbIndex - numMBX + 1].videoPacketNumber != first.videoPacketNumber;
                topBndry = mbmd[mbIndex - numMBX].videoPacketNumber != first.videoPacketNumber;
            }
            decodeRectangularHeader(first, mbIndex, 0, mby, 0, y, mvOff, true, rightBndry, topBndry, zeroMV);
            var x = 0;
            for (var mbx = 0; mbx < numMBX; mbx++, x += MB_SIZE * rrvScale) {
                var mode = mbmd[mbIndex];
                var newpredResync = false;
                if (uiSprite == 0 || uiSprite == 2) {
                    if (mode.dctMd == MbMode.INTRA || mode.dctMd == MbMode.INTRAQ) {
                        decodeTextureIntraMB(mode, mbIndex, mbx, mby, ref.y.pixels, offY, ref.u.pixels, ref.v.pixels,
                                offUV, null, null);
                        if (vopmd.interlace && mode.fieldDCT) {
                            fieldDCTtoFrameC(ref.y.pixels, offY);
                        }
                    } else if (!mode.skip) {
                        decodeTextureInterMB(mode, null, null);
                        if (vopmd.interlace && mode.fieldDCT) {
                            fieldDCTtoFrameI(errorMBY);
                        }
                    }
                } else if (spriteActive()) {
                    mv[mvOff].setToZero();
                    mode.has4MVForward = false;
                    mode.dctMd = MbMode.INTER;
                    if (mode.transpStatus[0] != MbMode.ALL && !mode.skip) {
                        decodeTextureInterMB(mode, null, null);
                    }
                }
                if (mbx != numMBX - 1) {
                    var right = mbmd[mbIndex + 1];
                    skipAnyStuffing();
                    if (checkResyncMarker()) {
                        qpHolder[0] = decodeVideoPacketHeader(qpHolder[0]);
                        videoPacketNumber++;
                        restart[0] = true;
                        newpredResync = true;
                    }
                    right.videoPacketNumber = videoPacketNumber;
                    boolean leftBndry;
                    if (mby == 0) {
                        leftBndry = mode.videoPacketNumber != right.videoPacketNumber;
                        rightBndry = true;
                        topBndry = true;
                    } else {
                        leftBndry = mode.videoPacketNumber != right.videoPacketNumber;
                        if (mbx + 1 == numMBX - 1) {
                            rightBndry = true;
                        } else {
                            rightBndry = mbmd[mbIndex + 1 - numMBX + 1].videoPacketNumber != right.videoPacketNumber;
                        }
                        topBndry = mbmd[mbIndex + 1 - numMBX].videoPacketNumber != right.videoPacketNumber;
                    }
                    if (uiSprite == 0 || uiSprite == 2) {
                        decodeRectangularHeader(right, mbIndex + 1, mbx + 1, mby, x + 16, y,
                                mvOff + PVOP_MV_PER_REF_PER_MB, leftBndry, rightBndry, topBndry, zeroMV);
                    } else if (spriteActive()) {
                        spriteUpdateNotHole(mbx + 1, mby, mbIndex + 1);
                        if (right.transpStatus[0] != MbMode.ALL) {
                            qpHolder[0] = decodeMBTextureHeadOfPVOP(right, qpHolder[0], restart);
                        }
                    }
                }
                if (mode.dctMd == MbMode.INTER || mode.dctMd == MbMode.INTERQ) {
                    if (uiSprite == 0 || uiSprite == 2) {
                        motionCompMB(predMBY, refQ0.y.pixels, mv, mvOff, mbIndex, mbx, mby, x, y, mode.skip, false,
                                rctRefVOPY0);
                        if (!(mode.skip && !mode.mcsel)) {
                            if (!mode.mcsel) {
                                mvLookupUV(mode, mvOff, uv);
                            }
                            if (mode.fieldMV) {
                                motionCompFieldUV(predMBU, predMBV, 0, refQ0, x, y, uv[0], uv[1],
                                        mode.forwardTop ? 1 : 0, rctRefVOPY0);
                                motionCompFieldUV(predMBU, predMBV, BLOCK_SIZE, refQ0, x, y, uv[2], uv[3],
                                        mode.forwardBottom ? 1 : 0, rctRefVOPY0);
                            } else if (mode.mcsel) {
                                findGlobalChromPredForGMC(x, y, predMBU, predMBV);
                            } else {
                                motionCompUV(predMBU, predMBV, refQ0, x, y, uv[0], uv[1], vopmd.roundingControl,
                                        rctRefVOPY0);
                            }
                            if (mode.skip) {
                                assignPredToCurrQ(ref.y.pixels, offY, ref.u.pixels, ref.v.pixels, offUV);
                            } else {
                                addErrorAndPredToCurrQ(ref.y.pixels, offY, ref.u.pixels, ref.v.pixels, offUV);
                            }
                        } else if (volmd.advPredDisable) {
                            copyFromRefToCurrQ(refQ0, x, y, ref.y.pixels, offY, ref.u.pixels, ref.v.pixels, offUV,
                                    null);
                        } else {
                            copyFromPredForYAndRefForCToCurrQ(x, y, ref.y.pixels, offY, ref.u.pixels, ref.v.pixels,
                                    offUV, null);
                        }
                    } else if (spriteActive()) {
                        copyCurrQToPred(offY, offUV);
                        if (!mode.skip && mode.transpStatus[0] != MbMode.ALL) {
                            addErrorAndPredToCurrQ(ref.y.pixels, offY, ref.u.pixels, ref.v.pixels, offUV);
                        }
                    }
                }
                if (newpredResync && volmd.newpredEnable && !volmd.newpredSegmentType) {
                    newpredResync(mbx + 1, mby);
                }
                mbIndex++;
                mvOff += PVOP_MV_PER_REF_PER_MB;
                offY += MB_SIZE * rrvScale;
                offUV += BLOCK_SIZE * rrvScale;
            }
            swapBlockMemoryRows();
        }
        skipAnyStuffing();
        if (vopmd.rrvOnOff == 1) {
            filterRrv(refQ1);
        }
        if (volmd.newpredEnable) {
            newpredEndPVop();
        }
    }

    private void resetBVectorPredictors() {
        Arrays.fill(forwardMvPredBVOPx, 0);
        Arrays.fill(forwardMvPredBVOPy, 0);
        Arrays.fill(backwardMvPredBVOPx, 0);
        Arrays.fill(backwardMvPredBVOPy, 0);
    }

    private void clearErrorMacroblock() {
        Arrays.fill(errorMBY, 0, MB_SQUARE_SIZE, 0);
        Arrays.fill(errorMBU, 0, BLOCK_SQUARE_SIZE, 0);
        Arrays.fill(errorMBV, 0, BLOCK_SQUARE_SIZE, 0);
    }

    /**
     * Decodes the macroblocks of a rectangular B-VOP ({@code decodeBVOP}).
     */
    final void decodeBVOP() {
        var y = 0;
        var curr = currQ;
        var qp = vopmd.intStepB;
        videoPacketNumber = 0;
        vpMBnum = 0;
        var haveRef = codedFutureRef;
        var mbIndex = 0;
        var enhancementFromBase = volmd.volType == VolMode.ENHN_LAYER && vopmd.refSelectCode == 0;
        for (var mby = 0; mby < numMBY; mby++, y += MB_SIZE) {
            var offY = startInRefToCurrRctY + mby * frameWidthYxMBSize;
            var offUV = startInRefToCurrRctUV + mby * frameWidthUVxBlkSize;
            var x = 0;
            resetBVectorPredictors();
            for (var mbx = 0; mbx < numMBX; mbx++, x += MB_SIZE) {
                var mode = mbmd[mbIndex];
                var refMode = haveRef ? mbmdRef[mbIndex] : null;
                var fwdOff = mbIndex * BVOP_MV_PER_REF_PER_MB;
                var bakOff = mvBackward + mbIndex * BVOP_MV_PER_REF_PER_MB;
                mode.colocatedMBSkip = !enhancementFromBase && refMode != null && refMode.skip;
                mode.colocatedMBMCSEL = !enhancementFromBase && refMode != null && refMode.mcsel;
                if (mode.colocatedMBSkip && !mode.colocatedMBMCSEL && (volmd.volType == VolMode.BASE_LAYER
                        || (volmd.volType == VolMode.ENHN_LAYER && vopmd.refSelectCode == 3 && volmd.enhnType == 0))) {
                    copyFromRefToCurrQ(refQ0, x, y, curr.y.pixels, offY, curr.u.pixels, curr.v.pixels, offUV, null);
                    mode.skip = true;
                    MotionVector.zero(mv, fwdOff, BVOP_MV_PER_REF_PER_MB);
                    MotionVector.zero(mv, bakOff, BVOP_MV_PER_REF_PER_MB);
                    mode.mbType = MbMode.FORWARD;
                } else {
                    if (checkResyncMarker()) {
                        qp = decodeVideoPacketHeader(qp);
                        videoPacketNumber++;
                        resetBVectorPredictors();
                    }
                    mode.videoPacketNumber = videoPacketNumber;
                    qp = decodeMBTextureHeadOfBVOP(mode, qp);
                    decodeMVofBVOP(fwdOff, bakOff, mode, haveRef ? mbIndex : -1);
                    if (!mode.skip) {
                        decodeTextureInterMB(mode, null, null);
                        if (vopmd.interlace && mode.fieldDCT) {
                            fieldDCTtoFrameI(errorMBY);
                        }
                        motionCompAndAddErrorMB_BVOP(fwdOff, bakOff, mode, mbIndex, mbx, mby, x, y, curr.y.pixels,
                                offY, curr.u.pixels, curr.v.pixels, offUV, rctRefVOPY0, rctRefVOPY1);
                    } else if (vopmd.interlace) {
                        if (mode.mbType != MbMode.DIRECT) {
                            throw CorruptStream.INSTANCE;
                        }
                        mode.directDeltaX = 0;
                        mode.directDeltaY = 0;
                        clearErrorMacroblock();
                        motionCompAndAddErrorMB_BVOP(fwdOff, bakOff, mode, mbIndex, mbx, mby, x, y, curr.y.pixels,
                                offY, curr.u.pixels, curr.v.pixels, offUV, rctRefVOPY0, rctRefVOPY1);
                    } else {
                        motionCompSkipMB_BVOP(mode, fwdOff, bakOff, x, y, curr.y.pixels, offY, curr.u.pixels,
                                curr.v.pixels, offUV, rctRefVOPY0, rctRefVOPY1);
                    }
                }
                mbIndex++;
                offY += MB_SIZE;
                offUV += BLOCK_SIZE;
            }
        }
    }

    /**
     * Decodes the macroblocks of a B-VOP with shape ({@code decodeBVOP_WithShape}).
     */
    final void decodeBVOP_WithShape() {
        var y = rctCurrVOPY.top;
        if (volmd.volType == VolMode.BASE_LAYER) {
            mvBaseBY = mvBY;
            rctBase.set(rctCurrVOPY);
        }
        var xIndex = 0;
        var yIndex = 0;
        if (volmd.volType == VolMode.ENHN_LAYER && volmd.spatialScalability) {
            xIndex = (rctCurrVOPY.left - rctBase.left * volmd.horSamplingFactorNShape
                    / volmd.horSamplingFactorMShape) / MB_SIZE;
            yIndex = (rctCurrVOPY.top - rctBase.top * volmd.verSamplingFactorNShape
                    / volmd.verSamplingFactorMShape) / MB_SIZE;
        }
        var curr = currQ;
        if (!codedFutureRef) {
            vopmd.shapeBPredDir = VopMode.B_FORWARD;
        } else if (volmd.volType == VolMode.ENHN_LAYER && volmd.hierarchyType == 0 && volmd.spatialScalability
                && volmd.enhnType != 0 && volmd.useRefShape == 1) {
            vopmd.shapeBPredDir = VopMode.B_FORWARD;
        } else {
            vopmd.shapeBPredDir = tFutureRef - t >= t - tPastRef ? VopMode.B_FORWARD : VopMode.B_BACKWARD;
        }
        var qp = vopmd.intStepB;
        var qpA = qpAlpha;
        qpAlphaInit(qpA, vopmd.intStepBAlpha);
        videoPacketNumber = 0;
        vpMBnum = 0;
        var mbIndex = 0;
        for (var mby = 0; mby < numMBY; mby++, y += MB_SIZE) {
            var offY = startInRefToCurrRctY + mby * frameWidthYxMBSize;
            var offUV = startInRefToCurrRctUV + mby * frameWidthUVxBlkSize;
            var x = rctCurrVOPY.left;
            resetBVectorPredictors();
            for (var mbx = 0; mbx < numMBX; mbx++, x += MB_SIZE) {
                var mode = mbmd[mbIndex];
                var offsets = alphaOffsetsIn(curr, mbx, mby);
                var fwdOff = mbIndex * BVOP_MV_PER_REF_PER_MB;
                var bakOff = mvBackward + mbIndex * BVOP_MV_PER_REF_PER_MB;
                if (checkResyncMarker()) {
                    qp = decodeVideoPacketHeader(qp);
                    videoPacketNumber++;
                    resetBVectorPredictors();
                }
                mode.videoPacketNumber = videoPacketNumber;
                mode.padded = false;
                var refIndex = findColocatedMB(mbx, mby);
                var refMode = refIndex >= 0 ? mbmdRef[refIndex] : null;
                mode.colocatedMBSkip = refMode != null && refMode.skip;
                mode.colocatedMBMCSEL = refMode != null && refMode.mcsel;
                if (vopmd.shapeCodingType) {
                    decodeShapeOfBMacroblock(mode, mbIndex, mbx, mby, x, y, curr.by.pixels, offY, xIndex, yIndex);
                } else {
                    decodeIntraShape(mode, mbIndex, mbx, mby, currMBBY, curr.by.pixels, offY);
                }
                if (!volmd.shapeOnly) {
                    downSampleBY(currMBBY, currMBBUV, mode);
                    if (mode.colocatedMBSkip && !mode.colocatedMBMCSEL && (volmd.volType == VolMode.BASE_LAYER
                            || (volmd.volType == VolMode.ENHN_LAYER && vopmd.refSelectCode == 3
                            && volmd.enhnType == 0))) {
                        copyFromRefToCurrQ(refQ0, x, y, curr.y.pixels, offY, curr.u.pixels, curr.v.pixels, offUV,
                                rctRefVOPY0);
                        if (volmd.alphaUsage == VopPlanes.EIGHT_BIT) {
                            copyAlphaFromRefToCurrQ(refQ0, curr, x, y, offsets, rctRefVOPY0);
                        }
                        mode.skip = true;
                        MotionVector.zero(mv, fwdOff, BVOP_MV_PER_REF_PER_MB);
                        MotionVector.zero(mv, bakOff, BVOP_MV_PER_REF_PER_MB);
                        mode.mbType = MbMode.FORWARD;
                    } else if (mode.transpStatus[0] != MbMode.ALL) {
                        var sadct = !volmd.sadctDisable;
                        if (sadct) {
                            deriveSADCTRowLengths(currMBBY, currMBBUV, mode.transpStatus);
                        }
                        qp = decodeMBTextureHeadOfBVOP(mode, qp);
                        decodeMVofBVOP(fwdOff, bakOff, mode, refIndex);
                        if (!mode.skip) {
                            decodeTextureInterMB(mode, sadct ? currMBBY : null, sadct ? currMBBUV : null);
                            if (mode.transpStatus[0] == MbMode.NONE && vopmd.interlace && mode.fieldDCT) {
                                fieldDCTtoFrameI(errorMBY);
                            }
                        } else if (vopmd.interlace) {
                            if (mode.mbType != MbMode.DIRECT) {
                                throw CorruptStream.INSTANCE;
                            }
                            mode.directDeltaX = 0;
                            mode.directDeltaY = 0;
                            clearErrorMacroblock();
                            motionCompAndAddErrorMB_BVOP(fwdOff, bakOff, mode, mbIndex, mbx, mby, x, y, curr.y.pixels,
                                    offY, curr.u.pixels, curr.v.pixels, offUV, rctRefVOPY0, rctRefVOPY1);
                        } else {
                            motionCompSkipMB_BVOP(mode, fwdOff, bakOff, x, y, curr.y.pixels, offY, curr.u.pixels,
                                    curr.v.pixels, offUV, rctRefVOPY0, rctRefVOPY1);
                        }
                        if (volmd.alphaUsage == VopPlanes.EIGHT_BIT) {
                            for (var aux = 0; aux < volmd.auxCompCount; aux++) {
                                decodeMBAlphaHeadOfBVOP(mode, qp, qpA[aux], aux);
                                decodeAlphaInterMB(mode, curr.a[aux].pixels, offsets[aux], aux,
                                        sadct ? currMBBY : null);
                            }
                        }
                        if (!mode.skip) {
                            motionCompAndAddErrorMB_BVOP(fwdOff, bakOff, mode, mbIndex, mbx, mby, x, y, curr.y.pixels,
                                    offY, curr.u.pixels, curr.v.pixels, offUV, rctRefVOPY0, rctRefVOPY1);
                        }
                        if (volmd.alphaUsage == VopPlanes.EIGHT_BIT) {
                            for (var aux = 0; aux < volmd.auxCompCount; aux++) {
                                if (mode.codAlpha[aux] != MbMode.ALPHA_ALL255) {
                                    motionCompAlphaMB_BVOP(fwdOff, bakOff, mode, mbIndex, mbx, mby, x, y, rctRefVOPY0,
                                            rctRefVOPY1, aux);
                                    if (mode.codAlpha[aux] == MbMode.ALPHA_SKIPPED) {
                                        assignAlphaPredToCurrQ(curr.a[aux].pixels, offsets[aux], aux);
                                    } else {
                                        addAlphaErrorAndPredToCurrQ(curr.a[aux].pixels, offsets[aux], aux);
                                    }
                                }
                            }
                        }
                    }
                    if (!vopmd.interlace) {
                        if (mode.transpStatus[0] != MbMode.ALL) {
                            if (mode.transpStatus[0] == MbMode.PARTIAL) {
                                mcPadCurrMB(curr, offY, offUV, offsets);
                            }
                            padNeighborTranspMBs(mbx, mby, mbIndex, curr, offY, offUV, offsets);
                        } else {
                            padCurrAndTopTranspMBFromNeighbor(mbx, mby, mbIndex, curr, offY, offUV, offsets);
                        }
                    }
                }
                mbIndex++;
                offY += MB_SIZE;
                offUV += BLOCK_SIZE;
            }
        }
        if (vopmd.interlace && !volmd.shapeOnly) {
            fieldBasedMCPadding(0, currQ);
        }
    }

    private int[] alphaOffsetsIn(VopPlanes vop, int mbx, int mby) {
        return alphaOffsets(mbx, mby);
    }

    private void decodeShapeOfBMacroblock(MbMode mode, int mbIndex, int mbx, int mby, int x, int y, int[] frm,
                                          int frmOff, int xIndex, int yIndex) {
        var forward = vopmd.shapeBPredDir == VopMode.B_FORWARD;
        if (!volmd.spatialScalability) {
            decodeInterShape(forward ? refQ0 : refQ1, mode, mbIndex, mbx, mby, x, y, -1, currMBBY, frm, frmOff,
                    colocatedBShape(forward, mbx, mby));
        } else if (volmd.volType == VolMode.BASE_LAYER
                || (!(volmd.enhnType == 0 || volmd.useRefShape == 0) && !volmd.shapeOnly)) {
            int colocated;
            if (volmd.enhnType != 0 && volmd.useRefShape == 1) {
                if (numMBXRef <= 0 || numMBYRef <= 0) {
                    throw new MediaException.Malformed("MPEG-4 enhancement B-VOP shape without a reference VOP");
                }
                colocated = mbmdRef[Math.min(Math.max(0, mbx), numMBXRef - 1)
                        + Math.min(Math.max(0, mby), numMBYRef - 1) * numMBXRef].shpmd;
            } else {
                colocated = colocatedBShape(forward, mbx, mby);
            }
            decodeInterShape(forward ? refQ0 : refQ1, mode, mbIndex, mbx, mby, x, y, -1, currMBBY, frm, frmOff,
                    colocated);
        } else if (volmd.volType == VolMode.ENHN_LAYER) {
            if (numMBBaseXRef <= 0 || numMBBaseYRef <= 0) {
                throw new MediaException.Malformed("MPEG-4 enhancement B-VOP shape without a base layer VOP");
            }
            var index = Math.min(Math.max(0, (mbx + xIndex) * volmd.horSamplingFactorMShape
                    / volmd.horSamplingFactorNShape), numMBBaseXRef - 1)
                    + Math.min(Math.max(0, (mby + yIndex) * volmd.verSamplingFactorMShape
                    / volmd.verSamplingFactorNShape), numMBBaseYRef - 1) * numMBBaseXRef;
            var colocated = baseShapeModes[index];
            decodeSIShapeBVOP(refQ0, refQ1, mode, index, mbx, mby, x, y, currMBBY, frm, frmOff, colocated);
        }
    }

    private int colocatedBShape(boolean forward, int mbx, int mby) {
        if (forward) {
            return refShapeModes[Math.min(Math.max(0, mbx), refShpNumMBX - 1)
                    + Math.min(Math.max(0, mby), refShpNumMBY - 1) * refShpNumMBX];
        }
        return mbmdRef[Math.min(Math.max(0, mbx), numMBXRef - 1)
                + Math.min(Math.max(0, mby), numMBYRef - 1) * numMBXRef].shpmd;
    }
}
