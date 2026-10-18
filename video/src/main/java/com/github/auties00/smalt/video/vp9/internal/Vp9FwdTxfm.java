/* Ports libvpx 1.17.0 (vpx_dsp/fwd_txfm.c, vp9/encoder/vp9_dct.c). Copyright (c) 2010, The WebM Project authors;
   BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.video.vp9.internal;

/**
 * Computes the forward transforms of the VP9 encoder: the discrete cosine transforms of every
 * size, the hybrid cosine and sine transforms of intra blocks, and the Walsh-Hadamard transform of
 * lossless blocks.
 *
 * <p>Every transform reads a block of residuals, {@code short}s with a row stride, and writes its
 * coefficients in raster order, row {@code r} holding vertical frequency {@code r}. The results
 * are those of libvpx's reference C code with 64-bit intermediates, which never overflow for
 * 8-bit residuals.
 *
 * @implNote This implementation ports libvpx's {@code vpx_dsp/fwd_txfm.c} ({@code vpx_fdct4x4_c},
 * {@code vpx_fdct8x8_c}, {@code vpx_fdct16x16_c}, {@code vpx_fdct32x32_c},
 * {@code vpx_fdct32x32_rd_c}) and {@code vp9/encoder/vp9_dct.c} ({@code vp9_fht4x4_c},
 * {@code vp9_fht8x8_c}, {@code vp9_fht16x16_c}, {@code vp9_fwht4x4_c}).
 */
final class Vp9FwdTxfm {
    /**
     * {@code round(16384 * cos(k * pi / 64))} for {@code k} from 0 to 31; entry 0 is unused.
     */
    private static final int[] COSPI = {
            16384, 16364, 16305, 16207, 16069, 15893, 15679, 15426, 15137, 14811, 14449, 14053, 13623, 13160, 12665,
            12140, 11585, 11003, 10394, 9760, 9102, 8423, 7723, 7005, 6270, 5520, 4756, 3981, 3196, 2404, 1606, 804
    };

    /**
     * {@code cospi_1_64}.
     */
    private static final long C1 = COSPI[1];

    /**
     * {@code cospi_2_64}.
     */
    private static final long C2 = COSPI[2];

    /**
     * {@code cospi_3_64}.
     */
    private static final long C3 = COSPI[3];

    /**
     * {@code cospi_4_64}.
     */
    private static final long C4 = COSPI[4];

    /**
     * {@code cospi_5_64}.
     */
    private static final long C5 = COSPI[5];

    /**
     * {@code cospi_6_64}.
     */
    private static final long C6 = COSPI[6];

    /**
     * {@code cospi_7_64}.
     */
    private static final long C7 = COSPI[7];

    /**
     * {@code cospi_8_64}.
     */
    private static final long C8 = COSPI[8];

    /**
     * {@code cospi_9_64}.
     */
    private static final long C9 = COSPI[9];

    /**
     * {@code cospi_10_64}.
     */
    private static final long C10 = COSPI[10];

    /**
     * {@code cospi_11_64}.
     */
    private static final long C11 = COSPI[11];

    /**
     * {@code cospi_12_64}.
     */
    private static final long C12 = COSPI[12];

    /**
     * {@code cospi_13_64}.
     */
    private static final long C13 = COSPI[13];

    /**
     * {@code cospi_14_64}.
     */
    private static final long C14 = COSPI[14];

    /**
     * {@code cospi_15_64}.
     */
    private static final long C15 = COSPI[15];

    /**
     * {@code cospi_16_64}.
     */
    private static final long C16 = COSPI[16];

    /**
     * {@code cospi_17_64}.
     */
    private static final long C17 = COSPI[17];

    /**
     * {@code cospi_18_64}.
     */
    private static final long C18 = COSPI[18];

    /**
     * {@code cospi_19_64}.
     */
    private static final long C19 = COSPI[19];

    /**
     * {@code cospi_20_64}.
     */
    private static final long C20 = COSPI[20];

    /**
     * {@code cospi_21_64}.
     */
    private static final long C21 = COSPI[21];

    /**
     * {@code cospi_22_64}.
     */
    private static final long C22 = COSPI[22];

    /**
     * {@code cospi_23_64}.
     */
    private static final long C23 = COSPI[23];

    /**
     * {@code cospi_24_64}.
     */
    private static final long C24 = COSPI[24];

    /**
     * {@code cospi_25_64}.
     */
    private static final long C25 = COSPI[25];

    /**
     * {@code cospi_26_64}.
     */
    private static final long C26 = COSPI[26];

    /**
     * {@code cospi_27_64}.
     */
    private static final long C27 = COSPI[27];

    /**
     * {@code cospi_28_64}.
     */
    private static final long C28 = COSPI[28];

    /**
     * {@code cospi_29_64}.
     */
    private static final long C29 = COSPI[29];

    /**
     * {@code cospi_30_64}.
     */
    private static final long C30 = COSPI[30];

    /**
     * {@code cospi_31_64}.
     */
    private static final long C31 = COSPI[31];

    /**
     * {@code sinpi_1_9}, the first constant of the 4-point sine transform.
     */
    private static final long S1 = 5283;

    /**
     * {@code sinpi_2_9}.
     */
    private static final long S2 = 9929;

    /**
     * {@code sinpi_3_9}.
     */
    private static final long S3 = 13377;

    /**
     * {@code sinpi_4_9}.
     */
    private static final long S4 = 15212;

    /**
     * Prevents instantiation.
     */
    private Vp9FwdTxfm() {
        throw new AssertionError();
    }

    /**
     * Rounds a fixed-point product back to integer precision ({@code fdct_round_shift}).
     *
     * @param value the product, scaled by 2^14
     * @return the rounded value
     */
    private static long round(long value) {
        return (value + 8192) >> 14;
    }

    /**
     * Computes the 4x4 discrete cosine transform ({@code vpx_fdct4x4_c}).
     *
     * @param in the residuals
     * @param inOff the index of the first residual
     * @param stride the residual row stride
     * @param out the coefficients, 16 entries
     * @param outOff the index of the first coefficient
     * @param tmp a scratch array of at least 16 entries
     */
    static void fdct4x4(short[] in, int inOff, int stride, int[] out, int outOff, long[] tmp) {
        for (var i = 0; i < 4; i++) {
            long a0 = in[inOff + i] * 16;
            long a1 = in[inOff + stride + i] * 16;
            long a2 = in[inOff + 2 * stride + i] * 16;
            long a3 = in[inOff + 3 * stride + i] * 16;
            if (i == 0 && a0 != 0) {
                a0++;
            }
            fdct4Into(a0, a1, a2, a3, tmp, i * 4);
        }
        for (var i = 0; i < 4; i++) {
            fdct4Into(tmp[i], tmp[4 + i], tmp[8 + i], tmp[12 + i], tmp, 16 + i * 4);
        }
        for (var i = 0; i < 16; i++) {
            out[outOff + i] = (int) ((tmp[16 + i] + 1) >> 2);
        }
    }

