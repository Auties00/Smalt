package com.github.auties00.smalt.video.vc1.internal;

/**
 * Computes the inverse transforms of SMPTE ST 421 Annex A: 8x8, 8x4, 4x8 and 4x4 blocks, exactly as Figure 159
 * defines them.
 *
 * <p>Blocks are held in an {@code int[64]} with rows 8 entries apart; an 8x4 block (8 wide, 4 tall) or a 4x8 block
 * occupies the top-left part of its own array. Arguments are not checked; values outside the documented ranges give
 * unspecified results.
 */
final class Transform {
    /**
     * Prevents instantiation.
     */
    private Transform() {
    }

    /**
     * The 8-point transform matrix of Annex A.
     */
    private static final int[][] T8 = {
            {12, 12, 12, 12, 12, 12, 12, 12},
            {16, 15, 9, 4, -4, -9, -15, -16},
            {16, 6, -6, -16, -16, -6, 6, 16},
            {15, -4, -16, -9, 9, 16, 4, -15},
            {12, -12, -12, 12, 12, -12, -12, 12},
            {9, -16, 4, 15, -15, -4, 16, -9},
            {6, -16, 16, -6, -6, 16, -16, 6},
            {4, -9, 15, -16, 16, -15, 9, -4}
    };

    /**
     * The 4-point transform matrix of Annex A.
     */
    private static final int[][] T4 = {
            {17, 17, 17, 17},
            {22, 10, -10, -22},
            {17, -17, -17, 17},
            {10, -22, 22, -10}
    };

    /**
     * Inverse transforms a block in place.
     *
     * @param block the dequantized coefficients, rows 8 apart; replaced by the residual
     * @param offset the index of the block's top-left coefficient
     * @param width the block width, 4 or 8
     * @param height the block height, 4 or 8
     * @param temp a scratch array of at least 64 entries
     */
    static void inverse(int[] block, int offset, int width, int height, int[] temp) {
        var rowMatrix = width == 8 ? T8 : T4;
        for (var r = 0; r < height; r++) {
            var base = offset + r * 8;
            var allZero = true;
            for (var j = 0; j < width; j++) {
                if (block[base + j] != 0) {
                    allZero = false;
                    break;
                }
            }
            for (var k = 0; k < width; k++) {
                if (allZero) {
                    temp[r * 8 + k] = 0;
                    continue;
                }
                var sum = 4;
                for (var j = 0; j < width; j++) {
                    sum += block[base + j] * rowMatrix[j][k];
                }
                temp[r * 8 + k] = sum >> 3;
            }
        }
        var columnMatrix = height == 8 ? T8 : T4;
        for (var c = 0; c < width; c++) {
            for (var k = 0; k < height; k++) {
                var sum = 64 + (height == 8 && k >= 4 ? 1 : 0);
                for (var j = 0; j < height; j++) {
                    sum += columnMatrix[j][k] * temp[j * 8 + c];
                }
                block[offset + k * 8 + c] = sum >> 7;
            }
        }
    }
}
