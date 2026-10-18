/* Ports libwebp 1.6.0 (src/dsp/enc.c: FTransform_C, ITransformOne, GetSSE, Disto4x4_C, QuantizeBlock_C). Copyright (c)
   2010, Google Inc.; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.video.vp8.internal;

/**
 * Implements {@link Vp8EncDsp} in plain Java; the reference implementation for
 * {@link VectorVp8EncDsp}.
 */
public final class ScalarVp8EncDsp extends Vp8EncDsp {
    /**
     * The zigzag scan order: raster position of each scan index.
     */
    static final int[] ZIGZAG = {0, 1, 4, 8, 5, 2, 3, 6, 9, 12, 13, 10, 7, 11, 14, 15};

    /**
     * The largest level the encoder codes.
     */
    static final int MAX_LEVEL = 2047;

    /**
     * Creates the scalar implementation.
     */
    public ScalarVp8EncDsp() {
    }

    @Override
    public void fTransform(byte[] src, int srcOff, byte[] ref, int refOff, short[] out, int outOff) {
        int t0, t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15;
        {
            var d0 = (src[srcOff] & 0xFF) - (ref[refOff] & 0xFF);
            var d1 = (src[srcOff + 1] & 0xFF) - (ref[refOff + 1] & 0xFF);
            var d2 = (src[srcOff + 2] & 0xFF) - (ref[refOff + 2] & 0xFF);
            var d3 = (src[srcOff + 3] & 0xFF) - (ref[refOff + 3] & 0xFF);
            var a0 = d0 + d3;
            var a1 = d1 + d2;
            var a2 = d1 - d2;
            var a3 = d0 - d3;
            t0 = (a0 + a1) * 8;
            t1 = (a2 * 2217 + a3 * 5352 + 1812) >> 9;
            t2 = (a0 - a1) * 8;
            t3 = (a3 * 2217 - a2 * 5352 + 937) >> 9;
        }
        {
            var s = srcOff + BPS;
            var r = refOff + BPS;
            var d0 = (src[s] & 0xFF) - (ref[r] & 0xFF);
            var d1 = (src[s + 1] & 0xFF) - (ref[r + 1] & 0xFF);
            var d2 = (src[s + 2] & 0xFF) - (ref[r + 2] & 0xFF);
            var d3 = (src[s + 3] & 0xFF) - (ref[r + 3] & 0xFF);
            var a0 = d0 + d3;
            var a1 = d1 + d2;
            var a2 = d1 - d2;
            var a3 = d0 - d3;
            t4 = (a0 + a1) * 8;
            t5 = (a2 * 2217 + a3 * 5352 + 1812) >> 9;
            t6 = (a0 - a1) * 8;
            t7 = (a3 * 2217 - a2 * 5352 + 937) >> 9;
        }
        {
            var s = srcOff + 2 * BPS;
            var r = refOff + 2 * BPS;
            var d0 = (src[s] & 0xFF) - (ref[r] & 0xFF);
            var d1 = (src[s + 1] & 0xFF) - (ref[r + 1] & 0xFF);
            var d2 = (src[s + 2] & 0xFF) - (ref[r + 2] & 0xFF);
            var d3 = (src[s + 3] & 0xFF) - (ref[r + 3] & 0xFF);
            var a0 = d0 + d3;
            var a1 = d1 + d2;
            var a2 = d1 - d2;
            var a3 = d0 - d3;
            t8 = (a0 + a1) * 8;
            t9 = (a2 * 2217 + a3 * 5352 + 1812) >> 9;
            t10 = (a0 - a1) * 8;
            t11 = (a3 * 2217 - a2 * 5352 + 937) >> 9;
        }
        {
            var s = srcOff + 3 * BPS;
            var r = refOff + 3 * BPS;
            var d0 = (src[s] & 0xFF) - (ref[r] & 0xFF);
            var d1 = (src[s + 1] & 0xFF) - (ref[r + 1] & 0xFF);
            var d2 = (src[s + 2] & 0xFF) - (ref[r + 2] & 0xFF);
            var d3 = (src[s + 3] & 0xFF) - (ref[r + 3] & 0xFF);
            var a0 = d0 + d3;
            var a1 = d1 + d2;
            var a2 = d1 - d2;
            var a3 = d0 - d3;
            t12 = (a0 + a1) * 8;
            t13 = (a2 * 2217 + a3 * 5352 + 1812) >> 9;
            t14 = (a0 - a1) * 8;
            t15 = (a3 * 2217 - a2 * 5352 + 937) >> 9;
        }
        fColumn(t0, t4, t8, t12, out, outOff);
        fColumn(t1, t5, t9, t13, out, outOff + 1);
        fColumn(t2, t6, t10, t14, out, outOff + 2);
        fColumn(t3, t7, t11, t15, out, outOff + 3);
    }

