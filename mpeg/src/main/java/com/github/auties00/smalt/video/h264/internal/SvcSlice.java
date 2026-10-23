package com.github.auties00.smalt.video.h264.internal;

import com.github.auties00.smalt.MediaException;

import java.util.Arrays;

/**
 * Holds one slice of a scalable access unit (ITU-T H.264 Annex F): its NAL unit header extension,
 * its slice header, as {@code slice_header()} (the base layer) or
 * {@code slice_header_in_scalable_extension()} (the enhancement layers) codes it, and its slice
 * data.
 *
 * <p>The fields a slice with {@code quality_id} above 0 does not code are inferred from the slice
 * of its dependency representation with {@code quality_id} 0 by {@link #inherit(SvcSlice)}.
 */
final class SvcSlice {
    /**
     * The {@code nal_ref_idc}.
     */
    int nalRefIdc;

    /**
     * Whether the slice comes from a coded slice in scalable extension NAL unit
     * ({@code nal_unit_type} 20) rather than a base layer slice.
     */
    boolean extension;

    /**
     * The {@code idr_flag}, or for a base layer slice whether its {@code nal_unit_type} is 5.
     */
    boolean idr;

    /**
     * The {@code no_inter_layer_pred_flag}.
     */
    boolean noInterLayerPred = true;

    /**
     * The {@code dependency_id}.
     */
    int dependencyId;

    /**
     * The {@code quality_id}.
     */
    int qualityId;

    /**
     * The {@code temporal_id}.
     */
    int temporalId;

    /**
     * The {@code use_ref_base_pic_flag}.
     */
    boolean useRefBasePic;

    /**
     * The {@code discardable_flag}.
     */
    boolean discardable;

    /**
     * The {@code output_flag}.
     */
    boolean output = true;

    /**
     * The {@code store_ref_base_pic_flag}.
     */
    boolean storeRefBasePic;

    /**
     * The {@code adaptive_ref_base_pic_marking_mode_flag}.
     */
    boolean adaptiveRefBasePicMarking;

    /**
     * The number of {@code memory_management_base_control_operation}s, without the terminating 0.
     */
    int baseMmcoCount;

    /**
     * The {@code memory_management_base_control_operation} values, 1 or 2.
     */
    final int[] baseMmcoOp = new int[H264SliceHeader.MAX_MMCO];

    /**
     * The {@code difference_of_base_pic_nums_minus1} or {@code long_term_base_pic_num} of each
     * operation.
     */
    final int[] baseMmcoArg = new int[H264SliceHeader.MAX_MMCO];

    /**
     * The slice header fields shared with {@code slice_header()}: picture identification, list
     * construction, prediction weights, reference marking, quantization and deblocking. Its
     * {@code disableDeblockingFilterIdc} ranges from 0 to 6.
     */
    final H264SliceHeader header = new H264SliceHeader();

    /**
     * The {@code base_pred_weight_table_flag}.
     */
    boolean basePredWeightTable;

    /**
     * The {@code ref_layer_dq_id}, -1 when the slice has no reference layer.
     */
    int refLayerDqId = -1;

    /**
     * The {@code disable_inter_layer_deblocking_filter_idc}.
     */
    int interLayerDeblockingIdc;

    /**
     * The {@code InterlayerFilterOffsetA}.
     */
    int interLayerOffsetA;

    /**
     * The {@code InterlayerFilterOffsetB}.
     */
    int interLayerOffsetB;

    /**
     * The {@code constrained_intra_resampling_flag}.
     */
    boolean constrainedIntraResampling;

    /**
     * The {@code ref_layer_chroma_phase_x_plus1_flag}.
     */
    int refLayerChromaPhaseXPlus1 = 1;

    /**
     * The {@code ref_layer_chroma_phase_y_plus1}.
     */
    int refLayerChromaPhaseYPlus1 = 1;

    /**
     * The {@code scaled_ref_layer_left_offset}, in units of two luma samples.
     */
    int scaledLeft;

    /**
     * The {@code scaled_ref_layer_top_offset}.
     */
    int scaledTop;

    /**
     * The {@code scaled_ref_layer_right_offset}.
     */
    int scaledRight;

    /**
     * The {@code scaled_ref_layer_bottom_offset}.
     */
    int scaledBottom;