    /**
     * Computes one 4-point cosine transform into a scratch array.
     *
     * @param a0 input 0
     * @param a1 input 1
     * @param a2 input 2
     * @param a3 input 3
     * @param out the destination
     * @param off the index of output 0
     */
    private static void fdct4Into(long a0, long a1, long a2, long a3, long[] out, int off) {
        var s0 = a0 + a3;
        var s1 = a1 + a2;
        var s2 = a1 - a2;
        var s3 = a0 - a3;
        out[off] = round((s0 + s1) * C16);
        out[off + 2] = round((s0 - s1) * C16);
        out[off + 1] = round(s2 * C24 + s3 * C8);
        out[off + 3] = round(-s2 * C8 + s3 * C24);
    }

    /**
     * Computes the 8x8 discrete cosine transform ({@code vpx_fdct8x8_c}).
     *
     * @param in the residuals
     * @param inOff the index of the first residual
     * @param stride the residual row stride
     * @param out the coefficients, 64 entries
     * @param outOff the index of the first coefficient
     * @param tmp a scratch array of at least 128 entries
     */
    static void fdct8x8(short[] in, int inOff, int stride, int[] out, int outOff, long[] tmp) {
        for (var i = 0; i < 8; i++) {
            var p = inOff + i;
            long x0 = in[p], x1 = in[p + stride], x2 = in[p + 2 * stride], x3 = in[p + 3 * stride];
            long x4 = in[p + 4 * stride], x5 = in[p + 5 * stride], x6 = in[p + 6 * stride], x7 = in[p + 7 * stride];
            fdct8Pass(x0 * 4, x1 * 4, x2 * 4, x3 * 4, x4 * 4, x5 * 4, x6 * 4, x7 * 4, tmp, i * 8);
        }
        for (var i = 0; i < 8; i++) {
            fdct8Pass(tmp[i], tmp[8 + i], tmp[16 + i], tmp[24 + i], tmp[32 + i], tmp[40 + i], tmp[48 + i], tmp[56 + i],
                    tmp, 64 + i * 8);
        }
        for (var i = 0; i < 64; i++) {
            out[outOff + i] = (int) (tmp[64 + i] / 2);
        }
    }

    /**
     * Computes one pass of the 8-point cosine transform of {@code vpx_fdct8x8_c} on its eight
     * inputs.
     *
     * @param i0 input 0
     * @param i1 input 1
     * @param i2 input 2
     * @param i3 input 3
     * @param i4 input 4
     * @param i5 input 5
     * @param i6 input 6
     * @param i7 input 7
     * @param out the destination
     * @param off the index of output 0
     */
    private static void fdct8Pass(long i0, long i1, long i2, long i3, long i4, long i5, long i6, long i7, long[] out,
                                  int off) {
        var s0 = i0 + i7;
        var s1 = i1 + i6;
        var s2 = i2 + i5;
        var s3 = i3 + i4;
        var s4 = i3 - i4;
        var s5 = i2 - i5;
        var s6 = i1 - i6;
        var s7 = i0 - i7;
        var x0 = s0 + s3;
        var x1 = s1 + s2;
        var x2 = s1 - s2;
        var x3 = s0 - s3;
        out[off] = round((x0 + x1) * C16);
        out[off + 4] = round((x0 - x1) * C16);
        out[off + 2] = round(x2 * C24 + x3 * C8);
        out[off + 6] = round(-x2 * C8 + x3 * C24);
        var t2 = round((s6 - s5) * C16);
        var t3 = round((s6 + s5) * C16);
        x0 = s4 + t2;
        x1 = s4 - t2;
        x2 = s7 - t3;
        x3 = s7 + t3;
        out[off + 1] = round(x0 * C28 + x3 * C4);
        out[off + 3] = round(x2 * C12 + x1 * -C20);
        out[off + 5] = round(x1 * C12 + x2 * C20);
        out[off + 7] = round(x3 * C28 + x0 * -C4);
    }

    /**
     * Computes the 16x16 discrete cosine transform ({@code vpx_fdct16x16_c}).
     *
     * @param in the residuals
     * @param inOff the index of the first residual
     * @param stride the residual row stride
     * @param out the coefficients, 256 entries
     * @param outOff the index of the first coefficient
     * @param tmp a scratch array of at least 288 entries
     */
    static void fdct16x16(short[] in, int inOff, int stride, int[] out, int outOff, long[] tmp) {
        var col = 256;
        for (var i = 0; i < 16; i++) {
            for (var j = 0; j < 16; j++) {
                tmp[col + j] = in[inOff + j * stride + i] * 4L;
            }
            fdct16(tmp, col, tmp, i * 16);
        }
        for (var i = 0; i < 16; i++) {
            for (var j = 0; j < 16; j++) {
                tmp[col + j] = (tmp[j * 16 + i] + 1) >> 2;
            }
            fdct16(tmp, col, tmp, col + 16);
            for (var j = 0; j < 16; j++) {
                out[outOff + i * 16 + j] = (int) tmp[col + 16 + j];
            }
        }
    }

    /**
     * Computes the 16-point cosine transform of 16 inputs into an array.
     *
     * @param in the array holding the 16 inputs
     * @param inOff the index of the first input
     * @param out the destination
     * @param outOff the index of output 0
     */
    private static void fdct16(long[] in, int inOff, long[] out, int outOff) {
        fdct16Core(in[inOff], in[inOff + 1], in[inOff + 2], in[inOff + 3], in[inOff + 4], in[inOff + 5],
                in[inOff + 6], in[inOff + 7], in[inOff + 8], in[inOff + 9], in[inOff + 10], in[inOff + 11],
                in[inOff + 12], in[inOff + 13], in[inOff + 14], in[inOff + 15], out, outOff);
    }

