/* Ports libvpx (vp9/common/vp9_common_data.c, vp9_filter.c, vp9_mvref_common.h, vp9_reconintra.c). Copyright (c)
   2010, The WebM Project authors; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.video.vp9.internal;

/**
 * Holds the constants and small lookup tables of the VP9 bitstream: block sizes, partition
 * types, transform sizes and modes, prediction modes, reference frames, interpolation filters,
 * and the geometry tables that relate them.
 *
 * <p>Every multi-dimensional table is flattened in row-major order; the javadoc of each table
 * names its dimensions.
 *
 * @implNote This implementation copies the tables verbatim from libvpx
 * ({@code vp9/common/vp9_common_data.c}, {@code vp9_filter.c},
 * {@code vp9_mvref_common.h}, {@code vp9_reconintra.c}).
 */
final class Vp9Constants {
    /**
     * Prevents instantiation.
     */
    private Vp9Constants() {
        throw new AssertionError();
    }

    /**
     * The 4x4 block size.
     */
    public static final int BLOCK_4X4 = 0;

    /**
     * The 4x8 block size.
     */
    public static final int BLOCK_4X8 = 1;

    /**
     * The 8x4 block size.
     */
    public static final int BLOCK_8X4 = 2;

    /**
     * The 8x8 block size, the smallest unit of mode information.
     */
    public static final int BLOCK_8X8 = 3;

    /**
     * The 8x16 block size.
     */
    public static final int BLOCK_8X16 = 4;

    /**
     * The 16x8 block size.
     */
    public static final int BLOCK_16X8 = 5;

    /**
     * The 16x16 block size.
     */
    public static final int BLOCK_16X16 = 6;

    /**
     * The 16x32 block size.
     */
    public static final int BLOCK_16X32 = 7;

    /**
     * The 32x16 block size.
     */
    public static final int BLOCK_32X16 = 8;

    /**
     * The 32x32 block size.
     */
    public static final int BLOCK_32X32 = 9;

    /**
     * The 32x64 block size.
     */
    public static final int BLOCK_32X64 = 10;

    /**
     * The 64x32 block size.
     */
    public static final int BLOCK_64X32 = 11;

    /**
     * The 64x64 block size, the superblock.
     */
    public static final int BLOCK_64X64 = 12;

    /**
     * The marker of a block size that a subsampling cannot represent.
     */
    public static final int BLOCK_INVALID = 13;

    /**
     * The partition that keeps the block whole.
     */
    public static final int PARTITION_NONE = 0;

    /**
     * The partition into a top and a bottom half.
     */
    public static final int PARTITION_HORZ = 1;

    /**
     * The partition into a left and a right half.
     */
    public static final int PARTITION_VERT = 2;

    /**
     * The partition into four quarters.
     */
    public static final int PARTITION_SPLIT = 3;

    /**
     * The 4x4 transform size.
     */
    public static final int TX_4X4 = 0;

    /**
     * The 8x8 transform size.
     */
    public static final int TX_8X8 = 1;

    /**
     * The 16x16 transform size.
     */
    public static final int TX_16X16 = 2;

    /**
     * The 32x32 transform size.
     */
    public static final int TX_32X32 = 3;

    /**
     * The transform mode that lets each block select its transform size.
     */
    public static final int TX_MODE_SELECT = 4;

    /**
     * The transform type with a DCT in both directions.
     */
    public static final int DCT_DCT = 0;

    /**
     * The DC intra prediction mode.
     */
    public static final int DC_PRED = 0;

    /**
     * The vertical intra prediction mode.
     */
    public static final int V_PRED = 1;

    /**
     * The horizontal intra prediction mode.
     */
    public static final int H_PRED = 2;

    /**
     * The 45 degree directional intra prediction mode.
     */
    public static final int D45_PRED = 3;

    /**
     * The 135 degree directional intra prediction mode.
     */
    public static final int D135_PRED = 4;

    /**
     * The 117 degree directional intra prediction mode.
     */
    public static final int D117_PRED = 5;

    /**
     * The 153 degree directional intra prediction mode.
     */
    public static final int D153_PRED = 6;

