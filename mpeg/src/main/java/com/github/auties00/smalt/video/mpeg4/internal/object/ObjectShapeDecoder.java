/* Ports the Microsoft MPEG-4 Visual reference software of ISO/IEC 14496-5:2001 (version 2 FDAM1-2.3-001213,
 * sys/decoder/shpdec.cpp). Copyright 1996-2000 Microsoft Corporation, Toshiba Corporation, Samsung AIT, Fraunhofer
 * HHI and the contributors named in the sources; ISO/IEC free license for products conforming to ISO/IEC 14496-2.
 * Modified for Smalt. */
package com.github.auties00.smalt.video.mpeg4.internal.object;

import java.util.Arrays;

/**
 * The decoding of binary alpha blocks (7.5): the shape modes, the context-based arithmetic decoding of intra and
 * inter blocks with their conversion ratios, the shape motion vectors, and the scalable shape coding of
 * object-based spatial scalability.
 */
abstract class ObjectShapeDecoder extends ObjectTexture {
    private static final Vlc[] VLC_SHAPE_MODE = {new Vlc(RefTables.VLC_SHAPE_MODE0),
            new Vlc(RefTables.VLC_SHAPE_MODE1), new Vlc(RefTables.VLC_SHAPE_MODE2),
            new Vlc(RefTables.VLC_SHAPE_MODE3), new Vlc(RefTables.VLC_SHAPE_MODE4),
            new Vlc(RefTables.VLC_SHAPE_MODE5), new Vlc(RefTables.VLC_SHAPE_MODE6)};
    private static final Vlc[] VLC_SHAPE_SSMODE_INTER = {new Vlc(RefTables.VLC_SHAPE_SSMODE_INTER0),
            new Vlc(RefTables.VLC_SHAPE_SSMODE_INTER1), new Vlc(RefTables.VLC_SHAPE_SSMODE_INTER2),
            new Vlc(RefTables.VLC_SHAPE_SSMODE_INTER3)};
    private static final Vlc VLC_SHAPE_SSMODE_INTRA0 = new Vlc(RefTables.VLC_SHAPE_SSMODE_INTRA0);
    private static final Vlc VLC_SHAPE_MV1 = new Vlc(RefTables.VLC_SHAPE_MV1);
    private static final Vlc VLC_SHAPE_MV2 = new Vlc(RefTables.VLC_SHAPE_MV2);
    private static final int[] COLOCATED_INDEX = {0, 0, 1, 3, 3, 2, 2};

    private final MotionVector shapeDiff = new MotionVector();
    private final MotionVector shapePrediction = new MotionVector();
    private final int[] transposed = new int[BAB_SIZE * BAB_SIZE];
    private final int[] sadctColumns = new int[BLOCK_SIZE];
    private final boolean[] horizontalChecks = new boolean[MB_SIZE + 4];
    private final boolean[] verticalChecks = new boolean[MB_SIZE + 4];

    /**
     * Computes the row lengths of the shape-adaptive DCT of the boundary blocks of a macroblock
     * ({@code deriveSADCTRowLengths}).
     */
    final void deriveSADCTRowLengths(int[] shapeY, int[] shapeUV, int[] transpStatus) {
        var columns = sadctColumns;
        for (var i = Y_BLOCK1; i < U_BLOCK; i++) {
            if (transpStatus[i] != MbMode.PARTIAL) {
                Arrays.fill(currMBCoeffWidth[i], 0);
                continue;
            }
            var off = switch (i) {
                case Y_BLOCK1 -> 0;
                case Y_BLOCK2 -> BLOCK_SIZE;
                case Y_BLOCK3 -> BLOCK_SIZE * MB_SIZE;
                default -> BLOCK_SIZE * MB_SIZE + BLOCK_SIZE;
            };
            InvSadct.rowLengths(currMBCoeffWidth[i], shapeY, off, MB_SIZE, columns);
        }
        if (transpStatus[U_BLOCK] == MbMode.PARTIAL) {
            InvSadct.rowLengths(currMBCoeffWidth[U_BLOCK], shapeUV, 0, BLOCK_SIZE, columns);
        } else {
            Arrays.fill(currMBCoeffWidth[U_BLOCK], 0);
        }
    }

    private void setBoundaries(int mbx, int mby) {
        var mbNum = vpMbNum(mbx, mby);
        vpNoLeft = vpNoLeft(mbNum, mbx);
        vpNoTop = vpNoTop(mbNum);
        vpNoRightTop = vpNoRightTop(mbNum, mbx);
        vpNoLeftTop = vpNoLeftTop(mbNum, mbx);
    }

