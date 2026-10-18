package com.github.auties00.smalt.video.av1.internal;


/**
 * Holds the small constant tables of AV1 block decoding.
 *
 * <p>Multi-dimensional tables are flattened in row-major order; each documents its dimensions,
 * indexed with the same enumerations as dav1d ({@code src/levels.h}).
 *
 * @implNote This implementation copies the tables of dav1d's {@code src/tables.c}, which transcribe
 * the corresponding tables and derivations of the AV1 specification.
 */
public final class Tables {
    /**
     * Holds the above and left partition context bits of each partition, as
     * {@code [above/left 2][BlockLevel 5][BlockPartition 10]}.
     */
    public static final int[] AL_PART_CTX = {
            0, 0, 16, 255, 0, 16, 16, 16, 255, 255, 16, 16, 24, 255, 16, 24, 24, 24, 16, 28,
            24, 24, 28, 255, 24, 28, 28, 28, 24, 30, 28, 28, 30, 255, 28, 30, 30, 30, 28, 31,
            30, 30, 31, 31, 255, 255, 255, 255, 255, 255, 0, 16, 0, 255, 16, 16, 0, 16, 255, 255,
            16, 24, 16, 255, 24, 24, 16, 24, 28, 16, 24, 28, 24, 255, 28, 28, 24, 28, 30, 24,
            28, 30, 28, 255, 30, 30, 28, 30, 31, 28, 30, 31, 30, 31, 255, 255, 255, 255, 255, 255
    };

    /**
     * Holds the block sizes of the first and second parts of each partition, as
     * {@code [BlockLevel 5][BlockPartition 10][2]}.
     */
    public static final int[] BLOCK_SIZES = {
            0, 0, 1, 0, 2, 0, 0, 0, 3, 1, 1, 3, 3, 2, 2, 3, 0, 0, 0, 0,
            3, 0, 4, 0, 6, 0, 0, 0, 7, 4, 4, 7, 7, 6, 6, 7, 5, 0, 10, 0,
            7, 0, 8, 0, 11, 0, 0, 0, 12, 8, 8, 12, 12, 11, 11, 12, 9, 0, 15, 0,
            12, 0, 13, 0, 16, 0, 0, 0, 17, 13, 13, 17, 17, 16, 16, 17, 14, 0, 19, 0,
            17, 0, 18, 0, 20, 0, 21, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0
    };

    /**
     * Holds the width and height in 4-sample units and their base-2 logarithms of each block size,
     * as {@code [BlockSize 22][4]}.
     */
    public static final int[] BLOCK_DIMENSIONS = {
            32, 32, 5, 5, 32, 16, 5, 4, 16, 32, 4, 5, 16, 16, 4, 4, 16, 8, 4, 3,
            16, 4, 4, 2, 8, 16, 3, 4, 8, 8, 3, 3, 8, 4, 3, 2, 8, 2, 3, 1,
            4, 16, 2, 4, 4, 8, 2, 3, 4, 4, 2, 2, 4, 2, 2, 1, 4, 1, 2, 0,
            2, 8, 1, 3, 2, 4, 1, 2, 2, 2, 1, 1, 2, 1, 1, 0, 1, 4, 0, 2,
            1, 2, 0, 1, 1, 1, 0, 0
    };

    /**
     * Holds the largest transform size of each block size for luma and for 4:2:0, 4:2:2 and 4:4:4
     * chroma, as {@code [BlockSize 22][4]}.
     */
    public static final int[] MAX_TXFM_SIZE_FOR_BS = {
            4, 3, 3, 3, 4, 3, 3, 3, 4, 3, 0, 3, 4, 3, 3, 3, 12, 10, 3, 3,
            18, 16, 10, 10, 11, 9, 0, 3, 3, 2, 9, 3, 10, 8, 2, 10, 16, 14, 8, 16,
            17, 15, 0, 9, 9, 7, 0, 9, 2, 1, 7, 2, 8, 6, 1, 8, 14, 6, 6, 14,
            15, 13, 0, 15, 7, 5, 0, 7, 1, 0, 5, 1, 6, 0, 0, 6, 13, 5, 0, 13,
            5, 0, 0, 5, 0, 0, 0, 0
    };

