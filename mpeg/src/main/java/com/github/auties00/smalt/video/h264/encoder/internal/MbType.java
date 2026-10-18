package com.github.auties00.smalt.video.h264.encoder.internal;

/**
 * Enumerates the macroblock types the encoder produces, as small integer constants stored in the
 * per-picture macroblock tables.
 */
final class MbType {
    /**
     * Intra_4x4 prediction ({@code I_NxN} with {@code transform_size_8x8_flag} 0).
     */
    static final int I4x4 = 0;

    /**
     * Intra_8x8 prediction ({@code I_NxN} with {@code transform_size_8x8_flag} 1).
     */
    static final int I8x8 = 1;

    /**
     * Intra_16x16 prediction.
     */
    static final int I16x16 = 2;

    /**
     * Raw samples ({@code I_PCM}).
     */
    static final int I_PCM = 3;

    /**
     * One 16x16 inter partition ({@code P_L0_16x16}).
     */
    static final int P16x16 = 4;

    /**
     * Two 16x8 inter partitions ({@code P_L0_L0_16x8}).
     */
    static final int P16x8 = 5;

    /**
     * Two 8x16 inter partitions ({@code P_L0_L0_8x16}).
     */
    static final int P8x16 = 6;

    /**
     * Four 8x8 sub-macroblocks ({@code P_8x8}).
     */
    static final int P8x8 = 7;

    /**
     * A skipped macroblock ({@code P_Skip}).
     */
    static final int P_SKIP = 8;

    /**
     * Not a macroblock type: the slot of the counts of {@link SliceWriter#typeCounts} that counts
     * the macroblocks coded with the 8x8 transform.
     */
    static final int TRANSFORM_8x8 = 9;

    /**
     * A 16x16 macroblock predicted in spatial direct mode with a residual ({@code B_Direct_16x16}).
     */
    static final int B_DIRECT = 10;

    /**
     * A skipped macroblock of a B slice ({@code B_Skip}): spatial direct prediction, no residual.
     */
    static final int B_SKIP = 11;

    /**
     * One 16x16 partition predicted from list 0 ({@code B_L0_16x16}).
     */
    static final int B_L0 = 12;

    /**
     * One 16x16 partition predicted from list 1 ({@code B_L1_16x16}).
     */
    static final int B_L1 = 13;

    /**
     * One 16x16 partition predicted from both lists ({@code B_Bi_16x16}).
     */
    static final int B_BI = 14;

    /**
     * The number of slots of a per-type table, above every type constant.
     */
    static final int COUNT = 16;

    /**
     * The sub-macroblock type of one 8x8 partition ({@code P_L0_8x8}).
     */
    static final int SUB_8x8 = 0;

    /**
     * The sub-macroblock type of two 8x4 partitions ({@code P_L0_8x4}).
     */
    static final int SUB_8x4 = 1;

    /**
     * The sub-macroblock type of two 4x8 partitions ({@code P_L0_4x8}).
     */
    static final int SUB_4x8 = 2;

    /**
     * The sub-macroblock type of four 4x4 partitions ({@code P_L0_4x4}).
     */
    static final int SUB_4x4 = 3;

    private MbType() {
        throw new AssertionError();
    }

    /**
     * Returns whether a macroblock type is intra.
     *
     * @param type the macroblock type
     * @return whether the macroblock uses intra prediction or raw samples
     */
    static boolean isIntra(int type) {
        return type <= I_PCM;
    }

    /**
     * Returns whether a macroblock type is a skipped macroblock of a P or B slice.
     *
     * @param type the macroblock type
     * @return whether the macroblock is {@link #P_SKIP} or {@link #B_SKIP}
     */
    static boolean isSkip(int type) {
        return type == P_SKIP || type == B_SKIP;
    }

    /**
     * Returns whether a macroblock type is one of the B slice inter types.
     *
     * @param type the macroblock type
     * @return whether the macroblock is a direct, skipped or 16x16 macroblock of a B slice
     */
    static boolean isB(int type) {
        return type >= B_DIRECT && type <= B_BI;
    }

    /**
     * Returns the number of motion vectors of a sub-macroblock type.
     *
     * @param subType the sub-macroblock type
     * @return 1, 2 or 4
     */
    static int subPartitions(int subType) {
        return switch (subType) {
            case SUB_8x8 -> 1;
            case SUB_8x4, SUB_4x8 -> 2;
            default -> 4;
        };
    }
}
