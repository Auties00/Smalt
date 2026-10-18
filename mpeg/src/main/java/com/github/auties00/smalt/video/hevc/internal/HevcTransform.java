package com.github.auties00.smalt.video.hevc.internal;

import java.util.Arrays;

/**
 * Computes the inverse transforms of ITU-T H.265 8.6.4: the 4x4 to 32x32 integer DCT and the 4x4
 * DST of intra luma blocks, with the intermediate clipping and rounding of the specification.
 *
 * <p>Blocks are raster arrays indexed {@code y * size + x}, where {@code x} is the horizontal
 * frequency for coefficients and the column for residuals.
 *
 * <p>The DCT of a block with more than its DC coefficient runs through {@link HevcDsp#inverseDct},
 * whose scalar implementation is {@link #dct}.
 *
 * @implNote This implementation evaluates each one-dimensional DCT as a sum of basis vectors
 * weighted by the non-zero inputs, skipping zero inputs and the rows and columns beyond
 * the last significant coefficient: residual blocks are mostly zero. The basis vectors of
 * even frequencies are symmetric and those of odd frequencies antisymmetric, so the sums
 * run over the first half of the samples only, the even and odd parts kept apart: the
 * second half is their difference (the partial butterfly of the HM reference software,
 * here in a sparse form). A block whose only non-zero coefficient is the DC one is a
 * constant, computed directly.
 */
