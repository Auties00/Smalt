package com.github.auties00.smalt.video.vp9.internal;

/**
 * Holds the binary trees of the VP9 symbols coded with tree probabilities.
 *
 * <p>Node {@code i} of a tree is the pair {@code tree[i]}, {@code tree[i + 1]}; a positive entry
 * is the index of the next node and a non-positive one the negated symbol, as read by
 * {@link Vp9BoolDecoder#readTree(int[], int[], int)}.
 *
 * @implNote This implementation copies the trees verbatim from libvpx
 *           ({@code vp9/common/vp9_entropymode.c}, {@code vp9_entropymv.c},
 *           {@code vp9_seg_common.c}), BSD-3.
 */
final class Vp9Trees {
    /**
     * Prevents instantiation.
     */
    private Vp9Trees() {
        throw new AssertionError();
    }

    /**
     * The intra mode tree ({@code vp9_intra_mode_tree}).
     */
    static final int[] INTRA_MODE = {
            -Vp9Constants.DC_PRED, 2,
            -Vp9Constants.TM_PRED, 4,
            -Vp9Constants.V_PRED, 6,
            8, 12,
            -Vp9Constants.H_PRED, 10,
            -Vp9Constants.D135_PRED, -Vp9Constants.D117_PRED,
            -Vp9Constants.D45_PRED, 14,
            -Vp9Constants.D63_PRED, 16,
            -Vp9Constants.D153_PRED, -Vp9Constants.D207_PRED
    };

    /**
     * The inter mode tree, whose symbols are the modes minus {@link Vp9Constants#NEARESTMV}
     * ({@code vp9_inter_mode_tree}).
     */
    static final int[] INTER_MODE = {-2, 2, 0, 4, -1, -3};

    /**
     * The partition tree ({@code vp9_partition_tree}).
     */
    static final int[] PARTITION = {
            -Vp9Constants.PARTITION_NONE, 2,
            -Vp9Constants.PARTITION_HORZ, 4,
            -Vp9Constants.PARTITION_VERT, -Vp9Constants.PARTITION_SPLIT
    };

    /**
     * The switchable interpolation filter tree ({@code vp9_switchable_interp_tree}).
     */
    static final int[] SWITCHABLE_INTERP = {
            -Vp9Constants.EIGHTTAP, 2,
            -Vp9Constants.EIGHTTAP_SMOOTH, -Vp9Constants.EIGHTTAP_SHARP
    };

    /**
     * The segment identifier tree ({@code vp9_segment_tree}).
     */
    static final int[] SEGMENT = {2, 4, 6, 8, 10, 12, 0, -1, -2, -3, -4, -5, -6, -7};

    /**
     * The motion vector joint tree ({@code vp9_mv_joint_tree}).
     */
    static final int[] MV_JOINT = {0, 2, -1, 4, -2, -3};

    /**
     * The motion vector class tree ({@code vp9_mv_class_tree}).
     */
    static final int[] MV_CLASS = {
            0, 2, -1, 4, 6, 8, -2, -3, 10, 12,
            -4, -5, -6, 14, 16, 18, -7, -8, -9, -10
    };

    /**
     * The class 0 integer tree ({@code vp9_mv_class0_tree}).
     */
    static final int[] MV_CLASS0 = {0, -1};

    /**
     * The motion vector fraction tree ({@code vp9_mv_fp_tree}).
     */
    static final int[] MV_FP = {0, 2, -1, 4, -2, -3};
}
