package com.github.auties00.smalt.video.h264.internal;

import java.util.Arrays;

/**
 * Decodes the layer representations of a scalable access unit with the processes of ITU-T H.264
 * F.8.1.3 to F.8.1.5: the base decoding of the layers below the target, which parses their
 * macroblocks into the {@link SvcLayer} arrays, and the target decoding, which constructs the
 * samples of the decoded picture.
 *
 * <p>Frames, frames with {@code MbaffFrameFlag} 1 and fields of 4:2:0 8-bit samples are decoded. A
 * field macroblock of a frame occupies every other row of its macroblock pair in the sample arrays
 * and predicts from fields of the reference frames.
 */
final class SvcLayerDecoder {
    /**
     * Supplies the state of the access unit the layers belong to.
     */
    interface Context extends SvcDeblocker.Slices {
        /**
         * Returns the layer representation with a {@code DQId}.
         *
         * @param dqId the {@code DQId}
         * @return the representation, or {@code null} when the access unit has none
         */
        SvcRepresentation representation(int dqId);

        /**
         * Returns the slice whose prediction weights a slice with
         * {@code base_pred_weight_table_flag} 1 inherits (ITU-T H.264 F.8.4.2.1).
         *
         * @param dqId the {@code DQId} of the slice
         * @return the slice of the largest {@code DQId} below {@code dqId} whose weights are coded
         */
        SvcSlice weightSlice(int dqId);

        /**
         * Returns a picture order count of the picture being decoded in the target dependency
         * representation.
         *
         * @param structure {@link SvcPicture#FRAME} for {@code PicOrderCnt( CurrPic )},
         * {@link SvcPicture#TOP} or {@link SvcPicture#BOTTOM} for the order count of a field of a
         * frame
         * @return the picture order count
         */
        int currentPoc(int structure);

        /**
         * Returns a picture order count of the picture being decoded in a dependency
         * representation.
         *
         * @param dependencyId the {@code dependency_id}
         * @param structure {@link SvcPicture#FRAME} for {@code PicOrderCnt( CurrPic )},
         * {@link SvcPicture#TOP} or {@link SvcPicture#BOTTOM} for the order count of a field of a
         * frame
         * @return the picture order count
         */
        int dependencyPoc(int dependencyId, int structure);

        /**
         * Returns the structure of the current picture of a dependency representation.
         *
         * @param dependencyId the {@code dependency_id}
         * @return {@link SvcPicture#FRAME}, {@link SvcPicture#TOP} or {@link SvcPicture#BOTTOM}
         */
        int currentStructure(int dependencyId);

        /**
         * Returns a frame of mid-gray samples standing for a missing reference picture.
         *
         * @return the frame
         */
        DecoderFrame missingFrame();
    }

    /**
     * The row stride of the luma workspace of the intra prediction of a macroblock of a frame
     * with {@code MbaffFrameFlag} 1.
     */
    private static final int WORK = 32;

    /**
     * The row stride of the chroma workspace of the intra prediction of a macroblock of a frame
     * with {@code MbaffFrameFlag} 1.
     */
    private static final int CHROMA_WORK = 16;

    /**
     * The parser of the macroblock syntax of the slices.
     */
    private final SvcSliceParser parser = new SvcSliceParser();

    /**
     * The derivation of the inter-layer motion predictors and of the motion of each macroblock.
     */
    private final SvcMotion motion = new SvcMotion();

    /**
     * The co-located motion of the direct prediction of the current base layer B slice.
     */
    private final SvcColocated colocated = new SvcColocated();

    /**
     * The slice {@link #colocated} was prepared for, or {@code null}.
     */
    private SvcSlice colocatedSlice;

    /**
     * Whether {@link #colocated} has a co-located picture.
     */
    private boolean colocatedPresent;

    /**
     * The refinement and scaling of the transform coefficients and the construction of residuals.
     */
    private final SvcCoefficients coefficients = new SvcCoefficients();

    /**
     * The intra sample predictors.
     */
    private final IntraPredictor intra = new IntraPredictor();

    /**
     * The resampling of intra samples and residuals for spatial inter-layer prediction.
     */
    private final SvcResampler resampler = new SvcResampler();

    /**
     * The deblocking filter of the layer pictures.
     */
    private final SvcDeblocker deblocker = new SvcDeblocker();

    /**
     * The neighbor derivation of the intra sample prediction of a macroblock of a frame with
     * {@code MbaffFrameFlag} 1.
     */
    private final SvcNeighbors intraNeighbors = new SvcNeighbors();

    /**
     * The neighboring macroblock candidates of the intra sample prediction.
     */
    private final int[] candidates = new int[4];

    /**
     * The motion compensation and weighted prediction kernels.
     */
    private final H264Dsp dsp = H264Dsp.INSTANCE;

    /**
     * The luma residual of the current macroblock, 16 by 16 in raster order.
     */
    private final int[] resL = new int[256];

    /**
     * The Cb residual of the current macroblock, 8 by 8 in raster order.
     */
    private final int[] resCb = new int[64];

    /**
     * The Cr residual of the current macroblock, 8 by 8 in raster order.
     */
    private final int[] resCr = new int[64];

    /**
     * The luma prediction of the current macroblock, 16 by 16 in raster order.
     */
    private final byte[] predL = new byte[256];

    /**
     * The Cb prediction of the current macroblock, 8 by 8 in raster order.
     */
    private final byte[] predCb = new byte[64];

    /**
     * The Cr prediction of the current macroblock, 8 by 8 in raster order.
     */
    private final byte[] predCr = new byte[64];

    /**
     * The list 1 luma prediction of a bi-predicted partition before the two predictions are
     * combined.
     */
    private final byte[] tempL = new byte[256];

    /**
     * The list 1 Cb prediction of a bi-predicted partition before the two predictions are combined.
     */
    private final byte[] tempCb = new byte[64];

    /**
     * The list 1 Cr prediction of a bi-predicted partition before the two predictions are combined.
     */
    private final byte[] tempCr = new byte[64];

    /**
     * The intermediate sums of the luma interpolation.
     */
    private final short[] sums = new short[H264Dsp.LUMA_SCRATCH_SHORTS];

    /**
     * The intermediate samples of the luma interpolation.
     */
    private final byte[] mcScratch = new byte[H264Dsp.LUMA_SCRATCH_BYTES];

    /**
     * The luma samples around a block whose motion vector reaches beyond the border of its
     * reference frame or field, with the border extended.
     */
    private final byte[] edge = new byte[24 * 24];

    /**
     * The Cb samples around a block whose motion vector reaches beyond the border of its reference
     * frame or field, with the border extended.
     */
    private final byte[] chromaEdgeCb = new byte[16 * 16];

    /**
     * The Cr samples around a block whose motion vector reaches beyond the border of its reference
     * frame or field, with the border extended.
     */
    private final byte[] chromaEdgeCr = new byte[16 * 16];

    /**
     * The luma workspace of the intra prediction of a macroblock of a frame with
     * {@code MbaffFrameFlag} 1: the neighboring samples in row 0 and column 0, the macroblock from
     * row 1 and column 1.
     */
    private final byte[] lumaWork = new byte[WORK * 17];

    /**
     * The Cb workspace, laid out as {@link #lumaWork} with {@link #CHROMA_WORK}.
     */
    private final byte[] cbWork = new byte[CHROMA_WORK * 9];

    /**
     * The Cr workspace, laid out as {@link #cbWork}.
     */
    private final byte[] crWork = new byte[CHROMA_WORK * 9];

    /**
     * The availability of the neighboring luma samples of the workspace: the left column (rows 0
     * to 15) at 0 to 15, the upper row (columns 0 to 23) at 16 to 39, the corner at 40.
     */
    private final boolean[] lumaAvailable = new boolean[41];

    /**
     * The availability of the neighboring chroma samples of the workspace: the left column at 0
     * to 7, the upper row at 8 to 15, the corner at 16.
     */
    private final boolean[] chromaAvailable = new boolean[17];

    /**
     * Whether the access unit being decoded predicted from a missing or damaged reference
     * picture.
     */
    private boolean damaged;

    /**
     * The access unit being decoded.
     */
    private Context context;

    /**
     * Constructs a layer decoder with its working storage.
     */
    SvcLayerDecoder() {
    }

    /**
     * Sets the access unit the following calls decode.
     *
     * @param context the access unit state
     */
    void start(Context context) {
        this.context = context;
        this.damaged = false;
        this.colocatedSlice = null;
        colocated.clearUnreliable();
    }

    /**
     * Returns whether a missing or damaged reference picture, or the motion of a lost or damaged
     * co-located picture, was predicted from since {@link #start}.
     *
     * @return {@code true} when the samples may differ from the encoder's
     */
    boolean damaged() {
        return damaged || colocated.readUnreliable();
    }

