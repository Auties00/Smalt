package com.github.auties00.smalt.video.av1.internal;

import java.util.Arrays;

/**
 * Implements the AV1 inverse transform kernels with plain scalar Java; the reference for any
 * Vector API implementation.
 *
 * <p>A block is transformed in two one-dimensional passes over a 32-bit intermediate buffer: the
 * first pass transforms the coded rows of the (transposed) coefficient buffer, clipping
 * intermediates to the row range of the bit depth; the values are then rounded by the size's
 * shift and clipped to the column range; the second pass transforms the columns, and the result,
 * rounded by four bits, is added to the prediction.
 *
 * @implNote This implementation ports dav1d's {@code src/itx_1d.c} and the C kernels of
 *           {@code src/itx_tmpl.c} ({@code inv_txfm_add_c}, {@code inv_txfm_add_wht_wht_4x4_c}),
 *           including dav1d's overflow-avoiding rewrites of the butterfly multiplications, its
 *           end-of-block shortcuts and its intermediate clipping: 16-bit for both passes in the
 *           8-bit template, and {@code ~bitdepthMax << 7} for rows and {@code ~bitdepthMax << 5}
 *           for columns in the high bit depth template. dav1d's function table maps the slot of a
 *           type to the kernel of its transpose (its coefficients being stored transposed), which
 *           {@code SLOT_TYPE} reproduces.
 */
public final class ScalarItxDsp extends ItxDsp {
    /**
     * The one-dimensional DCT (dav1d {@code Tx1dType} {@code DCT}).
     */
    static final int DCT = 0;

    /**
     * The one-dimensional ADST (dav1d {@code ADST}).
     */
    static final int ADST = 1;

    /**
     * The one-dimensional identity transform (dav1d {@code IDENTITY}).
     */
    static final int IDENTITY = 2;

    /**
     * The one-dimensional flipped ADST (dav1d {@code FLIPADST}).
     */
    static final int FLIPADST = 3;

    /**
     * The transform type {@code DCT_DCT}, the only one with a DC-only path.
     */
    static final int DCT_DCT = 0;

    /**
     * For each transform type, the one-dimensional transform of the first pass (dav1d
     * {@code dav1d_tx1d_types[type][0]}).
     */
    static final byte[] FIRST_1D = {
            DCT, ADST, DCT, ADST, FLIPADST, DCT, FLIPADST, ADST, FLIPADST, IDENTITY, DCT, IDENTITY, ADST, IDENTITY,
            FLIPADST, IDENTITY
    };

    /**
     * For each transform type, the one-dimensional transform of the second pass (dav1d
     * {@code dav1d_tx1d_types[type][1]}).
     */
    static final byte[] SECOND_1D = {
            DCT, DCT, ADST, ADST, DCT, FLIPADST, FLIPADST, FLIPADST, ADST, IDENTITY, IDENTITY, DCT, IDENTITY, ADST,
            IDENTITY, FLIPADST
    };

    /**
     * For each transform type slot of the kernel table, the transform type the kernel in that slot
     * runs, as dav1d's {@code dav1d_itx_dsp_init} assigns them.
     */
    static final byte[] SLOT_TYPE = {0, 2, 1, 3, 5, 4, 6, 8, 7, 9, 11, 10, 13, 12, 15, 14};

    /**
     * For each rectangular transform size, the base-two logarithm of its width in units of four
     * samples (dav1d {@code dav1d_txfm_dimensions[].lw}).
     */
    static final byte[] LOG2_W = {0, 1, 2, 3, 4, 0, 1, 1, 2, 2, 3, 3, 4, 0, 2, 1, 3, 2, 4};

    /**
     * For each rectangular transform size, the base-two logarithm of its height in units of four
     * samples (dav1d {@code dav1d_txfm_dimensions[].lh}).
     */
    static final byte[] LOG2_H = {0, 1, 2, 3, 4, 1, 0, 2, 1, 3, 2, 4, 3, 2, 0, 3, 1, 4, 2};

    /**
     * For each rectangular transform size, the rounding shift between the two passes (the shift
     * argument of dav1d's {@code inv_txfm_fn} instantiations).
     */
    static final byte[] SHIFT = {0, 1, 2, 2, 2, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 2, 2, 2, 2};

    /**
     * For each rectangular transform size and end-of-block scan position, the highest row of the
     * coded area (a column in dav1d's transposed layout) any coefficient up to that position can
     * occupy (dav1d {@code dav1d_last_nonzero_col_from_eob}).
     */
    static final byte[][] LAST_NONZERO_COL = lastNonzeroColTables();

    /**
     * Creates the scalar implementation.
     */
    public ScalarItxDsp() {
    }

    @Override
    public void itxfmAdd(int txSize, int txType, short[] dst, int dstOff, int stride, int[] coef, int coefOff,
                         int eob, int[] scratch, int bitdepthMax) {
        if (!isSupported(txSize, txType)) {
            throw new IllegalArgumentException("unsupported transform: size " + txSize + ", type " + txType);
        }
        if (txType == WHT_WHT) {
            whtAdd(dst, dstOff, stride, coef, coefOff, scratch, bitdepthMax);
        } else {
            transformAdd(dst, dstOff, stride, coef, coefOff, eob, txSize, SLOT_TYPE[txType], scratch, bitdepthMax);
        }
    }

    /**
     * Inverse transforms a block with the DCT, ADST and identity transforms and adds it to the
     * destination.
     *
     * @implNote This implementation ports dav1d's {@code inv_txfm_add_c}.
     *
     * @param dst         the destination plane
     * @param dstOff      the index of the block's top-left sample
     * @param stride      the destination row stride
     * @param coef        the coefficient buffer, reset to zero where read
     * @param coefOff     the index of the block's first coefficient
     * @param eob         the scan position of the last coded coefficient
     * @param txSize      the rectangular transform size
     * @param txType      the transform type the kernel runs, after the slot mapping
     * @param tmp         the intermediate buffer, at least {@link #SCRATCH_INTS} entries
     * @param bitdepthMax the largest sample value
     */
    private static void transformAdd(short[] dst, int dstOff, int stride, int[] coef, int coefOff, int eob,
                                     int txSize, int txType, int[] tmp, int bitdepthMax) {
        int lw = LOG2_W[txSize];
        int lh = LOG2_H[txSize];
        var w = 4 << lw;
        var h = 4 << lh;
        int shift = SHIFT[txSize];
        var isRect2 = w * 2 == h || h * 2 == w;
        var rnd = (1 << shift) >> 1;
        if (txType == DCT_DCT && eob < 1) {
            dcOnlyAdd(dst, dstOff, stride, coef, coefOff, w, h, isRect2, shift, rnd, bitdepthMax);
            return;
        }
        int first = FIRST_1D[txType];
        int second = SECOND_1D[txType];
        var sh = Math.min(h, 32);
        var sw = Math.min(w, 32);
        int rowClipMin;
        int colClipMin;
        if (bitdepthMax == 255) {
            rowClipMin = Short.MIN_VALUE;
            colClipMin = Short.MIN_VALUE;
        } else {
            rowClipMin = ~bitdepthMax << 7;
            colClipMin = ~bitdepthMax << 5;
        }
        var rowClipMax = ~rowClipMin;
        var colClipMax = ~colClipMin;
        int lastNonzeroCol;
        if (second == IDENTITY && first != IDENTITY) {
            lastNonzeroCol = Math.min(sh - 1, eob);
        } else if (first == IDENTITY && second != IDENTITY) {
            lastNonzeroCol = eob >> (lw + 2);
        } else {
            lastNonzeroCol = LAST_NONZERO_COL[txSize][eob];
        }
        var row = 0;
        for (var y = 0; y <= lastNonzeroCol; y++, row += w) {
            var source = coefOff + y;
            if (isRect2) {
                for (var x = 0; x < sw; x++) {
                    tmp[row + x] = (coef[source + x * sh] * 181 + 128) >> 8;
                }
            } else {
                for (var x = 0; x < sw; x++) {
                    tmp[row + x] = coef[source + x * sh];
                }
            }
            transform1d(lw, first, tmp, row, 1, rowClipMin, rowClipMax);
        }
        if (lastNonzeroCol + 1 < sh) {
            Arrays.fill(tmp, row, row + (sh - lastNonzeroCol - 1) * w, 0);
        }
        Arrays.fill(coef, coefOff, coefOff + sw * sh, 0);
        var area = w * sh;
        for (var i = 0; i < area; i++) {
            tmp[i] = clip((tmp[i] + rnd) >> shift, colClipMin, colClipMax);
        }
        for (var x = 0; x < w; x++) {
            transform1d(lh, second, tmp, x, w, colClipMin, colClipMax);
        }
        var c = 0;
        for (var y = 0; y < h; y++) {
            var line = dstOff + y * stride;
            for (var x = 0; x < w; x++) {
                dst[line + x] = (short) clip(dst[line + x] + ((tmp[c++] + 8) >> 4), 0, bitdepthMax);
            }
        }
    }

