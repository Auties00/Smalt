package com.github.auties00.smalt.video.hevc.internal;

/**
 * Holds the CABAC tables of ITU-T H.265 9.3: the arithmetic decoder's range and state transition
 * tables (Tables 9-52 and 9-53) and the context variable layout with the initialization values of
 * Tables 9-5 to 9-42.
 *
 * <p>All context variables of a slice live in one array; each syntax element owns a contiguous run
 * of it starting at its offset constant ({@link #SAO_MERGE} and the following ones), and
 * {@link #init(int, int, byte[])} fills the whole array for an initialization type.
 */
public final class CabacTables {
    /**
     * {@code rangeTabLps}, indexed {@code pStateIdx * 4 + qRangeIdx} (Table 9-52).
     */
    public static final int[] LPS_RANGE = {
            128, 176, 208, 240, 128, 167, 197, 227, 128, 158, 187, 216, 123, 150, 178, 205,
            116, 142, 169, 195, 111, 135, 160, 185, 105, 128, 152, 175, 100, 122, 144, 166,
            95, 116, 137, 158, 90, 110, 130, 150, 85, 104, 123, 142, 81, 99, 117, 135,
            77, 94, 111, 128, 73, 89, 105, 122, 69, 85, 100, 116, 66, 80, 95, 110,
            62, 76, 90, 104, 59, 72, 86, 99, 56, 69, 81, 94, 53, 65, 77, 89,
            51, 62, 73, 85, 48, 59, 69, 80, 46, 56, 66, 76, 43, 53, 63, 72,
            41, 50, 59, 69, 39, 48, 56, 65, 37, 45, 54, 62, 35, 43, 51, 59,
            33, 41, 48, 56, 32, 39, 46, 53, 30, 37, 43, 50, 29, 35, 41, 48,
            27, 33, 39, 45, 26, 31, 37, 43, 24, 30, 35, 41, 23, 28, 33, 39,
            22, 27, 32, 37, 21, 26, 30, 35, 20, 24, 29, 33, 19, 23, 27, 31,
            18, 22, 26, 30, 17, 21, 25, 28, 16, 20, 23, 27, 15, 19, 22, 25,
            14, 18, 21, 24, 14, 17, 20, 23, 13, 16, 19, 22, 12, 15, 18, 21,
            12, 14, 17, 20, 11, 14, 16, 19, 11, 13, 15, 18, 10, 12, 15, 17,
            10, 12, 14, 16, 9, 11, 13, 15, 9, 11, 12, 14, 8, 10, 12, 14,
            8, 9, 11, 13, 7, 9, 11, 12, 7, 9, 10, 12, 7, 8, 10, 11,
            6, 8, 9, 11, 6, 7, 9, 10, 6, 7, 8, 9, 2, 2, 2, 2
    };

    /**
     * {@code transIdxLps} (Table 9-53).
     */
    public static final int[] TRANS_LPS = {
            0, 0, 1, 2, 2, 4, 4, 5, 6, 7, 8, 9, 9, 11, 11, 12,
            13, 13, 15, 15, 16, 16, 18, 18, 19, 19, 21, 21, 22, 22, 23, 24,
            24, 25, 26, 26, 27, 27, 28, 29, 29, 30, 30, 30, 31, 32, 32, 33,
            33, 33, 34, 34, 35, 35, 35, 36, 36, 36, 37, 37, 37, 38, 38, 63
    };

    /**
     * The offset of {@code sao_merge_left_flag} and {@code sao_merge_up_flag} (1 context).
     */
    public static final int SAO_MERGE = 0;

    /**
     * The offset of {@code sao_type_idx_luma} and {@code sao_type_idx_chroma} (1 context).
     */
    public static final int SAO_TYPE = SAO_MERGE + 1;

    /**
     * The offset of {@code split_cu_flag} (3 contexts).
     */
    public static final int SPLIT_CU = SAO_TYPE + 1;

    /**
     * The offset of {@code cu_transquant_bypass_flag} (1 context).
     */
    public static final int TRANSQUANT_BYPASS = SPLIT_CU + 3;

    /**
     * The offset of {@code cu_skip_flag} (3 contexts).
     */
    public static final int CU_SKIP = TRANSQUANT_BYPASS + 1;

    /**
     * The offset of {@code pred_mode_flag} (1 context).
     */
    public static final int PRED_MODE = CU_SKIP + 3;

    /**
     * The offset of {@code part_mode} (4 contexts).
     */
    public static final int PART_MODE = PRED_MODE + 1;