public final class HevcTransform {
    /**
     * The 32-point DCT basis {@code transMatrix} of equations 8-319 to 8-321, indexed
     * {@code [frequency * 32 + sample]}.
     */
    static final int[] DCT32 = {
            64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64,
            90, 90, 88, 85, 82, 78, 73, 67, 61, 54, 46, 38, 31, 22, 13, 4, -4, -13, -22, -31, -38, -46, -54, -61, -67, -73, -78, -82, -85, -88, -90, -90,
            90, 87, 80, 70, 57, 43, 25, 9, -9, -25, -43, -57, -70, -80, -87, -90, -90, -87, -80, -70, -57, -43, -25, -9, 9, 25, 43, 57, 70, 80, 87, 90,
            90, 82, 67, 46, 22, -4, -31, -54, -73, -85, -90, -88, -78, -61, -38, -13, 13, 38, 61, 78, 88, 90, 85, 73, 54, 31, 4, -22, -46, -67, -82, -90,
            89, 75, 50, 18, -18, -50, -75, -89, -89, -75, -50, -18, 18, 50, 75, 89, 89, 75, 50, 18, -18, -50, -75, -89, -89, -75, -50, -18, 18, 50, 75, 89,
            88, 67, 31, -13, -54, -82, -90, -78, -46, -4, 38, 73, 90, 85, 61, 22, -22, -61, -85, -90, -73, -38, 4, 46, 78, 90, 82, 54, 13, -31, -67, -88,
            87, 57, 9, -43, -80, -90, -70, -25, 25, 70, 90, 80, 43, -9, -57, -87, -87, -57, -9, 43, 80, 90, 70, 25, -25, -70, -90, -80, -43, 9, 57, 87,
            85, 46, -13, -67, -90, -73, -22, 38, 82, 88, 54, -4, -61, -90, -78, -31, 31, 78, 90, 61, 4, -54, -88, -82, -38, 22, 73, 90, 67, 13, -46, -85,
            83, 36, -36, -83, -83, -36, 36, 83, 83, 36, -36, -83, -83, -36, 36, 83, 83, 36, -36, -83, -83, -36, 36, 83, 83, 36, -36, -83, -83, -36, 36, 83,
            82, 22, -54, -90, -61, 13, 78, 85, 31, -46, -90, -67, 4, 73, 88, 38, -38, -88, -73, -4, 67, 90, 46, -31, -85, -78, -13, 61, 90, 54, -22, -82,
            80, 9, -70, -87, -25, 57, 90, 43, -43, -90, -57, 25, 87, 70, -9, -80, -80, -9, 70, 87, 25, -57, -90, -43, 43, 90, 57, -25, -87, -70, 9, 80,
            78, -4, -82, -73, 13, 85, 67, -22, -88, -61, 31, 90, 54, -38, -90, -46, 46, 90, 38, -54, -90, -31, 61, 88, 22, -67, -85, -13, 73, 82, 4, -78,
            75, -18, -89, -50, 50, 89, 18, -75, -75, 18, 89, 50, -50, -89, -18, 75, 75, -18, -89, -50, 50, 89, 18, -75, -75, 18, 89, 50, -50, -89, -18, 75,
            73, -31, -90, -22, 78, 67, -38, -90, -13, 82, 61, -46, -88, -4, 85, 54, -54, -85, 4, 88, 46, -61, -82, 13, 90, 38, -67, -78, 22, 90, 31, -73,
            70, -43, -87, 9, 90, 25, -80, -57, 57, 80, -25, -90, -9, 87, 43, -70, -70, 43, 87, -9, -90, -25, 80, 57, -57, -80, 25, 90, 9, -87, -43, 70,
            67, -54, -78, 38, 85, -22, -90, 4, 90, 13, -88, -31, 82, 46, -73, -61, 61, 73, -46, -82, 31, 88, -13, -90, -4, 90, 22, -85, -38, 78, 54, -67,
            64, -64, -64, 64, 64, -64, -64, 64, 64, -64, -64, 64, 64, -64, -64, 64, 64, -64, -64, 64, 64, -64, -64, 64, 64, -64, -64, 64, 64, -64, -64, 64,
            61, -73, -46, 82, 31, -88, -13, 90, -4, -90, 22, 85, -38, -78, 54, 67, -67, -54, 78, 38, -85, -22, 90, 4, -90, 13, 88, -31, -82, 46, 73, -61,
            57, -80, -25, 90, -9, -87, 43, 70, -70, -43, 87, 9, -90, 25, 80, -57, -57, 80, 25, -90, 9, 87, -43, -70, 70, 43, -87, -9, 90, -25, -80, 57,
            54, -85, -4, 88, -46, -61, 82, 13, -90, 38, 67, -78, -22, 90, -31, -73, 73, 31, -90, 22, 78, -67, -38, 90, -13, -82, 61, 46, -88, 4, 85, -54,
            50, -89, 18, 75, -75, -18, 89, -50, -50, 89, -18, -75, 75, 18, -89, 50, 50, -89, 18, 75, -75, -18, 89, -50, -50, 89, -18, -75, 75, 18, -89, 50,
            46, -90, 38, 54, -90, 31, 61, -88, 22, 67, -85, 13, 73, -82, 4, 78, -78, -4, 82, -73, -13, 85, -67, -22, 88, -61, -31, 90, -54, -38, 90, -46,
            43, -90, 57, 25, -87, 70, 9, -80, 80, -9, -70, 87, -25, -57, 90, -43, -43, 90, -57, -25, 87, -70, -9, 80, -80, 9, 70, -87, 25, 57, -90, 43,
            38, -88, 73, -4, -67, 90, -46, -31, 85, -78, 13, 61, -90, 54, 22, -82, 82, -22, -54, 90, -61, -13, 78, -85, 31, 46, -90, 67, 4, -73, 88, -38,
            36, -83, 83, -36, -36, 83, -83, 36, 36, -83, 83, -36, -36, 83, -83, 36, 36, -83, 83, -36, -36, 83, -83, 36, 36, -83, 83, -36, -36, 83, -83, 36,
            31, -78, 90, -61, 4, 54, -88, 82, -38, -22, 73, -90, 67, -13, -46, 85, -85, 46, 13, -67, 90, -73, 22, 38, -82, 88, -54, -4, 61, -90, 78, -31,
            25, -70, 90, -80, 43, 9, -57, 87, -87, 57, -9, -43, 80, -90, 70, -25, -25, 70, -90, 80, -43, -9, 57, -87, 87, -57, 9, 43, -80, 90, -70, 25,
            22, -61, 85, -90, 73, -38, -4, 46, -78, 90, -82, 54, -13, -31, 67, -88, 88, -67, 31, 13, -54, 82, -90, 78, -46, 4, 38, -73, 90, -85, 61, -22,
            18, -50, 75, -89, 89, -75, 50, -18, -18, 50, -75, 89, -89, 75, -50, 18, 18, -50, 75, -89, 89, -75, 50, -18, -18, 50, -75, 89, -89, 75, -50, 18,
            13, -38, 61, -78, 88, -90, 85, -73, 54, -31, 4, 22, -46, 67, -82, 90, -90, 82, -67, 46, -22, -4, 31, -54, 73, -85, 90, -88, 78, -61, 38, -13,
            9, -25, 43, -57, 70, -80, 87, -90, 90, -87, 80, -70, 57, -43, 25, -9, -9, 25, -43, 57, -70, 80, -87, 90, -90, 87, -80, 70, -57, 43, -25, 9,
            4, -13, 22, -31, 38, -46, 54, -61, 67, -73, 78, -82, 85, -88, 90, -90, 90, -90, 88, -85, 82, -78, 73, -67, 61, -54, 46, -38, 31, -22, 13, -4
    };

