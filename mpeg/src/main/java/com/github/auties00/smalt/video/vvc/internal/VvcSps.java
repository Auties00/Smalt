/* Ports VVdeC 3.3.0 (source/Lib/DecoderLib/HLSyntaxReader.cpp, source/Lib/CommonLib/Slice.cpp). Copyright (c)
 * 2018-2026, Fraunhofer-Gesellschaft zur Foerderung der angewandten Forschung e.V. & The VVdeC Authors; Clear BSD
 * License. Modified for Smalt. */
package com.github.auties00.smalt.video.vvc.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.video.h264.internal.H264BitReader;

/**
 * Holds a parsed sequence parameter set (ITU-T H.266 7.3.2.4) with the variables derived from it.
 *
 * <p>Partition constraint arrays are indexed by slice kind: 0 for the luma tree of intra slices, 1 for inter
 * slices and 2 for the chroma tree of intra slices coded with a dual tree.
 */
public final class VvcSps {
    /**
     * Constructs an empty set, which {@link #parse} fills.
     */
    public VvcSps() {
    }

    /**
     * The most subpictures a sequence may declare.
     */
    static final int MAX_SUBPICS = 600;

    /**
     * {@code sps_seq_parameter_set_id}.
     */
    public int id;
    /**
     * {@code sps_video_parameter_set_id}.
     */
    public int vpsId;
    /**
     * {@code sps_max_sublayers_minus1 + 1}.
     */
    public int maxSubLayers;
    /**
     * {@code sps_chroma_format_idc}: 0 for 4:0:0, 1 for 4:2:0, 2 for 4:2:2, 3 for 4:4:4.
     */
    public int chromaFormatIdc;
    /**
     * {@code CtbLog2SizeY}, from 5 to 7.
     */
    public int log2CtuSize;
    /**
     * {@code CtbSizeY}, the CTU size in luma samples.
     */
    public int ctuSize;
    /**
     * {@code general_profile_idc}.
     */
    public int profileIdc;
    /**
     * {@code general_tier_flag}.
     */
    public int tierFlag;
    /**
     * {@code general_level_idc}.
     */
    public int levelIdc;
    /**
     * {@code sps_gdr_enabled_flag}.
     */
    public boolean gdrEnabled;
    /**
     * {@code sps_ref_pic_resampling_enabled_flag}.
     */
    public boolean rprEnabled;
    /**
     * {@code sps_res_change_in_clvs_allowed_flag}.
     */
    public boolean resChangeInClvsAllowed;
    /**
     * {@code sps_pic_width_max_in_luma_samples}.
     */
    public int maxWidth;
    /**
     * {@code sps_pic_height_max_in_luma_samples}.
     */
    public int maxHeight;
    /**
     * {@code sps_conf_win_left_offset}, in chroma samples.
     */
    public int confLeft;
    /**
     * {@code sps_conf_win_right_offset}, in chroma samples.
     */
    public int confRight;
    /**
     * {@code sps_conf_win_top_offset}, in chroma samples.
     */
    public int confTop;
    /**
     * {@code sps_conf_win_bottom_offset}, in chroma samples.
     */
    public int confBottom;
    /**
     * {@code sps_subpic_info_present_flag}.
     */
    public boolean subpicInfoPresent;
    /**
     * {@code sps_num_subpics_minus1 + 1}.
     */
    public int numSubpics = 1;
    /**
     * {@code sps_independent_subpics_flag}.
     */
    public boolean independentSubpics;
    /**
     * {@code sps_subpic_ctu_top_left_x} of each subpicture, in CTUs.
     */
    public int[] subpicCtuX = new int[1];
    /**
     * {@code sps_subpic_ctu_top_left_y} of each subpicture, in CTUs.
     */
    public int[] subpicCtuY = new int[1];
    /**
     * {@code sps_subpic_width_minus1 + 1} of each subpicture, in CTUs.
     */
    public int[] subpicWidth = new int[1];
    /**
     * {@code sps_subpic_height_minus1 + 1} of each subpicture, in CTUs.
     */
    public int[] subpicHeight = new int[1];
    /**
     * {@code sps_subpic_treated_as_pic_flag} of each subpicture.
     */
    public boolean[] subpicTreatedAsPic = {true};
    /**
     * {@code sps_loop_filter_across_subpic_enabled_flag} of each subpicture.
     */
    public boolean[] loopFilterAcrossSubpic = {false};
    /**
     * {@code sps_subpic_id_len_minus1 + 1}.
     */
    public int subpicIdLen;
    /**
     * {@code sps_subpic_id_mapping_explicitly_signalled_flag}.
     */
    public boolean subpicIdMappingExplicit;
    /**
     * {@code sps_subpic_id_mapping_present_flag}.
     */
    public boolean subpicIdMappingPresent;
    /**
     * The identifier of each subpicture the sequence parameter set gives: {@code sps_subpic_id} when signalled, its
     * index otherwise.
     */
    public int[] subpicId = {0};
    /**
     * {@code BitDepth}: {@code sps_bitdepth_minus8 + 8}.
     */
    public int bitDepth;
    /**
     * {@code QpBdOffset}: {@code 6 * sps_bitdepth_minus8}.
     */
    public int qpBdOffset;
    /**
     * {@code sps_entropy_coding_sync_enabled_flag}: the CTU rows are wavefronts.
     */
    public boolean entropyCodingSync;
    /**
     * {@code sps_entry_point_offsets_present_flag}.
     */
    public boolean entryPointsPresent;
    /**
     * {@code sps_log2_max_pic_order_cnt_lsb_minus4 + 4}.
     */
    public int log2MaxPocLsb;
    /**
     * {@code sps_poc_msb_cycle_flag}.
     */
    public boolean pocMsbCycle;
    /**
     * {@code sps_poc_msb_cycle_len_minus1 + 1}, or 0 without the flag.
     */
    public int pocMsbCycleLen;
    /**
     * {@code sps_extra_ph_bit_present_flag} of each extra picture header bit.
     */
    public boolean[] extraPhBits = new boolean[0];
    /**
     * {@code sps_extra_sh_bit_present_flag} of each extra slice header bit.
     */
    public boolean[] extraShBits = new boolean[0];
    /**
     * {@code dpb_max_dec_pic_buffering_minus1 + 1} of each sublayer.
     */
    public int[] maxDecPicBuffering = new int[8];
    /**
     * {@code dpb_max_num_reorder_pics} of each sublayer.
     */
    public int[] maxNumReorder = new int[8];
    /**
     * {@code dpb_max_latency_increase_plus1} of each sublayer.
     */
    public int[] maxLatencyIncreasePlus1 = new int[8];
    /**
     * {@code MinCbLog2SizeY}.
     */
    public int log2MinCbSize;
    /**
     * {@code sps_partition_constraints_override_enabled_flag}.
     */
    public boolean partitionConstraintsOverride;
    /**
     * The minimum quadtree leaf size of each slice kind ({@code MinQtSizeY} or {@code MinQtSizeC}).
     */
    public final int[] minQtSize = new int[3];
    /**
     * The maximum multi-type tree depth of each slice kind.
     */
    public final int[] maxMttDepth = new int[3];
    /**
     * The maximum binary tree root size of each slice kind ({@code MaxBtSizeY} or {@code MaxBtSizeC}).
     */
    public final int[] maxBtSize = new int[3];
    /**
     * The maximum ternary tree root size of each slice kind ({@code MaxTtSizeY} or {@code MaxTtSizeC}).
     */
    public final int[] maxTtSize = new int[3];
    /**
     * {@code sps_qtbtt_dual_tree_intra_flag}.
     */
    public boolean dualTree;
    /**
     * {@code MaxTbLog2SizeY}, 5 or 6.
     */
    public int log2MaxTbSize;
    /**
     * {@code sps_transform_skip_enabled_flag}.
     */
    public boolean transformSkip;
    /**
     * The base 2 logarithm of {@code MaxTsSize}.
     */
    public int log2MaxTsSize;
    /**
     * {@code sps_bdpcm_enabled_flag}.
     */
    public boolean bdpcm;
    /**
     * {@code sps_mts_enabled_flag}.
     */
    public boolean mts;
    /**
     * {@code sps_explicit_mts_intra_enabled_flag}.
     */
    public boolean explicitMtsIntra;
    /**
     * {@code sps_explicit_mts_inter_enabled_flag}.
     */
    public boolean explicitMtsInter;
    /**
     * {@code sps_lfnst_enabled_flag}.
     */
    public boolean lfnst;
    /**
     * {@code sps_joint_cbcr_enabled_flag}.
     */
    public boolean jointCbCr;
    /**
     * The chroma QP mapping tables (Cb, Cr, joint Cb-Cr), indexed by {@code qp + qpBdOffset} for luma QPs from
     * {@code -qpBdOffset} to 63.
     */
    public final int[][] chromaQpTable = new int[3][];
    /**
     * {@code sps_sao_enabled_flag}.
     */
    public boolean sao;
    /**
     * {@code sps_alf_enabled_flag}.
     */
    public boolean alf;
    /**
     * {@code sps_ccalf_enabled_flag}.
     */
    public boolean ccalf;
    /**
     * {@code sps_lmcs_enabled_flag}.
     */
    public boolean lmcs;
    /**
     * {@code sps_weighted_pred_flag}.
     */
    public boolean weightedPred;
    /**
     * {@code sps_weighted_bipred_flag}.
     */
    public boolean weightedBipred;
    /**
     * {@code sps_long_term_ref_pics_flag}.
     */
    public boolean longTermRefs;
    /**
     * {@code sps_inter_layer_prediction_enabled_flag}.
     */
    public boolean interLayerPrediction;
    /**
     * {@code sps_idr_rpl_present_flag}.
     */
    public boolean idrRplPresent;
    /**
     * {@code sps_rpl1_same_as_rpl0_flag}.
     */
    public boolean rpl1SameAsRpl0;
    /**
     * The reference picture list structures of each list, by {@code rplsIdx}.
     */
    public final VvcRefPicList[][] rplLists = new VvcRefPicList[2][];
    /**
     * {@code sps_ref_wraparound_enabled_flag}.
     */
    public boolean refWraparound;
    /**
     * {@code sps_temporal_mvp_enabled_flag}.
     */
    public boolean temporalMvp;
    /**
     * {@code sps_sbtmvp_enabled_flag}.
     */
    public boolean sbtmvp;
    /**
     * {@code sps_amvr_enabled_flag}.
     */
    public boolean amvr;
    /**
     * {@code sps_bdof_enabled_flag}.
     */
    public boolean bdof;
    /**
     * {@code sps_bdof_control_present_in_ph_flag}.
     */
    public boolean bdofControlInPh;
    /**
     * {@code sps_smvd_enabled_flag}.
     */
    public boolean smvd;
    /**
     * {@code sps_dmvr_enabled_flag}.
     */
    public boolean dmvr;
    /**
     * {@code sps_dmvr_control_present_in_ph_flag}.
     */
    public boolean dmvrControlInPh;
    /**
     * {@code sps_mmvd_enabled_flag}.
     */
    public boolean mmvd;
    /**
     * {@code sps_mmvd_fullpel_only_enabled_flag}.
     */
    public boolean mmvdFullpelOnly;
    /**
     * {@code MaxNumMergeCand}.
     */
    public int maxNumMergeCand;
    /**
     * {@code sps_sbt_enabled_flag}.
     */
    public boolean sbt;
    /**
     * {@code sps_affine_enabled_flag}.
     */
    public boolean affine;
    /**
     * {@code MaxNumSubblockMergeCand}.
     */
    public int maxNumSubblockMergeCand;
    /**
     * {@code sps_6param_affine_enabled_flag}.
     */
    public boolean sixParamAffine;
    /**
     * {@code sps_affine_amvr_enabled_flag}.
     */
    public boolean affineAmvr;
    /**
     * {@code sps_affine_prof_enabled_flag}.
     */
    public boolean prof;
    /**
     * {@code sps_prof_control_present_in_ph_flag}.
     */
    public boolean profControlInPh;
    /**
     * {@code sps_bcw_enabled_flag}.
     */
    public boolean bcw;
    /**
     * {@code sps_ciip_enabled_flag}.
     */
    public boolean ciip;
    /**
     * {@code sps_gpm_enabled_flag}.
     */
    public boolean gpm;
    /**
     * {@code MaxNumGpmMergeCand}.
     */
    public int maxNumGpmMergeCand;
    /**
     * {@code Log2ParMrgLevel}.
     */
    public int log2ParallelMergeLevel;
    /**
     * {@code sps_isp_enabled_flag}.
     */
    public boolean isp;
    /**
     * {@code sps_mrl_enabled_flag}.
     */
    public boolean mrl;
    /**
     * {@code sps_mip_enabled_flag}.
     */
    public boolean mip;
    /**
     * {@code sps_cclm_enabled_flag}.
     */
    public boolean cclm;
    /**
     * {@code sps_chroma_horizontal_collocated_flag}.
     */
    public boolean chromaHorCollocated;
    /**
     * {@code sps_chroma_vertical_collocated_flag}.
     */
    public boolean chromaVerCollocated;
    /**
     * {@code sps_palette_enabled_flag}.
     */
    public boolean palette;
    /**
     * {@code sps_act_enabled_flag}.
     */
    public boolean act;
    /**
     * {@code sps_min_qp_prime_ts}.
     */
    public int minQpPrimeTs;
    /**
     * {@code sps_ibc_enabled_flag}.
     */
    public boolean ibc;
    /**
     * {@code MaxNumIbcMergeCand}.
     */
    public int maxNumIbcMergeCand;
    /**
     * {@code sps_ladf_enabled_flag}.
     */
    public boolean ladf;
    /**
     * {@code sps_num_ladf_intervals_minus2 + 2}.
     */
    public int ladfNumIntervals;
    /**
     * The QP offset of each luma adaptive deblocking interval: {@code sps_ladf_lowest_interval_qp_offset}, then each
     * {@code sps_ladf_qp_offset}.
     */
    public final int[] ladfQpOffset = new int[5];
    /**
     * {@code SpsLadfIntervalLowerBound} of each luma adaptive deblocking interval.
     */
    public final int[] ladfLowerBound = new int[5];
    /**
     * {@code sps_explicit_scaling_list_enabled_flag}.
     */
    public boolean explicitScalingList;
    /**
     * {@code sps_scaling_matrix_for_lfnst_disabled_flag}.
     */
    public boolean scalingMatrixForLfnstDisabled;
    /**
     * {@code sps_dep_quant_enabled_flag}.
     */
    public boolean depQuant;
    /**
     * {@code sps_sign_data_hiding_enabled_flag}.
     */
    public boolean signDataHiding;
    /**
     * {@code sps_virtual_boundaries_enabled_flag}.
     */
    public boolean virtualBoundariesEnabled;
    /**
     * {@code sps_virtual_boundaries_present_flag}.
     */
    public boolean virtualBoundariesPresent;
    /**
     * {@code sps_num_ver_virtual_boundaries}.
     */
    public int numVerVirtualBoundaries;
    /**
     * {@code sps_num_hor_virtual_boundaries}.
     */
    public int numHorVirtualBoundaries;
    /**
     * The vertical virtual boundary positions, in luma samples.
     */
    public final int[] virtualBoundaryX = new int[3];
    /**
     * The horizontal virtual boundary positions, in luma samples.
     */
    public final int[] virtualBoundaryY = new int[3];
    /**
     * {@code sps_field_seq_flag}: every picture is a field.
     */
    public boolean fieldSeq;
    /**
     * The timing of the general timing and HRD parameters, or {@code null} when absent.
     */
    VvcHrd hrd;
    /**
     * The VUI parameters, the defaults when absent.
     */
    public final VvcVui vui = new VvcVui();
    /**
     * {@code sps_vui_parameters_present_flag}.
     */
    public boolean vuiPresent;
    /**
     * {@code sps_extended_precision_flag} of the range extension.
     */
    public boolean extendedPrecision;
    /**
     * {@code sps_ts_residual_coding_rice_present_in_sh_flag} of the range extension.
     */
    public boolean tsResidualCodingRiceInSh;
    /**
     * {@code sps_rrc_rice_extension_flag} of the range extension.
     */
    public boolean rrcRiceExtension;
    /**
     * {@code sps_persistent_rice_adaptation_enabled_flag} of the range extension.
     */
    public boolean persistentRiceAdaptation;
    /**
     * {@code sps_reverse_last_sig_coeff_enabled_flag} of the range extension.
     */
    public boolean reverseLastSigCoeff;