    /**
     * The offset of {@code prev_intra_luma_pred_flag} (1 context).
     */
    public static final int PREV_INTRA_LUMA = PART_MODE + 4;

    /**
     * The offset of {@code intra_chroma_pred_mode} (1 context).
     */
    public static final int INTRA_CHROMA = PREV_INTRA_LUMA + 1;

    /**
     * The offset of {@code rqt_root_cbf} (1 context).
     */
    public static final int RQT_ROOT_CBF = INTRA_CHROMA + 1;

    /**
     * The offset of {@code merge_flag} (1 context).
     */
    public static final int MERGE_FLAG = RQT_ROOT_CBF + 1;

    /**
     * The offset of {@code merge_idx} (1 context).
     */
    public static final int MERGE_IDX = MERGE_FLAG + 1;

    /**
     * The offset of {@code inter_pred_idc} (5 contexts).
     */
    public static final int INTER_PRED_IDC = MERGE_IDX + 1;

    /**
     * The offset of {@code ref_idx_l0} and {@code ref_idx_l1} (2 contexts).
     */
    public static final int REF_IDX = INTER_PRED_IDC + 5;

    /**
     * The offset of {@code mvp_l0_flag} and {@code mvp_l1_flag} (1 context).
     */
    public static final int MVP_FLAG = REF_IDX + 2;

    /**
     * The offset of {@code split_transform_flag} (3 contexts).
     */
    public static final int SPLIT_TRANSFORM = MVP_FLAG + 1;

    /**
     * The offset of {@code cbf_luma} (2 contexts).
     */
    public static final int CBF_LUMA = SPLIT_TRANSFORM + 3;

    /**
     * The offset of {@code cbf_cb} and {@code cbf_cr} (5 contexts).
     */
    public static final int CBF_CHROMA = CBF_LUMA + 2;

    /**
     * The offset of {@code abs_mvd_greater0_flag} (1 context).
     */
    public static final int MVD_GREATER0 = CBF_CHROMA + 5;

    /**
     * The offset of {@code abs_mvd_greater1_flag} (1 context).
     */
    public static final int MVD_GREATER1 = MVD_GREATER0 + 1;

    /**
     * The offset of {@code cu_qp_delta_abs} (2 contexts).
     */
    public static final int CU_QP_DELTA = MVD_GREATER1 + 1;

    /**
     * The offset of {@code transform_skip_flag} (2 contexts: luma, then chroma).
     */
    public static final int TRANSFORM_SKIP = CU_QP_DELTA + 2;

    /**
     * The offset of {@code last_sig_coeff_x_prefix} (18 contexts).
     */
    public static final int LAST_X = TRANSFORM_SKIP + 2;

    /**
     * The offset of {@code last_sig_coeff_y_prefix} (18 contexts).
     */
    public static final int LAST_Y = LAST_X + 18;

    /**
     * The offset of {@code coded_sub_block_flag} (4 contexts).
     */
    public static final int CODED_SUB_BLOCK = LAST_Y + 18;

    /**
     * The offset of {@code sig_coeff_flag} (44 contexts: 27 luma, 15 chroma and the 2 transform
     * skip contexts).
     */
    public static final int SIG_COEFF = CODED_SUB_BLOCK + 4;

    /**
     * The offset of {@code coeff_abs_level_greater1_flag} (24 contexts).
     */
    public static final int GREATER1 = SIG_COEFF + 44;

    /**
     * The offset of {@code coeff_abs_level_greater2_flag} (6 contexts).
     */
    public static final int GREATER2 = GREATER1 + 24;

    /**
     * The first context of {@code explicit_rdpcm_flag}: one for luma and one for chroma (Table
     * 9-32).
     */
    public static final int EXPLICIT_RDPCM = GREATER2 + 6;

    /**
     * The first context of {@code explicit_rdpcm_dir_flag}: one for luma and one for chroma
     * (Table 9-33).
     */
    public static final int EXPLICIT_RDPCM_DIR = EXPLICIT_RDPCM + 2;

    /**
     * The first context of {@code log2_res_scale_abs_plus1}, {@code 4 * c + binIdx} (Table 9-36).
     */
    public static final int LOG2_RES_SCALE = EXPLICIT_RDPCM_DIR + 2;

    /**
     * The first context of {@code res_scale_sign_flag}, one per chroma component (Table 9-37).
     */
    public static final int RES_SCALE_SIGN = LOG2_RES_SCALE + 8;

    /**
     * The context of {@code cu_chroma_qp_offset_flag} (Table 9-34).
     */
    public static final int CU_CHROMA_QP_OFFSET_FLAG = RES_SCALE_SIGN + 2;

