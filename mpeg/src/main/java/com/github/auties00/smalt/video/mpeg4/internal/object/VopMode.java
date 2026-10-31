/* Ports the Microsoft MPEG-4 Visual reference software of ISO/IEC 14496-5:2001 (version 2 FDAM1-2.3-001213,
 * sys/mode.hpp: VOPMode, MVInfo, RRVmodeStr). Copyright 1996-2000 Microsoft Corporation and the contributors named
 * in the sources; ISO/IEC free license for products conforming to ISO/IEC 14496-2. Modified for Smalt. */
package com.github.auties00.smalt.video.mpeg4.internal.object;

/**
 * The coding settings of the video object plane being decoded ({@code VOPMode}).
 */
final class VopMode {
    static final int IVOP = 0;
    static final int PVOP = 1;
    static final int BVOP = 2;
    static final int SPRITE = 3;

    static final int B_FORWARD = 0;
    static final int B_BACKWARD = 1;

    static final int STOP = 0;
    static final int PIECE = 1;
    static final int UPDATE = 2;
    static final int PAUSE = 3;
    static final int NEXT = 4;

    /**
     * The motion vector range of one direction ({@code MVInfo}).
     */
    static final class MvInfo {
        int range;
        int fCode;
        int scaleFactor;
    }

    int intStepI;
    int intStep;
    int intStepB;
    final int[] intStepIAlpha = new int[VolMode.MAX_MAC];
    final int[] intStepPAlpha = new int[VolMode.MAX_MAC];
    final int[] intStepBAlpha = new int[VolMode.MAX_MAC];
    int predType;
    int intraDcSwitchThr;
    int roundingControl;
    int shapeBPredDir;
    int vopConstantAlphaValue = 255;
    boolean bgComposition;
    final MvInfo mvInfoForward = new MvInfo();
    final MvInfo mvInfoBackward = new MvInfo();
    boolean interlace;
    boolean topFieldFirst;
    boolean alternateScan;
    int refSelectCode;
    int loadForShape;
    int loadBakShape;
    boolean shapeCodingType;
    int spriteXmitMode = STOP;
    int vopId;
    int numBitsVopId;
    int vopId4PredictionIndication;
    int vopId4Prediction;
    /**
     * Whether reduced resolution VOPs are enabled for the layer ({@code RRVmode.iOnOff}).
     */
    int rrvEnable;
    /**
     * Whether the VOP being decoded has reduced resolution ({@code RRVmode.iRRVOnOff}).
     */
    int rrvOnOff;
}
