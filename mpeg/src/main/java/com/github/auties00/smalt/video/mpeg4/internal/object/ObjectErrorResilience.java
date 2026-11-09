/* Ports the Microsoft MPEG-4 Visual reference software of ISO/IEC 14496-5:2001 (version 2 FDAM1-2.3-001213,
 * sys/decoder/errdec.cpp: data partitioning). Copyright 1997-2000 Toshiba Corporation, Microsoft Corporation and the
 * contributors named in the sources; ISO/IEC free license for products conforming to ISO/IEC 14496-2. Modified for
 * Smalt. */
package com.github.auties00.smalt.video.mpeg4.internal.object;

import java.util.Arrays;

/**
 * The data partitioned I- and P-VOPs (6.2.5.2, E.1.2), with and without shape: per video packet the modes and the DC
 * coefficients or motion vectors come first, the texture after the DC or motion marker.
 */
abstract class ObjectErrorResilience extends ObjectMacroblocks {
    private static final int DC_MARKER = 0x6B001;
    private static final int MOTION_MARKER = 0x1F001;

    private int[] mcbpc = new int[0];
    private int[] intraDC = new int[0];
    private final boolean[] restartDp = new boolean[1];
    private final int[] dpQp = new int[1];
    private final int[] dpUv = new int[2];

    private boolean checkDCMarker() {
        return bits.peekBits(19) == DC_MARKER;
    }

    private boolean checkMotionMarker() {
        return bits.peekBits(17) == MOTION_MARKER;
    }

    private void ensureBuffers() {
        var count = numMBX * numMBY + 1;
        if (mcbpc.length < count) {
            mcbpc = new int[count];
            intraDC = new int[count * V_BLOCK];
        }
    }

    private int dquant(MbMode mode, int qp) {
        mode.intStepDelta = switch (bits.getBits(2)) {
            case 0 -> -1;
            case 1 -> -2;
            case 2 -> 1;
            default -> 2;
        };
        return Math.clamp(qp + mode.intStepDelta, 1, (1 << volmd.quantPrecision) - 1);
    }

    private void decodeDCs(MbMode mode, int dcOff) {
        if (!mode.codeDcAsAc) {
            for (var blk = Y_BLOCK1; blk < U_BLOCK; blk++) {
                if (mode.transpStatus[blk] != MbMode.ALL) {
                    intraDC[dcOff + blk - 1] = decodeIntraDCmpeg(true);
                }
            }
            for (var blk = U_BLOCK; blk <= V_BLOCK; blk++) {
                intraDC[dcOff + blk - 1] = decodeIntraDCmpeg(false);
            }
        }
    }

    private int decodeMBTextureDCOfIVOP(MbMode mode, int qp, int dcOff) {
        mode.intStepDelta = 0;
        mode.skip = false;
        if (mode.dctMd == MbMode.INTRAQ) {
            qp = dquant(mode, qp);
        }
        mode.stepSize = qp;
        if (mode.transpStatus[0] == MbMode.ALL) {
            return qp;
        }
        setDCVLCMode(mode, restartDp);
        decodeDCs(mode, dcOff);
        return qp;
    }

    private int opaqueBlocks(MbMode mode) {
        var count = 0;
        for (var blk = Y_BLOCK1; blk <= Y_BLOCK4; blk++) {
            if (mode.transpStatus[blk] != MbMode.ALL) {
                count++;
            }
        }
        return count;
    }

    private int cbpy(int opaque, boolean inter) {
        return switch (opaque) {
            case 1 -> inter ? 1 - decode(VLC_CBPY1) : decode(VLC_CBPY1);
            case 2 -> inter ? 3 - decode(VLC_CBPY2) : decode(VLC_CBPY2);
            case 3 -> inter ? 7 - decode(VLC_CBPY3) : decode(VLC_CBPY3);
            case 4 -> inter ? 15 - decode(VLC_CBPY) : decode(VLC_CBPY);
            default -> throw CorruptStream.INSTANCE;
        };
    }