    private static void setAllStatus(MbMode mode, int status) {
        for (var i = 0; i <= 6; i++) {
            mode.transpStatus[i] = status;
        }
    }

    /**
     * Decodes the shape of a macroblock of an intra VOP ({@code decodeIntraShape}).
     *
     * @param frm the shape plane of the VOP
     * @param frmOff the index of the macroblock in the plane
     */
    final void decodeIntraShape(MbMode mode, int mbIndex, int mbx, int mby, int[] mbBY, int[] frm, int frmOff) {
        setBoundaries(mbx, mby);
        var tableIndex = shpMdTableIndex(mbIndex, mbx, mby);
        var codeTransp = RefTables.CH_FIRST_SHP_CD[tableIndex + MbMode.ALL_TRANSP];
        var codeOpaque = RefTables.CH_FIRST_SHP_CD[tableIndex + MbMode.ALL_OPAQUE];
        var codeCae = RefTables.CH_FIRST_SHP_CD[tableIndex + MbMode.INTRA_CAE];
        var code = bits.peekBits(3);
        if (code == 0) {
            throw CorruptStream.INSTANCE;
        }
        if (code >= 4) {
            bits.getBits(1);
            code = 0;
        } else if (code >= 2) {
            bits.getBits(2);
            code = 2;
        } else {
            bits.getBits(3);
            code = 3;
        }
        if (code == codeTransp) {
            mode.shpmd = MbMode.ALL_TRANSP;
            copyReconShapeToMbAndRef(mbBY, frm, frmOff, TRANSPARENT);
            setAllStatus(mode, MbMode.ALL);
        } else if (code == codeOpaque) {
            mode.shpmd = MbMode.ALL_OPAQUE;
            copyReconShapeToMbAndRef(mbBY, frm, frmOff, OPAQUE);
            setAllStatus(mode, MbMode.NONE);
        } else if (code == codeCae) {
            mode.shpmd = MbMode.INTRA_CAE;
            decodeIntraCaeBAB(mbBY, frm, frmOff);
            decideTransparencyStatus(mode, mbBY, 0);
        } else {
            throw CorruptStream.INSTANCE;
        }
    }

