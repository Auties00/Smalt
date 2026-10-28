/* Ports the Microsoft MPEG-4 Visual reference software of ISO/IEC 14496-5:2001 (version 2 FDAM1-2.3-001213,
 * sys/decoder/vopsedec.cpp: decodeVOLHead and decodeVOPHead). Copyright 1996-2000 Microsoft Corporation, Sharp
 * Corporation, Sony Corporation, Toshiba Corporation, Hughes Electronics, EPFL, Hitachi, Samsung AIT, Oki Electric
 * Industry and the contributors named in the sources; ISO/IEC free license for products conforming to ISO/IEC
 * 14496-2. Modified for Smalt. */
package com.github.auties00.smalt.video.mpeg4.internal.object;

import java.util.Arrays;

/**
 * The video object layer and video object plane headers (6.2.3, 6.2.5) as the object decoder reads them, with the
 * sprite, NEWPRED, reduced resolution, scalability and complexity estimation fields of version 2.
 */
abstract class ObjectHeaders extends ObjectSprite {
    int clockRateScale = 1;
    int volId;
    /** The {@code vop_width} of the last VOP header with a bounding rectangle, before rounding to macroblocks. */
    int vopWidthCoded;
    /** The {@code vop_height} of the last VOP header with a bounding rectangle, before rounding to macroblocks. */
    int vopHeightCoded;
    boolean shapeExtensionSeen;
    int estimationOpaque;
    final int[] complexity = new int[24];

    /**
     * {@return the number of auxiliary components of a grayscale shape extension ({@code getAuxCompCount})}
     */
    static int auxComponents(int extension) {
        return auxCompCount(extension);
    }

    private void marker() {
        if (bits.getBits(1) != 1) {
            throw CorruptStream.INSTANCE;
        }
    }

    private void readMatrix(int[] matrix, int[] defaults, boolean load) {
        if (!load) {
            System.arraycopy(defaults, 0, matrix, 0, 64);
            return;
        }
        var zigzag = RefTables.STANDARD_ZIGZAG;
        var i = 0;
        int element;
        do {
            element = bits.getBits(8);
            matrix[zigzag[i]] = element;
        } while (element != 0 && ++i < 64);
        if (i == 0) {
            throw CorruptStream.INSTANCE;
        }
        for (var j = i; j < 64; j++) {
            matrix[zigzag[j]] = matrix[zigzag[i - 1]];
        }
    }

