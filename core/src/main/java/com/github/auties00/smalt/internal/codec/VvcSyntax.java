package com.github.auties00.smalt.internal.codec;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ChromaSiting;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.internal.bits.MsbBitReader;
import com.github.auties00.smalt.internal.nal.NalUnits;
import com.github.auties00.smalt.track.Rational;

import java.util.Arrays;
import java.util.Map;

/**
 * Reads the fields of VVC parameter sets and picture headers that describe, order and time the pictures of a stream
 * without decoding it (ITU-T H.266 clauses 7.3.2.4, 7.3.2.5, 7.3.2.8, 7.3.3, 7.3.10 and Rec. ITU-T H.274 clause
 * 7.2): the profile, tier and level, the picture size, color and sample aspect ratio, the frame rate, the reorder
 * bounds and the picture order count.
 *
 * <p>Every method returns {@code null} for a unit it cannot read. Units are whole NAL units, two-byte header
 * included, with their emulation prevention bytes.
 */
public final class VvcSyntax {
    /**
     * The NAL unit type of an IDR picture with leading pictures.
     */
    public static final int IDR_W_RADL = 7;

    /**
     * The NAL unit type of an IDR picture without leading pictures.
     */
    public static final int IDR_N_LP = 8;

    /**
     * The NAL unit type of a clean random access picture.
     */
    public static final int CRA = 9;

    /**
     * The NAL unit type of a gradual decoding refresh picture.
     */
    public static final int GDR = 10;

    /**
     * The NAL unit type of operating point information.
     */
    public static final int OPI = 12;

    /**
     * The NAL unit type of decoding capability information.
     */
    public static final int DCI = 13;

    /**
     * The NAL unit type of a video parameter set.
     */
    public static final int VPS = 14;

    /**
     * The NAL unit type of a sequence parameter set.
     */
    public static final int SPS = 15;

    /**
     * The NAL unit type of a picture parameter set.
     */
    public static final int PPS = 16;

    /**
     * The NAL unit type of a prefix adaptation parameter set.
     */
    public static final int PREFIX_APS = 17;

    /**
     * The NAL unit type of a picture header.
     */
    public static final int PH = 19;

    /**
     * The NAL unit type of an access unit delimiter.
     */
    public static final int AUD = 20;

    /**
     * The NAL unit type of an end of sequence.
     */
    public static final int EOS = 21;

    /**
     * The NAL unit type of an end of bitstream.
     */
    public static final int EOB = 22;

    /**
     * The NAL unit type of a prefix SEI message.
     */
    public static final int PREFIX_SEI = 23;

    /**
     * The NAL unit type of a suffix SEI message.
     */
    public static final int SUFFIX_SEI = 24;

    /**
     * The largest number of subpictures of a sequence (Table A.8 bounds it at 600).
     */
    private static final int MAX_SUBPICS = 600;

    /**
     * The largest number of entries of a reference picture list structure.
     */
    private static final int MAX_REF_ENTRIES = 29;

    /**
     * The most bytes of a NAL unit read for its picture and slice headers.
     */
    private static final int HEADER_BYTES = 4096;

    /**
     * The aspect ratios of {@code vui_aspect_ratio_idc} 1 to 16 (Rec. ITU-T H.273 Table 7).
     */
    private static final int[][] SAR_TABLE = {{1, 1}, {12, 11}, {10, 11}, {16, 11}, {40, 33}, {24, 11}, {20, 11},
            {32, 11}, {80, 33}, {18, 11}, {15, 11}, {64, 33}, {160, 99}, {4, 3}, {3, 2}, {2, 1}};

    private VvcSyntax() {
        throw new AssertionError();
    }

    /**
     * The fields of a sequence parameter set this library needs without decoding the stream.
     *
     * @param id {@code sps_seq_parameter_set_id}
     * @param vpsId {@code sps_video_parameter_set_id}
     * @param maxSubLayers {@code sps_max_sublayers_minus1 + 1}
     * @param chromaFormatIdc {@code sps_chroma_format_idc}
     * @param bitDepth {@code sps_bitdepth_minus8 + 8}
     * @param profileTierLevel the bytes of the {@code profile_tier_level( 1, sps_max_sublayers_minus1 )} structure,
     * from {@code general_profile_idc} to its end, or {@code null} when the set carries none
     * @param constraintBytes the number of bytes of the structure, from {@code ptl_frame_only_constraint_flag} to the
     * end of {@code general_constraints_info( )}, or 0 without the structure
     * @param profileIdc {@code general_profile_idc}, or 0 without the structure
     * @param tier {@code general_tier_flag}
     * @param levelIdc {@code general_level_idc}, or 0 without the structure
     * @param maxWidth {@code sps_pic_width_max_in_luma_samples}
     * @param maxHeight {@code sps_pic_height_max_in_luma_samples}
     * @param width the maximum width less the conformance window
     * @param height the maximum height less the conformance window
     * @param log2MaxPocLsb {@code sps_log2_max_pic_order_cnt_lsb_minus4 + 4}
     * @param pocMsbCycleLen {@code sps_poc_msb_cycle_len_minus1 + 1}, or 0 when {@code sps_poc_msb_cycle_flag} is
     * zero
     * @param extraPhBits {@code NumExtraPhBits}
     * @param alf {@code sps_alf_enabled_flag}
     * @param ccalf {@code sps_ccalf_enabled_flag}
     * @param lmcs {@code sps_lmcs_enabled_flag}
     * @param explicitScalingList {@code sps_explicit_scaling_list_enabled_flag}
     * @param virtualBoundariesInPh whether {@code sps_virtual_boundaries_enabled_flag} is set and
     * {@code sps_virtual_boundaries_present_flag} is not
     * @param reorderPictures {@code dpb_max_num_reorder_pics} of the highest sublayer, or -1 without DPB parameters
     * @param latencyIncreasePlus1 {@code dpb_max_latency_increase_plus1} of the highest sublayer
     * @param maxDecPicBuffering {@code dpb_max_dec_pic_buffering_minus1 + 1} of the highest sublayer, or 0
     * @param timingUnits {@code num_units_in_tick} times the {@code elemental_duration_in_tc_minus1 + 1} of the
     * highest sublayer when its picture rate is fixed, or 0 without timing information
     * @param timingScale {@code time_scale}, or 0 without timing information
     * @param constantRate whether the timing information fixes the picture rate
     * @param fieldSeq {@code sps_field_seq_flag}: every picture is a field
     * @param color the color description of the VUI, or {@code null} when it signals none
     * @param sampleAspect the sample aspect ratio of the VUI, or {@code null} when it signals none
     * @param tools the fields the picture and slice headers depend on
     */
    public record Sps(int id, int vpsId, int maxSubLayers, int chromaFormatIdc, int bitDepth, byte[] profileTierLevel,
                      int constraintBytes, int profileIdc, int tier, int levelIdc, int maxWidth, int maxHeight,
                      int width, int height, int log2MaxPocLsb, int pocMsbCycleLen, int extraPhBits, boolean alf,
                      boolean ccalf, boolean lmcs, boolean explicitScalingList, boolean virtualBoundariesInPh,
                      int reorderPictures, int latencyIncreasePlus1, int maxDecPicBuffering, long timingUnits,
                      long timingScale, boolean constantRate, boolean fieldSeq, ColorInfo color,
                      Rational sampleAspect, SliceTools tools) {
        /**
         * {@return the chroma format}
         */
        public ChromaFormat chroma() {
            return switch (chromaFormatIdc) {
                case 0 -> ChromaFormat.MONOCHROME;
                case 2 -> ChromaFormat.YUV422;
                case 3 -> ChromaFormat.YUV444;
                default -> ChromaFormat.YUV420;
            };
        }

        /**
         * {@return the picture rate of the timing information, or {@code null} without one}
         */
        public Rational pictureRate() {
            return timingUnits > 0 && timingScale > 0 ? new Rational(timingScale, timingUnits).reduce() : null;
        }
    }

    /**
     * The subpicture layout of a sequence parameter set, in CTUs.
     *
     * @param present {@code sps_subpic_info_present_flag}
     * @param idLength {@code sps_subpic_id_len_minus1 + 1}, or 0 without subpicture information
     * @param ids {@code sps_subpic_id} by subpicture index (-1 where the picture parameter set signals it) when
     * {@code sps_subpic_id_mapping_explicitly_signalled_flag} is set, or {@code null} when the identifiers are the
     * indexes
     * @param x the left CTU column of each subpicture
     * @param y the top CTU row of each subpicture
     * @param width the width in CTUs of each subpicture
     * @param height the height in CTUs of each subpicture
     */
    public record Subpictures(boolean present, int idLength, int[] ids, int[] x, int[] y, int[] width, int[] height) {
        /**
         * {@return the number of subpictures}
         */
        public int count() {
            return x.length;
        }

        /**
         * {@return the index of the subpicture holding a CTU, or -1}
         *
         * @param column the CTU column
         * @param row the CTU row
         */
        public int indexOf(int column, int row) {
            for (var i = 0; i < x.length; i++) {
                if (column >= x[i] && column < x[i] + width[i] && row >= y[i] && row < y[i] + height[i]) {
                    return i;
                }
            }
            return -1;
        }
    }

    /**
     * A {@code ref_pic_list_struct} of a sequence parameter set, picture header or slice header.
     *
     * @param ltrpInHeader {@code ltrp_in_header_flag}: the long-term entries' order count bits follow in the
     * header that selects the structure
     * @param kinds {@link #SHORT_TERM}, {@link #LONG_TERM} or {@link #INTER_LAYER} for each entry
     * @param values for a short-term entry the sum of the signed order count deltas through it (the reference's
     * order count less the picture's); for a long-term entry its {@code rpls_poc_lsb_lt}, or -1 when the header
     * carries it; for an inter-layer entry its {@code ilrp_idx}
     */
    public record RefList(boolean ltrpInHeader, byte[] kinds, int[] values) {
        /**
         * The kind of a short-term reference entry.
         */
        public static final byte SHORT_TERM = 0;

        /**
         * The kind of a long-term reference entry.
         */
        public static final byte LONG_TERM = 1;