    /**
     * Decodes the shape of a macroblock of a predicted VOP ({@code decodeInterShape}).
     *
     * @param mvOff the index of the macroblock's texture vectors in {@link #mv}
     * @param colocated the shape mode of the co-located macroblock of the reference
     */
    final void decodeInterShape(VopPlanes ref, MbMode mode, int mbIndex, int mbx, int mby, int x, int y, int mvOff,
                                int[] mbBY, int[] frm, int frmOff, int colocated) {
        setBoundaries(mbx, mby);
        if (colocated == MbMode.UNKNOWN) {
            throw CorruptStream.INSTANCE;
        }
        mode.shpmd = decode(VLC_SHAPE_MODE[colocated]);
        var shapeVector = mvBY[mbIndex];
        if (mode.shpmd == MbMode.ALL_TRANSP) {
            copyReconShapeToMbAndRef(mbBY, frm, frmOff, TRANSPARENT);
            shapeVector.mvx = MotionVector.NOT_MV;
            shapeVector.mvy = MotionVector.NOT_MV;
            setAllStatus(mode, MbMode.ALL);
        } else if (mode.shpmd == MbMode.ALL_OPAQUE) {
            copyReconShapeToMbAndRef(mbBY, frm, frmOff, OPAQUE);
            shapeVector.mvx = MotionVector.NOT_MV;
            shapeVector.mvy = MotionVector.NOT_MV;
            setAllStatus(mode, MbMode.NONE);
        } else if (mode.shpmd == MbMode.INTRA_CAE) {
            decodeIntraCaeBAB(mbBY, frm, frmOff);
            shapeVector.mvx = MotionVector.NOT_MV;
            shapeVector.mvy = MotionVector.NOT_MV;
            decideTransparencyStatus(mode, mbBY, 0);
        } else {
            shapeDiff.setToZero();
            if (mode.shpmd == MbMode.MVDNZ_NOUPDT || mode.shpmd == MbMode.INTER_CAE_MVDNZ) {
                decodeMVDS(shapeDiff);
            }
            findShapeMVP(shapePrediction, mvOff, mvBY, mbIndex, mbx, mby);
            shapeVector.setHalfPel(shapePrediction.trueX + shapeDiff.trueX, shapePrediction.trueY + shapeDiff.trueY);
            var pred = predBAB.pixels;
            if (volmd.volType == VolMode.ENHN_LAYER && vopmd.predType == VopMode.PVOP && volmd.spatialScalability
                    && volmd.hierarchyType == 0 && volmd.enhnType == 1 && volmd.useRefShape == 1) {
                Arrays.fill(pred, 0, MC_BAB_SIZE * MC_BAB_SIZE, 255);
            } else {
                motionCompBY(pred, ref.by.pixels, shapeVector.mvx + x - 1, shapeVector.mvy + y - 1);
            }
            if (mode.shpmd == MbMode.INTER_CAE_MVDZ || mode.shpmd == MbMode.INTER_CAE_MVDNZ) {
                inverseCR = 1;
                widthCurrBAB = BAB_SIZE;
                var predBab = pred;
                if (!volmd.noCrChange && bits.getBits(1) != 0) {
                    if (bits.getBits(1) == 0) {
                        inverseCR = 2;
                        widthCurrBAB = 12;
                        downSampleShapeMCPred(pred, predBABDown2, 2);
                        predBab = predBABDown2;
                        subsampleLeftTopBorderFromVOP(frm, frmOff, currMBBYDown2);
                        caeSymbol = currMBBYDown2;
                    } else {
                        inverseCR = 4;
                        widthCurrBAB = 8;
                        downSampleShapeMCPred(pred, predBABDown4, 4);
                        predBab = predBABDown4;
                        subsampleLeftTopBorderFromVOP(frm, frmOff, currMBBYDown4);
                        caeSymbol = currMBBYDown4;
                    }
                } else {
                    copyLeftTopBorderFromVOP(frm, frmOff, reconCurrBAB);
                    caeSymbol = reconCurrBAB;
                }
                caeSymbolOffset = 0;
                if (bits.getBits(1) == 1) {
                    decodeInterCAEH(predBab);
                } else {
                    decodeInterCAEV(predBab);
                }
                if (inverseCR > 1) {
                    upSampleShape(caeSymbol, reconCurrBAB);
                }
                copyReconShapeToMbAndRef(mbBY, frm, frmOff, reconCurrBAB, TOTAL_BAB_SIZE, BAB_BORDER);
            } else {
                copyReconShapeToMbAndRef(mbBY, frm, frmOff, pred, MC_BAB_SIZE, MC_BAB_BORDER);
            }
            decideTransparencyStatus(mode, mbBY, 0);
        }
    }

    private void setScalableBoundaries(int mbx, int mby) {
        setBoundaries(mbx, mby);
        vpNoRight = vpNoRight(mbx);
        vpNoBottom = vpNoBottom(mby);
    }

    private int refOffset(int x, int y) {
        return (y + EXPANDY_REF_FRAME) * frameWidthY + x + EXPANDY_REF_FRAME;
    }

    /**
     * Decodes the shape of a macroblock of a P-VOP of an object-based spatial scalability enhancement layer
     * ({@code decodeSIShapePVOP}).
     */
    final void decodeSIShapePVOP(VopPlanes ref, MbMode mode, int mbx, int mby, int x, int y, int[] mbBY, int[] frm,
                                 int frmOff) {
        setScalableBoundaries(mbx, mby);
        var pred = predBAB.pixels;
        var refBY = ref.by;
        if (volmd.useRefShape == 1 && !volmd.shapeOnly) {
            motionCompBY(pred, refBY.pixels, x - 1, y - 1);
            copyReconShapeToMbAndRef(mbBY, frm, frmOff, pred, MC_BAB_SIZE, MC_BAB_BORDER);
            decideTransparencyStatus(mode, mbBY, 0);
            return;
        }
        mode.shpssmd = decode(VLC_SHAPE_SSMODE_INTRA0);
        if (mode.shpssmd == MbMode.INTRA_NOT_CODED) {
            motionCompBY(pred, refBY.pixels, x - 1, y - 1);
            copyReconShapeToMbAndRef(mbBY, frm, frmOff, pred, MC_BAB_SIZE, MC_BAB_BORDER);
            decideTransparencyStatus(mode, mbBY, 0);
        } else if (mode.shpssmd == MbMode.INTRA_CODED) {
            motionCompBY(pred, refBY.pixels, x - 1, y - 1);
            copyReconShapeToMbAndRef(mbBY, frm, frmOff, pred, MC_BAB_SIZE, MC_BAB_BORDER);
            decodeSIBAB(mbBY, frm, frmOff, refBY.horSamplingCheck, x, refBY.verSamplingCheck, y, refBY.pixels,
                    refOffset(x, y));
            decideTransparencyStatus(mode, mbBY, 0);
        }
    }