    /**
     * Computes the 16-point cosine transform shared by both passes of {@code vpx_fdct16x16_c}
     * ({@code fdct16} of {@code vp9_dct.c}).
     *
     * @param i0 input 0
     * @param i1 input 1
     * @param i2 input 2
     * @param i3 input 3
     * @param i4 input 4
     * @param i5 input 5
     * @param i6 input 6
     * @param i7 input 7
     * @param i8 input 8
     * @param i9 input 9
     * @param i10 input 10
     * @param i11 input 11
     * @param i12 input 12
     * @param i13 input 13
     * @param i14 input 14
     * @param i15 input 15
     * @param out the destination
     * @param outOff the index of output 0
     */
    private static void fdct16Core(long i0, long i1, long i2, long i3, long i4, long i5, long i6, long i7, long i8,
                                   long i9, long i10, long i11, long i12, long i13, long i14, long i15, long[] out,
                                   int outOff) {
        var in0 = i0 + i15;
        var in1 = i1 + i14;
        var in2 = i2 + i13;
        var in3 = i3 + i12;
        var in4 = i4 + i11;
        var in5 = i5 + i10;
        var in6 = i6 + i9;
        var in7 = i7 + i8;
        var st0 = i7 - i8;
        var st1 = i6 - i9;
        var st2 = i5 - i10;
        var st3 = i4 - i11;
        var st4 = i3 - i12;
        var st5 = i2 - i13;
        var st6 = i1 - i14;
        var st7 = i0 - i15;
        var s0 = in0 + in7;
        var s1 = in1 + in6;
        var s2 = in2 + in5;
        var s3 = in3 + in4;
        var s4 = in3 - in4;
        var s5 = in2 - in5;
        var s6 = in1 - in6;
        var s7 = in0 - in7;
        var x0 = s0 + s3;
        var x1 = s1 + s2;
        var x2 = s1 - s2;
        var x3 = s0 - s3;
        out[outOff] = round((x0 + x1) * C16);
        out[outOff + 4] = round(x3 * C8 + x2 * C24);
        out[outOff + 8] = round((x0 - x1) * C16);
        out[outOff + 12] = round(x3 * C24 - x2 * C8);
        var t2 = round((s6 - s5) * C16);
        var t3 = round((s6 + s5) * C16);
        x0 = s4 + t2;
        x1 = s4 - t2;
        x2 = s7 - t3;
        x3 = s7 + t3;
        out[outOff + 2] = round(x0 * C28 + x3 * C4);
        out[outOff + 6] = round(x2 * C12 + x1 * -C20);
        out[outOff + 10] = round(x1 * C12 + x2 * C20);
        out[outOff + 14] = round(x3 * C28 + x0 * -C4);
        var b2 = round((st5 - st2) * C16);
        var b3 = round((st4 - st3) * C16);
        var b4 = round((st4 + st3) * C16);
        var b5 = round((st5 + st2) * C16);
        var c0 = st0 + b3;
        var c1 = st1 + b2;
        var c2 = st1 - b2;
        var c3 = st0 - b3;
        var c4 = st7 - b4;
        var c5 = st6 - b5;
        var c6 = st6 + b5;
        var c7 = st7 + b4;
        var d1 = round(c1 * -C8 + c6 * C24);
        var d2 = round(c2 * C24 + c5 * C8);
        var d5 = round(c2 * C8 - c5 * C24);
        var d6 = round(c1 * C24 + c6 * C8);
        var e0 = c0 + d1;
        var e1 = c0 - d1;
        var e2 = c3 + d2;
        var e3 = c3 - d2;
        var e4 = c4 - d5;
        var e5 = c4 + d5;
        var e6 = c7 - d6;
        var e7 = c7 + d6;
        out[outOff + 1] = round(e0 * C30 + e7 * C2);
        out[outOff + 9] = round(e1 * C14 + e6 * C18);
        out[outOff + 5] = round(e2 * C22 + e5 * C10);
        out[outOff + 13] = round(e3 * C6 + e4 * C26);
        out[outOff + 3] = round(e3 * -C26 + e4 * C6);
        out[outOff + 11] = round(e2 * -C10 + e5 * C22);
        out[outOff + 7] = round(e1 * -C18 + e6 * C14);
        out[outOff + 15] = round(e0 * -C2 + e7 * C30);
    }

    /**
     * Computes the 32x32 discrete cosine transform ({@code vpx_fdct32x32_c}), or its lower
     * precision variant for the rate-distortion loop ({@code vpx_fdct32x32_rd_c}).
     *
     * @param in the residuals
     * @param inOff the index of the first residual
     * @param stride the residual row stride
     * @param out the coefficients, 1024 entries
     * @param outOff the index of the first coefficient
     * @param rdVariant whether to compute the lower precision variant
     * @param tmp a scratch array of at least 1024 + 96 entries
     */
    static void fdct32x32(short[] in, int inOff, int stride, int[] out, int outOff, boolean rdVariant, long[] tmp) {
        var tin = 1024;
        var tout = 1056;
        var step = 1088;
        for (var i = 0; i < 32; i++) {
            for (var j = 0; j < 32; j++) {
                tmp[tin + j] = in[inOff + j * stride + i] * 4L;
            }
            fdct32(tmp, tin, tmp, tout, step, false);
            for (var j = 0; j < 32; j++) {
                var v = tmp[tout + j];
                tmp[j * 32 + i] = (v + 1 + (v > 0 ? 1 : 0)) >> 2;
            }
        }
        for (var i = 0; i < 32; i++) {
            System.arraycopy(tmp, i * 32, tmp, tin, 32);
            fdct32(tmp, tin, tmp, tout, step, rdVariant);
            for (var j = 0; j < 32; j++) {
                var v = tmp[tout + j];
                out[outOff + i * 32 + j] = rdVariant ? (int) v : (int) ((v + 1 + (v < 0 ? 1 : 0)) >> 2);
            }
        }
    }

    /**
     * Rounds a value by a quarter, half away from zero ({@code half_round_shift}).
     *
     * @param v the value
     * @return the rounded quarter
     */
    private static long halfRound(long v) {
        return (v + 1 + (v < 0 ? 1 : 0)) >> 2;
    }