    /**
     * The 207 degree directional intra prediction mode.
     */
    public static final int D207_PRED = 7;

    /**
     * The 63 degree directional intra prediction mode.
     */
    public static final int D63_PRED = 8;

    /**
     * The true-motion intra prediction mode.
     */
    public static final int TM_PRED = 9;

    /**
     * The intra modes that read the left column ({@code NEED_LEFT}).
     */
    static final int NEED_LEFT = 2;

    /**
     * The intra modes that read the above row ({@code NEED_ABOVE}).
     */
    static final int NEED_ABOVE = 4;

    /**
     * The intra modes that read the above row and the samples right of it ({@code NEED_ABOVERIGHT}).
     */
    static final int NEED_ABOVERIGHT = 8;

    /**
     * The edges each intra mode reads, a combination of {@link #NEED_LEFT}, {@link #NEED_ABOVE}
     * and {@link #NEED_ABOVERIGHT} ({@code extend_modes}).
     */
    static final int[] EXTEND_MODES = {
            NEED_ABOVE | NEED_LEFT, NEED_ABOVE, NEED_LEFT, NEED_ABOVERIGHT, NEED_LEFT | NEED_ABOVE,
            NEED_LEFT | NEED_ABOVE, NEED_LEFT | NEED_ABOVE, NEED_LEFT, NEED_ABOVERIGHT, NEED_LEFT | NEED_ABOVE
    };

    /**
     * The smallest magnitude of each coefficient token, from {@code ZERO_TOKEN} to
     * {@code CATEGORY6_TOKEN}, then 0 for {@code EOB_TOKEN}.
     */
    static final int[] TOKEN_BASE = {0, 1, 2, 3, 4, 5, 7, 11, 19, 35, 67, 0};

    /**
     * The energy class each coefficient token leaves in the token cache, the context of the next
     * coefficients ({@code vp9_pt_energy_class}).
     */
    static final byte[] ENERGY_CLASS = {0, 1, 2, 3, 3, 4, 4, 5, 5, 5, 5, 5};

    /**
     * The index of the first extra bit probability of each coefficient token in the extra bit
     * tables of the decoder and the encoder.
     */
    static final int[] EXTRA_START = {0, 0, 0, 0, 0, 0, 1, 3, 6, 10, 15, 0};

    /**
     * The inter mode using the nearest candidate motion vector.
     */
    public static final int NEARESTMV = 10;

    /**
     * The inter mode using the second candidate motion vector.
     */
    public static final int NEARMV = 11;

    /**
     * The inter mode using the zero motion vector.
     */
    public static final int ZEROMV = 12;

    /**
     * The inter mode coding a new motion vector.
     */
    public static final int NEWMV = 13;

    /**
     * The reference frame of intra blocks.
     */
    public static final int INTRA_FRAME = 0;

    /**
     * The last reference frame.
     */
    public static final int LAST_FRAME = 1;

    /**
     * The golden reference frame.
     */
    public static final int GOLDEN_FRAME = 2;

    /**
     * The alternate reference frame.
     */
    public static final int ALTREF_FRAME = 3;

    /**
     * The absent second reference of single prediction.
     */
    public static final int NO_REF_FRAME = -1;

    /**
     * The regular eight-tap interpolation filter.
     */
    public static final int EIGHTTAP = 0;

    /**
     * The smooth eight-tap interpolation filter.
     */
    public static final int EIGHTTAP_SMOOTH = 1;

    /**
     * The sharp eight-tap interpolation filter.
     */
    public static final int EIGHTTAP_SHARP = 2;

    /**
     * The bilinear interpolation filter.
     */
    public static final int BILINEAR = 3;

    /**
     * The interpolation filter setting that lets each block choose among the three eight-tap
     * filters.
     */
    public static final int SWITCHABLE = 4;

    /**
     * The number of filters a block may switch between, also the filter value of intra blocks
     * in the switchable filter context.
     */
    public static final int SWITCHABLE_FILTERS = 3;

