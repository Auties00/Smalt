package com.github.auties00.smalt.video.h264.internal;

/**
 * Holds the constant tables of ITU-T H.264 shared by the decoder and the encoder: coefficient
 * scans, default scaling matrices, dequantization factors, the chroma quantization mapping, the
 * deblocking thresholds and the coded block pattern mappings.
 *
 * <p>Every array is shared and must be treated as read-only.
 */
public final class H264Tables {
    /**
     * The 4x4 zig-zag (frame) scan: entry {@code i} is the raster index, {@code y * 4 + x}, of
     * the {@code i}-th coefficient in scan order (ITU-T H.264 Table 8-13).
     */
    public static final byte[] ZIGZAG_4X4 = {
            0, 1, 4, 8, 5, 2, 3, 6, 9, 12, 13, 10, 7, 11, 14, 15
    };

    /**
     * The scan of a 4:2:0 chroma DC block, which is raster order (ITU-T H.264 8.5.11.1).
     */
    public static final byte[] CHROMA_DC_SCAN = {0, 1, 2, 3};

    /**
     * The 8x8 zig-zag (frame) scan: entry {@code i} is the raster index, {@code y * 8 + x}, of
     * the {@code i}-th coefficient in scan order (ITU-T H.264 Table 8-14).
     */
    public static final byte[] ZIGZAG_8X8 = {
            0, 1, 8, 16, 9, 2, 3, 10, 17, 24, 32, 25, 18, 11, 4, 5,
            12, 19, 26, 33, 40, 48, 41, 34, 27, 20, 13, 6, 7, 14, 21, 28,
            35, 42, 49, 56, 57, 50, 43, 36, 29, 22, 15, 23, 30, 37, 44, 51,
            58, 59, 52, 45, 38, 31, 39, 46, 53, 60, 61, 54, 47, 55, 62, 63
    };

    /**
     * The default intra 4x4 scaling list {@code Default_4x4_Intra}, in zig-zag order (ITU-T
     * H.264 Table 7-3).
     */
    public static final byte[] DEFAULT_4X4_INTRA = {
            6, 13, 13, 20, 20, 20, 28, 28, 28, 28, 32, 32, 32, 37, 37, 42
    };

    /**
     * The default inter 4x4 scaling list {@code Default_4x4_Inter}, in zig-zag order (ITU-T
     * H.264 Table 7-3).
     */
    public static final byte[] DEFAULT_4X4_INTER = {
            10, 14, 14, 20, 20, 20, 24, 24, 24, 24, 27, 27, 27, 30, 30, 34
    };

    /**
     * The default intra 8x8 scaling list {@code Default_8x8_Intra}, in zig-zag order (ITU-T
     * H.264 Table 7-4).
     */
    public static final byte[] DEFAULT_8X8_INTRA = {
            6, 10, 10, 13, 11, 13, 16, 16, 16, 16, 18, 18, 18, 18, 18, 23,
            23, 23, 23, 23, 23, 25, 25, 25, 25, 25, 25, 25, 27, 27, 27, 27,
            27, 27, 27, 27, 29, 29, 29, 29, 29, 29, 29, 31, 31, 31, 31, 31,
            31, 33, 33, 33, 33, 33, 36, 36, 36, 36, 38, 38, 38, 40, 40, 42
    };

    /**
     * The default inter 8x8 scaling list {@code Default_8x8_Inter}, in zig-zag order (ITU-T
     * H.264 Table 7-4).
     */
    public static final byte[] DEFAULT_8X8_INTER = {
            9, 13, 13, 15, 13, 15, 17, 17, 17, 17, 19, 19, 19, 19, 19, 21,
            21, 21, 21, 21, 21, 22, 22, 22, 22, 22, 22, 22, 24, 24, 24, 24,
            24, 24, 24, 24, 25, 25, 25, 25, 25, 25, 25, 27, 27, 27, 27, 27,
            27, 28, 28, 28, 28, 28, 30, 30, 30, 30, 32, 32, 32, 33, 33, 35
    };

    /**
     * The 4x4 normalization factors {@code v[m][k]} of ITU-T H.264 equation 8-315, indexed by
     * {@code qP % 6} and the position class.
     */
    private static final int[][] NORM_ADJUST_4X4_V = {
            {10, 16, 13}, {11, 18, 14}, {13, 20, 16}, {14, 23, 18}, {16, 25, 20}, {18, 29, 23}
    };