    /**
     * Parses a sequence parameter set RBSP.
     *
     * @param r the reader, positioned after the NAL unit header
     * @return the parsed set
     * @throws MediaException.Malformed if a syntax element is out of range
     */
    public static VvcSps parse(H264BitReader r) {
        var s = new VvcSps();
        s.id = r.readBits(4);
        s.vpsId = r.readBits(4);
        s.maxSubLayers = check(r.readBits(3), 0, 6, "sps_max_sublayers_minus1") + 1;
        s.chromaFormatIdc = r.readBits(2);
        s.log2CtuSize = check(r.readBits(2), 0, 2, "sps_log2_ctu_size_minus5") + 5;
        s.ctuSize = 1 << s.log2CtuSize;
        var ptlPresent = r.readFlag();
        if (ptlPresent) {
            parseProfileTierLevel(r, s, true, s.maxSubLayers - 1);
        }
        s.gdrEnabled = r.readFlag();
        s.rprEnabled = r.readFlag();
        if (s.rprEnabled) {
            s.resChangeInClvsAllowed = r.readFlag();
        }
        s.maxWidth = r.readUe(1 << 16, "sps_pic_width_max_in_luma_samples");
        s.maxHeight = r.readUe(1 << 16, "sps_pic_height_max_in_luma_samples");
        var subW = s.chromaFormatIdc == 1 || s.chromaFormatIdc == 2 ? 2 : 1;
        var subH = s.chromaFormatIdc == 1 ? 2 : 1;
        if (r.readFlag()) {
            s.confLeft = r.readUe(1 << 16, "sps_conf_win_left_offset");
            s.confRight = r.readUe(1 << 16, "sps_conf_win_right_offset");
            s.confTop = r.readUe(1 << 16, "sps_conf_win_top_offset");
            s.confBottom = r.readUe(1 << 16, "sps_conf_win_bottom_offset");
            if (subW * (s.confLeft + s.confRight) >= s.maxWidth || subH * (s.confTop + s.confBottom) >= s.maxHeight) {
                throw new MediaException.Malformed("vvc conformance window exceeds the picture");
            }
        }
        var widthInCtus = (s.maxWidth + s.ctuSize - 1) >> s.log2CtuSize;
        var heightInCtus = (s.maxHeight + s.ctuSize - 1) >> s.log2CtuSize;
        s.subpicInfoPresent = r.readFlag();
        if (s.subpicInfoPresent) {
            parseSubpictures(r, s, widthInCtus, heightInCtus);
        } else {
            s.subpicWidth[0] = widthInCtus;
            s.subpicHeight[0] = heightInCtus;
        }
        if (!s.subpicIdMappingExplicit || !s.subpicIdMappingPresent) {
            for (var i = 0; i < s.numSubpics; i++) {
                s.subpicId[i] = i;
            }
        }
        s.bitDepth = check(r.readUe(), 0, 8, "sps_bitdepth_minus8") + 8;
        s.qpBdOffset = 6 * (s.bitDepth - 8);
        s.entropyCodingSync = r.readFlag();
        s.entryPointsPresent = r.readFlag();
        s.log2MaxPocLsb = check(r.readBits(4), 0, 12, "sps_log2_max_pic_order_cnt_lsb_minus4") + 4;
        s.pocMsbCycle = r.readFlag();
        if (s.pocMsbCycle) {
            s.pocMsbCycleLen = r.readUe(32 - s.log2MaxPocLsb - 1, "sps_poc_msb_cycle_len_minus1") + 1;
        }
        var extraPh = check(r.readBits(2), 0, 2, "sps_num_extra_ph_bytes");
        s.extraPhBits = new boolean[8 * extraPh];
        for (var i = 0; i < s.extraPhBits.length; i++) {
            s.extraPhBits[i] = r.readFlag();
        }
        var extraSh = check(r.readBits(2), 0, 2, "sps_num_extra_sh_bytes");
        s.extraShBits = new boolean[8 * extraSh];
        for (var i = 0; i < s.extraShBits.length; i++) {
            s.extraShBits[i] = r.readFlag();
        }
        if (ptlPresent) {
            var subLayerInfo = s.maxSubLayers > 1 && r.readFlag();
            parseDpbParameters(r, s, s.maxSubLayers - 1, subLayerInfo);
        }
        var ctbLog2 = s.log2CtuSize;
        s.log2MinCbSize = r.readUe(Math.min(4, ctbLog2 - 2), "sps_log2_min_luma_coding_block_size_minus2") + 2;
        var minCbLog2 = s.log2MinCbSize;
        var minCb = 1 << minCbLog2;
        var picUnit = Math.max(8, minCb);
        if (s.maxWidth == 0 || s.maxHeight == 0 || s.maxWidth % picUnit != 0 || s.maxHeight % picUnit != 0) {
            throw new MediaException.Malformed("vvc picture size not a multiple of the minimum coding block");
        }
        s.partitionConstraintsOverride = r.readFlag();
        var minQtIntraY = r.readUe(Math.min(6, ctbLog2) - minCbLog2,
                "sps_log2_diff_min_qt_min_cb_intra_slice_luma") + minCbLog2;
        s.maxMttDepth[0] = r.readUe(2 * (ctbLog2 - minCbLog2), "sps_max_mtt_hierarchy_depth_intra_slice_luma");
        s.minQtSize[0] = 1 << minQtIntraY;
        s.maxBtSize[0] = s.minQtSize[0];
        s.maxTtSize[0] = s.minQtSize[0];
        if (s.maxMttDepth[0] != 0) {
            s.maxBtSize[0] <<= r.readUe(ctbLog2 - minQtIntraY, "sps_log2_diff_max_bt_min_qt_intra_slice_luma");
            s.maxTtSize[0] <<= r.readUe(Math.min(6, ctbLog2) - minQtIntraY,
                    "sps_log2_diff_max_tt_min_qt_intra_slice_luma");
        }
        if (s.chromaFormatIdc != 0) {
            s.dualTree = r.readFlag();
        }
        if (s.dualTree) {
            var minQtIntraC = r.readUe(Math.min(6, ctbLog2) - minCbLog2,
                    "sps_log2_diff_min_qt_min_cb_intra_slice_chroma") + minCbLog2;
            s.maxMttDepth[2] = r.readUe(2 * (ctbLog2 - minCbLog2), "sps_max_mtt_hierarchy_depth_intra_slice_chroma");
            s.minQtSize[2] = 1 << minQtIntraC;
            s.maxBtSize[2] = s.minQtSize[2];
            s.maxTtSize[2] = s.minQtSize[2];
            if (s.maxMttDepth[2] != 0) {
                s.maxBtSize[2] <<= r.readUe(Math.min(6, ctbLog2) - minQtIntraC,
                        "sps_log2_diff_max_bt_min_qt_intra_slice_chroma");
                s.maxTtSize[2] <<= r.readUe(Math.min(6, ctbLog2) - minQtIntraC,
                        "sps_log2_diff_max_tt_min_qt_intra_slice_chroma");
            }
        }
        var minQtInter = r.readUe(Math.min(6, ctbLog2) - minCbLog2, "sps_log2_diff_min_qt_min_cb_inter_slice")
                + minCbLog2;
        s.maxMttDepth[1] = r.readUe(2 * (ctbLog2 - minCbLog2), "sps_max_mtt_hierarchy_depth_inter_slice");
        s.minQtSize[1] = 1 << minQtInter;
        s.maxBtSize[1] = s.minQtSize[1];
        s.maxTtSize[1] = s.minQtSize[1];
        if (s.maxMttDepth[1] != 0) {
            s.maxBtSize[1] <<= r.readUe(ctbLog2 - minQtInter, "sps_log2_diff_max_bt_min_qt_inter_slice");
            s.maxTtSize[1] <<= r.readUe(Math.min(6, ctbLog2) - minQtInter, "sps_log2_diff_max_tt_min_qt_inter_slice");
        }
        s.log2MaxTbSize = s.ctuSize > 32 && r.readFlag() ? 6 : 5;
        s.transformSkip = r.readFlag();
        if (s.transformSkip) {
            s.log2MaxTsSize = r.readUe(3, "sps_log2_transform_skip_max_size_minus2") + 2;
            s.bdpcm = r.readFlag();
        }
        s.mts = r.readFlag();
        if (s.mts) {
            s.explicitMtsIntra = r.readFlag();
            s.explicitMtsInter = r.readFlag();
        }
        s.lfnst = r.readFlag();
        if (s.chromaFormatIdc != 0) {
            s.jointCbCr = r.readFlag();
            parseChromaQpTables(r, s);
        }
        s.sao = r.readFlag();
        s.alf = r.readFlag();
        if (s.alf && s.chromaFormatIdc != 0) {
            s.ccalf = r.readFlag();
        }
        s.lmcs = r.readFlag();
        s.weightedPred = r.readFlag();
        s.weightedBipred = r.readFlag();
        s.longTermRefs = r.readFlag();
        if (s.vpsId > 0) {
            s.interLayerPrediction = r.readFlag();
        }
        s.idrRplPresent = r.readFlag();
        s.rpl1SameAsRpl0 = r.readFlag();
        for (var i = 0; i < (s.rpl1SameAsRpl0 ? 1 : 2); i++) {
            var count = r.readUe(64, "sps_num_ref_pic_lists");
            s.rplLists[i] = new VvcRefPicList[count];
            for (var j = 0; j < count; j++) {
                s.rplLists[i][j] = VvcRefPicList.parse(r, s, j);
            }
        }
        if (s.rpl1SameAsRpl0) {
            s.rplLists[1] = new VvcRefPicList[s.rplLists[0].length];
            for (var j = 0; j < s.rplLists[0].length; j++) {
                s.rplLists[1][j] = s.rplLists[0][j].copy();
            }
        }
        s.refWraparound = r.readFlag();
        s.temporalMvp = r.readFlag();
        if (s.temporalMvp) {
            s.sbtmvp = r.readFlag();
        }
        s.amvr = r.readFlag();
        s.bdof = r.readFlag();
        if (s.bdof) {
            s.bdofControlInPh = r.readFlag();
        }
        s.smvd = r.readFlag();
        s.dmvr = r.readFlag();
        if (s.dmvr) {
            s.dmvrControlInPh = r.readFlag();
        }
        s.mmvd = r.readFlag();
        if (s.mmvd) {
            s.mmvdFullpelOnly = r.readFlag();
        }
        s.maxNumMergeCand = 6 - r.readUe(5, "sps_six_minus_max_num_merge_cand");
        s.sbt = r.readFlag();
        s.affine = r.readFlag();
        if (s.affine) {
            s.maxNumSubblockMergeCand = 5 - r.readUe(s.sbtmvp ? 4 : 5, "sps_five_minus_max_num_subblock_merge_cand");
            s.sixParamAffine = r.readFlag();
            if (s.amvr) {
                s.affineAmvr = r.readFlag();
            }
            s.prof = r.readFlag();
            if (s.prof) {
                s.profControlInPh = r.readFlag();
            }
        }
        s.bcw = r.readFlag();
        s.ciip = r.readFlag();
        if (s.maxNumMergeCand >= 2) {
            s.gpm = r.readFlag();
            if (s.gpm) {
                s.maxNumGpmMergeCand = s.maxNumMergeCand >= 3
                        ? s.maxNumMergeCand - r.readUe(s.maxNumMergeCand - 2,
                                "sps_max_num_merge_cand_minus_max_num_gpm_cand")
                        : 2;
            }
        }
        s.log2ParallelMergeLevel = r.readUe(ctbLog2 - 2, "sps_log2_parallel_merge_level_minus2") + 2;
        s.isp = r.readFlag();
        s.mrl = r.readFlag();
        s.mip = r.readFlag();
        if (s.chromaFormatIdc != 0) {
            s.cclm = r.readFlag();
        }
        if (s.chromaFormatIdc == 1) {
            s.chromaHorCollocated = r.readFlag();
            s.chromaVerCollocated = r.readFlag();
        }
        s.palette = r.readFlag();
        if (s.chromaFormatIdc == 3 && s.log2MaxTbSize != 6) {
            s.act = r.readFlag();
        }
        if (s.transformSkip || s.palette) {
            s.minQpPrimeTs = r.readUe(8, "sps_min_qp_prime_ts");
        }
        s.ibc = r.readFlag();
        if (s.ibc) {
            s.maxNumIbcMergeCand = 6 - r.readUe(5, "sps_six_minus_max_num_ibc_merge_cand");
        }
        s.ladf = r.readFlag();
        if (s.ladf) {
            s.ladfNumIntervals = r.readBits(2) + 2;
            s.ladfQpOffset[0] = r.readSe(-63, 63, "sps_ladf_lowest_interval_qp_offset");
            for (var i = 0; i < s.ladfNumIntervals - 1; i++) {
                s.ladfQpOffset[i + 1] = r.readSe(-63, 63, "sps_ladf_qp_offset");
                s.ladfLowerBound[i + 1] = s.ladfLowerBound[i]
                        + r.readUe((1 << s.bitDepth) - 3, "sps_ladf_delta_threshold_minus1") + 1;
            }
        }
        s.explicitScalingList = r.readFlag();
        if (s.lfnst && s.explicitScalingList) {
            s.scalingMatrixForLfnstDisabled = r.readFlag();
        }
        if (s.act && s.explicitScalingList) {
            if (r.readFlag()) {
                r.readFlag();
            }
        }
        s.depQuant = r.readFlag();
        s.signDataHiding = r.readFlag();
        s.virtualBoundariesEnabled = r.readFlag();
        if (s.virtualBoundariesEnabled) {
            s.virtualBoundariesPresent = r.readFlag();
            if (s.virtualBoundariesPresent) {
                s.numVerVirtualBoundaries = r.readUe(s.maxWidth <= 8 ? 0 : 3, "sps_num_ver_virtual_boundaries");
                for (var i = 0; i < s.numVerVirtualBoundaries; i++) {
                    s.virtualBoundaryX[i] = (r.readUe((s.maxWidth + 7) / 8 - 2, "sps_virtual_boundary_pos_x_minus1")
                            + 1) << 3;
                }
                s.numHorVirtualBoundaries = r.readUe(s.maxHeight <= 8 ? 0 : 3, "sps_num_hor_virtual_boundaries");
                for (var i = 0; i < s.numHorVirtualBoundaries; i++) {
                    s.virtualBoundaryY[i] = (r.readUe((s.maxHeight + 7) / 8 - 2, "sps_virtual_boundary_pos_y_minus1")
                            + 1) << 3;
                }
            }
        }
        if (ptlPresent && r.readFlag()) {
            s.hrd = VvcHrd.skipGeneralAndOls(r, s.maxSubLayers);
        }
        s.fieldSeq = r.readFlag();
        s.vuiPresent = r.readFlag();
        if (s.vuiPresent) {
            var payloadSize = r.readUe(1023, "sps_vui_payload_size_minus1") + 1;
            r.alignToByte();
            var start = r.bitPosition();
            s.vui.parse(r, payloadSize * 8L);
            r.skip(start + payloadSize * 8L - r.bitPosition());
        }
        if (r.readFlag()) {
            var rangeExtension = r.readFlag();
            r.readBits(7);
            if (rangeExtension) {
                s.extendedPrecision = r.readFlag();
                if (s.transformSkip) {
                    s.tsResidualCodingRiceInSh = r.readFlag();
                }
                s.rrcRiceExtension = r.readFlag();
                s.persistentRiceAdaptation = r.readFlag();
                s.reverseLastSigCoeff = r.readFlag();
            }
        }
        if (r.overrun()) {
            throw new MediaException.Malformed("truncated vvc sequence parameter set");
        }
        return s;
    }