    /**
     * Runs the base decoding of a layer representation without resolution change (ITU-T H.264
     * F.8.1.3.1, step 1a and step 2): the intra reconstruction of the reference layer when
     * required, then the parsing and base decoding of every slice.
     *
     * @param rep the layer representation
     * @param cur the arrays, holding those of the reference layer representation when the
     * representation has one
     */
    void decodeWithoutResolutionChange(SvcRepresentation rep, SvcLayer cur) {
        if (!rep.minNoInterLayerPred && !rep.maxTcoeffLevelPred) {
            var refDq = rep.maxRefLayerDqId;
            var all = context.representation(refDq).maxTcoeffLevelPred;
            for (var mb = 0; mb < cur.mbCount; mb++) {
                if ((all || (cur.sliceIdc[mb] & 127) == refDq) && intraCoded(cur.mbType[mb])) {
                    reconstructIntra(cur, mb);
                }
            }
        }
        for (var slice : rep.slices) {
            parser.begin(slice, cur, rep);
            while (parser.next()) {
                var mb = parser.mbAddr;
                rep.mbSlice[mb] = slice;
                baseWithoutResolutionChange(rep, cur, slice, mb);
            }
        }
        checkCoverage(rep);
    }

    /**
     * Notes a layer representation whose slices leave macroblocks uncovered, as lost slices do:
     * those macroblocks, and what predicts from them, differ from the encoder's.
     *
     * @param rep the decoded layer representation
     */
    private void checkCoverage(SvcRepresentation rep) {
        var slices = rep.mbSlice;
        var count = Math.min(rep.mbCount(), slices.length);
        for (var mb = 0; mb < count; mb++) {
            if (slices[mb] == null) {
                damaged = true;
                return;
            }
        }
    }

    /**
     * Prepares a reference layer for resampling (ITU-T H.264 F.8.1.3.2, steps 1 and 2): the
     * construction of its intra samples and residuals and its deblocking for Intra_Base
     * prediction.
     *
     * @param rep the layer representation with resolution change
     * @param ref the arrays of its reference layer representation
     */
    void prepareResampling(SvcRepresentation rep, SvcLayer ref) {
        var refRep = context.representation(rep.maxRefLayerDqId);
        for (var mb = 0; mb < ref.mbCount; mb++) {
            var type = ref.mbType[mb];
            if (intraCoded(type)) {
                reconstructIntra(ref, mb);
            } else if (type == SvcMb.IBL) {
                accumulateResidual(ref, mb, false);
                addResidual(ref, mb);
                clearResidual(ref, mb);
            } else {
                accumulateResidual(ref, mb, true);
                clearSamples(ref, mb);
            }
        }
        deblocker.filter(ref, refRep, context, rep);
    }

    /**
     * Runs the base decoding of the slices of a layer representation with resolution change
     * (ITU-T H.264 F.8.1.3.2, step 5).
     *
     * @param rep the layer representation
     * @param ref the arrays of the reference layer representation
     * @param cur the arrays of the representation, initialized
     */
    void decodeWithResolutionChange(SvcRepresentation rep, SvcLayer ref, SvcLayer cur) {
        for (var slice : rep.slices) {
            parser.begin(slice, cur, rep);
            while (parser.next()) {
                var mb = parser.mbAddr;
                rep.mbSlice[mb] = slice;
                baseWithResolutionChange(rep, ref, cur, slice, mb);
            }
        }
        checkCoverage(rep);
    }

    /**
     * Returns whether a macroblock type is decoded by intra prediction in its own layer.
     *
     * @param type the macroblock type code of {@link SvcMb}
     * @return {@code true} for I_PCM, I_16x16, I_8x8 and I_4x4
     */
    private static boolean intraCoded(int type) {
        return type == SvcMb.I4X4 || type == SvcMb.I8X8 || type == SvcMb.I16X16 || type == SvcMb.IPCM;
    }

    /**
     * Runs the base decoding of one macroblock of a slice without resolution change (ITU-T H.264
     * F.8.1.5.2).
     *
     * @param rep the layer representation
     * @param cur the arrays
     * @param slice the slice holding the macroblock
     * @param mb the macroblock address
     */
    private void baseWithoutResolutionChange(SvcRepresentation rep, SvcLayer cur, SvcSlice slice, int mb) {
        var tcoeff = slice.tcoeffLevelPrediction;
        int refQpY = cur.qpY[mb];
        int refQpCb = cur.qpCb[mb];
        int refQpCr = cur.qpCr[mb];
        var refType = cur.mbType[mb];
        var refIntraBl = !slice.noInterLayerPred && refType == SvcMb.IBL;
        var residualPrediction = parser.residualPred && !SvcMb.intra(refType);
        initMacroblock(rep, cur, cur, slice, mb);
        deriveMotion(rep, cur, slice, mb);
        var type = cur.mbType[mb];
        var pps = slice.header.pps;
        var field = cur.fieldMb[mb];
        if (intraCoded(type)) {
            if (!parser.baseMode) {
                deriveIntraModes(cur, slice, mb);
            }
            if (tcoeff && parser.baseMode) {
                SvcCoefficients.scaleLevels(cur, mb, cur.qpY[mb], cur.qpCb[mb], cur.qpCr[mb], refQpY, refQpCb, refQpCr);
            }
            coefficients.refine(cur, mb, parser.baseMode, type, cur.cTrafo[mb], parser, parser.baseMode, tcoeff, pps,
                    field);
            clearResidual(cur, mb);
            clearSamples(cur, mb);
        } else if (type == SvcMb.IBL) {
            coefficients.refine(cur, mb, refIntraBl, type, cur.cTrafo[mb], parser, parser.baseMode, tcoeff, pps,
                    field);
        } else {
            if (tcoeff && residualPrediction) {
                SvcCoefficients.scaleLevels(cur, mb, cur.qpY[mb], cur.qpCb[mb], cur.qpCr[mb], refQpY, refQpCb, refQpCr);
            }
            coefficients.refine(cur, mb, residualPrediction, type, cur.cTrafo[mb], parser, parser.baseMode, tcoeff,
                    pps, field);
            if (!residualPrediction) {
                clearResidual(cur, mb);
                clearSamples(cur, mb);
            }
        }
    }

    /**
     * Runs the base decoding of one macroblock of a slice with resolution change (ITU-T H.264
     * F.8.1.5.3).
     *
     * @param rep the layer representation
     * @param ref the arrays of the reference layer representation
     * @param cur the arrays of the representation
     * @param slice the slice holding the macroblock
     * @param mb the macroblock address
     */
    private void baseWithResolutionChange(SvcRepresentation rep, SvcLayer ref, SvcLayer cur, SvcSlice slice,
                                          int mb) {
        initMacroblock(rep, ref, cur, slice, mb);
        deriveMotion(rep, cur, slice, mb);
        var type = cur.mbType[mb];
        if (type == SvcMb.IBL || (!rep.restrictedSpatialResolutionChange && !rep.mbaff && !rep.refMbaff
                && parser.baseMode)) {
            resampler.resampleIntra(rep, ref, cur, mb, slice.constrainedIntraResampling);
        }
        if (intraCoded(type)) {
            deriveIntraModes(cur, slice, mb);
        } else if (type != SvcMb.IBL && parser.residualPred) {
            resampler.resampleResidual(rep, ref, cur, mb);
        }
        coefficients.refine(cur, mb, false, type, cur.cTrafo[mb], parser, parser.baseMode,
                slice.tcoeffLevelPrediction, slice.header.pps, cur.fieldMb[mb]);
    }

