/* Ports the Microsoft MPEG-4 Visual reference software of ISO/IEC 14496-5:2001 (version 2 FDAM1-2.3-001213,
 * sys/mode.hpp and mode.cpp: CMBMode). Copyright 1996-2000 Microsoft Corporation and the contributors named in the
 * sources; ISO/IEC free license for products conforming to ISO/IEC 14496-2. Modified for Smalt. */
package com.github.auties00.smalt.video.mpeg4.internal.object;

/**
 * The coding mode of one macroblock ({@code CMBMode}). Block numbers follow the reference software: 1 to 4 the
 * luminance blocks, 5 and 6 the chrominance blocks, 7 to 10 the alpha blocks of the first auxiliary component, and so
 * on; index 0 of the transparency arrays describes the whole macroblock.
 */
final class MbMode {
    static final int ALL = 0;
    static final int PARTIAL = 1;
    static final int NONE = 2;

    static final int INTRA = 0;
    static final int INTRAQ = 1;
    static final int INTER = 2;
    static final int INTERQ = 3;

    static final int DIRECT = 0;
    static final int INTERPOLATE = 1;
    static final int BACKWARD = 2;
    static final int FORWARD = 3;

    static final int ALL_TRANSP = 0;
    static final int ALL_OPAQUE = 1;
    static final int INTRA_CAE = 2;
    static final int INTER_CAE_MVDZ = 3;
    static final int INTER_CAE_MVDNZ = 4;
    static final int MVDZ_NOUPDT = 5;
    static final int MVDNZ_NOUPDT = 6;
    static final int UNKNOWN = 7;

    static final int INTRA_NOT_CODED = 0;
    static final int INTRA_CODED = 1;
    static final int INTER_NOT_CODED = 2;
    static final int INTER_CODED = 3;
    static final int UNDEFINED = 4;

    static final int ALPHA_CODED = 0;
    static final int ALPHA_SKIPPED = 1;
    static final int ALPHA_ALL255 = 2;

    static final int UNKNOWN_DIR = 0;
    static final int HORIZONTAL = 1;
    static final int VERTICAL = 2;
    static final int DIAGONAL = 3;

    final int auxCount;
    final int numBlocks;
    final int[] transpStatus = new int[11];
    final int[] numNonTranspPixels = new int[11];
    final int[] fieldTranspStatus = {ALL, ALL, ALL, ALL, ALL};
    final boolean[] fieldPadded = new boolean[5];
    final boolean[] codedBlockPattern;
    final int[] predDir;
    final int[] codAlpha;
    final boolean[] acPredictionAlpha;
    boolean padded;
    boolean skip;
    boolean mcsel;
    int mbType = FORWARD;
    int dctMd = INTRA;
    int shpmd = UNKNOWN;
    int shpssmd = UNDEFINED;
    int intStepDelta;
    boolean has4MVForward;
    boolean has4MVBackward;
    boolean fieldMV;
    boolean forwardTop;
    boolean forwardBottom;
    boolean backwardTop;
    boolean backwardBottom;
    boolean fieldDCT;
    boolean perspectiveForward;
    boolean perspectiveBackward;
    int stepSize;
    int stepSizeAlpha;
    boolean acPrediction;
    boolean interShapeCoding;
    boolean codeDcAsAc;
    boolean codeDcAsAcAlpha;
    boolean colocatedMBSkip;
    boolean colocatedMBMCSEL;
    int videoPacketNumber;
    int directDeltaX;
    int directDeltaY;
    int npSegmentNumber;

    MbMode(int auxCount) {
        this.auxCount = auxCount;
        this.numBlocks = 6 + auxCount * 4;
        for (var i = 0; i < 11; i++) {
            transpStatus[i] = NONE;
        }
        codedBlockPattern = new boolean[numBlocks];
        predDir = new int[numBlocks];
        codAlpha = new int[auxCount];
        acPredictionAlpha = new boolean[auxCount];
    }

    static MbMode[] array(int count, int auxCount) {
        var modes = new MbMode[count];
        for (var i = 0; i < count; i++) {
            modes[i] = new MbMode(auxCount);
        }
        return modes;
    }

    boolean coded(int block) {
        return codedBlockPattern[block - 1];
    }

    void coded(int block, boolean coded) {
        codedBlockPattern[block - 1] = coded;
    }

    /**
     * Copies the mode of another macroblock ({@code operator =}), which leaves out the field motion flags and the
     * shape mode of spatial scalability, as the reference software does.
     */
    void set(MbMode md) {
        System.arraycopy(md.transpStatus, 0, transpStatus, 0, 11);
        System.arraycopy(md.numNonTranspPixels, 0, numNonTranspPixels, 0, 11);
        System.arraycopy(md.predDir, 0, predDir, 0, Math.min(predDir.length, md.predDir.length));
        System.arraycopy(md.fieldTranspStatus, 0, fieldTranspStatus, 0, 5);
        System.arraycopy(md.fieldPadded, 0, fieldPadded, 0, 5);
        padded = md.padded;
        skip = md.skip;
        mcsel = md.mcsel;
        mbType = md.mbType;
        dctMd = md.dctMd;
        shpmd = md.shpmd;
        intStepDelta = md.intStepDelta;
        has4MVForward = md.has4MVForward;
        has4MVBackward = md.has4MVBackward;
        perspectiveForward = md.perspectiveForward;
        perspectiveBackward = md.perspectiveBackward;
        stepSize = md.stepSize;
        acPrediction = md.acPrediction;
        interShapeCoding = md.interShapeCoding;
        codeDcAsAc = md.codeDcAsAc;
        colocatedMBSkip = md.colocatedMBSkip;
        colocatedMBMCSEL = md.colocatedMBMCSEL;
        videoPacketNumber = md.videoPacketNumber;
        System.arraycopy(md.codedBlockPattern, 0, codedBlockPattern, 0,
                Math.min(codedBlockPattern.length, md.codedBlockPattern.length));
        stepSizeAlpha = md.stepSizeAlpha;
        codeDcAsAcAlpha = md.codeDcAsAcAlpha;
        for (var i = 0; i < Math.min(auxCount, md.auxCount); i++) {
            codAlpha[i] = md.codAlpha[i];
            acPredictionAlpha[i] = md.acPredictionAlpha[i];
        }
    }
}
