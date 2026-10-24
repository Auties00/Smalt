package com.github.auties00.smalt.video.h264.internal;

import com.github.auties00.smalt.MediaException;

/**
 * Holds a parsed H.264 slice header (ITU-T H.264 7.3.3), including the reference picture list
 * modification, prediction weight table and decoded reference picture marking syntax.
 *
 * <p>An instance is reused for successive slices: {@link #parseStart} and {@link #parseRest}
 * overwrite every field.
 */
public final class H264SliceHeader {
    /**
     * The P slice type, {@code slice_type % 5 == 0}.
     */
    public static final int P = 0;

    /**
     * The B slice type.
     */
    public static final int B = 1;

    /**
     * The I slice type.
     */
    public static final int I = 2;

    /**
     * The SP slice type.
     */
    public static final int SP = 3;

    /**
     * The SI slice type.
     */
    public static final int SI = 4;

    /**
     * The largest number of memory management control operations accepted in one header.
     */
    public static final int MAX_MMCO = 72;

    /**
     * The largest number of reference list modification operations accepted per list.
     */
    public static final int MAX_MODIFICATIONS = 33;

    /**
     * The {@code nal_unit_type} of the slice NAL unit.
     */
    public int nalUnitType;

    /**
     * The {@code nal_ref_idc} of the slice NAL unit.
     */
    public int nalRefIdc;

    /**
     * Whether the slice belongs to an IDR picture.
     */
    public boolean idr;

    /**
     * Whether the slice is a coded slice MVC extension ({@code nal_unit_type} 20), whose list
     * modifications may name inter-view references.
     */
    public boolean mvc;

    /**
     * The view order index {@code VOIdx} of the view the slice belongs to, 0 for the base view.
     */
    public int voIdx;

    /**
     * The {@code view_id} of the view the slice belongs to.
     */
    public int viewId;

    /**
     * The {@code temporal_id} of the slice, 0 when its NAL unit has no header extension.
     */
    public int temporalId;

    /**
     * The {@code anchor_pic_flag} of the slice's access unit.
     */
    public boolean anchor;

    /**
     * The {@code inter_view_flag} of the slice: whether its view component may be an inter-view
     * reference of the other view components of its access unit.
     */
    public boolean interView = true;

    /**
     * The {@code first_mb_in_slice}.
     */
    public int firstMbInSlice;

    /**
     * The slice type reduced modulo 5: {@link #P}, {@link #B}, {@link #I}, {@link #SP} or
     * {@link #SI}.
     */
    public int sliceType;

    /**
     * The {@code pic_parameter_set_id}.
     */
    public int ppsId;

    /**
     * The picture parameter set in effect.
     */
    public H264Pps pps;

    /**
     * The sequence parameter set in effect.
     */
    public H264Sps sps;

    /**
     * The {@code colour_plane_id} of a slice of a picture whose color planes are coded
     * separately: 0 for Y, 1 for Cb, 2 for Cr; 0 otherwise.
     */
    public int colorPlaneId;

    /**
     * The {@code frame_num}.
     */
    public int frameNum;

    /**
     * The {@code field_pic_flag}.
     */
    public boolean fieldPic;

    /**
     * The {@code bottom_field_flag}.
     */
    public boolean bottomField;

    /**
     * The {@code idr_pic_id}.
     */
    public int idrPicId;

    /**
     * The {@code pic_order_cnt_lsb}.
     */
    public int pocLsb;

    /**
     * The {@code delta_pic_order_cnt_bottom}.
     */
    public int deltaPocBottom;

    /**
     * The {@code delta_pic_order_cnt[0]}.
     */
    public int deltaPoc0;

    /**
     * The {@code delta_pic_order_cnt[1]}.
     */
    public int deltaPoc1;

    /**
     * The {@code redundant_pic_cnt}.
     */
    public int redundantPicCnt;

    /**
     * The {@code direct_spatial_mv_pred_flag}.
     */
    public boolean directSpatialMvPred;

    /**
     * The number of active references per list, {@code num_ref_idx_lX_active_minus1 + 1}; zero
     * for lists the slice type does not use.
     */
    public final int[] numRefIdxActive = new int[2];

    /**
     * Whether each list carries a modification.
     */
    public final boolean[] refPicListModification = new boolean[2];

    /**
     * The number of modification operations per list, excluding the terminating 3.
     */
    public final int[] modificationCount = new int[2];