    /**
     * The 8x8 normalization factors {@code v[m][k]} of ITU-T H.264 equation 8-318, indexed by
     * {@code qP % 6} and the position class.
     */
    private static final int[][] NORM_ADJUST_8X8_V = {
            {20, 18, 32, 19, 25, 24}, {22, 19, 35, 21, 28, 26}, {26, 23, 42, 24, 33, 31},
            {28, 25, 45, 26, 35, 33}, {32, 28, 51, 30, 40, 38}, {36, 32, 58, 34, 46, 43}
    };

    /**
     * The 4x4 normalization factors {@code normAdjust4x4(m, i, j)} per raster position, indexed
     * {@code [m * 16 + y * 4 + x]}.
     */
    public static final int[] NORM_ADJUST_4X4 = buildNormAdjust4x4();

    /**
     * The 8x8 normalization factors {@code normAdjust8x8(m, i, j)} per raster position, indexed
     * {@code [m * 64 + y * 8 + x]}.
     */
    public static final int[] NORM_ADJUST_8X8 = buildNormAdjust8x8();

    /**
     * The chroma quantization parameter {@code QPc} for each {@code qPI} from 0 to 51 (ITU-T
     * H.264 Table 8-15).
     */
    public static final byte[] CHROMA_QP = {
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29,
            29, 30, 31, 32, 32, 33, 34, 34, 35, 35, 36, 36, 37, 37, 37, 38, 38, 38, 39, 39, 39, 39
    };

    /**
     * The deblocking threshold {@code alpha'} for each {@code indexA} (ITU-T H.264 Table 8-16).
     */
    public static final byte[] ALPHA = {
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 4, 4, 5, 6, 7, 8, 9, 10, 12, 13, 15, 17, 20, 22, 25, 28,
            32, 36, 40, 45, 50, 56, 63, 71, 80, 90, 101, 113, 127, (byte) 144, (byte) 162, (byte) 182, (byte) 203,
            (byte) 226, (byte) 255, (byte) 255
    };

    /**
     * The deblocking threshold {@code beta'} for each {@code indexB} (ITU-T H.264 Table 8-16).
     */
    public static final byte[] BETA = {
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 6, 6, 7, 7, 8, 8,
            9, 9, 10, 10, 11, 11, 12, 12, 13, 13, 14, 14, 15, 15, 16, 16, 17, 17, 18, 18
    };

    /**
     * The deblocking clipping value {@code tC0'} indexed {@code [indexA * 4 + bS]} for boundary
     * strengths 1 to 3; column 0 is unused (ITU-T H.264 Table 8-17).
     */
    public static final byte[] TC0 = {
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 1, 0, 1, 1, 1,
            0, 1, 1, 1, 0, 1, 1, 1, 0, 1, 1, 1, 0, 1, 1, 2, 0, 1, 1, 2, 0, 1, 1, 2, 0, 1, 1, 2, 0, 1, 2, 3,
            0, 1, 2, 3, 0, 2, 2, 3, 0, 2, 2, 4, 0, 2, 3, 4, 0, 2, 3, 4, 0, 3, 3, 5, 0, 3, 4, 6, 0, 3, 4, 6,
            0, 4, 5, 7, 0, 4, 5, 8, 0, 4, 6, 9, 0, 5, 7, 10, 0, 6, 8, 11, 0, 6, 8, 13, 0, 7, 10, 14, 0, 8, 11, 16,
            0, 9, 12, 18, 0, 10, 13, 20, 0, 11, 15, 23, 0, 13, 17, 25
    };

    /**
     * The coded block pattern of each {@code me(v)} code number for Intra_4x4 and Intra_8x8
     * macroblocks when {@code ChromaArrayType} is 1 or 2 (ITU-T H.264 Table 9-4).
     */
    public static final byte[] CBP_INTRA = {
            47, 31, 15, 0, 23, 27, 29, 30, 7, 11, 13, 14, 39, 43, 45, 46,
            16, 3, 5, 10, 12, 19, 21, 26, 28, 35, 37, 42, 44, 1, 2, 4,
            8, 17, 18, 20, 24, 6, 9, 22, 25, 32, 33, 34, 36, 40, 38, 41
    };