    private void decodeMBTextureHeadOfIVOP(MbMode mode, int mcbpcValue) {
        var opaque = opaqueBlocks(mode);
        mode.dctMd = mcbpcValue > 3 ? MbMode.INTRAQ : MbMode.INTRA;
        mode.skip = false;
        mode.mcsel = false;
        var cbpc = mcbpcValue % 4;
        mode.acPrediction = bits.getBits(1) != 0;
        setCBPYandC(mode, cbpc, cbpy(opaque, false), opaque);
    }

    /**
     * {@return whether the macroblock mode is stuffing ({@code decodeMBTextureModeOfPVOP_DataPartitioning})}
     */
    private boolean decodeMBTextureModeOfPVOP(MbMode mode, int index) {
        mode.skip = bits.getBits(1) != 0;
        var gmc = uiSprite == 2 && vopmd.predType == VopMode.SPRITE;
        if (!mode.skip) {
            var value = decode(VLC_MCBPC_INTER);
            mcbpc[index] = value;
            if (value == 20) {
                return true;
            }
            switch (value / 4) {
                case 0 -> {
                    mode.dctMd = MbMode.INTER;
                    mode.has4MVForward = false;
                }
                case 1 -> {
                    mode.dctMd = MbMode.INTERQ;
                    mode.has4MVForward = false;
                }
                case 2 -> {
                    mode.dctMd = MbMode.INTER;
                    mode.has4MVForward = true;
                }
                case 3 -> mode.dctMd = MbMode.INTRA;
                case 4 -> mode.dctMd = MbMode.INTRAQ;
                default -> throw CorruptStream.INSTANCE;
            }
            mode.mcsel = false;
            if ((mode.dctMd == MbMode.INTER || mode.dctMd == MbMode.INTERQ) && !mode.has4MVForward && gmc) {
                mode.mcsel = bits.getBits(1) != 0;
            }
        } else {
            mode.dctMd = MbMode.INTER;
            mode.has4MVForward = false;
            mode.mcsel = gmc;
        }
        return false;
    }

    private int decodeMBTextureHeadOfPVOP(MbMode mode, int qp, int index, int dcOff) {
        var opaque = opaqueBlocks(mode);
        var cbpc = 0;
        var cbpyValue = 0;
        if (!mode.skip) {
            cbpc = mcbpc[index] % 4;
            if (mode.dctMd == MbMode.INTRA || mode.dctMd == MbMode.INTRAQ) {
                mode.acPrediction = bits.getBits(1) != 0;
                cbpyValue = cbpy(opaque, false);
            } else {
                cbpyValue = cbpy(opaque, true);
            }
        } else {
            mode.dctMd = MbMode.INTER;
            mode.has4MVForward = false;
        }
        setCBPYandC(mode, cbpc, cbpyValue, opaque);
        mode.intStepDelta = 0;
        if (mode.dctMd == MbMode.INTERQ || mode.dctMd == MbMode.INTRAQ) {
            qp = dquant(mode, qp);
        }
        mode.stepSize = qp;
        setDCVLCMode(mode, restartDp);
        if (mode.dctMd == MbMode.INTRA || mode.dctMd == MbMode.INTRAQ) {
            decodeDCs(mode, dcOff);
        }
        return qp;
    }