    /**
     * Decodes a video object layer header from just after its start code ({@code decodeVOLHead}).
     *
     * @param id the four low bits of the start code
     */
    final void decodeVOLHead(int id) {
        volId = id;
        bits.getBits(1);
        bits.getBits(8);
        if (bits.getBits(1) != 0) {
            volmd.verId = Math.min(2, bits.getBits(4));
            bits.getBits(3);
        } else {
            volmd.verId = 1;
        }
        if (bits.getBits(4) == 15) {
            bits.getBits(8);
            bits.getBits(8);
        }
        if (bits.getBits(1) != 0) {
            bits.getBits(2);
            bits.getBits(1);
            if (bits.getBits(1) != 0) {
                bits.getBits(15);
                marker();
                bits.getBits(15);
                marker();
                bits.getBits(15);
                marker();
                bits.getBits(3);
                bits.getBits(11);
                marker();
                bits.getBits(15);
                marker();
            }
        }
        var usage = bits.getBits(2);
        if (usage == 3) {
            if (volmd.verId != 1) {
                volmd.alphaShapeExtension = bits.getBits(4);
                volmd.auxCompCount = auxCompCount(volmd.alphaShapeExtension);
            } else {
                volmd.auxCompCount = 1;
            }
        } else {
            volmd.auxCompCount = 0;
        }
        marker();
        volmd.clockRate = bits.getBits(16);
        marker();
        if (volmd.clockRate == 0) {
            throw CorruptStream.INSTANCE;
        }
        var clock = volmd.clockRate - 1;
        if (clock > 0) {
            for (numBitsTimeIncr = 1; numBitsTimeIncr < 16; numBitsTimeIncr++) {
                if (clock == 1) {
                    break;
                }
                clock >>= 1;
            }
        } else {
            numBitsTimeIncr = 1;
        }
        if (bits.getBits(1) != 0) {
            bits.getBits(numBitsTimeIncr);
        }
        if (usage == 2) {
            decodeShapeOnlyVOLHead();
            return;
        }
        volmd.shapeOnly = false;
        volmd.alphaUsage = usage == 3 ? VopPlanes.EIGHT_BIT : usage;
        if (volmd.alphaUsage == VopPlanes.RECTANGLE) {
            marker();
            volWidth = bits.getBits(13);
            marker();
            volHeight = bits.getBits(13);
            marker();
        }
        vopmd.interlace = bits.getBits(1) != 0;
        volmd.advPredDisable = bits.getBits(1) != 0;
        uiSprite = volmd.verId == 1 ? bits.getBits(1) : bits.getBits(2);
        if (uiSprite == 3) {
            throw CorruptStream.INSTANCE;
        }
        if (uiSprite == 1) {
            var hdim = bits.getBits(13);
            marker();
            var vdim = bits.getBits(13);
            marker();
            var leftEdge = bits.getBits(1) == 0 ? bits.getBits(12) : bits.getBits(12) - (1 << 12);
            marker();
            var topEdge = bits.getBits(1) == 0 ? bits.getBits(12) : bits.getBits(12) - (1 << 12);
            marker();
            rctSpt.set(leftEdge, topEdge, leftEdge + hdim, topEdge + vdim);
            rctSptPieceY.set(rctSpt);
        }
        if (uiSprite == 1 || uiSprite == 2) {
            numOfPnts = bits.getBits(6);
            if ((uiSprite == 2 && numOfPnts > 3) || numOfPnts > 4) {
                throw CorruptStream.INSTANCE;
            }
            srcQx = new double[4];
            srcQy = new double[4];
            dstQx = new double[4];
            dstQy = new double[4];
            warpingAccuracy = bits.getBits(2);
            lightChange = bits.getBits(1) != 0;
        }
        if (uiSprite == 1) {
            sptMode = bits.getBits(1) != 0 ? LOW_LATENCY : BASIC_SPRITE;
        }
        if (volmd.alphaUsage != VopPlanes.RECTANGLE) {
            volmd.sadctDisable = volmd.verId == 1 || bits.getBits(1) != 0;
        }
        volmd.not8Bit = bits.getBits(1) != 0;
        if (volmd.not8Bit) {
            volmd.quantPrecision = bits.getBits(4);
            volmd.nBits = bits.getBits(4);
            if (volmd.nBits < 4 || volmd.nBits > 12 || volmd.quantPrecision < 3 || volmd.quantPrecision > 9) {
                throw CorruptStream.INSTANCE;
            }
        } else {
            volmd.quantPrecision = 5;
            volmd.nBits = 8;
        }
        if (volmd.alphaUsage == VopPlanes.EIGHT_BIT) {
            volmd.noGrayQuantUpdate = bits.getBits(1) != 0;
            bits.getBits(1);
            bits.getBits(1);
        }
        volmd.quantizer = bits.getBits(1);
        if (volmd.quantizer == VolMode.Q_MPEG) {
            volmd.loadIntraMatrix = bits.getBits(1) != 0;
            readMatrix(volmd.intraQuantizerMatrix, RefTables.DEFAULT_INTRA_QMATRIX, volmd.loadIntraMatrix);
            volmd.loadInterMatrix = bits.getBits(1) != 0;
            readMatrix(volmd.interQuantizerMatrix, RefTables.DEFAULT_INTER_QMATRIX, volmd.loadInterMatrix);
            if (volmd.alphaUsage == VopPlanes.EIGHT_BIT) {
                // the alpha planes default to the matrices of the texture (6.3.3)
                for (var aux = 0; aux < volmd.auxCompCount; aux++) {
                    volmd.loadIntraMatrixAlpha = bits.getBits(1) != 0;
                    readMatrix(volmd.intraQuantizerMatrixAlpha[aux], RefTables.DEFAULT_INTRA_QMATRIX,
                            volmd.loadIntraMatrixAlpha);
                    volmd.loadInterMatrixAlpha = bits.getBits(1) != 0;
                    readMatrix(volmd.interQuantizerMatrixAlpha[aux], RefTables.DEFAULT_INTER_QMATRIX,
                            volmd.loadInterMatrixAlpha);
                }
            }
        }
        volmd.quarterSample = volmd.verId != 1 && bits.getBits(1) != 0;
        volmd.complexityEstimationDisable = bits.getBits(1) != 0;
        if (!volmd.complexityEstimationDisable) {
            decodeComplexityEstimationHeader();
        }
        volmd.resyncMarkerDisable = bits.getBits(1) != 0;
        volmd.dataPartitioning = bits.getBits(1) != 0;
        volmd.reversibleVlc = volmd.dataPartitioning && bits.getBits(1) != 0;
        if (volmd.verId != 1) {
            volmd.newpredEnable = bits.getBits(1) != 0;
            if (volmd.newpredEnable) {
                if (volmd.alphaUsage != VopPlanes.RECTANGLE || vopmd.interlace || uiSprite != 0) {
                    throw CorruptStream.INSTANCE;
                }
                volmd.requestedBackwardMessageType = bits.getBits(2);
                volmd.newpredSegmentType = bits.getBits(1) != 0;
            }
            volmd.reducedResolutionVopEnable = bits.getBits(1) != 0;
        }
        vopmd.rrvEnable = volmd.reducedResolutionVopEnable ? 1 : 0;
        volmd.volType = bits.getBits(1) == 0 ? VolMode.BASE_LAYER : VolMode.ENHN_LAYER;
        if ((volmd.newpredEnable || uiSprite == 2) && volmd.volType != VolMode.BASE_LAYER) {
            throw CorruptStream.INSTANCE;
        }
        volmd.horSamplingFactorN = volmd.horSamplingFactorM = 1;
        volmd.verSamplingFactorN = volmd.verSamplingFactorM = 1;
        volmd.horSamplingFactorNShape = volmd.horSamplingFactorMShape = 1;
        volmd.verSamplingFactorNShape = volmd.verSamplingFactorMShape = 1;
        if (volmd.volType == VolMode.ENHN_LAYER) {
            volmd.hierarchyType = bits.getBits(1);
            bits.getBits(4);
            bits.getBits(1);
            volmd.horSamplingFactorN = bits.getBits(5);
            volmd.horSamplingFactorM = bits.getBits(5);
            volmd.verSamplingFactorN = bits.getBits(5);
            volmd.verSamplingFactorM = bits.getBits(5);
            volmd.enhnType = bits.getBits(1);
            if (volmd.alphaUsage == VopPlanes.ONE_BIT && volmd.hierarchyType == 0) {
                volmd.useRefShape = bits.getBits(1);
                volmd.useRefTexture = bits.getBits(1);
                volmd.horSamplingFactorNShape = bits.getBits(5);
                volmd.horSamplingFactorMShape = bits.getBits(5);
                volmd.verSamplingFactorNShape = bits.getBits(5);
                volmd.verSamplingFactorMShape = bits.getBits(5);
            }
            checkSamplingFactors();
        }
        volmd.deblockFilterDisable = true;
    }