    /**
     * Adds the inverse transform of a {@code DCT_DCT} block whose only coefficient is the DC one.
     *
     * @implNote This implementation ports the {@code eob < has_dconly} path of dav1d's
     *           {@code inv_txfm_add_c}.
     *
     * @param dst         the destination plane
     * @param dstOff      the index of the block's top-left sample
     * @param stride      the destination row stride
     * @param coef        the coefficient buffer, whose DC coefficient is reset to zero
     * @param coefOff     the index of the DC coefficient
     * @param w           the block width
     * @param h           the block height
     * @param isRect2     whether the block has a 1:2 or 2:1 aspect ratio
     * @param shift       the rounding shift between the passes
     * @param rnd         the rounding offset of {@code shift}
     * @param bitdepthMax the largest sample value
     */
    static void dcOnlyAdd(short[] dst, int dstOff, int stride, int[] coef, int coefOff, int w, int h,
                                  boolean isRect2, int shift, int rnd, int bitdepthMax) {
        var dc = coef[coefOff];
        coef[coefOff] = 0;
        if (isRect2) {
            dc = (dc * 181 + 128) >> 8;
        }
        dc = (dc * 181 + 128) >> 8;
        dc = (dc + rnd) >> shift;
        dc = (dc * 181 + 128 + 2048) >> 12;
        for (var y = 0; y < h; y++) {
            var line = dstOff + y * stride;
            for (var x = 0; x < w; x++) {
                dst[line + x] = (short) clip(dst[line + x] + dc, 0, bitdepthMax);
            }
        }
    }

    /**
     * Inverse transforms a lossless 4x4 block with the Walsh-Hadamard transform and adds it to the
     * destination.
     *
     * @implNote This implementation ports dav1d's {@code inv_txfm_add_wht_wht_4x4_c}.
     *
     * @param dst         the destination plane
     * @param dstOff      the index of the block's top-left sample
     * @param stride      the destination row stride
     * @param coef        the 16 coefficients, reset to zero
     * @param coefOff     the index of the first coefficient
     * @param tmp         the intermediate buffer, at least 16 entries
     * @param bitdepthMax the largest sample value
     */
    private static void whtAdd(short[] dst, int dstOff, int stride, int[] coef, int coefOff, int[] tmp,
                               int bitdepthMax) {
        for (var y = 0; y < 4; y++) {
            for (var x = 0; x < 4; x++) {
                tmp[y * 4 + x] = coef[coefOff + y + x * 4] >> 2;
            }
            wht4(tmp, y * 4, 1);
        }
        Arrays.fill(coef, coefOff, coefOff + 16, 0);
        for (var x = 0; x < 4; x++) {
            wht4(tmp, x, 4);
        }
        var c = 0;
        for (var y = 0; y < 4; y++) {
            var line = dstOff + y * stride;
            for (var x = 0; x < 4; x++) {
                dst[line + x] = (short) clip(dst[line + x] + tmp[c++], 0, bitdepthMax);
            }
        }
    }

    /**
     * Runs the one-dimensional transform of a pass in place.
     *
     * @implNote This implementation dispatches like dav1d's {@code dav1d_tx1d_fns} table; a
     *           flipped ADST is an ADST whose outputs are stored in reverse order.
     *
     * @param log2Size the base-two logarithm of the transform length in units of four samples
     * @param type     the one-dimensional transform: {@link #DCT}, {@link #ADST},
     *                 {@link #IDENTITY} or {@link #FLIPADST}
     * @param c        the values
     * @param o        the index of the first value
     * @param s        the distance between consecutive values
     * @param min      the lowest intermediate value
     * @param max      the highest intermediate value
     */
    static void transform1d(int log2Size, int type, int[] c, int o, int s, int min, int max) {
        switch (log2Size * 4 + type) {
            case 0 -> dct4(c, o, s, min, max, false);
            case 1 -> adst4(c, o, s, min, max, c, o, s);
            case 2 -> identity4(c, o, s);
            case 3 -> adst4(c, o, s, min, max, c, o + 3 * s, -s);
            case 4 -> dct8(c, o, s, min, max, false);
            case 5 -> adst8(c, o, s, min, max, c, o, s);
            case 6 -> identity8(c, o, s);
            case 7 -> adst8(c, o, s, min, max, c, o + 7 * s, -s);
            case 8 -> dct16(c, o, s, min, max, false);
            case 9 -> adst16(c, o, s, min, max, c, o, s);
            case 10 -> identity16(c, o, s);
            case 11 -> adst16(c, o, s, min, max, c, o + 15 * s, -s);
            case 12 -> dct32(c, o, s, min, max, false);
            case 14 -> identity32(c, o, s);
            case 16 -> dct64(c, o, s, min, max);
            default -> throw new IllegalArgumentException("no 1-d transform " + type + " of length " + (4 << log2Size));
        }
    }

    /**
     * Derives the end-of-block shortcut tables from the scans of {@link Scans#SCANS}, sharing one
     * table between the sizes that share a scan.
     *
     * @implNote This implementation follows dav1d's {@code init_tbl} in {@code src/scan.c}.
     *
     * @return the tables, indexed by rectangular transform size
     */
    private static byte[][] lastNonzeroColTables() {
        var tables = new byte[TX_SIZES][];
        for (var size = 0; size < TX_SIZES; size++) {
            var scan = Scans.SCANS[size];
            for (var earlier = 0; earlier < size && tables[size] == null; earlier++) {
                if (Scans.SCANS[earlier] == scan) {
                    tables[size] = tables[earlier];
                }
            }
            if (tables[size] == null) {
                var codedHeight = Math.min(4 << LOG2_H[size], 32);
                var table = new byte[scan.length];
                var max = 0;
                for (var n = 0; n < scan.length; n++) {
                    max = Math.max(max, scan[n] & (codedHeight - 1));
                    table[n] = (byte) max;
                }
                tables[size] = table;
            }
        }
        return tables;
    }

    /**
     * Clamps a value to a range.
     *
     * @param v   the value
     * @param min the lowest result
     * @param max the highest result
     * @return {@code v} clamped to {@code min..max}
     */
    private static int clip(int v, int min, int max) {
        return v < min ? min : Math.min(v, max);
    }

    /**
     * Computes a 4-point inverse identity transform in place: a scaling by {@code sqrt(2)}.
     *
     * @implNote This implementation ports dav1d's {@code inv_identity4_1d_c}.
     *
     * @param c the values
     * @param o the index of the first value
     * @param s the distance between consecutive values
     */
    private static void identity4(int[] c, int o, int s) {
        for (var i = 0; i < 4; i++) {
            var in = c[o + i * s];
            c[o + i * s] = in + ((in * 1697 + 2048) >> 12);
        }
    }

    /**
     * Computes an 8-point inverse identity transform in place: a scaling by 2.
     *
     * @implNote This implementation ports dav1d's {@code inv_identity8_1d_c}.
     *
     * @param c the values
     * @param o the index of the first value
     * @param s the distance between consecutive values
     */
    private static void identity8(int[] c, int o, int s) {
        for (var i = 0; i < 8; i++) {
            c[o + i * s] *= 2;
        }
    }

    /**
     * Computes a 16-point inverse identity transform in place: a scaling by {@code 2 * sqrt(2)}.
     *
     * @implNote This implementation ports dav1d's {@code inv_identity16_1d_c}.
     *
     * @param c the values
     * @param o the index of the first value
     * @param s the distance between consecutive values
     */
    private static void identity16(int[] c, int o, int s) {
        for (var i = 0; i < 16; i++) {
            var in = c[o + i * s];
            c[o + i * s] = 2 * in + ((in * 1697 + 1024) >> 11);
        }
    }

    /**
     * Computes a 32-point inverse identity transform in place: a scaling by 4.
     *
     * @implNote This implementation ports dav1d's {@code inv_identity32_1d_c}.
     *
     * @param c the values
     * @param o the index of the first value
     * @param s the distance between consecutive values
     */
    private static void identity32(int[] c, int o, int s) {
        for (var i = 0; i < 32; i++) {
            c[o + i * s] *= 4;
        }
    }

    /**
     * Computes a 4-point inverse DCT in place, clipping the intermediate values.
     *
     * <p>With {@code tx64} set, only the first half of the inputs is read (the others are zero by
     * construction), as the even half of a 64-point inverse DCT requires.
     *
     * @implNote This implementation ports dav1d's {@code inv_dct4_1d_internal_c}.
     *
     * @param c   the values, transformed in place
     * @param o   the index of the first value
     * @param s   the distance between consecutive values, positive
     * @param min the lowest intermediate value
     * @param max the highest intermediate value
     * @param tx64 whether only the first half of the inputs is read
     */
    private static void dct4(int[] c, int o, int s, int min, int max, boolean tx64) {
        int in0 = c[o + 0 * s], in1 = c[o + 1 * s];
        int t0, t1, t2, t3;
        if (tx64) {
            t0 = t1 = (in0 * 181 + 128) >> 8;
            t2 = (in1 * 1567 + 2048) >> 12;
            t3 = (in1 * 3784 + 2048) >> 12;
        } else {
            int in2 = c[o + 2 * s], in3 = c[o + 3 * s];
            t0 = ((in0 + in2) * 181 + 128) >> 8;
            t1 = ((in0 - in2) * 181 + 128) >> 8;
            t2 = ((in1 *  1567         - in3 * (3784 - 4096) + 2048) >> 12) - in3;
            t3 = ((in1 * (3784 - 4096) + in3 *  1567         + 2048) >> 12) + in1;
        }
        c[o + 0 * s] = clip(t0 + t3, min, max);
        c[o + 1 * s] = clip(t1 + t2, min, max);
        c[o + 2 * s] = clip(t1 - t2, min, max);
        c[o + 3 * s] = clip(t0 - t3, min, max);
    }