    /**
     * Decodes the AC coefficients of an intra block of a data partitioned VOP and reconstructs it
     * ({@code decodeIntraBlockTextureTcoef_DataPartitioning}).
     */
    private void decodeIntraBlockTcoef(int[] dst, int dstOff, int dstWidth, int qp, int dcScaler, int blk,
                                       int[][] memory, MbMode mode, int[] pred, int predQP, int dcOff, int[] shape,
                                       int shapeOff, int shapeWidth) {
        var start = mode.codeDcAsAc ? 0 : 1;
        var coefQ = this.coefQ[blk - 1];
        coefQ[0] = intraDC[dcOff + blk - 1];
        if (mode.coded(blk)) {
            var zigzag = RefTables.STANDARD_ZIGZAG;
            if (mode.acPrediction) {
                zigzag = mode.predDir[blk - 1] == MbMode.HORIZONTAL ? RefTables.VERTICAL_ZIGZAG
                        : RefTables.HORIZONTAL_ZIGZAG;
            }
            zigzag = selectScan(zigzag, mode.transpStatus[0] == MbMode.PARTIAL, blk);
            decodeDpTcoef(coefQ, start, zigzag);
        } else {
            Arrays.fill(coefQ, start, BLOCK_SQUARE_SIZE, 0);
        }
        inverseDCACPred(mode, blk - 1, coefQ, qp, dcScaler, pred, predQP);
        inverseQuantizeIntraDc(coefQ, dcScaler);
        if (shape != null) {
            var lx = currMBCoeffWidth[blk];
            for (var ix = lx[0]; ix < BLOCK_SIZE; ix++) {
                coefQ[ix] = 0;
            }
            for (var iy = 1; iy < BLOCK_SIZE; iy++) {
                if (lx[iy] == 0) {
                    coefQ[iy * BLOCK_SIZE] = 0;
                }
            }
        }
        if (volmd.quantizer == VolMode.Q_H263) {
            inverseQuantizeDCTcoefH263(coefQ, 1, qp);
        } else {
            inverseQuantizeIntraDCTcoefMPEG(coefQ, 1, qp, blk >= A_BLOCK1, 0);
        }
        var saved = memory[blk - 1];
        saved[0] = dctCoef[0];
        for (int i = 1, j = 8; i < BLOCK_SIZE; i++, j += BLOCK_SIZE) {
            saved[i] = coefQ[i];
            saved[i + BLOCK_SIZE - 1] = coefQ[j];
        }
        if (vopmd.rrvOnOff == 1) {
            idct.applyClipped(dctCoef, 0, BLOCK_SIZE, dpBlock8, 0, BLOCK_SIZE, shape, shapeOff, shapeWidth);
            RrvUtil.meanUpSampling(dpBlock8, dpBlock16, 8, 8);
            RrvUtil.writeCubicRct(16, dstWidth, dpBlock16, 0, dst, dstOff);
        } else {
            idct.applyClipped(dctCoef, 0, BLOCK_SIZE, dst, dstOff, dstWidth, shape, shapeOff, shapeWidth);
        }
    }

    private final int[] dpBlock8 = new int[BLOCK_SQUARE_SIZE];
    private final int[] dpBlock16 = new int[MB_SQUARE_SIZE];

    private void decodeDpTcoef(int[] coefQ, int start, int[] zigzag) {
        if (volmd.reversibleVlc) {
            decodeRvlcTcoef(coefQ, start, zigzag, true);
        } else {
            decodeIntraTCOEF(coefQ, start, zigzag);
        }
    }

    private void decodeTextureIntraMB(MbMode mode, int mbIndex, int mbx, int mby, int[] y, int offY, int[] u,
                                      int[] v, int offUV, int dcOff, int[] shapeY, int[] shapeUV) {
        if (mode.transpStatus[0] == MbMode.ALL) {
            return;
        }
        var qp = mode.stepSize;
        var scalers = dcScalers(qp);
        int[][] top = null;
        int[][] left = null;
        int[][] leftTop = null;
        MbMode topMode = null;
        MbMode leftMode = null;
        MbMode leftTopMode = null;
        if (mby > 0 && mode.videoPacketNumber == mbmd[mbIndex - numMBX].videoPacketNumber) {
            top = mbmAbove[mbx];
            topMode = mbmd[mbIndex - numMBX];
        }
        if (mbx > 0 && mode.videoPacketNumber == mbmd[mbIndex - 1].videoPacketNumber) {
            left = mbmCurr[mbx - 1];
            leftMode = mbmd[mbIndex - 1];
        }
        if (mby > 0 && mbx > 0 && mode.videoPacketNumber == mbmd[mbIndex - numMBX - 1].videoPacketNumber) {
            leftTop = mbmAbove[mbx - 1];
            leftTopMode = mbmd[mbIndex - numMBX - 1];
        }
        for (var blk = Y_BLOCK1; blk <= V_BLOCK; blk++) {
            int[] dst;
            int dstOff;
            int width;
            int scaler;
            int[] shape;
            var shapeOff = 0;
            if (blk < U_BLOCK) {
                if (mode.transpStatus[blk] == MbMode.ALL) {
                    continue;
                }
                dst = y;
                dstOff = offY;
                switch (blk) {
                    case Y_BLOCK2 -> {
                        dstOff += BLOCK_SIZE * rrvScale;
                        shapeOff = BLOCK_SIZE;
                    }
                    case Y_BLOCK3 -> {
                        dstOff += frameWidthYxBlkSize;
                        shapeOff = BLOCK_SIZE * MB_SIZE;
                    }
                    case Y_BLOCK4 -> {
                        dstOff += frameWidthYxBlkSize + BLOCK_SIZE * rrvScale;
                        shapeOff = BLOCK_SIZE * MB_SIZE + BLOCK_SIZE;
                    }
                    default -> {
                    }
                }
                shape = shapeY != null && mode.transpStatus[blk] == MbMode.PARTIAL ? shapeY : null;
                width = frameWidthY;
                scaler = scalers & 0xFFFF;
            } else {
                width = frameWidthUV;
                dst = blk == U_BLOCK ? u : v;
                dstOff = offUV;
                shape = shapeUV != null && mode.transpStatus[blk] == MbMode.PARTIAL ? shapeUV : null;
                scaler = scalers >>> 16;
            }
            var pred = decideIntraPred(mode, qp, blk, left, top, leftTop, mbmCurr[mbx], leftMode, topMode,
                    leftTopMode);
            decodeIntraBlockTcoef(dst, dstOff, width, qp, scaler, blk, mbmCurr[mbx], mode, pred, qpPred, dcOff,
                    shape, shapeOff, blk < U_BLOCK ? MB_SIZE : BLOCK_SIZE);
        }
    }