    private void checkSamplingFactors() {
        if (volmd.horSamplingFactorN == 0 || volmd.horSamplingFactorM == 0 || volmd.verSamplingFactorN == 0
                || volmd.verSamplingFactorM == 0 || volmd.horSamplingFactorNShape == 0
                || volmd.horSamplingFactorMShape == 0 || volmd.verSamplingFactorNShape == 0
                || volmd.verSamplingFactorMShape == 0) {
            throw CorruptStream.INSTANCE;
        }
    }

    private void decodeShapeOnlyVOLHead() {
        if (volmd.verId == 2) {
            volmd.volType = bits.getBits(1) == 0 ? VolMode.BASE_LAYER : VolMode.ENHN_LAYER;
            volmd.enhnType = 0;
            volmd.hierarchyType = 0;
            volmd.horSamplingFactorN = volmd.horSamplingFactorM = 1;
            volmd.verSamplingFactorN = volmd.verSamplingFactorM = 1;
            volmd.horSamplingFactorNShape = volmd.horSamplingFactorMShape = 1;
            volmd.verSamplingFactorNShape = volmd.verSamplingFactorMShape = 1;
            if (volmd.volType == VolMode.ENHN_LAYER) {
                volmd.horSamplingFactorNShape = bits.getBits(5);
                volmd.horSamplingFactorMShape = bits.getBits(5);
                volmd.verSamplingFactorNShape = bits.getBits(5);
                volmd.verSamplingFactorMShape = bits.getBits(5);
                volmd.horSamplingFactorN = volmd.horSamplingFactorNShape;
                volmd.horSamplingFactorM = volmd.horSamplingFactorMShape;
                volmd.verSamplingFactorN = volmd.verSamplingFactorNShape;
                volmd.verSamplingFactorM = volmd.verSamplingFactorMShape;
                checkSamplingFactors();
            }
        }
        volmd.resyncMarkerDisable = bits.getBits(1) != 0;
        volmd.shapeOnly = true;
        volmd.alphaUsage = VopPlanes.ONE_BIT;
        volmd.advPredDisable = false;
        volmd.quantizer = VolMode.Q_H263;
        volmd.sadctDisable = true;
        volmd.newpredEnable = false;
        volmd.quarterSample = false;
        volmd.deblockFilterDisable = true;
        uiSprite = 0;
        volmd.not8Bit = false;
        volmd.complexityEstimationDisable = true;
        volmd.dataPartitioning = false;
        volmd.reversibleVlc = false;
    }