    /**
     * Computes the 32-point cosine transform ({@code vpx_fdct32}).
     *
     * @param in the array holding the 32 inputs
     * @param inOff the index of the first input
     * @param out the array receiving the 32 outputs
     * @param outOff the index of the first output
     * @param stepOff the index of 32 scratch entries in {@code out}
     * @param roundMid whether to divide the intermediate values by four after stage 2
     */
    private static void fdct32(long[] in, int inOff, long[] out, int outOff, int stepOff, boolean roundMid) {
        var s = out;
        var o = out;
        var so = stepOff;
        var oo = outOff;
        for (var k = 0; k < 16; k++) {
            s[so + k] = in[inOff + k] + in[inOff + 31 - k];
        }
        for (var k = 16; k < 32; k++) {
            s[so + k] = -in[inOff + k] + in[inOff + 31 - k];
        }
        for (var k = 0; k < 8; k++) {
            o[oo + k] = s[so + k] + s[so + 15 - k];
        }
        for (var k = 8; k < 16; k++) {
            o[oo + k] = -s[so + k] + s[so + 15 - k];
        }
        o[oo + 16] = s[so + 16];
        o[oo + 17] = s[so + 17];
        o[oo + 18] = s[so + 18];
        o[oo + 19] = s[so + 19];
        o[oo + 20] = round((-s[so + 20] + s[so + 27]) * C16);
        o[oo + 21] = round((-s[so + 21] + s[so + 26]) * C16);
        o[oo + 22] = round((-s[so + 22] + s[so + 25]) * C16);
        o[oo + 23] = round((-s[so + 23] + s[so + 24]) * C16);
        o[oo + 24] = round((s[so + 24] + s[so + 23]) * C16);
        o[oo + 25] = round((s[so + 25] + s[so + 22]) * C16);
        o[oo + 26] = round((s[so + 26] + s[so + 21]) * C16);
        o[oo + 27] = round((s[so + 27] + s[so + 20]) * C16);
        o[oo + 28] = s[so + 28];
        o[oo + 29] = s[so + 29];
        o[oo + 30] = s[so + 30];
        o[oo + 31] = s[so + 31];
        if (roundMid) {
            for (var k = 0; k < 32; k++) {
                o[oo + k] = halfRound(o[oo + k]);
            }
        }
        s[so] = o[oo] + o[oo + 7];
        s[so + 1] = o[oo + 1] + o[oo + 6];
        s[so + 2] = o[oo + 2] + o[oo + 5];
        s[so + 3] = o[oo + 3] + o[oo + 4];
        s[so + 4] = -o[oo + 4] + o[oo + 3];
        s[so + 5] = -o[oo + 5] + o[oo + 2];
        s[so + 6] = -o[oo + 6] + o[oo + 1];
        s[so + 7] = -o[oo + 7] + o[oo];
        s[so + 8] = o[oo + 8];
        s[so + 9] = o[oo + 9];
        s[so + 10] = round((-o[oo + 10] + o[oo + 13]) * C16);
        s[so + 11] = round((-o[oo + 11] + o[oo + 12]) * C16);
        s[so + 12] = round((o[oo + 12] + o[oo + 11]) * C16);
        s[so + 13] = round((o[oo + 13] + o[oo + 10]) * C16);
        s[so + 14] = o[oo + 14];
        s[so + 15] = o[oo + 15];
        s[so + 16] = o[oo + 16] + o[oo + 23];
        s[so + 17] = o[oo + 17] + o[oo + 22];
        s[so + 18] = o[oo + 18] + o[oo + 21];
        s[so + 19] = o[oo + 19] + o[oo + 20];
        s[so + 20] = -o[oo + 20] + o[oo + 19];
        s[so + 21] = -o[oo + 21] + o[oo + 18];
        s[so + 22] = -o[oo + 22] + o[oo + 17];
        s[so + 23] = -o[oo + 23] + o[oo + 16];
        s[so + 24] = -o[oo + 24] + o[oo + 31];
        s[so + 25] = -o[oo + 25] + o[oo + 30];
        s[so + 26] = -o[oo + 26] + o[oo + 29];
        s[so + 27] = -o[oo + 27] + o[oo + 28];
        s[so + 28] = o[oo + 28] + o[oo + 27];
        s[so + 29] = o[oo + 29] + o[oo + 26];
        s[so + 30] = o[oo + 30] + o[oo + 25];
        s[so + 31] = o[oo + 31] + o[oo + 24];
        o[oo] = s[so] + s[so + 3];
        o[oo + 1] = s[so + 1] + s[so + 2];
        o[oo + 2] = -s[so + 2] + s[so + 1];
        o[oo + 3] = -s[so + 3] + s[so];
        o[oo + 4] = s[so + 4];
        o[oo + 5] = round((-s[so + 5] + s[so + 6]) * C16);
        o[oo + 6] = round((s[so + 6] + s[so + 5]) * C16);
        o[oo + 7] = s[so + 7];
        o[oo + 8] = s[so + 8] + s[so + 11];
        o[oo + 9] = s[so + 9] + s[so + 10];
        o[oo + 10] = -s[so + 10] + s[so + 9];
        o[oo + 11] = -s[so + 11] + s[so + 8];
        o[oo + 12] = -s[so + 12] + s[so + 15];
        o[oo + 13] = -s[so + 13] + s[so + 14];
        o[oo + 14] = s[so + 14] + s[so + 13];
        o[oo + 15] = s[so + 15] + s[so + 12];
        o[oo + 16] = s[so + 16];
        o[oo + 17] = s[so + 17];
        o[oo + 18] = round(s[so + 18] * -C8 + s[so + 29] * C24);
        o[oo + 19] = round(s[so + 19] * -C8 + s[so + 28] * C24);
        o[oo + 20] = round(s[so + 20] * -C24 + s[so + 27] * -C8);
        o[oo + 21] = round(s[so + 21] * -C24 + s[so + 26] * -C8);
        o[oo + 22] = s[so + 22];
        o[oo + 23] = s[so + 23];
        o[oo + 24] = s[so + 24];
        o[oo + 25] = s[so + 25];
        o[oo + 26] = round(s[so + 26] * C24 + s[so + 21] * -C8);
        o[oo + 27] = round(s[so + 27] * C24 + s[so + 20] * -C8);
        o[oo + 28] = round(s[so + 28] * C8 + s[so + 19] * C24);
        o[oo + 29] = round(s[so + 29] * C8 + s[so + 18] * C24);
        o[oo + 30] = s[so + 30];
        o[oo + 31] = s[so + 31];
        s[so] = round((o[oo] + o[oo + 1]) * C16);
        s[so + 1] = round((-o[oo + 1] + o[oo]) * C16);
        s[so + 2] = round(o[oo + 2] * C24 + o[oo + 3] * C8);
        s[so + 3] = round(o[oo + 3] * C24 - o[oo + 2] * C8);
        s[so + 4] = o[oo + 4] + o[oo + 5];
        s[so + 5] = -o[oo + 5] + o[oo + 4];
        s[so + 6] = -o[oo + 6] + o[oo + 7];
        s[so + 7] = o[oo + 7] + o[oo + 6];
        s[so + 8] = o[oo + 8];
        s[so + 9] = round(o[oo + 9] * -C8 + o[oo + 14] * C24);
        s[so + 10] = round(o[oo + 10] * -C24 + o[oo + 13] * -C8);
        s[so + 11] = o[oo + 11];
        s[so + 12] = o[oo + 12];
        s[so + 13] = round(o[oo + 13] * C24 + o[oo + 10] * -C8);
        s[so + 14] = round(o[oo + 14] * C8 + o[oo + 9] * C24);
        s[so + 15] = o[oo + 15];
        s[so + 16] = o[oo + 16] + o[oo + 19];
        s[so + 17] = o[oo + 17] + o[oo + 18];
        s[so + 18] = -o[oo + 18] + o[oo + 17];
        s[so + 19] = -o[oo + 19] + o[oo + 16];
        s[so + 20] = -o[oo + 20] + o[oo + 23];
        s[so + 21] = -o[oo + 21] + o[oo + 22];
        s[so + 22] = o[oo + 22] + o[oo + 21];
        s[so + 23] = o[oo + 23] + o[oo + 20];
        s[so + 24] = o[oo + 24] + o[oo + 27];
        s[so + 25] = o[oo + 25] + o[oo + 26];
        s[so + 26] = -o[oo + 26] + o[oo + 25];
        s[so + 27] = -o[oo + 27] + o[oo + 24];
        s[so + 28] = -o[oo + 28] + o[oo + 31];
        s[so + 29] = -o[oo + 29] + o[oo + 30];
        s[so + 30] = o[oo + 30] + o[oo + 29];
        s[so + 31] = o[oo + 31] + o[oo + 28];
        o[oo] = s[so];
        o[oo + 1] = s[so + 1];
        o[oo + 2] = s[so + 2];
        o[oo + 3] = s[so + 3];
        o[oo + 4] = round(s[so + 4] * C28 + s[so + 7] * C4);
        o[oo + 5] = round(s[so + 5] * C12 + s[so + 6] * C20);
        o[oo + 6] = round(s[so + 6] * C12 + s[so + 5] * -C20);
        o[oo + 7] = round(s[so + 7] * C28 + s[so + 4] * -C4);
        o[oo + 8] = s[so + 8] + s[so + 9];
        o[oo + 9] = -s[so + 9] + s[so + 8];
        o[oo + 10] = -s[so + 10] + s[so + 11];
        o[oo + 11] = s[so + 11] + s[so + 10];
        o[oo + 12] = s[so + 12] + s[so + 13];
        o[oo + 13] = -s[so + 13] + s[so + 12];
        o[oo + 14] = -s[so + 14] + s[so + 15];
        o[oo + 15] = s[so + 15] + s[so + 14];
        o[oo + 16] = s[so + 16];
        o[oo + 17] = round(s[so + 17] * -C4 + s[so + 30] * C28);
        o[oo + 18] = round(s[so + 18] * -C28 + s[so + 29] * -C4);
        o[oo + 19] = s[so + 19];
        o[oo + 20] = s[so + 20];
        o[oo + 21] = round(s[so + 21] * -C20 + s[so + 26] * C12);
        o[oo + 22] = round(s[so + 22] * -C12 + s[so + 25] * -C20);
        o[oo + 23] = s[so + 23];
        o[oo + 24] = s[so + 24];
        o[oo + 25] = round(s[so + 25] * C12 + s[so + 22] * -C20);
        o[oo + 26] = round(s[so + 26] * C20 + s[so + 21] * C12);
        o[oo + 27] = s[so + 27];
        o[oo + 28] = s[so + 28];
        o[oo + 29] = round(s[so + 29] * C28 + s[so + 18] * -C4);
        o[oo + 30] = round(s[so + 30] * C4 + s[so + 17] * C28);
        o[oo + 31] = s[so + 31];
        s[so] = o[oo];
        s[so + 1] = o[oo + 1];
        s[so + 2] = o[oo + 2];
        s[so + 3] = o[oo + 3];
        s[so + 4] = o[oo + 4];
        s[so + 5] = o[oo + 5];
        s[so + 6] = o[oo + 6];
        s[so + 7] = o[oo + 7];
        s[so + 8] = round(o[oo + 8] * C30 + o[oo + 15] * C2);
        s[so + 9] = round(o[oo + 9] * C14 + o[oo + 14] * C18);
        s[so + 10] = round(o[oo + 10] * C22 + o[oo + 13] * C10);
        s[so + 11] = round(o[oo + 11] * C6 + o[oo + 12] * C26);
        s[so + 12] = round(o[oo + 12] * C6 + o[oo + 11] * -C26);
        s[so + 13] = round(o[oo + 13] * C22 + o[oo + 10] * -C10);
        s[so + 14] = round(o[oo + 14] * C14 + o[oo + 9] * -C18);
        s[so + 15] = round(o[oo + 15] * C30 + o[oo + 8] * -C2);
        s[so + 16] = o[oo + 16] + o[oo + 17];
        s[so + 17] = -o[oo + 17] + o[oo + 16];
        s[so + 18] = -o[oo + 18] + o[oo + 19];
        s[so + 19] = o[oo + 19] + o[oo + 18];
        s[so + 20] = o[oo + 20] + o[oo + 21];
        s[so + 21] = -o[oo + 21] + o[oo + 20];
        s[so + 22] = -o[oo + 22] + o[oo + 23];
        s[so + 23] = o[oo + 23] + o[oo + 22];
        s[so + 24] = o[oo + 24] + o[oo + 25];
        s[so + 25] = -o[oo + 25] + o[oo + 24];
        s[so + 26] = -o[oo + 26] + o[oo + 27];
        s[so + 27] = o[oo + 27] + o[oo + 26];
        s[so + 28] = o[oo + 28] + o[oo + 29];
        s[so + 29] = -o[oo + 29] + o[oo + 28];
        s[so + 30] = -o[oo + 30] + o[oo + 31];
        s[so + 31] = o[oo + 31] + o[oo + 30];
        o[oo] = s[so];
        o[oo + 16] = s[so + 1];
        o[oo + 8] = s[so + 2];
        o[oo + 24] = s[so + 3];
        o[oo + 4] = s[so + 4];
        o[oo + 20] = s[so + 5];
        o[oo + 12] = s[so + 6];
        o[oo + 28] = s[so + 7];
        o[oo + 2] = s[so + 8];
        o[oo + 18] = s[so + 9];
        o[oo + 10] = s[so + 10];
        o[oo + 26] = s[so + 11];
        o[oo + 6] = s[so + 12];
        o[oo + 22] = s[so + 13];
        o[oo + 14] = s[so + 14];
        o[oo + 30] = s[so + 15];
        o[oo + 1] = round(s[so + 16] * C31 + s[so + 31] * C1);
        o[oo + 17] = round(s[so + 17] * C15 + s[so + 30] * C17);
        o[oo + 9] = round(s[so + 18] * C23 + s[so + 29] * C9);
        o[oo + 25] = round(s[so + 19] * C7 + s[so + 28] * C25);
        o[oo + 5] = round(s[so + 20] * C27 + s[so + 27] * C5);
        o[oo + 21] = round(s[so + 21] * C11 + s[so + 26] * C21);
        o[oo + 13] = round(s[so + 22] * C19 + s[so + 25] * C13);
        o[oo + 29] = round(s[so + 23] * C3 + s[so + 24] * C29);
        o[oo + 3] = round(s[so + 24] * C3 + s[so + 23] * -C29);
        o[oo + 19] = round(s[so + 25] * C19 + s[so + 22] * -C13);
        o[oo + 11] = round(s[so + 26] * C11 + s[so + 21] * -C21);
        o[oo + 27] = round(s[so + 27] * C27 + s[so + 20] * -C5);
        o[oo + 7] = round(s[so + 28] * C7 + s[so + 19] * -C25);
        o[oo + 23] = round(s[so + 29] * C23 + s[so + 18] * -C9);
        o[oo + 15] = round(s[so + 30] * C15 + s[so + 17] * -C17);
        o[oo + 31] = round(s[so + 31] * C31 + s[so + 16] * -C1);
    }