    private int offY(int mbx, int mby) {
        return startInRefToCurrRctY + mby * frameWidthYxMBSize + mbx * MB_SIZE * rrvScale;
    }

    private int offUV(int mbx, int mby) {
        return startInRefToCurrRctUV + mby * frameWidthUVxBlkSize + mbx * BLOCK_SIZE * rrvScale;
    }

    @Override
    final void decodeIVOP_DataPartitioning() {
        var slicePoints = slicePoints();
        var sliceCount = 1;
        var segmented = true;
        MotionVector.zero(mv, 0, numMB * PVOP_MV_PER_REF_PER_MB);
        ensureBuffers();
        var ref = refQ1;
        var qp = vopmd.intStepI;
        videoPacketNumber = 0;
        vpMBnum = 0;
        var mbn = 0;
        restartDp[0] = true;
        do {
            if (checkResyncMarker()) {
                qp = decodeVideoPacketHeader(qp);
                videoPacketNumber++;
                restartDp[0] = true;
                if (volmd.newpredEnable) {
                    if (!volmd.newpredSegmentType) {
                        if (rrvScale == 2) {
                            var mbxT = mbn % numMBX;
                            var mbyT = mbn / numMBX;
                            slicePoints[sliceCount++] = (mbyT * rrvScale) * (numMBX * rrvScale) + mbxT * rrvScale;
                        } else {
                            slicePoints[sliceCount++] = mbn;
                        }
                    } else {
                        segmented = false;
                    }
                }
            }
            var first = mbn;
            do {
                if (mbn >= numMB) {
                    throw CorruptStream.INSTANCE;
                }
                var mode = mbmd[mbn];
                mode.videoPacketNumber = videoPacketNumber;
                var value = decode(VLC_MCBPC_INTRA);
                mcbpc[mbn] = value;
                if (value == 8) {
                    if (checkDCMarker()) {
                        break;
                    }
                    continue;
                }
                mode.dctMd = value > 3 ? MbMode.INTRAQ : MbMode.INTRA;
                qp = decodeMBTextureDCOfIVOP(mode, qp, mbn * V_BLOCK);
                mbn++;
            } while (!checkDCMarker());
            bits.getBits(19);
            for (var i = first; i < mbn; i++) {
                decodeMBTextureHeadOfIVOP(mbmd[i], mcbpc[i]);
            }
            for (var i = first; i < mbn; i++) {
                var mbx = i % numMBX;
                var mby = i / numMBX;
                decodeTextureIntraMB(mbmd[i], i, mbx, mby, ref.y.pixels, offY(mbx, mby), ref.u.pixels, ref.v.pixels,
                        offUV(mbx, mby), i * V_BLOCK, null, null);
                if (mbx == numMBX - 1) {
                    swapBlockMemoryRows();
                }
            }
        } while (checkResyncMarker());
        if (vopmd.rrvOnOff == 1) {
            filterRrv(refQ1);
        }
        if (volmd.newpredEnable) {
            newpredEndIVop(segmented ? slicePoints : null, sliceCount);
        }
    }

