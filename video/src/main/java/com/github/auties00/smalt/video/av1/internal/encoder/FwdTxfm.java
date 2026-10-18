package com.github.auties00.smalt.video.av1.internal.encoder;

import com.github.auties00.smalt.video.av1.internal.Tables;

/**
 * Computes the forward two-dimensional transforms of AV1 residual blocks, the inverse of the
 * decoder's {@code DCT_DCT} transforms at every transform size, of its {@code ADST_DCT},
 * {@code DCT_ADST} and {@code ADST_ADST} transforms up to 16 samples per side, of the identity
 * transforms ({@code IDTX} up to 32 samples, the one-dimensional {@code V_DCT}, {@code H_DCT},
 * {@code V_ADST} and {@code H_ADST} up to 16) and of the lossless Walsh-Hadamard transform.
 *
 * <p>Coefficients are produced in the decoder's layout: column-major, the coefficient of
 * horizontal frequency {@code x} and vertical frequency {@code y} at {@code x * h + y}, with
 * {@code h} the transform height capped at 32. Transforms with a 64-sample side keep only their
 * 32x32 lowest frequencies, as the bitstream codes only those.
 *
 * <p>An instance holds scratch buffers and is used by one thread at a time.
 *
 * @implNote This implementation ports libaom's {@code av1/encoder/av1_fwd_txfm1d.c}
 * ({@code av1_fdct4} to {@code av1_fdct64}, {@code av1_fadst4} to {@code av1_fadst16},
 * {@code av1_fidentity4_c} to {@code av1_fidentity32_c}), {@code av1_fwht4x4_c}
 * ({@code av1/encoder/av1_fwd_txfm1d.c}, {@code av1/encoder/hybrid_fwd_txfm.c}) and the two-dimensional driver of
 * {@code av1/encoder/av1_fwd_txfm2d.c} ({@code fwd_txfm2d_c} with the shift and cosine
 * precision tables of {@code av1_get_fwd_txfm_cfg}), so the coefficients match libaom's
 * exactly. The 32- and 64-point transforms are split into one method per butterfly
 * stage to stay below the JIT compiler's method size limits.
 */
final class FwdTxfm {
    /**
     * The transform type with a vertical ADST and a horizontal DCT.
     */
    static final int ADST_DCT = 1;

    /**
     * The transform type with a vertical DCT and a horizontal ADST.
     */
    static final int DCT_ADST = 2;

    /**
     * The transform type with an ADST in both directions.
     */
    static final int ADST_ADST = 3;

    /**
     * The two-dimensional identity transform type.
     */
    static final int IDTX = 9;

    /**
     * The transform type with a vertical DCT and a horizontal identity.
     */
    static final int V_DCT = 10;

    /**
     * The transform type with a vertical identity and a horizontal DCT.
     */
    static final int H_DCT = 11;

    /**
     * The transform type with a vertical ADST and a horizontal identity.
     */
    static final int V_ADST = 12;

    /**
     * The transform type with a vertical identity and a horizontal ADST.
     */
    static final int H_ADST = 13;

    /**
     * The one-dimensional DCT.
     */
    static final int KIND_DCT = 0;

    /**
     * The one-dimensional ADST.
     */
    static final int KIND_ADST = 1;

    /**
     * The one-dimensional identity transform.
     */
    static final int KIND_IDENTITY = 2;

    /**
     * The sine tables of the 4-point ADST, one row per cosine precision from
     * {@link #COS_BIT_MIN} ({@code av1_sinpi_arr_data}).
     */
    private static final int[][] SINPI = {
            {0, 330, 621, 836, 951}, {0, 660, 1241, 1672, 1901}, {0, 1321, 2482, 3344, 3803},
            {0, 2642, 4964, 6689, 7606}
    };

    /**
     * The precision of the first row of {@link #COSPI}.
     */
    private static final int COS_BIT_MIN = 10;

    /**
     * The cosine tables {@code round(cos(j * pi / 128) * (1 << (10 + i)))} ({@code av1_cospi_arr_data}).
     */
    private static final int[][] COSPI = {
            {1024, 1024, 1023, 1021, 1019, 1016, 1013, 1009, 1004, 999, 993, 987, 980,
                    972, 964, 955, 946, 936, 926, 915, 903, 891, 878, 865, 851, 837,
                    822, 807, 792, 775, 759, 742, 724, 706, 688, 669, 650, 630, 610,
                    590, 569, 548, 526, 505, 483, 460, 438, 415, 392, 369, 345, 321,
                    297, 273, 249, 224, 200, 175, 150, 125, 100, 75, 50, 25},
            {2048, 2047, 2046, 2042, 2038, 2033, 2026, 2018, 2009, 1998, 1987,
                    1974, 1960, 1945, 1928, 1911, 1892, 1872, 1851, 1829, 1806, 1782,
                    1757, 1730, 1703, 1674, 1645, 1615, 1583, 1551, 1517, 1483, 1448,
                    1412, 1375, 1338, 1299, 1260, 1220, 1179, 1138, 1096, 1053, 1009,
                    965, 921, 876, 830, 784, 737, 690, 642, 595, 546, 498,
                    449, 400, 350, 301, 251, 201, 151, 100, 50},
            {4096, 4095, 4091, 4085, 4076, 4065, 4052, 4036, 4017, 3996, 3973,
                    3948, 3920, 3889, 3857, 3822, 3784, 3745, 3703, 3659, 3612, 3564,
                    3513, 3461, 3406, 3349, 3290, 3229, 3166, 3102, 3035, 2967, 2896,
                    2824, 2751, 2675, 2598, 2520, 2440, 2359, 2276, 2191, 2106, 2019,
                    1931, 1842, 1751, 1660, 1567, 1474, 1380, 1285, 1189, 1092, 995,
                    897, 799, 700, 601, 501, 401, 301, 201, 101},
            {8192, 8190, 8182, 8170, 8153, 8130, 8103, 8071, 8035, 7993, 7946,
                    7895, 7839, 7779, 7713, 7643, 7568, 7489, 7405, 7317, 7225, 7128,
                    7027, 6921, 6811, 6698, 6580, 6458, 6333, 6203, 6070, 5933, 5793,
                    5649, 5501, 5351, 5197, 5040, 4880, 4717, 4551, 4383, 4212, 4038,
                    3862, 3683, 3503, 3320, 3135, 2948, 2760, 2570, 2378, 2185, 1990,
                    1795, 1598, 1401, 1202, 1003, 803, 603, 402, 201}
    };

    /**
     * The three rounding shifts of each transform size: before the column transform, after it and
     * after the row transform ({@code av1_fwd_txfm_shift_ls}).
     */
    private static final int[][] SHIFT = {
            {2, 0, 0}, {2, -1, 0}, {2, -2, 0}, {2, -4, 0}, {0, -2, -2},
            {2, -1, 0}, {2, -1, 0}, {2, -2, 0}, {2, -2, 0}, {2, -4, 0}, {2, -4, 0},
            {0, -2, -2}, {2, -4, -2}, {2, -1, 0}, {2, -1, 0}, {2, -2, 0}, {2, -2, 0},
            {0, -2, 0}, {2, -4, 0}
    };

    /**
     * The cosine precision of the column transform by width and height index
     * ({@code av1_fwd_cos_bit_col}).
     */
    private static final int[][] COS_BIT_COL = {
            {13, 13, 13, 0, 0},
            {13, 13, 13, 12, 0},
            {13, 13, 13, 12, 13},
            {0, 13, 13, 12, 13},
            {0, 0, 13, 12, 13}
    };

    /**
     * The cosine precision of the row transform by width and height index
     * ({@code av1_fwd_cos_bit_row}).
     */
    private static final int[][] COS_BIT_ROW = {
            {13, 13, 12, 0, 0},
            {13, 13, 13, 12, 0},
            {13, 13, 12, 13, 12},
            {0, 12, 13, 12, 11},
            {0, 0, 12, 11, 10}
    };

    /**
     * The scaling of rectangular 2:1 transforms, {@code round(sqrt(2) * 4096)}.
     */
    static final int NEW_SQRT2 = 5793;

    /**
     * The precision of {@link #NEW_SQRT2}.
     */
    static final int NEW_SQRT2_BITS = 12;

    /**
     * The column-pass input of one column, and the row-pass input of one row.
     */
    private final int[] colIn = new int[64];

    /**
     * The column-pass output of one column.
     */
    private final int[] colOut = new int[64];

    /**
     * The butterfly scratch.
     */
    private final int[] step = new int[64];

    /**
     * The row-pass output of one row.
     */
    private final int[] rowOut = new int[64];

    /**
     * The intermediate block between the two passes, row-major.
     */
    private final int[] buf = new int[64 * 64];

    /**
     * The full-size output before the 64-sample sides are cropped.
     */
    private final int[] full = new int[64 * 64];

    /**
     * The eight-lane input scratch of {@link VectorFwdTxfm}.
     */
    final int[] vectorIn = new int[64 * 8];

    /**
     * The eight-lane output scratch of {@link VectorFwdTxfm}.
     */
    final int[] vectorOut = new int[64 * 8];

    /**
     * The eight-lane butterfly scratch of {@link VectorFwdTxfm}.
     */
    final int[] vectorStep = new int[64 * 8];

    /**
     * The column-major intermediate block of {@link VectorFwdTxfm}.
     */
    final int[] vectorBuf = new int[64 * 64];

    /**
     * Creates a transform with its scratch buffers.
     */
    FwdTxfm() {
    }

    /**
     * Rounds a product sum to the cosine precision ({@code half_btf}).
     *
     * @param w0 the first weight
     * @param in0 the first input
     * @param w1 the second weight
     * @param in1 the second input
     * @param bit the precision
     * @return the rounded result
     */
    private static int halfBtf(int w0, int in0, int w1, int in1, int bit) {
        var result = (long) (w0 * in0) + (long) (w1 * in1);
        return (int) ((result + (1L << (bit - 1))) >> bit);
    }

    /**
     * Returns the cosine table of a precision.
     *
     * @param cosBit the precision, 10 to 13
     * @return the table, indexed by multiples of {@code pi / 128}
     */
    static int[] cospi(int cosBit) {
        return COSPI[cosBit - COS_BIT_MIN];
    }

    /**
     * Returns the three rounding shifts of a transform size: before the columns, between the
     * passes and after the rows, negative for right shifts.
     *
     * @param txSize the transform size
     * @return the shifts
     */
    static int[] shift(int txSize) {
        return SHIFT[txSize];
    }

    /**
     * Returns the cosine precision of the column transforms of a size.
     *
     * @param txw the base-2 logarithm of the width in 4-sample units
     * @param txh the base-2 logarithm of the height in 4-sample units
     * @return the precision
     */
    static int cosBitCol(int txw, int txh) {
        return COS_BIT_COL[txw][txh];
    }

    /**
     * Returns the cosine precision of the row transforms of a size.
     *
     * @param txw the base-2 logarithm of the width in 4-sample units
     * @param txh the base-2 logarithm of the height in 4-sample units
     * @return the precision
     */
    static int cosBitRow(int txw, int txh) {
        return COS_BIT_ROW[txw][txh];
    }

    /**
     * Applies a one-dimensional DCT of the given size.
     *
     * @param n the size
     * @param input the input values
     * @param output receives the output values
     * @param cosBit the cosine precision
     */
    private void fdct(int n, int[] input, int[] output, int cosBit) {
        switch (n) {
            case 4 -> fdct4(input, output, step, cosBit);
            case 8 -> fdct8(input, output, step, cosBit);
            case 16 -> fdct16(input, output, step, cosBit);
            case 32 -> fdct32(input, output, step, cosBit);
            default -> fdct64(input, output, step, cosBit);
        }
    }

    /**
     * Returns the one-dimensional transform a transform type applies to the columns.
     *
     * @param txType the transform type, without flipping
     * @return {@link #KIND_DCT}, {@link #KIND_ADST} or {@link #KIND_IDENTITY}
     * @throws IllegalArgumentException if the type flips its input
     */
    static int columnKind(int txType) {
        return switch (txType) {
            case 0, DCT_ADST, V_DCT -> KIND_DCT;
            case ADST_DCT, ADST_ADST, V_ADST -> KIND_ADST;
            case IDTX, H_DCT, H_ADST -> KIND_IDENTITY;
            default -> throw new IllegalArgumentException("unsupported transform type " + txType);
        };
    }

    /**
     * Returns the one-dimensional transform a transform type applies to the rows.
     *
     * @param txType the transform type, without flipping
     * @return {@link #KIND_DCT}, {@link #KIND_ADST} or {@link #KIND_IDENTITY}
     * @throws IllegalArgumentException if the type flips its input
     */
    static int rowKind(int txType) {
        return switch (txType) {
            case 0, ADST_DCT, H_DCT -> KIND_DCT;
            case DCT_ADST, ADST_ADST, H_ADST -> KIND_ADST;
            case IDTX, V_DCT, V_ADST -> KIND_IDENTITY;
            default -> throw new IllegalArgumentException("unsupported transform type " + txType);
        };
    }

    /**
     * Applies one one-dimensional transform.
     *
     * @param kind {@link #KIND_DCT}, {@link #KIND_ADST} or {@link #KIND_IDENTITY}
     * @param n the size
     * @param input the input values
     * @param output receives the output values
     * @param cosBit the cosine precision
     */
    private void transform1d(int kind, int n, int[] input, int[] output, int cosBit) {
        switch (kind) {
            case KIND_DCT -> fdct(n, input, output, cosBit);
            case KIND_ADST -> fadst(n, input, output, cosBit);
            default -> fidentity(n, input, output);
        }
    }