    /**
     * The 4-point DST basis of equation 8-317, indexed {@code [frequency * 4 + sample]}.
     */
    static final int[] DST4 = {
            29, 55, 74, 84,
            74, 74, 0, -74,
            84, -29, -74, 55,
            55, -84, 74, -29
    };

    /**
     * The intermediate block after the vertical transform, transposed: the transformed column
     * {@code x} occupies entries {@code x * size} to {@code x * size + size - 1}, so both passes
     * accumulate into consecutive entries.
     */
    private final int[] intermediate = new int[32 * 32];

    /**
     * The number of entries of the scratch buffer of {@link HevcDsp#inverseDct}: the intermediate
     * block and the even and odd halves of a one-dimensional transform.
     */
    public static final int SCRATCH_INTS = 32 * 32 + 2 * 16;

    /**
     * The scratch buffer of the DCT, {@link #SCRATCH_INTS} entries.
     */
    private final int[] dctScratch = new int[SCRATCH_INTS];

    /**
     * Constructs a transform with its own scratch memory; instances are not thread-safe.
     */
    public HevcTransform() {
    }

    /**
     * Transforms a block of scaled coefficients into residual samples with the extended dynamic
     * range of {@code extended_precision_processing_flag} (ITU-T H.265 8.6.4 and equation 8-299):
     * the intermediate samples are clipped to {@code coeffMin..coeffMax} instead of 16 bits, and
     * the sums are evaluated in 64 bits, as coefficients of up to 23 bits overflow 32-bit sums.
     *
     * @param coefficients the scaled transform coefficients {@code d}, {@code size * size} entries
     * @param log2Size the block size as a power of two, 2 to 5
     * @param dst whether the block uses the DST ({@code trType} 1): 4x4 intra luma blocks
     * @param bdShift the final shift
     * @param coeffMin {@code CoeffMinY} or {@code CoeffMinC}
     * @param coeffMax {@code CoeffMaxY} or {@code CoeffMaxC}
     * @param residual receives the residual samples, {@code size * size} entries
     */
    public void inverseWide(int[] coefficients, int log2Size, boolean dst, int bdShift, int coeffMin, int coeffMax,
                            int[] residual) {
        var size = 1 << log2Size;
        var basis = dst ? DST4 : DCT32;
        var step = dst ? 4 : (32 >> log2Size) * 32;
        var tmp = intermediate;
        for (var x = 0; x < size; x++) {
            for (var n = 0; n < size; n++) {
                long sum = 0;
                for (var k = 0; k < size; k++) {
                    sum += (long) basis[k * step + n] * coefficients[k * size + x];
                }
                tmp[x * size + n] = (int) Math.clamp((sum + 64) >> 7, coeffMin, coeffMax);
            }
        }
        var add = 1L << (bdShift - 1);
        for (var y = 0; y < size; y++) {
            for (var n = 0; n < size; n++) {
                long sum = 0;
                for (var k = 0; k < size; k++) {
                    sum += (long) basis[k * step + n] * tmp[k * size + y];
                }
                residual[y * size + n] = (int) ((sum + add) >> bdShift);
            }
        }
    }

    /**
     * Transforms a block of scaled coefficients into residual samples (ITU-T H.265 8.6.4 and
     * equation 8-299).
     *
     * @param coefficients the scaled transform coefficients {@code d}, {@code size * size} entries
     * @param log2Size the block size as a power of two, 2 to 5
     * @param dst whether the block uses the DST ({@code trType} 1): 4x4 intra luma blocks
     * @param bdShift the final shift, {@code 20 - bitDepth}
     * @param columns the number of leading columns that may hold non-zero coefficients
     * @param rows the number of leading rows that may hold non-zero coefficients
     * @param residual receives the residual samples, {@code size * size} entries
     * @param dsp the kernels that compute the DCT
     */
    public void inverse(int[] coefficients, int log2Size, boolean dst, int bdShift, int columns, int rows,
                        int[] residual, HevcDsp dsp) {
        var size = 1 << log2Size;
        var count = size * size;
        if (!dst && columns == 1 && rows == 1) {
            var column = Math.clamp((64 * coefficients[0] + 64) >> 7, -32768, 32767);
            Arrays.fill(residual, 0, count, (64 * column + (1 << (bdShift - 1))) >> bdShift);
            return;
        }
        if (log2Size == 2) {
            if (dst) {
                dst4(coefficients, bdShift, residual);
            } else {
                dct4(coefficients, bdShift, residual);
            }
            return;
        }
        dsp.inverseDct(coefficients, log2Size, bdShift, columns, rows, residual, dctScratch);
    }