    /**
     * The {@code modification_of_pic_nums_idc} of each operation, indexed
     * {@code [list][operation]}.
     */
    public final int[][] modificationIdc = new int[2][MAX_MODIFICATIONS];

    /**
     * The {@code abs_diff_pic_num_minus1} or {@code long_term_pic_num} of each operation.
     */
    public final int[][] modificationValue = new int[2][MAX_MODIFICATIONS];

    /**
     * Whether the slice carries an explicit prediction weight table.
     */
    public boolean hasWeightTable;

    /**
     * The {@code luma_log2_weight_denom}.
     */
    public int lumaLog2WeightDenom;

    /**
     * The {@code chroma_log2_weight_denom}.
     */
    public int chromaLog2WeightDenom;

    /**
     * The luma weights, indexed {@code [list][refIdx]}; the default {@code 2^denom} when not
     * signaled.
     */
    public final int[][] lumaWeight = new int[2][32];

    /**
     * The luma offsets, indexed {@code [list][refIdx]}.
     */
    public final int[][] lumaOffset = new int[2][32];

    /**
     * Whether {@code luma_weight_lX_flag} was set, indexed {@code [list][refIdx]}.
     */
    public final boolean[][] lumaWeightFlag = new boolean[2][32];

    /**
     * The chroma weights, indexed {@code [list][refIdx * 2 + iCbCr]}.
     */
    public final int[][] chromaWeight = new int[2][64];

    /**
     * The chroma offsets, indexed {@code [list][refIdx * 2 + iCbCr]}.
     */
    public final int[][] chromaOffset = new int[2][64];

    /**
     * Whether {@code chroma_weight_lX_flag} was set, indexed {@code [list][refIdx]}.
     */
    public final boolean[][] chromaWeightFlag = new boolean[2][32];

    /**
     * The {@code no_output_of_prior_pics_flag}.
     */
    public boolean noOutputOfPriorPics;

    /**
     * The {@code long_term_reference_flag}.
     */
    public boolean longTermReference;

    /**
     * The {@code adaptive_ref_pic_marking_mode_flag}.
     */
    public boolean adaptiveRefPicMarking;

    /**
     * The number of memory management control operations, excluding the terminating 0.
     */
    public int mmcoCount;

    /**
     * The {@code memory_management_control_operation} values.
     */
    public final int[] mmcoOp = new int[MAX_MMCO];

    /**
     * The first argument of each operation: {@code difference_of_pic_nums_minus1},
     * {@code long_term_pic_num} or {@code max_long_term_frame_idx_plus1}.
     */
    public final int[] mmcoArg1 = new int[MAX_MMCO];

    /**
     * The {@code long_term_frame_idx} of operations 3 and 6.
     */
    public final int[] mmcoArg2 = new int[MAX_MMCO];

    /**
     * The {@code cabac_init_idc}.
     */
    public int cabacInitIdc;

    /**
     * The slice luma quantization parameter {@code SliceQPY}.
     */
    public int sliceQp;

    /**
     * The {@code sp_for_switch_flag} of an SP slice: whether its inter macroblocks are decoded
     * with the switching process of ITU-T H.264 8.6.2 rather than 8.6.1.
     */
    public boolean spForSwitch;

    /**
     * The quantization parameter {@code QSY} of an SP or SI slice,
     * {@code 26 + pic_init_qs_minus26 + slice_qs_delta}, 0 to 51.
     */
    public int sliceQs;

    /**
     * The {@code slice_id} of a slice data partition A, which its partitions B and C repeat.
     */
    public int sliceId;

    /**
     * The {@code disable_deblocking_filter_idc}.
     */
    public int disableDeblockingFilterIdc;

    /**
     * The {@code FilterOffsetA}, {@code slice_alpha_c0_offset_div2 * 2}.
     */
    public int filterOffsetA;

    /**
     * The {@code FilterOffsetB}, {@code slice_beta_offset_div2 * 2}.
     */
    public int filterOffsetB;

    /**
     * The {@code slice_group_change_cycle}.
     */
    public int sliceGroupChangeCycle;

    /**
     * The bit position of the first bit of {@code slice_data()} within the payload.
     */
    public long dataBitPosition;

    /**
     * Constructs an empty header.
     */
    public H264SliceHeader() {
    }