    /**
     * The reference mode where every block uses one reference.
     */
    public static final int SINGLE_REFERENCE = 0;

    /**
     * The reference mode where every block averages two references.
     */
    public static final int COMPOUND_REFERENCE = 1;

    /**
     * The reference mode where each block chooses single or compound prediction.
     */
    public static final int REFERENCE_MODE_SELECT = 2;

    /**
     * The segment feature forcing a reference frame.
     */
    public static final int SEG_LVL_REF_FRAME = 2;

    /**
     * The segment feature forcing skipped blocks with zero motion.
     */
    public static final int SEG_LVL_SKIP = 3;

    /**
     * The scale factor of an unscaled reference, in {@code 1 << 14} units.
     */
    public static final int REF_NO_SCALE = 1 << 14;

    /**
     * The scale factor marking a reference whose size is too different to predict from.
     */
    public static final int REF_INVALID_SCALE = -1;

    /**
     * Maps a block size to the base-2 logarithm of its width in 4x4 units ({@code
     * vp9_common_data.c} {@code b_width_log2_lookup}, 13 entries).
     */
    public static final int[] B_WIDTH_LOG2 = {
            0, 0, 1, 1, 1, 2, 2, 2, 3, 3, 3, 4, 4
    };

    /**
     * Maps a block size to the base-2 logarithm of its height in 4x4 units ({@code
     * vp9_common_data.c} {@code b_height_log2_lookup}, 13 entries).
     */
    public static final int[] B_HEIGHT_LOG2 = {
            0, 1, 0, 1, 2, 1, 2, 3, 2, 3, 4, 3, 4
    };

    /**
     * Maps a block size to its width in 4x4 units ({@code vp9_common_data.c} {@code
     * num_4x4_blocks_wide_lookup}, 13 entries).
     */
    public static final int[] NUM_4X4_WIDE = {
            1, 1, 2, 2, 2, 4, 4, 4, 8, 8, 8, 16, 16
    };

    /**
     * Maps a block size to its height in 4x4 units ({@code vp9_common_data.c} {@code
     * num_4x4_blocks_high_lookup}, 13 entries).
     */
    public static final int[] NUM_4X4_HIGH = {
            1, 2, 1, 2, 4, 2, 4, 8, 4, 8, 16, 8, 16
    };

    /**
     * Maps a block size to its width in 8x8 units ({@code vp9_common_data.c} {@code
     * num_8x8_blocks_wide_lookup}, 13 entries).
     */
    public static final int[] NUM_8X8_WIDE = {
            1, 1, 1, 1, 1, 2, 2, 2, 4, 4, 4, 8, 8
    };

    /**
     * Maps a block size to its height in 8x8 units ({@code vp9_common_data.c} {@code
     * num_8x8_blocks_high_lookup}, 13 entries).
     */
    public static final int[] NUM_8X8_HIGH = {
            1, 1, 1, 1, 2, 1, 2, 4, 2, 4, 8, 4, 8
    };

    /**
     * Maps a block size to the size group of its luma intra mode probabilities ({@code
     * vp9_common_data.c} {@code size_group_lookup}, 13 entries).
     */
    public static final int[] SIZE_GROUP = {
            0, 0, 0, 1, 1, 1, 2, 2, 2, 3, 3, 3, 3
    };

    /**
     * Maps a partition type and a block size to the size of the parts, flattened as {@code
     * [partition 4][block size 13]} ({@code vp9_common_data.c} {@code subsize_lookup}, 52
     * entries).
     */
    public static final int[] SUBSIZE = {
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12,
            13, 13, 13, 2, 13, 13, 5, 13, 13, 8, 13, 13, 11,
            13, 13, 13, 1, 13, 13, 4, 13, 13, 7, 13, 13, 10,
            13, 13, 13, 0, 13, 13, 3, 13, 13, 6, 13, 13, 9
    };

    /**
     * Maps a block size to its largest transform size ({@code vp9_common_data.c} {@code
     * max_txsize_lookup}, 13 entries).
     */
    public static final int[] MAX_TX_SIZE = {
            0, 0, 0, 1, 1, 1, 2, 2, 2, 3, 3, 3, 3
    };

