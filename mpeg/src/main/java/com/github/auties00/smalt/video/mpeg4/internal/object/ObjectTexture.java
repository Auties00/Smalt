/* Ports the Microsoft MPEG-4 Visual reference software of ISO/IEC 14496-5:2001 (version 2 FDAM1-2.3-001213,
 * sys/block.cpp and sys/decoder/blkdec.cpp, rvlcdec.cpp, mbdec.cpp, mbheaddec.cpp, mvdec.cpp and mcdec.cpp, without
 * the short video header). Copyright 1996-2000 Microsoft Corporation, Toshiba Corporation, Fraunhofer HHI, Sharp
 * Corporation, NextLevel Systems, Hitachi and the contributors named in the sources; ISO/IEC free license for
 * products conforming to ISO/IEC 14496-2. Modified for Smalt. */
package com.github.auties00.smalt.video.mpeg4.internal.object;

import java.util.Arrays;

/**
 * The macroblock layer of the texture and the alpha planes: the macroblock headers (6.3.6), the motion vectors
 * (7.6.3), the coefficients with their variable length and reversible codes (7.4.1), intra DC and AC prediction
 * (7.4.3), inverse quantization (7.4.4), the inverse transform and the reconstruction of B-VOP macroblocks.
 */
abstract class ObjectTexture extends ObjectShape {
    static final Vlc VLC_DCT = new Vlc(RefTables.VLC_DCT);
    static final Vlc VLC_DCT_INTRA = new Vlc(RefTables.VLC_DCTINTRA);
    static final Vlc VLC_DCT_RVLC = new Vlc(RefTables.VLC_DCTRVLC);
    static final Vlc VLC_DCT_INTRA_RVLC = new Vlc(RefTables.VLC_DCTINTRA_RVLC);
    static final Vlc VLC_MV = new Vlc(RefTables.VLC_MV);
    static final Vlc VLC_MCBPC_INTRA = new Vlc(RefTables.VLC_MCBPCINTRA);
    static final Vlc VLC_MCBPC_INTER = new Vlc(RefTables.VLC_MCBPCINTER);
    static final Vlc VLC_CBPY = new Vlc(RefTables.VLC_CBPY);
    static final Vlc VLC_CBPY1 = new Vlc(RefTables.VLC_CBPY1);
    static final Vlc VLC_CBPY2 = new Vlc(RefTables.VLC_CBPY2);
    static final Vlc VLC_CBPY3 = new Vlc(RefTables.VLC_CBPY3);
    static final Vlc VLC_INTRA_DCY = new Vlc(RefTables.VLC_INTRA_DCY);
    static final Vlc VLC_INTRA_DCC = new Vlc(RefTables.VLC_INTRA_DCC);
    static final Vlc VLC_MB_TYPE_BVOP = new Vlc(RefTables.VLC_MB_TYPE_BVOP);

    private static final int TCOEF_ESCAPE = 102;
    private static final int TCOEF_RVLC_ESCAPE = 169;
    private static final int NUMBITS_ESC_RUN = 6;
    private static final int[] BLK_OFFSET_X = {0, BLOCK_SIZE, 0, BLOCK_SIZE};
    private static final int[] BLK_OFFSET_Y = {0, 0, BLOCK_SIZE, BLOCK_SIZE};
    private static final int[] BLK_PIXEL = {0, OFFSET_BLK1, OFFSET_BLK2, OFFSET_BLK3};

    InBits bits = new InBits();

    private final int[] adaptedScan = new int[BLOCK_SQUARE_SIZE];
    private final int[] scanSave = new int[BLOCK_SQUARE_SIZE];
    private final int[] rrvBlock8 = new int[BLOCK_SQUARE_SIZE];
    private final int[] rrvBlock16 = new int[MB_SQUARE_SIZE];
    private final int[] diff = new int[2];
    private final int[] predVector = new int[2];
    private final int[] uvVector = new int[2];
    private int level;
    private int run;
    private boolean last;
    int qpPred;

    /**
     * Skips the macroblock stuffing codes ({@code skipAnyStuffing}).
     */
    final void skipAnyStuffing() {
        if (vopmd.predType == VopMode.IVOP) {
            while (bits.peekBits(9) == 1) {
                bits.getBits(9);
            }
        } else {
            while (bits.peekBits(10) == 1) {
                bits.getBits(10);
            }
        }
    }

    final int decode(Vlc vlc) {
        var symbol = vlc.decode(bits);
        if (symbol < 0) {
            throw CorruptStream.INSTANCE;
        }
        return symbol;
    }

    private int opaqueLumaBlocks(MbMode mode) {
        var count = 0;
        for (var i = Y_BLOCK1; i <= Y_BLOCK4; i++) {
            if (mode.transpStatus[i] != MbMode.ALL) {
                count++;
            }
        }
        return count;
    }

    private int decodeCbpy(int opaque, boolean inter) {
        return switch (opaque) {
            case 1 -> inter ? 1 - decode(VLC_CBPY1) : decode(VLC_CBPY1);
            case 2 -> inter ? 3 - decode(VLC_CBPY2) : decode(VLC_CBPY2);
            case 3 -> inter ? 7 - decode(VLC_CBPY3) : decode(VLC_CBPY3);
            case 4 -> inter ? 15 - decode(VLC_CBPY) : decode(VLC_CBPY);
            default -> throw CorruptStream.INSTANCE;
        };
    }

    private int dquant(int qp, MbMode mode) {
        mode.intStepDelta = switch (bits.getBits(2)) {
            case 0 -> -1;
            case 1 -> -2;
            case 2 -> 1;
            default -> 2;
        };
        qp += mode.intStepDelta;
        return Math.clamp(qp, 1, (1 << volmd.quantPrecision) - 1);
    }

    /**
     * Decodes the texture header of a macroblock of an I-VOP ({@code decodeMBTextureHeadOfIVOP}).
     *
     * @return the quantizer after the macroblock
     */
    final int decodeMBTextureHeadOfIVOP(MbMode mode, int qp, boolean[] restart) {
        var opaque = opaqueLumaBlocks(mode);
        var mcbpc = 8;
        while (mcbpc == 8) {
            mcbpc = decode(VLC_MCBPC_INTRA);
        }
        mode.dctMd = MbMode.INTRA;
        mode.skip = false;
        mode.mcsel = false;
        mode.fieldMV = false;
        mode.intStepDelta = 0;
        if (mcbpc > 3) {
            mode.dctMd = MbMode.INTRAQ;
        }
        var cbpc = mcbpc % 4;
        mode.acPrediction = bits.getBits(1) != 0;
        var cbpy = decodeCbpy(opaque, false);
        setCBPYandC(mode, cbpc, cbpy, opaque);
        if (mode.dctMd == MbMode.INTRAQ) {
            qp = dquant(qp, mode);
        }
        mode.stepSize = qp;
        if (vopmd.interlace) {
            mode.fieldDCT = bits.getBits(1) != 0;
        }
        setDCVLCMode(mode, restart);
        return qp;
    }

    /**
     * Decodes the header of an alpha plane of an intra macroblock ({@code decodeMBAlphaHeadOfIVOP}).
     */
    final void decodeMBAlphaHeadOfIVOP(MbMode mode, int qp, int qpA, int vopQP, int vopQPA, int aux) {
        if (!volmd.noGrayQuantUpdate) {
            qpA = Math.max(1, (qp * vopQPA) / vopQP);
        }
        mode.stepSizeAlpha = qpA;
        var coda = bits.getBits(1);
        mode.codAlpha[aux] = coda != 0 ? MbMode.ALPHA_ALL255 : MbMode.ALPHA_CODED;
        if (coda != 0) {
            return;
        }
        mode.acPredictionAlpha[aux] = bits.getBits(1) != 0;
        decodeCbpa(mode, aux);
    }

    private void decodeCbpa(MbMode mode, int aux) {
        var opaque = opaqueLumaBlocks(mode);
        var cbpa = decodeCbpy(opaque, true);
        var bitPos = 1;
        for (var blk = A_BLOCK1; blk <= A_BLOCK4; blk++) {
            if (mode.transpStatus[blk - 6] != MbMode.ALL) {
                mode.coded(blk + aux * 4, ((cbpa >> (opaque - bitPos)) & 1) != 0);
                bitPos++;
            } else {
                mode.coded(blk + aux * 4, false);
            }
        }
    }