    /**
     * Returns whether this is a B slice.
     *
     * @return {@code true} for B slices
     */
    public boolean isB() {
        return sliceType == B;
    }

    /**
     * Returns whether this slice is intra-only (I or SI).
     *
     * @return {@code true} for I and SI slices
     */
    public boolean isIntra() {
        return sliceType == I || sliceType == SI;
    }

    /**
     * Parses the first three fields of a slice header without resolving parameter sets.
     *
     * @param reader the reader positioned at the start of the slice header
     * @return {@code first_mb_in_slice}; {@link #sliceType} and {@link #ppsId} are also set
     * @throws MediaException.Malformed if a field is out of range
     */
    public int parseStart(H264BitReader reader) {
        firstMbInSlice = reader.readUe();
        var type = reader.readUe();
        if (type > 9) {
            throw new MediaException.Malformed("slice_type " + type + " out of range");
        }
        sliceType = type % 5;
        ppsId = reader.readUe();
        if (ppsId > 255) {
            throw new MediaException.Malformed("pic_parameter_set_id " + ppsId + " out of range");
        }
        return firstMbInSlice;
    }

    /**
     * Parses the rest of the slice header after {@link #parseStart(H264BitReader)}.
     *
     * @param reader the reader positioned after {@code pic_parameter_set_id}
     * @param pps the picture parameter set named by {@link #ppsId}
     * @param nalUnitType the {@code nal_unit_type}
     * @param nalRefIdc the {@code nal_ref_idc}
     * @throws MediaException.Malformed if a field is out of range or the header is truncated
     */
    public void parseRest(H264BitReader reader, H264Pps pps, int nalUnitType, int nalRefIdc) {
        parseRest(reader, pps, nalUnitType, nalRefIdc, nalUnitType == H264Nal.IDR_SLICE);
    }