    /**
     * Maps a transform mode to the largest transform size it allows ({@code vp9_common_data.c}
     * {@code tx_mode_to_biggest_tx_size}, 5 entries).
     */
    public static final int[] TX_MODE_TO_BIGGEST = {
            0, 1, 2, 3, 3
    };

    /**
     * Maps a block size and the chroma subsampling to the chroma block size, flattened as {@code
     * [block size 13][subsampling x 2][subsampling y 2]} ({@code vp9_common_data.c} {@code
     * ss_size_lookup}, 52 entries).
     */
    public static final int[] SS_SIZE = {
            0, 13, 13, 13,
            1, 0, 13, 13,
            2, 13, 0, 13,
            3, 2, 1, 0,
            4, 3, 13, 1,
            5, 13, 3, 2,
            6, 5, 4, 3,
            7, 6, 13, 4,
            8, 13, 6, 5,
            9, 8, 7, 6,
            10, 9, 13, 7,
            11, 13, 9, 8,
            12, 11, 10, 9
    };

    /**
     * Maps a block size, its luma transform size and the chroma subsampling to the chroma
     * transform size, flattened as {@code [block size 13][transform size 4][subsampling x
     * 2][subsampling y 2]} ({@code vp9_common_data.c} {@code uv_txsize_lookup}, 208 entries).
     */
    public static final int[] UV_TX_SIZE = {
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0,
            0, 0, 0, 0, 1, 1, 0, 0, 1, 1, 0, 0, 1, 1, 0, 0,
            0, 0, 0, 0, 1, 0, 1, 0, 1, 0, 1, 1, 1, 0, 1, 1,
            0, 0, 0, 0, 1, 1, 1, 1, 2, 1, 1, 1, 2, 1, 1, 1,
            0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 1, 1, 2, 2, 1, 1,
            0, 0, 0, 0, 1, 1, 1, 1, 2, 1, 2, 1, 2, 1, 2, 1,
            0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 2, 2, 2,
            0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 2, 2,
            0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 2, 3, 2,
            0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3
    };

    /**
     * Maps a block size to the partition context values written above and left of it, flattened as
     * {@code [block size 13][above, left]} ({@code vp9_common_data.c} {@code
     * partition_context_lookup}, 26 entries).
     */
    public static final int[] PARTITION_CONTEXT = {
            15, 15,
            15, 14,
            14, 15,
            14, 14,
            14, 12,
            12, 14,
            12, 12,
            12, 8,
            8, 12,
            8, 8,
            8, 0,
            0, 8,
            0, 0
    };

    /**
     * Maps a luma intra mode to the transform type of its residual ({@code vp9_reconintra.c}
     * {@code intra_mode_to_tx_type_lookup}, 10 entries).
     */
    public static final int[] MODE_TO_TX_TYPE = {
            0, 1, 2, 0, 3, 1, 2, 2, 1, 3
    };

    /**
     * Holds the regular eight-tap sub-pixel filters, flattened as {@code [phase 16][tap 8]}
     * ({@code vp9_filter.c} {@code sub_pel_filters_8}, 128 entries).
     */
    private static final short[] REGULAR_KERNELS = {
            0, 0, 0, 128, 0, 0, 0, 0,
            0, 1, -5, 126, 8, -3, 1, 0,
            -1, 3, -10, 122, 18, -6, 2, 0,
            -1, 4, -13, 118, 27, -9, 3, -1,
            -1, 4, -16, 112, 37, -11, 4, -1,
            -1, 5, -18, 105, 48, -14, 4, -1,
            -1, 5, -19, 97, 58, -16, 5, -1,
            -1, 6, -19, 88, 68, -18, 5, -1,
            -1, 6, -19, 78, 78, -19, 6, -1,
            -1, 5, -18, 68, 88, -19, 6, -1,
            -1, 5, -16, 58, 97, -19, 5, -1,
            -1, 4, -14, 48, 105, -18, 5, -1,
            -1, 4, -11, 37, 112, -16, 4, -1,
            -1, 3, -9, 27, 118, -13, 4, -1,
            0, 2, -6, 18, 122, -10, 3, -1,
            0, 1, -3, 8, 126, -5, 1, 0
    };