    /**
     * Initializes a macroblock (ITU-T H.264 F.8.1.5.1): its slice, type, sub-macroblock types,
     * quantization parameters and transform type, and the inter-layer motion predictors.
     *
     * @param rep the layer representation
     * @param ref the arrays of the reference layer representation, the arrays of the
     * representation themselves without resolution change
     * @param cur the arrays of the representation
     * @param slice the slice holding the macroblock
     * @param mb the macroblock address
     */
    private void initMacroblock(SvcRepresentation rep, SvcLayer ref, SvcLayer cur, SvcSlice slice, int mb) {
        var resolutionChange = rep.spatialResolutionChange;
        var sliceType = slice.header.sliceType % 5;
        var predictMotion = parser.baseMode;
        for (var p = 0; p < 4; p++) {
            predictMotion |= parser.motionPred0[p] || parser.motionPred1[p];
        }
        if (predictMotion && !slice.noInterLayerPred) {
            motion.predictInterLayer(rep, ref, mb, sliceType, slice.tcoeffLevelPrediction,
                    slice.header.sps.direct8x8Inference, rep.croppingChange ? slice.refLists : null, cur.fieldMb[mb]);
        } else {
            motion.clearInterLayer();
        }
        var refType = resolutionChange ? SvcMb.NONE : ref.mbType[mb];
        var refQp = resolutionChange ? 0 : ref.qpY[mb];
        var refTrafo = resolutionChange ? SvcLayer.T_NONE : ref.cTrafo[mb];
        var cbpZero = parser.cbpLuma == 0 && parser.cbpChroma == 0;
        int type;
        if (parser.baseMode) {
            type = !resolutionChange && refType == SvcMb.IPCM && cbpZero ? SvcMb.IPCM : motion.ilType;
        } else {
            type = parser.type;
        }
        cur.mbType[mb] = (byte) type;
        for (var p = 0; p < 4; p++) {
            var sub = 0;
            if (SvcMb.eightByEight(type)) {
                sub = parser.baseMode ? motion.ilSub[p] : parser.subType[p];
            }
            cur.subMbType[mb * 4 + p] = (byte) sub;
        }
        cur.baseMode[mb] = parser.baseMode;
        var intra = SvcMb.intra(type);
        var skip = type == SvcMb.P_SKIP || type == SvcMb.B_SKIP;
        var qp = parser.qp;
        if (!resolutionChange && ((intra && parser.baseMode && cbpZero) || (skip && parser.residualPred)
                || (!intra && !skip && parser.residualPred && cbpZero))) {
            qp = refQp;
        }
        var pps = slice.header.pps;
        cur.qpY[mb] = (byte) qp;
        cur.qpCb[mb] = (byte) SvcCoefficients.chromaQp(qp, pps.chromaQpIndexOffset);
        cur.qpCr[mb] = (byte) SvcCoefficients.chromaQp(qp, pps.secondChromaQpIndexOffset);
        var tcoeff = slice.tcoeffLevelPrediction;
        var predTrafo = false;
        if (!resolutionChange) {
            predTrafo = (parser.baseMode && !tcoeff && refType == SvcMb.IBL && parser.cbpLuma == 0)
                    || (parser.baseMode && !tcoeff && refType == SvcMb.IPCM && cbpZero)
                    || (parser.baseMode && !tcoeff && (refType == SvcMb.I8X8 || refType == SvcMb.I4X4)
                    && parser.cbpLuma == 0)
                    || (parser.baseMode && tcoeff && intraCoded(type))
                    || (parser.residualPred && !SvcMb.intra(refType) && !intra && parser.cbpLuma == 0);
        }
        int trafo;
        if (type == SvcMb.IPCM) {
            trafo = SvcLayer.T_PCM;
        } else if (type == SvcMb.I16X16) {
            trafo = SvcLayer.T_16X16;
        } else if (type == SvcMb.I8X8 || parser.transform8x8) {
            trafo = SvcLayer.T_8X8;
        } else if (predTrafo) {
            trafo = refTrafo;
        } else {
            trafo = SvcLayer.T_4X4;
        }
        cur.cTrafo[mb] = (byte) trafo;
        if (!parser.baseMode && type == SvcMb.I16X16) {
            cur.ipred16x16[mb] = (byte) parser.i16Mode;
        }
    }

    /**
     * Derives the motion of a macroblock (ITU-T H.264 F.8.4.1).
     *
     * @param rep the layer representation
     * @param cur the arrays
     * @param slice the slice holding the macroblock
     * @param mb the macroblock address
     */
    private void deriveMotion(SvcRepresentation rep, SvcLayer cur, SvcSlice slice, int mb) {
        var header = slice.header;
        var baseB = rep.dqId == 0 && header.sliceType % 5 == H264SliceHeader.B;
        if (baseB && colocatedSlice != slice) {
            colocatedSlice = slice;
            colocatedPresent = colocated.begin(slice, cur, context.currentStructure(0),
                    context.dependencyPoc(0, SvcPicture.FRAME), context.dependencyPoc(0, SvcPicture.TOP),
                    context.dependencyPoc(0, SvcPicture.BOTTOM));
        }
        motion.derive(cur, mb, parser, rep.dqId == 0, baseB && colocatedPresent ? colocated : null,
                header.directSpatialMvPred, slice, header.sps.direct8x8Inference);
    }

    /**
     * Derives the intra prediction modes of a macroblock (ITU-T H.264 F.8.3.1).
     *
     * @param cur the arrays
     * @param slice the slice holding the macroblock
     * @param mb the macroblock address
     */
    private void deriveIntraModes(SvcLayer cur, SvcSlice slice, int mb) {
        var type = cur.mbType[mb];
        if (type == SvcMb.IPCM) {
            return;
        }
        var constrained = slice.header.pps.constrainedIntraPred;
        var tcoeff = slice.tcoeffLevelPrediction;
        if (type == SvcMb.I4X4) {
            for (var blk = 0; blk < 16; blk++) {
                var x = H264Tables.BLOCK_X[blk];
                var y = H264Tables.BLOCK_Y[blk];
                var neighborA = x > 0 ? (mb << 4) | blockIndex(x - 1, y) : parser.neighborBlock(x, y, true);
                var neighborB = y > 0 ? (mb << 4) | blockIndex(x, y - 1) : parser.neighborBlock(x, y, false);
                var availA = neighborA >= 0 && modeAvailable(cur, neighborA >> 4, constrained, tcoeff);
                var availB = neighborB >= 0 && modeAvailable(cur, neighborB >> 4, constrained, tcoeff);
                var dcPredicted = !availA || !availB;
                var modeA = dcPredicted ? 2 : neighborMode4x4(cur, neighborA >> 4, neighborA & 15);
                var modeB = dcPredicted ? 2 : neighborMode4x4(cur, neighborB >> 4, neighborB & 15);
                var predicted = Math.min(modeA, modeB);
                int mode;
                if (parser.prevIntraMode[blk]) {
                    mode = predicted;
                } else {
                    var rem = parser.remIntraMode[blk];
                    mode = rem < predicted ? rem : rem + 1;
                }
                cur.ipred4x4[mb * 16 + blk] = (byte) mode;
            }
        } else if (type == SvcMb.I8X8) {
            for (var b8 = 0; b8 < 4; b8++) {
                var x = b8 & 1;
                var y = b8 >> 1;
                var neighborA = x > 0 ? (mb << 2) | (b8 - 1) : parser.neighbor8x8(b8, true);
                var neighborB = y > 0 ? (mb << 2) | (b8 - 2) : parser.neighbor8x8(b8, false);
                var availA = neighborA >= 0 && modeAvailable(cur, neighborA >> 2, constrained, tcoeff);
                var availB = neighborB >= 0 && modeAvailable(cur, neighborB >> 2, constrained, tcoeff);
                var dcPredicted = !availA || !availB;
                var subA = b8 == 2 && availA && cur.mbaff && !cur.fieldMb[mb] && cur.fieldMb[neighborA >> 2] ? 3 : 1;
                var modeA = dcPredicted ? 2 : neighborMode8x8(cur, neighborA >> 2, neighborA & 3, subA);
                var modeB = dcPredicted ? 2 : neighborMode8x8(cur, neighborB >> 2, neighborB & 3, 2);
                var predicted = Math.min(modeA, modeB);
                int mode;
                if (parser.prevIntraMode[b8]) {
                    mode = predicted;
                } else {
                    var rem = parser.remIntraMode[b8];
                    mode = rem < predicted ? rem : rem + 1;
                }
                cur.ipred8x8[mb * 4 + b8] = (byte) mode;
            }
        } else if (type == SvcMb.I16X16) {
            cur.ipred16x16[mb] = (byte) parser.i16Mode;
        }
        cur.ipredChroma[mb] = (byte) parser.chromaPredMode;
    }

    /**
     * Returns the {@code luma4x4BlkIdx} of the 4x4 block at a position.
     *
     * @param x the block column
     * @param y the block row
     * @return the block index
     */
    private static int blockIndex(int x, int y) {
        return (y >> 1) * 8 + (x >> 1) * 4 + (y & 1) * 2 + (x & 1);
    }

    /**
     * Returns the {@code availableFlagN} of a neighbor in the derivation of intra prediction
     * modes (ITU-T H.264 F.8.3.1.1).
     *
     * @param cur the arrays
     * @param addr the neighbor address
     * @param constrained the {@code constrained_intra_pred_flag}
     * @param tcoeff the {@code tcoeff_level_prediction_flag}
     * @return whether the neighbor's mode is used
     */
    private static boolean modeAvailable(SvcLayer cur, int addr, boolean constrained, boolean tcoeff) {
        if (!constrained) {
            return true;
        }
        var type = cur.mbType[addr];
        return (type == SvcMb.IPCM && (tcoeff || !cur.baseMode[addr])) || type == SvcMb.I16X16
                || type == SvcMb.I8X8 || type == SvcMb.I4X4;
    }

    /**
     * Returns the Intra_4x4 mode a neighboring 4x4 block contributes.
     *
     * @param cur the arrays
     * @param addr the neighbor macroblock
     * @param blk the {@code luma4x4BlkIdx} of the neighbor block
     * @return its mode, or 2 when it has no Intra_4x4 or Intra_8x8 mode
     */
    private static int neighborMode4x4(SvcLayer cur, int addr, int blk) {
        var type = cur.mbType[addr];
        if (type == SvcMb.I4X4) {
            return cur.ipred4x4[addr * 16 + blk];
        }
        if (type == SvcMb.I8X8) {
            return cur.ipred8x8[addr * 4 + (blk >> 2)];
        }
        return 2;
    }