    /**
     * Applies a one-dimensional identity transform of the given size, 4 to 32
     * ({@code av1_fidentity4_c} to {@code av1_fidentity32_c}).
     *
     * @param n the size
     * @param input the input values
     * @param output receives the output values
     */
    static void fidentity(int n, int[] input, int[] output) {
        var round = 1L << (NEW_SQRT2_BITS - 1);
        switch (n) {
            case 4 -> {
                for (var i = 0; i < 4; i++) {
                    output[i] = (int) (((long) NEW_SQRT2 * input[i] + round) >> NEW_SQRT2_BITS);
                }
            }
            case 8 -> {
                for (var i = 0; i < 8; i++) {
                    output[i] = input[i] * 2;
                }
            }
            case 16 -> {
                for (var i = 0; i < 16; i++) {
                    output[i] = (int) (((long) NEW_SQRT2 * 2 * input[i] + round) >> NEW_SQRT2_BITS);
                }
            }
            default -> {
                for (var i = 0; i < n; i++) {
                    output[i] = input[i] * 4;
                }
            }
        }
    }

    /**
     * Transforms one 4x4 residual block with the lossless Walsh-Hadamard transform
     * ({@code av1_fwht4x4_c}), the inverse of the decoder's {@code WHT_WHT}; the coefficients are
     * four times the transform, so the lossless quantizer step of 4 divides them exactly.
     *
     * @param residual the residual samples
     * @param resOff the offset of the top-left sample
     * @param resStride the distance between rows
     * @param out receives the 16 coefficients in the decoder's layout
     */
    void fwht4x4(int[] residual, int resOff, int resStride, int[] out) {
        var tmp = step;
        for (var i = 0; i < 4; i++) {
            long a1 = residual[resOff + i];
            long b1 = residual[resOff + resStride + i];
            long c1 = residual[resOff + 2 * resStride + i];
            long d1 = residual[resOff + 3 * resStride + i];
            a1 += b1;
            d1 = d1 - c1;
            var e1 = (a1 - d1) >> 1;
            b1 = e1 - b1;
            c1 = e1 - c1;
            a1 -= c1;
            d1 += b1;
            tmp[i] = (int) a1;
            tmp[4 + i] = (int) c1;
            tmp[8 + i] = (int) d1;
            tmp[12 + i] = (int) b1;
        }
        for (var r = 0; r < 4; r++) {
            long a1 = tmp[r * 4];
            long b1 = tmp[r * 4 + 1];
            long c1 = tmp[r * 4 + 2];
            long d1 = tmp[r * 4 + 3];
            a1 += b1;
            d1 -= c1;
            var e1 = (a1 - d1) >> 1;
            b1 = e1 - b1;
            c1 = e1 - c1;
            a1 -= c1;
            d1 += b1;
            out[r] = (int) (a1 * 4);
            out[4 + r] = (int) (c1 * 4);
            out[8 + r] = (int) (d1 * 4);
            out[12 + r] = (int) (b1 * 4);
        }
    }

    /**
     * Applies a one-dimensional ADST of the given size, 4 to 16.
     *
     * @param n the size
     * @param input the input values
     * @param output receives the output values
     * @param cosBit the cosine precision
     */
    private void fadst(int n, int[] input, int[] output, int cosBit) {
        switch (n) {
            case 4 -> fadst4(input, output, cosBit);
            case 8 -> fadst8(input, output, step, cosBit);
            default -> fadst16(input, output, step, cosBit);
        }
    }

    /**
     * Computes a 4-point forward ADST ({@code av1_fadst4}).
     *
     * @param input the input samples
     * @param output the output coefficients
     * @param cosBit the precision of the sine table
     */
    static void fadst4(int[] input, int[] output, int cosBit) {
        var sinpi = SINPI[cosBit - COS_BIT_MIN];
        var x0 = input[0];
        var x1 = input[1];
        var x2 = input[2];
        var x3 = input[3];
        if ((x0 | x1 | x2 | x3) == 0) {
            output[0] = output[1] = output[2] = output[3] = 0;
            return;
        }
        var s0 = sinpi[1] * x0;
        var s1 = sinpi[4] * x0;
        var s2 = sinpi[2] * x1;
        var s3 = sinpi[1] * x1;
        var s4 = sinpi[3] * x2;
        var s5 = sinpi[4] * x3;
        var s6 = sinpi[2] * x3;
        var s7 = x0 + x1;
        s7 = s7 - x3;
        x0 = s0 + s2;
        x1 = sinpi[3] * s7;
        x2 = s1 - s3;
        x3 = s4;
        x0 = x0 + s5;
        x2 = x2 + s6;
        s0 = x0 + x3;
        s1 = x1;
        s2 = x2 - x3;
        s3 = x2 - x0;
        s3 = s3 + x3;
        var add = 1 << (cosBit - 1);
        output[0] = (s0 + add) >> cosBit;
        output[1] = (s1 + add) >> cosBit;
        output[2] = (s2 + add) >> cosBit;
        output[3] = (s3 + add) >> cosBit;
    }

    /**
     * Rounds and shifts an array in place ({@code av1_round_shift_array}): right by {@code bit}
     * with rounding when positive, left when negative.
     *
     * @param arr the array
     * @param size the number of entries
     * @param bit the shift
     */
    private static void roundShift(int[] arr, int size, int bit) {
        if (bit > 0) {
            var add = 1 << (bit - 1);
            for (var i = 0; i < size; i++) {
                arr[i] = (arr[i] + add) >> bit;
            }
        } else if (bit < 0) {
            for (var i = 0; i < size; i++) {
                arr[i] <<= -bit;
            }
        }
    }

    /**
     * Transforms one residual block.
     *
     * @param txSize the transform size, in the decoder's numbering
     * @param txType the transform type: 0 {@code DCT_DCT}; {@link #ADST_DCT},
     * {@link #DCT_ADST}, {@link #ADST_ADST}, {@link #V_DCT}, {@link #H_DCT},
     * {@link #V_ADST} or {@link #H_ADST} for sides up to 16 samples; or
     * {@link #IDTX} for sides up to 32 samples
     * @param residual the residual samples
     * @param resOff the offset of the top-left sample
     * @param resStride the distance between rows
     * @param out receives the coefficients in the decoder's layout
     */
    void forward(int txSize, int txType, int[] residual, int resOff, int resStride, int[] out) {
        var colKind = columnKind(txType);
        var rowKind = rowKind(txType);
        var w = Tables.TX_W[txSize] * 4;
        var h = Tables.TX_H[txSize] * 4;
        var txw = Tables.TX_LW[txSize];
        var txh = Tables.TX_LH[txSize];
        var shift = SHIFT[txSize];
        var cosBitCol = COS_BIT_COL[txw][txh];
        var cosBitRow = COS_BIT_ROW[txw][txh];
        var rect = Math.abs(txw - txh) == 1;
        var in = colIn;
        var tmp = colOut;
        for (var c = 0; c < w; c++) {
            for (int r = 0, p = resOff + c; r < h; r++, p += resStride) {
                in[r] = residual[p];
            }
            roundShift(in, h, -shift[0]);
            transform1d(colKind, h, in, tmp, cosBitCol);
            roundShift(tmp, h, -shift[1]);
            for (var r = 0; r < h; r++) {
                buf[r * w + c] = tmp[r];
            }
        }
        var row = rowOut;
        var crop = w == 64 || h == 64;
        var dst = crop ? full : out;
        for (var r = 0; r < h; r++) {
            System.arraycopy(buf, r * w, in, 0, w);
            transform1d(rowKind, w, in, row, cosBitRow);
            roundShift(row, w, -shift[2]);
            if (rect) {
                for (var c = 0; c < w; c++) {
                    row[c] = (int) (((long) row[c] * NEW_SQRT2 + (1 << (NEW_SQRT2_BITS - 1))) >> NEW_SQRT2_BITS);
                }
            }
            for (var c = 0; c < w; c++) {
                dst[c * h + r] = row[c];
            }
        }
        if (crop) {
            var cw = Math.min(w, 32);
            var ch = Math.min(h, 32);
            for (var c = 0; c < cw; c++) {
                System.arraycopy(full, c * h, out, c * ch, ch);
            }
        }
    }

    /**
     * Computes a 4-point forward DCT ({@code av1_fdct4}).
     *
     * @param input the input samples
     * @param output the output coefficients
     * @param step a scratch array of at least 4 entries
     * @param cosBit the precision of the cosine table
     */
    static void fdct4(int[] input, int[] output, int[] step, int cosBit) {
        var cospi = COSPI[cosBit - COS_BIT_MIN];
        output[0] = input[0] + input[3];
        output[1] = input[1] + input[2];
        output[2] = -input[2] + input[1];
        output[3] = -input[3] + input[0];
        step[0] = halfBtf(cospi[32], output[0], cospi[32], output[1], cosBit);
        step[1] = halfBtf(-cospi[32], output[1], cospi[32], output[0], cosBit);
        step[2] = halfBtf(cospi[48], output[2], cospi[16], output[3], cosBit);
        step[3] = halfBtf(cospi[48], output[3], -cospi[16], output[2], cosBit);
        output[0] = step[0];
        output[1] = step[2];
        output[2] = step[1];
        output[3] = step[3];
    }

    /**
     * Computes a 8-point forward DCT ({@code av1_fdct8}).
     *
     * @param input the input samples
     * @param output the output coefficients
     * @param step a scratch array of at least 8 entries
     * @param cosBit the precision of the cosine table
     */
    static void fdct8(int[] input, int[] output, int[] step, int cosBit) {
        var cospi = COSPI[cosBit - COS_BIT_MIN];
        output[0] = input[0] + input[7];
        output[1] = input[1] + input[6];
        output[2] = input[2] + input[5];
        output[3] = input[3] + input[4];
        output[4] = -input[4] + input[3];
        output[5] = -input[5] + input[2];
        output[6] = -input[6] + input[1];
        output[7] = -input[7] + input[0];
        step[0] = output[0] + output[3];
        step[1] = output[1] + output[2];
        step[2] = -output[2] + output[1];
        step[3] = -output[3] + output[0];
        step[4] = output[4];
        step[5] = halfBtf(-cospi[32], output[5], cospi[32], output[6], cosBit);
        step[6] = halfBtf(cospi[32], output[6], cospi[32], output[5], cosBit);
        step[7] = output[7];
        output[0] = halfBtf(cospi[32], step[0], cospi[32], step[1], cosBit);
        output[1] = halfBtf(-cospi[32], step[1], cospi[32], step[0], cosBit);
        output[2] = halfBtf(cospi[48], step[2], cospi[16], step[3], cosBit);
        output[3] = halfBtf(cospi[48], step[3], -cospi[16], step[2], cosBit);
        output[4] = step[4] + step[5];
        output[5] = -step[5] + step[4];
        output[6] = -step[6] + step[7];
        output[7] = step[7] + step[6];
        step[0] = output[0];
        step[1] = output[1];
        step[2] = output[2];
        step[3] = output[3];
        step[4] = halfBtf(cospi[56], output[4], cospi[8], output[7], cosBit);
        step[5] = halfBtf(cospi[24], output[5], cospi[40], output[6], cosBit);
        step[6] = halfBtf(cospi[24], output[6], -cospi[40], output[5], cosBit);
        step[7] = halfBtf(cospi[56], output[7], -cospi[8], output[4], cosBit);
        output[0] = step[0];
        output[1] = step[4];
        output[2] = step[2];
        output[3] = step[6];
        output[4] = step[1];
        output[5] = step[5];
        output[6] = step[3];
        output[7] = step[7];
    }

    /**
     * Computes a 16-point forward DCT ({@code av1_fdct16}).
     *
     * @param input the input samples
     * @param output the output coefficients
     * @param step a scratch array of at least 16 entries
     * @param cosBit the precision of the cosine table
     */
    static void fdct16(int[] input, int[] output, int[] step, int cosBit) {
        var cospi = COSPI[cosBit - COS_BIT_MIN];
        output[0] = input[0] + input[15];
        output[1] = input[1] + input[14];
        output[2] = input[2] + input[13];
        output[3] = input[3] + input[12];
        output[4] = input[4] + input[11];
        output[5] = input[5] + input[10];
        output[6] = input[6] + input[9];
        output[7] = input[7] + input[8];
        output[8] = -input[8] + input[7];
        output[9] = -input[9] + input[6];
        output[10] = -input[10] + input[5];
        output[11] = -input[11] + input[4];
        output[12] = -input[12] + input[3];
        output[13] = -input[13] + input[2];
        output[14] = -input[14] + input[1];
        output[15] = -input[15] + input[0];
        step[0] = output[0] + output[7];
        step[1] = output[1] + output[6];
        step[2] = output[2] + output[5];
        step[3] = output[3] + output[4];
        step[4] = -output[4] + output[3];
        step[5] = -output[5] + output[2];
        step[6] = -output[6] + output[1];
        step[7] = -output[7] + output[0];
        step[8] = output[8];
        step[9] = output[9];
        step[10] = halfBtf(-cospi[32], output[10], cospi[32], output[13], cosBit);
        step[11] = halfBtf(-cospi[32], output[11], cospi[32], output[12], cosBit);
        step[12] = halfBtf(cospi[32], output[12], cospi[32], output[11], cosBit);
        step[13] = halfBtf(cospi[32], output[13], cospi[32], output[10], cosBit);
        step[14] = output[14];
        step[15] = output[15];
        output[0] = step[0] + step[3];
        output[1] = step[1] + step[2];
        output[2] = -step[2] + step[1];
        output[3] = -step[3] + step[0];
        output[4] = step[4];
        output[5] = halfBtf(-cospi[32], step[5], cospi[32], step[6], cosBit);
        output[6] = halfBtf(cospi[32], step[6], cospi[32], step[5], cosBit);
        output[7] = step[7];
        output[8] = step[8] + step[11];
        output[9] = step[9] + step[10];
        output[10] = -step[10] + step[9];
        output[11] = -step[11] + step[8];
        output[12] = -step[12] + step[15];
        output[13] = -step[13] + step[14];
        output[14] = step[14] + step[13];
        output[15] = step[15] + step[12];
        step[0] = halfBtf(cospi[32], output[0], cospi[32], output[1], cosBit);
        step[1] = halfBtf(-cospi[32], output[1], cospi[32], output[0], cosBit);
        step[2] = halfBtf(cospi[48], output[2], cospi[16], output[3], cosBit);
        step[3] = halfBtf(cospi[48], output[3], -cospi[16], output[2], cosBit);
        step[4] = output[4] + output[5];
        step[5] = -output[5] + output[4];
        step[6] = -output[6] + output[7];
        step[7] = output[7] + output[6];
        step[8] = output[8];
        step[9] = halfBtf(-cospi[16], output[9], cospi[48], output[14], cosBit);
        step[10] = halfBtf(-cospi[48], output[10], -cospi[16], output[13], cosBit);
        step[11] = output[11];
        step[12] = output[12];
        step[13] = halfBtf(cospi[48], output[13], -cospi[16], output[10], cosBit);
        step[14] = halfBtf(cospi[16], output[14], cospi[48], output[9], cosBit);
        step[15] = output[15];
        output[0] = step[0];
        output[1] = step[1];
        output[2] = step[2];
        output[3] = step[3];
        output[4] = halfBtf(cospi[56], step[4], cospi[8], step[7], cosBit);
        output[5] = halfBtf(cospi[24], step[5], cospi[40], step[6], cosBit);
        output[6] = halfBtf(cospi[24], step[6], -cospi[40], step[5], cosBit);
        output[7] = halfBtf(cospi[56], step[7], -cospi[8], step[4], cosBit);
        output[8] = step[8] + step[9];
        output[9] = -step[9] + step[8];
        output[10] = -step[10] + step[11];
        output[11] = step[11] + step[10];
        output[12] = step[12] + step[13];
        output[13] = -step[13] + step[12];
        output[14] = -step[14] + step[15];
        output[15] = step[15] + step[14];
        step[0] = output[0];
        step[1] = output[1];
        step[2] = output[2];
        step[3] = output[3];
        step[4] = output[4];
        step[5] = output[5];
        step[6] = output[6];
        step[7] = output[7];
        step[8] = halfBtf(cospi[60], output[8], cospi[4], output[15], cosBit);
        step[9] = halfBtf(cospi[28], output[9], cospi[36], output[14], cosBit);
        step[10] = halfBtf(cospi[44], output[10], cospi[20], output[13], cosBit);
        step[11] = halfBtf(cospi[12], output[11], cospi[52], output[12], cosBit);
        step[12] = halfBtf(cospi[12], output[12], -cospi[52], output[11], cosBit);
        step[13] = halfBtf(cospi[44], output[13], -cospi[20], output[10], cosBit);
        step[14] = halfBtf(cospi[28], output[14], -cospi[36], output[9], cosBit);
        step[15] = halfBtf(cospi[60], output[15], -cospi[4], output[8], cosBit);
        output[0] = step[0];
        output[1] = step[8];
        output[2] = step[4];
        output[3] = step[12];
        output[4] = step[2];
        output[5] = step[10];
        output[6] = step[6];
        output[7] = step[14];
        output[8] = step[1];
        output[9] = step[9];
        output[10] = step[5];
        output[11] = step[13];
        output[12] = step[3];
        output[13] = step[11];
        output[14] = step[7];
        output[15] = step[15];
    }