    /**
     * The coded block pattern of each {@code me(v)} code number for inter macroblocks when
     * {@code ChromaArrayType} is 1 or 2 (ITU-T H.264 Table 9-4).
     */
    public static final byte[] CBP_INTER = {
            0, 16, 1, 2, 4, 8, 32, 3, 5, 10, 12, 15, 47, 7, 11, 13,
            14, 6, 9, 31, 35, 37, 42, 44, 33, 34, 36, 40, 39, 43, 45, 46,
            17, 18, 20, 24, 19, 21, 26, 28, 23, 27, 29, 30, 22, 25, 38, 41
    };

    /**
     * The coded block pattern of each {@code me(v)} code number for Intra_4x4 and Intra_8x8
     * macroblocks when {@code ChromaArrayType} is 0 or 3 (ITU-T H.264 Table 9-4).
     */
    public static final byte[] CBP_INTRA_MONO = {
            15, 0, 7, 11, 13, 14, 3, 5, 10, 12, 1, 2, 4, 8, 6, 9
    };

    /**
     * The coded block pattern of each {@code me(v)} code number for inter macroblocks when
     * {@code ChromaArrayType} is 0 or 3 (ITU-T H.264 Table 9-4).
     */
    public static final byte[] CBP_INTER_MONO = {
            0, 1, 2, 4, 8, 3, 5, 10, 12, 15, 7, 11, 13, 14, 6, 9
    };

    /**
     * The horizontal position, in 4x4 block units, of each luma 4x4 block index (the
     * {@code luma4x4BlkIdx} order of ITU-T H.264 6.4.3).
     */
    public static final byte[] BLOCK_X = {0, 1, 0, 1, 2, 3, 2, 3, 0, 1, 0, 1, 2, 3, 2, 3};

    /**
     * The vertical position, in 4x4 block units, of each luma 4x4 block index.
     */
    public static final byte[] BLOCK_Y = {0, 0, 1, 1, 0, 0, 1, 1, 2, 2, 3, 3, 2, 2, 3, 3};

    /**
     * The raster index, {@code y * 4 + x}, of each luma 4x4 block index.
     */
    public static final byte[] BLOCK_RASTER = {0, 1, 4, 5, 2, 3, 6, 7, 8, 9, 12, 13, 10, 11, 14, 15};

    private H264Tables() {
        throw new AssertionError();
    }

    /**
     * Expands the 4x4 normalization classes to one factor per raster position.
     *
     * @return the factors indexed {@code [m * 16 + y * 4 + x]}
     */
    private static int[] buildNormAdjust4x4() {
        var result = new int[6 * 16];
        for (var m = 0; m < 6; m++) {
            for (var y = 0; y < 4; y++) {
                for (var x = 0; x < 4; x++) {
                    int k;
                    if ((x & 1) == 0 && (y & 1) == 0) {
                        k = 0;
                    } else if ((x & 1) == 1 && (y & 1) == 1) {
                        k = 1;
                    } else {
                        k = 2;
                    }
                    result[m * 16 + y * 4 + x] = NORM_ADJUST_4X4_V[m][k];
                }
            }
        }
        return result;
    }

    /**
     * Expands the 8x8 normalization classes to one factor per raster position.
     *
     * @return the factors indexed {@code [m * 64 + y * 8 + x]}
     */
    private static int[] buildNormAdjust8x8() {
        var result = new int[6 * 64];
        for (var m = 0; m < 6; m++) {
            for (var i = 0; i < 8; i++) {
                for (var j = 0; j < 8; j++) {
                    int k;
                    if ((i & 3) == 0 && (j & 3) == 0) {
                        k = 0;
                    } else if ((i & 1) == 1 && (j & 1) == 1) {
                        k = 1;
                    } else if ((i & 3) == 2 && (j & 3) == 2) {
                        k = 2;
                    } else if (((i & 3) == 0 && (j & 1) == 1) || ((i & 1) == 1 && (j & 3) == 0)) {
                        k = 3;
                    } else if (((i & 3) == 0 && (j & 3) == 2) || ((i & 3) == 2 && (j & 3) == 0)) {
                        k = 4;
                    } else {
                        k = 5;
                    }
                    result[m * 64 + i * 8 + j] = NORM_ADJUST_8X8_V[m][k];
                }
            }
        }
        return result;
    }
}