    /**
     * Parses {@code profile_tier_level( 1, maxNumSubLayersMinus1 )}, keeping the profile, tier and level.
     *
     * @param r the reader, positioned at the structure
     * @param s the set receiving the profile, tier and level, or {@code null} to skip them
     * @param profilePresent {@code profileTierPresentFlag}
     * @param maxNumSubLayersMinus1 {@code MaxNumSubLayersMinus1}
     */
    static void parseProfileTierLevel(H264BitReader r, VvcSps s, boolean profilePresent, int maxNumSubLayersMinus1) {
        if (profilePresent) {
            var profile = r.readBits(7);
            var tier = r.readBits(1);
            if (s != null) {
                s.profileIdc = profile;
                s.tierFlag = tier;
            }
        }
        var level = r.readBits(8);
        if (s != null) {
            s.levelIdc = level;
        }
        r.readBits(2);
        if (profilePresent) {
            if (r.readFlag()) {
                // gci_*: 71 constraint flags and fields, then the reserved bits
                r.skip(71);
                var reserved = r.readBits(8);
                r.skip(reserved);
            }
            r.alignToByte();
        }
        var subLayerLevelPresent = new boolean[Math.max(1, maxNumSubLayersMinus1)];
        for (var i = maxNumSubLayersMinus1 - 1; i >= 0; i--) {
            subLayerLevelPresent[i] = r.readFlag();
        }
        r.alignToByte();
        for (var i = maxNumSubLayersMinus1 - 1; i >= 0; i--) {
            if (subLayerLevelPresent[i]) {
                r.readBits(8);
            }
        }
        if (profilePresent) {
            var subProfiles = r.readBits(8);
            for (var i = 0; i < subProfiles; i++) {
                r.readBits(16);
                r.readBits(16);
            }
        }
    }