    /**
     * The context of every bin of {@code cu_chroma_qp_offset_idx} (Table 9-35).
     */
    public static final int CU_CHROMA_QP_OFFSET_IDX = CU_CHROMA_QP_OFFSET_FLAG + 1;

    /**
     * The context of {@code palette_mode_flag} (Table 9-38).
     */
    public static final int PALETTE_MODE = CU_CHROMA_QP_OFFSET_IDX + 1;

    /**
     * The context of {@code tu_residual_act_flag} (Table 9-39).
     */
    public static final int ACT_FLAG = PALETTE_MODE + 1;

    /**
     * The context of {@code copy_above_palette_indices_flag} and
     * {@code copy_above_indices_for_final_run_flag} (Table 9-41).
     */
    public static final int PALETTE_COPY_ABOVE = ACT_FLAG + 1;

    /**
     * The context of {@code palette_transpose_flag} (Table 9-42).
     */
    public static final int PALETTE_TRANSPOSE = PALETTE_COPY_ABOVE + 1;

    /**
     * The first of the 8 contexts of {@code palette_run_prefix} (Table 9-40, 9.3.4.2.8).
     */
    public static final int PALETTE_RUN = PALETTE_TRANSPOSE + 1;

    /**
     * The number of context variables.
     */
    public static final int COUNT = PALETTE_RUN + 8;

    /**
     * The initialization values, indexed {@code [initType][ctxIdx]}.
     */
    private static final int[][] INIT = new int[3][COUNT];

    static {
        put(SAO_MERGE, new int[]{153}, new int[]{153}, new int[]{153});
        put(SAO_TYPE, new int[]{200}, new int[]{185}, new int[]{160});
        put(SPLIT_CU, new int[]{139, 141, 157}, new int[]{107, 139, 126}, new int[]{107, 139, 126});
        put(TRANSQUANT_BYPASS, new int[]{154}, new int[]{154}, new int[]{154});
        put(CU_SKIP, new int[]{154, 154, 154}, new int[]{197, 185, 201}, new int[]{197, 185, 201});
        put(PRED_MODE, new int[]{154}, new int[]{149}, new int[]{134});
        put(PART_MODE, new int[]{184, 154, 154, 154}, new int[]{154, 139, 154, 154}, new int[]{154, 139, 154, 154});
        put(PREV_INTRA_LUMA, new int[]{184}, new int[]{154}, new int[]{183});
        put(INTRA_CHROMA, new int[]{63}, new int[]{152}, new int[]{152});
        put(RQT_ROOT_CBF, new int[]{154}, new int[]{79}, new int[]{79});
        put(MERGE_FLAG, new int[]{154}, new int[]{110}, new int[]{154});
        put(MERGE_IDX, new int[]{154}, new int[]{122}, new int[]{137});
        put(INTER_PRED_IDC, new int[]{154, 154, 154, 154, 154}, new int[]{95, 79, 63, 31, 31}, new int[]{95, 79, 63, 31, 31});
        put(REF_IDX, new int[]{154, 154}, new int[]{153, 153}, new int[]{153, 153});
        put(MVP_FLAG, new int[]{154}, new int[]{168}, new int[]{168});
        put(SPLIT_TRANSFORM, new int[]{153, 138, 138}, new int[]{124, 138, 94}, new int[]{224, 167, 122});
        put(CBF_LUMA, new int[]{111, 141}, new int[]{153, 111}, new int[]{153, 111});
        put(CBF_CHROMA, new int[]{94, 138, 182, 154, 154}, new int[]{149, 107, 167, 154, 154}, new int[]{149, 92, 167, 154, 154});
        put(MVD_GREATER0, new int[]{154}, new int[]{140}, new int[]{169});
        put(MVD_GREATER1, new int[]{154}, new int[]{198}, new int[]{198});
        put(CU_QP_DELTA, new int[]{154, 154}, new int[]{154, 154}, new int[]{154, 154});
        put(TRANSFORM_SKIP, new int[]{139, 139}, new int[]{139, 139}, new int[]{139, 139});
        var last0 = new int[]{110, 110, 124, 125, 140, 153, 125, 127, 140, 109, 111, 143, 127, 111, 79, 108, 123, 63};
        var last1 = new int[]{125, 110, 94, 110, 95, 79, 125, 111, 110, 78, 110, 111, 111, 95, 94, 108, 123, 108};
        var last2 = new int[]{125, 110, 124, 110, 95, 94, 125, 111, 111, 79, 125, 126, 111, 111, 79, 108, 123, 93};
        put(LAST_X, last0, last1, last2);
        put(LAST_Y, last0, last1, last2);
        put(CODED_SUB_BLOCK, new int[]{91, 171, 134, 141}, new int[]{121, 140, 61, 154}, new int[]{121, 140, 61, 154});
        var sig = new int[]{
                111, 111, 125, 110, 110, 94, 124, 108, 124, 107, 125, 141, 179, 153, 125, 107,
                125, 141, 179, 153, 125, 107, 125, 141, 179, 153, 125, 140, 139, 182, 182, 152,
                136, 152, 136, 153, 136, 139, 111, 136, 139, 111, 155, 154, 139, 153, 139, 123,
                123, 63, 153, 166, 183, 140, 136, 153, 154, 166, 183, 140, 136, 153, 154, 166,
                183, 140, 136, 153, 154, 170, 153, 123, 123, 107, 121, 107, 121, 167, 151, 183,
                140, 151, 183, 140, 170, 154, 139, 153, 139, 123, 123, 63, 124, 166, 183, 140,
                136, 153, 154, 166, 183, 140, 136, 153, 154, 166, 183, 140, 136, 153, 154, 170,
                153, 138, 138, 122, 121, 122, 121, 167, 151, 183, 140, 151, 183, 140, 141, 111,
                140, 140, 140, 140
        };
        for (var type = 0; type < 3; type++) {
            for (var i = 0; i < 42; i++) {
                INIT[type][SIG_COEFF + i] = sig[42 * type + i];
            }
            INIT[type][SIG_COEFF + 42] = sig[126 + 2 * type];
            INIT[type][SIG_COEFF + 43] = sig[127 + 2 * type];
        }
        var greater1 = new int[]{
                140, 92, 137, 138, 140, 152, 138, 139, 153, 74, 149, 92, 139, 107, 122, 152,
                140, 179, 166, 182, 140, 227, 122, 197, 154, 196, 196, 167, 154, 152, 167, 182,
                182, 134, 149, 136, 153, 121, 136, 137, 169, 194, 166, 167, 154, 167, 137, 182,
                154, 196, 167, 167, 154, 152, 167, 182, 182, 134, 149, 136, 153, 121, 136, 122,
                169, 208, 166, 167, 154, 152, 167, 182
        };
        var greater2 = new int[]{138, 153, 136, 167, 152, 152, 107, 167, 91, 122, 107, 167, 107, 167, 91, 107, 107, 167};
        for (var type = 0; type < 3; type++) {
            System.arraycopy(greater1, 24 * type, INIT[type], GREATER1, 24);
            System.arraycopy(greater2, 6 * type, INIT[type], GREATER2, 6);
            java.util.Arrays.fill(INIT[type], EXPLICIT_RDPCM, LOG2_RES_SCALE, 139);
            java.util.Arrays.fill(INIT[type], LOG2_RES_SCALE, COUNT, 154);
        }
    }