    /**
     * Computes an 8-point inverse DCT in place, clipping the intermediate values.
     *
     * <p>With {@code tx64} set, only the first half of the inputs is read (the others are zero by
     * construction), as the even half of a 64-point inverse DCT requires.
     *
     * @implNote This implementation ports dav1d's {@code inv_dct8_1d_internal_c}.
     *
     * @param c   the values, transformed in place
     * @param o   the index of the first value
     * @param s   the distance between consecutive values, positive
     * @param min the lowest intermediate value
     * @param max the highest intermediate value
     * @param tx64 whether only the first half of the inputs is read
     */
    private static void dct8(int[] c, int o, int s, int min, int max, boolean tx64) {
        dct4(c, o, s << 1, min, max, tx64);
        int in1 = c[o + 1 * s], in3 = c[o + 3 * s];
        int t4a, t5a, t6a, t7a;
        if (tx64) {
            t4a = (in1 *   799 + 2048) >> 12;
            t5a = (in3 * -2276 + 2048) >> 12;
            t6a = (in3 *  3406 + 2048) >> 12;
            t7a = (in1 *  4017 + 2048) >> 12;
        } else {
            int in5 = c[o + 5 * s], in7 = c[o + 7 * s];
            t4a = ((in1 *   799         - in7 * (4017 - 4096) + 2048) >> 12) - in7;
            t5a =  (in5 *  1703         - in3 *  1138         + 1024) >> 11;
            t6a =  (in5 *  1138         + in3 *  1703         + 1024) >> 11;
            t7a = ((in1 * (4017 - 4096) + in7 *  799          + 2048) >> 12) + in1;
        }
        int t4  = clip(t4a + t5a, min, max);
                  t5a = clip(t4a - t5a, min, max);
        int t7  = clip(t7a + t6a, min, max);
                  t6a = clip(t7a - t6a, min, max);
        int t5  = ((t6a - t5a) * 181 + 128) >> 8;
        int t6  = ((t6a + t5a) * 181 + 128) >> 8;
        int t0 = c[o + 0 * s];
        int t1 = c[o + 2 * s];
        int t2 = c[o + 4 * s];
        int t3 = c[o + 6 * s];
        c[o + 0 * s] = clip(t0 + t7, min, max);
        c[o + 1 * s] = clip(t1 + t6, min, max);
        c[o + 2 * s] = clip(t2 + t5, min, max);
        c[o + 3 * s] = clip(t3 + t4, min, max);
        c[o + 4 * s] = clip(t3 - t4, min, max);
        c[o + 5 * s] = clip(t2 - t5, min, max);
        c[o + 6 * s] = clip(t1 - t6, min, max);
        c[o + 7 * s] = clip(t0 - t7, min, max);
    }

    /**
     * Computes a 16-point inverse DCT in place, clipping the intermediate values.
     *
     * <p>With {@code tx64} set, only the first half of the inputs is read (the others are zero by
     * construction), as the even half of a 64-point inverse DCT requires.
     *
     * @implNote This implementation ports dav1d's {@code inv_dct16_1d_internal_c}.
     *
     * @param c   the values, transformed in place
     * @param o   the index of the first value
     * @param s   the distance between consecutive values, positive
     * @param min the lowest intermediate value
     * @param max the highest intermediate value
     * @param tx64 whether only the first half of the inputs is read
     */
    private static void dct16(int[] c, int o, int s, int min, int max, boolean tx64) {
        dct8(c, o, s << 1, min, max, tx64);
        int in1 = c[o + 1 * s], in3 = c[o + 3 * s];
        int in5 = c[o + 5 * s], in7 = c[o + 7 * s];
        int t8a, t9a, t10a, t11a, t12a, t13a, t14a, t15a;
        if (tx64) {
            t8a  = (in1 *   401 + 2048) >> 12;
            t9a  = (in7 * -2598 + 2048) >> 12;
            t10a = (in5 *  1931 + 2048) >> 12;
            t11a = (in3 * -1189 + 2048) >> 12;
            t12a = (in3 *  3920 + 2048) >> 12;
            t13a = (in5 *  3612 + 2048) >> 12;
            t14a = (in7 *  3166 + 2048) >> 12;
            t15a = (in1 *  4076 + 2048) >> 12;
        } else {
            int in9  = c[o + 9 * s], in11 = c[o + 11 * s];
            int in13 = c[o + 13 * s], in15 = c[o + 15 * s];
            t8a  = ((in1  *   401         - in15 * (4076 - 4096) + 2048) >> 12) - in15;
            t9a  =  (in9  *  1583         - in7  *  1299         + 1024) >> 11;
            t10a = ((in5  *  1931         - in11 * (3612 - 4096) + 2048) >> 12) - in11;
            t11a = ((in13 * (3920 - 4096) - in3  *  1189         + 2048) >> 12) + in13;
            t12a = ((in13 *  1189         + in3  * (3920 - 4096) + 2048) >> 12) + in3;
            t13a = ((in5  * (3612 - 4096) + in11 *  1931         + 2048) >> 12) + in5;
            t14a =  (in9  *  1299         + in7  *  1583         + 1024) >> 11;
            t15a = ((in1  * (4076 - 4096) + in15 *   401         + 2048) >> 12) + in1;
        }
        int t8  = clip(t8a  + t9a, min, max);
        int t9  = clip(t8a  - t9a, min, max);
        int t10 = clip(t11a - t10a, min, max);
        int t11 = clip(t11a + t10a, min, max);
        int t12 = clip(t12a + t13a, min, max);
        int t13 = clip(t12a - t13a, min, max);
        int t14 = clip(t15a - t14a, min, max);
        int t15 = clip(t15a + t14a, min, max);
        t9a  = ((  t14 *  1567         - t9  * (3784 - 4096)  + 2048) >> 12) - t9;
        t14a = ((  t14 * (3784 - 4096) + t9  *  1567          + 2048) >> 12) + t14;
        t10a = ((-(t13 * (3784 - 4096) + t10 *  1567)         + 2048) >> 12) - t13;
        t13a = ((  t13 *  1567         - t10 * (3784 - 4096)  + 2048) >> 12) - t10;
        t8a  = clip(t8   + t11, min, max);
        t9   = clip(t9a  + t10a, min, max);
        t10  = clip(t9a  - t10a, min, max);
        t11a = clip(t8   - t11, min, max);
        t12a = clip(t15  - t12, min, max);
        t13  = clip(t14a - t13a, min, max);
        t14  = clip(t14a + t13a, min, max);
        t15a = clip(t15  + t12, min, max);
        t10a = ((t13  - t10)  * 181 + 128) >> 8;
        t13a = ((t13  + t10)  * 181 + 128) >> 8;
        t11  = ((t12a - t11a) * 181 + 128) >> 8;
        t12  = ((t12a + t11a) * 181 + 128) >> 8;
        int t0 = c[o + 0 * s];
        int t1 = c[o + 2 * s];
        int t2 = c[o + 4 * s];
        int t3 = c[o + 6 * s];
        int t4 = c[o + 8 * s];
        int t5 = c[o + 10 * s];
        int t6 = c[o + 12 * s];
        int t7 = c[o + 14 * s];
        c[o + 0 * s] = clip(t0 + t15a, min, max);
        c[o + 1 * s] = clip(t1 + t14, min, max);
        c[o + 2 * s] = clip(t2 + t13a, min, max);
        c[o + 3 * s] = clip(t3 + t12, min, max);
        c[o + 4 * s] = clip(t4 + t11, min, max);
        c[o + 5 * s] = clip(t5 + t10a, min, max);
        c[o + 6 * s] = clip(t6 + t9, min, max);
        c[o + 7 * s] = clip(t7 + t8a, min, max);
        c[o + 8 * s] = clip(t7 - t8a, min, max);
        c[o + 9 * s] = clip(t6 - t9, min, max);
        c[o + 10 * s] = clip(t5 - t10a, min, max);
        c[o + 11 * s] = clip(t4 - t11, min, max);
        c[o + 12 * s] = clip(t3 - t12, min, max);
        c[o + 13 * s] = clip(t2 - t13a, min, max);
        c[o + 14 * s] = clip(t1 - t14, min, max);
        c[o + 15 * s] = clip(t0 - t15a, min, max);
    }