    /**
     * Decodes the shape of a macroblock of a B-VOP of an object-based spatial scalability enhancement layer
     * ({@code decodeSIShapeBVOP}).
     *
     * @param past the previous VOP of the layer
     * @param lower the upsampled VOP of the reference layer
     */
    final void decodeSIShapeBVOP(VopPlanes past, VopPlanes lower, MbMode mode, int mbIndex, int mbx, int mby, int x,
                                 int y, int[] mbBY, int[] frm, int frmOff, int colocated) {
        setScalableBoundaries(mbx, mby);
        var pred = predBAB.pixels;
        var lowerBY = lower.by;
        if (volmd.useRefShape == 1 && !volmd.shapeOnly) {
            motionCompLowerBY(pred, lowerBY.pixels, x - 1, y - 1);
            copyReconShapeToMbAndRef(mbBY, frm, frmOff, pred, MC_BAB_SIZE, MC_BAB_BORDER);
            decideTransparencyStatus(mode, mbBY, 0);
            return;
        }
        if (vopmd.predType == VopMode.BVOP) {
            mode.shpssmd = decode(VLC_SHAPE_SSMODE_INTER[COLOCATED_INDEX[colocated]]);
        }
        if (mode.shpssmd == MbMode.INTRA_NOT_CODED) {
            motionCompLowerBY(pred, lowerBY.pixels, x - 1, y - 1);
            copyReconShapeToMbAndRef(mbBY, frm, frmOff, pred, MC_BAB_SIZE, MC_BAB_BORDER);
            decideTransparencyStatus(mode, mbBY, 0);
        } else if (mode.shpssmd == MbMode.INTRA_CODED) {
            motionCompLowerBY(pred, lowerBY.pixels, x - 1, y - 1);
            copyReconShapeToMbAndRef(mbBY, frm, frmOff, pred, MC_BAB_SIZE, MC_BAB_BORDER);
            decodeSIBAB(mbBY, frm, frmOff, lowerBY.horSamplingCheck, x, lowerBY.verSamplingCheck, y,
                    lowerBY.pixels, refOffset(x, y));
            decideTransparencyStatus(mode, mbBY, 0);
        } else {
            var base = mvBaseBY[mbIndex];
            if (base.mvx == MotionVector.NOT_MV && base.mvy == MotionVector.NOT_MV) {
                base.mvx = 0;
                base.mvy = 0;
            }
            var mx = (base.mvx * volmd.horSamplingFactorNShape
                    + Integer.signum(base.mvx) * volmd.horSamplingFactorMShape / 2) / volmd.horSamplingFactorMShape;
            var my = (base.mvy * volmd.verSamplingFactorNShape
                    + Integer.signum(base.mvy) * volmd.verSamplingFactorMShape / 2) / volmd.verSamplingFactorMShape;
            motionCompBY(pred, past.by.pixels, mx + x - 1, my + y - 1);
            if (mode.shpssmd == MbMode.INTER_NOT_CODED) {
                copyReconShapeToMbAndRef(mbBY, frm, frmOff, pred, MC_BAB_SIZE, MC_BAB_BORDER);
            } else if (mode.shpssmd == MbMode.INTER_CODED) {
                inverseCR = 1;
                widthCurrBAB = BAB_SIZE;
                copyLeftTopBorderFromVOP(frm, frmOff, reconCurrBAB);
                caeSymbol = reconCurrBAB;
                caeSymbolOffset = 0;
                if (bits.getBits(1) == 1) {
                    decodeInterCAEH(pred);
                } else {
                    decodeInterCAEV(pred);
                }
                copyReconShapeToMbAndRef(mbBY, frm, frmOff, reconCurrBAB, TOTAL_BAB_SIZE, BAB_BORDER);
            }
            decideTransparencyStatus(mode, mbBY, 0);
        }
    }

    private static int log2Floor(double value) {
        return (int) Math.floor(value + 0.000001);
    }