    /**
     * Computes a 32-point forward DCT ({@code av1_fdct32}), one method per butterfly stage so
     * every method stays small enough for the JIT compiler.
     *
     * @param input the input samples
     * @param output the output coefficients
     * @param step a scratch array of at least 32 entries
     * @param cosBit the precision of the cosine table
     */
    static void fdct32(int[] input, int[] output, int[] step, int cosBit) {
        var cospi = COSPI[cosBit - COS_BIT_MIN];
        fdct32Stage1(input, output, cospi, cosBit);
        fdct32Stage2(output, step, cospi, cosBit);
        fdct32Stage3(step, output, cospi, cosBit);
        fdct32Stage4(output, step, cospi, cosBit);
        fdct32Stage5(step, output, cospi, cosBit);
        fdct32Stage6(output, step, cospi, cosBit);
        fdct32Stage7(step, output, cospi, cosBit);
        fdct32Stage8(output, step, cospi, cosBit);
        fdct32Stage9(step, output, cospi, cosBit);
    }

    /**
     * Computes stage 1 of {@link #fdct32(int[], int[], int[], int)}.
     *
     * @param bf0 the stage input
     * @param bf1 the stage output
     * @param cospi the cosine table
     * @param cosBit the precision of the cosine table
     */
    private static void fdct32Stage1(int[] bf0, int[] bf1, int[] cospi, int cosBit) {
        bf1[0] = bf0[0] + bf0[31];
        bf1[1] = bf0[1] + bf0[30];
        bf1[2] = bf0[2] + bf0[29];
        bf1[3] = bf0[3] + bf0[28];
        bf1[4] = bf0[4] + bf0[27];
        bf1[5] = bf0[5] + bf0[26];
        bf1[6] = bf0[6] + bf0[25];
        bf1[7] = bf0[7] + bf0[24];
        bf1[8] = bf0[8] + bf0[23];
        bf1[9] = bf0[9] + bf0[22];
        bf1[10] = bf0[10] + bf0[21];
        bf1[11] = bf0[11] + bf0[20];
        bf1[12] = bf0[12] + bf0[19];
        bf1[13] = bf0[13] + bf0[18];
        bf1[14] = bf0[14] + bf0[17];
        bf1[15] = bf0[15] + bf0[16];
        bf1[16] = -bf0[16] + bf0[15];
        bf1[17] = -bf0[17] + bf0[14];
        bf1[18] = -bf0[18] + bf0[13];
        bf1[19] = -bf0[19] + bf0[12];
        bf1[20] = -bf0[20] + bf0[11];
        bf1[21] = -bf0[21] + bf0[10];
        bf1[22] = -bf0[22] + bf0[9];
        bf1[23] = -bf0[23] + bf0[8];
        bf1[24] = -bf0[24] + bf0[7];
        bf1[25] = -bf0[25] + bf0[6];
        bf1[26] = -bf0[26] + bf0[5];
        bf1[27] = -bf0[27] + bf0[4];
        bf1[28] = -bf0[28] + bf0[3];
        bf1[29] = -bf0[29] + bf0[2];
        bf1[30] = -bf0[30] + bf0[1];
        bf1[31] = -bf0[31] + bf0[0];
    }

    /**
     * Computes stage 2 of {@link #fdct32(int[], int[], int[], int)}.
     *
     * @param bf0 the stage input
     * @param bf1 the stage output
     * @param cospi the cosine table
     * @param cosBit the precision of the cosine table
     */
    private static void fdct32Stage2(int[] bf0, int[] bf1, int[] cospi, int cosBit) {
        bf1[0] = bf0[0] + bf0[15];
        bf1[1] = bf0[1] + bf0[14];
        bf1[2] = bf0[2] + bf0[13];
        bf1[3] = bf0[3] + bf0[12];
        bf1[4] = bf0[4] + bf0[11];
        bf1[5] = bf0[5] + bf0[10];
        bf1[6] = bf0[6] + bf0[9];
        bf1[7] = bf0[7] + bf0[8];
        bf1[8] = -bf0[8] + bf0[7];
        bf1[9] = -bf0[9] + bf0[6];
        bf1[10] = -bf0[10] + bf0[5];
        bf1[11] = -bf0[11] + bf0[4];
        bf1[12] = -bf0[12] + bf0[3];
        bf1[13] = -bf0[13] + bf0[2];
        bf1[14] = -bf0[14] + bf0[1];
        bf1[15] = -bf0[15] + bf0[0];
        bf1[16] = bf0[16];
        bf1[17] = bf0[17];
        bf1[18] = bf0[18];
        bf1[19] = bf0[19];
        bf1[20] = halfBtf(-cospi[32], bf0[20], cospi[32], bf0[27], cosBit);
        bf1[21] = halfBtf(-cospi[32], bf0[21], cospi[32], bf0[26], cosBit);
        bf1[22] = halfBtf(-cospi[32], bf0[22], cospi[32], bf0[25], cosBit);
        bf1[23] = halfBtf(-cospi[32], bf0[23], cospi[32], bf0[24], cosBit);
        bf1[24] = halfBtf(cospi[32], bf0[24], cospi[32], bf0[23], cosBit);
        bf1[25] = halfBtf(cospi[32], bf0[25], cospi[32], bf0[22], cosBit);
        bf1[26] = halfBtf(cospi[32], bf0[26], cospi[32], bf0[21], cosBit);
        bf1[27] = halfBtf(cospi[32], bf0[27], cospi[32], bf0[20], cosBit);
        bf1[28] = bf0[28];
        bf1[29] = bf0[29];
        bf1[30] = bf0[30];
        bf1[31] = bf0[31];
    }

    /**
     * Computes stage 3 of {@link #fdct32(int[], int[], int[], int)}.
     *
     * @param bf0 the stage input
     * @param bf1 the stage output
     * @param cospi the cosine table
     * @param cosBit the precision of the cosine table
     */
    private static void fdct32Stage3(int[] bf0, int[] bf1, int[] cospi, int cosBit) {
        bf1[0] = bf0[0] + bf0[7];
        bf1[1] = bf0[1] + bf0[6];
        bf1[2] = bf0[2] + bf0[5];
        bf1[3] = bf0[3] + bf0[4];
        bf1[4] = -bf0[4] + bf0[3];
        bf1[5] = -bf0[5] + bf0[2];
        bf1[6] = -bf0[6] + bf0[1];
        bf1[7] = -bf0[7] + bf0[0];
        bf1[8] = bf0[8];
        bf1[9] = bf0[9];
        bf1[10] = halfBtf(-cospi[32], bf0[10], cospi[32], bf0[13], cosBit);
        bf1[11] = halfBtf(-cospi[32], bf0[11], cospi[32], bf0[12], cosBit);
        bf1[12] = halfBtf(cospi[32], bf0[12], cospi[32], bf0[11], cosBit);
        bf1[13] = halfBtf(cospi[32], bf0[13], cospi[32], bf0[10], cosBit);
        bf1[14] = bf0[14];
        bf1[15] = bf0[15];
        bf1[16] = bf0[16] + bf0[23];
        bf1[17] = bf0[17] + bf0[22];
        bf1[18] = bf0[18] + bf0[21];
        bf1[19] = bf0[19] + bf0[20];
        bf1[20] = -bf0[20] + bf0[19];
        bf1[21] = -bf0[21] + bf0[18];
        bf1[22] = -bf0[22] + bf0[17];
        bf1[23] = -bf0[23] + bf0[16];
        bf1[24] = -bf0[24] + bf0[31];
        bf1[25] = -bf0[25] + bf0[30];
        bf1[26] = -bf0[26] + bf0[29];
        bf1[27] = -bf0[27] + bf0[28];
        bf1[28] = bf0[28] + bf0[27];
        bf1[29] = bf0[29] + bf0[26];
        bf1[30] = bf0[30] + bf0[25];
        bf1[31] = bf0[31] + bf0[24];
    }

    /**
     * Computes stage 4 of {@link #fdct32(int[], int[], int[], int)}.
     *
     * @param bf0 the stage input
     * @param bf1 the stage output
     * @param cospi the cosine table
     * @param cosBit the precision of the cosine table
     */
    private static void fdct32Stage4(int[] bf0, int[] bf1, int[] cospi, int cosBit) {
        bf1[0] = bf0[0] + bf0[3];
        bf1[1] = bf0[1] + bf0[2];
        bf1[2] = -bf0[2] + bf0[1];
        bf1[3] = -bf0[3] + bf0[0];
        bf1[4] = bf0[4];
        bf1[5] = halfBtf(-cospi[32], bf0[5], cospi[32], bf0[6], cosBit);
        bf1[6] = halfBtf(cospi[32], bf0[6], cospi[32], bf0[5], cosBit);
        bf1[7] = bf0[7];
        bf1[8] = bf0[8] + bf0[11];
        bf1[9] = bf0[9] + bf0[10];
        bf1[10] = -bf0[10] + bf0[9];
        bf1[11] = -bf0[11] + bf0[8];
        bf1[12] = -bf0[12] + bf0[15];
        bf1[13] = -bf0[13] + bf0[14];
        bf1[14] = bf0[14] + bf0[13];
        bf1[15] = bf0[15] + bf0[12];
        bf1[16] = bf0[16];
        bf1[17] = bf0[17];
        bf1[18] = halfBtf(-cospi[16], bf0[18], cospi[48], bf0[29], cosBit);
        bf1[19] = halfBtf(-cospi[16], bf0[19], cospi[48], bf0[28], cosBit);
        bf1[20] = halfBtf(-cospi[48], bf0[20], -cospi[16], bf0[27], cosBit);
        bf1[21] = halfBtf(-cospi[48], bf0[21], -cospi[16], bf0[26], cosBit);
        bf1[22] = bf0[22];
        bf1[23] = bf0[23];
        bf1[24] = bf0[24];
        bf1[25] = bf0[25];
        bf1[26] = halfBtf(cospi[48], bf0[26], -cospi[16], bf0[21], cosBit);
        bf1[27] = halfBtf(cospi[48], bf0[27], -cospi[16], bf0[20], cosBit);
        bf1[28] = halfBtf(cospi[16], bf0[28], cospi[48], bf0[19], cosBit);
        bf1[29] = halfBtf(cospi[16], bf0[29], cospi[48], bf0[18], cosBit);
        bf1[30] = bf0[30];
        bf1[31] = bf0[31];
    }