    /**
     * Computes a 32-point inverse DCT in place, clipping the intermediate values.
     *
     * <p>With {@code tx64} set, only the first half of the inputs is read (the others are zero by
     * construction), as the even half of a 64-point inverse DCT requires.
     *
     * @implNote This implementation ports dav1d's {@code inv_dct32_1d_internal_c}.
     *
     * @param c   the values, transformed in place
     * @param o   the index of the first value
     * @param s   the distance between consecutive values, positive
     * @param min the lowest intermediate value
     * @param max the highest intermediate value
     * @param tx64 whether only the first half of the inputs is read
     */
    private static void dct32(int[] c, int o, int s, int min, int max, boolean tx64) {
        dct16(c, o, s << 1, min, max, tx64);
        int in1  = c[o + 1 * s], in3  = c[o + 3 * s];
        int in5  = c[o + 5 * s], in7  = c[o + 7 * s];
        int in9  = c[o + 9 * s], in11 = c[o + 11 * s];
        int in13 = c[o + 13 * s], in15 = c[o + 15 * s];
        int t16a, t17a, t18a, t19a, t20a, t21a, t22a, t23a;
        int t24a, t25a, t26a, t27a, t28a, t29a, t30a, t31a;
        if (tx64) {
            t16a = (in1  *   201 + 2048) >> 12;
            t17a = (in15 * -2751 + 2048) >> 12;
            t18a = (in9  *  1751 + 2048) >> 12;
            t19a = (in7  * -1380 + 2048) >> 12;
            t20a = (in5  *   995 + 2048) >> 12;
            t21a = (in11 * -2106 + 2048) >> 12;
            t22a = (in13 *  2440 + 2048) >> 12;
            t23a = (in3  *  -601 + 2048) >> 12;
            t24a = (in3  *  4052 + 2048) >> 12;
            t25a = (in13 *  3290 + 2048) >> 12;
            t26a = (in11 *  3513 + 2048) >> 12;
            t27a = (in5  *  3973 + 2048) >> 12;
            t28a = (in7  *  3857 + 2048) >> 12;
            t29a = (in9  *  3703 + 2048) >> 12;
            t30a = (in15 *  3035 + 2048) >> 12;
            t31a = (in1  *  4091 + 2048) >> 12;
        } else {
            int in17 = c[o + 17 * s], in19 = c[o + 19 * s];
            int in21 = c[o + 21 * s], in23 = c[o + 23 * s];
            int in25 = c[o + 25 * s], in27 = c[o + 27 * s];
            int in29 = c[o + 29 * s], in31 = c[o + 31 * s];
            t16a = ((in1  *   201         - in31 * (4091 - 4096) + 2048) >> 12) - in31;
            t17a = ((in17 * (3035 - 4096) - in15 *  2751         + 2048) >> 12) + in17;
            t18a = ((in9  *  1751         - in23 * (3703 - 4096) + 2048) >> 12) - in23;
            t19a = ((in25 * (3857 - 4096) - in7  *  1380         + 2048) >> 12) + in25;
            t20a = ((in5  *   995         - in27 * (3973 - 4096) + 2048) >> 12) - in27;
            t21a = ((in21 * (3513 - 4096) - in11 *  2106         + 2048) >> 12) + in21;
            t22a =  (in13 *  1220         - in19 *  1645         + 1024) >> 11;
            t23a = ((in29 * (4052 - 4096) - in3  *   601         + 2048) >> 12) + in29;
            t24a = ((in29 *   601         + in3  * (4052 - 4096) + 2048) >> 12) + in3;
            t25a =  (in13 *  1645         + in19 *  1220         + 1024) >> 11;
            t26a = ((in21 *  2106         + in11 * (3513 - 4096) + 2048) >> 12) + in11;
            t27a = ((in5  * (3973 - 4096) + in27 *   995         + 2048) >> 12) + in5;
            t28a = ((in25 *  1380         + in7  * (3857 - 4096) + 2048) >> 12) + in7;
            t29a = ((in9  * (3703 - 4096) + in23 *  1751         + 2048) >> 12) + in9;
            t30a = ((in17 *  2751         + in15 * (3035 - 4096) + 2048) >> 12) + in15;
            t31a = ((in1  * (4091 - 4096) + in31 *   201         + 2048) >> 12) + in1;
        }
        int t16 = clip(t16a + t17a, min, max);
        int t17 = clip(t16a - t17a, min, max);
        int t18 = clip(t19a - t18a, min, max);
        int t19 = clip(t19a + t18a, min, max);
        int t20 = clip(t20a + t21a, min, max);
        int t21 = clip(t20a - t21a, min, max);
        int t22 = clip(t23a - t22a, min, max);
        int t23 = clip(t23a + t22a, min, max);
        int t24 = clip(t24a + t25a, min, max);
        int t25 = clip(t24a - t25a, min, max);
        int t26 = clip(t27a - t26a, min, max);
        int t27 = clip(t27a + t26a, min, max);
        int t28 = clip(t28a + t29a, min, max);
        int t29 = clip(t28a - t29a, min, max);
        int t30 = clip(t31a - t30a, min, max);
        int t31 = clip(t31a + t30a, min, max);
        t17a = ((  t30 *   799         - t17 * (4017 - 4096)  + 2048) >> 12) - t17;
        t30a = ((  t30 * (4017 - 4096) + t17 *   799          + 2048) >> 12) + t30;
        t18a = ((-(t29 * (4017 - 4096) + t18 *   799)         + 2048) >> 12) - t29;
        t29a = ((  t29 *   799         - t18 * (4017 - 4096)  + 2048) >> 12) - t18;
        t21a =  (  t26 *  1703         - t21 *  1138          + 1024) >> 11;
        t26a =  (  t26 *  1138         + t21 *  1703          + 1024) >> 11;
        t22a =  (-(t25 *  1138         + t22 *  1703        ) + 1024) >> 11;
        t25a =  (  t25 *  1703         - t22 *  1138          + 1024) >> 11;
        t16a = clip(t16  + t19, min, max);
        t17  = clip(t17a + t18a, min, max);
        t18  = clip(t17a - t18a, min, max);
        t19a = clip(t16  - t19, min, max);
        t20a = clip(t23  - t20, min, max);
        t21  = clip(t22a - t21a, min, max);
        t22  = clip(t22a + t21a, min, max);
        t23a = clip(t23  + t20, min, max);
        t24a = clip(t24  + t27, min, max);
        t25  = clip(t25a + t26a, min, max);
        t26  = clip(t25a - t26a, min, max);
        t27a = clip(t24  - t27, min, max);
        t28a = clip(t31  - t28, min, max);
        t29  = clip(t30a - t29a, min, max);
        t30  = clip(t30a + t29a, min, max);
        t31a = clip(t31  + t28, min, max);
        t18a = ((  t29  *  1567         - t18  * (3784 - 4096)  + 2048) >> 12) - t18;
        t29a = ((  t29  * (3784 - 4096) + t18  *  1567          + 2048) >> 12) + t29;
        t19  = ((  t28a *  1567         - t19a * (3784 - 4096)  + 2048) >> 12) - t19a;
        t28  = ((  t28a * (3784 - 4096) + t19a *  1567          + 2048) >> 12) + t28a;
        t20  = ((-(t27a * (3784 - 4096) + t20a *  1567)         + 2048) >> 12) - t27a;
        t27  = ((  t27a *  1567         - t20a * (3784 - 4096)  + 2048) >> 12) - t20a;
        t21a = ((-(t26  * (3784 - 4096) + t21  *  1567)         + 2048) >> 12) - t26;
        t26a = ((  t26  *  1567         - t21  * (3784 - 4096)  + 2048) >> 12) - t21;
        t16  = clip(t16a + t23a, min, max);
        t17a = clip(t17  + t22, min, max);
        t18  = clip(t18a + t21a, min, max);
        t19a = clip(t19  + t20, min, max);
        t20a = clip(t19  - t20, min, max);
        t21  = clip(t18a - t21a, min, max);
        t22a = clip(t17  - t22, min, max);
        t23  = clip(t16a - t23a, min, max);
        t24  = clip(t31a - t24a, min, max);
        t25a = clip(t30  - t25, min, max);
        t26  = clip(t29a - t26a, min, max);
        t27a = clip(t28  - t27, min, max);
        t28a = clip(t28  + t27, min, max);
        t29  = clip(t29a + t26a, min, max);
        t30a = clip(t30  + t25, min, max);
        t31  = clip(t31a + t24a, min, max);
        t20  = ((t27a - t20a) * 181 + 128) >> 8;
        t27  = ((t27a + t20a) * 181 + 128) >> 8;
        t21a = ((t26  - t21 ) * 181 + 128) >> 8;
        t26a = ((t26  + t21 ) * 181 + 128) >> 8;
        t22  = ((t25a - t22a) * 181 + 128) >> 8;
        t25  = ((t25a + t22a) * 181 + 128) >> 8;
        t23a = ((t24  - t23 ) * 181 + 128) >> 8;
        t24a = ((t24  + t23 ) * 181 + 128) >> 8;
        int t0  = c[o + 0 * s];
        int t1  = c[o + 2 * s];
        int t2  = c[o + 4 * s];
        int t3  = c[o + 6 * s];
        int t4  = c[o + 8 * s];
        int t5  = c[o + 10 * s];
        int t6  = c[o + 12 * s];
        int t7  = c[o + 14 * s];
        int t8  = c[o + 16 * s];
        int t9  = c[o + 18 * s];
        int t10 = c[o + 20 * s];
        int t11 = c[o + 22 * s];
        int t12 = c[o + 24 * s];
        int t13 = c[o + 26 * s];
        int t14 = c[o + 28 * s];
        int t15 = c[o + 30 * s];
        c[o + 0 * s] = clip(t0  + t31, min, max);
        c[o + 1 * s] = clip(t1  + t30a, min, max);
        c[o + 2 * s] = clip(t2  + t29, min, max);
        c[o + 3 * s] = clip(t3  + t28a, min, max);
        c[o + 4 * s] = clip(t4  + t27, min, max);
        c[o + 5 * s] = clip(t5  + t26a, min, max);
        c[o + 6 * s] = clip(t6  + t25, min, max);
        c[o + 7 * s] = clip(t7  + t24a, min, max);
        c[o + 8 * s] = clip(t8  + t23a, min, max);
        c[o + 9 * s] = clip(t9  + t22, min, max);
        c[o + 10 * s] = clip(t10 + t21a, min, max);
        c[o + 11 * s] = clip(t11 + t20, min, max);
        c[o + 12 * s] = clip(t12 + t19a, min, max);
        c[o + 13 * s] = clip(t13 + t18, min, max);
        c[o + 14 * s] = clip(t14 + t17a, min, max);
        c[o + 15 * s] = clip(t15 + t16, min, max);
        c[o + 16 * s] = clip(t15 - t16, min, max);
        c[o + 17 * s] = clip(t14 - t17a, min, max);
        c[o + 18 * s] = clip(t13 - t18, min, max);
        c[o + 19 * s] = clip(t12 - t19a, min, max);
        c[o + 20 * s] = clip(t11 - t20, min, max);
        c[o + 21 * s] = clip(t10 - t21a, min, max);
        c[o + 22 * s] = clip(t9  - t22, min, max);
        c[o + 23 * s] = clip(t8  - t23a, min, max);
        c[o + 24 * s] = clip(t7  - t24a, min, max);
        c[o + 25 * s] = clip(t6  - t25, min, max);
        c[o + 26 * s] = clip(t5  - t26a, min, max);
        c[o + 27 * s] = clip(t4  - t27, min, max);
        c[o + 28 * s] = clip(t3  - t28a, min, max);
        c[o + 29 * s] = clip(t2  - t29, min, max);
        c[o + 30 * s] = clip(t1  - t30a, min, max);
        c[o + 31 * s] = clip(t0  - t31, min, max);
    }