        /**
         * The kind of an inter-layer reference entry.
         */
        public static final byte INTER_LAYER = 2;

        /**
         * {@return the number of long-term entries}
         */
        int longTermCount() {
            var count = 0;
            for (var kind : kinds) {
                count += kind == LONG_TERM ? 1 : 0;
            }
            return count;
        }
    }

    /**
     * The fields of a sequence parameter set its picture and slice headers depend on.
     *
     * @param log2Ctu {@code CtbLog2SizeY}
     * @param log2MinCb {@code MinCbLog2SizeY}
     * @param subpictures the subpicture layout; one subpicture covering the picture without subpicture
     * information
     * @param extraShBits {@code NumExtraShBits}
     * @param partitionOverride {@code sps_partition_constraints_override_enabled_flag}
     * @param dualTree {@code sps_qtbtt_dual_tree_intra_flag}
     * @param jointCbCr {@code sps_joint_cbcr_enabled_flag}
     * @param sao {@code sps_sao_enabled_flag}
     * @param longTermRefs {@code sps_long_term_ref_pics_flag}
     * @param interLayer {@code sps_inter_layer_prediction_enabled_flag}
     * @param idrRplPresent {@code sps_idr_rpl_present_flag}
     * @param weighted whether {@code sps_weighted_pred_flag} or {@code sps_weighted_bipred_flag} is set
     * @param rplLists the candidate reference picture list structures of each list
     * @param temporalMvp {@code sps_temporal_mvp_enabled_flag}
     * @param mmvdFullpel {@code sps_mmvd_fullpel_only_enabled_flag}
     * @param bdofControl {@code sps_bdof_control_present_in_ph_flag}
     * @param dmvrControl {@code sps_dmvr_control_present_in_ph_flag}
     * @param profControl {@code sps_prof_control_present_in_ph_flag}
     */
    public record SliceTools(int log2Ctu, int log2MinCb, Subpictures subpictures, int extraShBits,
                             boolean partitionOverride, boolean dualTree, boolean jointCbCr, boolean sao,
                             boolean longTermRefs, boolean interLayer, boolean idrRplPresent, boolean weighted,
                             RefList[][] rplLists, boolean temporalMvp, boolean mmvdFullpel, boolean bdofControl,
                             boolean dmvrControl, boolean profControl) {
    }

    /**
     * The fields of a picture parameter set its picture and slice headers depend on, inferred values included when
     * the set does not signal them.
     *
     * @param tiles {@code NumTilesInPic}
     * @param rectSlice {@code pps_rect_slice_flag}
     * @param singleSlicePerSubpic {@code pps_single_slice_per_subpic_flag}
     * @param sliceColumns the left CTU column of each rectangular slice, or {@code null} when every subpicture is
     * one slice or the slices are raster-scan slices
     * @param sliceRows the top CTU row of each rectangular slice, or {@code null} as for {@code sliceColumns}
     * @param subpicIds {@code pps_subpic_id} when {@code pps_subpic_id_mapping_present_flag} is set, or
     * {@code null}
     * @param rpl1IdxPresent {@code pps_rpl1_idx_present_flag}
     * @param weightedPred {@code pps_weighted_pred_flag}
     * @param weightedBipred {@code pps_weighted_bipred_flag}
     * @param cuQpDelta {@code pps_cu_qp_delta_enabled_flag}
     * @param cuChromaQpOffsetList {@code pps_cu_chroma_qp_offset_list_enabled_flag}
     * @param chromaToolOffsets {@code pps_chroma_tool_offsets_present_flag}
     * @param deblockingDisabled {@code pps_deblocking_filter_disabled_flag}
     * @param dbfInfoInPh {@code pps_dbf_info_in_ph_flag}
     * @param rplInfoInPh {@code pps_rpl_info_in_ph_flag}
     * @param saoInfoInPh {@code pps_sao_info_in_ph_flag}
     * @param wpInfoInPh {@code pps_wp_info_in_ph_flag}
     * @param qpDeltaInfoInPh {@code pps_qp_delta_info_in_ph_flag}
     * @param pictureHeaderExtension {@code pps_picture_header_extension_present_flag}
     */
    public record PpsTools(int tiles, boolean rectSlice, boolean singleSlicePerSubpic, int[] sliceColumns,
                           int[] sliceRows, int[] subpicIds, boolean rpl1IdxPresent, boolean weightedPred,
                           boolean weightedBipred, boolean cuQpDelta, boolean cuChromaQpOffsetList,
                           boolean chromaToolOffsets, boolean deblockingDisabled, boolean dbfInfoInPh,
                           boolean rplInfoInPh, boolean saoInfoInPh, boolean wpInfoInPh, boolean qpDeltaInfoInPh,
                           boolean pictureHeaderExtension) {
    }

    /**
     * The reference picture lists of a picture, the entries of both lists together, with what is needed to tell
     * which earlier pictures they keep as references (clause 8.3.2).
     *
     * @param shortTerm the order count of each short-term entry less the picture's
     * @param longTermLsb the {@code PocLsbLt} of each long-term entry
     * @param longTermMsbCycle the {@code DeltaPocMsbCycleLt} of each long-term entry, -1 when
     * {@code delta_poc_msb_cycle_present_flag} is zero
     */
    public record References(int[] shortTerm, int[] longTermLsb, int[] longTermMsbCycle) {
        /**
         * The lists of a picture without references.
         */
        public static final References NONE = new References(new int[0], new int[0], new int[0]);

