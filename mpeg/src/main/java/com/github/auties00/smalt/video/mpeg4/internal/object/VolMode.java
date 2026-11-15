/* Ports the Microsoft MPEG-4 Visual reference software of ISO/IEC 14496-5:2001 (version 2 FDAM1-2.3-001213,
 * sys/mode.hpp: VOLMode). Copyright 1996-2000 Microsoft Corporation and the contributors named in the sources;
 * ISO/IEC free license for products conforming to ISO/IEC 14496-2. Modified for Smalt. */
package com.github.auties00.smalt.video.mpeg4.internal.object;

/**
 * The coding settings of a video object layer ({@code VOLMode}).
 */
final class VolMode {
    static final int MAX_MAC = 10;
    static final int BASE_LAYER = 0;
    static final int ENHN_LAYER = 1;
    static final int Q_H263 = 0;
    static final int Q_MPEG = 1;

    int verId = 1;
    int volType = BASE_LAYER;
    boolean not8Bit;
    int quantPrecision = 5;
    int nBits = 8;
    int clockRate;
    int alphaUsage;
    int alphaShapeExtension;
    int auxCompCount;
    boolean shapeOnly;
    boolean noCrChange;
    boolean advPredDisable;
    boolean quarterSample;
    boolean newpredEnable;
    int requestedBackwardMessageType;
    boolean newpredSegmentType;
    boolean resyncMarkerDisable;
    boolean dataPartitioning;
    boolean reversibleVlc;
    int profileAndLevel;
    int quantizer = Q_H263;
    boolean loadIntraMatrix;
    final int[] intraQuantizerMatrix = new int[64];
    boolean loadInterMatrix;
    final int[] interQuantizerMatrix = new int[64];
    boolean loadIntraMatrixAlpha;
    final int[][] intraQuantizerMatrixAlpha = new int[MAX_MAC][64];
    boolean loadInterMatrixAlpha;
    final int[][] interQuantizerMatrixAlpha = new int[MAX_MAC][64];
    boolean deblockFilterDisable = true;
    boolean noGrayQuantUpdate;
    boolean sadctDisable = true;
    boolean complexityEstimationDisable = true;
    int estimationMethod;
    boolean shapeComplexityEstimationDisable;
    boolean opaque;
    boolean transparent;
    boolean intraCAE;
    boolean interCAE;
    boolean noUpdate;
    boolean upsampling;
    boolean textureComplexityEstimationSet1Disable;
    boolean intraBlocks;
    boolean interBlocks;
    boolean inter4vBlocks;
    boolean notCodedBlocks;
    boolean textureComplexityEstimationSet2Disable;
    boolean dctCoefs;
    boolean dctLines;
    boolean vlcSymbols;
    boolean vlcBits;
    boolean motionCompensationComplexityDisable;
    boolean apm;
    boolean npm;
    boolean interpolateMCQ;
    boolean forwBackMCQ;
    boolean halfpel2;
    boolean halfpel4;
    boolean version2ComplexityEstimationDisable = true;
    boolean sadct;
    boolean quarterpel;
    int hierarchyType;
    int enhnType;
    int horSamplingFactorM = 1;
    int horSamplingFactorN = 1;
    int verSamplingFactorM = 1;
    int verSamplingFactorN = 1;
    int frmWidthSS;
    int frmHeightSS;
    int useRefShape;
    int useRefTexture;
    int horSamplingFactorMShape = 1;
    int horSamplingFactorNShape = 1;
    int verSamplingFactorMShape = 1;
    int verSamplingFactorNShape = 1;
    boolean spatialScalability;
    boolean temporalScalability;
    boolean reducedResolutionVopEnable;
}