    /**
     * Computes a 64-point inverse DCT in place, reading only the first 32 inputs.
     *
     * @implNote This implementation ports dav1d's {@code inv_dct64_1d_c}.
     *
     * @param c   the values, transformed in place
     * @param o   the index of the first value
     * @param s   the distance between consecutive values, positive
     * @param min the lowest intermediate value
     * @param max the highest intermediate value
     */
    private static void dct64(int[] c, int o, int s, int min, int max) {
        dct32(c, o, s << 1, min, max, true);
        int in1  = c[o + 1 * s], in3  = c[o + 3 * s];
        int in5  = c[o + 5 * s], in7  = c[o + 7 * s];
        int in9  = c[o + 9 * s], in11 = c[o + 11 * s];
        int in13 = c[o + 13 * s], in15 = c[o + 15 * s];
        int in17 = c[o + 17 * s], in19 = c[o + 19 * s];
        int in21 = c[o + 21 * s], in23 = c[o + 23 * s];
        int in25 = c[o + 25 * s], in27 = c[o + 27 * s];
        int in29 = c[o + 29 * s], in31 = c[o + 31 * s];
        int t32a = (in1  *   101 + 2048) >> 12;
        int t33a = (in31 * -2824 + 2048) >> 12;
        int t34a = (in17 *  1660 + 2048) >> 12;
        int t35a = (in15 * -1474 + 2048) >> 12;
        int t36a = (in9  *   897 + 2048) >> 12;
        int t37a = (in23 * -2191 + 2048) >> 12;
        int t38a = (in25 *  2359 + 2048) >> 12;
        int t39a = (in7  *  -700 + 2048) >> 12;
        int t40a = (in5  *   501 + 2048) >> 12;
        int t41a = (in27 * -2520 + 2048) >> 12;
        int t42a = (in21 *  2019 + 2048) >> 12;
        int t43a = (in11 * -1092 + 2048) >> 12;
        int t44a = (in13 *  1285 + 2048) >> 12;
        int t45a = (in19 * -1842 + 2048) >> 12;
        int t46a = (in29 *  2675 + 2048) >> 12;
        int t47a = (in3  *  -301 + 2048) >> 12;
        int t48a = (in3  *  4085 + 2048) >> 12;
        int t49a = (in29 *  3102 + 2048) >> 12;
        int t50a = (in19 *  3659 + 2048) >> 12;
        int t51a = (in13 *  3889 + 2048) >> 12;
        int t52a = (in11 *  3948 + 2048) >> 12;
        int t53a = (in21 *  3564 + 2048) >> 12;
        int t54a = (in27 *  3229 + 2048) >> 12;
        int t55a = (in5  *  4065 + 2048) >> 12;
        int t56a = (in7  *  4036 + 2048) >> 12;
        int t57a = (in25 *  3349 + 2048) >> 12;
        int t58a = (in23 *  3461 + 2048) >> 12;
        int t59a = (in9  *  3996 + 2048) >> 12;
        int t60a = (in15 *  3822 + 2048) >> 12;
        int t61a = (in17 *  3745 + 2048) >> 12;
        int t62a = (in31 *  2967 + 2048) >> 12;
        int t63a = (in1  *  4095 + 2048) >> 12;
        int t32 = clip(t32a + t33a, min, max);
        int t33 = clip(t32a - t33a, min, max);
        int t34 = clip(t35a - t34a, min, max);
        int t35 = clip(t35a + t34a, min, max);
        int t36 = clip(t36a + t37a, min, max);
        int t37 = clip(t36a - t37a, min, max);
        int t38 = clip(t39a - t38a, min, max);
        int t39 = clip(t39a + t38a, min, max);
        int t40 = clip(t40a + t41a, min, max);
        int t41 = clip(t40a - t41a, min, max);
        int t42 = clip(t43a - t42a, min, max);
        int t43 = clip(t43a + t42a, min, max);
        int t44 = clip(t44a + t45a, min, max);
        int t45 = clip(t44a - t45a, min, max);
        int t46 = clip(t47a - t46a, min, max);
        int t47 = clip(t47a + t46a, min, max);
        int t48 = clip(t48a + t49a, min, max);
        int t49 = clip(t48a - t49a, min, max);
        int t50 = clip(t51a - t50a, min, max);
        int t51 = clip(t51a + t50a, min, max);
        int t52 = clip(t52a + t53a, min, max);
        int t53 = clip(t52a - t53a, min, max);
        int t54 = clip(t55a - t54a, min, max);
        int t55 = clip(t55a + t54a, min, max);
        int t56 = clip(t56a + t57a, min, max);
        int t57 = clip(t56a - t57a, min, max);
        int t58 = clip(t59a - t58a, min, max);
        int t59 = clip(t59a + t58a, min, max);
        int t60 = clip(t60a + t61a, min, max);
        int t61 = clip(t60a - t61a, min, max);
        int t62 = clip(t63a - t62a, min, max);
        int t63 = clip(t63a + t62a, min, max);
        t33a = ((t33 * (4096 - 4076) + t62 *   401         + 2048) >> 12) - t33;
        t34a = ((t34 *  -401         + t61 * (4096 - 4076) + 2048) >> 12) - t61;
        t37a =  (t37 * -1299         + t58 *  1583         + 1024) >> 11;
        t38a =  (t38 * -1583         + t57 * -1299         + 1024) >> 11;
        t41a = ((t41 * (4096 - 3612) + t54 *  1931         + 2048) >> 12) - t41;
        t42a = ((t42 * -1931         + t53 * (4096 - 3612) + 2048) >> 12) - t53;
        t45a = ((t45 * -1189         + t50 * (3920 - 4096) + 2048) >> 12) + t50;
        t46a = ((t46 * (4096 - 3920) + t49 * -1189         + 2048) >> 12) - t46;
        t49a = ((t46 * -1189         + t49 * (3920 - 4096) + 2048) >> 12) + t49;
        t50a = ((t45 * (3920 - 4096) + t50 *  1189         + 2048) >> 12) + t45;
        t53a = ((t42 * (4096 - 3612) + t53 *  1931         + 2048) >> 12) - t42;
        t54a = ((t41 *  1931         + t54 * (3612 - 4096) + 2048) >> 12) + t54;
        t57a =  (t38 * -1299         + t57 *  1583         + 1024) >> 11;
        t58a =  (t37 *  1583         + t58 *  1299         + 1024) >> 11;
        t61a = ((t34 * (4096 - 4076) + t61 *   401         + 2048) >> 12) - t34;
        t62a = ((t33 *   401         + t62 * (4076 - 4096) + 2048) >> 12) + t62;
        t32a = clip(t32  + t35, min, max);
        t33  = clip(t33a + t34a, min, max);
        t34  = clip(t33a - t34a, min, max);
        t35a = clip(t32  - t35, min, max);
        t36a = clip(t39  - t36, min, max);
        t37  = clip(t38a - t37a, min, max);
        t38  = clip(t38a + t37a, min, max);
        t39a = clip(t39  + t36, min, max);
        t40a = clip(t40  + t43, min, max);
        t41  = clip(t41a + t42a, min, max);
        t42  = clip(t41a - t42a, min, max);
        t43a = clip(t40  - t43, min, max);
        t44a = clip(t47  - t44, min, max);
        t45  = clip(t46a - t45a, min, max);
        t46  = clip(t46a + t45a, min, max);
        t47a = clip(t47  + t44, min, max);
        t48a = clip(t48  + t51, min, max);
        t49  = clip(t49a + t50a, min, max);
        t50  = clip(t49a - t50a, min, max);
        t51a = clip(t48  - t51, min, max);
        t52a = clip(t55  - t52, min, max);
        t53  = clip(t54a - t53a, min, max);
        t54  = clip(t54a + t53a, min, max);
        t55a = clip(t55  + t52, min, max);
        t56a = clip(t56  + t59, min, max);
        t57  = clip(t57a + t58a, min, max);
        t58  = clip(t57a - t58a, min, max);
        t59a = clip(t56  - t59, min, max);
        t60a = clip(t63  - t60, min, max);
        t61  = clip(t62a - t61a, min, max);
        t62  = clip(t62a + t61a, min, max);
        t63a = clip(t63  + t60, min, max);
        t34a = ((t34  * (4096 - 4017) + t61  *   799         + 2048) >> 12) - t34;
        t35  = ((t35a * (4096 - 4017) + t60a *   799         + 2048) >> 12) - t35a;
        t36  = ((t36a *  -799         + t59a * (4096 - 4017) + 2048) >> 12) - t59a;
        t37a = ((t37  *  -799         + t58  * (4096 - 4017) + 2048) >> 12) - t58;
        t42a =  (t42  * -1138         + t53  *  1703         + 1024) >> 11;
        t43  =  (t43a * -1138         + t52a *  1703         + 1024) >> 11;
        t44  =  (t44a * -1703         + t51a * -1138         + 1024) >> 11;
        t45a =  (t45  * -1703         + t50  * -1138         + 1024) >> 11;
        t50a =  (t45  * -1138         + t50  *  1703         + 1024) >> 11;
        t51  =  (t44a * -1138         + t51a *  1703         + 1024) >> 11;
        t52  =  (t43a *  1703         + t52a *  1138         + 1024) >> 11;
        t53a =  (t42  *  1703         + t53  *  1138         + 1024) >> 11;
        t58a = ((t37  * (4096 - 4017) + t58  *   799         + 2048) >> 12) - t37;
        t59  = ((t36a * (4096 - 4017) + t59a *   799         + 2048) >> 12) - t36a;
        t60  = ((t35a *   799         + t60a * (4017 - 4096) + 2048) >> 12) + t60a;
        t61a = ((t34  *   799         + t61  * (4017 - 4096) + 2048) >> 12) + t61;
        t32  = clip(t32a + t39a, min, max);
        t33a = clip(t33  + t38, min, max);
        t34  = clip(t34a + t37a, min, max);
        t35a = clip(t35  + t36, min, max);
        t36a = clip(t35  - t36, min, max);
        t37  = clip(t34a - t37a, min, max);
        t38a = clip(t33  - t38, min, max);
        t39  = clip(t32a - t39a, min, max);
        t40  = clip(t47a - t40a, min, max);
        t41a = clip(t46  - t41, min, max);
        t42  = clip(t45a - t42a, min, max);
        t43a = clip(t44  - t43, min, max);
        t44a = clip(t44  + t43, min, max);
        t45  = clip(t45a + t42a, min, max);
        t46a = clip(t46  + t41, min, max);
        t47  = clip(t47a + t40a, min, max);
        t48  = clip(t48a + t55a, min, max);
        t49a = clip(t49  + t54, min, max);
        t50  = clip(t50a + t53a, min, max);
        t51a = clip(t51  + t52, min, max);
        t52a = clip(t51  - t52, min, max);
        t53  = clip(t50a - t53a, min, max);
        t54a = clip(t49  - t54, min, max);
        t55  = clip(t48a - t55a, min, max);
        t56  = clip(t63a - t56a, min, max);
        t57a = clip(t62  - t57, min, max);
        t58  = clip(t61a - t58a, min, max);
        t59a = clip(t60  - t59, min, max);
        t60a = clip(t60  + t59, min, max);
        t61  = clip(t61a + t58a, min, max);
        t62a = clip(t62  + t57, min, max);
        t63  = clip(t63a + t56a, min, max);
        t36  = ((t36a * (4096 - 3784) + t59a *  1567         + 2048) >> 12) - t36a;
        t37a = ((t37  * (4096 - 3784) + t58  *  1567         + 2048) >> 12) - t37;
        t38  = ((t38a * (4096 - 3784) + t57a *  1567         + 2048) >> 12) - t38a;
        t39a = ((t39  * (4096 - 3784) + t56  *  1567         + 2048) >> 12) - t39;
        t40a = ((t40  * -1567         + t55  * (4096 - 3784) + 2048) >> 12) - t55;
        t41  = ((t41a * -1567         + t54a * (4096 - 3784) + 2048) >> 12) - t54a;
        t42a = ((t42  * -1567         + t53  * (4096 - 3784) + 2048) >> 12) - t53;
        t43  = ((t43a * -1567         + t52a * (4096 - 3784) + 2048) >> 12) - t52a;
        t52  = ((t43a * (4096 - 3784) + t52a *  1567         + 2048) >> 12) - t43a;
        t53a = ((t42  * (4096 - 3784) + t53  *  1567         + 2048) >> 12) - t42;
        t54  = ((t41a * (4096 - 3784) + t54a *  1567         + 2048) >> 12) - t41a;
        t55a = ((t40  * (4096 - 3784) + t55  *  1567         + 2048) >> 12) - t40;
        t56a = ((t39  *  1567         + t56  * (3784 - 4096) + 2048) >> 12) + t56;
        t57  = ((t38a *  1567         + t57a * (3784 - 4096) + 2048) >> 12) + t57a;
        t58a = ((t37  *  1567         + t58  * (3784 - 4096) + 2048) >> 12) + t58;
        t59  = ((t36a *  1567         + t59a * (3784 - 4096) + 2048) >> 12) + t59a;
        t32a = clip(t32  + t47, min, max);
        t33  = clip(t33a + t46a, min, max);
        t34a = clip(t34  + t45, min, max);
        t35  = clip(t35a + t44a, min, max);
        t36a = clip(t36  + t43, min, max);
        t37  = clip(t37a + t42a, min, max);
        t38a = clip(t38  + t41, min, max);
        t39  = clip(t39a + t40a, min, max);
        t40  = clip(t39a - t40a, min, max);
        t41a = clip(t38  - t41, min, max);
        t42  = clip(t37a - t42a, min, max);
        t43a = clip(t36  - t43, min, max);
        t44  = clip(t35a - t44a, min, max);
        t45a = clip(t34  - t45, min, max);
        t46  = clip(t33a - t46a, min, max);
        t47a = clip(t32  - t47, min, max);
        t48a = clip(t63  - t48, min, max);
        t49  = clip(t62a - t49a, min, max);
        t50a = clip(t61  - t50, min, max);
        t51  = clip(t60a - t51a, min, max);
        t52a = clip(t59  - t52, min, max);
        t53  = clip(t58a - t53a, min, max);
        t54a = clip(t57  - t54, min, max);
        t55  = clip(t56a - t55a, min, max);
        t56  = clip(t56a + t55a, min, max);
        t57a = clip(t57  + t54, min, max);
        t58  = clip(t58a + t53a, min, max);
        t59a = clip(t59  + t52, min, max);
        t60  = clip(t60a + t51a, min, max);
        t61a = clip(t61  + t50, min, max);
        t62  = clip(t62a + t49a, min, max);
        t63a = clip(t63  + t48, min, max);
        t40a = ((t55  - t40 ) * 181 + 128) >> 8;
        t41  = ((t54a - t41a) * 181 + 128) >> 8;
        t42a = ((t53  - t42 ) * 181 + 128) >> 8;
        t43  = ((t52a - t43a) * 181 + 128) >> 8;
        t44a = ((t51  - t44 ) * 181 + 128) >> 8;
        t45  = ((t50a - t45a) * 181 + 128) >> 8;
        t46a = ((t49  - t46 ) * 181 + 128) >> 8;
        t47  = ((t48a - t47a) * 181 + 128) >> 8;
        t48  = ((t47a + t48a) * 181 + 128) >> 8;
        t49a = ((t46  + t49 ) * 181 + 128) >> 8;
        t50  = ((t45a + t50a) * 181 + 128) >> 8;
        t51a = ((t44  + t51 ) * 181 + 128) >> 8;
        t52  = ((t43a + t52a) * 181 + 128) >> 8;
        t53a = ((t42  + t53 ) * 181 + 128) >> 8;
        t54  = ((t41a + t54a) * 181 + 128) >> 8;
        t55a = ((t40  + t55 ) * 181 + 128) >> 8;
        int t0  = c[o + 0 * s];
        int t1  = c[o + 2 * s];
        int t2  = c[o + 4 * s];
        int t3  = c[o + 6 * s];
        int t4  = c[o + 8 * s];
        int t5  = c[o + 10 * s];
        int t6  = c[o + 12 * s];
        int t7  = c[o + 14 * s];
        int t8  = c[o + 16 * s];
        int t9  = c[o + 18 * s];
        int t10 = c[o + 20 * s];
        int t11 = c[o + 22 * s];
        int t12 = c[o + 24 * s];
        int t13 = c[o + 26 * s];
        int t14 = c[o + 28 * s];
        int t15 = c[o + 30 * s];
        int t16 = c[o + 32 * s];
        int t17 = c[o + 34 * s];
        int t18 = c[o + 36 * s];
        int t19 = c[o + 38 * s];
        int t20 = c[o + 40 * s];
        int t21 = c[o + 42 * s];
        int t22 = c[o + 44 * s];
        int t23 = c[o + 46 * s];
        int t24 = c[o + 48 * s];
        int t25 = c[o + 50 * s];
        int t26 = c[o + 52 * s];
        int t27 = c[o + 54 * s];
        int t28 = c[o + 56 * s];
        int t29 = c[o + 58 * s];
        int t30 = c[o + 60 * s];
        int t31 = c[o + 62 * s];
        c[o + 0 * s] = clip(t0  + t63a, min, max);
        c[o + 1 * s] = clip(t1  + t62, min, max);
        c[o + 2 * s] = clip(t2  + t61a, min, max);
        c[o + 3 * s] = clip(t3  + t60, min, max);
        c[o + 4 * s] = clip(t4  + t59a, min, max);
        c[o + 5 * s] = clip(t5  + t58, min, max);
        c[o + 6 * s] = clip(t6  + t57a, min, max);
        c[o + 7 * s] = clip(t7  + t56, min, max);
        c[o + 8 * s] = clip(t8  + t55a, min, max);
        c[o + 9 * s] = clip(t9  + t54, min, max);
        c[o + 10 * s] = clip(t10 + t53a, min, max);
        c[o + 11 * s] = clip(t11 + t52, min, max);
        c[o + 12 * s] = clip(t12 + t51a, min, max);
        c[o + 13 * s] = clip(t13 + t50, min, max);
        c[o + 14 * s] = clip(t14 + t49a, min, max);
        c[o + 15 * s] = clip(t15 + t48, min, max);
        c[o + 16 * s] = clip(t16 + t47, min, max);
        c[o + 17 * s] = clip(t17 + t46a, min, max);
        c[o + 18 * s] = clip(t18 + t45, min, max);
        c[o + 19 * s] = clip(t19 + t44a, min, max);
        c[o + 20 * s] = clip(t20 + t43, min, max);
        c[o + 21 * s] = clip(t21 + t42a, min, max);
        c[o + 22 * s] = clip(t22 + t41, min, max);
        c[o + 23 * s] = clip(t23 + t40a, min, max);
        c[o + 24 * s] = clip(t24 + t39, min, max);
        c[o + 25 * s] = clip(t25 + t38a, min, max);
        c[o + 26 * s] = clip(t26 + t37, min, max);
        c[o + 27 * s] = clip(t27 + t36a, min, max);
        c[o + 28 * s] = clip(t28 + t35, min, max);
        c[o + 29 * s] = clip(t29 + t34a, min, max);
        c[o + 30 * s] = clip(t30 + t33, min, max);
        c[o + 31 * s] = clip(t31 + t32a, min, max);
        c[o + 32 * s] = clip(t31 - t32a, min, max);
        c[o + 33 * s] = clip(t30 - t33, min, max);
        c[o + 34 * s] = clip(t29 - t34a, min, max);
        c[o + 35 * s] = clip(t28 - t35, min, max);
        c[o + 36 * s] = clip(t27 - t36a, min, max);
        c[o + 37 * s] = clip(t26 - t37, min, max);
        c[o + 38 * s] = clip(t25 - t38a, min, max);
        c[o + 39 * s] = clip(t24 - t39, min, max);
        c[o + 40 * s] = clip(t23 - t40a, min, max);
        c[o + 41 * s] = clip(t22 - t41, min, max);
        c[o + 42 * s] = clip(t21 - t42a, min, max);
        c[o + 43 * s] = clip(t20 - t43, min, max);
        c[o + 44 * s] = clip(t19 - t44a, min, max);
        c[o + 45 * s] = clip(t18 - t45, min, max);
        c[o + 46 * s] = clip(t17 - t46a, min, max);
        c[o + 47 * s] = clip(t16 - t47, min, max);
        c[o + 48 * s] = clip(t15 - t48, min, max);
        c[o + 49 * s] = clip(t14 - t49a, min, max);
        c[o + 50 * s] = clip(t13 - t50, min, max);
        c[o + 51 * s] = clip(t12 - t51a, min, max);
        c[o + 52 * s] = clip(t11 - t52, min, max);
        c[o + 53 * s] = clip(t10 - t53a, min, max);
        c[o + 54 * s] = clip(t9  - t54, min, max);
        c[o + 55 * s] = clip(t8  - t55a, min, max);
        c[o + 56 * s] = clip(t7  - t56, min, max);
        c[o + 57 * s] = clip(t6  - t57a, min, max);
        c[o + 58 * s] = clip(t5  - t58, min, max);
        c[o + 59 * s] = clip(t4  - t59a, min, max);
        c[o + 60 * s] = clip(t3  - t60, min, max);
        c[o + 61 * s] = clip(t2  - t61a, min, max);
        c[o + 62 * s] = clip(t1  - t62, min, max);
        c[o + 63 * s] = clip(t0  - t63a, min, max);
    }