    private void gmcVector(int mvOff, int x, int y) {
        globalMv(gmcVector, x, y, vopmd.mvInfoForward.range, volmd.quarterSample);
        var v = mv[mvOff];
        v.setParts(gmcVector[0], gmcVector[1], gmcVector[2], gmcVector[3]);
        v.computeMV();
        for (var i = 1; i < PVOP_MV_PER_REF_PER_MB; i++) {
            mv[mvOff + i].set(v);
        }
    }

    private void reconstructInter(MbMode mode, int mbIndex, int mbx, int mby, int x, int y, int mvOff, int offY,
                                  int offUV, boolean shape, Rct limit) {
        var ref = refQ1;
        motionCompMB(predMBY, refQ0.y.pixels, mv, mvOff, mbIndex, mbx, mby, x, y, mode.skip, false, rctRefVOPY0);
        if (!(mode.skip && !mode.mcsel)) {
            if (mode.mcsel) {
                findGlobalChromPredForGMC(x, y, predMBU, predMBV);
            } else {
                if (shape) {
                    mvLookupUVWithShape(mode, mv, mvOff, dpUv);
                } else {
                    mvLookupUV(mode, mvOff, uv);
                    dpUv[0] = uv[0];
                    dpUv[1] = uv[1];
                }
                motionCompUV(predMBU, predMBV, refQ0, x, y, dpUv[0], dpUv[1], vopmd.roundingControl, rctRefVOPY0);
            }
            if (mode.skip) {
                assignPredToCurrQ(ref.y.pixels, offY, ref.u.pixels, ref.v.pixels, offUV);
            } else {
                addErrorAndPredToCurrQ(ref.y.pixels, offY, ref.u.pixels, ref.v.pixels, offUV);
            }
        } else if (volmd.advPredDisable) {
            copyFromRefToCurrQ(refQ0, x, y, ref.y.pixels, offY, ref.u.pixels, ref.v.pixels, offUV, limit);
        } else {
            copyFromPredForYAndRefForCToCurrQ(x, y, ref.y.pixels, offY, ref.u.pixels, ref.v.pixels, offUV, limit);
        }
    }