    /**
     * Computes a 4x4 hybrid transform ({@code vp9_fht4x4_c}).
     *
     * @param in the residuals
     * @param inOff the index of the first residual
     * @param stride the residual row stride
     * @param out the coefficients, 16 entries
     * @param outOff the index of the first coefficient
     * @param txType the transform type: 0 DCT_DCT, 1 ADST_DCT, 2 DCT_ADST, 3 ADST_ADST
     * @param tmp a scratch array of at least 32 entries
     */
    static void fht4x4(short[] in, int inOff, int stride, int[] out, int outOff, int txType, long[] tmp) {
        if (txType == 0) {
            fdct4x4(in, inOff, stride, out, outOff, tmp);
            return;
        }
        var colAdst = txType == 1 || txType == 3;
        var rowAdst = txType == 2 || txType == 3;
        for (var i = 0; i < 4; i++) {
            long a0 = in[inOff + i] * 16;
            long a1 = in[inOff + stride + i] * 16;
            long a2 = in[inOff + 2 * stride + i] * 16;
            long a3 = in[inOff + 3 * stride + i] * 16;
            if (i == 0 && a0 != 0) {
                a0++;
            }
            if (colAdst) {
                fadst4(a0, a1, a2, a3, tmp, 16);
            } else {
                fdct4Into(a0, a1, a2, a3, tmp, 16);
            }
            for (var j = 0; j < 4; j++) {
                tmp[j * 4 + i] = tmp[16 + j];
            }
        }
        for (var i = 0; i < 4; i++) {
            if (rowAdst) {
                fadst4(tmp[i * 4], tmp[i * 4 + 1], tmp[i * 4 + 2], tmp[i * 4 + 3], tmp, 16);
            } else {
                fdct4Into(tmp[i * 4], tmp[i * 4 + 1], tmp[i * 4 + 2], tmp[i * 4 + 3], tmp, 16);
            }
            for (var j = 0; j < 4; j++) {
                out[outOff + i * 4 + j] = (int) ((tmp[16 + j] + 1) >> 2);
            }
        }
    }