    /**
     * Computes the inverse DCT of a 4x4 block with the even-odd decomposition of the 4-point
     * transform, which gives the sums of {@link #dct} exactly.
     *
     * @param c the scaled transform coefficients, 16 entries
     * @param bdShift the final shift
     * @param res receives the residual samples, 16 entries
     */
    private static void dct4(int[] c, int bdShift, int[] res) {
        var e0 = 64 * (c[0] + c[8]);
        var e1 = 64 * (c[0] - c[8]);
        var o0 = 83 * c[4] + 36 * c[12];
        var o1 = 36 * c[4] - 83 * c[12];
        var m00 = Math.clamp((e0 + o0 + 64) >> 7, -32768, 32767);
        var m01 = Math.clamp((e1 + o1 + 64) >> 7, -32768, 32767);
        var m02 = Math.clamp((e1 - o1 + 64) >> 7, -32768, 32767);
        var m03 = Math.clamp((e0 - o0 + 64) >> 7, -32768, 32767);
        e0 = 64 * (c[1] + c[9]);
        e1 = 64 * (c[1] - c[9]);
        o0 = 83 * c[5] + 36 * c[13];
        o1 = 36 * c[5] - 83 * c[13];
        var m10 = Math.clamp((e0 + o0 + 64) >> 7, -32768, 32767);
        var m11 = Math.clamp((e1 + o1 + 64) >> 7, -32768, 32767);
        var m12 = Math.clamp((e1 - o1 + 64) >> 7, -32768, 32767);
        var m13 = Math.clamp((e0 - o0 + 64) >> 7, -32768, 32767);
        e0 = 64 * (c[2] + c[10]);
        e1 = 64 * (c[2] - c[10]);
        o0 = 83 * c[6] + 36 * c[14];
        o1 = 36 * c[6] - 83 * c[14];
        var m20 = Math.clamp((e0 + o0 + 64) >> 7, -32768, 32767);
        var m21 = Math.clamp((e1 + o1 + 64) >> 7, -32768, 32767);
        var m22 = Math.clamp((e1 - o1 + 64) >> 7, -32768, 32767);
        var m23 = Math.clamp((e0 - o0 + 64) >> 7, -32768, 32767);
        e0 = 64 * (c[3] + c[11]);
        e1 = 64 * (c[3] - c[11]);
        o0 = 83 * c[7] + 36 * c[15];
        o1 = 36 * c[7] - 83 * c[15];
        var m30 = Math.clamp((e0 + o0 + 64) >> 7, -32768, 32767);
        var m31 = Math.clamp((e1 + o1 + 64) >> 7, -32768, 32767);
        var m32 = Math.clamp((e1 - o1 + 64) >> 7, -32768, 32767);
        var m33 = Math.clamp((e0 - o0 + 64) >> 7, -32768, 32767);
        var add = 1 << (bdShift - 1);
        e0 = 64 * (m00 + m20);
        e1 = 64 * (m00 - m20);
        o0 = 83 * m10 + 36 * m30;
        o1 = 36 * m10 - 83 * m30;
        res[0] = (e0 + o0 + add) >> bdShift;
        res[1] = (e1 + o1 + add) >> bdShift;
        res[2] = (e1 - o1 + add) >> bdShift;
        res[3] = (e0 - o0 + add) >> bdShift;
        e0 = 64 * (m01 + m21);
        e1 = 64 * (m01 - m21);
        o0 = 83 * m11 + 36 * m31;
        o1 = 36 * m11 - 83 * m31;
        res[4] = (e0 + o0 + add) >> bdShift;
        res[5] = (e1 + o1 + add) >> bdShift;
        res[6] = (e1 - o1 + add) >> bdShift;
        res[7] = (e0 - o0 + add) >> bdShift;
        e0 = 64 * (m02 + m22);
        e1 = 64 * (m02 - m22);
        o0 = 83 * m12 + 36 * m32;
        o1 = 36 * m12 - 83 * m32;
        res[8] = (e0 + o0 + add) >> bdShift;
        res[9] = (e1 + o1 + add) >> bdShift;
        res[10] = (e1 - o1 + add) >> bdShift;
        res[11] = (e0 - o0 + add) >> bdShift;
        e0 = 64 * (m03 + m23);
        e1 = 64 * (m03 - m23);
        o0 = 83 * m13 + 36 * m33;
        o1 = 36 * m13 - 83 * m33;
        res[12] = (e0 + o0 + add) >> bdShift;
        res[13] = (e1 + o1 + add) >> bdShift;
        res[14] = (e1 - o1 + add) >> bdShift;
        res[15] = (e0 - o0 + add) >> bdShift;
    }