    @Override
    final void decodePVOP_DataPartitioning() {
        ensureBuffers();
        var qp = vopmd.intStep;
        videoPacketNumber = 0;
        vpMBnum = 0;
        var mbn = 0;
        var x = 0;
        var y = 0;
        var backup = -1;
        restartDp[0] = true;
        if (volmd.newpredEnable) {
            newpredBeginPVop();
        }
        do {
            var first = mbn;
            if (checkResyncMarker()) {
                qp = decodeVideoPacketHeader(qp);
                videoPacketNumber++;
                restartDp[0] = true;
            }
            do {
                if (mbn >= numMB) {
                    throw CorruptStream.INSTANCE;
                }
                var mode = mbmd[mbn];
                mode.videoPacketNumber = videoPacketNumber;
                var mbx = mbn % numMBX;
                var mby = mbn / numMBX;
                if (decodeMBTextureModeOfPVOP(mode, mbn)) {
                    if (checkMotionMarker()) {
                        break;
                    }
                    continue;
                }
                var leftBndry = mbx == 0 || mbmd[mbn - 1].videoPacketNumber != mode.videoPacketNumber;
                var topBndry = mby == 0 || mbmd[mbn - numMBX].videoPacketNumber != mode.videoPacketNumber;
                var rightBndry = mbx == numMBX - 1 || mby == 0
                        || mbmd[mbn - numMBX + 1].videoPacketNumber != mode.videoPacketNumber;
                var mvOff = mbn * PVOP_MV_PER_REF_PER_MB;
                if (!mode.mcsel) {
                    decodeMV(mode, mvOff, mbn, leftBndry, rightBndry, topBndry, false, mbx, mby);
                } else {
                    gmcVector(mvOff, mbx * MB_SIZE, mby * MB_SIZE);
                }
                if (backup >= 0) {
                    var bm = mbmd[backup];
                    if (bm.dctMd == MbMode.INTER || bm.dctMd == MbMode.INTERQ) {
                        var bx = backup % numMBX;
                        var by = backup / numMBX;
                        reconstructInter(bm, backup, bx, by, x, y, backup * PVOP_MV_PER_REF_PER_MB, offY(bx, by),
                                offUV(bx, by), false, null);
                    }
                    backup = -1;
                    if (volmd.newpredEnable && !volmd.newpredSegmentType) {
                        newpredResync(mbx, mby);
                    }
                }
                mbn++;
            } while (!checkMotionMarker());
            bits.getBits(17);
            for (var i = first; i < mbn; i++) {
                qp = decodeMBTextureHeadOfPVOP(mbmd[i], qp, i, i * V_BLOCK);
            }
            for (var i = first; i < mbn; i++) {
                var mode = mbmd[i];
                var mbx = i % numMBX;
                var mby = i / numMBX;
                if (mbx == 0) {
                    x = 0;
                    if (mby != 0) {
                        y += MB_SIZE * rrvScale;
                    }
                } else {
                    x += MB_SIZE * rrvScale;
                }
                var offY = offY(mbx, mby);
                var offUV = offUV(mbx, mby);
                if (mode.dctMd == MbMode.INTRA || mode.dctMd == MbMode.INTRAQ) {
                    decodeTextureIntraMB(mode, i, mbx, mby, refQ1.y.pixels, offY, refQ1.u.pixels, refQ1.v.pixels,
                            offUV, i * V_BLOCK, null, null);
                } else if (!mode.skip) {
                    decodeTextureInterMB(mode, null, null);
                }
                if (i == mbn - 1) {
                    backup = i;
                }
                if (mode.dctMd == MbMode.INTER || mode.dctMd == MbMode.INTERQ) {
                    reconstructInter(mode, i, mbx, mby, x, y, i * PVOP_MV_PER_REF_PER_MB, offY, offUV, false, null);
                }
                if (mbx == numMBX - 1) {
                    swapBlockMemoryRows();
                }
            }
        } while (checkResyncMarker());
        if (vopmd.rrvOnOff == 1) {
            filterRrv(refQ1);
        }
        if (volmd.newpredEnable) {
            newpredEndPVop();
        }
    }

    @Override
    final void decodeIVOP_WithShape_DataPartitioning() {
        if (volmd.alphaUsage == VopPlanes.EIGHT_BIT) {
            throw CorruptStream.INSTANCE;
        }
        MotionVector.zero(mv, 0, numMB * PVOP_MV_PER_REF_PER_MB);
        ensureBuffers();
        var ref = refQ1;
        var qp = vopmd.intStepI;
        videoPacketNumber = 0;
        vpMBnum = 0;
        restartDp[0] = true;
        var mbn = 0;
        do {
            if (checkResyncMarker()) {
                qp = decodeVideoPacketHeader(qp);
                videoPacketNumber++;
                restartDp[0] = true;
            }
            var first = mbn;
            do {
                if (mbn >= numMB) {
                    throw CorruptStream.INSTANCE;
                }
                var mbx = mbn % numMBX;
                var mby = mbn / numMBX;
                var mode = mbmd[mbn];
                mode.videoPacketNumber = videoPacketNumber;
                decodeIntraShape(mode, mbn, mbx, mby, currMBBY, ref.by.pixels, offY(mbx, mby));
                downSampleBY(currMBBY, currMBBUV, mode);
                mode.padded = false;
                if (mode.transpStatus[0] != MbMode.ALL) {
                    var value = decode(VLC_MCBPC_INTRA);
                    while (value == 8) {
                        value = decode(VLC_MCBPC_INTRA);
                    }
                    mcbpc[mbn] = value;
                    mode.dctMd = value > 3 ? MbMode.INTRAQ : MbMode.INTRA;
                    qp = decodeMBTextureDCOfIVOP(mode, qp, mbn * V_BLOCK);
                }
                mbn++;
            } while (!checkDCMarker());
            bits.getBits(19);
            for (var i = first; i < mbn; i++) {
                if (mbmd[i].transpStatus[0] != MbMode.ALL) {
                    decodeMBTextureHeadOfIVOP(mbmd[i], mcbpc[i]);
                }
            }
            for (var i = first; i < mbn; i++) {
                var mode = mbmd[i];
                mode.padded = false;
                var mbx = i % numMBX;
                var mby = i / numMBX;
                var offY = offY(mbx, mby);
                var offUV = offUV(mbx, mby);
                copyRefShapeToMb(currMBBY, ref.by.pixels, offY);
                downSampleBY(currMBBY, currMBBUV, mode);
                if (mode.transpStatus[0] != MbMode.ALL) {
                    var sadct = !volmd.sadctDisable;
                    if (sadct) {
                        deriveSADCTRowLengths(currMBBY, currMBBUV, mode.transpStatus);
                    }
                    decodeTextureIntraMB(mode, i, mbx, mby, ref.y.pixels, offY, ref.u.pixels, ref.v.pixels, offUV,
                            i * V_BLOCK, sadct ? currMBBY : null, sadct ? currMBBUV : null);
                    if (mode.transpStatus[0] == MbMode.PARTIAL) {
                        mcPadCurrMB(ref, offY, offUV, null);
                    }
                    padNeighborTranspMBs(mbx, mby, i, ref, offY, offUV, null);
                } else {
                    padCurrAndTopTranspMBFromNeighbor(mbx, mby, i, ref, offY, offUV, null);
                }
                if (mbx == numMBX - 1) {
                    swapBlockMemoryRows();
                }
            }
        } while (checkResyncMarker());
    }