    /**
     * Decodes an enhancement binary alpha block of the scalable shape coding (7.5.5, {@code decodeSIBAB}).
     *
     * @param horCheck the columns of the reference that the upsampling took from the base layer
     * @param horOff the index of the macroblock's first column in {@code horCheck}
     * @param refSrc the shape plane of the reference
     * @param refOff the index of the macroblock in {@code refSrc}
     */
    private void decodeSIBAB(int[] mbBY, int[] frm, int frmOff, boolean[] horCheck, int horOff, boolean[] verCheck,
                             int verOff, int[] refSrc, int refOff) {
        widthCurrBAB = BAB_SIZE;
        copyLeftTopBorderFromVOP(frm, frmOff, reconCurrBAB);
        var hFactor = StrictMath.log((double) volmd.horSamplingFactorNShape / volmd.horSamplingFactorMShape)
                / StrictMath.log(2);
        var hFactorInt = log2Floor(hFactor);
        var vFactor = StrictMath.log((double) volmd.verSamplingFactorNShape / volmd.verSamplingFactorMShape)
                / StrictMath.log(2);
        var vFactorInt = log2Floor(vFactor);
        var residualX = hFactor - hFactorInt > 0.000001;
        var residualY = vFactor - vFactorInt > 0.000001;
        for (var j = 0; j < 16; j++) {
            System.arraycopy(mbBY, j * 16, reconCurrBAB, 20 * 2 + 2 + j * 20, 16);
        }
        makeRightBottomBorder(reconCurrBAB, TOTAL_BAB_SIZE, refSrc, refOff, frameWidthY);
        caeSymbol = reconCurrBAB;
        caeSymbolOffset = 0;
        var hCheck = horizontalChecks;
        var vCheck = verticalChecks;
        copyChecks(hCheck, horCheck, horOff);
        copyChecks(vCheck, verCheck, verOff);
        var dyadic = volmd.horSamplingFactorNShape == 2 && volmd.horSamplingFactorMShape == 1
                && volmd.verSamplingFactorNShape == 2 && volmd.verSamplingFactorMShape == 1;
        var scanOrder = 0;
        if (dyadic) {
            scanOrder = decideScanOrder(predBAB.pixels);
            if (scanOrder != 0) {
                for (var j = 0; j < BAB_SIZE; j++) {
                    for (var i = 0; i < BAB_SIZE; i++) {
                        transposed[j * BAB_SIZE + i] = reconCurrBAB[i * BAB_SIZE + j];
                    }
                }
                caeSymbol = transposed;
                var tmp = hCheck;
                hCheck = vCheck;
                vCheck = tmp;
            }
        }
        arCodec.start(bits);
        var codingMode = arCodec.decode(RefTables.SI_BAB_TYPE_PROB[0], bits);
        if (codingMode == 0) {
            if (residualX || hFactorInt > 0) {
                verticalDecoding(hFactorInt, vFactorInt, residualX, hCheck, vCheck, true);
            }
            if (residualY || vFactorInt > 0) {
                horizontalDecoding(vFactorInt, residualY, vCheck, true);
            }
        } else {
            if (residualX || hFactorInt > 0) {
                verticalDecoding(hFactorInt, vFactorInt, residualX, hCheck, vCheck, false);
            }
            if (residualY || vFactorInt > 0) {
                horizontalDecoding(vFactorInt, residualY, vCheck, false);
            }
        }
        arCodec.stop(bits);
        if (dyadic && scanOrder != 0) {
            for (var j = 0; j < BAB_SIZE; j++) {
                for (var i = 0; i < BAB_SIZE; i++) {
                    reconCurrBAB[j * BAB_SIZE + i] = transposed[i * BAB_SIZE + j];
                }
            }
        }
        copyReconShapeToMbAndRef(mbBY, frm, frmOff, reconCurrBAB, TOTAL_BAB_SIZE, BAB_BORDER);
    }

    private static void copyChecks(boolean[] dst, boolean[] src, int off) {
        for (var i = 0; i < dst.length; i++) {
            dst[i] = src != null && off + i >= 0 && off + i < src.length && src[off + i];
        }
    }

    private static int firstTrue(boolean[] check, boolean skipLeadingTrue) {
        var i = 0;
        if (skipLeadingTrue) {
            while (i < check.length && check[i]) {
                i++;
            }
        }
        while (i < check.length && !check[i]) {
            i++;
        }
        if (i >= check.length) {
            throw CorruptStream.INSTANCE;
        }
        return i;
    }

    private static int firstFalseThenTrue(boolean[] check) {
        var i = 0;
        while (i < check.length && !check[i]) {
            i++;
        }
        if (i >= check.length) {
            throw CorruptStream.INSTANCE;
        }
        return i;
    }