    /**
     * Computes stage 5 of {@link #fdct32(int[], int[], int[], int)}.
     *
     * @param bf0 the stage input
     * @param bf1 the stage output
     * @param cospi the cosine table
     * @param cosBit the precision of the cosine table
     */
    private static void fdct32Stage5(int[] bf0, int[] bf1, int[] cospi, int cosBit) {
        bf1[0] = halfBtf(cospi[32], bf0[0], cospi[32], bf0[1], cosBit);
        bf1[1] = halfBtf(-cospi[32], bf0[1], cospi[32], bf0[0], cosBit);
        bf1[2] = halfBtf(cospi[48], bf0[2], cospi[16], bf0[3], cosBit);
        bf1[3] = halfBtf(cospi[48], bf0[3], -cospi[16], bf0[2], cosBit);
        bf1[4] = bf0[4] + bf0[5];
        bf1[5] = -bf0[5] + bf0[4];
        bf1[6] = -bf0[6] + bf0[7];
        bf1[7] = bf0[7] + bf0[6];
        bf1[8] = bf0[8];
        bf1[9] = halfBtf(-cospi[16], bf0[9], cospi[48], bf0[14], cosBit);
        bf1[10] = halfBtf(-cospi[48], bf0[10], -cospi[16], bf0[13], cosBit);
        bf1[11] = bf0[11];
        bf1[12] = bf0[12];
        bf1[13] = halfBtf(cospi[48], bf0[13], -cospi[16], bf0[10], cosBit);
        bf1[14] = halfBtf(cospi[16], bf0[14], cospi[48], bf0[9], cosBit);
        bf1[15] = bf0[15];
        bf1[16] = bf0[16] + bf0[19];
        bf1[17] = bf0[17] + bf0[18];
        bf1[18] = -bf0[18] + bf0[17];
        bf1[19] = -bf0[19] + bf0[16];
        bf1[20] = -bf0[20] + bf0[23];
        bf1[21] = -bf0[21] + bf0[22];
        bf1[22] = bf0[22] + bf0[21];
        bf1[23] = bf0[23] + bf0[20];
        bf1[24] = bf0[24] + bf0[27];
        bf1[25] = bf0[25] + bf0[26];
        bf1[26] = -bf0[26] + bf0[25];
        bf1[27] = -bf0[27] + bf0[24];
        bf1[28] = -bf0[28] + bf0[31];
        bf1[29] = -bf0[29] + bf0[30];
        bf1[30] = bf0[30] + bf0[29];
        bf1[31] = bf0[31] + bf0[28];
    }

    /**
     * Computes stage 6 of {@link #fdct32(int[], int[], int[], int)}.
     *
     * @param bf0 the stage input
     * @param bf1 the stage output
     * @param cospi the cosine table
     * @param cosBit the precision of the cosine table
     */
    private static void fdct32Stage6(int[] bf0, int[] bf1, int[] cospi, int cosBit) {
        bf1[0] = bf0[0];
        bf1[1] = bf0[1];
        bf1[2] = bf0[2];
        bf1[3] = bf0[3];
        bf1[4] = halfBtf(cospi[56], bf0[4], cospi[8], bf0[7], cosBit);
        bf1[5] = halfBtf(cospi[24], bf0[5], cospi[40], bf0[6], cosBit);
        bf1[6] = halfBtf(cospi[24], bf0[6], -cospi[40], bf0[5], cosBit);
        bf1[7] = halfBtf(cospi[56], bf0[7], -cospi[8], bf0[4], cosBit);
        bf1[8] = bf0[8] + bf0[9];
        bf1[9] = -bf0[9] + bf0[8];
        bf1[10] = -bf0[10] + bf0[11];
        bf1[11] = bf0[11] + bf0[10];
        bf1[12] = bf0[12] + bf0[13];
        bf1[13] = -bf0[13] + bf0[12];
        bf1[14] = -bf0[14] + bf0[15];
        bf1[15] = bf0[15] + bf0[14];
        bf1[16] = bf0[16];
        bf1[17] = halfBtf(-cospi[8], bf0[17], cospi[56], bf0[30], cosBit);
        bf1[18] = halfBtf(-cospi[56], bf0[18], -cospi[8], bf0[29], cosBit);
        bf1[19] = bf0[19];
        bf1[20] = bf0[20];
        bf1[21] = halfBtf(-cospi[40], bf0[21], cospi[24], bf0[26], cosBit);
        bf1[22] = halfBtf(-cospi[24], bf0[22], -cospi[40], bf0[25], cosBit);
        bf1[23] = bf0[23];
        bf1[24] = bf0[24];
        bf1[25] = halfBtf(cospi[24], bf0[25], -cospi[40], bf0[22], cosBit);
        bf1[26] = halfBtf(cospi[40], bf0[26], cospi[24], bf0[21], cosBit);
        bf1[27] = bf0[27];
        bf1[28] = bf0[28];
        bf1[29] = halfBtf(cospi[56], bf0[29], -cospi[8], bf0[18], cosBit);
        bf1[30] = halfBtf(cospi[8], bf0[30], cospi[56], bf0[17], cosBit);
        bf1[31] = bf0[31];
    }

    /**
     * Computes stage 7 of {@link #fdct32(int[], int[], int[], int)}.
     *
     * @param bf0 the stage input
     * @param bf1 the stage output
     * @param cospi the cosine table
     * @param cosBit the precision of the cosine table
     */
    private static void fdct32Stage7(int[] bf0, int[] bf1, int[] cospi, int cosBit) {
        bf1[0] = bf0[0];
        bf1[1] = bf0[1];
        bf1[2] = bf0[2];
        bf1[3] = bf0[3];
        bf1[4] = bf0[4];
        bf1[5] = bf0[5];
        bf1[6] = bf0[6];
        bf1[7] = bf0[7];
        bf1[8] = halfBtf(cospi[60], bf0[8], cospi[4], bf0[15], cosBit);
        bf1[9] = halfBtf(cospi[28], bf0[9], cospi[36], bf0[14], cosBit);
        bf1[10] = halfBtf(cospi[44], bf0[10], cospi[20], bf0[13], cosBit);
        bf1[11] = halfBtf(cospi[12], bf0[11], cospi[52], bf0[12], cosBit);
        bf1[12] = halfBtf(cospi[12], bf0[12], -cospi[52], bf0[11], cosBit);
        bf1[13] = halfBtf(cospi[44], bf0[13], -cospi[20], bf0[10], cosBit);
        bf1[14] = halfBtf(cospi[28], bf0[14], -cospi[36], bf0[9], cosBit);
        bf1[15] = halfBtf(cospi[60], bf0[15], -cospi[4], bf0[8], cosBit);
        bf1[16] = bf0[16] + bf0[17];
        bf1[17] = -bf0[17] + bf0[16];
        bf1[18] = -bf0[18] + bf0[19];
        bf1[19] = bf0[19] + bf0[18];
        bf1[20] = bf0[20] + bf0[21];
        bf1[21] = -bf0[21] + bf0[20];
        bf1[22] = -bf0[22] + bf0[23];
        bf1[23] = bf0[23] + bf0[22];
        bf1[24] = bf0[24] + bf0[25];
        bf1[25] = -bf0[25] + bf0[24];
        bf1[26] = -bf0[26] + bf0[27];
        bf1[27] = bf0[27] + bf0[26];
        bf1[28] = bf0[28] + bf0[29];
        bf1[29] = -bf0[29] + bf0[28];
        bf1[30] = -bf0[30] + bf0[31];
        bf1[31] = bf0[31] + bf0[30];
    }

    /**
     * Computes stage 8 of {@link #fdct32(int[], int[], int[], int)}.
     *
     * @param bf0 the stage input
     * @param bf1 the stage output
     * @param cospi the cosine table
     * @param cosBit the precision of the cosine table
     */
    private static void fdct32Stage8(int[] bf0, int[] bf1, int[] cospi, int cosBit) {
        bf1[0] = bf0[0];
        bf1[1] = bf0[1];
        bf1[2] = bf0[2];
        bf1[3] = bf0[3];
        bf1[4] = bf0[4];
        bf1[5] = bf0[5];
        bf1[6] = bf0[6];
        bf1[7] = bf0[7];
        bf1[8] = bf0[8];
        bf1[9] = bf0[9];
        bf1[10] = bf0[10];
        bf1[11] = bf0[11];
        bf1[12] = bf0[12];
        bf1[13] = bf0[13];
        bf1[14] = bf0[14];
        bf1[15] = bf0[15];
        bf1[16] = halfBtf(cospi[62], bf0[16], cospi[2], bf0[31], cosBit);
        bf1[17] = halfBtf(cospi[30], bf0[17], cospi[34], bf0[30], cosBit);
        bf1[18] = halfBtf(cospi[46], bf0[18], cospi[18], bf0[29], cosBit);
        bf1[19] = halfBtf(cospi[14], bf0[19], cospi[50], bf0[28], cosBit);
        bf1[20] = halfBtf(cospi[54], bf0[20], cospi[10], bf0[27], cosBit);
        bf1[21] = halfBtf(cospi[22], bf0[21], cospi[42], bf0[26], cosBit);
        bf1[22] = halfBtf(cospi[38], bf0[22], cospi[26], bf0[25], cosBit);
        bf1[23] = halfBtf(cospi[6], bf0[23], cospi[58], bf0[24], cosBit);
        bf1[24] = halfBtf(cospi[6], bf0[24], -cospi[58], bf0[23], cosBit);
        bf1[25] = halfBtf(cospi[38], bf0[25], -cospi[26], bf0[22], cosBit);
        bf1[26] = halfBtf(cospi[22], bf0[26], -cospi[42], bf0[21], cosBit);
        bf1[27] = halfBtf(cospi[54], bf0[27], -cospi[10], bf0[20], cosBit);
        bf1[28] = halfBtf(cospi[14], bf0[28], -cospi[50], bf0[19], cosBit);
        bf1[29] = halfBtf(cospi[46], bf0[29], -cospi[18], bf0[18], cosBit);
        bf1[30] = halfBtf(cospi[30], bf0[30], -cospi[34], bf0[17], cosBit);
        bf1[31] = halfBtf(cospi[62], bf0[31], -cospi[2], bf0[16], cosBit);
    }

    /**
     * Computes stage 9 of {@link #fdct32(int[], int[], int[], int)}.
     *
     * @param bf0 the stage input
     * @param bf1 the stage output
     * @param cospi the cosine table
     * @param cosBit the precision of the cosine table
     */
    private static void fdct32Stage9(int[] bf0, int[] bf1, int[] cospi, int cosBit) {
        bf1[0] = bf0[0];
        bf1[1] = bf0[16];
        bf1[2] = bf0[8];
        bf1[3] = bf0[24];
        bf1[4] = bf0[4];
        bf1[5] = bf0[20];
        bf1[6] = bf0[12];
        bf1[7] = bf0[28];
        bf1[8] = bf0[2];
        bf1[9] = bf0[18];
        bf1[10] = bf0[10];
        bf1[11] = bf0[26];
        bf1[12] = bf0[6];
        bf1[13] = bf0[22];
        bf1[14] = bf0[14];
        bf1[15] = bf0[30];
        bf1[16] = bf0[1];
        bf1[17] = bf0[17];
        bf1[18] = bf0[9];
        bf1[19] = bf0[25];
        bf1[20] = bf0[5];
        bf1[21] = bf0[21];
        bf1[22] = bf0[13];
        bf1[23] = bf0[29];
        bf1[24] = bf0[3];
        bf1[25] = bf0[19];
        bf1[26] = bf0[11];
        bf1[27] = bf0[27];
        bf1[28] = bf0[7];
        bf1[29] = bf0[23];
        bf1[30] = bf0[15];
        bf1[31] = bf0[31];
    }

    /**
     * Computes a 64-point forward DCT ({@code av1_fdct64}), one method per butterfly stage so
     * every method stays small enough for the JIT compiler.
     *
     * @param input the input samples
     * @param output the output coefficients
     * @param step a scratch array of at least 64 entries
     * @param cosBit the precision of the cosine table
     */
    static void fdct64(int[] input, int[] output, int[] step, int cosBit) {
        var cospi = COSPI[cosBit - COS_BIT_MIN];
        fdct64Stage1(input, output, cospi, cosBit);
        fdct64Stage2(output, step, cospi, cosBit);
        fdct64Stage3(step, output, cospi, cosBit);
        fdct64Stage4(output, step, cospi, cosBit);
        fdct64Stage5(step, output, cospi, cosBit);
        fdct64Stage6(output, step, cospi, cosBit);
        fdct64Stage7(step, output, cospi, cosBit);
        fdct64Stage8(output, step, cospi, cosBit);
        fdct64Stage9(step, output, cospi, cosBit);
        fdct64Stage10(output, step, cospi, cosBit);
        fdct64Stage11(step, output, cospi, cosBit);
    }

    /**
     * Computes stage 1 of {@link #fdct64(int[], int[], int[], int)}.
     *
     * @param bf0 the stage input
     * @param bf1 the stage output
     * @param cospi the cosine table
     * @param cosBit the precision of the cosine table
     */
    private static void fdct64Stage1(int[] bf0, int[] bf1, int[] cospi, int cosBit) {
        bf1[0] = bf0[0] + bf0[63];
        bf1[1] = bf0[1] + bf0[62];
        bf1[2] = bf0[2] + bf0[61];
        bf1[3] = bf0[3] + bf0[60];
        bf1[4] = bf0[4] + bf0[59];
        bf1[5] = bf0[5] + bf0[58];
        bf1[6] = bf0[6] + bf0[57];
        bf1[7] = bf0[7] + bf0[56];
        bf1[8] = bf0[8] + bf0[55];
        bf1[9] = bf0[9] + bf0[54];
        bf1[10] = bf0[10] + bf0[53];
        bf1[11] = bf0[11] + bf0[52];
        bf1[12] = bf0[12] + bf0[51];
        bf1[13] = bf0[13] + bf0[50];
        bf1[14] = bf0[14] + bf0[49];
        bf1[15] = bf0[15] + bf0[48];
        bf1[16] = bf0[16] + bf0[47];
        bf1[17] = bf0[17] + bf0[46];
        bf1[18] = bf0[18] + bf0[45];
        bf1[19] = bf0[19] + bf0[44];
        bf1[20] = bf0[20] + bf0[43];
        bf1[21] = bf0[21] + bf0[42];
        bf1[22] = bf0[22] + bf0[41];
        bf1[23] = bf0[23] + bf0[40];
        bf1[24] = bf0[24] + bf0[39];
        bf1[25] = bf0[25] + bf0[38];
        bf1[26] = bf0[26] + bf0[37];
        bf1[27] = bf0[27] + bf0[36];
        bf1[28] = bf0[28] + bf0[35];
        bf1[29] = bf0[29] + bf0[34];
        bf1[30] = bf0[30] + bf0[33];
        bf1[31] = bf0[31] + bf0[32];
        bf1[32] = -bf0[32] + bf0[31];
        bf1[33] = -bf0[33] + bf0[30];
        bf1[34] = -bf0[34] + bf0[29];
        bf1[35] = -bf0[35] + bf0[28];
        bf1[36] = -bf0[36] + bf0[27];
        bf1[37] = -bf0[37] + bf0[26];
        bf1[38] = -bf0[38] + bf0[25];
        bf1[39] = -bf0[39] + bf0[24];
        bf1[40] = -bf0[40] + bf0[23];
        bf1[41] = -bf0[41] + bf0[22];
        bf1[42] = -bf0[42] + bf0[21];
        bf1[43] = -bf0[43] + bf0[20];
        bf1[44] = -bf0[44] + bf0[19];
        bf1[45] = -bf0[45] + bf0[18];
        bf1[46] = -bf0[46] + bf0[17];
        bf1[47] = -bf0[47] + bf0[16];
        bf1[48] = -bf0[48] + bf0[15];
        bf1[49] = -bf0[49] + bf0[14];
        bf1[50] = -bf0[50] + bf0[13];
        bf1[51] = -bf0[51] + bf0[12];
        bf1[52] = -bf0[52] + bf0[11];
        bf1[53] = -bf0[53] + bf0[10];
        bf1[54] = -bf0[54] + bf0[9];
        bf1[55] = -bf0[55] + bf0[8];
        bf1[56] = -bf0[56] + bf0[7];
        bf1[57] = -bf0[57] + bf0[6];
        bf1[58] = -bf0[58] + bf0[5];
        bf1[59] = -bf0[59] + bf0[4];
        bf1[60] = -bf0[60] + bf0[3];
        bf1[61] = -bf0[61] + bf0[2];
        bf1[62] = -bf0[62] + bf0[1];
        bf1[63] = -bf0[63] + bf0[0];
    }