    /**
     * Prevents instantiation.
     */
    private CabacTables() {
        throw new AssertionError();
    }

    /**
     * Stores the initialization values of one syntax element for the three initialization types.
     *
     * @param offset the context offset of the syntax element
     * @param type0  the values of initialization type 0
     * @param type1  the values of initialization type 1
     * @param type2  the values of initialization type 2
     */
    private static void put(int offset, int[] type0, int[] type1, int[] type2) {
        System.arraycopy(type0, 0, INIT[0], offset, type0.length);
        System.arraycopy(type1, 0, INIT[1], offset, type1.length);
        System.arraycopy(type2, 0, INIT[2], offset, type2.length);
    }

    /**
     * Initializes every context variable for a slice (ITU-T H.265 9.3.2.2).
     *
     * @param initType the initialization type, 0 to 2 (equation 9-7)
     * @param sliceQp  {@code SliceQpY}
     * @param states   receives the states as {@code (pStateIdx << 1) | valMps}, {@link #COUNT}
     *                 entries
     */
    public static void init(int initType, int sliceQp, byte[] states) {
        var qp = Math.clamp(sliceQp, 0, 51);
        var values = INIT[initType];
        for (var i = 0; i < COUNT; i++) {
            var initValue = values[i];
            var m = (initValue >> 4) * 5 - 45;
            var n = ((initValue & 15) << 3) - 16;
            var pre = Math.clamp(((m * qp) >> 4) + n, 1, 126);
            states[i] = (byte) (pre <= 63 ? (63 - pre) << 1 : ((pre - 64) << 1) | 1);
        }
    }
}