    /**
     * Returns the Intra_8x8 mode a neighboring 8x8 block contributes.
     *
     * @param cur the arrays
     * @param addr the neighbor macroblock
     * @param b8 the index of the neighbor 8x8 block
     * @param sub the 4x4 block of an Intra_4x4 neighbor that supplies the mode: 1 for the left
     * neighbor (3 for the lower left 8x8 block of a frame macroblock beside a field macroblock
     * pair), 2 for the upper one
     * @return its mode, or 2 when it has no Intra_4x4 or Intra_8x8 mode
     */
    private static int neighborMode8x8(SvcLayer cur, int addr, int b8, int sub) {
        var type = cur.mbType[addr];
        if (type == SvcMb.I4X4) {
            return cur.ipred4x4[addr * 16 + b8 * 4 + sub];
        }
        if (type == SvcMb.I8X8) {
            return cur.ipred8x8[addr * 4 + b8];
        }
        return 2;
    }

    /**
     * Constructs the samples of an intra macroblock with intra prediction (ITU-T H.264 F.8.3.2).
     *
     * @param layer the arrays
     * @param mb the macroblock address
     */
    private void reconstructIntra(SvcLayer layer, int mb) {
        if (layer.mbaff) {
            reconstructIntraPair(layer, mb);
            return;
        }
        var type = layer.mbType[mb];
        coefficients.residual(layer.sTCoeff, mb * SvcLayer.COEFFS, layer.cTrafo[mb], resL, resCb, resCr);
        var w = layer.widthMbs;
        var mbX = mb % w;
        var mbY = mb / w;
        var stride = layer.stride;
        var origin = mbY * 16 * stride + SvcLayer.PAD + mbX * 16;
        var chromaStride = layer.chromaStride;
        var chromaOrigin = mbY * 8 * chromaStride + SvcLayer.PAD + mbX * 8;
        if (type == SvcMb.IPCM) {
            store(layer.cY, origin, stride, resL, 16);
            store(layer.cCb, chromaOrigin, chromaStride, resCb, 8);
            store(layer.cCr, chromaOrigin, chromaStride, resCr, 8);
            return;
        }
        var sliceIdc = layer.sliceIdc[mb];
        var slice = context.slice(sliceIdc);
        var rep = context.representation(sliceIdc & 127);
        var constrained = slice.header.pps.constrainedIntraPred;
        var availA = mbX > 0 && intraAvailable(layer, rep, mb, mb - 1, constrained);
        var availB = mbY > 0 && intraAvailable(layer, rep, mb, mb - w, constrained);
        var availC = mbY > 0 && mbX < w - 1 && intraAvailable(layer, rep, mb, mb - w + 1, constrained);
        var availD = mbY > 0 && mbX > 0 && intraAvailable(layer, rep, mb, mb - w - 1, constrained);
        var plane = layer.cY;
        if (type == SvcMb.I4X4) {
            for (var blk = 0; blk < 16; blk++) {
                var x = H264Tables.BLOCK_X[blk];
                var y = H264Tables.BLOCK_Y[blk];
                var left = x > 0 || availA;
                var top = y > 0 || availB;
                var topLeft = x > 0 && y > 0 || (x == 0 && y == 0 ? availD : x == 0 ? availA : availB);
                boolean topRight;
                if (y == 0) {
                    topRight = x < 3 ? availB : availC;
                } else {
                    topRight = x < 3 && blockIndex(x + 1, y - 1) < blk;
                }
                var offset = origin + y * 4 * stride + x * 4;
                intra.predict4x4(layer.ipred4x4[mb * 16 + blk], plane, offset, stride, left, top, topLeft, topRight);
                addBlock(plane, offset, stride, resL, y * 64 + x * 4, 16, 4);
            }
        } else if (type == SvcMb.I8X8) {
            for (var b8 = 0; b8 < 4; b8++) {
                var x = b8 & 1;
                var y = b8 >> 1;
                var left = x > 0 || availA;
                var top = y > 0 || availB;
                var topLeft = x > 0 && y > 0 || (x == 0 && y == 0 ? availD : x == 0 ? availA : availB);
                var topRight = y == 0 ? (x == 0 ? availB : availC) : x == 0;
                var offset = origin + y * 8 * stride + x * 8;
                intra.predict8x8(layer.ipred8x8[mb * 4 + b8], plane, offset, stride, left, top, topLeft, topRight);
                addBlock(plane, offset, stride, resL, y * 128 + x * 8, 16, 8);
            }
        } else {
            intra.predict16x16(layer.ipred16x16[mb], plane, origin, stride, availA, availB, availD);
            addBlock(plane, origin, stride, resL, 0, 16, 16);
        }
        var chromaMode = layer.ipredChroma[mb];
        intra.predictChroma(chromaMode, layer.cCb, chromaOrigin, chromaStride, 8, availA ? 3 : 0, availB, availD);
        addBlock(layer.cCb, chromaOrigin, chromaStride, resCb, 0, 8, 8);
        intra.predictChroma(chromaMode, layer.cCr, chromaOrigin, chromaStride, 8, availA ? 3 : 0, availB, availD);
        addBlock(layer.cCr, chromaOrigin, chromaStride, resCr, 0, 8, 8);
    }

    /**
     * Constructs the samples of an intra macroblock of a frame with {@code MbaffFrameFlag} 1
     * (ITU-T H.264 F.8.3.2): the neighboring samples are gathered with the neighboring locations
     * of 6.4.12.2 into a workspace, the macroblock is predicted and constructed there and its rows
     * are then stored in the rows of the macroblock pair it occupies.
     *
     * @param layer the arrays
     * @param mb the macroblock address
     */
    private void reconstructIntraPair(SvcLayer layer, int mb) {
        var type = layer.mbType[mb];
        coefficients.residual(layer.sTCoeff, mb * SvcLayer.COEFFS, layer.cTrafo[mb], resL, resCb, resCr);
        var x0 = layer.mbX(mb) * 16;
        var step = layer.rowStep(mb);
        var origin = layer.lumaY(mb) * layer.stride + SvcLayer.PAD + x0;
        var chromaOrigin = layer.chromaY(mb) * layer.chromaStride + SvcLayer.PAD + x0 / 2;
        if (type == SvcMb.IPCM) {
            store(layer.cY, origin, layer.stride * step, resL, 16);
            store(layer.cCb, chromaOrigin, layer.chromaStride * step, resCb, 8);
            store(layer.cCr, chromaOrigin, layer.chromaStride * step, resCr, 8);
            return;
        }
        var sliceIdc = layer.sliceIdc[mb];
        var slice = context.slice(sliceIdc);
        var rep = context.representation(sliceIdc & 127);
        var constrained = slice.header.pps.constrainedIntraPred;
        SvcNeighbors.candidates(layer, mb, candidates);
        for (var i = 0; i < 4; i++) {
            if (candidates[i] >= 0 && !sliceAvailable(layer, rep, mb, candidates[i])) {
                candidates[i] = -1;
            }
        }
        intraNeighbors.setMacroblock(layer, mb, candidates[0], candidates[1], candidates[2], candidates[3]);
        for (var y = 0; y < 16; y++) {
            lumaAvailable[y] = gather(layer, rep, mb, constrained, -1, y, 0, lumaWork, (y + 1) * WORK);
        }
        for (var x = 0; x < 24; x++) {
            lumaAvailable[16 + x] = gather(layer, rep, mb, constrained, x, -1, 0, lumaWork, 1 + x);
        }
        lumaAvailable[40] = gather(layer, rep, mb, constrained, -1, -1, 0, lumaWork, 0);
        var work = lumaWork;
        if (type == SvcMb.I4X4) {
            for (var blk = 0; blk < 16; blk++) {
                var x = H264Tables.BLOCK_X[blk];
                var y = H264Tables.BLOCK_Y[blk];
                var left = x > 0 || allAvailable(lumaAvailable, 4 * y, 4);
                var top = y > 0 || allAvailable(lumaAvailable, 16 + 4 * x, 4);
                boolean topLeft;
                if (x > 0 && y > 0) {
                    topLeft = true;
                } else if (x == 0 && y == 0) {
                    topLeft = lumaAvailable[40];
                } else if (x == 0) {
                    topLeft = lumaAvailable[4 * y - 1];
                } else {
                    topLeft = lumaAvailable[16 + 4 * x - 1];
                }
                boolean topRight;
                if (y == 0) {
                    topRight = allAvailable(lumaAvailable, 16 + 4 * x + 4, 4);
                } else {
                    topRight = x < 3 && blockIndex(x + 1, y - 1) < blk;
                }
                var offset = (1 + 4 * y) * WORK + 1 + 4 * x;
                intra.predict4x4(layer.ipred4x4[mb * 16 + blk], work, offset, WORK, left, top, topLeft, topRight);
                addBlock(work, offset, WORK, resL, y * 64 + x * 4, 16, 4);
            }
        } else if (type == SvcMb.I8X8) {
            for (var b8 = 0; b8 < 4; b8++) {
                var x = b8 & 1;
                var y = b8 >> 1;
                var left = x > 0 || allAvailable(lumaAvailable, 8 * y, 8);
                var top = y > 0 || allAvailable(lumaAvailable, 16 + 8 * x, 8);
                boolean topLeft;
                if (x > 0 && y > 0) {
                    topLeft = true;
                } else if (x == 0 && y == 0) {
                    topLeft = lumaAvailable[40];
                } else if (x == 0) {
                    topLeft = lumaAvailable[8 * y - 1];
                } else {
                    topLeft = lumaAvailable[16 + 8 * x - 1];
                }
                var topRight = y == 0 ? allAvailable(lumaAvailable, 16 + 8 * x + 8, 8) : x == 0;
                var offset = (1 + 8 * y) * WORK + 1 + 8 * x;
                intra.predict8x8(layer.ipred8x8[mb * 4 + b8], work, offset, WORK, left, top, topLeft, topRight);
                addBlock(work, offset, WORK, resL, y * 128 + x * 8, 16, 8);
            }
        } else {
            intra.predict16x16(layer.ipred16x16[mb], work, WORK + 1, WORK, allAvailable(lumaAvailable, 0, 16),
                    allAvailable(lumaAvailable, 16, 16), lumaAvailable[40]);
            addBlock(work, WORK + 1, WORK, resL, 0, 16, 16);
        }
        for (var y = 0; y < 16; y++) {
            System.arraycopy(work, (y + 1) * WORK + 1, layer.cY, origin + y * step * layer.stride, 16);
        }
        for (var plane = 0; plane < 2; plane++) {
            var chromaWork = plane == 0 ? cbWork : crWork;
            for (var y = 0; y < 8; y++) {
                chromaAvailable[y] = gather(layer, rep, mb, constrained, -1, y, plane + 1, chromaWork,
                        (y + 1) * CHROMA_WORK);
            }
            for (var x = 0; x < 8; x++) {
                chromaAvailable[8 + x] = gather(layer, rep, mb, constrained, x, -1, plane + 1, chromaWork, 1 + x);
            }
            chromaAvailable[16] = gather(layer, rep, mb, constrained, -1, -1, plane + 1, chromaWork, 0);
            var leftBlocks = (allAvailable(chromaAvailable, 0, 4) ? 1 : 0) | (allAvailable(chromaAvailable, 4, 4) ? 2 : 0);
            intra.predictChroma(layer.ipredChroma[mb], chromaWork, CHROMA_WORK + 1, CHROMA_WORK, 8, leftBlocks,
                    allAvailable(chromaAvailable, 8, 8), chromaAvailable[16]);
            addBlock(chromaWork, CHROMA_WORK + 1, CHROMA_WORK, plane == 0 ? resCb : resCr, 0, 8, 8);
            var target = plane == 0 ? layer.cCb : layer.cCr;
            for (var y = 0; y < 8; y++) {
                System.arraycopy(chromaWork, (y + 1) * CHROMA_WORK + 1, target,
                        chromaOrigin + y * step * layer.chromaStride, 8);
            }
        }
    }