    /**
     * Holds the smooth eight-tap sub-pixel filters, laid out as {@link #REGULAR_KERNELS} ({@code
     * vp9_filter.c} {@code sub_pel_filters_8lp}, 128 entries).
     */
    private static final short[] SMOOTH_KERNELS = {
            0, 0, 0, 128, 0, 0, 0, 0,
            -3, -1, 32, 64, 38, 1, -3, 0,
            -2, -2, 29, 63, 41, 2, -3, 0,
            -2, -2, 26, 63, 43, 4, -4, 0,
            -2, -3, 24, 62, 46, 5, -4, 0,
            -2, -3, 21, 60, 49, 7, -4, 0,
            -1, -4, 18, 59, 51, 9, -4, 0,
            -1, -4, 16, 57, 53, 12, -4, -1,
            -1, -4, 14, 55, 55, 14, -4, -1,
            -1, -4, 12, 53, 57, 16, -4, -1,
            0, -4, 9, 51, 59, 18, -4, -1,
            0, -4, 7, 49, 60, 21, -3, -2,
            0, -4, 5, 46, 62, 24, -3, -2,
            0, -4, 4, 43, 63, 26, -2, -2,
            0, -3, 2, 41, 63, 29, -2, -2,
            0, -3, 1, 38, 64, 32, -1, -3
    };

    /**
     * Holds the sharp eight-tap sub-pixel filters, laid out as {@link #REGULAR_KERNELS} ({@code
     * vp9_filter.c} {@code sub_pel_filters_8s}, 128 entries).
     */
    private static final short[] SHARP_KERNELS = {
            0, 0, 0, 128, 0, 0, 0, 0,
            -1, 3, -7, 127, 8, -3, 1, 0,
            -2, 5, -13, 125, 17, -6, 3, -1,
            -3, 7, -17, 121, 27, -10, 5, -2,
            -4, 9, -20, 115, 37, -13, 6, -2,
            -4, 10, -23, 108, 48, -16, 8, -3,
            -4, 10, -24, 100, 59, -19, 9, -3,
            -4, 11, -24, 90, 70, -21, 10, -4,
            -4, 11, -23, 80, 80, -23, 11, -4,
            -4, 10, -21, 70, 90, -24, 11, -4,
            -3, 9, -19, 59, 100, -24, 10, -4,
            -3, 8, -16, 48, 108, -23, 10, -4,
            -2, 6, -13, 37, 115, -20, 9, -4,
            -2, 5, -10, 27, 121, -17, 7, -3,
            -1, 3, -6, 17, 125, -13, 5, -2,
            0, 1, -3, 8, 127, -7, 3, -1
    };

    /**
     * Holds the bilinear sub-pixel filters, laid out as {@link #REGULAR_KERNELS} ({@code vp9_filter.c}
     * {@code bilinear_filters}, 128 entries).
     */
    private static final short[] BILINEAR_KERNELS = {
            0, 0, 0, 128, 0, 0, 0, 0,
            0, 0, 0, 120, 8, 0, 0, 0,
            0, 0, 0, 112, 16, 0, 0, 0,
            0, 0, 0, 104, 24, 0, 0, 0,
            0, 0, 0, 96, 32, 0, 0, 0,
            0, 0, 0, 88, 40, 0, 0, 0,
            0, 0, 0, 80, 48, 0, 0, 0,
            0, 0, 0, 72, 56, 0, 0, 0,
            0, 0, 0, 64, 64, 0, 0, 0,
            0, 0, 0, 56, 72, 0, 0, 0,
            0, 0, 0, 48, 80, 0, 0, 0,
            0, 0, 0, 40, 88, 0, 0, 0,
            0, 0, 0, 32, 96, 0, 0, 0,
            0, 0, 0, 24, 104, 0, 0, 0,
            0, 0, 0, 16, 112, 0, 0, 0,
            0, 0, 0, 8, 120, 0, 0, 0
    };