    /**
     * Computes stage 2 of {@link #fdct64(int[], int[], int[], int)}.
     *
     * @param bf0 the stage input
     * @param bf1 the stage output
     * @param cospi the cosine table
     * @param cosBit the precision of the cosine table
     */
    private static void fdct64Stage2(int[] bf0, int[] bf1, int[] cospi, int cosBit) {
        bf1[0] = bf0[0] + bf0[31];
        bf1[1] = bf0[1] + bf0[30];
        bf1[2] = bf0[2] + bf0[29];
        bf1[3] = bf0[3] + bf0[28];
        bf1[4] = bf0[4] + bf0[27];
        bf1[5] = bf0[5] + bf0[26];
        bf1[6] = bf0[6] + bf0[25];
        bf1[7] = bf0[7] + bf0[24];
        bf1[8] = bf0[8] + bf0[23];
        bf1[9] = bf0[9] + bf0[22];
        bf1[10] = bf0[10] + bf0[21];
        bf1[11] = bf0[11] + bf0[20];
        bf1[12] = bf0[12] + bf0[19];
        bf1[13] = bf0[13] + bf0[18];
        bf1[14] = bf0[14] + bf0[17];
        bf1[15] = bf0[15] + bf0[16];
        bf1[16] = -bf0[16] + bf0[15];
        bf1[17] = -bf0[17] + bf0[14];
        bf1[18] = -bf0[18] + bf0[13];
        bf1[19] = -bf0[19] + bf0[12];
        bf1[20] = -bf0[20] + bf0[11];
        bf1[21] = -bf0[21] + bf0[10];
        bf1[22] = -bf0[22] + bf0[9];
        bf1[23] = -bf0[23] + bf0[8];
        bf1[24] = -bf0[24] + bf0[7];
        bf1[25] = -bf0[25] + bf0[6];
        bf1[26] = -bf0[26] + bf0[5];
        bf1[27] = -bf0[27] + bf0[4];
        bf1[28] = -bf0[28] + bf0[3];
        bf1[29] = -bf0[29] + bf0[2];
        bf1[30] = -bf0[30] + bf0[1];
        bf1[31] = -bf0[31] + bf0[0];
        bf1[32] = bf0[32];
        bf1[33] = bf0[33];
        bf1[34] = bf0[34];
        bf1[35] = bf0[35];
        bf1[36] = bf0[36];
        bf1[37] = bf0[37];
        bf1[38] = bf0[38];
        bf1[39] = bf0[39];
        bf1[40] = halfBtf(-cospi[32], bf0[40], cospi[32], bf0[55], cosBit);
        bf1[41] = halfBtf(-cospi[32], bf0[41], cospi[32], bf0[54], cosBit);
        bf1[42] = halfBtf(-cospi[32], bf0[42], cospi[32], bf0[53], cosBit);
        bf1[43] = halfBtf(-cospi[32], bf0[43], cospi[32], bf0[52], cosBit);
        bf1[44] = halfBtf(-cospi[32], bf0[44], cospi[32], bf0[51], cosBit);
        bf1[45] = halfBtf(-cospi[32], bf0[45], cospi[32], bf0[50], cosBit);
        bf1[46] = halfBtf(-cospi[32], bf0[46], cospi[32], bf0[49], cosBit);
        bf1[47] = halfBtf(-cospi[32], bf0[47], cospi[32], bf0[48], cosBit);
        bf1[48] = halfBtf(cospi[32], bf0[48], cospi[32], bf0[47], cosBit);
        bf1[49] = halfBtf(cospi[32], bf0[49], cospi[32], bf0[46], cosBit);
        bf1[50] = halfBtf(cospi[32], bf0[50], cospi[32], bf0[45], cosBit);
        bf1[51] = halfBtf(cospi[32], bf0[51], cospi[32], bf0[44], cosBit);
        bf1[52] = halfBtf(cospi[32], bf0[52], cospi[32], bf0[43], cosBit);
        bf1[53] = halfBtf(cospi[32], bf0[53], cospi[32], bf0[42], cosBit);
        bf1[54] = halfBtf(cospi[32], bf0[54], cospi[32], bf0[41], cosBit);
        bf1[55] = halfBtf(cospi[32], bf0[55], cospi[32], bf0[40], cosBit);
        bf1[56] = bf0[56];
        bf1[57] = bf0[57];
        bf1[58] = bf0[58];
        bf1[59] = bf0[59];
        bf1[60] = bf0[60];
        bf1[61] = bf0[61];
        bf1[62] = bf0[62];
        bf1[63] = bf0[63];
    }

    /**
     * Computes stage 3 of {@link #fdct64(int[], int[], int[], int)}.
     *
     * @param bf0 the stage input
     * @param bf1 the stage output
     * @param cospi the cosine table
     * @param cosBit the precision of the cosine table
     */
    private static void fdct64Stage3(int[] bf0, int[] bf1, int[] cospi, int cosBit) {
        bf1[0] = bf0[0] + bf0[15];
        bf1[1] = bf0[1] + bf0[14];
        bf1[2] = bf0[2] + bf0[13];
        bf1[3] = bf0[3] + bf0[12];
        bf1[4] = bf0[4] + bf0[11];
        bf1[5] = bf0[5] + bf0[10];
        bf1[6] = bf0[6] + bf0[9];
        bf1[7] = bf0[7] + bf0[8];
        bf1[8] = -bf0[8] + bf0[7];
        bf1[9] = -bf0[9] + bf0[6];
        bf1[10] = -bf0[10] + bf0[5];
        bf1[11] = -bf0[11] + bf0[4];
        bf1[12] = -bf0[12] + bf0[3];
        bf1[13] = -bf0[13] + bf0[2];
        bf1[14] = -bf0[14] + bf0[1];
        bf1[15] = -bf0[15] + bf0[0];
        bf1[16] = bf0[16];
        bf1[17] = bf0[17];
        bf1[18] = bf0[18];
        bf1[19] = bf0[19];
        bf1[20] = halfBtf(-cospi[32], bf0[20], cospi[32], bf0[27], cosBit);
        bf1[21] = halfBtf(-cospi[32], bf0[21], cospi[32], bf0[26], cosBit);
        bf1[22] = halfBtf(-cospi[32], bf0[22], cospi[32], bf0[25], cosBit);
        bf1[23] = halfBtf(-cospi[32], bf0[23], cospi[32], bf0[24], cosBit);
        bf1[24] = halfBtf(cospi[32], bf0[24], cospi[32], bf0[23], cosBit);
        bf1[25] = halfBtf(cospi[32], bf0[25], cospi[32], bf0[22], cosBit);
        bf1[26] = halfBtf(cospi[32], bf0[26], cospi[32], bf0[21], cosBit);
        bf1[27] = halfBtf(cospi[32], bf0[27], cospi[32], bf0[20], cosBit);
        bf1[28] = bf0[28];
        bf1[29] = bf0[29];
        bf1[30] = bf0[30];
        bf1[31] = bf0[31];
        bf1[32] = bf0[32] + bf0[47];
        bf1[33] = bf0[33] + bf0[46];
        bf1[34] = bf0[34] + bf0[45];
        bf1[35] = bf0[35] + bf0[44];
        bf1[36] = bf0[36] + bf0[43];
        bf1[37] = bf0[37] + bf0[42];
        bf1[38] = bf0[38] + bf0[41];
        bf1[39] = bf0[39] + bf0[40];
        bf1[40] = -bf0[40] + bf0[39];
        bf1[41] = -bf0[41] + bf0[38];
        bf1[42] = -bf0[42] + bf0[37];
        bf1[43] = -bf0[43] + bf0[36];
        bf1[44] = -bf0[44] + bf0[35];
        bf1[45] = -bf0[45] + bf0[34];
        bf1[46] = -bf0[46] + bf0[33];
        bf1[47] = -bf0[47] + bf0[32];
        bf1[48] = -bf0[48] + bf0[63];
        bf1[49] = -bf0[49] + bf0[62];
        bf1[50] = -bf0[50] + bf0[61];
        bf1[51] = -bf0[51] + bf0[60];
        bf1[52] = -bf0[52] + bf0[59];
        bf1[53] = -bf0[53] + bf0[58];
        bf1[54] = -bf0[54] + bf0[57];
        bf1[55] = -bf0[55] + bf0[56];
        bf1[56] = bf0[56] + bf0[55];
        bf1[57] = bf0[57] + bf0[54];
        bf1[58] = bf0[58] + bf0[53];
        bf1[59] = bf0[59] + bf0[52];
        bf1[60] = bf0[60] + bf0[51];
        bf1[61] = bf0[61] + bf0[50];
        bf1[62] = bf0[62] + bf0[49];
        bf1[63] = bf0[63] + bf0[48];
    }

    /**
     * Computes stage 4 of {@link #fdct64(int[], int[], int[], int)}.
     *
     * @param bf0 the stage input
     * @param bf1 the stage output
     * @param cospi the cosine table
     * @param cosBit the precision of the cosine table
     */
    private static void fdct64Stage4(int[] bf0, int[] bf1, int[] cospi, int cosBit) {
        bf1[0] = bf0[0] + bf0[7];
        bf1[1] = bf0[1] + bf0[6];
        bf1[2] = bf0[2] + bf0[5];
        bf1[3] = bf0[3] + bf0[4];
        bf1[4] = -bf0[4] + bf0[3];
        bf1[5] = -bf0[5] + bf0[2];
        bf1[6] = -bf0[6] + bf0[1];
        bf1[7] = -bf0[7] + bf0[0];
        bf1[8] = bf0[8];
        bf1[9] = bf0[9];
        bf1[10] = halfBtf(-cospi[32], bf0[10], cospi[32], bf0[13], cosBit);
        bf1[11] = halfBtf(-cospi[32], bf0[11], cospi[32], bf0[12], cosBit);
        bf1[12] = halfBtf(cospi[32], bf0[12], cospi[32], bf0[11], cosBit);
        bf1[13] = halfBtf(cospi[32], bf0[13], cospi[32], bf0[10], cosBit);
        bf1[14] = bf0[14];
        bf1[15] = bf0[15];
        bf1[16] = bf0[16] + bf0[23];
        bf1[17] = bf0[17] + bf0[22];
        bf1[18] = bf0[18] + bf0[21];
        bf1[19] = bf0[19] + bf0[20];
        bf1[20] = -bf0[20] + bf0[19];
        bf1[21] = -bf0[21] + bf0[18];
        bf1[22] = -bf0[22] + bf0[17];
        bf1[23] = -bf0[23] + bf0[16];
        bf1[24] = -bf0[24] + bf0[31];
        bf1[25] = -bf0[25] + bf0[30];
        bf1[26] = -bf0[26] + bf0[29];
        bf1[27] = -bf0[27] + bf0[28];
        bf1[28] = bf0[28] + bf0[27];
        bf1[29] = bf0[29] + bf0[26];
        bf1[30] = bf0[30] + bf0[25];
        bf1[31] = bf0[31] + bf0[24];
        bf1[32] = bf0[32];
        bf1[33] = bf0[33];
        bf1[34] = bf0[34];
        bf1[35] = bf0[35];
        bf1[36] = halfBtf(-cospi[16], bf0[36], cospi[48], bf0[59], cosBit);
        bf1[37] = halfBtf(-cospi[16], bf0[37], cospi[48], bf0[58], cosBit);
        bf1[38] = halfBtf(-cospi[16], bf0[38], cospi[48], bf0[57], cosBit);
        bf1[39] = halfBtf(-cospi[16], bf0[39], cospi[48], bf0[56], cosBit);
        bf1[40] = halfBtf(-cospi[48], bf0[40], -cospi[16], bf0[55], cosBit);
        bf1[41] = halfBtf(-cospi[48], bf0[41], -cospi[16], bf0[54], cosBit);
        bf1[42] = halfBtf(-cospi[48], bf0[42], -cospi[16], bf0[53], cosBit);
        bf1[43] = halfBtf(-cospi[48], bf0[43], -cospi[16], bf0[52], cosBit);
        bf1[44] = bf0[44];
        bf1[45] = bf0[45];
        bf1[46] = bf0[46];
        bf1[47] = bf0[47];
        bf1[48] = bf0[48];
        bf1[49] = bf0[49];
        bf1[50] = bf0[50];
        bf1[51] = bf0[51];
        bf1[52] = halfBtf(cospi[48], bf0[52], -cospi[16], bf0[43], cosBit);
        bf1[53] = halfBtf(cospi[48], bf0[53], -cospi[16], bf0[42], cosBit);
        bf1[54] = halfBtf(cospi[48], bf0[54], -cospi[16], bf0[41], cosBit);
        bf1[55] = halfBtf(cospi[48], bf0[55], -cospi[16], bf0[40], cosBit);
        bf1[56] = halfBtf(cospi[16], bf0[56], cospi[48], bf0[39], cosBit);
        bf1[57] = halfBtf(cospi[16], bf0[57], cospi[48], bf0[38], cosBit);
        bf1[58] = halfBtf(cospi[16], bf0[58], cospi[48], bf0[37], cosBit);
        bf1[59] = halfBtf(cospi[16], bf0[59], cospi[48], bf0[36], cosBit);
        bf1[60] = bf0[60];
        bf1[61] = bf0[61];
        bf1[62] = bf0[62];
        bf1[63] = bf0[63];
    }