    /**
     * Applies the vertical pass of the forward DCT to one column.
     *
     * @param c0  the first row's intermediate
     * @param c1  the second row's intermediate
     * @param c2  the third row's intermediate
     * @param c3  the fourth row's intermediate
     * @param out the coefficients
     * @param off the index of the column's first coefficient
     */
    private static void fColumn(int c0, int c1, int c2, int c3, short[] out, int off) {
        var a0 = c0 + c3;
        var a1 = c1 + c2;
        var a2 = c1 - c2;
        var a3 = c0 - c3;
        out[off] = (short) ((a0 + a1 + 7) >> 4);
        out[off + 4] = (short) (((a2 * 2217 + a3 * 5352 + 12000) >> 16) + (a3 != 0 ? 1 : 0));
        out[off + 8] = (short) ((a0 - a1 + 7) >> 4);
        out[off + 12] = (short) ((a3 * 2217 - a2 * 5352 + 51000) >> 16);
    }

    /**
     * Multiplies by {@code sqrt(2) * cos(pi / 8)} in 16-bit fixed point.
     *
     * @param a the value
     * @return the product
     */
    static int mul1(int a) {
        return ((a * 20091) >> 16) + a;
    }

    /**
     * Multiplies by {@code sqrt(2) * sin(pi / 8)} in 16-bit fixed point.
     *
     * @param a the value
     * @return the product
     */
    static int mul2(int a) {
        return (a * 35468) >> 16;
    }

    @Override
    public void iTransform(byte[] ref, int refOff, short[] in, int inOff, byte[] dst, int dstOff) {
        int t0, t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15;
        {
            int i0 = in[inOff], i4 = in[inOff + 4], i8 = in[inOff + 8], i12 = in[inOff + 12];
            var a = i0 + i8;
            var b = i0 - i8;
            var cc = mul2(i4) - mul1(i12);
            var d = mul1(i4) + mul2(i12);
            t0 = a + d;
            t1 = b + cc;
            t2 = b - cc;
            t3 = a - d;
        }
        {
            int i0 = in[inOff + 1], i4 = in[inOff + 5], i8 = in[inOff + 9], i12 = in[inOff + 13];
            var a = i0 + i8;
            var b = i0 - i8;
            var cc = mul2(i4) - mul1(i12);
            var d = mul1(i4) + mul2(i12);
            t4 = a + d;
            t5 = b + cc;
            t6 = b - cc;
            t7 = a - d;
        }
        {
            int i0 = in[inOff + 2], i4 = in[inOff + 6], i8 = in[inOff + 10], i12 = in[inOff + 14];
            var a = i0 + i8;
            var b = i0 - i8;
            var cc = mul2(i4) - mul1(i12);
            var d = mul1(i4) + mul2(i12);
            t8 = a + d;
            t9 = b + cc;
            t10 = b - cc;
            t11 = a - d;
        }
        {
            int i0 = in[inOff + 3], i4 = in[inOff + 7], i8 = in[inOff + 11], i12 = in[inOff + 15];
            var a = i0 + i8;
            var b = i0 - i8;
            var cc = mul2(i4) - mul1(i12);
            var d = mul1(i4) + mul2(i12);
            t12 = a + d;
            t13 = b + cc;
            t14 = b - cc;
            t15 = a - d;
        }
        iRow(t0, t4, t8, t12, ref, refOff, dst, dstOff);
        iRow(t1, t5, t9, t13, ref, refOff + BPS, dst, dstOff + BPS);
        iRow(t2, t6, t10, t14, ref, refOff + 2 * BPS, dst, dstOff + 2 * BPS);
        iRow(t3, t7, t11, t15, ref, refOff + 3 * BPS, dst, dstOff + 3 * BPS);
    }

