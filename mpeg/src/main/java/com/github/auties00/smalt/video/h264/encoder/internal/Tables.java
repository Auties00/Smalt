/* Ports JM, the ITU-T / ISO/IEC H.264 reference software (app/lencod/q_matrix.c: the forward quantization
   multipliers). Copyright ITU-T and ISO/IEC; royalty-free license with notice. Modified for Smalt. */
package com.github.auties00.smalt.video.h264.encoder.internal;

import com.github.auties00.smalt.video.h264.internal.H264Tables;

/**
 * Holds the constant tables of the H.264 syntax and of the encoder's quantizer.
 *
 * <p>Block indices follow the specification: a macroblock's sixteen 4x4 luma blocks are numbered
 * by {@code luma4x4BlkIdx} in the nested z-order of clause 6.4.3, while per-macroblock neighbor
 * arrays are indexed in raster order ({@code y4 * 4 + x4}).
 */
final class Tables {
    /**
     * The 4x4 dequantization factors {@code normAdjust4x4} per {@code qP % 6}, in raster order
     * (clause 8.5.9, equation 8-315); the flat scaling matrix multiplies them by 16 and the
     * decoder divides by 16 again, so they are used as is.
     */
    static final int[][] DEQUANT4 = new int[6][16];

    /**
     * The 8x8 dequantization factors {@code normAdjust8x8} per {@code qP % 6}, in raster order
     * (clause 8.5.9, equation 8-318), multiplied by the flat weight 16.
     */
    static final int[][] DEQUANT8 = new int[6][64];

    /**
     * The 4x4 forward quantization multipliers per {@code qP % 6}, in raster order, for a shift
     * of {@code 15 + qP / 6}.
     *
     * @implNote This implementation uses the multipliers of the ITU-T JM reference encoder,
     * which are {@code round(2^15 * ...)} inverses of {@link #DEQUANT4} under the transform norms.
     */
    static final int[][] QUANT4 = new int[6][16];

    /**
     * The 8x8 forward quantization multipliers per {@code qP % 6}, in raster order, for a shift
     * of {@code 16 + qP / 6}.
     *
     * @implNote This implementation uses the multipliers of the ITU-T JM reference encoder.
     */
    static final int[][] QUANT8 = new int[6][64];

    /**
     * The 4x4 forward multipliers per {@code qP % 6} for the position classes even-even, odd-odd
     * and mixed.
     */
    private static final int[][] M4 = {
            {13107, 5243, 8066}, {11916, 4660, 7490}, {10082, 4194, 6554},
            {9362, 3647, 5825}, {8192, 3355, 5243}, {7282, 2893, 4559}
    };

    /**
     * The 8x8 forward multipliers per {@code qP % 6} for the six position classes of equation
     * 8-318.
     */
    private static final int[][] M8 = {
            {13107, 11428, 20972, 12222, 16777, 15481}, {11916, 10826, 19174, 11058, 14980, 14290},
            {10082, 8943, 15978, 9675, 12710, 11985}, {9362, 8228, 14913, 8931, 11984, 11259},
            {8192, 7346, 13159, 7740, 10486, 9777}, {7282, 6428, 11570, 6830, 9118, 8640}
    };

    /**
     * Maps an intra macroblock's {@code coded_block_pattern} to its code number, the inverse of
     * {@link H264Tables#CBP_INTRA}.
     */
    static final int[] CBP_INTRA_TO_CODE = new int[48];

    /**
     * Maps an inter macroblock's {@code coded_block_pattern} to its code number, the inverse of
     * {@link H264Tables#CBP_INTER}.
     */
    static final int[] CBP_INTER_TO_CODE = new int[48];

    static {
        for (var m = 0; m < 6; m++) {
            for (var i = 0; i < 4; i++) {
                for (var j = 0; j < 4; j++) {
                    var positionClass = (i & 1) == 0 && (j & 1) == 0 ? 0 : (i & 1) == 1 && (j & 1) == 1 ? 1 : 2;
                    DEQUANT4[m][i * 4 + j] = H264Tables.NORM_ADJUST_4X4[m * 16 + i * 4 + j];
                    QUANT4[m][i * 4 + j] = M4[m][positionClass];
                }
            }
            for (var i = 0; i < 8; i++) {
                for (var j = 0; j < 8; j++) {
                    int positionClass;
                    if (i % 4 == 0 && j % 4 == 0) {
                        positionClass = 0;
                    } else if (i % 2 == 1 && j % 2 == 1) {
                        positionClass = 1;
                    } else if (i % 4 == 2 && j % 4 == 2) {
                        positionClass = 2;
                    } else if ((i % 4 == 0 && j % 2 == 1) || (i % 2 == 1 && j % 4 == 0)) {
                        positionClass = 3;
                    } else if ((i % 4 == 0 && j % 4 == 2) || (i % 4 == 2 && j % 4 == 0)) {
                        positionClass = 4;
                    } else {
                        positionClass = 5;
                    }
                    DEQUANT8[m][i * 8 + j] = H264Tables.NORM_ADJUST_8X8[m * 64 + i * 8 + j] * 16;
                    QUANT8[m][i * 8 + j] = M8[m][positionClass];
                }
            }
        }
        for (var code = 0; code < 48; code++) {
            CBP_INTRA_TO_CODE[H264Tables.CBP_INTRA[code]] = code;
            CBP_INTER_TO_CODE[H264Tables.CBP_INTER[code]] = code;
        }
    }

    private Tables() {
        throw new AssertionError();
    }

    /**
     * Returns the chroma quantization parameter for a luma quantization parameter.
     *
     * @param qp the luma quantization parameter, from 0 to 51
     * @param offset the {@code chroma_qp_index_offset}, from -12 to 12
     * @return {@code QPc}
     */
    static int chromaQp(int qp, int offset) {
        return H264Tables.CHROMA_QP[Math.clamp(qp + offset, 0, 51)];
    }
}
