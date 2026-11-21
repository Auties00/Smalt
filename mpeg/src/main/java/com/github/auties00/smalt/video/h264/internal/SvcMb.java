package com.github.auties00.smalt.video.h264.internal;

/**
 * Encodes the macroblock and sub-macroblock types of the scalable decoding process (ITU-T H.264
 * F.8.1.2.1, Tables 7-11, 7-13, 7-14, 7-17, 7-18 and F-5) as small integers, with their
 * partitioning and prediction modes.
 *
 * <p>Inter types are numbered after the {@code mb_type} of their slice type: P types from
 * {@link #P_BASE} and B types from {@link #B_BASE}. A prediction mode is a mask of the lists a
 * partition predicts from: 1 for list 0, 2 for list 1, 3 for both.
 */
final class SvcMb {
    /**
     * The value of an unspecified type.
     */
    static final int NONE = 0;

    /**
     * The I_NxN type with 4x4 transforms, Intra_4x4 prediction.
     */
    static final int I4X4 = 1;

    /**
     * The I_NxN type with 8x8 transforms, Intra_8x8 prediction.
     */
    static final int I8X8 = 2;

    /**
     * The Intra_16x16 types.
     */
    static final int I16X16 = 3;

    /**
     * The I_PCM type.
     */
    static final int IPCM = 4;

    /**
     * The I_BL type, predicted from the reference layer (Table F-5).
     */
    static final int IBL = 5;

    /**
     * The P_Skip type.
     */
    static final int P_SKIP = 6;

    /**
     * The B_Skip type.
     */
    static final int B_SKIP = 7;

    /**
     * The first P type, P_L0_16x16; P {@code mb_type} {@code n} from 0 to 4 is {@code P_BASE + n}.
     */
    static final int P_BASE = 10;

    /**
     * The P_8x8 type.
     */
    static final int P_8X8 = P_BASE + 3;

    /**
     * The P_8x8ref0 type.
     */
    static final int P_8X8_REF0 = P_BASE + 4;

    /**
     * The first B type, B_Direct_16x16; B {@code mb_type} {@code n} from 0 to 22 is
     * {@code B_BASE + n}.
     */
    static final int B_BASE = 20;

    /**
     * The B_Direct_16x16 type.
     */
    static final int B_DIRECT = B_BASE;

    /**
     * The B_8x8 type.
     */
    static final int B_8X8 = B_BASE + 22;

    /**
     * The first B sub-macroblock type, B_Direct_8x8; B {@code sub_mb_type} {@code n} from 0 to 12
     * is {@code SUB_B_BASE + n}, while P {@code sub_mb_type} {@code n} from 0 to 3 is {@code n}.
     */
    static final int SUB_B_BASE = 4;

    /**
     * The B_Direct_8x8 sub-macroblock type.
     */
    static final int SUB_B_DIRECT = SUB_B_BASE;

    /**
     * The partition shape of a 16x16 partition.
     */
    static final int SHAPE_16X16 = 0;

    /**
     * The partition shape of two 16x8 partitions, or of two 8x4 sub-partitions.
     */
    static final int SHAPE_16X8 = 1;

    /**
     * The partition shape of two 8x16 partitions, or of two 4x8 sub-partitions.
     */
    static final int SHAPE_8X16 = 2;

    /**
     * The partition shape of four 8x8 partitions, or of four 4x4 sub-partitions.
     */
    static final int SHAPE_8X8 = 3;

    /**
     * The prediction modes of the two partitions of B types 4 to 21, two entries per pair of
     * types.
     */
    private static final byte[] B_PAIR_MODES = {1, 1, 2, 2, 1, 2, 2, 1, 1, 3, 2, 3, 3, 1, 3, 2, 3, 3};

    /**
     * The shape of each B sub-macroblock type from 1 to 12.
     */
    private static final byte[] B_SUB_SHAPE = {0, 0, 0, 0, 1, 2, 1, 2, 1, 2, 3, 3, 3};

    /**
     * The prediction mode of each B sub-macroblock type from 1 to 12.
     */
    private static final byte[] B_SUB_MODE = {0, 1, 2, 3, 1, 1, 2, 2, 3, 3, 1, 2, 3};

    /**
     * Prevents instantiation of this utility class.
     */
    private SvcMb() {
        throw new AssertionError();
    }

    /**
     * Returns whether a type is an intra type, including I_BL.
     *
     * @param type the macroblock type code
     * @return {@code true} for I_4x4, I_8x8, I_16x16, I_PCM and I_BL
     */
    static boolean intra(int type) {
        return type >= I4X4 && type <= IBL;
    }

    /**
     * Returns whether a type is an inter type.
     *
     * @param type the macroblock type code
     * @return {@code true} for the P and B types, skipped ones included
     */
    static boolean inter(int type) {
        return type >= P_SKIP;
    }