    /**
     * Copies one neighboring sample of a macroblock of a frame with {@code MbaffFrameFlag} 1 into
     * a workspace and returns whether it is available for intra prediction.
     *
     * @param layer the arrays
     * @param rep the layer representation that coded the current macroblock
     * @param mb the current macroblock address
     * @param constrained the {@code constrained_intra_pred_flag} of the current macroblock
     * @param xN the horizontal location relative to the macroblock, in samples of the component
     * @param yN the vertical location relative to the macroblock, in samples of the component
     * @param component 0 for luma, 1 for Cb, 2 for Cr
     * @param work the workspace
     * @param at the index of the sample in the workspace
     * @return whether the sample is available
     */
    private boolean gather(SvcLayer layer, SvcRepresentation rep, int mb, boolean constrained, int xN, int yN,
                           int component, byte[] work, int at) {
        var size = component == 0 ? 16 : 8;
        var n = intraNeighbors.locate(xN, yN, size, size);
        if (n < 0 || (n != mb && !intraAvailable(layer, rep, mb, n, constrained))) {
            work[at] = 0;
            return false;
        }
        var xW = intraNeighbors.xW;
        var yW = intraNeighbors.yW;
        if (size == 16) {
            work[at] = layer.cY[(layer.lumaY(n) + yW * layer.rowStep(n)) * layer.stride + SvcLayer.PAD
                    + layer.mbX(n) * 16 + xW];
        } else {
            var plane = component == 2 ? layer.cCr : layer.cCb;
            work[at] = plane[(layer.chromaY(n) + yW * layer.rowStep(n)) * layer.chromaStride + SvcLayer.PAD
                    + layer.mbX(n) * 8 + xW];
        }
        return true;
    }