    /**
     * Parses {@code dpb_parameters( )}.
     *
     * @param r the reader, positioned at the structure
     * @param s the set receiving the parameters
     * @param maxSubLayersMinus1 the highest sublayer index
     * @param subLayerInfo {@code subLayerInfoFlag}: whether every sublayer carries its own parameters
     */
    private static void parseDpbParameters(H264BitReader r, VvcSps s, int maxSubLayersMinus1, boolean subLayerInfo) {
        for (var i = subLayerInfo ? 0 : maxSubLayersMinus1; i <= maxSubLayersMinus1; i++) {
            var buffering = r.readUe(63, "dpb_max_dec_pic_buffering_minus1") + 1;
            s.maxDecPicBuffering[i] = buffering;
            s.maxNumReorder[i] = r.readUe(buffering - 1, "dpb_max_num_reorder_pics");
            s.maxLatencyIncreasePlus1[i] = r.readUe();
        }
        if (!subLayerInfo) {
            for (var i = 0; i < maxSubLayersMinus1; i++) {
                s.maxDecPicBuffering[i] = s.maxDecPicBuffering[maxSubLayersMinus1];
                s.maxNumReorder[i] = s.maxNumReorder[maxSubLayersMinus1];
                s.maxLatencyIncreasePlus1[i] = s.maxLatencyIncreasePlus1[maxSubLayersMinus1];
            }
        }
    }

