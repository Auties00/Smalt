/* Ports libvpx (vpx_dsp/inv_txfm.c, vp9/common/vp9_idct.c, vpx_dsp/vpx_convolve.c, vpx_dsp/loopfilter.c,
   vpx_dsp/intrapred.c). Copyright (c) 2010, The WebM Project authors; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.video.vp9.internal;

import java.util.Arrays;

/**
 * Implements the high bit depth (10 and 12-bit) VP9 pixel kernels on {@code short} samples:
 * inverse transforms, sub-pixel motion compensation, loop filters and intra predictors.
 *
 * <p>Samples are unsigned, from 0 to {@code (1 << bitDepth) - 1}, stored in {@code short}s. The
 * kernels mirror the 8-bit ones of {@link ScalarVp9Dsp} with the clamping range, the loop filter
 * thresholds and the edge defaults of the bit depth, and with 64-bit arithmetic in the
 * transforms, whose coefficients reach 20 bits at 12-bit depth.
 *
 * @implNote This implementation ports libvpx's high bit depth C kernels:
 * {@code vpx_dsp/inv_txfm.c} ({@code vpx_highbd_*}), {@code vp9/common/vp9_idct.c}
 * ({@code vp9_highbd_iht*}), {@code vpx_dsp/vpx_convolve.c} ({@code highbd_convolve*}),
 * {@code vpx_dsp/loopfilter.c} ({@code vpx_highbd_lpf_*}) and
 * {@code vpx_dsp/intrapred.c} ({@code highbd_*_predictor}). The one-dimensional
 * transforms were translated mechanically from the C source. The inner even half of
 * the 8-point DCT keeps libvpx's range check, which libvpx performs in a nested call.
 */
final class Vp9HighDsp {
    /**
     * The DCT constant {@code round(16384 * cos(1 * pi / 64))}, as a {@code long} so that
     * products are 64-bit.
     */
    private static final long COSPI_1_64 = 16364;

    /**
     * The DCT constant for {@code 2 * pi / 64}.
     */
    private static final long COSPI_2_64 = 16305;

    /**
     * The DCT constant for {@code 3 * pi / 64}.
     */
    private static final long COSPI_3_64 = 16207;

    /**
     * The DCT constant for {@code 4 * pi / 64}.
     */
    private static final long COSPI_4_64 = 16069;

    /**
     * The DCT constant for {@code 5 * pi / 64}.
     */
    private static final long COSPI_5_64 = 15893;

    /**
     * The DCT constant for {@code 6 * pi / 64}.
     */
    private static final long COSPI_6_64 = 15679;

    /**
     * The DCT constant for {@code 7 * pi / 64}.
     */
    private static final long COSPI_7_64 = 15426;

    /**
     * The DCT constant for {@code 8 * pi / 64}.
     */
    private static final long COSPI_8_64 = 15137;

    /**
     * The DCT constant for {@code 9 * pi / 64}.
     */
    private static final long COSPI_9_64 = 14811;

    /**
     * The DCT constant for {@code 10 * pi / 64}.
     */
    private static final long COSPI_10_64 = 14449;

    /**
     * The DCT constant for {@code 11 * pi / 64}.
     */
    private static final long COSPI_11_64 = 14053;

    /**
     * The DCT constant for {@code 12 * pi / 64}.
     */
    private static final long COSPI_12_64 = 13623;

    /**
     * The DCT constant for {@code 13 * pi / 64}.
     */
    private static final long COSPI_13_64 = 13160;

    /**
     * The DCT constant for {@code 14 * pi / 64}.
     */
    private static final long COSPI_14_64 = 12665;

    /**
     * The DCT constant for {@code 15 * pi / 64}.
     */
    private static final long COSPI_15_64 = 12140;

    /**
     * The DCT constant for {@code 16 * pi / 64}.
     */
    private static final long COSPI_16_64 = 11585;

    /**
     * The DCT constant for {@code 17 * pi / 64}.
     */
    private static final long COSPI_17_64 = 11003;

    /**
     * The DCT constant for {@code 18 * pi / 64}.
     */
    private static final long COSPI_18_64 = 10394;

    /**
     * The DCT constant for {@code 19 * pi / 64}.
     */
    private static final long COSPI_19_64 = 9760;

    /**
     * The DCT constant for {@code 20 * pi / 64}.
     */
    private static final long COSPI_20_64 = 9102;

    /**
     * The DCT constant for {@code 21 * pi / 64}.
     */
    private static final long COSPI_21_64 = 8423;

    /**
     * The DCT constant for {@code 22 * pi / 64}.
     */
    private static final long COSPI_22_64 = 7723;

    /**
     * The DCT constant for {@code 23 * pi / 64}.
     */
    private static final long COSPI_23_64 = 7005;

    /**
     * The DCT constant for {@code 24 * pi / 64}.
     */
    private static final long COSPI_24_64 = 6270;

    /**
     * The DCT constant for {@code 25 * pi / 64}.
     */
    private static final long COSPI_25_64 = 5520;

    /**
     * The DCT constant for {@code 26 * pi / 64}.
     */
    private static final long COSPI_26_64 = 4756;

    /**
     * The DCT constant for {@code 27 * pi / 64}.
     */
    private static final long COSPI_27_64 = 3981;

    /**
     * The DCT constant for {@code 28 * pi / 64}.
     */
    private static final long COSPI_28_64 = 3196;

    /**
     * The DCT constant for {@code 29 * pi / 64}.
     */
    private static final long COSPI_29_64 = 2404;

    /**
     * The DCT constant for {@code 30 * pi / 64}.
     */
    private static final long COSPI_30_64 = 1606;

    /**
     * The DCT constant for {@code 31 * pi / 64}.
     */
    private static final long COSPI_31_64 = 804;

    /**
     * The first 4-point ADST constant.
     */
    private static final long SINPI_1_9 = 5283;

    /**
     * The second 4-point ADST constant.
     */
    private static final long SINPI_2_9 = 9929;

    /**
     * The third 4-point ADST constant.
     */
    private static final long SINPI_3_9 = 13377;

    /**
     * The fourth 4-point ADST constant.
     */
    private static final long SINPI_4_9 = 15212;

    /**
     * The magnitude from which a transform input is invalid for every bit depth
     * ({@code HIGHBD_VALID_TXFM_MAGNITUDE_RANGE}).
     */
    private static final int VALID_RANGE = 1 << 25;

    /**
     * Prevents instantiation.
     */
    private Vp9HighDsp() {
        throw new AssertionError();
    }

    /**
     * Returns a sample clamped to the range of a bit depth.
     *
     * @param value the sample before clipping
     * @param bitDepth the bit depth
     * @return the clamped value
     */
    static int clip(int value, int bitDepth) {
        var max = (1 << bitDepth) - 1;
        return value < 0 ? 0 : (value > max ? max : value);
    }