    /**
     * Holds the transform type implied by each chroma intra mode.
     */
    public static final int[] TXTP_FROM_UVMODE = {
            0, 1, 2, 0, 3, 1, 2, 2, 1, 3, 1, 2, 3, 0
    };

    /**
     * Holds the single-reference modes of the two references of each compound inter mode, as
     * {@code [CompInterPredMode 8][2]}.
     */
    public static final int[] COMP_INTER_PRED_MODES = {
            0, 0, 1, 1, 0, 3, 3, 0, 1, 3, 3, 1, 2, 2, 3, 3
    };

    /**
     * Holds the number of partition types minus one at each block level.
     */
    public static final int[] PARTITION_TYPE_COUNT = {
            7, 9, 9, 9, 3
    };

    /**
     * Holds the transform types of the transform sets, in symbol order.
     */
    public static final int[] TX_TYPES_PER_SET = {
            9, 0, 3, 1, 2, 9, 0, 10, 11, 3, 1, 2, 9, 10, 11, 0, 1, 2, 4, 5,
            3, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 0, 1, 2, 4, 5, 3, 6, 7, 8
    };

    /**
     * Holds the luma intra mode equivalent to each filter intra mode.
     */
    public static final int[] FILTER_MODE_TO_Y_MODE = {
            0, 1, 2, 6, 0
    };

    /**
     * Holds the luma mode CDF context of each block size.
     */
    public static final int[] YMODE_SIZE_CONTEXT = {
            3, 3, 3, 3, 3, 2, 3, 3, 2, 1, 2, 2, 2, 1, 0, 1, 1, 1, 0, 0,
            0, 0
    };

    /**
     * Holds the base coefficient context offsets, as {@code [2-D square/wide/tall 3][y 5][x 5]}.
     */
    public static final int[] LO_CTX_OFFSETS = {
            0, 1, 6, 6, 21, 1, 6, 6, 21, 21, 6, 6, 21, 21, 21, 6, 21, 21, 21, 21,
            21, 21, 21, 21, 21, 0, 16, 6, 6, 21, 16, 16, 6, 21, 21, 16, 16, 21, 21, 21,
            16, 16, 21, 21, 21, 16, 16, 21, 21, 21, 0, 11, 11, 11, 11, 11, 11, 11, 11, 11,
            6, 6, 21, 21, 21, 6, 21, 21, 21, 21, 21, 21, 21, 21, 21
    };

    /**
     * Holds the all-zero coefficient context of each pair of above and left magnitudes, as {@code [5][5]}.
     */
    public static final int[] SKIP_CTX = {
            1, 2, 2, 2, 3, 2, 4, 4, 4, 5, 2, 4, 4, 4, 5, 2, 4, 4, 4, 5,
            3, 5, 5, 5, 6
    };

    /**
     * Holds the transform class (2-D, horizontal or vertical) of each transform type.
     */
    public static final int[] TX_TYPE_CLASS = {
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2, 1, 2, 1, 2, 1, 0
    };

    /**
     * Holds the 2-D filter of each pair of horizontal and vertical interpolation filters, as {@code [h 4][v 4]}.
     */
    public static final int[] FILTER_2D = {
            0, 1, 2, 0, 6, 7, 8, 0, 3, 4, 5, 0, 0, 0, 0, 9
    };


    /**
     * Holds the key frame luma mode CDF context of each intra mode.
     */
    public static final int[] INTRA_MODE_CONTEXT = {
            0, 1, 2, 3, 4, 4, 4, 4, 3, 0, 1, 2, 0
    };

    /**
     * Holds the wedge CDF context of each block size.
     */
    public static final int[] WEDGE_CTX_LUT = {
            0, 0, 0, 0, 0, 0, 0, 6, 5, 8, 0, 4, 3, 2, 0, 7, 1, 0, 0, 0,
            0, 0
    };