    /**
     * Parses the rest of the slice header after {@link #parseStart(H264BitReader)}, for a slice
     * whose IDR status the NAL unit header extension gives.
     *
     * @param reader the reader positioned after {@code pic_parameter_set_id}
     * @param pps the picture parameter set named by {@link #ppsId}
     * @param nalUnitType the {@code nal_unit_type}
     * @param nalRefIdc the {@code nal_ref_idc}
     * @param idr whether the slice belongs to an IDR picture ({@code IdrPicFlag})
     * @throws MediaException.Malformed if a field is out of range or the header is truncated
     */
    public void parseRest(H264BitReader reader, H264Pps pps, int nalUnitType, int nalRefIdc, boolean idr) {
        var sps = pps.sps;
        this.pps = pps;
        this.sps = sps;
        this.nalUnitType = nalUnitType;
        this.nalRefIdc = nalRefIdc;
        this.idr = idr;
        this.mvc = nalUnitType == H264Nal.SLICE_EXTENSION;
        colorPlaneId = 0;
        if (sps.separateColorPlane) {
            colorPlaneId = reader.readBits(2);
            if (colorPlaneId > 2) {
                throw new MediaException.Malformed("colour_plane_id " + colorPlaneId + " out of range");
            }
        }
        frameNum = reader.readBits(sps.log2MaxFrameNum);
        fieldPic = false;
        bottomField = false;
        if (!sps.frameMbsOnly) {
            fieldPic = reader.readFlag();
            if (fieldPic) {
                bottomField = reader.readFlag();
            }
        }
        if (idr) {
            idrPicId = reader.readUe();
        }
        deltaPocBottom = 0;
        deltaPoc0 = 0;
        deltaPoc1 = 0;
        if (sps.pocType == 0) {
            pocLsb = reader.readBits(sps.log2MaxPocLsb);
            if (pps.bottomFieldPicOrderInFramePresent && !fieldPic) {
                deltaPocBottom = reader.readSe();
            }
        } else if (sps.pocType == 1 && !sps.deltaPicOrderAlwaysZero) {
            deltaPoc0 = reader.readSe();
            if (pps.bottomFieldPicOrderInFramePresent && !fieldPic) {
                deltaPoc1 = reader.readSe();
            }
        }
        redundantPicCnt = 0;
        if (pps.redundantPicCntPresent) {
            redundantPicCnt = reader.readUe();
        }
        directSpatialMvPred = false;
        if (sliceType == B) {
            directSpatialMvPred = reader.readFlag();
        }
        numRefIdxActive[0] = 0;
        numRefIdxActive[1] = 0;
        if (sliceType == P || sliceType == SP || sliceType == B) {
            numRefIdxActive[0] = pps.numRefIdxL0Default;
            if (sliceType == B) {
                numRefIdxActive[1] = pps.numRefIdxL1Default;
            }
            if (reader.readFlag()) {
                numRefIdxActive[0] = reader.readUe() + 1;
                if (sliceType == B) {
                    numRefIdxActive[1] = reader.readUe() + 1;
                }
            }
            var limit = fieldPic ? 32 : 16;
            if (numRefIdxActive[0] > limit || numRefIdxActive[1] > limit) {
                throw new MediaException.Malformed("num_ref_idx_active_minus1 out of range");
            }
        }
        refPicListModification[0] = false;
        refPicListModification[1] = false;
        modificationCount[0] = 0;
        modificationCount[1] = 0;
        if (sliceType != I && sliceType != SI) {
            parseModification(reader, 0);
            if (sliceType == B) {
                parseModification(reader, 1);
            }
        }
        hasWeightTable = (pps.weightedPred && (sliceType == P || sliceType == SP))
                || (pps.weightedBipredIdc == 1 && sliceType == B);
        if (hasWeightTable) {
            parseWeightTable(reader, sps.chromaArrayType());
        }
        noOutputOfPriorPics = false;
        longTermReference = false;
        adaptiveRefPicMarking = false;
        mmcoCount = 0;
        if (nalRefIdc != 0) {
            if (idr) {
                noOutputOfPriorPics = reader.readFlag();
                longTermReference = reader.readFlag();
            } else {
                adaptiveRefPicMarking = reader.readFlag();
                if (adaptiveRefPicMarking) {
                    parseMarking(reader);
                }
            }
        }
        cabacInitIdc = 0;
        if (pps.cabac && sliceType != I && sliceType != SI) {
            cabacInitIdc = reader.readUe();
            if (cabacInitIdc > 2) {
                throw new MediaException.Malformed("cabac_init_idc " + cabacInitIdc + " out of range");
            }
        }
        var qpDelta = reader.readSe();
        sliceQp = pps.initQp + qpDelta;
        if (sliceQp < -6 * (sps.bitDepthLuma - 8) || sliceQp > 51) {
            throw new MediaException.Malformed("slice QP " + sliceQp + " out of range");
        }
        spForSwitch = false;
        sliceQs = 0;
        if (sliceType == SP || sliceType == SI) {
            if (sliceType == SP) {
                spForSwitch = reader.readFlag();
            }
            sliceQs = pps.initQs + reader.readSe();
            if (sliceQs < 0 || sliceQs > 51) {
                throw new MediaException.Malformed("slice QS " + sliceQs + " out of range");
            }
        }
        disableDeblockingFilterIdc = 0;
        filterOffsetA = 0;
        filterOffsetB = 0;
        if (pps.deblockingFilterControlPresent) {
            disableDeblockingFilterIdc = reader.readUe();
            if (disableDeblockingFilterIdc > 2) {
                throw new MediaException.Malformed("disable_deblocking_filter_idc out of range");
            }
            if (disableDeblockingFilterIdc != 1) {
                filterOffsetA = reader.readSe() * 2;
                filterOffsetB = reader.readSe() * 2;
                if (filterOffsetA < -12 || filterOffsetA > 12 || filterOffsetB < -12 || filterOffsetB > 12) {
                    throw new MediaException.Malformed("deblocking filter offset out of range");
                }
            }
        }
        sliceGroupChangeCycle = 0;
        if (pps.numSliceGroups > 1 && pps.sliceGroupMapType >= 3 && pps.sliceGroupMapType <= 5) {
            var mapUnits = sps.widthInMbs * sps.heightInMapUnits;
            var value = mapUnits / pps.sliceGroupChangeRate + (mapUnits % pps.sliceGroupChangeRate != 0 ? 1 : 0) + 1;
            var bits = 32 - Integer.numberOfLeadingZeros(value - 1);
            sliceGroupChangeCycle = reader.readBits(bits);
        }
        sliceId = 0;
        if (nalUnitType == H264Nal.SLICE_PARTITION_A) {
            sliceId = reader.readUe();
            var limit = sps.widthInMbs * sps.frameHeightInMbs;
            if (sliceId >= limit) {
                throw new MediaException.Malformed("slice_id " + sliceId + " out of range");
            }
        }
        if (reader.overrun()) {
            throw new MediaException.Malformed("truncated slice header");
        }
        dataBitPosition = reader.bitPosition();
    }