        /**
         * Returns whether an earlier picture of the coded video sequence stays a reference picture for a picture
         * with these lists.
         *
         * @param other the order count of the earlier picture
         * @param poc the order count of the picture
         * @param log2MaxPocLsb {@code sps_log2_max_pic_order_cnt_lsb_minus4 + 4}
         * @return {@code true} when an entry names the earlier picture
         */
        public boolean keeps(long other, long poc, int log2MaxPocLsb) {
            for (var delta : shortTerm) {
                if (poc + delta == other) {
                    return true;
                }
            }
            var maxLsb = 1L << log2MaxPocLsb;
            for (var i = 0; i < longTermLsb.length; i++) {
                if (longTermMsbCycle[i] < 0) {
                    if ((other & (maxLsb - 1)) == longTermLsb[i]) {
                        return true;
                    }
                } else if (poc - longTermMsbCycle[i] * maxLsb - (poc & (maxLsb - 1)) + longTermLsb[i] == other) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * The fields of the first slice of a picture this library needs.
     *
     * @param header the picture header, from the slice header or the picture header NAL unit
     * @param noOutputOfPriorPics {@code sh_no_output_of_prior_pics_flag}, {@code false} for a picture that is not an
     * IRAP or GDR picture
     * @param references the reference picture lists
     */
    public record Slice(PictureHeader header, boolean noOutputOfPriorPics, References references) {
    }

    /**
     * The fields of a video parameter set that describe one of its output layer sets.
     *
     * @param id {@code vps_video_parameter_set_id}
     * @param maxLayers {@code vps_max_layers_minus1 + 1}
     * @param olsIndex the index of the output layer set
     * @param layerIds the {@code nuh_layer_id} of every layer of the set, in increasing layer index
     * @param profileTierLevel the profile, tier and level structure of the set in the layout of a
     * {@code VvcPTLRecord} after its {@code num_bytes_constraint_info} byte, profile and tier inferred from the
     * previous structure when the VPS does not repeat them
     * @param constraintBytes {@code num_bytes_constraint_info}: the bytes from {@code ptl_frame_only_constraint_flag}
     * through the general constraint information
     * @param maxSubLayers {@code vps_ptl_max_tid + 1} of the structure
     * @param width {@code vps_ols_dpb_pic_width} of a set of several layers, 0 for a single-layer set
     * @param height {@code vps_ols_dpb_pic_height} of a set of several layers, 0 for a single-layer set
     * @param chromaFormatIdc {@code vps_ols_dpb_chroma_format} of a set of several layers, -1 for a single-layer set
     * @param bitDepth {@code vps_ols_dpb_bitdepth_minus8 + 8} of a set of several layers, 0 for a single-layer set
     */
    public record Vps(int id, int maxLayers, int olsIndex, int[] layerIds, byte[] profileTierLevel,
                      int constraintBytes, int maxSubLayers, int width, int height, int chromaFormatIdc,
                      int bitDepth) {
    }

    /**
     * The fields of a picture parameter set this library needs.
     *
     * @param id {@code pps_pic_parameter_set_id}
     * @param spsId {@code pps_seq_parameter_set_id}
     * @param mixedNaluTypes {@code pps_mixed_nalu_types_in_pic_flag}
     * @param outputFlagPresent {@code pps_output_flag_present_flag}
     * @param alfInfoInPh {@code pps_alf_info_in_ph_flag}
     * @param tools the fields the picture and slice headers depend on, or {@code null} when the rest of the set does
     * not parse
     */
    public record Pps(int id, int spsId, boolean mixedNaluTypes, boolean outputFlagPresent, boolean alfInfoInPh,
                      PpsTools tools) {
    }

    /**
     * The fields of a picture header this library needs.
     *
     * @param sps the sequence parameter set of the picture
     * @param gdrOrIrap {@code ph_gdr_or_irap_pic_flag}
     * @param nonReference {@code ph_non_ref_pic_flag}
     * @param gdr {@code ph_gdr_pic_flag}
     * @param pocLsb {@code ph_pic_order_cnt_lsb}
     * @param recoveryPocCount {@code ph_recovery_poc_cnt}, or 0 for a picture that is not a GDR picture
     * @param pocMsb {@code ph_poc_msb_cycle_val}, or -1 when absent
     * @param output {@code ph_pic_output_flag}, inferred as {@code true} when absent
     * @param mixedNaluTypes the {@code pps_mixed_nalu_types_in_pic_flag} of the picture parameter set
     * @param pps the picture parameter set of the picture
     * @param interAllowed {@code ph_inter_slice_allowed_flag}
     * @param lmcs {@code ph_lmcs_enabled_flag}
     * @param scalingList {@code ph_explicit_scaling_list_enabled_flag}
     * @param complete whether the whole structure was read, which reading the slice header that follows needs
     * @param references the reference picture lists the picture header carries, or {@code null} when the slice
     * headers carry them or the structure is incomplete
     */
    public record PictureHeader(Sps sps, boolean gdrOrIrap, boolean nonReference, boolean gdr, int pocLsb,
                                int recoveryPocCount, int pocMsb, boolean output, boolean mixedNaluTypes, Pps pps,
                                boolean interAllowed, boolean lmcs, boolean scalingList, boolean complete,
                                References references) {
    }

    /**
     * {@return the {@code nal_unit_type} of a NAL unit}
     *
     * @param data the bytes
     * @param offset the index of the two-byte NAL unit header
     */
    public static int type(byte[] data, int offset) {
        return (data[offset + 1] & 0xFF) >> 3;
    }

    /**
     * {@return the {@code nuh_layer_id} of a NAL unit}
     *
     * @param data the bytes
     * @param offset the index of the two-byte NAL unit header
     */
    public static int layer(byte[] data, int offset) {
        return data[offset] & 0x3F;
    }

    /**
     * {@return the {@code TemporalId} of a NAL unit, -1 for an invalid {@code nuh_temporal_id_plus1} of zero}
     *
     * @param data the bytes
     * @param offset the index of the two-byte NAL unit header
     */
    public static int temporalId(byte[] data, int offset) {
        return (data[offset + 1] & 7) - 1;
    }

    /**
     * {@return whether a NAL unit type holds a coded slice, reserved VCL types included}
     *
     * @param type the NAL unit type
     */
    public static boolean isSlice(int type) {
        return type <= 11;
    }

    /**
     * {@return whether a NAL unit type is an IRAP picture: IDR or CRA}
     *
     * @param type the NAL unit type
     */
    public static boolean isIrap(int type) {
        return type >= IDR_W_RADL && type <= CRA;
    }

    /**
     * {@return the identifier of a parameter set, or -1 for another unit}
     *
     * <p>{@code vps_video_parameter_set_id} and {@code sps_seq_parameter_set_id} are four bits, and
     * {@code pps_pic_parameter_set_id} six bits, at the start of the payload.
     *
     * @param data the bytes
     * @param offset the index of the NAL unit header
     * @param length the NAL unit length
     */
    public static int parameterSetId(byte[] data, int offset, int length) {
        if (length < 3) {
            return -1;
        }
        var first = data[offset + 2] & 0xFF;
        return switch (type(data, offset)) {
            case VPS, SPS -> first >> 4;
            case PPS -> first >> 2;
            default -> -1;
        };
    }

    /**
     * Reads a video parameter set through the decoded picture buffer sizes of its multi-layer output layer sets
     * (clause 7.3.2.3), and picks the output layer set a track carrying every layer of the stream represents: the
     * one with the most layers, the lowest index among equals.
     *
     * @param data the bytes
     * @param offset the index of the NAL unit header
     * @param length the NAL unit length
     * @return the fields, or {@code null}
     */
    public static Vps vps(byte[] data, int offset, int length) {
        if (length < 4) {
            return null;
        }
        try {
            var bits = new MsbBitReader(NalUnits.unescape(data, offset + 2, length - 2));
            var id = bits.read(4);
            var maxLayers = bits.read(6) + 1;
            var maxSubLayersMinus1 = bits.read(3);
            if (maxSubLayersMinus1 > 6) {
                return null;
            }
            var defaultMaxTid = maxLayers <= 1 || maxSubLayersMinus1 <= 0 || bits.readFlag();
            var allIndependent = maxLayers <= 1 || bits.readFlag();
            var layerIds = new int[maxLayers];
            var direct = new boolean[maxLayers][maxLayers];
            for (var i = 0; i < maxLayers; i++) {
                layerIds[i] = bits.read(6);
                if (i > 0 && !allIndependent && !bits.readFlag()) {
                    var maxTidPresent = bits.readFlag();
                    for (var j = 0; j < i; j++) {
                        direct[i][j] = bits.readFlag();
                        if (maxTidPresent && direct[i][j]) {
                            bits.skip(3);
                        }
                    }
                }
            }
            var eachLayerIsOls = maxLayers == 1;
            var mode = 2;
            var olsCount = maxLayers;
            boolean[][] outputFlags = null;
            if (maxLayers > 1) {
                eachLayerIsOls = allIndependent && bits.readFlag();
                if (!eachLayerIsOls) {
                    if (!allIndependent) {
                        mode = bits.read(2);
                    }
                    if (mode == 2) {
                        olsCount = bits.read(8) + 2;
                        outputFlags = new boolean[olsCount][maxLayers];
                        for (var i = 1; i < olsCount; i++) {
                            for (var j = 0; j < maxLayers; j++) {
                                outputFlags[i][j] = bits.readFlag();
                            }
                        }
                    } else if (mode == 3) {
                        return null;
                    }
                }
            }
            var olsLayers = olsLayers(maxLayers, eachLayerIsOls, mode, olsCount, direct, outputFlags);
            var ptlCount = bits.read(8) + 1;
            var ptPresent = new boolean[ptlCount];
            var ptlMaxTid = new int[ptlCount];
            for (var i = 0; i < ptlCount; i++) {
                ptPresent[i] = i == 0 || bits.readFlag();
                ptlMaxTid[i] = defaultMaxTid ? maxSubLayersMinus1 : bits.read(3);
                if (ptlMaxTid[i] > maxSubLayersMinus1) {
                    return null;
                }
            }
            bits.alignToByte();
            var ptls = new byte[ptlCount][];
            var constraintBytes = new int[ptlCount];
            for (var i = 0; i < ptlCount; i++) {
                var profileTier = ptPresent[i] ? bits.read(8) : ptls[i - 1][0] & 0xFF;
                var level = bits.read(8);
                var flags = bits.read(2);
                var record = new java.io.ByteArrayOutputStream();
                record.write(profileTier);
                record.write(level);
                if (ptPresent[i]) {
                    var start = bits.bitPosition();
                    if (bits.readFlag()) {
                        bits.skip(71);
                        bits.skip(bits.read(8));
                    }
                    bits.alignToByte();
                    var gciBits = (int) (bits.bitPosition() - start);
                    bits.seek(start);
                    var first = bits.read(Math.min(6, gciBits));
                    record.write((flags << 6) | (first << (6 - Math.min(6, gciBits))));
                    for (var remaining = gciBits - 6; remaining > 0; remaining -= 8) {
                        record.write(bits.read(8));
                    }
                    constraintBytes[i] = (gciBits + 2) >> 3;
                } else {
                    var previous = ptls[i - 1];
                    var gci = Arrays.copyOfRange(previous, 2, 2 + constraintBytes[i - 1]);
                    gci[0] = (byte) ((flags << 6) | (gci[0] & 0x3F));
                    record.writeBytes(gci);
                    constraintBytes[i] = constraintBytes[i - 1];
                }
                var levelPresent = new boolean[ptlMaxTid[i]];
                var presentBits = 0;
                for (var k = ptlMaxTid[i] - 1; k >= 0; k--) {
                    levelPresent[k] = bits.readFlag();
                    presentBits = (presentBits << 1) | (levelPresent[k] ? 1 : 0);
                }
                bits.alignToByte();
                if (ptlMaxTid[i] > 0) {
                    record.write(presentBits << (8 - ptlMaxTid[i]));
                }
                for (var k = ptlMaxTid[i] - 1; k >= 0; k--) {
                    if (levelPresent[k]) {
                        record.write(bits.read(8));
                    }
                }
                if (ptPresent[i]) {
                    var subProfiles = bits.read(8);
                    record.write(subProfiles);
                    for (var k = 0; k < 4 * subProfiles; k++) {
                        record.write(bits.read(8));
                    }
                } else {
                    record.writeBytes(subProfiles(ptls[i - 1], constraintBytes[i - 1], ptlMaxTid[i - 1]));
                }
                if (bits.overrun()) {
                    return null;
                }
                ptls[i] = record.toByteArray();
            }
            var olsPtl = new int[olsCount];
            if (ptlCount > 1 && ptlCount != olsCount) {
                for (var i = 0; i < olsCount; i++) {
                    olsPtl[i] = bits.read(8);
                    if (olsPtl[i] >= ptlCount) {
                        return null;
                    }
                }
            } else if (ptlCount > 1) {
                for (var i = 0; i < olsCount; i++) {
                    olsPtl[i] = i;
                }
            }
            var chosen = 0;
            for (var i = 1; i < olsCount; i++) {
                if (olsLayers[i].length > olsLayers[chosen].length) {
                    chosen = i;
                }
            }
            var width = 0;
            var height = 0;
            var chroma = -1;
            var bitDepth = 0;
            if (!eachLayerIsOls) {
                var dpbCount = bits.readUe() + 1;
                var multiLayerOlss = 0;
                var chosenMulti = -1;
                for (var i = 0; i < olsCount; i++) {
                    if (olsLayers[i].length > 1) {
                        if (i == chosen) {
                            chosenMulti = multiLayerOlss;
                        }
                        multiLayerOlss++;
                    }
                }
                if (dpbCount > 64) {
                    return null;
                }
                var subLayerInfo = maxSubLayersMinus1 > 0 && bits.readFlag();
                for (var i = 0; i < dpbCount; i++) {
                    var maxTid = defaultMaxTid ? maxSubLayersMinus1 : bits.read(3);
                    for (var k = subLayerInfo ? 0 : maxTid; k <= maxTid; k++) {
                        bits.readUe();
                        bits.readUe();
                        bits.readUe();
                    }
                }
                for (var i = 0; i < multiLayerOlss; i++) {
                    var olsWidth = bits.readUe();
                    var olsHeight = bits.readUe();
                    var olsChroma = bits.read(2);
                    var olsBitDepth = bits.readUe() + 8;
                    if (dpbCount > 1 && dpbCount != multiLayerOlss) {
                        bits.readUe();
                    }
                    if (i == chosenMulti) {
                        width = olsWidth;
                        height = olsHeight;
                        chroma = olsChroma;
                        bitDepth = olsBitDepth;
                    }
                }
            }
            if (bits.overrun()) {
                return null;
            }
            var ptlIndex = olsPtl[chosen];
            var layers = new int[olsLayers[chosen].length];
            for (var i = 0; i < layers.length; i++) {
                layers[i] = layerIds[olsLayers[chosen][i]];
            }
            return new Vps(id, maxLayers, chosen, layers, ptls[ptlIndex], constraintBytes[ptlIndex],
                    ptlMaxTid[ptlIndex] + 1, width, height, chroma, bitDepth);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    /**
     * Reads a sequence parameter set through its VUI.
     *
     * @param data the bytes
     * @param offset the index of the NAL unit header
     * @param length the NAL unit length
     * @return the fields, or {@code null}
     */
    public static Sps sps(byte[] data, int offset, int length) {
        if (length < 4) {
            return null;
        }
        try {
            var rbsp = NalUnits.unescape(data, offset + 2, length - 2);
            var bits = new MsbBitReader(rbsp);
            var id = bits.read(4);
            var vpsId = bits.read(4);
            var maxSubLayers = bits.read(3) + 1;
            var chromaFormatIdc = bits.read(2);
            var log2Ctu = bits.read(2) + 5;
            if (maxSubLayers > 7 || log2Ctu > 7) {
                return null;
            }
            var ptlPresent = bits.readFlag();
            byte[] ptl = null;
            var constraintBytes = 0;
            var profileIdc = 0;
            var tier = 0;
            var levelIdc = 0;
            if (ptlPresent) {
                var start = (int) (bits.bitPosition() >> 3);
                profileIdc = bits.read(7);
                tier = bits.read(1);
                levelIdc = bits.read(8);
                bits.skip(2);
                if (bits.readFlag()) {
                    bits.skip(71);
                    bits.skip(bits.read(8));
                }
                bits.alignToByte();
                constraintBytes = (int) (bits.bitPosition() >> 3) - start - 2;
                var levelPresent = new boolean[maxSubLayers];
                for (var i = maxSubLayers - 2; i >= 0; i--) {
                    levelPresent[i] = bits.readFlag();
                }
                bits.alignToByte();
                for (var i = maxSubLayers - 2; i >= 0; i--) {
                    if (levelPresent[i]) {
                        bits.skip(8);
                    }
                }
                bits.skip(32L * bits.read(8));
                if (bits.overrun()) {
                    return null;
                }
                ptl = Arrays.copyOfRange(rbsp, start, (int) (bits.bitPosition() >> 3));
            }
            bits.readFlag();
            if (bits.readFlag()) {
                bits.readFlag();
            }
            var maxWidth = bits.readUe();
            var maxHeight = bits.readUe();
            var subWidth = chromaFormatIdc == 1 || chromaFormatIdc == 2 ? 2 : 1;
            var subHeight = chromaFormatIdc == 1 ? 2 : 1;
            var width = maxWidth;
            var height = maxHeight;
            if (bits.readFlag()) {
                var left = bits.readUe();
                var right = bits.readUe();
                var top = bits.readUe();
                var bottom = bits.readUe();
                width -= subWidth * (left + right);
                height -= subHeight * (top + bottom);
            }
            var ctu = 1 << log2Ctu;
            var widthInCtus = (maxWidth + ctu - 1) >> log2Ctu;
            var heightInCtus = (maxHeight + ctu - 1) >> log2Ctu;
            var subpictures = bits.readFlag()
                    ? readSubpictures(bits, maxWidth, maxHeight, ctu, widthInCtus, heightInCtus)
                    : new Subpictures(false, 0, null, new int[]{0}, new int[]{0}, new int[]{widthInCtus},
                    new int[]{heightInCtus});
            if (subpictures == null) {
                return null;
            }
            var bitDepth = bits.readUe() + 8;
            bits.skip(2);
            var log2MaxPocLsb = bits.read(4) + 4;
            var pocMsbCycleLen = bits.readFlag() ? bits.readUe() + 1 : 0;
            var extraPhBits = 0;
            var extraPhBytes = bits.read(2);
            for (var i = 0; i < 8 * extraPhBytes; i++) {
                extraPhBits += bits.read(1);
            }
            var extraShBits = 0;
            var extraShBytes = bits.read(2);
            for (var i = 0; i < 8 * extraShBytes; i++) {
                extraShBits += bits.read(1);
            }
            var reorder = -1;
            var latency = 0;
            var buffering = 0;
            if (ptlPresent) {
                var subLayerInfo = maxSubLayers > 1 && bits.readFlag();
                for (var i = subLayerInfo ? 0 : maxSubLayers - 1; i < maxSubLayers; i++) {
                    buffering = bits.readUe() + 1;
                    reorder = bits.readUe();
                    latency = bits.readUe();
                }
            }
            var log2MinCb = bits.readUe() + 2;
            var partitionOverride = bits.readFlag();
            var minQtIntra = bits.readUe() + log2MinCb;
            if (bits.readUe() != 0) {
                bits.readUe();
                bits.readUe();
            }
            var dualTree = chromaFormatIdc != 0 && bits.readFlag();
            if (dualTree) {
                bits.readUe();
                if (bits.readUe() != 0) {
                    bits.readUe();
                    bits.readUe();
                }
            }
            bits.readUe();
            if (bits.readUe() != 0) {
                bits.readUe();
                bits.readUe();
            }
            if (minQtIntra < 0) {
                return null;
            }
            var maxTransform64 = ctu > 32 && bits.readFlag();
            var transformSkip = bits.readFlag();
            if (transformSkip) {
                bits.readUe();
                bits.readFlag();
            }
            if (bits.readFlag()) {
                bits.skip(2);
            }
            var lfnst = bits.readFlag();
            var jointCbCr = false;
            if (chromaFormatIdc != 0) {
                jointCbCr = bits.readFlag();
                var tables = bits.readFlag() ? 1 : jointCbCr ? 3 : 2;
                for (var i = 0; i < tables; i++) {
                    bits.readSe();
                    var points = bits.readUe() + 1;
                    if (points > 64) {
                        return null;
                    }
                    for (var j = 0; j < points; j++) {
                        bits.readUe();
                        bits.readUe();
                    }
                }
            }
            var sao = bits.readFlag();
            var alf = bits.readFlag();
            var ccalf = alf && chromaFormatIdc != 0 && bits.readFlag();
            var lmcs = bits.readFlag();
            var weightedPred = bits.readFlag();
            var weightedBipred = bits.readFlag();
            var longTermRefs = bits.readFlag();
            var interLayer = vpsId > 0 && bits.readFlag();
            var idrRplPresent = bits.readFlag();
            var rpl1SameAsRpl0 = bits.readFlag();
            var weighted = weightedPred || weightedBipred;
            var rplLists = new RefList[2][];
            for (var list = 0; list < (rpl1SameAsRpl0 ? 1 : 2); list++) {
                var count = bits.readUe();
                if (count > 64) {
                    return null;
                }
                rplLists[list] = new RefList[count];
                for (var j = 0; j < count; j++) {
                    rplLists[list][j] = readRefPicList(bits, longTermRefs, interLayer, weighted, log2MaxPocLsb, true);
                    if (rplLists[list][j] == null) {
                        return null;
                    }
                }
            }
            if (rpl1SameAsRpl0) {
                rplLists[1] = rplLists[0];
            }
            bits.readFlag();
            var temporalMvp = bits.readFlag();
            if (temporalMvp) {
                bits.readFlag();
            }
            var amvr = bits.readFlag();
            var bdofControl = bits.readFlag() && bits.readFlag();
            bits.readFlag();
            var dmvrControl = bits.readFlag() && bits.readFlag();
            var mmvdFullpel = bits.readFlag() && bits.readFlag();
            var maxMergeCand = 6 - bits.readUe();
            bits.readFlag();
            var profControl = false;
            if (bits.readFlag()) {
                bits.readUe();
                bits.readFlag();
                if (amvr) {
                    bits.readFlag();
                }
                profControl = bits.readFlag() && bits.readFlag();
            }
            bits.readFlag();
            bits.readFlag();
            if (maxMergeCand >= 2 && bits.readFlag() && maxMergeCand >= 3) {
                bits.readUe();
            }
            bits.readUe();
            bits.skip(3);
            if (chromaFormatIdc != 0) {
                bits.readFlag();
            }
            if (chromaFormatIdc == 1) {
                bits.skip(2);
            }
            var palette = bits.readFlag();
            var act = chromaFormatIdc == 3 && !maxTransform64 && bits.readFlag();
            if (transformSkip || palette) {
                bits.readUe();
            }
            if (bits.readFlag()) {
                bits.readUe();
            }
            if (bits.readFlag()) {
                var intervals = bits.read(2) + 2;
                bits.readSe();
                for (var i = 0; i < intervals - 1; i++) {
                    bits.readSe();
                    bits.readUe();
                }
            }
            var explicitScaling = bits.readFlag();
            if (lfnst && explicitScaling) {
                bits.readFlag();
            }
            if (act && explicitScaling && bits.readFlag()) {
                bits.readFlag();
            }
            bits.skip(2);
            var virtualBoundariesInPh = false;
            if (bits.readFlag()) {
                if (bits.readFlag()) {
                    var vertical = bits.readUe();
                    if (vertical > 3) {
                        return null;
                    }
                    for (var i = 0; i < vertical; i++) {
                        bits.readUe();
                    }
                    var horizontal = bits.readUe();
                    if (horizontal > 3) {
                        return null;
                    }
                    for (var i = 0; i < horizontal; i++) {
                        bits.readUe();
                    }
                } else {
                    virtualBoundariesInPh = true;
                }
            }
            long timingUnits = 0;
            long timingScale = 0;
            var constant = false;
            if (ptlPresent && bits.readFlag()) {
                var timing = readTiming(bits, maxSubLayers);
                if (timing == null) {
                    return null;
                }
                timingUnits = timing[0];
                timingScale = timing[1];
                constant = timing[2] != 0;
            }
            var fieldSeq = bits.readFlag();
            ColorInfo color = null;
            Rational sar = null;
            if (bits.readFlag()) {
                var payloadSize = bits.readUe() + 1;
                bits.alignToByte();
                var start = (int) (bits.bitPosition() >> 3);
                if (start + payloadSize > rbsp.length) {
                    return null;
                }
                var vui = new MsbBitReader(rbsp, start, payloadSize);
                var progressive = vui.readFlag();
                var interlaced = vui.readFlag();
                vui.skip(2);
                if (vui.readFlag()) {
                    vui.readFlag();
                    var idc = vui.read(8);
                    if (idc == 255) {
                        var w = vui.read(16);
                        var h = vui.read(16);
                        sar = w > 0 && h > 0 ? new Rational(w, h).reduce() : null;
                    } else if (idc >= 1 && idc <= SAR_TABLE.length) {
                        sar = new Rational(SAR_TABLE[idc - 1][0], SAR_TABLE[idc - 1][1]);
                    }
                }
                if (vui.readFlag()) {
                    vui.readFlag();
                }
                var signaled = false;
                var primaries = 2;
                var transfer = 2;
                var matrix = 2;
                var fullRange = false;
                if (vui.readFlag()) {
                    primaries = vui.read(8);
                    transfer = vui.read(8);
                    matrix = vui.read(8);
                    fullRange = vui.readFlag();
                    signaled = true;
                }
                var siting = ChromaSiting.UNSPECIFIED;
                if (vui.readFlag()) {
                    siting = ChromaSiting.of(vui.readUe());
                    if (!progressive || interlaced) {
                        vui.readUe();
                    }
                    signaled = true;
                }
                if (vui.overrun()) {
                    sar = null;
                } else if (signaled) {
                    color = new ColorInfo(MatrixCoefficients.of(matrix), fullRange ? ColorRange.FULL
                            : ColorRange.LIMITED, ColorPrimaries.of(primaries), TransferCharacteristics.of(transfer))
                            .withChromaSiting(siting);
                }
            }
            if (bits.overrun() || bitDepth > 16 || log2MaxPocLsb > 16 || maxWidth <= 0 || maxHeight <= 0
                    || width <= 0 || height <= 0) {
                return null;
            }
            var tools = new SliceTools(log2Ctu, log2MinCb, subpictures, extraShBits, partitionOverride, dualTree,
                    jointCbCr, sao, longTermRefs, interLayer, idrRplPresent, weighted, rplLists, temporalMvp,
                    mmvdFullpel, bdofControl, dmvrControl, profControl);
            return new Sps(id, vpsId, maxSubLayers, chromaFormatIdc, bitDepth, ptl, constraintBytes, profileIdc, tier,
                    levelIdc, maxWidth, maxHeight, width, height, log2MaxPocLsb, pocMsbCycleLen, extraPhBits, alf,
                    ccalf, lmcs, explicitScaling, virtualBoundariesInPh, reorder, latency, buffering, timingUnits,
                    timingScale, constant, fieldSeq, color, sar, tools);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    /**
     * Derives the layers of every output layer set of a video parameter set (clause 7.4.3.3).
     *
     * @param maxLayers the number of layers
     * @param eachLayerIsOls {@code vps_each_layer_is_an_ols_flag}
     * @param mode {@code vps_ols_mode_idc}
     * @param olsCount {@code TotalNumOlss}
     * @param direct {@code vps_direct_ref_layer_flag}
     * @param outputFlags {@code vps_ols_output_layer_flag} for mode 2, else {@code null}
     * @return the layer indexes of each set, increasing
     */
    private static int[][] olsLayers(int maxLayers, boolean eachLayerIsOls, int mode, int olsCount,
                                     boolean[][] direct, boolean[][] outputFlags) {
        var dependency = new boolean[maxLayers][maxLayers];
        for (var i = 0; i < maxLayers; i++) {
            for (var j = 0; j < i; j++) {
                if (direct[i][j]) {
                    dependency[i][j] = true;
                    for (var k = 0; k < j; k++) {
                        dependency[i][k] |= dependency[j][k];
                    }
                }
            }
        }
        var result = new int[olsCount][];
        for (var i = 0; i < olsCount; i++) {
            var included = new boolean[maxLayers];
            if (eachLayerIsOls) {
                included[i] = true;
            } else if (mode != 2) {
                Arrays.fill(included, 0, i + 1, true);
            } else if (i == 0) {
                included[0] = true;
            } else {
                for (var j = 0; j < maxLayers; j++) {
                    if (outputFlags[i][j]) {
                        included[j] = true;
                        for (var k = 0; k < j; k++) {
                            included[k] |= dependency[j][k];
                        }
                    }
                }
            }
            var count = 0;
            for (var flag : included) {
                count += flag ? 1 : 0;
            }
            var layers = new int[count];
            for (int j = 0, n = 0; j < maxLayers; j++) {
                if (included[j]) {
                    layers[n++] = j;
                }
            }
            result[i] = layers;
        }
        return result;
    }

    /**
     * {@return the trailing {@code ptl_num_sub_profiles} byte and sub-profile identifiers of a structure built by
     * {@link #vps(byte[], int, int)}}
     *
     * @param ptl the structure
     * @param constraintBytes its {@code num_bytes_constraint_info}
     * @param maxTid its {@code vps_ptl_max_tid}
     */
    private static byte[] subProfiles(byte[] ptl, int constraintBytes, int maxTid) {
        var position = 2 + constraintBytes;
        if (maxTid > 0) {
            position += 1 + Integer.bitCount(ptl[position] & 0xFF);
        }
        return Arrays.copyOfRange(ptl, position, ptl.length);
    }

    /**
     * Reads the subpicture layout and identifier mapping of a sequence parameter set.
     *
     * @param bits the reader, after {@code sps_subpic_info_present_flag}
     * @param maxWidth the maximum picture width
     * @param maxHeight the maximum picture height
     * @param ctu the CTU size
     * @param widthInCtus the maximum width in CTUs
     * @param heightInCtus the maximum height in CTUs
     * @return the layout, or {@code null} when a count is out of range
     */
    private static Subpictures readSubpictures(MsbBitReader bits, int maxWidth, int maxHeight, int ctu,
                                               int widthInCtus, int heightInCtus) {
        var count = bits.readUe() + 1;
        if (count > MAX_SUBPICS) {
            return null;
        }
        var x = new int[count];
        var y = new int[count];
        var w = new int[count];
        var h = new int[count];
        if (count > 1) {
            var independent = bits.readFlag();
            var sameSize = bits.readFlag();
            var bitsX = ceilLog2(widthInCtus);
            var bitsY = ceilLog2(heightInCtus);
            for (var i = 0; i < count; i++) {
                if (!sameSize || i == 0) {
                    if (i > 0 && maxWidth > ctu) {
                        x[i] = bits.read(bitsX);
                    }
                    if (i > 0 && maxHeight > ctu) {
                        y[i] = bits.read(bitsY);
                    }
                    w[i] = i < count - 1 && maxWidth > ctu ? bits.read(bitsX) + 1 : widthInCtus - x[i];
                    h[i] = i < count - 1 && maxHeight > ctu ? bits.read(bitsY) + 1 : heightInCtus - y[i];
                } else {
                    var columns = Math.max(1, widthInCtus / w[0]);
                    x[i] = (i % columns) * w[0];
                    y[i] = (i / columns) * h[0];
                    w[i] = w[0];
                    h[i] = h[0];
                }
                if (!independent) {
                    bits.skip(2);
                }
            }
        } else {
            w[0] = widthInCtus;
            h[0] = heightInCtus;
        }
        var idLength = bits.readUe() + 1;
        if (idLength > 16) {
            return null;
        }
        int[] ids = null;
        if (bits.readFlag()) {
            ids = new int[count];
            Arrays.fill(ids, -1);
            if (bits.readFlag()) {
                for (var i = 0; i < count; i++) {
                    ids[i] = bits.read(idLength);
                }
            }
        }
        return new Subpictures(true, idLength, ids, x, y, w, h);
    }

    /**
     * Reads one {@code ref_pic_list_struct( listIdx, rplsIdx )} (clause 7.3.10).
     *
     * @param bits the reader
     * @param longTermRefs {@code sps_long_term_ref_pics_flag}
     * @param interLayer {@code sps_inter_layer_prediction_enabled_flag}
     * @param weighted whether weighted prediction or weighted bi-prediction is enabled
     * @param log2MaxPocLsb {@code sps_log2_max_pic_order_cnt_lsb_minus4 + 4}
     * @param inSps whether the structure is one of the sequence parameter set's candidates, which may signal
     * {@code ltrp_in_header_flag}
     * @return the structure, or {@code null} when it has too many entries
     */
    private static RefList readRefPicList(MsbBitReader bits, boolean longTermRefs, boolean interLayer,
                                          boolean weighted, int log2MaxPocLsb, boolean inSps) {
        var entries = bits.readUe();
        if (entries > MAX_REF_ENTRIES) {
            return null;
        }
        var inHeader = longTermRefs;
        if (longTermRefs && entries > 0 && inSps) {
            inHeader = bits.readFlag();
        }
        var kinds = new byte[entries];
        var values = new int[entries];
        var cumulative = 0;
        for (var i = 0; i < entries; i++) {
            if (interLayer && bits.readFlag()) {
                kinds[i] = RefList.INTER_LAYER;
                values[i] = bits.readUe();
                continue;
            }
            var longTerm = longTermRefs && !bits.readFlag();
            if (!longTerm) {
                var delta = bits.readUe();
                if (!weighted || i == 0) {
                    delta++;
                }
                if (delta > 0 && bits.readFlag()) {
                    delta = -delta;
                }
                cumulative += delta;
                kinds[i] = RefList.SHORT_TERM;
                values[i] = cumulative;
            } else {
                kinds[i] = RefList.LONG_TERM;
                values[i] = inHeader ? -1 : bits.read(log2MaxPocLsb);
            }
        }
        return new RefList(inHeader, kinds, values);
    }

    /**
     * Reads {@code general_timing_hrd_parameters( )} and skips {@code ols_timing_hrd_parameters( )}.
     *
     * @param bits the reader, after {@code sps_timing_hrd_params_present_flag}
     * @param maxSubLayers the number of sublayers
     * @return {@code {units, scale, constant}}: the picture period of the highest sublayer as {@code units / scale}
     * seconds and 1 when the rate is fixed, or {@code null} when a count is out of range
     */
    private static long[] readTiming(MsbBitReader bits, int maxSubLayers) {
        var units = bits.readLong(32);
        var scale = bits.readLong(32);
        var nal = bits.readFlag();
        var vcl = bits.readFlag();
        var du = false;
        var cpbCount = 0;
        if (nal || vcl) {
            bits.readFlag();
            du = bits.readFlag();
            if (du) {
                bits.skip(8);
            }
            bits.skip(8);
            if (du) {
                bits.skip(4);
            }
            cpbCount = bits.readUe();
            if (cpbCount > 31) {
                return null;
            }
        }
        var subLayerCpb = maxSubLayers > 1 && bits.readFlag();
        var elemental = 0;
        for (var i = subLayerCpb ? 0 : maxSubLayers - 1; i < maxSubLayers; i++) {
            var fixed = bits.readFlag() || bits.readFlag();
            elemental = 0;
            if (fixed) {
                elemental = bits.readUe() + 1;
            } else if ((nal || vcl) && cpbCount == 0) {
                bits.readFlag();
            }
            for (var k = 0; k < 2; k++) {
                if (k == 0 ? nal : vcl) {
                    for (var j = 0; j <= cpbCount; j++) {
                        bits.readUe();
                        bits.readUe();
                        if (du) {
                            bits.readUe();
                            bits.readUe();
                        }
                        bits.readFlag();
                    }
                }
            }
        }
        if (units <= 0 || scale <= 0) {
            return new long[]{0, 0, 0};
        }
        return new long[]{units * Math.max(1, elemental), scale, elemental > 0 ? 1 : 0};
    }

    /**
     * Reads a picture parameter set.
     *
     * @param data the bytes
     * @param offset the index of the NAL unit header
     * @param length the NAL unit length
     * @return the fields, or {@code null} when the unit cannot be read
     */
    public static Pps pps(byte[] data, int offset, int length) {
        if (length < 4) {
            return null;
        }
        try {
            var bits = new MsbBitReader(NalUnits.unescape(data, offset + 2, length - 2));
            var id = bits.read(6);
            var spsId = bits.read(4);
            var mixed = bits.readFlag();
            var width = bits.readUe();
            var height = bits.readUe();
            if (bits.readFlag()) {
                for (var i = 0; i < 4; i++) {
                    bits.readUe();
                }
            }
            if (bits.readFlag()) {
                for (var i = 0; i < 4; i++) {
                    bits.readSe();
                }
            }
            var outputFlagPresent = bits.readFlag();
            try {
                return ppsTail(bits, id, spsId, mixed, width, height, outputFlagPresent);
            } catch (RuntimeException exception) {
                return outputFlagPresent ? null : new Pps(id, spsId, mixed, false, false, null);
            }
        } catch (RuntimeException exception) {
            return null;
        }
    }

    /**
     * Reads a picture parameter set after {@code pps_output_flag_present_flag}.
     *
     * @param bits the reader
     * @param id {@code pps_pic_parameter_set_id}
     * @param spsId {@code pps_seq_parameter_set_id}
     * @param mixed {@code pps_mixed_nalu_types_in_pic_flag}
     * @param width {@code pps_pic_width_in_luma_samples}
     * @param height {@code pps_pic_height_in_luma_samples}
     * @param outputFlagPresent {@code pps_output_flag_present_flag}
     * @return the fields; without the slice header tools, or {@code null} when {@code outputFlagPresent} is set,
     * when the rest does not parse
     */
    private static Pps ppsTail(MsbBitReader bits, int id, int spsId, boolean mixed, int width, int height,
                               boolean outputFlagPresent) {
        var fallback = outputFlagPresent ? null : new Pps(id, spsId, mixed, false, false, null);
        var noPicPartition = bits.readFlag();
        int[] subpicIds = null;
        if (bits.readFlag()) {
            var count = noPicPartition ? 1 : bits.readUe() + 1;
            var idLength = bits.readUe() + 1;
            if (count > MAX_SUBPICS || idLength > 16) {
                return fallback;
            }
            subpicIds = new int[count];
            for (var i = 0; i < count; i++) {
                subpicIds[i] = bits.read(idLength);
            }
        }
        var partition = new Partition(1, true, true, null, null);
        if (!noPicPartition) {
            var log2Ctu = bits.read(2) + 5;
            var ctu = 1 << log2Ctu;
            var widthInCtus = (width + ctu - 1) >> log2Ctu;
            var heightInCtus = (height + ctu - 1) >> log2Ctu;
            partition = widthInCtus <= 0 || heightInCtus <= 0 ? null
                    : readPartition(bits, widthInCtus, heightInCtus);
            if (partition == null) {
                return fallback;
            }
        }
        bits.readFlag();
        bits.readUe();
        bits.readUe();
        var rpl1IdxPresent = bits.readFlag();
        var weightedPred = bits.readFlag();
        var weightedBipred = bits.readFlag();
        if (bits.readFlag()) {
            bits.readUe();
        }
        bits.readSe();
        var cuQpDelta = bits.readFlag();
        var chromaToolOffsets = bits.readFlag();
        var cuChromaQpOffsetList = false;
        if (chromaToolOffsets) {
            bits.readSe();
            bits.readSe();
            var joint = bits.readFlag();
            if (joint) {
                bits.readSe();
            }
            bits.readFlag();
            cuChromaQpOffsetList = bits.readFlag();
            if (cuChromaQpOffsetList) {
                var entries = bits.readUe() + 1;
                if (entries > 6) {
                    return fallback;
                }
                for (var i = 0; i < entries; i++) {
                    bits.readSe();
                    bits.readSe();
                    if (joint) {
                        bits.readSe();
                    }
                }
            }
        }
        var deblockingDisabled = false;
        var dbfInfoInPh = false;
        if (bits.readFlag()) {
            var override = bits.readFlag();
            deblockingDisabled = bits.readFlag();
            if (!noPicPartition && override) {
                dbfInfoInPh = bits.readFlag();
            }
            if (!deblockingDisabled) {
                for (var i = 0; i < (chromaToolOffsets ? 6 : 2); i++) {
                    bits.readSe();
                }
            }
        }
        var rplInfoInPh = false;
        var saoInfoInPh = false;
        var alfInfoInPh = false;
        var wpInfoInPh = false;
        var qpDeltaInfoInPh = false;
        if (!noPicPartition) {
            rplInfoInPh = bits.readFlag();
            saoInfoInPh = bits.readFlag();
            alfInfoInPh = bits.readFlag();
            if ((weightedPred || weightedBipred) && rplInfoInPh) {
                wpInfoInPh = bits.readFlag();
            }
            qpDeltaInfoInPh = bits.readFlag();
        }
        var pictureHeaderExtension = bits.readFlag();
        if (bits.overrun()) {
            return fallback;
        }
        var tools = new PpsTools(partition.tiles(), partition.rectSlice(), partition.singleSlicePerSubpic(),
                partition.columns(), partition.rows(), subpicIds, rpl1IdxPresent, weightedPred, weightedBipred,
                cuQpDelta, cuChromaQpOffsetList, chromaToolOffsets, deblockingDisabled, dbfInfoInPh, rplInfoInPh,
                saoInfoInPh, wpInfoInPh, qpDeltaInfoInPh, pictureHeaderExtension);
        return new Pps(id, spsId, mixed, outputFlagPresent, alfInfoInPh, tools);
    }

    /**
     * The tile and slice layout of a picture parameter set.
     *
     * @param tiles {@code NumTilesInPic}
     * @param rectSlice {@code pps_rect_slice_flag}
     * @param singleSlicePerSubpic {@code pps_single_slice_per_subpic_flag}
     * @param columns the left CTU column of each rectangular slice, or {@code null}
     * @param rows the top CTU row of each rectangular slice, or {@code null}
     */
    private record Partition(int tiles, boolean rectSlice, boolean singleSlicePerSubpic, int[] columns, int[] rows) {
    }

    /**
     * Reads the tile and slice layout of a picture parameter set (clause 7.3.2.5), from
     * {@code pps_num_exp_tile_columns_minus1} through {@code pps_loop_filter_across_slices_enabled_flag}, and
     * locates the top-left CTU of every rectangular slice (clause 6.5.1).
     *
     * @param bits the reader
     * @param widthInCtus the picture width in CTUs
     * @param heightInCtus the picture height in CTUs
     * @return the layout, or {@code null} when it is out of range
     */
    private static Partition readPartition(MsbBitReader bits, int widthInCtus, int heightInCtus) {
        var expColumns = bits.readUe() + 1;
        var expRows = bits.readUe() + 1;
        if (expColumns > widthInCtus || expRows > heightInCtus) {
            return null;
        }
        var columnWidths = new int[expColumns];
        for (var i = 0; i < expColumns; i++) {
            columnWidths[i] = bits.readUe() + 1;
        }
        var rowHeights = new int[expRows];
        for (var i = 0; i < expRows; i++) {
            rowHeights[i] = bits.readUe() + 1;
        }
        var columns = uniformSizes(columnWidths, widthInCtus);
        var rows = uniformSizes(rowHeights, heightInCtus);
        if (columns == null || rows == null) {
            return null;
        }
        var numColumns = columns.length;
        var numRows = rows.length;
        var columnStart = new int[numColumns];
        for (var i = 1; i < numColumns; i++) {
            columnStart[i] = columnStart[i - 1] + columns[i - 1];
        }
        var rowStart = new int[numRows];
        for (var i = 1; i < numRows; i++) {
            rowStart[i] = rowStart[i - 1] + rows[i - 1];
        }
        var numTiles = numColumns * numRows;
        var rectSlice = true;
        if (numTiles > 1) {
            bits.readFlag();
            rectSlice = bits.readFlag();
        }
        var singleSlicePerSubpic = rectSlice && bits.readFlag();
        var numSlices = 0;
        int[] sliceColumns = null;
        int[] sliceRows = null;
        if (rectSlice && !singleSlicePerSubpic) {
            numSlices = bits.readUe() + 1;
            if (numSlices > 1000) {
                return null;
            }
            sliceColumns = new int[numSlices];
            sliceRows = new int[numSlices];
            var deltaPresent = numSlices > 2 && bits.readFlag();
            var tileIndex = 0;
            var previousHeight = 1;
            var i = 0;
            for (; i < numSlices - 1; i++) {
                if (tileIndex < 0 || tileIndex >= numTiles) {
                    return null;
                }
                var column = tileIndex % numColumns;
                var row = tileIndex / numColumns;
                sliceColumns[i] = columnStart[column];
                sliceRows[i] = rowStart[row];
                var widthInTiles = column != numColumns - 1 ? bits.readUe() + 1 : 1;
                int heightInTiles;
                if (row != numRows - 1 && (deltaPresent || column == 0)) {
                    heightInTiles = bits.readUe() + 1;
                } else if (row == numRows - 1) {
                    heightInTiles = 1;
                } else {
                    heightInTiles = previousHeight;
                }
                previousHeight = heightInTiles;
                if (widthInTiles == 1 && heightInTiles == 1 && rows[row] > 1) {
                    var tileHeight = rows[row];
                    var explicit = bits.readUe();
                    if (explicit > tileHeight) {
                        return null;
                    }
                    if (explicit > 0) {
                        var remaining = tileHeight;
                        var last = 0;
                        var top = rowStart[row];
                        var heights = new int[tileHeight];
                        var sliceCount = 0;
                        for (; sliceCount < explicit; sliceCount++) {
                            last = bits.readUe() + 1;
                            if (last > remaining) {
                                return null;
                            }
                            heights[sliceCount] = last;
                            remaining -= last;
                        }
                        while (remaining >= last && sliceCount < tileHeight) {
                            heights[sliceCount++] = last;
                            remaining -= last;
                        }
                        if (remaining > 0) {
                            heights[sliceCount++] = remaining;
                        }
                        if (i + sliceCount > numSlices) {
                            return null;
                        }
                        for (var k = 0; k < sliceCount; k++) {
                            sliceColumns[i + k] = columnStart[column];
                            sliceRows[i + k] = top;
                            top += heights[k];
                        }
                        i += sliceCount - 1;
                    }
                }
                if (i < numSlices - 1) {
                    if (deltaPresent) {
                        tileIndex += bits.readSe();
                    } else {
                        tileIndex += widthInTiles;
                        if (tileIndex % numColumns == 0) {
                            tileIndex += (heightInTiles - 1) * numColumns;
                        }
                    }
                }
            }
            if (i == numSlices - 1) {
                if (tileIndex < 0 || tileIndex >= numTiles) {
                    return null;
                }
                sliceColumns[i] = columnStart[tileIndex % numColumns];
                sliceRows[i] = rowStart[tileIndex / numColumns];
            }
        }
        if (!rectSlice || singleSlicePerSubpic || numSlices > 1) {
            bits.readFlag();
        }
        return bits.overrun() ? null
                : new Partition(numTiles, rectSlice, singleSlicePerSubpic, sliceColumns, sliceRows);
    }

    /**
     * Completes explicit tile sizes with uniform ones (ITU-T H.266 clause 6.5.1).
     *
     * @param explicit the explicit sizes, at least one
     * @param total the picture size in CTUs
     * @return every size, or {@code null} when the explicit sizes exceed the picture
     */
    private static int[] uniformSizes(int[] explicit, int total) {
        var remaining = total;
        for (var size : explicit) {
            remaining -= size;
        }
        if (remaining < 0) {
            return null;
        }
        var uniform = explicit[explicit.length - 1];
        var sizes = Arrays.copyOf(explicit, explicit.length + (remaining + uniform - 1) / uniform);
        var count = explicit.length;
        while (remaining > 0) {
            var size = Math.min(remaining, uniform);
            sizes[count++] = size;
            remaining -= size;
        }
        return Arrays.copyOf(sizes, count);
    }

    /**
     * Reads the picture header of a picture header NAL unit, or the one a slice carries in its slice header.
     *
     * @param data the bytes
     * @param offset the index of the NAL unit header
     * @param length the NAL unit length
     * @param sequences the sequence parameter sets by identifier
     * @param pictures the picture parameter sets by identifier
     * @return the fields, or {@code null} for a slice whose picture header is a NAL unit of its own, or a unit that
     * cannot be read
     */
    public static PictureHeader pictureHeader(byte[] data, int offset, int length, Map<Integer, Sps> sequences,
                                              Map<Integer, Pps> pictures) {
        if (length < 3) {
            return null;
        }
        try {
            var type = type(data, offset);
            var bits = new MsbBitReader(NalUnits.unescape(data, offset + 2, Math.min(length - 2, HEADER_BYTES)));
            if (type != PH && (!isSlice(type) || !bits.readFlag())) {
                return null;
            }
            return readHeader(bits, sequences, pictures);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    /**
     * Reads the first slice header of a picture through its reference picture lists (clause 7.3.7.1).
     *
     * @param data the bytes
     * @param offset the index of the NAL unit header
     * @param length the NAL unit length
     * @param header the picture header of the picture header NAL unit before the slice, or {@code null}
     * @param sequences the sequence parameter sets by identifier
     * @param pictures the picture parameter sets by identifier
     * @return the fields, or {@code null} when the unit is not a slice, its picture header is missing or the header
     * cannot be read
     */
    public static Slice slice(byte[] data, int offset, int length, PictureHeader header, Map<Integer, Sps> sequences,
                              Map<Integer, Pps> pictures) {
        var type = length < 3 ? -1 : type(data, offset);
        if (!isSlice(type)) {
            return null;
        }
        try {
            var bits = new MsbBitReader(NalUnits.unescape(data, offset + 2, Math.min(length - 2, HEADER_BYTES)));
            var inSlice = bits.readFlag();
            if (inSlice) {
                header = readHeader(bits, sequences, pictures);
            }
            if (header == null || !header.complete()) {
                return null;
            }
            var sps = header.sps();
            var tools = sps.tools();
            var pps = header.pps();
            var layout = pps.tools();
            var subpictures = tools.subpictures();
            var subpicIndex = 0;
            if (subpictures.present()) {
                var subpicId = bits.read(subpictures.idLength());
                subpicIndex = -1;
                for (var i = 0; i < subpictures.count(); i++) {
                    var value = layout.subpicIds() != null && i < layout.subpicIds().length ? layout.subpicIds()[i]
                            : subpictures.ids() != null ? subpictures.ids()[i] : i;
                    if (value == subpicId) {
                        subpicIndex = i;
                        break;
                    }
                }
                if (subpicIndex < 0) {
                    return null;
                }
            }
            var address = 0;
            if (!layout.rectSlice()) {
                if (layout.tiles() > 1) {
                    address = bits.read(ceilLog2(layout.tiles()));
                }
            } else {
                var inSubpic = 0;
                if (layout.singleSlicePerSubpic() || layout.sliceColumns() == null) {
                    inSubpic = 1;
                } else {
                    for (var i = 0; i < layout.sliceColumns().length; i++) {
                        var at = subpictures.indexOf(layout.sliceColumns()[i], layout.sliceRows()[i]);
                        inSubpic += at == subpicIndex || (at < 0 && subpicIndex == 0) ? 1 : 0;
                    }
                }
                if (inSubpic > 1) {
                    bits.skip(ceilLog2(inSubpic));
                }
            }
            bits.skip(tools.extraShBits());
            if (!layout.rectSlice() && layout.tiles() - address > 1) {
                bits.readUe();
            }
            if (header.interAllowed()) {
                bits.readUe();
            }
            var idr = type == IDR_W_RADL || type == IDR_N_LP;
            var noOutputOfPriorPics = (idr || type == CRA || type == GDR) && bits.readFlag();
            if (sps.alf() && !pps.alfInfoInPh() && bits.readFlag()) {
                skipAlf(bits, sps);
            }
            if (header.lmcs() && !inSlice) {
                bits.readFlag();
            }
            if (header.scalingList() && !inSlice) {
                bits.readFlag();
            }
            References references;
            if (layout.rplInfoInPh()) {
                references = header.references();
            } else if (idr && !tools.idrRplPresent()) {
                references = References.NONE;
            } else {
                references = readLists(bits, sps, layout, null);
            }
            return references == null || bits.overrun() ? null
                    : new Slice(header, noOutputOfPriorPics, references);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    /**
     * Reads a {@code picture_header_structure()} (clause 7.3.2.8): through {@code ph_pic_output_flag} for the fields
     * every picture needs, then the rest, which a slice header that carries the picture header precedes its own
     * fields with.
     *
     * @param bits the reader, at {@code ph_gdr_or_irap_pic_flag}
     * @param sequences the sequence parameter sets by identifier
     * @param pictures the picture parameter sets by identifier
     * @return the fields, incomplete when the structure after {@code ph_pic_output_flag} does not parse, or
     * {@code null} when its first part does not
     */
    private static PictureHeader readHeader(MsbBitReader bits, Map<Integer, Sps> sequences,
                                            Map<Integer, Pps> pictures) {
        var gdrOrIrap = bits.readFlag();
        var nonReference = bits.readFlag();
        var gdr = gdrOrIrap && bits.readFlag();
        var interAllowed = bits.readFlag();
        var intraAllowed = !interAllowed || bits.readFlag();
        var pps = pictures.get(bits.readUe());
        if (pps == null) {
            return null;
        }
        var sps = sequences.get(pps.spsId());
        if (sps == null) {
            return null;
        }
        var pocLsb = bits.read(sps.log2MaxPocLsb());
        var recovery = gdr ? bits.readUe() : 0;
        bits.skip(sps.extraPhBits());
        var pocMsb = -1;
        if (sps.pocMsbCycleLen() > 0 && bits.readFlag()) {
            pocMsb = bits.read(sps.pocMsbCycleLen());
        }
        if (sps.alf() && pps.alfInfoInPh() && bits.readFlag()) {
            skipAlf(bits, sps);
        }
        var lmcs = false;
        if (sps.lmcs() && bits.readFlag()) {
            lmcs = true;
            bits.skip(2);
            if (sps.chromaFormatIdc() != 0) {
                bits.readFlag();
            }
        }
        var scalingList = false;
        if (sps.explicitScalingList() && bits.readFlag()) {
            scalingList = true;
            bits.skip(3);
        }
        if (sps.virtualBoundariesInPh() && bits.readFlag()) {
            for (var direction = 0; direction < 2; direction++) {
                var count = bits.readUe();
                if (count > 3) {
                    return null;
                }
                for (var i = 0; i < count; i++) {
                    bits.readUe();
                }
            }
        }
        var output = true;
        if (pps.outputFlagPresent() && !nonReference) {
            output = bits.readFlag();
        }
        if (bits.overrun()) {
            return null;
        }
        var partial = new PictureHeader(sps, gdrOrIrap, nonReference, gdr, pocLsb, recovery, pocMsb, output,
                pps.mixedNaluTypes(), pps, interAllowed, lmcs, scalingList, false, null);
        if (sps.tools() == null || pps.tools() == null) {
            return partial;
        }
        try {
            var references = finishHeader(bits, sps, pps.tools(), interAllowed, intraAllowed);
            if (references == null || bits.overrun()) {
                return partial;
            }
            return new PictureHeader(sps, gdrOrIrap, nonReference, gdr, pocLsb, recovery, pocMsb, output,
                    pps.mixedNaluTypes(), pps, interAllowed, lmcs, scalingList, true,
                    pps.tools().rplInfoInPh() ? references : null);
        } catch (RuntimeException exception) {
            return partial;
        }
    }

    /**
     * Reads a picture header after {@code ph_pic_output_flag}.
     *
     * @param bits the reader
     * @param sps the sequence parameter set
     * @param layout the picture parameter set's fields
     * @param interAllowed {@code ph_inter_slice_allowed_flag}
     * @param intraAllowed {@code ph_intra_slice_allowed_flag}
     * @return the reference picture lists of the picture header, {@link References#NONE} when the slice headers
     * carry them, or {@code null} when a field is out of range
     */
    private static References finishHeader(MsbBitReader bits, Sps sps, PpsTools layout, boolean interAllowed,
                                           boolean intraAllowed) {
        var tools = sps.tools();
        var entries = new int[2];
        var references = References.NONE;
        if (layout.rplInfoInPh()) {
            references = readLists(bits, sps, layout, entries);
            if (references == null) {
                return null;
            }
        }
        var override = tools.partitionOverride() && bits.readFlag();
        if (intraAllowed) {
            if (override) {
                bits.readUe();
                if (bits.readUe() != 0) {
                    bits.readUe();
                    bits.readUe();
                }
                if (tools.dualTree()) {
                    bits.readUe();
                    if (bits.readUe() != 0) {
                        bits.readUe();
                        bits.readUe();
                    }
                }
            }
            if (layout.cuQpDelta()) {
                bits.readUe();
            }
            if (layout.cuChromaQpOffsetList()) {
                bits.readUe();
            }
        }
        if (interAllowed) {
            if (override) {
                bits.readUe();
                if (bits.readUe() != 0) {
                    bits.readUe();
                    bits.readUe();
                }
            }
            if (layout.cuQpDelta()) {
                bits.readUe();
            }
            if (layout.cuChromaQpOffsetList()) {
                bits.readUe();
            }
            if (tools.temporalMvp() && bits.readFlag() && layout.rplInfoInPh()) {
                var fromL0 = entries[1] <= 0 || bits.readFlag();
                if ((fromL0 && entries[0] > 1) || (!fromL0 && entries[1] > 1)) {
                    bits.readUe();
                }
            }
            if (tools.mmvdFullpel()) {
                bits.readFlag();
            }
            if (!layout.rplInfoInPh() || entries[1] > 0) {
                bits.readFlag();
                if (tools.bdofControl()) {
                    bits.readFlag();
                }
                if (tools.dmvrControl()) {
                    bits.readFlag();
                }
            }
            if (tools.profControl()) {
                bits.readFlag();
            }
            if ((layout.weightedPred() || layout.weightedBipred()) && layout.wpInfoInPh()
                    && !skipPredWeights(bits, sps.chromaFormatIdc() != 0, layout.weightedBipred(), entries)) {
                return null;
            }
        }
        if (layout.qpDeltaInfoInPh()) {
            bits.readSe();
        }
        if (tools.jointCbCr()) {
            bits.readFlag();
        }
        if (tools.sao() && layout.saoInfoInPh()) {
            bits.readFlag();
            if (sps.chromaFormatIdc() != 0) {
                bits.readFlag();
            }
        }
        if (layout.dbfInfoInPh() && bits.readFlag()) {
            var disabled = !layout.deblockingDisabled() && bits.readFlag();
            if (!disabled) {
                for (var i = 0; i < (layout.chromaToolOffsets() ? 6 : 2); i++) {
                    bits.readSe();
                }
            }
        }
        if (layout.pictureHeaderExtension()) {
            var extension = bits.readUe();
            if (extension > 256) {
                return null;
            }
            bits.skip(8L * extension);
        }
        return references;
    }

    /**
     * Skips the adaptive loop filter fields of a picture or slice header after its enable flag.
     *
     * @param bits the reader
     * @param sps the sequence parameter set
     */
    private static void skipAlf(MsbBitReader bits, Sps sps) {
        var luma = bits.read(3);
        bits.skip(3L * luma);
        var cb = false;
        var cr = false;
        if (sps.chromaFormatIdc() != 0) {
            cb = bits.readFlag();
            cr = bits.readFlag();
        }
        if (cb || cr) {
            bits.skip(3);
        }
        if (sps.ccalf()) {
            if (bits.readFlag()) {
                bits.skip(3);
            }
            if (bits.readFlag()) {
                bits.skip(3);
            }
        }
    }

    /**
     * Reads a {@code ref_pic_lists()} structure (clause 7.3.9).
     *
     * @param bits the reader
     * @param sps the sequence parameter set
     * @param layout the picture parameter set's fields
     * @param entries receives the number of entries of each list, or {@code null}
     * @return the lists, or {@code null} when an index is out of range
     */
    private static References readLists(MsbBitReader bits, Sps sps, PpsTools layout, int[] entries) {
        var tools = sps.tools();
        var spsFlag = new boolean[2];
        var rplIdx = new int[2];
        var shortTerm = new int[2 * MAX_REF_ENTRIES];
        var shortTermCount = 0;
        var lsb = new int[2 * MAX_REF_ENTRIES];
        var cycles = new int[2 * MAX_REF_ENTRIES];
        var longTermCount = 0;
        for (var li = 0; li < 2; li++) {
            var candidates = tools.rplLists()[li];
            var count = candidates.length;
            var signalled = li == 0 || layout.rpl1IdxPresent();
            if (count > 0 && signalled) {
                spsFlag[li] = bits.readFlag();
            } else {
                spsFlag[li] = count > 0 && spsFlag[0];
            }
            RefList list;
            if (spsFlag[li]) {
                var idx = 0;
                if (count > 1 && signalled) {
                    idx = bits.read(ceilLog2(count));
                } else if (li == 1 && count > 1) {
                    idx = rplIdx[0];
                }
                if (idx >= count) {
                    return null;
                }
                rplIdx[li] = idx;
                list = candidates[idx];
            } else {
                list = readRefPicList(bits, tools.longTermRefs(), tools.interLayer(), tools.weighted(),
                        sps.log2MaxPocLsb(), false);
                if (list == null) {
                    return null;
                }
            }
            if (entries != null) {
                entries[li] = list.kinds().length;
            }
            var cycle = 0;
            for (var j = 0; j < list.kinds().length; j++) {
                switch (list.kinds()[j]) {
                    case RefList.SHORT_TERM -> shortTerm[shortTermCount++] = list.values()[j];
                    case RefList.LONG_TERM -> {
                        lsb[longTermCount] = list.ltrpInHeader() ? bits.read(sps.log2MaxPocLsb()) : list.values()[j];
                        if (bits.readFlag()) {
                            cycle += bits.readUe();
                            cycles[longTermCount] = cycle;
                        } else {
                            cycles[longTermCount] = -1;
                        }
                        longTermCount++;
                    }
                    default -> {
                    }
                }
            }
        }
        return new References(Arrays.copyOf(shortTerm, shortTermCount), Arrays.copyOf(lsb, longTermCount),
                Arrays.copyOf(cycles, longTermCount));
    }

    /**
     * Skips a {@code pred_weight_table()} of a picture header (clause 7.3.8).
     *
     * @param bits the reader
     * @param chroma whether the pictures have chroma
     * @param bipred {@code pps_weighted_bipred_flag}
     * @param entries the number of entries of each reference picture list
     * @return {@code false} when a count is out of range
     */
    private static boolean skipPredWeights(MsbBitReader bits, boolean chroma, boolean bipred, int[] entries) {
        bits.readUe();
        if (chroma) {
            bits.readSe();
        }
        for (var list = 0; list < 2; list++) {
            var count = 0;
            if (list == 0 || (bipred && entries[1] > 0)) {
                count = bits.readUe();
            }
            if (count > 15 || count > entries[list]) {
                return false;
            }
            var luma = new boolean[count];
            var chromaFlags = new boolean[count];
            for (var i = 0; i < count; i++) {
                luma[i] = bits.readFlag();
            }
            if (chroma) {
                for (var i = 0; i < count; i++) {
                    chromaFlags[i] = bits.readFlag();
                }
            }
            for (var i = 0; i < count; i++) {
                if (luma[i]) {
                    bits.readSe();
                    bits.readSe();
                }
                if (chromaFlags[i]) {
                    for (var j = 0; j < 4; j++) {
                        bits.readSe();
                    }
                }
            }
        }
        return true;
    }

    /**
     * {@return {@code Ceil( Log2( value ) )} for a positive value}
     *
     * @param value the positive value
     */
    private static int ceilLog2(int value) {
        return value <= 1 ? 0 : 32 - Integer.numberOfLeadingZeros(value - 1);
    }
}