    /**
     * The {@code slice_skip_flag}.
     */
    boolean sliceSkip;

    /**
     * The {@code num_mbs_in_slice_minus1 + 1} of a skipped slice.
     */
    int skippedMbs;

    /**
     * The {@code adaptive_base_mode_flag}.
     */
    boolean adaptiveBaseMode;

    /**
     * The {@code default_base_mode_flag}.
     */
    boolean defaultBaseMode;

    /**
     * The {@code adaptive_motion_prediction_flag}.
     */
    boolean adaptiveMotionPrediction;

    /**
     * The {@code default_motion_prediction_flag}.
     */
    boolean defaultMotionPrediction;

    /**
     * The {@code adaptive_residual_prediction_flag}.
     */
    boolean adaptiveResidualPrediction;

    /**
     * The {@code default_residual_prediction_flag}.
     */
    boolean defaultResidualPrediction;

    /**
     * The {@code tcoeff_level_prediction_flag}.
     */
    boolean tcoeffLevelPrediction;

    /**
     * The {@code scan_idx_start}.
     */
    int scanIdxStart;

    /**
     * The {@code scan_idx_end}.
     */
    int scanIdxEnd = 15;

    /**
     * The reference picture lists the slice predicts from, indexed {@code [list][refIdx]}, or
     * {@code null} before they are constructed.
     */
    SvcPicture[][] refLists;

    /**
     * The unescaped slice payload, followed by zero padding; owned by the slice.
     */
    byte[] rbsp;


    /**
     * The payload length.
     */
    int size;

    /**
     * Returns the {@code DQId} of the slice, {@code (dependency_id << 4) + quality_id}.
     *
     * @return the {@code DQId}
     */
    int dqId() {
        return dependencyId << 4 | qualityId;
    }

    /**
     * Reads a {@code nal_unit_header_svc_extension()} (ITU-T H.264 F.7.3.1.1) into this slice.
     *
     * @param data the buffer holding the three bytes of the extension
     * @param offset the index of the first byte, whose top bit is {@code svc_extension_flag}
     */
    void readHeader(byte[] data, int offset) {
        var b1 = data[offset] & 0xFF;
        var b2 = data[offset + 1] & 0xFF;
        var b3 = data[offset + 2] & 0xFF;
        idr = (b1 & 0x40) != 0;
        noInterLayerPred = (b2 & 0x80) != 0;
        dependencyId = b2 >> 4 & 7;
        qualityId = b2 & 15;
        temporalId = b3 >> 5;
        useRefBasePic = (b3 & 0x10) != 0;
        discardable = (b3 & 0x08) != 0;
        output = (b3 & 0x04) != 0;
    }

    /**
     * Parses the {@code prefix_nal_unit_svc()} of the prefix NAL unit of a base layer slice
     * (ITU-T H.264 F.7.3.2.12.1).
     *
     * @param rbsp the unescaped payload after the header extension
     * @param size the payload length
     * @param nalRefIdc the {@code nal_ref_idc} of the prefix NAL unit
     * @throws MediaException.Malformed if the payload is truncated
     */
    void parsePrefix(byte[] rbsp, int size, int nalRefIdc) {
        storeRefBasePic = false;
        adaptiveRefBasePicMarking = false;
        baseMmcoCount = 0;
        if (nalRefIdc == 0 || size == 0) {
            return;
        }
        var reader = new H264BitReader();
        reader.init(rbsp, 0, size);
        storeRefBasePic = reader.readFlag();
        if ((useRefBasePic || storeRefBasePic) && !idr) {
            parseBaseMarking(reader);
        }
        if (reader.overrun()) {
            throw new MediaException.Malformed("truncated prefix NAL unit");
        }
    }

    /**
     * Parses a {@code dec_ref_base_pic_marking()} (ITU-T H.264 F.7.3.3.5).
     *
     * @param reader the reader positioned at the structure
     */
    private void parseBaseMarking(H264BitReader reader) {
        adaptiveRefBasePicMarking = reader.readFlag();
        baseMmcoCount = 0;
        if (!adaptiveRefBasePicMarking) {
            return;
        }
        while (true) {
            var op = reader.readUe();
            if (op == 0) {
                return;
            }
            if (op > 2 || baseMmcoCount >= baseMmcoOp.length || reader.overrun()) {
                throw new MediaException.Malformed("invalid memory_management_base_control_operation");
            }
            baseMmcoOp[baseMmcoCount] = op;
            baseMmcoArg[baseMmcoCount] = reader.readUe();
            baseMmcoCount++;
        }
    }