    /**
     * Parses the subpicture layout and identifier mapping.
     *
     * @param r the reader, positioned after {@code sps_subpic_info_present_flag}
     * @param s the set receiving the layout
     * @param widthInCtus the maximum picture width in CTUs
     * @param heightInCtus the maximum picture height in CTUs
     */
    private static void parseSubpictures(H264BitReader r, VvcSps s, int widthInCtus, int heightInCtus) {
        var count = r.readUe(MAX_SUBPICS - 1, "sps_num_subpics_minus1") + 1;
        if (count > widthInCtus * heightInCtus) {
            throw new MediaException.Malformed("vvc sps_num_subpics_minus1 out of range: " + (count - 1));
        }
        s.numSubpics = count;
        s.subpicCtuX = new int[count];
        s.subpicCtuY = new int[count];
        s.subpicWidth = new int[count];
        s.subpicHeight = new int[count];
        s.subpicTreatedAsPic = new boolean[count];
        s.loopFilterAcrossSubpic = new boolean[count];
        s.subpicId = new int[count];
        if (count == 1) {
            s.subpicWidth[0] = widthInCtus;
            s.subpicHeight[0] = heightInCtus;
            s.independentSubpics = true;
            s.subpicTreatedAsPic[0] = true;
        } else {
            s.independentSubpics = r.readFlag();
            var sameSize = r.readFlag();
            var bitsX = ceilLog2(widthInCtus);
            var bitsY = ceilLog2(heightInCtus);
            for (var i = 0; i < count; i++) {
                if (!sameSize || i == 0) {
                    s.subpicCtuX[i] = i > 0 && s.maxWidth > s.ctuSize ? r.readBits(bitsX) : 0;
                    s.subpicCtuY[i] = i > 0 && s.maxHeight > s.ctuSize ? r.readBits(bitsY) : 0;
                    s.subpicWidth[i] = i < count - 1 && s.maxWidth > s.ctuSize
                            ? r.readBits(bitsX) + 1 : widthInCtus - s.subpicCtuX[i];
                    s.subpicHeight[i] = i < count - 1 && s.maxHeight > s.ctuSize
                            ? r.readBits(bitsY) + 1 : heightInCtus - s.subpicCtuY[i];
                } else {
                    var columns = widthInCtus / s.subpicWidth[0];
                    s.subpicCtuX[i] = (i % columns) * s.subpicWidth[0];
                    s.subpicCtuY[i] = (i / columns) * s.subpicHeight[0];
                    s.subpicWidth[i] = s.subpicWidth[0];
                    s.subpicHeight[i] = s.subpicHeight[0];
                }
                if (s.subpicWidth[i] <= 0 || s.subpicHeight[i] <= 0
                        || s.subpicCtuX[i] + s.subpicWidth[i] > widthInCtus
                        || s.subpicCtuY[i] + s.subpicHeight[i] > heightInCtus) {
                    throw new MediaException.Malformed("vvc subpicture " + i + " outside the picture");
                }
                if (!s.independentSubpics) {
                    s.subpicTreatedAsPic[i] = r.readFlag();
                    s.loopFilterAcrossSubpic[i] = r.readFlag();
                } else {
                    s.subpicTreatedAsPic[i] = true;
                }
            }
        }
        s.subpicIdLen = r.readUe(15, "sps_subpic_id_len_minus1") + 1;
        s.subpicIdMappingExplicit = r.readFlag();
        if (s.subpicIdMappingExplicit) {
            s.subpicIdMappingPresent = r.readFlag();
            if (s.subpicIdMappingPresent) {
                for (var i = 0; i < count; i++) {
                    s.subpicId[i] = r.readBits(s.subpicIdLen);
                }
            }
        }
    }