    private void decodeComplexityEstimationHeader() {
        volmd.estimationMethod = bits.getBits(2);
        if (volmd.estimationMethod != 0 && volmd.estimationMethod != 1) {
            throw CorruptStream.INSTANCE;
        }
        volmd.shapeComplexityEstimationDisable = bits.getBits(1) != 0;
        if (!volmd.shapeComplexityEstimationDisable) {
            volmd.opaque = bits.getBits(1) != 0;
            volmd.transparent = bits.getBits(1) != 0;
            volmd.intraCAE = bits.getBits(1) != 0;
            volmd.interCAE = bits.getBits(1) != 0;
            volmd.noUpdate = bits.getBits(1) != 0;
            volmd.upsampling = bits.getBits(1) != 0;
        } else {
            volmd.opaque = volmd.transparent = volmd.intraCAE = volmd.interCAE = volmd.noUpdate =
                    volmd.upsampling = false;
        }
        volmd.textureComplexityEstimationSet1Disable = bits.getBits(1) != 0;
        if (!volmd.textureComplexityEstimationSet1Disable) {
            volmd.intraBlocks = bits.getBits(1) != 0;
            volmd.interBlocks = bits.getBits(1) != 0;
            volmd.inter4vBlocks = bits.getBits(1) != 0;
            volmd.notCodedBlocks = bits.getBits(1) != 0;
        } else {
            volmd.intraBlocks = volmd.interBlocks = volmd.inter4vBlocks = volmd.notCodedBlocks = false;
        }
        marker();
        volmd.textureComplexityEstimationSet2Disable = bits.getBits(1) != 0;
        if (!volmd.textureComplexityEstimationSet2Disable) {
            volmd.dctCoefs = bits.getBits(1) != 0;
            volmd.dctLines = bits.getBits(1) != 0;
            volmd.vlcSymbols = bits.getBits(1) != 0;
            volmd.vlcBits = bits.getBits(1) != 0;
        } else {
            volmd.dctCoefs = volmd.dctLines = volmd.vlcSymbols = volmd.vlcBits = false;
        }
        volmd.motionCompensationComplexityDisable = bits.getBits(1) != 0;
        if (!volmd.motionCompensationComplexityDisable) {
            volmd.apm = bits.getBits(1) != 0;
            volmd.npm = bits.getBits(1) != 0;
            volmd.interpolateMCQ = bits.getBits(1) != 0;
            volmd.forwBackMCQ = bits.getBits(1) != 0;
            volmd.halfpel2 = bits.getBits(1) != 0;
            volmd.halfpel4 = bits.getBits(1) != 0;
        } else {
            volmd.apm = volmd.npm = volmd.interpolateMCQ = volmd.forwBackMCQ = volmd.halfpel2 = volmd.halfpel4 =
                    false;
        }
        marker();
        if (volmd.estimationMethod == 1) {
            volmd.version2ComplexityEstimationDisable = bits.getBits(1) != 0;
            if (!volmd.version2ComplexityEstimationDisable) {
                volmd.sadct = bits.getBits(1) != 0;
                volmd.quarterpel = bits.getBits(1) != 0;
            } else {
                volmd.sadct = volmd.quarterpel = false;
            }
        } else {
            volmd.version2ComplexityEstimationDisable = true;
            volmd.sadct = volmd.quarterpel = false;
        }
    }