    /**
     * Parses a {@code slice_header_in_scalable_extension()} (ITU-T H.264 F.7.3.3.4) from the start
     * of {@link #rbsp}; the NAL unit header fields must be set.
     *
     * @param pps the picture parameter set the slice names, parsed against its subset sequence
     * parameter set
     * @return the bit position of the first bit of the slice data
     * @throws MediaException.Malformed if a field is out of range or the header is truncated
     */
    long parseScalable(H264Pps pps) {
        var reader = new H264BitReader();
        reader.init(rbsp, 0, size);
        var h = header;
        h.parseStart(reader);
        if (h.ppsId != pps.id) {
            throw new MediaException.Malformed("slice names another picture parameter set");
        }
        var sps = pps.sps;
        var svc = sps.svc;
        if (svc == null) {
            throw new MediaException.Malformed("scalable slice refers to a sequence parameter set without an SVC "
                    + "extension");
        }
        h.pps = pps;
        h.sps = sps;
        h.nalUnitType = H264Nal.SLICE_EXTENSION;
        h.nalRefIdc = nalRefIdc;
        h.idr = idr;
        h.mvc = false;
        h.colorPlaneId = 0;
        h.frameNum = reader.readBits(sps.log2MaxFrameNum);
        h.fieldPic = false;
        h.bottomField = false;
        if (!sps.frameMbsOnly) {
            h.fieldPic = reader.readFlag();
            if (h.fieldPic) {
                h.bottomField = reader.readFlag();
            }
        }
        if (idr) {
            h.idrPicId = reader.readUe();
        }
        h.deltaPocBottom = 0;
        h.deltaPoc0 = 0;
        h.deltaPoc1 = 0;
        if (sps.pocType == 0) {
            h.pocLsb = reader.readBits(sps.log2MaxPocLsb);
            if (pps.bottomFieldPicOrderInFramePresent && !h.fieldPic) {
                h.deltaPocBottom = reader.readSe();
            }
        } else if (sps.pocType == 1 && !sps.deltaPicOrderAlwaysZero) {
            h.deltaPoc0 = reader.readSe();
            if (pps.bottomFieldPicOrderInFramePresent && !h.fieldPic) {
                h.deltaPoc1 = reader.readSe();
            }
        }
        h.redundantPicCnt = 0;
        if (pps.redundantPicCntPresent) {
            h.redundantPicCnt = reader.readUe();
        }
        basePredWeightTable = false;
        storeRefBasePic = false;
        adaptiveRefBasePicMarking = false;
        baseMmcoCount = 0;
        if (qualityId == 0) {
            h.directSpatialMvPred = false;
            if (h.sliceType == H264SliceHeader.B) {
                h.directSpatialMvPred = reader.readFlag();
            }
            h.numRefIdxActive[0] = 0;
            h.numRefIdxActive[1] = 0;
            if (h.sliceType == H264SliceHeader.P || h.sliceType == H264SliceHeader.B) {
                h.numRefIdxActive[0] = pps.numRefIdxL0Default;
                if (h.sliceType == H264SliceHeader.B) {
                    h.numRefIdxActive[1] = pps.numRefIdxL1Default;
                }
                if (reader.readFlag()) {
                    h.numRefIdxActive[0] = reader.readUe() + 1;
                    if (h.sliceType == H264SliceHeader.B) {
                        h.numRefIdxActive[1] = reader.readUe() + 1;
                    }
                }
                var limit = h.fieldPic ? 32 : 16;
                if (h.numRefIdxActive[0] > limit || h.numRefIdxActive[1] > limit) {
                    throw new MediaException.Malformed("num_ref_idx_active_minus1 out of range");
                }
            }
            h.refPicListModification[0] = false;
            h.refPicListModification[1] = false;
            h.modificationCount[0] = 0;
            h.modificationCount[1] = 0;
            if (h.sliceType != H264SliceHeader.I) {
                h.parseModification(reader, 0);
                if (h.sliceType == H264SliceHeader.B) {
                    h.parseModification(reader, 1);
                }
            }
            h.hasWeightTable = false;
            if ((pps.weightedPred && h.sliceType == H264SliceHeader.P)
                    || (pps.weightedBipredIdc == 1 && h.sliceType == H264SliceHeader.B)) {
                if (!noInterLayerPred) {
                    basePredWeightTable = reader.readFlag();
                }
                if (noInterLayerPred || !basePredWeightTable) {
                    h.hasWeightTable = true;
                    h.parseWeightTable(reader, sps.chromaArrayType());
                }
            }
            h.noOutputOfPriorPics = false;
            h.longTermReference = false;
            h.adaptiveRefPicMarking = false;
            h.mmcoCount = 0;
            if (nalRefIdc != 0) {
                if (idr) {
                    h.noOutputOfPriorPics = reader.readFlag();
                    h.longTermReference = reader.readFlag();
                } else {
                    h.adaptiveRefPicMarking = reader.readFlag();
                    if (h.adaptiveRefPicMarking) {
                        h.parseMarking(reader);
                    }
                }
                if (!svc.sliceHeaderRestriction) {
                    storeRefBasePic = reader.readFlag();
                    if ((useRefBasePic || storeRefBasePic) && !idr) {
                        parseBaseMarking(reader);
                    }
                }
            }
        }
        h.cabacInitIdc = 0;
        if (pps.cabac && h.sliceType != H264SliceHeader.I) {
            h.cabacInitIdc = reader.readUe();
            if (h.cabacInitIdc > 2) {
                throw new MediaException.Malformed("cabac_init_idc " + h.cabacInitIdc + " out of range");
            }
        }
        h.sliceQp = pps.initQp + reader.readSe();
        if (h.sliceQp < 0 || h.sliceQp > 51) {
            throw new MediaException.Malformed("slice QP " + h.sliceQp + " out of range");
        }
        h.spForSwitch = false;
        h.sliceQs = 0;
        h.disableDeblockingFilterIdc = 0;
        h.filterOffsetA = 0;
        h.filterOffsetB = 0;
        if (pps.deblockingFilterControlPresent) {
            h.disableDeblockingFilterIdc = reader.readUe();
            if (h.disableDeblockingFilterIdc > 6) {
                throw new MediaException.Malformed("disable_deblocking_filter_idc out of range");
            }
            if (h.disableDeblockingFilterIdc != 1) {
                h.filterOffsetA = reader.readSe() * 2;
                h.filterOffsetB = reader.readSe() * 2;
                if (Math.abs(h.filterOffsetA) > 12 || Math.abs(h.filterOffsetB) > 12) {
                    throw new MediaException.Malformed("deblocking filter offset out of range");
                }
            }
        }
        h.sliceGroupChangeCycle = 0;
        if (pps.numSliceGroups > 1 && pps.sliceGroupMapType >= 3 && pps.sliceGroupMapType <= 5) {
            var mapUnits = sps.widthInMbs * sps.heightInMapUnits;
            var value = mapUnits / pps.sliceGroupChangeRate + (mapUnits % pps.sliceGroupChangeRate != 0 ? 1 : 0) + 1;
            var bits = 32 - Integer.numberOfLeadingZeros(value - 1);
            h.sliceGroupChangeCycle = reader.readBits(bits);
        }
        refLayerDqId = qualityId > 0 ? dqId() - 1 : -1;
        interLayerDeblockingIdc = 0;
        interLayerOffsetA = 0;
        interLayerOffsetB = 0;
        constrainedIntraResampling = false;
        if (qualityId > 0) {
            refLayerChromaPhaseXPlus1 = svc.chromaPhaseXPlus1;
            refLayerChromaPhaseYPlus1 = svc.chromaPhaseYPlus1;
            scaledLeft = 0;
            scaledTop = 0;
            scaledRight = 0;
            scaledBottom = 0;
        } else {
            refLayerChromaPhaseXPlus1 = svc.refLayerChromaPhaseXPlus1;
            refLayerChromaPhaseYPlus1 = svc.refLayerChromaPhaseYPlus1;
            scaledLeft = svc.scaledLeftOffset;
            scaledTop = svc.scaledTopOffset;
            scaledRight = svc.scaledRightOffset;
            scaledBottom = svc.scaledBottomOffset;
        }
        if (!noInterLayerPred && qualityId == 0) {
            refLayerDqId = reader.readUe();
            if (refLayerDqId >= dqId()) {
                throw new MediaException.Malformed("ref_layer_dq_id " + refLayerDqId + " out of range");
            }
            if (svc.interLayerDeblockingControl) {
                interLayerDeblockingIdc = reader.readUe();
                if (interLayerDeblockingIdc > 6) {
                    throw new MediaException.Malformed("disable_inter_layer_deblocking_filter_idc out of range");
                }
                if (interLayerDeblockingIdc != 1) {
                    interLayerOffsetA = reader.readSe() * 2;
                    interLayerOffsetB = reader.readSe() * 2;
                    if (Math.abs(interLayerOffsetA) > 12 || Math.abs(interLayerOffsetB) > 12) {
                        throw new MediaException.Malformed("inter-layer deblocking filter offset out of range");
                    }
                }
            }
            constrainedIntraResampling = reader.readFlag();
            if (svc.extendedSpatialScalability == 2) {
                if (sps.chromaArrayType() > 0) {
                    refLayerChromaPhaseXPlus1 = reader.readBits(1);
                    refLayerChromaPhaseYPlus1 = reader.readBits(2);
                    if (refLayerChromaPhaseYPlus1 > 2) {
                        throw new MediaException.Malformed("ref_layer_chroma_phase_y_plus1 out of range");
                    }
                }
                scaledLeft = reader.readSe();
                scaledTop = reader.readSe();
                scaledRight = reader.readSe();
                scaledBottom = reader.readSe();
            }
        }
        sliceSkip = false;
        skippedMbs = 0;
        adaptiveBaseMode = false;
        defaultBaseMode = false;
        adaptiveMotionPrediction = false;
        defaultMotionPrediction = false;
        adaptiveResidualPrediction = false;
        defaultResidualPrediction = false;
        tcoeffLevelPrediction = false;
        if (!noInterLayerPred) {
            sliceSkip = reader.readFlag();
            if (sliceSkip) {
                skippedMbs = reader.readUe() + 1;
            } else {
                adaptiveBaseMode = reader.readFlag();
                if (!adaptiveBaseMode) {
                    defaultBaseMode = reader.readFlag();
                }
                if (!defaultBaseMode) {
                    adaptiveMotionPrediction = reader.readFlag();
                    if (!adaptiveMotionPrediction) {
                        defaultMotionPrediction = reader.readFlag();
                    }
                }
                adaptiveResidualPrediction = reader.readFlag();
                if (!adaptiveResidualPrediction) {
                    defaultResidualPrediction = reader.readFlag();
                }
            }
            tcoeffLevelPrediction = svc.adaptiveTcoeffLevelPrediction ? reader.readFlag()
                    : svc.tcoeffLevelPrediction;
        }
        scanIdxStart = 0;
        scanIdxEnd = 15;
        if (!svc.sliceHeaderRestriction && !sliceSkip) {
            scanIdxStart = reader.readBits(4);
            scanIdxEnd = reader.readBits(4);
        }
        if (reader.overrun()) {
            throw new MediaException.Malformed("truncated slice header in scalable extension");
        }
        var position = reader.bitPosition();
        h.dataBitPosition = position;
        return position;
    }