    /**
     * Parses one {@code ref_pic_list_modification()} list.
     *
     * @param reader the reader positioned at the list modification
     * @param list the list index, 0 or 1
     */
    void parseModification(H264BitReader reader, int list) {
        refPicListModification[list] = reader.readFlag();
        if (!refPicListModification[list]) {
            return;
        }
        var count = 0;
        while (true) {
            var idc = reader.readUe();
            if (idc == 3) {
                break;
            }
            if (idc > 5 || count >= MAX_MODIFICATIONS || reader.overrun()) {
                throw new MediaException.Malformed("invalid reference picture list modification");
            }
            if (idc > 2 && !mvc) {
                throw new MediaException.Malformed("inter-view reference list modification outside a multiview "
                        + "slice");
            }
            modificationIdc[list][count] = idc;
            modificationValue[list][count] = reader.readUe();
            count++;
        }
        modificationCount[list] = count;
    }

    /**
     * Parses the {@code pred_weight_table()}.
     *
     * @param reader the reader positioned at the prediction weight table
     * @param chromaArrayType the {@code ChromaArrayType}
     */
    void parseWeightTable(H264BitReader reader, int chromaArrayType) {
        lumaLog2WeightDenom = reader.readUe();
        if (lumaLog2WeightDenom > 7) {
            throw new MediaException.Malformed("luma_log2_weight_denom out of range");
        }
        chromaLog2WeightDenom = 0;
        if (chromaArrayType != 0) {
            chromaLog2WeightDenom = reader.readUe();
            if (chromaLog2WeightDenom > 7) {
                throw new MediaException.Malformed("chroma_log2_weight_denom out of range");
            }
        }
        var lists = sliceType == B ? 2 : 1;
        for (var list = 0; list < lists; list++) {
            for (var i = 0; i < numRefIdxActive[list]; i++) {
                lumaWeightFlag[list][i] = reader.readFlag();
                if (lumaWeightFlag[list][i]) {
                    lumaWeight[list][i] = reader.readSe();
                    lumaOffset[list][i] = reader.readSe();
                    if (lumaWeight[list][i] < -128 || lumaWeight[list][i] > 127
                            || lumaOffset[list][i] < -128 || lumaOffset[list][i] > 127) {
                        throw new MediaException.Malformed("luma weight out of range");
                    }
                } else {
                    lumaWeight[list][i] = 1 << lumaLog2WeightDenom;
                    lumaOffset[list][i] = 0;
                }
                chromaWeightFlag[list][i] = false;
                if (chromaArrayType != 0) {
                    chromaWeightFlag[list][i] = reader.readFlag();
                }
                for (var c = 0; c < 2; c++) {
                    if (chromaWeightFlag[list][i]) {
                        var weight = reader.readSe();
                        var offset = reader.readSe();
                        if (weight < -128 || weight > 127 || offset < -128 || offset > 127) {
                            throw new MediaException.Malformed("chroma weight out of range");
                        }
                        chromaWeight[list][i * 2 + c] = weight;
                        chromaOffset[list][i * 2 + c] = offset;
                    } else {
                        chromaWeight[list][i * 2 + c] = 1 << chromaLog2WeightDenom;
                        chromaOffset[list][i * 2 + c] = 0;
                    }
                }
            }
        }
    }

    /**
     * Parses the adaptive memory management control operations of
     * {@code dec_ref_pic_marking()}.
     *
     * @param reader the reader positioned at the reference picture marking
     */
    void parseMarking(H264BitReader reader) {
        var count = 0;
        while (true) {
            var op = reader.readUe();
            if (op == 0) {
                break;
            }
            if (op > 6 || count >= MAX_MMCO || reader.overrun()) {
                throw new MediaException.Malformed("invalid memory management control operation");
            }
            mmcoOp[count] = op;
            mmcoArg1[count] = 0;
            mmcoArg2[count] = 0;
            if (op == 1 || op == 3) {
                mmcoArg1[count] = reader.readUe();
            }
            if (op == 2) {
                mmcoArg1[count] = reader.readUe();
            }
            if (op == 3 || op == 6) {
                mmcoArg2[count] = reader.readUe();
            }
            if (op == 4) {
                mmcoArg1[count] = reader.readUe();
            }
            count++;
        }
        mmcoCount = count;
    }