    /**
     * Computes stage 5 of {@link #fdct64(int[], int[], int[], int)}.
     *
     * @param bf0 the stage input
     * @param bf1 the stage output
     * @param cospi the cosine table
     * @param cosBit the precision of the cosine table
     */
    private static void fdct64Stage5(int[] bf0, int[] bf1, int[] cospi, int cosBit) {
        bf1[0] = bf0[0] + bf0[3];
        bf1[1] = bf0[1] + bf0[2];
        bf1[2] = -bf0[2] + bf0[1];
        bf1[3] = -bf0[3] + bf0[0];
        bf1[4] = bf0[4];
        bf1[5] = halfBtf(-cospi[32], bf0[5], cospi[32], bf0[6], cosBit);
        bf1[6] = halfBtf(cospi[32], bf0[6], cospi[32], bf0[5], cosBit);
        bf1[7] = bf0[7];
        bf1[8] = bf0[8] + bf0[11];
        bf1[9] = bf0[9] + bf0[10];
        bf1[10] = -bf0[10] + bf0[9];
        bf1[11] = -bf0[11] + bf0[8];
        bf1[12] = -bf0[12] + bf0[15];
        bf1[13] = -bf0[13] + bf0[14];
        bf1[14] = bf0[14] + bf0[13];
        bf1[15] = bf0[15] + bf0[12];
        bf1[16] = bf0[16];
        bf1[17] = bf0[17];
        bf1[18] = halfBtf(-cospi[16], bf0[18], cospi[48], bf0[29], cosBit);
        bf1[19] = halfBtf(-cospi[16], bf0[19], cospi[48], bf0[28], cosBit);
        bf1[20] = halfBtf(-cospi[48], bf0[20], -cospi[16], bf0[27], cosBit);
        bf1[21] = halfBtf(-cospi[48], bf0[21], -cospi[16], bf0[26], cosBit);
        bf1[22] = bf0[22];
        bf1[23] = bf0[23];
        bf1[24] = bf0[24];
        bf1[25] = bf0[25];
        bf1[26] = halfBtf(cospi[48], bf0[26], -cospi[16], bf0[21], cosBit);
        bf1[27] = halfBtf(cospi[48], bf0[27], -cospi[16], bf0[20], cosBit);
        bf1[28] = halfBtf(cospi[16], bf0[28], cospi[48], bf0[19], cosBit);
        bf1[29] = halfBtf(cospi[16], bf0[29], cospi[48], bf0[18], cosBit);
        bf1[30] = bf0[30];
        bf1[31] = bf0[31];
        bf1[32] = bf0[32] + bf0[39];
        bf1[33] = bf0[33] + bf0[38];
        bf1[34] = bf0[34] + bf0[37];
        bf1[35] = bf0[35] + bf0[36];
        bf1[36] = -bf0[36] + bf0[35];
        bf1[37] = -bf0[37] + bf0[34];
        bf1[38] = -bf0[38] + bf0[33];
        bf1[39] = -bf0[39] + bf0[32];
        bf1[40] = -bf0[40] + bf0[47];
        bf1[41] = -bf0[41] + bf0[46];
        bf1[42] = -bf0[42] + bf0[45];
        bf1[43] = -bf0[43] + bf0[44];
        bf1[44] = bf0[44] + bf0[43];
        bf1[45] = bf0[45] + bf0[42];
        bf1[46] = bf0[46] + bf0[41];
        bf1[47] = bf0[47] + bf0[40];
        bf1[48] = bf0[48] + bf0[55];
        bf1[49] = bf0[49] + bf0[54];
        bf1[50] = bf0[50] + bf0[53];
        bf1[51] = bf0[51] + bf0[52];
        bf1[52] = -bf0[52] + bf0[51];
        bf1[53] = -bf0[53] + bf0[50];
        bf1[54] = -bf0[54] + bf0[49];
        bf1[55] = -bf0[55] + bf0[48];
        bf1[56] = -bf0[56] + bf0[63];
        bf1[57] = -bf0[57] + bf0[62];
        bf1[58] = -bf0[58] + bf0[61];
        bf1[59] = -bf0[59] + bf0[60];
        bf1[60] = bf0[60] + bf0[59];
        bf1[61] = bf0[61] + bf0[58];
        bf1[62] = bf0[62] + bf0[57];
        bf1[63] = bf0[63] + bf0[56];
    }

    /**
     * Computes stage 6 of {@link #fdct64(int[], int[], int[], int)}.
     *
     * @param bf0 the stage input
     * @param bf1 the stage output
     * @param cospi the cosine table
     * @param cosBit the precision of the cosine table
     */
    private static void fdct64Stage6(int[] bf0, int[] bf1, int[] cospi, int cosBit) {
        bf1[0] = halfBtf(cospi[32], bf0[0], cospi[32], bf0[1], cosBit);
        bf1[1] = halfBtf(-cospi[32], bf0[1], cospi[32], bf0[0], cosBit);
        bf1[2] = halfBtf(cospi[48], bf0[2], cospi[16], bf0[3], cosBit);
        bf1[3] = halfBtf(cospi[48], bf0[3], -cospi[16], bf0[2], cosBit);
        bf1[4] = bf0[4] + bf0[5];
        bf1[5] = -bf0[5] + bf0[4];
        bf1[6] = -bf0[6] + bf0[7];
        bf1[7] = bf0[7] + bf0[6];
        bf1[8] = bf0[8];
        bf1[9] = halfBtf(-cospi[16], bf0[9], cospi[48], bf0[14], cosBit);
        bf1[10] = halfBtf(-cospi[48], bf0[10], -cospi[16], bf0[13], cosBit);
        bf1[11] = bf0[11];
        bf1[12] = bf0[12];
        bf1[13] = halfBtf(cospi[48], bf0[13], -cospi[16], bf0[10], cosBit);
        bf1[14] = halfBtf(cospi[16], bf0[14], cospi[48], bf0[9], cosBit);
        bf1[15] = bf0[15];
        bf1[16] = bf0[16] + bf0[19];
        bf1[17] = bf0[17] + bf0[18];
        bf1[18] = -bf0[18] + bf0[17];
        bf1[19] = -bf0[19] + bf0[16];
        bf1[20] = -bf0[20] + bf0[23];
        bf1[21] = -bf0[21] + bf0[22];
        bf1[22] = bf0[22] + bf0[21];
        bf1[23] = bf0[23] + bf0[20];
        bf1[24] = bf0[24] + bf0[27];
        bf1[25] = bf0[25] + bf0[26];
        bf1[26] = -bf0[26] + bf0[25];
        bf1[27] = -bf0[27] + bf0[24];
        bf1[28] = -bf0[28] + bf0[31];
        bf1[29] = -bf0[29] + bf0[30];
        bf1[30] = bf0[30] + bf0[29];
        bf1[31] = bf0[31] + bf0[28];
        bf1[32] = bf0[32];
        bf1[33] = bf0[33];
        bf1[34] = halfBtf(-cospi[8], bf0[34], cospi[56], bf0[61], cosBit);
        bf1[35] = halfBtf(-cospi[8], bf0[35], cospi[56], bf0[60], cosBit);
        bf1[36] = halfBtf(-cospi[56], bf0[36], -cospi[8], bf0[59], cosBit);
        bf1[37] = halfBtf(-cospi[56], bf0[37], -cospi[8], bf0[58], cosBit);
        bf1[38] = bf0[38];
        bf1[39] = bf0[39];
        bf1[40] = bf0[40];
        bf1[41] = bf0[41];
        bf1[42] = halfBtf(-cospi[40], bf0[42], cospi[24], bf0[53], cosBit);
        bf1[43] = halfBtf(-cospi[40], bf0[43], cospi[24], bf0[52], cosBit);
        bf1[44] = halfBtf(-cospi[24], bf0[44], -cospi[40], bf0[51], cosBit);
        bf1[45] = halfBtf(-cospi[24], bf0[45], -cospi[40], bf0[50], cosBit);
        bf1[46] = bf0[46];
        bf1[47] = bf0[47];
        bf1[48] = bf0[48];
        bf1[49] = bf0[49];
        bf1[50] = halfBtf(cospi[24], bf0[50], -cospi[40], bf0[45], cosBit);
        bf1[51] = halfBtf(cospi[24], bf0[51], -cospi[40], bf0[44], cosBit);
        bf1[52] = halfBtf(cospi[40], bf0[52], cospi[24], bf0[43], cosBit);
        bf1[53] = halfBtf(cospi[40], bf0[53], cospi[24], bf0[42], cosBit);
        bf1[54] = bf0[54];
        bf1[55] = bf0[55];
        bf1[56] = bf0[56];
        bf1[57] = bf0[57];
        bf1[58] = halfBtf(cospi[56], bf0[58], -cospi[8], bf0[37], cosBit);
        bf1[59] = halfBtf(cospi[56], bf0[59], -cospi[8], bf0[36], cosBit);
        bf1[60] = halfBtf(cospi[8], bf0[60], cospi[56], bf0[35], cosBit);
        bf1[61] = halfBtf(cospi[8], bf0[61], cospi[56], bf0[34], cosBit);
        bf1[62] = bf0[62];
        bf1[63] = bf0[63];
    }

    /**
     * Computes stage 7 of {@link #fdct64(int[], int[], int[], int)}.
     *
     * @param bf0 the stage input
     * @param bf1 the stage output
     * @param cospi the cosine table
     * @param cosBit the precision of the cosine table
     */
    private static void fdct64Stage7(int[] bf0, int[] bf1, int[] cospi, int cosBit) {
        bf1[0] = bf0[0];
        bf1[1] = bf0[1];
        bf1[2] = bf0[2];
        bf1[3] = bf0[3];
        bf1[4] = halfBtf(cospi[56], bf0[4], cospi[8], bf0[7], cosBit);
        bf1[5] = halfBtf(cospi[24], bf0[5], cospi[40], bf0[6], cosBit);
        bf1[6] = halfBtf(cospi[24], bf0[6], -cospi[40], bf0[5], cosBit);
        bf1[7] = halfBtf(cospi[56], bf0[7], -cospi[8], bf0[4], cosBit);
        bf1[8] = bf0[8] + bf0[9];
        bf1[9] = -bf0[9] + bf0[8];
        bf1[10] = -bf0[10] + bf0[11];
        bf1[11] = bf0[11] + bf0[10];
        bf1[12] = bf0[12] + bf0[13];
        bf1[13] = -bf0[13] + bf0[12];
        bf1[14] = -bf0[14] + bf0[15];
        bf1[15] = bf0[15] + bf0[14];
        bf1[16] = bf0[16];
        bf1[17] = halfBtf(-cospi[8], bf0[17], cospi[56], bf0[30], cosBit);
        bf1[18] = halfBtf(-cospi[56], bf0[18], -cospi[8], bf0[29], cosBit);
        bf1[19] = bf0[19];
        bf1[20] = bf0[20];
        bf1[21] = halfBtf(-cospi[40], bf0[21], cospi[24], bf0[26], cosBit);
        bf1[22] = halfBtf(-cospi[24], bf0[22], -cospi[40], bf0[25], cosBit);
        bf1[23] = bf0[23];
        bf1[24] = bf0[24];
        bf1[25] = halfBtf(cospi[24], bf0[25], -cospi[40], bf0[22], cosBit);
        bf1[26] = halfBtf(cospi[40], bf0[26], cospi[24], bf0[21], cosBit);
        bf1[27] = bf0[27];
        bf1[28] = bf0[28];
        bf1[29] = halfBtf(cospi[56], bf0[29], -cospi[8], bf0[18], cosBit);
        bf1[30] = halfBtf(cospi[8], bf0[30], cospi[56], bf0[17], cosBit);
        bf1[31] = bf0[31];
        bf1[32] = bf0[32] + bf0[35];
        bf1[33] = bf0[33] + bf0[34];
        bf1[34] = -bf0[34] + bf0[33];
        bf1[35] = -bf0[35] + bf0[32];
        bf1[36] = -bf0[36] + bf0[39];
        bf1[37] = -bf0[37] + bf0[38];
        bf1[38] = bf0[38] + bf0[37];
        bf1[39] = bf0[39] + bf0[36];
        bf1[40] = bf0[40] + bf0[43];
        bf1[41] = bf0[41] + bf0[42];
        bf1[42] = -bf0[42] + bf0[41];
        bf1[43] = -bf0[43] + bf0[40];
        bf1[44] = -bf0[44] + bf0[47];
        bf1[45] = -bf0[45] + bf0[46];
        bf1[46] = bf0[46] + bf0[45];
        bf1[47] = bf0[47] + bf0[44];
        bf1[48] = bf0[48] + bf0[51];
        bf1[49] = bf0[49] + bf0[50];
        bf1[50] = -bf0[50] + bf0[49];
        bf1[51] = -bf0[51] + bf0[48];
        bf1[52] = -bf0[52] + bf0[55];
        bf1[53] = -bf0[53] + bf0[54];
        bf1[54] = bf0[54] + bf0[53];
        bf1[55] = bf0[55] + bf0[52];
        bf1[56] = bf0[56] + bf0[59];
        bf1[57] = bf0[57] + bf0[58];
        bf1[58] = -bf0[58] + bf0[57];
        bf1[59] = -bf0[59] + bf0[56];
        bf1[60] = -bf0[60] + bf0[63];
        bf1[61] = -bf0[61] + bf0[62];
        bf1[62] = bf0[62] + bf0[61];
        bf1[63] = bf0[63] + bf0[60];
    }

