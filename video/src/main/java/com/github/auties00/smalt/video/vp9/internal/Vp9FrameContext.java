/* Ports libvpx (vp9/common/vp9_entropymode.h). Copyright (c) 2010, The WebM Project authors; BSD 3-Clause License.
   Modified for Smalt. */
package com.github.auties00.smalt.video.vp9.internal;

import java.util.Arrays;

/**
 * Holds one set of VP9 symbol probabilities, the {@code FRAME_CONTEXT} of the specification.
 *
 * <p>Every probability lives in the single array {@link #probs}, at the offset of its table
 * plus its index within the table, so that a context is copied with one array copy. Each
 * offset constant names the dimensions of its table.
 *
 * @implNote This implementation follows the layout of libvpx's {@code FRAME_CONTEXT}
 * ({@code vp9/common/vp9_entropymode.h}) and fills the defaults of
 * {@code vp9_default_coef_probs}, {@code init_mode_probs} and
 * {@code vp9_init_mv_probs}.
 */
final class Vp9FrameContext {
    /**
     * The offset of the luma intra mode probabilities of inter frames, {@code [size group 4][node 9]}.
     */
    public static final int Y_MODE = 0;

    /**
     * The offset of the chroma intra mode probabilities of inter frames, {@code [luma mode 10][node 9]}.
     */
    public static final int UV_MODE = Y_MODE + 4 * 9;

    /**
     * The offset of the partition probabilities of inter frames, {@code [context 16][node 3]}.
     */
    public static final int PARTITION = UV_MODE + 10 * 9;

    /**
     * The offset of the coefficient probabilities,
     * {@code [transform size 4][plane type 2][reference 2][band 6][context 6][node 3]}.
     */
    public static final int COEF = PARTITION + 16 * 3;

    /**
     * The offset of the switchable interpolation filter probabilities, {@code [context 4][node 2]}.
     */
    public static final int SWITCHABLE_INTERP = COEF + 4 * 2 * 2 * 6 * 6 * 3;

    /**
     * The offset of the inter mode probabilities, {@code [context 7][node 3]}.
     */
    public static final int INTER_MODE = SWITCHABLE_INTERP + 4 * 2;

    /**
     * The offset of the intra or inter probabilities, {@code [context 4]}.
     */
    public static final int INTRA_INTER = INTER_MODE + 7 * 3;

    /**
     * The offset of the compound prediction probabilities, {@code [context 5]}.
     */
    public static final int COMP_INTER = INTRA_INTER + 4;

    /**
     * The offset of the single reference probabilities, {@code [context 5][node 2]}.
     */
    public static final int SINGLE_REF = COMP_INTER + 5;

    /**
     * The offset of the compound reference probabilities, {@code [context 5]}.
     */
    public static final int COMP_REF = SINGLE_REF + 5 * 2;

    /**
     * The offset of the transform size probabilities of blocks whose largest transform is 8x8,
     * {@code [context 2][node 1]}.
     */
    public static final int TX_8X8 = COMP_REF + 5;

    /**
     * The offset of the transform size probabilities of blocks whose largest transform is
     * 16x16, {@code [context 2][node 2]}.
     */
    public static final int TX_16X16 = TX_8X8 + 2;

    /**
     * The offset of the transform size probabilities of blocks whose largest transform is
     * 32x32, {@code [context 2][node 3]}.
     */
    public static final int TX_32X32 = TX_16X16 + 2 * 2;

    /**
     * The offset of the skip probabilities, {@code [context 3]}.
     */
    public static final int SKIP = TX_32X32 + 2 * 3;

    /**
     * The offset of the motion vector joint probabilities, {@code [node 3]}.
     */
    public static final int MV_JOINTS = SKIP + 3;

    /**
     * The offset of the motion vector sign probabilities, {@code [component 2]}; component 0 is
     * the vertical one.
     */
    public static final int MV_SIGN = MV_JOINTS + 3;

    /**
     * The offset of the motion vector class probabilities, {@code [component 2][node 10]}.
     */
    public static final int MV_CLASSES = MV_SIGN + 2;

    /**
     * The offset of the class 0 integer probabilities, {@code [component 2][node 1]}.
     */
    public static final int MV_CLASS0 = MV_CLASSES + 2 * 10;

    /**
     * The offset of the integer bit probabilities, {@code [component 2][bit 10]}.
     */
    public static final int MV_BITS = MV_CLASS0 + 2;

    /**
     * The offset of the class 0 fraction probabilities, {@code [component 2][integer 2][node 3]}.
     */
    public static final int MV_CLASS0_FP = MV_BITS + 2 * 10;