    /**
     * Copies every field of another header into this one.
     *
     * @param other the header to copy
     */
    public void copyFrom(H264SliceHeader other) {
        nalUnitType = other.nalUnitType;
        nalRefIdc = other.nalRefIdc;
        idr = other.idr;
        mvc = other.mvc;
        voIdx = other.voIdx;
        viewId = other.viewId;
        temporalId = other.temporalId;
        anchor = other.anchor;
        interView = other.interView;

        firstMbInSlice = other.firstMbInSlice;
        sliceType = other.sliceType;
        ppsId = other.ppsId;
        pps = other.pps;
        sps = other.sps;
        colorPlaneId = other.colorPlaneId;
        frameNum = other.frameNum;
        fieldPic = other.fieldPic;
        bottomField = other.bottomField;
        idrPicId = other.idrPicId;
        pocLsb = other.pocLsb;
        deltaPocBottom = other.deltaPocBottom;
        deltaPoc0 = other.deltaPoc0;
        deltaPoc1 = other.deltaPoc1;
        redundantPicCnt = other.redundantPicCnt;
        directSpatialMvPred = other.directSpatialMvPred;
        System.arraycopy(other.numRefIdxActive, 0, numRefIdxActive, 0, numRefIdxActive.length);
        System.arraycopy(other.refPicListModification, 0, refPicListModification, 0, refPicListModification.length);
        System.arraycopy(other.modificationCount, 0, modificationCount, 0, modificationCount.length);
        for (var i = 0; i < modificationIdc.length; i++) {
            System.arraycopy(other.modificationIdc[i], 0, modificationIdc[i], 0, modificationIdc[i].length);
        }
        for (var i = 0; i < modificationValue.length; i++) {
            System.arraycopy(other.modificationValue[i], 0, modificationValue[i], 0, modificationValue[i].length);
        }
        hasWeightTable = other.hasWeightTable;
        lumaLog2WeightDenom = other.lumaLog2WeightDenom;
        chromaLog2WeightDenom = other.chromaLog2WeightDenom;
        for (var i = 0; i < lumaWeight.length; i++) {
            System.arraycopy(other.lumaWeight[i], 0, lumaWeight[i], 0, lumaWeight[i].length);
        }
        for (var i = 0; i < lumaOffset.length; i++) {
            System.arraycopy(other.lumaOffset[i], 0, lumaOffset[i], 0, lumaOffset[i].length);
        }
        for (var i = 0; i < lumaWeightFlag.length; i++) {
            System.arraycopy(other.lumaWeightFlag[i], 0, lumaWeightFlag[i], 0, lumaWeightFlag[i].length);
        }
        for (var i = 0; i < chromaWeight.length; i++) {
            System.arraycopy(other.chromaWeight[i], 0, chromaWeight[i], 0, chromaWeight[i].length);
        }
        for (var i = 0; i < chromaOffset.length; i++) {
            System.arraycopy(other.chromaOffset[i], 0, chromaOffset[i], 0, chromaOffset[i].length);
        }
        for (var i = 0; i < chromaWeightFlag.length; i++) {
            System.arraycopy(other.chromaWeightFlag[i], 0, chromaWeightFlag[i], 0, chromaWeightFlag[i].length);
        }
        noOutputOfPriorPics = other.noOutputOfPriorPics;
        longTermReference = other.longTermReference;
        adaptiveRefPicMarking = other.adaptiveRefPicMarking;
        mmcoCount = other.mmcoCount;
        System.arraycopy(other.mmcoOp, 0, mmcoOp, 0, mmcoOp.length);
        System.arraycopy(other.mmcoArg1, 0, mmcoArg1, 0, mmcoArg1.length);
        System.arraycopy(other.mmcoArg2, 0, mmcoArg2, 0, mmcoArg2.length);
        cabacInitIdc = other.cabacInitIdc;
        sliceQp = other.sliceQp;
        spForSwitch = other.spForSwitch;
        sliceQs = other.sliceQs;
        sliceId = other.sliceId;
        disableDeblockingFilterIdc = other.disableDeblockingFilterIdc;
        filterOffsetA = other.filterOffsetA;
        filterOffsetB = other.filterOffsetB;
        sliceGroupChangeCycle = other.sliceGroupChangeCycle;
        dataBitPosition = other.dataBitPosition;
    }
}