    /**
     * Computes a 4-point inverse ADST, reading every input before writing any output.
     *
     * @implNote This implementation ports dav1d's {@code inv_adst4_1d_internal_c}.
     *
     * @param in  the input values
     * @param io  the index of the first input
     * @param is  the distance between consecutive inputs, positive
     * @param min the lowest intermediate value
     * @param max the highest intermediate value
     * @param out the output values, which may be the input array
     * @param oo  the index of the first output
     * @param os  the distance between consecutive outputs, negative to store them reversed
     */
    private static void adst4(int[] in, int io, int is, int min, int max, int[] out, int oo, int os) {
        int in0 = in[io + 0 * is], in1 = in[io + 1 * is];
        int in2 = in[io + 2 * is], in3 = in[io + 3 * is];
        out[oo + 0 * os] = (( 1321         * in0 + (3803 - 4096) * in2 +
                           (2482 - 4096) * in3 + (3344 - 4096) * in1 + 2048) >> 12) +
                         in2 + in3 + in1;
        out[oo + 1 * os] = (((2482 - 4096) * in0 -  1321         * in2 -
                           (3803 - 4096) * in3 + (3344 - 4096) * in1 + 2048) >> 12) +
                         in0 - in3 + in1;
        out[oo + 2 * os] = (209 * (in0 - in2 + in3) + 128) >> 8;
        out[oo + 3 * os] = (((3803 - 4096) * in0 + (2482 - 4096) * in2 -
                            1321         * in3 - (3344 - 4096) * in1 + 2048) >> 12) +
                         in0 + in2 - in1;
    }

