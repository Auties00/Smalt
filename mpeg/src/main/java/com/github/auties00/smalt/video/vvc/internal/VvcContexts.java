/* Ports VVdeC 3.3.0 (source/Lib/CommonLib/Contexts.cpp: the context initialization values). Copyright (c)
 * 2018-2026, Fraunhofer-Gesellschaft zur Foerderung der angewandten Forschung e.V. & The VVdeC Authors; Clear BSD
 * License. Modified for Smalt. */
package com.github.auties00.smalt.video.vvc.internal;

/**
 * Holds the context variable initialization values of ITU-T H.266 9.3.2.2: the offset of every context set in
 * the context table, the {@code initValue} of every context for the three initialization types and its
 * {@code shiftIdx}.
 */
final class VvcContexts {
    /**
     * The index of the first context of {@code split_cu_flag}.
     */
    static final int SPLIT_FLAG = 0;
    /**
     * The index of the first context of {@code split_qt_flag}.
     */
    static final int SPLIT_QT_FLAG = 9;
    /**
     * The index of the first context of {@code mtt_split_cu_vertical_flag}.
     */
    static final int SPLIT_HV_FLAG = 15;
    /**
     * The index of the first context of {@code mtt_split_cu_binary_flag}.
     */
    static final int SPLIT12_FLAG = 20;
    /**
     * The index of the first context of {@code non_inter_flag}.
     */
    static final int MODE_CONS_FLAG = 24;
    /**
     * The index of the first context of {@code cu_skip_flag}.
     */
    static final int SKIP_FLAG = 26;
    /**
     * The index of the first context of {@code general_merge_flag}.
     */
    static final int MERGE_FLAG = 29;
    /**
     * The index of the first context of {@code regular_merge_flag}.
     */
    static final int REGULAR_MERGE_FLAG = 30;
    /**
     * The index of the first context of {@code merge_idx}.
     */
    static final int MERGE_IDX = 32;
    /**
     * The index of the first context of {@code mmvd_merge_flag}.
     */
    static final int MMVD_FLAG = 33;
    /**
     * The index of the first context of {@code mmvd_cand_flag}.
     */
    static final int MMVD_MERGE_IDX = 34;
    /**
     * The index of the first context of {@code mmvd_distance_idx}.
     */
    static final int MMVD_STEP_MVP_IDX = 35;
    /**
     * The index of the first context of {@code pred_mode_flag}.
     */
    static final int PRED_MODE = 36;
    /**
     * The index of the first context of {@code intra_luma_ref_idx}.
     */
    static final int MULTI_REF_LINE_IDX = 38;
    /**
     * The index of the first context of {@code intra_luma_mpm_flag}.
     */
    static final int IPRED_MODE_0 = 40;
    /**
     * The index of the first context of {@code intra_chroma_pred_mode}.
     */
    static final int IPRED_MODE_1 = 41;
    /**
     * The index of the first context of {@code intra_luma_not_planar_flag}.
     */
    static final int INTRA_LUMA_PLANAR_FLAG = 42;
    /**
     * The index of the first context of {@code cclm_mode_flag}.
     */
    static final int CCLM_MODE_FLAG = 44;
    /**
     * The index of the first context of {@code cclm_mode_idx}.
     */
    static final int CCLM_MODE_IDX = 45;
    /**
     * The index of the first context of {@code intra_mip_flag}.
     */
    static final int MIP_FLAG = 46;
    /**
     * The index of the first context of {@code cu_qp_delta_abs}.
     */
    static final int DELTA_QP = 50;
    /**
     * The index of the first context of {@code inter_pred_idc}.
     */
    static final int INTER_DIR = 52;
    /**
     * The index of the first context of {@code ref_idx_l0} and {@code ref_idx_l1}.
     */
    static final int REF_PIC = 58;
    /**
     * The index of the first context of {@code merge_subblock_flag}.
     */
    static final int SUBBLOCK_MERGE_FLAG = 60;
    /**
     * The index of the first context of {@code inter_affine_flag}.
     */
    static final int AFFINE_FLAG = 63;
    /**
     * The index of the first context of {@code cu_affine_type_flag}.
     */
    static final int AFFINE_TYPE = 66;
    /**
     * The index of the first context of {@code merge_subblock_idx}.
     */
    static final int AFF_MERGE_IDX = 67;
    /**
     * The index of the first context of {@code bcw_idx}.
     */
    static final int BCW_IDX = 68;
    /**
     * The index of the first context of {@code abs_mvd_greater0_flag} and {@code abs_mvd_greater1_flag}.
     */
    static final int MVD = 69;
    /**
     * The index of the first context of {@code intra_bdpcm_luma_flag}, {@code intra_bdpcm_luma_dir_flag} and their
     * chroma counterparts.
     */
    static final int BDPCMMODE = 71;
    /**
     * The index of the first context of {@code cu_coded_flag}.
     */
    static final int QT_ROOT_CBF = 75;
    /**
     * The index of the first context of {@code cu_act_enabled_flag}.
     */
    static final int ACTFLAG = 76;
    /**
     * The index of the first context of {@code tu_y_coded_flag}.
     */
    static final int QT_CBF_0 = 77;
    /**
     * The index of the first context of {@code tu_cb_coded_flag}.
     */
    static final int QT_CBF_1 = 81;
    /**
     * The index of the first context of {@code tu_cr_coded_flag}.
     */
    static final int QT_CBF_2 = 83;
    /**
     * The index of the first context of {@code sb_coded_flag} of luma.
     */
    static final int SIG_COEFF_GROUP_0 = 86;
    /**
     * The index of the first context of {@code sb_coded_flag} of chroma.
     */
    static final int SIG_COEFF_GROUP_1 = 88;
    /**
     * The index of the first context of {@code sig_coeff_flag} of luma, quantizer state set 0.
     */
    static final int SIG_FLAG_0 = 90;
    /**
     * The index of the first context of {@code sig_coeff_flag} of chroma, quantizer state set 0.
     */
    static final int SIG_FLAG_1 = 102;
    /**
     * The index of the first context of {@code sig_coeff_flag} of luma, quantizer state set 1.
     */
    static final int SIG_FLAG_2 = 110;
    /**
     * The index of the first context of {@code sig_coeff_flag} of chroma, quantizer state set 1.
     */
    static final int SIG_FLAG_3 = 122;
    /**
     * The index of the first context of {@code sig_coeff_flag} of luma, quantizer state set 2.
     */
    static final int SIG_FLAG_4 = 130;
    /**
     * The index of the first context of {@code sig_coeff_flag} of chroma, quantizer state set 2.
     */
    static final int SIG_FLAG_5 = 142;
    /**
     * The index of the first context of {@code par_level_flag} of luma.
     */
    static final int PAR_FLAG_0 = 150;
    /**
     * The index of the first context of {@code par_level_flag} of chroma.
     */
    static final int PAR_FLAG_1 = 171;
    /**
     * The index of the first context of {@code abs_level_gtx_flag[ n ][ 1 ]} of luma.
     */
    static final int GTX_FLAG_0 = 182;
    /**
     * The index of the first context of {@code abs_level_gtx_flag[ n ][ 1 ]} of chroma.
     */
    static final int GTX_FLAG_1 = 203;
    /**
     * The index of the first context of {@code abs_level_gtx_flag[ n ][ 0 ]} of luma.
     */
    static final int GTX_FLAG_2 = 214;
    /**
     * The index of the first context of {@code abs_level_gtx_flag[ n ][ 0 ]} of chroma.
     */
    static final int GTX_FLAG_3 = 235;
    /**
     * The index of the first context of {@code last_sig_coeff_x_prefix} of luma.
     */
    static final int LAST_X_0 = 246;
    /**
     * The index of the first context of {@code last_sig_coeff_x_prefix} of chroma.
     */
    static final int LAST_X_1 = 266;
    /**
     * The index of the first context of {@code last_sig_coeff_y_prefix} of luma.
     */
    static final int LAST_Y_0 = 269;
    /**
     * The index of the first context of {@code last_sig_coeff_y_prefix} of chroma.
     */
    static final int LAST_Y_1 = 289;
    /**
     * The index of the first context of {@code mvp_l0_flag} and {@code mvp_l1_flag}.
     */
    static final int MVPIDX = 292;
    /**
     * The index of the first context of {@code sym_mvd_flag}.
     */
    static final int SMVD_FLAG = 293;
    /**
     * The index of the first context of {@code sao_merge_left_flag} and {@code sao_merge_up_flag}.
     */
    static final int SAO_MERGE_FLAG = 294;
    /**
     * The index of the first context of {@code sao_type_idx_luma} and {@code sao_type_idx_chroma}.
     */
    static final int SAO_TYPE_IDX = 295;
    /**
     * The index of the first context of {@code lfnst_idx}.
     */
    static final int LFNSTIDX = 296;
    /**
     * The index of the first context of the residual DPCM flag, a set of the VVdeC table that no syntax element of this
     * decoder reads.
     */
    static final int RDPCM_FLAG = 299;
    /**
     * The index of the first context of the residual DPCM direction, a set of the VVdeC table that no syntax element of
     * this decoder reads.
     */
    static final int RDPCM_DIR = 301;
    /**
     * The index of the first context of {@code mts_idx}.
     */
    static final int MTSINDEX = 303;
    /**
     * The index of the first context of {@code intra_subpartitions_mode_flag} and {@code
     * intra_subpartitions_split_flag}.
     */
    static final int ISPMODE = 309;
    /**
     * The index of the first context of {@code cu_sbt_flag}.
     */
    static final int SBT_FLAG = 311;
    /**
     * The index of the first context of {@code cu_sbt_quad_flag}.
     */
    static final int SBT_QUAD_FLAG = 313;
    /**
     * The index of the first context of {@code cu_sbt_horizontal_flag}.
     */
    static final int SBT_HOR_FLAG = 314;
    /**
     * The index of the first context of {@code cu_sbt_pos_flag}.
     */
    static final int SBT_POS_FLAG = 317;
    /**
     * The index of the first context of {@code cu_chroma_qp_offset_flag}.
     */
    static final int CHROMA_QP_ADJ_FLAG = 318;
    /**
     * The index of the first context of {@code cu_chroma_qp_offset_idx}.
     */
    static final int CHROMA_QP_ADJ_IDC = 319;
    /**
     * The index of the first context of {@code amvr_flag} and {@code amvr_precision_idx}.
     */
    static final int IMV_FLAG = 320;
    /**
     * The index of the first context of {@code alf_ctb_flag}.
     */
    static final int CTB_ALF_FLAG = 325;
    /**
     * The index of the first context of {@code alf_ctb_filter_alt_idx}.
     */
    static final int CTB_ALF_ALTERNATIVE = 334;
    /**
     * The index of the first context of {@code alf_use_aps_flag}.
     */
    static final int ALF_USE_TEMPORAL_FILT = 336;
    /**
     * The index of the first context of {@code alf_ctb_cc_cb_idc} and {@code alf_ctb_cc_cr_idc}.
     */
    static final int CC_ALF_FILTER_CONTROL_FLAG = 337;
    /**
     * The index of the first context of {@code ciip_flag}.
     */
    static final int CIIP_FLAG = 343;
    /**
     * The index of the first context of {@code pred_mode_ibc_flag}.
     */
    static final int IBCFLAG = 344;
    /**
     * The index of the first context of {@code tu_joint_cbcr_residual_flag}.
     */
    static final int JOINT_CB_CR_FLAG = 347;
    /**
     * The index of the first context of {@code sb_coded_flag} of transform skip residuals.
     */
    static final int TS_SIG_COEFF_GROUP = 350;
    /**
     * The index of the first context of {@code sig_coeff_flag} of transform skip residuals.
     */
    static final int TS_SIG_FLAG = 353;
    /**
     * The index of the first context of {@code par_level_flag} of transform skip residuals.
     */
    static final int TS_PAR_FLAG = 356;
    /**
     * The index of the first context of the {@code abs_level_gtx_flag[ n ][ j ]} after the first of transform skip
     * residuals.
     */
    static final int TS_GTX_FLAG = 357;
    /**
     * The index of the first context of {@code abs_level_gtx_flag[ n ][ 0 ]} of transform skip residuals.
     */
    static final int TS_LRG1_FLAG = 362;
    /**
     * The index of the first context of {@code coeff_sign_flag} of transform skip residuals.
     */
    static final int TS_RESIDUAL_SIGN = 366;
    /**
     * The number of contexts.
     */
    static final int COUNT = 372;
    /**
     * The {@code initValue} of every context of the table of B slices ({@code sh_slice_type} 0).
     */
    private static final byte[] INIT_0 = {
            18, 27, 15, 18, 28, 45, 26, 7, 23, 26, 36, 38, 18, 34, 21, 43, 42, 37, 42, 44,
            28, 29, 28, 29, 25, 20, 57, 60, 46, 6, 46, 15, 18, 25, 43, 59, 40, 35, 25, 59,
            44, 25, 13, 6, 26, 27, 56, 57, 50, 26, 35, 35, 14, 13, 5, 4, 3, 40, 5, 35,
            25, 58, 45, 19, 13, 6, 35, 4, 5, 51, 36, 19, 21, 0, 28, 12, 46, 15, 6, 5,
            14, 25, 37, 9, 36, 45, 25, 45, 25, 14, 17, 41, 49, 36, 1, 49, 50, 37, 48, 51,
            58, 45, 9, 49, 50, 36, 48, 59, 59, 38, 26, 45, 53, 46, 49, 54, 61, 39, 35, 39,
            39, 39, 34, 45, 38, 31, 58, 39, 39, 39, 19, 54, 39, 39, 50, 39, 39, 39, 0, 39,
            39, 39, 34, 38, 54, 39, 41, 39, 39, 39, 33, 40, 25, 41, 26, 42, 25, 33, 26, 34,
            27, 25, 41, 42, 42, 35, 33, 27, 35, 42, 43, 33, 25, 26, 34, 19, 27, 33, 42, 43,
            35, 43, 25, 0, 0, 17, 25, 26, 0, 9, 25, 33, 19, 0, 25, 33, 26, 20, 25, 33,
            27, 35, 22, 25, 1, 25, 33, 26, 12, 25, 33, 27, 28, 37, 0, 0, 33, 34, 35, 21,
            25, 34, 35, 28, 29, 40, 42, 43, 29, 30, 49, 36, 37, 45, 38, 0, 40, 34, 43, 36,
            37, 57, 52, 45, 38, 46, 6, 6, 12, 14, 6, 4, 14, 7, 6, 4, 29, 7, 6, 6,
            12, 28, 7, 13, 13, 35, 19, 5, 4, 5, 5, 20, 13, 13, 19, 21, 6, 12, 12, 14,
            14, 5, 4, 12, 13, 7, 13, 12, 41, 11, 5, 27, 34, 28, 2, 2, 52, 37, 27, 35,
            35, 35, 35, 45, 25, 27, 0, 25, 17, 33, 43, 41, 57, 42, 35, 51, 27, 28, 35, 35,
            59, 26, 50, 60, 38, 33, 52, 46, 25, 61, 54, 25, 61, 54, 11, 26, 46, 25, 35, 38,
            25, 28, 38, 57, 0, 43, 45, 42, 43, 52, 18, 35, 45, 25, 50, 37, 11, 35, 3, 4,
            4, 5, 19, 11, 4, 6, 35, 25, 46, 28, 33, 38
    };
    /**
     * The {@code initValue} of every context of the table of P slices ({@code sh_slice_type} 1).
     */
    private static final byte[] INIT_1 = {
            11, 35, 53, 12, 6, 30, 13, 15, 31, 20, 14, 23, 18, 19, 6, 43, 35, 37, 34, 52,
            43, 37, 21, 22, 25, 12, 57, 59, 45, 21, 38, 7, 20, 26, 43, 60, 40, 35, 25, 58,
            36, 25, 12, 20, 34, 27, 41, 57, 58, 26, 35, 35, 7, 6, 5, 12, 4, 40, 20, 35,
            48, 57, 44, 12, 13, 14, 35, 5, 4, 44, 43, 40, 36, 0, 13, 5, 46, 23, 5, 20,
            7, 25, 28, 25, 29, 45, 25, 30, 25, 45, 17, 41, 42, 29, 25, 49, 43, 37, 33, 58,
            51, 30, 17, 34, 35, 21, 41, 59, 60, 38, 19, 38, 38, 46, 34, 54, 54, 39, 6, 39,
            39, 39, 35, 45, 53, 54, 44, 39, 39, 39, 19, 39, 54, 39, 19, 39, 39, 39, 56, 39,
            39, 39, 34, 38, 62, 39, 26, 39, 39, 39, 18, 17, 33, 18, 26, 42, 25, 33, 26, 42,
            27, 25, 34, 42, 42, 35, 26, 27, 42, 20, 20, 25, 25, 26, 11, 19, 27, 33, 42, 35,
            35, 43, 17, 0, 1, 17, 25, 18, 0, 9, 25, 33, 34, 9, 25, 18, 26, 20, 25, 18,
            19, 27, 29, 17, 9, 25, 10, 18, 4, 17, 33, 19, 20, 29, 0, 17, 26, 19, 35, 21,
            25, 34, 20, 28, 29, 33, 27, 28, 29, 22, 34, 28, 44, 37, 38, 0, 25, 19, 20, 13,
            14, 57, 44, 30, 30, 23, 6, 13, 12, 6, 6, 12, 14, 14, 13, 12, 29, 7, 6, 13,
            36, 28, 14, 13, 5, 26, 12, 4, 18, 5, 5, 12, 6, 6, 4, 6, 14, 5, 12, 14,
            7, 13, 5, 13, 21, 14, 20, 12, 34, 11, 4, 18, 34, 28, 60, 5, 37, 45, 27, 35,
            35, 35, 35, 45, 40, 27, 0, 25, 9, 33, 36, 56, 57, 42, 20, 43, 12, 28, 35, 35,
            59, 48, 58, 60, 60, 13, 23, 46, 4, 61, 54, 19, 46, 54, 20, 12, 46, 18, 21, 38,
            18, 21, 38, 57, 0, 57, 44, 27, 36, 45, 18, 12, 29, 40, 35, 44, 3, 35, 2, 10,
            3, 3, 18, 11, 4, 28, 5, 10, 53, 43, 25, 46
    };
    /**
     * The {@code initValue} of every context of the table of I slices ({@code sh_slice_type} 2).
     */
    private static final byte[] INIT_2 = {
            19, 28, 38, 27, 29, 38, 20, 30, 31, 27, 6, 15, 25, 19, 37, 43, 42, 29, 27, 44,
            36, 45, 36, 45, 35, 35, 0, 26, 28, 26, 35, 35, 34, 35, 35, 35, 35, 35, 25, 60,
            45, 34, 13, 28, 59, 27, 33, 49, 50, 25, 35, 35, 35, 35, 35, 35, 35, 35, 35, 35,
            35, 35, 35, 35, 35, 35, 35, 35, 35, 14, 45, 19, 35, 1, 27, 6, 52, 15, 12, 5,
            7, 12, 21, 33, 28, 36, 18, 31, 25, 15, 25, 19, 28, 14, 25, 20, 29, 30, 19, 37,
            30, 38, 25, 27, 28, 37, 34, 53, 53, 46, 11, 38, 46, 54, 27, 39, 39, 39, 44, 39,
            39, 39, 19, 46, 38, 39, 52, 39, 39, 39, 18, 39, 39, 39, 27, 39, 39, 39, 0, 39,
            39, 39, 11, 39, 39, 39, 19, 39, 39, 39, 33, 25, 18, 26, 34, 27, 25, 26, 19, 42,
            35, 33, 19, 27, 35, 35, 34, 42, 20, 43, 20, 33, 25, 26, 42, 19, 27, 26, 50, 35,
            20, 43, 25, 1, 40, 25, 33, 11, 17, 25, 25, 18, 4, 17, 33, 26, 19, 13, 33, 19,
            20, 28, 22, 40, 9, 25, 18, 26, 35, 25, 26, 35, 28, 37, 25, 25, 11, 27, 20, 21,
            33, 12, 28, 21, 22, 34, 28, 29, 29, 30, 36, 29, 45, 30, 23, 40, 33, 27, 28, 21,
            37, 36, 37, 45, 38, 46, 13, 5, 4, 21, 14, 4, 6, 14, 21, 11, 14, 7, 14, 5,
            11, 21, 30, 22, 13, 42, 12, 4, 3, 13, 5, 4, 6, 13, 11, 14, 6, 5, 3, 14,
            22, 6, 4, 3, 6, 22, 29, 20, 34, 12, 4, 3, 42, 35, 60, 13, 28, 52, 42, 35,
            35, 35, 35, 29, 0, 28, 0, 25, 9, 33, 43, 35, 35, 35, 35, 35, 35, 35, 35, 35,
            35, 34, 35, 35, 35, 62, 39, 39, 54, 39, 39, 31, 39, 39, 11, 11, 46, 18, 30, 31,
            18, 30, 31, 35, 17, 42, 36, 12, 21, 35, 18, 20, 38, 25, 28, 38, 11, 35, 10, 3,
            3, 3, 11, 5, 5, 14, 12, 17, 46, 28, 25, 46
    };
    /**
     * The {@code shiftIdx} of every context.
     */
    private static final byte[] INIT_3 = {
            12, 13, 8, 8, 13, 12, 5, 9, 9, 0, 8, 8, 12, 12, 8, 9, 8, 9, 8, 5,
            12, 13, 12, 13, 1, 0, 5, 4, 8, 4, 5, 5, 4, 4, 10, 0, 5, 1, 5, 8,
            6, 5, 1, 5, 4, 9, 9, 10, 9, 6, 8, 8, 0, 0, 1, 4, 4, 0, 0, 4,
            4, 4, 4, 4, 0, 0, 4, 0, 1, 9, 5, 1, 4, 1, 0, 4, 1, 5, 1, 8,
            9, 5, 0, 2, 1, 0, 8, 5, 5, 8, 12, 9, 9, 10, 9, 9, 9, 10, 8, 8,
            8, 10, 12, 12, 9, 13, 4, 5, 8, 9, 9, 13, 8, 8, 8, 8, 8, 5, 8, 0,
            0, 0, 8, 12, 12, 8, 4, 0, 0, 0, 8, 8, 8, 8, 8, 0, 4, 4, 0, 0,
            0, 0, 8, 8, 8, 8, 4, 0, 0, 0, 8, 9, 12, 13, 13, 13, 10, 13, 13, 13,
            13, 13, 13, 13, 13, 13, 10, 13, 13, 13, 13, 8, 12, 12, 12, 13, 13, 13, 13, 13,
            13, 13, 1, 5, 9, 9, 9, 6, 5, 9, 10, 10, 9, 9, 9, 9, 9, 9, 6, 8,
            9, 9, 10, 1, 5, 8, 8, 9, 6, 6, 9, 8, 8, 9, 9, 5, 10, 13, 13, 10,
            9, 10, 13, 13, 13, 9, 10, 10, 10, 13, 8, 9, 10, 10, 13, 8, 8, 9, 12, 12,
            10, 5, 9, 9, 9, 13, 8, 5, 4, 5, 4, 4, 5, 4, 1, 0, 4, 1, 0, 0,
            0, 0, 1, 0, 0, 0, 5, 4, 4, 8, 5, 8, 5, 5, 4, 5, 5, 4, 0, 5,
            4, 1, 0, 0, 1, 4, 0, 0, 0, 6, 5, 5, 12, 5, 0, 4, 9, 9, 10, 8,
            8, 8, 8, 8, 0, 9, 0, 1, 1, 9, 2, 1, 5, 10, 8, 4, 1, 13, 8, 8,
            0, 5, 0, 0, 4, 0, 0, 0, 4, 0, 0, 1, 0, 0, 0, 0, 0, 4, 1, 4,
            4, 1, 4, 1, 1, 5, 8, 1, 1, 0, 5, 8, 8, 13, 13, 8, 6, 8, 1, 1,
            1, 1, 4, 2, 1, 6, 1, 4, 4, 5, 8, 8
    };

    /**
     * The {@code initValue} tables indexed by initialization type (B, P, I as in {@code sh_slice_type}) and the
     * {@code shiftIdx} table at index 3.
     */
    static final byte[][] INIT = {INIT_0, INIT_1, INIT_2, INIT_3};

    /**
     * Prevents instantiation.
     */
    private VvcContexts() {
    }
}