    /**
     * Computes the inverse DST of a 4x4 block, the transform of intra luma blocks of that size,
     * with the sums of {@link #DST4} written out.
     *
     * @param c the scaled transform coefficients, 16 entries
     * @param bdShift the final shift
     * @param res receives the residual samples, 16 entries
     */
    private static void dst4(int[] c, int bdShift, int[] res) {
        var m00 = Math.clamp((29 * c[0] + 74 * c[4] + 84 * c[8] + 55 * c[12] + 64) >> 7, -32768, 32767);
        var m01 = Math.clamp((55 * c[0] + 74 * c[4] - 29 * c[8] - 84 * c[12] + 64) >> 7, -32768, 32767);
        var m02 = Math.clamp((74 * c[0] - 74 * c[8] + 74 * c[12] + 64) >> 7, -32768, 32767);
        var m03 = Math.clamp((84 * c[0] - 74 * c[4] + 55 * c[8] - 29 * c[12] + 64) >> 7, -32768, 32767);
        var m10 = Math.clamp((29 * c[1] + 74 * c[5] + 84 * c[9] + 55 * c[13] + 64) >> 7, -32768, 32767);
        var m11 = Math.clamp((55 * c[1] + 74 * c[5] - 29 * c[9] - 84 * c[13] + 64) >> 7, -32768, 32767);
        var m12 = Math.clamp((74 * c[1] - 74 * c[9] + 74 * c[13] + 64) >> 7, -32768, 32767);
        var m13 = Math.clamp((84 * c[1] - 74 * c[5] + 55 * c[9] - 29 * c[13] + 64) >> 7, -32768, 32767);
        var m20 = Math.clamp((29 * c[2] + 74 * c[6] + 84 * c[10] + 55 * c[14] + 64) >> 7, -32768, 32767);
        var m21 = Math.clamp((55 * c[2] + 74 * c[6] - 29 * c[10] - 84 * c[14] + 64) >> 7, -32768, 32767);
        var m22 = Math.clamp((74 * c[2] - 74 * c[10] + 74 * c[14] + 64) >> 7, -32768, 32767);
        var m23 = Math.clamp((84 * c[2] - 74 * c[6] + 55 * c[10] - 29 * c[14] + 64) >> 7, -32768, 32767);
        var m30 = Math.clamp((29 * c[3] + 74 * c[7] + 84 * c[11] + 55 * c[15] + 64) >> 7, -32768, 32767);
        var m31 = Math.clamp((55 * c[3] + 74 * c[7] - 29 * c[11] - 84 * c[15] + 64) >> 7, -32768, 32767);
        var m32 = Math.clamp((74 * c[3] - 74 * c[11] + 74 * c[15] + 64) >> 7, -32768, 32767);
        var m33 = Math.clamp((84 * c[3] - 74 * c[7] + 55 * c[11] - 29 * c[15] + 64) >> 7, -32768, 32767);
        var add = 1 << (bdShift - 1);
        res[0] = (29 * m00 + 74 * m10 + 84 * m20 + 55 * m30 + add) >> bdShift;
        res[1] = (55 * m00 + 74 * m10 - 29 * m20 - 84 * m30 + add) >> bdShift;
        res[2] = (74 * m00 - 74 * m20 + 74 * m30 + add) >> bdShift;
        res[3] = (84 * m00 - 74 * m10 + 55 * m20 - 29 * m30 + add) >> bdShift;
        res[4] = (29 * m01 + 74 * m11 + 84 * m21 + 55 * m31 + add) >> bdShift;
        res[5] = (55 * m01 + 74 * m11 - 29 * m21 - 84 * m31 + add) >> bdShift;
        res[6] = (74 * m01 - 74 * m21 + 74 * m31 + add) >> bdShift;
        res[7] = (84 * m01 - 74 * m11 + 55 * m21 - 29 * m31 + add) >> bdShift;
        res[8] = (29 * m02 + 74 * m12 + 84 * m22 + 55 * m32 + add) >> bdShift;
        res[9] = (55 * m02 + 74 * m12 - 29 * m22 - 84 * m32 + add) >> bdShift;
        res[10] = (74 * m02 - 74 * m22 + 74 * m32 + add) >> bdShift;
        res[11] = (84 * m02 - 74 * m12 + 55 * m22 - 29 * m32 + add) >> bdShift;
        res[12] = (29 * m03 + 74 * m13 + 84 * m23 + 55 * m33 + add) >> bdShift;
        res[13] = (55 * m03 + 74 * m13 - 29 * m23 - 84 * m33 + add) >> bdShift;
        res[14] = (74 * m03 - 74 * m23 + 74 * m33 + add) >> bdShift;
        res[15] = (84 * m03 - 74 * m13 + 55 * m23 - 29 * m33 + add) >> bdShift;
    }