    /**
     * Computes an 8-point inverse ADST, reading every input before writing any output.
     *
     * @implNote This implementation ports dav1d's {@code inv_adst8_1d_internal_c}.
     *
     * @param in  the input values
     * @param io  the index of the first input
     * @param is  the distance between consecutive inputs, positive
     * @param min the lowest intermediate value
     * @param max the highest intermediate value
     * @param out the output values, which may be the input array
     * @param oo  the index of the first output
     * @param os  the distance between consecutive outputs, negative to store them reversed
     */
    private static void adst8(int[] in, int io, int is, int min, int max, int[] out, int oo, int os) {
        int in0 = in[io + 0 * is], in1 = in[io + 1 * is];
        int in2 = in[io + 2 * is], in3 = in[io + 3 * is];
        int in4 = in[io + 4 * is], in5 = in[io + 5 * is];
        int in6 = in[io + 6 * is], in7 = in[io + 7 * is];
        int t0a = (((4076 - 4096) * in7 +   401         * in0 + 2048) >> 12) + in7;
        int t1a = ((  401         * in7 - (4076 - 4096) * in0 + 2048) >> 12) - in0;
        int t2a = (((3612 - 4096) * in5 +  1931         * in2 + 2048) >> 12) + in5;
        int t3a = (( 1931         * in5 - (3612 - 4096) * in2 + 2048) >> 12) - in2;
              int t4a =  ( 1299         * in3 +  1583         * in4 + 1024) >> 11;
              int t5a =  ( 1583         * in3 -  1299         * in4 + 1024) >> 11;
              int t6a = (( 1189         * in1 + (3920 - 4096) * in6 + 2048) >> 12) + in6;
              int t7a = (((3920 - 4096) * in1 -  1189         * in6 + 2048) >> 12) + in1;
        int t0 = clip(t0a + t4a, min, max);
        int t1 = clip(t1a + t5a, min, max);
              int t2 = clip(t2a + t6a, min, max);
              int t3 = clip(t3a + t7a, min, max);
        int t4 = clip(t0a - t4a, min, max);
        int t5 = clip(t1a - t5a, min, max);
              int t6 = clip(t2a - t6a, min, max);
              int t7 = clip(t3a - t7a, min, max);
        t4a = (((3784 - 4096) * t4 +  1567         * t5 + 2048) >> 12) + t4;
        t5a = (( 1567         * t4 - (3784 - 4096) * t5 + 2048) >> 12) - t5;
        t6a = (((3784 - 4096) * t7 -  1567         * t6 + 2048) >> 12) + t7;
        t7a = (( 1567         * t7 + (3784 - 4096) * t6 + 2048) >> 12) + t6;
        out[oo + 0 * os] =  clip(t0  + t2 , min, max);
        out[oo + 7 * os] = -clip(t1  + t3 , min, max);
        t2             =  clip(t0  - t2 , min, max);
        t3             =  clip(t1  - t3 , min, max);
        out[oo + 1 * os] = -clip(t4a + t6a, min, max);
        out[oo + 6 * os] =  clip(t5a + t7a, min, max);
        t6             =  clip(t4a - t6a, min, max);
        t7             =  clip(t5a - t7a, min, max);
        out[oo + 3 * os] = -(((t2 + t3) * 181 + 128) >> 8);
        out[oo + 4 * os] =   ((t2 - t3) * 181 + 128) >> 8;
        out[oo + 2 * os] =   ((t6 + t7) * 181 + 128) >> 8;
        out[oo + 5 * os] = -(((t6 - t7) * 181 + 128) >> 8);
    }