    /**
     * Computes the 4-point sine transform ({@code fadst4}).
     *
     * @param x0 input 0
     * @param x1 input 1
     * @param x2 input 2
     * @param x3 input 3
     * @param out the destination
     * @param off the index of output 0
     */
    private static void fadst4(long x0, long x1, long x2, long x3, long[] out, int off) {
        if ((x0 | x1 | x2 | x3) == 0) {
            out[off] = 0;
            out[off + 1] = 0;
            out[off + 2] = 0;
            out[off + 3] = 0;
            return;
        }
        var s0 = S1 * x0;
        var s1 = S4 * x0;
        var s2 = S2 * x1;
        var s3 = S1 * x1;
        var s4 = S3 * x2;
        var s5 = S4 * x3;
        var s6 = S2 * x3;
        var s7 = x0 + x1 - x3;
        var y0 = s0 + s2 + s5;
        var y1 = S3 * s7;
        var y2 = s1 - s3 + s6;
        var y3 = s4;
        out[off] = round(y0 + y3);
        out[off + 1] = round(y1);
        out[off + 2] = round(y2 - y3);
        out[off + 3] = round(y2 - y0 + y3);
    }

    /**
     * Computes an 8x8 hybrid transform ({@code vp9_fht8x8_c}).
     *
     * @param in the residuals
     * @param inOff the index of the first residual
     * @param stride the residual row stride
     * @param out the coefficients, 64 entries
     * @param outOff the index of the first coefficient
     * @param txType the transform type
     * @param tmp a scratch array of at least 128 entries
     */
    static void fht8x8(short[] in, int inOff, int stride, int[] out, int outOff, int txType, long[] tmp) {
        if (txType == 0) {
            fdct8x8(in, inOff, stride, out, outOff, tmp);
            return;
        }
        var colAdst = txType == 1 || txType == 3;
        var rowAdst = txType == 2 || txType == 3;
        for (var i = 0; i < 8; i++) {
            for (var j = 0; j < 8; j++) {
                tmp[64 + j] = in[inOff + j * stride + i] * 4L;
            }
            if (colAdst) {
                fadst8(tmp, 64, tmp, 72);
            } else {
                fdct8Generic(tmp, 64, tmp, 72);
            }
            for (var j = 0; j < 8; j++) {
                tmp[j * 8 + i] = tmp[72 + j];
            }
        }
        for (var i = 0; i < 8; i++) {
            System.arraycopy(tmp, i * 8, tmp, 64, 8);
            if (rowAdst) {
                fadst8(tmp, 64, tmp, 72);
            } else {
                fdct8Generic(tmp, 64, tmp, 72);
            }
            for (var j = 0; j < 8; j++) {
                var v = tmp[72 + j];
                out[outOff + i * 8 + j] = (int) ((v + (v < 0 ? 1 : 0)) >> 1);
            }
        }
    }