    /**
     * Applies the horizontal pass of the inverse DCT to one row and stores the reconstruction.
     *
     * @param c0     the row's first intermediate
     * @param c1     the row's second intermediate
     * @param c2     the row's third intermediate
     * @param c3     the row's fourth intermediate
     * @param ref    the prediction samples
     * @param refOff the index of the row's first prediction sample
     * @param dst    the reconstruction samples
     * @param dstOff the index of the row's first reconstructed sample
     */
    private static void iRow(int c0, int c1, int c2, int c3, byte[] ref, int refOff, byte[] dst, int dstOff) {
        var dc = c0 + 4;
        var a = dc + c2;
        var b = dc - c2;
        var c = mul2(c1) - mul1(c3);
        var d = mul1(c1) + mul2(c3);
        dst[dstOff] = clip8((ref[refOff] & 0xFF) + ((a + d) >> 3));
        dst[dstOff + 1] = clip8((ref[refOff + 1] & 0xFF) + ((b + c) >> 3));
        dst[dstOff + 2] = clip8((ref[refOff + 2] & 0xFF) + ((b - c) >> 3));
        dst[dstOff + 3] = clip8((ref[refOff + 3] & 0xFF) + ((a - d) >> 3));
    }

    /**
     * Clamps a value to {@code [0, 255]}.
     *
     * @param v the value
     * @return the clamped sample
     */
    private static byte clip8(int v) {
        return (byte) ((v & ~0xFF) == 0 ? v : (v < 0 ? 0 : 255));
    }

    @Override
    public int sse(byte[] a, int aOff, byte[] b, int bOff, int width, int height) {
        var count = 0;
        for (var y = 0; y < height; y++) {
            var ra = aOff + y * BPS;
            var rb = bOff + y * BPS;
            for (var x = 0; x < width; x++) {
                var diff = (a[ra + x] & 0xFF) - (b[rb + x] & 0xFF);
                count += diff * diff;
            }
        }
        return count;
    }

    @Override
    public int tDisto4x4(byte[] a, int aOff, byte[] b, int bOff, int[] weights) {
        var sum1 = tTransform(a, aOff, weights);
        var sum2 = tTransform(b, bOff, weights);
        return Math.abs(sum2 - sum1) >> 5;
    }

    /**
     * Returns the weighted sum of the absolute Hadamard-transform coefficients of a 4x4 block.
     *
     * @param in      the samples
     * @param off     the index of the block
     * @param weights the 16 frequency weights
     * @return the weighted sum
     */
    static int tTransform(byte[] in, int off, int[] weights) {
        int r0, r1, r2, r3, r4, r5, r6, r7, r8, r9, r10, r11, r12, r13, r14, r15;
        {
            int p0 = in[off] & 0xFF, p1 = in[off + 1] & 0xFF, p2 = in[off + 2] & 0xFF, p3 = in[off + 3] & 0xFF;
            r0 = p0 + p2 + p1 + p3;
            r1 = p0 - p2 + p1 - p3;
            r2 = p0 - p2 - p1 + p3;
            r3 = p0 + p2 - p1 - p3;
        }
        off += BPS;
        {
            int p0 = in[off] & 0xFF, p1 = in[off + 1] & 0xFF, p2 = in[off + 2] & 0xFF, p3 = in[off + 3] & 0xFF;
            r4 = p0 + p2 + p1 + p3;
            r5 = p0 - p2 + p1 - p3;
            r6 = p0 - p2 - p1 + p3;
            r7 = p0 + p2 - p1 - p3;
        }
        off += BPS;
        {
            int p0 = in[off] & 0xFF, p1 = in[off + 1] & 0xFF, p2 = in[off + 2] & 0xFF, p3 = in[off + 3] & 0xFF;
            r8 = p0 + p2 + p1 + p3;
            r9 = p0 - p2 + p1 - p3;
            r10 = p0 - p2 - p1 + p3;
            r11 = p0 + p2 - p1 - p3;
        }
        off += BPS;
        {
            int p0 = in[off] & 0xFF, p1 = in[off + 1] & 0xFF, p2 = in[off + 2] & 0xFF, p3 = in[off + 3] & 0xFF;
            r12 = p0 + p2 + p1 + p3;
            r13 = p0 - p2 + p1 - p3;
            r14 = p0 - p2 - p1 + p3;
            r15 = p0 + p2 - p1 - p3;
        }
        return tColumn(r0, r4, r8, r12, weights, 0) + tColumn(r1, r5, r9, r13, weights, 1)
                + tColumn(r2, r6, r10, r14, weights, 2) + tColumn(r3, r7, r11, r15, weights, 3);
    }