    private void estimate(boolean present, int length) {
        if (present && bits.getBits(length) == 0) {
            throw CorruptStream.INSTANCE;
        }
    }

    private void decodeComplexityEstimation() {
        var type = vopmd.predType;
        var ipb = type == VopMode.IVOP || type == VopMode.PVOP || type == VopMode.BVOP;
        var pbs = type == VopMode.PVOP || type == VopMode.BVOP || (type == VopMode.SPRITE && uiSprite == 1);
        var bs = type == VopMode.BVOP || (type == VopMode.SPRITE && uiSprite == 1);
        if (ipb) {
            estimate(volmd.opaque, 8);
            estimate(volmd.transparent, 8);
            estimate(volmd.intraCAE, 8);
            estimate(volmd.interCAE, 8);
            estimate(volmd.noUpdate, 8);
            estimate(volmd.upsampling, 8);
        }
        estimate(volmd.intraBlocks, 8);
        estimate(volmd.notCodedBlocks, 8);
        estimate(volmd.dctCoefs, 8);
        estimate(volmd.dctLines, 8);
        estimate(volmd.vlcSymbols, 8);
        estimate(volmd.vlcBits, 4);
        if (pbs) {
            estimate(volmd.interBlocks, 8);
            estimate(volmd.inter4vBlocks, 8);
            estimate(volmd.apm, 8);
            estimate(volmd.npm, 8);
            estimate(volmd.forwBackMCQ, 8);
            estimate(volmd.halfpel2, 8);
            estimate(volmd.halfpel4, 8);
        }
        if (bs) {
            estimate(volmd.interpolateMCQ, 8);
        }
        if (ipb) {
            estimate(volmd.sadct, 8);
        }
        if (pbs) {
            estimate(volmd.quarterpel, 8);
        }
    }

    private void decodeFCode(VopMode.MvInfo info) {
        info.fCode = bits.getBits(3);
        if (info.fCode == 0) {
            throw CorruptStream.INSTANCE;
        }
        info.scaleFactor = 1 << (info.fCode - 1);
        info.range = 16 << info.fCode;
    }

    /**
     * Reads a group of VOP header and returns the start code that follows it.
     */
    final void decodeGovHeader() {
        useGov = true;
        linkIsBroken = false;
        var timecode = bits.getBits(5) * 3600;
        timecode += bits.getBits(6) * 60;
        bits.getBits(1);
        timecode += bits.getBits(6);
        tModuloBaseDecd = timecode;
        tModuloBaseDisp = timecode;
        var closed = bits.getBits(1);
        var broken = bits.getBits(1);
        if (closed == 0 && broken == 1) {
            linkIsBroken = true;
        }
    }