    /**
     * Parses the chroma QP mapping tables and derives {@link #chromaQpTable} (7.4.3.4, equation 57).
     *
     * @param r the reader, positioned at {@code sps_same_qp_table_for_chroma_flag}
     * @param s the set receiving the tables
     */
    private static void parseChromaQpTables(H264BitReader r, VvcSps s) {
        var sameTable = r.readFlag();
        var numTables = sameTable ? 1 : s.jointCbCr ? 3 : 2;
        var bd = s.qpBdOffset;
        for (var i = 0; i < numTables; i++) {
            var start = r.readSe(-26 - bd, 36, "sps_qp_table_start_minus26") + 26;
            var points = r.readUe(36 - (start - 26), "sps_num_points_in_qp_table_minus1") + 1;
            var inVal = new int[points + 1];
            var outVal = new int[points + 1];
            var deltaIn = new int[points];
            inVal[0] = start;
            outVal[0] = start;
            for (var j = 0; j < points; j++) {
                deltaIn[j] = r.readUe(63 + bd, "sps_delta_qp_in_val_minus1");
                var diff = r.readUe(2 * (63 + bd) + 1, "sps_delta_qp_diff_val");
                inVal[j + 1] = inVal[j] + deltaIn[j] + 1;
                outVal[j + 1] = outVal[j] + (diff ^ deltaIn[j]);
            }
            if (inVal[points] > 63) {
                throw new MediaException.Malformed("vvc chroma QP table out of range");
            }
            var table = new int[64 + bd];
            table[inVal[0] + bd] = outVal[0];
            for (var k = inVal[0] - 1; k >= -bd; k--) {
                table[k + bd] = Math.clamp(table[k + 1 + bd] - 1, -bd, 63);
            }
            for (var j = 0; j < points; j++) {
                var sh = (deltaIn[j] + 1) >> 1;
                for (int k = inVal[j] + 1, m = 1; k <= inVal[j + 1]; k++, m++) {
                    table[k + bd] = table[inVal[j] + bd] + ((outVal[j + 1] - outVal[j]) * m + sh) / (deltaIn[j] + 1);
                }
            }
            for (var k = inVal[points] + 1; k <= 63; k++) {
                table[k + bd] = Math.clamp(table[k - 1 + bd] + 1, -bd, 63);
            }
            s.chromaQpTable[i] = table;
        }
        for (var i = numTables; i < 3; i++) {
            s.chromaQpTable[i] = s.chromaQpTable[0];
        }
    }

    /**
     * Checks a fixed-length field against its range.
     *
     * @param value the value read
     * @param min the smallest valid value
     * @param max the largest valid value
     * @param name the syntax element name, for the message
     * @return {@code value}
     * @throws MediaException.Malformed if the value is out of range
     */
    static int check(int value, int min, int max, String name) {
        if (value < min || value > max) {
            throw new MediaException.Malformed("vvc " + name + " out of range: " + value);
        }
        return value;
    }

    /**
     * Returns {@code Ceil( Log2( value ) )} for a positive value.
     *
     * @param value the value, positive
     * @return the exponent
     */
    static int ceilLog2(int value) {
        return value <= 1 ? 0 : 32 - Integer.numberOfLeadingZeros(value - 1);
    }

    /**
     * {@return the horizontal chroma subsampling shift}
     */
    public int chromaShiftX() {
        return chromaFormatIdc == 1 || chromaFormatIdc == 2 ? 1 : 0;
    }

    /**
     * {@return the vertical chroma subsampling shift}
     */
    public int chromaShiftY() {
        return chromaFormatIdc == 1 ? 1 : 0;
    }
}