    /**
     * Decodes the columns of a scalable binary alpha block that the horizontal upsampling leaves out, in exclusive
     * or ({@code VerticalXORdecoding}) or full ({@code VerticalFulldecoding}) mode.
     */
    private void verticalDecoding(int numLoopHor, int numLoopVer, boolean residualHor, boolean[] horCheck,
                                  boolean[] verCheck, boolean xor) {
        var data = caeSymbol;
        var smb = widthCurrBAB * BAB_BORDER + BAB_BORDER;
        var width = 20;
        var height = 20;
        var startH = 0;
        var tmp = xor ? firstFalseThenTrue(horCheck) : firstTrue(horCheck, true);
        if (residualHor) {
            var hScanFreq = 1 << numLoopHor;
            var vScanFreq = 1 << numLoopVer;
            var skipHor = 1 << numLoopHor;
            if (tmp - (1 << numLoopHor) >= 0) {
                startH = tmp - (1 << numLoopHor);
            } else if (tmp + (1 << numLoopHor) <= MB_SIZE - 1) {
                startH = tmp + (1 << numLoopHor);
            }
            var startV = firstFalseThenTrue(verCheck);
            for (var j = startH; j < width - 4; j += hScanFreq) {
                if (horCheck[j]) {
                    continue;
                }
                verticalColumn(data, smb, j, startV, vScanFreq, hScanFreq, skipHor, verCheck, width, height, xor);
            }
        }
        var i = xor ? firstFalseThenTrue(horCheck) : firstTrue(horCheck, true);
        tmp = i > startH && residualHor ? startH : i;
        while (numLoopHor > 0) {
            var hScanFreq = 1 << numLoopHor;
            var vScanFreq = 1 << numLoopVer;
            var skipHor = 1 << (numLoopHor - 1);
            if (tmp - (1 << (numLoopHor - 1)) >= 0) {
                tmp = startH = tmp - (1 << (numLoopHor - 1));
            } else {
                startH = tmp + (1 << (numLoopHor - 1));
            }
            var startV = firstFalseThenTrue(verCheck);
            for (var j = startH; j < width - 4; j += hScanFreq) {
                verticalColumn(data, smb, j, startV, vScanFreq, 1 << (numLoopHor - 1), skipHor, verCheck, width,
                        height, xor);
            }
            numLoopHor--;
        }
    }

    private void verticalColumn(int[] data, int smb, int j, int startV, int vScanFreq, int neighbor, int skipHor,
                                boolean[] verCheck, int width, int height, boolean xor) {
        var skipUpper = startV + 1;
        for (var i = startV; i < height - 4; i += vScanFreq) {
            if (!verCheck[i]) {
                continue;
            }
            int skipBottom;
            if (i + vScanFreq > 15) {
                skipBottom = MB_SIZE + 1 - i;
            } else if (!verCheck[i + vScanFreq]) {
                skipBottom = i + vScanFreq * 2 > 15 ? MB_SIZE + 1 - i : vScanFreq * 2;
            } else {
                skipBottom = vScanFreq;
            }
            var skipRight = j + skipHor > 15 ? MB_SIZE + 1 - j : skipHor;
            var skipLeft = j - skipHor < 0 ? j + 2 : skipHor;
            var at = smb + i * width + j;
            if (xor) {
                var prev = j - neighbor < -2 ? data[smb + i * width - 2] : data[at - neighbor];
                var next = j + neighbor > 17 ? data[smb + i * width + 17] : data[at + neighbor];
                if (prev == next) {
                    data[at] = prev;
                } else {
                    data[at] = decodeSymbol(RefTables.ENH_INTRA_V_PROB[contextSIVertical(data, at, skipRight,
                            skipLeft, skipUpper, skipBottom)]);
                }
            } else {
                data[at] = decodeSymbol(RefTables.ENH_INTRA_V_PROB[contextSIVertical(data, at, skipRight, skipLeft,
                        skipUpper, skipBottom)]);
            }
            skipUpper = skipBottom == vScanFreq ? vScanFreq : vScanFreq * 2;
        }
    }

    private int decodeSymbol(int probability) {
        return arCodec.decode(probability, bits) != 0 ? OPAQUE : TRANSPARENT;
    }