    /**
     * Returns whether a run of neighboring samples is available.
     *
     * @param available the availability flags
     * @param first the first flag of the run
     * @param count the number of flags
     * @return {@code true} when every flag of the run is set
     */
    private static boolean allAvailable(boolean[] available, int first, int count) {
        for (var i = first; i < first + count; i++) {
            if (!available[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns whether a neighboring macroblock belongs to the slice of the current macroblock for
     * the intra prediction (ITU-T H.264 F.8.3.2, modification b).
     *
     * @param layer the arrays
     * @param rep the layer representation that coded the current macroblock
     * @param mb the current macroblock address
     * @param neighbor the neighbor address
     * @return {@code true} when the neighbor is not in another slice
     */
    private static boolean sliceAvailable(SvcLayer layer, SvcRepresentation rep, int mb, int neighbor) {
        var sliceIdc = layer.sliceIdc[mb];
        var map = rep != null ? rep.groupMap : null;
        if (map != null && map[neighbor] != map[mb]) {
            return false;
        }
        return neighbor >= (sliceIdc >> 7) * (layer.mbaff ? 2 : 1) && layer.sliceIdc[neighbor] >= 0;
    }

    /**
     * Returns whether the samples of a neighboring macroblock are available for the intra
     * prediction of a macroblock (ITU-T H.264 F.8.3.2, modifications b, f and g).
     *
     * @param layer the arrays
     * @param rep the layer representation that coded the current macroblock
     * @param mb the current macroblock address
     * @param neighbor the neighbor address
     * @param constrained the {@code constrained_intra_pred_flag} of the current macroblock
     * @return {@code true} when the neighbor's samples may be used
     */
    private boolean intraAvailable(SvcLayer layer, SvcRepresentation rep, int mb, int neighbor, boolean constrained) {
        if (!sliceAvailable(layer, rep, mb, neighbor)) {
            return false;
        }
        if (!constrained) {
            return true;
        }
        var sliceIdc = layer.sliceIdc[mb];
        var type = layer.mbType[neighbor];
        var neighborIdc = layer.sliceIdc[neighbor];
        if (neighborIdc != sliceIdc || type == SvcMb.I16X16 || type == SvcMb.I8X8 || type == SvcMb.I4X4) {
            return true;
        }
        if (type == SvcMb.IPCM) {
            return !layer.baseMode[neighbor] || context.slice(neighborIdc).tcoeffLevelPrediction;
        }
        return false;
    }

    /**
     * Adds a residual block to the prediction in a plane, clipping to the sample range.
     *
     * @param plane the plane holding the prediction
     * @param offset the index of the block's top-left sample
     * @param stride the plane's row stride
     * @param residual the residual samples
     * @param at the index of the block's top-left residual sample
     * @param residualStride the residual row stride
     * @param size the block size
     */
    private static void addBlock(byte[] plane, int offset, int stride, int[] residual, int at, int residualStride,
                                 int size) {
        for (var y = 0; y < size; y++) {
            for (var x = 0; x < size; x++) {
                var value = (plane[offset + y * stride + x] & 0xFF) + residual[at + y * residualStride + x];
                plane[offset + y * stride + x] = (byte) Math.clamp(value, 0, 255);
            }
        }
    }

    /**
     * Stores sample values into a plane, clipping to the sample range.
     *
     * @param plane the destination sample plane
     * @param offset the index of the block's top-left sample
     * @param stride the distance between the block's rows in the plane
     * @param values the samples in raster order
     * @param size the block size
     */
    private static void store(byte[] plane, int offset, int stride, int[] values, int size) {
        for (var y = 0; y < size; y++) {
            for (var x = 0; x < size; x++) {
                plane[offset + y * stride + x] = (byte) Math.clamp(values[y * size + x], 0, 255);
            }
        }
    }

    /**
     * Constructs the residual of a macroblock from its scaled coefficients and stores it, or
     * accumulates it onto the stored residual (ITU-T H.264 F.8.5.3).
     *
     * @param layer the arrays
     * @param mb the macroblock address
     * @param accumulate the {@code accumulationFlag}
     */
    private void accumulateResidual(SvcLayer layer, int mb, boolean accumulate) {
        coefficients.residual(layer.sTCoeff, mb * SvcLayer.COEFFS, layer.cTrafo[mb], resL, resCb, resCr);
        var x0 = layer.mbX(mb) * 16;
        var step = layer.rowStep(mb);
        accumulatePlane(layer.rY, layer.lumaY(mb) * layer.width + x0, layer.width * step, resL, 16, accumulate);
        var chromaAt = layer.chromaY(mb) * layer.chromaWidth + x0 / 2;
        accumulatePlane(layer.rCb, chromaAt, layer.chromaWidth * step, resCb, 8, accumulate);
        accumulatePlane(layer.rCr, chromaAt, layer.chromaWidth * step, resCr, 8, accumulate);
    }

    /**
     * Stores or accumulates one residual block (ITU-T H.264 equations F-173 and F-176).
     *
     * @param plane the residual plane
     * @param offset the index of the block's top-left sample
     * @param stride the distance between the block's rows in the plane
     * @param values the constructed residual
     * @param size the block size
     * @param accumulate whether the block is added to the stored residual
     */
    private static void accumulatePlane(short[] plane, int offset, int stride, int[] values, int size,
                                        boolean accumulate) {
        for (var y = 0; y < size; y++) {
            for (var x = 0; x < size; x++) {
                var at = offset + y * stride + x;
                var value = values[y * size + x];
                if (accumulate) {
                    value = Math.clamp(value + plane[at], -255, 255);
                }
                plane[at] = (short) value;
            }
        }
    }

    /**
     * Adds the stored residual of a macroblock to its constructed samples (ITU-T H.264 F.8.5.4).
     *
     * @param layer the arrays
     * @param mb the macroblock address
     */
    private static void addResidual(SvcLayer layer, int mb) {
        var x0 = layer.mbX(mb) * 16;
        var y0 = layer.lumaY(mb);
        var step = layer.rowStep(mb);
        addPlane(layer.cY, y0 * layer.stride + SvcLayer.PAD + x0, layer.stride * step, layer.rY, y0 * layer.width + x0,
                layer.width * step, 16);
        var cy = layer.chromaY(mb);
        var chromaSamples = cy * layer.chromaStride + SvcLayer.PAD + x0 / 2;
        var chromaResidual = cy * layer.chromaWidth + x0 / 2;
        addPlane(layer.cCb, chromaSamples, layer.chromaStride * step, layer.rCb, chromaResidual,
                layer.chromaWidth * step, 8);
        addPlane(layer.cCr, chromaSamples, layer.chromaStride * step, layer.rCr, chromaResidual,
                layer.chromaWidth * step, 8);
    }

    /**
     * Adds a residual block to a block of samples with clipping.
     *
     * @param samples the sample plane
     * @param offset the index of the block's top-left sample
     * @param stride the distance between the block's rows in the sample plane
     * @param residual the residual plane
     * @param at the index of the block's top-left residual sample
     * @param residualStride the distance between the block's rows in the residual plane
     * @param size the block size
     */
    private static void addPlane(byte[] samples, int offset, int stride, short[] residual, int at, int residualStride,
                                 int size) {
        for (var y = 0; y < size; y++) {
            for (var x = 0; x < size; x++) {
                var value = (samples[offset + y * stride + x] & 0xFF) + residual[at + y * residualStride + x];
                samples[offset + y * stride + x] = (byte) Math.clamp(value, 0, 255);
            }
        }
    }

    /**
     * Sets the stored residual of a macroblock to 0 (ITU-T H.264 F.8.5.5).
     *
     * @param layer the arrays
     * @param mb the macroblock address
     */
    private static void clearResidual(SvcLayer layer, int mb) {
        var x0 = layer.mbX(mb) * 16;
        var y0 = layer.lumaY(mb);
        var step = layer.rowStep(mb);
        for (var y = 0; y < 16; y++) {
            var at = (y0 + y * step) * layer.width + x0;
            Arrays.fill(layer.rY, at, at + 16, (short) 0);
        }
        var cy = layer.chromaY(mb);
        for (var y = 0; y < 8; y++) {
            var at = (cy + y * step) * layer.chromaWidth + x0 / 2;
            Arrays.fill(layer.rCb, at, at + 8, (short) 0);
            Arrays.fill(layer.rCr, at, at + 8, (short) 0);
        }
    }

    /**
     * Sets the constructed samples of a macroblock to 0 (ITU-T H.264 F.8.5.5).
     *
     * @param layer the arrays
     * @param mb the macroblock address
     */
    private static void clearSamples(SvcLayer layer, int mb) {
        var x0 = layer.mbX(mb) * 16;
        var y0 = layer.lumaY(mb);
        var step = layer.rowStep(mb);
        for (var y = 0; y < 16; y++) {
            var at = (y0 + y * step) * layer.stride + SvcLayer.PAD + x0;
            Arrays.fill(layer.cY, at, at + 16, (byte) 0);
        }
        var cy = layer.chromaY(mb);
        for (var y = 0; y < 8; y++) {
            var at = (cy + y * step) * layer.chromaStride + SvcLayer.PAD + x0 / 2;
            Arrays.fill(layer.cCb, at, at + 8, (byte) 0);
            Arrays.fill(layer.cCr, at, at + 8, (byte) 0);
        }
    }

    /**
     * Runs the target layer representation decoding (ITU-T H.264 F.8.1.3.3, steps 2 and 3) on a
     * set of arrays: the construction of every macroblock and the deblocking.
     *
     * @param rep the target layer representation, with DQId {@code currDQId}
     * @param vars the arrays to construct, a copy when the arrays are decoded further
     * @param ref the arrays of the reference layer representation with resolution change, or
     * {@code null}
     */
    void decodeTarget(SvcRepresentation rep, SvcLayer vars, SvcLayer ref) {
        var currDq = rep.dqId;
        var targetQ = currDq & 15;
        for (var mb = 0; mb < vars.mbCount; mb++) {
            var sliceIdc = vars.sliceIdc[mb];
            if (sliceIdc < 0) {
                continue;
            }
            var own = (sliceIdc & 127) == currDq;
            if (rep.maxTcoeffLevelPred && !own) {
                var qp = vars.qpY[mb];
                var cQpCb = SvcCoefficients.chromaQp(qp, rep.pps.chromaQpIndexOffset);
                var cQpCr = SvcCoefficients.chromaQp(qp, rep.pps.secondChromaQpIndexOffset);
                SvcCoefficients.scaleLevels(vars, mb, qp, cQpCb, cQpCr, qp, vars.qpCb[mb], vars.qpCr[mb]);
                vars.qpCb[mb] = (byte) cQpCb;
                vars.qpCr[mb] = (byte) cQpCr;
                coefficients.refine(vars, mb, true, vars.mbType[mb], vars.cTrafo[mb], null, true, true, rep.pps,
                        vars.fieldMb[mb]);
            }
            var type = vars.mbType[mb];
            if (intraCoded(type)) {
                if (own || rep.maxTcoeffLevelPred) {
                    reconstructIntra(vars, mb);
                }
            } else if (type == SvcMb.IBL) {
                accumulateResidual(vars, mb, false);
                addResidual(vars, mb);
            } else if (type != SvcMb.NONE) {
                predictInter(rep, vars, ref, mb, targetQ);
                accumulateResidual(vars, mb, true);
                addResidual(vars, mb);
            }
        }
        deblocker.filter(vars, rep, context, null);
    }

    /**
     * Forms the inter prediction of a macroblock of the target representation and stores it as
     * the macroblock's constructed samples (ITU-T H.264 F.8.4.2).
     *
     * @param rep the target layer representation
     * @param vars the arrays
     * @param ref the arrays of the reference layer representation with resolution change, or
     * {@code null}
     * @param mb the macroblock address
     * @param targetQ the {@code quality_id} of the target
     */
    private void predictInter(SvcRepresentation rep, SvcLayer vars, SvcLayer ref, int mb, int targetQ) {
        var sliceIdc = vars.sliceIdc[mb];
        var listSlice = context.listSlice(sliceIdc);
        var weightSlice = context.slice(sliceIdc);
        var header = weightSlice.header;
        if (weightSlice.basePredWeightTable) {
            var base = context.weightSlice(sliceIdc & 127);
            if (base != null) {
                header = base.header;
            }
        }
        var pps = weightSlice.header.pps;
        var b = listSlice.header.sliceType % 5 == H264SliceHeader.B;
        var fieldInFrame = vars.mbaff && vars.fieldMb[mb];
        int parity;
        int x0 = vars.mbX(mb) * 16;
        int y0;
        if (vars.fieldPic) {
            parity = vars.bottomField ? SvcPicture.BOTTOM : SvcPicture.TOP;
            y0 = (mb / vars.widthMbs) * 16;
        } else if (fieldInFrame) {
            parity = (mb & 1) == 0 ? SvcPicture.TOP : SvcPicture.BOTTOM;
            y0 = ((mb >> 1) / vars.widthMbs) * 16;
        } else {
            parity = SvcPicture.FRAME;
            y0 = vars.lumaY(mb);
        }
        for (var q = 0; q < 4; q++) {
            var ref0 = vars.refIdx0[mb * 4 + q];
            var ref1 = vars.refIdx1[mb * 4 + q];
            var qx = (q & 1) * 8;
            var qy = (q >> 1) * 8;
            var uniform = true;
            var first = mb * 32 + ((qy >> 2) * 4 + (qx >> 2)) * 2;
            for (var s = 1; s < 4 && uniform; s++) {
                var at = mb * 32 + (((qy >> 2) + (s >> 1)) * 4 + (qx >> 2) + (s & 1)) * 2;
                uniform = vars.mv0[at] == vars.mv0[first] && vars.mv0[at + 1] == vars.mv0[first + 1]
                        && vars.mv1[at] == vars.mv1[first] && vars.mv1[at + 1] == vars.mv1[first + 1];
            }
            var size = uniform ? 8 : 4;
            for (var by = qy; by < qy + 8; by += size) {
                for (var bx = qx; bx < qx + 8; bx += size) {
                    var at = mb * 32 + ((by >> 2) * 4 + (bx >> 2)) * 2;
                    predictBlock(listSlice, header, pps, b, ref0, ref1, vars.mv0[at], vars.mv0[at + 1], vars.mv1[at],
                            vars.mv1[at + 1], x0 + bx, y0 + by, bx, by, size, mb, fieldInFrame, parity);
                }
            }
        }
        if (targetQ == 0 && vars.baseMode[mb] && ref != null && !rep.restrictedSpatialResolutionChange
                && !rep.mbaff && !rep.refMbaff) {
            combineIntraInter(rep, vars, ref, mb);
        }
        var step = vars.rowStep(mb);
        var origin = vars.lumaY(mb) * vars.stride + SvcLayer.PAD + x0;
        for (var y = 0; y < 16; y++) {
            System.arraycopy(predL, y * 16, vars.cY, origin + y * step * vars.stride, 16);
        }
        var chromaOrigin = vars.chromaY(mb) * vars.chromaStride + SvcLayer.PAD + x0 / 2;
        for (var y = 0; y < 8; y++) {
            System.arraycopy(predCb, y * 8, vars.cCb, chromaOrigin + y * step * vars.chromaStride, 8);
            System.arraycopy(predCr, y * 8, vars.cCr, chromaOrigin + y * step * vars.chromaStride, 8);
        }
    }

    /**
     * Replaces the inter prediction and the residual of the samples of a macroblock whose
     * reference layer samples are intra-coded with the resampled intra samples and zero (ITU-T
     * H.264 F.8.4.2.2).
     *
     * @param rep the target layer representation
     * @param vars the arrays, whose constructed samples hold the resampled intra samples
     * @param ref the arrays of the reference layer representation
     * @param mb the macroblock address
     */
    private void combineIntraInter(SvcRepresentation rep, SvcLayer vars, SvcLayer ref, int mb) {
        var w = vars.widthMbs;
        var x0 = (mb % w) * 16;
        var y0 = (mb / w) * 16;
        var fieldMb = vars.fieldMb[mb];
        for (var y = 0; y < 16; y++) {
            for (var x = 0; x < 16; x++) {
                var refMb = rep.referenceLuma(mb, x, y, fieldMb, ref) >> 8;
                if (!SvcMb.intra(ref.mbType[refMb])) {
                    continue;
                }
                predL[y * 16 + x] = vars.cY[(y0 + y) * vars.stride + SvcLayer.PAD + x0 + x];
                vars.rY[(y0 + y) * vars.width + x0 + x] = 0;
                if ((x & 1) == 0 && (y & 1) == 0) {
                    var cx = x / 2;
                    var cy = y / 2;
                    var samples = (y0 / 2 + cy) * vars.chromaStride + SvcLayer.PAD + x0 / 2 + cx;
                    predCb[cy * 8 + cx] = vars.cCb[samples];
                    predCr[cy * 8 + cx] = vars.cCr[samples];
                    var residual = (y0 / 2 + cy) * vars.chromaWidth + x0 / 2 + cx;
                    vars.rCb[residual] = 0;
                    vars.rCr[residual] = 0;
                }
            }
        }
    }

    /**
     * Predicts one block of a macroblock into {@link #predL}, {@link #predCb} and
     * {@link #predCr} (ITU-T H.264 8.4.2 with the weights of F.8.4.2.1).
     *
     * @param slice the slice whose reference picture lists the macroblock predicts from
     * @param header the slice header holding the explicit weights
     * @param pps the picture parameter set selecting the weighted prediction mode
     * @param b whether the slice is a B or EB slice
     * @param ref0 the list 0 reference index, -1 when unused
     * @param ref1 the list 1 reference index, -1 when unused
     * @param mv0x the list 0 horizontal motion vector component
     * @param mv0y the list 0 vertical motion vector component
     * @param mv1x the list 1 horizontal motion vector component
     * @param mv1y the list 1 vertical motion vector component
     * @param px the block's horizontal luma position in the reference picture
     * @param py the block's vertical luma position in the reference picture, in field rows for a
     * field macroblock
     * @param bx the block's horizontal luma position in the macroblock
     * @param by the block's vertical luma position in the macroblock
     * @param size the block size, 4 or 8
     * @param mb the macroblock address
     * @param fieldInFrame whether the macroblock is a field macroblock of a frame
     * @param parity the parity of the current field or field macroblock, or
     * {@link SvcPicture#FRAME}
     */
    private void predictBlock(SvcSlice slice, H264SliceHeader header, H264Pps pps, boolean b, int ref0, int ref1,
                              int mv0x, int mv0y, int mv1x, int mv1y, int px, int py, int bx, int by, int size, int mb,
                              boolean fieldInFrame, int parity) {
        var lumaAt = by * 16 + bx;
        var chromaAt = (by / 2) * 8 + bx / 2;
        var chromaSize = size / 2;
        var use0 = ref0 >= 0;
        var use1 = ref1 >= 0;
        if (use0) {
            var structure = referenceStructure(slice, 0, ref0, fieldInFrame, parity);
            motionCompensate(frameOf(slice, 0, ref0, fieldInFrame), structure, parity, px, py, mv0x, mv0y, size,
                    predL, predCb, predCr, lumaAt, chromaAt);
        }
        if (use1) {
            var structure = referenceStructure(slice, 1, ref1, fieldInFrame, parity);
            var frame = frameOf(slice, 1, ref1, fieldInFrame);
            if (use0) {
                motionCompensate(frame, structure, parity, px, py, mv1x, mv1y, size, tempL, tempCb, tempCr, lumaAt,
                        chromaAt);
            } else {
                motionCompensate(frame, structure, parity, px, py, mv1x, mv1y, size, predL, predCb, predCr, lumaAt,
                        chromaAt);
            }
        }
        var explicit = (!b && pps.weightedPred) || (b && pps.weightedBipredIdc == 1);
        var implicit = b && pps.weightedBipredIdc == 2 && use0 && use1;
        var wp0 = fieldInFrame ? ref0 >> 1 : ref0;
        var wp1 = fieldInFrame ? ref1 >> 1 : ref1;
        if (use0 && use1) {
            if (implicit) {
                var weights = implicitWeights(slice, ref0, ref1, mb, fieldInFrame, parity);
                var w0 = weights >> 16;
                var w1 = (short) weights;
                dsp.biWeight(predL, lumaAt, 16, tempL, lumaAt, 16, size, size, 5, w0, w1, 0);
                dsp.biWeight(predCb, chromaAt, 8, tempCb, chromaAt, 8, chromaSize, chromaSize, 5, w0, w1, 0);
                dsp.biWeight(predCr, chromaAt, 8, tempCr, chromaAt, 8, chromaSize, chromaSize, 5, w0, w1, 0);
            } else if (explicit) {
                dsp.biWeight(predL, lumaAt, 16, tempL, lumaAt, 16, size, size, header.lumaLog2WeightDenom,
                        header.lumaWeight[0][wp0], header.lumaWeight[1][wp1],
                        (header.lumaOffset[0][wp0] + header.lumaOffset[1][wp1] + 1) >> 1);
                for (var c = 0; c < 2; c++) {
                    dsp.biWeight(c == 0 ? predCb : predCr, chromaAt, 8, c == 0 ? tempCb : tempCr, chromaAt, 8,
                            chromaSize, chromaSize, header.chromaLog2WeightDenom,
                            header.chromaWeight[0][wp0 * 2 + c], header.chromaWeight[1][wp1 * 2 + c],
                            (header.chromaOffset[0][wp0 * 2 + c] + header.chromaOffset[1][wp1 * 2 + c] + 1) >> 1);
                }
            } else {
                dsp.average(predL, lumaAt, 16, tempL, lumaAt, 16, size, size);
                dsp.average(predCb, chromaAt, 8, tempCb, chromaAt, 8, chromaSize, chromaSize);
                dsp.average(predCr, chromaAt, 8, tempCr, chromaAt, 8, chromaSize, chromaSize);
            }
        } else if (explicit && (use0 || use1)) {
            var list = use0 ? 0 : 1;
            var refIdx = use0 ? wp0 : wp1;
            dsp.weight(predL, lumaAt, 16, size, size, header.lumaLog2WeightDenom, header.lumaWeight[list][refIdx],
                    header.lumaOffset[list][refIdx]);
            for (var c = 0; c < 2; c++) {
                dsp.weight(c == 0 ? predCb : predCr, chromaAt, 8, chromaSize, chromaSize,
                        header.chromaLog2WeightDenom, header.chromaWeight[list][refIdx * 2 + c],
                        header.chromaOffset[list][refIdx * 2 + c]);
            }
        }
    }

    /**
     * Derives the implicit bi-predictive weights of a pair of references (ITU-T H.264 8.4.3).
     *
     * @param slice the slice holding the reference picture lists
     * @param ref0 the list 0 reference index
     * @param ref1 the list 1 reference index
     * @param mb the macroblock address
     * @param fieldInFrame whether the macroblock is a field macroblock of a frame
     * @param parity the parity of the current field or field macroblock, or
     * {@link SvcPicture#FRAME}
     * @return {@code (w0 << 16) | (w1 & 0xFFFF)}
     */
    private int implicitWeights(SvcSlice slice, int ref0, int ref1, int mb, boolean fieldInFrame, int parity) {
        var poc0 = SvcColocated.referencePoc(slice, 0, ref0, mb, fieldInFrame);
        var poc1 = SvcColocated.referencePoc(slice, 1, ref1, mb, fieldInFrame);
        if (poc0 == Integer.MIN_VALUE || poc1 == Integer.MIN_VALUE
                || SvcColocated.longTerm(slice, 0, ref0, mb, fieldInFrame)
                || SvcColocated.longTerm(slice, 1, ref1, mb, fieldInFrame)) {
            return (32 << 16) | 32;
        }
        var diff = poc1 - poc0;
        if (diff == 0) {
            return (32 << 16) | 32;
        }
        var tb = Math.clamp(context.currentPoc(parity) - poc0, -128, 127);
        var td = Math.clamp(diff, -128, 127);
        var tx = (16384 + Math.abs(td / 2)) / td;
        var scale = Math.clamp((tb * tx + 32) >> 6, -1024, 1023);
        if ((scale >> 2) < -64 || (scale >> 2) > 128) {
            return (32 << 16) | 32;
        }
        return ((64 - (scale >> 2)) << 16) | ((scale >> 2) & 0xFFFF);
    }

    /**
     * Returns the structure of the reference picture a reference index of a macroblock names.
     *
     * @param slice the slice holding the reference picture lists
     * @param list the list index
     * @param refIdx the reference index
     * @param fieldInFrame whether the macroblock is a field macroblock of a frame, whose indices
     * name the fields of the frames of the lists
     * @param parity the parity of the current field or field macroblock, or
     * {@link SvcPicture#FRAME}
     * @return {@link SvcPicture#FRAME}, {@link SvcPicture#TOP} or {@link SvcPicture#BOTTOM}
     */
    private static int referenceStructure(SvcSlice slice, int list, int refIdx, boolean fieldInFrame, int parity) {
        if (fieldInFrame) {
            return (refIdx & 1) == 0 ? parity : SvcPicture.FRAME - parity;
        }
        if (parity == SvcPicture.FRAME || slice.refStructures == null || refIdx >= slice.refStructures[list].length) {
            return parity;
        }
        return slice.refStructures[list][refIdx];
    }

    /**
     * Returns the samples of a reference picture.
     *
     * @param slice the slice holding the reference picture lists
     * @param list the reference picture list index, 0 or 1
     * @param refIdx the reference index
     * @param fieldInFrame whether the macroblock is a field macroblock of a frame
     * @return the frame, a gray frame when the reference is missing
     */
    private DecoderFrame frameOf(SvcSlice slice, int list, int refIdx, boolean fieldInFrame) {
        var index = fieldInFrame ? refIdx >> 1 : refIdx;
        var lists = slice.refLists;
        if (lists != null && index < lists[list].length) {
            var picture = lists[list][index];
            if (picture != null && picture.frame != null) {
                damaged |= picture.damaged;
                return picture.frame;
            }
        }
        damaged = true;
        return context.missingFrame();
    }

    /**
     * Interpolates one block from a reference frame or field (ITU-T H.264 8.4.2.2), reading
     * through an edge-emulation block where the motion vector reaches beyond the picture's border.
     *
     * @param frame the reference frame
     * @param structure the picture of the frame predicted from: {@link SvcPicture#FRAME}, or the
     * field
     * @param parity the parity of the current field or field macroblock, or
     * {@link SvcPicture#FRAME}
     * @param px the block's horizontal luma position in the reference picture
     * @param py the block's vertical luma position in the reference picture
     * @param mvx the horizontal motion vector component
     * @param mvy the vertical motion vector component
     * @param size the luma block size
     * @param luma receives the luma prediction, stride 16
     * @param cb receives the Cb prediction, stride 8
     * @param cr receives the Cr prediction, stride 8
     * @param lumaAt the index of the block in {@code luma}
     * @param chromaAt the index of the block in {@code cb} and {@code cr}
     */
    private void motionCompensate(DecoderFrame frame, int structure, int parity, int px, int py, int mvx, int mvy,
                                  int size, byte[] luma, byte[] cb, byte[] cr, int lumaAt, int chromaAt) {
        var fieldRef = structure != SvcPicture.FRAME;
        var rowFactor = fieldRef ? 2 : 1;
        var bottomRow = structure == SvcPicture.BOTTOM ? 1 : 0;
        var width = frame.mbWidth * 16;
        var height = frame.mbHeight * 16 / rowFactor;
        var lumaStride = frame.lumaStride * rowFactor;
        var lumaOrigin = frame.lumaOrigin + bottomRow * frame.lumaStride;
        var ix = px + (mvx >> 2);
        var iy = py + (mvy >> 2);
        var pad = DecoderFrame.LUMA_PAD;
        byte[] src;
        int srcOffset;
        int srcStride;
        var verticalInside = fieldRef ? iy - 2 >= 0 && iy + size + 3 <= height
                : iy - 2 >= -pad && iy + size + 3 <= height + pad;
        if (ix - 2 >= -pad && ix + size + 3 <= width + pad && verticalInside) {
            src = frame.luma;
            srcStride = lumaStride;
            srcOffset = lumaOrigin + iy * srcStride + ix;
        } else {
            for (var y = 0; y < size + 5; y++) {
                var sy = Math.clamp(iy - 2 + y, 0, height - 1);
                for (var x = 0; x < size + 5; x++) {
                    var sx = Math.clamp(ix - 2 + x, 0, width - 1);
                    edge[y * 24 + x] = frame.luma[lumaOrigin + sy * lumaStride + sx];
                }
            }
            src = edge;
            srcStride = 24;
            srcOffset = 2 * 24 + 2;
        }
        dsp.lumaMc(src, srcOffset, srcStride, luma, lumaAt, 16, size, size, mvx & 3, mvy & 3, sums, mcScratch);
        var mvcy = mvy;
        if (fieldRef && parity != SvcPicture.FRAME && structure != parity) {
            mvcy += structure == SvcPicture.TOP ? 2 : -2;
        }
        var chromaSize = size / 2;
        var chromaWidth = width / 2;
        var chromaHeight = height / 2;
        var chromaStride = frame.chromaStride * rowFactor;
        var chromaOrigin = frame.chromaOrigin + bottomRow * frame.chromaStride;
        var cx = px / 2 + (mvx >> 3);
        var cy = py / 2 + (mvcy >> 3);
        var chromaPad = frame.chromaPad;
        var chromaVerticalInside = fieldRef ? cy >= 0 && cy + chromaSize + 1 <= chromaHeight
                : cy >= -chromaPad && cy + chromaSize + 1 <= chromaHeight + chromaPad;
        if (cx >= -chromaPad && cx + chromaSize + 1 <= chromaWidth + chromaPad && chromaVerticalInside) {
            var offset = chromaOrigin + cy * chromaStride + cx;
            dsp.chromaMc(frame.cb, frame.cr, offset, chromaStride, cb, cr, chromaAt, 8, chromaSize, chromaSize,
                    mvx & 7, mvcy & 7);
        } else {
            for (var y = 0; y < chromaSize + 1; y++) {
                var sy = Math.clamp(cy + y, 0, chromaHeight - 1);
                for (var x = 0; x < chromaSize + 1; x++) {
                    var sx = Math.clamp(cx + x, 0, chromaWidth - 1);
                    var at = chromaOrigin + sy * chromaStride + sx;
                    chromaEdgeCb[y * 16 + x] = frame.cb[at];
                    chromaEdgeCr[y * 16 + x] = frame.cr[at];
                }
            }
            dsp.chromaMc(chromaEdgeCb, chromaEdgeCr, 0, 16, cb, cr, chromaAt, 8, chromaSize, chromaSize, mvx & 7,
                    mvcy & 7);
        }
    }
}
