/* Ports jxl-oxide 0.12.6 (crates/jxl-vardct: TransformType). Copyright Wonwoo Choi and the jxl-oxide contributors;
   MIT License. Modified for Smalt. */
package com.github.auties00.smalt.image.jxl.internal.vardct;

/**
 * Describes the varblock transform types of VarDCT frames (ISO/IEC 18181-1 table I.1,
 * {@code DctSelect}): their size in 8x8 blocks, their coefficient order and their dequantization
 * matrix.
 *
 * <p>Types are identified by their codestream value, from 0 to 26; every value of the
 * specification has its constant, used or not. Block sizes are given as {@code (width, height)}
 * in 8x8 blocks. Every method is a pure function of its arguments.
 */
public final class TransformType {
    /**
     * The 8x8 DCT.
     */
    public static final int DCT8 = 0;

    /**
     * The Hornuss transform.
     */
    public static final int HORNUSS = 1;

    /**
     * The 2x2 DCT.
     */
    public static final int DCT2 = 2;

    /**
     * The 4x4 DCT.
     */
    public static final int DCT4 = 3;

    /**
     * The 16x16 DCT.
     */
    public static final int DCT16 = 4;

    /**
     * The 32x32 DCT.
     */
    public static final int DCT32 = 5;

    /**
     * The 16x8 DCT (16 rows, 8 columns).
     */
    public static final int DCT16X8 = 6;

    /**
     * The 8x16 DCT.
     */
    public static final int DCT8X16 = 7;

    /**
     * The 32x8 DCT.
     */
    public static final int DCT32X8 = 8;

    /**
     * The 8x32 DCT.
     */
    public static final int DCT8X32 = 9;

    /**
     * The 32x16 DCT.
     */
    public static final int DCT32X16 = 10;

    /**
     * The 16x32 DCT.
     */
    public static final int DCT16X32 = 11;

    /**
     * The 4x8 DCT.
     */
    public static final int DCT4X8 = 12;

    /**
     * The 8x4 DCT.
     */
    public static final int DCT8X4 = 13;

    /**
     * The first adaptive flat variant transform.
     */
    public static final int AFV0 = 14;

    /**
     * The second adaptive flat variant transform.
     */
    public static final int AFV1 = 15;

    /**
     * The third adaptive flat variant transform.
     */
    public static final int AFV2 = 16;

    /**
     * The fourth adaptive flat variant transform.
     */
    public static final int AFV3 = 17;

    /**
     * The 64x64 DCT.
     */
    public static final int DCT64 = 18;

    /**
     * The 64x32 DCT.
     */
    public static final int DCT64X32 = 19;

    /**
     * The 32x64 DCT.
     */
    public static final int DCT32X64 = 20;

    /**
     * The 128x128 DCT.
     */
    public static final int DCT128 = 21;

    /**
     * The 128x64 DCT.
     */
    public static final int DCT128X64 = 22;

    /**
     * The 64x128 DCT.
     */
    public static final int DCT64X128 = 23;

    /**
     * The 256x256 DCT.
     */
    public static final int DCT256 = 24;

    /**
     * The 256x128 DCT.
     */
    public static final int DCT256X128 = 25;

    /**
     * The 128x256 DCT.
     */
    public static final int DCT128X256 = 26;

    /**
     * The number of transform types.
     */
    public static final int COUNT = 27;

    /**
     * The width of each type, in blocks.
     */
    private static final int[] WIDTH = {1, 1, 1, 1, 2, 4, 1, 2, 1, 4, 2, 4, 1, 1, 1, 1, 1, 1, 8, 4, 8, 16, 8, 16, 32, 16, 32};

    /**
     * The height of each type, in blocks.
     */
    private static final int[] HEIGHT = {1, 1, 1, 1, 2, 4, 2, 1, 4, 1, 4, 2, 1, 1, 1, 1, 1, 1, 8, 8, 4, 16, 16, 8, 32, 32, 16};

    /**
     * The coefficient order of each type.
     */
    private static final int[] ORDER = {0, 1, 1, 1, 2, 3, 4, 4, 5, 5, 6, 6, 1, 1, 1, 1, 1, 1, 7, 8, 8, 9, 10, 10, 11, 12, 12};

    /**
     * The dequantization parameter set of each type.
     */
    private static final int[] MATRIX = {0, 1, 2, 3, 4, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 10, 10, 11, 12, 12, 13, 14, 14, 15, 16, 16};

    /**
     * The width of each dequantization parameter set, in coefficients.
     */
    private static final int[] MATRIX_WIDTH = {8, 8, 8, 8, 16, 32, 16, 32, 32, 8, 8, 64, 64, 128, 128, 256, 256};

    /**
     * The height of each dequantization parameter set, in coefficients.
     */
    private static final int[] MATRIX_HEIGHT = {8, 8, 8, 8, 16, 32, 8, 8, 16, 8, 8, 64, 32, 128, 64, 256, 128};

    /**
     * Prevents instantiation.
     */
    private TransformType() {
        throw new AssertionError();
    }

    /**
     * Returns the width of a type.
     *
     * @param type the transform type, from 0 to 26
     * @return the width in 8x8 blocks
     */
    public static int width(int type) {
        return WIDTH[type];
    }

    /**
     * Returns the height of a type.
     *
     * @param type the transform type, from 0 to 26
     * @return the height in 8x8 blocks
     */
    public static int height(int type) {
        return HEIGHT[type];
    }

    /**
     * Returns the coefficient order of a type.
     *
     * @param type the transform type, from 0 to 26
     * @return the order index, from 0 to 12
     */
    public static int order(int type) {
        return ORDER[type];
    }

    /**
     * Returns the dequantization parameter set of a type.
     *
     * @param type the transform type, from 0 to 26
     * @return the set index, from 0 to 16
     */
    public static int matrix(int type) {
        return MATRIX[type];
    }

    /**
     * Returns the width of a dequantization parameter set.
     *
     * @param set the set index
     * @return the width in coefficients
     */
    public static int matrixWidth(int set) {
        return MATRIX_WIDTH[set];
    }

    /**
     * Returns the height of a dequantization parameter set.
     *
     * @param set the set index
     * @return the height in coefficients
     */
    public static int matrixHeight(int set) {
        return MATRIX_HEIGHT[set];
    }

    /**
     * Returns whether the coefficients of a type are stored transposed relative to its order.
     *
     * @param type the transform type, from 0 to 26
     * @return {@code true} for the DCTs at least as tall as wide
     */
    public static boolean needsTranspose(int type) {
        if (type == HORNUSS || type == DCT2 || type == DCT4 || type == DCT4X8 || type == DCT8X4
                || (type >= AFV0 && type <= AFV3)) {
            return false;
        }
        return HEIGHT[type] >= WIDTH[type];
    }
}