    /**
     * Decodes the texture header of a macroblock of a P-VOP or a GMC sprite VOP
     * ({@code decodeMBTextureHeadOfPVOP}).
     *
     * @return the quantizer after the macroblock
     */
    final int decodeMBTextureHeadOfPVOP(MbMode mode, int qp, boolean[] restart) {
        var opaque = opaqueLumaBlocks(mode);
        var cbpc = 0;
        var cbpy = 0;
        var mcbpc = 0;
        do {
            mode.skip = bits.getBits(1) != 0;
            if (!mode.skip) {
                mcbpc = decode(VLC_MCBPC_INTER);
            }
        } while (!mode.skip && mcbpc == 20);
        var gmc = uiSprite == 2 && vopmd.predType == VopMode.SPRITE;
        if (!mode.skip) {
            mode.fieldMV = false;
            switch (mcbpc / 4) {
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
            cbpc = mcbpc % 4;
            mode.mcsel = false;
            var inter = mode.dctMd == MbMode.INTER || mode.dctMd == MbMode.INTERQ;
            if (inter && !mode.has4MVForward && gmc) {
                mode.mcsel = bits.getBits(1) != 0;
            }
            if (!inter) {
                mode.acPrediction = bits.getBits(1) != 0;
            }
            cbpy = decodeCbpy(opaque, inter);
        } else {
            mode.dctMd = MbMode.INTER;
            mode.has4MVForward = false;
            mode.fieldMV = false;
            mode.mcsel = gmc;
        }
        setCBPYandC(mode, cbpc, cbpy, opaque);
        mode.intStepDelta = 0;
        if (mode.dctMd == MbMode.INTERQ || mode.dctMd == MbMode.INTRAQ) {
            qp = dquant(qp, mode);
        }
        mode.stepSize = qp;
        if (vopmd.interlace && !mode.skip) {
            if (mode.dctMd == MbMode.INTRA || mode.dctMd == MbMode.INTRAQ || cbpy != 0 || cbpc != 0) {
                mode.fieldDCT = bits.getBits(1) != 0;
            } else {
                mode.fieldDCT = false;
            }
            mode.fieldMV = false;
            if ((mode.dctMd == MbMode.INTERQ || mode.dctMd == MbMode.INTER) && !mode.has4MVForward && !mode.mcsel) {
                mode.fieldMV = bits.getBits(1) != 0;
                if (mode.fieldMV) {
                    mode.forwardTop = bits.getBits(1) != 0;
                    mode.forwardBottom = bits.getBits(1) != 0;
                }
            }
        }
        setDCVLCMode(mode, restart);
        return qp;
    }

    /**
     * Decodes the texture header of a macroblock of a B-VOP ({@code decodeMBTextureHeadOfBVOP}).
     *
     * @return the quantizer after the macroblock
     */
    final int decodeMBTextureHeadOfBVOP(MbMode mode, int qp) {
        mode.has4MVForward = false;
        mode.has4MVBackward = false;
        mode.mcsel = false;
        mode.skip = false;
        mode.dctMd = MbMode.INTER;
        mode.intStepDelta = 0;
        mode.fieldDCT = false;
        mode.fieldMV = false;
        var opaque = opaqueLumaBlocks(mode);
        var cbpc = 0;
        var cbpy = 0;
        var cbpb = 0;
        var enhancementFromBase = volmd.volType == VolMode.ENHN_LAYER && vopmd.refSelectCode == 0;
        if (bits.getBits(1) == 1) {
            mode.skip = true;
            mode.mbType = enhancementFromBase ? MbMode.FORWARD : MbMode.DIRECT;
            if (enhancementFromBase) {
                return qp;
            }
        } else if (bits.getBits(1) == 1) {
            mode.mbType = decodeBMbType(enhancementFromBase);
        } else {
            mode.mbType = decodeBMbType(enhancementFromBase);
            cbpb = bits.getBits(opaque + 2);
            mode.has4MVForward = false;
            mode.has4MVBackward = false;
            cbpc = cbpb & 3;
            cbpy = (cbpb >> 2) & 0xF;
            if (mode.mbType != MbMode.DIRECT) {
                int delta;
                if (bits.getBits(1) == 0) {
                    delta = 0;
                } else if (bits.getBits(1) == 0) {
                    delta = -2;
                } else {
                    delta = 2;
                }
                mode.intStepDelta = delta;
                qp = Math.clamp(qp + delta, 1, (1 << volmd.quantPrecision) - 1);
            }
        }
        mode.stepSize = qp;
        setCBPYandC(mode, cbpc, cbpy, opaque);
        mode.dctMd = mode.mbType == MbMode.DIRECT ? MbMode.INTER : MbMode.INTERQ;
        if (vopmd.interlace) {
            if (cbpb != 0) {
                mode.fieldDCT = bits.getBits(1) != 0;
            }
            if (mode.mbType != MbMode.DIRECT) {
                mode.fieldMV = bits.getBits(1) != 0;
                if (mode.fieldMV) {
                    if (mode.mbType != MbMode.BACKWARD) {
                        mode.forwardTop = bits.getBits(1) != 0;
                        mode.forwardBottom = bits.getBits(1) != 0;
                    }
                    if (mode.mbType != MbMode.FORWARD) {
                        mode.backwardTop = bits.getBits(1) != 0;
                        mode.backwardBottom = bits.getBits(1) != 0;
                    }
                }
            }
        }
        return qp;
    }

    private int decodeBMbType(boolean enhancementFromBase) {
        if (!enhancementFromBase) {
            return decode(VLC_MB_TYPE_BVOP);
        }
        if (bits.getBits(1) == 1) {
            return MbMode.FORWARD;
        }
        if (bits.getBits(1) == 1) {
            return MbMode.INTERPOLATE;
        }
        if (bits.getBits(1) == 1) {
            return MbMode.BACKWARD;
        }
        throw CorruptStream.INSTANCE;
    }

    /**
     * Decodes the header of an alpha plane of a P-VOP macroblock ({@code decodeMBAlphaHeadOfPVOP}).
     */
    final void decodeMBAlphaHeadOfPVOP(MbMode mode, int qp, int qpA, int aux) {
        if (mode.dctMd == MbMode.INTRA || mode.dctMd == MbMode.INTRAQ) {
            decodeMBAlphaHeadOfIVOP(mode, qp, qpA, vopmd.intStep, vopmd.intStepPAlpha[aux], aux);
            return;
        }
        if (!volmd.noGrayQuantUpdate) {
            qpA = Math.max(1, (mode.stepSize * vopmd.intStepPAlpha[aux]) / vopmd.intStep);
        }
        mode.stepSizeAlpha = qpA;
        decodeCodAlpha(mode, aux);
    }

    /**
     * Decodes the header of an alpha plane of a B-VOP macroblock ({@code decodeMBAlphaHeadOfBVOP}).
     */
    final void decodeMBAlphaHeadOfBVOP(MbMode mode, int qp, int qpA, int aux) {
        if (!volmd.noGrayQuantUpdate) {
            qpA = Math.max(1, (qp * vopmd.intStepBAlpha[aux]) / vopmd.intStepB);
        }
        mode.stepSizeAlpha = qpA;
        decodeCodAlpha(mode, aux);
    }

    private void decodeCodAlpha(MbMode mode, int aux) {
        if (bits.getBits(1) == 0) {
            mode.codAlpha[aux] = bits.getBits(1) == 0 ? MbMode.ALPHA_CODED : MbMode.ALPHA_ALL255;
        } else {
            mode.codAlpha[aux] = MbMode.ALPHA_SKIPPED;
        }
        if (mode.codAlpha[aux] == MbMode.ALPHA_CODED) {
            decodeCbpa(mode, aux);
        }
    }

    final void setCBPYandC(MbMode mode, int cbpc, int cbpy, int opaque) {
        mode.coded(U_BLOCK, ((cbpc >> 1) & 1) != 0);
        mode.coded(V_BLOCK, (cbpc & 1) != 0);
        var bitPos = 1;
        for (var blk = Y_BLOCK1; blk <= Y_BLOCK4; blk++) {
            if (mode.transpStatus[blk] != MbMode.ALL) {
                mode.coded(blk, ((cbpy >> (opaque - bitPos)) & 1) != 0);
                bitPos++;
            } else {
                mode.coded(blk, false);
            }
        }
    }

    /**
     * Decodes the forward motion vectors of a P-VOP macroblock of a rectangular VOP ({@code decodeMV}).
     */
    final void decodeMV(MbMode mode, int mvOff, int mbIndex, boolean leftBndry, boolean rightBndry,
                        boolean topBndry, boolean zeroMV, int mbx, int mby) {
        if (mode.skip || mode.dctMd == MbMode.INTRA || mode.dctMd == MbMode.INTRAQ || zeroMV) {
            MotionVector.zero(mv, mvOff, PVOP_MV_PER_REF_PER_MB);
            return;
        }
        var info = vopmd.mvInfoForward;
        if (mode.has4MVForward) {
            for (var blk = Y_BLOCK1; blk <= Y_BLOCK4; blk++) {
                if (leftBndry || rightBndry || topBndry) {
                    findMVpredGeneric(predVector, mvOff, mbIndex, blk, mbx, mby);
                } else {
                    find8x8MVpredInterior(predVector, mvOff, blk);
                }
                decodeVector(info, predVector[0], predVector[1]);
                mv[mvOff + blk].setHalfPel(diff[0], diff[1]);
            }
        } else if (vopmd.interlace && mode.fieldMV) {
            find16x16MVpred(predVector, mvOff, leftBndry, rightBndry, topBndry);
            decodeFieldVectors(mode, mvOff, info);
        } else {
            findMVpredGeneric(predVector, mvOff, mbIndex, ALL_Y_BLOCKS, mbx, mby);
            decodeVector(info, predVector[0], predVector[1]);
            mv[mvOff].setHalfPel(diff[0], diff[1]);
            for (var i = 1; i <= 4; i++) {
                mv[mvOff + i].set(mv[mvOff]);
            }
        }
        if (vopmd.rrvOnOff == 1) {
            for (var i = 0; i < PVOP_MV_PER_REF_PER_MB; i++) {
                mv[mvOff + i].scaleUp();
            }
        }
    }

    private void decodeVector(VopMode.MvInfo info, int predX, int predY) {
        getDiffMV(info);
        diff[0] += predX;
        diff[1] += predY;
        fitMvInRange(diff, info);
    }

    private void decodeFieldVectors(MbMode mode, int mvOff, VopMode.MvInfo info) {
        getDiffMV(info);
        diff[1] *= 2;
        predVector[1] = 2 * (predVector[1] / 2);
        diff[0] += predVector[0];
        diff[1] += predVector[1];
        fitMvInRange(diff, info);
        var top = mv[mvOff + 5 + (mode.forwardTop ? 1 : 0)];
        top.setHalfPel(diff[0], diff[1]);
        var x1 = top.trueX;
        var y1 = top.trueY;
        getDiffMV(info);
        diff[1] *= 2;
        diff[0] += predVector[0];
        diff[1] += predVector[1];
        fitMvInRange(diff, info);
        var bottom = mv[mvOff + 7 + (mode.forwardBottom ? 1 : 0)];
        bottom.setHalfPel(diff[0], diff[1]);
        var x2 = bottom.trueX;
        var y2 = bottom.trueY;
        for (var i = 1; i < 5; i++) {
            var tx = x1 + x2;
            var ty = y1 + y2;
            mv[mvOff + i].setHalfPel((tx & 3) != 0 ? ((tx >> 1) | 1) : (tx >> 1),
                    (ty & 3) != 0 ? ((ty >> 1) | 1) : (ty >> 1));
        }
    }

    /**
     * Decodes the forward motion vectors of a P-VOP macroblock with shape ({@code decodeMVWithShape}).
     */
    final void decodeMVWithShape(MbMode mode, int mbx, int mby, int mvOff, int mbIndex) {
        if (mode.skip || mode.dctMd == MbMode.INTRA || mode.dctMd == MbMode.INTRAQ
                || (volmd.volType == VolMode.ENHN_LAYER && vopmd.refSelectCode == 3)) {
            MotionVector.zero(mv, mvOff, PVOP_MV_PER_REF_PER_MB);
            return;
        }
        var info = vopmd.mvInfoForward;
        if (mode.has4MVForward) {
            for (var blk = Y_BLOCK1; blk <= Y_BLOCK4; blk++) {
                if (mode.transpStatus[blk] == MbMode.ALL) {
                    mv[mvOff + blk].setParts(MotionVector.NOT_MV, MotionVector.NOT_MV, 0, 0);
                } else {
                    findMVpredGeneric(predVector, mvOff, mbIndex, blk, mbx, mby);
                    decodeVector(info, predVector[0], predVector[1]);
                    mv[mvOff + blk].setHalfPel(diff[0], diff[1]);
                }
            }
        } else if (vopmd.interlace && mode.fieldMV) {
            findMVpredGeneric(predVector, mvOff, mbIndex, ALL_Y_BLOCKS, mbx, mby);
            decodeFieldVectors(mode, mvOff, info);
        } else {
            findMVpredGeneric(predVector, mvOff, mbIndex, ALL_Y_BLOCKS, mbx, mby);
            decodeVector(info, predVector[0], predVector[1]);
            mv[mvOff].setHalfPel(diff[0], diff[1]);
            for (var i = 1; i <= 4; i++) {
                mv[mvOff + i].set(mv[mvOff]);
            }
        }
    }

    /**
     * Decodes the motion vectors of a B-VOP macroblock ({@code decodeMVofBVOP}).
     *
     * @param fwdOff the index of the forward vectors in {@link #mv}
     * @param bakOff the index of the backward vectors in {@link #mv}
     * @param refIndex the index of the co-located macroblock of the future reference, or -1
     */
    final void decodeMVofBVOP(int fwdOff, int bakOff, MbMode mode, int refIndex) {
        if (mode.skip && volmd.volType == VolMode.ENHN_LAYER && vopmd.refSelectCode == 0) {
            return;
        }
        var notEnhancement = volmd.volType != VolMode.ENHN_LAYER;
        if (mode.mbType == MbMode.FORWARD || mode.mbType == MbMode.INTERPOLATE) {
            decodeBVectors(fwdOff, mode.fieldMV && notEnhancement, vopmd.mvInfoForward, forwardMvPredBVOPx,
                    forwardMvPredBVOPy);
        }
        if ((mode.mbType == MbMode.BACKWARD || mode.mbType == MbMode.INTERPOLATE)
                && (volmd.volType != VolMode.ENHN_LAYER || vopmd.refSelectCode != 0)) {
            decodeBVectors(bakOff, mode.fieldMV && notEnhancement, vopmd.mvInfoBackward, backwardMvPredBVOPx,
                    backwardMvPredBVOPy);
        }
        if (mode.mbType == MbMode.DIRECT) {
            int dx;
            int dy;
            if (mode.skip) {
                dx = 0;
                dy = 0;
            } else {
                dx = deScaleMV(decode(VLC_MV) - 32, 0, 1);
                dy = deScaleMV(decode(VLC_MV) - 32, 0, 1);
            }
            if (vopmd.interlace) {
                if (!mode.skip) {
                    mode.directDeltaX = dx;
                    mode.directDeltaY = dy;
                }
            }
            computeDirectForwardMV(dx, dy, fwdOff, refIndex);
            if (refIndex < 0) {
                mode.has4MVBackward = mode.has4MVForward = volmd.quarterSample;
                zeroVector.setToZero();
                backwardMVFromForwardMV(mv[bakOff], mv[fwdOff], zeroVector, dx, dy);
                for (var i = 1; i <= 4; i++) {
                    backwardMVFromForwardMV(mv[bakOff + i], mv[fwdOff + i], zeroVector, dx, dy);
                }
            } else {
                var refMode = mbmdRef[refIndex];
                mode.has4MVBackward = mode.has4MVForward = refMode.has4MVForward;
                var refOff = refIndex * PVOP_MV_PER_REF_PER_MB;
                for (var i = 1; i <= 4; i++) {
                    backwardMVFromForwardMV(mv[bakOff + i], mv[fwdOff + i], mvRef[refOff + i], dx, dy);
                }
            }
        }
    }

    private final MotionVector zeroVector = new MotionVector();

    private void decodeBVectors(int off, boolean field, VopMode.MvInfo info, int[] predX, int[] predY) {
        getDiffMV(info);
        if (field) {
            var x = diff[0] + predX[0];
            var y = diff[1] + predY[0] / 2;
            diff[0] = x;
            diff[1] = y;
            fitMvInRange(diff, info);
            diff[1] *= 2;
            predX[0] = diff[0];
            predY[0] = diff[1];
            mv[off].setHalfPel(diff[0], diff[1]);
            mv[off + 1].set(mv[off]);
            mv[off + 2].set(mv[off]);
            getDiffMV(info);
            x = diff[0] + predX[1];
            y = diff[1] + predY[1] / 2;
            diff[0] = x;
            diff[1] = y;
            fitMvInRange(diff, info);
            diff[1] *= 2;
            predX[1] = diff[0];
            predY[1] = diff[1];
            mv[off + 3].setHalfPel(diff[0], diff[1]);
            mv[off + 4].set(mv[off + 3]);
        } else {
            diff[0] += predX[0];
            diff[1] += predY[0];
            fitMvInRange(diff, info);
            predX[0] = predX[1] = diff[0];
            predY[0] = predY[1] = diff[1];
            mv[off].setHalfPel(diff[0], diff[1]);
            for (var i = 1; i <= 4; i++) {
                mv[off + i].set(mv[off]);
            }
        }
    }

    private void computeDirectForwardMV(int dx, int dy, int fwdOff, int refIndex) {
        if (refIndex < 0) {
            mv[fwdOff].setHalfPel(dx, dy);
            for (var i = 1; i <= 4; i++) {
                mv[fwdOff + i].set(mv[fwdOff]);
            }
            return;
        }
        var part = t - tPastRef;
        var full = tFutureRef - tPastRef;
        var refOff = refIndex * PVOP_MV_PER_REF_PER_MB;
        if (!mbmdRef[refIndex].has4MVForward) {
            var ref = mvRef[refOff];
            mv[fwdOff].setHalfPel(dx + ref.trueX * part / full, dy + ref.trueY * part / full);
            for (var i = 1; i <= 4; i++) {
                mv[fwdOff + i].set(mv[fwdOff]);
            }
        } else {
            for (var i = 1; i <= 4; i++) {
                var ref = mvRef[refOff + i];
                mv[fwdOff + i].setHalfPel(dx + ref.trueX * part / full, dy + ref.trueY * part / full);
            }
        }
    }

    /**
     * Derives the backward vector of a direct mode block ({@code backwardMVFromForwardMV}).
     */
    final void backwardMVFromForwardMV(MotionVector backward, MotionVector forward, MotionVector ref, int deltaX,
                                       int deltaY) {
        var full = tFutureRef - tPastRef;
        var bx = deltaX == 0 ? (t - tFutureRef) * ref.trueX / full : forward.trueX - ref.trueX;
        var by = deltaY == 0 ? (t - tFutureRef) * ref.trueY / full : forward.trueY - ref.trueY;
        backward.setHalfPel(bx, by);
    }

    /**
     * Decodes a motion vector difference in half samples into {@link #diff} ({@code getDiffMV}).
     */
    private void getDiffMV(VopMode.MvInfo info) {
        var vlc = decode(VLC_MV) - 32;
        var residual = vlc != 0 ? bits.getBits(info.fCode - 1) : 0;
        diff[0] = deScaleMV(vlc, residual, info.scaleFactor);
        vlc = decode(VLC_MV) - 32;
        residual = vlc != 0 ? bits.getBits(info.fCode - 1) : 0;
        diff[1] = deScaleMV(vlc, residual, info.scaleFactor);
    }

    static int deScaleMV(int vlc, int residual, int scaleFactor) {
        if (vlc == 0 && residual == 0) {
            return 0;
        }
        if (scaleFactor == 1) {
            return vlc;
        }
        var abs = Math.abs(vlc) * scaleFactor + residual - scaleFactor + 1;
        return Integer.signum(vlc) * abs;
    }

    static void fitMvInRange(int[] v, VopMode.MvInfo info) {
        var range = info.range;
        for (var i = 0; i < 2; i++) {
            if (v[i] < -range) {
                v[i] += 2 * range;
            } else if (v[i] >= range) {
                v[i] -= 2 * range;
            }
        }
    }

    /**
     * Finds the median prediction of a macroblock vector at the VOP boundaries ({@code find16x16MVpred}).
     */
    final void find16x16MVpred(int[] pred, int mvOff, boolean leftBndry, boolean rightBndry, boolean topBndry) {
        var cand = RefTables.INDEX_OF_CAND_BLK;
        int x0 = 0;
        int y0 = 0;
        if (!leftBndry) {
            var v = mv[mvOff - PVOP_MV_PER_REF_PER_MB + cand[3]];
            x0 = v.trueX;
            y0 = v.trueY;
        }
        if (topBndry) {
            pred[0] = x0;
            pred[1] = y0;
            return;
        }
        var v1 = mv[mvOff - numOfTotalMVPerRow + cand[4]];
        int x2 = 0;
        int y2 = 0;
        if (!rightBndry) {
            var v2 = mv[mvOff - numOfTotalMVPerRow + PVOP_MV_PER_REF_PER_MB + cand[5]];
            x2 = v2.trueX;
            y2 = v2.trueY;
        }
        pred[0] = median(x0, v1.trueX, x2);
        pred[1] = median(y0, v1.trueY, y2);
    }

    /**
     * Finds the median prediction of a block vector inside the VOP ({@code find8x8MVpredInterior}).
     */
    final void find8x8MVpredInterior(int[] pred, int mvOff, int blk) {
        var cand = RefTables.INDEX_OF_CAND_BLK;
        MotionVector v0;
        MotionVector v1;
        MotionVector v2;
        var c0 = cand[blk * 3];
        var c1 = cand[blk * 3 + 1];
        var c2 = cand[blk * 3 + 2];
        switch (blk) {
            case Y_BLOCK1 -> {
                v0 = mv[mvOff - PVOP_MV_PER_REF_PER_MB + c0];
                v1 = mv[mvOff - numOfTotalMVPerRow + c1];
                v2 = mv[mvOff - numOfTotalMVPerRow + PVOP_MV_PER_REF_PER_MB + c2];
            }
            case Y_BLOCK2 -> {
                v0 = mv[mvOff + c0];
                v1 = mv[mvOff - numOfTotalMVPerRow + c1];
                v2 = mv[mvOff - numOfTotalMVPerRow + PVOP_MV_PER_REF_PER_MB + c2];
            }
            case Y_BLOCK3 -> {
                v0 = mv[mvOff - PVOP_MV_PER_REF_PER_MB + c0];
                v1 = mv[mvOff + c1];
                v2 = mv[mvOff + c2];
            }
            default -> {
                v0 = mv[mvOff + c0];
                v1 = mv[mvOff + c1];
                v2 = mv[mvOff + c2];
            }
        }
        pred[0] = median(v0.trueX, v1.trueX, v2.trueX);
        pred[1] = median(v0.trueY, v1.trueY, v2.trueY);
    }

    /**
     * Finds the chrominance vectors of a macroblock of a rectangular VOP ({@code mvLookupUV}).
     *
     * @param result receives the frame (or top field) vector and the bottom field vector
     */
    final void mvLookupUV(MbMode mode, int mvOff, int[] result) {
        int dx = 0;
        int dy = 0;
        if (mode.has4MVForward) {
            for (var k = 1; k <= 4; k++) {
                var v = mv[mvOff + k];
                if (volmd.quarterSample) {
                    dx += v.mvx;
                    dy += v.mvy;
                } else if (vopmd.rrvOnOff == 1) {
                    dx += v.trueX2;
                    dy += v.trueY2;
                } else {
                    dx += v.trueX;
                    dy += v.trueY;
                }
            }
            result[0] = Integer.signum(dx) * (RefTables.MV_ROUND16[Math.abs(dx) % 16] + (Math.abs(dx) / 16) * 2);
            result[1] = Integer.signum(dy) * (RefTables.MV_ROUND16[Math.abs(dy) % 16] + (Math.abs(dy) / 16) * 2);
        } else if (mode.fieldMV) {
            var top = mv[mvOff + (mode.forwardTop ? 6 : 5)];
            dx = volmd.quarterSample ? top.mvx : top.trueX;
            dy = volmd.quarterSample ? top.mvy : top.trueY;
            result[0] = (dx & 3) != 0 ? ((dx >> 1) | 1) : (dx >> 1);
            result[1] = (dy & 6) != 0 ? ((dy >> 1) | 2) : (dy >> 1);
            var bottom = mv[mvOff + (mode.forwardBottom ? 8 : 7)];
            dx = volmd.quarterSample ? bottom.mvx : bottom.trueX;
            dy = volmd.quarterSample ? bottom.mvy : bottom.trueY;
            result[2] = (dx & 3) != 0 ? ((dx >> 1) | 1) : (dx >> 1);
            result[3] = (dy & 6) != 0 ? ((dy >> 1) | 2) : (dy >> 1);
        } else {
            var v = mv[mvOff];
            if (volmd.quarterSample) {
                dx = v.mvx;
                dy = v.mvy;
            } else if (vopmd.rrvOnOff == 1) {
                dx = v.trueX2;
                dy = v.trueY2;
            } else {
                dx = v.trueX;
                dy = v.trueY;
            }
            result[0] = Integer.signum(dx) * (RefTables.MV_ROUND4[Math.abs(dx) % 4] + (Math.abs(dx) / 4) * 2);
            result[1] = Integer.signum(dy) * (RefTables.MV_ROUND4[Math.abs(dy) % 4] + (Math.abs(dy) / 4) * 2);
        }
    }

    /**
     * Inverse quantizes the intra DC coefficient ({@code inverseQuantizeIntraDc}).
     */
    final void inverseQuantizeIntraDc(int[] coefQ, int dcScaler) {
        dctCoef[0] = coefQ[0] * dcScaler;
    }

    final void inverseQuantizeDCTcoefH263(int[] coefQ, int start, int qp) {
        for (var i = start; i < BLOCK_SQUARE_SIZE; i++) {
            var q = coefQ[i];
            if (q != 0) {
                var value = qp % 2 == 1 ? qp * (2 * Math.abs(q) + 1) : qp * (2 * Math.abs(q) + 1) - 1;
                dctCoef[i] = Integer.signum(q) * value;
            } else {
                dctCoef[i] = 0;
            }
        }
    }

    final void inverseQuantizeIntraDCTcoefMPEG(int[] coefQ, int start, int qp, boolean alphaMatrix, int aux) {
        var sum = dctCoef[0];
        var allZero = dctCoef[0] == 0;
        var matrix = alphaMatrix ? volmd.intraQuantizerMatrixAlpha[aux] : volmd.intraQuantizerMatrix;
        var maxVal = 1 << (volmd.nBits + 3);
        for (var i = start; i < BLOCK_SQUARE_SIZE; i++) {
            if (coefQ[i] == 0) {
                dctCoef[i] = 0;
            } else {
                dctCoef[i] = Math.clamp(qp * coefQ[i] * matrix[i] / 8, -maxVal, maxVal - 1);
                allZero = false;
            }
            sum ^= dctCoef[i];
        }
        if (!allZero && (sum & 1) == 0) {
            dctCoef[BLOCK_SQUARE_SIZE - 1] ^= 1;
        }
    }

    final void inverseQuantizeInterDCTcoefMPEG(int[] coefQ, int start, int qp, boolean alphaMatrix, int aux) {
        var sum = 0;
        var allZero = true;
        var matrix = alphaMatrix ? volmd.interQuantizerMatrixAlpha[aux] : volmd.interQuantizerMatrix;
        var maxVal = 1 << (volmd.nBits + 3);
        for (var i = start; i < BLOCK_SQUARE_SIZE; i++) {
            var q = coefQ[i];
            if (q == 0) {
                dctCoef[i] = 0;
            } else {
                dctCoef[i] = Math.clamp((qp * (q * 2 + Integer.signum(q)) * matrix[i]) / 16, -maxVal,
                        maxVal - 1);
                allZero = false;
            }
            sum ^= dctCoef[i];
        }
        if (!allZero && (sum & 1) == 0) {
            dctCoef[BLOCK_SQUARE_SIZE - 1] ^= 1;
        }
    }

    static int divRoundNearest(int i, int denom) {
        return i >= 0 ? (i + (denom >> 1)) / denom : (i - (denom >> 1)) / denom;
    }

    private static boolean intra(MbMode mode) {
        return mode.dctMd == MbMode.INTRA || mode.dctMd == MbMode.INTRAQ;
    }

    /**
     * Finds the block that predicts the DC and AC coefficients of a block in one direction
     * ({@code findPredictorBlock}); sets {@link #qpPred} when found.
     *
     * @return the coefficient memory of the predicting block, or {@code null}
     */
    final int[] findPredictorBlock(int blkOrig, int predDir, int[][] left, int[][] top, int[][] leftTop,
                                   int[][] curr, MbMode mdLeft, MbMode mdTop, MbMode mdLeftTop, MbMode mdCurr) {
        int blk;
        var aux = 0;
        if (blkOrig < 7) {
            blk = blkOrig;
        } else {
            aux = (blkOrig - 7) / 4;
            blk = ((blkOrig - 7) & 3) + 7;
        }
        var a = aux * 4;
        switch (predDir) {
            case MbMode.HORIZONTAL -> {
                return switch (blk) {
                    case Y_BLOCK1 -> fromNeighbor(left, mdLeft, Y_BLOCK2, Y_BLOCK2, false);
                    case Y_BLOCK2 -> fromCurrent(curr, mdCurr, Y_BLOCK1, Y_BLOCK1, false);
                    case Y_BLOCK3 -> fromNeighbor(left, mdLeft, Y_BLOCK4, Y_BLOCK4, false);
                    case Y_BLOCK4 -> fromCurrent(curr, mdCurr, Y_BLOCK3, Y_BLOCK3, false);
                    case A_BLOCK1 -> fromNeighbor(left, mdLeft, Y_BLOCK2, A_BLOCK2 + a, true);
                    case A_BLOCK2 -> fromCurrent(curr, mdCurr, Y_BLOCK1, A_BLOCK1 + a, true);
                    case A_BLOCK3 -> fromNeighbor(left, mdLeft, Y_BLOCK4, A_BLOCK4 + a, true);
                    case A_BLOCK4 -> fromCurrent(curr, mdCurr, Y_BLOCK3, A_BLOCK3 + a, true);
                    default -> fromNeighbor(left, mdLeft, ALL_Y_BLOCKS, blk, false);
                };
            }
            case MbMode.VERTICAL -> {
                return switch (blk) {
                    case Y_BLOCK1 -> fromNeighbor(top, mdTop, Y_BLOCK3, Y_BLOCK3, false);
                    case Y_BLOCK2 -> fromNeighbor(top, mdTop, Y_BLOCK4, Y_BLOCK4, false);
                    case Y_BLOCK3 -> fromCurrent(curr, mdCurr, Y_BLOCK1, Y_BLOCK1, false);
                    case Y_BLOCK4 -> fromCurrent(curr, mdCurr, Y_BLOCK2, Y_BLOCK2, false);
                    case A_BLOCK1 -> fromNeighbor(top, mdTop, Y_BLOCK3, A_BLOCK3 + a, true);
                    case A_BLOCK2 -> fromNeighbor(top, mdTop, Y_BLOCK4, A_BLOCK4 + a, true);
                    case A_BLOCK3 -> fromCurrent(curr, mdCurr, Y_BLOCK1, A_BLOCK1 + a, true);
                    case A_BLOCK4 -> fromCurrent(curr, mdCurr, Y_BLOCK2, A_BLOCK2 + a, true);
                    default -> fromNeighbor(top, mdTop, ALL_Y_BLOCKS, blk, false);
                };
            }
            case MbMode.DIAGONAL -> {
                return switch (blk) {
                    case Y_BLOCK1 -> fromNeighbor(leftTop, mdLeftTop, Y_BLOCK4, Y_BLOCK4, false);
                    case Y_BLOCK2 -> fromNeighbor(top, mdTop, Y_BLOCK3, Y_BLOCK3, false);
                    case Y_BLOCK3 -> fromNeighbor(left, mdLeft, Y_BLOCK2, Y_BLOCK2, false);
                    case Y_BLOCK4 -> fromCurrent(curr, mdCurr, Y_BLOCK1, Y_BLOCK1, false);
                    case A_BLOCK1 -> fromNeighbor(leftTop, mdLeftTop, Y_BLOCK4, A_BLOCK4 + a, true);
                    case A_BLOCK2 -> fromNeighbor(top, mdTop, Y_BLOCK3, A_BLOCK3 + a, true);
                    case A_BLOCK3 -> fromNeighbor(left, mdLeft, Y_BLOCK2, A_BLOCK2 + a, true);
                    case A_BLOCK4 -> fromCurrent(curr, mdCurr, Y_BLOCK1, A_BLOCK1 + a, true);
                    default -> fromNeighbor(leftTop, mdLeftTop, ALL_Y_BLOCKS, blk, false);
                };
            }
            default -> throw CorruptStream.INSTANCE;
        }
    }

    private int[] fromNeighbor(int[][] memory, MbMode mode, int statusBlock, int block, boolean alpha) {
        if (memory != null && intra(mode) && mode.transpStatus[statusBlock] != MbMode.ALL) {
            qpPred = alpha ? mode.stepSizeAlpha : mode.stepSize;
            return memory[block - 1];
        }
        return null;
    }

    private int[] fromCurrent(int[][] memory, MbMode mode, int statusBlock, int block, boolean alpha) {
        if (mode.transpStatus[statusBlock] != MbMode.ALL) {
            qpPred = alpha ? mode.stepSizeAlpha : mode.stepSize;
            return memory[block - 1];
        }
        return null;
    }

    /**
     * Chooses the direction of intra prediction of a block from the DC gradients (7.4.3.1, {@code decideIntraPred});
     * sets {@link #qpPred} when a predictor is found.
     *
     * @param qp the quantizer of the block, kept in {@link #qpPred} without a predictor
     * @return the coefficient memory of the predicting block, or {@code null}
     */
    final int[] decideIntraPred(MbMode mdCurr, int qp, int blkn, int[][] left, int[][] top, int[][] leftTop,
                                int[][] curr, MbMode mdLeft, MbMode mdTop, MbMode mdLeftTop) {
        qpPred = qp;
        var blkTop = findPredictorBlock(blkn, MbMode.VERTICAL, left, top, leftTop, curr, mdLeft, mdTop, mdLeftTop,
                mdCurr);
        var qpTop = qpPred;
        qpPred = qp;
        var blkLeftTop = findPredictorBlock(blkn, MbMode.DIAGONAL, left, top, leftTop, curr, mdLeft, mdTop,
                mdLeftTop, mdCurr);
        qpPred = qp;
        var blkLeft = findPredictorBlock(blkn, MbMode.HORIZONTAL, left, top, leftTop, curr, mdLeft, mdTop, mdLeftTop,
                mdCurr);
        var qpLeft = qpPred;
        var defVal = 1 << (volmd.nBits + 2);
        var predLeftTop = blkLeftTop == null ? defVal : blkLeftTop[0];
        var horizontalGrad = (blkTop == null ? defVal : blkTop[0]) - predLeftTop;
        var verticalGrad = (blkLeft == null ? defVal : blkLeft[0]) - predLeftTop;
        qpPred = qp;
        if (Math.abs(verticalGrad) < Math.abs(horizontalGrad)) {
            mdCurr.predDir[blkn - 1] = MbMode.VERTICAL;
            if (blkTop != null) {
                qpPred = qpTop;
                return blkTop;
            }
        } else {
            mdCurr.predDir[blkn - 1] = MbMode.HORIZONTAL;
            if (blkLeft != null) {
                qpPred = qpLeft;
                return blkLeft;
            }
        }
        return null;
    }

    /**
     * Selects the scan of a block, moving the positions outside the shape-adaptive transform of a boundary block
     * to its end ({@code CInvScanSelectorForSADCT::select}).
     */
    final int[] selectScan(int[] scan, boolean boundary, int blk) {
        if (volmd.sadctDisable || !boundary) {
            return scan;
        }
        var widths = currMBCoeffWidth[blk];
        var count = 0;
        var saved = 0;
        for (var n = 0; n < BLOCK_SQUARE_SIZE; n++) {
            var iy = scan[n] / BLOCK_SIZE;
            var ix = scan[n] % BLOCK_SIZE;
            if (widths[iy] > ix) {
                adaptedScan[count++] = scan[n];
            } else {
                scanSave[saved++] = scan[n];
            }
        }
        System.arraycopy(scanSave, 0, adaptedScan, count, saved);
        return adaptedScan;
    }

    /**
     * Decodes, predicts, inverse quantizes and transforms an intra block into its destination
     * ({@code decodeIntraBlockTexture}).
     *
     * @param dst the destination plane
     * @param dstOff the index of the block's top-left sample
     * @param blk the block number, alpha blocks offset by 4 per auxiliary component
     * @param memory the coefficient memory of the current macroblock
     * @param pred the predicting block, or {@code null}
     * @param shape the shape of the macroblock for a boundary block, or {@code null}
     * @param shapeOff the index of the block in the shape
     */
    final void decodeIntraBlockTexture(int[] dst, int dstOff, int dstWidth, int qp, int dcScaler, int blk,
                                       int[][] memory, MbMode mode, int[] pred, int predQP, int[] shape,
                                       int shapeOff, int shapeWidth, int aux) {
        var coefQ = this.coefQ[blk - 1];
        var start = 0;
        if ((blk <= V_BLOCK && !mode.codeDcAsAc) || (blk >= A_BLOCK1 && !mode.codeDcAsAcAlpha)) {
            coefQ[0] = decodeIntraDCmpeg(blk <= Y_BLOCK4 || blk >= A_BLOCK1);
            start++;
        }
        if (mode.coded(blk)) {
            var zigzag = RefTables.STANDARD_ZIGZAG;
            if (vopmd.alternateScan && blk <= V_BLOCK) {
                zigzag = RefTables.VERTICAL_ZIGZAG;
            } else if ((blk <= V_BLOCK && mode.acPrediction)
                    || (blk >= A_BLOCK1 && mode.acPredictionAlpha[(blk - 7) / 4])) {
                zigzag = mode.predDir[blk - 1] == MbMode.HORIZONTAL ? RefTables.VERTICAL_ZIGZAG
                        : RefTables.HORIZONTAL_ZIGZAG;
            }
            zigzag = selectScan(zigzag, mode.transpStatus[0] == MbMode.PARTIAL, blk);
            if (volmd.dataPartitioning && volmd.reversibleVlc && vopmd.predType != VopMode.BVOP) {
                decodeRvlcTcoef(coefQ, start, zigzag, true);
            } else {
                decodeIntraTCOEF(coefQ, start, zigzag);
            }
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
            inverseQuantizeIntraDCTcoefMPEG(coefQ, 1, qp, blk >= A_BLOCK1, aux);
        }
        var saved = memory[blk - 1];
        saved[0] = dctCoef[0];
        for (int i = 1, j = 8; i < BLOCK_SIZE; i++, j += BLOCK_SIZE) {
            saved[i] = coefQ[i];
            saved[i + BLOCK_SIZE - 1] = coefQ[j];
        }
        if (vopmd.rrvOnOff == 1) {
            idct.applyClipped(dctCoef, 0, BLOCK_SIZE, rrvBlock8, 0, BLOCK_SIZE, shape, shapeOff, shapeWidth);
            RrvUtil.meanUpSampling(rrvBlock8, rrvBlock16, 8, 8);
            RrvUtil.writeCubicRct(16, dstWidth, rrvBlock16, 0, dst, dstOff);
        } else {
            idct.applyClipped(dctCoef, 0, BLOCK_SIZE, dst, dstOff, dstWidth, shape, shapeOff, shapeWidth);
        }
    }

    /**
     * Decodes, inverse quantizes and transforms the residual of an inter block ({@code decodeTextureInterBlock}).
     */
    final void decodeTextureInterBlock(int[] dst, int dstOff, int dstWidth, int qp, boolean alphaBlock, MbMode mode,
                                       int blk, int[] shape, int shapeOff, int shapeWidth, int aux) {
        var coefQ = this.coefQ[0];
        var boundary = mode.transpStatus[0] == MbMode.PARTIAL;
        if (volmd.dataPartitioning && volmd.reversibleVlc && vopmd.predType != VopMode.BVOP) {
            decodeRvlcTcoef(coefQ, 0, selectScan(RefTables.STANDARD_ZIGZAG, boundary, blk), false);
        } else {
            var scan = vopmd.alternateScan && !alphaBlock ? RefTables.VERTICAL_ZIGZAG : RefTables.STANDARD_ZIGZAG;
            decodeInterTCOEF(coefQ, 0, selectScan(scan, boundary, blk));
        }
        if (volmd.quantizer == VolMode.Q_H263) {
            inverseQuantizeDCTcoefH263(coefQ, 0, qp);
        } else {
            inverseQuantizeInterDCTcoefMPEG(coefQ, 0, qp, alphaBlock, aux);
        }
        if (vopmd.rrvOnOff == 1) {
            idct.applyResidual(dctCoef, 0, BLOCK_SIZE, rrvBlock8, 0, BLOCK_SIZE, shape, shapeOff, shapeWidth);
            RrvUtil.meanUpSampling(rrvBlock8, rrvBlock16, 8, 8);
            RrvUtil.writeCubicRct(16, dstWidth, rrvBlock16, 0, dst, dstOff);
        } else {
            idct.applyResidual(dctCoef, 0, BLOCK_SIZE, dst, dstOff, dstWidth, shape, shapeOff, shapeWidth);
        }
    }

    final void decodeIntraTCOEF(int[] coefQ, int coef, int[] zigzag) {
        last = false;
        while (!last) {
            var index = decode(VLC_DCT_INTRA);
            if (index != TCOEF_ESCAPE) {
                intraTableIndex(index);
            } else {
                decodeEscape(VLC_DCT_INTRA, true);
            }
            coef = placeRun(coefQ, coef, zigzag);
        }
        for (var i = coef; i < BLOCK_SQUARE_SIZE; i++) {
            coefQ[zigzag[i]] = 0;
        }
    }

    private void decodeInterTCOEF(int[] coefQ, int coef, int[] zigzag) {
        last = false;
        while (!last) {
            var index = decode(VLC_DCT);
            if (index != TCOEF_ESCAPE) {
                interTableIndex(index);
            } else {
                decodeEscape(VLC_DCT, false);
            }
            coef = placeRun(coefQ, coef, zigzag);
        }
        for (var i = coef; i < BLOCK_SQUARE_SIZE; i++) {
            coefQ[zigzag[i]] = 0;
        }
    }

    private int placeRun(int[] coefQ, int coef, int[] zigzag) {
        if (coef + run >= BLOCK_SQUARE_SIZE) {
            throw CorruptStream.INSTANCE;
        }
        for (var i = 0; i < run; i++) {
            coefQ[zigzag[coef++]] = 0;
        }
        coefQ[zigzag[coef++]] = level;
        return coef;
    }

    private void decodeEscape(Vlc vlc, boolean intra) {
        if (bits.getBits(1) == 0) {
            var index = decode(vlc);
            if (intra) {
                intraTableIndex(index);
            } else {
                interTableIndex(index);
            }
            var lmax = intra ? RefTables.LMAXINTRA : RefTables.LMAXINTER;
            var abs = Math.abs(level) + lmax[(run & 0x3F) + ((last ? 1 : 0) << 6)];
            level = Integer.signum(level) * abs;
        } else if (bits.getBits(1) == 0) {
            var index = decode(vlc);
            if (intra) {
                intraTableIndex(index);
            } else {
                interTableIndex(index);
            }
            var rmax = intra ? RefTables.RMAXINTRA : RefTables.RMAXINTER;
            run = run + rmax[(Math.abs(level) & 0x1F) + ((last ? 1 : 0) << 5)];
        } else {
            last = bits.getBits(1) != 0;
            run = bits.getBits(NUMBITS_ESC_RUN);
            bits.getBits(1);
            level = bits.getBits(12);
            bits.getBits(1);
            if (level > 2047) {
                level -= 1 << 12;
            }
        }
    }

    private void intraTableIndex(int index) {
        var entry = RefTables.INTRA_YAVCLHASHING_TABLE[index];
        level = entry & 0x1F;
        run = (entry & 0x3E0) >> 5;
        last = (entry & 0x400) != 0;
        if (bits.getBits(1) != 0) {
            level = -level;
        }
    }

    private void interTableIndex(int index) {
        if (index < 0 || index >= 102) {
            throw CorruptStream.INSTANCE;
        }
        last = false;
        var left = index;
        if (index >= 58) {
            left -= 58;
            last = true;
        }
        run = 0;
        var table = last ? RefTables.IF_LAST_NUM_OF_LEVEL_AT_RUN : RefTables.IF_NOT_LAST_NUM_OF_LEVEL_AT_RUN;
        while (left >= 0) {
            left -= table[run];
            run++;
        }
        run--;
        level = left + table[run] + 1;
        if (bits.getBits(1) != 0) {
            level = -level;
        }
    }

    final void decodeRvlcTcoef(int[] coefQ, int coef, int[] zigzag, boolean intra) {
        last = false;
        var vlc = intra ? VLC_DCT_INTRA_RVLC : VLC_DCT_RVLC;
        var table = intra ? RefTables.INTRA_RVLCYAVCLHASHING_TABLE : RefTables.INTER_RVLCYAVCLHASHING_TABLE;
        while (!last) {
            var index = decode(vlc);
            if (index != TCOEF_RVLC_ESCAPE) {
                var entry = table[index];
                level = entry & 0x1F;
                run = (entry & 0x7E0) >> 5;
                last = (entry & 0x800) != 0;
                if (bits.getBits(1) != 0) {
                    level = -level;
                }
            } else {
                bits.getBits(1);
                last = bits.getBits(1) != 0;
                run = bits.getBits(6);
                bits.getBits(1);
                level = bits.getBits(11);
                bits.getBits(1);
                if (decode(VLC_DCT_INTRA_RVLC) != TCOEF_RVLC_ESCAPE) {
                    throw CorruptStream.INSTANCE;
                }
                if (bits.getBits(1) != 0) {
                    level = -level;
                }
            }
            coef = placeRun(coefQ, coef, zigzag);
        }
        for (var i = coef; i < BLOCK_SQUARE_SIZE; i++) {
            coefQ[zigzag[i]] = 0;
        }
    }

    final int decodeIntraDCmpeg(boolean luma) {
        var size = luma ? decode(VLC_INTRA_DCY) : decode(VLC_INTRA_DCC);
        if (size == 0) {
            return 0;
        }
        if (size <= 8) {
            var value = bits.getBits(size);
            if (((1 << (size - 1)) & value) == 0) {
                return -((0xFF >> (8 - size)) & ~value);
            }
            return value;
        }
        var value = bits.getBits(size);
        int result;
        if (((1 << (size - 1)) & value) == 0) {
            result = -((0xFFFF >> (16 - size)) & ~value);
        } else {
            result = value;
        }
        bits.getBits(1);
        return result;
    }

    /**
     * Adds the DC and AC prediction to the quantized coefficients of an intra block ({@code inverseDCACPred}).
     */
    final void inverseDCACPred(MbMode mode, int blkIdx, int[] coefQ, int qp, int dcScaler, int[] pred, int predQP) {
        var defVal = 1 << (volmd.nBits + 2);
        if (pred == null) {
            coefQ[0] += divRoundNearest(defVal, dcScaler);
            return;
        }
        coefQ[0] = Math.clamp(coefQ[0] + divRoundNearest(pred[0], dcScaler), -2048, 2047);
        if ((blkIdx < A_BLOCK1 - 1 && mode.acPrediction)
                || (blkIdx >= A_BLOCK1 - 1 && mode.acPredictionAlpha[(blkIdx - 7) / 4])) {
            if (mode.predDir[blkIdx] == MbMode.HORIZONTAL) {
                for (int i = 8, j = 8; j < 2 * BLOCK_SIZE - 1; i += 8, j++) {
                    var add = qp == predQP ? pred[j] : divRoundNearest(pred[j] * predQP, qp);
                    coefQ[i] = Math.clamp(coefQ[i] + add, -2048, 2047);
                }
            } else if (mode.predDir[blkIdx] == MbMode.VERTICAL) {
                for (var i = 1; i < BLOCK_SIZE; i++) {
                    var add = qp == predQP ? pred[i] : divRoundNearest(pred[i] * predQP, qp);
                    coefQ[i] = Math.clamp(coefQ[i] + add, -2048, 2047);
                }
            } else {
                throw CorruptStream.INSTANCE;
            }
        }
    }

    /**
     * {@return the DC scalers of a quantizer: luminance in the low 16 bits, chrominance above}
     */
    static int dcScalers(int qp) {
        int y;
        int c;
        if (qp <= 4) {
            y = 8;
            c = 8;
        } else if (qp <= 8) {
            y = 2 * qp;
            c = (qp + 13) / 2;
        } else if (qp <= 24) {
            y = qp + 8;
            c = (qp + 13) / 2;
        } else {
            y = 2 * qp - 16;
            c = qp - 6;
        }
        return y | (c << 16);
    }

    private int[][] leftMemory;
    private int[][] topMemory;
    private int[][] leftTopMemory;
    private MbMode leftMode;
    private MbMode topMode;
    private MbMode leftTopMode;

    private void findIntraNeighbors(MbMode mode, int mbIndex, int mbx, int mby) {
        leftMemory = topMemory = leftTopMemory = null;
        leftMode = topMode = leftTopMode = null;
        var mbTop = mby - 1;
        if (mbTop >= 0 && mode.videoPacketNumber == mbmd[mbIndex - numMBX].videoPacketNumber) {
            topMemory = mbmAbove[mbx];
            topMode = mbmd[mbIndex - numMBX];
        }
        if (mbx > 0 && mode.videoPacketNumber == mbmd[mbIndex - 1].videoPacketNumber) {
            leftMemory = mbmCurr[mbx - 1];
            leftMode = mbmd[mbIndex - 1];
        }
        if (mbTop >= 0 && mbx > 0 && mode.videoPacketNumber == mbmd[mbIndex - numMBX - 1].videoPacketNumber) {
            leftTopMemory = mbmAbove[mbx - 1];
            leftTopMode = mbmd[mbIndex - numMBX - 1];
        }
    }

    /**
     * Decodes the texture of an intra macroblock into the current VOP ({@code decodeTextureIntraMB}).
     *
     * @param shapeY the binary shape of the macroblock for shape-adaptive DCT, or {@code null}
     * @param shapeUV the chrominance shape of the macroblock for shape-adaptive DCT, or {@code null}
     */
    final void decodeTextureIntraMB(MbMode mode, int mbIndex, int mbx, int mby, int[] y, int offY, int[] u, int[] v,
                                    int offUV, int[] shapeY, int[] shapeUV) {
        if (mode.transpStatus[0] == MbMode.ALL) {
            return;
        }
        var qp = mode.stepSize;
        var scalers = dcScalers(qp);
        var mbTop = mby - 1;
        if (uiSprite == 1 && vopmd.spriteXmitMode != VopMode.STOP) {
            if (mbTop >= 0) {
                mbmd[mbIndex - numMBX].videoPacketNumber = 0;
            }
            if (mbx > 0) {
                mbmd[mbIndex - 1].videoPacketNumber = 0;
            }
            if (mbTop >= 0 && mbx > 0) {
                mbmd[mbIndex - numMBX - 1].videoPacketNumber = 0;
            }
        }
        findIntraNeighbors(mode, mbIndex, mbx, mby);
        for (var blk = Y_BLOCK1; blk <= V_BLOCK; blk++) {
            int[] dst;
            int dstOff;
            int[] shape = null;
            var shapeOff = 0;
            int width;
            int scaler;
            if (blk < U_BLOCK) {
                if (mode.transpStatus[blk] == MbMode.ALL) {
                    continue;
                }
                dst = y;
                var partial = shapeY != null && mode.transpStatus[blk] == MbMode.PARTIAL;
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
                shape = partial ? shapeY : null;
                width = frameWidthY;
                scaler = scalers & 0xFFFF;
            } else {
                width = frameWidthUV;
                dst = blk == U_BLOCK ? u : v;
                dstOff = offUV;
                shape = shapeUV != null && mode.transpStatus[blk] == MbMode.PARTIAL ? shapeUV : null;
                scaler = scalers >>> 16;
            }
            var pred = decideIntraPred(mode, qp, blk, leftMemory, topMemory, leftTopMemory, mbmCurr[mbx], leftMode,
                    topMode, leftTopMode);
            decodeIntraBlockTexture(dst, dstOff, width, qp, scaler, blk, mbmCurr[mbx], mode, pred, qpPred, shape,
                    shapeOff, blk < U_BLOCK ? MB_SIZE : BLOCK_SIZE, 0);
        }
    }

    /**
     * Decodes an alpha plane of an intra macroblock into the current VOP ({@code decodeAlphaIntraMB}).
     */
    final void decodeAlphaIntraMB(MbMode mode, int mbIndex, int mbx, int mby, int[] a, int offA, int aux,
                                  int[] shapeY) {
        if (mode.transpStatus[0] == MbMode.ALL) {
            return;
        }
        var qp = mode.stepSizeAlpha;
        if (mode.stepSizeAlpha < 1) {
            mode.stepSizeAlpha = 1;
        }
        var scaler = dcScalers(qp) & 0xFFFF;
        mode.codeDcAsAcAlpha = false;
        if (mode.codAlpha[aux] == MbMode.ALPHA_ALL255) {
            var p = offA;
            for (var iy = 0; iy < MB_SIZE; iy++, p += frameWidthY) {
                Arrays.fill(a, p, p + MB_SIZE, 255);
            }
            var curr = mbmCurr[mbx];
            for (var blk = A_BLOCK1; blk <= A_BLOCK4; blk++) {
                var saved = curr[blk - 1];
                saved[0] = divRoundNearest(255 * 8, scaler) * scaler;
                for (var i = 1; i < BLOCK_SIZE; i++) {
                    saved[i] = 0;
                    saved[i + BLOCK_SIZE - 1] = 0;
                }
            }
            return;
        }
        findIntraNeighbors(mode, mbIndex, mbx, mby);
        for (var blk = A_BLOCK1; blk <= A_BLOCK4; blk++) {
            if (mode.transpStatus[blk - 6] == MbMode.ALL) {
                continue;
            }
            var dstOff = offA;
            var shapeOff = 0;
            switch (blk) {
                case A_BLOCK2 -> {
                    dstOff += BLOCK_SIZE;
                    shapeOff = BLOCK_SIZE;
                }
                case A_BLOCK3 -> {
                    dstOff += frameWidthYxBlkSize;
                    shapeOff = BLOCK_SIZE * MB_SIZE;
                }
                case A_BLOCK4 -> {
                    dstOff += frameWidthYxBlkSize + BLOCK_SIZE;
                    shapeOff = BLOCK_SIZE * MB_SIZE + BLOCK_SIZE;
                }
                default -> {
                }
            }
            var shape = shapeY != null && mode.transpStatus[blk - 6] == MbMode.PARTIAL ? shapeY : null;
            var pred = decideIntraPred(mode, qp, blk + aux * 4, leftMemory, topMemory, leftTopMemory, mbmCurr[mbx],
                    leftMode, topMode, leftTopMode);
            decodeIntraBlockTexture(a, dstOff, frameWidthY, qp, scaler, blk + aux * 4, mbmCurr[mbx], mode, pred,
                    qpPred, shape, shapeOff, MB_SIZE, aux);
        }
    }

    /**
     * Decodes the residual of an inter macroblock into the error buffers ({@code decodeTextureInterMB}).
     */
    final void decodeTextureInterMB(MbMode mode, int[] shapeY, int[] shapeUV) {
        if (mode.transpStatus[0] == MbMode.ALL || mode.skip) {
            return;
        }
        var qp = mode.stepSize;
        for (var blk = Y_BLOCK1; blk <= V_BLOCK; blk++) {
            int[] dst;
            int dstOff;
            int width;
            int[] shape;
            var shapeOff = 0;
            if (blk < U_BLOCK) {
                if (mode.transpStatus[blk] == MbMode.ALL) {
                    continue;
                }
                dst = errorMBY;
                dstOff = switch (blk) {
                    case Y_BLOCK1 -> 0;
                    case Y_BLOCK2 -> BLOCK_SIZE * rrvScale;
                    case Y_BLOCK3 -> MB_SIZE * BLOCK_SIZE * rrvScale * rrvScale;
                    default -> MB_SIZE * BLOCK_SIZE * rrvScale * rrvScale + BLOCK_SIZE * rrvScale;
                };
                shapeOff = switch (blk) {
                    case Y_BLOCK1 -> 0;
                    case Y_BLOCK2 -> BLOCK_SIZE;
                    case Y_BLOCK3 -> BLOCK_SIZE * MB_SIZE;
                    default -> BLOCK_SIZE * MB_SIZE + BLOCK_SIZE;
                };
                shape = shapeY != null && mode.transpStatus[blk] == MbMode.PARTIAL ? shapeY : null;
                width = MB_SIZE * rrvScale;
            } else {
                width = BLOCK_SIZE * rrvScale;
                dst = blk == U_BLOCK ? errorMBU : errorMBV;
                dstOff = 0;
                shape = shapeUV != null && mode.transpStatus[blk] == MbMode.PARTIAL ? shapeUV : null;
            }
            if (mode.coded(blk)) {
                decodeTextureInterBlock(dst, dstOff, width, qp, false, mode, blk, shape, shapeOff,
                        blk < U_BLOCK ? MB_SIZE : BLOCK_SIZE, 0);
            } else if (vopmd.rrvOnOff == 1) {
                for (var i = 0; i < MB_SIZE; i++) {
                    Arrays.fill(dst, dstOff + i * width, dstOff + i * width + MB_SIZE, 0);
                }
            } else {
                for (var i = 0; i < BLOCK_SIZE; i++) {
                    Arrays.fill(dst, dstOff + i * width, dstOff + i * width + BLOCK_SIZE, 0);
                }
            }
        }
    }

    /**
     * Decodes the residual of an alpha plane of an inter macroblock, or fills it when it is opaque
     * ({@code decodeAlphaInterMB}).
     */
    final void decodeAlphaInterMB(MbMode mode, int[] a, int offA, int aux, int[] shapeY) {
        if (mode.transpStatus[0] == MbMode.ALL) {
            return;
        }
        if (mode.codAlpha[aux] == MbMode.ALPHA_ALL255) {
            var p = offA;
            for (var iy = 0; iy < MB_SIZE; iy++, p += frameWidthY) {
                Arrays.fill(a, p, p + MB_SIZE, 255);
            }
            return;
        }
        if (mode.codAlpha[aux] != MbMode.ALPHA_CODED) {
            return;
        }
        var qp = mode.stepSizeAlpha;
        var error = errorMBA[aux];
        for (var blk = A_BLOCK1; blk <= A_BLOCK4; blk++) {
            if (mode.transpStatus[blk - 6] == MbMode.ALL) {
                continue;
            }
            var off = switch (blk) {
                case A_BLOCK1 -> 0;
                case A_BLOCK2 -> BLOCK_SIZE;
                case A_BLOCK3 -> MB_SIZE * BLOCK_SIZE;
                default -> MB_SIZE * BLOCK_SIZE + BLOCK_SIZE;
            };
            var shape = shapeY != null && mode.transpStatus[blk - 6] == MbMode.PARTIAL ? shapeY : null;
            if (mode.coded(blk + aux * 4)) {
                decodeTextureInterBlock(error, off, MB_SIZE, qp, true, mode, blk + aux * 4, shape, off, MB_SIZE,
                        aux);
            } else {
                for (var i = 0; i < BLOCK_SIZE; i++) {
                    Arrays.fill(error, off + i * MB_SIZE, off + i * MB_SIZE + BLOCK_SIZE, 0);
                }
            }
        }
    }

    /**
     * Stores the luminance prediction and the zero-motion chrominance of the past reference in the current VOP
     * ({@code copyFromPredForYAndRefForCToCurrQ}).
     */
    final void copyFromPredForYAndRefForCToCurrQ(int x, int y, int[] currY, int offY, int[] currU, int[] currV,
                                                 int offUV, Rct limit) {
        limitFullPel(x, y, limit, MB_SIZE * rrvScale);
        x = limitXY[0];
        y = limitXY[1];
        var refUV = (y / 2 + EXPANDUV_REF_FRAME) * frameWidthUV + x / 2 + EXPANDUV_REF_FRAME;
        var widthY = MB_SIZE * rrvScale;
        var widthUV = BLOCK_SIZE * rrvScale;
        var p = 0;
        for (var iy = 0; iy < BLOCK_SIZE * rrvScale; iy++) {
            System.arraycopy(predMBY, p, currY, offY, widthY);
            System.arraycopy(refQ0.u.pixels, refUV, currU, offUV, widthUV);
            System.arraycopy(refQ0.v.pixels, refUV, currV, offUV, widthUV);
            offY += frameWidthY;
            p += widthY;
            offUV += frameWidthUV;
            refUV += frameWidthUV;
            System.arraycopy(predMBY, p, currY, offY, widthY);
            offY += frameWidthY;
            p += widthY;
        }
    }

    /**
     * Stores the prediction of an alpha plane in the current VOP ({@code copyAlphaFromPredToCurrQ}).
     */
    final void copyAlphaFromPredToCurrQ(int[] a, int offA, int aux) {
        var pred = predMBA[aux];
        for (var iy = 0; iy < MB_SIZE; iy++) {
            System.arraycopy(pred, iy * MB_SIZE, a, offA, MB_SIZE);
            offA += frameWidthY;
        }
    }

    /**
     * Stores the average of the forward and backward predictions plus the residual in the current VOP
     * ({@code averagePredAndAddErrorToCurrQ}).
     */
    final void averagePredAndAddErrorToCurrQ(int[] y, int offY, int[] u, int[] v, int offUV) {
        var ic = 0;
        for (var iy = 0; iy < MB_SIZE; iy++) {
            for (var ix = 0; ix < MB_SIZE; ix++, ic++) {
                y[offY + ix] = clip(((predMBY[ic] + predMBBackY[ic] + 1) >> 1) + errorMBY[ic]);
            }
            offY += frameWidthY;
        }
        ic = 0;
        for (var iy = 0; iy < BLOCK_SIZE; iy++) {
            for (var ix = 0; ix < BLOCK_SIZE; ix++, ic++) {
                u[offUV + ix] = clip(((predMBU[ic] + predMBBackU[ic] + 1) >> 1) + errorMBU[ic]);
                v[offUV + ix] = clip(((predMBV[ic] + predMBBackV[ic] + 1) >> 1) + errorMBV[ic]);
            }
            offUV += frameWidthUV;
        }
    }

    /**
     * Stores the average of the forward and backward predictions in the current VOP
     * ({@code averagePredAndAssignToCurrQ}).
     */
    final void averagePredAndAssignToCurrQ(int[] y, int offY, int[] u, int[] v, int offUV) {
        var ic = 0;
        for (var iy = 0; iy < MB_SIZE; iy++) {
            for (var ix = 0; ix < MB_SIZE; ix++, ic++) {
                y[offY + ix] = (predMBY[ic] + predMBBackY[ic] + 1) >> 1;
            }
            offY += frameWidthY;
        }
        ic = 0;
        for (var iy = 0; iy < BLOCK_SIZE; iy++) {
            for (var ix = 0; ix < BLOCK_SIZE; ix++, ic++) {
                u[offUV + ix] = (predMBU[ic] + predMBBackU[ic] + 1) >> 1;
                v[offUV + ix] = (predMBV[ic] + predMBBackV[ic] + 1) >> 1;
            }
            offUV += frameWidthUV;
        }
    }

    private void predictLuma(int[] pred, int[] ref, MbMode mode, int mvOff, boolean fourMV, int x, int y,
                             Rct limit) {
        if (!fourMV) {
            var v = mv[mvOff];
            if (volmd.quarterSample) {
                motionCompQuarterSample(pred, 0, ref, 0, MB_SIZE, x * 4 + v.trueX, y * 4 + v.trueY,
                        vopmd.roundingControl, limit);
            } else {
                motionComp(pred, 0, ref, 0, MB_SIZE, x * 2 + v.trueX, y * 2 + v.trueY, vopmd.roundingControl,
                        limit);
            }
        } else {
            predictBlocks(pred, ref, mode, mvOff, x, y, vopmd.roundingControl, limit);
        }
    }

    private void predictBlocks(int[] pred, int[] ref, MbMode mode, int mvOff, int x, int y, int rounding,
                               Rct limit) {
        for (var blk = 0; blk < 4; blk++) {
            var v = mv[mvOff + blk + 1];
            if (mode.transpStatus[blk + 1] == MbMode.ALL) {
                continue;
            }
            if (volmd.quarterSample) {
                motionCompQuarterSample(pred, BLK_PIXEL[blk], ref, 0, BLOCK_SIZE,
                        (x + BLK_OFFSET_X[blk]) * 4 + v.trueX, (y + BLK_OFFSET_Y[blk]) * 4 + v.trueY,
                        vopmd.roundingControl, limit);
            } else {
                motionComp(pred, BLK_PIXEL[blk], ref, 0, BLOCK_SIZE, (x + BLK_OFFSET_X[blk]) * 2 + v.trueX,
                        (y + BLK_OFFSET_Y[blk]) * 2 + v.trueY, rounding, limit);
            }
        }
    }

    /**
     * Predicts a B-VOP macroblock and adds its residual into the current VOP ({@code motionCompAndAddErrorMB_BVOP}).
     */
    final void motionCompAndAddErrorMB_BVOP(int fwdOff, int bakOff, MbMode mode, int mbIndex, int mbx, int mby,
                                            int x, int y, int[] currY, int offY, int[] currU, int[] currV,
                                            int offUV, Rct limitFwd, Rct limitBak) {
        if (vopmd.interlace) {
            switch (mode.mbType) {
                case MbMode.FORWARD -> {
                    motionCompOneBVOPReference(predMB, false, x, y, mode, mv, fwdOff, limitFwd);
                    addErrorAndPredToCurrQ(currY, offY, currU, currV, offUV);
                }
                case MbMode.BACKWARD -> {
                    motionCompOneBVOPReference(predMB, true, x, y, mode, mv, bakOff, limitBak);
                    addErrorAndPredToCurrQ(currY, offY, currU, currV, offUV);
                }
                case MbMode.DIRECT -> {
                    int offset;
                    if (volmd.alphaUsage != VopPlanes.RECTANGLE) {
                        offset = (Math.min(Math.max(0, mbx), numMBXRef - 1)
                                + Math.min(Math.max(0, mby), numMBYRef - 1) * numMBXRef) * PVOP_MV_PER_REF_PER_MB;
                    } else {
                        offset = PVOP_MV_PER_REF_PER_MB * (mbx + mby * numMBX);
                    }
                    motionCompDirectMode(x, y, mbIndex, mvRef, offset, limitFwd, limitBak, 0);
                    averagePredAndAddErrorToCurrQ(currY, offY, currU, currV, offUV);
                }
                default -> {
                    motionCompOneBVOPReference(predMB, false, x, y, mode, mv, fwdOff, limitFwd);
                    motionCompOneBVOPReference(predMBBack, true, x, y, mode, mv, bakOff, limitBak);
                    averagePredAndAddErrorToCurrQ(currY, offY, currU, currV, offUV);
                }
            }
            return;
        }
        if (mode.mbType == MbMode.DIRECT || mode.mbType == MbMode.INTERPOLATE) {
            predictBothDirections(fwdOff, bakOff, mode, x, y, limitFwd, limitBak);
            averagePredAndAddErrorToCurrQ(currY, offY, currU, currV, offUV);
        } else {
            var forward = mode.mbType == MbMode.FORWARD;
            var off = forward ? fwdOff : bakOff;
            var ref = forward ? refQ0 : refQ1;
            var limit = forward ? limitFwd : limitBak;
            var v = mv[off];
            if (volmd.quarterSample) {
                motionCompQuarterSample(predMBY, 0, ref.y.pixels, 0, MB_SIZE, x * 4 + v.trueX, y * 4 + v.trueY,
                        vopmd.roundingControl, limit);
            } else {
                motionComp(predMBY, 0, ref.y.pixels, 0, MB_SIZE, x * 2 + v.trueX, y * 2 + v.trueY,
                        vopmd.roundingControl, limit);
            }
            mvLookupUVWithShape(mode, mv, off, uvVector);
            motionCompUV(predMBU, predMBV, ref, x, y, uvVector[0], uvVector[1], vopmd.roundingControl, limit);
            addErrorAndPredToCurrQ(currY, offY, currU, currV, offUV);
        }
    }

    private void predictBothDirections(int fwdOff, int bakOff, MbMode mode, int x, int y, Rct limitFwd,
                                       Rct limitBak) {
        var direct = mode.mbType == MbMode.DIRECT;
        predictLuma(predMBY, refQ0.y.pixels, mode, fwdOff, mode.has4MVForward || direct, x, y, limitFwd);
        mvLookupUVWithShape(mode, mv, fwdOff, uvVector);
        motionCompUV(predMBU, predMBV, refQ0, x, y, uvVector[0], uvVector[1], vopmd.roundingControl, limitFwd);
        predictLuma(predMBBackY, refQ1.y.pixels, mode, bakOff, mode.has4MVBackward || direct, x, y, limitBak);
        mvLookupUVWithShape(mode, mv, bakOff, uvVector);
        motionCompUV(predMBBackU, predMBBackV, refQ1, x, y, uvVector[0], uvVector[1], vopmd.roundingControl,
                limitBak);
    }

    /**
     * Predicts an alpha plane of a B-VOP macroblock into the prediction buffer ({@code motionCompAlphaMB_BVOP}).
     */
    final void motionCompAlphaMB_BVOP(int fwdOff, int bakOff, MbMode mode, int mbIndex, int mbx, int mby, int x,
                                      int y, Rct limitFwd, Rct limitBak, int aux) {
        var predA = predMBA[aux];
        var predBackA = predMBBackA[aux];
        var refA0 = refQ0.a[aux].pixels;
        var refA1 = refQ1.a[aux].pixels;
        if (mode.mbType == MbMode.DIRECT || mode.mbType == MbMode.INTERPOLATE) {
            if (mode.mbType == MbMode.DIRECT && !mode.fieldMV) {
                predictBlocks(predA, refA0, mode, fwdOff, x, y, 0, limitFwd);
                predictBlocks(predBackA, refA1, mode, bakOff, x, y, 0, limitBak);
            } else {
                if (!mode.has4MVForward && !mode.fieldMV) {
                    predictAlphaMacroblock(predA, refA0, mv[fwdOff], x, y, limitFwd);
                } else if (mode.fieldMV && mode.mbType == MbMode.INTERPOLATE) {
                    predictAlphaFields(predA, refA0, fwdOff, mode.forwardTop, mode.forwardBottom, x, y, limitFwd);
                } else if (mode.fieldMV && mode.mbType == MbMode.DIRECT) {
                    var offset = (Math.min(Math.max(0, mbx), numMBXRef - 1)
                            + Math.min(Math.max(0, mby), numMBYRef - 1) * numMBXRef) * PVOP_MV_PER_REF_PER_MB;
                    motionCompDirectMode(x, y, mbIndex, mvRef, offset, limitFwd, limitBak, 1);
                } else {
                    predictBlocks(predA, refA0, mode, fwdOff, x, y, 0, limitFwd);
                }
                if (!mode.has4MVBackward && !mode.fieldMV) {
                    predictAlphaMacroblock(predBackA, refA1, mv[bakOff], x, y, limitBak);
                } else if (mode.fieldMV && mode.mbType == MbMode.INTERPOLATE) {
                    predictAlphaFields(predBackA, refA1, bakOff, mode.backwardTop, mode.backwardBottom, x, y,
                            limitBak);
                } else if (!mode.fieldMV || mode.mbType != MbMode.DIRECT) {
                    predictBlocks(predBackA, refA1, mode, bakOff, x, y, 0, limitBak);
                }
            }
            for (var i = 0; i < MB_SQUARE_SIZE; i++) {
                predA[i] = (predA[i] + predBackA[i] + 1) >> 1;
            }
        } else {
            var forward = mode.mbType == MbMode.FORWARD;
            var off = forward ? fwdOff : bakOff;
            var ref = forward ? refA0 : refA1;
            var limit = forward ? limitFwd : limitBak;
            if (mode.fieldMV) {
                predictAlphaFields(predA, ref, off, forward ? mode.forwardTop : mode.backwardTop,
                        forward ? mode.forwardBottom : mode.backwardBottom, x, y, limit);
            } else {
                predictAlphaMacroblock(predA, ref, mv[off], x, y, limit);
            }
        }
    }

    private void predictAlphaMacroblock(int[] pred, int[] ref, MotionVector v, int x, int y, Rct limit) {
        if (volmd.quarterSample) {
            motionCompQuarterSample(pred, 0, ref, 0, MB_SIZE, x * 4 + v.trueX, y * 4 + v.trueY,
                    vopmd.roundingControl, limit);
        } else {
            motionComp(pred, 0, ref, 0, MB_SIZE, x * 2 + v.trueX, y * 2 + v.trueY, 0, limit);
        }
    }

    private void predictAlphaFields(int[] pred, int[] ref, int off, boolean topRef, boolean bottomRef, int x, int y,
                                    Rct limit) {
        var top = mv[off + 1 + (topRef ? 1 : 0)];
        var topOff = topRef ? frameWidthY : 0;
        if (volmd.quarterSample) {
            motionCompQuarterSample(pred, 0, ref, topOff, 0, 4 * x + top.trueX, 4 * y + top.trueY,
                    vopmd.roundingControl, limit);
        } else {
            motionCompYField(pred, 0, ref, topOff, 2 * x + top.trueX, 2 * y + top.trueY, limit);
        }
        var bottom = mv[off + 3 + (bottomRef ? 1 : 0)];
        var bottomOff = bottomRef ? frameWidthY : 0;
        if (volmd.quarterSample) {
            motionCompQuarterSample(pred, MB_SIZE, ref, bottomOff, 0, 4 * x + bottom.trueX, 4 * y + bottom.trueY,
                    vopmd.roundingControl, limit);
        } else {
            motionCompYField(pred, MB_SIZE, ref, bottomOff, 2 * x + bottom.trueX, 2 * y + bottom.trueY, limit);
        }
    }

    /**
     * Reconstructs a B-VOP macroblock without residual at zero motion ({@code copyFromRefToCurrQ_BVOP}).
     */
    final void copyFromRefToCurrQ_BVOP(MbMode mode, int x, int y, int[] currY, int offY, int[] currU, int[] currV,
                                       int offUV, Rct limitFwd, Rct limitBak) {
        if (mode.mbType == MbMode.DIRECT || mode.mbType == MbMode.INTERPOLATE) {
            if (volmd.quarterSample) {
                motionCompQuarterSample(predMBY, 0, refQ0.y.pixels, 0, MB_SIZE, x * 4, y * 4,
                        vopmd.roundingControl, limitFwd);
            } else {
                motionComp(predMBY, 0, refQ0.y.pixels, 0, MB_SIZE, x * 2, y * 2, vopmd.roundingControl, limitFwd);
            }
            motionCompUV(predMBU, predMBV, refQ0, x, y, 0, 0, vopmd.roundingControl, limitFwd);
            if (volmd.quarterSample) {
                motionCompQuarterSample(predMBBackY, 0, refQ1.y.pixels, 0, MB_SIZE, x * 4, y * 4,
                        vopmd.roundingControl, limitBak);
            } else {
                motionComp(predMBBackY, 0, refQ1.y.pixels, 0, MB_SIZE, x * 2, y * 2, vopmd.roundingControl,
                        limitBak);
            }
            motionCompUV(predMBBackU, predMBBackV, refQ1, x, y, 0, 0, vopmd.roundingControl, limitBak);
            averagePredAndAssignToCurrQ(currY, offY, currU, currV, offUV);
        } else {
            var forward = mode.mbType == MbMode.FORWARD;
            copyFromRefToCurrQ(forward ? refQ0 : refQ1, x, y, currY, offY, currU, currV, offUV,
                    forward ? limitFwd : limitBak);
        }
    }

    /**
     * Reconstructs a skipped B-VOP macroblock from its vectors ({@code motionCompSkipMB_BVOP}).
     */
    final void motionCompSkipMB_BVOP(MbMode mode, int fwdOff, int bakOff, int x, int y, int[] currY, int offY,
                                     int[] currU, int[] currV, int offUV, Rct limitFwd, Rct limitBak) {
        if (mode.mbType == MbMode.DIRECT || mode.mbType == MbMode.INTERPOLATE) {
            predictBothDirections(fwdOff, bakOff, mode, x, y, limitFwd, limitBak);
            averagePredAndAssignToCurrQ(currY, offY, currU, currV, offUV);
        } else {
            var forward = mode.mbType == MbMode.FORWARD;
            copyFromRefToCurrQ(forward ? refQ0 : refQ1, x, y, currY, offY, currU, currV, offUV,
                    forward ? limitFwd : limitBak);
        }
    }
}