    /**
     * Applies the vertical Hadamard pass to one column and returns its weighted magnitude.
     *
     * @param c0      the first row's intermediate
     * @param c1      the second row's intermediate
     * @param c2      the third row's intermediate
     * @param c3      the fourth row's intermediate
     * @param weights the 16 frequency weights
     * @param i       the column index
     * @return the weighted sum of the column's absolute coefficients
     */
    private static int tColumn(int c0, int c1, int c2, int c3, int[] weights, int i) {
        var a0 = c0 + c2;
        var a1 = c1 + c3;
        var a2 = c1 - c3;
        var a3 = c0 - c2;
        return weights[i] * Math.abs(a0 + a1) + weights[4 + i] * Math.abs(a3 + a2)
                + weights[8 + i] * Math.abs(a3 - a2) + weights[12 + i] * Math.abs(a0 - a1);
    }

    @Override
    public int spectralSum(byte[] a, int aOff, int[] weights) {
        return tTransform(a, aOff, weights);
    }

    @Override
    public long evaluateBlock(byte[] src, int srcOff, byte[] pred, int predOff, QuantMatrix m, short[] levels,
                              int levelsOff, byte[] recon, int reconOff, int[] weights, short[] scratch) {
        fTransform(src, srcOff, pred, predOff, scratch, 0);
        var nonZero = quantizeBlock(scratch, 0, levels, levelsOff, m);
        iTransform(pred, predOff, scratch, 0, recon, reconOff);
        var sse = sse(src, srcOff, recon, reconOff, 4, 4);
        return ((long) tTransform(recon, reconOff, weights) << 32) | ((long) sse << 1) | nonZero;
    }

    @Override
    public int quantizeBlock(short[] in, int inOff, short[] out, int outOff, QuantMatrix m) {
        var last = -1;
        for (var n = 0; n < 16; n++) {
            var j = ZIGZAG[n];
            int v = in[inOff + j];
            var sign = v < 0;
            var coeff = (sign ? -v : v) + m.sharpen[j];
            if (coeff > m.zthresh[j]) {
                var level = QuantMatrix.quantDiv(coeff, m.iq[j], m.bias[j]);
                if (level > MAX_LEVEL) {
                    level = MAX_LEVEL;
                }
                if (sign) {
                    level = -level;
                }
                in[inOff + j] = (short) (level * m.q[j]);
                out[outOff + n] = (short) level;
                if (level != 0) {
                    last = n;
                }
            } else {
                out[outOff + n] = 0;
                in[inOff + j] = 0;
            }
        }
        return last >= 0 ? 1 : 0;
    }

    @Override
    public int lastNonZero(short[] levels, int off) {
        for (var n = 15; n >= 0; n--) {
            if (levels[off + n] != 0) {
                return n;
            }
        }
        return -1;
    }

    @Override
    public int countNonZeroAc(short[] levels, int off, int blocks, int limit) {
        var count = 0;
        for (var b = 0; b < blocks; b++) {
            for (var i = 1; i < 16; i++) {
                if (levels[off + b * 16 + i] != 0 && ++count > limit) {
                    return count;
                }
            }
        }
        return count;
    }
}