    /**
     * Sets the NAL unit header extension fields of a base layer slice without a prefix NAL unit
     * to the values ITU-T H.264 F.7.4.1.1 gives them.
     */
    void inferPrefix() {
        noInterLayerPred = true;
        dependencyId = 0;
        qualityId = 0;
        temporalId = 0;
        useRefBasePic = false;
        discardable = false;
        output = true;
        storeRefBasePic = false;
        adaptiveRefBasePicMarking = false;
        baseMmcoCount = 0;
    }

    /**
     * Sets the fields of a base layer slice, whose header {@link #header} holds, to the values
     * ITU-T H.264 F.7.4.3 infers for {@code slice_header()}.
     */
    void inferBase() {
        extension = false;
        refLayerDqId = -1;
        noInterLayerPred = true;
        sliceSkip = false;
        skippedMbs = 0;
        interLayerOffsetA = 0;
        interLayerOffsetB = 0;
        refLayerChromaPhaseXPlus1 = 1;
        refLayerChromaPhaseYPlus1 = 1;
        scaledLeft = 0;
        scaledTop = 0;
        scaledRight = 0;
        scaledBottom = 0;
        scanIdxStart = 0;
        scanIdxEnd = 15;
        tcoeffLevelPrediction = false;
        adaptiveBaseMode = false;
        defaultBaseMode = false;
        adaptiveMotionPrediction = false;
        defaultMotionPrediction = false;
        adaptiveResidualPrediction = false;
        defaultResidualPrediction = false;
        basePredWeightTable = false;
        interLayerDeblockingIdc = 0;
        constrainedIntraResampling = false;
    }