    /**
     * Holds the four sub-pixel filter kernel sets one after another, indexed by
     * {@code (filter type * 16 + phase) * 8 + tap}: regular, smooth, sharp, bilinear.
     */
    public static final short[] FILTER_KERNELS = concat(REGULAR_KERNELS, SMOOTH_KERNELS, SHARP_KERNELS, BILINEAR_KERNELS);

    /**
     * Holds the candidate positions of the motion vector reference search, as
     * {@code (row, column)} offsets in mode information units, flattened as
     * {@code [block size 13][candidate 8][row, column]} ({@code vp9_mvref_common.h}
     * {@code mv_ref_blocks}).
     */
    static final int[] MV_REF_BLOCKS = {
            -1, 0, 0, -1, -1, -1, -2, 0, 0, -2, -2, -1, -1, -2, -2, -2,
            -1, 0, 0, -1, -1, -1, -2, 0, 0, -2, -2, -1, -1, -2, -2, -2,
            -1, 0, 0, -1, -1, -1, -2, 0, 0, -2, -2, -1, -1, -2, -2, -2,
            -1, 0, 0, -1, -1, -1, -2, 0, 0, -2, -2, -1, -1, -2, -2, -2,
            0, -1, -1, 0, 1, -1, -1, -1, 0, -2, -2, 0, -2, -1, -1, -2,
            -1, 0, 0, -1, -1, 1, -1, -1, -2, 0, 0, -2, -1, -2, -2, -1,
            -1, 0, 0, -1, -1, 1, 1, -1, -1, -1, -3, 0, 0, -3, -3, -3,
            0, -1, -1, 0, 2, -1, -1, -1, -1, 1, 0, -3, -3, 0, -3, -3,
            -1, 0, 0, -1, -1, 2, -1, -1, 1, -1, -3, 0, 0, -3, -3, -3,
            -1, 1, 1, -1, -1, 2, 2, -1, -1, -1, -3, 0, 0, -3, -3, -3,
            0, -1, -1, 0, 4, -1, -1, 2, -1, -1, 0, -3, -3, 0, 2, -1,
            -1, 0, 0, -1, -1, 4, 2, -1, -1, -1, -3, 0, 0, -3, -1, 2,
            -1, 3, 3, -1, -1, 4, 4, -1, -1, -1, -1, 0, 0, -1, -1, 6
    };

    /**
     * Maps a prediction mode to its weight in the inter mode context of neighboring blocks:
     * 9 for intra modes, 0 for the nearest and near modes, 3 for the zero mode and 1 for a new
     * motion vector ({@code vp9_mvref_common.h} {@code mode_2_counter}).
     */
    static final int[] MODE_2_COUNTER = {9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 0, 0, 3, 1};

    /**
     * Maps the summed mode weights of the two nearest neighbors to an inter mode context; 9
     * marks impossible sums ({@code vp9_mvref_common.h} {@code counter_to_context}).
     */
    static final int[] COUNTER_TO_CONTEXT = {2, 3, 4, 1, 3, 9, 0, 9, 9, 5, 5, 9, 5, 9, 9, 9, 9, 9, 6};

    /**
     * Maps a sub-8x8 block index and whether the candidate lies above (0) or to the left (1)
     * to the candidate sub-block holding the nearest motion vector, flattened as
     * {@code [block 4][left 2]} ({@code vp9_mvref_common.h} {@code idx_n_column_to_subblock}).
     */
    static final int[] IDX_N_COLUMN_TO_SUBBLOCK = {1, 2, 1, 3, 3, 2, 3, 3};

    /**
     * Returns the concatenation of filter kernel tables.
     *
     * @param tables the tables to join, in order
     * @return a new array holding the tables one after another
     */
    private static short[] concat(short[]... tables) {
        var length = 0;
        for (var table : tables) {
            length += table.length;
        }
        var out = new short[length];
        var position = 0;
        for (var table : tables) {
            System.arraycopy(table, 0, out, position, table.length);
            position += table.length;
        }
        return out;
    }
}