    /**
     * Computes the 8-point cosine transform of {@code vp9_dct.c} ({@code fdct8}).
     *
     * @param in the array holding the inputs
     * @param inOff the index of the first input
     * @param out the array receiving the outputs
     * @param outOff the index of the first output
     */
    private static void fdct8Generic(long[] in, int inOff, long[] out, int outOff) {
        fdct8Pass(in[inOff], in[inOff + 1], in[inOff + 2], in[inOff + 3], in[inOff + 4], in[inOff + 5],
                in[inOff + 6], in[inOff + 7], out, outOff);
    }

    /**
     * Computes the 8-point sine transform ({@code fadst8}).
     *
     * @param in the array holding the inputs
     * @param inOff the index of the first input
     * @param out the array receiving the outputs
     * @param outOff the index of the first output
     */
    private static void fadst8(long[] in, int inOff, long[] out, int outOff) {
        var x0 = in[inOff + 7];
        var x1 = in[inOff];
        var x2 = in[inOff + 5];
        var x3 = in[inOff + 2];
        var x4 = in[inOff + 3];
        var x5 = in[inOff + 4];
        var x6 = in[inOff + 1];
        var x7 = in[inOff + 6];
        var s0 = C2 * x0 + C30 * x1;
        var s1 = C30 * x0 - C2 * x1;
        var s2 = C10 * x2 + C22 * x3;
        var s3 = C22 * x2 - C10 * x3;
        var s4 = C18 * x4 + C14 * x5;
        var s5 = C14 * x4 - C18 * x5;
        var s6 = C26 * x6 + C6 * x7;
        var s7 = C6 * x6 - C26 * x7;
        x0 = round(s0 + s4);
        x1 = round(s1 + s5);
        x2 = round(s2 + s6);
        x3 = round(s3 + s7);
        x4 = round(s0 - s4);
        x5 = round(s1 - s5);
        x6 = round(s2 - s6);
        x7 = round(s3 - s7);
        s0 = x0;
        s1 = x1;
        s2 = x2;
        s3 = x3;
        s4 = C8 * x4 + C24 * x5;
        s5 = C24 * x4 - C8 * x5;
        s6 = -C24 * x6 + C8 * x7;
        s7 = C8 * x6 + C24 * x7;
        x0 = s0 + s2;
        x1 = s1 + s3;
        x2 = s0 - s2;
        x3 = s1 - s3;
        x4 = round(s4 + s6);
        x5 = round(s5 + s7);
        x6 = round(s4 - s6);
        x7 = round(s5 - s7);
        s2 = C16 * (x2 + x3);
        s3 = C16 * (x2 - x3);
        s6 = C16 * (x6 + x7);
        s7 = C16 * (x6 - x7);
        x2 = round(s2);
        x3 = round(s3);
        x6 = round(s6);
        x7 = round(s7);
        out[outOff] = x0;
        out[outOff + 1] = -x4;
        out[outOff + 2] = x6;
        out[outOff + 3] = -x2;
        out[outOff + 4] = x3;
        out[outOff + 5] = -x7;
        out[outOff + 6] = x5;
        out[outOff + 7] = -x1;
    }

    /**
     * Computes a 16x16 hybrid transform ({@code vp9_fht16x16_c}).
     *
     * @param in the residuals
     * @param inOff the index of the first residual
     * @param stride the residual row stride
     * @param out the coefficients, 256 entries
     * @param outOff the index of the first coefficient
     * @param txType the transform type
     * @param tmp a scratch array of at least 288 entries
     */
    static void fht16x16(short[] in, int inOff, int stride, int[] out, int outOff, int txType, long[] tmp) {
        if (txType == 0) {
            fdct16x16(in, inOff, stride, out, outOff, tmp);
            return;
        }
        var colAdst = txType == 1 || txType == 3;
        var rowAdst = txType == 2 || txType == 3;
        for (var i = 0; i < 16; i++) {
            for (var j = 0; j < 16; j++) {
                tmp[256 + j] = in[inOff + j * stride + i] * 4L;
            }
            if (colAdst) {
                fadst16(tmp, 256, tmp, 272);
            } else {
                fdct16(tmp, 256, tmp, 272);
            }
            for (var j = 0; j < 16; j++) {
                var v = tmp[272 + j];
                tmp[j * 16 + i] = (v + 1 + (v < 0 ? 1 : 0)) >> 2;
            }
        }
        for (var i = 0; i < 16; i++) {
            if (rowAdst) {
                fadst16(tmp, i * 16, tmp, 272);
            } else {
                fdct16(tmp, i * 16, tmp, 272);
            }
            for (var j = 0; j < 16; j++) {
                out[outOff + i * 16 + j] = (int) tmp[272 + j];
            }
        }
    }