    /**
     * The offset of the fraction probabilities, {@code [component 2][node 3]}.
     */
    public static final int MV_FP = MV_CLASS0_FP + 2 * 2 * 3;

    /**
     * The offset of the class 0 high precision probabilities, {@code [component 2]}.
     */
    public static final int MV_CLASS0_HP = MV_FP + 2 * 3;

    /**
     * The offset of the high precision probabilities, {@code [component 2]}.
     */
    public static final int MV_HP = MV_CLASS0_HP + 2;

    /**
     * The number of probabilities of a context.
     */
    public static final int SIZE = MV_HP + 2;

    /**
     * The default motion vector probabilities, laid out from {@link #MV_JOINTS} to the end of
     * the context ({@code vp9_entropymv.c} {@code default_nmv_context}).
     */
    private static final int[] MV_DEFAULTS = {
            32, 64, 96,
            128, 128,
            224, 144, 192, 168, 192, 176, 192, 198, 198, 245,
            216, 128, 176, 160, 176, 176, 192, 198, 198, 208,
            216, 208,
            136, 140, 148, 160, 176, 192, 224, 234, 234, 240,
            136, 140, 148, 160, 176, 192, 224, 234, 234, 240,
            128, 128, 64, 96, 112, 64,
            128, 128, 64, 96, 112, 64,
            64, 96, 64,
            64, 96, 64,
            160, 160,
            128, 128
    };

    /**
     * The default transform size probabilities, laid out from {@link #TX_8X8} to {@link #SKIP}
     * ({@code vp9_entropymode.c} {@code default_tx_probs}).
     */
    private static final int[] TX_DEFAULTS = {100, 66, 20, 152, 15, 101, 3, 136, 37, 5, 52, 13};

    /**
     * The probabilities.
     */
    public final int[] probs = new int[SIZE];

    /**
     * Whether the context holds probabilities, either the defaults or adapted ones.
     */
    public boolean initialized;

    /**
     * Creates an uninitialized context of zero probabilities.
     */
    public Vp9FrameContext() {
    }

    /**
     * Copies another context into this one.
     *
     * @param other the context to copy
     */
    public void copyFrom(Vp9FrameContext other) {
        System.arraycopy(other.probs, 0, probs, 0, SIZE);
        initialized = other.initialized;
    }

    /**
     * Resets every probability to its default value and marks the context initialized.
     */
    public void setDefaults() {
        var p = probs;
        Arrays.fill(p, 0);
        System.arraycopy(Vp9ProbTables.Y_MODE_PROBS, 0, p, Y_MODE, 4 * 9);
        System.arraycopy(Vp9ProbTables.UV_MODE_PROBS, 0, p, UV_MODE, 10 * 9);
        System.arraycopy(Vp9ProbTables.PARTITION_PROBS, 0, p, PARTITION, 16 * 3);
        var coefSize = 2 * 2 * 6 * 6 * 3;
        System.arraycopy(Vp9ProbTables.COEF_PROBS_4X4, 0, p, COEF, coefSize);
        System.arraycopy(Vp9ProbTables.COEF_PROBS_8X8, 0, p, COEF + coefSize, coefSize);
        System.arraycopy(Vp9ProbTables.COEF_PROBS_16X16, 0, p, COEF + 2 * coefSize, coefSize);
        System.arraycopy(Vp9ProbTables.COEF_PROBS_32X32, 0, p, COEF + 3 * coefSize, coefSize);
        System.arraycopy(Vp9ProbTables.SWITCHABLE_INTERP_PROBS, 0, p, SWITCHABLE_INTERP, 4 * 2);
        System.arraycopy(Vp9ProbTables.INTER_MODE_PROBS, 0, p, INTER_MODE, 7 * 3);
        System.arraycopy(Vp9ProbTables.INTRA_INTER_PROBS, 0, p, INTRA_INTER, 4);
        System.arraycopy(Vp9ProbTables.COMP_INTER_PROBS, 0, p, COMP_INTER, 5);
        System.arraycopy(Vp9ProbTables.SINGLE_REF_PROBS, 0, p, SINGLE_REF, 5 * 2);
        System.arraycopy(Vp9ProbTables.COMP_REF_PROBS, 0, p, COMP_REF, 5);
        System.arraycopy(TX_DEFAULTS, 0, p, TX_8X8, TX_DEFAULTS.length);
        System.arraycopy(Vp9ProbTables.SKIP_PROBS, 0, p, SKIP, 3);
        System.arraycopy(MV_DEFAULTS, 0, p, MV_JOINTS, MV_DEFAULTS.length);
        initialized = true;
    }
}