    /**
     * Returns whether any of {@code n} transform inputs is out of the valid range
     * ({@code detect_invalid_highbd_input}).
     *
     * @param in the inputs
     * @param inOff the index of the first input
     * @param n the number of inputs
     * @return {@code true} when an input is invalid
     */
    private static boolean invalidInput(int[] in, int inOff, int n) {
        for (var i = 0; i < n; i++) {
            if (Math.abs(in[inOff + i]) >= VALID_RANGE) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns whether any of four transform inputs is out of the valid range.
     *
     * @param a the first input
     * @param b the second input
     * @param c the third input
     * @param d the fourth input
     * @return {@code true} when an input is invalid
     */
    private static boolean invalidInput(int a, int b, int c, int d) {
        return Math.abs(a) >= VALID_RANGE || Math.abs(b) >= VALID_RANGE || Math.abs(c) >= VALID_RANGE
                || Math.abs(d) >= VALID_RANGE;
    }

    /**
     * Adds the inverse transform of a block to the prediction in place, clamping to the bit
     * depth ({@code vp9_highbd_iht*_add}, {@code vp9_highbd_idct*_add}).
     *
     * @param txSize the transform size
     * @param txType the transform type
     * @param eob the number of coded coefficients, positive
     * @param coef the coefficients in raster order
     * @param coefOff the index of the first coefficient
     * @param dst the plane holding the prediction
     * @param dstOff the index of the block's top-left sample
     * @param stride the plane's row stride
     * @param bitDepth the bit depth
     * @param scratch a scratch array of at least {@link Vp9Dsp#TRANSFORM_SCRATCH} entries
     */
    static void inverseTransformAdd(int txSize, int txType, int eob, int[] coef, int coefOff, short[] dst,
                                    int dstOff, int stride, int bitDepth, int[] scratch) {
        if (txType == Vp9Constants.DCT_DCT && eob == 1) {
            var out = (int) ((coef[coefOff] * COSPI_16_64 + 8192) >> 14);
            out = (int) ((out * COSPI_16_64 + 8192) >> 14);
            var shift = txSize == Vp9Constants.TX_4X4 ? 4 : txSize == Vp9Constants.TX_8X8 ? 5 : 6;
            var a1 = (out + (1 << (shift - 1))) >> shift;
            var size = 4 << txSize;
            for (var y = 0; y < size; y++) {
                var row = dstOff + y * stride;
                for (var x = 0; x < size; x++) {
                    dst[row + x] = (short) clip((dst[row + x] & 0xFFFF) + a1, bitDepth);
                }
            }
            return;
        }
        var size = 4 << txSize;
        var rows = txType == Vp9Constants.DCT_DCT ? ScalarVp9Dsp.partialRows(txSize, eob) : size;
        var shift = txSize == Vp9Constants.TX_4X4 ? 4 : txSize == Vp9Constants.TX_8X8 ? 5 : 6;
        var rowAdst = (txType & 2) != 0;
        var colAdst = (txType & 1) != 0;
        var tempIn = size * size;
        var tempOut = tempIn + 32;
        for (var i = 0; i < rows; i++) {
            var input = coefOff + i * size;
            if (txSize == Vp9Constants.TX_32X32 && rows == 32) {
                var zero = 0;
                for (var j = 0; j < 32; j++) {
                    zero |= coef[input + j];
                }
                if (zero == 0) {
                    Arrays.fill(scratch, i * 32, i * 32 + 32, 0);
                    continue;
                }
            }
            transform1d(txSize, rowAdst, coef, input, scratch, i * size);
        }
        if (rows < size) {
            Arrays.fill(scratch, rows * size, size * size, 0);
        }
        var round = 1 << (shift - 1);
        for (var i = 0; i < size; i++) {
            for (var j = 0; j < size; j++) {
                scratch[tempIn + j] = scratch[j * size + i];
            }
            transform1d(txSize, colAdst, scratch, tempIn, scratch, tempOut);
            for (var j = 0; j < size; j++) {
                var index = dstOff + j * stride + i;
                dst[index] = (short) clip((dst[index] & 0xFFFF) + ((scratch[tempOut + j] + round) >> shift), bitDepth);
            }
        }
    }

    /**
     * Computes one row or column of a high bit depth inverse transform.
     *
     * @param txSize the transform size
     * @param adst whether the dimension uses the ADST
     * @param in the input array
     * @param inOff the index of the first input value
     * @param out the output array
     * @param outOff the index of the first output value
     */
    private static void transform1d(int txSize, boolean adst, int[] in, int inOff, int[] out, int outOff) {
        switch (txSize) {
            case Vp9Constants.TX_4X4 -> {
                if (adst) {
                    iadst4(in, inOff, out, outOff);
                } else {
                    idct4(in, inOff, out, outOff);
                }
            }
            case Vp9Constants.TX_8X8 -> {
                if (adst) {
                    iadst8(in, inOff, out, outOff);
                } else {
                    idct8(in, inOff, out, outOff);
                }
            }
            case Vp9Constants.TX_16X16 -> {
                if (adst) {
                    iadst16(in, inOff, out, outOff);
                } else {
                    idct16(in, inOff, out, outOff);
                }
            }
            default -> idct32(in, inOff, out, outOff);
        }
    }

    /**
     * Adds the inverse Walsh-Hadamard transform of a lossless 4x4 block to the prediction
     * ({@code vpx_highbd_iwht4x4_16_add}, {@code vpx_highbd_iwht4x4_1_add}).
     *
     * @param eob the number of coded coefficients, positive
     * @param coef the coefficients in raster order
     * @param coefOff the index of the first coefficient
     * @param dst the plane holding the prediction
     * @param dstOff the index of the block's top-left sample
     * @param stride the plane's row stride
     * @param bitDepth the bit depth
     * @param scratch a scratch array of at least 16 entries
     */
    static void inverseWhtAdd(int eob, int[] coef, int coefOff, short[] dst, int dstOff, int stride, int bitDepth,
                              int[] scratch) {
        if (eob > 1) {
            for (var i = 0; i < 4; i++) {
                var ip = coefOff + i * 4;
                long a1 = coef[ip] >> 2;
                long c1 = coef[ip + 1] >> 2;
                long d1 = coef[ip + 2] >> 2;
                long b1 = coef[ip + 3] >> 2;
                a1 += c1;
                d1 -= b1;
                var e1 = (a1 - d1) >> 1;
                b1 = e1 - b1;
                c1 = e1 - c1;
                a1 -= b1;
                d1 += c1;
                scratch[i * 4] = (int) a1;
                scratch[i * 4 + 1] = (int) b1;
                scratch[i * 4 + 2] = (int) c1;
                scratch[i * 4 + 3] = (int) d1;
            }
            for (var i = 0; i < 4; i++) {
                long a1 = scratch[i];
                long c1 = scratch[4 + i];
                long d1 = scratch[8 + i];
                long b1 = scratch[12 + i];
                a1 += c1;
                d1 -= b1;
                var e1 = (a1 - d1) >> 1;
                b1 = e1 - b1;
                c1 = e1 - c1;
                a1 -= b1;
                d1 += c1;
                var column = dstOff + i;
                addClipped(dst, column, (int) a1, bitDepth);
                addClipped(dst, column + stride, (int) b1, bitDepth);
                addClipped(dst, column + 2 * stride, (int) c1, bitDepth);
                addClipped(dst, column + 3 * stride, (int) d1, bitDepth);
            }
        } else {
            var a1 = coef[coefOff] >> 2;
            var e1 = a1 >> 1;
            a1 -= e1;
            for (var i = 0; i < 4; i++) {
                var value = i == 0 ? a1 : e1;
                var e = value >> 1;
                var a = value - e;
                var column = dstOff + i;
                addClipped(dst, column, a, bitDepth);
                addClipped(dst, column + stride, e, bitDepth);
                addClipped(dst, column + 2 * stride, e, bitDepth);
                addClipped(dst, column + 3 * stride, e, bitDepth);
            }
        }
    }

    /**
     * Adds a residual to a sample, clamping to the bit depth ({@code highbd_clip_pixel_add}).
     *
     * @param dst the plane
     * @param index the sample index
     * @param residual the inverse transform output added to the sample
     * @param bitDepth the bit depth
     */
    private static void addClipped(short[] dst, int index, int residual, int bitDepth) {
        dst[index] = (short) clip((dst[index] & 0xFFFF) + residual, bitDepth);
    }

    /**
     * Predicts a block from a reference with the eight-tap sub-pixel filters, as
     * {@link Vp9Dsp#convolve} does for 8-bit samples ({@code vpx_highbd_convolve8} and its
     * variants).
     *
     * @param src the reference plane
     * @param srcOff the index of the reference sample at the block's integer position
     * @param srcStride the reference row stride
     * @param dst the destination plane
     * @param dstOff the index of the block's top-left sample
     * @param dstStride the destination row stride
     * @param kernels the filter kernels
     * @param kernelBase the index of phase 0 of the block's filter type
     * @param subX the horizontal phase of the first column
     * @param xStep the horizontal step in sixteenths
     * @param subY the vertical phase of the first row
     * @param yStep the vertical step in sixteenths
     * @param w the block width
     * @param h the block height
     * @param average whether to average into the destination
     * @param bitDepth the bit depth
     * @param scratch a scratch array of at least {@link Vp9Dsp#CONVOLVE_SCRATCH} samples
     */
    static void convolve(short[] src, int srcOff, int srcStride, short[] dst, int dstOff, int dstStride,
                         short[] kernels, int kernelBase, int subX, int xStep, int subY, int yStep, int w, int h,
                         boolean average, int bitDepth, short[] scratch) {
        if (xStep == 16 && yStep == 16 && subX == 0 && subY == 0) {
            for (var y = 0; y < h; y++) {
                var s = srcOff + y * srcStride;
                var d = dstOff + y * dstStride;
                if (average) {
                    for (var x = 0; x < w; x++) {
                        dst[d + x] = (short) (((dst[d + x] & 0xFFFF) + (src[s + x] & 0xFFFF) + 1) >> 1);
                    }
                } else {
                    System.arraycopy(src, s, dst, d, w);
                }
            }
        } else if (xStep == 16 && yStep == 16 && subY == 0) {
            horizontal(src, srcOff, srcStride, dst, dstOff, dstStride, kernels, kernelBase, subX, 16, w, h, average,
                    bitDepth);
        } else if (xStep == 16 && yStep == 16 && subX == 0) {
            vertical(src, srcOff, srcStride, dst, dstOff, dstStride, kernels, kernelBase, subY, 16, w, h, average,
                    bitDepth);
        } else {
            var intermediate = (((h - 1) * yStep + subY) >> 4) + 8;
            horizontal(src, srcOff - 3 * srcStride, srcStride, scratch, 0, 64, kernels, kernelBase, subX, xStep, w,
                    intermediate, false, bitDepth);
            vertical(scratch, 3 * 64, 64, dst, dstOff, dstStride, kernels, kernelBase, subY, yStep, w, h, average,
                    bitDepth);
        }
    }

    /**
     * Filters a block horizontally ({@code highbd_convolve_horiz}, {@code highbd_convolve_avg_horiz}).
     *
     * @param src the source plane
     * @param srcOff the index of the source sample at the block's integer position
     * @param srcStride the source row stride
     * @param dst the destination plane
     * @param dstOff the index of the first destination sample
     * @param dstStride the destination row stride
     * @param kernels the filter kernels
     * @param kernelBase the index of phase 0 of the filter type
     * @param x0 the phase of the first column
     * @param xStep the horizontal step in sixteenths
     * @param w the block width
     * @param h the number of rows
     * @param average whether to average into the destination
     * @param bitDepth the bit depth
     */
    private static void horizontal(short[] src, int srcOff, int srcStride, short[] dst, int dstOff, int dstStride,
                                   short[] kernels, int kernelBase, int x0, int xStep, int w, int h, boolean average,
                                   int bitDepth) {
        for (var y = 0; y < h; y++) {
            var s = srcOff + y * srcStride - 3;
            var d = dstOff + y * dstStride;
            var xq = x0;
            for (var x = 0; x < w; x++) {
                var p = s + (xq >> 4);
                var k = kernelBase + (xq & 15) * 8;
                var sum = 0;
                for (var t = 0; t < 8; t++) {
                    sum += (src[p + t] & 0xFFFF) * kernels[k + t];
                }
                var value = clip((sum + 64) >> 7, bitDepth);
                if (average) {
                    value = ((dst[d + x] & 0xFFFF) + value + 1) >> 1;
                }
                dst[d + x] = (short) value;
                xq += xStep;
            }
        }
    }

    /**
     * Filters a block vertically ({@code highbd_convolve_vert}, {@code highbd_convolve_avg_vert}).
     *
     * @param src the source plane
     * @param srcOff the index of the source sample at the block's integer position
     * @param srcStride the source row stride
     * @param dst the destination plane
     * @param dstOff the index of the first destination sample
     * @param dstStride the destination row stride
     * @param kernels the filter kernels
     * @param kernelBase the index of phase 0 of the filter type
     * @param y0 the phase of the first row
     * @param yStep the vertical step in sixteenths
     * @param w the block width
     * @param h the block height
     * @param average whether to average into the destination
     * @param bitDepth the bit depth
     */
    private static void vertical(short[] src, int srcOff, int srcStride, short[] dst, int dstOff, int dstStride,
                                 short[] kernels, int kernelBase, int y0, int yStep, int w, int h, boolean average,
                                 int bitDepth) {
        var base = srcOff - 3 * srcStride;
        for (var x = 0; x < w; x++) {
            var yq = y0;
            for (var y = 0; y < h; y++) {
                var p = base + (yq >> 4) * srcStride + x;
                var k = kernelBase + (yq & 15) * 8;
                var sum = 0;
                for (var t = 0; t < 8; t++) {
                    sum += (src[p + t * srcStride] & 0xFFFF) * kernels[k + t];
                }
                var value = clip((sum + 64) >> 7, bitDepth);
                var d = dstOff + y * dstStride + x;
                if (average) {
                    value = ((dst[d] & 0xFFFF) + value + 1) >> 1;
                }
                dst[d] = (short) value;
                yq += yStep;
            }
        }
    }

    /**
     * Returns whether no difference across or along an edge exceeds the limits, scaled to the
     * bit depth ({@code highbd_filter_mask}).
     *
     * @param limit the interior limit, scaled
     * @param blimit the edge limit, scaled
     * @param p3 the fourth sample before the edge
     * @param p2 the third sample before the edge
     * @param p1 the second sample before the edge
     * @param p0 the sample before the edge
     * @param q0 the sample after the edge
     * @param q1 the second sample after the edge
     * @param q2 the third sample after the edge
     * @param q3 the fourth sample after the edge
     * @return {@code true} when the edge is filtered
     */
    private static boolean filterMask(int limit, int blimit, int p3, int p2, int p1, int p0, int q0, int q1, int q2,
                                      int q3) {
        return Math.abs(p3 - p2) <= limit && Math.abs(p2 - p1) <= limit && Math.abs(p1 - p0) <= limit
                && Math.abs(q1 - q0) <= limit && Math.abs(q2 - q1) <= limit && Math.abs(q3 - q2) <= limit
                && Math.abs(p0 - q0) * 2 + Math.abs(p1 - q1) / 2 <= blimit;
    }

    /**
     * Returns a value clamped to the signed range of a bit depth ({@code signed_char_clamp_high}).
     *
     * @param value the value to clamp to the signed range of the bit depth
     * @param shift the bit depth minus 8
     * @return the clamped value
     */
    private static int signedClamp(int value, int shift) {
        var low = -128 << shift;
        var high = (128 << shift) - 1;
        return value < low ? low : (value > high ? high : value);
    }

    /**
     * Applies the 4-tap filter to one line across an edge, the mask already known to be set
     * ({@code highbd_filter4}).
     *
     * @param s the plane
     * @param off the index of the first sample after the edge
     * @param step the distance between consecutive samples across the edge
     * @param thresh the high edge variance threshold, scaled
     * @param shift the bit depth minus 8
     */
    private static void filter4(short[] s, int off, int step, int thresh, int shift) {
        var op1 = s[off - 2 * step] & 0xFFFF;
        var op0 = s[off - step] & 0xFFFF;
        var oq0 = s[off] & 0xFFFF;
        var oq1 = s[off + step] & 0xFFFF;
        var offset = 0x80 << shift;
        var ps1 = op1 - offset;
        var ps0 = op0 - offset;
        var qs0 = oq0 - offset;
        var qs1 = oq1 - offset;
        var hev = Math.abs(op1 - op0) > thresh || Math.abs(oq1 - oq0) > thresh;
        var filter = hev ? signedClamp(ps1 - qs1, shift) : 0;
        filter = signedClamp(filter + 3 * (qs0 - ps0), shift);
        var filter1 = signedClamp(filter + 4, shift) >> 3;
        var filter2 = signedClamp(filter + 3, shift) >> 3;
        s[off] = (short) (signedClamp(qs0 - filter1, shift) + offset);
        s[off - step] = (short) (signedClamp(ps0 + filter2, shift) + offset);
        if (!hev) {
            filter = (filter1 + 1) >> 1;
            s[off + step] = (short) (signedClamp(qs1 - filter, shift) + offset);
            s[off - 2 * step] = (short) (signedClamp(ps1 + filter, shift) + offset);
        }
    }

    /**
     * Applies the 4-tap loop filter to {@code count} lines across an edge
     * ({@code vpx_highbd_lpf_horizontal_4}, {@code vpx_highbd_lpf_vertical_4}).
     *
     * @param s the plane
     * @param off the index of the first sample after the edge, on the first line
     * @param step the distance between consecutive samples across the edge
     * @param along the distance between consecutive lines
     * @param count the number of lines
     * @param blimit the edge limit, at 8-bit scale
     * @param limit the interior limit, at 8-bit scale
     * @param thresh the high edge variance threshold, at 8-bit scale
     * @param bitDepth the bit depth
     */
    static void lpf4(short[] s, int off, int step, int along, int count, int blimit, int limit, int thresh,
                     int bitDepth) {
        var shift = bitDepth - 8;
        var limit16 = limit << shift;
        var blimit16 = blimit << shift;
        var thresh16 = thresh << shift;
        for (var i = 0; i < count; i++, off += along) {
            var p3 = s[off - 4 * step] & 0xFFFF;
            var p2 = s[off - 3 * step] & 0xFFFF;
            var p1 = s[off - 2 * step] & 0xFFFF;
            var p0 = s[off - step] & 0xFFFF;
            var q0 = s[off] & 0xFFFF;
            var q1 = s[off + step] & 0xFFFF;
            var q2 = s[off + 2 * step] & 0xFFFF;
            var q3 = s[off + 3 * step] & 0xFFFF;
            if (filterMask(limit16, blimit16, p3, p2, p1, p0, q0, q1, q2, q3)) {
                filter4(s, off, step, thresh16, shift);
            }
        }
    }

    /**
     * Applies the 8-tap loop filter to {@code count} lines across an edge
     * ({@code vpx_highbd_lpf_horizontal_8}, {@code vpx_highbd_lpf_vertical_8}).
     *
     * @param s the plane
     * @param off the index of the first sample after the edge, on the first line
     * @param step the distance between consecutive samples across the edge
     * @param along the distance between consecutive lines
     * @param count the number of lines
     * @param blimit the edge limit, at 8-bit scale
     * @param limit the interior limit, at 8-bit scale
     * @param thresh the high edge variance threshold, at 8-bit scale
     * @param bitDepth the bit depth
     */
    static void lpf8(short[] s, int off, int step, int along, int count, int blimit, int limit, int thresh,
                     int bitDepth) {
        var shift = bitDepth - 8;
        var limit16 = limit << shift;
        var blimit16 = blimit << shift;
        var thresh16 = thresh << shift;
        var flat = 1 << shift;
        for (var i = 0; i < count; i++, off += along) {
            var p3 = s[off - 4 * step] & 0xFFFF;
            var p2 = s[off - 3 * step] & 0xFFFF;
            var p1 = s[off - 2 * step] & 0xFFFF;
            var p0 = s[off - step] & 0xFFFF;
            var q0 = s[off] & 0xFFFF;
            var q1 = s[off + step] & 0xFFFF;
            var q2 = s[off + 2 * step] & 0xFFFF;
            var q3 = s[off + 3 * step] & 0xFFFF;
            if (!filterMask(limit16, blimit16, p3, p2, p1, p0, q0, q1, q2, q3)) {
                continue;
            }
            if (flat4(flat, p3, p2, p1, p0, q0, q1, q2, q3)) {
                filter8Flat(s, off, step, p3, p2, p1, p0, q0, q1, q2, q3);
            } else {
                filter4(s, off, step, thresh16, shift);
            }
        }
    }

    /**
     * Returns whether the four samples on each side of an edge are within a threshold of the
     * edge samples ({@code highbd_flat_mask4}).
     *
     * @param threshold the threshold, {@code 1 << (bitDepth - 8)}
     * @param p3 the fourth sample before the edge
     * @param p2 the third sample before the edge
     * @param p1 the second sample before the edge
     * @param p0 the sample before the edge
     * @param q0 the sample after the edge
     * @param q1 the second sample after the edge
     * @param q2 the third sample after the edge
     * @param q3 the fourth sample after the edge
     * @return {@code true} when the region is flat
     */
    private static boolean flat4(int threshold, int p3, int p2, int p1, int p0, int q0, int q1, int q2, int q3) {
        return Math.abs(p1 - p0) <= threshold && Math.abs(q1 - q0) <= threshold && Math.abs(p2 - p0) <= threshold
                && Math.abs(q2 - q0) <= threshold && Math.abs(p3 - p0) <= threshold && Math.abs(q3 - q0) <= threshold;
    }

    /**
     * Applies the 7-tap smoothing of a flat 8-wide edge ({@code highbd_filter8}).
     *
     * @param s the plane
     * @param off the index of the first sample after the edge
     * @param step the distance between consecutive samples across the edge
     * @param p3 the fourth sample before the edge
     * @param p2 the third sample before the edge
     * @param p1 the second sample before the edge
     * @param p0 the sample before the edge
     * @param q0 the sample after the edge
     * @param q1 the second sample after the edge
     * @param q2 the third sample after the edge
     * @param q3 the fourth sample after the edge
     */
    private static void filter8Flat(short[] s, int off, int step, int p3, int p2, int p1, int p0, int q0, int q1,
                                    int q2, int q3) {
        s[off - 3 * step] = (short) ((p3 + p3 + p3 + 2 * p2 + p1 + p0 + q0 + 4) >> 3);
        s[off - 2 * step] = (short) ((p3 + p3 + p2 + 2 * p1 + p0 + q0 + q1 + 4) >> 3);
        s[off - step] = (short) ((p3 + p2 + p1 + 2 * p0 + q0 + q1 + q2 + 4) >> 3);
        s[off] = (short) ((p2 + p1 + p0 + 2 * q0 + q1 + q2 + q3 + 4) >> 3);
        s[off + step] = (short) ((p1 + p0 + q0 + 2 * q1 + q2 + q3 + q3 + 4) >> 3);
        s[off + 2 * step] = (short) ((p0 + q0 + q1 + 2 * q2 + q3 + q3 + q3 + 4) >> 3);
    }

    /**
     * Applies the 16-tap loop filter to {@code count} lines across an edge
     * ({@code vpx_highbd_lpf_horizontal_16}, {@code vpx_highbd_lpf_vertical_16}).
     *
     * @param s the plane
     * @param off the index of the first sample after the edge, on the first line
     * @param step the distance between consecutive samples across the edge
     * @param along the distance between consecutive lines
     * @param count the number of lines
     * @param blimit the edge limit, at 8-bit scale
     * @param limit the interior limit, at 8-bit scale
     * @param thresh the high edge variance threshold, at 8-bit scale
     * @param bitDepth the bit depth
     */
    static void lpf16(short[] s, int off, int step, int along, int count, int blimit, int limit, int thresh,
                      int bitDepth) {
        var shift = bitDepth - 8;
        var limit16 = limit << shift;
        var blimit16 = blimit << shift;
        var thresh16 = thresh << shift;
        var flat = 1 << shift;
        for (var i = 0; i < count; i++, off += along) {
            var p3 = s[off - 4 * step] & 0xFFFF;
            var p2 = s[off - 3 * step] & 0xFFFF;
            var p1 = s[off - 2 * step] & 0xFFFF;
            var p0 = s[off - step] & 0xFFFF;
            var q0 = s[off] & 0xFFFF;
            var q1 = s[off + step] & 0xFFFF;
            var q2 = s[off + 2 * step] & 0xFFFF;
            var q3 = s[off + 3 * step] & 0xFFFF;
            if (!filterMask(limit16, blimit16, p3, p2, p1, p0, q0, q1, q2, q3)) {
                continue;
            }
            if (!flat4(flat, p3, p2, p1, p0, q0, q1, q2, q3)) {
                filter4(s, off, step, thresh16, shift);
                continue;
            }
            var p7 = s[off - 8 * step] & 0xFFFF;
            var p6 = s[off - 7 * step] & 0xFFFF;
            var p5 = s[off - 6 * step] & 0xFFFF;
            var p4 = s[off - 5 * step] & 0xFFFF;
            var q4 = s[off + 4 * step] & 0xFFFF;
            var q5 = s[off + 5 * step] & 0xFFFF;
            var q6 = s[off + 6 * step] & 0xFFFF;
            var q7 = s[off + 7 * step] & 0xFFFF;
            var flat2 = Math.abs(p4 - p0) <= flat && Math.abs(q4 - q0) <= flat && Math.abs(p5 - p0) <= flat
                    && Math.abs(q5 - q0) <= flat && Math.abs(p6 - p0) <= flat && Math.abs(q6 - q0) <= flat
                    && Math.abs(p7 - p0) <= flat && Math.abs(q7 - q0) <= flat;
            if (!flat2) {
                filter8Flat(s, off, step, p3, p2, p1, p0, q0, q1, q2, q3);
                continue;
            }
            s[off - 7 * step] = (short) ((p7 * 7 + p6 * 2 + p5 + p4 + p3 + p2 + p1 + p0 + q0 + 8) >> 4);
            s[off - 6 * step] = (short) ((p7 * 6 + p6 + p5 * 2 + p4 + p3 + p2 + p1 + p0 + q0 + q1 + 8) >> 4);
            s[off - 5 * step] = (short) ((p7 * 5 + p6 + p5 + p4 * 2 + p3 + p2 + p1 + p0 + q0 + q1 + q2 + 8) >> 4);
            s[off - 4 * step] = (short) ((p7 * 4 + p6 + p5 + p4 + p3 * 2 + p2 + p1 + p0 + q0 + q1 + q2 + q3 + 8) >> 4);
            s[off - 3 * step] = (short) ((p7 * 3 + p6 + p5 + p4 + p3 + p2 * 2 + p1 + p0 + q0 + q1 + q2 + q3 + q4 + 8)
                    >> 4);
            s[off - 2 * step] = (short) ((p7 * 2 + p6 + p5 + p4 + p3 + p2 + p1 * 2 + p0 + q0 + q1 + q2 + q3 + q4 + q5
                    + 8) >> 4);
            s[off - step] = (short) ((p7 + p6 + p5 + p4 + p3 + p2 + p1 + p0 * 2 + q0 + q1 + q2 + q3 + q4 + q5 + q6 + 8)
                    >> 4);
            s[off] = (short) ((p6 + p5 + p4 + p3 + p2 + p1 + p0 + q0 * 2 + q1 + q2 + q3 + q4 + q5 + q6 + q7 + 8) >> 4);
            s[off + step] = (short) ((p5 + p4 + p3 + p2 + p1 + p0 + q0 + q1 * 2 + q2 + q3 + q4 + q5 + q6 + q7 * 2 + 8)
                    >> 4);
            s[off + 2 * step] = (short) ((p4 + p3 + p2 + p1 + p0 + q0 + q1 + q2 * 2 + q3 + q4 + q5 + q6 + q7 * 3 + 8)
                    >> 4);
            s[off + 3 * step] = (short) ((p3 + p2 + p1 + p0 + q0 + q1 + q2 + q3 * 2 + q4 + q5 + q6 + q7 * 4 + 8) >> 4);
            s[off + 4 * step] = (short) ((p2 + p1 + p0 + q0 + q1 + q2 + q3 + q4 * 2 + q5 + q6 + q7 * 5 + 8) >> 4);
            s[off + 5 * step] = (short) ((p1 + p0 + q0 + q1 + q2 + q3 + q4 + q5 * 2 + q6 + q7 * 6 + 8) >> 4);
            s[off + 6 * step] = (short) ((p0 + q0 + q1 + q2 + q3 + q4 + q5 + q6 * 2 + q7 * 7 + 8) >> 4);
        }
    }

    /**
     * Predicts a square block from its decoded neighbors, as {@link Vp9Dsp#intraPredict} does
     * for 8-bit samples ({@code highbd_*_predictor}).
     *
     * @param mode the intra mode
     * @param txSize the block size
     * @param dcEdges the edges available to the DC predictor, bit 0 above and bit 1 left
     * @param dst the destination plane
     * @param dstOff the index of the block's top-left sample
     * @param stride the destination row stride
     * @param above the array holding the above row
     * @param aboveOff the index of the first above sample
     * @param left the array holding the left column
     * @param leftOff the index of the first left sample
     * @param bitDepth the bit depth
     */
    static void intraPredict(int mode, int txSize, int dcEdges, short[] dst, int dstOff, int stride,
                             short[] above, int aboveOff, short[] left, int leftOff, int bitDepth) {
        var bs = 4 << txSize;
        switch (mode) {
            case Vp9Constants.DC_PRED -> predictDc(dcEdges, bs, dst, dstOff, stride, above, aboveOff, left, leftOff, bitDepth);
            case Vp9Constants.V_PRED -> {
                for (var r = 0; r < bs; r++) {
                    System.arraycopy(above, aboveOff, dst, dstOff + r * stride, bs);
                }
            }
            case Vp9Constants.H_PRED -> {
                for (var r = 0; r < bs; r++) {
                    Arrays.fill(dst, dstOff + r * stride, dstOff + r * stride + bs, left[leftOff + r]);
                }
            }
            case Vp9Constants.TM_PRED -> {
                var topLeft = above[aboveOff - 1] & 0xFFFF;
                for (var r = 0; r < bs; r++) {
                    var l = (left[leftOff + r] & 0xFFFF) - topLeft;
                    var row = dstOff + r * stride;
                    for (var c = 0; c < bs; c++) {
                        dst[row + c] = (short) clip(l + (above[aboveOff + c] & 0xFFFF), bitDepth);
                    }
                }
            }
            default -> {
                if (bs == 4) {
                    predictDirectional4(mode, dst, dstOff, stride, above, aboveOff, left, leftOff);
                } else {
                    predictDirectional(mode, bs, dst, dstOff, stride, above, aboveOff, left, leftOff);
                }
            }
        }
    }

    /**
     * Fills a block with the DC prediction ({@code dc_predictor}, {@code dc_top_predictor},
     * {@code dc_left_predictor}, {@code dc_128_predictor}).
     *
     * @param dcEdges bit 0 when the above row is used, bit 1 when the left column is
     * @param bs the block size
     * @param dst the destination plane
     * @param dstOff the index of the block's top-left sample
     * @param stride the destination row stride
     * @param above the array holding the above row
     * @param aboveOff the index of the first above sample
     * @param left the array holding the left column
     * @param leftOff the index of the first left sample
     * @param bitDepth the bit depth
     */
    private static void predictDc(int dcEdges, int bs, short[] dst, int dstOff, int stride, short[] above,
                                  int aboveOff, short[] left, int leftOff, int bitDepth) {
        int value;
        var sum = 0;
        switch (dcEdges) {
            case 3 -> {
                for (var i = 0; i < bs; i++) {
                    sum += (above[aboveOff + i] & 0xFFFF) + (left[leftOff + i] & 0xFFFF);
                }
                value = (sum + bs) / (2 * bs);
            }
            case 1 -> {
                for (var i = 0; i < bs; i++) {
                    sum += above[aboveOff + i] & 0xFFFF;
                }
                value = (sum + (bs >> 1)) / bs;
            }
            case 2 -> {
                for (var i = 0; i < bs; i++) {
                    sum += left[leftOff + i] & 0xFFFF;
                }
                value = (sum + (bs >> 1)) / bs;
            }
            default -> value = 128 << (bitDepth - 8);
        }
        for (var r = 0; r < bs; r++) {
            Arrays.fill(dst, dstOff + r * stride, dstOff + r * stride + bs, (short) value);
        }
    }

    /**
     * Returns the three-tap smoothed sample {@code (a + 2b + c + 2) / 4}.
     *
     * @param a the first sample
     * @param b the middle sample
     * @param c the last sample
     * @return the smoothed sample
     */
    private static int avg3(int a, int b, int c) {
        return (a + 2 * b + c + 2) >> 2;
    }

    /**
     * Returns the rounded average of two samples.
     *
     * @param a the first sample
     * @param b the second sample
     * @return the average
     */
    private static int avg2(int a, int b) {
        return (a + b + 1) >> 1;
    }

    /**
     * Predicts a 4x4 block with a directional mode, using the dedicated 4x4 predictors of
     * libvpx ({@code vpx_d*_predictor_4x4_c}), which read the true above-right samples.
     *
     * @param mode the directional mode
     * @param dst the destination plane
     * @param dstOff the index of the block's top-left sample
     * @param stride the destination row stride
     * @param above the array holding the above row
     * @param aboveOff the index of the first above sample
     * @param left the array holding the left column
     * @param leftOff the index of the first left sample
     */
    private static void predictDirectional4(int mode, short[] dst, int dstOff, int stride, short[] above,
                                            int aboveOff, short[] left, int leftOff) {
        var r1 = dstOff + stride;
        var r2 = dstOff + 2 * stride;
        var r3 = dstOff + 3 * stride;
        switch (mode) {
            case Vp9Constants.D207_PRED -> {
                var i = left[leftOff] & 0xFFFF;
                var j = left[leftOff + 1] & 0xFFFF;
                var k = left[leftOff + 2] & 0xFFFF;
                var l = left[leftOff + 3] & 0xFFFF;
                dst[dstOff] = (short) avg2(i, j);
                dst[dstOff + 2] = dst[r1] = (short) avg2(j, k);
                dst[r1 + 2] = dst[r2] = (short) avg2(k, l);
                dst[dstOff + 1] = (short) avg3(i, j, k);
                dst[dstOff + 3] = dst[r1 + 1] = (short) avg3(j, k, l);
                dst[r1 + 3] = dst[r2 + 1] = (short) avg3(k, l, l);
                dst[r2 + 3] = dst[r2 + 2] = dst[r3] = dst[r3 + 1] = dst[r3 + 2] = dst[r3 + 3] = (short) l;
            }
            case Vp9Constants.D63_PRED -> {
                var a = above[aboveOff] & 0xFFFF;
                var b = above[aboveOff + 1] & 0xFFFF;
                var c = above[aboveOff + 2] & 0xFFFF;
                var d = above[aboveOff + 3] & 0xFFFF;
                var e = above[aboveOff + 4] & 0xFFFF;
                var f = above[aboveOff + 5] & 0xFFFF;
                var g = above[aboveOff + 6] & 0xFFFF;
                dst[dstOff] = (short) avg2(a, b);
                dst[dstOff + 1] = dst[r2] = (short) avg2(b, c);
                dst[dstOff + 2] = dst[r2 + 1] = (short) avg2(c, d);
                dst[dstOff + 3] = dst[r2 + 2] = (short) avg2(d, e);
                dst[r2 + 3] = (short) avg2(e, f);
                dst[r1] = (short) avg3(a, b, c);
                dst[r1 + 1] = dst[r3] = (short) avg3(b, c, d);
                dst[r1 + 2] = dst[r3 + 1] = (short) avg3(c, d, e);
                dst[r1 + 3] = dst[r3 + 2] = (short) avg3(d, e, f);
                dst[r3 + 3] = (short) avg3(e, f, g);
            }
            case Vp9Constants.D45_PRED -> {
                var a = above[aboveOff] & 0xFFFF;
                var b = above[aboveOff + 1] & 0xFFFF;
                var c = above[aboveOff + 2] & 0xFFFF;
                var d = above[aboveOff + 3] & 0xFFFF;
                var e = above[aboveOff + 4] & 0xFFFF;
                var f = above[aboveOff + 5] & 0xFFFF;
                var g = above[aboveOff + 6] & 0xFFFF;
                var h = above[aboveOff + 7] & 0xFFFF;
                dst[dstOff] = (short) avg3(a, b, c);
                dst[dstOff + 1] = dst[r1] = (short) avg3(b, c, d);
                dst[dstOff + 2] = dst[r1 + 1] = dst[r2] = (short) avg3(c, d, e);
                dst[dstOff + 3] = dst[r1 + 2] = dst[r2 + 1] = dst[r3] = (short) avg3(d, e, f);
                dst[r1 + 3] = dst[r2 + 2] = dst[r3 + 1] = (short) avg3(e, f, g);
                dst[r2 + 3] = dst[r3 + 2] = (short) avg3(f, g, h);
                dst[r3 + 3] = (short) h;
            }
            case Vp9Constants.D117_PRED -> {
                var i = left[leftOff] & 0xFFFF;
                var j = left[leftOff + 1] & 0xFFFF;
                var k = left[leftOff + 2] & 0xFFFF;
                var x = above[aboveOff - 1] & 0xFFFF;
                var a = above[aboveOff] & 0xFFFF;
                var b = above[aboveOff + 1] & 0xFFFF;
                var c = above[aboveOff + 2] & 0xFFFF;
                var d = above[aboveOff + 3] & 0xFFFF;
                dst[dstOff] = dst[r2 + 1] = (short) avg2(x, a);
                dst[dstOff + 1] = dst[r2 + 2] = (short) avg2(a, b);
                dst[dstOff + 2] = dst[r2 + 3] = (short) avg2(b, c);
                dst[dstOff + 3] = (short) avg2(c, d);
                dst[r3] = (short) avg3(k, j, i);
                dst[r2] = (short) avg3(j, i, x);
                dst[r1] = dst[r3 + 1] = (short) avg3(i, x, a);
                dst[r1 + 1] = dst[r3 + 2] = (short) avg3(x, a, b);
                dst[r1 + 2] = dst[r3 + 3] = (short) avg3(a, b, c);
                dst[r1 + 3] = (short) avg3(b, c, d);
            }
            case Vp9Constants.D135_PRED -> {
                var i = left[leftOff] & 0xFFFF;
                var j = left[leftOff + 1] & 0xFFFF;
                var k = left[leftOff + 2] & 0xFFFF;
                var l = left[leftOff + 3] & 0xFFFF;
                var x = above[aboveOff - 1] & 0xFFFF;
                var a = above[aboveOff] & 0xFFFF;
                var b = above[aboveOff + 1] & 0xFFFF;
                var c = above[aboveOff + 2] & 0xFFFF;
                var d = above[aboveOff + 3] & 0xFFFF;
                dst[r3] = (short) avg3(j, k, l);
                dst[r3 + 1] = dst[r2] = (short) avg3(i, j, k);
                dst[r3 + 2] = dst[r2 + 1] = dst[r1] = (short) avg3(x, i, j);
                dst[r3 + 3] = dst[r2 + 2] = dst[r1 + 1] = dst[dstOff] = (short) avg3(a, x, i);
                dst[r2 + 3] = dst[r1 + 2] = dst[dstOff + 1] = (short) avg3(b, a, x);
                dst[r1 + 3] = dst[dstOff + 2] = (short) avg3(c, b, a);
                dst[dstOff + 3] = (short) avg3(d, c, b);
            }
            default -> {
                var i = left[leftOff] & 0xFFFF;
                var j = left[leftOff + 1] & 0xFFFF;
                var k = left[leftOff + 2] & 0xFFFF;
                var l = left[leftOff + 3] & 0xFFFF;
                var x = above[aboveOff - 1] & 0xFFFF;
                var a = above[aboveOff] & 0xFFFF;
                var b = above[aboveOff + 1] & 0xFFFF;
                var c = above[aboveOff + 2] & 0xFFFF;
                dst[dstOff] = dst[r1 + 2] = (short) avg2(i, x);
                dst[r1] = dst[r2 + 2] = (short) avg2(j, i);
                dst[r2] = dst[r3 + 2] = (short) avg2(k, j);
                dst[r3] = (short) avg2(l, k);
                dst[dstOff + 3] = (short) avg3(a, b, c);
                dst[dstOff + 2] = (short) avg3(x, a, b);
                dst[dstOff + 1] = dst[r1 + 3] = (short) avg3(i, x, a);
                dst[r1 + 1] = dst[r2 + 3] = (short) avg3(j, i, x);
                dst[r2 + 1] = dst[r3 + 3] = (short) avg3(k, j, i);
                dst[r3 + 1] = (short) avg3(l, k, j);
            }
        }
    }

    /**
     * Returns entry {@code k} of the outer border of the 135 degree predictor, which runs from
     * the bottom-left (entry 0) up the left column, through the top-left corner (entry
     * {@code bs - 1}) and along the above row ({@code d135_predictor}).
     *
     * @param k the border entry, from 0 to {@code 2 * bs - 2}
     * @param bs the block size
     * @param above the array holding the above row
     * @param aboveOff the index of the first above sample
     * @param left the array holding the left column
     * @param leftOff the index of the first left sample
     * @return the smoothed sample
     */
    private static int d135Border(int k, int bs, short[] above, int aboveOff, short[] left, int leftOff) {
        if (k < bs - 2) {
            return avg3(left[leftOff + bs - 3 - k] & 0xFFFF, left[leftOff + bs - 2 - k] & 0xFFFF,
                    left[leftOff + bs - 1 - k] & 0xFFFF);
        }
        if (k == bs - 2) {
            return avg3(above[aboveOff - 1] & 0xFFFF, left[leftOff] & 0xFFFF, left[leftOff + 1] & 0xFFFF);
        }
        if (k == bs - 1) {
            return avg3(left[leftOff] & 0xFFFF, above[aboveOff - 1] & 0xFFFF, above[aboveOff] & 0xFFFF);
        }
        if (k == bs) {
            return avg3(above[aboveOff - 1] & 0xFFFF, above[aboveOff] & 0xFFFF, above[aboveOff + 1] & 0xFFFF);
        }
        var i = k - bs - 1;
        return avg3(above[aboveOff + i] & 0xFFFF, above[aboveOff + i + 1] & 0xFFFF, above[aboveOff + i + 2] & 0xFFFF);
    }

    /**
     * Predicts a block of 8, 16 or 32 samples with a directional mode ({@code d207_predictor},
     * {@code d63_predictor}, {@code d45_predictor}, {@code d117_predictor},
     * {@code d135_predictor}, {@code d153_predictor}).
     *
     * @param mode the directional mode
     * @param bs the block size
     * @param dst the destination plane
     * @param dstOff the index of the block's top-left sample
     * @param stride the destination row stride
     * @param above the array holding the above row
     * @param aboveOff the index of the first above sample
     * @param left the array holding the left column
     * @param leftOff the index of the first left sample
     */
    private static void predictDirectional(int mode, int bs, short[] dst, int dstOff, int stride, short[] above,
                                           int aboveOff, short[] left, int leftOff) {
        switch (mode) {
            case Vp9Constants.D207_PRED -> {
                var d = dstOff;
                for (var r = 0; r < bs - 1; r++) {
                    dst[d + r * stride] = (short) avg2(left[leftOff + r] & 0xFFFF, left[leftOff + r + 1] & 0xFFFF);
                }
                dst[d + (bs - 1) * stride] = left[leftOff + bs - 1];
                d++;
                for (var r = 0; r < bs - 2; r++) {
                    dst[d + r * stride] = (short) avg3(left[leftOff + r] & 0xFFFF, left[leftOff + r + 1] & 0xFFFF,
                            left[leftOff + r + 2] & 0xFFFF);
                }
                dst[d + (bs - 2) * stride] = (short) avg3(left[leftOff + bs - 2] & 0xFFFF, left[leftOff + bs - 1] & 0xFFFF,
                        left[leftOff + bs - 1] & 0xFFFF);
                dst[d + (bs - 1) * stride] = left[leftOff + bs - 1];
                d++;
                for (var c = 0; c < bs - 2; c++) {
                    dst[d + (bs - 1) * stride + c] = left[leftOff + bs - 1];
                }
                for (var r = bs - 2; r >= 0; r--) {
                    for (var c = 0; c < bs - 2; c++) {
                        dst[d + r * stride + c] = dst[d + (r + 1) * stride + c - 2];
                    }
                }
            }
            case Vp9Constants.D63_PRED -> {
                for (var c = 0; c < bs; c++) {
                    dst[dstOff + c] = (short) avg2(above[aboveOff + c] & 0xFFFF, above[aboveOff + c + 1] & 0xFFFF);
                    dst[dstOff + stride + c] = (short) avg3(above[aboveOff + c] & 0xFFFF, above[aboveOff + c + 1] & 0xFFFF,
                            above[aboveOff + c + 2] & 0xFFFF);
                }
                var last = above[aboveOff + bs - 1];
                for (int r = 2, size = bs - 2; r < bs; r += 2, size--) {
                    var row0 = dstOff + r * stride;
                    var row1 = row0 + stride;
                    System.arraycopy(dst, dstOff + (r >> 1), dst, row0, size);
                    Arrays.fill(dst, row0 + size, row0 + bs, last);
                    System.arraycopy(dst, dstOff + stride + (r >> 1), dst, row1, size);
                    Arrays.fill(dst, row1 + size, row1 + bs, last);
                }
            }
            case Vp9Constants.D45_PRED -> {
                var aboveRight = above[aboveOff + bs - 1];
                for (var x = 0; x < bs - 1; x++) {
                    dst[dstOff + x] = (short) avg3(above[aboveOff + x] & 0xFFFF, above[aboveOff + x + 1] & 0xFFFF,
                            above[aboveOff + x + 2] & 0xFFFF);
                }
                dst[dstOff + bs - 1] = aboveRight;
                var d = dstOff + stride;
                for (int x = 1, size = bs - 2; x < bs; x++, size--) {
                    System.arraycopy(dst, dstOff + x, dst, d, size);
                    Arrays.fill(dst, d + size, d + bs, aboveRight);
                    d += stride;
                }
            }
            case Vp9Constants.D117_PRED -> {
                for (var c = 0; c < bs; c++) {
                    dst[dstOff + c] = (short) avg2(above[aboveOff + c - 1] & 0xFFFF, above[aboveOff + c] & 0xFFFF);
                }
                var d = dstOff + stride;
                dst[d] = (short) avg3(left[leftOff] & 0xFFFF, above[aboveOff - 1] & 0xFFFF, above[aboveOff] & 0xFFFF);
                for (var c = 1; c < bs; c++) {
                    dst[d + c] = (short) avg3(above[aboveOff + c - 2] & 0xFFFF, above[aboveOff + c - 1] & 0xFFFF,
                            above[aboveOff + c] & 0xFFFF);
                }
                d += stride;
                dst[d] = (short) avg3(above[aboveOff - 1] & 0xFFFF, left[leftOff] & 0xFFFF, left[leftOff + 1] & 0xFFFF);
                for (var r = 3; r < bs; r++) {
                    dst[d + (r - 2) * stride] = (short) avg3(left[leftOff + r - 3] & 0xFFFF, left[leftOff + r - 2] & 0xFFFF,
                            left[leftOff + r - 1] & 0xFFFF);
                }
                for (var r = 2; r < bs; r++) {
                    for (var c = 1; c < bs; c++) {
                        dst[d + c] = dst[d - 2 * stride + c - 1];
                    }
                    d += stride;
                }
            }
            case Vp9Constants.D135_PRED -> {
                for (var j = 0; j < bs; j++) {
                    dst[dstOff + j] = (short) d135Border(bs - 1 + j, bs, above, aboveOff, left, leftOff);
                }
                for (var i = 1; i < bs; i++) {
                    dst[dstOff + i * stride] = (short) d135Border(bs - 1 - i, bs, above, aboveOff, left, leftOff);
                }
                for (var i = 1; i < bs; i++) {
                    var row = dstOff + i * stride;
                    for (var j = 1; j < bs; j++) {
                        dst[row + j] = dst[row - stride + j - 1];
                    }
                }
            }
            default -> {
                var d = dstOff;
                dst[d] = (short) avg2(above[aboveOff - 1] & 0xFFFF, left[leftOff] & 0xFFFF);
                for (var r = 1; r < bs; r++) {
                    dst[d + r * stride] = (short) avg2(left[leftOff + r - 1] & 0xFFFF, left[leftOff + r] & 0xFFFF);
                }
                d++;
                dst[d] = (short) avg3(left[leftOff] & 0xFFFF, above[aboveOff - 1] & 0xFFFF, above[aboveOff] & 0xFFFF);
                dst[d + stride] = (short) avg3(above[aboveOff - 1] & 0xFFFF, left[leftOff] & 0xFFFF, left[leftOff + 1] & 0xFFFF);
                for (var r = 2; r < bs; r++) {
                    dst[d + r * stride] = (short) avg3(left[leftOff + r - 2] & 0xFFFF, left[leftOff + r - 1] & 0xFFFF,
                            left[leftOff + r] & 0xFFFF);
                }
                d++;
                for (var c = 0; c < bs - 2; c++) {
                    dst[d + c] = (short) avg3(above[aboveOff + c - 1] & 0xFFFF, above[aboveOff + c] & 0xFFFF,
                            above[aboveOff + c + 1] & 0xFFFF);
                }
                d += stride;
                for (var r = 1; r < bs; r++) {
                    for (var c = 0; c < bs - 2; c++) {
                        dst[d + c] = dst[d - stride + c - 2];
                    }
                    d += stride;
                }
            }
        }
    }

    /**
     * Computes the high bit depth 4-point inverse DCT of one row or column ({@code vpx_highbd_idct4_c}).
     *
     * @param in the input array
     * @param inOff the index of the first input value
     * @param out the output array
     * @param outOff the index of the first output value
     */
    private static void idct4(int[] in, int inOff, int[] out, int outOff) {
        int step_0 = 0, step_1 = 0, step_2 = 0, step_3 = 0;
        long temp1 = 0;
        long temp2 = 0;
        if (invalidInput(in, inOff, 4)) {
            Arrays.fill(out, outOff, outOff + 4, 0);
            return;
        }
        temp1 = (in[inOff + 0] + in[inOff + 2]) * (long) COSPI_16_64;
        temp2 = (in[inOff + 0] - in[inOff + 2]) * (long) COSPI_16_64;
        step_0 = (int) ((temp1 + 8192) >> 14);
        step_1 = (int) ((temp2 + 8192) >> 14);
        temp1 = in[inOff + 1] * (long) COSPI_24_64 - in[inOff + 3] * (long) COSPI_8_64;
        temp2 = in[inOff + 1] * (long) COSPI_8_64 + in[inOff + 3] * (long) COSPI_24_64;
        step_2 = (int) ((temp1 + 8192) >> 14);
        step_3 = (int) ((temp2 + 8192) >> 14);
        out[outOff + 0] = (int) (step_0 + step_3);
        out[outOff + 1] = (int) (step_1 + step_2);
        out[outOff + 2] = (int) (step_1 - step_2);
        out[outOff + 3] = (int) (step_0 - step_3);
    }

    /**
     * Computes the high bit depth 4-point inverse ADST of one row or column ({@code vpx_highbd_iadst4_c}).
     *
     * @param in the input array
     * @param inOff the index of the first input value
     * @param out the output array
     * @param outOff the index of the first output value
     */
    private static void iadst4(int[] in, int inOff, int[] out, int outOff) {
        long s0 = 0;
        long s1 = 0;
        long s2 = 0;
        long s3 = 0;
        long s4 = 0;
        long s5 = 0;
        long s6 = 0;
        long s7 = 0;
        int x0 = in[inOff + 0];
        int x1 = in[inOff + 1];
        int x2 = in[inOff + 2];
        int x3 = in[inOff + 3];
        if (invalidInput(in, inOff, 4)) {
            Arrays.fill(out, outOff, outOff + 4, 0);
            return;
        }
        if ((x0 | x1 | x2 | x3) == 0) {
            Arrays.fill(out, outOff, outOff + 4, 0);
            return;
        }
        s0 = (long) SINPI_1_9 * x0;
        s1 = (long) SINPI_2_9 * x0;
        s2 = (long) SINPI_3_9 * x1;
        s3 = (long) SINPI_4_9 * x2;
        s4 = (long) SINPI_1_9 * x2;
        s5 = (long) SINPI_2_9 * x3;
        s6 = (long) SINPI_4_9 * x3;
        s7 = (long) (int) (x0 - x2 + x3);
        s0 = s0 + s3 + s5;
        s1 = s1 - s4 - s6;
        s3 = s2;
        s2 = SINPI_3_9 * s7;
        out[outOff + 0] = (int) (((s0 + s3) + 8192) >> 14);
        out[outOff + 1] = (int) (((s1 + s3) + 8192) >> 14);
        out[outOff + 2] = (int) ((s2 + 8192) >> 14);
        out[outOff + 3] = (int) ((((s0 + s1 - s3) + 8192) >> 14));
    }

    /**
     * Computes the high bit depth 8-point inverse DCT of one row or column ({@code vpx_highbd_idct8_c}).
     *
     * @param in the input array
     * @param inOff the index of the first input value
     * @param out the output array
     * @param outOff the index of the first output value
     */
    private static void idct8(int[] in, int inOff, int[] out, int outOff) {
        int step1_0 = 0, step1_1 = 0, step1_2 = 0, step1_3 = 0, step1_4 = 0, step1_5 = 0, step1_6 = 0, step1_7 = 0;
        int step2_0 = 0, step2_1 = 0, step2_2 = 0, step2_3 = 0, step2_4 = 0, step2_5 = 0, step2_6 = 0, step2_7 = 0;
        long temp1 = 0;
        long temp2 = 0;
        if (invalidInput(in, inOff, 8)) {
            Arrays.fill(out, outOff, outOff + 8, 0);
            return;
        }
        step1_0 = in[inOff + 0];
        step1_2 = in[inOff + 4];
        step1_1 = in[inOff + 2];
        step1_3 = in[inOff + 6];
        temp1 = in[inOff + 1] * (long) COSPI_28_64 - in[inOff + 7] * (long) COSPI_4_64;
        temp2 = in[inOff + 1] * (long) COSPI_4_64 + in[inOff + 7] * (long) COSPI_28_64;
        step1_4 = (int) ((temp1 + 8192) >> 14);
        step1_7 = (int) ((temp2 + 8192) >> 14);
        temp1 = in[inOff + 5] * (long) COSPI_12_64 - in[inOff + 3] * (long) COSPI_20_64;
        temp2 = in[inOff + 5] * (long) COSPI_20_64 + in[inOff + 3] * (long) COSPI_12_64;
        step1_5 = (int) ((temp1 + 8192) >> 14);
        step1_6 = (int) ((temp2 + 8192) >> 14);
        if (invalidInput(step1_0, step1_1, step1_2, step1_3)) {
            step1_0 = 0;
            step1_1 = 0;
            step1_2 = 0;
            step1_3 = 0;
        } else {
            long even1 = (step1_0 + step1_2) * COSPI_16_64;
            long even2 = (step1_0 - step1_2) * COSPI_16_64;
            int e0 = (int) ((even1 + 8192) >> 14);
            int e1 = (int) ((even2 + 8192) >> 14);
            even1 = step1_1 * COSPI_24_64 - step1_3 * COSPI_8_64;
            even2 = step1_1 * COSPI_8_64 + step1_3 * COSPI_24_64;
            int e2 = (int) ((even1 + 8192) >> 14);
            int e3 = (int) ((even2 + 8192) >> 14);
            step1_0 = e0 + e3;
            step1_1 = e1 + e2;
            step1_2 = e1 - e2;
            step1_3 = e0 - e3;
        }
        step2_4 = (int) (step1_4 + step1_5);
        step2_5 = (int) (step1_4 - step1_5);
        step2_6 = (int) (-step1_6 + step1_7);
        step2_7 = (int) (step1_6 + step1_7);
        step1_4 = step2_4;
        temp1 = (step2_6 - step2_5) * (long) COSPI_16_64;
        temp2 = (step2_5 + step2_6) * (long) COSPI_16_64;
        step1_5 = (int) ((temp1 + 8192) >> 14);
        step1_6 = (int) ((temp2 + 8192) >> 14);
        step1_7 = step2_7;
        out[outOff + 0] = (int) (step1_0 + step1_7);
        out[outOff + 1] = (int) (step1_1 + step1_6);
        out[outOff + 2] = (int) (step1_2 + step1_5);
        out[outOff + 3] = (int) (step1_3 + step1_4);
        out[outOff + 4] = (int) (step1_3 - step1_4);
        out[outOff + 5] = (int) (step1_2 - step1_5);
        out[outOff + 6] = (int) (step1_1 - step1_6);
        out[outOff + 7] = (int) (step1_0 - step1_7);
    }

    /**
     * Computes the high bit depth 8-point inverse ADST of one row or column ({@code vpx_highbd_iadst8_c}).
     *
     * @param in the input array
     * @param inOff the index of the first input value
     * @param out the output array
     * @param outOff the index of the first output value
     */
    private static void iadst8(int[] in, int inOff, int[] out, int outOff) {
        long s0 = 0;
        long s1 = 0;
        long s2 = 0;
        long s3 = 0;
        long s4 = 0;
        long s5 = 0;
        long s6 = 0;
        long s7 = 0;
        int x0 = in[inOff + 7];
        int x1 = in[inOff + 0];
        int x2 = in[inOff + 5];
        int x3 = in[inOff + 2];
        int x4 = in[inOff + 3];
        int x5 = in[inOff + 4];
        int x6 = in[inOff + 1];
        int x7 = in[inOff + 6];
        if (invalidInput(in, inOff, 8)) {
            Arrays.fill(out, outOff, outOff + 8, 0);
            return;
        }
        if ((x0 | x1 | x2 | x3 | x4 | x5 | x6 | x7) == 0) {
            Arrays.fill(out, outOff, outOff + 8, 0);
            return;
        }
        s0 = (long) COSPI_2_64 * x0 + (long) COSPI_30_64 * x1;
        s1 = (long) COSPI_30_64 * x0 - (long) COSPI_2_64 * x1;
        s2 = (long) COSPI_10_64 * x2 + (long) COSPI_22_64 * x3;
        s3 = (long) COSPI_22_64 * x2 - (long) COSPI_10_64 * x3;
        s4 = (long) COSPI_18_64 * x4 + (long) COSPI_14_64 * x5;
        s5 = (long) COSPI_14_64 * x4 - (long) COSPI_18_64 * x5;
        s6 = (long) COSPI_26_64 * x6 + (long) COSPI_6_64 * x7;
        s7 = (long) COSPI_6_64 * x6 - (long) COSPI_26_64 * x7;
        x0 = (int) (((s0 + s4) + 8192) >> 14);
        x1 = (int) (((s1 + s5) + 8192) >> 14);
        x2 = (int) (((s2 + s6) + 8192) >> 14);
        x3 = (int) (((s3 + s7) + 8192) >> 14);
        x4 = (int) (((s0 - s4) + 8192) >> 14);
        x5 = (int) (((s1 - s5) + 8192) >> 14);
        x6 = (int) (((s2 - s6) + 8192) >> 14);
        x7 = (int) (((s3 - s7) + 8192) >> 14);
        s0 = x0;
        s1 = x1;
        s2 = x2;
        s3 = x3;
        s4 = (long) COSPI_8_64 * x4 + (long) COSPI_24_64 * x5;
        s5 = (long) COSPI_24_64 * x4 - (long) COSPI_8_64 * x5;
        s6 = (long) (-COSPI_24_64) * x6 + (long) COSPI_8_64 * x7;
        s7 = (long) COSPI_8_64 * x6 + (long) COSPI_24_64 * x7;
        x0 = (int) (s0 + s2);
        x1 = (int) (s1 + s3);
        x2 = (int) (s0 - s2);
        x3 = (int) (s1 - s3);
        x4 = (int) (((s4 + s6) + 8192) >> 14);
        x5 = (int) (((s5 + s7) + 8192) >> 14);
        x6 = (int) (((s4 - s6) + 8192) >> 14);
        x7 = (int) (((s5 - s7) + 8192) >> 14);
        s2 = (long) COSPI_16_64 * (x2 + x3);
        s3 = (long) COSPI_16_64 * (x2 - x3);
        s6 = (long) COSPI_16_64 * (x6 + x7);
        s7 = (long) COSPI_16_64 * (x6 - x7);
        x2 = (int) ((s2 + 8192) >> 14);
        x3 = (int) ((s3 + 8192) >> 14);
        x6 = (int) ((s6 + 8192) >> 14);
        x7 = (int) ((s7 + 8192) >> 14);
        out[outOff + 0] = (int) (x0);
        out[outOff + 1] = (int) (-x4);
        out[outOff + 2] = (int) (x6);
        out[outOff + 3] = (int) (-x2);
        out[outOff + 4] = (int) (x3);
        out[outOff + 5] = (int) (-x7);
        out[outOff + 6] = (int) (x5);
        out[outOff + 7] = (int) (-x1);
    }

    /**
     * Computes the high bit depth 16-point inverse DCT of one row or column ({@code vpx_highbd_idct16_c}).
     *
     * @param in the input array
     * @param inOff the index of the first input value
     * @param out the output array
     * @param outOff the index of the first output value
     */
    private static void idct16(int[] in, int inOff, int[] out, int outOff) {
        int step1_0 = 0, step1_1 = 0, step1_2 = 0, step1_3 = 0, step1_4 = 0, step1_5 = 0, step1_6 = 0, step1_7 = 0, step1_8 = 0, step1_9 = 0, step1_10 = 0, step1_11 = 0, step1_12 = 0, step1_13 = 0, step1_14 = 0, step1_15 = 0;
        int step2_0 = 0, step2_1 = 0, step2_2 = 0, step2_3 = 0, step2_4 = 0, step2_5 = 0, step2_6 = 0, step2_7 = 0, step2_8 = 0, step2_9 = 0, step2_10 = 0, step2_11 = 0, step2_12 = 0, step2_13 = 0, step2_14 = 0, step2_15 = 0;
        long temp1 = 0;
        long temp2 = 0;
        if (invalidInput(in, inOff, 16)) {
            Arrays.fill(out, outOff, outOff + 16, 0);
            return;
        }
        step1_0 = in[inOff + 0];
        step1_1 = in[inOff + 8];
        step1_2 = in[inOff + 4];
        step1_3 = in[inOff + 12];
        step1_4 = in[inOff + 2];
        step1_5 = in[inOff + 10];
        step1_6 = in[inOff + 6];
        step1_7 = in[inOff + 14];
        step1_8 = in[inOff + 1];
        step1_9 = in[inOff + 9];
        step1_10 = in[inOff + 5];
        step1_11 = in[inOff + 13];
        step1_12 = in[inOff + 3];
        step1_13 = in[inOff + 11];
        step1_14 = in[inOff + 7];
        step1_15 = in[inOff + 15];
        step2_0 = step1_0;
        step2_1 = step1_1;
        step2_2 = step1_2;
        step2_3 = step1_3;
        step2_4 = step1_4;
        step2_5 = step1_5;
        step2_6 = step1_6;
        step2_7 = step1_7;
        temp1 = step1_8 * (long) COSPI_30_64 - step1_15 * (long) COSPI_2_64;
        temp2 = step1_8 * (long) COSPI_2_64 + step1_15 * (long) COSPI_30_64;
        step2_8 = (int) ((temp1 + 8192) >> 14);
        step2_15 = (int) ((temp2 + 8192) >> 14);
        temp1 = step1_9 * (long) COSPI_14_64 - step1_14 * (long) COSPI_18_64;
        temp2 = step1_9 * (long) COSPI_18_64 + step1_14 * (long) COSPI_14_64;
        step2_9 = (int) ((temp1 + 8192) >> 14);
        step2_14 = (int) ((temp2 + 8192) >> 14);
        temp1 = step1_10 * (long) COSPI_22_64 - step1_13 * (long) COSPI_10_64;
        temp2 = step1_10 * (long) COSPI_10_64 + step1_13 * (long) COSPI_22_64;
        step2_10 = (int) ((temp1 + 8192) >> 14);
        step2_13 = (int) ((temp2 + 8192) >> 14);
        temp1 = step1_11 * (long) COSPI_6_64 - step1_12 * (long) COSPI_26_64;
        temp2 = step1_11 * (long) COSPI_26_64 + step1_12 * (long) COSPI_6_64;
        step2_11 = (int) ((temp1 + 8192) >> 14);
        step2_12 = (int) ((temp2 + 8192) >> 14);
        step1_0 = step2_0;
        step1_1 = step2_1;
        step1_2 = step2_2;
        step1_3 = step2_3;
        temp1 = step2_4 * (long) COSPI_28_64 - step2_7 * (long) COSPI_4_64;
        temp2 = step2_4 * (long) COSPI_4_64 + step2_7 * (long) COSPI_28_64;
        step1_4 = (int) ((temp1 + 8192) >> 14);
        step1_7 = (int) ((temp2 + 8192) >> 14);
        temp1 = step2_5 * (long) COSPI_12_64 - step2_6 * (long) COSPI_20_64;
        temp2 = step2_5 * (long) COSPI_20_64 + step2_6 * (long) COSPI_12_64;
        step1_5 = (int) ((temp1 + 8192) >> 14);
        step1_6 = (int) ((temp2 + 8192) >> 14);
        step1_8 = (int) (step2_8 + step2_9);
        step1_9 = (int) (step2_8 - step2_9);
        step1_10 = (int) (-step2_10 + step2_11);
        step1_11 = (int) (step2_10 + step2_11);
        step1_12 = (int) (step2_12 + step2_13);
        step1_13 = (int) (step2_12 - step2_13);
        step1_14 = (int) (-step2_14 + step2_15);
        step1_15 = (int) (step2_14 + step2_15);
        temp1 = (step1_0 + step1_1) * (long) COSPI_16_64;
        temp2 = (step1_0 - step1_1) * (long) COSPI_16_64;
        step2_0 = (int) ((temp1 + 8192) >> 14);
        step2_1 = (int) ((temp2 + 8192) >> 14);
        temp1 = step1_2 * (long) COSPI_24_64 - step1_3 * (long) COSPI_8_64;
        temp2 = step1_2 * (long) COSPI_8_64 + step1_3 * (long) COSPI_24_64;
        step2_2 = (int) ((temp1 + 8192) >> 14);
        step2_3 = (int) ((temp2 + 8192) >> 14);
        step2_4 = (int) (step1_4 + step1_5);
        step2_5 = (int) (step1_4 - step1_5);
        step2_6 = (int) (-step1_6 + step1_7);
        step2_7 = (int) (step1_6 + step1_7);
        step2_8 = step1_8;
        step2_15 = step1_15;
        temp1 = -step1_9 * (long) COSPI_8_64 + step1_14 * (long) COSPI_24_64;
        temp2 = step1_9 * (long) COSPI_24_64 + step1_14 * (long) COSPI_8_64;
        step2_9 = (int) ((temp1 + 8192) >> 14);
        step2_14 = (int) ((temp2 + 8192) >> 14);
        temp1 = -step1_10 * (long) COSPI_24_64 - step1_13 * (long) COSPI_8_64;
        temp2 = -step1_10 * (long) COSPI_8_64 + step1_13 * (long) COSPI_24_64;
        step2_10 = (int) ((temp1 + 8192) >> 14);
        step2_13 = (int) ((temp2 + 8192) >> 14);
        step2_11 = step1_11;
        step2_12 = step1_12;
        step1_0 = (int) (step2_0 + step2_3);
        step1_1 = (int) (step2_1 + step2_2);
        step1_2 = (int) (step2_1 - step2_2);
        step1_3 = (int) (step2_0 - step2_3);
        step1_4 = step2_4;
        temp1 = (step2_6 - step2_5) * (long) COSPI_16_64;
        temp2 = (step2_5 + step2_6) * (long) COSPI_16_64;
        step1_5 = (int) ((temp1 + 8192) >> 14);
        step1_6 = (int) ((temp2 + 8192) >> 14);
        step1_7 = step2_7;
        step1_8 = (int) (step2_8 + step2_11);
        step1_9 = (int) (step2_9 + step2_10);
        step1_10 = (int) (step2_9 - step2_10);
        step1_11 = (int) (step2_8 - step2_11);
        step1_12 = (int) (-step2_12 + step2_15);
        step1_13 = (int) (-step2_13 + step2_14);
        step1_14 = (int) (step2_13 + step2_14);
        step1_15 = (int) (step2_12 + step2_15);
        step2_0 = (int) (step1_0 + step1_7);
        step2_1 = (int) (step1_1 + step1_6);
        step2_2 = (int) (step1_2 + step1_5);
        step2_3 = (int) (step1_3 + step1_4);
        step2_4 = (int) (step1_3 - step1_4);
        step2_5 = (int) (step1_2 - step1_5);
        step2_6 = (int) (step1_1 - step1_6);
        step2_7 = (int) (step1_0 - step1_7);
        step2_8 = step1_8;
        step2_9 = step1_9;
        temp1 = (-step1_10 + step1_13) * (long) COSPI_16_64;
        temp2 = (step1_10 + step1_13) * (long) COSPI_16_64;
        step2_10 = (int) ((temp1 + 8192) >> 14);
        step2_13 = (int) ((temp2 + 8192) >> 14);
        temp1 = (-step1_11 + step1_12) * (long) COSPI_16_64;
        temp2 = (step1_11 + step1_12) * (long) COSPI_16_64;
        step2_11 = (int) ((temp1 + 8192) >> 14);
        step2_12 = (int) ((temp2 + 8192) >> 14);
        step2_14 = step1_14;
        step2_15 = step1_15;
        out[outOff + 0] = (int) (step2_0 + step2_15);
        out[outOff + 1] = (int) (step2_1 + step2_14);
        out[outOff + 2] = (int) (step2_2 + step2_13);
        out[outOff + 3] = (int) (step2_3 + step2_12);
        out[outOff + 4] = (int) (step2_4 + step2_11);
        out[outOff + 5] = (int) (step2_5 + step2_10);
        out[outOff + 6] = (int) (step2_6 + step2_9);
        out[outOff + 7] = (int) (step2_7 + step2_8);
        out[outOff + 8] = (int) (step2_7 - step2_8);
        out[outOff + 9] = (int) (step2_6 - step2_9);
        out[outOff + 10] = (int) (step2_5 - step2_10);
        out[outOff + 11] = (int) (step2_4 - step2_11);
        out[outOff + 12] = (int) (step2_3 - step2_12);
        out[outOff + 13] = (int) (step2_2 - step2_13);
        out[outOff + 14] = (int) (step2_1 - step2_14);
        out[outOff + 15] = (int) (step2_0 - step2_15);
    }

    /**
     * Computes the high bit depth 16-point inverse ADST of one row or column ({@code vpx_highbd_iadst16_c}).
     *
     * @param in the input array
     * @param inOff the index of the first input value
     * @param out the output array
     * @param outOff the index of the first output value
     */
    private static void iadst16(int[] in, int inOff, int[] out, int outOff) {
        long s0 = 0;
        long s1 = 0;
        long s2 = 0;
        long s3 = 0;
        long s4 = 0;
        long s5 = 0;
        long s6 = 0;
        long s7 = 0;
        long s8 = 0;
        long s9 = 0;
        long s10 = 0;
        long s11 = 0;
        long s12 = 0;
        long s13 = 0;
        long s14 = 0;
        long s15 = 0;
        int x0 = in[inOff + 15];
        int x1 = in[inOff + 0];
        int x2 = in[inOff + 13];
        int x3 = in[inOff + 2];
        int x4 = in[inOff + 11];
        int x5 = in[inOff + 4];
        int x6 = in[inOff + 9];
        int x7 = in[inOff + 6];
        int x8 = in[inOff + 7];
        int x9 = in[inOff + 8];
        int x10 = in[inOff + 5];
        int x11 = in[inOff + 10];
        int x12 = in[inOff + 3];
        int x13 = in[inOff + 12];
        int x14 = in[inOff + 1];
        int x15 = in[inOff + 14];
        if (invalidInput(in, inOff, 16)) {
            Arrays.fill(out, outOff, outOff + 16, 0);
            return;
        }
        if ((x0 | x1 | x2 | x3 | x4 | x5 | x6 | x7 | x8 | x9 | x10 | x11 | x12 | x13 | x14 | x15) == 0) {
            Arrays.fill(out, outOff, outOff + 16, 0);
            return;
        }
        s0 = x0 * (long) COSPI_1_64 + x1 * (long) COSPI_31_64;
        s1 = x0 * (long) COSPI_31_64 - x1 * (long) COSPI_1_64;
        s2 = x2 * (long) COSPI_5_64 + x3 * (long) COSPI_27_64;
        s3 = x2 * (long) COSPI_27_64 - x3 * (long) COSPI_5_64;
        s4 = x4 * (long) COSPI_9_64 + x5 * (long) COSPI_23_64;
        s5 = x4 * (long) COSPI_23_64 - x5 * (long) COSPI_9_64;
        s6 = x6 * (long) COSPI_13_64 + x7 * (long) COSPI_19_64;
        s7 = x6 * (long) COSPI_19_64 - x7 * (long) COSPI_13_64;
        s8 = x8 * (long) COSPI_17_64 + x9 * (long) COSPI_15_64;
        s9 = x8 * (long) COSPI_15_64 - x9 * (long) COSPI_17_64;
        s10 = x10 * (long) COSPI_21_64 + x11 * (long) COSPI_11_64;
        s11 = x10 * (long) COSPI_11_64 - x11 * (long) COSPI_21_64;
        s12 = x12 * (long) COSPI_25_64 + x13 * (long) COSPI_7_64;
        s13 = x12 * (long) COSPI_7_64 - x13 * (long) COSPI_25_64;
        s14 = x14 * (long) COSPI_29_64 + x15 * (long) COSPI_3_64;
        s15 = x14 * (long) COSPI_3_64 - x15 * (long) COSPI_29_64;
        x0 = (int) (((s0 + s8) + 8192) >> 14);
        x1 = (int) (((s1 + s9) + 8192) >> 14);
        x2 = (int) (((s2 + s10) + 8192) >> 14);
        x3 = (int) (((s3 + s11) + 8192) >> 14);
        x4 = (int) (((s4 + s12) + 8192) >> 14);
        x5 = (int) (((s5 + s13) + 8192) >> 14);
        x6 = (int) (((s6 + s14) + 8192) >> 14);
        x7 = (int) (((s7 + s15) + 8192) >> 14);
        x8 = (int) (((s0 - s8) + 8192) >> 14);
        x9 = (int) (((s1 - s9) + 8192) >> 14);
        x10 = (int) (((s2 - s10) + 8192) >> 14);
        x11 = (int) (((s3 - s11) + 8192) >> 14);
        x12 = (int) (((s4 - s12) + 8192) >> 14);
        x13 = (int) (((s5 - s13) + 8192) >> 14);
        x14 = (int) (((s6 - s14) + 8192) >> 14);
        x15 = (int) (((s7 - s15) + 8192) >> 14);
        s0 = x0;
        s1 = x1;
        s2 = x2;
        s3 = x3;
        s4 = x4;
        s5 = x5;
        s6 = x6;
        s7 = x7;
        s8 = x8 * (long) COSPI_4_64 + x9 * (long) COSPI_28_64;
        s9 = x8 * (long) COSPI_28_64 - x9 * (long) COSPI_4_64;
        s10 = x10 * (long) COSPI_20_64 + x11 * (long) COSPI_12_64;
        s11 = x10 * (long) COSPI_12_64 - x11 * (long) COSPI_20_64;
        s12 = -x12 * (long) COSPI_28_64 + x13 * (long) COSPI_4_64;
        s13 = x12 * (long) COSPI_4_64 + x13 * (long) COSPI_28_64;
        s14 = -x14 * (long) COSPI_12_64 + x15 * (long) COSPI_20_64;
        s15 = x14 * (long) COSPI_20_64 + x15 * (long) COSPI_12_64;
        x0 = (int) (s0 + s4);
        x1 = (int) (s1 + s5);
        x2 = (int) (s2 + s6);
        x3 = (int) (s3 + s7);
        x4 = (int) (s0 - s4);
        x5 = (int) (s1 - s5);
        x6 = (int) (s2 - s6);
        x7 = (int) (s3 - s7);
        x8 = (int) (((s8 + s12) + 8192) >> 14);
        x9 = (int) (((s9 + s13) + 8192) >> 14);
        x10 = (int) (((s10 + s14) + 8192) >> 14);
        x11 = (int) (((s11 + s15) + 8192) >> 14);
        x12 = (int) (((s8 - s12) + 8192) >> 14);
        x13 = (int) (((s9 - s13) + 8192) >> 14);
        x14 = (int) (((s10 - s14) + 8192) >> 14);
        x15 = (int) (((s11 - s15) + 8192) >> 14);
        s0 = x0;
        s1 = x1;
        s2 = x2;
        s3 = x3;
        s4 = x4 * (long) COSPI_8_64 + x5 * (long) COSPI_24_64;
        s5 = x4 * (long) COSPI_24_64 - x5 * (long) COSPI_8_64;
        s6 = -x6 * (long) COSPI_24_64 + x7 * (long) COSPI_8_64;
        s7 = x6 * (long) COSPI_8_64 + x7 * (long) COSPI_24_64;
        s8 = x8;
        s9 = x9;
        s10 = x10;
        s11 = x11;
        s12 = x12 * (long) COSPI_8_64 + x13 * (long) COSPI_24_64;
        s13 = x12 * (long) COSPI_24_64 - x13 * (long) COSPI_8_64;
        s14 = -x14 * (long) COSPI_24_64 + x15 * (long) COSPI_8_64;
        s15 = x14 * (long) COSPI_8_64 + x15 * (long) COSPI_24_64;
        x0 = (int) (s0 + s2);
        x1 = (int) (s1 + s3);
        x2 = (int) (s0 - s2);
        x3 = (int) (s1 - s3);
        x4 = (int) (((s4 + s6) + 8192) >> 14);
        x5 = (int) (((s5 + s7) + 8192) >> 14);
        x6 = (int) (((s4 - s6) + 8192) >> 14);
        x7 = (int) (((s5 - s7) + 8192) >> 14);
        x8 = (int) (s8 + s10);
        x9 = (int) (s9 + s11);
        x10 = (int) (s8 - s10);
        x11 = (int) (s9 - s11);
        x12 = (int) (((s12 + s14) + 8192) >> 14);
        x13 = (int) (((s13 + s15) + 8192) >> 14);
        x14 = (int) (((s12 - s14) + 8192) >> 14);
        x15 = (int) (((s13 - s15) + 8192) >> 14);
        s2 = (long) (-COSPI_16_64) * (x2 + x3);
        s3 = (long) COSPI_16_64 * (x2 - x3);
        s6 = (long) COSPI_16_64 * (x6 + x7);
        s7 = (long) COSPI_16_64 * (-x6 + x7);
        s10 = (long) COSPI_16_64 * (x10 + x11);
        s11 = (long) COSPI_16_64 * (-x10 + x11);
        s14 = (long) (-COSPI_16_64) * (x14 + x15);
        s15 = (long) COSPI_16_64 * (x14 - x15);
        x2 = (int) ((s2 + 8192) >> 14);
        x3 = (int) ((s3 + 8192) >> 14);
        x6 = (int) ((s6 + 8192) >> 14);
        x7 = (int) ((s7 + 8192) >> 14);
        x10 = (int) ((s10 + 8192) >> 14);
        x11 = (int) ((s11 + 8192) >> 14);
        x14 = (int) ((s14 + 8192) >> 14);
        x15 = (int) ((s15 + 8192) >> 14);
        out[outOff + 0] = (int) (x0);
        out[outOff + 1] = (int) (-x8);
        out[outOff + 2] = (int) (x12);
        out[outOff + 3] = (int) (-x4);
        out[outOff + 4] = (int) (x6);
        out[outOff + 5] = (int) (x14);
        out[outOff + 6] = (int) (x10);
        out[outOff + 7] = (int) (x2);
        out[outOff + 8] = (int) (x3);
        out[outOff + 9] = (int) (x11);
        out[outOff + 10] = (int) (x15);
        out[outOff + 11] = (int) (x7);
        out[outOff + 12] = (int) (x5);
        out[outOff + 13] = (int) (-x13);
        out[outOff + 14] = (int) (x9);
        out[outOff + 15] = (int) (-x1);
    }

    /**
     * Computes the high bit depth 32-point inverse DCT of one row or column ({@code highbd_idct32_c}).
     *
     * @param in the input array
     * @param inOff the index of the first input value
     * @param out the output array
     * @param outOff the index of the first output value
     */
    private static void idct32(int[] in, int inOff, int[] out, int outOff) {
        int step1_0 = 0, step1_1 = 0, step1_2 = 0, step1_3 = 0, step1_4 = 0, step1_5 = 0, step1_6 = 0, step1_7 = 0, step1_8 = 0, step1_9 = 0, step1_10 = 0, step1_11 = 0, step1_12 = 0, step1_13 = 0, step1_14 = 0, step1_15 = 0, step1_16 = 0, step1_17 = 0, step1_18 = 0, step1_19 = 0, step1_20 = 0, step1_21 = 0, step1_22 = 0, step1_23 = 0, step1_24 = 0, step1_25 = 0, step1_26 = 0, step1_27 = 0, step1_28 = 0, step1_29 = 0, step1_30 = 0, step1_31 = 0;
        int step2_0 = 0, step2_1 = 0, step2_2 = 0, step2_3 = 0, step2_4 = 0, step2_5 = 0, step2_6 = 0, step2_7 = 0, step2_8 = 0, step2_9 = 0, step2_10 = 0, step2_11 = 0, step2_12 = 0, step2_13 = 0, step2_14 = 0, step2_15 = 0, step2_16 = 0, step2_17 = 0, step2_18 = 0, step2_19 = 0, step2_20 = 0, step2_21 = 0, step2_22 = 0, step2_23 = 0, step2_24 = 0, step2_25 = 0, step2_26 = 0, step2_27 = 0, step2_28 = 0, step2_29 = 0, step2_30 = 0, step2_31 = 0;
        long temp1 = 0;
        long temp2 = 0;
        if (invalidInput(in, inOff, 32)) {
            Arrays.fill(out, outOff, outOff + 32, 0);
            return;
        }
        step1_0 = in[inOff + 0];
        step1_1 = in[inOff + 16];
        step1_2 = in[inOff + 8];
        step1_3 = in[inOff + 24];
        step1_4 = in[inOff + 4];
        step1_5 = in[inOff + 20];
        step1_6 = in[inOff + 12];
        step1_7 = in[inOff + 28];
        step1_8 = in[inOff + 2];
        step1_9 = in[inOff + 18];
        step1_10 = in[inOff + 10];
        step1_11 = in[inOff + 26];
        step1_12 = in[inOff + 6];
        step1_13 = in[inOff + 22];
        step1_14 = in[inOff + 14];
        step1_15 = in[inOff + 30];
        temp1 = in[inOff + 1] * (long) COSPI_31_64 - in[inOff + 31] * (long) COSPI_1_64;
        temp2 = in[inOff + 1] * (long) COSPI_1_64 + in[inOff + 31] * (long) COSPI_31_64;
        step1_16 = (int) ((temp1 + 8192) >> 14);
        step1_31 = (int) ((temp2 + 8192) >> 14);
        temp1 = in[inOff + 17] * (long) COSPI_15_64 - in[inOff + 15] * (long) COSPI_17_64;
        temp2 = in[inOff + 17] * (long) COSPI_17_64 + in[inOff + 15] * (long) COSPI_15_64;
        step1_17 = (int) ((temp1 + 8192) >> 14);
        step1_30 = (int) ((temp2 + 8192) >> 14);
        temp1 = in[inOff + 9] * (long) COSPI_23_64 - in[inOff + 23] * (long) COSPI_9_64;
        temp2 = in[inOff + 9] * (long) COSPI_9_64 + in[inOff + 23] * (long) COSPI_23_64;
        step1_18 = (int) ((temp1 + 8192) >> 14);
        step1_29 = (int) ((temp2 + 8192) >> 14);
        temp1 = in[inOff + 25] * (long) COSPI_7_64 - in[inOff + 7] * (long) COSPI_25_64;
        temp2 = in[inOff + 25] * (long) COSPI_25_64 + in[inOff + 7] * (long) COSPI_7_64;
        step1_19 = (int) ((temp1 + 8192) >> 14);
        step1_28 = (int) ((temp2 + 8192) >> 14);
        temp1 = in[inOff + 5] * (long) COSPI_27_64 - in[inOff + 27] * (long) COSPI_5_64;
        temp2 = in[inOff + 5] * (long) COSPI_5_64 + in[inOff + 27] * (long) COSPI_27_64;
        step1_20 = (int) ((temp1 + 8192) >> 14);
        step1_27 = (int) ((temp2 + 8192) >> 14);
        temp1 = in[inOff + 21] * (long) COSPI_11_64 - in[inOff + 11] * (long) COSPI_21_64;
        temp2 = in[inOff + 21] * (long) COSPI_21_64 + in[inOff + 11] * (long) COSPI_11_64;
        step1_21 = (int) ((temp1 + 8192) >> 14);
        step1_26 = (int) ((temp2 + 8192) >> 14);
        temp1 = in[inOff + 13] * (long) COSPI_19_64 - in[inOff + 19] * (long) COSPI_13_64;
        temp2 = in[inOff + 13] * (long) COSPI_13_64 + in[inOff + 19] * (long) COSPI_19_64;
        step1_22 = (int) ((temp1 + 8192) >> 14);
        step1_25 = (int) ((temp2 + 8192) >> 14);
        temp1 = in[inOff + 29] * (long) COSPI_3_64 - in[inOff + 3] * (long) COSPI_29_64;
        temp2 = in[inOff + 29] * (long) COSPI_29_64 + in[inOff + 3] * (long) COSPI_3_64;
        step1_23 = (int) ((temp1 + 8192) >> 14);
        step1_24 = (int) ((temp2 + 8192) >> 14);
        step2_0 = step1_0;
        step2_1 = step1_1;
        step2_2 = step1_2;
        step2_3 = step1_3;
        step2_4 = step1_4;
        step2_5 = step1_5;
        step2_6 = step1_6;
        step2_7 = step1_7;
        temp1 = step1_8 * (long) COSPI_30_64 - step1_15 * (long) COSPI_2_64;
        temp2 = step1_8 * (long) COSPI_2_64 + step1_15 * (long) COSPI_30_64;
        step2_8 = (int) ((temp1 + 8192) >> 14);
        step2_15 = (int) ((temp2 + 8192) >> 14);
        temp1 = step1_9 * (long) COSPI_14_64 - step1_14 * (long) COSPI_18_64;
        temp2 = step1_9 * (long) COSPI_18_64 + step1_14 * (long) COSPI_14_64;
        step2_9 = (int) ((temp1 + 8192) >> 14);
        step2_14 = (int) ((temp2 + 8192) >> 14);
        temp1 = step1_10 * (long) COSPI_22_64 - step1_13 * (long) COSPI_10_64;
        temp2 = step1_10 * (long) COSPI_10_64 + step1_13 * (long) COSPI_22_64;
        step2_10 = (int) ((temp1 + 8192) >> 14);
        step2_13 = (int) ((temp2 + 8192) >> 14);
        temp1 = step1_11 * (long) COSPI_6_64 - step1_12 * (long) COSPI_26_64;
        temp2 = step1_11 * (long) COSPI_26_64 + step1_12 * (long) COSPI_6_64;
        step2_11 = (int) ((temp1 + 8192) >> 14);
        step2_12 = (int) ((temp2 + 8192) >> 14);
        step2_16 = (int) (step1_16 + step1_17);
        step2_17 = (int) (step1_16 - step1_17);
        step2_18 = (int) (-step1_18 + step1_19);
        step2_19 = (int) (step1_18 + step1_19);
        step2_20 = (int) (step1_20 + step1_21);
        step2_21 = (int) (step1_20 - step1_21);
        step2_22 = (int) (-step1_22 + step1_23);
        step2_23 = (int) (step1_22 + step1_23);
        step2_24 = (int) (step1_24 + step1_25);
        step2_25 = (int) (step1_24 - step1_25);
        step2_26 = (int) (-step1_26 + step1_27);
        step2_27 = (int) (step1_26 + step1_27);
        step2_28 = (int) (step1_28 + step1_29);
        step2_29 = (int) (step1_28 - step1_29);
        step2_30 = (int) (-step1_30 + step1_31);
        step2_31 = (int) (step1_30 + step1_31);
        step1_0 = step2_0;
        step1_1 = step2_1;
        step1_2 = step2_2;
        step1_3 = step2_3;
        temp1 = step2_4 * (long) COSPI_28_64 - step2_7 * (long) COSPI_4_64;
        temp2 = step2_4 * (long) COSPI_4_64 + step2_7 * (long) COSPI_28_64;
        step1_4 = (int) ((temp1 + 8192) >> 14);
        step1_7 = (int) ((temp2 + 8192) >> 14);
        temp1 = step2_5 * (long) COSPI_12_64 - step2_6 * (long) COSPI_20_64;
        temp2 = step2_5 * (long) COSPI_20_64 + step2_6 * (long) COSPI_12_64;
        step1_5 = (int) ((temp1 + 8192) >> 14);
        step1_6 = (int) ((temp2 + 8192) >> 14);
        step1_8 = (int) (step2_8 + step2_9);
        step1_9 = (int) (step2_8 - step2_9);
        step1_10 = (int) (-step2_10 + step2_11);
        step1_11 = (int) (step2_10 + step2_11);
        step1_12 = (int) (step2_12 + step2_13);
        step1_13 = (int) (step2_12 - step2_13);
        step1_14 = (int) (-step2_14 + step2_15);
        step1_15 = (int) (step2_14 + step2_15);
        step1_16 = step2_16;
        step1_31 = step2_31;
        temp1 = -step2_17 * (long) COSPI_4_64 + step2_30 * (long) COSPI_28_64;
        temp2 = step2_17 * (long) COSPI_28_64 + step2_30 * (long) COSPI_4_64;
        step1_17 = (int) ((temp1 + 8192) >> 14);
        step1_30 = (int) ((temp2 + 8192) >> 14);
        temp1 = -step2_18 * (long) COSPI_28_64 - step2_29 * (long) COSPI_4_64;
        temp2 = -step2_18 * (long) COSPI_4_64 + step2_29 * (long) COSPI_28_64;
        step1_18 = (int) ((temp1 + 8192) >> 14);
        step1_29 = (int) ((temp2 + 8192) >> 14);
        step1_19 = step2_19;
        step1_20 = step2_20;
        temp1 = -step2_21 * (long) COSPI_20_64 + step2_26 * (long) COSPI_12_64;
        temp2 = step2_21 * (long) COSPI_12_64 + step2_26 * (long) COSPI_20_64;
        step1_21 = (int) ((temp1 + 8192) >> 14);
        step1_26 = (int) ((temp2 + 8192) >> 14);
        temp1 = -step2_22 * (long) COSPI_12_64 - step2_25 * (long) COSPI_20_64;
        temp2 = -step2_22 * (long) COSPI_20_64 + step2_25 * (long) COSPI_12_64;
        step1_22 = (int) ((temp1 + 8192) >> 14);
        step1_25 = (int) ((temp2 + 8192) >> 14);
        step1_23 = step2_23;
        step1_24 = step2_24;
        step1_27 = step2_27;
        step1_28 = step2_28;
        temp1 = (step1_0 + step1_1) * (long) COSPI_16_64;
        temp2 = (step1_0 - step1_1) * (long) COSPI_16_64;
        step2_0 = (int) ((temp1 + 8192) >> 14);
        step2_1 = (int) ((temp2 + 8192) >> 14);
        temp1 = step1_2 * (long) COSPI_24_64 - step1_3 * (long) COSPI_8_64;
        temp2 = step1_2 * (long) COSPI_8_64 + step1_3 * (long) COSPI_24_64;
        step2_2 = (int) ((temp1 + 8192) >> 14);
        step2_3 = (int) ((temp2 + 8192) >> 14);
        step2_4 = (int) (step1_4 + step1_5);
        step2_5 = (int) (step1_4 - step1_5);
        step2_6 = (int) (-step1_6 + step1_7);
        step2_7 = (int) (step1_6 + step1_7);
        step2_8 = step1_8;
        step2_15 = step1_15;
        temp1 = -step1_9 * (long) COSPI_8_64 + step1_14 * (long) COSPI_24_64;
        temp2 = step1_9 * (long) COSPI_24_64 + step1_14 * (long) COSPI_8_64;
        step2_9 = (int) ((temp1 + 8192) >> 14);
        step2_14 = (int) ((temp2 + 8192) >> 14);
        temp1 = -step1_10 * (long) COSPI_24_64 - step1_13 * (long) COSPI_8_64;
        temp2 = -step1_10 * (long) COSPI_8_64 + step1_13 * (long) COSPI_24_64;
        step2_10 = (int) ((temp1 + 8192) >> 14);
        step2_13 = (int) ((temp2 + 8192) >> 14);
        step2_11 = step1_11;
        step2_12 = step1_12;
        step2_16 = (int) (step1_16 + step1_19);
        step2_17 = (int) (step1_17 + step1_18);
        step2_18 = (int) (step1_17 - step1_18);
        step2_19 = (int) (step1_16 - step1_19);
        step2_20 = (int) (-step1_20 + step1_23);
        step2_21 = (int) (-step1_21 + step1_22);
        step2_22 = (int) (step1_21 + step1_22);
        step2_23 = (int) (step1_20 + step1_23);
        step2_24 = (int) (step1_24 + step1_27);
        step2_25 = (int) (step1_25 + step1_26);
        step2_26 = (int) (step1_25 - step1_26);
        step2_27 = (int) (step1_24 - step1_27);
        step2_28 = (int) (-step1_28 + step1_31);
        step2_29 = (int) (-step1_29 + step1_30);
        step2_30 = (int) (step1_29 + step1_30);
        step2_31 = (int) (step1_28 + step1_31);
        step1_0 = (int) (step2_0 + step2_3);
        step1_1 = (int) (step2_1 + step2_2);
        step1_2 = (int) (step2_1 - step2_2);
        step1_3 = (int) (step2_0 - step2_3);
        step1_4 = step2_4;
        temp1 = (step2_6 - step2_5) * (long) COSPI_16_64;
        temp2 = (step2_5 + step2_6) * (long) COSPI_16_64;
        step1_5 = (int) ((temp1 + 8192) >> 14);
        step1_6 = (int) ((temp2 + 8192) >> 14);
        step1_7 = step2_7;
        step1_8 = (int) (step2_8 + step2_11);
        step1_9 = (int) (step2_9 + step2_10);
        step1_10 = (int) (step2_9 - step2_10);
        step1_11 = (int) (step2_8 - step2_11);
        step1_12 = (int) (-step2_12 + step2_15);
        step1_13 = (int) (-step2_13 + step2_14);
        step1_14 = (int) (step2_13 + step2_14);
        step1_15 = (int) (step2_12 + step2_15);
        step1_16 = step2_16;
        step1_17 = step2_17;
        temp1 = -step2_18 * (long) COSPI_8_64 + step2_29 * (long) COSPI_24_64;
        temp2 = step2_18 * (long) COSPI_24_64 + step2_29 * (long) COSPI_8_64;
        step1_18 = (int) ((temp1 + 8192) >> 14);
        step1_29 = (int) ((temp2 + 8192) >> 14);
        temp1 = -step2_19 * (long) COSPI_8_64 + step2_28 * (long) COSPI_24_64;
        temp2 = step2_19 * (long) COSPI_24_64 + step2_28 * (long) COSPI_8_64;
        step1_19 = (int) ((temp1 + 8192) >> 14);
        step1_28 = (int) ((temp2 + 8192) >> 14);
        temp1 = -step2_20 * (long) COSPI_24_64 - step2_27 * (long) COSPI_8_64;
        temp2 = -step2_20 * (long) COSPI_8_64 + step2_27 * (long) COSPI_24_64;
        step1_20 = (int) ((temp1 + 8192) >> 14);
        step1_27 = (int) ((temp2 + 8192) >> 14);
        temp1 = -step2_21 * (long) COSPI_24_64 - step2_26 * (long) COSPI_8_64;
        temp2 = -step2_21 * (long) COSPI_8_64 + step2_26 * (long) COSPI_24_64;
        step1_21 = (int) ((temp1 + 8192) >> 14);
        step1_26 = (int) ((temp2 + 8192) >> 14);
        step1_22 = step2_22;
        step1_23 = step2_23;
        step1_24 = step2_24;
        step1_25 = step2_25;
        step1_30 = step2_30;
        step1_31 = step2_31;
        step2_0 = (int) (step1_0 + step1_7);
        step2_1 = (int) (step1_1 + step1_6);
        step2_2 = (int) (step1_2 + step1_5);
        step2_3 = (int) (step1_3 + step1_4);
        step2_4 = (int) (step1_3 - step1_4);
        step2_5 = (int) (step1_2 - step1_5);
        step2_6 = (int) (step1_1 - step1_6);
        step2_7 = (int) (step1_0 - step1_7);
        step2_8 = step1_8;
        step2_9 = step1_9;
        temp1 = (-step1_10 + step1_13) * (long) COSPI_16_64;
        temp2 = (step1_10 + step1_13) * (long) COSPI_16_64;
        step2_10 = (int) ((temp1 + 8192) >> 14);
        step2_13 = (int) ((temp2 + 8192) >> 14);
        temp1 = (-step1_11 + step1_12) * (long) COSPI_16_64;
        temp2 = (step1_11 + step1_12) * (long) COSPI_16_64;
        step2_11 = (int) ((temp1 + 8192) >> 14);
        step2_12 = (int) ((temp2 + 8192) >> 14);
        step2_14 = step1_14;
        step2_15 = step1_15;
        step2_16 = (int) (step1_16 + step1_23);
        step2_17 = (int) (step1_17 + step1_22);
        step2_18 = (int) (step1_18 + step1_21);
        step2_19 = (int) (step1_19 + step1_20);
        step2_20 = (int) (step1_19 - step1_20);
        step2_21 = (int) (step1_18 - step1_21);
        step2_22 = (int) (step1_17 - step1_22);
        step2_23 = (int) (step1_16 - step1_23);
        step2_24 = (int) (-step1_24 + step1_31);
        step2_25 = (int) (-step1_25 + step1_30);
        step2_26 = (int) (-step1_26 + step1_29);
        step2_27 = (int) (-step1_27 + step1_28);
        step2_28 = (int) (step1_27 + step1_28);
        step2_29 = (int) (step1_26 + step1_29);
        step2_30 = (int) (step1_25 + step1_30);
        step2_31 = (int) (step1_24 + step1_31);
        step1_0 = (int) (step2_0 + step2_15);
        step1_1 = (int) (step2_1 + step2_14);
        step1_2 = (int) (step2_2 + step2_13);
        step1_3 = (int) (step2_3 + step2_12);
        step1_4 = (int) (step2_4 + step2_11);
        step1_5 = (int) (step2_5 + step2_10);
        step1_6 = (int) (step2_6 + step2_9);
        step1_7 = (int) (step2_7 + step2_8);
        step1_8 = (int) (step2_7 - step2_8);
        step1_9 = (int) (step2_6 - step2_9);
        step1_10 = (int) (step2_5 - step2_10);
        step1_11 = (int) (step2_4 - step2_11);
        step1_12 = (int) (step2_3 - step2_12);
        step1_13 = (int) (step2_2 - step2_13);
        step1_14 = (int) (step2_1 - step2_14);
        step1_15 = (int) (step2_0 - step2_15);
        step1_16 = step2_16;
        step1_17 = step2_17;
        step1_18 = step2_18;
        step1_19 = step2_19;
        temp1 = (-step2_20 + step2_27) * (long) COSPI_16_64;
        temp2 = (step2_20 + step2_27) * (long) COSPI_16_64;
        step1_20 = (int) ((temp1 + 8192) >> 14);
        step1_27 = (int) ((temp2 + 8192) >> 14);
        temp1 = (-step2_21 + step2_26) * (long) COSPI_16_64;
        temp2 = (step2_21 + step2_26) * (long) COSPI_16_64;
        step1_21 = (int) ((temp1 + 8192) >> 14);
        step1_26 = (int) ((temp2 + 8192) >> 14);
        temp1 = (-step2_22 + step2_25) * (long) COSPI_16_64;
        temp2 = (step2_22 + step2_25) * (long) COSPI_16_64;
        step1_22 = (int) ((temp1 + 8192) >> 14);
        step1_25 = (int) ((temp2 + 8192) >> 14);
        temp1 = (-step2_23 + step2_24) * (long) COSPI_16_64;
        temp2 = (step2_23 + step2_24) * (long) COSPI_16_64;
        step1_23 = (int) ((temp1 + 8192) >> 14);
        step1_24 = (int) ((temp2 + 8192) >> 14);
        step1_28 = step2_28;
        step1_29 = step2_29;
        step1_30 = step2_30;
        step1_31 = step2_31;
        out[outOff + 0] = (int) (step1_0 + step1_31);
        out[outOff + 1] = (int) (step1_1 + step1_30);
        out[outOff + 2] = (int) (step1_2 + step1_29);
        out[outOff + 3] = (int) (step1_3 + step1_28);
        out[outOff + 4] = (int) (step1_4 + step1_27);
        out[outOff + 5] = (int) (step1_5 + step1_26);
        out[outOff + 6] = (int) (step1_6 + step1_25);
        out[outOff + 7] = (int) (step1_7 + step1_24);
        out[outOff + 8] = (int) (step1_8 + step1_23);
        out[outOff + 9] = (int) (step1_9 + step1_22);
        out[outOff + 10] = (int) (step1_10 + step1_21);
        out[outOff + 11] = (int) (step1_11 + step1_20);
        out[outOff + 12] = (int) (step1_12 + step1_19);
        out[outOff + 13] = (int) (step1_13 + step1_18);
        out[outOff + 14] = (int) (step1_14 + step1_17);
        out[outOff + 15] = (int) (step1_15 + step1_16);
        out[outOff + 16] = (int) (step1_15 - step1_16);
        out[outOff + 17] = (int) (step1_14 - step1_17);
        out[outOff + 18] = (int) (step1_13 - step1_18);
        out[outOff + 19] = (int) (step1_12 - step1_19);
        out[outOff + 20] = (int) (step1_11 - step1_20);
        out[outOff + 21] = (int) (step1_10 - step1_21);
        out[outOff + 22] = (int) (step1_9 - step1_22);
        out[outOff + 23] = (int) (step1_8 - step1_23);
        out[outOff + 24] = (int) (step1_7 - step1_24);
        out[outOff + 25] = (int) (step1_6 - step1_25);
        out[outOff + 26] = (int) (step1_5 - step1_26);
        out[outOff + 27] = (int) (step1_4 - step1_27);
        out[outOff + 28] = (int) (step1_3 - step1_28);
        out[outOff + 29] = (int) (step1_2 - step1_29);
        out[outOff + 30] = (int) (step1_1 - step1_30);
        out[outOff + 31] = (int) (step1_0 - step1_31);
    }
}