    /**
     * Computes stage 8 of {@link #fdct64(int[], int[], int[], int)}.
     *
     * @param bf0 the stage input
     * @param bf1 the stage output
     * @param cospi the cosine table
     * @param cosBit the precision of the cosine table
     */
    private static void fdct64Stage8(int[] bf0, int[] bf1, int[] cospi, int cosBit) {
        bf1[0] = bf0[0];
        bf1[1] = bf0[1];
        bf1[2] = bf0[2];
        bf1[3] = bf0[3];
        bf1[4] = bf0[4];
        bf1[5] = bf0[5];
        bf1[6] = bf0[6];
        bf1[7] = bf0[7];
        bf1[8] = halfBtf(cospi[60], bf0[8], cospi[4], bf0[15], cosBit);
        bf1[9] = halfBtf(cospi[28], bf0[9], cospi[36], bf0[14], cosBit);
        bf1[10] = halfBtf(cospi[44], bf0[10], cospi[20], bf0[13], cosBit);
        bf1[11] = halfBtf(cospi[12], bf0[11], cospi[52], bf0[12], cosBit);
        bf1[12] = halfBtf(cospi[12], bf0[12], -cospi[52], bf0[11], cosBit);
        bf1[13] = halfBtf(cospi[44], bf0[13], -cospi[20], bf0[10], cosBit);
        bf1[14] = halfBtf(cospi[28], bf0[14], -cospi[36], bf0[9], cosBit);
        bf1[15] = halfBtf(cospi[60], bf0[15], -cospi[4], bf0[8], cosBit);
        bf1[16] = bf0[16] + bf0[17];
        bf1[17] = -bf0[17] + bf0[16];
        bf1[18] = -bf0[18] + bf0[19];
        bf1[19] = bf0[19] + bf0[18];
        bf1[20] = bf0[20] + bf0[21];
        bf1[21] = -bf0[21] + bf0[20];
        bf1[22] = -bf0[22] + bf0[23];
        bf1[23] = bf0[23] + bf0[22];
        bf1[24] = bf0[24] + bf0[25];
        bf1[25] = -bf0[25] + bf0[24];
        bf1[26] = -bf0[26] + bf0[27];
        bf1[27] = bf0[27] + bf0[26];
        bf1[28] = bf0[28] + bf0[29];
        bf1[29] = -bf0[29] + bf0[28];
        bf1[30] = -bf0[30] + bf0[31];
        bf1[31] = bf0[31] + bf0[30];
        bf1[32] = bf0[32];
        bf1[33] = halfBtf(-cospi[4], bf0[33], cospi[60], bf0[62], cosBit);
        bf1[34] = halfBtf(-cospi[60], bf0[34], -cospi[4], bf0[61], cosBit);
        bf1[35] = bf0[35];
        bf1[36] = bf0[36];
        bf1[37] = halfBtf(-cospi[36], bf0[37], cospi[28], bf0[58], cosBit);
        bf1[38] = halfBtf(-cospi[28], bf0[38], -cospi[36], bf0[57], cosBit);
        bf1[39] = bf0[39];
        bf1[40] = bf0[40];
        bf1[41] = halfBtf(-cospi[20], bf0[41], cospi[44], bf0[54], cosBit);
        bf1[42] = halfBtf(-cospi[44], bf0[42], -cospi[20], bf0[53], cosBit);
        bf1[43] = bf0[43];
        bf1[44] = bf0[44];
        bf1[45] = halfBtf(-cospi[52], bf0[45], cospi[12], bf0[50], cosBit);
        bf1[46] = halfBtf(-cospi[12], bf0[46], -cospi[52], bf0[49], cosBit);
        bf1[47] = bf0[47];
        bf1[48] = bf0[48];
        bf1[49] = halfBtf(cospi[12], bf0[49], -cospi[52], bf0[46], cosBit);
        bf1[50] = halfBtf(cospi[52], bf0[50], cospi[12], bf0[45], cosBit);
        bf1[51] = bf0[51];
        bf1[52] = bf0[52];
        bf1[53] = halfBtf(cospi[44], bf0[53], -cospi[20], bf0[42], cosBit);
        bf1[54] = halfBtf(cospi[20], bf0[54], cospi[44], bf0[41], cosBit);
        bf1[55] = bf0[55];
        bf1[56] = bf0[56];
        bf1[57] = halfBtf(cospi[28], bf0[57], -cospi[36], bf0[38], cosBit);
        bf1[58] = halfBtf(cospi[36], bf0[58], cospi[28], bf0[37], cosBit);
        bf1[59] = bf0[59];
        bf1[60] = bf0[60];
        bf1[61] = halfBtf(cospi[60], bf0[61], -cospi[4], bf0[34], cosBit);
        bf1[62] = halfBtf(cospi[4], bf0[62], cospi[60], bf0[33], cosBit);
        bf1[63] = bf0[63];
    }

    /**
     * Computes stage 9 of {@link #fdct64(int[], int[], int[], int)}.
     *
     * @param bf0 the stage input
     * @param bf1 the stage output
     * @param cospi the cosine table
     * @param cosBit the precision of the cosine table
     */
    private static void fdct64Stage9(int[] bf0, int[] bf1, int[] cospi, int cosBit) {
        bf1[0] = bf0[0];
        bf1[1] = bf0[1];
        bf1[2] = bf0[2];
        bf1[3] = bf0[3];
        bf1[4] = bf0[4];
        bf1[5] = bf0[5];
        bf1[6] = bf0[6];
        bf1[7] = bf0[7];
        bf1[8] = bf0[8];
        bf1[9] = bf0[9];
        bf1[10] = bf0[10];
        bf1[11] = bf0[11];
        bf1[12] = bf0[12];
        bf1[13] = bf0[13];
        bf1[14] = bf0[14];
        bf1[15] = bf0[15];
        bf1[16] = halfBtf(cospi[62], bf0[16], cospi[2], bf0[31], cosBit);
        bf1[17] = halfBtf(cospi[30], bf0[17], cospi[34], bf0[30], cosBit);
        bf1[18] = halfBtf(cospi[46], bf0[18], cospi[18], bf0[29], cosBit);
        bf1[19] = halfBtf(cospi[14], bf0[19], cospi[50], bf0[28], cosBit);
        bf1[20] = halfBtf(cospi[54], bf0[20], cospi[10], bf0[27], cosBit);
        bf1[21] = halfBtf(cospi[22], bf0[21], cospi[42], bf0[26], cosBit);
        bf1[22] = halfBtf(cospi[38], bf0[22], cospi[26], bf0[25], cosBit);
        bf1[23] = halfBtf(cospi[6], bf0[23], cospi[58], bf0[24], cosBit);
        bf1[24] = halfBtf(cospi[6], bf0[24], -cospi[58], bf0[23], cosBit);
        bf1[25] = halfBtf(cospi[38], bf0[25], -cospi[26], bf0[22], cosBit);
        bf1[26] = halfBtf(cospi[22], bf0[26], -cospi[42], bf0[21], cosBit);
        bf1[27] = halfBtf(cospi[54], bf0[27], -cospi[10], bf0[20], cosBit);
        bf1[28] = halfBtf(cospi[14], bf0[28], -cospi[50], bf0[19], cosBit);
        bf1[29] = halfBtf(cospi[46], bf0[29], -cospi[18], bf0[18], cosBit);
        bf1[30] = halfBtf(cospi[30], bf0[30], -cospi[34], bf0[17], cosBit);
        bf1[31] = halfBtf(cospi[62], bf0[31], -cospi[2], bf0[16], cosBit);
        bf1[32] = bf0[32] + bf0[33];
        bf1[33] = -bf0[33] + bf0[32];
        bf1[34] = -bf0[34] + bf0[35];
        bf1[35] = bf0[35] + bf0[34];
        bf1[36] = bf0[36] + bf0[37];
        bf1[37] = -bf0[37] + bf0[36];
        bf1[38] = -bf0[38] + bf0[39];
        bf1[39] = bf0[39] + bf0[38];
        bf1[40] = bf0[40] + bf0[41];
        bf1[41] = -bf0[41] + bf0[40];
        bf1[42] = -bf0[42] + bf0[43];
        bf1[43] = bf0[43] + bf0[42];
        bf1[44] = bf0[44] + bf0[45];
        bf1[45] = -bf0[45] + bf0[44];
        bf1[46] = -bf0[46] + bf0[47];
        bf1[47] = bf0[47] + bf0[46];
        bf1[48] = bf0[48] + bf0[49];
        bf1[49] = -bf0[49] + bf0[48];
        bf1[50] = -bf0[50] + bf0[51];
        bf1[51] = bf0[51] + bf0[50];
        bf1[52] = bf0[52] + bf0[53];
        bf1[53] = -bf0[53] + bf0[52];
        bf1[54] = -bf0[54] + bf0[55];
        bf1[55] = bf0[55] + bf0[54];
        bf1[56] = bf0[56] + bf0[57];
        bf1[57] = -bf0[57] + bf0[56];
        bf1[58] = -bf0[58] + bf0[59];
        bf1[59] = bf0[59] + bf0[58];
        bf1[60] = bf0[60] + bf0[61];
        bf1[61] = -bf0[61] + bf0[60];
        bf1[62] = -bf0[62] + bf0[63];
        bf1[63] = bf0[63] + bf0[62];
    }

    /**
     * Computes stage 10 of {@link #fdct64(int[], int[], int[], int)}.
     *
     * @param bf0 the stage input
     * @param bf1 the stage output
     * @param cospi the cosine table
     * @param cosBit the precision of the cosine table
     */
    private static void fdct64Stage10(int[] bf0, int[] bf1, int[] cospi, int cosBit) {
        bf1[0] = bf0[0];
        bf1[1] = bf0[1];
        bf1[2] = bf0[2];
        bf1[3] = bf0[3];
        bf1[4] = bf0[4];
        bf1[5] = bf0[5];
        bf1[6] = bf0[6];
        bf1[7] = bf0[7];
        bf1[8] = bf0[8];
        bf1[9] = bf0[9];
        bf1[10] = bf0[10];
        bf1[11] = bf0[11];
        bf1[12] = bf0[12];
        bf1[13] = bf0[13];
        bf1[14] = bf0[14];
        bf1[15] = bf0[15];
        bf1[16] = bf0[16];
        bf1[17] = bf0[17];
        bf1[18] = bf0[18];
        bf1[19] = bf0[19];
        bf1[20] = bf0[20];
        bf1[21] = bf0[21];
        bf1[22] = bf0[22];
        bf1[23] = bf0[23];
        bf1[24] = bf0[24];
        bf1[25] = bf0[25];
        bf1[26] = bf0[26];
        bf1[27] = bf0[27];
        bf1[28] = bf0[28];
        bf1[29] = bf0[29];
        bf1[30] = bf0[30];
        bf1[31] = bf0[31];
        bf1[32] = halfBtf(cospi[63], bf0[32], cospi[1], bf0[63], cosBit);
        bf1[33] = halfBtf(cospi[31], bf0[33], cospi[33], bf0[62], cosBit);
        bf1[34] = halfBtf(cospi[47], bf0[34], cospi[17], bf0[61], cosBit);
        bf1[35] = halfBtf(cospi[15], bf0[35], cospi[49], bf0[60], cosBit);
        bf1[36] = halfBtf(cospi[55], bf0[36], cospi[9], bf0[59], cosBit);
        bf1[37] = halfBtf(cospi[23], bf0[37], cospi[41], bf0[58], cosBit);
        bf1[38] = halfBtf(cospi[39], bf0[38], cospi[25], bf0[57], cosBit);
        bf1[39] = halfBtf(cospi[7], bf0[39], cospi[57], bf0[56], cosBit);
        bf1[40] = halfBtf(cospi[59], bf0[40], cospi[5], bf0[55], cosBit);
        bf1[41] = halfBtf(cospi[27], bf0[41], cospi[37], bf0[54], cosBit);
        bf1[42] = halfBtf(cospi[43], bf0[42], cospi[21], bf0[53], cosBit);
        bf1[43] = halfBtf(cospi[11], bf0[43], cospi[53], bf0[52], cosBit);
        bf1[44] = halfBtf(cospi[51], bf0[44], cospi[13], bf0[51], cosBit);
        bf1[45] = halfBtf(cospi[19], bf0[45], cospi[45], bf0[50], cosBit);
        bf1[46] = halfBtf(cospi[35], bf0[46], cospi[29], bf0[49], cosBit);
        bf1[47] = halfBtf(cospi[3], bf0[47], cospi[61], bf0[48], cosBit);
        bf1[48] = halfBtf(cospi[3], bf0[48], -cospi[61], bf0[47], cosBit);
        bf1[49] = halfBtf(cospi[35], bf0[49], -cospi[29], bf0[46], cosBit);
        bf1[50] = halfBtf(cospi[19], bf0[50], -cospi[45], bf0[45], cosBit);
        bf1[51] = halfBtf(cospi[51], bf0[51], -cospi[13], bf0[44], cosBit);
        bf1[52] = halfBtf(cospi[11], bf0[52], -cospi[53], bf0[43], cosBit);
        bf1[53] = halfBtf(cospi[43], bf0[53], -cospi[21], bf0[42], cosBit);
        bf1[54] = halfBtf(cospi[27], bf0[54], -cospi[37], bf0[41], cosBit);
        bf1[55] = halfBtf(cospi[59], bf0[55], -cospi[5], bf0[40], cosBit);
        bf1[56] = halfBtf(cospi[7], bf0[56], -cospi[57], bf0[39], cosBit);
        bf1[57] = halfBtf(cospi[39], bf0[57], -cospi[25], bf0[38], cosBit);
        bf1[58] = halfBtf(cospi[23], bf0[58], -cospi[41], bf0[37], cosBit);
        bf1[59] = halfBtf(cospi[55], bf0[59], -cospi[9], bf0[36], cosBit);
        bf1[60] = halfBtf(cospi[15], bf0[60], -cospi[49], bf0[35], cosBit);
        bf1[61] = halfBtf(cospi[47], bf0[61], -cospi[17], bf0[34], cosBit);
        bf1[62] = halfBtf(cospi[31], bf0[62], -cospi[33], bf0[33], cosBit);
        bf1[63] = halfBtf(cospi[63], bf0[63], -cospi[1], bf0[32], cosBit);
    }