    /**
     * Returns whether a type is a B type.
     *
     * @param type the macroblock type code
     * @return {@code true} for B_Skip and the B types
     */
    static boolean b(int type) {
        return type == B_SKIP || type >= B_BASE;
    }

    /**
     * Returns whether a type is coded in direct mode as a whole.
     *
     * @param type the macroblock type code
     * @return {@code true} for B_Skip and B_Direct_16x16
     */
    static boolean direct(int type) {
        return type == B_SKIP || type == B_DIRECT;
    }

    /**
     * Returns whether a type splits into four 8x8 partitions with sub-macroblock types.
     *
     * @param type the macroblock type code
     * @return {@code true} for P_8x8, P_8x8ref0 and B_8x8
     */
    static boolean eightByEight(int type) {
        return type == P_8X8 || type == P_8X8_REF0 || type == B_8X8;
    }

    /**
     * Returns the partition shape of a non-direct inter type.
     *
     * @param type the macroblock type code
     * @return one of {@link #SHAPE_16X16}, {@link #SHAPE_16X8}, {@link #SHAPE_8X16} and
     * {@link #SHAPE_8X8}
     */
    static int shape(int type) {
        if (type == P_SKIP) {
            return SHAPE_16X16;
        }
        if (type < B_BASE) {
            return type == P_8X8 || type == P_8X8_REF0 ? SHAPE_8X8 : type - P_BASE;
        }
        var n = type - B_BASE;
        if (n <= 3) {
            return SHAPE_16X16;
        }
        if (n == 22) {
            return SHAPE_8X8;
        }
        return (n & 1) == 0 ? SHAPE_16X8 : SHAPE_8X16;
    }

    /**
     * Returns the number of partitions of a shape.
     *
     * @param shape the partition shape code
     * @return 1, 2 or 4
     */
    static int parts(int shape) {
        return shape == SHAPE_16X16 ? 1 : shape == SHAPE_8X8 ? 4 : 2;
    }

    /**
     * Returns the prediction mode of a partition of a non-8x8, non-direct inter type.
     *
     * @param type the macroblock type code
     * @param part the partition index
     * @return the list mask: 1, 2 or 3
     */
    static int mode(int type, int part) {
        if (type < B_BASE) {
            return 1;
        }
        var n = type - B_BASE;
        if (n <= 3) {
            return n;
        }
        return B_PAIR_MODES[((n - 4) >> 1) * 2 + part];
    }

    /**
     * Returns whether a sub-macroblock type is B_Direct_8x8.
     *
     * @param sub the sub-macroblock type
     * @return {@code true} for B_Direct_8x8
     */
    static boolean subDirect(int sub) {
        return sub == SUB_B_DIRECT;
    }

    /**
     * Returns the shape of a non-direct sub-macroblock type.
     *
     * @param sub the sub-macroblock type
     * @return the shape of its sub-partitions
     */
    static int subShape(int sub) {
        return sub < SUB_B_BASE ? sub : B_SUB_SHAPE[sub - SUB_B_BASE];
    }

    /**
     * Returns the prediction mode of a non-direct sub-macroblock type.
     *
     * @param sub the sub-macroblock type
     * @return the list mask: 1, 2 or 3
     */
    static int subMode(int sub) {
        return sub < SUB_B_BASE ? 1 : B_SUB_MODE[sub - SUB_B_BASE];
    }

    /**
     * Returns the B type of a partitioning and its prediction modes (ITU-T H.264 Table F-7).
     *
     * @param shape the partition shape
     * @param modeA the mode of the first partition, for shapes other than 8x8
     * @param modeB the mode of the second partition, for the 16x8 and 8x16 shapes
     * @return the B type
     */
    static int bType(int shape, int modeA, int modeB) {
        if (shape == SHAPE_8X8) {
            return B_8X8;
        }
        if (shape == SHAPE_16X16) {
            return B_BASE + modeA;
        }
        for (var pair = 0; pair < 9; pair++) {
            if (B_PAIR_MODES[2 * pair] == modeA && B_PAIR_MODES[2 * pair + 1] == modeB) {
                return B_BASE + 4 + 2 * pair + (shape == SHAPE_8X16 ? 1 : 0);
            }
        }
        throw new IllegalArgumentException("no B type predicts from lists " + modeA + " and " + modeB);
    }

    /**
     * Returns the B sub-macroblock type of a sub-partitioning and its prediction mode (ITU-T
     * H.264 Table F-8).
     *
     * @param shape the sub-partition shape
     * @param mode the prediction mode
     * @return the sub-macroblock type
     */
    static int bSub(int shape, int mode) {
        for (var n = 1; n <= 12; n++) {
            if (B_SUB_SHAPE[n] == shape && B_SUB_MODE[n] == mode) {
                return SUB_B_BASE + n;
            }
        }
        throw new IllegalArgumentException("no B sub-macroblock type predicts from lists " + mode);
    }
}