    /**
     * Decodes the rows of a scalable binary alpha block that the vertical upsampling leaves out, in exclusive or
     * ({@code HorizontalXORdecoding}) or full ({@code HorizontalFulldecoding}) mode.
     */
    private void horizontalDecoding(int numLoopVer, boolean residualVer, boolean[] verCheck, boolean xor) {
        var data = caeSymbol;
        var smb = widthCurrBAB * BAB_BORDER + BAB_BORDER;
        var width = 20;
        var height = 20;
        var startV = 0;
        var tmp = firstTrue(verCheck, true);
        if (residualVer) {
            var vScanFreq = 1 << numLoopVer;
            var skipVer = vScanFreq;
            if (tmp - (1 << numLoopVer) >= 0) {
                startV = tmp - (1 << numLoopVer);
            } else if (tmp + (1 << numLoopVer) <= MB_SIZE - 1) {
                startV = tmp + (1 << numLoopVer);
            }
            for (var i = startV; i < height - 4; i += vScanFreq) {
                if (verCheck[i]) {
                    continue;
                }
                horizontalRow(data, smb, i, vScanFreq, skipVer, width, xor);
            }
        }
        var i = firstTrue(verCheck, true);
        tmp = i > startV && residualVer ? startV : i;
        while (numLoopVer > 0) {
            var vScanFreq = 1 << numLoopVer;
            if (tmp - (1 << (numLoopVer - 1)) >= 0) {
                tmp = startV = tmp - (1 << (numLoopVer - 1));
            } else {
                startV = tmp + (1 << (numLoopVer - 1));
            }
            var skipVer = 1 << (numLoopVer - 1);
            for (i = startV; i < height - 4; i += vScanFreq) {
                horizontalRow(data, smb, i, skipVer, skipVer, width, xor);
            }
            numLoopVer--;
        }
    }

    private void horizontalRow(int[] data, int smb, int i, int neighbor, int skipVer, int width, boolean xor) {
        for (var j = 0; j < width - 4; j++) {
            var at = smb + i * width + j;
            var skipBottom = i + skipVer > 15 ? MB_SIZE + 1 - i : skipVer;
            var skipUpper = i - skipVer < 0 ? i + 2 : skipVer;
            if (xor) {
                var prev = i - neighbor < -2 ? data[smb - 2 * width + j] : data[at - neighbor * width];
                var next = i + neighbor > 17 ? data[smb + 17 * width + j] : data[at + neighbor * width];
                if (prev == next) {
                    data[at] = prev;
                    continue;
                }
            }
            data[at] = decodeSymbol(RefTables.ENH_INTRA_H_PROB[contextSIHorizontal(data, at, skipUpper,
                    skipBottom)]);
        }
    }

    /**
     * {@return the base index of the first shape mode code of an intra VOP macroblock from its neighbors
     * ({@code shpMdTableIndex})}
     */
    final int shpMdTableIndex(int mbIndex, int mbx, int mby) {
        var mbNum = vpMbNum(mbx, mby);
        var top = !vpNoTop(mbNum) ? mbmd[mbIndex - numMBX].shpmd : MbMode.ALL_TRANSP;
        var topRight = !vpNoRightTop(mbNum, mbx) ? mbmd[mbIndex - numMBX + 1].shpmd : MbMode.ALL_TRANSP;
        var topLeft = !vpNoLeftTop(mbNum, mbx) ? mbmd[mbIndex - numMBX - 1].shpmd : MbMode.ALL_TRANSP;
        var left = !vpNoLeft(mbNum, mbx) ? mbmd[mbIndex - 1].shpmd : MbMode.ALL_TRANSP;
        return topLeft * 81 + top * 27 + topRight * 9 + left * 3;
    }

    /**
     * Decodes an intra binary alpha block with its conversion ratio ({@code decodeIntraCaeBAB}).
     */
    final void decodeIntraCaeBAB(int[] mbBY, int[] frm, int frmOff) {
        caeSymbolOffset = 0;
        if (volmd.noCrChange || bits.getBits(1) == 0) {
            inverseCR = 1;
            widthCurrBAB = BAB_SIZE;
            copyLeftTopBorderFromVOP(frm, frmOff, reconCurrBAB);
            caeSymbol = reconCurrBAB;
            if (bits.getBits(1) == 1) {
                decodeIntraCAEH();
            } else {
                decodeIntraCAEV();
            }
        } else {
            if (bits.getBits(1) == 0) {
                inverseCR = 2;
                widthCurrBAB = 12;
                subsampleLeftTopBorderFromVOP(frm, frmOff, currMBBYDown2);
                caeSymbol = currMBBYDown2;
            } else {
                inverseCR = 4;
                widthCurrBAB = 8;
                subsampleLeftTopBorderFromVOP(frm, frmOff, currMBBYDown4);
                caeSymbol = currMBBYDown4;
            }
            if (bits.getBits(1) == 1) {
                decodeIntraCAEH();
            } else {
                decodeIntraCAEV();
            }
            upSampleShape(caeSymbol, reconCurrBAB);
        }
        copyReconShapeToMbAndRef(mbBY, frm, frmOff, reconCurrBAB, TOTAL_BAB_SIZE, BAB_BORDER);
    }