    /**
     * Holds the self-guided filter strengths of each parameter set, as {@code [16][2]} (AV1
     * specification section 7.17.3, {@code Sgr_Params}).
     */
    public static final int[] SGR_PARAMS = {
            140, 3236, 112, 2158, 93, 1618, 80, 1438, 70, 1295, 58, 1177, 47, 1079, 37, 996, 30, 925, 25, 863,
            0, 2589, 0, 1618, 0, 1177, 0, 925, 56, 0, 22, 0
    };

    /**
     * Holds the width in 4-sample units of each transform size ({@code RectTxfmSize}).
     */
    public static final int[] TX_W = {
            1, 2, 4, 8, 16, 1, 2, 2, 4, 4, 8, 8, 16, 1, 4, 2, 8, 4, 16
    };

    /**
     * Holds the height in 4-sample units of each transform size ({@code RectTxfmSize}).
     */
    public static final int[] TX_H = {
            1, 2, 4, 8, 16, 2, 1, 4, 2, 8, 4, 16, 8, 4, 1, 8, 2, 16, 4
    };

    /**
     * Holds the base-2 logarithm of the width in 4-sample units of each transform size ({@code RectTxfmSize}).
     */
    public static final int[] TX_LW = {
            0, 1, 2, 3, 4, 0, 1, 1, 2, 2, 3, 3, 4, 0, 2, 1, 3, 2, 4
    };

    /**
     * Holds the base-2 logarithm of the height in 4-sample units of each transform size ({@code RectTxfmSize}).
     */
    public static final int[] TX_LH = {
            0, 1, 2, 3, 4, 1, 0, 2, 1, 3, 2, 4, 3, 2, 0, 3, 1, 4, 2
    };

    /**
     * Holds the smaller of the two logarithms of each transform size ({@code RectTxfmSize}).
     */
    public static final int[] TX_MIN = {
            0, 1, 2, 3, 4, 0, 0, 1, 1, 2, 2, 3, 3, 0, 0, 1, 1, 2, 2
    };

    /**
     * Holds the larger of the two logarithms of each transform size ({@code RectTxfmSize}).
     */
    public static final int[] TX_MAX = {
            0, 1, 2, 3, 4, 1, 1, 2, 2, 3, 3, 4, 4, 2, 2, 3, 3, 4, 4
    };

    /**
     * Holds the next smaller transform size of each transform size ({@code RectTxfmSize}).
     */
    public static final int[] TX_SUB = {
            0, 0, 1, 2, 3, 0, 0, 1, 1, 2, 2, 3, 3, 5, 6, 7, 8, 9, 10
    };

    /**
     * Holds the context class of the size of each transform size ({@code RectTxfmSize}).
     */
    public static final int[] TX_CTX = {
            0, 1, 2, 3, 4, 1, 1, 2, 2, 3, 3, 4, 4, 1, 1, 2, 2, 3, 3
    };

    /**
     * The block sizes that allow chroma-from-luma in a lossy segment, as a bit mask over
     * {@code BlockSize} numbers: the blocks of at most 32x32 samples.
     */
    private static final int CFL_ALLOWED_MASK = (1 << 7) | (1 << 8) | (1 << 9) | (1 << 11) | (1 << 12) | (1 << 13)
            | (1 << 14) | (1 << 15) | (1 << 16) | (1 << 17) | (1 << 18) | (1 << 19) | (1 << 20) | (1 << 21);

    /**
     * Returns whether a block may predict chroma from luma ({@code is_cfl_allowed}): in a lossless
     * segment when its chroma block is 4x4, otherwise when it is at most 32x32 samples.
     *
     * @param bs the block size, a {@code BlockSize} number
     * @param lossless whether the block's segment is lossless
     * @param cbw4 the chroma block width, in 4-sample units
     * @param cbh4 the chroma block height, in 4-sample units
     * @return {@code true} when chroma-from-luma is allowed
     */
    public static boolean cflAllowed(int bs, boolean lossless, int cbw4, int cbh4) {
        return lossless ? cbw4 == 1 && cbh4 == 1 : (CFL_ALLOWED_MASK & (1 << bs)) != 0;
    }

    /**
     * Prevents instantiation.
     */
    private Tables() {
        throw new AssertionError();
    }
}