    /**
     * Computes stage 11 of {@link #fdct64(int[], int[], int[], int)}.
     *
     * @param bf0 the stage input
     * @param bf1 the stage output
     * @param cospi the cosine table
     * @param cosBit the precision of the cosine table
     */
    private static void fdct64Stage11(int[] bf0, int[] bf1, int[] cospi, int cosBit) {
        bf1[0] = bf0[0];
        bf1[1] = bf0[32];
        bf1[2] = bf0[16];
        bf1[3] = bf0[48];
        bf1[4] = bf0[8];
        bf1[5] = bf0[40];
        bf1[6] = bf0[24];
        bf1[7] = bf0[56];
        bf1[8] = bf0[4];
        bf1[9] = bf0[36];
        bf1[10] = bf0[20];
        bf1[11] = bf0[52];
        bf1[12] = bf0[12];
        bf1[13] = bf0[44];
        bf1[14] = bf0[28];
        bf1[15] = bf0[60];
        bf1[16] = bf0[2];
        bf1[17] = bf0[34];
        bf1[18] = bf0[18];
        bf1[19] = bf0[50];
        bf1[20] = bf0[10];
        bf1[21] = bf0[42];
        bf1[22] = bf0[26];
        bf1[23] = bf0[58];
        bf1[24] = bf0[6];
        bf1[25] = bf0[38];
        bf1[26] = bf0[22];
        bf1[27] = bf0[54];
        bf1[28] = bf0[14];
        bf1[29] = bf0[46];
        bf1[30] = bf0[30];
        bf1[31] = bf0[62];
        bf1[32] = bf0[1];
        bf1[33] = bf0[33];
        bf1[34] = bf0[17];
        bf1[35] = bf0[49];
        bf1[36] = bf0[9];
        bf1[37] = bf0[41];
        bf1[38] = bf0[25];
        bf1[39] = bf0[57];
        bf1[40] = bf0[5];
        bf1[41] = bf0[37];
        bf1[42] = bf0[21];
        bf1[43] = bf0[53];
        bf1[44] = bf0[13];
        bf1[45] = bf0[45];
        bf1[46] = bf0[29];
        bf1[47] = bf0[61];
        bf1[48] = bf0[3];
        bf1[49] = bf0[35];
        bf1[50] = bf0[19];
        bf1[51] = bf0[51];
        bf1[52] = bf0[11];
        bf1[53] = bf0[43];
        bf1[54] = bf0[27];
        bf1[55] = bf0[59];
        bf1[56] = bf0[7];
        bf1[57] = bf0[39];
        bf1[58] = bf0[23];
        bf1[59] = bf0[55];
        bf1[60] = bf0[15];
        bf1[61] = bf0[47];
        bf1[62] = bf0[31];
        bf1[63] = bf0[63];
    }

    /**
     * Computes a 8-point forward ADST ({@code av1_fadst8}).
     *
     * @param input the input samples
     * @param output the output coefficients
     * @param step a scratch array of at least 8 entries
     * @param cosBit the precision of the cosine table
     */
    static void fadst8(int[] input, int[] output, int[] step, int cosBit) {
        var cospi = COSPI[cosBit - COS_BIT_MIN];
        output[0] = input[0];
        output[1] = -input[7];
        output[2] = -input[3];
        output[3] = input[4];
        output[4] = -input[1];
        output[5] = input[6];
        output[6] = input[2];
        output[7] = -input[5];
        step[0] = output[0];
        step[1] = output[1];
        step[2] = halfBtf(cospi[32], output[2], cospi[32], output[3], cosBit);
        step[3] = halfBtf(cospi[32], output[2], -cospi[32], output[3], cosBit);
        step[4] = output[4];
        step[5] = output[5];
        step[6] = halfBtf(cospi[32], output[6], cospi[32], output[7], cosBit);
        step[7] = halfBtf(cospi[32], output[6], -cospi[32], output[7], cosBit);
        output[0] = step[0] + step[2];
        output[1] = step[1] + step[3];
        output[2] = step[0] - step[2];
        output[3] = step[1] - step[3];
        output[4] = step[4] + step[6];
        output[5] = step[5] + step[7];
        output[6] = step[4] - step[6];
        output[7] = step[5] - step[7];
        step[0] = output[0];
        step[1] = output[1];
        step[2] = output[2];
        step[3] = output[3];
        step[4] = halfBtf(cospi[16], output[4], cospi[48], output[5], cosBit);
        step[5] = halfBtf(cospi[48], output[4], -cospi[16], output[5], cosBit);
        step[6] = halfBtf(-cospi[48], output[6], cospi[16], output[7], cosBit);
        step[7] = halfBtf(cospi[16], output[6], cospi[48], output[7], cosBit);
        output[0] = step[0] + step[4];
        output[1] = step[1] + step[5];
        output[2] = step[2] + step[6];
        output[3] = step[3] + step[7];
        output[4] = step[0] - step[4];
        output[5] = step[1] - step[5];
        output[6] = step[2] - step[6];
        output[7] = step[3] - step[7];
        step[0] = halfBtf(cospi[4], output[0], cospi[60], output[1], cosBit);
        step[1] = halfBtf(cospi[60], output[0], -cospi[4], output[1], cosBit);
        step[2] = halfBtf(cospi[20], output[2], cospi[44], output[3], cosBit);
        step[3] = halfBtf(cospi[44], output[2], -cospi[20], output[3], cosBit);
        step[4] = halfBtf(cospi[36], output[4], cospi[28], output[5], cosBit);
        step[5] = halfBtf(cospi[28], output[4], -cospi[36], output[5], cosBit);
        step[6] = halfBtf(cospi[52], output[6], cospi[12], output[7], cosBit);
        step[7] = halfBtf(cospi[12], output[6], -cospi[52], output[7], cosBit);
        output[0] = step[1];
        output[1] = step[6];
        output[2] = step[3];
        output[3] = step[4];
        output[4] = step[5];
        output[5] = step[2];
        output[6] = step[7];
        output[7] = step[0];
    }

    /**
     * Computes a 16-point forward ADST ({@code av1_fadst16}).
     *
     * @param input the input samples
     * @param output the output coefficients
     * @param step a scratch array of at least 16 entries
     * @param cosBit the precision of the cosine table
     */
    static void fadst16(int[] input, int[] output, int[] step, int cosBit) {
        var cospi = COSPI[cosBit - COS_BIT_MIN];
        output[0] = input[0];
        output[1] = -input[15];
        output[2] = -input[7];
        output[3] = input[8];
        output[4] = -input[3];
        output[5] = input[12];
        output[6] = input[4];
        output[7] = -input[11];
        output[8] = -input[1];
        output[9] = input[14];
        output[10] = input[6];
        output[11] = -input[9];
        output[12] = input[2];
        output[13] = -input[13];
        output[14] = -input[5];
        output[15] = input[10];
        step[0] = output[0];
        step[1] = output[1];
        step[2] = halfBtf(cospi[32], output[2], cospi[32], output[3], cosBit);
        step[3] = halfBtf(cospi[32], output[2], -cospi[32], output[3], cosBit);
        step[4] = output[4];
        step[5] = output[5];
        step[6] = halfBtf(cospi[32], output[6], cospi[32], output[7], cosBit);
        step[7] = halfBtf(cospi[32], output[6], -cospi[32], output[7], cosBit);
        step[8] = output[8];
        step[9] = output[9];
        step[10] = halfBtf(cospi[32], output[10], cospi[32], output[11], cosBit);
        step[11] = halfBtf(cospi[32], output[10], -cospi[32], output[11], cosBit);
        step[12] = output[12];
        step[13] = output[13];
        step[14] = halfBtf(cospi[32], output[14], cospi[32], output[15], cosBit);
        step[15] = halfBtf(cospi[32], output[14], -cospi[32], output[15], cosBit);
        output[0] = step[0] + step[2];
        output[1] = step[1] + step[3];
        output[2] = step[0] - step[2];
        output[3] = step[1] - step[3];
        output[4] = step[4] + step[6];
        output[5] = step[5] + step[7];
        output[6] = step[4] - step[6];
        output[7] = step[5] - step[7];
        output[8] = step[8] + step[10];
        output[9] = step[9] + step[11];
        output[10] = step[8] - step[10];
        output[11] = step[9] - step[11];
        output[12] = step[12] + step[14];
        output[13] = step[13] + step[15];
        output[14] = step[12] - step[14];
        output[15] = step[13] - step[15];
        step[0] = output[0];
        step[1] = output[1];
        step[2] = output[2];
        step[3] = output[3];
        step[4] = halfBtf(cospi[16], output[4], cospi[48], output[5], cosBit);
        step[5] = halfBtf(cospi[48], output[4], -cospi[16], output[5], cosBit);
        step[6] = halfBtf(-cospi[48], output[6], cospi[16], output[7], cosBit);
        step[7] = halfBtf(cospi[16], output[6], cospi[48], output[7], cosBit);
        step[8] = output[8];
        step[9] = output[9];
        step[10] = output[10];
        step[11] = output[11];
        step[12] = halfBtf(cospi[16], output[12], cospi[48], output[13], cosBit);
        step[13] = halfBtf(cospi[48], output[12], -cospi[16], output[13], cosBit);
        step[14] = halfBtf(-cospi[48], output[14], cospi[16], output[15], cosBit);
        step[15] = halfBtf(cospi[16], output[14], cospi[48], output[15], cosBit);
        output[0] = step[0] + step[4];
        output[1] = step[1] + step[5];
        output[2] = step[2] + step[6];
        output[3] = step[3] + step[7];
        output[4] = step[0] - step[4];
        output[5] = step[1] - step[5];
        output[6] = step[2] - step[6];
        output[7] = step[3] - step[7];
        output[8] = step[8] + step[12];
        output[9] = step[9] + step[13];
        output[10] = step[10] + step[14];
        output[11] = step[11] + step[15];
        output[12] = step[8] - step[12];
        output[13] = step[9] - step[13];
        output[14] = step[10] - step[14];
        output[15] = step[11] - step[15];
        step[0] = output[0];
        step[1] = output[1];
        step[2] = output[2];
        step[3] = output[3];
        step[4] = output[4];
        step[5] = output[5];
        step[6] = output[6];
        step[7] = output[7];
        step[8] = halfBtf(cospi[8], output[8], cospi[56], output[9], cosBit);
        step[9] = halfBtf(cospi[56], output[8], -cospi[8], output[9], cosBit);
        step[10] = halfBtf(cospi[40], output[10], cospi[24], output[11], cosBit);
        step[11] = halfBtf(cospi[24], output[10], -cospi[40], output[11], cosBit);
        step[12] = halfBtf(-cospi[56], output[12], cospi[8], output[13], cosBit);
        step[13] = halfBtf(cospi[8], output[12], cospi[56], output[13], cosBit);
        step[14] = halfBtf(-cospi[24], output[14], cospi[40], output[15], cosBit);
        step[15] = halfBtf(cospi[40], output[14], cospi[24], output[15], cosBit);
        output[0] = step[0] + step[8];
        output[1] = step[1] + step[9];
        output[2] = step[2] + step[10];
        output[3] = step[3] + step[11];
        output[4] = step[4] + step[12];
        output[5] = step[5] + step[13];
        output[6] = step[6] + step[14];
        output[7] = step[7] + step[15];
        output[8] = step[0] - step[8];
        output[9] = step[1] - step[9];
        output[10] = step[2] - step[10];
        output[11] = step[3] - step[11];
        output[12] = step[4] - step[12];
        output[13] = step[5] - step[13];
        output[14] = step[6] - step[14];
        output[15] = step[7] - step[15];
        step[0] = halfBtf(cospi[2], output[0], cospi[62], output[1], cosBit);
        step[1] = halfBtf(cospi[62], output[0], -cospi[2], output[1], cosBit);
        step[2] = halfBtf(cospi[10], output[2], cospi[54], output[3], cosBit);
        step[3] = halfBtf(cospi[54], output[2], -cospi[10], output[3], cosBit);
        step[4] = halfBtf(cospi[18], output[4], cospi[46], output[5], cosBit);
        step[5] = halfBtf(cospi[46], output[4], -cospi[18], output[5], cosBit);
        step[6] = halfBtf(cospi[26], output[6], cospi[38], output[7], cosBit);
        step[7] = halfBtf(cospi[38], output[6], -cospi[26], output[7], cosBit);
        step[8] = halfBtf(cospi[34], output[8], cospi[30], output[9], cosBit);
        step[9] = halfBtf(cospi[30], output[8], -cospi[34], output[9], cosBit);
        step[10] = halfBtf(cospi[42], output[10], cospi[22], output[11], cosBit);
        step[11] = halfBtf(cospi[22], output[10], -cospi[42], output[11], cosBit);
        step[12] = halfBtf(cospi[50], output[12], cospi[14], output[13], cosBit);
        step[13] = halfBtf(cospi[14], output[12], -cospi[50], output[13], cosBit);
        step[14] = halfBtf(cospi[58], output[14], cospi[6], output[15], cosBit);
        step[15] = halfBtf(cospi[6], output[14], -cospi[58], output[15], cosBit);
        output[0] = step[1];
        output[1] = step[14];
        output[2] = step[3];
        output[3] = step[12];
        output[4] = step[5];
        output[5] = step[10];
        output[6] = step[7];
        output[7] = step[8];
        output[8] = step[9];
        output[9] = step[6];
        output[10] = step[11];
        output[11] = step[4];
        output[12] = step[13];
        output[13] = step[2];
        output[14] = step[15];
        output[15] = step[0];
    }
}