    /**
     * Computes a 16-point inverse ADST, reading every input before writing any output.
     *
     * @implNote This implementation ports dav1d's {@code inv_adst16_1d_internal_c}.
     *
     * @param in  the input values
     * @param io  the index of the first input
     * @param is  the distance between consecutive inputs, positive
     * @param min the lowest intermediate value
     * @param max the highest intermediate value
     * @param out the output values, which may be the input array
     * @param oo  the index of the first output
     * @param os  the distance between consecutive outputs, negative to store them reversed
     */
    private static void adst16(int[] in, int io, int is, int min, int max, int[] out, int oo, int os) {
        int in0  = in[io + 0 * is], in1  = in[io + 1 * is];
        int in2  = in[io + 2 * is], in3  = in[io + 3 * is];
        int in4  = in[io + 4 * is], in5  = in[io + 5 * is];
        int in6  = in[io + 6 * is], in7  = in[io + 7 * is];
        int in8  = in[io + 8 * is], in9  = in[io + 9 * is];
        int in10 = in[io + 10 * is], in11 = in[io + 11 * is];
        int in12 = in[io + 12 * is], in13 = in[io + 13 * is];
        int in14 = in[io + 14 * is], in15 = in[io + 15 * is];
        int t0  = ((in15 * (4091 - 4096) + in0  *   201         + 2048) >> 12) + in15;
        int t1  = ((in15 *   201         - in0  * (4091 - 4096) + 2048) >> 12) - in0;
        int t2  = ((in13 * (3973 - 4096) + in2  *   995         + 2048) >> 12) + in13;
        int t3  = ((in13 *   995         - in2  * (3973 - 4096) + 2048) >> 12) - in2;
        int t4  = ((in11 * (3703 - 4096) + in4  *  1751         + 2048) >> 12) + in11;
        int t5  = ((in11 *  1751         - in4  * (3703 - 4096) + 2048) >> 12) - in4;
        int t6  =  (in9  *  1645         + in6  *  1220         + 1024) >> 11;
        int t7  =  (in9  *  1220         - in6  *  1645         + 1024) >> 11;
        int t8  = ((in7  *  2751         + in8  * (3035 - 4096) + 2048) >> 12) + in8;
        int t9  = ((in7  * (3035 - 4096) - in8  *  2751         + 2048) >> 12) + in7;
        int t10 = ((in5  *  2106         + in10 * (3513 - 4096) + 2048) >> 12) + in10;
        int t11 = ((in5  * (3513 - 4096) - in10 *  2106         + 2048) >> 12) + in5;
        int t12 = ((in3  *  1380         + in12 * (3857 - 4096) + 2048) >> 12) + in12;
        int t13 = ((in3  * (3857 - 4096) - in12 *  1380         + 2048) >> 12) + in3;
        int t14 = ((in1  *   601         + in14 * (4052 - 4096) + 2048) >> 12) + in14;
        int t15 = ((in1  * (4052 - 4096) - in14 *   601         + 2048) >> 12) + in1;
        int t0a  = clip(t0 + t8 , min, max);
        int t1a  = clip(t1 + t9 , min, max);
        int t2a  = clip(t2 + t10, min, max);
        int t3a  = clip(t3 + t11, min, max);
        int t4a  = clip(t4 + t12, min, max);
        int t5a  = clip(t5 + t13, min, max);
        int t6a  = clip(t6 + t14, min, max);
        int t7a  = clip(t7 + t15, min, max);
        int t8a  = clip(t0 - t8 , min, max);
        int t9a  = clip(t1 - t9 , min, max);
        int t10a = clip(t2 - t10, min, max);
        int t11a = clip(t3 - t11, min, max);
        int t12a = clip(t4 - t12, min, max);
        int t13a = clip(t5 - t13, min, max);
        int t14a = clip(t6 - t14, min, max);
        int t15a = clip(t7 - t15, min, max);
        t8   = ((t8a  * (4017 - 4096) + t9a  *   799         + 2048) >> 12) + t8a;
        t9   = ((t8a  *   799         - t9a  * (4017 - 4096) + 2048) >> 12) - t9a;
        t10  = ((t10a *  2276         + t11a * (3406 - 4096) + 2048) >> 12) + t11a;
        t11  = ((t10a * (3406 - 4096) - t11a *  2276         + 2048) >> 12) + t10a;
        t12  = ((t13a * (4017 - 4096) - t12a *   799         + 2048) >> 12) + t13a;
        t13  = ((t13a *   799         + t12a * (4017 - 4096) + 2048) >> 12) + t12a;
        t14  = ((t15a *  2276         - t14a * (3406 - 4096) + 2048) >> 12) - t14a;
        t15  = ((t15a * (3406 - 4096) + t14a *  2276         + 2048) >> 12) + t15a;
        t0   = clip(t0a + t4a, min, max);
        t1   = clip(t1a + t5a, min, max);
        t2   = clip(t2a + t6a, min, max);
        t3   = clip(t3a + t7a, min, max);
        t4   = clip(t0a - t4a, min, max);
        t5   = clip(t1a - t5a, min, max);
        t6   = clip(t2a - t6a, min, max);
        t7   = clip(t3a - t7a, min, max);
        t8a  = clip(t8  + t12, min, max);
        t9a  = clip(t9  + t13, min, max);
        t10a = clip(t10 + t14, min, max);
        t11a = clip(t11 + t15, min, max);
        t12a = clip(t8  - t12, min, max);
        t13a = clip(t9  - t13, min, max);
        t14a = clip(t10 - t14, min, max);
        t15a = clip(t11 - t15, min, max);
        t4a  = ((t4   * (3784 - 4096) + t5   *  1567         + 2048) >> 12) + t4;
        t5a  = ((t4   *  1567         - t5   * (3784 - 4096) + 2048) >> 12) - t5;
        t6a  = ((t7   * (3784 - 4096) - t6   *  1567         + 2048) >> 12) + t7;
        t7a  = ((t7   *  1567         + t6   * (3784 - 4096) + 2048) >> 12) + t6;
        t12  = ((t12a * (3784 - 4096) + t13a *  1567         + 2048) >> 12) + t12a;
        t13  = ((t12a *  1567         - t13a * (3784 - 4096) + 2048) >> 12) - t13a;
        t14  = ((t15a * (3784 - 4096) - t14a *  1567         + 2048) >> 12) + t15a;
        t15  = ((t15a *  1567         + t14a * (3784 - 4096) + 2048) >> 12) + t14a;
        out[oo + 0 * os] =  clip(t0  + t2  , min, max);
        out[oo + 15 * os] = -clip(t1  + t3  , min, max);
        t2a             =  clip(t0  - t2  , min, max);
        t3a             =  clip(t1  - t3  , min, max);
        out[oo + 3 * os] = -clip(t4a + t6a , min, max);
        out[oo + 12 * os] =  clip(t5a + t7a , min, max);
        t6              =  clip(t4a - t6a , min, max);
        t7              =  clip(t5a - t7a , min, max);
        out[oo + 1 * os] = -clip(t8a + t10a, min, max);
        out[oo + 14 * os] =  clip(t9a + t11a, min, max);
        t10             =  clip(t8a - t10a, min, max);
        t11             =  clip(t9a - t11a, min, max);
        out[oo + 2 * os] =  clip(t12 + t14 , min, max);
        out[oo + 13 * os] = -clip(t13 + t15 , min, max);
        t14a            =  clip(t12 - t14 , min, max);
        t15a            =  clip(t13 - t15 , min, max);
        out[oo + 7 * os] = -(((t2a  + t3a)  * 181 + 128) >> 8);
        out[oo + 8 * os] =   ((t2a  - t3a)  * 181 + 128) >> 8;
        out[oo + 4 * os] =   ((t6   + t7)   * 181 + 128) >> 8;
        out[oo + 11 * os] = -(((t6   - t7)   * 181 + 128) >> 8);
        out[oo + 6 * os] =   ((t10  + t11)  * 181 + 128) >> 8;
        out[oo + 9 * os] = -(((t10  - t11)  * 181 + 128) >> 8);
        out[oo + 5 * os] = -(((t14a + t15a) * 181 + 128) >> 8);
        out[oo + 10 * os] =   ((t14a - t15a) * 181 + 128) >> 8;
    }

    /**
     * Computes a 4-point inverse Walsh-Hadamard transform in place.
     *
     * @implNote This implementation ports dav1d's {@code dav1d_inv_wht4_1d_c}.
     *
     * @param c the values, transformed in place
     * @param o the index of the first value
     * @param s the distance between consecutive values, positive
     */
    private static void wht4(int[] c, int o, int s) {
        int in0 = c[o + 0 * s], in1 = c[o + 1 * s];
        int in2 = c[o + 2 * s], in3 = c[o + 3 * s];
        int t0 = in0 + in1;
        int t2 = in2 - in3;
        int t4 = (t0 - t2) >> 1;
        int t3 = t4 - in3;
        int t1 = t4 - in1;
        c[o + 0 * s] = t0 - t3;
        c[o + 1 * s] = t3;
        c[o + 2 * s] = t1;
        c[o + 3 * s] = t2 + t1;
    }
}