    /**
     * Computes the inverse DCT of a block (ITU-T H.265 8.6.4.2), the scalar implementation of
     * {@link HevcDsp#inverseDct}: the vertical transform of every column that may hold non-zero
     * coefficients, its results rounded, shifted by 7 and clipped to 16 bits, then the horizontal
     * transform of every row, rounded and shifted by {@code bdShift}.
     *
     * @param coefficients the scaled transform coefficients {@code d}, {@code size * size} entries
     * @param log2Size the block size as a power of two, 2 to 5
     * @param bdShift the final shift, {@code 20 - bitDepth}
     * @param columns the number of leading columns that may hold non-zero coefficients
     * @param rows the number of leading rows that may hold non-zero coefficients
     * @param residual receives the residual samples, {@code size * size} entries
     * @param scratch a scratch buffer of {@link #SCRATCH_INTS} entries
     */
    static void dct(int[] coefficients, int log2Size, int bdShift, int columns, int rows, int[] residual,
                    int[] scratch) {
        var size = 1 << log2Size;
        var step = (32 >> log2Size) * 32;
        var half = size >> 1;
        var basis = DCT32;
        var tmp = scratch;
        var even = 32 * 32;
        var odd = even + 16;
        for (var x = 0; x < columns; x++) {
            for (var n = 0; n < half; n++) {
                tmp[even + n] = 0;
                tmp[odd + n] = 0;
            }
            for (var k = 0; k < rows; k++) {
                var c = coefficients[k * size + x];
                if (c == 0) {
                    continue;
                }
                var basisRow = k * step;
                var target = (k & 1) == 0 ? even : odd;
                for (var n = 0; n < half; n++) {
                    tmp[target + n] += basis[basisRow + n] * c;
                }
            }
            var column = x * size;
            for (var n = 0; n < half; n++) {
                tmp[column + n] = Math.clamp((tmp[even + n] + tmp[odd + n] + 64) >> 7, -32768, 32767);
                tmp[column + size - 1 - n] = Math.clamp((tmp[even + n] - tmp[odd + n] + 64) >> 7, -32768, 32767);
            }
        }
        var add = 1 << (bdShift - 1);
        for (var y = 0; y < size; y++) {
            for (var n = 0; n < half; n++) {
                tmp[even + n] = 0;
                tmp[odd + n] = 0;
            }
            for (var k = 0; k < columns; k++) {
                var t = tmp[k * size + y];
                if (t == 0) {
                    continue;
                }
                var basisRow = k * step;
                var target = (k & 1) == 0 ? even : odd;
                for (var n = 0; n < half; n++) {
                    tmp[target + n] += basis[basisRow + n] * t;
                }
            }
            var row = y * size;
            for (var n = 0; n < half; n++) {
                residual[row + n] = (tmp[even + n] + tmp[odd + n] + add) >> bdShift;
                residual[row + size - 1 - n] = (tmp[even + n] - tmp[odd + n] + add) >> bdShift;
            }
        }
    }
}