    private void decodeIntraCAEH() {
        arCodec.start(bits);
        var w = widthCurrBAB;
        var data = caeSymbol;
        var row = w * BAB_BORDER + BAB_BORDER;
        for (var r = BAB_BORDER; r < w - BAB_BORDER; r++) {
            var d = row;
            for (var c = BAB_BORDER; c < w - BAB_BORDER; c++) {
                data[d] = decodeSymbol(RefTables.CAEINTRA_PROB[contextIntra(data, d)]);
                d++;
            }
            data[d] = data[d - 1];
            data[d + 1] = data[d];
            row += w;
        }
        arCodec.stop(bits);
        bottomBorder(data, w);
    }

    private void decodeIntraCAEV() {
        arCodec.start(bits);
        var w = widthCurrBAB;
        var data = caeSymbol;
        var col = w * BAB_BORDER + BAB_BORDER;
        for (var c = BAB_BORDER; c < w - BAB_BORDER; c++) {
            var d = col;
            for (var r = BAB_BORDER; r < w - BAB_BORDER; r++) {
                data[d] = decodeSymbol(RefTables.CAEINTRA_PROB[contextIntraTranspose(data, d)]);
                d += w;
            }
            data[d] = data[d - w];
            data[d + w] = data[d];
            col++;
        }
        arCodec.stop(bits);
        rightBorder(data, w);
    }

    private static void bottomBorder(int[] data, int w) {
        var d = w * (w - BAB_BORDER) + BAB_BORDER;
        for (var c = BAB_BORDER; c < w; c++) {
            data[d] = data[d - w];
            data[d + w] = data[d];
            d++;
        }
    }

    private static void rightBorder(int[] data, int w) {
        var d = (w - BAB_BORDER) + w * BAB_BORDER;
        for (var r = BAB_BORDER; r < w; r++) {
            data[d] = data[d - 1];
            data[d + 1] = data[d];
            d += w;
        }
    }

    private void decodeInterCAEH(int[] pred) {
        arCodec.start(bits);
        var w = widthCurrBAB;
        var predSize = w - 2 * BAB_BORDER + 2 * MC_BAB_BORDER;
        var mbSize = w - 2 * BAB_BORDER;
        var data = caeSymbol;
        var row = w * BAB_BORDER + BAB_BORDER;
        var predRow = predSize * MC_BAB_BORDER + MC_BAB_BORDER;
        for (var r = 0; r < mbSize; r++) {
            var d = row;
            var p = predRow;
            for (var c = 0; c < mbSize; c++) {
                data[d] = decodeSymbol(RefTables.CAEINTER_PROB[contextInter(data, d, pred, p)]);
                d++;
                p++;
            }
            data[d] = data[d - 1];
            data[d + 1] = data[d];
            row += w;
            predRow += predSize;
        }
        arCodec.stop(bits);
        bottomBorder(data, w);
    }

    private void decodeInterCAEV(int[] pred) {
        arCodec.start(bits);
        var w = widthCurrBAB;
        var predSize = w - 2 * BAB_BORDER + 2 * MC_BAB_BORDER;
        var mbSize = w - 2 * BAB_BORDER;
        var data = caeSymbol;
        var col = w * BAB_BORDER + BAB_BORDER;
        var predCol = predSize * MC_BAB_BORDER + MC_BAB_BORDER;
        for (var c = 0; c < mbSize; c++) {
            var d = col;
            var p = predCol;
            for (var r = 0; r < mbSize; r++) {
                data[d] = decodeSymbol(RefTables.CAEINTER_PROB[contextInterTranspose(data, d, pred, p)]);
                d += w;
                p += predSize;
            }
            data[d] = data[d - w];
            data[d + w] = data[d];
            col++;
            predCol++;
        }
        arCodec.stop(bits);
        rightBorder(data, w);
    }

    /**
     * Decodes a shape motion vector difference ({@code decodeMVDS}).
     */
    private void decodeMVDS(MotionVector diff) {
        diff.mvx = decode(VLC_SHAPE_MV1);
        if (diff.mvx != 0 && bits.getBits(1) == 0) {
            diff.mvx = -diff.mvx;
        }
        if (diff.mvx == 0) {
            diff.mvy = decode(VLC_SHAPE_MV2) + 1;
        } else {
            diff.mvy = decode(VLC_SHAPE_MV1);
        }
        if (diff.mvy != 0 && bits.getBits(1) == 0) {
            diff.mvy = -diff.mvy;
        }
        diff.computeTrueMV();
    }
}