    @Override
    final void decodePVOP_WithShape_DataPartitioning() {
        if (volmd.alphaUsage == VopPlanes.EIGHT_BIT) {
            throw CorruptStream.INSTANCE;
        }
        ensureBuffers();
        var ref = refQ1;
        var x = rctCurrVOPY.left;
        var y = rctCurrVOPY.top;
        var ys = rctCurrVOPY.top;
        var xs = rctCurrVOPY.left;
        var qp = vopmd.intStep;
        videoPacketNumber = 0;
        vpMBnum = 0;
        restartDp[0] = true;
        var mbn = 0;
        var backup = -1;
        var paddedLeftBackup = false;
        var paddedTopBackup = false;
        do {
            var first = mbn;
            if (checkResyncMarker()) {
                qp = decodeVideoPacketHeader(qp);
                videoPacketNumber++;
                restartDp[0] = true;
            }
            do {
                if (mbn >= numMB) {
                    throw CorruptStream.INSTANCE;
                }
                var mode = mbmd[mbn];
                mode.videoPacketNumber = videoPacketNumber;
                var mbx = mbn % numMBX;
                var mby = mbn / numMBX;
                if (mbx == 0) {
                    xs = rctCurrVOPY.left;
                    if (mby != 0) {
                        ys += MB_SIZE;
                    }
                } else {
                    xs += MB_SIZE;
                }
                var mvOff = mbn * PVOP_MV_PER_REF_PER_MB;
                if (vopmd.shapeCodingType) {
                    var colocated = mbmdRef[Math.min(Math.max(0, mbx), numMBXRef - 1)
                            + Math.min(Math.max(0, mby), numMBYRef - 1) * numMBXRef].shpmd;
                    decodeInterShape(refQ0, mode, mbn, mbx, mby, xs, ys, mvOff, currMBBY, ref.by.pixels,
                            offY(mbx, mby), colocated);
                } else {
                    decodeIntraShape(mode, mbn, mbx, mby, currMBBY, ref.by.pixels, offY(mbx, mby));
                }
                downSampleBY(currMBBY, currMBBUV, mode);
                if (mode.transpStatus[0] != MbMode.ALL && !volmd.shapeOnly) {
                    var stuffing = true;
                    while (stuffing) {
                        stuffing = decodeMBTextureModeOfPVOP(mode, mbn);
                    }
                    if (!mode.mcsel) {
                        decodeMVWithShape(mode, mbx, mby, mvOff, mbn);
                    } else {
                        gmcVector(mvOff, rctCurrVOPY.left + mbx * MB_SIZE, rctCurrVOPY.top + mby * MB_SIZE);
                    }
                    if (mode.has4MVForward) {
                        padMotionVectors(mode, mv, mvOff);
                    }
                }
                if (backup >= 0) {
                    var bm = mbmd[backup];
                    var bx = backup % numMBX;
                    var by = backup / numMBX;
                    var bOffY = offY(bx, by);
                    var bOffUV = offUV(bx, by);
                    copyRefShapeToMb(currMBBY, ref.by.pixels, bOffY);
                    downSampleBY(currMBBY, currMBBUV, bm);
                    bm.padded = false;
                    if (bx > 0) {
                        mbmd[backup - 1].padded = paddedLeftBackup;
                    }
                    if (by > 0) {
                        mbmd[backup - numMBX].padded = paddedTopBackup;
                    }
                    if (bm.transpStatus[0] != MbMode.ALL) {
                        if (bm.dctMd == MbMode.INTER || bm.dctMd == MbMode.INTERQ) {
                            reconstructInter(bm, backup, bx, by, x, y, backup * PVOP_MV_PER_REF_PER_MB, bOffY, bOffUV,
                                    true, rctRefVOPY0);
                        }
                        if (bm.transpStatus[0] == MbMode.PARTIAL) {
                            mcPadCurrMB(ref, bOffY, bOffUV, null);
                        }
                        padNeighborTranspMBs(bx, by, backup, ref, bOffY, bOffUV, null);
                    } else {
                        padCurrAndTopTranspMBFromNeighbor(bx, by, backup, ref, bOffY, bOffUV, null);
                    }
                    backup = -1;
                }
                mbn++;
            } while (!checkMotionMarker());
            bits.getBits(17);
            for (var i = first; i < mbn; i++) {
                if (mbmd[i].transpStatus[0] != MbMode.ALL && !volmd.shapeOnly) {
                    qp = decodeMBTextureHeadOfPVOP(mbmd[i], qp, i, i * V_BLOCK);
                }
            }
            for (var i = first; i < mbn; i++) {
                var mode = mbmd[i];
                mode.padded = false;
                var mbx = i % numMBX;
                var mby = i / numMBX;
                if (mbx == 0) {
                    x = rctCurrVOPY.left;
                    if (mby != 0) {
                        y += MB_SIZE;
                    }
                } else {
                    x += MB_SIZE;
                }
                var offY = offY(mbx, mby);
                var offUV = offUV(mbx, mby);
                copyRefShapeToMb(currMBBY, ref.by.pixels, offY);
                downSampleBY(currMBBY, currMBBUV, mode);
                var sadct = !volmd.sadctDisable;
                if (mode.transpStatus[0] != MbMode.ALL && !volmd.shapeOnly) {
                    if (sadct) {
                        deriveSADCTRowLengths(currMBBY, currMBBUV, mode.transpStatus);
                    }
                    if (mode.dctMd == MbMode.INTRA || mode.dctMd == MbMode.INTRAQ) {
                        decodeTextureIntraMB(mode, i, mbx, mby, ref.y.pixels, offY, ref.u.pixels, ref.v.pixels,
                                offUV, i * V_BLOCK, sadct ? currMBBY : null, sadct ? currMBBUV : null);
                    } else if (!mode.skip) {
                        decodeTextureInterMB(mode, sadct ? currMBBY : null, sadct ? currMBBUV : null);
                    }
                }
                if (i == mbn - 1) {
                    backup = i;
                    paddedLeftBackup = i > 0 && mbmd[i - 1].padded;
                    paddedTopBackup = i >= numMBX && mbmd[i - numMBX].padded;
                }
                if (mode.transpStatus[0] != MbMode.ALL) {
                    if (mode.dctMd == MbMode.INTER || mode.dctMd == MbMode.INTERQ) {
                        reconstructInter(mode, i, mbx, mby, x, y, i * PVOP_MV_PER_REF_PER_MB, offY, offUV, true,
                                rctRefVOPY0);
                    }
                    if (mode.transpStatus[0] == MbMode.PARTIAL) {
                        mcPadCurrMB(ref, offY, offUV, null);
                    }
                    padNeighborTranspMBs(mbx, mby, i, ref, offY, offUV, null);
                } else {
                    padCurrAndTopTranspMBFromNeighbor(mbx, mby, i, ref, offY, offUV, null);
                }
                if (mbx == numMBX - 1) {
                    swapBlockMemoryRows();
                }
            }
        } while (checkResyncMarker());
    }
}