    /**
     * Computes the 16-point sine transform ({@code fadst16}).
     *
     * @param in the array holding the inputs
     * @param inOff the index of the first input
     * @param out the array receiving the outputs
     * @param outOff the index of the first output
     */
    private static void fadst16(long[] in, int inOff, long[] out, int outOff) {
        var x0 = in[inOff + 15];
        var x1 = in[inOff];
        var x2 = in[inOff + 13];
        var x3 = in[inOff + 2];
        var x4 = in[inOff + 11];
        var x5 = in[inOff + 4];
        var x6 = in[inOff + 9];
        var x7 = in[inOff + 6];
        var x8 = in[inOff + 7];
        var x9 = in[inOff + 8];
        var x10 = in[inOff + 5];
        var x11 = in[inOff + 10];
        var x12 = in[inOff + 3];
        var x13 = in[inOff + 12];
        var x14 = in[inOff + 1];
        var x15 = in[inOff + 14];
        var s0 = x0 * C1 + x1 * C31;
        var s1 = x0 * C31 - x1 * C1;
        var s2 = x2 * C5 + x3 * C27;
        var s3 = x2 * C27 - x3 * C5;
        var s4 = x4 * C9 + x5 * C23;
        var s5 = x4 * C23 - x5 * C9;
        var s6 = x6 * C13 + x7 * C19;
        var s7 = x6 * C19 - x7 * C13;
        var s8 = x8 * C17 + x9 * C15;
        var s9 = x8 * C15 - x9 * C17;
        var s10 = x10 * C21 + x11 * C11;
        var s11 = x10 * C11 - x11 * C21;
        var s12 = x12 * C25 + x13 * C7;
        var s13 = x12 * C7 - x13 * C25;
        var s14 = x14 * C29 + x15 * C3;
        var s15 = x14 * C3 - x15 * C29;
        x0 = round(s0 + s8);
        x1 = round(s1 + s9);
        x2 = round(s2 + s10);
        x3 = round(s3 + s11);
        x4 = round(s4 + s12);
        x5 = round(s5 + s13);
        x6 = round(s6 + s14);
        x7 = round(s7 + s15);
        x8 = round(s0 - s8);
        x9 = round(s1 - s9);
        x10 = round(s2 - s10);
        x11 = round(s3 - s11);
        x12 = round(s4 - s12);
        x13 = round(s5 - s13);
        x14 = round(s6 - s14);
        x15 = round(s7 - s15);
        s0 = x0;
        s1 = x1;
        s2 = x2;
        s3 = x3;
        s4 = x4;
        s5 = x5;
        s6 = x6;
        s7 = x7;
        s8 = x8 * C4 + x9 * C28;
        s9 = x8 * C28 - x9 * C4;
        s10 = x10 * C20 + x11 * C12;
        s11 = x10 * C12 - x11 * C20;
        s12 = -x12 * C28 + x13 * C4;
        s13 = x12 * C4 + x13 * C28;
        s14 = -x14 * C12 + x15 * C20;
        s15 = x14 * C20 + x15 * C12;
        x0 = s0 + s4;
        x1 = s1 + s5;
        x2 = s2 + s6;
        x3 = s3 + s7;
        x4 = s0 - s4;
        x5 = s1 - s5;
        x6 = s2 - s6;
        x7 = s3 - s7;
        x8 = round(s8 + s12);
        x9 = round(s9 + s13);
        x10 = round(s10 + s14);
        x11 = round(s11 + s15);
        x12 = round(s8 - s12);
        x13 = round(s9 - s13);
        x14 = round(s10 - s14);
        x15 = round(s11 - s15);
        s0 = x0;
        s1 = x1;
        s2 = x2;
        s3 = x3;
        s4 = x4 * C8 + x5 * C24;
        s5 = x4 * C24 - x5 * C8;
        s6 = -x6 * C24 + x7 * C8;
        s7 = x6 * C8 + x7 * C24;
        s8 = x8;
        s9 = x9;
        s10 = x10;
        s11 = x11;
        s12 = x12 * C8 + x13 * C24;
        s13 = x12 * C24 - x13 * C8;
        s14 = -x14 * C24 + x15 * C8;
        s15 = x14 * C8 + x15 * C24;
        x0 = s0 + s2;
        x1 = s1 + s3;
        x2 = s0 - s2;
        x3 = s1 - s3;
        x4 = round(s4 + s6);
        x5 = round(s5 + s7);
        x6 = round(s4 - s6);
        x7 = round(s5 - s7);
        x8 = s8 + s10;
        x9 = s9 + s11;
        x10 = s8 - s10;
        x11 = s9 - s11;
        x12 = round(s12 + s14);
        x13 = round(s13 + s15);
        x14 = round(s12 - s14);
        x15 = round(s13 - s15);
        s2 = -C16 * (x2 + x3);
        s3 = C16 * (x2 - x3);
        s6 = C16 * (x6 + x7);
        s7 = C16 * (-x6 + x7);
        s10 = C16 * (x10 + x11);
        s11 = C16 * (-x10 + x11);
        s14 = -C16 * (x14 + x15);
        s15 = C16 * (x14 - x15);
        x2 = round(s2);
        x3 = round(s3);
        x6 = round(s6);
        x7 = round(s7);
        x10 = round(s10);
        x11 = round(s11);
        x14 = round(s14);
        x15 = round(s15);
        out[outOff] = x0;
        out[outOff + 1] = -x8;
        out[outOff + 2] = x12;
        out[outOff + 3] = -x4;
        out[outOff + 4] = x6;
        out[outOff + 5] = x14;
        out[outOff + 6] = x10;
        out[outOff + 7] = x2;
        out[outOff + 8] = x3;
        out[outOff + 9] = x11;
        out[outOff + 10] = x15;
        out[outOff + 11] = x7;
        out[outOff + 12] = x5;
        out[outOff + 13] = -x13;
        out[outOff + 14] = x9;
        out[outOff + 15] = -x1;
    }

    /**
     * Computes the lossless 4x4 Walsh-Hadamard transform ({@code vp9_fwht4x4_c}); the
     * coefficients are scaled by 4 ({@code UNIT_QUANT_FACTOR}), so the quantizer of index 0
     * recovers them exactly.
     *
     * @param in the residuals
     * @param inOff the index of the first residual
     * @param stride the residual row stride
     * @param out the coefficients, 16 entries
     * @param outOff the index of the first coefficient
     */
    static void fwht4x4(short[] in, int inOff, int stride, int[] out, int outOff) {
        for (var i = 0; i < 4; i++) {
            int a1 = in[inOff + i];
            int b1 = in[inOff + stride + i];
            int c1 = in[inOff + 2 * stride + i];
            int d1 = in[inOff + 3 * stride + i];
            a1 += b1;
            d1 -= c1;
            var e1 = (a1 - d1) >> 1;
            b1 = e1 - b1;
            c1 = e1 - c1;
            a1 -= c1;
            d1 += b1;
            out[outOff + i] = a1;
            out[outOff + 4 + i] = c1;
            out[outOff + 8 + i] = d1;
            out[outOff + 12 + i] = b1;
        }
        for (var i = 0; i < 4; i++) {
            var p = outOff + i * 4;
            var a1 = out[p];
            var b1 = out[p + 1];
            var c1 = out[p + 2];
            var d1 = out[p + 3];
            a1 += b1;
            d1 -= c1;
            var e1 = (a1 - d1) >> 1;
            b1 = e1 - b1;
            c1 = e1 - c1;
            a1 -= c1;
            d1 += b1;
            out[p] = a1 * 4;
            out[p + 1] = c1 * 4;
            out[p + 2] = d1 * 4;
            out[p + 3] = b1 * 4;
        }
    }
}