    /**
     * Decodes the header that follows a VOP start code ({@code decodeVOPHead}).
     *
     * @return whether the VOP is coded
     */
    final boolean decodeVOPHead() {
        vopmd.predType = bits.getBits(2);
        if (volmd.newpredEnable && vopmd.predType == VopMode.BVOP) {
            throw CorruptStream.INSTANCE;
        }
        var moduloInc = 0;
        while (bits.getBits(1) != 0) {
            moduloInc++;
            if (moduloInc > 1 << 16) {
                throw CorruptStream.INSTANCE;
            }
        }
        var fromDecoded = vopmd.predType != VopMode.BVOP || volmd.volType == VolMode.ENHN_LAYER;
        var currSec = moduloInc + (fromDecoded ? tModuloBaseDecd : tModuloBaseDisp);
        marker();
        var increment = numBitsTimeIncr != 0 ? bits.getBits(numBitsTimeIncr) : 0;
        marker();
        oldModuloBaseDecd = tModuloBaseDecd;
        oldModuloBaseDisp = tModuloBaseDisp;
        if (fromDecoded) {
            tModuloBaseDisp = tModuloBaseDecd;
            tModuloBaseDecd = currSec;
        }
        t = currSec * volmd.clockRate * clockRateScale + increment * clockRateScale;
        if (bits.getBits(1) == 0) {
            vopmd.interlace = false;
            return false;
        }
        if (volmd.newpredEnable) {
            vopmd.vopId = bits.getBits(vopmd.numBitsVopId);
            vopmd.vopId4PredictionIndication = bits.getBits(1);
            if (vopmd.vopId4PredictionIndication != 0) {
                vopmd.vopId4Prediction = bits.getBits(vopmd.numBitsVopId);
            }
            bits.getBits(1);
            newpredHeader(true);
        }
        if ((predictedLike()) && !volmd.shapeOnly) {
            vopmd.roundingControl = bits.getBits(1);
        } else {
            vopmd.roundingControl = 0;
        }
        if (volmd.reducedResolutionVopEnable && volmd.alphaUsage == VopPlanes.RECTANGLE
                && (vopmd.predType == VopMode.PVOP || vopmd.predType == VopMode.IVOP)) {
            vopmd.rrvOnOff = bits.getBits(1);
        } else {
            vopmd.rrvOnOff = 0;
        }
        if (volmd.alphaUsage != VopPlanes.RECTANGLE) {
            if (!(uiSprite == 1 && vopmd.predType == VopMode.IVOP)) {
                vopWidthCoded = bits.getBits(13);
                var width = (vopWidthCoded + MB_SIZE - 1) & ~(MB_SIZE - 1);
                marker();
                vopHeightCoded = bits.getBits(13);
                var height = (vopHeightCoded + MB_SIZE - 1) & ~(MB_SIZE - 1);
                marker();
                var left = bits.getBits(1) == 0 ? bits.getBits(12) : bits.getBits(12) - (1 << 12);
                marker();
                var top = bits.getBits(1) == 0 ? bits.getBits(12) : bits.getBits(12) - (1 << 12);
                if (((left | top) & 1) != 0) {
                    throw CorruptStream.INSTANCE;
                }
                marker();
                rctCurrVOPY.set(left, top, left + width, top + height);
                rctCurrVOPUV.set(rctCurrVOPY.downSampleBy2());
            }
            if (volmd.volType == VolMode.ENHN_LAYER && volmd.enhnType != 0) {
                vopmd.bgComposition = bits.getBits(1) != 0;
            }
            volmd.noCrChange = bits.getBits(1) != 0;
            vopmd.vopConstantAlphaValue = bits.getBits(1) == 1 ? bits.getBits(8) : 255;
            vopmd.shapeCodingType = vopmd.predType != VopMode.IVOP;
        }
        if (!volmd.complexityEstimationDisable) {
            decodeComplexityEstimation();
        }
        if (volmd.shapeOnly) {
            vopmd.intStep = 10;
            vopmd.intStepI = 10;
            vopmd.intStepB = 10;
            vopmd.mvInfoForward.fCode = vopmd.mvInfoBackward.fCode = 1;
            vopmd.interlace = false;
            if (volmd.volType == VolMode.ENHN_LAYER && vopmd.predType == VopMode.PVOP) {
                vopmd.refSelectCode = 3;
            } else if (volmd.volType == VolMode.ENHN_LAYER && vopmd.predType == VopMode.BVOP) {
                vopmd.refSelectCode = 0;
            }
            return true;
        }
        vopmd.intraDcSwitchThr = bits.getBits(3);
        if (vopmd.interlace) {
            vopmd.topFieldFirst = bits.getBits(1) != 0;
            vopmd.alternateScan = bits.getBits(1) != 0;
            if (volmd.volType != VolMode.BASE_LAYER) {
                throw CorruptStream.INSTANCE;
            }
        } else {
            vopmd.alternateScan = false;
        }
        if (uiSprite == 2 && vopmd.predType == VopMode.SPRITE) {
            if (numOfPnts > 0) {
                decodeWarpPoints();
            }
            if (lightChange) {
                decodeBrightnessChangeFactor();
            }
        }
        if (vopmd.predType == VopMode.IVOP) {
            vopmd.intStepI = vopmd.intStep = nonZero(bits.getBits(volmd.quantPrecision));
            vopmd.mvInfoForward.fCode = vopmd.mvInfoBackward.fCode = 1;
            if (volmd.alphaUsage == VopPlanes.EIGHT_BIT) {
                for (var aux = 0; aux < volmd.auxCompCount; aux++) {
                    vopmd.intStepIAlpha[aux] = nonZero(bits.getBits(6));
                }
            }
        } else if (predictedLike()) {
            vopmd.intStep = nonZero(bits.getBits(volmd.quantPrecision));
            if (volmd.alphaUsage == VopPlanes.EIGHT_BIT) {
                for (var aux = 0; aux < volmd.auxCompCount; aux++) {
                    vopmd.intStepPAlpha[aux] = nonZero(bits.getBits(6));
                }
            }
            decodeFCode(vopmd.mvInfoForward);
            vopmd.mvInfoBackward.fCode = 1;
        } else if (vopmd.predType == VopMode.BVOP) {
            vopmd.intStepB = vopmd.intStep = nonZero(bits.getBits(volmd.quantPrecision));
            if (volmd.alphaUsage == VopPlanes.EIGHT_BIT) {
                for (var aux = 0; aux < volmd.auxCompCount; aux++) {
                    vopmd.intStepBAlpha[aux] = nonZero(bits.getBits(6));
                }
            }
            decodeFCode(vopmd.mvInfoForward);
            decodeFCode(vopmd.mvInfoBackward);
        }
        if (volmd.volType == VolMode.BASE_LAYER) {
            if (volmd.alphaUsage != VopPlanes.RECTANGLE && vopmd.predType != VopMode.IVOP && uiSprite != 1) {
                vopmd.shapeCodingType = bits.getBits(1) != 0;
            }
        } else {
            vopmd.shapeCodingType = vopmd.predType != VopMode.IVOP;
            if (volmd.enhnType != 0) {
                vopmd.loadBakShape = bits.getBits(1);
                if (vopmd.loadBakShape != 0) {
                    decodeBackwardShapes();
                } else {
                    vopmd.loadForShape = 0;
                }
            } else {
                vopmd.loadForShape = 0;
                vopmd.loadBakShape = 0;
            }
            vopmd.refSelectCode = bits.getBits(2);
        }
        return true;
    }

    private static int nonZero(int value) {
        if (value == 0) {
            throw CorruptStream.INSTANCE;
        }
        return value;
    }

    /**
     * Reads the vop_id of a VOP or video packet header into the NEWPRED state.
     */
    abstract void newpredHeader(boolean vopHeader);

    /**
     * Decodes the backward and forward shapes of an enhancement layer VOP of temporal scalability
     * ({@code load_backward_shape}).
     */
    abstract void decodeBackwardShapes();

    final int[] readShapeRect() {
        var width = bits.getBits(13);
        if (width % MB_SIZE != 0) {
            throw CorruptStream.INSTANCE;
        }
        marker();
        var height = bits.getBits(13);
        if (height % MB_SIZE != 0) {
            throw CorruptStream.INSTANCE;
        }
        marker();
        var sign = bits.getBits(1) == 1 ? -1 : 1;
        var left = sign * bits.getBits(12);
        bits.getBits(1);
        sign = bits.getBits(1) == 1 ? -1 : 1;
        var top = sign * bits.getBits(12);
        return new int[]{left, top, left + width, top + height};
    }
}