    /**
     * Infers the fields a slice with {@code quality_id} above 0 does not code from the slice of the
     * same dependency representation with {@code quality_id} 0 that covers its first macroblock
     * (ITU-T H.264 F.7.4.3.4).
     *
     * @param base that slice
     */
    void inherit(SvcSlice base) {
        var h = header;
        var b = base.header;
        h.directSpatialMvPred = b.directSpatialMvPred;
        System.arraycopy(b.numRefIdxActive, 0, h.numRefIdxActive, 0, 2);
        System.arraycopy(b.refPicListModification, 0, h.refPicListModification, 0, 2);
        System.arraycopy(b.modificationCount, 0, h.modificationCount, 0, 2);
        for (var list = 0; list < 2; list++) {
            System.arraycopy(b.modificationIdc[list], 0, h.modificationIdc[list], 0, b.modificationIdc[list].length);
            System.arraycopy(b.modificationValue[list], 0, h.modificationValue[list], 0,
                    b.modificationValue[list].length);
        }
        basePredWeightTable = base.basePredWeightTable;
        h.hasWeightTable = b.hasWeightTable;
        h.lumaLog2WeightDenom = b.lumaLog2WeightDenom;
        h.chromaLog2WeightDenom = b.chromaLog2WeightDenom;
        for (var list = 0; list < 2; list++) {
            System.arraycopy(b.lumaWeight[list], 0, h.lumaWeight[list], 0, b.lumaWeight[list].length);
            System.arraycopy(b.lumaOffset[list], 0, h.lumaOffset[list], 0, b.lumaOffset[list].length);
            System.arraycopy(b.lumaWeightFlag[list], 0, h.lumaWeightFlag[list], 0, b.lumaWeightFlag[list].length);
            System.arraycopy(b.chromaWeight[list], 0, h.chromaWeight[list], 0, b.chromaWeight[list].length);
            System.arraycopy(b.chromaOffset[list], 0, h.chromaOffset[list], 0, b.chromaOffset[list].length);
            System.arraycopy(b.chromaWeightFlag[list], 0, h.chromaWeightFlag[list], 0,
                    b.chromaWeightFlag[list].length);
        }
        h.noOutputOfPriorPics = b.noOutputOfPriorPics;
        h.longTermReference = b.longTermReference;
        h.adaptiveRefPicMarking = b.adaptiveRefPicMarking;
        h.mmcoCount = b.mmcoCount;
        System.arraycopy(b.mmcoOp, 0, h.mmcoOp, 0, b.mmcoOp.length);
        System.arraycopy(b.mmcoArg1, 0, h.mmcoArg1, 0, b.mmcoArg1.length);
        System.arraycopy(b.mmcoArg2, 0, h.mmcoArg2, 0, b.mmcoArg2.length);
        storeRefBasePic = base.storeRefBasePic;
        adaptiveRefBasePicMarking = base.adaptiveRefBasePicMarking;
        baseMmcoCount = base.baseMmcoCount;
        System.arraycopy(base.baseMmcoOp, 0, baseMmcoOp, 0, baseMmcoOp.length);
        System.arraycopy(base.baseMmcoArg, 0, baseMmcoArg, 0, baseMmcoArg.length);
    }

    /**
     * Takes a copy of an unescaped payload as this slice's data.
     *
     * @param source the payload
     * @param length the payload length
     */
    void takePayload(byte[] source, int length) {
        rbsp = Arrays.copyOf(source, length + H264BitReader.PADDING);
        size = length;
    }
}
